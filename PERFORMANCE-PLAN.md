# Generation-Phase Performance Plan

> **STATUS (2026-07-06): items #1–#6 are IMPLEMENTED** on `fix/review-findings`
> as Phase G (#2 had landed earlier with the bug-fix pass). Re-measured with
> the F1 harness (`./gradlew benchmark`, 128×128 corpus, 300/800 shapes per
> image): old production config vs the new `GeneratorConfig.DEFAULT`
> (`sa=false;proxy=true;states=500;age=100`) = **3.6× / 4.9× end-to-end** at
> hard-image quality parity. Two deviations from the letter of this plan, both
> harness-driven: **age stays 100** (50 measured −1.9% SSIM / +1.9% ΔE00
> aggregate — this plan's "score unchanged" held only for RMSE on the single
> synthetic target) and **proxy strides are pinned at {2,4,4}** (idx 3/4/5;
> coarser broke ΔE00 parity). #7 parallel refine is now the top remaining
> lever: post-proxy, the sequential refine phase dominates step time.

Scope: the plan-calculation phase only — `Model.processStep()` and everything it
calls, i.e. the hill-climb loop that turns the target image into circles before
any painting happens. Painting speed is out of scope.

All numbers below are **measured**, not estimated, on this box (4-core Graviton
ARM, Corretto 21, `-Djava.awt.headless=true`), on a 512×256 synthetic
photo-detail target (wooden-sign size) with the production feature flags
(SA + error map + gradient map + batch parallel, alpha=190). Harnesses:
`src/test/java/com/bobrust/generator/PerfProbe.java` (checked into this
worktree) and a standalone copy in `/tmp/borstbench/` (the project's Gradle 7.2
cannot run on the installed JDK 21, so the probes were compiled directly with
javac against the generator sources plus a stubbed `AppConstants`).

Caveat: user machines are typically 8–16 thread x86. The parallel candidate
phase shrinks with cores; the sequential refine phase does not. The ranking
below holds directionally everywhere; on many-core machines the refine-phase
items matter relatively more.

---

## (a) Where the time goes — measured breakdown

Per `processStep()` (production config, steps 0–200, averaged):

| Phase | 512×256 | share | 1024×512 | share |
|---|---|---|---|---|
| parallel energy eval of 1000 candidates | 7.92 ms | **70.6%** | 6.93 ms | **61.2%** |
| refine (SA, 310 sequential evals) | 2.56 ms | **22.9%** | 3.72 ms | **32.9%** |
| randomize 1000 candidates (error map sample + gradient size pick) | 0.40 ms | 3.5% | 0.26 ms | 2.3% |
| errorMap incremental update + alias rebuild | 0.15 ms | 1.4% | 0.08 ms | 0.7% |
| commit: beforeImage full copy | 0.04 ms | 0.4% | 0.12 ms | 1.1% |
| commit: drawLines ×2 (current + dead `context`) | 0.06 ms | 0.5% | 0.09 ms | 0.8% |
| commit: differencePartial | 0.07 ms | 0.6% | 0.08 ms | 0.7% |
| commit: computeColor (redundant re-computation) | 0.01 ms | 0.1% | 0.03 ms | 0.2% |
| **total** | **11.2 ms/step (~89 shapes/s)** | | **11.3 ms/step** | |

Long-run behaviour: per-step cost is **flat** over 2000 steps (5.6–6.9 ms/step
in the classic-refine config) — late fine-detail steps are *not* cheaper,
because candidates keep sampling all sizes. A 5 000-shape plan costs
5000 × per-step cost; there is no natural tail speedup.

The energy kernel cost is proportional to circle area (single-thread, per eval,
`differencePartialThreadCombined`):

| size idx | diameter | µs/eval |
|---|---|---|
| 0 | 3 | 0.28 |
| 1 | 6 | 0.63 |
| 2 | 12 | 1.73 |
| 3 | 25 | 6.57 |
| 4 | 50 | 29.1 |
| 5 | 100 | **101.5** |

**The two largest sizes account for ~93% of uniform candidate-eval work.**
This is the single most important structural fact: making large candidates
cheap to *rank* is worth more than any micro-optimization of the kernel.

Explicitly *not* bottlenecks (measured or verified):
- **Preview / ShapeRender**: callback fires every 100 shapes
  (`Settings.EditorCallbackInterval` default 100, `Settings.java:27`), and
  `ShapeRender.getImage` renders incrementally from cached buffers
  (`ShapeRender.java:120-155`), on the EDT, not the generator thread. ~1
  repaint/second at current speed. Ignore.
- **ErrorMap per-step update + alias rebuild** (`ErrorMap.java:83-137,143-201`):
  0.15 ms/step = 1.4%. The alias rebuild is O(1024). Already effectively
  incremental; no further work needed.
- **BorstData.update**: appends only new blobs per callback
  (`BorstGenerator.java:216-234`). Negligible.

## (b) Ranked bottlenecks, with evidence

1. **Exact full-area energy evaluation of all 1000 candidates** —
   `HillClimbGenerator.getBestRandomState` → `Worker.getEnergy`
   (`Worker.java:60-64`) → `BorstCore.differencePartialThreadCombined`
   (`BorstCore.java:282-415`). 61–71% of step time. Two passes over every
   pixel of every candidate circle; a d=100 candidate costs 360× a d=3 one,
   and full precision is wasted on the 999 candidates that only need to be
   *ranked*, not committed.

2. **P4 "spatial batching" is a measured slowdown** —
   `HillClimbGenerator.java:19-29`. Replaces one `parallelStream()` sweep with
   a 1000-element sort plus 20 sequential fork-join barriers of 50.
   Measured A/B on the eval phase: **batch 7.47 ms/step vs plain 5.65 ms/step**
   — plain is 1.32× faster. The review (REVIEW-FORK-CHANGES.md, P4) suspected
   this; now confirmed with numbers. With 50-item batches on mixed circle
   sizes, each barrier waits on whichever thread drew the d=100 circles.

3. **SA refinement costs more than classic hill climbing and delivers nothing**
   — `HillClimbGenerator.getHillClimbSA` (`HillClimbGenerator.java:85-126`)
   runs a fixed 300 moves + ~10 temperature probes = 310 sequential exact
   evals/step, vs classic's measured ~147–163 (`age=100`, resets on
   improvement). A/B at 300 shapes ×3 reps: SA 2.39 ms/step, score 0.29245;
   classic 1.91 ms/step, score **0.29185 (equal-or-better)**. SA is strictly
   worse on this workload: +25% refine cost, no quality gain, plus
   nondeterministic `ThreadLocalRandom` acceptance draws.

4. **Candidate count is higher than needed given error-guided placement** —
   `Model.java:99` (`max_random_states = 1000`). With the P2 error map biasing
   80% of placements at high-error cells, halving candidates barely moves
   quality (see experiment table below): 500 candidates lose 0.00005 score
   (noise) and save ~30% of step time.

5. **Refine phase is 100% sequential** — the `times` loop
   (`HillClimbGenerator.java:188-197`) is sequential and `times=1`
   (`Model.java:101`), so during 2–4 ms/step of refinement, N−1 cores idle.
   Matters more as core count grows (Amdahl); on a 16-thread machine refine
   becomes the dominant phase after fix #1.

6. Minor (each ≤3%): gradient-map size selection allocates a `float[6]` and
   computes 6 `Math.exp` per candidate (`GradientMap.selectSizeIndex`,
   `GradientMap.java:126-149` — measured 0.30 vs 0.11 ms/step randomize with
   map on/off); `ErrorMap.samplePosition` allocates an `int[2]` per call
   (`ErrorMap.java:231`); `Model.addShape` re-runs `computeColor`
   (`Model.java:66`) although the winning candidate's eval already derived the
   color and threw it away; one of the two `drawLines` writes to
   `Model.context` (`Model.java:73`), which is **write-only — dead work**;
   `Worker.counter` is a contended `AtomicInteger` hit once per eval
   (`Worker.java:15,61`) for a debug-only number; `beforeImage.draw(current)`
   copies the full image every step (`Model.java:63`, 2 MB/step at 1024×512).

7. **The "combined" kernel's claimed win does not exist** — measured
   single-thread over 12 000 mixed-size evals: combined 286 ms vs classic
   260 ms (combined ~10% *slower* here). Consistent with the review: both are
   two passes with the same memory traffic. Not a bottleneck per se, but the
   "~33% fewer memory reads / blend tables" comments (`BorstCore.java:273-281`)
   are wrong and should go.

## Validation experiments (300 shapes, 512×256, 3 reps averaged, plain parallelStream in all)

| config | ms/step | score (lower=better) | refine evals/step |
|---|---|---|---|
| A prod-like (1000 cand, SA) | 5.20 | 0.292454 | 310 |
| B 1000 cand, classic age=100 | 5.05 | **0.291850** | 147 |
| C 500 cand, classic 100 | 3.52 | 0.291901 | 157 |
| D 250 cand, classic 100 | 2.51 | 0.292313 | 163 |
| E 1000 cand, classic, **proxy eval** | 2.77 | 0.291939 | 155 |
| F 500 cand, classic age=50, proxy | **1.95** | 0.292486 | 76 |
| G 250 cand, classic age=50, proxy | **1.43** | 0.292610 | 79 |

All scores are within 0.26% of each other — quality parity across the board
(spread between reps is the same order). "Proxy eval" = the subsampled ranking
kernel described in item 1 below. Note config A here excludes the batching
slowdown (production also pays that: 11.2 ms/step in the phase breakdown);
against true production, config F is a **~4–6×** wall-clock win and config G
**~6–8×**, at unchanged output quality on this test image.

---

## (c) The optimization plan

### 1. Subsampled proxy evaluation for candidate ranking — THE headline change
- **Mechanism**: the 1000-candidate pass only needs to *rank* candidates; the
  winner is refined and committed with exact math anyway. Evaluate large
  candidates on a strided subset of their pixels (stride 4 in x and y for size
  idx ≥ 4, stride 2 for idx 3, exact for small sizes), scale the sampled
  error delta by stride², pick the best, then reset its score and let
  refinement/commit use the exact kernel unchanged. A d=100 candidate drops
  from 101 µs to ~7 µs. Prototype in `/tmp/borstbench/.../PerfProbe2.java`
  (`energyProxy`).
- **Measured speedup**: eval phase ~1.8× at 1000 candidates (B→E: 5.05→2.77
  ms/step overall); the enabler for #4's candidate cuts. This is also exactly
  the win P6/multi-res was chasing, without a second model to keep in sync.
- **Effort**: M (new kernel variant + a `rankEnergy` path in
  `Worker`/`State`; ~100 lines; keep `BatchParallelEnergyTest` untouched since
  exact kernels don't change).
- **Quality risk**: low, and validated — E matches B's score at 1000
  candidates. Refinement re-evaluates exactly, so a mis-ranked near-tie costs
  nothing visible. Add a rank-fidelity test (top-1 exact ∈ top-K proxy).
- **Touch-points**: `BorstCore` (new `differencePartialProxy`),
  `Worker.getEnergy`/`State.getEnergy` (rank vs exact path),
  `HillClimbGenerator.getBestRandomState`.

### 2. Revert P4 spatial batching to plain `parallelStream()`
- **Mechanism**: delete `HillClimbGenerator.java:19-29` (sort + 20 barriers),
  keep the `random_states.parallelStream().forEach(State::getEnergy)` branch.
- **Measured speedup**: 1.32× on the eval phase (7.47→5.65 ms/step), ~1.2×
  overall.
- **Effort**: S (deletion). **Quality risk**: none (identical math).
- **Touch-points**: `HillClimbGenerator.getBestRandomState`,
  `AppConstants.USE_BATCH_PARALLEL`. The review already recommends this; if
  the parallel bug-fix branch has done it, this item is complete.

### 3. Drop simulated annealing (default classic hill climbing)
- **Mechanism**: `USE_SIMULATED_ANNEALING = false` (or delete
  `getHillClimbSA`). Classic does ~150 evals/step vs SA's fixed 310.
- **Measured speedup**: refine phase 2.39→1.91 ms/step; ~5–10% overall on 4
  cores, more on many-core machines where refine is the bigger share.
- **Quality risk**: none measured — classic scored *better* (0.29185 vs
  0.29245). Also removes SA's unseeded acceptance draws, which helps the
  separate determinism-restoration work.
- **Effort**: S. **Touch-points**: `AppConstants.java:29`,
  `HillClimbGenerator.getHillClimb`.

### 4. Reduce candidates 1000 → 500, and expose it as a Setting
- **Mechanism**: `Model.max_random_states` (`Model.java:99`). Error-guided
  placement (P2) already concentrates candidates where they matter; 1000
  uniform-era samples are redundant.
- **Measured**: 500 candidates: −30% step time, score −0.00005 (noise);
  250: −50%, score −0.0005 (~0.16%, still parity).
- **Effort**: S. **Quality risk**: low-moderate on untested image classes —
  make it a runtime `Settings` entry (also addresses the review's
  "everything is a compile-time constant" complaint), default 500.
- **Touch-points**: `Model.java`, `Settings.java`.

### 5. Cut classic hill-climb age 100 → 50
- **Mechanism**: `Model.age` (`Model.java:100`); classic stops after `age`
  consecutive non-improving mutations, so expected refine evals halve
  (measured 147→76).
- **Measured**: F vs C-with-proxy ≈ 10–15% overall; score unchanged within
  noise. **Effort**: S. **Risk**: low; keep 100 available via the same Setting.

### 6. Minor cleanups (bundle, ~5–10% combined, all S effort, no risk)
- Delete the dead `context` image and its `drawLines` (`Model.java:43,73`).
- Return the winner's color from the eval/refine path so `addShape` skips its
  `computeColor` re-run (`Model.java:66`), or at least reuse pass-1 sums.
- Replace `GradientMap.selectSizeIndex`'s per-call `float[6]` + 6×`Math.exp`
  with a precomputed cumulative-weight LUT over quantized gradient values
  (e.g. 64 buckets × 6 sizes); measured 0.19 ms/step recoverable.
- `ErrorMap.samplePosition`: return a packed int (`y<<16|x`) instead of
  allocating `int[2]` ×1000/step (`ErrorMap.java:231`).
- `Worker.counter`: `LongAdder` or delete — it's debug-only
  (`Worker.java:15`).
- Fix or delete the false "precomputed alpha blend tables / 33% fewer reads"
  comments (`BorstCore.java:273-281`); optionally re-unify on the classic
  kernel, which measured ~10% faster single-thread here.

### 7. Optional / deferred
- **Parallel refine chains** (M): run K classic hill climbs from the top-K
  proxy-ranked candidates via `parallelStream`, take the best. Wall-time of
  one chain, better quality, uses cores that currently idle during refine.
  Most valuable on 8+ core machines after #1 shrinks the eval phase. No
  ordering hazards — chains are independent; commit stays sequential.
- **One-pass exact kernel via sufficient statistics** (M): pass 1 can
  accumulate Σu and Σu² per channel where u = 256·tt − pa·bb, making
  after-error O(1) once the color is chosen — a true single-pass kernel
  (~1.4–1.8× on remaining exact evals). Caveat: the `>>>8` truncation in the
  blend means results differ by ±1 LSB from the current kernel, so
  `BatchParallelEnergyTest`'s byte-identical assertion would need a tolerance.
  Only worth it if refine remains hot after #1–#5.
- **Java Vector API SIMD kernel** (L): 2–4× on the pixel loops is realistic,
  but needs `--add-modules jdk.incubator.vector`, a Gradle upgrade (the
  current wrapper is 7.2 and can't even run on JDK 17+), and an ARM/x86
  validation matrix. Not justified while algorithmic wins are on the table.

### Rejected, with reasons
- **Wiring in MultiResModel (P6)**: rejected. Candidate eval cost is
  proportional to the circle's own pixel area, which is resolution-independent
  (SIZES are fixed sign-space diameters) — a coarse level does *not* make
  steps cheaper, it makes a d=100 circle *represent* d=400 structure. But the
  game brush caps at d=100 in sign space, which is precisely Bug 5
  (`MultiResModel.java:107-108`): coarse shapes above d=25 cannot be upscaled
  and would corrupt output. Restricting coarse levels to d≤25 gives the same
  "large structure cheap" effect that proxy eval (#1) already provides on a
  single model, without cross-resolution drift, image pyramids, or shape
  propagation. Recommend deleting `MultiResModel` + `USE_PROGRESSIVE_RESOLUTION`.
- **Skipping/batching the ErrorMap alias rebuild**: measured 1.4% of step
  time including the incremental cell recompute. Not worth touching; keep P2
  (it is what makes #4 safe) after its Bug 3 crash fix lands.
- **Preview/ShapeRender optimization**: not on the generator thread, fires
  ~1×/s, incremental. Nothing to win.
- **Fixed thread pool instead of common ForkJoinPool**: the plain
  parallelStream sweep already saturates cores once batching is gone; a
  dedicated pool adds config surface for ~0. Revisit only if the app ever
  runs generation concurrently with other parallel work.
- **Early-stopping the whole generation on score convergence**: changes
  user-visible behaviour (shape-count slider semantics) for savings that only
  materialize on near-converged runs; per-step cost is flat, so there's no
  hidden tail waste. Out of scope; could be a UI hint instead ("score improved
  <0.1% over last 500 shapes").

## (d) Implementation order

1. **#2 revert batching** + **#3 SA off** — two one-line flag changes already
   endorsed by the review; coordinate with the bug-fix branch (P4 revert and
   determinism work overlap here; SA-off removes one nondeterminism source).
2. **#6 minor bundle** — mechanical, independently testable.
3. **#1 proxy ranking kernel** — the highest-leverage single change
   (eval phase ~1.8× alone at unchanged candidates, and it unlocks #4/#5 at
   quality parity). Ship with a rank-fidelity unit test.
4. **#4 candidates→500 and #5 age→50 as Settings** — after #1, re-run the
   quality check on 2–3 *real* photos (not just the synthetic target) with a
   seeded RNG from the determinism work, asserting score parity within 0.5%.
5. Re-measure; only then consider #7 items if generation is still the UX
   bottleneck.

**Single highest-leverage change**: the subsampled proxy evaluation (#1) —
it attacks the measured 61–71% phase at its structural root (large circles
cost 360× small ones to rank), was validated end-to-end at quality parity,
and it subsumes the goal of the never-wired multi-res proposal with a tenth
of the machinery.

**Combined expectation (honest)**: config F (revert batching + classic +
proxy + 500 candidates + age 50) measured **1.95 ms/step vs production's
11.2 ms/step on identical hardware/image ≈ 5.7×**; allowing for harness
variance and image dependence, **4–6× end-to-end generation speedup at
quality parity** is the defensible claim, rising to ~8× with 250 candidates
if real-image validation holds. On many-core machines absolute times drop
further but the multiplier shrinks toward ~3–4× unless #7's parallel refine
is added.

## Compatibility notes with in-flight bug fixes

- **Determinism restoration**: everything here is compatible with a seeded
  per-run RNG. RNG consumers on the hot path are the sequential randomize
  loop and sequential refine mutations; the parallel eval phase consumes no
  randomness. Dropping SA (#3) removes the only parallel-unfriendly RNG use.
- **P4 batching revert**: identical to #2 — dedupe with that branch.
- **ErrorMap Bug 3 (crash) fix**: keep — this plan increases reliance on the
  error map (#4 assumes it), so the out-of-range-cell fix must land first.
- **2-opt (P5) disable**: unrelated to generation speed, but its EDT freeze
  happens in the same user flow ("calculate exact time" / draw start);
  disabling it removes a perceived "generation is slow" symptom that is
  actually the sorter.

## Appendix: harnesses

- `src/test/java/com/bobrust/generator/PerfProbe.java` — phase-timing probe
  (JUnit; note the repo's Gradle 7.2 cannot run on the installed JDK 21 —
  needs a Gradle ≥8 upgrade or a JDK ≤16 to run in-tree).
- `/tmp/borstbench/` — standalone javac-compiled copy of the generator
  package + stub `AppConstants` + `PerfProbe`/`PerfProbe2` mains used to
  produce every number in this document. `PerfProbe2.energyProxy` is the
  reference implementation for plan item #1.
