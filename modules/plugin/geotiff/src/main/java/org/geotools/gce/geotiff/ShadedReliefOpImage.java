/* JAI-Ext - OpenSource Java Advanced Image Extensions Library
 *    http://www.geo-solutions.it/
 *    Copyright 2015 GeoSolutions


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

import it.geosolutions.jaiext.JAIExt;
import it.geosolutions.jaiext.border.BorderDescriptor;
import it.geosolutions.jaiext.range.Range;
import it.geosolutions.jaiext.range.RangeFactory;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
import java.awt.image.renderable.RenderedImageFactory;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import javax.media.jai.AreaOpImage;
import javax.media.jai.BorderExtender;
import javax.media.jai.ImageLayout;
import javax.media.jai.IntegerSequence;
import javax.media.jai.JAI;
import javax.media.jai.KernelJAI;
import javax.media.jai.OperationDescriptor;
import javax.media.jai.OperationRegistry;
import javax.media.jai.PlanarImage;
import javax.media.jai.ROI;
import javax.media.jai.RasterAccessor;
import javax.media.jai.RasterFactory;
import javax.media.jai.RasterFormatTag;
import javax.media.jai.RenderedOp;
import javax.media.jai.iterator.RandomIter;
import javax.media.jai.registry.RenderedRegistryMode;

import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.opengis.parameter.ParameterValue;
import org.opengis.referencing.datum.PixelInCell;
import org.opengis.referencing.operation.MathTransform;

import com.sun.media.jai.util.ImageUtil;

public class ShadedReliefOpImage extends AreaOpImage {

    private static final BorderExtender EXTENDER = BorderExtender
            .createInstance(BorderExtender.BORDER_ZERO);

    private static final double DEGREES_TO_RADIANS = Math.PI / 180.0;

    private static final double SQUARED_PI_2 = Math.PI * Math.PI / 4;

    private static final double DEFAULT_Z = 100000;

    private static final double DEFAULT_SCALE = 111120;

    private static final double DEFAULT_AZIMUTH = 315;

    private static final double DEFAULT_ALTITUDE = 45;

    public static enum Algorithm {
        ZEVENBERGEN_THORNE {

            @Override
            public double getX(double[] window) {
                return getX2(window);
            }

            @Override
            public double getY(double[] window) {
                return getY2(window);
            }

            @Override
            public double getFactor() {
                return getZTFactor();
            }
        },
        ZEVENBERGEN_THORNE_COMBINED {
            @Override
            public double getX(double[] window) {
                return getX2(window);
            }

            @Override
            public double getY(double[] window) {
                return getY2(window);
            }

            @Override
            public double getFactor() {
                return getZTFactor();
            }

            @Override
            public double refineValue(double value, double slope) {
                return combineValue(value, slope);
            }
        }

        ,
        DEFAULT, COMBINED {

            @Override
            public double refineValue(double value, double slope) {
                return combineValue(value, slope);
            }

        };

        public double getFactor() {
            return 8;
        }

        private static double getZTFactor() {
            return 2;
        }

        private static double getX2(double[] window) {
            return (window[3] - window[5]);
        }

        private static double getY2(double[] window) {
            return (window[7] - window[1]);
        }

        public double getX(double[] window) {
            return (window[3] - window[5])
                    + ((window[0] + window[3] + window[6]) - (window[2] + +window[5] + window[8]));
        }

        public double getY(double[] window) {
            return (window[7] - window[1])
                    + ((window[6] + window[7] + window[8]) - (window[0] + window[1] + window[2]));
        }

        private static double combineValue(double value, double slope) {
            // combined shading
            value = Math.acos(value);

            value = 1 - (value * Math.atan(Math.sqrt(slope)) / SQUARED_PI_2);
            return value;
        }

        public double refineValue(double value, double slope) {
            return value;
        }

        public float getValue(double[] window, HillShadeParams params) {

            double x, y, aspect, square, value;
            double xNum = getX(window);
            double yNum = getY(window);

            // Computing slope
            x = xNum / params.resX;
            y = yNum / params.resY;
            square = (x * x) + (y * y);
            double slope = square * params.squaredZetaScaleFactor;

            // Computing aspect
            aspect = Math.atan2(y, x);

            // Computing shading
            value = (params.sineOfAltitude - (params.cosinusOfaltitudeRadiansForZetaScaleFactor
                    * Math.sqrt(square) * Math.sin(aspect - params.azimuthRadians)))
                    / Math.sqrt(1 + slope);

            value = refineValue(value, slope);
            if (value <= 0.0) {
                value = 1.0;
            } else {
                value = 1.0 + (254.0 * value);
            }

            return (float) value;
        }

    }

    /** Constant indicating that the inner random iterators must pre-calculate an array of the image positions */
    public static final boolean ARRAY_CALC = true;

    /** Constant indicating that the inner random iterators must cache the current tile position */
    public static final boolean TILE_CACHED = true;

    /** Boolean indicating that NoData must be checked */
    protected final boolean hasNoData;

    /** NoData Range element */
    protected Range noData;

    /** LookupTable used for checking if an input byte sample is a NoData */
    protected boolean[] lut;

    /** Boolean indicating that ROI must be checked */
    protected final boolean hasROI;

    /** ROI element */
    protected ROI roi;

    /** Boolean indicating that no roi and no data check must be done */
    protected final boolean caseA;

    /** Boolean indicating that only roi check must be done */
    protected final boolean caseB;

    /** Boolean indicating that only no data check must be done */
    protected final boolean caseC;

    /** ROI bounds as a Shape */
    protected final Rectangle roiBounds;

    /** ROI related image */
    protected PlanarImage roiImage;

    /** Destination No Data value for Byte sources */
    protected byte destNoDataByte;

    /** Destination No Data value for Short sources */
    protected short destNoDataShort;

    /** Destination No Data value for Integer sources */
    protected int destNoDataInt;

    /** Destination No Data value for Float sources */
    protected float destNoDataFloat;

    /** Destination No Data value for Double sources */
    protected double destNoDataDouble;

    // protected boolean skipNoData;

    protected RenderedImage extendedIMG;

    protected Rectangle destBounds;

    protected KernelJAI kernel;

    private double noDataDouble;

    private int maxX;

    private int maxY;

    private Algorithm algorithm;

    private HillShadeParams params;

    public ShadedReliefOpImage(RenderedImage source, RenderingHints hints, ImageLayout l,
            ROI roi,
            Range noData,
            // double destinationNoData,
            // boolean skipNoData,
            double resX, double resY, double verticalExaggeration, double verticalScale,
            double altitude, double azimuth, Algorithm algorithm, boolean computeEdge) {
        super(source, l, hints, true, null,/* EXTENDER, */1, 1, 1, 1);

        maxX = minX + width - 1;
        maxY = maxY + height - 1;

        // Check if ROI control must be done
        if (roi != null) {
            hasROI = true;
            // Roi object
            this.roi = roi;
            roiBounds = roi.getBounds();
        } else {
            hasROI = false;
            this.roi = null;
            roiBounds = null;
        }

        // Check if No Data control must be done
        if (noData != null) {
            hasNoData = true;
            this.noData = noData;
            this.noDataDouble = noData.getMin().doubleValue();
            // this.skipNoData = skipNoData;
        } else {
            hasNoData = false;
            // this.skipNoData = false;
        }

        this.params = prepareParams(resX, resY, verticalExaggeration, verticalScale, altitude,
                azimuth, algorithm);
        this.algorithm = algorithm;

        // Getting datatype
        int dataType = source.getSampleModel().getDataType();

        // // Destination No Data value is clamped to the image data type
        // this.destNoDataDouble = destinationNoData;
        // switch (dataType) {
        // case DataBuffer.TYPE_BYTE:
        // this.destNoDataByte = ImageUtil.clampRoundByte(destinationNoData);
        // break;
        // case DataBuffer.TYPE_USHORT:
        // this.destNoDataShort = ImageUtil.clampRoundUShort(destinationNoData);
        // break;
        // case DataBuffer.TYPE_SHORT:
        // this.destNoDataShort = ImageUtil.clampRoundShort(destinationNoData);
        // break;
        // case DataBuffer.TYPE_INT:
        // this.destNoDataInt = ImageUtil.clampRoundInt(destinationNoData);
        // break;
        // case DataBuffer.TYPE_FLOAT:
        // this.destNoDataFloat = ImageUtil.clampFloat(destinationNoData);
        // break;
        // case DataBuffer.TYPE_DOUBLE:
        // break;
        // default:
        // throw new IllegalArgumentException("Wrong image data type");
        // }

        // Definition of the possible cases that can be found
        // caseA = no ROI nor No Data
        // caseB = ROI present but No Data not present
        // caseC = No Data present but ROI not present
        // Last case not defined = both ROI and No Data are present
        caseA = !hasNoData && !hasROI;
        caseB = !hasNoData && hasROI;
        caseC = hasNoData && !hasROI;

        // if (hasNoData && dataType == DataBuffer.TYPE_BYTE) {
        // initBooleanNoDataTable();
        // }

        if (this.extender != null) {
            extendedIMG = BorderDescriptor.create(source, leftPadding, rightPadding, topPadding,
                    bottomPadding, extender, RangeFactory.create(0, 0), 0d /* destinationNoData */,
                    hints);
            this.destBounds = getBounds();
        } else {
            int x0 = getMinX() + leftPadding;
            int y0 = getMinY() + topPadding;

            int w = getWidth() - leftPadding - rightPadding;
            w = Math.max(w, 0);

            int h = getHeight() - topPadding - bottomPadding;
            h = Math.max(h, 0);

            this.destBounds = new Rectangle(x0, y0, w, h);
        }
    }

    /**
     * Performs the computation on a specified rectangle. The sources are cobbled.
     * 
     * @param sources an array of source Rasters, guaranteed to provide all necessary source data for computing the output.
     * @param dest a WritableRaster tile containing the area to be computed.
     * @param destRect the rectangle within dest to be processed.
     */
    protected void computeRect(Raster[] sources, WritableRaster dest, Rectangle destRect) {
        // Retrieve format tags.
        RasterFormatTag[] formatTags = getFormatTags();

        Raster source = sources[0];
        Rectangle srcRect = mapDestRect(destRect, 0);

        RasterAccessor src = new RasterAccessor(source, srcRect, formatTags[0], getSourceImage(0)
                .getColorModel());
        RasterAccessor dst = new RasterAccessor(dest, destRect, formatTags[1], getColorModel());

        // ROI fields
        // ROI roiTile = null;

        RandomIter roiIter = null;

        boolean roiContainsTile = false;
        boolean roiDisjointTile = false;

        // ROI check
        // if (hasROI) {
        // Rectangle srcRectExpanded = mapDestRect(destRect, 0);
        // // The tile dimension is extended for avoiding border errors
        // srcRectExpanded.setRect(srcRectExpanded.getMinX() - 1, srcRectExpanded.getMinY() - 1,
        // srcRectExpanded.getWidth() + 2, srcRectExpanded.getHeight() + 2);
        // roiTile = roi.intersect(new ROIShape(srcRectExpanded));
        //
        // if (!roiBounds.intersects(srcRectExpanded)) {
        // roiDisjointTile = true;
        // } else {
        // roiContainsTile = roiTile.contains(srcRectExpanded);
        // if (!roiContainsTile) {
        // if (!roiTile.intersects(srcRectExpanded)) {
        // roiDisjointTile = true;
        // } else {
        // PlanarImage roiIMG = getImage();
        // roiIter = RandomIterFactory.create(roiIMG, null, TILE_CACHED, ARRAY_CALC);
        // }
        // }
        // }
        // }

        if (!hasROI || !roiDisjointTile) {
            switch (dst.getDataType()) {
            case DataBuffer.TYPE_BYTE:
                byteLoop(src, dst, roiIter, roiContainsTile);
                break;
            case DataBuffer.TYPE_USHORT:
                ushortLoop(src, dst, roiIter, roiContainsTile);
                break;
            case DataBuffer.TYPE_SHORT:
                shortLoop(src, dst, roiIter, roiContainsTile);
                break;
            case DataBuffer.TYPE_INT:
                intLoop(src, dst, roiIter, roiContainsTile);
                break;
            case DataBuffer.TYPE_FLOAT:
                floatLoop(src, dst, roiIter, roiContainsTile);
                break;
            case DataBuffer.TYPE_DOUBLE:
                doubleLoop(src, dst, roiIter, roiContainsTile);
                break;
            default:
                throw new IllegalArgumentException("Wrong Data Type defined");
            }

            // If the RasterAccessor object set up a temporary buffer for the
            // op to write to, tell the RasterAccessor to write that data
            // to the raster no that we're done with it.
            if (dst.isDataCopy()) {
                dst.clampDataArrays();
                dst.copyDataToRaster();
            }
        } else {
            // Setting all as NoData
            double[] backgroundValues = new double[src.getNumBands()];
            Arrays.fill(backgroundValues, destNoDataDouble);
            ImageUtil.fillBackground(dest, destRect, backgroundValues);
        }
    }

    protected void byteLoop(RasterAccessor src, RasterAccessor dst, RandomIter roiIter,
            boolean roiContainsTile) {
        throw new UnsupportedOperationException();
    }

    protected void ushortLoop(RasterAccessor src, RasterAccessor dst, RandomIter roiIter,
            boolean roiContainsTile) {
        throw new UnsupportedOperationException();
    }

    protected void shortLoop(RasterAccessor src, RasterAccessor dst, RandomIter roiIter,
            boolean roiContainsTile) {
        throw new UnsupportedOperationException();
    }

    protected void floatLoop(RasterAccessor src, RasterAccessor dst, RandomIter roiIter,
            boolean roiContainsTile) {
        throw new UnsupportedOperationException();
    }

    protected void doubleLoop(RasterAccessor src, RasterAccessor dst, RandomIter roiIter,
            boolean roiContainsTile) {
        throw new UnsupportedOperationException();
    }

    public Raster computeTile(int tileX, int tileY) {
        if (!cobbleSources) {
            return super.computeTile(tileX, tileY);
        }
        // Special handling for Border Extender

        /* Create a new WritableRaster to represent this tile. */
        Point org = new Point(tileXToX(tileX), tileYToY(tileY));
        WritableRaster dest = createWritableRaster(sampleModel, org);

        /* Clip output rectangle to image bounds. */
        Rectangle rect = new Rectangle(org.x, org.y, sampleModel.getWidth(),
                sampleModel.getHeight());

        Rectangle destRect = rect.intersection(destBounds);
        if ((destRect.width <= 0) || (destRect.height <= 0)) {
            return dest;
        }

        /* account for padding in srcRectangle */
        PlanarImage s = getSourceImage(0);
        // Fix 4639755: Area operations throw exception for
        // destination extending beyond source bounds
        // The default dest image area is the same as the source
        // image area. However, when an ImageLayout hint is set,
        // this might be not true. So the destRect should be the
        // intersection of the provided rectangle, the destination
        // bounds and the source bounds.
        destRect = destRect.intersection(s.getBounds());
        Rectangle srcRect = new Rectangle(destRect);
        srcRect.x -= getLeftPadding();
        srcRect.width += getLeftPadding() + getRightPadding();
        srcRect.y -= getTopPadding();
        srcRect.height += getTopPadding() + getBottomPadding();

        /*
         * The tileWidth and tileHeight of the source image may differ from this tileWidth and tileHeight.
         */
        IntegerSequence srcXSplits = new IntegerSequence();
        IntegerSequence srcYSplits = new IntegerSequence();

        // there is only one source for an AreaOpImage
        s.getSplits(srcXSplits, srcYSplits, srcRect);

        // Initialize new sequences of X splits.
        IntegerSequence xSplits = new IntegerSequence(destRect.x, destRect.x + destRect.width);

        xSplits.insert(destRect.x);
        xSplits.insert(destRect.x + destRect.width);

        srcXSplits.startEnumeration();
        while (srcXSplits.hasMoreElements()) {
            int xsplit = srcXSplits.nextElement();
            int lsplit = xsplit - getLeftPadding();
            int rsplit = xsplit + getRightPadding();
            xSplits.insert(lsplit);
            xSplits.insert(rsplit);
        }

        // Initialize new sequences of Y splits.
        IntegerSequence ySplits = new IntegerSequence(destRect.y, destRect.y + destRect.height);

        ySplits.insert(destRect.y);
        ySplits.insert(destRect.y + destRect.height);

        srcYSplits.startEnumeration();
        while (srcYSplits.hasMoreElements()) {
            int ysplit = srcYSplits.nextElement();
            int tsplit = ysplit - getBottomPadding();
            int bsplit = ysplit + getTopPadding();
            ySplits.insert(tsplit);
            ySplits.insert(bsplit);
        }

        /*
         * Divide destRect into sub rectangles based on the source splits, and compute each sub rectangle separately.
         */
        int x1, x2, y1, y2;
        Raster[] sources = new Raster[1];

        ySplits.startEnumeration();
        for (y1 = ySplits.nextElement(); ySplits.hasMoreElements(); y1 = y2) {
            y2 = ySplits.nextElement();

            int h = y2 - y1;
            int py1 = y1 - getTopPadding();
            int py2 = y2 + getBottomPadding();
            int ph = py2 - py1;

            xSplits.startEnumeration();
            for (x1 = xSplits.nextElement(); xSplits.hasMoreElements(); x1 = x2) {
                x2 = xSplits.nextElement();

                int w = x2 - x1;
                int px1 = x1 - getLeftPadding();
                int px2 = x2 + getRightPadding();
                int pw = px2 - px1;

                // Fetch the padded src rectangle
                Rectangle srcSubRect = new Rectangle(px1, py1, pw, ph);
                sources[0] = extender != null ? extendedIMG.getData(srcSubRect) : s
                        .getData(srcSubRect);

                // Make a destRectangle
                Rectangle dstSubRect = new Rectangle(x1, y1, w, h);
                computeRect(sources, dest, dstSubRect);

                // Recycle the source tile
                if (s.overlapsMultipleTiles(srcSubRect)) {
                    recycleTile(sources[0]);
                }
            }
        }
        return dest;
    }

    /**
     * This method provides a lazy initialization of the image associated to the ROI. The method uses the Double-checked locking in order to maintain
     * thread-safety
     * 
     * @return
     */
    protected PlanarImage getImage() {
        PlanarImage img = roiImage;
        if (img == null) {
            synchronized (this) {
                img = roiImage;
                if (img == null) {
                    roiImage = img = roi.getAsImage();
                }
            }
        }
        return img;
    }

    static enum Case {
        TOP_LEFT {
            @Override
            public void setWindow(double[] window, int i, int j, int[] srcData, int srcPixelOffset,
                    int centerScanlineOffset) {

                window[3] = srcData[centerScanlineOffset + 1];
                window[4] = srcData[centerScanlineOffset + 1];
                window[5] = srcData[centerScanlineOffset + 2];
                window[6] = srcData[centerScanlineOffset * 2 + 1];
                window[7] = srcData[centerScanlineOffset * 2 + 1];
                window[8] = srcData[centerScanlineOffset * 2 + 2];
                window[0] = interpolate(window[3], window[6]);
                window[1] = interpolate(window[4], window[7]);
                window[2] = interpolate(window[5], window[8]);
            }
        }

        ,
        TOP {
            @Override
            public void setWindow(double[] window, int i, int j, int[] srcData, int srcPixelOffset,
                    int centerScanlineOffset) {

                window[3] = srcData[centerScanlineOffset + i];
                window[4] = srcData[centerScanlineOffset + i + 1];
                window[5] = srcData[centerScanlineOffset + i + 2];
                window[6] = srcData[centerScanlineOffset * 2 + i];
                window[7] = srcData[centerScanlineOffset * 2 + i + 1];
                window[8] = srcData[centerScanlineOffset * 2 + i + 2];
                window[0] = interpolate(window[3], window[6]);
                window[1] = interpolate(window[4], window[7]);
                window[2] = interpolate(window[5], window[8]);

            }
        }

        ,
        TOP_RIGHT {
            @Override
            public void setWindow(double[] window, int i, int j, int[] srcData, int srcPixelOffset,
                    int centerScanlineOffset) {
                window[3] = srcData[centerScanlineOffset + i];
                window[4] = srcData[centerScanlineOffset + i + 1];
                window[5] = srcData[centerScanlineOffset + i + 1];
                window[6] = srcData[centerScanlineOffset * 2 + i];
                window[7] = srcData[centerScanlineOffset * 2 + i + 1];
                window[8] = srcData[centerScanlineOffset * 2 + i + 1];
                window[0] = interpolate(window[3], window[6]);
                window[1] = interpolate(window[4], window[7]);
                window[2] = interpolate(window[5], window[8]);
            }
        }

        ,
        LEFT {
            @Override
            public void setWindow(double[] window, int i, int j, int[] srcData, int srcPixelOffset,
                    int centerScanlineOffset) {
                window[1] = srcData[srcPixelOffset + i + 1];
                window[2] = srcData[srcPixelOffset + i + 2];
                window[0] = interpolate(window[1], window[2]);

                window[4] = srcData[srcPixelOffset + centerScanlineOffset + i + 1];
                window[5] = srcData[srcPixelOffset + centerScanlineOffset + i + 2];
                window[3] = interpolate(window[4], window[5]);

                window[7] = srcData[srcPixelOffset + centerScanlineOffset * 2 + i + 1];
                window[8] = srcData[srcPixelOffset + centerScanlineOffset * 2 + i + 2];
                window[6] = interpolate(window[7], window[8]);
            }
        }

        ,
        STANDARD {
            @Override
            public void setWindow(double[] window, int i, int j, int[] srcData, int srcPixelOffset,
                    int centerScanlineOffset) {
                window[0] = srcData[srcPixelOffset];
                window[1] = srcData[srcPixelOffset + 1];
                window[2] = srcData[srcPixelOffset + 2];
                window[3] = srcData[srcPixelOffset + centerScanlineOffset];
                window[4] = srcData[srcPixelOffset + centerScanlineOffset + 1];
                window[5] = srcData[srcPixelOffset + centerScanlineOffset + 2];
                window[6] = srcData[srcPixelOffset + centerScanlineOffset * 2];
                window[7] = srcData[srcPixelOffset + centerScanlineOffset * 2 + 1];
                window[8] = srcData[srcPixelOffset + centerScanlineOffset * 2 + 2];
            }
        }

        ,
        RIGHT {
            @Override
            public void setWindow(double[] window, int i, int j, int[] srcData, int srcPixelOffset,
                    int centerScanlineOffset) {
                window[0] = srcData[srcPixelOffset];
                window[1] = srcData[srcPixelOffset + 1];
                window[2] = interpolate(window[1], window[0]);
                window[3] = srcData[srcPixelOffset + centerScanlineOffset];
                window[4] = srcData[srcPixelOffset + centerScanlineOffset + 1];
                window[5] = interpolate(window[4], window[3]);
                window[6] = srcData[srcPixelOffset + centerScanlineOffset * 2];
                window[7] = srcData[srcPixelOffset + centerScanlineOffset * 2 + 1];
                window[8] = interpolate(window[7], window[6]);
            }
        }

        ,
        BOTTOM_LEFT {
            @Override
            public void setWindow(double[] window, int i, int j, int[] srcData, int srcPixelOffset,
                    int centerScanlineOffset) {
                window[0] = srcData[srcPixelOffset + 1];
                window[1] = srcData[srcPixelOffset + 1];
                window[2] = srcData[srcPixelOffset + 2];
                window[3] = srcData[srcPixelOffset + centerScanlineOffset + 1];
                window[4] = srcData[srcPixelOffset + centerScanlineOffset + 1];
                window[5] = srcData[srcPixelOffset + centerScanlineOffset + 2];
                window[6] = interpolate(window[3], window[0]);
                window[7] = interpolate(window[4], window[1]);
                window[8] = interpolate(window[5], window[2]);
            }
        },
        BOTTOM {
            @Override
            public void setWindow(double[] window, int i, int j, int[] srcData, int srcPixelOffset,
                    int centerScanlineOffset) {
                window[0] = srcData[srcPixelOffset];
                window[1] = srcData[srcPixelOffset + 1];
                window[2] = srcData[srcPixelOffset + 2];
                window[3] = srcData[srcPixelOffset + centerScanlineOffset];
                window[4] = srcData[srcPixelOffset + centerScanlineOffset + 1];
                window[5] = srcData[srcPixelOffset + centerScanlineOffset + 2];
                window[6] = interpolate(window[3], window[0]);
                window[7] = interpolate(window[4], window[1]);
                window[8] = interpolate(window[5], window[2]);

            }
        },
        BOTTOM_RIGHT {
            @Override
            public void setWindow(double[] window, int i, int j, int[] srcData, int srcPixelOffset,
                    int centerScanlineOffset) {
                window[0] = srcData[srcPixelOffset];
                window[1] = srcData[srcPixelOffset + 1];
                window[2] = srcData[srcPixelOffset + 1];
                window[3] = srcData[srcPixelOffset + centerScanlineOffset];
                window[4] = srcData[srcPixelOffset + centerScanlineOffset + 1];
                window[5] = srcData[srcPixelOffset + centerScanlineOffset + 1];
                window[6] = interpolate(window[3], window[0]);
                window[7] = interpolate(window[4], window[1]);
                window[8] = interpolate(window[5], window[2]);
            }
        };

        public void setWindow(double[] window, int i, int j, int[] srcData, int srcPixelOffset,
                int centerScanlineOffset) {
            // TODO Auto-generated method stub

        }

        public final double interpolate(double a, double b) {
            return ((2 * (a)) - (b));

        }

    }

    protected void intLoop(RasterAccessor src, RasterAccessor dst, RandomIter roiIter,
            boolean roiContainsTile) {
        int dwidth = dst.getWidth();
        int dheight = dst.getHeight();

        int dstDataArrays[][] = dst.getIntDataArrays();
        int dstBandOffsets[] = dst.getBandOffsets();
        int dstPixelStride = dst.getPixelStride();
        int dstScanlineStride = dst.getScanlineStride();

        int srcDataArrays[][] = src.getIntDataArrays();
        int srcBandOffsets[] = src.getBandOffsets();
        int srcPixelStride = src.getPixelStride();
        int srcScanlineStride = src.getScanlineStride();

        // precalcaculate offsets
        int centerScanlineOffset = srcScanlineStride;
        int dstX = dst.getX();
        int dstY = dst.getY();

        double[] window = new double[9];

        int dstData[] = dstDataArrays[0];
        int srcData[] = srcDataArrays[0];
        int srcScanlineOffset = srcBandOffsets[0];
        int dstScanlineOffset = dstBandOffsets[0];
        for (int j = 0; j < dheight; j++) {
            int srcPixelOffset = srcScanlineOffset;
            int dstPixelOffset = dstScanlineOffset;
            for (int i = 0; i < dwidth; i++) {
                int sX = i + dstX;
                int sY = j + dstY;
                Case currentCase = getCase(sX, sY);
                currentCase.setWindow(window, i, j, srcData, srcPixelOffset, centerScanlineOffset);
                dstData[dstPixelOffset] = ImageUtil.clampRoundInt(algorithm
                        .getValue(window, params));
                srcPixelOffset += srcPixelStride;
                dstPixelOffset += dstPixelStride;
            }
            srcScanlineOffset += srcScanlineStride;
            dstScanlineOffset += dstScanlineStride;
        }
        //
        //

        // Valid data
        // if (caseA || (caseB && roiContainsTile)) {
        // for (int k = 0; k < dnumBands; k++) {
        // short dstData[] = dstDataArrays[k];
        // short srcData[] = srcDataArrays[k];
        // int srcScanlineOffset = srcBandOffsets[k];
        // int dstScanlineOffset = dstBandOffsets[k];
        // for (int j = 0; j < dheight; j++) {
        // int srcPixelOffset = srcScanlineOffset;
        // int dstPixelOffset = dstScanlineOffset;
        // for (int i = 0; i < dwidth; i++) {
        // short s0 = srcData[srcPixelOffset];
        // short s1 = srcData[srcPixelOffset + middlePixelOffset];
        // short s2 = srcData[srcPixelOffset + rightPixelOffset];
        // short s3 = srcData[srcPixelOffset + centerScanlineOffset];
        // short s4 = srcData[srcPixelOffset + centerScanlineOffset
        // + middlePixelOffset];
        // short s5 = srcData[srcPixelOffset + centerScanlineOffset + rightPixelOffset];
        // short s6 = srcData[srcPixelOffset + bottomScanlineOffset];
        // short s7 = srcData[srcPixelOffset + bottomScanlineOffset
        // + middlePixelOffset];
        // short s8 = srcData[srcPixelOffset + bottomScanlineOffset + rightPixelOffset];
        // float f = k0 * s0 + k1 * s1 + k2 * s2 + k3 * s3 + k4 * s4 + k5 * s5 + k6
        // * s6 + k7 * s7 + k8 * s8;
        //
        // dstData[dstPixelOffset] = ImageUtil.clampRoundShort(f);
        // srcPixelOffset += srcPixelStride;
        // dstPixelOffset += dstPixelStride;
        // }
        // srcScanlineOffset += srcScanlineStride;
        // dstScanlineOffset += dstScanlineStride;
        // }
        // }
        // // ROI Check
        // } else if (caseB) {
        // for (int k = 0; k < dnumBands; k++) {
        // short dstData[] = dstDataArrays[k];
        // short srcData[] = srcDataArrays[k];
        // int srcScanlineOffset = srcBandOffsets[k];
        // int dstScanlineOffset = dstBandOffsets[k];
        // for (int j = 0; j < dheight; j++) {
        // int srcPixelOffset = srcScanlineOffset;
        // int dstPixelOffset = dstScanlineOffset;
        //
        // y0 = srcY + j;
        //
        // for (int i = 0; i < dwidth; i++) {
        //
        // x0 = srcX + i;
        //
        // boolean inROI = false;
        // // ROI Check
        // for (int y = 0; y < kh && !inROI; y++) {
        // int yI = y0 + y;
        // for (int x = 0; x < kw && !inROI; x++) {
        // int xI = x0 + x;
        // if (roiBounds.contains(xI, yI) && roiIter.getSample(xI, yI, 0) > 0) {
        // inROI = true;
        // }
        // }
        // }
        //
        // short s0 = srcData[srcPixelOffset];
        // short s1 = srcData[srcPixelOffset + middlePixelOffset];
        // short s2 = srcData[srcPixelOffset + rightPixelOffset];
        // short s3 = srcData[srcPixelOffset + centerScanlineOffset];
        // short s4 = srcData[srcPixelOffset + centerScanlineOffset
        // + middlePixelOffset];
        // short s5 = srcData[srcPixelOffset + centerScanlineOffset + rightPixelOffset];
        // short s6 = srcData[srcPixelOffset + bottomScanlineOffset];
        // short s7 = srcData[srcPixelOffset + bottomScanlineOffset
        // + middlePixelOffset];
        // short s8 = srcData[srcPixelOffset + bottomScanlineOffset + rightPixelOffset];
        // float f = k0 * s0 + k1 * s1 + k2 * s2 + k3 * s3 + k4 * s4 + k5 * s5 + k6
        // * s6 + k7 * s7 + k8 * s8;
        //
        // if (inROI) {
        // dstData[dstPixelOffset] = ImageUtil.clampRoundShort(f);
        // } else {
        // dstData[dstPixelOffset] = destNoDataShort;
        // }
        //
        // srcPixelOffset += srcPixelStride;
        // dstPixelOffset += dstPixelStride;
        // }
        // srcScanlineOffset += srcScanlineStride;
        // dstScanlineOffset += dstScanlineStride;
        // }
        // }
        // // NoData Check
        // } else
        // if (caseC || (hasROI && hasNoData && roiContainsTile)) {
        // for (int k = 0; k < dnumBands; k++) {
        // short dstData[] = dstDataArrays[k];
        // short srcData[] = srcDataArrays[k];
        // int srcScanlineOffset = srcBandOffsets[k];
        // int dstScanlineOffset = dstBandOffsets[k];
        // for (int j = 0; j < dheight; j++) {
        // int srcPixelOffset = srcScanlineOffset;
        // int dstPixelOffset = dstScanlineOffset;
        // for (int i = 0; i < dwidth; i++) {
        // short s0 = srcData[srcPixelOffset];
        // short s1 = srcData[srcPixelOffset + middlePixelOffset];
        // short s2 = srcData[srcPixelOffset + rightPixelOffset];
        // short s3 = srcData[srcPixelOffset + centerScanlineOffset];
        // short s4 = srcData[srcPixelOffset + centerScanlineOffset
        // + middlePixelOffset];
        // short s5 = srcData[srcPixelOffset + centerScanlineOffset + rightPixelOffset];
        // short s6 = srcData[srcPixelOffset + bottomScanlineOffset];
        // short s7 = srcData[srcPixelOffset + bottomScanlineOffset
        // + middlePixelOffset];
        // short s8 = srcData[srcPixelOffset + bottomScanlineOffset + rightPixelOffset];
        //
        // boolean isValid = true;
        // // Boolean indicating NoData values
        // boolean nod0 = noData.contains(s0);
        // boolean nod1 = noData.contains(s1);
        // boolean nod2 = noData.contains(s2);
        // boolean nod3 = noData.contains(s3);
        // boolean nod4 = noData.contains(s4);
        // boolean nod5 = noData.contains(s5);
        // boolean nod6 = noData.contains(s6);
        // boolean nod7 = noData.contains(s7);
        // boolean nod8 = noData.contains(s8);
        // // Check if nodata must be skipped
        // if (skipNoData) {
        // isValid = !(nod0 || nod1 || nod2 || nod3 || nod4 || nod5 || nod6
        // || nod7 || nod8);
        // }
        // // NoData Check
        // if (isValid) {
        // float f = k0 * (nod0 ? 0 : s0) + k1 * (nod1 ? 0 : s1) + k2
        // * (nod2 ? 0 : s2) + k3 * (nod3 ? 0 : s3) + k4 * (nod4 ? 0 : s4)
        // + k5 * (nod5 ? 0 : s5) + k6 * (nod6 ? 0 : s6) + k7
        // * (nod7 ? 0 : s7) + k8 * (nod8 ? 0 : s8);
        // // Clamping data
        // dstData[dstPixelOffset] = ImageUtil.clampRoundShort(f);
        // } else {
        // dstData[dstPixelOffset] = destNoDataShort;
        // }
        // srcPixelOffset += srcPixelStride;
        // dstPixelOffset += dstPixelStride;
        // }
        // srcScanlineOffset += srcScanlineStride;
        // dstScanlineOffset += dstScanlineStride;
        // }
        // }
        // // ROI and NoData Check
        // } else {
        // for (int k = 0; k < dnumBands; k++) {
        // short dstData[] = dstDataArrays[k];
        // short srcData[] = srcDataArrays[k];
        // int srcScanlineOffset = srcBandOffsets[k];
        // int dstScanlineOffset = dstBandOffsets[k];
        // for (int j = 0; j < dheight; j++) {
        // int srcPixelOffset = srcScanlineOffset;
        // int dstPixelOffset = dstScanlineOffset;
        //
        // y0 = srcY + j;
        //
        // for (int i = 0; i < dwidth; i++) {
        //
        // x0 = srcX + i;
        //
        // boolean inROI = false;
        // // ROI Check
        // for (int y = 0; y < kh && !inROI; y++) {
        // int yI = y0 + y;
        // for (int x = 0; x < kw && !inROI; x++) {
        // int xI = x0 + x;
        // if (roiBounds.contains(xI, yI) && roiIter.getSample(xI, yI, 0) > 0) {
        // inROI = true;
        // }
        // }
        // }
        //
        // short s0 = srcData[srcPixelOffset];
        // short s1 = srcData[srcPixelOffset + middlePixelOffset];
        // short s2 = srcData[srcPixelOffset + rightPixelOffset];
        // short s3 = srcData[srcPixelOffset + centerScanlineOffset];
        // short s4 = srcData[srcPixelOffset + centerScanlineOffset
        // + middlePixelOffset];
        // short s5 = srcData[srcPixelOffset + centerScanlineOffset + rightPixelOffset];
        // short s6 = srcData[srcPixelOffset + bottomScanlineOffset];
        // short s7 = srcData[srcPixelOffset + bottomScanlineOffset
        // + middlePixelOffset];
        // short s8 = srcData[srcPixelOffset + bottomScanlineOffset + rightPixelOffset];
        //
        // boolean isValid = true;
        // // Boolean indicating NoData values
        // boolean nod0 = noData.contains(s0);
        // boolean nod1 = noData.contains(s1);
        // boolean nod2 = noData.contains(s2);
        // boolean nod3 = noData.contains(s3);
        // boolean nod4 = noData.contains(s4);
        // boolean nod5 = noData.contains(s5);
        // boolean nod6 = noData.contains(s6);
        // boolean nod7 = noData.contains(s7);
        // boolean nod8 = noData.contains(s8);
        // // Check if nodata must be skipped
        // if (skipNoData) {
        // isValid = !(nod0 || nod1 || nod2 || nod3 || nod4 || nod5 || nod6
        // || nod7 || nod8);
        // }
        // if (inROI && isValid) {
        // float f = k0 * (nod0 ? 0 : s0) + k1 * (nod1 ? 0 : s1) + k2
        // * (nod2 ? 0 : s2) + k3 * (nod3 ? 0 : s3) + k4 * (nod4 ? 0 : s4)
        // + k5 * (nod5 ? 0 : s5) + k6 * (nod6 ? 0 : s6) + k7
        // * (nod7 ? 0 : s7) + k8 * (nod8 ? 0 : s8);
        // // Clamping data
        // dstData[dstPixelOffset] = ImageUtil.clampRoundShort(f);
        // } else {
        // dstData[dstPixelOffset] = destNoDataShort;
        // }
        //
        // srcPixelOffset += srcPixelStride;
        // dstPixelOffset += dstPixelStride;
        // }
        // srcScanlineOffset += srcScanlineStride;
        // dstScanlineOffset += dstScanlineStride;
        // }
        // }
        // }

    }

    private Case getCase(int i, int j) {
        if (i == minX && j == minY) {
            return Case.TOP_LEFT;
        } else if (i == maxX && j == minY) {
            return Case.TOP_RIGHT;
        } else if (j == minY) {
            return Case.TOP;
        } else if (i == minX && j == maxY) {
            return Case.BOTTOM_LEFT;
        } else if (i == maxX && j == maxY) {
            return Case.BOTTOM_RIGHT;
        } else if (i == minX) {
            return Case.LEFT;
        } else if (i == maxX) {
            return Case.RIGHT;
        } else if (j == maxY) {
            return Case.BOTTOM;
        } else {
            return Case.STANDARD;
        }

    }

    public static void main(String[] args) throws IOException {
        JAIExt.initJAIEXT();
        OperationRegistry registry = JAI.getDefaultInstance().getOperationRegistry();
        OperationDescriptor op = new ShadedReliefDescriptor();
        registry.registerDescriptor(op);
        String descName = op.getName();
        RenderedImageFactory rif = new ShadedReliefRIF();
        registry.registerFactory(RenderedRegistryMode.MODE_NAME, descName,
                "org.geotools.gce.processing", rif);

        String dem = "C:\\data\\DEM\\test\\r1c2.tif";
        final File file = new File(dem);
        GeoTiffReader reader = new GeoTiffReader(file);

        ParameterValue<String> tileSize = GeoTiffFormat.SUGGESTED_TILE_SIZE.createValue();
        tileSize.setValue("480,600");

        MathTransform g2w = reader.getOriginalGridToWorld(PixelInCell.CELL_CENTER);
        AffineTransform af = (AffineTransform) g2w;
        double resX = af.getScaleX();
        double resY = af.getScaleY();
        // GridCoverage2D gc = reader.read(new GeneralParameterValue[]{tileSize});
        GridCoverage2D gc = reader.read(null);
        RenderedImage ri = gc.getRenderedImage();
        ColorModel cm = ri.getColorModel();
        final int w = ri.getWidth();
        final int h = ri.getHeight();
        ColorModel cm2 = RasterFactory.createComponentColorModel(DataBuffer.TYPE_BYTE,
                ColorSpace.getInstance(ColorSpace.CS_GRAY), false, false, cm.getTransparency());

        WritableRaster wRaster = (WritableRaster) RasterFactory.createWritableRaster(
                cm2.createCompatibleSampleModel(w, h), new Point(0, 0));
        BufferedImage bi = new BufferedImage(cm2, wRaster, false, null);
        ImageLayout layout = new ImageLayout(bi);

        RenderingHints hints = new RenderingHints(JAI.KEY_IMAGE_LAYOUT, layout);
        RenderedOp finalImage = ShadedReliefDescriptor.create(ri, null, null, resX, resY,
                DEFAULT_Z, DEFAULT_SCALE, DEFAULT_ALTITUDE, DEFAULT_AZIMUTH, Algorithm.COMBINED,
                true, hints);

        // Raster raster = ri.getData();

        long time = System.currentTimeMillis();
        GeoTiffWriter writer = new GeoTiffWriter(new File("c:/data/DEM/test/hillshadetest_"
                + time + ".tif"));
        GridCoverageFactory factory = new GridCoverageFactory();
        GridCoverage2D gc2 = factory.create("hillshade", finalImage, reader.getOriginalEnvelope());
        writer.write(gc2, null);
        writer.dispose();
    }

    public static HillShadeParams prepareParams(double resX, double resY, double zetaFactor,
            double scale, double altitude, double azimuth, Algorithm algorithm) {

        HillShadeParams params = new HillShadeParams();
        params.resY = resY;
        params.resX = resX;
        params.sineOfAltitude = Math.sin(altitude * DEGREES_TO_RADIANS);
        params.azimuthRadians = azimuth * DEGREES_TO_RADIANS;

        double zetaScaleFactor = zetaFactor / (algorithm.getFactor() * scale);
        params.zetaScaleFactor = zetaScaleFactor;
        params.cosinusOfaltitudeRadiansForZetaScaleFactor = Math.cos(altitude * DEGREES_TO_RADIANS)
                * zetaScaleFactor;
        params.squaredZetaScaleFactor = zetaScaleFactor * zetaScaleFactor;
        return params;

    }

    public final double interpolate(double a, double b) {
        return (hasNoData && (noData.contains(a) || noData.contains(b)) ? noDataDouble : (2 * (a))
                - (b));

    }

    static class HillShadeParams {
        double resY;

        double resX;

        double sineOfAltitude;

        double azimuthRadians;

        double zetaScaleFactor;

        double cosinusOfaltitudeRadiansForZetaScaleFactor;

        double squaredZetaScaleFactor;

        boolean isCombined;

        boolean isZevenbergenThorne;
    }

}
