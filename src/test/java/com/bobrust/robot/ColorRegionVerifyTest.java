package com.bobrust.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

/**
 * Tests for the color preview region verification helpers in
 * {@link BobRustPainter}. The Robot/screen capture path is not testable
 * headless — these cover the pure pixel logic only.
 */
public class ColorRegionVerifyTest {

	@Test
	public void identicalRegionsAreUnchanged() {
		int[] region = { 0xFF102030, 0xFF405060, 0xFF708090 };
		assertFalse(BobRustPainter.regionChanged(region, region.clone()));
	}

	@Test
	public void singlePixelDifferenceIsDetected() {
		int[] before = { 0xFF102030, 0xFF405060, 0xFF708090 };

		int[] middle = before.clone();
		middle[1] = 0xFF000000;
		assertTrue(BobRustPainter.regionChanged(before, middle));

		int[] first = before.clone();
		first[0] = 0xFF000000;
		assertTrue(BobRustPainter.regionChanged(before, first));

		int[] last = before.clone();
		last[2] = 0xFF000000;
		assertTrue(BobRustPainter.regionChanged(before, last));
	}

	@Test
	public void degenerateInputsAreSafe() {
		assertFalse(BobRustPainter.regionChanged(null, null));
		assertFalse(BobRustPainter.regionChanged(null, new int[] { 1 }));
		assertFalse(BobRustPainter.regionChanged(new int[] { 1 }, null));
		assertFalse(BobRustPainter.regionChanged(new int[0], new int[0]));
		assertFalse(BobRustPainter.regionChanged(new int[] { 1 }, new int[] { 1, 2 }));
	}

	@Test
	public void extractDiscKeepsOnlyPixelsWithinRadius() {
		BufferedImage image = fillUnique(new BufferedImage(11, 11, BufferedImage.TYPE_INT_RGB));

		int[] disc = BobRustPainter.extractDisc(image, 5, 5, 5);

		// Integer points with dx^2 + dy^2 <= 25 inside an 11x11 box
		assertEquals(81, disc.length);

		// The corner pixel (0,0) is ~7.07px from the center and must be excluded
		int corner = image.getRGB(0, 0);
		for (int pixel : disc) {
			assertNotEquals(corner, pixel);
		}
	}

	@Test
	public void discDetectsChangeInsideButIgnoresCorners() {
		BufferedImage before = fillUnique(new BufferedImage(11, 11, BufferedImage.TYPE_INT_RGB));

		// A pixel inside the disc changes -> detected
		BufferedImage inside = fillUnique(new BufferedImage(11, 11, BufferedImage.TYPE_INT_RGB));
		inside.setRGB(2, 5, 0x00FFFFFF);
		assertTrue(BobRustPainter.regionChanged(
			BobRustPainter.extractDisc(before, 5, 5, 5),
			BobRustPainter.extractDisc(inside, 5, 5, 5)));

		// Only a corner outside the disc changes -> not detected
		BufferedImage corner = fillUnique(new BufferedImage(11, 11, BufferedImage.TYPE_INT_RGB));
		corner.setRGB(0, 0, 0x00FFFFFF);
		assertFalse(BobRustPainter.regionChanged(
			BobRustPainter.extractDisc(before, 5, 5, 5),
			BobRustPainter.extractDisc(corner, 5, 5, 5)));
	}

	@Test
	public void extractDiscHandlesClampedCaptureAtScreenEdge() {
		// A capture clamped to the screen edge leaves the center outside the
		// image. Only x <= 3 can still be within radius 5 of (-2, 5)
		BufferedImage image = fillUnique(new BufferedImage(6, 11, BufferedImage.TYPE_INT_RGB));

		int[] disc = BobRustPainter.extractDisc(image, -2, 5, 5);
		assertEquals(26, disc.length);
	}

	private static BufferedImage fillUnique(BufferedImage image) {
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				image.setRGB(x, y, 0xFF000000 | (y * image.getWidth() + x + 1));
			}
		}
		return image;
	}
}
