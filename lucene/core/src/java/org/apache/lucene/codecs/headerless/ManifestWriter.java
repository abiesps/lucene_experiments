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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexOutput;

/**
 * Writes segment manifests to disk.
 *
 * <p>Usage pattern:
 * <pre class="prettyprint">
 * ManifestWriter writer = new ManifestWriter();
 *
 * // Add file metadata as files are written
 * writer.addFile("_0.doc", "Lucene104Postings", 0, docLength, metadata);
 * writer.addFile("_0.pos", "Lucene104Postings", 0, posLength, metadata);
 *
 * // Write manifest at the end
 * writer.write(directory, segmentName, segmentId, segmentSuffix);
 * </pre>
 *
 * @lucene.experimental
 */
public final class ManifestWriter {

  private final List<FileMetadata> files = new ArrayList<>();

  /**
   * Adds a file to the manifest.
   *
   * @param fileName the file name (e.g., "_0.doc")
   * @param codecName the codec name (e.g., "Lucene104PostingsFormat")
   * @param codecVersion the codec version
   * @param fileLength the file length in bytes
   * @param metadata codec-specific metadata (can be null)
   */
  public void addFile(
      String fileName,
      String codecName,
      int codecVersion,
      long fileLength,
      Map<String, Object> metadata) {
    addFile(fileName, codecName, codecVersion, fileLength, null, metadata);
  }

  /**
   * Adds a file to the manifest with optional checksum.
   *
   * @param fileName the file name (e.g., "_0.doc")
   * @param codecName the codec name (e.g., "Lucene104PostingsFormat")
   * @param codecVersion the codec version
   * @param fileLength the file length in bytes
   * @param fileChecksum optional CRC32C checksum of file content
   * @param metadata codec-specific metadata (can be null)
   */
  public void addFile(
      String fileName,
      String codecName,
      int codecVersion,
      long fileLength,
      Long fileChecksum,
      Map<String, Object> metadata) {
    long lastModified = System.currentTimeMillis();
    FileMetadata fileMeta = new FileMetadata(
        fileName, codecName, codecVersion, fileLength,
        lastModified, fileChecksum, metadata);
    files.add(fileMeta);
  }

  /**
   * Writes the manifest to disk.
   *
   * @param directory the directory to write to
   * @param segmentName the segment name
   * @param segmentId the segment ID (16 bytes)
   * @param segmentSuffix the segment suffix
   * @throws IOException if an I/O error occurs
   */
  public void write(
      Directory directory,
      String segmentName,
      byte[] segmentId,
      String segmentSuffix) throws IOException {
    if (segmentId.length != 16) {
      throw new IllegalArgumentException("Segment ID must be 16 bytes");
    }

    String manifestFileName = IndexFileNames.segmentFileName(
        segmentName, segmentSuffix, SegmentManifest.MANIFEST_EXTENSION);

    try (IndexOutput out = directory.createOutput(manifestFileName, IOContext.DEFAULT)) {
      // Write manifest header (manifest is the ONLY file with header)
      out.writeInt(SegmentManifest.MANIFEST_MAGIC);
      out.writeInt(SegmentManifest.MANIFEST_VERSION);

      // Write segment ID
      out.writeBytes(segmentId, 0, 16);

      // Write segment suffix
      out.writeString(segmentSuffix);

      // Write file metadata entries
      out.writeVInt(files.size());
      for (FileMetadata meta : files) {
        writeFileMetadata(out, meta);
      }

      // Write footer (manifest is the ONLY file with footer)
      CodecUtil.writeFooter(out);
    }
  }

  /**
   * Writes a single file metadata entry.
   */
  private void writeFileMetadata(IndexOutput out, FileMetadata meta) throws IOException {
    out.writeString(meta.getFileName());
    out.writeString(meta.getCodecName());
    out.writeVInt(meta.getCodecVersion());
    out.writeVLong(meta.getExpectedLength());
    out.writeVLong(meta.getLastModified());

    // Write optional checksum
    if (meta.getFileChecksum() != null) {
      out.writeByte((byte) 1);
      out.writeLong(meta.getFileChecksum());
    } else {
      out.writeByte((byte) 0);
    }

    // Write codec-specific metadata
    Map<String, Object> metadata = meta.getMetadata();
    out.writeVInt(metadata.size());

    for (Map.Entry<String, Object> entry : metadata.entrySet()) {
      out.writeString(entry.getKey());
      writeMetadataValue(out, entry.getValue());
    }
  }

  /**
   * Writes a metadata value with its type tag.
   */
  private void writeMetadataValue(IndexOutput out, Object value) throws IOException {
    if (value instanceof Integer) {
      out.writeByte((byte) 0); // INT
      out.writeVInt((Integer) value);
    } else if (value instanceof Long) {
      out.writeByte((byte) 1); // LONG
      out.writeVLong((Long) value);
    } else if (value instanceof String) {
      out.writeByte((byte) 2); // STRING
      out.writeString((String) value);
    } else if (value instanceof Boolean) {
      out.writeByte((byte) 3); // BOOLEAN
      out.writeByte((byte) ((Boolean) value ? 1 : 0));
    } else {
      throw new IllegalArgumentException(
          "Unsupported metadata type: " + value.getClass().getName());
    }
  }

  /**
   * Returns the number of files added to this manifest.
   */
  public int getFileCount() {
    return files.size();
  }

  /**
   * Clears all file metadata.
   */
  public void clear() {
    files.clear();
  }
}
