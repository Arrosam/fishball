# On-device embedder — the one unfinished seam

> **Superseded as a blocker (2026-08-30).** The product spec replaced the embedding-based
> memory this described. `:core` now matches repeat questions with character-bigram overlap
> (`text/Similarity.kt`), which needs no model at all and whose only failure mode is an
> unnecessary search — the safe direction. This document stays because an embedder is still
> the right upgrade for that matcher: a repeat question worded differently
> ("how big is the battery" versus "what is the battery capacity") misses today. Read it as
> a future option, not a gap.

The memory pipeline is complete and runs end to end. What it runs on today is
`HashingEmbedder`, a deterministic stand-in that hashes word unigrams and character
4-grams into 256 dimensions. It makes writes, retrieval, RRF fusion and time decay all
observable without a 300MB model file. **It understands nothing semantically.** Retrieval
quality until this is replaced is roughly "fuzzy keyword matching".

Both candidates below were deliberately left out of the compile path — their APIs could not
be verified at authoring time, and a scaffold that does not compile is worse than one with
an honest placeholder. Pick one, uncomment its dependency in `app/build.gradle.kts`,
implement `Embedder`, and swap the binding in `AppContainer`.

Changing embedder or dimensions invalidates every stored vector. Bump
`MemoryDatabase.DB_VERSION` — `onUpgrade` drops and recreates, which is correct here since
embeddings are derived data.

---

## Option A — Google AI Edge RAG SDK (Gecko-110m)

```kotlin
implementation("com.google.ai.edge.localagents:localagents-rag:0.3.0")
implementation("com.google.mediapipe:tasks-genai:0.10.27")
```

```kotlin
val embedder: Embedder<String> = GeckoEmbeddingModel(
    /* modelPath  = */ geckoPath,      // gecko_256_f32.tflite
    /* tokenizer  = */ tokenizerPath,  // sentencepiece.model
    /* useGpu     = */ true,
)
```

**For it:** tokenization is handled inside the library — no SentencePiece wiring, which is
the part that eats a day. Native 256 dimensions, matching our schema. GPU delegate is one
boolean. Google-supported.

**Against it:** Gecko-110m is weaker and English-centric next to EmbeddingGemma. The SDK
wants model files on disk; the samples push them to `/data/local/tmp/slm/`, so shipping
means either bundling in assets and copying out on first run, or downloading on first
launch. It drags in `tasks-genai`, most of which we do not use.

Note we would use *only* its embedder, not its `SqliteVectorStore` or
`RetrievalAndInferenceChain` — those are a fixed pipeline with no tiering, extraction or
hybrid fusion, which is the part we actually built.

## Option B — EmbeddingGemma-300M via ONNX Runtime

```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
```

Model: `onnx-community/embeddinggemma-300m-ONNX`, or `litert-community/embeddinggemma-300m`
if going the LiteRT route instead.

**For it:** materially better retrieval — 100+ languages, strong for its size, and
Matryoshka-trained so truncating 768 → 256 costs little. Under 200MB RAM quantized. One
runtime across platforms if this ever goes to iOS.

**Against it:** you must supply tokenization yourself (SentencePiece/Gemma tokenizer as a
JNI or pure-Kotlin port), then mean-pool and L2-normalize the output. That is the real work
here — the inference call is the easy half. Task prefixes must be applied manually; the
strings are already in `EmbedTask`.

## Recommendation

**Option A to get semantic retrieval working this week, Option B before shipping.** They sit
behind the same two-method interface, so the swap is contained, and having A running makes B
measurable — same corpus, same queries, compare what comes back.

## Sanity check after swapping

Write these three traces, then query each probe. A real embedder ranks the intended trace
first; `HashingEmbedder` will not.

| Probe query | Should retrieve | Shares no keywords with |
|---|---|---|
| "how do I make my app start faster" | "Asked: reducing Android cold start time" | ✓ |
| "papers about model memory" | "Asked: MemOS tiered memory architecture" | ✓ |
| a query in a third language | any memory written in English about the same topic | yes (cross-lingual, Option B only) |
