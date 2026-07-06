package com.bobrust.generator;

import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Q1 (perceptual color): the channel-weighted RGB metric used for
 * BOTH the palette snap and the energy kernels. The load-bearing invariant —
 * the validators' required correction — is that snap and energy share ONE
 * metric; if they disagreed, candidate ranking and color snapping would pull
 * in different directions and computeColor's optimal-color-then-snap would no
 * longer pick the discrete optimum.
 */
class PerceptualColorTest {
	private static final int ALPHA = 128;
	private static final int BACKGROUND = 0xFFFFFFFF;

	/**
	 * Pins the SNAP metric to the shared weight constants: the weighted LUT
	 * must agree with a brute-force scan using BorstUtils.PERCEPTUAL_WEIGHT_*.
	 * (Inputs are LUT-bucket centers, the exact colors the LUT was built from.)
	 */
	@Test
	void weightedSnapMinimizesSharedWeightMetric() {
		// LUT bucket centers: (i << 2) | 1 — the exact colors the LUT was built from
		for (int r = 1; r < 256; r += 4) {
			for (int g = 1; g < 256; g += 8) {
				for (int b = 1; b < 256; b += 8) {
					int chosen = BorstUtils.getClosestColorIndexWeighted((r << 16) | (g << 8) | b);
					int expected = BorstUtils.getClosestColorIndexLinear(r, g, b,
						BorstUtils.PERCEPTUAL_WEIGHT_R,
						BorstUtils.PERCEPTUAL_WEIGHT_G,
						BorstUtils.PERCEPTUAL_WEIGHT_B);
					assertEquals(expected, chosen, "weighted snap at (" + r + "," + g + "," + b + ")");
				}
			}
		}
	}

	/**
	 * Pins the ENERGY metric to the same weight constants: on single-pixel
	 * images the weighted total must be exactly
	 * wr*dr^2 + wg*dg^2 + wb*db^2 + wa*da^2 with the BorstUtils weights.
	 * Together with the snap test above this proves snap and energy share one
	 * metric — the Q1 consistency requirement.
	 */
	@Test
	void energyKernelUsesSharedWeightConstants() {
		Random rnd = new Random(42);
		for (int i = 0; i < 200; i++) {
			int pa = rnd.nextInt(256), pr = rnd.nextInt(256), pg = rnd.nextInt(256), pb = rnd.nextInt(256);
			int qa = rnd.nextInt(256), qr = rnd.nextInt(256), qg = rnd.nextInt(256), qb = rnd.nextInt(256);

			BorstImage a = new BorstImage(1, 1);
			BorstImage b = new BorstImage(1, 1);
			a.pixels[0] = (pa << 24) | (pr << 16) | (pg << 8) | pb;
			b.pixels[0] = (qa << 24) | (qr << 16) | (qg << 8) | qb;

			long dr = pr - qr, dg = pg - qg, db = pb - qb, da = pa - qa;
			long expected = BorstUtils.PERCEPTUAL_WEIGHT_R * dr * dr
				+ BorstUtils.PERCEPTUAL_WEIGHT_G * dg * dg
				+ BorstUtils.PERCEPTUAL_WEIGHT_B * db * db
				+ BorstUtils.PERCEPTUAL_WEIGHT_A * da * da;

			assertEquals(expected, BorstCore.differenceFullTotal(a, b, true));
		}
	}

	/**
	 * computeColor's continuous per-channel optimum is weight-invariant, so
	 * under the perceptual flag it must return exactly the WEIGHTED snap of
	 * that optimum. With current == target == solid color the optimum is the
	 * color itself, making the expected snap directly computable.
	 */
	@Test
	void computeColorSnapsItsOptimumWithTheWeightedMetric() {
		Random rnd = new Random(7);
		for (int i = 0; i < 100; i++) {
			int rgb = rnd.nextInt(1 << 24);
			BorstImage solid = new BorstImage(32, 32);
			Arrays.fill(solid.pixels, 0xFF000000 | rgb);

			BorstColor perceptual = BorstCore.computeColor(solid, solid, ALPHA, 3, 16, 16, true);
			assertSame(BorstUtils.getClosestColorWeighted(rgb), perceptual,
				"perceptual computeColor must snap with the weighted metric for #" + Integer.toHexString(rgb));

			BorstColor legacy = BorstCore.computeColor(solid, solid, ALPHA, 3, 16, 16, false);
			assertSame(BorstUtils.getClosestColor(rgb), legacy,
				"legacy computeColor must keep the unweighted snap for #" + Integer.toHexString(rgb));
		}
	}

	/** The G2 proxy must share the metric: stride-1 sizes are exact under the perceptual flag too. */
	@Test
	void proxyMatchesExactKernelUnderPerceptualMetric() {
		BorstImage target = new BorstImage(ensureArgb(TestImageGenerator.createPhotoDetail()));
		BorstImage current = new BorstImage(target.width, target.height);
		Arrays.fill(current.pixels, BACKGROUND);
		long total = BorstCore.differenceFullTotal(target, current, true);

		for (int size = 0; size < BorstCore.PROXY_STRIDE.length; size++) {
			if (BorstCore.PROXY_STRIDE[size] != 1) {
				continue;
			}
			for (int[] pos : new int[][] {{16, 16}, {64, 64}, {100, 30}, {-2, 50}}) {
				float exact = BorstCore.differencePartialThreadCombined(
					target, current, total, ALPHA, size, pos[0], pos[1], true, null);
				float proxy = BorstCore.differencePartialProxy(
					target, current, total, ALPHA, size, pos[0], pos[1], true);
				assertEquals(exact, proxy, 0.0f,
					"perceptual stride-1 size " + size + " at (" + pos[0] + "," + pos[1] + ")");
			}
		}
	}

	/** Classic and combined kernels must stay numerically identical under the weighted metric. */
	@Test
	void classicAndCombinedAgreeUnderPerceptualMetric() {
		BorstImage target = new BorstImage(ensureArgb(TestImageGenerator.createNature()));
		BorstImage current = new BorstImage(target.width, target.height);
		Arrays.fill(current.pixels, BACKGROUND);
		long total = BorstCore.differenceFullTotal(target, current, true);

		for (int[] tc : new int[][] {{64, 64, 0}, {64, 64, 5}, {0, 0, 2}, {127, 127, 2}, {-5, 30, 2}, {10, 50, 3}}) {
			float classic = BorstCore.differencePartialThreadClassic(
				target, current, total, ALPHA, tc[2], tc[0], tc[1], true, null);
			float combined = BorstCore.differencePartialThreadCombined(
				target, current, total, ALPHA, tc[2], tc[0], tc[1], true, null);
			assertEquals(classic, combined, 1e-6f,
				"perceptual classic vs combined at (" + tc[0] + "," + tc[1] + ") size=" + tc[2]);
		}
	}

	/**
	 * The exact-carry invariant holds under the weighted metric: after real
	 * generation steps the incremental weighted total equals a full weighted
	 * recompute, and proxy ranking still never leaks into the committed state.
	 */
	@Test
	void committedWeightedTotalIsExact() {
		BorstImage target = new BorstImage(testImage());
		Model model = new Model(target, BACKGROUND, ALPHA,
			GeneratorConfig.DEFAULT.withUsePerceptualColor(true).withUseProxyRanking(true));
		for (int i = 0; i < 25; i++) {
			model.processStep();
		}

		assertEquals(BorstCore.differenceFullTotal(target, model.current, true), model.getTotalError(),
			"incremental weighted total must equal a full weighted recompute");
		assertFalse(Float.isNaN(model.getScore()));
	}

	/** Perceptual generation stays deterministic: two identical runs render byte-identically. */
	@Test
	void perceptualGenerationIsDeterministic() {
		GeneratorConfig config = GeneratorConfig.DEFAULT.withUsePerceptualColor(true);
		Model a = new Model(new BorstImage(testImage()), BACKGROUND, ALPHA, config);
		Model b = new Model(new BorstImage(testImage()), BACKGROUND, ALPHA, config);
		for (int i = 0; i < 15; i++) {
			a.processStep();
			b.processStep();
		}

		assertArrayEquals(a.current.pixels, b.current.pixels);
	}

	private static BufferedImage testImage() {
		BufferedImage img = new BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(java.awt.Color.WHITE);
		g.fillRect(0, 0, 96, 96);
		g.setColor(java.awt.Color.RED);
		g.fillOval(8, 8, 48, 48);
		g.setColor(java.awt.Color.BLUE);
		g.fillRect(48, 48, 40, 40);
		g.dispose();
		return img;
	}

	private static BufferedImage ensureArgb(BufferedImage img) {
		if (img.getType() == BufferedImage.TYPE_INT_ARGB) return img;
		BufferedImage argb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = argb.createGraphics();
		g.drawImage(img, 0, 0, null);
		g.dispose();
		return argb;
	}
}
