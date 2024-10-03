package org.geotools.gce.geotiff;

import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.referencing.CRS;
import org.geotools.referencing.proj.PROJFormatter;
import org.geotools.referencing.proj.PROJFormattable;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class PROJTestWriteAll {

    public static void main(String[] args) {
        String outputFilePath = "c:/work/code/python/gt_proj_definitions.csv";
        PROJFormatter formatter = new PROJFormatter();

        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFilePath))) {
            // Write CSV headers
            writer.println("EPSG Code,PROJ Definition");

            // Iterate over EPSG codes from 1 to 100000
            for (int epsgCode = 1; epsgCode <= 33000; epsgCode++) {
                formatter.clear();
                try {
                    // Get the CRS from EPSG code
                    CoordinateReferenceSystem crs = CRS.decode("EPSG:" + epsgCode);
                    String projString = null;
                    if (crs instanceof PROJFormattable) {
                        System.out.println(epsgCode);
                        projString = formatter.toPROJ(crs);
                        writer.printf("%d,%s%n", epsgCode, projString);
                    }

                } catch (FactoryException e) {

                }
            }
        } catch (IOException e) {
            System.out.println("CSV file '" + outputFilePath + "' generated successfully.");
        }
    }
}
