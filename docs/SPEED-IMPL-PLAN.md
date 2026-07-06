# Speed/Quality Implementation Plan

Implementation plan for the top proposals of the click-budget study
(`docs/SPEED-QUALITY-PROPOSALS.md`, data in `docs/speed-quality-results.csv`).
Written against branch `fix/review-findings`. Plan only — no code has been
changed yet.

Build/test commands used throughout (source level 16 — records, switch
expressions and pattern-matching `instanceof` are fine; **no** sealed types,
no Java 17+ features):

```bash
JAVA_HOME=/usr/lib/jvm/java-17-amazon-corretto.aarch64 ./gradlew test
JAVA_HOME=/usr/lib/jvm/java-17-amazon-corretto.aarch64 ./gradlew benchmark \
  --tests com.bobrust.benchmark.ClickBudgetSweepTest -Dbench.addConfigs=...
```

## Step overview (implementation order)

| # | Step | Proposal | Default behavior change? |
|---|------|----------|--------------------------|
| S1 | Flip adaptive size OFF by default | P1a | **YES** — flagged below |
| S2 | Auto alpha floor (`minAlpha=-1` sentinel) | P2 | no — opt-in |
| S3 | Click-aware size weight (`sizeBias` knob) | P1b | no — opt-in, default 0 |
| S4 | Diminishing-returns auto-stop (`qualityStop` knob) | P4 | no — opt-in, default 0 |
| S5 | Preset retunes + stencil toggle in DrawDialog | P5 + P2b | **YES** for preset users (gated) |
| S6a | Color-batch scheduler in BorstSorter (flag OFF) + validation | P3 | no |
| S6b | Flip scheduler flag ON after gates pass | P3 | **YES** — paint order (render-identical) |

Ordering rationale: S1 is a one-line, data-proven strict win (quality AND
speed). S2 is the second-largest measured lever and pure plumbing. S3 and S4
are small opt-in knobs with calibration procedures. S5 wires the confirmed
winners into the preset ladder. S6 (sorter scheduler) is last because it is
the only step where a bug corrupts painted output rather than just quality —
it ships behind a flag with an offline bit-identity validator before the flag
flips.

Each step is one reviewable commit (S6 is two) and leaves the build green on
its own.

---

## S1 — Adaptive size OFF by default (P1a)

**Evidence** (proposals §5, §6-P1): at the 18k-click budget `noAdaptive` beats
`default` on pooled SSIM (+0.0066) and pooled ΔE00 (−0.08), wins SSIM on 4/5
images (glyphs −0.0025, barely above the ±0.001 seed-noise floor), and
generates 2.2× faster (51.3 s → 22.9 s pooled; portrait 78 → 17 s). It is the
only lever that improves quality and cost simultaneously, so it qualifies for
a default flip.

### Change

1. `src/main/java/com/bobrust/util/data/AppConstants.java:45`
   — `boolean USE_ADAPTIVE_SIZE = true;` → `false`.
   Rewrite the comment block (lines 43–45): keep the mechanism description,
   add the provenance: *"Default false since the click-budget study: a shape
   costs ~1 click regardless of area, so the small-size bias overproduces
   low-per-click-value shapes at edges; measured pooled +0.0066 SSIM,
   −0.08 ΔE00 and 2.2× faster generation at 18k clicks with it off
   (docs/SPEED-QUALITY-PROPOSALS.md P1). Re-enable per config with
   `adaptiveSize=true`."*
2. `src/main/java/com/bobrust/generator/GeneratorConfig.java` — no code
   change (`DEFAULT` reads the constant at line 41). Extend the class javadoc
   sentence listing reachable legacy behaviors: pre-P1 behavior stays
   reachable with `"adaptiveSize=true"`.

No new knobs. Reversal is `adaptiveSize=true` in the settings config string
(`Settings.SettingsGeneratorConfig`) or `withUseAdaptiveSize(true)`.

**Side effect to document in the AppConstants comment:** with the flag off,
`Model`'s constructor (Model.java:76) creates no `GradientMap` at all, so
`Circle.mutateShape`'s position-mutation scale (Circle.java:63) also reverts
to the fixed 1.0. This is exactly what the `noAdaptive` benchmark rows
measured — the quoted gains already include it.

### Existing tests to update

- `GeneratorConfigTest.defaultsArePhaseGTunedValues` —
  `assertTrue(config.useAdaptiveSize())` becomes
  `assertFalse(config.useAdaptiveSize(), "P1: adaptive size loses at a click budget — see SPEED-QUALITY-PROPOSALS.md")`.
- `AdaptiveSizeSelectionTest.runGenerator(...)` currently builds
  `new Model(target, BACKGROUND, ALPHA)` (default config) and *removes* the
  gradient map reflectively for the uniform arm. After the flip the default
  has no gradient map, so the "adaptive" arm silently becomes uniform. Fix:
  build `new Model(target, BACKGROUND, ALPHA, GeneratorConfig.DEFAULT.withUseAdaptiveSize(useAdaptiveSize))`
  and delete the now-dead reflective `setGradientMap` helper (the
  `worker.setGradientMap(null)` call too). Everything else in that test file
  stays.

### Tests to add

- `GeneratorConfigTest` (same file, same style): a small
  `defaultModelHasNoGradientMap()` — build a `Model` on the existing
  `testImage()` with `GeneratorConfig.DEFAULT`, assert
  `model.getWorker().getGradientMap() == null` and that
  `withUseAdaptiveSize(true)` produces a non-null one. (`getWorker()` is
  package-private; the test is already in `com.bobrust.generator`.)

### A/B verification

Generation is fully seeded, so this is a table-diff, not a judgment call:

```bash
JAVA_HOME=... ./gradlew benchmark --tests com.bobrust.benchmark.ClickBudgetSweepTest \
  -Dbench.addConfigs="-newDefault:,oldDefault:adaptiveSize=true" -Dbench.out=docs/ab-s1.csv
```

Expected: `newDefault` rows byte-identical (modulo `genSeconds`) to the
committed CSV's `noAdaptive` rows; `oldDefault` rows identical to the old
`default` rows. Any other outcome means the flip did more than intended.

### ⚠ Default-output flag

**This changes generated output for every existing user** (all presets derive
from `GeneratorConfig.DEFAULT`, and presets equal to DEFAULT store `null` and
track it). Users with a hand-tweaked persisted config string are unaffected —
`serialize()` always writes `adaptiveSize=` explicitly. Call this out in the
commit message and changelog.

---

## S2 — Content-adaptive alpha floor: auto `minAlpha` (P2)

**Evidence** (proposals §5-alpha, §6-P2): `alphaFloor0` wins both metrics on
photographic content (portrait ΔE00 −32%, texture −20%) and loses both on
hard edges (glyphs −0.035 SSIM). The per-image oracle choosing the best floor
is pooled +0.0056 SSIM / −0.31 ΔE00 with **no** per-image loss. No fixed
floor is right → resolve it per image. Stays opt-in in this step (the data
is content-dependent, so it does not qualify for a global default flip);
presets adopt it in S5 behind a measurement gate.

### Change 1 — `GeneratorConfig`: the `-1` auto sentinel

`src/main/java/com/bobrust/generator/GeneratorConfig.java`:

- Add `public static final int MIN_ALPHA_AUTO = -1;` with javadoc: *"Sentinel
  for minAlphaIndex: resolve the floor per image from the target's edge
  statistics (GradientMap) in the Model constructor. Never reaches
  Worker/Circle unresolved."*
- Compact constructor validation (line 58) becomes: allow
  `minAlphaIndex == MIN_ALPHA_AUTO` OR the existing `[0, ALPHAS.length-1]`
  range; reject everything else with the same IAE.
- `parse`/`serialize` need no change (`minAlpha=-1` already flows through
  `Integer.parseInt`; the relaxed validation makes it stick).

### Change 2 — `GradientMap`: absolute edge statistic

`src/main/java/com/bobrust/generator/GradientMap.java`, inside `compute()`:

The existing `cellGradients` are **normalized per image** (divided by the max,
lines 107–112), which destroys exactly the signal the classifier needs: a
uniform mid-amplitude texture normalizes up to look like a hard-edge image.
The classifier therefore uses a *raw-magnitude* statistic captured before
normalization:

- New field `private float hardEdgeFraction;` + getter
  `public float getHardEdgeFraction()`.
- New constant `static final float HARD_EDGE_MAGNITUDE = 500f;` — the raw
  Sobel magnitude that counts as a hard edge. A full-contrast 0→255 step
  produces magnitude ≈1020 (4·255) and the theoretical max is ≈1442
  (4·255·√2), so 500 ≈ "at least half a full-contrast step". Placeholder —
  pinned by the calibration test below.
- In the existing per-pixel Sobel loop (lines 74–94), count
  `if (magnitude > HARD_EDGE_MAGNITUDE) hardEdgeCount++;` and set
  `hardEdgeFraction = hardEdgeCount / (float) totalInteriorPixels` after the
  loop. One extra compare per pixel; no allocation.

### Change 3 — `Model`: resolve the sentinel before the Worker exists

`src/main/java/com/bobrust/generator/Model.java`, constructor (lines 48–81),
restructured at the top:

```java
GradientMap gradientMap = null;
if (config.useAdaptiveSize() || config.minAlphaIndex() == GeneratorConfig.MIN_ALPHA_AUTO) {
    gradientMap = new GradientMap(w, h);
    gradientMap.compute(target);
}
if (config.minAlphaIndex() == GeneratorConfig.MIN_ALPHA_AUTO) {
    int resolved = resolveAutoMinAlpha(gradientMap);
    AppConstants.LOGGER.info("Auto alpha floor: hardEdgeFraction={} -> minAlphaIndex={}",
        "%.4f".formatted(gradientMap.getHardEdgeFraction()), resolved);
    config = config.withMinAlphaIndex(resolved);
}
this.config = config;
...
this.worker = new Worker(target, alpha, config);   // sees the RESOLVED config
if (this.config.useAdaptiveSize()) {
    this.gradientMap = gradientMap;
    this.worker.setGradientMap(gradientMap);
}
```

Plus the classifier itself, package-private static for direct testability:

```java
/** Fraction of pixels that must be hard edges before glazing (floor 0) hurts. Calibrated on the 5-image corpus. */
static final float AUTO_ALPHA_EDGE_THRESHOLD = 0.02f; // placeholder — pinned by AutoAlphaContentStatsTest
static int resolveAutoMinAlpha(GradientMap map) {
    return map.getHardEdgeFraction() >= AUTO_ALPHA_EDGE_THRESHOLD ? AppConstants.MIN_ALPHA_INDEX : 0;
}
```

The classifier only ever returns `0` (photographic glazing regime) or the
shipped default `AppConstants.MIN_ALPHA_INDEX` (=1, hard-edge regime). It can
**never** return 5/opaque — the never-auto guarantee for stencil mode is by
construction.

Key point about the GradientMap: when `adaptiveSize=false` (the S1 default)
but the floor is auto, the map is computed **for statistics only** and NOT
attached to the worker, so size selection stays uniform. One extra Sobel pass
per generation start (O(w·h), a few ms at 384²) is the whole cost.

### Where `minAlphaIndex` is read (unchanged, for the record)

Resolution-before-Worker means **zero changes** in the selection path. The
resolved value is consumed at exactly two sites, both via
`worker.getConfig().minAlphaIndex()`:

- `Circle.randomize(ErrorMap)` — `Circle.java:125-126`: per-shape alpha drawn
  uniformly from `[min, ALPHAS.length-1]` when Q2 is on.
- `Circle.mutateShape()` — `Circle.java:72-73`: the alpha mutation (kind==3)
  clamps one palette step to `[min, ALPHAS.length-1]`.

Defensive guard: `Worker`'s constructor (Worker.java:44) gains
`if (config.minAlphaIndex() < 0) throw new IllegalArgumentException("unresolved auto minAlpha reached Worker");`
so a future caller that bypasses Model's resolution fails loudly instead of
letting `rnd.nextInt(ALPHAS.length - (-1))` silently widen the alpha range.

### Consumers checked (no further changes)

- `BorstGenerator.BorstData.update` reads `model.getConfig()` only for
  `usePerShapeAlpha()` — resolved config is fine.
- `DrawDialog.applyPreset` / `PaintPreset.matchesCurrentSettings` compare
  *settings-level* configs; resolution happens inside Model only, so a stored
  `minAlpha=-1` round-trips unchanged and preset matching is unaffected.

### Tests to add

- `GeneratorConfigTest`: `minAlpha=-1` parses and round-trips through
  `serialize()`; `withMinAlphaIndex(-2)` and `(6)` still throw;
  `withMinAlphaIndex(GeneratorConfig.MIN_ALPHA_AUTO)` does not.
- New `AutoAlphaFloorTest` in `com.bobrust.generator` (same package →
  package-private access, following `GeneratorConfigTest` style):
  - hard-edge target (`ComplexTestImages.createGlyphs()` — already in this
    test package, deterministic) + `minAlpha=-1` → assert
    `model.getConfig().minAlphaIndex() == AppConstants.MIN_ALPHA_INDEX`.
  - smooth target (`TestImageGenerator.createGradient()`) → resolves to 0.
  - explicit `minAlpha=2` → untouched (no resolution, no log).
  - auto + `adaptiveSize=false` → floor resolved AND
    `model.getWorker().getGradientMap() == null` (stats-only map is not
    attached).
  - a 10-step `processStep()` run with the resolved config produces only
    alpha indices ≥ the resolved floor (inspect `model.shapes`).
- New `AutoAlphaContentStatsTest` in `com.bobrust.generator` (fast unit test —
  5 Sobel passes at 384², no generation): compute `hardEdgeFraction` for all
  five `ComplexTestImages.corpus()` images, print the values, and assert the
  calibrated separation: glyphs and mosaic ≥ `AUTO_ALPHA_EDGE_THRESHOLD`,
  texture and portrait < it (skyline is a measured don't-care — ±0.004 SSIM
  either way — assert nothing about it, but print it). This test is what pins
  `HARD_EDGE_MAGNITUDE`/`AUTO_ALPHA_EDGE_THRESHOLD`: implement the stats,
  print, choose constants with ≥2× margin between the closest pair, then
  freeze the assertions.

### A/B verification (also the S5 preset gate)

Auto-resolution just *selects* one of the already-measured configs per image,
so the strongest possible check is available: each `autoAlpha` row must be
**bit-identical** to the corresponding forced-floor row.

```bash
# a) auto == the intended forced floor per image, on the new (S1) default:
JAVA_HOME=... ./gradlew benchmark --tests com.bobrust.benchmark.ClickBudgetSweepTest \
  -Dbench.addConfigs="-autoAlpha:minAlpha=-1,floor0:minAlpha=0" -Dbench.out=docs/ab-s2.csv
```

Expected (proposals §6-P2): `autoAlpha` ≡ `floor0` rows on texture+portrait,
≡ the (new-default) rows on glyphs+mosaic; pooled ≈ +0.005 SSIM / −0.3 ΔE00
vs default with no per-image loss. This run doubles as the P1×P2 interaction
measurement from proposals §8 (floor 0 must still win texture/portrait with
adaptive size off) — if the interaction breaks the win, the classifier
threshold is re-examined before S5 adopts anything.

No default change in this step: `minAlpha=-1` is reachable only via an
explicit config string until S5.

---

## S3 — Click-aware size weight: `sizeBias` (P1b, experimental)

**Evidence**: indirect — this is the proposals' follow-up to P1 ("recovers
the glyphs sliver and may beat both"), not yet measured. Ship as a dormant
knob (default 0 = bit-identical off) plus the calibration sweep that decides
whether it ever turns on. If the sweep disappoints, the knob stays at 0 and
costs nothing.

### Change 1 — `GeneratorConfig`: new component

- Record gains `double sizeClickBias` (append after `useAdaptiveSize` in the
  component list for readability; all 11 existing `with*` methods and
  `DEFAULT` update mechanically; add `withSizeClickBias(double)`).
- Default `0.0`. Validation in the compact constructor:
  `Double.isFinite(sizeClickBias) && sizeClickBias >= 0 && sizeClickBias <= 2`
  else IAE (parse's existing catch keeps the default for malformed values).
- `serialize()`: `";sizeBias=" + sizeClickBias` after the `adaptiveSize` key;
  `parse` case `"sizeBias" -> config.withSizeClickBias(Double.parseDouble(value))`.
- Update `GeneratorConfigTest.serializeParseRoundTrips`'s explicit-constructor
  case for the new arity, and `defaultsArePhaseGTunedValues` asserts `0.0`.

### Change 2 — `GradientMap`: biased weight table

- Constructor plumbing: add `GradientMap(int imageWidth, int imageHeight, double sizeClickBias)`;
  the existing constructors delegate with bias 0. Store as a final field.
  `Model`'s constructor passes `config.sizeClickBias()` where it builds the
  map (both the attached and stats-only paths from S2 — harmless for stats).
- `buildCumulativeWeights` (GradientMap.java:169): multiply each weight by
  `(SIZES[i]²)^β` — area-per-click tilt on top of the gradient prior:

```java
for (int i = 0; i < numSizes; i++) {
    float sizeNorm = (float) i / (numSizes - 1);
    double weight = Math.exp(-4.0 * Math.abs(sizeNorm - (1.0 - gradient)));
    if (sizeClickBias != 0) {
        int d = BorstUtils.SIZES[i];
        weight *= Math.pow((double) d * d, sizeClickBias);
    }
    cumulative += (float) weight;
    table[cell * numSizes + i] = cumulative;
}
```

  Bit-identity at β=0 is guaranteed by the branch: the β=0 path evaluates the
  exact original expression in the original accumulation order (the class doc
  already promises bit-identical selection — extend that doc sentence to note
  the β=0 guarantee). Weight magnitudes stay tiny (d≤13 ⇒ d²≤169 ⇒ ≤169²
  at β=2) — no float-range concern.
- Javadoc on the knob: *"Only meaningful together with `adaptiveSize=true`
  (no GradientMap otherwise). β=1 ≈ selection probability proportional to
  covered area × gradient prior."* Note in the comment (not code) the
  proposals' alternative — softening the exponent −4→−2 in high-gradient
  cells — as the fallback calibration direction if the β sweep loses.

### Tests to add

- Extend `AdaptiveSizeSelectionTest` (it already owns GradientMap coverage):
  - `biasZeroIsBitIdentical`: two maps over the same edge image, one via the
    legacy constructor and one with bias 0.0 — identical `selectSizeIndex`
    sequences over 10k draws from two `new Random(42)`s.
  - `biasShiftsSelectionTowardArea`: on the sharp-edge image, at the edge
    cell, mean selected size index with β=1 strictly greater than with β=0
    (5k samples, seeded).
- `GeneratorConfigTest`: `sizeBias=0.5` round-trip; `sizeBias=abc` and
  `sizeBias=-1` keep the default; `withSizeClickBias(3)` throws.

### A/B verification

```bash
JAVA_HOME=... ./gradlew benchmark --tests com.bobrust.benchmark.ClickBudgetSweepTest \
  -Dbench.addConfigs="-adB25:adaptiveSize=true;sizeBias=0.25,adB50:adaptiveSize=true;sizeBias=0.5,adB100:adaptiveSize=true;sizeBias=1.0" \
  -Dbench.out=docs/ab-s3.csv
```

Decision rule: a β wins only if it beats the S1 default (`noAdaptive`
behavior) on pooled SSIM **and** ΔE00 and recovers glyphs (which was
−0.0025). If yes → that β becomes a candidate for `AppConstants`/preset
adoption in a follow-up (own gate, own commit); if no → knob stays 0,
document the negative result in SPEED-QUALITY-PROPOSALS.md §7.

No default change in this step under any outcome.

---

## S4 — Diminishing-returns auto-stop: `qualityStop` (P4)

**Evidence** (proposals §4, §6-P4): the SSIM-vs-clicks curve is geometric
(each +3k clicks buys ~0.75× the previous step's gain); 12k clicks retain
~95% of the 18k SSIM pooled, portrait retains 95.4% at 3k. The win is time,
not quality — and it is per-image, which no fixed preset can express.

Scope decision: this step ships the **generator-side stop mechanism** (the
headless knob from the proposal) plus a log line. The DrawDialog
click/time-target + live predicted-quality-curve redesign from the proposal
is real UI work with no measurement risk — deferred to a follow-up UI change,
noted in S5's DrawDialog work as a TODO comment.

### Mechanism

All ingredients exist: `Model.getShapeContributions()` records each shape's
exact error reduction (long, energy metric). The stop rule works on trailing
windows of that list; clicks-per-shape is roughly constant within a run
(≈1.3), so per-shape windows are a faithful proxy for per-click rate — the
harness gate below measures the real click savings.

Rule (window size `W = 500` shapes, constant): let `S1` = sum of
contributions in the previous window, `S2` = sum in the latest window,
`A` = sum of all contributions so far (= initialError − currentError, both
exact longs). Model the remaining gain as the geometric tail
`R = S2 · r / (1 − r)` with window ratio `r = S2/S1` (only when `r < 1`).
Stop when the achieved fraction `A / (A + R) ≥ qualityStop`. Never evaluate
before two full windows; never stop when `r ≥ 1` or `S1 == 0`.

`qualityStop = 0.95` therefore means "stop when ≥95% of the projected
achievable error reduction is already banked" — an energy-metric proxy for
the SSIM curve, honest about being a proxy; the calibration run maps it to
realized SSIM retention.

### Changes

1. `GeneratorConfig`: record gains `double qualityStop`, default `0.0` (off).
   Validation: `isFinite && 0 <= qualityStop && qualityStop < 1`. Serialize
   key `qualityStop`. Same mechanical `with*`/test updates as S3.
2. New `src/main/java/com/bobrust/generator/DiminishingReturns.java` —
   package-private final class with one pure static method:
   `static boolean reached(List<Long> contributions, double qualityStop, int window)`
   implementing the rule above (no state, no RNG, no I/O — trivially
   testable). Javadoc carries the geometric-tail derivation and the §4
   evidence pointer.
3. `BorstGenerator.start` generation loop (BorstGenerator.java:81): after the
   callback-interval block (so at most once per `callbackInterval` steps, and
   cheap — one list scan of 2W entries):

```java
if (config.qualityStop() > 0
        && DiminishingReturns.reached(model.getShapeContributions(), config.qualityStop(), 500)) {
    LOGGER.info("Auto-stop: diminishing returns at {} shapes (qualityStop={})", i, config.qualityStop());
    break;
}
```

   `BorstGenerator` receives the config today only transitively through the
   Model — add a local `GeneratorConfig config = model.getConfig();` before
   the loop (accessor is package-private; same package). The existing
   `finally` block already publishes the final index, so DrawDialog's slider
   max simply stops growing — no UI change required for correctness.
4. Harness hook so the knob is measurable: in
   `ClickBudgetSweepTest.generateUntilOverBudget`, after each chunk, apply the
   same check (`config.qualityStop() > 0 && DiminishingReturns.reached(...)`)
   and break. `DiminishingReturns` is package-private in
   `com.bobrust.generator`; expose it to the test via a public static
   pass-through is unnecessary — move nothing: the sweep test is in
   `com.bobrust.benchmark`, so make the class and method **public** (it is a
   documented, stable utility; public is fine and avoids reflection).

### Tests to add

New `DiminishingReturnsTest` in `com.bobrust.generator` (plain unit test,
synthetic lists — instant):

- Geometric contributions with per-window ratio 0.75: `reached(..., 0.95, W)`
  first fires at the analytically expected window (assert the exact index:
  with r=0.75 the achieved fraction crosses 0.95 when the remaining tail
  `S2·r/(1−r)` < 5% of the projected total — precompute in the test).
- Flat contributions (r = 1): never fires.
- `qualityStop = 0`: never fires (loop guard, but assert the function too).
- Fewer than 2W entries: never fires.
- Monotonicity: on the same list, `reached(0.5)` fires no later than
  `reached(0.9)`.
- `GeneratorConfigTest`: round-trip `qualityStop=0.95`; `qualityStop=1` and
  `=-0.1` rejected; default 0.

### A/B verification

```bash
JAVA_HOME=... ./gradlew benchmark --tests com.bobrust.benchmark.ClickBudgetSweepTest \
  -Dbench.addConfigs="-stop95:qualityStop=0.95,stop90:qualityStop=0.90" -Dbench.out=docs/ab-s4.csv
```

Expected shape (from §4): portrait stops earliest (its curve is ≥95% done by
3–9k clicks), glyphs latest (still climbing at 18k → may not stop at all
inside the budget — correct behavior). Acceptance: at `stop95`, pooled SSIM
retention ≥ ~93% of the 18k default value with ≥25% pooled click savings;
report the per-image table in the PR. Calibrate the default-recommended value
(0.95 vs 0.90) from this table before S5 mentions it in any tooltip.

No default change: `qualityStop=0` ships everywhere.

---

## S5 — Preset retunes + stencil toggle (P5 + P2b)

All changes here are parameter/UI wiring on top of S1–S4. Two of them change
behavior for preset users and are **gated on the earlier steps' A/B runs**.

### Change 1 — `PaintPreset` (settings/data/PaintPreset.java)

- **MAX_QUALITY**: `GeneratorConfig.DEFAULT.withMaxRandomStates(1000)` →
  `.withMaxRandomStates(1000).withUseSimulatedAnnealing(true)`.
  Evidence: `sa` +0.0074 SSIM / −0.08 ΔE00 at 1.7× generation time — paint
  time dominates in this preset, so the generation cost is the one place it's
  justified (proposals §6-P5). **Gate before committing**: the sa evidence is
  2-image; rerun extras on all five images on the new S1 default:

  ```bash
  JAVA_HOME=... ./gradlew benchmark --tests com.bobrust.benchmark.ClickBudgetSweepTest \
    -Dbench.addConfigs="-noAdaptSa:sa=true" -Dbench.out=docs/ab-s5-sa.csv
  ```

  Adopt only if pooled SSIM and ΔE00 both improve vs the new default.
- **BALANCED** and **FAST**: adopt the auto alpha floor —
  `GeneratorConfig.DEFAULT.withMinAlphaIndex(GeneratorConfig.MIN_ALPHA_AUTO)`
  — **gated on the S2 A/B** (auto ≡ intended forced floor per image, pooled
  win, no per-image loss). Keep `maxLoss=0` pruning in BALANCED (measured
  free: +0.0008 SSIM, −0.044 ΔE00).
- **BLAZING**: parameters unchanged. Tooltip gains the measured price (see
  Change 3).
- Update the class javadoc provenance list (it explicitly demands a matching
  measurement run per retune — cite `docs/ab-s2.csv` / `docs/ab-s5-sa.csv`
  and the sweep harness instead of `PruneBenchmarkTest.presetLadder` alone).
- Do **not** ship `alphaFloor2`, `singleAlpha`, or `opaque` in any preset
  (all dominated — proposals §7).

Note on storage semantics: BALANCED currently equals `DEFAULT` and stores
`null` (tracks future defaults). After adopting auto-alpha it differs from
DEFAULT and stores a full serialized snapshot — existing `apply()` semantics,
but worth a sentence in the commit message.

### Change 2 — Stencil (opaque) toggle in DrawDialog

Generator-side there is nothing to build: stencil mode IS `minAlpha=5` (the
measured `opaque` config, +0.094 SSIM on glyphs — the largest single measured
gain — at 2.3× pooled ΔE00). The auto classifier can never select it (S2, by
construction), so it must be an explicit user choice.

`gui/dialog/DrawDialog.java`:

- Add a `JCheckBox stencilCheckbox = new JCheckBox("Stencil (text / logos)")`
  under the preset row (constructor, after the `presetPanel` block).
  Tooltip: *"Opaque paint only: much sharper text and hard edges, visibly
  wrong colors on photos. Never chosen automatically."*
- Listener: read `GeneratorConfig current = Settings.getGeneratorConfig()`;
  ON → `Settings.SettingsGeneratorConfig.set(current.withMinAlphaIndex(5).serialize())`;
  OFF → restore the floor to the active preset's value if a non-CUSTOM preset
  is selected, else `AppConstants.MIN_ALPHA_INDEX`. Then `markCustom()` is
  NOT called for the preset-restore path; the ON path calls `markCustom()`
  (it is a hand-tweak by definition). Both paths end with
  `restartGenerationFresh()` + `scheduleEstimate()` — same pattern as
  `applyPreset` (the config changed, the model is stale).
- `syncPresetFromSettings()` and `applyPreset(...)` set the checkbox state
  from `Settings.getGeneratorConfig().minAlphaIndex() == 5` inside the
  existing `suppressDirty` guard, so preset application unchecks it
  consistently.
- Leave a `// TODO(P4 follow-up): replace the raw shape spinner with a
  click/time target + predicted-quality readout (docs/SPEED-IMPL-PLAN.md S4)`
  comment where the estimate labels are built.

### Change 3 — Tooltips (same file, constructor `switch`)

- BLAZING: append "≈ −0.015 SSIM vs Balanced at the same clicks (draft
  tier)" — the now-measured `fastGen` price.
- MAX_QUALITY (`default ->` branch): mention "simulated annealing" once S5's
  gate passes.

### Tests

- New `PaintPresetTest` in `src/test/java/com/bobrust/settings/` (plain
  JUnit, no Settings statics — assert on `PaintPreset.*.getParams()` records
  only): MAX_QUALITY has `useSimulatedAnnealing() && maxRandomStates()==1000`;
  BALANCED/FAST have `minAlphaIndex()==GeneratorConfig.MIN_ALPHA_AUTO` and
  prune options as documented; BLAZING pins states=250/age=50. This turns the
  javadoc provenance into executable pins.
- Rerun the existing benchmark ladder for the record:
  `./gradlew benchmark --tests com.bobrust.benchmark.PruneBenchmarkTest`.

### ⚠ Default-output flags

- MAX_QUALITY output changes (sa on) — gated, evidence-backed.
- BALANCED is the **default preset**: adopting auto-alpha changes default-path
  output on photographic images (that is the point — portrait ΔE00 −32%), and
  is a no-op on hard-edge images if the classifier is right. Gated on the S2
  bit-identity A/B. Flag prominently in the changelog.
- DrawDialog gains one visible control.

---

## S6 — Z-order-safe color-batch scheduler in `BorstSorter` (P3) — riskiest, last

**Evidence** (proposals §5-toolchanges, §6-P3): tool changes are 26.6% of the
pooled budget; 67% of them are color changes (17.9% of the whole budget; 30%
on mosaic). Every reclaimed change converts 1:1 into an extra shape at the
same budget. Expected: 15–30% color-change reduction ⇒ ~+0.003 pooled SSIM,
up to +0.008 on mosaic-like content, ~0 on portrait (priced by the §4
marginal rates — bounded, but cheap to validate offline).

### The z-order invariant (precise statement)

The sorter operates per `AppConstants.MAX_SORT_GROUP` (=1000) window of the
generation order; windows are concatenated in order and never merged
(measured cross-boundary headroom is 19–32 clicks — a non-problem).

Within a window, for generation indices `i < j`, blob `j` **must** be painted
after blob `i` iff both:

1. their circles overlap: `(xᵢ−xⱼ)² + (yᵢ−yⱼ)² < (rᵢ+rⱼ)²`, and
2. they are distinguishable: NOT (same `size` AND same `color`) — the
   existing exemption in `get_intersections` (BorstSorter.java:327), safe
   because equal-color stamps commute under the blend for any alphas
   (verified today by `BorstSorterAlphaTest.mixedAlphaSameColorRenderIsOrderIndependent`;
   equal size+color is a strict subset).

Any order satisfying this partial order renders **bit-identically** to
generation order: constrained pairs keep their compositing order, exempt
overlapping pairs commute, and non-overlapping stamps touch disjoint pixels.
The existing DAG is exactly this relation — `map[i]` from
`get_intersections` lists i's predecessors — and the scheduler **reuses it
verbatim** (same QTree, same exemption; do not weaken it). The current greedy
already depends on the same invariant; its dead-end fallback
(`first_non_null_index` = lowest unpainted index, whose predecessors are all
painted by construction) is what the batch scheduler replaces with something
less myopic.

### Change 1 (commit 6a-prep) — retire the TSP dead end

`USE_TSP_OPTIMIZATION` optimizes travel distance, which is free
(`Robot.mouseMove` teleports), and is precedence-unsafe (its own comment,
AppConstants.java:90–98). Per proposals §6-P3, retire it: delete the flag,
the `TSP_W_*` constants, the `if (AppConstants.USE_TSP_OPTIMIZATION …)` block
in `BorstSorter.sort` (lines 155–159), `TwoOptOptimizer.java`, and
`TwoOptOptimizerTest.java`. Behavior change: none (flag is `false`). Small
standalone commit so the scheduler diff stays clean.

### Change 2 (commit 6a) — the batch scheduler, flag OFF

`src/main/java/com/bobrust/generator/sorter/BorstSorter.java`:

- New constant in `AppConstants`: `boolean USE_BATCH_COLOR_SCHEDULER = false;`
  with a comment pointing at this plan + the validation gates. `sort(BlobList, int)`
  keeps its signature; add a package-private
  `sort(BlobList data, int size, boolean batchScheduler)` overload so tests
  and the harness A/B both strategies without recompiling; the public method
  passes the constant.
- New private `sortBatched(Piece[] array, int size)` alongside `sort0`
  (per-group, same inputs/outputs). Algorithm — deterministic maximal
  ready-batch scheduling over the existing DAG:

```
build map[] exactly as sort0 does today (QTree + get_intersections, parallel)
indegree[i] = map[i].size(); successor lists: for each i, for each p in map[i]: succ[p].add(i)
readyByKey  = IntList[cacheKey table size]      // reuse cacheKey(size,color,alpha,shape)
readyCount[key], totalReady
seed: for i ascending, if indegree[i]==0 → readyByKey[key(i)].add(i)
currentKey = argmax readyCount (tie: lowest key)          // note: first blob need not be index 0
while emitted < n:
    while readyByKey[currentKey] non-empty:
        b = take lowest index from readyByKey[currentKey]  // FIFO cursor, O(1)
        emit b; for s in succ[b]: if --indegree[s]==0 → readyByKey[key(s)].add(s)
        // successors with key(s)==currentKey re-enter this loop: the cascade
    if emitted == n: break
    next key policy (deterministic):
      1. enumerate the ≤(6−1)+(64−1)+(6−1)+(4−1)=76 keys differing from
         currentKey in exactly ONE dimension (same loops as create_cache's
         "either" table); pick max readyCount, tie → fewest… (they're all 1
         change) → lowest key index
      2. if all empty: scan the full key table for max readyCount; tie →
         fewer differing dimensions from currentKey, then lowest key index
    currentKey = chosen
assert emitted == n  // DAG edges go low→high generation index: acyclic by construction
```

- Cost: n ≤ 1000 per group; E = existing overlap-pair count (already computed
  today); key-table scans are 9,216 ints per switch × at most a few thousand
  switches — microseconds. Log per-group timing under `AppConstants.DEBUG_TIME`
  like `sort` does.
- Determinism requirements (tests rely on it): no hash-order iteration, ties
  broken by lowest index, seeding in ascending index order. The `map` build
  keeps its `IntStream.parallel()` — writes are to disjoint slots, results
  deterministic.
- The greedy `sort0`, `create_cache`, `find_best_fast_cache` stay untouched —
  they remain the flag-off path and the A/B baseline.

Starting-state note (flag in review): the greedy always emits generation-blob
0 first; the scheduler starts at the largest ready batch. The painter's
initial tool setup is a constant 4 clicks either way
(`PaintTimeEstimator`/`RustUtil.getScore` book it identically), and
`BobRustPainter` keys its setup off the first blob of whatever list it gets —
no hidden dependency on "first = generation-first" (PaintPlan instructions
are in-memory only; resume cursors index the plan, not generation order).

### Tests to add (commit 6a)

New `BorstSorterBatchSchedulerTest` in
`src/test/java/com/bobrust/generator/sorter/` (mirrors
`BorstSorterAlphaTest`'s conventions — seeded, self-contained, renders with
the committed kernel):

1. **Permutation**: mixed sizes/colors/alphas (the 200-blob pattern from
   `mixedAlphaSortIsAPermutation`), scheduler output is a permutation.
2. **Z-order invariant, exhaustively**: 500 seeded random blobs with heavy
   overlap; after scheduling, O(n²)-verify every pair satisfying conditions
   (1)+(2) above keeps generation order. Run for 3 seeds.
3. **Render identity**: `BlobPruner.render(generationOrder)` vs
   `BlobPruner.render(scheduled)` — `assertArrayEquals` on pixels, for the
   heavy-overlap sets and for the same-color mixed-alpha cluster from the
   alpha test.
4. **Never worse, and strictly better where greedy is myopic**:
   `PaintTimeEstimator.toolChanges(scheduled) ≤ toolChanges(greedy)` on all
   sets above; plus one constructed case where greedy provably stitches — an
   interleaved 3-color grid with an overlap chain that blocks greedy's
   first-neighbor choice — assert strict `<` and assert the two-alpha-runs
   case from `alphaAwareCacheGroupsAlphaRuns` still yields score 5.
5. **Grouping**: >1000 blobs → all output indices of group k precede group
   k+1 (window concatenation preserved).

Plus the **offline plan validator** the proposal calls for (benchmark-tagged,
one run, no regeneration or repainting): new
`SchedulerClickValidationTest` in `com.bobrust.benchmark` — for each
`ComplexTestImages.corpus()` image, generate the default-config blob list
once (seeded ⇒ deterministic; reuse `generateUntilOverBudget`'s logic or a
trimmed copy at ~6k shapes to keep runtime sane), then for both strategies:
sort, `clicksOf`-equivalent click count, per-dimension change decomposition
(hoist the sweep's private `countChanges` into a small shared test helper),
and `BlobPruner.render` bit-equality against generation order. Print a
per-image table: `colorChanges greedy → scheduled (−x%)`, total clicks, sort
time.

### Commit 6b — flip the flag (separate, trivially revertable)

Flip `USE_BATCH_COLOR_SCHEDULER` to `true` only when ALL gates pass:

- Zero render mismatches across every validator image/seed.
- ≥10% pooled color-change reduction on the corpus (below that, the
  complexity isn't paying — leave the flag off and record the negative
  result).
- Group sort time within 2× of greedy (it runs inside `PaintPlan.extend` on
  the paint thread and the S2 estimate worker).

Then rerun the full sweep for the record — with the flag on, the harness's
prefix search automatically fits more shapes into 18k clicks:

```bash
JAVA_HOME=... ./gradlew benchmark --tests com.bobrust.benchmark.ClickBudgetSweepTest \
  -Dbench.out=docs/ab-s6.csv
```

Expected vs `docs/ab-s2.csv` era numbers: pooled ≈ +0.003 SSIM, mosaic
+0.004–0.008, portrait ≈ 0 (proposals §6-P3 pricing).

### ⚠ Default-output flag (6b only)

The rendered image is bit-identical by construction and verified by the
gates; what changes is the **painting order** (visible while the robot
paints) and the click count (plans get cheaper — the same slider value paints
faster). Existing in-flight `PaintPlan`s are unaffected (instructions are
frozen at extend time; the app holds no persisted plans across sessions).

---

## Cross-cutting notes

- **GeneratorConfig record shape** changes twice (S3 `sizeClickBias`, S4
  `qualityStop`). Each change mechanically rewrites the canonical
  constructor, `DEFAULT`, all `with*` methods, `serialize`/`parse`, and the
  two GeneratorConfigTest constructor-arity call sites. Do them in their own
  steps as planned; don't batch them with behavior changes.
- **Unknown-key forward compatibility**: `parse` ignores unknown keys, so old
  persisted settings strings survive every step, and new strings degrade
  gracefully on old builds.
- **Determinism**: every step preserves seeded reproducibility (S1/S2 change
  which seeded path runs; S3 β=0 and S4 qualityStop=0 are bit-identical
  no-ops; S6 is render-identical by invariant). Any A/B row that should be
  identical to a prior CSV row must be *identical*, which the plan uses as
  free verification wherever possible.
- **Docs**: after each step lands, append the measured outcome to
  `docs/SPEED-QUALITY-PROPOSALS.md` (§7 for negative results) so the study
  stays the single source of truth.
