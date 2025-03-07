package org.geotools.referencing.factory.epsg;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.geotools.metadata.iso.citation.Citations;
import org.geotools.referencing.NamedIdentifier;

class EnsembleDefinition {
    String name;
    NamedIdentifier nameIdentifier;
    String code;
    String ellipsoidCode;
    String primeMeridianCode;
    String identifierAuthority;
    String datumCode;
    boolean vertical;

    public EnsembleDefinition(
            String name,
            NamedIdentifier nameIdentifier,
            String code,
            String ellipsoidCode,
            String primeMeridianCode,
            String identifierAuthority,
            String datumCode,
            boolean vertical) {
        this.name = name;
        this.nameIdentifier = nameIdentifier;
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

    public static boolean hasCode(String code) {
        return ENSEMBLE_MAP_BY_CODE.containsKey(code);
    }

    public static EnsembleDefinition getEnsemble(String epsg) {
        EnsembleDefinition result = null;
        if (ENSEMBLE_MAP_BY_CODE.containsKey(epsg)) {
            result = ENSEMBLE_MAP_BY_CODE.get(epsg);
        }
        return result;
    }

    public static boolean isExcludedOperation(String operationCode) {
        return EXCLUDED_OPERATIONS.contains(operationCode);
    }

    public String getName() {
        return name;
    }

    public NamedIdentifier getNameIdentifier() {
        return nameIdentifier;
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
    static final Set<String> EXCLUDED_OPERATIONS;

    static {
        ENSEMBLE_MAP_BY_NAME = new HashMap<>();
        ENSEMBLE_MAP_BY_CODE = new HashMap<>();

        EnsembleDefinition wgs84 = new EnsembleDefinition(
                "World Geodetic System 1984",
                new NamedIdentifier(Citations.EPSG, "World Geodetic System 1984"),
                "6326",
                "7030",
                "8901",
                "6326",
                "1155",
                false);
        ENSEMBLE_MAP_BY_NAME.put(wgs84.getName(), wgs84);
        ENSEMBLE_MAP_BY_CODE.put(wgs84.getCode(), wgs84);

        EnsembleDefinition etrs89 = new EnsembleDefinition(
                "European Terrestrial Reference System 1989",
                new NamedIdentifier(Citations.EPSG, "European Terrestrial Reference System 1989"),
                "6258",
                "7019",
                "8901",
                "6258",
                "1178",
                false);
        ENSEMBLE_MAP_BY_NAME.put(etrs89.getName(), etrs89);
        ENSEMBLE_MAP_BY_CODE.put(etrs89.getCode(), etrs89);

        EnsembleDefinition dvr90 = new EnsembleDefinition(
                "Dansk Vertikal Reference 1990",
                new NamedIdentifier(Citations.EPSG, "Dansk Vertikal Reference 1990"),
                "1371",
                null,
                null,
                "5206",
                null,
                true);
        ENSEMBLE_MAP_BY_NAME.put(dvr90.getName(), dvr90);
        ENSEMBLE_MAP_BY_CODE.put(dvr90.getCode(), dvr90);

        // These operations are returned at the top of the results but they
        // provide operations that are breaking several tests where a
        // back-forward and transformation doesn't provide the same result back.
        // We may need to revisit the sorting criteria/logic
        EXCLUDED_OPERATIONS = new HashSet<>();
        EXCLUDED_OPERATIONS.add("9688");
    }
}
