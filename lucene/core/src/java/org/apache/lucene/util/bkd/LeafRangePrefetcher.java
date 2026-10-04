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

import java.io.IOException;
import java.util.Arrays;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.ArrayUtil;

/**
 * Collects the byte ranges of the leaves that an intersection will read, rounds them to whole
 * storage nodes, merges ranges that touch and prefetches the result in a few chunks of nearly equal
 * size, in file order. File pointers are offsets in the input that {@link #issue} gets.
 */
final class LeafRangePrefetcher {
  private final long nodeBytes;
  private long[] starts = new long[8];
  private long[] ends = new long[8];
  private int count;

  LeafRangePrefetcher(long nodeBytes) {
    assert Long.bitCount(nodeBytes) == 1 : nodeBytes;
    this.nodeBytes = nodeBytes;
  }

  /** Adds the byte range [start, end) of the input. Empty ranges are ignored. */
  void add(long start, long end) {
    assert start >= 0 : start;
    if (end <= start) {
      return;
    }
    if (count == starts.length) {
      starts = ArrayUtil.grow(starts, count + 1);
      ends = ArrayUtil.growExact(ends, starts.length);
    }
    starts[count] = start;
    ends[count] = end;
    count++;
  }

  /** Adds the storage node that holds the byte at {@code fp}. */
  void addNodeOf(long fp) {
    add(fp, fp + 1);
  }

  boolean isEmpty() {
    return count == 0;
  }

  /**
   * Rounds, merges and prefetches the collected ranges on {@code in}, in at most {@code max(chunks,
   * number of merged ranges)} calls. Returns the number of prefetch calls.
   */
  int issue(IndexInput in, int chunks) throws IOException {
    if (count == 0) {
      return 0;
    }
    final long length = in.length();
    // align to whole nodes
    long[][] ranges = new long[count][];
    for (int i = 0; i < count; i++) {
      long s = starts[i] - Math.floorMod(starts[i], nodeBytes);
      long e = Math.min(length, ceil(ends[i]));
      ranges[i] = new long[] {s, e};
    }
    Arrays.sort(ranges, (a, b) -> Long.compare(a[0], b[0]));
    // merge ranges that touch or overlap
    int merged = 0;
    for (int i = 0; i < ranges.length; i++) {
      long[] r = ranges[i];
      if (r[1] <= r[0]) {
        continue;
      }
      if (merged > 0 && r[0] <= ranges[merged - 1][1]) {
        ranges[merged - 1][1] = Math.max(ranges[merged - 1][1], r[1]);
      } else {
        ranges[merged++] = r;
      }
    }
    if (merged == 0) {
      return 0;
    }
    long[] blocks = new long[merged];
    long totalBlocks = 0;
    for (int i = 0; i < merged; i++) {
      blocks[i] = (ceil(ranges[i][1]) - ranges[i][0]) / nodeBytes;
      totalBlocks += blocks[i];
    }
    final long pieces = Math.min(chunks, totalBlocks);
    // pieces per range: proportional to its blocks, at least 1, at most its blocks
    long[] perRange = new long[merged];
    long assigned = 0;
    for (int i = 0; i < merged; i++) {
      perRange[i] = Math.max(1, Math.min(blocks[i], pieces * blocks[i] / totalBlocks));
      assigned += perRange[i];
    }
    while (assigned < pieces) {
      int best = -1;
      for (int i = 0; i < merged; i++) {
        if (perRange[i] < blocks[i]
            && (best == -1 || blocks[i] * perRange[best] > blocks[best] * perRange[i])) {
          best = i;
        }
      }
      if (best == -1) {
        break;
      }
      perRange[best]++;
      assigned++;
    }
    int calls = 0;
    for (int i = 0; i < merged; i++) {
      long start = ranges[i][0];
      long end = ranges[i][1];
      long base = blocks[i] / perRange[i];
      long extra = blocks[i] % perRange[i];
      long pos = start;
      for (long p = 0; p < perRange[i]; p++) {
        long n = base + (p < extra ? 1 : 0);
        long pieceEnd = Math.min(end, pos + n * nodeBytes);
        in.prefetch(pos, pieceEnd - pos);
        calls++;
        pos = pieceEnd;
      }
      assert pos == end : pos + " != " + end;
    }
    return calls;
  }

  private long ceil(long fp) {
    long rem = Math.floorMod(fp, nodeBytes);
    return rem == 0 ? fp : fp - rem + nodeBytes;
  }
}
