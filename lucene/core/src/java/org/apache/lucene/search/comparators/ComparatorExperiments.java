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
package org.apache.lucene.search.comparators;

/**
 * Experiment switches for the numeric sort comparators. All are off by default (stock behavior).
 * Settings are process-wide and read when a leaf comparator is created.
 *
 * @lucene.experimental
 */
public final class ComparatorExperiments {
  /** Smallest non-zero value accepted by {@link #setSampleDocs(int)}. */
  public static final int MIN_SAMPLE_DOCS = 4096;

  /** Largest value accepted by {@link #setSampleDocs(int)}. */
  public static final int MAX_SAMPLE_DOCS = 16 << 20;

  /** Where the comparator gets competitive doc ranges from. */
  public enum SkipperMode {
    /** Stock: the points index only. */
    OFF,
    /** The doc-values skipper only where the points index cannot be used. */
    FALLBACK,
    /** The doc-values skipper first, the points index only where the skipper cannot help. */
    FIRST
  }

  private static volatile int sampleDocs;
  private static volatile SkipperMode skipperMode = SkipperMode.OFF;

  private ComparatorExperiments() {}

  /**
   * Sets how many docs the comparator samples to estimate the competitive bound before it first
   * updates its competitive iterator. 0 keeps the stock behavior.
   *
   * @throws IllegalArgumentException if {@code docs} is neither 0 nor in 4,096..16,777,216
   */
  public static void setSampleDocs(int docs) {
    if (docs != 0 && (docs < MIN_SAMPLE_DOCS || docs > MAX_SAMPLE_DOCS)) {
      throw new IllegalArgumentException(
          "sample docs must be 0 or in "
              + MIN_SAMPLE_DOCS
              + ".."
              + MAX_SAMPLE_DOCS
              + ", got "
              + docs);
    }
    sampleDocs = docs;
  }

  /** Returns how many docs the comparator samples; 0 means stock. */
  public static int getSampleDocs() {
    return sampleDocs;
  }

  /**
   * Sets where the comparator gets competitive doc ranges from.
   *
   * @throws IllegalArgumentException if {@code mode} is null
   */
  public static void setSkipperMode(SkipperMode mode) {
    if (mode == null) {
      throw new IllegalArgumentException("skipper mode must not be null");
    }
    skipperMode = mode;
  }

  /** Returns where the comparator gets competitive doc ranges from. */
  public static SkipperMode getSkipperMode() {
    return skipperMode;
  }
}
