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
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.codecs.lucene104.Lucene104DualNavPostingsFormat.DualNavTermState;
import org.apache.lucene.codecs.lucene104.Lucene104DualNavPostingsFormat.ReadMode;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DisjunctionPrefetch;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.tests.analysis.MockAnalyzer;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.BytesRef;

/** Tests the .nav-planned {@link DocIdSetIterator#prefetchAhead} of Lucene104DualNav. */
public class TestLucene104DualNavPrefetch extends LuceneTestCase {

  @Override
  public void tearDown() throws Exception {
    Lucene104DualNavPostingsFormat.setReadMode(ReadMode.DOC);
    DisjunctionPrefetch.setBytesAhead(0);
    super.tearDown();
  }

  /** Exhaustive OR through BooleanScorer: same counts with and without prefetching. */
  public void testBooleanScorerPrefetch() throws IOException {
    final Recorder recorder = new Recorder();
    try (Directory dir = new RecordingDirectory(newFSDirectory(createTempDir()), recorder)) {
      IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
      iwc.setCodec(TestUtil.alwaysPostingsFormat(new Lucene104DualNavPostingsFormat()));
      iwc.setUseCompoundFile(false);
      final int numDocs = atLeast(80_000);
      try (IndexWriter w = new IndexWriter(dir, iwc)) {
        for (int i = 0; i < numDocs; i++) {
          Document doc = new Document();
          if (i % 2 == 0) doc.add(new StringField("kw", "half", Field.Store.NO));
          if (i % 7 == 1) doc.add(new StringField("kw", "seventh", Field.Store.NO));
          if (i % 301 == 3) doc.add(new StringField("kw", "sparse", Field.Store.NO));
          if (random().nextInt(20) == 0) doc.add(new StringField("kw", "random", Field.Store.NO));
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }
      Lucene104DualNavPostingsFormat.setReadMode(ReadMode.NAV);
      try (DirectoryReader r = DirectoryReader.open(dir)) {
        final IndexSearcher searcher = new IndexSearcher(r);
        searcher.setQueryCache(null);
        final String[][] queries = {
          {"half", "seventh"},
          {"half", "sparse"},
          {"seventh", "sparse", "random"},
          {"half", "seventh", "sparse", "random"}
        };
        final String docFile = docFile(dir);
        for (String[] terms : queries) {
          BooleanQuery.Builder b = new BooleanQuery.Builder();
          for (String t : terms) {
            b.add(new TermQuery(new Term("kw", t)), BooleanClause.Occur.SHOULD);
          }
          final BooleanQuery q = b.build();
          DisjunctionPrefetch.setBytesAhead(0);
          final int expected = searcher.count(q);
          final long bytesAhead = 1L << (9 + random().nextInt(9));
          DisjunctionPrefetch.setBytesAhead(bytesAhead);
          recorder.reset();
          final int actual = searcher.count(q);
          assertEquals(String.join(" OR ", terms) + " ahead=" + bytesAhead, expected, actual);
          if (terms.length > 1 && terms[0].equals("half")) {
            assertFalse("no prefetch recorded", recorder.prefetches(docFile).isEmpty());
          }
        }
      }
    }
  }

  public void testPrefetchCoversReadsAndStaysInTerm() throws IOException {
    final Recorder recorder = new Recorder();
    try (Directory dir = new RecordingDirectory(newFSDirectory(createTempDir()), recorder)) {
      IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
      iwc.setCodec(TestUtil.alwaysPostingsFormat(new Lucene104DualNavPostingsFormat()));
      iwc.setUseCompoundFile(false);
      final int numDocs = atLeast(60_000);
      try (IndexWriter w = new IndexWriter(dir, iwc)) {
        for (int i = 0; i < numDocs; i++) {
          Document doc = new Document();
          doc.add(new StringField("kw", "all", Field.Store.NO));
          if (i % 2 == 0) doc.add(new StringField("kw", "half", Field.Store.NO));
          if (i % 7 == 1) doc.add(new StringField("kw", "seventh", Field.Store.NO));
          if (i % 301 == 3) doc.add(new StringField("kw", "sparse", Field.Store.NO));
          doc.add(
              new TextField("txt", "all " + (i % 3 == 0 ? "third third" : "x"), Field.Store.NO));
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }
      Lucene104DualNavPostingsFormat.setReadMode(ReadMode.NAV);
      try (DirectoryReader r = DirectoryReader.open(dir)) {
        LeafReader leaf = getOnlyLeafReader(r);
        final String docFile = docFile(dir);
        for (String[] ft :
            new String[][] {
              {"kw", "all"}, {"kw", "half"}, {"kw", "seventh"}, {"kw", "sparse"}, {"txt", "third"}
            }) {
          for (boolean sparseAdvance : new boolean[] {false, true}) {
            final long bytesAhead = 1L << (8 + random().nextInt(10)); // 256 B .. 128 KiB
            check(leaf, ft[0], ft[1], docFile, recorder, sparseAdvance, bytesAhead);
          }
        }
      }
    }
  }

  private void check(
      LeafReader leaf,
      String field,
      String term,
      String docFile,
      Recorder recorder,
      boolean sparseAdvance,
      long bytesAhead)
      throws IOException {
    final TermsEnum te = leaf.terms(field).iterator();
    assertTrue(te.seekExact(new BytesRef(term)));
    final DualNavTermState state = (DualNavTermState) te.termState();
    final long termStart = state.base.docStartFP;
    final long termEnd = termStart + state.docLength;
    final String msg = field + ":" + term + " sparse=" + sparseAdvance + " ahead=" + bytesAhead;

    // expected docs, from a second enum that is never prefetched
    final List<Integer> expected = new ArrayList<>();
    final PostingsEnum plain = te.postings(null, PostingsEnum.NONE);
    final int step = sparseAdvance ? 1 + random().nextInt(leaf.maxDoc() / 20 + 1) : 0;
    for (int doc = plain.nextDoc();
        doc != DocIdSetIterator.NO_MORE_DOCS;
        doc = step == 0 ? plain.nextDoc() : plain.advance(doc + step)) {
      expected.add(doc);
    }

    recorder.reset();
    final PostingsEnum pe = te.postings(null, PostingsEnum.NONE);
    final List<Integer> actual = new ArrayList<>();
    int nextPrefetch = pe.prefetchAhead(0, bytesAhead);
    for (int doc = pe.nextDoc();
        doc != DocIdSetIterator.NO_MORE_DOCS;
        doc = step == 0 ? pe.nextDoc() : pe.advance(doc + step)) {
      actual.add(doc);
      final int next = step == 0 ? doc + 1 : doc + step;
      if (next >= nextPrefetch && nextPrefetch != DocIdSetIterator.NO_MORE_DOCS) {
        nextPrefetch = pe.prefetchAhead(next, bytesAhead);
      }
    }
    assertEquals(msg, expected, actual);
    if (state.docLength < 0) {
      // fewer docs than one block: no .nav entry and no recorded length, so nothing is planned
      assertEquals(
          msg, DocIdSetIterator.NO_MORE_DOCS, te.postings(null, 0).prefetchAhead(0, bytesAhead));
      return;
    }

    // every prefetch stays inside this term's postings
    for (long[] p : recorder.prefetches(docFile)) {
      assertTrue(
          msg + " prefetch " + p[0] + "+" + p[1], p[0] >= termStart && p[0] + p[1] <= termEnd);
    }
    // every .doc byte the enum read for this term was prefetched before it was read
    for (long[] read : recorder.reads(docFile)) {
      if (read[0] < termStart || read[0] >= termEnd) {
        continue; // footer/header checks and other terms
      }
      assertTrue(
          msg + " read " + read[0] + "+" + read[1] + " not prefetched before",
          recorder.coveredBefore(docFile, read[0], read[1], read[2]));
    }
  }

  private static String docFile(Directory dir) throws IOException {
    for (String f : dir.listAll()) {
      if (f.endsWith("_" + Lucene104DualNavPostingsFormat.NAME + "_0.doc")) {
        return f;
      }
    }
    throw new AssertionError("no .doc file");
  }

  /** Records reads and prefetches, each with a sequence number, per file. */
  static final class Recorder {
    private long seq;
    private final List<Object[]> events = new ArrayList<>(); // file, pos, len, seq, isPrefetch

    synchronized void reset() {
      events.clear();
    }

    synchronized void record(String file, long pos, long len, boolean prefetch) {
      events.add(new Object[] {file, pos, len, seq++, prefetch});
    }

    synchronized List<long[]> prefetches(String file) {
      return select(file, true);
    }

    synchronized List<long[]> reads(String file) {
      return select(file, false);
    }

    private List<long[]> select(String file, boolean prefetch) {
      List<long[]> out = new ArrayList<>();
      for (Object[] e : events) {
        if (e[0].equals(file) && (boolean) e[4] == prefetch) {
          out.add(new long[] {(long) e[1], (long) e[2], (long) e[3]});
        }
      }
      return out;
    }

    /**
     * True if [pos, pos+len) lies inside one prefetch recorded before sequence number seqOfRead.
     */
    synchronized boolean coveredBefore(String file, long pos, long len, long seqOfRead) {
      for (long[] p : prefetches(file)) {
        if (p[2] < seqOfRead && p[0] <= pos && pos + len <= p[0] + p[1]) {
          return true;
        }
      }
      return false;
    }
  }

  static final class RecordingDirectory extends FilterDirectory {
    final Recorder recorder;

    RecordingDirectory(Directory in, Recorder recorder) {
      super(in);
      this.recorder = recorder;
    }

    @Override
    public IndexInput openInput(String name, IOContext context) throws IOException {
      return new RecordingInput(in.openInput(name, context), name, 0, recorder);
    }
  }

  /** Records the absolute position of every read and prefetch. */
  static final class RecordingInput extends IndexInput {
    final IndexInput in;
    final String file;
    final long base;
    final Recorder recorder;

    RecordingInput(IndexInput in, String file, long base, Recorder recorder) {
      super("recording(" + in + ")");
      this.in = in;
      this.file = file;
      this.base = base;
      this.recorder = recorder;
    }

    private void read(long len) {
      recorder.record(file, base + in.getFilePointer(), len, false);
    }

    @Override
    public byte readByte() throws IOException {
      read(1);
      return in.readByte();
    }

    @Override
    public void readBytes(byte[] b, int offset, int len) throws IOException {
      read(len);
      in.readBytes(b, offset, len);
    }

    @Override
    public short readShort() throws IOException {
      read(Short.BYTES);
      return in.readShort();
    }

    @Override
    public int readInt() throws IOException {
      read(Integer.BYTES);
      return in.readInt();
    }

    @Override
    public long readLong() throws IOException {
      read(Long.BYTES);
      return in.readLong();
    }

    @Override
    public void readInts(int[] dst, int offset, int len) throws IOException {
      read((long) len * Integer.BYTES);
      in.readInts(dst, offset, len);
    }

    @Override
    public void readLongs(long[] dst, int offset, int len) throws IOException {
      read((long) len * Long.BYTES);
      in.readLongs(dst, offset, len);
    }

    @Override
    public void readFloats(float[] dst, int offset, int len) throws IOException {
      read((long) len * Float.BYTES);
      in.readFloats(dst, offset, len);
    }

    @Override
    public void prefetch(long offset, long length) throws IOException {
      recorder.record(file, base + offset, length, true);
      in.prefetch(offset, length);
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
    public RecordingInput clone() {
      return new RecordingInput(in.clone(), file, base, recorder);
    }

    @Override
    public IndexInput slice(String sliceDescription, long offset, long length) throws IOException {
      return new RecordingInput(
          in.slice(sliceDescription, offset, length), file, base + offset, recorder);
    }

    @Override
    public void close() throws IOException {
      in.close();
    }
  }
}
