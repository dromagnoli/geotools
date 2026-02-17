/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2026, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geotools.imageio.netcdf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import org.geotools.coverage.io.CoverageSource.DomainType;
import org.geotools.imageio.netcdf.VariableAdapter.UnidataAdditionalDomain;
import org.geotools.imageio.netcdf.VariableAdapter.UnidataVerticalDomain;
import org.geotools.imageio.netcdf.cv.CoordinateVariable;

/**
 * Dimension indexes for slice resolution.
 *
 * <p>Use regular-axis math when the coordinate variable is regular, and fall back to a compact array-backed lookup
 * otherwise. The implemented indexes/lookup allow to return a dimension's index from the requested domain's value/range
 */
public final class NetCDFDimensionIndexes {

    public interface AxisLookup {
        int size();
    }

    NetCDFDimensionIndexes() {}

    /**
     * Lookup interface for temporal axes (e.g. TIME dimension).
     *
     * <p>Provides resolution from a temporal value to its corresponding axis index, supporting both exact matches and
     * range queries. Implementations may be backed by regular (start + increment) or irregular coordinate variables.
     *
     * <p>All lookup methods assume the underlying axis values are ordered.
     */
    public interface TimeAxisLookup extends AxisLookup {

        /** Returns the index of the axis element exactly matching the provided value. */
        int exact(Date value);

        /** Returns the index of the first axis value greater than (or equal to) the provided value. */
        int firstIndexAfter(Date value, boolean inclusive);

        /** Returns the index of the last axis value less than (or equal to) the provided value. */
        int lastIndexBefore(Date value, boolean inclusive);

        /** Returns the temporal value at the specified axis index. */
        Date valueAt(int index);
    }

    /**
     * Lookup interface for numeric axes (e.g. elevation or additional numeric dimensions).
     *
     * <p>Provides resolution from a numeric value to its corresponding axis index, supporting both exact matches and
     * range queries. Implementations may be backed by regular (start + increment) or irregular coordinate variables.
     *
     * <p>All lookup methods assume the underlying axis values are ordered.
     */
    public interface NumericAxisLookup extends AxisLookup {

        /** Returns the index of the axis element exactly matching the provided value. */
        int exact(double value);

        /** Returns the index of the first axis value greater than (or equal to) the provided value. */
        int firstIndexAfter(double value, boolean inclusive);

        /** Returns the index of the last axis value less than (or equal to) the provided value. */
        int lastIndexBefore(double value, boolean inclusive);

        /** Returns the numeric value at the specified axis index. */
        double valueAt(int index);
    }

    /** Factory methods to create temporal indexes from coordinate variables. */
    public static TimeAxisLookup forTime(CoordinateVariable<Date> cv) throws IOException {
        if (cv.isRegular()) {
            long start = (cv.getMinimum()).getTime();
            int size = (int) cv.getSize();
            long step = computeRegularLongStep(start, (cv.getMaximum()).getTime(), size);
            if (step != Long.MIN_VALUE) {
                return new RegularTimeAxisLookup(size, start, step);
            }
        }
        @SuppressWarnings("unchecked")
        List<Date> dates = cv.read();
        long[] values = new long[dates.size()];
        for (int i = 0; i < dates.size(); i++) {
            values[i] = dates.get(i).getTime();
        }
        return new ArrayTimeAxisLookup(values);
    }

    /** Factory method to create numeric indexes from vertical coordinate variables. */
    public static NumericAxisLookup forVertical(UnidataVerticalDomain domain) throws IOException {
        CoordinateVariable<? extends Number> cv = domain.adaptee;
        if (cv.isRegular()) {
            return new RegularNumericAxisLookup((int) cv.getSize(), cv.getStart(), cv.getIncrement());
        }
        @SuppressWarnings("unchecked")
        List<Number> numbers = (List<Number>) cv.read();
        double[] values = new double[numbers.size()];
        for (int i = 0; i < numbers.size(); i++) {
            values[i] = numbers.get(i).doubleValue();
        }
        return new ArrayNumericAxisLookup(values);
    }

    /** Factory method to create numeric indexes from additional coordinate variables. */
    public static NumericAxisLookup forNumericAdditional(UnidataAdditionalDomain domain) throws IOException {
        CoordinateVariable<? extends Number> cv = getCoordinateVariable(domain);
        if (cv.isRegular()) {
            int size = (int) cv.getSize();
            double start = size == 1 ? cv.getMinimum().doubleValue() : cv.getStart();
            double increment = size == 1 ? 0.0 : cv.getIncrement();
            return new RegularNumericAxisLookup(size, start, increment);
        }
        @SuppressWarnings("unchecked")
        List<Number> numbers = (List<Number>) cv.read();
        double[] values = new double[numbers.size()];
        for (int i = 0; i < numbers.size(); i++) {
            values[i] = numbers.get(i).doubleValue();
        }
        return new ArrayNumericAxisLookup(values);
    }

    private static CoordinateVariable<? extends Number> getCoordinateVariable(UnidataAdditionalDomain domain) {
        if (domain.getType() != DomainType.NUMBER) {
            throw new IllegalArgumentException("Domain is not numeric: " + domain.getName());
        }
        CoordinateVariable<?> raw = domain.adaptee;
        if (!(raw.getType() != null && Number.class.isAssignableFrom(raw.getType()))) {
            throw new IllegalArgumentException("Coordinate variable is not numeric: " + domain.getName());
        }
        @SuppressWarnings("unchecked")
        CoordinateVariable<? extends Number> cv = (CoordinateVariable<? extends Number>) raw;
        return cv;
    }

    /** Factory method to create temporal indexes from additional coordinate variables. */
    public static TimeAxisLookup forDateAdditional(UnidataAdditionalDomain domain) throws IOException {
        if (domain.getType() != DomainType.DATE) {
            throw new IllegalArgumentException("Domain is not date: " + domain.getName());
        }
        CoordinateVariable<?> raw = domain.adaptee;
        if (!(raw.getType() != null && Date.class.isAssignableFrom(raw.getType()))) {
            throw new IllegalArgumentException("Coordinate variable is not date: " + domain.getName());
        }
        @SuppressWarnings("unchecked")
        CoordinateVariable<Date> cv = (CoordinateVariable<Date>) raw;
        return forTime(cv);
    }

    private static long computeRegularLongStep(long start, long end, int size) {
        if (size <= 1) {
            return 0L;
        }
        long delta = end - start;
        int intervals = size - 1;
        if (delta % intervals == 0) {
            return delta / intervals;
        }
        return Long.MIN_VALUE;
    }

    public static int[] contiguousRange(int startInclusive, int endExclusive) {
        if (endExclusive <= startInclusive) {
            return new int[0];
        }
        int[] out = new int[endExclusive - startInclusive];
        for (int i = 0; i < out.length; i++) {
            out[i] = startInclusive + i;
        }
        return out;
    }

    /** Regular time axis index, supporting exact match and lower/upper bound queries. */
    public static final class RegularTimeAxisLookup implements TimeAxisLookup {
        private final int size;
        private final long start;
        private final long step;

        public RegularTimeAxisLookup(int size, long start, long step) {
            this.size = size;
            this.start = start;
            this.step = step;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public int exact(Date value) {
            return exactMillis(value.getTime());
        }

        @Override
        public int firstIndexAfter(Date value, boolean inclusive) {
            return firstIndexAfterMillis(value.getTime(), inclusive);
        }

        @Override
        public int lastIndexBefore(Date value, boolean inclusive) {
            return lastIndexBeforeMillis(value.getTime(), inclusive);
        }

        @Override
        public Date valueAt(int index) {
            return new Date(start + index * step);
        }

        private int exactMillis(long millis) {
            if (size == 0) return -1;
            if (step == 0L) {
                return millis == start ? 0 : -1;
            }
            long delta = millis - start;
            if (delta % step != 0) {
                return -1;
            }
            long idx = delta / step;
            return idx >= 0 && idx < size ? (int) idx : -1;
        }

        private int firstIndexAfterMillis(long millis, boolean inclusive) {
            if (size == 0) return 0;
            if (step == 0L) {
                if (inclusive ? millis <= start : millis < start) return 0;
                return size;
            }
            if (step > 0) {
                return findBoundaryIncreasing(millis, !inclusive);
            }
            return findBoundaryDecreasing(millis, !inclusive);
        }

        private int lastIndexBeforeMillis(long millis, boolean inclusive) {
            if (size == 0) return 0;
            if (step == 0L) {
                if (inclusive ? millis < start : millis <= start) return 0;
                return size;
            }
            if (step > 0) {
                return findBoundaryIncreasing(millis, inclusive);
            }
            return findBoundaryDecreasing(millis, inclusive);
        }

        private int findBoundaryIncreasing(long millis, boolean movePastEqual) {
            double raw = (millis - start) / (double) step;
            int idx = movePastEqual ? (int) Math.floor(raw) + 1 : (int) Math.ceil(raw);
            return clamp(idx, size);
        }

        private int findBoundaryDecreasing(long millis, boolean moveRightOnEqual) {
            int lo = 0, hi = size;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                long v = start + mid * step;
                boolean takeRight = moveRightOnEqual ? v >= millis : v > millis;
                if (takeRight) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }
    }

    /** Array-backed time index, supporting exact match and lower/upper bound queries. */
    public static final class ArrayTimeAxisLookup implements TimeAxisLookup {
        private final long[] values;

        public ArrayTimeAxisLookup(long[] values) {
            this.values = values.clone();
        }

        @Override
        public int size() {
            return values.length;
        }

        @Override
        public int exact(Date value) {
            int idx = Arrays.binarySearch(values, value.getTime());
            return idx >= 0 ? idx : -1;
        }

        @Override
        public int firstIndexAfter(Date value, boolean inclusive) {
            return NetCDFDimensionIndexes.firstIndexAfter(values, value.getTime(), inclusive);
        }

        @Override
        public int lastIndexBefore(Date value, boolean inclusive) {
            return NetCDFDimensionIndexes.lastIndexBefore(values, value.getTime(), inclusive);
        }

        @Override
        public Date valueAt(int index) {
            return new Date(values[index]);
        }
    }

    /** Regular numeric axis index, supporting exact match and lower/upper bound queries. */
    public static final class RegularNumericAxisLookup implements NumericAxisLookup {
        private final int size;
        private final double start;
        private final double step;
        private static final double EPS = 1E-9;

        public RegularNumericAxisLookup(int size, double start, double step) {
            this.size = size;
            this.start = start;
            this.step = step;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public int exact(double value) {
            if (size == 0) return -1;
            if (Math.abs(step) < EPS) {
                return Math.abs(value - start) <= EPS ? 0 : -1;
            }
            double raw = (value - start) / step;
            long rounded = Math.round(raw);
            if (Math.abs(raw - rounded) > EPS) {
                return -1;
            }
            return rounded >= 0 && rounded < size ? (int) rounded : -1;
        }

        @Override
        public int firstIndexAfter(double value, boolean inclusive) {
            if (size == 0) return 0;
            if (step == 0.0) {
                if (inclusive ? value <= start : value < start) return 0;
                return size;
            }
            if (step > 0) {
                return findBoundaryIncreasing(value, !inclusive);
            }
            return findBoundaryDecreasing(value, !inclusive);
        }

        @Override
        public int lastIndexBefore(double value, boolean inclusive) {
            if (size == 0) return 0;
            if (step == 0.0) {
                if (inclusive ? value < start : value <= start) return 0;
                return size;
            }
            if (step > 0) {
                return findBoundaryIncreasing(value, inclusive);
            }
            return findBoundaryDecreasing(value, inclusive);
        }

        @Override
        public double valueAt(int index) {
            return start + index * step;
        }

        private int findBoundaryIncreasing(double value, boolean movePastEqual) {
            double raw = (value - start) / step;
            int idx = movePastEqual ? (int) Math.floor(raw + EPS) + 1 : (int) Math.ceil(raw - EPS);
            return clamp(idx, size);
        }

        private int findBoundaryDecreasing(double value, boolean moveRightOnEqual) {
            int lo = 0, hi = size;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                double v = valueAt(mid);
                boolean takeRight = moveRightOnEqual ? v >= value : v > value;
                if (takeRight) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }
    }

    /** Array-backed numeric index, supporting exact match and lower/upper bound queries. */
    public static final class ArrayNumericAxisLookup implements NumericAxisLookup {
        private final double[] values;
        private static final double EPS = 1E-9;

        public ArrayNumericAxisLookup(double[] values) {
            this.values = values.clone();
        }

        @Override
        public int size() {
            return values.length;
        }

        @Override
        public int exact(double value) {
            int idx = Arrays.binarySearch(values, value);
            if (idx >= 0) return idx;
            int insertion = -idx - 1;
            if (insertion < values.length && Math.abs(values[insertion] - value) <= EPS) return insertion;
            if (insertion > 0 && Math.abs(values[insertion - 1] - value) <= EPS) return insertion - 1;
            return -1;
        }

        @Override
        public int firstIndexAfter(double value, boolean inclusive) {
            return NetCDFDimensionIndexes.firstIndexAfter(values, value, inclusive);
        }

        @Override
        public int lastIndexBefore(double value, boolean inclusive) {
            return NetCDFDimensionIndexes.lastIndexBefore(values, value, inclusive);
        }

        @Override
        public double valueAt(int index) {
            return values[index];
        }
    }

    public static int firstIndexAfter(long[] values, long target, boolean inclusive) {
        int lo = 0, hi = values.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (inclusive ? values[mid] < target : values[mid] <= target) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    public static int lastIndexBefore(long[] values, long target, boolean inclusive) {
        int lo = 0, hi = values.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (inclusive ? values[mid] <= target : values[mid] < target) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    public static int firstIndexAfter(double[] values, double target, boolean inclusive) {
        final double eps = 1E-9;
        int lo = 0, hi = values.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (inclusive ? values[mid] < target - eps : values[mid] <= target + eps) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    public static int lastIndexBefore(double[] values, double target, boolean inclusive) {
        final double eps = 1E-9;
        int lo = 0, hi = values.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (inclusive ? values[mid] <= target + eps : values[mid] < target - eps) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }

    /** Tiny per-variable context holding Dimensions lookup */
    public static final class DimensionIndexesContext {
        private TimeAxisLookup time;
        private NumericAxisLookup elevation;
        private final List<AxisLookup> additional = new ArrayList<>();

        public TimeAxisLookup getTime() {
            return time;
        }

        public void setTime(TimeAxisLookup time) {
            this.time = time;
        }

        public NumericAxisLookup getElevation() {
            return elevation;
        }

        public void setElevation(NumericAxisLookup elevation) {
            this.elevation = elevation;
        }

        public List<AxisLookup> getAdditional() {
            return additional;
        }

        public void addAdditional(AxisLookup index) {
            additional.add(index);
        }
    }
}
