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
import org.apache.lucene.store.RandomAccessInput;
import org.apache.lucene.util.BitUtil;

/**
 * Bulk reads of a {@link org.apache.lucene.util.packed.DirectWriter} packed array (little-endian,
 * {@code bitsPerValue} bits per value, no gaps): the bytes between the first and the last requested
 * value are read with one {@link RandomAccessInput#readBytes(long, byte[], int, int)} call, and
 * each requested value is decoded from that byte array, instead of one {@link RandomAccessInput}
 * read per value.
 */
final class PackedSpans {

  /**
   * Bulk reads are used when the requested values span at most this many values per requested
   * value. Reading the span costs about {@code bitsPerValue / 8} bytes of copy per value of the
   * span; decoding a requested value from the copy costs about a nanosecond, several times less
   * than a {@link RandomAccessInput} read.
   */
  static final int MAX_SPAN_PER_DOC = 64;

  private PackedSpans() {}

  /**
   * Reads the values at indexes {@code docs[docsOffset + i] - docBase}, for {@code i < count}, of
   * the packed array that starts at {@code offset} in {@code slice}, into {@code out[outOffset +
   * i]}. Doc IDs are sorted.
   *
   * @param buffer scratch bytes, reused when large enough
   * @return the scratch bytes to pass to the next call
   */
  static byte[] gather(
      RandomAccessInput slice,
      long offset,
      int bitsPerValue,
      int[] docs,
      int docsOffset,
      int count,
      int docBase,
      long[] out,
      int outOffset,
      byte[] buffer)
      throws IOException {
    final long first = docs[docsOffset] - docBase;
    final long last = docs[docsOffset + count - 1] - docBase;
    final long firstByte = (first * bitsPerValue) >>> 3;
    final long endByte = (((last + 1) * bitsPerValue) + 7) >>> 3;
    // 8 bytes past the span, so each value can be read with one 8-byte load; DirectWriter pads
    // less, so past the end of the slice the bytes are zeros
    final int needed = Math.toIntExact(endByte - firstByte) + Long.BYTES;
    if (buffer.length < needed) {
      buffer = new byte[Math.max(needed, buffer.length * 2)];
    }
    final long start = offset + firstByte;
    final int available = (int) Math.min(needed, slice.length() - start);
    slice.readBytes(start, buffer, 0, available);
    Arrays.fill(buffer, available, needed, (byte) 0);

    final byte[] b = buffer;
    final long bitBase = firstByte << 3;
    final int end = docsOffset + count;
    int o = outOffset;
    switch (bitsPerValue) {
      case 8 -> {
        for (int i = docsOffset; i < end; i++) {
          out[o++] = b[(int) (docs[i] - docBase - firstByte)] & 0xFFL;
        }
      }
      case 16 -> {
        for (int i = docsOffset; i < end; i++) {
          final int p = (int) (((long) (docs[i] - docBase) << 1) - firstByte);
          out[o++] = (short) BitUtil.VH_LE_SHORT.get(b, p) & 0xFFFFL;
        }
      }
      case 32 -> {
        for (int i = docsOffset; i < end; i++) {
          final int p = (int) (((long) (docs[i] - docBase) << 2) - firstByte);
          out[o++] = (int) BitUtil.VH_LE_INT.get(b, p) & 0xFFFFFFFFL;
        }
      }
      case 64 -> {
        for (int i = docsOffset; i < end; i++) {
          final int p = (int) (((long) (docs[i] - docBase) << 3) - firstByte);
          out[o++] = (long) BitUtil.VH_LE_LONG.get(b, p);
        }
      }
      case 1, 2, 4 -> {
        final int mask = (1 << bitsPerValue) - 1;
        for (int i = docsOffset; i < end; i++) {
          final long bit = (long) (docs[i] - docBase) * bitsPerValue - bitBase;
          out[o++] = (b[(int) (bit >>> 3)] >>> (bit & 7)) & mask;
        }
      }
      default -> {
        // 12, 20, 24, 28, 40, 48, 56: one unaligned little-endian 8-byte load per value
        final long mask = (1L << bitsPerValue) - 1;
        for (int i = docsOffset; i < end; i++) {
          final long bit = (long) (docs[i] - docBase) * bitsPerValue - bitBase;
          final long word = (long) BitUtil.VH_LE_LONG.get(b, (int) (bit >>> 3));
          out[o++] = (word >>> (bit & 7)) & mask;
        }
      }
    }
    return buffer;
  }
}
