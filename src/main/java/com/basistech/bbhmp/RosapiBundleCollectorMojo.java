/*
* Copyright 2016 Basis Technology Corp.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.basistech.bbhmp;

import javanet.staxutils.IndentingXMLStreamWriter;
import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.interpolation.fixed.FixedStringSearchInterpolator;
import org.codehaus.plexus.interpolation.fixed.PropertiesBasedValueSource;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Read one or more XML files specifying OSGi bundles, and copy them, setting
 * up a metadata file that describes them.
 * The input files look like:
 * <pre>
 * {@code
 <?xml version='1.0' encoding='utf-8'?>
 <bundles>
   <level level="1">
     <bundle>commons-io/commons-io/-dependency-</bundle>
     <bundle>com.google.inject.extensions/guice-throwingproviders/4.0</bundle>
   </level>
   <level level="2">
     <bundle>com.google.inject.extensions/guice-throwingproviders/4.0</bundle>
   </level>
    </bundles>
}
 * </pre>
 * If a bundle is a fragment, this plugin will notice and arrange <i>not</i> to start it
 * at runtime. If you want to avoid starting some bundle that is not a fragment, add
 * {@code noStart='true'} to the {@code <bundle/>} element.
 *
 */
@Mojo(name = "collect-bundles", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class RosapiBundleCollectorMojo extends AbstractMojo {

    @Parameter(required = true)
    List<File> bundleInfoFiles;


    @Parameter(defaultValue = "${project.build.directory}/bundles")
    File outputDirectory;

    /**
     * What start level to apply to bundles that have no explicit start level.
     */
    @Parameter(defaultValue = "80")
    int defaultStartLevel;

    /**
     * POM
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /**
     * Log at the bundle level.
     */
    @Parameter
    boolean verboseBundles;

    /**
     * Skip missing artifacts.
     */
    @Parameter
    boolean skipMissingArtifacts;


    /**
     * Log at the feature level
     */
    @Parameter(defaultValue = "true")
    boolean verboseFeatures;

    /**
     * Used to look up Artifacts in the remote repository.
     */
    @Component
    ArtifactResolver resolver;

    /**
     * Location of the local repository.
     */
    @Parameter(defaultValue = "${localRepository}", readonly = true, required = true)
    ArtifactRepository local;

    /**
     * Contains the full list of projects in the reactor.
     */
    @Parameter(defaultValue = "${reactorProjects}", readonly = true)
    List<MavenProject> reactorProjects;

    /**
     * List of Remote Repositories used by the resolver
     */
    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true, required = true)
    List<ArtifactRepository> remoteRepos;

    /**
     * Used to look up Artifacts in the remote repository.
     */
    @Component
    ArtifactFactory factory;

    private FixedStringSearchInterpolator interpolator;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        Properties additional = new Properties();
        // do we need others?
        additional.put("project.version", project.getVersion());

        interpolator = FixedStringSearchInterpolator.create(new PropertiesBasedValueSource(project.getProperties()),
                new PropertiesBasedValueSource(additional));

        processInputs();

    }


    private void processInputs() throws MojoFailureException, MojoExecutionException {

        Map<String, BundleSpec> bundlesByGav = new HashMap<>();
        Map<Integer, List<BundleSpec>> bundlesByLevel = new TreeMap<>(); // keep those levels in order


        /* read, check, merge, input files. */
        if (bundleInfoFiles.size() == 0) {
            throw new MojoFailureException("No input files provided");
        }

        for (File bif : bundleInfoFiles) {
            BundlesInfo info;
            try {
                info = BundlesInfo.read(bif.toPath());
            } catch (IOException e) {
                throw new MojoFailureException("Unable to read " + bif.getAbsolutePath(), e);
            }
            for (LevelBundles levelBundles : info.levels) {
                for (BundleInfo bi : levelBundles.bundles) {
                    processBundle(levelBundles.level, bi, bundlesByGav, bundlesByLevel);
                }
            }
        }
        /* Specs are all sitting in the map. Files are all copied. */
        writeMetadata(bundlesByLevel);
    }


    private void processBundle(int level, BundleInfo bundle,
                                     Map<String, BundleSpec> bundlesByGav,
                                     Map<Integer, List<BundleSpec>> bundlesByLevel
    ) throws MojoExecutionException, MojoFailureException {

        Artifact artifact = null;
        try {
            artifact = getArtifact(bundle);
        } catch (MojoExecutionException e) {
            if (skipMissingArtifacts) {
                // just skip
                return;
            }
            throw e;
        }


        if (verboseBundles) {
            getLog().info(String.format("Bundle %s included", artifact.getId()));
        }

        String gav;
        if (artifact.getClassifier() != null) {
            gav = String.format("%s:%s:%s:%s", artifact.getGroupId(), artifact.getArtifactId(), artifact.getClassifier(), artifact.getVersion());
        } else {
            gav = String.format("%s:%s::%s", artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
        }

        BundleSpec prior = bundlesByGav.get(gav);
        int effLevel;
        String filename;
        boolean start;
        if (prior != null) {
            start = prior.start;
            effLevel = Math.min(prior.level, level);
            if (effLevel != level) {
                getLog().info(String.format("Multiple levels for %s; choosing %d", gav, effLevel));
            }
            bundlesByLevel.get(prior.level).remove(prior);
            bundlesByGav.remove(gav);
            filename = prior.filename;
        } else {
            effLevel = level;
            filename = String.format("%s-%s-%s.jar", artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
            File outputFile = new File(outputDirectory, filename);
            copyFile(artifact.getFile(), outputFile);
            start = bundle.start && !isJarFragment(outputFile);

        }

        BundleSpec spec = new BundleSpec(gav, effLevel, start, filename);
        bundlesByGav.put(gav, spec);

        List<BundleSpec> levelSpecs = bundlesByLevel.get(effLevel);
        if (levelSpecs == null) {
            levelSpecs = new ArrayList<>();
            bundlesByLevel.put(effLevel, levelSpecs);
        }
        levelSpecs.add(spec);
    }

    private boolean isJarFragment(File outputFile) throws MojoExecutionException, MojoFailureException {
        JarFile jar;
        try {
            jar = new JarFile(outputFile);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to open dependency we just copied " + outputFile.getAbsolutePath(), e);
        }
        final Manifest manifest;
        try {
            manifest = jar.getManifest();
        } catch (IOException e) {
            throw new MojoFailureException("Failed to read manifest from dependency we just copied " + outputFile.getAbsolutePath(), e);
        }
        final Attributes mattr = manifest.getMainAttributes();
        // getValue is case-insensitive.
        String mfVersion = mattr.getValue("Bundle-ManifestVersion");
        /*
         * '2' is the only legitimate bundle manifest version. Version 1 is long obsolete, and not supported
         * in current containers. There's no plan on the horizon for a version 3. No version at all indicates
         * that the jar file is not an OSGi bundle at all.
         */
        if (!"2".equals(mfVersion)) {
            throw new MojoFailureException("Bundle-ManifestVersion is not '2' from dependency we just copied " + outputFile.getAbsolutePath());
        }
        String host = mattr.getValue("Fragment-Host");
        return host != null;
    }

    private void writeMetadata(Map<Integer, List<BundleSpec>> bundlesByLevel) throws MojoExecutionException {
        OutputStream os = null;
        File md = new File(outputDirectory, "bundles.xml");

        try {
            os = new FileOutputStream(md);
            XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(os);
            writer = new IndentingXMLStreamWriter(writer);
            writer.writeStartDocument("utf-8", "1.0");
            writer.writeStartElement("bundles");
            for (Integer level : bundlesByLevel.keySet()) {
                writer.writeStartElement("level");
                writer.writeAttribute("level", Integer.toString(level));
                for (BundleSpec spec : bundlesByLevel.get(level)) {
                    writer.writeStartElement("bundle");
                    writer.writeAttribute("start", Boolean.toString(spec.start));
                    writer.writeCharacters(spec.filename);
                    writer.writeEndElement();
                }
                writer.writeEndElement();
            }
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.flush();

        } catch (IOException | XMLStreamException e) {
            throw new MojoExecutionException("Failed to write metadata file " + md.toString(), e);
        } finally {
            IOUtils.closeQuietly(os);
        }
    }

    private void copyFile(File artifact, File destFile) throws MojoExecutionException {
        try {
            getLog().info("Copying " + artifact.getAbsolutePath() + destFile);

            if (artifact.isDirectory()) {
                throw new MojoExecutionException("Artifact has not been packaged yet.");
            }

            FileUtils.copyFile(artifact, destFile);
        } catch (IOException e) {
            throw new MojoExecutionException("Error copying artifact from " + artifact + " to " + destFile, e);
        }
    }


    private String getArtifactVersionFromDependencies(String groupId, String artifactId) {
        for (Artifact dep : project.getArtifacts()) {
            if (groupId.equals(dep.getGroupId()) && artifactId.equals(dep.getArtifactId())) {
                getLog().debug(String.format("Found dependency %s:%s:%s", groupId, artifactId, dep.getVersion()));
                return dep.getVersion();
            }
        }
        return null;
    }

    private Artifact getArtifact(BundleInfo bundle) throws MojoExecutionException, MojoFailureException {
        Artifact artifact;

        /*
         * Anything in the gav may be interpolated.
         * Version may be "-dependency-" to look for the artifact as a dependency.
         */

        String gav = interpolator.interpolate(bundle.gav);
        String[] pieces = gav.split("/");
        String groupId = pieces[0];
        String artifactId = pieces[1];
        String versionStr;
        String classifier = null;
        if (pieces.length == 3) {
            versionStr = pieces[2];
        } else {
            classifier = pieces[2];
            versionStr = pieces[3];
        }

        if ("-dependency-".equals(versionStr)) {
            versionStr = getArtifactVersionFromDependencies(groupId, artifactId);
            if (versionStr == null) {
                throw new MojoFailureException(String.format("Request for %s:%s as a dependency, but it is not a dependency", groupId, artifactId));
            }
        }

        VersionRange vr;
        try {
            vr = VersionRange.createFromVersionSpec(versionStr);
        } catch (InvalidVersionSpecificationException e1) {
            throw new MojoExecutionException("Bad version range " + versionStr, e1);
        }

        artifact = factory.createDependencyArtifact(groupId, artifactId, vr,
                "jar", classifier, Artifact.SCOPE_COMPILE);

        // Maven 3 will search the reactor for the artifact but Maven 2 does not
        // to keep consistent behaviour, we search the reactor ourselves.
        Artifact result = getArtifactFomReactor(artifact);
        if (result != null) {
            return result;
        }

        try {
            resolver.resolve(artifact, remoteRepos, local);
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException("Unable to resolve artifact.", e);
        } catch (ArtifactNotFoundException e) {
            throw new MojoExecutionException("Unable to find artifact.", e);
        }

        return artifact;
    }

    /**
     * Checks to see if the specified artifact is available from the reactor.
     *
     * @param artifact The artifact we are looking for.
     * @return The resolved artifact that is the same as the one we were looking for or <code>null</code> if one could
     * not be found.
     */
    private Artifact getArtifactFomReactor(Artifact artifact) {
        // check project dependencies first off
        for (Artifact a : project.getArtifacts()) {
            if (equals(artifact, a) && hasFile(a)) {
                return a;
            }
        }

        // check reactor projects
        for (MavenProject p : reactorProjects == null ? Collections.<MavenProject>emptyList() : reactorProjects) {
            // check the main artifact
            if (equals(artifact, p.getArtifact()) && hasFile(p.getArtifact())) {
                return p.getArtifact();
            }

            // check any side artifacts
            for (Artifact a : (List<Artifact>) p.getAttachedArtifacts()) {
                if (equals(artifact, a) && hasFile(a)) {
                    return a;
                }
            }
        }

        // not available
        return null;
    }

    /**
     * Returns <code>true</code> if the artifact has a file.
     *
     * @param artifact the artifact (may be null)
     * @return <code>true</code> if and only if the artifact is non-null and has a file.
     */
    private static boolean hasFile(Artifact artifact) {
        return artifact != null && artifact.getFile() != null && artifact.getFile().isFile();
    }

    /**
     * Null-safe compare of two artifacts based on groupId, artifactId, version, type and classifier.
     *
     * @param a the first artifact.
     * @param b the second artifact.
     * @return <code>true</code> if and only if the two artifacts have the same groupId, artifactId, version,
     * type and classifier.
     */
    private static boolean equals(Artifact a, Artifact b) {
        //CHECKSTYLE:OFF
        return a == b || !(a == null || b == null)
                && StringUtils.equals(a.getGroupId(), b.getGroupId())
                && StringUtils.equals(a.getArtifactId(), b.getArtifactId())
                && StringUtils.equals(a.getVersion(), b.getVersion())
                && StringUtils.equals(a.getType(), b.getType())
                && StringUtils.equals(a.getClassifier(), b.getClassifier());
        //CHECKSTYLE:ON
    }
}
