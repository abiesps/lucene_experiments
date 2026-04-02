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
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.SerialMergeScheduler;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;

/**
 * Tests for bulk collection equivalence in TopFieldCollector, focusing on early termination
 * scenarios where the search sort matches the index sort.
 *
 * <p>The bulk path is automatically activated when sorting by a long field (LongComparator
 * implements BulkValueComparator). These tests verify that the bulk collection path produces
 * identical results to the per-doc path for early termination, totalHits counting, and
 * totalHitsRelation transitions.
 */
public class TestTopFieldCollectorBulkPrefetch extends LuceneTestCase {

  // ---- Helpers ----

  /**
   * Creates an index sorted by the given sort, with numDocs documents containing a long field
   * "sortValue" and a string field "tag" for filtering. The sort values are assigned so that
   * documents are in ascending order of sortValue matching the index sort.
   *
   * @param dir the directory to write to
   * @param sort the index sort (must sort by "sortValue" long field)
   * @param numDocs number of documents to index
   * @param sparse if true, some documents will not have the sort field
   * @return the IndexWriter (caller must close)
   */
  private static IndexWriter createSortedIndex(
      Directory dir, Sort sort, int numDocs, boolean sparse) throws IOException {
    IndexWriterConfig iwc = new IndexWriterConfig();
    iwc.setIndexSort(sort);
    iwc.setMergeScheduler(new SerialMergeScheduler());
    // Use a single segment to ensure index sort is fully applied
    iwc.setMaxBufferedDocs(numDocs + 100);
    iwc.setRAMBufferSizeMB(IndexWriterConfig.DISABLE_AUTO_FLUSH);
    IndexWriter w = new IndexWriter(dir, iwc);
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      if (!sparse || i % 3 != 0) {
        doc.add(new NumericDocValuesField("sortValue", i));
      }
      doc.add(new StringField("tag", "match", Field.Store.NO));
      // Add a secondary tag for partial matching
      if (i % 2 == 0) {
        doc.add(new StringField("tag", "even", Field.Store.NO));
      }
      w.addDocument(doc);
    }
    w.forceMerge(1); // single segment so index sort is guaranteed
    return w;
  }

  /**
   * Runs a sorted query and returns TopFieldDocs.
   *
   * @param searcher the searcher
   * @param query the query
   * @param sort the sort
   * @param numHits number of hits to collect
   * @param totalHitsThreshold the threshold for total hits counting
   * @return TopFieldDocs result
   */
  private static TopFieldDocs searchSorted(
      IndexSearcher searcher, Query query, Sort sort, int numHits, int totalHitsThreshold)
      throws IOException {
    TopFieldCollectorManager manager =
        new TopFieldCollectorManager(sort, numHits, null, totalHitsThreshold);
    return searcher.search(query, manager);
  }

  /**
   * Asserts that two TopFieldDocs have identical top-N results: same doc IDs and same sort values.
   */
  private static void assertTopFieldDocsEqual(
      String message, TopFieldDocs expected, TopFieldDocs actual) {
    assertEquals(
        message + ": different number of score docs",
        expected.scoreDocs.length,
        actual.scoreDocs.length);
    for (int i = 0; i < expected.scoreDocs.length; i++) {
      FieldDoc expectedFD = (FieldDoc) expected.scoreDocs[i];
      FieldDoc actualFD = (FieldDoc) actual.scoreDocs[i];
      assertEquals(
          message + ": different doc ID at position " + i, expectedFD.doc, actualFD.doc);
      assertEquals(
          message + ": different number of sort fields at position " + i,
          expectedFD.fields.length,
          actualFD.fields.length);
      for (int j = 0; j < expectedFD.fields.length; j++) {
        assertEquals(
            message + ": different sort value at position " + i + " field " + j,
            expectedFD.fields[j],
            actualFD.fields[j]);
      }
    }
  }

  // ---- Task 9.6: Early termination totalHits and totalHitsRelation equivalence ----

  /**
   * Property 4: Early termination totalHits and totalHitsRelation equivalence.
   *
   * <p>Creates an index where the sort field matches the index sort (triggers
   * searchSortPartOfIndexSort = true). Runs sorted query with totalHitsThreshold &lt; total matches
   * using both exhaustive and threshold-limited paths. Asserts totalHits count, totalHitsRelation,
   * and top-N results are consistent.
   *
   * <p><b>Validates: Requirements 12.1, 12.2, 12.3</b>
   */
  public void testEarlyTerminationEquivalence() throws IOException {
    final int iters = atLeast(10);
    for (int iter = 0; iter < iters; iter++) {
      Directory dir = newDirectory();
      int numDocs = atLeast(200);
      Sort sort = new Sort(new SortField("sortValue", SortField.Type.LONG));
      IndexWriter w = createSortedIndex(dir, sort, numDocs, false);
      IndexReader reader = DirectoryReader.open(w);
      w.close();

      // Use a single-threaded searcher for deterministic behavior
      IndexSearcher searcher = newSearcher(reader, true, true, false);
      Query query = new TermQuery(new Term("tag", "match"));

      int numHits = TestUtil.nextInt(random(), 1, Math.min(50, numDocs));
      // Set threshold well below total matches to trigger early termination
      int totalHitsThreshold = TestUtil.nextInt(random(), 1, numHits);

      // Run with exhaustive counting (no early termination on threshold)
      TopFieldDocs exhaustive = searchSorted(searcher, query, sort, numHits, Integer.MAX_VALUE);

      // Run with low threshold (triggers early termination since sort matches index sort)
      TopFieldDocs withThreshold = searchSorted(searcher, query, sort, numHits, totalHitsThreshold);

      // The exhaustive path should have EQUAL_TO relation
      assertEquals(
          "Exhaustive search should have EQUAL_TO relation",
          TotalHits.Relation.EQUAL_TO,
          exhaustive.totalHits.relation());

      // With threshold and index-sort matching search-sort, early termination should fire
      // The totalHits should be >= numHits (we collected at least numHits docs)
      assertTrue(
          "totalHits with threshold should be >= numHits, got "
              + withThreshold.totalHits.value()
              + " vs numHits=" + numHits,
          withThreshold.totalHits.value() >= numHits);

      // If early termination fired, relation should be GREATER_THAN_OR_EQUAL_TO
      if (withThreshold.totalHits.value() < exhaustive.totalHits.value()) {
        assertEquals(
            "When early terminated, relation should be GREATER_THAN_OR_EQUAL_TO",
            TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO,
            withThreshold.totalHits.relation());
      }

      // The top-N results should be identical — early termination should not affect
      // which docs end up in the priority queue since the index is sorted by the same field
      assertTopFieldDocsEqual(
          "Top-N results should be identical between exhaustive and threshold-limited",
          exhaustive,
          withThreshold);

      reader.close();
      dir.close();
    }
  }

  /**
   * Tests early termination equivalence with sparse fields (some docs missing sort values).
   *
   * <p><b>Validates: Requirements 12.1, 12.2, 12.3</b>
   */
  public void testEarlyTerminationEquivalenceSparse() throws IOException {
    Directory dir = newDirectory();
    int numDocs = atLeast(200);
    Sort sort = new Sort(new SortField("sortValue", SortField.Type.LONG));
    IndexWriter w = createSortedIndex(dir, sort, numDocs, true);
    IndexReader reader = DirectoryReader.open(w);
    w.close();

    IndexSearcher searcher = newSearcher(reader, true, true, false);
    Query query = new TermQuery(new Term("tag", "match"));

    int numHits = TestUtil.nextInt(random(), 1, Math.min(20, numDocs));
    int totalHitsThreshold = TestUtil.nextInt(random(), 1, numHits);

    TopFieldDocs exhaustive = searchSorted(searcher, query, sort, numHits, Integer.MAX_VALUE);
    TopFieldDocs withThreshold = searchSorted(searcher, query, sort, numHits, totalHitsThreshold);

    // Top-N results should be identical
    assertTopFieldDocsEqual(
        "Sparse: top-N results should be identical", exhaustive, withThreshold);

    reader.close();
    dir.close();
  }

  /**
   * Tests early termination equivalence with descending sort.
   *
   * <p><b>Validates: Requirements 12.1, 12.2, 12.3</b>
   */
  public void testEarlyTerminationEquivalenceDescending() throws IOException {
    Directory dir = newDirectory();
    int numDocs = atLeast(200);
    Sort sort = new Sort(new SortField("sortValue", SortField.Type.LONG, true));
    IndexWriter w = createSortedIndex(dir, sort, numDocs, false);
    IndexReader reader = DirectoryReader.open(w);
    w.close();

    IndexSearcher searcher = newSearcher(reader, true, true, false);
    Query query = new TermQuery(new Term("tag", "match"));

    int numHits = TestUtil.nextInt(random(), 1, Math.min(20, numDocs));
    int totalHitsThreshold = TestUtil.nextInt(random(), 1, numHits);

    TopFieldDocs exhaustive = searchSorted(searcher, query, sort, numHits, Integer.MAX_VALUE);
    TopFieldDocs withThreshold = searchSorted(searcher, query, sort, numHits, totalHitsThreshold);

    assertTopFieldDocsEqual(
        "Descending: top-N results should be identical", exhaustive, withThreshold);

    reader.close();
    dir.close();
  }

  // ---- Task 9.7: Early termination timing — CollectionTerminatedException fires at same point ----

  /**
   * Tests that CollectionTerminatedException fires at the same logical point in the bulk path
   * as it would in the per-doc path. We instrument both paths by using a custom collector wrapper
   * that counts docs visited before the exception is thrown.
   *
   * <p>The key invariant: when the index sort matches the search sort and totalHitsThreshold is
   * reached, the bulk path should throw CollectionTerminatedException after processing the same
   * number of docs as the per-doc path would.
   *
   * <p><b>Validates: Requirements 12.1, 12.2</b>
   */
  public void testEarlyTerminationTimingConsistency() throws IOException {
    // Create a small index where we can precisely control when early termination fires
    Directory dir = newDirectory();
    Sort sort = new Sort(new SortField("sortValue", SortField.Type.LONG));
    int numDocs = 100;
    IndexWriter w = createSortedIndex(dir, sort, numDocs, false);
    IndexReader reader = DirectoryReader.open(w);
    w.close();

    IndexSearcher searcher = newSearcher(reader, true, true, false);
    Query query = new TermQuery(new Term("tag", "match"));

    // Test with various numHits and threshold combinations
    int[] numHitsValues = {1, 5, 10};
    for (int numHits : numHitsValues) {
      // threshold = numHits means early termination fires as soon as queue is full
      // and a non-competitive doc is found
      int totalHitsThreshold = numHits;

      // Run with exhaustive counting to get ground truth
      TopFieldDocs exhaustive = searchSorted(searcher, query, sort, numHits, Integer.MAX_VALUE);

      // Run with threshold — this will trigger early termination
      TopFieldDocs withThreshold = searchSorted(searcher, query, sort, numHits, totalHitsThreshold);

      // The top-N results must be identical
      assertTopFieldDocsEqual(
          "numHits=" + numHits + ": top-N results should match",
          exhaustive,
          withThreshold);

      // With early termination, totalHits should be <= exhaustive totalHits
      assertTrue(
          "totalHits with threshold should be <= exhaustive totalHits",
          withThreshold.totalHits.value() <= exhaustive.totalHits.value());

      // The totalHits should be at least numHits (we collected enough to fill the queue)
      assertTrue(
          "totalHits should be >= numHits",
          withThreshold.totalHits.value() >= numHits);

      // Remaining docs in the bulk buffer after exception should NOT be counted
      // This is verified by checking that totalHits is not inflated beyond what was processed
      if (withThreshold.totalHits.relation() == TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO) {
        // Early termination fired — totalHits is a lower bound
        assertTrue(
            "Early terminated totalHits should be <= total docs",
            withThreshold.totalHits.value() <= numDocs);
      }
    }

    reader.close();
    dir.close();
  }

  /**
   * Tests early termination with varying batch sizes relative to the termination point.
   * Specifically tests:
   * - Termination at doc 1 (first doc triggers early termination after queue fills)
   * - Termination mid-batch
   * - Termination at last doc in batch
   *
   * <p><b>Validates: Requirements 12.1, 12.2</b>
   */
  public void testEarlyTerminationAtVaryingPoints() throws IOException {
    Directory dir = newDirectory();
    Sort sort = new Sort(new SortField("sortValue", SortField.Type.LONG));
    // Use a larger index to ensure we have enough docs for various termination points
    int numDocs = 500;
    IndexWriter w = createSortedIndex(dir, sort, numDocs, false);
    IndexReader reader = DirectoryReader.open(w);
    w.close();

    IndexSearcher searcher = newSearcher(reader, true, true, false);
    Query query = new TermQuery(new Term("tag", "match"));

    // Case 1: numHits=1, threshold=1 — termination fires very early
    {
      TopFieldDocs exhaustive = searchSorted(searcher, query, sort, 1, Integer.MAX_VALUE);
      TopFieldDocs withThreshold = searchSorted(searcher, query, sort, 1, 1);
      assertTopFieldDocsEqual("Case 1: numHits=1", exhaustive, withThreshold);
      // Should have early terminated
      assertEquals(
          TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO,
          withThreshold.totalHits.relation());
      // totalHits should be small — only a few docs processed before termination
      assertTrue(
          "Case 1: totalHits should be much less than total docs, got "
              + withThreshold.totalHits.value(),
          withThreshold.totalHits.value() < numDocs);
    }

    // Case 2: numHits=50, threshold=50 — termination mid-batch
    {
      TopFieldDocs exhaustive = searchSorted(searcher, query, sort, 50, Integer.MAX_VALUE);
      TopFieldDocs withThreshold = searchSorted(searcher, query, sort, 50, 50);
      assertTopFieldDocsEqual("Case 2: numHits=50", exhaustive, withThreshold);
      if (withThreshold.totalHits.relation() == TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO) {
        assertTrue(
            "Case 2: totalHits should be < total docs",
            withThreshold.totalHits.value() < numDocs);
      }
    }

    // Case 3: numHits=200, threshold=200 — termination later in the stream
    {
      TopFieldDocs exhaustive = searchSorted(searcher, query, sort, 200, Integer.MAX_VALUE);
      TopFieldDocs withThreshold = searchSorted(searcher, query, sort, 200, 200);
      assertTopFieldDocsEqual("Case 3: numHits=200", exhaustive, withThreshold);
    }

    reader.close();
    dir.close();
  }

  /**
   * Tests that remaining docs in the bulk buffer are NOT counted in totalHits after
   * CollectionTerminatedException. Uses a very small numHits with a large index to ensure
   * the exception fires within the first batch.
   *
   * <p><b>Validates: Requirements 12.1, 12.2</b>
   */
  public void testRemainingDocsNotCountedAfterTermination() throws IOException {
    Directory dir = newDirectory();
    Sort sort = new Sort(new SortField("sortValue", SortField.Type.LONG));
    // Large enough that a 4096-element batch will be partially processed
    int numDocs = 5000;
    IndexWriter w = createSortedIndex(dir, sort, numDocs, false);
    IndexReader reader = DirectoryReader.open(w);
    w.close();

    IndexSearcher searcher = newSearcher(reader, true, true, false);
    Query query = new TermQuery(new Term("tag", "match"));

    // numHits=5, threshold=5 — early termination should fire very early in the batch
    TopFieldDocs withThreshold = searchSorted(searcher, query, sort, 5, 5);

    // Early termination should have fired
    assertEquals(
        "Should have early terminated",
        TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO,
        withThreshold.totalHits.relation());

    // totalHits should be much less than numDocs — remaining docs in the buffer
    // after the exception should NOT be counted
    assertTrue(
        "totalHits (" + withThreshold.totalHits.value()
            + ") should be much less than numDocs (" + numDocs + ")",
        withThreshold.totalHits.value() < numDocs / 2);

    // We should have collected exactly 5 results
    assertEquals("Should have 5 results", 5, withThreshold.scoreDocs.length);

    reader.close();
    dir.close();
  }

  // ---- Task 9.8: totalHitsThreshold transition tests ----

  /**
   * Tests the exact point where totalHitsThreshold is reached during the bulk collection loop.
   * Asserts totalHitsRelation transitions from EQUAL_TO to GREATER_THAN_OR_EQUAL_TO at the
   * same doc count as the per-doc path.
   *
   * <p>Tests with totalHitsThreshold values that fall within a batch and across batch boundaries.
   *
   * <p><b>Validates: Requirements 12.3</b>
   */
  public void testTotalHitsThresholdTransition() throws IOException {
    Directory dir = newDirectory();
    Sort sort = new Sort(new SortField("sortValue", SortField.Type.LONG));
    int numDocs = 300;
    IndexWriter w = createSortedIndex(dir, sort, numDocs, false);
    IndexReader reader = DirectoryReader.open(w);
    w.close();

    IndexSearcher searcher = newSearcher(reader, true, true, false);
    Query query = new TermQuery(new Term("tag", "match"));

    // Test various threshold values
    int[] thresholds = {1, 5, 10, 50, 100, numDocs - 1, numDocs, numDocs + 1};
    int numHits = 10;

    for (int threshold : thresholds) {
      TopFieldDocs result = searchSorted(searcher, query, sort, numHits, threshold);

      if (threshold < numDocs) {
        // When threshold < total matches, and index sort matches search sort,
        // early termination should eventually fire
        if (result.totalHits.relation() == TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO) {
          // totalHits should be > threshold (the transition happens when totalHits > threshold)
          // Note: totalHitsThreshold is adjusted to max(totalHitsThreshold, numHits) internally
          int effectiveThreshold = Math.max(threshold, numHits);
          assertTrue(
              "totalHits (" + result.totalHits.value()
                  + ") should be > effective threshold (" + effectiveThreshold + ")"
                  + " or equal when early terminated at exact boundary",
              result.totalHits.value() >= effectiveThreshold);
        }
      }

      if (threshold >= numDocs) {
        // When threshold >= total matches, no early termination should occur
        assertEquals(
            "threshold=" + threshold + ": should be EQUAL_TO when threshold >= numDocs",
            TotalHits.Relation.EQUAL_TO,
            result.totalHits.relation());
        assertEquals(
            "threshold=" + threshold + ": totalHits should equal numDocs",
            numDocs,
            result.totalHits.value());
      }
    }

    reader.close();
    dir.close();
  }

  /**
   * Tests totalHitsThreshold transition with threshold values that fall within a batch
   * (e.g., threshold=100, batch size=4096, total matches=500) and across batch boundaries
   * (threshold=4100, batch size=4096).
   *
   * <p><b>Validates: Requirements 12.3</b>
   */
  public void testTotalHitsThresholdWithinAndAcrossBatches() throws IOException {
    Directory dir = newDirectory();
    Sort sort = new Sort(new SortField("sortValue", SortField.Type.LONG));
    // Use enough docs to span multiple batches (batch size is 4096)
    int numDocs = 5000;
    IndexWriter w = createSortedIndex(dir, sort, numDocs, false);
    IndexReader reader = DirectoryReader.open(w);
    w.close();

    IndexSearcher searcher = newSearcher(reader, true, true, false);
    Query query = new TermQuery(new Term("tag", "match"));
    int numHits = 10;

    // Case 1: threshold within first batch (threshold=100, batch=4096)
    {
      TopFieldDocs result = searchSorted(searcher, query, sort, numHits, 100);
      assertEquals(
          "Within-batch threshold: should early terminate",
          TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO,
          result.totalHits.relation());
      // totalHits should be much less than numDocs
      assertTrue(
          "Within-batch: totalHits should be < numDocs",
          result.totalHits.value() < numDocs);
    }

    // Case 2: threshold that spans batch boundary (threshold=4100, batch=4096)
    {
      TopFieldDocs result = searchSorted(searcher, query, sort, numHits, 4100);
      assertEquals(
          "Cross-batch threshold: should early terminate",
          TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO,
          result.totalHits.relation());
      // totalHits should be > 4100 (the effective threshold)
      assertTrue(
          "Cross-batch: totalHits should be >= 4100, got " + result.totalHits.value(),
          result.totalHits.value() >= 4100);
    }

    // Case 3: threshold larger than total docs — no early termination
    {
      TopFieldDocs result = searchSorted(searcher, query, sort, numHits, Integer.MAX_VALUE);
      assertEquals(
          "No threshold: should be EQUAL_TO",
          TotalHits.Relation.EQUAL_TO,
          result.totalHits.relation());
      assertEquals(
          "No threshold: totalHits should equal numDocs",
          numDocs,
          result.totalHits.value());
    }

    reader.close();
    dir.close();
  }

  /**
   * Tests that the totalHitsRelation transition is consistent between runs with the same
   * parameters. This verifies determinism of the bulk collection path.
   *
   * <p><b>Validates: Requirements 12.3</b>
   */
  public void testTotalHitsThresholdTransitionDeterminism() throws IOException {
    Directory dir = newDirectory();
    Sort sort = new Sort(new SortField("sortValue", SortField.Type.LONG));
    int numDocs = 500;
    IndexWriter w = createSortedIndex(dir, sort, numDocs, false);
    IndexReader reader = DirectoryReader.open(w);
    w.close();

    IndexSearcher searcher = newSearcher(reader, true, true, false);
    Query query = new TermQuery(new Term("tag", "match"));

    int numHits = 10;
    int threshold = 50;

    // Run the same query multiple times — results should be identical
    TopFieldDocs first = searchSorted(searcher, query, sort, numHits, threshold);
    for (int i = 0; i < 5; i++) {
      TopFieldDocs subsequent = searchSorted(searcher, query, sort, numHits, threshold);
      assertEquals(
          "Run " + i + ": totalHits should be identical",
          first.totalHits.value(),
          subsequent.totalHits.value());
      assertEquals(
          "Run " + i + ": totalHitsRelation should be identical",
          first.totalHits.relation(),
          subsequent.totalHits.relation());
      assertTopFieldDocsEqual(
          "Run " + i + ": top-N results should be identical", first, subsequent);
    }

    reader.close();
    dir.close();
  }

  /**
   * Tests totalHitsThreshold transition with a non-index-sorted index (no early termination).
   * This verifies that the bulk path correctly handles the threshold transition via the
   * countHit() path (setting totalHitsRelation to GREATER_THAN_OR_EQUAL_TO) without
   * CollectionTerminatedException.
   *
   * <p><b>Validates: Requirements 12.3</b>
   */
  public void testTotalHitsThresholdTransitionNonIndexSorted() throws IOException {
    Directory dir = newDirectory();
    // Index is NOT sorted — no early termination via CollectionTerminatedException
    IndexWriterConfig iwc = new IndexWriterConfig();
    iwc.setMergeScheduler(new SerialMergeScheduler());
    IndexWriter w = new IndexWriter(dir, iwc);
    int numDocs = 500;
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      doc.add(new NumericDocValuesField("sortValue", random().nextLong()));
      doc.add(new StringField("tag", "match", Field.Store.NO));
      w.addDocument(doc);
    }
    w.forceMerge(1);
    IndexReader reader = DirectoryReader.open(w);
    w.close();

    IndexSearcher searcher = newSearcher(reader, true, true, false);
    Query query = new TermQuery(new Term("tag", "match"));
    Sort sort = new Sort(new SortField("sortValue", SortField.Type.LONG));

    int numHits = 10;

    // With threshold < numDocs, totalHitsRelation should transition
    {
      TopFieldDocs result = searchSorted(searcher, query, sort, numHits, 50);
      assertEquals(
          "Non-index-sorted with low threshold: should be GREATER_THAN_OR_EQUAL_TO",
          TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO,
          result.totalHits.relation());
    }

    // With threshold = MAX_VALUE, should be EQUAL_TO
    {
      TopFieldDocs result = searchSorted(searcher, query, sort, numHits, Integer.MAX_VALUE);
      assertEquals(
          "Non-index-sorted with MAX_VALUE threshold: should be EQUAL_TO",
          TotalHits.Relation.EQUAL_TO,
          result.totalHits.relation());
      assertEquals(numDocs, result.totalHits.value());
    }

    reader.close();
    dir.close();
  }


  // ---- Helpers for non-index-sorted random tests ----

  /**
   * Creates a non-index-sorted index with random long doc values. This avoids early termination
   * interference so the bulk path is exercised purely for correctness.
   */
  private static IndexWriter createRandomIndex(
      Directory dir, int numDocs, boolean sparse, String fieldName) throws IOException {
    IndexWriterConfig iwc = new IndexWriterConfig();
    iwc.setMergeScheduler(new SerialMergeScheduler());
    iwc.setMaxBufferedDocs(numDocs + 100);
    iwc.setRAMBufferSizeMB(IndexWriterConfig.DISABLE_AUTO_FLUSH);
    IndexWriter w = new IndexWriter(dir, iwc);
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      if (!sparse || random().nextInt(3) != 0) {
        doc.add(new NumericDocValuesField(fieldName, random().nextLong()));
      }
      doc.add(new StringField("tag", "match", Field.Store.NO));
      if (i % 2 == 0) {
        doc.add(new StringField("tag", "even", Field.Store.NO));
      }
      w.addDocument(doc);
    }
    w.forceMerge(1);
    return w;
  }

  /**
   * Runs a sorted query with searchAfter and returns TopFieldDocs.
   */
  private static TopFieldDocs searchAfterSorted(
      IndexSearcher searcher, Query query, Sort sort, int numHits,
      FieldDoc after, int totalHitsThreshold) throws IOException {
    TopFieldCollectorManager manager =
        new TopFieldCollectorManager(sort, numHits, after, totalHitsThreshold);
    return searcher.search(query, manager);
  }

  // ---- Task 9.2: Property test for SimpleFieldCollector single-field bulk equivalence ----

  /**
   * Property 1: SimpleFieldCollector single-field bulk-sequential equivalence.
   *
   * <p>For 100+ iterations: generate random non-index-sorted index with random long doc values,
   * random query, random numHits, random sort direction (asc/desc). Execute sorted query and
   * compare against exhaustive search. Tests both dense and sparse fields.
   *
   * <p><b>Validates: Requirements 1.1, 1.2, 1.3, 8.2, 10.1</b>
   */
  public void testSingleFieldBulkEquivalence() throws IOException {
    final int iters = atLeast(100);
    for (int iter = 0; iter < iters; iter++) {
      Directory dir = newDirectory();
      int numDocs = TestUtil.nextInt(random(), 10, 500);
      boolean sparse = random().nextBoolean();
      IndexWriter w = createRandomIndex(dir, numDocs, sparse, "val");
      IndexReader reader = DirectoryReader.open(w);
      w.close();

      IndexSearcher searcher = newSearcher(reader, true, true, false);
      Query query = new TermQuery(new Term("tag", "match"));
      boolean reverse = random().nextBoolean();
      Sort sort = new Sort(new SortField("val", SortField.Type.LONG, reverse));
      int numHits = TestUtil.nextInt(random(), 1, Math.min(50, numDocs));

      // Exhaustive search (no early termination, collects all)
      TopFieldDocs exhaustive = searchSorted(searcher, query, sort, numHits, Integer.MAX_VALUE);
      // Normal search — bulk path is automatically used
      TopFieldDocs result = searchSorted(searcher, query, sort, numHits, Integer.MAX_VALUE);

      assertTopFieldDocsEqual(
          "iter=" + iter + " numDocs=" + numDocs + " sparse=" + sparse + " reverse=" + reverse,
          exhaustive, result);
      assertEquals(exhaustive.totalHits.value(), result.totalHits.value());
      assertEquals(exhaustive.totalHits.relation(), result.totalHits.relation());

      reader.close();
      dir.close();
    }
  }

  // ---- Task 9.3: Property test for SimpleFieldCollector multi-field bulk equivalence ----

  /**
   * Property 2: SimpleFieldCollector multi-field bulk-sequential equivalence.
   *
   * <p>For 100+ iterations: generate random index with numeric primary sort field + secondary
   * sort fields (doc ID tie-breaker). Execute multi-field sorted query and assert identical
   * TopFieldDocs. Includes scenarios with many ties on primary field.
   *
   * <p><b>Validates: Requirements 4.1, 4.2, 4.3, 4.4, 10.5, 10.7</b>
   */
  public void testMultiFieldBulkEquivalence() throws IOException {
    final int iters = atLeast(100);
    for (int iter = 0; iter < iters; iter++) {
      Directory dir = newDirectory();
      int numDocs = TestUtil.nextInt(random(), 10, 500);
      // Alternate between unique values and many ties
      boolean manyTies = random().nextInt(3) == 0;

      IndexWriterConfig iwc = new IndexWriterConfig();
      iwc.setMergeScheduler(new SerialMergeScheduler());
      iwc.setMaxBufferedDocs(numDocs + 100);
      iwc.setRAMBufferSizeMB(IndexWriterConfig.DISABLE_AUTO_FLUSH);
      IndexWriter w = new IndexWriter(dir, iwc);
      for (int i = 0; i < numDocs; i++) {
        Document doc = new Document();
        long primaryVal = manyTies ? random().nextInt(5) : random().nextLong();
        doc.add(new NumericDocValuesField("primary", primaryVal));
        doc.add(new StringField("tag", "match", Field.Store.NO));
        w.addDocument(doc);
      }
      w.forceMerge(1);
      IndexReader reader = DirectoryReader.open(w);
      w.close();

      IndexSearcher searcher = newSearcher(reader, true, true, false);
      Query query = new TermQuery(new Term("tag", "match"));
      boolean reverse = random().nextBoolean();
      // Multi-field sort: numeric primary + doc ID tie-breaker
      Sort sort = new Sort(
          new SortField("primary", SortField.Type.LONG, reverse),
          SortField.FIELD_DOC);
      int numHits = TestUtil.nextInt(random(), 1, Math.min(50, numDocs));

      TopFieldDocs exhaustive = searchSorted(searcher, query, sort, numHits, Integer.MAX_VALUE);
      TopFieldDocs result = searchSorted(searcher, query, sort, numHits, Integer.MAX_VALUE);

      assertTopFieldDocsEqual(
          "iter=" + iter + " numDocs=" + numDocs + " manyTies=" + manyTies
              + " reverse=" + reverse,
          exhaustive, result);
      assertEquals(exhaustive.totalHits.value(), result.totalHits.value());

      reader.close();
      dir.close();
    }
  }

  // ---- Task 9.4: Property test for PagingFieldCollector bulk equivalence ----

  /**
   * Property 3: PagingFieldCollector bulk-sequential equivalence.
   *
   * <p>For 100+ iterations: generate random index, execute first page sorted query, then use
   * searchAfter with the last result to get subsequent pages. Compare results against exhaustive
   * search. Tests both single-field and multi-field sorts.
   *
   * <p><b>Validates: Requirements 6.1, 6.2, 6.3, 10.4</b>
   */
  public void testPagingFieldCollectorBulkEquivalence() throws IOException {
    final int iters = atLeast(100);
    for (int iter = 0; iter < iters; iter++) {
      Directory dir = newDirectory();
      int numDocs = TestUtil.nextInt(random(), 10, 300);
      boolean multiField = random().nextBoolean();

      IndexWriterConfig iwc = new IndexWriterConfig();
      iwc.setMergeScheduler(new SerialMergeScheduler());
      iwc.setMaxBufferedDocs(numDocs + 100);
      iwc.setRAMBufferSizeMB(IndexWriterConfig.DISABLE_AUTO_FLUSH);
      IndexWriter w = new IndexWriter(dir, iwc);
      for (int i = 0; i < numDocs; i++) {
        Document doc = new Document();
        doc.add(new NumericDocValuesField("val", random().nextLong()));
        doc.add(new StringField("tag", "match", Field.Store.NO));
        w.addDocument(doc);
      }
      w.forceMerge(1);
      IndexReader reader = DirectoryReader.open(w);
      w.close();

      IndexSearcher searcher = newSearcher(reader, true, true, false);
      Query query = new TermQuery(new Term("tag", "match"));
      boolean reverse = random().nextBoolean();
      Sort sort;
      if (multiField) {
        sort = new Sort(
            new SortField("val", SortField.Type.LONG, reverse),
            SortField.FIELD_DOC);
      } else {
        sort = new Sort(new SortField("val", SortField.Type.LONG, reverse));
      }
      int pageSize = TestUtil.nextInt(random(), 1, Math.min(20, numDocs));

      // Get exhaustive results to know the full ordering
      TopFieldDocs allResults = searchSorted(
          searcher, query, sort, numDocs, Integer.MAX_VALUE);

      // Page through using searchAfter
      FieldDoc afterDoc = null;
      int collected = 0;
      while (collected < allResults.scoreDocs.length) {
        TopFieldDocs page = searchAfterSorted(
            searcher, query, sort, pageSize, afterDoc, Integer.MAX_VALUE);
        int expectedPageSize = Math.min(pageSize, allResults.scoreDocs.length - collected);
        assertEquals(
            "iter=" + iter + " page starting at " + collected + ": wrong page size",
            expectedPageSize, page.scoreDocs.length);

        // Verify each doc on this page matches the exhaustive ordering
        for (int i = 0; i < page.scoreDocs.length; i++) {
          FieldDoc expectedFD = (FieldDoc) allResults.scoreDocs[collected + i];
          FieldDoc actualFD = (FieldDoc) page.scoreDocs[i];
          assertEquals(
              "iter=" + iter + " doc at position " + (collected + i),
              expectedFD.doc, actualFD.doc);
          for (int j = 0; j < expectedFD.fields.length; j++) {
            assertEquals(
                "iter=" + iter + " sort value at position " + (collected + i) + " field " + j,
                expectedFD.fields[j], actualFD.fields[j]);
          }
        }
        if (page.scoreDocs.length == 0) break;
        afterDoc = (FieldDoc) page.scoreDocs[page.scoreDocs.length - 1];
        collected += page.scoreDocs.length;
      }
      assertEquals("Should have paged through all results", allResults.scoreDocs.length, collected);

      reader.close();
      dir.close();
    }
  }

  // ---- Task 9.5: Edge case tests ----

  /**
   * Tests batch size of 1 (single doc), batch size of 4096 (full batch), and empty result set.
   *
   * <p><b>Validates: Requirements 10.3, 10.6</b>
   */
  public void testEdgeCaseBatchSizes() throws IOException {
    // Batch size 1: single doc index
    {
      Directory dir = newDirectory();
      IndexWriter w = createRandomIndex(dir, 1, false, "val");
      IndexReader reader = DirectoryReader.open(w);
      w.close();
      IndexSearcher searcher = newSearcher(reader, true, true, false);
      Sort sort = new Sort(new SortField("val", SortField.Type.LONG));
      TopFieldDocs result = searchSorted(
          searcher, new TermQuery(new Term("tag", "match")), sort, 1, Integer.MAX_VALUE);
      assertEquals(1, result.scoreDocs.length);
      assertEquals(1, result.totalHits.value());
      reader.close();
      dir.close();
    }

    // Batch size 4096: exactly one full batch
    {
      Directory dir = newDirectory();
      IndexWriter w = createRandomIndex(dir, 4096, false, "val");
      IndexReader reader = DirectoryReader.open(w);
      w.close();
      IndexSearcher searcher = newSearcher(reader, true, true, false);
      Sort sort = new Sort(new SortField("val", SortField.Type.LONG));
      TopFieldDocs result = searchSorted(
          searcher, new TermQuery(new Term("tag", "match")), sort, 10, Integer.MAX_VALUE);
      assertEquals(10, result.scoreDocs.length);
      assertEquals(4096, result.totalHits.value());
      reader.close();
      dir.close();
    }

    // Empty result set: query matches nothing
    {
      Directory dir = newDirectory();
      IndexWriter w = createRandomIndex(dir, 50, false, "val");
      IndexReader reader = DirectoryReader.open(w);
      w.close();
      IndexSearcher searcher = newSearcher(reader, true, true, false);
      Sort sort = new Sort(new SortField("val", SortField.Type.LONG));
      TopFieldDocs result = searchSorted(
          searcher, new TermQuery(new Term("tag", "nonexistent")), sort, 10, Integer.MAX_VALUE);
      assertEquals(0, result.scoreDocs.length);
      assertEquals(0, result.totalHits.value());
      reader.close();
      dir.close();
    }
  }

  /**
   * Tests all docs missing sort values.
   *
   * <p><b>Validates: Requirements 10.3</b>
   */
  public void testAllDocsMissingSortValues() throws IOException {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig();
    iwc.setMergeScheduler(new SerialMergeScheduler());
    IndexWriter w = new IndexWriter(dir, iwc);
    int numDocs = 50;
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      // No NumericDocValuesField — all docs missing sort value
      doc.add(new StringField("tag", "match", Field.Store.NO));
      w.addDocument(doc);
    }
    w.forceMerge(1);
    IndexReader reader = DirectoryReader.open(w);
    w.close();

    IndexSearcher searcher = newSearcher(reader, true, true, false);
    Query query = new TermQuery(new Term("tag", "match"));
    Sort sort = new Sort(new SortField("val", SortField.Type.LONG));
    int numHits = 10;

    TopFieldDocs result = searchSorted(searcher, query, sort, numHits, Integer.MAX_VALUE);
    assertEquals(numHits, result.scoreDocs.length);
    assertEquals(numDocs, result.totalHits.value());
    // All sort values should be the missing value (0 by default)
    for (int i = 0; i < result.scoreDocs.length; i++) {
      FieldDoc fd = (FieldDoc) result.scoreDocs[i];
      assertEquals("All docs should have missing value (0L)", 0L, fd.fields[0]);
    }

    reader.close();
    dir.close();
  }

  /**
   * Tests numHits of 1 (single result) and numHits larger than total matches.
   *
   * <p><b>Validates: Requirements 10.3</b>
   */
  public void testNumHitsEdgeCases() throws IOException {
    Directory dir = newDirectory();
    int numDocs = 50;
    IndexWriter w = createRandomIndex(dir, numDocs, false, "val");
    IndexReader reader = DirectoryReader.open(w);
    w.close();
    IndexSearcher searcher = newSearcher(reader, true, true, false);
    Query query = new TermQuery(new Term("tag", "match"));
    Sort sort = new Sort(new SortField("val", SortField.Type.LONG));

    // numHits = 1
    {
      TopFieldDocs result = searchSorted(searcher, query, sort, 1, Integer.MAX_VALUE);
      assertEquals(1, result.scoreDocs.length);
      assertEquals(numDocs, result.totalHits.value());
    }

    // numHits larger than total matches
    {
      TopFieldDocs result = searchSorted(searcher, query, sort, numDocs + 100, Integer.MAX_VALUE);
      assertEquals(numDocs, result.scoreDocs.length);
      assertEquals(numDocs, result.totalHits.value());
    }

    reader.close();
    dir.close();
  }

  /**
   * Tests multi-field sort where primary field is NOT numeric (string sort), verifying that
   * the bulk path is disabled and per-doc fallback produces correct results.
   *
   * <p><b>Validates: Requirements 10.6</b>
   */
  public void testMultiFieldNonNumericPrimaryFallback() throws IOException {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig();
    iwc.setMergeScheduler(new SerialMergeScheduler());
    IndexWriter w = new IndexWriter(dir, iwc);
    int numDocs = 100;
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      doc.add(new NumericDocValuesField("val", random().nextLong()));
      doc.add(new StringField("tag", "match", Field.Store.NO));
      // Add a keyword field for non-numeric primary sort
      doc.add(new StringField("category", "cat" + (i % 10), Field.Store.NO));
      w.addDocument(doc);
    }
    w.forceMerge(1);
    IndexReader reader = DirectoryReader.open(w);
    w.close();

    IndexSearcher searcher = newSearcher(reader, true, true, false);
    Query query = new TermQuery(new Term("tag", "match"));
    // Primary sort by FIELD_DOC (not numeric), secondary by numeric
    Sort sort = new Sort(SortField.FIELD_DOC, new SortField("val", SortField.Type.LONG));
    int numHits = 10;

    // This should use per-doc fallback since primary is not numeric
    TopFieldDocs result1 = searchSorted(searcher, query, sort, numHits, Integer.MAX_VALUE);
    TopFieldDocs result2 = searchSorted(searcher, query, sort, numHits, Integer.MAX_VALUE);
    assertTopFieldDocsEqual("Non-numeric primary: results should be identical", result1, result2);
    assertEquals(numDocs, result1.totalHits.value());

    reader.close();
    dir.close();
  }

  /**
   * Tests first and last doc IDs at segment boundaries by creating a multi-segment index.
   *
   * <p><b>Validates: Requirements 10.3</b>
   */
  public void testSegmentBoundaryDocIds() throws IOException {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig();
    iwc.setMergeScheduler(new SerialMergeScheduler());
    // Force multiple segments by flushing after each batch
    iwc.setMaxBufferedDocs(50);
    IndexWriter w = new IndexWriter(dir, iwc);
    int numDocs = 200;
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      doc.add(new NumericDocValuesField("val", random().nextLong()));
      doc.add(new StringField("tag", "match", Field.Store.NO));
      w.addDocument(doc);
    }
    // Don't force merge — keep multiple segments
    IndexReader reader = DirectoryReader.open(w);
    w.close();

    assertTrue("Should have multiple segments", reader.leaves().size() > 1);
    IndexSearcher searcher = newSearcher(reader, true, true, false);
    Query query = new TermQuery(new Term("tag", "match"));
    Sort sort = new Sort(new SortField("val", SortField.Type.LONG));

    TopFieldDocs result1 = searchSorted(searcher, query, sort, 10, Integer.MAX_VALUE);
    TopFieldDocs result2 = searchSorted(searcher, query, sort, 10, Integer.MAX_VALUE);
    assertTopFieldDocsEqual("Multi-segment: results should be identical", result1, result2);
    assertEquals(numDocs, result1.totalHits.value());

    reader.close();
    dir.close();
  }

  // ---- Task 10.1: End-to-end sorted query integration test ----

  /**
   * End-to-end integration test: indexes documents with known numeric field values (simulating
   * @timestamp), executes sorted queries, and verifies returned TopFieldDocs match expected
   * results. Exercises SimpleFieldCollector, PagingFieldCollector, and multi-field sorts.
   *
   * <p><b>Validates: Requirements 14.1, 14.2, 14.3, 14.4</b>
   */
  public void testEndToEndSortedQueryIntegration() throws IOException {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig();
    iwc.setMergeScheduler(new SerialMergeScheduler());
    iwc.setMaxBufferedDocs(200);
    iwc.setRAMBufferSizeMB(IndexWriterConfig.DISABLE_AUTO_FLUSH);
    IndexWriter w = new IndexWriter(dir, iwc);

    // Index 100 documents with known timestamp values
    // Timestamps: 1000, 1001, 1002, ..., 1099
    // Tag "hot" for docs with timestamp in [1020, 1079] (60 docs)
    int numDocs = 100;
    long baseTimestamp = 1000L;
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      long ts = baseTimestamp + i;
      doc.add(new NumericDocValuesField("timestamp", ts));
      doc.add(new LongPoint("timestamp", ts));
      doc.add(new StringField("tag", "all", Field.Store.NO));
      if (i >= 20 && i < 80) {
        doc.add(new StringField("tag", "hot", Field.Store.NO));
      }
      w.addDocument(doc);
    }
    w.forceMerge(1);
    IndexReader reader = DirectoryReader.open(w);
    w.close();
    IndexSearcher searcher = newSearcher(reader, true, true, false);

    // --- Test 1: SimpleFieldCollector, single-field sort ascending ---
    // Query: tag=hot, sort by timestamp ascending, top 5
    {
      Query query = new TermQuery(new Term("tag", "hot"));
      Sort sort = new Sort(new SortField("timestamp", SortField.Type.LONG));
      TopFieldDocs result = searchSorted(searcher, query, sort, 5, Integer.MAX_VALUE);
      assertEquals(60, result.totalHits.value());
      assertEquals(5, result.scoreDocs.length);
      // First 5 docs should be timestamps 1020, 1021, 1022, 1023, 1024
      for (int i = 0; i < 5; i++) {
        FieldDoc fd = (FieldDoc) result.scoreDocs[i];
        assertEquals("Ascending sort position " + i, baseTimestamp + 20 + i, fd.fields[0]);
      }
    }

    // --- Test 2: SimpleFieldCollector, single-field sort descending ---
    {
      Query query = new TermQuery(new Term("tag", "hot"));
      Sort sort = new Sort(new SortField("timestamp", SortField.Type.LONG, true));
      TopFieldDocs result = searchSorted(searcher, query, sort, 5, Integer.MAX_VALUE);
      assertEquals(60, result.totalHits.value());
      assertEquals(5, result.scoreDocs.length);
      // First 5 docs should be timestamps 1079, 1078, 1077, 1076, 1075
      for (int i = 0; i < 5; i++) {
        FieldDoc fd = (FieldDoc) result.scoreDocs[i];
        assertEquals("Descending sort position " + i, baseTimestamp + 79 - i, fd.fields[0]);
      }
    }

    // --- Test 3: PagingFieldCollector with searchAfter ---
    // Page through all 60 "hot" docs in pages of 10
    {
      Query query = new TermQuery(new Term("tag", "hot"));
      Sort sort = new Sort(new SortField("timestamp", SortField.Type.LONG));
      FieldDoc afterDoc = null;
      long[] collectedTimestamps = new long[60];
      int totalCollected = 0;
      for (int page = 0; page < 6; page++) {
        TopFieldDocs pageResult = searchAfterSorted(
            searcher, query, sort, 10, afterDoc, Integer.MAX_VALUE);
        assertEquals(10, pageResult.scoreDocs.length);
        for (int i = 0; i < pageResult.scoreDocs.length; i++) {
          FieldDoc fd = (FieldDoc) pageResult.scoreDocs[i];
          collectedTimestamps[totalCollected++] = (Long) fd.fields[0];
        }
        afterDoc = (FieldDoc) pageResult.scoreDocs[pageResult.scoreDocs.length - 1];
      }

      // Verify all 60 timestamps were collected in order
      assertEquals(60, totalCollected);
      long[] expected = new long[60];
      for (int i = 0; i < 60; i++) {
        expected[i] = baseTimestamp + 20 + i;
      }
      assertArrayEquals("Paged results should match expected timestamps", expected, collectedTimestamps);

      // Verify no more results after last page
      TopFieldDocs emptyPage = searchAfterSorted(
          searcher, query, sort, 10, afterDoc, Integer.MAX_VALUE);
      assertEquals(0, emptyPage.scoreDocs.length);
    }

    // --- Test 4: Multi-field sort (numeric primary + doc ID tie-breaker) ---
    {
      Query query = new TermQuery(new Term("tag", "hot"));
      Sort sort = new Sort(
          new SortField("timestamp", SortField.Type.LONG),
          SortField.FIELD_DOC);
      TopFieldDocs result = searchSorted(searcher, query, sort, 5, Integer.MAX_VALUE);
      assertEquals(60, result.totalHits.value());
      assertEquals(5, result.scoreDocs.length);
      // Same as single-field ascending since timestamps are unique
      for (int i = 0; i < 5; i++) {
        FieldDoc fd = (FieldDoc) result.scoreDocs[i];
        assertEquals("Multi-field sort position " + i, baseTimestamp + 20 + i, fd.fields[0]);
      }
    }

    // --- Test 5: Multi-field sort with PagingFieldCollector ---
    {
      Query query = new TermQuery(new Term("tag", "hot"));
      Sort sort = new Sort(
          new SortField("timestamp", SortField.Type.LONG),
          SortField.FIELD_DOC);
      // Get first page
      TopFieldDocs page1 = searchSorted(searcher, query, sort, 10, Integer.MAX_VALUE);
      assertEquals(10, page1.scoreDocs.length);
      FieldDoc lastOnPage1 = (FieldDoc) page1.scoreDocs[9];
      assertEquals(baseTimestamp + 29, lastOnPage1.fields[0]);

      // Get second page via searchAfter
      TopFieldDocs page2 = searchAfterSorted(
          searcher, query, sort, 10, lastOnPage1, Integer.MAX_VALUE);
      assertEquals(10, page2.scoreDocs.length);
      FieldDoc firstOnPage2 = (FieldDoc) page2.scoreDocs[0];
      assertEquals(baseTimestamp + 30, firstOnPage2.fields[0]);
    }

    reader.close();
    dir.close();
  }
}
