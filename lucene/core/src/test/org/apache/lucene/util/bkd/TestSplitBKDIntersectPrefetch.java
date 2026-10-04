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
package org.apache.lucene.util.bkd;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import org.apache.lucene.codecs.lucene90.SplitPointsAssert;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.PointValues.IntersectVisitor;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopFieldCollectorManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.bkd.TestBKDIntersectPrefetch.Recording;
import org.apache.lucene.util.bkd.TestBKDIntersectPrefetch.RecordingDirectory;
import org.junit.After;

/**
 * The split format's intersect prefetch: doc IDs of INSIDE and CROSSES leaves, values of CROSSES
 * leaves only, nothing for leaves whose tight bounds are OUTSIDE, and the two index-file rules
 * (whole section of at most 2 storage nodes, child prefetch). Every prefetched node is read.
 */
public class TestSplitBKDIntersectPrefetch extends LuceneTestCase {
  private static final String FIELD = "t" + SplitPointsAssert.SPLIT_SUFFIX;
  // values are multiples of this, so the tight leaf bounds leave gaps that loose cells cover
  private static final long STEP = 10;

  @After
  public void resetSwitches() {
    BKDExperiments.setIntersectPrefetch(false);
    BKDExperiments.setPrefetchChunks(BKDExperiments.DEFAULT_PREFETCH_CHUNKS);
    BKDExperiments.setNodeBytes(BKDExperiments.DEFAULT_NODE_BYTES);
    BKDExperiments.setWholeIndexPrefetch(false);
    BKDExperiments.setWholeIndexPrefetchBytes(BKDExperiments.DEFAULT_WHOLE_INDEX_PREFETCH_BYTES);
    BKDExperiments.setIndexChildPrefetch(false);
  }

  static final class SplitIndex implements AutoCloseable {
    final Recording recording = new Recording();
    final Directory dir;
    final IndexReader reader;
    final LeafReader leaf;
    final SplitBKDReader points;
    final long[] values;
    final String kdi;
    final String kdd;
    final String kdv;

    SplitIndex(int numDocs, int leafSize) throws IOException {
      dir = new RecordingDirectory(newDirectory(), recording);
      IndexWriterConfig iwc =
          new IndexWriterConfig()
              .setCodec(SplitPointsAssert.twinCodec(leafSize))
              .setUseCompoundFile(false);
      iwc.getMergePolicy().setNoCFSRatio(0.0);
      values = new long[numDocs];
      long t = random().nextInt(1000);
      try (IndexWriter w = new IndexWriter(dir, iwc)) {
        for (int i = 0; i < numDocs; i++) {
          if (random().nextInt(5_000) == 0) {
            t = random().nextInt(1000);
          }
          t += random().nextInt(3);
          values[i] = t * STEP;
          Document doc = new Document();
          doc.add(new LongPoint(FIELD, values[i]));
          doc.add(new NumericDocValuesField(FIELD, values[i]));
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }
      reader = DirectoryReader.open(dir);
      leaf = getOnlyLeafReader(reader);
      PointValues pv = leaf.getPointValues(FIELD);
      assertTrue(pv.getClass().getName(), pv instanceof SplitBKDReader);
      points = (SplitBKDReader) pv;
      String segment = null;
      for (String file : dir.listAll()) {
        if (file.endsWith("_Lucene90Split_0.kdi")) {
          segment = file.substring(0, file.length() - ".kdi".length());
        }
      }
      assertNotNull(segment);
      kdi = segment + ".kdi";
      kdd = segment + ".kdd";
      kdv = segment + ".kdv";
    }

    @Override
    public void close() throws IOException {
      reader.close();
      dir.close();
    }

    long[] sortedValues() {
      long[] sorted = values.clone();
      Arrays.sort(sorted);
      return sorted;
    }
  }

  static Relation relate(long min, long max, long lo, long hi) {
    if (min > hi || max < lo) {
      return Relation.CELL_OUTSIDE_QUERY;
    }
    if (min >= lo && max <= hi) {
      return Relation.CELL_INSIDE_QUERY;
    }
    return Relation.CELL_CROSSES_QUERY;
  }

  /** Every read of the split data files lies in a leaf whose tight bounds allow it. */
  static void assertReadsMatchTightBounds(SplitIndex index, long lo, long hi) throws IOException {
    int numLeaves = index.points.getNumLeaves();
    long[][] docAllowed = new long[numLeaves][];
    long[][] valAllowed = new long[numLeaves][];
    for (int i = 0; i < numLeaves; i++) {
      SplitBKDReader.LeafEntry e = index.points.leafEntry(i);
      Relation r =
          relate(
              LongPoint.decodeDimension(e.minPackedValue(), 0),
              LongPoint.decodeDimension(e.maxPackedValue(), 0),
              lo,
              hi);
      if (r != Relation.CELL_OUTSIDE_QUERY) {
        docAllowed[i] = new long[] {e.docBlockFP(), e.docBlockFP() + e.docBlockLength()};
      }
      if (r == Relation.CELL_CROSSES_QUERY) {
        valAllowed[i] = new long[] {e.valueBlockFP(), e.valueBlockFP() + e.valueBlockLength()};
      }
    }
    synchronized (index.recording) {
      assertWithin(
          index.recording.reads.get(index.kdd), docAllowed, "kdd [" + lo + ", " + hi + "]");
      assertWithin(
          index.recording.reads.get(index.kdv), valAllowed, "kdv [" + lo + ", " + hi + "]");
    }
  }

  private static void assertWithin(List<long[]> reads, long[][] allowed, String context) {
    if (reads == null) {
      return;
    }
    for (long[] read : reads) {
      boolean ok = false;
      for (long[] a : allowed) {
        if (a != null && read[0] >= a[0] && read[0] + read[1] <= a[1]) {
          ok = true;
          break;
        }
      }
      assertTrue(context + ": read at " + read[0] + " +" + read[1] + " is not allowed", ok);
    }
  }

  static IntersectVisitor rangeVisitor(long lo, long hi, long[] count) {
    return TestBKDIntersectPrefetch.rangeVisitor(lo, hi, count);
  }

  static long expectedCount(long[] values, long lo, long hi) {
    return TestBKDIntersectPrefetch.expectedCount(values, lo, hi);
  }

  /**
   * Ranges and their inverse-like gaps: every prefetched node of .kdd and .kdv is read, INSIDE
   * leaves read no values, leaves with OUTSIDE tight bounds read nothing, and reads equal the reads
   * with the switch off.
   */
  public void testSplitPrefetchesOnlyReadNodes() throws IOException {
    try (SplitIndex index = new SplitIndex(atLeast(30_000), TestUtil.nextInt(random(), 64, 512))) {
      long[] sorted = index.sortedValues();
      for (int iter = 0; iter < 30; iter++) {
        long nodeBytes = random().nextBoolean() ? 4096 : 131072;
        BKDExperiments.setNodeBytes(nodeBytes);
        BKDExperiments.setPrefetchChunks(TestUtil.nextInt(random(), 1, 8));
        long a = sorted[random().nextInt(sorted.length)];
        long b = sorted[random().nextInt(sorted.length)];
        long lo = Math.min(a, b);
        long hi = Math.max(a, b);
        switch (random().nextInt(5)) {
          case 0 -> hi = Long.MAX_VALUE;
          case 1 -> lo = Long.MIN_VALUE;
          case 2 -> {
            // a range inside a gap between values: loose cells may cross, tight bounds do not
            lo = a + 1;
            hi = a + STEP - 1;
          }
          default -> {}
        }
        long[] count = new long[1];
        BKDExperiments.setIntersectPrefetch(true);
        index.recording.clear();
        index.points.intersect(rangeVisitor(lo, hi, count));
        assertEquals(expectedCount(index.values, lo, hi), count[0]);
        String ctx = "[" + lo + ", " + hi + "] nodeBytes=" + nodeBytes;
        index.recording.assertAllPrefetchedRead(nodeBytes, ctx);
        assertReadsMatchTightBounds(index, lo, hi);
        TreeSet<Long> readDocs = index.recording.readBlocks(index.kdd, nodeBytes);
        TreeSet<Long> readVals = index.recording.readBlocks(index.kdv, nodeBytes);
        // exact ranges: every node read is prefetched
        assertEquals(ctx, readDocs, index.recording.prefetchedBlocks(index.kdd, nodeBytes));
        assertEquals(ctx, readVals, index.recording.prefetchedBlocks(index.kdv, nodeBytes));

        BKDExperiments.setIntersectPrefetch(false);
        index.recording.clear();
        count[0] = 0;
        index.points.intersect(rangeVisitor(lo, hi, count));
        assertEquals(expectedCount(index.values, lo, hi), count[0]);
        assertEquals(0, index.recording.prefetchCalls());
        assertEquals(readDocs, index.recording.readBlocks(index.kdd, nodeBytes));
        assertEquals(readVals, index.recording.readBlocks(index.kdv, nodeBytes));
      }
    }
  }

  /** The sort comparator's competitive intersections on the split field, asc and desc. */
  public void testSplitSortComparator() throws IOException {
    try (SplitIndex index = new SplitIndex(atLeast(30_000), 512)) {
      IndexSearcher searcher = new IndexSearcher(index.reader);
      BKDExperiments.setIntersectPrefetch(true);
      BKDExperiments.setIndexChildPrefetch(random().nextBoolean());
      int withPrefetch = 0;
      for (boolean reverse : new boolean[] {false, true}) {
        long nodeBytes = random().nextBoolean() ? 4096 : 131072;
        BKDExperiments.setNodeBytes(nodeBytes);
        Sort sort = new Sort(new SortField(FIELD, SortField.Type.LONG, reverse));
        index.recording.clear();
        searcher.search(new MatchAllDocsQuery(), new TopFieldCollectorManager(sort, 10, 1000));
        index.recording.assertAllPrefetchedRead(nodeBytes, "reverse=" + reverse);
        if (index.recording.prefetchCalls() > 0) {
          withPrefetch++;
        }
      }
      assertTrue(withPrefetch > 0);
    }
  }

  /** Child prefetch: requested index nodes are all read; inert with intersect prefetch off. */
  public void testChildPrefetch() throws IOException {
    try (SplitIndex index = new SplitIndex(atLeast(30_000), TestUtil.nextInt(random(), 16, 128))) {
      long[] sorted = index.sortedValues();
      BKDExperiments.setNodeBytes(4096);
      BKDExperiments.setIndexChildPrefetch(true);
      BKDExperiments.setWholeIndexPrefetch(random().nextBoolean());
      int kdiCalls = 0;
      for (int iter = 0; iter < 20; iter++) {
        long a = sorted[random().nextInt(sorted.length)];
        long b = sorted[random().nextInt(sorted.length)];
        long lo = Math.min(a, b);
        long hi = Math.max(a, b);
        long[] count = new long[1];
        BKDExperiments.setIntersectPrefetch(true);
        index.recording.clear();
        index.points.intersect(rangeVisitor(lo, hi, count));
        assertEquals(expectedCount(index.values, lo, hi), count[0]);
        index.recording.assertAllPrefetchedRead(4096, "child [" + lo + ", " + hi + "]");
        List<long[]> calls = index.recording.prefetches.get(index.kdi);
        kdiCalls += calls == null ? 0 : calls.size();

        BKDExperiments.setIntersectPrefetch(false);
        index.recording.clear();
        index.points.intersect(rangeVisitor(lo, hi, new long[1]));
        assertEquals(0, index.recording.prefetchCalls());
      }
      assertTrue("child prefetch never requested an index node", kdiCalls > 0);
    }
  }

  /**
   * Whole-section rule on a small field: a section in 1 node gets one request (the root's node); in
   * 2 nodes the second only when pass 1 visits it; in 3 nodes nothing.
   */
  public void testWholeSection() throws IOException {
    // grow the field until its index section ends in (8 KiB, 12 KiB]: 3 nodes of 4 KiB, 2 of 8 KiB
    // (the section starts in the first node), 1 of 16 KiB
    int numDocs = 6_000;
    SplitIndex index = null;
    for (int attempt = 0; attempt < 8; attempt++) {
      index = new SplitIndex(numDocs, 16);
      long end = index.points.directoryEndFP;
      if (index.points.indexStartFP < 4096 && end > 8192 && end <= 12288) {
        break;
      }
      numDocs = (int) Math.max(1000, numDocs * 10240L / end);
      index.close();
      index = null;
    }
    assertNotNull("could not size the field's index section", index);
    try (SplitIndex idx = index) {
      long[] sorted = idx.sortedValues();
      long min = sorted[0];
      long max = sorted[sorted.length - 1];
      BKDExperiments.setWholeIndexPrefetch(true);
      BKDExperiments.setIndexChildPrefetch(false);
      BKDExperiments.setIntersectPrefetch(true);

      // 1 node: one request, the root's node
      BKDExperiments.setNodeBytes(16384);
      for (long[] range : ranges(sorted, min, max)) {
        idx.recording.clear();
        idx.points.intersect(rangeVisitor(range[0], range[1], new long[1]));
        idx.recording.assertAllPrefetchedRead(16384, "1 node");
        assertEquals(1, idx.recording.prefetches.get(idx.kdi).size());
      }

      // 3 nodes: nothing
      BKDExperiments.setNodeBytes(4096);
      for (long[] range : ranges(sorted, min, max)) {
        idx.recording.clear();
        idx.points.intersect(rangeVisitor(range[0], range[1], new long[1]));
        assertNull(idx.recording.prefetches.get(idx.kdi));
      }

      // 2 nodes: the root's node always, the second only when pass 1 reads it
      BKDExperiments.setNodeBytes(8192);
      idx.recording.clear();
      // OUTSIDE at the root: pass 1 never leaves the root
      idx.points.intersect(rangeVisitor(max + 1, Long.MAX_VALUE, new long[1]));
      assertEquals(treeSetOf(0L), idx.recording.prefetchedBlocks(idx.kdi, 8192));
      // a point query on the first value of a leaf that is a right child and whose directory page
      // is in the second node: the child rule requests that page before pass 1 descends left
      int secondNodeRequests = 0;
      int tried = 0;
      for (int leafID = 0; leafID < idx.points.numLeaves && tried < 20; leafID++) {
        int page = leafID >>> idx.points.pageShift;
        if (idx.points.pageEndFP(page) <= 8192
            || (leafNode(leafID, idx.points.numLeaves) & 1) == 0) {
          continue;
        }
        tried++;
        long v = LongPoint.decodeDimension(idx.points.leafEntry(leafID).minPackedValue(), 0);
        idx.recording.clear();
        idx.points.intersect(rangeVisitor(v, v, new long[1]));
        idx.recording.assertAllPrefetchedRead(8192, "2 nodes, point " + v);
        if (idx.recording.prefetchedBlocks(idx.kdi, 8192).contains(1L)) {
          secondNodeRequests++;
        }
      }
      assertTrue(tried > 0);
      assertTrue("the second node was never requested", secondNodeRequests > 0);
      for (long[] range : ranges(sorted, min, max)) {
        idx.recording.clear();
        idx.points.intersect(rangeVisitor(range[0], range[1], new long[1]));
        idx.recording.assertAllPrefetchedRead(8192, "2 nodes");
      }

      // inert with intersect prefetch off
      BKDExperiments.setIntersectPrefetch(false);
      idx.recording.clear();
      idx.points.intersect(rangeVisitor(min + 1, max - 1, new long[1]));
      assertEquals(0, idx.recording.prefetchCalls());
    }
  }

  /** Node ID of leaf {@code leafID}, the inverse of {@link SplitBKDReader#leafIDOf}. */
  private static int leafNode(int leafID, int numLeaves) {
    if (numLeaves == 1) {
      return 1;
    }
    int p = Integer.highestOneBit(numLeaves - 1) << 1;
    int deep = 2 * numLeaves - p;
    int node = leafID < deep ? p + leafID : leafID - deep + numLeaves;
    assert SplitBKDReader.leafIDOf(node, numLeaves) == leafID;
    return node;
  }

  private static TreeSet<Long> treeSetOf(Long... blocks) {
    return new TreeSet<>(Arrays.asList(blocks));
  }

  private static long[][] ranges(long[] sorted, long min, long max) {
    long[][] ranges = new long[10][];
    for (int i = 0; i < ranges.length; i++) {
      long a = sorted[random().nextInt(sorted.length)];
      long b = sorted[random().nextInt(sorted.length)];
      ranges[i] = new long[] {Math.min(a, b), Math.max(a, b)};
    }
    ranges[0] = new long[] {min, max};
    ranges[1] = new long[] {min - 1, min - 1};
    return ranges;
  }
}
