# BobRust "Palettized Mode" — HSV-Picker Color Entry + Continuous-Size Square Tiling

**Design + implementation plan.** Target repo: `/home/ec2-user/projects/bobrust-fix` (Java 17+, Gradle, branch `fix/review-findings`).

**This document supersedes the color-entry and brush-size sections of `PLAN-PIXEL-MODE.md`** after the owner's screenshot of Rust's current color picker (`docs/rust-hsv-color-picker.png`) proved the hex field is **read-only**. The planner core of that document (quantizer, resize, square tiler, painter's algorithm, resume machinery) carries over; §0 below states section-by-section what carries, what is revised, and what is new. Read that document first — shared reasoning is referenced, not repeated.

**Scope — evidence actually examined for this design:**
- Code (re-read, line references verified): `robot/BobRustPainter.java`, `robot/BobRustPalette.java`, `robot/ButtonConfiguration.java`, `robot/BobRustPaletteGenerator.java`, `robot/error/PaintingInterrupted.java`, `calibration/CalibrationPatternGenerator.java`, `calibration/ScreenshotAnalyzer.java`, `generator/BorstUtils.java`, `generator/CircleCache.java`, `gui/ApplicationWindow.java`, `gui/dialog/{DrawDialog,ScreenDrawDialog,RegionSelectionDialog,SettingsDialog}.java`, `settings/Settings.java`, `settings/data/PaintPreset.java`, `util/ImageUtil.java`, `util/PaintTimeEstimator.java`, `util/data/AppConstants.java`, tests `calibration/CalibrationRoundTripTest.java`, `generator/ExactColorStudyTest.java`.
- The picker screenshot, **measured pixel-by-pixel** (analysis summarized in §1; scripts inline in the session, reproducible from the PNG).
- A new simulation, `palettized_sim.py` (this directory): click budgets with continuous sizes + HSV entry pricing (Part A), picker quantization / closed-loop convergence (Parts B–E). All numbers in this document labeled *measured* come from it; re-run with `python3 palettized_sim.py` (plus `refit` / `probe` / `accept` arguments for parts C/D/E).

---

## TL;DR

- **Mode:** resize → quantize to N exact colors (default **32**, user 2..64) → per-color greedy square-tiling → paint **full-opacity** with the **square brush**, colors set via Rust's **HSV picker** (hue-bar click + SV-square click), sizes and opacity set by **typing into the numeric SIZE/OPACITY fields**. A sibling drawing mode on the draw-prep screen, beside the existing quality-preset (simulated annealing) pipeline.
- **The read-only hex is an asset, not a loss.** The color-preview swatch is a large flat fill of *exactly* the selected color (measured: 16,275 screenshot pixels byte-identical to the `#295A66` readout). Every color entry is therefore **closed-loop**: click hue, click SV, read the swatch back with the existing screen-capture machinery, nudge ±1–3 px until it matches, adopt the read-back as the palette color. No OCR needed (hex-OCR remains a fallback idea only).
- **The picker is a textbook linear HSV picker** (measured in the screenshot): hue bar linear with **H=360° at top → 0° at bottom**; SV square `x`=saturation 0→1 left→right, `y`=value 1→0 top→bottom; whole square within mean 3.1/255 per channel of the ideal `HSV(h,s,v)` field; the crosshair sits within 0.5 px of where `#295A66` predicts. The design still *measures* the mapping per install (robot-driven probe fit, §2.3) rather than assuming it — the fit is orientation-agnostic and doubles as the "is the picker even in HSV mode?" pre-flight check.
- **Precision verdict (measured, Parts B/E):** clicking is quantized by screen pixels — at a 1080p-sized picker (~205 px square) the *reachable-color floor* for a 40-color palette is mean ΔE00 0.25, max 0.95 vs the ideal quantizer centroids; the closed loop **achieves that floor** (after ≤3 nudges: mean 0.25, max 0.96, ≤4 swatch reads/color) even when the user-marked rects start ±3 px and 2% scale off. Everything lands under ΔE00 ≈ 1.0 — at or below one just-noticeable difference, and an order of magnitude better than the old 64-swatch palette snap. Byte-exactness holds between the **adopted** palette (what was actually read back) and the canvas; the preview is within ≤1 ΔE00 of it per entry.
- **Continuous brush size (typed into the SIZE field) buys exact alignment, not fewer stamps.** The tiler still stamps integer multiples of the base cell (sides 1..32), so stamp counts match the old sim; the wins are (a) the base size can be chosen so the footprint is an **exact texel count** — pitch 3.2 texels (SIZE ≈ 1.024 under the linear model) makes a 160×160 XL grid with zero rounding drift, (b) sizes are typed exactly, killing the slider-quantization edge case, (c) opacity is typed as exactly `1`. Cost: a size change is ~6 input actions instead of 1 slider click — measured, this shifts totals <10% and a restricted side vocabulary does not beat it (§3.3).
- **Click budget (measured, XL 512×512, N=32 default, pitch 3.2, 30 cps):** portrait **6.9 k actions ≈ 3.8 min**, skyline 10.1 k ≈ 5.6 min, photo 12.6 k ≈ 7.0 min, glyphs 18.5 k ≈ 10.3 min, texture (worst case) 19.1 k ≈ 10.6 min. "~15 min max on XL" holds with headroom; a pitch-4.0 economy option cuts glyph-like images to ~7 min.
- **New calibration surface:** three rectangles (SV square, hue bar, swatch) + two points (SIZE field, OPACITY field) + exposing the already-existing `brush_square` point, appended to the existing Setup flow. A ~27-click automatic probe pass at the start of every paint fits the picker mapping and pre-flights the whole color subsystem before a single canvas click.

---

## 0. Relationship to PLAN-PIXEL-MODE.md

| PLAN-PIXEL-MODE section | Status | Notes |
|---|---|---|
| §0 Where the mode sits | **Carries over** | Same architecture slot; mode name changes to "Palettized". |
| §1 Color quantization (Wu → Oklab Lloyd, dither off, alpha compositing) | **Carries over** | Default N stays **32** (owner, 2026-07-15); the final palette is post-composed with the picker's reachable-color snap (§2.4) so preview == paint. |
| §2 Resize-to-frame (area-average, cell grid, quantize-after-resize, base material) | **Carries over** | One revision: pitch is now *chosen* (3.2 texels default) rather than inherited (3.125), because the SIZE field makes fractional base sizes reachable (§3.2). |
| §3 Square tiling / click plan | **Mostly carries over** | DP cover, painter's-algorithm relaxation, rank ordering, cell-unit sides 1..32 — all unchanged. Revised: pixel→screen mapping now lands on exact texel boundaries (§3.2); size changes are field entries, not slider clicks (§3.3); budget tables regenerated (§8). |
| §4 Hex-entry calibration + execution | **INVALID — replaced** | The hex field cannot be typed into. Replaced by the HSV color-entry subsystem (§2) and field entry for size/opacity (§3). `hexInput` coordinate, `HexColorPainter`, `HexKeyboard`, `InterruptType.HexEntryFailed` are all dropped. |
| §5 UI / settings | **Carries over, renamed** | `DrawingMode.PALETTIZED`, `SettingsPalettizedColors` default 32; estimate model re-parameterized (§7). |
| §6 Interruption / resume / pacing | **Carries over** | New interrupt types replace `HexEntryFailed` (§6). Resume re-issues the current color's picker entry instead of hex entry. |
| Edge cases 1–7, 11–18, 20 | **Carry over** | Table §9 restates only the changed/new rows. |
| Edge cases 8–10, 19 (hex-specific) | **Replaced** | HSV/field analogues in §9. |
| Implementation Steps 2, 3, 4, 7 | **Carry over** | Quantizer, tiler, estimator, benchmark — unchanged except names/constants. |
| Implementation Steps 1, 5, 6, 8 | **Revised** | Plumbing, executor, UI, brush calibration — this document's §10. |
| Open questions 1–4, 6, 7 | **Still open / updated** | Restated in §12 with the new HSV/field questions. |

---

## 1. The picker — measured facts

Source: `docs/rust-hsv-color-picker.png` (571×1439 crop of the in-game paint panel). All coordinates below are in that image's pixel space; the *shape* of the findings, not the absolute pixels, is what the design relies on (absolute geometry is calibrated per install, §2.2–2.3).

- Panel: TOOLS (paintbrush/eraser/eyedropper), BRUSH (7 shapes — the 4th is the **solid square**; SIZE `7.01`, SPACING `0.01`, OPACITY `0.20`, each a slider with a numeric readout), COLOUR (SV square, hue bar, swatch, read-only hex, and a small toggle icon top-right of the header — presumably HSV picker ⇄ legacy palette grid).
- **SV square** at x∈[61,372], y∈[762,1073]. Sampled against the ideal `HSV(h, s=x-fraction, v=1−y-fraction)` field for h=191.8°: mean per-channel deviation 3.1/255, max 9/255 (consistent with screenshot compression). Corners: TL≈white, TR≈pure hue, BL≈black, BR≈dark hue. So: **x = S ∈ [0,1] left→right, y = V ∈ [1,0] top→bottom.**
- **Hue bar** at x∈[377,426], same y span. 13 samples down the center: H = 359.1° at top → 179.8° at midpoint → 1.2° at bottom, linear within ~1°. **H = 360·(1 − t) for t = fraction down the bar.** The two triangle markers straddle y=907; the predicted position for H=191.8° under that mapping is y=907. Confirmed.
- **Swatch** at x∈[431,520], y∈[762,999]: a **flat fill** — 16,275 pixels all exactly `(41,90,102)` = `#295A66`. The hex readout below it matches byte-for-byte. HSV of that color: (191.8°, 0.598, 0.400).
- **Crosshair** in the SV square detected at (248,949); position predicted from `#295A66` under the mapping above: (247.0, 948.6). Sub-pixel agreement.

Conclusions the design builds on: the picker is linear HSV with the orientations above; the swatch is a perfect closed-loop sensor; **and none of this is hard-coded** — the probe fit (§2.3) re-derives offsets, scales *and sign/orientation* per install, so a game patch that flips the bar breaks nothing.

The **eyedropper** tool picks colors *from the canvas*. It cannot set a color that is not already painted, so it is useless for entering the palette (which is the whole job) and would add a fragile screen-position dependency for zero benefit on repeat entries (each palette color is entered exactly once — the plan never revisits a color). Excluded from the design.

---

## 2. The color-entry subsystem (the novel core)

### 2.1 New `ButtonConfiguration` coordinates

Following the existing two-corner convention used for the palette grid (`color_topLeft`/`color_botRight`, `ButtonConfiguration.java:37–38`):

```java
// HSV color picker (palettized mode)
public Coordinate hsvSquare_topLeft   = DEFAULT;  // SV square rect
public Coordinate hsvSquare_botRight  = DEFAULT;
public Coordinate hueBar_topLeft      = DEFAULT;  // hue bar rect
public Coordinate hueBar_botRight     = DEFAULT;
public Coordinate swatch_topLeft      = DEFAULT;  // color-preview swatch rect
public Coordinate swatch_botRight     = DEFAULT;
// Numeric input fields (palettized mode)
public Coordinate sizeField           = DEFAULT;  // click-to-focus point of the SIZE readout
public Coordinate opacityField        = DEFAULT;  // click-to-focus point of the OPACITY readout
```

plus the matching `Objects.requireNonNullElse` lines in `update()` (`ButtonConfiguration.java:50–68`). Gson back-compat is free: absent fields in an old `button_config.json` deserialize to null → `update()` maps them to `DEFAULT` (`valid=false`) → the UI knows the mode is uncalibrated. `brush_square` already exists (`ButtonConfiguration.java:26`) and is already clickable via `BobRustPalette.getShapeButton(AppConstants.SQUARE_SHAPE /* =3 */)` (`BobRustPalette.java:141–147`, `AppConstants.java:109`) — the owner's item 4 needs **no new robot code**, only exposure in the calibration UI flow (today `setupButtonRegions` collects `brush_circle` but never `brush_square`, `ApplicationWindow.java:149–167`).

`BobRustPaletteGenerator.createAutomaticV3` (`BobRustPaletteGenerator.java:37–114`) can grow best-effort estimates for all eight coordinates with the same `v3_remapRelative` technique once someone measures the HSV panel's ratios on a 1080p screenshot; until then they return invalid and the manual steps are authoritative — same posture the old plan took for `hexInput`.

### 2.2 Calibration UX (Setup flow)

`ApplicationWindow.setupButtonRegions` (`ApplicationWindow.java:146–191`) currently runs 7 arrow-marker point steps, then the palette **rect** selection via `regionSelectionDialog.openDialog(..., DOTTED_4_16, ...)` (`selectPaletteRegion`, `ApplicationWindow.java:193–254`). Palettized mode appends, in this order:

1. Arrow-marker points (reusing `openArrowMarker`, `RegionSelectionDialog.java:132`): **"Select the Square Brush"**, **"Select the SIZE number field"** (the numeric readout at the right end of the SIZE slider), **"Select the OPACITY number field"**.
2. Rect selections (reusing `openDialog` exactly like the palette region): **"Drag over the Saturation/Value square"**, **"Drag over the Hue bar"**, **"Drag over the color preview swatch"**. Corner accuracy of ±3 px and a few % of scale is sufficient — the probe pass below refines everything else. Instructional text tells the user to have the COLOUR panel toggled to the HSV picker (not the legacy grid) before starting.

All nine values persist through the existing `saveButtonConfiguration` (`ApplicationWindow.java:397–404`, `button_config.json`). The draw button in palettized mode refuses to start while any of the eight new coordinates is invalid, pointing at Setup Buttons — same gate the old plan specified for `hexInput`.

### 2.3 Probe calibration — fitting the click→color mapping (every paint, automatic)

The user-marked rects give a *prior*; the authoritative mapping is **fitted in-game by the robot at the start of every paint run** (and its result persisted as the next run's prior). This costs ~27 paced clicks + 27 swatch captures ≈ 5–10 s and eliminates the entire class of "calibration drifted / resolution changed / picker moved by a patch" failures *before the first canvas click*.

Model (per axis, linear — exactly what §1 measured, but never assumed):

```
x_px  = x0 + S · Wx      (SV square, saturation axis)
y_px  = y0 + V' · Wy     (SV square, V' = 1 − value)
yh_px = h0 + H' · Wh     (hue bar,   H' = 1 − hue/360°)
```

Probe sequence (`HsvColorPicker.probe(Robot)`):

1. Click the hue bar at mid-height (sets some well-conditioned hue), then click the SV square's top-right region (S≈1, V≈1) so hue is observable in the swatch.
2. **S axis:** 5–9 clicks spread along the top edge of the marked SV rect (V≈1); after each, read the swatch (§2.5), convert to HSV, record `(x_px, S_readback)`.
3. **V axis:** same along the right edge (S≈1) recording `(y_px, V'_readback)`.
4. **Hue axis:** with SV pinned at top-right, 5–9 clicks down the marked hue-bar rect recording `(yh_px, H'_readback)`.
5. Per-axis least-squares fit of `(offset, scale)`. **The sign of the fitted scale determines orientation** — a flipped hue bar or a bottom-up V axis simply fits a negative scale; nothing is hard-coded.
6. Sanity gates, each aborting cleanly (§6) with a specific message *before any painting*:
   - fit residual > 2 px on any axis → rects mis-marked or the picker is not in HSV mode ("toggle the COLOUR panel to the HSV picker and re-run");
   - hue readbacks not spanning > 300° → not a hue bar under the marked rect;
   - a reference click at fitted (S=1, V=1, H=0) reading back far from `(255,0,0)` → a global screen color transform (night mode, HDR tone-mapping, color-blind filter) — warn and refuse, because absolute readback is load-bearing in this mode (the legacy mode only ever compared before/after and did not care).

The fitted model is stored in `Settings.SettingsHsvPicker` (hidden StringType, `x0=…;wx=…;y0=…;wy=…;h0=…;wh=…` — same serialization style as `SettingsGeneratorConfig`, `Settings.java:100`) and used as the prior next run.

*Why measured and not assumed:* simulation Part D shows even a probe-fitted model is only sub-pixel accurate (offsets ≤ ~1 px), and Part B shows a mere 1% scale error in an *unfitted* model drops first-click byte-exactness to 43–65%. The probe pass plus the per-color nudge loop (§2.5) is what turns "usually right" into "always within tolerance".

### 2.4 Color → click mapping, and what "exact" means here

Planner side (`HsvColorPicker.clicksFor(int rgb)`): sRGB → HSV (`java.awt.Color.RGBtoHSB` semantics; the game's picker matched ideal HSV within measurement noise, §1) → invert the fitted model → round to integer screen pixels → clamp to the rect.

Because clicks are integer pixels, the picker exposes a finite reachable-color set. Per-pixel steps at a picker of side P px:

- ΔV per y-px = 1/P → ≤ 255/P per 8-bit channel (≈1.2 at P=205);
- ΔS per x-px = V/P in channel units — finer, and vanishing for dark colors;
- ΔH per hue-px = 360°/P → worst-case channel step 1530·S·V/P (≈7.5 at P=205 for fully saturated colors — hue is the coarse axis, exactly as the old plan feared for *sliders*; here it is measured and bounded).

**Measured floor (Part B, real 40-color palettes from the repo corpus):** nearest-reachable snap ΔE00 mean 0.25 / max 0.95 at P=205 (1080p-sized picker); mean 0.10 / max 0.63 at P=312; mean 0.06 / max 0.64 at P=410. All under ΔE00 ≈ 1 (one JND). For comparison, the existing pipeline's 64-swatch palette snap errs by *multiple* ΔE00 routinely (`ExactColorStudyTest` exists precisely because that gap is visible).

**The exactness contract** (sharper than the old plan's, because the loop observes truth): the executor *adopts* the best swatch read-back as the palette color (§2.5). Therefore:

1. **Canvas == adopted palette, byte-exact** — the game paints exactly the swatch color at opacity 1 (this is the tiler invariant's ground truth, §5);
2. **Adopted palette vs the preview's palette: ≤ ~1 ΔE00 per entry, typically ≈0.2** (measured, Part E) — the preview is computed from the model-predicted reachable colors, so it is honest to within less than one JND;
3. When the model predicts perfectly (Part B "perfect model" row: 100% at 1 read), adopted == predicted and the preview is byte-exact too.

### 2.5 Closed-loop entry: read-back, nudge, converge, abort

Swatch read-back (`readSwatch(Robot)`): capture the calibrated swatch rect inset by ~15% per side with one `createScreenCapture` (same primitive as `capturePreviewDisc`, `BobRustPainter.java:394–408`), take the per-channel **median**. The swatch is a flat fill (§1), so the median is exact and robust to a few px of rect error and edge anti-aliasing. This *replaces* the disc-change heuristic for palettized mode — we compare absolute values, since we know the target; the legacy `colorPreview` disc machinery stays untouched for brush mode.

Per-color entry (`ColorEntryController.enter(target)` — pure logic, Robot-free, unit-testable):

```
(x, y, yh) = clicksFor(target)                         // via fitted model
predicted  = model.colorAt(x, y, yh)                   // the preview promise
for read in 1..MAX_READS (default 6):
    click hue bar (yh) if changed; click SV square (x, y)   // paced clicks, §6
    got = readSwatch()
    track best-so-far by ΔE00(got, target)             // Ciede2000.java, existing
    if got == predicted (byte) or ΔE00(got, target) ≤ ACCEPT (default 1.0):
        adopt(got); break
    error   = HSV(target) − HSV(got)                   // hue wrapped to ±180°
    step_px = error scaled by fitted Wx/Wy/Wh, clamped to ±3 px,
              min ±1 px in the largest-error axis if all round to 0
    (x, y, yh) += step_px;  abort the loop if this position was already tried
on loop exhaustion:
    if best ≤ ACCEPT_LOOSE (default 1.5): adopt best (warn-log)
    else: throw PaintingInterrupted(painted, InterruptType.ColorEntryFailed)
```

Design properties:

- **Bounded and non-spraying:** at most `MAX_READS` swatch reads and ≤ 2·`MAX_READS` picker clicks per color, every click inside the calibrated picker rects, every step ≤ 3 px. A wrong color can never leak canvas clicks: the executor's canvas pass for a color starts only after `adopt`. Contrast with the legacy `clickColor`'s "warn and keep drawing" (`BobRustPainter.java:367`) — in this mode a wrong color corrupts an entire color pass, so the failure mode is a clean typed abort, exactly the old plan's `HexEntryFailed` posture with a new name.
- **Convergence (measured, Part E):** starting from rects ±3 px / 2% scale off and probe-fitted: first click mean ΔE00 0.73 / max 2.50; after nudging, mean 0.25 / max **0.96** — i.e. the loop reaches the physical floor; reads mean 3.2, max 4 (< MAX_READS=6). With a perfect model (Part B): 1 read, byte-exact, 100%. The `NUDGE_AVG=2.2` clicks/color priced into §8 comes from this.
- **Online refit (cheap insurance):** every `(click px, readback HSV)` pair is a free observation; after each color, the per-axis fits are updated with the accumulated pairs (only well-conditioned ones: V-observations need V>0.1, hue needs S·V>0.08). Measured (Part C) this cuts reads/color from 3.1 to ~1.8 under model error; with the probe pass already in place it mostly guards against mid-run drift.
- **Hue-first ordering** within an entry: the SV square's rendering depends on hue, but the *stored* S/V do not (standard picker semantics, and the probe verifies it: S/V readbacks during hue probing stay pinned). If calibration ever finds otherwise, the loop already re-clicks SV after hue each iteration, so correctness is unaffected — only the click count would rise.
- Colors whose S or V ≈ 0 (greys/blacks): hue is unobservable *and irrelevant*; the controller weights axis errors by observability so it never chases hue noise on greys. (This falls out of stepping in the largest-*scaled*-error axis.)

### 2.6 Why not OCR the hex?

The swatch read gives the same information as the hex text at zero dependency cost, one capture, and no font/scale sensitivity. OCR of the 6-glyph hex (fixed font, high contrast) would work as a *diagnostic* fallback if some install renders the swatch with an overlay — noted as future work, not designed in.

---

## 3. Exact brush SIZE and OPACITY via the numeric fields

### 3.1 Field-entry primitive

The SIZE/SPACING/OPACITY readouts are editable text fields per the owner. Entry sequence (`FieldInput.enter(Coordinate field, String value)` — the direct successor of the old plan's `HexKeyboard`, same keystroke discipline):

1. `clickPoint(field)` — focus (3-phase paced click, `BobRustPainter.java:273–298` extracted per §6).
2. Clicking the field (step 1) already focuses it **and selects the current number** (owner-confirmed 2026-07-15), so **no `Ctrl+A` is needed** — directly **clipboard paste** the value (primary; one chord, layout-immune) or typed fallback: digits via `VK_NUMPAD0..9`, decimal point via `VK_PERIOD` *and* `VK_DECIMAL` variants behind a setting (locale decimal-comma risk — same reasoning as the old plan's AZERTY analysis, edge case 9 there). Submit with **Enter** (or a click away).
3. `VK_ENTER` commit.
4. **Verify via slider-fill read-back:** the numeric field sits *inside* its slider row, and both slider endpoints are already calibrated (`size_1`/`size_32`, `opacity_0`/`opacity_1` — `ButtonConfiguration.java:29–34`). One row capture between the endpoints, scan for the fill edge (the bright-green fill vs the dark track is exactly what `clickSlider` already keys on, `BobRustPainter.java:317–319`), convert edge fraction → value, accept within ±0.3 size units / ±0.02 opacity. On mismatch: retry once, then `PaintingInterrupted(painted, InterruptType.FieldEntryFailed)`.

The verification matters for the same reason the old plan's edge case 8 did: an unfocused field means **keystrokes leak to the game** (chat, binds). One retry then hard abort; never soldier on.

Opacity is entered **once per run**, value `1` — replacing the old plan's `opacity_1`-endpoint click and killing its edge case 6 (calibrated endpoint ≠ true max) outright.

**Degraded path if the fields turn out not to be type-able** (open question 1): everything in this document still works with slider-clicked integer sizes exactly as PLAN-PIXEL-MODE §3 specified (fraction k/32 between `size_1`/`size_32`) — pitch reverts to the measured minimum footprint (3.125 default) and the exact-texel alignment win of §3.2 is lost, nothing else.

### 3.2 Brush-size model and pitch selection

The repo's measured brush data (`CircleCache.java:20–25`: painted diameters {3,6,12,25,50,100} texels at in-game sizes {1,2,4,8,16,32}; clicked at fraction `SIZES[i]/100` — `BobRustPalette.getSizeButton`, `BobRustPalette.java:130–139`) gives the working prior: **footprint(s) ≈ 3.125·s texels, linear through the origin** — for the *circle* brush. Palettized mode needs the *square* brush's `side(s) = a·s + b` over **continuous** s, which is new measurement territory:

- **Calibration procedure (extends the existing pattern loop):** `CalibrationPatternGenerator` (currently a 6×6 circle grid keyed to `BorstUtils.SIZES`, `CalibrationPatternGenerator.java:43–92`) grows a square-brush mode: one row of square stamps at SIZE values {1.00, 1.50, 2.00, 3.00, 4.00, 8.00, 16.00, 32.00} (typed via §3.1), painted on a fresh sign by the executor's dry-run harness; `ScreenshotAnalyzer` (grid transform + bounding-box measurement, `ScreenshotAnalyzer.java:101–260`) grows square-side measurement (its bbox measurement is shape-agnostic already) + corner-sharpness (its `computeShapeMatch` mask comparison, `:311–345`, pointed at a square mask instead of `CIRCLE_CACHE`). Output: fitted `a`, `b`, plus the minimum SIZE the field accepts (open question 2). Persisted as `SettingsSquareBrush` (hidden StringType, `a=3.125;b=0;minSize=1.0` default).
- **Pitch is now chosen, not inherited.** With a fractional base SIZE we pick the pitch to be an **exact texel count** that divides the sign dimensions: default **pitch 3.2 texels** → base SIZE = 3.2/a ≈ 1.024, XL grid exactly 160×160, every stamp side k an exact 3.2·k texels, every anchor on an exact texel boundary. This eliminates the old plan's systematic ±1-texel rounding drift (its §3 "accuracy floor") — only screen-space click rounding (< 1 screen px) remains. All built-in sign dimensions in `RustSigns` are multiples of 128 (`RustSigns.java:71–95`), and 128/3.2 = 40, so 3.2 tiles every frame exactly; the user-dimension custom sign (`bobrust.custom`, `RustSigns.java:58`) falls back to `ceil` with one partial edge cell — the same behavior every pitch had in the old plan. If calibration finds `b ≠ 0` or min SIZE > 1.024, the pitch generalizes to the smallest exact-divisor footprint ≥ the minimum — it is a persisted parameter, same as the old plan.
- **Resolution/economy trade:** measured (Part A), pitch 4.0 (128×128 on XL, base SIZE 1.28) cuts total actions ~20–35% (glyphs 19.5 k → 12.7 k). Exposed as a "Detail: Fine (3.2) / Economy (4.0)" toggle, default Fine. If the field accepts SIZE < 1 (open question 2), a finer pitch (e.g. 2.0 → 256×256) becomes available at ~2–3× the click cost — do not enable by default.

The tiler itself (`SquareTiler`, PLAN-PIXEL-MODE Step 3) is **unchanged**: it works in integer cell units with sides 1..32; only the executor's translation of "side k" changes — from a slider fraction to the typed value `k · baseSize`, formatted to 2 decimals (the field's displayed precision; 2-decimal rounding of k·1.024 errs ≤ 0.016 texels — noise).

### 3.3 Size-change pricing and the vocabulary question (measured)

A size change now costs ~6 input actions (§3.1: click + Ctrl+A + paste/3 keys + Enter) vs the old verified slider click. Per §8, size entries are 150–500 per image ⇒ 1–3 k actions, 10–27% of totals. Two mitigations were simulated (Part A):

- **Restricted side vocabulary** {1,2,4,8,16,32}: size entries drop ~55%, stamps rise ~5–19% — **a wash** (portrait 7.5 k → 7.3 k, photo 14.3 k → 14.1 k, glyphs 19.5 k → 19.6 k). Not adopted; the full 1..32 vocabulary stays (simpler, and strictly better if paste is available at 4 actions).
- **Grouping** stamps by side within each color (old plan §3 step 4) already minimizes entries — the counts above are post-grouping.

---

## 4. Square brush — already wired

`brush_square` coordinate exists (`ButtonConfiguration.java:26`), `getShapeButton(AppConstants.SQUARE_SHAPE=3)` resolves it (`BobRustPalette.java:141–147`), the automatic layout already estimates it (`BobRustPaletteGenerator.java:97`), and the painter clicks shape buttons through `clickSlider` (`BobRustPainter.java:155`). Confirmed: **only the Setup flow step (§2.2) is new.** The executor clicks it once during setup; the calibration pattern run (§3.2) verifies the footprint is actually square (old plan edge case 20: if small sizes render with rounded corners, bump the pitch — parameterized, unchanged).

---

## 5. Planner: quantize → snap → tile (carried, with one composition added)

Pipeline order (unchanged from PLAN-PIXEL-MODE §§1–3): place via `imageRect`/`canvasRect` → area-average scale to the cell grid (reusing `ImageUtil.getScaledInstance`'s rect mapping + bg compositing semantics, `ImageUtil.java:82–103`) → **Wu init + ~10 Lloyd iterations in Oklab** (deterministic, N respected, dither checkbox default off with its measured 2× cost) → **NEW: snap each of the N centroids to the picker's predicted-reachable set** via the fitted model (§2.4) — this is a pure function of `SettingsHsvPicker`, so preview == plan == paint — → greedy square cover per color in area-descending rank order with the painter's-algorithm relaxation → `PalettizedPaintPlan` (flattened `SetColor c / SetSize k / Stamp x,y` ops + resume cursor, mirroring `PaintPlan`'s discipline).

The **exactness invariant** carries over with its ground truth named precisely: *rendering the plan's ops onto a virtual grid yields bit-equality with the quantized label image colored by the **adopted** palette*; the adopted palette differs from the previewed (predicted) palette by the measured ≤ ~1 ΔE00 per entry (§2.4). The invariant test is unchanged (PLAN-PIXEL-MODE Step 3); the adopted-vs-predicted bound is the new `HsvClosedLoopTest`'s job (§11).

N default: **32** (owner's spec, 2026-07-15). N=40 costs +5–13% actions (§8); the estimate label makes the trade visible if the user raises N.

---

## 6. Executor: `PalettizedPainter`

A sibling of `BobRustPainter`, built on the same primitives **extracted, not copied**, into a package-private `RobotIO` (pure refactor of `clickPoint`/`clickSlider`/`addTimeDelay`/`checkMouseDisplacement`/screen-capture helpers plus the display-geometry preamble, `BobRustPainter.java:61–72, 198–323, 374–450`) — and `RobotIO` is an **interface** with the AWT implementation and a test fake (§11.5). Run loop:

```
focus click ×4                                (as today, BobRustPainter.java:103)
clearCanvas click                             (if "clear first", default on)
brush_square click (clickSlider-verified)
FieldInput.enter(opacityField, "1")           (verified, §3.1)
HsvColorPicker.probe()                        (~27 clicks; aborts pre-paint on any gate, §2.3)
for each color c in rank order:
    ColorEntryController.enter(palette[c])    (≤6 reads; adopt or abort, §2.5)
    for each side group k (desc):
        FieldInput.enter(sizeField, fmt(k·baseSize))   (verified, §3.1)
        for each stamp: paced click at stamp center
            · plan-aware sparse verification: capture only stamps whose center
              cell the plan predicts CHANGES color, at the SettingsClickVerifyInterval
              cadence (carries over PLAN-PIXEL-MODE §4 + edge case 11)
    autosave (saveImage) every SettingsAutosaveInterval stamps
final saveImage click ×4
throw PaintingInterrupted(…, PaintingFinished)          (same contract, BobRustPainter.java:188)
```

- **Stamp→screen mapping:** cell (cx,cy), side k → texel center ((cx+k/2)·3.2, (cy+k/2)·3.2) → the exact canvas math of `BobRustPainter.startDrawing` (`BobRustPainter.java:160–170`).
- **Pacing:** identical envelope — `autoDelay = 1000/(clickInterval·3)` (`BobRustPainter.java:76–77`), keystrokes scheduled through the same `addTimeDelay` stream, autosave cadence unchanged, mouse-displacement abort unchanged (`MAXIMUM_DISPLACEMENT=10`, `:25`). No new automation signature; palettized runs are the same length or shorter than SA runs.
- **Interrupts:** `InterruptType` (`PaintingInterrupted.java:25–40`) grows `ColorEntryFailed` and `FieldEntryFailed` (replacing the planned `HexEntryFailed`). Both carry the stamp cursor like `MouseMoved`/`ThreadInterrupted`.
- **Resume:** `PalettizedPaintPlan` cursor discipline exactly as PLAN-PIXEL-MODE §6 — on resume re-run setup (including probe), re-issue the `SetColor`+`SetSize` governing the cursor, continue; opaque stamps are idempotent. The adopted palette from the interrupted run is kept (it is the plan's ground truth); re-entry converges to the same reachable colors by construction.
- **Dry-run mode:** the `ALLOW_PRESSES` pattern (`BobRustPainter.java:26`) generalizes — `RobotIO` fake logs/overlays planned clicks for in-game rehearsal without presses.

---

## 7. UI / settings / estimator

- **Mode selector in `DrawDialog`** (per-draw decision, sits above the preset row): two-button toggle **"Brush (palette)" / "Palettized (pixel)"** → `Settings.SettingsDrawingMode` (`EnumType<DrawingMode>`; auto-persisted via the settings framework, `Settings.java:26–142`). Palettized hides the SA-specific controls (preset ladder `DrawDialog.java:127–147`, stencil checkbox `:152–158`, shape slider) and shows:
  - **Colors (N):** `JIntegerField` + slider, 2..64, default **32** → `SettingsPalettizedColors` (IntType).
  - **Detail:** Fine (pitch 3.2) / Economy (4.0) → `SettingsPalettizedPitch`.
  - **Dither** (default off), **Clear canvas first** (default on), **Skip sign-colored cells** (default off, advanced) — carried from PLAN-PIXEL-MODE §5 with their measured tooltips.
  - **Live estimate** via the existing debounced `SwingWorker` pattern (`estimateDebounce`/`runEstimate`, `DrawDialog.java:269, 410–475`): `PalettizedTimeEstimator` mirrors `PaintTimeEstimator`'s linear form — `actions·(1000/cps) + captures·captureMs` with `actions = stamps + 6·sizeEntries + (2+2.2)·N + probe(54) + setup + autosaves` and `captures = 3.2·N + sizeEntries + predictedStampVerifies + 27`, reusing the calibrated `Settings.getCaptureMs()` (`Settings.java:165–175`). Detail line: "N colors · M stamps · K size entries".
  - **Preview:** `ScreenDrawDialog.paintComponent` (`ScreenDrawDialog.java:105–141`) branches on mode and draws the quantized-and-snapped cell image nearest-neighbor into `canvasRect` — the preview *is* the promised result (±the §2.4 adoption bound).
- **Presets stay SA-only** (unchanged rationale from PLAN-PIXEL-MODE §5): palettized reads `SettingsClickInterval`/`SettingsClickVerifyInterval` as-is; `applyPreset`/`syncPresetFromSettings` (`DrawDialog.java:304–386`) untouched; `PaintPresetTest` unaffected.
- **Draw-button gate:** all §2.1 coordinates valid + palette-mode screenshot sanity replaced by the probe gates (§2.3). The 64-swatch scan (`findColorPalette`, `DrawDialog.java:615–637`) is skipped in palettized mode.

---

## 8. Click/time budgets (measured — `palettized_sim.py` Part A)

Pricing: stamps 1 action; size entry 6; color entry 2 clicks + 2.2 measured nudges; setup 67 (incl. the 54-action probe); autosave/1000; 30 cps (`SettingsClickInterval` default, `Settings.java:82`). XL 512×512, pitch 3.2 (160×160 = 25,600 cells), full side vocabulary:

| image | **N=32 (default)** | N=40 | N=64 |
|---|---|---|---|
| portrait (smooth photo) | **6.9 k · 3.8 min** | 7.5 k · 4.2 min | 10.2 k · 5.6 min |
| skyline | **10.1 k · 5.6 min** | 10.7 k · 5.9 min | 12.8 k · 7.1 min |
| photo_detail | **12.6 k · 7.0 min** | 14.3 k · 7.9 min | 18.1 k · 10.1 min |
| glyphs (dense text) | **18.5 k · 10.3 min** | 19.5 k · 10.9 min | 20.6 k · 11.4 min |
| texture (worst-case noise) | **19.1 k · 10.6 min** | 20.3 k · 11.3 min | 22.8 k · 12.7 min |

Other frames at N=40: landscape 256×128 ≈ **2.6–3.2 k · ~1.5–1.8 min**; XXL 1024×512 ≈ **11.0–16.7 k · 6.1–9.3 min** (photo-class). Pitch 4.0 economy: portrait 6.0 k · 3.4 min, glyphs 12.7 k · 7.1 min. Comparison to the invalidated hex design (its §3 table, N=32, pitch 3.125): totals within ±5% at equal N — the HSV entry + field-entry overheads and the hex overhead cancel to first order; the mode's cost story is unchanged, and **"~15 min max on XL" holds** for everything except full-canvas noise at N=64.

Color-entry overhead specifically: ~4.2 actions + ~3.2 captures per color = **~170 actions + ~130 captures ≈ 7 s at N=40** — noise. Size entries: 150–500 per image (10–27% of actions) — the §3.3 analysis says leave as-is.

---

## 9. Edge cases — changed and new rows only

PLAN-PIXEL-MODE's table rows 1–7, 11–18, 20 carry over verbatim (with "hex entry" → "color entry" in 12 and 18). Replaced/new:

| # | Case | Risk | Handling |
|---|---|---|---|
| 8′ | Picker toggled to the legacy palette grid (or a patch changes picker layout) | Clicks land on grid swatches / nothing; colors arbitrary | Probe gates (§2.3) fail closed **before painting**: fit residual/hue-span checks catch both. Setup text instructs the correct toggle state. Optional future: calibrate the toggle button and switch automatically — not in v1 (unknown toggle semantics). |
| 9′ | Global screen color transform (night light, HDR, colorblind filter) | Absolute swatch readback systematically wrong → loop never converges | Probe reference-color gate (§2.3) detects and refuses with a specific message. This mode is the first to *need* absolute readback — the legacy painter only ever diffed. |
| 10′ | SIZE/OPACITY field click lands on the slider instead of the text (readout may share the slider hitbox) | One stray click sets a random size before typing corrects it | The typed value replaces whatever the click did (Ctrl+A + type + Enter), and the slider-fill readback (§3.1) verifies the final value. Residual risk only if the field is not type-able at all → degraded path §3.1, owner question 1. |
| 19′ | Keystrokes leak to game (field lost focus mid-entry) | Chat/bind activation | Same posture as old 8/9: paste-first (one chord), verified entry, retry once, `FieldEntryFailed` hard abort. Focus click precedes every entry. |
| 21 | Locale decimal separator (comma locales) for fractional SIZE | Field rejects "1.02" or parses "102" | Paste path sends the exact string; typed fallback has a `.`/`,` setting; slider-fill verification catches a wrong parse before any stamp. Integer-only sizes (pitch 3.125·k) as last resort. |
| 22 | Hue marker triangles / SV crosshair overlap the click target | Cosmetic overlays swallow clicks? | UI overlays don't intercept picker clicks (they're drawn atop the same control); if a specific position misbehaves, the nudge loop routes around it (±1 px steps); worst case the color aborts cleanly. |
| 23 | SPACING field affects single-click stamps | Footprint depends on a control we don't set | Screenshot shows SPACING 0.01 (dense stroke spacing); single click-stamps shouldn't consult it. The square-footprint calibration (§3.2) is run with SPACING untouched and would expose any effect; if real, set SPACING once in setup like opacity. |
| 24 | Swatch not perfectly flat on some setup (overlay/gradient) | Median readback biased | Probe pass measures readback consistency across 27 known clicks — variance gate folds into the residual check. Fallback: shrink the sampled inset further; last resort hex-OCR (§2.6). |

---

## 10. Implementation plan

Ordered; each step independently buildable + testable. Effort: S (≤ ~150 LoC), M (~150–500), L (500+).

**Step 1 — Plumbing: coordinates + settings + Setup flow.** *(S–M, low risk)*
`ButtonConfiguration` ×8 new coords + `update()` lines; `Settings`: `SettingsDrawingMode`, `SettingsPalettizedColors` (2..64, default 32), `SettingsPalettizedPitch`, `SettingsPalettizedDither/ClearFirst/SkipBase`, `SettingsSquareBrush`, `SettingsHsvPicker` (hidden StringTypes); `ApplicationWindow.setupButtonRegions` append 3 arrow steps + 3 rect steps (§2.2). Tests: `ButtonConfigurationTest` round-trip incl. missing-field back-compat.

**Step 2 — Quantizer.** *(M — carried verbatim from PLAN-PIXEL-MODE Step 2: `generator/quant/{Oklab,WuQuantizer,PixelQuantizer}`, `util/PixelImageScaler`; only the N default changes.)*

**Step 3 — Tiler + plan.** *(M — carried verbatim from PLAN-PIXEL-MODE Step 3: `generator/tiler/{PixelStamp,SquareTiler,PalettizedPaintPlan}` + the exactness-invariant test suite and stamp-count regression pins, re-pinned to this doc's §8 counts.)*

**Step 4 — HSV picker model + color-entry controller (pure logic).** *(M — the novel core, zero Robot risk)*
`robot/hsv/HsvPickerModel` (per-axis linear fit, forward/inverse, reachable-snap), `robot/hsv/ColorEntryController` (the §2.5 loop as a state machine over an injected `Sensor` interface: `click(Point)`, `readSwatch()`), `robot/hsv/ProbePlanner` (§2.3 sequence + gates). Tests: `HsvClosedLoopTest` — a simulated picker (the `palettized_sim.py` Part B model ported: integer-px clicks, 8-bit rounding, injected offset/scale error), asserting the Part E numbers: probe fit residual < 1 px, ≤ 6 reads, max ΔE00 ≤ 1.0 vs centroids at 205 px (via the existing `util/metrics/Ciede2000`), abort fires on an unconvergeable sensor (stuck swatch), never > 2·MAX_READS clicks, all clicks in-rect. Deterministic.

**Step 5 — RobotIO extraction + FieldInput.** *(M, pure-refactor risk)*
Extract the shared primitives from `BobRustPainter` behind `RobotIO` (interface + AWT impl; byte-identical behavior for the legacy path — verified by the existing painter tests still passing). `robot/FieldInput` (§3.1: paste/typed entry + slider-fill readback parser). Tests: keystroke-sequence encoder unit tests; fill-edge parser on synthetic slider-row images.

**Step 6 — Executor.** *(M–L, highest integration risk)*
`robot/PalettizedPainter` (§6 run loop) over `RobotIO` + Steps 4–5 components; `InterruptType.{ColorEntryFailed,FieldEntryFailed}`; plan-aware sparse verification; dry-run mode. Tests: the §11.5 offline end-to-end harness (this step's real acceptance test) + coordinate-mapping pure-function tests.

**Step 7 — UI integration + estimator.** *(M)*
`DrawDialog` mode toggle + palettized control set + estimate wiring; `ScreenDrawDialog` preview branch; `util/PalettizedTimeEstimator` (+ closed-form unit tests against hand-built plans, mirroring `PaintTimeEstimatorTest`).

**Step 8 — Square-brush + picker field calibration tooling.** *(M, independent after Steps 1+5; defaults apply until run)*
`CalibrationPatternGenerator` square/continuous-size row + `ScreenshotAnalyzer` square measurement (§3.2); an in-app "measure brush" helper that paints the pattern via the dry-run-capable executor. Output → `SettingsSquareBrush`.

**Step 9 — Benchmark + docs.** *(S)*
`PalettizedSweepTest` (`@Tag("benchmark")`, style of `ClickBudgetSweepTest`): regenerates §8's tables in Java — the living regression harness. `docs/PALETTIZED-MODE.md` condensed user docs + calibration instructions.

Dependency order: 1 ∥ 2 → 3 → 4 ∥ 5 → 6 → 7; 8 after 1+5; 9 last. Steps 2–4 are pure JVM with deterministic tests — the entire planner *and* the entire color-entry brain exist and are validated before any Robot code runs.

---

## 11. Test plan

What runs offline (JUnit, deterministic, CI-able) vs what needs a live Rust session (owner tests those — each live item is a *parameter measurement*, never a correctness gate):

**11.1 Click/time-budget simulation.** Done in Python (Part A, §8) and re-implemented as `PalettizedSweepTest` (Step 9) over `src/test/resources/test-images{,-complex}/` — commits §8's counts as regression pins so tiler/pricing changes surface as diffs. *Offline.*

**11.2 Color-accuracy / closed-loop convergence.** `HsvClosedLoopTest` (Step 4): simulated picker with injected calibration error (offset ±3 px, scale ±2%, both orientations of both axes), real corpus palettes at N=40; asserts (i) probe fit recovers geometry to < 1 px, (ii) every color adopted within ΔE00 ≤ 1.0 of its centroid at a 205 px picker (the Part E bound + margin), (iii) reads ≤ 6, clicks bounded and in-rect, (iv) unreachable/stuck sensor → `ColorEntryFailed` after exactly the budgeted attempts, (v) online refit shrinks reads on a sequential 40-color run. *Offline.* (The Python Parts B–E are the design evidence; the Java test is the permanent guard.)

**11.3 Calibration round-trip.** Extends the `CalibrationRoundTripTest` pattern (`src/test/java/com/bobrust/calibration/CalibrationRoundTripTest.java`): generate the square pattern → analyze the exact image → measured sides within ±2 px, corner match ≥ threshold, at 1× and 2× scale (mirroring `scaledPatternDetection`). New `HsvProbeFitTest`: render a synthetic picker *image* (ideal HSV field), back the probe's `Sensor` with image sampling, assert the fitted model reproduces the render geometry. *Offline.*

**11.4 Tiler exactness invariant.** Unchanged from PLAN-PIXEL-MODE Step 3: render the plan's ops onto a sentinel-initialized grid → bit-equality with the quantized labels (whole corpus + randomized grids); no stamp of rank k touches rank < k; sides ≤ 32; solid-canvas = ceil(160/32)² = 25 stamps; stray-cell masks. *Offline.*

**11.5 End-to-end offline dry-run.** `FakeGame implements RobotIO + Sensor`: virtual picker (11.2's model), virtual SIZE/OPACITY fields (with keystroke parsing, so `FieldInput`'s actual key sequences are exercised — including a "focus lost" fault that swallows keys), virtual canvas that stamps `side(s)=a·s+b` squares at click positions through the *real* screen-mapping math. Run `PalettizedPainter` end-to-end on corpus images; assert: final virtual canvas == adopted-palette render **byte-exact** (the mode's headline promise); autosave cadence; probe-gate aborts (picker in "grid mode" fake → pre-paint abort); focus-loss fault → `FieldEntryFailed` with zero subsequent canvas clicks; resume from a mid-run interrupt completes to the same byte-exact canvas. *Offline — this is the highest-leverage test in the plan.*

**11.6 Unit tests.** Oklab round-trip + quantizer determinism (Step 2, carried); HSV↔px inversion incl. flipped orientations; nudge-step clamping; slider-fill parser; estimator closed forms; `PaintingInterrupted` cursor propagation.

**Live (owner, one short session each):**
1. Field behavior: click-to-focus? paste? Ctrl+A? decimal format? min/max SIZE? (§12 Q1–Q2) — selects code paths, all fallbacks designed.
2. Square-footprint pattern paint + screenshot → `SettingsSquareBrush` (a, b, min; §3.2).
3. Probe-pass smoke test on a real sign: gates pass, 40 colors adopt, log the reads/color histogram (expect ≤ 4).
4. One full XL paint of the portrait corpus image; compare wall time to §8 and the saved sign to the preview.

---

## 12. Open questions for the owner

1. **Numeric fields — RESOLVED (owner, 2026-07-15):** clicking the SIZE/OPACITY readout focuses it **and selects the current number**; then paste or type the new value and press **Enter** (or click away) to submit. Field entry = click → paste/type → Enter; **no Ctrl+A needed** (§3.1 updated). Fields ARE type-able → the exact-texel path is live, not the degraded slider fallback.
2. **SIZE field range:** minimum accepted value (is < 1.00 legal?) and max (32?). Sets the attainable pitch: min 1.00 → pitch 3.2 (160×160 XL); min 0.5 would unlock ~2.0 (256×256) at ~2–3× clicks — measure, don't enable by default. *(Still open — one in-game check.)*
3. **One click = set? — RESOLVED (owner):** yes, a single click on the hue bar / SV square sets it (no drag). `RobotIO` uses plain clicks; no press-move-release primitive needed.
4. **N default — RESOLVED (owner): 32.** Default reverted 40 → 32 throughout; the estimate label still shows the live cost if the user raises N.
5. **Detail default:** Fine (pitch 3.2) vs Economy (4.0) — Economy is 20–35% cheaper and visibly blockier. Recommend Fine as default, Economy exposed. *(Still open — recommendation stands unless owner prefers Economy.)*
6. **COLOUR panel toggle — RESOLVED (owner):** the game has BOTH the old 64-swatch grid AND the HSV picker, with a user toggle between them; the legacy grid is **NOT gone**, so the existing SA/brush mode still works on live Rust. For now the user **manually toggles** the COLOUR panel to the HSV picker before a palettized paint (auto-toggle deferred as a later nicety) — the Setup instructions must call this out.
7. **Dither default off / letterbox fill color / "pin color" feature** — carried unchanged from PLAN-PIXEL-MODE open questions 1, 2, 4.
8. **SPACING:** confirm it does not affect single-click stamps (§9 #23) — one test stamp at SPACING 0.01 vs 1.0.

---

*Numbers provenance: click/action/time figures and all ΔE00/convergence statistics measured by `palettized_sim.py` (this directory) against `src/test/resources/test-images{,-complex}/` — median-cut+kmeans quantizer stand-in, greedy cover per §5, pitch 3.2, pricing per §8, picker model per §1's screenshot measurements. Re-run: `python3 palettized_sim.py` (Parts A–B), `… refit` (C), `… probe` (D), `… accept` (E). Screenshot geometry measured from `docs/rust-hsv-color-picker.png`.*
