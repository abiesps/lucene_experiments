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
package org.apache.lucene.codecs.lucene90;

import java.io.IOException;
import org.apache.lucene.codecs.PointsFormat;
import org.apache.lucene.codecs.PointsReader;
import org.apache.lucene.codecs.PointsWriter;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.util.bkd.BKDConfig;

/**
 * One-dimensional points in a block KD-tree whose leaves are split in two streams, written by
 * {@link org.apache.lucene.util.bkd.SplitBKDWriter}. Four files per segment:
 *
 * <ul>
 *   <li>{@code .kdm}: per-field metadata.
 *   <li>{@code .kdi}: the inner nodes, encoded as {@link Lucene90PointsFormat} encodes them, then a
 *       leaf directory with the tight bounds, count, doc ID range and block lengths of every leaf.
 *   <li>{@code .kdd}: per leaf, the count and the doc IDs, in value order.
 *   <li>{@code .kdv}: per leaf, the values, encoded as {@link Lucene90PointsFormat} encodes them.
 * </ul>
 *
 * <p>Fields with more than one dimension are not supported; use it through {@link
 * org.apache.lucene.codecs.perfield.PerFieldPointsFormat}.
 *
 * @lucene.experimental
 */
public final class Lucene90SplitPointsFormat extends PointsFormat {

  /** Codec name of the metadata file. */
  public static final String META_CODEC_NAME = "Lucene90SplitPointsFormatMeta";

  /** Codec name of the index file. */
  public static final String INDEX_CODEC_NAME = "Lucene90SplitPointsFormatIndex";

  /** Codec name of the doc ID file. */
  public static final String DATA_CODEC_NAME = "Lucene90SplitPointsFormatData";

  /** Codec name of the values file. */
  public static final String VALUES_CODEC_NAME = "Lucene90SplitPointsFormatValues";

  /** Filename extension for the metadata. */
  public static final String META_EXTENSION = "kdm";

  /** Filename extension for the inner nodes and the leaf directory. */
  public static final String INDEX_EXTENSION = "kdi";

  /** Filename extension for the leaf doc IDs. */
  public static final String DATA_EXTENSION = "kdd";

  /** Filename extension for the leaf values. */
  public static final String VALUES_EXTENSION = "kdv";

  static final int VERSION_START = 0;
  static final int VERSION_CURRENT = VERSION_START;

  private final int maxPointsInLeafNode;

  /** Uses {@link BKDConfig#DEFAULT_MAX_POINTS_IN_LEAF_NODE} points per leaf. */
  public Lucene90SplitPointsFormat() {
    this(BKDConfig.DEFAULT_MAX_POINTS_IN_LEAF_NODE);
  }

  /** Writes at most {@code maxPointsInLeafNode} points per leaf. */
  public Lucene90SplitPointsFormat(int maxPointsInLeafNode) {
    if (maxPointsInLeafNode <= 0) {
      throw new IllegalArgumentException(
          "maxPointsInLeafNode must be > 0, got " + maxPointsInLeafNode);
    }
    this.maxPointsInLeafNode = maxPointsInLeafNode;
  }

  @Override
  public PointsWriter fieldsWriter(SegmentWriteState state) throws IOException {
    return new Lucene90SplitPointsWriter(state, maxPointsInLeafNode);
  }

  @Override
  public PointsReader fieldsReader(SegmentReadState state) throws IOException {
    return new Lucene90SplitPointsReader(state);
  }
}
