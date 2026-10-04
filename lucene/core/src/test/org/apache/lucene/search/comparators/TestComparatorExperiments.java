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
import java.util.Arrays;
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
import org.apache.lucene.search.Pruning;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SkipBlockRangeIterator;
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
import org.apache.lucene.util.BitSetIterator;
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
    NumericComparator.testHooks = null;
    resetSwitches();
    super.tearDown();
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    NumericComparator.testHooks = null;
    resetSwitches();
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
  // K2: doc-distance sampling with a trailing update

  public void testSampleDocsSameResults() throws Exception {
    assertSameResultsOnRandomIndices(
        random -> {
          ComparatorExperiments.setSampleDocs(
              TestUtil.nextInt(random, ComparatorExperiments.MIN_SAMPLE_DOCS, 1 << 16));
          CollectExperiments.setCompetitiveRunCap(random.nextBoolean());
        },
        false,
        atLeast(3));
  }

  public void testSampleDocsTrailingUpdateWithoutRunCap() throws Exception {
    assertTrailingUpdate(false);
  }

  public void testSampleDocsTrailingUpdateWithRunCap() throws Exception {
    assertTrailingUpdate(true);
  }

  /**
   * Two-run asc burst: the 500 replacements at the run-B entry span 500 doc IDs, so the
   * doc-distance test skips the decisive one. The trailing update runs it within sampleDocs + one
   * window, intersects points, and prunes the rest of run B.
   */
  private void assertTrailingUpdate(boolean runCap) throws Exception {
    final int runDocs = 20 * 4096;
    final int sampleDocs = 4096;
    try (Directory dir = twoRunIndex(runDocs, runDocs);
        DirectoryReader reader = DirectoryReader.open(dir)) {
      final Sort sort = new Sort(new SortField(FIELD, SortField.Type.LONG));
      final TopFieldDocs stock = search(reader, MatchAllDocsQuery.INSTANCE, sort, 500, null, 1000);
      final int lastReplacementDoc = runDocs + 499;

      for (boolean trailing : new boolean[] {true, false}) {
        ComparatorExperiments.setSampleDocs(sampleDocs);
        CollectExperiments.setCompetitiveRunCap(runCap);
        final Recorder recorder = new Recorder();
        recorder.trailingEnabled = trailing;
        NumericComparator.testHooks = recorder;
        final Delivered delivered = new Delivered(reader.maxDoc());
        final TopFieldDocs actual =
            search(reader, MatchAllDocsQuery.INSTANCE, sort, 500, null, 1000, delivered);
        NumericComparator.testHooks = null;
        resetSwitches();
        assertSameTopDocs(stock, actual, 1000);

        // the last attempt that ran at or before the last replacement: its due point
        int lastAttemptDoc = -1;
        for (int[] a : recorder.attempts) {
          if (a[2] == 0 && a[0] <= lastReplacementDoc) {
            lastAttemptDoc = Math.max(lastAttemptDoc, a[0]);
          }
        }
        assertTrue(lastAttemptDoc >= runDocs); // the first 256 replacements attempt every time
        final long bound = (long) lastAttemptDoc + sampleDocs + 4096;
        int trailingInWindow = 0;
        boolean intersected = false;
        for (int i = 0; i < recorder.events.size(); i++) {
          final int[] e = recorder.events.get(i);
          if (e[0] == Recorder.ATTEMPT
              && e[3] == 1
              && e[1] >= lastReplacementDoc + 1
              && e[1] <= bound) {
            trailingInWindow++;
            intersected |=
                i + 2 < recorder.events.size()
                    && recorder.events.get(i + 1)[0] == Recorder.ESTIMATE
                    && recorder.events.get(i + 2)[0] == Recorder.INTERSECT;
          }
        }
        if (trailing) {
          assertTrue("trailing attempts " + recorder.attempts, trailingInWindow >= 1);
          assertTrue("the trailing update intersects points", intersected);
          assertTrue(
              "delivered from run B " + delivered.countIn(runDocs, 2 * runDocs),
              delivered.countIn(runDocs, 2 * runDocs) <= bound - runDocs + 1);
        } else {
          assertEquals(0, trailingInWindow);
        }
        assertFalse(recorder.runEnds.isEmpty());
        for (int[] e : recorder.runEnds) {
          final int doc = e[0], result = e[1], last = e[2], sample = e[3];
          assertTrue(Arrays.toString(e), result > doc);
          assertTrue(Arrays.toString(e), result <= (long) Math.max(last, doc) + sample);
        }
      }
    }
  }

  public void testTrailingUpdateIteratorForwards() throws Exception {
    final Random r = random();
    ComparatorExperiments.setSampleDocs(ComparatorExperiments.MIN_SAMPLE_DOCS);
    try (Directory dir = randomSingleSegment(r);
        DirectoryReader reader = DirectoryReader.open(dir)) {
      final LeafReaderContext ctx = reader.leaves().get(0);
      final int maxDoc = ctx.reader().maxDoc();
      for (int iter = 0; iter < 10; iter++) {
        final int kind = r.nextInt(3);
        final long lo = r.nextInt(600_000), hi = lo + r.nextInt(300_000);
        final FixedBitSet bits = new FixedBitSet(maxDoc);
        for (int i = 0; i < maxDoc; i++) {
          if (r.nextInt(4) == 0) {
            bits.set(i);
          }
        }
        final IteratorFactory factory =
            () ->
                switch (kind) {
                  case 0 -> new BitSetIterator(bits, bits.cardinality());
                  case 1 -> DocIdSetIterator.all(maxDoc);
                  default ->
                      new SkipBlockRangeIterator(ctx.reader().getDocValuesSkipper(FIELD), lo, hi);
                };
        final NumericComparator<?>.NumericLeafComparator leaf = newLeafComparator(ctx, 10);
        final NumericComparator<?>.CompetitiveDISIBuilder builder = leaf.competitiveDISIBuilder();
        builder.updateCompetitiveIterator(factory.create());
        final DocIdSetIterator wrapper = leaf.competitiveIterator();
        assertEquals("TrailingUpdateIterator", wrapper.getClass().getSimpleName());
        final DocIdSetIterator expected = factory.create();
        final FixedBitSet expectedBits = new FixedBitSet(maxDoc + 1);
        final FixedBitSet actualBits = new FixedBitSet(maxDoc + 1);
        while (expected.docID() != DocIdSetIterator.NO_MORE_DOCS) {
          switch (r.nextInt(3)) {
            case 0 -> assertEquals(expected.nextDoc(), wrapper.nextDoc());
            case 1 -> {
              final int target = expected.docID() + 1 + r.nextInt(5000);
              if (target >= maxDoc) {
                assertEquals(expected.advance(maxDoc), wrapper.advance(maxDoc));
              } else {
                assertEquals(expected.advance(target), wrapper.advance(target));
              }
            }
            default -> {
              if (expected.docID() < 0) {
                assertEquals(expected.nextDoc(), wrapper.nextDoc());
              }
              if (expected.docID() == DocIdSetIterator.NO_MORE_DOCS) {
                break;
              }
              wrapper.docIDRunEnd();
              assertEquals(expected.docID(), wrapper.docID());
              final int doc = expected.docID();
              final int offset = doc - r.nextInt(Math.min(doc, 100) + 1);
              final int upTo = (int) Math.min(maxDoc + 1L, doc + 1L + r.nextInt(3 * 4096));
              expected.intoBitSet(upTo, expectedBits, offset);
              wrapper.intoBitSet(upTo, actualBits, offset);
              assertEquals(expectedBits, actualBits);
              expectedBits.clear();
              actualBits.clear();
            }
          }
          assertEquals(expected.docID(), wrapper.docID());
        }
      }

      // a pending update that fires inside docIDRunEnd or intoBitSet
      for (boolean inRunEnd : new boolean[] {true, false}) {
        final Recorder recorder = new Recorder();
        NumericComparator.testHooks = recorder;
        final NumericComparator<?>.NumericLeafComparator leaf = newLeafComparator(ctx, 10);
        NumericComparator.testHooks = null;
        leaf.setScorer(
            new Scorable() {
              @Override
              public float score() {
                return 0;
              }
            });
        leaf.setHitsThresholdReached();
        for (int slot = 0; slot < 10; slot++) {
          leaf.copy(slot, slot);
        }
        leaf.setBottom(r.nextInt(10));
        final NumericComparator<?>.CompetitiveDISIBuilder builder = leaf.competitiveDISIBuilder();
        final DocIdSetIterator wrapper = leaf.competitiveIterator();
        final int start = wrapper.nextDoc();
        if (start == DocIdSetIterator.NO_MORE_DOCS) {
          continue;
        }
        builder.pendingUpdate = true;
        builder.lastAttemptDoc = start - builder.sampleDocs;
        final int attemptsBefore = recorder.attempts.size();
        if (inRunEnd) {
          final int end = wrapper.docIDRunEnd();
          assertTrue(end > wrapper.docID());
          assertEquals(start, wrapper.docID());
        } else {
          final int upTo = (int) Math.min(maxDoc, start + 1L + r.nextInt(3 * 4096));
          final FixedBitSet bits = new FixedBitSet(maxDoc);
          wrapper.intoBitSet(upTo, bits, 0);
          assertTrue(wrapper.docID() >= upTo);
        }
        assertFalse(builder.pendingUpdate);
        assertEquals(attemptsBefore + 1, recorder.attempts.size());
        assertEquals(1, recorder.attempts.get(attemptsBefore)[2]);
        final int before = wrapper.docID();
        if (before != DocIdSetIterator.NO_MORE_DOCS) {
          assertTrue(wrapper.nextDoc() > before);
        }
      }
    }
  }

  public void testSizeTenControl() throws Exception {
    // bursts below 256 updates: every variant prunes run B after its 10 replacements
    final int runDocs = 20 * 4096;
    try (Directory dir = twoRunIndex(runDocs, runDocs);
        DirectoryReader reader = DirectoryReader.open(dir)) {
      final Sort sort = new Sort(new SortField(FIELD, SortField.Type.LONG));
      final TopFieldDocs stock = search(reader, MatchAllDocsQuery.INSTANCE, sort, 10, null, 1000);
      for (SwitchSetter variant : sizeTenVariants()) {
        variant.set(random());
        CollectExperiments.setCompetitiveRunCap(true);
        final Delivered delivered = new Delivered(reader.maxDoc());
        final TopFieldDocs actual =
            search(reader, MatchAllDocsQuery.INSTANCE, sort, 10, null, 1000, delivered);
        resetSwitches();
        assertSameTopDocs(stock, actual, 1000);
        final long fromB = delivered.countIn(runDocs, 2 * runDocs);
        assertTrue("delivered from run B " + fromB, fromB <= 10 + 2 * 4096);
      }
    }
  }

  List<SwitchSetter> sizeTenVariants() {
    final List<SwitchSetter> variants = new ArrayList<>();
    variants.add(random -> {}); // stock comparator (with the run cap, as every variant here)
    variants.add(random -> ComparatorExperiments.setSampleDocs(65536));
    return variants;
  }

  /** Records the hooks of every builder. */
  static final class Recorder implements NumericComparator.TestHooks {
    static final int ATTEMPT = 0, ESTIMATE = 1, INTERSECT = 2;
    final List<int[]> attempts = new ArrayList<>(); // {doc, updateCounter, trailing}
    final List<int[]> runEnds = new ArrayList<>(); // {doc, result, lastAttemptDoc, sampleDocs}
    final List<Integer> estimates = new ArrayList<>(); // updateCounter
    final List<Integer> intersects = new ArrayList<>(); // updateCounter
    final List<DocIdSetIterator> intersected = new ArrayList<>();
    final List<int[]> events = new ArrayList<>(); // {kind, doc or -1, updateCounter, trailing}
    boolean trailingEnabled = true;

    @Override
    public void onAttempt(int doc, int updateCounter, boolean trailing) {
      attempts.add(new int[] {doc, updateCounter, trailing ? 1 : 0});
      events.add(new int[] {ATTEMPT, doc, updateCounter, trailing ? 1 : 0});
    }

    @Override
    public void onDocIDRunEnd(int doc, int result, int lastAttemptDoc, int sampleDocs) {
      runEnds.add(new int[] {doc, result, lastAttemptDoc, sampleDocs});
    }

    @Override
    public boolean trailingUpdateEnabled() {
      return trailingEnabled;
    }

    @Override
    public void onEstimate(int updateCounter) {
      estimates.add(updateCounter);
      events.add(new int[] {ESTIMATE, -1, updateCounter, 0});
    }

    @Override
    public void onIntersect(int updateCounter, DocIdSetIterator iterator) {
      intersects.add(updateCounter);
      intersected.add(iterator);
      events.add(new int[] {INTERSECT, -1, updateCounter, 0});
    }
  }

  interface IteratorFactory {
    DocIdSetIterator create() throws IOException;
  }

  static NumericComparator<?>.NumericLeafComparator newLeafComparator(
      LeafReaderContext ctx, int numHits) throws IOException {
    final LongComparator comparator =
        new LongComparator(numHits, FIELD, null, false, Pruning.GREATER_THAN_OR_EQUAL_TO);
    return (NumericComparator<?>.NumericLeafComparator) comparator.getLeafComparator(ctx);
  }

  /**
   * One segment: run A (values 1,000,000 + i) then run B (values j), both rising with the doc ID,
   * every run-B value below every run-A value.
   */
  static Directory twoRunIndex(int runA, int runB) throws IOException {
    final Directory dir = newDirectory();
    try (IndexWriter w = new IndexWriter(dir, oneSegmentConfig())) {
      for (int i = 0; i < runA; i++) {
        w.addDocument(valueDoc(1_000_000L + i, true, true));
      }
      for (int j = 0; j < runB; j++) {
        w.addDocument(valueDoc(j, true, true));
      }
    }
    return dir;
  }

  static Directory randomSingleSegment(Random r) throws IOException {
    final Directory dir = newDirectory();
    final int numDocs = TestUtil.nextInt(r, 5000, 40_000);
    final int runs = TestUtil.nextInt(r, 1, 5);
    try (IndexWriter w = new IndexWriter(dir, oneSegmentConfig())) {
      for (int i = 0; i < numDocs; i++) {
        final int run = (int) ((long) i * runs / numDocs);
        w.addDocument(
            valueDoc(
                (runs - run) * 100_000L + i % (numDocs / runs + 1) + r.nextInt(3), true, true));
      }
    }
    return dir;
  }

  // ---------------------------------------------------------------------------------------------
  // shared helpers

  static TopFieldDocs search(
      IndexReader reader, Query query, Sort sort, int size, FieldDoc after, int threshold)
      throws IOException {
    return search(reader, query, sort, size, after, threshold, null);
  }

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
