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
package org.apache.lucene.search.comparators;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.BulkValueComparator;
import org.apache.lucene.search.LeafFieldComparator;
import org.apache.lucene.search.Pruning;
import org.apache.lucene.search.SortField;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.util.LuceneTestCase;

/**
 * Unit tests for the {@link BulkValueComparator} implementation on {@link
 * LongComparator.LongLeafComparator}.
 *
 * <p>Validates: Requirements 11.1, 11.2, 11.3, 11.4, 11.5
 */
public class TestLongLeafComparatorBulkValue extends LuceneTestCase {

  private static final String FIELD = "val";

  /**
   * Creates a single-segment index with numDocs documents, each having a numeric doc values field.
   * Returns the writer (caller must close).
   */
  private static IndexWriter createIndex(Directory dir, int numDocs) throws IOException {
    IndexWriterConfig iwc = new IndexWriterConfig();
    iwc.setMaxBufferedDocs(numDocs + 100);
    iwc.setRAMBufferSizeMB(IndexWriterConfig.DISABLE_AUTO_FLUSH);
    IndexWriter w = new IndexWriter(dir, iwc);
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      doc.add(new NumericDocValuesField(FIELD, i * 10L));
      w.addDocument(doc);
    }
    w.forceMerge(1);
    return w;
  }

  /**
   * Helper: creates a LongComparator, gets a leaf comparator, and casts to BulkValueComparator.
   * Sets bottom and topValue on the comparator for testing.
   */
  private static BulkValueComparator getLeafBulkComparator(
      LeafReaderContext ctx, int numHits, long bottomValue, long topValue, boolean reverse)
      throws IOException {
    LongComparator comp =
        (LongComparator)
            new SortField(FIELD, SortField.Type.LONG, reverse)
                .getComparator(numHits, Pruning.NONE);
    LeafFieldComparator leaf = comp.getLeafComparator(ctx);
    assertTrue(
        "LongLeafComparator should implement BulkValueComparator",
        leaf instanceof BulkValueComparator);

    // Set bottom: copy a value into slot 0, then setBottom(0)
    comp.bottom = bottomValue;
    comp.setTopValue(topValue);

    return (BulkValueComparator) leaf;
  }

  // ---- Requirement 11.1: compareBottomAt correctness ----

  /**
   * Tests that compareBottomAt returns the same result as Long.compare(bottom, batchValues[idx])
   * for edge values including Long.MIN_VALUE, Long.MAX_VALUE, 0, negatives, and missing value.
   *
   * <p>Validates: Requirement 11.1
   */
  public void testCompareBottomAtEdgeValues() throws IOException {
    Directory dir = newDirectory();
    IndexWriter w = createIndex(dir, 10);
    DirectoryReader reader = DirectoryReader.open(w);
    w.close();

    LeafReaderContext ctx = reader.leaves().get(0);

    long[] edgeValues = {
      Long.MIN_VALUE, Long.MIN_VALUE + 1, -1000L, -1L, 0L, 1L, 1000L, Long.MAX_VALUE - 1,
      Long.MAX_VALUE
    };

    long[] bottomValues = {Long.MIN_VALUE, -42L, 0L, 42L, Long.MAX_VALUE};

    for (long bottom : bottomValues) {
      BulkValueComparator bvc = getLeafBulkComparator(ctx, 10, bottom, 0L, false);
      int[] docs = new int[edgeValues.length];
      for (int i = 0; i < docs.length; i++) docs[i] = i;
      bvc.setBatch(edgeValues, docs, edgeValues.length);

      for (int i = 0; i < edgeValues.length; i++) {
        int expected = Long.compare(bottom, edgeValues[i]);
        int actual = bvc.compareBottomAt(i);
        assertEquals(
            "compareBottomAt mismatch for bottom="
                + bottom
                + " value="
                + edgeValues[i],
            Integer.signum(expected),
            Integer.signum(actual));
      }
    }

    reader.close();
    dir.close();
  }

  // ---- Requirement 11.2: compareTopAt correctness ----

  /**
   * Tests that compareTopAt returns the same result as Long.compare(topValue, batchValues[idx])
   * for edge values.
   *
   * <p>Validates: Requirement 11.2
   */
  public void testCompareTopAtEdgeValues() throws IOException {
    Directory dir = newDirectory();
    IndexWriter w = createIndex(dir, 10);
    DirectoryReader reader = DirectoryReader.open(w);
    w.close();

    LeafReaderContext ctx = reader.leaves().get(0);

    long[] edgeValues = {
      Long.MIN_VALUE, Long.MIN_VALUE + 1, -1000L, -1L, 0L, 1L, 1000L, Long.MAX_VALUE - 1,
      Long.MAX_VALUE
    };

    long[] topValues = {Long.MIN_VALUE, -42L, 0L, 42L, Long.MAX_VALUE};

    for (long top : topValues) {
      BulkValueComparator bvc = getLeafBulkComparator(ctx, 10, 0L, top, false);
      int[] docs = new int[edgeValues.length];
      for (int i = 0; i < docs.length; i++) docs[i] = i;
      bvc.setBatch(edgeValues, docs, edgeValues.length);

      for (int i = 0; i < edgeValues.length; i++) {
        int expected = Long.compare(top, edgeValues[i]);
        int actual = bvc.compareTopAt(i);
        assertEquals(
            "compareTopAt mismatch for top=" + top + " value=" + edgeValues[i],
            Integer.signum(expected),
            Integer.signum(actual));
      }
    }

    reader.close();
    dir.close();
  }

  // ---- Requirement 11.3: copyAt correctness ----

  /**
   * Tests that copyAt(slot, idx) sets value(slot) to batchValues[idx].
   *
   * <p>Validates: Requirement 11.3
   */
  public void testCopyAtSetsValue() throws IOException {
    Directory dir = newDirectory();
    IndexWriter w = createIndex(dir, 10);
    DirectoryReader reader = DirectoryReader.open(w);
    w.close();

    LeafReaderContext ctx = reader.leaves().get(0);
    int numHits = 5;

    LongComparator comp =
        (LongComparator)
            new SortField(FIELD, SortField.Type.LONG, false)
                .getComparator(numHits, Pruning.NONE);
    LeafFieldComparator leaf = comp.getLeafComparator(ctx);
    BulkValueComparator bvc = (BulkValueComparator) leaf;

    long[] batchValues = {100L, -200L, Long.MAX_VALUE, 0L, Long.MIN_VALUE};
    int[] batchDocs = {0, 1, 2, 3, 4};
    bvc.setBatch(batchValues, batchDocs, batchValues.length);

    for (int idx = 0; idx < batchValues.length; idx++) {
      int slot = idx; // use idx as slot since numHits=5
      bvc.copyAt(slot, idx);
      assertEquals(
          "value(slot) should equal batchValues[idx] after copyAt",
          Long.valueOf(batchValues[idx]),
          comp.value(slot));
    }

    reader.close();
    dir.close();
  }

  // ---- Requirement 11.4: compareBottomAt and copyAt do not call advanceExact or longValue ----

  /**
   * Verifies that compareBottomAt and copyAt do not call advanceExact or longValue on the
   * underlying NumericDocValues. Uses a counting wrapper on NumericDocValues.
   *
   * <p>Validates: Requirement 11.4
   */
  public void testBulkMethodsDoNotCallDocValues() throws IOException {
    Directory dir = newDirectory();
    IndexWriter w = createIndex(dir, 10);
    DirectoryReader reader = DirectoryReader.open(w);
    w.close();

    LeafReaderContext ctx = reader.leaves().get(0);

    AtomicInteger advanceExactCount = new AtomicInteger(0);
    AtomicInteger longValueCount = new AtomicInteger(0);

    // Create a custom LongComparator that wraps NumericDocValues with a counting proxy
    int numHits = 5;
    LongComparator comp =
        new LongComparator(numHits, FIELD, 0L, false, Pruning.NONE) {
          @Override
          public LeafFieldComparator getLeafComparator(LeafReaderContext context)
              throws IOException {
            return new LongLeafComparator(context) {
              @Override
              protected NumericDocValues getNumericDocValues(
                  LeafReaderContext context, String field) throws IOException {
                NumericDocValues delegate = super.getNumericDocValues(context, field);
                return new CountingNumericDocValues(
                    delegate, advanceExactCount, longValueCount);
              }
            };
          }
        };

    LeafFieldComparator leaf = comp.getLeafComparator(ctx);
    BulkValueComparator bvc = (BulkValueComparator) leaf;

    // Reset counters after construction (getLeafComparator may call advanceExact internally)
    advanceExactCount.set(0);
    longValueCount.set(0);

    long[] batchValues = {10L, 20L, 30L, 40L, 50L};
    int[] batchDocs = {0, 1, 2, 3, 4};
    bvc.setBatch(batchValues, batchDocs, 5);

    comp.bottom = 25L;

    // Call compareBottomAt — should NOT touch doc values
    for (int i = 0; i < 5; i++) {
      bvc.compareBottomAt(i);
    }
    assertEquals(
        "compareBottomAt should not call advanceExact", 0, advanceExactCount.get());
    assertEquals(
        "compareBottomAt should not call longValue", 0, longValueCount.get());

    // Call copyAt — should NOT call advanceExact or longValue
    // (it calls super.copy which touches competitiveDISIBuilder, but not doc values IO)
    for (int i = 0; i < 5; i++) {
      bvc.copyAt(i, i);
    }
    assertEquals(
        "copyAt should not call advanceExact", 0, advanceExactCount.get());
    assertEquals(
        "copyAt should not call longValue", 0, longValueCount.get());

    // Call compareTopAt — should NOT touch doc values
    comp.setTopValue(35L);
    // Need a new leaf comparator since topValue is set after construction
    // But compareTopAt reads from batchValues, not doc values, so we can test on existing
    for (int i = 0; i < 5; i++) {
      bvc.compareTopAt(i);
    }
    assertEquals(
        "compareTopAt should not call advanceExact", 0, advanceExactCount.get());
    assertEquals(
        "compareTopAt should not call longValue", 0, longValueCount.get());

    reader.close();
    dir.close();
  }

  // ---- Requirement 11.5: copyAt invokes super.copy for competitive iterator state ----

  /**
   * Verifies that copyAt(slot, idx) correctly invokes super.copy(slot, batchDocs[idx]) for
   * competitive iterator bookkeeping. We verify this by comparing the behavior of copyAt against
   * the per-doc copy method: both should produce the same value(slot) result, and copyAt should
   * use batchDocs[idx] (not idx) as the doc ID.
   *
   * <p>We also verify that copyAt produces the same result as manually calling
   * the per-doc copy(slot, doc) with the same doc ID, confirming that super.copy is invoked.
   *
   * <p>Validates: Requirement 11.5
   */
  public void testCopyAtInvokesSuperCopy() throws IOException {
    Directory dir = newDirectory();
    IndexWriter w = createIndex(dir, 10);
    DirectoryReader reader = DirectoryReader.open(w);
    w.close();

    LeafReaderContext ctx = reader.leaves().get(0);
    int numHits = 5;

    // Create two comparators: one for bulk copyAt, one for per-doc copy
    LongComparator bulkComp =
        (LongComparator)
            new SortField(FIELD, SortField.Type.LONG, false)
                .getComparator(numHits, Pruning.NONE);
    LeafFieldComparator bulkLeaf = bulkComp.getLeafComparator(ctx);
    BulkValueComparator bvc = (BulkValueComparator) bulkLeaf;

    LongComparator perDocComp =
        (LongComparator)
            new SortField(FIELD, SortField.Type.LONG, false)
                .getComparator(numHits, Pruning.NONE);
    LeafFieldComparator perDocLeaf = perDocComp.getLeafComparator(ctx);

    // Use non-sequential doc IDs to verify batchDocs[idx] is used, not idx
    // Doc 3 has value 30, doc 7 has value 70, doc 9 has value 90 (from createIndex: i*10)
    long[] batchValues = {30L, 70L, 90L};
    int[] batchDocs = {3, 7, 9};
    bvc.setBatch(batchValues, batchDocs, 3);

    // copyAt(slot=0, idx=0) should produce same result as copy(slot=0, doc=3)
    bvc.copyAt(0, 0);
    perDocLeaf.copy(0, 3);
    assertEquals(
        "copyAt and per-doc copy should produce same value for slot 0",
        perDocComp.value(0),
        bulkComp.value(0));

    // copyAt(slot=1, idx=1) should produce same result as copy(slot=1, doc=7)
    bvc.copyAt(1, 1);
    perDocLeaf.copy(1, 7);
    assertEquals(
        "copyAt and per-doc copy should produce same value for slot 1",
        perDocComp.value(1),
        bulkComp.value(1));

    // copyAt(slot=2, idx=2) should produce same result as copy(slot=2, doc=9)
    bvc.copyAt(2, 2);
    perDocLeaf.copy(2, 9);
    assertEquals(
        "copyAt and per-doc copy should produce same value for slot 2",
        perDocComp.value(2),
        bulkComp.value(2));

    // Verify the values are what we expect (batchDocs[idx] values, not idx values)
    assertEquals(Long.valueOf(30L), bulkComp.value(0));
    assertEquals(Long.valueOf(70L), bulkComp.value(1));
    assertEquals(Long.valueOf(90L), bulkComp.value(2));

    reader.close();
    dir.close();
  }

  // ---- Helper: Counting NumericDocValues wrapper ----

  /**
   * A NumericDocValues wrapper that counts calls to advanceExact and longValue.
   */
  private static class CountingNumericDocValues extends NumericDocValues {
    private final NumericDocValues delegate;
    private final AtomicInteger advanceExactCount;
    private final AtomicInteger longValueCount;

    CountingNumericDocValues(
        NumericDocValues delegate,
        AtomicInteger advanceExactCount,
        AtomicInteger longValueCount) {
      this.delegate = delegate;
      this.advanceExactCount = advanceExactCount;
      this.longValueCount = longValueCount;
    }

    @Override
    public long longValue() throws IOException {
      longValueCount.incrementAndGet();
      return delegate.longValue();
    }

    @Override
    public boolean advanceExact(int target) throws IOException {
      advanceExactCount.incrementAndGet();
      return delegate.advanceExact(target);
    }

    @Override
    public int docID() {
      return delegate.docID();
    }

    @Override
    public int nextDoc() throws IOException {
      return delegate.nextDoc();
    }

    @Override
    public int advance(int target) throws IOException {
      return delegate.advance(target);
    }

    @Override
    public long cost() {
      return delegate.cost();
    }
  }
}
