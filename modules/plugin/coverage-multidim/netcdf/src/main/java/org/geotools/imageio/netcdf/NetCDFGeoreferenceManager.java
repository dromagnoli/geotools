/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2015 - 2016, Open Source Geospatial Foundation (OSGeo)
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
package org.geotools.imageio.netcdf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.geotools.coverage.io.netcdf.crs.NetCDFProjection;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.imageio.netcdf.cv.CoordinateVariable;
import org.geotools.imageio.netcdf.utilities.NetCDFCRSUtilities;
import org.geotools.imageio.netcdf.utilities.NetCDFUtilities;
import org.geotools.util.logging.Logging;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import ucar.nc2.Attribute;
import ucar.nc2.Variable;
import ucar.nc2.constants.AxisType;
import ucar.nc2.dataset.CoordinateAxis;
import ucar.nc2.dataset.CoordinateAxis1D;
import ucar.nc2.dataset.NetcdfDataset;

/**
 * Store information about the underlying NetCDF georeferencing, 
 * such as Coordinate Variables (i.e. Latitude, Longitude, Time variables, with values), 
 * Coordinate Reference Systems (i.e. GridMappings), NetCDF dimensions to NetCDF coordinates 
 * relations.
 */
class NetCDFGeoreferenceManager {

    /** 
     * Base class for BBOX initialization and retrieval. 
     */
    class BBoxGetter {

        /**
         * BoundingBoxes available for the underlying dataset. Most common case is that
         * the whole dataset has a single boundingbox/grid-mapping, resulting into a map 
         * made by a single element. In that case, the only one envelope will be referred
         * through the "DEFAULT" key  
         */
        protected Map<String, ReferencedEnvelope> boundingBoxes = new HashMap<String, ReferencedEnvelope>();

        public BBoxGetter() throws FactoryException,
                IOException {
            double[] xLon = new double[2];
            double[] yLat = new double[2];
            byte set = 0;
            isLonLat = false;
            // Scan over coordinateVariables
            for (CoordinateVariable<?> cv : getCoordinatesVariables()) {
                if (cv.isNumeric()) {

                    // is it lat or lon (or geoY or geoX)?
                    AxisType type = cv.getAxisType();
                    switch (type) {
                    case GeoY:
                    case Lat:
                        getCoordinate(cv, yLat);
                        if (yLat[1] > yLat[0]) {
                            setNeedsFlipping(true);
                        } else {
                            setNeedsFlipping(false);
                        }
                        set++;
                        break;
                    case GeoX:
                    case Lon:
                        getCoordinate(cv, xLon);
                        set++;
                        break;
                    default:
                        break;
                    }
                    switch (type) {
                    case Lat:
                    case Lon:
                        isLonLat = true;
                    default:
                        break;
                    }
                }
                if (set == 2) {
                    break;
                }
            }

            if (set != 2) {
                throw new IllegalStateException("Unable to create envelope for this dataset");
            }

            // create the envelope
            CoordinateReferenceSystem crs = NetCDFCRSUtilities.WGS84;
            // Looks for Projection definition
            if (!isLonLat) {
                // First, looks for a global crs (it may have been defined
                // as a NetCDF output write operation) to be used as fallback
                CoordinateReferenceSystem defaultCrs = NetCDFProjection.lookForDatasetCRS(dataset);

                // Then, look for a per variable CRS
                CoordinateReferenceSystem localCrs = NetCDFProjection.lookForVariableCRS(dataset,
                        defaultCrs);
                if (localCrs != null) {
                    // lookup for a custom EPSG if any
                    crs = NetCDFProjection.lookupForCustomEpsg(localCrs);
                }
            }
            ReferencedEnvelope boundingBox = new ReferencedEnvelope(xLon[0], xLon[1], yLat[0], yLat[1], crs);
            boundingBoxes.put(NetCDFGeoreferenceManager.DEFAULT, boundingBox);
        }

        public void dispose() {
            if (boundingBoxes != null) {
                boundingBoxes.clear();
            }
        }

        public ReferencedEnvelope getBBox(String bboxName) {
            return boundingBoxes.get(bboxName);
        }
    }

    /**
     *  BBoxGetter Implementation for multiple bounding boxes management.
     *  Use it for NetCDF datasets defining multiple bounding boxes. 
     */
    class MultipleBBoxGetter extends BBoxGetter {

        public MultipleBBoxGetter() throws FactoryException, IOException {
            Map<String, double[]> xLonCoords = new HashMap<String, double[]>();
            Map<String, double[]> yLatCoords = new HashMap<String, double[]>();
            isLonLat = false;
            for (CoordinateVariable<?> cv : getCoordinatesVariables()) {
                if (cv.isNumeric()) {
                    // is it lat or lon (or geoY or geoX)?
                    AxisType type = cv.getAxisType();
                    switch (type) {
                    case GeoY:
                    case Lat:
                        double[] yLat = new double[2];
                        getCoordinate(cv, yLat);
                        if (yLat[1] > yLat[0]) {
                            setNeedsFlipping(true);
                        } else {
                            setNeedsFlipping(false);
                        }
                        yLatCoords.put(cv.getName(), yLat);
                        break;
                    case GeoX:
                    case Lon:
                        double[] xLon = new double[2];
                        getCoordinate(cv, xLon);
                        xLonCoords.put(cv.getName(), xLon);
                        break;
                    default:
                        break;
                    }
                }
            }
            // create the envelope
            CoordinateReferenceSystem crs = NetCDFCRSUtilities.WGS84;

            // Looking for all coordinates pairs
            List<Variable> variables = dataset.getVariables();
            for (Variable var : variables) {
                Attribute coordinatesAttribute = var.findAttribute(NetCDFUtilities.COORDINATES);
                Attribute gridMappingAttribute = var.findAttribute(NetCDFUtilities.GRID_MAPPING);
                if (gridMappingAttribute != null && coordinatesAttribute != null) {
                    String coordinates = coordinatesAttribute.getStringValue();
                    if (!boundingBoxes.containsKey(coordinates)) {
                        Variable mapping = dataset.findVariable(null,
                                gridMappingAttribute.getStringValue());
                        if (mapping != null) {
                            CoordinateReferenceSystem localCrs = NetCDFProjection
                                    .parseProjection(mapping);
                            if (localCrs != null) {
                                // lookup for a custom EPSG if any
                                crs = NetCDFProjection.lookupForCustomEpsg(localCrs);
                            }
                        }

                        String coords[] = coordinates.split(" ");
                        double xLon[] = xLonCoords.get(coords[0]);
                        double yLat[] = yLatCoords.get(coords[1]);
                        ReferencedEnvelope boundingBox = new ReferencedEnvelope(xLon[0], xLon[1], yLat[0], yLat[1], crs);
                        boundingBoxes.put(coordinates, boundingBox);
                    }
                }
            }
        }
    }

    private final static Logger LOGGER = Logging.getLogger(NetCDFGeoreferenceManager.class.toString());

    /**
     * Set it to {@code true} in case the dataset has a single bbox.
     * Set it to {@code false} in case the dataset has multiple 2D coordinates definitions and bboxes.
     * Used to quickly access the bbox in case there is only a single one.
     */
    private boolean hasSingleBBox = true;

    /** The underlying BoundingBox getter instance */
    private BBoxGetter bbox;

    /**
     * Class mapping the NetCDF dimensions to related coordinate variables/axis names.
     */
    class DimensionMapper {
        /** Mapping containing the relation between a dimension and the related coordinate variable */
        private Map<String, String> dimensions;

        /**
         * Return the whole dimension to coordinates mapping
         */
        public Map<String, String> getDimensions() {
            return dimensions;
        }

        /**
         * Return the dimension names handled by this mapper
         */
        public Set<String> getDimensionNames() {
            return dimensions.keySet();
        }

        /** 
         * Return the dimension name associated to the specified coordinateVariable.
         */
        public String getDimension(String coordinateVariableName) {
            return dimensions.get(coordinateVariableName);
        }

        /**
         * Mapper constructor parsing the coordinateVariables.
         *  
         * @param coordinates
         */
        public DimensionMapper(Map<String, CoordinateVariable<?>> coordinates) {
            // check other dimensions
            int coordinates2Dx = 0;
            int coordinates2Dy = 0;

            dimensions = new HashMap<String, String>();
            Set<String> coordinateKeys = new TreeSet<String>(coordinates.keySet());
            for (String key : coordinateKeys) {
                // get from coordinate vars
                final CoordinateVariable<?> cv = getCoordinateVariable(key);
                if (cv != null) {
                    final String name = cv.getName();
                    AxisType axisType = cv.getAxisType();
                    switch (axisType) {
                    case GeoX:
                    case Lon:
                        coordinates2Dx++;
                        continue;
                    case GeoY:
                    case Lat:
                        coordinates2Dy++;
                        continue;
                    case Height:
                    case Pressure:
                    case RadialElevation:
                    case RadialDistance:
                    case GeoZ:
                        if (NetCDFCRSUtilities.VERTICAL_AXIS_NAMES.contains(name)
                                && !dimensions.containsKey(NetCDFUtilities.ELEVATION_DIM)) {
                            // Main elevation dimension
                            dimensions.put(NetCDFUtilities.ELEVATION_DIM, name);
                        } else {
                            // additional elevation dimension
                            dimensions.put(name.toUpperCase(), name);
                        }
                        break;
                    case Time:
                        if (!dimensions.containsKey(NetCDFUtilities.TIME_DIM)) {
                            // Main time dimension
                            dimensions.put(NetCDFUtilities.TIME_DIM, name);
                        } else {
                            // additional time dimension
                            dimensions.put(name.toUpperCase(), name);
                        }
                        break;
                    default:
                        if (LOGGER.isLoggable(Level.FINE)) {
                            LOGGER.fine("The specified axis type isn't currently supported: " 
                        + axisType + "\nskipping it");
                        }
                        break;
                    }
                } else {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.fine("Null coordinate variable: '" + key + "' while processing input: " + dataset.getLocation());
                    }
                }
            }
            if (coordinates2Dx + coordinates2Dy > 2) {
                if (coordinates2Dx != coordinates2Dy) {
                    throw new IllegalArgumentException("number of x/lon coordinates must match number of y/lat coordinates");
                }
                // More than 2D coordinates have been found, as an instance lon1, lat1, lon2, lat2
                // Report that by unsetting the singleBbox flag.
                setHasSingleBBox(false);
            }
        }

        /** Update the dimensions mapping */
        public void remap(String dimension, String name) {
            dimensions.put(dimension, name);
        }
    }

    /** Map containing coordinates being wrapped by variables */
    private Map<String, CoordinateVariable<?>> coordinatesVariables;

    final static String DEFAULT = "Default";

    /** Flag reporting if the input file needs flipping or not */
    private boolean needsFlipping = false;

    /** The underlying NetCDF dataset */
    private NetcdfDataset dataset;

    /** The DimensionMapper instance */
    DimensionMapper dimensionMapper;

    /** Flag reporting if the dataset is lonLat to avoid projections checks */
    private boolean isLonLat;

    public boolean isNeedsFlipping() {
        return needsFlipping;
    }

    public void setNeedsFlipping(boolean needsFlipping) {
        this.needsFlipping = needsFlipping;
    }

    public boolean isLonLat() {
        return isLonLat;
    }

    public boolean isHasSingleBBox() {
        return hasSingleBBox;
    }

    public void setHasSingleBBox(boolean hasSingleBBox) {
        this.hasSingleBBox = hasSingleBBox;
    }

    public CoordinateVariable<?> getCoordinateVariable(String name) {
        return coordinatesVariables.get(name);
    }

    public Collection<CoordinateVariable<?>> getCoordinatesVariables() {
        return coordinatesVariables.values();
    }

    public void dispose() {
        if (coordinatesVariables != null) {
            coordinatesVariables.clear();
        }
        bbox.dispose();
    }

    public ReferencedEnvelope getBoundingBox(String shortName) {
        return hasSingleBBox ? bbox.getBBox(DEFAULT) : getBBoxForCoordinate(shortName);
    }

    public CoordinateReferenceSystem getCoordinateReferenceSystem(String shortName) {
        ReferencedEnvelope envelope = getBoundingBox(shortName);
        if (envelope != null) {
            return envelope.getCoordinateReferenceSystem();
        }
        throw new IllegalArgumentException("Unable to find a CRS for the provided variable: " + shortName);
    }

    private ReferencedEnvelope getBBoxForCoordinate(String shortName) {
        // find auxiliary coordinateVariable
        String coordinates = getCoordinatesForVariable(shortName);
        if (coordinates != null) {
            return bbox.getBBox(coordinates);
        }
        throw new IllegalArgumentException("Unable to find an envelope for the provided variable: "
                + shortName);
    }

    private String getCoordinatesForVariable(String shortName) {
        Variable var = dataset.findVariable(null, shortName);
        if (var != null) {
            // Getting the coordinates attribute
            Attribute attribute = var.findAttribute(NetCDFUtilities.COORDINATES);
            if (attribute != null) {
                return attribute.getStringValue(); 
            }
        }
        return null;
    }

    public Collection<CoordinateVariable<?>> getCoordinatesVariables(String shortName) {
        if (hasSingleBBox) {
            return coordinatesVariables.values();
        } else {
            String coordinates = getCoordinatesForVariable(shortName);
            String coords[] = coordinates.split(" ");
            List<CoordinateVariable<?>> coordVar = new ArrayList<CoordinateVariable<?>>();
            for (String coord: coords) {
                coordVar.add(coordinatesVariables.get(coord));
            }
            return coordVar;
        }
    }

    /** 
     * Main constructor to setup the NetCDF Georeferencing based on the available
     * information stored within the NetCDF dataset. 
     * */
    public NetCDFGeoreferenceManager(NetcdfDataset dataset) {
        this.dataset = dataset;
        initCoordinates();
        try {
            initBBox();
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        } catch (FactoryException fe) {
            throw new RuntimeException(fe);
        }
    }

    /**
     * Parse the CoordinateAxes of the dataset and setup proper {@link CoordinateVariable} instances
     * on top of it.
     */
    private void initCoordinates() {
        // get the coordinate variables
        Map<String, CoordinateVariable<?>> coordinates = new HashMap<String, CoordinateVariable<?>>();
        for (CoordinateAxis axis : dataset.getCoordinateAxes()) {
            if (axis instanceof CoordinateAxis1D && axis.getAxisType() != null && !"reftime".equalsIgnoreCase(axis.getFullName())) {
                coordinates.put(axis.getFullName(), CoordinateVariable.create((CoordinateAxis1D)axis));
            } else {
                // Workaround for Unsupported Axes
                Set<String> unsupported = NetCDFUtilities.getUnsupportedDimensions();
                if (axis instanceof CoordinateAxis1D && unsupported.contains(axis.getFullName())) {
                    axis.setAxisType(AxisType.GeoZ);
                    coordinates.put(axis.getFullName(),
                            CoordinateVariable.create((CoordinateAxis1D) axis));
                // Workaround for files that have a time dimension, but in a format that could not be parsed
                } else if ("time".equals(axis.getFullName())) {
                    LOGGER.warning("Detected unparseable unit string in time axis: '"
                            + axis.getUnitsString() + "'.");
                    axis.setAxisType(AxisType.Time);
                    coordinates.put(axis.getFullName(),
                            CoordinateVariable.create((CoordinateAxis1D) axis));
                } else if ("reftime".equals(axis.getFullName())) {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.fine("Unable to support reftime which is not a CoordinateAxis1D");
                    }
                } else {
                    LOGGER.warning("Unsupported axis: " + axis + " in input: " + dataset.getLocation()
                            + " has been found");
                }
            }
        }
        coordinatesVariables = coordinates;
        dimensionMapper = new DimensionMapper(coordinates);
    }

    /**
     * Initialize the bbox getter
     * 
     * @throws IOException 
     * @throws FactoryException 
     */
    private void initBBox() throws IOException, FactoryException {
        bbox = hasSingleBBox ? new BBoxGetter() : new MultipleBBoxGetter();
    }

    /**
     * Get the bounding box coordinates from the provided coordinateVariable.
     * The resulting coordinates will be stored in the provided array.
     * @param cv
     * @param coordinate
     * @throws IOException
     */
    private void getCoordinate(CoordinateVariable<?> cv, double[] coordinate) throws IOException {
        if (cv.isRegular()) {
            coordinate[0] = cv.getStart() - (cv.getIncrement() / 2d);
            coordinate[1] = coordinate[0] + cv.getIncrement() * (cv.getSize());
        } else {
            double min = ((Number) cv.getMinimum()).doubleValue();
            double max = ((Number) cv.getMaximum()).doubleValue();
            double incr = (max - min) / (cv.getSize() - 1);
            coordinate[0] = min - (incr / 2d);
            coordinate[1] = max + (incr / 2d);
        }
    }
}
