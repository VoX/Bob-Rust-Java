# Click-Budget Sweep — Findings & Proposals

Analysis of `docs/speed-quality-results.csv` (produced by
`ClickBudgetSweepTest`, branch `fix/review-findings`). Question answered:
**which generator/paint configuration maximizes perceptual quality inside a
fixed 18,000-click paint budget** (10 minutes at 30 clicks/second), and which
not-yet-implemented changes would buy the most quality per click.

TL;DR — the two biggest levers are both *policy*, not search effort:

1. **Adaptive size selection is a net loss at a click budget.** `noAdaptive`
   beats `default` on pooled SSIM (+0.0066) *and* pooled ΔE00 (−0.08) while
   generating **2.2× faster**. One-line change.
2. **Alpha policy is strongly content-dependent.** `alphaFloor0` wins both
   metrics decisively on photographic content (portrait ΔE00 **−32%**), while
   hard-edge content wants a *higher* floor (`opaque` is +0.094 SSIM on
   glyphs but wrecks color). No fixed floor is right → make it adaptive.
3. Search-effort levers (`sa`, `hiStates`, `exactRank`) barely move quality at
   a fixed click budget: **quality is coverage-limited, not search-limited.**

---

## 1. Metric and method

- **Budget model** (verified in-harness against `PaintTimeEstimator` /
  `BobRustPainter.startDrawing` on every plan): total clicks =
  `N blobs × 1` + in-loop tool changes of the **sorted** plan
  (size/color/alpha/shape) + `floor((N−1)/1000)` autosaves + 21 constant
  setup/save clicks. Mouse travel is free (`Robot.mouseMove` teleports), so
  clicks *are* paint time at a given cps.
- **Harness** (`src/test/java/com/bobrust/benchmark/ClickBudgetSweepTest.java`):
  generate shapes incrementally until the sorted plan exceeds 18k clicks,
  binary-search the longest generation-order prefix that fits, re-render that
  exact plan with the committed paint kernel (`BlobPruner.render`), score
  against the target.
- **Metrics**: SSIM (structure, higher better), mean CIEDE2000 `deltaE00`
  (perceptual color error, lower better), RMSE. Independently verified.
- **Images** (5 regimes): `texture` (high-freq), `portrait` (photographic),
  `skyline` (mixed), `glyphs` (hard-edge text), `mosaic` (hard-edge tiles).
  Renders for eyeballing: `docs/speed-quality-renders/`.
- **Sorting matters**: the greedy sorter (`BorstSorter`) cuts clicks
  1.9–2.3× vs unsorted order (`unsortedClicks`/`clicks` per row).
- Seed noise floor (seedB vs default, texture+skyline): **±0.001 SSIM,
  ±0.04 ΔE00** — differences below that are noise.

## 2. Results at 18,000 clicks

Per-image winners (vs `default`):

| image    | SSIM winner                    | ΔE00 winner                  | default (SSIM / ΔE00) |
|----------|--------------------------------|------------------------------|-----------------------|
| texture  | **alphaFloor0** 0.691 (+0.016) | **alphaFloor0** 3.15 (−20%)  | 0.675 / 3.93          |
| glyphs   | **opaque** 0.864 (**+0.094**)  | **singleAlpha** 7.37 (−3%)   | 0.769 / 7.59          |
| portrait | **alphaFloor0** 0.956 (+0.012) | **alphaFloor0** 1.69 (**−32%**) | 0.944 / 2.48       |
| skyline  | **noAdaptive** 0.935 (+0.012)  | **noAdaptive** 2.73 (−9%)    | 0.923 / 3.00          |
| mosaic   | **opaque** 0.768 (+0.027)      | **alphaFloor0** 4.56 (−4%)   | 0.741 / 4.73          |

Pooled means over the 5 images (core configs):

| config       | SSIM ↑      | ΔE00 ↓     | gen time | shapes fit | tool changes |
|--------------|-------------|------------|----------|------------|--------------|
| **noAdaptive** | **0.8170** | 4.266      | **22.9 s** | 13,358   | 4,608        |
| prune        | 0.8111      | 4.302      | +4.4 s¹  | 13,228     | 4,675        |
| default      | 0.8104      | 4.346      | 51.3 s   | 13,183     | 4,783        |
| noErrGuided  | 0.8065      | 4.454      | 54.2 s   | 13,139     | 4,827        |
| alphaFloor0  | 0.8038      | **4.073**  | 47.8 s   | 11,556     | 6,412        |
| singleAlpha  | 0.7949      | 5.600      | 68.7 s   | 15,177     | 2,787        |
| fastGen      | 0.7949      | 4.578      | 26.9 s   | 12,948     | 5,019        |
| opaque       | 0.7862      | 9.981      | 51.5 s   | 13,609     | 4,357        |

¹ prune reuses the default generation; 4.4 s is post-processing only.

Extras (texture+skyline only): `sa` 0.8062/3.38 at 79.5 s (default
0.7988/3.47 at 46.8 s); `hiStates` 0.8016/3.44 at 54.5 s; `exactRank`
0.8011/3.44 at 106.8 s; `alphaFloor2` 0.7677/5.13; `seedB` 0.7992/3.48.

## 3. Where SSIM and ΔE00 disagree — and why

They disagree exactly on the hard-edge images:

- **`opaque` (minAlpha=5, only α=255)** wins SSIM on glyphs (+0.094!) and
  mosaic (+0.027) but is pooled-worst on ΔE00 (9.98 vs 4.35 — 2.3×, and
  13.05 on mosaic, 10.49 on portrait). SSIM rewards crisp local structure —
  opaque circles make hard edges and clean fills. ΔE00 punishes it: with no
  translucent stacking, every pixel is quantized to the raw paint palette;
  blending is the only way to reach off-palette colors.
- **`alphaFloor0` (adds the α=23 rung)** is pooled-best ΔE00 (4.07) but only
  4th on SSIM: ultra-transparent glazing nails color on smooth content and
  smears structure on hard edges (loses both metrics on glyphs).

Practical reading: **ΔE00 is the right target for photographic content,
SSIM for text/pixel-art**; a config that wins only one of them is exploiting
that metric's blind spot. This conflict is what Proposal 2 resolves.

## 4. The quality-vs-clicks curve (default config)

SSIM at each budget, as % of the 18k value:

| image    | 3k    | 6k    | 9k    | 12k   | 15k   | 18k SSIM |
|----------|-------|-------|-------|-------|-------|----------|
| portrait | 95.4% | 97.2% | 98.4% | 99.1% | 99.6% | 0.944    |
| skyline  | 81.4% | 90.9% | 95.2% | 97.5% | 99.0% | 0.923    |
| texture  | 80.9% | 87.1% | 91.7% | 95.2% | 97.9% | 0.675    |
| mosaic   | 79.9% | 86.3% | 91.5% | 95.1% | 98.0% | 0.741    |
| glyphs   | 58.1% | 73.3% | 83.0% | 90.2% | 95.7% | 0.769    |

- **The decay is geometric, not kneed**: each additional 3k clicks buys
  ~0.65–0.8× the previous step's SSIM gain (ratio ≈ 0.75 average). There is
  no sharp cliff; the "knee" is a per-image budget where the marginal rate
  crosses whatever the user's time is worth.
- **12k clicks (6.7 min) retains ~95% of 18k SSIM pooled** (99.1% portrait
  … 90.2% glyphs); ΔE00 is 4–18% worse.
- Content split: smooth/structured images (portrait, skyline) are ≥95% done
  by 9k; dense hard-edge images (glyphs, mosaic, texture) are still climbing
  meaningfully at 18k and would keep improving past it.
- Marginal value at the 18k margin (15k→18k step, per 1,000 clicks):
  portrait +0.0012 SSIM, skyline +0.0031, texture/mosaic +0.0048,
  glyphs +0.0110. These rates price every "reclaimed clicks" proposal below.

## 5. Lever analysis

### Alpha policy (the biggest per-image lever, content-dependent)

Default is `minAlpha=1` (α ∈ {48,100,190,230,255}; `AppConstants.MIN_ALPHA_INDEX`).

| image    | floor0 (SSIM/ΔE) | default    | floor2      | singleAlpha | opaque      |
|----------|------------------|------------|-------------|-------------|-------------|
| texture  | **.691/3.15**    | .675/3.93  | .627/5.93   | .640/6.26   | .533/8.39   |
| glyphs   | .734/8.12        | .769/7.59  | —           | .780/**7.37** | **.864**/8.56 |
| portrait | **.956/1.69**    | .944/2.48  | —           | .906/4.08   | .890/10.49  |
| skyline  | .919/**2.84**    | **.923**/3.00 | .909/4.33 | .908/4.33  | .876/9.42   |
| mosaic   | .719/**4.56**    | **.741**/4.73 | —         | .740/5.95   | .768/13.05  |

- `alphaFloor0` pays ~1,600 extra tool changes (6,412 vs 4,783 pooled — alpha
  varies more, runs batch worse) and fits 1,600 fewer shapes, **and still
  wins both metrics on photographic content**: the α=23 glaze rung is worth
  more than the clicks it costs there. On hard edges it loses both.
- `singleAlpha` (Q2 off) has by far the fewest tool changes (2,787) and the
  most shapes (15,177) — and still loses: alpha *diversity* buys more than
  the clicks it costs. Click efficiency alone is not the objective.
- A **per-image oracle** choosing the best measured floor per image scores
  pooled **0.8159 SSIM / 4.03 ΔE00** (vs 0.8104/4.35 default) without ever
  using `opaque`; allowing `opaque` on glyphs+mosaic gives **0.840 pooled
  SSIM** (+0.030) at the price of +35% pooled ΔE00.

### Adaptive size (`noAdaptive` beats `default` at this budget)

Measured: SSIM +0.0067 texture, +0.0042 portrait, +0.0120 skyline, +0.0126
mosaic, −0.0025 glyphs (≈ noise); ΔE00 better on 3/5; gen time 51.3 → 22.9 s
pooled (portrait 78 → 17 s).

Why a per-*proposal* win turns into a per-*click* loss: `GradientMap`
(`selectSizeIndex`, weight `exp(−4·|sizeNorm − (1−gradient)|)`) biases toward
small circles in high-gradient cells. Under a click budget every shape costs
~1 click + ~0.3 amortized tool changes **regardless of area**, so per-click
value scales with covered area — the small-size bias overproduces low-value
shapes exactly where colors churn fastest (edges), which also fragments the
sorter's same-tool runs (default: 4,783 pooled tool changes and 13,183 shapes
fit vs 4,608 / 13,358 for noAdaptive). The generator optimizes error-per-shape
and is blind to click cost.

The gen-time gap has a second mechanism: `HillClimbGenerator.getHillClimbClassic`
resets its age counter on every improvement (`i = -1`), so climbs run until
`age` consecutive failures — the gradient-biased size re-draw finds
improvements more often and climbs far longer, and in smooth cells it favors
the largest circles, whose exact evaluations cost ∝ area.

### Tool-change composition (what a better ordering could reclaim)

Default @18k: tool changes consume **16.5% (texture) … 41.1% (mosaic)** of the
budget, pooled 26.6%. **Color changes are 67% of all tool changes** (pooled
16,095 clicks = 17.9% of the whole budget; mosaic: 5,378 = 30% of its budget).
Size (4,150) and alpha (3,670) split the rest. `groupBoundaryChanges` is
19–32 per image — cross-group stitching is already a solved non-problem; all
headroom is *within* the 1,000-blob sort groups.

### Prune (S1 budget-selection)

Consistently, cheaply positive: pooled +0.0008 SSIM, −0.044 ΔE00 (−1.0%), at
2.7–5.7 s post-processing. Free — keep it on (BALANCED preset already does).

### Generation-effort levers (quality vs gen-time only)

At a fixed click budget these barely matter — the plan is coverage-limited:

- `sa` (simulated annealing): +0.0074 SSIM / −0.084 ΔE (2-image pool) at
  **1.7× gen time**. The largest search-effort gain, still ~half of what the
  adaptive-size *policy* fix gives for negative cost.
- `hiStates` (states=1000): +0.0028 SSIM at 1.16× gen time.
- `exactRank` (proxy off): +0.0023 SSIM at **2.3×** gen time — proxy ranking
  validated again.
- `fastGen` (states=250, age=50): −0.0155 pooled SSIM, +0.23 ΔE for ~half the
  gen time — the measured quality price of the BLAZING generation settings at
  fixed clicks.
- `noErrGuided`: −0.0038 pooled SSIM, −0.017 on glyphs — error-guided
  placement earns its keep; keep it on.
- `seedB`: ±0.001 SSIM — establishes the noise floor.

---

## 6. Proposals, ranked by measured quality-per-click impact

### P1 — Turn adaptive size off at click budgets (then make it budget-aware)

- **Mechanism**: stop biasing shape sizes by local gradient; every shape
  costs a click, so per-click value ∝ covered area — see §5.
- **Evidence**: pooled **+0.0066 SSIM, −0.080 ΔE00, 2.2× faster generation**;
  wins 4/5 images on SSIM, only glyphs −0.0025 (≈ noise floor).
- **Expected gain**: the full measured delta, immediately; it is the only
  lever that improves quality and cost at once.
- **Implementation**:
  - One line: `AppConstants.USE_ADAPTIVE_SIZE = false`
    (`AppConstants.java:45`, flows into `GeneratorConfig.DEFAULT`), or per
    preset via `GeneratorConfig.withUseAdaptiveSize(false)` in `PaintPreset`.
  - Follow-up (recovers the glyphs sliver and may beat both): make
    `GradientMap.buildCumulativeWeights` (`GradientMap.java:169`) click-aware —
    tilt weights by per-click value, e.g. multiply by `(SIZES[i]²)^β` or
    soften the exponent (−4.0 → −2.0) only in high-gradient cells, keeping the
    (useful) large-in-smooth-cells preference. Calibrate β with one
    `bench.addConfigs` sweep run.

### P2 — Content-adaptive alpha floor (auto `minAlpha`)

- **Mechanism**: pick `minAlphaIndex` per image from a cheap content
  statistic instead of a fixed constant. The `GradientMap` is already
  computed from the target: high mean edge density ⇒ floor 1–2 (hard-edge
  regime); low ⇒ floor 0 (photographic glazing regime).
- **Evidence**: floor0 wins both metrics on texture (+0.016 SSIM / −20% ΔE)
  and portrait (+0.012 / **−32%** ΔE); default floor already right for
  skyline/mosaic/glyphs. Per-image oracle: pooled **+0.0056 SSIM,
  −0.31 ΔE00 (−7.2%)** — with no per-image loss, unlike shipping floor0
  globally (which costs −0.035 SSIM on glyphs).
- **Expected gain**: ≈ the oracle numbers if the classifier separates
  {texture, portrait} from {glyphs, mosaic} (skyline is a don't-care —
  ±0.004 either way). Likely near-additive with P1 (interaction unmeasured —
  verify, see §8).
- **Implementation**: `GeneratorConfig.minAlphaIndex` already exists and is
  enforced in `Circle.randomize`/`mutateShape`. Add an auto sentinel
  (`minAlpha=-1`), resolved once in the `Model` constructor from
  `GradientMap` cell stats (e.g. fraction of cells with gradient > 0.5).
  Calibrate the threshold against the 5-image corpus with
  `bench.addConfigs` forced-floor rows. Presets stay unchanged — auto is
  inside the generator.
- **Optional "stencil mode"**: expose `opaque` as an explicit user toggle for
  text/logo targets — **+0.094 SSIM on glyphs** is by far the largest single
  measured gain — but never auto-select it: its pooled ΔE00 (9.98) means
  visibly wrong colors everywhere else.

### P3 — Z-order-safe, color-run-maximizing scheduler in `BorstSorter`

- **Mechanism**: the greedy sorter picks the *first* non-colliding same-tool
  neighbor (`find_best_fast_cache`) and breaks a color run whenever a
  collision chain (or its own myopia) blocks it. Reordering any two
  *non-overlapping* blobs cannot change the render, and the full overlap
  DAG is already computed per group (`get_intersections`/`QTree`). Schedule
  by **maximal ready-batches**: repeatedly pick the tool state with the
  largest ready set (no unpainted DAG predecessors), emit that whole set
  plus same-tool blobs freed by the cascade, then switch tools.
- **Evidence**: color changes are 17.9% of the pooled budget (30% on
  mosaic); the greedy pass already proves ordering is worth 1.9–2.3×, and
  every reclaimed change converts 1:1 into an extra shape at the same budget
  with a bit-identical render (the harness's prefix search simply fits more).
- **Expected gain**: at a 15–30% color-change reduction: ~800 clicks/image
  pooled (mosaic 810–1,610) ⇒ **+0.003 pooled SSIM** by the §4 marginal
  rates (+0.004–0.008 on mosaic-like content, ~0 on portrait). Bounded but
  cheap to validate: re-sort the *existing* plans and diff `clicksOf` — no
  regeneration or repainting needed.
- **Implementation**: new strategy in `BorstSorter.sort0` behind a flag;
  keep the (size,color,alpha,shape)-keyed cache for tie-breaking within a
  batch. Note `USE_TSP_OPTIMIZATION` (`TwoOptOptimizer`) is currently
  `false` and optimizes travel distance — the wrong objective, since mouse
  travel is free; retire it in favor of pure change-count scheduling.

### P4 — Diminishing-returns auto-stop / click-budget picker

- **Mechanism**: the curve is geometric (§4) — fit the marginal
  error-reduction-per-click online and stop (or advise) when it drops below
  a threshold. All ingredients exist: `Model.shapeContributions` records
  each shape's exact error reduction, `PaintTimeEstimator`/`clicksOf` prices
  the plan, and the S1 `PaintPlan` instruction-list already supports
  truncation/resume.
- **Evidence**: 12k clicks retain ~95% of 18k SSIM pooled; portrait retains
  95.4% at **3k clicks** (1.7 min instead of 10). Nobody should pay 6,000
  clicks for portrait's last +0.006 SSIM.
- **Expected gain**: not quality — **time**: −33% paint time for a 0.9–9.8%
  relative SSIM cost, chosen per image instead of by fixed preset. Also the
  honest way to expose speed presets: "stop at 95% predicted quality"
  instead of opaque shape counts.
- **Implementation**: in `DrawDialog`, replace the raw shape-count spinner
  with a click/time target plus a live "predicted quality vs time" readout
  from the fitted curve (the estimator already renders live paint times);
  optionally add a `qualityStop=0.95` knob to `GeneratorConfig` for headless
  use.

### P5 — Preset ladder retune (`PaintPreset`)

- **MAX_QUALITY**: add `sa=true` (+0.0074 SSIM / −0.08 ΔE, 2-image evidence)
  — its 1.7× generation cost is the one place it's justified, since paint
  time dominates there. Keep `states=1000` (+0.003).
- **BALANCED**: adopt P1/P2 when they land; keep `maxLoss=0` pruning
  (measured free: +0.0008 SSIM, −0.044 ΔE00).
- **BLAZING**: unchanged — `fastGen`'s −0.0155 pooled SSIM at fixed clicks is
  now the *measured* draft-tier price, worth documenting in its tooltip.
- Do **not** ship `alphaFloor2`, `singleAlpha`, or `opaque` in any preset
  (all dominated; opaque only via the explicit P2 stencil toggle).

## 7. What did NOT help (negative results, for the record)

| lever | result at fixed 18k clicks | verdict |
|---|---|---|
| `sa` | +0.0074 SSIM at 1.7× gen time | MAX_QUALITY only (P5) |
| `hiStates` (1000) | +0.0028 SSIM at 1.16× | inside noise ×3; skip |
| `exactRank` (proxy off) | +0.0023 SSIM at 2.3× gen time | proxy ranking stays |
| `noErrGuided` | −0.0038 pooled, −0.017 glyphs | error-guided stays ON |
| `seedB` | ±0.001 SSIM | seed is noise, as hoped |
| `alphaFloor2` | −0.031 SSIM, +1.67 ΔE (2-img) | dominated at both ends |
| `singleAlpha` | −0.015 SSIM, +1.25 ΔE pooled | alpha diversity pays for its clicks |
| `opaque` as default | −0.024 SSIM, +5.63 ΔE pooled | metric-gaming; stencil toggle only |

The pattern: at a fixed click budget, spending more compute per shape
(`sa`/`hiStates`/`exactRank`) returns ≤+0.007 SSIM, while spending the *same*
clicks under a better policy (P1+P2) returns ~+0.012 pooled and up to +0.016
per image. The budget, not the optimizer, is the binding constraint.

## 8. Suggested follow-up measurements (one harness run each)

```bash
# P1×P2 interaction + budget-aware size tilt calibration:
./gradlew benchmark --tests com.bobrust.benchmark.ClickBudgetSweepTest \
  -Dbench.addConfigs="-combo:adaptiveSize=false;minAlpha=0,comboHi:adaptiveSize=false;minAlpha=2,noAdaptSa:adaptiveSize=false;sa=true"

# P3 validation is offline: re-sort the saved plans with the batch scheduler
# and compare clicksOf() — render-identical, so click delta is the whole story.
```
