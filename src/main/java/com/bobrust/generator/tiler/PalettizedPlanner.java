package com.bobrust.generator.tiler;

import java.awt.Color;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import com.bobrust.generator.quant.PixelQuantizer;
import com.bobrust.robot.hsv.HsvPickerModel;
import com.bobrust.util.PixelImageScaler;
import com.bobrust.util.Sign;
import com.bobrust.util.metrics.Ciede2000;

/**
 * The palettized planning pipeline (PLAN-PALETTIZED-MODE.md §5): place via
 * canvas/image rects → area-average to the cell grid → quantize to N exact
 * colors → snap each centroid to the picker's predicted-reachable set (when
 * a fitted model exists — a pure function of it, so preview == plan ==
 * paint) → greedy square cover. Deterministic end to end.
 */
public final class PalettizedPlanner {
	/** "Skip sign-colored cells": ΔE00 at or below this counts as sign-colored. */
	public static final double SKIP_BASE_TOLERANCE = 1.0;

	/**
	 * The plan plus the exact preview: {@code cellImage} is the quantized
	 * (and reachable-snapped) cell grid — drawing it nearest-neighbor into
	 * the canvas rect IS the promised result.
	 */
	public record PlanResult(PalettizedPaintPlan plan, BufferedImage cellImage) {
	}

	private PalettizedPlanner() {
	}

	public static PlanResult plan(Image source, Rectangle canvasRect, Rectangle imageRect, Sign sign,
			Color background, int colors, double pitch, boolean dither, boolean skipBase,
			SquareBrushGeometry brush, HsvPickerModel snapModel) {
		int gridW = Math.max(1, (int) Math.ceil(sign.getWidth() / pitch));
		int gridH = Math.max(1, (int) Math.ceil(sign.getHeight() / pitch));

		BufferedImage cells = PixelImageScaler.getAreaAveragedInstance(
			source, canvasRect, imageRect, gridW, gridH, background);
		PixelQuantizer.Result quantized = PixelQuantizer.quantize(cells, colors, dither);

		// Snap the palette to what the picker can actually show. Labels stay:
		// the snap moves each centroid < ~1 JND (§2.4), never reassigns cells.
		int[] palette = quantized.paletteRgb().clone();
		if (snapModel != null) {
			for (int c = 0; c < palette.length; c++) {
				palette[c] = snapModel.snapToReachable(palette[c]);
			}
		}

		boolean[] paintable = null;
		if (skipBase) {
			int signAverage = sign.getAverageColor().getRGB();
			boolean[] skipColor = new boolean[palette.length];
			for (int c = 0; c < palette.length; c++) {
				skipColor[c] = Ciede2000.deltaE(palette[c], signAverage) <= SKIP_BASE_TOLERANCE;
			}
			paintable = new boolean[quantized.labels().length];
			for (int i = 0; i < paintable.length; i++) {
				paintable[i] = !skipColor[quantized.labels()[i]];
			}
		}

		PalettizedPaintPlan plan = SquareTiler.tile(
			quantized.labels(), gridW, gridH, palette, paintable, brush.maxSideCells(pitch));

		BufferedImage cellImage = new BufferedImage(gridW, gridH, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < gridH; y++) {
			for (int x = 0; x < gridW; x++) {
				cellImage.setRGB(x, y, palette[quantized.labels()[y * gridW + x]]);
			}
		}

		return new PlanResult(plan, cellImage);
	}
}
