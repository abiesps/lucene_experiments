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
import org.apache.lucene.store.IndexInput;

/**
 * Wrapper around IndexInput that presents a view without headers/footers.
 *
 * <p>This class makes headerless files appear as if they have headers/footers
 * to existing codec implementations, allowing us to reuse all the standard
 * Lucene codec logic without modification.
 *
 * <p>Key behaviors:
 * <ul>
 *   <li>File appears to start at position 0 (hiding header offset)
 *   <li>File length excludes header and footer
 *   <li>Seeks are adjusted to account for missing header
 *   <li>Reads are passed through to underlying input
 * </ul>
 *
 * @lucene.experimental
 */
public final class HeaderlessIndexInput extends IndexInput {

  private final IndexInput delegate;
  private final long headerLength;
  private final long footerLength;
  private final long dataLength;

  /**
   * Creates a headerless view of an index input.
   *
   * @param delegate the underlying input (positioned at start of data, after header)
   * @param resourceDescription description for error messages
   * @param headerLength length of header that was skipped
   * @param footerLength length of footer to hide
   */
  public HeaderlessIndexInput(
      IndexInput delegate,
      String resourceDescription,
      long headerLength,
      long footerLength) {
    super(resourceDescription);
    this.delegate = delegate;
    this.headerLength = headerLength;
    this.footerLength = footerLength;
    this.dataLength = delegate.length() - headerLength - footerLength;
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }

  @Override
  public long getFilePointer() {
    // Return position relative to data start (hide header offset)
    return delegate.getFilePointer() - headerLength;
  }

  @Override
  public void seek(long pos) throws IOException {
    // Adjust seek to account for header
    if (pos < 0 || pos > dataLength) {
      throw new IOException("Seek out of range: " + pos + " (length=" + dataLength + ")");
    }
    delegate.seek(pos + headerLength);
  }

  @Override
  public long length() {
    // Return data length (excluding header and footer)
    return dataLength;
  }

  @Override
  public IndexInput slice(String sliceDescription, long offset, long length) throws IOException {
    // Create slice of underlying input, adjusting for header
    IndexInput slice = delegate.slice(sliceDescription, offset + headerLength, length);
    // Slices don't have their own headers/footers
    return new HeaderlessIndexInput(slice, sliceDescription, 0, 0);
  }

  @Override
  public byte readByte() throws IOException {
    return delegate.readByte();
  }

  @Override
  public void readBytes(byte[] b, int offset, int len) throws IOException {
    delegate.readBytes(b, offset, len);
  }

  @Override
  public HeaderlessIndexInput clone() {
    HeaderlessIndexInput clone = (HeaderlessIndexInput) super.clone();
    // Note: delegate is not cloned here, will be handled by superclass
    return clone;
  }
}
