# Bob-Rust-Java — Improvement Plan (FINAL — post-bugfix, perf-integrated)

Compiled from the multi-agent validation of `PROPOSALS-V2.md` (P7–P18) and
`PROPOSALS-SPEED.md`, cross-checked against `REVIEW-FORK-CHANGES.md` /
`REVIEW-CORE-MODEL.md`, **updated after the bug-fix pass on this branch**
(`fix/review-findings`, see `FIXES-APPLIED.md` — build green, 45 tests) and
**integrating the measured generation profile** in `PERFORMANCE-PLAN.md`.
This is the execution document: work the roadmap in §3 in order.

Legend: effort **S/M/L**; status **✅ DONE / NEXT / LATER**; "long pole" = high
value but externally blocked.

---

## Status as of this update (2026-07-06)

**Already DONE by the bug-fix pass** (verified in code on this branch):

- ✅ **2-opt demoted** — `USE_TSP_OPTIMIZATION = false` (z-order corruption + EDT
  freeze gone). Unblocks the estimator, pruning, budget selection, and journal.
- ✅ **Determinism restored** — `Worker` holds one seeded `new Random(0)`
  (`Worker.java:17`); the SA acceptance draws use the same worker RNG. Two runs
  are byte-identical (`ReviewFixesRegressionTest.generationIsDeterministic`).
  **PARTIAL:** the seed is a compile-time constant, not a Setting; and the
  *benchmark harness + SSIM/ΔE00 metrics half of P15 is NOT done* — only the
  determinism half is.
- ✅ **Exact long-integer energy accumulation** — the running total is an exact
  `long` end-to-end; NaN/drift near convergence is now impossible
  (`incrementalTotalMatchesFullRecomputeExactly`). P12's refit and P18's live
  metric no longer inherit that risk.
- ✅ **P4 spatial batching reverted** — plain
  `random_states.parallelStream().forEach(State::getEnergy)`
  (`HillClimbGenerator.java:23`). This *is* PERFORMANCE-PLAN item #2 (measured
  1.32× on the eval phase) — already banked, don't re-do it.
- ✅ **ErrorMap.samplePosition crash fixed** — grid recomputed as
  `ceil(image/cellSize)`, bounds guarded; blank/non-divisible targets survive
  10k samples. Prerequisite for leaning harder on error-guided placement (G3
  below) and for B3's ÷2 dimensions.
- ✅ **Calibration screenshot-scale fix (Bug 4)** — suggested `SIZES` are now
  `round(measuredDiameters / scaleX)`; a persisted P9 profile can no longer
  bake in the scale bug.
- ✅ **`USE_PROGRESSIVE_RESOLUTION = false`** — the flag was lying (nothing
  reads it); `MultiResModel` up-scale snap documented as a TODO on dead code.
- ✅ **Hygiene** — test PNGs untracked + redirected to `build/test-output/`,
  CircleCache logger fixed, `SA_ITERATIONS_PER_AGE` extracted, 4-test
  regression suite pinning the above.

**Still deferred, deliberately** (from FIXES-APPLIED.md, unchanged here):
blend-divisor mismatch (`>>>8` vs `/255` — needs measured calibration data,
§3 Phase 4), `clickPointScaledDrawColor` false-retry (part of the open-loop
problem, §3 Phase 5), MultiResModel snap (dead code — recommend deletion, §3
Phase 6), `PAINT_THRESHOLD`, GradientMap max-normalization caveat.

**Also DONE since** (this branch):

- ✅ **F1 benchmark harness + perceptual metrics** (`com.bobrust.benchmark.
  BenchmarkHarness`, `com.bobrust.util.metrics`, runtime `GeneratorConfig`) —
  commit cc792da.
- ✅ **Phase G generation speed (G1–G4)** — measured on the F1 corpus, 4-core
  Graviton: old production config vs new `GeneratorConfig.DEFAULT`
  (`sa=false;proxy=true;states=500;age=100`) is **3.6× at 300 shapes and
  4.9× at 800 shapes** end-to-end, at quality parity on the hard images
  (nature improves on RMSE/SSIM/ΔE00; corpus ΔE00 sum +0.02% at 300 shapes).
  The G4 bundle (dead `Model.context` removed, winner color reused at commit,
  LongAdder counter, GradientMap weight LUT, packed ErrorMap sampling) is
  **bit-exact** — identical pixel hashes for the old config before/after.
  `age` stays 100: 50 measured 1.78× faster but −1.9% SSIM / +1.9% ΔE00
  aggregate, outside the seed-noise band (a speed preset can set it later).

**NOT done — the remaining work** (§3 below): all quality / paint-speed /
robustness / packaging features (Q1+, S1+, C1, R1, …) and the deferred G5
parallel-refine item.

---

## 1. The through-line (updated for the new reality)

1. **Determinism is done; measurability now hangs only on the harness.** The
   expensive half of P15 (seeded RNG) landed in the fix pass and was cheaper
   than proposed (single serial generator thread — no per-state seed mixing
   needed). What's left is the *cheap* half: a repeatable benchmark runner +
   SSIM/ΔE00 metrics + seed-as-Setting. Until it exists, every quality claim
   below stays provisional — build it first (F1).
2. **Generation speed has a measured 4–6× sitting on the table.** The profile
   in PERFORMANCE-PLAN.md is measured, not estimated: candidate ranking is
   61–71% of step time, large circles cost 360× small ones to *rank*, and the
   subsampled proxy kernel + SA-off + candidate cut reached quality parity
   (score spread 0.26%) at ~5.7× on production config. These are the cheapest
   validated wins in the whole plan — they go immediately after F1.
3. **The `BorstCore` hot loops get rewritten once, not five times.** P7, P8b,
   P9's soft-edge rim, P10, and P11 all touch `computeColor` / `drawLines` /
   `differencePartialThread{Classic,Combined}` — and now **also the proxy
   ranking kernel (G2)**, which must stay metric-consistent with the exact
   kernels (same channel weights, same alpha handling) or ranking silently
   diverges from the objective. Treat them as one coordinated rewrite guarded
   by `BatchParallelEnergyTest` + a proxy rank-fidelity test.

---

## 2. Reconciled dependencies (what the fixes unblocked)

- **Honest A/B for every change** — determinism ✅ means F1's harness is pure
  tooling; no engine surgery left in the foundation.
- **Estimator / pruning / budget selection / journal** — all called `sort()`;
  2-opt ✅ makes `sort()` responsive and the sorted order trustworthy.
- **Pruning + refit color paths** — exact energy ✅ removes the NaN poisoning
  risk they inherited; a re-rendered "true score after pruning" is now exact.
- **P9 profile persistence** — Bug 4 ✅ means a persisted profile is in
  sign-pixel space; plumbing can proceed without baking in the scale bug.
- **B3 half-res drafts** — ErrorMap ✅ removes the ÷2-dimension crash; still
  conditional on the large-brush size cap (see Phase 6).
- **SA-off no longer carries determinism weight** — PERFORMANCE-PLAN.md wrote
  that dropping SA "removes nondeterministic acceptance draws"; the fix pass
  already seeded those draws. G1 is now purely a speed/quality-parity change.
- **Still-standing validator corrections** (unchanged): per-shape alpha's
  *painted* payoff is partly hostage to P9 calibration; the perceptual snap
  metric must equal the energy metric; blob pruning must re-render-and-verify
  (substrate effect) and ship with the `previouslyUsed` refactor; blend
  divisor and the open-loop painter stay deferred pending calibration /
  closed-loop work.

---

## 3. Roadmap — remaining work, in build order

### Phase 0 — Finish the foundation

- **F1 — Benchmark harness + perceptual metrics (the remaining half of P15).
  NEXT, effort S–M, deps: none (determinism ✅).**
  A repeatable runner over a small fixed image corpus (2–3 real photos + the
  synthetic target) reporting score, SSIM, ΔE00, shapes/s, wall time per
  config; promote the RNG seed and the relevant `USE_*` flags into a runtime
  `GeneratorConfig`/`Settings` so configs can be A/B'd without recompiling.
  Reuse `PerfProbe` (already in-tree) as the timing skeleton. Note the Gradle
  7.2 / JDK constraint (run via Java 17 as the tests do, or javac-standalone
  like `/tmp/borstbench`).
  *Success: one command produces a per-config metrics table; same seed + same
  config ⇒ byte-identical output twice.*

### Phase 1 — Generation speed (measured wins from PERFORMANCE-PLAN.md)

Order follows the perf plan's own sequencing; each lands with an F1 parity run.

- **G1 — Drop simulated annealing; default classic hill climb. ✅ DONE, S.**
  `GeneratorConfig` default `sa=false` (SA path reachable via `sa=true`).
  F1-measured at 1000 candidates: 1.2× overall, aggregate quality
  equal-or-better (notably better on nature/photo_detail; marginally worse
  on the near-converged trivial images).
- **G2 — Subsampled proxy evaluation for candidate ranking. ✅ DONE, M. THE
  headline generation change.** `BorstCore.differencePartialProxy` ranks
  candidates on a strided pixel subset (`PROXY_STRIDE = {1,1,1,2,4,4}`),
  scales the sampled delta by stride², then the winner's memoized score is
  invalidated so refine/commit re-evaluate with the exact kernels — the
  committed geometry, color and running total stay exact (ProxyRankingTest).
  F1-measured: 2.3× at 1000 candidates at aggregate parity. Stride tuning:
  {2,4,6} +7% speed but ΔE00 +1.4%; {2,6,8} broke parity and was net
  *slower*. This kernel joins the coordinated hot-loop family (§1.3): any
  later energy-metric change (Q1) must update it in the same commit.
- **G3 — Candidates 1000→500 (GeneratorConfig). ✅ DONE, S.** F1-measured
  at proxy-on: 1.13× at sub-1% aggregate deltas, within the measured
  seed-to-seed noise band (SSIM ±1.7%, RMSE ±0.4% between seeds); 250 drifted
  ~2.3% on every metric for only 7% more speed — rejected. **`age` stays
  100**: 50 measured 1.78× faster but −1.9% SSIM / +1.9% ΔE00, outside
  noise — post-G2 the refine phase dominates, so `age` now costs quality;
  leave it to a future speed preset (S3) and to G5.
- **G4 — Minor hot-path bundle. ✅ DONE, S.** Dead `Model.context` +
  `drawLines` deleted; winner's exact-eval color threaded through
  `State.color` so `Model.addShape` skips the `computeColor` re-run;
  `GradientMap.selectSizeIndex` per-call `float[6]` + 6×`Math.exp` →
  lazily-built per-cell cumulative LUT (bit-identical selection);
  `ErrorMap.samplePositionPacked` returns a packed int; `Worker.counter` →
  LongAdder. **Whole bundle verified bit-exact**: identical F1 pixel hashes
  for the pre-G config before/after the change.
- **G5 — Parallel refine chains (top-K proxy candidates → K parallel classic
  climbs, take best). LATER, M, deps: G2.** After G2 shrinks the eval phase,
  the sequential refine loop is the Amdahl limit — this matters *more on
  users' 8–16-thread x86 machines* than on this 4-core dev box. Chains are
  independent; commit stays sequential. Only if F1 shows refine dominant
  post-G2. (Same trigger for the one-pass sufficient-statistics kernel and
  the Vector-API kernel — deferred, see PERFORMANCE-PLAN.md §c7.)

*Phase-1 outcome (measured on the F1 corpus, 128×128 images, 4-core Graviton):
**3.6× at 300 shapes, 4.9× at 800 shapes** end-to-end vs the old production
config, at hard-image quality parity. Larger targets clip less, so real
sign-size images should sit at or above the plan's 4–6× band. 250 candidates
and age=50 did NOT validate at parity — both stay config-reachable.*

### Phase 2 — Quality (the coordinated `BorstCore` rewrite begins)

- **Q1 — P8a+P8b perceptual color: OKLab palette snap + channel-weighted
  energy. NEXT, S, deps: F1 (to prove it), G2 (proxy kernel must get the same
  weights in the same commit).** Cheapest quality win, no external deps.
  **Required correction stands:** the snap distance in `getClosestColor` must
  use the *same weights as the energy* or ranking and snapping disagree,
  reintroducing the discrete-suboptimality the optimal-color-then-snap design
  avoids. 8c (dithering) stays flagged/LOW.
  *Success: F1 shows ΔE00 improvement at equal shape count; snap and energy
  provably share one metric (unit test).*
- **Q2 — P7 per-shape alpha optimization. NEXT, M, deps: Q1 ordering only;
  payoff multiplier is Phase 4.** Biggest untapped quality lever; the whole
  downstream pipeline already carries per-shape alpha — only the generator is
  locked (4 lock points: `Circle` alpha field, `Worker.getEnergy`,
  `Model.alpha`, `BorstData.update`). Watch items stand: extend the sorter
  cache key to `(size,color,alpha,shape)` (shared with P11); thread
  `alphaIndex` through `State.getCopy`/`Circle.fromValues` (currently drops
  it → undo/determinism bug); floor `minAlphaIndex`. **Correction stands:**
  sim may improve 10–25% while the *painted sign* improves less until P9
  calibrates the low-alpha blend.
  *Success: F1 sim score/SSIM improve ≥10% at equal shape count,
  determinism test still green.*

### Phase 3 — Paint-side speed + UX (paint time is the dominant real-world cost)

- **S1 — Blob-count budget selection + dead-blob pruning. NEXT, M, deps:
  2-opt ✅, exact energy ✅; ship WITH the `previouslyUsed` refactor.** The
  single biggest real-world lever: paint time is linear in N
  (`T ≈ N·60ms + const`); 4000→1500 = 2.6×. Corrections stand: (1)
  prune-then-**re-render-and-verify** the true score (substrate effect — the
  per-blob bound isn't rigorous); (2) budget selection breaks the pure-prefix
  `previouslyUsed` resume assumption — do the instruction-list refactor here
  (same one R1/P13d needs; build once).
  *Success: on the corpus, ≥30% blob reduction at ≤1% re-rendered score loss.*
- **S2 — Paint-time estimator + live readout in `DrawDialog`. NEXT, M, deps:
  2-opt ✅.** Replaces the `OverlayTopPanel` fudge and the (formerly
  EDT-freezing) "Calculate Exact Time" button with a debounced background
  `SwingWorker`: "≈ 1m 12s · ≈ 84% match". Cost model verified line-by-line
  against `BobRustPainter`; persist `t_cap`.
  *Success: estimate within ±10% of a real paint run.*
- **S3 — Preset ladder (Blazing/Fast/Balanced/Max Quality) + verification
  throttling + cps presets. LATER, S–M, deps: S1, S2, G3 (presets set the new
  Settings).** Every knob maps to a real target; keep the slider as fine
  budget control + "(Custom)" dirty state. Verification throttling ≈
  2.5–3×/blob at the Blazing end (skip the ~2·t_cap `getPixelColor` tax);
  mitigate reliability with sparse verification. (Retry-timer sub-item stays
  dropped — `retryTime` already re-anchors per iteration.) Preset numbers
  come from F1 runs, not formulas.
  *Success: presets ship with measured (time, match%) labels from F1.*

### Phase 4 — Correct the objective (long pole — start plumbing early)

- **C1 — P9 measured paint model → persisted `CalibrationProfile`. NEXT
  (plumbing) / LATER (engine cutover), M–H, blocked by: an in-game Rust
  session.** The only item that fixes the objective itself; correctness
  multiplier for Q2 (real alpha) and P11 (real square mask), and the *only*
  path to resolving the deferred blend-divisor question (`>>>8` vs `/255` vs
  the game's likely linear-space compositing). Bug 4 ✅ so the profile is safe
  to persist. Remaining gaps stand: `CalibrationPatternGenerator` (6×6) and
  `generateDebugDrawList` aren't wired together (the paint-in-game round trip
  doesn't close); making `ALPHAS`/`SIZES` non-final forces rebuilding
  `NumberLookup`, `CircleCache`, and the color LUT (class-load-time today).
  Land the analyzer/profile plumbing behind flags alongside Phases 1–3;
  cut the engine over when in-game data exists.
  *Success: a profile round-trips paint→screenshot→analyze within tolerance
  on a real sign, and the engine can run from it behind a flag.*

### Phase 5 — Robot robustness

- **R1 — P13 closed-loop painting + journal/resume. LATER, L, deps: 2-opt ✅,
  S1's `previouslyUsed` refactor; 13a's delta-magnitude gate wants C1.**
  Ship order inside: 13a false-retry hotfix + 13d journal/exact-resume + 13b
  batch screenshot-diff verification first. Until calibrated, gate on delta
  *sign* + tolerance, not magnitude. This also finally subsumes the deferred
  `clickPointScaledDrawColor` false-retry (single-pixel oracle needs the
  forward model).
  *Success: kill the painter mid-run; resume completes with zero repainted or
  skipped blobs on a real sign.*

### Phase 6 — Secondary (validated KEEP, lower leverage or deeper deps)

All LATER; corrections from the validation pass unchanged:

- **P14 auto palette detection + setup validation** (M) — real
  silent-collision bug (`putIfAbsent`, `BobRustPalette.java:60`) + 6-bit-LUT
  aliasing; self-contained; don't trust the vestigial
  `.with(GraphicsConfiguration)` HiDPI machinery.
- **P16 headless CLI + plan format** (S–M) — unlocks CI benchmarking for F1
  and murals; round-trip must compare against the `drawLines` composite, not
  the antialiased `ShapeRender` preview.
- **P18 live quality overlay** (S) — live-RMSE half ships independently
  (`Model.getScore()` — NaN guard now moot thanks to exact energy ✅);
  SSIM/ΔE00 half reuses F1's metrics package.
- **P12 redundant-shape pruning + final color re-fit** (M) — refit is
  strictly-non-negative and now NaN-safe ✅; ε=0 pruning yield stays
  structurally small until Q2 adds opaque stamps — schedule after Q2. Reuse
  the `drawLines` replay, not `ShapeRender` buffers.
- **P10 edge-weighted objective** (L–M) — only with F1 evidence; re-weight
  `ErrorMap` sampling in the same change or objective and placement disagree.
- **P11 square brush in generator** (M, widest refactor) — hard-gated on C1
  confirming the in-game square mask; last among quality items.
- **B3 half-res drafts** — CONDITIONAL: only with the large-brush size cap
  (cap half-res candidates to r ≤ 100/scale); ErrorMap crash prerequisite ✅.
  Note G2's proxy eval already delivers most of what B3 chased — re-justify
  against F1 numbers before building.
- **Cleanup: delete `MultiResModel` + `USE_PROGRESSIVE_RESOLUTION`** (S) —
  per PERFORMANCE-PLAN.md's rejection analysis: dead code, structurally
  wrong (candidate cost is resolution-independent), superseded by G2.

### Phase 7 — Last

- **P17 multi-sign murals** (L) — best user-facing capability, deepest
  dependency node (needs R1/13d + P16). Adds no engine improvement itself.

---

## 4. Priorities at a glance

**Landed:** F1 (harness + metrics + runtime config) and Phase G (G1–G4,
measured 3.6–4.9× at quality parity, defaults tuned and pinned by tests).

**Highest-leverage remaining code changes:** S1 blob budget/pruning (paint
time linear in N) on the real-world-minutes side; Q2 per-shape alpha (with Q1
first) on the quality side; G5 parallel refine chains on the compute side —
post-G2 the sequential refine phase is now the dominant generation cost
(halving candidates barely moved wall time on refine-heavy images).

> Numbers and harness details: `PERFORMANCE-PLAN.md`. What the fix pass
> changed and why: `FIXES-APPLIED.md`. Validation provenance of P7–P18:
> the previous revision of this file (git history).
