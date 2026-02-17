/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2012, Open Source Geospatial Foundation (OSGeo)
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
import java.util.Iterator;
import org.geotools.api.data.Query;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.coverage.grid.io.GranuleSource;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.feature.collection.AbstractFeatureCollection;
import org.geotools.geometry.jts.ReferencedEnvelope;

/**
 * A {@link GranuleSource} implementation wrapping a {@link CoverageSlicesCatalog}.
 *
 * @author Daniele Romagnoli, GeoSolutions SAS
 */
public class CoverageSlicesCatalogSource implements GranuleSource {

    private CoverageSlicesCatalog innerCatalog;

    private final String typeName;

    public CoverageSlicesCatalogSource(CoverageSlicesCatalog innerCatalog, String typeName) {
        this.innerCatalog = innerCatalog;
        this.typeName = typeName;
    }

    @Override
    public SimpleFeatureCollection getGranules(Query q) throws IOException {
        Query query = prepareQuery(q);
        SimpleFeatureType schema = innerCatalog.getSchema(typeName);
        return new LazyCoverageSliceFeatureCollection(innerCatalog, schema, query);
    }

    @Override
    public int getCount(Query q) throws IOException {
        Query query = prepareQuery(q);
        return innerCatalog.getCount(query);
    }

    @Override
    public ReferencedEnvelope getBounds(Query q) throws IOException {
        Query query = prepareQuery(q);
        return innerCatalog.getBounds(query);
    }

    private Query prepareQuery(Query q) {
        // Filtering by typeName + avoid mutating the caller's Query
        Query query = (q == null) ? new Query(typeName) : new Query(q);
        query.setTypeName(typeName);
        return query;
    }

    @Override
    public SimpleFeatureType getSchema() throws IOException {
        return innerCatalog.getSchema(typeName);
    }

    @Override
    public void dispose() throws IOException {
        // do nothing
    }

    /**
     * Lazy collection that streams slices from {@link CoverageSlicesCatalog#iterateGranules(Query)} and exposes their
     * originator {@link SimpleFeature}s.
     */
    private static final class LazyCoverageSliceFeatureCollection extends AbstractFeatureCollection
            implements SimpleFeatureCollection {

        private final CoverageSlicesCatalog catalog;
        private final Query query;

        LazyCoverageSliceFeatureCollection(CoverageSlicesCatalog catalog, SimpleFeatureType schema, Query query) {
            super(schema);
            this.catalog = catalog;
            this.query = query;
        }

        private static final class SliceFeatureIterator implements Iterator<SimpleFeature> {
            private final CoverageSlicesCatalog.SliceIterator delegate;

            SliceFeatureIterator(CoverageSlicesCatalog.SliceIterator delegate) {
                this.delegate = delegate;
            }

            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public SimpleFeature next() {
                return delegate.next().getOriginator();
            }

            void close() {
                delegate.close();
            }
        }

        @Override
        protected Iterator<SimpleFeature> openIterator() {
            try {
                return new SliceFeatureIterator(catalog.iterateGranules(query));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        /** Proper GeoTools streaming iterator. */
        @Override
        @SuppressWarnings("PMD.CloseResource")
        public SimpleFeatureIterator features() {
            try {
                // The sliceIt will be closed when closing the features.
                final CoverageSlicesCatalog.SliceIterator sliceIt = catalog.iterateGranules(query);

                return new SimpleFeatureIterator() {

                    @Override
                    public boolean hasNext() {
                        return sliceIt.hasNext();
                    }

                    @Override
                    public SimpleFeature next() {
                        return sliceIt.next().getOriginator();
                    }

                    @Override
                    public void close() {
                        sliceIt.close();
                    }
                };

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        /** Efficient count without iterating features. */
        @Override
        public int size() {
            try {
                return catalog.getCount(query);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        /** Efficient bounds without iterating features. */
        @Override
        public ReferencedEnvelope getBounds() {
            try {
                return catalog.getBounds(query);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
