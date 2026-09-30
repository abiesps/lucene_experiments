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
package org.apache.lucene.codecs.lucene104;

import java.io.IOException;
import java.util.Random;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104DualNavPostingsFormat.ReadMode;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.tests.analysis.MockAnalyzer;
import org.apache.lucene.tests.index.BasePostingsFormatTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BitUtil;
import org.apache.lucene.util.BytesRef;

/**
 * Runs the postings format test suite on {@link Lucene104DualNavPostingsFormat} in {@link
 * ReadMode#NAV}, and checks that its stock files match {@link Lucene104PostingsFormat} and that
 * both read modes return the same postings as {@link Lucene104PostingsFormat}.
 */
public class TestLucene104DualNavPostingsFormat extends BasePostingsFormatTestCase {

  /** The read mode the base test suite runs with. */
  protected ReadMode readMode() {
    return ReadMode.NAV;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    Lucene104DualNavPostingsFormat.setReadMode(readMode());
  }

  @Override
  public void tearDown() throws Exception {
    Lucene104DualNavPostingsFormat.setReadMode(ReadMode.DOC);
    super.tearDown();
  }

  @Override
  protected Codec getCodec() {
    return TestUtil.alwaysPostingsFormat(new Lucene104DualNavPostingsFormat());
  }

  private static FieldType offsetsType() {
    FieldType ft = new FieldType(TextField.TYPE_NOT_STORED);
    ft.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
    ft.freeze();
    return ft;
  }

  /** Adds the same values to fields "kw", "txt" and "off" of each doc, with the given prefix. */
  private static void addFields(Document doc, String prefix, int i) {
    for (String term : termsFor(i)) {
      doc.add(new StringField(prefix + "kw", term, Field.Store.NO));
    }
    final String text = String.join(" ", termsFor(i)) + " " + String.join(" ", termsFor(i / 3));
    doc.add(new TextField(prefix + "txt", text, Field.Store.NO));
    doc.add(new Field(prefix + "off", text + " " + text, offsetsType()));
  }

  private static String[] termsFor(int i) {
    StringBuilder sb = new StringBuilder("all");
    if (i % 2 == 0) sb.append(" half");
    if (i % 10 == 3) sb.append(" tenth");
    if (i % 997 == 5) sb.append(" rare");
    if (i == 12345) sb.append(" single");
    return sb.toString().split(" ");
  }

  /**
   * Indexes the same docs once with Lucene104 and once with Lucene104DualNav, each in a single
   * flushed segment, and compares the stock files between header and footer: the header holds the
   * segment id and suffix, the footer a checksum over the header.
   */
  public void testStockFilesUnchanged() throws IOException {
    final int numDocs = atLeast(20_000);
    try (Directory stockDir = newFSDirectory(createTempDir());
        Directory dualDir = newFSDirectory(createTempDir())) {
      writeSingleSegment(stockDir, new Lucene104PostingsFormat(), numDocs);
      writeSingleSegment(dualDir, new Lucene104DualNavPostingsFormat(), numDocs);
      final String[][] files = {
        {Lucene104PostingsFormat.DOC_EXTENSION, Lucene104PostingsFormat.DOC_CODEC},
        {Lucene104PostingsFormat.POS_EXTENSION, Lucene104PostingsFormat.POS_CODEC},
        {Lucene104PostingsFormat.PAY_EXTENSION, Lucene104PostingsFormat.PAY_CODEC},
      };
      for (String[] f : files) {
        byte[] stock = body(stockDir, "_0_Lucene104_0." + f[0], f[1], "Lucene104_0");
        byte[] dual = body(dualDir, "_0_Lucene104DualNav_0." + f[0], f[1], "Lucene104DualNav_0");
        assertTrue("empty ." + f[0], stock.length > 0);
        int firstDiff = -1;
        for (int i = 0; i < Math.min(stock.length, dual.length); i++) {
          if (stock[i] != dual[i]) {
            firstDiff = i;
            break;
          }
        }
        assertEquals(
            "."
                + f[0]
                + " differs from Lucene104 at body offset "
                + firstDiff
                + " of "
                + stock.length,
            -1,
            firstDiff);
        assertEquals("." + f[0] + " length", stock.length, dual.length);
      }
      // .psm: four impact-size ints, then the lengths of .doc, .pos and .pay, which differ only by
      // their header lengths (the suffix is longer), so compare them against the actual files
      byte[] stockMeta =
          body(stockDir, "_0_Lucene104_0.psm", Lucene104PostingsFormat.META_CODEC, "Lucene104_0");
      byte[] dualMeta =
          body(
              dualDir,
              "_0_Lucene104DualNav_0.psm",
              Lucene104PostingsFormat.META_CODEC,
              "Lucene104DualNav_0");
      assertEquals(stockMeta.length, dualMeta.length);
      assertArrayEquals(
          ArrayUtil.copyOfSubArray(stockMeta, 0, 4 * Integer.BYTES),
          ArrayUtil.copyOfSubArray(dualMeta, 0, 4 * Integer.BYTES));
      final String[] lengthFiles = {"doc", "pos", "pay"};
      for (int i = 0; i < lengthFiles.length; i++) {
        final long recorded =
            (long) BitUtil.VH_LE_LONG.get(dualMeta, 4 * Integer.BYTES + i * Long.BYTES);
        assertEquals(
            "." + lengthFiles[i] + " length in .psm",
            dualDir.fileLength("_0_Lucene104DualNav_0." + lengthFiles[i]),
            recorded);
      }
      assertTrue(dualDir.fileLength("_0_Lucene104DualNav_0.nav") > 0);
    }
  }

  private static void writeSingleSegment(Directory dir, PostingsFormat format, int numDocs)
      throws IOException {
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(new Random(42)));
    iwc.setCodec(TestUtil.alwaysPostingsFormat(format));
    iwc.setMergePolicy(NoMergePolicy.INSTANCE);
    iwc.setUseCompoundFile(false);
    iwc.setMaxBufferedDocs(numDocs + 1);
    iwc.setRAMBufferSizeMB(IndexWriterConfig.DISABLE_AUTO_FLUSH);
    try (IndexWriter w = new IndexWriter(dir, iwc)) {
      for (int i = 0; i < numDocs; i++) {
        Document doc = new Document();
        addFields(doc, "", i);
        w.addDocument(doc);
      }
      w.commit();
    }
  }

  private static byte[] body(Directory dir, String file, String codec, String suffix)
      throws IOException {
    try (IndexInput in = dir.openInput(file, IOContext.READONCE)) {
      final int header = CodecUtil.indexHeaderLength(codec, suffix);
      final int length = Math.toIntExact(in.length() - header - CodecUtil.footerLength());
      final byte[] bytes = new byte[length];
      in.seek(header);
      in.readBytes(bytes, 0, length);
      return bytes;
    }
  }

  /** Codec that writes fields "base*" with Lucene104 and "dual*" with Lucene104DualNav. */
  private static Codec perFieldCodec() {
    final PostingsFormat base = new Lucene104PostingsFormat();
    final PostingsFormat dual = new Lucene104DualNavPostingsFormat();
    return new Lucene104Codec() {
      @Override
      public PostingsFormat getPostingsFormatForField(String field) {
        return field.startsWith("dual") ? dual : base;
      }
    };
  }

  /** Both read modes return the same docs, freqs and positions as Lucene104 on the same data. */
  public void testSameResultsAsLucene104InBothModes() throws IOException {
    try (Directory dir = newDirectory()) {
      IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
      iwc.setCodec(perFieldCodec());
      final int numDocs = atLeast(40_000);
      try (IndexWriter w = new IndexWriter(dir, iwc)) {
        for (int i = 0; i < numDocs; i++) {
          Document doc = new Document();
          addFields(doc, "base_", i);
          addFields(doc, "dual_", i);
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }
      try (DirectoryReader r = DirectoryReader.open(dir)) {
        LeafReader leaf = getOnlyLeafReader(r);
        for (ReadMode mode : ReadMode.values()) {
          Lucene104DualNavPostingsFormat.setReadMode(mode);
          for (String term : new String[] {"all", "half", "tenth", "rare", "single"}) {
            for (String suffix : new String[] {"kw", "txt", "off"}) {
              assertSamePostings(leaf, "base_" + suffix, "dual_" + suffix, term, mode);
            }
          }
        }
      }
    }
  }

  private void assertSamePostings(
      LeafReader leaf, String baseField, String dualField, String term, ReadMode mode)
      throws IOException {
    final int maxDoc = leaf.maxDoc();
    final boolean hasPositions = baseField.endsWith("kw") == false;
    for (int iter = 0; iter < 20; iter++) {
      final int flags =
          switch (random().nextInt(3)) {
            case 0 -> PostingsEnum.NONE;
            case 1 -> PostingsEnum.FREQS;
            default -> hasPositions ? PostingsEnum.ALL : PostingsEnum.FREQS;
          };
      PostingsEnum expected = postings(leaf, baseField, term, flags);
      PostingsEnum actual = postings(leaf, dualField, term, flags);
      if (expected == null) {
        assertNull(actual);
        return;
      }
      final String msg = mode + " " + dualField + ":" + term;
      int doc = -1;
      while (doc != DocIdSetIterator.NO_MORE_DOCS) {
        final int e, a;
        if (random().nextInt(4) == 0) {
          e = expected.nextDoc();
          a = actual.nextDoc();
        } else {
          final int target = doc + 1 + random().nextInt(1 + random().nextInt(maxDoc / 2 + 1));
          if (target >= maxDoc) {
            break;
          }
          e = expected.advance(target);
          a = actual.advance(target);
        }
        assertEquals(msg, e, a);
        if (e != DocIdSetIterator.NO_MORE_DOCS && flags != PostingsEnum.NONE) {
          assertEquals(msg, expected.freq(), actual.freq());
          if (flags == PostingsEnum.ALL && random().nextBoolean()) {
            for (int p = 0; p < expected.freq(); p++) {
              assertEquals(msg, expected.nextPosition(), actual.nextPosition());
              assertEquals(msg, expected.startOffset(), actual.startOffset());
              assertEquals(msg, expected.endOffset(), actual.endOffset());
            }
          }
        }
        doc = e;
      }
    }
  }

  private static PostingsEnum postings(LeafReader leaf, String field, String term, int flags)
      throws IOException {
    TermsEnum te = leaf.terms(field).iterator();
    if (te.seekExact(new BytesRef(term)) == false) {
      return null;
    }
    return te.postings(null, flags);
  }
}
