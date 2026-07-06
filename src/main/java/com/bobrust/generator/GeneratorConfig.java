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
 * proxy candidate ranking, 500 candidates), each backed by an F1 benchmark
 * run — see PERFORMANCE-PLAN.md. The pre-G behavior stays reachable with
 * {@code "sa=true;proxy=false;states=1000;age=100"}.
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
	boolean useBatchParallel,
	boolean useProxyRanking,
	int maxRandomStates,
	int age
) {
	public static final GeneratorConfig DEFAULT = new GeneratorConfig(
		0L,
		AppConstants.USE_SIMULATED_ANNEALING,
		AppConstants.USE_ERROR_GUIDED_PLACEMENT,
		AppConstants.USE_ADAPTIVE_SIZE,
		AppConstants.USE_BATCH_PARALLEL,
		AppConstants.USE_PROXY_RANKING,
		500,
		100
	);

	public GeneratorConfig {
		if (maxRandomStates < 1) {
			throw new IllegalArgumentException("maxRandomStates must be >= 1: " + maxRandomStates);
		}
		if (age < 1) {
			throw new IllegalArgumentException("age must be >= 1: " + age);
		}
	}

	public GeneratorConfig withSeed(long seed) {
		return new GeneratorConfig(seed, useSimulatedAnnealing, useErrorGuidedPlacement, useAdaptiveSize, useBatchParallel, useProxyRanking, maxRandomStates, age);
	}

	public GeneratorConfig withUseSimulatedAnnealing(boolean useSimulatedAnnealing) {
		return new GeneratorConfig(seed, useSimulatedAnnealing, useErrorGuidedPlacement, useAdaptiveSize, useBatchParallel, useProxyRanking, maxRandomStates, age);
	}

	public GeneratorConfig withUseErrorGuidedPlacement(boolean useErrorGuidedPlacement) {
		return new GeneratorConfig(seed, useSimulatedAnnealing, useErrorGuidedPlacement, useAdaptiveSize, useBatchParallel, useProxyRanking, maxRandomStates, age);
	}

	public GeneratorConfig withUseAdaptiveSize(boolean useAdaptiveSize) {
		return new GeneratorConfig(seed, useSimulatedAnnealing, useErrorGuidedPlacement, useAdaptiveSize, useBatchParallel, useProxyRanking, maxRandomStates, age);
	}

	public GeneratorConfig withUseBatchParallel(boolean useBatchParallel) {
		return new GeneratorConfig(seed, useSimulatedAnnealing, useErrorGuidedPlacement, useAdaptiveSize, useBatchParallel, useProxyRanking, maxRandomStates, age);
	}

	public GeneratorConfig withUseProxyRanking(boolean useProxyRanking) {
		return new GeneratorConfig(seed, useSimulatedAnnealing, useErrorGuidedPlacement, useAdaptiveSize, useBatchParallel, useProxyRanking, maxRandomStates, age);
	}

	public GeneratorConfig withMaxRandomStates(int maxRandomStates) {
		return new GeneratorConfig(seed, useSimulatedAnnealing, useErrorGuidedPlacement, useAdaptiveSize, useBatchParallel, useProxyRanking, maxRandomStates, age);
	}

	public GeneratorConfig withAge(int age) {
		return new GeneratorConfig(seed, useSimulatedAnnealing, useErrorGuidedPlacement, useAdaptiveSize, useBatchParallel, useProxyRanking, maxRandomStates, age);
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
			+ ";batchParallel=" + useBatchParallel
			+ ";proxy=" + useProxyRanking
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
					case "batchParallel" -> config.withUseBatchParallel(Boolean.parseBoolean(value));
					case "proxy" -> config.withUseProxyRanking(Boolean.parseBoolean(value));
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
