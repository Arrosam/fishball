# FishBall — product requirements

**Date:** 2026-08-30 · **Status:** signed off

> **Language.** This document, the codebase and all comments are English. Chinese appears
> only as *examples of product output* and as the model-facing prompt, because the product
> answers a Chinese-speaking user (§12) and is instructed in that language. In code those
> strings live in `core/copy/Copy.kt` and `app/res/values/strings.xml`, nowhere else.

## Problem

A small group of non-technical people talk to AI the way they'd talk to a professional —
one endless conversation, real questions, real stakes. Today that's Dora, running a light
Doubao flash model with short context. It is confidently wrong, forgets everything between
conversations, and cites nothing. The user cannot tell a good answer from a bad one, and
has no vocabulary — agent, API key, session, model — to help themselves.

First real user: the author's family member.

## Users

Non-technical. No concept of sessions, keys, models, or citations. Small, known group,
keys issued by hand.

## Behavior

1. **Login** — one key, issued by the operator. The key is both identity and the Hydrogen
   credential. No signup, no email, no password.
2. **Interface** — one endless conversation. No chat list, no folders, no settings the user
   must understand to get an answer. Text input only for v1.
3. **Model** — DeepSeek-v4-pro via Hydrogen (`llm.areel.org`, OpenAI-compatible).
4. **Search** — SearXNG (`search.areel.org`). The model decides when to search; not every
   message triggers one.

### 5. Source trust

| Tier | Sources |
|---|---|
| **AUTHORITATIVE** | `.gov` / `.gov.cn` / `.gov.hk` · `.edu` / `.edu.cn` · international bodies on `.int`/`.org` (WHO, UN, World Bank, IMF) · high-impact journals by name (`nature.com`, `science.org`, `nejm.org`, `thelancet.com`, `cell.com`) · manufacturer official sites — **scoped, see R3** |
| **INSTITUTIONAL** | Major news organisations (Xinhua, People's Daily, CCTV, Nanfang, Reuters, AP, BBC) · professional verticals (DXY) · small-brand official sites · paper *indexes* (PubMed, PMC, CNKI, arXiv) where the journal isn't itself listed |
| **PERSONAL** | Forums, Q&A answers, comments, individual creators — **pattern only, see R4** |
| **LOW** | Small self-media · random blogs · content farms · **any unrecognized domain** |

**R1** — Only the registry grants AUTHORITATIVE. The model may never promote a source to it.
This holds even with a strong model: the same domain otherwise gets rated differently run to
run, and the point is a consistent, auditable rule.

**R2** — An unknown domain starts at LOW. The model may argue it up to INSTITUTIONAL with a
stated reason, never higher.

**R3 — manufacturer authority is scoped to the claim.** A well-known brand's official site is
AUTHORITATIVE about the objective attributes of its own product (specifications, ingredients,
price, availability, warranty) and drops to INSTITUTIONAL for anything evaluative or
comparative. `apple.com` is authoritative for battery capacity and merely interested in
whether the phone is any good. Without this rule, a brand's own marketing about whether its
product *works* inherits the authority of its spec sheet.

**R4 — personal-tier sources count as a pattern, never as a testimony.** A single comment is
not evidence and is not stated at all. Enough *independent* sources converging on the same
experiential claim may be stated at INSTITUTIONAL-adjacent confidence, phrased as a pattern —
*"不少网友都反映……，不过这是个人使用体验，不是测试数据。"*

The threshold scales with how much the claim is standing alone:

| | ordinary topic | high-stakes topic (health / medication / investment / safety) |
|---|---|---|
| no high-confidence backing, or backing is contested | **5** | **9** |
| uncontested high-confidence backing exists | **3** | **5** |

*Accepted consequence:* 9 is effectively a ban with an escape hatch, not a threshold. SearXNG
returns 10–20 results per page and many are reposts of each other, so reaching 9 independent
sources takes several result pages and will usually fail. For high-stakes topics personal
sources are therefore excluded unless the pattern is genuinely massive — a widely-known side
effect. This is intended.

**R5 — echo is not corroboration.** Independent means distinct publisher **and** not
near-duplicate text; content farms mass-repost identical copy, so naive counting turns one
claim into false consensus. R4 promotes experiential claims from personal sources only —
LOW-tier media is never promoted by count.

**R6 — actively search for disconfirmation.** Don't just fail to find support; go looking for
the counter-case. Fires on any of three triggers:

- no AUTHORITATIVE and no INSTITUTIONAL source was found
- high-confidence sources contradict each other
- the topic is health / medication / investment / safety — always, even when authoritative
  sources were found

Query patterns are Chinese because the corpus is; they live in `SearchTerms`. Three
consequences:

- Counter-evidence clears **the same trust bar** as evidence. A search for "X is a scam"
  always returns something — the internet has a debunking page for every claim — so
  unfiltered negative results just swap one bad answer for its mirror image.
- A successful disconfirmation **raises** confidence. Authoritative research finding no
  effect is a confident answer — *"有研究做过，没发现明确效果"* — not a hedge.
- Both directions empty is itself the answer: *"正反两个方向我都查了，都没有权威资料。"*

**R7 — disclose disagreement, never resolve it silently.** When authoritative sources
conflict, the user is told that they conflict. Picking a side and presenting it as settled
is, for a user who cannot check, the same as lying. Shape:

1. What is *not* disputed, stated plainly — there is almost always common ground.
2. The disagreement, with each side attributed.
3. What would settle it for them specifically — usually "ask a doctor" or "it depends on your
   situation", never "judge for yourself".

**R8 — prefer Chinese authorities; explain foreign ones.** The AUTHORITATIVE tier is
disproportionately English, and naming a foreign journal transmits nothing to this user —
while the source card lands them on a page they cannot read, killing the one affordance for
checking the app's work. So: reach for a Chinese-language authority first when one covers the
claim. When only a foreign source has it, name it **and** say who they are in one clause —
*"美国国家卫生研究院（NIH，美国政府的医学研究机构）的资料里写着……（英文）"*. The registry
therefore carries an `explanation` field per publisher. Note also that the model is
translating the claim, and a confident mistranslation is indistinguishable from a good one —
another reason to prefer Chinese sources for authoritative medical claims.

### 6. The wording carries the trust

In-sentence attribution, the way a knowledgeable person talks. No footnote markers, no
confidence badges. Source card below the answer.

- AUTHORITATIVE → *"iPhone 17 Pro 的电池是 3582mAh，这个是苹果官网上写的，可以放心。"*
- INSTITUTIONAL → *"路透社报道，这款已经在 8 月停产了。"*
- PERSONAL pattern → *"有不少网友说用下来发烫，不过这是个人使用体验，不是测试数据。"*
- LOW only → leads with the status, never a trailing caveat:
  *"这个我没找到权威资料，只有一些个人分享和卖家的说法，仅供参考：他们说……"*
- Conflict → *"这个问题上权威来源本身就有分歧。大家都认可的是……；但在 X 上，A 说……，
  B 说……。这种情况建议问医生。"*
- Nothing found → says so plainly. Does not invent.

### 7–13

7. **Medical splits fact from diagnosis.** Answers the factual half fully and cited — what
   the condition is, typical symptoms, which tests diagnose it. Refuses the personal leap
   ("do I have this"), and names the concrete next step: which specialty, which test. The
   refusal is structural: the answer they wanted is absent, not footnoted, because this user
   skips disclaimers.
8. **Sessions are invisible.** Rolls over after 1 hour idle. A short bridge summary — what
   was being discussed, what was unresolved — rides into the new session, so a back-reference
   still resolves three hours later. The user never sees a boundary.
9. **Three kinds of memory.**
   - *World knowledge* — facts retrieved from search, each stamped with a TTL bucket:
     permanent / one year / one month / always-research. Unclassifiable defaults to
     always-research.
   - *Preference knowledge* — the user's preferences and personal status (current medication,
     allergies, occupation, recurring interests).
   - *Conversation log* — every turn, kept, time-partitioned, searchable. Answers
     "what did I ask you yesterday".
10. **Cached answers.** If live world knowledge answers the question, answer immediately
    without searching. Expired, or the model is unsure of the TTL → search again.
    Re-searching costs three seconds; being confidently stale costs the user's trust in the
    whole app.
11. **Attachments.** Camera or file picker — docx, pdf, jpeg, png — attached to a message.
    OCR via Hydrogen. Discussed in the conversation; durable facts extracted into preference
    knowledge. Large documents are chunked and retrieved within the document. No library UI.
12. **Language** — the product speaks Chinese to the user; the codebase is English.
13. **Backup** — manual export/import file covering memory and logs.

## Conversation behavior

**14. Turn kinds.** Factual → search. Advice → clarify, then search. Emotional register →
comfort first. Crisis → §18. Nothing else routes anywhere special.

**15. Emotional register opens with comfort, then a fork.** When a message carries distress,
it comforts properly — not one perfunctory line — then asks which the person wants:
*"你想先说说吗？还是想我直接帮你分析分析？"*

- Venting → no search, no advice, no clarifying questions. It listens.
- Wanting advice → proceeds to §16.

The fork fires on emotional register, **not** on advice questions generally — "should I buy
an iPhone or an Android" gets no comfort preamble. It fires even when the message is phrased
as a question, because people ask rhetorically when they want to be heard. Phrasing must be
warm; asked coldly this reads as *are you going to cry or shall we get on with it*.

**16. Clarify in one turn, then answer.** Whatever it needs is bundled into a single ask —
never a one-question-at-a-time interrogation. For a "should I quit my job" question: the
financial situation and the source of the stress. It then answers with whatever it got, even
a partial reply. Search only after advice was explicitly requested.

**17. Mental health is medical.** §7's split applies — what the condition is, what screening
exists, cited; refuses the personal diagnosis; names the specialty.

**18. Crisis floor.** On expressed intent toward self-harm, everything above switches off:
no search, no tiers, no citations, no "please see a specialist" bureaucracy. Direct warm
response, a crisis line, and an offer that prompts rather than acts — *"要不要现在给家里人
打个电话？"* The person decides. The app never reports on them.
*Helpline numbers go in only after verification.*

**19. Corrections split by target.** A correction about themselves (their phone model, an
allergy) is accepted immediately as preference knowledge and never argued with. A correction
about the world invalidates that cached fact and forces a fresh search; if authoritative
sources still disagree it holds its ground politely. A user's belief is never written as world
knowledge.

**20. Preference knowledge expires too**, same mechanism as world knowledge: permanent for
allergies, chronic conditions and occupation; six months for current medication and current
projects; one month for transient states. Expired facts are demoted, not deleted, and before
any medical reasoning relies on one it asks whether it is still true. Confirming resets the
clock.

**21. Waiting is narrated.** R6 makes health questions the slowest — two search rounds plus a
long context — so 20–40s is normal for exactly the questions this user asks most. Plain
language, no URLs, no query strings. This is also the only surface where the trust discipline
is visible.

**22. The app makes no privacy statement.** *(Reversed 2026-08-31.)* The operator tells the
user in person that the server is his and that chat content is technically visible to him.
It is not said in the interface.

The earlier decision put that sentence on the activation screen, then on the memory screen.
Both were rejected on the same ground: an unprompted disclosure inside an app built by
someone you know reads as boilerplate, and boilerplate makes a personal tool look *less*
trustworthy, not more. The disclosure still happens — spoken, once, by the person who wrote
the app — which is a stronger guarantee than a line of text the user would skip anyway.

Full logs are still kept during phase 1 for debugging the trust rules.

**23. Never assert an unsourced fact.** If search is unavailable — SearXNG down, timeout,
rate limit — factual questions get *"我现在查不了资料，等一下再问我吧"* and nothing more.
The model's own training knowledge is never used to answer a factual question, because such
an answer looks exactly like a good one minus a source card the user won't notice is missing,
which is Doubao's failure mode reproduced at the worst moment. The app is not dead during an
outage: comfort, venting, advice not resting on facts, and memory with a live TTL all still
work. It just won't assert.

**24. Answer first, briefly, in prose.** The answer and its source land in the first two or
three sentences, then it stops and offers to go deeper. No markdown: no headers, no bullet
lists, no bold. A chat bubble full of formatting reads as a document, not a person. Medical
and conflict answers get more room and still stay prose.

*Why this rule exists:* every other rule in this spec makes answers longer — attribution,
tier-calibrated hedging, pattern qualifiers, disagreement disclosure, "I checked both
directions", foreign source explanations, the medical split. Left alone that compounds into
600 words, and the baseline being replaced is instant and short. Thorough and unread loses to
fast and wrong.

## Non-goals

- No chat list or visible session UI
- No voice input or output in v1
- No document library, folders, or file management
- No on-device LLM — remote Hydrogen only
- No account system: no signup, email, password, or self-serve key issuance
- No automatic cloud sync of memory
- Not a coding or agentic tool — no shell, no code execution
- No multi-user, no sharing

## Accepted risks

**New phone means amnesia.** Memory is local with a manual export file, chosen over
encrypted sync. Realistically the operator moves the file by hand, which works because every
user is personally known. If the group grows past that, this is the first thing to revisit.

## Delivery

Two phases, backend first.

### Phase 1 — `:core`, a pure-Kotlin module with no Android dependencies

Trust engine (R1–R8), memory and TTL, conversation log, SearXNG client, turn routing. Tested
with JVM fixture tests: given this set of search results, does it produce the right tier, the
right threshold, the right answer shape? No emulator, no UI, runs in seconds.

Keeping `:core` Android-free is what makes that possible, and it has one design consequence:
storage sits behind an interface, with the Android SQLite implementation in `:app`.

**Phase 1 is done when** the fixture suite covers every rule in §5 and passes, *and* a test
harness can answer a real question end to end against the live SearXNG and Hydrogen — so the
rules are proven against real search results, not only against fixtures written to suit them.

### Phase 2 — frontend

Compose UI in areel.org's design language: flat concrete grey, one magenta sweep, near-black
band, checkerboard, CAD hairlines. Layout follows §2 and §6 — one endless conversation,
in-sentence attribution, source card below.

### Field check, once it's on the phone

The family member uses it for a week instead of Dora, and reading their log there is at least
one answer where the app said it found no reliable source, or that it checked both
directions, instead of inventing something. That's the trust discipline firing in the wild
rather than in tests.
