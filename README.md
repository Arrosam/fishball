# FishBallAgent

An Android search agent. It answers questions by searching a private
[SearXNG](https://docs.searxng.org/) instance, and it remembers what you have asked about
before in a tiered RAG memory that lives entirely on the phone.

- **Agent runtime:** [Koog](https://github.com/JetBrains/koog) (JetBrains, JVM/KMP)
- **Reasoning model:** DeepSeek-v4-pro via Hydrogen (`llm.areel.org`), provider-swappable
- **Memory:** on-device, three kinds with TTLs — world knowledge, preferences, conversation log
- **Search:** SearXNG JSON API

Why these and not Pi / DeepSeek Harness / MemOS: [docs/00-stack-selection.md](docs/00-stack-selection.md).

## Status

**Builds.** `./gradlew :app:assembleDebug` produces an APK; `:core:test` passes; the release
build survives R8. Toolchain setup is in [docs/07-toolchain.md](docs/07-toolchain.md).

The product is specified in [docs/03-product-spec.md](docs/03-product-spec.md) (§1–§24) and
the source tier list in [docs/04-source-tiers.md](docs/04-source-tiers.md). Build order is
backend first: `:core` with tests, then the UI.

| Area | State |
|---|---|
| **`:core` backend** | **Complete. Compiled, all checks passing — trust, memory, TTL, sessions, R6, answer planning, turn routing. See [core/README.md](core/README.md)** |
| Source tier list | v5 — 60 publishers / 21 platforms / 10 patterns, publisher-first |
| SearXNG gateway | Written, in `:core`, sends the `User-Agent` the instance requires. Not exercised against the network |
| **`:app` Compose frontend** | **Compiles, builds an APK.** Dummy content; areel.org surfaces (CAD grid, glass, chamfered plates); nothing wired to `:core` yet |
| LLM driver around `TurnEngine` | Not built — the remaining piece between the two |

`TurnEngine` does no IO: it returns the next step and consumes results. The driver that runs
searches and calls DeepSeek through Hydrogen is what still has to be written, and it's the
only thing standing between the working backend and the working app.

## Building

```bash
./gradlew :app:assembleDebug
```

Toolchain, and the three build-config bugs the first real build exposed:
[docs/07-toolchain.md](docs/07-toolchain.md).

Point it at a different SearXNG instance:

```bash
./gradlew installDebug -Pfishball.searxng.baseUrl=https://searx.example.org
```

## How the memory works

Three kinds, per spec §9 — not the tiered/decayed design this README described before the
product was specified:

- **World knowledge** — facts retrieved from search, each stamped with a TTL bucket
  (permanent / one year / one month / always-research). Anything unclassifiable defaults to
  always-research, because re-searching costs three seconds and being confidently stale costs
  the user's trust in the whole app.
- **Preference knowledge** — what the app has learned about the user. Expires too (§20): a
  fact about a person is a slow lie if it never ages out, and it must be confirmed before any
  medical reasoning leans on it.
- **Conversation log** — every turn, kept and time-partitioned, so "what did I ask you
  yesterday" is answerable.

Repeat questions are matched by character-bigram overlap rather than embeddings. A reworded
question misses, which costs an unnecessary search — the safe direction. See
[docs/01-embedder-options.md](docs/01-embedder-options.md).

## Known gaps

- **`:app` compiles but has never run.** No screen has been rendered on a device. An emulator
  image is the next thing to install.
- **`TurnEngine` has no driver.** It returns the next step and consumes results; the loop that
  actually runs searches and calls DeepSeek through Hydrogen is the missing piece between the
  working backend and the working app.
- **The activation key is held in memory only** — the dummy never persists it.
- **No memory screen.** The band has the entry point and `MemoryStore` has
  `browse`/`forget`/`forgetEverything`; the screen itself is not built.
- **`search.areel.org` answers unauthenticated JSON from the open internet**, and three of its
  engines are CAPTCHA-suspended, so every result currently comes from one index. See
  [docs/05-search-reality-check.md](docs/05-search-reality-check.md).
