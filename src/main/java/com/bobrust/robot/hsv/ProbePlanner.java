package com.bobrust.robot.hsv;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import com.bobrust.robot.error.PaintingInterrupted;
import com.bobrust.util.metrics.Ciede2000;

/**
 * The automatic probe calibration run at the start of every palettized paint
 * (PLAN-PALETTIZED-MODE.md §2.3): ~30 paced clicks + swatch reads fit the
 * per-axis linear click→color mapping from the user-marked rects, and a set
 * of sanity gates fails closed — before a single canvas click — when the
 * picker is not what the mode needs (legacy grid toggled, mis-marked rects,
 * a global screen color transform).
 *
 * <p>Axis order matters for observability: V is fitted first (readable at any
 * saturation, any orientation), then S is probed along the fitted maximum-V
 * row (so the swatch is bright enough for S to be readable even on a flipped
 * picker), then hue with S/V pinned at the fitted bright-saturated corner.
 * Probe clicks that land outside the real control saturate their read-back;
 * the per-axis fit drops up to two such outliers before gating.
 */
public final class ProbePlanner {
	/** Probes per axis (9 → ~30 clicks incl. setup + reference, §2.3). */
	public static final int DEFAULT_PROBES_PER_AXIS = 9;
	/** Fit residual gate: any axis worse than this fails the probe. */
	public static final double MAX_RESIDUAL_PX = 2.0;
	/** Hue readbacks must span more than this fraction of the wheel (300°). */
	public static final double MIN_HUE_SPAN = 300.0 / 360.0;
	/** The fitted pure-red reference click must read back within this ΔE00. */
	public static final double MAX_REFERENCE_DELTA_E = 5.0;
	/** Inset from the marked rect edges for probe clicks (mis-marking slack). */
	private static final int EDGE_INSET = 2;
	/** Trim residual: outliers are dropped while the fit is worse than this. */
	private static final double TRIM_RESIDUAL_PX = 1.0;
	/** At most this many saturated-endpoint outliers are dropped per axis. */
	private static final int MAX_TRIM_DROPS = 2;

	/**
	 * Probe outcome: the fitted model (null on failure), the worst per-axis
	 * fit residual, and a human-readable failure reason.
	 */
	public record ProbeResult(HsvPickerModel model, double maxResidualPx, String failure) {
		public boolean ok() {
			return model != null;
		}
	}

	private ProbePlanner() {
	}

	/** Runs the probe sequence and gates; never clicks outside the marked rects. */
	public static ProbeResult probe(PickerSensor sensor, Rectangle svRect, Rectangle hueRect, int probesPerAxis)
			throws PaintingInterrupted {
		int n = Math.max(3, probesPerAxis);
		int svRight = svRect.x + svRect.width - 2;
		int hueMid = hueRect.y + hueRect.height / 2;

		// A well-conditioned starting state: some hue, SV near a corner.
		sensor.clickHue(hueMid);
		sensor.clickSv(svRight, svRect.y + 1);

		// V axis along the right edge — V is observable at any saturation and
		// any orientation, so this axis anchors the rest of the probe.
		double[] vTs = new double[n];
		int[] vPxs = new int[n];
		for (int i = 0; i < n; i++) {
			int py = probePosition(svRect.y, svRect.height, i, n);
			sensor.clickSv(svRight, py);
			double[] hsv = HsvColor.rgbToHsv(sensor.readSwatch());
			vPxs[i] = py;
			vTs[i] = 1.0 - hsv[2];
		}
		HsvPickerModel.AxisFit vFit = fitTrimmed(vTs, vPxs);
		if (vFit == null) {
			return degenerate();
		}

		// S axis along the fitted maximum-V row (bright swatch → S readable)
		int maxVRow = clamp(vFit.axis().pxFor(0.0), svRect.y, svRect.y + svRect.height - 1);
		double[] sTs = new double[n];
		int[] sPxs = new int[n];
		for (int i = 0; i < n; i++) {
			int px = probePosition(svRect.x, svRect.width, i, n);
			sensor.clickSv(px, maxVRow);
			double[] hsv = HsvColor.rgbToHsv(sensor.readSwatch());
			sPxs[i] = px;
			sTs[i] = hsv[1];
		}
		HsvPickerModel.AxisFit sFit = fitTrimmed(sTs, sPxs);
		if (sFit == null) {
			return degenerate();
		}

		// Hue axis with S/V pinned at the fitted bright-saturated corner
		int maxSCol = clamp(sFit.axis().pxFor(1.0), svRect.x, svRect.x + svRect.width - 1);
		sensor.clickSv(maxSCol, maxVRow);
		double[] hTs = new double[n];
		int[] hPxs = new int[n];
		double hueMin = Double.MAX_VALUE, hueMax = -Double.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			int py = probePosition(hueRect.y, hueRect.height, i, n);
			sensor.clickHue(py);
			double[] hsv = HsvColor.rgbToHsv(sensor.readSwatch());
			hPxs[i] = py;
			hTs[i] = 1.0 - hsv[0];
			hueMin = Math.min(hueMin, hsv[0]);
			hueMax = Math.max(hueMax, hsv[0]);
		}

		if (hueMax - hueMin < MIN_HUE_SPAN) {
			return new ProbeResult(null, Double.MAX_VALUE,
				("hue readbacks span only %.0f deg - the marked rect is not a hue bar, or the COLOUR panel "
					+ "is not toggled to the HSV picker").formatted((hueMax - hueMin) * 360));
		}

		HsvPickerModel.AxisFit hFit = fitTrimmed(hTs, hPxs);
		if (hFit == null) {
			return degenerate();
		}

		double maxResidual = Math.max(sFit.maxResidualPx(), Math.max(vFit.maxResidualPx(), hFit.maxResidualPx()));
		if (maxResidual > MAX_RESIDUAL_PX) {
			return new ProbeResult(null, maxResidual,
				("probe fit residual %.1f px exceeds %.1f px - re-mark the picker rects in Setup, and toggle "
					+ "the COLOUR panel to the HSV picker").formatted(maxResidual, MAX_RESIDUAL_PX));
		}

		HsvPickerModel model = new HsvPickerModel(sFit.axis(), vFit.axis(), hFit.axis());

		// Reference gate: pure red at the fitted (S=1, V=1, H=0). A large
		// mismatch means a global screen color transform (night mode, HDR,
		// colorblind filter) — absolute readback is load-bearing here, refuse.
		int[] red = model.clicksFor(0xffff0000);
		sensor.clickHue(clamp(red[2], hueRect.y, hueRect.y + hueRect.height - 1));
		sensor.clickSv(
			clamp(red[0], svRect.x, svRect.x + svRect.width - 1),
			clamp(red[1], svRect.y, svRect.y + svRect.height - 1));
		int readBack = sensor.readSwatch();
		double deltaE = Ciede2000.deltaE(readBack | 0xff000000, 0xffff0000);
		if (deltaE > MAX_REFERENCE_DELTA_E) {
			return new ProbeResult(null, maxResidual,
				("reference red read back dE00 %.1f away (#%06x) - a global screen color transform "
					+ "(night light / HDR / filter) breaks absolute color entry; disable it and retry")
					.formatted(deltaE, readBack & 0xffffff));
		}

		return new ProbeResult(model, maxResidual, null);
	}

	private static ProbeResult degenerate() {
		return new ProbeResult(null, Double.MAX_VALUE,
			"probe readbacks are degenerate - is the COLOUR panel toggled to the HSV picker?");
	}

	/**
	 * Least-squares fit that drops up to {@link #MAX_TRIM_DROPS} worst-residual
	 * observations while the fit is worse than {@link #TRIM_RESIDUAL_PX} —
	 * probe clicks that landed outside the real control saturate their
	 * read-back and would otherwise bias the line.
	 */
	static HsvPickerModel.AxisFit fitTrimmed(double[] ts, int[] pxs) {
		List<Integer> alive = new ArrayList<>();
		for (int i = 0; i < ts.length; i++) {
			alive.add(i);
		}

		HsvPickerModel.AxisFit fit = fitSubset(ts, pxs, alive);
		for (int drops = 0; drops < MAX_TRIM_DROPS; drops++) {
			if (fit == null || fit.maxResidualPx() <= TRIM_RESIDUAL_PX || alive.size() <= 4) {
				break;
			}
			// Drop the worst-residual observation (lowest index on ties)
			int worst = -1;
			double worstResidual = -1;
			for (int index : alive) {
				double predicted = fit.axis().offset() + fit.axis().scale() * ts[index];
				double residual = Math.abs(predicted - (pxs[index] + 0.5));
				if (residual > worstResidual) {
					worstResidual = residual;
					worst = index;
				}
			}
			alive.remove(Integer.valueOf(worst));
			fit = fitSubset(ts, pxs, alive);
		}
		return fit;
	}

	private static HsvPickerModel.AxisFit fitSubset(double[] ts, int[] pxs, List<Integer> indices) {
		double[] subTs = new double[indices.size()];
		int[] subPxs = new int[indices.size()];
		for (int i = 0; i < indices.size(); i++) {
			subTs[i] = ts[indices.get(i)];
			subPxs[i] = pxs[indices.get(i)];
		}
		return HsvPickerModel.fitAxis(subTs, subPxs);
	}

	/** The i-th of n probe positions, inset from both edges of the marked span. */
	private static int probePosition(int start, int length, int i, int n) {
		double t = i / (double) (n - 1);
		return (int) Math.round(start + EDGE_INSET + t * (length - 1 - 2 * EDGE_INSET));
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
