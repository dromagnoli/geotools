package org.geotools.coverage.processing.operation;

import it.geosolutions.jaiext.border.BorderDescriptor;
import it.geosolutions.jaiext.iterators.RandomIterFactory;
import it.geosolutions.jaiext.range.Range;
import it.geosolutions.jaiext.range.RangeFactory;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Properties;

import javax.media.jai.AreaOpImage;
import javax.media.jai.BorderExtender;
import javax.media.jai.ImageLayout;
import javax.media.jai.IntegerSequence;
import javax.media.jai.KernelJAI;
import javax.media.jai.PlanarImage;
import javax.media.jai.ROI;
import javax.media.jai.ROIShape;
import javax.media.jai.RasterAccessor;
import javax.media.jai.RasterFormatTag;
import javax.media.jai.iterator.RandomIter;

import com.sun.media.jai.util.ImageUtil;

public class ShadedReliefOpImage extends AreaOpImage {

    private static String DEBUG_PATH = System.getProperty("org.geotools.shadedrelief.debugProperties");

    private static final BorderExtender EXTENDER = BorderExtender
            .createInstance(BorderExtender.BORDER_ZERO);

    private static final double DEGREES_TO_RADIANS = Math.PI / 180.0;

    private static final double SQUARED_PI_2 = Math.PI * Math.PI / 4;

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

    /** Boolean indicating that no roi and no data check must be done */
    protected final boolean caseA;

    /** Boolean indicating that only roi check must be done */
    protected final boolean caseB;

    /** Boolean indicating that only no data check must be done */
    protected final boolean caseC;
    
    /** LookupTable used for checking if an input byte sample is a NoData */
    protected boolean[] lut;

    /** Boolean indicating that ROI must be checked */
    protected final boolean hasROI;

    /** ROI element */
    protected ROI roi;

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

    protected RenderedImage extendedIMG;

    protected Rectangle destBounds;

    private double noDataDouble;

    private int maxX;

    private int maxY;

    private Algorithm algorithm;

    private HillShadeParams params;

    public ShadedReliefOpImage(RenderedImage source, RenderingHints hints, ImageLayout l,
            ROI roi,
            Range noData, double destinationNoData,
            double resX, double resY, double verticalExaggeration, double verticalScale,
            double altitude, double azimuth, Algorithm algorithm, boolean computeEdge) {
        super(source, l, hints, true, EXTENDER, 1, 1, 1, 1);

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

        // Getting datatype
        int dataType = source.getSampleModel().getDataType();

        // Check if No Data control must be done
        if (noData != null) {
            hasNoData = true;
            this.noData = noData;
            this.noDataDouble = noData.getMin().doubleValue();
        } else {
            hasNoData = false;
        }

        if (hasNoData && dataType == DataBuffer.TYPE_BYTE) {
            initBooleanNoDataTable();
        }
        
     // Definition of the possible cases that can be found
        // caseA = no ROI nor No Data
        // caseB = ROI present but No Data not present
        // caseC = No Data present but ROI not present
        // Last case not defined = both ROI and No Data are present
        caseA = !hasNoData && !hasROI;
        caseB = !hasNoData && hasROI;
        caseC = hasNoData && !hasROI;
        


        // Destination No Data value is clamped to the image data type
        this.destNoDataDouble = destinationNoData;
        switch (dataType) {
        case DataBuffer.TYPE_BYTE:
            this.destNoDataByte = ImageUtil.clampRoundByte(destinationNoData);
            break;
        case DataBuffer.TYPE_USHORT:
            this.destNoDataShort = ImageUtil.clampRoundUShort(destinationNoData);
            break;
        case DataBuffer.TYPE_SHORT:
            this.destNoDataShort = ImageUtil.clampRoundShort(destinationNoData);
            break;
        case DataBuffer.TYPE_INT:
            this.destNoDataInt = ImageUtil.clampRoundInt(destinationNoData);
            break;
        case DataBuffer.TYPE_FLOAT:
            this.destNoDataFloat = ImageUtil.clampFloat(destinationNoData);
            break;
        case DataBuffer.TYPE_DOUBLE:
            break;
        default:
            throw new IllegalArgumentException("Wrong image data type");
        }

        this.algorithm = algorithm;
        this.params = prepareParams(resX, resY, verticalExaggeration, verticalScale, altitude,
                azimuth);
        
        if (this.extender != null) {
            extendedIMG = BorderDescriptor.create(source, leftPadding, rightPadding, topPadding,
                    bottomPadding, extender, this.noData, destinationNoData ,
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

    private void initBooleanNoDataTable() {
        // Initialization of the boolean lookup table
        lut = new boolean[256];

        // Fill the lookuptable
        for (int i = 0; i < 256; i++) {
            boolean result = true;
            if (noData.contains((byte) i)) {
                result = false;
            }
            lut[i] = result;
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
        ROI roiTile = null;

        RandomIter roiIter = null;

        boolean roiContainsTile = false;
        boolean roiDisjointTile = false;

        // ROI check
        if (hasROI) {
            Rectangle srcRectExpanded = mapDestRect(destRect, 0);
            // The tile dimension is extended for avoiding border errors
            srcRectExpanded.setRect(srcRectExpanded.getMinX() - 1, srcRectExpanded.getMinY() - 1,
                    srcRectExpanded.getWidth() + 2, srcRectExpanded.getHeight() + 2);
            roiTile = roi.intersect(new ROIShape(srcRectExpanded));

            if (!roiBounds.intersects(srcRectExpanded)) {
                roiDisjointTile = true;
            } else {
                roiContainsTile = roiTile.contains(srcRectExpanded);
                if (!roiContainsTile) {
                    if (!roiTile.intersects(srcRectExpanded)) {
                        roiDisjointTile = true;
                    } else {
                        PlanarImage roiIMG = getImage();
                        roiIter = RandomIterFactory.create(roiIMG, null, TILE_CACHED, ARRAY_CALC);
                    }
                }
            }
        }

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

    abstract class DataProcessor {
        boolean hasNoData;
        Range noData;
        double noDataDouble;
        Algorithm algorithm;

        public DataProcessor (boolean hasNoData, Range noData, double noDataDouble, Algorithm algorithm) {
            this.hasNoData = hasNoData;
            this.noData = noData;
            this.noDataDouble = noDataDouble;
            this.algorithm = algorithm;
        }

        abstract double getValue(int index);

        public final double interpolate(double a, double b) {
            return (2 * (a)) - (b);
        }

        public final double interpolateNoData(double a, double b) {
            return (hasNoData && (noData.contains(a) || noData.contains(b))) ? noDataDouble : interpolate(a,b);
        }

        public double processWindow(double[] window, int i, int j, int srcPixelOffset,
                int centerScanlineOffset, ProcessingCase processingCase) {
            processingCase.setWindow(window, i, j, srcPixelOffset, centerScanlineOffset, this);
            return algorithm.getValue(window, params);
        }

        public double processWindowNoData(double[] window, int i, int j, int srcPixelOffset,
                int centerScanlineOffset, ProcessingCase processingCase) {
            processingCase.setWindowNoData(window, i, j, srcPixelOffset, centerScanlineOffset, this);
            if (isNoData(window[4])) {
                // Return NaN in case of noData. The caller will properly remap it to the proper noDataType
                return Double.NaN;
            } else {
                for (int index = 0; index < 9; index++) {
                    if (isNoData(window[index])) {
                        window[index] = window[4];
                    }
                }
                return algorithm.getValue(window, params);
            }
        }

        private boolean isNoData(double value) {
            return (hasNoData && noData.contains(value));
        }
    }

    class DataProcessorShort extends DataProcessor {
        short[] srcDataShort;
        public DataProcessorShort(short[] data, boolean hasNoData, Range noData, double noDataDouble, Algorithm algorithm) {
            super(hasNoData, noData, noDataDouble, algorithm);
            srcDataShort = data;
        }
        @Override
        double getValue(int index) {
            return srcDataShort[index];
        }
    }

    class DataProcessorInt extends DataProcessor {
        int[] srcDataInt;
        public DataProcessorInt(int[] data, boolean hasNoData, Range noData, double noDataDouble, Algorithm algorithm) {
            super(hasNoData, noData, noDataDouble, algorithm);
            srcDataInt = data;
        }
        @Override
        double getValue(int index) {
            return srcDataInt[index];
        }
    }


    static enum ProcessingCase {
        TOP_LEFT {
            @Override
            public void setWindow(double[] window, int i, int j, int srcPixelOffset,
                    int centerScanlineOffset, DataProcessor data) {

                window[3] = data.getValue(centerScanlineOffset + 1);
                window[4] = data.getValue(centerScanlineOffset + 1);
                window[5] = data.getValue(centerScanlineOffset + 2);
                window[6] = data.getValue(centerScanlineOffset * 2 + 1);
                window[7] = data.getValue(centerScanlineOffset * 2 + 1);
                window[8] = data.getValue(centerScanlineOffset * 2 + 2);
                window[0] = data.interpolate(window[3], window[6]);
                window[1] = data.interpolate(window[4], window[7]);
                window[2] = data.interpolate(window[5], window[8]);
            }
            @Override
            public void setWindowNoData(double[] window, int i, int j, int srcPixelOffset,
                    int centerScanlineOffset, DataProcessor data) {

                window[3] = data.getValue(centerScanlineOffset + 1);
                window[4] = data.getValue(centerScanlineOffset + 1);
                window[5] = data.getValue(centerScanlineOffset + 2);
                window[6] = data.getValue(centerScanlineOffset * 2 + 1);
                window[7] = data.getValue(centerScanlineOffset * 2 + 1);
                window[8] = data.getValue(centerScanlineOffset * 2 + 2);
                window[0] = data.interpolateNoData(window[3], window[6]);
                window[1] = data.interpolateNoData(window[4], window[7]);
                window[2] = data.interpolateNoData(window[5], window[8]);
            }
        },

        TOP {
            @Override
            public void setWindow(double[] window, int i, int j, int srcPixelOffset,
                    int centerScanlineOffset, DataProcessor data) {

                window[3] = data.getValue(centerScanlineOffset + i);
                window[4] = data.getValue(centerScanlineOffset + i + 1);
                window[5] = data.getValue(centerScanlineOffset + i + 2);
                window[6] = data.getValue(centerScanlineOffset * 2 + i);
                window[7] = data.getValue(centerScanlineOffset * 2 + i + 1);
                window[8] = data.getValue(centerScanlineOffset * 2 + i + 2);
                window[0] = data.interpolate(window[3], window[6]);
                window[1] = data.interpolate(window[4], window[7]);
                window[2] = data.interpolate(window[5], window[8]);

            }
            @Override
            public void setWindowNoData(double[] window, int i, int j, int srcPixelOffset,
                    int centerScanlineOffset, DataProcessor data) {

                window[3] = data.getValue(centerScanlineOffset + i);
                window[4] = data.getValue(centerScanlineOffset + i + 1);
                window[5] = data.getValue(centerScanlineOffset + i + 2);
                window[6] = data.getValue(centerScanlineOffset * 2 + i);
                window[7] = data.getValue(centerScanlineOffset * 2 + i + 1);
                window[8] = data.getValue(centerScanlineOffset * 2 + i + 2);
                window[0] = data.interpolateNoData(window[3], window[6]);
                window[1] = data.interpolateNoData(window[4], window[7]);
                window[2] = data.interpolateNoData(window[5], window[8]);

            }
        },

        TOP_RIGHT {
            @Override
            public void setWindow(double[] window, int i, int j, int srcPixelOffset,
                    int centerScanlineOffset, DataProcessor data) {
                window[3] = data.getValue(centerScanlineOffset + i);
                window[4] = data.getValue(centerScanlineOffset + i + 1);
                window[5] = data.getValue(centerScanlineOffset + i + 1);
                window[6] = data.getValue(centerScanlineOffset * 2 + i);
                window[7] = data.getValue(centerScanlineOffset * 2 + i + 1);
                window[8] = data.getValue(centerScanlineOffset * 2 + i + 1);
                window[0] = data.interpolate(window[3], window[6]);
                window[1] = data.interpolate(window[4], window[7]);
                window[2] = data.interpolate(window[5], window[8]);
            }
            @Override
            public void setWindowNoData(double[] window, int i, int j, int srcPixelOffset,
                    int centerScanlineOffset, DataProcessor data) {
                window[3] = data.getValue(centerScanlineOffset + i);
                window[4] = data.getValue(centerScanlineOffset + i + 1);
                window[5] = data.getValue(centerScanlineOffset + i + 1);
                window[6] = data.getValue(centerScanlineOffset * 2 + i);
                window[7] = data.getValue(centerScanlineOffset * 2 + i + 1);
                window[8] = data.getValue(centerScanlineOffset * 2 + i + 1);
                window[0] = data.interpolateNoData(window[3], window[6]);
                window[1] = data.interpolateNoData(window[4], window[7]);
                window[2] = data.interpolateNoData(window[5], window[8]);
            }
        },

        LEFT {
            @Override
            public void setWindow(double[] window, int i, int j, int srcPixelOffset,
                    int centerScanlineOffset, DataProcessor data) {
                window[1] = data.getValue(srcPixelOffset + i + 1);
                window[2] = data.getValue(srcPixelOffset + i + 2);
                window[4] = data.getValue(srcPixelOffset + centerScanlineOffset + i + 1);
                window[5] = data.getValue(srcPixelOffset + centerScanlineOffset + i + 2);
                window[7] = data.getValue(srcPixelOffset + centerScanlineOffset * 2 + i + 1);
                window[8] = data.getValue(srcPixelOffset + centerScanlineOffset * 2 + i + 2);
                window[0] = data.interpolate(window[1], window[2]);
                window[3] = data.interpolate(window[4], window[5]);
                window[6] = data.interpolate(window[7], window[8]);
            }
            @Override
            public void setWindowNoData(double[] window, int i, int j, int srcPixelOffset,
                    int centerScanlineOffset, DataProcessor data) {
                window[1] = data.getValue(srcPixelOffset + i + 1);
                window[2] = data.getValue(srcPixelOffset + i + 2);
                window[4] = data.getValue(srcPixelOffset + centerScanlineOffset + i + 1);
                window[5] = data.getValue(srcPixelOffset + centerScanlineOffset + i + 2);
                window[7] = data.getValue(srcPixelOffset + centerScanlineOffset * 2 + i + 1);
                window[8] = data.getValue(srcPixelOffset + centerScanlineOffset * 2 + i + 2);
                window[0] = data.interpolateNoData(window[1], window[2]);
                window[3] = data.interpolateNoData(window[4], window[5]);
                window[6] = data.interpolateNoData(window[7], window[8]);
            }
        },

        STANDARD {
            @Override
            public void setWindow(double[] window, int i, int j, int srcPixelOffset,
                    int centerScanlineOffset, DataProcessor data) {
                window[0] = data.getValue(srcPixelOffset);
                window[1] = data.getValue(srcPixelOffset + 1);
                window[2] = data.getValue(srcPixelOffset + 2);
                window[3] = data.getValue(srcPixelOffset + centerScanlineOffset);
                window[4] = data.getValue(srcPixelOffset + centerScanlineOffset + 1);
                window[5] = data.getValue(srcPixelOffset + centerScanlineOffset + 2);
                window[6] = data.getValue(srcPixelOffset + centerScanlineOffset * 2);
                window[7] = data.getValue(srcPixelOffset + centerScanlineOffset * 2 + 1);
                window[8] = data.getValue(srcPixelOffset + centerScanlineOffset * 2 + 2);
            }
            @Override
            public void setWindowNoData(double[] window, int i, int j, int srcPixelOffset,
                    int centerScanlineOffset, DataProcessor data) {
                setWindow(window, i,j, srcPixelOffset, centerScanlineOffset, data);
            }
        },

        RIGHT {
            @Override
            public void setWindow(double[] window, int i, int j, int srcPixelOffset,
                    int centerScanlineOffset, DataProcessor data) {
                window[0] = data.getValue(srcPixelOffset);
                window[1] = data.getValue(srcPixelOffset + 1);
                window[3] = data.getValue(srcPixelOffset + centerScanlineOffset);
                window[4] = data.getValue(srcPixelOffset + centerScanlineOffset + 1);
                window[6] = data.getValue(srcPixelOffset + centerScanlineOffset * 2);
                window[7] = data.getValue(srcPixelOffset + centerScanlineOffset * 2 + 1);
                window[2] = data.interpolate(window[1], window[0]);
                window[5] = data.interpolate(window[4], window[3]);
                window[8] = data.interpolate(window[7], window[6]);
            }
            @Override
            public void setWindowNoData(double[] window, int i, int j, int srcPixelOffset,
                    int centerScanlineOffset, DataProcessor data) {
                window[0] = data.getValue(srcPixelOffset);
                window[1] = data.getValue(srcPixelOffset + 1);
                window[3] = data.getValue(srcPixelOffset + centerScanlineOffset);
                window[4] = data.getValue(srcPixelOffset + centerScanlineOffset + 1);
                window[6] = data.getValue(srcPixelOffset + centerScanlineOffset * 2);
                window[7] = data.getValue(srcPixelOffset + centerScanlineOffset * 2 + 1);
                window[2] = data.interpolateNoData(window[1], window[0]);
                window[5] = data.interpolateNoData(window[4], window[3]);
                window[8] = data.interpolateNoData(window[7], window[6]);
            }
        },

        BOTTOM_LEFT {
            @Override
            public void setWindow(double[] window, int i, int j, int srcPixelOffset,
                    int centerScanlineOffset, DataProcessor data) {
                window[0] = data.getValue(srcPixelOffset + 1);
                window[1] = data.getValue(srcPixelOffset + 1);
                window[2] = data.getValue(srcPixelOffset + 2);
                window[3] = data.getValue(srcPixelOffset + centerScanlineOffset + 1);
                window[4] = data.getValue(srcPixelOffset + centerScanlineOffset + 1);
                window[5] = data.getValue(srcPixelOffset + centerScanlineOffset + 2);
                window[6] = data.interpolate(window[3], window[0]);
                window[7] = data.interpolate(window[4], window[1]);
                window[8] = data.interpolate(window[5], window[2]);
            }
            
            @Override
            public void setWindowNoData(double[] window, int i, int j, int srcPixelOffset,
                    int centerScanlineOffset, DataProcessor data) {
                window[0] = data.getValue(srcPixelOffset + 1);
                window[1] = data.getValue(srcPixelOffset + 1);
                window[2] = data.getValue(srcPixelOffset + 2);
                window[3] = data.getValue(srcPixelOffset + centerScanlineOffset + 1);
                window[4] = data.getValue(srcPixelOffset + centerScanlineOffset + 1);
                window[5] = data.getValue(srcPixelOffset + centerScanlineOffset + 2);
                window[6] = data.interpolateNoData(window[3], window[0]);
                window[7] = data.interpolateNoData(window[4], window[1]);
                window[8] = data.interpolateNoData(window[5], window[2]);
            }
        },

        BOTTOM {
            @Override
            public void setWindow(double[] window, int i, int j, int srcPixelOffset,
                    int centerScanlineOffset, DataProcessor data) {
                window[0] = data.getValue(srcPixelOffset);
                window[1] = data.getValue(srcPixelOffset + 1);
                window[2] = data.getValue(srcPixelOffset + 2);
                window[3] = data.getValue(srcPixelOffset + centerScanlineOffset);
                window[4] = data.getValue(srcPixelOffset + centerScanlineOffset + 1);
                window[5] = data.getValue(srcPixelOffset + centerScanlineOffset + 2);
                window[6] = data.interpolate(window[3], window[0]);
                window[7] = data.interpolate(window[4], window[1]);
                window[8] = data.interpolate(window[5], window[2]);

            }

            @Override
            public void setWindowNoData(double[] window, int i, int j, int srcPixelOffset,
                    int centerScanlineOffset, DataProcessor data) {
                window[0] = data.getValue(srcPixelOffset);
                window[1] = data.getValue(srcPixelOffset + 1);
                window[2] = data.getValue(srcPixelOffset + 2);
                window[3] = data.getValue(srcPixelOffset + centerScanlineOffset);
                window[4] = data.getValue(srcPixelOffset + centerScanlineOffset + 1);
                window[5] = data.getValue(srcPixelOffset + centerScanlineOffset + 2);
                window[6] = data.interpolateNoData(window[3], window[0]);
                window[7] = data.interpolateNoData(window[4], window[1]);
                window[8] = data.interpolateNoData(window[5], window[2]);

            }
        },

        BOTTOM_RIGHT {
            @Override
            public void setWindow(double[] window, int i, int j, int srcPixelOffset,
                    int centerScanlineOffset, DataProcessor data) {
                window[0] = data.getValue(srcPixelOffset);
                window[1] = data.getValue(srcPixelOffset + 1);
                window[2] = data.getValue(srcPixelOffset + 1);
                window[3] = data.getValue(srcPixelOffset + centerScanlineOffset);
                window[4] = data.getValue(srcPixelOffset + centerScanlineOffset + 1);
                window[5] = data.getValue(srcPixelOffset + centerScanlineOffset + 1);
                window[6] = data.interpolate(window[3], window[0]);
                window[7] = data.interpolate(window[4], window[1]);
                window[8] = data.interpolate(window[5], window[2]);
            }

            @Override
            public void setWindowNoData(double[] window, int i, int j, int srcPixelOffset,
                    int centerScanlineOffset, DataProcessor data) {
                window[0] = data.getValue(srcPixelOffset);
                window[1] = data.getValue(srcPixelOffset + 1);
                window[2] = data.getValue(srcPixelOffset + 1);
                window[3] = data.getValue(srcPixelOffset + centerScanlineOffset);
                window[4] = data.getValue(srcPixelOffset + centerScanlineOffset + 1);
                window[5] = data.getValue(srcPixelOffset + centerScanlineOffset + 1);
                window[6] = data.interpolateNoData(window[3], window[0]);
                window[7] = data.interpolateNoData(window[4], window[1]);
                window[8] = data.interpolateNoData(window[5], window[2]);
            }
        };

        abstract void setWindow(double[] window, int i, int j, int srcPixelOffset,
                int centerScanlineOffset, DataProcessor data);

        abstract void setWindowNoData(double[] window, int i, int j, int srcPixelOffset,
                int centerScanlineOffset, DataProcessor data);

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

        // X,Y positions
        int x0 = 0;
        int y0 = 0;
        int srcX = src.getX();
        int srcY = src.getY();

        double[] window = new double[9];

        int dstData[] = dstDataArrays[0];
        int srcData[] = srcDataArrays[0];
        int srcScanlineOffset = srcBandOffsets[0];
        int dstScanlineOffset = dstBandOffsets[0];
        int srcPixelOffset = srcScanlineOffset;
        int dstPixelOffset = dstScanlineOffset;
        double destValue = Double.NaN;
        DataProcessor data = new DataProcessorInt(srcData, hasNoData, noData, noDataDouble, algorithm);
        
//        if (caseA || (caseB && roiContainsTile)) {
            for (int j = 0; j < dheight; j++) {
                srcPixelOffset = srcScanlineOffset;
                dstPixelOffset = dstScanlineOffset;
                for (int i = 0; i < dwidth; i++) {
                    int sX = i + dstX;
                    int sY = j + dstY;
                    ProcessingCase currentCase = getCase(sX, sY);
                    destValue = data.processWindow(window, i, j, srcPixelOffset, centerScanlineOffset, currentCase);
                    dstData[dstPixelOffset] = ImageUtil.clampRoundInt(algorithm.getValue(window, params));
                    srcPixelOffset += srcPixelStride;
                    dstPixelOffset += dstPixelStride;
                }
                srcScanlineOffset += srcScanlineStride;
                dstScanlineOffset += dstScanlineStride;
            }
//            // ROI Check
//        } else if (caseB) {
//
//            for (int j = 0; j < dheight; j++) {
//                y0 = srcY + j;
//
//                for (int i = 0; i < dwidth; i++) {
//
//                    x0 = srcX + i;
//
//                    boolean inROI = false;
//                    // ROI Check
//                    for (int y = 0; y < 3 && !inROI; y++) {
//                        int yI = y0 + y;
//                        for (int x = 0; x < 3 && !inROI; x++) {
//                            int xI = x0 + x;
//                            if (roiBounds.contains(xI, yI) && roiIter.getSample(xI, yI, 0) > 0) {
//                                inROI = true;
//                            }
//                        }
//                    }
//
//                    if (inROI) {
//                        int sX = i + dstX;
//                        int sY = j + dstY;
//                        ProcessingCase currentCase = getCase(sX, sY);
//                        destValue = data.processWindow(window, i, j, srcPixelOffset,
//                                centerScanlineOffset, currentCase);
//                        dstData[dstPixelOffset] = ImageUtil.clampRoundInt(algorithm.getValue(
//                                window, params));
//                    } else {
//                        dstData[dstPixelOffset] = destNoDataInt;
//                    }
//
//                    srcPixelOffset += srcPixelStride;
//                    dstPixelOffset += dstPixelStride;
//                }
//                srcScanlineOffset += srcScanlineStride;
//                dstScanlineOffset += dstScanlineStride;
//
//            }
//        // NoData Check
//    } else if (caseC || (hasROI && hasNoData && roiContainsTile)) {
//        for (int j = 0; j < dheight; j++) {
//            srcPixelOffset = srcScanlineOffset;
//            dstPixelOffset = dstScanlineOffset;
//            for (int i = 0; i < dwidth; i++) {
//                int sX = i + dstX;
//                int sY = j + dstY;
//                ProcessingCase currentCase = getCase(sX, sY);
//                destValue = data.processWindowNoData(window, i, j, srcPixelOffset, centerScanlineOffset, currentCase);
//                dstData[dstPixelOffset] = Double.isNaN(destValue) ? destNoDataInt : ImageUtil.clampRoundInt(destValue);
//                srcPixelOffset += srcPixelStride;
//                dstPixelOffset += dstPixelStride;
//            }
//            srcScanlineOffset += srcScanlineStride;
//            dstScanlineOffset += dstScanlineStride;
//        }
//        // ROI and No Data Check
//    } else {
//        for (int j = 0; j < dheight; j++) {
//            y0 = srcY + j;
//
//            for (int i = 0; i < dwidth; i++) {
//
//                x0 = srcX + i;
//
//                boolean inROI = false;
//                // ROI Check
//                for (int y = 0; y < 3 && !inROI; y++) {
//                    int yI = y0 + y;
//                    for (int x = 0; x < 3 && !inROI; x++) {
//                        int xI = x0 + x;
//                        if (roiBounds.contains(xI, yI) && roiIter.getSample(xI, yI, 0) > 0) {
//                            inROI = true;
//                        }
//                    }
//                }
//
//                if (inROI) {
//                    int sX = i + dstX;
//                    int sY = j + dstY;
//                    ProcessingCase currentCase = getCase(sX, sY);
//                    destValue = data.processWindowNoData(window, i, j, srcPixelOffset,
//                            centerScanlineOffset, currentCase);
//                    dstData[dstPixelOffset] = Double.isNaN(destValue) ? destNoDataInt : ImageUtil.clampRoundInt(destValue);
//                } else {
//                    dstData[dstPixelOffset] = destNoDataInt;
//                }
//
//                srcPixelOffset += srcPixelStride;
//                dstPixelOffset += dstPixelStride;
//            }
//            srcScanlineOffset += srcScanlineStride;
//            dstScanlineOffset += dstScanlineStride;
//
//        }
//    }
    }
    
    private ProcessingCase getCase(int i, int j) {
        if (i == minX && j == minY) {
            return ProcessingCase.TOP_LEFT;
        } else if (i == maxX && j == minY) {
            return ProcessingCase.TOP_RIGHT;
        } else if (j == minY) {
            return ProcessingCase.TOP;
        } else if (i == minX && j == maxY) {
            return ProcessingCase.BOTTOM_LEFT;
        } else if (i == maxX && j == maxY) {
            return ProcessingCase.BOTTOM_RIGHT;
        } else if (i == minX) {
            return ProcessingCase.LEFT;
        } else if (i == maxX) {
            return ProcessingCase.RIGHT;
        } else if (j == maxY) {
            return ProcessingCase.BOTTOM;
        } else {
            return ProcessingCase.STANDARD;
        }

    }

    private HillShadeParams prepareParams(double resX, double resY, double zetaFactor,
            double scale, double altitude, double azimuth) {

        if (DEBUG_PATH != null) {
            File file = new File(DEBUG_PATH);
            if (file.exists() && file.canRead()) {
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(file);
                    Properties prop = new Properties();
                    prop.load(fis);
                    String scaleS = prop.getProperty("scale");
                    if (scaleS != null) {
                        scale = Double.parseDouble(scaleS);
                    }
                    String altitudeS = prop.getProperty("altitude");
                    if (altitudeS != null) {
                        altitude = Double.parseDouble(altitudeS);
                    }
                    String azimuthS = prop.getProperty("azimuth");
                    if (azimuthS != null) {
                        azimuth = Double.parseDouble(azimuthS);
                    }
                    String zetaS = prop.getProperty("zetaFactor");
                    if (zetaS != null) {
                        zetaFactor = Double.parseDouble(zetaS);
                    }
                    String algorithmS = prop.getProperty("algorithm");
                    if (algorithmS != null) {
                        algorithm = Algorithm.valueOf(algorithmS);
                    }
                } catch (Exception e) {
                    // Does Nothing: we are in debug
                } finally {
                    if (fis != null) {
                        try {
                            fis.close();
                        } catch (IOException e) {
                            // Does nothing
                        }
                    }
                    
                }
                
            }
        }
        
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

    static class HillShadeParams {
        double resY;

        double resX;

        double sineOfAltitude;

        double azimuthRadians;

        double zetaScaleFactor;

        double cosinusOfaltitudeRadiansForZetaScaleFactor;

        double squaredZetaScaleFactor;
    }

}
