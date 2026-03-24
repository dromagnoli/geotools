/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2007-2016, Open Source Geospatial Foundation (OSGeo)
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
package org.geotools.coverage.io.catalog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.geotools.api.data.Query;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.geometry.BoundingBox;
import org.geotools.feature.visitor.FeatureCalc;
import org.geotools.geometry.jts.ReferencedEnvelope;

/**
 * DB-free implementation of {@code CoverageSlicesCatalog}.
 *
 * <p>Historically this class relied on an underlying {@code DataStore} (H2/PostGIS/...) to persist and query slice
 * metadata (time/elevation/other dimensions) and the corresponding {@code imageIndex}.
 *
 * <p>This version removes any dependency on persistent DB-backed stores and it delegates query planning and slice
 * iteration to a {@link SliceProvider}. That can compute matches on-the-fly (e.g. using a deterministic Var/T/Z →
 * imageIndex mapping).
 *
 * <p>The {@link #getGranules(Query)} method is retained only for compatibility and is implemented as a materializing
 * wrapper around {@link #iterateGranules(Query)}.
 */
public class CoverageSlicesCatalog {

    /** Logger. */
    static final Logger LOGGER = org.geotools.util.logging.Logging.getLogger(CoverageSlicesCatalog.class);

    public static final String IMAGE_INDEX_ATTR = "imageindex";

    /**
     * An iterator over {@link CoverageSlice}s that must be closed to release any underlying resources (feature
     * iterators, transactions, locks).
     */
    public interface SliceIterator extends Iterator<CoverageSlice>, AutoCloseable {
        @Override
        void close();
    }

    /**
     * Resolver that can translate GeoTools {@link Query} objects into a stream of matching slices.
     *
     * <p>Implementations are expected to:
     *
     * <ul>
     *   <li>Recognize TIME/ELEVATION (and other dimension) constraints in {@code Query#getFilter()}
     *   <li>Compute the matching tuples and their {@code imageIndex} without materializing all slices
     *   <li>Build {@link CoverageSlice} instances (or at least their originator {@link SimpleFeature}) lazily
     * </ul>
     */
    public interface SliceProvider {
        SliceIterator iterate(Query query) throws IOException;

        /**
         * Optional fast-path for counts. Implementations may return {@code -1} to indicate that the catalog should
         * fallback to counting by iteration.
         */
        default int count(Query query) throws IOException {
            return -1;
        }

        /**
         * Optional fast-path for bounds. Implementations may return {@code null} to indicate that the catalog should
         * fallback to computing bounds by iteration.
         */
        default ReferencedEnvelope bounds(Query query) throws IOException {
            return null;
        }
    }

    /** Filter on a single dimension of the coverage. */
    public interface DimensionFilter {
        DimensionFilter ALL = new DimensionFilter() {
            @Override
            public DimensionFilter and(DimensionFilter other) {
                return other == null ? this : other;
            }
        };

        default DimensionFilter and(DimensionFilter other) {
            if (other == null || other == ALL) return this;
            if (this == ALL) return other;
            return new CompositeDimensionFilter(this, other);
        }
    }

    /**
     * A composite filter that combines two filters with logical AND. Used to merge multiple recognized filters on the
     * same dimension (e.g. from multiple OR-ed equality conditions) into a single filter.
     */
    public static final class CompositeDimensionFilter implements DimensionFilter {
        private final DimensionFilter left;
        private final DimensionFilter right;

        CompositeDimensionFilter(DimensionFilter left, DimensionFilter right) {
            this.left = left;
            this.right = right;
        }

        public DimensionFilter getLeft() {
            return left;
        }

        public DimensionFilter getRight() {
            return right;
        }
    }

    /** A filter that matches if the coordinate value on the given dimension is equal to the expected value. */
    public static final class ExactDimensionFilter implements DimensionFilter {
        private final Object expected;

        public ExactDimensionFilter(Object expected) {
            this.expected = expected;
        }

        public Object getExpected() {
            return expected;
        }
    }

    /**
     * A filter that matches if the coordinate value on the given dimension is contained in the expected list. Used to
     * represent OR-ed equality conditions on dimensions (e.g. time = v1 OR time = v2 OR ...)
     */
    public static final class ListFilter implements DimensionFilter {
        private final Set<Object> values;

        public ListFilter(List<Object> values) {
            this.values = new HashSet<>();
            this.values.addAll(values);
        }

        public Set<Object> getValues() {
            return values;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    /** A filter that matches if the coordinate value on the given dimension is contained in the expected range. */
    public static final class RangeFilter implements DimensionFilter {
        private final Comparable lower;
        private final boolean lowerInclusive;
        private final Comparable upper;
        private final boolean upperInclusive;

        public RangeFilter(Object lower, boolean lowerInclusive, Object upper, boolean upperInclusive) {
            this.lower = (Comparable) normalizeComparable(lower);
            this.lowerInclusive = lowerInclusive;
            this.upper = (Comparable) normalizeComparable(upper);
            this.upperInclusive = upperInclusive;
        }

        private Object normalizeComparable(Object value) {
            if (value instanceof Date) {
                return ((Date) value).getTime();
            }
            return value;
        }

        public Comparable getLower() {
            return lower;
        }

        public Comparable getUpper() {
            return upper;
        }

        public boolean isLowerInclusive() {
            return lowerInclusive;
        }

        public boolean isUpperInclusive() {
            return upperInclusive;
        }
    }

    /**
     * A CoverageContext holds the typeName of a coverage, the underlying schema and a SliceProvider instance to extract
     * CoverageSlices.
     */
    public static final class CoverageContext {
        private final String typeName;
        private final SimpleFeatureType schema;
        private SliceProvider sliceProvider;

        public CoverageContext(SimpleFeatureType schema) {
            this.schema = Objects.requireNonNull(schema, "schema");
            this.typeName = schema.getTypeName();
        }

        public void setSliceProvider(SliceProvider sliceProvider) {
            this.sliceProvider = sliceProvider;
        }

        public String getTypeName() {
            return typeName;
        }

        public SimpleFeatureType getSchema() {
            return schema;
        }

        public SliceProvider getSliceProvider() {
            return sliceProvider;
        }
    }

    private final Map<String, CoverageContext> contextsByTypeName = new LinkedHashMap<>();

    public CoverageSlicesCatalog() {}

    public String[] getTypeNames() {
        return contextsByTypeName.keySet().toArray(new String[0]);
    }

    public SimpleFeatureType getSchema(String requestedTypeName) throws IOException {
        CoverageContext context = context(requestedTypeName);
        return context != null ? context.getSchema() : null;
    }

    public void dispose() {
        for (CoverageContext context : contextsByTypeName.values()) {
            Object sliceProvider = context.getSliceProvider();
            if (sliceProvider instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) sliceProvider).close();
                } catch (Exception e) {
                    if (LOGGER.isLoggable(Level.INFO)) {
                        LOGGER.log(
                                Level.INFO,
                                "Error closing sliceProvider for typeName '" + context.getTypeName() + "'",
                                e);
                    }
                }
            }
        }
    }

    private CoverageContext context(String typeName) {
        return contextsByTypeName.get(typeName);
    }

    /**
     * Streams matching {@link CoverageSlice}s without materializing them into a list.
     *
     * <p>This is the preferred entry point for callers that want to avoid eagerly loading all slices. The returned
     * iterator must be closed to release resources.
     */
    public CoverageSlicesCatalog.SliceIterator iterateGranules(final Query q) throws IOException {
        Query query = prepareQuery(q);
        return context(requiredTypeName(query)).getSliceProvider().iterate(query);
    }
    /**
     * Returns the number of granules matching the provided query without loading them all. Falls back to iteration if
     * the underlying store cannot compute counts.
     */
    public int getCount(final Query q) throws IOException {
        Query query = prepareQuery(q);
        SliceProvider provider = context(requiredTypeName(query)).getSliceProvider();

        int fast = provider.count(query);
        if (fast >= 0) {
            return fast;
        }

        int count = 0;
        try (SliceIterator it = provider.iterate(query)) {
            while (it.hasNext()) {
                it.next();
                count++;
            }
        }
        return count;
    }

    /**
     * Returns bounds for granules matching the provided query without loading them all. Falls back to
     * feature-collection bounds if the underlying store can't compute it directly.
     */
    public ReferencedEnvelope getBounds(final Query q) throws IOException {
        Query query = prepareQuery(q);
        SliceProvider provider = context(requiredTypeName(query)).getSliceProvider();

        ReferencedEnvelope fast = provider.bounds(query);
        if (fast != null) {
            return fast;
        }

        ReferencedEnvelope envelope = null;
        try (SliceIterator it = provider.iterate(query)) {
            while (it.hasNext()) {
                CoverageSlice slice = it.next();
                SimpleFeature feature = slice.getOriginator();
                if (feature == null) {
                    continue;
                }
                BoundingBox bbox = feature.getBounds();
                if (bbox == null) {
                    continue;
                }
                ReferencedEnvelope bounds = new ReferencedEnvelope(bbox);
                if (envelope == null) {
                    envelope = bounds;
                } else {
                    envelope.expandToInclude(bounds);
                }
            }
        }
        return envelope;
    }

    private Query prepareQuery(Query q) throws IOException {
        Query query = (q == null) ? null : new Query(q);

        if (query == null || query.getTypeName() == null || query.getTypeName().isEmpty()) {
            if (contextsByTypeName.size() == 1) {
                String onlyTypeName = contextsByTypeName.keySet().iterator().next();
                return new Query(onlyTypeName);
            }
            throw new IOException(
                    "Query typename is required for multi-typename CoverageSlicesCatalog. Known typenames: "
                            + contextsByTypeName.keySet());
        }
        return query;
    }

    private String requiredTypeName(Query query) throws IOException {
        String typeName = query.getTypeName();
        if (typeName == null || typeName.isEmpty()) {
            throw new IOException("Missing typename in query");
        }
        return typeName;
    }
    // ---------------------------------------------------------------------
    // Compatibility API (materializing) implemented on top of the streaming API.
    // ---------------------------------------------------------------------

    public List<CoverageSlice> getGranules(final Query q) throws IOException {
        List<CoverageSlice> out = new ArrayList<>();
        try (SliceIterator it = iterateGranules(q)) {
            while (it.hasNext()) {
                out.add(it.next());
            }
        }
        return out;
    }

    public void computeAggregateFunction(Query query, FeatureCalc function) throws IOException {
        try (SliceIterator it = iterateGranules(query)) {
            while (it.hasNext()) {
                CoverageSlice slice = it.next();
                SimpleFeature feature = slice.getOriginator();
                function.visit(feature);
            }
        }
    }

    public void registerContext(CoverageContext context) {
        contextsByTypeName.put(context.getTypeName(), context);
    }
}
