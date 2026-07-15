package com.bobrust.generator.quant;

/**
 * sRGB ↔ Oklab conversion (Björn Ottosson, 2020). Squared Euclidean distance
 * in Oklab is the perceptual ΔE proxy the palettized quantizer clusters in —
 * CIEDE2000 has no closed-form centroid and would break Lloyd's convergence
 * (see {@link com.bobrust.util.metrics.Ciede2000} for the evaluation-side
 * metric).
 */
public final class Oklab {
	private Oklab() {
	}

	/** Converts an sRGB pixel (alpha ignored) to {@code {L, a, b}}. */
	public static double[] fromRgb(int rgb) {
		return fromSrgb(
			((rgb >>> 16) & 0xff) / 255.0,
			((rgb >>>  8) & 0xff) / 255.0,
			((rgb       ) & 0xff) / 255.0
		);
	}

	/** Converts sRGB components in {@code [0, 1]} to {@code {L, a, b}}. */
	public static double[] fromSrgb(double r, double g, double b) {
		double lr = srgbToLinear(r);
		double lg = srgbToLinear(g);
		double lb = srgbToLinear(b);

		double l = 0.4122214708 * lr + 0.5363325363 * lg + 0.0514459929 * lb;
		double m = 0.2119034982 * lr + 0.6806995451 * lg + 0.1073969566 * lb;
		double s = 0.0883024619 * lr + 0.2817188376 * lg + 0.6299787005 * lb;

		double l_ = Math.cbrt(l);
		double m_ = Math.cbrt(m);
		double s_ = Math.cbrt(s);

		return new double[] {
			0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_,
			1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_,
			0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_
		};
	}

	/** Converts an Oklab color to an opaque 8-bit sRGB pixel (clamped). */
	public static int toRgb(double L, double a, double b) {
		double l_ = L + 0.3963377774 * a + 0.2158037573 * b;
		double m_ = L - 0.1055613458 * a - 0.0638541728 * b;
		double s_ = L - 0.0894841775 * a - 1.2914855480 * b;

		double l = l_ * l_ * l_;
		double m = m_ * m_ * m_;
		double s = s_ * s_ * s_;

		double lr =  4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
		double lg = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
		double lb = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s;

		int r = to8Bit(linearToSrgb(lr));
		int g = to8Bit(linearToSrgb(lg));
		int bl = to8Bit(linearToSrgb(lb));
		return 0xff000000 | (r << 16) | (g << 8) | bl;
	}

	private static int to8Bit(double c) {
		int v = (int) Math.round(c * 255.0);
		return v < 0 ? 0 : Math.min(v, 255);
	}

	private static double srgbToLinear(double c) {
		return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}

	private static double linearToSrgb(double c) {
		if (c <= 0) {
			return 0;
		}
		return c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055;
	}
}
