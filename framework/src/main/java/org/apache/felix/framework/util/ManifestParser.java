/*
 *   Copyright 2006 The Apache Software Foundation
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package org.apache.felix.framework.util;

import java.util.*;

import org.apache.felix.framework.Logger;
import org.apache.felix.framework.cache.BundleRevision;
import org.apache.felix.framework.searchpolicy.*;
import org.osgi.framework.*;

public class ManifestParser
{
    private Logger m_logger = null;
    private PropertyResolver m_config = null;
    private Map m_headerMap = null;
    private R4Export[] m_exports = null;
    private R4Import[] m_imports = null;
    private R4Import[] m_dynamics = null;
    private R4LibraryClause[] m_libraryHeaders = null;
    private boolean m_libraryHeadersOptional = false;

    public ManifestParser(Logger logger, PropertyResolver config, Map headerMap)
        throws BundleException
    {
        m_logger = logger;
        m_config = config;
        m_headerMap = headerMap;

        // Verify that only manifest version 2 is specified.
        String manifestVersion = get(Constants.BUNDLE_MANIFESTVERSION);
        if ((manifestVersion != null) && !manifestVersion.equals("2"))
        {
            throw new BundleException(
                "Unknown 'Bundle-ManifestVersion' value: " + manifestVersion);
        }

        // Verify bundle version syntax.
        if (get(Constants.BUNDLE_VERSION) != null)
        {
            try
            {
                Version.parseVersion(get(Constants.BUNDLE_VERSION));
            }
            catch (RuntimeException ex)
            {
                // R4 bundle versions must parse, R3 bundle version may not.
                if (getVersion().equals("2"))
                {
                    throw ex;
                }
            }
        }

        // Create map to check for duplicate imports/exports.
        Map dupeMap = new HashMap();

        //
        // Parse Export-Package.
        //

        // Get export packages from bundle manifest.
        R4Package[] pkgs = R4Package.parseImportOrExportHeader(
            (String) headerMap.get(Constants.EXPORT_PACKAGE));

        // Create non-duplicated export array.
        dupeMap.clear();
        for (int i = 0; i < pkgs.length; i++)
        {
            if (dupeMap.get(pkgs[i].getName()) == null)
            {
                // Verify that java.* packages are not exported.
                if (pkgs[i].getName().startsWith("java."))
                {
                    throw new BundleException(
                        "Exporting java.* packages not allowed: " + pkgs[i].getName());
                }
                dupeMap.put(pkgs[i].getName(), new R4Export(pkgs[i]));
            }
            else
            {
                // TODO: FRAMEWORK - Exports can be duplicated, so fix this.
                m_logger.log(Logger.LOG_WARNING,
                    "Duplicate export - " + pkgs[i].getName());
            }
        }
        m_exports = (R4Export[]) dupeMap.values().toArray(new R4Export[dupeMap.size()]);

        //
        // Parse Import-Package.
        //

        // Get import packages from bundle manifest.
        pkgs = R4Package.parseImportOrExportHeader(
            (String) headerMap.get(Constants.IMPORT_PACKAGE));

        // Create non-duplicated import array.
        dupeMap.clear();
        for (int i = 0; i < pkgs.length; i++)
        {
            if (dupeMap.get(pkgs[i].getName()) == null)
            {
                // Verify that java.* packages are not imported.
                if (pkgs[i].getName().startsWith("java."))
                {
                    throw new BundleException(
                        "Importing java.* packages not allowed: " + pkgs[i].getName());
                }
                dupeMap.put(pkgs[i].getName(), new R4Import(pkgs[i]));
            }
            else
            {
                throw new BundleException(
                    "Duplicate import - " + pkgs[i].getName());
            }
        }
        m_imports = (R4Import[]) dupeMap.values().toArray(new R4Import[dupeMap.size()]);

        //
        // Parse DynamicImport-Package.
        //

        // Get dynamic import packages from bundle manifest.
        pkgs = R4Package.parseImportOrExportHeader(
            (String) headerMap.get(Constants.DYNAMICIMPORT_PACKAGE));

        // Dynamic imports can have duplicates, so just create an array.
        m_dynamics = new R4Import[pkgs.length];
        for (int i = 0; i < pkgs.length; i++)
        {
            m_dynamics[i] = new R4Import(pkgs[i]);
        }

        //
        // Parse Bundle-NativeCode.
        //

        // Get native library entry names for module library sources.
        m_libraryHeaders =
            Util.parseLibraryStrings(
                m_logger,
                Util.parseDelimitedString(get(Constants.BUNDLE_NATIVECODE), ","));

        // Check to see if there was an optional native library clause, which is
        // represented by a null library header; if so, record it and remove it.
        if ((m_libraryHeaders.length > 0) &&
            (m_libraryHeaders[m_libraryHeaders.length - 1].getLibraryFiles() == null))
        {
            m_libraryHeadersOptional = true;
            R4LibraryClause[] tmp = new R4LibraryClause[m_libraryHeaders.length - 1];
            System.arraycopy(m_libraryHeaders, 0, tmp, 0, m_libraryHeaders.length - 1);
            m_libraryHeaders = tmp;
        }

        // Do final checks and normalization of manifest.
        if (getVersion().equals("2"))
        {
            checkAndNormalizeR4();
        }
        else
        {
            checkAndNormalizeR3();
        }
    }

    public String get(String key)
    {
        return (String) m_headerMap.get(key);
    }

    public String getVersion()
    {
        String manifestVersion = get(Constants.BUNDLE_MANIFESTVERSION);
        return (manifestVersion == null) ? "1" : manifestVersion;
    }

    public R4Export[] getExports()
    {
        return m_exports;
    }

    public R4Import[] getImports()
    {
        return m_imports;
    }

    public R4Import[] getDynamicImports()
    {
        return m_dynamics;
    }

    public R4LibraryClause[] getLibraryClauses()
    {
        return m_libraryHeaders;
    }

    /**
     * <p>
     * This method returns the selected native library metadata from
     * the manifest. The information is not the raw metadata from the
     * manifest, but is native library metadata clause selected according
     * to the OSGi native library clause selection policy. The metadata
     * returned by this method will be attached directly to a module and
     * used for finding its native libraries at run time. To inspect the
     * raw native library metadata refer to <tt>getLibraryClauses()</tt>.
     * </p>
     * @param revision the bundle revision for the module.
     * @return an array of selected library metadata objects from the manifest.
     * @throws BundleException if any problems arise.
     */
    public R4Library[] getLibraries(BundleRevision revision) throws BundleException
    {
        R4LibraryClause clause = getSelectedLibraryClause();

        if (clause != null)
        {
            R4Library[] libraries = new R4Library[clause.getLibraryFiles().length];
            for (int i = 0; i < libraries.length; i++)
            {
                libraries[i] = new R4Library(
                    m_logger, revision, clause.getLibraryFiles()[i],
                    clause.getOSNames(), clause.getProcessors(), clause.getOSVersions(),
                    clause.getLanguages(), clause.getSelectionFilter());
            }
            return libraries;
        }
        return null;
    }

    private R4LibraryClause getSelectedLibraryClause() throws BundleException
    {
        if ((m_libraryHeaders != null) && (m_libraryHeaders.length > 0))
        {
            List clauseList = new ArrayList();

            // Search for matching native clauses.
            for (int i = 0; i < m_libraryHeaders.length; i++)
            {
                if (m_libraryHeaders[i].match(m_config))
                {
                    clauseList.add(m_libraryHeaders[i]);
                }
            }

            // Select the matching native clause.
            int selected = 0;
            if (clauseList.size() == 0)
            {
                // If optional clause exists, no error thrown.
                if (m_libraryHeadersOptional)
                {
                    return null;
                }
                else
                {
                    throw new BundleException("Unable to select a native library clause.");
                }
            }
            else if (clauseList.size() == 1)
            {
                selected = 0;
            }
            else if (clauseList.size() > 1)
            {
                selected = firstSortedClause(clauseList);
            }
            return ((R4LibraryClause) clauseList.get(selected));
        }

        return null;
    }

    private int firstSortedClause(List clauseList)
    {
        ArrayList indexList = new ArrayList();
        ArrayList selection = new ArrayList();

        // Init index list
        for (int i = 0; i < clauseList.size(); i++)
        {
            indexList.add("" + i);
        }

        // Select clause with 'osversion' range declared
        // and get back the max floor of 'osversion' ranges.
        Version osVersionRangeMaxFloor = new Version(0, 0, 0);
        for (int i = 0; i < indexList.size(); i++)
        {
            int index = Integer.parseInt(indexList.get(i).toString());
            String[] osversions = ((R4LibraryClause) clauseList.get(index)).getOSVersions();
            if (osversions != null)
            {
                selection.add("" + indexList.get(i));
            }
            for (int k = 0; (osversions != null) && (k < osversions.length); k++)
            {
                VersionRange range = VersionRange.parse(osversions[k]);
                if ((range.getLow()).compareTo(osVersionRangeMaxFloor) >= 0)
                {
                    osVersionRangeMaxFloor = range.getLow();
                }
            }
        }

        if (selection.size() == 1)
        {
            return Integer.parseInt(selection.get(0).toString());
        }
        else if (selection.size() > 1)
        {
            // Keep only selected clauses with an 'osversion'
            // equal to the max floor of 'osversion' ranges.
            indexList = selection;
            selection = new ArrayList();
            for (int i = 0; i < indexList.size(); i++)
            {
                int index = Integer.parseInt(indexList.get(i).toString());
                String[] osversions = ((R4LibraryClause) clauseList.get(index)).getOSVersions();
                for (int k = 0; k < osversions.length; k++)
                {
                    VersionRange range = VersionRange.parse(osversions[k]);
                    if ((range.getLow()).compareTo(osVersionRangeMaxFloor) >= 0)
                    {
                        selection.add("" + indexList.get(i));
                    }
                }
            }
        }

        if (selection.size() == 0)
        {
            // Re-init index list.
            selection.clear();
            indexList.clear();
            for (int i = 0; i < clauseList.size(); i++)
            {
                indexList.add("" + i);
            }
        }
        else if (selection.size() == 1)
        {
            return Integer.parseInt(selection.get(0).toString());
        }
        else
        {
            indexList = selection;
            selection.clear();
        }

        // Keep only clauses with 'language' declared.
        for (int i = 0; i < indexList.size(); i++)
        {
            int index = Integer.parseInt(indexList.get(i).toString());
            if (((R4LibraryClause) clauseList.get(index)).getLanguages() != null)
            {
                selection.add("" + indexList.get(i));
            }
        }

        // Return the first sorted clause
        if (selection.size() == 0)
        {
            return 0;
        }
        else
        {
            return Integer.parseInt(selection.get(0).toString());
        }
    }

    private void checkAndNormalizeR3() throws BundleException
    {
        // Check to make sure that R3 bundles have only specified
        // the 'specification-version' attribute and no directives
        // on their exports.
        for (int i = 0; (m_exports != null) && (i < m_exports.length); i++)
        {
            if (m_exports[i].getDirectives().length != 0)
            {
                throw new BundleException("R3 exports cannot contain directives.");
            }
            // NOTE: This is checking for "version" rather than "specification-version"
            // because the package class normalizes to "version" to avoid having
            // future special cases. This could be changed if more strict behavior
            // is required.
            if ((m_exports[i].getAttributes().length > 1) ||
                ((m_exports[i].getAttributes().length == 1) &&
                    (!m_exports[i].getAttributes()[0].getName().equals(Constants.VERSION_ATTRIBUTE))))
            {
                throw new BundleException(
                    "R3 export syntax does not support attributes: " + m_exports[i]);
            }
        }
        
        // Check to make sure that R3 bundles have only specified
        // the 'specification-version' attribute and no directives
        // on their imports.
        for (int i = 0; (m_imports != null) && (i < m_imports.length); i++)
        {
            if (m_imports[i].getDirectives().length != 0)
            {
                throw new BundleException("R3 imports cannot contain directives.");
            }
            // NOTE: This is checking for "version" rather than "specification-version"
            // because the package class normalizes to "version" to avoid having
            // future special cases. This could be changed if more strict behavior
            // is required.
            if ((m_imports[i].getVersionHigh() != null) ||
                (m_imports[i].getAttributes().length > 1) ||
                ((m_imports[i].getAttributes().length == 1) &&
                    (!m_imports[i].getAttributes()[0].getName().equals(Constants.VERSION_ATTRIBUTE))))
            {
                throw new BundleException(
                    "R3 import syntax does not support attributes: " + m_imports[i]);
            }
        }

        // Since all R3 exports imply an import, add a corresponding
        // import for each existing export. Create non-duplicated import array.
        Map map =  new HashMap();
        // Add existing imports.
        for (int i = 0; i < m_imports.length; i++)
        {
            map.put(m_imports[i].getName(), m_imports[i]);
        }
        // Add import for each export.
        for (int i = 0; i < m_exports.length; i++)
        {
            if (map.get(m_exports[i].getName()) == null)
            {
                map.put(m_exports[i].getName(), new R4Import(m_exports[i]));
            }
        }
        m_imports =
            (R4Import[]) map.values().toArray(new R4Import[map.size()]);

        // Add a "uses" directive onto each export of R3 bundles
        // that references every other import (which will include
        // exports, since export implies import); this is
        // necessary since R3 bundles assumed a single class space,
        // but R4 allows for multiple class spaces.
        String usesValue = "";
        for (int i = 0; (m_imports != null) && (i < m_imports.length); i++)
        {
            usesValue = usesValue
                + ((usesValue.length() > 0) ? "," : "")
                + m_imports[i].getName();
        }
        R4Directive uses = new R4Directive(
            Constants.USES_DIRECTIVE, usesValue);
        for (int i = 0; (m_exports != null) && (i < m_exports.length); i++)
        {
            m_exports[i] = new R4Export(
                m_exports[i].getName(),
                new R4Directive[] { uses },
                m_exports[i].getAttributes());
        }

        // Check to make sure that R3 bundles have no attributes or
        // directives on their dynamic imports.
        for (int i = 0; (m_dynamics != null) && (i < m_dynamics.length); i++)
        {
            if (m_dynamics[i].getDirectives().length != 0)
            {
                throw new BundleException("R3 dynamic imports cannot contain directives.");
            }
            if (m_dynamics[i].getAttributes().length != 0)
            {
                throw new BundleException("R3 dynamic imports cannot contain attributes.");
            }
        }
    }

    private void checkAndNormalizeR4() throws BundleException
    {
        // Verify that bundle symbolic name is specified.
        String symName = get(Constants.BUNDLE_SYMBOLICNAME);
        if (symName == null)
        {
            throw new BundleException("R4 bundle manifests must include bundle symbolic name.");
        }

        // Verify that the exports do not specify bundle symbolic name
        // or bundle version.
        for (int i = 0; (m_exports != null) && (i < m_exports.length); i++)
        {
            String targetVer = get(Constants.BUNDLE_VERSION);
            targetVer = (targetVer == null) ? "0.0.0" : targetVer;

            R4Attribute[] attrs = m_exports[i].getAttributes();
            for (int attrIdx = 0; attrIdx < attrs.length; attrIdx++)
            {
                // Find symbolic name and version attribute, if present.
                if (attrs[attrIdx].getName().equals(Constants.BUNDLE_VERSION_ATTRIBUTE) ||
                    attrs[attrIdx].getName().equals(Constants.BUNDLE_SYMBOLICNAME_ATTRIBUTE))
                {
                    throw new BundleException(
                        "Exports must not specify bundle symbolic name or bundle version.");
                }
            }

            // Now that we know that there are no bundle symbolic name and version
            // attributes, add them since the spec says they are there implicitly.
            R4Attribute[] newAttrs = new R4Attribute[attrs.length + 2];
            System.arraycopy(attrs, 0, newAttrs, 0, attrs.length);
            newAttrs[attrs.length] = new R4Attribute(
                Constants.BUNDLE_SYMBOLICNAME_ATTRIBUTE, symName, false);
            newAttrs[attrs.length + 1] = new R4Attribute(
                Constants.BUNDLE_VERSION_ATTRIBUTE, Version.parseVersion(targetVer), false);
            m_exports[i] = new R4Export(
                m_exports[i].getName(), m_exports[i].getDirectives(), newAttrs);
        }
    }
}