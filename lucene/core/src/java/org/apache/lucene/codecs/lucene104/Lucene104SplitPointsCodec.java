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

import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.PointsFormat;
import org.apache.lucene.codecs.perfield.PerFieldPointsFormat;
import org.apache.lucene.index.FieldInfo;

/**
 * {@link Lucene104Codec} with a {@link PerFieldPointsFormat}, so that segments can hold points in
 * the split format. It reads every field through the format its {@link FieldInfo} attributes name.
 * When it writes, every field is stock; a codec that chooses the split format for some fields
 * writes under this name so that this class reads its segments.
 *
 * @lucene.experimental
 */
public final class Lucene104SplitPointsCodec extends FilterCodec {

  /** Name of this codec. */
  public static final String NAME = "Lucene104SplitPoints";

  private final PointsFormat pointsFormat =
      new PerFieldPointsFormat() {
        @Override
        public PointsFormat getPointsFormatForField(FieldInfo field) {
          return STOCK;
        }
      };

  /** Sole constructor. */
  public Lucene104SplitPointsCodec() {
    super(NAME, new Lucene104Codec());
  }

  @Override
  public PointsFormat pointsFormat() {
    return pointsFormat;
  }
}
