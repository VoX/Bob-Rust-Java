package com.bobrust.benchmark;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.bobrust.benchmark.BenchmarkHarness.BenchmarkResult;
import com.bobrust.generator.GeneratorConfig;
import com.bobrust.generator.TestImageGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Opt-in entry point for the F1 benchmark harness. Excluded from the default
 * {@code ./gradlew test} run (tagged {@code benchmark}); run with:
 *
 * <pre>
 * ./gradlew benchmark
 * ./gradlew benchmark -Dbench.shapes=500
 * ./gradlew benchmark -Dbench.configA="sa=true" -Dbench.configB="sa=false"
 * </pre>
 *
 * System properties:
 * <ul>
 *   <li>{@code bench.shapes} — shapes generated per image (default 200)</li>
 *   <li>{@code bench.configA} — serialized {@link GeneratorConfig} (default:
 *       {@link GeneratorConfig#DEFAULT}); missing keys keep their defaults</li>
 *   <li>{@code bench.configB} — optional second config for an A/B run</li>
 * </ul>
 * Config string format (any subset of keys):
 * {@code seed=0;sa=true;errorGuided=true;adaptiveSize=true;batchParallel=true;states=1000;age=100}
 */
@Tag("benchmark")
class GenerationBenchmarkTest {

	@Test
	void benchmarkCorpus() {
		int shapes = Integer.getInteger("bench.shapes", 200);
		String rawA = System.getProperty("bench.configA");
		String rawB = System.getProperty("bench.configB");

		// Warm-up: get the hot loops JIT-compiled before anything is timed
		BenchmarkHarness.run("warmup", TestImageGenerator.createPhotoDetail(),
			GeneratorConfig.DEFAULT, "warmup", Math.min(50, shapes));

		List<BenchmarkResult> results = new ArrayList<>(
			BenchmarkHarness.runCorpus(GeneratorConfig.parse(rawA), rawA == null ? "default" : "A", shapes));
		if (rawB != null) {
			results.addAll(BenchmarkHarness.runCorpus(GeneratorConfig.parse(rawB), "B", shapes));
		}

		System.out.println();
		System.out.println("Benchmark: " + shapes + " shapes/image, alpha=" + BenchmarkHarness.ALPHA
			+ ", background=#" + Integer.toHexString(BenchmarkHarness.BACKGROUND));
		if (rawA != null) {
			System.out.println("config A: " + GeneratorConfig.parse(rawA).serialize());
		}
		if (rawB != null) {
			System.out.println("config B: " + GeneratorConfig.parse(rawB).serialize());
		}
		System.out.println(BenchmarkHarness.formatTable(results));
	}

	/** Determinism guarantee of the harness: same seed + same config ⇒ byte-identical output. */
	@Test
	void sameConfigIsByteIdentical() {
		BenchmarkResult first = BenchmarkHarness.run("photo_detail",
			TestImageGenerator.createPhotoDetail(), GeneratorConfig.DEFAULT, "run1", 40);
		BenchmarkResult second = BenchmarkHarness.run("photo_detail",
			TestImageGenerator.createPhotoDetail(), GeneratorConfig.DEFAULT, "run2", 40);

		assertEquals(first.pixelHash(), second.pixelHash(), "renders must be byte-identical");
		assertEquals(first.rmse(), second.rmse(), 0.0);
		assertEquals(first.ssim(), second.ssim(), 0.0);
		assertEquals(first.deltaE00(), second.deltaE00(), 0.0);
	}
}
