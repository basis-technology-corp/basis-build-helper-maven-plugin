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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.codehaus.plexus.util.SelectorUtils;

/**
 * @author Benjamin Bentmann
 */
class ArtifactId {

    private final String groupId;

    private final String artifactId;

    private final String type;

    private final String classifier;

    private final String version;

    ArtifactId(Dependency dependency) {
        this(dependency.getGroupId(), dependency.getArtifactId(), dependency.getType(), dependency.getClassifier(), dependency.getVersion());
    }

    ArtifactId(Artifact artifact) {
        this(artifact.getGroupId(), artifact.getArtifactId(), artifact.getType(), artifact.getClassifier(), artifact.getVersion());
    }

    ArtifactId(String groupId, String artifactId, String type, String classifier, String version) {
        this.groupId = (groupId != null) ? groupId : "";
        this.artifactId = (artifactId != null) ? artifactId : "";
        this.type = (type != null) ? type : "";
        this.classifier = (classifier != null) ? classifier : "";
        this.version = (version != null) ? version : "";
    }

    ArtifactId(String id) {
        String[] tokens = new String[0];
        if (id != null && id.length() > 0) {
            tokens = id.split(":", -1);
        }
        groupId = (tokens.length > 0) ? tokens[0] : "";
        artifactId = (tokens.length > 1) ? tokens[1] : "*";
        type = (tokens.length > 3) ? tokens[2] : "*";
        classifier = (tokens.length > 3) ? tokens[3] : ((tokens.length > 2) ? tokens[2] : "*");
        version = (tokens.length > 4) ? tokens[4] : "*";
    }

    String getGroupId() {
        return groupId;
    }

    String getArtifactId() {
        return artifactId;
    }

    String getType() {
        return type;
    }

    String getClassifier() {
        return classifier;
    }

    public String getVersion() {
        return version;
    }

    boolean matches(ArtifactId pattern) {
        if (pattern == null) {
            return false;
        }
        if (!match(getGroupId(), pattern.getGroupId())) {
            return false;
        }
        if (!match(getArtifactId(), pattern.getArtifactId())) {
            return false;
        }
        if (!match(getType(), pattern.getType())) {
            return false;
        }
        if (!match(getClassifier(), pattern.getClassifier())) {
            return false;
        }
        return match(getVersion(), pattern.getVersion());
    }

    private boolean match(String str, String pattern) {
        return SelectorUtils.match(pattern, str);
    }

}
