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
package org.apache.lucene.codecs.lucene104;

import java.io.IOException;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.index.TieredMergePolicy;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.analysis.MockAnalyzer;
import org.apache.lucene.tests.index.BasePostingsFormatTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.BytesRef;

public class TestLucene104NavPostingsFormat extends BasePostingsFormatTestCase {

  @Override
  protected Codec getCodec() {
    return TestUtil.alwaysPostingsFormat(new Lucene104NavPostingsFormat());
  }

  /** Codec that writes field "base" with Lucene104 and field "nav" with Lucene104Nav. */
  private static Codec perFieldCodec() {
    final PostingsFormat base = new Lucene104PostingsFormat();
    final PostingsFormat nav = new Lucene104NavPostingsFormat();
    return new Lucene104Codec() {
      @Override
      public PostingsFormat getPostingsFormatForField(String field) {
        return field.startsWith("nav") ? nav : base;
      }
    };
  }

  /**
   * Indexes the same postings into a Lucene104 field and a Lucene104Nav field, then checks that
   * nextDoc, advance and freq return the same values, for docs-only and freq-indexed fields.
   */
  public void testSameResultsAsLucene104() throws IOException {
    try (Directory dir = newDirectory()) {
      IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
      iwc.setCodec(perFieldCodec());
      // keep .doc and .nav as separate files so their sizes can be compared
      TieredMergePolicy mp = new TieredMergePolicy();
      mp.setNoCFSRatio(0.0);
      iwc.setMergePolicy(mp);
      iwc.setUseCompoundFile(false);
      final int numDocs = atLeast(40_000);
      try (IndexWriter w = new IndexWriter(dir, iwc)) {
        for (int i = 0; i < numDocs; i++) {
          Document doc = new Document();
          for (String term : termsFor(i)) {
            doc.add(new StringField("base_kw", term, Field.Store.NO));
            doc.add(new StringField("nav_kw", term, Field.Store.NO));
          }
          String text = String.join(" ", termsFor(i)) + " " + String.join(" ", termsFor(i / 3));
          doc.add(new TextField("base_txt", text, Field.Store.NO));
          doc.add(new TextField("nav_txt", text, Field.Store.NO));
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }
      try (DirectoryReader r = DirectoryReader.open(dir)) {
        LeafReader leaf = getOnlyLeafReader(r);
        for (String term : new String[] {"all", "half", "tenth", "rare", "single"}) {
          for (String suffix : new String[] {"kw", "txt"}) {
            assertSamePostings(leaf, "base_" + suffix, "nav_" + suffix, term);
          }
        }
      }

      // The skip data moved out of .doc: the nav .doc plus .nav hold the same bytes as the
      // baseline .doc, give or take the file headers and the level-1 entries' .doc lengths.
      long baseDoc = 0, navDoc = 0, nav = 0;
      for (String file : dir.listAll()) {
        if (file.contains(Lucene104NavPostingsFormat.NAME) && file.endsWith(".doc")) {
          navDoc = dir.fileLength(file);
        } else if (file.contains(Lucene104NavPostingsFormat.NAME) && file.endsWith(".nav")) {
          nav = dir.fileLength(file);
        } else if (file.endsWith(".doc")) {
          baseDoc = dir.fileLength(file);
        }
      }
      assertTrue("nav file missing", nav > 0);
      assertTrue(navDoc + " should be smaller than " + baseDoc, navDoc < baseDoc);
      assertEquals((double) baseDoc, navDoc + nav, baseDoc * 0.05);
    }
  }

  /** Many terms whose doc counts range from 1 to tens of thousands, in the same field. */
  public void testManyTermsOfVaryingDocFreq() throws IOException {
    try (Directory dir = newDirectory()) {
      IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
      iwc.setCodec(perFieldCodec());
      final int numDocs = atLeast(30_000);
      final int numTerms = 300;
      final double[] prob = new double[numTerms];
      for (int t = 0; t < numTerms; t++) {
        prob[t] = Math.pow(10, -5 * random().nextDouble()); // 1 down to 1e-5
      }
      try (IndexWriter w = new IndexWriter(dir, iwc)) {
        for (int i = 0; i < numDocs; i++) {
          Document doc = new Document();
          for (int t = 0; t < numTerms; t++) {
            if (random().nextDouble() < prob[t]) {
              doc.add(new StringField("base_kw", "t" + t, Field.Store.NO));
              doc.add(new StringField("nav_kw", "t" + t, Field.Store.NO));
            }
          }
          w.addDocument(doc);
        }
        w.forceMerge(1);
      }
      try (DirectoryReader r = DirectoryReader.open(dir)) {
        LeafReader leaf = getOnlyLeafReader(r);
        for (int t = 0; t < numTerms; t++) {
          assertSamePostings(leaf, "base_kw", "nav_kw", "t" + t);
        }
      }
    }
  }

  private static String[] termsFor(int i) {
    StringBuilder sb = new StringBuilder("all");
    if (i % 2 == 0) sb.append(" half");
    if (i % 10 == 3) sb.append(" tenth");
    if (i % 997 == 5) sb.append(" rare");
    if (i == 12345) sb.append(" single");
    return sb.toString().split(" ");
  }

  private void assertSamePostings(LeafReader leaf, String baseField, String navField, String term)
      throws IOException {
    final int maxDoc = leaf.maxDoc();
    for (int iter = 0; iter < 20; iter++) {
      final int flags = random().nextBoolean() ? PostingsEnum.NONE : PostingsEnum.FREQS;
      PostingsEnum expected = postings(leaf, baseField, term, flags);
      PostingsEnum actual = postings(leaf, navField, term, flags);
      if (expected == null) {
        assertNull(actual);
        return;
      }
      int doc = -1;
      while (doc != DocIdSetIterator.NO_MORE_DOCS) {
        final int e, a;
        if (random().nextInt(4) == 0) {
          e = expected.nextDoc();
          a = actual.nextDoc();
        } else {
          // jumps of random length, from inside the current block to many level-1 groups ahead
          final int target = doc + 1 + random().nextInt(1 + random().nextInt(maxDoc / 2 + 1));
          if (target >= maxDoc) {
            break;
          }
          e = expected.advance(target);
          a = actual.advance(target);
        }
        assertEquals(baseField + ":" + term, e, a);
        if (e != DocIdSetIterator.NO_MORE_DOCS && flags == PostingsEnum.FREQS) {
          assertEquals(expected.freq(), actual.freq());
        }
        doc = e;
      }
    }
  }

  private static PostingsEnum postings(LeafReader leaf, String field, String term, int flags)
      throws IOException {
    TermsEnum te = leaf.terms(field).iterator();
    if (te.seekExact(new BytesRef(term)) == false) {
      return null;
    }
    assertEquals(leaf.docFreq(new Term(field, term)), te.docFreq());
    return te.postings(null, flags);
  }
}
