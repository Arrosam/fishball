# When brute-force vector scan stops being enough

> **Superseded (2026-08-30).** Written for the pre-spec memory layer, which stored vectors
> and scanned them. The current design stores no vectors — see docs/03-product-spec.md §9.
> Kept only for the verified ObjectBox HNSW API, which would matter again if the memory ever
> becomes embedding-backed.

`MemoryDatabase.allEmbeddings()` loads every live vector and `HybridRetriever` scans them
all. This is deliberate, not a shortcut.

## Why brute force is right at this scale

A personal search memory is thousands of records, not millions. At 256 dimensions:

| Records | Vector bytes | Full scan |
|---|---|---|
| 1,000 | 1.0 MB | sub-millisecond |
| 10,000 | 10 MB | a few ms |
| 100,000 | 100 MB | tens of ms — getting uncomfortable |

Against an ANN index it buys: exact results (no recall cliff), no index build or rebalance
cost on write, no extra Gradle plugin or annotation processor, and no separate index file to
keep consistent with the base table. `Config.vectorScanLimit` caps the scan at 20k
most-recent records as a backstop.

## The signals that it is time to move

- Retrieval latency above ~50ms on a mid-range device
- Live record count past ~50,000
- Memory pressure from holding vectors during the scan

## Migration: ObjectBox HNSW

ObjectBox 4.x has an on-device HNSW index for Android/JVM. API verified against
docs.objectbox.io:

```kotlin
@Entity
data class MemoryEntity(
    @Id var id: Long = 0,
    var tier: Int = 0,
    var text: String = "",
    var createdAt: Long = 0,
    @HnswIndex(dimensions = 256, distanceType = VectorDistanceType.COSINE)
    var embedding: FloatArray? = null,
)
```

```kotlin
val query = box
    .query(MemoryEntity_.embedding.nearestNeighbors(queryVector, 50))
    .build()

val results: List<ObjectWithScore<MemoryEntity>> = query.findWithScores()
for (result in results) {
    println("${result.get().text} — distance ${result.score}")
}
```

Tunables on `@HnswIndex`: `neighborsPerNode`, `indexingSearchCount`, `flags`,
`reparationBacklinkProbability`, `vectorCacheHintSizeKB`.

Build setup: the `io.objectbox` Gradle plugin plus its annotation processor.
`dimensions` must be a compile-time constant, so it cannot be driven by
`DEFAULT_DIMENSIONS` if that ever becomes configurable.

### What the migration actually touches

Only `MemoryDatabase` and the vector leg of `HybridRetriever`. Keep FTS5 for the lexical
leg — ObjectBox has no BM25 equivalent, and the hybrid design depends on having two
genuinely different rankings to fuse. That means running both stores side by side, joined
on record id, which is more moving parts than today's single SQLite file. Another reason
not to do it early.
