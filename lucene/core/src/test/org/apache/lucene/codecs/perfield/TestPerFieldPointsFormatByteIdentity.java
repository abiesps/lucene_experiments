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
package org.apache.lucene.codecs.perfield;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Random;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.lucene.codecs.lucene104.Lucene104SplitPointsCodec;
import org.apache.lucene.codecs.lucene90.SplitPointsAssert;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LatLonPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LogDocMergePolicy;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.index.SerialMergeScheduler;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.util.bkd.BKDConfig;
import org.apache.lucene.util.bkd.BKDReader;
import org.apache.lucene.util.bkd.SplitBKDReader;

/** T1 and T2: the stock points files keep their bytes when a per-field points format is used. */
public class TestPerFieldPointsFormatByteIdentity extends LuceneTestCase {

  /**
   * SHA-256 of the stock points file bodies of the fixed corpus (5 flushed segments, then the
   * force-merged segment), written by plain {@link Lucene104Codec} on the base commit 1373677cfb.
   */
  static final String GOLDEN_STOCK_BODIES_SHA256 =
      "ec709088405f9768a67500639737106e901ddd705a4ff30daf34598d6be67274";

  static final int NUM_SEGMENTS = 5;
  static final int DOCS_PER_SEGMENT = 4_000;
  static final String TWIN = "ts_twin";

  /** How the corpus indexes the twin field. */
  enum TwinMode {
    /** Points and doc values, like {@code ts}. */
    POINTS,
    /** Doc values only: same field set and field order, no points. */
    DOC_VALUES_ONLY
  }

  /** Adds the docs of segment {@code segment} of the fixed corpus. */
  static void addSegmentDocs(IndexWriter w, int segment, TwinMode twinMode) throws IOException {
    Random r = new Random(0x5EEDL * 31 + segment);
    long ts = 1_700_000_000_000L + segment * 10_000_000L;
    for (int i = 0; i < DOCS_PER_SEGMENT; i++) {
      Document doc = new Document();
      // mostly increasing timestamps with some noise and repeats
      ts += r.nextInt(4) == 0 ? 0 : r.nextInt(2_000);
      long value = ts - (r.nextInt(10) == 0 ? r.nextInt(50_000) : 0);
      if (r.nextInt(50) != 0) {
        doc.add(new LongPoint("ts", value));
        doc.add(new NumericDocValuesField("ts", value));
        if (twinMode == TwinMode.POINTS) {
          doc.add(new LongPoint(TWIN, value));
        }
        doc.add(new NumericDocValuesField(TWIN, value));
      }
      int numInts = r.nextInt(4);
      for (int j = 0; j < numInts; j++) {
        doc.add(new IntPoint("i", r.nextInt(100)));
      }
      if (r.nextBoolean()) {
        doc.add(new LatLonPoint("geo", r.nextDouble() * 180 - 90, r.nextDouble() * 360 - 180));
      }
      doc.add(new IntPoint("nd", r.nextInt(1000), r.nextInt()));
      w.addDocument(doc);
    }
  }

  static IndexWriterConfig flushConfig(Codec codec) {
    IndexWriterConfig iwc = new IndexWriterConfig();
    iwc.setCodec(codec);
    iwc.setMergePolicy(NoMergePolicy.INSTANCE);
    iwc.setMergeScheduler(new SerialMergeScheduler());
    iwc.setMaxBufferedDocs(IndexWriterConfig.DISABLE_AUTO_FLUSH);
    iwc.setRAMBufferSizeMB(256);
    iwc.setUseCompoundFile(false);
    return iwc;
  }

  static IndexWriterConfig mergeConfig(Codec codec) {
    LogDocMergePolicy mp = new LogDocMergePolicy();
    mp.setNoCFSRatio(0.0);
    IndexWriterConfig iwc = new IndexWriterConfig();
    iwc.setCodec(codec);
    iwc.setMergePolicy(mp);
    iwc.setMergeScheduler(new SerialMergeScheduler());
    iwc.setUseCompoundFile(false);
    return iwc;
  }

  /** Writes the corpus as {@link #NUM_SEGMENTS} flushed segments. */
  static void writeCorpus(Directory dir, Codec codec, TwinMode twinMode) throws IOException {
    try (IndexWriter w = new IndexWriter(dir, flushConfig(codec))) {
      for (int s = 0; s < NUM_SEGMENTS; s++) {
        addSegmentDocs(w, s, twinMode);
        w.commit();
      }
    }
  }

  /** Force merges the index in {@code dir} to one segment. */
  static void forceMerge(Directory dir, Codec codec) throws IOException {
    try (IndexWriter w = new IndexWriter(dir, mergeConfig(codec))) {
      w.forceMerge(1);
      w.commit();
    }
  }

  /** Feeds the stock points file bodies of every segment of the last commit into {@code md}. */
  static void digestStockBodies(Directory dir, MessageDigest md) throws IOException {
    SegmentInfos infos = SegmentInfos.readLatestCommit(dir);
    for (SegmentCommitInfo info : infos) {
      String seg = info.info.name;
      digestBody(dir, seg + ".kdm", "Lucene90PointsFormatMeta", md);
      digestBody(dir, seg + ".kdi", "Lucene90PointsFormatIndex", md);
      digestBody(dir, seg + ".kdd", "Lucene90PointsFormatData", md);
    }
  }

  /** Returns the stock points file bodies of every segment of the last commit, concatenated. */
  static byte[] stockBodies(Directory dir) throws IOException {
    MessageDigest md = sha256();
    digestStockBodies(dir, md);
    return md.digest();
  }

  static void digestBody(Directory dir, String file, String codecName, MessageDigest md)
      throws IOException {
    try (IndexInput in = dir.openInput(file, IOContext.READONCE)) {
      long start = CodecUtil.indexHeaderLength(codecName, "");
      long end = in.length() - CodecUtil.footerLength();
      in.seek(start);
      byte[] buffer = new byte[8192];
      for (long pos = start; pos < end; ) {
        int len = (int) Math.min(buffer.length, end - pos);
        in.readBytes(buffer, 0, len);
        md.update(buffer, 0, len);
        pos += len;
      }
    }
  }

  static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  /** The T2 digest: flushed segments, then the force-merged segment, all stock. */
  static String goldenDigest(Codec codec, TwinMode twinMode) throws IOException {
    MessageDigest md = sha256();
    try (Directory dir = new ByteBuffersDirectory()) {
      writeCorpus(dir, codec, twinMode);
      digestStockBodies(dir, md);
      forceMerge(dir, codec);
      digestStockBodies(dir, md);
    }
    return HexFormat.of().formatHex(md.digest());
  }

  /** T2: the stock writer still produces the bytes recorded on the base commit. */
  public void testGoldenHash() throws IOException {
    assertEquals(GOLDEN_STOCK_BODIES_SHA256, goldenDigest(new Lucene104Codec(), TwinMode.POINTS));
  }

  /**
   * T1 (a) and (c): the per-field format with a chooser that selects no field writes the stock
   * bytes, on flush and after a force merge of 5 segments.
   */
  public void testChooserSelectsNoField() throws IOException {
    assertEquals(
        GOLDEN_STOCK_BODIES_SHA256, goldenDigest(new Lucene104SplitPointsCodec(), TwinMode.POINTS));
  }

  /**
   * T1 (b) and (c): with the twin split, the stock files hold the bytes of a baseline where the
   * twin has doc values only (same field set and field order), on flush and after a force merge.
   */
  public void testChooserSelectsTwin() throws IOException {
    Codec splitTwin =
        SplitPointsAssert.chooserCodec(
            BKDConfig.DEFAULT_MAX_POINTS_IN_LEAF_NODE, fi -> fi.name.equals(TWIN));
    String baseline = goldenDigest(new Lucene104Codec(), TwinMode.DOC_VALUES_ONLY);
    assertEquals(baseline, goldenDigest(splitTwin, TwinMode.POINTS));

    // and the twin really is split
    try (Directory dir = new ByteBuffersDirectory()) {
      writeCorpus(dir, splitTwin, TwinMode.POINTS);
      forceMerge(dir, splitTwin);
      try (DirectoryReader r = DirectoryReader.open(dir)) {
        LeafReader leaf = getOnlyLeafReader(r);
        assertTrue(leaf.getPointValues(TWIN) instanceof SplitBKDReader);
        assertTrue(leaf.getPointValues("ts") instanceof BKDReader);
        assertEquals(leaf.getPointValues("ts").size(), leaf.getPointValues(TWIN).size());
        String seg = ((SegmentReader) leaf).getSegmentName();
        for (String ext : new String[] {"kdm", "kdi", "kdd", "kdv"}) {
          assertTrue(slowFileExists(dir, seg + "_Lucene90Split_0." + ext));
        }
      }
    }
  }
}
