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
package org.apache.lucene.search;

/**
 * Experiment switches for batch collection. All are off by default (stock behavior). Settings are
 * read when a bulk scorer is created.
 *
 * @lucene.experimental
 */
public final class CollectExperiments {
  private static volatile boolean cacheRunEnd;

  private CollectExperiments() {}

  /**
   * Sets whether {@link DenseConjunctionBulkScorer} keeps each clause's last {@link
   * DocIdSetIterator#docIDRunEnd()} while the clause is inside that run, instead of recomputing it
   * for every window. Recomputing costs O(run length) for a bit set, so a clause that matches most
   * of the segment (a time range over a whole index) pays O(maxDoc / 64) per 4,096-doc window.
   */
  public static void setCacheRunEnd(boolean on) {
    cacheRunEnd = on;
  }

  /** Returns whether run ends are cached. */
  public static boolean isCacheRunEnd() {
    return cacheRunEnd;
  }
}
