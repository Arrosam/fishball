# FishBall

An Android search agent that will not guess.

It answers by searching a private [SearXNG](https://docs.searxng.org/) instance, grades every
source it reads, names the publisher inside the sentence you are reading, and cuts quotations
out of the retrieved page rather than letting the model write them. When nothing trustworthy
turns up, it says so instead of answering.

Official site and installer: **[areel.org/fishball](https://areel.org/fishball/en/)**

- **Model:** `fishball-pro` / `fishball-flash` through [Hydrogen](https://github.com/Arrosam/Hydrogen-LLM-proxy), spoken to in the Anthropic message format
- **Retrieval:** `embedding` (1024-dim) and `reranker` on the same proxy
- **Memory:** on-device, three kinds with TTLs — world knowledge, facts about the user, conversation log
- **Search:** SearXNG JSON API
- **UI:** Compose, portrait only, Chinese — the design language is [areel.org](https://areel.org)'s

The product is specified in [docs/03-product-spec.md](docs/03-product-spec.md) (§1–§25); the
source tier list is [docs/04-source-tiers.md](docs/04-source-tiers.md). Stack choices, and why
not Pi / DeepSeek Harness / MemOS: [docs/00-stack-selection.md](docs/00-stack-selection.md).

## Why it exists

Ask a chatbot about a medicine and you get a confident paragraph. Ask again and you may get a
different confident paragraph. Nothing in either tells you which parts came from a health
ministry, which came from a forum, and which the model produced because the sentence needed
finishing.

FishBall was built for one person — someone who reads what a screen tells them and acts on it.
That single fact set every rule in it. The rules are enforced in code, not in the prompt, and
each one can make the app refuse to answer:

- **Every source is graded** on a four-step ladder — authoritative / institutional / personal /
  low — decided by who published it, not by how convincing it reads. The grade changes the
  answer's wording.
- **Attribution is in the sentence**, not a footnote. Attribution you have to go looking for is
  attribution nobody checks.
- **Quotes are sliced, not written.** The model selects a span; the verifier cuts it out of the
  retrieved text and checks it character by character. A quote that does not match is rejected
  and the model is told to try again. No path through the code lets an invented quote reach the
  screen (§25).
- **It searches against itself.** On anything that matters a second search tries to disprove the
  first. Disagreement is shown, not resolved silently (R6).
- **No source, no answer.** If search is unavailable, factual questions get *"我现在查不了资料"*
  and nothing more. The model's own training knowledge is never used to answer one, because such
  an answer looks exactly like a good one minus a citation nobody notices is missing (§23).

## Layout

| Module | What it is |
|---|---|
| `:core` | Pure JVM, no Android. Trust resolution, memory, TTLs, sessions, answer planning, turn routing, the quote verifier, and the driver that runs it all. |
| `:app` | Compose UI, the Hydrogen client wiring, favicons, update checking. |

`TurnEngine` does no IO — it returns the next step and consumes the result.
[`Conversation`](core/src/main/kotlin/org/areel/fishball/core/agent/Conversation.kt) is the
driver: read its `when (step)` block and you are reading spec §7–§25 in execution order.

## How memory works

Three kinds, per §9:

- **World knowledge** — facts retrieved from search, each stamped with a TTL bucket (permanent /
  one year / one month / always-research). Anything unclassifiable defaults to always-research:
  re-searching costs three seconds, being confidently stale costs trust in the whole app.
- **Facts about the user** — what they have said about themselves. These expire too (§20), and
  are confirmed before any medical reasoning leans on one.
- **Conversation log** — every turn, kept and time-partitioned, so *"what did I ask you
  yesterday"* is answerable.

Recall runs in three stages, and each exists because the one before it cannot do the job:

1. **The question is not the search key.** It carries grammar, politeness and usually a pronoun
   standing in for the only word that matters. The model is asked first what facts the question
   *needs* — for 医生给我开了消炎药，吃之前我要注意什么 it answers
   `药物过敏史 / 正在吃的其他药 / 是否怀孕或哺乳` — and those phrases are what memory is searched with.
2. **A wide pass** takes ten cached answers and ten facts about the user, on cosine with a
   word-overlap floor. Tuned to miss nothing.
3. **A narrowing pass, which is two different tests.** Cached answers go to the reranker against
   the original question. Facts about the user are kept on cosine instead — a reranker scores
   whether a passage *answers* a query, and a personal fact answers nothing, so over that section
   it does not rank facts, it deletes them. The measurements behind both thresholds are in
   [docs/03-product-spec.md](docs/03-product-spec.md) §10.

Say something has changed and the harvest finds the note that contradicts it and retires it
(§19), rather than holding two answers to one question.

## Building

```bash
./gradlew :app:assembleDebug
```

Toolchain, and the three build-config bugs the first real build exposed:
[docs/07-toolchain.md](docs/07-toolchain.md).

Point it at a different SearXNG instance, or a different update manifest:

```bash
./gradlew installDebug -Pfishball.searxng.baseUrl=https://searx.example.org
```

### Tests

```bash
./gradlew :core:test
```

`LiveSmokeTest` is opt-in and talks to the real proxy and the real search instance. Without
`HYDROGEN_KEY` in the environment it does nothing and passes, so CI never depends on somebody's
key existing:

```bash
HYDROGEN_KEY=... ./gradlew :core:test --tests '*LiveSmokeTest*'
```

It exists because every other test fakes the model, and the two defects that actually reached a
device — a model that is listed but not permitted, and reply blocks the parser dropped — were
both invisible to a fake.

### Releases

```bash
./gradlew :app:assembleRelease
```

Signing comes from `keystore.properties` at the repo root, which is not in version control along
with the `.jks` it names. Without it the build still runs and produces an unsigned APK, so a
clone compiles without holding the key that ships it.

**Both files have to be backed up somewhere other than the build machine.** Android identifies an
app by its signing key: lose the keystore and no future build can ever update an installed
FishBall. Everyone running it would have to uninstall — losing their conversation and their
memory — to move to a new one.

Two things follow from the release being signed differently to the debug build:

- A phone with the debug build has to uninstall it first, and that wipes its data.
- Release runs R8. It is verified working end to end, but it is a genuinely different binary from
  the debug one: a turn on the release APK is worth running after any dependency change.

### Publishing an update

The app reads `https://areel.org/fishball/latest.json` on launch and offers anything with a
higher `versionCode`; Settings has a 检查更新 row that runs the same check on demand. To ship
one: bump `versionCode` and `versionName` in [app/build.gradle.kts](app/build.gradle.kts),
build the release, and attach the APK to a GitHub release tagged `v<versionName>` under two
names — **`fishball-<versionName>.apk`**, so a downloaded file says which build it is, and
**`fishball.apk`**, so `releases/latest/download/fishball.apk` keeps resolving for anyone who
has that link. Then update `latest.json` on the site: `versionCode`, `versionName`, `size`,
`notes`, and `url` pointing at the version-named asset. The site's download buttons follow
`url` from the manifest, so the page itself does not need editing beyond its fallback text.

## Known gaps

- **One pair of hands.** It is used daily and it works end to end, but it has had no second user
  for a week, which is the only thing that earns a 1.x.
- **Fonts.** areel.org uses Archivo and JetBrains Mono; the app currently renders in the system
  families. Geometry and colour are faithful, the letterforms are not.
- **Memory-screen copy is unapproved** — 网上学到的 / 关于你的 are the designer's proposals from
  [docs/06-design-directions.md](docs/06-design-directions.md), used so the screen exists and can
  be judged.
- **`search.areel.org` answers unauthenticated JSON from the open internet**, and some of its
  engines are CAPTCHA-suspended. See [docs/05-search-reality-check.md](docs/05-search-reality-check.md).

## Licence

MIT.
