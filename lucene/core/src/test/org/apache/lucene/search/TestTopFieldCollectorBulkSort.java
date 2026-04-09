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
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.util.BytesRef;

/**
 * Tests that TopFieldCollector's bulk collect(DocIdStream) path produces identical results to the
 * per-doc collect(int) path. The bulk path uses BulkValueComparator with pre-fetched values from
 * NumericDocValues.longValues() for competitive checks, while the per-doc path uses
 * LeafFieldComparator.compareBottom(doc) which reads values one at a time.
 *
 * <p>If the bulk path has a bug (wrong comparison, missed doc, wrong copy), the results will differ
 * from the per-doc path.
 */
public class TestTopFieldCollectorBulkSort extends LuceneTestCase {

  /** Sort by a single long field descending — the most common sort query pattern. */
  public void testDescSortSingleField() throws Exception {
    doTestSort(50_000, 10, new Sort(new SortField("timestamp", SortField.Type.LONG, true)));
  }

  /** Sort by a single long field ascending. */
  public void testAscSortSingleField() throws Exception {
    doTestSort(50_000, 10, new Sort(new SortField("timestamp", SortField.Type.LONG, false)));
  }

  /** Sort with larger topN to exercise more queue operations. */
  public void testSortLargeTopN() throws Exception {
    doTestSort(50_000, 500, new Sort(new SortField("timestamp", SortField.Type.LONG, true)));
  }

  /** Sort with topN=1 — edge case where queue is always full after first hit. */
  public void testSortTopOne() throws Exception {
    doTestSort(20_000, 1, new Sort(new SortField("timestamp", SortField.Type.LONG, true)));
  }

  /** Sort with many docs to exercise multiple DocIdStream batches. */
  public void testSortManyDocs() throws Exception {
    doTestSort(50000, 20, new Sort(new SortField("timestamp", SortField.Type.LONG, true)));
  }

  /** Randomized sort test. */
  public void testSortRandomized() throws Exception {
    int numDocs = 20_000 + random().nextInt(30000);
    int topN = 1 + random().nextInt(Math.min(numDocs, 500));
    boolean reverse = random().nextBoolean();
    doTestSort(numDocs, topN, new Sort(new SortField("timestamp", SortField.Type.LONG, reverse)));
  }

  /** SearchAfter pagination — PagingFieldCollector path. */
  public void testSearchAfterPagination() throws Exception {
    try (Directory dir = newDirectory()) {
      int numDocs = 50_000;
      indexDocs(dir, numDocs);

      Sort sort = new Sort(new SortField("timestamp", SortField.Type.LONG, true));
      int pageSize = 50;

      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        IndexSearcher searcher = new IndexSearcher(reader);

        // Get first page with prefetch enabled
        PrefetchConfig.setEnabled(true);
        TopFieldDocs firstPage = searcher.search(new MatchAllDocsQuery(), pageSize, sort);
        assertTrue("Should have results", firstPage.scoreDocs.length > 0);

        // Get second page using searchAfter
        FieldDoc lastDoc = (FieldDoc) firstPage.scoreDocs[firstPage.scoreDocs.length - 1];
        TopFieldDocs secondPageBulk = (TopFieldDocs) searcher.searchAfter(lastDoc, new MatchAllDocsQuery(), pageSize, sort);

        // Get second page with prefetch disabled (per-doc path)
        PrefetchConfig.setEnabled(false);
        TopFieldDocs secondPagePerDoc = (TopFieldDocs) searcher.searchAfter(lastDoc, new MatchAllDocsQuery(), pageSize, sort);
        PrefetchConfig.setEnabled(true);

        // Results must match
        assertEquals("Page size mismatch", secondPagePerDoc.scoreDocs.length, secondPageBulk.scoreDocs.length);
        for (int i = 0; i < secondPagePerDoc.scoreDocs.length; i++) {
          assertEquals("Doc ID mismatch at " + i, secondPagePerDoc.scoreDocs[i].doc, secondPageBulk.scoreDocs[i].doc);
          FieldDoc fdPerDoc = (FieldDoc) secondPagePerDoc.scoreDocs[i];
          FieldDoc fdBulk = (FieldDoc) secondPageBulk.scoreDocs[i];
          assertEquals("Sort value mismatch at " + i, fdPerDoc.fields[0], fdBulk.fields[0]);
        }
      }
    }
  }

  /**
   * Core test: run the same sort query with prefetch enabled (bulk path) and disabled (per-doc
   * path), assert identical results.
   */
  private void doTestSort(int numDocs, int topN, Sort sort) throws Exception {
    try (Directory dir = newDirectory()) {
      indexDocs(dir, numDocs);

      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        IndexSearcher searcher = new IndexSearcher(reader);
        Query query = new MatchAllDocsQuery();

        // Run with prefetch enabled (bulk collect path)
        PrefetchConfig.setEnabled(true);
        TopFieldDocs bulkResults = searcher.search(query, topN, sort);

        // Run with prefetch disabled (per-doc collect path)
        PrefetchConfig.setEnabled(false);
        TopFieldDocs perDocResults = searcher.search(query, topN, sort);

        // Re-enable for other tests
        PrefetchConfig.setEnabled(true);

        // Compare results
        assertEquals("Total hits mismatch",
            perDocResults.totalHits.value(), bulkResults.totalHits.value());
        assertEquals("Result count mismatch",
            perDocResults.scoreDocs.length, bulkResults.scoreDocs.length);

        for (int i = 0; i < perDocResults.scoreDocs.length; i++) {
          FieldDoc fdPerDoc = (FieldDoc) perDocResults.scoreDocs[i];
          FieldDoc fdBulk = (FieldDoc) bulkResults.scoreDocs[i];
          assertEquals("Doc ID mismatch at position " + i, fdPerDoc.doc, fdBulk.doc);
          assertEquals("Sort value mismatch at position " + i, fdPerDoc.fields[0], fdBulk.fields[0]);
        }
      }
    }
  }

  private void indexDocs(Directory dir, int numDocs) throws IOException {
    IndexWriterConfig conf = new IndexWriterConfig();
    conf.setMaxBufferedDocs(numDocs + 1);
    try (IndexWriter w = new IndexWriter(dir, conf)) {
      for (int i = 0; i < numDocs; i++) {
        Document doc = new Document();
        // Use a mix of values to create interesting sort orders
        long timestamp = 1_000_000_000L + (long)(random().nextGaussian() * 1_000_000);
        doc.add(new NumericDocValuesField("timestamp", timestamp));
        doc.add(new SortedDocValuesField("id", new BytesRef("doc_" + i)));
        w.addDocument(doc);
      }
      w.forceMerge(1);
    }
  }

  /** Sort by int field. */
  public void testIntSort() throws Exception {
    doTestSort(200000, 20, new Sort(new SortField("timestamp", SortField.Type.INT, true)));
  }

  /** Sort by float field. */
  public void testFloatSort() throws Exception {
    try (Directory dir = newDirectory()) {
      IndexWriterConfig conf = new IndexWriterConfig();
      conf.setMaxBufferedDocs(200001);
      try (IndexWriter w = new IndexWriter(dir, conf)) {
        for (int i = 0; i < 200000; i++) {
          Document doc = new Document();
          doc.add(new FloatDocValuesField("fval", (float)(random().nextGaussian() * 1000)));
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }
      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        IndexSearcher searcher = new IndexSearcher(reader);
        Sort sort = new Sort(new SortField("fval", SortField.Type.FLOAT, true));
        PrefetchConfig.setEnabled(true);
        TopFieldDocs bulk = searcher.search(new MatchAllDocsQuery(), 20, sort);
        PrefetchConfig.setEnabled(false);
        TopFieldDocs perDoc = searcher.search(new MatchAllDocsQuery(), 20, sort);
        PrefetchConfig.setEnabled(true);
        assertEquals(perDoc.scoreDocs.length, bulk.scoreDocs.length);
        for (int i = 0; i < perDoc.scoreDocs.length; i++) {
          assertEquals(perDoc.scoreDocs[i].doc, bulk.scoreDocs[i].doc);
        }
      }
    }
  }

  /** Sort by double field. */
  public void testDoubleSort() throws Exception {
    try (Directory dir = newDirectory()) {
      IndexWriterConfig conf = new IndexWriterConfig();
      conf.setMaxBufferedDocs(200001);
      try (IndexWriter w = new IndexWriter(dir, conf)) {
        for (int i = 0; i < 200000; i++) {
          Document doc = new Document();
          doc.add(new NumericDocValuesField("dval", Double.doubleToRawLongBits(random().nextGaussian() * 100000)));
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }
      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        IndexSearcher searcher = new IndexSearcher(reader);
        Sort sort = new Sort(new SortField("dval", SortField.Type.DOUBLE, true));
        PrefetchConfig.setEnabled(true);
        TopFieldDocs bulk = searcher.search(new MatchAllDocsQuery(), 20, sort);
        PrefetchConfig.setEnabled(false);
        TopFieldDocs perDoc = searcher.search(new MatchAllDocsQuery(), 20, sort);
        PrefetchConfig.setEnabled(true);
        assertEquals(perDoc.scoreDocs.length, bulk.scoreDocs.length);
        for (int i = 0; i < perDoc.scoreDocs.length; i++) {
          assertEquals(perDoc.scoreDocs[i].doc, bulk.scoreDocs[i].doc);
        }
      }
    }
  }

  /** Float edge cases: NaN, Infinity, -0.0. */
  public void testFloatEdgeCases() throws Exception {
    try (Directory dir = newDirectory()) {
      IndexWriterConfig conf = new IndexWriterConfig();
      try (IndexWriter w = new IndexWriter(dir, conf)) {
        float[] edgeValues = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            -0.0f, 0.0f, Float.MIN_VALUE, Float.MAX_VALUE, -1.5f, 1.5f};
        for (int i = 0; i < 50000; i++) {
          Document doc = new Document();
          float val = i < edgeValues.length ? edgeValues[i] : random().nextFloat() * 1000 - 500;
          doc.add(new FloatDocValuesField("fval", val));
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }
      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        IndexSearcher searcher = new IndexSearcher(reader);
        Sort sort = new Sort(new SortField("fval", SortField.Type.FLOAT, true));
        PrefetchConfig.setEnabled(true);
        TopFieldDocs bulk = searcher.search(new MatchAllDocsQuery(), 50, sort);
        PrefetchConfig.setEnabled(false);
        TopFieldDocs perDoc = searcher.search(new MatchAllDocsQuery(), 50, sort);
        PrefetchConfig.setEnabled(true);
        assertEquals(perDoc.scoreDocs.length, bulk.scoreDocs.length);
        for (int i = 0; i < perDoc.scoreDocs.length; i++) {
          assertEquals("doc mismatch at " + i, perDoc.scoreDocs[i].doc, bulk.scoreDocs[i].doc);
        }
      }
    }
  }

  /** SearchAfter with float sort. */
  public void testSearchAfterFloat() throws Exception {
    try (Directory dir = newDirectory()) {
      IndexWriterConfig conf = new IndexWriterConfig();
      conf.setMaxBufferedDocs(200001);
      try (IndexWriter w = new IndexWriter(dir, conf)) {
        for (int i = 0; i < 200000; i++) {
          Document doc = new Document();
          doc.add(new FloatDocValuesField("fval", random().nextFloat() * 10000));
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }
      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        IndexSearcher searcher = new IndexSearcher(reader);
        Sort sort = new Sort(new SortField("fval", SortField.Type.FLOAT, true));
        TopFieldDocs firstPage = searcher.search(new MatchAllDocsQuery(), 50, sort);
        FieldDoc lastDoc = (FieldDoc) firstPage.scoreDocs[firstPage.scoreDocs.length - 1];
        PrefetchConfig.setEnabled(true);
        TopFieldDocs bulkPage = (TopFieldDocs) searcher.searchAfter(lastDoc, new MatchAllDocsQuery(), 50, sort);
        PrefetchConfig.setEnabled(false);
        TopFieldDocs perDocPage = (TopFieldDocs) searcher.searchAfter(lastDoc, new MatchAllDocsQuery(), 50, sort);
        PrefetchConfig.setEnabled(true);
        assertEquals(perDocPage.scoreDocs.length, bulkPage.scoreDocs.length);
        for (int i = 0; i < perDocPage.scoreDocs.length; i++) {
          assertEquals(perDocPage.scoreDocs[i].doc, bulkPage.scoreDocs[i].doc);
        }
      }
    }
  }

}
