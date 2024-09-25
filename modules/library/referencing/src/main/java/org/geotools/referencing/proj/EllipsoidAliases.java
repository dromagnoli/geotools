package org.geotools.referencing.proj;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class EllipsoidAliases {

    // Map to store the EPSG Ellipsoid name and its corresponding PROJ alias
    private Map<String, String> ellipsoidAliases = new HashMap<>();

    // Constructor to load aliases from the resource file
    public EllipsoidAliases(String resourceFileName) throws IOException {
        loadEllipsoidAliases(resourceFileName);
    }

    // Method to load the aliases from a resource file
    private void loadEllipsoidAliases(String resourceFileName) throws IOException {
        // Load file from the classpath using ClassLoader
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourceFileName);

        if (inputStream == null) {
            throw new IOException("Resource file not found: " + resourceFileName);
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(";");
                if (parts.length == 2) {
                    String epsgEllipsoid = parts[0].trim();
                    String projAlias = parts[1].trim();
                    ellipsoidAliases.put(epsgEllipsoid, projAlias);
                }
            }
        }
    }

    // Method to get the PROJ alias for a given EPSG ellipsoid name
    public String getProjAlias(String epsgEllipsoid) {
        return ellipsoidAliases.get(epsgEllipsoid);
    }

    // For testing
    public static void main(String[] args) {
        try {
            // Create an instance of EllipsoidAliasLookup and load the resource file
            EllipsoidAliases aliasLookup = new EllipsoidAliases("ellipsoid_aliases.csv");

            // Test lookups
            String epsgName = "Clarke 1966";
            String projAlias = aliasLookup.getProjAlias(epsgName);
            System.out.println("PROJ alias for " + epsgName + ": " + projAlias);

            epsgName = "GRS 1980";
            projAlias = aliasLookup.getProjAlias(epsgName);
            System.out.println("PROJ alias for " + epsgName + ": " + projAlias);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
