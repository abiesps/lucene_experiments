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
package org.apache.lucene.codecs.lucene90;

import java.io.IOException;
import java.util.Arrays;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;

/**
 * Tests for {@link NumericDocValues#longValues} bulk read API. Validates that bulk reads produce
 * identical results to per-doc reads across all encoding variants in {@link
 * Lucene90DocValuesProducer}: constant (bpv=0), fixed BPV (table/GCD/plain), varying BPV, and
 * sparse variants of each.
 */
public class TestLucene90DocValuesBulkRead extends LuceneTestCase {

  // ---- Dense encoding variants ----

  /** Dense field, all docs have the same value (constant encoding, bpv=0). */
  public void testLongValuesDenseConstant() throws Exception {
    try (Directory dir = newDirectory()) {
      indexNumericDocs(dir, 500, i -> 42L, true);
      assertLongValuesMatchPerDoc(dir, "numeric", 500, 0L);
    }
  }

  /** Dense field, small values fitting in 8 bits (plain encoding, low bpv). */
  public void testLongValuesDenseLowBPV() throws Exception {
    try (Directory dir = newDirectory()) {
      indexNumericDocs(dir, 1000, i -> (long) (i % 200), true);
      assertLongValuesMatchPerDoc(dir, "numeric", 1000, 0L);
    }
  }

  /** Dense field, large values requiring high bpv. */
  public void testLongValuesDenseHighBPV() throws Exception {
    try (Directory dir = newDirectory()) {
      indexNumericDocs(dir, 1000, i -> (long) i * 1_000_000L + 999_999_999L, true);
      assertLongValuesMatchPerDoc(dir, "numeric", 1000, 0L);
    }
  }

  /** Dense field with GCD-compressible values (all multiples of 7 + offset 3). */
  public void testLongValuesDenseGCD() throws Exception {
    try (Directory dir = newDirectory()) {
      indexNumericDocs(dir, 1000, i -> 3L + 7L * i, true);
      assertLongValuesMatchPerDoc(dir, "numeric", 1000, 0L);
    }
  }

  /** Dense field with table-compressible values (few unique values). */
  public void testLongValuesDenseTable() throws Exception {
    try (Directory dir = newDirectory()) {
      long[] table = {10L, 20L, 30L, 40L, 50L};
      indexNumericDocs(dir, 1000, i -> table[i % table.length], true);
      assertLongValuesMatchPerDoc(dir, "numeric", 1000, 0L);
    }
  }

  // ---- Sparse encoding variants ----

  /** Sparse field — only even-numbered docs have values. */
  public void testLongValuesSparse() throws Exception {
    try (Directory dir = newDirectory()) {
      indexNumericDocs(dir, 1000, i -> (long) i * 100, false);
      assertLongValuesMatchPerDoc(dir, "numeric", 1000, -999L);
    }
  }

  /** Sparse field with constant value for docs that have it. */
  public void testLongValuesSparseConstant() throws Exception {
    try (Directory dir = newDirectory()) {
      indexNumericDocsSparseConstant(dir, 500, 77L);
      assertLongValuesMatchPerDoc(dir, "numeric", 500, -1L);
    }
  }

  // ---- Sparse DISI traversal tests ----
  // These test that traverseDISI correctly identifies which docs exist and maps
  // to the right DISI indices, which is critical for sparse longValues() correctness.

  /** Very sparse field — only 1 in 100 docs has a value. Tests DISI jump table traversal. */
  public void testLongValuesVerySparse() throws Exception {
    try (Directory dir = newDirectory()) {
      int numDocs = 10000;
      // Only every 100th doc has a value — forces DISI to use jump table
      indexNumericDocsSparsePattern(dir, numDocs, i -> (long) i * 7, i -> i % 100 == 0);
      assertLongValuesMatchPerDoc(dir, "numeric", numDocs, -1L);
    }
  }

  /** Sparse field with clustered values — docs 0-99 have values, 100-999 don't, 1000-1099 do. */
  public void testLongValuesSparseClusteredBlocks() throws Exception {
    try (Directory dir = newDirectory()) {
      int numDocs = 2000;
      indexNumericDocsSparsePattern(
          dir, numDocs, i -> (long) i, i -> (i / 100) % 2 == 0); // alternating blocks of 100
      assertLongValuesMatchPerDoc(dir, "numeric", numDocs, -1L);
    }
  }

  /** Sparse field where only the first doc has a value. */
  public void testLongValuesSparseFirstDocOnly() throws Exception {
    try (Directory dir = newDirectory()) {
      int numDocs = 500;
      indexNumericDocsSparsePattern(dir, numDocs, i -> 999L, i -> i == 0);
      assertLongValuesMatchPerDoc(dir, "numeric", numDocs, -1L);
    }
  }

  /** Sparse field where only the last doc has a value. */
  public void testLongValuesSparseLastDocOnly() throws Exception {
    try (Directory dir = newDirectory()) {
      int numDocs = 500;
      indexNumericDocsSparsePattern(dir, numDocs, i -> 999L, i -> i == numDocs - 1);
      assertLongValuesMatchPerDoc(dir, "numeric", numDocs, -1L);
    }
  }

  /**
   * Sparse field crossing DISI block boundaries (blocks are 65536 docs). Tests that traverseDISI
   * handles cross-block batches correctly.
   */
  public void testLongValuesSparseCrossBlock() throws Exception {
    try (Directory dir = newDirectory()) {
      // 70000 docs to cross the 65536 DISI block boundary
      int numDocs = 70000;
      // Values at doc 0, 65535, 65536, 69999 — straddles the block boundary
      indexNumericDocsSparsePattern(
          dir,
          numDocs,
          i -> (long) i,
          i -> i == 0 || i == 65535 || i == 65536 || i == numDocs - 1);
      assertLongValuesMatchPerDoc(dir, "numeric", numDocs, -1L);
    }
  }

  /** Sparse field with ~50% density — half the docs have values. */
  public void testLongValuesSparseHalfDensity() throws Exception {
    try (Directory dir = newDirectory()) {
      int numDocs = 2000;
      indexNumericDocsSparsePattern(dir, numDocs, i -> (long) i * 13 - 500, i -> i % 2 == 0);
      assertLongValuesMatchPerDoc(dir, "numeric", numDocs, Long.MIN_VALUE);
    }
  }

  /** Sparse field with random sparsity pattern. */
  public void testLongValuesSparseRandom() throws Exception {
    try (Directory dir = newDirectory()) {
      int numDocs = 3000;
      // Use a deterministic "random" pattern based on doc ID
      indexNumericDocsSparsePattern(
          dir, numDocs, i -> (long) i * 31 + 17, i -> ((i * 7 + 13) % 11) < 4);
      assertLongValuesMatchPerDoc(dir, "numeric", numDocs, -1L);
    }
  }

  // ---- Edge cases ----

  /** Bulk read with batch size larger than total docs. */
  public void testLongValuesBatchLargerThanDocs() throws Exception {
    try (Directory dir = newDirectory()) {
      indexNumericDocs(dir, 5000, i -> (long) i, true);
      assertLongValuesMatchPerDoc(dir, "numeric", 5000, 0L);
    }
  }

  /** Bulk read with a single doc. */
  public void testLongValuesSingleDoc() throws Exception {
    try (Directory dir = newDirectory()) {
      indexNumericDocs(dir, 1, i -> 12345L, true);
      assertLongValuesMatchPerDoc(dir, "numeric", 1, 0L);
    }
  }

  /** Bulk read with empty batch (size=0) should not throw. */
  public void testLongValuesEmptyBatch() throws Exception {
    try (Directory dir = newDirectory()) {
      indexNumericDocs(dir, 10, i -> (long) i, true);
      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        LeafReader leaf = reader.leaves().get(0).reader();
        NumericDocValues ndv = leaf.getNumericDocValues("numeric");
        assertNotNull(ndv);
        ndv.longValues(0, new int[0], new long[0], 0L);
      }
    }
  }

  /** Negative values and Long.MIN_VALUE/MAX_VALUE. */
  public void testLongValuesExtremeValues() throws Exception {
    try (Directory dir = newDirectory()) {
      indexNumericDocs(
          dir,
          100,
          i -> {
            if (i == 0) return Long.MIN_VALUE;
            if (i == 1) return Long.MAX_VALUE;
            if (i % 2 == 0) return -(long) i * 1_000_000_000L;
            return (long) i * 1_000_000_000L;
          },
          true);
      assertLongValuesMatchPerDoc(dir, "numeric", 100, 0L);
    }
  }

  // ---- Randomized property tests ----
  // These use large doc counts to ensure data spans multiple DISI blocks (65536 docs/block)
  // and multiple value data blocks, exercising cross-block prefetch and traversal.

  /**
   * Property test: for any random combination of doc count, value range, sparsity, and batch size,
   * longValues() must produce identical results to per-doc advanceExact + longValue.
   *
   * <p>Runs multiple iterations with randomized parameters. Uses 100K+ docs to span multiple DISI
   * blocks (each block is 65536 docs) and generate ~1MB+ of doc values data.
   */
  public void testLongValuesRandomProperty() throws Exception {
    int iterations = atLeast(5);
    for (int iter = 0; iter < iterations; iter++) {
      // Randomize doc count: 70K-200K to span 2-3 DISI blocks
      int numDocs = 70000 + random().nextInt(130000);
      // Randomize value range to trigger different BPV encodings
      int valueType = random().nextInt(5);
      IntToLong valueFunc =
          switch (valueType) {
            case 0 -> i -> 42L; // constant → bpv=0
            case 1 -> i -> (long) (i % 5); // table → few unique values
            case 2 -> i -> 100L + 7L * i; // GCD → all multiples of 7
            case 3 -> i -> (long) i; // sequential → moderate bpv
            default -> i -> random().nextLong(); // random → high bpv
          };
      // Randomize sparsity: 0.0 = all docs, 1.0 = no docs
      double skipRate = random().nextDouble() * 0.9; // 0% to 90% missing
      boolean allDocs = skipRate < 0.05; // treat <5% skip as dense
      long defaultValue = random().nextLong();

      try (Directory dir = newDirectory()) {
        indexNumericDocsSparsePattern(
            dir, numDocs, valueFunc, i -> allDocs || random().nextDouble() >= skipRate);
        assertLongValuesRandomBatches(dir, "numeric", numDocs, defaultValue);
      }
    }
  }

  /**
   * Property test with dense fields and large doc counts to generate ~1MB+ value data, ensuring
   * prefetchFixedBPV spans multiple cache blocks.
   */
  public void testLongValuesDenseLargeProperty() throws Exception {
    int iterations = atLeast(3);
    for (int iter = 0; iter < iterations; iter++) {
      // 200K docs × 8 bytes/value = ~1.6MB of value data
      int numDocs = 150000 + random().nextInt(100000);
      // Use values that require 32-64 bits to ensure large data blocks
      IntToLong valueFunc = i -> ((long) i << 20) | random().nextInt(1 << 20);
      long defaultValue = -1L;

      try (Directory dir = newDirectory()) {
        indexNumericDocs(dir, numDocs, valueFunc, true);
        assertLongValuesRandomBatches(dir, "numeric", numDocs, defaultValue);
      }
    }
  }

  /**
   * Property test with sparse fields crossing multiple DISI blocks. Uses very low density to
   * exercise DISI jump table prefetch across block boundaries.
   */
  public void testLongValuesSparseMultiBlockProperty() throws Exception {
    int iterations = atLeast(3);
    for (int iter = 0; iter < iterations; iter++) {
      // 200K docs across 3+ DISI blocks
      int numDocs = 200000 + random().nextInt(50000);
      // Very sparse: ~1% density
      int step = 50 + random().nextInt(150); // every 50-200th doc
      IntToLong valueFunc = i -> (long) i * 31 + 17;

      try (Directory dir = newDirectory()) {
        indexNumericDocsSparsePattern(dir, numDocs, valueFunc, i -> i % step == 0);
        assertLongValuesRandomBatches(dir, "numeric", numDocs, -1L);
      }
    }
  }

  // ---- usePerDocPrefetch unit tests ----

  /**
   * Leverage the same randomized value patterns as the existing Lucene doc values test suite
   * (NumericsVsStoredFields) but validate via longValues() bulk API. This covers all encoding
   * variants that Lucene90DocValuesProducer selects based on the data: constant, table, GCD, plain,
   * and varying BPV — at both dense and sparse densities.
   */
  public void testLongValuesAllEncodingsViaStoredFields() throws Exception {
    // Each supplier triggers a different encoding in Lucene90DocValuesProducer
    long[][] valuePatterns = {
      {0}, // constant → bpv=0
      {1, 2, 3, 4, 5}, // table → few unique values
      {0, 7, 14, 21, 28, 35, 42}, // GCD → all multiples of 7
      {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE}, // wide range → high bpv plain
    };
    double[] densities = {1.0, 0.5, 0.1, 0.01};

    for (long[] pattern : valuePatterns) {
      for (double density : densities) {
        int numDocs = 1000 + random().nextInt(4000);
        try (Directory dir = newDirectory()) {
          // Index with stored fields as ground truth
          IndexWriterConfig conf = new IndexWriterConfig();
          conf.setMaxBufferedDocs(numDocs + 1);
          long[] storedValues = new long[numDocs];
          boolean[] hasValue = new boolean[numDocs];
          try (IndexWriter w = new IndexWriter(dir, conf)) {
            for (int i = 0; i < numDocs; i++) {
              Document doc = new Document();
              if (random().nextDouble() < density) {
                long val = pattern[random().nextInt(pattern.length)];
                if (pattern.length > 2) {
                  // Add some noise for non-trivial patterns
                  val += random().nextInt(100);
                }
                doc.add(new NumericDocValuesField("dv", val));
                doc.add(new org.apache.lucene.document.StoredField("stored", val));
                storedValues[i] = val;
                hasValue[i] = true;
              }
              w.addDocument(doc);
            }
            w.forceMerge(1);
          }

          // Validate: longValues() must match stored field values
          long defaultValue = Long.MIN_VALUE + 42; // unlikely to collide
          try (DirectoryReader reader = DirectoryReader.open(dir)) {
            LeafReader leaf = reader.leaves().get(0).reader();
            NumericDocValues ndv = leaf.getNumericDocValues("dv");
            if (ndv == null) continue; // all docs missing

            // Full batch
            int[] docs = new int[numDocs];
            for (int i = 0; i < numDocs; i++) docs[i] = i;
            long[] actual = new long[numDocs];
            ndv.longValues(numDocs, docs, actual, defaultValue);

            for (int i = 0; i < numDocs; i++) {
              if (hasValue[i]) {
                assertEquals(
                    "Mismatch at doc " + i + " pattern=" + Arrays.toString(pattern)
                        + " density=" + density,
                    storedValues[i], actual[i]);
              } else {
                assertEquals(
                    "Missing doc " + i + " should have default",
                    defaultValue, actual[i]);
              }
            }
          }
        }
      }
    }
  }

  /**
   * Replicate the exact value patterns from the existing Lucene test suite's
   * doTestNumericsVsStoredFields (boolean, byte, short, int, long ranges) and validate via
   * longValues(). Uses >256 docs to trigger all storage layouts. Tests at both dense (100%) and
   * sparse (10%) density, matching the existing suite's coverage.
   */
  public void testLongValuesMatchesExistingSuitePatterns() throws Exception {
    IntToLong[] valueSuppliers = {
      i -> random().nextBoolean() ? 0L : 1L,
      i -> (long) TestUtil.nextInt(random(), Byte.MIN_VALUE, Byte.MAX_VALUE),
      i -> (long) TestUtil.nextInt(random(), Short.MIN_VALUE, Short.MAX_VALUE),
      i -> (long) TestUtil.nextInt(random(), Integer.MIN_VALUE, Integer.MAX_VALUE),
      i -> random().nextLong(),
    };
    double[] densities = {1.0, 0.1};

    for (IntToLong supplier : valueSuppliers) {
      for (double density : densities) {
        int numDocs = TestUtil.nextInt(random(), 300, 1000);
        try (Directory dir = newDirectory()) {
          IndexWriterConfig conf = new IndexWriterConfig();
          conf.setMaxBufferedDocs(numDocs + 1);
          long[] groundTruth = new long[numDocs];
          boolean[] hasValue = new boolean[numDocs];
          long defaultValue = Long.MIN_VALUE + 7;

          try (IndexWriter w = new IndexWriter(dir, conf)) {
            for (int i = 0; i < numDocs; i++) {
              Document doc = new Document();
              if (random().nextDouble() < density) {
                long val = supplier.apply(i);
                doc.add(new NumericDocValuesField("dv", val));
                groundTruth[i] = val;
                hasValue[i] = true;
              }
              w.addDocument(doc);
            }
            w.forceMerge(1);
          }

          try (DirectoryReader reader = DirectoryReader.open(dir)) {
            for (LeafReaderContext ctx : reader.leaves()) {
              LeafReader leaf = ctx.reader();
              NumericDocValues ndv = leaf.getNumericDocValues("dv");
              if (ndv == null) continue;

              int maxDoc = leaf.maxDoc();
              int[] docs = new int[maxDoc];
              for (int i = 0; i < maxDoc; i++) docs[i] = i;
              long[] actual = new long[maxDoc];
              ndv.longValues(maxDoc, docs, actual, defaultValue);

              for (int i = 0; i < maxDoc; i++) {
                if (hasValue[i]) {
                  assertEquals("Doc " + i, groundTruth[i], actual[i]);
                } else {
                  assertEquals("Doc " + i + " default", defaultValue, actual[i]);
                }
              }
            }
          }
        }
      }
    }
  }

  // ---- usePerDocPrefetch unit tests ----

  public void testUsePerDocPrefetchDense() {
    // Dense batch: densityRatio=1, bpv=8, blockCapacity=32768 → contiguous
    assertFalse(Lucene90DocValuesProducer.usePerDocPrefetch(1, 8, 1));
  }

  public void testUsePerDocPrefetchSparse() {
    // Sparse batch: densityRatio=100000, bpv=8, blockCapacity=32768 → per-doc
    assertTrue(Lucene90DocValuesProducer.usePerDocPrefetch(100000, 8, 1));
  }

  public void testUsePerDocPrefetchBPVZero() {
    // bpv=0 (constant) → no IO needed, always false
    assertFalse(Lucene90DocValuesProducer.usePerDocPrefetch(100000, 0, 1));
  }

  public void testUsePerDocPrefetchBoundary() {
    int bpv = 8;
    long blockCapacity = (32768L * 8) / bpv;
    // At boundary → contiguous
    assertFalse(Lucene90DocValuesProducer.usePerDocPrefetch(blockCapacity, bpv, 1));
    // Just over → per-doc
    assertTrue(Lucene90DocValuesProducer.usePerDocPrefetch(blockCapacity + 1, bpv, 1));
  }

  public void testUsePerDocPrefetchHighBPV() {
    // bpv=64 → readSize=8, blockCapacity=32768*8/64=4096
    assertFalse(Lucene90DocValuesProducer.usePerDocPrefetch(4096, 64, 1));
    assertTrue(Lucene90DocValuesProducer.usePerDocPrefetch(4097, 64, 1));
  }

  // ---- Helper methods ----

  @FunctionalInterface
  interface IntToLong {
    long apply(int i);
  }

  private void indexNumericDocs(Directory dir, int numDocs, IntToLong valueFunc, boolean allDocs)
      throws IOException {
    IndexWriterConfig conf = new IndexWriterConfig();
    conf.setMaxBufferedDocs(numDocs + 1);
    try (IndexWriter w = new IndexWriter(dir, conf)) {
      for (int i = 0; i < numDocs; i++) {
        Document doc = new Document();
        if (allDocs || i % 2 == 0) {
          doc.add(new NumericDocValuesField("numeric", valueFunc.apply(i)));
        }
        w.addDocument(doc);
      }
      w.forceMerge(1);
    }
  }

  private void indexNumericDocsSparseConstant(Directory dir, int numDocs, long value)
      throws IOException {
    IndexWriterConfig conf = new IndexWriterConfig();
    conf.setMaxBufferedDocs(numDocs + 1);
    try (IndexWriter w = new IndexWriter(dir, conf)) {
      for (int i = 0; i < numDocs; i++) {
        Document doc = new Document();
        if (i % 3 == 0) {
          doc.add(new NumericDocValuesField("numeric", value));
        }
        w.addDocument(doc);
      }
      w.forceMerge(1);
    }
  }

  /** Index with arbitrary sparsity pattern controlled by a predicate. */
  private void indexNumericDocsSparsePattern(
      Directory dir, int numDocs, IntToLong valueFunc, java.util.function.IntPredicate hasValue)
      throws IOException {
    IndexWriterConfig conf = new IndexWriterConfig();
    conf.setMaxBufferedDocs(numDocs + 1);
    try (IndexWriter w = new IndexWriter(dir, conf)) {
      for (int i = 0; i < numDocs; i++) {
        Document doc = new Document();
        if (hasValue.test(i)) {
          doc.add(new NumericDocValuesField("numeric", valueFunc.apply(i)));
        }
        w.addDocument(doc);
      }
      w.forceMerge(1);
    }
  }

  /**
   * Assert that longValues() bulk read produces identical results to per-doc advanceExact +
   * longValue for all docs, using three access patterns: full batch, small batches, and scattered.
   */
  private void assertLongValuesMatchPerDoc(
      Directory dir, String field, int numDocs, long defaultValue) throws IOException {
    try (DirectoryReader reader = DirectoryReader.open(dir)) {
      assertEquals(1, reader.leaves().size());
      LeafReader leaf = reader.leaves().get(0).reader();

      // Ground truth via per-doc API
      long[] expected = new long[numDocs];
      {
        NumericDocValues ndv = leaf.getNumericDocValues(field);
        if (ndv == null) {
          Arrays.fill(expected, defaultValue);
        } else {
          for (int i = 0; i < numDocs; i++) {
            expected[i] = ndv.advanceExact(i) ? ndv.longValue() : defaultValue;
          }
        }
      }

      // Pattern 1: Full batch
      {
        NumericDocValues ndv = leaf.getNumericDocValues(field);
        if (ndv != null) {
          int[] docs = new int[numDocs];
          for (int i = 0; i < numDocs; i++) docs[i] = i;
          long[] actual = new long[numDocs];
          ndv.longValues(numDocs, docs, actual, defaultValue);
          assertArrayEquals("Full batch mismatch", expected, actual);
        }
      }

      // Pattern 2: Small batches (64 docs)
      {
        NumericDocValues ndv = leaf.getNumericDocValues(field);
        if (ndv != null) {
          int batchSize = 64;
          int[] docs = new int[batchSize];
          long[] actual = new long[batchSize];
          for (int start = 0; start < numDocs; start += batchSize) {
            int count = Math.min(batchSize, numDocs - start);
            for (int i = 0; i < count; i++) docs[i] = start + i;
            ndv.longValues(count, docs, actual, defaultValue);
            for (int i = 0; i < count; i++) {
              assertEquals("Batch mismatch at doc " + (start + i), expected[start + i], actual[i]);
            }
          }
        }
      }

      // Pattern 3: Scattered (every 7th doc)
      {
        NumericDocValues ndv = leaf.getNumericDocValues(field);
        if (ndv != null && numDocs > 10) {
          int count = 0;
          int[] docs = new int[numDocs / 7 + 1];
          for (int i = 0; i < numDocs; i += 7) docs[count++] = i;
          long[] actual = new long[count];
          ndv.longValues(count, docs, actual, defaultValue);
          for (int i = 0; i < count; i++) {
            assertEquals("Scattered mismatch at doc " + docs[i], expected[docs[i]], actual[i]);
          }
        }
      }
    }
  }

  /**
   * Assert with randomized batch sizes and access patterns. Used by property tests.
   */
  private void assertLongValuesRandomBatches(
      Directory dir, String field, int numDocs, long defaultValue) throws IOException {
    try (DirectoryReader reader = DirectoryReader.open(dir)) {
      assertEquals(1, reader.leaves().size());
      LeafReader leaf = reader.leaves().get(0).reader();

      // Ground truth
      long[] expected = new long[numDocs];
      {
        NumericDocValues ndv = leaf.getNumericDocValues(field);
        if (ndv == null) {
          Arrays.fill(expected, defaultValue);
          return;
        }
        for (int i = 0; i < numDocs; i++) {
          expected[i] = ndv.advanceExact(i) ? ndv.longValue() : defaultValue;
        }
      }

      // Random batch sizes: small, medium, large
      int[] batchSizes = {
        16 + random().nextInt(113),
        256 + random().nextInt(1793),
        numDocs
      };
      for (int batchSize : batchSizes) {
        NumericDocValues ndv = leaf.getNumericDocValues(field);
        if (ndv == null) continue;
        int[] docs = new int[batchSize];
        long[] actual = new long[batchSize];
        for (int start = 0; start < numDocs; start += batchSize) {
          int count = Math.min(batchSize, numDocs - start);
          for (int i = 0; i < count; i++) docs[i] = start + i;
          ndv.longValues(count, docs, actual, defaultValue);
          for (int i = 0; i < count; i++) {
            assertEquals(
                "Batch(" + batchSize + ") mismatch at doc " + (start + i),
                expected[start + i],
                actual[i]);
          }
        }
      }

      // Random scatter steps
      for (int s = 0; s < 2; s++) {
        int step = 3 + random().nextInt(200);
        NumericDocValues ndv = leaf.getNumericDocValues(field);
        if (ndv == null || numDocs <= step) continue;
        int count = 0;
        int[] docs = new int[numDocs / step + 1];
        for (int i = 0; i < numDocs; i += step) docs[count++] = i;
        long[] actual = new long[count];
        ndv.longValues(count, docs, actual, defaultValue);
        for (int i = 0; i < count; i++) {
          assertEquals(
              "Scattered(" + step + ") mismatch at doc " + docs[i],
              expected[docs[i]],
              actual[i]);
        }
      }

      // Random subset: pick N random doc IDs (sorted, no dupes)
      {
        int subsetSize = Math.min(1000, numDocs / 2);
        java.util.TreeSet<Integer> picked = new java.util.TreeSet<>();
        while (picked.size() < subsetSize) {
          picked.add(random().nextInt(numDocs));
        }
        int[] randomDocs = new int[picked.size()];
        int idx = 0;
        for (int doc : picked) randomDocs[idx++] = doc;

        NumericDocValues ndv = leaf.getNumericDocValues(field);
        if (ndv != null) {
          long[] actual = new long[randomDocs.length];
          ndv.longValues(randomDocs.length, randomDocs, actual, defaultValue);
          for (int i = 0; i < randomDocs.length; i++) {
            assertEquals(
                "Random subset mismatch at doc " + randomDocs[i],
                expected[randomDocs[i]],
                actual[i]);
          }
        }
      }
    }
  }
}
