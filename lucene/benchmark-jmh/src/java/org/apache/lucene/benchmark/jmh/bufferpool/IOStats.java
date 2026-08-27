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
package org.apache.lucene.benchmark.jmh.bufferpool;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Global IO accounting for the buffer-pool directory. Every actual disk IO (block load) and every
 * cache hit is recorded here, keyed by file extension, so a benchmark can deterministically answer
 * "how many IO calls did this query issue against the .vec (full-precision) file vs the .veq
 * (quantized) file".
 *
 * <p>{@code floatReads} counts calls to {@code IndexInput.readFloats} per extension — on the {@code
 * .vec} file this is exactly the number of full-precision vectors materialized by the rescore phase
 * (each {@code OffHeapFloatVectorValues.vectorValue(ord)} issues one seek + readFloats of {@code
 * dim} floats).
 */
public final class IOStats {

  private IOStats() {}

  private static final ConcurrentHashMap<String, LongAdder> BLOCK_LOADS = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, LongAdder> BYTES_LOADED =
      new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, LongAdder> CACHE_HITS = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, LongAdder> FLOAT_READS = new ConcurrentHashMap<>();
  private static final LongAdder EVICTIONS = new LongAdder();

  public static String extension(Path path) {
    String name = path.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "<none>" : name.substring(dot + 1);
  }

  static void recordBlockLoad(String ext, int bytes) {
    BLOCK_LOADS.computeIfAbsent(ext, e -> new LongAdder()).increment();
    BYTES_LOADED.computeIfAbsent(ext, e -> new LongAdder()).add(bytes);
  }

  static void recordCacheHit(String ext) {
    CACHE_HITS.computeIfAbsent(ext, e -> new LongAdder()).increment();
  }

  static void recordFloatRead(String ext) {
    FLOAT_READS.computeIfAbsent(ext, e -> new LongAdder()).increment();
  }

  static void recordEviction() {
    EVICTIONS.increment();
  }

  public static long blockLoads(String ext) {
    LongAdder a = BLOCK_LOADS.get(ext);
    return a == null ? 0 : a.sum();
  }

  public static long totalBlockLoads() {
    return BLOCK_LOADS.values().stream().mapToLong(LongAdder::sum).sum();
  }

  public static long bytesLoaded(String ext) {
    LongAdder a = BYTES_LOADED.get(ext);
    return a == null ? 0 : a.sum();
  }

  public static long cacheHits(String ext) {
    LongAdder a = CACHE_HITS.get(ext);
    return a == null ? 0 : a.sum();
  }

  public static long totalCacheHits() {
    return CACHE_HITS.values().stream().mapToLong(LongAdder::sum).sum();
  }

  public static long floatReads(String ext) {
    LongAdder a = FLOAT_READS.get(ext);
    return a == null ? 0 : a.sum();
  }

  public static long evictions() {
    return EVICTIONS.sum();
  }

  public static void reset() {
    BLOCK_LOADS.clear();
    BYTES_LOADED.clear();
    CACHE_HITS.clear();
    FLOAT_READS.clear();
    EVICTIONS.reset();
  }

  /** Formatted per-extension dump for characterization output. */
  public static String dump() {
    StringBuilder sb = new StringBuilder();
    Map<String, long[]> rows = new TreeMap<>();
    BLOCK_LOADS.forEach((e, v) -> rows.computeIfAbsent(e, x -> new long[4])[0] = v.sum());
    BYTES_LOADED.forEach((e, v) -> rows.computeIfAbsent(e, x -> new long[4])[1] = v.sum());
    CACHE_HITS.forEach((e, v) -> rows.computeIfAbsent(e, x -> new long[4])[2] = v.sum());
    FLOAT_READS.forEach((e, v) -> rows.computeIfAbsent(e, x -> new long[4])[3] = v.sum());
    sb.append(
        String.format(
            java.util.Locale.ROOT,
            "%-8s %12s %14s %12s %12s%n",
            "ext",
            "blockLoads",
            "bytesLoaded",
            "cacheHits",
            "floatReads"));
    for (Map.Entry<String, long[]> e : rows.entrySet()) {
      long[] r = e.getValue();
      sb.append(
          String.format(
              java.util.Locale.ROOT,
              "%-8s %12d %14d %12d %12d%n",
              e.getKey(),
              r[0],
              r[1],
              r[2],
              r[3]));
    }
    sb.append("evictions: ").append(EVICTIONS.sum()).append('\n');
    return sb.toString();
  }
}
