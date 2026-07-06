package com.bobrust.benchmark;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.bobrust.generator.BorstImage;
import com.bobrust.generator.BorstUtils;
import com.bobrust.generator.GeneratorConfig;
import com.bobrust.generator.Model;
import com.bobrust.generator.TestImageGenerator;
import com.bobrust.util.metrics.ImageMetrics;

/**
 * F1 benchmark harness: runs the generator for a fixed shape count over the
 * synthetic test-image corpus and reports wall time, shapes/s, RMSE, SSIM and
 * mean ΔE00 per (image, config) in a comparable table.
 *
 * <p>Generation is fully deterministic for a given (image, config): the same
 * seed produces a byte-identical render, which {@link BenchmarkResult#pixelHash()}
 * makes visible — equal hashes mean literally identical output, so two
 * {@link GeneratorConfig}s can be A/B compared honestly and any behavior
 * change shows up as a changed hash or metric. Wall time is the only
 * non-deterministic column; compare it within a single JVM run (the harness
 * caller should do a warm-up run first, see {@code GenerationBenchmarkTest}).
 *
 * <p>Off the default test path — run via {@code ./gradlew benchmark}.
 */
public final class BenchmarkHarness {
	/** Fixed white background, matching the existing regression tests. */
	public static final int BACKGROUND = 0xFFFFFFFF;
	/** Alpha index 2 is the app's default alpha setting. */
	public static final int ALPHA = BorstUtils.ALPHAS[2];

	private BenchmarkHarness() {
	}

	/**
	 * One benchmark measurement. All fields except {@code wallTimeMs} and
	 * {@code shapesPerSec} are deterministic for a given (image, config).
	 *
	 * @param pixelHash FNV-1a hash of the rendered pixels; equal hash ⇒ byte-identical render
	 */
	public record BenchmarkResult(
		String image,
		String config,
		int shapes,
		long wallTimeMs,
		double shapesPerSec,
		double rmse,
		double ssim,
		double deltaE00,
		long pixelHash
	) {
	}

	/** The fixed synthetic corpus from {@link TestImageGenerator}, in stable order. */
	public static Map<String, BufferedImage> corpus() {
		Map<String, BufferedImage> corpus = new LinkedHashMap<>();
		corpus.put("solid", TestImageGenerator.createSolid());
		corpus.put("gradient", TestImageGenerator.createGradient());
		corpus.put("edges", TestImageGenerator.createEdges());
		corpus.put("photo_detail", TestImageGenerator.createPhotoDetail());
		corpus.put("nature", TestImageGenerator.createNature());
		return corpus;
	}

	/** Runs one (image, config) measurement: generate {@code shapeCount} shapes, then score the render. */
	public static BenchmarkResult run(String imageName, BufferedImage image, GeneratorConfig config, String configLabel, int shapeCount) {
		BorstImage target = new BorstImage(toIntArgb(image));
		Model model = new Model(target, BACKGROUND, ALPHA, config);

		long begin = System.nanoTime();
		for (int i = 0; i < shapeCount; i++) {
			model.processStep();
		}
		long wallNanos = System.nanoTime() - begin;

		ImageMetrics.Result metrics = ImageMetrics.compare(target, model.current);
		return new BenchmarkResult(
			imageName,
			configLabel,
			shapeCount,
			wallNanos / 1_000_000,
			shapeCount / (wallNanos / 1e9),
			metrics.rmse(),
			metrics.ssim(),
			metrics.deltaE00(),
			hash(model.current.pixels)
		);
	}

	/** Runs the whole corpus under one config. */
	public static List<BenchmarkResult> runCorpus(GeneratorConfig config, String configLabel, int shapeCount) {
		List<BenchmarkResult> results = new ArrayList<>();
		for (Map.Entry<String, BufferedImage> entry : corpus().entrySet()) {
			results.add(run(entry.getKey(), entry.getValue(), config, configLabel, shapeCount));
		}
		return results;
	}

	/** Formats results as an aligned, comparable table. */
	public static String formatTable(List<BenchmarkResult> results) {
		StringBuilder sb = new StringBuilder();
		sb.append(String.format("%-14s %-10s %7s %9s %10s %10s %8s %8s  %s%n",
			"image", "config", "shapes", "wall(ms)", "shapes/s", "RMSE", "SSIM", "dE00", "pixelHash"));
		for (BenchmarkResult r : results) {
			sb.append(String.format("%-14s %-10s %7d %9d %10.1f %10.6f %8.5f %8.4f  %016x%n",
				r.image(), r.config(), r.shapes(), r.wallTimeMs(), r.shapesPerSec(),
				r.rmse(), r.ssim(), r.deltaE00(), r.pixelHash()));
		}
		return sb.toString();
	}

	/** FNV-1a over the rendered ARGB pixels — equal hash ⇒ byte-identical render. */
	public static long hash(int[] pixels) {
		long h = 0xcbf29ce484222325L;
		for (int p : pixels) {
			for (int shift = 0; shift < 32; shift += 8) {
				h ^= (p >>> shift) & 0xff;
				h *= 0x100000001b3L;
			}
		}
		return h;
	}

	private static BufferedImage toIntArgb(BufferedImage image) {
		if (image.getType() == BufferedImage.TYPE_INT_ARGB) {
			return image;
		}
		BufferedImage converted = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = converted.createGraphics();
		g.drawImage(image, 0, 0, null);
		g.dispose();
		return converted;
	}
}
