/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2011, Open Source Geospatial Foundation (OSGeo)
 *    (C) 2004-2005, Open Geospatial Consortium Inc.
 *
 *    All Rights Reserved. http://www.opengis.org/legal/
 */
package org.geotools.api.metadata.spatial;

import static org.geotools.api.annotation.Obligation.MANDATORY;
import static org.geotools.api.annotation.Obligation.OPTIONAL;
import static org.geotools.api.annotation.Specification.ISO_19115;

import java.util.Collection;
import org.geotools.api.annotation.UML;
import org.geotools.api.metadata.citation.Citation;
import org.geotools.api.util.InternationalString;
import org.geotools.api.util.Record;

/**
 * Grid with cells irregularly spaced in any given geographic/map projection coordinate system,
 * whose individual cells can be geolocated using geolocation information supplied with the data but
 * cannot be geolocated from the grid properties alone.
 *
 * @version <A HREF="http://www.opengeospatial.org/standards/as#01-111">ISO 19115</A>
 * @author Martin Desruisseaux (IRD)
 * @author Cory Horner (Refractions Research)
 * @since GeoAPI 2.0
 */
public interface Georeferenceable extends GridSpatialRepresentation {
    /**
     * Indication of whether or not control point(s) exists.
     *
     * @return Whether or not control point(s) exists.
     */
    boolean isControlPointAvailable();

    /**
     * Indication of whether or not orientation parameters are available.
     *
     * @return Whether or not orientation parameters are available.
     */
    @UML(
            identifier = "orientationParameterAvailability",
            obligation = MANDATORY,
            specification = ISO_19115)
    boolean isOrientationParameterAvailable();

    /**
     * Description of parameters used to describe sensor orientation.
     *
     * @return Description of parameters used to describe sensor orientation, or {@code null}.
     */
    @UML(
            identifier = "orientationParameterDescription",
            obligation = OPTIONAL,
            specification = ISO_19115)
    InternationalString getOrientationParameterDescription();

    /**
     * Terms which support grid data georeferencing.
     *
     * @return Terms which support grid data georeferencing.
     * @since GeoAPI 2.1
     */
    Record getGeoreferencedParameters();

    /**
     * Reference providing description of the parameters.
     *
     * @return Reference providing description of the parameters.
     */
    Collection<? extends Citation> getParameterCitation();
}
