package com.bobrust.util.metrics;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import com.bobrust.generator.BorstImage;
import com.bobrust.generator.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageMetricsTest {
	@Test
	void identicalImagesAreAPerfectScore() {
		BufferedImage image = testImage();
		ImageMetrics.Result result = ImageMetrics.compare(image, image);

		assertEquals(0.0, result.rmse(), 0.0);
		assertEquals(1.0, result.ssim(), 1e-12);
		assertEquals(0.0, result.deltaE00(), 0.0);
	}

	/** RMSE follows the generator's definition: sqrt(sum(d²)/(w*h*4))/255 over ARGB. */
	@Test
	void rmseMatchesManualComputation() {
		int w = 4, h = 2;
		int[] a = new int[w * h];
		int[] b = new int[w * h];
		java.util.Arrays.fill(a, 0xFF646464);
		java.util.Arrays.fill(b, 0xFF646464);
		b[3] = 0xFF6E5A64; // +10 red, -10 green on one pixel

		double expected = Math.sqrt((10 * 10 + 10 * 10) / (w * h * 4.0)) / 255.0;
		assertEquals(expected, ImageMetrics.compare(a, b, w, h).rmse(), 1e-12);
	}

	/**
	 * The harness relies on ImageMetrics.rmse being the same number the
	 * generator reports as its score — pin them against each other on a
	 * real generation run.
	 */
	@Test
	void rmseAgreesWithModelScore() {
		BorstImage target = new BorstImage(testImage());
		// The RMSE == generator-score identity is defined for the uniform
		// energy metric; under Q1's weighted metric the generator score is
		// intentionally weighted and no longer equals plain RMSE.
		Model model = new Model(target, 0xFFFFFFFF, 128,
			com.bobrust.generator.GeneratorConfig.DEFAULT.withUsePerceptualColor(false));
		for (int i = 0; i < 5; i++) {
			model.processStep();
		}

		double rmse = ImageMetrics.compare(target, model.current).rmse();
		assertEquals(model.getScore(), rmse, 1e-6,
			"metrics RMSE must match the generator score");
	}

	@Test
	void mismatchedDimensionsAreRejected() {
		BufferedImage small = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
		BufferedImage large = new BufferedImage(20, 10, BufferedImage.TYPE_INT_ARGB);
		assertThrows(IllegalArgumentException.class, () -> ImageMetrics.compare(small, large));
	}

	@Test
	void differentImagesScoreWorseThanSimilarOnes() {
		BufferedImage original = testImage();
		BufferedImage inverted = new BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 96; y++) {
			for (int x = 0; x < 96; x++) {
				inverted.setRGB(x, y, original.getRGB(x, y) ^ 0x00FFFFFF);
			}
		}

		ImageMetrics.Result result = ImageMetrics.compare(original, inverted);
		assertTrue(result.rmse() > 0.3, "inverted image must have large RMSE, was " + result.rmse());
		assertTrue(result.ssim() < 0.5, "inverted image must have low SSIM, was " + result.ssim());
		assertTrue(result.deltaE00() > 10, "inverted image must have large dE00, was " + result.deltaE00());
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
