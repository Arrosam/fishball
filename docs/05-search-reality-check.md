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

---

# Addendum — 2026-08-31

Re-ran the probes while wiring the app up. Two things below correct the findings above.

## 0. A warning about how these were measured

The first re-run appeared to show the instance corrupting every Chinese query: `甲亢` came
back echoed as `%BC..%BA`, which are its GBK bytes. It was not the instance. Git Bash on this
machine was transcoding the argument before curl ever saw it, and a query sent as a
pre-encoded literal (`q=%E6%97%A5%E6%9C%AC`) round-tripped perfectly.

**The instance handles UTF-8 correctly.** `query` echoes back byte-identical on every probe
below. Anyone re-testing this should drive it from a script that owns its own encoding, not
from a Windows shell — otherwise you will measure your terminal.

## 1. The engine situation has changed, and §1 above is now too pessimistic

Same query (`甲亢 确诊 靠什么检查`), engines forced individually:

| engine | results | reaches |
|---|---|---|
| google cse | 20 | Hong Kong only — hkah.org.hk, trinitymedical.com.hk |
| **brave** | **20** | **mainland — haodf.com, rmhospital.com, med66.com** |
| **360search** | **5** | **mainland — 39健康网, 复禾健康, 120ask.com** |
| bing | 10 | irrelevant (returned Instagram for a thyroid query) |
| duckduckgo | 0 | CAPTCHA |
| startpage | 0 | Suspended: CAPTCHA |
| wikipedia | 0 | Suspended: access denied |

**brave and 360search work, and they reach the mainland Chinese web that google cse cannot.**
Brave was reported dead above and was still intermittently reporting "too many requests"
during this session, so it recovers rather than staying suspended.

This does not overturn §2 — google cse really is Hong Kong-skewed, and every result it
returned here was `.hk`. What it overturns is the conclusion drawn from that: the corpus is
not unreachable, it was unreached because one engine was doing all the work.

The DuckDuckGo/Startpage CAPTCHAs are the ordinary condition of a SearXNG instance on a
datacenter IP and are not worth chasing. Enabling brave and 360search, and dropping bing,
is worth more than fixing either of them.

## 2. What this means for the tier list

§2 above concluded the v3 list was "calibrated for a web this instance cannot reach". With
brave and 360search enabled that stops being true, and the mainland/Hong Kong calibration
question becomes a real decision again rather than a moot one.
