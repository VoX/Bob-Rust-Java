package com.bobrust.generator;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

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
	private final AtomicInteger counter = new AtomicInteger();
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
		this.counter.set(0);
	}

	public float getEnergy(Circle circle) {
		this.counter.incrementAndGet();
		int cache_index = BorstUtils.getClosestSizeIndex(circle.r);
		return BorstCore.differencePartialThread(target, current, totalError, alpha, cache_index, circle.x, circle.y, config.useBatchParallel());
	}

	public int getCounter() {
		return counter.get();
	}
}
