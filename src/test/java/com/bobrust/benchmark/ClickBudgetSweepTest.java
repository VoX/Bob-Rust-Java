package com.bobrust.benchmark;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.bobrust.generator.BlobPruner;
import com.bobrust.generator.BorstColor;
import com.bobrust.generator.BorstImage;
import com.bobrust.generator.BorstUtils;
import com.bobrust.generator.Circle;
import com.bobrust.generator.ComplexTestImages;
import com.bobrust.generator.DiminishingReturns;
import com.bobrust.generator.GeneratorConfig;
import com.bobrust.generator.Model;
import com.bobrust.generator.TestBlobs;
import com.bobrust.generator.TestImageGenerator;
import com.bobrust.generator.sorter.Blob;
import com.bobrust.generator.sorter.BlobList;
import com.bobrust.generator.sorter.BorstSorter;
import com.bobrust.util.PaintTimeEstimator;
import com.bobrust.util.data.AppConstants;
import com.bobrust.util.metrics.ImageMetrics;

/**
 * The click-budget study: which generator/paint configuration maximizes
 * perceptual quality within a fixed painter click budget (default 18,000 =
 * 10 minutes at 30 clicks/second)?
 *
 * <p><b>Click accounting</b> (ground truth = {@link PaintTimeEstimator} /
 * {@code BobRustPainter.startDrawing}): every canvas blob is one click, every
 * in-loop tool change (size / color / alpha / shape, {@code getScore - 4} of
 * the SORTED plan) is one click, plus {@code floor((N-1)/1000)} autosave
 * clicks and the constant 21 setup/save clicks (17 setup + 4 final save).
 * Mouse travel is free (Robot.mouseMove teleports), so the click count IS the
 * paint time at a given cps: {@code estimateMillis(plan, 30, 0, v, 1000)}
 * equals {@code clicks/30 s + 2.55 s} (the setup clicks run at a fixed 150 ms
 * cycle) — the harness asserts that identity on every measured plan.
 *
 * <p>For each (image, config) the harness generates shapes incrementally
 * until the sorted full plan exceeds the budget, then binary-searches the
 * longest generation-order prefix whose sorted plan fits, re-renders that
 * exact plan with the committed paint kernel ({@link BlobPruner#render}) and
 * scores it against the target (RMSE / SSIM / mean CIEDE2000). Emits CSV.
 *
 * <p>Opt-in ({@code @Tag("benchmark")}):
 * <pre>
 * ./gradlew benchmark --tests com.bobrust.benchmark.ClickBudgetSweepTest \
 *   [-Dbench.clicks=18000] [-Dbench.images=texture,portrait]
 *   [-Dbench.shapeCap=20000] [-Dbench.prune=true] [-Dbench.curve=true]
 *   [-Dbench.extras=texture,skyline] [-Dbench.out=docs/speed-quality-results.csv]
 * </pre>
 */
@Tag("benchmark")
class ClickBudgetSweepTest {
	static final int BACKGROUND = TestBlobs.BACKGROUND;
	static final int GLOBAL_ALPHA = TestBlobs.ALPHA;
	static final int AUTOSAVE_INTERVAL = 1000;
	static final int CPS = 30;
	/**
	 * 17 setup click cycles + 4 final save clicks — constant per painting
	 * (PaintTimeEstimator.SETUP_CLICKS/FINAL_SAVE_CLICKS, package-private there).
	 */
	static final int SETUP_CLICKS = 17;
	static final int FINAL_SAVE_CLICKS = 4;
	static final int CONSTANT_CLICKS = SETUP_CLICKS + FINAL_SAVE_CLICKS;

	/** One measured budget point: the plan that fits and its true quality. */
	record Row(String image, String config, int clickBudget, int shapes, int toolChanges,
			int sizeChanges, int colorChanges, int alphaChanges,
			long clicks, long unsortedClicks, int groupBoundaryChanges,
			double rmse, double ssim, double deltaE00,
			double genSeconds, double paintMinutes, List<Blob> plan) {
	}

	@Test
	void sweepClickBudget() throws IOException {
		final int budget = Integer.getInteger("bench.clicks", 18000);
		final int shapeCap = Integer.getInteger("bench.shapeCap", 20000);
		final int chunk = Integer.getInteger("bench.chunk", 500);
		final boolean runPrune = Boolean.parseBoolean(System.getProperty("bench.prune", "true"));
		final boolean runCurve = Boolean.parseBoolean(System.getProperty("bench.curve", "true"));
		final String imageFilter = System.getProperty("bench.images", "");
		final String extraImages = System.getProperty("bench.extras", "texture,skyline");
		final File outFile = new File(System.getProperty("bench.out", "docs/speed-quality-results.csv"));
		final File renderDir = new File(System.getProperty("bench.renders", "docs/speed-quality-renders"));

		// Configs swept on every image: the levers that trade clicks for quality.
		Map<String, GeneratorConfig> core = new LinkedHashMap<>();
		core.put("default", GeneratorConfig.DEFAULT);
		core.put("noAdaptive", GeneratorConfig.parse("adaptiveSize=false"));
		core.put("noErrGuided", GeneratorConfig.parse("errorGuided=false"));
		core.put("singleAlpha", GeneratorConfig.parse("shapeAlpha=false"));
		core.put("opaque", GeneratorConfig.parse("minAlpha=5"));
		core.put("alphaFloor0", GeneratorConfig.parse("minAlpha=0"));
		core.put("fastGen", GeneratorConfig.parse("states=250;age=50"));

		// Configs swept on the bench.extras subset only (gen-cost levers + seed noise).
		Map<String, GeneratorConfig> extra = new LinkedHashMap<>();
		extra.put("hiStates", GeneratorConfig.parse("states=1000"));
		extra.put("sa", GeneratorConfig.parse("sa=true"));
		extra.put("exactRank", GeneratorConfig.parse("proxy=false"));
		extra.put("alphaFloor2", GeneratorConfig.parse("minAlpha=2"));
		extra.put("seedB", GeneratorConfig.parse("seed=1"));

		// Ad-hoc follow-up configs on every swept image:
		// -Dbench.addConfigs="label:key=val;key=val,label2:..." ('-' = skip core/extras)
		String addConfigs = System.getProperty("bench.addConfigs", "");
		if (addConfigs.startsWith("-")) {
			core.clear();
			extra.clear();
			addConfigs = addConfigs.substring(1);
		}
		for (String spec : addConfigs.split(",")) {
			int colon = spec.indexOf(':');
			if (colon > 0) {
				core.put(spec.substring(0, colon), GeneratorConfig.parse(spec.substring(colon + 1)));
			}
		}

		// Warm-up so JIT noise doesn't pollute the first genSeconds column.
		TestBlobs.generate(TestImageGenerator.createPhotoDetail(), 60);

		System.out.println();
		System.out.println("Click-budget sweep: budget=" + budget + " clicks (10 min @ 30 cps), autosave="
			+ AUTOSAVE_INTERVAL + ", constant clicks=" + CONSTANT_CLICKS + ", shapeCap=" + shapeCap);
		System.out.printf(Locale.ROOT, "%-9s %-12s %7s %7s %6s %6s %6s %7s %8s %6s %10s %8s %8s %8s%n",
			"image", "config", "budget", "shapes", "chgSz", "chgCol", "chgAl", "clicks", "unsorted",
			"bndry", "RMSE", "SSIM", "dE00", "gen_s");

		List<Row> rows = new ArrayList<>();
		for (Map.Entry<String, BufferedImage> entry : ComplexTestImages.corpus().entrySet()) {
			String imageName = entry.getKey();
			if (!imageFilter.isEmpty() && !("," + imageFilter + ",").contains("," + imageName + ",")) {
				continue;
			}
			BorstImage target = new BorstImage(TestBlobs.ensureArgb(entry.getValue()));

			Map<String, GeneratorConfig> configs = new LinkedHashMap<>(core);
			if (("," + extraImages + ",").contains("," + imageName + ",")) {
				configs.putAll(extra);
			}

			for (Map.Entry<String, GeneratorConfig> cfg : configs.entrySet()) {
				long begin = System.nanoTime();
				List<Blob> all = generateUntilOverBudget(target, cfg.getValue(), budget, shapeCap, chunk);
				double genSeconds = (System.nanoTime() - begin) / 1e9;

				int fit = maxShapesWithinBudget(all, budget);
				Row row = evaluate(imageName, cfg.getKey(), target, all.subList(0, fit), budget, genSeconds);
				rows.add(row);
				printRow(row);

				if ("default".equals(cfg.getKey())) {
					if (runCurve) {
						// Quality-vs-clicks curve from the same generation run.
						for (int cp = 3000; cp < budget; cp += 3000) {
							int n = maxShapesWithinBudget(all, cp);
							Row curveRow = evaluate(imageName, "default", target, all.subList(0, n), cp, 0);
							rows.add(curveRow);
							printRow(curveRow);
						}
					}
					if (runPrune) {
						Row pruneRow = pruneToBudget(imageName, target, all, budget);
						if (pruneRow != null) {
							rows.add(pruneRow);
							printRow(pruneRow);
						}
					}
				}
			}
		}

		writeCsv(outFile, rows);
		saveRenders(renderDir, rows, budget);
		System.out.println();
		System.out.println("CSV written to " + outFile.getAbsolutePath());
	}

	/**
	 * Run the generator incrementally until the SORTED full plan exceeds the
	 * click budget (or the shape cap). Returns the blobs in generation order —
	 * a prefix of this list is exactly what a shorter run would have produced.
	 */
	private static List<Blob> generateUntilOverBudget(BorstImage target, GeneratorConfig config,
			int budget, int shapeCap, int chunk) {
		Model model = new Model(target, BACKGROUND, GLOBAL_ALPHA, config);
		List<Blob> blobs = new ArrayList<>();
		boolean perShapeAlpha = config.usePerShapeAlpha();

		while (blobs.size() < shapeCap) {
			for (int i = 0; i < chunk && blobs.size() + i < shapeCap; i++) {
				model.processStep();
			}
			// Convert only the newly committed shapes (same math as TestBlobs).
			for (int i = blobs.size(); i < model.shapes.size(); i++) {
				Circle shape = model.shapes.get(i);
				BorstColor color = model.colors.get(i);
				blobs.add(Blob.of(shape.x, shape.y, shape.r, color.rgb,
					perShapeAlpha ? BorstUtils.ALPHAS[shape.alphaIndex] : GLOBAL_ALPHA,
					AppConstants.CIRCLE_SHAPE));
			}
			// S4: apply the same diminishing-returns auto-stop the generator uses, so the sweep measures the
			// real early-stop click savings (off unless qualityStop > 0).
			if (config.qualityStop() > 0
					&& DiminishingReturns.reached(model.getShapeContributions(), config.qualityStop(), 500)) {
				break;
			}
			if (clicksOf(BorstSorter.sort(new BlobList(blobs))) > budget) {
				break;
			}
		}
		return blobs;
	}

	/**
	 * Longest generation-order prefix whose sorted plan fits the budget.
	 * clicks(sort(prefix(n))) is monotone in n up to greedy-sort noise of a
	 * few clicks; binary search is exact modulo that noise.
	 */
	private static int maxShapesWithinBudget(List<Blob> all, int budget) {
		int lo = 0;
		int hi = all.size();
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			long clicks = clicksOf(BorstSorter.sort(new BlobList(all.subList(0, mid))));
			if (clicks <= budget) {
				lo = mid;
			} else {
				hi = mid - 1;
			}
		}
		return lo;
	}

	/** Total painter clicks of a sorted plan — see the class doc for the model. */
	static long clicksOf(BlobList sorted) {
		int n = sorted.size();
		if (n == 0) {
			return CONSTANT_CLICKS;
		}
		long autosaves = (n - 1) / AUTOSAVE_INTERVAL;
		return n + PaintTimeEstimator.toolChanges(sorted) + autosaves + CONSTANT_CLICKS;
	}

	/** Sort the plan, verify the click identity against the estimator, score the true render. */
	private static Row evaluate(String image, String config, BorstImage target, List<Blob> plan,
			int clickBudget, double genSeconds) {
		BlobList sorted = BorstSorter.sort(new BlobList(plan));
		long clicks = clicksOf(sorted);

		// Ground-truth check: the click count and the S2 estimator must agree.
		long estimatorMs = PaintTimeEstimator.estimateMillis(sorted, CPS, 0.0, 1, AUTOSAVE_INTERVAL);
		long estimatorClicks = Math.round((estimatorMs - SETUP_CLICKS * 3 * 50) * CPS / 1000.0)
			+ SETUP_CLICKS;
		if (Math.abs(estimatorClicks - clicks) > 1) {
			throw new AssertionError("click model diverged from PaintTimeEstimator: "
				+ clicks + " vs " + estimatorClicks);
		}

		long unsortedClicks = clicksOf(new BlobList(plan));
		int[] changes = countChanges(sorted);
		int boundary = groupBoundaryChanges(sorted);

		BorstImage rendered = BlobPruner.render(sorted.getList(), target.width, target.height, BACKGROUND);
		ImageMetrics.Result metrics = ImageMetrics.compare(target.pixels, rendered.pixels, target.width, target.height);

		return new Row(image, config, clickBudget, sorted.size(), PaintTimeEstimator.toolChanges(sorted),
			changes[0], changes[1], changes[2], clicks, unsortedClicks, boundary,
			metrics.rmse(), metrics.ssim(), metrics.deltaE00(),
			genSeconds, clicks / (double) CPS / 60.0, sorted.getList());
	}

	/**
	 * The S1 alternative to prefix truncation: generate past the budget, then
	 * budget-select the highest-value blobs with the exact pruner. Up to three
	 * iterations to land the click count just under the budget.
	 */
	private static Row pruneToBudget(String image, BorstImage target, List<Blob> all, int budget) {
		long begin = System.nanoTime();
		int fit = maxShapesWithinBudget(all, budget);
		int blobBudget = fit;
		Row best = null;
		for (int iter = 0; iter < 3; iter++) {
			List<Blob> kept = BlobPruner.prune(all, target, BACKGROUND,
				new BlobPruner.Options(blobBudget, -1.0)).kept();
			long clicks = clicksOf(BorstSorter.sort(new BlobList(kept)));
			if (clicks <= budget) {
				double pruneSeconds = (System.nanoTime() - begin) / 1e9;
				best = evaluate(image, "prune", target, kept, budget, pruneSeconds);
				long slack = budget - best.clicks();
				if (slack < 60) {
					break;
				}
				blobBudget += (int) (slack * 0.8);
			} else {
				blobBudget -= (int) (clicks - budget);
				if (blobBudget <= 0) {
					break;
				}
			}
		}
		return best;
	}

	/** Per-dimension tool-change decomposition of a sorted plan: {size, color, alpha}. */
	private static int[] countChanges(BlobList sorted) {
		int size = 0;
		int color = 0;
		int alpha = 0;
		for (int i = 1; i < sorted.size(); i++) {
			Blob a = sorted.get(i - 1);
			Blob b = sorted.get(i);
			size += a.size != b.size ? 1 : 0;
			color += a.color != b.color ? 1 : 0;
			alpha += a.alpha != b.alpha ? 1 : 0;
		}
		return new int[] { size, color, alpha };
	}

	/**
	 * Tool changes that occur exactly at MAX_SORT_GROUP boundaries — the upper
	 * bound of what a cross-group-aware sorter could still recover.
	 */
	private static int groupBoundaryChanges(BlobList sorted) {
		int changes = 0;
		for (int i = AppConstants.MAX_SORT_GROUP; i < sorted.size(); i += AppConstants.MAX_SORT_GROUP) {
			Blob a = sorted.get(i - 1);
			Blob b = sorted.get(i);
			changes += (a.size != b.size ? 1 : 0) + (a.color != b.color ? 1 : 0)
				+ (a.alpha != b.alpha ? 1 : 0) + (a.shapeIndex != b.shapeIndex ? 1 : 0);
		}
		return changes;
	}

	private static void printRow(Row r) {
		System.out.printf(Locale.ROOT, "%-9s %-12s %7d %7d %6d %6d %6d %7d %8d %6d %10.6f %8.5f %8.4f %8.1f%n",
			r.image(), r.config(), r.clickBudget(), r.shapes(), r.sizeChanges(), r.colorChanges(),
			r.alphaChanges(), r.clicks(), r.unsortedClicks(), r.groupBoundaryChanges(),
			r.rmse(), r.ssim(), r.deltaE00(), r.genSeconds());
	}

	private static void writeCsv(File file, List<Row> rows) throws IOException {
		File parent = file.getParentFile();
		if (parent != null) {
			parent.mkdirs();
		}
		try (PrintWriter out = new PrintWriter(file, "UTF-8")) {
			out.println("image,config,clickBudget,shapes,toolChanges,sizeChanges,colorChanges,alphaChanges,"
				+ "clicks,unsortedClicks,groupBoundaryChanges,rmse,ssim,deltaE00,genSeconds,paintMinutesAt30cps");
			for (Row r : rows) {
				out.printf(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.6f,%.5f,%.4f,%.1f,%.2f%n",
					r.image(), r.config(), r.clickBudget(), r.shapes(), r.toolChanges(), r.sizeChanges(),
					r.colorChanges(), r.alphaChanges(), r.clicks(), r.unsortedClicks(),
					r.groupBoundaryChanges(), r.rmse(), r.ssim(), r.deltaE00(), r.genSeconds(),
					r.paintMinutes());
			}
		}
	}

	/** Save the full-budget default render and the per-image SSIM winner for eyeballing. */
	private static void saveRenders(File dir, List<Row> rows, int budget) throws IOException {
		dir.mkdirs();
		Map<String, BufferedImage> corpus = ComplexTestImages.corpus();
		for (Map.Entry<String, BufferedImage> entry : corpus.entrySet()) {
			String image = entry.getKey();
			Row winner = null;
			Row defaultRow = null;
			for (Row r : rows) {
				if (!r.image().equals(image) || r.clickBudget() != budget) {
					continue;
				}
				if (r.config().equals("default")) {
					defaultRow = r;
				}
				if (winner == null || r.ssim() > winner.ssim()) {
					winner = r;
				}
			}
			if (defaultRow == null) {
				continue;
			}
			ImageIO.write(entry.getValue(), "png", new File(dir, image + "-target.png"));
			savePlan(dir, image + "-default", defaultRow, entry.getValue());
			if (winner != null && !winner.config().equals("default")) {
				savePlan(dir, image + "-winner-" + winner.config(), winner, entry.getValue());
			}
		}
	}

	private static void savePlan(File dir, String name, Row row, BufferedImage reference) throws IOException {
		BorstImage rendered = BlobPruner.render(row.plan(), reference.getWidth(), reference.getHeight(), BACKGROUND);
		BufferedImage img = new BufferedImage(reference.getWidth(), reference.getHeight(), BufferedImage.TYPE_INT_ARGB);
		img.setRGB(0, 0, img.getWidth(), img.getHeight(), rendered.pixels, 0, img.getWidth());
		ImageIO.write(img, "png", new File(dir, name + ".png"));
	}
}
