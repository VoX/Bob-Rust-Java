package com.bobrust.generator;

import java.util.Random;

public class Circle {
	private final Worker worker;

	// Position
	public int x;
	public int y;

	// Radius
	public int r;

	/**
	 * Index into {@link BorstUtils#ALPHAS} for this shape (Q2 per-shape alpha).
	 * Only consulted when {@link GeneratorConfig#usePerShapeAlpha()} is on —
	 * otherwise the worker's single global alpha applies (see
	 * {@link Worker#alphaFor(Circle)}) and this stays pinned to its index.
	 * COPY-EXACTNESS: this field must be threaded everywhere x/y/r are
	 * (constructor copy, {@link #fromValues}, State.getCopy) — dropping it
	 * breaks the hill-climb undo and determinism.
	 */
	public int alphaIndex;

	public Circle(Worker worker) {
		this.worker = worker;
		this.randomize();
	}

	public Circle(Worker worker, int x, int y, int r) {
		this(worker, x, y, r, worker.alphaIndex);
	}

	public Circle(Worker worker, int x, int y, int r, int alphaIndex) {
		this.worker = worker;
		this.x = x;
		this.y = y;
		this.r = r;
		this.alphaIndex = alphaIndex;
	}

	public void mutateShape() {
		int w = worker.w - 1;
		int h = worker.h - 1;
		Random rnd = worker.getRandom();
		// Only non-null when the model's config has adaptive sizing enabled
		GradientMap gradientMap = worker.getGradientMap();
		GeneratorConfig config = worker.getConfig();

		// With per-shape alpha off this draws nextInt(3) exactly as before, so
		// the flag-off RNG stream (and therefore the render) is unchanged.
		// With it on: 1/4 position, 2/4 size, 1/4 alpha.
		int kind;
		if (config.usePerShapeAlpha()) {
			kind = rnd.nextInt(4);
		} else {
			kind = (rnd.nextInt(3) == 0) ? 0 : 1;
		}

		if (kind == 0) {
			// Mutate position — scale perturbation by local gradient
			float scale = (gradientMap != null) ? gradientMap.getMutationScale(x, y) : 1.0f;
			int a = x + (int)(rnd.nextGaussian() * 16 * scale);
			int b = y + (int)(rnd.nextGaussian() * 16 * scale);
			x = BorstUtils.clampInt(a, 0, w);
			y = BorstUtils.clampInt(b, 0, h);
		} else if (kind == 3) {
			// Mutate alpha — one palette step up or down, floored at the
			// configured minimum (low alpha balloons shape counts)
			int step = rnd.nextBoolean() ? 1 : -1;
			alphaIndex = BorstUtils.clampInt(alphaIndex + step,
				config.minAlphaIndex(), BorstUtils.ALPHAS.length - 1);
		} else {
			if (gradientMap != null) {
				// Use gradient-biased size selection during mutation too
				int sizeIdx = gradientMap.selectSizeIndex(rnd, x, y);
				r = BorstUtils.SIZES[sizeIdx];
			} else {
				int c = BorstUtils.getClosestSize(r + (int)(rnd.nextGaussian() * 16));
				r = BorstUtils.clampInt(c, 1, w);
			}
		}
	}

	public void randomize() {
		randomize(null);
	}

	/**
	 * Randomize circle position and size.
	 * When an {@link ErrorMap} is provided and error-guided placement is enabled,
	 * 80% of placements are biased toward high-error regions via importance
	 * sampling. The remaining 20% use uniform random placement for exploration.
	 *
	 * When adaptive size selection is enabled and a {@link GradientMap} is
	 * available, circle sizes are biased by local gradient: small circles
	 * near edges, large circles in smooth areas.
	 *
	 * When per-shape alpha is enabled, the alpha index is drawn uniformly from
	 * [minAlphaIndex, ALPHAS.length - 1]; otherwise no RNG draw happens and the
	 * shape keeps the worker's global alpha index.
	 */
	public void randomize(ErrorMap errorMap) {
		Random rnd = worker.getRandom();
		if (errorMap != null && rnd.nextFloat() < 0.8f) {
			int packed = errorMap.samplePositionPacked(rnd);
			this.x = packed & 0xffff;
			this.y = packed >>> 16;
		} else {
			this.x = rnd.nextInt(worker.w);
			this.y = rnd.nextInt(worker.h);
		}

		GradientMap gradientMap = worker.getGradientMap();
		if (gradientMap != null) {
			int sizeIdx = gradientMap.selectSizeIndex(rnd, this.x, this.y);
			this.r = BorstUtils.SIZES[sizeIdx];
		} else {
			this.r = BorstUtils.SIZES[rnd.nextInt(BorstUtils.SIZES.length)];
		}

		GeneratorConfig config = worker.getConfig();
		if (config.usePerShapeAlpha()) {
			int min = config.minAlphaIndex();
			this.alphaIndex = min + rnd.nextInt(BorstUtils.ALPHAS.length - min);
		} else {
			this.alphaIndex = worker.alphaIndex;
		}
	}

	public void fromValues(Circle shape) {
		this.r = shape.r;
		this.x = shape.x;
		this.y = shape.y;
		this.alphaIndex = shape.alphaIndex;
	}
}
