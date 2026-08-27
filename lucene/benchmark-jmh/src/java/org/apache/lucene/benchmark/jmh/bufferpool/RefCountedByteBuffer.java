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
import java.nio.ByteBuffer;

/**
 * A GC-managed wrapper around a direct {@link ByteBuffer}. Backported as-is from
 * JunoStorageEncryptionPlugin {@code org.opensearch.index.store.block.RefCountedByteBuffer}.
 *
 * <p>All lifecycle methods are no-ops. The JVM's GC frees the backing DirectByteBuffer when this
 * object becomes unreachable. No ref counting, no generation tracking, no close flag.
 *
 * <p>Note: the plugin's global-arena {@code MemorySegment.ofAddress().reinterpret()} optimization
 * is a restricted (native-access) API; this standalone backport uses the plugin's feature-flag-off
 * path ({@code MemorySegment.ofBuffer}) so the benchmark runs without {@code
 * --enable-native-access}.
 */
public final class RefCountedByteBuffer {

  private final ByteBuffer buffer;
  private final int length;
  private final MemorySegment segment;

  public RefCountedByteBuffer(ByteBuffer buffer, int length) {
    this.buffer = buffer;
    this.length = length;
    this.segment = MemorySegment.ofBuffer(buffer);
  }

  public ByteBuffer buffer() {
    return buffer;
  }

  public MemorySegment segment() {
    return segment;
  }

  public RefCountedByteBuffer value() {
    return this;
  }

  public int length() {
    return length;
  }

  public boolean tryPin() {
    return true;
  }

  public void unpin() {}

  public void decRef() {}

  public void close() {}
}
