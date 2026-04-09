---
inclusion: manual
---

# Doc Values Prefetch Integration Guide for OpenSearch Queries

## Overview

This guide describes how to integrate Lucene doc values prefetch APIs into OpenSearch
query types to eliminate synchronous IO on search threads. The APIs are in the
`prefetch-sort-10.3.1` branch of `lucene_experiments`.

**Core principle:** Batch doc IDs first, prefetch data for the batch, then iterate.
Never prefetch for a single doc — the async IO has no time to complete before the read.

## Available Prefetch APIs

### 1. NumericDocValues.longValues(int size, int[] docs, long[] values, long defaultValue)

**What it does:** Reads numeric values for a batch of docs with prefetch. The codec
override in Lucene90DocValuesProducer calls prefetchFixedBPV, prefetchVaryingBPV,
or prefetchDISI before reading, depending on the encoding.

**Storage encodings covered:** Dense fixed BPV, dense varying BPV, dense table,
dense GCD, sparse fixed BPV, sparse varying BPV, sparse table, sparse GCD, constant (BPV=0).

**When to use:** Any query that reads numeric doc values for multiple docs:
- Sort queries (long, int, float, double) — already integrated in TopFieldCollector
- Date histogram aggregation — reads timestamp values
- Range aggregation — reads numeric boundaries
- Stats/avg/sum/min/max aggregations — reads numeric values

**Integration pattern:**
```java
// In your LeafBucketCollector or LeafCollector:
NumericDocValues ndv = context.reader().getNumericDocValues("field");
int[] docBuffer = new int[PrefetchConfig.getBatchSize()];
long[] valueBuffer = new long[PrefetchConfig.getBatchSize()];

// Collect docs into buffer (from DocIdStream, iterator, or scorer)
int count = collectDocsIntoBatch(docBuffer);

// Prefetch + read in one call
ndv.longValues(count, docBuffer, valueBuffer, 0L);

// Process values — zero IO, all data is warm
for (int i = 0; i < count; i++) {
    aggregator.collect(valueBuffer[i]);
}
```

### 2. SortedDocValues.ordValues(int size, int[] docs, int[] ords, int defaultOrd)

**What it does:** Reads ordinals for a batch of docs with prefetch on the packed
integer ordinal data. Does NOT read term dictionary (BytesRef values).

**When to use:** Any query that needs ordinals for keyword/string fields:
- Keyword sort — already integrated in TopFieldCollector via TermOrdValComparator
- Terms aggregation (per-doc collection path) — reads ordinals per doc
- Composite aggregation with keyword source — reads segment ordinals
- Significant terms aggregation — reads ordinals per doc

**Integration pattern for terms aggregation (per-doc path):**
```java
// In GlobalOrdinalsStringTermsAggregator when tryCollectFromTermFrequencies
// is NOT used (filtered queries, sub-aggregations, significant_terms):
SortedDocValues sdv = context.reader().getSortedDocValues("field");
int[] docBuffer = new int[batchSize];
int[] ordBuffer = new int[batchSize];

// Batch docs from the scorer
int count = collectDocsIntoBatch(docBuffer);

// Prefetch + read ordinals
sdv.ordValues(count, docBuffer, ordBuffer, -1);

// Map segment ordinals to global ordinals and collect
for (int i = 0; i < count; i++) {
    if (ordBuffer[i] >= 0) {
        long globalOrd = globalOrdinalMapping.getGlobalOrd(ordBuffer[i]);
        collectBucket(globalOrd);
    }
}
```

**Integration pattern for composite aggregation:**
```java
// In GlobalOrdinalValuesSource.getLeafCollector():
// Unwrap GlobalOrdinalMapping to get segment SortedDocValues
SortedDocValues segmentSorted = DocValues.unwrapSingleton(
    ((GlobalOrdinalMapping) dvs).getSegmentValues());

// In collectBulk(int[] docs, int count):
int[] segOrds = new int[count];
segmentSorted.ordValues(count, docs, segOrds, -1);
for (int i = 0; i < count; i++) {
    if (segOrds[i] != -1) {
        currentValue = globalMapping.getGlobalOrd(segOrds[i]);
        next.collect(docs[i], 0);
    }
}
```

### 3. SortedDocValues.prepareSeekExact(int ord)

**What it does:** Prefetches the LZ4-compressed term dictionary block containing
the given ordinal. The subsequent lookupOrd(ord) finds the block already in cache.

**When to use:** Any code path that calls lookupOrd() for multiple ordinals:
- Terms aggregation result building — resolves ordinals to term BytesRef values
- Keyword sort copy() — resolves ordinal to BytesRef for priority queue
- Significant terms result building

**IMPORTANT:** Single-ord prefetch provides minimal benefit. The real win is batching:
prefetch N ordinals, then lookupOrd N ordinals.

**Integration pattern for terms aggregation result building:**
```java
// In GlobalOrdinalsStringTermsAggregator.buildResult() or buildAggregations():
// After collecting all bucket ordinals:
int[] collectedOrds = getCollectedOrdinals();  // sorted ascending

// Phase 1: prefetch all LZ4 blocks
SortedDocValues sdv = getSortedDocValues();
for (int ord : collectedOrds) {
    sdv.prepareSeekExact(ord);
}

// Phase 2: read terms — all blocks warm
for (int i = 0; i < collectedOrds.length; i++) {
    BytesRef term = sdv.lookupOrd(collectedOrds[i]);
    buckets[i].setKey(BytesRef.deepCopyOf(term));
}
```

**Integration pattern for keyword sort (already done in TermOrdValComparator):**
```java
// In TermOrdValComparator.copyAt(int slot, int idx):
int ord = (int) batchValues[idx];
termsIndex.prepareSeekExact(ord);  // prefetch LZ4 block
// ... allocate BytesRefBuilder ...
tempBRs[slot].copyBytes(termsIndex.lookupOrd(ord));  // block is warm
```

### 4. SortedNumericDocValues.prefetchRange(int[] docs, int size)

**What it does:** Prefetches DISI (sparse), address index, and value data for a
batch of docs. The subsequent advanceExact() + nextValue() iteration finds
all data warm.

**When to use:** Any query that reads multi-valued numeric fields:
- Date histogram aggregation (multi-valued date field)
- Auto date histogram
- Range aggregation on multi-valued numeric
- Composite aggregation with numeric source

**Integration pattern for date_histogram:**
```java
// In DateHistogramAggregator.getLeafCollector():
SortedNumericDocValues sndv = context.reader().getSortedNumericDocValues("timestamp");
int[] docBuffer = new int[batchSize];

// Batch docs from scorer
int count = collectDocsIntoBatch(docBuffer);

// Prefetch all three layers (DISI + addresses + values)
sndv.prefetchRange(docBuffer, count);

// Iterate normally — all data is warm
for (int i = 0; i < count; i++) {
    if (sndv.advanceExact(docBuffer[i])) {
        int valueCount = sndv.docValueCount();
        for (int j = 0; j < valueCount; j++) {
            long timestamp = sndv.nextValue();
            long bucket = rounding.round(timestamp);
            collectBucket(bucket);
        }
    }
}
```

## Query Type to API Mapping

| OpenSearch Query | Doc Value Type | Prefetch API | Integration Point | Status |
|---|---|---|---|---|
| Sort by long/int/float/double | NumericDocValues | longValues() | TopFieldCollector.collect(DocIdStream) | DONE |
| Sort by keyword | SortedDocValues | ordValues() + prepareSeekExact() | TopFieldCollector via TermOrdValComparator | DONE |
| Terms agg (tryCollectFromTermFrequencies) | SortedDocValues | prepareSeekExact() | GlobalOrdinalsStringTermsAggregator.buildResult() | TODO |
| Terms agg (per-doc path) | SortedDocValues | ordValues() | GlobalOrdinalsStringTermsAggregator.collect() | TODO |
| Composite agg (keyword source) | SortedDocValues | ordValues() | GlobalOrdinalValuesSource.collectBulk() | TODO |
| Composite agg (numeric source) | NumericDocValues | longValues() | NumericValuesSource | TODO |
| Date histogram | SortedNumericDocValues | prefetchRange() | DateHistogramAggregator | TODO |
| Auto date histogram | SortedNumericDocValues | prefetchRange() | AutoDateHistogramAggregator | TODO |
| Range aggregation | NumericDocValues | longValues() | RangeAggregator | TODO |
| Stats/avg/sum/min/max | NumericDocValues | longValues() | MetricsAggregator subclasses | TODO |
| Significant terms | SortedDocValues | ordValues() + prepareSeekExact() | SignificantTermsAggregator | TODO |
| Cardinality aggregation | SortedDocValues | ordValues() | CardinalityAggregator | TODO |

## How to Add Prefetch to a New Query Type

### Step 1: Identify the IO path

Use JFR FileRead events filtered to [search] and [index_searcher] threads to find
which code paths cause IO. Look for:
- Lucene90DocValuesProducer in the stack = doc values IO
- DirectReader.get() = packed integer read (NumericDocValues)
- TermsDict.decompressBlock() = LZ4 term dictionary read (SortedDocValues.lookupOrd)
- IndexedDISI.advanceExact() = DISI existence check (sparse fields)

### Step 2: Choose the right API

- Reading numeric values? Use NumericDocValues.longValues()
- Reading keyword ordinals? Use SortedDocValues.ordValues()
- Resolving ordinals to terms? Use SortedDocValues.prepareSeekExact() before lookupOrd()
- Reading multi-valued numerics? Use SortedNumericDocValues.prefetchRange()

### Step 3: Add batching to the collector

The aggregation framework collects per-doc via LeafBucketCollector.collect(int doc, long bucket).
To batch, add a collectBulk(int[] docs, int count) method:

```java
// In your LeafBucketCollector subclass:
@Override
public void collectBulk(int[] docs, int count) throws IOException {
    // 1. Prefetch
    numericDocValues.longValues(count, docs, valueBuffer, defaultValue);
    // or: sortedDocValues.ordValues(count, docs, ordBuffer, -1);
    // or: sortedNumericDocValues.prefetchRange(docs, count);

    // 2. Process (zero IO)
    for (int i = 0; i < count; i++) {
        // your aggregation logic using valueBuffer[i] or ordBuffer[i]
    }
}
```

Then modify the caller (e.g., SortedDocsProducer.processBucket()) to batch docs
and call collectBulk() instead of per-doc collect().

### Step 4: Gate behind PrefetchConfig

```java
if (PrefetchConfig.isEnabled()) {
    // use bulk API with prefetch
} else {
    // fallback to per-doc
}
```

### Step 5: Validate

1. **Correctness:** Run the query with prefetch ON and OFF, compare results exactly
2. **Prefetch efficiency:** Use buffer pool _stats endpoint to check effective_rate
3. **No wasted prefetches:** wasted_prefetches should be ~0
4. **Cold path latency:** Flush buffer pool before each query, measure latency

## Rules

1. **Never prefetch for a single doc.** The async IO has no time to complete.
2. **Batch size matters.** Use PrefetchConfig.getBatchSize() (default 4096).
3. **Docs must be sorted ascending** in the batch array.
4. **Do not advance iterators in prefetch.** prefetchRange() must not consume
   DISI or other iterator state — the consumer needs to iterate after prefetch.
5. **Gate behind PrefetchConfig.isEnabled().** Always provide a fallback path.
6. **Validate with tracking.** effective_rate_pct must be ~100%, wasted_prefetches ~0.

## File Locations (Lucene — prefetch-sort-10.3.1 branch)

- lucene/core/src/java/org/apache/lucene/index/NumericDocValues.java — longValues() API
- lucene/core/src/java/org/apache/lucene/index/SortedDocValues.java — ordValues(), prepareSeekExact() APIs
- lucene/core/src/java/org/apache/lucene/index/SortedNumericDocValues.java — prefetchRange() API
- lucene/core/src/java/org/apache/lucene/codecs/lucene90/Lucene90DocValuesProducer.java — all prefetch overrides
- lucene/core/src/java/org/apache/lucene/search/TopFieldCollector.java — sort query bulk collection
- lucene/core/src/java/org/apache/lucene/search/BulkValueComparator.java — batch comparison interface
- lucene/core/src/java/org/apache/lucene/search/PrefetchConfig.java — runtime config
- lucene/core/src/java/org/apache/lucene/search/comparators/TermOrdValComparator.java — keyword sort

## File Locations (OpenSearch — TODO integration points)

- server/.../search/aggregations/bucket/terms/GlobalOrdinalsStringTermsAggregator.java
- server/.../search/aggregations/bucket/composite/GlobalOrdinalValuesSource.java
- server/.../search/aggregations/bucket/composite/SortedDocsProducer.java
- server/.../search/aggregations/bucket/histogram/DateHistogramAggregator.java
- server/.../search/aggregations/LeafBucketCollector.java
