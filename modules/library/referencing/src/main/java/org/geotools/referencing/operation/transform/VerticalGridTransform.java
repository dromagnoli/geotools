/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2006-2008, Open Source Geospatial Foundation (OSGeo)
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
package org.geotools.referencing.operation.transform;

import au.com.objectix.jgridshift.GridShift;
import au.com.objectix.jgridshift.GridShiftFile;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.geotools.metadata.iso.citation.Citations;
import org.geotools.parameter.DefaultParameterDescriptor;
import org.geotools.referencing.NamedIdentifier;
import org.geotools.referencing.ReferencingFactoryFinder;
import org.geotools.referencing.factory.gridshift.GridShiftLocator;
import org.geotools.referencing.factory.gridshift.VerticalGridShift;
import org.geotools.referencing.factory.gridshift.VerticalGridShiftFactory;
import org.geotools.referencing.factory.gridshift.VerticalGridShiftFile;
import org.geotools.referencing.operation.MathTransformProvider;
import org.geotools.util.logging.LoggerAdapter;
import org.geotools.util.logging.Logging;
import org.opengis.parameter.ParameterDescriptor;
import org.opengis.parameter.ParameterDescriptorGroup;
import org.opengis.parameter.ParameterNotFoundException;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.NoSuchIdentifierException;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;
import org.opengis.referencing.operation.Transformation;

/**
 * Base class for transformations between 2 different height's related verticalCRS. The
 * transformation goes through a Vertical offset file containing the offset to be applied for each
 * specific x,y pair. (Currently, only GeoTIFF implementation based is supported)
 */
public class VerticalGridTransform extends AbstractMathTransform {

    /** Logger */
    protected static final Logger LOGGER = Logging.getLogger(VerticalGridTransform.class);

    /** Creates a new instance of {@code VerticalTransform}. */
    protected VerticalGridTransform() {}

    /**
     * Constructs a {@code VerticalGridTransform} from the specified grid shift file.
     *
     * <p>This constructor checks for grid shift file availability, but doesn't actually load the
     * full grid into memory to preserve resources.
     *
     * @param file Vertical grid file name
     * @throws NoSuchIdentifierException if the grid is not available.
     */
    public VerticalGridTransform(URI file) throws NoSuchIdentifierException {
        if (file == null) {
            throw new NoSuchIdentifierException("No NTv2 Grid File specified.", null);
        }

        this.grid = file;

        gridLocation = locateGrid(grid.toString());
        if (gridLocation == null) {
            throw new NoSuchIdentifierException("Could not locate Vertical Grid File " + file, null);
        }

        // Search for grid file
        if (!FACTORY.isVerticalGrid(gridLocation)) {
            throw new NoSuchIdentifierException("Vertical Grid File not available.", file.toString());
        }
    }

    URL locateGrid(String grid) {
        for (GridShiftLocator locator : ReferencingFactoryFinder.getGridShiftLocators(null)) {
            URL result = locator.locateGrid(grid);
            if (result != null) {
                return result;
            }
        }

        return null;
    }

    /** Gets the dimension of input points. */
    public final int getSourceDimensions() {
        return 1;
    }

    /** Gets the dimension of output points. */
    public final int getTargetDimensions() {
        return 1;
    }

    /** The original grid name */
    private URI grid = null;

    /** The grid file name as set in the constructor. */
    private URL gridLocation = null;

    /** The grid shift to be used */
    private VerticalGridShiftFile gridShift;

    /** The factory that loads the grid shift files */
    private static VerticalGridShiftFactory FACTORY = new VerticalGridShiftFactory();

    /**
     * Returns the value to add to a <cite>height above the ellipsoid</cite> in order to get a
     * <cite>height above the geoid</cite> for the specified geographic coordinate.
     *
     * @param longitude The geodetic longitude, in decimal degrees.
     * @param latitude The geodetic latitude, in decimal degrees.
     * @param height The height above the ellipsoid in metres.
     * @return The value to add in order to get the height above the geoid (in metres).
     * @throws TransformException if the offset can't be computed for the specified coordinates.
     */
    protected double heightOffset(double longitude, double latitude, double height)
            throws TransformException {
        return 0;
    }

    /** Transforms a list of coordinate point ordinal values. */
    @Override
    public void transform(
            final double[] srcPts, int srcOff, final double[] dstPts, int dstOff, int numPts)
            throws TransformException {
        bidirectionalTransform(srcPts, srcOff, dstPts, dstOff, numPts, true);
    }
    /*    final int step;

        if (srcPts == dstPts && srcOff < dstOff) {
            srcOff += 3 * (numPts - 1);
            dstOff += 3 * (numPts - 1);
            step = -3;
        } else {
            step = +3;
        }
        while (--numPts >= 0) {
            final float x, y, z;
            dstPts[dstOff + 0] = (x = srcPts[srcOff + 0]);
            dstPts[dstOff + 1] = (y = srcPts[srcOff + 1]);
            dstPts[dstOff + 2] = (float) ((z = srcPts[srcOff + 2]) + heightOffset(x, y, z));
            srcOff += step;
            dstOff += step;
        }
    }
*/
    /** Transforms a list of coordinate point ordinal values. */
  /*
    public void transform(
            final double[] srcPts, int srcOff, final double[] dstPts, int dstOff, int numPts)
            throws TransformException {
        final int step;
        if (srcPts == dstPts && srcOff < dstOff) {
            srcOff += 3 * (numPts - 1);
            dstOff += 3 * (numPts - 1);
            step = -3;
        } else {
            step = +3;
        }
        while (--numPts >= 0) {
            final double x, y, z;
            dstPts[dstOff + 0] = (x = srcPts[srcOff + 0]);
            dstPts[dstOff + 1] = (y = srcPts[srcOff + 1]);
            dstPts[dstOff + 2] = (z = srcPts[srcOff + 2]) + heightOffset(x, y, z);
            srcOff += step;
            dstOff += step;
        }
    }
*/
    /**
     * Performs the actual transformation.
     *
     * @param srcPts the array containing the source point coordinates.
     * @param srcOff the offset to the first point to be transformed in the source array.
     * @param dstPts the array into which the transformed point coordinates are returned. May be the
     *     same than {@code srcPts}.
     * @param dstOff the offset to the location of the first transformed point that is stored in the
     *     destination array.
     * @param numPts the number of point objects to be transformed.
     * @param forward {@code true} for direct transform, {@code false} for inverse transform.
     * @throws TransformException if an IO error occurs reading the grid file.
     */
    private void bidirectionalTransform(
            double[] srcPts, int srcOff, double[] dstPts, int dstOff, int numPts, boolean forward)
            throws TransformException {

        boolean shifted;

        if (gridShift == null) { // Create grid when first needed.
            try {
                gridShift = FACTORY.createVerticalGrid(gridLocation);
            } catch (FactoryException e) {
                throw new TransformException(
                        "Vertical Grid " + gridLocation + " Could not be created", e);
            }
        }

        /*try {*/
            VerticalGridShift shift = new VerticalGridShift();
            while (--numPts >= 0) {
                shift.setLonPositiveEastDegrees(srcPts[srcOff++]);
                shift.setLatDegrees(srcPts[srcOff++]);
                if (forward) {
                    shifted = gridShift.gridShiftForward(shift);
                } else {
                    shifted = gridShift.gridShiftReverse(shift);
                }
                if (shifted) {
                    dstPts[dstOff++] = shift.getShiftedLonPositiveEastDegrees();
                    dstPts[dstOff++] = shift.getShiftedLatDegrees();
                } else {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.log(
                                Level.FINE,
                                "Point ("
                                        + srcPts[srcOff - 2]
                                        + ", "
                                        + srcPts[srcOff - 1]
                                        + ") is not covered by '"
                                        + this.grid
                                        + "' NTv2 grid,"
                                        + " it will not be shifted.");
                    }
                    dstPts[dstOff++] = srcPts[srcOff - 2];
                    dstPts[dstOff++] = srcPts[srcOff - 1];
                }
            }
        /*} catch (IOException e) {
            throw new TransformException(e.getLocalizedMessage(), e);
        }*/
    }



    /** The {@link VerticalGridTransform} provider. */
    public static class Provider extends MathTransformProvider {

        private static final long serialVersionUID = -3710592152744574801L;

        /**
         * The operation parameter descriptor for the "Latitude and longitude difference file"
         * parameter value. The default value is "".
         */
        public static final DefaultParameterDescriptor<URI> FILE =
                new DefaultParameterDescriptor<URI>(
                        toMap(
                                new NamedIdentifier[] {
                                    new NamedIdentifier(Citations.EPSG, "Vertical Offset file"),
                                    new NamedIdentifier(Citations.EPSG, "8732")
                                }),
                        URI.class,
                        null,
                        null,
                        null,
                        null,
                        null,
                        true);

        /** The parameters group. */
        static final ParameterDescriptorGroup PARAMETERS =
                createDescriptorGroup(
                        new NamedIdentifier[] {
                            new NamedIdentifier(
                                    Citations.EPSG, "Vertical Offset by Grid Interpolation"),
                            new NamedIdentifier(Citations.EPSG, "1081")
                        },
                        new ParameterDescriptor[] {FILE});

        /** Constructs a provider. */
        public Provider() {
            super(2, 2, PARAMETERS);
        }

        /** Returns the operation type. */
        @Override
        public Class<Transformation> getOperationType() {
            return Transformation.class;
        }

        /**
         * N Creates a math transform from the specified group of parameter values.
         *
         * @param values The group of parameter values.
         * @return The created math transform.
         * @throws ParameterNotFoundException if a required parameter was not found.
         * @throws FactoryException if there is a problem creating this math transform.
         */
        protected MathTransform createMathTransform(final ParameterValueGroup values)
                throws ParameterNotFoundException, FactoryException {
            return new VerticalGridTransform(value(FILE, values));
        }
    }
}
