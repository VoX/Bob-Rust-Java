package com.bobrust.generator;

import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Q2 (per-shape alpha in the generator). Focus areas, per the review
 * corrections: the copy paths must thread alphaIndex exactly like x/y/r
 * (State.getCopy and Circle.fromValues used to drop new fields — the undo /
 * determinism footgun), the committed state must stay exact, the search must
 * respect the minAlphaIndex floor, and everything stays deterministic.
 */
class PerShapeAlphaTest {
	private static final int ALPHA = 128;
	private static final int BACKGROUND = 0xFFFFFFFF;

	private static GeneratorConfig alphaConfig() {
		return GeneratorConfig.DEFAULT.withUsePerShapeAlpha(true).withMinAlphaIndex(2);
	}

	/** The copy-footgun regression: every copy path must carry alphaIndex. */
	@Test
	void copyPathsPreserveAlphaIndex() {
		BorstImage target = new BorstImage(testImage());
		Worker worker = new Worker(target, ALPHA, alphaConfig());

		Circle original = new Circle(worker, 10, 20, 8, 4);
		assertEquals(4, original.alphaIndex);

		// Circle.fromValues
		Circle assigned = new Circle(worker, 0, 0, 1, 2);
		assigned.fromValues(original);
		assertEquals(10, assigned.x);
		assertEquals(20, assigned.y);
		assertEquals(8, assigned.r);
		assertEquals(4, assigned.alphaIndex, "fromValues must copy alphaIndex with x/y/r");

		// State.getCopy
		State state = new State(worker, original, 0.5f);
		state.color = BorstUtils.COLORS[7];
		State copy = state.getCopy();
		assertEquals(original.x, copy.shape.x);
		assertEquals(original.y, copy.shape.y);
		assertEquals(original.r, copy.shape.r);
		assertEquals(original.alphaIndex, copy.shape.alphaIndex, "getCopy must copy alphaIndex with x/y/r");
		assertEquals(state.score, copy.score, 0.0f);
		assertSame(state.color, copy.color);

		// The 3-arg constructor pins the worker's global alpha index
		Circle legacy = new Circle(worker, 1, 2, 3);
		assertEquals(worker.alphaIndex, legacy.alphaIndex);
	}

	/**
	 * The hill-climb undo path: doMove saves the pre-move state into undo, and
	 * fromValues(undo) must restore it exactly — alphaIndex included. This is
	 * the loop getHillClimbClassic runs; a dropped field here corrupts the
	 * search silently.
	 */
	@Test
	void hillClimbUndoRestoresAlphaIndex() {
		BorstImage target = new BorstImage(testImage());
		Worker worker = new Worker(target, ALPHA, alphaConfig());

		State state = new State(worker, new Circle(worker, 30, 30, 6, 3), 0.25f);
		State undo = state.getCopy();

		for (int i = 0; i < 50; i++) {
			int x = state.shape.x, y = state.shape.y, r = state.shape.r, a = state.shape.alphaIndex;
			state.doMove(undo);

			assertEquals(x, undo.shape.x, "doMove must save x");
			assertEquals(y, undo.shape.y, "doMove must save y");
			assertEquals(r, undo.shape.r, "doMove must save r");
			assertEquals(a, undo.shape.alphaIndex, "doMove must save alphaIndex");

			state.fromValues(undo);
			assertEquals(x, state.shape.x);
			assertEquals(y, state.shape.y);
			assertEquals(r, state.shape.r);
			assertEquals(a, state.shape.alphaIndex, "undo must restore alphaIndex");

			// Advance to a fresh state for the next round
			state.doMove(undo);
		}
	}

	/**
	 * THE commit-exactness guarantee under per-shape alpha: the alpha used to
	 * evaluate a candidate is the alpha used to commit it, so the carried
	 * total still equals a full recompute — with and without Q1's metric.
	 */
	@Test
	void committedTotalIsExactWithPerShapeAlpha() {
		for (boolean perceptual : new boolean[] { false, true }) {
			BorstImage target = new BorstImage(testImage());
			Model model = new Model(target, BACKGROUND, ALPHA,
				alphaConfig().withUsePerceptualColor(perceptual));
			for (int i = 0; i < 25; i++) {
				model.processStep();
			}

			assertEquals(BorstCore.differenceFullTotal(target, model.current, perceptual), model.getTotalError(),
				"per-shape alpha (perceptual=" + perceptual + ") must keep the carried total exact");
			assertFalse(Float.isNaN(model.getScore()));
		}
	}

	/** All committed shapes respect the minAlphaIndex floor. */
	@Test
	void alphaSearchRespectsTheConfiguredFloor() {
		BorstImage target = new BorstImage(testImage());
		Model model = new Model(target, BACKGROUND, ALPHA,
			GeneratorConfig.DEFAULT.withUsePerShapeAlpha(true).withMinAlphaIndex(3));
		for (int i = 0; i < 20; i++) {
			model.processStep();
		}

		for (Circle shape : model.shapes) {
			assertTrue(shape.alphaIndex >= 3 && shape.alphaIndex < BorstUtils.ALPHAS.length,
				"alphaIndex " + shape.alphaIndex + " outside [3, 5]");
		}
	}

	/** The search actually explores alpha: a soft image commits more than one alpha level. */
	@Test
	void alphaSearchActuallyExploresAlpha() {
		BorstImage target = new BorstImage(ensureArgb(TestImageGenerator.createGradient()));
		Model model = new Model(target, BACKGROUND, ALPHA, alphaConfig());
		for (int i = 0; i < 60; i++) {
			model.processStep();
		}

		Set<Integer> alphas = new HashSet<>();
		for (Circle shape : model.shapes) {
			alphas.add(shape.alphaIndex);
		}
		assertTrue(alphas.size() > 1,
			"expected mixed alphas on a gradient image, got only " + alphas);
	}

	/** Per-shape alpha generation is deterministic: two identical runs are byte-identical. */
	@Test
	void perShapeAlphaGenerationIsDeterministic() {
		Model a = new Model(new BorstImage(testImage()), BACKGROUND, ALPHA, alphaConfig());
		Model b = new Model(new BorstImage(testImage()), BACKGROUND, ALPHA, alphaConfig());
		for (int i = 0; i < 15; i++) {
			a.processStep();
			b.processStep();
		}

		assertArrayEquals(a.current.pixels, b.current.pixels);
		assertEquals(a.shapes.size(), b.shapes.size());
		for (int i = 0; i < a.shapes.size(); i++) {
			assertEquals(a.shapes.get(i).alphaIndex, b.shapes.get(i).alphaIndex, "shape " + i + " alphaIndex");
		}
	}

	/** With the flag off, no committed shape deviates from the worker's global alpha index. */
	@Test
	void flagOffPinsAllShapesToTheGlobalAlpha() {
		BorstImage target = new BorstImage(testImage());
		Model model = new Model(target, BACKGROUND, ALPHA,
			GeneratorConfig.DEFAULT.withUsePerShapeAlpha(false));
		for (int i = 0; i < 10; i++) {
			model.processStep();
		}

		int expected = BorstUtils.getClosestAlphaIndex(ALPHA);
		for (Circle shape : model.shapes) {
			assertEquals(expected, shape.alphaIndex, "flag off must not vary alpha");
		}
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
