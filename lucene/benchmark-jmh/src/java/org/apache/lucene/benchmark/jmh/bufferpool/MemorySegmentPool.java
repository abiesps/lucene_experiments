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

import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.apache.lucene.util.SuppressForbidden;

/**
 * Backport of JunoStorageEncryptionPlugin {@code org.opensearch.index.store.pool.MemorySegmentPool}
 * (opensearch-3.3.0-junosearchworker branch), with encryption, Juno metrics/logging, and the
 * OpenSearch circuit-breaker types stripped.
 *
 * <p>Semantics preserved as-is:
 *
 * <ul>
 *   <li>There is NO free list. Every successful {@link #tryAcquire} allocates a brand-new
 *       page-aligned direct {@link ByteBuffer}.
 *   <li>The JVM GC frees buffers: a {@link Cleaner} registered on each {@link RefCountedByteBuffer}
 *       wrapper decrements {@code buffersInUse} when the wrapper becomes unreachable (e.g. evicted
 *       from the block cache). {@link #release} is a no-op.
 *   <li>Admission control via {@code allocationLimit = maxSegments + maxSegments *
 *       gcHeadroomFraction}; when over the limit the pool either fails fast ({@code
 *       stallLoopDisabled=true}, the production default) or enters the bounded 10ms stall loop.
 *   <li>Direct-memory OOM engages the throttle and (optionally) issues a {@code System.gc()} hint
 *       gated by a cooldown; a background monitor clears the throttle once allocations are again
 *       below the limit.
 * </ul>
 */
public class MemorySegmentPool implements Pool<RefCountedByteBuffer>, AutoCloseable {

  private static final Cleaner CLEANER = Cleaner.create();

  private final int segmentSize;

  /** Native-memory footprint per segment. */
  private final int reservedSegmentSize;

  /** Alignment padding per segment: {@code reservedSegmentSize - segmentSize}. */
  private final int segmentPadding;

  private final int maxSegments;
  private final long totalMemory;
  private final int allocationLimit;
  private final Thread memoryMonitor;
  private final AtomicInteger buffersInUse = new AtomicInteger(0);
  private final LongAdder stallCount = new LongAdder();
  private final LongAdder gcTriggerCount = new LongAdder();
  private final LongAdder oomCount = new LongAdder();
  private final LongAdder cumulativeAllocations = new LongAdder();

  private final Runnable cleanerAction;

  /** Edge-triggered direct-memory OOM signal; raised by the OOM catch, consumed per tick. */
  private final AtomicBoolean recentOom = new AtomicBoolean(false);

  /** GC hint controls. */
  private volatile boolean gcHintEnabled;

  private volatile long gcHintCooldownNanos;
  private volatile long lastGcHintNanos = Long.MIN_VALUE / 2;

  /** Throttling controls. */
  private volatile boolean throttle = false;

  /**
   * When {@code true}, tryAcquire fails fast when buffersInUse exceeds allocationLimit instead of
   * entering the bounded stall loop. Production default in the plugin is {@code true}.
   */
  private volatile boolean stallLoopDisabled = true;

  private volatile boolean closed = false;

  /** Thrown where the plugin throws OpenSearch's transient {@code CircuitBreakingException}. */
  public static final class PoolExhaustedException extends RuntimeException {
    PoolExhaustedException(String message) {
      super(message);
    }
  }

  public MemorySegmentPool(
      long totalMemory,
      int segmentSize,
      int reservedSegmentSize,
      double gcHeadroomFraction,
      boolean gcHintEnabled,
      long gcHintCooldownSeconds) {
    if (totalMemory % reservedSegmentSize != 0) {
      throw new IllegalArgumentException(
          "Total memory must be a multiple of reserved segment size");
    }
    this.totalMemory = totalMemory;
    this.segmentSize = segmentSize;
    this.reservedSegmentSize = reservedSegmentSize;
    this.segmentPadding = reservedSegmentSize - segmentSize;
    this.maxSegments = (int) (totalMemory / reservedSegmentSize);
    this.allocationLimit = maxSegments + (int) (maxSegments * gcHeadroomFraction);
    this.cleanerAction = buffersInUse::decrementAndGet;
    this.gcHintEnabled = gcHintEnabled;
    this.gcHintCooldownNanos = gcHintCooldownSeconds * 1_000_000_000L;
    this.memoryMonitor = new Thread(this::memoryMonitorLoop, "pool-gc-debt-monitor");
    memoryMonitor.setDaemon(true);
    memoryMonitor.start();
  }

  @Override
  public RefCountedByteBuffer tryAcquire(long timeout, TimeUnit unit) throws Exception {
    if (closed) throw new IllegalStateException("Pool is closed");
    if (throttle) {
      throw new PoolExhaustedException("Direct memory throttle engaged");
    }

    if (buffersInUse.incrementAndGet() <= allocationLimit) {
      return allocateAndWrap();
    }
    buffersInUse.decrementAndGet();

    // Fail-fast when stall loop is disabled — reject immediately instead of spinning.
    if (stallLoopDisabled) {
      stallCount.increment();
      throw new PoolExhaustedException(
          "Pool over limit (stall loop disabled)"
              + " (inUse="
              + buffersInUse.get()
              + ", limit="
              + allocationLimit
              + ")");
    }

    long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
    stallCount.increment();
    while (true) {
      if (closed) throw new IllegalStateException("Pool is closed");
      if (buffersInUse.incrementAndGet() <= allocationLimit) {
        return allocateAndWrap();
      }
      buffersInUse.decrementAndGet();
      long remainingNanos = deadlineNanos - System.nanoTime();
      if (remainingNanos <= 0) {
        throw new PoolExhaustedException(
            "Pool acquisition timed out after "
                + unit.toMillis(timeout)
                + "ms (inUse="
                + buffersInUse.get()
                + ", max="
                + maxSegments
                + ", limit="
                + allocationLimit
                + ")");
      }
      Thread.sleep(Math.min(TimeUnit.NANOSECONDS.toMillis(remainingNanos), 10));
    }
  }

  private RefCountedByteBuffer allocateAndWrap() {
    final ByteBuffer buf;
    try {
      // INVARIANT: this try body must contain ONLY the direct-memory allocation call.
      buf = defaultAllocator(segmentSize);
    } catch (
        @SuppressWarnings("unused")
        OutOfMemoryError e) {
      buffersInUse.decrementAndGet();
      throttle = true;
      recentOom.set(true);
      oomCount.increment();
      throw new PoolExhaustedException("Direct memory exhausted during allocation");
    }
    final RefCountedByteBuffer wrapper;
    try {
      wrapper = wrapAndRegister(buf);
    } catch (Throwable t) {
      buffersInUse.decrementAndGet();
      throw t;
    }
    cumulativeAllocations.increment();
    return wrapper;
  }

  private RefCountedByteBuffer wrapAndRegister(ByteBuffer direct) {
    RefCountedByteBuffer wrapper = new RefCountedByteBuffer(direct, segmentSize);
    CLEANER.register(wrapper, cleanerAction);
    return wrapper;
  }

  private void memoryMonitorLoop() {
    while (!closed) {
      try {
        // Plugin ticks at 100ms (memoryMonitorLoop).
        Thread.sleep(100);
        // Recompute the throttle signal each tick: with the plugin's MXBean/OS-meminfo
        // monitors stripped, throttle is engaged by an OOM and cleared once the pool
        // is back under its allocation limit.
        throttle = recentOom.get();
        // ProactiveMemoryMonitor equivalent: under pool pressure the only way to reclaim
        // GC-managed buffers ("gc debt" — evicted wrappers not yet collected) is a GC cycle,
        // so hint one, gated by the cooldown.
        if (recentOom.get() || buffersInUse.get() >= maxSegments) {
          maybeGcHint();
        }
        recentOom.set(false);
      } catch (
          @SuppressWarnings("unused")
          InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /** Issue a {@code System.gc()} hint, gated by the kill switch and cooldown. */
  @SuppressForbidden(reason = "backport of MemorySegmentPool.maybeGcHint gc-debt relief")
  private void maybeGcHint() {
    if (!gcHintEnabled) return;
    long now = System.nanoTime();
    if (now - lastGcHintNanos < gcHintCooldownNanos) return;
    System.gc();
    gcTriggerCount.increment();
    lastGcHintNanos = now;
  }

  @SuppressForbidden(reason = "backport of MemorySegmentPool.defaultAllocator direct allocation")
  private ByteBuffer defaultAllocator(int size) {
    ByteBuffer raw = ByteBuffer.allocateDirect(size + segmentPadding);
    long addr = MemorySegment.ofBuffer(raw).address();
    // segmentPadding is configured to equal the page size, so it doubles as the alignment
    // modulus: advance to the next page-aligned offset within the over-allocated buffer.
    int pad =
        segmentPadding > 0
            ? (int) ((segmentPadding - (addr % segmentPadding)) % segmentPadding)
            : 0;
    raw.position(pad).limit(pad + size);
    return raw.slice().order(ByteOrder.LITTLE_ENDIAN);
  }

  @Override
  public void release(RefCountedByteBuffer refSegment) {
    // No-op: Cleaner handles lifecycle when wrapper is GC'd.
  }

  @Override
  public long totalMemory() {
    return totalMemory;
  }

  @Override
  public long availableMemory() {
    return (long) Math.max(0, maxSegments - buffersInUse.get()) * reservedSegmentSize;
  }

  @Override
  public int pooledSegmentSize() {
    return reservedSegmentSize;
  }

  public int getBuffersInUse() {
    return buffersInUse.get();
  }

  public long getAllocatedBytes() {
    return (long) buffersInUse.get() * reservedSegmentSize;
  }

  @Override
  public boolean isUnderPressure() {
    return buffersInUse.get() >= (int) (maxSegments * 0.95);
  }

  public void setStallLoopDisabled(boolean disabled) {
    this.stallLoopDisabled = disabled;
  }

  public void setGcHintEnabled(boolean enabled) {
    this.gcHintEnabled = enabled;
  }

  public long getStallCount() {
    return stallCount.sum();
  }

  public long getGcTriggerCount() {
    return gcTriggerCount.sum();
  }

  public long getOomCount() {
    return oomCount.sum();
  }

  /** Total number of fresh direct-buffer allocations (there is no reuse, so this == acquires). */
  public long getCumulativeAllocations() {
    return cumulativeAllocations.sum();
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    memoryMonitor.interrupt();
    try {
      memoryMonitor.join(5000);
    } catch (
        @SuppressWarnings("unused")
        InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean isClosed() {
    return closed;
  }
}
