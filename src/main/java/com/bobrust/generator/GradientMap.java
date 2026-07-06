package com.bobrust.generator;

import java.util.Random;

/**
 * Precomputed Sobel gradient magnitude map of the target image, downsampled
 * to a coarse grid. Provides normalized [0,1] gradient values that indicate
 * edge density at any pixel position.
 *
 * High gradient = edges/detail, low gradient = smooth regions.
 *
 * Used by adaptive size selection (Proposal 3) to bias circle sizes:
 * small circles near edges, large circles in smooth areas.
 */
public class GradientMap {
	private static final int DEFAULT_GRID_DIM = 32;
	/** Raw (pre-normalization) Sobel magnitude that counts as a "hard edge". A full-contrast 0->255 step is
	 *  ~1020 (4*255); ~500 is about half a full-contrast step. Calibrated by AutoAlphaContentStatsTest. */
	static final float HARD_EDGE_MAGNITUDE = 500f;

	final int gridWidth;
	final int gridHeight;
	final int cellWidth;
	final int cellHeight;
	final int imageWidth;
	final int imageHeight;

	/** Click-aware size tilt (S3/P1b). 0 = off: the weight table is the exact original expression in the original
	 *  accumulation order, so {@link #selectSizeIndex} stays bit-identical. beta&gt;0 multiplies each size's weight by
	 *  {@code (SIZES[i]^2)^beta} — an area-per-click tilt on top of the gradient prior (beta=1 ~ selection probability
	 *  proportional to covered area x gradient prior). Only meaningful when the map actually drives size selection
	 *  ({@code adaptiveSize=true}); harmless on the stats-only path. */
	private final double sizeClickBias;

	/** Normalized gradient values per grid cell, range [0,1]. */
	final float[] cellGradients;

	/** Fraction of interior pixels whose RAW Sobel magnitude exceeds {@link #HARD_EDGE_MAGNITUDE}. Content
	 *  classifier signal — survives the per-image normalization that {@link #cellGradients} discards. Set by
	 *  {@link #compute}. */
	private float hardEdgeFraction;

	/**
	 * Lazily built per-cell cumulative size weights for
	 * {@link #selectSizeIndex}: entry {@code cell * numSizes + i} holds
	 * {@code weights[0] + ... + weights[i]} accumulated in the same float order
	 * as the previous per-call computation, so selection is bit-identical while
	 * skipping the per-call {@code float[]} allocation and 6x {@code Math.exp}.
	 * Invalidated by {@link #compute}. Only touched from the generator thread.
	 */
	private float[] cumulativeWeights;

	public GradientMap(int imageWidth, int imageHeight) {
		this(imageWidth, imageHeight, DEFAULT_GRID_DIM, DEFAULT_GRID_DIM, 0.0);
	}

	/** S3: build the map with a click-aware size tilt (see {@link #sizeClickBias}). Bias 0 is the legacy behavior. */
	public GradientMap(int imageWidth, int imageHeight, double sizeClickBias) {
		this(imageWidth, imageHeight, DEFAULT_GRID_DIM, DEFAULT_GRID_DIM, sizeClickBias);
	}

	public GradientMap(int imageWidth, int imageHeight, int gridWidth, int gridHeight) {
		this(imageWidth, imageHeight, gridWidth, gridHeight, 0.0);
	}

	public GradientMap(int imageWidth, int imageHeight, int gridWidth, int gridHeight, double sizeClickBias) {
		this.imageWidth = imageWidth;
		this.imageHeight = imageHeight;
		this.gridWidth = gridWidth;
		this.gridHeight = gridHeight;
		this.cellWidth = Math.max(1, (imageWidth + gridWidth - 1) / gridWidth);
		this.cellHeight = Math.max(1, (imageHeight + gridHeight - 1) / gridHeight);
		this.cellGradients = new float[gridWidth * gridHeight];
		this.sizeClickBias = sizeClickBias;
	}

	/**
	 * Compute the gradient map from the target image using Sobel operators.
	 * This is a one-time cost at initialization.
	 */
	public void compute(BorstImage target) {
		int w = target.width;
		int h = target.height;

		// Convert to grayscale luminance
		float[] gray = new float[w * h];
		for (int i = 0; i < w * h; i++) {
			int px = target.pixels[i];
			int r = (px >>> 16) & 0xff;
			int g = (px >>> 8) & 0xff;
			int b = px & 0xff;
			gray[i] = 0.299f * r + 0.587f * g + 0.114f * b;
		}

		// Compute Sobel gradient magnitude per pixel, accumulate into grid cells
		float[] cellSums = new float[gridWidth * gridHeight];
		int[] cellCounts = new int[gridWidth * gridHeight];
		int hardEdgeCount = 0, interiorPixels = 0;

		for (int y = 1; y < h - 1; y++) {
			int gy = Math.min(y / cellHeight, gridHeight - 1);
			for (int x = 1; x < w - 1; x++) {
				int gx = Math.min(x / cellWidth, gridWidth - 1);

				// Sobel X kernel: [-1 0 1; -2 0 2; -1 0 1]
				float sx = -gray[(y - 1) * w + (x - 1)] + gray[(y - 1) * w + (x + 1)]
						 - 2 * gray[y * w + (x - 1)]    + 2 * gray[y * w + (x + 1)]
						 - gray[(y + 1) * w + (x - 1)]  + gray[(y + 1) * w + (x + 1)];

				// Sobel Y kernel: [-1 -2 -1; 0 0 0; 1 2 1]
				float sy = -gray[(y - 1) * w + (x - 1)] - 2 * gray[(y - 1) * w + x] - gray[(y - 1) * w + (x + 1)]
						 + gray[(y + 1) * w + (x - 1)]  + 2 * gray[(y + 1) * w + x] + gray[(y + 1) * w + (x + 1)];

				float magnitude = (float) Math.sqrt(sx * sx + sy * sy);
				interiorPixels++;
				if (magnitude > HARD_EDGE_MAGNITUDE) hardEdgeCount++;

				int cellIdx = gy * gridWidth + gx;
				cellSums[cellIdx] += magnitude;
				cellCounts[cellIdx]++;
			}
		}

		hardEdgeFraction = interiorPixels > 0 ? hardEdgeCount / (float) interiorPixels : 0f;

		// Compute average gradient per cell
		float maxGradient = 0;
		for (int i = 0; i < cellGradients.length; i++) {
			if (cellCounts[i] > 0) {
				cellGradients[i] = cellSums[i] / cellCounts[i];
			} else {
				cellGradients[i] = 0;
			}
			maxGradient = Math.max(maxGradient, cellGradients[i]);
		}

		// Normalize to [0, 1]
		if (maxGradient > 0) {
			for (int i = 0; i < cellGradients.length; i++) {
				cellGradients[i] /= maxGradient;
			}
		}

		// Cell gradients changed — rebuild the size-weight table on next use
		cumulativeWeights = null;
	}

	/** Fraction of interior pixels that are hard edges (raw Sobel magnitude &gt; {@link #HARD_EDGE_MAGNITUDE}),
	 *  after {@link #compute}. Used to classify photographic vs hard-edge/text content for the auto alpha floor. */
	public float getHardEdgeFraction() {
		return hardEdgeFraction;
	}

	/**
	 * Get the normalized gradient value [0,1] at the given pixel position.
	 * 0 = smooth area, 1 = strongest edge.
	 */
	public float getGradient(int x, int y) {
		int gx = Math.min(x / cellWidth, gridWidth - 1);
		int gy = Math.min(y / cellHeight, gridHeight - 1);
		if (gx < 0) gx = 0;
		if (gy < 0) gy = 0;
		return cellGradients[gy * gridWidth + gx];
	}

	/**
	 * Select a size index from SIZES weighted by local gradient.
	 * High gradient favors small sizes (low indices), low gradient favors large sizes.
	 *
	 * @param rnd random source
	 * @param x pixel x position
	 * @param y pixel y position
	 * @return index into BorstUtils.SIZES
	 */
	public int selectSizeIndex(Random rnd, int x, int y) {
		int gx = Math.min(x / cellWidth, gridWidth - 1);
		int gy = Math.min(y / cellHeight, gridHeight - 1);
		if (gx < 0) gx = 0;
		if (gy < 0) gy = 0;

		int numSizes = BorstUtils.SIZES.length;
		float[] table = cumulativeWeights;
		if (table == null) {
			table = buildCumulativeWeights(numSizes);
			cumulativeWeights = table;
		}

		// Weighted random selection over the precomputed cumulative weights
		int base = (gy * gridWidth + gx) * numSizes;
		float r = rnd.nextFloat() * table[base + numSizes - 1];
		for (int i = 0; i < numSizes; i++) {
			if (r <= table[base + i]) {
				return i;
			}
		}
		return numSizes - 1; // fallback
	}

	/**
	 * Precompute the cumulative size weights for every cell. At {@code sizeClickBias == 0} this is the exact
	 * expressions and float accumulation order of the old per-call loop in {@link #selectSizeIndex}, so the
	 * selection stays bit-identical: high gradient favors small sizes, low gradient favors large ones.
	 *
	 * <p>With {@code sizeClickBias > 0} (S3/P1b) each size's weight is multiplied by {@code (SIZES[i]^2)^beta} before
	 * accumulation — an area-per-click tilt toward larger blobs. The alternative calibration direction, if the beta
	 * sweep loses (see docs/SPEED-QUALITY-PROPOSALS.md §7), is to soften the {@code -4.0} exponent toward {@code -2.0}
	 * in high-gradient cells rather than tilt by area.
	 */
	private float[] buildCumulativeWeights(int numSizes) {
		float[] table = new float[cellGradients.length * numSizes];
		for (int cell = 0; cell < cellGradients.length; cell++) {
			float gradient = cellGradients[cell];
			float cumulative = 0;
			for (int i = 0; i < numSizes; i++) {
				float sizeNorm = (float) i / (numSizes - 1); // 0=smallest, 1=largest
				double weight = Math.exp(-4.0 * Math.abs(sizeNorm - (1.0 - gradient)));
				if (sizeClickBias != 0) {
					int d = BorstUtils.SIZES[i];
					weight *= Math.pow((double) d * d, sizeClickBias);
				}
				cumulative += (float) weight;
				table[cell * numSizes + i] = cumulative;
			}
		}
		return table;
	}

	/**
	 * Get the position perturbation scale based on local gradient.
	 * Near edges (high gradient): small perturbations for fine-tuning.
	 * In smooth areas (low gradient): large perturbations for broad exploration.
	 *
	 * @return scale factor for Gaussian perturbation (range roughly [0.25, 1.0])
	 */
	public float getMutationScale(int x, int y) {
		float gradient = getGradient(x, y);
		// High gradient -> small scale (0.25), low gradient -> large scale (1.0)
		return 1.0f - 0.75f * gradient;
	}
}
