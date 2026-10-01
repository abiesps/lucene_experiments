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
 * A per-document byte[] with presorted values. This is fundamentally an iterator over the int ord
 * values per document, with random access APIs to resolve an int ord to BytesRef.
 *
 * <p>Per-Document values in a SortedDocValues are deduplicated, dereferenced, and sorted into a
 * dictionary of unique values. A pointer to the dictionary value (ordinal) can be retrieved for
 * each document. Ordinals are dense and in increasing sorted order.
 */
public abstract class SortedDocValues extends DocValuesIterator {

  /** Sole constructor. (For invocation by subclass constructors, typically implicit.) */
  protected SortedDocValues() {}

  /**
   * Returns the ordinal for the current docID. It is illegal to call this method after {@link
   * #advanceExact(int)} returned {@code false}.
   *
   * @return ordinal for the document: this is dense, starts at 0, then increments by 1 for the next
   *     value in sorted order.
   */
  public abstract int ordValue() throws IOException;

  /**
   * Experimental bulk retrieval of ordinals: {@code ords[i]} is the ordinal of {@code docs[i]}, or
   * {@code -1} when that doc has no value, for {@code i < size}. Behaves as if implemented as
   * below, which is the default implementation:
   *
   * <pre class="prettyprint">
   * for (int i = 0; i &lt; size; ++i) {
   *   ords[i] = advanceExact(docs[i]) ? ordValue() : -1;
   * }
   * </pre>
   *
   * <p><b>NOTE</b>: The {@code docs} array is required to be sorted in ascending order with no
   * duplicates, and the first doc must not be before the current doc.
   *
   * @lucene.experimental
   */
  public void ordValues(int size, int[] docs, int[] ords) throws IOException {
    for (int i = 0; i < size; ++i) {
      ords[i] = advanceExact(docs[i]) ? ordValue() : -1;
    }
  }

  /**
   * Experimental prefetch hint: requests, in whole nodes of {@code nodeBytes} bytes, the stored
   * ordinals of docs in {@code [fromDoc, toDoc)}. See {@link NumericDocValues#prefetchNodes}.
   *
   * @return true if this instance supports the hint
   * @lucene.experimental
   */
  public boolean prefetchNodes(int fromDoc, int toDoc, long nodeBytes) throws IOException {
    return false;
  }

  /**
   * Experimental: see {@link NumericDocValues#nextPrefetchNodeDoc}; -1 if unsupported.
   *
   * @lucene.experimental
   */
  public int nextPrefetchNodeDoc(int doc, long nodeBytes) throws IOException {
    return -1;
  }

  /**
   * Retrieves the value for the specified ordinal. The returned {@link BytesRef} may be re-used
   * across calls to {@link #lookupOrd(int)} so make sure to {@link BytesRef#deepCopyOf(BytesRef)
   * copy it} if you want to keep it around.
   *
   * @param ord ordinal to lookup (must be &gt;= 0 and &lt; {@link #getValueCount()})
   * @see #ordValue()
   */
  public abstract BytesRef lookupOrd(int ord) throws IOException;

  /**
   * Returns the number of unique values.
   *
   * @return number of unique values in this SortedDocValues. This is also equivalent to one plus
   *     the maximum ordinal.
   */
  public abstract int getValueCount();

  /**
   * If {@code key} exists, returns its ordinal, else returns {@code -insertionPoint-1}, like {@code
   * Arrays.binarySearch}.
   *
   * @param key Key to look up
   */
  public int lookupTerm(BytesRef key) throws IOException {
    int low = 0;
    int high = getValueCount() - 1;

    while (low <= high) {
      int mid = (low + high) >>> 1;
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
   * Returns a {@link TermsEnum} over the values. The enum supports {@link TermsEnum#ord()} and
   * {@link TermsEnum#seekExact(long)}.
   */
  public TermsEnum termsEnum() throws IOException {
    return new SortedDocValuesTermsEnum(this);
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
