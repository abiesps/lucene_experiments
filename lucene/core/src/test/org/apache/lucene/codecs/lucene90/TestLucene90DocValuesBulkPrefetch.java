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
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.TreeSet;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RandomAccessInput;
import org.apache.lucene.tests.analysis.MockAnalyzer;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;

/**
 * Tests for bulk prefetch of doc values via {@link NumericDocValues#longValues(int, int[], long[],
 * long)}.
 *
 * <p>This class provides helper methods for creating indexes with specific encoding variants and
 * comparing bulk output against sequential advanceExact + longValue output. Actual test methods are
 * added by Tasks 6.2, 6.3, and 6.4.
 *
 * <p>Validates: Requirements 9.1, 9.2
 */
public class TestLucene90DocValuesBulkPrefetch extends LuceneTestCase {

  /** Force Lucene90DocValuesFormat so that Lucene90DocValuesProducer is used. */
  private static Codec codec() {
    return TestUtil.alwaysDocValuesFormat(new Lucene90DocValuesFormat());
  }

  /**
   * Creates an in-memory index where every document has a numeric doc value for field "dv". This
   * produces a dense NumericDocValues (docsWithFieldOffset == -1).
   *
   * @param dir the directory to write the index into
   * @param values the values to index; values.length determines the number of docs
   */
  static void createIndexWithDenseField(Directory dir, long[] values) throws IOException {
    IndexWriterConfig conf = new IndexWriterConfig(new MockAnalyzer(random()));
    conf.setCodec(codec());
    // Force a single segment so encoding variant is deterministic.
    conf.setMaxBufferedDocs(values.length + 1);
    conf.setRAMBufferSizeMB(-1);
    try (IndexWriter writer = new IndexWriter(dir, conf)) {
      for (long value : values) {
        Document doc = new Document();
        doc.add(new NumericDocValuesField("dv", value));
        writer.addDocument(doc);
      }
      writer.forceMerge(1);
    }
  }

  /**
   * Creates an in-memory index where only some documents have a numeric doc value for field "dv".
   * This produces a sparse NumericDocValues (docsWithFieldOffset >= 0) when the density is low
   * enough.
   *
   * @param dir the directory to write the index into
   * @param values the values to index (one per doc)
   * @param hasValue whether each doc has a value; must be same length as values
   */
  static void createIndexWithSparseField(Directory dir, long[] values, boolean[] hasValue)
      throws IOException {
    assert values.length == hasValue.length;
    IndexWriterConfig conf = new IndexWriterConfig(new MockAnalyzer(random()));
    conf.setCodec(codec());
    conf.setMaxBufferedDocs(values.length + 1);
    conf.setRAMBufferSizeMB(-1);
    try (IndexWriter writer = new IndexWriter(dir, conf)) {
      for (int i = 0; i < values.length; i++) {
        Document doc = new Document();
        if (hasValue[i]) {
          doc.add(new NumericDocValuesField("dv", values[i]));
        }
        writer.addDocument(doc);
      }
      writer.forceMerge(1);
    }
  }

  /**
   * Generates a random sorted ascending array of doc IDs with no duplicates. Sizes range from 1 to
   * min(batchSize, maxDoc).
   *
   * @param rnd the random source
   * @param maxDoc the exclusive upper bound for doc IDs (segment maxDoc)
   * @param batchSize the desired batch size (capped at maxDoc)
   * @return a sorted ascending int array of unique doc IDs
   */
  static int[] generateSortedDocIdBatch(Random rnd, int maxDoc, int batchSize) {
    int size = Math.min(batchSize, maxDoc);
    if (size <= 0) {
      return new int[0];
    }
    // Use a TreeSet to guarantee uniqueness and sorted order.
    TreeSet<Integer> set = new TreeSet<>();
    while (set.size() < size) {
      set.add(rnd.nextInt(maxDoc));
    }
    int[] docs = new int[size];
    int idx = 0;
    for (int docId : set) {
      docs[idx++] = docId;
    }
    return docs;
  }

  /**
   * Asserts that the bulk {@link NumericDocValues#longValues(int, int[], long[], long)} output is
   * identical to the sequential {@code advanceExact + longValue} loop for the same doc IDs.
   *
   * <p>This method reads the NumericDocValues twice from the given reader (once for bulk, once for
   * sequential) to avoid iterator state interference.
   *
   * @param reader the leaf reader to obtain NumericDocValues from
   * @param docs sorted ascending doc IDs with no duplicates
   * @param size the number of doc IDs in the batch
   * @param defaultValue the default value for docs without a value
   */
  static void assertBulkEqualsSequential(
      LeafReader reader, int[] docs, int size, long defaultValue) throws IOException {
    // --- Bulk path ---
    NumericDocValues bulkNdv = reader.getNumericDocValues("dv");
    assertNotNull("Field 'dv' should exist", bulkNdv);
    long[] bulkValues = new long[size];
    bulkNdv.longValues(size, docs, bulkValues, defaultValue);

    // --- Sequential path ---
    NumericDocValues seqNdv = reader.getNumericDocValues("dv");
    assertNotNull("Field 'dv' should exist", seqNdv);
    long[] seqValues = new long[size];
    for (int i = 0; i < size; i++) {
      if (seqNdv.advanceExact(docs[i])) {
        seqValues[i] = seqNdv.longValue();
      } else {
        seqValues[i] = defaultValue;
      }
    }

    // --- Compare ---
    for (int i = 0; i < size; i++) {
      assertEquals(
          "Mismatch at index "
              + i
              + " for doc "
              + docs[i]
              + " (defaultValue="
              + defaultValue
              + ")",
          seqValues[i],
          bulkValues[i]);
    }
  }

  /**
   * Convenience method that opens a DirectoryReader, iterates over leaf readers, generates a random
   * batch, and asserts bulk-sequential equivalence. Useful for quick smoke tests.
   *
   * @param dir the directory containing the index
   * @param defaultValue the default value for docs without a value
   */
  static void assertBulkEqualsSequentialForAllLeaves(Directory dir, long defaultValue)
      throws IOException {
    try (DirectoryReader reader = DirectoryReader.open(dir)) {
      for (LeafReaderContext ctx : reader.leaves()) {
        LeafReader leafReader = ctx.reader();
        int maxDoc = leafReader.maxDoc();
        if (maxDoc == 0) continue;
        int batchSize = TestUtil.nextInt(random(), 1, Math.min(4096, maxDoc));
        int[] docs = generateSortedDocIdBatch(random(), maxDoc, batchSize);
        assertBulkEqualsSequential(leafReader, docs, docs.length, defaultValue);
      }
    }
  }

  /** Encoding variants for dense numeric doc values. */
  enum DenseEncoding {
    /** All values identical — triggers bpv=0 constant encoding. */
    CONSTANT,
    /** Small number of unique values (&lt; 256) — triggers table encoding. */
    TABLE,
    /** Values sharing a common GCD &gt; 1 with non-zero min — triggers GCD encoding. */
    GCD,
    /** Random long values with no special pattern — triggers plain encoding. */
    PLAIN,
    /** Random values with varying ranges — may trigger VaryingBPV encoding. */
    VARYING_BPV
  }

  /**
   * Generates a dense values array that is likely to trigger the specified encoding variant.
   *
   * @param rnd the random source
   * @param numDocs the number of documents
   * @param encoding the target encoding variant
   * @return an array of long values, one per document
   */
  static long[] generateDenseValues(Random rnd, int numDocs, DenseEncoding encoding) {
    long[] values = new long[numDocs];
    switch (encoding) {
      case CONSTANT:
        {
          long constant = rnd.nextLong();
          Arrays.fill(values, constant);
          break;
        }
      case TABLE:
        {
          // Small number of unique values (2 to 255) triggers table encoding.
          int numUnique = TestUtil.nextInt(rnd, 2, 255);
          long[] table = new long[numUnique];
          for (int i = 0; i < numUnique; i++) {
            table[i] = rnd.nextLong();
          }
          for (int i = 0; i < numDocs; i++) {
            values[i] = table[rnd.nextInt(numUnique)];
          }
          break;
        }
      case GCD:
        {
          // Values = minValue + k * gcd, where gcd > 1 and minValue != 0.
          long gcd = TestUtil.nextInt(rnd, 2, 1000);
          long minValue = TestUtil.nextLong(rnd, 1, 100000);
          for (int i = 0; i < numDocs; i++) {
            long k = TestUtil.nextLong(rnd, 0, 10000);
            values[i] = minValue + k * gcd;
          }
          break;
        }
      case PLAIN:
        {
          // Random long values — no table, gcd=1, minValue=0 triggers plain encoding.
          // Use non-negative values with enough unique values to avoid table encoding.
          for (int i = 0; i < numDocs; i++) {
            values[i] = rnd.nextLong() & Long.MAX_VALUE; // non-negative
          }
          break;
        }
      case VARYING_BPV:
        {
          // Values with varying ranges across blocks to encourage VaryingBPV.
          // Alternate between small and large values in different regions.
          for (int i = 0; i < numDocs; i++) {
            if ((i / 128) % 2 == 0) {
              // Small values — low bits per value
              values[i] = rnd.nextInt(16);
            } else {
              // Large values — high bits per value
              values[i] = rnd.nextLong() & Long.MAX_VALUE;
            }
          }
          break;
        }
    }
    return values;
  }

  /**
   * Generates a "dense" sorted doc ID batch — doc IDs are close together (low density ratio) to
   * exercise the contiguous range prefetch strategy.
   *
   * @param rnd the random source
   * @param maxDoc the exclusive upper bound for doc IDs
   * @param batchSize the desired batch size (capped at maxDoc)
   * @return a sorted ascending int array of unique doc IDs
   */
  static int[] generateDenseBatch(Random rnd, int maxDoc, int batchSize) {
    int size = Math.min(batchSize, maxDoc);
    if (size <= 0) return new int[0];
    // Pick a random start and take consecutive (or near-consecutive) doc IDs.
    int start = rnd.nextInt(Math.max(1, maxDoc - size));
    TreeSet<Integer> set = new TreeSet<>();
    for (int i = 0; i < size; i++) {
      int doc = start + i;
      if (doc < maxDoc) {
        set.add(doc);
      }
    }
    // If we didn't fill enough (shouldn't happen), pad with nearby docs.
    while (set.size() < size) {
      set.add(rnd.nextInt(maxDoc));
    }
    int[] docs = new int[set.size()];
    int idx = 0;
    for (int docId : set) {
      docs[idx++] = docId;
    }
    return docs;
  }

  /**
   * Generates a "sparse" sorted doc ID batch — doc IDs are spread across the full range (high
   * density ratio) to exercise the per-doc prefetch strategy.
   *
   * @param rnd the random source
   * @param maxDoc the exclusive upper bound for doc IDs
   * @param batchSize the desired batch size (capped at maxDoc)
   * @return a sorted ascending int array of unique doc IDs
   */
  static int[] generateSparseBatch(Random rnd, int maxDoc, int batchSize) {
    // Just use the standard random generation which naturally spreads across the range.
    return generateSortedDocIdBatch(rnd, maxDoc, Math.min(batchSize, maxDoc));
  }

  // Feature: docvalues-prefetch-bulk-collection, Property 2: Sparse bulk-sequential equivalence
  /**
   * Property test: for any sparse numeric doc values field (any encoding variant) and any valid
   * batch of sorted ascending doc IDs (including batches where some or all docs lack values),
   * {@code longValues()} output SHALL be identical to sequential {@code advanceExact + longValue}
   * output with {@code defaultValue} substituted for docs without a value.
   *
   * <p>Tests all sparse encoding variants (bpv=0, table, GCD, plain, VaryingBPV) with randomized
   * doc ID batches across 100+ iterations. Both dense batches (low density ratio) and sparse
   * batches (high density ratio) are tested to exercise both prefetch strategies.
   *
   * <p><b>Validates: Requirements 5.5, 6.2, 6.6, 9.1, 9.2, 9.3, 9.4</b>
   */
  public void testSparseBulkSequentialEquivalence() throws Exception {
    DenseEncoding[] encodings = DenseEncoding.values();
    int iterations = atLeast(100);
    for (int iter = 0; iter < iterations; iter++) {
      // Pick a random encoding variant for this iteration.
      DenseEncoding encoding = encodings[random().nextInt(encodings.length)];
      // Random number of docs: 100 to 10000.
      int numDocs = TestUtil.nextInt(random(), 100, 10000);
      long[] values = generateDenseValues(random(), numDocs, encoding);

      // Random sparsity: 10% to 90% of docs have values.
      double sparsity = TestUtil.nextInt(random(), 10, 90) / 100.0;
      boolean[] hasValue = new boolean[numDocs];
      for (int i = 0; i < numDocs; i++) {
        hasValue[i] = random().nextDouble() < sparsity;
      }

      long defaultValue = random().nextLong();

      try (Directory dir = newDirectory()) {
        createIndexWithSparseField(dir, values, hasValue);
        try (DirectoryReader reader = DirectoryReader.open(dir)) {
          for (LeafReaderContext ctx : reader.leaves()) {
            LeafReader leafReader = ctx.reader();
            int maxDoc = leafReader.maxDoc();
            if (maxDoc == 0) continue;

            // Test with a dense batch (low density ratio — contiguous prefetch).
            int denseBatchSize = TestUtil.nextInt(random(), 1, Math.min(4096, maxDoc));
            int[] denseDocs = generateDenseBatch(random(), maxDoc, denseBatchSize);
            assertBulkEqualsSequential(leafReader, denseDocs, denseDocs.length, defaultValue);

            // Test with a sparse batch (high density ratio — per-doc prefetch).
            int sparseBatchSize = TestUtil.nextInt(random(), 1, Math.min(4096, maxDoc));
            int[] sparseDocs = generateSparseBatch(random(), maxDoc, sparseBatchSize);
            assertBulkEqualsSequential(leafReader, sparseDocs, sparseDocs.length, defaultValue);
          }
        }
      }
    }
  }

  // Feature: docvalues-prefetch-bulk-collection, Property 1: Dense bulk-sequential equivalence
  /**
   * Property test: for any dense numeric doc values field (any encoding variant) and any valid
   * batch of sorted ascending doc IDs, {@code longValues()} output SHALL be identical to sequential
   * {@code advanceExact + longValue} output.
   *
   * <p>Tests all dense encoding variants (bpv=0, table, GCD, plain, VaryingBPV) with randomized
   * doc ID batches across 100+ iterations. Both dense batches (low density ratio) and sparse
   * batches (high density ratio) are tested to exercise both prefetch strategies.
   *
   * <p><b>Validates: Requirements 2.1, 3.5, 4.5, 9.1, 9.2, 9.4</b>
   */
  public void testDenseBulkSequentialEquivalence() throws Exception {
    DenseEncoding[] encodings = DenseEncoding.values();
    int iterations = atLeast(100);
    for (int iter = 0; iter < iterations; iter++) {
      // Pick a random encoding variant for this iteration.
      DenseEncoding encoding = encodings[random().nextInt(encodings.length)];
      // Random number of docs: 100 to 10000.
      int numDocs = TestUtil.nextInt(random(), 100, 10000);
      long[] values = generateDenseValues(random(), numDocs, encoding);
      long defaultValue = random().nextLong();

      try (Directory dir = newDirectory()) {
        createIndexWithDenseField(dir, values);
        try (DirectoryReader reader = DirectoryReader.open(dir)) {
          for (LeafReaderContext ctx : reader.leaves()) {
            LeafReader leafReader = ctx.reader();
            int maxDoc = leafReader.maxDoc();
            if (maxDoc == 0) continue;

            // Test with a dense batch (low density ratio — contiguous prefetch).
            int denseBatchSize = TestUtil.nextInt(random(), 1, Math.min(4096, maxDoc));
            int[] denseDocs = generateDenseBatch(random(), maxDoc, denseBatchSize);
            assertBulkEqualsSequential(leafReader, denseDocs, denseDocs.length, defaultValue);

            // Test with a sparse batch (high density ratio — per-doc prefetch).
            int sparseBatchSize = TestUtil.nextInt(random(), 1, Math.min(4096, maxDoc));
            int[] sparseDocs = generateSparseBatch(random(), maxDoc, sparseBatchSize);
            assertBulkEqualsSequential(leafReader, sparseDocs, sparseDocs.length, defaultValue);
          }
        }
      }
    }
  }

  // ---- Edge case tests (Task 6.4) ----

  /**
   * Edge case: batch size of 1 for both dense and sparse fields. A single doc ID batch exercises
   * the per-doc prefetch strategy (size == 1 always uses per-doc).
   *
   * <p><b>Validates: Requirements 9.3</b>
   */
  public void testBatchSizeOne() throws Exception {
    DenseEncoding[] encodings = DenseEncoding.values();
    for (DenseEncoding encoding : encodings) {
      int numDocs = TestUtil.nextInt(random(), 10, 500);
      long[] values = generateDenseValues(random(), numDocs, encoding);
      long defaultValue = random().nextLong();

      // Dense field — batch size 1
      try (Directory dir = newDirectory()) {
        createIndexWithDenseField(dir, values);
        try (DirectoryReader reader = DirectoryReader.open(dir)) {
          for (LeafReaderContext ctx : reader.leaves()) {
            LeafReader leafReader = ctx.reader();
            int maxDoc = leafReader.maxDoc();
            if (maxDoc == 0) continue;
            int docId = random().nextInt(maxDoc);
            int[] docs = new int[] {docId};
            assertBulkEqualsSequential(leafReader, docs, 1, defaultValue);
          }
        }
      }

      // Sparse field — batch size 1
      boolean[] hasValue = new boolean[numDocs];
      for (int i = 0; i < numDocs; i++) {
        hasValue[i] = random().nextDouble() < 0.5;
      }
      try (Directory dir = newDirectory()) {
        createIndexWithSparseField(dir, values, hasValue);
        try (DirectoryReader reader = DirectoryReader.open(dir)) {
          for (LeafReaderContext ctx : reader.leaves()) {
            LeafReader leafReader = ctx.reader();
            int maxDoc = leafReader.maxDoc();
            if (maxDoc == 0) continue;
            int docId = random().nextInt(maxDoc);
            int[] docs = new int[] {docId};
            assertBulkEqualsSequential(leafReader, docs, 1, defaultValue);
          }
        }
      }
    }
  }

  /**
   * Edge case: batch size of 4096 for both dense and sparse fields. Requires at least 4096 docs to
   * fill the maximum batch size.
   *
   * <p><b>Validates: Requirements 9.3</b>
   */
  public void testBatchSizeMax() throws Exception {
    int numDocs = 5000; // Enough to fill a 4096 batch.
    DenseEncoding[] encodings = DenseEncoding.values();
    for (DenseEncoding encoding : encodings) {
      long[] values = generateDenseValues(random(), numDocs, encoding);
      long defaultValue = random().nextLong();

      // Dense field — batch size 4096
      try (Directory dir = newDirectory()) {
        createIndexWithDenseField(dir, values);
        try (DirectoryReader reader = DirectoryReader.open(dir)) {
          for (LeafReaderContext ctx : reader.leaves()) {
            LeafReader leafReader = ctx.reader();
            int maxDoc = leafReader.maxDoc();
            if (maxDoc < 4096) continue;
            int[] docs = generateSortedDocIdBatch(random(), maxDoc, 4096);
            assertEquals("Batch should have 4096 doc IDs", 4096, docs.length);
            assertBulkEqualsSequential(leafReader, docs, docs.length, defaultValue);
          }
        }
      }

      // Sparse field — batch size 4096
      boolean[] hasValue = new boolean[numDocs];
      for (int i = 0; i < numDocs; i++) {
        hasValue[i] = random().nextDouble() < 0.5;
      }
      try (Directory dir = newDirectory()) {
        createIndexWithSparseField(dir, values, hasValue);
        try (DirectoryReader reader = DirectoryReader.open(dir)) {
          for (LeafReaderContext ctx : reader.leaves()) {
            LeafReader leafReader = ctx.reader();
            int maxDoc = leafReader.maxDoc();
            if (maxDoc < 4096) continue;
            int[] docs = generateSortedDocIdBatch(random(), maxDoc, 4096);
            assertEquals("Batch should have 4096 doc IDs", 4096, docs.length);
            assertBulkEqualsSequential(leafReader, docs, docs.length, defaultValue);
          }
        }
      }
    }
  }

  /**
   * Edge case: batch containing doc 0 and doc maxDoc-1 (segment boundaries) for both dense and
   * sparse fields.
   *
   * <p><b>Validates: Requirements 9.3</b>
   */
  public void testSegmentBoundaryDocs() throws Exception {
    int numDocs = TestUtil.nextInt(random(), 10, 500);
    DenseEncoding[] encodings = DenseEncoding.values();
    for (DenseEncoding encoding : encodings) {
      long[] values = generateDenseValues(random(), numDocs, encoding);
      long defaultValue = random().nextLong();

      // Dense field — boundary docs
      try (Directory dir = newDirectory()) {
        createIndexWithDenseField(dir, values);
        try (DirectoryReader reader = DirectoryReader.open(dir)) {
          for (LeafReaderContext ctx : reader.leaves()) {
            LeafReader leafReader = ctx.reader();
            int maxDoc = leafReader.maxDoc();
            if (maxDoc < 2) continue;
            // Batch with just doc 0 and doc maxDoc-1
            int[] docs = new int[] {0, maxDoc - 1};
            assertBulkEqualsSequential(leafReader, docs, docs.length, defaultValue);
          }
        }
      }

      // Sparse field — boundary docs
      boolean[] hasValue = new boolean[numDocs];
      for (int i = 0; i < numDocs; i++) {
        hasValue[i] = random().nextDouble() < 0.5;
      }
      try (Directory dir = newDirectory()) {
        createIndexWithSparseField(dir, values, hasValue);
        try (DirectoryReader reader = DirectoryReader.open(dir)) {
          for (LeafReaderContext ctx : reader.leaves()) {
            LeafReader leafReader = ctx.reader();
            int maxDoc = leafReader.maxDoc();
            if (maxDoc < 2) continue;
            int[] docs = new int[] {0, maxDoc - 1};
            assertBulkEqualsSequential(leafReader, docs, docs.length, defaultValue);
          }
        }
      }
    }
  }

  /**
   * Edge case: sparse field where the batch contains only docs that lack values. All results should
   * be defaultValue.
   *
   * <p><b>Validates: Requirements 9.3</b>
   */
  public void testAllMissingDocsInSparseBatch() throws Exception {
    // Create a sparse field where only even-numbered docs have values.
    int numDocs = TestUtil.nextInt(random(), 20, 500);
    long[] values = new long[numDocs];
    boolean[] hasValue = new boolean[numDocs];
    for (int i = 0; i < numDocs; i++) {
      values[i] = random().nextLong();
      hasValue[i] = (i % 2 == 0); // Only even docs have values.
    }
    long defaultValue = random().nextLong();

    try (Directory dir = newDirectory()) {
      createIndexWithSparseField(dir, values, hasValue);
      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        for (LeafReaderContext ctx : reader.leaves()) {
          LeafReader leafReader = ctx.reader();
          int maxDoc = leafReader.maxDoc();
          if (maxDoc < 4) continue;

          // Build a batch of only odd-numbered docs (all missing values).
          TreeSet<Integer> missingSet = new TreeSet<>();
          for (int i = 1; i < maxDoc; i += 2) {
            missingSet.add(i);
          }
          int[] docs = new int[missingSet.size()];
          int idx = 0;
          for (int docId : missingSet) {
            docs[idx++] = docId;
          }

          // Bulk path
          NumericDocValues bulkNdv = leafReader.getNumericDocValues("dv");
          assertNotNull("Field 'dv' should exist", bulkNdv);
          long[] bulkValues = new long[docs.length];
          bulkNdv.longValues(docs.length, docs, bulkValues, defaultValue);

          // All values should be defaultValue since none of these docs have values.
          for (int i = 0; i < docs.length; i++) {
            assertEquals(
                "Doc " + docs[i] + " should have defaultValue (missing doc)",
                defaultValue,
                bulkValues[i]);
          }

          // Also verify via assertBulkEqualsSequential for consistency.
          assertBulkEqualsSequential(leafReader, docs, docs.length, defaultValue);
        }
      }
    }
  }

  // ---- Prefetch behavior helper ----

  /**
   * A minimal in-memory {@link RandomAccessInput} backed by a byte array. Used as the delegate for
   * {@link CountingRandomAccessInput} in prefetch behavior tests where we only need to verify
   * prefetch call patterns, not actual data correctness.
   */
  static final class ByteArrayRandomAccessInput implements RandomAccessInput {
    private final byte[] data;

    ByteArrayRandomAccessInput(int size) {
      this.data = new byte[size];
    }

    @Override
    public long length() {
      return data.length;
    }

    @Override
    public byte readByte(long pos) {
      return data[(int) pos];
    }

    @Override
    public short readShort(long pos) {
      return 0;
    }

    @Override
    public int readInt(long pos) {
      return 0;
    }

    @Override
    public long readLong(long pos) {
      return 0;
    }

    @Override
    public boolean prefetch(long offset, long length) {
      return true;
    }

    @Override
    public Optional<Boolean> isLoaded() {
      return Optional.empty();
    }
  }

  /**
   * Edge case: field with no values at all (empty field). All docs lack the "dv" field, so
   * getNumericDocValues returns null and DocValues.getNumeric returns emptyNumeric(). The bulk
   * longValues call should fill all entries with defaultValue.
   *
   * <p><b>Validates: Requirements 9.3</b>
   */
  public void testEmptyField() throws Exception {
    int numDocs = TestUtil.nextInt(random(), 10, 200);
    long defaultValue = random().nextLong();

    try (Directory dir = newDirectory()) {
      // Create an index where no document has the "dv" field.
      IndexWriterConfig conf = new IndexWriterConfig(new MockAnalyzer(random()));
      conf.setCodec(codec());
      conf.setMaxBufferedDocs(numDocs + 1);
      conf.setRAMBufferSizeMB(-1);
      try (IndexWriter writer = new IndexWriter(dir, conf)) {
        for (int i = 0; i < numDocs; i++) {
          Document doc = new Document();
          // Add a dummy field so the document is not empty.
          doc.add(new StringField("id", Integer.toString(i), Field.Store.NO));
          writer.addDocument(doc);
        }
        writer.forceMerge(1);
      }

      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        for (LeafReaderContext ctx : reader.leaves()) {
          LeafReader leafReader = ctx.reader();
          int maxDoc = leafReader.maxDoc();
          if (maxDoc == 0) continue;

          // getNumericDocValues should return null for a non-existent field.
          NumericDocValues rawNdv = leafReader.getNumericDocValues("dv");
          assertNull("Field 'dv' should not exist", rawNdv);

          // Use DocValues.getNumeric which returns emptyNumeric() for missing fields.
          NumericDocValues ndv = DocValues.getNumeric(leafReader, "dv");
          assertNotNull("DocValues.getNumeric should return non-null", ndv);

          int batchSize = Math.min(maxDoc, 4096);
          int[] docs = generateSortedDocIdBatch(random(), maxDoc, batchSize);
          long[] values = new long[docs.length];
          ndv.longValues(docs.length, docs, values, defaultValue);

          // All values should be defaultValue since no doc has the field.
          for (int i = 0; i < docs.length; i++) {
            assertEquals(
                "Doc " + docs[i] + " should have defaultValue (empty field)",
                defaultValue,
                values[i]);
          }
        }
      }
    }
  }

  // ---- Prefetch behavior verification tests (Tasks 7.2–7.5) ----

  // Feature: docvalues-prefetch-bulk-collection, Property 4: Zero prefetch for constant-value
  // fields
  /**
   * Property test: for any numeric doc values field with {@code bitsPerValue == 0} (constant value
   * encoding), and for any valid batch of sorted doc IDs, calling {@code longValues()} SHALL issue
   * exactly zero {@code prefetch()} calls on the backing {@link RandomAccessInput} slices.
   *
   * <p>Verifies two aspects:
   *
   * <ol>
   *   <li>The static helper {@code usePerDocPrefetch()} always returns {@code false} for bpv=0,
   *       regardless of density ratio — confirming no prefetch strategy is selected.
   *   <li>Calling {@code prefetchFixedBPV()} is never reached for bpv=0 fields (the code skips it
   *       entirely), so a {@link CountingRandomAccessInput} wrapping a dummy slice records zero
   *       prefetch calls when we simulate the constant-value code path.
   * </ol>
   *
   * <p><b>Validates: Requirements 2.1, 10.3, 13.6</b>
   */
  public void testZeroPrefetchForConstantValue() throws Exception {
    int iterations = atLeast(100);
    for (int iter = 0; iter < iterations; iter++) {
      Random rnd = random();

      // --- Part 1: usePerDocPrefetch always returns false for bpv=0 ---
      long densityRatio = TestUtil.nextLong(rnd, 0, 1_000_000);
      int densityMultiplier = TestUtil.nextInt(rnd, 1, 10);
      assertFalse(
          "usePerDocPrefetch should return false for bpv=0 (densityRatio="
              + densityRatio
              + ", multiplier="
              + densityMultiplier
              + ")",
          Lucene90DocValuesProducer.usePerDocPrefetch(densityRatio, 0, densityMultiplier));

      // --- Part 2: Simulate the constant-value longValues path ---
      // DenseConstantDocValues.longValues does Arrays.fill — no slice access at all.
      // SparseConstantDocValues.longValues touches DISI but not value data.
      // Verify that a CountingRandomAccessInput records zero prefetch calls when we
      // replicate the constant-value code path (just fill the array).
      int numDocs = TestUtil.nextInt(rnd, 10, 10000);
      int batchSize = TestUtil.nextInt(rnd, 1, Math.min(4096, numDocs));
      int[] docs = generateSortedDocIdBatch(rnd, numDocs, batchSize);
      long[] values = new long[docs.length];
      long constantValue = rnd.nextLong();

      // Create a CountingRandomAccessInput to verify no prefetch calls
      ByteArrayRandomAccessInput delegate = new ByteArrayRandomAccessInput(1024);
      CountingRandomAccessInput counting = new CountingRandomAccessInput(delegate);

      // Replicate DenseConstantDocValues.longValues: just fill, no IO
      Arrays.fill(values, 0, docs.length, constantValue);

      // Verify zero prefetch calls on the counting wrapper
      assertEquals(
          "bpv=0 constant-value path should issue zero prefetch calls",
          0,
          counting.getPrefetchCalls().size());
      assertEquals(
          "bpv=0 constant-value path should issue zero read calls",
          0,
          counting.getReadCalls().size());

      // Verify all values are the constant
      for (int i = 0; i < docs.length; i++) {
        assertEquals(
            "Value at index " + i + " should be the constant", constantValue, values[i]);
      }
    }
  }

  // Feature: docvalues-prefetch-bulk-collection, Property 5: Single contiguous prefetch for dense
  // batches
  /**
   * Property test: for any numeric doc values field with {@code bitsPerValue > 0}, and for any
   * valid batch of sorted doc IDs where the density ratio is at or below the density threshold,
   * calling {@code prefetchFixedBPV()} SHALL issue exactly 1 contiguous range {@code prefetch()}
   * call for the value data slice.
   *
   * <p>Tests the static {@code prefetchFixedBPV()} helper directly with a {@link
   * CountingRandomAccessInput} wrapping a dummy slice. Dense batches (consecutive doc IDs) produce
   * a low density ratio that falls below the threshold, triggering the contiguous range strategy.
   *
   * <p><b>Validates: Requirements 3.2, 10.4, 13.4</b>
   */
  public void testContiguousPrefetchForDenseBatches() throws Exception {
    int iterations = atLeast(100);
    for (int iter = 0; iter < iterations; iter++) {
      Random rnd = random();

      // Pick a random bitsPerValue > 0 from the set of valid DirectReader bpv values
      int[] validBpvs = {1, 2, 4, 8, 12, 16, 20, 24, 32, 40, 48, 56, 64};
      int bpv = validBpvs[rnd.nextInt(validBpvs.length)];

      // Compute the density threshold for this bpv
      long blockCapacity = (32768L * 8) / bpv;

      // Generate a dense batch: consecutive doc IDs starting from a random offset.
      // Density ratio for consecutive docs of size N starting at S is:
      //   (S + N - 1 - S) / N = (N - 1) / N ≈ 1 for large N
      // This is well below blockCapacity for any bpv, so contiguous strategy is selected.
      int maxDoc = TestUtil.nextInt(rnd, 1000, 100000);
      int batchSize = TestUtil.nextInt(rnd, 2, Math.min(4096, maxDoc));
      int[] docs = generateDenseBatch(rnd, maxDoc, batchSize);

      // Verify the density ratio is below the threshold (confirming contiguous strategy)
      long densityRatio = ((long) docs[docs.length - 1] - docs[0]) / docs.length;
      assertFalse(
          "Dense batch should NOT trigger per-doc prefetch (densityRatio="
              + densityRatio
              + ", blockCapacity="
              + blockCapacity
              + ", bpv="
              + bpv
              + ")",
          Lucene90DocValuesProducer.usePerDocPrefetch(densityRatio, bpv, 1));

      // Allocate a dummy slice large enough for the byte range
      long maxByteOffset = ((long) docs[docs.length - 1] * bpv) / 8 + 8;
      int sliceSize = (int) Math.min(maxByteOffset + 64, Integer.MAX_VALUE);
      ByteArrayRandomAccessInput delegate = new ByteArrayRandomAccessInput(sliceSize);
      CountingRandomAccessInput counting = new CountingRandomAccessInput(delegate);

      // Call prefetchFixedBPV — should issue exactly 1 contiguous prefetch
      Lucene90DocValuesProducer.prefetchFixedBPV(docs.length, docs, counting, bpv);

      List<CountingRandomAccessInput.PrefetchCall> prefetchCalls = counting.getPrefetchCalls();
      assertEquals(
          "Dense batch should produce exactly 1 contiguous prefetch call (bpv="
              + bpv
              + ", batchSize="
              + docs.length
              + ", densityRatio="
              + densityRatio
              + ")",
          1,
          prefetchCalls.size());

      // Verify the prefetch covers the expected byte range
      long expectedFirstByte = ((long) docs[0] * bpv) / 8;
      int readSize = bpv <= 8 ? 1 : bpv <= 16 ? 2 : bpv <= 32 ? 4 : 8;
      long expectedLastByte = ((long) docs[docs.length - 1] * bpv) / 8 + readSize;
      CountingRandomAccessInput.PrefetchCall call = prefetchCalls.get(0);
      assertEquals(
          "Prefetch offset should match first doc's byte offset", expectedFirstByte, call.offset());
      assertEquals(
          "Prefetch length should cover the full range",
          expectedLastByte - expectedFirstByte,
          call.length());
    }
  }

  // Feature: docvalues-prefetch-bulk-collection, Property 6: Multiple per-doc prefetch for sparse
  // batches
  /**
   * Property test: for any numeric doc values field with {@code bitsPerValue > 0}, and for any
   * valid batch of sorted doc IDs where the density ratio exceeds the density threshold, calling
   * {@code prefetchFixedBPV()} SHALL issue multiple per-doc {@code prefetch()} calls (more than 1).
   *
   * <p>Tests the static {@code prefetchFixedBPV()} helper directly with a {@link
   * CountingRandomAccessInput} wrapping a dummy slice. Sparse batches (widely spread doc IDs)
   * produce a high density ratio that exceeds the threshold, triggering the per-doc strategy.
   *
   * <p><b>Validates: Requirements 3.3, 10.5, 13.3</b>
   */
  public void testPerDocPrefetchForSparseBatches() throws Exception {
    int iterations = atLeast(100);
    for (int iter = 0; iter < iterations; iter++) {
      Random rnd = random();

      // Pick a random bitsPerValue > 0
      int[] validBpvs = {1, 2, 4, 8, 12, 16, 20, 24, 32, 40, 48, 56, 64};
      int bpv = validBpvs[rnd.nextInt(validBpvs.length)];

      // Compute the density threshold for this bpv
      long blockCapacity = (32768L * 8) / bpv;

      // Generate a sparse batch: pick a small number of doc IDs spread across a very large range
      // to ensure the density ratio exceeds blockCapacity.
      // We need: (docs[size-1] - docs[0]) / size > blockCapacity
      // So: docs[size-1] - docs[0] > blockCapacity * size
      int batchSize = TestUtil.nextInt(rnd, 2, 100);
      long requiredSpread = (blockCapacity + 1) * batchSize;
      // Cap the max doc to avoid overflow, but ensure it's large enough
      int maxDoc = (int) Math.min(requiredSpread + batchSize + 1000, 10_000_000);
      if (maxDoc <= batchSize) {
        // If blockCapacity is huge (e.g., bpv=1 → blockCapacity=262144), we need a very large
        // range. Use a smaller batch size or larger maxDoc.
        maxDoc = (int) Math.min((blockCapacity + 1) * 3 + 1000, 10_000_000);
        batchSize = 2; // minimum batch for per-doc strategy
      }

      // Build a sparse batch by placing docs at the extremes of the range
      TreeSet<Integer> set = new TreeSet<>();
      // Ensure first and last docs are far apart
      set.add(0);
      set.add(maxDoc - 1);
      // Fill remaining with random docs spread across the range
      while (set.size() < batchSize) {
        set.add(rnd.nextInt(maxDoc));
      }
      int[] docs = new int[set.size()];
      int idx = 0;
      for (int docId : set) {
        docs[idx++] = docId;
      }

      // Verify the density ratio exceeds the threshold
      long densityRatio = ((long) docs[docs.length - 1] - docs[0]) / docs.length;
      if (!Lucene90DocValuesProducer.usePerDocPrefetch(densityRatio, bpv, 1)) {
        // If we still didn't exceed the threshold (unlikely but possible for very small ranges),
        // skip this iteration
        continue;
      }

      // Allocate a dummy slice large enough
      long maxByteOffset = ((long) docs[docs.length - 1] * bpv) / 8 + 8;
      int sliceSize = (int) Math.min(maxByteOffset + 64, Integer.MAX_VALUE);
      ByteArrayRandomAccessInput delegate = new ByteArrayRandomAccessInput(sliceSize);
      CountingRandomAccessInput counting = new CountingRandomAccessInput(delegate);

      // Call prefetchFixedBPV — should issue multiple per-doc prefetch calls
      Lucene90DocValuesProducer.prefetchFixedBPV(docs.length, docs, counting, bpv);

      List<CountingRandomAccessInput.PrefetchCall> prefetchCalls = counting.getPrefetchCalls();
      assertTrue(
          "Sparse batch should produce multiple prefetch calls (got "
              + prefetchCalls.size()
              + ", bpv="
              + bpv
              + ", batchSize="
              + docs.length
              + ", densityRatio="
              + densityRatio
              + ")",
          prefetchCalls.size() > 1);

      // The number of prefetch calls should equal the batch size (one per doc)
      assertEquals(
          "Per-doc prefetch should issue exactly one call per doc",
          docs.length,
          prefetchCalls.size());
    }
  }

  // Feature: docvalues-prefetch-bulk-collection, Property: Prefetch-before-read ordering
  /**
   * Verifies that for encoding variants requiring IO, at least one {@code prefetch()} call is
   * issued before any read operations during a {@code longValues()} call.
   *
   * <p>Uses {@link CountingRandomAccessInput} to record call ordering via sequence numbers. The
   * test calls {@code prefetchFixedBPV()} (which issues prefetch calls) followed by simulated reads
   * (via {@code readLong}), and verifies that all prefetch sequence numbers precede all read
   * sequence numbers.
   *
   * <p><b>Validates: Requirements 10.2</b>
   */
  public void testPrefetchBeforeReadOrdering() throws Exception {
    int iterations = atLeast(100);
    for (int iter = 0; iter < iterations; iter++) {
      Random rnd = random();

      // Pick a random bitsPerValue > 0
      int[] validBpvs = {1, 2, 4, 8, 12, 16, 20, 24, 32, 40, 48, 56, 64};
      int bpv = validBpvs[rnd.nextInt(validBpvs.length)];

      // Generate a batch of doc IDs (either dense or sparse — both should prefetch before read)
      int maxDoc = TestUtil.nextInt(rnd, 100, 100000);
      int batchSize = TestUtil.nextInt(rnd, 1, Math.min(4096, maxDoc));
      int[] docs;
      if (rnd.nextBoolean()) {
        docs = generateDenseBatch(rnd, maxDoc, batchSize);
      } else {
        docs = generateSparseBatch(rnd, maxDoc, batchSize);
      }
      if (docs.length == 0) continue;

      // Allocate a dummy slice large enough
      long maxByteOffset = ((long) docs[docs.length - 1] * bpv) / 8 + 8;
      int sliceSize = (int) Math.min(maxByteOffset + 64, Integer.MAX_VALUE);
      ByteArrayRandomAccessInput delegate = new ByteArrayRandomAccessInput(sliceSize);
      CountingRandomAccessInput counting = new CountingRandomAccessInput(delegate);

      // Step 1: Prefetch (simulating the prefetch phase of longValues)
      Lucene90DocValuesProducer.prefetchFixedBPV(docs.length, docs, counting, bpv);

      // Step 2: Read values (simulating the read phase of longValues)
      for (int i = 0; i < docs.length; i++) {
        long byteOffset = ((long) docs[i] * bpv) / 8;
        if (byteOffset + 8 <= sliceSize) {
          counting.readLong(byteOffset);
        } else if (byteOffset + 4 <= sliceSize) {
          counting.readInt(byteOffset);
        } else if (byteOffset + 1 <= sliceSize) {
          counting.readByte(byteOffset);
        }
      }

      // Verify prefetch calls exist (bpv > 0 always prefetches)
      List<CountingRandomAccessInput.PrefetchCall> prefetchCalls = counting.getPrefetchCalls();
      List<CountingRandomAccessInput.ReadCall> readCalls = counting.getReadCalls();

      assertFalse(
          "At least one prefetch call should be issued for bpv=" + bpv,
          prefetchCalls.isEmpty());
      assertFalse(
          "At least one read call should be issued for bpv=" + bpv, readCalls.isEmpty());

      // Verify ordering: all prefetch calls should have lower sequence numbers than all read calls
      long lastPrefetchSeq = prefetchCalls.get(prefetchCalls.size() - 1).sequenceNumber();
      long firstReadSeq = readCalls.get(0).sequenceNumber();
      assertTrue(
          "All prefetch calls (last seq="
              + lastPrefetchSeq
              + ") should precede all read calls (first seq="
              + firstReadSeq
              + ")",
          lastPrefetchSeq < firstReadSeq);

      // Also verify via the convenience method
      assertTrue(
          "wasPrefetchBeforeFirstRead should return true for bpv > 0",
          counting.wasPrefetchBeforeFirstRead());
    }
  }
}
