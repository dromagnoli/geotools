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

    static final class ResolvedSlice {
        private final String variableName;
        private final int[] indexes;

        ResolvedSlice(String variableName, int[] indexes) {
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

    private static final class Entry {
        private final VariableAdapter variableAdapter;
        private final String variableName;
        private final int startIndexInclusive;
        private final int endIndexExclusive;

        Entry(VariableAdapter variableAdapter, int startIndexInclusive) {
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

    private final List<Entry> entries;
    private final int[] startIndexes;
    private final int totalImageCount;

    NetCDFImageIndexResolver(List<VariableAdapter> variableAdapters) {
        Utilities.ensureNonNull("variableAdapters", variableAdapters);

        List<Entry> resolvedEntries = new ArrayList<>(variableAdapters.size());
        List<Integer> starts = new ArrayList<>(variableAdapters.size());

        int offset = 0;
        for (VariableAdapter adapter : variableAdapters) {
            if (adapter == null) {
                continue;
            }
            Entry entry = new Entry(adapter, offset);
            resolvedEntries.add(entry);
            starts.add(offset);
            offset += entry.getSliceCount();
        }

        this.entries = Collections.unmodifiableList(resolvedEntries);
        this.startIndexes = new int[starts.size()];
        for (int i = 0; i < starts.size(); i++) {
            this.startIndexes[i] = starts.get(i);
        }
        this.totalImageCount = offset;
    }

    ResolvedSlice resolve(int imageIndex) {
        Entry entry = findEntry(imageIndex);
        int localImageIndex = entry.toLocalIndex(imageIndex);
        int[] indexes = entry.variableAdapter.splitIndex(localImageIndex);
        return new ResolvedSlice(entry.variableName, indexes);
    }

    private Entry findEntry(int imageIndex) {
        if (imageIndex < 0 || imageIndex >= totalImageCount) {
            throw new IndexOutOfBoundsException(
                    "Invalid imageIndex " + imageIndex + ", valid range is [0.." + (totalImageCount - 1) + "]");
        }

        int idx = Arrays.binarySearch(startIndexes, imageIndex);
        if (idx >= 0) {
            return entries.get(idx);
        }

        // imageIndex falls inside the range started by the previous entry
        int insertionPoint = -(idx + 1);
        return entries.get(insertionPoint - 1);
    }
}
