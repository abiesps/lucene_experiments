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
import java.util.function.LongUnaryOperator;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.search.CollectExperiments;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.RandomAccessInput;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.LongValues;
import org.apache.lucene.util.packed.DirectReader;
import org.apache.lucene.util.packed.DirectWriter;

/** Bulk reads with {@link CollectExperiments#setBulkDecode} must return the per-doc values. */
public class TestLucene90BulkDecode extends LuceneTestCase {

  private static final int[] BITS_PER_VALUE = {1, 2, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64};

  @Override
  public void tearDown() throws Exception {
    CollectExperiments.setBulkDecode(false);
    super.tearDown();
  }

  /** Every span of a DirectWriter array decodes to what DirectReader reads, for every width. */
  public void testPackedSpansMatchDirectReader() throws IOException {
    try (Directory dir = newDirectory()) {
      for (int bpv : BITS_PER_VALUE) {
        int n = TestUtil.nextInt(random(), 1, 5000);
        long[] original = new long[n];
        for (int i = 0; i < n; i++) {
          original[i] = bpv == 64 ? random().nextLong() : random().nextLong() & ((1L << bpv) - 1);
        }
        int prefix = random().nextInt(20);
        String name = "bpv" + bpv;
        try (IndexOutput out = dir.createOutput(name, IOContext.DEFAULT)) {
          for (int i = 0; i < prefix; i++) {
            out.writeByte((byte) random().nextInt());
          }
          DirectWriter writer = DirectWriter.getInstance(out, n, bpv);
          for (long v : original) {
            writer.add(v);
          }
          writer.finish();
        }
        try (IndexInput in = dir.openInput(name, IOContext.DEFAULT)) {
          RandomAccessInput slice = in.randomAccessSlice(0, in.length());
          LongValues reader = DirectReader.getInstance(slice, bpv, prefix);
          byte[] buffer = new byte[0];
          for (int iter = 0; iter < 50; iter++) {
            int first = random().nextInt(n);
            int count = TestUtil.nextInt(random(), 1, n - first);
            long[] decoded = new long[count];
            buffer = PackedSpans.decode(slice, prefix, bpv, first, count, decoded, buffer);
            for (int i = 0; i < count; i++) {
              assertEquals("bpv=" + bpv + " index=" + (first + i), original[first + i], decoded[i]);
              assertEquals(reader.get(first + i), decoded[i]);
            }
          }
        }
      }
    }
  }

  /** Value patterns that make Lucene90 pick each dense numeric encoding. */
  private static LongUnaryOperator pattern(String encoding) {
    return switch (encoding) {
      // blocks of 16,384 values with very different ranges: varying bits per value
      case "blocks" ->
          doc ->
              ((doc >>> 14) & 1) == 0
                  ? random().nextInt(16)
                  : (1L << 40) + random().nextInt(1 << 20);
      // few distinct values: table
      case "table" -> doc -> new long[] {-7, 3, 1_000_000, 42}[random().nextInt(4)];
      // multiples of 1,000 over a wide range: gcd and delta
      case "gcd" -> doc -> -5_000_000L + 1_000L * random().nextInt(1 << 20);
      // small non-negative values: plain packed ints, like ordinals
      case "plain" -> doc -> random().nextInt(1 << TestUtil.nextInt(random(), 1, 30));
      default -> throw new AssertionError(encoding);
    };
  }

  public void testDenseNumericBulkReads() throws IOException {
    for (String encoding : new String[] {"blocks", "table", "gcd", "plain"}) {
      try (Directory dir = newDirectory()) {
        int maxDoc = TestUtil.nextInt(random(), 40_000, 80_000);
        long[] expected = new long[maxDoc];
        LongUnaryOperator values = pattern(encoding);
        IndexWriterConfig config = new IndexWriterConfig().setCodec(TestUtil.getDefaultCodec());
        try (IndexWriter w = new IndexWriter(dir, config)) {
          for (int doc = 0; doc < maxDoc; doc++) {
            expected[doc] = values.applyAsLong(doc);
            Document d = new Document();
            d.add(new NumericDocValuesField("f", expected[doc]));
            w.addDocument(d);
          }
          w.forceMerge(1);
        }
        try (DirectoryReader reader = DirectoryReader.open(dir)) {
          LeafReader leaf = getOnlyLeafReader(reader);
          for (boolean bulk : new boolean[] {false, true}) {
            CollectExperiments.setBulkDecode(bulk);
            NumericDocValues dv = leaf.getNumericDocValues("f");
            int[] docs = new int[1024];
            long[] got = new long[1024];
            int doc = 0;
            while (doc < maxDoc) {
              // dense, sparse and contiguous chunks, like DocIdStream windows of different queries
              int step = random().nextInt(3) == 0 ? 1 : TestUtil.nextInt(random(), 1, 40);
              int n = 0;
              for (; n < docs.length && doc < maxDoc; n++) {
                docs[n] = doc;
                doc += TestUtil.nextInt(random(), 1, step);
              }
              dv.longValues(n, docs, got, Long.MIN_VALUE);
              for (int i = 0; i < n; i++) {
                assertEquals(
                    encoding + " bulk=" + bulk + " doc=" + docs[i], expected[docs[i]], got[i]);
              }
              // the iterator stays usable after a bulk read
              if (n > 0 && docs[n - 1] + 1 < maxDoc) {
                int next = docs[n - 1] + 1;
                assertTrue(dv.advanceExact(next));
                assertEquals(expected[next], dv.longValue());
                doc = Math.max(doc, next + 1);
              }
            }
          }
        }
      }
    }
  }

  public void testDenseSortedOrdValues() throws IOException {
    try (Directory dir = newDirectory()) {
      int maxDoc = TestUtil.nextInt(random(), 20_000, 60_000);
      int numTerms = TestUtil.nextInt(random(), 2, 3000);
      String[] terms = new String[maxDoc];
      IndexWriterConfig config = new IndexWriterConfig().setCodec(TestUtil.getDefaultCodec());
      try (IndexWriter w = new IndexWriter(dir, config)) {
        for (int doc = 0; doc < maxDoc; doc++) {
          terms[doc] = "t" + random().nextInt(numTerms);
          Document d = new Document();
          d.add(new SortedDocValuesField("f", new BytesRef(terms[doc])));
          w.addDocument(d);
        }
        w.forceMerge(1);
      }
      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        LeafReader leaf = getOnlyLeafReader(reader);
        for (boolean bulk : new boolean[] {false, true}) {
          CollectExperiments.setBulkDecode(bulk);
          SortedDocValues dv = leaf.getSortedDocValues("f");
          SortedDocValues lookup = leaf.getSortedDocValues("f");
          int[] docs = new int[1024];
          int[] ords = new int[1024];
          int doc = 0;
          while (doc < maxDoc) {
            int step = random().nextInt(3) == 0 ? 1 : TestUtil.nextInt(random(), 1, 40);
            int n = 0;
            for (; n < docs.length && doc < maxDoc; n++) {
              docs[n] = doc;
              doc += TestUtil.nextInt(random(), 1, step);
            }
            dv.ordValues(n, docs, ords);
            for (int i = 0; i < n; i++) {
              assertEquals(
                  "bulk=" + bulk + " doc=" + docs[i],
                  terms[docs[i]],
                  lookup.lookupOrd(ords[i]).utf8ToString());
            }
          }
        }
      }
    }
  }
}
