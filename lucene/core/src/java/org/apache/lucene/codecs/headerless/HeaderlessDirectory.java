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
import java.util.Collection;
import java.util.Set;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.Lock;

/**
 * Directory wrapper that provides headerless views of files.
 *
 * <p>This directory intercepts file opens to:
 * <ul>
 *   <li>Skip headers when opening for read
 *   <li>Skip writing headers/footers when creating files
 *   <li>Register files with manifest writer
 *   <li>Validate files using manifest reader
 * </ul>
 *
 * @lucene.experimental
 */
public final class HeaderlessDirectory extends FilterDirectory {

  private final ManifestReader manifestReader;
  private final ManifestWriter manifestWriter;
  private final String segmentName;

  /**
   * Creates a headerless directory for reading.
   *
   * @param delegate the underlying directory
   * @param segmentName the segment name
   * @param manifestReader the manifest reader
   */
  public HeaderlessDirectory(
      Directory delegate,
      String segmentName,
      ManifestReader manifestReader) {
    super(delegate);
    this.segmentName = segmentName;
    this.manifestReader = manifestReader;
    this.manifestWriter = null;
  }

  /**
   * Creates a headerless directory for writing.
   *
   * @param delegate the underlying directory
   * @param segmentName the segment name
   * @param manifestWriter the manifest writer
   */
  public HeaderlessDirectory(
      Directory delegate,
      String segmentName,
      ManifestWriter manifestWriter) {
    super(delegate);
    this.segmentName = segmentName;
    this.manifestReader = null;
    this.manifestWriter = manifestWriter;
  }

  @Override
  public IndexInput openInput(String name, IOContext context) throws IOException {
    IndexInput input = in.openInput(name, context);

    // If we have a manifest, validate and create headerless view
    if (manifestReader != null && name.startsWith(segmentName)) {
      FileMetadata meta = manifestReader.getFileMetadata(name);
      if (meta != null) {
        // Validate file length
        manifestReader.validateFileLength(name, input.length());

        // Files in headerless format have no headers/footers
        // Return input as-is since there's nothing to skip
        return input;
      }
    }

    // Non-segment files or files not in manifest - return as-is
    return input;
  }

  @Override
  public IndexOutput createOutput(String name, IOContext context) throws IOException {
    IndexOutput output = in.createOutput(name, context);

    // If we have a manifest writer and this is a segment file, wrap it
    if (manifestWriter != null && name.startsWith(segmentName)) {
      // Codec name and version will be set by the format component
      return new HeaderlessIndexOutput(output, manifestWriter, name, "Unknown", 0);
    }

    // Non-segment files - return as-is
    return output;
  }

  /**
   * Returns the manifest reader (for read operations).
   */
  public ManifestReader getManifestReader() {
    return manifestReader;
  }

  /**
   * Returns the manifest writer (for write operations).
   */
  public ManifestWriter getManifestWriter() {
    return manifestWriter;
  }
}
