# Palettized Mode

Palettized mode paints an image as N exact colors (default 32, range 2..64):
the image is resized to the brush's virtual cell grid, quantized (Wu + Oklab
Lloyd), covered with square stamps per color (largest colors first, painter's
algorithm), and painted **fully opaque** with the **square brush**. Colors are
entered through Rust's **HSV color picker** (hue-bar click + SV-square click,
verified against the color-preview swatch), and brush SIZE/OPACITY are
**typed into the numeric fields**. The on-screen preview is exactly what gets
painted.

Full design + measurements: `PLAN-PALETTIZED-MODE.md` (and
`PLAN-PIXEL-MODE.md` for the planner core).

## Setup (once per install / UI change)

1. Run **Setup Buttons**. After the classic steps it now also asks for:
   - the **Square Brush** button, the **SIZE number field**, and the
     **OPACITY number field** (arrow markers);
   - the **Saturation/Value square**, the **Hue bar**, and the **color
     preview swatch** (drag rectangles). A few pixels of slack is fine — an
     automatic probe pass refines the mapping at the start of every paint.
2. **Toggle the COLOUR panel to the HSV picker** (not the legacy 64-swatch
   grid) before painting. The probe pass detects the wrong state and refuses
   cleanly.
3. Disable any global screen color transform (night light, HDR tone mapping,
   colorblind filters) — palettized mode reads absolute colors off the
   screen and the probe will refuse if the reference color reads back wrong.

## Painting

1. In the draw dialog choose **Palettized (pixel)** in the mode row.
2. Pick **Colors** (N), **Detail** (Fine = pitch 3.2, Economy = 4.0 — ~20-35%
   fewer clicks, blockier), optional **Dither** (≈2× stamps on smooth
   images), **Clear canvas first** (recommended), **Skip sign-colored cells**
   (advanced; only for freshly placed signs).
3. The preview *is* the promised result; the estimate line shows
   `colors · stamps · size entries` and the predicted paint time.
4. Press **Select Color Palette And Draw**. The run starts with a ~30-click
   probe of the picker; every color entry is closed-loop verified against
   the swatch. A failed color or field entry aborts cleanly — nothing is
   ever painted with an unverified tool state. Painting resumes from the
   cursor if interrupted (move the mouse to abort, as always).

## Square-brush calibration (recommended once)

The footprint model defaults to the circle-brush measurements
(`side ≈ 3.125 · SIZE` texels). To measure the square brush's true `a·s + b`:

1. On a fresh sign, paint one row of single square stamps at SIZE values
   `1.00, 1.50, 2.00, 3.00, 4.00, 8.00, 16.00, 32.00` (type each into the
   SIZE field), left to right, well separated.
2. Screenshot the sign, crop to the painted area, note
   `texelsPerPixel = sign texel width / crop pixel width`.
3. Run `java -cp <classes> com.bobrust.calibration.SquareBrushCalibration
   screenshot.png <texelsPerPixel>` and put the printed
   `a=…;b=…;minSize=…` string into the config as `SettingsSquareBrush`.

The tool also warns when stamps aren't sharp squares (rounded corners at
small sizes → pick Economy detail).

## Live checks the code can't do offline (one short session)

- SIZE field: minimum accepted value (is `< 1.00` legal?) and maximum
  (`> 32`?). The planner conservatively caps stamp sides so SIZE stays ≤ 32.
- SPACING: confirm it doesn't affect single-click stamps.
- One probe-pass smoke test on a real sign, then a full XL paint compared
  against the preview.

## Benchmarks

`./gradlew benchmark --tests com.bobrust.benchmark.PalettizedSweepTest`
regenerates the click/time tables over the test corpus (the living version
of the design doc's §8).
