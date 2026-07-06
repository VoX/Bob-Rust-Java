package com.bobrust.util.metrics;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsimTest {
	private static final int SIZE = 32;

	@Test
	void identicalImagesScoreExactlyOne() {
		int[] image = patternImage(SIZE);
		assertEquals(1.0, Ssim.compare(image, image.clone(), SIZE, SIZE), 1e-12);
	}

	/**
	 * Two flat images have zero variance, so SSIM reduces analytically to the
	 * luminance term: (2·μa·μb + C1) / (μa² + μb² + C1) with C1 = (0.01·255)².
	 */
	@Test
	void flatImagesMatchAnalyticValue() {
		int[] flatA = flatGray(16, 100);
		int[] flatB = flatGray(16, 110);

		double muA = luma(100);
		double muB = luma(110);
		double c1 = Math.pow(0.01 * 255, 2);
		double expected = (2 * muA * muB + c1) / (muA * muA + muB * muB + c1);

		assertEquals(expected, Ssim.compare(flatA, flatB, 16, 16), 1e-6);
	}

	@Test
	void degradesMonotonicallyWithNoise() {
		int[] original = patternImage(SIZE);
		double slightNoise = Ssim.compare(original, noisy(original, 8, 42), SIZE, SIZE);
		double heavyNoise = Ssim.compare(original, noisy(original, 64, 42), SIZE, SIZE);

		assertTrue(slightNoise < 1.0, "noise must reduce SSIM, was " + slightNoise);
		assertTrue(heavyNoise < slightNoise,
			"more noise must score lower: " + heavyNoise + " !< " + slightNoise);
		assertTrue(heavyNoise > -1.0);
	}

	@Test
	void isSymmetric() {
		int[] a = patternImage(SIZE);
		int[] b = noisy(a, 20, 7);
		assertEquals(Ssim.compare(a, b, SIZE, SIZE), Ssim.compare(b, a, SIZE, SIZE), 1e-12);
	}

	/** Images smaller than the 11x11 window use the global fallback. */
	@Test
	void tinyImagesUseGlobalFallback() {
		int[] tiny = patternImage(8);
		assertEquals(1.0, Ssim.compare(tiny, tiny.clone(), 8, 8), 1e-12);

		double different = Ssim.compare(tiny, flatGray(8, 128), 8, 8);
		assertTrue(different < 1.0);
	}

	private static double luma(int gray) {
		return 0.299 * gray + 0.587 * gray + 0.114 * gray;
	}

	private static int[] flatGray(int size, int value) {
		int[] pixels = new int[size * size];
		Arrays.fill(pixels, 0xFF000000 | (value << 16) | (value << 8) | value);
		return pixels;
	}

	/** Deterministic structured test image (diagonal gradient + blocks). */
	private static int[] patternImage(int size) {
		int[] pixels = new int[size * size];
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int v = ((x + y) * 255 / (2 * size - 2));
				if ((x / 8 + y / 8) % 2 == 0) {
					v = 255 - v;
				}
				pixels[y * size + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
			}
		}
		return pixels;
	}

	private static int[] noisy(int[] source, int amplitude, long seed) {
		Random random = new Random(seed);
		int[] result = new int[source.length];
		for (int i = 0; i < source.length; i++) {
			int v = (source[i] >>> 16) & 0xff;
			v = Math.max(0, Math.min(255, v + random.nextInt(2 * amplitude + 1) - amplitude));
			result[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
		}
		return result;
	}
}
