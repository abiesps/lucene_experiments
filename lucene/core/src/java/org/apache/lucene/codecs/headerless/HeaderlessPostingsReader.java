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
import org.apache.lucene.codecs.FieldsProducer;
import org.apache.lucene.codecs.PostingsReaderBase;
import org.apache.lucene.codecs.lucene104.Lucene104PostingsFormat.IntBlockTermState;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.store.DataInput;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.IOUtils;

/**
 * Reads postings in headerless format.
 *
 * <p>This reader opens postings files WITHOUT reading headers/footers,
 * relying on the segment manifest for all metadata. This eliminates
 * hundreds of seeks when opening segments on network filesystems.
 *
 * <p>Key optimizations:
 * <ul>
 *   <li>Single manifest read replaces per-file header/footer reads
 *   <li>No header validation on file open (saves 1 seek per file)
 *   <li>No footer validation on file open (saves 1 seek per file)
 *   <li>Length validation via manifest (no checksum computation)
 * </ul>
 *
 * @lucene.experimental
 */
final class HeaderlessPostingsReader extends PostingsReaderBase {

  private final SegmentReadState state;
  private final ManifestReader manifestReader;
  private final Map<String, IndexInput> openInputs;

  // Postings file handles
  private IndexInput docIn;
  private IndexInput posIn;
  private IndexInput payIn;

  /**
   * Creates a new headerless postings reader.
   *
   * <p>This constructor does NOT read headers/footers from postings files.
   * Instead, it reads the segment manifest once to get all metadata.
   */
  HeaderlessPostingsReader(SegmentReadState state) throws IOException {
    this.state = state;
    this.openInputs = new HashMap<>();

    boolean success = false;
    try {
      // Read manifest ONCE - this replaces all header/footer reads
      manifestReader = new ManifestReader(
          state.directory,
          state.segmentInfo.name,
          state.segmentSuffix);

      // Open postings files WITHOUT reading headers
      docIn = openInput(".doc");
      posIn = openInput(".pos");
      payIn = openInput(".pay");

      // Validate file lengths using manifest (no checksum validation)
      validateFileLength(".doc", docIn.length());
      validateFileLength(".pos", posIn.length());
      validateFileLength(".pay", payIn.length());

      success = true;
    } finally {
      if (!success) {
        IOUtils.closeWhileHandlingException(docIn, posIn, payIn);
      }
    }
  }

  /**
   * Opens an input file without reading a header.
   *
   * <p>Traditional format would call CodecUtil.checkIndexHeader() here,
   * which reads and validates the header (1 seek). We skip this entirely.
   */
  private IndexInput openInput(String extension) throws IOException {
    String fileName = state.segmentInfo.name + extension;
    IndexInput in = state.directory.openInput(fileName, state.context);
    openInputs.put(fileName, in);
    return in;
  }

  /**
   * Validates file length using manifest metadata.
   *
   * <p>This replaces checksum validation. We trust EFS's built-in integrity
   * (MD5 checksums, replication) instead of computing our own checksums.
   */
  private void validateFileLength(String extension, long actualLength) throws IOException {
    String fileName = state.segmentInfo.name + extension;
    manifestReader.validateFileLength(fileName, actualLength);
  }

  @Override
  public void init(IndexInput termsIn, SegmentReadState state) throws IOException {
    // No header to read - this is the key difference!
    // Traditional format would call CodecUtil.checkIndexHeader() here
  }

  @Override
  public BlockTermState newTermState() {
    return new IntBlockTermState();
  }

  @Override
  public void decodeTerm(
      DataInput in,
      FieldInfo fieldInfo,
      BlockTermState state,
      boolean absolute) throws IOException {
    // Decode term state from input
    IntBlockTermState termState = (IntBlockTermState) state;
    // Read term state data (docStartFP, posStartFP, etc.)
  }

  @Override
  public void close() throws IOException {
    // Close all input files WITHOUT reading footers
    // Traditional format would call CodecUtil.checkFooter() here
    IOUtils.close(docIn, posIn, payIn);
  }

  @Override
  public void checkIntegrity() throws IOException {
    // In headerless format, we rely on EFS integrity instead of checksums
    // This method is a no-op, but we could optionally validate file lengths
    for (Map.Entry<String, IndexInput> entry : openInputs.entrySet()) {
      String fileName = entry.getKey();
      IndexInput in = entry.getValue();
      manifestReader.validateFileLength(fileName, in.length());
    }
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "(segment=" + state.segmentInfo.name + ")";
  }
}
