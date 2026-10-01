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
import java.util.Arrays;
import org.apache.lucene.internal.vectorization.DocValuesBulkDecodeSupport;
import org.apache.lucene.store.RandomAccessInput;
import org.apache.lucene.util.BitUtil;

/**
 * Decodes a span of consecutive values of a {@link org.apache.lucene.util.packed.DirectWriter}
 * packed array (little-endian, {@code bitsPerValue} bits per value, no gaps) with one read of the
 * span's bytes, instead of one {@link RandomAccessInput} read per value.
 */
final class PackedSpans {

  /**
   * A span is decoded in one pass when it holds at most this many values per requested value.
   * Decoding a value from a byte array costs a few cycles; a {@link RandomAccessInput} read per
   * value costs several times more.
   */
  static final int MAX_SPAN_PER_DOC = 8;

  private static final DocValuesBulkDecodeSupport BYTE_ALIGNED =
      Lucene90DocValuesProducer.DOC_VALUES_BULK_DECODE_SUPPORT;

  private PackedSpans() {}

  /**
   * Decodes the values at indexes {@code [first, first + count)} of the packed array that starts at
   * {@code offset} in {@code slice} into {@code out[0, count)}.
   *
   * @param buffer scratch bytes, reused when large enough
   * @return the scratch bytes to pass to the next call
   */
  static byte[] decode(
      RandomAccessInput slice,
      long offset,
      int bitsPerValue,
      long first,
      int count,
      long[] out,
      byte[] buffer)
      throws IOException {
    final long firstBit = first * bitsPerValue;
    final long startByte = offset + (firstBit >>> 3);
    final long endBit = (first + count) * bitsPerValue;
    final int spanBytes = Math.toIntExact(offset + ((endBit + 7) >>> 3) - startByte);
    // 8 bytes past the span, so each value can be read with one 8-byte load; DirectWriter pads
    // less, so past the end of the slice the bytes are zeros
    final int needed = spanBytes + Long.BYTES;
    if (buffer.length < needed) {
      buffer = new byte[needed];
    }
    final int available = (int) Math.min(needed, slice.length() - startByte);
    slice.readBytes(startByte, buffer, 0, available);
    Arrays.fill(buffer, available, needed, (byte) 0);

    if ((bitsPerValue & 7) == 0) {
      BYTE_ALIGNED.decodeByteAligned(buffer, 0, bitsPerValue, out, 0, count);
      return buffer;
    }
    final long mask = (1L << bitsPerValue) - 1;
    long bit = firstBit & 7;
    switch (bitsPerValue) {
      case 4 -> {
        for (int i = 0; i < count; i++, bit += 4) {
          out[i] = (buffer[(int) (bit >>> 3)] >>> (bit & 7)) & 0xF;
        }
      }
      case 2 -> {
        for (int i = 0; i < count; i++, bit += 2) {
          out[i] = (buffer[(int) (bit >>> 3)] >>> (bit & 7)) & 0x3;
        }
      }
      case 1 -> {
        for (int i = 0; i < count; i++, bit++) {
          out[i] = (buffer[(int) (bit >>> 3)] >>> (bit & 7)) & 0x1;
        }
      }
      default -> {
        // 12, 20, 28 (and any width up to 57): one unaligned little-endian 8-byte load per value
        for (int i = 0; i < count; i++, bit += bitsPerValue) {
          final long word = (long) BitUtil.VH_LE_LONG.get(buffer, (int) (bit >>> 3));
          out[i] = (word >>> (bit & 7)) & mask;
        }
      }
    }
    return buffer;
  }
}
