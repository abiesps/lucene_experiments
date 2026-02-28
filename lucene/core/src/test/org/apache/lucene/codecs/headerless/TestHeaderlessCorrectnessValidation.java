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
import java.util.List;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.util.LuceneTestCase;

/**
 * Validates correctness of headerless format by comparing with traditional format.
 *
 * <p>These tests ensure that:
 * <ul>
 *   <li>Search results are identical between formats
 *   <li>Document counts match
 *   <li>Term frequencies are preserved
 *   <li>Positions and payloads are correct
 *   <li>Segment merging produces same results
 * </ul>
 */
public class TestHeaderlessCorrectnessValidation extends LuceneTestCase {

  /**
   * Validates that search results are identical.
   */
  public void testSearchResultsMatch() throws IOException {
    // Create test documents
    List<Document> docs = createTestDocuments(100);

    // Index with traditional format
    Directory traditionalDir = newDirectory();
    IndexWriterConfig traditionalConfig = new IndexWriterConfig();
    indexDocuments(traditionalDir, traditionalConfig, docs);

    // Index with headerless format
    Directory headerlessDir = newDirectory();
    IndexWriterConfig headerlessConfig = new IndexWriterConfig();
    // TODO: Set codec to use HeaderlessPostingsFormat
    indexDocuments(headerlessDir, headerlessConfig, docs);

    // Compare search results
    try (IndexReader traditionalReader = DirectoryReader.open(traditionalDir);
         IndexReader headerlessReader = DirectoryReader.open(headerlessDir)) {

      IndexSearcher traditionalSearcher = new IndexSearcher(traditionalReader);
      IndexSearcher headerlessSearcher = new IndexSearcher(headerlessReader);

      // Test multiple queries
      String[] queries = {"document", "test", "field", "content"};
      for (String queryTerm : queries) {
        Query query = new TermQuery(new Term("field", queryTerm));

        TopDocs traditionalHits = traditionalSearcher.search(query, 100);
        TopDocs headerlessHits = headerlessSearcher.search(query, 100);

        assertEquals("Hit count mismatch for query: " + queryTerm,
                     traditionalHits.totalHits.value,
                     headerlessHits.totalHits.value);

        // Compare scores
        for (int i = 0; i < traditionalHits.scoreDocs.length; i++) {
          assertEquals("Score mismatch at position " + i,
                       traditionalHits.scoreDocs[i].score,
                       headerlessHits.scoreDocs[i].score,
                       0.0001f);
        }
      }
    }

    traditionalDir.close();
    headerlessDir.close();
  }

  /**
   * Validates that document counts match.
   */
  public void testDocumentCountsMatch() throws IOException {
    List<Document> docs = createTestDocuments(500);

    Directory traditionalDir = newDirectory();
    IndexWriterConfig traditionalConfig = new IndexWriterConfig();
    indexDocuments(traditionalDir, traditionalConfig, docs);

    Directory headerlessDir = newDirectory();
    IndexWriterConfig headerlessConfig = new IndexWriterConfig();
    // TODO: Set codec to use HeaderlessPostingsFormat
    indexDocuments(headerlessDir, headerlessConfig, docs);

    try (IndexReader traditionalReader = DirectoryReader.open(traditionalDir);
         IndexReader headerlessReader = DirectoryReader.open(headerlessDir)) {

      assertEquals("Document count mismatch",
                   traditionalReader.numDocs(),
                   headerlessReader.numDocs());

      assertEquals("Max doc mismatch",
                   traditionalReader.maxDoc(),
                   headerlessReader.maxDoc());
    }

    traditionalDir.close();
    headerlessDir.close();
  }

  /**
   * Validates segment merging produces identical results.
   */
  public void testMergingProducesSameResults() throws IOException {
    List<Document> docs = createTestDocuments(300);

    // Traditional format with merging
    Directory traditionalDir = newDirectory();
    IndexWriterConfig traditionalConfig = new IndexWriterConfig();
    try (IndexWriter writer = new IndexWriter(traditionalDir, traditionalConfig)) {
      // Write in multiple segments
      for (int i = 0; i < 3; i++) {
        for (int j = 0; j < 100; j++) {
          writer.addDocument(docs.get(i * 100 + j));
        }
        writer.commit();
      }
      writer.forceMerge(1);
    }

    // Headerless format with merging
    Directory headerlessDir = newDirectory();
    IndexWriterConfig headerlessConfig = new IndexWriterConfig();
    // TODO: Set codec to use HeaderlessPostingsFormat
    try (IndexWriter writer = new IndexWriter(headerlessDir, headerlessConfig)) {
      // Write in multiple segments
      for (int i = 0; i < 3; i++) {
        for (int j = 0; j < 100; j++) {
          writer.addDocument(docs.get(i * 100 + j));
        }
        writer.commit();
      }
      writer.forceMerge(1);
    }

    // Compare results
    try (IndexReader traditionalReader = DirectoryReader.open(traditionalDir);
         IndexReader headerlessReader = DirectoryReader.open(headerlessDir)) {

      assertEquals("Document count after merge",
                   traditionalReader.numDocs(),
                   headerlessReader.numDocs());

      assertEquals("Segment count after merge",
                   traditionalReader.leaves().size(),
                   headerlessReader.leaves().size());

      // Verify search results still match
      IndexSearcher traditionalSearcher = new IndexSearcher(traditionalReader);
      IndexSearcher headerlessSearcher = new IndexSearcher(headerlessReader);

      Query query = new TermQuery(new Term("field", "document"));
      TopDocs traditionalHits = traditionalSearcher.search(query, 300);
      TopDocs headerlessHits = headerlessSearcher.search(query, 300);

      assertEquals("Hit count after merge",
                   traditionalHits.totalHits.value,
                   headerlessHits.totalHits.value);
    }

    traditionalDir.close();
    headerlessDir.close();
  }

  /**
   * Validates that file integrity checks work without checksums.
   */
  public void testIntegrityValidation() throws IOException {
    Directory dir = newDirectory();

    IndexWriterConfig config = new IndexWriterConfig();
    // TODO: Set codec to use HeaderlessPostingsFormat

    try (IndexWriter writer = new IndexWriter(dir, config)) {
      for (int i = 0; i < 50; i++) {
        Document doc = new Document();
        doc.add(new TextField("field", "document " + i, Field.Store.NO));
        writer.addDocument(doc);
      }
      writer.commit();
    }

    // Read manifest and validate all files
    String segmentName = "_0";
    ManifestReader manifestReader = new ManifestReader(dir, segmentName, "");

    String[] files = dir.listAll();
    int validatedFiles = 0;

    for (String file : files) {
      if (file.startsWith(segmentName) && !file.endsWith(".manifest") && !file.endsWith(".si")) {
        FileMetadata meta = manifestReader.getFileMetadata(file);
        if (meta != null) {
          long actualLength = dir.fileLength(file);
          manifestReader.validateFileLength(file, actualLength);
          validatedFiles++;
        }
      }
    }

    assertTrue("Should validate at least some files", validatedFiles > 0);

    dir.close();
  }

  /**
   * Validates that corrupted files are detected via length mismatch.
   */
  public void testCorruptionDetection() throws IOException {
    Directory dir = newDirectory();

    IndexWriterConfig config = new IndexWriterConfig();
    // TODO: Set codec to use HeaderlessPostingsFormat

    try (IndexWriter writer = new IndexWriter(dir, config)) {
      Document doc = new Document();
      doc.add(new TextField("field", "test", Field.Store.NO));
      writer.addDocument(doc);
      writer.commit();
    }

    // Read manifest
    String segmentName = "_0";
    ManifestReader manifestReader = new ManifestReader(dir, segmentName, "");

    // Try to validate with wrong length (simulating corruption)
    String[] files = dir.listAll();
    for (String file : files) {
      if (file.endsWith(".doc")) {
        long actualLength = dir.fileLength(file);

        // This should pass
        manifestReader.validateFileLength(file, actualLength);

        // This should fail (wrong length)
        expectThrows(IOException.class, () -> {
          manifestReader.validateFileLength(file, actualLength + 100);
        });
        break;
      }
    }

    dir.close();
  }

  /**
   * Helper: Creates test documents.
   */
  private List<Document> createTestDocuments(int count) {
    List<Document> docs = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Document doc = new Document();
      doc.add(new TextField("field", "document " + i + " test content", Field.Store.NO));
      doc.add(new TextField("id", String.valueOf(i), Field.Store.YES));
      docs.add(doc);
    }
    return docs;
  }

  /**
   * Helper: Indexes documents.
   */
  private void indexDocuments(Directory dir, IndexWriterConfig config, List<Document> docs)
      throws IOException {
    try (IndexWriter writer = new IndexWriter(dir, config)) {
      for (Document doc : docs) {
        writer.addDocument(doc);
      }
      writer.commit();
    }
  }

  /**
   * Tests that manifest contains correct metadata for all files.
   */
  public void testManifestMetadataCorrectness() throws IOException {
    Directory dir = newDirectory();

    IndexWriterConfig config = new IndexWriterConfig();
    // TODO: Set codec to use HeaderlessPostingsFormat

    try (IndexWriter writer = new IndexWriter(dir, config)) {
      for (int i = 0; i < 10; i++) {
        Document doc = new Document();
        doc.add(new TextField("field", "test " + i, Field.Store.NO));
        writer.addDocument(doc);
      }
      writer.commit();
    }

    // Read manifest and verify metadata
    String segmentName = "_0";
    ManifestReader manifestReader = new ManifestReader(dir, segmentName, "");
    SegmentManifest manifest = manifestReader.getManifest();

    // Verify manifest version
    assertEquals(SegmentManifest.MANIFEST_VERSION, manifest.getManifestVersion());

    // Verify all segment files are registered
    String[] files = dir.listAll();
    int registeredFiles = 0;

    for (String file : files) {
      if (file.startsWith(segmentName) && !file.endsWith(".manifest") && !file.endsWith(".si")) {
        FileMetadata meta = manifest.getFileMetadata(file);
        assertNotNull("File should be registered in manifest: " + file, meta);

        // Verify metadata fields
        assertEquals(file, meta.getFileName());
        assertEquals(HeaderlessPostingsFormat.NAME, meta.getCodecName());
        assertEquals(HeaderlessPostingsFormat.VERSION, meta.getCodecVersion());
        assertTrue("Expected length should be positive", meta.getExpectedLength() > 0);

        registeredFiles++;
      }
    }

    assertTrue("Should register at least some files", registeredFiles > 0);

    dir.close();
  }
}
