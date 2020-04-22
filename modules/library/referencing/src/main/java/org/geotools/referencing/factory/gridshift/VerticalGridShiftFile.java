package org.geotools.referencing.factory.gridshift;


import sun.misc.IOUtils;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;

public class VerticalGridShiftFile {


    public void loadGridShiftFile(File file) {
        String path = file.getAbsolutePath();

        

    }

    /*
    public void loadGridShiftFile(InputStream in, boolean b) {

    }
    */


    public boolean gridShiftForward(VerticalGridShift shift) {
        return true;
    }

    public boolean gridShiftReverse(VerticalGridShift shift) {
        return true;
    }
}
