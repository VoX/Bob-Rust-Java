package com.bobrust.generator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.bobrust.util.data.AppConstants;

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
	/**
	 * Marginal contribution of each committed shape (S1a): the exact reduction
	 * of {@link #totalError} the shape's commit produced, in the model's energy
	 * metric (perceptual-weighted when Q1 is on). Parallel to {@link #shapes}.
	 * Recorded against the composite at commit time — dropping an earlier shape
	 * changes what later shapes blended over (the substrate effect), so this is
	 * a ranking heuristic only; any pruning decision must re-render and verify
	 * the true score (see BlobPruner).
	 */
	private final List<Long> shapeContributions;
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
		this.shapeContributions = new ArrayList<>();
		this.target = target;
		this.width = w;
		this.height = h;
		// The gradient map is needed if adaptive size is on OR the alpha floor is auto (stats-only in the latter
		// case). Build + resolve the auto floor BEFORE the Worker, which reads the resolved minAlphaIndex.
		GradientMap gradientMap = null;
		if (config.useAdaptiveSize() || config.minAlphaIndex() == GeneratorConfig.MIN_ALPHA_AUTO) {
			// sizeClickBias only tilts selection when the map is attached to the worker (adaptiveSize=true); it is
			// inert on the stats-only auto-alpha path, so passing it unconditionally is harmless.
			gradientMap = new GradientMap(w, h, config.sizeClickBias());
			gradientMap.compute(target);
		}
		if (config.minAlphaIndex() == GeneratorConfig.MIN_ALPHA_AUTO) {
			int resolved = resolveAutoMinAlpha(gradientMap);
			AppConstants.LOGGER.info("Auto alpha floor: hardEdgeFraction={} -> minAlphaIndex={}",
				String.format("%.4f", gradientMap.getHardEdgeFraction()), resolved);
			config = config.withMinAlphaIndex(resolved);
		}
		this.config = config;

		this.current = new BorstImage(w, h);
		Arrays.fill(this.current.pixels, backgroundRGB);
		this.beforeImage = new BorstImage(w, h);

		this.totalError = BorstCore.differenceFullTotal(target, current, config.usePerceptualColor());
		this.score = BorstCore.scoreFromTotal(totalError, w, h, config.usePerceptualColor());
		this.worker = new Worker(target, alpha, config);   // sees the RESOLVED config
		this.alpha = alpha;

		// Initialize error map if error-guided placement is enabled
		if (config.useErrorGuidedPlacement()) {
			this.errorMap = new ErrorMap(w, h);
			this.errorMap.computeFull(target, current);
			this.worker.setErrorMap(this.errorMap);
		}

		// Attach the gradient map to the worker ONLY when adaptive size is on. When it was built stats-only for
		// the auto alpha floor it must NOT drive size selection (that stays uniform, per S1).
		if (config.useAdaptiveSize()) {
			this.gradientMap = gradientMap;
			this.worker.setGradientMap(this.gradientMap);
		}
	}

	/** Fraction of hard-edge pixels at/above which glazing (alpha floor 0) hurts; below it, photographic content
	 *  benefits from the extra translucency. Calibrated on the 5-image corpus (AutoAlphaContentStatsTest): with
	 *  HARD_EDGE_MAGNITUDE=500 the corpus measured texture 0.000 / portrait 0.004 (smooth) vs mosaic 0.034 /
	 *  glyphs 0.196 (edgy); 0.01 sits >=2x clear of the closest of each class. */
	static final float AUTO_ALPHA_EDGE_THRESHOLD = 0.01f;

	/** Resolve the {@link GeneratorConfig#MIN_ALPHA_AUTO} sentinel: hard-edge/text content keeps the shipped floor
	 *  ({@link AppConstants#MIN_ALPHA_INDEX}), photographic content drops to 0 for glazing. Never returns opaque —
	 *  stencil mode is explicit-only. */
	static int resolveAutoMinAlpha(GradientMap map) {
		return map.getHardEdgeFraction() >= AUTO_ALPHA_EDGE_THRESHOLD ? AppConstants.MIN_ALPHA_INDEX : 0;
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
		final long errorBefore = totalError;
		int cache_index = BorstUtils.getClosestSizeIndex(shape.r);
		BorstColor color = (knownColor != null)
			? knownColor
			: BorstCore.computeColor(target, current, shapeAlpha, cache_index, shape.x, shape.y, perceptual);

		BorstCore.drawLines(current, color, shapeAlpha, cache_index, shape.x, shape.y);
		this.totalError = BorstCore.differencePartialTotal(target, beforeImage, current, totalError, cache_index, shape.x, shape.y, perceptual);
		this.score = BorstCore.scoreFromTotal(totalError, width, height, perceptual);
		shapes.add(shape);
		colors.add(color);
		shapeContributions.add(errorBefore - totalError);

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

	/**
	 * Per-shape marginal contributions, parallel to {@link #shapes} — see the
	 * field doc for the substrate-effect caveat. Live list; only read from the
	 * generator thread (BorstData.update).
	 */
	public List<Long> getShapeContributions() {
		return shapeContributions;
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