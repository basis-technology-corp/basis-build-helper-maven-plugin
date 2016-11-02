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
import org.apache.karaf.features.internal.model.Bundle;
import org.apache.karaf.features.internal.model.Feature;
import org.apache.karaf.features.internal.model.Features;
import org.apache.karaf.features.internal.model.JaxbUtil;
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
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;
import shaded.org.apache.commons.io.IOUtils;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turn a Karaf features.xml into a collection of jar files and a simpler metadata file.
 * This class exists to allow a transition away from Karaf without leaping immediately
 * into the complexity of bndtools. Its job is to read a features.xml, download each
 * of the bundles, and write out an XML file that lists them, organized by start level.
 * Note that this is completely ignorant of the configuration admin 'features' of features;
 * it is up to someone else to set up any required configuration.
 */
@Mojo(name = "repackage-karaf-features", defaultPhase = LifecyclePhase.PREPARE_PACKAGE)
public class KarafFeatureConverterMojo extends AbstractMojo {

    /**
     * The feature file to process. The default is not very useful, since this
     * will usually be a file built elsewhere. We could make this look in the dependency
     * graph, but that seems too much trouble. The pom might grab it with the maven-dependency-plugin.
     */
    @Parameter(required = true)
    List<File> featuresFiles;

    @Parameter(defaultValue = "${project.build.directory}/bundles")
    File outputDirectory;

    /**
     * What start level to apply to bundles that have no explicit start level.
     */
    @Parameter(defaultValue = "70")
    int defaultStartLevel;

    /**
     * POM
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /**
     * Select a specific collection of features.
     */
    @Parameter
    Set<String> features;

    /**
     * Select features by name by taking pot luck from feature files.
     */
    @Parameter
    Set<String> featureIncludes;

    /**
     * Exclude features by name
     */
    @Parameter
    Set<String> featureExcludes;

    /**
     * Include bundles by GAV patterns: group:artifact:classifier:type patterns
     */
    @Parameter
    Set<String> bundleIncludes;

    /**
     * Exclude bundles by GAV patterns: group:artifact:classifier:type patterns
     */
    @Parameter
    Set<String> bundleExcludes;

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

    private Map<Integer, List<BundleInfo>> accumulatedBundles;
    private IncludeExcludeArtifactFilter bundleFilter;
    private Set<String> bundlesProcessed;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        accumulatedBundles = new TreeMap<>();
        /*
         * If a bundle turns up twice, we're going to believe the first start level we see.
         * We may need something more complex to ensure that we take, instead, the smallest start level
         * that we see.
         */
        bundlesProcessed = new HashSet<>();
        bundleFilter = new IncludeExcludeArtifactFilter(bundleIncludes, bundleExcludes, null);

        for (File featuresFile : featuresFiles) {
            processOneFeatureFile(featuresFile);
        }

        if (features != null && !features.isEmpty()) {
            for (String feature : features) {
                getLog().error("Feature not found: " + feature);
            }
            throw new MojoExecutionException("Not all features were found.");
        }

        writeMetadata();
    }

    private void processOneFeatureFile(File featuresFile) throws MojoExecutionException, MojoFailureException {
        InputStream is;
        Features featuresFromXml;
        try {
            is = new FileInputStream(featuresFile);
            featuresFromXml = JaxbUtil.unmarshal(featuresFile.toURI().toString(), is, true);
        } catch (IOException ex) {
            throw new MojoExecutionException("Failed to read " + featuresFile.toString(), ex);
        }

        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs();
        }

        for (Feature feature : featuresFromXml.getFeature()) {
            if (acceptFeature(feature)) {
                if (verboseFeatures) {
                    getLog().info("Including feature " + feature.getName());
                }
                for (Bundle bundle : feature.getBundle()) {
                    processBundle(bundle);
                }
            } else {
                if (verboseFeatures) {
                    getLog().info("Excluding feature " + feature.getName());
                }
            }
        }
    }

    // this also notes what features have been used.
    private boolean acceptFeature(Feature feature) {
        if (features != null) {
            if (features.contains(feature.getName())) {
                features.remove(feature.getName());
                return true;
            } else {
                return false;
            }
        }

        // Include/exclude not used when 'features' are used.

        if (featureIncludes != null && featureIncludes.size() > 0) {
            if (!featureIncludes.contains(feature.getName())) {
                return false;
            }
        }

        if (featureExcludes != null && featureExcludes.size() > 0) {
            return !featureExcludes.contains(feature.getName());
        }
        return true;
    }

    private void writeMetadata() throws MojoExecutionException {
        OutputStream os = null;
        File md = new File(outputDirectory, "bundles.xml");

        try {
            os = new FileOutputStream(md);
            XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(os);
            writer = new IndentingXMLStreamWriter(writer);
            writer.writeStartDocument("utf-8", "1.0");
            writer.writeStartElement("bundles");
            for (Map.Entry<Integer, List<BundleInfo>> me : accumulatedBundles.entrySet()) {
                writer.writeStartElement("level");
                writer.writeAttribute("level", Integer.toString(me.getKey()));
                for (BundleInfo bi : me.getValue()) {
                    writer.writeStartElement("bundle");
                    writer.writeAttribute("start", Boolean.toString(bi.start));
                    writer.writeCharacters(bi.location);
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

    private void processBundle(Bundle bundle) throws MojoExecutionException, MojoFailureException {
        Artifact artifact = getArtifact(bundle);

        if (!bundleFilter.isSelected(artifact)) { // this checks for null, and thus handles wrap:
            if (verboseBundles) {
                getLog().info(String.format("Bundle %s excluded", artifact.getId()));
            }
            return;
        }
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
        List<BundleInfo> infoList;
        int startLevel = bundle.getStartLevel();
        if (startLevel == 0) {
            startLevel = defaultStartLevel;
        }
        infoList = accumulatedBundles.get(startLevel);
        if (infoList == null) {
            infoList = new ArrayList<>();
            accumulatedBundles.put(startLevel, infoList);
        }
        infoList.add(new BundleInfo(bundle.isStart(), outputFilename));
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

    protected Artifact getArtifact(Bundle bundle) throws MojoExecutionException, MojoFailureException {
        Artifact artifact;

        KarafBundleCoordinates coords;
        try {
            coords = new KarafBundleCoordinates(bundle.getLocation());
        } catch (IllegalArgumentException e) {
            getLog().warn("Non-mvn: bundle skipped: " + bundle.getLocation());
            return null;
        }

        VersionRange vr;
        try {
            vr = VersionRange.createFromVersionSpec(coords.getVersion());
        } catch (InvalidVersionSpecificationException e1) {
            throw new MojoExecutionException("Bad version range " + coords.getVersion(), e1);
        }

        artifact = factory.createDependencyArtifact(coords.getGroupId(), coords.getArtifactId(), vr,
                "jar", coords.getClassifier(), Artifact.SCOPE_COMPILE);

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
