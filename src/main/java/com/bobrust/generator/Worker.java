package com.bobrust.generator;

import java.util.Random;
import java.util.concurrent.atomic.LongAdder;

class Worker {
	private final BorstImage target;
	private final GeneratorConfig config;
	/**
	 * Seeded from {@link GeneratorConfig#seed()} so generation is reproducible
	 * for a given (image, config) input. All RNG consumers (Circle.randomize,
	 * Circle.mutateShape and the SA acceptance draws) run on the single
	 * generator thread — the parallel energy evaluation is RNG-free — so one
	 * seeded instance per worker is both deterministic and contention-free.
	 */
	private final Random random;
	private BorstImage current;
	public final int alpha;

	public final int w;
	public final int h;
	public long totalError;
	/**
	 * Debug-only count of exact energy evaluations per step (proxy ranking
	 * evaluations are not counted). LongAdder because the eval phase increments
	 * from the parallel stream; the exact value is only read once per step.
	 */
	private final LongAdder counter = new LongAdder();
	private ErrorMap errorMap;
	private GradientMap gradientMap;

	public Worker(BorstImage target, int alpha) {
		this(target, alpha, GeneratorConfig.DEFAULT);
	}

	public Worker(BorstImage target, int alpha, GeneratorConfig config) {
		this.w = target.width;
		this.h = target.height;
		this.target = target;
		this.alpha = alpha;
		this.config = config;
		this.random = new Random(config.seed());
	}

	/** Returns the runtime generator configuration this worker was created with. */
	public GeneratorConfig getConfig() {
		return config;
	}

	/** Returns the error map, or null if error-guided placement is disabled. */
	public ErrorMap getErrorMap() {
		return errorMap;
	}

	/** Sets the error map (called from Model when error-guided placement is enabled). */
	public void setErrorMap(ErrorMap errorMap) {
		this.errorMap = errorMap;
	}

	/** Returns the gradient map, or null if adaptive sizing is disabled. */
	public GradientMap getGradientMap() {
		return gradientMap;
	}

	/** Sets the gradient map (called from Model when adaptive sizing is enabled). */
	public void setGradientMap(GradientMap gradientMap) {
		this.gradientMap = gradientMap;
	}

	/**
	 * Returns this worker's seeded Random instance. Only ever called from the
	 * generator thread (never from the parallel energy evaluation), so it needs
	 * no thread-local handling and keeps generation reproducible.
	 */
	public Random getRandom() {
		return random;
	}

	public void init(BorstImage current, long totalError) {
		this.current = current;
		this.totalError = totalError;
		this.counter.reset();
	}

	public float getEnergy(Circle circle) {
		return getEnergy(circle, null);
	}

	/**
	 * Exact energy of drawing {@code circle} on the current image. When
	 * {@code colorOut} is non-null, the optimal color the kernel derived is
	 * reported through it (see BorstCore.differencePartialThread) so the commit
	 * path can skip its computeColor re-run.
	 */
	public float getEnergy(Circle circle, BorstColor[] colorOut) {
		this.counter.increment();
		int cache_index = BorstUtils.getClosestSizeIndex(circle.r);
		return BorstCore.differencePartialThread(target, current, totalError, alpha, cache_index, circle.x, circle.y, config.useBatchParallel(), colorOut);
	}

	/**
	 * Approximate energy on a strided pixel subset — used ONLY to rank the
	 * per-step random candidates against each other. The winning candidate is
	 * always re-evaluated exactly before refinement and commit.
	 */
	public float getProxyEnergy(Circle circle) {
		int cache_index = BorstUtils.getClosestSizeIndex(circle.r);
		return BorstCore.differencePartialProxy(target, current, totalError, alpha, cache_index, circle.x, circle.y);
	}

	public int getCounter() {
		return counter.intValue();
	}
}
