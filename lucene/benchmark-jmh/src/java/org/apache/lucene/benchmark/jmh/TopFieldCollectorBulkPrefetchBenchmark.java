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
package org.apache.lucene.benchmark.jmh;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.DocIdStream;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopFieldCollectorManager;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH benchmark measuring end-to-end sorted query latency on MMapDirectory. The bulk
 * {@code collect(DocIdStream)} path is automatically activated when sorting by a long field
 * (LongComparator implements BulkValueComparator).
 *
 * <p>Parameterized over numHits, numDocs, field density, and sort configuration (single-field vs
 * multi-field with numeric primary + doc ID tie-breaker).
 *
 * <p>Run with: {@code java -jar benchmark-jmh.jar TopFieldCollectorBulkPrefetchBenchmark}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(
    value = 1,
    jvmArgsAppend = {"-Xmx2g", "-Xms2g", "-XX:+AlwaysPreTouch"})
public class TopFieldCollectorBulkPrefetchBenchmark {

  private static final String SORT_FIELD = "sortValue";
  private static final String FILTER_FIELD = "tag";
  private static final String FILTER_VALUE = "match";

  /** Number of top hits to collect. */
  @Param({"10", "100"})
  public int numHits;

  /** Total number of documents in the index. */
  @Param({"100000", "500000"})
  public int numDocs;

  /** Field density: whether all docs have sort values or only ~50%. */
  public enum Density {
    DENSE,
    SPARSE
  }

  @Param({"DENSE", "SPARSE"})
  public Density density;

  /** Sort configuration: single long field or long field + doc ID tie-breaker. */
  public enum SortConfig {
    SINGLE_FIELD,
    MULTI_FIELD
  }

  @Param({"SINGLE_FIELD", "MULTI_FIELD"})
  public SortConfig sortConfig;

  private Directory directory;
  private DirectoryReader reader;
  private IndexSearcher searcher;
  private Query query;
  private Sort sort;

  @Setup(Level.Trial)
  public void setup() throws Exception {
    directory = MMapDirectory.open(Files.createTempDirectory("topfield-bench"));
    buildIndex();
    reader = DirectoryReader.open(directory);
    searcher = new IndexSearcher(reader);

    // Query that matches ~50% of docs (those tagged "match")
    query = new TermQuery(new Term(FILTER_FIELD, FILTER_VALUE));

    // Build the sort based on sortConfig
    switch (sortConfig) {
      case SINGLE_FIELD:
        sort = new Sort(new SortField(SORT_FIELD, SortField.Type.LONG));
        break;
      case MULTI_FIELD:
        sort =
            new Sort(
                new SortField(SORT_FIELD, SortField.Type.LONG), SortField.FIELD_DOC);
        break;
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() throws Exception {
    reader.close();
    for (String file : directory.listAll()) {
      directory.deleteFile(file);
    }
    directory.close();
  }

  /**
   * Benchmark: sorted query using TopFieldCollectorManager. The bulk collect(DocIdStream) path is
   * automatically activated when sorting by a long field.
   */
  @Benchmark
  public TopFieldDocs bulkPath(Blackhole bh) throws IOException {
    TopFieldCollectorManager manager =
        new TopFieldCollectorManager(sort, numHits, null, Integer.MAX_VALUE);
    TopFieldDocs topDocs = searcher.search(query, manager);
    bh.consume(topDocs.scoreDocs);
    return topDocs;
  }

  /**
   * Benchmark: sorted query forcing per-doc collection (no bulk DocIdStream path). Wraps the
   * collector to intercept collect(DocIdStream) and delegate to stream.forEach(this::collect),
   * bypassing the BulkValueComparator optimization.
   */
  @Benchmark
  public TopFieldDocs perDocPath(Blackhole bh) throws IOException {
    TopFieldCollectorManager inner =
        new TopFieldCollectorManager(sort, numHits, null, Integer.MAX_VALUE);
    // Wrap to force per-doc collection
    CollectorManager<PerDocForceCollector, TopFieldDocs> forcePerDoc =
        new CollectorManager<>() {
          @Override
          public PerDocForceCollector newCollector() throws IOException {
            return new PerDocForceCollector(inner.newCollector());
          }

          @Override
          public TopFieldDocs reduce(java.util.Collection<PerDocForceCollector> collectors)
              throws IOException {
            @SuppressWarnings("unchecked")
            java.util.Collection<org.apache.lucene.search.TopFieldCollector> delegates =
                (java.util.Collection<org.apache.lucene.search.TopFieldCollector>)
                    (java.util.Collection<?>)
                        collectors.stream()
                            .map(c -> (org.apache.lucene.search.TopFieldCollector) c.delegate)
                            .collect(java.util.stream.Collectors.toList());
            return inner.reduce(delegates);
          }
        };
    TopFieldDocs topDocs = searcher.search(query, forcePerDoc);
    bh.consume(topDocs.scoreDocs);
    return topDocs;
  }

  /**
   * Collector wrapper that forces per-doc collection by overriding collect(DocIdStream) to use
   * stream.forEach, bypassing the bulk BulkValueComparator path in TopFieldLeafCollector.
   */
  static class PerDocForceCollector implements Collector {
    final Collector delegate;

    PerDocForceCollector(Collector delegate) {
      this.delegate = delegate;
    }

    @Override
    public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
      LeafCollector inner = delegate.getLeafCollector(context);
      return new LeafCollector() {
        @Override
        public void setScorer(Scorable scorer) throws IOException {
          inner.setScorer(scorer);
        }

        @Override
        public void collect(int doc) throws IOException {
          inner.collect(doc);
        }

        @Override
        public void collect(DocIdStream stream) throws IOException {
          // Force per-doc: bypass bulk path entirely
          stream.forEach(this::collect);
        }

        @Override
        public org.apache.lucene.search.DocIdSetIterator competitiveIterator() throws IOException {
          return inner.competitiveIterator();
        }
      };
    }

    @Override
    public ScoreMode scoreMode() {
      return delegate.scoreMode();
    }
  }

  // --- Index building ---

  private void buildIndex() throws IOException {
    IndexWriterConfig conf = new IndexWriterConfig();
    conf.setMaxBufferedDocs(numDocs + 1);
    conf.setRAMBufferSizeMB(-1);
    try (IndexWriter writer = new IndexWriter(directory, conf)) {
      Random rnd = new Random(42);
      long base = 1_700_000_000_000L; // ~2023 epoch millis
      long gcd = 60_000L; // 1-minute granularity

      for (int i = 0; i < numDocs; i++) {
        Document doc = new Document();

        boolean hasValue =
            (density == Density.DENSE) || rnd.nextBoolean(); // ~50% for SPARSE
        if (hasValue) {
          long value = base + (long) rnd.nextInt(numDocs) * gcd;
          doc.add(new NumericDocValuesField(SORT_FIELD, value));
        }

        // ~50% of docs get the "match" tag for filtering
        if (i % 2 == 0) {
          doc.add(new StringField(FILTER_FIELD, FILTER_VALUE, Field.Store.NO));
        }

        writer.addDocument(doc);
      }
      writer.forceMerge(1);
    }
  }
}
