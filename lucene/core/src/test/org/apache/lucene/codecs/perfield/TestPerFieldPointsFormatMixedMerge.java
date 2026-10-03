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

import static org.apache.lucene.codecs.perfield.TestPerFieldPointsFormatByteIdentity.NUM_SEGMENTS;
import static org.apache.lucene.codecs.perfield.TestPerFieldPointsFormatByteIdentity.TWIN;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.lucene.codecs.lucene104.Lucene104SplitPointsCodec;
import org.apache.lucene.codecs.lucene90.SplitPointsAssert;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.NumericUtils;
import org.apache.lucene.util.bkd.BKDConfig;
import org.apache.lucene.util.bkd.SplitBKDReader;

/**
 * T9: segments where the twin is stock, split, or stock again after the chooser changed, merged in
 * random order, give the same points as an all-stock index.
 */
public class TestPerFieldPointsFormatMixedMerge extends LuceneTestCase {

  /** Who wrote a segment. */
  private enum Writer {
    /** plain Lucene104Codec, no per-field points */
    PLAIN,
    /** per-field points, twin split */
    SPLIT,
    /** per-field points, chooser changed back to stock */
    AFTER_CHANGE
  }

  private static Codec codec(Writer writer) {
    return switch (writer) {
      case PLAIN -> new Lucene104Codec();
      case SPLIT -> splitTwinCodec();
      case AFTER_CHANGE -> new Lucene104SplitPointsCodec();
    };
  }

  private static Codec splitTwinCodec() {
    return SplitPointsAssert.chooserCodec(
        BKDConfig.DEFAULT_MAX_POINTS_IN_LEAF_NODE, fi -> fi.name.equals(TWIN));
  }

  /** Writes segment {@code s} of the fixed corpus with its own writer session. */
  private static void writeSegment(Directory dir, int s, Codec codec) throws IOException {
    try (IndexWriter w =
        new IndexWriter(dir, TestPerFieldPointsFormatByteIdentity.flushConfig(codec))) {
      TestPerFieldPointsFormatByteIdentity.addSegmentDocs(
          w, s, TestPerFieldPointsFormatByteIdentity.TwinMode.POINTS);
      w.commit();
    }
  }

  public void testMixedMerge() throws IOException {
    Writer[] writers = new Writer[NUM_SEGMENTS];
    for (int s = 0; s < NUM_SEGMENTS; s++) {
      writers[s] = Writer.values()[random().nextInt(Writer.values().length)];
    }
    boolean mergeSplits = random().nextBoolean();
    runCase(writers, mergeSplits);
  }

  public void testAllStockInputs() throws IOException {
    Writer[] writers = new Writer[NUM_SEGMENTS];
    for (int s = 0; s < NUM_SEGMENTS; s++) {
      writers[s] = random().nextBoolean() ? Writer.PLAIN : Writer.AFTER_CHANGE;
    }
    runCase(writers, false);
  }

  public void testSplitIntoStockChosenField() throws IOException {
    Writer[] writers = new Writer[NUM_SEGMENTS];
    Arrays.fill(writers, Writer.PLAIN);
    writers[random().nextInt(NUM_SEGMENTS)] = Writer.SPLIT;
    runCase(writers, false);
  }

  private void runCase(Writer[] writers, boolean mergeSplits) throws IOException {
    boolean anySplit = Arrays.asList(writers).contains(Writer.SPLIT);
    Codec mergeCodec = mergeSplits ? splitTwinCodec() : new Lucene104SplitPointsCodec();
    try (Directory dir = new ByteBuffersDirectory();
        Directory baseline = new ByteBuffersDirectory()) {
      for (int s = 0; s < NUM_SEGMENTS; s++) {
        writeSegment(dir, s, codec(writers[s]));
        writeSegment(baseline, s, new Lucene104Codec());
      }
      TestUtil.checkIndex(dir);
      TestPerFieldPointsFormatByteIdentity.forceMerge(dir, mergeCodec);
      TestPerFieldPointsFormatByteIdentity.forceMerge(baseline, new Lucene104Codec());
      TestUtil.checkIndex(dir);

      if (anySplit == false && mergeSplits == false) {
        // every input stock and the twin stays stock: the merged stock files equal the baseline
        assertArrayEquals(
            TestPerFieldPointsFormatByteIdentity.stockBodies(baseline),
            TestPerFieldPointsFormatByteIdentity.stockBodies(dir));
      }

      try (DirectoryReader r = DirectoryReader.open(dir);
          DirectoryReader b = DirectoryReader.open(baseline)) {
        LeafReader leaf = getOnlyLeafReader(r);
        LeafReader base = getOnlyLeafReader(b);
        assertEquals(base.maxDoc(), leaf.maxDoc());

        FieldInfo twin = leaf.getFieldInfos().fieldInfo(TWIN);
        String format = twin.getAttribute(PerFieldPointsFormat.PER_FIELD_FORMAT_KEY);
        PointValues twinValues = leaf.getPointValues(TWIN);
        if (mergeSplits) {
          assertEquals(PerFieldPointsFormat.SPLIT_FORMAT_NAME, format);
          assertEquals("0", twin.getAttribute(PerFieldPointsFormat.PER_FIELD_SUFFIX_KEY));
          assertTrue(twinValues instanceof SplitBKDReader);
        } else {
          if (anySplit) {
            assertEquals(PerFieldPointsFormat.STOCK_FORMAT_NAME, format);
          } else {
            assertTrue(format == null || format.equals(PerFieldPointsFormat.STOCK_FORMAT_NAME));
          }
          assertFalse(twinValues instanceof SplitBKDReader);
        }

        for (String field : List.of("ts", TWIN, "i", "geo", "nd")) {
          PointValues expected = base.getPointValues(field);
          PointValues actual = leaf.getPointValues(field);
          assertEquals(field, expected.size(), actual.size());
          assertEquals(field, expected.getDocCount(), actual.getDocCount());
          assertArrayEquals(field, expected.getMinPackedValue(), actual.getMinPackedValue());
          assertArrayEquals(field, expected.getMaxPackedValue(), actual.getMaxPackedValue());
        }
        // the twin holds every point of the baseline twin: same docs and (doc, value) pairs
        PointValues expectedTwin = base.getPointValues(TWIN);
        long lo = NumericUtils.sortableBytesToLong(expectedTwin.getMinPackedValue(), 0);
        long hi = NumericUtils.sortableBytesToLong(expectedTwin.getMaxPackedValue(), 0);
        List<long[]> ranges = new ArrayList<>();
        ranges.add(new long[] {Long.MIN_VALUE, Long.MAX_VALUE});
        for (int i = 0; i < 10; i++) {
          long a = lo + (long) (random().nextDouble() * (hi - lo));
          long c = lo + (long) (random().nextDouble() * (hi - lo));
          ranges.add(new long[] {Math.min(a, c), Math.max(a, c)});
        }
        for (long[] range : ranges) {
          byte[] min = new byte[Long.BYTES];
          byte[] max = new byte[Long.BYTES];
          NumericUtils.longToSortableBytes(range[0], min, 0);
          NumericUtils.longToSortableBytes(range[1], max, 0);
          for (String field : List.of("ts", TWIN)) {
            assertEquals(
                SplitPointsAssert.intersect(base.getPointValues(field), min, max, true).docs,
                SplitPointsAssert.intersect(leaf.getPointValues(field), min, max, true).docs);
            assertEquals(
                SplitPointsAssert.intersect(base.getPointValues(field), min, max, false).pairs,
                SplitPointsAssert.intersect(leaf.getPointValues(field), min, max, false).pairs);
          }
        }
      }
    }
  }
}
