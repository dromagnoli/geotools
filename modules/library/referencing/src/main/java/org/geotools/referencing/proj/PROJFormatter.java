package org.geotools.referencing.proj;

import java.lang.reflect.Array;
import java.text.FieldPosition;
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.util.Collection;
import javax.measure.Unit;
import javax.measure.quantity.Angle;
import javax.measure.quantity.Length;
import org.geotools.api.metadata.Identifier;
import org.geotools.api.metadata.citation.Citation;
import org.geotools.api.parameter.GeneralParameterValue;
import org.geotools.api.parameter.ParameterDescriptor;
import org.geotools.api.parameter.ParameterValue;
import org.geotools.api.parameter.ParameterValueGroup;
import org.geotools.api.referencing.IdentifiedObject;
import org.geotools.api.referencing.cs.CoordinateSystemAxis;
import org.geotools.api.referencing.datum.Datum;
import org.geotools.api.referencing.datum.Ellipsoid;
import org.geotools.api.util.GenericName;
import org.geotools.measure.UnitFormatter;
import org.geotools.metadata.i18n.ErrorKeys;
import org.geotools.metadata.iso.citation.Citations;
import org.geotools.metadata.math.XMath;
import org.geotools.referencing.CRS;
import org.geotools.referencing.wkt.EpsgUnitFormat;
import org.geotools.referencing.wkt.EsriUnitFormat;
import org.geotools.referencing.wkt.Symbols;
import si.uom.SI;
import tech.units.indriya.AbstractUnit;

public class PROJFormatter {

    private final Symbols symbols;
    private StringBuffer buffer;

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

    private final FieldPosition dummy = new FieldPosition(0);

    public PROJFormatter() {
        this(Symbols.DEFAULT);
    }

    private String proj = null;

    private boolean projectedCRS = false;

    private boolean datumProvided = false;

    private boolean ellipsoidProvided = false;

    /**
     * Creates a new instance of the formatter. The whole WKT will be formatted on a single line.
     *
     * @param symbols The symbols.
     */
    public PROJFormatter(final Symbols symbols) {
        this.symbols = symbols;
        if (symbols == null) {
            throw new IllegalArgumentException(
                    MessageFormat.format(ErrorKeys.NULL_ARGUMENT_$1, "symbols"));
        }
        numberFormat = (NumberFormat) symbols.numberFormat.clone();
        buffer = new StringBuffer();
    }

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

    /**
     * Non-null if the formatting is invalid. If non-null, then this field contains the interface
     * class of the problematic part (e.g. {@link org.geotools.api.referencing.crs.EngineeringCRS}).
     */
    //   private Class<?> unformattable;

    /** Warning that may be produced during formatting, or {@code null} if none. */
    String warning;

    public boolean isProjectedCRS() {
        return projectedCRS;
    }

    public void setProjectedCRS(boolean projectedCRS) {
        this.projectedCRS = projectedCRS;
    }

    public boolean isDatumProvided() {
        return datumProvided;
    }

    public void setDatumProvided(boolean datumProvided) {
        this.datumProvided = datumProvided;
    }

    public boolean isEllipsoidProvided() {
        return ellipsoidProvided;
    }

    public void setEllipsoidProvided(boolean ellipsoidProvided) {
        this.ellipsoidProvided = ellipsoidProvided;
    }

    public String toPROJ(IdentifiedObject identifiedObject) {
        if (identifiedObject instanceof org.geotools.referencing.util.PROJFormattable) {
            org.geotools.referencing.util.PROJFormattable formattable =
                    ((org.geotools.referencing.util.PROJFormattable) identifiedObject);
            formattable.formatPROJ(this);
            return this.toString();
        }
        return "";
    }

    public void append(final org.geotools.referencing.util.PROJFormattable formattable) {

        int base = buffer.length();
        final IdentifiedObject info =
                (formattable instanceof IdentifiedObject) ? (IdentifiedObject) formattable : null;
        if (info != null) {
            buffer.append(getName(info)).append(" ");
        }

        String keyword = formattable.formatPROJ(this);
        buffer.insert(base, keyword);
        /*final Identifier identifier = getIdentifier(info);
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
                    buffer.append(title);
                    final String code = identifier.getCode();
                    if (code != null) {
                        buffer.append(code);
                    }
                }
            }
        }*/
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

            buffer.append("+" + getName(descriptor));
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
                buffer.append("=");
                format(value);
                buffer.append(" ");
            } else {
                buffer.append("=");
                appendObject(param.getValue());
                buffer.append(" ");
            }
        }
    }

    public void append(final Unit<?> unit) {
        if (unit != null) {
            //            try {
            buffer.append("+units=");
            String name = remapUnit(unit);
            buffer.append(name);
            /*Unit<?> base = null;
            if (SI.METRE.isCompatible(unit)) {
                base = SI.METRE;
            } else if (SI.SECOND.isCompatible(unit)) {
                base = SI.SECOND;
            } else if (SI.RADIAN.isCompatible(unit)) {
                if (!AbstractUnit.ONE.equals(unit)) {
                    base = SI.RADIAN;
                }
            }
            if (base != null) {
                append(unit.getConverterToAny(base).convert(1));
            }*/
            /*           } catch (IOException | UnconvertibleException e) {
                          throw new IllegalArgumentException("The provided unit is not compatible", e);
                      }
            */ }
    }

    private String remapUnit(Unit<?> unit) {
        String symbol = unit.getSymbol();
        if (symbol == null) {
            symbol = unit.toString();
        }
        if ("ft_survey_us".equalsIgnoreCase(symbol)) {
            symbol = "us-ft";
        }
        return symbol;
    }

    /**
     * Append the specified value to a string builder. If the value is an array, then the array
     * elements are appended recursively (i.e. the array may contains sub-array).
     */
    private void appendObject(final Object value) {
        if (value == null) {
            buffer.append("null");
            return;
        }
        if (value.getClass().isArray()) {
            buffer.append('{');
            final int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (i != 0) {
                    buffer.append(',').append(' ');
                }
                appendObject(Array.get(value, i));
            }
            buffer.append('}');
            return;
        }
        if (value instanceof Number) {
            format((Number) value);
        } else {
            buffer.append('"').append(value).append('"');
        }
    }

    public void append(String value) {
        buffer.append(value);
    }

    public void append(final int number) {
        format(number);
    }

    public void append(final double number) {
        format(number);
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
        if (Citations.PROJ != name.getAuthority()) {
            final Collection<GenericName> aliases = info.getAlias();
            if (aliases != null && !aliases.isEmpty()) {
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
                            if (info instanceof Datum) {
                                setDatumProvided(true);
                            } else if (info instanceof Ellipsoid) {
                                setEllipsoidProvided(true);
                            }
                            return alias.tip().toString();
                        }
                    }
                }
            }
        }
        return "";
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

    public void clear() {
        if (buffer != null) {
            buffer.setLength(0);
        }
        linearUnit = null;
        angularUnit = null;
        warning = null;
        datumProvided = false;
        ellipsoidProvided = false;
        projectedCRS = false;
    }

    /**
     * The linear unit for formatting measures, or {@code null} for the "natural" unit of each WKT
     * element.
     *
     * @return The unit for measure. Default value is {@code null}.
     */
    public Unit<Length> getLinearUnit() {
        return linearUnit;
    }

    /**
     * Set the unit for formatting linear measures.
     *
     * @param unit The new unit, or {@code null}.
     */
    public void setLinearUnit(final Unit<Length> unit) {
        if (unit != null && !SI.METRE.isCompatible(unit)) {
            throw new IllegalArgumentException(
                    MessageFormat.format(ErrorKeys.NON_LINEAR_UNIT_$1, unit));
        }
        linearUnit = unit;
    }

    /**
     * The angular unit for formatting measures, or {@code null} for the "natural" unit of each WKT
     * element. This value is set for example by "GEOGCS", which force its enclosing "PRIMEM" to
     * take the same units than itself.
     *
     * @return The unit for measure. Default value is {@code null}.
     */
    public Unit<Angle> getAngularUnit() {
        return angularUnit;
    }

    /**
     * Set the angular unit for formatting measures.
     *
     * @param unit The new unit, or {@code null}.
     */
    public void setAngularUnit(final Unit<Angle> unit) {
        if (unit != null && (!SI.RADIAN.isCompatible(unit) || AbstractUnit.ONE.equals(unit))) {
            throw new IllegalArgumentException(
                    MessageFormat.format(ErrorKeys.NON_ANGULAR_UNIT_$1, unit));
        }
        angularUnit = unit;
    }

    /** Format an arbitrary number. */
    private void format(final Number number) {
        if (number instanceof Byte || number instanceof Short || number instanceof Integer) {
            format(number.intValue());
        } else {
            format(number.doubleValue());
        }
    }

    /** Formats an integer number. */
    private void format(final int number) {
        final int fraction = numberFormat.getMinimumFractionDigits();
        numberFormat.setMinimumFractionDigits(0);
        numberFormat.format(number, buffer, dummy);
        numberFormat.setMinimumFractionDigits(fraction);
    }

    /** Formats a floating point number. */
    private void format(final double number) {
        numberFormat.format(number, buffer, dummy);
    }

    public String toString() {
        return buffer.toString().replaceAll("\\s+", " ");
    }
}
