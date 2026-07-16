package com.bobrust.robot.hsv;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.bobrust.robot.error.PaintingInterrupted;
import com.bobrust.util.metrics.Ciede2000;

/**
 * The closed-loop color entry of PLAN-PALETTIZED-MODE.md §2.5: click the
 * planned picker position, read the swatch back, nudge by the scaled HSV
 * error (≤ ±3 px per axis) until the read-back is byte-equal to the model's
 * prediction or within tolerance of the target, then adopt what was actually
 * read. Bounded and non-spraying: at most {@code maxReads} swatch reads and
 * {@code 2·maxReads + 2} picker clicks per color (the +2 is the loose-path
 * re-issue of the best position), every click inside the
 * calibrated rects. Pure logic over an injected {@link PickerSensor} — no
 * Robot, fully unit-testable.
 */
public class ColorEntryController {
	/** Default read budget per color (measured convergence needs ≤ 4). */
	public static final int DEFAULT_MAX_READS = 6;
	/** Adopt immediately at or below this ΔE00 vs the target. */
	public static final double DEFAULT_ACCEPT = 1.0;
	/** On loop exhaustion, adopt the best-so-far at or below this (warn). */
	public static final double DEFAULT_ACCEPT_LOOSE = 1.5;

	private static final int MAX_STEP_PX = 3;
	/** Online-refit conditioning thresholds (PLAN-PALETTIZED-MODE.md §2.5). */
	private static final double SV_OBSERVABLE_MIN_V = 0.1;
	private static final double HUE_OBSERVABLE_MIN_SV = 0.08;
	private static final int REFIT_MIN_OBSERVATIONS = 3;
	private static final double REFIT_MIN_SPREAD_PX = 8;

	public enum Status {
		/** The read-back was adopted as the palette color. */
		ADOPTED,
		/** The loop exhausted its budget without an acceptable read-back. */
		FAILED
	}

	/**
	 * One color entry's outcome. {@code adoptedRgb} is meaningful only for
	 * {@link Status#ADOPTED}; {@code loose} marks an exhaustion-path adoption
	 * (best-so-far within the loose bound — warn-log worthy).
	 */
	public record Result(Status status, int adoptedRgb, int reads, int clicks, boolean loose, double deltaE) {
	}

	private final PickerSensor sensor;
	private final Rectangle svRect;
	private final Rectangle hueRect;
	private final int maxReads;
	private final double accept;
	private final double acceptLoose;
	private final boolean onlineRefit;

	private HsvPickerModel model;
	private Integer lastHueClick;

	// Online-refit observation pools: (px, t) pairs per axis
	private final List<double[]> sObservations = new ArrayList<>();
	private final List<double[]> vObservations = new ArrayList<>();
	private final List<double[]> hObservations = new ArrayList<>();

	public ColorEntryController(PickerSensor sensor, HsvPickerModel model, Rectangle svRect, Rectangle hueRect) {
		this(sensor, model, svRect, hueRect, DEFAULT_MAX_READS, DEFAULT_ACCEPT, DEFAULT_ACCEPT_LOOSE, true);
	}

	public ColorEntryController(PickerSensor sensor, HsvPickerModel model, Rectangle svRect, Rectangle hueRect,
			int maxReads, double accept, double acceptLoose, boolean onlineRefit) {
		this.sensor = sensor;
		this.model = model;
		this.svRect = svRect;
		this.hueRect = hueRect;
		this.maxReads = maxReads;
		this.accept = accept;
		this.acceptLoose = acceptLoose;
		this.onlineRefit = onlineRefit;
	}

	/** The current (possibly online-refitted) model. */
	public HsvPickerModel getModel() {
		return model;
	}

	/** Enters one target color; never throws on mis-verification — inspect the result. */
	public Result enter(int targetRgb) throws PaintingInterrupted {
		targetRgb |= 0xff000000;
		int[] clicks = clampToRects(model.clicksFor(targetRgb));
		int predicted = model.predictedColor(clicks[0], clicks[1], clicks[2]);
		double[] targetHsv = HsvColor.rgbToHsv(targetRgb);

		int bestRgb = 0;
		int[] bestClicks = null;   // the picker position that produced bestRgb (to re-issue on loose adoption)
		int[] lastClicked = null;  // the position the picker physically reflects right now
		double bestDeltaE = Double.MAX_VALUE;
		int reads = 0;
		int clickCount = 0;
		Set<Long> tried = new HashSet<>();
		tried.add(positionKey(clicks));

		while (reads < maxReads) {
			if (lastHueClick == null || lastHueClick != clicks[2]) {
				sensor.clickHue(clicks[2]);
				lastHueClick = clicks[2];
				clickCount++;
			}
			sensor.clickSv(clicks[0], clicks[1]);
			lastClicked = clicks;   // the picker now physically reflects `clicks`
			clickCount++;

			int got = sensor.readSwatch() | 0xff000000;
			reads++;
			observe(clicks, got);

			double deltaE = Ciede2000.deltaE(got, targetRgb);
			if (deltaE < bestDeltaE) {
				bestDeltaE = deltaE;
				bestRgb = got;
				bestClicks = clicks;
			}
			if (got == predicted || deltaE <= accept) {
				maybeRefit();
				return new Result(Status.ADOPTED, got, reads, clickCount, false, deltaE);
			}

			int[] next = nextPosition(clicks, targetHsv, got);
			if (next == null || !tried.add(positionKey(next))) {
				break; // no movement possible, or the position was already tried
			}
			clicks = next;
		}

		maybeRefit();
		if (bestDeltaE <= acceptLoose) {
			// The picker physically reflects `lastClicked`, but the best read-back was at
			// `bestClicks`. Adopting bestRgb while the picker sits at a different (worse)
			// position would paint a DIFFERENT color than we record (PalettizedPainter never
			// re-issues color per stamp). Re-issue the best position so the picker's live
			// state == the adopted color — the "canvas == adopted palette, byte-exact" contract.
			if (bestClicks != lastClicked) {
				if (lastHueClick == null || lastHueClick != bestClicks[2]) {
					sensor.clickHue(bestClicks[2]);
					lastHueClick = bestClicks[2];
					clickCount++;
				}
				sensor.clickSv(bestClicks[0], bestClicks[1]);
				clickCount++;
			}
			return new Result(Status.ADOPTED, bestRgb, reads, clickCount, true, bestDeltaE);
		}
		return new Result(Status.FAILED, 0, reads, clickCount, false, bestDeltaE);
	}

	/**
	 * The §2.5 nudge: per-axis error in t-space scaled to pixels by the
	 * fitted axis scales, clamped to ±3 px; if every axis rounds to zero, a
	 * single ±1 px probe in the largest-scaled-error axis. Greys never chase
	 * hue noise because both the target's and the read-back's hue collapse
	 * to 0 at zero chroma, and residual quantization noise is bounded by the
	 * ±3 px clamp plus the already-tried-position stop.
	 */
	private int[] nextPosition(int[] clicks, double[] targetHsv, int gotRgb) {
		double[] gotHsv = HsvColor.rgbToHsv(gotRgb);

		double errS = (targetHsv[1] - gotHsv[1]) * model.s().scale();
		double errV = ((1.0 - targetHsv[2]) - (1.0 - gotHsv[2])) * model.v().scale();
		double errH = HsvColor.wrapHue((1.0 - targetHsv[0]) - (1.0 - gotHsv[0])) * model.h().scale();

		int stepX = clampStep(errS);
		int stepY = clampStep(errV);
		int stepYh = clampStep(errH);

		if (stepX == 0 && stepY == 0 && stepYh == 0) {
			double aS = Math.abs(errS), aV = Math.abs(errV), aH = Math.abs(errH);
			if (aS >= aV && aS >= aH && aS > 0) {
				stepX = errS > 0 ? 1 : -1;
			} else if (aV >= aH && aV > 0) {
				stepY = errV > 0 ? 1 : -1;
			} else if (aH > 0) {
				stepYh = errH > 0 ? 1 : -1;
			} else {
				return null; // zero error everywhere — nothing to try
			}
		}

		return clampToRects(new int[] { clicks[0] + stepX, clicks[1] + stepY, clicks[2] + stepYh });
	}

	private static int clampStep(double errPx) {
		return Math.max(-MAX_STEP_PX, Math.min(MAX_STEP_PX, (int) Math.round(errPx)));
	}

	private int[] clampToRects(int[] clicks) {
		return new int[] {
			clamp(clicks[0], svRect.x, svRect.x + svRect.width - 1),
			clamp(clicks[1], svRect.y, svRect.y + svRect.height - 1),
			clamp(clicks[2], hueRect.y, hueRect.y + hueRect.height - 1)
		};
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static long positionKey(int[] clicks) {
		return ((long) clicks[0] << 42) ^ ((long) clicks[1] << 21) ^ clicks[2];
	}

	// ---------------------------------------------------------- online refit

	/**
	 * Every read-back is a free {@code (click px, t)} observation; only
	 * well-conditioned ones are kept (V readable, hue observable, and the
	 * parameter not saturated — a click that landed outside the real control
	 * reads back a clamped t that would bias the fit).
	 */
	private void observe(int[] clicks, int gotRgb) {
		if (!onlineRefit) {
			return;
		}
		double[] hsv = HsvColor.rgbToHsv(gotRgb);
		if (hsv[2] > SV_OBSERVABLE_MIN_V) {
			addUnsaturated(sObservations, clicks[0], hsv[1]);
			addUnsaturated(vObservations, clicks[1], 1.0 - hsv[2]);
		}
		if (hsv[1] * hsv[2] > HUE_OBSERVABLE_MIN_SV) {
			addUnsaturated(hObservations, clicks[2], 1.0 - hsv[0]);
		}
	}

	private static void addUnsaturated(List<double[]> pool, int px, double t) {
		if (t > 0.01 && t < 0.99) {
			pool.add(new double[] { px, t });
		}
	}

	/** Per-axis refit with the accumulated pairs, after each color entry. */
	private void maybeRefit() {
		if (!onlineRefit) {
			return;
		}
		HsvPickerModel.Axis s = refitAxis(sObservations, model.s());
		HsvPickerModel.Axis v = refitAxis(vObservations, model.v());
		HsvPickerModel.Axis h = refitAxis(hObservations, model.h());
		model = new HsvPickerModel(s, v, h);
	}

	private static HsvPickerModel.Axis refitAxis(List<double[]> pairs, HsvPickerModel.Axis prior) {
		if (pairs.size() < REFIT_MIN_OBSERVATIONS) {
			return prior;
		}
		double minPx = Double.MAX_VALUE, maxPx = -Double.MAX_VALUE;
		double[] ts = new double[pairs.size()];
		int[] pxs = new int[pairs.size()];
		for (int i = 0; i < pairs.size(); i++) {
			pxs[i] = (int) pairs.get(i)[0];
			ts[i] = pairs.get(i)[1];
			minPx = Math.min(minPx, pxs[i]);
			maxPx = Math.max(maxPx, pxs[i]);
		}
		if (maxPx - minPx < REFIT_MIN_SPREAD_PX) {
			return prior; // not enough spread to identify the scale
		}
		HsvPickerModel.AxisFit fit = ProbePlanner.fitTrimmed(ts, pxs);
		return fit == null ? prior : fit.axis();
	}
}
