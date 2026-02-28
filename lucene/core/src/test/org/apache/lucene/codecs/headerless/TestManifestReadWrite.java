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
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.util.StringHelper;

/**
 * Unit tests for ManifestReader and ManifestWriter.
 */
public class TestManifestReadWrite extends LuceneTestCase {

  public void testBasicReadWrite() throws IOException {
    Directory dir = newDirectory();
    byte[] segmentId = StringHelper.randomId();
    String segmentName = "_0";
    String segmentSuffix = "";

    // Write manifest
    ManifestWriter writer = new ManifestWriter();

    Map<String, Object> metadata1 = new HashMap<>();
    metadata1.put("maxImpacts", 10);
    metadata1.put("blockSize", 128);
    writer.addFile("_0.doc", "TestCodec", 1, 1000L, metadata1);

    Map<String, Object> metadata2 = new HashMap<>();
    metadata2.put("chunkSize", 16384);
    writer.addFile("_0.fdt", "TestCodec", 1, 5000L, metadata2);

    writer.write(dir, segmentName, segmentId, segmentSuffix);

    // Read manifest
    ManifestReader reader = new ManifestReader(dir, segmentName, segmentSuffix);
    SegmentManifest manifest = reader.getManifest();

    assertEquals(SegmentManifest.MANIFEST_VERSION, manifest.getManifestVersion());
    assertArrayEquals(segmentId, manifest.getSegmentId());
    assertEquals(segmentSuffix, manifest.getSegmentSuffix());
    assertEquals(2, manifest.getFileCount());

    // Verify first file
    FileMetadata meta1 = manifest.getFileMetadata("_0.doc");
    assertNotNull(meta1);
    assertEquals("_0.doc", meta1.getFileName());
    assertEquals("TestCodec", meta1.getCodecName());
    assertEquals(1, meta1.getCodecVersion());
    assertEquals(1000L, meta1.getExpectedLength());
    assertEquals(Integer.valueOf(10), meta1.getMetadataInt("maxImpacts"));
    assertEquals(Integer.valueOf(128), meta1.getMetadataInt("blockSize"));

    // Verify second file
    FileMetadata meta2 = manifest.getFileMetadata("_0.fdt");
    assertNotNull(meta2);
    assertEquals("_0.fdt", meta2.getFileName());
    assertEquals(5000L, meta2.getExpectedLength());
    assertEquals(Integer.valueOf(16384), meta2.getMetadataInt("chunkSize"));

    dir.close();
  }

  public void testMetadataTypes() throws IOException {
    Directory dir = newDirectory();
    byte[] segmentId = StringHelper.randomId();

    ManifestWriter writer = new ManifestWriter();

    Map<String, Object> metadata = new HashMap<>();
    metadata.put("intValue", 42);
    metadata.put("longValue", 123456789012345L);
    metadata.put("stringValue", "test string");
    metadata.put("boolValue", true);

    writer.addFile("_0.test", "TestCodec", 1, 100L, metadata);
    writer.write(dir, "_0", segmentId, "");

    ManifestReader reader = new ManifestReader(dir, "_0", "");
    FileMetadata meta = reader.getFileMetadata("_0.test");

    assertEquals(Integer.valueOf(42), meta.getMetadataInt("intValue"));
    assertEquals(Long.valueOf(123456789012345L), meta.getMetadataLong("longValue"));
    assertEquals("test string", meta.getMetadataValue("stringValue"));
    assertEquals(Boolean.TRUE, meta.getMetadataValue("boolValue"));

    dir.close();
  }

  public void testWithChecksum() throws IOException {
    Directory dir = newDirectory();
    byte[] segmentId = StringHelper.randomId();

    ManifestWriter writer = new ManifestWriter();

    long checksum = 0x123456789ABCDEFL;
    writer.addFile("_0.doc", "TestCodec", 1, 1000L, checksum, null);
    writer.write(dir, "_0", segmentId, "");

    ManifestReader reader = new ManifestReader(dir, "_0", "");
    FileMetadata meta = reader.getFileMetadata("_0.doc");

    assertNotNull(meta.getFileChecksum());
    assertEquals(checksum, meta.getFileChecksum().longValue());

    dir.close();
  }

  public void testEmptyManifest() throws IOException {
    Directory dir = newDirectory();
    byte[] segmentId = StringHelper.randomId();

    ManifestWriter writer = new ManifestWriter();
    writer.write(dir, "_0", segmentId, "");

    ManifestReader reader = new ManifestReader(dir, "_0", "");
    assertEquals(0, reader.getManifest().getFileCount());

    dir.close();
  }

  public void testMultipleFiles() throws IOException {
    Directory dir = newDirectory();
    byte[] segmentId = StringHelper.randomId();

    ManifestWriter writer = new ManifestWriter();

    // Add many files
    for (int i = 0; i < 100; i++) {
      Map<String, Object> metadata = new HashMap<>();
      metadata.put("fileIndex", i);
      writer.addFile("_0.f" + i, "TestCodec", 1, i * 1000L, metadata);
    }

    writer.write(dir, "_0", segmentId, "");

    ManifestReader reader = new ManifestReader(dir, "_0", "");
    assertEquals(100, reader.getManifest().getFileCount());

    // Verify all files
    for (int i = 0; i < 100; i++) {
      FileMetadata meta = reader.getFileMetadata("_0.f" + i);
      assertNotNull("File _0.f" + i + " should exist", meta);
      assertEquals(i * 1000L, meta.getExpectedLength());
      assertEquals(Integer.valueOf(i), meta.getMetadataInt("fileIndex"));
    }

    dir.close();
  }

  public void testFileLengthValidation() throws IOException {
    Directory dir = newDirectory();
    byte[] segmentId = StringHelper.randomId();

    ManifestWriter writer = new ManifestWriter();
    writer.addFile("_0.doc", "TestCodec", 1, 1000L, null);
    writer.write(dir, "_0", segmentId, "");

    ManifestReader reader = new ManifestReader(dir, "_0", "");

    // Valid length should pass
    reader.validateFileLength("_0.doc", 1000L);

    // Invalid length should fail
    expectThrows(IOException.class, () -> {
      reader.validateFileLength("_0.doc", 999L);
    });

    // Non-existent file should fail
    expectThrows(IOException.class, () -> {
      reader.validateFileLength("_0.nonexistent", 1000L);
    });

    dir.close();
  }

  public void testSegmentSuffix() throws IOException {
    Directory dir = newDirectory();
    byte[] segmentId = StringHelper.randomId();
    String suffix = "_Lucene104_0";

    ManifestWriter writer = new ManifestWriter();
    writer.addFile("_0.doc", "TestCodec", 1, 1000L, null);
    writer.write(dir, "_0", segmentId, suffix);

    ManifestReader reader = new ManifestReader(dir, "_0", suffix);
    assertEquals(suffix, reader.getManifest().getSegmentSuffix());

    dir.close();
  }

  public void testInvalidSegmentId() throws IOException {
    ManifestWriter writer = new ManifestWriter();
    writer.addFile("_0.doc", "TestCodec", 1, 1000L, null);

    Directory dir = newDirectory();
    byte[] invalidId = new byte[15]; // Wrong length

    expectThrows(IllegalArgumentException.class, () -> {
      writer.write(dir, "_0", invalidId, "");
    });

    dir.close();
  }

  public void testClearManifest() throws IOException {
    ManifestWriter writer = new ManifestWriter();
    writer.addFile("_0.doc", "TestCodec", 1, 1000L, null);
    assertEquals(1, writer.getFileCount());

    writer.clear();
    assertEquals(0, writer.getFileCount());
  }

  public void testManifestFileSize() throws IOException {
    Directory dir = newDirectory();
    byte[] segmentId = StringHelper.randomId();

    ManifestWriter writer = new ManifestWriter();

    // Add typical segment files
    writer.addFile("_0.doc", "Lucene104Postings", 0, 1000000L, null);
    writer.addFile("_0.pos", "Lucene104Postings", 0, 500000L, null);
    writer.addFile("_0.pay", "Lucene104Postings", 0, 200000L, null);

    Map<String, Object> metadata = new HashMap<>();
    metadata.put("chunkSize", 16384);
    metadata.put("numChunks", 100);
    writer.addFile("_0.fdt", "Lucene90StoredFields", 0, 2000000L, metadata);

    writer.write(dir, "_0", segmentId, "");

    // Check manifest file size
    String manifestFile = "_0.manifest";
    long manifestSize = dir.fileLength(manifestFile);

    // Manifest should be small (< 5KB for typical segment)
    assertTrue("Manifest size should be < 5KB, was: " + manifestSize,
               manifestSize < 5 * 1024);

    dir.close();
  }
}
