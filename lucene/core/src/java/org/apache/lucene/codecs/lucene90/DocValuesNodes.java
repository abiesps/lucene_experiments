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
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.RandomAccessInput;
import org.apache.lucene.util.packed.DirectWriter;

/**
 * Experimental: maps the doc IDs of a dense Lucene90 numeric field (or the ordinals of a dense
 * sorted field) to the file nodes (for example storage or cache blocks of {@code nodeBytes} bytes)
 * that hold their values, and prefetches whole nodes, each at most once.
 *
 * <p>Positions are file offsets of the {@code .dvd} file. For a single packed array the position of
 * doc {@code d} is exact. For the blocked encoding (values in blocks of {@code 2^blockShift} docs,
 * each with its own bits per value) the block start comes from the value jump table at the end of
 * the field, which is prefetched once, and the bits per value of a block are derived from the
 * block's length, so no value block is read to plan.
 */
final class DocValuesNodes {

  /** Bytes a read of one value may touch past its first byte (8-byte loads of packed values). */
  private static final int READ_SPAN = Long.BYTES;

  private static final int BLOCK_HEADER = 1 + Long.BYTES + Integer.BYTES; // bpv, delta, length
  private static final int BLOCK_HEADER_ZERO_BPV = 1 + Long.BYTES; // bpv = 0, delta

  private final RandomAccessInput slice; // the values, starting at file offset valuesOffset
  private final long valuesOffset;
  private final long valuesLength;
  private final int maxDoc;
  private final int bitsPerValue; // single packed array; unused when blocked
  // blocked encoding
  private final RandomAccessInput jumpTable; // block start offsets (absolute), numBlocks + 1 longs
  private final int blockShift;
  private final long numValues;
  private final long numBlocks;
  private boolean jumpTablePrefetched;

  private long nodeBytes;
  private final Lucene90NormsProducer.FixedBitSetHolder requested =
      new Lucene90NormsProducer.FixedBitSetHolder();

  private DocValuesNodes(
      RandomAccessInput slice,
      long valuesOffset,
      long valuesLength,
      int maxDoc,
      int bitsPerValue,
      RandomAccessInput jumpTable,
      int blockShift,
      long numValues) {
    this.slice = slice;
    this.valuesOffset = valuesOffset;
    this.valuesLength = valuesLength;
    this.maxDoc = maxDoc;
    this.bitsPerValue = bitsPerValue;
    this.jumpTable = jumpTable;
    this.blockShift = blockShift;
    this.numValues = numValues;
    this.numBlocks = jumpTable == null ? 0 : (numValues + (1L << blockShift) - 1) >>> blockShift;
  }

  /** One packed array of {@code bitsPerValue} bits per doc, doc {@code d} at index {@code d}. */
  static DocValuesNodes packed(
      RandomAccessInput slice, long valuesOffset, long valuesLength, int maxDoc, int bitsPerValue) {
    if (bitsPerValue <= 0) {
      throw new IllegalArgumentException("bitsPerValue must be > 0, got " + bitsPerValue);
    }
    return new DocValuesNodes(slice, valuesOffset, valuesLength, maxDoc, bitsPerValue, null, -1, 0);
  }

  /**
   * Blocks of {@code 2^blockShift} values with their own bits per value; {@code jumpTable} holds
   * the absolute start offset of every block and the end offset of the last one.
   */
  static DocValuesNodes blocked(
      RandomAccessInput slice,
      long valuesOffset,
      long valuesLength,
      int maxDoc,
      RandomAccessInput jumpTable,
      int blockShift,
      long numValues) {
    return new DocValuesNodes(
        slice, valuesOffset, valuesLength, maxDoc, -1, jumpTable, blockShift, numValues);
  }

  private void prefetchJumpTable() throws IOException {
    if (jumpTable != null && jumpTablePrefetched == false) {
      jumpTablePrefetched = true;
      jumpTable.prefetch(0, (numBlocks + 1) * Long.BYTES);
    }
  }

  private long blockStart(long block) throws IOException {
    return jumpTable.readLong(block * Long.BYTES);
  }

  /** Bits per value of a block of {@code n} values whose packed data is {@code payload} bytes. */
  private static int blockBitsPerValue(long n, long payload) {
    for (int bpv : new int[] {1, 2, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64}) {
      if (DirectWriter.bytesRequired(n, bpv) == payload) {
        return bpv;
      }
    }
    throw new IllegalStateException(
        "no bits per value for " + n + " values in " + payload + " bytes");
  }

  private long blockValues(long block) {
    return Math.min(1L << blockShift, numValues - (block << blockShift));
  }

  /** File offset of the first byte of doc {@code doc}'s value. */
  long position(int doc) throws IOException {
    if (jumpTable == null) {
      return valuesOffset + (((long) doc * bitsPerValue) >>> 3);
    }
    final long block = doc >>> blockShift;
    final long start = blockStart(block);
    final long length = blockStart(block + 1) - start;
    if (length <= BLOCK_HEADER_ZERO_BPV) {
      return start; // bits per value 0: the value is the block's delta
    }
    final int bpv = blockBitsPerValue(blockValues(block), length - BLOCK_HEADER);
    return start + BLOCK_HEADER + (((doc - (block << blockShift)) * (long) bpv) >>> 3);
  }

  /** First doc whose value starts at or after file offset {@code position}, or NO_MORE_DOCS. */
  int firstDocAtOrAfter(long position) throws IOException {
    long doc;
    if (jumpTable == null) {
      final long rel = position - valuesOffset;
      doc = rel <= 0 ? 0 : (rel * Byte.SIZE + bitsPerValue - 1) / bitsPerValue;
    } else {
      // last block that starts at or before position
      long lo = 0, hi = numBlocks - 1;
      while (lo < hi) {
        final long mid = (lo + hi + 1) >>> 1;
        if (blockStart(mid) <= position) {
          lo = mid;
        } else {
          hi = mid - 1;
        }
      }
      final long block = lo;
      final long start = blockStart(block);
      final long length = blockStart(block + 1) - start;
      final long first = block << blockShift;
      if (position <= start) {
        doc = first;
      } else if (length <= BLOCK_HEADER_ZERO_BPV) {
        doc = first + blockValues(block); // all values of the block sit at its start
      } else {
        final long rel = position - start - BLOCK_HEADER;
        if (rel <= 0) {
          doc = first;
        } else {
          final long n = blockValues(block);
          final int bpv = blockBitsPerValue(n, length - BLOCK_HEADER);
          final long k = (rel * Byte.SIZE + bpv - 1) / bpv;
          doc = k >= n ? first + n : first + k;
        }
      }
    }
    return doc >= maxDoc ? DocIdSetIterator.NO_MORE_DOCS : (int) doc;
  }

  /**
   * First doc whose value starts in a node after the node holding the start of {@code doc}'s value,
   * or NO_MORE_DOCS.
   */
  int nextNodeDoc(int doc, long nodeBytes) throws IOException {
    prefetchJumpTable();
    final long node = position(doc) / nodeBytes;
    return firstDocAtOrAfter((node + 1) * nodeBytes);
  }

  /** Requests, in whole nodes, the bytes read for the values of docs {@code [fromDoc, toDoc)}. */
  void prefetch(int fromDoc, int toDoc, long nodeBytes) throws IOException {
    if (toDoc <= fromDoc || nodeBytes <= 0 || valuesLength == 0) {
      return;
    }
    prefetchJumpTable();
    if (nodeBytes != this.nodeBytes) {
      this.nodeBytes = nodeBytes;
      requested.reset();
    }
    final long end = valuesOffset + valuesLength;
    final long startByte = position(fromDoc);
    final long endByte = Math.min(end, position(toDoc - 1) + READ_SPAN); // exclusive
    final long base = valuesOffset / nodeBytes;
    final long n0 = startByte / nodeBytes;
    final long n1 = (endByte - 1) / nodeBytes;
    long runStart = -1;
    for (long n = n0; n <= n1 + 1; n++) {
      final boolean want = n <= n1 && requested.getAndSet(n - base) == false;
      if (want && runStart < 0) {
        runStart = n;
      } else if (want == false && runStart >= 0) {
        final long s = Math.max(runStart * nodeBytes, valuesOffset) - valuesOffset;
        final long e = Math.min(n * nodeBytes, end) - valuesOffset;
        if (e > s) {
          slice.prefetch(s, e - s);
        }
        runStart = -1;
      }
    }
  }
}
