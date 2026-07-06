# Fixes Applied — branch `fix/review-findings`

Fixes for the confirmed bugs in REVIEW-FORK-CHANGES.md and REVIEW-CORE-MODEL.md.
Every fix was verified against the code before changing it. Build: `./gradlew test`
(Java 17) — **BUILD SUCCESSFUL, 45 tests, 0 failures** (2 pre-existing headless skips
in `PaletteButtonConfigurationTest`).

## Fixes

### 1. Disabled 2-opt paint-order optimization (Review: Fork Bugs 1 & 2 — critical)
- **Bug:** `TwoOptOptimizer.optimize` (TwoOptOptimizer.java:103-106) reverses segments on
  palette+travel cost with no overlap-precedence check, wired inside `BorstSorter.sort`
  (BorstSorter.java:156-159). This breaks the sorter's z-order invariant, so the painted
  sign can diverge from the preview; it is also O(n²)×100 sweeps over the full blob list
  (UI freeze), and the travel it optimizes is free (`Robot.mouseMove` teleports).
- **Change:** `AppConstants.USE_TSP_OPTIMIZATION = false` with a comment explaining the
  z-order invariant break and the conditions for re-enabling (precedence-aware 2-opt).
  `TwoOptOptimizer` class left in place. Stale comment in `TwoOptOptimizerTest` test 7
  updated (its assertions hold either way).
- **Files:** `AppConstants.java`, `TwoOptOptimizerTest.java`.
- **Confidence: high.** Explicitly requested; verified no precedence check exists in the
  optimizer and that `get_intersections`/`find_best_fast_cache` enforce the invariant the
  reversal violates.

### 2. ErrorMap.samplePosition crash (Review: Fork Bug 3 — high)
- **Bug:** `cellWidth = ceil(w/32)` means for widths not divisible by the grid the last
  cells lie wholly outside the image (w=100 → 32 cells × 4px cover 128px). The uniform
  fallback for zero total error (blank target, e.g. clearing a sign) made every cell
  samplable, so `rnd.nextInt(min(cellWidth, imageWidth - pxStart))` got a bound ≤ 0 →
  `IllegalArgumentException`, silently killing the generator thread (no handler around
  `processStep`).
- **Change (ErrorMap.java constructor):** grid dimensions are recomputed as
  `ceil(image / cellSize)` so every cell covers ≥1 in-image pixel; the uniform fallback
  therefore only ever selects valid cells. `samplePosition` additionally guards the
  bounds with `Math.max(1, …)` and clamps the result, so even a degenerate table cannot
  throw.
- **Confidence: high.** Regression-tested: `ReviewFixesRegressionTest.errorMapSamplingSurvivesBlankNonDivisibleTarget`
  (10k samples on a blank 100×100 target) and `generationOnBlankTargetDoesNotCrash`.
  Existing `ErrorGuidedPlacementTest` grids (64×64/2×1, 128×128/32×32) are dimensionally
  unchanged, and all its tests still pass.

### 3. USE_PROGRESSIVE_RESOLUTION flag was lying (Review: Fork Bugs 5 & 6)
- **Bug:** the flag was `true` but nothing in `src/main` reads it; `BorstGenerator.start`
  constructs a plain `Model` (BorstGenerator.java:52) and `MultiResModel` is referenced
  only by tests. Additionally `MultiResModel.scaleCircle` (MultiResModel.java:107-108)
  snaps up-scaled radii (200/400) back to the 100px cache cap.
- **Change:** `USE_PROGRESSIVE_RESOLUTION = false` with a comment stating the feature is
  not wired in. The up-scale snap is **not** fixable locally — the engine can only
  rasterize the six cached SIZES, so a real fix requires restricting per-level candidate
  sizes or extending the circle cache; left a detailed TODO on `scaleCircle` instead.
- **Files:** `AppConstants.java`, `MultiResModel.java`.
- **Confidence: high** for the flag; the snap bug is documented, not fixed (deliberate —
  it is dead code and the fix is a design change, not a patch).

### 4. Restored deterministic, seedable generation (Review: cross-cutting #2 / core §5)
- **Bug:** the fork replaced upstream's seeded `new Random(0)` with
  `ThreadLocalRandom.current()` (Worker.java:50-52) and the SA loop's acceptance draws
  (HillClimbGenerator.java:112), making every run non-reproducible.
- **Change:** `Worker` holds one `new Random(RANDOM_SEED)` (seed 0, matching upstream);
  the SA acceptance draws use the same worker RNG via a new package-private
  `State.getWorker()`. Verified that **all** RNG consumers (`Circle.randomize`,
  `Circle.mutateShape`, SA acceptance, temperature probes) run on the serial generator
  thread — the parallel energy pass (`State::getEnergy`) is RNG-free — so this loses no
  parallelism and adds no contention. Per-worker seeding was unnecessary (there is one
  worker per model, one generator thread per run).
- **Files:** `Worker.java`, `HillClimbGenerator.java`, `State.java`.
- **Confidence: high.** Regression-tested: `ReviewFixesRegressionTest.generationIsDeterministic`
  asserts two runs produce identical shape lists, colors, and scores.
- **Note:** the seed is a compile-time constant, not yet a Settings entry — making it
  user-configurable is improvement-plan work.

### 5. Removed spatial batching, kept combined pass + LUT (Review: Fork P4)
- **Bug:** `getBestRandomState` replaced one parallel sweep over 1000 states with a
  1000-element sort plus 20 sequential fork-join barriers of 50 (HillClimbGenerator.java:19-29)
  — real barrier overhead for a speculative locality win, never benchmarked against
  `parallelStream()`.
- **Change:** reverted to a single `random_states.parallelStream().forEach(State::getEnergy)`.
  Kept the genuinely good parts: the combined color+energy pass (still gated by
  `USE_BATCH_PARALLEL`, still verified identical by `BatchParallelEnergyTest`) and the
  always-on color LUT in `BorstUtils`. Updated the flag's comment and corrected the
  combined pass's false claims ("precomputed alpha blend tables" — none exist; "~33%
  fewer memory reads" — both variants make the same four traversals per pixel).
- **Files:** `HillClimbGenerator.java`, `BorstCore.java`, `AppConstants.java`.
- **Confidence: high.** Equivalence tests all pass.

### 6. Calibration suggested SIZES ignored screenshot scale (Review: Fork Bug 4 — high)
- **Bug:** `printSuggestedSizes`/`printJavaSnippet` (ScreenshotAnalyzer.java:494-511,
  544-556) emitted raw screenshot-pixel `measuredDiameters` as suggested `SIZES`, which
  live in sign-pixel space — wrong by the scale factor for any non-1:1 screenshot, i.e.
  virtually every real one.
- **Change:** added `suggestedSize(row, col)` = `round(measuredDiameters / scaleX)`
  (guarding `scaleX <= 0`), used by both printers; the report now states the conversion
  and warns that measured alphas assume an unlit, gamma-neutral screenshot. Raw-measurement
  getters (used by tests) unchanged; `CalibrationRoundTripTest` passes (1:1 round trip
  unaffected, 2× test asserts on raw measurements).
- **File:** `ScreenshotAnalyzer.java`.
- **Confidence: high.**

### 7a. Exact long-integer energy accumulation (Review: core §2 drift/NaN, fix №4)
- **Bug:** the running squared-error total was reconstructed from the rounded `float`
  score each step (`total = (long)(pow(score*255,2)*denom)` at BorstCore.java:144, 215,
  411) — injecting up to ~total·2⁻²³ error per shape, and for near-perfect fits the
  reconstructed total could go negative after subtraction → `sqrt(negative)` = NaN, which
  silently poisons every subsequent hill-climb comparison.
- **Change:** the energy carry is now an exact `long` end-to-end:
  - `BorstCore.differenceFullTotal` / `differencePartialTotal` (exact long in/out);
    `scoreFromTotal` derives the float score only at the display/ranking boundary;
    `differenceFull` kept as a float wrapper.
  - `differencePartialThread{,Classic,Combined}` take `long baseTotal` instead of
    `float score`.
  - `Model` carries `private long totalError` (float `score` now display-only, derived);
    `Worker.init` takes the long total.
  - Exact totals are sums of per-pixel squared errors, so they are provably non-negative:
    NaN is now impossible in this path.
- **Files:** `BorstCore.java`, `Model.java`, `Worker.java`; call-site updates in
  `BatchParallelEnergyTest`, `ErrorGuidedPlacementTest`, `AdaptiveSizeSelectionTest`,
  `SimulatedAnnealingBenchmark`.
- **Confidence: high.** Regression-tested: `incrementalTotalMatchesFullRecomputeExactly`
  asserts the incremental total equals a full recompute **exactly** (long equality) after
  25 shapes; the full `BatchParallelEnergyTest` equivalence suite passes unchanged.

### 8. Other confirmed low-risk fixes
- **Test artifacts:** `AdaptiveSizeSelectionTest` and `ProgressiveResolutionTest` now
  write under `build/test-output/` (matching `ErrorGuidedPlacementTest`); the 35
  git-tracked, test-regenerated PNGs in `test-results/` were `git rm`'d and the directory
  gitignored. Verified: working tree stays clean after a full test run.
- **CircleCache logger misattribution:** `LogManager.getLogger(BobRustPainter.class)` →
  `CircleCache.class` (CircleCache.java:8).
- **SA doc/code mismatch:** the duplicated `maxAge * 3` in `getHillClimbSA` and
  `computeCoolingRate` extracted to `SA_ITERATIONS_PER_AGE`; the javadoc claiming
  "maxAge * 10" corrected.
- **New regression suite:** `ReviewFixesRegressionTest` (4 tests) pins the ErrorMap
  crash input, blank-target generation, determinism, and exact accumulation.

## Deferred (deliberately not fixed)

- **Alpha-blend divisor mismatch (`>>>8` vs `/255`)** — Review core §2 defect 1. The
  forward blend (`drawLines`) divides by 256 while `computeColor`'s inverse assumes /255,
  a ~0.4% systematic darkening. Which divisor matches the game's actual compositing
  (likely linear-space in Unity, matching *neither*) cannot be determined from the code —
  the four historical ALPHAS tables in `BorstUtils` are evidence the model never matched
  the game. Changing it would silently alter every generated sign. Left unchanged with an
  explanatory NOTE on `drawLines`; needs measured calibration data (the calibration tool
  is the path). Also leaves the related `pd = 65280/alpha` truncation and clamp-then-snap
  (both negligible alone).
- **`clickPointScaledDrawColor` same-color false-retry** — the review confirms the
  weakness (same-color-over-same-color is a no-change fixpoint → up to 3 spurious extra
  stamps) but there is no contained fix: the verification oracle is a single pixel and
  the expected post-stamp color is unknowable without a forward model of the game's
  rendering. This is part of the open-loop-painter problem (improvement plan: closed-loop
  painting).
- **MultiResModel up-scale snap** — documented TODO instead of a fix (see #3): a correct
  fix restricts per-level candidate sizes, which is feature work on dead code.
- **`PAINT_THRESHOLD = 10`** in ScreenshotAnalyzer — fine for the current ALPHAS
  (minimum 23); deriving it from `ALPHAS[0]` is safe but subtly changes detection
  thresholds in a tool with pixel-exact tests; not a current bug, so left alone.
- **GradientMap max-normalization caveat** (near-uniform images bias toward small
  circles) — a design caveat, not a confirmed bug; improvement plan.
- **2-opt precedence-aware redesign, seed-in-Settings, runtime flags for the
  proposals** — improvement plan.

## Build & test result

```
./gradlew test   (JAVA_HOME=java-17-amazon-corretto; Gradle 7.2 can't run on the default JDK 21)
BUILD SUCCESSFUL
45 tests, 0 failures, 2 skipped (pre-existing headless skips in PaletteButtonConfigurationTest)
```

No pre-existing test asserted the buggy behaviors; the only test-side changes were the
new `long`-total signatures, output directories, and one stale comment.
