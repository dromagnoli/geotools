/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2024, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 *
 *    This package contains documentation from OpenGIS specifications.
 *    OpenGIS consortium's work is fully acknowledged here.
 */
package org.geotools.referencing.proj;

import org.geotools.util.Classes;

/** */
public class PROJFormattable implements org.geotools.referencing.util.PROJFormattable {

    /*
    // The formatter for the {@link #toPROJ()} method.
    private static final ThreadLocal<PROJFormatter> FORMATTER = new ThreadLocal<>();
    */

    /** Default constructor. */
    protected PROJFormattable() {}

/*
    public String toPROJ() throws UnformattableObjectException {
        PROJFormatter formatter = FORMATTER.get();
        if (formatter == null) {
            formatter = new PROJFormatter();
            FORMATTER.set(formatter);
        }
        try {
            if (this instanceof GeneralParameterValue) {
                // Special processing for parameter values, which is formatted
                // directly in 'Formatter'. Note that in GeoAPI, this interface
                // doesn't share the same parent interface than other interfaces.
                formatter.append((GeneralParameterValue) this);
            } else {
                formatter.append(this);
            }
            return formatter.toString();
        } finally {
            formatter.clear();
        }
    }
*/
    public String formatPROJ(final PROJFormatter formatter) {
        Class type = getClass();
        Class[] interfaces = type.getInterfaces();
        for (final Class candidate : interfaces) {
            if (candidate.getName().startsWith("org.geotools.api.referencing.")) {
                type = candidate;
                break;
            }
        }
        return Classes.getShortName(type);
    }

    /**
     * Cleans up the thread local set in this thread. They can prevent web applications from proper
     * shutdown
     */
    /*public static void cleanupThreadLocals() {
        FORMATTER.remove();
    }*/
}
