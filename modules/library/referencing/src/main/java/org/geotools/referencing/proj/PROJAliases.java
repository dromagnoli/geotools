package org.geotools.referencing.proj;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class EllipsoidAliases {

    private static final String ALIAS_TABLE = "ProjAliases.txt";

    // Map to store the EPSG Ellipsoid name and its corresponding PROJ alias
    private Map<String, String> ellipsoidAliases = new HashMap<>();
    private Map<String, String> primeMeridianAliases = new HashMap<>();

    public EllipsoidAliases() {
        // Load file from the classpath using ClassLoader
        URL aliasURL = EllipsoidAliases.class.getResource(ALIAS_TABLE);

        try {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(aliasURL.openStream()))) {
                String line;
                Map<String, String> currentMap = null;

                while ((line = br.readLine()) != null) {
                    line = line.trim();

                    // Check for section headers
                    if (line.startsWith("[EllipsoidAliases]")) {
                        currentMap = ellipsoidAliases;
                        continue;
                    } else if (line.startsWith("[PrimeMeridianAliases]")) {
                        currentMap = primeMeridianAliases;
                        continue;
                    }
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] parts = line.split(";");
                    if (parts.length == 2 && currentMap != null) {
                        String epsgVersion = parts[0].trim();
                        String projAlias = parts[1].trim();
                        currentMap.put(epsgVersion, projAlias);
                    }
                }
            }
        } catch (IOException ioe) {
            throw new RuntimeException("Unable to load Ellipsoid Aliases ", ioe.getCause());
        }
    }

    // Method to get the PROJ alias for a given EPSG ellipsoid name
    public String getProjEllipsoidAlias(String epsgEllipsoid) {
        return ellipsoidAliases.get(epsgEllipsoid);
    }

    public String getProjPrimeMeridianAlias(String epsgEllipsoid) {
        return primeMeridianAliases.get(epsgEllipsoid);
    }

    // For testing
    public static void main(String[] args) {

        // Create an instance of EllipsoidAliasLookup and load the resource file
        EllipsoidAliases aliasLookup = new EllipsoidAliases();

        // Test lookups
        String epsgName = "Clarke 1966";
        String projAlias = aliasLookup.getProjEllipsoidAlias(epsgName);
        System.out.println("PROJ alias for " + epsgName + ": " + projAlias);

        epsgName = "GRS 1980";
        projAlias = aliasLookup.getProjEllipsoidAlias(epsgName);
        System.out.println("PROJ alias for " + epsgName + ": " + projAlias);
    }
}
