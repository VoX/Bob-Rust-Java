package com.bobrust.robot.hsv;

import java.util.Locale;

/**
 * The fitted click→color mapping of the game's HSV picker
 * (PLAN-PALETTIZED-MODE.md §2.3): one linear axis each for saturation
 * (SV-square x), value (SV-square y) and hue (hue-bar y). Nothing about the
 * orientation is assumed — a flipped axis simply fits a negative scale.
 *
 * <p>Axis convention (matches the screenshot measurements, §1):
 * <pre>
 * x_px  ≈ x0 + S · wx          (t = s)
 * y_px  ≈ y0 + (1 − V) · wy    (t = 1 − v)
 * yh_px ≈ h0 + (1 − H) · wh    (t = 1 − h, hue as a fraction of 360°)
 * </pre>
 * where "≈" is "the click pixel whose center lands nearest": a click on
 * integer pixel {@code px} reads back {@code t = (px + 0.5 − offset)/scale}.
 *
 * <p>Serialized as {@code x0=…;wx=…;y0=…;wy=…;h0=…;wh=…}
 * ({@code Settings.SettingsHsvPicker}).
 */
public record HsvPickerModel(Axis s, Axis v, Axis h) {
	/** One linear axis: {@code pxCenter = offset + scale · t}. */
	public record Axis(double offset, double scale) {
		/** The integer pixel whose center is nearest to parameter {@code t}. */
		public int pxFor(double t) {
			return (int) Math.round(offset + scale * t - 0.5);
		}

		/** The parameter value a click on integer pixel {@code px} selects. */
		public double tFor(int px) {
			return (px + 0.5 - offset) / scale;
		}
	}

	/** Result of a least-squares axis fit: the axis and its worst residual in px. */
	public record AxisFit(Axis axis, double maxResidualPx) {
	}

	/**
	 * The integer picker clicks {@code {x, y, yh}} that best select
	 * {@code rgb} under this model (unclamped — the caller clamps to the
	 * calibrated rects).
	 */
	public int[] clicksFor(int rgb) {
		double[] hsv = HsvColor.rgbToHsv(rgb);
		return new int[] {
			s.pxFor(hsv[1]),
			v.pxFor(1.0 - hsv[2]),
			h.pxFor(1.0 - hsv[0])
		};
	}

	/**
	 * The color this model predicts the picker shows for the given integer
	 * clicks — the preview's promise, and the byte-exact convergence target
	 * of the closed loop.
	 */
	public int predictedColor(int xPx, int yPx, int yhPx) {
		double sat = clamp01(s.tFor(xPx));
		double val = 1.0 - clamp01(v.tFor(yPx));
		double hue = 1.0 - clamp01(h.tFor(yhPx));
		return HsvColor.hsvToRgb(hue, sat, val);
	}

	/**
	 * Snaps {@code rgb} to the picker's predicted-reachable color set: the
	 * color the picker would show after clicking {@link #clicksFor}. A pure
	 * function of the model, so preview == plan == paint (§2.4).
	 */
	public int snapToReachable(int rgb) {
		int[] clicks = clicksFor(rgb);
		return predictedColor(clicks[0], clicks[1], clicks[2]);
	}

	/**
	 * Least-squares fit of one axis from {@code (t, px)} observations:
	 * solves {@code px + 0.5 = offset + scale · t}. Requires at least two
	 * observations with distinct {@code t}; returns null when degenerate.
	 */
	public static AxisFit fitAxis(double[] ts, int[] pxs) {
		int n = ts.length;
		if (n < 2 || n != pxs.length) {
			return null;
		}
		double sumT = 0, sumP = 0, sumTT = 0, sumTP = 0;
		for (int i = 0; i < n; i++) {
			double p = pxs[i] + 0.5;
			sumT += ts[i];
			sumP += p;
			sumTT += ts[i] * ts[i];
			sumTP += ts[i] * p;
		}
		double det = n * sumTT - sumT * sumT;
		if (Math.abs(det) < 1e-9) {
			return null; // all t equal — scale unidentifiable
		}
		double scale = (n * sumTP - sumT * sumP) / det;
		double offset = (sumP - scale * sumT) / n;

		double maxResidual = 0;
		for (int i = 0; i < n; i++) {
			maxResidual = Math.max(maxResidual, Math.abs(offset + scale * ts[i] - (pxs[i] + 0.5)));
		}
		return new AxisFit(new Axis(offset, scale), maxResidual);
	}

	public String serialize() {
		return String.format(Locale.ROOT, "x0=%s;wx=%s;y0=%s;wy=%s;h0=%s;wh=%s",
			s.offset(), s.scale(), v.offset(), v.scale(), h.offset(), h.scale());
	}

	/** Parses the serialized form; returns null on missing keys or malformed input. */
	public static HsvPickerModel parse(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		Double x0 = null, wx = null, y0 = null, wy = null, h0 = null, wh = null;
		try {
			for (String part : text.split(";")) {
				String[] pair = part.split("=", 2);
				if (pair.length != 2) {
					continue;
				}
				double value = Double.parseDouble(pair[1].trim());
				switch (pair[0].trim()) {
					case "x0" -> x0 = value;
					case "wx" -> wx = value;
					case "y0" -> y0 = value;
					case "wy" -> wy = value;
					case "h0" -> h0 = value;
					case "wh" -> wh = value;
				}
			}
		} catch (NumberFormatException ignored) {
			return null;
		}
		if (x0 == null || wx == null || y0 == null || wy == null || h0 == null || wh == null
				|| wx == 0 || wy == 0 || wh == 0) {
			return null;
		}
		return new HsvPickerModel(new Axis(x0, wx), new Axis(y0, wy), new Axis(h0, wh));
	}

	/**
	 * A prior model derived straight from the user-marked rects, assuming the
	 * measured orientations (§1) — the probe pass replaces it with the fitted
	 * truth before any color entry.
	 */
	public static HsvPickerModel fromRects(java.awt.Rectangle svRect, java.awt.Rectangle hueRect) {
		return new HsvPickerModel(
			new Axis(svRect.x, svRect.width),
			new Axis(svRect.y, svRect.height),
			new Axis(hueRect.y, hueRect.height)
		);
	}

	private static double clamp01(double t) {
		return t < 0 ? 0 : Math.min(t, 1);
	}
}
