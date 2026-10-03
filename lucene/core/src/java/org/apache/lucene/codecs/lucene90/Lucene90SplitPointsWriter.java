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
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.MutablePointTree;
import org.apache.lucene.codecs.PointsReader;
import org.apache.lucene.codecs.PointsWriter;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.PointValues.IntersectVisitor;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IORunnable;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.bkd.BKDConfig;
import org.apache.lucene.util.bkd.SplitBKDWriter;

/**
 * Writes one-dimensional points in the split format of {@link Lucene90SplitPointsFormat}.
 *
 * @lucene.experimental
 */
public class Lucene90SplitPointsWriter extends PointsWriter {

  private final IndexOutput metaOut, indexOut, dataOut, valOut;
  private final SegmentWriteState writeState;
  private final int maxPointsInLeafNode;
  private boolean finished;

  /** Creates the four outputs of the segment. */
  public Lucene90SplitPointsWriter(SegmentWriteState writeState, int maxPointsInLeafNode)
      throws IOException {
    this.writeState = writeState;
    this.maxPointsInLeafNode = maxPointsInLeafNode;
    IndexOutput meta = null, index = null, data = null, values = null;
    boolean success = false;
    try {
      data = createOutput(Lucene90SplitPointsFormat.DATA_EXTENSION);
      writeHeader(data, Lucene90SplitPointsFormat.DATA_CODEC_NAME);
      values = createOutput(Lucene90SplitPointsFormat.VALUES_EXTENSION);
      writeHeader(values, Lucene90SplitPointsFormat.VALUES_CODEC_NAME);
      meta = createOutput(Lucene90SplitPointsFormat.META_EXTENSION);
      writeHeader(meta, Lucene90SplitPointsFormat.META_CODEC_NAME);
      index = createOutput(Lucene90SplitPointsFormat.INDEX_EXTENSION);
      writeHeader(index, Lucene90SplitPointsFormat.INDEX_CODEC_NAME);
      success = true;
    } finally {
      if (success == false) {
        IOUtils.closeWhileHandlingException(meta, index, data, values);
      }
    }
    this.metaOut = meta;
    this.indexOut = index;
    this.dataOut = data;
    this.valOut = values;
  }

  private IndexOutput createOutput(String extension) throws IOException {
    String fileName =
        IndexFileNames.segmentFileName(
            writeState.segmentInfo.name, writeState.segmentSuffix, extension);
    return writeState.directory.createOutput(fileName, writeState.context);
  }

  private void writeHeader(IndexOutput out, String codecName) throws IOException {
    CodecUtil.writeIndexHeader(
        out,
        codecName,
        Lucene90SplitPointsFormat.VERSION_CURRENT,
        writeState.segmentInfo.getId(),
        writeState.segmentSuffix);
  }

  private BKDConfig config(FieldInfo fieldInfo) {
    if (fieldInfo.getPointDimensionCount() != 1 || fieldInfo.getPointIndexDimensionCount() != 1) {
      throw new IllegalArgumentException(
          "split points support exactly 1 dimension, field=\""
              + fieldInfo.name
              + "\" has "
              + fieldInfo.getPointDimensionCount()
              + " dimensions and "
              + fieldInfo.getPointIndexDimensionCount()
              + " index dimensions");
    }
    return new BKDConfig(1, 1, fieldInfo.getPointNumBytes(), maxPointsInLeafNode);
  }

  @Override
  public void writeField(FieldInfo fieldInfo, PointsReader reader) throws IOException {
    BKDConfig config = config(fieldInfo);
    PointValues.PointTree values = reader.getValues(fieldInfo.name).getPointTree();
    MutablePointTree mutable;
    if (values instanceof MutablePointTree m) {
      mutable = m;
    } else {
      // only the generic merge of an index-sorted segment gets here
      mutable = HeapPointTree.copyOf(config, values);
    }
    SplitBKDWriter writer =
        new SplitBKDWriter(writeState.segmentInfo.maxDoc(), config, mutable.size());
    IORunnable finalizer = writer.writeField1Dim(metaOut, indexOut, dataOut, valOut, mutable);
    if (finalizer != null) {
      metaOut.writeInt(fieldInfo.number);
      finalizer.run();
    }
  }

  @Override
  public void merge(MergeState mergeState) throws IOException {
    if (mergeState.needsIndexSort) {
      // docs move inside segments: re-sort every point through writeField
      super.merge(mergeState);
      return;
    }
    for (PointsReader reader : mergeState.pointsReaders) {
      if (reader != null) {
        mergeState.checkAborted();
        reader.checkIntegrity();
      }
    }

    for (FieldInfo fieldInfo : mergeState.mergeFieldInfos) {
      if (fieldInfo.getPointDimensionCount() == 0) {
        continue;
      }
      BKDConfig config = config(fieldInfo);
      long totMaxSize = 0;
      List<PointValues> pointValues = new ArrayList<>();
      List<MergeState.DocMap> docMaps = new ArrayList<>();
      for (int i = 0; i < mergeState.pointsReaders.length; i++) {
        PointsReader reader = mergeState.pointsReaders[i];
        if (reader == null) {
          continue;
        }
        // resolve the field by name: field numbers can differ between segments
        FieldInfos readerFieldInfos = mergeState.fieldInfos[i];
        FieldInfo readerFieldInfo = readerFieldInfos.fieldInfo(fieldInfo.name);
        if (readerFieldInfo != null && readerFieldInfo.getPointDimensionCount() > 0) {
          PointValues values = reader.getValues(readerFieldInfo.name);
          if (values != null) {
            totMaxSize += values.size();
            pointValues.add(values);
            docMaps.add(mergeState.docMaps[i]);
          }
        }
      }
      SplitBKDWriter writer =
          new SplitBKDWriter(writeState.segmentInfo.maxDoc(), config, totMaxSize);
      IORunnable finalizer = writer.merge(metaOut, indexOut, dataOut, valOut, docMaps, pointValues);
      if (finalizer != null) {
        metaOut.writeInt(fieldInfo.number);
        finalizer.run();
      }
    }

    finish();
  }

  @Override
  public void finish() throws IOException {
    if (finished) {
      throw new IllegalStateException("already finished");
    }
    finished = true;
    metaOut.writeInt(-1);
    CodecUtil.writeFooter(indexOut);
    CodecUtil.writeFooter(dataOut);
    CodecUtil.writeFooter(valOut);
    metaOut.writeLong(indexOut.getFilePointer());
    metaOut.writeLong(dataOut.getFilePointer());
    metaOut.writeLong(valOut.getFilePointer());
    CodecUtil.writeFooter(metaOut);
  }

  @Override
  public void close() throws IOException {
    IOUtils.close(metaOut, indexOut, dataOut, valOut);
  }

  /**
   * Points copied to the heap so that a non-mutable source can be sorted and written like a flush.
   * Sorted indirectly through {@code ords}, so a swap moves 4 bytes.
   */
  static final class HeapPointTree extends MutablePointTree {
    private final int packedBytesLength;
    private final byte[] values;
    private final int[] docs;
    private final int[] ords;
    private final int size;
    private int[] temp;

    private HeapPointTree(int packedBytesLength, byte[] values, int[] docs, int size) {
      this.packedBytesLength = packedBytesLength;
      this.values = values;
      this.docs = docs;
      this.size = size;
      this.ords = new int[size];
      for (int i = 0; i < size; i++) {
        ords[i] = i;
      }
    }

    /** Copies every point of {@code source} with {@link PointValues.PointTree#visitDocValues}. */
    static HeapPointTree copyOf(BKDConfig config, PointValues.PointTree source) throws IOException {
      final int packedBytesLength = config.packedBytesLength();
      final long maxSize = source.size();
      if (maxSize > Integer.MAX_VALUE / packedBytesLength) {
        throw new IllegalArgumentException("split points: too many points for the heap merge path");
      }
      final byte[] values = new byte[Math.toIntExact(maxSize * packedBytesLength)];
      final int[] docs = new int[Math.toIntExact(maxSize)];
      final int[] count = new int[1];
      source.visitDocValues(
          new IntersectVisitor() {
            @Override
            public void visit(int docID) {
              throw new IllegalStateException();
            }

            @Override
            public void visit(int docID, byte[] packedValue) {
              int i = count[0]++;
              System.arraycopy(packedValue, 0, values, i * packedBytesLength, packedBytesLength);
              docs[i] = docID;
            }

            @Override
            public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
              return Relation.CELL_CROSSES_QUERY;
            }
          });
      return new HeapPointTree(packedBytesLength, values, docs, count[0]);
    }

    @Override
    public long size() {
      return size;
    }

    @Override
    public void visitDocValues(IntersectVisitor visitor) throws IOException {
      final byte[] packedValue = new byte[packedBytesLength];
      for (int i = 0; i < size; i++) {
        System.arraycopy(values, ords[i] * packedBytesLength, packedValue, 0, packedBytesLength);
        visitor.visit(getDocID(i), packedValue);
      }
    }

    @Override
    public void getValue(int i, BytesRef packedValue) {
      packedValue.bytes = values;
      packedValue.offset = ords[i] * packedBytesLength;
      packedValue.length = packedBytesLength;
    }

    @Override
    public byte getByteAt(int i, int k) {
      return values[ords[i] * packedBytesLength + k];
    }

    @Override
    public int getDocID(int i) {
      return docs[ords[i]];
    }

    @Override
    public void swap(int i, int j) {
      int tmp = ords[i];
      ords[i] = ords[j];
      ords[j] = tmp;
    }

    @Override
    public void save(int i, int j) {
      if (temp == null) {
        temp = new int[ords.length];
      }
      temp[j] = ords[i];
    }

    @Override
    public void restore(int i, int j) {
      if (temp != null) {
        System.arraycopy(temp, i, ords, i, j - i);
      }
    }
  }
}
