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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.PointsReader;
import org.apache.lucene.codecs.lucene104.Lucene104SplitPointsCodec;
import org.apache.lucene.codecs.perfield.PerFieldPointsFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LogDocMergePolicy;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.SerialMergeScheduler;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.tests.index.BasePointsFormatTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.InfoStream;
import org.apache.lucene.util.StringHelper;
import org.apache.lucene.util.Version;
import org.apache.lucene.util.bkd.BKDConfig;
import org.apache.lucene.util.bkd.SplitBKDReader;

/**
 * T3: the split format passes the generic points format tests with every 1-D field split (N-D
 * fields fall back to stock in the same tests).
 */
public class TestLucene90SplitPointsFormat extends BasePointsFormatTestCase {

  private final Codec codec;

  public TestLucene90SplitPointsFormat() {
    int maxPointsInLeafNode =
        random().nextBoolean()
            ? BKDConfig.DEFAULT_MAX_POINTS_IN_LEAF_NODE
            : TestUtil.nextInt(random(), 50, 500);
    codec = SplitPointsAssert.splitAllCodec(maxPointsInLeafNode);
  }

  @Override
  protected Codec getCodec() {
    return codec;
  }

  public void testCodecResolvesThroughSPI() throws IOException {
    assertTrue(Codec.forName(Lucene104SplitPointsCodec.NAME) instanceof Lucene104SplitPointsCodec);
    try (Directory dir = newDirectory()) {
      try (IndexWriter w = new IndexWriter(dir, newIndexWriterConfig().setCodec(codec))) {
        Document doc = new Document();
        doc.add(new LongPoint("f", 42L));
        w.addDocument(doc);
      }
      try (DirectoryReader r = DirectoryReader.open(dir)) {
        LeafReader leaf = getOnlyLeafReader(r);
        FieldInfo fi = leaf.getFieldInfos().fieldInfo("f");
        assertEquals(
            PerFieldPointsFormat.SPLIT_FORMAT_NAME,
            fi.getAttribute(PerFieldPointsFormat.PER_FIELD_FORMAT_KEY));
        PointValues values = leaf.getPointValues("f");
        assertTrue(values instanceof SplitBKDReader);
        assertEquals(1, values.size());
      }
    }
  }

  /**
   * Index sort with docs out of order: the merge goes through the generic path, which hands the
   * split writer a non-mutable source (the heap path). Results equal the stock twin.
   */
  public void testIndexSortedMerge() throws IOException {
    int leafSize = TestUtil.nextInt(random(), 2, 300);
    Codec twin = SplitPointsAssert.twinCodec(leafSize);
    int numDocs = atLeast(2000);
    long[] pool = new long[numDocs];
    for (int i = 0; i < numDocs; i++) {
      pool[i] = random().nextInt(10_000);
    }
    try (Directory dir = newDirectory()) {
      IndexWriterConfig iwc = new IndexWriterConfig();
      iwc.setCodec(twin);
      iwc.setIndexSort(new Sort(new SortField("sort", SortField.Type.LONG)));
      iwc.setMergeScheduler(new SerialMergeScheduler());
      iwc.setMergePolicy(new LogDocMergePolicy());
      iwc.setMaxBufferedDocs(TestUtil.nextInt(random(), 50, 500));
      try (IndexWriter w = new IndexWriter(dir, iwc)) {
        for (int i = 0; i < numDocs; i++) {
          Document doc = new Document();
          doc.add(new StringField("id", Integer.toString(i), Field.Store.NO));
          doc.add(new NumericDocValuesField("sort", random().nextLong()));
          int numValues = random().nextInt(3);
          for (int j = 0; j < numValues; j++) {
            long v = pool[random().nextInt(numDocs)];
            doc.add(new LongPoint("f", v));
            doc.add(new LongPoint("f" + SplitPointsAssert.SPLIT_SUFFIX, v));
          }
          w.addDocument(doc);
          if (random().nextInt(20) == 0) {
            w.deleteDocuments(new Term("id", Integer.toString(random().nextInt(i + 1))));
          }
        }
        w.forceMerge(1);
      }
      try (DirectoryReader r = DirectoryReader.open(dir)) {
        LeafReader leaf = getOnlyLeafReader(r);
        SplitPointsAssert.assertSameAnswers(
            random(),
            leaf.getPointValues("f"),
            leaf.getPointValues("f" + SplitPointsAssert.SPLIT_SUFFIX),
            0,
            10_000,
            pool);
      }
    }
  }

  /** A direct writeField call with a non-mutable source (a stock tree) equals the source. */
  public void testWriteFieldNonMutableSource() throws IOException {
    int numDocs = atLeast(3000);
    long[] pool = new long[numDocs];
    for (int i = 0; i < numDocs; i++) {
      pool[i] = random().nextLong();
    }
    try (Directory src = newDirectory();
        Directory dst = newDirectory()) {
      IndexWriterConfig iwc = new IndexWriterConfig().setCodec(TestUtil.getDefaultCodec());
      try (IndexWriter w = new IndexWriter(src, iwc)) {
        for (int i = 0; i < numDocs; i++) {
          Document doc = new Document();
          int numValues = random().nextInt(3);
          for (int j = 0; j < numValues; j++) {
            doc.add(new LongPoint("f", pool[random().nextInt(numDocs)]));
          }
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }
      try (DirectoryReader r = DirectoryReader.open(src)) {
        LeafReader leaf = getOnlyLeafReader(r);
        PointValues stock = leaf.getPointValues("f");
        assertFalse(stock.getPointTree() instanceof org.apache.lucene.codecs.MutablePointTree);
        FieldInfos fieldInfos = leaf.getFieldInfos();
        FieldInfo fi = fieldInfos.fieldInfo("f");
        SegmentInfo si =
            new SegmentInfo(
                dst,
                Version.LATEST,
                Version.LATEST,
                "_0",
                leaf.maxDoc(),
                false,
                false,
                codec,
                Collections.emptyMap(),
                StringHelper.randomId(),
                new HashMap<>(),
                null);
        SegmentWriteState writeState =
            new SegmentWriteState(
                InfoStream.NO_OUTPUT, dst, si, fieldInfos, null, IOContext.DEFAULT);
        int leafSize = TestUtil.nextInt(random(), 1, 600);
        try (Lucene90SplitPointsWriter writer =
            new Lucene90SplitPointsWriter(writeState, leafSize)) {
          writer.writeField(
              fi,
              new PointsReader() {
                @Override
                public void checkIntegrity() {}

                @Override
                public PointValues getValues(String field) {
                  return stock;
                }

                @Override
                public void close() {}
              });
          writer.finish();
        }
        try (Lucene90SplitPointsReader reader =
            new Lucene90SplitPointsReader(
                new SegmentReadState(dst, si, fieldInfos, IOContext.DEFAULT))) {
          reader.checkIntegrity();
          long lo = Arrays.stream(pool).min().getAsLong();
          long hi = Arrays.stream(pool).max().getAsLong();
          PointValues split = reader.getValues("f");
          assertEquals(stock.size(), split.size());
          assertEquals(stock.getDocCount(), split.getDocCount());
          for (int iter = 0; iter < 20; iter++) {
            SplitPointsAssert.LongRange range =
                SplitPointsAssert.randomRange(random(), lo, hi, pool);
            byte[] min = range.minBytes();
            byte[] max = range.maxBytes();
            assertEquals(
                SplitPointsAssert.intersect(stock, min, max, true).docs,
                SplitPointsAssert.intersect(split, min, max, true).docs);
            assertEquals(
                SplitPointsAssert.intersect(stock, min, max, false).pairs,
                SplitPointsAssert.intersect(split, min, max, false).pairs);
          }
        }
      }
    }
  }
}
