package com.bobrust.util;

import com.bobrust.generator.tiler.PalettizedPaintPlan;
import com.bobrust.generator.tiler.PalettizedPaintPlan.Op;
import com.bobrust.generator.tiler.PalettizedPaintPlan.Stamp;

/**
 * The palettized-mode cost model (PLAN-PALETTIZED-MODE.md §7/§8), mirroring
 * {@link PaintTimeEstimator}'s linear form: {@code actions·(1000/cps) +
 * captures·captureMs}, reusing the same calibrated per-capture cost.
 *
 * <pre>
 * actions  = stamps + 6·sizeEntries + (2 + 2.2)·colors     // entry + nudges
 *          + PROBE(54) + SETUP(13) + autosaves + finalSave(4)
 * captures = 3.2·colors + sizeEntries + 2·verifiedStamps + 27  // probe reads
 * </pre>
 *
 * Verified stamps are the plan-aware subset: stamps whose center cell the
 * plan predicts changes, at the configured verify cadence — each costs a
 * before + after pixel read like the legacy model's verified blobs.
 */
public final class PalettizedTimeEstimator {
	/** Focus ×4 + clear + brush ×4 + opacity field entry ≈ the sim's 67−54. */
	static final int SETUP_ACTIONS = 13;
	/** The probe pass: hue is now a single pixel-scan capture (not a click-loop), so only the
	 *  V + S axes click-and-read (~9 each) + set-hue + reference ≈ 36 actions / 19 captures. */
	static final int PROBE_ACTIONS = 36;
	static final int PROBE_CAPTURES = 19;
	/** Hue + SV click per color entry. */
	static final double COLOR_CLICKS = 2;
	/** Measured nudge clicks per color (Part E). */
	static final double NUDGE_AVG = 2.2;
	/** Click + paste/keys + Enter per SIZE entry (§3.3). */
	static final int SIZE_ENTRY_ACTIONS = 6;
	/** Swatch reads per color entry (measured mean 3.2). */
	static final double CAPTURES_PER_COLOR = 3.2;
	static final int FINAL_SAVE_CLICKS = 4;

	/** One live-readout result. */
	public record Estimate(long millis, int stamps, int colors, int sizeEntries) {
	}

	private PalettizedTimeEstimator() {
	}

	public static Estimate estimate(PalettizedPaintPlan plan, int cps, double captureMs,
			int verifyInterval, int autosaveInterval) {
		int stamps = plan.getTotalStamps();
		int colors = plan.getColorEntries();
		int sizeEntries = plan.getSizeEntries();
		if (stamps == 0) {
			return new Estimate(0, 0, colors, sizeEntries);
		}

		long autosaves = (stamps - 1) / Math.max(1, autosaveInterval);
		double actions = stamps
			+ (double) SIZE_ENTRY_ACTIONS * sizeEntries
			+ (COLOR_CLICKS + NUDGE_AVG) * colors
			+ PROBE_ACTIONS + SETUP_ACTIONS + autosaves + FINAL_SAVE_CLICKS;

		long captures = Math.round(CAPTURES_PER_COLOR * colors) + sizeEntries
			+ 2L * verifiedStamps(plan, verifyInterval) + PROBE_CAPTURES;

		double clickMs = 1000.0 / Math.max(1, cps);
		long millis = Math.round(actions * clickMs + captures * captureMs);
		return new Estimate(millis, stamps, colors, sizeEntries);
	}

	/**
	 * The stamps the executor will actually verify: predicted-changing center
	 * cells at the verify cadence — exactly the painter's condition.
	 */
	public static int verifiedStamps(PalettizedPaintPlan plan, int verifyInterval) {
		int v = Math.max(1, verifyInterval);
		int count = 0;
		int index = 0;
		for (Op op : plan.getOps()) {
			if (op instanceof Stamp stamp) {
				if (stamp.changesCenter() && (index % v) == 0) {
					count++;
				}
				index++;
			}
		}
		return count;
	}
}
