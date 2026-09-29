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
package org.apache.lucene.bufferpool;

import static org.apache.lucene.bufferpool.BlockCache.BLOCK_SIZE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.RandomAccessInput;
import org.apache.lucene.tests.store.BaseDirectoryTestCase;
import org.apache.lucene.util.ArrayUtil;

public class TestBufferPoolDirectory extends BaseDirectoryTestCase {

  @Override
  protected Directory getDirectory(Path file) throws IOException {
    // a cache of a few blocks exercises eviction; prefetch runs on the calling thread
    final long maxBytes = random().nextBoolean() ? 4L * BLOCK_SIZE : 64L * BLOCK_SIZE;
    return new BufferPoolDirectory(file, new BlockCache(maxBytes, Runnable::run));
  }

  private static byte[] randomData(int size) {
    final byte[] data = new byte[size];
    random().nextBytes(data);
    return data;
  }

  private static void write(Directory dir, String name, byte[] data) throws IOException {
    try (IndexOutput out = dir.createOutput(name, IOContext.DEFAULT)) {
      out.writeBytes(data, data.length);
    }
  }

  public void testReadsAcrossBlockBoundaries() throws IOException {
    final byte[] data = randomData(3 * BLOCK_SIZE + random().nextInt(BLOCK_SIZE));
    final ByteBuffer expected = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    try (Directory dir = getDirectory(createTempDir())) {
      write(dir, "multi_block", data);
      try (IndexInput in = dir.openInput("multi_block", IOContext.DEFAULT)) {
        assertEquals(data.length, in.length());
        for (int iter = 0; iter < 2000; iter++) {
          // positions clustered around block boundaries
          final int boundary = BLOCK_SIZE * (1 + random().nextInt(3));
          final int pos =
              Math.max(0, Math.min(data.length - 8, boundary - 8 + random().nextInt(16)));
          in.seek(pos);
          switch (random().nextInt(5)) {
            case 0 -> assertEquals(data[pos], in.readByte());
            case 1 -> assertEquals(expected.getShort(pos), in.readShort());
            case 2 -> assertEquals(expected.getInt(pos), in.readInt());
            case 3 -> assertEquals(expected.getLong(pos), in.readLong());
            default -> {
              final int len = Math.min(data.length - pos, random().nextInt(2 * BLOCK_SIZE));
              final byte[] actual = new byte[len];
              in.readBytes(actual, 0, len);
              assertArrayEquals(ArrayUtil.copyOfSubArray(data, pos, pos + len), actual);
            }
          }
        }
        final int sliceOffset = random().nextInt(BLOCK_SIZE);
        final RandomAccessInput slice =
            in.randomAccessSlice(sliceOffset, data.length - sliceOffset);
        for (int iter = 0; iter < 2000; iter++) {
          final int p = random().nextInt(data.length - sliceOffset - 8);
          assertEquals(expected.getLong(sliceOffset + p), slice.readLong(p));
          assertEquals(expected.getInt(sliceOffset + p), slice.readInt(p));
          assertEquals(expected.getShort(sliceOffset + p), slice.readShort(p));
          assertEquals(data[sliceOffset + p], slice.readByte(p));
        }
      }
    }
  }

  public void testReplacedFileIsNotServedFromCache() throws IOException {
    final byte[] first = randomData(2 * BLOCK_SIZE + 17);
    final byte[] second = randomData(first.length);
    try (Directory dir = getDirectory(createTempDir())) {
      write(dir, "replaced", first);
      // keep a reader of the old file open across the delete, the way an old searcher would
      try (IndexInput old = dir.openInput("replaced", IOContext.DEFAULT)) {
        final byte[] read = new byte[first.length];
        old.readBytes(read, 0, read.length);
        assertArrayEquals(first, read);

        dir.deleteFile("replaced");
        // a file system that cannot delete open files (WindowsFS) leaves the delete pending, so the
        // name cannot be re-used while the old reader is open
        assumeTrue("file system does not delete open files", dir.getPendingDeletions().isEmpty());
        write(dir, "replaced", second);

        // the old reader re-populates the cache with blocks of the old file
        old.seek(0);
        old.readBytes(read, 0, read.length);
        assertArrayEquals(first, read);

        try (IndexInput fresh = dir.openInput("replaced", IOContext.DEFAULT)) {
          fresh.readBytes(read, 0, read.length);
          assertArrayEquals(second, read);
        }
      }

      write(dir, "source", first);
      try (IndexInput in = dir.openInput("replaced", IOContext.DEFAULT)) {
        in.readBytes(new byte[second.length], 0, second.length);
      }
      dir.rename("source", "replaced");
      try (IndexInput in = dir.openInput("replaced", IOContext.DEFAULT)) {
        final byte[] read = new byte[first.length];
        in.readBytes(read, 0, read.length);
        assertArrayEquals(first, read);
      }
    }
  }

  public void testPrefetchLoadsBlocks() throws IOException {
    final byte[] data = randomData(4 * BLOCK_SIZE);
    final BlockCache cache = new BlockCache(64L * BLOCK_SIZE, Runnable::run);
    try (Directory dir = new BufferPoolDirectory(createTempDir(), cache)) {
      write(dir, "prefetched", data);
      try (IndexInput in = dir.openInput("prefetched", IOContext.DEFAULT)) {
        assertEquals(0, cache.size());
        // spans the end of block 0 and the start of block 2
        in.prefetch(BLOCK_SIZE - 1, BLOCK_SIZE + 2);
        assertEquals(3, cache.size());
        in.prefetch(0, 1);
        assertEquals(3, cache.size());
        final IndexInput slice = in.slice("slice", 3L * BLOCK_SIZE, BLOCK_SIZE);
        slice.prefetch(0, BLOCK_SIZE);
        assertEquals(4, cache.size());
        assertEquals(data[3 * BLOCK_SIZE], slice.readByte());
      }
    }
    assertEquals("closing the directory drops its blocks", 0, cache.size());
  }
}
