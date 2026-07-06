package com.bobrust.util;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.bobrust.generator.BlobPruner;
import com.bobrust.generator.BorstImage;
import com.bobrust.generator.BorstUtils;
import com.bobrust.generator.sorter.Blob;
import com.bobrust.generator.sorter.BlobList;
import com.bobrust.util.data.AppConstants;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S2: the paint-time cost model. Every term is pinned against the closed form
 * derived from BobRustPainter (click cycles, tool changes incl. Q2 alpha
 * changes, verification cadence, autosaves, fixed setup, capture taxes), and
 * calibration must invert the model exactly (it is linear in the capture
 * cost).
 */
class PaintTimeEstimatorTest {
	private static final int BACKGROUND = 0xFFFFFFFF;

	private static Blob blob(int sizeIndex, int colorIndex, int alphaIndex) {
		return Blob.of(20, 20, BorstUtils.SIZES[sizeIndex], BorstUtils.COLORS[colorIndex].rgb,
			BorstUtils.ALPHAS[alphaIndex], AppConstants.CIRCLE_SHAPE);
	}

	private static BlobList uniformPlan(int count) {
		BlobList plan = new BlobList();
		for (int i = 0; i < count; i++) {
			plan.add(blob(2, 0, 2));
		}
		return plan;
	}

	@Test
	void uniformPlanHasNoToolChangesAndExactClosedForm() {
		BlobList plan = uniformPlan(3);
		assertEquals(0, PaintTimeEstimator.toolChanges(plan));

		// cps=20 → 50 ms/click. 3 blobs + 0 changes + 0 autosaves + 4 final
		// save clicks = 350 ms; setup 17×150 = 2550 ms; captures 2·3+2 = 8 at
		// 10 ms = 80 ms.
		assertEquals(350 + 2550 + 80,
			PaintTimeEstimator.estimateMillis(plan, 20, 10.0, 1, 1000));
	}

	@Test
	void toolChangesCountSizeColorAlphaChanges() {
		// Q2 shipped per-shape alpha: alpha deltas are REAL tool changes now.
		BlobList plan = new BlobList();
		plan.add(blob(2, 0, 2));
		plan.add(blob(3, 0, 2)); // size change
		plan.add(blob(3, 1, 2)); // color change
		plan.add(blob(3, 1, 4)); // alpha change
		assertEquals(3, PaintTimeEstimator.toolChanges(plan));

		// cps=25 → 40 ms/click. (4 blobs + 3 changes + 4 save) × 40 = 440;
		// setup 2550; captures 2·(4+3)+2 = 16 at 5 ms = 80.
		assertEquals(440 + 2550 + 80,
			PaintTimeEstimator.estimateMillis(plan, 25, 5.0, 1, 1000));
	}

	@Test
	void verificationCadenceOnlyChangesCaptureCount() {
		BlobList plan = uniformPlan(10);
		// verify every click: 2·10+2 captures; every 3rd: blobs 0,3,6,9 → 2·4+2.
		assertEquals(22, PaintTimeEstimator.captureCount(plan, 1));
		assertEquals(10, PaintTimeEstimator.captureCount(plan, 3));

		long always = PaintTimeEstimator.estimateMillis(plan, 30, 10.0, 1, 1000);
		long sparse = PaintTimeEstimator.estimateMillis(plan, 30, 10.0, 3, 1000);
		assertEquals((22 - 10) * 10, always - sparse,
			"sparse verification must save exactly the skipped captures");
	}

	@Test
	void autosaveClicksAreCounted() {
		// The painter autosaves at i % interval == 0 for 0 < i < N.
		BlobList plan = uniformPlan(2001);
		double withAutosave = PaintTimeEstimator.fixedMillis(plan, 30, 1000);
		double without = PaintTimeEstimator.fixedMillis(plan, 30, 1_000_000);
		assertEquals(2 * (1000.0 / 30), withAutosave - without, 1e-9,
			"2001 blobs at interval 1000 autosave exactly twice");
	}

	@Test
	void emptyPlanCostsNothing() {
		assertEquals(0, PaintTimeEstimator.estimateMillis(new BlobList(), 30, 10.0, 1, 1000));
		assertEquals(0, PaintTimeEstimator.toolChanges(new BlobList()));
	}

	@Test
	void calibrationInvertsTheModel() {
		BlobList plan = new BlobList();
		for (int i = 0; i < 60; i++) {
			plan.add(blob(i % 3, i % 5, 2 + (i % 2)));
		}
		double captureMs = 13.7;
		long realized = PaintTimeEstimator.estimateMillis(plan, 30, captureMs, 5, 1000);
		double calibrated = PaintTimeEstimator.calibrateCaptureMs(realized, plan, 30, 5, 1000);
		assertEquals(captureMs, calibrated, 0.05,
			"calibration must recover the capture cost the estimate was built from");
	}

	@Test
	void calibrationIsClampedToSaneRange() {
		BlobList plan = uniformPlan(60);
		// Absurdly fast run → clamp at 0 rather than a negative capture cost.
		assertEquals(0.0, PaintTimeEstimator.calibrateCaptureMs(1, plan, 30, 1, 1000));
		// Absurdly slow run → clamp at 100 ms.
		assertEquals(100.0, PaintTimeEstimator.calibrateCaptureMs(10_000_000, plan, 30, 1, 1000));
		// No captures at all → the prior.
		assertEquals(PaintTimeEstimator.DEFAULT_CAPTURE_MS,
			PaintTimeEstimator.calibrateCaptureMs(1000, new BlobList(), 30, 1, 1000));
	}

	@Test
	void matchPercentIsRelativeBlankCanvasErrorRemoved() {
		List<Blob> blobs = List.of(
			Blob.of(20, 20, BorstUtils.SIZES[3], 0x000000, 255, AppConstants.CIRCLE_SHAPE),
			Blob.of(40, 44, BorstUtils.SIZES[2], 0xff0000, 255, AppConstants.CIRCLE_SHAPE));
		BorstImage target = BlobPruner.render(blobs, 64, 64, BACKGROUND);

		// The exact plan reproduces the target: 100% of blank error removed.
		assertEquals(100.0, PaintTimeEstimator.matchPercent(blobs, target, BACKGROUND), 1e-9);
		// An empty plan removes nothing.
		assertEquals(0.0, PaintTimeEstimator.matchPercent(List.of(), target, BACKGROUND), 1e-9);
		// A partial plan sits strictly between.
		double partial = PaintTimeEstimator.matchPercent(blobs.subList(0, 1), target, BACKGROUND);
		assertTrue(partial > 0 && partial < 100, "partial plan match was " + partial);
	}
}
