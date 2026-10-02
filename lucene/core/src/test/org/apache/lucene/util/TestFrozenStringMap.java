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
package org.apache.lucene.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;

public class TestFrozenStringMap extends LuceneTestCase {

  public void testEmpty() {
    FrozenStringMap<Integer> map = FrozenStringMap.of(new HashMap<>());
    assertEquals(0, map.size());
    assertNull(map.get("a"));
    assertNull(map.get(""));
    assertTrue(map.sortedKeys().isEmpty());
  }

  public void testRandom() {
    final int iters = atLeast(50);
    for (int iter = 0; iter < iters; iter++) {
      final Map<String, Integer> expected = new HashMap<>();
      final int count = random().nextInt(2000);
      for (int i = 0; i < count; i++) {
        expected.put(TestUtil.randomUnicodeString(random(), 20), i);
      }
      assertSameContent(expected, FrozenStringMap.of(expected));
    }
  }

  public void testCollidingHashCodes() {
    // "Aa" and "BB" have the same String#hashCode, so all 2^k combinations of them collide
    final Map<String, Integer> expected = new HashMap<>();
    List<String> words = new ArrayList<>(List.of(""));
    for (int k = 0; k < 9; k++) {
      List<String> next = new ArrayList<>();
      for (String w : words) {
        next.add(w + "Aa");
        next.add(w + "BB");
      }
      words = next;
    }
    for (int i = 0; i < words.size(); i++) {
      expected.put(words.get(i), i);
    }
    expected.put("other", -1);
    FrozenStringMap<Integer> map = FrozenStringMap.of(expected);
    assertSameContent(expected, map);
    assertNull(map.get("AaAaAaAaAaAaAaAaBc"));
  }

  public void testLookupWithNewStringInstance() {
    final Map<String, Integer> expected = new HashMap<>();
    expected.put("productType", 1);
    expected.put("copy.title.localized", 2);
    FrozenStringMap<Integer> map = FrozenStringMap.of(expected);
    assertEquals(Integer.valueOf(1), map.get(new String("productType".toCharArray())));
    assertEquals(Integer.valueOf(2), map.get(new String("copy.title.localized".toCharArray())));
  }

  private static void assertSameContent(
      Map<String, Integer> expected, FrozenStringMap<Integer> map) {
    assertEquals(expected.size(), map.size());
    for (Map.Entry<String, Integer> e : expected.entrySet()) {
      assertEquals(e.getValue(), map.get(new String(e.getKey().toCharArray())));
    }
    for (int i = 0; i < 100; i++) {
      String absent = TestUtil.randomUnicodeString(random(), 25) + "\u0000absent";
      assertNull(map.get(absent));
    }
    // sorted keys and positional access agree with a TreeMap
    List<String> sorted = new ArrayList<>(new TreeMap<>(expected).keySet());
    assertEquals(sorted, map.sortedKeys());
    for (int i = 0; i < sorted.size(); i++) {
      assertEquals(sorted.get(i), map.keyAt(i));
      assertEquals(expected.get(sorted.get(i)), map.valueAt(i));
    }
    expectThrows(UnsupportedOperationException.class, () -> map.sortedKeys().add("x"));
  }
}
