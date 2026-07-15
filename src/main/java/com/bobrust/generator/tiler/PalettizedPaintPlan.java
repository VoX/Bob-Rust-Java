package com.bobrust.generator.tiler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The flattened palettized paint plan: an ordered op list of
 * {@code SetColor c / SetSize k / Stamp x,y} instructions plus a resume
 * cursor, mirroring {@link com.bobrust.generator.sorter.PaintPlan}'s
 * discipline (the instruction list is frozen once built; painting consumes
 * stamps from the cursor forward; opaque stamps are idempotent so replaying
 * a verified stamp is safe).
 */
public final class PalettizedPaintPlan {
	/** One plan instruction (SetColor, SetSize or Stamp — source-16, so unsealed). */
	public interface Op {
	}

	/** Enter palette color {@code colorIndex} through the HSV picker. */
	public record SetColor(int colorIndex) implements Op {
	}

	/** Type {@code sideCells · baseSize} into the SIZE field. */
	public record SetSize(int sideCells) implements Op {
	}

	/**
	 * Click the canvas: a {@code side × side} stamp anchored at
	 * ({@code cellX}, {@code cellY}). {@code changesCenter} is the planner's
	 * prediction of whether the stamp's center cell changes color — the
	 * executor only screen-verifies stamps where a before/after pixel compare
	 * can actually observe the click (PLAN-PIXEL-MODE.md edge case 11).
	 */
	public record Stamp(int cellX, int cellY, boolean changesCenter) implements Op {
	}

	private final List<Op> ops;
	private final int[] paletteRgb;
	private final int gridW;
	private final int gridH;
	private final int totalStamps;
	private final int sizeEntries;
	private final int colorEntries;
	private final int changingStamps;

	/** Resume cursor: stamps successfully painted (not op index). */
	private int paintedStamps;

	private PalettizedPaintPlan(List<Op> ops, int[] paletteRgb, int gridW, int gridH) {
		this.ops = Collections.unmodifiableList(ops);
		this.paletteRgb = paletteRgb;
		this.gridW = gridW;
		this.gridH = gridH;

		int stamps = 0, sizes = 0, colors = 0, changing = 0;
		for (Op op : ops) {
			if (op instanceof Stamp stamp) {
				stamps++;
				if (stamp.changesCenter()) {
					changing++;
				}
			} else if (op instanceof SetSize) {
				sizes++;
			} else if (op instanceof SetColor) {
				colors++;
			}
		}
		this.totalStamps = stamps;
		this.sizeEntries = sizes;
		this.colorEntries = colors;
		this.changingStamps = changing;
	}

	/**
	 * Builds the flattened op list from the tiler's stamp list (already in
	 * paint order: rank-ordered colors, side-descending groups within each).
	 * The {@code changesCenter} predictions come from replaying the stamps
	 * onto a virtual grid.
	 */
	static PalettizedPaintPlan fromStamps(List<PixelStamp> stamps, int[] paletteRgb, int gridW, int gridH) {
		List<Op> ops = new ArrayList<>(stamps.size() + 64);
		int[] canvas = new int[gridW * gridH];
		Arrays.fill(canvas, -1);

		int lastColor = -1;
		int lastSide = -1;
		for (PixelStamp stamp : stamps) {
			if (stamp.colorIndex() != lastColor) {
				ops.add(new SetColor(stamp.colorIndex()));
				lastColor = stamp.colorIndex();
				lastSide = -1;
			}
			if (stamp.side() != lastSide) {
				ops.add(new SetSize(stamp.side()));
				lastSide = stamp.side();
			}

			int centerX = stamp.cellX() + stamp.side() / 2;
			int centerY = stamp.cellY() + stamp.side() / 2;
			boolean changes = canvas[centerY * gridW + centerX] != stamp.colorIndex();
			ops.add(new Stamp(stamp.cellX(), stamp.cellY(), changes));

			for (int y = stamp.cellY(); y < stamp.cellY() + stamp.side(); y++) {
				Arrays.fill(canvas, y * gridW + stamp.cellX(), y * gridW + stamp.cellX() + stamp.side(), stamp.colorIndex());
			}
		}
		return new PalettizedPaintPlan(ops, paletteRgb, gridW, gridH);
	}

	public List<Op> getOps() {
		return ops;
	}

	public int[] getPaletteRgb() {
		return paletteRgb;
	}

	public int getGridWidth() {
		return gridW;
	}

	public int getGridHeight() {
		return gridH;
	}

	public int getTotalStamps() {
		return totalStamps;
	}

	/** Distinct SIZE-field entries the executor will type. */
	public int getSizeEntries() {
		return sizeEntries;
	}

	/** Distinct color entries through the HSV picker. */
	public int getColorEntries() {
		return colorEntries;
	}

	/** Stamps whose center cell the plan predicts changes color. */
	public int getChangingStamps() {
		return changingStamps;
	}

	/** The resume cursor: stamps already painted. */
	public synchronized int getPaintedStamps() {
		return paintedStamps;
	}

	/** Record {@code count} more stamps as successfully painted. */
	public synchronized void advancePainted(int count) {
		paintedStamps = Math.min(totalStamps, paintedStamps + Math.max(0, count));
	}

	/**
	 * The instructions to paint from stamp cursor {@code fromStamp} on. The
	 * returned list re-issues the governing {@code SetColor} and
	 * {@code SetSize} before the first stamp, so resuming mid-color re-enters
	 * the correct tool state (PLAN-PALETTIZED-MODE.md §6).
	 */
	public List<Op> opsFrom(int fromStamp) {
		if (fromStamp <= 0) {
			return ops;
		}

		List<Op> result = new ArrayList<>();
		SetColor governingColor = null;
		SetSize governingSize = null;
		int stampIndex = 0;
		int start = ops.size();
		for (int i = 0; i < ops.size(); i++) {
			Op op = ops.get(i);
			if (op instanceof SetColor color) {
				governingColor = color;
				governingSize = null;
			} else if (op instanceof SetSize size) {
				governingSize = size;
			} else if (op instanceof Stamp) {
				if (stampIndex == fromStamp) {
					start = i;
					break;
				}
				stampIndex++;
			}
		}
		if (start >= ops.size()) {
			return List.of();
		}
		if (governingColor != null) {
			result.add(governingColor);
		}
		if (governingSize != null) {
			result.add(governingSize);
		}
		result.addAll(ops.subList(start, ops.size()));
		return result;
	}

	/**
	 * Replays the plan onto a virtual grid initialized to {@code emptyLabel},
	 * returning the final per-cell palette indices — the exactness-invariant
	 * ground truth (rendering must reproduce the quantized labels on every
	 * painted cell).
	 */
	public int[] renderCells(int emptyLabel) {
		int[] canvas = new int[gridW * gridH];
		Arrays.fill(canvas, emptyLabel);

		int color = emptyLabel;
		int side = 0;
		for (Op op : ops) {
			if (op instanceof SetColor setColor) {
				color = setColor.colorIndex();
			} else if (op instanceof SetSize setSize) {
				side = setSize.sideCells();
			} else if (op instanceof Stamp stamp) {
				for (int y = stamp.cellY(); y < stamp.cellY() + side; y++) {
					Arrays.fill(canvas, y * gridW + stamp.cellX(), y * gridW + stamp.cellX() + side, color);
				}
			}
		}
		return canvas;
	}
}
