package org.geotools.referencing.factory.gridshift;

import org.opengis.coverage.grid.GridGeometry;

public class VerticalGridShift {

    String name;

    int width;

    int height;

    GridGeometry gridGeometry;


    public void setLonPositiveEastDegrees(double srcPt) {
    }

    public void setLatDegrees(double srcPt) {
    }

    public double getShiftedLonPositiveEastDegrees() {
        return 0;
    }

    public double getShiftedLatDegrees() {
        return 0;
    }
}
