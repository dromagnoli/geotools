package org.geotools.referencing.factory.epsg;

import java.util.HashMap;
import java.util.Map;

class EnsembleDefinition {
    String name;
    String code;
    String ellipsoidCode;
    String primeMeridianCode;
    String identifierAuthority;
    String datumCode;
    boolean vertical;

    public EnsembleDefinition(
            String name,
            String code,
            String ellipsoidCode,
            String primeMeridianCode,
            String identifierAuthority,
            String datumCode,
            boolean vertical) {
        this.name = name;
        this.code = code;
        this.ellipsoidCode = ellipsoidCode;
        this.primeMeridianCode = primeMeridianCode;
        this.identifierAuthority = identifierAuthority;
        this.datumCode = datumCode;
        this.vertical = vertical;
    }

    public static boolean hasId(String identifier) {
        return ENSEMBLE_MAP_BY_NAME.containsKey(identifier);
    }

    public static EnsembleDefinition getEnsemble(String epsg) {
        EnsembleDefinition result = null;
        if (ENSEMBLE_MAP_BY_CODE.containsKey(epsg)) {
            result = ENSEMBLE_MAP_BY_CODE.get(epsg);
        }
        return result;
    }

    public String getName() {
        return name;
    }

    public String getCode() {
        return code;
    }

    public String getEllipsoidCode() {
        return ellipsoidCode;
    }

    public String getPrimeMeridianCode() {
        return primeMeridianCode;
    }

    public String getIdentifierAuthority() {
        return identifierAuthority;
    }

    public String getDatumCode() {
        return datumCode;
    }

    public boolean isVertical() {
        return vertical;
    }

    static final Map<String, EnsembleDefinition> ENSEMBLE_MAP_BY_NAME;
    static final Map<String, EnsembleDefinition> ENSEMBLE_MAP_BY_CODE;

    static {
        ENSEMBLE_MAP_BY_NAME = new HashMap<>();
        ENSEMBLE_MAP_BY_CODE = new HashMap<>();

        EnsembleDefinition wgs84 =
                new EnsembleDefinition("World Geodetic System 1984", "6326", "7030", "8901", "6326", "1155", false);
        ENSEMBLE_MAP_BY_NAME.put(wgs84.getName(), wgs84);
        ENSEMBLE_MAP_BY_CODE.put(wgs84.getCode(), wgs84);

        EnsembleDefinition etrs89 = new EnsembleDefinition(
                "European Terrestrial Reference System 1989", "6258", "7019", "8901", "6258", "1178", false);
        ENSEMBLE_MAP_BY_NAME.put(etrs89.getName(), etrs89);
        ENSEMBLE_MAP_BY_CODE.put(etrs89.getCode(), etrs89);

        EnsembleDefinition dvr90 =
                new EnsembleDefinition("Dansk Vertikal Reference 1990", "1371", null, null, "5206", null, true);
        ENSEMBLE_MAP_BY_NAME.put(dvr90.getName(), dvr90);
        ENSEMBLE_MAP_BY_CODE.put(dvr90.getCode(), dvr90);
    }
}
