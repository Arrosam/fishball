# Search reality check — 2026-08-30

Ran 8 live queries against `search.areel.org` before writing `:core`, to test whether the
publisher-first tier model survives contact with real results. It mostly doesn't, yet — for
reasons that have nothing to do with the model.

## 1. The instance is not doing metasearch

83 engines enabled. **100 out of 100 results came from `google cse`.** Every query returns
the same `unresponsive_engines`:

```
brave      → Suspended: too many requests
duckduckgo → CAPTCHA
startpage  → Suspended: CAPTCHA
```

Forcing them individually (`&engines=duckduckgo`) confirms it: 0 results each.

**This is very likely caused by the open JSON API flagged in spec §5.** An unauthenticated
public instance gets scraped; the upstream engines see the abuse and CAPTCHA or suspend it.
The token/rate-limit fix now has a second, larger payoff than tidiness — it may be what
restores multi-engine search.

It also weakens spec R4/R5 at the root: corroboration counts *independent sources*, but every
candidate currently comes from one index. Independence of publishers still holds; independence
of retrieval does not.

## 2. Google cannot see the mainland Chinese web

Zero platform results across all queries — **no WeChat Official Accounts, no Baijiahao, no Zhihu, no Weibo.** Not
one. That is not a ranking accident: Google indexes those platforms poorly or not at all.

Worse, results skew hard to **Hong Kong**, and stay there even when the query is explicitly
mainland-framed. A query naming the mainland health ministry and a hypertension guideline returned `change4health.gov.hk`,
`healthbureau.gov.hk`, `chp.gov.hk` — and **zero** `nhc.gov.cn`.

Observed hosts across the health queries: `stheadline.com` (12), `hk01.com` (10),
`drugoffice.gov.hk` (6), plus a long tail of `.hk` hospitals and clinics.

So the v3 tier list is calibrated for a web this instance cannot reach, and the real returned
corpus — Hong Kong government health bodies, Hong Kong press, traditional Chinese - was almost
entirely unlisted and resolving to LOW.

## 3. `author` exists in the schema and is always empty

The field I set out to test **is** in SearXNG's result objects, alongside `publishedDate`,
`length`, `views`, `metadata`. It was empty in **100/100** results, because `google cse`
doesn't populate it.

The publisher-first restructure is still right — it's just untestable until platform results
appear at all. If Chinese engines get enabled, retest before assuming account names arrive.

## 4. SearXNG 403s without a browser User-Agent

`Python-urllib/3.x` → `403 Forbidden`. A Chrome-like UA works. The Android client must send a
real `User-Agent`; the current `SearxngClient` does not set one.

## What to do

**Yours:**

1. **Rate-limit / token the instance.** Named in spec §5 as hygiene; it is now the prime
   suspect for three suspended engines.
2. **Decide the target region.** Is your family member reading simplified-Chinese mainland sources or
   traditional-Chinese Hong Kong sources? Everything downstream depends on it, and right now
   the instance answers
   HK while the tier list assumes mainland.
3. **If mainland: enable Chinese engines** — `baidu`, `360search` (already in config,
   disabled), `sogou`. Without one, the mainland web is invisible and the platform half of
   v3 stays dead code.

**Mine, already done:** added `*.gov.hk` as an authoritative pattern plus the HK publishers
actually observed. `drugoffice.gov.hk` was resolving to LOW because `*.gov` does not match
`.gov.hk` — a real bug this check caught.

**Mine, pending your answer to (2):** recalibrating the registry toward whichever web the
instance actually serves.

## Why this didn't block `:core`

The resolver is data-driven — the registry is a JSON file it loads. Which sources come back
changes the *data*, not the algorithm. Building `:core` now is safe; the fixtures that pin
the tier list should be written against real captured results once the region question is
settled.

This is exactly the failure the Phase 1 done-when was written to catch, and it caught it
before a line of code, which is the cheapest place to catch it.
