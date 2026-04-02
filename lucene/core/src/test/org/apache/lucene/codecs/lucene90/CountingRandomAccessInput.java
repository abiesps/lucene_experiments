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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.apache.lucene.store.RandomAccessInput;

/**
 * A test wrapper around {@link RandomAccessInput} that records all {@code prefetch(offset, length)}
 * calls and all read calls ({@code readByte}, {@code readShort}, {@code readInt}, {@code readLong})
 * with their arguments and a monotonically increasing sequence number for ordering verification.
 *
 * <p>This is used by prefetch behavior tests (Tasks 7.2–7.5) to verify:
 *
 * <ul>
 *   <li>Zero prefetch for constant-value fields (Property 4)
 *   <li>Single contiguous prefetch for dense batches (Property 5)
 *   <li>Multiple per-doc prefetch for sparse batches (Property 6)
 *   <li>Prefetch-before-read ordering
 * </ul>
 *
 * <p>Validates: Requirements 10.1
 */
final class CountingRandomAccessInput implements RandomAccessInput {

  /** A recorded prefetch call with offset, length, and sequence number. */
  record PrefetchCall(long offset, long length, long sequenceNumber) {}

  /** A recorded read call with the position, type of read, and sequence number. */
  record ReadCall(long position, String readType, long sequenceNumber) {}

  private final RandomAccessInput delegate;
  private final List<PrefetchCall> prefetchCalls = new ArrayList<>();
  private final List<ReadCall> readCalls = new ArrayList<>();
  private long sequenceCounter = 0;

  /**
   * Creates a new CountingRandomAccessInput wrapping the given delegate.
   *
   * @param delegate the underlying RandomAccessInput to delegate read operations to
   */
  CountingRandomAccessInput(RandomAccessInput delegate) {
    this.delegate = delegate;
  }

  // ---- Prefetch recording ----

  @Override
  public boolean prefetch(long offset, long length) throws IOException {
    prefetchCalls.add(new PrefetchCall(offset, length, sequenceCounter++));
    return delegate.prefetch(offset, length);
  }

  // ---- Read recording + delegation ----

  @Override
  public long length() {
    return delegate.length();
  }

  @Override
  public byte readByte(long pos) throws IOException {
    readCalls.add(new ReadCall(pos, "readByte", sequenceCounter++));
    return delegate.readByte(pos);
  }

  @Override
  public void readBytes(long pos, byte[] bytes, int offset, int length) throws IOException {
    readCalls.add(new ReadCall(pos, "readBytes", sequenceCounter++));
    delegate.readBytes(pos, bytes, offset, length);
  }

  @Override
  public short readShort(long pos) throws IOException {
    readCalls.add(new ReadCall(pos, "readShort", sequenceCounter++));
    return delegate.readShort(pos);
  }

  @Override
  public int readInt(long pos) throws IOException {
    readCalls.add(new ReadCall(pos, "readInt", sequenceCounter++));
    return delegate.readInt(pos);
  }

  @Override
  public long readLong(long pos) throws IOException {
    readCalls.add(new ReadCall(pos, "readLong", sequenceCounter++));
    return delegate.readLong(pos);
  }

  @Override
  public Optional<Boolean> isLoaded() {
    return delegate.isLoaded();
  }

  // ---- Query methods ----

  /** Returns an unmodifiable list of all recorded prefetch calls, in order. */
  List<PrefetchCall> getPrefetchCalls() {
    return Collections.unmodifiableList(prefetchCalls);
  }

  /** Returns an unmodifiable list of all recorded read calls, in order. */
  List<ReadCall> getReadCalls() {
    return Collections.unmodifiableList(readCalls);
  }

  /** Clears all recorded prefetch and read calls and resets the sequence counter. */
  void reset() {
    prefetchCalls.clear();
    readCalls.clear();
    sequenceCounter = 0;
  }

  /**
   * Returns {@code true} if at least one prefetch call was recorded before the first read call.
   * Returns {@code false} if no prefetch calls were recorded, or if the first read occurred before
   * any prefetch.
   */
  boolean wasPrefetchBeforeFirstRead() {
    if (prefetchCalls.isEmpty()) {
      return false;
    }
    if (readCalls.isEmpty()) {
      // Prefetch was called but no reads — prefetch is trivially "before" reads.
      return true;
    }
    return prefetchCalls.get(0).sequenceNumber() < readCalls.get(0).sequenceNumber();
  }
}
