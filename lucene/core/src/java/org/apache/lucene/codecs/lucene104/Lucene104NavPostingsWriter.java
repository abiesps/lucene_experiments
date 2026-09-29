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

import static org.apache.lucene.codecs.lucene104.Lucene104NavPostingsFormat.BLOCK_SIZE;
import static org.apache.lucene.codecs.lucene104.Lucene104NavPostingsFormat.DOC_CODEC;
import static org.apache.lucene.codecs.lucene104.Lucene104NavPostingsFormat.LEVEL1_MASK;
import static org.apache.lucene.codecs.lucene104.Lucene104NavPostingsFormat.META_CODEC;
import static org.apache.lucene.codecs.lucene104.Lucene104NavPostingsFormat.NAV_CODEC;
import static org.apache.lucene.codecs.lucene104.Lucene104NavPostingsFormat.PAY_CODEC;
import static org.apache.lucene.codecs.lucene104.Lucene104NavPostingsFormat.POS_CODEC;
import static org.apache.lucene.codecs.lucene104.Lucene104NavPostingsFormat.TERMS_CODEC;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.apache.lucene.codecs.BlockTermState;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.CompetitiveImpactAccumulator;
import org.apache.lucene.codecs.Impact;
import org.apache.lucene.codecs.PushPostingsWriterBase;
import org.apache.lucene.codecs.lucene104.Lucene104NavPostingsFormat.NavIntBlockTermState;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.store.ByteBuffersDataOutput;
import org.apache.lucene.store.DataOutput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BitUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.packed.PackedInts;

/** Writer for {@link Lucene104NavPostingsFormat}. */
public class Lucene104NavPostingsWriter extends PushPostingsWriterBase {

  static final NavIntBlockTermState EMPTY_STATE = new NavIntBlockTermState();

  private final int version;

  IndexOutput metaOut;
  IndexOutput docOut;
  IndexOutput navOut;
  IndexOutput posOut;
  IndexOutput payOut;

  NavIntBlockTermState lastState;

  // Holds starting file pointers for current term:
  private long docStartFP;
  private long navStartFP;
  // .doc position where the current level-1 region (32 blocks) starts
  private long level1DocStartFP;
  // last .nav start pointer written to the terms dictionary, see encodeTerm
  private long lastEncodedNavStartFP;
  private long posStartFP;
  private long payStartFP;

  final int[] docDeltaBuffer;
  final int[] freqBuffer;
  private int docBufferUpto;

  final int[] posDeltaBuffer;
  final int[] payloadLengthBuffer;
  final int[] offsetStartDeltaBuffer;
  final int[] offsetLengthBuffer;
  private int posBufferUpto;

  private byte[] payloadBytes;
  private int payloadByteUpto;

  private int level0LastDocID;
  private long level0LastPosFP;
  private long level0LastPayFP;

  private int level1LastDocID;
  private long level1LastPosFP;
  private long level1LastPayFP;

  private int docID;
  private int lastDocID;
  private int lastPosition;
  private int lastStartOffset;
  private int docCount;

  private final PForUtil pforUtil;
  private final ForUtil forUtil;

  private boolean fieldHasNorms;
  private NumericDocValues norms;
  private final CompetitiveImpactAccumulator level0FreqNormAccumulator =
      new CompetitiveImpactAccumulator();
  private final CompetitiveImpactAccumulator level1CompetitiveFreqNormAccumulator =
      new CompetitiveImpactAccumulator();

  private int maxNumImpactsAtLevel0;
  private int maxImpactNumBytesAtLevel0;
  private int maxNumImpactsAtLevel1;
  private int maxImpactNumBytesAtLevel1;

  /** Scratch output that we use to be able to prepend the encoded length, e.g. impacts. */
  private final ByteBuffersDataOutput scratchOutput = ByteBuffersDataOutput.newResettableInstance();

  /**
   * Level-0 skip entry of a single block (impacts, pos and pay pointers). The content is copied to
   * {@link #level1Output}; the block payload itself goes to .doc.
   */
  private final ByteBuffersDataOutput level0Output = ByteBuffersDataOutput.newResettableInstance();

  /** Encoded payload of the current block (encoding byte, doc deltas, freqs), written to .doc. */
  private final ByteBuffersDataOutput payloadOutput = ByteBuffersDataOutput.newResettableInstance();

  /** Level-1 entry being built, before it is written to .nav. */
  private final ByteBuffersDataOutput level1BodyOutput =
      ByteBuffersDataOutput.newResettableInstance();

  /**
   * Level-0 skip entries of the current group of 32 blocks. They are copied to .nav after the
   * level-1 entry of the group, which can only be computed once the 32 blocks are encoded.
   */
  private final ByteBuffersDataOutput level1Output = ByteBuffersDataOutput.newResettableInstance();

  /**
   * Reusable FixedBitSet, for dense blocks that are more efficiently stored by storing them as a
   * bit set than as packed deltas.
   */
  // Since we use a bit set when it's more storage-efficient, the bit set cannot have more than
  // BLOCK_SIZE*32 bits, which is the maximum possible storage requirement with FOR.
  private final FixedBitSet spareBitSet = new FixedBitSet(BLOCK_SIZE * Integer.SIZE);

  /** Sole public constructor. */
  public Lucene104NavPostingsWriter(SegmentWriteState state) throws IOException {
    this(state, Lucene104NavPostingsFormat.VERSION_CURRENT);
  }

  /** Constructor that takes a version. */
  Lucene104NavPostingsWriter(SegmentWriteState state, int version) throws IOException {
    this.version = version;
    String metaFileName =
        IndexFileNames.segmentFileName(
            state.segmentInfo.name, state.segmentSuffix, Lucene104NavPostingsFormat.META_EXTENSION);
    String docFileName =
        IndexFileNames.segmentFileName(
            state.segmentInfo.name, state.segmentSuffix, Lucene104NavPostingsFormat.DOC_EXTENSION);
    String navFileName =
        IndexFileNames.segmentFileName(
            state.segmentInfo.name, state.segmentSuffix, Lucene104NavPostingsFormat.NAV_EXTENSION);
    metaOut = state.directory.createOutput(metaFileName, state.context);
    IndexOutput posOut = null;
    IndexOutput payOut = null;
    boolean success = false;
    try {
      docOut = state.directory.createOutput(docFileName, state.context);
      navOut = state.directory.createOutput(navFileName, state.context);
      CodecUtil.writeIndexHeader(
          metaOut, META_CODEC, version, state.segmentInfo.getId(), state.segmentSuffix);
      CodecUtil.writeIndexHeader(
          docOut, DOC_CODEC, version, state.segmentInfo.getId(), state.segmentSuffix);
      CodecUtil.writeIndexHeader(
          navOut, NAV_CODEC, version, state.segmentInfo.getId(), state.segmentSuffix);
      forUtil = new ForUtil();
      pforUtil = new PForUtil(forUtil);
      if (state.fieldInfos.hasProx()) {
        posDeltaBuffer = new int[BLOCK_SIZE];
        String posFileName =
            IndexFileNames.segmentFileName(
                state.segmentInfo.name,
                state.segmentSuffix,
                Lucene104NavPostingsFormat.POS_EXTENSION);
        posOut = state.directory.createOutput(posFileName, state.context);
        CodecUtil.writeIndexHeader(
            posOut, POS_CODEC, version, state.segmentInfo.getId(), state.segmentSuffix);

        if (state.fieldInfos.hasPayloads()) {
          payloadBytes = new byte[128];
          payloadLengthBuffer = new int[BLOCK_SIZE];
        } else {
          payloadBytes = null;
          payloadLengthBuffer = null;
        }

        if (state.fieldInfos.hasOffsets()) {
          offsetStartDeltaBuffer = new int[BLOCK_SIZE];
          offsetLengthBuffer = new int[BLOCK_SIZE];
        } else {
          offsetStartDeltaBuffer = null;
          offsetLengthBuffer = null;
        }

        if (state.fieldInfos.hasPayloads() || state.fieldInfos.hasOffsets()) {
          String payFileName =
              IndexFileNames.segmentFileName(
                  state.segmentInfo.name,
                  state.segmentSuffix,
                  Lucene104NavPostingsFormat.PAY_EXTENSION);
          payOut = state.directory.createOutput(payFileName, state.context);
          CodecUtil.writeIndexHeader(
              payOut, PAY_CODEC, version, state.segmentInfo.getId(), state.segmentSuffix);
        }
      } else {
        posDeltaBuffer = null;
        payloadLengthBuffer = null;
        offsetStartDeltaBuffer = null;
        offsetLengthBuffer = null;
        payloadBytes = null;
      }
      this.payOut = payOut;
      this.posOut = posOut;
      success = true;
    } finally {
      if (success == false) {
        IOUtils.closeWhileHandlingException(metaOut, docOut, navOut, posOut, payOut);
      }
    }

    docDeltaBuffer = new int[BLOCK_SIZE];
    freqBuffer = new int[BLOCK_SIZE];
  }

  @Override
  public NavIntBlockTermState newTermState() {
    return new NavIntBlockTermState();
  }

  @Override
  public void init(IndexOutput termsOut, SegmentWriteState state) throws IOException {
    CodecUtil.writeIndexHeader(
        termsOut, TERMS_CODEC, version, state.segmentInfo.getId(), state.segmentSuffix);
    termsOut.writeVInt(BLOCK_SIZE);
  }

  @Override
  public void setField(FieldInfo fieldInfo) {
    super.setField(fieldInfo);
    lastState = EMPTY_STATE;
    fieldHasNorms = fieldInfo.hasNorms();
  }

  @Override
  public void startTerm(NumericDocValues norms) {
    docStartFP = docOut.getFilePointer();
    navStartFP = navOut.getFilePointer();
    level1DocStartFP = docStartFP;
    if (writePositions) {
      posStartFP = posOut.getFilePointer();
      level1LastPosFP = level0LastPosFP = posStartFP;
      if (writePayloads || writeOffsets) {
        payStartFP = payOut.getFilePointer();
        level1LastPayFP = level0LastPayFP = payStartFP;
      }
    }
    lastDocID = -1;
    level0LastDocID = -1;
    level1LastDocID = -1;
    this.norms = norms;
    if (writeFreqs) {
      level0FreqNormAccumulator.clear();
    }
  }

  @Override
  public void startDoc(int docID, int termDocFreq) throws IOException {
    if (docBufferUpto == BLOCK_SIZE) {
      flushDocBlock(false);
      docBufferUpto = 0;
    }

    final int docDelta = docID - lastDocID;

    if (docID < 0 || docDelta <= 0) {
      throw new CorruptIndexException(
          "docs out of order (" + docID + " <= " + lastDocID + " )", docOut);
    }

    docDeltaBuffer[docBufferUpto] = docDelta;
    if (writeFreqs) {
      freqBuffer[docBufferUpto] = termDocFreq;
    }

    this.docID = docID;
    lastPosition = 0;
    lastStartOffset = 0;

    if (writeFreqs) {
      long norm;
      if (fieldHasNorms) {
        boolean found = norms.advanceExact(docID);
        if (found == false) {
          // This can happen if indexing hits a problem after adding a doc to the
          // postings but before buffering the norm. Such documents are written
          // deleted and will go away on the first merge.
          norm = 1L;
        } else {
          norm = norms.longValue();
          assert norm != 0 : docID;
        }
      } else {
        norm = 1L;
      }

      level0FreqNormAccumulator.add(termDocFreq, norm);
    }
  }

  @Override
  public void addPosition(int position, BytesRef payload, int startOffset, int endOffset)
      throws IOException {
    if (position > IndexWriter.MAX_POSITION) {
      throw new CorruptIndexException(
          "position="
              + position
              + " is too large (> IndexWriter.MAX_POSITION="
              + IndexWriter.MAX_POSITION
              + ")",
          docOut);
    }
    if (position < 0) {
      throw new CorruptIndexException("position=" + position + " is < 0", docOut);
    }
    posDeltaBuffer[posBufferUpto] = position - lastPosition;
    if (writePayloads) {
      if (payload == null || payload.length == 0) {
        // no payload
        payloadLengthBuffer[posBufferUpto] = 0;
      } else {
        payloadLengthBuffer[posBufferUpto] = payload.length;
        if (payloadByteUpto + payload.length > payloadBytes.length) {
          payloadBytes = ArrayUtil.grow(payloadBytes, payloadByteUpto + payload.length);
        }
        System.arraycopy(
            payload.bytes, payload.offset, payloadBytes, payloadByteUpto, payload.length);
        payloadByteUpto += payload.length;
      }
    }

    if (writeOffsets) {
      assert startOffset >= lastStartOffset;
      assert endOffset >= startOffset;
      offsetStartDeltaBuffer[posBufferUpto] = startOffset - lastStartOffset;
      offsetLengthBuffer[posBufferUpto] = endOffset - startOffset;
      lastStartOffset = startOffset;
    }

    posBufferUpto++;
    lastPosition = position;
    if (posBufferUpto == BLOCK_SIZE) {
      pforUtil.encode(posDeltaBuffer, posOut);

      if (writePayloads) {
        pforUtil.encode(payloadLengthBuffer, payOut);
        payOut.writeVInt(payloadByteUpto);
        payOut.writeBytes(payloadBytes, 0, payloadByteUpto);
        payloadByteUpto = 0;
      }
      if (writeOffsets) {
        pforUtil.encode(offsetStartDeltaBuffer, payOut);
        pforUtil.encode(offsetLengthBuffer, payOut);
      }
      posBufferUpto = 0;
    }
  }

  @Override
  public void finishDoc() {
    docBufferUpto++;
    docCount++;

    lastDocID = docID;
  }

  /**
   * Special vints that are encoded on 2 bytes if they require 15 bits or less. VInt becomes
   * especially slow when the number of bytes is variable, so this special layout helps in the case
   * when the number likely requires 15 bits or less
   */
  static void writeVInt15(DataOutput out, int v) throws IOException {
    assert v >= 0;
    writeVLong15(out, v);
  }

  /**
   * @see #writeVInt15(DataOutput, int)
   */
  static void writeVLong15(DataOutput out, long v) throws IOException {
    assert v >= 0;
    if ((v & ~0x7FFFL) == 0) {
      out.writeShort((short) v);
    } else {
      out.writeShort((short) (0x8000 | (v & 0x7FFF)));
      out.writeVLong(v >> 15);
    }
  }

  /**
   * Writes the buffered block. A full block writes its payload to .doc and its level-0 skip entry
   * to {@link #level1Output}, which goes to .nav. The last, partial block of a term (fewer than
   * BLOCK_SIZE docs) is vInt-encoded straight to .doc and has no skip entry.
   *
   * <p>Level-0 entry in .nav: [VLong numSkipBytes] [VInt15 docDelta] [VLong15 payloadLength] and,
   * if freqs are indexed, [VLong numImpactBytes] [impacts] [pos/pay pointers]. numSkipBytes counts
   * the bytes after itself, so a reader can jump over the entry.
   */
  private void flushDocBlock(boolean finishTerm) throws IOException {
    assert docBufferUpto != 0;

    if (docBufferUpto < BLOCK_SIZE) {
      assert finishTerm;
      PostingsUtil.writeVIntBlock(docOut, docDeltaBuffer, freqBuffer, docBufferUpto, writeFreqs);
    } else {
      if (writeFreqs) {
        List<Impact> impacts = level0FreqNormAccumulator.getCompetitiveFreqNormPairs();
        if (impacts.size() > maxNumImpactsAtLevel0) {
          maxNumImpactsAtLevel0 = impacts.size();
        }
        writeImpacts(impacts, scratchOutput);
        assert level0Output.size() == 0;
        if (scratchOutput.size() > maxImpactNumBytesAtLevel0) {
          maxImpactNumBytesAtLevel0 = Math.toIntExact(scratchOutput.size());
        }
        level0Output.writeVLong(scratchOutput.size());
        scratchOutput.copyTo(level0Output);
        scratchOutput.reset();
        if (writePositions) {
          level0Output.writeVLong(posOut.getFilePointer() - level0LastPosFP);
          level0Output.writeByte((byte) posBufferUpto);
          level0LastPosFP = posOut.getFilePointer();

          if (writeOffsets || writePayloads) {
            level0Output.writeVLong(payOut.getFilePointer() - level0LastPayFP);
            level0Output.writeVInt(payloadByteUpto);
            level0LastPayFP = payOut.getFilePointer();
          }
        }
      }
      long numSkipBytes = level0Output.size();
      // Same FOR vs. unary (bit set) choice as Lucene104PostingsWriter.
      int or = 0;
      for (int i : docDeltaBuffer) {
        or |= i;
      }
      assert or != 0;
      int bitsPerValue = PackedInts.bitsRequired(or);
      int docRange = lastDocID - level0LastDocID;
      assert docRange == Arrays.stream(docDeltaBuffer).sum();
      int numBitSetLongs = FixedBitSet.bits2words(docRange);
      int numBitsNextBitsPerValue = Math.min(Integer.SIZE, bitsPerValue + 1) * BLOCK_SIZE;
      assert payloadOutput.size() == 0;
      if (docRange == BLOCK_SIZE) {
        payloadOutput.writeByte((byte) 0);
      } else if (numBitsNextBitsPerValue <= docRange) {
        payloadOutput.writeByte((byte) bitsPerValue);
        forUtil.encode(docDeltaBuffer, bitsPerValue, payloadOutput);
      } else {
        spareBitSet.clear(0, numBitSetLongs << 6);
        int s = -1;
        for (int i : docDeltaBuffer) {
          s += i;
          spareBitSet.set(s);
        }
        assert numBitSetLongs <= BLOCK_SIZE / 2;
        payloadOutput.writeByte((byte) -numBitSetLongs);
        for (int i = 0; i < numBitSetLongs; ++i) {
          payloadOutput.writeLong(spareBitSet.getBits()[i]);
        }
      }

      if (writeFreqs) {
        pforUtil.encode(freqBuffer, payloadOutput);
      }

      writeVInt15(scratchOutput, docID - level0LastDocID);
      writeVLong15(scratchOutput, payloadOutput.size());
      numSkipBytes += scratchOutput.size();
      level1Output.writeVLong(numSkipBytes);
      scratchOutput.copyTo(level1Output);
      scratchOutput.reset();
      level0Output.copyTo(level1Output);
      level0Output.reset();

      payloadOutput.copyTo(docOut);
      payloadOutput.reset();
    }

    level0LastDocID = docID;
    if (writeFreqs) {
      level1CompetitiveFreqNormAccumulator.addAll(level0FreqNormAccumulator);
      level0FreqNormAccumulator.clear();
    }

    if ((docCount & LEVEL1_MASK) == 0) { // true every 32 blocks (8,192 docs)
      writeLevel1SkipData();
      level1LastDocID = docID;
      level1CompetitiveFreqNormAccumulator.clear();
    } else if (finishTerm) {
      // the trailing group of fewer than 32 blocks has no level-1 entry
      level1Output.copyTo(navOut);
      level1Output.reset();
      level1CompetitiveFreqNormAccumulator.clear();
    }
  }

  /**
   * Writes the level-1 entry of the last 32 blocks to .nav, followed by their level-0 entries.
   *
   * <p>Level-1 entry: [VInt docDelta] [VLong navLength] [VLong docLength] and, if freqs are
   * indexed, [short skip1Length] [short numImpactBytes] [impacts] [pos/pay pointers]. navLength
   * counts the .nav bytes after itself up to the end of the group, docLength the .doc bytes of the
   * group's 32 payloads.
   */
  private void writeLevel1SkipData() throws IOException {
    navOut.writeVInt(docID - level1LastDocID);
    final long docLength = docOut.getFilePointer() - level1DocStartFP;
    level1DocStartFP = docOut.getFilePointer();
    final ByteBuffersDataOutput body = level1BodyOutput;
    assert body.size() == 0;
    body.writeVLong(docLength);
    if (writeFreqs) {
      List<Impact> impacts = level1CompetitiveFreqNormAccumulator.getCompetitiveFreqNormPairs();
      if (impacts.size() > maxNumImpactsAtLevel1) {
        maxNumImpactsAtLevel1 = impacts.size();
      }
      writeImpacts(impacts, scratchOutput);
      long numImpactBytes = scratchOutput.size();
      if (numImpactBytes > maxImpactNumBytesAtLevel1) {
        maxImpactNumBytesAtLevel1 = Math.toIntExact(numImpactBytes);
      }
      if (writePositions) {
        scratchOutput.writeVLong(posOut.getFilePointer() - level1LastPosFP);
        scratchOutput.writeByte((byte) posBufferUpto);
        level1LastPosFP = posOut.getFilePointer();
        if (writeOffsets || writePayloads) {
          scratchOutput.writeVLong(payOut.getFilePointer() - level1LastPayFP);
          scratchOutput.writeVInt(payloadByteUpto);
          level1LastPayFP = payOut.getFilePointer();
        }
      }
      assert numImpactBytes <= Short.MAX_VALUE;
      assert scratchOutput.size() + Short.BYTES <= Short.MAX_VALUE;
      body.writeShort((short) (scratchOutput.size() + Short.BYTES));
      body.writeShort((short) numImpactBytes);
      scratchOutput.copyTo(body);
      scratchOutput.reset();
    }
    navOut.writeVLong(body.size() + level1Output.size());
    final long level1End = navOut.getFilePointer() + body.size() + level1Output.size();
    body.copyTo(navOut);
    body.reset();
    level1Output.copyTo(navOut);
    level1Output.reset();
    assert navOut.getFilePointer() == level1End : navOut.getFilePointer() + " " + level1End;
  }

  static void writeImpacts(Collection<Impact> impacts, DataOutput out) throws IOException {
    Impact previous = new Impact(0, 0);
    for (Impact impact : impacts) {
      assert impact.freq > previous.freq;
      assert Long.compareUnsigned(impact.norm, previous.norm) > 0;
      int freqDelta = impact.freq - previous.freq - 1;
      long normDelta = impact.norm - previous.norm - 1;
      if (normDelta == 0) {
        // most of time, norm only increases by 1, so we can fold everything in a single byte
        out.writeVInt(freqDelta << 1);
      } else {
        out.writeVInt((freqDelta << 1) | 1);
        out.writeZLong(normDelta);
      }
      previous = impact;
    }
  }

  /** Called when we are done adding docs to this term */
  @Override
  public void finishTerm(BlockTermState _state) throws IOException {
    NavIntBlockTermState state = (NavIntBlockTermState) _state;
    assert state.docFreq > 0;

    // TODO: wasteful we are counting this (counting # docs
    // for this term) in two places?
    assert state.docFreq == docCount : state.docFreq + " vs " + docCount;

    // docFreq == 1, don't write the single docid/freq to a separate file along with a pointer to
    // it.
    final int singletonDocID;
    if (state.docFreq == 1) {
      // pulse the singleton docid into the term dictionary, freq is implicitly totalTermFreq
      singletonDocID = docDeltaBuffer[0] - 1;
    } else {
      singletonDocID = -1;
      flushDocBlock(true);
    }

    final long lastPosBlockOffset;

    if (writePositions) {
      // totalTermFreq is just total number of positions(or payloads, or offsets)
      // associated with current term.
      assert state.totalTermFreq != -1;
      if (state.totalTermFreq > BLOCK_SIZE) {
        // record file offset for last pos in last block
        lastPosBlockOffset = posOut.getFilePointer() - posStartFP;
      } else {
        lastPosBlockOffset = -1;
      }
      if (posBufferUpto > 0) {
        assert posBufferUpto < BLOCK_SIZE;
        // TODO: should we send offsets/payloads to
        // .pay...?  seems wasteful (have to store extra
        // vLong for low (< BLOCK_SIZE) DF terms = vast vast
        // majority)

        // vInt encode the remaining positions/payloads/offsets:
        int lastPayloadLength = -1; // force first payload length to be written
        int lastOffsetLength = -1; // force first offset length to be written
        int payloadBytesReadUpto = 0;
        for (int i = 0; i < posBufferUpto; i++) {
          final int posDelta = posDeltaBuffer[i];
          if (writePayloads) {
            final int payloadLength = payloadLengthBuffer[i];
            if (payloadLength != lastPayloadLength) {
              lastPayloadLength = payloadLength;
              posOut.writeVInt((posDelta << 1) | 1);
              posOut.writeVInt(payloadLength);
            } else {
              posOut.writeVInt(posDelta << 1);
            }

            if (payloadLength != 0) {
              posOut.writeBytes(payloadBytes, payloadBytesReadUpto, payloadLength);
              payloadBytesReadUpto += payloadLength;
            }
          } else {
            posOut.writeVInt(posDelta);
          }

          if (writeOffsets) {
            int delta = offsetStartDeltaBuffer[i];
            int length = offsetLengthBuffer[i];
            if (length == lastOffsetLength) {
              posOut.writeVInt(delta << 1);
            } else {
              posOut.writeVInt(delta << 1 | 1);
              posOut.writeVInt(length);
              lastOffsetLength = length;
            }
          }
        }

        if (writePayloads) {
          assert payloadBytesReadUpto == payloadByteUpto;
          payloadByteUpto = 0;
        }
      }
    } else {
      lastPosBlockOffset = -1;
    }

    state.docStartFP = docStartFP;
    state.navStartFP = navStartFP;
    state.posStartFP = posStartFP;
    state.payStartFP = payStartFP;
    state.singletonDocID = singletonDocID;

    state.lastPosBlockOffset = lastPosBlockOffset;
    docBufferUpto = 0;
    posBufferUpto = 0;
    lastDocID = -1;
    docCount = 0;
  }

  @Override
  public void encodeTerm(
      DataOutput out, FieldInfo fieldInfo, BlockTermState _state, boolean absolute)
      throws IOException {
    NavIntBlockTermState state = (NavIntBlockTermState) _state;
    if (absolute) {
      lastState = EMPTY_STATE;
      lastEncodedNavStartFP = 0;
      assert lastState.docStartFP == 0;
    }

    if (lastState.singletonDocID != -1
        && state.singletonDocID != -1
        && state.docStartFP == lastState.docStartFP) {
      // With runs of rare values such as ID fields, the increment of pointers in the docs file is
      // often 0.
      // Furthermore some ID schemes like auto-increment IDs or Flake IDs are monotonic, so we
      // encode the delta
      // between consecutive doc IDs to save space.
      final long delta = (long) state.singletonDocID - lastState.singletonDocID;
      out.writeVLong((BitUtil.zigZagEncode(delta) << 1) | 0x01);
    } else {
      out.writeVLong((state.docStartFP - lastState.docStartFP) << 1);
      if (state.singletonDocID != -1) {
        out.writeVInt(state.singletonDocID);
      }
    }

    // Only terms with at least one full block have skip data. Their .nav pointers are delta-coded
    // against the previous such term, so terms without skip data cost nothing here.
    if (state.docFreq >= BLOCK_SIZE) {
      out.writeVLong(state.navStartFP - lastEncodedNavStartFP);
      lastEncodedNavStartFP = state.navStartFP;
    }

    if (writePositions) {
      out.writeVLong(state.posStartFP - lastState.posStartFP);
      if (writePayloads || writeOffsets) {
        out.writeVLong(state.payStartFP - lastState.payStartFP);
      }
    }
    if (writePositions) {
      if (state.lastPosBlockOffset != -1) {
        out.writeVLong(state.lastPosBlockOffset);
      }
    }
    lastState = state;
  }

  @Override
  public void close() throws IOException {
    // TODO: add a finish() at least to PushBase? DV too...?
    try {
      boolean success = false;
      try {
        if (docOut != null) {
          CodecUtil.writeFooter(docOut);
        }
        if (navOut != null) {
          CodecUtil.writeFooter(navOut);
        }
        if (posOut != null) {
          CodecUtil.writeFooter(posOut);
        }
        if (payOut != null) {
          CodecUtil.writeFooter(payOut);
        }
        if (metaOut != null) {
          metaOut.writeInt(maxNumImpactsAtLevel0);
          metaOut.writeInt(maxImpactNumBytesAtLevel0);
          metaOut.writeInt(maxNumImpactsAtLevel1);
          metaOut.writeInt(maxImpactNumBytesAtLevel1);
          metaOut.writeLong(docOut.getFilePointer());
          metaOut.writeLong(navOut.getFilePointer());
          if (posOut != null) {
            metaOut.writeLong(posOut.getFilePointer());
            if (payOut != null) {
              metaOut.writeLong(payOut.getFilePointer());
            }
          }
          CodecUtil.writeFooter(metaOut);
        }
        success = true;
      } finally {
        if (success == false) {
          IOUtils.closeWhileHandlingException(metaOut, docOut, navOut, posOut, payOut);
        }
      }
      IOUtils.close(metaOut, docOut, navOut, posOut, payOut);
    } finally {
      metaOut = docOut = navOut = posOut = payOut = null;
    }
  }
}
