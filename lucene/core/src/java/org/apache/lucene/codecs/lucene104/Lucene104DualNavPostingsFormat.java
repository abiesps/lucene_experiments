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
import org.apache.lucene.codecs.lucene104.Lucene104PostingsFormat.IntBlockTermState;
import org.apache.lucene.index.TermState;
import org.apache.lucene.util.IOUtils;

/**
 * Experimental postings format that writes the stock {@link Lucene104PostingsFormat} files
 * unchanged and adds a duplicate copy of the skip metadata in a separate file, so that a reader can
 * take skip data from either place.
 *
 * <ul>
 *   <li><b>.doc, .pos, .pay, .psm</b>: the same bytes {@link Lucene104PostingsFormat} writes,
 *       including the skip data interleaved in .doc, apart from the file headers (which name the
 *       segment suffix). The terms dictionary starts each term's metadata with the stock encoding.
 *   <li><b>.nav</b>: for each term with at least {@value #BLOCK_SIZE} docs, a copy of its level-1
 *       and level-0 skip entries (last doc delta, impacts, pos/pay pointers) plus the position of
 *       each block's payload in .doc, stored as the gap from the end of the previous payload and
 *       the payload length. A level-1 entry also records the .doc bytes its 32 blocks span.
 *   <li><b>.nsm</b>: metadata of .nav.
 *   <li>Terms dictionary: after the stock metadata, terms with skip data add their .nav start
 *       pointer and the byte length of their postings in .doc.
 * </ul>
 *
 * <p>{@link ReadMode#DOC} reads exactly like {@link Lucene104PostingsFormat} (it uses its reader),
 * {@link ReadMode#NAV} skips with .nav and reads only block payloads from .doc. The mode is read
 * each time a postings list is opened, so it can change between queries.
 *
 * @lucene.experimental
 */
public final class Lucene104DualNavPostingsFormat extends PostingsFormat {

  /** Name of this format, as recorded in the segment. */
  public static final String NAME = "Lucene104DualNav";

  /** Filename extension for the duplicate skip metadata. */
  public static final String NAV_EXTENSION = "nav";

  /** Filename extension for the metadata of the .nav file. */
  public static final String NAV_META_EXTENSION = "nsm";

  /** Size of blocks. */
  public static final int BLOCK_SIZE = ForUtil.BLOCK_SIZE;

  static final String NAV_CODEC = "Lucene104DualNavPostingsWriterNav";
  static final String NAV_META_CODEC = "Lucene104DualNavPostingsWriterNavMeta";
  static final int VERSION_START = 0;
  static final int VERSION_CURRENT = VERSION_START;

  /** Where a reader takes skip data from. */
  public enum ReadMode {
    /** Skip data interleaved in .doc, exactly as {@link Lucene104PostingsFormat} reads it. */
    DOC,
    /** Skip data from .nav; .doc is only read at block payloads. */
    NAV
  }

  private static volatile ReadMode readMode = ReadMode.DOC;

  /** Sets the read mode of all readers of this format, for postings lists opened from now on. */
  public static void setReadMode(ReadMode mode) {
    readMode = mode;
  }

  /** Returns the current read mode. */
  public static ReadMode getReadMode() {
    return readMode;
  }

  private final int minTermBlockSize;
  private final int maxTermBlockSize;

  /** Creates {@code Lucene104DualNavPostingsFormat} with default settings. */
  public Lucene104DualNavPostingsFormat() {
    this(
        Lucene103BlockTreeTermsWriter.DEFAULT_MIN_BLOCK_SIZE,
        Lucene103BlockTreeTermsWriter.DEFAULT_MAX_BLOCK_SIZE);
  }

  /**
   * Creates {@code Lucene104DualNavPostingsFormat} with custom values for {@code minBlockSize} and
   * {@code maxBlockSize} passed to block terms dictionary.
   */
  public Lucene104DualNavPostingsFormat(int minTermBlockSize, int maxTermBlockSize) {
    super(NAME);
    Lucene103BlockTreeTermsWriter.validateSettings(minTermBlockSize, maxTermBlockSize);
    this.minTermBlockSize = minTermBlockSize;
    this.maxTermBlockSize = maxTermBlockSize;
  }

  @Override
  public FieldsConsumer fieldsConsumer(org.apache.lucene.index.SegmentWriteState state)
      throws IOException {
    PostingsWriterBase postingsWriter = new Lucene104DualNavPostingsWriter(state);
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
  public FieldsProducer fieldsProducer(org.apache.lucene.index.SegmentReadState state)
      throws IOException {
    PostingsReaderBase postingsReader = new Lucene104DualNavPostingsReader(state);
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
   * Term state of this format: the stock {@link IntBlockTermState} plus the .nav start pointer and
   * the .doc length.
   *
   * @lucene.internal
   */
  public static final class DualNavTermState extends BlockTermState {
    /** The stock term state, read and used by {@link Lucene104PostingsReader}. */
    public final IntBlockTermState base = new IntBlockTermState();

    /** Start of the term's skip data in .nav; only meaningful if docFreq >= BLOCK_SIZE. */
    public long navStartFP;

    /** Bytes of the term's postings in .doc; -1 when not recorded (fewer than BLOCK_SIZE docs). */
    public long docLength = -1;

    /** Sole constructor. */
    public DualNavTermState() {}

    /** Copies docFreq and totalTermFreq into {@link #base}, which the stock reader reads. */
    void syncStats() {
      base.docFreq = docFreq;
      base.totalTermFreq = totalTermFreq;
    }

    @Override
    public DualNavTermState clone() {
      DualNavTermState other = new DualNavTermState();
      other.copyFrom(this);
      return other;
    }

    @Override
    public void copyFrom(TermState _other) {
      super.copyFrom(_other);
      DualNavTermState other = (DualNavTermState) _other;
      base.copyFrom(other.base);
      navStartFP = other.navStartFP;
      docLength = other.docLength;
    }

    @Override
    public String toString() {
      return super.toString()
          + " base=["
          + base
          + "] navStartFP="
          + navStartFP
          + " docLength="
          + docLength;
    }
  }
}
