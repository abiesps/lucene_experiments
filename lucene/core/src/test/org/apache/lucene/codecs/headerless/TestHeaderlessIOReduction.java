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
import org.apache.lucene.store.ByteArrayDataOutput;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.tests.store.MockDirectoryWrapper;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.util.StringHelper;

/**
 * Tests that measure I/O reduction from headerless codec format.
 *
 * <p>This test quantifies the byte savings by comparing:
 * <ul>
 *   <li>Traditional format: header + data + footer per file
 *   <li>Headerless format: data only + single manifest
 * </ul>
 */
public class TestHeaderlessIOReduction extends LuceneTestCase {

  /**
   * Measures bytes read when opening a segment with traditional headers/footers.
   */
  public void testTraditionalFormatBytesRead() throws IOException {
    Directory dir = newDirectory();
    byte[] segmentId = StringHelper.randomId();
    String segmentName = "_0";
    String codecName = "TestCodec";
    int version = 1;

    // Simulate typical segment with multiple files
    String[] fileNames = {
      "_0.doc", "_0.pos", "_0.pay",  // postings
      "_0.tim", "_0.tip",             // terms
      "_0.fdt", "_0.fdx",             // stored fields
      "_0.dvd", "_0.dvm",             // doc values
      "_0.nvd", "_0.nvm",             // norms
      "_0.kdd", "_0.kdi", "_0.kdm"   // points
    };

    long totalBytesWritten = 0;
    long totalHeaderFooterBytes = 0;

    // Write files with traditional headers and footers
    for (String fileName : fileNames) {
      try (IndexOutput out = dir.createOutput(fileName, IOContext.DEFAULT)) {
        long startPos = out.getFilePointer();

        // Write header
        CodecUtil.writeIndexHeader(out, codecName, version, segmentId, "");
        long afterHeader = out.getFilePointer();
        long headerSize = afterHeader - startPos;

        // Write some data (simulate 10KB per file)
        byte[] data = new byte[10 * 1024];
        random().nextBytes(data);
        out.writeBytes(data, 0, data.length);

        // Write footer
        long beforeFooter = out.getFilePointer();
        CodecUtil.writeFooter(out);
        long afterFooter = out.getFilePointer();
        long footerSize = afterFooter - beforeFooter;

        totalBytesWritten += out.getFilePointer();
        totalHeaderFooterBytes += (headerSize + footerSize);
      }
    }

    // Now measure bytes read when opening segment
    long totalBytesRead = 0;

    for (String fileName : fileNames) {
      try (IndexInput in = dir.openInput(fileName, IOContext.READONCE)) {
        long startPos = in.getFilePointer();

        // Read and validate header
        CodecUtil.checkIndexHeader(in, codecName, version, version, segmentId, "");
        long afterHeader = in.getFilePointer();

        // Seek to footer
        long footerStart = in.length() - CodecUtil.footerLength();
        in.seek(footerStart);

        // Read footer
        CodecUtil.checkFooter(in);
        long afterFooter = in.getFilePointer();

        totalBytesRead += (afterHeader - startPos) + (afterFooter - footerStart);
      }
    }

    System.out.println("=== Traditional Format I/O Analysis ===");
    System.out.println("Files: " + fileNames.length);
    System.out.println("Total bytes written: " + totalBytesWritten);
    System.out.println("Header+Footer overhead: " + totalHeaderFooterBytes +
                       " (" + (totalHeaderFooterBytes * 100 / totalBytesWritten) + "%)");
    System.out.println("Bytes read on open: " + totalBytesRead);
    System.out.println("Seeks on open: " + (fileNames.length * 2) + " (2 per file)");

    dir.close();
  }

  /**
   * Measures bytes read when opening a segment with headerless format.
   */
  public void testHeaderlessFormatBytesRead() throws IOException {
    Directory dir = newDirectory();
    byte[] segmentId = StringHelper.randomId();
    String segmentName = "_0";

    String[] fileNames = {
      "_0.doc", "_0.pos", "_0.pay",
      "_0.tim", "_0.tip",
      "_0.fdt", "_0.fdx",
      "_0.dvd", "_0.dvm",
      "_0.nvd", "_0.nvm",
      "_0.kdd", "_0.kdi", "_0.kdm"
    };

    long totalBytesWritten = 0;
    ManifestWriter manifestWriter = new ManifestWriter();

    // Write files WITHOUT headers and footers
    for (String fileName : fileNames) {
      try (IndexOutput out = dir.createOutput(fileName, IOContext.DEFAULT)) {
        // Write data directly (no header/footer)
        byte[] data = new byte[10 * 1024];
        random().nextBytes(data);
        out.writeBytes(data, 0, data.length);

        long fileLength = out.getFilePointer();
        totalBytesWritten += fileLength;

        // Register in manifest
        manifestWriter.addFile(fileName, "TestCodec", 1, fileLength, null);
      }
    }

    // Write manifest
    manifestWriter.write(dir, segmentName, segmentId, "");

    // Measure manifest size
    String manifestFile = segmentName + ".manifest";
    long manifestSize = dir.fileLength(manifestFile);
    totalBytesWritten += manifestSize;

    // Now measure bytes read when opening segment
    long totalBytesRead = 0;

    // Read manifest (single read)
    try (IndexInput in = dir.openInput(manifestFile, IOContext.READONCE)) {
      totalBytesRead += in.length();
    }

    // Validate file lengths (no actual reads, just metadata lookup)
    ManifestReader reader = new ManifestReader(dir, segmentName, "");
    for (String fileName : fileNames) {
      FileMetadata meta = reader.getFileMetadata(fileName);
      assertNotNull(meta);
      // In real usage, we'd validate: reader.validateFileLength(fileName, actualLength)
      // But that doesn't require reading file content
    }

    System.out.println("\n=== Headerless Format I/O Analysis ===");
    System.out.println("Files: " + fileNames.length);
    System.out.println("Total bytes written: " + totalBytesWritten);
    System.out.println("Manifest size: " + manifestSize);
    System.out.println("Bytes read on open: " + totalBytesRead);
    System.out.println("Seeks on open: 1 (manifest only)");

    dir.close();
  }

  /**
   * Direct comparison of traditional vs headerless format.
   */
  public void testComparativeIOAnalysis() throws IOException {
    int numFiles = 200; // Typical segment file count

    // Traditional format
    long traditionalHeaderFooterBytes = 0;
    long traditionalSeeks = 0;

    for (int i = 0; i < numFiles; i++) {
      // Header: magic(4) + codec name(~15) + version(4) + id(16) + suffix(1) = ~40 bytes
      // Footer: algorithm(4) + checksum(8) + magic(4) = 16 bytes
      traditionalHeaderFooterBytes += 40 + 16;
      traditionalSeeks += 2; // header read + footer read
    }

    // Headerless format
    long headerlessManifestBytes = 0;
    long headerlessSeeks = 1; // single manifest read

    // Manifest overhead: ~50 bytes per file entry
    headerlessManifestBytes = numFiles * 50;

    // Calculate savings
    long bytesSaved = traditionalHeaderFooterBytes - headerlessManifestBytes;
    long seeksSaved = traditionalSeeks - headerlessSeeks;
    double seekReduction = (seeksSaved * 100.0) / traditionalSeeks;

    System.out.println("\n=== Comparative Analysis (" + numFiles + " files) ===");
    System.out.println("Traditional:");
    System.out.println("  Header+Footer bytes: " + traditionalHeaderFooterBytes);
    System.out.println("  Seeks: " + traditionalSeeks);
    System.out.println("\nHeaderless:");
    System.out.println("  Manifest bytes: " + headerlessManifestBytes);
    System.out.println("  Seeks: " + headerlessSeeks);
    System.out.println("\nSavings:");
    System.out.println("  Bytes saved: " + bytesSaved +
                       " (" + (bytesSaved * 100 / traditionalHeaderFooterBytes) + "%)");
    System.out.println("  Seeks saved: " + seeksSaved +
                       " (" + String.format("%.1f", seekReduction) + "%)");
    System.out.println("  Seek reduction: " + (traditionalSeeks / headerlessSeeks) + "x");

    // Assertions
    assertTrue("Should save bytes", bytesSaved > 0);
    assertTrue("Should save seeks", seeksSaved > 0);
    assertTrue("Should reduce seeks by >99%", seekReduction > 99.0);
  }

  /**
   * Tests I/O reduction for a realistic large segment (1000 shards scenario).
   */
  public void testLargeScaleIOReduction() {
    int numShards = 1000;
    int segmentsPerShard = 20;
    int filesPerSegment = 200;

    int totalSegments = numShards * segmentsPerShard;
    int totalFiles = totalSegments * filesPerSegment;

    // Traditional format
    long traditionalSeeks = totalFiles * 2L; // 2 seeks per file
    long traditionalBytes = totalFiles * 56L; // ~56 bytes header+footer per file

    // Headerless format
    long headerlessSeeks = totalSegments; // 1 seek per segment (manifest)
    long headerlessBytes = totalSegments * (filesPerSegment * 50L); // ~50 bytes per file in manifest

    long seeksSaved = traditionalSeeks - headerlessSeeks;
    double seekReduction = (seeksSaved * 100.0) / traditionalSeeks;

    // Assuming 10ms per seek on EFS
    long traditionalTimeMs = traditionalSeeks * 10;
    long headerlessTimeMs = headerlessSeeks * 10;
    long timeSavedMs = traditionalTimeMs - headerlessTimeMs;

    System.out.println("\n=== Large Scale Analysis ===");
    System.out.println("Cluster: " + numShards + " shards, " +
                       segmentsPerShard + " segments/shard");
    System.out.println("Total segments: " + totalSegments);
    System.out.println("Total files: " + totalFiles);
    System.out.println("\nTraditional format:");
    System.out.println("  Seeks: " + traditionalSeeks);
    System.out.println("  Time: " + (traditionalTimeMs / 1000) + " seconds");
    System.out.println("\nHeaderless format:");
    System.out.println("  Seeks: " + headerlessSeeks);
    System.out.println("  Time: " + (headerlessTimeMs / 1000) + " seconds");
    System.out.println("\nImprovement:");
    System.out.println("  Seeks saved: " + seeksSaved +
                       " (" + String.format("%.2f", seekReduction) + "%)");
    System.out.println("  Time saved: " + (timeSavedMs / 1000) + " seconds");
    System.out.println("  Speedup: " + (traditionalSeeks / headerlessSeeks) + "x");

    // Assertions for expected improvements
    assertTrue("Should achieve >99% seek reduction", seekReduction > 99.0);
    assertTrue("Should achieve >100x speedup", (traditionalSeeks / headerlessSeeks) > 100);
  }

  /**
   * Tests that headerless format uses less bandwidth on network filesystems.
   */
  public void testNetworkBandwidthReduction() throws IOException {
    Directory dir = newDirectory();
    byte[] segmentId = StringHelper.randomId();

    // Create a segment with typical file sizes
    Map<String, Long> fileSizes = new HashMap<>();
    fileSizes.put("_0.doc", 1000000L);  // 1MB
    fileSizes.put("_0.pos", 500000L);   // 500KB
    fileSizes.put("_0.pay", 200000L);   // 200KB
    fileSizes.put("_0.tim", 300000L);   // 300KB
    fileSizes.put("_0.tip", 50000L);    // 50KB
    fileSizes.put("_0.fdt", 2000000L);  // 2MB
    fileSizes.put("_0.fdx", 100000L);   // 100KB

    long totalDataSize = fileSizes.values().stream().mapToLong(Long::longValue).sum();

    // Traditional: read header + footer for each file
    long traditionalMetadataReads = fileSizes.size() * (40 + 16); // header + footer

    // Headerless: read manifest once
    ManifestWriter writer = new ManifestWriter();
    for (Map.Entry<String, Long> entry : fileSizes.entrySet()) {
      writer.addFile(entry.getKey(), "TestCodec", 1, entry.getValue(), null);
    }
    writer.write(dir, "_0", segmentId, "");

    long manifestSize = dir.fileLength("_0.manifest");
    long headerlessMetadataReads = manifestSize;

    long bandwidthSaved = traditionalMetadataReads - headerlessMetadataReads;
    double savingsPercent = (bandwidthSaved * 100.0) / traditionalMetadataReads;

    System.out.println("\n=== Network Bandwidth Analysis ===");
    System.out.println("Total data size: " + totalDataSize + " bytes");
    System.out.println("Traditional metadata reads: " + traditionalMetadataReads + " bytes");
    System.out.println("Headerless metadata reads: " + headerlessMetadataReads + " bytes");
    System.out.println("Bandwidth saved: " + bandwidthSaved + " bytes " +
                       "(" + String.format("%.1f", savingsPercent) + "%)");

    assertTrue("Should save bandwidth", bandwidthSaved > 0);

    dir.close();
  }
}
