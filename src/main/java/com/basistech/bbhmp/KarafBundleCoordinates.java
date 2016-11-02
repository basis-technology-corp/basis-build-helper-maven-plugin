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

import org.apache.maven.plugin.MojoFailureException;

/**
 * Karaf feature.xml files have URI's for bundles. In theory, we could use pax-url to resolve these,
 *  but that is a big entanglement. We insist on mvn:, reject others (e.g. wrap), and assume no
 *  use of classifiers for now.
 * mvn:org.apache.servicemix.bundles/org.apache.servicemix.bundles.aws-java-sdk/some.version
 */
class KarafBundleCoordinates {
    private final String groupId;
    private final String artifactId;
    private final String version;
    private final String classifier;

    KarafBundleCoordinates(String uri) throws MojoFailureException {
        if (!uri.startsWith("mvn:")) {
            throw new IllegalArgumentException("Bundle location is not an mvn: URI: " + uri);
        }
        String[] pieces = uri.substring(4).split("/");
        if (pieces.length < 3) {
            throw new MojoFailureException("Invalid bundle location: " + uri);
        }
        groupId = pieces[0];
        artifactId = pieces[1];
        version = pieces[2];
        if (pieces.length >= 5) {
            if (!"jar".equals(pieces[3])) {
                throw new MojoFailureException("Non-jar 'bundle' " + uri);
            }
            classifier = pieces[4];
        } else {
            classifier = null;
        }
    }

    String getGroupId() {
        return groupId;
    }

    String getArtifactId() {
        return artifactId;
    }

    String getVersion() {
        return version;
    }

    public String getClassifier() {
        return classifier;
    }
}
