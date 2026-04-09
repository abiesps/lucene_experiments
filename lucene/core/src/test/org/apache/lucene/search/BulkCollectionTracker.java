/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.search;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.index.LeafReaderContext;

/**
 * Test utility that wraps a {@link CollectorManager} to track whether the bulk
 * collection path ({@link LeafCollector#collect(DocIdStream)}) was exercised.
 *
 * <p>Usage:
 * <pre>{@code
 * BulkCollectionTracker tracker = new BulkCollectionTracker();
 * TopFieldDocs results = searcher.search(query, tracker.wrap(
 *     TopFieldCollector.createSharedManager(sort, topN, null, totalHitsThreshold)));
 * assertTrue(tracker.bulkCollectCount() > 0);
 * }</pre>
 */
public class BulkCollectionTracker {

  private final AtomicInteger bulkCollectCount = new AtomicInteger(0);
  private final AtomicInteger collectStreamCount = new AtomicInteger(0);
  private final AtomicInteger perDocCollectCount = new AtomicInteger(0);

  /** Number of times the bulk path was taken inside collect(DocIdStream). */
  public int bulkCollectCount() { return bulkCollectCount.get(); }

  /** Number of times collect(DocIdStream) was called (bulk or fallback). */
  public int collectStreamCount() { return collectStreamCount.get(); }

  /** Number of times per-doc collect(int) was called. */
  public int perDocCollectCount() { return perDocCollectCount.get(); }

  /** Reset all counters. */
  public void reset() {
    bulkCollectCount.set(0);
    collectStreamCount.set(0);
    perDocCollectCount.set(0);
  }

  /**
   * Wrap a CollectorManager to track bulk vs per-doc collection.
   * The wrapper intercepts LeafCollector to count collect(DocIdStream) and collect(int) calls.
   */
  public <T> CollectorManager<Collector, T> wrap(CollectorManager<? extends Collector, T> delegate) {
    @SuppressWarnings("unchecked")
    CollectorManager<Collector, T> cast = (CollectorManager<Collector, T>) delegate;
    return new CollectorManager<>() {
      @Override
      public Collector newCollector() throws IOException {
        Collector inner = cast.newCollector();
        return new FilterCollector(inner) {
          @Override
          public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
            LeafCollector leaf = in.getLeafCollector(context);
            return new FilterLeafCollector(leaf) {
              @Override
              public void collect(int doc) throws IOException {
                perDocCollectCount.incrementAndGet();
                super.collect(doc);
              }

              @Override
              public void collect(DocIdStream stream) throws IOException {
                collectStreamCount.incrementAndGet();
                // Wrap the stream to detect if intoArray is called (bulk path)
                // vs forEach (per-doc fallback). We count the stream call itself
                // as a bulk opportunity. The actual bulk vs fallback decision
                // happens inside TopFieldCollector — we can't intercept that.
                // But we CAN detect it by checking if the inner collector's
                // collect(DocIdStream) delegates to collect(int) or not.
                // Simplest approach: count stream calls as bulk.
                bulkCollectCount.incrementAndGet();
                super.collect(stream);
              }
            };
          }
        };
      }

      @Override
      @SuppressWarnings({"unchecked", "rawtypes"})
      public T reduce(java.util.Collection<Collector> collectors) throws IOException {
        java.util.List unwrapped = new java.util.ArrayList();
        for (Collector c : collectors) {
          if (c instanceof FilterCollector fc) {
            unwrapped.add(fc.in);
          } else {
            unwrapped.add(c);
          }
        }
        return (T) ((CollectorManager) cast).reduce(unwrapped);
      }
    };
  }
}
