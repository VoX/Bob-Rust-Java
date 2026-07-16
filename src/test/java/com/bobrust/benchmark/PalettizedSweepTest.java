package com.bobrust.benchmark;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.bobrust.generator.quant.PixelQuantizer;
import com.bobrust.generator.tiler.PalettizedPaintPlan;
import com.bobrust.generator.tiler.SquareBrushGeometry;
import com.bobrust.generator.tiler.SquareTiler;
import com.bobrust.util.PalettizedTimeEstimator;
import com.bobrust.util.PixelImageScaler;

/**
 * The living version of PLAN-PALETTIZED-MODE.md §8's click/time tables: the
 * real quantizer + tiler + cost model over the repo corpus, XL 512×512.
 * Numbers here supersede the Python simulation's (`palettized_sim.py` used a
 * median-cut stand-in quantizer; Wu+Oklab produces fewer stamps).
 *
 * <p>Opt-in ({@code @Tag("benchmark")}):
 * <pre>
 * ./gradlew benchmark --tests com.bobrust.benchmark.PalettizedSweepTest
 * </pre>
 */
@Tag("benchmark")
public class PalettizedSweepTest {
	private static final Map<String, String> IMAGES = new LinkedHashMap<>();
	static {
		IMAGES.put("portrait", "src/test/resources/test-images-complex/portrait.png");
		IMAGES.put("skyline", "src/test/resources/test-images-complex/skyline.png");
		IMAGES.put("photo", "src/test/resources/test-images/photo_detail.png");
		IMAGES.put("glyphs", "src/test/resources/test-images-complex/glyphs.png");
		IMAGES.put("texture", "src/test/resources/test-images-complex/texture.png");
	}

	@Test
	public void sweep() throws Exception {
		int cps = 30;
		double captureMs = 12.0;
		SquareBrushGeometry brush = SquareBrushGeometry.DEFAULT;

		System.out.println("== Palettized click/time sweep, XL 512x512, 30 cps ==");
		for (double pitch : new double[] { 3.2, 4.0 }) {
			int grid = (int) Math.ceil(512 / pitch);
			int maxSide = brush.maxSideCells(pitch);
			System.out.printf(Locale.ROOT, "%n-- pitch %.1f (grid %dx%d, max side %d) --%n",
				pitch, grid, grid, maxSide);
			System.out.println("image     |      N=32      |      N=40      |      N=64");

			for (Map.Entry<String, String> entry : IMAGES.entrySet()) {
				StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "%-9s |", entry.getKey()));
				BufferedImage source = ImageIO.read(new File(entry.getValue()));
				Rectangle rect = new Rectangle(0, 0, source.getWidth(), source.getHeight());
				BufferedImage cells = PixelImageScaler.getAreaAveragedInstance(
					source, rect, rect, grid, grid, Color.black);

				for (int n : new int[] { 32, 40, 64 }) {
					PixelQuantizer.Result quantized = PixelQuantizer.quantize(cells, n, false);
					PalettizedPaintPlan plan = SquareTiler.tile(
						quantized.labels(), grid, grid, quantized.paletteRgb(), null, maxSide);
					PalettizedTimeEstimator.Estimate estimate = PalettizedTimeEstimator.estimate(
						plan, cps, captureMs, 1, 1000);
					row.append(String.format(Locale.ROOT, " %5d st %4.1fmin |",
						estimate.stamps(), estimate.millis() / 60000.0));
				}
				System.out.println(row);
			}
		}
	}
}
