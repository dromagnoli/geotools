/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2014, Open Source Geospatial Foundation (OSGeo)
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
package it.geosolutions.footprint;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.data.DataSourceException;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.process.raster.FootprintExtractionProcess;
import org.geotools.process.raster.MarchingSquaresVectorizer.ImageLoadingType;
import org.geotools.util.Range;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.io.OutStream;
import com.vividsolutions.jts.io.OutputStreamOutStream;
import com.vividsolutions.jts.io.WKBWriter;

/**
 * Simple tool to compute {@link FootprintExtractionProcess} on a provided GeoTIFF File.
 * 
 * Just invoke the {@link FootprintExtractionTool#generateFootprint(FootprintProcessingInputBean)}
 * method by providing a {@link FootprintProcessingInputBean} input bean containing the input 
 * GeoTIFF {@link File} to be processed. The output is written as a WKB beside the original GeoTIFF.
 * 
 * By Default, footprint extraction: 
 * - uses a luminance threshold in the range 0 - 10,
 * - does remove collinear
 * - excludes polygon having pixel area lower than 100 pixels
 * - doesn't compute a simplified version too
 * - uses deferred execution based image loading
 * 
 * These parameters can be customized by providing a Map<String, Object> to the
 * input bean. See {@link FootprintParameter} for the name of the Parameter Keys
 * as well as the values of the Defaults which will be used in case of missing
 * parameter. 
 * 
 * @author Daniele Romagnoli, GeoSolutions SAS
 */
public class FootprintExtractionTool {

    private static FootprintExtractionProcess process = null;

    static {
        process = new FootprintExtractionProcess();
    }

    /**
     * {@link FootprintProcessingInputBean} defining the input of the processing.
     * 
     * Requested parameters are the input {@link File} to be processed as well as a 
     * map of footprint extraction parameters. The footprint parameters
     * may be null; in that case, default will be used. 
     * See {@link FootprintParameter#initDefaults()}
     * 
     * 
     */
    public static class FootprintProcessingInputBean {
        File inputFile;

        Map<String, Object> footprintParameters;

        public Map<String, Object> getFootprintParameters() {
            return footprintParameters;
        }

        public void setFootprintParameters(Map<String, Object> footprintParameters) {
            this.footprintParameters = footprintParameters;
        }

        public File getInputFile() {
            return inputFile;
        }

        public void setInputFile(File inputFile) {
            this.inputFile = inputFile;
        }
    }

    /**
     * FootprintProcessingOutputBean
     * 
     * Check for empty list of exceptions when processing is done.
     */
    public static class FootprintProcessingOutputBean {
        List<Exception> exceptions;

        public List<Exception> getExceptions() {
            return exceptions;
        }

        public FootprintProcessingOutputBean() {
            this.exceptions = new ArrayList<Exception>();
        }

        public void addException(Exception e) {
            exceptions.add(e);
        }
    }

    /**
     * Method to generate the footprint and create a WKB file beside the input file. 
     * @param inputBean
     * 
     * @return
     */
    public static FootprintProcessingOutputBean generateFootprint(
            FootprintProcessingInputBean inputBean) {

        FootprintProcessingOutputBean outputBean = new FootprintProcessingOutputBean();

        GeoTiffReader reader = null;
        FeatureIterator<SimpleFeature> iter = null;
        GridCoverage2D cov = null;

        try {

            // Accessing the dataset
            final File inputFile = inputBean.getInputFile();
            final String fileName = inputFile.getCanonicalPath();
            reader = new GeoTiffReader(inputFile);
            cov = reader.read(null);

            // Preparing the footprint processing parameters
            Map<String, Object> params = FootprintParameter.parseParams(inputBean.getFootprintParameters());

            SimpleFeatureCollection fc = process.execute(cov,
                    (List<Range<Integer>>) params.get(FootprintParameter.Key.EXCLUSION_RANGES),
                    (Double) params.get(FootprintParameter.Key.THRESHOLD_AREA),
                    (Boolean) params.get(FootprintParameter.Key.COMPUTE_SIMPLIFIED_FOOTPRINT),
                    (Double) params.get(FootprintParameter.Key.SIMPLIFIER_FACTOR),
                    (Boolean) params.get(FootprintParameter.Key.REMOVE_COLLINEAR),
                    (Boolean) params.get(FootprintParameter.Key.FORCE_VALID),
                    (ImageLoadingType) params.get(FootprintParameter.Key.LOADING_TYPE), null);

            // Getting the computed features
            iter = fc.features();

            // First feature is main footprint
            SimpleFeature feature = iter.next();
            Geometry geometry = (Geometry) feature.getDefaultGeometry();
            final String basePath = FilenameUtils.getFullPath(fileName);
            final String baseName = FilenameUtils.getBaseName(fileName);
            final String outputName = baseName + ".wkb";
            File outputFile = new File(FilenameUtils.concat(basePath, outputName));
            if (outputFile.exists()) {
                FileUtils.deleteQuietly(outputFile);
            }
            CoordinateReferenceSystem crs = cov.getCoordinateReferenceSystem();

            // writing the precise footprint
            write(geometry, outputFile, crs);

            if (iter.hasNext()) {
                // Write simplified footprint too
                feature = iter.next();
                geometry = (Geometry) feature.getDefaultGeometry();
                final String simplifiedOutputName = baseName + "_simplified.wkb";
                outputFile = new File(FilenameUtils.concat(basePath, simplifiedOutputName));
                if (outputFile.exists()) {
                    FileUtils.deleteQuietly(outputFile);
                }
                write(geometry, outputFile, crs);

            }

        } catch (DataSourceException e) {
            outputBean.addException(e);
        } catch (IOException e) {
            outputBean.addException(e);
        } finally {
            if (iter != null) {
                iter.close();
            }
            if (reader != null) {
                try {
                    reader.dispose();
                } catch (Throwable t) {

                }
            }
            if (cov != null) {
                try {
                    cov.dispose(true);
                } catch (Throwable t) {

                }
            }
        }
        return outputBean;
    }

    /**
     * Write the provided geometry to the specified output file.
     * 
     * @param geometry
     * @param outputFile
     * @param crs
     * @throws IOException
     */
    static void write(Geometry geometry, File outputFile, CoordinateReferenceSystem crs)
            throws IOException {
        final WKBWriter wkbWriter = new WKBWriter(2);
        final OutputStream outputStream = new FileOutputStream(outputFile);
        final BufferedOutputStream bufferedStream = new BufferedOutputStream(outputStream);
        final OutStream outStream = new OutputStreamOutStream(bufferedStream);
        try {
            wkbWriter.write(geometry, outStream);
        } finally {
            IOUtils.closeQuietly(bufferedStream);
        }

    }
}