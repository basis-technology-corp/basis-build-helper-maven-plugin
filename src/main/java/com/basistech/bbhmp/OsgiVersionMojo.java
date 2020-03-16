/*
* Copyright 2014 Basis Technology Corp.
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

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transform a maven version to an OSGi version, dealing, as needed, with the
 * Basis convention of x.y.z.cXX.Y{-SNAPSHOT}. So, we deal with the following cases:
 * x.y.z(.qualifier)(-SNAPSHOT) [where a qualifier is a sequence of alphanumeric characters, '.', '_', or '-']
 * x.y.z.cXX.Y(qualifier)
 * Anything else is an error.
 *
 */
@Mojo(name = "osgi-version", defaultPhase = LifecyclePhase.PROCESS_SOURCES)
public class OsgiVersionMojo extends AbstractMojo {

    // Matches valid OSGi versions with an optional '-SNAPSHOT' suffix
    // (note that for the "qualifier" group we deliberately include the leading period)
    private static final Pattern PLAIN_PATTERN = Pattern.compile("(?<major>[0-9]+)(\\.(?<minor>[0-9]+)(\\.(?<patch>[0-9]+)(?<qualifier>\\.[\\p{Alnum}._-]+?)?)?)?(-SNAPSHOT)?");
    private static final Pattern CXX_PATTERN = Pattern.compile("([0-9]+\\.[0-9]+\\.[0-9]+\\.c[0-9]+)\\.([0-9]+[\\p{Alnum}._-]*)");
    private static final String TIMESTAMP_PATTERN = "'v'yyyyMMddhhmmss";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /**
     * Whether to attach a timestamp as a qualifier to the OSGi version.
     * If this parameter is false, then the qualifier is a timestamp for a snapshot,
     * unmodified for a release. If this parameter is true, then the qualifier is added with a
     * timestamp.
     */
    @Parameter(defaultValue = "false")
    boolean timestampQualifier;

    /**
     * The name of the property to which we're delivering the result.
     */
    @Parameter(defaultValue = "osgi-version")
    String propertyName;

    public void execute() throws MojoExecutionException {
        boolean snapshot = project.getVersion().endsWith("-SNAPSHOT");
        boolean hasQualifier = false;
        String result;

        Matcher matcher = CXX_PATTERN.matcher(project.getVersion());
        if (matcher.matches()) {
            result = String.format("%s_%s", matcher.group(1), matcher.group(2));
            hasQualifier = true; // c-number is always a qualifier
        } else {
            matcher = PLAIN_PATTERN.matcher(project.getVersion());
            if (matcher.matches()) {
                String major = matcher.group("major");
                String minor = matcher.group("minor");
                String patch = matcher.group("patch");
                String qualifier = matcher.group("qualifier");
                hasQualifier = qualifier != null;
                result = String.format("%s.%s.%s%s",
                    major,
                    minor == null ? "0" : minor,
                    patch == null ? "0" : patch,
                    qualifier == null ? "" : qualifier);
            } else {
                throw new MojoExecutionException(String.format("Version %s does not match either x.y.z, x.y.z.cXX.Y(<qualifier>), or x.y.z.<qualifier>",
                    project.getVersion()));
            }
        }

        if (snapshot || timestampQualifier) {
            SimpleDateFormat format = new SimpleDateFormat(TIMESTAMP_PATTERN);
            TimeZone timeZone = TimeZone.getTimeZone("GMT");
            format.setTimeZone(timeZone);
            Date now = new Date();
            Calendar calendar = new GregorianCalendar();
            calendar.setTime(now);
            calendar.setTimeZone(timeZone);
            calendar.add(Calendar.SECOND, 0);
            /* add or update the qualifier */
            result = result + (hasQualifier ? "-" : ".") + format.format(calendar.getTime());
        }
        defineProperty(propertyName, result);
    }

    private void defineProperty(String name, String value) {
        if (getLog().isDebugEnabled()) {
            getLog().debug("define property " + name + " = \"" + value + "\"");
        }

        project.getProperties().put(name, value);
    }
}
