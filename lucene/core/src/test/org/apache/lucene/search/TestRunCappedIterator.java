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
package org.apache.lucene.search;

import java.io.IOException;
import java.util.Random;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.search.comparators.LongComparator;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.BitSetIterator;
import org.apache.lucene.util.FixedBitSet;

/**
 * T8, K3 bit fill: {@link DenseConjunctionBulkScorer.RunCappedIterator} sets the same bits as the
 * iterator it wraps and ends at the same doc, and caps the run end at one window.
 */
public class TestRunCappedIterator extends LuceneTestCase {

  private static final String FIELD = "f";

  interface IteratorFactory {
    DocIdSetIterator create() throws IOException;
  }

  public void testBitSetInput() throws IOException {
    final Random r = random();
    final int maxDoc = TestUtil.nextInt(r, 1, 50_000);
    final FixedBitSet bits = new FixedBitSet(maxDoc);
    final int mode = r.nextInt(3);
    for (int i = 0; i < maxDoc; i++) {
      if (mode == 0 ? r.nextInt(10) == 0 : mode == 1 ? r.nextInt(10) != 0 : (i / 3000) % 2 == 0) {
        bits.set(i);
      }
    }
    final long cost = bits.cardinality();
    assertSameBits(() -> new BitSetIterator(bits, cost), maxDoc, r);
    assertSameBits(() -> DocIdSetIterator.all(maxDoc), maxDoc, r);
  }

  public void testSkipperRangeAndComparatorInputs() throws IOException {
    final Random r = random();
    final int numDocs = TestUtil.nextInt(r, 5000, 40_000);
    try (Directory dir = newDirectory()) {
      final IndexWriterConfig config =
          new IndexWriterConfig()
              .setCodec(TestUtil.getDefaultCodec())
              .setMergePolicy(NoMergePolicy.INSTANCE)
              .setMaxBufferedDocs(IndexWriterConfig.DISABLE_AUTO_FLUSH)
              .setRAMBufferSizeMB(256);
      final long[] values = new long[numDocs];
      try (IndexWriter w = new IndexWriter(dir, config)) {
        final int runs = TestUtil.nextInt(r, 1, 5);
        for (int i = 0; i < numDocs; i++) {
          final int run = (int) ((long) i * runs / numDocs);
          values[i] = (runs - run) * 100_000L + i % (numDocs / runs + 1) + r.nextInt(3);
          final Document doc = new Document();
          doc.add(new LongPoint(FIELD, values[i]));
          doc.add(NumericDocValuesField.indexedField(FIELD, values[i]));
          w.addDocument(doc);
        }
      }
      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        assertEquals(1, reader.leaves().size());
        final LeafReaderContext ctx = reader.leaves().get(0);
        final int maxDoc = ctx.reader().maxDoc();
        for (int iter = 0; iter < 5; iter++) {
          final long lo = r.nextInt(600_000), hi = lo + r.nextInt(300_000);
          assertSameBits(
              () -> new SkipBlockRangeIterator(ctx.reader().getDocValuesSkipper(FIELD), lo, hi),
              maxDoc,
              r);
          // the comparator's competitive iterator (an UpdateableDocIdSetIterator) right after an
          // update: its new inner iterator is not positioned yet
          final int numHits = TestUtil.nextInt(r, 1, 50);
          final int bottomSlot = r.nextInt(numHits);
          assertSameBits(() -> competitiveIterator(ctx, numHits, bottomSlot), maxDoc, r);
        }
      }
    }
  }

  /** Builds the same competitive iterator each time: the queue holds docs 0..numHits-1. */
  private static DocIdSetIterator competitiveIterator(
      LeafReaderContext ctx, int numHits, int bottomSlot) throws IOException {
    final LongComparator comparator =
        new LongComparator(numHits, FIELD, null, false, Pruning.GREATER_THAN_OR_EQUAL_TO);
    final LeafFieldComparator leaf = comparator.getLeafComparator(ctx);
    leaf.setScorer(new SimpleScorable());
    leaf.setHitsThresholdReached();
    for (int slot = 0; slot < numHits; slot++) {
      leaf.copy(slot, slot);
    }
    leaf.setBottom(bottomSlot);
    final DocIdSetIterator it = leaf.competitiveIterator();
    assertNotNull(it);
    return it;
  }

  private static void assertSameBits(IteratorFactory factory, int maxDoc, Random r)
      throws IOException {
    final DocIdSetIterator expected = factory.create();
    final DocIdSetIterator capped =
        new DenseConjunctionBulkScorer.RunCappedIterator(factory.create());
    final FixedBitSet expectedBits = new FixedBitSet(maxDoc + 1);
    final FixedBitSet actualBits = new FixedBitSet(maxDoc + 1);
    while (true) {
      final int target =
          Math.min(maxDoc, expected.docID() + 1 + r.nextInt(r.nextBoolean() ? 50 : 10_000));
      final int doc = target >= maxDoc ? DocIdSetIterator.NO_MORE_DOCS : expected.advance(target);
      assertEquals(doc, target >= maxDoc ? doc : capped.advance(target));
      if (doc == DocIdSetIterator.NO_MORE_DOCS) {
        break;
      }
      final int runEnd = expected.docIDRunEnd();
      final int cappedEnd = capped.docIDRunEnd();
      assertTrue(cappedEnd > doc);
      assertEquals(Math.min(runEnd, doc + DenseConjunctionBulkScorer.WINDOW_SIZE), cappedEnd);
      assertEquals(doc, capped.docID());
      final int offset = doc - r.nextInt(Math.min(doc, 100) + 1);
      final int upTo = (int) Math.min((long) maxDoc + 1, (long) doc + 1 + r.nextInt(3 * 4096));
      expected.intoBitSet(upTo, expectedBits, offset);
      capped.intoBitSet(upTo, actualBits, offset);
      assertEquals(expectedBits, actualBits);
      assertEquals(expected.docID(), capped.docID());
      expectedBits.clear();
      actualBits.clear();
      if (expected.docID() == DocIdSetIterator.NO_MORE_DOCS) {
        break;
      }
    }
  }
}
