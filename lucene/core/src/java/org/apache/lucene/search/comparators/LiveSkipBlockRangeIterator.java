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

import java.io.IOException;
import org.apache.lucene.index.DocValuesSkipper;
import org.apache.lucene.search.AbstractDocIdSetIterator;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.SkipBlockRangeIterator;
import org.apache.lucene.util.FixedBitSet;

/**
 * K4: a copy of {@link SkipBlockRangeIterator} whose range is the comparator's current competitive
 * range: it reads the builder's {@code minValueAsLong} and {@code maxValueAsLong} each time it
 * classifies a block, so every bottom update is seen at the next block without installing a new
 * iterator. The competitive range only shrinks during a leaf, so a block classified with an older
 * range is a superset of the competitive docs.
 *
 * @lucene.experimental
 */
final class LiveSkipBlockRangeIterator extends AbstractDocIdSetIterator {

  private enum Match {
    YES,
    YES_IF_PRESENT,
    MAYBE
  }

  private final DocValuesSkipper skipper;
  private final NumericComparator<?>.CompetitiveDISIBuilder builder;

  private Match match = Match.MAYBE;

  LiveSkipBlockRangeIterator(
      DocValuesSkipper skipper, NumericComparator<?>.CompetitiveDISIBuilder builder) {
    this.skipper = skipper;
    this.builder = builder;
  }

  @Override
  public int nextDoc() throws IOException {
    return advance(doc + 1);
  }

  @Override
  public int advance(int target) throws IOException {
    if (target <= skipper.maxDocID(0)) {
      // within current block
      if (doc > -1) {
        // already positioned, so we've checked bounds and know that we're in a matching block
        return doc = target;
      }
    } else {
      // Advance to target
      skipper.advance(target);
    }

    // Find the next matching block (could be the current block)
    final long minValue = builder.minValueAsLong;
    final long maxValue = builder.maxValueAsLong;
    skipper.advance(minValue, maxValue);
    int nextDoc = Math.max(target, skipper.minDocID(0));
    if (nextDoc == DocIdSetIterator.NO_MORE_DOCS) {
      this.match = Match.MAYBE;
    } else {
      this.match = classifyBlock(minValue, maxValue);
    }
    return doc = nextDoc;
  }

  private Match classifyBlock(long minValue, long maxValue) {
    if (skipper.minValue(0) >= minValue && skipper.maxValue(0) <= maxValue) {
      if (skipper.maxDocID(0) - skipper.minDocID(0) == skipper.docCount(0) - 1) {
        return Match.YES;
      }
      return Match.YES_IF_PRESENT;
    }
    return Match.MAYBE;
  }

  @Override
  public long cost() {
    return DocIdSetIterator.NO_MORE_DOCS;
  }

  @Override
  public int docIDRunEnd() throws IOException {
    int maxDoc = skipper.maxDocID(0);
    if (match == Match.YES) {
      final long minValue = builder.minValueAsLong;
      final long maxValue = builder.maxValueAsLong;
      int nextLevel = 1;
      while (nextLevel < skipper.numLevels()
          && skipper.minValue(nextLevel) >= minValue
          && skipper.maxValue(nextLevel) <= maxValue
          && skipper.maxDocID(nextLevel) - skipper.minDocID(nextLevel)
              == skipper.docCount(nextLevel) - 1) {
        maxDoc = skipper.maxDocID(nextLevel);
        nextLevel++;
      }
    }
    return maxDoc + 1;
  }

  @Override
  public void intoBitSet(int upTo, FixedBitSet bitSet, int offset) throws IOException {
    while (doc < upTo) {
      int end = Math.min(upTo, docIDRunEnd());
      bitSet.set(doc - offset, end - offset);
      advance(end);
    }
  }
}
