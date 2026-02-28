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
 * Consolidated metadata for a segment, replacing individual file headers and footers.
 *
 * <p>This class represents the manifest file structure that contains all metadata
 * previously scattered across individual segment files. The manifest is the ONLY
 * file in a headerless segment that contains a header and footer for validation.
 *
 * <p>The manifest contains:
 * <ul>
 *   <li>Segment-level metadata (ID, suffix, version)
 *   <li>Per-file metadata (codec name, version, expected length)
 *   <li>Codec-specific metadata (previously in .meta files)
 *   <li>Optional checksums (if validation mode requires)
 * </ul>
 *
 * @lucene.experimental
 */
public final class SegmentManifest {

  /** Magic number identifying manifest files: "MANI" in ASCII */
  public static final int MANIFEST_MAGIC = 0x4D414E49;

  /** Current manifest format version */
  public static final int MANIFEST_VERSION = 1;

  /** Manifest file extension */
  public static final String MANIFEST_EXTENSION = "manifest";

  private final int manifestVersion;
  private final byte[] segmentId;
  private final String segmentSuffix;
  private final Map<String, FileMetadata> fileMetadata;

  /**
   * Creates a new segment manifest.
   *
   * @param manifestVersion the manifest format version
   * @param segmentId the unique segment identifier (16 bytes)
   * @param segmentSuffix the segment suffix (e.g., codec suffix)
   * @param fileMetadata map of file names to their metadata
   */
  public SegmentManifest(
      int manifestVersion,
      byte[] segmentId,
      String segmentSuffix,
      Map<String, FileMetadata> fileMetadata) {
    if (segmentId.length != 16) {
      throw new IllegalArgumentException("Segment ID must be 16 bytes");
    }
    this.manifestVersion = manifestVersion;
    this.segmentId = segmentId.clone();
    this.segmentSuffix = segmentSuffix;
    this.fileMetadata = Collections.unmodifiableMap(new HashMap<>(fileMetadata));
  }

  /** Returns the manifest format version */
  public int getManifestVersion() {
    return manifestVersion;
  }

  /** Returns the segment ID (16 bytes) */
  public byte[] getSegmentId() {
    return segmentId.clone();
  }

  /** Returns the segment suffix */
  public String getSegmentSuffix() {
    return segmentSuffix;
  }

  /** Returns metadata for a specific file, or null if not found */
  public FileMetadata getFileMetadata(String fileName) {
    return fileMetadata.get(fileName);
  }

  /** Returns all file metadata entries */
  public Map<String, FileMetadata> getAllFileMetadata() {
    return fileMetadata;
  }

  /** Returns the number of files in this manifest */
  public int getFileCount() {
    return fileMetadata.size();
  }

  @Override
  public String toString() {
    return "SegmentManifest{" +
        "version=" + manifestVersion +
        ", suffix='" + segmentSuffix + '\'' +
        ", files=" + fileMetadata.size() +
        '}';
  }
}
