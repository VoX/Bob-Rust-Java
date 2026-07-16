package com.bobrust.calibration;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;

import com.bobrust.generator.tiler.SquareBrushGeometry;
import org.junit.jupiter.api.Test;

/**
 * §11.3 round-trip for the square-brush calibration: render the expected
 * pattern → analyze the exact image → sides within ±2 px, fill ≥ threshold,
 * fit recovers the geometry — at 1× and 2× scale, and a circle-shaped brush
 * is flagged.
 */
public class SquareBrushCalibrationTest {

	@Test
	public void roundTripRecoversTheGeometryAtBothScales() {
		SquareBrushGeometry truth = new SquareBrushGeometry(3.125, 0.0, 1.0);

		for (double pixelsPerTexel : new double[] { 1.0, 2.0 }) {
			BufferedImage pattern = SquareBrushCalibration.renderExpectedPattern(truth, pixelsPerTexel);
			SquareBrushCalibration.Result result = SquareBrushCalibration.analyze(pattern, 1.0 / pixelsPerTexel);

			assertEquals(SquareBrushCalibration.SIZE_STEPS.length, result.stamps().size(),
				"every stamp must be detected at scale " + pixelsPerTexel);
			assertTrue(result.squareShaped(), "rendered squares must pass the corner gate");

			for (int i = 0; i < result.stamps().size(); i++) {
				double expectedPx = Math.round(
					(truth.a() * SquareBrushCalibration.SIZE_STEPS[i] + truth.b()) * pixelsPerTexel);
				assertEquals(expectedPx, result.stamps().get(i).sidePx(), 2.0,
					"side of stamp " + i + " at scale " + pixelsPerTexel);
			}

			SquareBrushGeometry fitted = result.fitted();
			assertNotNull(fitted);
			assertEquals(truth.a(), fitted.a(), truth.a() * 0.02, "slope within 2%");
			assertEquals(truth.b(), fitted.b(), 1.5, "intercept within 1.5 texels");
		}
	}

	@Test
	public void nonZeroInterceptIsRecovered() {
		SquareBrushGeometry truth = new SquareBrushGeometry(3.0, 2.0, 1.0);
		BufferedImage pattern = SquareBrushCalibration.renderExpectedPattern(truth, 1.0);
		SquareBrushCalibration.Result result = SquareBrushCalibration.analyze(pattern, 1.0);

		assertNotNull(result.fitted());
		assertEquals(3.0, result.fitted().a(), 0.06);
		assertEquals(2.0, result.fitted().b(), 1.5);
	}

	@Test
	public void circleShapedStampsFailTheCornerGate() {
		// A brush that paints circles instead of squares (edge case 20)
		int spacing = 124;
		BufferedImage pattern = new BufferedImage(spacing * 8 + 16, 140, BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = pattern.createGraphics();
		g.setColor(java.awt.Color.WHITE);
		for (int i = 0; i < 8; i++) {
			int diameter = (int) Math.round(3.125 * SquareBrushCalibration.SIZE_STEPS[i]);
			int cx = 8 + spacing * i + spacing / 2;
			g.fillOval(cx - diameter / 2, 70 - diameter / 2, Math.max(diameter, 4), Math.max(diameter, 4));
		}
		g.dispose();

		SquareBrushCalibration.Result result = SquareBrushCalibration.analyze(pattern, 1.0);
		assertFalse(result.squareShaped(), "circles fill only ~79% of their bbox and must be flagged");
	}

	@Test
	public void degenerateFitFallsBackToTheDefaults() {
		assertEquals(SquareBrushGeometry.DEFAULT,
			SquareBrushCalibration.fit(new double[] { 2, 2, 2 }, new double[] { 6, 6, 6 }),
			"identical sizes cannot identify a slope");
	}
}
