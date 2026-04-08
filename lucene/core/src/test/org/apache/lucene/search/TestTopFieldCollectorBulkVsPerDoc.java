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
        10, 1000, new MatchAllDocsQuery());
  }

  /** Asc sort on long field. */
  public void testAscLongSort() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(new SortField("ndv", SortField.Type.LONG, false)),
        10, 1000, new MatchAllDocsQuery());
  }

  /** Sort with a filter query — not all docs match. */
  public void testFilteredSort() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(new SortField("ndv", SortField.Type.LONG, true)),
        10, 2000, new TermQuery(new Term("s", "a")));
  }

  /** Sort with topN=1 — queue is always full after first hit. */
  public void testTopOne() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(new SortField("ndv", SortField.Type.LONG, true)),
        1, 1000, new MatchAllDocsQuery());
  }

  /** Sort with large topN — many docs in the queue. */
  public void testLargeTopN() throws Exception {
    doTestBulkVsPerDoc(
        new Sort(new SortField("ndv", SortField.Type.LONG, true)),
        200, 1000, new MatchAllDocsQuery());
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
      indexDocs(dir, 3000);
      Sort sort = new Sort(new SortField("ndv", SortField.Type.LONG, true));
      int pageSize = 50;

      try (IndexReader reader = DirectoryReader.open(dir)) {
        IndexSearcher searcher = newSearcher(reader, true, true, false);

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
        IndexSearcher searcher = newSearcher(reader, true, true, false);

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
        IndexSearcher searcher = newSearcher(reader, true, true, false);

        PrefetchConfig.setEnabled(true);
        TopFieldDocs bulkResults = searcher.search(query, topN, sort);

        PrefetchConfig.setEnabled(false);
        TopFieldDocs perDocResults = searcher.search(query, topN, sort);
        PrefetchConfig.setEnabled(true);

        assertResultsMatch(sort.toString(), perDocResults, bulkResults);
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
    conf.setMaxBufferedDocs(numDocs + 1);
    try (IndexWriter w = new IndexWriter(dir, conf)) {
      for (int i = 0; i < numDocs; i++) {
        Document doc = new Document();
        doc.add(new NumericDocValuesField("ndv", random().nextInt(10000)));
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
}
