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
package org.apache.lucene.codecs.lucene90;

import java.io.IOException;
import java.util.Optional;
import org.apache.lucene.store.RandomAccessInput;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.FixedBitSet;

/**
 * {@link DocValuesNodes#isLoaded} asks the input about exactly the nodes {@link
 * DocValuesNodes#prefetch} requests: a doc's nodes are loaded once prefetched, and not before.
 */
public class TestDocValuesNodesLoaded extends LuceneTestCase {

  /** An input whose bytes are "loaded" once a prefetch covered them; reads return zeros. */
  private static final class LoadingInput implements RandomAccessInput {
    final long length;
    final FixedBitSet loaded;

    LoadingInput(long length) {
      this.length = length;
      this.loaded = new FixedBitSet(Math.toIntExact(length));
    }

    @Override
    public long length() {
      return length;
    }

    @Override
    public void prefetch(long offset, long len) {
      loaded.set(Math.toIntExact(offset), Math.toIntExact(offset + len));
    }

    @Override
    public Optional<Boolean> isLoaded(long offset, long len) {
      final int from = Math.toIntExact(offset);
      final int to = Math.toIntExact(offset + len);
      return Optional.of(loaded.nextClearBit(from) >= to);
    }

    @Override
    public byte readByte(long pos) {
      return 0;
    }

    @Override
    public short readShort(long pos) {
      return 0;
    }

    @Override
    public int readInt(long pos) {
      return 0;
    }

    @Override
    public long readLong(long pos) {
      return 0;
    }
  }

  /** A jump table of absolute block starts (the end of the last block last). */
  private static final class JumpTable implements RandomAccessInput {
    final long[] starts;

    JumpTable(long[] starts) {
      this.starts = starts;
    }

    @Override
    public long length() {
      return (long) starts.length * Long.BYTES;
    }

    @Override
    public byte readByte(long pos) {
      throw new UnsupportedOperationException();
    }

    @Override
    public short readShort(long pos) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int readInt(long pos) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long readLong(long pos) {
      return starts[Math.toIntExact(pos / Long.BYTES)];
    }
  }

  public void testPacked() throws IOException {
    for (int iter = 0; iter < 20; iter++) {
      int maxDoc = TestUtil.nextInt(random(), 1, 200_000);
      int bpv = TestUtil.nextInt(random(), 1, 64);
      long valuesOffset = TestUtil.nextInt(random(), 0, 5000);
      long valuesLength = ((long) maxDoc * bpv + 7) / 8;
      LoadingInput input = new LoadingInput(valuesOffset + valuesLength + 64);
      DocValuesNodes nodes = DocValuesNodes.packed(input, valuesOffset, valuesLength, maxDoc, bpv);
      check(nodes, input, maxDoc);
    }
  }

  public void testBlocked() throws IOException {
    for (int iter = 0; iter < 20; iter++) {
      int blockShift = TestUtil.nextInt(random(), 4, 10);
      int numBlocks = TestUtil.nextInt(random(), 1, 300);
      long numValues =
          ((long) numBlocks - 1) * (1L << blockShift)
              + TestUtil.nextInt(random(), 1, 1 << blockShift);
      int maxDoc = Math.toIntExact(numValues);
      long valuesOffset = TestUtil.nextInt(random(), 0, 5000);
      long[] starts = new long[numBlocks + 1];
      starts[0] = valuesOffset;
      for (int b = 0; b < numBlocks; b++) {
        int values =
            Math.toIntExact(Math.min(1L << blockShift, numValues - ((long) b << blockShift)));
        int[] bpvs = {1, 2, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64};
        int bpv = bpvs[random().nextInt(bpvs.length)];
        // a block as Lucene90 writes it: a header, then its values packed by DirectWriter
        starts[b + 1] =
            starts[b]
                + DocValuesNodes.BLOCK_HEADER
                + org.apache.lucene.util.packed.DirectWriter.bytesRequired(values, bpv);
      }
      long valuesLength = starts[numBlocks] - valuesOffset;
      LoadingInput input = new LoadingInput(starts[numBlocks] + 64);
      DocValuesNodes nodes =
          DocValuesNodes.blocked(
              input,
              valuesOffset,
              valuesLength,
              maxDoc,
              new JumpTable(starts),
              blockShift,
              numValues);
      check(nodes, input, maxDoc);
    }
  }

  private void check(DocValuesNodes nodes, LoadingInput input, int maxDoc) throws IOException {
    long nodeBytes = 1L << TestUtil.nextInt(random(), 6, 12);
    for (int k = 0; k < 30; k++) {
      int doc = random().nextInt(maxDoc);
      boolean before = nodes.isLoaded(doc, doc + 1, nodeBytes);
      nodes.prefetch(doc, doc + 1, nodeBytes);
      assertTrue("loaded after prefetch, doc=" + doc, nodes.isLoaded(doc, doc + 1, nodeBytes));
      if (k == 0) {
        assertFalse("nothing loaded before the first prefetch, doc=" + doc, before);
      }
    }
    // every doc whose nodes were all prefetched reads as loaded, and a doc reads as loaded only
    // then
    input.loaded.clear();
    int doc = random().nextInt(maxDoc);
    assertFalse(nodes.isLoaded(doc, doc + 1, nodeBytes));
  }
}
