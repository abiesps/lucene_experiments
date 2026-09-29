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

import java.io.IOException;
import org.apache.lucene.codecs.BlockTermState;
import org.apache.lucene.codecs.FieldsConsumer;
import org.apache.lucene.codecs.FieldsProducer;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.PostingsReaderBase;
import org.apache.lucene.codecs.PostingsWriterBase;
import org.apache.lucene.codecs.lucene103.blocktree.Lucene103BlockTreeTermsReader;
import org.apache.lucene.codecs.lucene103.blocktree.Lucene103BlockTreeTermsWriter;
import org.apache.lucene.index.ImpactsEnum;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.TermState;
import org.apache.lucene.util.IOUtils;

/**
 * Experimental variant of {@link Lucene104PostingsFormat} that stores the skip metadata of the
 * postings lists in its own file.
 *
 * <p>The only difference from {@link Lucene104PostingsFormat} is where the skip data lives. The
 * encodings, the block size ({@value #BLOCK_SIZE} docs), the two skip levels, the impacts and the
 * skipping algorithm are the same:
 *
 * <ul>
 *   <li><b>.doc</b> holds only block payloads: for each full block its encoding byte, its packed
 *       doc deltas and its packed freqs, and the vInt tail block of each term.
 *   <li><b>.nav</b> holds, for each term with at least {@value #BLOCK_SIZE} docs, the level-1 skip
 *       entries (one per {@value #LEVEL1_NUM_DOCS} docs) and the level-0 skip entries (one per
 *       block) that {@link Lucene104PostingsFormat} interleaves with the payloads in .doc: last doc
 *       delta, payload length in .doc, impacts, and pointers into .pos and .pay. A level-1 entry
 *       also records how many .doc bytes its {@value #LEVEL1_FACTOR} blocks span.
 * </ul>
 *
 * <p>Skipping reads only .nav, so an {@code advance()} that crosses many blocks touches .doc only
 * at the block that holds the target. The terms dictionary records a .nav start pointer for each
 * term that has skip data.
 *
 * @lucene.experimental
 */
public final class Lucene104NavPostingsFormat extends PostingsFormat {

  /** Name of this format, as recorded in the segment. */
  public static final String NAME = "Lucene104Nav";

  /** Filename extension for the postings metadata. */
  public static final String META_EXTENSION = "psm";

  /** Filename extension for the block payloads (doc deltas and freqs). */
  public static final String DOC_EXTENSION = "doc";

  /** Filename extension for the skip metadata. */
  public static final String NAV_EXTENSION = "nav";

  /** Filename extension for positions. */
  public static final String POS_EXTENSION = "pos";

  /** Filename extension for payloads and offsets. */
  public static final String PAY_EXTENSION = "pay";

  /** Size of blocks. */
  public static final int BLOCK_SIZE = ForUtil.BLOCK_SIZE;

  static final int BLOCK_MASK = BLOCK_SIZE - 1;

  /** We insert skip data on every block and every LEVEL1_FACTOR=32 blocks. */
  public static final int LEVEL1_FACTOR = 32;

  /** Total number of docs covered by level 1 skip data: 32 * 256 = 8,192. */
  public static final int LEVEL1_NUM_DOCS = LEVEL1_FACTOR * BLOCK_SIZE;

  static final int LEVEL1_MASK = LEVEL1_NUM_DOCS - 1;

  /**
   * Return the class that implements {@link ImpactsEnum} in this format. This is internally used to
   * help the JVM make good inlining decisions.
   *
   * @lucene.internal
   */
  public static Class<? extends ImpactsEnum> getImpactsEnumImpl() {
    return Lucene104NavPostingsReader.BlockPostingsEnum.class;
  }

  static final String TERMS_CODEC = "Lucene104NavPostingsWriterTerms";
  static final String META_CODEC = "Lucene104NavPostingsWriterMeta";
  static final String DOC_CODEC = "Lucene104NavPostingsWriterDoc";
  static final String NAV_CODEC = "Lucene104NavPostingsWriterNav";
  static final String POS_CODEC = "Lucene104NavPostingsWriterPos";
  static final String PAY_CODEC = "Lucene104NavPostingsWriterPay";

  static final int VERSION_START = 0;
  static final int VERSION_CURRENT = VERSION_START;

  private final int version;
  private final int minTermBlockSize;
  private final int maxTermBlockSize;

  /** Creates {@code Lucene104NavPostingsFormat} with default settings. */
  public Lucene104NavPostingsFormat() {
    this(
        Lucene103BlockTreeTermsWriter.DEFAULT_MIN_BLOCK_SIZE,
        Lucene103BlockTreeTermsWriter.DEFAULT_MAX_BLOCK_SIZE);
  }

  /**
   * Creates {@code Lucene104NavPostingsFormat} with custom values for {@code minBlockSize} and
   * {@code maxBlockSize} passed to block terms dictionary.
   *
   * @see
   *     Lucene103BlockTreeTermsWriter#Lucene103BlockTreeTermsWriter(SegmentWriteState,PostingsWriterBase,int,int)
   */
  public Lucene104NavPostingsFormat(int minTermBlockSize, int maxTermBlockSize) {
    super(NAME);
    Lucene103BlockTreeTermsWriter.validateSettings(minTermBlockSize, maxTermBlockSize);
    this.version = VERSION_CURRENT;
    this.minTermBlockSize = minTermBlockSize;
    this.maxTermBlockSize = maxTermBlockSize;
  }

  @Override
  public FieldsConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
    PostingsWriterBase postingsWriter = new Lucene104NavPostingsWriter(state, version);
    boolean success = false;
    try {
      FieldsConsumer ret =
          new Lucene103BlockTreeTermsWriter(
              state, postingsWriter, minTermBlockSize, maxTermBlockSize);
      success = true;
      return ret;
    } finally {
      if (success == false) {
        IOUtils.closeWhileHandlingException(postingsWriter);
      }
    }
  }

  @Override
  public FieldsProducer fieldsProducer(SegmentReadState state) throws IOException {
    PostingsReaderBase postingsReader = new Lucene104NavPostingsReader(state);
    boolean success = false;
    try {
      FieldsProducer ret = new Lucene103BlockTreeTermsReader(postingsReader, state);
      success = true;
      return ret;
    } finally {
      if (success == false) {
        IOUtils.closeWhileHandlingException(postingsReader);
      }
    }
  }

  /**
   * Holds all state required for {@link Lucene104NavPostingsReader} to produce a {@link
   * org.apache.lucene.index.PostingsEnum} without re-seeking the terms dict.
   *
   * @lucene.internal
   */
  public static final class NavIntBlockTermState extends BlockTermState {
    /** file pointer to the start of the doc ids enumeration, in {@link #DOC_EXTENSION} file */
    public long docStartFP;

    /**
     * file pointer to the start of the skip data, in {@link #NAV_EXTENSION} file. Only meaningful
     * for terms with at least {@link #BLOCK_SIZE} docs.
     */
    public long navStartFP;

    /** file pointer to the start of the positions enumeration, in {@link #POS_EXTENSION} file */
    public long posStartFP;

    /** file pointer to the start of the payloads enumeration, in {@link #PAY_EXTENSION} file */
    public long payStartFP;

    /**
     * file offset for the last position in the last block, if there are more than {@link
     * ForUtil#BLOCK_SIZE} positions; otherwise -1
     */
    public long lastPosBlockOffset;

    /**
     * docid when there is a single pulsed posting, otherwise -1. freq is always implicitly
     * totalTermFreq in this case.
     */
    public int singletonDocID;

    /** Sole constructor. */
    public NavIntBlockTermState() {
      lastPosBlockOffset = -1;
      singletonDocID = -1;
    }

    @Override
    public NavIntBlockTermState clone() {
      NavIntBlockTermState other = new NavIntBlockTermState();
      other.copyFrom(this);
      return other;
    }

    @Override
    public void copyFrom(TermState _other) {
      super.copyFrom(_other);
      NavIntBlockTermState other = (NavIntBlockTermState) _other;
      docStartFP = other.docStartFP;
      navStartFP = other.navStartFP;
      posStartFP = other.posStartFP;
      payStartFP = other.payStartFP;
      lastPosBlockOffset = other.lastPosBlockOffset;
      singletonDocID = other.singletonDocID;
    }

    @Override
    public String toString() {
      return super.toString()
          + " docStartFP="
          + docStartFP
          + " navStartFP="
          + navStartFP
          + " posStartFP="
          + posStartFP
          + " payStartFP="
          + payStartFP
          + " lastPosBlockOffset="
          + lastPosBlockOffset
          + " singletonDocID="
          + singletonDocID;
    }
  }
}
