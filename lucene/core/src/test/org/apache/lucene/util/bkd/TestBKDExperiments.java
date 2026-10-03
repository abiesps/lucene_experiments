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
package org.apache.lucene.util.bkd;

import org.apache.lucene.search.CollectExperiments;
import org.apache.lucene.search.comparators.ComparatorExperiments;
import org.apache.lucene.search.comparators.ComparatorExperiments.SkipperMode;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;

public class TestBKDExperiments extends LuceneTestCase {

  @Override
  public void tearDown() throws Exception {
    reset();
    super.tearDown();
  }

  private static void reset() {
    BKDExperiments.setIntersectPrefetch(false);
    BKDExperiments.setPrefetchChunks(BKDExperiments.DEFAULT_PREFETCH_CHUNKS);
    BKDExperiments.setNodeBytes(BKDExperiments.DEFAULT_NODE_BYTES);
    BKDExperiments.setWholeIndexPrefetch(false);
    BKDExperiments.setWholeIndexPrefetchBytes(BKDExperiments.DEFAULT_WHOLE_INDEX_PREFETCH_BYTES);
    BKDExperiments.setIndexChildPrefetch(false);
    ComparatorExperiments.setSampleDocs(0);
    ComparatorExperiments.setSkipperMode(SkipperMode.OFF);
    CollectExperiments.setCompetitiveRunCap(false);
  }

  public void testDefaults() {
    assertFalse(BKDExperiments.isIntersectPrefetch());
    assertEquals(8, BKDExperiments.getPrefetchChunks());
    assertEquals(131072L, BKDExperiments.getNodeBytes());
    assertFalse(BKDExperiments.isWholeIndexPrefetch());
    assertEquals(65536L, BKDExperiments.getWholeIndexPrefetchBytes());
    assertFalse(BKDExperiments.isIndexChildPrefetch());
    assertEquals(0, ComparatorExperiments.getSampleDocs());
    assertEquals(SkipperMode.OFF, ComparatorExperiments.getSkipperMode());
    assertFalse(CollectExperiments.isCompetitiveRunCap());
  }

  public void testBooleans() {
    BKDExperiments.setIntersectPrefetch(true);
    BKDExperiments.setWholeIndexPrefetch(true);
    BKDExperiments.setIndexChildPrefetch(true);
    CollectExperiments.setCompetitiveRunCap(true);
    assertTrue(BKDExperiments.isIntersectPrefetch());
    assertTrue(BKDExperiments.isWholeIndexPrefetch());
    assertTrue(BKDExperiments.isIndexChildPrefetch());
    assertTrue(CollectExperiments.isCompetitiveRunCap());
  }

  public void testPrefetchChunks() {
    int chunks = TestUtil.nextInt(random(), 1, 64);
    BKDExperiments.setPrefetchChunks(chunks);
    assertEquals(chunks, BKDExperiments.getPrefetchChunks());
    BKDExperiments.setPrefetchChunks(1);
    BKDExperiments.setPrefetchChunks(64);
    for (int bad : new int[] {0, -1, 65, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
      IllegalArgumentException e =
          expectThrows(IllegalArgumentException.class, () -> BKDExperiments.setPrefetchChunks(bad));
      assertTrue(e.getMessage(), e.getMessage().contains(Integer.toString(bad)));
    }
    assertEquals(64, BKDExperiments.getPrefetchChunks());
  }

  public void testNodeBytes() {
    long bytes = 1L << TestUtil.nextInt(random(), 12, 24);
    BKDExperiments.setNodeBytes(bytes);
    assertEquals(bytes, BKDExperiments.getNodeBytes());
    for (long bad : new long[] {0, 2048, 4095, 4097, 131071, 3L << 16, 32L << 20, -4096}) {
      IllegalArgumentException e =
          expectThrows(IllegalArgumentException.class, () -> BKDExperiments.setNodeBytes(bad));
      assertTrue(e.getMessage(), e.getMessage().contains(Long.toString(bad)));
    }
    assertEquals(bytes, BKDExperiments.getNodeBytes());
  }

  public void testWholeIndexPrefetchBytes() {
    long bytes = TestUtil.nextLong(random(), 0, 16L << 20);
    BKDExperiments.setWholeIndexPrefetchBytes(bytes);
    assertEquals(bytes, BKDExperiments.getWholeIndexPrefetchBytes());
    BKDExperiments.setWholeIndexPrefetchBytes(0);
    BKDExperiments.setWholeIndexPrefetchBytes(16L << 20);
    for (long bad : new long[] {-1, (16L << 20) + 1, Long.MAX_VALUE}) {
      IllegalArgumentException e =
          expectThrows(
              IllegalArgumentException.class, () -> BKDExperiments.setWholeIndexPrefetchBytes(bad));
      assertTrue(e.getMessage(), e.getMessage().contains(Long.toString(bad)));
    }
    assertEquals(16L << 20, BKDExperiments.getWholeIndexPrefetchBytes());
  }

  public void testSampleDocs() {
    int docs = TestUtil.nextInt(random(), 4096, 16 << 20);
    ComparatorExperiments.setSampleDocs(docs);
    assertEquals(docs, ComparatorExperiments.getSampleDocs());
    ComparatorExperiments.setSampleDocs(4096);
    ComparatorExperiments.setSampleDocs(16 << 20);
    ComparatorExperiments.setSampleDocs(0);
    for (int bad : new int[] {-1, 1, 4095, (16 << 20) + 1, Integer.MAX_VALUE}) {
      IllegalArgumentException e =
          expectThrows(
              IllegalArgumentException.class, () -> ComparatorExperiments.setSampleDocs(bad));
      assertTrue(e.getMessage(), e.getMessage().contains(Integer.toString(bad)));
    }
    assertEquals(0, ComparatorExperiments.getSampleDocs());
  }

  public void testSkipperMode() {
    for (SkipperMode mode : SkipperMode.values()) {
      ComparatorExperiments.setSkipperMode(mode);
      assertEquals(mode, ComparatorExperiments.getSkipperMode());
    }
    expectThrows(IllegalArgumentException.class, () -> ComparatorExperiments.setSkipperMode(null));
    assertEquals(SkipperMode.FIRST, ComparatorExperiments.getSkipperMode());
  }
}
