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
package org.apache.lucene.benchmark.jmh;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.lucene.store.RandomAccessInput;

/**
 * A {@link RandomAccessInput} wrapper that simulates EFS-like latency on cache misses. Maintains a
 * simulated cache of 32KB block indices. Reads to blocks not in the cache incur configurable
 * artificial latency and increment a cache miss counter. {@link #prefetch(long, long)} warms the
 * simulated cache without blocking.
 *
 * <p>This is used in JMH benchmarks to measure the benefit of bulk prefetch: the prefetch path
 * warms blocks ahead of time (zero latency), while the per-doc path pays the simulated latency on
 * each cache miss.
 */
public class SlowIndexInput implements RandomAccessInput {

  /** 32KB block size matching the Caffeine buffer pool block size. */
  private static final int BLOCK_SHIFT = 15; // 32768 = 1 << 15

  private final RandomAccessInput delegate;
  private final long latencyNanos;
  private final Set<Long> warmBlocks;
  private final AtomicLong cacheMissCount;

  /**
   * Creates a SlowIndexInput with the specified latency per cache miss.
   *
   * @param delegate the underlying RandomAccessInput to wrap
   * @param latencyMillis artificial latency in milliseconds per cache miss (e.g., 1-2ms for EFS)
   */
  public SlowIndexInput(RandomAccessInput delegate, double latencyMillis) {
    this.delegate = delegate;
    this.latencyNanos = (long) (latencyMillis * 1_000_000);
    this.warmBlocks = new HashSet<>();
    this.cacheMissCount = new AtomicLong();
  }

  /** Creates a SlowIndexInput with default 1ms latency per cache miss. */
  public SlowIndexInput(RandomAccessInput delegate) {
    this(delegate, 1.0);
  }

  /** Returns the total number of cache misses observed during reads. */
  public long getCacheMissCount() {
    return cacheMissCount.get();
  }

  /** Resets the cache miss counter and clears the simulated cache. */
  public void reset() {
    cacheMissCount.set(0);
    warmBlocks.clear();
  }

  /**
   * Warms the simulated cache for the given byte range without blocking. This simulates the
   * behavior of {@code IndexInput.prefetch()} which triggers async IO to populate the buffer pool.
   */
  @Override
  public boolean prefetch(long offset, long length) throws IOException {
    long firstBlock = offset >> BLOCK_SHIFT;
    long lastBlock = (offset + length - 1) >> BLOCK_SHIFT;
    for (long block = firstBlock; block <= lastBlock; block++) {
      warmBlocks.add(block);
    }
    return true;
  }

  @Override
  public long length() {
    return delegate.length();
  }

  @Override
  public byte readByte(long pos) throws IOException {
    simulateLatencyIfCacheMiss(pos);
    return delegate.readByte(pos);
  }

  @Override
  public void readBytes(long pos, byte[] bytes, int offset, int length) throws IOException {
    simulateLatencyIfCacheMiss(pos);
    delegate.readBytes(pos, bytes, offset, length);
  }

  @Override
  public short readShort(long pos) throws IOException {
    simulateLatencyIfCacheMiss(pos);
    return delegate.readShort(pos);
  }

  @Override
  public int readInt(long pos) throws IOException {
    simulateLatencyIfCacheMiss(pos);
    return delegate.readInt(pos);
  }

  @Override
  public long readLong(long pos) throws IOException {
    simulateLatencyIfCacheMiss(pos);
    return delegate.readLong(pos);
  }

  /**
   * Checks if the block containing {@code pos} is in the simulated cache. If not, adds artificial
   * latency via busy-wait and increments the cache miss counter. The block is then added to the
   * cache (simulating that the read populated it).
   */
  private void simulateLatencyIfCacheMiss(long pos) {
    long block = pos >> BLOCK_SHIFT;
    if (!warmBlocks.contains(block)) {
      cacheMissCount.incrementAndGet();
      // Busy-wait for the configured latency to simulate IO delay
      busyWait(latencyNanos);
      // After the "IO", the block is now in the cache
      warmBlocks.add(block);
    }
  }

  /** Busy-wait loop for precise sub-millisecond delays (Thread.sleep is too coarse). */
  private static void busyWait(long nanos) {
    long start = System.nanoTime();
    while (System.nanoTime() - start < nanos) {
      Thread.onSpinWait();
    }
  }

  @Override
  public String toString() {
    return "SlowIndexInput(latencyNanos="
        + latencyNanos
        + ", cacheMisses="
        + cacheMissCount.get()
        + ", delegate="
        + delegate
        + ")";
  }
}
