# FishBall — design directions, UI pass 2 · **round 2**

**Date:** 2026-08-31 · **Status:** revision to the author's point-by-point review of round 1
**Inputs:** the round-1 review (verbatim), `03-product-spec.md` (§6, §21, §24), `docs/preview/designs.html` (the reviewed board), areel.org tokens

Everything below stays inside the given token set unless explicitly argued otherwise:

```
--concrete #c9c9c5 · --concrete-2 #d3d3cf · --paper #f2f2ee
--ink #101010 · ink-60/40/20/10/06 = rgba(16,16,16,.60/.40/.20/.10/.06)
--magenta #ec0a6e · --magenta-lo rgba(236,10,110,.14)
--rule: 1px solid ink-20 · Archivo (display) · JetBrains Mono (labels)
border-radius: 0 everywhere · checkerboard cell 8px
```

One shared motion vocabulary, used by every section (full table in Appendix 1):

| token | value | used for |
|---|---|---|
| `ease-mech` | `cubic-bezier(0.2, 0, 0, 1)` | anything that moves once (Compose: `CubicBezierEasing(0.2f, 0f, 0f, 1f)`) |
| `step` | no easing — discrete jump | checker tide, bar cell fill |
| `linear` | `linear` | ambient loops (border lap, crosshair drift) |
| durations | 120 / 220 / 600 ms; loops 900 ms pulse / 1200 ms-per-step tide / ~5.6 s lap / 56–74 s drift | small / structural / reveal; ambient |

No per-frame blur, no particles anywhere. Every loop below is a compositor-only transform of
a tiny layer (translate a 1px line), a repaint of a strip ≤ 8dp tall at ≤ 1 Hz, or one
bubble-sized stroke redraw (the pending lap, costed in D). Round 1 said "no blur, no
shadow-blur" flatly; round 2 amends: the glass shell (S) carries the site's own baked soft
shade and drop — **static, drawn once, never animated** — and that is the only blur-shaped
thing in the app. The shell has no `backdrop-filter`; nothing blurs per frame.

---

## Round 2 — what changed

| # | Author's call | What this revision does |
|---|---|---|
| 1 | Logo: **H3 polygon fish chosen**, rotate 45°, head to upper-right | H rewritten — coordinates recomputed (no transform wrapper), every edge re-snapped to 0/45/90, 20px + checkerboard-adjacency checked, rendered |
| 2 | Meander brain **confirmed** | E3's literal-dome alternative deleted |
| 3 | Bubbles: **more options** | Five new directions **B5–B9** added and rendered; B1 stays a candidate; B4 (radius) deleted — settled, no radii |
| 4 | Meter **approved, add labels** | 置信度 / 各有说辞 caption spec (size, colour, placement); §6 amendment flagged, not resolved |
| 5 | Pending line must **lap the whole border** | Top-edge shuttle replaced by a border lap: `stroke-dasharray` (HTML) / `PathMeasure` (Compose), exact segment, period, easing, cost + mitigation |
| 6 | **A2 rejected**; develop A1 + A3; fill the empty gate | A2 deleted everywhere. A1 and A3 fully specced and rendered; four background fills proposed and rendered; pairing recommended |
| 7 | Memory affordance: **no tilt, give it a plate** | −8° removed; 44dp paper plate — fill, padding, inversion, pressed state — specced and rendered |
| 8 | **Privacy disclosure removed from the app** | Footer and every §22 reuse deleted; no alternative home proposed; footer slot left **empty**, argued below; spec §22 amendment flagged |
| 9 | Scrollbar **slightly slimmer** | HTML thumb 7px → **5px**; Compose transient thumb stays 3dp × 32dp (both stated) |
| 10 | *(added mid-round)* The **glass shell** and the **double CAD grid** are core areel.org and missing; the design is "too empty" | New section **S**: exact shell recipe (verified from the live site) + Compose mapping, glass allocated surface by surface, screws budgeted; the double grid (96px major / 16px minor) becomes the app-wide ground and replaces A1's guessed 28px grid; the gate rebuilt as grid + one large cropped object + glass input; new bubble direction **B10 "glass plate"** |

**Reservations** — each item is implemented exactly as directed; noted once, one line each:

- **#4** I would have captioned only CONFLICT and kept the scalar meter wordless — a repeated
  置信度 on every answer is the closest the system now comes to the badge §6 bans. Implemented
  both labels as directed; §6 flag below.
- **#8** No objection to removal itself, but spec §22 still reads "privacy is disclosed at
  first login" — the spec needs an out-of-band note. Flagged in F, not resolved here.
- **#1** Round 1 worried the fish "pulls toward cute"; recut at 45° it reads more plotter-mark
  than mascot, so the reservation is largely withdrawn.

---

## 0. Recommended set — summary

> **Round 2:** table rewritten to the review's decisions.

| Item | Round-2 state |
|---|---|
| **S** Ground + glass | *(new)* Double CAD grid (96/16, ink-06) is the app-wide ground; the site's glass shell becomes a system surface — gate input, memory ledger, source card; screws on the memory ledger only |
| **A** Gate | A2 deleted. **A1 "drafting table"** and **A3 "checker tide"** fully developed over the real double grid; the empty field is filled by **one large cropped object** (three options rendered). Recommend **A3 + A-bg1 watermark fish**, glass input in every variant |
| **B** Bubbles | Field widened to **seven live options**: B1 chamfer + new B5 overshoot, B6 margin note, B7 register corners, B8 checker cut, B9 baseline shelf, **B10 glass plate**. B4 radius deleted — settled. My ranking: **B10 › B1 › B8** |
| **C** Confidence | **Approved.** 4-cell scalar meter + C-a fault line ship; captions added — 置信度 (scalar), 各有说辞 (fault line), both author-approved strings. §6 amendment flagged |
| **D** Progress | **Approved.** Pending bubble as specced, except the top-edge shuttle is replaced by a **24px magenta segment lapping the full outline** (chamfer included) |
| **E** Icons | **Approved.** Meander brain confirmed; literal dome deleted; E1/E2/E4 unchanged |
| **F** Memory | Tilt deleted. Brain + 记忆 on a **44dp paper plate**, pressed = concrete-2 + 1dp offset. Ledger becomes a glass shell with corner screws. Privacy footer deleted; slot left empty (argued) |
| **G** Scrollbar | HTML thumb **5px**; Compose transient 3dp × 32dp fixed-length thumb unchanged |
| **H** Logo | **H3 polygon fish**, recut on the 45° axis, head to the upper-right; H1 retired to future house mark, H2 retired |

Dark mode: per `Theme.kt`'s stated philosophy, dark inverts only the ground (ink band stays,
concrete → near-black, paper → #1A1A1A) and keeps every ink/magenta relationship. Every spec
below is written for light; where a colour role flips it is noted inline.

---

## S. System surfaces — the double grid and the glass shell

> **Round 2:** new section, from the author's mid-round addition. Two verified facts from
> the live site anchor it: the ground is a **double CAD grid**, and the card surface is a
> **layered translucent shell with no backdrop-filter**. The unused `--glass:
> rgba(255,255,255,.42)` token in `:root` is a red herring — the real recipe is the
> gradient stack below; do not use the flat token.

### S1 — the ground is engineering paper, everywhere

The site body is not bare concrete; it is concrete under a two-scale grid — 96px majors,
16px minors, both 1px ink-06:

```css
body{
  background:var(--concrete);
  background-image:
    linear-gradient(rgba(16,16,16,.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(16,16,16,.06) 1px, transparent 1px),
    linear-gradient(rgba(16,16,16,.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(16,16,16,.06) 1px, transparent 1px);
  background-size:96px 96px, 96px 96px, 16px 16px, 16px 16px;
}
```

**Ship this as the app-wide ground**: the thread, the gate, and the memory screen all sit on
it. It directly answers "too empty" at zero motion cost, it is what makes translucent
surfaces legible (glass over a plain field is just a grey rectangle — glass needs something
behind it to be glass), and it turns every hairline in the app into a mark *on* a drawing
rather than a border *around* a widget. The near-black band and the checker strip stay
grid-free (opaque ink).

Compose: one static `drawBehind` on the root surface — two nested loops of `drawLine` at
16dp/96dp pitch, ink-06, drawn once per size change; zero per-frame cost. (dp, not px: on
the phone the site's px values read 1:1 as dp.) The site's 16 into 96 divides exactly
(6 minors per major); keep that ratio.

### S2 — the glass shell, verbatim recipe

Verified computed CSS from the live site (`.spec.shell.screws`), reproduced exactly — this
is the HTML-side spec:

```css
.shell{
  position:relative; isolation:isolate;
  background:linear-gradient(148deg,
      rgba(255,255,255,.26) 0%,
      rgba(255,255,255,.06) 46%,
      rgba(255,255,255,.18) 100%);
  border:1px solid rgba(255,255,255,.6);
  box-shadow:
      rgba(255,255,255,.7) 0 1px 0 inset,             /* top light lip   */
      rgba(16,16,16,.10)   0 0 0 1px inset,           /* inner hairline  */
      rgba(16,16,16,.40)   0 -16px 30px -28px inset,  /* bottom shade    */
      rgba(16,16,16,.45)   0 22px 40px -32px;         /* soft drop       */
}
.shell::before{                                        /* specular streak */
  content:""; position:absolute; inset:0; z-index:-1; pointer-events:none;
  background:linear-gradient(112deg, transparent 40%,
             rgba(255,255,255,.22) 46%, rgba(255,255,255,0) 52%);
}
.shell.screws::after{                                  /* four corner screws */
  content:""; position:absolute; inset:10px; pointer-events:none;
  background-image:radial-gradient(circle, var(--ink-20) 0 1.5px, transparent 1.6px);
  background-position:0 0, 100% 0, 0 100%, 100% 100%;
  background-size:4px 4px; background-repeat:no-repeat;
}
```

What matters for budget: **no `backdrop-filter`**. The glass is translucent white layers +
inset shadows + one diagonal streak — no blur pass, so it costs what any gradient fill
costs. Do not "improve" it with real blur.

### S3 — Compose mapping, exact

Inset shadows have no native equivalent; the shell is one `drawBehind` plus a border and a
cheap render-node drop shadow. CSS gradient angles convert with one helper (CSS measures
from "to top", clockwise; y is down):

```kotlin
private fun DrawScope.cssLinear(deg: Double, vararg stops: Pair<Float, Color>) {
    val th = Math.toRadians(deg)
    val d = Offset(sin(th).toFloat(), -cos(th).toFloat())
    val len = abs(size.width * d.x) + abs(size.height * d.y)
    val c = size.center
    drawRect(Brush.linearGradient(
        *stops, start = c - d * (len / 2), end = c + d * (len / 2)))
}

fun Modifier.glassShell(drop: Boolean = true): Modifier = composed {
    this
        .then(if (drop) Modifier.shadow(6.dp, RectangleShape, clip = false)
              else Modifier)                       // ≈ the CSS soft drop; render-node
        .drawBehind {                              // shadows are GPU-cheap and static
            // specular streak (CSS ::before, z-index:-1 → drawn first, under the fill)
            cssLinear(112.0,
                .40f to Color.Transparent,
                .46f to Color(0x38FFFFFF),         // white .22
                .52f to Color.Transparent)
            // shell fill
            cssLinear(148.0,
                0f   to Color(0x42FFFFFF),         // white .26
                .46f to Color(0x0FFFFFFF),         // white .06
                1f   to Color(0x2EFFFFFF))         // white .18
            // bottom inner shade — the 30px-blur inset approximated as a 24dp ramp
            val sh = 24.dp.toPx()
            drawRect(Brush.verticalGradient(
                    0f to Color.Transparent, 1f to Color(0x33101010),
                    startY = size.height - sh, endY = size.height),
                topLeft = Offset(0f, size.height - sh), size = Size(size.width, sh))
            // inner ink hairline (1px ring)
            drawRect(Color(0x1A101010), style = Stroke(1.dp.toPx()))
            // top light lip
            drawLine(Color(0xB3FFFFFF), Offset(1.dp.toPx(), 0.5f * 1.dp.toPx()),
                Offset(size.width - 1.dp.toPx(), 0.5f * 1.dp.toPx()), 1.dp.toPx())
        }
        .border(1.dp, Color(0x99FFFFFF))           // white .6
}

fun Modifier.screws(): Modifier = drawWithContent {
    drawContent()
    val inset = 10.dp.toPx() + 1.5.dp.toPx()
    val r = 1.5.dp.toPx()
    listOf(Offset(inset, inset), Offset(size.width - inset, inset),
           Offset(inset, size.height - inset),
           Offset(size.width - inset, size.height - inset))
        .forEach { drawCircle(Color(0x33101010), r, it) }
}
```

Stated approximations (both invisible at arm's length, both static): the CSS bottom shade's
30px blur becomes a 24dp linear ramp capped at ink-20-equivalent alpha; the CSS drop
(`22px 40px −32px`) becomes `shadow(6.dp)`. If even the render-node shadow offends on a
given device, the flat fallback is a 1dp ink-20 rule under the bottom edge — the shell
survives without its drop.

### S4 — which surfaces are glass

Glass everywhere would be as monotonous as concrete everywhere. The rule that allocates it:
**glass is for display cases — surfaces that hold verified content up for inspection.
Controls are plates; ground is paper.**

| Surface | Material | Why |
|---|---|---|
| **Gate input** | **Glass** (no screws) | The one focal object on the gate; a pane you type your key into. Keeps the author-approved 2dp ink border — glass replaces only the paper fill; focus still swaps the border to 2dp magenta. The shell's own 1px white border is dropped here (superseded by the ink frame); lip, streak and shade stay |
| **Memory ledger** | **Glass + screws** | The app's one archival surface — facts held over time, bolted down. The single place screws ship (S5) |
| **Source card** | **Glass** (no screws) | The site's `.spec` card *is* a source card: provenance under glass, inside the answer. At ~48px tall there is no room for 10px-inset screws anyway |
| **Assistant bubble** | **Candidate** — B10 | Argued as a bubble direction, not decreed here; see B10 and the ranking |
| 确认 button, tabs, send plate, memory plate | Flat plates | Controls press; glass doesn't. The magenta/ink plate language stays |
| User bubble | Flat | The user's words are print, not exhibit — the material split does speaker work (B10) |
| Band, checker, pending hatch | Opaque | The masthead is ink; the pending bubble is *unresolved* material — hatched drawing, not yet under glass |

### S5 — screws, budgeted

Corner screws read as "permanently mounted". Used once, they mean something; used
everywhere, they are wallpaper. **Ship screws on the memory ledger shell only.** Explicitly
screwless: the gate input (a door, not an archive), source cards (too small, and answers
are current, not mounted), any bubble. If a second screwed surface ever appears, it should
be a future "about this app" panel — nothing in v1.

---

## A. Activation gate

> **Round 2:** A2 "signal sweep" rejected by the author and deleted (its motion row, CSS and
> Compose snippet are gone from this document and the board). A1 and A3 are now fully
> developed peers. The bare-background problem is answered the way the site answers it —
> **grid + one large cropped geometric object + glass** (S): the double grid is the ground
> in every variant, the input becomes a glass pane, and three cropped-object options are
> rendered. Round 2's earlier sheet-registration / dimension-line / checker-field fills were
> drafted and then dropped as filler next to that pattern. The A2 press payoff survives,
> ported to A3.

Target content after the copy deletion: wordmark · input (`请输入激活码`, bold black border) ·
button (`确认`). Nothing else. The screen's job is thirty seconds of first impression, once.

Shared layout (unchanged from round 1):

```
┌──────────────────────────────┐
│                              │   background: --concrete under the S1 double
│                              │   grid (96/16, ink-06) — every variant
│   FISHBALL                   │   Archivo Black 40sp, tracking −1
│   ▓░▓░▓░▓░▓░▓░▓░▓░▓░▓░       │   checker band 8dp, full content width
│                              │   gap 36dp
│   ┌────────────────────────┐ │   input: 56dp tall, GLASS SHELL fill (S2),
│   │ 请输入激活码           │ │   framed by the kept 2dp --ink border;
│   └────────────────────────┘ │   focus → border 2dp --magenta; no screws
│                              │   gap 14dp
│   ┌────────────────────────┐ │   button: 54dp, --magenta fill, --paper text
│   │          确认          │ │   17sp SemiBold; disabled = ink-20 fill
│   └────────────────────────┘ │   pressed = content offset +1dp x/y (plate press)
│                              │
└──────────────────────────────┘   column centred vertically, 28dp side padding
```

The input's glass detail: the shell recipe minus its own 1px white border (the 2dp ink
frame supersedes it); lip, streak, fill and bottom shade as S2; the grid reads faintly
through the pane, which is the point — the key is typed onto the drawing.

Input text (the key itself is latin): JetBrains Mono 18sp, letter-spacing 1.5sp. Placeholder
请输入激活码: Archivo/display 15sp, ink-40. Chinese never renders in JetBrains Mono anyway (no
CJK coverage), so CJK strings get the display font deliberately rather than by fallback
accident.

### A1 — "Drafting table" (plotter crosshair) — fully developed

The ground is the S1 double grid — **96px majors / 16px minors, 1px ink-06** (round 1
guessed a single 28px grid; the live site's real values replace it, and S1 makes them the
app-wide ground rather than a gate special). Over it, a plotter crosshair: one full-height
vertical hairline and one full-width horizontal hairline at ink-10, drifting independently.
Where they cross, a 5×5px ink-20 square tick rides the intersection.

- Vertical line x: 15% → 85% of width, **56 s**, `linear`, reverse-repeat.
- Horizontal line y: 20% → 80% of height, **74 s**, `linear`, reverse-repeat.
- Different periods so the pattern never visibly loops (co-prime-ish: full figure repeats
  every ~17 min).
- Tick: 5×5px ink-20, centred on the intersection, derived from the same two animated values
  — never independently animated.
- **Focus behaviour** (now part of the spec, not optional): when the input gains focus, both
  lines animate (600 ms, `ease-mech`) to the input's top-left **outer** corner and park —
  the drawing registers the active element; the tick rests on the corner. On blur they tween
  back (600 ms, `ease-mech`) to where the drift clock — which never pauses — currently is.
- Z-order: grid ‹ lines ‹ tick ‹ plates. Input and button are opaque paper/magenta, so
  occlusion is free.

Compose: grid via one static `drawBehind` (drawn once per size change). The two lines are
1dp `Box`es inside `graphicsLayer { translationX/Y = … }` driven by
`rememberInfiniteTransition` — compositor-only, no repaint. Park = an `Animatable` override
that takes precedence over the drift value while focused. Tick is a third 5dp `Box` whose
offset derives from the same two floats.
HTML: two absolutely-positioned 1px divs, `@keyframes` on `transform`, `animation: 56s/74s
linear infinite alternate` (the board demos at 4× speed and animates `left/top` for brevity —
production uses transforms).

Now that the grid is the ground everywhere (S1), A1's contribution is no longer the grid —
it is the *instrument* moving over it. That changes its standing: A1 is a motion option like
A3, not a background fill, and it composes with a cropped object the same way (with the
caveat in the pairing note).

### A3 — "Checker tide" — fully developed

The checker band under the wordmark grows a shadow row: a second 8px band, cells ink-10,
sitting 2px beneath the main band, which advances one cell (8px) leftward every **1200 ms** —
stepped, never tweened, like film sprockets or a conveyor. One discrete jump per 1.2 s;
everything else static.

- Main band: 8px cells, solid ink, static (exists today).
- Tide row: 8px tall, full band width, cells ink-10, `background-position-x` −8px per step.
- **Press payoff** (ported from dead A2): while 确认 is pressed with a non-empty key and the
  key is being verified, the tide doubles rate to 600 ms/step — the conveyor works harder.
  Honest, cheap, and it ends when verification ends.

Compose: `LaunchedEffect { while (true) { delay(1200); phase = (phase + 8) % 16 } }` and the
existing `CheckerBand` Canvas gets a `translate(left = -phase)` — repaints an 8dp-tall strip
under 1 Hz, effectively free. Verification switches the delay to 600.
HTML: `animation: crawl 2.4s steps(2, end) infinite;
@keyframes crawl{to{background-position-x:-16px}}` (two 8px jumps per cycle).

### The bare background — grid + one large cropped object

After the copy deletions the gate is wordmark, input, button and a lot of bare field. The
site's own answer, now adopted wholesale: the field is never empty because it is always
**grid plus one big cropped geometric object**. The grid is settled (S1, every variant).
The object — exactly one, large, bleeding off an edge, static — has three candidates, all
rendered:

**A-bg1 — Watermark fish** ← recommend. The H mark (now the 45° fish) as an architectural
supergraphic: rendered 260×260px from the 24-grid, single colour **ink-06** laid *over* the
grid (mass survives 6% alpha because of sheer size; the grid reads through it, which keeps
it ground, not sticker), anchored `right: −48px` (cropped by the screen edge), `bottom:
56px`, behind the plates. The eye is **punched out** (`fill-rule="evenodd"` hole — grid
shows through), because at 6% alpha a coloured eye would either vanish (ink) or read as a
stain (magenta). The brand states itself at the one brand moment the app has, at the volume
of a wall graphic, not a splash screen.

**A-bg2 — Cropped arc.** The site hero's own object, quoted: a **magenta arc bleeding off
the top-right corner** — circle centre at `(width + 40px, −60px)`, radius 170px, stroke
**14px solid #ec0a6e**, butt caps, cropped by both edges — with three faint concentric
technical circles behind it, same centre, radii 120 / 210 / 260px, 1px ink-10. The most
site-literal option and the boldest. Named risk: it doubles the screen's magenta (arc +
确认 button). The site spends its magenta exactly this way, so it is defensible — but it is
the one option where the background competes with the action for the accent.

**A-bg3 — Oversized quadrant.** H1's retired 2×2 quadrant as supergraphic: two opposing
150×150px cells (300×300 total), **ink-06**, anchored off the top-right corner (`right:
−90px, top: −90px`), cropped. The most anonymous of the three — house-mark energy, says
areel, not FishBall.

Drafted and dropped as filler next to this pattern (recorded so they stay dead): sheet
registration corners, dimension-line annotations, a bottom checker field — all three
decorate the emptiness instead of occupying it, and none survives sitting next to a
296px cropped object.

### Pairing recommendation

**Ship A3 + A-bg1 + glass input.** The gate is the product's one brand moment, seen exactly
once for thirty seconds: grid ground, one giant ghost fish off the right edge, the key
typed into a glass pane, and the tide's quiet mechanical motion in the one banded element.
A1 + A-bg2 is the coherent alternate — instrument over drawing with the site's own arc —
for an author who prefers the drafting-room posture; its caveat is that the crosshair will
cross the arc's circles and the intersection tick briefly rides foreign geometry (harmless,
visible, judge on the board). A-bg3 is the priced fallback if the fish fails in the flesh.

---

## B. Message bubbles

> **Round 2:** the author asked for a wider field. B1 stays a candidate (unchanged values);
> B2/B3 remain as round-1 leftovers, now rendered on the board too; **B4 radius is deleted —
> the inside-the-language question is settled, no radii, no blobs**; six new directions
> B5–B10 follow (B10 added with the mid-round glass addendum), all rendered side by side
> with real Chinese text over the real gridded ground. Every option keeps the bubble's
> top-right interior free, where the C meter lives, and every option must read speaker
> identity **without colour** — each spec names its colour-free cue.

Baseline for all directions (from round 1): assistant carries the content and the source
card; user turns get `max-width: 85%` aligned right; existing fills (paper / magenta-lo over
paper = `#f1d2dc`), 16dp interior padding, §24 prose (no markdown), 12dp thread gap. B6 and
B9 amend the width rule and say so.

### B1 — "Chamfer + weight" (round-1 candidate, unchanged)

One corner cut at 45°, on the speaker's side, stroke weight concentrated on the cut edge.
Cut 14px; heavy edge 3px butt caps (--ink / --magenta); other edges 1px (ink-20 / magenta);
assistant cut top-left, user cut bottom-right. Compose `CutCornerShape` + one `drawLine`;
HTML clip-path + inset layer + 14px SVG edge overlay. Full values and code stand as written
in round 1. **Colour-free cue:** which corner is cut + the weighted edge's position.

### B2 — "Tab index" (round-1 leftover, now rendered)

Plain hairline rectangle; a solid square tab 24×10px protrudes above the top edge, flush to
the speaker's side (assistant left/ink, user right/magenta). Compose: two welded `Box`es or
one `GenericShape`. **Colour-free cue:** tab side. Reservation from round 1 stands: the
user-side tab carries no semantics — decoration on one side, meaning on the other.

### B3 — "Offset plate" (round-1 leftover, now rendered)

Rectangle + duplicate plate offset +5px/+5px behind it, zero blur (assistant ink-10, user
magenta-lo). HTML `box-shadow: 5px 5px 0`. Quietest option; best as a layer on B1, not
alone. **Colour-free cue:** weak (shadow side only) — noted as its main flaw.

### ~~B4 — Radius~~ — deleted

Settled by the author: the bubbles stay inside the areel.org language. No radii, no blobs.

### B5 — "Overshoot" (new — a rule that runs past the corner)

Plain rectangle, but the two edges meeting at the speaker's corner are drawn at full
strength and **overshoot the corner by 10px** into open space, the way plan drawings let
wall lines cross at corners. The crossing makes a small drafting cross hanging off the
bubble.

```
──┬──────────────────────────┐
  │ 这个我没找到权威资料……   │      assistant: top + left edges 1px --ink,
  │                          │      overshoot 10px past the top-left corner;
  └──────────────────────────┘      bottom + right edges 1px ink-20
                                    user: bottom + right edges 1px --magenta,
  ┌──────────────────────┐          overshoot past bottom-right; top + left
  │ 奇亚籽真的能减肥吗？ │          edges 1px --magenta-lo
  └──────────────────────┴──
```

Exact values: overshoot 10px/10dp beyond the corner, 1px/1dp, butt ends, colinear with the
edge it continues. Assistant active edges `#101010`, passive `ink-20`; user active
`#ec0a6e`, passive `--magenta-lo` (the 14% alpha token as a hairline — first use of it as a
line, argued: it is the token set's only quiet magenta).

Compose: skip `Modifier.border`; one `drawBehind` draws all four edges as `drawLine`s — the
two active ones running from −10dp before the corner. The thread row reserves 10dp
start/top (assistant) or end/bottom (user) padding so nothing clips.
HTML: element border with per-side colours; two absolutely-positioned 10×1px/1×10px
pseudo-element segments at `left:-11px;top:-1px` and `left:-1px;top:-11px` (mirrored for
user). **Colour-free cue:** which corner the cross hangs off.

### B6 — "Margin note" (new — a bubble that is not a closed shape at all)

Asymmetric materiality. Assistant turns stay filed documents — the plain paper plate with
its hairline. User turns are **unboxed**: no fill, no border, ink text set directly on the
gridded concrete ground (S1 — the grid runs under the words, which is what makes them read
as written *on* the drawing), right-aligned, with a single **3px solid magenta rail** flush
to the text block's right edge — a margin annotation against the record.

```
┌────────────────────────────────┐
│ 甲亢确实常见心悸、手抖、容易   │    assistant: unchanged paper plate
│ 出汗这些表现……                │
└────────────────────────────────┘
           我最近老是心慌手抖，是不是甲亢？ ▐    user: ink text on concrete,
                                                 rail 3px --magenta, full text
                                                 height, 12px gap text → rail
```

Exact values: rail 3px/3dp wide, height = text block height, butt ends; text right-aligned,
max-width 85%, 12px padding between text and rail, 4px top/bottom; thread gap around user
turns 16px (up from 12 — no plate mass to separate). Ink on concrete contrast is ample.

Compose: `Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = End) {
Text(…); Spacer(12.dp); Box(Modifier.fillMaxHeight().width(3.dp).background(Magenta)) }`.
HTML: `border-right: 3px solid var(--magenta); padding: 4px 12px 4px 0`.
**Colour-free cue:** boxed vs unboxed — the strongest glance cue in the field.
Honest cost: the user's words lose the "received and filed" feeling a plate gives; the
composer-to-thread moment leans entirely on position.

### B7 — "Register corners" (new — register marks)

No border at all. Fill only, plus **two corner brackets per bubble** — arm 12px, stroke
1.5px, butt caps, miter joins, centred on the plate edge (half in, half out). Assistant:
top-left + bottom-right, --ink, on paper. User: top-right + bottom-left, --magenta, on
magenta-flat. The bubble is set inside oversized drawn quotation marks — E1's 「」 at plate
scale: the app only prints what it can quote.

```
⌐─                                  assistant: brackets TL + BR, ink
│ 大家都认可的是：适量喝咖啡……      user: brackets TR + BL, magenta
                              ─┘    arms 12px, stroke 1.5px, on the edge line
```

Exact values: arm 12px/12dp; stroke 1.5px/1.5dp; bracket vertex exactly on the plate
corner; edge definition comes from fill contrast alone (paper and #f1d2dc both hold against
concrete — **dark-mode flag:** verify both fills against the near-black ground when the
inverted palette lands).

Compose: `background(fill)` + `drawWithContent` with four `drawLine`s (two per bracket).
HTML: fill + two absolutely-positioned 13×13 SVGs (`<path d="M0 12 V0 H12">`), the second
rotated 180°. **Colour-free cue:** which diagonal the brackets sit on (TL↘BR vs TR↙BL).

### B8 — "Checker cut" (new — stepped edge)

B1's chamfer quantized to the checkerboard: the 45° cut becomes a **staircase of two 8px
steps** (16×16 total), and one solid 8×8 **accent cell** lodges at the staircase's inner
corner — a checker cell left behind in the cut. Assistant: cut top-left, cell --ink. User:
cut bottom-right, cell --magenta.

```
  ▛▀──────────────────────────┐
 ▛▘▪                          │     steps 8px, from the checker grid
│  苹果官网写的是 3582mAh……   │     accent cell 8×8 at (8,8)–(16,16)
└─────────────────────────────┘     from the cut corner, solid
```

Exact values: step 8px/8dp (two steps, 16 deep); accent cell 8×8 at offset (8,8) from the
cut corner; padding 16 keeps text off the cut (padding ≥ cut depth, same rationale as B1);
fills and hairlines as baseline.

Compose — exact outline, border included:

```kotlin
class CheckerCutShape(private val user: Boolean) : Shape {
    override fun createOutline(size: Size, ld: LayoutDirection, density: Density): Outline {
        val s = with(density) { 8.dp.toPx() }
        val p = Path()
        if (!user) {
            p.moveTo(0f, 2 * s); p.lineTo(s, 2 * s); p.lineTo(s, s)
            p.lineTo(2 * s, s); p.lineTo(2 * s, 0f); p.lineTo(size.width, 0f)
            p.lineTo(size.width, size.height); p.lineTo(0f, size.height)
        } else {
            p.moveTo(0f, 0f); p.lineTo(size.width, 0f)
            p.lineTo(size.width, size.height - 2 * s)
            p.lineTo(size.width - s, size.height - 2 * s)
            p.lineTo(size.width - s, size.height - s)
            p.lineTo(size.width - 2 * s, size.height - s)
            p.lineTo(size.width - 2 * s, size.height); p.lineTo(0f, size.height)
        }
        p.close(); return Outline.Generic(p)
    }
}
// background(fill, shape) + border(1.dp, edge, shape); accent cell = drawBehind drawRect
```

HTML: `clip-path: polygon(0 16px, 8px 16px, 8px 8px, 16px 8px, 16px 0, 100% 0, 100% 100%,
0 100%)` (user mirrored with `calc(100% − …)`); accent cell a `::before` at `top:8px;
left:8px`. Known preview compromise: the inset box-shadow hairline cannot trace the steps,
so the stepped edge is a bare fill edge in HTML; Compose draws the true outline.
**Colour-free cue:** cut corner position + the lodged cell.

### B9 — "Baseline shelf" (new — the hairline escapes the box)

Every message sits on a **full-thread-width 1px rule**, its bubble occupying the speaker's
side of the line — a ledger of utterances. The bubble's own bottom edge lies exactly on the
rule; past the bubble the rule continues at ink-10 to the far margin.

```
┌──────────────────────────────┐
│ 正反两个方向我都查了……       │
┴──────────────────────────────┴──────────────    assistant: plate left, 85%
                                                  shelf 1px ink-10, full width
              ┌────────────────────────┐
──────────────┴────────────────────────┴──        user: plate right, 85%
```

Exact values: shelf 1px/1dp, ink-10, full thread width (edge to edge, under the 16dp thread
padding too); **both speakers 85% width** on their own side (amends the baseline width rule
— the shelf needs a margin to run into); bubble flush on the rule; 12px to the next row.
Both shelves ink-10 — colour does no identifying work here.

Compose: the row composable (already full-width) gets `drawBehind { drawLine(Ink10,
Offset(-padPx, size.height - 0.5f), Offset(size.width + padPx, size.height - 0.5f)) }` —
zero cost. HTML: `.row::after` 1px absolute line at bottom.
**Colour-free cue:** which side of the ruled line the plate occupies. Side benefit: rhymes
with F's memory ledger — the whole app becomes ruled paper. Risk: densely stacked short
turns read slightly like a form; that is close enough to the language to be a feature.

### B10 — "Glass plate" (new — the shell as a bubble)

The material split does the speaker work. Assistant turns are **glass shells** (S2, no
screws) — the machine's statements under glass, the grid faintly visible through the pane.
User turns stay **flat print**: the existing magenta-lo plate with its magenta hairline.
Verified content is exhibited; the user's words are written.

```
╔══════════════════════════════╗
║ 苹果官网写的是 3582mAh……     ║    assistant: S2 shell — translucent fill,
╚══════════════════════════════╝    white border, lip, streak, bottom shade;
      (grid visible through it)     grid reads through
          ┌───────────────────┐
          │ 电池能用多久？    │     user: flat #f1d2dc plate, 1px --magenta,
          └───────────────────┘     85% right — unchanged from baseline
```

Exact values: the S2 recipe verbatim (HTML) / `Modifier.glassShell()` (Compose); rectangle
silhouette; 16dp padding; the source card inside it stays its own smaller glass strip (S4)
— glass-in-glass is fine at two clearly different scales, the site nests shells the same
way. No screws on any bubble, ever (S5). Drop shadow: ship `drop = true`; if a 40-message
thread of shadowed layers bothers a mid-range GPU (it should not — render-node shadows are
cached), `drop = false` per S3's fallback and the thread loses nothing structural.

**Colour-free cue:** material — translucent gradient + white border vs flat fill + flat
hairline. Strong at a glance and unmistakable in motion (the shell's streak shifts subtly
against the grid when the thread scrolls; free, it is just parallax-of-nothing —
the layers are static, the *content* moves).

Honest cost: an all-glass thread risks the monotony S4 warns about — mitigated because user
turns, the pending hatch plate, the band and the composer are all *not* glass; glass stays
the assistant's material alone. The real risk is quieter: paper-fill bubbles (B1/B8) read
"printed page", glass bubbles read "instrument readout" — the author should pick the
metaphor, not the prettiest swatch.

### Ranking

**B10 › B1 › B8.** With the grid as the app-wide ground, B10 is the thread the site would
build — hairlines through clear plastic, the exact texture the author named — and it leaves
silhouettes untouched for C/D to work against. B1 stays the pick if the author prefers
print to glass: proven silhouette-per-cost, and it composes with B10 later (a chamfered
shell is one `clip` away, priced in a follow-up if wanted). B8 is the choice for binding
the bubbles to the checkerboard motif instead. B6 pushes austerity furthest; B5 and B9
combine poorly with the meter-and-source-card density of assistant turns (B5's cross and
C's meter crowd the same corner zone; B9 + source-card hairlines stack three rules within
40px). B2/B3 remain priced leftovers. All nine options — the seven live plus the two
leftovers — render on the board with real copy over the real ground — the choice is the
author's.

---

## C. Confidence meter

> **Round 2:** approved by the author — 4-cell scalar meter ships, C-a fault line ships for
> CONFLICT. New: the two text labels, with author-approved strings **置信度** (scalar) and
> **各有说辞** (fault line), used exactly. The meter is therefore no longer wordless — §6
> amendment flagged below, not resolved. Everything else (cell geometry, state mapping,
> reveal) stands as written in round 1.

### The approved core (unchanged)

Four 9×5px cells, 3px gaps (bar 45×5px), filled left to right, count and ink darkness
encoding the same value: CONFIDENT/REFUTED 4/4 `#101010` · ATTRIBUTED 3/4 ink-60 ·
PERSONAL_PATTERN 2/4 ink-40 · WEAK_LEAD 1/4 ink-40 · NOTHING_FOUND / SEARCH_UNAVAILABLE
render nothing. CONFLICT = C-a fault line: two 20×5 solid ink blocks misaligned ±1px against
a 1×8 magenta seam, 45×8px total; C-c's 15×8 condensation reappears inside the source card
beside the two disagreeing rows. Reveal: one cell per 120 ms, discrete. Placement: top of
the assistant bubble's interior, right-aligned, 9dp above the first text line; user bubbles
never carry one. Round-1 Compose/HTML mappings stand.

### The labels (new)

| | string (exact) | beside |
|---|---|---|
| scalar meter | **置信度** | the 4-cell bar |
| fault line | **各有说辞** | the C-a mark |

Spec — the label must caption the instrument, not badge the sentence:

- **Type**: display face (CJK falls to the system face by design, as everywhere), **9px /
  9sp**, regular weight, letter-spacing 1px / 0.1sp-per-glyph equivalent (1.sp).
- **Colour**: **ink-40** — both labels, CONFLICT included. The fault line's seam is the
  emphasis; the label stays annotation-grey. No plate, no border, no fill behind it — a
  caption has no chrome, a badge does.
- **Placement**: label **left of the meter**, 6px/6dp gap, vertically centred against the
  bar; the group right-aligned as before. Label-left keeps the meter anchored to the
  padding edge across states (置信度 is 3 glyphs, 各有说辞 is 4 — right-anchoring the meter
  means the geometry the eye returns to never shifts).
- **Size discipline**: 9px is the source-card-meta size class — the smallest text in the
  app, two steps below body. At that size, ink-40, with no chrome, the label reads as an
  instrument caption in the drawing's margin. If it ever competes with the sentence, the
  correct fix is smaller/greyer, never bolder.

```
 ╱────────────────────────────────┐
╱            置信度 ▪ ▪ ▪ ▫      │   scalar (ATTRIBUTED shown)
│ 路透社报道，这款已经在 8 月……  │
└─────────────────────────────────┘
             各有说辞 ▮▮▮│▮▮▮        CONFLICT
```

Compose:

```kotlin
if (shape.rendersMeter()) Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp)
) {
    Text(if (shape == CONFLICT) "各有说辞" else "置信度",
         fontSize = 9.sp, color = Areel.Ink40, letterSpacing = 1.sp)
    ConfidenceMeter(shape)   // round-1 component, unchanged
}
```

HTML: `<span class="mlabel">置信度</span>` before the meter span; `.mlabel{font-size:9px;
color:var(--ink-40);letter-spacing:1px}`; flex row, `gap:6px`, `align-items:center`.

### §6 flag (not resolved here)

Spec §6: *"In-sentence attribution … No footnote markers, no confidence badges."* The
meter was defensible under §6 as a wordless instrument; with 置信度 beside it, it is a
worded instrument. §6 needs an amendment along the lines of "no worded verdicts on the
sentence; the margin meter carries two fixed captions (置信度/各有说辞) and nothing else" —
**author's call, flagged, not resolved in this document.** (Reservation recorded at the
top: I would have captioned CONFLICT only.)

---

## D. Inline progress — the pending bubble

> **Round 2:** approved, with one structural change: the 24px magenta shuttle no longer
> slides along the top edge — it **laps the bubble's entire outline**, chamfer included, as
> a path animation. Hatch, accumulating steps, in-place replacement and the arrival
> choreography stand as written in round 1; the source card that arrives is now a glass
> strip per S4.

### The lap

A 24px segment of the border, solid `#ec0a6e`, travelling the bubble's closed outline at
constant speed. Because it is a fixed-length segment on a closed loop, it has no start, no
end and no fill state — it structurally *cannot* read as a percentage, which keeps §21's
honesty for free.

Exact values:

- Segment **24px/24dp** along the path; stroke **2px/2dp**, butt caps, centred on the
  outline (1px in, 1px out).
- Speed **150px/s (150dp/s)**, `linear`, clockwise, starting at the chamfer.
- Period = perimeter / 150. For the B1 assistant shape (cut c=14) the perimeter is
  **P = 2(w+h) − 2c + c√2 = 2(w+h) − 8.2px**. Reference instance 320×104 → P = 839.8px →
  **5.6 s per lap**.
- The period is computed **once from the first measured size and then held**; as steps
  accumulate and the bubble grows, the effective speed drifts ≤ ~15% — imperceptible, and
  it avoids re-deriving the animation mid-flight.
- The lap follows whatever outline the winning B direction defines (B8's stepped cut just
  changes P; B10's rectangle makes it 2(w+h)). Values above are instanced for B1.

HTML — the bubble clips itself (`clip-path`), so the lap lives on a **sibling SVG overlay**
that is not clipped; dash = one segment + one gap summing to exactly P:

```html
<div class="pendwrap">
  <div class="pending">…hatch + steps…</div>
  <svg class="lap" viewBox="0 0 320 104" width="320" height="104" aria-hidden="true">
    <path d="M14 0 H320 V104 H0 V14 Z"/>
  </svg>
</div>
```
```css
.pendwrap{position:relative;width:320px}
.lap{position:absolute;inset:0;overflow:visible;pointer-events:none}
.lap path{fill:none;stroke:var(--magenta);stroke-width:2;
  stroke-dasharray:24 815.8;             /* 24 + 815.8 = P = 839.8 */
  animation:lap 5.6s linear infinite}    /* 839.8 / 150 ≈ 5.6s     */
@keyframes lap{to{stroke-dashoffset:-839.8}}
```

Compose — `PathMeasure` over the shape's outline, objects remembered, zero allocation per
frame:

```kotlin
val phase by rememberInfiniteTransition(label = "lap").animateFloat(
    0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = "t")
// periodMs = (P0 / 150.dp.toPx() * 1000).roundToInt(), P0 measured once via onSizeChanged

val measure = remember { PathMeasure() }
val outline = remember { Path() }
val segment = remember { Path() }

Modifier.drawWithContent {
    drawContent()
    outline.reset()
    outline.addOutline(bubbleShape.createOutline(size, layoutDirection, this))
    measure.setPath(outline, forceClosed = true)
    val len = measure.length
    val seg = 24.dp.toPx()
    val start = phase * len
    segment.reset()
    measure.getSegment(start, minOf(start + seg, len), segment, true)
    if (start + seg > len)                                  // wrap past the start point
        measure.getSegment(0f, start + seg - len, segment, true)
    drawPath(segment, Color(0xFFEC0A6E),
             style = Stroke(2.dp.toPx(), cap = StrokeCap.Butt))
}
```

### Cost, stated plainly

Per frame this is **one stroked `drawPath` plus a 6-segment `PathMeasure` walk on a single
bubble-sized layer** — no layout, no allocation (paths are reset, not recreated), and the
invalidation is scoped to the pending bubble's draw phase, not the screen. That is
acceptable on a mid-range phone; the hatch stays static and the step pulse already shipped.
If profiling on the target device ever disagrees: (a) drive `phase` from a 30 Hz frame
clock (halves the redraws, the lap still reads as continuous), and (b) suspend the infinite
transition when the pending bubble is scrolled off-screen or the app is backgrounded
(lifecycle-aware pause). Both are one-line mitigations; neither is expected to be needed.

### Unchanged from round 1

Hatch (45°, 1px ink-06, 8px pitch, static — "material not yet resolved"); steps accumulate
with past steps at ink-40 and the live step's 4×4 magenta square pulsing at 900 ms; bubble
grows via `animateContentSize` (220 ms `ease-mech`); arrival = hatch out 200 ms → rows
collapse 220 ms → answer in 200 ms (60 ms stagger) → C cells fill → source card (now glass,
S4) appears; the `Pending` list item is replaced in place, same key, so the placeholder
literally becomes the answer. `SEARCH_UNAVAILABLE` short-circuits identically.

---

## E. Icon set

> **Round 2:** the meander-spiral brain is **confirmed** by the author; the literal-dome
> alternative is deleted (was: chamfered dome with fold lines — gone, no fallback needed).
> E1/E2/E4 unchanged. One addition: the source card the E1 mark sits in is now a glass
> strip (S4); the mark itself does not change.

One grid for everything: **24×24 viewBox · stroke 1.5 · `stroke-linecap="butt"` ·
`stroke-linejoin="miter"` · fill none** · all runs orthogonal or 45° · coordinates on the
integer grid. Rendered at 24dp (composer), 20dp (masthead), 12–14px (source card). Colour is
`currentColor` — ink on paper/glass, paper on the band.

### E1 — Source mark (replaces 📎) — unchanged

```svg
<svg viewBox="0 0 24 24" width="12" height="12">
  <path d="M10 5 H5 V10 M14 19 H19 V14"
        fill="none" stroke="currentColor" stroke-width="1.5"
        stroke-linecap="butt" stroke-linejoin="miter"/>
</svg>
```

Drawn 「 」 at 12px, ink, aligned to the source-name cap height. In the CONFLICT source
card, the two disagreeing rows swap this for C-c's misregistration mark (15×8) in
ink+magenta.

### E2 — Send — unchanged

```svg
<svg viewBox="0 0 24 24" width="24" height="24">
  <path d="M12 20 V5 M5 12 L12 5 L19 12"
        fill="none" stroke="currentColor" stroke-width="1.5"
        stroke-linecap="butt" stroke-linejoin="miter"/>
</svg>
```

Paper on the magenta 48dp send plate; ink-20 plate + ink-40 glyph when disabled.

### E3 — Brain (memory) — confirmed, alternative deleted

The meander spiral, as shipped in round 1 — a rectangular coil in the Greek-key language the
checkerboard already lives in:

```svg
<svg viewBox="0 0 24 24" width="20" height="20">
  <path d="M4 4 H20 V20 H8 V8 H16 V16 H12"
        fill="none" stroke="currentColor" stroke-width="1.5"
        stroke-linecap="butt" stroke-linejoin="miter"/>
</svg>
```

### E4 — Back — unchanged

```svg
<path d="M19 12 H5 M11 6 L5 12 L11 18" fill="none" stroke="currentColor"
      stroke-width="1.5" stroke-linecap="butt" stroke-linejoin="miter"/>
```

Compose mapping unchanged from round 1: `res/drawable` VectorDrawables, `d` strings
verbatim into `android:pathData`, stroke attributes as above, tint via `LocalContentColor`.

---

## F. Memory affordance + screen

> **Round 2:** three changes. (1) The −8° tilt is **deleted** — the affordance becomes a
> plated icon (fill, size, padding, inversion and pressed state below, rendered). (2) The
> **privacy disclosure is removed from the app entirely** — the footer is gone, the §22
> sentence appears nowhere in the UI, and no alternative in-app home is proposed; the freed
> slot is left **empty**, argued below. (3) The ledger becomes the app's one **glass +
> screws** surface (S4/S5). The tilt's old job — "the system's one diagonal" — now belongs
> to the H logo fish.

### The affordance — a plate, not a tilt

In the near-black band, right side: a **44×44dp paper plate** (48×48dp touch target)
carrying the E3 brain and the 记忆 label. It inverts against the band by construction —
paper on ink, the established "pressable plate" material (the tabs and gate plates already
speak it) — so it reads as a key on a console, not floating text.

```
┌────────────────────────────────────┐
│ ⟨fish⟩ FISHBALL          ┌──────┐  │   band --ink, height 56dp
│                          │ ⌇⌇⌇  │  │   plate 44×44dp --paper, 12dp end margin
│                          │ 记忆 │  │   brain 18×18dp ink stroke 1.5
│                          └──────┘  │   label 9sp ink, +2sp tracking
└────────────────────────────────────┘
▓░▓░▓░▓░▓░▓░▓░▓░▓░▓░▓░▓░▓░▓░▓░▓░▓░▓░   checker 8dp
```

- **Fill**: `--paper #f2f2ee`, square, no border (the edge is the contrast).
- **Size / padding**: 44×44dp visual inside a 48dp touch target; contents centred — brain
  18×18dp, 3dp gap, label 9sp; that stack is 30dp tall → 7dp effective vertical padding.
- **Label**: 记忆, display face, **9sp, --ink** (not magenta: #ec0a6e on paper is 3.86:1,
  which fails at 9px; the round-1 magenta label dies with the tilt), letter-spacing 2sp
  with 2dp start padding compensating the trailing tracking.
- **Inversion**: the plate exists only on the band in v1, where it is the inverted element
  (paper on ink). If it ever sits on a light ground it flips to ink fill / paper glyphs —
  stated for completeness, not used anywhere yet.
- **Pressed**: fill → `--concrete-2 #d3d3cf` and content offsets **+1dp x/y** — the
  system-wide plate press. No Material ripple (`indication = null`).

```kotlin
Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        Modifier.size(44.dp)
            .background(if (pressed) Areel.Concrete2 else Areel.Paper)
            .clickable(interaction, indication = null) { openMemory() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val push = if (pressed) 1.dp else 0.dp
        Column(Modifier.offset(push, push), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_brain), null,
                 Modifier.size(18.dp), tint = Areel.Ink)
            Spacer(Modifier.height(3.dp))
            Text("记忆", fontSize = 9.sp, color = Areel.Ink, letterSpacing = 2.sp,
                 modifier = Modifier.padding(start = 2.dp))
        }
    }
}
```

HTML: `.memplate{width:44px;height:44px;background:var(--paper);display:flex;
flex-direction:column;align-items:center;justify-content:center;gap:3px}` with
`:active{background:var(--concrete-2)}` and `:active>*{transform:translate(1px,1px)}`.

Rejected alternates, one line each: hairline-only plate (a 1px paper box on ink is harsh
and readless at 44dp); concrete-fill "window" plate (quieter but reads as a hole, not a
key); keeping magenta anywhere in it (fails contrast on paper at this size).

### The screen

Full-screen route, band persists with E4 back arrow + 记忆 title (Archivo Black 22sp
paper); the brain sits at the band's right at 20×20 paper stroke, unplated — inside the
screen it is the subject, not the button. Below the checker strip, on the S1 gridded
ground:

```
┌────────────────────────────────────┐
│ ⟵  记忆                    ⌇⌇⌇     │  band --ink
├────────────────────────────────────┤
▓░▓░▓░▓░▓░▓░▓░▓░▓░▓░▓░▓░▓░▓░▓░▓░▓░▓░  checker 8dp
│ ┌────────────────┬────────────────┐│  two plates, 44dp: active = ink fill/paper
│ │   网上学到的   │    关于你的    ││  text; inactive = paper fill/ink text
│ └────────────────┴────────────────┘│
│ ╔════════════════════════════════╗ │  THE LEDGER SHELL — S2 glass + S5 screws,
│ ║ ▫ iPhone 17 Pro 电池 3582mAh   ║ │  one shell around the whole list; rows
│ ║   长期 · 2026-08-12            ║ │  inside on 1px ink-10 separators
│ ║ ──────────────────────────────║ │
│ ║ ▫ 奇亚籽减肥没有权威结论       ║ │  world row: 6×6 ink outline square bullet
│ ║   每次重查 · 2026-08-29        ║ │  fact 15sp ink · meta mono 10px ink-40
│ ║ ▪ 对青霉素过敏 · 长期          ║ │  personal row: 6×6 solid magenta bullet
│ ╚════════════════════════════════╝ │
│                                    │  (footer slot: empty — see below)
└────────────────────────────────────┘
```

- **Ledger = one glass shell** (S2) with the four corner screws (S5's single budget spend):
  the facts the app holds are mounted under glass, on the drawing. Rows sit inside the
  shell on 1px ink-10 separators, 14dp vertical padding; grid visible through the pane
  between rows. List scrolls *inside* the shell (shell is the viewport frame, screws
  stationary); G's scrollbar applies.
- **Tabs**: unchanged two-plate switch (active = ink), 220 ms crossfade of the list only.
- **Bullets/meta**: unchanged — outline ink = world, solid magenta = personal; TTL bucket
  in plain words, mono 10px ink-40.
- **The freed footer slot stays empty.** The privacy footer is deleted (author's call —
  told in person; it read as unprofessional in-app). Nothing replaces it: a record count
  duplicates the visible ledger; last-updated duplicates the per-row dates; a clear-all
  control is an uninvited destructive affordance for exactly the user the spec protects,
  and no §-requirement asks for it. The shell simply ends and the gridded ground runs to
  the screen edge — empty concrete is the app's idiom, and after S1 it is never *blank*.
- **Spec flag, not resolved here:** §22 still reads "privacy is disclosed at first login".
  With the disclosure moved out-of-band (the author tells the user in person), §22 needs
  an amendment noting the channel — the obligation is unchanged, the surface is no longer
  the app.

Placeholder copy still requiring sign-off (unchanged from round 1, Appendix 2): tab labels
网上学到的 / 关于你的; TTL words 长期 / 一年 / 一个月 / 每次重查; empty states. The §22
sentence is deleted from the strings inventory.

Compose mapping: as round 1 (`LazyColumn` of rows + `Hairline()`, two weighted tab `Box`es,
conditional two-screen state, no NavHost needed) with the list's container gaining
`glassShell().screws()` and the footer `Box` deleted.

---

## G. Scrollbar

> **Round 2:** slimmer, per the author. The reviewed board rendered a 7px thumb (round 1's
> doc text said 3px — the board is what was reviewed, so 7px is the baseline being
> corrected). New values, both surfaces: **HTML 5px · Compose 3dp × 32dp (unchanged)**.

**HTML preview** (`.thread`, and the memory shell's inner list):

```css
.thread{scrollbar-width:thin;scrollbar-color:var(--ink-40) transparent}   /* Firefox */
.thread::-webkit-scrollbar{width:5px}
.thread::-webkit-scrollbar-track{background:transparent}
.thread::-webkit-scrollbar-thumb{background:var(--ink-40);border-radius:0}
.thread::-webkit-scrollbar-thumb:hover{background:var(--ink)}
```

**Compose**: unchanged from round 1 — `LazyColumn` ships no bar; the transient fixed-length
thumb (3dp × 32dp solid ink, flush right, fade in 80 ms, fade out 400 ms after 600 ms idle,
position-only mapping, no drag) stands exactly as specced, `areelScrollbar(state)` code as
written. 3dp at handset density already sits at the visual weight the author is asking the
board to move to; the memory shell's list reuses the same modifier.

---

## H. Logo mark

> **Round 2:** the author chose **H3 "polygon fish"** and asked for a 45° rotation, head
> into the upper-right corner. Delivered as **recomputed path coordinates** — no transform
> wrapper — with the geometry re-snapped so the rotation is exact, plus the 20px band check
> and the checkerboard-adjacency check, both rendered on the board. H2 skewer is retired;
> H1 quadrant is retired to a possible future house mark (and echoes in A-bg3). Round 1's
> "pulls toward cute" reservation is largely withdrawn — recut at 45° the fish reads more
> plotter-mark than mascot. The fish is now the system's one diagonal object (the job the
> deleted −8° tilt used to do).

### The recut, shown

Rotating round 1's paths by `transform="rotate(-45 12 12)"` was rejected for two concrete
reasons, which is why the coordinates are recomputed:

1. **The eye must stay orthogonal.** Squares in this system never tilt (the H2 argument;
   the checkerboard's cells; every plate). A wrapper transform spins the eye into a diamond
   and forces a counter-rotated child node — a wrapper to fix the wrapper.
2. **Round 1's fish had a hidden flaw the rotation would expose.** Its head cuts were true
   45° but its tail-side edges were 4:5 (51.3°) — invisible while horizontal, glaring once
   diagonal. The local geometry is re-snapped so **every edge is 0°, 45° or 90° in the
   rotated frame**.

Derivation: express the fish in axis coordinates (a along the body, b across), snap all
local edges to 0/45/90, then map through the −45° rotation about (12,12), which reduces to
`screen(a,b) = (12 + (a+b)·√2/2, 12 + (b−a)·√2/2)`; finally slide the fish +3 units along
its own axis so the rotated bounding box centres in the viewBox (margins 3.51px on all four
sides). Head tip lands at (20.49, 3.51) — pointing exactly into the upper-right corner
along the anti-diagonal — and becomes a **square 90° corner**, rhyming with E1's brackets.

### The final asset (no wrapper)

```svg
<svg viewBox="0 0 24 24" width="20" height="20">
  <path d="M20.49 3.51 V10.59 L16.24 14.83 H9.17 V7.76 L13.41 3.51 Z" fill="#f2f2ee"/>
  <path d="M10.59 13.41 V20.49 L3.51 13.41 Z" fill="#f2f2ee"/>
  <rect x="16.66" y="5.34" width="2" height="2" fill="#ec0a6e"/>
</svg>
```

Body: hexagon — two horizontal edges, two vertical, two 45°; nose = the orthogonal corner
at (20.49, 3.51). Tail: right triangle flaring down-left, its right angle overlapping the
body by 2 local units so the union is seamless. Eye: **orthogonal** 2×2 at (16.66, 5.34),
centred on the body axis one unit behind the head shoulders, `#ec0a6e`. On the band the
fills are paper as above; on light ground, ink; the watermark variant (A-bg1) is single
ink-06 with the eye punched via `fill-rule="evenodd"`:

```svg
<path fill-rule="evenodd" fill="rgba(16,16,16,.06)"
      d="M20.49 3.51 V10.59 L16.24 14.83 H9.17 V7.76 L13.41 3.51 Z
         M16.66 5.34 h2 v2 h-2 Z"/>
<path fill="rgba(16,16,16,.06)" d="M10.59 13.41 V20.49 L3.51 13.41 Z"/>
```

### The two checks the author asked for

**20px on the near-black band**: at 20px the viewBox scales ×0.833 — smallest features are
the 2px eye (→1.67px, one crisp device pixel cluster at 3× density) and the 7.07px tail
(→5.9px). All fills, no strokes, so nothing thins away. Rendered at exactly 20px on the
band on the board; verdict there.

**Checkerboard adjacency**: in the 56dp band the 20px mark is vertically centred, so ≥14px
of solid ink sits between the fish's lowest point and the band's bottom edge, then the 8px
checker strip begins on the *other* ground (ink-on-concrete vs the fish's
paper-on-ink — figure and ground flipped). The fish's long diagonals also run up-right,
against the checker's orthogonal grid, so nothing aligns to vibrate. Rendered directly
above the checker strip on the board; judge there.

Compose: VectorDrawable, the `d` strings verbatim into `android:pathData` (decimals are
legal), `Icon(..., tint = Unspecified)` — the mark is two-coloured.

---

## Appendix 1 — motion constants

> **Round 2:** sweep row deleted with A2; shuttle row replaced by the border lap; tide
> promoted from "alt"; crosshair park added; glass adds **no** motion rows (every shell
> layer is static).

| name | value | curve | loop | surface |
|---|---|---|---|---|
| crosshair drift | 15–85% W / 20–80% H | linear, alternate | 56 s / 74 s ∞ | A1 |
| crosshair park / release | to input corner / back to phase | `ease-mech` | 600 ms, once | A1 |
| checker tide | background-position −8px/step | steps | 1200 ms/step ∞ (600 ms while verifying) | A3 |
| border lap | 24px segment, 150px/s clockwise | linear | perimeter/150 ∞ (ref 5.6 s) | D |
| step pulse | square alpha .35 ↔ 1 | linear, alternate | 900 ms ∞ | D (exists today) |
| cell fill | +1 cell | discrete | 120 ms/cell, once | C |
| bubble grow | height | `ease-mech` | 220 ms, once | D (`animateContentSize`) |
| hatch/text swap | opacity | `ease-mech` | 200 ms (+60 ms stagger), once | D |
| scrollbar fade | alpha 0 ↔ 1 | tween | in 80 ms / out 400 ms after 600 ms | G |
| plate press | content +1dp x/y | none | on press | gate button, composer send, F memory plate |

`ease-mech` = `cubic-bezier(0.2, 0, 0, 1)` / `CubicBezierEasing(0.2f, 0f, 0f, 1f)`.

## Appendix 2 — strings

> **Round 2:** two strings **approved by the author this round** — use exactly, no
> sign-off needed. The §22 privacy sentence is **deleted from the app**; it leaves the
> strings inventory entirely.

| status | where | string |
|---|---|---|
| **approved** | C scalar caption | 置信度 |
| **approved** | C conflict caption | 各有说辞 |
| given in brief | F plate label | 记忆 |
| pending sign-off | memory tabs | 网上学到的 · 关于你的 |
| pending sign-off | TTL words | 长期 · 一年 · 一个月 · 每次重查 |
| pending sign-off | memory empty states | 还没有从网上记下什么。· 还没有记下关于你的事。 |
| ~~removed~~ | ~~memory footer~~ | ~~§22 disclosure sentence~~ — out of the app per the author; §22 amendment flagged in F |

(A-bg2/A-bg3 contain no text; A1/A3 add none. The dropped dimension-line option was the
only background that carried numerals, and it is dead.)

## Appendix 3 — implementation order (suggested)

> **Round 2:** reordered — the ground and shell come first (everything sits on them), and
> the B decision is the one blocking choice.

1. **S ground + shell** — the double-grid `drawBehind` and `glassShell()`/`screws()`
   modifiers; pure additions, everything else layers onto them.
2. **B bubbles** — the author picks from the field of eight; the pending outline and the
   thread's whole look hang on this one choice.
3. **D pending bubble** — list-model restructure (`Pending` item) + the border lap against
   the chosen B outline.
4. **C meter + captions** — additive component on the assistant bubble.
5. **E icons + H fish** — asset drop; replaces 📎 / ↑ / Material Send; band gains the mark.
6. **A gate (A1-vs-A3 + cropped object + glass input) and F memory (plate, shell ledger,
   no footer)** — new screens/states.
7. **G scrollbars** — polish, last.
