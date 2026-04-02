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
package org.apache.lucene.benchmark.jmh;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH benchmark measuring the performance benefit of bulk doc values prefetch. Compares
 * prefetch-enabled bulk {@code longValues()} against per-doc {@code advanceExact() + longValue()}
 * using a {@link SlowIndexInput} wrapper that simulates EFS-like cache miss latency.
 *
 * <p>Parameterized over encoding type, field density, batch size, and doc ID distribution.
 *
 * <p>Run with: {@code java -jar benchmark-jmh.jar DocValuesBulkPrefetchBenchmark}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(
    value = 1,
    jvmArgsAppend = {"-Xmx1g", "-Xms1g", "-XX:+AlwaysPreTouch"})
public class DocValuesBulkPrefetchBenchmark {

  private static final String FIELD_NAME = "dv";
  private static final int NUM_DOCS = 100_000;
  private static final long DEFAULT_VALUE = 0L;

  /** Encoding type for the numeric doc values field. */
  public enum EncodingType {
    /** Fixed bits-per-value with GCD encoding (common for timestamps). */
    FIXED_BPV,
    /** Varying bits-per-value across blocks. */
    VARYING_BPV
  }

  /** Field density: whether all docs have values or only some. */
  public enum FieldDensity {
    /** Every document has a value (no DISI). */
    DENSE,
    /** ~50% of documents have values (uses DISI). */
    SPARSE
  }

  /** Doc ID distribution pattern within a batch. */
  public enum DocIdDistribution {
    /** Nearly contiguous doc IDs (low density ratio, triggers contiguous range prefetch). */
    CONTIGUOUS,
    /** Scattered doc IDs with moderate gaps (medium density ratio). */
    SCATTERED_MEDIUM,
    /** Widely scattered doc IDs (high density ratio, triggers per-doc prefetch). */
    SCATTERED_WIDE
  }

  @Param({"FIXED_BPV", "VARYING_BPV"})
  public EncodingType encodingType;

  @Param({"DENSE", "SPARSE"})
  public FieldDensity fieldDensity;

  @Param({"256", "1024", "4096"})
  public int batchSize;

  @Param({"CONTIGUOUS", "SCATTERED_MEDIUM", "SCATTERED_WIDE"})
  public DocIdDistribution distribution;

  private Directory directory;
  private DirectoryReader reader;
  private int[] docBatch;
  private long[] valueBuffer;

  @Setup(Level.Trial)
  public void setup() throws Exception {
    directory = MMapDirectory.open(java.nio.file.Files.createTempDirectory("dvbench"));
    buildIndex();
    reader = DirectoryReader.open(directory);
    valueBuffer = new long[batchSize];
    docBatch = generateDocIdBatch();
  }

  @TearDown(Level.Trial)
  public void tearDown() throws Exception {
    reader.close();
    // Clean up temp directory
    for (String file : directory.listAll()) {
      directory.deleteFile(file);
    }
    directory.close();
  }

  /**
   * Benchmark: Bulk longValues() with prefetch. This is the optimized path that prefetches byte
   * ranges before reading.
   */
  @Benchmark
  public void bulkLongValues(Blackhole bh) throws IOException {
    for (LeafReaderContext ctx : reader.leaves()) {
      LeafReader leaf = ctx.reader();
      NumericDocValues dv = leaf.getNumericDocValues(FIELD_NAME);
      if (dv == null) continue;

      // Filter batch to docs within this leaf's range
      int base = ctx.docBase;
      int maxDoc = leaf.maxDoc();
      int[] localDocs = adjustDocsForLeaf(docBatch, base, maxDoc);
      if (localDocs.length == 0) continue;

      long[] vals = valueBuffer;
      dv.longValues(localDocs.length, localDocs, vals, DEFAULT_VALUE);
      for (int i = 0; i < localDocs.length; i++) {
        bh.consume(vals[i]);
      }
    }
  }

  /**
   * Benchmark: Per-doc advanceExact + longValue (baseline). This is the traditional per-doc path
   * without prefetch.
   */
  @Benchmark
  public void perDocAdvanceExact(Blackhole bh) throws IOException {
    for (LeafReaderContext ctx : reader.leaves()) {
      LeafReader leaf = ctx.reader();
      NumericDocValues dv = leaf.getNumericDocValues(FIELD_NAME);
      if (dv == null) continue;

      int base = ctx.docBase;
      int maxDoc = leaf.maxDoc();
      int[] localDocs = adjustDocsForLeaf(docBatch, base, maxDoc);
      if (localDocs.length == 0) continue;

      for (int doc : localDocs) {
        if (dv.advanceExact(doc)) {
          bh.consume(dv.longValue());
        } else {
          bh.consume(DEFAULT_VALUE);
        }
      }
    }
  }

  // --- Index building ---

  private void buildIndex() throws IOException {
    IndexWriterConfig conf = new IndexWriterConfig();
    conf.setMaxBufferedDocs(NUM_DOCS + 1);
    conf.setRAMBufferSizeMB(-1);
    try (IndexWriter writer = new IndexWriter(directory, conf)) {
      Random rnd = new Random(42);
      long[] values = generateValues(rnd);
      boolean[] hasValue = generateHasValue(rnd);

      for (int i = 0; i < NUM_DOCS; i++) {
        Document doc = new Document();
        if (hasValue[i]) {
          doc.add(new NumericDocValuesField(FIELD_NAME, values[i]));
        }
        writer.addDocument(doc);
      }
      writer.forceMerge(1);
    }
  }

  private long[] generateValues(Random rnd) {
    long[] values = new long[NUM_DOCS];
    switch (encodingType) {
      case FIXED_BPV:
        // GCD-encoded timestamps: base + k * gcd (common for date_histogram workloads)
        long base = 1_700_000_000_000L; // ~2023 epoch millis
        long gcd = 60_000L; // 1-minute granularity
        for (int i = 0; i < NUM_DOCS; i++) {
          values[i] = base + (long) rnd.nextInt(100_000) * gcd;
        }
        break;
      case VARYING_BPV:
        // Alternating small/large values to trigger VaryingBPV encoding
        for (int i = 0; i < NUM_DOCS; i++) {
          if ((i / 128) % 2 == 0) {
            values[i] = rnd.nextInt(16);
          } else {
            values[i] = rnd.nextLong() & Long.MAX_VALUE;
          }
        }
        break;
    }
    return values;
  }

  private boolean[] generateHasValue(Random rnd) {
    boolean[] hasValue = new boolean[NUM_DOCS];
    switch (fieldDensity) {
      case DENSE:
        Arrays.fill(hasValue, true);
        break;
      case SPARSE:
        for (int i = 0; i < NUM_DOCS; i++) {
          hasValue[i] = rnd.nextBoolean(); // ~50% density
        }
        break;
    }
    return hasValue;
  }

  // --- Doc ID batch generation ---

  private int[] generateDocIdBatch() {
    Random rnd = new Random(123);
    int maxDoc = NUM_DOCS;
    int size = Math.min(batchSize, maxDoc);

    switch (distribution) {
      case CONTIGUOUS:
        return generateContiguousBatch(rnd, maxDoc, size);
      case SCATTERED_MEDIUM:
        return generateScatteredBatch(rnd, maxDoc, size, 10);
      case SCATTERED_WIDE:
        return generateScatteredBatch(rnd, maxDoc, size, 100);
      default:
        throw new IllegalStateException("Unknown distribution: " + distribution);
    }
  }

  /** Generates a nearly contiguous batch starting from a random offset. */
  private static int[] generateContiguousBatch(Random rnd, int maxDoc, int size) {
    int start = rnd.nextInt(Math.max(1, maxDoc - size));
    int[] docs = new int[size];
    for (int i = 0; i < size; i++) {
      docs[i] = start + i;
    }
    return docs;
  }

  /**
   * Generates a scattered batch with the given spread factor. Higher spread = wider scatter = higher
   * density ratio.
   */
  private static int[] generateScatteredBatch(Random rnd, int maxDoc, int size, int spreadFactor) {
    TreeSet<Integer> docSet = new TreeSet<>();
    while (docSet.size() < size) {
      docSet.add(rnd.nextInt(Math.min(maxDoc, size * spreadFactor)));
    }
    return docSet.stream().mapToInt(Integer::intValue).toArray();
  }

  /**
   * Adjusts global doc IDs to leaf-local doc IDs, filtering to docs within the leaf's range.
   * Returns a sorted array of leaf-local doc IDs.
   */
  private static int[] adjustDocsForLeaf(int[] globalDocs, int docBase, int maxDoc) {
    int count = 0;
    for (int doc : globalDocs) {
      int local = doc - docBase;
      if (local >= 0 && local < maxDoc) {
        count++;
      }
    }
    int[] result = new int[count];
    int idx = 0;
    for (int doc : globalDocs) {
      int local = doc - docBase;
      if (local >= 0 && local < maxDoc) {
        result[idx++] = local;
      }
    }
    return result;
  }
}
