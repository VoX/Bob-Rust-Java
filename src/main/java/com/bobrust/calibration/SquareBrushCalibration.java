package com.bobrust.calibration;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import com.bobrust.generator.tiler.SquareBrushGeometry;

/**
 * Square-brush footprint calibration (PLAN-PALETTIZED-MODE.md §3.2): the
 * owner paints one row of square stamps at the {@link #SIZE_STEPS} SIZE
 * values (typed into the field) on a fresh sign, screenshots it, and this
 * analyzer measures each stamp's painted side and corner sharpness, then
 * fits {@code side(s) = a·s + b}. The result is serialized into
 * {@code Settings.SettingsSquareBrush}; until it is run the circle-derived
 * defaults apply.
 *
 * <p>Analysis is scale-aware: pass the screenshot's texels-per-pixel factor
 * (sign texel width ÷ painted-area pixel width). Corner sharpness is the
 * painted fraction of the stamp's bounding box — a true square fills ~100%,
 * a circle only π/4 ≈ 79% (edge case 20: rounded small sizes mean the pitch
 * must grow).
 *
 * <p>Usage: {@code java -cp ... com.bobrust.calibration.SquareBrushCalibration
 * screenshot.png <texelsPerPixel>}
 */
public class SquareBrushCalibration {
	/** SIZE values of the calibration row, typed via the §3.1 field entry. */
	public static final double[] SIZE_STEPS = { 1.00, 1.50, 2.00, 3.00, 4.00, 8.00, 16.00, 32.00 };

	/** Pixel brightness threshold to consider a pixel painted (dark background). */
	private static final int PAINT_THRESHOLD = 10;
	/** A stamp counts as square when it fills at least this bbox fraction. */
	public static final double MIN_SQUARE_FILL = 0.95;

	/** One measured stamp: its bounding box side (px) and bbox fill fraction. */
	public record Measured(double sidePx, double fillFraction, int centerX, int centerY) {
	}

	/** The analysis: per-step measurements + the fitted geometry (texels). */
	public record Result(List<Measured> stamps, SquareBrushGeometry fitted, boolean squareShaped) {
	}

	private SquareBrushCalibration() {
	}

	/**
	 * Renders the expected pattern (white squares on black, left to right in
	 * {@link #SIZE_STEPS} order) — the round-trip test's ground truth and a
	 * visual reference for the owner.
	 */
	public static BufferedImage renderExpectedPattern(SquareBrushGeometry geometry, double pixelsPerTexel) {
		int spacing = (int) Math.ceil((geometry.a() * 32 + geometry.b()) * pixelsPerTexel) + 24;
		int width = spacing * SIZE_STEPS.length + 16;
		int height = spacing + 16;

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int i = 0; i < SIZE_STEPS.length; i++) {
			int side = (int) Math.round((geometry.a() * SIZE_STEPS[i] + geometry.b()) * pixelsPerTexel);
			int cx = 8 + spacing * i + spacing / 2;
			int cy = height / 2;
			for (int y = cy - side / 2; y < cy - side / 2 + side; y++) {
				for (int x = cx - side / 2; x < cx - side / 2 + side; x++) {
					if (x >= 0 && y >= 0 && x < width && y < height) {
						image.setRGB(x, y, 0xffffffff);
					}
				}
			}
		}
		return image;
	}

	/**
	 * Measures the painted stamps (bright connected clusters, left to right)
	 * and fits the footprint model in texel units.
	 *
	 * @param texelsPerPixel sign texels per screenshot pixel
	 */
	public static Result analyze(BufferedImage screenshot, double texelsPerPixel) {
		List<Measured> stamps = detectStamps(screenshot);

		boolean square = true;
		for (Measured stamp : stamps) {
			if (stamp.fillFraction() < MIN_SQUARE_FILL) {
				square = false;
			}
		}

		SquareBrushGeometry fitted = null;
		if (stamps.size() == SIZE_STEPS.length) {
			double[] sides = new double[stamps.size()];
			for (int i = 0; i < stamps.size(); i++) {
				sides[i] = stamps.get(i).sidePx() * texelsPerPixel;
			}
			fitted = fit(SIZE_STEPS, sides);
		}
		return new Result(stamps, fitted, square);
	}

	/**
	 * Least-squares fit of {@code side = a·s + b} over the measured row.
	 * {@code minSize} keeps the parsed default until the field's true minimum
	 * is measured separately (open question 2).
	 */
	public static SquareBrushGeometry fit(double[] sizes, double[] sidesTexels) {
		int n = sizes.length;
		double sumS = 0, sumD = 0, sumSS = 0, sumSD = 0;
		for (int i = 0; i < n; i++) {
			sumS += sizes[i];
			sumD += sidesTexels[i];
			sumSS += sizes[i] * sizes[i];
			sumSD += sizes[i] * sidesTexels[i];
		}
		double det = n * sumSS - sumS * sumS;
		if (Math.abs(det) < 1e-9) {
			return SquareBrushGeometry.DEFAULT;
		}
		double a = (n * sumSD - sumS * sumD) / det;
		double b = (sumD - a * sumS) / n;
		if (a <= 0) {
			return SquareBrushGeometry.DEFAULT;
		}
		return new SquareBrushGeometry(a, b, SquareBrushGeometry.DEFAULT.minSize());
	}

	/** Bright connected clusters (4-connected flood fill), ordered left to right. */
	private static List<Measured> detectStamps(BufferedImage image) {
		int w = image.getWidth();
		int h = image.getHeight();
		boolean[] visited = new boolean[w * h];
		List<Measured> result = new ArrayList<>();

		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int index = y * w + x;
				if (visited[index] || !painted(image.getRGB(x, y))) {
					continue;
				}

				// Flood fill this cluster, tracking bbox + painted count
				int minX = x, maxX = x, minY = y, maxY = y;
				long count = 0;
				java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
				queue.add(index);
				visited[index] = true;
				while (!queue.isEmpty()) {
					int i = queue.poll();
					int px = i % w, py = i / w;
					count++;
					minX = Math.min(minX, px);
					maxX = Math.max(maxX, px);
					minY = Math.min(minY, py);
					maxY = Math.max(maxY, py);
					int[][] neighbors = { { px - 1, py }, { px + 1, py }, { px, py - 1 }, { px, py + 1 } };
					for (int[] neighbor : neighbors) {
						int nx = neighbor[0], ny = neighbor[1];
						if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
							continue;
						}
						int ni = ny * w + nx;
						if (!visited[ni] && painted(image.getRGB(nx, ny))) {
							visited[ni] = true;
							queue.add(ni);
						}
					}
				}

				int bw = maxX - minX + 1;
				int bh = maxY - minY + 1;
				if (bw < 2 || bh < 2) {
					continue; // speck / label noise
				}
				double side = (bw + bh) / 2.0;
				double fill = count / (double) ((long) bw * bh);
				result.add(new Measured(side, fill, (minX + maxX) / 2, (minY + maxY) / 2));
			}
		}

		result.sort((a, b) -> Integer.compare(a.centerX(), b.centerX()));
		return result;
	}

	private static boolean painted(int rgb) {
		int brightness = (((rgb >>> 16) & 0xff) + ((rgb >>> 8) & 0xff) + (rgb & 0xff)) / 3;
		return brightness > PAINT_THRESHOLD;
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.out.println("Usage: SquareBrushCalibration <screenshot.png> <texelsPerPixel>");
			System.out.println("  texelsPerPixel = sign texel width / painted-area pixel width");
			return;
		}
		BufferedImage screenshot = ImageIO.read(new File(args[0]));
		double texelsPerPixel = Double.parseDouble(args[1]);

		Result result = analyze(screenshot, texelsPerPixel);
		System.out.println("Detected " + result.stamps().size() + " stamps (expected " + SIZE_STEPS.length + ")");
		for (int i = 0; i < result.stamps().size(); i++) {
			Measured stamp = result.stamps().get(i);
			String size = i < SIZE_STEPS.length ? "SIZE %.2f".formatted(SIZE_STEPS[i]) : "extra";
			System.out.printf("  %s: side %.1f px = %.2f texels, bbox fill %.1f%%%n",
				size, stamp.sidePx(), stamp.sidePx() * texelsPerPixel, stamp.fillFraction() * 100);
		}
		if (!result.squareShaped()) {
			System.out.println("WARNING: at least one stamp fills < 95% of its bounding box — the brush is "
				+ "not painting sharp squares at these sizes; consider a larger pitch (edge case 20).");
		}
		if (result.fitted() != null) {
			System.out.println("Fitted: " + result.fitted().serialize());
			System.out.println("Paste into the config as SettingsSquareBrush to replace the defaults.");
		} else {
			System.out.println("Could not fit — wrong stamp count. Crop the screenshot to the pattern row.");
		}
	}
}
