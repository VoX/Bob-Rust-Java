package com.bobrust.robot;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

import com.bobrust.generator.quant.PixelQuantizer;
import com.bobrust.generator.tiler.PalettizedPaintPlan;
import com.bobrust.generator.tiler.SquareBrushGeometry;
import com.bobrust.generator.tiler.SquareTiler;
import com.bobrust.robot.error.PaintingInterrupted;
import com.bobrust.robot.error.PaintingInterrupted.InterruptType;
import com.bobrust.robot.hsv.HsvColor;
import com.bobrust.robot.io.RobotIO;
import com.bobrust.util.PixelImageScaler;
import org.junit.jupiter.api.Test;

/**
 * The §11.5 offline end-to-end: a {@code FakeGame} implements {@link RobotIO}
 * with a virtual HSV picker, virtual SIZE/OPACITY fields with real keystroke
 * parsing, and a virtual canvas that stamps {@code side = a·SIZE + b}
 * squares at click positions through the painter's real screen-mapping math.
 * {@link PalettizedPainter} runs unmodified against it — the headline
 * assertion is byte-exact equality between the final virtual canvas and the
 * plan rendered in the adopted palette.
 */
public class PalettizedPainterTest {
	private static final int SIGN = 512;
	private static final double PITCH = 3.2;
	private static final int GRID = 160; // 512 / 3.2
	private static final double BRUSH_A = 3.125, BRUSH_B = 0.0;
	private static final int BASE_COLOR = 0xffb3aba0;
	private static final int GREEN_FILL = 0xff58c832;
	private static final int DARK_TRACK = 0xff2a2a24;

	// ------------------------------------------------------------- FakeGame

	private static class FakeGame implements RobotIO {
		final ButtonConfiguration buttons = testButtons();
		final Rectangle canvasArea = new Rectangle(100, 100, 1344, 1344);
		final Rectangle svRect = new Rectangle(1600, 100, 205, 205);
		final Rectangle hueRect = new Rectangle(1830, 100, 40, 205);
		final Rectangle swatchRect = new Rectangle(1600, 350, 90, 60);

		// Virtual state
		double time;
		Point mouse = new Point(0, 0);
		int svX, svY, hueY;
		int brush = 1; // 1 = circle (game default), 3 = square
		double sizeValue = 7.01, opacityValue = 0.20; // the screenshot's defaults
		String clipboard = "";
		StringBuilder fieldBuffer = new StringBuilder();
		Integer focusedField; // 0 = SIZE, 1 = OPACITY
		boolean ctrlDown;
		final int[] canvas = new int[GRID * GRID];

		// Counters + fault injection
		int canvasClicks, saveClicks, leakedKeys, sizeCommits;
		boolean stuckSwatch;
		int breakSizeFocusAfterCommits = Integer.MAX_VALUE;
		int mouseAwayAfterCanvasClicks = Integer.MAX_VALUE;

		FakeGame() {
			java.util.Arrays.fill(canvas, BASE_COLOR);
		}

		static ButtonConfiguration testButtons() {
			ButtonConfiguration b = new ButtonConfiguration();
			b.focus = new ButtonConfiguration.Coordinate(1500, 60);
			b.clearCanvas = new ButtonConfiguration.Coordinate(1600, 60);
			b.saveImage = new ButtonConfiguration.Coordinate(1700, 60);
			b.brush_circle = new ButtonConfiguration.Coordinate(1600, 620);
			b.brush_square = new ButtonConfiguration.Coordinate(1640, 620);
			b.size_1 = new ButtonConfiguration.Coordinate(1600, 520);
			b.size_32 = new ButtonConfiguration.Coordinate(1745, 520);
			b.opacity_0 = new ButtonConfiguration.Coordinate(1600, 580);
			b.opacity_1 = new ButtonConfiguration.Coordinate(1745, 580);
			b.sizeField = new ButtonConfiguration.Coordinate(1700, 500);
			b.opacityField = new ButtonConfiguration.Coordinate(1700, 560);
			b.hsvSquare_topLeft = new ButtonConfiguration.Coordinate(1600, 100);
			b.hsvSquare_botRight = new ButtonConfiguration.Coordinate(1805, 305);
			b.hueBar_topLeft = new ButtonConfiguration.Coordinate(1830, 100);
			b.hueBar_botRight = new ButtonConfiguration.Coordinate(1870, 305);
			b.swatch_topLeft = new ButtonConfiguration.Coordinate(1600, 350);
			b.swatch_botRight = new ButtonConfiguration.Coordinate(1690, 410);
			return b;
		}

		// ------------------------------------------------------ interaction

		@Override
		public void mouseMove(int x, int y) {
			mouse = new Point(x, y);
		}

		@Override
		public void mousePress() {
			handleClick(mouse);
		}

		@Override
		public void mouseRelease() {
		}

		private void handleClick(Point p) {
			if (nearRect(svRect, p)) {
				svX = clamp(p.x, svRect.x, svRect.x + svRect.width - 1);
				svY = clamp(p.y, svRect.y, svRect.y + svRect.height - 1);
				focusedField = null;
			} else if (nearRect(hueRect, p)) {
				hueY = clamp(p.y, hueRect.y, hueRect.y + hueRect.height - 1);
				focusedField = null;
			} else if (near(buttons.sizeField, p)) {
				focusedField = (sizeCommits >= breakSizeFocusAfterCommits) ? null : 0;
				fieldBuffer.setLength(0);
			} else if (near(buttons.opacityField, p)) {
				focusedField = 1;
				fieldBuffer.setLength(0);
			} else if (near(buttons.brush_square, p)) {
				brush = 3;
				focusedField = null;
			} else if (near(buttons.brush_circle, p)) {
				brush = 1;
				focusedField = null;
			} else if (near(buttons.clearCanvas, p)) {
				java.util.Arrays.fill(canvas, BASE_COLOR);
				focusedField = null;
			} else if (near(buttons.saveImage, p)) {
				saveClicks++;
				focusedField = null;
			} else if (near(buttons.focus, p)) {
				focusedField = null;
			} else if (canvasArea.contains(p)) {
				if (canvasClicks >= mouseAwayAfterCanvasClicks) {
					return; // pointer fault active — the click is "the user's"
				}
				stampAt(p);
				canvasClicks++;
				focusedField = null;
			} else {
				focusedField = null;
			}
		}

		private void stampAt(Point p) {
			double signX = (p.x - canvasArea.x) * (double) SIGN / canvasArea.width;
			double signY = (p.y - canvasArea.y) * (double) SIGN / canvasArea.height;
			double cellCenterX = signX / PITCH;
			double cellCenterY = signY / PITCH;

			int sideCells = (int) Math.round((BRUSH_A * sizeValue + BRUSH_B) / PITCH);
			int anchorX = (int) Math.round(cellCenterX - sideCells / 2.0);
			int anchorY = (int) Math.round(cellCenterY - sideCells / 2.0);
			int color = pickerRgb();

			double radius = sideCells / 2.0;
			for (int cy = Math.max(0, anchorY); cy < Math.min(GRID, anchorY + sideCells); cy++) {
				for (int cx = Math.max(0, anchorX); cx < Math.min(GRID, anchorX + sideCells); cx++) {
					if (brush != 3) {
						// Circle brush: only cells within the radius — a missing
						// brush_square click corrupts corners and fails the test
						double dx = cx + 0.5 - (anchorX + radius);
						double dy = cy + 0.5 - (anchorY + radius);
						if (dx * dx + dy * dy > radius * radius) {
							continue;
						}
					}
					canvas[cy * GRID + cx] = blend(canvas[cy * GRID + cx], color, opacityValue);
				}
			}
		}

		private static int blend(int old, int top, double alpha) {
			if (alpha >= 1.0) {
				return top | 0xff000000;
			}
			int r = (int) Math.round(((old >>> 16) & 0xff) * (1 - alpha) + ((top >>> 16) & 0xff) * alpha);
			int g = (int) Math.round(((old >>> 8) & 0xff) * (1 - alpha) + ((top >>> 8) & 0xff) * alpha);
			int b = (int) Math.round((old & 0xff) * (1 - alpha) + (top & 0xff) * alpha);
			return 0xff000000 | (r << 16) | (g << 8) | b;
		}

		int pickerRgb() {
			double s = (svX - svRect.x + 0.5) / svRect.width;
			double v = 1.0 - (svY - svRect.y + 0.5) / svRect.height;
			double h = 1.0 - (hueY - hueRect.y + 0.5) / hueRect.height;
			return HsvColor.hsvToRgb(h, clamp01(s), clamp01(v));
		}

		// -------------------------------------------------------- keyboard

		@Override
		public void keyPress(int keyCode) {
			if (keyCode == KeyEvent.VK_CONTROL) {
				ctrlDown = true;
				return;
			}
			if (focusedField == null) {
				leakedKeys++; // chat/bind leak — the disaster FieldInput must prevent
				return;
			}
			if (keyCode == KeyEvent.VK_V && ctrlDown) {
				fieldBuffer.append(clipboard);
			} else if (keyCode >= KeyEvent.VK_NUMPAD0 && keyCode <= KeyEvent.VK_NUMPAD9) {
				fieldBuffer.append((char) ('0' + keyCode - KeyEvent.VK_NUMPAD0));
			} else if (keyCode == KeyEvent.VK_PERIOD || keyCode == KeyEvent.VK_DECIMAL) {
				fieldBuffer.append('.');
			} else if (keyCode == KeyEvent.VK_ENTER) {
				commitField();
			}
		}

		private void commitField() {
			try {
				double value = Double.parseDouble(fieldBuffer.toString());
				if (focusedField == 0) {
					sizeValue = clampD(value, 1, 32);
					sizeCommits++;
				} else {
					opacityValue = clampD(value, 0, 1);
				}
			} catch (NumberFormatException ignored) {
				// The field rejects garbage; value unchanged
			}
			fieldBuffer.setLength(0);
			focusedField = null;
		}

		@Override
		public void keyRelease(int keyCode) {
			if (keyCode == KeyEvent.VK_CONTROL) {
				ctrlDown = false;
			}
		}

		// ---------------------------------------------------------- screen

		int pixelAt(int x, int y) {
			if (swatchRect.contains(x, y)) {
				return stuckSwatch ? 0xff885522 : pickerRgb();
			}
			if (hueRect.contains(x, y)) {
				// Render the hue bar the probe now scans, consistent with clickHue's mapping.
				double h = 1.0 - (y - hueRect.y + 0.5) / (double) hueRect.height;
				return HsvColor.hsvToRgb(clamp01(h), 1.0, 1.0);
			}
			if (sliderPixel(x, y, buttons.size_1, buttons.size_32)) {
				return fillPixel(x, buttons.size_1.x(), buttons.size_32.x(), (sizeValue - 1) / 31.0);
			}
			if (sliderPixel(x, y, buttons.opacity_0, buttons.opacity_1)) {
				return fillPixel(x, buttons.opacity_0.x(), buttons.opacity_1.x(), opacityValue);
			}
			if (canvasArea.contains(x, y)) {
				int cx = (int) Math.floor((x - canvasArea.x) * (double) SIGN / canvasArea.width / PITCH);
				int cy = (int) Math.floor((y - canvasArea.y) * (double) SIGN / canvasArea.height / PITCH);
				if (cx >= 0 && cy >= 0 && cx < GRID && cy < GRID) {
					return canvas[cy * GRID + cx];
				}
				return BASE_COLOR;
			}
			return 0xff000000;
		}

		private static boolean sliderPixel(int x, int y, ButtonConfiguration.Coordinate start,
				ButtonConfiguration.Coordinate end) {
			return Math.abs(y - start.y()) <= 2 && x >= start.x() - 2 && x <= end.x() + 2;
		}

		private static int fillPixel(int x, int startX, int endX, double fraction) {
			double boundary = startX + clamp01(fraction) * (endX - startX);
			return x <= boundary ? GREEN_FILL : DARK_TRACK;
		}

		@Override
		public int getPixelRgb(int x, int y) {
			return pixelAt(x, y);
		}

		@Override
		public BufferedImage createScreenCapture(Rectangle region) {
			BufferedImage image = new BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_RGB);
			for (int y = 0; y < region.height; y++) {
				for (int x = 0; x < region.width; x++) {
					image.setRGB(x, y, pixelAt(region.x + x, region.y + y));
				}
			}
			return image;
		}

		@Override
		public Point getPointerLocation() {
			if (canvasClicks >= mouseAwayAfterCanvasClicks) {
				return new Point(mouse.x + 500, mouse.y + 500); // user grabbed the mouse
			}
			return mouse;
		}

		@Override
		public double currentTimeMs() {
			return time;
		}

		@Override
		public void sleepMs(long millis) {
			time += millis; // virtual clock — the whole paint runs instantly
		}

		@Override
		public void setClipboard(String text) {
			clipboard = text;
		}

		private static boolean near(ButtonConfiguration.Coordinate c, Point p) {
			return Math.abs(c.x() - p.x) <= 3 && Math.abs(c.y() - p.y) <= 3;
		}

		private static boolean nearRect(Rectangle r, Point p) {
			return p.x >= r.x - 4 && p.x < r.x + r.width + 4 && p.y >= r.y - 4 && p.y < r.y + r.height + 4;
		}

		private static int clamp(int v, int lo, int hi) {
			return Math.max(lo, Math.min(hi, v));
		}

		private static double clampD(double v, double lo, double hi) {
			return Math.max(lo, Math.min(hi, v));
		}

		private static double clamp01(double v) {
			return Math.max(0, Math.min(1, v));
		}
	}

	// -------------------------------------------------------------- helpers

	private static PalettizedPaintPlan corpusPlan(int colors) throws Exception {
		BufferedImage source = ImageIO.read(new File("src/test/resources/test-images-complex/portrait.png"));
		Rectangle rect = new Rectangle(0, 0, source.getWidth(), source.getHeight());
		BufferedImage cells = PixelImageScaler.getAreaAveragedInstance(source, rect, rect, GRID, GRID, Color.black);
		PixelQuantizer.Result quantized = PixelQuantizer.quantize(cells, colors, false);

		int maxSide = new SquareBrushGeometry(BRUSH_A, BRUSH_B, 1.0).maxSideCells(PITCH);
		return SquareTiler.tile(quantized.labels(), GRID, GRID, quantized.paletteRgb(), null, maxSide);
	}

	private static PalettizedPainter painter(FakeGame game) {
		return new PalettizedPainter(game, new PalettizedPainter.Config(
			game.buttons, game.canvasArea, 0, 0, SIGN, SIGN, PITCH,
			new SquareBrushGeometry(BRUSH_A, BRUSH_B, 1.0),
			30, 500, 8, true, true, FieldInput.DecimalKey.PERIOD));
	}

	private static PaintingInterrupted paint(PalettizedPainter painter, PalettizedPaintPlan plan) {
		try {
			painter.startDrawing(plan, (done, total) -> { });
			return fail("startDrawing always exits via PaintingInterrupted");
		} catch (PaintingInterrupted e) {
			return e;
		}
	}

	/** Canvas == plan render colored by the adopted palette, byte-exact. */
	private static void assertCanvasMatches(FakeGame game, PalettizedPaintPlan plan, int[] adopted) {
		int[] rendered = plan.renderCells(-1);
		int mismatches = 0;
		for (int i = 0; i < rendered.length; i++) {
			assertTrue(rendered[i] >= 0, "full-coverage plan left cell " + i + " unpainted");
			int expected = adopted[rendered[i]] | 0xff000000;
			if (game.canvas[i] != expected) {
				mismatches++;
			}
		}
		assertEquals(0, mismatches, "virtual canvas differs from the adopted-palette render");
	}

	// ----------------------------------------------------------------- tests

	@Test
	public void paintsACorpusPlanByteExactly() throws Exception {
		PalettizedPaintPlan plan = corpusPlan(12);
		FakeGame game = new FakeGame();
		PalettizedPainter painter = painter(game);

		PaintingInterrupted result = paint(painter, plan);

		assertEquals(InterruptType.PaintingFinished, result.getInterruptType());
		assertEquals(plan.getTotalStamps(), result.getDrawnShapes());
		assertEquals(plan.getTotalStamps(), game.canvasClicks, "every canvas click is one stamp");
		assertEquals(0, game.leakedKeys, "no keystroke may ever leak to the game");

		// Autosave cadence: every 500 stamps except at the very end, + the
		// final clickPoint(save, 4)
		int autosaves = (plan.getTotalStamps() - 1) / 500;
		assertEquals(autosaves + 4, game.saveClicks, "autosave cadence + final save x4");

		// The headline promise: canvas == adopted-palette render, byte-exact
		int[] adopted = painter.getAdoptedPalette();
		for (int c = 0; c < adopted.length; c++) {
			assertTrue((adopted[c] & 0xff000000) != 0, "color " + c + " was never adopted");
		}
		assertCanvasMatches(game, plan, adopted);

		assertNotNull(painter.getFittedModel(), "the probe model is exposed for persistence");
	}

	@Test
	public void probeGateAbortsBeforeAnyCanvasClick() throws Exception {
		PalettizedPaintPlan plan = corpusPlan(8);
		FakeGame game = new FakeGame();
		game.stuckSwatch = true; // "the COLOUR panel is in legacy grid mode"

		PaintingInterrupted result = paint(painter(game), plan);

		assertEquals(InterruptType.ColorEntryFailed, result.getInterruptType());
		assertEquals(0, result.getDrawnShapes());
		assertEquals(0, game.canvasClicks, "the probe gates must fire before the first canvas click");
	}

	@Test
	public void fieldFocusLossAbortsWithoutCorruptingTheCanvas() throws Exception {
		PalettizedPaintPlan plan = corpusPlan(8);
		FakeGame game = new FakeGame();
		game.breakSizeFocusAfterCommits = 1; // the second size entry loses focus

		PaintingInterrupted result = paint(painter(game), plan);

		assertEquals(InterruptType.FieldEntryFailed, result.getInterruptType());
		assertTrue(game.leakedKeys > 0, "the fault leaks keystrokes (that is what it simulates)");
		assertEquals(game.canvasClicks, result.getDrawnShapes(),
			"no canvas click may follow the failed field entry");
		assertTrue(game.canvasClicks < plan.getTotalStamps(), "the run must have stopped early");
	}

	@Test
	public void resumeAfterInterruptCompletesByteExactly() throws Exception {
		PalettizedPaintPlan plan = corpusPlan(10);
		FakeGame game = new FakeGame();
		game.mouseAwayAfterCanvasClicks = 200; // the user grabs the mouse

		PalettizedPainter first = painter(game);
		PaintingInterrupted interrupt = paint(first, plan);
		assertEquals(InterruptType.MouseMoved, interrupt.getInterruptType());
		assertTrue(interrupt.getDrawnShapes() > 0 && interrupt.getDrawnShapes() < plan.getTotalStamps());

		// The DrawDialog resume contract: advance the cursor, run again
		plan.advancePainted(interrupt.getDrawnShapes());
		game.mouseAwayAfterCanvasClicks = Integer.MAX_VALUE;

		PalettizedPainter second = painter(game);
		PaintingInterrupted finish = paint(second, plan);
		assertEquals(InterruptType.PaintingFinished, finish.getInterruptType());
		assertEquals(plan.getTotalStamps() - interrupt.getDrawnShapes(), finish.getDrawnShapes());

		// Colors entered before the resume cursor keep run 1's adoption; the
		// deterministic picker re-adopts identical values for re-entered ones
		int[] adopted = first.getAdoptedPalette().clone();
		int[] second0 = second.getAdoptedPalette();
		for (int c = 0; c < adopted.length; c++) {
			if ((second0[c] & 0xff000000) != 0) {
				if ((adopted[c] & 0xff000000) != 0) {
					assertEquals(adopted[c], second0[c], "re-entered color " + c + " must adopt the same value");
				}
				adopted[c] = second0[c];
			}
		}
		assertCanvasMatches(game, plan, adopted);
	}

	@Test
	public void stampMappingRoundTripsThroughTheFakeGame() throws Exception {
		// A focused check of the forward screen mapping: one stamp of each
		// side lands on its exact anchor cell after the int truncations.
		FakeGame game = new FakeGame();
		PalettizedPainter painter = painter(game);
		game.brush = 3;
		game.opacityValue = 1.0;

		for (int side : new int[] { 1, 2, 3, 5, 8, 17, 31 }) {
			game.sizeValue = new SquareBrushGeometry(BRUSH_A, BRUSH_B, 1.0).sizeFor(side, PITCH);
			int anchorX = 40, anchorY = 71;
			Point screen = painter.stampScreenPoint(
				new PalettizedPaintPlan.Stamp(anchorX, anchorY, true), side);
			game.mouseMove(screen.x, screen.y);
			game.mousePress();
			game.mouseRelease();

			for (int cy = anchorY; cy < anchorY + side; cy++) {
				for (int cx = anchorX; cx < anchorX + side; cx++) {
					assertEquals(game.pickerRgb() | 0xff000000, game.canvas[cy * GRID + cx],
						"side " + side + " stamp missed cell " + cx + "," + cy);
				}
			}
			java.util.Arrays.fill(game.canvas, BASE_COLOR);
		}
	}
}
