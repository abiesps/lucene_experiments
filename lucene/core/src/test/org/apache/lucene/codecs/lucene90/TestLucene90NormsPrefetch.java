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
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.tests.analysis.MockAnalyzer;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;

/** Tests {@link NumericDocValues#prefetchNodes} of dense Lucene90 norms. */
public class TestLucene90NormsPrefetch extends LuceneTestCase {

  public void testWholeNodesInsideFieldNeverTwice() throws IOException {
    final List<long[]> prefetches = new ArrayList<>(); // file offset, length
    try (Directory dir =
        new PrefetchRecordingDirectory(newFSDirectory(createTempDir()), prefetches)) {
      IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
      iwc.setCodec(TestUtil.getDefaultCodec());
      iwc.setUseCompoundFile(false);
      final int numDocs = atLeast(20_000);
      try (IndexWriter w = new IndexWriter(dir, iwc)) {
        for (int i = 0; i < numDocs; i++) {
          Document doc = new Document();
          // two fields, so the second field's values do not start at the beginning of .nvd
          doc.add(new TextField("a", "x ".repeat(1 + i % 7), Field.Store.NO));
          doc.add(new TextField("b", "y ".repeat(1 + (i * 31) % 50), Field.Store.NO));
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }
      try (DirectoryReader r = DirectoryReader.open(dir)) {
        final LeafReader leaf = getOnlyLeafReader(r);
        final int maxDoc = leaf.maxDoc();
        for (String field : new String[] {"a", "b"}) {
          final long nodeBytes = 1L << (6 + random().nextInt(6)); // 64 B .. 2 KiB
          prefetches.clear();
          final NumericDocValues norms = leaf.getNormValues(field);
          final long regionStart = regionStart(leaf, field, prefetches);
          prefetches.clear();
          // values read from the planning side are not moved: remember a few
          assertTrue(norms.advanceExact(0));
          final long first = norms.longValue();

          // random forward ranges, some overlapping the previous one
          int from = 0;
          long requestedEnd = -1;
          while (from < maxDoc) {
            final int to = Math.min(maxDoc, from + 1 + random().nextInt(3 * (int) nodeBytes));
            assertTrue(norms.prefetchNodes(from, to, nodeBytes));
            from = Math.max(from + 1, to - random().nextInt(to - from + 1));
          }
          assertTrue(norms.advanceExact(0));
          assertEquals("prefetch moved the iterator", first, norms.longValue());

          final long regionEnd = regionStart + maxDoc; // 1 byte per norm here
          long covered = -1;
          for (long[] p : prefetches) {
            final long start = p[0];
            final long end = p[0] + p[1];
            assertTrue(
                field + " outside the field: " + start, start >= regionStart && end <= regionEnd);
            assertTrue(
                field + " unaligned start " + start,
                start == regionStart || start % nodeBytes == 0);
            assertTrue(field + " unaligned end " + end, end == regionEnd || end % nodeBytes == 0);
            assertTrue(field + " requested twice at " + start, start >= requestedEnd);
            requestedEnd = end;
            covered = end;
          }
          assertEquals(field + " did not cover all docs", regionEnd, covered);
        }
      }
    }
  }

  /** The file offset of a field's first norm, found by prefetching doc 0 with 1-byte nodes. */
  private static long regionStart(LeafReader leaf, String field, List<long[]> prefetches)
      throws IOException {
    final NumericDocValues probe = leaf.getNormValues(field);
    prefetches.clear();
    assertTrue(probe.prefetchNodes(0, 1, 1));
    assertEquals(1, prefetches.size());
    return prefetches.get(0)[0];
  }

  public void testConstantNormsDoNotPrefetch() throws IOException {
    try (Directory dir = newDirectory()) {
      IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
      iwc.setCodec(TestUtil.getDefaultCodec());
      try (IndexWriter w = new IndexWriter(dir, iwc)) {
        for (int i = 0; i < 100; i++) {
          Document doc = new Document();
          doc.add(new TextField("c", "same length", Field.Store.NO));
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }
      try (DirectoryReader r = DirectoryReader.open(dir)) {
        // every doc has the same norm: stored as a constant in .nvm, nothing to prefetch
        assertFalse(getOnlyLeafReader(r).getNormValues("c").prefetchNodes(0, 100, 64));
      }
    }
  }

  /** Records the file offset and length of every .nvd prefetch. */
  private static final class PrefetchRecordingDirectory extends FilterDirectory {
    final List<long[]> prefetches;

    PrefetchRecordingDirectory(Directory in, List<long[]> prefetches) {
      super(in);
      this.prefetches = prefetches;
    }

    @Override
    public IndexInput openInput(String name, IOContext context) throws IOException {
      final IndexInput input = in.openInput(name, context);
      return name.endsWith(".nvd") ? new Recording(input, 0, prefetches) : input;
    }
  }

  private static final class Recording extends IndexInput {
    final IndexInput in;
    final long base;
    final List<long[]> prefetches;

    Recording(IndexInput in, long base, List<long[]> prefetches) {
      super("recording(" + in + ")");
      this.in = in;
      this.base = base;
      this.prefetches = prefetches;
    }

    @Override
    public void prefetch(long offset, long length) throws IOException {
      synchronized (prefetches) {
        prefetches.add(new long[] {base + offset, length});
      }
      in.prefetch(offset, length);
    }

    @Override
    public byte readByte() throws IOException {
      return in.readByte();
    }

    @Override
    public void readBytes(byte[] b, int offset, int len) throws IOException {
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
      return new Recording(in.slice(desc, offset, length), base + offset, prefetches);
    }

    @Override
    public Recording clone() {
      return new Recording(in.clone(), base, prefetches);
    }
  }
}
