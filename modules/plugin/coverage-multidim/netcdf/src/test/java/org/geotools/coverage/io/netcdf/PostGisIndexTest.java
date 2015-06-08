/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2015, Open Source Geospatial Foundation (OSGeo)
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
package org.geotools.coverage.io.netcdf;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.geotools.coverage.grid.io.GranuleSource;
import org.geotools.coverage.grid.io.HarvestedSource;
import org.geotools.coverage.io.catalog.CoverageSlice;
import org.geotools.coverage.io.catalog.CoverageSlicesCatalog;
import org.geotools.coverage.io.netcdf.crs.NetCDFCRSAuthorityFactory;
import org.geotools.data.DataUtilities;
import org.geotools.data.Query;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.feature.NameImpl;
import org.geotools.gce.imagemosaic.ImageMosaicFormat;
import org.geotools.gce.imagemosaic.ImageMosaicReader;
import org.geotools.imageio.netcdf.NetCDFImageReader;
import org.geotools.imageio.netcdf.NetCDFImageReaderSpi;
import org.geotools.imageio.netcdf.Slice2DIndex;
import org.geotools.test.OnlineTestCase;
import org.geotools.test.TestData;
import org.junit.Ignore;
import org.junit.Test;
import org.opengis.feature.Property;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.type.Name;
import org.opengis.filter.Filter;
import org.opengis.filter.FilterFactory2;
import org.opengis.filter.sort.SortBy;
import org.opengis.filter.sort.SortOrder;

/**
 * Testing Low level index based on PostGis
 * 
 * @author Daniele Romagnoli, GeoSolutions SAS
 * @source $URL$
 */
public final class PostGisIndexTest extends OnlineTestCase {

    @Override
    public void setUpInternal() throws Exception {
        String netcdfPropertiesPath = TestData.file(this, "netcdf.projections.properties")
                .getCanonicalPath();
        System.setProperty(NetCDFCRSAuthorityFactory.SYSTEM_DEFAULT_USER_PROJ_FILE,
                netcdfPropertiesPath);
    }

    private final static Logger LOGGER = Logger.getLogger(PostGisIndexTest.class.toString());

    private final static String GOME_DIR = "gomeDir";

    private final static String GOME_FILE = "O3-NO2.nc";

    private final static String UTM_DIR = "utmDir";

    @Ignore
    public void testPostGisIndexWrapping() throws Exception {
        final String auxName = "O3NO2wrapped.xml";
        File file = TestData.file(this, GOME_FILE);
        File auxFile = TestData.file(this, auxName);
        final File dir = new File(TestData.file(this, "."), GOME_DIR);
        if (!dir.mkdir()) {
            FileUtils.deleteDirectory(dir);
            assertTrue("Unable to create workdir:" + dir, dir.mkdir());
        }
        File destFile = new File(dir, GOME_FILE);
        File destAuxFile = new File(dir, auxName);
        FileUtils.copyFile(file, destFile);
        FileUtils.copyFile(auxFile, destAuxFile);
        createDatastoreProperties(dir, null);

        final NetCDFImageReaderSpi unidataImageReaderSpi = new NetCDFImageReaderSpi();
        assertTrue(unidataImageReaderSpi.canDecodeInput(file));
        NetCDFImageReader reader = null;
        try {
            reader = (NetCDFImageReader) unidataImageReaderSpi.createReaderInstance();
            reader.setAuxiliaryFilesPath(destAuxFile.getCanonicalPath());
            reader.setInput(destFile);
            int numImages = reader.getNumImages(true);
            assertEquals(8, numImages);
            for (int i = 0; i < numImages; i++) {
                Slice2DIndex sliceIndex = reader.getSlice2DIndex(i);
                assertNotNull(sliceIndex);
                spitOutSliceInformation(i, sliceIndex);
            }

            // check coverage names
            final List<Name> names = reader.getCoveragesNames();
            assertNotNull(names);
            assertTrue(!names.isEmpty());
            assertTrue(2 == names.size());
            assertTrue(names.contains(new NameImpl("O3")));

            // checking slice catalog
            final CoverageSlicesCatalog cs = reader.getCatalog();
            assertNotNull(cs);

            // get typenames
            final String[] typeNames = cs.getTypeNames();
            for (String typeName : typeNames) {
                final List<CoverageSlice> granules = cs.getGranules(new Query(typeName));
                checkGranules(granules);
            }
        } finally {
            if (reader != null) {
                try {
                    reader.dispose();
                } catch (Throwable t) {
                    // Does nothing
                }
            }
        }
    }

    private void createDatastoreProperties(File dir, Map<String, String> override) throws IOException {
        FileWriter out = null;
        try {

            // Preparing custom multidim datastore properties
            out = new FileWriter(new File(dir, "mddatastore.properties"));
            final Properties props = createExampleFixture();
            if (override != null && override.isEmpty()) {
                Set<String> mapKeys = override.keySet();
                for (String mapKey: mapKeys) {
                    String value = override.get(mapKey);
                    props.setProperty(mapKey, value);
                }
            }

            final Set<Object> keyset = props.keySet();
            for (Object key : keyset) {
                final String key_ = (String) key;
                final String value = props.getProperty(key_);
                out.write(key_.replace(" ", "\\ ") + "=" + value.replace(" ", "\\ ") + "\n");
            }
            out.flush();
        } finally {
            if (out != null) {
                IOUtils.closeQuietly(out);
            }
        }
    }

    @Test
    public void testUTM() throws Exception {
        File file = TestData.file(this, "utm.nc");
        final File dir = new File(TestData.file(this, "."), UTM_DIR);
        if (!dir.mkdir()) {
            FileUtils.deleteDirectory(dir);
            assertTrue("Unable to create workdir:" + dir, dir.mkdir());
        }
        File destFile = new File(dir, "utm.nc");
        FileUtils.copyFile(file, destFile);
        createDatastoreProperties(dir, null);

        final NetCDFImageReaderSpi unidataImageReaderSpi = new NetCDFImageReaderSpi();
        assertTrue(unidataImageReaderSpi.canDecodeInput(destFile));
        NetCDFImageReader reader = null;
        try {

            // checking low level
            reader = (NetCDFImageReader) unidataImageReaderSpi.createReaderInstance();
            reader.setInput(destFile);
            int numImages = reader.getNumImages(true);
            LOGGER.info("Found " + numImages + " images.");
            for (int i = 0; i < numImages; i++) {
                Slice2DIndex sliceIndex = reader.getSlice2DIndex(i);
                assertNotNull(sliceIndex);
                spitOutSliceInformation(i, sliceIndex);
            }

            // checking slice catalog
            CoverageSlicesCatalog cs = reader.getCatalog();
            assertNotNull(cs);

            // get typenames
            final String[] typeNames = cs.getTypeNames();
            for (String typeName : typeNames) {
                
                final List<CoverageSlice> granules = cs.getGranules(new Query(typeName,
                        Filter.INCLUDE));
                checkGranules(granules);
                
            }
            // dipose reader and read it again once the catalog has been created
            reader.dispose();
            reader.setInput(destFile);
            cs = reader.getCatalog();
            String typeName = cs.getTypeNames()[0];
            assertNotNull(cs);
            final List<CoverageSlice> granules = cs.getGranules(new Query(typeName,
                    Filter.INCLUDE));
            checkGranules(granules);

        } finally {
            if (reader != null) {
                try {
                    reader.dispose();
                } catch (Throwable t) {
                    // Does nothing
                }
            }
        }
    }

    @Test
    public void testMosaicUsingPostGisIndexForNC() throws Exception {
        // prepare a "mosaic" with just one NetCDF
        File nc1 = TestData.file(this,"polyphemus_20130301_test.nc");
        File mosaic = new File(TestData.file(this,"."),"mosaic");
        if (mosaic.exists()) {
            FileUtils.deleteDirectory(mosaic);
        }
        assertTrue(mosaic.mkdirs());
        FileUtils.copyFileToDirectory(nc1, mosaic);

        // The indexer
        String indexer = "TimeAttribute=time\n" + 
                "Schema=the_geom:Polygon,location:String,imageindex:Integer,time:java.util.Date\n"
                +"AuxiliaryDatastoreFile=mddatastore.properties";
        FileUtils.writeStringToFile(new File(mosaic, "indexer.properties"), indexer);

        // using an H2 based datastore for imageMosaic index
        File dsp = TestData.file(this, "datastore.properties");
        FileUtils.copyFileToDirectory(dsp, mosaic);
        Map<String, String> overrideMap = new HashMap<String, String>();
        overrideMap.put("database", "lowlevelindex");
        createDatastoreProperties(mosaic, overrideMap);

        // have the reader harvest it
        ImageMosaicFormat format = new ImageMosaicFormat();
        ImageMosaicReader reader = format.getReader(mosaic);
        SimpleFeatureIterator it = null;
        assertNotNull(reader);
        try {
            String[] names = reader.getGridCoverageNames();
            assertEquals(1, names.length);
            assertEquals("O3", names[0]);
            
            // check we have the two granules we expect
            GranuleSource source = reader.getGranules("O3", true);
            FilterFactory2 ff = CommonFactoryFinder.getFilterFactory2();
            Query q = new Query(Query.ALL);
            q.setSortBy(new SortBy[] {ff.sort("time", SortOrder.ASCENDING)});
            SimpleFeatureCollection granules = source.getGranules(q);
            assertEquals(2, granules.size());
            it = granules.features();
            assertTrue(it.hasNext());
            SimpleFeature f = it.next();
            assertEquals("polyphemus_20130301_test.nc", f.getAttribute("location"));
            assertEquals(0, f.getAttribute("imageindex"));
            assertEquals("2013-03-01T00:00:00.000Z", ConvertersHack.convert(f.getAttribute("time"), String.class));
            assertTrue(it.hasNext());
            f = it.next();
            assertEquals("polyphemus_20130301_test.nc", f.getAttribute("location"));
            assertEquals(1, f.getAttribute("imageindex"));
            assertEquals("2013-03-01T01:00:00.000Z", ConvertersHack.convert(f.getAttribute("time"), String.class));
            it.close();
            
            // close the reader and re-open it
            reader.dispose();
            reader = format.getReader(mosaic);
            source = reader.getGranules("O3", true);
            
            // wait a bit, we have to make sure the old indexes are recognized as old
            Thread.sleep(1000);
            
            // now replace the netcdf file with a more up to date version of the same 
            File nc2 = TestData.file(this,"polyphemus_20130302_test.nc");
            File target = new File(mosaic, "polyphemus_20130302_test.nc");
            FileUtils.copyFile(nc2, target, false);
            File fileToHarvest = new File(mosaic, "polyphemus_20130302_test.nc");
            List<HarvestedSource> harvestSummary = reader.harvest(null, fileToHarvest, null);
            assertEquals(2, harvestSummary.size());
            HarvestedSource hf = harvestSummary.get(0);
            assertEquals("polyphemus_20130302_test.nc", ((File) hf.getSource()).getName());
            assertTrue(hf.success());
            assertEquals(1, reader.getGridCoverageNames().length);
            
            // check that we have four times now
            source = reader.getGranules("O3", true);
            granules = source.getGranules(q);
            assertEquals(4, granules.size());
        } finally {
            if(it != null) {
                it.close();
            }
            reader.dispose();
        }
    }
    
    private void checkGranules(List<CoverageSlice> granules) {
        assertNotNull(granules);
        assertFalse(granules.isEmpty());
        for (CoverageSlice slice : granules) {
            final SimpleFeature sf = slice.getOriginator();
            if (TestData.isInteractiveTest()) {
                LOGGER.info(DataUtilities.encodeFeature(sf));
            }

            // checks
            for (Property p : sf.getProperties()) {
                assertNotNull("Property " + p.getName() + " had a null value!",
                        p.getValue());
            }
        }
    }

    /**
     * recursively delete indexes
     * 
     * @param file
     */
    private void cleanupFolders(final File file) {
        if (file.isFile()) {
        } else {
            final File[] files = file.listFiles();

            for (File f : files) {
                cleanupFolders(f);
                if (f.getName().equalsIgnoreCase(GOME_DIR)
                        || f.getName().equalsIgnoreCase(UTM_DIR)) {

                    f.delete();
                }
            }
        }
    }

    private void cleanUp() throws Exception {
        final File dir = TestData.file(this, ".");
        cleanupFolders(dir);
        removeTables(new String[] { "O3", "NO2", "Band1" }, "catalogtest");
    }

    /**
     * Remove the postgis created tables
     * 
     * @param tables
     * @param database
     * @throws Exception
     */
    private void removeTables(String[] tables, String database) throws Exception {
        // delete tables
        Class.forName("org.postgresql.Driver");
        Connection connection = null;
        Statement st = null;
        try {
            connection = DriverManager.getConnection(
                    "jdbc:postgresql://" + fixture.getProperty("host") + ":"
                            + fixture.getProperty("port") + "/"
                            + (database != null ? database : fixture.getProperty("database")),
                    fixture.getProperty("user"), fixture.getProperty("passwd"));
            st = connection.createStatement();
            for (String table : tables) {
                st.execute("DROP TABLE IF EXISTS \"" + table + "\"");
            }
        } finally {

            if (st != null) {
                try {
                    st.close();
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, e.getLocalizedMessage(), e);
                }
            }

            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, e.getLocalizedMessage(), e);
                }
            }
        }
    }

    @Override
    public void tearDownInternal() throws Exception {
        if (TestData.isInteractiveTest()) {
            return;
        }
        cleanUp();
    }

    /**
     * @param i
     * @param sliceIndex
     */
    private void spitOutSliceInformation(int i, Slice2DIndex sliceIndex) {
        if (TestData.isInteractiveTest()) {
            String variableName = sliceIndex.getVariableName();
            StringBuilder sb = new StringBuilder();
            sb.append("\n").append("\n").append("\n");
            sb.append("IMAGE: ").append(i).append("\n");
            sb.append(" Variable Name = ").append(variableName);
            sb.append(" ( Z = ");
            sb.append(sliceIndex.getZIndex());
            sb.append("; T = ");
            sb.append(sliceIndex.getTIndex());
            sb.append(")");
            LOGGER.info(sb.toString());
        }
    }

    //

    @Override
    protected Properties createExampleFixture() {
        // create sample properties file for postgis datastore
        final Properties props = new Properties();
        props.setProperty("SPI", "org.geotools.data.postgis.PostgisNGDataStoreFactory");
        props.setProperty("host", "localhost");
        props.setProperty("port", "5432");
        props.setProperty("user", "postgres");
        props.setProperty("passwd", "postgres");
        props.setProperty("database", "catalogtest");
        props.setProperty("schema", "public");
        props.setProperty("Loose bbox", "true");
        props.setProperty("Estimated extends", "false");
        props.setProperty("validate connections", "true");
        props.setProperty("Connection timeout", "10");
        props.setProperty("preparedStatements", "false");
        props.setProperty("create database params", "WITH TEMPLATE=template_postgis");
        return props;
    }

    @Override
    protected String getFixtureId() {
        return "mdpostgis_datastore";
    }
}
