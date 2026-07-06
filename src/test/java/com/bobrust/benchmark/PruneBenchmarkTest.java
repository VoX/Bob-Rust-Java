package com.bobrust.benchmark;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.bobrust.generator.BlobPruner;
import com.bobrust.generator.BorstImage;
import com.bobrust.generator.GeneratorConfig;
import com.bobrust.generator.TestBlobs;
import com.bobrust.generator.sorter.Blob;
import com.bobrust.generator.sorter.BlobList;
import com.bobrust.generator.sorter.BorstSorter;
import com.bobrust.settings.data.PaintPreset;
import com.bobrust.util.PaintTimeEstimator;
import com.bobrust.util.metrics.ImageMetrics;

/**
 * S1 evidence run: the quality-vs-blob-count curve. For each corpus image,
 * generate a fixed shape count, then budget-select down to 90/80/70/60% of
 * the blobs (plus a maxLoss=1% tolerance row) and report the TRUE re-rendered
 * F1 metrics (uniform RMSE, SSIM, mean ΔE00) of the kept set against the
 * target. Paint time is linear in the blob count, so "blobs kept" reads
 * directly as relative paint time.
 *
 * <p>Opt-in like the other benchmarks:
 * {@code ./gradlew benchmark --tests com.bobrust.benchmark.PruneBenchmarkTest
 * [-Dbench.shapes=800]}
 */
@Tag("benchmark")
class PruneBenchmarkTest {

	@Test
	void qualityVsBlobCountCurve() {
		int shapes = Integer.getInteger("bench.shapes", 800);
		int[] budgetPercents = { 100, 90, 80, 70, 60 };

		System.out.println();
		System.out.println("S1 prune curve: " + shapes + " generated shapes/image, budgets "
			+ java.util.Arrays.toString(budgetPercents) + "% + tolerance maxLoss=0.01");
		System.out.printf("%-14s %-12s %7s %10s %8s %8s %9s %9s%n",
			"image", "mode", "blobs", "RMSE", "SSIM", "dE00", "dRMSE%", "ddE00%");

		for (Map.Entry<String, BufferedImage> entry : BenchmarkHarness.corpus().entrySet()) {
			TestBlobs.Generated data = TestBlobs.generate(entry.getValue(), shapes);
			List<Blob> blobs = data.blobs();
			BorstImage target = data.target();

			ImageMetrics.Result base = metricsOf(blobs, target);
			printRow(entry.getKey(), "full", blobs.size(), base, base);

			for (int percent : budgetPercents) {
				if (percent == 100) {
					continue;
				}
				int budget = blobs.size() * percent / 100;
				BlobPruner.Result result = BlobPruner.prune(blobs, target, TestBlobs.BACKGROUND,
					new BlobPruner.Options(budget, -1));
				printRow(entry.getKey(), "budget" + percent, result.kept().size(),
					metricsOf(result.kept(), target), base);
			}

			BlobPruner.Result tolerance = BlobPruner.prune(blobs, target, TestBlobs.BACKGROUND,
				new BlobPruner.Options(0, 0.01));
			printRow(entry.getKey(), "maxLoss1%", tolerance.kept().size(),
				metricsOf(tolerance.kept(), target), base);
		}
	}

	/**
	 * S3 evidence run: the actual preset ladder. For each corpus image and
	 * each preset, generate with the preset's GeneratorConfig, prune with its
	 * budget/tolerance, sort, and report the TRUE re-rendered metrics, the
	 * match figure and the estimated paint time from the S2 cost model
	 * (t_cap = 12 ms prior). The "today" row is the pre-S3 shipping behavior
	 * (default config, no pruning, 30 cps, verify every click).
	 */
	@Test
	void presetLadder() {
		int shapes = Integer.getInteger("bench.shapes", 800);
		double captureMs = PaintTimeEstimator.DEFAULT_CAPTURE_MS;
		int autosave = 1000;

		System.out.println();
		System.out.println("S3 preset ladder: " + shapes + " generated shapes/image, t_cap="
			+ captureMs + " ms, autosave=" + autosave);
		System.out.printf("%-14s %-12s %7s %7s %10s %8s %8s %8s %9s %8s%n",
			"image", "preset", "blobs", "chgs", "RMSE", "SSIM", "dE00", "match%", "paint_s", "gen_s");

		for (Map.Entry<String, BufferedImage> entry : BenchmarkHarness.corpus().entrySet()) {
			// "today": what shipping defaults paint right now.
			presetRow(entry.getKey(), "today", entry.getValue(), shapes,
				GeneratorConfig.DEFAULT, BlobPruner.Options.NONE, 30, 1, captureMs, autosave);

			for (PaintPreset preset : PaintPreset.values()) {
				PaintPreset.Params params = preset.getParams();
				if (params == null) {
					continue;
				}
				presetRow(entry.getKey(), preset.name().toLowerCase(), entry.getValue(), shapes,
					params.generator(), params.prune(), params.clicksPerSecond(),
					params.verifyInterval(), captureMs, autosave);
			}
		}
	}

	private static void presetRow(String image, String label, BufferedImage source, int shapes,
			GeneratorConfig config, BlobPruner.Options prune, int cps, int verifyInterval,
			double captureMs, int autosave) {
		long begin = System.nanoTime();
		TestBlobs.Generated data = TestBlobs.generate(source, shapes, config);
		double genSeconds = (System.nanoTime() - begin) / 1e9;
		BorstImage target = data.target();

		List<Blob> kept = data.blobs();
		if (prune.enabled()) {
			kept = BlobPruner.prune(kept, target, TestBlobs.BACKGROUND, prune).kept();
		}
		BlobList sorted = BorstSorter.sort(new BlobList(kept));

		ImageMetrics.Result metrics = metricsOf(kept, target);
		double match = PaintTimeEstimator.matchPercent(kept, target, TestBlobs.BACKGROUND);
		long paintMs = PaintTimeEstimator.estimateMillis(sorted, cps, captureMs, verifyInterval, autosave);

		System.out.printf("%-14s %-12s %7d %7d %10.6f %8.5f %8.4f %7.2f%% %8.1fs %7.1fs%n",
			image, label, sorted.size(), PaintTimeEstimator.toolChanges(sorted),
			metrics.rmse(), metrics.ssim(), metrics.deltaE00(), match,
			paintMs / 1000.0, genSeconds);
	}

	private static ImageMetrics.Result metricsOf(List<Blob> blobs, BorstImage target) {
		BorstImage rendered = BlobPruner.render(blobs, target.width, target.height, TestBlobs.BACKGROUND);
		return ImageMetrics.compare(target.pixels, rendered.pixels, target.width, target.height);
	}

	private static void printRow(String image, String mode, int blobs,
			ImageMetrics.Result metrics, ImageMetrics.Result base) {
		System.out.printf("%-14s %-12s %7d %10.6f %8.5f %8.4f %8.2f%% %8.2f%%%n",
			image, mode, blobs, metrics.rmse(), metrics.ssim(), metrics.deltaE00(),
			relative(metrics.rmse(), base.rmse()), relative(metrics.deltaE00(), base.deltaE00()));
	}

	private static double relative(double value, double base) {
		return base == 0 ? 0 : (value / base - 1) * 100;
	}
}
