package com.bobrust.generator.tiler;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

import javax.imageio.ImageIO;

import com.bobrust.generator.quant.PixelQuantizer;
import com.bobrust.generator.tiler.PalettizedPaintPlan.Op;
import com.bobrust.generator.tiler.PalettizedPaintPlan.SetColor;
import com.bobrust.generator.tiler.PalettizedPaintPlan.SetSize;
import com.bobrust.generator.tiler.PalettizedPaintPlan.Stamp;
import com.bobrust.util.PixelImageScaler;
import org.junit.jupiter.api.Test;

public class SquareTilerTest {
	private static final int EMPTY = -1;

	/**
	 * Stamp-count regression pins: XL frame (160×160 cells, pitch 3.2), N=32,
	 * no dither. These commit the measured tiler output for the corpus so any
	 * tiler or quantizer change surfaces as a diff, mirroring
	 * PLAN-PALETTIZED-MODE.md §8's simulation numbers.
	 */
	private static final Map<String, Integer> STAMP_PINS = Map.of(
		"test-images-complex/portrait.png", 3974,
		"test-images-complex/skyline.png", 5107,
		"test-images-complex/glyphs.png", 9110,
		"test-images-complex/texture.png", 15404,
		"test-images/photo_detail.png", 12752
	);

	// ------------------------------------------------- exactness invariant

	@Test
	public void exactnessInvariantHoldsOnTheCorpus() throws Exception {
		for (String name : STAMP_PINS.keySet()) {
			PixelQuantizer.Result quantized = quantizeCorpusImage(name);
			PalettizedPaintPlan plan = SquareTiler.tile(
				quantized.labels(), quantized.gridW(), quantized.gridH(), quantized.paletteRgb(), null);

			assertArrayEquals(quantized.labels(), plan.renderCells(EMPTY),
				"rendering the plan must reproduce the quantized labels bit-exactly: " + name);
			assertPlanWellFormed(plan, quantized.labels(), null);
		}
	}

	@Test
	public void exactnessInvariantHoldsOnRandomGrids() {
		Random random = new Random(12345);
		for (int trial = 0; trial < 20; trial++) {
			int w = 8 + random.nextInt(70);
			int h = 8 + random.nextInt(70);
			int n = 2 + random.nextInt(15);
			int[] labels = new int[w * h];
			for (int i = 0; i < labels.length; i++) {
				labels[i] = random.nextInt(n);
			}
			int[] palette = new int[n];
			for (int c = 0; c < n; c++) {
				palette[c] = 0xff000000 | random.nextInt(0x1000000);
			}

			PalettizedPaintPlan plan = SquareTiler.tile(labels, w, h, palette, null);
			assertArrayEquals(labels, plan.renderCells(EMPTY), "random grid trial " + trial);
			assertPlanWellFormed(plan, labels, null);
		}
	}

	@Test
	public void solidCanvasUsesTwentyFiveMaxStamps() {
		int[] labels = new int[160 * 160];
		PalettizedPaintPlan plan = SquareTiler.tile(labels, 160, 160, new int[] { 0xff000000 }, null);

		assertEquals(25, plan.getTotalStamps(), "ceil(160/32)^2 = 25 side-32 stamps");
		assertEquals(1, plan.getSizeEntries());
		assertEquals(1, plan.getColorEntries());
		assertArrayEquals(labels, plan.renderCells(EMPTY));
	}

	@Test
	public void strayCellCostsExactlyOneMinimumStamp() {
		int w = 20, h = 20;
		int[] labels = new int[w * h]; // all color 0
		labels[7 * w + 11] = 1;        // one stray cell of color 1

		PalettizedPaintPlan plan = SquareTiler.tile(labels, w, h, new int[] { 0xff000000, 0xffffffff }, null);
		assertArrayEquals(labels, plan.renderCells(EMPTY));

		List<Stamp> strayStamps = stampsOfColor(plan, 1);
		assertEquals(1, strayStamps.size());
		assertEquals(11, strayStamps.get(0).cellX());
		assertEquals(7, strayStamps.get(0).cellY());
	}

	@Test
	public void painterRelaxationOverhangsLaterColorsOnly() {
		// A 8x8 background of color 0 with a 2x2 island of color 1 in the
		// middle: the relaxation lets color 0 (larger, painted first) stamp
		// right across the island, which color 1 then repaints.
		int w = 8, h = 8;
		int[] labels = new int[w * h];
		for (int y = 3; y < 5; y++) {
			for (int x = 3; x < 5; x++) {
				labels[y * w + x] = 1;
			}
		}

		PalettizedPaintPlan plan = SquareTiler.tile(labels, w, h, new int[] { 0xff000000, 0xffffffff }, null);
		assertArrayEquals(labels, plan.renderCells(EMPTY));

		// Without the relaxation, covering the ring around the island needs
		// >= 4 stamps of color 0; with it, one side-8 stamp suffices.
		assertEquals(1, stampsOfColor(plan, 0).size(), "the background should be one relaxed stamp");
	}

	@Test
	public void skipMaskCellsAreNeverTouched() {
		Random random = new Random(999);
		int w = 40, h = 40;
		int[] labels = new int[w * h];
		boolean[] paintable = new boolean[w * h];
		for (int i = 0; i < labels.length; i++) {
			labels[i] = random.nextInt(4);
			paintable[i] = random.nextInt(5) != 0; // ~20% skipped
		}
		int[] palette = { 0xff111111, 0xff222222, 0xff333333, 0xff444444 };

		PalettizedPaintPlan plan = SquareTiler.tile(labels, w, h, palette, paintable);
		int[] rendered = plan.renderCells(EMPTY);
		for (int i = 0; i < labels.length; i++) {
			if (paintable[i]) {
				assertEquals(labels[i], rendered[i], "paintable cell must end in its color");
			} else {
				assertEquals(EMPTY, rendered[i], "skipped cell must never be painted over");
			}
		}
		assertPlanWellFormed(plan, labels, paintable);
	}

	@Test
	public void stampCountsMatchTheRegressionPins() throws Exception {
		for (Map.Entry<String, Integer> pin : STAMP_PINS.entrySet()) {
			PixelQuantizer.Result quantized = quantizeCorpusImage(pin.getKey());
			PalettizedPaintPlan plan = SquareTiler.tile(
				quantized.labels(), quantized.gridW(), quantized.gridH(), quantized.paletteRgb(), null);
			assertEquals(pin.getValue().intValue(), plan.getTotalStamps(),
				"stamp count drifted for " + pin.getKey() + " — retune the pin only for a deliberate change");
		}
	}

	@Test
	public void tilingIsDeterministic() throws Exception {
		PixelQuantizer.Result quantized = quantizeCorpusImage("test-images-complex/portrait.png");
		PalettizedPaintPlan a = SquareTiler.tile(quantized.labels(), quantized.gridW(), quantized.gridH(), quantized.paletteRgb(), null);
		PalettizedPaintPlan b = SquareTiler.tile(quantized.labels(), quantized.gridW(), quantized.gridH(), quantized.paletteRgb(), null);
		assertEquals(a.getOps(), b.getOps());
	}

	// ----------------------------------------------------- resume cursor

	@Test
	public void opsFromReissuesTheGoverningToolState() {
		int w = 20, h = 20;
		int[] labels = new int[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				labels[y * w + x] = (x / 10) + 2 * (y / 10); // 4 quadrant colors
			}
		}
		PalettizedPaintPlan plan = SquareTiler.tile(labels, w, h,
			new int[] { 0xff000000, 0xffffffff, 0xffff0000, 0xff00ff00 }, null);
		int total = plan.getTotalStamps();
		assertTrue(total >= 4, "test needs a few stamps");

		// Resuming from stamp k: the head must be the governing SetColor +
		// SetSize, and replaying the remaining stamps onto the state the first
		// k stamps left must still satisfy the invariant.
		for (int cursor = 1; cursor < total; cursor++) {
			List<Op> resumed = plan.opsFrom(cursor);
			assertTrue(resumed.get(0) instanceof SetColor, "cursor " + cursor + " must re-issue SetColor");
			assertTrue(resumed.get(1) instanceof SetSize, "cursor " + cursor + " must re-issue SetSize");

			int stamps = (int) resumed.stream().filter(op -> op instanceof Stamp).count();
			assertEquals(total - cursor, stamps);
		}

		assertEquals(plan.getOps(), plan.opsFrom(0), "cursor 0 is the whole plan");
		assertTrue(plan.opsFrom(total).isEmpty(), "nothing left after the last stamp");
	}

	@Test
	public void resumeReplayReproducesTheFullRender() {
		int w = 30, h = 30;
		Random random = new Random(7);
		int[] labels = new int[w * h];
		for (int i = 0; i < labels.length; i++) {
			labels[i] = random.nextInt(5);
		}
		int[] palette = new int[5];
		for (int c = 0; c < 5; c++) {
			palette[c] = 0xff000000 | (c * 40 << 16);
		}
		PalettizedPaintPlan plan = SquareTiler.tile(labels, w, h, palette, null);

		// Paint the first k stamps from the full plan, then the rest from
		// opsFrom(k): the union must equal the full render.
		int cursor = plan.getTotalStamps() / 3;
		int[] canvas = new int[w * h];
		Arrays.fill(canvas, EMPTY);
		replay(plan.getOps(), canvas, w, cursor);
		replay(plan.opsFrom(cursor), canvas, w, Integer.MAX_VALUE);

		assertArrayEquals(labels, canvas, "interrupted + resumed paint must equal the quantized image");
	}

	// ------------------------------------------------ SquareBrushGeometry

	@Test
	public void squareBrushGeometryRoundTripsAndSolvesSizes() {
		SquareBrushGeometry geometry = new SquareBrushGeometry(3.2, 0.5, 0.9);
		assertEquals(geometry, SquareBrushGeometry.parse(geometry.serialize()));

		assertEquals(SquareBrushGeometry.DEFAULT, SquareBrushGeometry.parse(null));
		assertEquals(SquareBrushGeometry.DEFAULT, SquareBrushGeometry.parse(""));
		assertEquals(SquareBrushGeometry.DEFAULT, SquareBrushGeometry.parse("garbage"));
		assertEquals(SquareBrushGeometry.DEFAULT, SquareBrushGeometry.parse("a=0;b=1"), "non-positive slope is invalid");

		// Default geometry at pitch 3.2: side k -> SIZE = k * 1.024
		SquareBrushGeometry defaults = SquareBrushGeometry.DEFAULT;
		assertEquals(1.024, defaults.sizeFor(1, 3.2), 1e-9);
		assertEquals(32.768, defaults.sizeFor(32, 3.2), 1e-9);
		assertEquals("1.02", SquareBrushGeometry.formatSize(defaults.sizeFor(1, 3.2)));
		assertTrue(defaults.supportsPitch(3.2));
		assertFalse(defaults.supportsPitch(2.0), "pitch 2.0 needs SIZE 0.64 < minSize 1.0");
	}

	// -------------------------------------------------------------- helpers

	private static PixelQuantizer.Result quantizeCorpusImage(String name) throws Exception {
		BufferedImage source = ImageIO.read(new File("src/test/resources/" + name));
		Rectangle rect = new Rectangle(0, 0, source.getWidth(), source.getHeight());
		BufferedImage cells = PixelImageScaler.getAreaAveragedInstance(source, rect, rect, 160, 160, Color.black);
		return PixelQuantizer.quantize(cells, 32, false);
	}

	private static List<Stamp> stampsOfColor(PalettizedPaintPlan plan, int colorIndex) {
		List<Stamp> result = new ArrayList<>();
		int current = -1;
		for (Op op : plan.getOps()) {
			if (op instanceof SetColor setColor) {
				current = setColor.colorIndex();
			} else if (op instanceof Stamp stamp && current == colorIndex) {
				result.add(stamp);
			}
		}
		return result;
	}

	private static void replay(List<Op> ops, int[] canvas, int gridW, int maxStamps) {
		int color = EMPTY, side = 0, painted = 0;
		for (Op op : ops) {
			if (op instanceof SetColor setColor) {
				color = setColor.colorIndex();
			} else if (op instanceof SetSize setSize) {
				side = setSize.sideCells();
			} else if (op instanceof Stamp stamp) {
				if (painted++ >= maxStamps) {
					return;
				}
				for (int y = stamp.cellY(); y < stamp.cellY() + side; y++) {
					Arrays.fill(canvas, y * gridW + stamp.cellX(), y * gridW + stamp.cellX() + side, color);
				}
			}
		}
	}

	/**
	 * Structural checks shared by every invariant test: ops well-formed,
	 * sides within 1..32 and descending within each color, stamps in-bounds,
	 * and no stamp of rank k touching a cell of rank &lt; k (or a skipped
	 * cell) — the painter's-algorithm safety property.
	 */
	private static void assertPlanWellFormed(PalettizedPaintPlan plan, int[] labels, boolean[] paintable) {
		int gridW = plan.getGridWidth();
		int gridH = plan.getGridHeight();
		int[] rank = computeRanks(labels, plan.getPaletteRgb().length, paintable);

		int color = -1;
		int side = -1;
		int lastSide = Integer.MAX_VALUE;
		Set<Integer> seenColors = new HashSet<>();
		for (Op op : plan.getOps()) {
			if (op instanceof SetColor setColor) {
				assertTrue(seenColors.add(setColor.colorIndex()), "each color is entered exactly once");
				color = setColor.colorIndex();
				lastSide = Integer.MAX_VALUE;
				side = -1;
			} else if (op instanceof SetSize setSize) {
				assertTrue(setSize.sideCells() >= 1 && setSize.sideCells() <= SquareTiler.MAX_SIDE,
					"side out of range: " + setSize.sideCells());
				assertTrue(setSize.sideCells() < lastSide, "sides must descend within a color");
				lastSide = setSize.sideCells();
				side = setSize.sideCells();
			} else if (op instanceof Stamp stamp) {
				assertTrue(color >= 0 && side > 0, "stamp before tool state");
				assertTrue(stamp.cellX() >= 0 && stamp.cellY() >= 0
					&& stamp.cellX() + side <= gridW && stamp.cellY() + side <= gridH,
					"stamp out of bounds");

				int stampRank = rank[color];
				for (int y = stamp.cellY(); y < stamp.cellY() + side; y++) {
					for (int x = stamp.cellX(); x < stamp.cellX() + side; x++) {
						int cell = y * gridW + x;
						assertTrue(paintable == null || paintable[cell],
							"stamp touches a skipped cell at " + x + "," + y);
						assertTrue(rank[labels[cell]] >= stampRank,
							"stamp of rank " + stampRank + " touches earlier rank " + rank[labels[cell]]);
					}
				}
			}
		}
	}

	/** Rank per color: paintable-cell count desc, palette index asc; -1 for absent. */
	private static int[] computeRanks(int[] labels, int paletteSize, boolean[] paintable) {
		int[] counts = new int[paletteSize];
		for (int i = 0; i < labels.length; i++) {
			if (paintable == null || paintable[i]) {
				counts[labels[i]]++;
			}
		}
		Integer[] order = new Integer[paletteSize];
		for (int i = 0; i < paletteSize; i++) {
			order[i] = i;
		}
		Arrays.sort(order, (x, y) -> counts[x] != counts[y]
			? Integer.compare(counts[y], counts[x])
			: Integer.compare(x, y));

		int[] rank = new int[paletteSize];
		Arrays.fill(rank, -1);
		int next = 0;
		for (int c : order) {
			if (counts[c] > 0) {
				rank[c] = next++;
			}
		}
		return rank;
	}
}
