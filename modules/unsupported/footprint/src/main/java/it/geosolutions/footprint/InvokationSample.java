package it.geosolutions.footprint;

import it.geosolutions.footprint.FootprintExtractionTool.FootprintProcessingInputBean;
import it.geosolutions.footprint.FootprintExtractionTool.FootprintProcessingOutputBean;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class InvokationSample {

    public static void main(String[] args) {
        FootprintProcessingInputBean inputBean = new FootprintProcessingInputBean();
        inputBean.setInputFile(new File(
                "C:\\data\\eumetsat\\IR108\\WMS-MSG-IR108-8bit_1412071200.tiff"));
        Map<String, Object> parameters = new HashMap<String, Object>();
        // parameters.put(FootprintParameter.Key.THRESHOLD_AREA, 100);
        // parameters.put(FootprintParameter.Key.FORCE_VALID, false);
        parameters.put(FootprintParameter.Key.COMPUTE_SIMPLIFIED_FOOTPRINT, true);
        inputBean.setFootprintParameters(parameters);

        FootprintProcessingOutputBean outputBean = FootprintExtractionTool
                .generateFootprint(inputBean);
        if (outputBean.getExceptions().isEmpty()) {
            // everything was ok. wkb file should have been created beside the original tif file
            // note that previous WKB file will be deleted if already existing
        }
    }
}
