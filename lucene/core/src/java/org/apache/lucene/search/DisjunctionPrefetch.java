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
 * Experiment switch for prefetching in exhaustive disjunctions ({@code BooleanScorer}).
 *
 * <p>When {@link #getBytesAhead()} is positive, {@code BooleanScorer} asks each clause, as it moves
 * from one 4,096-doc window to the next, to keep about that many bytes of its postings requested
 * ahead of the current window, see {@link DocIdSetIterator#prefetchAhead}. Clauses whose postings
 * cannot plan prefetches ignore the hint. 0 (the default) disables it.
 *
 * @lucene.experimental
 */
public final class DisjunctionPrefetch {

  private static volatile long bytesAhead;

  private DisjunctionPrefetch() {}

  /** Sets how many bytes of postings each clause keeps requested ahead; 0 disables prefetching. */
  public static void setBytesAhead(long bytes) {
    if (bytes < 0) {
      throw new IllegalArgumentException("bytes must be >= 0, got " + bytes);
    }
    bytesAhead = bytes;
  }

  /** Returns the current setting, 0 when disabled. */
  public static long getBytesAhead() {
    return bytesAhead;
  }

  private static volatile long nodeBytes;

  /**
   * Aligned mode: when positive, iterators that support it plan prefetches in whole nodes of this
   * many bytes (the storage block size, for example the cache block size), counted from the start
   * of the file. When a clause starts reading node k, nodes up to k + bytesAhead / nodeBytes (at
   * least k + 1) are requested, and the clause asks to be called again at the first doc whose
   * postings reach node k + 1. 0 (the default) keeps the byte-budget mode.
   *
   * <p>Offsets are file offsets of the postings file, so nodes line up with storage blocks only
   * when that file is not inside a compound file.
   */
  public static void setNodeBytes(long bytes) {
    if (bytes < 0) {
      throw new IllegalArgumentException("bytes must be >= 0, got " + bytes);
    }
    nodeBytes = bytes;
  }

  /** Returns the node size of aligned mode, 0 when aligned mode is off. */
  public static long getNodeBytes() {
    return nodeBytes;
  }
}
