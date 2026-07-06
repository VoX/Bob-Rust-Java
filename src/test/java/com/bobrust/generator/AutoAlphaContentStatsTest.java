package com.bobrust.generator;

import java.awt.image.BufferedImage;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the auto-alpha content classifier constants ({@link GradientMap#HARD_EDGE_MAGNITUDE},
 * {@link Model#AUTO_ALPHA_EDGE_THRESHOLD}) against the 5-image complex corpus. Fast: 5 Sobel passes at 384px,
 * no generation. Hard-edge/text images (glyphs, mosaic) must land ABOVE the threshold, photographic ones
 * (texture, portrait) BELOW it, with a comfortable margin. skyline is a measured don't-care (only prints).
 */
class AutoAlphaContentStatsTest {

	private static float hardEdgeFraction(BufferedImage img) {
		BorstImage target = new BorstImage(img);
		GradientMap map = new GradientMap(target.width, target.height);
		map.compute(target);
		return map.getHardEdgeFraction();
	}

	@Test
	void corpusEdgeStatsSeparateContentClasses() {
		Map<String, BufferedImage> corpus = ComplexTestImages.corpus();
		StringBuilder sb = new StringBuilder("hardEdgeFraction (HARD_EDGE_MAGNITUDE=" + GradientMap.HARD_EDGE_MAGNITUDE
			+ ", AUTO_ALPHA_EDGE_THRESHOLD=" + Model.AUTO_ALPHA_EDGE_THRESHOLD + "):\n");
		for (Map.Entry<String, BufferedImage> e : corpus.entrySet()) {
			sb.append(String.format("  %-9s = %.5f%n", e.getKey(), hardEdgeFraction(e.getValue())));
		}
		System.out.print(sb);

		float texture = hardEdgeFraction(corpus.get("texture"));
		float portrait = hardEdgeFraction(corpus.get("portrait"));
		float glyphs = hardEdgeFraction(corpus.get("glyphs"));
		float mosaic = hardEdgeFraction(corpus.get("mosaic"));
		float t = Model.AUTO_ALPHA_EDGE_THRESHOLD;

		assertTrue(glyphs >= t, "glyphs (hard-edge) must classify as edgy: " + glyphs);
		assertTrue(mosaic >= t, "mosaic (hard-edge) must classify as edgy: " + mosaic);
		assertTrue(texture < t, "texture (photographic) must classify as smooth: " + texture);
		assertTrue(portrait < t, "portrait (photographic) must classify as smooth: " + portrait);
		// >=2x separation margin between the closest edgy image and the threshold, and threshold and closest smooth
		float closestEdgy = Math.min(glyphs, mosaic);
		float closestSmooth = Math.max(texture, portrait);
		assertTrue(closestEdgy >= 2 * t, "edgy margin too thin: min(glyphs,mosaic)=" + closestEdgy + " vs 2*threshold=" + (2 * t));
		assertTrue(closestSmooth <= t / 2, "smooth margin too thin: max(texture,portrait)=" + closestSmooth + " vs threshold/2=" + (t / 2));
	}
}
