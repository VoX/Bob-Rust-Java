package com.bobrust.util.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ciede2000Test {
	/**
	 * Reference pairs from Sharma, Wu &amp; Dalal (2005), Table 1:
	 * {L1, a1, b1, L2, a2, b2, expected ΔE00}. The selection covers the
	 * tricky regions: the blue a'-correction (1–3), hue-angle discontinuities
	 * near 0°/360° (9, 13), large differences (17), a designed ΔE00 = 1 pair
	 * (21) and real experimental pairs (25, 33, 34).
	 */
	private static final double[][] SHARMA_PAIRS = {
		{50.0000,  2.6772, -79.7751, 50.0000,  0.0000, -82.7485,  2.0425},
		{50.0000,  3.1571, -77.2803, 50.0000,  0.0000, -82.7485,  2.8615},
		{50.0000,  2.8361, -74.0200, 50.0000,  0.0000, -82.7485,  3.4412},
		{50.0000,  0.0000,   0.0000, 50.0000, -1.0000,   2.0000,  2.3669},
		{50.0000,  2.4900,  -0.0010, 50.0000, -2.4900,   0.0009,  7.1792},
		{50.0000, -0.0010,   2.4900, 50.0000,  0.0009,  -2.4900,  4.8045},
		{50.0000,  2.5000,   0.0000, 73.0000, 25.0000, -18.0000, 27.1492},
		{50.0000,  2.5000,   0.0000, 50.0000,  3.1736,   0.5854,  1.0000},
		{60.2574, -34.0099, 36.2677, 60.4626, -34.1751,  39.4387, 1.2644},
		{ 6.7747, -0.2908,  -2.4247,  5.8714, -0.0985,  -2.2286,  0.6377},
		{ 2.0776,  0.0795,  -1.1350,  0.9033, -0.0636,  -0.5514,  0.9082},
	};

	@Test
	void matchesSharmaReferencePairs() {
		for (double[] p : SHARMA_PAIRS) {
			double actual = Ciede2000.deltaE(p[0], p[1], p[2], p[3], p[4], p[5]);
			assertEquals(p[6], actual, 1e-4,
				"dE00 for Lab(" + p[0] + "," + p[1] + "," + p[2] + ") vs Lab(" + p[3] + "," + p[4] + "," + p[5] + ")");
		}
	}

	@Test
	void deltaEIsSymmetric() {
		for (double[] p : SHARMA_PAIRS) {
			double forward = Ciede2000.deltaE(p[0], p[1], p[2], p[3], p[4], p[5]);
			double backward = Ciede2000.deltaE(p[3], p[4], p[5], p[0], p[1], p[2]);
			assertEquals(forward, backward, 1e-12);
		}
	}

	@Test
	void identicalColorsHaveZeroDeltaE() {
		assertEquals(0.0, Ciede2000.deltaE(50.0, 2.5, -30.0, 50.0, 2.5, -30.0), 0.0);
		assertEquals(0.0, Ciede2000.deltaE(0xFF3366AA, 0xFF3366AA), 0.0);
	}

	/** sRGB → Lab conversion pinned against standard D65 reference values. */
	@Test
	void rgbToLabMatchesKnownValues() {
		double[] white = Ciede2000.rgbToLab(0xFFFFFFFF);
		assertEquals(100.0, white[0], 0.01, "white L");
		assertEquals(0.0, white[1], 0.01, "white a");
		assertEquals(0.0, white[2], 0.01, "white b");

		double[] black = Ciede2000.rgbToLab(0xFF000000);
		assertEquals(0.0, black[0], 1e-6, "black L");
		assertEquals(0.0, black[1], 1e-6, "black a");
		assertEquals(0.0, black[2], 1e-6, "black b");

		double[] red = Ciede2000.rgbToLab(0xFFFF0000);
		assertEquals(53.24, red[0], 0.05, "red L");
		assertEquals(80.09, red[1], 0.10, "red a");
		assertEquals(67.20, red[2], 0.10, "red b");
	}

	/** A flat patch of A vs a flat patch of B must average to exactly deltaE(A, B). */
	@Test
	void meanDeltaEOfFlatPatchesEqualsSingleDelta() {
		int colorA = 0xFF6688AA;
		int colorB = 0xFF6890A8; // known small patch delta
		int[] patchA = new int[10 * 10];
		int[] patchB = new int[10 * 10];
		java.util.Arrays.fill(patchA, colorA);
		java.util.Arrays.fill(patchB, colorB);

		double single = Ciede2000.deltaE(colorA, colorB);
		assertTrue(single > 0 && single < 10, "small but non-zero delta, was " + single);
		assertEquals(single, Ciede2000.meanDeltaE(patchA, patchB), 1e-12);
		assertEquals(0.0, Ciede2000.meanDeltaE(patchA, patchA), 0.0);
	}
}
