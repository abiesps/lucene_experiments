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
package org.apache.lucene.index;

import java.io.IOException;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.CompiledAutomaton;

/**
 * A multi-valued version of {@link SortedDocValues}.
 *
 * <p>Per-Document values in a SortedSetDocValues are deduplicated, dereferenced, and sorted into a
 * dictionary of unique values. A pointer to the dictionary value (ordinal) can be retrieved for
 * each document. Ordinals are dense and in increasing sorted order.
 */
public abstract class SortedSetDocValues extends DocValuesIterator {

  /** Sole constructor. (For invocation by subclass constructors, typically implicit.) */
  protected SortedSetDocValues() {}

  /**
   * Returns the next ordinal for the current document. It is illegal to call this method after
   * {@link #advanceExact(int)} returned {@code false}. It is illegal to call this more than {@link
   * #docValueCount()} times for the currently-positioned doc.
   *
   * @return next ordinal for the document. ordinals are dense, start at 0, then increment by 1 for
   *     the next value in sorted order.
   */
  public abstract long nextOrd() throws IOException;

  /**
   * Retrieves the number of unique ords for the current document. This must always be greater than
   * zero. It is illegal to call this method after {@link #advanceExact(int)} returned {@code
   * false}.
   */
  public abstract int docValueCount();

  /**
   * Retrieves the value for the specified ordinal. The returned {@link BytesRef} may be re-used
   * across calls to lookupOrd so make sure to {@link BytesRef#deepCopyOf(BytesRef) copy it} if you
   * want to keep it around.
   *
   * @param ord ordinal to lookup
   * @see #nextOrd
   */
  public abstract BytesRef lookupOrd(long ord) throws IOException;

  /**
   * Returns the number of unique values.
   *
   * @return number of unique values in this SortedDocValues. This is also equivalent to one plus
   *     the maximum ordinal.
   */
  public abstract long getValueCount();

  /**
   * If {@code key} exists, returns its ordinal, else returns {@code -insertionPoint-1}, like {@code
   * Arrays.binarySearch}.
   *
   * @param key Key to look up
   */
  public long lookupTerm(BytesRef key) throws IOException {
    long low = 0;
    long high = getValueCount() - 1;

    while (low <= high) {
      long mid = (low + high) >>> 1;
      final BytesRef term = lookupOrd(mid);
      int cmp = term.compareTo(key);

      if (cmp < 0) {
        low = mid + 1;
      } else if (cmp > 0) {
        high = mid - 1;
      } else {
        return mid; // key found
      }
    }

    return -(low + 1); // key not found.
  }

  /**
   * Retrieves ordinals for a batch of doc IDs into a flat output array. The {@code docs} array must
   * be sorted in ascending order with no duplicates.
   *
   * <p>The default implementation loops over each doc, calling {@link #advanceExact(int)} and {@link
   * #nextOrd()} for docs that have values, or setting {@code counts[i] = 0} for docs without
   * values. Subclasses may override this method to prefetch data and improve I/O efficiency.
   *
   * @param size number of doc IDs to process
   * @param docs sorted ascending doc IDs (no duplicates)
   * @param ordsOut flat output array for all ordinals across all docs
   * @param counts output array: counts[i] = number of ordinals for docs[i] (0 if no value)
   * @return total number of ordinals written to ordsOut
   */
  public int ordValues(int size, int[] docs, long[] ordsOut, int[] counts) throws IOException {
    int total = 0;
    for (int i = 0; i < size; i++) {
      if (advanceExact(docs[i])) {
        int c = docValueCount();
        counts[i] = c;
        for (int j = 0; j < c; j++) {
          ordsOut[total++] = nextOrd();
        }
      } else {
        counts[i] = 0;
      }
    }
    return total;
  }

  /**
   * Prefetches term dictionary data for a set of ordinals so that subsequent {@link
   * #lookupOrd(long)} calls find the data warm in the cache.
   *
   * <p>The default implementation is a no-op. Subclasses backed by compressed term dictionaries
   * (e.g., LZ4-compressed blocks) may override this to pre-warm the cache for the given ordinals.
   *
   * @param ords array of ordinals to prefetch
   * @param count number of ordinals in the array
   */
  public void prefetchOrdinals(long[] ords, int count) throws IOException {
    // no-op by default
  }

  /**
   * Prefetches the term dictionary block for the given ordinal so that a subsequent
   * {@link #lookupOrd(long)} call finds the data warm in the cache.
   *
   * <p>This is the ordinal-based equivalent of {@link TermsEnum#prepareSeekExact(BytesRef)}:
   * phase 1 issues async IO for the target block, phase 2 ({@link #lookupOrd(long)})
   * reads the data without blocking on IO.
   *
   * <p>The default implementation is a no-op. Subclasses backed by compressed term dictionaries
   * (e.g., LZ4-compressed blocks) may override this.
   *
   * @param ord the ordinal to prefetch
   */
  public void prepareLookupOrd(long ord) throws IOException {
    // no-op by default
  }

  /**
   * Returns a {@link TermsEnum} over the values. The enum supports {@link TermsEnum#ord()} and
   * {@link TermsEnum#seekExact(long)}.
   */
  public TermsEnum termsEnum() throws IOException {
    return new SortedSetDocValuesTermsEnum(this);
  }

  /**
   * Returns a {@link TermsEnum} optimized for sequential full scan with lookahead prefetch.
   * Callers MUST iterate all terms via next() — do not use for random seeks.
   * The default implementation delegates to {@link #termsEnum()}.
   *
   * @lucene.experimental
   */
  public TermsEnum sequentialTermsEnum() throws IOException {
    return termsEnum();
  }

  /**
   * Returns a {@link TermsEnum} over the values, filtered by a {@link CompiledAutomaton} The enum
   * supports {@link TermsEnum#ord()}.
   */
  public TermsEnum intersect(CompiledAutomaton automaton) throws IOException {
    TermsEnum in = termsEnum();
    switch (automaton.type) {
      case NONE:
        return TermsEnum.EMPTY;
      case ALL:
        return in;
      case SINGLE:
        return new SingleTermsEnum(in, automaton.term);
      case NORMAL:
        return new AutomatonTermsEnum(in, automaton);
      default:
        // unreachable
        throw new RuntimeException("unhandled case");
    }
  }
}
