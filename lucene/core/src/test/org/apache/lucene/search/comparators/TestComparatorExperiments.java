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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.CollectExperiments;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.DocIdStream;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.IndexOrDocValuesQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSelector;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopFieldCollector;
import org.apache.lucene.search.TopFieldCollectorManager;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.FixedBitSet;

/**
 * T8: the comparator experiments (K1 clamp, K2 doc-distance sampling, K3 run cap, K4 skipper
 * pruning) return the same top hits, sort values and totals as stock, and prune where they are
 * meant to.
 */
public class TestComparatorExperiments extends LuceneTestCase {

  static final String FIELD = "f";
  static final String SEL = "sel";

  @Override
  public void tearDown() throws Exception {
    resetSwitches();
    super.tearDown();
  }

  static void resetSwitches() {
    CollectExperiments.setCompetitiveRunCap(false);
    ComparatorExperiments.setSampleDocs(0);
    ComparatorExperiments.setSkipperMode(ComparatorExperiments.SkipperMode.OFF);
  }

  // ---------------------------------------------------------------------------------------------
  // K3: run cap for the competitive clause

  public void testRunCapSameResults() throws Exception {
    assertSameResultsOnRandomIndices(
        random -> CollectExperiments.setCompetitiveRunCap(true), false, atLeast(3));
  }

  public void testRunCapCollectsFewerDocsInLongAllMatchRun() throws Exception {
    // values rise with the doc ID: after the queue is full no later doc is competitive. The
    // competitive iterator starts as all docs (run end maxDoc), so without the cap the scorer
    // collects the whole segment as one range and never sees the updated iterator.
    final int numDocs = 20 * 4096;
    try (Directory dir = newDirectory()) {
      try (IndexWriter w = new IndexWriter(dir, oneSegmentConfig())) {
        for (int i = 0; i < numDocs; i++) {
          w.addDocument(valueDoc(i, true, true));
        }
      }
      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        assertEquals(1, reader.leaves().size());
        final Sort sort = new Sort(new SortField(FIELD, SortField.Type.LONG));
        final Delivered off = new Delivered(reader.maxDoc());
        final TopFieldDocs stock =
            search(reader, MatchAllDocsQuery.INSTANCE, sort, 10, null, 10, off);
        CollectExperiments.setCompetitiveRunCap(true);
        final Delivered on = new Delivered(reader.maxDoc());
        final TopFieldDocs capped =
            search(reader, MatchAllDocsQuery.INSTANCE, sort, 10, null, 10, on);
        assertSameTopDocs(stock, capped, 10);
        assertEquals(numDocs, off.count);
        assertTrue("delivered " + on.count, on.count <= 2 * 4096);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // shared helpers

  /** Docs that reached the leaf collector, by top-level doc ID. */
  static final class Delivered {
    final FixedBitSet docs;
    long count;

    Delivered(int maxDoc) {
      docs = new FixedBitSet(maxDoc);
    }

    void add(int doc) {
      docs.set(doc);
      count++;
    }

    long countIn(int from, int to) {
      long n = 0;
      for (int d = docs.nextSetBit(from, to);
          d != DocIdSetIterator.NO_MORE_DOCS;
          d = d + 1 < to ? docs.nextSetBit(d + 1, to) : DocIdSetIterator.NO_MORE_DOCS) {
        n++;
      }
      return n;
    }
  }

  interface SwitchSetter {
    void set(Random random);
  }

  static IndexWriterConfig oneSegmentConfig() {
    return new IndexWriterConfig()
        .setCodec(TestUtil.getDefaultCodec())
        .setMergePolicy(NoMergePolicy.INSTANCE)
        .setMaxBufferedDocs(IndexWriterConfig.DISABLE_AUTO_FLUSH)
        .setRAMBufferSizeMB(512);
  }

  static Document valueDoc(long value, boolean points, boolean skipper) {
    final Document doc = new Document();
    if (points) {
      doc.add(new LongPoint(FIELD, value));
    }
    doc.add(
        skipper
            ? NumericDocValuesField.indexedField(FIELD, value)
            : new NumericDocValuesField(FIELD, value));
    return doc;
  }

  /** Searches without concurrency, recording the docs that reach the leaf collector. */
  static TopFieldDocs search(
      IndexReader reader,
      Query query,
      Sort sort,
      int size,
      FieldDoc after,
      int threshold,
      Delivered delivered)
      throws IOException {
    final IndexSearcher searcher = new IndexSearcher(reader);
    searcher.setQueryCache(null);
    final TopFieldCollectorManager in = new TopFieldCollectorManager(sort, size, after, threshold);
    return searcher.search(
        query,
        new CollectorManager<CountingCollector, TopFieldDocs>() {
          @Override
          public CountingCollector newCollector() throws IOException {
            return new CountingCollector(in.newCollector(), delivered);
          }

          @Override
          public TopFieldDocs reduce(java.util.Collection<CountingCollector> collectors)
              throws IOException {
            final List<TopFieldCollector> inner = new ArrayList<>();
            for (CountingCollector c : collectors) {
              inner.add(c.in);
            }
            return in.reduce(inner);
          }
        });
  }

  static final class CountingCollector implements Collector {
    final TopFieldCollector in;
    final Delivered delivered;

    CountingCollector(TopFieldCollector in, Delivered delivered) {
      this.in = in;
      this.delivered = delivered;
    }

    @Override
    public ScoreMode scoreMode() {
      return in.scoreMode();
    }

    @Override
    public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
      final LeafCollector leaf = in.getLeafCollector(context);
      final int base = context.docBase;
      return new LeafCollector() {
        @Override
        public void setScorer(Scorable scorer) throws IOException {
          leaf.setScorer(scorer);
        }

        @Override
        public void collect(int doc) throws IOException {
          if (delivered != null) {
            delivered.add(base + doc);
          }
          leaf.collect(doc);
        }

        @Override
        public void collect(DocIdStream stream) throws IOException {
          // TopFieldCollector's leaf collectors take streams doc by doc, so this is the same path
          stream.forEach(this::collect);
        }

        @Override
        public void collectRange(int min, int max) throws IOException {
          for (int doc = min; doc < max; doc++) {
            collect(doc);
          }
        }

        @Override
        public DocIdSetIterator competitiveIterator() throws IOException {
          return leaf.competitiveIterator();
        }

        @Override
        public void finish() throws IOException {
          leaf.finish();
        }
      };
    }
  }

  /**
   * Same hits (doc IDs in order) and sort values; totals: same relation, the value only when it is
   * exact, and both above the threshold otherwise.
   */
  static void assertSameTopDocs(TopFieldDocs expected, TopFieldDocs actual, int threshold) {
    assertEquals(expected.scoreDocs.length, actual.scoreDocs.length);
    for (int i = 0; i < expected.scoreDocs.length; i++) {
      final ScoreDoc e = expected.scoreDocs[i], a = actual.scoreDocs[i];
      assertEquals("hit " + i, e.doc, a.doc);
      assertArrayEquals("hit " + i, ((FieldDoc) e).fields, ((FieldDoc) a).fields);
    }
    assertEquals(expected.totalHits.relation(), actual.totalHits.relation());
    if (expected.totalHits.relation() == TotalHits.Relation.EQUAL_TO) {
      assertEquals(expected.totalHits.value(), actual.totalHits.value());
    } else {
      assertTrue(expected.totalHits.value() > threshold);
      assertTrue(actual.totalHits.value() > threshold);
    }
  }

  /** A random index of time-run-like values (or shuffled ones), sometimes with missing values. */
  static Directory randomIndex(Random r, boolean multiValued) throws IOException {
    final Directory dir = newDirectory();
    final int numDocs = TestUtil.nextInt(r, 2000, 30000);
    final boolean points = r.nextInt(10) != 0;
    final boolean skipper = r.nextInt(5) != 0;
    final boolean shuffled = r.nextInt(5) == 0;
    final double missing = r.nextBoolean() ? 0 : 0.1;
    final int runs = TestUtil.nextInt(r, 1, 6);
    final long[] runStart = new long[runs];
    for (int i = 0; i < runs; i++) {
      runStart[i] = r.nextInt(1_000_000);
    }
    final int flushEvery = r.nextBoolean() ? Integer.MAX_VALUE : TestUtil.nextInt(r, 500, numDocs);
    try (IndexWriter w = new IndexWriter(dir, oneSegmentConfig())) {
      for (int i = 0; i < numDocs; i++) {
        final Document doc = new Document();
        doc.add(new StringField(SEL, "s" + r.nextInt(10), Field.Store.NO));
        if (r.nextDouble() >= missing) {
          final int run = (int) ((long) i * runs / numDocs);
          final long base =
              shuffled ? r.nextInt(1_000_000) : runStart[run] + (i - run * (numDocs / runs)) * 3L;
          final int numValues = multiValued ? TestUtil.nextInt(r, 1, 3) : 1;
          for (int v = 0; v < numValues; v++) {
            final long value = base + (v == 0 ? 0 : r.nextInt(5000)) + r.nextInt(3);
            if (points) {
              doc.add(new LongPoint(FIELD, value));
            }
            if (multiValued) {
              doc.add(
                  skipper
                      ? SortedNumericDocValuesField.indexedField(FIELD, value)
                      : new SortedNumericDocValuesField(FIELD, value));
            } else {
              doc.add(
                  skipper
                      ? NumericDocValuesField.indexedField(FIELD, value)
                      : new NumericDocValuesField(FIELD, value));
            }
          }
        }
        w.addDocument(doc);
        if ((i + 1) % flushEvery == 0) {
          w.flush();
        }
      }
    }
    return dir;
  }

  static Query randomQuery(Random r) {
    final long lo = r.nextInt(1_200_000), hi = lo + r.nextInt(400_000);
    switch (r.nextInt(5)) {
      case 0:
        return MatchAllDocsQuery.INSTANCE;
      case 1:
        return LongPoint.newRangeQuery(FIELD, lo, hi);
      case 2:
        return new IndexOrDocValuesQuery(
            LongPoint.newRangeQuery(FIELD, lo, hi),
            SortedNumericDocValuesField.newSlowRangeQuery(FIELD, lo, hi));
      case 3:
        return new ConstantScoreQuery(
            new BooleanQuery.Builder()
                .add(new TermQuery(new Term(SEL, "s" + r.nextInt(10))), BooleanClause.Occur.FILTER)
                .add(LongPoint.newRangeQuery(FIELD, lo, hi), BooleanClause.Occur.FILTER)
                .build());
      default:
        return new ConstantScoreQuery(new TermQuery(new Term(SEL, "s" + r.nextInt(10))));
    }
  }

  static SortField randomSortField(Random r, boolean multiValued) {
    final boolean reverse = r.nextBoolean();
    final SortField sortField;
    if (multiValued || r.nextBoolean()) {
      sortField =
          new SortedNumericSortField(
              FIELD,
              SortField.Type.LONG,
              reverse,
              reverse ? SortedNumericSelector.Type.MAX : SortedNumericSelector.Type.MIN);
    } else {
      sortField = new SortField(FIELD, SortField.Type.LONG, reverse);
    }
    switch (r.nextInt(4)) {
      case 0:
        sortField.setMissingValue(Long.MIN_VALUE);
        break;
      case 1:
        sortField.setMissingValue(Long.MAX_VALUE);
        break;
      case 2:
        sortField.setMissingValue((long) r.nextInt(1_200_000));
        break;
      default:
        break;
    }
    return sortField;
  }

  static int randomThreshold(Random r, int size) {
    switch (r.nextInt(5)) {
      case 0:
        return 1;
      case 1:
        return size;
      case 2:
        return 1000;
      case 3:
        return Integer.MAX_VALUE;
      default:
        return TestUtil.nextInt(r, 1, 2000);
    }
  }

  /**
   * For {@code numIndices} random indices and several random searches on each: the switches set by
   * {@code setter} return the same top hits, sort values and totals as stock.
   */
  static void assertSameResultsOnRandomIndices(
      SwitchSetter setter, boolean multiValued, int numIndices) throws IOException {
    final Random r = random();
    for (int n = 0; n < numIndices; n++) {
      try (Directory dir = randomIndex(r, multiValued);
          DirectoryReader reader = DirectoryReader.open(dir)) {
        for (int q = 0; q < 10; q++) {
          final Query query = randomQuery(r);
          final Sort sort = new Sort(randomSortField(r, multiValued));
          final int size = r.nextInt(4) == 0 ? TestUtil.nextInt(r, 100, 500) : r.nextInt(20) + 1;
          final int threshold = randomThreshold(r, size);
          resetSwitches();
          FieldDoc after = null;
          if (r.nextInt(5) == 0) {
            final TopFieldDocs first = search(reader, query, sort, 50, null, 1000, null);
            if (first.scoreDocs.length > 0) {
              final FieldDoc hit = (FieldDoc) first.scoreDocs[r.nextInt(first.scoreDocs.length)];
              after = new FieldDoc(hit.doc, Float.NaN, hit.fields);
            }
          }
          final TopFieldDocs stock = search(reader, query, sort, size, after, threshold, null);
          setter.set(r);
          final TopFieldDocs variant = search(reader, query, sort, size, after, threshold, null);
          resetSwitches();
          assertSameTopDocs(stock, variant, threshold);
        }
      }
    }
  }
}
