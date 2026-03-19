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
import org.apache.lucene.util.IOIntConsumer;

/**
 * A {@link DocIdStream} backed by an int array. Used by {@link PrefetchingLeafBucketCollector} to
 * re-wrap materialized doc IDs after prefetching.
 *
 * @lucene.experimental
 */
public final class ArrayDocIdStream extends DocIdStream {

  private final int[] docs;
  private final int count;
  private int pos;

  /** Create a stream over the first {@code count} entries of {@code docs}. */
  public ArrayDocIdStream(int[] docs, int count) {
    this.docs = docs;
    this.count = count;
    this.pos = 0;
  }

  @Override
  public void forEach(int upTo, IOIntConsumer consumer) throws IOException {
    while (pos < count && docs[pos] < upTo) {
      consumer.accept(docs[pos++]);
    }
  }

  @Override
  public int count(int upTo) throws IOException {
    int c = 0;
    while (pos < count && docs[pos] < upTo) {
      pos++;
      c++;
    }
    return c;
  }

  @Override
  public int intoArray(int upTo, int[] array) {
    int n = 0;
    while (pos < count && docs[pos] < upTo && n < array.length) {
      array[n++] = docs[pos++];
    }
    return n;
  }

  @Override
  public boolean mayHaveRemaining() {
    return pos < count;
  }
}
