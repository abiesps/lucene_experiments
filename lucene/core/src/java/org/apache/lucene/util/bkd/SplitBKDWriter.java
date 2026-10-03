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
import java.util.function.IntFunction;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.MutablePointTree;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.PointValues.IntersectVisitor;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.store.ByteBuffersDataOutput;
import org.apache.lucene.store.DataOutput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.ArrayUtil.ByteArrayComparator;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.FixedLengthBytesRefArray;
import org.apache.lucene.util.IORunnable;
import org.apache.lucene.util.LongValues;
import org.apache.lucene.util.PriorityQueue;
import org.apache.lucene.util.bkd.BKDUtil.ByteArrayPredicate;
import org.apache.lucene.util.packed.PackedInts;
import org.apache.lucene.util.packed.PackedLongValues;

/**
 * Writes a one-dimensional block KD-tree whose leaves are split in two streams: the doc IDs of each
 * leaf in the data file ({@code .kdd}) and its values in the values file ({@code .kdv}). The index
 * file ({@code .kdi}) holds the inner nodes, encoded exactly as {@link BKDWriter} encodes them,
 * followed by a leaf directory with the tight bounds, the doc count, the doc ID range and the block
 * lengths of every leaf. A reader can then decide how each leaf relates to a query from the index
 * file alone.
 *
 * <p>This is a fork of the one-dimensional path of {@link BKDWriter} (flush through {@link
 * MutablePointTree} and the merge sort of already sorted segments). The stock writer is not
 * changed. Only {@code numDims == numIndexDims == 1} is supported.
 *
 * @lucene.experimental
 */
public class SplitBKDWriter {

  /** Codec name of the per-field header in the metadata file. */
  public static final String CODEC_NAME = "SplitBKD";

  /** First version. */
  public static final int VERSION_START = 0;

  /** Current version. */
  public static final int VERSION_CURRENT = VERSION_START;

  /** log2 of the number of leaves per directory page. */
  public static final int PAGE_SHIFT = 6;

  /** Value of the {@code hasLeafDirectory} metadata byte. */
  static final byte HAS_LEAF_DIRECTORY = 1;

  /** {@code docEncoding} 0: {@link DocIdsWriter}, doc IDs in value order. */
  static final byte DOC_ENCODING_DOC_IDS_WRITER = 0;

  /** Directory entry flag: the leaf holds exactly {@code maxPointsInLeafNode} points. */
  static final int FLAG_FULL_LEAF = 1;

  /** The {@link BKDWriter} version whose doc ID and value block encodings this format uses. */
  static final int BLOCK_ENCODING_VERSION = BKDWriter.VERSION_CURRENT;

  private final BKDConfig config;
  private final ByteArrayPredicate equalsPredicate;
  private final ByteArrayComparator commonPrefixComparator;
  private final FixedBitSet docsSeen;
  private final byte[] scratch;
  private final BytesRef scratchBytesRef1 = new BytesRef();
  private final int[] commonPrefixLengths;
  private final byte[] minPackedValue;
  private final byte[] maxPackedValue;
  private final long totalPointCount;
  private final int maxDoc;
  private final DocIdsWriter docIdsWriter;
  private long pointCount;
  private boolean finished;

  /**
   * Creates a writer for one field.
   *
   * @param maxDoc number of docs in the segment
   * @param config tree configuration, must have one dimension
   * @param totalPointCount upper bound of the number of points that will be added
   */
  public SplitBKDWriter(int maxDoc, BKDConfig config, long totalPointCount) {
    if (config.numDims() != 1 || config.numIndexDims() != 1) {
      throw new IllegalArgumentException(
          "split points support exactly 1 dimension, got numDims="
              + config.numDims()
              + " numIndexDims="
              + config.numIndexDims());
    }
    if (totalPointCount < 0) {
      throw new IllegalArgumentException(
          "totalPointCount must be >=0 (got: " + totalPointCount + ")");
    }
    this.config = config;
    this.maxDoc = maxDoc;
    this.totalPointCount = totalPointCount;
    this.equalsPredicate = BKDUtil.getEqualsPredicate(config.bytesPerDim());
    this.commonPrefixComparator = BKDUtil.getPrefixLengthComparator(config.bytesPerDim());
    this.docsSeen = new FixedBitSet(maxDoc);
    this.scratch = new byte[config.packedBytesLength()];
    this.commonPrefixLengths = new int[config.numDims()];
    this.minPackedValue = new byte[config.packedIndexBytesLength()];
    this.maxPackedValue = new byte[config.packedIndexBytesLength()];
    this.docIdsWriter = new DocIdsWriter(config.maxPointsInLeafNode(), BLOCK_ENCODING_VERSION);
  }

  /**
   * Writes a field from a {@link MutablePointTree}: sorts it, then writes the leaves. Returns the
   * finalizer that writes the metadata and the index, or {@code null} if there are no points.
   */
  public IORunnable writeField1Dim(
      IndexOutput metaOut,
      IndexOutput indexOut,
      IndexOutput dataOut,
      IndexOutput valOut,
      MutablePointTree reader)
      throws IOException {
    MutablePointTreeReaderUtils.sort(config, maxDoc, reader, 0, Math.toIntExact(reader.size()));

    final OneDimensionWriter oneDimWriter =
        new OneDimensionWriter(metaOut, indexOut, dataOut, valOut);

    reader.visitDocValues(
        new IntersectVisitor() {

          @Override
          public void visit(int docID, byte[] packedValue) throws IOException {
            oneDimWriter.add(packedValue, docID);
          }

          @Override
          public void visit(int docID) {
            throw new IllegalStateException();
          }

          @Override
          public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
            return Relation.CELL_CROSSES_QUERY;
          }
        });

    return oneDimWriter.finish();
  }

  /**
   * Merges already sorted incoming {@link PointValues} with one merge sort. Any one-dimensional
   * {@link PointValues} source works. Returns {@code null} if all documents with points were
   * deleted.
   */
  public IORunnable merge(
      IndexOutput metaOut,
      IndexOutput indexOut,
      IndexOutput dataOut,
      IndexOutput valOut,
      List<MergeState.DocMap> docMaps,
      List<PointValues> readers)
      throws IOException {
    assert docMaps == null || readers.size() == docMaps.size();

    MergeQueue queue = new MergeQueue(config.bytesPerDim(), readers.size());

    for (int i = 0; i < readers.size(); i++) {
      PointValues pointValues = readers.get(i);
      assert pointValues.getNumDimensions() == config.numDims()
          && pointValues.getBytesPerDimension() == config.bytesPerDim()
          && pointValues.getNumIndexDimensions() == config.numIndexDims();
      MergeState.DocMap docMap = docMaps == null ? null : docMaps.get(i);
      MergeReader reader = new MergeReader(pointValues, docMap);
      if (reader.next()) {
        queue.add(reader);
      }
    }

    OneDimensionWriter oneDimWriter = new OneDimensionWriter(metaOut, indexOut, dataOut, valOut);

    while (queue.size() != 0) {
      MergeReader reader = queue.top();
      oneDimWriter.add(reader.packedValue, reader.docID);
      if (reader.next()) {
        queue.updateTop();
      } else {
        // This segment was exhausted
        queue.pop();
      }
    }

    return oneDimWriter.finish();
  }

  private static class MergeReader {
    private final PointValues.PointTree pointTree;
    private final int packedBytesLength;
    private final MergeState.DocMap docMap;
    private final MergeIntersectsVisitor mergeIntersectsVisitor;

    /** Which doc in this block we are up to */
    private int docBlockUpto;

    /** Current doc ID */
    int docID;

    /** Current packed value */
    final byte[] packedValue;

    MergeReader(PointValues pointValues, MergeState.DocMap docMap) throws IOException {
      this.packedBytesLength = pointValues.getBytesPerDimension() * pointValues.getNumDimensions();
      this.pointTree = pointValues.getPointTree();
      this.mergeIntersectsVisitor = new MergeIntersectsVisitor(packedBytesLength);
      // move to first child of the tree and collect docs
      while (pointTree.moveToChild()) {}
      pointTree.visitDocValues(mergeIntersectsVisitor);
      this.docMap = docMap;
      this.packedValue = new byte[packedBytesLength];
    }

    boolean next() throws IOException {
      while (true) {
        if (docBlockUpto == mergeIntersectsVisitor.docsInBlock) {
          if (collectNextLeaf() == false) {
            assert mergeIntersectsVisitor.docsInBlock == 0;
            return false;
          }
          assert mergeIntersectsVisitor.docsInBlock > 0;
          docBlockUpto = 0;
        }

        final int index = docBlockUpto++;
        int oldDocID = mergeIntersectsVisitor.docIDs[index];

        int mappedDocID = docMap == null ? oldDocID : docMap.get(oldDocID);

        if (mappedDocID != -1) {
          // Not deleted!
          docID = mappedDocID;
          System.arraycopy(
              mergeIntersectsVisitor.packedValues,
              index * packedBytesLength,
              packedValue,
              0,
              packedBytesLength);
          return true;
        }
      }
    }

    private boolean collectNextLeaf() throws IOException {
      assert pointTree.moveToChild() == false;
      mergeIntersectsVisitor.reset();
      do {
        if (pointTree.moveToSibling()) {
          // move to first child of this node and collect docs
          while (pointTree.moveToChild()) {}
          pointTree.visitDocValues(mergeIntersectsVisitor);
          return true;
        }
      } while (pointTree.moveToParent());
      return false;
    }
  }

  private static class MergeIntersectsVisitor implements IntersectVisitor {

    int docsInBlock = 0;
    byte[] packedValues;
    int[] docIDs;
    private final int packedBytesLength;

    MergeIntersectsVisitor(int packedBytesLength) {
      this.docIDs = new int[0];
      this.packedValues = new byte[0];
      this.packedBytesLength = packedBytesLength;
    }

    void reset() {
      docsInBlock = 0;
    }

    @Override
    public void grow(int count) {
      ensureCapacity(docsInBlock + count);
    }

    private void ensureCapacity(int count) {
      if (docIDs.length < count) {
        docIDs = ArrayUtil.grow(docIDs, count);
        int packedValuesSize = Math.toIntExact(docIDs.length * (long) packedBytesLength);
        if (packedValuesSize > ArrayUtil.MAX_ARRAY_LENGTH) {
          throw new IllegalStateException(
              "array length must be <= to "
                  + ArrayUtil.MAX_ARRAY_LENGTH
                  + " but was: "
                  + packedValuesSize);
        }
        packedValues = ArrayUtil.growExact(packedValues, packedValuesSize);
      }
    }

    @Override
    public void visit(int docID) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void visit(int docID, byte[] packedValue) {
      // a source that does not call grow() before visiting still works
      ensureCapacity(docsInBlock + 1);
      System.arraycopy(
          packedValue, 0, packedValues, docsInBlock * packedBytesLength, packedBytesLength);
      docIDs[docsInBlock++] = docID;
    }

    @Override
    public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
      return Relation.CELL_CROSSES_QUERY;
    }
  }

  private static class MergeQueue extends PriorityQueue<MergeReader> {
    private final ArrayUtil.ByteArrayComparator comparator;

    MergeQueue(int bytesPerDim, int maxSize) {
      super(maxSize);
      this.comparator = ArrayUtil.getUnsignedComparator(bytesPerDim);
    }

    @Override
    public boolean lessThan(MergeReader a, MergeReader b) {
      assert a != b;
      int cmp = comparator.compare(a.packedValue, 0, b.packedValue, 0);
      if (cmp < 0) {
        return true;
      } else if (cmp > 0) {
        return false;
      }
      // Tie break by sorting smaller docIDs earlier:
      return a.docID < b.docID;
    }
  }

  /** flat representation of a kd-tree, as in {@link BKDWriter} */
  private interface LeafNodes {
    int numLeaves();

    long getLeafLP(int index);

    BytesRef getSplitValue(int index);

    int getSplitDimension(int index);
  }

  private class OneDimensionWriter {

    final IndexOutput metaOut, indexOut, dataOut, valOut;
    final long dataStartFP;
    final long valDataStartFP;
    private final PackedLongValues.Builder leafBlockFPs =
        PackedLongValues.monotonicBuilder(PackedInts.COMPACT);
    final FixedLengthBytesRefArray leafBlockStartValues =
        new FixedLengthBytesRefArray(config.packedIndexBytesLength());
    final byte[] leafValues = new byte[config.maxPointsInLeafNode() * config.packedBytesLength()];
    final int[] leafDocs = new int[config.maxPointsInLeafNode()];
    private long valueCount;
    private int leafCount;
    private int leafCardinality;

    // leaf directory, built in memory and written after the inner nodes
    private final ByteBuffersDataOutput directory = ByteBuffersDataOutput.newResettableInstance();
    private long[] pageStarts = new long[8];
    private int numLeaves;
    private final byte[] previousLeafMax = new byte[config.bytesPerDim()];
    private long docDataEndFP;
    private long valDataEndFP;

    // for asserts
    final byte[] lastPackedValue;
    private int lastDocID;

    OneDimensionWriter(
        IndexOutput metaOut, IndexOutput indexOut, IndexOutput dataOut, IndexOutput valOut) {
      if (pointCount != 0) {
        throw new IllegalStateException("cannot mix add and merge");
      }
      if (finished) {
        throw new IllegalStateException("already finished");
      }
      finished = true;
      this.metaOut = metaOut;
      this.indexOut = indexOut;
      this.dataOut = dataOut;
      this.valOut = valOut;
      this.dataStartFP = dataOut.getFilePointer();
      this.valDataStartFP = valOut.getFilePointer();
      lastPackedValue = new byte[config.packedBytesLength()];
    }

    void add(byte[] packedValue, int docID) throws IOException {
      assert valueInOrder(valueCount + leafCount, lastPackedValue, packedValue, docID, lastDocID);

      if (leafCount == 0
          || equalsPredicate.test(
                  leafValues, (leafCount - 1) * config.bytesPerDim(), packedValue, 0)
              == false) {
        leafCardinality++;
      }
      System.arraycopy(
          packedValue,
          0,
          leafValues,
          leafCount * config.packedBytesLength(),
          config.packedBytesLength());
      leafDocs[leafCount] = docID;
      docsSeen.set(docID);
      leafCount++;

      if (valueCount + leafCount > totalPointCount) {
        throw new IllegalStateException(
            "totalPointCount="
                + totalPointCount
                + " was passed when we were created, but we just hit "
                + (valueCount + leafCount)
                + " values");
      }

      if (leafCount == config.maxPointsInLeafNode()) {
        writeLeafBlock(leafCardinality);
        leafCardinality = 0;
        leafCount = 0;
      }

      assert (lastDocID = docID) >= 0; // only assign when asserts are enabled
    }

    IORunnable finish() throws IOException {
      if (leafCount > 0) {
        writeLeafBlock(leafCardinality);
        leafCardinality = 0;
        leafCount = 0;
      }

      if (valueCount == 0) {
        return null;
      }

      pointCount = valueCount;
      docDataEndFP = dataOut.getFilePointer();
      valDataEndFP = valOut.getFilePointer();

      scratchBytesRef1.length = config.packedIndexBytesLength();
      scratchBytesRef1.offset = 0;
      assert leafBlockStartValues.size() + 1 == leafBlockFPs.size();
      final LongValues leafFPLongValues = leafBlockFPs.build();
      LeafNodes leafNodes =
          new LeafNodes() {
            @Override
            public long getLeafLP(int index) {
              return leafFPLongValues.get(index);
            }

            @Override
            public BytesRef getSplitValue(int index) {
              return leafBlockStartValues.get(scratchBytesRef1, index);
            }

            @Override
            public int getSplitDimension(int index) {
              return 0;
            }

            @Override
            public int numLeaves() {
              return Math.toIntExact(leafBlockFPs.size());
            }
          };
      return () -> writeIndex(this, leafNodes);
    }

    private void writeLeafBlock(int leafCardinality) throws IOException {
      assert leafCount != 0;
      if (valueCount == 0) {
        System.arraycopy(leafValues, 0, minPackedValue, 0, config.packedIndexBytesLength());
      }
      System.arraycopy(
          leafValues,
          (leafCount - 1) * config.packedBytesLength(),
          maxPackedValue,
          0,
          config.packedIndexBytesLength());

      valueCount += leafCount;

      if (leafBlockFPs.size() > 0) {
        // Save the first (minimum) value in each leaf block except the first, to build the split
        // value index in the end:
        scratchBytesRef1.bytes = leafValues;
        scratchBytesRef1.offset = 0;
        scratchBytesRef1.length = config.packedIndexBytesLength();
        leafBlockStartValues.append(scratchBytesRef1);
      }
      final long docFP = dataOut.getFilePointer();
      leafBlockFPs.add(docFP);
      checkMaxLeafNodeCount(Math.toIntExact(leafBlockFPs.size()));

      // .kdd: count and doc IDs, in value order
      dataOut.writeVInt(leafCount);
      docIdsWriter.writeDocIds(leafDocs, 0, leafCount, dataOut);
      final long docBlockLen = dataOut.getFilePointer() - docFP;

      // .kdv: the stock value block
      final long valFP = valOut.getFilePointer();
      commonPrefixLengths[0] =
          commonPrefixComparator.compare(
              leafValues, 0, leafValues, (leafCount - 1) * config.packedBytesLength());
      writeCommonPrefixes(valOut, commonPrefixLengths, leafValues);

      scratchBytesRef1.length = config.packedBytesLength();
      scratchBytesRef1.bytes = leafValues;

      final IntFunction<BytesRef> packedValues =
          i -> {
            scratchBytesRef1.offset = config.packedBytesLength() * i;
            return scratchBytesRef1;
          };
      writeLeafBlockPackedValues(
          valOut, commonPrefixLengths, leafCount, 0, packedValues, leafCardinality);
      final long valBlockLen = valOut.getFilePointer() - valFP;

      writeDirectoryEntry(docFP, docBlockLen, valFP, valBlockLen);
    }

    private void writeDirectoryEntry(long docFP, long docBlockLen, long valFP, long valBlockLen)
        throws IOException {
      final int bytesPerDim = config.bytesPerDim();
      final int entry = numLeaves & ((1 << PAGE_SHIFT) - 1);
      if (entry == 0) {
        final int page = numLeaves >>> PAGE_SHIFT;
        pageStarts = ArrayUtil.grow(pageStarts, page + 1);
        pageStarts[page] = directory.size();
        directory.writeVLong(docFP);
        directory.writeVLong(valFP);
      }
      if (leafCount == config.maxPointsInLeafNode()) {
        directory.writeByte((byte) FLAG_FULL_LEAF);
      } else {
        directory.writeByte((byte) 0);
        directory.writeVInt(leafCount);
      }
      final int maxOffset = (leafCount - 1) * config.packedBytesLength();
      // tight min, prefix-coded against the previous entry's max in the page
      int minPrefix =
          entry == 0 ? 0 : commonPrefixComparator.compare(leafValues, 0, previousLeafMax, 0);
      directory.writeByte((byte) minPrefix);
      directory.writeBytes(leafValues, minPrefix, bytesPerDim - minPrefix);
      // tight max, prefix-coded against this entry's min
      int maxPrefix = commonPrefixComparator.compare(leafValues, maxOffset, leafValues, 0);
      directory.writeByte((byte) maxPrefix);
      directory.writeBytes(leafValues, maxOffset + maxPrefix, bytesPerDim - maxPrefix);
      System.arraycopy(leafValues, maxOffset, previousLeafMax, 0, bytesPerDim);

      int minDoc = leafDocs[0];
      int maxDoc = leafDocs[0];
      for (int i = 1; i < leafCount; i++) {
        minDoc = Math.min(minDoc, leafDocs[i]);
        maxDoc = Math.max(maxDoc, leafDocs[i]);
      }
      directory.writeVInt(minDoc);
      directory.writeVInt(maxDoc - minDoc);
      directory.writeVInt(Math.toIntExact(docBlockLen));
      directory.writeVInt(Math.toIntExact(valBlockLen));
      numLeaves++;
    }
  }

  private void writeIndex(OneDimensionWriter w, LeafNodes leafNodes) throws IOException {
    byte[] packedIndex = packIndex(leafNodes);
    final int numLeaves = leafNodes.numLeaves();
    assert numLeaves == w.numLeaves;
    final int numPages = (numLeaves + (1 << PAGE_SHIFT) - 1) >>> PAGE_SHIFT;
    final long indexStartFP = w.indexOut.getFilePointer();
    final long directoryStartFP = indexStartFP + packedIndex.length;
    final long pagesStartFP = directoryStartFP + (long) Long.BYTES * numPages;
    final long directoryEndFP = pagesStartFP + w.directory.size();

    IndexOutput metaOut = w.metaOut;
    CodecUtil.writeHeader(metaOut, CODEC_NAME, VERSION_CURRENT);
    metaOut.writeVInt(config.numDims());
    metaOut.writeVInt(config.numIndexDims());
    metaOut.writeVInt(config.maxPointsInLeafNode());
    metaOut.writeVInt(config.bytesPerDim());
    metaOut.writeVInt(numLeaves);
    metaOut.writeBytes(minPackedValue, 0, config.packedIndexBytesLength());
    metaOut.writeBytes(maxPackedValue, 0, config.packedIndexBytesLength());
    metaOut.writeVLong(pointCount);
    metaOut.writeVInt(docsSeen.cardinality());
    metaOut.writeVInt(packedIndex.length);
    metaOut.writeLong(w.dataStartFP);
    metaOut.writeLong(indexStartFP);
    metaOut.writeLong(w.valDataStartFP);
    metaOut.writeByte(HAS_LEAF_DIRECTORY);
    metaOut.writeByte(DOC_ENCODING_DOC_IDS_WRITER);
    metaOut.writeLong(directoryStartFP);
    metaOut.writeLong(directoryEndFP);
    metaOut.writeLong(w.docDataEndFP);
    metaOut.writeLong(w.valDataEndFP);
    metaOut.writeVInt(PAGE_SHIFT);
    metaOut.writeVInt(numPages);

    w.indexOut.writeBytes(packedIndex, 0, packedIndex.length);
    assert w.indexOut.getFilePointer() == directoryStartFP;
    for (int page = 0; page < numPages; page++) {
      w.indexOut.writeLong(pagesStartFP + w.pageStarts[page]);
    }
    w.directory.copyTo(w.indexOut);
    assert w.indexOut.getFilePointer() == directoryEndFP;
  }

  private int getNumLeftLeafNodes(int numLeaves) {
    assert numLeaves > 1 : "getNumLeftLeaveNodes() called with " + numLeaves;
    // return the level that can be filled with this number of leaves
    int lastFullLevel = 31 - Integer.numberOfLeadingZeros(numLeaves);
    // how many leaf nodes are in the full level
    int leavesFullLevel = 1 << lastFullLevel;
    // half of the leaf nodes from the full level goes to the left
    int numLeftLeafNodes = leavesFullLevel / 2;
    // leaf nodes that do not fit in the full level
    int unbalancedLeafNodes = numLeaves - leavesFullLevel;
    // distribute unbalanced leaf nodes
    numLeftLeafNodes += Math.min(unbalancedLeafNodes, numLeftLeafNodes);
    // we should always place unbalanced leaf nodes on the left
    assert numLeftLeafNodes >= numLeaves - numLeftLeafNodes
        && numLeftLeafNodes <= 2L * (numLeaves - numLeftLeafNodes);
    return numLeftLeafNodes;
  }

  private void checkMaxLeafNodeCount(int numLeaves) {
    if (config.bytesPerDim() * (long) numLeaves > ArrayUtil.MAX_ARRAY_LENGTH) {
      throw new IllegalStateException(
          "too many nodes; increase config.maxPointsInLeafNode() (currently "
              + config.maxPointsInLeafNode()
              + ") and reindex");
    }
  }

  /** Packs the inner nodes exactly as {@link BKDWriter} does. */
  private byte[] packIndex(LeafNodes leafNodes) throws IOException {
    ByteBuffersDataOutput writeBuffer = ByteBuffersDataOutput.newResettableInstance();
    List<byte[]> blocks = new ArrayList<>();
    byte[] lastSplitValues = new byte[config.bytesPerDim() * config.numIndexDims()];
    int totalSize =
        recursePackIndex(
            writeBuffer,
            leafNodes,
            0L,
            blocks,
            lastSplitValues,
            new boolean[config.numIndexDims()],
            false,
            0,
            leafNodes.numLeaves());

    byte[] index = new byte[totalSize];
    int upto = 0;
    for (byte[] block : blocks) {
      System.arraycopy(block, 0, index, upto, block.length);
      upto += block.length;
    }
    assert upto == totalSize;
    return index;
  }

  private int appendBlock(ByteBuffersDataOutput writeBuffer, List<byte[]> blocks) {
    byte[] block = writeBuffer.toArrayCopy();
    blocks.add(block);
    writeBuffer.reset();
    return block.length;
  }

  private int recursePackIndex(
      ByteBuffersDataOutput writeBuffer,
      LeafNodes leafNodes,
      long minBlockFP,
      List<byte[]> blocks,
      byte[] lastSplitValues,
      boolean[] negativeDeltas,
      boolean isLeft,
      int leavesOffset,
      int numLeaves)
      throws IOException {
    if (numLeaves == 1) {
      if (isLeft) {
        assert leafNodes.getLeafLP(leavesOffset) - minBlockFP == 0;
        return 0;
      } else {
        long delta = leafNodes.getLeafLP(leavesOffset) - minBlockFP;
        assert leafNodes.numLeaves() == numLeaves || delta > 0
            : "expected delta > 0; got numLeaves =" + numLeaves + " and delta=" + delta;
        writeBuffer.writeVLong(delta);
        return appendBlock(writeBuffer, blocks);
      }
    } else {
      long leftBlockFP;
      if (isLeft) {
        // The left tree's left most leaf block FP is always the minimal FP:
        assert leafNodes.getLeafLP(leavesOffset) == minBlockFP;
        leftBlockFP = minBlockFP;
      } else {
        leftBlockFP = leafNodes.getLeafLP(leavesOffset);
        long delta = leftBlockFP - minBlockFP;
        assert leafNodes.numLeaves() == numLeaves || delta > 0
            : "expected delta > 0; got numLeaves =" + numLeaves + " and delta=" + delta;
        writeBuffer.writeVLong(delta);
      }

      int numLeftLeafNodes = getNumLeftLeafNodes(numLeaves);
      final int rightOffset = leavesOffset + numLeftLeafNodes;
      final int splitOffset = rightOffset - 1;

      int splitDim = leafNodes.getSplitDimension(splitOffset);
      BytesRef splitValue = leafNodes.getSplitValue(splitOffset);
      int address = splitValue.offset;

      // find common prefix with last split value in this dim:
      int prefix =
          commonPrefixComparator.compare(
              splitValue.bytes, address, lastSplitValues, splitDim * config.bytesPerDim());

      int firstDiffByteDelta;
      if (prefix < config.bytesPerDim()) {
        firstDiffByteDelta =
            (splitValue.bytes[address + prefix] & 0xFF)
                - (lastSplitValues[splitDim * config.bytesPerDim() + prefix] & 0xFF);
        if (negativeDeltas[splitDim]) {
          firstDiffByteDelta = -firstDiffByteDelta;
        }
        assert firstDiffByteDelta > 0;
      } else {
        firstDiffByteDelta = 0;
      }

      // pack the prefix, splitDim and delta first diff byte into a single vInt:
      int code =
          (firstDiffByteDelta * (1 + config.bytesPerDim()) + prefix) * config.numIndexDims()
              + splitDim;

      writeBuffer.writeVInt(code);

      // write the split value, prefix coded vs. our parent's split value:
      int suffix = config.bytesPerDim() - prefix;
      byte[] savSplitValue = new byte[suffix];
      if (suffix > 1) {
        writeBuffer.writeBytes(splitValue.bytes, address + prefix + 1, suffix - 1);
      }

      byte[] cmp = lastSplitValues.clone();

      System.arraycopy(
          lastSplitValues, splitDim * config.bytesPerDim() + prefix, savSplitValue, 0, suffix);

      // copy our split value into lastSplitValues for our children to prefix-code against
      System.arraycopy(
          splitValue.bytes,
          address + prefix,
          lastSplitValues,
          splitDim * config.bytesPerDim() + prefix,
          suffix);

      int numBytes = appendBlock(writeBuffer, blocks);

      // placeholder for left-tree numBytes
      int idxSav = blocks.size();
      blocks.add(null);

      boolean savNegativeDelta = negativeDeltas[splitDim];
      negativeDeltas[splitDim] = true;

      int leftNumBytes =
          recursePackIndex(
              writeBuffer,
              leafNodes,
              leftBlockFP,
              blocks,
              lastSplitValues,
              negativeDeltas,
              true,
              leavesOffset,
              numLeftLeafNodes);

      if (numLeftLeafNodes != 1) {
        writeBuffer.writeVInt(leftNumBytes);
      } else {
        assert leftNumBytes == 0 : "leftNumBytes=" + leftNumBytes;
      }

      byte[] bytes2 = writeBuffer.toArrayCopy();
      writeBuffer.reset();
      // replace our placeholder:
      blocks.set(idxSav, bytes2);

      negativeDeltas[splitDim] = false;
      int rightNumBytes =
          recursePackIndex(
              writeBuffer,
              leafNodes,
              leftBlockFP,
              blocks,
              lastSplitValues,
              negativeDeltas,
              false,
              rightOffset,
              numLeaves - numLeftLeafNodes);

      negativeDeltas[splitDim] = savNegativeDelta;

      // restore lastSplitValues to what caller originally passed us:
      System.arraycopy(
          savSplitValue, 0, lastSplitValues, splitDim * config.bytesPerDim() + prefix, suffix);

      assert Arrays.equals(lastSplitValues, cmp);

      return numBytes + bytes2.length + leftNumBytes + rightNumBytes;
    }
  }

  private void writeLeafBlockPackedValues(
      DataOutput out,
      int[] commonPrefixLengths,
      int count,
      int sortedDim,
      IntFunction<BytesRef> packedValues,
      int leafCardinality)
      throws IOException {
    int prefixLenSum = Arrays.stream(commonPrefixLengths).sum();
    if (prefixLenSum == config.packedBytesLength()) {
      // all values in this block are equal
      out.writeByte((byte) -1);
    } else {
      assert commonPrefixLengths[sortedDim] < config.bytesPerDim();
      // estimate if storing the values with cardinality is cheaper than storing all values.
      int compressedByteOffset = sortedDim * config.bytesPerDim() + commonPrefixLengths[sortedDim];
      int highCardinalityCost;
      int lowCardinalityCost;
      if (count == leafCardinality) {
        // all values in this block are different
        highCardinalityCost = 0;
        lowCardinalityCost = 1;
      } else {
        // compute cost of runLen compression
        int numRunLens = 0;
        for (int i = 0; i < count; ) {
          // do run-length compression on the byte at compressedByteOffset
          int runLen = runLen(packedValues, i, Math.min(i + 0xff, count), compressedByteOffset);
          assert runLen <= 0xff;
          numRunLens++;
          i += runLen;
        }
        // Add cost of runLen compression
        highCardinalityCost =
            count * (config.packedBytesLength() - prefixLenSum - 1) + 2 * numRunLens;
        // +1 is the byte needed for storing the cardinality
        lowCardinalityCost = leafCardinality * (config.packedBytesLength() - prefixLenSum + 1);
      }
      if (lowCardinalityCost <= highCardinalityCost) {
        out.writeByte((byte) -2);
        writeLowCardinalityLeafBlockPackedValues(out, commonPrefixLengths, count, packedValues);
      } else {
        out.writeByte((byte) sortedDim);
        writeHighCardinalityLeafBlockPackedValues(
            out, commonPrefixLengths, count, sortedDim, packedValues, compressedByteOffset);
      }
    }
  }

  private void writeLowCardinalityLeafBlockPackedValues(
      DataOutput out, int[] commonPrefixLengths, int count, IntFunction<BytesRef> packedValues)
      throws IOException {
    BytesRef value = packedValues.apply(0);
    System.arraycopy(value.bytes, value.offset, scratch, 0, config.packedBytesLength());
    int cardinality = 1;
    for (int i = 1; i < count; i++) {
      value = packedValues.apply(i);
      for (int dim = 0; dim < config.numDims(); dim++) {
        final int start = dim * config.bytesPerDim();
        if (equalsPredicate.test(value.bytes, value.offset + start, scratch, start) == false) {
          out.writeVInt(cardinality);
          for (int j = 0; j < config.numDims(); j++) {
            out.writeBytes(
                scratch,
                j * config.bytesPerDim() + commonPrefixLengths[j],
                config.bytesPerDim() - commonPrefixLengths[j]);
          }
          System.arraycopy(value.bytes, value.offset, scratch, 0, config.packedBytesLength());
          cardinality = 1;
          break;
        } else if (dim == config.numDims() - 1) {
          cardinality++;
        }
      }
    }
    out.writeVInt(cardinality);
    for (int i = 0; i < config.numDims(); i++) {
      out.writeBytes(
          scratch,
          i * config.bytesPerDim() + commonPrefixLengths[i],
          config.bytesPerDim() - commonPrefixLengths[i]);
    }
  }

  private void writeHighCardinalityLeafBlockPackedValues(
      DataOutput out,
      int[] commonPrefixLengths,
      int count,
      int sortedDim,
      IntFunction<BytesRef> packedValues,
      int compressedByteOffset)
      throws IOException {
    commonPrefixLengths[sortedDim]++;
    for (int i = 0; i < count; ) {
      // do run-length compression on the byte at compressedByteOffset
      int runLen = runLen(packedValues, i, Math.min(i + 0xff, count), compressedByteOffset);
      assert runLen <= 0xff;
      BytesRef first = packedValues.apply(i);
      byte prefixByte = first.bytes[first.offset + compressedByteOffset];
      out.writeByte(prefixByte);
      out.writeByte((byte) runLen);
      writeLeafBlockPackedValuesRange(out, commonPrefixLengths, i, i + runLen, packedValues);
      i += runLen;
      assert i <= count;
    }
  }

  private void writeLeafBlockPackedValuesRange(
      DataOutput out,
      int[] commonPrefixLengths,
      int start,
      int end,
      IntFunction<BytesRef> packedValues)
      throws IOException {
    for (int i = start; i < end; ++i) {
      BytesRef ref = packedValues.apply(i);
      assert ref.length == config.packedBytesLength();

      for (int dim = 0; dim < config.numDims(); dim++) {
        int prefix = commonPrefixLengths[dim];
        out.writeBytes(
            ref.bytes,
            ref.offset + dim * config.bytesPerDim() + prefix,
            config.bytesPerDim() - prefix);
      }
    }
  }

  private static int runLen(
      IntFunction<BytesRef> packedValues, int start, int end, int byteOffset) {
    BytesRef first = packedValues.apply(start);
    byte b = first.bytes[first.offset + byteOffset];
    for (int i = start + 1; i < end; ++i) {
      BytesRef ref = packedValues.apply(i);
      byte b2 = ref.bytes[ref.offset + byteOffset];
      assert Byte.toUnsignedInt(b2) >= Byte.toUnsignedInt(b);
      if (b != b2) {
        return i - start;
      }
    }
    return end - start;
  }

  private void writeCommonPrefixes(DataOutput out, int[] commonPrefixes, byte[] packedValue)
      throws IOException {
    for (int dim = 0; dim < config.numDims(); dim++) {
      out.writeVInt(commonPrefixes[dim]);
      out.writeBytes(packedValue, dim * config.bytesPerDim(), commonPrefixes[dim]);
    }
  }

  // only called from assert
  private boolean valueInOrder(
      long ord, byte[] lastPackedValue, byte[] packedValue, int doc, int lastDoc) {
    if (ord > 0) {
      int cmp =
          Arrays.compareUnsigned(
              lastPackedValue, 0, config.bytesPerDim(), packedValue, 0, config.bytesPerDim());
      if (cmp > 0) {
        throw new AssertionError(
            "values out of order: last value="
                + new BytesRef(lastPackedValue)
                + " current value="
                + new BytesRef(packedValue)
                + " ord="
                + ord);
      }
      if (cmp == 0 && doc < lastDoc) {
        throw new AssertionError(
            "docs out of order: last doc=" + lastDoc + " current doc=" + doc + " ord=" + ord);
      }
    }
    System.arraycopy(packedValue, 0, lastPackedValue, 0, config.packedBytesLength());
    return true;
  }
}
