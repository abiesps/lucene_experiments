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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IntsRef;
import org.apache.lucene.util.MathUtil;

/**
 * Handles reading a block KD-tree in byte[] space previously written with {@link BKDWriter}.
 *
 * @lucene.experimental
 */
public class BKDReader extends PointValues {
  final BKDConfig config;
  final int numLeaves;
  final IndexInput in;
  final byte[] minPackedValue;
  final byte[] maxPackedValue;
  final long pointCount;
  final int docCount;
  final int version;
  final long minLeafBlockFP;

  private final long indexStartPointer;
  private final int numIndexBytes;
  private final IndexInput indexIn;
  // if true, the tree is a legacy balanced tree
  private final boolean isTreeBalanced;

  /**
   * Caller must pre-seek the provided {@link IndexInput} to the index location that {@link
   * BKDWriter#finish} returned. BKD tree is always stored off-heap.
   */
  public BKDReader(IndexInput metaIn, IndexInput indexIn, IndexInput dataIn) throws IOException {
    version =
        CodecUtil.checkHeader(
            metaIn, BKDWriter.CODEC_NAME, BKDWriter.VERSION_START, BKDWriter.VERSION_CURRENT);
    final int numDims = metaIn.readVInt();
    final int numIndexDims;
    if (version >= BKDWriter.VERSION_SELECTIVE_INDEXING) {
      numIndexDims = metaIn.readVInt();
    } else {
      numIndexDims = numDims;
    }
    final int maxPointsInLeafNode = metaIn.readVInt();
    final int bytesPerDim = metaIn.readVInt();
    config = BKDConfig.of(numDims, numIndexDims, bytesPerDim, maxPointsInLeafNode);

    // Read index:
    numLeaves = metaIn.readVInt();
    assert numLeaves > 0;

    byte[] minPackedValue = new byte[config.packedIndexBytesLength()];
    byte[] maxPackedValue = new byte[config.packedIndexBytesLength()];

    metaIn.readBytes(minPackedValue, 0, config.packedIndexBytesLength());
    metaIn.readBytes(maxPackedValue, 0, config.packedIndexBytesLength());
    final ArrayUtil.ByteArrayComparator comparator =
        ArrayUtil.getUnsignedComparator(config.bytesPerDim());
    for (int dim = 0; dim < config.numIndexDims(); dim++) {
      if (comparator.compare(
              minPackedValue,
              dim * config.bytesPerDim(),
              maxPackedValue,
              dim * config.bytesPerDim())
          > 0) {
        throw new CorruptIndexException(
            "minPackedValue "
                + new BytesRef(minPackedValue)
                + " is > maxPackedValue "
                + new BytesRef(maxPackedValue)
                + " for dim="
                + dim,
            metaIn);
      }
    }
    this.minPackedValue = minPackedValue;
    if (Arrays.equals(maxPackedValue, minPackedValue)) {
      // save heap for edge case of only a single value
      this.maxPackedValue = minPackedValue;
    } else {
      this.maxPackedValue = maxPackedValue;
    }

    pointCount = metaIn.readVLong();
    docCount = metaIn.readVInt();

    numIndexBytes = metaIn.readVInt();
    if (version >= BKDWriter.VERSION_META_FILE) {
      minLeafBlockFP = metaIn.readLong();
      indexStartPointer = metaIn.readLong();
    } else {
      indexStartPointer = indexIn.getFilePointer();
      minLeafBlockFP = indexIn.readVLong();
      indexIn.seek(indexStartPointer);
    }
    this.indexIn = indexIn;
    this.in = dataIn;
    // for only one leaf, balanced and unbalanced trees can be handled the same way
    // we set it to unbalanced.
    this.isTreeBalanced = numLeaves != 1 && isTreeBalanced();
  }

  private boolean isTreeBalanced() throws IOException {
    if (version >= BKDWriter.VERSION_META_FILE) {
      // since lucene 8.6 all trees are unbalanced.
      return false;
    }
    if (config.numDims() > 1) {
      // high dimensional tree in pre-8.6 indices are balanced.
      assert 1 << MathUtil.log(numLeaves, 2) == numLeaves;
      return true;
    }
    if (1 << MathUtil.log(numLeaves, 2) != numLeaves) {
      // if we don't have enough leaves to fill the last level then it is unbalanced
      return false;
    }
    // count of the last node for unbalanced trees
    final int lastLeafNodePointCount = Math.toIntExact(pointCount % config.maxPointsInLeafNode());
    // navigate to last node
    PointTree pointTree = getPointTree();
    do {
      while (pointTree.moveToSibling()) {}
    } while (pointTree.moveToChild());
    // count number of docs in the node
    final int[] count = new int[] {0};
    pointTree.visitDocIDs(
        new IntersectVisitor() {
          @Override
          public void visit(int docID) {
            count[0]++;
          }

          @Override
          public void visit(DocIdSetIterator iterator) throws IOException {
            int docID;
            while ((docID = iterator.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
              visit(docID);
            }
          }

          @Override
          public void visit(IntsRef ref) {
            count[0] += ref.length;
          }

          @Override
          public void visit(int docID, byte[] packedValue) {
            throw new AssertionError();
          }

          @Override
          public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
            throw new AssertionError();
          }
        });
    return count[0] != lastLeafNodePointCount;
  }

  @Override
  public PointTree getPointTree() throws IOException {
    return new BKDPointTree(
        indexIn.slice("packedIndex", indexStartPointer, numIndexBytes),
        this.in.clone(),
        config,
        numLeaves,
        version,
        pointCount,
        minPackedValue,
        maxPackedValue,
        isTreeBalanced);
  }

  public static class BKDPointTree implements PointTree {
    private int nodeID;
    // during clone, the node root can be different to 1
    private final int nodeRoot;
    // level is 1-based so that we can do level-1 w/o checking each time:
    private int level;
    // used to read the packed tree off-heap
    private final IndexInput innerNodes;
    // used to read the packed leaves off-heap
    private final IndexInput leafNodes;
    // holds the minimum (left most) leaf block file pointer for each level we've recursed to:
    private final long[] leafBlockFPStack;
    // holds the address, in the off-heap index, after reading the node data of each level:
    private final int[] readNodeDataPositions;
    // holds the address, in the off-heap index, of the right-node of each level:
    private final int[] rightNodePositions;
    // holds the splitDim position for each level:
    private final int[] splitDimsPos;
    // true if the per-dim delta we read for the node at this level is a negative offset vs. the
    // last split on this dim; this is a packed
    // 2D array, i.e. to access array[level][dim] you read from negativeDeltas[level*numDims+dim].
    // this will be true if the last time we
    // split on this dimension, we next pushed to the left sub-tree:
    private final boolean[] negativeDeltas;
    // holds the packed per-level split values
    private final byte[][] splitValuesStack;
    // holds the min / max value of the current node.
    private final byte[] minPackedValue, maxPackedValue;
    // holds the previous value of the split dimension
    private final byte[][] splitDimValueStack;
    // tree parameters
    private final BKDConfig config;
    // number of leaves
    private final int leafNodeOffset;
    // version of the index
    private final int version;
    // total number of points
    final long pointCount;
    // last node might not be fully populated
    private final int lastLeafNodePointCount;
    // right most leaf node ID
    private final int rightMostLeafNode;
    // helper objects for reading doc values
    private final byte[] scratchDataPackedValue,
        scratchMinIndexPackedValue,
        scratchMaxIndexPackedValue;
    private final int[] commonPrefixLengths;
    private final BKDReaderDocIDSetIterator scratchIterator;
    private final DocIdsWriter docIdsWriter;
    // if true the tree is balanced, otherwise unbalanced
    private final boolean isTreeBalanced;

    public BKDPointTree(
        IndexInput innerNodes,
        IndexInput leafNodes,
        BKDConfig config,
        int numLeaves,
        int version,
        long pointCount,
        byte[] minPackedValue,
        byte[] maxPackedValue,
        boolean isTreeBalanced)
        throws IOException {
      this(
          innerNodes,
          leafNodes,
          config,
          numLeaves,
          version,
          pointCount,
          1,
          1,
          minPackedValue,
          maxPackedValue,
          new BKDReaderDocIDSetIterator(config.maxPointsInLeafNode(), version),
          new byte[config.packedBytesLength()],
          new byte[config.packedIndexBytesLength()],
          new byte[config.packedIndexBytesLength()],
          new int[config.numDims()],
          isTreeBalanced);
      // read root node
      readNodeData(false);
    }

    public long innerNodesSize() {
      return innerNodes.length();
    }

    public BKDPointTree(
        IndexInput innerNodes,
        IndexInput leafNodes,
        BKDConfig config,
        int numLeaves,
        int version,
        long pointCount,
        int nodeID,
        int level,
        byte[] minPackedValue,
        byte[] maxPackedValue,
        BKDReaderDocIDSetIterator scratchIterator,
        byte[] scratchDataPackedValue,
        byte[] scratchMinIndexPackedValue,
        byte[] scratchMaxIndexPackedValue,
        int[] commonPrefixLengths,
        boolean isTreeBalanced) {
      this.config = config;
      this.version = version;
      this.nodeID = nodeID;
      this.nodeRoot = nodeID;
      this.level = level;
      this.isTreeBalanced = isTreeBalanced;
      leafNodeOffset = numLeaves;
      this.innerNodes = innerNodes;
      this.leafNodes = leafNodes;
      this.minPackedValue = minPackedValue.clone();
      this.maxPackedValue = maxPackedValue.clone();
      // stack arrays that keep information at different levels
      int treeDepth = getTreeDepth(numLeaves);
      splitDimValueStack = new byte[treeDepth][];
      splitValuesStack = new byte[treeDepth][];
      splitValuesStack[0] = new byte[config.packedIndexBytesLength()];
      leafBlockFPStack = new long[treeDepth + 1];
      readNodeDataPositions = new int[treeDepth + 1];
      rightNodePositions = new int[treeDepth];
      splitDimsPos = new int[treeDepth];
      negativeDeltas = new boolean[config.numIndexDims() * treeDepth];
      // information about the unbalance of the tree so we can report the exact size below a node
      this.pointCount = pointCount;
      rightMostLeafNode = (1 << treeDepth - 1) - 1;
      int lastLeafNodePointCount = Math.toIntExact(pointCount % config.maxPointsInLeafNode());
      this.lastLeafNodePointCount =
          lastLeafNodePointCount == 0 ? config.maxPointsInLeafNode() : lastLeafNodePointCount;
      // scratch objects, reused between clones so NN search are not creating those objects
      // in every clone.
      this.scratchIterator = scratchIterator;
      this.commonPrefixLengths = commonPrefixLengths;
      this.scratchDataPackedValue = scratchDataPackedValue;
      this.scratchMinIndexPackedValue = scratchMinIndexPackedValue;
      this.scratchMaxIndexPackedValue = scratchMaxIndexPackedValue;
      this.docIdsWriter = scratchIterator.docIdsWriter;
    }

    public void prefetch(long fp, int len) {
        try {
          leafNodes.prefetch(fp, 1);
        } catch (IOException e) {
          e.printStackTrace();
            //throw new RuntimeException(e);
        }
    }

    /** Dump the current in-memory state of this BKDPointTree instance. */
    public String logState() {



      final StringBuilder sb = new StringBuilder(4_096);
      sb.append("BKDPointTree State\n");
      sb.append("  nodeID=").append(nodeID)
              .append(" nodeRoot=").append(nodeRoot)
              .append(" level=").append(level)
              .append(" leafNodeOffset=").append(leafNodeOffset)
              .append(" isLeaf=").append(isLeafNode())
              .append(" isLeft=").append(isLeftNode())
              .append(" isRoot=").append(isRootNode())
              .append('\n');

      sb.append("  version=").append(version)
              .append(" isTreeBalanced=").append(isTreeBalanced)
              .append(" pointCount=").append(pointCount)
              .append(" lastLeafNodePointCount=").append(lastLeafNodePointCount)
              .append(" rightMostLeafNode=").append(rightMostLeafNode)
              .append('\n');

      // Config summary
      sb.append("  config{")
              .append("numDims=").append(config.numDims())
              .append(", numIndexDims=").append(config.numIndexDims())
              .append(", bytesPerDim=").append(config.bytesPerDim())
              .append(", maxPointsInLeaf=").append(config.maxPointsInLeafNode())
              .append(", packedIndexBytesLength=").append(config.packedIndexBytesLength())
              .append(", packedBytesLength=").append(config.packedBytesLength())
              .append("}\n");

      // Arrays that track traversal state
      sb.append("  leafBlockFPStack=").append(java.util.Arrays.toString(leafBlockFPStack)).append('\n');
      sb.append("  readNodeDataPositions=").append(java.util.Arrays.toString(readNodeDataPositions)).append('\n');
      sb.append("  rightNodePositions=").append(java.util.Arrays.toString(rightNodePositions)).append('\n');
      sb.append("  splitDimsPos=").append(java.util.Arrays.toString(splitDimsPos)).append('\n');

      sb.append("Total docs visited as per traversal").append(totalDocsVisited).append('\n');
      sb.append("leaf node name").append(leafNodes.resourceDescription).append('\n');
      sb.append("leaf blocks").append(leafBlocks()).append('\n');

      // negativeDeltas is packed [level * numIndexDims + dim]
//      final int treeDepth = splitDimsPos.length;
//      final int nIdxDims = config.numIndexDims();
//      sb.append("  negativeDeltas per level×dim:\n");
//      for (int lvl = 0; lvl < treeDepth; lvl++) {
//        sb.append("    level ").append(lvl).append(": [");
//        for (int dim = 0; dim < nIdxDims; dim++) {
//          if (dim > 0) sb.append(", ");
//          int idx = lvl * nIdxDims + dim;
//          if (idx >= negativeDeltas.length) {
//            sb.append("NA");
//          } else {
//            sb.append(negativeDeltas[idx] ? 'T' : 'F');
//          }
//        }
//        sb.append("]\n");
//      }
//
//      // Current bounds (grouped per dimension)
//      sb.append("  minPackedValue=").append(bytesPerDimToHex(minPackedValue, config.bytesPerDim(), config.numIndexDims())).append('\n');
//      sb.append("  maxPackedValue=").append(bytesPerDimToHex(maxPackedValue, config.bytesPerDim(), config.numIndexDims())).append('\n');
//
//      // splitValuesStack per level (only if allocated)
//      sb.append("  splitValuesStack per level (hex per-dim):\n");
//      for (int lvl = 0; lvl < splitValuesStack.length; lvl++) {
//        if (splitValuesStack[lvl] != null) {
//          sb.append("    L").append(lvl).append(": ")
//                  .append(bytesPerDimToHex(splitValuesStack[lvl], config.bytesPerDim(), config.numIndexDims()))
//                  .append('\n');
//        } else {
//          sb.append("    L").append(lvl).append(": null\n");
//        }
//      }
//
//      // splitDimValueStack per level (only if allocated)
//      sb.append("  splitDimValueStack per level (one dim snapshot):\n");
//      for (int lvl = 0; lvl < splitDimValueStack.length; lvl++) {
//        if (splitDimValueStack[lvl] != null) {
//          sb.append("    L").append(lvl).append(": ")
//                  .append(bytesToHex(splitDimValueStack[lvl]))
//                  .append('\n');
//        } else {
//          sb.append("    L").append(lvl).append(": null\n");
//        }
//      }
//
//      // Iterator scratch info
//      if (scratchIterator != null) {
//        sb.append("  scratchIterator{")
//                .append("offset=").append(getField(scratchIterator, "offset"))
//                .append(", length=").append(getField(scratchIterator, "length"))
//                .append(", idx=").append(getField(scratchIterator, "idx"))
//                .append(", docID=").append(getField(scratchIterator, "docID"))
//                .append("}\n");
//        // Show a small prefix of docIDs for context (doesn't mutate)
//        final int[] ids = scratchIterator.docIDs;
//        final int show = Math.min(ids.length, 16);
//        sb.append("  scratchIterator.docIDs[0..").append(show - 1).append("]=")
//                .append(java.util.Arrays.toString(java.util.Arrays.copyOf(ids, show))).append('\n');
//      }
//
//      // I/O positions (best-effort; guard for IOException)
//      try {
//        sb.append("  innerNodes.fp=").append(innerNodes.getFilePointer())
//                .append(" leafNodes.fp=").append(leafNodes.getFilePointer())
//                .append('\n');
//      } catch (Exception ioe) {
//        sb.append("  [failed to read file pointers: ").append(ioe).append("]\n");
//      }

      return sb.toString();
    }

    /** Hex dump of bytes grouped by dimensions (e.g., [d0: 01 02 ... | d1: ...]). */
    private static String bytesPerDimToHex(byte[] a, int bytesPerDim, int numDims) {
      if (a == null) return "null";
      StringBuilder sb = new StringBuilder(2 + a.length * 2 + numDims * 6);
      sb.append('[');
      for (int d = 0; d < numDims; d++) {
        if (d > 0) sb.append(" | ");
        sb.append("d").append(d).append(": ");
        int start = d * bytesPerDim;
        int end = Math.min(start + bytesPerDim, a.length);
        for (int i = start; i < end; i++) {
          int v = a[i] & 0xFF;
          if (i > start) sb.append(' ');
          sb.append(Character.forDigit((v >>> 4) & 0xF, 16));
          sb.append(Character.forDigit(v & 0xF, 16));
        }
      }
      sb.append(']');
      return sb.toString();
    }

    /** Simple hex dump for a whole byte[] (no grouping). */
    private static String bytesToHex(byte[] a) {
      if (a == null) return "null";
      StringBuilder sb = new StringBuilder(a.length * 2);
      for (byte b : a) {
        int v = b & 0xFF;
        sb.append(Character.forDigit((v >>> 4) & 0xF, 16));
        sb.append(Character.forDigit(v & 0xF, 16));
      }
      return sb.toString();
    }

    /**
     * Best-effort reflectively read private fields from BKDReaderDocIDSetIterator
     * without breaking encapsulation or modifying state.
     */
    private static Object getField(Object o, String name) {
      try {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
      } catch (Throwable t) {
        return "(n/a)";
      }
    }


    @Override
    public PointTree clone() {
      BKDPointTree index =
          new BKDPointTree(
              innerNodes.clone(),
              leafNodes.clone(),
              config,
              leafNodeOffset,
              version,
              pointCount,
              nodeID,
              level,
              minPackedValue,
              maxPackedValue,
              scratchIterator,
              scratchDataPackedValue,
              scratchMinIndexPackedValue,
              scratchMaxIndexPackedValue,
              commonPrefixLengths,
              isTreeBalanced);
      index.leafBlockFPStack[index.level] = leafBlockFPStack[level];
      if (isLeafNode() == false) {
        // copy node data
        index.rightNodePositions[index.level] = rightNodePositions[level];
        index.readNodeDataPositions[index.level] = readNodeDataPositions[level];
        index.splitValuesStack[index.level] = splitValuesStack[level].clone();
        System.arraycopy(
            negativeDeltas,
            level * config.numIndexDims(),
            index.negativeDeltas,
            level * config.numIndexDims(),
            config.numIndexDims());
        index.splitDimsPos[level] = splitDimsPos[level];
      }
      return index;
    }

    @Override
    public byte[] getMinPackedValue() {
      return minPackedValue;
    }

    @Override
    public byte[] getMaxPackedValue() {
      return maxPackedValue;
    }

    @Override
    public boolean moveToChild() throws IOException {
      if (isLeafNode()) {
        return false;
      }
      resetNodeDataPosition();
      pushBoundsLeft();
      pushLeft();
      return true;
    }

    public void resetNodeDataPosition() throws IOException {
      // move position of the inner nodes index to visit the first child
      assert readNodeDataPositions[level] <= innerNodes.getFilePointer();
      innerNodes.seek(readNodeDataPositions[level]);
    }

    private void pushBoundsLeft() {
      final int splitDimPos = splitDimsPos[level];
      if (splitDimValueStack[level] == null) {
        splitDimValueStack[level] = new byte[config.bytesPerDim()];
      }
      // save the dimension we are going to change
      System.arraycopy(
          maxPackedValue, splitDimPos, splitDimValueStack[level], 0, config.bytesPerDim());
      assert ArrayUtil.getUnsignedComparator(config.bytesPerDim())
                  .compare(maxPackedValue, splitDimPos, splitValuesStack[level], splitDimPos)
              >= 0
          : "config.bytesPerDim()="
              + config.bytesPerDim()
              + " splitDimPos="
              + splitDimsPos[level]
              + " config.numIndexDims()="
              + config.numIndexDims()
              + " config.numDims()="
              + config.numDims();
      // add the split dim value:
      System.arraycopy(
          splitValuesStack[level], splitDimPos, maxPackedValue, splitDimPos, config.bytesPerDim());
    }

    private void pushLeft() throws IOException {
      nodeID *= 2;
      level++;
      readNodeData(true);
    }

    private void pushBoundsRight() {
      final int splitDimPos = splitDimsPos[level];
      // we should have already visited the left node
      assert splitDimValueStack[level] != null;
      // save the dimension we are going to change
      System.arraycopy(
          minPackedValue, splitDimPos, splitDimValueStack[level], 0, config.bytesPerDim());
      assert ArrayUtil.getUnsignedComparator(config.bytesPerDim())
                  .compare(minPackedValue, splitDimPos, splitValuesStack[level], splitDimPos)
              <= 0
          : "config.bytesPerDim()="
              + config.bytesPerDim()
              + " splitDimPos="
              + splitDimsPos[level]
              + " config.numIndexDims()="
              + config.numIndexDims()
              + " config.numDims()="
              + config.numDims();
      // add the split dim value:
      System.arraycopy(
          splitValuesStack[level], splitDimPos, minPackedValue, splitDimPos, config.bytesPerDim());
    }

    private void pushRight() throws IOException {
      final int nodePosition = rightNodePositions[level];
      assert nodePosition >= innerNodes.getFilePointer()
          : "nodePosition = " + nodePosition + " < currentPosition=" + innerNodes.getFilePointer();
      innerNodes.seek(nodePosition);
      nodeID = 2 * nodeID + 1;
      level++;
      readNodeData(false);
    }

    @Override
    public boolean moveToSibling() throws IOException {
      if (isLeftNode() == false || isRootNode()) {
        return false;
      }
      pop();
      popBounds(maxPackedValue);
      pushBoundsRight();
      pushRight();
      assert nodeExists();
      return true;
    }

    private void pop() {
      nodeID /= 2;
      level--;
    }

    private void popBounds(byte[] packedValue) {
      // restore the split dimension
      System.arraycopy(
          splitDimValueStack[level], 0, packedValue, splitDimsPos[level], config.bytesPerDim());
    }

    @Override
    public boolean moveToParent() {
      if (isRootNode()) {
        return false;
      }
      final byte[] packedValue = isLeftNode() ? maxPackedValue : minPackedValue;
      pop();
      popBounds(packedValue);
      return true;
    }

    private boolean isRootNode() {
      return nodeID == nodeRoot;
    }

    private boolean isLeftNode() {
      return (nodeID & 1) == 0;
    }

    private boolean isLeafNode() {
      return nodeID >= leafNodeOffset;
    }

    private boolean nodeExists() {
      return nodeID - leafNodeOffset < leafNodeOffset;
    }

    /** Only valid after pushLeft or pushRight, not pop! */
    private long getLeafBlockFP() {
      assert isLeafNode() : "nodeID=" + nodeID + " is not a leaf";
      return leafBlockFPStack[level];
    }

    @Override
    public long size() {
      int leftMostLeafNode = nodeID;
      while (leftMostLeafNode < leafNodeOffset) {
        leftMostLeafNode = leftMostLeafNode * 2;
      }
      int rightMostLeafNode = nodeID;
      while (rightMostLeafNode < leafNodeOffset) {
        rightMostLeafNode = rightMostLeafNode * 2 + 1;
      }
      final int numLeaves;
      if (rightMostLeafNode >= leftMostLeafNode) {
        // both are on the same level
        numLeaves = rightMostLeafNode - leftMostLeafNode + 1;
      } else {
        // left is one level deeper than right
        numLeaves = rightMostLeafNode - leftMostLeafNode + 1 + leafNodeOffset;
      }
      assert numLeaves == getNumLeavesSlow(nodeID) : numLeaves + " " + getNumLeavesSlow(nodeID);
      if (isTreeBalanced) {
        // before lucene 8.6, trees might have been constructed as fully balanced trees.
        return sizeFromBalancedTree(leftMostLeafNode, rightMostLeafNode);
      }
      // size for an unbalanced tree.
      return rightMostLeafNode == this.rightMostLeafNode
          ? (long) (numLeaves - 1) * config.maxPointsInLeafNode() + lastLeafNodePointCount
          : (long) numLeaves * config.maxPointsInLeafNode();
    }

    private long sizeFromBalancedTree(int leftMostLeafNode, int rightMostLeafNode) {
      // number of points that need to be distributed between leaves, one per leaf
      final int extraPoints =
          Math.toIntExact(((long) config.maxPointsInLeafNode() * this.leafNodeOffset) - pointCount);
      assert extraPoints < leafNodeOffset : "point excess should be lower than leafNodeOffset";
      // offset where we stop adding one point to the leaves
      final int nodeOffset = leafNodeOffset - extraPoints;
      long count = 0;
      for (int node = leftMostLeafNode; node <= rightMostLeafNode; node++) {
        // offsetPosition provides which extra point will be added to this node
        if (balanceTreeNodePosition(0, leafNodeOffset, node - leafNodeOffset, 0, 0) < nodeOffset) {
          count += config.maxPointsInLeafNode();
        } else {
          count += config.maxPointsInLeafNode() - 1;
        }
      }
      return count;
    }

    private int balanceTreeNodePosition(
        int minNode, int maxNode, int node, int position, int level) {
      if (maxNode - minNode == 1) {
        return position;
      }
      final int mid = (minNode + maxNode + 1) >>> 1;
      if (mid > node) {
        return balanceTreeNodePosition(minNode, mid, node, position, level + 1);
      } else {
        return balanceTreeNodePosition(mid, maxNode, node, position + (1 << level), level + 1);
      }
    }

    @Override
    public void visitDocIDs(PointValues.IntersectVisitor visitor) throws IOException {
      resetNodeDataPosition();
      addAll(visitor, false);
    }

    public void addAll(PointValues.IntersectVisitor visitor, boolean grown) throws IOException {
      if (grown == false) {
        final long size = size();
        if (size <= Integer.MAX_VALUE) {
          visitor.grow((int) size);
          grown = true;
        }
      }
      if (isLeafNode()) {
        // Leaf node
        leafNodes.seek(getLeafBlockFP());
        // How many points are stored in this leaf cell:
        int count = leafNodes.readVInt();
        // No need to call grow(), it has been called up-front
        // Borrow scratchIterator.docIds as decoding buffer
        docIdsWriter.readInts(leafNodes, count, visitor, scratchIterator.docIDs);
      } else {
        pushLeft();
        addAll(visitor, grown);
        pop();
        pushRight();
        addAll(visitor, grown);
        pop();
      }
    }

    @Override
    public void prefetchDocValues(PointValues.IntersectVisitor visitor) throws IOException {
      resetNodeDataPosition();
      prefetchLeavesOneByOne(visitor);
    }

    private void prefetchLeavesOneByOne(PointValues.IntersectVisitor visitor) throws IOException {
      if (isLeafNode()) {
        // Leaf node
        prefetchDocValues(visitor, getLeafBlockFP());
      } else {
        pushLeft();
        prefetchLeavesOneByOne(visitor);
        pop();
        pushRight();
        prefetchLeavesOneByOne(visitor);
        pop();
      }
    }


    private void prefetchDocValues(PointValues.IntersectVisitor visitor, long fp) throws IOException {
      // Leaf node; scan and filter all points in this block:
      // leaf ordinals are 0..numLeaves-1
//      ToDo add prefetch leaf block code here
      prefetchLeafBlock(leafNodes, fp);
      //int count = readDocIDs(leafNodes, fp, scratchIterator);
//      if (version >= BKDWriter.VERSION_LOW_CARDINALITY_LEAVES) {
//        visitDocValuesWithCardinality(
//                commonPrefixLengths,
//                scratchDataPackedValue,
//                scratchMinIndexPackedValue,
//                scratchMaxIndexPackedValue,
//                leafNodes,
//                scratchIterator,
//                count,
//                visitor);
//      } else {
//        visitDocValuesNoCardinality(
//                commonPrefixLengths,
//                scratchDataPackedValue,
//                scratchMinIndexPackedValue,
//                scratchMaxIndexPackedValue,
//                leafNodes,
//                scratchIterator,
//                count,
//                visitor);
//      }
    }




    @Override
    public void visitDocValues(PointValues.IntersectVisitor visitor) throws IOException {
      resetNodeDataPosition();
      visitLeavesOneByOne(visitor);
    }

    public Set<Long> leafBlockFPs = new HashSet<>();


    public String name() {
      return innerNodes.resourceDescription + "_" + leafNodes.resourceDescription;
    }
    public void markLeafForVisiting() {
      if (isLeafNode()) {
        // Leaf node

        long leafBlockFP = getLeafBlockFP();
          try {
              leafNodes.prefetch(leafBlockFP, 1);
          } catch (IOException e) {
            e.printStackTrace();

          }
          leafBlockFPs.add(leafBlockFP);
        //System.out.println("");
        //visitDocValues(visitor, leafBlockFP);
      } else {
        System.out.println("===========IF we are here - we are fucked ============================================");
      }
    }


    private void visitLeavesOneByOne(PointValues.IntersectVisitor visitor) throws IOException {
      if (isLeafNode()) {
        // Leaf node
        long leafBlockFP = getLeafBlockFP();
        leafBlockFPs.add(leafBlockFP);
        //System.out.println("");
        visitDocValues(visitor, leafBlockFP);
      } else {
        System.out.println("====================Do I ever come here if yes then we are fucked !!!================================");
        pushLeft();
        visitLeavesOneByOne(visitor);
        pop();
        pushRight();
        visitLeavesOneByOne(visitor);
        pop();
      }
    }

    /** Best-effort prefetch of a single leaf block starting at file pointer fp. */
    private void prefetchLeafBlock(IndexInput in, long fp) {
      try {
        in.prefetch(fp, 1);
      } catch (Exception e) {
        e.printStackTrace();
        // Best-effort: swallow prefetch failures, do normal reads.
      }
    }

    public Set<Long> leafBlocks() {

//      Set<Long> leafBlocks = new HashSet<>();
//        for (long l : leafBlockFPStack) {
//            leafBlocks.add(l);
//        }
      return new HashSet<>(leafBlockFPs);
    }

    public void visitDocValues(PointValues.IntersectVisitor visitor, long fp) throws IOException {
      // Leaf node; scan and filter all points in this block:
      // leaf ordinals are 0..numLeaves-1
//      ToDo add prefetch leaf block code here
      //prefetchLeafBlock(leafNodes, fp);
      int count = readDocIDs(leafNodes, fp, scratchIterator);
      totalDocsVisited += count;
      if (version >= BKDWriter.VERSION_LOW_CARDINALITY_LEAVES) {
        visitDocValuesWithCardinality(
            commonPrefixLengths,
            scratchDataPackedValue,
            scratchMinIndexPackedValue,
            scratchMaxIndexPackedValue,
            leafNodes,
            scratchIterator,
            count,
            visitor);
      } else {
        visitDocValuesNoCardinality(
            commonPrefixLengths,
            scratchDataPackedValue,
            scratchMinIndexPackedValue,
            scratchMaxIndexPackedValue,
            leafNodes,
            scratchIterator,
            count,
            visitor);
      }
    }

    int totalDocsVisited = 0;
    private int readDocIDs(IndexInput in, long blockFP, BKDReaderDocIDSetIterator iterator)
        throws IOException {
      //prefetching leaf block
      //in.prefetch(blockFP,1);
      in.seek(blockFP);
      // How many points are stored in this leaf cell:
      int count = in.readVInt();

      docIdsWriter.readInts(in, count, iterator.docIDs);

      return count;
    }

    // for assertions
    private int getNumLeavesSlow(int node) {
      if (node >= 2 * leafNodeOffset) {
        return 0;
      } else if (node >= leafNodeOffset) {
        return 1;
      } else {
        final int leftCount = getNumLeavesSlow(node * 2);
        final int rightCount = getNumLeavesSlow(node * 2 + 1);
        return leftCount + rightCount;
      }
    }

    private void readNodeData(boolean isLeft) throws IOException {
      leafBlockFPStack[level] = leafBlockFPStack[level - 1];
      if (isLeft == false) {
        // read leaf block FP delta
        leafBlockFPStack[level] += innerNodes.readVLong();
      }

      if (isLeafNode() == false) {
        System.arraycopy(
            negativeDeltas,
            (level - 1) * config.numIndexDims(),
            negativeDeltas,
            level * config.numIndexDims(),
            config.numIndexDims());
        negativeDeltas[
                level * config.numIndexDims() + (splitDimsPos[level - 1] / config.bytesPerDim())] =
            isLeft;

        if (splitValuesStack[level] == null) {
          splitValuesStack[level] = splitValuesStack[level - 1].clone();
        } else {
          System.arraycopy(
              splitValuesStack[level - 1],
              0,
              splitValuesStack[level],
              0,
              config.packedIndexBytesLength());
        }

        // read split dim, prefix, firstDiffByteDelta encoded as int:
        int code = innerNodes.readVInt();
        final int splitDim = code % config.numIndexDims();
        splitDimsPos[level] = splitDim * config.bytesPerDim();
        code /= config.numIndexDims();
        final int prefix = code % (1 + config.bytesPerDim());
        final int suffix = config.bytesPerDim() - prefix;

        if (suffix > 0) {
          int firstDiffByteDelta = code / (1 + config.bytesPerDim());
          if (negativeDeltas[level * config.numIndexDims() + splitDim]) {
            firstDiffByteDelta = -firstDiffByteDelta;
          }
          final int startPos = splitDimsPos[level] + prefix;
          final int oldByte = splitValuesStack[level][startPos] & 0xFF;
          splitValuesStack[level][startPos] = (byte) (oldByte + firstDiffByteDelta);
          innerNodes.readBytes(splitValuesStack[level], startPos + 1, suffix - 1);
        } else {
          // our split value is == last split value in this dim, which can happen when there are
          // many duplicate values
        }

        final int leftNumBytes;
        if (nodeID * 2 < leafNodeOffset) {
          leftNumBytes = innerNodes.readVInt();
        } else {
          leftNumBytes = 0;
        }
        rightNodePositions[level] = Math.toIntExact(innerNodes.getFilePointer()) + leftNumBytes;
        readNodeDataPositions[level] = Math.toIntExact(innerNodes.getFilePointer());
      }
    }

    private int getTreeDepth(int numLeaves) {
      // First +1 because all the non-leave nodes makes another power
      // of 2; e.g. to have a fully balanced tree with 4 leaves you
      // need a depth=3 tree:

      // Second +1 because MathUtil.log computes floor of the logarithm; e.g.
      // with 5 leaves you need a depth=4 tree:
      return MathUtil.log(numLeaves, 2) + 2;
    }

    private void visitDocValuesNoCardinality(
        int[] commonPrefixLengths,
        byte[] scratchDataPackedValue,
        byte[] scratchMinIndexPackedValue,
        byte[] scratchMaxIndexPackedValue,
        IndexInput in,
        BKDReaderDocIDSetIterator scratchIterator,
        int count,
        PointValues.IntersectVisitor visitor)
        throws IOException {
      readCommonPrefixes(commonPrefixLengths, scratchDataPackedValue, in);
      if (config.numIndexDims() != 1 && version >= BKDWriter.VERSION_LEAF_STORES_BOUNDS) {
        byte[] minPackedValue = scratchMinIndexPackedValue;
        System.arraycopy(
            scratchDataPackedValue, 0, minPackedValue, 0, config.packedIndexBytesLength());
        byte[] maxPackedValue = scratchMaxIndexPackedValue;
        // Copy common prefixes before reading adjusted box
        System.arraycopy(minPackedValue, 0, maxPackedValue, 0, config.packedIndexBytesLength());
        readMinMax(commonPrefixLengths, minPackedValue, maxPackedValue, in);

        // The index gives us range of values for each dimension, but the actual range of values
        // might be much more narrow than what the index told us, so we double check the relation
        // here, which is cheap yet might help figure out that the block either entirely matches
        // or does not match at all. This is especially more likely in the case that there are
        // multiple dimensions that have correlation, ie. splitting on one dimension also
        // significantly changes the range of values in another dimension.
        PointValues.Relation r = visitor.compare(minPackedValue, maxPackedValue);
        if (r == PointValues.Relation.CELL_OUTSIDE_QUERY) {
          return;
        }
        visitor.grow(count);
        if (r == PointValues.Relation.CELL_INSIDE_QUERY) {
          for (int i = 0; i < count; ++i) {
            visitor.visit(scratchIterator.docIDs[i]);
          }
          return;
        }
      } else {
        visitor.grow(count);
      }

      int compressedDim = readCompressedDim(in);

      if (compressedDim == -1) {
        visitUniqueRawDocValues(scratchDataPackedValue, scratchIterator, count, visitor);
      } else {
        visitCompressedDocValues(
            commonPrefixLengths,
            scratchDataPackedValue,
            in,
            scratchIterator,
            count,
            visitor,
            compressedDim);
      }
    }

    private void visitDocValuesWithCardinality(
        int[] commonPrefixLengths,
        byte[] scratchDataPackedValue,
        byte[] scratchMinIndexPackedValue,
        byte[] scratchMaxIndexPackedValue,
        IndexInput in,
        BKDReaderDocIDSetIterator scratchIterator,
        int count,
        PointValues.IntersectVisitor visitor)
        throws IOException {

      readCommonPrefixes(commonPrefixLengths, scratchDataPackedValue, in);
      int compressedDim = readCompressedDim(in);
      if (compressedDim == -1) {
        // all values are the same
        visitor.grow(count);
        visitUniqueRawDocValues(scratchDataPackedValue, scratchIterator, count, visitor);
      } else {
        if (config.numIndexDims() != 1) {
          byte[] minPackedValue = scratchMinIndexPackedValue;
          System.arraycopy(
              scratchDataPackedValue, 0, minPackedValue, 0, config.packedIndexBytesLength());
          byte[] maxPackedValue = scratchMaxIndexPackedValue;
          // Copy common prefixes before reading adjusted box
          System.arraycopy(minPackedValue, 0, maxPackedValue, 0, config.packedIndexBytesLength());
          readMinMax(commonPrefixLengths, minPackedValue, maxPackedValue, in);

          // The index gives us range of values for each dimension, but the actual range of values
          // might be much more narrow than what the index told us, so we double check the relation
          // here, which is cheap yet might help figure out that the block either entirely matches
          // or does not match at all. This is especially more likely in the case that there are
          // multiple dimensions that have correlation, ie. splitting on one dimension also
          // significantly changes the range of values in another dimension.
          PointValues.Relation r = visitor.compare(minPackedValue, maxPackedValue);
          if (r == PointValues.Relation.CELL_OUTSIDE_QUERY) {
            return;
          }
          visitor.grow(count);

          if (r == PointValues.Relation.CELL_INSIDE_QUERY) {
            for (int i = 0; i < count; ++i) {
              visitor.visit(scratchIterator.docIDs[i]);
            }
            return;
          }
        } else {
          visitor.grow(count);
        }

        if (compressedDim == -2) {
          // low cardinality values
          visitSparseRawDocValues(
              commonPrefixLengths, scratchDataPackedValue, in, scratchIterator, count, visitor);
        } else {
          // high cardinality
          visitCompressedDocValues(
              commonPrefixLengths,
              scratchDataPackedValue,
              in,
              scratchIterator,
              count,
              visitor,
              compressedDim);
        }
      }
    }

    private void readMinMax(
        int[] commonPrefixLengths, byte[] minPackedValue, byte[] maxPackedValue, IndexInput in)
        throws IOException {
      for (int dim = 0; dim < config.numIndexDims(); dim++) {
        int prefix = commonPrefixLengths[dim];
        in.readBytes(
            minPackedValue, dim * config.bytesPerDim() + prefix, config.bytesPerDim() - prefix);
        in.readBytes(
            maxPackedValue, dim * config.bytesPerDim() + prefix, config.bytesPerDim() - prefix);
      }
    }

    // read cardinality and point
    private void visitSparseRawDocValues(
        int[] commonPrefixLengths,
        byte[] scratchPackedValue,
        IndexInput in,
        BKDReaderDocIDSetIterator scratchIterator,
        int count,
        PointValues.IntersectVisitor visitor)
        throws IOException {
      int i;
      for (i = 0; i < count; ) {
        int length = in.readVInt();
        for (int dim = 0; dim < config.numDims(); dim++) {
          int prefix = commonPrefixLengths[dim];
          //Is this resulting in a page fault ?
          in.readBytes(
              scratchPackedValue,
              dim * config.bytesPerDim() + prefix,
              config.bytesPerDim() - prefix);
        }
        scratchIterator.reset(i, length);
        visitor.visit(scratchIterator, scratchPackedValue);
        i += length;
      }
      if (i != count) {
        throw new CorruptIndexException(
            "Sub blocks do not add up to the expected count: " + count + " != " + i, in);
      }
    }

    // point is under commonPrefix
    private void visitUniqueRawDocValues(
        byte[] scratchPackedValue,
        BKDReaderDocIDSetIterator scratchIterator,
        int count,
        PointValues.IntersectVisitor visitor)
        throws IOException {
      scratchIterator.reset(0, count);
      visitor.visit(scratchIterator, scratchPackedValue);
    }

    private void visitCompressedDocValues(
        int[] commonPrefixLengths,
        byte[] scratchPackedValue,
        IndexInput in,
        BKDReaderDocIDSetIterator scratchIterator,
        int count,
        PointValues.IntersectVisitor visitor,
        int compressedDim)
        throws IOException {
      // the byte at `compressedByteOffset` is compressed using run-length compression,
      // other suffix bytes are stored verbatim
      final int compressedByteOffset =
          compressedDim * config.bytesPerDim() + commonPrefixLengths[compressedDim];
      commonPrefixLengths[compressedDim]++;
      int i;
      for (i = 0; i < count; ) {
        scratchPackedValue[compressedByteOffset] = in.readByte();
        final int runLen = Byte.toUnsignedInt(in.readByte());
        for (int j = 0; j < runLen; ++j) {
          for (int dim = 0; dim < config.numDims(); dim++) {
            int prefix = commonPrefixLengths[dim];
            in.readBytes(
                scratchPackedValue,
                dim * config.bytesPerDim() + prefix,
                config.bytesPerDim() - prefix);
          }
          visitor.visit(scratchIterator.docIDs[i + j], scratchPackedValue);
        }
        i += runLen;
      }
      if (i != count) {
        throw new CorruptIndexException(
            "Sub blocks do not add up to the expected count: " + count + " != " + i, in);
      }
    }

    private int readCompressedDim(IndexInput in) throws IOException {
      int compressedDim = in.readByte();
      if (compressedDim < -2
          || compressedDim >= config.numDims()
          || (version < BKDWriter.VERSION_LOW_CARDINALITY_LEAVES && compressedDim == -2)) {
        throw new CorruptIndexException("Got compressedDim=" + compressedDim, in);
      }
      return compressedDim;
    }

    private void readCommonPrefixes(
        int[] commonPrefixLengths, byte[] scratchPackedValue, IndexInput in) throws IOException {
      for (int dim = 0; dim < config.numDims(); dim++) {
        int prefix = in.readVInt();
        commonPrefixLengths[dim] = prefix;
        if (prefix > 0) {
          in.readBytes(scratchPackedValue, dim * config.bytesPerDim(), prefix);
        }
        // System.out.println("R: " + dim + " of " + numDims + " prefix=" + prefix);
      }
    }

    @Override
    public String toString() {
      return "nodeID=" + nodeID;
    }
  }

  @Override
  public byte[] getMinPackedValue() {
    return minPackedValue.clone();
  }

  @Override
  public byte[] getMaxPackedValue() {
    return maxPackedValue.clone();
  }

  @Override
  public int getNumDimensions() throws IOException {
    return config.numDims();
  }

  @Override
  public int getNumIndexDimensions() throws IOException {
    return config.numIndexDims();
  }

  @Override
  public int getBytesPerDimension() throws IOException {
    return config.bytesPerDim();
  }

  @Override
  public long size() {
    return pointCount;
  }

  @Override
  public int getDocCount() {
    return docCount;
  }

  /** Reusable {@link DocIdSetIterator} to handle low cardinality leaves. */
  private static class BKDReaderDocIDSetIterator extends DocIdSetIterator {

    private int idx;
    private int length;
    private int offset;
    private int docID;
    final int[] docIDs;
    private final DocIdsWriter docIdsWriter;

    public BKDReaderDocIDSetIterator(int maxPointsInLeafNode, int version) {
      this.docIDs = new int[maxPointsInLeafNode];
      this.docIdsWriter = new DocIdsWriter(maxPointsInLeafNode, version);
    }

    @Override
    public int docID() {
      return docID;
    }

    private void reset(int offset, int length) {
      this.offset = offset;
      this.length = length;
      assert offset + length <= docIDs.length;
      this.docID = -1;
      this.idx = 0;
    }

    @Override
    public int nextDoc() throws IOException {
      if (idx == length) {
        docID = DocIdSetIterator.NO_MORE_DOCS;
      } else {
        docID = docIDs[offset + idx];
        idx++;
      }
      return docID;
    }

    @Override
    public int advance(int target) throws IOException {
      return slowAdvance(target);
    }

    @Override
    public long cost() {
      return length;
    }
  }
}

