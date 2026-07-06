package com.bobrust.generator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Internal representation of the approximation model
 */
public class Model {
	private final Worker worker;
	private final GeneratorConfig config;
	private final BorstImage target;
	private final BorstImage beforeImage;
	public final BorstImage current;
		
	public final List<Circle> shapes;
	public final List<BorstColor> colors;
	public final int alpha;
	public final int width;
	public final int height;
	/**
	 * Exact squared-error total, carried as a long so the incremental updates
	 * stay identical to a full recompute (no float round-trip drift, no
	 * negative-total NaN near convergence). {@link #score} is derived from it
	 * for display only.
	 */
	private long totalError;
	protected float score;

	private ErrorMap errorMap;
	private GradientMap gradientMap;

	public Model(BorstImage target, int backgroundRGB, int alpha) {
		this(target, backgroundRGB, alpha, GeneratorConfig.DEFAULT);
	}

	public Model(BorstImage target, int backgroundRGB, int alpha, GeneratorConfig config) {
		int w = target.width;
		int h = target.height;
		this.shapes = new ArrayList<>();
		this.colors = new ArrayList<>();
		this.target = target;
		this.width = w;
		this.height = h;
		this.config = config;

		this.current = new BorstImage(w, h);
		Arrays.fill(this.current.pixels, backgroundRGB);
		this.beforeImage = new BorstImage(w, h);

		this.totalError = BorstCore.differenceFullTotal(target, current, config.usePerceptualColor());
		this.score = BorstCore.scoreFromTotal(totalError, w, h, config.usePerceptualColor());
		this.worker = new Worker(target, alpha, config);
		this.alpha = alpha;

		// Initialize error map if error-guided placement is enabled
		if (config.useErrorGuidedPlacement()) {
			this.errorMap = new ErrorMap(w, h);
			this.errorMap.computeFull(target, current);
			this.worker.setErrorMap(this.errorMap);
		}

		// Initialize gradient map if adaptive size selection is enabled
		if (config.useAdaptiveSize()) {
			this.gradientMap = new GradientMap(w, h);
			this.gradientMap.compute(target);
			this.worker.setGradientMap(this.gradientMap);
		}
	}

	/**
	 * Commit a shape to the model. {@code knownColor} is the exact optimal
	 * color the winner's last exact energy evaluation derived (identical math,
	 * same current image — see State#color); passing null recomputes it here.
	 * Geometry, color and the running total are exact either way.
	 */
	private void addShape(Circle shape, BorstColor knownColor) {
		beforeImage.draw(current);

		final boolean perceptual = config.usePerceptualColor();
		final int shapeAlpha = worker.alphaFor(shape);
		int cache_index = BorstUtils.getClosestSizeIndex(shape.r);
		BorstColor color = (knownColor != null)
			? knownColor
			: BorstCore.computeColor(target, current, shapeAlpha, cache_index, shape.x, shape.y, perceptual);

		BorstCore.drawLines(current, color, shapeAlpha, cache_index, shape.x, shape.y);
		this.totalError = BorstCore.differencePartialTotal(target, beforeImage, current, totalError, cache_index, shape.x, shape.y, perceptual);
		this.score = BorstCore.scoreFromTotal(totalError, width, height, perceptual);
		shapes.add(shape);
		colors.add(color);

		// Incrementally update the error map after drawing the new shape
		if (errorMap != null) {
			errorMap.updateIncremental(target, current, shape.x, shape.y, cache_index);
		}
	}

	/**
	 * Add a pre-defined shape to this model without running optimization.
	 * Used by MultiResModel to propagate shapes from lower to higher resolutions.
	 */
	public void addExternalShape(Circle shape) {
		addShape(shape, null);
	}

	/** Returns the current model score. */
	public float getScore() {
		return score;
	}

	/** Returns the exact squared-error total that {@link #getScore()} is derived from. */
	long getTotalError() {
		return totalError;
	}

	/** Package-private accessor for the worker (used by MultiResModel). */
	Worker getWorker() {
		return worker;
	}

	/** The runtime generator configuration (used by BorstData for per-shape alpha). */
	GeneratorConfig getConfig() {
		return config;
	}

	// max_random_states and age moved to GeneratorConfig
	private static final int times = 1; // one refine chain per step; parallel chains are a deferred item (perf plan #7)

	private List<State> randomStates;

	public int processStep() {
		worker.init(current, totalError);
		if (randomStates == null) {
			randomStates = new ArrayList<>();
			for (int i = 0; i < config.maxRandomStates(); i++) {
				randomStates.add(new State(worker));
			}
		}

		State state = HillClimbGenerator.getBestHillClimbState(randomStates, config.age(), times, errorMap);
		addShape(state.shape, state.color);

		return worker.getCounter();
	}
}