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

/**
 * Experiment switch for prefetching in top-k disjunctions ({@link MaxScoreBulkScorer}).
 *
 * <p>When {@link #getNormsDocsAhead()} is positive, the bulk scorer plans ahead of the doc it is
 * scoring in windows of {@link MaxScoreBulkScorer#INNER_WINDOW_SIZE} docs, and for each window up
 * to that many docs ahead requests the norms of the clauses' fields, in whole nodes of {@link
 * #getNodeBytes()} bytes (see {@link org.apache.lucene.index.NumericDocValues#prefetchNodes}).
 *
 * <p>With {@link #isFilter()}, only eligible windows are requested: the sum of the clauses' max
 * scores over the window, from their impacts, must be at least the current minimum competitive
 * score. The max scores come from a second impacts enum per clause, used only for planning, so the
 * scoring iterators do not move. The threshold only rises, so a window that is not eligible when
 * planned is never scored.
 *
 * <p>0 docs ahead (the default) disables it. Settings are read when a bulk scorer is created.
 *
 * @lucene.experimental
 */
public final class TopKPrefetch {
  private static volatile int normsDocsAhead;
  private static volatile long nodeBytes = 128 * 1024;
  private static volatile boolean filter = true;

  private TopKPrefetch() {}

  /** Sets how many doc IDs ahead of the scored doc to request norms for; 0 disables it. */
  public static void setNormsDocsAhead(int docs) {
    if (docs < 0) {
      throw new IllegalArgumentException("docs must be >= 0, got " + docs);
    }
    normsDocsAhead = docs;
  }

  /** Returns how many doc IDs ahead norms are requested, 0 when disabled. */
  public static int getNormsDocsAhead() {
    return normsDocsAhead;
  }

  /** Sets the node size in bytes, e.g. the storage or cache block size. */
  public static void setNodeBytes(long bytes) {
    if (bytes <= 0) {
      throw new IllegalArgumentException("bytes must be > 0, got " + bytes);
    }
    nodeBytes = bytes;
  }

  /** Returns the node size in bytes. */
  public static long getNodeBytes() {
    return nodeBytes;
  }

  /** Sets whether only windows whose max score can beat the current threshold are requested. */
  public static void setFilter(boolean on) {
    filter = on;
  }

  /** Returns whether requests are limited to eligible windows. */
  public static boolean isFilter() {
    return filter;
  }

  private static volatile int docNodesAhead;

  /**
   * Sets how many nodes of each clause's postings to keep requested ahead of the node being read
   * (see {@link DocIdSetIterator#prefetchAhead}); 0 disables it. Postings that plan in whole nodes
   * (Lucene104DualNav in nav mode) do so only when {@link DisjunctionPrefetch#setNodeBytes} is set,
   * normally to the same node size.
   */
  public static void setDocNodesAhead(int nodes) {
    if (nodes < 0) {
      throw new IllegalArgumentException("nodes must be >= 0, got " + nodes);
    }
    docNodesAhead = nodes;
  }

  /** Returns how many postings nodes are kept requested ahead per clause, 0 when disabled. */
  public static int getDocNodesAhead() {
    return docNodesAhead;
  }

  /** True if the planner is on (norms or postings prefetch). */
  public static boolean isEnabled() {
    return normsDocsAhead > 0 || docNodesAhead > 0;
  }
}
