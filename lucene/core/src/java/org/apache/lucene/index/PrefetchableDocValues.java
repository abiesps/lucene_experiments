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
package org.apache.lucene.index;

import java.io.IOException;

/**
 * Interface for doc values implementations that support prefetching data pages ahead of access.
 * This enables query-aware (non-speculative) prefetch of doc values data, which is critical for
 * high-latency storage backends like EFS where random I/O is expensive.
 *
 * <p>Implementations compute which data pages will be needed for a batch of doc IDs or term
 * ordinals, deduplicate pages, and issue prefetch (e.g., madvise WILLNEED) calls so that the data
 * is warm by the time the actual reads happen.
 *
 * @lucene.experimental
 */
public interface PrefetchableDocValues {

  /**
   * Prefetch data pages for the given batch of doc IDs. The docs array must be sorted in ascending
   * order. Only the first {@code count} entries are valid.
   *
   * <p>This is used in the collection path (aggregations, field sorting) where we know exactly
   * which doc IDs will be accessed via {@code advanceExact(doc)} + {@code longValue()}.
   *
   * @param docs sorted array of doc IDs to prefetch data for
   * @param count number of valid entries in the docs array
   */
  void prefetchExact(int[] docs, int count) throws IOException;

  /**
   * Prefetch data pages for the given batch of term ordinals. Used in the result-building path of
   * terms aggregations where we call {@code lookupOrd(ord)} for each bucket.
   *
   * <p>The default implementation is a no-op. Only SortedDocValues / SortedSetDocValues
   * implementations need to override this.
   *
   * @param ords array of term ordinals to prefetch data for
   * @param count number of valid entries in the ords array
   */
  default void prefetchTerms(long[] ords, int count) throws IOException {
    // no-op by default — only sorted doc values implement this
  }
}
