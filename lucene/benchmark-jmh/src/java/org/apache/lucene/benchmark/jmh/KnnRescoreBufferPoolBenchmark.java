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
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.benchmark.jmh.bufferpool.BufferPoolDirectory;
import org.apache.lucene.benchmark.jmh.bufferpool.IOStats;
import org.apache.lucene.benchmark.jmh.bufferpool.LRUBlockCache;
import org.apache.lucene.benchmark.jmh.bufferpool.MemorySegmentPool;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.lucene.codecs.lucene104.Lucene104HnswScalarQuantizedVectorsFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.RescoreTopNQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.SuppressForbidden;
import org.apache.lucene.util.quantization.QuantizedByteVectorValues.ScalarEncoding;
import org.openjdk.jmh.annotations.AuxCounters;
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

/**
 * Benchmarks the full-precision rescore phase of quantized KNN search, with all IO served through
 * the buffer-pool directory backported from JunoStorageEncryptionPlugin (block-aligned pooled
 * direct buffers, GC-freed on eviction) so IO calls are counted deterministically — mirroring how
 * search runs in AOSS.
 *
 * <p>Index: scalar-quantized HNSW ({@link Lucene104HnswScalarQuantizedVectorsFormat}, m=16,
 * beamWidth=256 — the AOS-SA-ARTIFACTS benchmark-with-locust HNSW config), cosine similarity,
 * deliberately split across {@code numSegments} segments ({@code NoMergePolicy} + one flush per
 * batch) to expose the recall/IO effect of segment count.
 *
 * <p>Two query shapes, both asking for the same inner candidate count {@code k' = k +
 * round(k*oversample)} (oversample 2.0 = the AOSS Tier-C rescore config):
 *
 * <ul>
 *   <li>{@code quantizedOnly} — plain {@link KnnFloatVectorQuery}, scores stay quantized.
 *   <li>{@code rescored} — the same query wrapped in {@link
 *       RescoreTopNQuery#createFullPrecisionRescorerQuery}, which loads {@code k'} full-precision
 *       vectors from the {@code .vec} file per query.
 * </ul>
 *
 * <p>Aux counters report per-iteration IO: block loads (actual pread calls) split by extension,
 * cache hits, and {@code fpVectorLoads} — the number of full-precision vectors materialized via
 * {@code readFloats} on the {@code .vec} file. A one-time characterization (recall@k vs exact
 * ground truth, cold/warm IO per query) is printed at trial setup.
 *
 * <p>Run with:
 *
 * <pre>
 *   ./gradlew -p lucene/benchmark-jmh assemble
 *   java -jar lucene/benchmark-jmh/build/benchmarks/lucene-benchmark-jmh-*.jar KnnRescoreBufferPool
 * </pre>
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(
    value = 1,
    jvmArgsAppend = {"-Xmx8g", "-Xms8g", "-XX:MaxDirectMemorySize=4g"})
public class KnnRescoreBufferPoolBenchmark {

  private static final String FIELD = "vec";
  private static final int NUM_QUERY_VECTORS = 32;

  /** Plugin default cache block size (StaticConfigs.CACHE_BLOCK_SIZE): 128 KiB. */
  private static final int BLOCK_SIZE = 128 * 1024;

  /** Page-alignment padding per pooled segment (StaticConfigs.PADDING_BLOCK_SIZE). */
  private static final int PADDING = 16 * 1024;

  @Param({"1024"})
  public int dim;

  @Param({"100000"})
  public int numDocs;

  /** Many segments on purpose: quantifies recall/IO drift as segment count grows. */
  @Param({"1", "10", "20"})
  public int numSegments;

  @Param({"10"})
  public int k;

  /** AOSS Tier-C rescore oversample factor; inner candidates k' = k + round(k * oversample). */
  @Param({"2.0"})
  public double oversample;

  /** Block cache capacity in MiB. */
  @Param({"128"})
  public int cacheMB;

  private Path tmpDir;
  private MemorySegmentPool pool;
  private LRUBlockCache blockCache;
  private BufferPoolDirectory dir;
  private IndexReader reader;
  private IndexSearcher searcher;
  private float[][] queryVectors;
  private int[][] groundTruth;
  private int innerK;
  private int queryIdx;

  @Setup(Level.Trial)
  public void setup() throws Exception {
    innerK = k + (int) Math.round(k * oversample);
    tmpDir = Files.createTempDirectory("KnnRescoreBufferPoolBenchmark");

    // --- Buffer pool + block cache (backported memory management) ---
    int reservedSegmentSize = BLOCK_SIZE + PADDING;
    int maxBlocks = (int) (((long) cacheMB << 20) / BLOCK_SIZE);
    // Pool sized 4x the cache: evicted blocks are freed by GC (Cleaner), so in-flight +
    // not-yet-collected wrappers need headroom above the cache capacity.
    long poolMemory = 4L * maxBlocks * reservedSegmentSize;
    pool =
        new MemorySegmentPool(
            poolMemory,
            BLOCK_SIZE,
            reservedSegmentSize,
            /* gcHeadroomFraction= */ 0.25,
            /* gcHintEnabled= */ true,
            /* gcHintCooldownSeconds= */ 1);
    // The plugin's fail-fast default relies on OpenSearch retrying the transient rejection at a
    // higher layer; the benchmark has no retry layer, so use the pool's bounded stall loop
    // (dynamic setting plugins.crypto.stall_loop_disabled=false in the plugin) to wait for the
    // GC/Cleaner to reclaim evicted buffers under pressure.
    pool.setStallLoopDisabled(false);
    blockCache = new LRUBlockCache(pool, BLOCK_SIZE, maxBlocks);
    dir = new BufferPoolDirectory(tmpDir, blockCache, BLOCK_SIZE);

    // --- Data (locust benchmark shape: cohere-wiki dim=1024, cosine) ---
    Random random = new Random(42);
    float[][] vectors = new float[numDocs][];
    for (int i = 0; i < numDocs; i++) {
      vectors[i] = randomUnitVector(dim, random);
    }

    // --- Ingest: force numSegments segments (NoMergePolicy, one flush per batch) ---
    IndexWriterConfig iwc =
        new IndexWriterConfig()
            .setCodec(
                new Lucene104Codec() {
                  @Override
                  public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
                    return new Lucene104HnswScalarQuantizedVectorsFormat(
                        ScalarEncoding.UNSIGNED_BYTE, 16, 256);
                  }
                })
            .setMergePolicy(NoMergePolicy.INSTANCE)
            .setUseCompoundFile(false)
            .setRAMBufferSizeMB(1024);
    int docsPerSegment = Math.max(1, numDocs / numSegments);
    try (IndexWriter writer = new IndexWriter(dir, iwc)) {
      for (int i = 0; i < numDocs; i++) {
        Document doc = new Document();
        doc.add(new KnnFloatVectorField(FIELD, vectors[i], VectorSimilarityFunction.COSINE));
        writer.addDocument(doc);
        if ((i + 1) % docsPerSegment == 0) {
          writer.flush();
        }
      }
      writer.commit();
    }

    reader = DirectoryReader.open(dir);
    searcher = new IndexSearcher(reader);
    searcher.setQueryCache(null);

    // --- Queries + exact ground truth ---
    Random queryRandom = new Random(123);
    queryVectors = new float[NUM_QUERY_VECTORS][];
    for (int i = 0; i < NUM_QUERY_VECTORS; i++) {
      queryVectors[i] = randomUnitVector(dim, queryRandom);
    }
    groundTruth = new int[NUM_QUERY_VECTORS][];
    for (int i = 0; i < NUM_QUERY_VECTORS; i++) {
      groundTruth[i] = exactTopK(vectors, queryVectors[i], k);
    }

    characterize();
    IOStats.reset();
  }

  /**
   * One-time deterministic characterization: recall@k and per-query IO for both query shapes, cold
   * (empty block cache) and warm. Printed once so the JMH numbers can be interpreted.
   */
  @SuppressForbidden(reason = "JMH benchmark one-time diagnostics")
  private void characterize() throws IOException {
    System.out.printf(
        Locale.ROOT,
        "%n=== characterization: numDocs=%d dim=%d segments=%d (leaves=%d) k=%d innerK=%d cache=%dMiB block=%dKiB ===%n",
        numDocs,
        dim,
        numSegments,
        reader.leaves().size(),
        k,
        innerK,
        cacheMB,
        BLOCK_SIZE / 1024);
    characterizeMode("quantizedOnly", false);
    characterizeMode("rescored     ", true);
  }

  @SuppressForbidden(reason = "JMH benchmark one-time diagnostics")
  private void characterizeMode(String label, boolean rescore) throws IOException {
    // Cold pass: empty cache, count demand IO.
    blockCache.clear();
    IOStats.reset();
    double recall = 0;
    for (int i = 0; i < NUM_QUERY_VECTORS; i++) {
      TopDocs td = searcher.search(query(queryVectors[i], rescore), k);
      recall += recall(td, groundTruth[i]);
    }
    recall /= NUM_QUERY_VECTORS;
    long coldVecLoads = IOStats.blockLoads("vec");
    long coldVeqLoads = IOStats.blockLoads("veq");
    long coldVexLoads = IOStats.blockLoads("vex");
    long coldTotal = IOStats.totalBlockLoads();
    long coldFp = IOStats.floatReads("vec");

    // Warm pass: same queries again, cache populated.
    IOStats.reset();
    for (int i = 0; i < NUM_QUERY_VECTORS; i++) {
      searcher.search(query(queryVectors[i], rescore), k);
    }
    long warmTotal = IOStats.totalBlockLoads();
    long warmFp = IOStats.floatReads("vec");

    System.out.printf(
        Locale.ROOT,
        "%s recall@%d=%.4f | cold/query: blockLoads=%.1f (.vec=%.1f .veq=%.1f .vex=%.1f) fpVectorLoads=%.1f"
            + " | warm/query: blockLoads=%.1f fpVectorLoads=%.1f%n",
        label,
        k,
        recall,
        (double) coldTotal / NUM_QUERY_VECTORS,
        (double) coldVecLoads / NUM_QUERY_VECTORS,
        (double) coldVeqLoads / NUM_QUERY_VECTORS,
        (double) coldVexLoads / NUM_QUERY_VECTORS,
        (double) coldFp / NUM_QUERY_VECTORS,
        (double) warmTotal / NUM_QUERY_VECTORS,
        (double) warmFp / NUM_QUERY_VECTORS);
  }

  @TearDown(Level.Trial)
  @SuppressForbidden(reason = "JMH benchmark one-time diagnostics")
  public void teardown() throws IOException {
    System.out.printf(
        Locale.ROOT,
        "%n=== pool: allocations=%d inUse=%d stalls=%d ooms=%d gcHints=%d | cache evictions=%d ===%n%s",
        pool.getCumulativeAllocations(),
        pool.getBuffersInUse(),
        pool.getStallCount(),
        pool.getOomCount(),
        pool.getGcTriggerCount(),
        IOStats.evictions(),
        IOStats.dump());
    IOUtils.close(reader, dir);
    pool.close();
    IOUtils.rm(tmpDir);
  }

  /**
   * IO and quality aux counters, reported per measurement iteration. {@code recall} is the running
   * average recall@k of every query measured in the iteration; {@code gtDocsFound} / ({@code
   * queries} * k) reproduces it from the raw event counts.
   */
  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class IOCounters {
    public long blockLoads;
    public long vecBlockLoads;
    public long veqBlockLoads;
    public long fpVectorLoads;
    public long cacheHits;
    public long gtDocsFound;
    public long queries;
    public double recall;

    private long blockLoadsMark, vecMark, veqMark, fpMark, hitsMark;

    @Setup(Level.Iteration)
    public void clean() {
      blockLoads = vecBlockLoads = veqBlockLoads = fpVectorLoads = cacheHits = 0;
      gtDocsFound = queries = 0;
      recall = 0;
      mark();
    }

    void mark() {
      blockLoadsMark = IOStats.totalBlockLoads();
      vecMark = IOStats.blockLoads("vec");
      veqMark = IOStats.blockLoads("veq");
      fpMark = IOStats.floatReads("vec");
      hitsMark = IOStats.totalCacheHits();
    }

    void collect(TopDocs topDocs, int[] truth, int k) {
      blockLoads += IOStats.totalBlockLoads() - blockLoadsMark;
      vecBlockLoads += IOStats.blockLoads("vec") - vecMark;
      veqBlockLoads += IOStats.blockLoads("veq") - veqMark;
      fpVectorLoads += IOStats.floatReads("vec") - fpMark;
      cacheHits += IOStats.totalCacheHits() - hitsMark;
      gtDocsFound += matches(topDocs, truth);
      queries++;
      recall = (double) gtDocsFound / ((double) queries * k);
    }
  }

  private Query query(float[] queryVector, boolean rescore) {
    Query inner = new KnnFloatVectorQuery(FIELD, queryVector, innerK);
    if (rescore == false) {
      return inner;
    }
    return RescoreTopNQuery.createFullPrecisionRescorerQuery(inner, queryVector, FIELD, k);
  }

  /** Quantized HNSW search only — no full-precision rescore. */
  @Benchmark
  public TopDocs quantizedOnly(IOCounters counters) throws IOException {
    int idx = queryIdx++ & (NUM_QUERY_VECTORS - 1);
    counters.mark();
    TopDocs td = searcher.search(query(queryVectors[idx], false), k);
    counters.collect(td, groundTruth[idx], k);
    return td;
  }

  /** Quantized HNSW search + full-precision rescore of the k' candidates. */
  @Benchmark
  public TopDocs rescored(IOCounters counters) throws IOException {
    int idx = queryIdx++ & (NUM_QUERY_VECTORS - 1);
    counters.mark();
    TopDocs td = searcher.search(query(queryVectors[idx], true), k);
    counters.collect(td, groundTruth[idx], k);
    return td;
  }

  /** Number of ground-truth docs present in the result (10x10 array scan — no allocation). */
  static int matches(TopDocs topDocs, int[] truth) {
    int found = 0;
    for (ScoreDoc sd : topDocs.scoreDocs) {
      for (int doc : truth) {
        if (sd.doc == doc) {
          found++;
          break;
        }
      }
    }
    return found;
  }

  private static double recall(TopDocs topDocs, int[] truth) {
    return (double) matches(topDocs, truth) / truth.length;
  }

  /** Exact top-k by cosine similarity; doc ids equal insertion order (no merges, no deletes). */
  private static int[] exactTopK(float[][] vectors, float[] query, int k) {
    float[] bestScores = new float[k];
    int[] bestDocs = new int[k];
    java.util.Arrays.fill(bestScores, Float.NEGATIVE_INFINITY);
    java.util.Arrays.fill(bestDocs, -1);
    for (int doc = 0; doc < vectors.length; doc++) {
      float score = VectorSimilarityFunction.COSINE.compare(query, vectors[doc]);
      if (score > bestScores[k - 1]) {
        int pos = k - 1;
        while (pos > 0 && bestScores[pos - 1] < score) {
          bestScores[pos] = bestScores[pos - 1];
          bestDocs[pos] = bestDocs[pos - 1];
          pos--;
        }
        bestScores[pos] = score;
        bestDocs[pos] = doc;
      }
    }
    return bestDocs;
  }

  private static float[] randomUnitVector(int dim, Random rnd) {
    float[] v = new float[dim];
    float norm = 0;
    for (int i = 0; i < dim; i++) {
      v[i] = rnd.nextFloat() * 2 - 1;
      norm += v[i] * v[i];
    }
    norm = (float) Math.sqrt(norm);
    for (int i = 0; i < dim; i++) {
      v[i] /= norm;
    }
    return v;
  }
}
