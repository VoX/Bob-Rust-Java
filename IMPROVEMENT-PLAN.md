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

**NOT done — the remaining work** (all of §3 below): the benchmark harness +
perceptual metrics; every generation-speed item from PERFORMANCE-PLAN.md that
isn't the batching revert (`USE_SIMULATED_ANNEALING` is still `true`,
`max_random_states` still 1000, `age` still 100, the proxy kernel doesn't
exist, the dead `Model.context` drawLines write is still at `Model.java:82`);
and all quality / paint-speed / robustness / packaging features.

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

- **G1 — Drop simulated annealing; default classic hill climb. NEXT, S,
  deps: none.** `USE_SIMULATED_ANNEALING = false` (→ `GeneratorConfig`). SA
  measured *strictly worse*: 310 sequential evals/step vs classic's ~150, at
  equal-or-worse score (0.29245 vs 0.29185).
  *Success: refine evals/step halve; F1 score parity (≤0.5%) on the corpus.*
- **G2 — Subsampled proxy evaluation for candidate ranking. NEXT, M,
  deps: none (G1 first only for clean measurement). THE headline generation
  change.** Rank the 1000 candidates on a strided pixel subset (stride 4 for
  size idx ≥ 4, stride 2 for idx 3, exact below), scale sampled delta by
  stride², then refine/commit the winner with the exact kernel unchanged. A
  d=100 candidate drops 101 µs → ~7 µs; eval phase ~1.8× alone, and it's the
  enabler for G3. Reference implementation:
  `/tmp/borstbench/.../PerfProbe2.energyProxy`. New `differencePartialProxy`
  in `BorstCore` + a rank-vs-exact path in `Worker`/`State`; exact kernels and
  `BatchParallelEnergyTest` untouched. This kernel joins the coordinated
  hot-loop family (§1.3): any later energy-metric change (Q1) must update it
  in the same commit.
  *Success: rank-fidelity test (exact top-1 ∈ proxy top-K) + F1 parity; step
  time ≥1.5× faster at 1000 candidates.*
- **G3 — Candidates 1000→500 and age 100→50, as Settings. NEXT, S, deps: G2,
  F1.** Error-guided placement already concentrates candidates; measured
  −30% step time at −0.00005 score (noise). Keep 1000/100 reachable via the
  Setting; validate on *real* photos, not just the synthetic target.
  *Success: F1 parity within 0.5% on 2–3 real photos, seeded.*
- **G4 — Minor hot-path bundle. NEXT, S, deps: none.** Delete the dead
  `Model.context` image + its `drawLines` (`Model.java:15,51,82` —
  write-only); return the winner's color from eval so `Model.addShape` skips
  the redundant `computeColor`; `GradientMap.selectSizeIndex` per-call
  `float[6]` + 6×`Math.exp` → precomputed LUT; `ErrorMap.samplePosition`
  packed-int return instead of `int[2]`/call; `Worker.counter`
  AtomicInteger → LongAdder or delete; fix the false "precomputed alpha blend
  tables / 33% fewer reads" comments in `BorstCore`.
  *Success: `BatchParallelEnergyTest` + regression suite green; ~5–10%
  measured on F1.*
- **G5 — Parallel refine chains (top-K proxy candidates → K parallel classic
  climbs, take best). LATER, M, deps: G2.** After G2 shrinks the eval phase,
  the sequential refine loop is the Amdahl limit — this matters *more on
  users' 8–16-thread x86 machines* than on this 4-core dev box. Chains are
  independent; commit stays sequential. Only if F1 shows refine dominant
  post-G2. (Same trigger for the one-pass sufficient-statistics kernel and
  the Vector-API kernel — deferred, see PERFORMANCE-PLAN.md §c7.)

*Phase-1 combined expectation (measured, honest): 4–6× end-to-end generation
speedup at quality parity; ~8× if 250 candidates validates on real photos.*

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

**Single highest-leverage remaining item:** F1 — it's now small (determinism
✅ did the hard half) and it converts every Phase-1–3 change from "measured
once on one box" into a regression-guarded decision.

**Highest-leverage code changes:** G2 proxy ranking (measured ~4–6× generation
with G1/G3) on the compute side; S1 blob budget/pruning (paint time linear in
N) on the real-world-minutes side; Q2 per-shape alpha (with Q1 first) on the
quality side.

> Numbers and harness details: `PERFORMANCE-PLAN.md`. What the fix pass
> changed and why: `FIXES-APPLIED.md`. Validation provenance of P7–P18:
> the previous revision of this file (git history).
