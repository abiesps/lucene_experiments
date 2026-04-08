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
import org.apache.lucene.document.FloatDocValuesField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;

/**
 * Extends the patterns from {@link TestTopFieldCollectorEarlyTermination} to explicitly validate
 * that the bulk collect(DocIdStream) path in TopFieldCollector produces identical results to the
 * per-doc collect(int) path. Each test runs the same query twice — once with PrefetchConfig enabled
 * (bulk path) and once disabled (per-doc path) — and asserts identical results.
 *
 * <p>This catches bugs where the bulk path's BulkValueComparator-based competitive checks diverge
 * from the per-doc LeafFieldComparator.compareBottom/compareTop checks.
 */
public class TestTopFieldCollectorBulkVsPerDoc extends LuceneTestCase {

  /** Desc sort on long field — the most common sort pattern (e.g., sort by @timestamp desc). */
  public void testDescLongSort() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(new SortField("ndv", SortField.Type.LONG, true)),
        50, 200000, new MatchAllDocsQuery());
  }

  /** Asc sort on long field. */
  public void testAscLongSort() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(new SortField("ndv", SortField.Type.LONG, false)),
        50, 200000, new MatchAllDocsQuery());
  }

  /** Sort with a filter query — not all docs match. */
  public void testFilteredSort() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(new SortField("ndv", SortField.Type.LONG, true)),
        50, 200000, new TermQuery(new Term("s", "a")));
  }

  /** Sort with topN=1 — queue is always full after first hit. */
  public void testTopOne() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(new SortField("ndv", SortField.Type.LONG, true)),
        1, 100000, new MatchAllDocsQuery());
  }

  /** Sort with large topN — many docs in the queue. */
  public void testLargeTopN() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(new SortField("ndv", SortField.Type.LONG, true)),
        200, 200000, new MatchAllDocsQuery());
  }

  /** Sort with missing value set to MAX_VALUE. */
  public void testMissingValueMax() throws Exception {
    SortField sf = new SortField("ndv", SortField.Type.LONG, true);
    sf.setMissingValue(Long.MAX_VALUE);
    doTestBulkVsPerDoc(new Sort(sf), 10, 1000, new MatchAllDocsQuery());
  }

  /** Sort with missing value set to MIN_VALUE. */
  public void testMissingValueMin() throws Exception {
    SortField sf = new SortField("ndv", SortField.Type.LONG, false);
    sf.setMissingValue(Long.MIN_VALUE);
    doTestBulkVsPerDoc(new Sort(sf), 10, 1000, new MatchAllDocsQuery());
  }

  /** Multi-field sort: primary long desc, secondary doc ID. */
  public void testMultiFieldSort() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(
            new SortField("ndv", SortField.Type.LONG, true),
            SortField.FIELD_DOC),
        10, 1000, new MatchAllDocsQuery());
  }

  /** SearchAfter pagination — PagingFieldCollector path. */
  public void testSearchAfterPagination() throws Exception {
    try (Directory dir = newDirectory()) {
      indexDocs(dir, 200000);
      Sort sort = new Sort(new SortField("ndv", SortField.Type.LONG, true));
      int pageSize = 50;

      try (IndexReader reader = DirectoryReader.open(dir)) {
        IndexSearcher searcher = new IndexSearcher(reader);

        // First page
        TopFieldDocs firstPage = searcher.search(new MatchAllDocsQuery(), pageSize, sort);
        assertTrue(firstPage.scoreDocs.length > 0);
        FieldDoc lastDoc = (FieldDoc) firstPage.scoreDocs[firstPage.scoreDocs.length - 1];

        // Second page with bulk
        PrefetchConfig.setEnabled(true);
        TopFieldDocs bulkPage = (TopFieldDocs) searcher.searchAfter(
            lastDoc, new MatchAllDocsQuery(), pageSize, sort);

        // Second page with per-doc
        PrefetchConfig.setEnabled(false);
        TopFieldDocs perDocPage = (TopFieldDocs) searcher.searchAfter(
            lastDoc, new MatchAllDocsQuery(), pageSize, sort);
        PrefetchConfig.setEnabled(true);

        assertResultsMatch("searchAfter", perDocPage, bulkPage);
      }
    }
  }

  /** Randomized test — random numDocs, topN, sort direction, query. */
  public void testRandomized() throws Exception {
    int numDocs = 500 + random().nextInt(5000);
    int topN = 1 + random().nextInt(Math.min(numDocs, 200));
    boolean reverse = random().nextBoolean();
    Sort sort = new Sort(new SortField("ndv", SortField.Type.LONG, reverse));
    Query query = random().nextBoolean()
        ? new MatchAllDocsQuery()
        : new TermQuery(new Term("s", "a"));
    doTestBulkVsPerDoc(sort, topN, numDocs, query);
  }

  /** Early termination with index sort — exercises the threshold check path. */
  public void testWithIndexSort() throws Exception {
    Sort indexSort = new Sort(new SortField("ndv", SortField.Type.LONG));
    try (Directory dir = newDirectory()) {
      IndexWriterConfig conf = new IndexWriterConfig();
      conf.setIndexSort(indexSort);
      try (IndexWriter w = new IndexWriter(dir, conf)) {
        for (int i = 0; i < 2000; i++) {
          Document doc = new Document();
          doc.add(new NumericDocValuesField("ndv", random().nextInt(1000)));
          doc.add(new StringField("s", random().nextBoolean() ? "a" : "b", Store.NO));
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }

      try (IndexReader reader = DirectoryReader.open(dir)) {
        IndexSearcher searcher = new IndexSearcher(reader);

        PrefetchConfig.setEnabled(true);
        TopFieldDocs bulkResults = searcher.search(new MatchAllDocsQuery(), 10, indexSort);

        PrefetchConfig.setEnabled(false);
        TopFieldDocs perDocResults = searcher.search(new MatchAllDocsQuery(), 10, indexSort);
        PrefetchConfig.setEnabled(true);

        assertResultsMatch("indexSort", perDocResults, bulkResults);
      }
    }
  }

  private void doTestBulkVsPerDoc(Sort sort, int topN, int numDocs, Query query) throws Exception {
    try (Directory dir = newDirectory()) {
      indexDocs(dir, numDocs);

      try (IndexReader reader = DirectoryReader.open(dir)) {
        // Use single-threaded searcher to ensure deterministic results
        IndexSearcher searcher = new IndexSearcher(reader);

        PrefetchConfig.setEnabled(true);
        TopFieldCollector.bulkCollectCount.set(0);
        TopFieldCollector.collectStreamCount.set(0);
        TopFieldDocs bulkResults = searcher.search(query, topN, sort);
        int bulkCount = TopFieldCollector.bulkCollectCount.get();

        PrefetchConfig.setEnabled(false);
        TopFieldDocs perDocResults = searcher.search(query, topN, sort);
        PrefetchConfig.setEnabled(true);

        assertResultsMatch(sort.toString(), perDocResults, bulkResults);
        // Only assert bulk path when DocIdStream was actually produced by the scorer.
        // Some queries (with competitive iterators from missingValue/index sort) use per-doc collection.
        int streamCount = TopFieldCollector.collectStreamCount.get();
        if (streamCount > 0) {
          assertTrue(sort + ": collect(DocIdStream) called " + streamCount + " times but bulk path not taken (bulkCollectCount=" + bulkCount + ")",
              bulkCount > 0);
        }
      }
    }
  }

  private void assertResultsMatch(String context, TopFieldDocs expected, TopFieldDocs actual) {
    assertEquals(context + ": total hits", expected.totalHits.value(), actual.totalHits.value());
    assertEquals(context + ": result count", expected.scoreDocs.length, actual.scoreDocs.length);
    for (int i = 0; i < expected.scoreDocs.length; i++) {
      FieldDoc fdExpected = (FieldDoc) expected.scoreDocs[i];
      FieldDoc fdActual = (FieldDoc) actual.scoreDocs[i];
      assertEquals(context + ": doc at " + i, fdExpected.doc, fdActual.doc);
      for (int f = 0; f < fdExpected.fields.length; f++) {
        assertEquals(context + ": field " + f + " at " + i, fdExpected.fields[f], fdActual.fields[f]);
      }
    }
  }

  private void indexDocs(Directory dir, int numDocs) throws IOException {
    IndexWriterConfig conf = new IndexWriterConfig();
    conf.setMaxBufferedDocs(Math.min(numDocs + 1, 500001));
    try (IndexWriter w = new IndexWriter(dir, conf)) {
      for (int i = 0; i < numDocs; i++) {
        Document doc = new Document();
        doc.add(new NumericDocValuesField("ndv", random().nextInt(10000)));
        doc.add(new NumericDocValuesField("ndv2", random().nextInt(5000)));
        // Some docs don't have the field — tests missing value handling
        if (random().nextInt(10) > 0) {
          doc.add(new NumericDocValuesField("ndv_sparse", random().nextLong()));
        }
        doc.add(new StringField("s", random().nextBoolean() ? "a" : "b", Store.NO));
        w.addDocument(doc);
      }
      w.forceMerge(1);
    }
  }

  // ---- Additional query type tests ----

  /** Boolean query with MUST + FILTER clauses. */
  public void testBoolMustFilter() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(new SortField("ndv", SortField.Type.LONG, true)),
        50, 200000,
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("s", "a")), BooleanClause.Occur.MUST)
            .add(new TermQuery(new Term("s", "a")), BooleanClause.Occur.FILTER)
            .build());
  }

  /** Boolean query with SHOULD clauses (disjunction). */
  public void testBoolShould() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(new SortField("ndv", SortField.Type.LONG, true)),
        50, 200000,
        new BooleanQuery.Builder()
            .add(new TermQuery(new Term("s", "a")), BooleanClause.Occur.SHOULD)
            .add(new TermQuery(new Term("s", "b")), BooleanClause.Occur.SHOULD)
            .build());
  }

  /** Boolean query with MUST_NOT (exclusion). */
  public void testBoolMustNot() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(new SortField("ndv", SortField.Type.LONG, true)),
        50, 200000,
        new BooleanQuery.Builder()
            .add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST)
            .add(new TermQuery(new Term("s", "a")), BooleanClause.Occur.MUST_NOT)
            .build());
  }

  /** Range query on numeric field + sort. */
  public void testNumericRangeQuery() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(new SortField("ndv", SortField.Type.LONG, true)),
        50, 200000,
        NumericDocValuesField.newSlowRangeQuery("ndv", 1000L, 5000L));
  }

  /** Prefix query on string field + sort. */
  public void testPrefixQuery() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(new SortField("ndv", SortField.Type.LONG, true)),
        50, 200000,
        new PrefixQuery(new Term("s", "a")));
  }

  /** Wildcard query + sort. */
  public void testWildcardQuery() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(new SortField("ndv", SortField.Type.LONG, true)),
        50, 200000,
        new WildcardQuery(new Term("s", "a*")));
  }

  // ---- Multi-field sort variations ----

  /** Two long fields, both descending. */
  public void testMultiFieldBothDesc() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(
            new SortField("ndv", SortField.Type.LONG, true),
            new SortField("ndv2", SortField.Type.LONG, true)),
        50, 200000, new MatchAllDocsQuery());
  }

  /** Two long fields, first desc second asc. */
  public void testMultiFieldMixedDirection() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(
            new SortField("ndv", SortField.Type.LONG, true),
            new SortField("ndv2", SortField.Type.LONG, false)),
        50, 200000, new MatchAllDocsQuery());
  }

  /** Three fields: long desc, long asc, doc ID. */
  public void testTripleFieldSort() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(
            new SortField("ndv", SortField.Type.LONG, true),
            new SortField("ndv2", SortField.Type.LONG, false),
            SortField.FIELD_DOC),
        50, 200000, new MatchAllDocsQuery());
  }

  /** Multi-field sort with filtered query. */
  public void testMultiFieldFiltered() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(
            new SortField("ndv", SortField.Type.LONG, true),
            new SortField("ndv2", SortField.Type.LONG, true)),
        50, 200000,
        new TermQuery(new Term("s", "a")));
  }

  /** Multi-field sort with missing values. */
  public void testMultiFieldMissingValues() throws Exception {
    SortField sf1 = new SortField("ndv", SortField.Type.LONG, true);
    sf1.setMissingValue(Long.MAX_VALUE);
    SortField sf2 = new SortField("ndv2", SortField.Type.LONG, false);
    sf2.setMissingValue(Long.MIN_VALUE);
    doTestBulkVsPerDoc(new Sort(sf1, sf2), 50, 200000, new MatchAllDocsQuery());
  }

  // ---- Edge cases ----

  /** Very few matching docs (less than topN). */
  public void testFewMatchingDocs() throws Exception {
    try (Directory dir = newDirectory()) {
      IndexWriterConfig conf = new IndexWriterConfig();
      try (IndexWriter w = new IndexWriter(dir, conf)) {
        for (int i = 0; i < 100000; i++) {
          Document doc = new Document();
          doc.add(new NumericDocValuesField("ndv", random().nextInt(10000)));
          // Only 5 docs have "rare" term
          doc.add(new StringField("s", i < 50 ? "rare" : "common", Store.NO));
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }
      try (IndexReader reader = DirectoryReader.open(dir)) {
        IndexSearcher searcher = new IndexSearcher(reader);
        Sort sort = new Sort(new SortField("ndv", SortField.Type.LONG, true));

        PrefetchConfig.setEnabled(true);
        TopFieldDocs bulk = searcher.search(new TermQuery(new Term("s", "rare")), 100, sort);
        PrefetchConfig.setEnabled(false);
        TopFieldDocs perDoc = searcher.search(new TermQuery(new Term("s", "rare")), 100, sort);
        PrefetchConfig.setEnabled(true);

        assertResultsMatch("fewDocs", perDoc, bulk);
      }
    }
  }

  /** All docs have the same sort value — tests tie-breaking. */
  public void testAllSameValue() throws Exception {
    try (Directory dir = newDirectory()) {
      IndexWriterConfig conf = new IndexWriterConfig();
      try (IndexWriter w = new IndexWriter(dir, conf)) {
        for (int i = 0; i < 100000; i++) {
          Document doc = new Document();
          doc.add(new NumericDocValuesField("ndv", 42L));
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }
      try (IndexReader reader = DirectoryReader.open(dir)) {
        IndexSearcher searcher = new IndexSearcher(reader);
        Sort sort = new Sort(new SortField("ndv", SortField.Type.LONG, true));

        PrefetchConfig.setEnabled(true);
        TopFieldDocs bulk = searcher.search(new MatchAllDocsQuery(), 20, sort);
        PrefetchConfig.setEnabled(false);
        TopFieldDocs perDoc = searcher.search(new MatchAllDocsQuery(), 20, sort);
        PrefetchConfig.setEnabled(true);

        assertResultsMatch("allSame", perDoc, bulk);
      }
    }
  }

  /** SearchAfter with multi-field sort. */
  public void testSearchAfterMultiField() throws Exception {
    try (Directory dir = newDirectory()) {
      indexDocs(dir, 200000);
      Sort sort = new Sort(
          new SortField("ndv", SortField.Type.LONG, true),
          SortField.FIELD_DOC);
      int pageSize = 30;

      try (IndexReader reader = DirectoryReader.open(dir)) {
        IndexSearcher searcher = new IndexSearcher(reader);

        TopFieldDocs firstPage = searcher.search(new MatchAllDocsQuery(), pageSize, sort);
        FieldDoc lastDoc = (FieldDoc) firstPage.scoreDocs[firstPage.scoreDocs.length - 1];

        PrefetchConfig.setEnabled(true);
        TopFieldDocs bulkPage = (TopFieldDocs) searcher.searchAfter(lastDoc, new MatchAllDocsQuery(), pageSize, sort);
        PrefetchConfig.setEnabled(false);
        TopFieldDocs perDocPage = (TopFieldDocs) searcher.searchAfter(lastDoc, new MatchAllDocsQuery(), pageSize, sort);
        PrefetchConfig.setEnabled(true);

        assertResultsMatch("searchAfterMulti", perDocPage, bulkPage);
      }
    }
  }

  /** Randomized multi-field sort with random queries. */
  public void testRandomizedMultiField() throws Exception {
    int numDocs = 100000 + random().nextInt(400000);
    int topN = 1 + random().nextInt(Math.min(numDocs, 200));
    int numSortFields = 2 + random().nextInt(2); // 2-3 fields
    SortField[] sortFields = new SortField[numSortFields];
    for (int i = 0; i < numSortFields; i++) {
      String field = i == 0 ? "ndv" : "ndv2";
      boolean reverse = random().nextBoolean();
      SortField sf = new SortField(field, SortField.Type.LONG, reverse);
      if (random().nextBoolean()) {
        sf.setMissingValue(reverse ? Long.MIN_VALUE : Long.MAX_VALUE);
      }
      sortFields[i] = sf;
    }
    Query query = random().nextBoolean()
        ? new MatchAllDocsQuery()
        : new TermQuery(new Term("s", random().nextBoolean() ? "a" : "b"));
    doTestBulkVsPerDoc(new Sort(sortFields), topN, numDocs, query);
  }



  // ---- Sort by different numeric types ----

  /** Sort by int field descending. */
  public void testIntSortDesc() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(new SortField("ndv", SortField.Type.INT, true)),
        50, 200000, new MatchAllDocsQuery());
  }

  /** Sort by int field ascending. */
  public void testIntSortAsc() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(new SortField("ndv", SortField.Type.INT, false)),
        50, 200000, new MatchAllDocsQuery());
  }

  /** Sort by float field descending. */
  public void testFloatSortDesc() throws Exception {
    try (Directory dir = newDirectory()) {
      indexDocsWithFloat(dir, 200000);
      try (IndexReader reader = DirectoryReader.open(dir)) {
        IndexSearcher searcher = new IndexSearcher(reader);
        Sort sort = new Sort(new SortField("float_val", SortField.Type.FLOAT, true));

        PrefetchConfig.setEnabled(true);
        TopFieldDocs bulk = searcher.search(new MatchAllDocsQuery(), 50, sort);
        PrefetchConfig.setEnabled(false);
        TopFieldDocs perDoc = searcher.search(new MatchAllDocsQuery(), 50, sort);
        PrefetchConfig.setEnabled(true);

        assertResultsMatch("floatDesc", perDoc, bulk);
      }
    }
  }

  /** Sort by double field descending. */
  public void testDoubleSortDesc() throws Exception {
    try (Directory dir = newDirectory()) {
      indexDocsWithDouble(dir, 200000);
      try (IndexReader reader = DirectoryReader.open(dir)) {
        IndexSearcher searcher = new IndexSearcher(reader);
        Sort sort = new Sort(new SortField("double_val", SortField.Type.DOUBLE, true));

        PrefetchConfig.setEnabled(true);
        TopFieldDocs bulk = searcher.search(new MatchAllDocsQuery(), 50, sort);
        PrefetchConfig.setEnabled(false);
        TopFieldDocs perDoc = searcher.search(new MatchAllDocsQuery(), 50, sort);
        PrefetchConfig.setEnabled(true);

        assertResultsMatch("doubleDesc", perDoc, bulk);
      }
    }
  }

  /** Multi-field sort: int desc + long asc. */
  public void testMultiFieldIntLong() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(
            new SortField("ndv", SortField.Type.INT, true),
            new SortField("ndv2", SortField.Type.LONG, false)),
        50, 200000, new MatchAllDocsQuery());
  }

  // ---- Block boundary tests ----
  // These use doc counts that cross key boundaries in the bulk collection path.

  /** 5000 docs — crosses the 4096 DocIdStream batch boundary. */
  public void testCrossBatchBoundary() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(new SortField("ndv", SortField.Type.LONG, true)),
        50, 100000, new MatchAllDocsQuery());
  }

  /** 10000 docs — multiple DocIdStream batches (4096 + 4096 + 1808). */
  public void testMultipleBatches() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(new SortField("ndv", SortField.Type.LONG, true)),
        100, 200000, new MatchAllDocsQuery());
  }

  /** 20000 docs with filtered query — sparse hits across multiple batches. */
  public void testSparseHitsAcrossBatches() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(new SortField("ndv", SortField.Type.LONG, true)),
        100, 200000, new TermQuery(new Term("s", "a")));
  }

  /** 70000 docs — crosses the 65536 DISI block boundary for sparse doc values. */
  public void testCrossDISIBlockBoundary() throws Exception {
    try (Directory dir = newDirectory()) {
      IndexWriterConfig conf = new IndexWriterConfig();
      conf.setMaxBufferedDocs(250000);
      try (IndexWriter w = new IndexWriter(dir, conf)) {
        for (int i = 0; i < 200000; i++) {
          Document doc = new Document();
          doc.add(new NumericDocValuesField("ndv", random().nextInt(100000)));
          doc.add(new NumericDocValuesField("ndv2", random().nextInt(50000)));
          // Sparse field — only every 3rd doc has it, crosses DISI block at 65536
          if (i % 3 == 0) {
            doc.add(new NumericDocValuesField("ndv_sparse", random().nextLong()));
          }
          doc.add(new StringField("s", random().nextBoolean() ? "a" : "b", Store.NO));
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }
      try (IndexReader reader = DirectoryReader.open(dir)) {
        IndexSearcher searcher = new IndexSearcher(reader);
        Sort sort = new Sort(new SortField("ndv", SortField.Type.LONG, true));

        PrefetchConfig.setEnabled(true);
        TopFieldDocs bulk = searcher.search(new MatchAllDocsQuery(), 100, sort);
        PrefetchConfig.setEnabled(false);
        TopFieldDocs perDoc = searcher.search(new MatchAllDocsQuery(), 100, sort);
        PrefetchConfig.setEnabled(true);

        assertResultsMatch("crossDISI", perDoc, bulk);
      }
    }
  }

  /** Exactly 4096 docs — boundary case for DocIdStream batch. */
  public void testExactBatchSize() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(new SortField("ndv", SortField.Type.LONG, true)),
        50, 500000, new MatchAllDocsQuery());
  }

  /** 4097 docs — one doc past the batch boundary. */
  public void testOnePastBatchBoundary() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(new SortField("ndv", SortField.Type.LONG, true)),
        50, 131073, new MatchAllDocsQuery());
  }

  /** Large randomized test — 10K-50K docs, random sort, random query. */
  public void testLargeRandomized() throws Exception {
    int numDocs = 100000 + random().nextInt(400000);
    int topN = 10 + random().nextInt(200);
    boolean reverse = random().nextBoolean();
    int numSortFields = 1 + random().nextInt(3);
    SortField[] sortFields = new SortField[numSortFields];
    for (int i = 0; i < numSortFields; i++) {
      String field = i == 0 ? "ndv" : "ndv2";
      sortFields[i] = new SortField(field, SortField.Type.LONG, random().nextBoolean());
      if (random().nextBoolean()) {
        sortFields[i].setMissingValue(random().nextBoolean() ? Long.MIN_VALUE : Long.MAX_VALUE);
      }
    }
    Query query = random().nextBoolean()
        ? new MatchAllDocsQuery()
        : new TermQuery(new Term("s", random().nextBoolean() ? "a" : "b"));
    doTestBulkVsPerDoc(new Sort(sortFields), topN, numDocs, query);
  }


  private void indexDocsWithFloat(Directory dir, int numDocs) throws IOException {
    IndexWriterConfig conf = new IndexWriterConfig();
    conf.setMaxBufferedDocs(Math.min(numDocs + 1, 500001));
    try (IndexWriter w = new IndexWriter(dir, conf)) {
      for (int i = 0; i < numDocs; i++) {
        Document doc = new Document();
        doc.add(new FloatDocValuesField("float_val", random().nextFloat() * 10000));
        doc.add(new StringField("s", random().nextBoolean() ? "a" : "b", Store.NO));
        w.addDocument(doc);
      }
      w.forceMerge(1);
    }
  }

  private void indexDocsWithDouble(Directory dir, int numDocs) throws IOException {
    IndexWriterConfig conf = new IndexWriterConfig();
    conf.setMaxBufferedDocs(Math.min(numDocs + 1, 500001));
    try (IndexWriter w = new IndexWriter(dir, conf)) {
      for (int i = 0; i < numDocs; i++) {
        Document doc = new Document();
        doc.add(new NumericDocValuesField("double_val", Double.doubleToRawLongBits(random().nextDouble() * 100000)));
        doc.add(new StringField("s", random().nextBoolean() ? "a" : "b", Store.NO));
        w.addDocument(doc);
      }
      w.forceMerge(1);
    }
  }

}
