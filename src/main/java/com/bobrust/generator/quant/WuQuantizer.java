package com.bobrust.generator.quant;

/**
 * Wu's weighted-variance color quantizer (Xiaolin Wu, "Efficient Statistical
 * Computations for Optimal Color Quantization", Graphics Gems II, 1991):
 * greedy orthogonal bipartition of RGB space minimizing weighted SSE over a
 * 33³ moment histogram. Deterministic; used to seed the Oklab Lloyd
 * refinement in {@link PixelQuantizer}.
 */
public final class WuQuantizer {
	private static final int INDEX_BITS = 5;
	private static final int SIDE = (1 << INDEX_BITS) + 1; // 33
	private static final int SIDE2 = SIDE * SIDE;
	private static final int RED = 2, GREEN = 1, BLUE = 0;

	private final long[] wt = new long[SIDE * SIDE2];
	private final long[] mr = new long[SIDE * SIDE2];
	private final long[] mg = new long[SIDE * SIDE2];
	private final long[] mb = new long[SIDE * SIDE2];
	private final double[] m2 = new double[SIDE * SIDE2];

	private static final class Box {
		int r0, r1, g0, g1, b0, b1;
		double vv;
	}

	private WuQuantizer() {
	}

	/**
	 * Quantizes {@code pixels} (ARGB, alpha ignored) to at most
	 * {@code maxColors} palette seeds. Returns fewer when the image does not
	 * contain enough distinct colors to fill the request.
	 */
	public static int[] quantize(int[] pixels, int maxColors) {
		WuQuantizer q = new WuQuantizer();
		q.buildHistogram(pixels);
		q.computeMoments();
		return q.buildPalette(maxColors);
	}

	private static int index(int r, int g, int b) {
		return r * SIDE2 + g * SIDE + b;
	}

	private void buildHistogram(int[] pixels) {
		for (int pixel : pixels) {
			int r = (pixel >>> 16) & 0xff;
			int g = (pixel >>>  8) & 0xff;
			int b = (pixel       ) & 0xff;
			int i = index((r >> 3) + 1, (g >> 3) + 1, (b >> 3) + 1);
			wt[i]++;
			mr[i] += r;
			mg[i] += g;
			mb[i] += b;
			m2[i] += (double) (r * r + g * g + b * b);
		}
	}

	/** Converts the histogram into cumulative moments. */
	private void computeMoments() {
		long[] area = new long[SIDE];
		long[] areaR = new long[SIDE];
		long[] areaG = new long[SIDE];
		long[] areaB = new long[SIDE];
		double[] area2 = new double[SIDE];

		for (int r = 1; r < SIDE; r++) {
			java.util.Arrays.fill(area, 0);
			java.util.Arrays.fill(areaR, 0);
			java.util.Arrays.fill(areaG, 0);
			java.util.Arrays.fill(areaB, 0);
			java.util.Arrays.fill(area2, 0);
			for (int g = 1; g < SIDE; g++) {
				long line = 0, lineR = 0, lineG = 0, lineB = 0;
				double line2 = 0;
				for (int b = 1; b < SIDE; b++) {
					int i = index(r, g, b);
					line += wt[i];
					lineR += mr[i];
					lineG += mg[i];
					lineB += mb[i];
					line2 += m2[i];
					area[b] += line;
					areaR[b] += lineR;
					areaG[b] += lineG;
					areaB[b] += lineB;
					area2[b] += line2;
					int prev = index(r - 1, g, b);
					wt[i] = wt[prev] + area[b];
					mr[i] = mr[prev] + areaR[b];
					mg[i] = mg[prev] + areaG[b];
					mb[i] = mb[prev] + areaB[b];
					m2[i] = m2[prev] + area2[b];
				}
			}
		}
	}

	/** Sum of {@code mmt} over the box (8-corner inclusion–exclusion). */
	private static long vol(Box c, long[] mmt) {
		return mmt[index(c.r1, c.g1, c.b1)]
			- mmt[index(c.r1, c.g1, c.b0)]
			- mmt[index(c.r1, c.g0, c.b1)]
			+ mmt[index(c.r1, c.g0, c.b0)]
			- mmt[index(c.r0, c.g1, c.b1)]
			+ mmt[index(c.r0, c.g1, c.b0)]
			+ mmt[index(c.r0, c.g0, c.b1)]
			- mmt[index(c.r0, c.g0, c.b0)];
	}

	private static double volDouble(Box c, double[] mmt) {
		return mmt[index(c.r1, c.g1, c.b1)]
			- mmt[index(c.r1, c.g1, c.b0)]
			- mmt[index(c.r1, c.g0, c.b1)]
			+ mmt[index(c.r1, c.g0, c.b0)]
			- mmt[index(c.r0, c.g1, c.b1)]
			+ mmt[index(c.r0, c.g1, c.b0)]
			+ mmt[index(c.r0, c.g0, c.b1)]
			- mmt[index(c.r0, c.g0, c.b0)];
	}

	/** Sum over the box with the {@code dir} lower bound clipped at {@code pos}: the "bottom" slab. */
	private static long bottom(Box c, int dir, long[] mmt) {
		return switch (dir) {
			case RED -> -mmt[index(c.r0, c.g1, c.b1)]
				+ mmt[index(c.r0, c.g1, c.b0)]
				+ mmt[index(c.r0, c.g0, c.b1)]
				- mmt[index(c.r0, c.g0, c.b0)];
			case GREEN -> -mmt[index(c.r1, c.g0, c.b1)]
				+ mmt[index(c.r1, c.g0, c.b0)]
				+ mmt[index(c.r0, c.g0, c.b1)]
				- mmt[index(c.r0, c.g0, c.b0)];
			default -> -mmt[index(c.r1, c.g1, c.b0)]
				+ mmt[index(c.r1, c.g0, c.b0)]
				+ mmt[index(c.r0, c.g1, c.b0)]
				- mmt[index(c.r0, c.g0, c.b0)];
		};
	}

	/** Sum over the box with the {@code dir} upper bound moved to {@code pos}. */
	private static long top(Box c, int dir, int pos, long[] mmt) {
		return switch (dir) {
			case RED -> mmt[index(pos, c.g1, c.b1)]
				- mmt[index(pos, c.g1, c.b0)]
				- mmt[index(pos, c.g0, c.b1)]
				+ mmt[index(pos, c.g0, c.b0)];
			case GREEN -> mmt[index(c.r1, pos, c.b1)]
				- mmt[index(c.r1, pos, c.b0)]
				- mmt[index(c.r0, pos, c.b1)]
				+ mmt[index(c.r0, pos, c.b0)];
			default -> mmt[index(c.r1, c.g1, pos)]
				- mmt[index(c.r1, c.g0, pos)]
				- mmt[index(c.r0, c.g1, pos)]
				+ mmt[index(c.r0, c.g0, pos)];
		};
	}

	/** Weighted variance of the box (the SSE the split minimizes). */
	private double variance(Box c) {
		double dr = vol(c, mr);
		double dg = vol(c, mg);
		double db = vol(c, mb);
		double xx = volDouble(c, m2);
		long w = vol(c, wt);
		return w == 0 ? 0 : xx - (dr * dr + dg * dg + db * db) / w;
	}

	/**
	 * Best split position along {@code dir}, writing the position to
	 * {@code cut[0]}. Returns the variance reduction (−1 if no valid cut).
	 */
	private double maximize(Box c, int dir, int first, int last, int[] cut,
			long wholeR, long wholeG, long wholeB, long wholeW) {
		long baseR = bottom(c, dir, mr);
		long baseG = bottom(c, dir, mg);
		long baseB = bottom(c, dir, mb);
		long baseW = bottom(c, dir, wt);

		double max = 0;
		cut[0] = -1;
		for (int i = first; i < last; i++) {
			long halfR = baseR + top(c, dir, i, mr);
			long halfG = baseG + top(c, dir, i, mg);
			long halfB = baseB + top(c, dir, i, mb);
			long halfW = baseW + top(c, dir, i, wt);
			if (halfW == 0) {
				continue;
			}
			double temp = ((double) halfR * halfR + (double) halfG * halfG + (double) halfB * halfB) / halfW;

			halfR = wholeR - halfR;
			halfG = wholeG - halfG;
			halfB = wholeB - halfB;
			halfW = wholeW - halfW;
			if (halfW == 0) {
				continue;
			}
			temp += ((double) halfR * halfR + (double) halfG * halfG + (double) halfB * halfB) / halfW;

			if (temp > max) {
				max = temp;
				cut[0] = i;
			}
		}
		return max;
	}

	/** Splits {@code set1} into (set1, set2). Returns false if the box cannot be cut. */
	private boolean cut(Box set1, Box set2) {
		long wholeR = vol(set1, mr);
		long wholeG = vol(set1, mg);
		long wholeB = vol(set1, mb);
		long wholeW = vol(set1, wt);

		int[] cutR = new int[1], cutG = new int[1], cutB = new int[1];
		double maxR = maximize(set1, RED, set1.r0 + 1, set1.r1, cutR, wholeR, wholeG, wholeB, wholeW);
		double maxG = maximize(set1, GREEN, set1.g0 + 1, set1.g1, cutG, wholeR, wholeG, wholeB, wholeW);
		double maxB = maximize(set1, BLUE, set1.b0 + 1, set1.b1, cutB, wholeR, wholeG, wholeB, wholeW);

		int dir;
		if (maxR >= maxG && maxR >= maxB) {
			dir = RED;
			if (cutR[0] < 0) {
				return false;
			}
		} else if (maxG >= maxR && maxG >= maxB) {
			dir = GREEN;
		} else {
			dir = BLUE;
		}

		set2.r1 = set1.r1;
		set2.g1 = set1.g1;
		set2.b1 = set1.b1;
		switch (dir) {
			case RED -> {
				set2.r0 = set1.r1 = cutR[0];
				set2.g0 = set1.g0;
				set2.b0 = set1.b0;
			}
			case GREEN -> {
				set2.g0 = set1.g1 = cutG[0];
				set2.r0 = set1.r0;
				set2.b0 = set1.b0;
			}
			default -> {
				set2.b0 = set1.b1 = cutB[0];
				set2.r0 = set1.r0;
				set2.g0 = set1.g0;
			}
		}
		return true;
	}

	private int[] buildPalette(int maxColors) {
		Box[] cube = new Box[maxColors];
		cube[0] = new Box();
		cube[0].r1 = cube[0].g1 = cube[0].b1 = SIDE - 1;

		// Classic Wu main loop: repeatedly split the highest-variance box.
		int produced = maxColors; // boxes actually created
		int next = 0;
		for (int i = 1; i < maxColors; i++) {
			Box set2 = new Box();
			if (cut(cube[next], set2)) {
				cube[next].vv = vol(cube[next], wt) > 1 ? variance(cube[next]) : 0;
				set2.vv = vol(set2, wt) > 1 ? variance(set2) : 0;
				cube[i] = set2;
			} else {
				cube[next].vv = 0; // never try to split this box again
				i--;               // didn't create box i
			}

			next = 0;
			double temp = cube[0].vv;
			for (int k = 1; k <= i; k++) {
				if (cube[k].vv > temp) {
					temp = cube[k].vv;
					next = k;
				}
			}
			if (temp <= 0) {
				produced = i + 1; // ran out of splittable boxes
				break;
			}
		}

		int[] palette = new int[produced];
		int out = 0;
		for (int i = 0; i < produced; i++) {
			long w = vol(cube[i], wt);
			if (w > 0) {
				int r = (int) (vol(cube[i], mr) / w);
				int g = (int) (vol(cube[i], mg) / w);
				int b = (int) (vol(cube[i], mb) / w);
				palette[out++] = 0xff000000 | (r << 16) | (g << 8) | b;
			}
		}
		return java.util.Arrays.copyOf(palette, out);
	}
}
