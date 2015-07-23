/* JAI-Ext - OpenSource Java Advanced Image Extensions Library
*    http://www.geo-solutions.it/
*    Copyright 2014 GeoSolutions


* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at

* http://www.apache.org/licenses/LICENSE-2.0

* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.geotools.gce.geotiff;

import it.geosolutions.jaiext.range.Range;

import java.awt.RenderingHints;
import java.awt.image.RenderedImage;

import javax.media.jai.JAI;
import javax.media.jai.OperationDescriptorImpl;
import javax.media.jai.ParameterBlockJAI;
import javax.media.jai.PropertyGenerator;
import javax.media.jai.ROI;
import javax.media.jai.RenderedOp;
import javax.media.jai.registry.RenderedRegistryMode;

import org.geotools.gce.geotiff.ShadedReliefOpImage.Algorithm;

import com.sun.media.jai.util.AreaOpPropertyGenerator;

/**
 * An <code>OperationDescriptor</code> describing the "ShadedRelief" operation.
 * 
 * TODO: moreJavadoc
 */
public class ShadedReliefDescriptor extends OperationDescriptorImpl {

    /** serialVersionUID */
    private static final long serialVersionUID = 1L;

    /**
     * The resource strings that provide the general documentation and specify the parameter list for a ShadedRelief operation.
     */
    private static final String[][] resources = {
            { "GlobalName", "ShadedRelief" },
            { "LocalName", "ShadedRelief" },
            { "Vendor", "it.geosolutions.jaiext" },
            { "Description", "desc" },
            { "Version", "ver" },
//            { "arg0Desc", JaiI18N.getString("ShadedReliefDescriptor1") },
//            { "arg1Desc", JaiI18N.getString("ShadedReliefDescriptor2") },
//            { "arg2Desc", JaiI18N.getString("ShadedReliefDescriptor3") },
//            { "arg3Desc", JaiI18N.getString("ShadedReliefDescriptor4") },
//            { "arg5Desc", JaiI18N.getString("ShadedReliefDescriptor5") }
            };

    /** The parameter names for the ShadedRelief operation. */
    private static final String[] paramNames = { "roi", "nodata", "resX","resY", 
        "verticalExaggeration", 
        "verticalScale",
        "altitude",
        "azimuth",
        "algorithm",
        "computeEdge"};

    /** The parameter class types for the ShadedRelief operation. */
    private static final Class[] paramClasses = { javax.media.jai.ROI.class,
        it.geosolutions.jaiext.range.Range.class,
            Double.class,Double.class,Double.class,Double.class,Double.class,Double.class,
            Algorithm.class, Boolean.class
    };

    /** The parameter default values for the ShadedRelief operation. */
    private static final Object[] paramDefaults = { 
        null, null, NO_PARAMETER_DEFAULT, NO_PARAMETER_DEFAULT, 1d,
        1d, 45d, 315d, Algorithm.ZEVENBERGEN_THORNE_COMBINED, true};

    /** Constructor. */
    public ShadedReliefDescriptor() {
        super(resources, 1, paramClasses, paramNames, paramDefaults);
    }

    /**
     * Returns an array of <code>PropertyGenerators</code> implementing property inheritance for the "ShadedRelief" operation.
     * 
     * @return An array of property generators.
     */
    public PropertyGenerator[] getPropertyGenerators() {
        PropertyGenerator[] pg = new PropertyGenerator[1];
        pg[0] = new AreaOpPropertyGenerator();
        return pg;
    }

    public static RenderedOp create(RenderedImage source0,
            ROI roi, Range nodata,
            double resX, double resY,
            double verticalExaggeration, double verticalScale, 
            double altitude, double azimuth, Algorithm algorithm, boolean computeEdge,    
            /*double destNoData, boolean skipNoData, */RenderingHints hints) {
        ParameterBlockJAI pb = new ParameterBlockJAI("ShadedRelief", RenderedRegistryMode.MODE_NAME);
        // Setting sources
        pb.setSource("source0", source0);
        // Setting params
                
        pb.setParameter("roi", roi);
        pb.setParameter("nodata", nodata);
        pb.setParameter("resX", resX);
        pb.setParameter("resY", resY);
        pb.setParameter("verticalExaggeration", verticalExaggeration);
        pb.setParameter("verticalScale", verticalScale);
        pb.setParameter("altitude", altitude);
        pb.setParameter("azimuth", azimuth);
        pb.setParameter("algorithm", algorithm);
        pb.setParameter("computeEdge", computeEdge);

        return JAI.create("ShadedRelief", pb, hints);
    }
}
