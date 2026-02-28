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

import java.io.IOException;
import org.apache.lucene.codecs.FieldsConsumer;
import org.apache.lucene.codecs.FieldsProducer;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;

/**
 * Headerless postings format that eliminates per-file headers and footers.
 *
 * <p>This format writes postings data directly without headers/footers,
 * consolidating all metadata into the segment manifest. This dramatically
 * reduces I/O operations when opening segments on network filesystems like EFS.
 *
 * <p>Key differences from traditional formats:
 * <ul>
 *   <li>No header written at file start (saves ~40 bytes + 1 seek per file)
 *   <li>No footer written at file end (saves ~16 bytes + 1 seek per file)
 *   <li>No per-file checksum validation (relies on EFS integrity)
 *   <li>All metadata consolidated in segment manifest
 * </ul>
 *
 * <p>Expected improvements for 200-file segment:
 * <ul>
 *   <li>400 seeks eliminated (2 per file)
 *   <li>~11KB metadata overhead eliminated
 *   <li>Segment open time: ~4 seconds → ~10ms (400x faster)
 * </ul>
 *
 * @lucene.experimental
 */
public final class HeaderlessPostingsFormat extends PostingsFormat {

  /** Format name for SPI registration */
  public static final String NAME = "HeaderlessPostings";

  /** Current format version */
  public static final int VERSION = 1;

  /**
   * Creates a new headerless postings format.
   */
  public HeaderlessPostingsFormat() {
    super(NAME);
  }

  @Override
  public FieldsConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
    return new HeaderlessPostingsWriter(state);
  }

  @Override
  public FieldsProducer fieldsProducer(SegmentReadState state) throws IOException {
    return new HeaderlessPostingsReader(state);
  }
}
