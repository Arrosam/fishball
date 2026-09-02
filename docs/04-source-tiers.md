# Source tiers — publisher-first

Data: [`data/source-tiers.json`](../data/source-tiers.json), loaded by `:core`.

**v8** · 60 publishers · 22 platforms · 10 publisher patterns · 4 scope rules

Chinese appears in this data only as `displayName`, `explanation` and `accounts` — the text
the product speaks to the user. Everything structural (topics, verification descriptions,
notes, tier names) is English.

## The four bands

| band | | what belongs in it |
|---|---|---|
| `AUTHORITATIVE` | 权威 | The United Nations and its agencies, the national academies, PubMed, and the major academic journals. Where a finding is established and recorded — not "important organisations". The only band one source can carry an answer on alone, which is why it is kept narrow. |
| `HIGH` | 高 | Governments and their departments, the state broadcasters and news agencies, Wikipedia, and a major manufacturer describing its own product's specifications. What a ministry publishes is a decision; the band above is for what is known. |
| `MEDIUM` | 中 | Major news institutions, major self-media, 百度百科, and institutions that are not the record — professional outlets, indexes, preprint servers. Needs corroboration before anything is stated from it. |
| `LOW` | 低 | Personal posts, and everything else. |

The ordinal positions are load-bearing: every threshold in `:core` is written as
`>= Tier.HIGH` or `< Tier.HIGH`, so the bands may be renamed but not reordered.

A manufacturer sits at `HIGH` for its own specifications and drops a band for anything
evaluative. It is the best source alive for what is in the box and a party to the sale;
`AUTHORITATIVE` is for the record, not for the seller.

## The model

**The publisher is the source. A domain is only a shortcut for identifying one.**

A government health authority's site isn't authoritative because of its domain — it's
authoritative because it *is* that authority, and the domain is how you know. That
distinction is invisible while every source lives on its own domain, and it becomes the whole
architecture the moment platforms enter.

**Platforms are venues and carry no tier.** Baijiahao, WeChat Official Accounts, Sohu-hao,
Zhihu host thousands of unrelated publishers. A health authority posting on Baijiahao is
still that authority; an anonymous account there is still anonymous. Tiering the venue answers
the wrong question.

This also fixes spec R5 for free. Corroboration counts independent *publishers* — so an
authority's website, its official account and its syndicated feed are **one** source, which is
correct and which domain-counting got wrong.

## Resolution

```
1. Domain is a platform?  → identify the publishing account
                           → verified, or in the registry?
                              yes → that publisher's tier, capped at HIGH
                              no  → platform.defaultTier
2. Domain belongs to a registry publisher?   → that publisher's tier
3. Domain matches a publisherPattern?        → that pattern's tier
4. Registrable name matches a brand in the query? → R3-brand-official
5. Otherwise → LOW   (promotable to HIGH with a stated reason, spec R2)
```

Step order matters: `pubmed.ncbi.nlm.nih.gov` is a registry publisher (an *index*, HIGH) and must resolve at step 2 before `*.gov` promotes it at step 3.

## LOW is the complement

Nothing enumerates bad domains — unwinnable, they appear faster than anyone maintains a list.
Anything unmatched is LOW. Under publisher-first there is no pinned-LOW list either: content
farms moved to `platforms` because that is what they are.

## The security rule

**`no-promotion-by-name`.** A platform account that merely *resembles* a registry publisher
gets nothing. Promotion above the platform floor requires a **verification signal** — the
platform's own certification badge, a displayed professional credential — or an exact match in
that publisher's `accounts` list.

An account whose name is a near-miss of a national health authority is not that authority. On
an app that answers medical questions for someone who cannot check, a fuzzy name match
granting AUTHORITATIVE is the worst failure available, and it is the one an attacker would aim
for.

Two consequences:

- **`accounts` lists start near-empty on purpose.** Three are seeded, from publishers whose
  official account names are unambiguous. Populate the rest from *observed* results in Phase 1
  rather than from guesses — a wrong entry here hands AUTHORITATIVE to an impostor.
- **`platform-publisher-cap`** holds a platform-identified publisher at HIGH even when
  verified. A secondary channel is never the primary record, and it bounds the damage if
  identification is ever fooled.

## The other three scope rules

**R3-brand-official** — a domain whose registrable name matches the brand in the query is that
brand's official site: AUTHORITATIVE for specifications, ingredients, price, availability and
warranty; HIGH for anything evaluative. Covers unboundedly many manufacturers without
listing them.

**seller-efficacy** — any publisher that sells, or takes commission on, the thing being asked
about is LOW for efficacy, health-benefit and weight-loss claims, whatever tier it otherwise
holds. It can demote an AUTHORITATIVE publisher: a pharma company is authoritative for its
drug's ingredients and LOW for its drug's efficacy.

**Owner groups** — seven corporate families; members count as one source for R4 thresholds.
Still useful alongside publisher-first, for the reposting case where no publisher is
identifiable at all.

## The open engineering risk

**Half the platforms have a verification signal that exists on the page — and it is unknown
whether SearXNG surfaces it.** 10 of 21 platforms have real certification mechanisms, but if a
WeChat result arrives as a bare title and URL with no account name, every one of them falls to
`defaultTier` and the publisher-first model quietly degrades back to venue-tiering.

That is the single most important thing for Phase 1 fixtures to establish, and it is
answerable against real results from search.areel.org rather than against hand-written
fixtures. If account names don't come through, the fallback is fetching the result page rather
than trusting the snippet — more latency, on a path that already runs two search rounds.

## Still thin

- **Individual creators can't be enumerated by domain.** They live on platforms as accounts.
  The mixed-domain rule now handles them properly — publisher, not domain.
- **Predatory clinics and supplement sellers have no clean list.** `seller-efficacy` is the
  right shape of mitigation but depends on the model recognising that a site sells the thing.
- **Regional `*.gov.cn` and `*.gov.hk`** all inherit AUTHORITATIVE from the pattern. A county
  page is not a national ministry.
- **Journal coverage is English-heavy.** Chinese-language core journals are reachable only
  through CNKI, which sits at HIGH as an index. Per spec R8 Chinese authorities are
  preferred, so this gap bites exactly where it shouldn't.

## Maintaining it

Log every unresolved domain and every unmatched platform account, with counts. Frequent
entries earn a registry line or confirm as LOW. That log is the honest coverage metric — if
most results resolve at step 5, the list isn't working yet.

Any change bumps `version` and needs a fixture test: search results in, expected tier and
answer shape out.
