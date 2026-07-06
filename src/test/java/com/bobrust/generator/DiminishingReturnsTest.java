package com.bobrust.generator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link DiminishingReturns} stop rule. All synthetic — no generation.
 *
 * <p>The geometric fixtures use exact-integer blocks so the per-window ratio is exactly 3/4 with no float drift:
 * block j is {@code window} copies of {@code 4^(N-j) * 3^j}. For a list of {@code k} such blocks the banked fraction
 * {@code A/(A+R)} works out to exactly {@code 1 - (3/4)^k} (the {@code window} factor cancels), so with
 * {@code qualityStop=0.95} the rule first fires at {@code k=11} (1-0.75^11=0.9578) and not at {@code k=10}
 * (1-0.75^10=0.9437).
 */
class DiminishingReturnsTest {

	private static final int N_EXP = 12; // block value exponent base; supports up to N_EXP+1 blocks as exact longs

	/** {@code numBlocks} blocks of {@code window} entries each; block j = 4^(N_EXP-j) * 3^j (per-window ratio 3/4). */
	private static List<Long> geometricBlocks(int numBlocks, int window) {
		List<Long> list = new ArrayList<>();
		for (int j = 0; j < numBlocks; j++) {
			long v = 1;
			for (int p = 0; p < N_EXP - j; p++) v *= 4;
			for (int p = 0; p < j; p++) v *= 3;
			for (int w = 0; w < window; w++) list.add(v);
		}
		return list;
	}

	@Test
	void geometricRatioFiresAtExpectedWindow() {
		int w = 4;
		// 10 blocks -> banked fraction 0.9437 < 0.95 -> not yet
		assertFalse(DiminishingReturns.reached(geometricBlocks(10, w), 0.95, w),
			"1-0.75^10 = 0.9437 is below 0.95");
		// 11 blocks -> banked fraction 0.9578 >= 0.95 -> fire
		assertTrue(DiminishingReturns.reached(geometricBlocks(11, w), 0.95, w),
			"1-0.75^11 = 0.9578 is at/above 0.95");
	}

	@Test
	void flatContributionsNeverFire() {
		// r == 1 (no decay): the geometric tail would diverge, so it must never stop.
		List<Long> flat = new ArrayList<>(Collections.nCopies(20, 1000L));
		assertFalse(DiminishingReturns.reached(flat, 0.95, 4));
		assertFalse(DiminishingReturns.reached(flat, 0.5, 4));
	}

	@Test
	void qualityStopZeroNeverFires() {
		List<Long> steep = geometricBlocks(11, 4);
		assertFalse(DiminishingReturns.reached(steep, 0.0, 4), "qualityStop=0 disables the check");
		assertFalse(DiminishingReturns.reached(steep, -0.1, 4), "negative qualityStop disables the check");
	}

	@Test
	void fewerThanTwoWindowsNeverFires() {
		// 2*window - 1 entries: not enough for a previous AND a latest full window.
		List<Long> almost = new ArrayList<>(Collections.nCopies(2 * 4 - 1, 1000L));
		assertFalse(DiminishingReturns.reached(almost, 0.95, 4));
		assertFalse(DiminishingReturns.reached(new ArrayList<>(), 0.95, 4), "empty list never fires");
	}

	@Test
	void priorWindowZeroNeverFires() {
		// Previous window sums to 0 (ratio undefined) -> no stop, even though the latest window is large.
		List<Long> list = new ArrayList<>();
		for (int i = 0; i < 4; i++) list.add(0L);      // previous window: all zero
		for (int i = 0; i < 4; i++) list.add(1000L);   // latest window
		assertFalse(DiminishingReturns.reached(list, 0.95, 4));
	}

	@Test
	void lowerThresholdFiresNoLaterThanHigher() {
		// Monotonicity: at 4 geometric blocks the banked fraction is 1-0.75^4 = 0.6836, so a 0.5 bar fires but a
		// 0.9 bar does not — the lower threshold is (weakly) easier at every list length.
		List<Long> list = geometricBlocks(4, 4);
		assertTrue(DiminishingReturns.reached(list, 0.5, 4));
		assertFalse(DiminishingReturns.reached(list, 0.9, 4));
		// And whenever the strict bar fires, the loose one must too.
		List<Long> longer = geometricBlocks(11, 4);
		assertTrue(DiminishingReturns.reached(longer, 0.95, 4));
		assertTrue(DiminishingReturns.reached(longer, 0.5, 4));
	}
}
