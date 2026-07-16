package com.bobrust.robot.hsv;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.imageio.ImageIO;

import com.bobrust.generator.quant.PixelQuantizer;
import com.bobrust.robot.hsv.ColorEntryController.Result;
import com.bobrust.robot.hsv.ColorEntryController.Status;
import com.bobrust.robot.hsv.ProbePlanner.ProbeResult;
import com.bobrust.util.PixelImageScaler;
import com.bobrust.util.metrics.Ciede2000;
import org.junit.jupiter.api.Test;

/**
 * The Step 4 acceptance suite (PLAN-PALETTIZED-MODE.md §11.2): a simulated
 * integer-pixel picker with injected calibration error, real corpus palettes,
 * asserting the measured Part B/D/E numbers — probe fit &lt; 1 px, adoption
 * within ΔE00 ≤ 1.0 at a 205 px picker, ≤ 6 reads, bounded in-rect clicks,
 * clean aborts, and the online-refit read saving. Deterministic.
 */
public class HsvClosedLoopTest {
	private static final int SV_PX = 205;

	/**
	 * The simulated picker (the palettized_sim.py Part B model ported):
	 * integer-pixel clicks select pixel centers, the game renders the swatch
	 * with 8-bit rounding. Optionally flipped V axis, a stuck swatch, or a
	 * global channel transform for the fault-injection tests. Records click
	 * counts and asserts every click stays inside the calibrated rects.
	 */
	private static class SimulatedPicker implements PickerSensor {
		final double x0, y0, w, h, yh0, hh;
		final boolean flipV;
		Rectangle allowedSv, allowedHue;
		Integer stuckSwatch;
		double channelGain = 1.0;
		int curX, curY, curYh;
		int clicks;

		SimulatedPicker(double x0, double y0, double w, double h, double yh0, double hh, boolean flipV) {
			this.x0 = x0;
			this.y0 = y0;
			this.w = w;
			this.h = h;
			this.yh0 = yh0;
			this.hh = hh;
			this.flipV = flipV;
		}

		static SimulatedPicker standard() {
			return new SimulatedPicker(0, 0, SV_PX, SV_PX, 0, SV_PX, false);
		}

		@Override
		public void clickHue(int yPx) {
			if (allowedHue != null) {
				assertTrue(yPx >= allowedHue.y && yPx < allowedHue.y + allowedHue.height,
					"hue click " + yPx + " left the calibrated rect " + allowedHue);
			}
			curYh = yPx;
			clicks++;
		}

		@Override
		public void clickSv(int xPx, int yPx) {
			if (allowedSv != null) {
				assertTrue(allowedSv.contains(xPx, yPx),
					"SV click " + xPx + "," + yPx + " left the calibrated rect " + allowedSv);
			}
			curX = xPx;
			curY = yPx;
			clicks++;
		}

		@Override
		public int readSwatch() {
			if (stuckSwatch != null) {
				return stuckSwatch;
			}
			double s = clamp01((curX - x0 + 0.5) / w);
			double vt = clamp01((curY - y0 + 0.5) / h);
			double v = flipV ? vt : 1.0 - vt;
			double hue = 1.0 - clamp01((curYh - yh0 + 0.5) / hh);
			int rgb = HsvColor.hsvToRgb(hue, s, v);
			if (channelGain != 1.0) {
				int r = (int) Math.round(((rgb >>> 16) & 0xff) * channelGain);
				int g = (int) Math.round(((rgb >>> 8) & 0xff) * channelGain);
				int b = (int) Math.round((rgb & 0xff) * channelGain);
				rgb = 0xff000000 | (r << 16) | (g << 8) | b;
			}
			return rgb;
		}

		@Override
		public java.awt.image.BufferedImage captureHueBar() {
			// Synthesize the bar the probe will scan: row y ↔ screen-y (allowedHue.y + y), colored
			// by this picker's own hue mapping so the scan-derived model matches what clicking does.
			Rectangle r = allowedHue != null ? allowedHue : new Rectangle((int) yh0, (int) yh0, 15, (int) hh);
			int w = Math.max(1, r.width), h = Math.max(1, r.height);
			var img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
			for (int y = 0; y < h; y++) {
				double hue = 1.0 - clamp01((r.y + y - yh0 + 0.5) / hh);
				int rgb = HsvColor.hsvToRgb(hue, 1.0, 1.0);
				if (channelGain != 1.0) {
					int rr = Math.min(255, (int) Math.round(((rgb >>> 16) & 0xff) * channelGain));
					int gg = Math.min(255, (int) Math.round(((rgb >>> 8) & 0xff) * channelGain));
					int bb = Math.min(255, (int) Math.round((rgb & 0xff) * channelGain));
					rgb = (rr << 16) | (gg << 8) | bb;
				}
				for (int x = 0; x < w; x++) {
					img.setRGB(x, y, rgb);
				}
			}
			return img;
		}

		private static double clamp01(double t) {
			return t < 0 ? 0 : Math.min(t, 1);
		}
	}

	/** The user-marked rects: ±3 px offset and 2% scale off the truth (§11.2). */
	private static Rectangle markedSvRect() {
		return new Rectangle(-3, 3, (int) (SV_PX * 1.02), (int) (SV_PX * 1.02));
	}

	private static Rectangle markedHueRect() {
		return new Rectangle(300, 3, 50, (int) (SV_PX * 1.02));
	}

	// ------------------------------------------------------------ HSV math

	@Test
	public void hsvRoundTripsEightBitColors() {
		for (int r = 0; r < 256; r += 15) {
			for (int g = 0; g < 256; g += 15) {
				for (int b = 0; b < 256; b += 15) {
					int rgb = 0xff000000 | (r << 16) | (g << 8) | b;
					double[] hsv = HsvColor.rgbToHsv(rgb);
					assertEquals(rgb, HsvColor.hsvToRgb(hsv[0], hsv[1], hsv[2]),
						"HSV round trip failed for %06x".formatted(rgb & 0xffffff));

					// Match java.awt.Color semantics
					float[] awt = Color.RGBtoHSB(r, g, b, null);
					assertEquals(awt[0], hsv[0], 1e-4);
					assertEquals(awt[1], hsv[1], 1e-4);
					assertEquals(awt[2], hsv[2], 1e-4);
				}
			}
		}
	}

	@Test
	public void modelSerializationRoundTrips() {
		HsvPickerModel model = new HsvPickerModel(
			new HsvPickerModel.Axis(12.25, 204.5),
			new HsvPickerModel.Axis(-3.5, 207.125),
			new HsvPickerModel.Axis(410.0, -205.0));
		HsvPickerModel parsed = HsvPickerModel.parse(model.serialize());
		assertEquals(model, parsed);

		assertNull(HsvPickerModel.parse(null));
		assertNull(HsvPickerModel.parse(""));
		assertNull(HsvPickerModel.parse("x0=1;wx=2"), "missing keys are invalid");
		assertNull(HsvPickerModel.parse("x0=a;wx=2;y0=0;wy=1;h0=0;wh=1"), "malformed is invalid");
	}

	// ------------------------------------------------------------ probe fit

	@Test
	public void probeFitRecoversGeometryWithinOnePixel() throws Exception {
		SimulatedPicker picker = SimulatedPicker.standard();
		picker.allowedSv = markedSvRect();
		picker.allowedHue = markedHueRect();
		ProbeResult result = ProbePlanner.probe(picker, markedSvRect(), markedHueRect(),
			ProbePlanner.DEFAULT_PROBES_PER_AXIS);

		assertTrue(result.ok(), () -> "probe failed: " + result.failure());
		assertTrue(result.maxResidualPx() < 1.0,
			"fit residual %.3f px should be sub-pixel".formatted(result.maxResidualPx()));

		HsvPickerModel model = result.model();
		assertEquals(0.0, model.s().offset(), 1.0, "S offset");
		assertEquals(SV_PX, model.s().scale(), 2.0, "S scale");
		assertEquals(0.0, model.v().offset(), 1.0, "V offset");
		assertEquals(SV_PX, model.v().scale(), 2.0, "V scale");
		assertEquals(0.0, model.h().offset(), 1.0, "H offset");
		assertEquals(SV_PX, model.h().scale(), 2.0, "H scale");
	}

	@Test
	public void probeFitsAFlippedAxisAsANegativeScale() throws Exception {
		SimulatedPicker picker = new SimulatedPicker(0, 0, SV_PX, SV_PX, 0, SV_PX, true);
		ProbeResult result = ProbePlanner.probe(picker, markedSvRect(), markedHueRect(),
			ProbePlanner.DEFAULT_PROBES_PER_AXIS);

		assertTrue(result.ok(), () -> "probe failed: " + result.failure());
		assertTrue(result.model().v().scale() < 0,
			"a flipped V axis must fit a negative scale, got " + result.model().v().scale());
	}

	@Test
	public void probeFailsClosedWhenThePickerIsNotHsv() throws Exception {
		// Legacy-grid mode: the swatch never responds to picker clicks
		SimulatedPicker picker = SimulatedPicker.standard();
		picker.stuckSwatch = 0xff885522;

		ProbeResult result = ProbePlanner.probe(picker, markedSvRect(), markedHueRect(),
			ProbePlanner.DEFAULT_PROBES_PER_AXIS);
		assertFalse(result.ok());
		assertNotNull(result.failure());
	}

	@Test
	public void probeRefusesAGlobalScreenColorTransform() throws Exception {
		// "Night mode": every channel dimmed 25%. The per-axis fits stay
		// linear so only the absolute reference gate can catch this.
		SimulatedPicker picker = SimulatedPicker.standard();
		picker.channelGain = 0.75;

		ProbeResult result = ProbePlanner.probe(picker, markedSvRect(), markedHueRect(),
			ProbePlanner.DEFAULT_PROBES_PER_AXIS);
		assertFalse(result.ok(), "a global color transform must refuse to paint");
		assertNotNull(result.failure());
	}

	// ---------------------------------------------------------- closed loop

	@Test
	public void closedLoopAdoptsEveryCorpusColorWithinOneJnd() throws Exception {
		int[] palette = corpusPalette();
		assertTrue(palette.length >= 30, "expected a rich test palette, got " + palette.length);

		SimulatedPicker picker = SimulatedPicker.standard();
		Rectangle svRect = markedSvRect();
		Rectangle hueRect = markedHueRect();
		picker.allowedSv = svRect;
		picker.allowedHue = hueRect;

		ProbeResult probe = ProbePlanner.probe(picker, svRect, hueRect, ProbePlanner.DEFAULT_PROBES_PER_AXIS);
		assertTrue(probe.ok(), () -> "probe failed: " + probe.failure());

		ColorEntryController controller = new ColorEntryController(picker, probe.model(), svRect, hueRect);
		int maxReads = 0;
		double maxDeltaE = 0;
		for (int target : palette) {
			int before = picker.clicks;
			Result result = controller.enter(target);

			assertEquals(Status.ADOPTED, result.status(),
				"color %06x failed to adopt (best dE00 %.2f)".formatted(target & 0xffffff, result.deltaE()));
			double deltaE = Ciede2000.deltaE(result.adoptedRgb(), target);
			assertTrue(deltaE <= 1.0,
				"color %06x adopted at dE00 %.3f > 1.0".formatted(target & 0xffffff, deltaE));
			assertTrue(result.reads() <= ColorEntryController.DEFAULT_MAX_READS);
			assertTrue(picker.clicks - before <= 2 * ColorEntryController.DEFAULT_MAX_READS,
				"clicks per color must stay bounded");

			maxReads = Math.max(maxReads, result.reads());
			maxDeltaE = Math.max(maxDeltaE, deltaE);
		}
		System.out.printf("closed loop over %d colors: max reads %d, max dE00 %.3f%n",
			palette.length, maxReads, maxDeltaE);
	}

	@Test
	public void looseAdoptionLeavesThePickerOnTheAdoptedColor() throws Exception {
		// Regression: on the loose-exhaustion path the controller adopts the best-so-far
		// read (captured at some earlier nudge), but the picker is physically left on the
		// LAST clicked position. PalettizedPainter paints the picker's live color and never
		// re-issues per stamp — so the canvas would get a DIFFERENT color than adoptedPalette
		// records (silent corruption, measured up to dE00 ~11). After the fix the picker must
		// end physically on the adopted color's position.
		int[] palette = corpusPalette();
		SimulatedPicker picker = SimulatedPicker.standard();
		Rectangle svRect = markedSvRect();
		Rectangle hueRect = markedHueRect();
		picker.allowedSv = svRect;
		picker.allowedHue = hueRect;

		ProbeResult probe = ProbePlanner.probe(picker, svRect, hueRect, ProbePlanner.DEFAULT_PROBES_PER_AXIS);
		assertTrue(probe.ok(), () -> "probe failed: " + probe.failure());

		// accept=0 (no gained read is ever byte-exact) + a generous loose bound forces EVERY
		// color down the loose-exhaustion path; refit off so the gain error persists and the loop
		// nudges AWAY from the best (first) position — i.e. best != last, the exact bug shape.
		picker.channelGain = 1.01;
		ColorEntryController controller = new ColorEntryController(picker, probe.model(), svRect, hueRect,
			ColorEntryController.DEFAULT_MAX_READS, 0.0, 5.0, false);

		int looseSeen = 0;
		for (int target : palette) {
			Result result = controller.enter(target);
			if (result.status() != Status.ADOPTED) {
				continue;
			}
			// THE CONTRACT (§2.4): the picker's live swatch == the color we recorded to paint.
			assertEquals(result.adoptedRgb() & 0xffffff, picker.readSwatch() & 0xffffff,
				"picker's live color must equal the adopted color for %06x (loose=%s)"
					.formatted(target & 0xffffff, result.loose()));
			if (result.loose()) {
				looseSeen++;
			}
		}
		assertTrue(looseSeen > 0, "test must actually exercise the loose-adoption path");
	}

	@Test
	public void closedLoopConvergesOnAFlippedPicker() throws Exception {
		SimulatedPicker picker = new SimulatedPicker(0, 0, SV_PX, SV_PX, 0, SV_PX, true);
		Rectangle svRect = markedSvRect();
		Rectangle hueRect = markedHueRect();

		ProbeResult probe = ProbePlanner.probe(picker, svRect, hueRect, ProbePlanner.DEFAULT_PROBES_PER_AXIS);
		assertTrue(probe.ok(), () -> "probe failed: " + probe.failure());

		ColorEntryController controller = new ColorEntryController(picker, probe.model(), svRect, hueRect);
		for (int target : new int[] { 0xffff0000, 0xff295a66, 0xff10c040, 0xff777777, 0xff000000, 0xffffffff }) {
			Result result = controller.enter(target);
			assertEquals(Status.ADOPTED, result.status(), "flipped picker, color %06x".formatted(target & 0xffffff));
			assertTrue(Ciede2000.deltaE(result.adoptedRgb(), target) <= 1.0);
		}
	}

	@Test
	public void unconvergeableSensorAbortsAfterTheBudget() throws Exception {
		SimulatedPicker picker = SimulatedPicker.standard();
		Rectangle svRect = markedSvRect();
		Rectangle hueRect = markedHueRect();
		picker.allowedSv = svRect;
		picker.allowedHue = hueRect;
		picker.stuckSwatch = 0xff888888; // swatch never changes

		ColorEntryController controller = new ColorEntryController(
			picker, HsvPickerModel.fromRects(svRect, hueRect), svRect, hueRect);
		Result result = controller.enter(0xffff0000);

		assertEquals(Status.FAILED, result.status(), "a stuck swatch must fail, never soldier on");
		assertTrue(result.reads() <= ColorEntryController.DEFAULT_MAX_READS,
			"reads bounded, got " + result.reads());
		assertTrue(result.clicks() <= 2 * ColorEntryController.DEFAULT_MAX_READS,
			"clicks bounded, got " + result.clicks());
	}

	@Test
	public void onlineRefitShrinksReadsUnderModelError() throws Exception {
		int[] palette = corpusPalette();

		// No probe pass: the deliberately-off marked-rect prior is the model,
		// so the refit has real error to learn away (sim Part C).
		int readsWithRefit = sequentialReads(palette, true);
		int readsWithoutRefit = sequentialReads(palette, false);

		assertTrue(readsWithRefit < readsWithoutRefit,
			"online refit should cut reads: with=%d without=%d".formatted(readsWithRefit, readsWithoutRefit));
	}

	private static int sequentialReads(int[] palette, boolean refit) throws Exception {
		SimulatedPicker picker = SimulatedPicker.standard();
		Rectangle svRect = markedSvRect();
		Rectangle hueRect = markedHueRect();
		ColorEntryController controller = new ColorEntryController(
			picker, HsvPickerModel.fromRects(svRect, hueRect), svRect, hueRect,
			ColorEntryController.DEFAULT_MAX_READS, ColorEntryController.DEFAULT_ACCEPT,
			ColorEntryController.DEFAULT_ACCEPT_LOOSE, refit);

		int total = 0;
		for (int target : palette) {
			Result result = controller.enter(target);
			assertEquals(Status.ADOPTED, result.status(),
				"color %06x should adopt even from the unfitted prior".formatted(target & 0xffffff));
			total += result.reads();
		}
		return total;
	}

	// -------------------------------------------------------------- helpers

	/** N=40 quantizer centroids of the portrait corpus image, deduplicated. */
	private static int[] corpusPalette() throws Exception {
		BufferedImage source = ImageIO.read(new File("src/test/resources/test-images-complex/portrait.png"));
		Rectangle rect = new Rectangle(0, 0, source.getWidth(), source.getHeight());
		BufferedImage cells = PixelImageScaler.getAreaAveragedInstance(source, rect, rect, 160, 160, Color.black);
		PixelQuantizer.Result quantized = PixelQuantizer.quantize(cells, 40, false);

		Set<Integer> distinct = new LinkedHashSet<>();
		for (int rgb : quantized.paletteRgb()) {
			distinct.add(rgb | 0xff000000);
		}
		return distinct.stream().mapToInt(Integer::intValue).toArray();
	}
}
