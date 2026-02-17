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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.geotools.util.Utilities;

/** Resolves a global image index into the owning variable and its logical non-spatial dimension indexes. */
final class NetCDFImageIndexResolver {

    /**
     * Represents the result of resolving a variable selection into index positions.
     *
     * <p>This encapsulates the target variable name and the corresponding index for each dimension, forming a fully
     * specified coordinate within the variable, identifying a 2D(xy) slice of the multidimensional hypercube.
     */
    static final class ResolvedVariableIndex {
        private final String variableName;
        private final int[] indexes;

        ResolvedVariableIndex(String variableName, int[] indexes) {
            this.variableName = variableName;
            this.indexes = indexes;
        }

        String getVariableName() {
            return variableName;
        }

        int getNIndex(int n) {
            return indexes[n];
        }

        int getNCount() {
            return indexes.length;
        }
    }

    private static final class VariableIndexRange {
        private final VariableAdapter variableAdapter;
        private final String variableName;
        private final int startIndexInclusive;
        private final int endIndexExclusive;

        VariableIndexRange(VariableAdapter variableAdapter, int startIndexInclusive) {
            this.variableAdapter = variableAdapter;
            this.variableName = variableAdapter.getName();
            this.startIndexInclusive = startIndexInclusive;
            this.endIndexExclusive = startIndexInclusive + variableAdapter.getNumberOfSlices();
        }

        int toLocalIndex(int imageIndex) {
            return imageIndex - startIndexInclusive;
        }

        int getSliceCount() {
            return endIndexExclusive - startIndexInclusive;
        }
    }

    private final List<VariableIndexRange> ranges;
    private final int[] startIndexes;
    private final int totalImageCount;

    NetCDFImageIndexResolver(List<VariableAdapter> variableAdapters) {
        Utilities.ensureNonNull("variableAdapters", variableAdapters);

        List<VariableIndexRange> variableIndexRanges = new ArrayList<>(variableAdapters.size());
        List<Integer> starts = new ArrayList<>(variableAdapters.size());

        int offset = 0;
        for (VariableAdapter adapter : variableAdapters) {
            if (adapter == null) {
                continue;
            }
            VariableIndexRange variableIndexRange = new VariableIndexRange(adapter, offset);
            variableIndexRanges.add(variableIndexRange);
            starts.add(offset);
            offset += variableIndexRange.getSliceCount();
        }

        this.ranges = Collections.unmodifiableList(variableIndexRanges);
        this.startIndexes = new int[starts.size()];
        for (int i = 0; i < starts.size(); i++) {
            this.startIndexes[i] = starts.get(i);
        }
        this.totalImageCount = offset;
    }

    ResolvedVariableIndex resolve(int imageIndex) {
        VariableIndexRange variableIndexRange = toVariableIndexRange(imageIndex);
        int localImageIndex = variableIndexRange.toLocalIndex(imageIndex);
        int[] indexes = variableIndexRange.variableAdapter.splitIndex(localImageIndex);
        return new ResolvedVariableIndex(variableIndexRange.variableName, indexes);
    }

    private VariableIndexRange toVariableIndexRange(int imageIndex) {
        if (imageIndex < 0 || imageIndex >= totalImageCount) {
            throw new IndexOutOfBoundsException(
                    "Invalid imageIndex " + imageIndex + ", valid range is [0.." + (totalImageCount - 1) + "]");
        }

        int idx = Arrays.binarySearch(startIndexes, imageIndex);
        if (idx >= 0) {
            return ranges.get(idx);
        }

        // imageIndex falls inside the range started by the previous entry
        int insertionPoint = -(idx + 1);
        return ranges.get(insertionPoint - 1);
    }
}
