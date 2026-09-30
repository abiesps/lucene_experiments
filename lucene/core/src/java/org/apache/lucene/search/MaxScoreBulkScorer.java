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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.MathUtil;

final class MaxScoreBulkScorer extends BulkScorer {

  static final int INNER_WINDOW_SIZE = 1 << 12;

  private final int maxDoc;
  // All scorers, sorted by increasing max score.
  final DisiWrapper[] allScorers;
  private final DisiWrapper[] scratch;
  // These are the last scorers from `allScorers` that are "essential", ie. required for a match to
  // have a competitive score.
  private final DisiPriorityQueue essentialQueue;
  // Index of the first essential scorer, ie. essentialQueue contains all scorers from
  // allScorers[firstEssentialScorer:]. All scorers below this index are non-essential.
  int firstEssentialScorer;
  // Index of the first scorer that is required, this scorer and all following scorers are required
  // for a document to match.
  int firstRequiredScorer;
  // The minimum value of minCompetitiveScore that would produce a more favorable partitioning.
  float nextMinCompetitiveScore;
  private final long cost;
  final SimpleScorable scorable = new SimpleScorable();
  final double[] maxScoreSums;
  private final DisiWrapper filter;

  private final FixedBitSet windowMatches = new FixedBitSet(INNER_WINDOW_SIZE);
  private final double[] windowScores = new double[INNER_WINDOW_SIZE];
  private FixedBitSet filterMatches = null;

  private final DocAndFloatFeatureBuffer docAndScoreBuffer = new DocAndFloatFeatureBuffer();
  private final DocAndScoreAccBuffer docAndScoreAccBuffer;

  MaxScoreBulkScorer(int maxDoc, List<Scorer> scorers, Scorer filter) throws IOException {
    this.maxDoc = maxDoc;
    this.filter = filter == null ? null : new DisiWrapper(filter, false);
    allScorers = new DisiWrapper[scorers.size()];
    scratch = new DisiWrapper[allScorers.length];
    int i = 0;
    long cost = 0;
    for (Scorer scorer : scorers) {
      DisiWrapper w = new DisiWrapper(scorer, true);
      cost += w.cost;
      allScorers[i++] = w;
    }
    this.cost = cost;
    setUpPrefetch(scorers);
    essentialQueue = DisiPriorityQueue.ofMaxSize(allScorers.length);
    maxScoreSums = new double[allScorers.length];
    docAndScoreAccBuffer = new DocAndScoreAccBuffer();
    docAndScoreAccBuffer.growNoCopy(INNER_WINDOW_SIZE);

    if (this.filter != null && this.filter.twoPhaseView == null && maxDoc >= INNER_WINDOW_SIZE) {
      long minScorerCost = allScorers[0].cost;
      for (int j = 1; j < allScorers.length; j++) {
        minScorerCost = Math.min(minScorerCost, allScorers[j].cost);
      }
      // Use the bitset filter path if either:
      //  - the sparsest disjunction scorer is denser than the filter, OR
      //  - there are many scorers and their combined cost is denser than the filter, so the
      //    candidate stream is dense enough to favor bulk bit-set gating over per-candidate
      //    filter advance()
      if (minScorerCost >= this.filter.cost
          || (allScorers.length > 4 && this.cost >= this.filter.cost)) {
        this.filterMatches = new FixedBitSet(INNER_WINDOW_SIZE);
      }
    }
  }

  // ---- experimental top-k prefetch, see TopKPrefetch ----

  private boolean prefetchOn;
  private int prefetchDocsAhead; // norms look-ahead in doc IDs, 0 = no norms prefetch
  private long postingsBytesAhead; // postings look-ahead per clause, 0 = no postings prefetch
  private TermScorer[] postingsScorers = new TermScorer[0];
  private int[] postingsCallAgain = new int[0];
  private long prefetchNodeBytes;
  private boolean prefetchFilter;
  // one scorer per field whose norms are requested (clauses on the same field share norms)
  private TermScorer[] normsScorers = new TermScorer[0];
  // scorers whose planning impacts bound their scores; if a clause cannot be bounded, the filter is
  // off for this query
  private TermScorer[] planScorers = new TermScorer[0];
  private int planDoc; // start of the next window that is not planned yet
  // counters, for tests and diagnostics
  long plannedWindows;
  long eligibleWindows;

  private void setUpPrefetch(List<Scorer> scorers) {
    prefetchDocsAhead = TopKPrefetch.getNormsDocsAhead();
    prefetchNodeBytes = TopKPrefetch.getNodeBytes();
    postingsBytesAhead = (long) TopKPrefetch.getDocNodesAhead() * prefetchNodeBytes;
    if (prefetchDocsAhead <= 0 && postingsBytesAhead <= 0) {
      return;
    }
    final List<TermScorer> postings = new ArrayList<>();
    for (Scorer scorer : scorers) {
      if (scorer instanceof TermScorer ts && ts.planField() != null) {
        postings.add(ts);
      }
    }
    if (postingsBytesAhead > 0) {
      postingsScorers = postings.toArray(new TermScorer[0]);
      postingsCallAgain = new int[postingsScorers.length];
      Arrays.fill(postingsCallAgain, -1);
    }
    prefetchOn = true;
    if (prefetchDocsAhead <= 0) {
      return;
    }
    final List<TermScorer> norms = new ArrayList<>();
    final List<TermScorer> plan = new ArrayList<>();
    final List<String> fields = new ArrayList<>();
    boolean canFilter = TopKPrefetch.isFilter();
    for (Scorer scorer : scorers) {
      if (scorer instanceof TermScorer ts && ts.planField() != null) {
        if (fields.contains(ts.planField()) == false) {
          fields.add(ts.planField());
          norms.add(ts);
        }
        if (ts.canPlanScores()) {
          plan.add(ts);
        } else {
          canFilter = false;
        }
      } else {
        canFilter = false; // a clause whose scores cannot be bounded ahead
      }
    }
    if (norms.isEmpty()) {
      prefetchDocsAhead = 0;
      return;
    }
    normsScorers = norms.toArray(new TermScorer[0]);
    planScorers = plan.toArray(new TermScorer[0]);
    prefetchFilter = canFilter;
  }

  /**
   * Requests the norms of every eligible doc range in {@code [cur, cur + docsAhead)} that is not
   * planned yet. With the filter, a range ends where the first of the clauses' current level-0
   * impact blocks ends (at most {@link #INNER_WINDOW_SIZE} docs), so its bound is as tight as the
   * one this scorer prunes with; it is eligible if the sum of the clauses' max scores over it can
   * reach the current minimum competitive score. Without the filter, ranges are windows of {@link
   * #INNER_WINDOW_SIZE} docs and all are eligible.
   */
  private void planPrefetch(int cur) throws IOException {
    if (postingsBytesAhead > 0) {
      planPostings(cur);
    }
    if (prefetchDocsAhead > 0) {
      planNorms(cur);
    }
  }

  /**
   * Keeps each clause's postings requested ahead in whole nodes. A clause's call-again doc is where
   * it starts reading its next node; it is asked again as soon as that doc is inside the window
   * about to be scored, as in BooleanScorer's aligned mode.
   */
  private void planPostings(int cur) throws IOException {
    final int windowEnd = (int) Math.min((long) cur + INNER_WINDOW_SIZE, Integer.MAX_VALUE);
    for (int i = 0; i < postingsScorers.length; i++) {
      while (postingsCallAgain[i] < windowEnd) {
        final int from = Math.max(cur, postingsCallAgain[i]);
        final int next = postingsScorers[i].prefetchPostingsAhead(from, postingsBytesAhead);
        if (next <= from) {
          postingsCallAgain[i] = DocIdSetIterator.NO_MORE_DOCS; // no progress: stop asking
          break;
        }
        postingsCallAgain[i] = next;
      }
    }
  }

  private void planNorms(int cur) throws IOException {
    final int limit = (int) Math.min((long) cur + prefetchDocsAhead, maxDoc);
    if (planDoc < cur) {
      planDoc = cur; // docs behind the scorer are done
    }
    while (planDoc < limit) {
      int end = (int) Math.min((long) planDoc + INNER_WINDOW_SIZE, maxDoc);
      boolean eligible = true;
      if (prefetchFilter) {
        for (TermScorer ts : planScorers) {
          final int upTo = ts.planAdvanceShallow(planDoc);
          if (upTo != DocIdSetIterator.NO_MORE_DOCS) {
            end = Math.min(end, upTo + 1);
          }
        }
        eligible = rangeCanCompete(end - 1);
      }
      plannedWindows++;
      if (eligible) {
        eligibleWindows++;
        for (TermScorer ts : normsScorers) {
          ts.prefetchNorms(planDoc, end, prefetchNodeBytes);
        }
      }
      planDoc = end;
    }
  }

  private boolean rangeCanCompete(int upTo) throws IOException {
    final float minCompetitiveScore = scorable.minCompetitiveScore;
    if (minCompetitiveScore <= 0) {
      return true;
    }
    double sum = 0;
    for (TermScorer ts : planScorers) {
      sum += ts.planMaxScore(upTo);
    }
    return (float) MathUtil.sumUpperBound(sum, planScorers.length) >= minCompetitiveScore;
  }

  // Number of outer windows that have been evaluated
  private int numOuterWindows;
  // Number of candidate matches so far
  private int numCandidates;
  // Minimum window size. See #computeOuterWindowMax where we have heuristics that adjust the
  // minimum window size based on the average number of candidate matches per outer window, to keep
  // the per-window overhead under control.
  private int minWindowSize = 1;

  @Override
  public int score(LeafCollector collector, Bits acceptDocs, int min, int max) throws IOException {
    collector.setScorer(scorable);

    // This scorer computes outer windows based on impacts that are stored in the index. These outer
    // windows should be small enough to provide good upper bounds of scores, and big enough to make
    // sure we spend more time collecting docs than recomputing windows.
    // Then within these outer windows, it creates inner windows of size WINDOW_SIZE that help
    // collect matches into a bitset and save the overhead of rebalancing the priority queue on
    // every match.
    int outerWindowMin = min;
    outer:
    while (outerWindowMin < max) {
      if (prefetchOn) {
        planPrefetch(outerWindowMin);
      }
      int outerWindowMax = computeOuterWindowMax(outerWindowMin);
      outerWindowMax = Math.min(outerWindowMax, max);

      while (true) {
        updateMaxWindowScores(outerWindowMin, outerWindowMax);
        if (partitionScorers() == false) {
          // No matches in this window
          outerWindowMin = outerWindowMax;
          continue outer;
        }

        // There is a dependency between windows and maximum scores, as we compute windows based on
        // maximum scores and maximum scores based on windows.
        // So the approach consists of starting by computing a window based on the set of essential
        // scorers from the _previous_ window and then iteratively recompute maximum scores and
        // windows as long as the window size decreases.
        // In general the set of essential scorers is rather stable over time so this would exit
        // after a single iteration, but there is a change that some scorers got swapped between the
        // set of essential and non-essential scorers, in which case there may be multiple
        // iterations of this loop.

        int newOuterWindowMax = computeOuterWindowMax(outerWindowMin);
        if (newOuterWindowMax >= outerWindowMax) {
          break;
        }
        outerWindowMax = newOuterWindowMax;
      }

      if (prefetchDocsAhead > 0) {
        // this outer window is scored now: its norms are read whatever the look-ahead decided
        for (TermScorer ts : normsScorers) {
          ts.prefetchNorms(outerWindowMin, outerWindowMax, prefetchNodeBytes);
        }
      }

      DisiWrapper top = essentialQueue.top();
      while (top.doc < outerWindowMin) {
        top.doc = top.iterator.advance(outerWindowMin);
        top = essentialQueue.updateTop();
      }

      while (top.doc < outerWindowMax) {
        if (prefetchOn) {
          planPrefetch(top.doc);
        }
        scoreInnerWindow(collector, acceptDocs, outerWindowMax, filter);
        top = essentialQueue.top();
        if (scorable.minCompetitiveScore >= nextMinCompetitiveScore) {
          // The minimum competitive score increased substantially, so we can now partition scorers
          // in a more favorable way.
          break;
        }
      }

      outerWindowMin = Math.min(top.doc, outerWindowMax);
      ++numOuterWindows;
    }

    return nextCandidate(max);
  }

  private void scoreInnerWindow(
      LeafCollector collector, Bits acceptDocs, int max, DisiWrapper filter) throws IOException {
    if (filter != null) {
      scoreInnerWindowWithFilter(collector, acceptDocs, max, filter);
    } else {
      DisiWrapper top = essentialQueue.top();
      DisiWrapper top2 = essentialQueue.top2();
      if (top2 == null) {
        scoreInnerWindowSingleEssentialClause(collector, acceptDocs, max);
      } else if (top2.doc - INNER_WINDOW_SIZE / 2 >= top.doc) {
        // The first half of the window would match a single clause. Let's collect this single
        // clause until the next doc ID of the next clause.
        scoreInnerWindowSingleEssentialClause(collector, acceptDocs, Math.min(max, top2.doc));
      } else {
        scoreInnerWindowMultipleEssentialClauses(collector, acceptDocs, max);
      }
    }
  }

  private void scoreInnerWindowWithFilter(
      LeafCollector collector, Bits acceptDocs, int max, DisiWrapper filter) throws IOException {
    DisiWrapper top = essentialQueue.top();
    assert top.doc < max;
    while (top.doc < filter.doc) {
      // Must use the iterator as `top` might be a two-phase iterator
      top.doc = top.iterator.advance(filter.doc);
      top = essentialQueue.updateTop();
    }

    if (top.doc >= max) {
      return;
    }

    // Only score an inner window, after that we'll check if the min competitive score has increased
    // enough for a more favorable partitioning to be used.
    int innerWindowMin = top.doc;
    int innerWindowMax = MathUtil.unsignedMin(max, innerWindowMin + INNER_WINDOW_SIZE);

    docAndScoreAccBuffer.size = 0;
    if (filterMatches == null) {
      fillScoreBufferViaLeapFrog(top, acceptDocs, innerWindowMax);
    } else {
      fillScoreBufferViaBitSet(top, acceptDocs, innerWindowMax);
    }

    scoreNonEssentialClauses(collector, docAndScoreAccBuffer, firstEssentialScorer);
  }

  private void fillScoreBufferViaBitSet(DisiWrapper top, Bits acceptDocs, int innerWindowMax)
      throws IOException {
    filterMatches.clear();
    int innerWindowMin = top.doc;
    if (filter.doc < innerWindowMax) {
      if (filter.doc < innerWindowMin) {
        filter.doc = filter.approximation.advance(innerWindowMin);
      }
      if (filter.doc < innerWindowMax) {
        filter.approximation.intoBitSet(innerWindowMax, filterMatches, innerWindowMin);
        filter.doc = filter.approximation.docID();
      }
    }
    if (acceptDocs != null) {
      acceptDocs.applyMask(filterMatches, innerWindowMin);
    }

    int innerWindowSize = innerWindowMax - innerWindowMin;
    // Collect matches of essential clauses into a bitset, checking filter via bitset lookup
    collectEssentialScoresIntoWindow(top, innerWindowMax, innerWindowMin, null, filterMatches);
    flushWindowToDocAndScoreAccBuffer(innerWindowMin, innerWindowSize);
  }

  private void fillScoreBufferViaLeapFrog(DisiWrapper top, Bits acceptDocs, int innerWindowMax)
      throws IOException {
    while (top.doc < innerWindowMax) {
      assert filter.doc <= top.doc; // invariant
      if (filter.doc < top.doc) {
        filter.doc = filter.approximation.advance(top.doc);
      }

      if (filter.doc != top.doc) {
        do {
          top.doc = top.iterator.advance(filter.doc);
          top = essentialQueue.updateTop();
        } while (top.doc < filter.doc);
      } else {
        int doc = top.doc;
        boolean match =
            (acceptDocs == null || acceptDocs.get(doc))
                && (filter.twoPhaseView == null || filter.twoPhaseView.matches());
        double score = 0;
        do {
          if (match) {
            score += top.scorer.score();
          }
          top.doc = top.iterator.nextDoc();
          top = essentialQueue.updateTop();
        } while (top.doc == doc);

        if (match) {
          docAndScoreAccBuffer.grow(docAndScoreAccBuffer.size + 1);
          docAndScoreAccBuffer.docs[docAndScoreAccBuffer.size] = doc;
          docAndScoreAccBuffer.scores[docAndScoreAccBuffer.size] = score;
          docAndScoreAccBuffer.size++;
        }
      }
    }
  }

  /**
   * Collect matches of essential clauses into {@link #windowMatches} and {@link #windowScores}. The
   * caller is responsible for populating {@link #docAndScoreAccBuffer} from the window afterwards.
   *
   * @param acceptDocs docs to accept, passed to {@link Scorer#nextDocsAndScores}
   * @param filterMatches if non-null, only docs whose corresponding bit is set in this bitset will
   *     be collected; if null, all docs are collected
   */
  private void collectEssentialScoresIntoWindow(
      DisiWrapper top,
      int innerWindowMax,
      int innerWindowMin,
      Bits acceptDocs,
      FixedBitSet filterMatches)
      throws IOException {
    do {
      for (top.scorer.nextDocsAndScores(innerWindowMax, acceptDocs, docAndScoreBuffer);
          docAndScoreBuffer.size > 0;
          top.scorer.nextDocsAndScores(innerWindowMax, acceptDocs, docAndScoreBuffer)) {
        for (int index = 0; index < docAndScoreBuffer.size; ++index) {
          final int doc = docAndScoreBuffer.docs[index];
          if (filterMatches != null && filterMatches.get(doc - innerWindowMin) == false) {
            continue;
          }
          final float score = docAndScoreBuffer.features[index];
          final int i = doc - innerWindowMin;
          windowMatches.set(i);
          windowScores[i] += score;
        }
      }

      top.doc = top.iterator.docID();
      top = essentialQueue.updateTop();
    } while (top.doc < innerWindowMax);
  }

  /** Flush {@link #windowMatches} and {@link #windowScores} into {@link #docAndScoreAccBuffer}. */
  private void flushWindowToDocAndScoreAccBuffer(int innerWindowMin, int innerWindowSize)
      throws IOException {
    docAndScoreAccBuffer.size = 0;
    windowMatches.forEach(
        0,
        innerWindowSize,
        0,
        index -> {
          docAndScoreAccBuffer.docs[docAndScoreAccBuffer.size] = innerWindowMin + index;
          docAndScoreAccBuffer.scores[docAndScoreAccBuffer.size] = windowScores[index];
          docAndScoreAccBuffer.size++;
          windowScores[index] = 0d;
        });
    windowMatches.clear(0, innerWindowSize);
  }

  private void scoreInnerWindowSingleEssentialClause(
      LeafCollector collector, Bits acceptDocs, int upTo) throws IOException {
    DisiWrapper top = essentialQueue.top();

    // single essential clause in this window, we can iterate it directly and skip the bitset.
    // this is a common case for 2-clauses queries
    for (top.scorer.nextDocsAndScores(upTo, acceptDocs, docAndScoreBuffer);
        docAndScoreBuffer.size > 0;
        top.scorer.nextDocsAndScores(upTo, acceptDocs, docAndScoreBuffer)) {

      docAndScoreAccBuffer.copyFrom(docAndScoreBuffer);
      scoreNonEssentialClauses(collector, docAndScoreAccBuffer, firstEssentialScorer);
    }

    top.doc = top.iterator.docID();
    essentialQueue.updateTop();
  }

  private void scoreInnerWindowMultipleEssentialClauses(
      LeafCollector collector, Bits acceptDocs, int max) throws IOException {
    DisiWrapper top = essentialQueue.top();

    int innerWindowMin = top.doc;
    int innerWindowMax = MathUtil.unsignedMin(max, innerWindowMin + INNER_WINDOW_SIZE);
    int innerWindowSize = innerWindowMax - innerWindowMin;

    // Collect matches of essential clauses into a bitset
    collectEssentialScoresIntoWindow(top, innerWindowMax, innerWindowMin, acceptDocs, null);
    flushWindowToDocAndScoreAccBuffer(innerWindowMin, innerWindowSize);

    scoreNonEssentialClauses(collector, docAndScoreAccBuffer, firstEssentialScorer);
  }

  private int computeOuterWindowMax(int windowMin) throws IOException {
    // Only use essential scorers to compute the window's max doc ID, in order to avoid constantly
    // recomputing max scores over small windows
    final int firstWindowLead = Math.min(firstEssentialScorer, allScorers.length - 1);
    int windowMax = DocIdSetIterator.NO_MORE_DOCS;
    for (int i = firstWindowLead; i < allScorers.length; ++i) {
      final DisiWrapper scorer = allScorers[i];
      if (filter == null || scorer.cost >= filter.cost) {
        final int upTo = scorer.scorer.advanceShallow(Math.max(scorer.doc, windowMin));
        windowMax = MathUtil.unsignedMin(windowMax, upTo + 1); // upTo is inclusive
      }
    }

    if (allScorers.length - firstWindowLead > 1) {
      // The more clauses we consider to compute outer windows, the higher chances that one of these
      // clauses has a block boundary in the next few doc IDs. This situation can result in more
      // time spent computing maximum scores per outer window than evaluating hits. To avoid such
      // situations, we target at least 32 candidate matches per clause per outer window on average,
      // to make sure we amortize the cost of computing maximum scores.
      long threshold = numOuterWindows * 32L * allScorers.length;
      if (numCandidates < threshold) {
        minWindowSize = Math.min(minWindowSize << 1, INNER_WINDOW_SIZE);
      } else {
        minWindowSize = 1;
      }

      int minWindowMax = MathUtil.unsignedMin(Integer.MAX_VALUE, windowMin + minWindowSize);
      windowMax = Math.max(windowMax, minWindowMax);
    }

    return windowMax;
  }

  void updateMaxWindowScores(int windowMin, int windowMax) throws IOException {
    for (DisiWrapper scorer : allScorers) {
      if (scorer.doc < windowMax) {
        if (scorer.doc < windowMin) {
          // Make sure to advance shallow if necessary to get as good score upper bounds as
          // possible.
          scorer.scorer.advanceShallow(windowMin);
        }
        scorer.maxWindowScore = scorer.scorer.getMaxScore(windowMax - 1);
      } else {
        // This scorer has no documents in the considered window.
        scorer.maxWindowScore = 0;
      }
    }
  }

  private void scoreNonEssentialClauses(
      LeafCollector collector, DocAndScoreAccBuffer buffer, int numNonEssentialClauses)
      throws IOException {
    numCandidates += buffer.size;

    for (int i = numNonEssentialClauses - 1; i >= 0; --i) {
      DisiWrapper scorer = allScorers[i];
      assert scorable.minCompetitiveScore > 0
          : "All clauses are essential if minCompetitiveScore is equal to zero";

      ScorerUtil.filterCompetitiveHits(
          buffer, maxScoreSums[i], scorable.minCompetitiveScore, allScorers.length);

      if (i >= firstRequiredScorer) {
        ScorerUtil.applyRequiredClause(buffer, scorer.iterator, scorer.scorable);
      } else {
        ScorerUtil.applyOptionalClause(buffer, scorer.iterator, scorer.scorable);
      }
      scorer.doc = scorer.iterator.docID();
    }

    for (int i = 0; i < buffer.size; ++i) {
      scorable.score = (float) buffer.scores[i];
      collector.collect(buffer.docs[i]);
    }
  }

  boolean partitionScorers() {
    // Partitioning scorers is an optimization problem: the optimal set of non-essential scorers is
    // the subset of scorers whose sum of max window scores is less than the minimum competitive
    // score that maximizes the sum of costs.
    // Computing the optimal solution to this problem would take O(2^num_clauses). As a first
    // approximation, we take the first scorers sorted by max_window_score / cost whose sum of max
    // scores is less than the minimum competitive scores. In the common case, maximum scores are
    // inversely correlated with document frequency so this is the same as only sorting by maximum
    // score, as described in the MAXSCORE paper and gives the optimal solution. However, this can
    // make a difference when using custom scores (like FuzzyQuery), high query-time boosts, or
    // scoring based on wacky weights.
    System.arraycopy(allScorers, 0, scratch, 0, allScorers.length);
    // Do not use Comparator#comparingDouble below, it might cause unnecessary allocations
    Arrays.sort(
        scratch,
        (scorer1, scorer2) -> {
          return Double.compare(
              (double) scorer1.maxWindowScore / Math.max(1L, scorer1.cost),
              (double) scorer2.maxWindowScore / Math.max(1L, scorer2.cost));
        });
    double maxScoreSum = 0;
    firstEssentialScorer = 0;
    nextMinCompetitiveScore = Float.POSITIVE_INFINITY;
    for (int i = 0; i < allScorers.length; ++i) {
      final DisiWrapper w = scratch[i];
      double newMaxScoreSum = maxScoreSum + w.maxWindowScore;
      float maxScoreSumFloat =
          (float) MathUtil.sumUpperBound(newMaxScoreSum, firstEssentialScorer + 1);
      if (maxScoreSumFloat < scorable.minCompetitiveScore) {
        maxScoreSum = newMaxScoreSum;
        allScorers[firstEssentialScorer] = w;
        maxScoreSums[firstEssentialScorer] = maxScoreSum;
        firstEssentialScorer++;
      } else {
        allScorers[allScorers.length - 1 - (i - firstEssentialScorer)] = w;
        nextMinCompetitiveScore = Math.min(maxScoreSumFloat, nextMinCompetitiveScore);
      }
    }

    firstRequiredScorer = allScorers.length;

    if (firstEssentialScorer == allScorers.length) {
      return false;
    }

    essentialQueue.clear();
    for (int i = firstEssentialScorer; i < allScorers.length; ++i) {
      essentialQueue.add(allScorers[i]);
    }

    if (firstEssentialScorer == allScorers.length - 1) { // single essential clause
      // If there is a single essential clause and matching it plus all non-essential clauses but
      // the best one is not enough to yield a competitive match, the we know that hits must match
      // both the essential clause and the best non-essential clause. Here are some examples when
      // this optimization would kick in:
      //   `quick fox`  when maxscore(quick) = 1, maxscore(fox) = 1, minCompetitiveScore = 1.5
      //   `the quick fox` when maxscore (the) = 0.1, maxscore(quick) = 1, maxscore(fox) = 1,
      //       minCompetitiveScore = 1.5
      firstRequiredScorer = allScorers.length - 1;
      double maxRequiredScore = allScorers[firstEssentialScorer].maxWindowScore;

      while (firstRequiredScorer > 0) {
        double maxPossibleScoreWithoutPreviousClause = maxRequiredScore;
        if (firstRequiredScorer > 1) {
          maxPossibleScoreWithoutPreviousClause += maxScoreSums[firstRequiredScorer - 2];
        }
        if ((float) maxPossibleScoreWithoutPreviousClause >= scorable.minCompetitiveScore) {
          break;
        }
        // The sum of maximum scores ignoring the previous clause is less than the minimum
        // competitive
        --firstRequiredScorer;
        maxRequiredScore += allScorers[firstRequiredScorer].maxWindowScore;
      }
    }

    return true;
  }

  /** Return the next candidate on or after {@code rangeEnd}. */
  private int nextCandidate(int rangeEnd) {
    if (rangeEnd >= maxDoc) {
      return DocIdSetIterator.NO_MORE_DOCS;
    }

    int next = DocIdSetIterator.NO_MORE_DOCS;
    for (DisiWrapper scorer : allScorers) {
      if (scorer.doc < rangeEnd) {
        return rangeEnd;
      } else {
        next = Math.min(next, scorer.doc);
      }
    }
    return next;
  }

  @Override
  public long cost() {
    return cost;
  }
}
