package com.bobrust.util.metrics;

/**
 * CIEDE2000 (ΔE00) color difference, computed in a perceptual space:
 * sRGB → linear RGB → XYZ (D65) → CIELAB, then the CIEDE2000 formula with
 * parametric factors kL = kC = kH = 1.
 *
 * <p>Implementation follows Sharma, Wu &amp; Dalal, "The CIEDE2000
 * Color-Difference Formula: Implementation Notes, Supplementary Test Data,
 * and Mathematical Observations" (2005); {@code Ciede2000Test} pins the
 * paper's published test pairs, including the hue-discontinuity cases.
 */
public final class Ciede2000 {
	private static final double POW25_7 = 6103515625.0; // 25^7

	// D65 reference white
	private static final double XN = 0.95047;
	private static final double YN = 1.00000;
	private static final double ZN = 1.08883;

	private Ciede2000() {
	}

	/**
	 * Converts an sRGB pixel ({@code 0xAARRGGBB} or {@code 0xRRGGBB}; alpha is
	 * ignored) to CIELAB under D65, returning {@code {L, a, b}}.
	 */
	public static double[] rgbToLab(int rgb) {
		double r = srgbToLinear(((rgb >>> 16) & 0xff) / 255.0);
		double g = srgbToLinear(((rgb >>>  8) & 0xff) / 255.0);
		double b = srgbToLinear(((rgb       ) & 0xff) / 255.0);

		// Linear sRGB → XYZ (D65)
		double x = 0.4124564 * r + 0.3575761 * g + 0.1804375 * b;
		double y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
		double z = 0.0193339 * r + 0.1191920 * g + 0.9503041 * b;

		double fx = labF(x / XN);
		double fy = labF(y / YN);
		double fz = labF(z / ZN);

		return new double[] {
			116.0 * fy - 16.0,
			500.0 * (fx - fy),
			200.0 * (fy - fz)
		};
	}

	private static double srgbToLinear(double c) {
		return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}

	private static double labF(double t) {
		final double delta = 6.0 / 29.0;
		return t > delta * delta * delta
			? Math.cbrt(t)
			: t / (3.0 * delta * delta) + 4.0 / 29.0;
	}

	/** ΔE00 between two sRGB pixels (alpha ignored). */
	public static double deltaE(int rgb1, int rgb2) {
		if (rgb1 == rgb2) {
			return 0;
		}
		double[] lab1 = rgbToLab(rgb1);
		double[] lab2 = rgbToLab(rgb2);
		return deltaE(lab1[0], lab1[1], lab1[2], lab2[0], lab2[1], lab2[2]);
	}

	/** ΔE00 between two CIELAB colors (kL = kC = kH = 1). */
	public static double deltaE(double l1, double a1, double b1, double l2, double a2, double b2) {
		double c1 = Math.sqrt(a1 * a1 + b1 * b1);
		double c2 = Math.sqrt(a2 * a2 + b2 * b2);
		double cBar = (c1 + c2) * 0.5;

		double cBar7 = pow7(cBar);
		double g = 0.5 * (1.0 - Math.sqrt(cBar7 / (cBar7 + POW25_7)));

		double a1p = (1.0 + g) * a1;
		double a2p = (1.0 + g) * a2;
		double c1p = Math.sqrt(a1p * a1p + b1 * b1);
		double c2p = Math.sqrt(a2p * a2p + b2 * b2);

		double h1p = hueAngle(a1p, b1);
		double h2p = hueAngle(a2p, b2);

		double dLp = l2 - l1;
		double dCp = c2p - c1p;

		double dhp;
		if (c1p * c2p == 0) {
			dhp = 0;
		} else {
			dhp = h2p - h1p;
			if (dhp > 180) {
				dhp -= 360;
			} else if (dhp < -180) {
				dhp += 360;
			}
		}
		double dHp = 2.0 * Math.sqrt(c1p * c2p) * Math.sin(Math.toRadians(dhp * 0.5));

		double lBarP = (l1 + l2) * 0.5;
		double cBarP = (c1p + c2p) * 0.5;

		double hBarP;
		if (c1p * c2p == 0) {
			hBarP = h1p + h2p;
		} else {
			hBarP = (h1p + h2p) * 0.5;
			if (Math.abs(h1p - h2p) > 180) {
				hBarP += (h1p + h2p < 360) ? 180 : -180;
			}
		}

		double t = 1.0
			- 0.17 * Math.cos(Math.toRadians(hBarP - 30))
			+ 0.24 * Math.cos(Math.toRadians(2 * hBarP))
			+ 0.32 * Math.cos(Math.toRadians(3 * hBarP + 6))
			- 0.20 * Math.cos(Math.toRadians(4 * hBarP - 63));

		double dTheta = 30.0 * Math.exp(-square((hBarP - 275) / 25));
		double cBarP7 = pow7(cBarP);
		double rc = 2.0 * Math.sqrt(cBarP7 / (cBarP7 + POW25_7));
		double sl = 1.0 + 0.015 * square(lBarP - 50) / Math.sqrt(20.0 + square(lBarP - 50));
		double sc = 1.0 + 0.045 * cBarP;
		double sh = 1.0 + 0.015 * cBarP * t;
		double rt = -Math.sin(Math.toRadians(2 * dTheta)) * rc;

		double dl = dLp / sl;
		double dc = dCp / sc;
		double dh = dHp / sh;

		return Math.sqrt(dl * dl + dc * dc + dh * dh + rt * dc * dh);
	}

	/**
	 * Mean per-pixel ΔE00 between two equally sized ARGB pixel arrays
	 * (alpha ignored). Returns 0 for empty arrays.
	 */
	public static double meanDeltaE(int[] pixelsA, int[] pixelsB) {
		if (pixelsA.length != pixelsB.length) {
			throw new IllegalArgumentException("Pixel arrays differ in length: " + pixelsA.length + " != " + pixelsB.length);
		}
		if (pixelsA.length == 0) {
			return 0;
		}

		double sum = 0;
		for (int i = 0; i < pixelsA.length; i++) {
			sum += deltaE(pixelsA[i], pixelsB[i]);
		}
		return sum / pixelsA.length;
	}

	private static double hueAngle(double ap, double b) {
		if (ap == 0 && b == 0) {
			return 0;
		}
		double deg = Math.toDegrees(Math.atan2(b, ap));
		return deg < 0 ? deg + 360 : deg;
	}

	private static double square(double v) {
		return v * v;
	}

	private static double pow7(double v) {
		double v2 = v * v;
		return v2 * v2 * v2 * v;
	}
}
