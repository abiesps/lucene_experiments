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
  private static volatile boolean bulkDecode;
  private static volatile boolean competitiveRunCap;

  private CollectExperiments() {}

  /**
   * Sets whether the competitive iterator of a numeric sort caps the doc ranges it adds per update,
   * so that a run of competitive docs does not pull in value blocks beyond the next bound update.
   */
  public static void setCompetitiveRunCap(boolean on) {
    competitiveRunCap = on;
  }

  /** Returns whether competitive doc ranges are capped. */
  public static boolean isCompetitiveRunCap() {
    return competitiveRunCap;
  }

  /**
   * Sets whether bulk doc-values reads ({@link
   * org.apache.lucene.index.NumericDocValues#longValues(int, int[], long[], long)}) of dense
   * Lucene90 numeric fields decode the packed values between the first and the last requested doc
   * in one pass and pick the requested ones, instead of reading one value per doc. Applies when the
   * docs are dense enough within the span; read on every call.
   */
  public static void setBulkDecode(boolean on) {
    bulkDecode = on;
  }

  /** Returns whether bulk doc-values reads decode spans. */
  public static boolean isBulkDecode() {
    return bulkDecode;
  }

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
