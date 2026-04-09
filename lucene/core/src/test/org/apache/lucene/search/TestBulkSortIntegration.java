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
import org.apache.lucene.codecs.lucene103.Lucene103Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.FloatDocValuesField;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.util.BytesRef;

/**
 * Integration tests for bulk sort path covering all numeric types, keyword sort,
 * selectivity variations, doc value encoding variations, tiebreaker stress,
 * and bulk path + correctness validation.
 *
 * Uses Lucene103Codec directly (no Asserting wrapper) so instanceof checks in
 * TopFieldCollector succeed and the bulk path is always exercised.
 */
public class TestBulkSortIntegration extends LuceneTestCase {

  private static final int N = 200_000;

  /**
   * Index docs with rich field variety for all sort types and encoding variations.
   *
   * Fields:
   * - ndv_long: random long (high BPV, varying encoding)
   * - ndv_int: random int stored as long (IntComparator)
   * - ndv_float: random float stored as sortable long (FloatComparator)
   * - ndv_double: random double stored as sortable long (DoubleComparator)
   * - ndv_constant: constant value 42 (BPV=0 encoding)
   * - ndv_gcd: values 0,100,200,...,9900 (GCD=100 encoding)
   * - ndv_table: values from {10,20,30,40,50} (table encoding)
   * - ndv_lowcard: 0-9 (low cardinality, many ties for tiebreaker tests)
   * - ndv_sparse: only 1% of docs (very sparse DISI)
   * - keyword: high cardinality sorted doc values
   * - keyword_low: 10 unique values (tiebreaker stress)
   * - keyword_sparse: 90% of docs have it
   * - point: IntPoint for range queries with selectivity control
   * - s: "a" or "b" for TermQuery
   * - del: "yes" on 20% of docs (for delete tests)
   */
  private void indexRichDocs(Directory dir, boolean withDeletes) throws IOException {
    IndexWriterConfig conf = new IndexWriterConfig(null);
    conf.setCodec(new Lucene103Codec());
    conf.setMaxBufferedDocs(N + 1);
    if (withDeletes) conf.setMergePolicy(NoMergePolicy.INSTANCE);
    long[] table = {10, 20, 30, 40, 50};
    try (IndexWriter w = new IndexWriter(dir, conf)) {
      for (int i = 0; i < N; i++) {
        Document doc = new Document();
        doc.add(new NumericDocValuesField("ndv_long", random().nextLong()));
        doc.add(new NumericDocValuesField("ndv_int", random().nextInt()));
        doc.add(new FloatDocValuesField("ndv_float", random().nextFloat() * 10000));
        doc.add(new NumericDocValuesField("ndv_double",
            Double.doubleToRawLongBits(random().nextDouble() * 100000)));
        doc.add(new NumericDocValuesField("ndv_constant", 42));
        doc.add(new NumericDocValuesField("ndv_gcd", (long)(random().nextInt(100)) * 100));
        doc.add(new NumericDocValuesField("ndv_table", table[random().nextInt(table.length)]));
        doc.add(new NumericDocValuesField("ndv_lowcard", random().nextInt(10)));
        if (random().nextInt(100) == 0) { // 1% sparse
          doc.add(new NumericDocValuesField("ndv_sparse", random().nextLong()));
        }
        doc.add(new SortedDocValuesField("keyword",
            new BytesRef(String.format("kw_%08d", random().nextInt(N / 2)))));
        doc.add(new SortedDocValuesField("keyword_low",
            new BytesRef("cat_" + random().nextInt(10))));
        if (random().nextInt(10) > 0) {
          doc.add(new SortedDocValuesField("keyword_sparse",
              new BytesRef("sp_" + random().nextInt(1000))));
        }
        doc.add(new IntPoint("point", i)); // sequential for selectivity control
        doc.add(new StringField("s", random().nextBoolean() ? "a" : "b", Store.NO));
        if (withDeletes) {
          doc.add(new StringField("del", i % 5 == 0 ? "yes" : "no", Store.NO));
        }
        w.addDocument(doc);
      }
      if (withDeletes) {
        w.flush();
        w.deleteDocuments(new Term("del", "yes"));
      }
      if (!withDeletes) w.forceMerge(1);
    }
  }

  // ---- Core validation helper ----

  /**
   * Run a sort query with bulk ON and OFF, assert:
   * 1. Correctness: results match exactly
   * 2. Bulk path taken: BulkCollectionTracker detects collect(DocIdStream) calls
   */
  private void assertBulkSort(Directory dir, Sort sort, Query query, int topN) throws Exception {
    try (IndexReader reader = DirectoryReader.open(dir)) {
      IndexSearcher searcher = new IndexSearcher(reader);

      // Bulk path with tracking
      PrefetchConfig.setEnabled(true);
      BulkCollectionTracker tracker = new BulkCollectionTracker();
      TopFieldDocs bulkResults = searcher.search(query,
          tracker.wrap(new TopFieldCollectorManager(sort, topN, null, Integer.MAX_VALUE)));

      // Per-doc path (no tracking needed)
      PrefetchConfig.setEnabled(false);
      TopFieldDocs perDocResults = searcher.search(query, topN, sort);
      PrefetchConfig.setEnabled(true);

      // 1. Correctness
      assertEquals("totalHits mismatch for " + sort,
          perDocResults.totalHits.value(), bulkResults.totalHits.value());
      assertEquals("result count mismatch for " + sort,
          perDocResults.scoreDocs.length, bulkResults.scoreDocs.length);
      for (int i = 0; i < perDocResults.scoreDocs.length; i++) {
        FieldDoc expected = (FieldDoc) perDocResults.scoreDocs[i];
        FieldDoc actual = (FieldDoc) bulkResults.scoreDocs[i];
        assertEquals("doc mismatch at " + i + " for " + sort, expected.doc, actual.doc);
        assertArrayEquals("fields mismatch at " + i + " for " + sort,
            expected.fields, actual.fields);
      }

      // 2. Bulk path taken
      if (tracker.collectStreamCount() > 0) {
        assertTrue("Bulk path not taken for " + sort
            + " (streamCount=" + tracker.collectStreamCount()
            + ", bulkCount=" + tracker.bulkCollectCount() + ")",
            tracker.bulkCollectCount() > 0);
      }
    }
  }

  // ==================== Numeric type coverage ====================

  public void testLongSort() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, false);
      assertBulkSort(dir, new Sort(new SortField("ndv_long", SortField.Type.LONG)), new MatchAllDocsQuery(), 10);
      assertBulkSort(dir, new Sort(new SortField("ndv_long", SortField.Type.LONG, true)), new MatchAllDocsQuery(), 10);
    }
  }

  public void testIntSort() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, false);
      assertBulkSort(dir, new Sort(new SortField("ndv_int", SortField.Type.INT)), new MatchAllDocsQuery(), 10);
      assertBulkSort(dir, new Sort(new SortField("ndv_int", SortField.Type.INT, true)), new MatchAllDocsQuery(), 10);
    }
  }

  public void testFloatSort() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, false);
      assertBulkSort(dir, new Sort(new SortField("ndv_float", SortField.Type.FLOAT)), new MatchAllDocsQuery(), 10);
      assertBulkSort(dir, new Sort(new SortField("ndv_float", SortField.Type.FLOAT, true)), new MatchAllDocsQuery(), 10);
    }
  }

  public void testDoubleSort() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, false);
      assertBulkSort(dir, new Sort(new SortField("ndv_double", SortField.Type.DOUBLE)), new MatchAllDocsQuery(), 10);
    }
  }

  public void testKeywordSort() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, false);
      assertBulkSort(dir, new Sort(new SortField("keyword", SortField.Type.STRING)), new MatchAllDocsQuery(), 10);
      assertBulkSort(dir, new Sort(new SortField("keyword", SortField.Type.STRING, true)), new MatchAllDocsQuery(), 10);
    }
  }

  // ==================== Doc value encoding variations ====================

  public void testConstantValueSort() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, false);
      // BPV=0 — all docs have value 42. Sort is stable by doc ID.
      assertBulkSort(dir, new Sort(new SortField("ndv_constant", SortField.Type.LONG)), new MatchAllDocsQuery(), 10);
    }
  }

  public void testGCDEncodingSort() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, false);
      assertBulkSort(dir, new Sort(new SortField("ndv_gcd", SortField.Type.LONG)), new MatchAllDocsQuery(), 10);
    }
  }

  public void testTableEncodingSort() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, false);
      assertBulkSort(dir, new Sort(new SortField("ndv_table", SortField.Type.LONG)), new MatchAllDocsQuery(), 10);
    }
  }

  public void testSparseFieldSort() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, false);
      SortField sf = new SortField("ndv_sparse", SortField.Type.LONG);
      sf.setMissingValue(Long.MAX_VALUE);
      assertBulkSort(dir, new Sort(sf), new MatchAllDocsQuery(), 10);
    }
  }

  public void testSparseKeywordSort() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, false);
      SortField sf = new SortField("keyword_sparse", SortField.Type.STRING);
      sf.setMissingValue(SortField.STRING_LAST);
      assertBulkSort(dir, new Sort(sf), new MatchAllDocsQuery(), 10);
    }
  }

  // ==================== Selectivity variations ====================

  /** Low selectivity: ~1% of docs match (IntPoint range 0..2000 out of 200K). */
  public void testLowSelectivityLongSort() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, false);
      assertBulkSort(dir, new Sort(new SortField("ndv_long", SortField.Type.LONG)),
          IntPoint.newRangeQuery("point", 0, N / 100), 10);
    }
  }

  /** Medium selectivity: ~50% of docs match. */
  public void testMediumSelectivityKeywordSort() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, false);
      assertBulkSort(dir, new Sort(new SortField("keyword", SortField.Type.STRING)),
          IntPoint.newRangeQuery("point", 0, N / 2), 10);
    }
  }

  /** High selectivity: ~99% of docs match. */
  public void testHighSelectivityFloatSort() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, false);
      assertBulkSort(dir, new Sort(new SortField("ndv_float", SortField.Type.FLOAT)),
          IntPoint.newRangeQuery("point", 0, N * 99 / 100), 10);
    }
  }

  /** TermQuery selectivity: ~50% match. */
  public void testTermQuerySelectivity() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, false);
      assertBulkSort(dir, new Sort(new SortField("ndv_long", SortField.Type.LONG)),
          new TermQuery(new Term("s", "a")), 10);
    }
  }

  // ==================== Tiebreaker stress ====================

  /** Low-cardinality primary (many ties) + high-cardinality secondary. */
  public void testTiebreakerLowCardPrimaryNumeric() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, false);
      // ndv_lowcard has 10 values → ~20K docs per value → massive ties
      // ndv_long breaks ties
      assertBulkSort(dir, new Sort(
          new SortField("ndv_lowcard", SortField.Type.LONG),
          new SortField("ndv_long", SortField.Type.LONG)), new MatchAllDocsQuery(), 10);
    }
  }

  /** Low-cardinality keyword primary + numeric secondary. */
  public void testTiebreakerLowCardKeyword() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, false);
      // keyword_low has 10 values → massive ties
      assertBulkSort(dir, new Sort(
          new SortField("keyword_low", SortField.Type.STRING),
          new SortField("ndv_long", SortField.Type.LONG)), new MatchAllDocsQuery(), 10);
    }
  }

  /** Numeric primary + keyword secondary tiebreaker. */
  public void testTiebreakerNumericThenKeyword() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, false);
      assertBulkSort(dir, new Sort(
          new SortField("ndv_lowcard", SortField.Type.LONG),
          new SortField("keyword", SortField.Type.STRING)), new MatchAllDocsQuery(), 10);
    }
  }

  /** Triple sort: low-card + low-card + high-card. */
  public void testTiebreakerTripleField() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, false);
      assertBulkSort(dir, new Sort(
          new SortField("ndv_table", SortField.Type.LONG),       // 5 values
          new SortField("keyword_low", SortField.Type.STRING),   // 10 values
          new SortField("ndv_long", SortField.Type.LONG)),       // unique
          new MatchAllDocsQuery(), 10);
    }
  }

  /** Constant primary (all ties) + secondary breaks everything. */
  public void testTiebreakerConstantPrimary() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, false);
      // ndv_constant = 42 for all docs → 100% ties → secondary decides everything
      assertBulkSort(dir, new Sort(
          new SortField("ndv_constant", SortField.Type.LONG),
          new SortField("keyword", SortField.Type.STRING)), new MatchAllDocsQuery(), 10);
    }
  }

  // ==================== Deleted docs ====================

  public void testDeletedDocsLongSort() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, true);
      assertBulkSort(dir, new Sort(new SortField("ndv_long", SortField.Type.LONG)),
          new MatchAllDocsQuery(), 10);
    }
  }

  public void testDeletedDocsKeywordSort() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, true);
      assertBulkSort(dir, new Sort(new SortField("keyword", SortField.Type.STRING)),
          new MatchAllDocsQuery(), 10);
    }
  }

  public void testDeletedDocsWithFilter() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, true);
      assertBulkSort(dir, new Sort(new SortField("ndv_long", SortField.Type.LONG)),
          new TermQuery(new Term("s", "a")), 10);
    }
  }

  // ==================== Mixed type multi-field ====================

  public void testIntThenFloat() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, false);
      assertBulkSort(dir, new Sort(
          new SortField("ndv_int", SortField.Type.INT),
          new SortField("ndv_float", SortField.Type.FLOAT)), new MatchAllDocsQuery(), 10);
    }
  }

  public void testDoubleThenKeyword() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, false);
      assertBulkSort(dir, new Sort(
          new SortField("ndv_double", SortField.Type.DOUBLE),
          new SortField("keyword", SortField.Type.STRING)), new MatchAllDocsQuery(), 10);
    }
  }

  public void testKeywordThenFloat() throws Exception {
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, false);
      assertBulkSort(dir, new Sort(
          new SortField("keyword_low", SortField.Type.STRING),
          new SortField("ndv_float", SortField.Type.FLOAT)), new MatchAllDocsQuery(), 10);
    }
  }

  // ==================== Comprehensive randomized ====================

  /** Randomized: random sort type, random query, random deletes, random topN. */
  public void testFullyRandomized() throws Exception {
    boolean withDeletes = random().nextBoolean();
    try (Directory dir = newDirectory()) {
      indexRichDocs(dir, withDeletes);

      // Random primary sort
      SortField primary = randomSortField();
      // Random secondary (50% chance)
      Sort sort = random().nextBoolean()
          ? new Sort(primary, randomSortField())
          : new Sort(primary);

      // Random query with selectivity control
      Query query = randomSelectivityQuery();
      int topN = random().nextInt(1, 100);

      assertBulkSort(dir, sort, query, topN);
    }
  }

  /** Run randomized test 5 times for more coverage. */
  public void testFullyRandomizedRepeat() throws Exception {
    for (int iter = 0; iter < 5; iter++) {
      testFullyRandomized();
    }
  }

  private SortField randomSortField() {
    boolean reverse = random().nextBoolean();
    switch (random().nextInt(8)) {
      case 0: return new SortField("ndv_long", SortField.Type.LONG, reverse);
      case 1: return new SortField("ndv_int", SortField.Type.INT, reverse);
      case 2: return new SortField("ndv_float", SortField.Type.FLOAT, reverse);
      case 3: return new SortField("ndv_double", SortField.Type.DOUBLE, reverse);
      case 4: return new SortField("ndv_lowcard", SortField.Type.LONG, reverse);
      case 5: return new SortField("keyword", SortField.Type.STRING, reverse);
      case 6: return new SortField("keyword_low", SortField.Type.STRING, reverse);
      default: {
        SortField sf = new SortField("ndv_sparse", SortField.Type.LONG, reverse);
        sf.setMissingValue(reverse ? Long.MIN_VALUE : Long.MAX_VALUE);
        return sf;
      }
    }
  }

  private Query randomSelectivityQuery() {
    switch (random().nextInt(5)) {
      case 0: return new MatchAllDocsQuery();                              // 100%
      case 1: return new TermQuery(new Term("s", "a"));                    // ~50%
      case 2: return IntPoint.newRangeQuery("point", 0, N / 100);         // ~1%
      case 3: return IntPoint.newRangeQuery("point", 0, N * 99 / 100);    // ~99%
      default: return IntPoint.newRangeQuery("point", 0, N / 2);          // ~50%
    }
  }
}
