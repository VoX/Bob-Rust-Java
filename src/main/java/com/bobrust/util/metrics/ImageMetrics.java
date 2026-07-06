package com.bobrust.util.metrics;

import java.awt.image.BufferedImage;

import com.bobrust.generator.BorstImage;

/**
 * Convenience entry point for the perceptual quality metrics: compares a
 * target image against a rendered result and reports RMSE, SSIM and the mean
 * CIEDE2000 (ΔE00) in one pass.
 *
 * <p>{@code rmse} uses exactly the generator's score definition —
 * {@code sqrt(sum(dR² + dG² + dB² + dA²) / (w * h * 4)) / 255} — so it is
 * directly comparable to {@code Model.getScore()}.
 */
public final class ImageMetrics {
	/**
	 * Result of comparing a target against a rendered image.
	 *
	 * @param rmse     normalized RMSE in [0, 1]; same definition as the generator score
	 * @param ssim     mean structural similarity in (-1, 1]; 1 means identical
	 * @param deltaE00 mean per-pixel CIEDE2000; 0 means identical, ~1 is a just-noticeable difference
	 */
	public record Result(double rmse, double ssim, double deltaE00) {
	}

	private ImageMetrics() {
	}

	public static Result compare(BorstImage target, BorstImage rendered) {
		checkSize(target.width, target.height, rendered.width, rendered.height);
		return compare(target.pixels, rendered.pixels, target.width, target.height);
	}

	public static Result compare(BufferedImage target, BufferedImage rendered) {
		checkSize(target.getWidth(), target.getHeight(), rendered.getWidth(), rendered.getHeight());
		return compare(argbPixels(target), argbPixels(rendered), target.getWidth(), target.getHeight());
	}

	public static Result compare(int[] target, int[] rendered, int width, int height) {
		if (target.length != rendered.length || target.length != width * height) {
			throw new IllegalArgumentException("Pixel arrays must both be width * height long");
		}

		long total = 0;
		for (int i = 0; i < target.length; i++) {
			int tt = target[i];
			int rr = rendered[i];
			int da = ((tt >>> 24) & 0xff) - ((rr >>> 24) & 0xff);
			int dr = ((tt >>> 16) & 0xff) - ((rr >>> 16) & 0xff);
			int dg = ((tt >>>  8) & 0xff) - ((rr >>>  8) & 0xff);
			int db = ((tt       ) & 0xff) - ((rr       ) & 0xff);
			total += dr * dr + dg * dg + db * db + da * da;
		}
		double rmse = Math.sqrt(total / (width * height * 4.0)) / 255.0;

		return new Result(
			rmse,
			Ssim.compare(target, rendered, width, height),
			Ciede2000.meanDeltaE(target, rendered)
		);
	}

	/** Pixels of an image as ARGB ints, regardless of the image's internal type. */
	public static int[] argbPixels(BufferedImage image) {
		return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
	}

	private static void checkSize(int wa, int ha, int wb, int hb) {
		if (wa != wb || ha != hb) {
			throw new IllegalArgumentException(
				"Image dimensions differ: " + wa + "x" + ha + " vs " + wb + "x" + hb);
		}
	}
}
