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
package org.apache.lucene.codecs.lucene90;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.FilterIndexInput;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.RandomAccessInput;
import org.apache.lucene.tests.util.LuceneTestCase;

/**
 * Validates that prefetch calls in longValues() target byte ranges that are actually read. Uses a
 * tracking Directory wrapper that records all prefetch(offset, length) and read(offset) calls on
 * .dvd files, then asserts that every prefetched range contains at least one subsequent read.
 *
 * <p>This proves the prefetch offsets computed by prefetchFixedBPV, prefetchDISI, and the varying
 * BPV two-round strategy are correct — they target the actual data that will be read.
 */
public class TestLucene90DocValuesPrefetchCoverage extends LuceneTestCase {

  /** A byte range that was prefetched. */
  record PrefetchedRange(long offset, long length) {}

  /** Tracks prefetch and read offsets on a per-file basis. */
  static class IOTracker {
    final List<PrefetchedRange> prefetches = Collections.synchronizedList(new ArrayList<>());
    final List<Long> reads = Collections.synchronizedList(new ArrayList<>());

    void recordPrefetch(long offset, long length) {
      prefetches.add(new PrefetchedRange(offset, length));
    }

    void recordRead(long offset) {
      reads.add(offset);
    }

    /** Assert every prefetched range contains at least one read. */
    void assertAllPrefetchesUsed(String fileName) {
      if (prefetches.isEmpty()) return;

      for (PrefetchedRange pf : prefetches) {
        boolean found = false;
        for (long readOffset : reads) {
          if (readOffset >= pf.offset && readOffset < pf.offset + pf.length) {
            found = true;
            break;
          }
        }
        assertTrue(
            "Wasted prefetch in "
                + fileName
                + ": prefetch("
                + pf.offset
                + ", "
                + pf.length
                + ") had no reads in range. Total reads: "
                + reads.size(),
            found);
      }
    }
  }

  /** IndexInput wrapper that tracks prefetch and read offsets. */
  static class TrackingIndexInput extends FilterIndexInput implements RandomAccessInput {
    final IOTracker tracker;
    final long sliceOffset; // absolute offset of this slice within the file

    TrackingIndexInput(String resourceDesc, IndexInput in, IOTracker tracker, long sliceOffset) {
      super(resourceDesc, in);
      this.tracker = tracker;
      this.sliceOffset = sliceOffset;
    }

    @Override
    public void prefetch(long offset, long length) throws IOException {
      tracker.recordPrefetch(sliceOffset + offset, length);
      in.prefetch(offset, length);
    }

    @Override
    public byte readByte() throws IOException {
      tracker.recordRead(sliceOffset + getFilePointer());
      return in.readByte();
    }

    @Override
    public void readBytes(byte[] b, int offset, int len) throws IOException {
      tracker.recordRead(sliceOffset + getFilePointer());
      in.readBytes(b, offset, len);
    }

    @Override
    public short readShort() throws IOException {
      tracker.recordRead(sliceOffset + getFilePointer());
      return in.readShort();
    }

    @Override
    public int readInt() throws IOException {
      tracker.recordRead(sliceOffset + getFilePointer());
      return in.readInt();
    }

    @Override
    public long readLong() throws IOException {
      tracker.recordRead(sliceOffset + getFilePointer());
      return in.readLong();
    }

    @Override
    public IndexInput slice(String sliceDescription, long offset, long length) throws IOException {
      IndexInput sliced = in.slice(sliceDescription, offset, length);
      return new TrackingIndexInput(sliceDescription, sliced, tracker, sliceOffset + offset);
    }

    @Override
    public IndexInput clone() {
      IndexInput cloned = in.clone();
      return new TrackingIndexInput(toString(), cloned, tracker, sliceOffset);
    }

    @Override
    public RandomAccessInput randomAccessSlice(long offset, long length) throws IOException {
      // Return a tracking RandomAccessInput
      RandomAccessInput rai;
      IndexInput sliceInput = in.slice("rai", offset, length);
      if (sliceInput instanceof RandomAccessInput) {
        rai = (RandomAccessInput) sliceInput;
      } else {
        rai = in.randomAccessSlice(offset, length);
      }
      final long absOffset = sliceOffset + offset;
      final IOTracker t = tracker;
      return new RandomAccessInput() {
        @Override
        public long length() {
          return length;
        }

        @Override
        public byte readByte(long pos) throws IOException {
          t.recordRead(absOffset + pos);
          return rai.readByte(pos);
        }

        @Override
        public short readShort(long pos) throws IOException {
          t.recordRead(absOffset + pos);
          return rai.readShort(pos);
        }

        @Override
        public int readInt(long pos) throws IOException {
          t.recordRead(absOffset + pos);
          return rai.readInt(pos);
        }

        @Override
        public long readLong(long pos) throws IOException {
          t.recordRead(absOffset + pos);
          return rai.readLong(pos);
        }

        @Override
        public void prefetch(long pfOffset, long pfLength) throws IOException {
          t.recordPrefetch(absOffset + pfOffset, pfLength);
          rai.prefetch(pfOffset, pfLength);
        }
      };
    }

    // RandomAccessInput implementation (for when this is cast to RAI)
    @Override
    public byte readByte(long pos) throws IOException {
      tracker.recordRead(sliceOffset + pos);
      if (in instanceof RandomAccessInput rai) {
        return rai.readByte(pos);
      }
      in.seek(pos);
      return in.readByte();
    }

    @Override
    public short readShort(long pos) throws IOException {
      tracker.recordRead(sliceOffset + pos);
      if (in instanceof RandomAccessInput rai) {
        return rai.readShort(pos);
      }
      in.seek(pos);
      return in.readShort();
    }

    @Override
    public int readInt(long pos) throws IOException {
      tracker.recordRead(sliceOffset + pos);
      if (in instanceof RandomAccessInput rai) {
        return rai.readInt(pos);
      }
      in.seek(pos);
      return in.readInt();
    }

    @Override
    public long readLong(long pos) throws IOException {
      tracker.recordRead(sliceOffset + pos);
      if (in instanceof RandomAccessInput rai) {
        return rai.readLong(pos);
      }
      in.seek(pos);
      return in.readLong();
    }
  }

  /** Directory wrapper that tracks IO on .dvd files. */
  static class PrefetchTrackingDirectory extends FilterDirectory {
    final Map<String, IOTracker> trackers = new ConcurrentHashMap<>();

    PrefetchTrackingDirectory(Directory in) {
      super(in);
    }

    @Override
    public IndexInput openInput(String name, IOContext context) throws IOException {
      IndexInput in = super.openInput(name, context);
      if (name.endsWith(".dvd")) {
        IOTracker tracker = trackers.computeIfAbsent(name, k -> new IOTracker());
        return new TrackingIndexInput(name, in, tracker, 0);
      }
      return in;
    }

    void assertAllPrefetchesUsed() {
      for (var entry : trackers.entrySet()) {
        entry.getValue().assertAllPrefetchesUsed(entry.getKey());
      }
    }

    boolean hasPrefetches() {
      return trackers.values().stream().anyMatch(t -> !t.prefetches.isEmpty());
    }

    int totalPrefetches() {
      return trackers.values().stream().mapToInt(t -> t.prefetches.size()).sum();
    }
  }

  // ---- Tests ----

  /** Dense field with sequential values — exercises prefetchFixedBPV on packed data. */
  public void testPrefetchCoverageDenseFixedBPV() throws Exception {
    doTestPrefetchCoverage(1000, i -> (long) i, true);
  }

  /** Dense field with GCD values. */
  public void testPrefetchCoverageDenseGCD() throws Exception {
    doTestPrefetchCoverage(1000, i -> 3L + 7L * i, true);
  }

  /** Dense field with table values. */
  public void testPrefetchCoverageDenseTable() throws Exception {
    long[] table = {10, 20, 30, 40, 50};
    doTestPrefetchCoverage(1000, i -> table[i % table.length], true);
  }

  /** Sparse field — exercises prefetchDISI + prefetchFixedBPV on DISI indices. */
  public void testPrefetchCoverageSparse() throws Exception {
    doTestPrefetchCoverage(2000, i -> (long) i * 100, false);
  }

  /** Large dense field to span multiple cache blocks. */
  public void testPrefetchCoverageLargeDense() throws Exception {
    doTestPrefetchCoverage(50000, i -> (long) i * 1_000_000L, true);
  }

  @FunctionalInterface
  interface IntToLong {
    long apply(int i);
  }

  private void doTestPrefetchCoverage(int numDocs, IntToLong valueFunc, boolean allDocs)
      throws IOException {
    try (Directory baseDir = newDirectory()) {
      // Index docs
      IndexWriterConfig conf = new IndexWriterConfig();
      conf.setMaxBufferedDocs(numDocs + 1);
      try (IndexWriter w = new IndexWriter(baseDir, conf)) {
        for (int i = 0; i < numDocs; i++) {
          Document doc = new Document();
          if (allDocs || i % 2 == 0) {
            doc.add(new NumericDocValuesField("numeric", valueFunc.apply(i)));
          }
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }

      // Open with tracking directory
      try (PrefetchTrackingDirectory trackingDir = new PrefetchTrackingDirectory(baseDir)) {
        try (DirectoryReader reader = DirectoryReader.open(trackingDir)) {
          LeafReader leaf = reader.leaves().get(0).reader();
          NumericDocValues ndv = leaf.getNumericDocValues("numeric");
          assertNotNull(ndv);

          // Read via longValues() which should trigger prefetch
          int[] docs = new int[numDocs];
          for (int i = 0; i < numDocs; i++) docs[i] = i;
          long[] values = new long[numDocs];
          ndv.longValues(numDocs, docs, values, -1L);

          // Validate: every prefetched range must have been read
          trackingDir.assertAllPrefetchesUsed();

          // Log for visibility
          if (VERBOSE) {
            System.out.println(
                "Prefetch coverage: "
                    + trackingDir.totalPrefetches()
                    + " prefetches, all used");
          }
        }
      }
    }
  }
}
