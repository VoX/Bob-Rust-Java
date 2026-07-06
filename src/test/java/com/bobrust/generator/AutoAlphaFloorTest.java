package com.bobrust.generator;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import com.bobrust.util.data.AppConstants;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S2 content-adaptive alpha floor: the MIN_ALPHA_AUTO (-1) sentinel resolves to the shipped floor
 * (AppConstants.MIN_ALPHA_INDEX) on hard-edge/text content and to 0 (glazing) on photographic content, before
 * the Worker is built. Explicit floors are never touched. Resolution happens even when adaptive size is off
 * (the gradient map is then stats-only and not attached to the worker).
 */
class AutoAlphaFloorTest {

	private static final int BG = 0xFFFFFFFF;
	private static final int ALPHA = 128;

	private static BorstImage argb(BufferedImage img) {
		if (img.getType() == BufferedImage.TYPE_INT_ARGB) return new BorstImage(img);
		BufferedImage a = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = a.createGraphics();
		g.drawImage(img, 0, 0, null);
		g.dispose();
		return new BorstImage(a);
	}

	private static GeneratorConfig auto() {
		return GeneratorConfig.DEFAULT.withMinAlphaIndex(GeneratorConfig.MIN_ALPHA_AUTO);
	}

	@Test
	void hardEdgeContentResolvesToShippedFloor() {
		Model model = new Model(argb(ComplexTestImages.createGlyphs()), BG, ALPHA, auto());
		assertEquals(AppConstants.MIN_ALPHA_INDEX, model.getConfig().minAlphaIndex(),
			"hard-edge/text content keeps the shipped alpha floor");
	}

	@Test
	void smoothContentResolvesToZero() {
		Model model = new Model(argb(TestImageGenerator.createGradient()), BG, ALPHA, auto());
		assertEquals(0, model.getConfig().minAlphaIndex(), "photographic content drops the floor to 0 for glazing");
	}

	@Test
	void explicitFloorIsUntouched() {
		Model model = new Model(argb(ComplexTestImages.createGlyphs()), BG, ALPHA,
			GeneratorConfig.DEFAULT.withMinAlphaIndex(2));
		assertEquals(2, model.getConfig().minAlphaIndex(), "an explicit floor is not auto-resolved");
	}

	@Test
	void autoResolvesEvenWithAdaptiveSizeOff_statsMapNotAttached() {
		// The S1 default already has adaptive size off; auto floor must still resolve, but the gradient map it
		// builds for the stat must NOT drive size selection (not attached to the worker).
		GeneratorConfig cfg = auto().withUseAdaptiveSize(false);
		Model model = new Model(argb(ComplexTestImages.createGlyphs()), BG, ALPHA, cfg);
		assertEquals(AppConstants.MIN_ALPHA_INDEX, model.getConfig().minAlphaIndex());
		assertNull(model.getWorker().getGradientMap(), "stats-only gradient map must not be attached to the worker");
	}

	@Test
	void generatedShapesRespectTheResolvedFloor() {
		// Hard-edge content resolves to floor 1; every generated shape's alpha index must be >= 1.
		Model model = new Model(argb(ComplexTestImages.createGlyphs()), BG, ALPHA,
			auto().withMaxRandomStates(60));
		int floor = model.getConfig().minAlphaIndex();
		assertTrue(floor >= 1);
		for (int i = 0; i < 6; i++) model.processStep();
		assertFalse(model.shapes.isEmpty());
		for (Circle shape : model.shapes) {
			assertTrue(shape.alphaIndex >= floor, "shape alpha index " + shape.alphaIndex + " below floor " + floor);
		}
	}
}
