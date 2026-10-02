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
package org.apache.lucene.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable map from {@link String} keys to non-null values, built once and then only read.
 *
 * <p>It is designed for lookups on hot paths, such as finding the producer of a field in every
 * segment of every query, with few dependent memory loads per lookup:
 *
 * <ul>
 *   <li>Open addressing with linear probing over one flat {@code long[]}. Each slot holds the mixed
 *       hash of its key (upper 32 bits) and the index of the entry plus one (lower 32 bits), so a
 *       slot whose hash does not match is rejected without loading the key.
 *   <li>Keys and values live in two parallel arrays, sorted by key. {@link #sortedKeys()} returns
 *       them in {@link String#compareTo} order without sorting at read time.
 *   <li>The probe length is bounded: the table is built at a load factor of at most 0.5, and it is
 *       rebuilt with a new hash seed (and, if needed, a larger table) while the longest probe is
 *       above {@value #MAX_PROBE}. Lookups never scan more than the longest probe seen at build
 *       time. Keys with identical {@link String#hashCode()} cannot be separated by a new seed, so
 *       many such keys still lengthen the probe for those keys (lookups stay correct).
 * </ul>
 *
 * <p>Instances are immutable and safe to share between threads.
 *
 * @lucene.internal
 */
public final class FrozenStringMap<V> {

  /** Longest probe sequence accepted before the table is rebuilt with a new seed. */
  static final int MAX_PROBE = 8;

  private static final int MAX_BUILD_ATTEMPTS = 8;
  private static final FrozenStringMap<?> EMPTY =
      new FrozenStringMap<>(new String[0], new Object[0], new long[2], 0x9E3779B9, 0);

  private final long[] slots;
  private final int mask;
  private final int seed;
  private final int maxProbe;
  private final String[] keys;
  private final Object[] values;
  private final List<String> sortedKeys;

  private FrozenStringMap(String[] keys, Object[] values, long[] slots, int seed, int maxProbe) {
    this.keys = keys;
    this.values = values;
    this.slots = slots;
    this.mask = slots.length - 1;
    this.seed = seed;
    this.maxProbe = maxProbe;
    this.sortedKeys = Collections.unmodifiableList(Arrays.asList(keys));
  }

  /** Returns an empty map. */
  @SuppressWarnings("unchecked")
  public static <V> FrozenStringMap<V> empty() {
    return (FrozenStringMap<V>) EMPTY;
  }

  /** Builds a map with the entries of the given map. Keys and values must not be null. */
  public static <V> FrozenStringMap<V> of(Map<String, ? extends V> map) {
    final String[] keys = map.keySet().toArray(new String[0]);
    if (keys.length == 0) {
      return empty();
    }
    Arrays.sort(keys);
    final Object[] values = new Object[keys.length];
    for (int i = 0; i < keys.length; i++) {
      values[i] = Objects.requireNonNull(map.get(keys[i]), "null value");
    }
    int size = tableSize(keys.length);
    int seed = 0x9E3779B9;
    for (int attempt = 1; ; attempt++) {
      final long[] slots = new long[size];
      final int tableMask = size - 1;
      int longest = 0;
      for (int i = 0; i < keys.length; i++) {
        final int h = mix(keys[i].hashCode(), seed);
        int slot = h & tableMask;
        int probe = 1;
        while (slots[slot] != 0) {
          slot = (slot + 1) & tableMask;
          probe++;
        }
        slots[slot] = ((long) h << 32) | ((i + 1) & 0xFFFFFFFFL);
        longest = Math.max(longest, probe);
      }
      if (longest <= MAX_PROBE || attempt >= MAX_BUILD_ATTEMPTS) {
        // after MAX_BUILD_ATTEMPTS the table is still correct, lookups only probe further
        return new FrozenStringMap<>(keys, values, slots, seed, longest);
      }
      seed = (seed * 31 + 0x632BE5AB) | 1;
      if (attempt % 2 == 0) {
        size <<= 1;
      }
    }
  }

  private static int tableSize(int count) {
    // smallest power of two that keeps the load factor at or below 0.5
    final long wanted = Math.max(2L, 2L * count);
    if (wanted > (1 << 30)) {
      throw new IllegalArgumentException("too many keys: " + count);
    }
    return Integer.highestOneBit((int) wanted - 1) << 1;
  }

  private static int mix(int hash, int seed) {
    final int h = hash * seed;
    return h ^ (h >>> 16);
  }

  /** Returns the value for the given key, or {@code null} if the key is not in the map. */
  @SuppressWarnings("unchecked")
  public V get(String key) {
    final int h = mix(key.hashCode(), seed);
    final long[] slots = this.slots;
    final int mask = this.mask;
    int slot = h & mask;
    for (int probe = 0; probe < maxProbe; probe++) {
      final long entry = slots[slot];
      if (entry == 0) {
        return null;
      }
      if ((int) (entry >>> 32) == h) {
        final int index = (int) entry - 1;
        if (key.equals(keys[index])) {
          return (V) values[index];
        }
      }
      slot = (slot + 1) & mask;
    }
    return null;
  }

  /** Returns the number of entries. */
  public int size() {
    return keys.length;
  }

  /** Returns the key at the given position, in sorted order. */
  public String keyAt(int index) {
    return keys[index];
  }

  /** Returns the value at the given position, in the sorted order of the keys. */
  @SuppressWarnings("unchecked")
  public V valueAt(int index) {
    return (V) values[index];
  }

  /** Returns an unmodifiable view of the keys, sorted by {@link String#compareTo}. */
  public List<String> sortedKeys() {
    return sortedKeys;
  }

  /** Returns the longest probe sequence of this table (for tests). */
  int maxProbe() {
    return maxProbe;
  }
}
