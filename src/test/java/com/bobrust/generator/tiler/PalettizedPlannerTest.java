package com.bobrust.generator.tiler;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

import com.bobrust.generator.quant.PixelQuantizer;
import com.bobrust.generator.tiler.PalettizedPaintPlan.Op;
import com.bobrust.generator.tiler.PalettizedPaintPlan.SetSize;
import com.bobrust.robot.hsv.HsvPickerModel;
import com.bobrust.util.PalettizedTimeEstimator;
import com.bobrust.util.PixelImageScaler;
import com.bobrust.util.Sign;
import org.junit.jupiter.api.Test;

public class PalettizedPlannerTest {
	private static final Sign XL = new Sign("sign.pictureframe.xl", 512, 512, new Color(0xb3aba0));

	private static BufferedImage corpusImage() throws Exception {
		return ImageIO.read(new File("src/test/resources/test-images-complex/portrait.png"));
	}

	@Test
	public void planCoversEveryCellAndRespectsTheSideCap() throws Exception {
		BufferedImage source = corpusImage();
		Rectangle rect = new Rectangle(0, 0, source.getWidth(), source.getHeight());

		PalettizedPlanner.PlanResult result = PalettizedPlanner.plan(
			source, rect, rect, XL, Color.black, 16, 3.2, false, false,
			SquareBrushGeometry.DEFAULT, null);

		assertEquals(160, result.cellImage().getWidth());
		assertEquals(160, result.cellImage().getHeight());

		// Full coverage: replaying the plan leaves no cell unpainted, and the
		// preview image is exactly the rendered plan
		int[] rendered = result.plan().renderCells(-1);
		int[] palette = result.plan().getPaletteRgb();
		for (int y = 0; y < 160; y++) {
			for (int x = 0; x < 160; x++) {
				int label = rendered[y * 160 + x];
				assertTrue(label >= 0, "unpainted cell");
				assertEquals(palette[label] | 0xff000000, result.cellImage().getRGB(x, y) | 0xff000000,
					"the preview must be exactly the promised paint");
			}
		}

		// Side cap: at pitch 3.2 the default brush maxes out SIZE 32 at side 31
		int maxSide = 0;
		for (Op op : result.plan().getOps()) {
			if (op instanceof SetSize setSize) {
				maxSide = Math.max(maxSide, setSize.sideCells());
			}
		}
		assertTrue(maxSide <= 31, "side " + maxSide + " would need SIZE > 32");
	}

	@Test
	public void paletteIsSnappedThroughTheFittedModel() throws Exception {
		BufferedImage source = corpusImage();
		Rectangle rect = new Rectangle(0, 0, source.getWidth(), source.getHeight());

		// A deliberately coarse picker (60 px axes) so snapping visibly moves colors
		HsvPickerModel model = new HsvPickerModel(
			new HsvPickerModel.Axis(0, 60), new HsvPickerModel.Axis(0, 60), new HsvPickerModel.Axis(0, 60));

		PalettizedPlanner.PlanResult snapped = PalettizedPlanner.plan(
			source, rect, rect, XL, Color.black, 16, 3.2, false, false,
			SquareBrushGeometry.DEFAULT, model);

		// The quantizer is deterministic, so recompute its raw palette and
		// check the planner applied exactly the snap
		BufferedImage cells = PixelImageScaler.getAreaAveragedInstance(source, rect, rect, 160, 160, Color.black);
		PixelQuantizer.Result quantized = PixelQuantizer.quantize(cells, 16, false);
		int[] expected = quantized.paletteRgb().clone();
		boolean moved = false;
		for (int c = 0; c < expected.length; c++) {
			int raw = expected[c];
			expected[c] = model.snapToReachable(raw);
			moved |= expected[c] != raw;
		}
		assertTrue(moved, "a 60px picker must move at least one centroid");
		assertArrayEquals(expected, snapped.plan().getPaletteRgb());
	}

	@Test
	public void skipBaseLeavesSignColoredCellsUnpainted() {
		// Synthetic image: left half sign-colored, right half blue
		int signRgb = XL.getAverageColor().getRGB();
		BufferedImage source = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 64; y++) {
			for (int x = 0; x < 64; x++) {
				source.setRGB(x, y, x < 32 ? signRgb : 0xff2040c0);
			}
		}
		Rectangle rect = new Rectangle(0, 0, 64, 64);

		PalettizedPlanner.PlanResult result = PalettizedPlanner.plan(
			source, rect, rect, XL, XL.getAverageColor(), 4, 3.2, false, true,
			SquareBrushGeometry.DEFAULT, null);

		int[] rendered = result.plan().renderCells(-1);
		int gridW = result.cellImage().getWidth();
		int painted = 0, skipped = 0;
		for (int y = 0; y < result.cellImage().getHeight(); y++) {
			for (int x = 0; x < gridW; x++) {
				if (rendered[y * gridW + x] < 0) {
					skipped++;
				} else {
					painted++;
				}
			}
		}
		assertTrue(skipped > 0, "sign-colored cells should be skipped");
		assertTrue(painted > 0, "the blue half must still be painted");
	}

	// ------------------------------------------------------------ estimator

	@Test
	public void estimatorClosedFormMatchesAHandBuiltPlan() {
		// Solid 64x64 one-color grid: 4 side-32 stamps, 1 color, 1 size entry
		int[] labels = new int[64 * 64];
		PalettizedPaintPlan plan = SquareTiler.tile(labels, 64, 64, new int[] { 0xff123456 }, null);
		assertEquals(4, plan.getTotalStamps());
		assertEquals(1, plan.getColorEntries());
		assertEquals(1, plan.getSizeEntries());

		// All 4 stamps change their center; cadence 2 verifies stamps 0 and 2
		assertEquals(2, PalettizedTimeEstimator.verifiedStamps(plan, 2));
		assertEquals(4, PalettizedTimeEstimator.verifiedStamps(plan, 1));

		PalettizedTimeEstimator.Estimate estimate = PalettizedTimeEstimator.estimate(plan, 30, 12.0, 2, 1000);
		double actions = 4 + 6 * 1 + (2 + 2.2) * 1 + 54 + 13 + 0 + 4;
		long captures = Math.round(3.2 * 1) + 1 + 2 * 2 + 27;
		long expected = Math.round(actions * (1000.0 / 30) + captures * 12.0);
		assertEquals(expected, estimate.millis());
		assertEquals(4, estimate.stamps());
	}

	@Test
	public void estimatorHandlesTheEmptyPlan() {
		PalettizedPaintPlan plan = SquareTiler.tile(new int[0], 0, 0, new int[] { 0xff000000 }, null);
		assertEquals(0, PalettizedTimeEstimator.estimate(plan, 30, 12.0, 1, 1000).millis());
	}
}
