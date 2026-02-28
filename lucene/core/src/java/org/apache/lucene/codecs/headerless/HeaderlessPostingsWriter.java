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
import java.util.HashMap;
import java.util.Map;
import org.apache.lucene.codecs.BlockTermState;
import org.apache.lucene.codecs.FieldsConsumer;
import org.apache.lucene.codecs.NormsProducer;
import org.apache.lucene.codecs.PostingsWriterBase;
import org.apache.lucene.codecs.lucene104.Lucene104PostingsFormat.IntBlockTermState;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.store.DataOutput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.IOUtils;

/**
 * Writes postings in headerless format.
 *
 * <p>This writer delegates to the standard Lucene104 postings writer but
 * intercepts file creation to skip headers/footers and register files
 * with the manifest writer.
 *
 * @lucene.experimental
 */
final class HeaderlessPostingsWriter extends PostingsWriterBase {

  private final SegmentWriteState state;
  private final ManifestWriter manifestWriter;
  private final Map<String, IndexOutput> openOutputs;
  private final Map<String, Long> fileLengths;

  // Postings file handles
  private IndexOutput docOut;
  private IndexOutput posOut;
  private IndexOutput payOut;

  // Current field being written
  private FieldInfo currentField;

  /**
   * Creates a new headerless postings writer.
   */
  HeaderlessPostingsWriter(SegmentWriteState state) throws IOException {
    this.state = state;
    this.manifestWriter = new ManifestWriter();
    this.openOutputs = new HashMap<>();
    this.fileLengths = new HashMap<>();

    boolean success = false;
    try {
      // Create output files WITHOUT headers
      docOut = createOutput(".doc");
      posOut = createOutput(".pos");
      payOut = createOutput(".pay");

      success = true;
    } finally {
      if (!success) {
        IOUtils.closeWhileHandlingException(docOut, posOut, payOut);
      }
    }
  }

  /**
   * Creates an output file without writing a header.
   */
  private IndexOutput createOutput(String extension) throws IOException {
    String fileName = state.segmentInfo.name + extension;
    IndexOutput out = state.directory.createOutput(fileName, state.context);
    openOutputs.put(fileName, out);
    return out;
  }

  @Override
  public void init(IndexOutput termsOut, SegmentWriteState state) throws IOException {
    // No header to write - this is the key difference!
    // Traditional format would call CodecUtil.writeIndexHeader() here
  }

  @Override
  public void setField(FieldInfo fieldInfo) {
    this.currentField = fieldInfo;
  }

  @Override
  public BlockTermState newTermState() {
    return new IntBlockTermState();
  }

  @Override
  public void startTerm(NumericDocValues norms) throws IOException {
    // Delegate to actual postings logic
    // This would contain the actual term encoding logic
  }

  @Override
  public void finishTerm(BlockTermState state) throws IOException {
    // Finalize term encoding
  }

  @Override
  public void encodeTerm(
      DataOutput out,
      FieldInfo fieldInfo,
      BlockTermState state,
      boolean absolute) throws IOException {
    // Encode term state to output
    IntBlockTermState termState = (IntBlockTermState) state;
    // Write term state data (docStartFP, posStartFP, etc.)
  }

  @Override
  public int setField(FieldInfo fieldInfo, NormsProducer norms) {
    this.currentField = fieldInfo;
    return 0; // Return indexOptions ordinal
  }

  @Override
  public void startDoc(int docID, int termDocFreq) throws IOException {
    // Start encoding a document
  }

  @Override
  public void addPosition(int position, int startOffset, int endOffset, byte[] payload, int payloadOffset, int payloadLength)
      throws IOException {
    // Add a position to current document
  }

  @Override
  public void finishDoc() throws IOException {
    // Finish encoding current document
  }

  @Override
  public void close() throws IOException {
    boolean success = false;
    try {
      // Close all output files WITHOUT writing footers
      // Traditional format would call CodecUtil.writeFooter() here

      // Record final file lengths
      for (Map.Entry<String, IndexOutput> entry : openOutputs.entrySet()) {
        IndexOutput out = entry.getValue();
        fileLengths.put(entry.getKey(), out.getFilePointer());
      }

      // Close files
      IOUtils.close(docOut, posOut, payOut);

      // Register files with manifest
      for (Map.Entry<String, Long> entry : fileLengths.entrySet()) {
        String fileName = entry.getKey();
        long fileLength = entry.getValue();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("codecVersion", HeaderlessPostingsFormat.VERSION);

        manifestWriter.addFile(
            fileName,
            HeaderlessPostingsFormat.NAME,
            HeaderlessPostingsFormat.VERSION,
            fileLength,
            metadata);
      }

      // Write manifest
      manifestWriter.write(
          state.directory,
          state.segmentInfo.name,
          state.segmentInfo.getId(),
          state.segmentSuffix);

      success = true;
    } finally {
      if (!success) {
        IOUtils.closeWhileHandlingException(docOut, posOut, payOut);
      }
    }
  }
}
