package com.bobrust.generator;

import com.bobrust.util.data.AppConstants;

/**
 * Runtime configuration for the shape generator.
 *
 * <p>Carries the generation seed and the knobs that used to be compile-time
 * constants (the {@code AppConstants.USE_*} generator flags and
 * {@code Model}'s {@code max_random_states}/{@code age}) so benchmarks and
 * future presets can vary them without recompiling. {@link #DEFAULT} is the
 * shipping configuration: the Phase-G tuned values (classic hill climb,
 * proxy candidate ranking, 500 candidates) plus the Phase-Q quality flags,
 * each backed by an F1 benchmark run — see PERFORMANCE-PLAN.md and
 * IMPROVEMENT-PLAN.md §3 Phase 2. The pre-G behavior stays reachable with
 * {@code "sa=true;proxy=false;states=1000;age=100"}; the pre-Q behavior with
 * {@code "perceptual=false;shapeAlpha=false"}.
 *
 * <p>Serializes to a compact {@code key=value;...} string (see
 * {@link #serialize()} / {@link #parse(String)}) so a config can be stored as
 * a single settings property or passed to the benchmark harness on the
 * command line.
 */
public record GeneratorConfig(
	long seed,
	boolean useSimulatedAnnealing,
	boolean useErrorGuidedPlacement,
	boolean useAdaptiveSize,
	double sizeClickBias,
	boolean useBatchParallel,
	boolean useProxyRanking,
	boolean usePerceptualColor,
	boolean usePerShapeAlpha,
	int minAlphaIndex,
	int maxRandomStates,
	int age
) {
	public static final GeneratorConfig DEFAULT = new GeneratorConfig(
		0L,
		AppConstants.USE_SIMULATED_ANNEALING,
		AppConstants.USE_ERROR_GUIDED_PLACEMENT,
		AppConstants.USE_ADAPTIVE_SIZE,
		0.0,
		AppConstants.USE_BATCH_PARALLEL,
		AppConstants.USE_PROXY_RANKING,
		AppConstants.USE_PERCEPTUAL_COLOR,
		AppConstants.USE_PER_SHAPE_ALPHA,
		AppConstants.MIN_ALPHA_INDEX,
		500,
		100
	);

	/** Sentinel for {@code minAlphaIndex}: resolve the alpha floor per image from the target's edge statistics
	 *  (GradientMap hardEdgeFraction) in the Model constructor. Never reaches Worker/Circle unresolved. */
	public static final int MIN_ALPHA_AUTO = -1;

	public GeneratorConfig {
		if (maxRandomStates < 1) {
			throw new IllegalArgumentException("maxRandomStates must be >= 1: " + maxRandomStates);
		}
		if (age < 1) {
			throw new IllegalArgumentException("age must be >= 1: " + age);
		}
		if (minAlphaIndex != MIN_ALPHA_AUTO && (minAlphaIndex < 0 || minAlphaIndex >= BorstUtils.ALPHAS.length)) {
			throw new IllegalArgumentException("minAlphaIndex must be in [0, " + (BorstUtils.ALPHAS.length - 1) + "] or MIN_ALPHA_AUTO (-1): " + minAlphaIndex);
		}
		if (!Double.isFinite(sizeClickBias) || sizeClickBias < 0 || sizeClickBias > 2) {
			throw new IllegalArgumentException("sizeClickBias must be finite in [0, 2]: " + sizeClickBias);
		}
	}

	public GeneratorConfig withSeed(long seed) {
		return new GeneratorConfig(seed, useSimulatedAnnealing, useErrorGuidedPlacement, useAdaptiveSize, sizeClickBias, useBatchParallel, useProxyRanking, usePerceptualColor, usePerShapeAlpha, minAlphaIndex, maxRandomStates, age);
	}

	public GeneratorConfig withUseSimulatedAnnealing(boolean useSimulatedAnnealing) {
		return new GeneratorConfig(seed, useSimulatedAnnealing, useErrorGuidedPlacement, useAdaptiveSize, sizeClickBias, useBatchParallel, useProxyRanking, usePerceptualColor, usePerShapeAlpha, minAlphaIndex, maxRandomStates, age);
	}

	public GeneratorConfig withUseErrorGuidedPlacement(boolean useErrorGuidedPlacement) {
		return new GeneratorConfig(seed, useSimulatedAnnealing, useErrorGuidedPlacement, useAdaptiveSize, sizeClickBias, useBatchParallel, useProxyRanking, usePerceptualColor, usePerShapeAlpha, minAlphaIndex, maxRandomStates, age);
	}

	public GeneratorConfig withUseAdaptiveSize(boolean useAdaptiveSize) {
		return new GeneratorConfig(seed, useSimulatedAnnealing, useErrorGuidedPlacement, useAdaptiveSize, sizeClickBias, useBatchParallel, useProxyRanking, usePerceptualColor, usePerShapeAlpha, minAlphaIndex, maxRandomStates, age);
	}

	/** Click-aware size tilt (P1b). Only meaningful with {@code adaptiveSize=true} (no GradientMap otherwise).
	 *  0 = off (bit-identical to the pre-P1b weighting); beta=1 ~ selection probability proportional to covered
	 *  area x the gradient prior. Experimental — dormant by default until the beta sweep decides. */
	public GeneratorConfig withSizeClickBias(double sizeClickBias) {
		return new GeneratorConfig(seed, useSimulatedAnnealing, useErrorGuidedPlacement, useAdaptiveSize, sizeClickBias, useBatchParallel, useProxyRanking, usePerceptualColor, usePerShapeAlpha, minAlphaIndex, maxRandomStates, age);
	}

	public GeneratorConfig withUseBatchParallel(boolean useBatchParallel) {
		return new GeneratorConfig(seed, useSimulatedAnnealing, useErrorGuidedPlacement, useAdaptiveSize, sizeClickBias, useBatchParallel, useProxyRanking, usePerceptualColor, usePerShapeAlpha, minAlphaIndex, maxRandomStates, age);
	}

	public GeneratorConfig withUseProxyRanking(boolean useProxyRanking) {
		return new GeneratorConfig(seed, useSimulatedAnnealing, useErrorGuidedPlacement, useAdaptiveSize, sizeClickBias, useBatchParallel, useProxyRanking, usePerceptualColor, usePerShapeAlpha, minAlphaIndex, maxRandomStates, age);
	}

	public GeneratorConfig withUsePerceptualColor(boolean usePerceptualColor) {
		return new GeneratorConfig(seed, useSimulatedAnnealing, useErrorGuidedPlacement, useAdaptiveSize, sizeClickBias, useBatchParallel, useProxyRanking, usePerceptualColor, usePerShapeAlpha, minAlphaIndex, maxRandomStates, age);
	}

	public GeneratorConfig withUsePerShapeAlpha(boolean usePerShapeAlpha) {
		return new GeneratorConfig(seed, useSimulatedAnnealing, useErrorGuidedPlacement, useAdaptiveSize, sizeClickBias, useBatchParallel, useProxyRanking, usePerceptualColor, usePerShapeAlpha, minAlphaIndex, maxRandomStates, age);
	}

	public GeneratorConfig withMinAlphaIndex(int minAlphaIndex) {
		return new GeneratorConfig(seed, useSimulatedAnnealing, useErrorGuidedPlacement, useAdaptiveSize, sizeClickBias, useBatchParallel, useProxyRanking, usePerceptualColor, usePerShapeAlpha, minAlphaIndex, maxRandomStates, age);
	}

	public GeneratorConfig withMaxRandomStates(int maxRandomStates) {
		return new GeneratorConfig(seed, useSimulatedAnnealing, useErrorGuidedPlacement, useAdaptiveSize, sizeClickBias, useBatchParallel, useProxyRanking, usePerceptualColor, usePerShapeAlpha, minAlphaIndex, maxRandomStates, age);
	}

	public GeneratorConfig withAge(int age) {
		return new GeneratorConfig(seed, useSimulatedAnnealing, useErrorGuidedPlacement, useAdaptiveSize, sizeClickBias, useBatchParallel, useProxyRanking, usePerceptualColor, usePerShapeAlpha, minAlphaIndex, maxRandomStates, age);
	}

	/**
	 * Serialize to the compact {@code key=value;...} form. Inverse of
	 * {@link #parse(String)}.
	 */
	public String serialize() {
		return "seed=" + seed
			+ ";sa=" + useSimulatedAnnealing
			+ ";errorGuided=" + useErrorGuidedPlacement
			+ ";adaptiveSize=" + useAdaptiveSize
			+ ";sizeBias=" + sizeClickBias
			+ ";batchParallel=" + useBatchParallel
			+ ";proxy=" + useProxyRanking
			+ ";perceptual=" + usePerceptualColor
			+ ";shapeAlpha=" + usePerShapeAlpha
			+ ";minAlpha=" + minAlphaIndex
			+ ";states=" + maxRandomStates
			+ ";age=" + age;
	}

	/**
	 * Parse the {@code key=value;...} form produced by {@link #serialize()}.
	 * A {@code null}/blank input returns {@link #DEFAULT}; any missing,
	 * unknown or malformed key keeps the corresponding default, so a partial
	 * config like {@code "sa=false;age=50"} is valid.
	 */
	public static GeneratorConfig parse(String text) {
		GeneratorConfig config = DEFAULT;
		if (text == null || text.isBlank()) {
			return config;
		}

		for (String entry : text.split(";")) {
			int split = entry.indexOf('=');
			if (split < 0) {
				continue;
			}
			String key = entry.substring(0, split).trim();
			String value = entry.substring(split + 1).trim();
			try {
				config = switch (key) {
					case "seed" -> config.withSeed(Long.parseLong(value));
					case "sa" -> config.withUseSimulatedAnnealing(Boolean.parseBoolean(value));
					case "errorGuided" -> config.withUseErrorGuidedPlacement(Boolean.parseBoolean(value));
					case "adaptiveSize" -> config.withUseAdaptiveSize(Boolean.parseBoolean(value));
					case "sizeBias" -> config.withSizeClickBias(Double.parseDouble(value));
					case "batchParallel" -> config.withUseBatchParallel(Boolean.parseBoolean(value));
					case "proxy" -> config.withUseProxyRanking(Boolean.parseBoolean(value));
					case "perceptual" -> config.withUsePerceptualColor(Boolean.parseBoolean(value));
					case "shapeAlpha" -> config.withUsePerShapeAlpha(Boolean.parseBoolean(value));
					case "minAlpha" -> config.withMinAlphaIndex(Integer.parseInt(value));
					case "states" -> config.withMaxRandomStates(Integer.parseInt(value));
					case "age" -> config.withAge(Integer.parseInt(value));
					default -> config;
				};
			} catch (IllegalArgumentException ignored) {
				// Malformed or out-of-range value — keep the default for this key
			}
		}

		return config;
	}
}
