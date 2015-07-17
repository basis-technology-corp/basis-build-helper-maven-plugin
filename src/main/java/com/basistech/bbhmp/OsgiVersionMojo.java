/******************************************************************************
 * * This data and information is proprietary to, and a valuable trade secret
 * * of, Basis Technology Corp.  It is given in confidence by Basis Technology
 * * and may only be used as permitted under the license agreement under which
 * * it has been distributed, and in no other way.
 * *
 * * Copyright (c) 2015 Basis Technology Corporation All rights reserved.
 * *
 * * The technical data and information provided herein are provided with
 * * `limited rights', and the computer software provided herein is provided
 * * with `restricted rights' as those terms are defined in DAR and ASPR
 * * 7-104.9(a).
 ******************************************************************************/

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
 * x.y.z(-SNAPSHOT)
 * x.y.z.cXX.Y(-SNAPSHOT)
 * Anything else is an error.
 *
 */
@Mojo(name = "osgi-version", defaultPhase = LifecyclePhase.PROCESS_SOURCES)
public class OsgiVersionMojo extends AbstractMojo {

    private static final Pattern PLAIN_PATTERN = Pattern.compile("([0-9]+)\\.([0-9]+)\\.([0-9]+)(-SNAPSHOT)?");
    private static final Pattern CXX_PATTERN = Pattern.compile("([0-9]+)\\.([0-9]+)\\.([0-9]+)\\.c[0-9]+\\.[0-9]+(-SNAPSHOT)?");
    private static final String TIMESTAMP_PATTERN = "'v'yyyyMMddhhmmss";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /**
     * Whether to attach a timestamp as a qualifier to the OSGi version.
     * If this parameter is false, then the qualifier is a timestamp for a snapshot,
     * blank for a release. If this parameter is true, then the qualifier is always filled in with a
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
        boolean snapshot;
        String result;

        Matcher matcher = CXX_PATTERN.matcher(project.getVersion());
        if (matcher.matches()) {
            result = String.format("%s.%s.%s", matcher.group(1), matcher.group(2), matcher.group(3));
            snapshot = matcher.groupCount() > 3;
        } else {
            matcher = PLAIN_PATTERN.matcher(project.getVersion());
            if (matcher.matches()) {
                result = String.format("%s.%s.%s", matcher.group(1), matcher.group(2), matcher.group(3));
                snapshot = matcher.groupCount() > 3;
            } else {
                throw new MojoExecutionException(String.format("Version %s does not match either x.y.z or x.y.z.cXX.Y",
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
            /* add a qualifier */
            result = result + "." + format.format(calendar.getTime());
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
