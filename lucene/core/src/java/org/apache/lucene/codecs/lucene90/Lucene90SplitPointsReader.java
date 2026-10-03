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
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.PointsReader;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.internal.hppc.IntObjectHashMap;
import org.apache.lucene.store.ChecksumIndexInput;
import org.apache.lucene.store.FileTypeHint;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.bkd.SplitBKDReader;

/**
 * Reads points written by {@link Lucene90SplitPointsWriter}.
 *
 * @lucene.experimental
 */
public class Lucene90SplitPointsReader extends PointsReader {
  private final IndexInput indexIn, dataIn, valIn;
  private final SegmentReadState readState;
  private final IntObjectHashMap<SplitBKDReader> readers = new IntObjectHashMap<>();

  /** Sole constructor */
  public Lucene90SplitPointsReader(SegmentReadState readState) throws IOException {
    this.readState = readState;
    IndexInput index = null, data = null, values = null;
    boolean success = false;
    try {
      index =
          openInput(
              Lucene90SplitPointsFormat.INDEX_EXTENSION,
              Lucene90SplitPointsFormat.INDEX_CODEC_NAME,
              FileTypeHint.INDEX);
      data =
          openInput(
              Lucene90SplitPointsFormat.DATA_EXTENSION,
              Lucene90SplitPointsFormat.DATA_CODEC_NAME,
              FileTypeHint.DATA);
      values =
          openInput(
              Lucene90SplitPointsFormat.VALUES_EXTENSION,
              Lucene90SplitPointsFormat.VALUES_CODEC_NAME,
              FileTypeHint.DATA);

      String metaFileName =
          IndexFileNames.segmentFileName(
              readState.segmentInfo.name,
              readState.segmentSuffix,
              Lucene90SplitPointsFormat.META_EXTENSION);
      long indexLength = -1, dataLength = -1, valLength = -1;
      try (ChecksumIndexInput metaIn = readState.directory.openChecksumInput(metaFileName)) {
        Throwable priorE = null;
        try {
          CodecUtil.checkIndexHeader(
              metaIn,
              Lucene90SplitPointsFormat.META_CODEC_NAME,
              Lucene90SplitPointsFormat.VERSION_START,
              Lucene90SplitPointsFormat.VERSION_CURRENT,
              readState.segmentInfo.getId(),
              readState.segmentSuffix);
          while (true) {
            int fieldNumber = metaIn.readInt();
            if (fieldNumber == -1) {
              break;
            } else if (fieldNumber < 0) {
              throw new CorruptIndexException("Illegal field number: " + fieldNumber, metaIn);
            }
            readers.put(fieldNumber, new SplitBKDReader(metaIn, index, data, values));
          }
          indexLength = metaIn.readLong();
          dataLength = metaIn.readLong();
          valLength = metaIn.readLong();
          for (IntObjectHashMap.IntObjectCursor<SplitBKDReader> cursor : readers) {
            if (cursor.value.getDirectoryEndFP() > indexLength) {
              throw new CorruptIndexException(
                  "directory of field "
                      + cursor.key
                      + " ends at "
                      + cursor.value.getDirectoryEndFP()
                      + " past the index length "
                      + indexLength,
                  metaIn);
            }
          }
        } catch (Throwable t) {
          priorE = t;
        } finally {
          CodecUtil.checkFooter(metaIn, priorE);
        }
      }
      CodecUtil.retrieveChecksum(index, indexLength);
      CodecUtil.retrieveChecksum(data, dataLength);
      CodecUtil.retrieveChecksum(values, valLength);
      success = true;
    } finally {
      if (success == false) {
        IOUtils.closeWhileHandlingException(index, data, values);
      }
    }
    this.indexIn = index;
    this.dataIn = data;
    this.valIn = values;
  }

  private IndexInput openInput(String extension, String codecName, FileTypeHint hint)
      throws IOException {
    String fileName =
        IndexFileNames.segmentFileName(
            readState.segmentInfo.name, readState.segmentSuffix, extension);
    IndexInput in = readState.directory.openInput(fileName, readState.context.withHints(hint));
    boolean success = false;
    try {
      CodecUtil.checkIndexHeader(
          in,
          codecName,
          Lucene90SplitPointsFormat.VERSION_START,
          Lucene90SplitPointsFormat.VERSION_CURRENT,
          readState.segmentInfo.getId(),
          readState.segmentSuffix);
      CodecUtil.retrieveChecksum(in);
      success = true;
      return in;
    } finally {
      if (success == false) {
        IOUtils.closeWhileHandlingException(in);
      }
    }
  }

  /**
   * Returns the underlying {@link SplitBKDReader}.
   *
   * @lucene.internal
   */
  @Override
  public PointValues getValues(String fieldName) {
    FieldInfo fieldInfo = readState.fieldInfos.fieldInfo(fieldName);
    if (fieldInfo == null) {
      throw new IllegalArgumentException("field=\"" + fieldName + "\" is unrecognized");
    }
    if (fieldInfo.getPointDimensionCount() == 0) {
      throw new IllegalArgumentException("field=\"" + fieldName + "\" did not index point values");
    }
    return readers.get(fieldInfo.number);
  }

  @Override
  public void checkIntegrity() throws IOException {
    CodecUtil.checksumEntireFile(indexIn);
    CodecUtil.checksumEntireFile(dataIn);
    CodecUtil.checksumEntireFile(valIn);
    for (IntObjectHashMap.IntObjectCursor<SplitBKDReader> cursor : readers) {
      cursor.value.checkLeafCounts();
    }
  }

  @Override
  public void close() throws IOException {
    IOUtils.close(indexIn, dataIn, valIn);
    readers.clear();
  }
}
