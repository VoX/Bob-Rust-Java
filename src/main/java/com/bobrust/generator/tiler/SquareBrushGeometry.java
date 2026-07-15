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
	public static final SquareBrushGeometry DEFAULT = new SquareBrushGeometry(3.125, 0.0, 1.0);

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
