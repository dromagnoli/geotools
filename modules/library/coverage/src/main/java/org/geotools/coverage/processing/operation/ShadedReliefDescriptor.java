package org.geotools.coverage.processing.operation;

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

import org.geotools.coverage.processing.operation.ShadedReliefOpImage.Algorithm;

import com.sun.media.jai.util.AreaOpPropertyGenerator;

/**
 * An <code>OperationDescriptor</code> describing the "ShadedRelief" operation.
 * 
 * TODO: moreJavadoc
 */
public class ShadedReliefDescriptor extends OperationDescriptorImpl {

    public static final double DEFAULT_AZIMUTH = 315;

    public static final double DEFAULT_ALTITUDE = 45;

    public static final double DEFAULT_Z = 100000;

    private static final double DEGREES_TO_METERS = 111120; 

    public static final double DEFAULT_SCALE = DEGREES_TO_METERS;

    
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
            it.geosolutions.jaiext.range.Range.class, Double.class, Double.class, Double.class,
            Double.class, Double.class, Double.class, Algorithm.class, Boolean.class };

    /** The parameter default values for the ShadedRelief operation. */
    private static final Object[] paramDefaults = { null, null, NO_PARAMETER_DEFAULT,
            NO_PARAMETER_DEFAULT, 1d, 1d, DEFAULT_ALTITUDE, DEFAULT_AZIMUTH, Algorithm.ZEVENBERGEN_THORNE_COMBINED, true };

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
