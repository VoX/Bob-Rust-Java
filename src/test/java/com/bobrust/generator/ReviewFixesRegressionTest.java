package com.bobrust.generator;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the bugs fixed after the code review
 * (see REVIEW-FORK-CHANGES.md / REVIEW-CORE-MODEL.md):
 * <ul>
 *   <li>ErrorMap crash on sign sizes not divisible by the grid combined with
 *       a zero-error target (blank image)</li>
 *   <li>Non-reproducible generation after the ThreadLocalRandom switch</li>
 *   <li>Float score round-trip drift / NaN in the incremental energy carry</li>
 * </ul>
 */
class ReviewFixesRegressionTest {
	private static final int ALPHA = 128;
	private static final int BACKGROUND = 0xFFFFFFFF;

	/**
	 * A 100x100 sign gives 4px grid cells whose last columns/rows used to lie
	 * entirely outside the image; with a blank target (total error zero) the
	 * uniform fallback made those cells samplable and Random.nextInt threw
	 * IllegalArgumentException, killing the generator thread.
	 */
	@Test
	void errorMapSamplingSurvivesBlankNonDivisibleTarget() {
		BorstImage target = new BorstImage(100, 100);
		Arrays.fill(target.pixels, BACKGROUND);
		BorstImage current = new BorstImage(100, 100);
		Arrays.fill(current.pixels, BACKGROUND);

		ErrorMap map = new ErrorMap(100, 100);
		map.computeFull(target, current);

		Random rnd = new Random(1);
		for (int i = 0; i < 10_000; i++) {
			int packed = map.samplePositionPacked(rnd);
			int x = packed & 0xffff;
			int y = packed >>> 16;
			assertTrue(x >= 0 && x < 100, "x in bounds: " + x);
			assertTrue(y >= 0 && y < 100, "y in bounds: " + y);
		}
	}

	/** A full generation step on a blank target must not throw or produce NaN. */
	@Test
	void generationOnBlankTargetDoesNotCrash() {
		BorstImage target = new BorstImage(100, 100);
		Arrays.fill(target.pixels, BACKGROUND);

		Model model = new Model(target, BACKGROUND, ALPHA);
		for (int i = 0; i < 3; i++) {
			model.processStep();
		}
		assertFalse(Float.isNaN(model.getScore()), "score must never be NaN");
		assertTrue(model.getScore() >= 0);
	}

	/**
	 * Generation is seeded per worker, so two runs over the same input must
	 * produce identical shapes and identical scores.
	 */
	@Test
	void generationIsDeterministic() {
		BufferedImage img = testImage();

		Model a = new Model(new BorstImage(img), BACKGROUND, ALPHA);
		Model b = new Model(new BorstImage(img), BACKGROUND, ALPHA);

		for (int i = 0; i < 15; i++) {
			a.processStep();
			b.processStep();
		}

		assertEquals(a.shapes.size(), b.shapes.size());
		for (int i = 0; i < a.shapes.size(); i++) {
			Circle ca = a.shapes.get(i);
			Circle cb = b.shapes.get(i);
			assertEquals(ca.x, cb.x, "shape " + i + " x");
			assertEquals(ca.y, cb.y, "shape " + i + " y");
			assertEquals(ca.r, cb.r, "shape " + i + " r");
			assertEquals(a.colors.get(i).rgb, b.colors.get(i).rgb, "shape " + i + " color");
		}
		assertEquals(a.getScore(), b.getScore(), 0.0f, "final scores identical");
	}

	/**
	 * The incremental long-total carry must stay exactly equal to a full
	 * recompute — this is what eliminates the float round-trip drift and the
	 * negative-total NaN near convergence.
	 */
	@Test
	void incrementalTotalMatchesFullRecomputeExactly() {
		BufferedImage img = testImage();
		BorstImage target = new BorstImage(img);

		Model model = new Model(target, BACKGROUND, ALPHA);
		for (int i = 0; i < 25; i++) {
			model.processStep();
		}

		long recomputed = BorstCore.differenceFullTotal(target, model.current);
		assertEquals(recomputed, model.getTotalError(),
			"incremental total must be exactly equal to a full recompute");
		assertFalse(Float.isNaN(model.getScore()));
	}

	private static BufferedImage testImage() {
		BufferedImage img = new BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, 96, 96);
		g.setColor(Color.RED);
		g.fillOval(8, 8, 48, 48);
		g.setColor(Color.BLUE);
		g.fillRect(48, 48, 40, 40);
		g.dispose();
		return img;
	}
}
