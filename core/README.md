# :core

Pure Kotlin/JVM. No Android dependency — that's what makes spec Phase 1 testable without an
emulator. Anything needing Android (SQLite, `Context`) goes behind an interface implemented
in `:app`.

## What's here

| | |
|---|---|
| `trust/` | Tiers, publisher registry, host matching, R1–R8 resolution, R4/R5 corroboration |
| `memory/` | Three memory kinds (§9), TTL buckets (§10, §20), store interface + in-RAM reference impl |
| `session/` | 1-hour rollover and the bridge decision (§8) |
| `search/` | Query/response types, R6 disconfirmation triggers and query generation, SearXNG gateway |
| `answer/` | `AnswerPlanner` — decides the *shape* of a reply from the evidence (§6, §7, §23, §24, R7) |
| `agent/` | `TurnEngine` — the routing state machine (§14–§21) |
| `text/` | Character-bigram similarity, shared by echo detection and repeat-question matching |

Two structural choices worth knowing:

**`TurnEngine` performs no IO.** It returns the next `Step` and consumes results handed back
to it. That's what lets every rule in §7–§24 be exercised without a network, a model, or a
coroutine runtime — the driver in `:app` does the talking. It also means the whole decision
layer is deterministic and diffable.

**`Registry.kt` and `RegistryJson.kt` are split.** The domain model carries no serialization
annotations, so the resolution rules — where all the risk lives — compile and run without a
build system.

The tier list loads from `data/source-tiers.json`, wired in as a resource dir by
`build.gradle.kts`. Tests run against the data that actually ships, not a fixture.

## Verification status

**Gradle now runs.** `./gradlew :core:test` builds and passes:

```
BackendTest      > backend rules hold                          PASSED
TrustResolverTest > trust rules hold against the shipped registry  PASSED
TrustResolverTest > registry parsed with the expected shape    PASSED
TrustResolverTest > every publisher is usable for attribution  PASSED
TrustResolverTest > no domain is claimed by both a publisher and a platform  PASSED
```

That run exercises `loadBundledRegistry()`, so **`RegistryJson.kt`'s kotlinx-serialization
binding is genuinely verified** — previously it could only be cross-checked with a script that
compared property names, which catches typos but not behaviour.

Both specs are plain functions returning failures rather than JUnit assertions, so the
identical checks also run under a bare `kotlinc` when no build system is available. That
fallback found real bugs for weeks; it is no longer the primary gate.

Covered: host normalisation and specificity ordering; the `*.gov.hk` fix; platform floors and
`no-promotion-by-name` in both forms; R3 brand scoping; seller-efficacy demoting AUTHORITATIVE; R4's
threshold matrix; R5 echo and owner-group collapsing; R8 attribution; every TTL boundary;
invalidation (§19); the §20 confirmation gate; session rollover; all four R6 triggers and all
five outcomes; every `AnswerShape` including §23's refusal to answer unsourced; and the full
§14–§21 routing table including crisis override.

**Still not verified.** Anything touching the network — `SearxngGateway` compiles but has
never issued a request from the app.

## Known stopgap

`Similarity.isSameQuestion` matches repeat questions by character-bigram overlap. A question
reworded ("how big is the battery" versus "what is the battery capacity") will miss,
costing an unnecessary search, which is
the safe direction. See docs/01-embedder-options.md for the upgrade.
