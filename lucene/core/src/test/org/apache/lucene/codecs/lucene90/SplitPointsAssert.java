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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.PointsFormat;
import org.apache.lucene.codecs.PointsReader;
import org.apache.lucene.codecs.PointsWriter;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.lucene.codecs.lucene104.Lucene104SplitPointsCodec;
import org.apache.lucene.codecs.perfield.PerFieldPointsFormat;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.PointValues.IntersectVisitor;
import org.apache.lucene.index.PointValues.PointTree;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IntsRef;
import org.apache.lucene.util.NumericUtils;
import org.apache.lucene.util.bkd.BKDConfig;
import org.apache.lucene.util.bkd.BKDWriter;
import org.apache.lucene.util.bkd.SplitBKDReader;

/** Helpers that compare split points with stock points holding the same values. */
public final class SplitPointsAssert {

  private SplitPointsAssert() {}

  /** Suffix of the field names that a {@link #twinCodec} writes in the split format. */
  public static final String SPLIT_SUFFIX = "_split";

  /**
   * A codec named {@value Lucene104SplitPointsCodec#NAME} (so that it reads through SPI) that
   * writes 1-D fields whose name ends with {@link #SPLIT_SUFFIX} in the split format and every
   * other field in the stock format, both with {@code maxPointsInLeafNode} points per leaf.
   */
  public static FilterCodec twinCodec(int maxPointsInLeafNode) {
    return chooserCodec(maxPointsInLeafNode, fi -> fi.name.endsWith(SPLIT_SUFFIX) && isOneDim(fi));
  }

  /** As {@link #twinCodec} but splits every 1-D field. */
  public static FilterCodec splitAllCodec(int maxPointsInLeafNode) {
    return chooserCodec(maxPointsInLeafNode, SplitPointsAssert::isOneDim);
  }

  /** Chooser of {@link #chooserCodec}. */
  public interface Chooser {
    /** True to write {@code fi} in the split format. */
    boolean split(FieldInfo fi);
  }

  static boolean isOneDim(FieldInfo fi) {
    return fi.getPointDimensionCount() == 1 && fi.getPointIndexDimensionCount() == 1;
  }

  /** A codec that writes the fields {@code chooser} selects in the split format. */
  public static FilterCodec chooserCodec(int maxPointsInLeafNode, Chooser chooser) {
    final PointsFormat stock =
        maxPointsInLeafNode == BKDConfig.DEFAULT_MAX_POINTS_IN_LEAF_NODE
            ? PerFieldPointsFormat.STOCK
            : new StockPointsFormat(maxPointsInLeafNode);
    final PointsFormat split = new Lucene90SplitPointsFormat(maxPointsInLeafNode);
    final PointsFormat perField =
        new PerFieldPointsFormat() {
          @Override
          public PointsFormat getPointsFormatForField(FieldInfo field) {
            return chooser.split(field) ? split : stock;
          }

          @Override
          protected String getFormatName(PointsFormat format) {
            return format == stock ? STOCK_FORMAT_NAME : super.getFormatName(format);
          }
        };
    return new FilterCodec(Lucene104SplitPointsCodec.NAME, new Lucene104Codec()) {
      @Override
      public PointsFormat pointsFormat() {
        return perField;
      }
    };
  }

  /** The stock format with another number of points per leaf. */
  static final class StockPointsFormat extends PointsFormat {
    private final int maxPointsInLeafNode;

    StockPointsFormat(int maxPointsInLeafNode) {
      this.maxPointsInLeafNode = maxPointsInLeafNode;
    }

    @Override
    public PointsWriter fieldsWriter(SegmentWriteState writeState) throws IOException {
      return new Lucene90PointsWriter(
          writeState, maxPointsInLeafNode, BKDWriter.DEFAULT_MAX_MB_SORT_IN_HEAP);
    }

    @Override
    public PointsReader fieldsReader(SegmentReadState readState) throws IOException {
      return new Lucene90PointsReader(readState);
    }
  }

  /** Inclusive range on 8-byte sortable longs. */
  public record LongRange(long min, long max) {
    byte[] minBytes() {
      byte[] b = new byte[Long.BYTES];
      NumericUtils.longToSortableBytes(min, b, 0);
      return b;
    }

    byte[] maxBytes() {
      byte[] b = new byte[Long.BYTES];
      NumericUtils.longToSortableBytes(max, b, 0);
      return b;
    }
  }

  /** Points delivered by one intersect. */
  public static final class Collected {
    /** Matching docs, from every visit form. */
    public final TreeSet<Integer> docs = new TreeSet<>();

    /** In-range (doc, value) pairs with their multiplicity, from the value visit forms. */
    public final Map<String, Integer> pairs = new HashMap<>();

    /** Number of compare calls. */
    public int compares;
  }

  static String pair(int doc, byte[] value) {
    return doc + ":" + new BytesRef(value).toString();
  }

  /**
   * Intersects with a range visitor on {@code [min, max]} (unsigned bytes). With {@code allowInside
   * == false} the visitor never answers {@link Relation#CELL_INSIDE_QUERY}, so every cell that is
   * not outside is visited with values.
   */
  public static Collected intersect(PointValues values, byte[] min, byte[] max, boolean allowInside)
      throws IOException {
    Collected c = new Collected();
    final int bytes = min.length;
    values.intersect(
        new IntersectVisitor() {
          @Override
          public void visit(int docID) {
            c.docs.add(docID);
          }

          @Override
          public void visit(IntsRef ref) {
            for (int i = ref.offset; i < ref.offset + ref.length; i++) {
              c.docs.add(ref.ints[i]);
            }
          }

          @Override
          public void visit(DocIdSetIterator iterator) throws IOException {
            for (int d = iterator.nextDoc();
                d != DocIdSetIterator.NO_MORE_DOCS;
                d = iterator.nextDoc()) {
              c.docs.add(d);
            }
          }

          @Override
          public void visit(int docID, byte[] packedValue) {
            if (Arrays.compareUnsigned(packedValue, 0, bytes, min, 0, bytes) >= 0
                && Arrays.compareUnsigned(packedValue, 0, bytes, max, 0, bytes) <= 0) {
              c.docs.add(docID);
              c.pairs.merge(pair(docID, packedValue), 1, Integer::sum);
            }
          }

          @Override
          public void visit(DocIdSetIterator iterator, byte[] packedValue) throws IOException {
            for (int d = iterator.nextDoc();
                d != DocIdSetIterator.NO_MORE_DOCS;
                d = iterator.nextDoc()) {
              visit(d, packedValue);
            }
          }

          @Override
          public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
            c.compares++;
            if (Arrays.compareUnsigned(minPackedValue, 0, bytes, max, 0, bytes) > 0
                || Arrays.compareUnsigned(maxPackedValue, 0, bytes, min, 0, bytes) < 0) {
              return Relation.CELL_OUTSIDE_QUERY;
            }
            if (allowInside
                && Arrays.compareUnsigned(minPackedValue, 0, bytes, min, 0, bytes) >= 0
                && Arrays.compareUnsigned(maxPackedValue, 0, bytes, max, 0, bytes) <= 0) {
              return Relation.CELL_INSIDE_QUERY;
            }
            return Relation.CELL_CROSSES_QUERY;
          }
        });
    return c;
  }

  /** A range visitor's relation of {@code [cellMin, cellMax]} to {@code [min, max]}. */
  static Relation relate(byte[] cellMin, byte[] cellMax, byte[] min, byte[] max) {
    int bytes = min.length;
    if (Arrays.compareUnsigned(cellMin, 0, bytes, max, 0, bytes) > 0
        || Arrays.compareUnsigned(cellMax, 0, bytes, min, 0, bytes) < 0) {
      return Relation.CELL_OUTSIDE_QUERY;
    }
    if (Arrays.compareUnsigned(cellMin, 0, bytes, min, 0, bytes) >= 0
        && Arrays.compareUnsigned(cellMax, 0, bytes, max, 0, bytes) <= 0) {
      return Relation.CELL_INSIDE_QUERY;
    }
    return Relation.CELL_CROSSES_QUERY;
  }

  /** A visitor for {@link PointValues#estimatePointCount} on {@code [min, max]}. */
  static IntersectVisitor rangeEstimator(byte[] min, byte[] max) {
    return new IntersectVisitor() {
      @Override
      public void visit(int docID) {
        throw new AssertionError();
      }

      @Override
      public void visit(int docID, byte[] packedValue) {
        throw new AssertionError();
      }

      @Override
      public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
        return relate(minPackedValue, maxPackedValue, min, max);
      }
    };
  }

  /**
   * Stock's estimate algorithm on {@code tree} with each leaf's exact min and max (read from its
   * values) used as the leaf cell.
   */
  public static long tightLeafEstimate(PointTree tree, byte[] min, byte[] max) throws IOException {
    Relation r;
    boolean leaf = tree.moveToChild() == false;
    if (leaf) {
      byte[][] bounds = exactBounds(tree);
      r = relate(bounds[0], bounds[1], min, max);
    } else {
      tree.moveToParent();
      r = relate(tree.getMinPackedValue(), tree.getMaxPackedValue(), min, max);
    }
    switch (r) {
      case CELL_OUTSIDE_QUERY:
        return 0L;
      case CELL_INSIDE_QUERY:
        return tree.size();
      case CELL_CROSSES_QUERY:
      default:
        if (leaf) {
          return (tree.size() + 1) / 2;
        }
        long cost = 0;
        tree.moveToChild();
        do {
          cost += tightLeafEstimate(tree, min, max);
        } while (tree.moveToSibling());
        tree.moveToParent();
        return cost;
    }
  }

  /** The exact min and max of the values below the current node. */
  public static byte[][] exactBounds(PointTree tree) throws IOException {
    byte[][] bounds = new byte[2][];
    tree.visitDocValues(
        new IntersectVisitor() {
          @Override
          public void visit(int docID) {
            throw new AssertionError();
          }

          @Override
          public void visit(int docID, byte[] packedValue) {
            if (bounds[0] == null || Arrays.compareUnsigned(packedValue, bounds[0]) < 0) {
              bounds[0] = packedValue.clone();
            }
            if (bounds[1] == null || Arrays.compareUnsigned(packedValue, bounds[1]) > 0) {
              bounds[1] = packedValue.clone();
            }
          }

          @Override
          public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
            return Relation.CELL_CROSSES_QUERY;
          }
        });
    return bounds;
  }

  /** The (doc, value) pairs below the current node, with multiplicity. */
  public static Map<String, Integer> allPairs(PointTree tree) throws IOException {
    Map<String, Integer> pairs = new HashMap<>();
    tree.visitDocValues(
        new IntersectVisitor() {
          @Override
          public void visit(int docID) {
            throw new AssertionError();
          }

          @Override
          public void visit(int docID, byte[] packedValue) {
            pairs.merge(pair(docID, packedValue), 1, Integer::sum);
          }

          @Override
          public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
            return Relation.CELL_CROSSES_QUERY;
          }
        });
    return pairs;
  }

  /**
   * Per-range counts of a stateful visitor in the style of OpenSearch's {@code PointTreeTraversal}:
   * the ranges are sorted and disjoint, and {@code compare} advances the current range as the
   * traversal moves right.
   */
  public static long[] rangeCounts(PointValues values, long[] bounds) throws IOException {
    final int numRanges = bounds.length / 2;
    final long[] counts = new long[numRanges];
    final byte[][] mins = new byte[numRanges][];
    final byte[][] maxs = new byte[numRanges][];
    for (int i = 0; i < numRanges; i++) {
      LongRange r = new LongRange(bounds[2 * i], bounds[2 * i + 1]);
      mins[i] = r.minBytes();
      maxs[i] = r.maxBytes();
    }
    final int[] current = new int[1];
    values.intersect(
        new IntersectVisitor() {
          @Override
          public void visit(int docID) {
            counts[current[0]]++;
          }

          @Override
          public void visit(int docID, byte[] packedValue) {
            while (current[0] < numRanges
                && Arrays.compareUnsigned(packedValue, maxs[current[0]]) > 0) {
              current[0]++;
            }
            if (current[0] < numRanges
                && Arrays.compareUnsigned(packedValue, mins[current[0]]) >= 0) {
              counts[current[0]]++;
            }
          }

          @Override
          public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
            // cells arrive left to right: drop the ranges that end before this cell
            while (current[0] < numRanges
                && Arrays.compareUnsigned(minPackedValue, maxs[current[0]]) > 0) {
              current[0]++;
            }
            if (current[0] == numRanges
                || Arrays.compareUnsigned(maxPackedValue, mins[current[0]]) < 0) {
              return Relation.CELL_OUTSIDE_QUERY;
            }
            if (Arrays.compareUnsigned(minPackedValue, mins[current[0]]) >= 0
                && Arrays.compareUnsigned(maxPackedValue, maxs[current[0]]) <= 0) {
              return Relation.CELL_INSIDE_QUERY;
            }
            return Relation.CELL_CROSSES_QUERY;
          }
        });
    return counts;
  }

  /** Random sorted, disjoint ranges inside {@code [lo, hi]}. */
  public static long[] randomRanges(Random random, long lo, long hi) {
    int n = 1 + random.nextInt(6);
    long[] cuts = new long[2 * n];
    for (int i = 0; i < cuts.length; i++) {
      cuts[i] = lo + (long) (random.nextDouble() * ((double) hi - (double) lo));
    }
    Arrays.sort(cuts);
    return cuts;
  }

  /** A random range: inside the data, outside it, a single value, or everything. */
  public static LongRange randomRange(Random random, long lo, long hi, long[] someValues) {
    switch (random.nextInt(6)) {
      case 0:
        return new LongRange(Long.MIN_VALUE, Long.MAX_VALUE);
      case 1:
        return hi == Long.MAX_VALUE
            ? new LongRange(Long.MIN_VALUE, Long.MIN_VALUE)
            : new LongRange(hi + 1, Long.MAX_VALUE);
      case 2:
        {
          long v = someValues[random.nextInt(someValues.length)];
          return new LongRange(v, v);
        }
      default:
        {
          long a = lo + (long) (random.nextDouble() * ((double) hi - (double) lo));
          long b = lo + (long) (random.nextDouble() * ((double) hi - (double) lo));
          return new LongRange(Math.min(a, b), Math.max(a, b));
        }
    }
  }

  /** Asserts that two 1-D point values on 8-byte longs answer every check identically. */
  public static void assertSameAnswers(
      Random random, PointValues stock, PointValues split, long lo, long hi, long[] someValues)
      throws IOException {
    if (stock == null) {
      assertNull(split);
      return;
    }
    assertEquals(stock.getDocCount(), split.getDocCount());
    assertEquals(stock.size(), split.size());
    assertArrayEquals(stock.getMinPackedValue(), split.getMinPackedValue());
    assertArrayEquals(stock.getMaxPackedValue(), split.getMaxPackedValue());
    assertEquals(stock.getNumDimensions(), split.getNumDimensions());
    assertEquals(stock.getBytesPerDimension(), split.getBytesPerDimension());
    assertTrue(split instanceof SplitBKDReader);

    int iters = 20;
    for (int iter = 0; iter < iters; iter++) {
      LongRange range = randomRange(random, lo, hi, someValues);
      byte[] min = range.minBytes();
      byte[] max = range.maxBytes();
      Collected stockDocs = intersect(stock, min, max, true);
      Collected splitDocs = intersect(split, min, max, true);
      assertEquals("range " + range, stockDocs.docs, splitDocs.docs);
      Collected stockPairs = intersect(stock, min, max, false);
      Collected splitPairs = intersect(split, min, max, false);
      assertEquals("range " + range, stockPairs.pairs, splitPairs.pairs);
      assertEquals("range " + range, stockPairs.docs, splitPairs.docs);

      long estimate = split.estimatePointCount(rangeEstimator(min, max));
      assertTrue(estimate >= 0 && estimate <= split.size());
      assertEquals("range " + range, tightLeafEstimate(stock.getPointTree(), min, max), estimate);

      long[] ranges = randomRanges(random, lo, hi);
      assertArrayEquals(rangeCounts(stock, ranges), rangeCounts(split, ranges));
    }

    // visitDocValues at an inner node visits every point below it
    PointTree stockTree = stock.getPointTree();
    PointTree splitTree = split.getPointTree();
    int depth = random.nextInt(3);
    for (int i = 0; i < depth; i++) {
      boolean a = stockTree.moveToChild();
      boolean b = splitTree.moveToChild();
      assertEquals(a, b);
      if (a == false) {
        break;
      }
      if (random.nextBoolean()) {
        assertEquals(stockTree.moveToSibling(), splitTree.moveToSibling());
      }
    }
    assertEquals(stockTree.size(), splitTree.size());
    Map<String, Integer> stockAll = allPairs(stockTree);
    Map<String, Integer> splitAll = allPairs(splitTree);
    assertEquals(stockAll, splitAll);
    assertEquals(splitTree.size(), splitAll.values().stream().mapToLong(Integer::longValue).sum());
  }
}
