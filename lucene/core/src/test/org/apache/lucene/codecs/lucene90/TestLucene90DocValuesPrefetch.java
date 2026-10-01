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
import java.util.List;
import java.util.function.LongUnaryOperator;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValuesSkipper;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.TieredMergePolicy;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.BytesRef;

/**
 * Node planning of dense Lucene90 doc values ({@code nextPrefetchNodeDoc}, {@code prefetchNodes})
 * must agree with the bytes Lucene actually reads for each doc's value.
 */
public class TestLucene90DocValuesPrefetch extends LuceneTestCase {

  private static final String[] NUMERIC = {"blocks", "table", "gcd", "plain"};

  private static LongUnaryOperator pattern(String encoding) {
    return switch (encoding) {
      // blocks of 16,384 values with very different ranges, never constant: varying bits per value
      case "blocks" ->
          doc ->
              ((doc >>> 14) % 3) == 0
                  ? random().nextInt(1 << 4)
                  : (1L << 40) + random().nextInt(1 << TestUtil.nextInt(random(), 8, 20));
      case "table" -> doc -> new long[] {-7, 3, 1_000_000, 42}[random().nextInt(4)];
      case "gcd" -> doc -> -5_000_000L + 1_000L * random().nextInt(1 << 20);
      case "plain" -> doc -> random().nextInt(1 << 12);
      default -> throw new AssertionError(encoding);
    };
  }

  private Directory dir;
  private Recording recording;
  private DirectoryReader reader;
  private LeafReader leaf;
  private int maxDoc;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    recording = new Recording();
    dir = new RecordingDirectory(new ByteBuffersDirectory(), recording);
    maxDoc = TestUtil.nextInt(random(), 40_000, 90_000);
    LongUnaryOperator[] patterns = new LongUnaryOperator[NUMERIC.length];
    for (int i = 0; i < NUMERIC.length; i++) {
      patterns[i] = pattern(NUMERIC[i]);
    }
    // no compound files: the recording directory must see .dvd and .dvs opened directly
    TieredMergePolicy mp = new TieredMergePolicy();
    mp.setNoCFSRatio(0.0);
    IndexWriterConfig config =
        new IndexWriterConfig()
            .setCodec(TestUtil.getDefaultCodec())
            .setUseCompoundFile(false)
            .setMergePolicy(mp);
    try (IndexWriter w = new IndexWriter(dir, config)) {
      for (int doc = 0; doc < maxDoc; doc++) {
        Document d = new Document();
        for (int i = 0; i < NUMERIC.length; i++) {
          d.add(new NumericDocValuesField(NUMERIC[i], patterns[i].applyAsLong(doc)));
        }
        d.add(new SortedDocValuesField("sorted", new BytesRef("t" + random().nextInt(3000))));
        d.add(NumericDocValuesField.indexedField("skipped", doc / 7));
        w.addDocument(d);
      }
      w.forceMerge(1);
    }
    reader = DirectoryReader.open(dir);
    leaf = getOnlyLeafReader(reader);
  }

  @Override
  public void tearDown() throws Exception {
    reader.close();
    dir.close();
    super.tearDown();
  }

  /** Node of the first byte of {@code doc}'s value: the last .dvd read when reading it fresh. */
  private long valueNode(String field, int doc, long nodeBytes) throws IOException {
    recording.clear();
    if (field.equals("sorted")) {
      SortedDocValues dv = leaf.getSortedDocValues(field);
      assertTrue(dv.advanceExact(doc));
      dv.ordValue();
    } else {
      NumericDocValues dv = leaf.getNumericDocValues(field);
      assertTrue(dv.advanceExact(doc));
      dv.longValue();
    }
    long[] last = recording.reads.get(recording.reads.size() - 1);
    return last[0] / nodeBytes;
  }

  private int nextNodeDoc(String field, int doc, long nodeBytes) throws IOException {
    return field.equals("sorted")
        ? leaf.getSortedDocValues(field).nextPrefetchNodeDoc(doc, nodeBytes)
        : leaf.getNumericDocValues(field).nextPrefetchNodeDoc(doc, nodeBytes);
  }

  private static String[] fields() {
    String[] all = new String[NUMERIC.length + 1];
    System.arraycopy(NUMERIC, 0, all, 0, NUMERIC.length);
    all[NUMERIC.length] = "sorted";
    return all;
  }

  /** nextPrefetchNodeDoc(d) is the first doc whose value starts in a later node than d's. */
  public void testNextNodeDoc() throws IOException {
    long nodeBytes = 1L << TestUtil.nextInt(random(), 9, 13);
    for (String field : fields()) {
      for (int iter = 0; iter < 30; iter++) {
        int doc = random().nextInt(maxDoc);
        int next = nextNodeDoc(field, doc, nodeBytes);
        assertTrue(field + " supported", next >= 0);
        long node = valueNode(field, doc, nodeBytes);
        if (next == DocIdSetIterator.NO_MORE_DOCS) {
          assertEquals(field, node, valueNode(field, maxDoc - 1, nodeBytes));
        } else {
          assertTrue(field + " doc=" + doc + " next=" + next, next > doc);
          assertEquals(field + " doc=" + doc, node, valueNode(field, next - 1, nodeBytes));
          assertTrue(field + " doc=" + doc, valueNode(field, next, nodeBytes) > node);
        }
      }
    }
  }

  private boolean prefetch(String field, Object dv, int from, int to, long nodeBytes)
      throws IOException {
    return dv instanceof SortedDocValues sorted
        ? sorted.prefetchNodes(from, to, nodeBytes)
        : ((NumericDocValues) dv).prefetchNodes(from, to, nodeBytes);
  }

  private Object fresh(String field) throws IOException {
    return field.equals("sorted")
        ? leaf.getSortedDocValues(field)
        : leaf.getNumericDocValues(field);
  }

  /**
   * prefetchNodes(d, d + 1) on a fresh instance requests, in whole nodes inside the file, the node
   * read for d's value; the same request again requests nothing.
   */
  public void testPrefetchCoversReadNode() throws IOException {
    long nodeBytes = 1L << TestUtil.nextInt(random(), 9, 13);
    for (String field : fields()) {
      for (int iter = 0; iter < 30; iter++) {
        int doc = random().nextInt(maxDoc);
        Object dv = fresh(field);
        recording.clear();
        assertTrue(field, prefetch(field, dv, doc, doc + 1, nodeBytes));
        List<long[]> requests = new ArrayList<>(recording.prefetches);
        long node = valueNode(field, doc, nodeBytes);
        boolean covered = false;
        for (long[] p : requests) {
          assertTrue(field + " non-empty request", p[1] > 0);
          if (p[0] / nodeBytes <= node && (p[0] + p[1] - 1) / nodeBytes >= node) {
            covered = true;
          }
        }
        assertTrue(field + " doc=" + doc + " node=" + node + " not requested", covered);
        recording.clear();
        prefetch(field, dv, doc, doc + 1, nodeBytes);
        assertTrue(field + " requested twice", recording.prefetches.isEmpty());
      }
    }
  }

  /** A range request covers the nodes of every doc in the range. */
  public void testRangeRequest() throws IOException {
    long nodeBytes = 1L << TestUtil.nextInt(random(), 9, 13);
    for (String field : fields()) {
      int from = random().nextInt(maxDoc);
      int to = Math.min(maxDoc, from + 1 + random().nextInt(5000));
      Object dv = fresh(field);
      recording.clear();
      prefetch(field, dv, from, to, nodeBytes);
      List<long[]> requests = new ArrayList<>(recording.prefetches);
      for (int iter = 0; iter < 20; iter++) {
        int doc = from + random().nextInt(to - from);
        long node = valueNode(field, doc, nodeBytes);
        boolean covered = false;
        for (long[] p : requests) {
          if (p[0] / nodeBytes <= node && (p[0] + p[1] - 1) / nodeBytes >= node) {
            covered = true;
          }
        }
        assertTrue(field + " doc=" + doc + " in [" + from + ", " + to + ")", covered);
      }
    }
  }

  public void testSkipperPrefetch() throws IOException {
    DocValuesSkipper skipper = leaf.getDocValuesSkipper("skipped");
    assertNotNull(skipper);
    recording.clear();
    skipper.prefetch();
    assertFalse("skipper prefetched", recording.prefetches.isEmpty());
  }

  /** Records file offsets of reads and prefetches of .dvd and .dvs files. */
  private static final class Recording {
    final List<long[]> reads = new ArrayList<>();
    final List<long[]> prefetches = new ArrayList<>();

    synchronized void clear() {
      reads.clear();
      prefetches.clear();
    }
  }

  private static final class RecordingDirectory extends FilterDirectory {
    final Recording recording;

    RecordingDirectory(Directory in, Recording recording) {
      super(in);
      this.recording = recording;
    }

    @Override
    public IndexInput openInput(String name, IOContext context) throws IOException {
      IndexInput input = in.openInput(name, context);
      return name.endsWith(".dvd") || name.endsWith(".dvs")
          ? new RecordingInput(input, 0, recording)
          : input;
    }
  }

  private static final class RecordingInput extends IndexInput {
    final IndexInput in;
    final long base;
    final Recording recording;

    RecordingInput(IndexInput in, long base, Recording recording) {
      super("recording(" + in + ")");
      this.in = in;
      this.base = base;
      this.recording = recording;
    }

    @Override
    public void prefetch(long offset, long length) throws IOException {
      synchronized (recording) {
        recording.prefetches.add(new long[] {base + offset, length});
      }
      in.prefetch(offset, length);
    }

    @Override
    public byte readByte() throws IOException {
      synchronized (recording) {
        recording.reads.add(new long[] {base + in.getFilePointer(), 1});
      }
      return in.readByte();
    }

    @Override
    public void readBytes(byte[] b, int offset, int len) throws IOException {
      synchronized (recording) {
        recording.reads.add(new long[] {base + in.getFilePointer(), len});
      }
      in.readBytes(b, offset, len);
    }

    private void record(int len) {
      synchronized (recording) {
        recording.reads.add(new long[] {base + in.getFilePointer(), len});
      }
    }

    @Override
    public short readShort() throws IOException {
      record(Short.BYTES);
      return in.readShort();
    }

    @Override
    public int readInt() throws IOException {
      record(Integer.BYTES);
      return in.readInt();
    }

    @Override
    public long readLong() throws IOException {
      record(Long.BYTES);
      return in.readLong();
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
      return new RecordingInput(in.slice(desc, offset, length), base + offset, recording);
    }

    @Override
    public RecordingInput clone() {
      return new RecordingInput(in.clone(), base, recording);
    }
  }
}
