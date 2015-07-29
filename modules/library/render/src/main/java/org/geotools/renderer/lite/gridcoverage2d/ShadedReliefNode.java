/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 * 
 *    (C) 2005-2015, Open Source Geospatial Foundation (OSGeo)
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
 */
package org.geotools.renderer.lite.gridcoverage2d;

import it.geosolutions.jaiext.JAIExt;
import it.geosolutions.jaiext.range.NoDataContainer;
import it.geosolutions.jaiext.range.Range;

import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.geom.AffineTransform;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.renderable.RenderedImageFactory;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import javax.imageio.ImageIO;
import javax.media.jai.ImageLayout;
import javax.media.jai.JAI;
import javax.media.jai.OperationDescriptor;
import javax.media.jai.OperationRegistry;
import javax.media.jai.ROI;
import javax.media.jai.RasterFactory;
import javax.media.jai.RenderedOp;
import javax.media.jai.registry.RenderedRegistryMode;

import org.geotools.coverage.GridSampleDimension;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.processing.operation.ShadedReliefDescriptor;
import org.geotools.coverage.processing.operation.ShadedReliefOpImage.Algorithm;
import org.geotools.coverage.processing.operation.ShadedReliefRIF;
import org.geotools.factory.Hints;
import org.geotools.image.ImageWorker;
import org.geotools.renderer.i18n.ErrorKeys;
import org.geotools.renderer.i18n.Errors;
import org.geotools.resources.coverage.CoverageUtilities;
import org.geotools.styling.ShadedRelief;
import org.geotools.styling.StyleVisitor;
import org.geotools.util.SimpleInternationalString;
import org.opengis.coverage.grid.GridCoverage;
import org.opengis.filter.expression.Expression;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.util.InternationalString;

/**
 * This implementations of {@link CoverageProcessingNode} takes care of the {@link ShadedRelief} element of the SLD 1.0 spec.
 * 
 * @author Daniele Romagnoli, GeoSolutions SAS
 * 
 */
class ShadedReliefNode extends StyleVisitorCoverageProcessingNodeAdapter implements StyleVisitor,
        CoverageProcessingNode {

    static {
        JAIExt.initJAIEXT();
        OperationRegistry registry = JAI.getDefaultInstance().getOperationRegistry();
        OperationDescriptor op = new ShadedReliefDescriptor();
        registry.registerDescriptor(op);
        String descName = op.getName();
        RenderedImageFactory rif = new ShadedReliefRIF();
        registry.registerFactory(RenderedRegistryMode.MODE_NAME, descName,
                "org.geotools.gce.processing", rif);
    }
    /**
     * Minimal normalized value.
     */
    private static final double MIN_VALUE = 0d;

    /**
     * Maximal normalized value.
     */
    private static final double MAX_VALUE = 1d;

    private boolean brigthnessOnly = false;

    private double reliefFactor = Double.NaN;

    public double getReliefFactor() {
        return reliefFactor;
    }

    public InternationalString getName() {
        //TODO: Set entry from Vocabulary
        return new SimpleInternationalString("Shaded Relief");
//        return Vocabulary.formatInternational(VocabularyKeys.SHADED_RELIEF);
    }

    public void visit(final ShadedRelief sr) {
        // /////////////////////////////////////////////////////////////////////
        //
        // Do nothing if we don't have a valid ShadedRelief element.
        //
        // /////////////////////////////////////////////////////////////////////
        if (sr == null) {
            return;
        }

        // /////////////////////////////////////////////////////////////////////
        //
        // Brightness Only
        //
        // /////////////////////////////////////////////////////////////////////
        brigthnessOnly = sr.isBrightnessOnly();

        // /////////////////////////////////////////////////////////////////////
        //
        // Relief Factor
        //
        // /////////////////////////////////////////////////////////////////////
        final Expression rFactor = sr.getReliefFactor();
        if (rFactor != null) {
            final Number number = rFactor.evaluate(null, Double.class);
            if (number != null) {
                reliefFactor = number.doubleValue();
                // check the gamma value
                if (reliefFactor < 0)
                    throw new IllegalArgumentException(Errors.format(ErrorKeys.ILLEGAL_ARGUMENT_$2,
                            "reliefFactor", number));
                if (Double.isNaN(reliefFactor) || Double.isInfinite(reliefFactor))
                    throw new IllegalArgumentException(Errors.format(ErrorKeys.ILLEGAL_ARGUMENT_$2,
                            "reliefFactor", number));
            }
        }

    }

    /**
     * Default constructor
     */
    public ShadedReliefNode() {
        this(null);
    }

    /**
     * Constructor for a {@link ShadedReliefNode} which allows to specify a {@link Hints} instance to control internal factory machinery.
     * 
     * @param hints {@link Hints} instance to control internal factory machinery.
     */
    public ShadedReliefNode(final Hints hints) {
        super(1, hints, SimpleInternationalString.wrap("ShadedReliefNode"),
                SimpleInternationalString
                        .wrap("Node which applies ShadedRelief following SLD 1.0 spec."));
    }

    @SuppressWarnings("unchecked")
    protected GridCoverage2D execute() {
        final Hints hints = getHints();

        // /////////////////////////////////////////////////////////////////////
        //
        // Get the sources and see what we got to do. Note that if we have more
        // than one source we'll use only the first one
        //
        // /////////////////////////////////////////////////////////////////////
        final List<CoverageProcessingNode> sources = this.getSources();
        if (sources != null && !sources.isEmpty()) {
            final GridCoverage2D source = (GridCoverage2D) getSource(0).getOutput();
            GridCoverageRendererUtilities.ensureSourceNotNull(source, this.getName().toString());
            GridCoverage2D output;

            final RenderedImage sourceImage = source.getRenderedImage();

            // /////////////////////////////////////////////////////////////////////
            //
            // PREPARATION
            //
            // /////////////////////////////////////////////////////////////////////

            // //
            //
            // Get the ROI and NoData from the input coverageS
            //
            // //
            ROI roi = CoverageUtilities.getROIProperty(source);
            NoDataContainer noDataContainer = CoverageUtilities.getNoDataProperty(source);
            Range nodata = noDataContainer != null ? noDataContainer.getAsRange() : null;

            // //
            //
            // Get the source image and if necessary convert it to use a
            // ComponentColorModel. This way we are sure we will have a
            // visible image most part of the time.
            //
            // //
            ImageWorker worker = new ImageWorker(sourceImage).setROI(roi).setNoData(nodata)
                    .setRenderingHints(hints).forceComponentColorModel();
            final int numbands = worker.getNumBands();

            // //
            //
            // Save the alpha band if present, in order to put it back
            // later in the loop. We are not going to use it anyway for
            // the IHS conversion.
            //
            // //
            RenderedImage alphaBand = null;
            if (numbands % 2 == 0) {
                // get the alpha band
                alphaBand = new ImageWorker(worker.getRenderedImage()).setRenderingHints(hints)
                        .retainLastBand().getRenderedImage();
                // strip the alpha band from the original image
                worker.setRenderingHints(hints).retainBands(numbands - 1);
            }

            // //
            //
            // Get the single band to work on, which might be the
            // intensity for RGB(A) or the GRAY channel for Gray(A)
            //
            // //
            ImageWorker intensityWorker;
            RenderedImage hChannel = null;
            RenderedImage sChannel = null;
            final boolean intensity;
            RenderedImage IHS = null;
            if (numbands > 1) {
                // convert the prepared image to IHS colorspace to work
                // on I band
                IHS = worker.setRenderingHints(hints).forceColorSpaceIHS().getRenderedImage();

                // get the various singular bands
                intensityWorker = worker.setRenderingHints(hints).retainFirstBand();
                sChannel = new ImageWorker(IHS).setRenderingHints(hints).retainLastBand()
                        .getRenderedImage();
                hChannel = new ImageWorker(IHS).setRenderingHints(hints)
                        .retainBands(new int[] { 1 }).getRenderedImage();
                intensity = true;
            } else {
                // //
                //
                // we have only one band we don't need to go to IHS
                //
                // //
                intensityWorker = worker;
                intensity = false;
            }

            performShadedRelief(intensityWorker, source, hints);

            // /////////////////////////////////////////////////////////////////////
            //
            // POSTPROCESSING
            //
            // Take care of the intermediated image we left back. This
            // means, handle the fact that we might have gone to IHS and
            // the alpha band.
            //
            // /////////////////////////////////////////////////////////////////////
            if (intensity) {

                // //
                //
                // IHS --> RGB
                //
                // Let's merge the modified IHS image. The message on
                // the mailing list (see comments for this class)
                // mentioned that it is required to pass a RenderingHing
                // with a ImageLayout with the IHS color
                // model.
                //
                // //
                final ImageLayout imageLayout = new ImageLayout();
                imageLayout.setColorModel(IHS.getColorModel());
                imageLayout.setSampleModel(IHS.getSampleModel());
                final RenderingHints rendHints = new RenderingHints(Collections.EMPTY_MAP);
                rendHints.add(hints);
                rendHints.add(new RenderingHints(JAI.KEY_IMAGE_LAYOUT, imageLayout));

                // merge and go to rgb again
                intensityWorker.setRenderingHints(rendHints).addBands(
                        new RenderedImage[] { hChannel, sChannel }, false, null);
                intensityWorker.setRenderingHints(hints).forceColorSpaceRGB();
            }

            // //
            //
            // ALPHA BAND
            //
            // Let's merge the alpha band with the image we have rebuilt.
            //
            // //
            if (alphaBand != null) {
                final ColorModel cm = new ComponentColorModel(
                        numbands >= 3 ? ColorSpace.getInstance(ColorSpace.CS_sRGB)
                                : ColorSpace.getInstance(ColorSpace.CS_GRAY),
                        numbands >= 3 ? new int[] { 8, 8, 8, 8 } : new int[] { 8, 8 }, true, false,
                        Transparency.TRANSLUCENT, DataBuffer.TYPE_BYTE);
                final ImageLayout imageLayout = new ImageLayout();
                imageLayout.setColorModel(cm);
                imageLayout.setSampleModel(cm.createCompatibleSampleModel(intensityWorker
                        .getRenderedImage().getWidth(), intensityWorker.getRenderedImage()
                        .getHeight()));
                // merge and go to rgb
                intensityWorker.setRenderingHints(hints)
                        .setRenderingHint(JAI.KEY_IMAGE_LAYOUT, imageLayout)
                        .addBand(alphaBand, false, true, null);

            }

            // /////////////////////////////////////////////////////////////////////
            //
            // OUTPUT
            //
            // /////////////////////////////////////////////////////////////////////
            final int numSourceBands = source.getNumSampleDimensions();
            final RenderedImage finalImage = intensityWorker.getRenderedImage();
            final int numActualBands = finalImage.getSampleModel().getNumBands();
            final GridCoverageFactory factory = getCoverageFactory();
            final HashMap<Object, Object> props = new HashMap<Object, Object>();
            if (source.getProperties() != null) {
                props.putAll(source.getProperties());
            }
            // Setting ROI and NODATA
            if (intensityWorker.getNoData() != null) {
                props.put(NoDataContainer.GC_NODATA,
                        new NoDataContainer(intensityWorker.getNoData()));
            }
            if (intensityWorker.getROI() != null) {
                props.put("GC_ROI", intensityWorker.getROI());
            }

            if (numActualBands == numSourceBands) {
                final String name = "ce_coverage" + source.getName();
                output = factory.create(name, finalImage,
                        (GridGeometry2D) source.getGridGeometry(), source.getSampleDimensions(),
                        new GridCoverage[] { source }, props);
            } else {
                // replicate input bands
                final GridSampleDimension sd[] = new GridSampleDimension[numActualBands];
                for (int i = 0; i < numActualBands; i++)
                    sd[i] = (GridSampleDimension) source.getSampleDimension(0);
                output = factory.create("ce_coverage" + source.getName().toString(), finalImage,
                        (GridGeometry2D) source.getGridGeometry(), sd,
                        new GridCoverage[] { source }, props);

            }
            return output;

        }
        throw new IllegalStateException(Errors.format(ErrorKeys.SOURCE_CANT_BE_NULL_$1, this
                .getName().toString()));

    }

    private RenderedImage performShadedRelief(ImageWorker intensityWorker, GridCoverage2D source, Hints hints) {
        RenderedImage ri = source.getRenderedImage();
        final int w = ri.getWidth();
        final int h = ri.getHeight();
        ColorModel cm2 = RasterFactory.createComponentColorModel(DataBuffer.TYPE_BYTE,
                ColorSpace.getInstance(ColorSpace.CS_GRAY), false, false, ri.getColorModel().getTransparency());

        SampleModel sm2 = cm2.createCompatibleSampleModel(w, h);
        ImageLayout layout = new ImageLayout(ri.getMinX(), ri.getMinY(), ri.getWidth(), ri.getHeight(),
                ri.getTileGridXOffset(), ri.getTileGridYOffset(), ri.getTileWidth(), ri.getTileHeight(),
                sm2, cm2);

        RenderingHints newHints = hints.clone(); 
        newHints.add(new RenderingHints(JAI.KEY_IMAGE_LAYOUT, layout));

        MathTransform g2w = source.getGridGeometry().getGridToCRS();
        AffineTransform af = (AffineTransform) g2w;
        double resX = af.getScaleX();
        double resY = af.getScaleY();

        double[] destNoData = intensityWorker.getDestinationNoData();
        double destinationNoData = destNoData != null ? destNoData[0] : 0;
        
        //TODO: Handle CRS
        RenderedOp finalImage = 
                
                ShadedReliefDescriptor.create(ri, intensityWorker.getROI(), intensityWorker.getNoData(), destinationNoData, resX, resY,
                reliefFactor, ShadedReliefDescriptor.DEFAULT_SCALE, ShadedReliefDescriptor.DEFAULT_ALTITUDE, ShadedReliefDescriptor.DEFAULT_AZIMUTH, Algorithm.ZEVENBERGEN_THORNE_COMBINED,
                true, newHints);
        intensityWorker.setImage(finalImage);
        return finalImage;
    }

}
