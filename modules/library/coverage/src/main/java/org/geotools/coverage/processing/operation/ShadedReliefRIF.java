package org.geotools.coverage.processing.operation;

import it.geosolutions.jaiext.range.Range;

import java.awt.RenderingHints;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.ParameterBlock;
import java.awt.image.renderable.RenderedImageFactory;

import javax.media.jai.BorderExtender;
import javax.media.jai.ImageLayout;
import javax.media.jai.ROI;

import org.geotools.coverage.processing.operation.ShadedReliefOpImage.Algorithm;

import com.sun.media.jai.opimage.RIFUtil;


public class ShadedReliefRIF implements RenderedImageFactory {

    public ShadedReliefRIF() {
    }

    public RenderedImage create(ParameterBlock pb, RenderingHints hints) {
        // Getting the Layout
        ImageLayout l = RIFUtil.getImageLayoutHint(hints);
        // Get BorderExtender from renderHints if present.
        BorderExtender extender = RIFUtil.getBorderExtenderHint(hints);
        // Getting source
        RenderedImage img = pb.getRenderedSource(0);
        // Getting parameters

        int paramIndex = 0;
        ROI roi = (ROI) pb.getObjectParameter(paramIndex++);
        Range nodata = (Range) pb.getObjectParameter(paramIndex++);
//        double destinationNoData = pb.getDoubleParameter(paramIndex++);
        double resX = pb.getDoubleParameter(paramIndex++);
        double resY = pb.getDoubleParameter(paramIndex++);
        double verticalExaggeration = pb.getDoubleParameter(paramIndex++);
        double verticalScale = pb.getDoubleParameter(paramIndex++);
        double altitude = pb.getDoubleParameter(paramIndex++);
        double azimuth = pb.getDoubleParameter(paramIndex++);
        Algorithm algorithm = (Algorithm) pb.getObjectParameter(paramIndex++);
        boolean computeEdge = (Boolean) pb.getObjectParameter(paramIndex++);

        return new ShadedReliefOpImage(img, hints, l, roi, nodata,
                resX, resY, 
                verticalExaggeration, verticalScale, altitude,
                azimuth, algorithm, computeEdge);
    }
}
