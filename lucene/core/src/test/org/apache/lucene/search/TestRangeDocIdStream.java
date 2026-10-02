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
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.FixedBitSet;

public class TestRangeDocIdStream extends LuceneTestCase {

  public void testForEach() throws IOException {
    RangeDocIdStream stream = new RangeDocIdStream(42, 100);
    int[] expected = new int[] {42};
    stream.forEach(
        doc -> {
          assertEquals(expected[0]++, doc);
        });
    assertEquals(100, expected[0]);
  }

  public void testCount() throws IOException {
    RangeDocIdStream stream = new RangeDocIdStream(42, 100);
    assertEquals(100 - 42, stream.count());
  }

  public void testIntoArray() throws IOException {
    RangeDocIdStream stream = new RangeDocIdStream(42, 100);
    int[] array = new int[16];
    int o = array.length;
    int count = array.length;

    for (int i = 42; i < 100; ++i) {
      if (o == count) {
        count = stream.intoArray(array);
        o = 0;
        if (100 - i >= array.length) {
          assertEquals(array.length, count);
        }
      }
      assertEquals(i, array[o++]);
    }
    assertEquals(count, o);
  }

  public void testForEachUpTo() throws IOException {
    RangeDocIdStream stream = new RangeDocIdStream(42, 100);
    int[] expected = new int[] {42};

    assertTrue(stream.mayHaveRemaining());
    stream.forEach(20, doc -> fail());

    assertTrue(stream.mayHaveRemaining());
    stream.forEach(
        65,
        doc -> {
          assertEquals(expected[0]++, doc);
        });
    assertEquals(65, expected[0]);

    assertTrue(stream.mayHaveRemaining());
    stream.forEach(
        120,
        doc -> {
          assertEquals(expected[0]++, doc);
        });
    assertEquals(100, expected[0]);

    assertFalse(stream.mayHaveRemaining());
  }

  public void testCountUpTo() throws IOException {
    RangeDocIdStream stream = new RangeDocIdStream(42, 100);
    assertTrue(stream.mayHaveRemaining());
    assertEquals(0, stream.count(20));
    assertTrue(stream.mayHaveRemaining());
    assertEquals(65 - 42, stream.count(65));
    assertTrue(stream.mayHaveRemaining());
    assertEquals(100 - 65, stream.count(120));
    assertFalse(stream.mayHaveRemaining());
  }

  public void testIntoArrayUpTo() throws IOException {
    RangeDocIdStream stream = new RangeDocIdStream(42, 100);
    int[] array = new int[16];
    int o = array.length;
    int count = array.length;

    for (int upTo = 42; upTo < 100; ) {
      int newUpTo = Math.min(upTo + random().nextInt(40), 100);
      for (int i = upTo; i < newUpTo; ++i) {
        if (o == count) {
          count = stream.intoArray(newUpTo, array);
          o = 0;
          if (newUpTo - i >= array.length) {
            assertEquals(array.length, count);
          }
        }
        assertEquals(i, array[o++]);
      }
      assertEquals(count, o);
      upTo = newUpTo;
    }
  }

  public void testMixForEachCountUpTo() throws IOException {
    RangeDocIdStream stream = new RangeDocIdStream(42, 100);
    int[] expected = new int[] {42};
    assertTrue(stream.mayHaveRemaining());
    stream.forEach(
        65,
        doc -> {
          assertEquals(expected[0]++, doc);
        });
    assertEquals(65, expected[0]);

    assertTrue(stream.mayHaveRemaining());
    assertEquals(80 - 65, stream.count(80));

    expected[0] = 80;
    assertTrue(stream.mayHaveRemaining());
    stream.forEach(
        90,
        doc -> {
          assertEquals(expected[0]++, doc);
        });
    assertEquals(90, expected[0]);

    assertTrue(stream.mayHaveRemaining());
    assertEquals(100 - 90, stream.count(120));

    assertFalse(stream.mayHaveRemaining());
  }

  public void testIntoBitSetUpTo() throws IOException {
    for (int iter = 0; iter < 100; iter++) {
      int min = TestUtil.nextInt(random(), 0, 100);
      int max = min + TestUtil.nextInt(random(), 1, 200);
      int offset = min - TestUtil.nextInt(random(), 0, 70);
      FixedBitSet dest = new FixedBitSet(max - offset + 10);
      FixedBitSet expected = new FixedBitSet(dest.length());
      RangeDocIdStream stream = new RangeDocIdStream(min, max);
      int upTo = min;
      while (stream.mayHaveRemaining()) {
        int next = upTo + TestUtil.nextInt(random(), 0, 40);
        stream.intoBitSet(next, dest, offset);
        for (int doc = upTo; doc < Math.min(next, max); doc++) {
          expected.set(doc - offset);
        }
        upTo = Math.max(upTo, next);
        assertEquals(expected, dest);
      }
    }
  }

  public void testDefaultIntoBitSet() throws IOException {
    // the default implementation, through forEach
    FixedBitSet source = new FixedBitSet(300);
    for (int i = 0; i < 300; i += TestUtil.nextInt(random(), 1, 9)) {
      source.set(i);
    }
    DocIdStream stream =
        new DocIdStream() {
          final DocIdStream in = new BitSetDocIdStream(source, 0);

          @Override
          public void forEach(int upTo, CheckedIntConsumer<IOException> consumer)
              throws IOException {
            in.forEach(upTo, consumer);
          }

          @Override
          public int count(int upTo) throws IOException {
            return in.count(upTo);
          }

          @Override
          public int intoArray(int upTo, int[] array) {
            return in.intoArray(upTo, array);
          }

          @Override
          public boolean mayHaveRemaining() {
            return in.mayHaveRemaining();
          }
        };
    FixedBitSet dest = new FixedBitSet(400);
    stream.intoBitSet(150, dest, -100);
    stream.intoBitSet(DocIdSetIterator.NO_MORE_DOCS, dest, -100);
    for (int i = 0; i < 400; i++) {
      assertEquals(i >= 100 && source.get(i - 100), dest.get(i));
    }
  }
}
