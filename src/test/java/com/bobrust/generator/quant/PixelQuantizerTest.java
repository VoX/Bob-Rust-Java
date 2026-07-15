package com.bobrust.generator.quant;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

import javax.imageio.ImageIO;

import com.bobrust.util.PixelImageScaler;
import com.bobrust.util.metrics.Ciede2000;
import org.junit.jupiter.api.Test;

public class PixelQuantizerTest {
	private static final String CORPUS = "src/test/resources/test-images-complex/";

	// ---------------------------------------------------------------- Oklab

	@Test
	public void oklabRoundTripsEightBitColors() {
		for (int r = 0; r < 256; r += 15) {
			for (int g = 0; g < 256; g += 15) {
				for (int b = 0; b < 256; b += 15) {
					int rgb = 0xff000000 | (r << 16) | (g << 8) | b;
					double[] lab = Oklab.fromRgb(rgb);
					assertEquals(rgb, Oklab.toRgb(lab[0], lab[1], lab[2]),
						"Oklab round trip failed for %06x".formatted(rgb & 0xffffff));
				}
			}
		}
	}

	// ------------------------------------------------------------ quantizer

	@Test
	public void quantizationIsDeterministic() throws Exception {
		BufferedImage image = loadCellGrid("portrait.png", 160, 160);
		PixelQuantizer.Result a = PixelQuantizer.quantize(image, 32, false);
		PixelQuantizer.Result b = PixelQuantizer.quantize(image, 32, false);
		assertArrayEquals(a.paletteRgb(), b.paletteRgb());
		assertArrayEquals(a.labels(), b.labels());
	}

	@Test
	public void paletteSizeIsRespected() throws Exception {
		BufferedImage image = loadCellGrid("portrait.png", 160, 160);
		for (int n : new int[] { 2, 8, 32, 64 }) {
			PixelQuantizer.Result result = PixelQuantizer.quantize(image, n, false);
			assertEquals(n, result.paletteRgb().length, "photo has plenty of colors; N must be filled");
			for (int label : result.labels()) {
				assertTrue(label >= 0 && label < n, "label out of range");
			}
		}
	}

	@Test
	public void fewDistinctColorsQuantizeLosslessly() {
		int[] colors = { 0xff2040c0, 0xffc02040, 0xff40c020, 0xfff0f0f0 };
		int w = 40, h = 40;
		int[] pixels = new int[w * h];
		for (int i = 0; i < pixels.length; i++) {
			pixels[i] = colors[(i / 7) % colors.length];
		}

		PixelQuantizer.Result result = PixelQuantizer.quantize(pixels, w, h, 8, false);
		for (int i = 0; i < pixels.length; i++) {
			assertEquals(pixels[i], result.paletteRgb()[result.labels()[i]],
				"a <=N distinct-color image must reproduce exactly");
		}
	}

	@Test
	public void beatsMedianCutBaselineOnSmoothImage() throws Exception {
		BufferedImage image = loadCellGrid("portrait.png", 160, 160);
		int w = image.getWidth(), h = image.getHeight();
		int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);

		PixelQuantizer.Result result = PixelQuantizer.quantize(pixels, w, h, 32, false);
		double ours = meanError(pixels, result.paletteRgb(), result.labels());

		int[][] baseline = medianCut(pixels, 32);
		double theirs = meanError(pixels, baseline[0], baseline[1]);

		assertTrue(ours <= theirs,
			"Wu+Oklab-Lloyd (dE00 %.4f) should not lose to median-cut (dE00 %.4f)".formatted(ours, theirs));
	}

	@Test
	public void ditherChangesLabelsButNotPalette() {
		// Horizontal gradient — the classic banding case dither exists for
		int w = 64, h = 16;
		int[] pixels = new int[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int v = 60 + (x * 130) / w;
				pixels[y * w + x] = 0xff000000 | (v << 16) | (v << 8) | v;
			}
		}
		PixelQuantizer.Result flat = PixelQuantizer.quantize(pixels, w, h, 4, false);
		PixelQuantizer.Result dithered = PixelQuantizer.quantize(pixels, w, h, 4, true);

		assertArrayEquals(flat.paletteRgb(), dithered.paletteRgb(), "palette is computed dither-free");
		assertFalse(Arrays.equals(flat.labels(), dithered.labels()), "dither must change the assignment");

		PixelQuantizer.Result again = PixelQuantizer.quantize(pixels, w, h, 4, true);
		assertArrayEquals(dithered.labels(), again.labels(), "dither is deterministic");
	}

	// --------------------------------------------------------------- scaler

	@Test
	public void areaAverageIsTheExactBlockMean() {
		// 4x4 source of known blocks -> 2x2 cells; canvas == image rect
		BufferedImage source = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		int[] values = {
			10, 30, 100, 200,
			50, 70, 220, 240,
			0, 0, 8, 8,
			0, 0, 8, 8
		};
		for (int i = 0; i < 16; i++) {
			int v = values[i];
			source.setRGB(i % 4, i / 4, 0xff000000 | (v << 16) | (v << 8) | v);
		}

		Rectangle rect = new Rectangle(0, 0, 100, 100);
		BufferedImage scaled = PixelImageScaler.getAreaAveragedInstance(source, rect, rect, 2, 2, Color.black);

		assertEquals(40, (scaled.getRGB(0, 0) >> 16) & 0xff);  // mean(10,30,50,70)
		assertEquals(190, (scaled.getRGB(1, 0) >> 16) & 0xff); // mean(100,200,220,240)
		assertEquals(0, (scaled.getRGB(0, 1) >> 16) & 0xff);
		assertEquals(8, (scaled.getRGB(1, 1) >> 16) & 0xff);
	}

	@Test
	public void semiTransparentSourceCompositesOverBackground() {
		BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
		for (int i = 0; i < 4; i++) {
			source.setRGB(i % 2, i / 2, 0x80ffffff); // 50% white
		}
		Rectangle rect = new Rectangle(0, 0, 10, 10);
		BufferedImage scaled = PixelImageScaler.getAreaAveragedInstance(source, rect, rect, 1, 1, Color.black);

		int rgb = scaled.getRGB(0, 0);
		int r = (rgb >> 16) & 0xff;
		// 50% white over black = ~128 (alpha 0x80 = 128/255)
		assertTrue(Math.abs(r - 128) <= 1, "expected ~128, got " + r);
		assertEquals(0xff, (rgb >>> 24) & 0xff, "output is opaque");
	}

	@Test
	public void uncoveredCellsAreBackground() {
		BufferedImage source = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 8; y++) {
			for (int x = 0; x < 8; x++) {
				source.setRGB(x, y, 0xffff0000);
			}
		}
		// Image occupies the left half of the canvas only
		Rectangle canvas = new Rectangle(0, 0, 100, 100);
		Rectangle image = new Rectangle(0, 0, 50, 100);
		BufferedImage scaled = PixelImageScaler.getAreaAveragedInstance(source, canvas, image, 10, 10, new Color(0xb3aba0));

		assertEquals(0xffff0000, scaled.getRGB(0, 0));
		assertEquals(0xffff0000, scaled.getRGB(4, 5));
		assertEquals(0xffb3aba0, scaled.getRGB(5, 5), "letterbox region keeps the background color");
		assertEquals(0xffb3aba0, scaled.getRGB(9, 9));
	}

	// -------------------------------------------------------------- helpers

	private static BufferedImage loadCellGrid(String name, int gridW, int gridH) throws Exception {
		BufferedImage source = ImageIO.read(new File(CORPUS + name));
		Rectangle rect = new Rectangle(0, 0, source.getWidth(), source.getHeight());
		return PixelImageScaler.getAreaAveragedInstance(source, rect, rect, gridW, gridH, Color.black);
	}

	private static double meanError(int[] pixels, int[] palette, int[] labels) {
		double sum = 0;
		for (int i = 0; i < pixels.length; i++) {
			sum += Ciede2000.deltaE(pixels[i] | 0xff000000, palette[labels[i]]);
		}
		return sum / pixels.length;
	}

	/**
	 * Compact median-cut baseline (Heckbert '82: split the box with the
	 * longest axis at the pixel median). Returns {palette, labels}.
	 */
	private static int[][] medianCut(int[] pixels, int n) {
		List<int[]> boxes = new ArrayList<>();
		int[] all = pixels.clone();
		boxes.add(all);

		while (boxes.size() < n) {
			// Split the box with the largest single-axis spread
			int bestBox = -1, bestAxis = 0, bestSpread = -1;
			for (int i = 0; i < boxes.size(); i++) {
				int[] box = boxes.get(i);
				if (box.length < 2) {
					continue;
				}
				for (int axis = 0; axis < 3; axis++) {
					int min = 255, max = 0;
					for (int pixel : box) {
						int v = (pixel >>> (16 - axis * 8)) & 0xff;
						min = Math.min(min, v);
						max = Math.max(max, v);
					}
					if (max - min > bestSpread) {
						bestSpread = max - min;
						bestBox = i;
						bestAxis = axis;
					}
				}
			}
			if (bestBox < 0 || bestSpread == 0) {
				break;
			}
			int[] box = boxes.remove(bestBox);
			final int axis = bestAxis;
			int[] sorted = Arrays.stream(box)
				.boxed()
				.sorted(Comparator.comparingInt(p -> (p >>> (16 - axis * 8)) & 0xff))
				.mapToInt(Integer::intValue)
				.toArray();
			int mid = sorted.length / 2;
			boxes.add(Arrays.copyOfRange(sorted, 0, mid));
			boxes.add(Arrays.copyOfRange(sorted, mid, sorted.length));
		}

		int[] palette = new int[boxes.size()];
		for (int i = 0; i < boxes.size(); i++) {
			long r = 0, g = 0, b = 0;
			for (int pixel : boxes.get(i)) {
				r += (pixel >>> 16) & 0xff;
				g += (pixel >>> 8) & 0xff;
				b += pixel & 0xff;
			}
			int len = Math.max(1, boxes.get(i).length);
			palette[i] = 0xff000000 | (int) (r / len) << 16 | (int) (g / len) << 8 | (int) (b / len);
		}

		int[] labels = new int[pixels.length];
		for (int i = 0; i < pixels.length; i++) {
			int best = 0;
			long bestDist = Long.MAX_VALUE;
			for (int c = 0; c < palette.length; c++) {
				long dr = ((pixels[i] >>> 16) & 0xff) - ((palette[c] >>> 16) & 0xff);
				long dg = ((pixels[i] >>> 8) & 0xff) - ((palette[c] >>> 8) & 0xff);
				long db = (pixels[i] & 0xff) - (palette[c] & 0xff);
				long dist = dr * dr + dg * dg + db * db;
				if (dist < bestDist) {
					bestDist = dist;
					best = c;
				}
			}
			labels[i] = best;
		}
		return new int[][] { palette, labels };
	}
}
