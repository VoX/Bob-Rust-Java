package com.bobrust.generator.quant;

import java.awt.image.BufferedImage;

/**
 * The palettized-mode quantizer (PLAN-PALETTIZED-MODE.md §5, carried from
 * PLAN-PIXEL-MODE.md §1): Wu's quantizer seeds N centroids in RGB, ~10 fixed
 * Lloyd iterations refine them in Oklab, pixels are assigned to the nearest
 * centroid in Oklab. Fully deterministic — fixed iteration count, fixed
 * deterministic empty-cluster reseeding, lowest-index tie-breaking — so the
 * preview is exactly what gets painted.
 */
public final class PixelQuantizer {
	private static final int LLOYD_ITERATIONS = 10;

	/**
	 * Quantization result: the exact sRGB palette, the per-cell palette index
	 * (row-major {@code gridW × gridH}) and the grid dimensions.
	 */
	public record Result(int[] paletteRgb, int[] labels, int gridW, int gridH) {
	}

	private PixelQuantizer() {
	}

	/**
	 * Quantizes {@code cellImage} (the area-averaged cell grid, opaque) to at
	 * most {@code colors} exact sRGB colors.
	 *
	 * @param dither Floyd–Steinberg error diffusion on the label assignment.
	 *               The palette itself is always computed dither-free.
	 */
	public static Result quantize(BufferedImage cellImage, int colors, boolean dither) {
		int w = cellImage.getWidth();
		int h = cellImage.getHeight();
		int[] pixels = cellImage.getRGB(0, 0, w, h, null, 0, w);
		return quantize(pixels, w, h, colors, dither);
	}

	/** See {@link #quantize(BufferedImage, int, boolean)}; pixels are row-major ARGB. */
	public static Result quantize(int[] pixels, int gridW, int gridH, int colors, boolean dither) {
		if (colors < 1) {
			throw new IllegalArgumentException("colors must be >= 1, was " + colors);
		}

		int[] seeds = WuQuantizer.quantize(pixels, colors);
		double[][] lab = new double[pixels.length][];
		for (int i = 0; i < pixels.length; i++) {
			lab[i] = Oklab.fromRgb(pixels[i]);
		}

		double[][] centroids = new double[seeds.length][];
		for (int i = 0; i < seeds.length; i++) {
			centroids[i] = Oklab.fromRgb(seeds[i]);
		}

		int[] assignment = new int[pixels.length];
		for (int iter = 0; iter < LLOYD_ITERATIONS; iter++) {
			double[] errors = assignNearest(lab, centroids, assignment);

			double[][] sums = new double[centroids.length][3];
			int[] counts = new int[centroids.length];
			for (int i = 0; i < lab.length; i++) {
				int c = assignment[i];
				sums[c][0] += lab[i][0];
				sums[c][1] += lab[i][1];
				sums[c][2] += lab[i][2];
				counts[c]++;
			}

			// Deterministic empty-cluster rule: reseed on the highest-error
			// pixel (lowest index wins ties), excluding pixels already chosen.
			boolean[] taken = new boolean[lab.length];
			for (int c = 0; c < centroids.length; c++) {
				if (counts[c] > 0) {
					continue;
				}
				int worst = -1;
				double worstError = -1;
				for (int i = 0; i < lab.length; i++) {
					if (!taken[i] && errors[i] > worstError) {
						worstError = errors[i];
						worst = i;
					}
				}
				if (worst < 0) {
					continue; // fewer pixels than clusters; leave empty
				}
				taken[worst] = true;
				centroids[c] = lab[worst].clone();
				counts[c] = -1; // mark reseeded so the mean update below skips it
			}

			for (int c = 0; c < centroids.length; c++) {
				if (counts[c] > 0) {
					centroids[c] = new double[] {
						sums[c][0] / counts[c],
						sums[c][1] / counts[c],
						sums[c][2] / counts[c]
					};
				}
			}
		}

		// The palette the game will actually show: 8-bit sRGB roundings of the
		// final centroids. Labels are assigned against THESE (converted back
		// to Oklab) so the label→color mapping is exactly the painted result.
		int[] palette = new int[centroids.length];
		double[][] paletteLab = new double[centroids.length][];
		for (int c = 0; c < centroids.length; c++) {
			palette[c] = Oklab.toRgb(centroids[c][0], centroids[c][1], centroids[c][2]);
			paletteLab[c] = Oklab.fromRgb(palette[c]);
		}

		int[] labels = new int[pixels.length];
		if (dither) {
			ditherAssign(pixels, gridW, gridH, palette, paletteLab, labels);
		} else {
			assignNearest(lab, paletteLab, labels);
		}
		return new Result(palette, labels, gridW, gridH);
	}

	/**
	 * Assigns every pixel to its nearest centroid (squared Euclidean in
	 * Oklab, lowest index wins ties). Returns the per-pixel squared error.
	 */
	private static double[] assignNearest(double[][] lab, double[][] centroids, int[] assignment) {
		double[] errors = new double[lab.length];
		for (int i = 0; i < lab.length; i++) {
			int best = 0;
			double bestDist = Double.MAX_VALUE;
			for (int c = 0; c < centroids.length; c++) {
				double dl = lab[i][0] - centroids[c][0];
				double da = lab[i][1] - centroids[c][1];
				double db = lab[i][2] - centroids[c][2];
				double dist = dl * dl + da * da + db * db;
				if (dist < bestDist) {
					bestDist = dist;
					best = c;
				}
			}
			assignment[i] = best;
			errors[i] = bestDist;
		}
		return errors;
	}

	/**
	 * Floyd–Steinberg label assignment: errors diffuse in sRGB, the nearest
	 * palette entry is still chosen in Oklab. Row-major, deterministic.
	 */
	private static void ditherAssign(int[] pixels, int w, int h, int[] palette, double[][] paletteLab, int[] labels) {
		double[] r = new double[pixels.length];
		double[] g = new double[pixels.length];
		double[] b = new double[pixels.length];
		for (int i = 0; i < pixels.length; i++) {
			r[i] = (pixels[i] >>> 16) & 0xff;
			g[i] = (pixels[i] >>>  8) & 0xff;
			b[i] = (pixels[i]       ) & 0xff;
		}

		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = y * w + x;
				double cr = clamp255(r[i]);
				double cg = clamp255(g[i]);
				double cb = clamp255(b[i]);

				double[] lab = Oklab.fromSrgb(cr / 255.0, cg / 255.0, cb / 255.0);
				int best = 0;
				double bestDist = Double.MAX_VALUE;
				for (int c = 0; c < paletteLab.length; c++) {
					double dl = lab[0] - paletteLab[c][0];
					double da = lab[1] - paletteLab[c][1];
					double db = lab[2] - paletteLab[c][2];
					double dist = dl * dl + da * da + db * db;
					if (dist < bestDist) {
						bestDist = dist;
						best = c;
					}
				}
				labels[i] = best;

				double er = cr - ((palette[best] >>> 16) & 0xff);
				double eg = cg - ((palette[best] >>>  8) & 0xff);
				double eb = cb - ((palette[best]       ) & 0xff);
				if (x + 1 < w) {
					r[i + 1] += er * 7 / 16;
					g[i + 1] += eg * 7 / 16;
					b[i + 1] += eb * 7 / 16;
				}
				if (y + 1 < h) {
					if (x > 0) {
						r[i + w - 1] += er * 3 / 16;
						g[i + w - 1] += eg * 3 / 16;
						b[i + w - 1] += eb * 3 / 16;
					}
					r[i + w] += er * 5 / 16;
					g[i + w] += eg * 5 / 16;
					b[i + w] += eb * 5 / 16;
					if (x + 1 < w) {
						r[i + w + 1] += er * 1 / 16;
						g[i + w + 1] += eg * 1 / 16;
						b[i + w + 1] += eb * 1 / 16;
					}
				}
			}
		}
	}

	private static double clamp255(double v) {
		return v < 0 ? 0 : Math.min(v, 255);
	}
}
