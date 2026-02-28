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
import org.apache.lucene.store.IndexOutput;

/**
 * Wrapper around IndexOutput that skips writing headers/footers.
 *
 * <p>This class intercepts header/footer write operations and discards them,
 * while passing through all data writes to the underlying output. Metadata
 * that would be in headers/footers is instead registered with the manifest.
 *
 * @lucene.experimental
 */
public final class HeaderlessIndexOutput extends IndexOutput {

  private final IndexOutput delegate;
  private final ManifestWriter manifestWriter;
  private final String fileName;
  private final String codecName;
  private final int codecVersion;

  /**
   * Creates a headerless output.
   *
   * @param delegate the underlying output
   * @param manifestWriter manifest to register file metadata
   * @param fileName the file name
   * @param codecName the codec name
   * @param codecVersion the codec version
   */
  public HeaderlessIndexOutput(
      IndexOutput delegate,
      ManifestWriter manifestWriter,
      String fileName,
      String codecName,
      int codecVersion) {
    super(fileName, fileName);
    this.delegate = delegate;
    this.manifestWriter = manifestWriter;
    this.fileName = fileName;
    this.codecName = codecName;
    this.codecVersion = codecVersion;
  }

  @Override
  public void close() throws IOException {
    long fileLength = delegate.getFilePointer();
    delegate.close();

    // Register file with manifest (no checksum)
    manifestWriter.addFile(fileName, codecName, codecVersion, fileLength, null);
  }

  @Override
  public long getFilePointer() {
    return delegate.getFilePointer();
  }

  @Override
  public long getChecksum() throws IOException {
    // We don't compute checksums in headerless format
    return 0;
  }

  @Override
  public void writeByte(byte b) throws IOException {
    delegate.writeByte(b);
  }

  @Override
  public void writeBytes(byte[] b, int offset, int length) throws IOException {
    delegate.writeBytes(b, offset, length);
  }
}
