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
package org.apache.lucene.benchmark.jmh.bufferpool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Bounded LRU block cache over the {@link MemorySegmentPool}. This is the standalone stand-in for
 * the plugin's Caffeine L2 ({@code CaffeineBlockCache}) + {@code BlockLoader}: same contract —
 * {@code get} is a lookup-only probe, {@code getOrLoad} loads the block from disk into a freshly
 * pool-acquired buffer on miss, and eviction simply drops the reference so the GC (via the pool's
 * Cleaner) reclaims the direct buffer.
 *
 * <p>Every disk load is one positional {@code FileChannel.read} of up to {@code blockSize} bytes —
 * the deterministic "IO call" unit recorded in {@link IOStats}.
 */
public final class LRUBlockCache implements AutoCloseable {

  private final Pool<RefCountedByteBuffer> pool;
  private final int blockSize;
  private final int maxBlocks;

  private final LinkedHashMap<FileBlockCacheKey, RefCountedByteBuffer> cache;
  private final Map<Path, FileChannel> channels = new HashMap<>();

  public LRUBlockCache(Pool<RefCountedByteBuffer> pool, int blockSize, int maxBlocks) {
    this.pool = pool;
    this.blockSize = blockSize;
    this.maxBlocks = maxBlocks;
    this.cache =
        new LinkedHashMap<>(16, 0.75f, /* accessOrder= */ true) {
          @Override
          protected boolean removeEldestEntry(
              Map.Entry<FileBlockCacheKey, RefCountedByteBuffer> eldest) {
            if (size() > LRUBlockCache.this.maxBlocks) {
              // Dropping the reference is the eviction: GC frees the direct buffer and the
              // pool's Cleaner decrements buffersInUse (no explicit free, as in the plugin).
              IOStats.recordEviction();
              return true;
            }
            return false;
          }
        };
  }

  /** Lookup-only probe (the plugin's {@code BlockCache.get}). Returns null on miss. */
  public synchronized RefCountedByteBuffer get(FileBlockCacheKey key) {
    return cache.get(key);
  }

  /** Lookup, loading the block from disk on miss (the plugin's {@code BlockCache.getOrLoad}). */
  public synchronized RefCountedByteBuffer getOrLoad(FileBlockCacheKey key) throws IOException {
    RefCountedByteBuffer v = cache.get(key);
    if (v != null) {
      IOStats.recordCacheHit(IOStats.extension(key.path()));
      return v;
    }
    v = load(key);
    cache.put(key, v);
    return v;
  }

  private RefCountedByteBuffer load(FileBlockCacheKey key) throws IOException {
    final RefCountedByteBuffer wrapper;
    try {
      wrapper = pool.tryAcquire(5, TimeUnit.SECONDS);
    } catch (IOException | RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException("Failed to acquire pooled buffer", e);
    }

    FileChannel channel = channel(key.path());
    ByteBuffer buf = wrapper.buffer();
    buf.clear();
    int total = 0;
    long pos = key.blockOffset();
    while (total < blockSize) {
      int n = channel.read(buf, pos + total);
      if (n < 0) {
        break; // EOF: last block of the file is short
      }
      total += n;
    }
    if (total <= 0) {
      throw new IOException(
          "Read 0 bytes for block at offset " + key.blockOffset() + " of " + key.path());
    }
    buf.position(0).limit(total);
    IOStats.recordBlockLoad(IOStats.extension(key.path()), total);
    // Re-wrap with the actual block length so segment().byteSize() == valid bytes.
    return new RefCountedByteBuffer(buf, total);
  }

  private FileChannel channel(Path path) throws IOException {
    FileChannel c = channels.get(path);
    if (c == null) {
      c = FileChannel.open(path, StandardOpenOption.READ);
      channels.put(path, c);
    }
    return c;
  }

  /** Drops all cached blocks (GC reclaims) and closes file channels. */
  public synchronized void clear() {
    cache.clear();
    closeChannels();
  }

  /** Invalidate all blocks and the channel of one file (plugin's per-file delete path). */
  public synchronized void invalidateFile(Path path) {
    Iterator<FileBlockCacheKey> it = cache.keySet().iterator();
    while (it.hasNext()) {
      if (it.next().path().equals(path)) {
        it.remove();
      }
    }
    FileChannel c = channels.remove(path);
    if (c != null) {
      try {
        c.close();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  public synchronized int size() {
    return cache.size();
  }

  private void closeChannels() {
    for (FileChannel c : channels.values()) {
      try {
        c.close();
      } catch (
          @SuppressWarnings("unused")
          IOException e) {
        // best-effort close
      }
    }
    channels.clear();
  }

  @Override
  public synchronized void close() {
    clear();
  }
}
