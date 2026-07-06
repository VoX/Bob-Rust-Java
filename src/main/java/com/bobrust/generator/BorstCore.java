package com.bobrust.generator;

class BorstCore {
	static BorstColor computeColor(BorstImage target, BorstImage current, int alpha, int size, int x_offset, int y_offset) {
		long rsum_1 = 0;
		long gsum_1 = 0;
		long bsum_1 = 0;
		
		long rsum_2 = 0;
		long gsum_2 = 0;
		long bsum_2 = 0;
		
		int count = 0;
		int w = target.width;
		int h = target.height;
		
		final Scanline[] lines = CircleCache.CIRCLE_CACHE[size];
		final int len = lines.length;
		for (int i = 0; i < len; i++) {
			Scanline line = lines[i];
			int y = line.y + y_offset;
			if (y < 0 || y >= h) {
				continue;
			}
			
			int xs = Math.max(line.x1 + x_offset, 0);
			int xe = Math.min(line.x2 + x_offset, w - 1);
			int idx = y * w;
			
			if (xs > xe) continue;

			for (int x = xs; x <= xe; x++) {
				int tt = target.pixels[idx + x];
				int cc = current.pixels[idx + x];

				rsum_1 += (tt >>> 16) & 0xff;
				gsum_1 += (tt >>>  8) & 0xff;
				bsum_1 += (tt       ) & 0xff;

				rsum_2 += (cc >>> 16) & 0xff;
				gsum_2 += (cc >>>  8) & 0xff;
				bsum_2 += (cc       ) & 0xff;
			}

			count += (xe - xs + 1);
		}

		// Guard against division by zero when circle is entirely out of bounds
		if (count == 0) {
			return BorstUtils.COLORS[0];
		}

		int pd = 65280 / alpha; // (255 << 8) / alpha;
		long rsum = (rsum_1 - rsum_2) * pd + (rsum_2 << 8);
		long gsum = (gsum_1 - gsum_2) * pd + (gsum_2 << 8);
		long bsum = (bsum_1 - bsum_2) * pd + (bsum_2 << 8);

		int r = (int)(rsum / (double)count) >> 8;
		int g = (int)(gsum / (double)count) >> 8;
		int b = (int)(bsum / (double)count) >> 8;
		r = BorstUtils.clampInt(r, 0, 255);
		g = BorstUtils.clampInt(g, 0, 255);
		b = BorstUtils.clampInt(b, 0, 255);
		
		return BorstUtils.getClosestColor((alpha << 24) | (r << 16) | (g << 8) | (b));
	}
	
	// NOTE: This forward blend divides by 256 (>>> 8) while computeColor's
	// inverse assumes the standard /255 blend, so the model's own forward and
	// inverse disagree by ~0.4% and every stamp is simulated one level dark at
	// full opacity. Which divisor matches the game's actual compositing is
	// unknown without measured calibration data, so this is deliberately left
	// unchanged — see the calibration tool. Fix both sides together or not at all.
	static void drawLines(BorstImage im, BorstColor c, int alpha, int size, int x_offset, int y_offset) {
		int cr = c.r * alpha;
		int cg = c.g * alpha;
		int cb = c.b * alpha;
		int pa = 255 - alpha;
		int w = im.width;
		int h = im.height;
		
		final Scanline[] lines = CircleCache.CIRCLE_CACHE[size];
		final int len = lines.length;
		for (int i = 0; i < len; i++) {
			Scanline line = lines[i];
			int y = line.y + y_offset;
			if (y < 0 || y >= h) {
				continue;
			}
			
			int xs = Math.max(line.x1 + x_offset, 0);
			int xe = Math.min(line.x2 + x_offset, w - 1);
			int idx = y * w;
			
			for (int x = xs; x <= xe; x++) {
				int a = im.pixels[idx + x];
				int a_a = (a >>> 24) & 0xff;
				int a_r = (a >>> 16) & 0xff;
				int a_g = (a >>>  8) & 0xff;
				int a_b = (a       ) & 0xff;
				
				int ar = (cr + (a_r * pa)) >>> 8;
				int ag = (cg + (a_g * pa)) >>> 8;
				int ab = (cb + (a_b * pa)) >>> 8;
				int aa = 255 - (((255 - a_a) * pa) >>> 8);
				
				im.pixels[idx + x] = (aa << 24) | (ar << 16) | (ag << 8) | (ab);
			}
		}
	}
	
	/**
	 * Convert an exact squared-error total to the normalized display score.
	 * The energy carry is kept as an exact {@code long} everywhere internally
	 * (see {@link #differenceFullTotal} / {@link #differencePartialTotal});
	 * the float score is derived only at this boundary. Reconstructing the
	 * total from the rounded float — as the code previously did each step —
	 * injected up to ~total * 2^-23 error per shape and could go negative near
	 * convergence, turning the score into NaN and silently stalling the search.
	 */
	static float scoreFromTotal(long total, int w, int h) {
		return (float)(Math.sqrt(total / (w * h * 4.0)) / 255.0);
	}

	static float differenceFull(BorstImage a, BorstImage b) {
		return scoreFromTotal(differenceFullTotal(a, b), a.width, a.height);
	}

	static long differenceFullTotal(BorstImage a, BorstImage b) {
		final int w = a.width;
		final int h = a.height;

		long total = 0;

		final int length = w * h;
		for(int i = 0; i < length; i++) {
			int aa = a.pixels[i];
			int bb = b.pixels[i];
			
			int aa_a = (aa >>> 24) & 0xff;
			int aa_r = (aa >>> 16) & 0xff;
			int aa_g = (aa >>>  8) & 0xff;
			int aa_b = (aa       ) & 0xff;
			
			int bb_a = (bb >>> 24) & 0xff;
			int bb_r = (bb >>> 16) & 0xff;
			int bb_g = (bb >>>  8) & 0xff;
			int bb_b = (bb       ) & 0xff;
			
			int da = aa_a - bb_a;
			int dr = aa_r - bb_r;
			int dg = aa_g - bb_g;
			int db = aa_b - bb_b;
			
			total += (dr*dr + dg*dg + db*db + da*da);
		}

		return total;
	}

	/**
	 * Exact incremental update of the squared-error total: subtracts the
	 * before-error and adds the after-error over the circle's clipped pixels.
	 * Takes and returns the exact {@code long} total, so repeated application
	 * stays provably identical to {@link #differenceFullTotal}.
	 */
	static long differencePartialTotal(BorstImage target, BorstImage before, BorstImage after, long total, int size, int x_offset, int y_offset) {
		int w = target.width;
		int h = target.height;

		final Scanline[] lines = CircleCache.CIRCLE_CACHE[size];
		final int len = lines.length;
		for (int i = 0; i < len; i++) {
			Scanline line = lines[i];
			int y = line.y + y_offset;
			if (y < 0 || y >= h) {
				continue;
			}
			
			int xs = Math.max(line.x1 + x_offset, 0);
			int xe = Math.min(line.x2 + x_offset, w - 1);
			int idx = y * w;
			
			for (int x = xs; x <= xe; x++) {
				int tt = target.pixels[idx + x];
				int bb = before.pixels[idx + x];
				int aa = after.pixels[idx + x];
				
				int tt_a = (tt >>> 24) & 0xff;
				int tt_r = (tt >>> 16) & 0xff;
				int tt_g = (tt >>>  8) & 0xff;
				int tt_b = (tt       ) & 0xff;
				
				int aa_a = (aa >>> 24) & 0xff;
				int aa_r = (aa >>> 16) & 0xff;
				int aa_g = (aa >>>  8) & 0xff;
				int aa_b = (aa       ) & 0xff;
				
				int bb_a = (bb >>> 24) & 0xff;
				int bb_r = (bb >>> 16) & 0xff;
				int bb_g = (bb >>>  8) & 0xff;
				int bb_b = (bb       ) & 0xff;
				
				int da1 = tt_a - bb_a;
				int dr1 = tt_r - bb_r;
				int dg1 = tt_g - bb_g;
				int db1 = tt_b - bb_b;
				
				int da2 = tt_a - aa_a;
				int dr2 = tt_r - aa_r;
				int dg2 = tt_g - aa_g;
				int db2 = tt_b - aa_b;
				
				total -= (long)(dr1*dr1 + dg1*dg1 + db1*db1 + da1*da1);
				total += (long)(dr2*dr2 + dg2*dg2 + db2*db2 + da2*da2);
			}
		}

		return total;
	}

	static float differencePartialThread(BorstImage target, BorstImage before, long baseTotal, int alpha, int size, int x_offset, int y_offset, boolean useBatchParallel) {
		return differencePartialThread(target, before, baseTotal, alpha, size, x_offset, y_offset, useBatchParallel, null);
	}

	/**
	 * Exact candidate energy. When {@code colorOut} is non-null, the optimal
	 * palette color the kernel derived for this circle is stored in
	 * {@code colorOut[0]} so the commit path can reuse it instead of re-running
	 * {@link #computeColor} (it may be left untouched for fully out-of-bounds
	 * circles, where the kernel derives no color).
	 */
	static float differencePartialThread(BorstImage target, BorstImage before, long baseTotal, int alpha, int size, int x_offset, int y_offset, boolean useBatchParallel, BorstColor[] colorOut) {
		if (useBatchParallel) {
			return differencePartialThreadCombined(target, before, baseTotal, alpha, size, x_offset, y_offset, colorOut);
		}
		return differencePartialThreadClassic(target, before, baseTotal, alpha, size, x_offset, y_offset, colorOut);
	}

	static float differencePartialThreadClassic(BorstImage target, BorstImage before, long baseTotal, int alpha, int size, int x_offset, int y_offset) {
		return differencePartialThreadClassic(target, before, baseTotal, alpha, size, x_offset, y_offset, null);
	}

	/**
	 * Classic two-pass implementation: computeColor then energy calculation.
	 * Used as fallback when USE_BATCH_PARALLEL is false.
	 */
	static float differencePartialThreadClassic(BorstImage target, BorstImage before, long baseTotal, int alpha, int size, int x_offset, int y_offset, BorstColor[] colorOut) {
		BorstColor color = BorstCore.computeColor(target, before, alpha, size, x_offset, y_offset);
		if (colorOut != null) {
			colorOut[0] = color;
		}

		final int h = target.height;
		final int w = target.width;

		long total = baseTotal;

		final int cr = color.r * alpha;
		final int cg = color.g * alpha;
		final int cb = color.b * alpha;
		final int pa = 255 - alpha;

		final Scanline[] lines = CircleCache.CIRCLE_CACHE[size];
		final int len = lines.length;

		for (int i = 0; i < len; i++) {
			Scanline line = lines[i];
			int y = line.y + y_offset;
			if (y < 0 || y >= h) {
				continue;
			}

			int xs = Math.max(line.x1 + x_offset, 0);
			int xe = Math.min(line.x2 + x_offset, w - 1);
			int idx = y * w;

			for (int x = xs; x <= xe; x++) {
				int tt = target.pixels[idx + x];
				int bb = before.pixels[idx + x];

				int bb_a = (bb >>> 24) & 0xff;
				int bb_r = (bb >>> 16) & 0xff;
				int bb_g = (bb >>>  8) & 0xff;
				int bb_b = (bb       ) & 0xff;

				int aa_r = (cr + (bb_r * pa)) >>> 8;
				int aa_g = (cg + (bb_g * pa)) >>> 8;
				int aa_b = (cb + (bb_b * pa)) >>> 8;
				int aa_a = 255 - (((255 - bb_a) * pa) >>> 8);

				int tt_a = (tt >>> 24) & 0xff;
				int tt_r = (tt >>> 16) & 0xff;
				int tt_g = (tt >>>  8) & 0xff;
				int tt_b = (tt       ) & 0xff;

				int da1 = tt_a - bb_a;
				int dr1 = tt_r - bb_r;
				int dg1 = tt_g - bb_g;
				int db1 = tt_b - bb_b;

				int da2 = tt_a - aa_a;
				int dr2 = tt_r - aa_r;
				int dg2 = tt_g - aa_g;
				int db2 = tt_b - aa_b;

				total -= (long)(dr1*dr1 + dg1*dg1 + db1*db1 + da1*da1);
				total += (long)(dr2*dr2 + dg2*dg2 + db2*db2 + da2*da2);
			}
		}

		return scoreFromTotal(total, w, h);
	}

	/**
	 * Combined implementation that merges computeColor and the energy
	 * calculation. Pass 1 accumulates color sums AND before-error in one scan
	 * over the circle pixels; pass 2 computes the after-error with the color
	 * terms hoisted out of the loop. Verified numerically identical to the
	 * classic path by BatchParallelEnergyTest. (Both variants make the same
	 * four array traversals per pixel, so expect comparable speed, not the
	 * large win the original proposal claimed.)
	 */
	static float differencePartialThreadCombined(BorstImage target, BorstImage before, long baseTotal, int alpha, int size, int x_offset, int y_offset) {
		return differencePartialThreadCombined(target, before, baseTotal, alpha, size, x_offset, y_offset, null);
	}

	static float differencePartialThreadCombined(BorstImage target, BorstImage before, long baseTotal, int alpha, int size, int x_offset, int y_offset, BorstColor[] colorOut) {
		final int h = target.height;
		final int w = target.width;
		final int pa = 255 - alpha;

		final Scanline[] lines = CircleCache.CIRCLE_CACHE[size];
		final int len = lines.length;

		// --- Pass 1: accumulate color sums AND before-error simultaneously ---
		long rsum_1 = 0, gsum_1 = 0, bsum_1 = 0;
		long rsum_2 = 0, gsum_2 = 0, bsum_2 = 0;
		long beforeError = 0;
		int count = 0;

		for (int i = 0; i < len; i++) {
			Scanline line = lines[i];
			int y = line.y + y_offset;
			if (y < 0 || y >= h) {
				continue;
			}

			int xs = Math.max(line.x1 + x_offset, 0);
			int xe = Math.min(line.x2 + x_offset, w - 1);
			if (xs > xe) continue;
			int idx = y * w;

			for (int x = xs; x <= xe; x++) {
				int tt = target.pixels[idx + x];
				int cc = before.pixels[idx + x];

				int tt_a = (tt >>> 24) & 0xff;
				int tt_r = (tt >>> 16) & 0xff;
				int tt_g = (tt >>>  8) & 0xff;
				int tt_b = (tt       ) & 0xff;

				int cc_a = (cc >>> 24) & 0xff;
				int cc_r = (cc >>> 16) & 0xff;
				int cc_g = (cc >>>  8) & 0xff;
				int cc_b = (cc       ) & 0xff;

				// Accumulate color sums (same as computeColor)
				rsum_1 += tt_r;
				gsum_1 += tt_g;
				bsum_1 += tt_b;

				rsum_2 += cc_r;
				gsum_2 += cc_g;
				bsum_2 += cc_b;

				// Accumulate before-error (target vs current)
				int da1 = tt_a - cc_a;
				int dr1 = tt_r - cc_r;
				int dg1 = tt_g - cc_g;
				int db1 = tt_b - cc_b;
				beforeError += (long)(dr1*dr1 + dg1*dg1 + db1*db1 + da1*da1);
			}

			count += (xe - xs + 1);
		}

		// Guard against division by zero when circle is entirely out of bounds
		if (count == 0) {
			return scoreFromTotal(baseTotal, w, h);
		}

		// Compute optimal color from sums (same math as computeColor)
		int pd = 65280 / alpha;
		long rsum = (rsum_1 - rsum_2) * pd + (rsum_2 << 8);
		long gsum = (gsum_1 - gsum_2) * pd + (gsum_2 << 8);
		long bsum = (bsum_1 - bsum_2) * pd + (bsum_2 << 8);

		int r = (int)(rsum / (double)count) >> 8;
		int g = (int)(gsum / (double)count) >> 8;
		int b = (int)(bsum / (double)count) >> 8;
		r = BorstUtils.clampInt(r, 0, 255);
		g = BorstUtils.clampInt(g, 0, 255);
		b = BorstUtils.clampInt(b, 0, 255);

		BorstColor color = BorstUtils.getClosestColor((alpha << 24) | (r << 16) | (g << 8) | (b));
		if (colorOut != null) {
			colorOut[0] = color;
		}

		// Hoist the color * alpha terms out of the pass 2 loop
		final int cr = color.r * alpha;
		final int cg = color.g * alpha;
		final int cb = color.b * alpha;

		// --- Pass 2: compute after-error only (we already have before-error) ---
		long afterError = 0;

		for (int i = 0; i < len; i++) {
			Scanline line = lines[i];
			int y = line.y + y_offset;
			if (y < 0 || y >= h) {
				continue;
			}

			int xs = Math.max(line.x1 + x_offset, 0);
			int xe = Math.min(line.x2 + x_offset, w - 1);
			int idx = y * w;

			for (int x = xs; x <= xe; x++) {
				int tt = target.pixels[idx + x];
				int bb = before.pixels[idx + x];

				int bb_a = (bb >>> 24) & 0xff;
				int bb_r = (bb >>> 16) & 0xff;
				int bb_g = (bb >>>  8) & 0xff;
				int bb_b = (bb       ) & 0xff;

				// Alpha-blend using precomputed color*alpha values
				int aa_r = (cr + (bb_r * pa)) >>> 8;
				int aa_g = (cg + (bb_g * pa)) >>> 8;
				int aa_b = (cb + (bb_b * pa)) >>> 8;
				int aa_a = 255 - (((255 - bb_a) * pa) >>> 8);

				int tt_a = (tt >>> 24) & 0xff;
				int tt_r = (tt >>> 16) & 0xff;
				int tt_g = (tt >>>  8) & 0xff;
				int tt_b = (tt       ) & 0xff;

				int da2 = tt_a - aa_a;
				int dr2 = tt_r - aa_r;
				int dg2 = tt_g - aa_g;
				int db2 = tt_b - aa_b;
				afterError += (long)(dr2*dr2 + dg2*dg2 + db2*db2 + da2*da2);
			}
		}

		// Combine: total = baseTotal - beforeError + afterError
		long total = baseTotal - beforeError + afterError;

		return scoreFromTotal(total, w, h);
	}

	/**
	 * Sampling stride per circle size index for {@link #differencePartialProxy}.
	 * The two largest sizes are ~93% of uniform candidate-eval work (a d=100
	 * circle costs 360x a d=3 one), so they get the coarsest sampling; small
	 * circles have too few pixels to subsample and stay exact.
	 *
	 * <p>Tuned on the F1 corpus: {2,4,4} holds quality parity (metric sums
	 * within ~0.3% of exact ranking). {2,4,6} was ~7% faster but drifted the
	 * corpus ΔE00 sum +1.4%; {2,6,8} broke parity outright (nature ΔE00 +30%)
	 * and was net slower — a badly ranked winner costs more downstream than
	 * the sampling saves.
	 */
	static final int[] PROXY_STRIDE = { 1, 1, 1, 2, 4, 4 };

	/**
	 * Approximate candidate energy on a strided pixel subset — RANKING ONLY.
	 *
	 * <p>Samples every stride-th scanline and every stride-th pixel within it,
	 * derives the optimal color from the sampled sums (same math as
	 * {@link #computeColor}), and scales the sampled error delta by stride^2 to
	 * estimate the full-area delta. Sizes with stride 1 delegate to the exact
	 * combined kernel, so proxy == exact for small circles.
	 *
	 * <p>The returned score is only ever compared against other candidates'
	 * proxy scores to pick the step's winner; the winner is then re-evaluated
	 * from scratch with the exact kernels (HillClimbGenerator invalidates its
	 * memoized score), so the committed geometry, color and running total are
	 * bit-for-bit unaffected by this approximation.
	 */
	static float differencePartialProxy(BorstImage target, BorstImage before, long baseTotal, int alpha, int size, int x_offset, int y_offset) {
		final int stride = PROXY_STRIDE[size];
		if (stride == 1) {
			return differencePartialThreadCombined(target, before, baseTotal, alpha, size, x_offset, y_offset);
		}

		final int h = target.height;
		final int w = target.width;
		final int pa = 255 - alpha;

		final Scanline[] lines = CircleCache.CIRCLE_CACHE[size];
		final int len = lines.length;

		// --- Pass 1 (strided): color sums + before-error over the sample ---
		long rsum_1 = 0, gsum_1 = 0, bsum_1 = 0;
		long rsum_2 = 0, gsum_2 = 0, bsum_2 = 0;
		long beforeError = 0;
		int count = 0;

		for (int i = 0; i < len; i += stride) {
			Scanline line = lines[i];
			int y = line.y + y_offset;
			if (y < 0 || y >= h) {
				continue;
			}

			int xs = Math.max(line.x1 + x_offset, 0);
			int xe = Math.min(line.x2 + x_offset, w - 1);
			if (xs > xe) continue;
			int idx = y * w;

			for (int x = xs; x <= xe; x += stride) {
				int tt = target.pixels[idx + x];
				int cc = before.pixels[idx + x];

				int tt_a = (tt >>> 24) & 0xff;
				int tt_r = (tt >>> 16) & 0xff;
				int tt_g = (tt >>>  8) & 0xff;
				int tt_b = (tt       ) & 0xff;

				int cc_a = (cc >>> 24) & 0xff;
				int cc_r = (cc >>> 16) & 0xff;
				int cc_g = (cc >>>  8) & 0xff;
				int cc_b = (cc       ) & 0xff;

				rsum_1 += tt_r;
				gsum_1 += tt_g;
				bsum_1 += tt_b;

				rsum_2 += cc_r;
				gsum_2 += cc_g;
				bsum_2 += cc_b;

				int da1 = tt_a - cc_a;
				int dr1 = tt_r - cc_r;
				int dg1 = tt_g - cc_g;
				int db1 = tt_b - cc_b;
				beforeError += (long)(dr1*dr1 + dg1*dg1 + db1*db1 + da1*da1);
				count++;
			}
		}

		// Entirely out of bounds — same behavior as the exact kernels
		if (count == 0) {
			return scoreFromTotal(baseTotal, w, h);
		}

		// Optimal color from the sampled sums (same math as computeColor)
		int pd = 65280 / alpha;
		long rsum = (rsum_1 - rsum_2) * pd + (rsum_2 << 8);
		long gsum = (gsum_1 - gsum_2) * pd + (gsum_2 << 8);
		long bsum = (bsum_1 - bsum_2) * pd + (bsum_2 << 8);

		int r = BorstUtils.clampInt((int)(rsum / (double)count) >> 8, 0, 255);
		int g = BorstUtils.clampInt((int)(gsum / (double)count) >> 8, 0, 255);
		int b = BorstUtils.clampInt((int)(bsum / (double)count) >> 8, 0, 255);

		BorstColor color = BorstUtils.getClosestColor((alpha << 24) | (r << 16) | (g << 8) | (b));

		final int cr = color.r * alpha;
		final int cg = color.g * alpha;
		final int cb = color.b * alpha;

		// --- Pass 2 (strided, same sample): after-error ---
		long afterError = 0;

		for (int i = 0; i < len; i += stride) {
			Scanline line = lines[i];
			int y = line.y + y_offset;
			if (y < 0 || y >= h) {
				continue;
			}

			int xs = Math.max(line.x1 + x_offset, 0);
			int xe = Math.min(line.x2 + x_offset, w - 1);
			int idx = y * w;

			for (int x = xs; x <= xe; x += stride) {
				int tt = target.pixels[idx + x];
				int bb = before.pixels[idx + x];

				int bb_a = (bb >>> 24) & 0xff;
				int bb_r = (bb >>> 16) & 0xff;
				int bb_g = (bb >>>  8) & 0xff;
				int bb_b = (bb       ) & 0xff;

				int aa_r = (cr + (bb_r * pa)) >>> 8;
				int aa_g = (cg + (bb_g * pa)) >>> 8;
				int aa_b = (cb + (bb_b * pa)) >>> 8;
				int aa_a = 255 - (((255 - bb_a) * pa) >>> 8);

				int tt_a = (tt >>> 24) & 0xff;
				int tt_r = (tt >>> 16) & 0xff;
				int tt_g = (tt >>>  8) & 0xff;
				int tt_b = (tt       ) & 0xff;

				int da2 = tt_a - aa_a;
				int dr2 = tt_r - aa_r;
				int dg2 = tt_g - aa_g;
				int db2 = tt_b - aa_b;
				afterError += (long)(dr2*dr2 + dg2*dg2 + db2*db2 + da2*da2);
			}
		}

		// Scale the sampled delta back to a full-area estimate. The estimate can
		// dip below zero near convergence; clamp so the sqrt never sees a
		// negative value.
		long total = baseTotal + (afterError - beforeError) * (long)(stride * stride);
		if (total < 0) {
			total = 0;
		}

		return scoreFromTotal(total, w, h);
	}
}
