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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.lucene.codecs.lucene104.Lucene104DualNavPostingsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104DualNavPostingsFormat.ReadMode;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.tests.analysis.MockAnalyzer;
import org.apache.lucene.tests.util.LuceneTestCase;

/** Tests the norms prefetch planner of {@link MaxScoreBulkScorer} (see {@link TopKPrefetch}). */
public class TestTopKPrefetch extends LuceneTestCase {

  private static final String[] TERMS = {"t50", "t20", "t5", "t1"};
  private static final double[] SHARE = {0.5, 0.2, 0.05, 0.01};

  @Override
  public void tearDown() throws Exception {
    TopKPrefetch.setNormsDocsAhead(0);
    TopKPrefetch.setFilter(true);
    TopKPrefetch.setNodeBytes(128 * 1024);
    Lucene104DualNavPostingsFormat.setReadMode(ReadMode.DOC);
    super.tearDown();
  }

  public void testSameTopKAndNormsPrefetchedBeforeRead() throws IOException {
    final Recorder recorder = new Recorder();
    try (Directory dir = new RecordingDirectory(newFSDirectory(createTempDir()), recorder)) {
      // many windows, so a look-ahead of a few windows leaves most of them to plan with a real
      // threshold
      final int numDocs = atLeast(300_000);
      index(dir, numDocs);
      Lucene104DualNavPostingsFormat.setReadMode(
          random().nextBoolean() ? ReadMode.NAV : ReadMode.DOC);
      try (DirectoryReader r = DirectoryReader.open(dir)) {
        final IndexSearcher searcher = new IndexSearcher(r);
        searcher.setQueryCache(null);
        boolean filteredSomething = false;
        for (int iter = 0; iter < 12; iter++) {
          final Query q = randomQuery(random());
          final int k = random().nextBoolean() ? 10 : 100;
          TopKPrefetch.setNormsDocsAhead(0);
          final Run expected = run(searcher, q, k);
          assertNull("planner ran while disabled", expected.planned);

          final int docsAhead = MaxScoreBulkScorer.INNER_WINDOW_SIZE * (2 + random().nextInt(4));
          final long nodeBytes = 1L << (8 + random().nextInt(6)); // 256 B .. 8 KiB
          TopKPrefetch.setNormsDocsAhead(docsAhead);
          TopKPrefetch.setNodeBytes(nodeBytes);

          // without the filter: same top-k, and every norm read was requested before it
          TopKPrefetch.setFilter(false);
          recorder.reset();
          final Run unfiltered = run(searcher, q, k);
          assertSameTopK(q + " unfiltered", expected, unfiltered);
          assertTrue(q + " planner did not run", unfiltered.planned[0] > 0);
          assertEquals(unfiltered.planned[0], unfiltered.planned[1]);
          for (long[] read : recorder.reads(".nvd")) {
            assertTrue(
                q
                    + " norm read at "
                    + read[0]
                    + " (seq "
                    + read[2]
                    + ") not prefetched before; "
                    + recorder.describe(),
                recorder.coveredBefore(".nvd", read[0], read[1], read[2]));
          }

          // with the filter: same top-k, and fewer windows requested when the threshold is high
          TopKPrefetch.setFilter(true);
          recorder.reset();
          final Run filtered = run(searcher, q, k);
          assertSameTopK(q + " filtered", expected, filtered);
          assertTrue(filtered.planned[1] <= filtered.planned[0]);
          if (filtered.planned[1] < filtered.planned[0]) {
            filteredSomething = true;
          }
        }
        assertTrue("the filter never rejected a window", filteredSomething);
      }
    }
  }

  private static void assertSameTopK(String msg, Run expected, Run actual) {
    assertEquals(msg, expected.top.scoreDocs.length, actual.top.scoreDocs.length);
    for (int i = 0; i < expected.top.scoreDocs.length; i++) {
      assertEquals(msg + " doc " + i, expected.top.scoreDocs[i].doc, actual.top.scoreDocs[i].doc);
      assertEquals(
          msg + " score " + i, expected.top.scoreDocs[i].score, actual.top.scoreDocs[i].score, 0f);
    }
  }

  private static Query randomQuery(Random rnd) {
    final int n = 2 + rnd.nextInt(2);
    final List<String> terms = new ArrayList<>(List.of(TERMS));
    java.util.Collections.shuffle(terms, rnd);
    BooleanQuery.Builder b = new BooleanQuery.Builder();
    for (int i = 0; i < n; i++) {
      b.add(new TermQuery(new Term("body", terms.get(i))), BooleanClause.Occur.SHOULD);
    }
    return b.build();
  }

  /** Top-k of the only segment, scored in OpenSearch-like doc-ID chunks, plus planner counters. */
  private static final class Run {
    TopDocs top;
    long[] planned; // {planned windows, eligible windows}, or null if the planner was off
  }

  private static Run run(IndexSearcher s, Query q, int k) throws IOException {
    final Weight w = s.createWeight(s.rewrite(q), ScoreMode.TOP_SCORES, 1f);
    final TopScoreDocCollector c = new TopScoreDocCollectorManager(k, null, 1).newCollector();
    final Run run = new Run();
    for (LeafReaderContext ctx : s.getIndexReader().leaves()) {
      final BulkScorer bs = w.bulkScorer(ctx);
      assertTrue("expected MaxScoreBulkScorer, got " + bs, bs instanceof MaxScoreBulkScorer);
      final LeafCollector lc = c.getLeafCollector(ctx);
      int min = 0;
      int interval = 1 << 12;
      final int max = ctx.reader().maxDoc();
      while (min < max) {
        final int newMax = (int) Math.min((long) min + interval, max);
        min = bs.score(lc, ctx.reader().getLiveDocs(), min, newMax);
        interval = Math.min(interval << 1, 1 << 20);
      }
      lc.finish();
      final MaxScoreBulkScorer ms = (MaxScoreBulkScorer) bs;
      if (TopKPrefetch.isEnabled()) {
        run.planned = new long[] {ms.plannedWindows, ms.eligibleWindows};
      } else {
        assertEquals(0, ms.plannedWindows);
      }
    }
    run.top = c.topDocs();
    return run;
  }

  /** Terms at fixed shares; tf 1 except in rare "hot" stretches; lengths vary (norms). */
  private static void index(Directory dir, int numDocs) throws IOException {
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    // the real codec: the asserting test codec wraps norms in a class that drops the prefetch hint
    final PostingsFormat dualNav = new Lucene104DualNavPostingsFormat();
    iwc.setCodec(
        new Lucene104Codec() {
          @Override
          public PostingsFormat getPostingsFormatForField(String field) {
            return dualNav;
          }
        });
    iwc.setUseCompoundFile(false);
    final Random rnd = new Random(random().nextLong());
    final int stretch = 2_000;
    final boolean[][] hot = new boolean[TERMS.length][numDocs / stretch + 1];
    for (boolean[] h : hot) {
      for (int i = 0; i < h.length; i++) {
        h[i] = rnd.nextInt(40) == 0;
      }
    }
    try (IndexWriter w = new IndexWriter(dir, iwc)) {
      final StringBuilder sb = new StringBuilder();
      for (int i = 0; i < numDocs; i++) {
        sb.setLength(0);
        for (int t = 0; t < TERMS.length; t++) {
          if (rnd.nextDouble() < SHARE[t]) {
            final int tf = hot[t][i / stretch] ? 2 + rnd.nextInt(10) : 1;
            for (int j = 0; j < tf; j++) {
              sb.append(TERMS[t]).append(' ');
            }
          }
        }
        sb.append("z ".repeat(8 + rnd.nextInt(56)));
        Document doc = new Document();
        doc.add(new TextField("body", sb.toString(), Field.Store.NO));
        w.addDocument(doc);
      }
      w.forceMerge(1);
    }
  }

  /** Records reads and prefetches of files by extension, each with a sequence number. */
  static final class Recorder {
    private long seq;
    private final List<Object[]> events = new ArrayList<>(); // ext, pos, len, seq, isPrefetch

    synchronized void reset() {
      events.clear();
    }

    synchronized void record(String file, long pos, long len, boolean prefetch) {
      events.add(new Object[] {file.substring(file.lastIndexOf('.')), pos, len, seq++, prefetch});
    }

    synchronized String describe() {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < Math.min(8, events.size()); i++) {
        Object[] e = events.get(i);
        sb.append((boolean) e[4] ? "P" : "R")
            .append(e[0])
            .append('@')
            .append(e[1])
            .append('+')
            .append(e[2])
            .append('#')
            .append(e[3])
            .append(' ');
      }
      return sb.toString();
    }

    synchronized List<long[]> reads(String ext) {
      List<long[]> out = new ArrayList<>();
      for (Object[] e : events) {
        if (e[0].equals(ext) && (boolean) e[4] == false) {
          out.add(new long[] {(long) e[1], (long) e[2], (long) e[3]});
        }
      }
      return out;
    }

    /** True if [pos, pos+len) is inside the union of prefetches recorded before seqOfRead. */
    synchronized boolean coveredBefore(String ext, long pos, long len, long seqOfRead) {
      long covered = pos;
      boolean progress = true;
      while (covered < pos + len && progress) {
        progress = false;
        for (Object[] e : events) {
          if (e[0].equals(ext) && (boolean) e[4] && (long) e[3] < seqOfRead) {
            final long start = (long) e[1];
            final long end = start + (long) e[2];
            if (start <= covered && covered < end) {
              covered = end;
              progress = true;
            }
          }
        }
      }
      return covered >= pos + len;
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
      final IndexInput input = in.openInput(name, context);
      return name.endsWith(".nvd") ? new RecordingInput(input, name, 0, recorder) : input;
    }
  }

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
