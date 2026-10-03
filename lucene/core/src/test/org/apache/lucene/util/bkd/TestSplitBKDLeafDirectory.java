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
package org.apache.lucene.util.bkd;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.MutablePointTree;
import org.apache.lucene.index.PointValues.IntersectVisitor;
import org.apache.lucene.index.PointValues.PointTree;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IORunnable;
import org.apache.lucene.util.NumericUtils;

/** T7: leaf IDs, the leaf directory, the shared page cache and leaf state of split BKD trees. */
public class TestSplitBKDLeafDirectory extends LuceneTestCase {

  /** In-order leaf node IDs of the implicit tree that the reader walks. */
  private static void inOrderLeaves(int node, int numLeaves, List<Integer> out) {
    if (node >= numLeaves) {
      out.add(node);
    } else {
      inOrderLeaves(2 * node, numLeaves, out);
      inOrderLeaves(2 * node + 1, numLeaves, out);
    }
  }

  public void testLeafIDFormula() {
    for (int numLeaves = 1; numLeaves <= 5000; numLeaves++) {
      List<Integer> leaves = new ArrayList<>();
      inOrderLeaves(1, numLeaves, leaves);
      assertEquals(numLeaves, leaves.size());
      for (int i = 0; i < leaves.size(); i++) {
        assertEquals(
            "numLeaves=" + numLeaves + " node=" + leaves.get(i),
            i,
            SplitBKDReader.leafIDOf(leaves.get(i), numLeaves));
      }
    }
  }

  /** A split tree over sorted long values, with its files in a heap directory. */
  private static final class Tree implements AutoCloseable {
    final Directory dir = new ByteBuffersDirectory();
    final long[] values;
    final AtomicLong reads = new AtomicLong();
    IndexInput meta, index, data, vals;
    SplitBKDReader reader;

    Tree(long[] values, int[] docs, int maxPointsInLeafNode) throws IOException {
      this.values = values;
      int maxDoc = Arrays.stream(docs).max().orElse(0) + 1;
      BKDConfig config = new BKDConfig(1, 1, Long.BYTES, maxPointsInLeafNode);
      try (IndexOutput metaOut = dir.createOutput("m", IOContext.DEFAULT);
          IndexOutput indexOut = dir.createOutput("i", IOContext.DEFAULT);
          IndexOutput dataOut = dir.createOutput("d", IOContext.DEFAULT);
          IndexOutput valOut = dir.createOutput("v", IOContext.DEFAULT)) {
        SplitBKDWriter writer = new SplitBKDWriter(maxDoc, config, values.length);
        IORunnable finalizer =
            writer.writeField1Dim(metaOut, indexOut, dataOut, valOut, mutable(values, docs));
        assertNotNull(finalizer);
        finalizer.run();
        CodecUtil.writeFooter(indexOut);
        CodecUtil.writeFooter(dataOut);
        CodecUtil.writeFooter(valOut);
      }
      meta = dir.openInput("m", IOContext.DEFAULT);
      index = new CountingIndexInput(dir.openInput("i", IOContext.DEFAULT), reads);
      data = new CountingIndexInput(dir.openInput("d", IOContext.DEFAULT), reads);
      vals = new CountingIndexInput(dir.openInput("v", IOContext.DEFAULT), reads);
      reader = new SplitBKDReader(meta, index, data, vals);
    }

    @Override
    public void close() throws IOException {
      meta.close();
      index.close();
      data.close();
      vals.close();
      dir.close();
    }
  }

  private static MutablePointTree mutable(long[] values, int[] docs) {
    final int n = values.length;
    final byte[] bytes = new byte[n * Long.BYTES];
    for (int i = 0; i < n; i++) {
      NumericUtils.longToSortableBytes(values[i], bytes, i * Long.BYTES);
    }
    final int[] ords = new int[n];
    for (int i = 0; i < n; i++) {
      ords[i] = i;
    }
    return new MutablePointTree() {
      int[] temp;

      @Override
      public void getValue(int i, BytesRef packedValue) {
        packedValue.bytes = bytes;
        packedValue.offset = ords[i] * Long.BYTES;
        packedValue.length = Long.BYTES;
      }

      @Override
      public byte getByteAt(int i, int k) {
        return bytes[ords[i] * Long.BYTES + k];
      }

      @Override
      public int getDocID(int i) {
        return docs[ords[i]];
      }

      @Override
      public void swap(int i, int j) {
        int t = ords[i];
        ords[i] = ords[j];
        ords[j] = t;
      }

      @Override
      public void save(int i, int j) {
        if (temp == null) {
          temp = new int[n];
        }
        temp[j] = ords[i];
      }

      @Override
      public void restore(int i, int j) {
        if (temp != null) {
          System.arraycopy(temp, i, ords, i, j - i);
        }
      }

      @Override
      public long size() {
        return n;
      }

      @Override
      public void visitDocValues(IntersectVisitor visitor) throws IOException {
        byte[] scratch = new byte[Long.BYTES];
        for (int i = 0; i < n; i++) {
          System.arraycopy(bytes, ords[i] * Long.BYTES, scratch, 0, Long.BYTES);
          visitor.visit(getDocID(i), scratch);
        }
      }
    };
  }

  private static Tree randomTree(int numPoints, int maxPointsInLeafNode) throws IOException {
    long[] values = new long[numPoints];
    int[] docs = new int[numPoints];
    long v = random().nextInt(1000);
    for (int i = 0; i < numPoints; i++) {
      v += random().nextInt(3) == 0 ? 0 : random().nextInt(1 << random().nextInt(20));
      values[i] = random().nextBoolean() ? v : random().nextLong();
      docs[i] = random().nextInt(Math.max(1, numPoints));
    }
    return new Tree(values, docs, maxPointsInLeafNode);
  }

  /** The leaf values and docs, collected from the leaf's blocks. */
  private record LeafData(byte[] min, byte[] max, int count, int minDoc, int maxDoc) {}

  private static LeafData leafData(PointTree tree) throws IOException {
    byte[][] bounds = new byte[2][];
    int[] stats = {0, Integer.MAX_VALUE, -1};
    tree.visitDocValues(
        new IntersectVisitor() {
          @Override
          public void visit(int docID) {
            throw new AssertionError();
          }

          @Override
          public void visit(int docID, byte[] packedValue) {
            if (bounds[0] == null) {
              bounds[0] = packedValue.clone();
            }
            bounds[1] = packedValue.clone();
            stats[0]++;
            stats[1] = Math.min(stats[1], docID);
            stats[2] = Math.max(stats[2], docID);
          }

          @Override
          public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
            return Relation.CELL_CROSSES_QUERY;
          }
        });
    return new LeafData(bounds[0], bounds[1], stats[0], stats[1], stats[2]);
  }

  /** Walks every leaf in order and checks its state against its blocks and the directory. */
  public void testLeavesMatchDirectory() throws IOException {
    int maxPointsInLeafNode = TestUtil.nextInt(random(), 1, 8);
    int numPoints = TestUtil.nextInt(random(), 1, 3000);
    try (Tree t = randomTree(numPoints, maxPointsInLeafNode)) {
      SplitBKDReader reader = t.reader;
      int numLeaves = reader.getNumLeaves();
      assertEquals((numPoints + maxPointsInLeafNode - 1) / maxPointsInLeafNode, numLeaves);
      List<SplitBKDReader.LeafEntry> entries = new ArrayList<>();
      SplitBKDReader.SplitBKDPointTree tree =
          (SplitBKDReader.SplitBKDPointTree) reader.getPointTree();
      int ordinal = 0;
      // in-order walk
      while (tree.moveToChild()) {}
      while (true) {
        assertTrue(tree.isLeaf());
        assertEquals(ordinal, tree.leafID());
        LeafData data = leafData(tree);
        SplitBKDReader.LeafEntry entry = reader.leafEntry(ordinal);
        entries.add(entry);
        assertArrayEquals(data.min(), tree.getMinPackedValue());
        assertArrayEquals(data.max(), tree.getMaxPackedValue());
        assertEquals(data.count(), tree.size());
        assertArrayEquals(data.min(), entry.minPackedValue());
        assertArrayEquals(data.max(), entry.maxPackedValue());
        assertEquals(data.count(), entry.count());
        assertEquals(data.minDoc(), entry.minDocID());
        assertEquals(data.maxDoc(), entry.maxDocID());
        assertEquals(entry.docBlockFP(), tree.leafDocFP());
        assertEquals(entry.docBlockLength(), tree.leafDocLength());
        assertEquals(entry.valueBlockFP(), tree.leafValueFP());
        assertEquals(entry.valueBlockLength(), tree.leafValueLength());
        if (ordinal > 0) {
          SplitBKDReader.LeafEntry prev = entries.get(ordinal - 1);
          assertEquals(prev.docBlockFP() + prev.docBlockLength(), entry.docBlockFP());
          assertEquals(prev.valueBlockFP() + prev.valueBlockLength(), entry.valueBlockFP());
          assertTrue(Arrays.compareUnsigned(prev.maxPackedValue(), entry.minPackedValue()) <= 0);
        } else {
          assertEquals(reader.dataStartFP, entry.docBlockFP());
          assertEquals(reader.valDataStartFP, entry.valueBlockFP());
        }
        ordinal++;
        // next leaf in order
        boolean moved = false;
        while (moved == false) {
          if (tree.moveToSibling()) {
            while (tree.moveToChild()) {}
            moved = true;
          } else if (tree.moveToParent() == false) {
            break;
          }
        }
        if (moved == false) {
          break;
        }
      }
      assertEquals(numLeaves, ordinal);
      SplitBKDReader.LeafEntry last = entries.get(numLeaves - 1);
      assertEquals(reader.docDataEndFP, last.docBlockFP() + last.docBlockLength());
      assertEquals(reader.valDataEndFP, last.valueBlockFP() + last.valueBlockLength());
      assertEquals(numPoints, entries.stream().mapToLong(e -> e.count()).sum());
      reader.checkLeafCounts();

      // random access equals the in-order entries
      for (int i = 0; i < 100; i++) {
        int id = random().nextInt(numLeaves);
        SplitBKDReader.LeafEntry e = reader.leafEntry(id);
        SplitBKDReader.LeafEntry expected = entries.get(id);
        assertEquals(expected.count(), e.count());
        assertArrayEquals(expected.minPackedValue(), e.minPackedValue());
        assertArrayEquals(expected.maxPackedValue(), e.maxPackedValue());
        assertEquals(expected.docBlockFP(), e.docBlockFP());
        assertEquals(expected.valueBlockFP(), e.valueBlockFP());
      }
    }
  }

  public void testPageCacheSharedWithClones() throws IOException {
    // 64 leaves per page: 2 points per leaf and more than 4 pages
    try (Tree t = randomTree(TestUtil.nextInt(random(), 700, 3000), 2)) {
      SplitBKDReader.SplitBKDPointTree tree =
          (SplitBKDReader.SplitBKDPointTree) t.reader.getPointTree();
      SplitBKDReader.SplitBKDPointTree clone = (SplitBKDReader.SplitBKDPointTree) tree.clone();
      assertSame(tree.pageCache(), clone.pageCache());
      while (tree.moveToChild()) {}
      int decodes = tree.pageCache().decodes;
      assertEquals(1, decodes);
      // the clone reaches the same first leaf through the pages the original decoded
      while (clone.moveToChild()) {}
      assertEquals(0, clone.leafID());
      assertEquals(decodes, clone.pageCache().decodes);
      // more than 4 distinct pages evict the least recently used
      SplitBKDReader.PageCache cache = tree.pageCache();
      int numPages = t.reader.numPages;
      assertTrue(numPages > 4);
      for (int p = 0; p < 5; p++) {
        cache.get(p);
      }
      int before = cache.decodes;
      cache.get(4);
      cache.get(1);
      assertEquals(before, cache.decodes);
      cache.get(0); // evicted by page 4
      assertEquals(before + 1, cache.decodes);
    }
  }

  public void testCloneAtLeaf() throws IOException {
    try (Tree t =
        randomTree(TestUtil.nextInt(random(), 20, 2000), TestUtil.nextInt(random(), 1, 5))) {
      PointTree tree = t.reader.getPointTree();
      assumeTrue("needs two leaves", tree.moveToChild());
      byte[] parentMin = null, parentMax = null;
      while (true) {
        byte[] min = tree.getMinPackedValue().clone();
        byte[] max = tree.getMaxPackedValue().clone();
        if (tree.moveToChild() == false) {
          break;
        }
        parentMin = min;
        parentMax = max;
      }
      // at the leftmost leaf
      byte[] leafMin = tree.getMinPackedValue().clone();
      byte[] leafMax = tree.getMaxPackedValue().clone();
      long leafSize = tree.size();
      PointTree clone = tree.clone();
      assertArrayEquals(leafMin, clone.getMinPackedValue());
      assertArrayEquals(leafMax, clone.getMaxPackedValue());
      assertEquals(leafSize, clone.size());
      LeafData data = leafData(clone);
      assertArrayEquals(data.min(), clone.getMinPackedValue());
      assertEquals(data.count(), clone.size());
      if (tree.moveToSibling()) {
        assertArrayEquals(leafMin, clone.getMinPackedValue());
        assertArrayEquals(leafMax, clone.getMaxPackedValue());
        assertEquals(leafSize, clone.size());
      }
      // moveToParent from a leaf returns the cell bounds
      assertTrue(tree.moveToParent());
      if (parentMin != null) {
        assertArrayEquals(parentMin, tree.getMinPackedValue());
        assertArrayEquals(parentMax, tree.getMaxPackedValue());
      }
    }
  }

  public void testSingleLeafRootReadsNothing() throws IOException {
    int maxPointsInLeafNode = TestUtil.nextInt(random(), 1, 512);
    try (Tree t =
        randomTree(TestUtil.nextInt(random(), 1, maxPointsInLeafNode), maxPointsInLeafNode)) {
      assertEquals(1, t.reader.getNumLeaves());
      t.reads.set(0);
      PointTree tree = t.reader.getPointTree();
      assertArrayEquals(t.reader.getMinPackedValue(), tree.getMinPackedValue());
      assertArrayEquals(t.reader.getMaxPackedValue(), tree.getMaxPackedValue());
      assertEquals(t.reader.size(), tree.size());
      assertFalse(tree.moveToChild());
      assertFalse(tree.moveToSibling());
      assertFalse(tree.moveToParent());
      PointTree clone = tree.clone();
      assertEquals(t.reader.size(), clone.size());
      assertEquals(0, t.reads.get());
      LeafData data = leafData(tree);
      assertArrayEquals(data.min(), tree.getMinPackedValue());
      assertArrayEquals(data.max(), tree.getMaxPackedValue());
      assertTrue(t.reads.get() > 0);
    }
  }

  /** Values are visited in order with intersect on random ranges, tight bounds included. */
  public void testIntersectMatchesBruteForce() throws IOException {
    try (Tree t =
        randomTree(TestUtil.nextInt(random(), 1, 5000), TestUtil.nextInt(random(), 1, 64))) {
      for (int iter = 0; iter < 20; iter++) {
        long a = t.values[random().nextInt(t.values.length)];
        long b = random().nextBoolean() ? a : t.values[random().nextInt(t.values.length)];
        long lo = Math.min(a, b), hi = Math.max(a, b);
        long expected = Arrays.stream(t.values).filter(v -> v >= lo && v <= hi).count();
        byte[] min = new byte[Long.BYTES], max = new byte[Long.BYTES];
        NumericUtils.longToSortableBytes(lo, min, 0);
        NumericUtils.longToSortableBytes(hi, max, 0);
        long[] count = new long[1];
        t.reader.intersect(
            new IntersectVisitor() {
              @Override
              public void visit(int docID) {
                count[0]++;
              }

              @Override
              public void visit(int docID, byte[] packedValue) {
                if (Arrays.compareUnsigned(packedValue, min) >= 0
                    && Arrays.compareUnsigned(packedValue, max) <= 0) {
                  count[0]++;
                }
              }

              @Override
              public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
                if (Arrays.compareUnsigned(minPackedValue, max) > 0
                    || Arrays.compareUnsigned(maxPackedValue, min) < 0) {
                  return Relation.CELL_OUTSIDE_QUERY;
                }
                if (Arrays.compareUnsigned(minPackedValue, min) >= 0
                    && Arrays.compareUnsigned(maxPackedValue, max) <= 0) {
                  return Relation.CELL_INSIDE_QUERY;
                }
                return Relation.CELL_CROSSES_QUERY;
              }
            });
        assertEquals(expected, count[0]);
      }
    }
  }

  /** Counts every byte read through an input and its clones and slices. */
  private static final class CountingIndexInput extends IndexInput {
    private final IndexInput in;
    private final AtomicLong reads;

    CountingIndexInput(IndexInput in, AtomicLong reads) {
      super("counting(" + in + ")");
      this.in = in;
      this.reads = reads;
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
    public IndexInput slice(String sliceDescription, long offset, long length) throws IOException {
      return new CountingIndexInput(in.slice(sliceDescription, offset, length), reads);
    }

    @Override
    public IndexInput clone() {
      return new CountingIndexInput(in.clone(), reads);
    }

    @Override
    public byte readByte() throws IOException {
      reads.incrementAndGet();
      return in.readByte();
    }

    @Override
    public void readBytes(byte[] b, int offset, int len) throws IOException {
      reads.addAndGet(len);
      in.readBytes(b, offset, len);
    }
  }
}
