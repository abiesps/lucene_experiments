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
package org.apache.lucene.codecs.headerless;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.CompoundFormat;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.FieldInfosFormat;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.LiveDocsFormat;
import org.apache.lucene.codecs.NormsFormat;
import org.apache.lucene.codecs.PointsFormat;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.SegmentInfoFormat;
import org.apache.lucene.codecs.StoredFieldsFormat;
import org.apache.lucene.codecs.TermVectorsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;

/**
 * Headerless codec that eliminates per-file headers and footers.
 *
 * <p>This codec wraps all Lucene104 format components with headerless versions
 * that skip header/footer I/O and use the segment manifest for metadata.
 *
 * <p>Supported components:
 * <ul>
 *   <li>Postings (terms, docs, positions, payloads)
 *   <li>Stored fields
 *   <li>Doc values (numeric, binary, sorted, sorted numeric, sorted set)
 *   <li>Norms
 *   <li>Points (BKD trees)
 *   <li>Term vectors
 *   <li>KNN vectors
 *   <li>Compound files (.cfs, .cfe)
 * </ul>
 *
 * <p>Expected improvements for 200-file segment:
 * <ul>
 *   <li>400 seeks eliminated (2 per file)
 *   <li>~11KB metadata overhead eliminated
 *   <li>Segment open: ~4 seconds → ~10ms (400x faster on EFS)
 * </ul>
 *
 * @lucene.experimental
 */
public final class HeaderlessCodec extends Codec {

  /** Codec name for SPI registration */
  public static final String CODEC_NAME = "Headerless";

  /** Current codec version */
  public static final int VERSION = 1;

  private final PostingsFormat postingsFormat;
  private final StoredFieldsFormat storedFieldsFormat;
  private final DocValuesFormat docValuesFormat;
  private final NormsFormat normsFormat;
  private final PointsFormat pointsFormat;
  private final TermVectorsFormat termVectorsFormat;
  private final KnnVectorsFormat knnVectorsFormat;
  private final CompoundFormat compoundFormat;

  // These formats don't have headers/footers, so we use standard implementations
  private final FieldInfosFormat fieldInfosFormat;
  private final SegmentInfoFormat segmentInfoFormat;
  private final LiveDocsFormat liveDocsFormat;

  /**
   * Creates a new headerless codec with default settings.
   */
  public HeaderlessCodec() {
    super(CODEC_NAME);

    // Wrap all format components with headerless versions
    this.postingsFormat = new HeaderlessPostingsFormat();
    this.storedFieldsFormat = new HeaderlessStoredFieldsFormat();
    this.docValuesFormat = new HeaderlessDocValuesFormat();
    this.normsFormat = new HeaderlessNormsFormat();
    this.pointsFormat = new HeaderlessPointsFormat();
    this.termVectorsFormat = new HeaderlessTermVectorsFormat();
    this.knnVectorsFormat = new HeaderlessKnnVectorsFormat();
    this.compoundFormat = new HeaderlessCompoundFormat();

    // Use standard formats for components without headers/footers
    Lucene104Codec delegate = new Lucene104Codec();
    this.fieldInfosFormat = delegate.fieldInfosFormat();
    this.segmentInfoFormat = delegate.segmentInfoFormat();
    this.liveDocsFormat = delegate.liveDocsFormat();
  }

  @Override
  public PostingsFormat postingsFormat() {
    return postingsFormat;
  }

  @Override
  public StoredFieldsFormat storedFieldsFormat() {
    return storedFieldsFormat;
  }

  @Override
  public DocValuesFormat docValuesFormat() {
    return docValuesFormat;
  }

  @Override
  public NormsFormat normsFormat() {
    return normsFormat;
  }

  @Override
  public PointsFormat pointsFormat() {
    return pointsFormat;
  }

  @Override
  public TermVectorsFormat termVectorsFormat() {
    return termVectorsFormat;
  }

  @Override
  public KnnVectorsFormat knnVectorsFormat() {
    return knnVectorsFormat;
  }

  @Override
  public CompoundFormat compoundFormat() {
    return compoundFormat;
  }

  @Override
  public FieldInfosFormat fieldInfosFormat() {
    return fieldInfosFormat;
  }

  @Override
  public SegmentInfoFormat segmentInfoFormat() {
    return segmentInfoFormat;
  }

  @Override
  public LiveDocsFormat liveDocsFormat() {
    return liveDocsFormat;
  }
}
