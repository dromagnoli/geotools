/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2002-2012, Open Source Geospatial Foundation (OSGeo)
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
package org.geotools.referencing.factory.gridshift;

import java.io.*;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.geotools.metadata.i18n.ErrorKeys;
import org.geotools.metadata.i18n.Errors;
import org.geotools.referencing.factory.ReferencingFactory;
import org.geotools.util.SoftValueHashMap;
import org.geotools.util.URLs;
import org.geotools.util.factory.AbstractFactory;
import org.geotools.util.factory.BufferedFactory;
import org.geotools.util.logging.Logging;
import org.opengis.referencing.FactoryException;

/**
 * Loads and caches Vertical grid files. This incorporates a soft cache mechanism to keep grids in
 * memory when first loaded. It also checks supported VerticalShift grid file format in {@link
 * #isVerticalGrid(URL)} method.
 */
public class VerticalGridShiftFactory extends ReferencingFactory implements BufferedFactory {

    /** The number of hard references to hold internally. */
    private static final int GRID_CACHE_HARD_REFERENCES = 10;

    /** Logger. */
    protected static final Logger LOGGER = Logging.getLogger(VerticalGridShiftFactory.class);

    /** The soft cache that holds loaded grids. */
    private SoftValueHashMap<String, VerticalGridShiftFile> verticalGridCache;

    /** Constructs a factory with the default priority. */
    public VerticalGridShiftFactory() {
        super();
        verticalGridCache =
                new SoftValueHashMap<String, VerticalGridShiftFile>(GRID_CACHE_HARD_REFERENCES);
    }

    /**
     * Constructs an instance using the specified priority level.
     *
     * @param priority The priority for this factory, as a number between {@link
     *     AbstractFactory#MINIMUM_PRIORITY MINIMUM_PRIORITY} and {@link
     *     AbstractFactory#MAXIMUM_PRIORITY MAXIMUM_PRIORITY} inclusive.
     */
    public VerticalGridShiftFactory(final int priority) {
        super(priority);
        verticalGridCache =
                new SoftValueHashMap<String, VerticalGridShiftFile>(GRID_CACHE_HARD_REFERENCES);
    }

    /**
     * Performs a vertical grid file lookup given its name, and checks for file format correctness.
     *
     * @param location The vertical grid file location
     * @return {@code true} if file exists and is valid, {@code false} otherwise
     */
    public boolean isVerticalGrid(URL location) {
        if (location != null) {
            return isVerticalGridFileValid(location); // Check
        } else {
            return false;
        }
    }

    /**
     * Creates a NTv2 Grid.
     *
     * @param gridLocation The NTv2 grid file location
     * @return the grid
     * @throws FactoryException if grid cannot be created
     */
    public VerticalGridShiftFile createVerticalGrid(URL gridLocation) throws FactoryException {
        if (gridLocation == null) {
            throw new FactoryException("The grid location must be not null");
        }

        synchronized (verticalGridCache) { // Prevent simultaneous threads trying to load same grid
            VerticalGridShiftFile grid = verticalGridCache.get(gridLocation.toExternalForm());
            if (grid != null) { // Cached:
                return grid; // - Return
            } else { // Not cached:
                if (gridLocation != null) {
                    grid = loadVerticalGrid(gridLocation); // - Load
                    if (grid != null) {
                        verticalGridCache.put(gridLocation.toExternalForm(), grid); // - Cache
                        return grid; // - Return
                    }
                }
                throw new FactoryException("Vertical Offset Grid " + gridLocation + " could not be created.");
            }
        }
    }

    /**
     * Checks if a given resource is a valid vertical grid file without fully loading it.
     *
     * <p>If file is not valid, the cause is logged at {@link Level#WARNING warning level}.
     *
     * @param url the vertical grid file absolute path
     * @return true if file is a vertical grid format, false otherwise
     */
    protected boolean isVerticalGridFileValid(URL url) {
        try {

            // Loading as RandomAccessFile doesn't load the full grid
            // in memory, but is a quick method to see if file format
            // is a valid grid
            if (url.getProtocol().equals("file")) {
                File file = URLs.urlToFile(url);

                if (!file.exists() || !file.canRead()) {
                    throw new IOException(Errors.format(ErrorKeys.FILE_DOES_NOT_EXIST_$1, file));
                }
                new VerticalGridShiftFile().loadGridShiftFile(file);
            } else {
                return false;
                /*try (InputStream in =
                        new BufferedInputStream(url.openConnection().getInputStream())) {

                    // will throw an exception if not a valid file
                    new VerticalGridShiftFile().loadGridShiftFile(in, false);
                }*/
            }

            return true; // No exception thrown => valid file.
        } catch (IllegalArgumentException e) {
            // This usually means resource is not a valid NTv2 file.
            // Let exception message describe the cause.
            LOGGER.log(Level.WARNING, e.getLocalizedMessage(), e);
            return false;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, e.getLocalizedMessage(), e);
            return false;
        }
    }

    /**
     * Loads the grid in memory.
     *
     * <p>If file cannot be loaded, the cause is logged at {@link Level#SEVERE severe level}.
     *
     * @param location the vertical grid file absolute path
     * @return the grid, or {@code null} on error
     */
    private VerticalGridShiftFile loadVerticalGrid(URL location) throws FactoryException {
        InputStream in = null;
        try {
        VerticalGridShiftFile grid = new VerticalGridShiftFile();

        in = new BufferedInputStream(location.openStream());
        grid.loadGridShiftFile(in, false); // Load full grid in memory
        in.close();
        return grid;
        } catch (FileNotFoundException e) {
            LOGGER.log(Level.SEVERE, e.getLocalizedMessage(), e);
            return null;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, e.getLocalizedMessage(), e);
            return null;
        } catch (IllegalArgumentException e) {
            LOGGER.log(Level.SEVERE, e.getLocalizedMessage(), e);
            throw new FactoryException(e.getLocalizedMessage(), e);
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException e) {
                // never mind
            }
        }
    }
}
