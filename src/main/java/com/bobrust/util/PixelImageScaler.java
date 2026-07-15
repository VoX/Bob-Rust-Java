package com.bobrust.util;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Area-average (box-filter) scaler for the palettized cell grid
 * (PLAN-PIXEL-MODE.md §2). Each destination cell is the exact mean of the
 * source area it covers — the correct kernel when every cell will be painted
 * as one uniform block. Interpolating filters sub-sample and alias at the
 * ~7× reductions this mode runs at, and their halo colors eat palette
 * entries.
 *
 * <p>The rect-mapping arithmetic and background compositing semantics are
 * kept identical to {@link ImageUtil#getScaledInstance}: the image placement
 * inside the canvas maps to the same destination rectangle, uncovered cells
 * are the background color, and semi-transparent source pixels composite over
 * the background (SrcOver) before averaging.
 */
public final class PixelImageScaler {
	private PixelImageScaler() {
	}

	/**
	 * Scales {@code source} into a {@code gridW × gridH} cell image, placed
	 * per the {@code imageRect}-in-{@code canvasRect} selection, over
	 * {@code bgColor}.
	 */
	public static BufferedImage getAreaAveragedInstance(Image source, Rectangle canvasRect, Rectangle imageRect,
			int gridW, int gridH, Color bgColor) {
		// Same destination-rect arithmetic as ImageUtil.getScaledInstance
		int dstX1 = ((imageRect.x - canvasRect.x) * gridW) / canvasRect.width;
		int dstY1 = ((imageRect.y - canvasRect.y) * gridH) / canvasRect.height;
		int dstX2 = dstX1 + (imageRect.width * gridW) / canvasRect.width;
		int dstY2 = dstY1 + (imageRect.height * gridH) / canvasRect.height;

		BufferedImage src = toBufferedImage(source);
		int srcW = src.getWidth();
		int srcH = src.getHeight();
		int[] srcPixels = src.getRGB(0, 0, srcW, srcH, null, 0, srcW);

		BufferedImage result = new BufferedImage(gridW, gridH, BufferedImage.TYPE_INT_ARGB);
		int bg = bgColor.getRGB() | 0xff000000;
		int[] out = new int[gridW * gridH];
		java.util.Arrays.fill(out, bg);

		int dstW = dstX2 - dstX1;
		int dstH = dstY2 - dstY1;
		if (dstW > 0 && dstH > 0) {
			double bgR = (bg >>> 16) & 0xff;
			double bgG = (bg >>>  8) & 0xff;
			double bgB = (bg       ) & 0xff;

			int cellX1 = Math.max(0, dstX1);
			int cellY1 = Math.max(0, dstY1);
			int cellX2 = Math.min(gridW, dstX2);
			int cellY2 = Math.min(gridH, dstY2);

			for (int cy = cellY1; cy < cellY2; cy++) {
				double sy0 = (cy - dstY1) * (double) srcH / dstH;
				double sy1 = (cy + 1 - dstY1) * (double) srcH / dstH;
				for (int cx = cellX1; cx < cellX2; cx++) {
					double sx0 = (cx - dstX1) * (double) srcW / dstW;
					double sx1 = (cx + 1 - dstX1) * (double) srcW / dstW;

					// Premultiplied-alpha accumulation: the average of
					// SrcOver-composited pixels over a constant background.
					double sumA = 0, sumR = 0, sumG = 0, sumB = 0, area = 0;
					int iy0 = (int) Math.floor(sy0);
					int iy1 = Math.min(srcH, (int) Math.ceil(sy1));
					int ix0 = (int) Math.floor(sx0);
					int ix1 = Math.min(srcW, (int) Math.ceil(sx1));
					for (int sy = Math.max(0, iy0); sy < iy1; sy++) {
						double wy = Math.min(sy + 1, sy1) - Math.max(sy, sy0);
						if (wy <= 0) {
							continue;
						}
						for (int sx = Math.max(0, ix0); sx < ix1; sx++) {
							double wx = Math.min(sx + 1, sx1) - Math.max(sx, sx0);
							if (wx <= 0) {
								continue;
							}
							double weight = wx * wy;
							int pixel = srcPixels[sy * srcW + sx];
							double a = ((pixel >>> 24) & 0xff) / 255.0;
							sumA += a * weight;
							sumR += a * ((pixel >>> 16) & 0xff) * weight;
							sumG += a * ((pixel >>>  8) & 0xff) * weight;
							sumB += a * ((pixel       ) & 0xff) * weight;
							area += weight;
						}
					}
					if (area <= 0) {
						continue; // cell maps outside the source; keep background
					}
					double alpha = sumA / area;
					int r = to8Bit(sumR / area + (1.0 - alpha) * bgR);
					int g = to8Bit(sumG / area + (1.0 - alpha) * bgG);
					int b = to8Bit(sumB / area + (1.0 - alpha) * bgB);
					out[cy * gridW + cx] = 0xff000000 | (r << 16) | (g << 8) | b;
				}
			}
		}

		result.setRGB(0, 0, gridW, gridH, out, 0, gridW);
		return result;
	}

	private static int to8Bit(double v) {
		int i = (int) Math.round(v);
		return i < 0 ? 0 : Math.min(i, 255);
	}

	private static BufferedImage toBufferedImage(Image source) {
		if (source instanceof BufferedImage buffered && buffered.getType() == BufferedImage.TYPE_INT_ARGB) {
			return buffered;
		}
		BufferedImage image = new BufferedImage(source.getWidth(null), source.getHeight(null), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.drawImage(source, 0, 0, null);
		g.dispose();
		return image;
	}
}
