# BobRust "Pixel Mode" — Exact-Hex Quantize + Square-Tile Drawing Mode

**Design + implementation plan.** Target repo: `/home/ec2-user/projects/bobrust-fix` (Java 17+, Gradle, branch `fix/review-findings`).

**Scope — code actually read for this design:**
`robot/BobRustPainter.java`, `robot/ButtonConfiguration.java`, `robot/BobRustPalette.java`, `robot/BobRustPaletteGenerator.java`, `robot/error/PaintingInterrupted.java`, `calibration/CalibrationPatternGenerator.java`, `calibration/ScreenshotAnalyzer.java`, `util/data/RustSigns.java`, `util/data/AppConstants.java`, `util/Sign.java`, `util/ImageUtil.java`, `util/PaintTimeEstimator.java`, `generator/BorstUtils.java`, `generator/CircleCache.java`, `generator/sorter/{Blob,PaintPlan}.java`, `gui/ApplicationWindow.java`, `gui/dialog/{DrawDialog,ScreenDrawDialog,SettingsDialog,RegionSelectionDialog}.java`, `settings/Settings.java`, `settings/data/PaintPreset.java`, and the prior study `src/test/java/com/bobrust/generator/ExactColorStudyTest.java`. Click-budget numbers below come from a quantize+tile simulation I ran against the repo's own test corpus (`src/test/resources/test-images{,-complex}/`); the simulation script is `tiling_sim.py` next to this document.

---

## TL;DR

- **New mode = resize → quantize to N exact colors → per-color greedy square-tiling → paint opaque with hex-entered colors.** It is a parallel pipeline to the existing simulated-annealing generator (`BorstGenerator`), sharing the calibration, pacing, and canvas-mapping infrastructure of `BobRustPainter` but with its own planner and executor.
- **Quantizer recommendation: Wu's weighted-variance quantizer for palette selection, refined by ~10 Lloyd (k-means) iterations in Oklab, with pixel assignment by nearest-in-Oklab.** Deterministic (preview == paint), area-weighted, measurably better than median-cut on smooth gradients, and the perceptual space fixes median-cut's hue drift in shadows. Dithering **off by default** — measured, it roughly *doubles* the click count on smooth images (portrait: 4,691 → 9,027 stamps at N=32) because error diffusion shatters the same-color regions the tiler depends on.
- **The load-bearing physical fact: the size-1 brush does *not* paint 1 sign pixel.** The repo's own measured brush data (`CircleCache.CIRCLE_CACHE_LENGTH` = `{3, 6, 12, 25, 50, 100}` sign-pixel diameters for in-game sizes {1, 2, 4, 8, 16, 32}; `BobRustPalette.getSizeButton` clicks the slider at fraction `SIZES[i]/100`) means the minimum footprint is ≈ 100/32 = **3.125 sign texels**. The paintable "virtual grid" on an XL frame is therefore ≈ **164×164 = 26,896 cells**, not 512×512 = 262,144 pixels. Brush size *s* covers an *s*×*s* block of virtual cells, *s* = 1..32 — a clean unit system for the tiler.
- **The owner's ~26,000-click figure is confirmed as the *worst-case ceiling*, but for a corrected reason.** Per-texel stamping would be 262k clicks and is physically impossible anyway; one min-size stamp per *virtual cell* is 26,896 clicks — the true ceiling, coincidentally ≈ his number. Greedy square tiling brings **typical images to 2k–19k total input actions at N=32** (portrait 5.3k ≈ 3 min, photo 13k ≈ 7 min, dense-text worst case 18.3k ≈ 10 min at 30 cps). "~15 minutes max for XL" holds.
- **Per-color square covering uses a painter's-algorithm relaxation:** colors are painted in area-descending order, and a stamp may overhang cells belonging to *later* colors (they get overwritten) but never *earlier* ones. Measured: −40% stamps on portrait, −24% on glyphs vs strict per-mask tiling, and it guarantees every cell ends with exactly its assigned color, with full canvas coverage (the base material color never shows through).
- **Only one genuinely new calibration point is needed: the hex-entry field** (`ButtonConfiguration.hexInput`), added to the existing 7-step arrow-marker flow in `ApplicationWindow.setupButtonRegions`. `brush_square`, `size_1`/`size_32`, `opacity_0`/`opacity_1`, `focus`, `colorPreview`, `clearCanvas` all already exist and are reused as-is. One *measurement* task is new: verifying the square brush's texel footprint (side-vs-size function) with a small extension of the existing `CalibrationPatternGenerator`/`ScreenshotAnalyzer` tooling.
- **Hex entry is 1 click + 8 keystrokes per color (Ctrl+A, 6 hex digits or Ctrl+V paste, Enter), verified against `colorPreview`** using the exact disc-capture verification already in `BobRustPainter.clickColor`. At N=32 that is ~290 input actions total — noise next to the stamps. Hex-entry failure is *never* soldiered through (a missed color corrupts an entire color pass): retry once, then abort with a new `PaintingInterrupted.InterruptType`.

---

## Design

### 0. Where the mode sits in the existing architecture

The current flow (all verified in code):

1. `ApplicationWindow` owns the calibrated `ButtonConfiguration` (persisted as `button_config.json`, `ApplicationWindow:38–40, 397–418`) and the canvas/image region selection.
2. `ScreenDrawDialog.openDialog` snapshots `canvasRect`/`imageRect` and opens `DrawDialog` (`ScreenDrawDialog:51–67`).
3. `DrawDialog.startGeneration` scales the source image onto the sign's pixel grid via `ImageUtil.getScaledInstance(drawImage, canvasRect, imageRect, sign.getWidth(), sign.getHeight(), bgColor, scaling)` and runs `BorstGenerator` (`DrawDialog:639–679`).
4. "Select Color Palette And Draw" screenshots the screen, `BobRustPalette.initWith` locates the 64 swatches, and `BobRustPainter.startDrawing` executes the sorted `BlobList` through `java.awt.Robot` with pacing (`clickInterval` → `autoDelay`), per-click verification, autosave, and mouse-displacement abort (`BobRustPainter:46–189`).

Pixel mode replaces steps 3–4's *contents* but keeps their *shape*: same region selection, same sign grid, same `ButtonConfiguration`, same pacing/verify/interrupt machinery, a parallel planner (quantizer + tiler instead of `BorstGenerator`) and a parallel executor (`HexColorPainter` instead of the `BlobList` loop). It does **not** reuse `Blob`/`BlobList`: `Blob`'s constructor force-snaps color to the 64-palette and size to the 6-entry `SIZES` vocabulary (`Blob.java:24–30`), both of which are wrong for this mode (exact hex colors; 32 brush sizes).

### 1. Color quantization

**Requirement:** reduce the (already resized, see §2) image to N exact sRGB colors, user-selectable, default 32, "best colors to preserve the image", deterministic so the preview is exactly what gets painted.

**Algorithm survey (real options, honestly weighted):**

| Algorithm | Quality | Speed @ ~27k px | Deterministic | Notes |
|---|---|---|---|---|
| Popularity | poor | instant | yes | Drops low-population but salient colors. Reject. |
| Median-cut (Heckbert '82) | fair | instant | yes | Splits boxes at median of longest axis; wastes entries on dense smooth regions, banding on gradients. Fine as a fallback, not the recommendation. |
| Octree (Gervautz–Purgathofer) | fair | instant | yes | Streaming/memory win is irrelevant at 27k px. No quality edge over median-cut. Reject. |
| **Wu's weighted-variance (Wu '91)** | **good** | instant (33³ moment histogram) | **yes** | Greedy orthogonal bipartition minimizing weighted SSE. Consistently beats median-cut; the standard "best classical" choice. |
| k-means / Lloyd | best (local MSE optimum) | ms-scale at 27k px, N≤64 | yes *with fixed init* | Needs a good init or it lands in poor local optima; k-means++ uses RNG (breaks determinism unless seeded). |
| NeuQuant (SOM) | good on photos | slower | seed-dependent | Under-represents small salient regions; no advantage over Wu+k-means here. Reject. |

**Recommendation: Wu init → Lloyd refinement in Oklab.**

- Run **Wu's algorithm in RGB** over the resized cell grid to get N seed centroids. It is deterministic, fast, and already near-optimal in SSE.
- Convert image + centroids to **Oklab** and run **~10 fixed Lloyd iterations** (assignment = nearest centroid by squared Euclidean in Oklab; update = per-cluster mean in Oklab; empty-cluster rule: reseed deterministically on the highest-error pixel). Squared Euclidean in Oklab is a well-behaved perceptual ΔE proxy, which fixes the classic RGB failure (over-allocating colors to bright regions, hue drift in dark regions). CIEDE2000 (the repo has `util/metrics/Ciede2000.java` for *evaluation*) is deliberately **not** the clustering metric — it has no closed-form centroid and breaks Lloyd's convergence; Oklab-Euclidean is the standard engineering compromise.
- Final palette = cluster means converted back to sRGB, rounded to 8-bit — these are the exact 6-digit hex codes typed into the game. Assignment map (`cellColorIndex[cellY][cellX]`) is the tiler's input.
- Fixed iteration count + deterministic init + deterministic tie-breaking (lowest index wins) ⇒ same input → same palette → same plan → preview equals paint. This mirrors the repo's existing determinism discipline (`GeneratorConfig` seeds).

**Perceptual distance:** all quantizer distances in Oklab as above. The evaluation/report side (preview "quality" readout, tests) uses the existing `ImageMetrics`/`Ciede2000` so numbers are comparable with the SA benchmarks (`docs/speed-quality-results.csv` convention).

**Note on the repo's channel-weighted RGB metric:** `BorstUtils.PERCEPTUAL_WEIGHT_*` (2:4:3) exists to keep the SA energy kernel and palette snap metric-consistent (`BorstUtils.java:11–34`). That invariant is about *that* pipeline's snap-vs-energy coupling; pixel mode has no palette snap and no incremental energy kernel, so it is free to use the better space (Oklab) without violating Q1.

**Dithering — analyzed, default OFF.** Floyd–Steinberg (or ordered/Bayer) trades banding for high-frequency color noise. In this mode every same-color region is exactly what the tiler compresses into few large stamps; error diffusion shatters those regions into near-per-cell fragments. Measured on the repo corpus (XL, N=32, cell grid 164×164, FS dither):

| image | stamps flat | stamps FS-dithered | blowup |
|---|---|---|---|
| portrait | 4,691 | 9,027 | +92% |
| photo_detail | 12,553 | 12,906 | +3% (already noisy) |
| glyphs | 17,802 | 17,621 | ±0 (already shattered) |

So dithering costs the most exactly where it helps the most (smooth gradients) and does nothing on busy images. Decision: expose a **dither checkbox (default off)**; the live click/time estimate (§5) recomputes on toggle so the user sees the price before committing. If banding at N=32 bothers users in practice, the cheaper first lever is raising N (N=64 portrait is +2.5k stamps, less than dithering costs and *removes* error rather than masking it). An ordered-Bayer option is a possible follow-up: bounded, structured fragmentation (~2×2 blocks) instead of FS's unbounded scatter — noted as future work, not in scope.

**Alpha in the source** is composited before quantization by the existing scaler: `ImageUtil.getScaledInstance` fills the target with the chosen background color and then `drawImage`s the source over it (SrcOver), so semi-transparent source pixels arrive pre-blended (`ImageUtil.java:94–99`). The quantizer therefore only ever sees opaque RGB. (Design choice inherited: "transparent" regions become the user's background color, which then participates in quantization like any other region.)

### 2. Resize-to-frame

**Mapping** reuses the existing, already-correct geometry: the user's `imageRect` placement inside `canvasRect` (from `RegionSelectionDialog` / `ScreenDrawDialog.updateCanvasRect`) is projected onto the sign grid by `ImageUtil.getScaledInstance` (`ImageUtil.java:82–103`), with regions of the sign not covered by the image filled with the chosen background color (`Settings.getSettingsBackgroundCalculated`, which defaults to the sign material average, e.g. `CANVAS_AVERAGE = 0xb3aba0`, `AppConstants.java:102`). **Crop-vs-letterbox is therefore already the user's interactive choice** — they size/position `imageRect` themselves, overflow is cropped, uncovered sign is "letterboxed" in the background color. Pixel mode keeps those semantics unchanged (open question 2 below on the default fill color).

**Target resolution — the critical correction:** the mode does *not* resize to the sign's texel grid (512×512 for XL). The physically paintable grid has pitch = the size-1 square-brush footprint, **p ≈ 3.125 texels** (derivation in §3). So the resize target is the **virtual cell grid** `ceil(W/p) × ceil(H/p)` — 164×164 for XL. Each cell is painted uniformly by exactly one stamp, so quantizing/planning at cell resolution is not a quality loss, it *is* the attainable resolution. (The SA mode's smallest blob is the same 3-texel brush — `SIZES[0]=3` — so pixel mode loses nothing relative to the existing tool; this is the game's floor, not ours.)

**Filter choice: area-average (box) for the down-scale.** Sources are typically ≥1200px going to ~164 cells (factor ~7); single-pass `Graphics2D` bilinear/bicubic (what `getScaledInstance` does today via `RenderingHints`) sub-samples at that ratio and aliases, and Lanczos adds ringing overshoot — which manifests post-quantization as spurious halo clusters that eat palette entries and add stamp fragments. Area-average is the correct kernel when each destination cell will be rendered as a uniform block: the cell's value is the mean of the source area it covers. Implementation: a small dedicated `areaAveragedScale` (accumulate source pixels per destination cell; ~30 lines) in the new pipeline, reusing `getScaledInstance`'s rect-mapping arithmetic for placement, rather than adding a fourth `ScalingType` enum value that the SA path would also see. `SettingsScaling` (Nearest/Bilinear/Bicubic, `Settings.java:69–72`) remains SA-only.

**Quantize before or after resize: after.** (a) The quantizer must optimize the palette for the pixels that will actually be painted — 27k cells, not 1.4M source pixels; (b) resizing *after* quantization would re-blend the palette into new non-palette colors (interpolating filters) or alias (nearest); (c) it's ~50× cheaper. Order is fixed: **place → area-average to cell grid → quantize → tile**.

**Base canvas material:** signs start at a non-white material color (per-sign averages in `AppConstants:102–105`; textured, lighting-dependent in game). Two interactions:

1. *Full-cover default:* the tiling in §3 covers **every** cell (union of the N color masks = the whole grid), so the base material never shows through and its color is irrelevant to correctness. This is the "exactness" guarantee and the default.
2. *Optional "skip base-colored cells" optimization (default off):* cells whose quantized color is within a ΔE00 tolerance of the sign's average material color could be left unpainted. Rejected as a default because the material is textured and lighting-shifted (the repo itself warns about exactly this in `ScreenshotAnalyzer.printReport`: "in-game sign lighting will skew center brightness"), so "close to average" on file ≠ "matches on screen". Exposed as an advanced toggle for users painting on freshly-placed signs who want the click discount.
3. *Clear canvas first (default on):* one click on the already-calibrated `clearCanvas` before painting gives a deterministic substrate and removes stale art in the sub-cell slivers that rounding can leave at region boundaries.

### 3. Square-tiling / the click plan

**Brush-size model (grounded in the repo's measured data).** `BobRustPalette.getSizeButton(index)` clicks the size slider at `size_1.x + (size_32.x − size_1.x) · SIZES[index]/100` (`BobRustPalette.java:130–139`), and `SIZES = CircleCache.CIRCLE_CACHE_LENGTH = {3, 6, 12, 25, 50, 100}` — *measured painted diameters in sign texels* (`CircleCache.java:20–41`, refined by the `CalibrationPatternGenerator`/`ScreenshotAnalyzer` flow). Those six fractions {0.03, 0.06, 0.12, 0.25, 0.50, 1.00} correspond to in-game sizes {~1, ~2, ~4, 8, 16, 32} on the 1..32 slider — i.e. the measured footprint is **diameter(s) ≈ (100/32)·s = 3.125·s texels**, linear through the origin. Consequences:

- Minimum footprint (size 1) ≈ 3.125 texels ⇒ **virtual grid pitch p = 3.125**; "does size_1 cover exactly 1 canvas pixel?" — **No** (this kills naive per-texel exactness and must be stated to the owner).
- In-game size *s* covers an **s×s block of virtual cells** (side = 3.125·s texels = s cells). The tiler works entirely in cell units with square sides 1..32, and the slider click for side-k is at fraction `k/32` between the calibrated `size_1` and `size_32` endpoints — a new `getPixelSizeButton(int sideCells)` beside the existing `getSizeButton`.
- These numbers were measured with the *circle* brush. The square brush's side function must be verified (same pattern-paint-screenshot-measure loop, §"calibration" in the implementation plan). The whole design is **pitch-parameterized**: if measurement says the square brush side is `a·s + b`, only two persisted constants change; if it turns out size 1 truly paints 1 texel, the grid becomes 512×512 and everything below still holds (with the worst-case click numbers in the last table row).

**Per-color covering algorithm.** Input: the cell grid of color indices; palette of N colors. Output: an ordered list of `(colorIndex, [stamps])` where each stamp is `(cellX, cellY, side)` with side ∈ 1..32.

1. **Order colors by cell-count descending** (largest coverage first).
2. For color at rank k, define:
   - mask `M_k` = cells assigned color k (must end up color k);
   - allowed region `A_k` = cells whose color rank ≥ k (own cells + cells of colors painted **later**).
3. **Greedy cover of `M_k` with squares inside `A_k`:** compute the classic largest-square DP over `A_k` (`dp[i][j] = A[i][j] ? 1 + min(dp[i+1][j], dp[i][j+1], dp[i+1][j+1]) : 0`, O(cells)); scan row-major; at each not-yet-covered cell of `M_k`, emit a stamp anchored there with side `min(dp[i][j], 32)` and mark its square covered. Stamps of the same color may overlap each other freely (repainting the same opaque color is a no-op), so this is a *covering*, not a partition — strictly fewer stamps than exact tiling.
4. Within a color, **group stamps by side, descending** — the size slider is then clicked once per distinct side per color (measured: 200–500 size clicks total per image, see table). Stamp order within a size group is irrelevant for cost: `Robot.mouseMove` teleports, so mouse travel is free (`PaintTimeEstimator` doc block states and relies on exactly this).

**Why the painter's-algorithm relaxation (`A_k` ⊋ `M_k`) is both safe and worth it:**

- *Safety (exactness invariant):* a cell of rank r is only ever inside stamps of colors with rank ≤ r; all of those are painted before (or are) color r; color r's covering includes the cell. Hence the **last** stamp touching any cell is its own color ⇒ final canvas = quantized image exactly, and every cell is painted ⇒ full coverage over the base material.
- *Win (measured, XL, N=32):* portrait 7,885 → 4,691 stamps (−40%); glyphs 23,557 → 17,802 (−24%). Large background colors get huge stamps unbroken by enclosed details.

Greedy top-left-anchored largest-square is not optimal covering (optimal square-cover is NP-hard); it is O(cells) per color, deterministic, and the measured counts below already land well inside budget. Optimizations (anchor sliding, rectangle stamps via drag — the game brush is click-stamp so rectangles aren't available anyway) are not needed.

**Pixel→screen mapping.** Reuses `BobRustPainter.startDrawing`'s canvas math verbatim (`BobRustPainter.java:160–170`): a stamp's center in sign-texel space is `((cellX + side/2)·p, (cellY + side/2)·p)`; divide by sign W/H, scale into `canvasArea`, add monitor offset. Rounding to integer screen pixels errs < 1 screen px ⇒ < ~1 texel at typical canvas sizes; combined with brush-edge behavior this means **cell-boundary bleed of up to ±1 texel is the accuracy floor** — invisible at normal viewing, and the painter's ordering (big background stamps first, details later) puts the bleed under later, more-correct paint in the common case.

**Click/action budget — measured.** Simulation: median-cut+kmeans quantizer (stand-in for Wu+Oklab; counts shift only marginally with quantizer choice), greedy cover as specified, pitch 3.125, repo test images. Overheads counted: 1 focus-click + 7 keystrokes per color (hex entry), size-slider clicks (distinct sides per color), autosave every 1,000 stamps (`SettingsAutosaveInterval` default), ~21 setup clicks (matches `PaintTimeEstimator.SETUP_CLICKS` + brush/opacity setup).

XL frame (512×512 texels → 164×164 = 26,896 cells), total input actions (clicks + keystrokes) and wall time at the default 30 clicks/sec (`SettingsClickInterval`, `Settings.java:82`):

| image | N=8 | N=16 | N=32 | N=64 |
|---|---|---|---|---|
| portrait (smooth photo) | 1,982 · 1.1 min | 3,099 · 1.7 min | **5,289 · 2.9 min** | 8,238 · 4.6 min |
| photo_detail | 5,077 · 2.8 min | 8,565 · 4.8 min | **13,024 · 7.2 min** | 17,412 · 9.7 min |
| skyline | 5,072 · 2.8 min | 6,676 · 3.7 min | **9,231 · 5.1 min** | 11,588 · 6.4 min |
| texture (worst-case noise) | 9,023 · 5.0 min | 15,099 · 8.4 min | **19,142 · 10.6 min** | 22,471 · 12.5 min |
| glyphs (dense text) | 10,424 · 5.8 min | 15,504 · 8.6 min | **18,298 · 10.2 min** | 20,470 · 11.4 min |

Other frames (N=32): landscape/small 256×128 (3,362 cells) ≈ **1.8k–2.4k actions, ~1–1.3 min**; XXL 1024×512 (53,792 cells) ≈ **8.0k–14.1k actions, ~4.4–7.8 min** measured on photos (noise-texture worst case extrapolates to ~35k ≈ 20 min). Cell counts for the rest scale directly: tall 128×512 → 6,724; portrait frame 128×256 → 3,362; huge wood 1024×256 → 26,896 (same budget as XL); neon 128×128 → 1,681.

**Verdict on the owner's estimate:** ~26k clicks is **correct as the worst-case ceiling** for XL — but the mechanism is "one min-size stamp per 3.125-texel virtual cell" (26,896), not "one click per texel" (262,144, ~10× more, and unreachable since the brush can't paint below ~3 texels). Tiling brings *typical* XL/N=32 jobs to **5k–19k actions ≈ 3–11 min at 30 cps**, and "~15 min max" is safe for everything except pathological full-canvas noise on XXL. If calibration ever reveals a true 1-texel square brush, the measured hypothetical (512-grid) numbers are: portrait 18.5k ≈ 10 min (fine), glyphs 158.5k ≈ 88 min (impractical) — the mode stays usable on smooth content and the UI estimate (§5) makes the cost visible either way.

### 4. Hex-entry calibration + execution

**New `ButtonConfiguration` coordinate (the only genuinely new one):**

```java
// Hex color entry (pixel mode)
public Coordinate hexInput = DEFAULT;   // click-to-focus point of the hex text field
```

plus the matching line in `ButtonConfiguration.update()` (`ButtonConfiguration.java:50–68`). Gson deserialization of an old `button_config.json` leaves the field null → `update()`'s `requireNonNullElse` maps it to `DEFAULT` (`valid=false`) → the UI knows it's uncalibrated. No `hexConfirm` coordinate: confirm is the Enter key per the owner's spec. `brush_square` already exists (`ButtonConfiguration.java:26`) and is already clickable via `BobRustPalette.getShapeButton(AppConstants.SQUARE_SHAPE /* =3 */)` — **the owner's "add a config location for the square drawing tool" is already done**; only the calibration *UI flow* needs to expose it (today `setupButtonRegions` collects `brush_circle` but not `brush_square`, `ApplicationWindow.java:149–186`).

**Calibration flow changes** (`ApplicationWindow.setupButtonRegions`): append two arrow-marker steps — "Select the Square Brush" (backfills `brush_square` for configs calibrated before this feature) and "Select the Hex Color Input Field". The automatic layout generator (`BobRustPaletteGenerator.createAutomaticV3`) gets a best-effort `hexInput` estimate once someone measures the field's position ratios on a 1080p screenshot (same `v3_remapRelative` technique as the other controls, `BobRustPaletteGenerator.java:27–113`); until then it returns `Coordinate.INVALID` and the manual step is authoritative. `DrawDialog`'s pixel-mode draw button refuses to start while `hexInput.valid()` is false, with a pointer at the Setup Buttons flow.

**Per-color entry sequence** (executed once per color, N times per painting):

1. `clickPoint(hexInput)` — focus the field (reuses the standard 3-phase paced click).
2. Clear: `Ctrl+A` (select-all) then type — typed text replaces the previous color's 6 characters. Fallback if select-all doesn't work in Rust's field (manual test, open question 5): `End` + 7× `Backspace`.
3. Enter the 6 hex digits. **Primary: clipboard paste** — put `RRGGBB` on the AWT clipboard, send `Ctrl+V`. One chord, immune to keyboard-layout problems. **Fallback (setting-controlled): direct typing** with layout-robust key choices — digits via `VK_NUMPAD0..9` (physical numpad codes; unaffected by AZERTY-style layouts where top-row digits are shifted) and letters via `VK_A..VK_F` lowercase (hex parsing is case-insensitive; if a non-QWERTY layout remaps letter positions the *paste* path is the answer, which is why it's primary).
4. `VK_ENTER` — commit.
5. **Verify via `colorPreview`**: capture the preview disc *before* step 1 and compare after step 4 using the existing `capturePreviewDisc`/`regionChanged` machinery (`BobRustPainter.java:332–450`) — plus a stronger check unique to this mode: we *know* the expected sRGB, so also compare the disc's median pixel to the typed color with a tolerance (game-side gamma/at-rest UI tinting can shift it; tolerance calibrated once, warn-only at first).
6. On verification failure: retry the full sequence **once**, then **abort** with `PaintingInterrupted(paintedStamps, InterruptType.HexEntryFailed)` (new enum constant beside `MouseMoved`/`ThreadInterrupted`/`PaintingFinished`, `PaintingInterrupted.java:25–40`). Never "keep trying to draw" like the palette path's `clickColor` warning does — in this mode a wrong color paints an entire color pass wrong, and worse, **unfocused keystrokes leak to the game** (chat, inventory, movement binds). Fail fast, resume later (§6 resume).

All keystrokes go through the same pacing discipline as clicks (`addTimeDelay`-style scheduling between `keyPress`/`keyRelease`), so the input cadence stays inside the user's configured clicks-per-second envelope.

**Executor: `HexColorPainter`** — a sibling of `BobRustPainter` (shares, via extraction rather than copy-paste: `clickPoint`, `clickSlider`, `addTimeDelay`, `checkMouseDisplacement`, the preview-disc verify helpers, and the display/canvas coordinate setup from `startDrawing`'s preamble). Run loop:

```
focus click ×4                        (as today, BobRustPainter.java:103)
clearCanvas click                     (if "clear first" enabled)
brush_square click (clickSlider-verified)
opacity_1 click                       (max opacity; getAlphaButton(5) ≈ the same point)
for each color c in plan order:
    hex entry sequence (above)        — never skipped, always verified
    for each side group (desc):
        size-slider click at fraction side/32 (clickSlider-verified)
        for each stamp: paced click at stamp center
            · sparse canvas verification per SettingsClickVerifyInterval,
              but ONLY on stamps whose center cell the plan predicts is
              changing color (before≠after pixel compare is meaningless when
              stamping color over identical color — the plan knows which)
    autosave (saveImage) every SettingsAutosaveInterval stamps
final saveImage click ×4
throw PaintingInterrupted(…, PaintingFinished)   (same completion contract as today)
```

Opacity note: `getAlphaButton(5)` computes `opacity_0.x + (opacity_1.x − opacity_0.x)·255/256` (`BobRustPalette.java:116–128`) — one pixel shy of the calibrated `opacity_1` endpoint. Pixel mode clicks the `opacity_1` coordinate itself (the calibrated far end; sliders clamp), and "opacity actually reached 1.0" is on the calibration checklist (edge-case table) because *every* stamp depends on it.

### 5. UI / settings

**Mode selector in `DrawDialog`** (not the Settings dialog — it's a per-draw decision, like the preset ladder): a two-button toggle row at the top — **"Brush (palette)"** / **"Pixel (exact hex)"** — persisted as `Settings.SettingsDrawingMode` (`EnumType<DrawingMode>`; the settings framework auto-persists and can auto-generate GUI via `@GuiElement`, `Settings.java:26–100`, `SettingsDialog.addSetting`).

Pixel mode swaps the dialog's middle section (the preset row, stencil checkbox, and shape-count slider are SA-specific and hide):

- **Colors (N):** `JIntegerField` + slider, range 2..64, default 32 → `SettingsPixelColors` (IntType).
- **Dither** checkbox, default off, tooltip carrying the measured cost ("≈2× clicks on smooth images") → `SettingsPixelDither` (BoolType).
- **Clear canvas first** checkbox, default on → `SettingsPixelClearFirst` (BoolType).
- **Skip sign-colored cells** checkbox, default off (advanced) → `SettingsPixelSkipBase` (BoolType).
- **Live estimate label** — same widget pair as today (`estimateLabel`/`estimateDetailLabel`, `DrawDialog:258–270`), fed by a `PixelTimeEstimator` (same linear model family as `PaintTimeEstimator`: actions·(1000/cps) + captures·captureMs, reusing the calibrated `Settings.getCaptureMs()`), recomputed via the existing debounced `SwingWorker` pattern (`runEstimate`, `DrawDialog:410–475`). Detail line: "N colors · M stamps · K size changes".
- **Preview:** planning is fast (quantize+tile ≈ tens of ms at 27k cells — no progressive generation thread needed, a debounced recompute on control change suffices); `ScreenDrawDialog.paintComponent` (`ScreenDrawDialog.java:105–141`) branches on mode and draws the quantized cell image scaled nearest-neighbor into `canvasRect` instead of `shapeRender`'s image — the preview *is* the exact final result, which is the mode's selling point.
- The clicks-per-second field, canvas-area update button, and "Select Color Palette And Draw" button are shared. In pixel mode the draw button skips `BobRustPalette.initWith`'s 64-swatch scan (not needed — no palette clicking) but keeps the screenshot-based sanity check that the paint UI is open, and validates `hexInput.valid()`.

**Presets:** the S3 `PaintPreset` ladder owns SA-specific knobs (generator config, pruning) plus `SettingsClickInterval`/`SettingsClickVerifyInterval` (`PaintPreset.java:50–115`). Pixel mode deliberately stays **outside** the ladder (hidden in pixel mode; `applyPreset`/`syncPresetFromSettings` untouched) and simply reads the current `SettingsClickInterval`/`SettingsClickVerifyInterval` values. Rationale: the ladder's quality axis (pruning tolerance, SA effort) has no pixel-mode analogue; N-colors *is* the quality knob. `PaintPresetTest` invariants are unaffected.

**Persisted brush-geometry constants:** `SettingsSquareBrush` (hidden StringType, `side1=3.125;side32=100` serialized like `SettingsGeneratorConfig`) — defaults from the circle measurements, overwritten by the square-brush calibration when run. The tiler derives pitch and the side→slider-fraction map from these two numbers only.

### 6. Interruption, resume, and pacing

- **Interrupt sources** are inherited: mouse displacement > 10 px aborts (`checkMouseDisplacement`, `BobRustPainter.java:263–271`), `Thread.interrupt` aborts, plus the new `HexEntryFailed`. All carry a progress count.
- **Resume:** a `PixelPaintPlan` mirrors `PaintPlan`'s cursor discipline (`PaintPlan.java` — frozen instruction list + `painted` cursor + `advancePainted`): the flattened op list is `[SetColor c₀, Stamp…, SetColor c₁, Stamp…]`; on resume, re-run setup, re-issue the `SetColor` governing the cursor position, and continue from the cursor. Opaque stamps are idempotent, so the safe pattern "back the cursor up to the last verified stamp" costs at most a few redundant clicks. Unlike the SA path there is no "generation continues while painting" subtlety — the plan is fully static once computed.
- **Pacing / anti-cheat posture:** unchanged from the existing tool — the same user-configured clicks-per-second scheduling (`autoDelay = 1000/(clickInterval·3)`, `BobRustPainter.java:76–77`), the same autosave cadence, the same abort-on-user-mouse-movement. Pixel mode adds keystrokes to the paced stream but changes nothing about the tool's automation profile; total action counts (5k–19k) sit *below* typical SA runs at comparable quality settings.

---

## Edge cases

| # | Case | Risk | Handling |
|---|---|---|---|
| 1 | Source alpha / transparency | Transparent regions quantize as garbage or black | Already composited over the chosen background by `ImageUtil.getScaledInstance` (bg fill + SrcOver draw) before the pipeline sees pixels; quantizer input is always opaque RGB. |
| 2 | Anti-aliased source edges | AA blend colors form halo clusters, eating palette entries and fragmenting masks into 1-cell stamp noise | Area-average resize to the cell grid re-blends them (a 3.1-texel cell absorbs the halo); Oklab clustering assigns leftovers to the perceptually-nearest side. Residual: a 1-cell color seam costs 1 stamp per cell — bounded, visible in preview, user can lower N. |
| 3 | Single stray-cell mask (1-cell color region) | Minimum-size stamp per cell — count blowup if many | Size-1 stamp = 1 click; the N=64 sweep bounds the damage (worst corpus image: 22.5k actions). Preview + live estimate expose it before painting. |
| 4 | Brush-size ↔ pixel granularity (the load-bearing one) | If size-1 ≠ 3.125 texels or the side function isn't linear, stamps misalign with the planned grid — systematic drift across the sign | Design is pitch-parameterized from `SettingsSquareBrush`; a square-brush calibration pass (pattern paint → screenshot → measure, extending the existing `CalibrationPatternGenerator`/`ScreenshotAnalyzer`) measures side(s) for s ∈ {1,2,4,8,16,32} and fits `a·s+b`; nonlinearity ⇒ per-side lookup table instead of the linear map. Ship defaults from the circle data; block nothing on the calibration, surface a "brush geometry unverified" hint in the UI until run. |
| 5 | Slider click doesn't land the intended size (game quantizes or hitbox offset) | Whole size-group painted at wrong footprint → overlap onto *earlier* colors, breaking the exactness invariant | `clickSlider`-style verified click per size change; sizes ordered desc within a color so an off-by-one side errs small→gaps get covered by nothing later — prefer erring downward: round the slider fraction *down* to the nearest measured-safe position. Calibration (#4) tells us the real quantization. |
| 6 | Opacity not actually 1.0 (calibration off by pixels, or game max ≠ fully opaque) | Every stamp slightly blends with substrate → all colors off | Click the calibrated `opacity_1` endpoint itself (clamping), verified via `clickSlider`; calibration checklist: paint black over white at "max", measure. If the game's max is genuinely < 1.0, exactness is bounded by the game (document; the 2-pass "paint twice" fallback doubles clicks and is not planned). |
| 7 | Later color's square overlapping an earlier color's cells | Silent wrong pixels — worst kind of bug | Structurally impossible in the planner: stamps of rank k are constrained to `A_k` (rank ≥ k cells). Enforced by a unit-tested invariant (render the plan, assert equality with the quantized image) — the invariant test *is* the spec. Runtime residual: ±1-texel screen-rounding bleed at boundaries (accepted accuracy floor, §3). |
| 8 | Hex field loses focus mid-typing / entry fails | Keystrokes leak to the game (chat/binds); or color silently wrong for a whole pass | Preview-disc verification after Enter with expected-color check; retry once; hard abort (`HexEntryFailed`) — never spray-and-pray. Paste path minimizes keystroke exposure to one chord. |
| 9 | Keyboard layout (AZERTY etc.) | Typed "digits" produce punctuation → wrong or rejected hex | Primary path is clipboard paste (layout-free). Typing fallback uses numpad VK codes for digits. If the game field rejects paste (open question 5), the fallback is the default and AZERTY users rely on numpad digits + verified preview. |
| 10 | Hex field content persists between colors | Typing appends → 12-char garbage | Ctrl+A-then-type (replace) each entry; `End`+Backspace×7 fallback; verification catches failures regardless. |
| 11 | Verification false-negatives on stamps (before==after when painting over same color) | Retry-loop stalls, log spam | Plan-aware verification: only verify stamps whose center cell is *predicted* to change (planner knows the substrate); others clicked without capture — also saves ~2 captures/stamp of the `getPixelColor` tax (`PaintTimeEstimator` model). |
| 12 | Mid-draw interruption (mouse moved, alt-tab, disconnect) | Half-painted sign | `PixelPaintPlan` resume cursor (§6); opaque stamps idempotent; resume re-issues current color's hex entry. Autosave every `SettingsAutosaveInterval` stamps caps loss on disconnect. |
| 13 | ≥N genuinely important colors (owner's "all important" case) | Quantizer merges something the user cares about | Exact preview before painting + N up to 64 + live cost readout; per-color "pin this color" is future work (open question 4). |
| 14 | Very high N (towards 64) vs very low N | Hex-entry overhead; palette starvation | Hex overhead is linear and tiny (8 actions/color = 512 actions at N=64); the real N cost is mask fragmentation, measured in the N-sweep table — the estimate label makes the trade visible. Low N: preview shows the damage. |
| 15 | XXL 1024×512 (and huge wood 1024×256) | 2× (1×) the XL cell count → up to ~35k actions on noise | Measured 8–14k actions on photos (~4–8 min); estimate label warns; nothing structural — same plan, more cells. |
| 16 | Sign-material "letterbox" region when image doesn't fill the frame | Painting thousands of cells of flat background color that already roughly matches the sign | It's ~36 stamps for a full-canvas flat region at max brush (6×6 grid of side-32 stamps on XL) — negligible; and `SettingsPixelSkipBase` (off by default) exists for purists. |
| 17 | 15-min anti-cheat / cadence concerns | Behavioral flag risk from long uniform-rate runs | Identical posture to the existing painter: user-set cps, paced keystrokes, autosaves, human-abort via mouse move. No new automation signature; runs are typically *shorter* than SA paints. |
| 18 | Rust UI scale / resolution changes between calibration and painting | All coordinates wrong (existing failure mode, sharper here because keystrokes are involved) | Same defense as today (setup verification clicks + preview-disc check on the first hex entry aborts early); recommend re-running Setup Buttons after any UI-scale change (docs). |
| 19 | Game patch changes hex-field position/behavior | Mode breaks while palette mode still works | `hexInput` is one re-calibrable coordinate; failure mode is the clean `HexEntryFailed` abort, not corrupted paint. |
| 20 | Square brush has corner rounding / soft edge at small sizes | Cell corners under-covered → grid-pattern speckle | Calibration (#4) measures the actual footprint shape (`ScreenshotAnalyzer.computeShapeMatch` already does mask-vs-screenshot comparison for circles; reuse for squares). If size-1 squares are effectively rounded, bump pitch to size-2 (6.25 texels) for exact work — pitch is a parameter. |

---

## Implementation plan

Ordered; each step is independently buildable + testable. Effort: S (≤ ~150 LoC), M (~150–500), L (500+). No calendar estimates.

**Step 1 — Plumbing: config coordinate + settings + calibration step.** *(S, low risk)*
- `ButtonConfiguration`: add `hexInput` field + `update()` line (`robot/ButtonConfiguration.java`).
- `Settings`: add `SettingsDrawingMode` (EnumType over new `settings/data/DrawingMode.java`), `SettingsPixelColors` (IntType 2..64, default 32), `SettingsPixelDither`, `SettingsPixelClearFirst`, `SettingsPixelSkipBase` (BoolTypes), `SettingsSquareBrush` (hidden StringType, default `side1=3.125;side32=100`).
- `ApplicationWindow.setupButtonRegions`: append "Select the Square Brush" and "Select the Hex Color Input Field" arrow-marker steps; wire into `config` + save.
- Tests: extend `ButtonConfigurationTest` (serialization round-trip incl. missing-field back-compat → `DEFAULT`).

**Step 2 — Quantizer.** *(M, medium risk — algorithm correctness)*
- New package `generator/quant/`: `Oklab.java` (sRGB↔Oklab, ~40 lines), `WuQuantizer.java` (classic 33³-moment implementation), `PixelQuantizer.java` (facade: Wu seed → fixed-iteration Oklab Lloyd → `Result{int[] paletteRgb, int[] labels, int gridW, int gridH}`; deterministic tie-breaks; optional FS dither pass on the label assignment).
- `util/PixelImageScaler.java`: area-average scale using `getScaledInstance`'s rect-mapping math (source placement via canvasRect/imageRect) but a box kernel; bg-fill + SrcOver composite kept identical.
- Tests (deterministic, JUnit): determinism (two runs bit-equal), N respected, ΔE00 (via existing `Ciede2000`/`ImageMetrics`) beats a median-cut baseline on the `test-images-complex` corpus, alpha compositing, dither on/off label diffs.

**Step 3 — Tiler + plan.** *(M, the algorithmic core — highest correctness leverage, zero UI/Robot risk)*
- `generator/tiler/PixelStamp.java` (record: cellX, cellY, side, colorIndex), `generator/tiler/SquareTiler.java` (rank ordering, per-color DP + greedy cover with `A_k` don't-care, side grouping), `generator/tiler/PixelPaintPlan.java` (flattened `SetColor`/`Stamp` op list, resume cursor à la `PaintPlan`, stats accessors for the estimator).
- Tests: **the exactness invariant** (render plan ops onto a grid initialized to a sentinel; assert bit-equality with the quantizer labels — for every image in the corpus and randomized label grids); no stamp of rank k covers rank < k; side ≤ 32; solid-color canvas → ceil(164/32)² = 36 stamps; single-stray-cell masks; stamp-count regression pins (commit the measured counts from this doc's table as the baseline).

**Step 4 — Cost model.** *(S, low risk)*
- `util/PixelTimeEstimator.java`: actions = stamps + Σ distinct sides + N·(1 focus + 7 keys) + autosaves + setup; time = actions·(1000/cps) + predictedCaptures·`Settings.getCaptureMs()`; `Estimate` record mirroring `PaintTimeEstimator.Estimate`. Tests: closed-form checks against hand-built plans.

**Step 5 — Executor.** *(M–L, highest integration risk — Robot path, manual verification)*
- Extract the shared click/pacing/verify primitives from `BobRustPainter` into a package-private `RobotInput` helper (pure refactor, byte-identical behavior; keeps the two painters honest).
- `robot/HexColorPainter.java`: run loop from §4; `robot/HexKeyboard.java` (clipboard-paste primary, numpad-typing fallback, paced key events); plan-aware sparse verification; `InterruptType.HexEntryFailed` added to `PaintingInterrupted`.
- New `BobRustPalette.getPixelSizeButton(int sideCells, BrushGeometry g)` (fraction = side/32 between `size_1`/`size_32`).
- Tests: coordinate mapping + slider-fraction math as pure functions; keyboard encoder (VK sequences for a given hex string) unit-tested; the Robot loop itself is manual-only — add a **dry-run mode** (log + on-screen overlay of planned clicks, no `mousePress`) reusing the `ALLOW_PRESSES` pattern (`BobRustPainter.java:26`) for safe in-game rehearsal.

**Step 6 — UI integration.** *(M, medium risk — Swing state discipline)*
- `DrawDialog`: mode toggle row; pixel-mode control set (N field/slider, dither, clear-first, skip-base); hide/show of SA controls; debounced plan recompute (reuse the `estimateDebounce`/`SwingWorker` pattern) feeding `estimateLabel`; draw-button branch → `HexColorPainter` with `hexInput.valid()` gate; resume wiring through `PixelPaintPlan.advancePainted` in the `finally` block (mirroring `startDrawingAction`, `DrawDialog:484–581`).
- `ScreenDrawDialog.paintComponent`: pixel-mode branch drawing the quantized preview (nearest-neighbor into `canvasRect`).
- Persistence is free via the settings framework; `syncPresetFromSettings` untouched (presets stay SA-only).

**Step 7 — Benchmark + docs.** *(S)*
- `src/test/java/com/bobrust/benchmark/PixelModeSweepTest.java` (`@Tag("benchmark")`, style of `ClickBudgetSweepTest`): regenerate this doc's stamp/action tables in Java against the corpus — the living version of the click-budget claim, and the regression harness for tiler changes.
- `docs/PIXEL-MODE.md`: this design, condensed; calibration instructions.

**Step 8 — Square-brush footprint calibration.** *(M, independent — can land any time after Step 1; until then defaults apply)*
- Extend `CalibrationPatternGenerator` with a square-stamp row and `ScreenshotAnalyzer` with side-length + corner-shape measurement (its scale-mapping and mask-compare machinery transfers directly); output a `SettingsSquareBrush` string. Optionally: an in-app "measure brush" helper that paints the pattern via the executor and analyzes a screenshot in one flow.

Dependency order: 1 ∥ 2 → 3 → 4 → (5, 6) → 7; 8 independent after 1. Steps 2–4 are pure JVM logic with deterministic tests — build and validate the entire planner before any Robot code exists.

---

## Open questions for the owner

1. **Dither default** — recommend OFF (measured ≈2× clicks on smooth images, no benefit on busy ones); checkbox exposed. Confirm, or want it hidden entirely?
2. **Letterbox fill color** — when the image doesn't fill the frame, today's behavior fills with `SettingsBackground` (default: sign material average). In pixel mode that region gets *painted*. Keep (uniform, ~36-stamp cost), or default the fill to a user-picked flat color (e.g. black/white) for deliberate framing?
3. **N default 32 confirmed?** The measured curves say 16 is often visually sufficient at ~60% of the clicks on photos; 32 is a fine default, but if you want "fast by default", 24 is a reasonable middle. Slider range 2..64 either way.
4. **"Pin color" feature** — worth a follow-up where the user can force specific colors into the palette (logo brand colors) before Lloyd runs? Cheap to add post-MVP, out of scope for v1.
5. **Two 1-minute in-game facts I can't get from the repo** (they select code paths, nothing structural): (a) does the hex field accept **Ctrl+V paste**? (b) does it accept **Ctrl+A select-all**, and is a `#` prefix required or auto-shown? One manual test each; the design carries fallbacks for every combination.
6. **Square-brush geometry measurement** (Step 8) needs one calibration paint on a real sign to replace the circle-derived defaults (`side1=3.125`, linear). Until then the mode ships with defaults that match the existing tool's own measured brush data — acceptable?
7. **Continuous vs integer slider sizes** — if the in-game size slider is continuous (the current code already clicks fractional positions, `BobRustPalette.getSizeButton`), we get exactly 32 clean size steps; if the game snaps to integers 1..32, nothing changes. If it snaps to something *coarser*, the tiler's side vocabulary shrinks (calibration will reveal this). No action needed from you unless you already know the answer.

---

*Numbers provenance: all stamp/click/time figures measured by `tiling_sim.py` (this directory) against `src/test/resources/test-images{,-complex}/` — median-cut+kmeans quantizer stand-in, greedy cover as specified in §3, pitch 3.125, 30 cps, autosave 1000, setup 21. Re-run: `python3 tiling_sim.py`.*
