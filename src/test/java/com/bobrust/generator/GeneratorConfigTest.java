package com.bobrust.generator;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeneratorConfigTest {
	/**
	 * Pins the shipping defaults: the Phase-G tuned values, each backed by an
	 * F1 benchmark run (classic hill climb, proxy candidate ranking, 500
	 * candidates — see PERFORMANCE-PLAN.md). The pre-G behavior stays
	 * reachable via "sa=true;proxy=false;states=1000;age=100".
	 */
	@Test
	void defaultsArePhaseGTunedValues() {
		GeneratorConfig config = GeneratorConfig.DEFAULT;
		assertEquals(0L, config.seed());
		assertFalse(config.useSimulatedAnnealing(), "G1: classic hill climb is the default");
		assertTrue(config.useErrorGuidedPlacement());
		assertFalse(config.useAdaptiveSize(), "P1: adaptive size loses at a click budget -- see SPEED-QUALITY-PROPOSALS.md");
		assertTrue(config.useBatchParallel());
		assertTrue(config.useProxyRanking(), "G2: proxy candidate ranking is the default");
		assertTrue(config.usePerceptualColor(), "Q1: perceptual color metric is the default");
		assertTrue(config.usePerShapeAlpha(), "Q2: per-shape alpha search is the default");
		assertEquals(1, config.minAlphaIndex(), "Q2: harness-validated alpha floor");
		assertEquals(500, config.maxRandomStates(), "G3: harness-validated candidate count");
		assertEquals(100, config.age());
	}

	@Test
	void serializeParseRoundTrips() {
		assertEquals(GeneratorConfig.DEFAULT, GeneratorConfig.parse(GeneratorConfig.DEFAULT.serialize()));

		GeneratorConfig custom = new GeneratorConfig(42L, false, false, true, false, false, true, true, 3, 750, 50);
		assertEquals(custom, GeneratorConfig.parse(custom.serialize()));
	}

	@Test
	void unsetOrBlankParsesToDefault() {
		assertEquals(GeneratorConfig.DEFAULT, GeneratorConfig.parse(null));
		assertEquals(GeneratorConfig.DEFAULT, GeneratorConfig.parse(""));
		assertEquals(GeneratorConfig.DEFAULT, GeneratorConfig.parse("   "));
	}

	@Test
	void partialConfigKeepsRemainingDefaults() {
		GeneratorConfig config = GeneratorConfig.parse("sa=false;age=50");
		assertFalse(config.useSimulatedAnnealing());
		assertEquals(50, config.age());
		// everything else stays default
		assertEquals(GeneratorConfig.DEFAULT.seed(), config.seed());
		assertEquals(GeneratorConfig.DEFAULT.useErrorGuidedPlacement(), config.useErrorGuidedPlacement());
		assertEquals(GeneratorConfig.DEFAULT.useAdaptiveSize(), config.useAdaptiveSize());
		assertEquals(GeneratorConfig.DEFAULT.useBatchParallel(), config.useBatchParallel());
		assertEquals(GeneratorConfig.DEFAULT.useProxyRanking(), config.useProxyRanking());
		assertEquals(GeneratorConfig.DEFAULT.maxRandomStates(), config.maxRandomStates());
	}

	@Test
	void malformedValuesFallBackPerKey() {
		GeneratorConfig config = GeneratorConfig.parse("seed=abc;states=0;age=-5;unknown=1;noequals;age=25");
		assertEquals(GeneratorConfig.DEFAULT.seed(), config.seed(), "unparseable seed keeps default");
		assertEquals(GeneratorConfig.DEFAULT.maxRandomStates(), config.maxRandomStates(), "out-of-range states keeps default");
		assertEquals(25, config.age(), "later valid key wins");
	}

	@Test
	void invalidValuesAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> GeneratorConfig.DEFAULT.withMaxRandomStates(0));
		assertThrows(IllegalArgumentException.class, () -> GeneratorConfig.DEFAULT.withAge(0));
	}

	/** The config seed is actually consumed: same seed ⇒ identical, different seed ⇒ different. */
	@Test
	void seedControlsGeneration() {
		BufferedImage image = testImage();

		Model sameA = new Model(new BorstImage(image), 0xFFFFFFFF, 128, GeneratorConfig.DEFAULT.withSeed(123));
		Model sameB = new Model(new BorstImage(image), 0xFFFFFFFF, 128, GeneratorConfig.DEFAULT.withSeed(123));
		Model other = new Model(new BorstImage(image), 0xFFFFFFFF, 128, GeneratorConfig.DEFAULT.withSeed(999));
		for (int i = 0; i < 10; i++) {
			sameA.processStep();
			sameB.processStep();
			other.processStep();
		}

		assertArrayEquals(sameA.current.pixels, sameB.current.pixels, "same seed must render identically");
		assertFalse(java.util.Arrays.equals(sameA.current.pixels, other.current.pixels),
			"different seed must render differently");
	}

	/** The SA toggle is consumed at runtime: classic hill climb must also run cleanly. */
	@Test
	void classicHillClimbConfigRuns() {
		GeneratorConfig classic = GeneratorConfig.DEFAULT
			.withUseSimulatedAnnealing(false)
			.withMaxRandomStates(200)
			.withAge(20);
		Model model = new Model(new BorstImage(testImage()), 0xFFFFFFFF, 128, classic);
		for (int i = 0; i < 5; i++) {
			model.processStep();
		}

		assertEquals(5, model.shapes.size());
		assertFalse(Float.isNaN(model.getScore()));
	}

	@Test
	void defaultModelHasNoGradientMap() {
		// P1: adaptive size is off by default, so the default Model builds no GradientMap; enabling it per config
		// brings the map back. (getWorker() is package-private; this test lives in com.bobrust.generator.)
		BorstImage target = new BorstImage(testImage());
		Model defaultModel = new Model(target, 0xFFFFFFFF, 128, GeneratorConfig.DEFAULT);
		assertNull(defaultModel.getWorker().getGradientMap(), "P1: default has adaptive size off -> no gradient map");

		Model adaptiveModel = new Model(target, 0xFFFFFFFF, 128, GeneratorConfig.DEFAULT.withUseAdaptiveSize(true));
		assertNotNull(adaptiveModel.getWorker().getGradientMap(), "adaptiveSize=true rebuilds the gradient map");
	}

	@Test
	void autoMinAlphaSentinelParsesRoundTripsAndValidates() {
		// S2: minAlpha=-1 (MIN_ALPHA_AUTO) is accepted, survives serialize -> parse, and out-of-range still throws.
		GeneratorConfig cfg = GeneratorConfig.parse("minAlpha=-1");
		assertEquals(GeneratorConfig.MIN_ALPHA_AUTO, cfg.minAlphaIndex());
		GeneratorConfig round = GeneratorConfig.parse(cfg.serialize());
		assertEquals(GeneratorConfig.MIN_ALPHA_AUTO, round.minAlphaIndex(), "auto sentinel round-trips through serialize");
		assertDoesNotThrow(() -> GeneratorConfig.DEFAULT.withMinAlphaIndex(GeneratorConfig.MIN_ALPHA_AUTO));
		assertThrows(IllegalArgumentException.class, () -> GeneratorConfig.DEFAULT.withMinAlphaIndex(-2));
		assertThrows(IllegalArgumentException.class, () -> GeneratorConfig.DEFAULT.withMinAlphaIndex(6));
	}

	private static BufferedImage testImage() {
		BufferedImage img = new BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, 96, 96);
		g.setColor(Color.GREEN);
		g.fillOval(10, 10, 50, 50);
		g.setColor(Color.ORANGE);
		g.fillRect(40, 40, 45, 45);
		g.dispose();
		return img;
	}
}
