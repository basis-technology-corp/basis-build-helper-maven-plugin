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
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.interpolation.fixed.FixedStringSearchInterpolator;
import org.codehaus.plexus.interpolation.fixed.PropertiesBasedValueSource;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;
import shaded.org.apache.commons.io.IOUtils;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Read a Rosapi bundles.xml and collect all the bundles.
 */
@Mojo(name = "collect-bundles", defaultPhase = LifecyclePhase.PREPARE_PACKAGE)
public class RosapiBundleCollectorMojo extends AbstractMojo {

    @Parameter(required = true)
    File bundleInfoFile;

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

    private Set<String> bundlesProcessed;
    private FixedStringSearchInterpolator interpolator;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        bundlesProcessed = new HashSet<>();

        Properties additional = new Properties();
        // do we need others?
        additional.put("project.version", project.getVersion());

        interpolator = FixedStringSearchInterpolator.create(new PropertiesBasedValueSource(project.getProperties()),
                new PropertiesBasedValueSource(additional));

        BundlesInfo bundleInfo;
        try {
            bundleInfo = BundlesInfo.read(bundleInfoFile.toPath());
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read " + bundleInfoFile.toString(), e);
        }
        for (LevelBundles levelBundles : bundleInfo.levels) {
            for (BundleInfo bundle : levelBundles.bundles) {
                processBundle(bundle);
            }
        }
        writeMetadata(bundleInfo);
    }


    private void processBundle(BundleInfo bundle) throws MojoExecutionException, MojoFailureException {
        Artifact artifact = getArtifact(bundle);
        if (verboseBundles) {
            getLog().info(String.format("Bundle %s included", artifact.getId()));
        }

        String outputFilename = String.format("%s-%s-%s.jar", artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
        if (bundlesProcessed.contains(outputFilename)) {
            if (verboseBundles) {
                getLog().info(String.format("Bundle %s duplicated", artifact.getId()));
            }
            return;
        }

        File outputFile = new File(outputDirectory, outputFilename);
        copyFile(artifact.getFile(), outputFile);
        bundle.setFilename(outputFile.getName());
    }

    private void writeMetadata(BundlesInfo info) throws MojoExecutionException {
        OutputStream os = null;
        File md = new File(outputDirectory, "bundles.xml");

        try {
            os = new FileOutputStream(md);
            XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(os);
            writer = new IndentingXMLStreamWriter(writer);
            writer.writeStartDocument("utf-8", "1.0");
            writer.writeStartElement("bundles");
            for (LevelBundles levelBundles : info.levels) {
                writer.writeStartElement("level");
                writer.writeAttribute("level", Integer.toString(levelBundles.level));
                for (BundleInfo bi : levelBundles.bundles) {
                    writer.writeStartElement("bundle");
                    writer.writeAttribute("start", Boolean.toString(bi.start));
                    writer.writeCharacters(bi.gav);
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

    protected Artifact getArtifact(BundleInfo bundle) throws MojoExecutionException, MojoFailureException {
        Artifact artifact;

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
