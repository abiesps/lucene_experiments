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
package org.apache.lucene.util.bkd;

/**
 * Experiment switches for BKD tree reads. All are off by default (stock behavior). Settings are
 * process-wide and read when an intersection starts.
 *
 * @lucene.experimental
 */
public final class BKDExperiments {
  /** Smallest accepted value of {@link #setNodeBytes(long)}. */
  public static final long MIN_NODE_BYTES = 4096;

  /** Largest accepted value of {@link #setNodeBytes(long)}. */
  public static final long MAX_NODE_BYTES = 16L << 20;

  /** Largest accepted value of {@link #setWholeIndexPrefetchBytes(long)}. */
  public static final long MAX_WHOLE_INDEX_PREFETCH_BYTES = 16L << 20;

  /** Largest accepted value of {@link #setPrefetchChunks(int)}. */
  public static final int MAX_PREFETCH_CHUNKS = 64;

  /** Default of {@link #setPrefetchChunks(int)}. */
  public static final int DEFAULT_PREFETCH_CHUNKS = 8;

  /** Default of {@link #setNodeBytes(long)}. */
  public static final long DEFAULT_NODE_BYTES = 131072;

  /** Default of {@link #setWholeIndexPrefetchBytes(long)}. */
  public static final long DEFAULT_WHOLE_INDEX_PREFETCH_BYTES = 65536;

  private static volatile boolean intersectPrefetch;
  private static volatile int prefetchChunks = DEFAULT_PREFETCH_CHUNKS;
  private static volatile long nodeBytes = DEFAULT_NODE_BYTES;
  private static volatile boolean wholeIndexPrefetch;
  private static volatile long wholeIndexPrefetchBytes = DEFAULT_WHOLE_INDEX_PREFETCH_BYTES;
  private static volatile boolean indexChildPrefetch;

  private BKDExperiments() {}

  /**
   * Sets whether an intersection whose visitor opts in collects the file ranges of the leaves it
   * will read and prefetches them before it reads them.
   */
  public static void setIntersectPrefetch(boolean on) {
    intersectPrefetch = on;
  }

  /** Returns whether intersections prefetch the leaves they will read. */
  public static boolean isIntersectPrefetch() {
    return intersectPrefetch;
  }

  /**
   * Sets into how many chunks the collected leaf ranges are split before they are prefetched.
   *
   * @throws IllegalArgumentException if {@code chunks} is not in 1..64
   */
  public static void setPrefetchChunks(int chunks) {
    if (chunks < 1 || chunks > MAX_PREFETCH_CHUNKS) {
      throw new IllegalArgumentException(
          "prefetch chunks must be in 1.." + MAX_PREFETCH_CHUNKS + ", got " + chunks);
    }
    prefetchChunks = chunks;
  }

  /** Returns into how many chunks the collected leaf ranges are split. */
  public static int getPrefetchChunks() {
    return prefetchChunks;
  }

  /**
   * Sets the size of one storage node in bytes. Leaf ranges are rounded to this size before they
   * are prefetched.
   *
   * @throws IllegalArgumentException if {@code bytes} is not a power of two in 4 KiB..16 MiB
   */
  public static void setNodeBytes(long bytes) {
    if (bytes < MIN_NODE_BYTES || bytes > MAX_NODE_BYTES || Long.bitCount(bytes) != 1) {
      throw new IllegalArgumentException(
          "node bytes must be a power of two in "
              + MIN_NODE_BYTES
              + ".."
              + MAX_NODE_BYTES
              + ", got "
              + bytes);
    }
    nodeBytes = bytes;
  }

  /** Returns the size of one storage node in bytes. */
  public static long getNodeBytes() {
    return nodeBytes;
  }

  /**
   * Sets whether a split-format reader prefetches its whole inner-node file when the file is at
   * most {@link #getWholeIndexPrefetchBytes()} bytes. Used only when {@link #isIntersectPrefetch()}
   * is also on.
   */
  public static void setWholeIndexPrefetch(boolean on) {
    wholeIndexPrefetch = on;
  }

  /** Returns whether a small inner-node file is prefetched whole. */
  public static boolean isWholeIndexPrefetch() {
    return wholeIndexPrefetch;
  }

  /**
   * Sets the largest inner-node file, in bytes, that is prefetched whole.
   *
   * @throws IllegalArgumentException if {@code bytes} is not in 0..16 MiB
   */
  public static void setWholeIndexPrefetchBytes(long bytes) {
    if (bytes < 0 || bytes > MAX_WHOLE_INDEX_PREFETCH_BYTES) {
      throw new IllegalArgumentException(
          "whole index prefetch bytes must be in 0.."
              + MAX_WHOLE_INDEX_PREFETCH_BYTES
              + ", got "
              + bytes);
    }
    wholeIndexPrefetchBytes = bytes;
  }

  /** Returns the largest inner-node file, in bytes, that is prefetched whole. */
  public static long getWholeIndexPrefetchBytes() {
    return wholeIndexPrefetchBytes;
  }

  /**
   * Sets whether a split-format reader prefetches the child inner nodes while it descends. Used
   * only when {@link #isIntersectPrefetch()} is also on.
   */
  public static void setIndexChildPrefetch(boolean on) {
    indexChildPrefetch = on;
  }

  /** Returns whether child inner nodes are prefetched while descending. */
  public static boolean isIndexChildPrefetch() {
    return indexChildPrefetch;
  }
}
