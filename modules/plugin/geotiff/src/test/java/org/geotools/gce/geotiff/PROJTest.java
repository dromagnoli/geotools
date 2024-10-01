package org.geotools.gce.geotiff;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.referencing.CRS;
import org.geotools.referencing.proj.PROJFormatter;
import org.geotools.referencing.util.PROJFormattable;

public class PROJTest {

    public static void main(String[] args) {
        //int[] codes = new int[]{32632};
        //int[] codes = new int[] {3728};
        int[] codes = new int[] {29371};
        PROJFormatter formatter = new PROJFormatter();
        for (int code : codes) {
            formatter.clear();
            String epsgCode = "EPSG:" + code;
            try {
                CoordinateReferenceSystem crs = CRS.decode(epsgCode);
                System.out.println(crs);
                if (crs instanceof PROJFormattable) {
                    System.out.println(epsgCode);
                    System.out.println(formatter.toPROJ(crs));
                }
            } catch (FactoryException e) {
                // Do nothing
            }
        }
    }
}
