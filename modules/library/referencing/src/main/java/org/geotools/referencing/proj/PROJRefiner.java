package org.geotools.referencing.proj;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PROJRefiner {

    // A class to hold regex and its replacement
    private static class Refinement {
        String regex;
        String replacement;

        Refinement(String regex, String replacement) {
            this.regex = regex;
            this.replacement = replacement;
        }
    }

    private static final String REFINEMENTS_FILE = "ProjRefinements.txt";

    private Properties properties;

    private String globalUpdates = "";

    private Map<String, List<Refinement>> epsgRefinements;
    private List<String> projOrder;

    public PROJRefiner() {
        properties = new Properties();
        projOrder = new ArrayList<>();
        epsgRefinements = new HashMap<>();

        URL aliasURL = PROJRefiner.class.getResource(REFINEMENTS_FILE);

        try (InputStream input = aliasURL.openStream()) {
            properties.load(input);
            if (properties.containsKey("global_updates")) {
                globalUpdates = properties.getProperty("global_updates");
            }
            loadProjOrder();
            loadRefinements();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void loadProjOrder() {
        String order = properties.getProperty("proj_order");
        if (order != null) {
            projOrder.addAll(Arrays.asList(order.split(",")));
        }
    }
    private void loadRefinements() {

        for (int i = 1; properties.containsKey("regex" + i); i++) {
            String regex = properties.getProperty("regex" + i);
            String replacement = properties.getProperty("replacement" + i);
            String codes = properties.getProperty("codes" + i);

            if (codes != null) {
                String[] codeArray = codes.split(",");
                for (String code : codeArray) {
                    code = code.trim();
                    if (code.contains("-")) {
                        // Handle ranges
                        String[] range = code.split("-");
                        int start = Integer.parseInt(range[0].trim());
                        int end = Integer.parseInt(range[1].trim());
                        for (int j = start; j <= end; j++) {
                            epsgRefinements
                                    .computeIfAbsent(String.valueOf(j), k -> new ArrayList<>())
                                    .add(new Refinement(regex, replacement));
                        }
                    } else {
                        // Handle individual codes
                        epsgRefinements
                                .computeIfAbsent(code, k -> new ArrayList<>())
                                .add(new Refinement(regex, replacement));
                    }
                }
            }
        }
    }

    // Apply global updates and regex replacements for specific EPSG codes
    public String refine(String projString, String epsgCode) {
        // Apply global updates
        StringBuilder updatedProjString = new StringBuilder(projString);
        updatedProjString.append(" " + globalUpdates);

        List<Refinement> refinements = epsgRefinements.get(epsgCode);
        if (refinements != null) {
            for (Refinement refinement : refinements) {
                Pattern pattern = Pattern.compile(refinement.regex);
                Matcher matcher = pattern.matcher(updatedProjString);
                updatedProjString = new StringBuilder(matcher.replaceAll(refinement.replacement));
            }
        }
        return sortProjString(updatedProjString.toString().replaceAll("\\s+", " "));
    }

    private String sortProjString(String projString) {
        Map<String, String> projComponents = new LinkedHashMap<>();

        // Split the proj string into components
        String[] components = projString.trim().split("\\s+");
        for (String component : components) {
            if (component.contains("=")) {
                String[] pair = component.split("=");
                projComponents.put(pair[0], pair[1]);
            } else {
                projComponents.put(component, null);
            }
        }

        // Reorder components according to the defined order
        StringBuilder sortedProjString = new StringBuilder();
        for (String key : projOrder) {
            if (projComponents.containsKey("+" + key)) {
                sortedProjString.append("+").append(key);
                String value = projComponents.get("+" + key);
                if (value != null) {
                    sortedProjString.append("=").append(value);
                }
                sortedProjString.append(" ");
            }
        }

        // Add any remaining components not in the order
        for (String key : projComponents.keySet()) {
            if (!projOrder.contains(key.substring(1))) {
                sortedProjString.append(key).append("=").append(projComponents.get(key)).append(" ");
            }
        }

        return sortedProjString.toString().trim();
    }

}