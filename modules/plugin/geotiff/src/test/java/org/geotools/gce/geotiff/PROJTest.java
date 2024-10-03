package org.geotools.gce.geotiff;

import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.referencing.CRS;
import org.geotools.referencing.proj.PROJFormatter;
import org.geotools.referencing.proj.PROJFormattable;

public class PROJTest {

    public static void main(String[] args) {
        //int[] codes = new int[]{32632};
        //int[] codes = new int[] {3728};
        int[] codes = new int[] {3785};
        PROJFormatter formatter = new PROJFormatter();
        for (int code : codes) {
            formatter.clear();
            String epsgCode = "EPSG:" + code;
            try {
                CoordinateReferenceSystem crs = CRS.decode(epsgCode);
                System.out.println(crs);
                if (crs instanceof PROJFormattable) {
                    System.out.println(epsgCode);
                    String proj = formatter.toPROJ(crs);
                    //String proj = formatter.toPROJ(crs);
                    System.out.println(proj);
                }
            } catch (FactoryException e) {
                // Do nothing
            }
        }
    }
}
