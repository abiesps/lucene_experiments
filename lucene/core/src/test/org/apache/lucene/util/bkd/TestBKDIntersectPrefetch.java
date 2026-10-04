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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.PointValues.IntersectVisitor;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.search.CollectionTerminatedException;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopFieldCollectorManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.junit.After;

/**
 * The coalesced leaf prefetch of {@link PointValues#intersect(IntersectVisitor)}: every prefetched
 * storage node is read by the intersection that requested it, only visitors that opt in prefetch,
 * and the switch off means no prefetch at all.
 */
public class TestBKDIntersectPrefetch extends LuceneTestCase {

  @After
  public void resetSwitches() {
    BKDExperiments.setIntersectPrefetch(false);
    BKDExperiments.setPrefetchChunks(BKDExperiments.DEFAULT_PREFETCH_CHUNKS);
    BKDExperiments.setNodeBytes(BKDExperiments.DEFAULT_NODE_BYTES);
    BKDExperiments.setWholeIndexPrefetch(false);
    BKDExperiments.setWholeIndexPrefetchBytes(BKDExperiments.DEFAULT_WHOLE_INDEX_PREFETCH_BYTES);
    BKDExperiments.setIndexChildPrefetch(false);
  }

  /** Stock-format index with a random-value field and a clustered, timestamp-like field. */
  static final class Index implements AutoCloseable {
    final Recording recording = new Recording();
    final Directory dir;
    final IndexReader reader;
    final LeafReader leaf;
    final long[] randomValues;
    final long[] timeValues;

    Index(int numDocs, Codec codec, String... extraTimeFields) throws IOException {
      dir = new RecordingDirectory(newDirectory(), recording);
      // no compound file: the recording directory sees the points files themselves
      IndexWriterConfig iwc = new IndexWriterConfig().setCodec(codec).setUseCompoundFile(false);
      iwc.getMergePolicy().setNoCFSRatio(0.0);
      randomValues = new long[numDocs];
      timeValues = new long[numDocs];
      long t = random().nextInt(1000);
      try (IndexWriter w = new IndexWriter(dir, iwc)) {
        for (int i = 0; i < numDocs; i++) {
          // a few time-ordered runs, as in logs, with duplicate values
          if (random().nextInt(20_000) == 0) {
            t = random().nextInt(1000);
          }
          t += random().nextInt(3);
          randomValues[i] = random().nextLong();
          timeValues[i] = t;
          Document doc = new Document();
          doc.add(new LongPoint("r", randomValues[i]));
          doc.add(new NumericDocValuesField("r", randomValues[i]));
          doc.add(new LongPoint("t", t));
          doc.add(new NumericDocValuesField("t", t));
          for (String f : extraTimeFields) {
            doc.add(new LongPoint(f, t));
            doc.add(new NumericDocValuesField(f, t));
          }
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }
      reader = DirectoryReader.open(dir);
      leaf = getOnlyLeafReader(reader);
    }

    @Override
    public void close() throws IOException {
      reader.close();
      dir.close();
    }
  }

  /** File offsets of reads and prefetches, per file, of the points data files. */
  static final class Recording {
    final Map<String, List<long[]>> reads = new TreeMap<>();
    final Map<String, List<long[]>> prefetches = new TreeMap<>();

    synchronized void clear() {
      reads.clear();
      prefetches.clear();
    }

    synchronized void read(String file, long offset, long length) {
      reads.computeIfAbsent(file, k -> new ArrayList<>()).add(new long[] {offset, length});
    }

    synchronized void prefetch(String file, long offset, long length) {
      prefetches.computeIfAbsent(file, k -> new ArrayList<>()).add(new long[] {offset, length});
    }

    synchronized int prefetchCalls() {
      int calls = 0;
      for (List<long[]> l : prefetches.values()) {
        calls += l.size();
      }
      return calls;
    }

    static TreeSet<Long> blocks(List<long[]> ranges, long nodeBytes) {
      TreeSet<Long> blocks = new TreeSet<>();
      if (ranges != null) {
        for (long[] r : ranges) {
          if (r[1] == 0) {
            continue;
          }
          for (long b = r[0] / nodeBytes; b <= (r[0] + r[1] - 1) / nodeBytes; b++) {
            blocks.add(b);
          }
        }
      }
      return blocks;
    }

    synchronized TreeSet<Long> readBlocks(String file, long nodeBytes) {
      return blocks(reads.get(file), nodeBytes);
    }

    synchronized TreeSet<Long> prefetchedBlocks(String file, long nodeBytes) {
      return blocks(prefetches.get(file), nodeBytes);
    }

    /** Asserts that every prefetched node of every recorded file was read. */
    synchronized void assertAllPrefetchedRead(long nodeBytes, String context) {
      for (String file : prefetches.keySet()) {
        TreeSet<Long> unread = prefetchedBlocks(file, nodeBytes);
        unread.removeAll(readBlocks(file, nodeBytes));
        assertTrue(context + ": " + file + " prefetched but not read: " + unread, unread.isEmpty());
      }
    }

    /** Number of nodes of {@code file} that were read but not prefetched. */
    synchronized int notPrefetched(String file, long nodeBytes) {
      TreeSet<Long> missed = readBlocks(file, nodeBytes);
      missed.removeAll(prefetchedBlocks(file, nodeBytes));
      return missed.size();
    }
  }

  static final class RecordingDirectory extends FilterDirectory {
    final Recording recording;

    RecordingDirectory(Directory in, Recording recording) {
      super(in);
      this.recording = recording;
    }

    @Override
    public IndexInput openInput(String name, IOContext context) throws IOException {
      IndexInput input = in.openInput(name, context);
      return name.endsWith(".kdd") || name.endsWith(".kdv")
          ? new RecordingInput(name, input, 0, recording)
          : input;
    }
  }

  static final class RecordingInput extends IndexInput {
    final String file;
    final IndexInput in;
    final long base;
    final Recording recording;

    RecordingInput(String file, IndexInput in, long base, Recording recording) {
      super("recording(" + in + ")");
      this.file = file;
      this.in = in;
      this.base = base;
      this.recording = recording;
    }

    @Override
    public void prefetch(long offset, long length) throws IOException {
      recording.prefetch(file, base + offset, length);
      in.prefetch(offset, length);
    }

    @Override
    public byte readByte() throws IOException {
      recording.read(file, base + in.getFilePointer(), 1);
      return in.readByte();
    }

    @Override
    public void readBytes(byte[] b, int offset, int len) throws IOException {
      recording.read(file, base + in.getFilePointer(), len);
      in.readBytes(b, offset, len);
    }

    @Override
    public void close() throws IOException {
      in.close();
    }

    @Override
    public long getFilePointer() {
      return in.getFilePointer();
    }

    @Override
    public void seek(long pos) throws IOException {
      in.seek(pos);
    }

    @Override
    public long length() {
      return in.length();
    }

    @Override
    public IndexInput slice(String desc, long offset, long length) throws IOException {
      return new RecordingInput(file, in.slice(desc, offset, length), base + offset, recording);
    }

    @Override
    public RecordingInput clone() {
      return new RecordingInput(file, in.clone(), base, recording);
    }
  }

  /** Counts matches through the scorer, so the range query really intersects the points. */
  static final class CountingManager implements CollectorManager<Collector, Long> {
    @Override
    public Collector newCollector() {
      return new Collector() {
        long count;

        @Override
        public LeafCollector getLeafCollector(LeafReaderContext context) {
          return new LeafCollector() {
            @Override
            public void setScorer(Scorable scorer) {}

            @Override
            public void collect(int doc) {
              count++;
            }
          };
        }

        @Override
        public ScoreMode scoreMode() {
          return ScoreMode.COMPLETE_NO_SCORES;
        }

        @Override
        public String toString() {
          return Long.toString(count);
        }
      };
    }

    @Override
    public Long reduce(java.util.Collection<Collector> collectors) {
      long total = 0;
      for (Collector c : collectors) {
        total += Long.parseLong(c.toString());
      }
      return total;
    }
  }

  static long expectedCount(long[] values, long lo, long hi) {
    long n = 0;
    for (long v : values) {
      if (v >= lo && v <= hi) {
        n++;
      }
    }
    return n;
  }

  private static void setNodeBytes(long nodeBytes) {
    BKDExperiments.setNodeBytes(nodeBytes);
  }

  private static long randomNodeBytes() {
    return random().nextBoolean() ? 4096 : 131072;
  }

  /** Range queries (normal and inverse visitor) prefetch only nodes they read. */
  public void testRangeQueryPrefetchesOnlyReadNodes() throws IOException {
    try (Index index = new Index(atLeast(40_000), TestUtil.getDefaultCodec())) {
      IndexSearcher searcher = new IndexSearcher(index.reader);
      searcher.setQueryCache(null);
      for (int iter = 0; iter < 20; iter++) {
        long nodeBytes = randomNodeBytes();
        int chunks = TestUtil.nextInt(random(), 1, 8);
        setNodeBytes(nodeBytes);
        BKDExperiments.setPrefetchChunks(chunks);
        BKDExperiments.setIntersectPrefetch(true);
        boolean timeField = random().nextBoolean();
        String field = timeField ? "t" : "r";
        long[] values = timeField ? index.timeValues : index.randomValues;
        long a = values[random().nextInt(values.length)];
        long b = values[random().nextInt(values.length)];
        long lo = Math.min(a, b);
        long hi = Math.max(a, b);
        switch (random().nextInt(4)) {
          case 0 -> hi = Long.MAX_VALUE; // run ends with INSIDE leaves at the field's end
          case 1 -> lo = Long.MIN_VALUE; // run starts at the field's first leaf
          default -> {}
        }
        Query q = LongPoint.newRangeQuery(field, lo, hi);
        index.recording.clear();
        long count = searcher.search(q, new CountingManager());
        assertEquals(expectedCount(values, lo, hi), count);
        String ctx = field + " [" + lo + ", " + hi + "] nodeBytes=" + nodeBytes;
        index.recording.assertAllPrefetchedRead(nodeBytes, ctx);
        int calls = index.recording.prefetchCalls();
        assertTrue(ctx, calls > 0 || index.recording.reads.isEmpty());
        if (nodeBytes > BKDReader.maxValueSectionBytes(BKDConfig.of(1, 1, 8, 512))) {
          // coalesced: one run (or its inverse: at most two), each in at most `chunks` calls
          assertTrue(ctx + " calls=" + calls, calls <= 2 * chunks);
          for (String file : index.recording.reads.keySet()) {
            // only an INSIDE-ended run can miss its last leaf's doc IDs past the first node
            assertTrue(
                ctx + " missed " + index.recording.notPrefetched(file, nodeBytes),
                index.recording.notPrefetched(file, nodeBytes) <= 2);
          }
        }
        // switch off: same reads, no prefetch
        BKDExperiments.setIntersectPrefetch(false);
        index.recording.clear();
        assertEquals(count, searcher.search(q, new CountingManager()).longValue());
        assertEquals(0, index.recording.prefetchCalls());
      }
    }
  }

  /** The visitor of the inverse range (more than half the docs match) prefetches too. */
  public void testInverseVisitor() throws IOException {
    try (Index index = new Index(atLeast(40_000), TestUtil.getDefaultCodec())) {
      IndexSearcher searcher = new IndexSearcher(index.reader);
      searcher.setQueryCache(null);
      long nodeBytes = randomNodeBytes();
      setNodeBytes(nodeBytes);
      BKDExperiments.setIntersectPrefetch(true);
      // a range that matches about 90% of the random values: the inverse visitor is used
      long lo = Long.MIN_VALUE / 10 * 9;
      long hi = Long.MAX_VALUE / 10 * 9;
      index.recording.clear();
      long count = searcher.search(LongPoint.newRangeQuery("r", lo, hi), new CountingManager());
      assertEquals(expectedCount(index.randomValues, lo, hi), count);
      assertTrue(index.recording.prefetchCalls() > 0);
      index.recording.assertAllPrefetchedRead(nodeBytes, "inverse");
    }
  }

  /** The competitive-iterator intersections of the numeric sort comparator, asc and desc. */
  public void testSortComparatorPrefetchesOnlyReadNodes() throws IOException {
    try (Index index = new Index(atLeast(40_000), TestUtil.getDefaultCodec())) {
      IndexSearcher searcher = new IndexSearcher(index.reader);
      int withPrefetch = 0;
      for (boolean reverse : new boolean[] {false, true}) {
        for (String field : new String[] {"r", "t"}) {
          long nodeBytes = randomNodeBytes();
          setNodeBytes(nodeBytes);
          BKDExperiments.setIntersectPrefetch(true);
          Sort sort = new Sort(new SortField(field, SortField.Type.LONG, reverse));
          int numHits = random().nextBoolean() ? 10 : 500;
          index.recording.clear();
          var on =
              searcher.search(
                  new MatchAllDocsQuery(), new TopFieldCollectorManager(sort, numHits, 1000));
          index.recording.assertAllPrefetchedRead(nodeBytes, field + " reverse=" + reverse);
          if (index.recording.prefetchCalls() > 0) {
            withPrefetch++;
          }
          BKDExperiments.setIntersectPrefetch(false);
          index.recording.clear();
          var off =
              searcher.search(
                  new MatchAllDocsQuery(), new TopFieldCollectorManager(sort, numHits, 1000));
          assertEquals(0, index.recording.prefetchCalls());
          assertEquals(on.scoreDocs.length, off.scoreDocs.length);
          for (int i = 0; i < on.scoreDocs.length; i++) {
            assertEquals(on.scoreDocs[i].doc, off.scoreDocs[i].doc);
          }
        }
      }
      assertTrue("no comparator intersection prefetched", withPrefetch > 0);
    }
  }

  /** A visitor that does not opt in (like MinAggregator's, which stops at the first doc). */
  public void testVisitorWithoutOptInDoesNotPrefetch() throws IOException {
    try (Index index = new Index(atLeast(10_000), TestUtil.getDefaultCodec())) {
      BKDExperiments.setIntersectPrefetch(true);
      PointValues values = index.leaf.getPointValues("r");
      index.recording.clear();
      expectThrows(
          CollectionTerminatedException.class,
          () ->
              values.intersect(
                  new IntersectVisitor() {
                    @Override
                    public void visit(int docID) {
                      throw new CollectionTerminatedException();
                    }

                    @Override
                    public void visit(int docID, byte[] packedValue) {
                      throw new CollectionTerminatedException();
                    }

                    @Override
                    public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
                      return Relation.CELL_CROSSES_QUERY;
                    }
                  }));
      assertEquals(0, index.recording.prefetchCalls());
    }
  }

  /**
   * Large-leaf rule: at 4 KiB one value section of a high-cardinality leaf can cover a whole node,
   * so INSIDE leaves are requested leaf by leaf (first node only); at 128 KiB runs are coalesced.
   * Both prefetch only nodes that are read.
   */
  public void testLargeLeafRule() throws IOException {
    assertTrue(BKDReader.maxValueSectionBytes(BKDConfig.of(1, 1, 8, 512)) > 4096);
    assertTrue(BKDReader.maxValueSectionBytes(BKDConfig.of(1, 1, 8, 512)) < 131072);
    try (Index index = new Index(atLeast(40_000), TestUtil.getDefaultCodec())) {
      PointValues values = index.leaf.getPointValues("r");
      BKDExperiments.setIntersectPrefetch(true);
      for (long nodeBytes : new long[] {4096, 131072}) {
        setNodeBytes(nodeBytes);
        BKDExperiments.setPrefetchChunks(BKDExperiments.MAX_PREFETCH_CHUNKS);
        long lo = Long.MIN_VALUE / 2;
        long hi = Long.MAX_VALUE / 2;
        index.recording.clear();
        long[] count = new long[1];
        values.intersect(rangeVisitor(lo, hi, count));
        assertEquals(expectedCount(index.randomValues, lo, hi), count[0]);
        index.recording.assertAllPrefetchedRead(nodeBytes, "nodeBytes=" + nodeBytes);
        assertTrue(index.recording.prefetchCalls() > 0);
        for (String file : index.recording.prefetches.keySet()) {
          TreeSet<Long> prefetched = index.recording.prefetchedBlocks(file, nodeBytes);
          TreeSet<Long> read = index.recording.readBlocks(file, nodeBytes);
          if (nodeBytes == 4096) {
            // leaf by leaf: some read nodes (doc IDs past a leaf's first node) are not requested
            assertTrue(prefetched.size() <= read.size());
          } else {
            // coalesced, CROSSES-ended run: every read node was requested
            assertEquals(read, prefetched);
          }
        }
      }
    }
  }

  static IntersectVisitor rangeVisitor(long lo, long hi, long[] count) {
    return new IntersectVisitor() {
      @Override
      public void visit(int docID) {
        count[0]++;
      }

      @Override
      public void visit(int docID, byte[] packedValue) {
        long v = LongPoint.decodeDimension(packedValue, 0);
        if (v >= lo && v <= hi) {
          count[0]++;
        }
      }

      @Override
      public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
        long min = LongPoint.decodeDimension(minPackedValue, 0);
        long max = LongPoint.decodeDimension(maxPackedValue, 0);
        if (min > hi || max < lo) {
          return Relation.CELL_OUTSIDE_QUERY;
        }
        if (min >= lo && max <= hi) {
          return Relation.CELL_INSIDE_QUERY;
        }
        return Relation.CELL_CROSSES_QUERY;
      }

      @Override
      public boolean prefetchIntersect() {
        return true;
      }
    };
  }

  /** Several disjoint INSIDE/CROSSES runs separated by OUTSIDE nodes, with any chunk count. */
  public void testDisjointRuns() throws IOException {
    try (Index index = new Index(atLeast(40_000), TestUtil.getDefaultCodec())) {
      PointValues values = index.leaf.getPointValues("t");
      long[] sorted = index.timeValues.clone();
      java.util.Arrays.sort(sorted);
      for (int iter = 0; iter < 20; iter++) {
        long nodeBytes = randomNodeBytes();
        int chunks = TestUtil.nextInt(random(), 1, 16);
        setNodeBytes(nodeBytes);
        BKDExperiments.setPrefetchChunks(chunks);
        BKDExperiments.setIntersectPrefetch(true);
        // two ranges with a gap: an inverse of [lo, hi] over [min, max]
        long lo = sorted[random().nextInt(sorted.length)];
        long hi = lo + random().nextInt(200);
        long[] count = new long[1];
        index.recording.clear();
        IntersectVisitor inside = rangeVisitor(lo, hi, new long[1]);
        values.intersect(
            new IntersectVisitor() {
              @Override
              public void visit(int docID) {
                count[0]++;
              }

              @Override
              public void visit(int docID, byte[] packedValue) {
                long v = LongPoint.decodeDimension(packedValue, 0);
                if (v < lo || v > hi) {
                  count[0]++;
                }
              }

              @Override
              public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
                return switch (inside.compare(minPackedValue, maxPackedValue)) {
                  case CELL_INSIDE_QUERY -> Relation.CELL_OUTSIDE_QUERY;
                  case CELL_OUTSIDE_QUERY -> Relation.CELL_INSIDE_QUERY;
                  case CELL_CROSSES_QUERY -> Relation.CELL_CROSSES_QUERY;
                };
              }

              @Override
              public boolean prefetchIntersect() {
                return true;
              }
            });
        long expected = index.timeValues.length - expectedCount(index.timeValues, lo, hi);
        assertEquals(expected, count[0]);
        index.recording.assertAllPrefetchedRead(nodeBytes, "gap [" + lo + ", " + hi + "]");
      }
    }
  }
}
