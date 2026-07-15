package com.bobrust.generator.tiler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Per-color greedy square covering with the painter's-algorithm relaxation
 * (PLAN-PIXEL-MODE.md §3, carried into PLAN-PALETTIZED-MODE.md §5).
 *
 * <p>Colors are painted in cell-count-descending rank order. A stamp of rank
 * k may overhang cells of colors painted <em>later</em> (they get overwritten)
 * but never earlier ones, so the last stamp touching any cell is its own
 * color: rendering the plan reproduces the quantized label grid exactly —
 * the mode's exactness invariant, enforced by the Step 3 test suite.
 *
 * <p>Deterministic: rank ties break on the lower palette index, the greedy
 * scan is row-major, stamps group by side descending within each color.
 */
public final class SquareTiler {
	/** The in-game size slider tops out at 32 → max square side in cells. */
	public static final int MAX_SIDE = 32;

	private SquareTiler() {
	}

	/**
	 * Tiles the label grid into a paint plan.
	 *
	 * @param labels     row-major palette indices, {@code gridW × gridH}
	 * @param paletteRgb the palette the labels index into
	 * @param paintable  optional mask; {@code false} cells are neither covered
	 *                   nor overhung (the "skip sign-colored cells" option).
	 *                   Null means every cell is painted.
	 */
	public static PalettizedPaintPlan tile(int[] labels, int gridW, int gridH, int[] paletteRgb, boolean[] paintable) {
		return tile(labels, gridW, gridH, paletteRgb, paintable, MAX_SIDE);
	}

	/**
	 * Tiles with an explicit side cap — the planner passes
	 * {@link SquareBrushGeometry#maxSideCells} so every stamp's SIZE stays
	 * within the game's slider range.
	 */
	public static PalettizedPaintPlan tile(int[] labels, int gridW, int gridH, int[] paletteRgb, boolean[] paintable,
			int maxSide) {
		if (labels.length != gridW * gridH) {
			throw new IllegalArgumentException("labels length " + labels.length + " != " + gridW + "x" + gridH);
		}
		if (paintable != null && paintable.length != labels.length) {
			throw new IllegalArgumentException("paintable mask length mismatch");
		}
		if (maxSide < 1 || maxSide > MAX_SIDE) {
			throw new IllegalArgumentException("maxSide out of range: " + maxSide);
		}

		int[] rankOf = rankColors(labels, paletteRgb.length, paintable);

		// rank of every cell (paintable cells only; skipped cells get -1 so no
		// allowed-region test can ever include them)
		int[] cellRank = new int[labels.length];
		for (int i = 0; i < labels.length; i++) {
			cellRank[i] = (paintable == null || paintable[i]) ? rankOf[labels[i]] : -1;
		}

		// colors in rank order
		int[] colorAtRank = new int[paletteRgb.length];
		Arrays.fill(colorAtRank, -1);
		for (int c = 0; c < paletteRgb.length; c++) {
			if (rankOf[c] >= 0) {
				colorAtRank[rankOf[c]] = c;
			}
		}

		List<PixelStamp> stamps = new ArrayList<>();
		boolean[] covered = new boolean[labels.length];
		int[] dp = new int[(gridW + 1) * (gridH + 1)];

		for (int rank = 0; rank < paletteRgb.length; rank++) {
			int color = colorAtRank[rank];
			if (color < 0) {
				continue;
			}

			// The covering is per color: an earlier color's overhanging stamp
			// covering this cell is exactly what this color must repaint.
			Arrays.fill(covered, false);

			// Largest-square DP over the allowed region A_k = { rank >= k }
			// dp indexed on a (gridH+1) x (gridW+1) grid, scanned bottom-up.
			Arrays.fill(dp, 0);
			for (int y = gridH - 1; y >= 0; y--) {
				for (int x = gridW - 1; x >= 0; x--) {
					if (cellRank[y * gridW + x] >= rank) {
						int below = dp[(y + 1) * (gridW + 1) + x];
						int right = dp[y * (gridW + 1) + x + 1];
						int diag = dp[(y + 1) * (gridW + 1) + x + 1];
						dp[y * (gridW + 1) + x] = 1 + Math.min(below, Math.min(right, diag));
					}
				}
			}

			// Greedy row-major cover of M_k (this color's own cells)
			int stampStart = stamps.size();
			for (int y = 0; y < gridH; y++) {
				for (int x = 0; x < gridW; x++) {
					int i = y * gridW + x;
					if (covered[i] || labels[i] != color || cellRank[i] < 0) {
						continue;
					}
					int side = Math.min(dp[y * (gridW + 1) + x], maxSide);
					for (int sy = y; sy < y + side; sy++) {
						Arrays.fill(covered, sy * gridW + x, sy * gridW + x + side, true);
					}
					stamps.add(new PixelStamp(x, y, side, color));
				}
			}

			// Group by side descending within the color (one SIZE entry per
			// distinct side); the row-major scan order is kept within a group.
			stamps.subList(stampStart, stamps.size())
				.sort((a, b) -> Integer.compare(b.side(), a.side()));
		}

		return PalettizedPaintPlan.fromStamps(stamps, paletteRgb, gridW, gridH);
	}

	/**
	 * Ranks colors by paintable-cell count descending (ties: lower palette
	 * index first). Colors with zero cells get rank -1.
	 */
	private static int[] rankColors(int[] labels, int paletteSize, boolean[] paintable) {
		long[] keyed = new long[paletteSize];
		for (int c = 0; c < paletteSize; c++) {
			keyed[c] = c; // low bits: color index
		}
		for (int i = 0; i < labels.length; i++) {
			if (paintable == null || paintable[i]) {
				keyed[labels[i]] += 1L << 32;
			}
		}
		// Sort by count desc, index asc — encode as (-count, index) via key math
		Long[] order = new Long[paletteSize];
		for (int c = 0; c < paletteSize; c++) {
			order[c] = keyed[c];
		}
		Arrays.sort(order, (a, b) -> {
			int byCount = Long.compare(b >>> 32, a >>> 32);
			return byCount != 0 ? byCount : Long.compare(a & 0xffffffffL, b & 0xffffffffL);
		});

		int[] rankOf = new int[paletteSize];
		Arrays.fill(rankOf, -1);
		int rank = 0;
		for (Long key : order) {
			if ((key >>> 32) == 0) {
				break; // zero-cell colors are unranked
			}
			rankOf[(int) (key & 0xffffffffL)] = rank++;
		}
		return rankOf;
	}
}
