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

import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
import java.awt.image.renderable.RenderedImageFactory;
import java.io.File;
import java.io.IOException;

import javax.media.jai.BorderExtender;
import javax.media.jai.ImageLayout;
import javax.media.jai.JAI;
import javax.media.jai.OperationDescriptor;
import javax.media.jai.OperationRegistry;
import javax.media.jai.RasterFactory;
import javax.media.jai.RenderedOp;
import javax.media.jai.registry.RenderedRegistryMode;

import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.processing.operation.ShadedReliefDescriptor;
import org.geotools.coverage.processing.operation.ShadedReliefOpImage.Algorithm;
import org.geotools.coverage.processing.operation.ShadedReliefRIF;
import org.opengis.parameter.ParameterValue;
import org.opengis.referencing.datum.PixelInCell;
import org.opengis.referencing.operation.MathTransform;

public class ShadedReliefTest {

    private static final double DEFAULT_Z = 100000;

    private static final double DEFAULT_SCALE = 111120;

    private static final double DEFAULT_AZIMUTH = 315;

    private static final double DEFAULT_ALTITUDE = 45;

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

        long time = System.currentTimeMillis();
        GeoTiffWriter writer = new GeoTiffWriter(new File("c:/data/DEM/test/hillshadetest_"
                + time + ".tif"));
        GridCoverageFactory factory = new GridCoverageFactory();
        GridCoverage2D gc2 = factory.create("hillshade", finalImage, reader.getOriginalEnvelope());
        writer.write(gc2, null);
        writer.dispose();
    }

}
