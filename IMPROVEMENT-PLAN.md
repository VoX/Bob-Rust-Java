# Bob-Rust-Java — Improvement Plan (validated)

Compiled from a multi-agent validation of `PROPOSALS-V2.md` (proposals 7–18) and
`PROPOSALS-SPEED.md`, cross-checked against the two review docs
(`REVIEW-FORK-CHANGES.md`, `REVIEW-CORE-MODEL.md`). Every proposal below was
re-validated against the actual code; premises, effort, risk, and priority reflect
that pass, **not** the proposals' self-assessment. Where the validators overruled a
proposal's own claim, it's called out as a **correction**.

Legend: effort **S/M/L**; priority **HIGH / MED / LOW**; "long pole" = high value but
externally/temporally blocked.

---

## 0. The through-line the validators agree on

Three independent things showed up in every pass and shape the whole plan:

1. **Nothing is measurable until determinism is restored.** Generation uses
   `ThreadLocalRandom` (`Worker.java:50-52`), and every end-to-end test is a loose
   tolerance guard (1.05×–1.30×). No on-fork A/B number to date is trustworthy. So the
   **deterministic-seed + benchmark harness is the foundation, built first.**
2. **The broken 2-opt is a prerequisite, not a cleanup.** `BorstSorter.sort`
   unconditionally runs the 2-opt (`USE_TSP_OPTIMIZATION=true`), which (a) corrupts
   paint z-order (no precedence check — the likely cause of the "poor output" reported)
   and (b) is O(n²)×100, freezing the "Calculate Exact Time" button on the EDT. The
   estimator, pruning, and budget-selection all call `sort()`, so **flipping the flag to
   `false` must be step 1**, not a late step. (This is already in the bug-fix track.)
3. **Five quality proposals rewrite the same four `BorstCore` hot loops.** P7, P8b, P9's
   soft-edge rim, P10, and P11 all restructure `computeColor` / `drawLines` /
   `differencePartialThread{Classic,Combined}`. Doing them independently = five rounds of
   surgery on the same integer inner loops, each a chance to silently desync
   `computeColor` from the energy pass. **Treat them as one coordinated hot-loop rewrite**,
   guarded by the `BatchParallelEnergyTest` equivalence check.

---

## 1. Foundation (do first — mostly overlaps the bug-fix track)

- **P15 — Deterministic seed + benchmark harness. HIGH, effort M, build first.**
  Makes every other claim measurable; also folds the six compile-time `USE_*` flags into a
  runtime `GeneratorConfig` (which is what lets you runtime-disable the broken 2-opt and
  A/B any change). **Correction (validator):** the proposal overstates the *source* of
  nondeterminism — all RNG draws are already serial on the single generator thread
  (`randomize()` runs in a serial loop; the parallel `getEnergy` is pure), so a single
  seeded `RandomGenerator` on the `Worker` restores determinism. The elaborate
  per-state `mix(runSeed,stepIndex,stateIndex)` scheme is insurance for a future parallel
  mutation, not a requirement — so this is *cheaper and lower-risk* than written.
- **2-opt demotion (`USE_TSP_OPTIMIZATION=false`). HIGH, effort S.** Correctness + unblocks
  the speed features. Prerequisite for the estimator/pruning/selection. *(bug-fix track)*
- **Exact long-integer energy accumulator.** The `differencePartial` float score
  round-trips and can drift / NaN-poison near convergence (`REVIEW-CORE-MODEL §2`). Several
  proposals (P12 refit, P18 live metric) inherit this — do the exact accumulator early.
  *(bug-fix track)*
- **Calibration scale fix (Bug 4).** `ScreenshotAnalyzer` emits raw screenshot-pixel
  diameters as `SIZES` ignoring `scaleX` — must land before P9 persists a profile, or the
  profile bakes the bug in. *(bug-fix track)*

---

## 2. Highest-value quality levers

- **P7 — Per-shape alpha optimization. HIGH, effort M.** The biggest untapped quality
  lever and the most solidly code-grounded premise: the whole downstream pipeline already
  carries per-shape alpha (`Blob.alphaIndex`, the sorter, `TwoOptOptimizer`, and the
  painter's alpha-slider click at `BobRustPainter.java:132-136`); only the *generator* is
  locked to one global alpha (4 lock points: `Circle` has no alpha field, `Worker.getEnergy`
  uses `worker.alpha`, `Model.alpha`, `BorstData.update`). Low-alpha "glazing" blends colors
  beyond the fixed 64-palette — how human painters do skin/gradients. **Watch:** extend the
  sorter cache key to `(size,color,alpha,shape)` (shared with P11); thread `alphaIndex`
  through `State.getCopy`/`Circle.fromValues` (currently drops it → undo/determinism bug);
  floor `minAlphaIndex` so convergence doesn't balloon shape counts. **Correction:** its
  *painted-result* payoff is partly hostage to P9 — low alpha is exactly where the
  uncalibrated blend (`>>>8` darkening, sRGB-vs-linear) is least trustworthy, so the *sim*
  may improve 10–25% while the *sign* improves less until P9 lands.
- **P8a + P8b — Perceptual color (OKLab snap + channel-weighted energy). MED-HIGH, effort S.**
  Cheapest wins in the set, no external deps. 8a swaps the palette-snap metric in the
  static LUT initializer (near-free); 8b weights the energy channels (Rec.601-ish).
  **Required correction:** the snap distance in `getClosestColor` **must use the same
  weights as the energy**, or ranking and snapping disagree and you reintroduce exactly the
  discrete-suboptimality the design's "optimal-color-then-snap" avoids (`REVIEW-CORE-MODEL
  §2` calls that the strongest piece of the design). 8c (dithering) stays behind a flag /
  LOW — speculative at 3–100px circle scale.
- **P9 — Measured paint model → persisted `CalibrationProfile`. HIGH value, long pole,
  effort M–H.** The only proposal that fixes the *objective itself* rather than the search:
  today `ScreenshotAnalyzer` only prints suggestions the user hand-edits; there's no profile,
  no blend-space detection. It's the correctness multiplier for P7 (real alpha values) and
  P11 (real square mask). **Blocked by:** needs an in-game Rust session; inherits the Bug-4
  scale fix; the calibration pattern (`CalibrationPatternGenerator`, 6×6) and the only robot
  path that paints exact per-cell settings (`generateDebugDrawList`) aren't wired together,
  so the "paint this pattern in-game" round-trip doesn't close yet. Making `ALPHAS`/`SIZES`
  non-final also forces rebuilding the `NumberLookup` tables, `CircleCache` masks, and color
  LUT (all class-load-time today). **Plan:** land the analyzer/profile *plumbing* early
  (parallel to P7/P8 behind flags); expect the *engine cutover* last, when in-game data
  exists.
- **Coordinated hot-loop rewrite (P7 + P8b + P9-rim + P10 + P11).** Sequence these as one
  refactor of the four `BorstCore` functions, not five, and keep the
  `BatchParallelEnergyTest` equivalence guard green throughout.

---

## 3. Highest-value robustness

- **P13 — Closed-loop painting + journal/resume. HIGH, effort L.** Closes the #2
  foundational risk: the painter is open-loop, tool state is memory-only, and one missed
  palette click silently misprints the rest of the run. Verified premises: the false-retry
  bug (`clickPointScaledDrawColor` presses on every attempt, `BobRustPainter.java:196-218`);
  the literal `// TODO: previouslyUsed should start at drawnShapes` (`DrawDialog.java:216`);
  completion-as-exception (`:173`). Ship order: the 13a false-retry hotfix + 13d journal/
  exact-resume + 13b batch screenshot-diff verification first. **Depends on** the 2-opt fix
  (the journal encodes *sorted* order) and, for 13a's delta-*magnitude* gate, on P9 (the
  delta it checks is computed by the uncalibrated forward model — use the delta *sign* +
  tolerance until calibrated).

---

## 4. Highest-value speed (paint-time is the dominant real-world cost)

The paint-cost model in `PROPOSALS-SPEED.md` was verified **line-by-line accurate** against
`BobRustPainter` (per-canvas blob = `1000/cps + 2·t_cap`; `mouseMove` teleports so travel is
free; tool-change count = `getScore−4`). Paint-time figures are trustworthy; **match-%
figures are provisional** (un-benchmarked — fill in via the P15 harness).

- **Blob-count budget selection + dead-blob pruning. HIGH, effort M.** *The single biggest
  lever* — paint time is provably linear in blob count (`T ≈ N·60ms + const`), and N is the
  only multiplicative knob on the dominant term (cutting 4000→1500 = 2.6× beats any pacing
  change). Code-grounded: record marginal Δscore in `Model.addShape`, rasterize via the
  already-compacted `CircleCache`, hook at `convertToList → prune → sort` in
  `startDrawingAction`. **Required corrections:** (1) the per-blob "error bound" isn't
  rigorous — it ignores the substrate effect (dropping a blob shifts later blobs' blended
  colors), so **prune-then-re-render-and-verify the true score**, don't threshold-and-paint;
  (2) budget selection breaks the pure-prefix `previouslyUsed` resume assumption — ship it
  **with** the `previouslyUsed`-as-instruction-list refactor (same one P13d needs).
- **Paint-time estimator + live readout in `DrawDialog`. HIGH, effort M.** Replaces both the
  crude `OverlayTopPanel` fudge and the EDT-freezing "Calculate Exact Time" button with a
  debounced background `SwingWorker` showing "≈ 1m 12s · ≈ 84% match". Math verified;
  `updateTimeRemaining` already computes realized ms/shape — just persist `t_cap`. Depends on
  the 2-opt demotion for a responsive `sort()`.
- **Preset ladder (Blazing / Fast / Balanced / Max Quality). HIGH framework, provisional
  numbers.** Every knob maps to a real target (shape-count slider, `SettingsMaxShapes`,
  size-index floors, a masked 32-color LUT, cps, a new verification-cadence enum, a
  `GenerationConfig` for states/age/SA). Keep the existing slider as the fine budget control;
  add a "(Custom)" dirty state. Numbers are formula-driven placeholders until the harness runs.
- **Verification throttling + cps presets. MED-HIGH, effort S.** ~2.5–3×/blob at the Blazing
  end (skip the ~2·t_cap `getPixelColor` tax on most clicks). Risk is reliability, not
  accuracy; mitigate with sparse verification. *(Drop the "fix the retry-timer" sub-item — the
  validator confirmed `retryTime` is already re-anchored per iteration at
  `BobRustPainter.java:200`.)*
- **Generation effort scaling + early-stop (B1/B2). MED, effort M.** Cheap and safe;
  generation usually isn't the wall-clock bottleneck (see the separate `PERFORMANCE-PLAN.md`
  for the deep generation-speed profile).
- **Half-res drafts (B3). CONDITIONAL MED — else DROP.** Only with the large-brush size cap
  (cap half-res candidates to r ≤ 100/scale): the naive version inherits `MultiResModel`'s
  upscale bug (r=100 at half-res → r=200 → snaps back to the 100px cap → ¼ area, so large flat
  backgrounds can't be reproduced). Also needs the `ErrorMap.samplePosition` crash fix (Bug 3)
  for ÷2 dimensions.

---

## 5. Secondary / do-later (all validated KEEP, lower leverage or deeper deps)

- **P14 — Auto palette detection + setup validation. MED, effort M.** Kills the "my colors
  are wrong" support class rooted in a real silent-collision bug (`putIfAbsent` at
  `BobRustPalette.java:60`) + 6-bit-LUT aliasing. Self-contained (manual fallback). Watch: the
  `.with(GraphicsConfiguration)` machinery it wants to reuse is vestigial for HiDPI — validate,
  don't trust it.
- **P16 — Headless CLI + plan interchange format. MED, effort S-M.** Clean packaging over an
  AWT-light core; unlocks CI benchmarking (P15), remote generation, and murals (P17).
  **Correction:** the round-trip success criterion must compare to the `drawLines` composite,
  not the antialiased `ShapeRender` preview (they're different rasterizers).
- **P18 — Live quality metrics overlay. LOW-MED, effort S.** The live-RMSE half ships
  *independently* (surface `Model.getScore()`); the SSIM/ΔE00 half reuses P15's metrics
  package. Guard the drifting-score/NaN display.
- **P12 — Redundant-shape pruning + final color re-fit. MED-LOW, effort M.** Reusable
  bbox-replay infra + a strictly-non-negative refit gain. **Correction:** ε=0 ("quality-
  neutral") pruning yield is structurally *small* at the translucent default alpha (index 2 =
  100) — a shape is only fully occluded when later paint is opaque — so its headline yield
  leans on either the dead multi-res or on ε>0 (which isn't quality-neutral). Grows valuable
  *after* P7 adds opaque stamps. Reuse the `drawLines` replay, not `ShapeRender` buffers.
- **P10 — Edge-weighted objective. LOW-MED, effort L-M.** Modest, uncertain, image-dependent;
  internally consistent (per-pixel scalar weights keep the snap optimal). Only with P15's
  benchmark; also re-weight the `ErrorMap` sampling or objective and placement disagree.
- **P11 — Square brush in the generator. LOW, effort M (widest refactor).** Real but niche
  (sign painters mostly do photos/faces); hard-gated on P9 confirming the in-game square mask;
  amplifies preview divergence (`ShapeRender` fudges squares with `cd *= 1.25`). Do last among
  quality items.
- **P17 — Multi-sign murals. LOW, schedule last.** Best user-facing capability, fully
  code-grounded, but the deepest node in the dependency graph (needs P13d + P16, which need the
  2-opt/calibration fixes). Adds no engine improvement itself.

---

## 6. Recommended build order (dependency spine)

1. **Foundation:** determinism + benchmark harness (P15, simplified per the correction) ·
   `USE_TSP_OPTIMIZATION=false` · exact long-integer energy accumulator · Bug-4 calibration
   scale fix. *(Most of these are already in the bug-fix branch `fix/review-findings`.)*
2. **Cheap quality + the biggest speed lever, in parallel:** P8a+P8b (with the snap=energy
   consistency fix) · P7 per-shape alpha — begun as the coordinated `BorstCore` hot-loop
   rewrite. In parallel on the paint side: blob pruning + budget selection (with re-render
   verification + the `previouslyUsed` refactor) and the paint-time estimator + live readout.
3. **Package it:** the preset ladder + `DrawDialog`/`Settings` UX · verification throttling.
4. **Correct the objective:** P9 calibration plumbing early → engine cutover when in-game data
   exists (multiplies P7 and P11).
5. **Robot robustness:** P13 closed-loop + journal/resume (after the 2-opt fix).
6. **Then:** P10 · P12 (after P7) · P11 (after P9) · P14 · P16 · P18-perceptual · B3 (with the
   size cap).
7. **Last:** P17 murals.

**Single highest-leverage item overall:** the determinism + benchmark foundation — because it
is the only thing that turns every subsequent quality/speed change from "we think it helped"
into a measured decision, and because it's cheaper to build than the proposal claims. The
single highest-leverage *user-visible* change is the **blob-count budget + pruning** lever on
the speed side (paint time is linear in N) and **per-shape alpha** on the quality side.

> See `PERFORMANCE-PLAN.md` for the deep profile of the generation/preview phase, and
> `FIXES-APPLIED.md` (branch `fix/review-findings`) for the bug fixes this plan sequences
> around.
