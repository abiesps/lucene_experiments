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

/**
 * Headerless codec infrastructure for reducing file I/O operations.
 *
 * <h2>Overview</h2>
 *
 * <p>The headerless codec format eliminates per-file headers, footers, and checksums,
 * consolidating all metadata into a single manifest file per segment. This dramatically
 * reduces the number of I/O operations required to open a segment, which is critical
 * for large-scale deployments on network file systems like EFS.
 *
 * <h2>Architecture</h2>
 *
 * <p>Traditional Lucene segments write a header and footer to every file:
 * <ul>
 *   <li>Header: ~40-75 bytes (magic, codec name, version, segment ID, suffix)
 *   <li>Footer: ~16 bytes (algorithm ID, checksum, footer magic)
 *   <li>Separate .meta files for codec-specific metadata
 * </ul>
 *
 * <p>For a segment with 200 files, this means:
 * <ul>
 *   <li>400 seeks (2 per file: header + footer)
 *   <li>~11-23 KB of metadata overhead
 *   <li>Significant latency on network file systems
 * </ul>
 *
 * <p>The headerless format consolidates all this metadata into a single manifest file:
 * <ul>
 *   <li>1 seek to read the manifest
 *   <li>All file metadata available immediately
 *   <li>No per-file header/footer reads
 *   <li>Eliminates separate .meta files
 * </ul>
 *
 * <h2>File Structure</h2>
 *
 * <p>Headerless segment files:
 * <pre>
 * _0.doc          (raw postings data, no header/footer)
 * _0.pos          (raw positions data, no header/footer)
 * _0.pay          (raw payloads data, no header/footer)
 * _0.tim          (raw terms data, no header/footer)
 * _0.tip          (raw terms index, no header/footer)
 * _0.manifest     (consolidated metadata, ONLY file with header/footer)
 * </pre>
 *
 * <h2>Manifest Format</h2>
 *
 * <p>The manifest file structure:
 * <pre>
 * MANIFEST_MAGIC (4 bytes)
 * Manifest Version (4 bytes)
 * Segment ID (16 bytes)
 * Segment Suffix (string)
 * File Count (vint)
 * For each file:
 *   File Name (string)
 *   Codec Name (string)
 *   Codec Version (vint)
 *   Expected Length (vlong)
 *   Last Modified (vlong)
 *   Has Checksum (byte)
 *   [Optional] Checksum (long)
 *   Metadata Count (vint)
 *   For each metadata entry:
 *     Key (string)
 *     Type (byte)
 *     Value (type-specific)
 * Footer (checksum + magic)
 * </pre>
 *
 * <h2>Validation</h2>
 *
 * <p>Without per-file checksums, validation strategies include:
 * <ul>
 *   <li><b>Length validation</b>: Compare actual vs expected file length (very cheap)
 *   <li><b>Structural validation</b>: Verify codec-specific invariants during read
 *   <li><b>Lazy checksums</b>: Compute checksums on demand if needed
 *   <li><b>Trust EFS</b>: Rely on EFS's built-in integrity (MD5, replication)
 * </ul>
 *
 * <h2>Performance Impact</h2>
 *
 * <p>Expected improvements for a 1000-shard cluster:
 * <ul>
 *   <li>Segment open time: 4s → 0.01s (400x faster)
 *   <li>Cluster restart: 66 minutes → 10 seconds (396x faster)
 *   <li>File handles: 300k → 20k (15x reduction)
 *   <li>EFS delegation cache: fits within 61k limit
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <p>Writing a headerless segment:
 * <pre class="prettyprint">
 * ManifestWriter manifest = new ManifestWriter();
 *
 * // Write data files without headers/footers
 * IndexOutput docOut = dir.createOutput("_0.doc", context);
 * // ... write data ...
 * long docLength = docOut.getFilePointer();
 * docOut.close();
 *
 * // Register in manifest
 * Map&lt;String, Object&gt; metadata = new HashMap&lt;&gt;();
 * metadata.put("maxImpacts", 10);
 * manifest.addFile("_0.doc", "Lucene104Postings", 0, docLength, metadata);
 *
 * // Write manifest at the end
 * manifest.write(dir, segmentName, segmentId, suffix);
 * </pre>
 *
 * <p>Reading a headerless segment:
 * <pre class="prettyprint">
 * ManifestReader manifest = new ManifestReader(dir, segmentName);
 *
 * // Open data file without reading header
 * IndexInput docIn = dir.openInput("_0.doc", context);
 *
 * // Validate using manifest
 * FileMetadata meta = manifest.getFileMetadata("_0.doc");
 * manifest.validateFileLength("_0.doc", docIn.length());
 *
 * // Extract codec-specific metadata
 * int maxImpacts = meta.getMetadataInt("maxImpacts");
 * </pre>
 *
 * @lucene.experimental
 */
package org.apache.lucene.codecs.headerless;
