package com.bobrust.generator;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.bobrust.generator.sorter.Blob;
import com.bobrust.util.metrics.ImageMetrics;

/**
 * Exact-hex-color study (VoX 2026-07-06). Rust added custom hex color entry; the question is whether painting each
 * shape its exact optimal colour (no snap to the fixed 64-colour palette, and no need to layer translucent stamps to
 * fake an off-palette colour) beats the current palette pipeline at a fixed click budget — given a custom colour
 * costs ~3-6x a palette-swatch click and the two modes can't be mixed.
 *
 * <p>PART 1 (this file): the accuracy CEILING. Take the exact geometry the current 64-palette generator commits
 * (positions/sizes/alphas), then re-render it two ways against an identically-evolving substrate — snapping each
 * shape's exact optimal colour to the palette (= what the tool paints today) vs using the exact colour (= the custom
 * hex ideal). The gap is the pure palette-quantization penalty, isolated from placement. It is a slight
 * UNDER-estimate of the custom-hex win (a custom-colour generator would also place shapes differently), so a small
 * gap here kills the idea and a large gap justifies the deeper click-cost study.
 *
 * <p>Opt-in: {@code ./gradlew benchmark --tests com.bobrust.generator.ExactColorStudyTest}.
 */
@Tag("benchmark")
class ExactColorStudyTest {

	private static final int BG = TestBlobs.BACKGROUND; // white (test convention; relative gain is bg-independent)
	private static final boolean PERCEPTUAL = GeneratorConfig.DEFAULT.usePerceptualColor();
	private static final int[] SHAPE_COUNTS = { 500, 1000, 2000, 4000 };
	private static final int MAX_SHAPES = 4000;

	@Test
	void exactColorAccuracyCeiling() {
		// Warm the circle cache so the first image isn't paying init.
		TestBlobs.generate(ComplexTestImages.createGlyphs(), 100);

		System.out.println();
		System.out.println("Exact-color accuracy ceiling: same geometry, palette-snap vs exact colour, white bg.");
		System.out.printf(Locale.ROOT, "%-9s %6s | %8s %8s | %8s %8s | %8s %8s%n",
			"image", "shapes", "palSSIM", "exSSIM", "palDE00", "exDE00", "dSSIM", "dDE00");

		for (Map.Entry<String, BufferedImage> entry : ComplexTestImages.corpus().entrySet()) {
			String name = entry.getKey();
			TestBlobs.Generated gen = TestBlobs.generate(entry.getValue(), MAX_SHAPES);
			BorstImage target = gen.target();
			List<Blob> all = gen.blobs();

			for (int n : SHAPE_COUNTS) {
				List<Blob> shapes = all.subList(0, Math.min(n, all.size()));
				BorstImage palette = resimulate(target, shapes, false);
				BorstImage exact = resimulate(target, shapes, true);
				ImageMetrics.Result pal = ImageMetrics.compare(target, palette);
				ImageMetrics.Result ex = ImageMetrics.compare(target, exact);
				System.out.printf(Locale.ROOT, "%-9s %6d | %8.5f %8.5f | %8.4f %8.4f | %+8.5f %+8.4f%n",
					name, shapes.size(), pal.ssim(), ex.ssim(), pal.deltaE00(), ex.deltaE00(),
					ex.ssim() - pal.ssim(), ex.deltaE00() - pal.deltaE00());
			}
		}
		System.out.println("dSSIM>0 / dDE00<0 = exact colour is better. This is a lower bound on the custom-hex win.");
	}

	/**
	 * PART 3: the click-cost crossover. At a fixed 18k-click budget, does exact colour's quality-per-shape edge beat
	 * fitting more (palette) shapes, once a custom colour costs c=3..6x a palette-swatch click and the modes can't be
	 * mixed? Generous-to-custom model: quality uses the exact ceiling (unlimited exact colour, Part 1), while the
	 * custom click cost reuses the PALETTE plan's colour-change COUNT (a per-image custom palette of comparable size
	 * has comparable spatial colour structure) — so custom clicks(n) = palette clicks(n) + (c-1)*colourChanges(n). If
	 * even this optimistic custom scheme loses at equal budget, the verdict is decisive.
	 */
	private static final int CROSS_MAX = 16000;
	private static final int[] GRID = { 1000, 2000, 4000, 6000, 8000, 10000, 13000, 16000 };
	private static final int BUDGET = 18000;
	private static final int AUTOSAVE = 1000;
	private static final int SETUP = 21;
	private static final double[] COSTS = { 3.0, 4.5, 6.0 };

	@Test
	void exactColorClickCrossover() {
		TestBlobs.generate(ComplexTestImages.createGlyphs(), 100);
		System.out.println();
		System.out.println("Exact-color click crossover: quality at an 18k-click budget, palette vs custom-hex (c=3/4.5/6).");
		System.out.printf(Locale.ROOT, "%-9s %6s | %8s %7s | %8s %8s | %8s %8s%n",
			"image", "n", "palClk", "colChg", "palSSIM", "exSSIM", "palDE00", "exDE00");

		for (Map.Entry<String, BufferedImage> entry : ComplexTestImages.corpus().entrySet()) {
			String name = entry.getKey();
			TestBlobs.Generated gen = TestBlobs.generate(entry.getValue(), CROSS_MAX);
			BorstImage target = gen.target();
			List<Blob> all = gen.blobs();

			double[] gN = new double[GRID.length], gPalClk = new double[GRID.length], gCol = new double[GRID.length];
			double[] gPalS = new double[GRID.length], gExS = new double[GRID.length];
			double[] gPalD = new double[GRID.length], gExD = new double[GRID.length];

			for (int gi = 0; gi < GRID.length; gi++) {
				int n = Math.min(GRID[gi], all.size());
				List<Blob> shapes = all.subList(0, n);
				int[] chg = sortedChanges(shapes);          // {color, size, alpha}
				int tool = chg[0] + chg[1] + chg[2];        // all circles -> no shape changes
				int autosave = (n - 1) / AUTOSAVE;
				int palClicks = n + tool + autosave + SETUP;
				ImageMetrics.Result pal = ImageMetrics.compare(target, resimulate(target, shapes, false));
				ImageMetrics.Result ex = ImageMetrics.compare(target, resimulate(target, shapes, true));
				gN[gi] = n; gPalClk[gi] = palClicks; gCol[gi] = chg[0];
				gPalS[gi] = pal.ssim(); gExS[gi] = ex.ssim(); gPalD[gi] = pal.deltaE00(); gExD[gi] = ex.deltaE00();
				System.out.printf(Locale.ROOT, "%-9s %6d | %8d %7d | %8.5f %8.5f | %8.4f %8.4f%n",
					name, n, palClicks, chg[0], pal.ssim(), ex.ssim(), pal.deltaE00(), ex.deltaE00());
			}

			// Palette operating point: shapes fit in BUDGET, and its quality.
			double nPal = interp(gPalClk, gN, BUDGET);
			double palS = interpAt(gN, gPalS, nPal), palD = interpAt(gN, gPalD, nPal);
			StringBuilder verdict = new StringBuilder(String.format(Locale.ROOT,
				"  %s @18k: palette fits %.0f shapes -> SSIM %.5f / dE00 %.4f", name, nPal, palS, palD));
			for (double c : COSTS) {
				// custom clicks(n) = palClicks(n) + (c-1)*colourChanges(n); find n where that hits BUDGET.
				double[] custClk = new double[GRID.length];
				for (int gi = 0; gi < GRID.length; gi++) custClk[gi] = gPalClk[gi] + (c - 1) * gCol[gi];
				double nCust = interp(custClk, gN, BUDGET);
				double exS = interpAt(gN, gExS, nCust), exD = interpAt(gN, gExD, nCust);
				verdict.append(String.format(Locale.ROOT,
					"%n    custom c=%.1f: fits %.0f shapes -> SSIM %.5f / dE00 %.4f  [%s]",
					c, nCust, exS, exD, (exS >= palS && exD <= palD) ? "CUSTOM WINS" : "palette wins"));
			}
			System.out.println(verdict);
		}
	}

	/** {color, size, alpha} change counts of the sorted paint plan for these shapes. */
	private static int[] sortedChanges(List<Blob> shapes) {
		com.bobrust.generator.sorter.BlobList bl = new com.bobrust.generator.sorter.BlobList();
		for (Blob b : shapes) bl.add(b);
		com.bobrust.generator.sorter.BlobList sorted = com.bobrust.generator.sorter.BorstSorter.sort(bl);
		int color = 0, size = 0, alpha = 0;
		List<Blob> list = sorted.getList();
		for (int i = 1; i < list.size(); i++) {
			Blob a = list.get(i - 1), b = list.get(i);
			if (a.color != b.color) color++;
			if (a.size != b.size) size++;
			if (a.alpha != b.alpha) alpha++;
		}
		return new int[] { color, size, alpha };
	}

	/** Linear-interpolate the x (from xs, monotone increasing) at which ys crosses yTarget. */
	private static double interp(double[] ys, double[] xs, double yTarget) {
		for (int i = 1; i < ys.length; i++) {
			if (ys[i] >= yTarget) {
				double t = (yTarget - ys[i - 1]) / (ys[i] - ys[i - 1]);
				return xs[i - 1] + t * (xs[i] - xs[i - 1]);
			}
		}
		return xs[xs.length - 1]; // budget never reached within the grid
	}

	/** Linear-interpolate ys at x (xs monotone increasing). */
	private static double interpAt(double[] xs, double[] ys, double x) {
		if (x <= xs[0]) return ys[0];
		for (int i = 1; i < xs.length; i++) {
			if (xs[i] >= x) {
				double t = (x - xs[i - 1]) / (xs[i] - xs[i - 1]);
				return ys[i - 1] + t * (ys[i] - ys[i - 1]);
			}
		}
		return ys[ys.length - 1];
	}

	/**
	 * Re-render {@code shapes} in generation order against a fresh substrate, choosing each shape's colour by
	 * recomputing its exact optimal RGB (pre-snap) and then either using it directly ({@code exact}) or snapping it
	 * to the fixed palette (production behaviour). Same blend kernel either way.
	 */
	private static BorstImage resimulate(BorstImage target, List<Blob> shapes, boolean exact) {
		BorstImage current = new BorstImage(target.width, target.height);
		java.util.Arrays.fill(current.pixels, BG);
		for (Blob b : shapes) {
			int rgb = exactOptimalRgb(target, current, b.alpha, b.sizeIndex, b.x, b.y);
			BorstColor color = exact
				? new BorstColor((rgb >>> 16) & 0xff, (rgb >>> 8) & 0xff, rgb & 0xff)
				: BorstUtils.getClosestColor((b.alpha << 24) | rgb, PERCEPTUAL);
			BorstCore.drawLines(current, color, b.alpha, b.sizeIndex, b.x, b.y);
		}
		return current;
	}

	/** The per-channel continuous optimum from BorstCore.computeColor (lines 19-77), WITHOUT the palette snap. */
	private static int exactOptimalRgb(BorstImage target, BorstImage current, int alpha, int sizeIndex, int xo, int yo) {
		long rs1 = 0, gs1 = 0, bs1 = 0, rs2 = 0, gs2 = 0, bs2 = 0;
		int count = 0;
		int w = target.width, h = target.height;
		Scanline[] lines = CircleCache.CIRCLE_CACHE[sizeIndex];
		for (Scanline line : lines) {
			int y = line.y + yo;
			if (y < 0 || y >= h) continue;
			int xs = Math.max(line.x1 + xo, 0), xe = Math.min(line.x2 + xo, w - 1);
			if (xs > xe) continue;
			int idx = y * w;
			for (int x = xs; x <= xe; x++) {
				int tt = target.pixels[idx + x], cc = current.pixels[idx + x];
				rs1 += (tt >>> 16) & 0xff; gs1 += (tt >>> 8) & 0xff; bs1 += tt & 0xff;
				rs2 += (cc >>> 16) & 0xff; gs2 += (cc >>> 8) & 0xff; bs2 += cc & 0xff;
			}
			count += (xe - xs + 1);
		}
		if (count == 0) return BorstUtils.COLORS[0].rgb;
		int pd = 65280 / alpha;
		long rsum = (rs1 - rs2) * pd + (rs2 << 8);
		long gsum = (gs1 - gs2) * pd + (gs2 << 8);
		long bsum = (bs1 - bs2) * pd + (bs2 << 8);
		int r = BorstUtils.clampInt((int) (rsum / (double) count) >> 8, 0, 255);
		int g = BorstUtils.clampInt((int) (gsum / (double) count) >> 8, 0, 255);
		int b = BorstUtils.clampInt((int) (bsum / (double) count) >> 8, 0, 255);
		return (r << 16) | (g << 8) | b;
	}
}
