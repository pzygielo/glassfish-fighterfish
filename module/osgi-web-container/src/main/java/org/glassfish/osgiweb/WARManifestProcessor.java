/*
 * Copyright (c) 2009, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.osgiweb;

import org.glassfish.osgijavaeebase.JarHelper;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;

import java.util.jar.Attributes.Name;
import static org.glassfish.osgiweb.Constants.WEB_CONTEXT_PATH;
import static org.osgi.framework.Constants.*;

/**
 * When a deployer installs a bundle with
 * {@link Constants#WEB_BUNDLE_SCHEME},
 * our registered handler gets a chance to look at the stream and process the
 * MANIFEST.MF. It adds necessary OSGi metadata as specified in section #5.2.1.2
 * of RFC #66. It uses the following information during computation:
 * - WAR manifest entries, i.e., developer supplied data
 * - Properties supplied via URL query parameters
 * - Other information present in the WAR, e.g., existence of any jar in
 * WEB-INF/lib causes that jar to be added as Bundle-ClassPath.
 * For exact details, refer to the spec.
 *
 * @author Sanjeeb.Sahoo@Sun.COM
 */
public class WARManifestProcessor
{
    private static Logger logger =
            Logger.getLogger(WARManifestProcessor.class.getPackage().getName());
    private static final String DEFAULT_MAN_VERSION = "2";
    private static final String DEFAULT_IMPORT_PACKAGE =
            "javax.servlet; javax.servlet.http; version=2.5, " +
                    "javax.servlet.jsp; javax.servlet.jsp.tagext;" +
                    "javax.el; javax.servlet.jsp.el; version=2.1";
    // We always add WEB-INF/classes/, because not adding has the adverse
    // side effect of Bundle-ClassPath defaulting to "." by framework
    // in case there is no lib jar. Don't end with '/' as rfc66 ct does not like it.
    private static final String DEFAULT_BUNDLE_CP = "WEB-INF/classes";
    private static final AtomicInteger nextBsnId = new AtomicInteger();

    private static final String DEFAULT_BSN_PREFIX = "org.glassfish.fighterfish.autogenerated_";

    /**
     * These are the query parameters that are understood by this manifest processor. Any other params are directly applied to
     * the final manifest. More over, as per section 128.4.4, presence of any of these params mean the input is a WAB. When it is a WAB,
     * we only allow the Web-ContextPath to be customized.
     */
    private static Name[] supportedQueryParamNames = {
        new Name(BUNDLE_SYMBOLICNAME),
            new Name(BUNDLE_VERSION),
            new Name(BUNDLE_MANIFESTVERSION),
            new Name(IMPORT_PACKAGE),
            new Name(WEB_CONTEXT_PATH),
    };

    public static Map<String, String> readQueryParams(String query)
    {
        Map<String, String> queryParams = new HashMap<String, String>();
        if (query != null)
        {
            logger.logp(Level.FINE, "WARManifestProcessor", "readQueryParams",
                    "Input query params = {0}", new Object[]{query});
            // "&" separates query paremeters
            StringTokenizer st = new StringTokenizer(query, "&");
            while (st.hasMoreTokens())
            {
                String next = st.nextToken();
                int eq = next.indexOf("=");
                String name = next, value = null;
                if (eq != -1)
                {
                    name = next.substring(0, eq);
                    if ((eq + 1) < next.length())
                    {
                        value = next.substring(eq + 1);
                    }
                }
                // Canonicalize parameter names. The spec says that the query param names can be case insensitive.
                for (Name supportedQueryParamName : supportedQueryParamNames) {
                    if (supportedQueryParamName.toString().equalsIgnoreCase(name)) {
                        name = supportedQueryParamName.toString();
                    }
                }
                queryParams.put(name, value);
            }
            logger.logp(Level.FINE, "WARManifestProcessor", "readQueryParams",
                    "Canonicalized query params = {0}", new Object[]{queryParams});
        }
        return queryParams;
    }

    /**
     * Reads content of the given URL, uses it to come up with a new Manifest.
     *
     * @param url URL which is used to read the original Manifest and other data
     * @param query extra parameters passed by deployer
     * @return a new Manifest
     * @throws java.io.IOException if IO related error occurs
     */
    public static Manifest processManifest(URL url, String query) throws IOException
    {
        final JarInputStream jis = new JarInputStream(url.openStream());
        try {
            Manifest oldManifest = jis.getManifest();
            Manifest newManifest = new Manifest(oldManifest);
            Attributes attrs = newManifest.getMainAttributes();
            Map<String, String> queryParams = readQueryParams(decode(query));

            // For WAB Modification, the Web URL Handler must only support the Web-ContextPath parameter
            // and it must not modify any existing headers other than the Web-ContextPath. Any other parameter
            // given must result in a Bundle Exception. See section: 128.4.4 of r4.2 spec.
            // Since Web-ContextPath must always be set, we can safely assume that size() can only be 1
            if (isWAB(jis)) {
                if (queryParams.keySet().size() != 1) {
                    throw new IllegalArgumentException("Only Web-ContextPath can be customized using webbundle scheme for a WAB");
                }
                processWCP(queryParams, attrs);
            } else {
                processWCP(queryParams, attrs);
                processBMV(queryParams, attrs);
                processBSN(queryParams, attrs);
                processBV(queryParams, attrs);
                processBCP(queryParams, attrs, jis);
                processIP(queryParams, attrs);

                // We add this attribute until we have added support for
                // scanning class bytes to figure out import dependencies.
                attrs.putValue(DYNAMICIMPORT_PACKAGE, "*");

                // remove all signatures as per section 128.4.6 of the r42 spec.
                processSignatures(newManifest);
            }
            logger.logp(Level.FINE, "WARManifestProcessor", "processManifest", "New Attributes of the bundle = {0}", new Object[]{attrs});
            newManifest.write(System.err); // for debugging purpose, write this out
            return newManifest;
        } finally {
            jis.close();
        }
    }

    private static String decode(String encodedQuery) {
        logger.logp(Level.FINE, "WARManifestProcessor", "decode", "encodedQuery = {0}", new Object[]{encodedQuery});
        String decodedQuery;
        try {
            decodedQuery = new URI("http://localhost/index.html?" + encodedQuery).getQuery();
        } catch (URISyntaxException e) {
            // Assume this is already decoded and proceeed. This is needed to work around OSGi CT issue #1736
            logger.logp(Level.INFO, "WARManifestProcessor", "decode", "Assuming this is already decoded because of {0} ", new Object[]{e});
            decodedQuery = encodedQuery;
        }
        logger.logp(Level.FINE, "WARManifestProcessor", "decode", "decodedQuery = {0}", new Object[]{decodedQuery});
        return decodedQuery;
    }

    private static boolean isWAB(JarInputStream jis) {
        Attributes attrs = jis.getManifest().getMainAttributes();
        return !Collections.disjoint(attrs.keySet(), Arrays.asList(supportedQueryParamNames));
    }

    private static void processWCP(Map<String, String> queryParams, Attributes attrs) {
        String contextPath = queryParams.get(Constants.WEB_CONTEXT_PATH);
        if (!contextPath.startsWith("/")) {
            contextPath = "/" + contextPath; // spec requires us to prefix '/' if not there.
        }
        attrs.putValue(Constants.WEB_CONTEXT_PATH, contextPath);
    }

    private static void processBMV(Map<String, String> queryParams, Attributes attrs) {
        process(queryParams, attrs, BUNDLE_MANIFESTVERSION, DEFAULT_MAN_VERSION);
    }

    private static void processBSN(Map<String, String> queryParams, Attributes attrs) {
        // We generate symbolic name by incrementing a number and appending it to a fixed string
        String defaultSymName = DEFAULT_BSN_PREFIX + nextBsnId.getAndIncrement();
        process(queryParams, attrs, BUNDLE_SYMBOLICNAME,
                defaultSymName);
    }

    private static void processBV(Map<String, String> queryParams, Attributes attrs) {
        process(queryParams, attrs, BUNDLE_VERSION, null);
    }

    private static void processBCP(Map<String, String> queryParams, Attributes attrs, final JarInputStream jis) throws IOException {
        final List<String> libs = new ArrayList<String>();

        final List<String> jarNames = new ArrayList<String>();

        JarHelper.accept(jis, new JarHelper.Visitor()
        {
            public void visit(JarEntry je)
            {
                String name = je.getName();
                String LIB_DIR = "WEB-INF/lib/";
                String JAR_EXT = ".jar";
                if (!je.isDirectory() && name.endsWith(JAR_EXT)) {
                    jarNames.add(name);
                    if (name.startsWith(LIB_DIR)) {
                        String jarName = name.substring(LIB_DIR.length());
                        if (!jarName.contains("/"))
                        {
                            // only jar files directly in lib dir are considered
                            // as library jars.
                            libs.add(name);
                            // calculated classpaths referenced from this jar
                            try {
                                JarInputStream libJarIs = new JarInputStream(jis);
                                try {
                                    String classPath =
                                            libJarIs.getManifest().getMainAttributes().getValue(Name.CLASS_PATH);
                                    if (classPath != null && !classPath.isEmpty()) {
                                        logger.logp(Level.FINE, "WARManifestProcessor", "visit",
                                                "jar {0} has a Class-Path entry of {1}", new Object[]{name, classPath});
                                        try {
                                            String referencedJarName = null;
                                            referencedJarName = URI.create(name).resolve(classPath).toString();
                                            logger.logp(Level.INFO, "WARManifestProcessor", "visit",
                                                    "Resolved Class-Path {0} to entry name {1} ", new Object[]{classPath, referencedJarName});
                                            if (!libs.contains(referencedJarName)) {
                                                libs.add(referencedJarName);
                                            }
                                            // TODO(Sahoo): Ideally we are supposed to traverse the dependency, but some other time.
                                        } catch (Exception e) {
                                            logger.logp(Level.WARNING, "WARManifestProcessor", "visit", "Unexpected exception while trying to compute referenced classpath for " + name, e);
                                        }
                                    }
                                } finally {
                                    libJarIs.closeEntry();
                                }
                            } catch (IOException e) {
                                throw new RuntimeException(e); // TODO(Sahoo): Proper Exception Handling
                            }
                        }
                    }
                }
            }
        });
        String cp = convertToCP(libs, jarNames);
        cp = cp.length() > 0 ?
                DEFAULT_BUNDLE_CP.concat(",").concat(cp) : DEFAULT_BUNDLE_CP;
        logger.logp(Level.FINE, "WARManifestProcessor", "processBCP", "cp = {0}", new Object[]{cp});
        process(queryParams, attrs, BUNDLE_CLASSPATH, cp);
    }

    private static void processIP(Map<String, String> queryParams, Attributes attrs) {
        process(queryParams, attrs, IMPORT_PACKAGE, DEFAULT_IMPORT_PACKAGE);
    }

    private static void processSignatures(Manifest manifest) {
        for (Attributes attrs : manifest.getEntries().values()) {
            for (Object key : attrs.keySet().toArray()) { // toArray() is called to clone the attrs, as we want to modify attrs in the loop
                String keyName = key.toString();
                // Refer to http://download.oracle.com/javase/6/docs/technotes/guides/jar/jar.html#Per-Entry Attributes
                // The signature attributes are x-Digest-y or x-Digest and Magic
                if (keyName.endsWith("-Digest") || keyName.contains("-Digest-") || keyName.equals("Magic")) {
                    attrs.remove(key);
                }
            }
        }
    }

    private static String convertToCP(List<String> cpJars, List<String> allJarNames)
    {
        StringBuilder cp = new StringBuilder();
        for (String cpJar : cpJars) {
            if (!allJarNames.contains(cpJar)) {
                logger.logp(Level.INFO, "WARManifestProcessor", "convertToCP", "Excluding {0}, as there is no jar by this name in the war", new Object[]{cpJar});
                continue;
            }
            if (cp.length() > 0) cp.append(",");
            cp.append(cpJar);
        }
        return cp.toString();
    }

    private static void process(Map<String, String> deployerOptions,
                                Attributes developerOptions,
                                String key,
                                String defaultOption)
    {
        String deployerOption = deployerOptions.get(key);
        String developerOption = developerOptions.getValue(key);
        String finalOption = defaultOption;
        if (deployerOption != null) {
            finalOption = deployerOption;
        } else if (developerOption != null) {
            finalOption = developerOption;
        }
        if (finalOption!=null && !finalOption.equals(developerOption))
        {
            developerOptions.putValue(key, finalOption);
        }
    }

}
