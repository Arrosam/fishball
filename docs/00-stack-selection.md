# FishBallAgent — Stack Selection

**Date:** 2026-08-30
**Goal:** An Android application that acts as an online-search agent over a self-hosted
SearXNG instance (`https://search.areel.org`), with an advanced RAG memory of the user's
requests running locally on the phone.

---

## 1. Verdict

| Layer | Candidate considered | Decision |
|---|---|---|
| Base agent harness | Pi, DeepSeek Harness (DSH) | **Rejected — wrong runtime shape.** Use **Koog** (JetBrains). |
| Memory / RAG | MemOS | **Rejected as a dependency.** Reimplement its *architecture* natively. |

Neither Pi nor DSH can be the base of an Android app, and MemOS cannot run on a phone.
All three are still useful — as design references, and (for DSH/MemOS) as an optional
desktop companion. Details below.

---

## 2. Why not Pi or DSH

### Pi (`@earendil-works/pi-coding-agent`, MIT)

- TypeScript, runs on **Bun**; distributed via npm and standalone binaries.
- Genuinely minimal and well-factored: `pi-agent-core` (agent loop + tool calling + state),
  `pi-ai` (multi-provider LLM API), `pi-tui`. ~200-token system prompt, 4 default tools.
- **Blocker:** Bun does not target Android. Embedding `pi-agent-core` in an app means
  shipping a JS runtime via `nodejs-mobile` — which Janea Systems stopped maintaining;
  only a community fork remains. That is a load-bearing dependency on an orphaned runtime.
- **Keep as reference:** its tool schema and minimal-prompt philosophy are the right model
  for a phone agent, where every token costs latency and battery.

### DeepSeek Harness (`@deepseek-ai/dsh`, MIT)

- Node.js + TypeScript on the **Cordis** micro-kernel; everything (model, tools, sandbox,
  session store, agent loop, UI) is a swappable plugin.
- Ships a browser Web UI on `127.0.0.1:3080`; `--no-open` gives headless operation.
- **Blockers:** same JS-runtime problem as Pi, plus it is a **developer preview with
  declared breaking changes**, and it is server-shaped (localhost daemon + web UI), not
  app-shaped.
- **Keep as an option:** if we later want a desktop/home-server "power mode" that indexes
  heavy sources and syncs to the phone, DSH + the MemOS local plugin is a ready-made pairing.

### Koog (JetBrains, `ai.koog:koog-agents`) — selected

- JVM/Kotlin Multiplatform; **Android is a first-class target** (also iOS, JS, WasmJS).
- Reached **1.0 at KotlinConf 2026** with a no-breaking-changes guarantee for stable
  modules for at least a year — the opposite of DSH's preview status.
- Gives us out of the box: agent loop, annotation/class-based **tool definitions**,
  **MCP** client, **history compression**, chat + long-term **memory** abstractions,
  vector embeddings and ranked document storage, OpenTelemetry tracing.
- Providers: OpenAI, Anthropic, Google, DeepSeek, OpenRouter, Bedrock, Ollama — **plus a
  LiteRT provider for running models locally on Android**, added in 1.0. This is the single
  feature that lets one codebase serve both the cloud-LLM and on-device-LLM configurations.
- Practical consequence: the agent loop lives in the same Kotlin process as the UI and the
  vector store. No IPC, no bundled interpreter, no background-process death when the screen
  sleeps.

> Verify the current artifact version before wiring the build; some docs pages still show
> pre-1.0 coordinates (`ai.koog:koog-agents:0.5.0`).

---

## 3. Why not MemOS (as a dependency)

MemOS is the strongest *idea* in this space and the benchmarks back it (88.83 LoCoMo,
89.20 LongMemEval; leads OmniMemEval across 14 commercial memory products). But:

- **Core is Python + Neo4j + Qdrant**, Docker Compose. Not phone-viable.
- Its offline story is `@memtensor/memos-local-plugin` — an **npm** package doing
  SQLite + FTS5 + vector hybrid search, aimed at DSH / OpenClaw / Hermes on desktop.
  Same JS-runtime blocker as above.
- **No Android support is claimed anywhere in the project.**

### What we take from it instead

The local plugin's design is directly reimplementable in Kotlin, and it is the right design:

1. **Hybrid retrieval** — lexical (FTS5) *and* dense (vector) with score fusion. This is the
   main thing separating "advanced RAG" from a naive embed-and-cosine loop.
2. **Tiered memory** — MemOS's L1 traces / L2 policies / L3 world model. Mapped to us:
   L1 raw search sessions (query, results, what the user kept), L2 distilled preferences
   ("prefers primary sources", "reads Chinese and English"), L3 durable facts about the
   user's domains of interest.
3. **Write-time extraction, not just read-time retrieval** — a small model summarizes each
   session into memory records rather than dumping raw transcripts into the index.
4. **Explicit, inspectable memory** — a Memory Viewer equivalent. On a phone this matters
   more, not less: the user must be able to see and delete what the app remembers.

Also worth reading before we freeze the retrieval design: Mem0's April 2026 algorithm
(single-pass hierarchical extraction + multi-signal retrieval, +29.6 on temporal queries,
+23.1 on multi-hop), and Graphiti/Zep for temporally-scoped facts.

---

## 4. Recommended stack

| Layer | Choice | Notes |
|---|---|---|
| App shell | Kotlin + Jetpack Compose | Single process, native lifecycle |
| Agent runtime | **Koog** `ai.koog:koog-agents` | Loop, tools, MCP, history compression |
| Reasoning LLM | Pluggable via Koog provider | See open question §6 |
| Memory LLM | Small on-device model | Extraction/summarization only; cheap, private |
| Embeddings | **EmbeddingGemma-300M** | `litert-community/embeddinggemma-300m` or `onnx-community/embeddinggemma-300m-ONNX`; <200MB RAM quantized, 100+ languages, Matryoshka dims (768→128) |
| Vector store | **ObjectBox 4.x** | Native Android/Kotlin, HNSW ANN, sub-ms search |
| Lexical index | SQLite FTS5 | Second leg of hybrid retrieval |
| On-device inference | **LiteRT-LM** | Google's successor to the MediaPipe LLM API (now maintenance-only); GPU/NPU delegates, JIT — one model file serves CPU or GPU |
| Search tool | SearXNG JSON API | See §5 |

**Alternate for the RAG layer:** Google's AI Edge RAG SDK
(`com.google.ai.edge.localagents:localagents-rag`) bundles chunking, a Gecko-110m on-device
embedder, `SqliteVectorStore`, and a `RetrievalAndInferenceChain`. It is the fastest path to
a working baseline, but it is a *pipeline*, not a *memory system* — no tiering, no extraction,
no hybrid fusion. Reasonable as a week-one spike; not the destination.

**Alternate inference runtime:** llama.cpp via JNI (GGUF) if we want model choice beyond
what LiteRT converts cleanly. ExecuTorch is viable (~50KB runtime) but needs per-backend
ahead-of-time compilation, which multiplies our build matrix.

---

## 5. SearXNG integration

Confirmed working against `https://search.areel.org`:

```
GET /search?q=<query>&format=json
```

Returns `{query, results:[{url, title, content, engine, engines, score, category,
publishedDate, ...}]}`. `/config` enumerates the enabled engines and the instance's
32 categories.

Useful parameters for the agent's search tool: `categories`, `engines`, `language`,
`pageno`, `time_range` (`day`/`month`/`year`), `safesearch` (0/1/2).

**Operational note:** the JSON format is enabled and the instance answers unauthenticated
requests from the open internet. That is what makes this easy for us, and it also makes the
instance usable as a free search API by anyone who finds it. Before shipping, consider a
shared secret header or per-IP rate limiting, so the app's traffic is distinguishable and
the instance is not silently absorbing someone else's scraping.

---

## 6. Decision — where the reasoning model runs

**Chosen: remote LLM only** (2026-08-30). Koog's provider abstraction is still used, so
`agent/LlmProvider.kt` is the only file that names a provider and the on-device path stays
open.

**Accepted consequence:** retrieved memories cross the network on every turn, and the
distillation pass sends batches of raw traces. The memory *store* stays on the phone —
never bulk-uploaded, never synced — but its retrieved contents are not private from the
model provider. If that trade stops being acceptable, the mitigation in order of cost is:
(a) point `LlmProvider` at `simpleOllamaAIExecutor` against a machine you control,
(b) move distillation on-device first since it is the bulk-data path, (c) go hybrid.

The options as weighed:

- **On-device LLM** (Gemma / Qwen / Phi-mini class via LiteRT-LM). Fully private, works
  offline for everything except the search call itself, coherent with a local RAG.
  Cost: 3–9B models are markedly weaker at multi-step search loops, and 8GB phones give
  ~4–5GB usable.
- **Remote LLM** (DeepSeek / OpenRouter / Anthropic via Koog). Strong agentic behaviour
  immediately. Cost: retrieved memories get sent to a third party on every turn — which
  partly undercuts the reason for keeping the RAG local.
- **Hybrid (recommended).** An on-device small model owns all memory operations —
  extraction, summarization, query rewriting, dedup, retrieval ranking — so the memory
  store itself never leaves the phone wholesale. A remote model does the hard multi-step
  search reasoning, receiving only the specific retrieved snippets the query justifies.
  Koog's provider abstraction makes this a configuration choice rather than a rewrite, so
  building for hybrid keeps both pure options open.
