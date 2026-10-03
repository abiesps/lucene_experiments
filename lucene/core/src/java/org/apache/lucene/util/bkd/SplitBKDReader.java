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
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.search.AbstractDocIdSetIterator;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.MathUtil;

/**
 * Reads a one-dimensional block KD-tree written by {@link SplitBKDWriter}. Inner nodes are read as
 * in {@link BKDReader}. At a leaf, the tree's min and max packed values and its size are the tight
 * bounds and the count of the leaf directory, so a traversal decides each leaf from the index file
 * alone. Visiting doc IDs reads only the doc ID file; visiting doc values reads the doc ID file and
 * the values file.
 *
 * @lucene.experimental
 */
public final class SplitBKDReader extends PointValues {

  /** A decoded leaf directory entry. */
  public record LeafEntry(
      int count,
      byte[] minPackedValue,
      byte[] maxPackedValue,
      int minDocID,
      int maxDocID,
      long docBlockFP,
      int docBlockLength,
      long valueBlockFP,
      int valueBlockLength) {}

  final BKDConfig config;
  final int numLeaves;
  final byte[] minPackedValue;
  final byte[] maxPackedValue;
  final long pointCount;
  final int docCount;
  final int numIndexBytes;
  final long dataStartFP;
  final long indexStartFP;
  final long valDataStartFP;
  final long directoryStartFP;
  final long directoryEndFP;
  final long docDataEndFP;
  final long valDataEndFP;
  final int pageShift;
  final int numPages;
  // absolute .kdi file pointer of each directory page, read and checked at open
  final long[] pageFPs;

  private final IndexInput indexIn;
  private final IndexInput dataIn;
  private final IndexInput valIn;

  /**
   * Reads the field metadata from {@code metaIn} and the directory page table from {@code indexIn}.
   */
  public SplitBKDReader(IndexInput metaIn, IndexInput indexIn, IndexInput dataIn, IndexInput valIn)
      throws IOException {
    CodecUtil.checkHeader(
        metaIn,
        SplitBKDWriter.CODEC_NAME,
        SplitBKDWriter.VERSION_START,
        SplitBKDWriter.VERSION_CURRENT);
    final int numDims = metaIn.readVInt();
    final int numIndexDims = metaIn.readVInt();
    final int maxPointsInLeafNode = metaIn.readVInt();
    final int bytesPerDim = metaIn.readVInt();
    if (numDims != 1 || numIndexDims != 1) {
      throw new CorruptIndexException(
          "split points need 1 dimension, got numDims=" + numDims + " numIndexDims=" + numIndexDims,
          metaIn);
    }
    config = BKDConfig.of(numDims, numIndexDims, bytesPerDim, maxPointsInLeafNode);
    numLeaves = metaIn.readVInt();
    if (numLeaves <= 0) {
      throw new CorruptIndexException("numLeaves must be > 0, got " + numLeaves, metaIn);
    }
    byte[] minPackedValue = new byte[config.packedIndexBytesLength()];
    byte[] maxPackedValue = new byte[config.packedIndexBytesLength()];
    metaIn.readBytes(minPackedValue, 0, config.packedIndexBytesLength());
    metaIn.readBytes(maxPackedValue, 0, config.packedIndexBytesLength());
    if (Arrays.compareUnsigned(minPackedValue, maxPackedValue) > 0) {
      throw new CorruptIndexException(
          "minPackedValue "
              + new BytesRef(minPackedValue)
              + " is > maxPackedValue "
              + new BytesRef(maxPackedValue),
          metaIn);
    }
    this.minPackedValue = minPackedValue;
    this.maxPackedValue = maxPackedValue;
    pointCount = metaIn.readVLong();
    docCount = metaIn.readVInt();
    numIndexBytes = metaIn.readVInt();
    dataStartFP = metaIn.readLong();
    indexStartFP = metaIn.readLong();
    valDataStartFP = metaIn.readLong();
    final byte hasLeafDirectory = metaIn.readByte();
    if (hasLeafDirectory != SplitBKDWriter.HAS_LEAF_DIRECTORY) {
      throw new CorruptIndexException("unknown hasLeafDirectory=" + hasLeafDirectory, metaIn);
    }
    final byte docEncoding = metaIn.readByte();
    if (docEncoding != SplitBKDWriter.DOC_ENCODING_DOC_IDS_WRITER) {
      throw new CorruptIndexException("unknown docEncoding=" + docEncoding, metaIn);
    }
    directoryStartFP = metaIn.readLong();
    directoryEndFP = metaIn.readLong();
    docDataEndFP = metaIn.readLong();
    valDataEndFP = metaIn.readLong();
    pageShift = metaIn.readVInt();
    numPages = metaIn.readVInt();
    if (pageShift < 0 || pageShift > 16) {
      throw new CorruptIndexException("illegal pageShift=" + pageShift, metaIn);
    }
    final long expectedPages = ((long) numLeaves + (1L << pageShift) - 1) >>> pageShift;
    if (numPages != expectedPages) {
      throw new CorruptIndexException(
          "numPages=" + numPages + " but numLeaves=" + numLeaves + " needs " + expectedPages,
          metaIn);
    }
    if (directoryStartFP != indexStartFP + numIndexBytes
        || directoryEndFP < directoryStartFP + (long) Long.BYTES * numPages
        || directoryEndFP > indexIn.length() - CodecUtil.footerLength()) {
      throw new CorruptIndexException(
          "illegal directory range ["
              + directoryStartFP
              + ", "
              + directoryEndFP
              + ") for index of length "
              + indexIn.length(),
          metaIn);
    }
    if (docDataEndFP < dataStartFP || valDataEndFP < valDataStartFP) {
      throw new CorruptIndexException("illegal leaf data ranges", metaIn);
    }
    this.indexIn = indexIn;
    this.dataIn = dataIn;
    this.valIn = valIn;

    pageFPs = new long[numPages];
    IndexInput pageTable = indexIn.clone();
    pageTable.seek(directoryStartFP);
    pageTable.readLongs(pageFPs, 0, numPages);
    long previous = directoryStartFP + (long) Long.BYTES * numPages;
    if (pageFPs[0] != previous) {
      throw new CorruptIndexException(
          "first page at " + pageFPs[0] + " but expected " + previous, pageTable);
    }
    for (int page = 1; page < numPages; page++) {
      if (pageFPs[page] <= pageFPs[page - 1]) {
        throw new CorruptIndexException(
            "page file pointers are not increasing at page " + page, pageTable);
      }
    }
    if (pageFPs[numPages - 1] >= directoryEndFP) {
      throw new CorruptIndexException("last page starts at the directory end", pageTable);
    }
  }

  /** Number of leaves of this tree. */
  public int getNumLeaves() {
    return numLeaves;
  }

  /** End (exclusive) of the directory in the index file. */
  public long getDirectoryEndFP() {
    return directoryEndFP;
  }

  /** Reads the directory entry of leaf {@code leafID} (in value order). */
  public LeafEntry leafEntry(int leafID) throws IOException {
    if (leafID < 0 || leafID >= numLeaves) {
      throw new IllegalArgumentException("leafID=" + leafID + " numLeaves=" + numLeaves);
    }
    Page page = new Page(config, pageShift);
    decodePage(indexIn.clone(), leafID >>> pageShift, page);
    int k = leafID & ((1 << pageShift) - 1);
    int bytes = config.bytesPerDim();
    return new LeafEntry(
        page.count[k],
        ArrayUtil.copyOfSubArray(page.min, k * bytes, (k + 1) * bytes),
        ArrayUtil.copyOfSubArray(page.max, k * bytes, (k + 1) * bytes),
        page.minDoc[k],
        page.maxDoc[k],
        page.docFP[k],
        page.docLen[k],
        page.valFP[k],
        page.valLen[k]);
  }

  /**
   * Decodes every directory page and checks that the leaf counts add up to the point count.
   *
   * @throws CorruptIndexException if they do not, or if a page is corrupt
   */
  public void checkLeafCounts() throws IOException {
    IndexInput in = indexIn.clone();
    Page page = new Page(config, pageShift);
    long sum = 0;
    for (int p = 0; p < numPages; p++) {
      decodePage(in, p, page);
      for (int k = 0; k < page.numEntries; k++) {
        sum += page.count[k];
      }
    }
    if (sum != pointCount) {
      throw new CorruptIndexException(
          "leaf counts add up to " + sum + " but pointCount=" + pointCount, in);
    }
  }

  int entriesInPage(int page) {
    return page == numPages - 1 ? numLeaves - (page << pageShift) : 1 << pageShift;
  }

  /** Decodes directory page {@code p} into {@code page}. */
  void decodePage(IndexInput in, int p, Page page) throws IOException {
    final long end = p + 1 < numPages ? pageFPs[p + 1] : directoryEndFP;
    final int bytesPerDim = config.bytesPerDim();
    final int maxPoints = config.maxPointsInLeafNode();
    in.seek(pageFPs[p]);
    long docFP = in.readVLong();
    long valFP = in.readVLong();
    final int numEntries = entriesInPage(p);
    for (int k = 0; k < numEntries; k++) {
      int flags = in.readByte() & 0xFF;
      if ((flags & ~SplitBKDWriter.FLAG_FULL_LEAF) != 0) {
        throw new CorruptIndexException("reserved directory flag bits set: " + flags, in);
      }
      int count = (flags & SplitBKDWriter.FLAG_FULL_LEAF) != 0 ? maxPoints : in.readVInt();
      if (count <= 0 || count > maxPoints) {
        throw new CorruptIndexException("illegal leaf count " + count, in);
      }
      final int minOffset = k * bytesPerDim;
      int minPrefix = in.readByte() & 0xFF;
      if (minPrefix > bytesPerDim || (k == 0 && minPrefix != 0)) {
        throw new CorruptIndexException("illegal min prefix " + minPrefix, in);
      }
      if (minPrefix > 0) {
        System.arraycopy(page.max, minOffset - bytesPerDim, page.min, minOffset, minPrefix);
      }
      in.readBytes(page.min, minOffset + minPrefix, bytesPerDim - minPrefix);
      int maxPrefix = in.readByte() & 0xFF;
      if (maxPrefix > bytesPerDim) {
        throw new CorruptIndexException("illegal max prefix " + maxPrefix, in);
      }
      System.arraycopy(page.min, minOffset, page.max, minOffset, maxPrefix);
      in.readBytes(page.max, minOffset + maxPrefix, bytesPerDim - maxPrefix);
      int minDoc = in.readVInt();
      int maxDoc = minDoc + in.readVInt();
      int docLen = in.readVInt();
      int valLen = in.readVInt();
      page.count[k] = count;
      page.minDoc[k] = minDoc;
      page.maxDoc[k] = maxDoc;
      page.docFP[k] = docFP;
      page.docLen[k] = docLen;
      page.valFP[k] = valFP;
      page.valLen[k] = valLen;
      docFP += docLen;
      valFP += valLen;
      if (in.getFilePointer() > end) {
        throw new CorruptIndexException("directory entry past its page " + p, in);
      }
    }
    page.numEntries = numEntries;
    page.pageIndex = p;
  }

  /** A decoded directory page. */
  static final class Page {
    int pageIndex = -1;
    int numEntries;
    final int[] count;
    final byte[] min;
    final byte[] max;
    final int[] minDoc;
    final int[] maxDoc;
    final long[] docFP;
    final int[] docLen;
    final long[] valFP;
    final int[] valLen;

    Page(BKDConfig config, int pageShift) {
      int n = 1 << pageShift;
      count = new int[n];
      min = new byte[n * config.bytesPerDim()];
      max = new byte[n * config.bytesPerDim()];
      minDoc = new int[n];
      maxDoc = new int[n];
      docFP = new long[n];
      docLen = new int[n];
      valFP = new long[n];
      valLen = new int[n];
    }
  }

  /**
   * Decoded directory pages, at most {@link #SIZE}, least recently used out. Shared by a tree and
   * every clone made from it; not thread-safe, like the tree.
   */
  static final class PageCache {
    static final int SIZE = 4;
    private final SplitBKDReader reader;
    private final IndexInput in;
    private final Page[] pages = new Page[SIZE];
    private final long[] lastUse = new long[SIZE];
    private long clock;
    // number of page decodes, for tests
    int decodes;

    PageCache(SplitBKDReader reader, IndexInput in) {
      this.reader = reader;
      this.in = in;
    }

    Page get(int pageIndex) throws IOException {
      int victim = -1;
      for (int i = 0; i < SIZE; i++) {
        Page page = pages[i];
        if (page == null) {
          if (victim == -1 || pages[victim] != null) {
            victim = i;
          }
        } else if (page.pageIndex == pageIndex) {
          lastUse[i] = ++clock;
          return page;
        } else if (victim == -1 || (pages[victim] != null && lastUse[i] < lastUse[victim])) {
          victim = i;
        }
      }
      Page page = pages[victim];
      if (page == null) {
        page = new Page(reader.config, reader.pageShift);
        pages[victim] = page;
      }
      page.pageIndex = -1;
      reader.decodePage(in, pageIndex, page);
      decodes++;
      lastUse[victim] = ++clock;
      return page;
    }
  }

  /**
   * Leaf ID (in value order) of leaf node {@code nodeID} in a tree of {@code numLeaves} leaves.
   * Deeper leaves are on the left: with {@code P = 2^ceil(log2 numLeaves)} the {@code 2 * numLeaves
   * - P} leaves at the deepest level come first.
   */
  static int leafIDOf(int nodeID, int numLeaves) {
    if (numLeaves == 1) {
      assert nodeID == 1;
      return 0;
    }
    final int p = Integer.highestOneBit(numLeaves - 1) << 1;
    final int deep = 2 * numLeaves - p;
    assert nodeID >= numLeaves && nodeID < 2 * numLeaves;
    return nodeID >= p ? nodeID - p : deep + nodeID - numLeaves;
  }

  @Override
  public PointTree getPointTree() throws IOException {
    return new SplitBKDPointTree(
        this,
        indexIn.slice("packedIndex", indexStartFP, numIndexBytes),
        dataIn.clone(),
        valIn.clone(),
        new PageCache(this, indexIn.clone()));
  }

  static final class SplitBKDPointTree implements PointTree {
    private final SplitBKDReader reader;
    private int nodeID;
    // during clone, the node root can be different to 1
    private final int nodeRoot;
    // level is 1-based so that we can do level-1 w/o checking each time:
    private int level;
    // used to read the packed tree off-heap
    private final IndexInput innerNodes;
    // used to read the doc IDs (.kdd) and the values (.kdv) of the leaves
    private final IndexInput leafNodes;
    private final IndexInput valNodes;
    // holds the minimum (left most) leaf block file pointer for each level we've recursed to:
    private final long[] leafBlockFPStack;
    // holds the address, in the off-heap index, after reading the node data of each level:
    private final int[] readNodeDataPositions;
    // holds the address, in the off-heap index, of the right-node of each level:
    private final int[] rightNodePositions;
    // holds the splitDim position for each level:
    private final int[] splitDimsPos;
    // see BKDReader.BKDPointTree
    private final boolean[] negativeDeltas;
    // holds the packed per-level split values
    private final byte[][] splitValuesStack;
    // holds the min / max cell bounds of the current node
    private final byte[] minPackedValue, maxPackedValue;
    // holds the previous value of the split dimension
    private final byte[][] splitDimValueStack;
    private final BKDConfig config;
    // number of leaves
    private final int leafNodeOffset;
    final long pointCount;
    // last node might not be fully populated
    private final int lastLeafNodePointCount;
    // right most leaf node ID
    private final int rightMostLeafNode;
    // helper objects for reading doc values, shared with clones
    private final byte[] scratchDataPackedValue;
    private final int[] commonPrefixLengths;
    private final ReaderDocIDSetIterator scratchIterator;
    private final DocIdsWriter docIdsWriter;
    // directory pages, shared with clones
    private final PageCache pageCache;
    // directory entry of the current leaf, valid while isLeafNode()
    private final byte[] leafMin, leafMax;
    private int leafID;
    private int leafCount;
    private long leafDocFP;
    private int leafDocLen;
    private long leafValFP;
    private int leafValLen;

    private SplitBKDPointTree(
        SplitBKDReader reader,
        IndexInput innerNodes,
        IndexInput leafNodes,
        IndexInput valNodes,
        PageCache pageCache)
        throws IOException {
      this(
          reader,
          innerNodes,
          leafNodes,
          valNodes,
          1,
          1,
          reader.minPackedValue,
          reader.maxPackedValue,
          new ReaderDocIDSetIterator(reader.config.maxPointsInLeafNode()),
          new byte[reader.config.packedBytesLength()],
          new int[reader.config.numDims()],
          pageCache);
      if (isLeafNode()) {
        // a single leaf: everything comes from the metadata, nothing is read
        leafBlockFPStack[level] = reader.dataStartFP;
        System.arraycopy(reader.minPackedValue, 0, leafMin, 0, leafMin.length);
        System.arraycopy(reader.maxPackedValue, 0, leafMax, 0, leafMax.length);
        leafID = 0;
        leafCount = Math.toIntExact(reader.pointCount);
        leafDocFP = reader.dataStartFP;
        leafDocLen = Math.toIntExact(reader.docDataEndFP - reader.dataStartFP);
        leafValFP = reader.valDataStartFP;
        leafValLen = Math.toIntExact(reader.valDataEndFP - reader.valDataStartFP);
      } else {
        // read root node
        readNodeData(false);
      }
    }

    private SplitBKDPointTree(
        SplitBKDReader reader,
        IndexInput innerNodes,
        IndexInput leafNodes,
        IndexInput valNodes,
        int nodeID,
        int level,
        byte[] minPackedValue,
        byte[] maxPackedValue,
        ReaderDocIDSetIterator scratchIterator,
        byte[] scratchDataPackedValue,
        int[] commonPrefixLengths,
        PageCache pageCache) {
      this.reader = reader;
      this.config = reader.config;
      this.nodeID = nodeID;
      this.nodeRoot = nodeID;
      this.level = level;
      leafNodeOffset = reader.numLeaves;
      this.innerNodes = innerNodes;
      this.leafNodes = leafNodes;
      this.valNodes = valNodes;
      this.minPackedValue = minPackedValue.clone();
      this.maxPackedValue = maxPackedValue.clone();
      int treeDepth = getTreeDepth(leafNodeOffset);
      splitDimValueStack = new byte[treeDepth][];
      splitValuesStack = new byte[treeDepth][];
      splitValuesStack[0] = new byte[config.packedIndexBytesLength()];
      leafBlockFPStack = new long[treeDepth + 1];
      readNodeDataPositions = new int[treeDepth + 1];
      rightNodePositions = new int[treeDepth];
      splitDimsPos = new int[treeDepth];
      negativeDeltas = new boolean[config.numIndexDims() * treeDepth];
      this.pointCount = reader.pointCount;
      rightMostLeafNode = (1 << treeDepth - 1) - 1;
      int lastLeafNodePointCount = Math.toIntExact(pointCount % config.maxPointsInLeafNode());
      this.lastLeafNodePointCount =
          lastLeafNodePointCount == 0 ? config.maxPointsInLeafNode() : lastLeafNodePointCount;
      this.scratchIterator = scratchIterator;
      this.commonPrefixLengths = commonPrefixLengths;
      this.scratchDataPackedValue = scratchDataPackedValue;
      this.docIdsWriter = scratchIterator.docIdsWriter;
      this.pageCache = pageCache;
      this.leafMin = new byte[config.packedIndexBytesLength()];
      this.leafMax = new byte[config.packedIndexBytesLength()];
    }

    @Override
    public PointTree clone() {
      SplitBKDPointTree index =
          new SplitBKDPointTree(
              reader,
              innerNodes.clone(),
              leafNodes.clone(),
              valNodes.clone(),
              nodeID,
              level,
              minPackedValue,
              maxPackedValue,
              scratchIterator,
              scratchDataPackedValue,
              commonPrefixLengths,
              pageCache);
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
      } else {
        System.arraycopy(leafMin, 0, index.leafMin, 0, leafMin.length);
        System.arraycopy(leafMax, 0, index.leafMax, 0, leafMax.length);
        index.leafID = leafID;
        index.leafCount = leafCount;
        index.leafDocFP = leafDocFP;
        index.leafDocLen = leafDocLen;
        index.leafValFP = leafValFP;
        index.leafValLen = leafValLen;
      }
      return index;
    }

    @Override
    public byte[] getMinPackedValue() {
      return isLeafNode() ? leafMin : minPackedValue;
    }

    @Override
    public byte[] getMaxPackedValue() {
      return isLeafNode() ? leafMax : maxPackedValue;
    }

    @Override
    public boolean moveToChild() throws IOException {
      if (isLeafNode()) {
        return false;
      }
      resetNodeDataPosition();
      pushBoundsLeft();
      pushLeft();
      if (isLeafNode()) {
        loadLeaf();
      }
      return true;
    }

    private void resetNodeDataPosition() throws IOException {
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
          >= 0;
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
          <= 0;
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
      if (isLeafNode()) {
        loadLeaf();
      }
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
      // the cell bounds were not touched at the leaf, so popping restores them as stock does
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

    /** Decodes the directory entry of the current leaf node, through the page cache. */
    private void loadLeaf() throws IOException {
      assert isLeafNode();
      leafID = leafIDOf(nodeID, leafNodeOffset);
      Page page = pageCache.get(leafID >>> reader.pageShift);
      int k = leafID & ((1 << reader.pageShift) - 1);
      int bytes = config.bytesPerDim();
      System.arraycopy(page.min, k * bytes, leafMin, 0, bytes);
      System.arraycopy(page.max, k * bytes, leafMax, 0, bytes);
      leafCount = page.count[k];
      leafDocFP = page.docFP[k];
      leafDocLen = page.docLen[k];
      leafValFP = page.valFP[k];
      leafValLen = page.valLen[k];
      assert leafDocFP == leafBlockFPStack[level]
          : "directory doc FP " + leafDocFP + " != index leaf FP " + leafBlockFPStack[level];
    }

    // package-private accessors for tests and prefetch planning
    int leafID() {
      assert isLeafNode();
      return leafID;
    }

    long leafDocFP() {
      assert isLeafNode();
      return leafDocFP;
    }

    int leafDocLength() {
      assert isLeafNode();
      return leafDocLen;
    }

    long leafValueFP() {
      assert isLeafNode();
      return leafValFP;
    }

    int leafValueLength() {
      assert isLeafNode();
      return leafValLen;
    }

    PageCache pageCache() {
      return pageCache;
    }

    boolean isLeaf() {
      return isLeafNode();
    }

    private int leftMostLeafNode() {
      int leftMostLeafNode = nodeID;
      while (leftMostLeafNode < leafNodeOffset) {
        leftMostLeafNode = leftMostLeafNode * 2;
      }
      return leftMostLeafNode;
    }

    private int numLeavesBelow() {
      int leftMostLeafNode = leftMostLeafNode();
      int rightMostLeafNode = nodeID;
      while (rightMostLeafNode < leafNodeOffset) {
        rightMostLeafNode = rightMostLeafNode * 2 + 1;
      }
      if (rightMostLeafNode >= leftMostLeafNode) {
        // both are on the same level
        return rightMostLeafNode - leftMostLeafNode + 1;
      } else {
        // left is one level deeper than right
        return rightMostLeafNode - leftMostLeafNode + 1 + leafNodeOffset;
      }
    }

    @Override
    public long size() {
      if (isLeafNode()) {
        return leafCount;
      }
      int rightMostLeafNode = nodeID;
      while (rightMostLeafNode < leafNodeOffset) {
        rightMostLeafNode = rightMostLeafNode * 2 + 1;
      }
      final int numLeaves = numLeavesBelow();
      return rightMostLeafNode == this.rightMostLeafNode
          ? (long) (numLeaves - 1) * config.maxPointsInLeafNode() + lastLeafNodePointCount
          : (long) numLeaves * config.maxPointsInLeafNode();
    }

    @Override
    public void visitDocIDs(PointValues.IntersectVisitor visitor) throws IOException {
      final long size = size();
      if (size <= Integer.MAX_VALUE) {
        visitor.grow((int) size);
      }
      if (isLeafNode()) {
        leafNodes.seek(leafDocFP);
        int count = leafNodes.readVInt();
        docIdsWriter.readInts(leafNodes, count, visitor, scratchIterator.docIDs);
      } else {
        // the leaves below this node are contiguous in the doc ID file, in leaf order
        leafNodes.seek(leafBlockFPStack[level]);
        final int numLeaves = numLeavesBelow();
        for (int i = 0; i < numLeaves; i++) {
          int count = leafNodes.readVInt();
          docIdsWriter.readInts(leafNodes, count, visitor, scratchIterator.docIDs);
        }
      }
    }

    @Override
    public void visitDocValues(PointValues.IntersectVisitor visitor) throws IOException {
      final int numLeaves;
      if (isLeafNode()) {
        leafNodes.seek(leafDocFP);
        valNodes.seek(leafValFP);
        numLeaves = 1;
      } else {
        // the leaves below this node are contiguous in both files, in leaf order
        int firstLeafID = leafIDOf(leftMostLeafNode(), leafNodeOffset);
        Page page = pageCache.get(firstLeafID >>> reader.pageShift);
        leafNodes.seek(leafBlockFPStack[level]);
        valNodes.seek(page.valFP[firstLeafID & ((1 << reader.pageShift) - 1)]);
        numLeaves = numLeavesBelow();
      }
      for (int i = 0; i < numLeaves; i++) {
        int count = leafNodes.readVInt();
        docIdsWriter.readInts(leafNodes, count, scratchIterator.docIDs);
        visitLeafValues(valNodes, count, visitor);
      }
    }

    private void visitLeafValues(IndexInput in, int count, PointValues.IntersectVisitor visitor)
        throws IOException {
      readCommonPrefixes(commonPrefixLengths, scratchDataPackedValue, in);
      int compressedDim = readCompressedDim(in);
      visitor.grow(count);
      if (compressedDim == -1) {
        // all values are the same
        scratchIterator.reset(0, count);
        visitor.visit(scratchIterator, scratchDataPackedValue);
      } else if (compressedDim == -2) {
        // low cardinality values
        visitSparseRawDocValues(commonPrefixLengths, scratchDataPackedValue, in, count, visitor);
      } else {
        // high cardinality
        visitCompressedDocValues(
            commonPrefixLengths, scratchDataPackedValue, in, count, visitor, compressedDim);
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

    private static int getTreeDepth(int numLeaves) {
      return MathUtil.log(numLeaves, 2) + 2;
    }

    // read cardinality and point
    private void visitSparseRawDocValues(
        int[] commonPrefixLengths,
        byte[] scratchPackedValue,
        IndexInput in,
        int count,
        PointValues.IntersectVisitor visitor)
        throws IOException {
      int i;
      for (i = 0; i < count; ) {
        int length = in.readVInt();
        for (int dim = 0; dim < config.numDims(); dim++) {
          int prefix = commonPrefixLengths[dim];
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

    private void visitCompressedDocValues(
        int[] commonPrefixLengths,
        byte[] scratchPackedValue,
        IndexInput in,
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
      if (compressedDim < -2 || compressedDim >= config.numDims()) {
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
  private static final class ReaderDocIDSetIterator extends AbstractDocIdSetIterator {

    private int idx;
    private int length;
    private int offset;
    final int[] docIDs;
    private final DocIdsWriter docIdsWriter;

    ReaderDocIDSetIterator(int maxPointsInLeafNode) {
      this.docIDs = new int[maxPointsInLeafNode];
      this.docIdsWriter =
          new DocIdsWriter(maxPointsInLeafNode, SplitBKDWriter.BLOCK_ENCODING_VERSION);
    }

    private void reset(int offset, int length) {
      this.offset = offset;
      this.length = length;
      assert offset + length <= docIDs.length;
      this.doc = -1;
      this.idx = 0;
    }

    @Override
    public int nextDoc() throws IOException {
      if (idx == length) {
        doc = DocIdSetIterator.NO_MORE_DOCS;
      } else {
        doc = docIDs[offset + idx];
        idx++;
      }
      return doc;
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
