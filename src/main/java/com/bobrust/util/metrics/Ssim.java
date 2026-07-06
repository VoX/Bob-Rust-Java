package com.bobrust.util.metrics;

/**
 * Structural similarity index (SSIM, Wang et al. 2004) on the Rec.601 luma
 * channel: 11×11 Gaussian window (σ = 1.5), K1 = 0.01, K2 = 0.03, L = 255.
 * The mean SSIM over all fully interior windows is returned; images smaller
 * than the window fall back to a single uniform window over the whole image.
 *
 * <p>Identical images score exactly 1.0; the score decreases toward 0 (and
 * can go slightly negative) as structure diverges.
 */
public final class Ssim {
	private static final int RADIUS = 5; // 11x11 window
	private static final double SIGMA = 1.5;
	private static final double C1 = (0.01 * 255) * (0.01 * 255);
	private static final double C2 = (0.03 * 255) * (0.03 * 255);
	private static final double[] WEIGHTS = gaussianWeights();

	private Ssim() {
	}

	/**
	 * Mean SSIM between two equally sized ARGB pixel arrays (alpha ignored;
	 * compared on luma).
	 */
	public static double compare(int[] pixelsA, int[] pixelsB, int width, int height) {
		if (pixelsA.length != pixelsB.length || pixelsA.length != width * height) {
			throw new IllegalArgumentException("Pixel arrays must both be width * height long");
		}

		double[] lumaA = luma(pixelsA);
		double[] lumaB = luma(pixelsB);

		int window = 2 * RADIUS + 1;
		if (width < window || height < window) {
			return globalSsim(lumaA, lumaB);
		}

		double sum = 0;
		int count = 0;
		for (int cy = RADIUS; cy < height - RADIUS; cy++) {
			for (int cx = RADIUS; cx < width - RADIUS; cx++) {
				sum += windowSsim(lumaA, lumaB, width, cx, cy);
				count++;
			}
		}
		return sum / count;
	}

	private static double windowSsim(double[] la, double[] lb, int width, int cx, int cy) {
		double sumA = 0, sumB = 0, sumAA = 0, sumBB = 0, sumAB = 0;

		int wi = 0;
		for (int dy = -RADIUS; dy <= RADIUS; dy++) {
			int rowIdx = (cy + dy) * width + cx;
			for (int dx = -RADIUS; dx <= RADIUS; dx++, wi++) {
				double w = WEIGHTS[wi];
				double a = la[rowIdx + dx];
				double b = lb[rowIdx + dx];
				sumA += w * a;
				sumB += w * b;
				sumAA += w * a * a;
				sumBB += w * b * b;
				sumAB += w * a * b;
			}
		}

		return ssimFromMoments(sumA, sumB, sumAA, sumBB, sumAB);
	}

	/** Single uniform window over the whole image (for images smaller than 11×11). */
	private static double globalSsim(double[] la, double[] lb) {
		final double w = 1.0 / la.length;
		double sumA = 0, sumB = 0, sumAA = 0, sumBB = 0, sumAB = 0;
		for (int i = 0; i < la.length; i++) {
			double a = la[i];
			double b = lb[i];
			sumA += w * a;
			sumB += w * b;
			sumAA += w * a * a;
			sumBB += w * b * b;
			sumAB += w * a * b;
		}
		return ssimFromMoments(sumA, sumB, sumAA, sumBB, sumAB);
	}

	private static double ssimFromMoments(double muA, double muB, double eAA, double eBB, double eAB) {
		double varA = eAA - muA * muA;
		double varB = eBB - muB * muB;
		double covAB = eAB - muA * muB;

		double numerator = (2 * muA * muB + C1) * (2 * covAB + C2);
		double denominator = (muA * muA + muB * muB + C1) * (varA + varB + C2);
		return numerator / denominator;
	}

	private static double[] luma(int[] pixels) {
		double[] result = new double[pixels.length];
		for (int i = 0; i < pixels.length; i++) {
			int p = pixels[i];
			result[i] = 0.299 * ((p >>> 16) & 0xff)
			          + 0.587 * ((p >>>  8) & 0xff)
			          + 0.114 * ((p       ) & 0xff);
		}
		return result;
	}

	private static double[] gaussianWeights() {
		int window = 2 * RADIUS + 1;
		double[] weights = new double[window * window];
		double sum = 0;
		int i = 0;
		for (int dy = -RADIUS; dy <= RADIUS; dy++) {
			for (int dx = -RADIUS; dx <= RADIUS; dx++, i++) {
				double w = Math.exp(-(dx * dx + dy * dy) / (2 * SIGMA * SIGMA));
				weights[i] = w;
				sum += w;
			}
		}
		for (i = 0; i < weights.length; i++) {
			weights[i] /= sum;
		}
		return weights;
	}
}
