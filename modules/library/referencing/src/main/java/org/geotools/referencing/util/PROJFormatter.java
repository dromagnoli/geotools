package org.geotools.referencing.util;

import java.text.FieldPosition;
import java.text.NumberFormat;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.measure.Unit;
import javax.measure.quantity.Angle;
import javax.measure.quantity.Length;
import org.geotools.api.metadata.Identifier;
import org.geotools.api.metadata.citation.Citation;
import org.geotools.api.parameter.GeneralParameterValue;
import org.geotools.api.parameter.ParameterDescriptor;
import org.geotools.api.parameter.ParameterValue;
import org.geotools.api.parameter.ParameterValueGroup;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.IdentifiedObject;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.crs.GeographicCRS;
import org.geotools.api.referencing.crs.ProjectedCRS;
import org.geotools.api.referencing.cs.CoordinateSystemAxis;
import org.geotools.api.util.GenericName;
import org.geotools.api.util.InternationalString;
import org.geotools.measure.UnitFormatter;
import org.geotools.metadata.iso.citation.Citations;
import org.geotools.metadata.math.XMath;
import org.geotools.referencing.CRS;
import org.geotools.referencing.wkt.AbstractParser;
import org.geotools.referencing.wkt.EpsgUnitFormat;
import org.geotools.referencing.wkt.EsriUnitFormat;
import org.geotools.referencing.wkt.Formattable;
import org.geotools.util.X364;
import tech.units.indriya.AbstractUnit;

public class PROJFormatter {

    private StringBuilder builder;

    private Citation authority = Citations.PROJ;

    private static final Class<? extends IdentifiedObject>[] AUTHORITY_EXCLUDE =
            new Class[] {CoordinateSystemAxis.class};

    /** The unit for formatting measures, or {@code null} for the "natural" unit. */
    private Unit<Length> linearUnit;

    /**
     * The unit for formatting measures, or {@code null} for the "natural" unit of each element.
     * This value is set for example by "GEOGCS", which force its enclosing "PRIMEM" to take the
     * same units than itself.
     */
    private Unit<Angle> angularUnit;

    public Citation getAuthority() {
        return authority;
    }

    public void setAuthority(Citation authority) {
        this.authority = authority;
        this.unitFormatter =
                CRS.equalsIgnoreMetadata(Citations.ESRI, authority)
                        ? EsriUnitFormat.getInstance()
                        : EpsgUnitFormat.getInstance();
    }

    /** The object to use for formatting numbers. */
    private NumberFormat numberFormat;

    /** The object to use for formatting units. */
    private UnitFormatter unitFormatter = EpsgUnitFormat.getInstance();

    /** Dummy field position. */
    private final FieldPosition dummy = new FieldPosition(0);

    /**
     * The starting point in the builder. Always 0, except when used by {@link
     * AbstractParser#format}.
     */
    int builderBase;

    /**
     * Non-null if the formatting is invalid. If non-null, then this field contains the interface
     * class of the problematic part (e.g. {@link org.geotools.api.referencing.crs.EngineeringCRS}).
     */
    private Class<?> unformattable;

    /** Warning that may be produced during formatting, or {@code null} if none. */
    String warning;

    private Map<String, String> map = new LinkedHashMap<>();

    public String toProj(Formattable formattable) {
        builder = new StringBuilder();
        if (formattable instanceof GeographicCRS) {
            //formatGeographic((GeographicCRS) formattable);
        }
        return null;
    }

    public void append(final Formattable formattable) {

        int base = builder.length();
        final IdentifiedObject info =
                (formattable instanceof IdentifiedObject) ? (IdentifiedObject) formattable : null;
        if (info != null) {
            map.put(getName(info), null);
        }

        final Identifier identifier = getIdentifier(info);
        if (identifier != null && authorityAllowed(info)) {
            final Citation authority = identifier.getAuthority();
            if (authority != null) {
                InternationalString inter = authority.getTitle();
                String title = (inter != null) ? inter.toString() : null;
                for (final InternationalString alt : authority.getAlternateTitles()) {
                    if (alt != null) {
                        final String candidate = alt.toString();
                        if (candidate != null) {
                            if (title == null || candidate.length() < title.length()) {
                                title = candidate;
                            }
                        }
                    }
                }
                if (title != null) {
                    builder.append(title);
                    final String code = identifier.getCode();
                    if (code != null) {
                        builder.append(code);
                    }
                }
            }
        }
    }

    public void append(final GeneralParameterValue parameter) {
        if (parameter instanceof ParameterValueGroup) {
            for (final GeneralParameterValue param : ((ParameterValueGroup) parameter).values()) {
                append(param);
            }
        }
        if (parameter instanceof ParameterValue) {
            final ParameterValue<?> param = (ParameterValue) parameter;
            final ParameterDescriptor<?> descriptor = param.getDescriptor();
            final Unit<?> valueUnit = descriptor.getUnit();
            Unit<?> unit = valueUnit;

            if (unit != null && !AbstractUnit.ONE.equals(unit)) {
                if (linearUnit != null && unit.isCompatible(linearUnit)) {
                    unit = linearUnit;
                } else if (angularUnit != null && unit.isCompatible(angularUnit)) {
                    unit = angularUnit;
                }
            }

            builder.append(getName(descriptor));
            if (unit != null) {
                double value;
                try {
                    value = param.doubleValue(unit);
                } catch (IllegalStateException exception) {
                    // May happen if a parameter is mandatory (e.g. "semi-major")
                    // but no value has been set for this parameter.
                    warning = exception.getLocalizedMessage();
                    value = Double.NaN;
                }
                if (!unit.equals(valueUnit)) {
                    value = XMath.trimDecimalFractionDigits(value, 4, 9);
                }
                //format(value);
            } else {
                //appendObject(param.getValue());
            }
        }
    }
    
    /**
     * Returns the preferred name for the specified object. If the specified object contains a name
     * from the preferred authority then this name is returned. Otherwise, it will be added to not
     * parseable list
     *
     * @param info The object to looks for a preferred name.
     * @return The preferred name.
     */
    public String getName(final IdentifiedObject info) {
        final Identifier name = info.getName();
        if (Citations.PROJ == name.getAuthority()) {
            final Collection<GenericName> aliases = info.getAlias();
            if (aliases != null) {
                /*
                 * The main name doesn't matches. Search in alias. We will first
                 * check if alias implements Identifier (this is the case of
                 * Geotools implementation). Otherwise, we will look at the
                 * scope in generic name.
                 */
                for (final GenericName alias : aliases) {
                    if (alias instanceof Identifier) {
                        final Identifier candidate = (Identifier) alias;
                        if (Citations.PROJ == candidate.getAuthority()) {
                            return candidate.getCode();
                        }
                    }
                }
                // The "null" locale argument is required for getting the unlocalized version.
                final String title = authority.getTitle().toString(null);
                for (final GenericName alias : aliases) {
                    final GenericName scope = alias.scope().name();
                    if (scope != null) {
                        if (title.equalsIgnoreCase(scope.toString())) {
                            return alias.tip().toString();
                        }
                    }
                }
            }
        }
        return name.getCode();
    }

    public Identifier getIdentifier(final IdentifiedObject info) {
        Identifier first = null;
        if (info != null) {
            final Collection<? extends Identifier> identifiers = info.getIdentifiers();
            if (identifiers != null) {
                for (final Identifier id : identifiers) {
                    if (authority == id.getAuthority()) {
                        return id;
                    }
                    if (first == null) {
                        first = id;
                    }
                }
            }
        }
        return first;
    }

    /** Tells if an {@code "AUTHORITY"} element is allowed for the specified object. */
    private static boolean authorityAllowed(final IdentifiedObject info) {
        for (Class<? extends IdentifiedObject> aClass : AUTHORITY_EXCLUDE) {
            if (aClass.isInstance(info)) {
                return false;
            }
        }
        return true;
    }
}
