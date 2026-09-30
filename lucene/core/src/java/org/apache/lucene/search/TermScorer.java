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

import java.io.IOException;
import java.util.Arrays;
import org.apache.lucene.index.ImpactsEnum;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SlowImpactsEnum;
import org.apache.lucene.search.similarities.Similarity.BulkSimScorer;
import org.apache.lucene.search.similarities.Similarity.SimScorer;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.LongsRef;

/**
 * Expert: A <code>Scorer</code> for documents matching a <code>Term</code>.
 *
 * @lucene.internal
 */
public final class TermScorer extends Scorer {
  private final PostingsEnum postingsEnum;
  private final DocIdSetIterator iterator;
  private final SimScorer scorer;
  private final BulkSimScorer bulkScorer;
  private final NumericDocValues norms;
  private final ImpactsDISI impactsDisi;
  private final MaxScoreCache maxScoreCache;
  private long[] normValues = LongsRef.EMPTY_LONGS;

  // Experimental top-k prefetch planning (see TopKPrefetch): a second impacts enum on the same
  // term, used only to read upcoming max scores, and the field whose norms the scorer reads.
  private MaxScoreCache planCache;
  private String planField;

  /** Construct a {@link TermScorer} that will iterate all documents. */
  public TermScorer(PostingsEnum postingsEnum, SimScorer scorer, NumericDocValues norms) {
    iterator = this.postingsEnum = postingsEnum;
    ImpactsEnum impactsEnum = new SlowImpactsEnum(postingsEnum);
    maxScoreCache = new MaxScoreCache(impactsEnum, scorer);
    impactsDisi = null;
    this.scorer = scorer;
    this.norms = norms;
    this.bulkScorer = scorer.asBulkSimScorer();
  }

  /**
   * Construct a {@link TermScorer} that will use impacts to skip blocks of non-competitive
   * documents.
   */
  public TermScorer(
      ImpactsEnum impactsEnum,
      SimScorer scorer,
      NumericDocValues norms,
      boolean topLevelScoringClause) {
    postingsEnum = impactsEnum;
    maxScoreCache = new MaxScoreCache(impactsEnum, scorer);
    if (topLevelScoringClause) {
      impactsDisi = new ImpactsDISI(impactsEnum, maxScoreCache);
      iterator = impactsDisi;
    } else {
      impactsDisi = null;
      iterator = impactsEnum;
    }
    this.scorer = scorer;
    this.norms = norms;
    this.bulkScorer = scorer.asBulkSimScorer();
  }

  /** Attaches a planning-only impacts enum (see {@link TopKPrefetch}); never moves the scorer. */
  void setPlanning(String field, ImpactsEnum planningImpacts) {
    this.planField = field;
    this.planCache = planningImpacts == null ? null : new MaxScoreCache(planningImpacts, scorer);
  }

  /** The field of this term, if planning was set up, else null. */
  String planField() {
    return planField;
  }

  /** True if {@link #planMaxScore} can be used. */
  boolean canPlanScores() {
    return planCache != null;
  }

  /**
   * Moves the planning enum's impacts to {@code target} and returns the last doc ID of its level-0
   * block (inclusive). Calls must have non-decreasing targets.
   */
  int planAdvanceShallow(int target) throws IOException {
    return planCache.advanceShallow(target);
  }

  /** Upper bound of the score of any doc up to {@code upTo}, from the planning enum's impacts. */
  float planMaxScore(int upTo) throws IOException {
    return planCache.getMaxScore(upTo);
  }

  /** Prefetch hint on this scorer's postings, see {@link DocIdSetIterator#prefetchAhead}. */
  int prefetchPostingsAhead(int fromDoc, long bytesAhead) throws IOException {
    return postingsEnum.prefetchAhead(fromDoc, bytesAhead);
  }

  /** Requests the norms of docs in {@code [from, to)} in whole nodes; false if not supported. */
  boolean prefetchNorms(int from, int to, long nodeBytes) throws IOException {
    return norms != null && norms.prefetchNodes(from, to, nodeBytes);
  }

  @Override
  public int docID() {
    return postingsEnum.docID();
  }

  /** Returns term frequency in the current document. */
  public final int freq() throws IOException {
    return postingsEnum.freq();
  }

  @Override
  public DocIdSetIterator iterator() {
    return iterator;
  }

  @Override
  public float score() throws IOException {
    var postingsEnum = this.postingsEnum;
    var norms = this.norms;

    long norm = 1L;
    if (norms != null && norms.advanceExact(postingsEnum.docID())) {
      norm = norms.longValue();
    }
    return scorer.score(postingsEnum.freq(), norm);
  }

  @Override
  public float smoothingScore(int docId) throws IOException {
    long norm = 1L;
    if (norms != null && norms.advanceExact(docId)) {
      norm = norms.longValue();
    }
    return scorer.score(0, norm);
  }

  @Override
  public int advanceShallow(int target) throws IOException {
    return maxScoreCache.advanceShallow(target);
  }

  @Override
  public float getMaxScore(int upTo) throws IOException {
    return maxScoreCache.getMaxScore(upTo);
  }

  @Override
  public void setMinCompetitiveScore(float minScore) {
    if (impactsDisi != null) {
      impactsDisi.setMinCompetitiveScore(minScore);
    }
  }

  @Override
  public void nextDocsAndScores(int upTo, Bits liveDocs, DocAndFloatFeatureBuffer buffer)
      throws IOException {
    for (; ; ) {
      if (impactsDisi != null) {
        impactsDisi.ensureCompetitive();
      }

      postingsEnum.nextPostings(upTo, buffer);
      if (liveDocs != null && buffer.size != 0) {
        // An empty return value indicates that there are no more docs before upTo. We may be
        // unlucky, and there are docs left, but all docs from the current batch happen to be marked
        // as deleted. So we need to iterate until we find a batch that has at least one non-deleted
        // doc.
        buffer.apply(liveDocs);
        if (buffer.size == 0) {
          continue;
        }
      }
      break;
    }

    int size = buffer.size;
    if (normValues.length < size) {
      normValues = new long[ArrayUtil.oversize(size, Long.BYTES)];
      if (norms == null) {
        Arrays.fill(normValues, 1L);
      }
    }
    if (norms != null) {
      norms.longValues(size, buffer.docs, normValues, 1L);
    }

    bulkScorer.score(buffer.size, buffer.features, normValues, buffer.features);
  }
}
