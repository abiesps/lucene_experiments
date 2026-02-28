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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Metadata for a single file in a headerless segment.
 *
 * <p>This replaces the header and footer information that would normally be
 * written to each individual file. Instead, all this metadata is consolidated
 * in the segment manifest.
 *
 * @lucene.experimental
 */
public final class FileMetadata {

  private final String fileName;
  private final String codecName;
  private final int codecVersion;
  private final long expectedLength;
  private final long lastModified;
  private final Long fileChecksum; // nullable
  private final Map<String, Object> metadata;

  /**
   * Creates file metadata.
   *
   * @param fileName the file name (e.g., "_0.doc")
   * @param codecName the codec name (e.g., "Lucene104PostingsFormat")
   * @param codecVersion the codec version
   * @param expectedLength the expected file length in bytes
   * @param lastModified the last modified timestamp
   * @param fileChecksum optional CRC32C checksum of file content
   * @param metadata codec-specific metadata (replaces .meta files)
   */
  public FileMetadata(
      String fileName,
      String codecName,
      int codecVersion,
      long expectedLength,
      long lastModified,
      Long fileChecksum,
      Map<String, Object> metadata) {
    this.fileName = fileName;
    this.codecName = codecName;
    this.codecVersion = codecVersion;
    this.expectedLength = expectedLength;
    this.lastModified = lastModified;
    this.fileChecksum = fileChecksum;
    this.metadata = metadata == null ?
        Collections.emptyMap() :
        Collections.unmodifiableMap(new HashMap<>(metadata));
  }

  /** Returns the file name */
  public String getFileName() {
    return fileName;
  }

  /** Returns the codec name */
  public String getCodecName() {
    return codecName;
  }

  /** Returns the codec version */
  public int getCodecVersion() {
    return codecVersion;
  }

  /** Returns the expected file length in bytes */
  public long getExpectedLength() {
    return expectedLength;
  }

  /** Returns the last modified timestamp */
  public long getLastModified() {
    return lastModified;
  }

  /** Returns the file checksum, or null if not available */
  public Long getFileChecksum() {
    return fileChecksum;
  }

  /** Returns codec-specific metadata */
  public Map<String, Object> getMetadata() {
    return metadata;
  }

  /** Returns a specific metadata value, or null if not found */
  public Object getMetadataValue(String key) {
    return metadata.get(key);
  }

  /** Returns a specific metadata value as an integer */
  public Integer getMetadataInt(String key) {
    Object value = metadata.get(key);
    return value instanceof Integer ? (Integer) value : null;
  }

  /** Returns a specific metadata value as a long */
  public Long getMetadataLong(String key) {
    Object value = metadata.get(key);
    return value instanceof Long ? (Long) value : null;
  }

  @Override
  public String toString() {
    return "FileMetadata{" +
        "fileName='" + fileName + '\'' +
        ", codec='" + codecName + '\'' +
        ", version=" + codecVersion +
        ", length=" + expectedLength +
        ", metadata=" + metadata.size() + " entries" +
        '}';
  }
}
