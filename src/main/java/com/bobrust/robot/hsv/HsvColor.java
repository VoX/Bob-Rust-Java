package com.bobrust.robot.hsv;

/**
 * RGB ↔ HSV conversions with the exact rounding semantics the picker math
 * depends on: {@code hsvToRgb} rounds each channel with
 * {@code Math.round(255·c)} — the same quantization the game applies when it
 * renders the swatch (measured in PLAN-PALETTIZED-MODE.md §1), and the same
 * as {@code java.awt.Color.HSBtoRGB}. Hue, saturation and value are all
 * fractions in {@code [0, 1]} (hue 1.0 ≡ 0.0 = red).
 */
public final class HsvColor {
	private HsvColor() {
	}

	/** Converts an sRGB pixel (alpha ignored) to {@code {h, s, v}} fractions. */
	public static double[] rgbToHsv(int rgb) {
		int r = (rgb >>> 16) & 0xff;
		int g = (rgb >>>  8) & 0xff;
		int b = (rgb       ) & 0xff;

		int max = Math.max(r, Math.max(g, b));
		int min = Math.min(r, Math.min(g, b));
		double v = max / 255.0;
		double s = max == 0 ? 0 : (max - min) / (double) max;

		double h;
		if (max == min) {
			h = 0;
		} else {
			double delta = max - min;
			if (max == r) {
				h = ((g - b) / delta) / 6.0;
			} else if (max == g) {
				h = (2.0 + (b - r) / delta) / 6.0;
			} else {
				h = (4.0 + (r - g) / delta) / 6.0;
			}
			if (h < 0) {
				h += 1.0;
			}
		}
		return new double[] { h, s, v };
	}

	/** Converts HSV fractions to an opaque sRGB pixel ({@code round(255·c)} per channel). */
	public static int hsvToRgb(double h, double s, double v) {
		h = h - Math.floor(h); // wrap into [0, 1)
		double r, g, b;
		if (s <= 0) {
			r = g = b = v;
		} else {
			double h6 = h * 6.0;
			int i = (int) Math.floor(h6) % 6;
			double f = h6 - Math.floor(h6);
			double p = v * (1.0 - s);
			double q = v * (1.0 - s * f);
			double t = v * (1.0 - s * (1.0 - f));
			switch (i) {
				case 0 -> { r = v; g = t; b = p; }
				case 1 -> { r = q; g = v; b = p; }
				case 2 -> { r = p; g = v; b = t; }
				case 3 -> { r = p; g = q; b = v; }
				case 4 -> { r = t; g = p; b = v; }
				default -> { r = v; g = p; b = q; }
			}
		}
		return 0xff000000
			| ((int) Math.round(r * 255.0) << 16)
			| ((int) Math.round(g * 255.0) << 8)
			| (int) Math.round(b * 255.0);
	}

	/** Wraps a hue-fraction difference into {@code [-0.5, 0.5]}. */
	public static double wrapHue(double delta) {
		delta = delta - Math.floor(delta);
		return delta > 0.5 ? delta - 1.0 : delta;
	}
}
