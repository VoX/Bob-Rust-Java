package com.bobrust.util;

import java.util.List;

import com.bobrust.generator.BlobPruner;
import com.bobrust.generator.BorstImage;
import com.bobrust.generator.sorter.Blob;
import com.bobrust.generator.sorter.BlobList;

/**
 * S2: the paint-time cost model, verified line-by-line against
 * {@link com.bobrust.robot.BobRustPainter#startDrawing}. Every term is either
 * exact (blob count, tool changes, clicks-per-second) or measured (the
 * per-{@code Robot.getPixelColor} screen-capture cost {@code captureMs}), so
 * the estimate replaces both the old {@code OverlayTopPanel} fudge factor
 * ({@code 1.3 × (14 + 1000/cps)}) and the EDT-blocking "Calculate Exact Time"
 * button.
 *
 * <p>The model (times in ms; mouse travel is FREE — {@code Robot.mouseMove}
 * teleports, so ordering/distance never appears):
 * <pre>
 * T = N·(1000/cps)                    // every canvas blob: 3 × autoDelay waits
 *   + C·(1000/cps)                    // every tool change: one click cycle
 *   + floor((N−1)/autosave)·(1000/cps)// periodic autosave clicks
 *   + 4·(1000/cps)                    // the final save (clickPoint ×4)
 *   + 17·150                          // fixed setup: 4 focus + 1 color + 4 size
 *                                     //   + 4 alpha + 4 shape clicks at delay=50
 *   + captureMs·(2·ceil(N/v) + 2·C + 2) // getPixelColor taxes: verified blobs
 *                                     //   (before+after), tool-change checks,
 *                                     //   and the setup color check
 * </pre>
 * where {@code C = RustUtil.getScore(plan) − 4} is the exact tool-change count
 * of the sorted plan (size, color, alpha AND shape — post-Q2 the per-shape
 * alpha search makes alpha changes real) and {@code v} is the verification
 * cadence (verify every v-th canvas click; 1 = every click).
 *
 * <p>The model assumes first-try click success; realized retry overhead is
 * absorbed into the calibrated {@code captureMs} by
 * {@link #calibrateCaptureMs}, which inverts the (linear-in-captureMs) model
 * against a realized paint run.
 */
public final class PaintTimeEstimator {
	/** Setup clicks run at this fixed delay (BobRustPainter passes 50). */
	static final double SETUP_CLICK_DELAY_MS = 50;
	/** 4 focus + 1 color + 4 size + 4 alpha + 4 shape setup click cycles. */
	static final int SETUP_CLICKS = 17;
	/** The setup color click verifies against the preview: before + after. */
	static final int SETUP_CAPTURES = 2;
	/** The trailing "make sure we save" clickPoint(save, 4, delay). */
	static final int FINAL_SAVE_CLICKS = 4;
	/** Prior for the capture cost before any run has calibrated it. */
	public static final double DEFAULT_CAPTURE_MS = 12.0;

	/** One live-readout result: paint time, plan stats and the match figure. */
	public record Estimate(long millis, int blobs, int toolChanges, double matchPercent) {
	}

	private PaintTimeEstimator() {
	}

	/**
	 * Estimated wall-clock ms to paint {@code plan} (a sorted instruction
	 * segment, painted from its first blob — the painter re-runs tool setup
	 * for the segment's first blob on every start).
	 */
	public static long estimateMillis(BlobList plan, int cps, double captureMs, int verifyInterval, int autosaveInterval) {
		if (plan.size() == 0) {
			return 0;
		}
		return Math.round(fixedMillis(plan, cps, autosaveInterval)
			+ captureCount(plan, verifyInterval) * captureMs);
	}

	/**
	 * The capture-independent part of the model: all click-cycle waits. Linear
	 * decomposition {@code T = fixedMillis + captureCount·captureMs} is what
	 * makes {@link #calibrateCaptureMs} exact.
	 */
	static double fixedMillis(BlobList plan, int cps, int autosaveInterval) {
		int n = plan.size();
		if (n == 0) {
			return 0;
		}
		double clickMs = 1000.0 / Math.max(1, cps); // one click cycle = 3 × autoDelay
		long autosaves = (n - 1) / Math.max(1, autosaveInterval);
		return (n + toolChanges(plan) + autosaves + FINAL_SAVE_CLICKS) * clickMs
			+ SETUP_CLICKS * 3 * SETUP_CLICK_DELAY_MS;
	}

	/**
	 * Total {@code getPixelColor} calls the painter will make for this plan:
	 * 2 per verified canvas blob, 2 per tool change, plus the setup color
	 * verification.
	 */
	static long captureCount(BlobList plan, int verifyInterval) {
		int n = plan.size();
		if (n == 0) {
			return 0;
		}
		int v = Math.max(1, verifyInterval);
		long verified = (n + v - 1) / v; // blob i is verified iff i % v == 0
		return 2L * (verified + toolChanges(plan)) + SETUP_CAPTURES;
	}

	/**
	 * Exact in-loop tool changes of the plan: {@code RustUtil.getScore − 4}
	 * (getScore starts at 4 for the initial size/color/alpha/shape setup,
	 * which the model books under the fixed setup cost instead).
	 */
	public static int toolChanges(BlobList plan) {
		return plan.size() == 0 ? 0 : RustUtil.getScore(plan) - 4;
	}

	/**
	 * Solve the model for the per-capture cost given a realized paint run over
	 * {@code paintedPlan} that took {@code realizedMs}. The model is linear in
	 * the capture cost, so this is exact modulo retry noise (which it absorbs,
	 * by design). Clamped to a sane range.
	 */
	public static double calibrateCaptureMs(long realizedMs, BlobList paintedPlan, int cps, int verifyInterval, int autosaveInterval) {
		long captures = captureCount(paintedPlan, verifyInterval);
		if (captures <= 0) {
			return DEFAULT_CAPTURE_MS;
		}
		double captureMs = (realizedMs - fixedMillis(paintedPlan, cps, autosaveInterval)) / (double) captures;
		return Math.max(0.0, Math.min(100.0, captureMs));
	}

	/**
	 * The "match" figure of the live readout: the percentage of blank-canvas
	 * error the painted plan removes — {@code 100·(1 − rmse(plan)/rmse(blank))}
	 * over a true re-render with the exact paint kernel (F1's uniform RMSE, so
	 * comparable across benchmark columns). Relative-to-blank is the right
	 * framing: absolute RMSE depends on the image, "error −87%" compares.
	 */
	public static double matchPercent(List<Blob> plan, BorstImage target, int background) {
		double blank = BlobPruner.renderScore(List.of(), target, background);
		if (blank <= 0) {
			return 100.0;
		}
		double planned = BlobPruner.renderScore(plan, target, background);
		return Math.max(0.0, Math.min(100.0, (1.0 - planned / blank) * 100.0));
	}
}
