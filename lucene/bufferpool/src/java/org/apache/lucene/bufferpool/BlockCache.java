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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.apache.lucene.util.SuppressForbidden;

/**
 * Cache of fixed-size file blocks, backed by Caffeine, meant to be shared by all {@link
 * BufferPoolDirectory} instances of a process.
 *
 * <p>Each block is a read-only, little-endian, direct {@link ByteBuffer} of {@link #BLOCK_SIZE}
 * bytes (the last block of a file is shorter). The cache is bounded by the total bytes of the
 * cached blocks. There is no explicit memory pool and no reference counting: an evicted block is
 * freed by the garbage collector once no input still points at it. The bound must therefore stay
 * below {@code -XX:MaxDirectMemorySize}.
 *
 * <p>Concurrent misses on the same block are de-duplicated by Caffeine: one thread reads the block,
 * the others wait for it.
 */
public final class BlockCache {

  /** log2 of {@link #BLOCK_SIZE}. */
  public static final int BLOCK_SIZE_POWER = 17;

  /** Size of a cached block in bytes (128 KiB). */
  public static final int BLOCK_SIZE = 1 << BLOCK_SIZE_POWER;

  /** Mask of the offset of a byte within its block. */
  static final long BLOCK_MASK = BLOCK_SIZE - 1L;

  private final Cache<BlockKey, ByteBuffer> cache;
  private final Executor prefetchExecutor;

  /**
   * Creates a cache.
   *
   * @param maxBytes upper bound of the total size of all cached blocks
   * @param prefetchExecutor executor that loads prefetched blocks in the background. It may reject
   *     tasks with {@link RejectedExecutionException} to drop prefetches under load; {@code
   *     Runnable::run} loads them on the calling thread.
   */
  public BlockCache(long maxBytes, Executor prefetchExecutor) {
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be positive, got " + maxBytes);
    }
    this.cache = doPrivileged(() -> newCache(maxBytes));
    this.prefetchExecutor = prefetchExecutor;
  }

  private static Cache<BlockKey, ByteBuffer> newCache(long maxBytes) {
    return Caffeine.newBuilder()
        .maximumWeight(maxBytes)
        .weigher((BlockKey key, ByteBuffer block) -> block.capacity())
        // run cache maintenance on the calling thread so the cache owns no background threads
        .executor(Runnable::run)
        .build();
  }

  // Caffeine uses private lookups in static initializers, which a security manager must allow.
  // Extracted to a method to be able to apply the SuppressForbidden annotation.
  @SuppressWarnings("removal")
  @SuppressForbidden(reason = "security manager")
  private static <T> T doPrivileged(PrivilegedAction<T> action) {
    return AccessController.doPrivileged(action);
  }

  /**
   * Returns the block for {@code key}, reading it from {@code channel} on a miss.
   *
   * @param fileLength length of the whole file, which bounds the size of its last block
   */
  ByteBuffer getOrLoad(BlockKey key, FileChannel channel, long fileLength) throws IOException {
    try {
      return cache.get(key, k -> load(k, channel, fileLength));
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  /**
   * Loads the missing blocks among {@code blockCount} blocks that start at {@code
   * firstBlockOffset}, asynchronously. Best effort: the request is dropped when the executor
   * rejects it, and load failures are ignored, since a later read loads the block on demand.
   */
  void prefetch(
      Path file,
      long fileId,
      FileChannel channel,
      long fileLength,
      long firstBlockOffset,
      long blockCount) {
    final List<BlockKey> missing = new ArrayList<>();
    for (long i = 0; i < blockCount; i++) {
      BlockKey key = new BlockKey(file, fileId, firstBlockOffset + (i << BLOCK_SIZE_POWER));
      // containsKey does not count as an access, so a prefetch does not skew the eviction policy
      if (cache.asMap().containsKey(key) == false) {
        missing.add(key);
      }
    }
    if (missing.isEmpty()) {
      return;
    }
    try {
      prefetchExecutor.execute(
          () -> {
            for (BlockKey key : missing) {
              try {
                getOrLoad(key, channel, fileLength);
              } catch (@SuppressWarnings("unused") IOException | RuntimeException e) {
                // e.g. the input was closed before the prefetch ran
                return;
              }
            }
          });
    } catch (
        @SuppressWarnings("unused")
        RejectedExecutionException e) {
      // the executor is saturated: drop the prefetch
    }
  }

  /** Removes the blocks of one incarnation of a file. */
  void invalidateFile(Path file, long fileId, long fileLength) {
    for (long blockOffset = 0; blockOffset < fileLength; blockOffset += BLOCK_SIZE) {
      cache.invalidate(new BlockKey(file, fileId, blockOffset));
    }
  }

  /** Removes the blocks of all files under {@code directory}. Scans the whole cache. */
  void invalidateDirectory(Path directory) {
    cache.asMap().keySet().removeIf(key -> key.file().startsWith(directory));
  }

  /**
   * Returns the number of cached blocks.
   *
   * @return the number of cached blocks
   */
  public long size() {
    cache.cleanUp();
    return cache.estimatedSize();
  }

  private static ByteBuffer load(BlockKey key, FileChannel channel, long fileLength) {
    final int size = (int) Math.min(BLOCK_SIZE, fileLength - key.blockOffset());
    final ByteBuffer block = ByteBuffer.allocateDirect(size);
    try {
      long pos = key.blockOffset();
      while (block.hasRemaining()) {
        final int n = channel.read(block, pos);
        if (n < 0) {
          throw new EOFException(
              "read past EOF: file=" + key.file() + " pos=" + pos + " length=" + fileLength);
        }
        pos += n;
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    // readers only use absolute gets, so the shared buffer is never mutated
    return block.flip().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
  }
}
