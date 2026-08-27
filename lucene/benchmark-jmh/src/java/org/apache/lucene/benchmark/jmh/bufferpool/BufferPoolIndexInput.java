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

import java.io.EOFException;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Path;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.RandomAccessInput;

/**
 * Backport of JunoStorageEncryptionPlugin {@code
 * org.opensearch.index.store.bufferpoolfs.CachedMemorySegmentIndexInput}
 * (opensearch-3.3.0-junosearchworker branch) with encryption, read-ahead, RadixBlockTable L1,
 * working-set estimation, and shard plumbing stripped. The single-current-block fast path, the
 * block-cache miss path, and the clone/slice offset arithmetic are ported as-is.
 *
 * <p>{@link #prefetch} is a deliberate no-op so every recorded block load is demand-driven — this
 * is what makes per-query IO counting deterministic.
 */
public class BufferPoolIndexInput extends IndexInput implements RandomAccessInput {

  static final ValueLayout.OfByte LAYOUT_BYTE = ValueLayout.JAVA_BYTE;
  static final ValueLayout.OfShort LAYOUT_LE_SHORT =
      ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
  static final ValueLayout.OfInt LAYOUT_LE_INT =
      ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
  static final ValueLayout.OfLong LAYOUT_LE_LONG =
      ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
  static final ValueLayout.OfFloat LAYOUT_LE_FLOAT =
      ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

  final long length;
  final Path path;
  final String ext;
  final LRUBlockCache blockCache;
  final int blockSize;
  final long blockMask;

  final long absoluteBaseOffset; // absolute position in original file where this input starts
  final boolean isSlice;

  long curPosition = 0L; // position within this input (0-based)
  volatile boolean isOpen = true;

  // Single block cache for current access
  private long currentBlockOffset = -1;
  private RefCountedByteBuffer currentBlock = null;

  // Cached offset from last getCacheBlockWithOffset call
  private int lastOffsetInBlock;

  // --- JIT-friendly fast-path fields ---
  private long currentBlockEnd = 0L;
  private MemorySegment currentSegment;
  private long currentBlockStart;
  private long currentBlockStartRelative; // = currentBlockStart - absoluteBaseOffset

  public static BufferPoolIndexInput newInstance(
      String resourceDescription, Path path, long length, LRUBlockCache blockCache, int blockSize) {
    BufferPoolIndexInput input =
        new BufferPoolIndexInput(
            resourceDescription, path, 0, length, blockCache, blockSize, false);
    try {
      input.seek(0L);
    } catch (IOException ioe) {
      throw new AssertionError(ioe);
    }
    return input;
  }

  private BufferPoolIndexInput(
      String resourceDescription,
      Path path,
      long absoluteBaseOffset,
      long length,
      LRUBlockCache blockCache,
      int blockSize,
      boolean isSlice) {
    super(resourceDescription);
    this.path = path;
    this.ext = IOStats.extension(path);
    this.absoluteBaseOffset = absoluteBaseOffset;
    this.length = length;
    this.blockCache = blockCache;
    this.blockSize = blockSize;
    this.blockMask = blockSize - 1L;
    this.isSlice = isSlice;
  }

  void ensureOpen() {
    if (!isOpen) {
      throw alreadyClosed(null);
    }
  }

  RuntimeException handlePositionalIOOBE(
      @SuppressWarnings("unused") RuntimeException unused, String action, long pos)
      throws IOException {
    if (pos < 0L) {
      return new IllegalArgumentException(action + " negative position (pos=" + pos + "): " + this);
    } else {
      throw new EOFException(action + " past EOF (pos=" + pos + "): " + this);
    }
  }

  AlreadyClosedException alreadyClosed(@SuppressWarnings("unused") RuntimeException unused) {
    return new AlreadyClosedException("Already closed: " + this);
  }

  /**
   * Optimized method to get both cache block and offset in one operation. Fast path kept small for
   * JIT inlining.
   */
  private MemorySegment getCacheBlockWithOffset(long pos) throws IOException {
    final long fileOffset = absoluteBaseOffset + pos;
    final long blockOffset = fileOffset & ~blockMask;
    lastOffsetInBlock = (int) (fileOffset - blockOffset);

    // Fast path: reuse current block if still valid.
    if (blockOffset == currentBlockOffset && currentBlock != null) {
      return currentSegment;
    }
    return acquireCacheBlockOnMiss(blockOffset);
  }

  /** Slow path for cache block acquisition. */
  private MemorySegment acquireCacheBlockOnMiss(long blockOffset) throws IOException {
    final RefCountedByteBuffer cacheValue =
        blockCache.getOrLoad(new FileBlockCacheKey(path, blockOffset));

    currentBlockOffset = blockOffset;
    currentBlock = cacheValue;

    final MemorySegment seg = cacheValue.segment();
    currentSegment = seg;
    currentBlockStart = blockOffset;
    currentBlockStartRelative = blockOffset - absoluteBaseOffset;
    currentBlockEnd = currentBlockStartRelative + seg.byteSize();
    return seg;
  }

  private byte readByteSlow(long pos) throws IOException {
    try {
      final MemorySegment seg = getCacheBlockWithOffset(pos);
      final byte v = seg.get(LAYOUT_BYTE, lastOffsetInBlock);
      curPosition = pos + 1;
      return v;
    } catch (IndexOutOfBoundsException ioobe) {
      throw handlePositionalIOOBE(ioobe, "read", pos);
    } catch (NullPointerException | IllegalStateException e) {
      throw alreadyClosed(e);
    }
  }

  private short readShortSlow(long pos) throws IOException {
    try {
      final MemorySegment seg = getCacheBlockWithOffset(pos);
      final int off = lastOffsetInBlock;
      if (off + Short.BYTES > seg.byteSize()) {
        return super.readShort();
      }
      final short v = seg.get(LAYOUT_LE_SHORT, off);
      curPosition = pos + Short.BYTES;
      return v;
    } catch (IndexOutOfBoundsException ioobe) {
      throw handlePositionalIOOBE(ioobe, "read", pos);
    } catch (NullPointerException | IllegalStateException e) {
      throw alreadyClosed(e);
    }
  }

  private int readIntSlow(long pos) throws IOException {
    try {
      final MemorySegment seg = getCacheBlockWithOffset(pos);
      final int off = lastOffsetInBlock;
      if (off + Integer.BYTES > seg.byteSize()) {
        return super.readInt();
      }
      final int v = seg.get(LAYOUT_LE_INT, off);
      curPosition = pos + Integer.BYTES;
      return v;
    } catch (IndexOutOfBoundsException ioobe) {
      throw handlePositionalIOOBE(ioobe, "read", pos);
    } catch (NullPointerException | IllegalStateException e) {
      throw alreadyClosed(e);
    }
  }

  private long readLongSlow(long pos) throws IOException {
    try {
      final MemorySegment seg = getCacheBlockWithOffset(pos);
      final int off = lastOffsetInBlock;
      if (off + Long.BYTES > seg.byteSize()) {
        return super.readLong();
      }
      final long v = seg.get(LAYOUT_LE_LONG, off);
      curPosition = pos + Long.BYTES;
      return v;
    } catch (IndexOutOfBoundsException ioobe) {
      throw handlePositionalIOOBE(ioobe, "read", pos);
    } catch (NullPointerException | IllegalStateException e) {
      throw alreadyClosed(e);
    }
  }

  @Override
  public final byte readByte() throws IOException {
    final long pos = curPosition;
    if (pos >= currentBlockStartRelative && pos < currentBlockEnd) {
      final long off = absoluteBaseOffset + pos - currentBlockStart;
      final byte v = currentSegment.get(LAYOUT_BYTE, off);
      curPosition = pos + 1;
      return v;
    }
    return readByteSlow(pos);
  }

  @Override
  public final void readBytes(byte[] b, int offset, int len) throws IOException {
    if (len == 0) return;

    final long startPos = curPosition;
    int remaining = len;
    int bufferOffset = offset;
    long currentPos = startPos;

    try {
      while (remaining > 0) {
        final MemorySegment seg = getCacheBlockWithOffset(currentPos);
        final int offInBlock = lastOffsetInBlock;
        final int avail = (int) (seg.byteSize() - offInBlock);

        // Fast path: full block copy
        if (offInBlock == 0 && remaining >= blockSize && seg.byteSize() >= blockSize) {
          MemorySegment.copy(seg, LAYOUT_BYTE, 0L, b, bufferOffset, blockSize);
          remaining -= blockSize;
          bufferOffset += blockSize;
          currentPos += blockSize;
          continue;
        }

        // Partial block
        final int toRead = Math.min(remaining, avail);
        MemorySegment.copy(seg, LAYOUT_BYTE, offInBlock, b, bufferOffset, toRead);

        remaining -= toRead;
        bufferOffset += toRead;
        currentPos += toRead;
      }

      curPosition = startPos + len;
    } catch (IndexOutOfBoundsException ioobe) {
      throw handlePositionalIOOBE(ioobe, "read", startPos);
    } catch (NullPointerException | IllegalStateException e) {
      throw alreadyClosed(e);
    }
  }

  @Override
  public void readInts(int[] dst, int offset, int length) throws IOException {
    if (length == 0) return;

    final long startPos = getFilePointer();
    final long totalBytes = Integer.BYTES * (long) length;

    try {
      final MemorySegment segment = getCacheBlockWithOffset(startPos);
      final int offsetInBlock = lastOffsetInBlock;

      if (offsetInBlock + totalBytes <= segment.byteSize()) {
        MemorySegment.copy(segment, LAYOUT_LE_INT, offsetInBlock, dst, offset, length);
        curPosition += totalBytes;
      } else {
        super.readInts(dst, offset, length);
      }
    } catch (IndexOutOfBoundsException ioobe) {
      throw handlePositionalIOOBE(ioobe, "read", startPos);
    } catch (NullPointerException | IllegalStateException e) {
      throw alreadyClosed(e);
    }
  }

  @Override
  public void readLongs(long[] dst, int offset, int length) throws IOException {
    if (length == 0) return;

    final long startPos = getFilePointer();
    final long totalBytes = Long.BYTES * (long) length;

    try {
      final MemorySegment segment = getCacheBlockWithOffset(startPos);
      final int offsetInBlock = lastOffsetInBlock;

      if (offsetInBlock + totalBytes <= segment.byteSize()) {
        MemorySegment.copy(segment, LAYOUT_LE_LONG, offsetInBlock, dst, offset, length);
        curPosition += totalBytes;
      } else {
        super.readLongs(dst, offset, length);
      }
    } catch (IndexOutOfBoundsException ioobe) {
      throw handlePositionalIOOBE(ioobe, "read", startPos);
    } catch (NullPointerException | IllegalStateException e) {
      throw alreadyClosed(e);
    }
  }

  @Override
  public void readFloats(float[] dst, int offset, int length) throws IOException {
    if (length == 0) return;

    // Full-precision-vector load accounting: on the .vec file each rescore-phase
    // OffHeapFloatVectorValues.vectorValue(ord) issues exactly one readFloats(dim).
    IOStats.recordFloatRead(ext);

    final long startPos = getFilePointer();
    final long totalBytes = Float.BYTES * (long) length;

    try {
      final MemorySegment segment = getCacheBlockWithOffset(startPos);
      final int offsetInBlock = lastOffsetInBlock;

      if (offsetInBlock + totalBytes <= segment.byteSize()) {
        MemorySegment.copy(segment, LAYOUT_LE_FLOAT, offsetInBlock, dst, offset, length);
        curPosition += totalBytes;
      } else {
        super.readFloats(dst, offset, length);
      }
    } catch (IndexOutOfBoundsException ioobe) {
      throw handlePositionalIOOBE(ioobe, "read", startPos);
    } catch (NullPointerException | IllegalStateException e) {
      throw alreadyClosed(e);
    }
  }

  @Override
  public final short readShort() throws IOException {
    final long pos = curPosition;
    if (pos >= currentBlockStartRelative && pos + Short.BYTES <= currentBlockEnd) {
      final long off = absoluteBaseOffset + pos - currentBlockStart;
      final short v = currentSegment.get(LAYOUT_LE_SHORT, off);
      curPosition = pos + Short.BYTES;
      return v;
    }
    return readShortSlow(pos);
  }

  @Override
  public final int readInt() throws IOException {
    final long pos = curPosition;
    if (pos >= currentBlockStartRelative && pos + Integer.BYTES <= currentBlockEnd) {
      final long off = absoluteBaseOffset + pos - currentBlockStart;
      final int v = currentSegment.get(LAYOUT_LE_INT, off);
      curPosition = pos + Integer.BYTES;
      return v;
    }
    return readIntSlow(pos);
  }

  @Override
  public final long readLong() throws IOException {
    final long pos = curPosition;
    if (pos >= currentBlockStartRelative && pos + Long.BYTES <= currentBlockEnd) {
      final long off = absoluteBaseOffset + pos - currentBlockStart;
      final long v = currentSegment.get(LAYOUT_LE_LONG, off);
      curPosition = pos + Long.BYTES;
      return v;
    }
    return readLongSlow(pos);
  }

  @Override
  public long getFilePointer() {
    ensureOpen();
    return curPosition;
  }

  @Override
  public void seek(long pos) throws IOException {
    ensureOpen();
    if (pos < 0 || pos > length) {
      throw handlePositionalIOOBE(null, "seek", pos);
    }
    this.curPosition = pos;
  }

  @Override
  public byte readByte(long pos) throws IOException {
    if (pos < 0 || pos >= length) {
      return 0;
    }
    if (pos >= currentBlockStartRelative && pos < currentBlockEnd) {
      final long off = absoluteBaseOffset + pos - currentBlockStart;
      return currentSegment.get(LAYOUT_BYTE, off);
    }
    try {
      final MemorySegment segment = getCacheBlockWithOffset(pos);
      return segment.get(LAYOUT_BYTE, lastOffsetInBlock);
    } catch (IndexOutOfBoundsException ioobe) {
      throw handlePositionalIOOBE(ioobe, "read", pos);
    } catch (NullPointerException | IllegalStateException e) {
      throw alreadyClosed(e);
    }
  }

  @Override
  public short readShort(long pos) throws IOException {
    if (pos >= currentBlockStartRelative && pos + Short.BYTES <= currentBlockEnd) {
      final long off = absoluteBaseOffset + pos - currentBlockStart;
      return currentSegment.get(LAYOUT_LE_SHORT, off);
    }
    try {
      final MemorySegment segment = getCacheBlockWithOffset(pos);
      final int offsetInBlock = lastOffsetInBlock;
      if (offsetInBlock + Short.BYTES > segment.byteSize()) {
        long savedPos = getFilePointer();
        try {
          seek(pos);
          return readShort();
        } finally {
          seek(savedPos);
        }
      }
      return segment.get(LAYOUT_LE_SHORT, offsetInBlock);
    } catch (IndexOutOfBoundsException ioobe) {
      throw handlePositionalIOOBE(ioobe, "read", pos);
    } catch (NullPointerException | IllegalStateException e) {
      throw alreadyClosed(e);
    }
  }

  @Override
  public int readInt(long pos) throws IOException {
    if (pos >= currentBlockStartRelative && pos + Integer.BYTES <= currentBlockEnd) {
      final long off = absoluteBaseOffset + pos - currentBlockStart;
      return currentSegment.get(LAYOUT_LE_INT, off);
    }
    try {
      final MemorySegment segment = getCacheBlockWithOffset(pos);
      final int offsetInBlock = lastOffsetInBlock;
      if (offsetInBlock + Integer.BYTES > segment.byteSize()) {
        long savedPos = getFilePointer();
        try {
          seek(pos);
          return readInt();
        } finally {
          seek(savedPos);
        }
      }
      return segment.get(LAYOUT_LE_INT, offsetInBlock);
    } catch (IndexOutOfBoundsException ioobe) {
      throw handlePositionalIOOBE(ioobe, "read", pos);
    } catch (NullPointerException | IllegalStateException e) {
      throw alreadyClosed(e);
    }
  }

  @Override
  public long readLong(long pos) throws IOException {
    if (pos >= currentBlockStartRelative && pos + Long.BYTES <= currentBlockEnd) {
      final long off = absoluteBaseOffset + pos - currentBlockStart;
      return currentSegment.get(LAYOUT_LE_LONG, off);
    }
    try {
      final MemorySegment segment = getCacheBlockWithOffset(pos);
      final int offsetInBlock = lastOffsetInBlock;
      if (offsetInBlock + Long.BYTES > segment.byteSize()) {
        long savedPos = getFilePointer();
        try {
          seek(pos);
          return readLong();
        } finally {
          seek(savedPos);
        }
      }
      return segment.get(LAYOUT_LE_LONG, offsetInBlock);
    } catch (IndexOutOfBoundsException ioobe) {
      throw handlePositionalIOOBE(ioobe, "read", pos);
    } catch (NullPointerException | IllegalStateException e) {
      throw alreadyClosed(e);
    }
  }

  @Override
  public final long length() {
    return length;
  }

  @Override
  public final BufferPoolIndexInput clone() {
    final BufferPoolIndexInput clone = buildSlice((String) null, 0L, this.length);
    try {
      clone.seek(getFilePointer());
    } catch (IOException ioe) {
      throw new AssertionError(ioe);
    }
    return clone;
  }

  @Override
  public final BufferPoolIndexInput slice(String sliceDescription, long offset, long length)
      throws IOException {
    if (offset < 0 || length < 0 || offset + length > this.length) {
      throw new IllegalArgumentException(
          "slice() "
              + sliceDescription
              + " out of bounds: offset="
              + offset
              + ",length="
              + length
              + ",fileLength="
              + this.length
              + ": "
              + this);
    }
    var slice = buildSlice(sliceDescription, offset, length);
    slice.seek(0L);
    return slice;
  }

  BufferPoolIndexInput buildSlice(String sliceDescription, long sliceOffset, long length) {
    ensureOpen();
    final long sliceAbsoluteBaseOffset = this.absoluteBaseOffset + sliceOffset;
    final String newResourceDescription = getFullSliceDescription(sliceDescription);

    BufferPoolIndexInput slice =
        new BufferPoolIndexInput(
            newResourceDescription,
            path,
            sliceAbsoluteBaseOffset,
            length,
            blockCache,
            blockSize,
            true);
    try {
      slice.seek(0L);
    } catch (IOException ioe) {
      throw new AssertionError(ioe);
    }
    return slice;
  }

  /**
   * No-op: the plugin routes this to the read-ahead worker; the benchmark keeps IO strictly
   * demand-driven so per-query block-load counts are deterministic.
   */
  @Override
  public boolean prefetch(long offset, long length) throws IOException {
    ensureOpen();
    return false;
  }

  @Override
  public final void close() throws IOException {
    if (!isOpen) {
      return;
    }
    isOpen = false;

    // Release current block reference — GC handles cleanup
    currentBlock = null;
    currentBlockOffset = -1;
    currentBlockEnd = 0L;
    currentSegment = null;
    currentBlockStart = 0L;
    currentBlockStartRelative = 0L;
    // Slices share the cache; the owning directory closes it.
  }
}
