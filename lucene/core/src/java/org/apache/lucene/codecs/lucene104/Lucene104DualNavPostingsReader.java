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
package org.apache.lucene.codecs.lucene104;

import static org.apache.lucene.codecs.lucene104.ForUtil.BLOCK_SIZE;
import static org.apache.lucene.codecs.lucene104.Lucene104DualNavPostingsFormat.NAV_CODEC;
import static org.apache.lucene.codecs.lucene104.Lucene104DualNavPostingsFormat.NAV_META_CODEC;
import static org.apache.lucene.codecs.lucene104.Lucene104PostingsFormat.LEVEL1_NUM_DOCS;
import static org.apache.lucene.codecs.lucene104.Lucene104PostingsFormat.META_CODEC;
import static org.apache.lucene.codecs.lucene104.Lucene104PostingsReader.VECTORIZATION_PROVIDER;
import static org.apache.lucene.codecs.lucene104.Lucene104PostingsReader.prefixSum;
import static org.apache.lucene.codecs.lucene104.Lucene104PostingsReader.readVInt15;
import static org.apache.lucene.codecs.lucene104.Lucene104PostingsReader.readVLong15;

import java.io.IOException;
import java.util.Arrays;
import org.apache.lucene.codecs.BlockTermState;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.PostingsReaderBase;
import org.apache.lucene.codecs.lucene104.Lucene104DualNavPostingsFormat.DualNavTermState;
import org.apache.lucene.codecs.lucene104.Lucene104DualNavPostingsFormat.ReadMode;
import org.apache.lucene.codecs.lucene104.Lucene104PostingsFormat.IntBlockTermState;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FreqAndNormBuffer;
import org.apache.lucene.index.Impacts;
import org.apache.lucene.index.ImpactsEnum;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.internal.vectorization.PostingDecodingUtil;
import org.apache.lucene.search.DisjunctionPrefetch;
import org.apache.lucene.search.DocAndFloatFeatureBuffer;
import org.apache.lucene.store.ByteArrayDataInput;
import org.apache.lucene.store.ChecksumIndexInput;
import org.apache.lucene.store.DataInput;
import org.apache.lucene.store.FileDataHint;
import org.apache.lucene.store.FileTypeHint;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.VectorUtil;

/**
 * Reader for {@link Lucene104DualNavPostingsFormat}. In {@link ReadMode#DOC} it delegates to the
 * stock {@link Lucene104PostingsReader}, which reads the unchanged .doc/.pos/.pay files. In {@link
 * ReadMode#NAV} it reads skip data from .nav and only block payloads from .doc.
 */
public final class Lucene104DualNavPostingsReader extends PostingsReaderBase {

  private final Lucene104PostingsReader stock;
  private final IndexInput docIn;
  private final IndexInput navIn;
  private final IndexInput posIn;
  private final IndexInput payIn;

  private final int maxNumImpactsAtLevel0;
  private final int maxImpactNumBytesAtLevel0;
  private final int maxNumImpactsAtLevel1;
  private final int maxImpactNumBytesAtLevel1;

  /** Sole constructor. */
  public Lucene104DualNavPostingsReader(SegmentReadState state) throws IOException {
    // .psm is stock: read the impact sizes the nav enum needs; the stock reader validates the rest
    final String metaName =
        IndexFileNames.segmentFileName(
            state.segmentInfo.name, state.segmentSuffix, Lucene104PostingsFormat.META_EXTENSION);
    final int version;
    try (ChecksumIndexInput metaIn = state.directory.openChecksumInput(metaName)) {
      version =
          CodecUtil.checkIndexHeader(
              metaIn,
              META_CODEC,
              Lucene104PostingsFormat.VERSION_START,
              Lucene104PostingsFormat.VERSION_CURRENT,
              state.segmentInfo.getId(),
              state.segmentSuffix);
      maxNumImpactsAtLevel0 = metaIn.readInt();
      maxImpactNumBytesAtLevel0 = metaIn.readInt();
      maxNumImpactsAtLevel1 = metaIn.readInt();
      maxImpactNumBytesAtLevel1 = metaIn.readInt();
      // the file lengths and footer are checked by the stock reader
    }
    final long expectedNavFileLength;
    final String navMetaName =
        IndexFileNames.segmentFileName(
            state.segmentInfo.name,
            state.segmentSuffix,
            Lucene104DualNavPostingsFormat.NAV_META_EXTENSION);
    try (ChecksumIndexInput navMetaIn = state.directory.openChecksumInput(navMetaName)) {
      Throwable priorE = null;
      long length = -1;
      try {
        CodecUtil.checkIndexHeader(
            navMetaIn,
            NAV_META_CODEC,
            Lucene104DualNavPostingsFormat.VERSION_START,
            Lucene104DualNavPostingsFormat.VERSION_CURRENT,
            state.segmentInfo.getId(),
            state.segmentSuffix);
        length = navMetaIn.readLong();
      } catch (Throwable t) {
        priorE = t;
      } finally {
        CodecUtil.checkFooter(navMetaIn, priorE);
      }
      expectedNavFileLength = length;
    }

    Lucene104PostingsReader stock = null;
    IndexInput docIn = null, navIn = null, posIn = null, payIn = null;
    boolean success = false;
    try {
      stock = new Lucene104PostingsReader(state);
      // the nav enum's own handles on the stock files; with a caching directory they share its
      // cache
      docIn =
          state.directory.openInput(
              IndexFileNames.segmentFileName(
                  state.segmentInfo.name,
                  state.segmentSuffix,
                  Lucene104PostingsFormat.DOC_EXTENSION),
              state.context.withHints(FileTypeHint.DATA, FileDataHint.POSTINGS));
      navIn =
          state.directory.openInput(
              IndexFileNames.segmentFileName(
                  state.segmentInfo.name,
                  state.segmentSuffix,
                  Lucene104DualNavPostingsFormat.NAV_EXTENSION),
              state.context.withHints(FileTypeHint.DATA, FileDataHint.POSTINGS));
      CodecUtil.checkIndexHeader(
          navIn,
          NAV_CODEC,
          Lucene104DualNavPostingsFormat.VERSION_START,
          Lucene104DualNavPostingsFormat.VERSION_CURRENT,
          state.segmentInfo.getId(),
          state.segmentSuffix);
      CodecUtil.retrieveChecksum(navIn, expectedNavFileLength);
      if (state.fieldInfos.hasProx()) {
        posIn =
            state.directory.openInput(
                IndexFileNames.segmentFileName(
                    state.segmentInfo.name,
                    state.segmentSuffix,
                    Lucene104PostingsFormat.POS_EXTENSION),
                state.context.withHints(FileTypeHint.DATA));
        if (state.fieldInfos.hasPayloads() || state.fieldInfos.hasOffsets()) {
          payIn =
              state.directory.openInput(
                  IndexFileNames.segmentFileName(
                      state.segmentInfo.name,
                      state.segmentSuffix,
                      Lucene104PostingsFormat.PAY_EXTENSION),
                  state.context.withHints(FileTypeHint.DATA));
        }
      }
      success = true;
    } finally {
      if (success == false) {
        IOUtils.closeWhileHandlingException(stock, docIn, navIn, posIn, payIn);
      }
    }
    assert version >= 0;
    this.stock = stock;
    this.docIn = docIn;
    this.navIn = navIn;
    this.posIn = posIn;
    this.payIn = payIn;
  }

  @Override
  public void init(IndexInput termsIn, SegmentReadState state) throws IOException {
    stock.init(termsIn, state);
  }

  @Override
  public BlockTermState newTermState() {
    return new DualNavTermState();
  }

  @Override
  public void close() throws IOException {
    IOUtils.close(stock, docIn, navIn, posIn, payIn);
  }

  @Override
  public void decodeTerm(
      DataInput in, FieldInfo fieldInfo, BlockTermState _termState, boolean absolute)
      throws IOException {
    final DualNavTermState termState = (DualNavTermState) _termState;
    termState.syncStats();
    // the stock metadata comes first and is decoded by the stock reader, unchanged
    stock.decodeTerm(in, fieldInfo, termState.base, absolute);
    if (absolute) {
      termState.navStartFP = 0;
    }
    if (termState.docFreq >= BLOCK_SIZE) {
      termState.navStartFP += in.readVLong();
      termState.docLength = in.readVLong();
    } else {
      termState.docLength = -1;
    }
  }

  @Override
  public PostingsEnum postings(
      FieldInfo fieldInfo, BlockTermState termState, PostingsEnum reuse, int flags)
      throws IOException {
    final DualNavTermState state = (DualNavTermState) termState;
    if (Lucene104DualNavPostingsFormat.getReadMode() == ReadMode.DOC) {
      state.syncStats();
      return stock.postings(fieldInfo, state.base, reuse, flags);
    }
    return (reuse instanceof BlockPostingsEnum everythingEnum
                && everythingEnum.canReuse(docIn, fieldInfo, flags, false)
            ? everythingEnum
            : new BlockPostingsEnum(fieldInfo, flags, false))
        .reset(state, flags);
  }

  @Override
  public ImpactsEnum impacts(FieldInfo fieldInfo, BlockTermState termState, int flags)
      throws IOException {
    final DualNavTermState state = (DualNavTermState) termState;
    if (Lucene104DualNavPostingsFormat.getReadMode() == ReadMode.DOC) {
      state.syncStats();
      return stock.impacts(fieldInfo, state.base, flags);
    }
    return new BlockPostingsEnum(fieldInfo, flags, true).reset(state, flags);
  }

  @Override
  public void checkIntegrity() throws IOException {
    stock.checkIntegrity();
    CodecUtil.checksumEntireFile(navIn);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "(" + stock + ")";
  }

  private static int sumOverRange(int[] arr, int start, int end) {
    int res = 0;
    for (int i = start; i < end; i++) {
      res += arr[i];
    }
    return res;
  }

  private static void prefetchPostings(IndexInput docIn, IntBlockTermState state)
      throws IOException {
    assert state.docFreq > 1;
    if (docIn.getFilePointer() != state.docStartFP) {
      docIn.prefetch(state.docStartFP, 1);
    }
  }

  /**
   * Postings enum of {@link ReadMode#NAV}: the skipping logic of {@link Lucene104PostingsReader}
   * with skip entries read from .nav. Each .nav level-0 entry gives the gap from the end of the
   * previous payload to this block's payload in .doc (the stock skip header in between is not read)
   * and the payload length.
   */
  final class BlockPostingsEnum extends ImpactsEnum {

    private enum DeltaEncoding {
      /**
       * Deltas between consecutive docs are stored as packed integers, ie. the block is encoded
       * using Frame Of Reference (FOR).
       */
      PACKED,
      /**
       * Deltas between consecutive docs are stored using unary coding, ie. {@code delta-1} zero
       * bits followed by a one bit, ie. the block is encoded as an offset plus a bit set.
       */
      UNARY
    }

    private ForUtil forUtil;
    private PForUtil pforUtil;

    /* Variables that store the content of a block and the current position within this block */
    /* Shared variables */
    private DeltaEncoding encoding;
    private int doc; // doc we last read

    /* Variables when the block is stored as packed deltas (Frame Of Reference) */
    private final int[] docBuffer = new int[BLOCK_SIZE];

    /* Variables when the block is stored as a bit set */
    // Since we use a bit set when it's more storage-efficient, the bit set cannot have more than
    // BLOCK_SIZE*32 bits, which is the maximum possible storage requirement with FOR.
    private final FixedBitSet docBitSet = new FixedBitSet(BLOCK_SIZE * Integer.SIZE);
    private int docBitSetBase;
    // Reuse docBuffer for cumulative pop counts of the words of the bit set.
    private final int[] docCumulativeWordPopCounts = docBuffer;

    // level 0 skip data
    private int level0LastDocID;
    private long level0DocEndFP;

    // level 1 skip data
    private int level1LastDocID;
    private long level1DocEndFP;
    private long level1NavEndFP;
    private int level1DocCountUpto;

    private int docFreq; // number of docs in this posting list
    private long totalTermFreq; // sum of freqBuffer in this posting list (or docFreq when omitted)

    private int singletonDocID; // docid when there is a single pulsed posting, otherwise -1

    private int docCountLeft; // number of remaining docs in this postings list
    private int prevDocID; // last doc ID of the previous block

    private int docBufferSize;
    private int docBufferUpto;

    private IndexInput docIn;
    private PostingDecodingUtil docInUtil;
    // skip data: only opened for terms that have at least one full block
    private IndexInput navIn;

    private final int[] freqBuffer = new int[BLOCK_SIZE];
    private final int[] posDeltaBuffer;

    private final int[] payloadLengthBuffer;
    private final int[] offsetStartDeltaBuffer;
    private final int[] offsetLengthBuffer;

    private byte[] payloadBytes;
    private int payloadByteUpto;
    private int payloadLength;

    private int lastStartOffset;
    private int startOffset;
    private int endOffset;

    private int posBufferUpto;

    final IndexInput posIn;
    final PostingDecodingUtil posInUtil;
    final IndexInput payIn;
    final PostingDecodingUtil payInUtil;
    final BytesRef payload;

    final IndexOptions options;
    final boolean indexHasFreq;
    final boolean indexHasPos;
    final boolean indexHasOffsets;
    final boolean indexHasPayloads;
    final boolean indexHasOffsetsOrPayloads;

    final int flags;
    final boolean needsFreq;
    final boolean needsPos;
    final boolean needsOffsets;
    final boolean needsPayloads;
    final boolean needsOffsetsOrPayloads;
    final boolean needsImpacts;
    final boolean needsDocsAndFreqsOnly;

    private long freqFP; // offset of the freq block

    private int position; // current position

    // value of docBufferUpto on the last doc ID when positions have been read
    private int posDocBufferUpto;

    // how many positions "behind" we are; nextPosition must
    // skip these to "catch up":
    private int posPendingCount;

    // File pointer where the last (vInt encoded) pos delta
    // block is.  We need this to know whether to bulk
    // decode vs vInt decode the block:
    private long lastPosBlockFP;

    // level 0 skip data
    private long level0PosEndFP;
    private int level0BlockPosUpto;
    private long level0PayEndFP;
    private int level0BlockPayUpto;
    private final BytesRef level0SerializedImpacts;

    // level 1 skip data
    private long level1PosEndFP;
    private int level1BlockPosUpto;
    private long level1PayEndFP;
    private int level1BlockPayUpto;
    private final BytesRef level1SerializedImpacts;

    private final FreqAndNormBuffer impactBuffer;

    // true if we shallow-advanced to a new block that we have not decoded yet
    private boolean needsRefilling;

    public BlockPostingsEnum(FieldInfo fieldInfo, int flags, boolean needsImpacts)
        throws IOException {
      options = fieldInfo.getIndexOptions();
      indexHasFreq = options.subsumes(IndexOptions.DOCS_AND_FREQS);
      indexHasPos = options.subsumes(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
      indexHasOffsets = options.subsumes(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
      indexHasPayloads = fieldInfo.hasPayloads();
      indexHasOffsetsOrPayloads = indexHasOffsets || indexHasPayloads;

      this.flags = flags;
      needsFreq = indexHasFreq && PostingsEnum.featureRequested(flags, PostingsEnum.FREQS);
      needsPos = indexHasPos && PostingsEnum.featureRequested(flags, PostingsEnum.POSITIONS);
      needsOffsets = indexHasOffsets && PostingsEnum.featureRequested(flags, PostingsEnum.OFFSETS);
      needsPayloads =
          indexHasPayloads && PostingsEnum.featureRequested(flags, PostingsEnum.PAYLOADS);
      needsOffsetsOrPayloads = needsOffsets || needsPayloads;
      this.needsImpacts = needsImpacts;
      needsDocsAndFreqsOnly = needsPos == false && needsImpacts == false;

      if (needsFreq == false) {
        Arrays.fill(freqBuffer, 1);
      }

      if (needsImpacts) {
        impactBuffer = new FreqAndNormBuffer();
        int capacity = 1; // for dummy impacts
        if (needsFreq) {
          capacity = Math.max(maxNumImpactsAtLevel0, capacity);
          capacity = Math.max(maxNumImpactsAtLevel1, capacity);
        }
        impactBuffer.growNoCopy(capacity);
      } else {
        impactBuffer = null;
      }

      if (needsFreq && needsImpacts) {
        level0SerializedImpacts = new BytesRef(maxImpactNumBytesAtLevel0);
        level1SerializedImpacts = new BytesRef(maxImpactNumBytesAtLevel1);
      } else {
        level0SerializedImpacts = null;
        level1SerializedImpacts = null;
      }

      if (needsPos) {
        this.posIn = Lucene104DualNavPostingsReader.this.posIn.clone();
        posInUtil = VECTORIZATION_PROVIDER.newPostingDecodingUtil(posIn);
        posDeltaBuffer = new int[BLOCK_SIZE];
      } else {
        this.posIn = null;
        this.posInUtil = null;
        posDeltaBuffer = null;
      }

      if (needsOffsets || needsPayloads) {
        this.payIn = Lucene104DualNavPostingsReader.this.payIn.clone();
        payInUtil = VECTORIZATION_PROVIDER.newPostingDecodingUtil(payIn);
      } else {
        this.payIn = null;
        payInUtil = null;
      }

      if (needsOffsets) {
        offsetStartDeltaBuffer = new int[BLOCK_SIZE];
        offsetLengthBuffer = new int[BLOCK_SIZE];
      } else {
        offsetStartDeltaBuffer = null;
        offsetLengthBuffer = null;
        startOffset = -1;
        endOffset = -1;
      }

      if (indexHasPayloads) {
        payloadLengthBuffer = new int[BLOCK_SIZE];
        payloadBytes = new byte[128];
        payload = new BytesRef();
      } else {
        payloadLengthBuffer = null;
        payloadBytes = null;
        payload = null;
      }
    }

    public boolean canReuse(
        IndexInput docIn, FieldInfo fieldInfo, int flags, boolean needsImpacts) {
      return docIn == Lucene104DualNavPostingsReader.this.docIn
          && options == fieldInfo.getIndexOptions()
          && indexHasPayloads == fieldInfo.hasPayloads()
          && this.flags == flags
          && this.needsImpacts == needsImpacts;
    }

    public BlockPostingsEnum reset(DualNavTermState dualState, int flags) throws IOException {
      final IntBlockTermState termState = dualState.base;
      planInitialized = false;
      planTermDocStartFP = termState.docStartFP;
      planTermNavStartFP = dualState.navStartFP;
      planTermDocLength = dualState.docLength;
      docFreq = termState.docFreq;
      singletonDocID = termState.singletonDocID;
      if (docFreq > 1) {
        if (docIn == null) {
          // lazy init
          docIn = Lucene104DualNavPostingsReader.this.docIn.clone();
          docInUtil = VECTORIZATION_PROVIDER.newPostingDecodingUtil(docIn);
        }
        if (docFreq >= BLOCK_SIZE && navIn == null) {
          navIn = Lucene104DualNavPostingsReader.this.navIn.clone();
        }
        prefetchPostings(docIn, termState);
      }

      if (forUtil == null && docFreq >= BLOCK_SIZE) {
        forUtil = new ForUtil();
      }
      totalTermFreq = indexHasFreq ? termState.totalTermFreq : termState.docFreq;
      if (needsFreq && pforUtil == null && totalTermFreq >= BLOCK_SIZE) {
        if (forUtil == null) {
          forUtil = new ForUtil();
        }
        pforUtil = new PForUtil(forUtil);
      }

      // Where this term's postings start in the .pos file:
      final long posTermStartFP = termState.posStartFP;
      // Where this term's payloads/offsets start in the .pay
      // file:
      final long payTermStartFP = termState.payStartFP;
      if (posIn != null) {
        posIn.seek(posTermStartFP);
        if (payIn != null) {
          payIn.seek(payTermStartFP);
        }
      }
      level1PosEndFP = posTermStartFP;
      level1PayEndFP = payTermStartFP;
      level0PosEndFP = posTermStartFP;
      level0PayEndFP = payTermStartFP;
      posPendingCount = 0;
      payloadByteUpto = 0;
      if (termState.totalTermFreq < BLOCK_SIZE) {
        lastPosBlockFP = posTermStartFP;
      } else if (termState.totalTermFreq == BLOCK_SIZE) {
        lastPosBlockFP = -1;
      } else {
        lastPosBlockFP = posTermStartFP + termState.lastPosBlockOffset;
      }

      level1BlockPosUpto = 0;
      level1BlockPayUpto = 0;
      level0BlockPosUpto = 0;
      level0BlockPayUpto = 0;
      posBufferUpto = BLOCK_SIZE;

      doc = -1;
      prevDocID = -1;
      docCountLeft = docFreq;
      freqFP = -1L;
      level0LastDocID = -1;
      if (docFreq < LEVEL1_NUM_DOCS) {
        level1LastDocID = NO_MORE_DOCS;
        if (docFreq > 1) {
          docIn.seek(termState.docStartFP);
        }
        if (docFreq >= BLOCK_SIZE) {
          navIn.seek(dualState.navStartFP);
        }
      } else {
        level1LastDocID = -1;
        level1DocEndFP = termState.docStartFP;
        level1NavEndFP = dualState.navStartFP;
      }
      level1DocCountUpto = 0;
      docBufferSize = BLOCK_SIZE;
      docBufferUpto = BLOCK_SIZE;
      posDocBufferUpto = BLOCK_SIZE;

      return this;
    }

    @Override
    public int docID() {
      return doc;
    }

    @Override
    public int freq() throws IOException {
      if (freqFP != -1) {
        docIn.seek(freqFP);
        pforUtil.decode(docInUtil, freqBuffer);
        freqFP = -1;
      }
      return freqBuffer[docBufferUpto - 1];
    }

    private void refillFullBlock() throws IOException {
      int bitsPerValue = docIn.readByte();
      if (bitsPerValue > 0) {
        // block is encoded as 256 packed integers that record the delta between doc IDs
        forUtil.decode(bitsPerValue, docInUtil, docBuffer);
        prefixSum(docBuffer, BLOCK_SIZE, prevDocID);
        encoding = DeltaEncoding.PACKED;
      } else {
        // block is encoded as a bit set
        assert level0LastDocID != NO_MORE_DOCS;
        docBitSetBase = prevDocID + 1;
        int numLongs;
        if (bitsPerValue == 0) {
          // 0 is used to record that all 256 docs in the block are consecutive
          numLongs = BLOCK_SIZE / Long.SIZE; // 2
          docBitSet.set(0, BLOCK_SIZE);
        } else {
          numLongs = -bitsPerValue;
          docIn.readLongs(docBitSet.getBits(), 0, numLongs);
        }
        if (needsFreq) {
          // Note: we know that BLOCK_SIZE bits are set, so no need to compute the cumulative pop
          // count at the last index, it will be BLOCK_SIZE.
          // Note: this for loop auto-vectorizes
          for (int i = 0; i < numLongs - 1; ++i) {
            docCumulativeWordPopCounts[i] = Long.bitCount(docBitSet.getBits()[i]);
          }
          prefixSum(docCumulativeWordPopCounts, numLongs - 1, 0);
          docCumulativeWordPopCounts[numLongs - 1] = BLOCK_SIZE;
          assert docCumulativeWordPopCounts[numLongs - 2]
                  + Long.bitCount(docBitSet.getBits()[numLongs - 1])
              == BLOCK_SIZE;
        }
        encoding = DeltaEncoding.UNARY;
      }
      if (indexHasFreq) {
        if (needsFreq) {
          freqFP = docIn.getFilePointer();
        }
        PForUtil.skip(docIn);
      }
      docCountLeft -= BLOCK_SIZE;
      prevDocID = docBuffer[BLOCK_SIZE - 1];
      docBufferUpto = 0;
      posDocBufferUpto = 0;
    }

    private void refillRemainder() throws IOException {
      assert docCountLeft >= 0 && docCountLeft < BLOCK_SIZE;
      if (docFreq == 1) {
        docBuffer[0] = singletonDocID;
        freqBuffer[0] = (int) totalTermFreq;
        docBuffer[1] = NO_MORE_DOCS;
        assert freqFP == -1;
        docCountLeft = 0;
        docBufferSize = 1;
      } else {
        // Read vInts:
        PostingsUtil.readVIntBlock(
            docIn, docBuffer, freqBuffer, docCountLeft, indexHasFreq, needsFreq);
        prefixSum(docBuffer, docCountLeft, prevDocID);
        docBuffer[docCountLeft] = NO_MORE_DOCS;
        freqFP = -1L;
        docBufferSize = docCountLeft;
        docCountLeft = 0;
      }
      prevDocID = docBuffer[BLOCK_SIZE - 1];
      docBufferUpto = 0;
      posDocBufferUpto = 0;
      encoding = DeltaEncoding.PACKED;
      assert docBuffer[docBufferSize] == NO_MORE_DOCS;
    }

    private void refillDocs() throws IOException {
      assert docCountLeft >= 0;

      if (docCountLeft >= BLOCK_SIZE) {
        refillFullBlock();
      } else {
        refillRemainder();
      }
    }

    // Skip data lives in navIn; docIn holds only block payloads. Every method below mirrors the
    // Lucene104PostingsReader method of the same name. The difference is that skip entries are read
    // from navIn, and a block's end in docIn is its start plus the payload length from its entry.

    private void skipLevel1To(int target) throws IOException {
      while (true) {
        prevDocID = level1LastDocID;
        level0LastDocID = level1LastDocID;
        docIn.seek(level1DocEndFP);
        navIn.seek(level1NavEndFP);
        level0PosEndFP = level1PosEndFP;
        level0BlockPosUpto = level1BlockPosUpto;
        level0PayEndFP = level1PayEndFP;
        level0BlockPayUpto = level1BlockPayUpto;
        docCountLeft = docFreq - level1DocCountUpto;
        level1DocCountUpto += LEVEL1_NUM_DOCS;

        if (docCountLeft < LEVEL1_NUM_DOCS) {
          level1LastDocID = NO_MORE_DOCS;
          break;
        }

        level1LastDocID += navIn.readVInt();
        long navLength = navIn.readVLong();
        level1NavEndFP = navLength + navIn.getFilePointer();
        level1DocEndFP = docIn.getFilePointer() + navIn.readVLong();

        if (indexHasFreq) {
          long skip1EndFP = navIn.readShort() + navIn.getFilePointer();
          int numImpactBytes = navIn.readShort();
          if (needsImpacts && level1LastDocID >= target) {
            navIn.readBytes(level1SerializedImpacts.bytes, 0, numImpactBytes);
            level1SerializedImpacts.length = numImpactBytes;
          } else {
            navIn.skipBytes(numImpactBytes);
          }
          if (indexHasPos) {
            level1PosEndFP += navIn.readVLong();
            level1BlockPosUpto = navIn.readByte() & 0xFF;
            if (indexHasOffsetsOrPayloads) {
              level1PayEndFP += navIn.readVLong();
              level1BlockPayUpto = navIn.readVInt();
            }
          }
          assert navIn.getFilePointer() == skip1EndFP;
        }

        if (level1LastDocID >= target) {
          break;
        }
      }
    }

    private void doMoveToNextLevel0Block() throws IOException {
      assert doc == level0LastDocID;
      if (posIn != null) {
        if (level0PosEndFP >= posIn.getFilePointer()) {
          posIn.seek(level0PosEndFP);
          posPendingCount = level0BlockPosUpto;
          if (payIn != null) {
            assert level0PayEndFP >= payIn.getFilePointer();
            payIn.seek(level0PayEndFP);
            payloadByteUpto = level0BlockPayUpto;
          }
          posBufferUpto = BLOCK_SIZE;
        } else {
          assert freqFP == -1L;
          posPendingCount += sumOverRange(freqBuffer, posDocBufferUpto, BLOCK_SIZE);
        }
      }

      if (docCountLeft >= BLOCK_SIZE) {
        navIn.readVLong(); // level0NumBytes
        int docDelta = readVInt15(navIn);
        level0LastDocID += docDelta;
        // .doc has this block's stock skip header before its payload; jump over it
        docIn.seek(docIn.getFilePointer() + navIn.readVInt());
        long payloadLength = readVLong15(navIn);
        level0DocEndFP = docIn.getFilePointer() + payloadLength;
        if (indexHasFreq) {
          int numImpactBytes = navIn.readVInt();
          if (needsImpacts) {
            navIn.readBytes(level0SerializedImpacts.bytes, 0, numImpactBytes);
            level0SerializedImpacts.length = numImpactBytes;
          } else {
            navIn.skipBytes(numImpactBytes);
          }

          if (indexHasPos) {
            level0PosEndFP += navIn.readVLong();
            level0BlockPosUpto = navIn.readByte() & 0xFF;
            if (indexHasOffsetsOrPayloads) {
              level0PayEndFP += navIn.readVLong();
              level0BlockPayUpto = navIn.readVInt();
            }
          }
        }
        refillFullBlock();
      } else {
        level0LastDocID = NO_MORE_DOCS;
        refillRemainder();
      }
    }

    private void moveToNextLevel0Block() throws IOException {
      if (doc == level1LastDocID) { // advance level 1 skip data
        skipLevel1To(doc + 1);
      }

      // Now advance level 0 skip data
      prevDocID = level0LastDocID;

      if (needsDocsAndFreqsOnly && docCountLeft >= BLOCK_SIZE) {
        // Optimize the common path for exhaustive evaluation
        long level0NumBytes = navIn.readVLong();
        long level0End = navIn.getFilePointer() + level0NumBytes;
        level0LastDocID += readVInt15(navIn);
        docIn.seek(docIn.getFilePointer() + navIn.readVInt()); // skip the stock header in .doc
        navIn.seek(level0End);
        refillFullBlock();
      } else {
        doMoveToNextLevel0Block();
      }
    }

    private void readLevel0PosData() throws IOException {
      level0PosEndFP += navIn.readVLong();
      level0BlockPosUpto = navIn.readByte() & 0xFF;
      if (indexHasOffsetsOrPayloads) {
        level0PayEndFP += navIn.readVLong();
        level0BlockPayUpto = navIn.readVInt();
      }
    }

    private void seekPosData(long posFP, int posUpto, long payFP, int payUpto) throws IOException {
      // If nextBlockPosFP is less than the current FP, it means that the block of positions for
      // the first docs of the next block are already decoded. In this case we just accumulate
      // frequencies into posPendingCount instead of seeking backwards and decoding the same pos
      // block again.
      if (posFP >= posIn.getFilePointer()) {
        posIn.seek(posFP);
        posPendingCount = posUpto;
        if (payIn != null) { // needs payloads or offsets
          assert level0PayEndFP >= payIn.getFilePointer();
          payIn.seek(payFP);
          payloadByteUpto = payUpto;
        }
        posBufferUpto = BLOCK_SIZE;
      } else {
        posPendingCount += sumOverRange(freqBuffer, posDocBufferUpto, BLOCK_SIZE);
      }
    }

    private void skipLevel0To(int target) throws IOException {
      long posFP;
      int posUpto;
      long payFP;
      int payUpto;

      while (true) {
        prevDocID = level0LastDocID;

        posFP = level0PosEndFP;
        posUpto = level0BlockPosUpto;
        payFP = level0PayEndFP;
        payUpto = level0BlockPayUpto;

        if (docCountLeft >= BLOCK_SIZE) {
          long numSkipBytes = navIn.readVLong();
          long skip0End = navIn.getFilePointer() + numSkipBytes;
          int docDelta = readVInt15(navIn);
          level0LastDocID += docDelta;
          boolean found = target <= level0LastDocID;
          // docIn is at the end of the previous payload; this block's payload starts after its
          // stock skip header
          final long payloadStart = docIn.getFilePointer() + navIn.readVInt();
          long payloadLength = readVLong15(navIn);
          level0DocEndFP = payloadStart + payloadLength;

          if (indexHasFreq) {
            if (found == false && needsPos == false) {
              navIn.seek(skip0End);
            } else {
              int numImpactBytes = navIn.readVInt();
              if (needsImpacts && found) {
                navIn.readBytes(level0SerializedImpacts.bytes, 0, numImpactBytes);
                level0SerializedImpacts.length = numImpactBytes;
              } else {
                navIn.skipBytes(numImpactBytes);
              }

              if (needsPos) {
                readLevel0PosData();
              } else {
                navIn.seek(skip0End);
              }
            }
          }

          if (found) {
            docIn.seek(payloadStart);
            break;
          }

          docIn.seek(level0DocEndFP);
          docCountLeft -= BLOCK_SIZE;
        } else {
          level0LastDocID = NO_MORE_DOCS;
          break;
        }
      }

      if (posIn != null) { // needs positions
        seekPosData(posFP, posUpto, payFP, payUpto);
      }
    }

    @Override
    public void advanceShallow(int target) throws IOException {
      if (target > level0LastDocID) { // advance level 0 skip data
        doAdvanceShallow(target);
        needsRefilling = true;
      }
    }

    private void doAdvanceShallow(int target) throws IOException {
      if (target > level1LastDocID) { // advance skip data on level 1
        skipLevel1To(target);
      } else if (needsRefilling) {
        docIn.seek(level0DocEndFP);
        docCountLeft -= BLOCK_SIZE;
      }

      skipLevel0To(target);
    }

    @Override
    public int nextDoc() throws IOException {
      if (doc == level0LastDocID || needsRefilling) {
        if (needsRefilling) {
          refillDocs();
          needsRefilling = false;
        } else {
          moveToNextLevel0Block();
        }
      }

      switch (encoding) {
        case PACKED:
          doc = docBuffer[docBufferUpto];
          break;
        case UNARY:
          int next = docBitSet.nextSetBit(doc - docBitSetBase + 1);
          assert next != NO_MORE_DOCS;
          doc = docBitSetBase + next;
          break;
      }

      ++docBufferUpto;
      return this.doc;
    }

    @Override
    public int advance(int target) throws IOException {
      if (target > level0LastDocID || needsRefilling) {
        if (target > level0LastDocID) {
          doAdvanceShallow(target);
        }
        refillDocs();
        needsRefilling = false;
      }

      switch (encoding) {
        case PACKED:
          {
            int next = VectorUtil.findNextGEQ(docBuffer, target, docBufferUpto, docBufferSize);
            this.doc = docBuffer[next];
            docBufferUpto = next + 1;
          }
          break;
        case UNARY:
          {
            int next = docBitSet.nextSetBit(target - docBitSetBase);
            assert next != NO_MORE_DOCS;
            this.doc = docBitSetBase + next;
            if (needsFreq) {
              int wordIndex = next >> 6;
              // Take the cumulative pop count for the given word, and subtract bits on the left of
              // the current doc.
              docBufferUpto =
                  1
                      + docCumulativeWordPopCounts[wordIndex]
                      - Long.bitCount(docBitSet.getBits()[wordIndex] >>> next);
            } else {
              // When only docs needed and block is UNARY encoded, we do not need to maintain
              // docBufferUpTo to record the iteration position in the block.
              // docBufferUpTo == 0 means the block has not been iterated.
              // docBufferUpTo != 0 means the block has been iterated.
              docBufferUpto = 1;
            }
          }
          break;
      }

      return doc;
    }

    @Override
    public void intoBitSet(int upTo, FixedBitSet bitSet, int offset) throws IOException {
      if (doc >= upTo) {
        return;
      }

      // Handle the current doc separately, it may be on the previous docBuffer.
      bitSet.set(doc - offset);

      for (; ; ) {
        if (doc == level0LastDocID) {
          // refill
          moveToNextLevel0Block();
        }

        switch (encoding) {
          case PACKED:
            {
              int start = docBufferUpto;
              int end = computeBufferEndBoundary(upTo);
              if (end != 0) {
                bufferIntoBitSet(start, end, bitSet, offset);
                doc = docBuffer[end - 1];
              }
              docBufferUpto = end;
              if (end != BLOCK_SIZE) {
                // Either the block is a tail block, or the block did not fully match, we're done.
                nextDoc();
                assert doc >= upTo;
                return;
              }
            }
            break;
          case UNARY:
            {
              int sourceFrom;
              if (docBufferUpto == 0) {
                // start from beginning
                sourceFrom = 0;
              } else {
                // start after the current doc
                sourceFrom = doc - docBitSetBase + 1;
              }

              int destFrom = docBitSetBase - offset + sourceFrom;

              assert level0LastDocID != NO_MORE_DOCS;
              int sourceTo = Math.min(upTo, level0LastDocID + 1) - docBitSetBase;

              if (sourceTo > sourceFrom) {
                FixedBitSet.orRange(docBitSet, sourceFrom, bitSet, destFrom, sourceTo - sourceFrom);
              }
              if (docBitSetBase + sourceTo <= level0LastDocID) {
                // We stopped before the end of the current bit set, which means that we're done.
                // Set the current doc before returning.
                advance(docBitSetBase + sourceTo);
                return;
              }
              doc = level0LastDocID;
              docBufferUpto = BLOCK_SIZE;
            }
            break;
        }
      }
    }

    @Override
    public void nextPostings(int upTo, DocAndFloatFeatureBuffer buffer) throws IOException {
      assert needsRefilling == false;

      if (needsFreq == false) {
        super.nextPostings(upTo, buffer);
        return;
      }

      buffer.size = 0;
      if (doc >= upTo) {
        return;
      }

      // Only return docs from the current block
      // +1 to make FixedBitSet#intoArray perform a bit faster, it uses a slower path if it doesn't
      // have a free slot after the last element
      buffer.growNoCopy(BLOCK_SIZE + 1);
      upTo = (int) Math.min(upTo, level0LastDocID + 1L);

      // Frequencies are decoded lazily, calling freq() makes sure that the freq block is decoded
      freq();

      int start = docBufferUpto - 1;
      switch (encoding) {
        case PACKED:
          int end = computeBufferEndBoundary(upTo);
          buffer.size = end - start;
          System.arraycopy(docBuffer, start, buffer.docs, 0, buffer.size);
          break;
        case UNARY:
          buffer.size =
              docBitSet.intoArray(
                  doc - docBitSetBase, upTo - docBitSetBase, docBitSetBase, buffer.docs);
          assert buffer.size < buffer.docs.length; // buffer's capacity is BLOCK_SIZE+1
          break;
      }

      assert buffer.size > 0;
      for (int i = 0; i < buffer.size; ++i) {
        buffer.features[i] = freqBuffer[start + i];
      }

      advance(upTo);
    }

    private int computeBufferEndBoundary(int upTo) {
      if (docBufferSize != 0 && docBuffer[docBufferSize - 1] < upTo) {
        // All docs in the buffer are under upTo
        return docBufferSize;
      } else {
        // Find the index of the first doc that is greater than or equal to upTo
        return VectorUtil.findNextGEQ(docBuffer, upTo, docBufferUpto, docBufferSize);
      }
    }

    private void bufferIntoBitSet(int start, int end, FixedBitSet bitSet, int offset)
        throws IOException {
      // bitSet#set and `doc - offset` get auto-vectorized
      for (int i = start; i < end; ++i) {
        int doc = docBuffer[i];
        bitSet.set(doc - offset);
      }
    }

    @Override
    public int docIDRunEnd() throws IOException {
      if (encoding == DeltaEncoding.UNARY) {
        // Note: this assumes that BLOCK_SIZE == 256, this bit of the code would need to be changed
        // if
        // the block size was changed.
        // Hack to avoid compiler warning that both sides of the equal sign are identical.
        long blockSize = BLOCK_SIZE;
        assert blockSize == 4 * Long.SIZE;
        boolean level0IsDense = true;
        for (int i = 0; i < 4; ++i) {
          if (docBitSet.getBits()[i] != -1L) {
            level0IsDense = false;
            break;
          }
        }
        if (level0IsDense) {

          int level0DocCountUpto = docFreq - docCountLeft;
          boolean level1IsDense =
              level1LastDocID - level0LastDocID == level1DocCountUpto - level0DocCountUpto;
          if (level1IsDense) {
            return level1LastDocID + 1;
          }

          return level0LastDocID + 1;
        }
      }

      return super.docIDRunEnd();
    }

    private void skipPositions(int freq) throws IOException {
      // Skip positions now:
      int toSkip = posPendingCount - freq;
      // if (DEBUG) {
      //   System.out.println("      FPR.skipPositions: toSkip=" + toSkip);
      // }

      final int leftInBlock = BLOCK_SIZE - posBufferUpto;
      if (toSkip < leftInBlock) {
        int end = posBufferUpto + toSkip;
        if (needsPayloads) {
          payloadByteUpto += sumOverRange(payloadLengthBuffer, posBufferUpto, end);
        }
        posBufferUpto = end;
      } else {
        toSkip -= leftInBlock;
        while (toSkip >= BLOCK_SIZE) {
          assert posIn.getFilePointer() != lastPosBlockFP;
          PForUtil.skip(posIn);

          if (payIn != null) {
            if (indexHasPayloads) {
              // Skip payloadLength block:
              PForUtil.skip(payIn);

              // Skip payloadBytes block:
              int numBytes = payIn.readVInt();
              payIn.seek(payIn.getFilePointer() + numBytes);
            }

            if (indexHasOffsets) {
              PForUtil.skip(payIn);
              PForUtil.skip(payIn);
            }
          }
          toSkip -= BLOCK_SIZE;
        }
        refillPositions();
        if (needsPayloads) {
          payloadByteUpto = sumOverRange(payloadLengthBuffer, 0, toSkip);
        }
        posBufferUpto = toSkip;
      }
    }

    private void refillLastPositionBlock() throws IOException {
      final int count = (int) (totalTermFreq % BLOCK_SIZE);
      int payloadLength = 0;
      int offsetLength = 0;
      payloadByteUpto = 0;
      for (int i = 0; i < count; i++) {
        int code = posIn.readVInt();
        if (indexHasPayloads) {
          if ((code & 1) != 0) {
            payloadLength = posIn.readVInt();
          }
          if (payloadLengthBuffer != null) { // needs payloads
            payloadLengthBuffer[i] = payloadLength;
            posDeltaBuffer[i] = code >>> 1;
            if (payloadLength != 0) {
              if (payloadByteUpto + payloadLength > payloadBytes.length) {
                payloadBytes = ArrayUtil.grow(payloadBytes, payloadByteUpto + payloadLength);
              }
              posIn.readBytes(payloadBytes, payloadByteUpto, payloadLength);
              payloadByteUpto += payloadLength;
            }
          } else {
            posIn.skipBytes(payloadLength);
          }
        } else {
          posDeltaBuffer[i] = code;
        }

        if (indexHasOffsets) {
          int deltaCode = posIn.readVInt();
          if ((deltaCode & 1) != 0) {
            offsetLength = posIn.readVInt();
          }
          if (offsetStartDeltaBuffer != null) { // needs offsets
            offsetStartDeltaBuffer[i] = deltaCode >>> 1;
            offsetLengthBuffer[i] = offsetLength;
          }
        }
      }
      payloadByteUpto = 0;
    }

    private void refillOffsetsOrPayloads() throws IOException {
      if (indexHasPayloads) {
        if (needsPayloads) {
          pforUtil.decode(payInUtil, payloadLengthBuffer);
          int numBytes = payIn.readVInt();

          if (numBytes > payloadBytes.length) {
            payloadBytes = ArrayUtil.growNoCopy(payloadBytes, numBytes);
          }
          payIn.readBytes(payloadBytes, 0, numBytes);
        } else if (payIn != null) { // needs offsets
          // this works, because when writing a vint block we always force the first length to be
          // written
          PForUtil.skip(payIn); // skip over lengths
          int numBytes = payIn.readVInt(); // read length of payloadBytes
          payIn.seek(payIn.getFilePointer() + numBytes); // skip over payloadBytes
        }
        payloadByteUpto = 0;
      }

      if (indexHasOffsets) {
        if (needsOffsets) {
          pforUtil.decode(payInUtil, offsetStartDeltaBuffer);
          pforUtil.decode(payInUtil, offsetLengthBuffer);
        } else if (payIn != null) { // needs payloads
          // this works, because when writing a vint block we always force the first length to be
          // written
          PForUtil.skip(payIn); // skip over starts
          PForUtil.skip(payIn); // skip over lengths
        }
      }
    }

    private void refillPositions() throws IOException {
      if (posIn.getFilePointer() == lastPosBlockFP) {
        refillLastPositionBlock();
        return;
      }
      pforUtil.decode(posInUtil, posDeltaBuffer);

      if (indexHasOffsetsOrPayloads) {
        refillOffsetsOrPayloads();
      }
    }

    private void accumulatePendingPositions() throws IOException {
      int freq = freq(); // trigger lazy decoding of freqs
      posPendingCount += sumOverRange(freqBuffer, posDocBufferUpto, docBufferUpto);
      posDocBufferUpto = docBufferUpto;

      assert posPendingCount > 0;

      if (posPendingCount > freq) {
        skipPositions(freq);
        posPendingCount = freq;
      }
    }

    private void accumulatePayloadAndOffsets() {
      if (needsPayloads) {
        payloadLength = payloadLengthBuffer[posBufferUpto];
        payload.bytes = payloadBytes;
        payload.offset = payloadByteUpto;
        payload.length = payloadLength;
        payloadByteUpto += payloadLength;
      }

      if (needsOffsets) {
        startOffset = lastStartOffset + offsetStartDeltaBuffer[posBufferUpto];
        endOffset = startOffset + offsetLengthBuffer[posBufferUpto];
        lastStartOffset = startOffset;
      }
    }

    @Override
    public int nextPosition() throws IOException {
      if (needsPos == false) {
        return -1;
      }

      assert posDocBufferUpto <= docBufferUpto;
      if (posDocBufferUpto != docBufferUpto) {
        // First position we're reading on this doc
        accumulatePendingPositions();
        position = 0;
        lastStartOffset = 0;
      }

      if (posBufferUpto == BLOCK_SIZE) {
        refillPositions();
        posBufferUpto = 0;
      }
      position += posDeltaBuffer[posBufferUpto];

      if (needsOffsetsOrPayloads) {
        accumulatePayloadAndOffsets();
      }

      posBufferUpto++;
      posPendingCount--;
      return position;
    }

    @Override
    public int startOffset() {
      if (needsOffsets == false) {
        return -1;
      }
      return startOffset;
    }

    @Override
    public int endOffset() {
      if (needsOffsets == false) {
        return -1;
      }
      return endOffset;
    }

    @Override
    public BytesRef getPayload() {
      if (needsPayloads == false || payloadLength == 0) {
        return null;
      } else {
        return payload;
      }
    }

    @Override
    public long cost() {
      return docFreq;
    }

    private final Impacts impacts =
        new Impacts() {

          private final ByteArrayDataInput scratch = new ByteArrayDataInput();

          @Override
          public int numLevels() {
            return indexHasFreq == false || level1LastDocID == NO_MORE_DOCS ? 1 : 2;
          }

          @Override
          public int getDocIdUpTo(int level) {
            if (indexHasFreq == false) {
              return NO_MORE_DOCS;
            }
            if (level == 0) {
              return level0LastDocID;
            }
            return level == 1 ? level1LastDocID : NO_MORE_DOCS;
          }

          @Override
          public FreqAndNormBuffer getImpacts(int level) {
            if (indexHasFreq == false) {
              // Max freq is 1 since freqs are not indexed
              impactBuffer.size = 1;
              impactBuffer.freqs[0] = 1;
              impactBuffer.norms[0] = 1L;
              return impactBuffer;
            }
            if (level == 0 && level0LastDocID != NO_MORE_DOCS) {
              return readImpacts(level0SerializedImpacts, impactBuffer);
            }
            if (level == 1) {
              return readImpacts(level1SerializedImpacts, impactBuffer);
            }
            impactBuffer.size = 1;
            impactBuffer.freqs[0] = Integer.MAX_VALUE;
            impactBuffer.norms[0] = 1L;
            return impactBuffer;
          }

          private FreqAndNormBuffer readImpacts(
              BytesRef serialized, FreqAndNormBuffer impactBuffer) {
            var scratch = this.scratch;
            scratch.reset(serialized.bytes, 0, serialized.length);
            Lucene104PostingsReader.readImpacts(scratch, impactBuffer);
            return impactBuffer;
          }
        };

    // ---- prefetch planning over .nav (see prefetchAhead); never moves the enum itself ----

    /** Runs of planned blocks closer than this in .doc are prefetched as one range. */
    private static final int PLAN_MERGE_GAP = 4096;

    private static final int PLAN_MAX_CHECKPOINTS = 32;

    private IndexInput planNav;
    private boolean planInitialized;
    private boolean planDone;
    private long planTermDocStartFP;
    private long planTermNavStartFP;
    private long planTermDocLength;
    private long
        planDocFP; // .doc end of the last block the planner walked past (or the term start)
    private int planLastDoc; // last doc ID of that block
    private int planDocsLeft; // docs of the term the planner has not walked yet
    private int planBlocksLeftInGroup; // level-0 entries left before the next level-1 entry
    private long planConsumedFP; // approximate .doc position of the consumer
    private long planRequestedFP; // .doc end of the last requested range
    private long runStart = -1;
    private long runEnd = -1;
    // checkpoints (last doc ID, .doc end) of requested blocks, to track the consumer's position
    private final int[] cpDoc = new int[PLAN_MAX_CHECKPOINTS];
    private final long[] cpFP = new long[PLAN_MAX_CHECKPOINTS];
    private int cpHead;
    private int cpSize;

    @Override
    public int prefetchAhead(int fromDoc, long bytesAhead) throws IOException {
      if (docFreq < BLOCK_SIZE || planTermDocLength < 0 || bytesAhead <= 0) {
        // a single short block: reset() already prefetched its first page
        return NO_MORE_DOCS;
      }
      final long nodeBytes = DisjunctionPrefetch.getNodeBytes();
      if (planInitialized && planNodeBytes != nodeBytes) {
        planInitialized = false; // the mode changed between calls: start over
      }
      if (nodeBytes > 0) {
        return prefetchAligned(fromDoc, Math.max(1, bytesAhead / nodeBytes), nodeBytes);
      }
      if (planInitialized == false) {
        planNodeBytes = 0;
        if (planNav == null) {
          planNav = Lucene104DualNavPostingsReader.this.navIn.clone();
        }
        planNav.seek(planTermNavStartFP);
        planDocFP = planTermDocStartFP;
        planLastDoc = -1;
        planDocsLeft = docFreq;
        planBlocksLeftInGroup = 0;
        planConsumedFP = planTermDocStartFP;
        planRequestedFP = planTermDocStartFP;
        planDone = false;
        cpHead = cpSize = 0;
        runStart = runEnd = -1;
        planInitialized = true;
      }
      // blocks whose last doc is before fromDoc have been consumed
      while (cpSize > 0 && cpDoc[cpHead] < fromDoc) {
        planConsumedFP = cpFP[cpHead];
        cpHead = (cpHead + 1) % PLAN_MAX_CHECKPOINTS;
        cpSize--;
      }
      if (cpSize == 0) {
        planConsumedFP = Math.max(planConsumedFP, planRequestedFP);
      }
      final long checkpointEvery = Math.max(1, bytesAhead / 8);
      long lastCheckpointFP = cpSize == 0 ? planConsumedFP : lastCheckpointFP();
      while (planDone == false && planRequestedFP - planConsumedFP < bytesAhead) {
        if (planStep(fromDoc) && planRequestedFP - lastCheckpointFP >= checkpointEvery) {
          addCheckpoint(planLastDoc, planRequestedFP);
          lastCheckpointFP = planRequestedFP;
        }
      }
      flushRun();
      if (cpSize > 0) {
        return cpDoc[cpHead] + 1;
      }
      return planDone ? NO_MORE_DOCS : planLastDoc + 1;
    }

    private long lastCheckpointFP() {
      return cpFP[(cpHead + cpSize - 1) % PLAN_MAX_CHECKPOINTS];
    }

    private void addCheckpoint(int lastDoc, long fp) {
      if (cpSize == PLAN_MAX_CHECKPOINTS) {
        return; // coarser tracking, still correct
      }
      final int i = (cpHead + cpSize) % PLAN_MAX_CHECKPOINTS;
      cpDoc[i] = lastDoc;
      cpFP[i] = fp;
      cpSize++;
    }

    /**
     * Walks one level-0 entry (or skips one level-1 group, or plans the tail block) of .nav.
     *
     * @return true if it requested a range
     */
    private boolean planStep(int fromDoc) throws IOException {
      if (planDocsLeft < BLOCK_SIZE) {
        // the vInt tail block follows the last full payload and ends the term
        final long termEnd = planTermDocStartFP + planTermDocLength;
        planDone = true;
        if (planDocsLeft > 0 && termEnd > planDocFP) {
          request(planDocFP, termEnd);
          planLastDoc = NO_MORE_DOCS - 1;
          return true;
        }
        return false;
      }
      if (planBlocksLeftInGroup == 0) {
        if (planDocsLeft >= LEVEL1_NUM_DOCS) {
          final int groupLastDoc = planLastDoc + planNav.readVInt();
          final long navLength = planNav.readVLong();
          final long groupNavEnd = planNav.getFilePointer() + navLength;
          final long groupDocEnd = planDocFP + planNav.readVLong();
          if (groupLastDoc < fromDoc) {
            // the whole group is behind the consumer
            planNav.seek(groupNavEnd);
            planDocFP = groupDocEnd;
            planLastDoc = groupLastDoc;
            planDocsLeft -= LEVEL1_NUM_DOCS;
            skipped(groupDocEnd);
            return false;
          }
          if (indexHasFreq) {
            final int level1SkipBytes = planNav.readShort();
            planNav.seek(planNav.getFilePointer() + level1SkipBytes);
          }
          planBlocksLeftInGroup = Lucene104PostingsFormat.LEVEL1_FACTOR;
        } else {
          planBlocksLeftInGroup = planDocsLeft / BLOCK_SIZE;
        }
      }
      final long entryLength = planNav.readVLong();
      final long entryEnd = planNav.getFilePointer() + entryLength;
      final int blockLastDoc = planLastDoc + readVInt15(planNav);
      final long payloadStart = planDocFP + planNav.readVInt();
      final long payloadEnd = payloadStart + readVLong15(planNav);
      planNav.seek(entryEnd);
      planDocFP = payloadEnd;
      planLastDoc = blockLastDoc;
      planDocsLeft -= BLOCK_SIZE;
      planBlocksLeftInGroup--;
      if (blockLastDoc < fromDoc) {
        skipped(payloadEnd);
        return false;
      }
      request(payloadStart, payloadEnd);
      return true;
    }

    /** A block behind the consumer: it does not count against the budget. */
    private void skipped(long endFP) throws IOException {
      flushRun();
      planConsumedFP = Math.max(planConsumedFP, endFP);
      planRequestedFP = Math.max(planRequestedFP, endFP);
    }

    private void request(long start, long end) throws IOException {
      if (runStart >= 0 && start - runEnd > PLAN_MERGE_GAP) {
        flushRun();
      }
      if (runStart < 0) {
        runStart = start;
      }
      runEnd = end;
      planRequestedFP = end;
    }

    private void flushRun() throws IOException {
      if (runStart >= 0) {
        docIn.prefetch(runStart, runEnd - runStart);
        runStart = runEnd = -1;
      }
    }

    // ---- aligned mode (DisjunctionPrefetch.getNodeBytes() > 0): whole nodes, one node ahead ----

    private long planNodeBytes; // node size the plan was initialized with, 0 for byte-budget mode
    private boolean alHasBlock; // the cursor is on a postings block, described by alBlock*
    private int alBlockFirstDoc;
    private long alBlockStart;
    private long alBlockEnd;

    /**
     * Requests whole nodes of {@code nodeBytes} (file offsets of .doc): the node the consumer is
     * reading at {@code fromDoc} and {@code nodesAhead} more, and returns the first doc of the
     * first postings block that reaches into the next node, where the next call is due.
     */
    private int prefetchAligned(int fromDoc, long nodesAhead, long nodeBytes) throws IOException {
      final long termStart = planTermDocStartFP;
      final long termEnd = termStart + planTermDocLength;
      if (planInitialized == false) {
        if (planNav == null) {
          planNav = Lucene104DualNavPostingsReader.this.navIn.clone();
        }
        planNav.seek(planTermNavStartFP);
        planDocFP = termStart;
        planLastDoc = -1;
        planDocsLeft = docFreq;
        planBlocksLeftInGroup = 0;
        planRequestedFP = termStart;
        planDone = false;
        alHasBlock = false;
        planNodeBytes = nodeBytes;
        planInitialized = true;
      }
      // move the cursor to the postings block that holds fromDoc
      while (alHasBlock == false || planLastDoc < fromDoc) {
        if (alignedStep(fromDoc, Long.MIN_VALUE) == false) {
          return NO_MORE_DOCS; // fromDoc is past the last doc of the term
        }
      }
      // The last node that reading this block touches. A postings block can cross a node boundary,
      // so taking the node of its END (not its start) requests every node the block needs (two
      // for a crossing block) before it is read, plus nodesAhead more.
      final long node = (alBlockEnd - 1) / nodeBytes;
      final long wantEnd = Math.min(termEnd, (node + 1 + nodesAhead) * nodeBytes);
      // from the start of the node that holds this block (nodes behind it are consumed), or from
      // where the previous request ended
      final long start = Math.max(planRequestedFP, alBlockStart - alBlockStart % nodeBytes);
      if (wantEnd > start) {
        docIn.prefetch(start, wantEnd - start);
      }
      planRequestedFP = Math.max(planRequestedFP, wantEnd);
      if (wantEnd >= termEnd) {
        return NO_MORE_DOCS;
      }
      // call again at the first postings block that reaches into node + 1
      final long boundary = (node + 1) * nodeBytes;
      do {
        if (alignedStep(Integer.MIN_VALUE, boundary) == false) {
          return NO_MORE_DOCS;
        }
      } while (alHasBlock == false || alBlockEnd <= boundary);
      return alBlockFirstDoc;
    }

    /**
     * Moves the aligned cursor by one postings block, or over one whole level-1 group when all of
     * its docs are before {@code skipDocsBefore} or all of its bytes end at or before {@code
     * skipBytesUpTo} (then {@code alHasBlock} is false).
     *
     * @return false at the end of the term
     */
    private boolean alignedStep(int skipDocsBefore, long skipBytesUpTo) throws IOException {
      if (planDone) {
        return false;
      }
      if (planDocsLeft < BLOCK_SIZE) {
        // the vInt tail block follows the last full payload and ends the term
        final long termEnd = planTermDocStartFP + planTermDocLength;
        planDone = true;
        if (planDocsLeft == 0 || termEnd <= planDocFP) {
          return false;
        }
        alBlockFirstDoc = planLastDoc + 1;
        alBlockStart = planDocFP;
        alBlockEnd = termEnd;
        planDocFP = termEnd;
        planLastDoc = NO_MORE_DOCS - 1;
        planDocsLeft = 0;
        alHasBlock = true;
        return true;
      }
      if (planBlocksLeftInGroup == 0) {
        if (planDocsLeft >= LEVEL1_NUM_DOCS) {
          final int groupLastDoc = planLastDoc + planNav.readVInt();
          final long navLength = planNav.readVLong();
          final long groupNavEnd = planNav.getFilePointer() + navLength;
          final long groupDocEnd = planDocFP + planNav.readVLong();
          if (groupLastDoc < skipDocsBefore || groupDocEnd <= skipBytesUpTo) {
            planNav.seek(groupNavEnd);
            planDocFP = groupDocEnd;
            planLastDoc = groupLastDoc;
            planDocsLeft -= LEVEL1_NUM_DOCS;
            alHasBlock = false;
            return true;
          }
          if (indexHasFreq) {
            final int level1SkipBytes = planNav.readShort();
            planNav.seek(planNav.getFilePointer() + level1SkipBytes);
          }
          planBlocksLeftInGroup = Lucene104PostingsFormat.LEVEL1_FACTOR;
        } else {
          planBlocksLeftInGroup = planDocsLeft / BLOCK_SIZE;
        }
      }
      final long entryLength = planNav.readVLong();
      final long entryEnd = planNav.getFilePointer() + entryLength;
      final int blockLastDoc = planLastDoc + readVInt15(planNav);
      final long payloadStart = planDocFP + planNav.readVInt();
      final long payloadEnd = payloadStart + readVLong15(planNav);
      planNav.seek(entryEnd);
      alBlockFirstDoc = planLastDoc + 1;
      alBlockStart = payloadStart;
      alBlockEnd = payloadEnd;
      planDocFP = payloadEnd;
      planLastDoc = blockLastDoc;
      planDocsLeft -= BLOCK_SIZE;
      planBlocksLeftInGroup--;
      alHasBlock = true;
      return true;
    }

    @Override
    public Impacts getImpacts() {
      assert needsImpacts;
      return impacts;
    }
  }
}
