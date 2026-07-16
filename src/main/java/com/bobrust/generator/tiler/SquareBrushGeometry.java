package com.bobrust.generator.tiler;

import java.util.Locale;

/**
 * The square brush's measured footprint model (PLAN-PALETTIZED-MODE.md §3.2):
 * the painted side in sign texels at in-game SIZE {@code s} is
 * {@code a·s + b}. Defaults derive from the repo's measured circle-brush data
 * (diameter ≈ 3.125·s, {@link com.bobrust.generator.CircleCache}); the
 * square-brush calibration pattern overwrites them when run.
 *
 * <p>Serialized as {@code a=3.125;b=0.0;minSize=1.0} in
 * {@code Settings.SettingsSquareBrush}.
 */
public record SquareBrushGeometry(double a, double b, double minSize) {
	// The SQUARE brush's measured footprint (owner in-game test 2026-07-16): SIZE 1 ≈ 1 sign
	// texel, i.e. side ≈ 1.0·SIZE. This is very different from the circle brush's 3.125·s
	// diameter the design originally borrowed — the square is far finer, so an XL sign can be
	// painted near its native 512×512 instead of 160×160. The square-brush calibration pattern
	// refines a/b precisely; 1.0/0.0 is the field-measured default.
	public static final SquareBrushGeometry DEFAULT = new SquareBrushGeometry(1.0, 0.0, 1.0);

	/**
	 * The SIZE-field value whose footprint is {@code sideCells} cells of
	 * {@code pitch} texels: solves {@code a·s + b = sideCells·pitch}.
	 */
	public double sizeFor(int sideCells, double pitch) {
		return (sideCells * pitch - b) / a;
	}

	/**
	 * Formats a SIZE value the way it is typed into the game's field — two
	 * decimals, the field's displayed precision (rounding error ≤ 0.016
	 * texels at pitch 3.2: noise).
	 */
	public static String formatSize(double size) {
		return String.format(Locale.ROOT, "%.2f", size);
	}

	/**
	 * True when the base (side 1) size at {@code pitch} is actually reachable
	 * given the field's minimum accepted SIZE.
	 */
	public boolean supportsPitch(double pitch) {
		return sizeFor(1, pitch) >= minSize;
	}

	/**
	 * The largest stamp side (in cells) whose SIZE stays within the game's
	 * 1..32 slider range at this pitch: {@code floor((a·32 + b)/pitch)},
	 * capped at {@link SquareTiler#MAX_SIDE}. With the default circle-derived
	 * geometry at pitch 3.2 this is 31 — a side-32 stamp would need SIZE
	 * 32.77, beyond the slider (open question 2; conservative until the
	 * field's true max is measured).
	 */
	public int maxSideCells(double pitch) {
		int side = (int) Math.floor((a * 32.0 + b) / pitch + 1e-9);
		return Math.max(1, Math.min(SquareTiler.MAX_SIDE, side));
	}

	public String serialize() {
		return String.format(Locale.ROOT, "a=%s;b=%s;minSize=%s", a, b, minSize);
	}

	/**
	 * Parses the serialized form; unknown keys are ignored, missing keys fall
	 * back per key, and any malformed input returns {@link #DEFAULT}.
	 */
	public static SquareBrushGeometry parse(String text) {
		if (text == null || text.isBlank()) {
			return DEFAULT;
		}
		double a = DEFAULT.a();
		double b = DEFAULT.b();
		double minSize = DEFAULT.minSize();
		try {
			for (String part : text.split(";")) {
				String[] pair = part.split("=", 2);
				if (pair.length != 2) {
					continue;
				}
				double value = Double.parseDouble(pair[1].trim());
				switch (pair[0].trim()) {
					case "a" -> a = value;
					case "b" -> b = value;
					case "minSize" -> minSize = value;
				}
			}
		} catch (NumberFormatException ignored) {
			return DEFAULT;
		}
		if (a <= 0) {
			return DEFAULT;
		}
		return new SquareBrushGeometry(a, b, minSize);
	}
}
