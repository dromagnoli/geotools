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
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import org.geotools.api.data.Query;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.filter.And;
import org.geotools.api.filter.ExcludeFilter;
import org.geotools.api.filter.Filter;
import org.geotools.api.filter.FilterFactory;
import org.geotools.api.filter.IncludeFilter;
import org.geotools.api.filter.Or;
import org.geotools.api.filter.PropertyIsBetween;
import org.geotools.api.filter.PropertyIsEqualTo;
import org.geotools.api.filter.PropertyIsGreaterThan;
import org.geotools.api.filter.PropertyIsGreaterThanOrEqualTo;
import org.geotools.api.filter.PropertyIsLessThan;
import org.geotools.api.filter.PropertyIsLessThanOrEqualTo;
import org.geotools.api.filter.expression.Expression;
import org.geotools.api.filter.expression.Literal;
import org.geotools.api.filter.expression.PropertyName;
import org.geotools.coverage.io.CoverageSource.AdditionalDomain;
import org.geotools.coverage.io.CoverageSource.DomainType;
import org.geotools.coverage.io.catalog.CoverageSlice;
import org.geotools.coverage.io.catalog.CoverageSlicesCatalog;
import org.geotools.coverage.io.catalog.CoverageSlicesCatalog.CompositeDimensionFilter;
import org.geotools.coverage.io.catalog.CoverageSlicesCatalog.DimensionFilter;
import org.geotools.coverage.io.catalog.CoverageSlicesCatalog.ExactDimensionFilter;
import org.geotools.coverage.io.catalog.CoverageSlicesCatalog.ListFilter;
import org.geotools.coverage.io.catalog.CoverageSlicesCatalog.RangeFilter;
import org.geotools.coverage.io.catalog.CoverageSlicesCatalog.SliceIterator;
import org.geotools.coverage.io.catalog.CoverageSlicesCatalog.SliceProvider;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.filter.visitor.DuplicatingFilterVisitor;
import org.geotools.filter.visitor.SimplifyingFilterVisitor;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.imageio.netcdf.NetCDFDimensionIndexes.DimensionIndexesContext;
import org.geotools.imageio.netcdf.NetCDFDimensionIndexes.NumericAxisLookup;
import org.geotools.imageio.netcdf.NetCDFDimensionIndexes.TimeAxisLookup;

/**
 * A {@link SliceProvider} implementation that selects slices from a NetCDF variable, based on the filters in the query.
 * It recognizes filters on time, elevation and additional dimensions, and uses them to efficiently select the relevant
 * slices from the variable, while applying any unrecognized filters as post-filters during iteration.
 */
public class VariableSliceProvider implements SliceProvider {

    private static final FilterFactory FF = CommonFactoryFinder.getFilterFactory(null);

    // The variable adapter to read the variable metadata and create features for the slices
    private final VariableAdapter adapter;

    // The schema of the features created for the slices
    private final SimpleFeatureType schema;

    // ImageIndex starts from 0 for the first variable in the NetCDF file,
    // so we need to know the offset of this variable to compute the global image index for the slices
    private final int variableImageStartIndex;
    private final ReferencedEnvelope bounds;
    private final String timeAttribute;
    private final String elevationAttribute;
    private final List<AdditionalDomainBinding> additionalBindings;

    private final DimensionIndexesContext dimensionsContext;

    public VariableSliceProvider(
            VariableAdapter adapter,
            SimpleFeatureType schema,
            int variableImageStartIndex,
            ReferencedEnvelope bounds,
            DimensionIndexesContext context) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.variableImageStartIndex = variableImageStartIndex;
        this.bounds = bounds;
        this.timeAttribute = adapter.getTimeAttributeName();
        this.elevationAttribute = adapter.getElevationAttributeName();
        this.additionalBindings = initAdditionalBindings(adapter);
        this.dimensionsContext = context;
    }

    @Override
    public CoverageSlicesCatalog.SliceIterator iterate(Query query) {
        final SlicesQuery slicesQuery =
                SlicesQuery.fromQuery(query, adapter, timeAttribute, elevationAttribute, additionalBindings);
        SliceIndexDomain slicesDomain = slicesQuery.toDomain(adapter, dimensionsContext, additionalBindings);
        return new SliceQueryIterator(slicesQuery, slicesDomain);
    }

    @Override
    public int count(Query query) {
        SlicesQuery slicesQuery =
                SlicesQuery.fromQuery(query, adapter, timeAttribute, elevationAttribute, additionalBindings);
        SliceIndexDomain slicesDomain = slicesQuery.toDomain(adapter, dimensionsContext, additionalBindings);
        if (!slicesQuery.hasPostFilter()) {
            long size = slicesDomain.size();
            return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
        }
        int count = 0;
        try (CoverageSlicesCatalog.SliceIterator it = new SliceQueryIterator(slicesQuery, slicesDomain)) {
            while (it.hasNext()) {
                it.next();
                count++;
            }
        }
        return count;
    }

    @Override
    public ReferencedEnvelope bounds(Query query) throws IOException {
        return bounds;
    }

    private final class SliceQueryIterator implements SliceIterator {
        private final SlicesQuery slicesQuery;
        private final SliceIndexDomain sliceIndexDomain;
        private final IndexTupleIterator tupleIterator;
        private CoverageSlice next;

        private SliceQueryIterator(SlicesQuery slicesQuery, SliceIndexDomain sliceIndexDomain) {
            this.slicesQuery = slicesQuery;
            this.sliceIndexDomain = sliceIndexDomain;
            this.tupleIterator = new IndexTupleIterator(sliceIndexDomain.selectedIndices);
        }

        @Override
        public boolean hasNext() {
            if (next != null) {
                return true;
            }
            while (tupleIterator.hasNext()) {
                int[] compactTuple = tupleIterator.next();
                int[] splitIndex = sliceIndexDomain.toSplitIndex(compactTuple);

                int localImageIndex = adapter.getLocalImageIndex(splitIndex);
                int globalImageIndex = variableImageStartIndex + localImageIndex;
                SimpleFeature feature = adapter.createFeatureForSplitIndex(splitIndex, globalImageIndex, schema);
                if (feature == null) {
                    continue;
                }
                if (slicesQuery.hasPostFilter() && !slicesQuery.postFilter.evaluate(feature)) {
                    continue;
                }
                next = new CoverageSlice(feature);
                return true;
            }
            return false;
        }

        @Override
        public CoverageSlice next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            final CoverageSlice result = next;
            next = null;
            return result;
        }

        @Override
        public void close() {}
    }

    private static List<AdditionalDomainBinding> initAdditionalBindings(VariableAdapter adapter) {
        final List<AdditionalDomainBinding> bindings = new ArrayList<>();
        final List<AdditionalDomain> additionalDomains = adapter.getAdditionalDomains();
        if (additionalDomains == null) {
            return bindings;
        }
        for (int i = 0; i < additionalDomains.size(); i++) {
            final AdditionalDomain domain = additionalDomains.get(i);
            final int logicalDimension = i + 2;
            final int actualDimension = adapter.getNDimensionIndex(logicalDimension);
            if (actualDimension < 0) {
                continue;
            }
            final String attr = adapter.getAdditionalDomainAttributeName(domain.getName());
            bindings.add(new AdditionalDomainBinding(domain.getName(), attr, logicalDimension, domain.getType()));
        }
        return bindings;
    }

    private static final class AdditionalDomainBinding {
        final String domainName;
        final String attributeName;
        final int logicalDimension;
        final DomainType type;

        AdditionalDomainBinding(String domainName, String attributeName, int logicalDimension, DomainType type) {
            this.domainName = domainName;
            this.attributeName = attributeName;
            this.logicalDimension = logicalDimension;
            this.type = type;
        }
    }

    /**
     * The preprocessed query for slices: recognized dimension filters plus postfilters (post filters will be applied in
     * memory).
     */
    static final class SlicesQuery {
        final DimensionFilter time;
        final DimensionFilter elevation;
        final List<AdditionalDimensionFilter> additional;
        final Filter postFilter;

        SlicesQuery(
                DimensionFilter time,
                DimensionFilter elevation,
                List<AdditionalDimensionFilter> additional,
                Filter postFilter) {
            this.time = time;
            this.elevation = elevation;
            this.additional = additional;
            this.postFilter = postFilter == null ? Filter.INCLUDE : postFilter;
        }

        /**
         * Preprocesses the original query to extract the filters on time, elevation and additional dimensions. The
         * extracted filters are converted to DimensionFilter for efficient matching during iteration
         */
        static SlicesQuery fromQuery(
                Query query,
                VariableAdapter adapter,
                String timeAttribute,
                String elevationAttribute,
                List<AdditionalDomainBinding> additionalBindings) {
            Filter raw = query != null && query.getFilter() != null ? query.getFilter() : Filter.INCLUDE;
            Filter simplified = simplify(raw);
            SplitResult split = splitFilter(simplified, adapter, timeAttribute, elevationAttribute, additionalBindings);
            return new SlicesQuery(split.time, split.elevation, split.additional, split.postFilter);
        }

        boolean hasPostFilter() {
            return postFilter != Filter.INCLUDE;
        }

        /**
         * Converts the recognized dimension filters into a SliceIndexDomain, which contains the logical dimensions to
         * filter and the selected indices for each dimension.
         */
        SliceIndexDomain toDomain(
                VariableAdapter adapter,
                DimensionIndexesContext context,
                List<AdditionalDomainBinding> additionalBindings) {

            List<Integer> logicalDimensions = new ArrayList<>();
            List<int[]> selected = new ArrayList<>();

            if (adapter.getNDimensionIndex(VariableAdapter.T) >= 0) {
                logicalDimensions.add(VariableAdapter.T);
                selected.add(resolveTimeFilter(time, context.getTime()));
            }

            if (adapter.getNDimensionIndex(VariableAdapter.Z) >= 0) {
                logicalDimensions.add(VariableAdapter.Z);
                selected.add(resolveNumericFilter(elevation, context.getElevation()));
            }

            for (AdditionalDomainBinding binding : additionalBindings) {
                if (adapter.getNDimensionIndex(binding.logicalDimension) < 0) {
                    continue;
                }

                DimensionFilter filter = DimensionFilter.ALL;
                for (AdditionalDimensionFilter adf : additional) {
                    if (adf.dimensionIndex == binding.logicalDimension) {
                        filter = adf.filter;
                        break;
                    }
                }

                logicalDimensions.add(binding.logicalDimension);
                selected.add(resolveAdditionalFilter(filter, binding, context));
            }

            int[] logicalArray =
                    logicalDimensions.stream().mapToInt(Integer::intValue).toArray();
            int[][] selectedArray = selected.toArray(new int[selected.size()][]);
            return new SliceIndexDomain(logicalArray, selectedArray);
        }

        private static int[] resolveTimeFilter(DimensionFilter filter, TimeAxisLookup axis) {
            if (axis == null || filter == null || filter == DimensionFilter.ALL) {
                return allIndices(axis);
            }
            return resolveTimeFilterInternal(filter, axis);
        }

        private static int[] resolveNumericFilter(DimensionFilter filter, NumericAxisLookup axis) {
            if (axis == null || filter == null || filter == DimensionFilter.ALL) {
                return allIndices(axis);
            }
            return resolveNumericFilterInternal(filter, axis);
        }

        private static int[] resolveAdditionalFilter(
                DimensionFilter filter, AdditionalDomainBinding binding, DimensionIndexesContext bundle) {

            NetCDFDimensionIndexes.AxisLookup axis = getAdditionalAxis(bundle, binding.logicalDimension);
            if (axis instanceof TimeAxisLookup timeAxis) {
                return resolveTimeFilter(filter, timeAxis);
            }
            if (axis instanceof NumericAxisLookup numericAxis) {
                return resolveNumericFilter(filter, numericAxis);
            }
            return allIndices(axis);
        }

        private static int[] resolveTimeFilterInternal(DimensionFilter filter, TimeAxisLookup axis) {
            if (filter == DimensionFilter.ALL) {
                return allIndices(axis);
            }
            if (filter instanceof CompositeDimensionFilter composite) {
                // optimize the filters like x <= A and x >= A
                Date exact = extractExactTime(composite);
                if (exact != null) {
                    int idx = axis.exact(exact);
                    return idx >= 0 ? new int[] {idx} : new int[0];
                }
                return intersect(
                        resolveTimeFilterInternal(composite.getLeft(), axis),
                        resolveTimeFilterInternal(composite.getRight(), axis));
            }
            if (filter instanceof ExactDimensionFilter exact) {
                Date value = toDate(exact.getExpected());
                if (value == null) {
                    return allIndices(axis);
                }
                int idx = axis.exact(value);
                return idx >= 0 ? new int[] {idx} : new int[0];
            }
            if (filter instanceof ListFilter list) {
                Set<Integer> indices = new HashSet<>();
                for (Object candidate : list.getValues()) {
                    Date value = toDate(candidate);
                    if (value == null) {
                        continue;
                    }
                    int idx = axis.exact(value);
                    if (idx >= 0) {
                        indices.add(idx);
                    }
                }
                return sorted(indices);
            }
            if (filter instanceof RangeFilter range) {
                Date lower = toDate(range.getLower());
                Date upper = toDate(range.getUpper());
                int start = lower == null ? 0 : axis.firstIndexAfter(lower, range.isLowerInclusive());
                int end = upper == null ? axis.size() : axis.lastIndexBefore(upper, range.isUpperInclusive());
                return NetCDFDimensionIndexes.contiguousRange(start, end);
            }
            return allIndices(axis);
        }

        private static int[] resolveNumericFilterInternal(DimensionFilter filter, NumericAxisLookup axis) {
            if (filter == DimensionFilter.ALL) {
                return allIndices(axis);
            }

            if (filter instanceof CompositeDimensionFilter composite) {
                // optimize the filters like x <= A and x >= A
                Double exact = extractExactNumeric(composite);
                if (exact != null) {
                    int idx = axis.exact(exact);
                    return idx >= 0 ? new int[] {idx} : new int[0];
                }

                return intersect(
                        resolveNumericFilterInternal(composite.getLeft(), axis),
                        resolveNumericFilterInternal(composite.getRight(), axis));
            }
            if (filter instanceof ExactDimensionFilter exact) {
                Double value = toDouble(exact.getExpected());
                if (value == null) {
                    return allIndices(axis);
                }
                int idx = axis.exact(value);
                return idx >= 0 ? new int[] {idx} : new int[0];
            }
            if (filter instanceof ListFilter list) {
                Set<Integer> indices = new HashSet<>();
                for (Object candidate : list.getValues()) {
                    Double value = toDouble(candidate);
                    if (value == null) {
                        continue;
                    }
                    int idx = axis.exact(value);
                    if (idx >= 0) {
                        indices.add(idx);
                    }
                }
                return sorted(indices);
            }
            if (filter instanceof RangeFilter range) {
                Double lower = toDouble(range.getLower());
                Double upper = toDouble(range.getUpper());
                int start = lower == null ? 0 : axis.firstIndexAfter(lower, range.isLowerInclusive());
                int end = upper == null ? axis.size() : axis.lastIndexBefore(upper, range.isUpperInclusive());
                return NetCDFDimensionIndexes.contiguousRange(start, end);
            }
            return allIndices(axis);
        }

        private static int[] allIndices(NetCDFDimensionIndexes.AxisLookup index) {
            if (index == null) {
                return new int[0];
            }
            return NetCDFDimensionIndexes.contiguousRange(0, index.size());
        }

        private static NetCDFDimensionIndexes.AxisLookup getAdditionalAxis(
                DimensionIndexesContext bundle, int logicalDimension) {
            int additionalIndex = logicalDimension - 2;
            if (additionalIndex < 0 || additionalIndex >= bundle.getAdditional().size()) {
                return null;
            }
            return bundle.getAdditional().get(additionalIndex);
        }

        private static Date extractExactTime(CompositeDimensionFilter composite) {
            RangeFilter left = asRange(composite.getLeft());
            RangeFilter right = asRange(composite.getRight());
            if (left == null || right == null) {
                return null;
            }

            Date exact = extractExactTime(left, right);
            if (exact != null) {
                return exact;
            }
            return extractExactTime(right, left);
        }

        private static Date extractExactTime(RangeFilter upperBoundFilter, RangeFilter lowerBoundFilter) {
            if (upperBoundFilter.getLower() != null || lowerBoundFilter.getUpper() != null) {
                return null;
            }

            Date upper = toDate(upperBoundFilter.getUpper());
            Date lower = toDate(lowerBoundFilter.getLower());
            if (upper == null || lower == null) {
                return null;
            }

            if (upper.getTime() != lower.getTime()) {
                return null;
            }

            if (!upperBoundFilter.isUpperInclusive() || !lowerBoundFilter.isLowerInclusive()) {
                return null;
            }

            return upper;
        }

        private static Double extractExactNumeric(CompositeDimensionFilter composite) {
            RangeFilter left = asRange(composite.getLeft());
            RangeFilter right = asRange(composite.getRight());
            if (left == null || right == null) {
                return null;
            }

            Double exact = extractExactNumeric(left, right);
            if (exact != null) {
                return exact;
            }
            return extractExactNumeric(right, left);
        }

        private static Double extractExactNumeric(RangeFilter upperBoundFilter, RangeFilter lowerBoundFilter) {
            if (upperBoundFilter.getLower() != null || lowerBoundFilter.getUpper() != null) {
                return null;
            }

            Double upper = toDouble(upperBoundFilter.getUpper());
            Double lower = toDouble(lowerBoundFilter.getLower());
            if (upper == null || lower == null) {
                return null;
            }

            if (Double.compare(upper, lower) != 0) {
                return null;
            }

            if (!upperBoundFilter.isUpperInclusive() || !lowerBoundFilter.isLowerInclusive()) {
                return null;
            }

            return upper;
        }

        private static RangeFilter asRange(DimensionFilter filter) {
            return filter instanceof RangeFilter range ? range : null;
        }

        private static int[] intersect(int[] left, int[] right) {
            int i = 0, j = 0;
            int[] tmp = new int[Math.min(left.length, right.length)];
            int count = 0;
            while (i < left.length && j < right.length) {
                if (left[i] == right[j]) {
                    tmp[count++] = left[i];
                    i++;
                    j++;
                } else if (left[i] < right[j]) {
                    i++;
                } else {
                    j++;
                }
            }
            int[] out = new int[count];
            System.arraycopy(tmp, 0, out, 0, count);
            return out;
        }

        private static int[] sorted(Set<Integer> values) {
            return values.stream().sorted().mapToInt(Integer::intValue).toArray();
        }

        private static Date toDate(Object value) {
            if (value instanceof Date d) {
                return d;
            }
            if (value instanceof Number n) {
                return new Date(n.longValue());
            }
            return null;
        }

        private static Double toDouble(Object value) {
            if (value instanceof Number n) {
                return n.doubleValue();
            }
            return null;
        }

        private static Filter simplify(Filter raw) {
            try {
                Object simplified = raw.accept(new SimplifyingFilterVisitor(), null);
                return simplified instanceof Filter ? (Filter) simplified : raw;
            } catch (RuntimeException e) {
                return raw;
            }
        }

        /**
         * Recognizes filters on time, elevation and additional dimensions, and splits them from the post filters. The
         * recognized filters are converted to {@link DimensionFilter} for efficient matching during iteration.
         */
        private static SplitResult splitFilter(
                Filter filter,
                VariableAdapter adapter,
                String timeAttribute,
                String elevationAttribute,
                List<AdditionalDomainBinding> additionalBindings) {
            if (filter == null || filter == Filter.INCLUDE || filter instanceof IncludeFilter) {
                return SplitResult.empty();
            }
            if (filter == Filter.EXCLUDE || filter instanceof ExcludeFilter) {
                return new SplitResult(
                        DimensionFilter.ALL, DimensionFilter.ALL, Collections.emptyList(), Filter.EXCLUDE);
            }

            FilterSplitter splitter =
                    new FilterSplitter(adapter, timeAttribute, elevationAttribute, additionalBindings);
            return splitter.split(filter);
        }
    }

    /** */
    private static final class SliceIndexDomain {
        final int[] dimensions;
        final int[][] selectedIndices;

        SliceIndexDomain(int[] dimensions, int[][] selectedIndices) {
            this.dimensions = dimensions;
            this.selectedIndices = selectedIndices;
        }

        long size() {
            long result = 1L;
            for (int[] values : selectedIndices) {
                result *= values.length;
            }
            return result;
        }

        int[] toSplitIndex(int[] compactTuple) {
            int[] splitIndex = new int[dimensions.length == 0 ? 0 : maxLogicalDimension(dimensions) + 1];
            Arrays.fill(splitIndex, -1);
            for (int i = 0; i < dimensions.length; i++) {
                splitIndex[dimensions[i]] = compactTuple[i];
            }
            return splitIndex;
        }

        private static int maxLogicalDimension(int[] logicalDimensions) {
            int max = -1;
            for (int logical : logicalDimensions) {
                if (logical > max) {
                    max = logical;
                }
            }
            return max;
        }
    }

    /**
     * An iterator over the tuples of selected indices for each dimension. It iterates in a cartesian product manner
     * over the selected indices.
     */
    private static final class IndexTupleIterator {
        private final int[][] selectedIndices;
        private final int[] offsets;
        private boolean hasNext = true;

        IndexTupleIterator(int[][] selectedIndices) {
            this.selectedIndices = selectedIndices;
            this.offsets = new int[selectedIndices.length];
            for (int[] c : selectedIndices) {
                if (c.length == 0) {
                    hasNext = false;
                    break;
                }
            }
        }

        boolean hasNext() {
            return hasNext;
        }

        int[] next() {
            if (!hasNext) {
                throw new NoSuchElementException();
            }
            int[] tuple = new int[selectedIndices.length];
            for (int i = 0; i < selectedIndices.length; i++) {
                tuple[i] = selectedIndices[i][offsets[i]];
            }
            advanceOffset();
            return tuple;
        }

        private void advanceOffset() {
            for (int i = 0; i < offsets.length; i++) {
                offsets[i]++;
                if (offsets[i] < selectedIndices[i].length) {
                    return;
                }
                offsets[i] = 0;
            }
            hasNext = false;
        }
    }

    /** The result of splitting the original filter into recognized dimension filters and post filter. */
    private static final class SplitResult {
        final DimensionFilter time;
        final DimensionFilter elevation;
        final List<AdditionalDimensionFilter> additional;
        final Filter postFilter;

        SplitResult(
                DimensionFilter time,
                DimensionFilter elevation,
                List<AdditionalDimensionFilter> additional,
                Filter postFilter) {
            this.time = time == null ? DimensionFilter.ALL : time;
            this.elevation = elevation == null ? DimensionFilter.ALL : elevation;
            this.additional = additional == null ? Collections.emptyList() : additional;
            this.postFilter = postFilter == null ? Filter.INCLUDE : postFilter;
        }

        static SplitResult empty() {
            return new SplitResult(DimensionFilter.ALL, DimensionFilter.ALL, Collections.emptyList(), Filter.INCLUDE);
        }
    }

    /** A filter on a single additional dimension, associated to its logical dimension index. */
    private static final class AdditionalDimensionFilter {
        final int dimensionIndex;
        final DimensionFilter filter;

        AdditionalDimensionFilter(int dimensionIndex, DimensionFilter filter) {
            this.dimensionIndex = dimensionIndex;
            this.filter = filter;
        }
    }

    /**
     * A filter visitor that recognizes filters on time, elevation and additional dimensions and splits them in
     * DimensionFilter instances, while collecting the unrecognized filters as postFilter. (the postFilter will be
     * evaluated against the features during iteration to filter out the ones that don't match the original query).
     */
    private static final class FilterSplitter extends DuplicatingFilterVisitor {
        private final VariableAdapter adapter;
        private final String timeAttribute;
        private final String elevationAttribute;
        private final List<AdditionalDomainBinding> additionalBindings;

        private DimensionFilter time = DimensionFilter.ALL;
        private DimensionFilter elevation = DimensionFilter.ALL;
        private final List<AdditionalDimensionFilter> additional = new ArrayList<>();
        private final List<Filter> postFilter = new ArrayList<>();

        FilterSplitter(
                VariableAdapter adapter,
                String timeAttribute,
                String elevationAttribute,
                List<AdditionalDomainBinding> additionalBindings) {
            super(FF);
            this.adapter = adapter;
            this.timeAttribute = normalizeAttribute(timeAttribute);
            this.elevationAttribute = normalizeAttribute(elevationAttribute);
            this.additionalBindings = additionalBindings;
        }

        private static String normalizeAttribute(String name) {
            return name == null ? null : name.trim().toUpperCase(Locale.ROOT);
        }

        SplitResult split(Filter filter) {
            walk(filter);
            return new SplitResult(time, elevation, List.copyOf(additional), mergePostFilter());
        }

        private void walk(Filter filter) {
            if (filter == null || filter == Filter.INCLUDE || filter instanceof IncludeFilter) {
                return;
            }
            if (filter == Filter.EXCLUDE || filter instanceof ExcludeFilter) {
                postFilter.add(Filter.EXCLUDE);
                return;
            }
            if (filter instanceof And and) {
                for (Filter child : and.getChildren()) {
                    walk(child);
                }
                return;
            }
            if (filter instanceof PropertyIsEqualTo eq && checkForEquality(eq)) {
                return;
            }
            if (filter instanceof PropertyIsBetween between && checkForBetween(between)) {
                return;
            }
            if (filter instanceof PropertyIsLessThan lt
                    && checkForComparison(lt.getExpression1(), lt.getExpression2(), ComparisonType.LT)) {
                return;
            }
            if (filter instanceof PropertyIsLessThanOrEqualTo lte
                    && checkForComparison(lte.getExpression1(), lte.getExpression2(), ComparisonType.LTE)) {
                return;
            }
            if (filter instanceof PropertyIsGreaterThan gt
                    && checkForComparison(gt.getExpression1(), gt.getExpression2(), ComparisonType.GT)) {
                return;
            }
            if (filter instanceof PropertyIsGreaterThanOrEqualTo gte
                    && checkForComparison(gte.getExpression1(), gte.getExpression2(), ComparisonType.GTE)) {
                return;
            }

            if (filter instanceof Or or && checkOr(or)) {
                return;
            }
            postFilter.add(filter);
        }

        private boolean checkForEquality(PropertyIsEqualTo eq) {
            AttributeLiteralPair pair = extractPair(eq.getExpression1(), eq.getExpression2());
            if (pair == null) {
                return false;
            }
            return addRecognizedFilter(pair.attributeName, new ExactDimensionFilter(pair.literal));
        }

        private boolean checkForBetween(PropertyIsBetween between) {
            if (!(between.getExpression() instanceof PropertyName prop)
                    || !(between.getLowerBoundary() instanceof Literal lower)
                    || !(between.getUpperBoundary() instanceof Literal upper)) {
                return false;
            }
            final String attributeName = normalizeAttribute(prop.getPropertyName());
            return addRecognizedFilter(attributeName, new RangeFilter(lower.getValue(), true, upper.getValue(), true));
        }

        private boolean checkForComparison(Expression left, Expression right, ComparisonType type) {
            ComparisonPair pair = extractComparisonPair(left, right, type);
            if (pair == null) {
                return false;
            }
            DimensionFilter filter;
            switch (pair.type) {
                case LT:
                    filter = new RangeFilter(null, true, pair.literal, false);
                    break;
                case LTE:
                    filter = new RangeFilter(null, true, pair.literal, true);
                    break;
                case GT:
                    filter = new RangeFilter(pair.literal, false, null, true);
                    break;
                case GTE:
                    filter = new RangeFilter(pair.literal, true, null, true);
                    break;
                default:
                    return false;
            }
            return addRecognizedFilter(pair.attributeName, filter);
        }

        private boolean checkOr(Or or) {
            String commonAttribute = null;
            List<Object> values = new ArrayList<>();
            for (Filter child : or.getChildren()) {
                if (!(child instanceof PropertyIsEqualTo eq)) {
                    return false;
                }
                AttributeLiteralPair pair = extractPair(eq.getExpression1(), eq.getExpression2());
                if (pair == null) {
                    return false;
                }
                if (commonAttribute == null) {
                    commonAttribute = pair.attributeName;
                } else if (!commonAttribute.equals(pair.attributeName)) {
                    return false;
                }
                values.add(pair.literal);
            }
            if (commonAttribute == null) {
                return false;
            }
            return addRecognizedFilter(commonAttribute, new ListFilter(values));
        }

        private boolean addRecognizedFilter(String attributeName, DimensionFilter filter) {
            if (attributeName == null) {
                return false;
            }

            if (adapter.getNDimensionIndex(VariableAdapter.T) >= 0 && attributeName.equals(timeAttribute)) {
                time = time.and(filter);
                return true;
            }

            if (adapter.getNDimensionIndex(VariableAdapter.Z) >= 0 && attributeName.equals(elevationAttribute)) {
                elevation = elevation.and(filter);
                return true;
            }

            for (AdditionalDomainBinding binding : additionalBindings) {
                if (attributeName.equals(normalizeAttribute(binding.attributeName))) {
                    mergeAdditional(binding.logicalDimension, filter);
                    return true;
                }
            }

            return false;
        }

        private void mergeAdditional(int logicalDimension, DimensionFilter filter) {
            for (int i = 0; i < additional.size(); i++) {
                AdditionalDimensionFilter current = additional.get(i);
                if (current.dimensionIndex == logicalDimension) {
                    additional.set(i, new AdditionalDimensionFilter(logicalDimension, current.filter.and(filter)));
                    return;
                }
            }
            additional.add(new AdditionalDimensionFilter(logicalDimension, filter));
        }

        private Filter mergePostFilter() {
            if (postFilter.isEmpty()) {
                return Filter.INCLUDE;
            }
            if (postFilter.size() == 1) {
                return postFilter.get(0);
            }
            return FF.and(postFilter);
        }

        private static AttributeLiteralPair extractPair(Expression left, Expression right) {
            if (left instanceof PropertyName prop && right instanceof Literal lit) {
                return new AttributeLiteralPair(normalizeAttribute(prop.getPropertyName()), lit.getValue());
            }
            if (right instanceof PropertyName prop && left instanceof Literal lit) {
                return new AttributeLiteralPair(normalizeAttribute(prop.getPropertyName()), lit.getValue());
            }
            return null;
        }

        private static ComparisonPair extractComparisonPair(Expression left, Expression right, ComparisonType type) {
            if (left instanceof PropertyName prop && right instanceof Literal lit) {
                return new ComparisonPair(normalizeAttribute(prop.getPropertyName()), lit.getValue(), type);
            }
            if (right instanceof PropertyName prop && left instanceof Literal lit) {
                return new ComparisonPair(normalizeAttribute(prop.getPropertyName()), lit.getValue(), type.invert());
            }
            return null;
        }
    }

    private enum ComparisonType {
        LT,
        LTE,
        GT,
        GTE;

        ComparisonType invert() {
            return switch (this) {
                case LT -> GT;
                case LTE -> GTE;
                case GT -> LT;
                case GTE -> LTE;
            };
        }
    }

    private static final class AttributeLiteralPair {
        final String attributeName;
        final Object literal;

        AttributeLiteralPair(String attributeName, Object literal) {
            this.attributeName = attributeName;
            this.literal = literal;
        }
    }

    private static final class ComparisonPair {
        final String attributeName;
        final Object literal;
        final ComparisonType type;

        ComparisonPair(String attributeName, Object literal, ComparisonType type) {
            this.attributeName = attributeName;
            this.literal = literal;
            this.type = type;
        }
    }
}
