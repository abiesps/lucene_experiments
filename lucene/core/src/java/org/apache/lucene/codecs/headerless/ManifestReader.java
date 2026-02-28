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
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.store.ChecksumIndexInput;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;

/**
 * Reads segment manifests from disk.
 *
 * <p>The manifest is the ONLY file in a headerless segment that contains
 * a header and footer. This is necessary to validate the manifest itself
 * and ensure it hasn't been corrupted.
 *
 * @lucene.experimental
 */
public final class ManifestReader {

  private final SegmentManifest manifest;

  /**
   * Reads a segment manifest from the directory.
   *
   * @param directory the directory containing the manifest
   * @param segmentName the segment name
   * @throws IOException if an I/O error occurs
   * @throws CorruptIndexException if the manifest is corrupted
   */
  public ManifestReader(Directory directory, String segmentName) throws IOException {
    this(directory, segmentName, "");
  }

  /**
   * Reads a segment manifest from the directory with a suffix.
   *
   * @param directory the directory containing the manifest
   * @param segmentName the segment name
   * @param segmentSuffix the segment suffix
   * @throws IOException if an I/O error occurs
   * @throws CorruptIndexException if the manifest is corrupted
   */
  public ManifestReader(Directory directory, String segmentName, String segmentSuffix)
      throws IOException {
    String manifestFileName = IndexFileNames.segmentFileName(
        segmentName, segmentSuffix, SegmentManifest.MANIFEST_EXTENSION);

    try (ChecksumIndexInput in = directory.openChecksumInput(manifestFileName)) {
      // Read and validate manifest header
      int magic = in.readInt();
      if (magic != SegmentManifest.MANIFEST_MAGIC) {
        throw new CorruptIndexException(
            "Invalid manifest magic: expected=" +
            Integer.toHexString(SegmentManifest.MANIFEST_MAGIC) +
            " actual=" + Integer.toHexString(magic), in);
      }

      int manifestVersion = in.readInt();
      if (manifestVersion < 1 || manifestVersion > SegmentManifest.MANIFEST_VERSION) {
        throw new CorruptIndexException(
            "Unsupported manifest version: " + manifestVersion, in);
      }

      // Read segment ID
      byte[] segmentId = new byte[16];
      in.readBytes(segmentId, 0, 16);

      // Read segment suffix
      String suffix = in.readString();

      // Read file metadata entries
      int numFiles = in.readVInt();
      Map<String, FileMetadata> fileMetadata = new HashMap<>(numFiles);

      for (int i = 0; i < numFiles; i++) {
        FileMetadata meta = readFileMetadata(in);
        fileMetadata.put(meta.getFileName(), meta);
      }

      // Validate footer (manifest is the only file with footer)
      CodecUtil.checkFooter(in);

      this.manifest = new SegmentManifest(
          manifestVersion, segmentId, suffix, fileMetadata);
    }
  }

  /**
   * Reads a single file metadata entry.
   */
  private FileMetadata readFileMetadata(ChecksumIndexInput in) throws IOException {
    String fileName = in.readString();
    String codecName = in.readString();
    int codecVersion = in.readVInt();
    long expectedLength = in.readVLong();
    long lastModified = in.readVLong();

    // Read optional checksum
    boolean hasChecksum = in.readByte() != 0;
    Long fileChecksum = hasChecksum ? in.readLong() : null;

    // Read codec-specific metadata
    int metadataCount = in.readVInt();
    Map<String, Object> metadata = new HashMap<>(metadataCount);

    for (int i = 0; i < metadataCount; i++) {
      String key = in.readString();
      byte type = in.readByte();
      Object value = readMetadataValue(in, type);
      metadata.put(key, value);
    }

    return new FileMetadata(
        fileName, codecName, codecVersion, expectedLength,
        lastModified, fileChecksum, metadata);
  }

  /**
   * Reads a metadata value based on its type.
   */
  private Object readMetadataValue(ChecksumIndexInput in, byte type) throws IOException {
    switch (type) {
      case 0: // INT
        return in.readVInt();
      case 1: // LONG
        return in.readVLong();
      case 2: // STRING
        return in.readString();
      case 3: // BOOLEAN
        return in.readByte() != 0;
      default:
        throw new CorruptIndexException("Unknown metadata type: " + type, in);
    }
  }

  /**
   * Returns the segment manifest.
   */
  public SegmentManifest getManifest() {
    return manifest;
  }

  /**
   * Returns metadata for a specific file.
   *
   * @param fileName the file name
   * @return the file metadata, or null if not found
   */
  public FileMetadata getFileMetadata(String fileName) {
    return manifest.getFileMetadata(fileName);
  }

  /**
   * Validates that a file's actual length matches the expected length.
   *
   * @param fileName the file name
   * @param actualLength the actual file length
   * @throws CorruptIndexException if the lengths don't match
   */
  public void validateFileLength(String fileName, long actualLength) throws IOException {
    FileMetadata meta = manifest.getFileMetadata(fileName);
    if (meta == null) {
      throw new CorruptIndexException(
          "File not found in manifest: " + fileName, fileName);
    }

    if (meta.getExpectedLength() != actualLength) {
      throw new CorruptIndexException(
          "File length mismatch for " + fileName +
          ": expected=" + meta.getExpectedLength() +
          " actual=" + actualLength, fileName);
    }
  }
}
