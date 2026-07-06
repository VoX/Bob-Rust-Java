package com.bobrust.generator;

import java.util.List;

/**
 * Diminishing-returns auto-stop rule (S4 / proposal P4). Pure, stateless decision function over the running list of
 * per-shape error contributions ({@link Model#getShapeContributions()} — each entry is the exact reduction of the
 * model's energy total the shape's commit produced).
 *
 * <p><b>Evidence.</b> The SSIM-vs-clicks curve is geometric: each +3k clicks buys roughly 0.75x the previous step's
 * gain, so ~12k clicks retain ~95% of the 18k-click SSIM (see docs/SPEED-QUALITY-PROPOSALS.md §4). The knee is
 * per-image — no fixed preset can express it — so we detect it online from the contribution stream.
 *
 * <p><b>Rule.</b> With trailing windows of {@code window} shapes: let {@code S1} = the sum of contributions in the
 * previous window, {@code S2} = the sum in the latest window, and {@code A} = the total banked reduction so far
 * (= initialError - currentError). Model the remaining achievable reduction as the geometric tail
 * {@code R = S2 * r / (1 - r)} with per-window ratio {@code r = S2 / S1} (a decaying series, so only meaningful when
 * {@code r < 1}). Stop once the banked fraction {@code A / (A + R) >= qualityStop}. Clicks-per-shape is roughly
 * constant within a run (~1.3), so per-shape windows are a faithful proxy for the per-click rate the SSIM curve is
 * measured against; {@code qualityStop} is therefore an honest energy-metric proxy, and the harness A/B maps it to
 * realized SSIM retention.
 *
 * <p>Guards: never fires before two full windows exist, when the prior window banked nothing ({@code S1 <= 0}), when
 * the series is not decaying ({@code r >= 1}), or when {@code qualityStop <= 0}.
 */
public final class DiminishingReturns {
	private DiminishingReturns() {}

	/**
	 * Whether generation has hit the diminishing-returns threshold — see the class doc for the rule.
	 *
	 * @param contributions per-shape error reductions in commit order (never mutated)
	 * @param qualityStop   banked-fraction threshold in (0, 1); {@code <= 0} disables the check
	 * @param window        trailing window size in shapes (e.g. 500)
	 * @return true iff the banked fraction of the projected achievable reduction has reached {@code qualityStop}
	 */
	public static boolean reached(List<Long> contributions, double qualityStop, int window) {
		if (qualityStop <= 0 || window < 1) {
			return false;
		}
		int n = contributions.size();
		if (n < 2 * window) {
			return false; // need a previous AND a latest full window
		}

		long s2 = 0; // latest window: [n - window, n)
		for (int i = n - window; i < n; i++) {
			s2 += contributions.get(i);
		}
		long s1 = 0; // previous window: [n - 2*window, n - window)
		for (int i = n - 2 * window; i < n - window; i++) {
			s1 += contributions.get(i);
		}
		if (s1 <= 0) {
			return false; // no measurable prior-window gain -> ratio undefined
		}

		double r = (double) s2 / (double) s1;
		if (r >= 1.0) {
			return false; // not decaying -> the geometric tail would diverge; keep going
		}

		long a = 0; // total banked reduction so far = initialError - currentError
		for (int i = 0; i < n; i++) {
			a += contributions.get(i);
		}
		if (a <= 0) {
			return false;
		}

		double remaining = s2 * r / (1.0 - r); // geometric tail estimate of the reduction still achievable
		double bankedFraction = a / (a + remaining);
		return bankedFraction >= qualityStop;
	}
}
