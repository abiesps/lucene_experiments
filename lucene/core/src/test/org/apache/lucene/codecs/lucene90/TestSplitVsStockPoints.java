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
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LogDocMergePolicy;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.SerialMergeScheduler;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;

/** T4: a split field answers every point query exactly like a stock twin with the same values. */
public class TestSplitVsStockPoints extends LuceneTestCase {

  private static final String STOCK = "f";
  private static final String SPLIT = "f" + SplitPointsAssert.SPLIT_SUFFIX;

  private static IndexWriterConfig config(Codec codec, boolean merges) {
    IndexWriterConfig iwc = new IndexWriterConfig();
    iwc.setCodec(codec);
    iwc.setMergeScheduler(new SerialMergeScheduler());
    iwc.setMaxBufferedDocs(IndexWriterConfig.DISABLE_AUTO_FLUSH);
    iwc.setRAMBufferSizeMB(64);
    iwc.setMergePolicy(merges ? new LogDocMergePolicy() : NoMergePolicy.INSTANCE);
    return iwc;
  }

  private static int randomLeafSize() {
    return switch (random().nextInt(4)) {
      case 0 -> 1;
      case 1 -> 2;
      default -> TestUtil.nextInt(random(), 3, 600);
    };
  }

  /** Values generator kinds. */
  private enum Kind {
    RANDOM,
    INCREASING,
    FEW_VALUES,
    ALL_EQUAL
  }

  private static long[] values(Kind kind, int n) {
    long[] values = new long[n];
    long constant = random().nextLong();
    long ts = random().nextInt(1 << 30);
    for (int i = 0; i < n; i++) {
      values[i] =
          switch (kind) {
            case RANDOM -> random().nextLong();
            case INCREASING -> ts += random().nextInt(5);
            case FEW_VALUES -> random().nextInt(7) * 1_000L;
            case ALL_EQUAL -> constant;
          };
    }
    return values;
  }

  /**
   * Indexes {@code numDocs} docs where both fields hold the same 0-3 values, in {@code numSegments}
   * segments, then optionally deletes some docs and force merges.
   */
  private void runCase(int numDocs, int numSegments, Kind kind, int leafSize, boolean deleteAll)
      throws IOException {
    Codec codec = SplitPointsAssert.twinCodec(leafSize);
    long[] pool = values(kind, Math.max(1, numDocs * 2));
    try (Directory dir = newDirectory()) {
      boolean merge = numSegments > 1 || deleteAll;
      try (IndexWriter w = new IndexWriter(dir, config(codec, merge))) {
        int docsPerSegment = Math.max(1, numDocs / numSegments);
        for (int i = 0; i < numDocs; i++) {
          Document doc = new Document();
          doc.add(new StringField("id", Integer.toString(i), Field.Store.NO));
          // every segment starts with a doc with points, so merges take the bulk paths
          int numValues = i % docsPerSegment == 0 ? 1 : random().nextInt(4);
          for (int j = 0; j < numValues; j++) {
            long v = pool[random().nextInt(pool.length)];
            doc.add(new LongPoint(STOCK, v));
            doc.add(new LongPoint(SPLIT, v));
          }
          w.addDocument(doc);
          if ((i + 1) % docsPerSegment == 0 || i == numDocs - 1) {
            if (deleteAll) {
              // a doc without points keeps the segment alive after the deletes
              w.addDocument(new Document());
            }
            w.commit();
          }
        }
        if (deleteAll) {
          for (int i = 0; i < numDocs; i++) {
            w.deleteDocuments(new Term("id", Integer.toString(i)));
          }
        } else if (merge && random().nextBoolean()) {
          for (int i = 0; i < numDocs; i++) {
            if (random().nextInt(5) == 0) {
              w.deleteDocuments(new Term("id", Integer.toString(i)));
            }
          }
        }
        if (merge) {
          w.forceMerge(1);
        }
        w.commit();
      }
      try (DirectoryReader r = DirectoryReader.open(dir)) {
        for (var ctx : r.leaves()) {
          LeafReader leaf = ctx.reader();
          if (deleteAll) {
            assertEquals(1, r.leaves().size());
            assertNotNull(leaf.getFieldInfos().fieldInfo(SPLIT));
            assertNull(leaf.getPointValues(STOCK));
            assertNull(leaf.getPointValues(SPLIT));
          }
          long lo = Arrays.stream(pool).min().getAsLong();
          long hi = Arrays.stream(pool).max().getAsLong();
          SplitPointsAssert.assertSameAnswers(
              random(), leaf.getPointValues(STOCK), leaf.getPointValues(SPLIT), lo, hi, pool);
        }
      }
    }
  }

  public void testRandom() throws IOException {
    Kind kind = Kind.values()[random().nextInt(Kind.values().length)];
    runCase(atLeast(2000), 1, kind, randomLeafSize(), false);
  }

  public void testRandomMerged() throws IOException {
    Kind kind = Kind.values()[random().nextInt(Kind.values().length)];
    runCase(atLeast(2000), TestUtil.nextInt(random(), 2, 5), kind, randomLeafSize(), false);
  }

  public void testSingleLeaf() throws IOException {
    int leafSize = TestUtil.nextInt(random(), 64, 512);
    // at most leafSize points: each doc has at most 3 values
    runCase(TestUtil.nextInt(random(), 1, leafSize / 3), 1, Kind.RANDOM, leafSize, false);
  }

  public void testAllValuesEqual() throws IOException {
    runCase(
        atLeast(1000), TestUtil.nextInt(random(), 1, 3), Kind.ALL_EQUAL, randomLeafSize(), false);
  }

  public void testLeafSizeOne() throws IOException {
    runCase(atLeast(300), TestUtil.nextInt(random(), 1, 3), Kind.INCREASING, 1, false);
  }

  public void testLeafSizeTwo() throws IOException {
    runCase(atLeast(300), TestUtil.nextInt(random(), 1, 3), Kind.FEW_VALUES, 2, false);
  }

  /** All docs with points deleted, then merged: no points in either field. */
  public void testEmptySegment() throws IOException {
    runCase(atLeast(100), TestUtil.nextInt(random(), 1, 3), Kind.RANDOM, randomLeafSize(), true);
  }
}
