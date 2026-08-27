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

import java.util.concurrent.TimeUnit;

/**
 * Generic pool interface for managing reusable resources. Backported as-is from
 * JunoStorageEncryptionPlugin {@code org.opensearch.index.store.pool.Pool} (encryption-free
 * standalone).
 *
 * @param <T> the type of pooled resource
 */
public interface Pool<T> {

  /**
   * Attempts to acquire a resource from the pool within the specified timeout.
   *
   * @throws Exception if acquisition fails due to timeout, pool closure, or allocation errors
   */
  T tryAcquire(long timeout, TimeUnit unit) throws Exception;

  /** Returns a resource to the pool for reuse. (No-op in the GC-managed implementation.) */
  void release(T pooled);

  /** Total memory capacity of the pool in bytes. */
  long totalMemory();

  /** Available memory in the pool in bytes. */
  long availableMemory();

  /** Size of each pooled segment in bytes. */
  int pooledSegmentSize();

  /** Whether the pool is under memory pressure. */
  boolean isUnderPressure();

  /** Closes the pool. */
  void close();

  /** Whether the pool has been closed. */
  boolean isClosed();
}
