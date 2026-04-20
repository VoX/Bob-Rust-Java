package com.bobrust.generator;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Final comparison: generates before/after images and statistics for all 6 proposals combined.
 *
 * "BEFORE" = all features OFF (classic hill climbing, uniform placement, uniform sizing,
 *            classic energy, no TSP, single resolution).
 * "AFTER"  = all features ON  (simulated annealing, error-guided placement, adaptive size,
 *            batch-parallel energy, TSP optimization, progressive resolution).
 *
 * Run with: ./gradlew test --tests "com.bobrust.generator.FinalComparisonTest" -i
 * Or run main() directly.
 */
public class FinalComparisonTest {
    private static final int ALPHA = 128;
    private static final int BACKGROUND = 0xFFFFFFFF;
    private static final int MAX_SHAPES = 200;
    private static final int NUM_RUNS = 5; // For statistical analysis

    // ===================== BEFORE: All features OFF =====================

    /**
     * Runs the generator with SA, error-guided placement, and adaptive sizing OFF.
     * - No error map (uniform random placement)
     * - No gradient map (uniform random sizing)
     * - Classic hill climbing (no SA)
     * - Energy evaluation still uses whatever is compiled (batch-parallel doesn't affect quality)
     * - No progressive resolution
     *
     * This mirrors the original algorithm before any proposals were added.
     */
    private static RunResult runAllFeaturesOff(BorstImage target, int maxShapes) {
        Model model = new Model(target, BACKGROUND, ALPHA);

        // Strip error map and gradient map from worker so Circle.randomize(null)
        // and Circle.mutateShape() fall back to uniform behavior.
        Worker worker = model.getWorker();
        worker.setErrorMap(null);
        worker.setGradientMap(null);

        List<State> randomStates = new ArrayList<>();
        for (int j = 0; j < 1000; j++) {
            randomStates.add(new State(worker));
        }

        int times = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

        for (int i = 0; i < maxShapes; i++) {
            worker.init(model.current, model.score);

            float bestEnergy = Float.MAX_VALUE;
            State bestState = null;

            for (int t = 0; t < times; t++) {
                // Randomize without error map = uniform placement
                for (State s : randomStates) {
                    s.score = -1;
                    s.shape.randomize(null);
                }
                randomStates.parallelStream().forEach(State::getEnergy);

                // Find best from random sample
                State candidateBest = null;
                float candidateEnergy = Float.MAX_VALUE;
                for (State s : randomStates) {
                    float energy = s.getEnergy();
                    if (candidateBest == null || energy < candidateEnergy) {
                        candidateEnergy = energy;
                        candidateBest = s;
                    }
                }

                // Classic hill climbing (no SA)
                State climbed = HillClimbGenerator.getHillClimbClassic(candidateBest, 100);
                float energy = climbed.getEnergy();
                if (bestState == null || energy < bestEnergy) {
                    bestEnergy = energy;
                    bestState = climbed.getCopy();
                }
            }

            model.addExternalShape(bestState.shape);
        }

        return new RunResult(model.getScore(), model.current.image);
    }

    // ===================== AFTER: All features ON =====================

    /**
     * Runs the generator with all features ON using Model.processStep() directly.
     * This uses:
     * - SA (via HillClimbGenerator.getHillClimb which dispatches to SA)
     * - Error-guided placement (errorMap is set in Model constructor)
     * - Adaptive size selection (gradientMap is set in Model constructor)
     * - Batch-parallel energy (via BorstCore.differencePartialThread)
     * - Progressive resolution via MultiResModel
     * - TSP optimization is a post-processing step on the sort order; it doesn't affect
     *   the energy score, so we report the generation quality here.
     */
    private static RunResult runAllFeaturesOn(BorstImage target, int maxShapes) {
        // Use progressive resolution (Proposal 6) wrapping the standard Model
        MultiResModel multiRes = new MultiResModel(target, BACKGROUND, ALPHA);

        for (int i = 0; i < maxShapes; i++) {
            multiRes.processStep(i, maxShapes);
        }

        Model fullModel = multiRes.getFullResModel();
        return new RunResult(fullModel.getScore(), fullModel.current.image);
    }

    /**
     * Runs the generator with proposals 1-5 ON but progressive resolution OFF.
     * Uses Model.processStep() directly (SA + error-guided + adaptive + batch-parallel).
     */
    private static RunResult runFeaturesOnNoProgressive(BorstImage target, int maxShapes) {
        Model model = new Model(target, BACKGROUND, ALPHA);
        for (int i = 0; i < maxShapes; i++) {
            model.processStep();
        }
        return new RunResult(model.getScore(), model.current.image);
    }

    // ===================== Utility =====================

    static class RunResult {
        final float score;
        final BufferedImage image;
        RunResult(float score, BufferedImage image) {
            this.score = score;
            this.image = image;
        }
    }

    static class ImageStats {
        final String name;
        final double[] beforeScores;
        final double[] afterScores;
        final double[] afterNoProgScores;
        final double beforeMean;
        final double beforeStd;
        final double afterMean;
        final double afterStd;
        final double afterNoProgMean;
        final double afterNoProgStd;
        final double improvementPct;
        final double improvementNoProgPct;
        final double tStatistic;
        final double pValue;
        final double tStatisticNoProg;
        final double pValueNoProg;
        BufferedImage bestBeforeImage;
        BufferedImage bestAfterImage;
        BufferedImage targetImage;

        ImageStats(String name, double[] beforeScores, double[] afterScores, double[] afterNoProgScores,
                   BufferedImage bestBefore, BufferedImage bestAfter, BufferedImage target) {
            this.name = name;
            this.beforeScores = beforeScores;
            this.afterScores = afterScores;
            this.afterNoProgScores = afterNoProgScores;
            this.bestBeforeImage = bestBefore;
            this.bestAfterImage = bestAfter;
            this.targetImage = target;

            this.beforeMean = mean(beforeScores);
            this.beforeStd = std(beforeScores);
            this.afterMean = mean(afterScores);
            this.afterStd = std(afterScores);
            this.afterNoProgMean = mean(afterNoProgScores);
            this.afterNoProgStd = std(afterNoProgScores);
            this.improvementPct = (beforeMean - afterMean) / beforeMean * 100.0;
            this.improvementNoProgPct = (beforeMean - afterNoProgMean) / beforeMean * 100.0;

            // Paired t-test: before vs after (with progressive)
            double[] diffs = new double[beforeScores.length];
            for (int i = 0; i < diffs.length; i++) diffs[i] = beforeScores[i] - afterScores[i];
            double diffMean = mean(diffs);
            double diffStd = std(diffs);
            int n = diffs.length;
            this.tStatistic = diffStd > 0 ? diffMean / (diffStd / Math.sqrt(n)) : (diffMean > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
            this.pValue = approximatePValue(this.tStatistic, n - 1);

            // Paired t-test: before vs after (without progressive)
            double[] diffs2 = new double[beforeScores.length];
            for (int i = 0; i < diffs2.length; i++) diffs2[i] = beforeScores[i] - afterNoProgScores[i];
            double diffMean2 = mean(diffs2);
            double diffStd2 = std(diffs2);
            this.tStatisticNoProg = diffStd2 > 0 ? diffMean2 / (diffStd2 / Math.sqrt(n)) : (diffMean2 > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
            this.pValueNoProg = approximatePValue(this.tStatisticNoProg, n - 1);
        }

        private static double mean(double[] vals) {
            double sum = 0;
            for (double v : vals) sum += v;
            return sum / vals.length;
        }

        private static double std(double[] vals) {
            double m = mean(vals);
            double sumSq = 0;
            for (double v : vals) sumSq += (v - m) * (v - m);
            return Math.sqrt(sumSq / (vals.length - 1));
        }

        private static double approximatePValue(double t, int df) {
            if (t <= 0) return 1.0;
            double x = t * (1 - 1.0 / (4 * df)) / Math.sqrt(1 + t * t / (2.0 * df));
            return 1.0 - normalCDF(x);
        }

        private static double normalCDF(double x) {
            if (x < -8) return 0;
            if (x > 8) return 1;
            double t = 1.0 / (1.0 + 0.2316419 * Math.abs(x));
            double d = 0.3989422804014327;
            double p = d * Math.exp(-x * x / 2.0) *
                (t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429)))));
            return x > 0 ? 1.0 - p : p;
        }
    }

    private static BufferedImage ensureArgb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_ARGB) return img;
        BufferedImage argb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return argb;
    }

    private static BufferedImage resizeTo(BufferedImage img, int w, int h) {
        BufferedImage resized = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return resized;
    }

    /**
     * Generate a diff heatmap. Green = after closer to target, Red = before closer.
     */
    private static BufferedImage generateDiffHeatmap(BufferedImage target, BufferedImage before, BufferedImage after) {
        int w = target.getWidth();
        int h = target.getHeight();
        BufferedImage heatmap = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int t = target.getRGB(x, y);
                int b = before.getRGB(x, y);
                int a = after.getRGB(x, y);

                int tr = (t >> 16) & 0xff, tg = (t >> 8) & 0xff, tb = t & 0xff;
                int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
                int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;

                int errBefore = (tr-br)*(tr-br) + (tg-bg)*(tg-bg) + (tb-bb)*(tb-bb);
                int errAfter  = (tr-ar)*(tr-ar) + (tg-ag)*(tg-ag) + (tb-ab)*(tb-ab);

                int diff = errBefore - errAfter;
                int intensity;
                int r, g2;
                if (diff > 0) {
                    intensity = Math.min(255, diff / 3);
                    r = 0; g2 = intensity;
                } else {
                    intensity = Math.min(255, -diff / 3);
                    r = intensity; g2 = 0;
                }
                heatmap.setRGB(x, y, 0xFF000000 | (r << 16) | (g2 << 8));
            }
        }
        return heatmap;
    }

    // ===================== Main entry point =====================

    public static void main(String[] args) throws Exception {
        File outDir = new File("test-results/final-comparison");
        outDir.mkdirs();

        // Programmatic test images
        String[] synthNames = {"photo_detail", "nature", "edges"};
        BufferedImage[] synthImages = {
            TestImageGenerator.createPhotoDetail(),
            TestImageGenerator.createNature(),
            TestImageGenerator.createEdges()
        };

        // Real photos
        File photoDir = new File("test-results/final-comparison/photos");
        photoDir.mkdirs();
        String[][] photoSources = {
            {"river", "/tmp/photo1.jpg"},
            {"portrait", "/tmp/photo2.jpg"},
            {"landscape", "/tmp/photo3.jpg"}
        };

        List<BufferedImage> realPhotos = new ArrayList<>();
        List<String> realNames = new ArrayList<>();
        for (String[] src : photoSources) {
            File photoFile = new File(src[1]);
            if (photoFile.exists()) {
                BufferedImage photo = ImageIO.read(photoFile);
                if (photo != null) {
                    photo = resizeTo(photo, 128, 128);
                    realPhotos.add(photo);
                    realNames.add(src[0]);
                    ImageIO.write(photo, "png", new File(photoDir, src[0] + ".png"));
                }
            }
        }

        // Combine all test images
        int totalImages = synthImages.length + realPhotos.size();
        String[] allNames = new String[totalImages];
        BufferedImage[] allImages = new BufferedImage[totalImages];
        for (int i = 0; i < synthImages.length; i++) {
            allNames[i] = synthNames[i];
            allImages[i] = synthImages[i];
        }
        for (int i = 0; i < realPhotos.size(); i++) {
            allNames[synthImages.length + i] = realNames.get(i);
            allImages[synthImages.length + i] = realPhotos.get(i);
        }

        System.out.println("=== Final Comparison: All 6 Proposals Combined ===");
        System.out.println("Shapes: " + MAX_SHAPES + ", Runs per config: " + NUM_RUNS);
        System.out.println("Test images: " + totalImages + " (" + synthImages.length + " synthetic + " + realPhotos.size() + " photos)");
        System.out.println();

        List<ImageStats> allStats = new ArrayList<>();

        for (int imgIdx = 0; imgIdx < totalImages; imgIdx++) {
            String name = allNames[imgIdx];
            BufferedImage rawImage = allImages[imgIdx];
            BufferedImage argbImage = ensureArgb(rawImage);

            System.out.println("--- " + name + " (" + argbImage.getWidth() + "x" + argbImage.getHeight() + ") ---");

            double[] beforeScores = new double[NUM_RUNS];
            double[] afterScores = new double[NUM_RUNS];
            double[] afterNoProgScores = new double[NUM_RUNS];
            BufferedImage bestBeforeImg = null;
            float bestBeforeScore = Float.MAX_VALUE;
            BufferedImage bestAfterImg = null;
            float bestAfterScore = Float.MAX_VALUE;

            for (int run = 0; run < NUM_RUNS; run++) {
                System.out.print("  Run " + (run + 1) + "/" + NUM_RUNS + ": ");

                // BEFORE: all features OFF
                long t0 = System.nanoTime();
                RunResult before = runAllFeaturesOff(new BorstImage(ensureArgb(rawImage)), MAX_SHAPES);
                long t1 = System.nanoTime();
                beforeScores[run] = before.score;
                if (before.score < bestBeforeScore) {
                    bestBeforeScore = before.score;
                    bestBeforeImg = before.image;
                }

                // AFTER with progressive resolution (all 6 proposals)
                long t2 = System.nanoTime();
                RunResult after = runAllFeaturesOn(new BorstImage(ensureArgb(rawImage)), MAX_SHAPES);
                long t3 = System.nanoTime();
                afterScores[run] = after.score;
                if (after.score < bestAfterScore) {
                    bestAfterScore = after.score;
                    bestAfterImg = after.image;
                }

                // AFTER without progressive resolution (proposals 1-5 only)
                long t4 = System.nanoTime();
                RunResult afterNoProg = runFeaturesOnNoProgressive(new BorstImage(ensureArgb(rawImage)), MAX_SHAPES);
                long t5 = System.nanoTime();
                afterNoProgScores[run] = afterNoProg.score;

                System.out.printf("OFF=%.6f (%.1fs)  ON=%.6f (%.1fs)  ON-noPR=%.6f (%.1fs)\n",
                    before.score, (t1-t0)/1e9, after.score, (t3-t2)/1e9,
                    afterNoProg.score, (t5-t4)/1e9);
            }

            ImageStats stats = new ImageStats(name, beforeScores, afterScores, afterNoProgScores,
                bestBeforeImg, bestAfterImg, argbImage);
            allStats.add(stats);

            // Save images
            ImageIO.write(argbImage, "png", new File(outDir, name + "_target.png"));
            ImageIO.write(bestBeforeImg, "png", new File(outDir, name + "_before.png"));
            ImageIO.write(bestAfterImg, "png", new File(outDir, name + "_after.png"));
            BufferedImage heatmap = generateDiffHeatmap(argbImage, bestBeforeImg, bestAfterImg);
            ImageIO.write(heatmap, "png", new File(outDir, name + "_diff.png"));

            System.out.printf("  BEFORE: %.6f +/- %.6f\n", stats.beforeMean, stats.beforeStd);
            System.out.printf("  AFTER (all 6):    %.6f +/- %.6f  (%.2f%%, t=%.3f, p=%.4f)\n",
                stats.afterMean, stats.afterStd, stats.improvementPct, stats.tStatistic, stats.pValue);
            System.out.printf("  AFTER (no prog):  %.6f +/- %.6f  (%.2f%%, t=%.3f, p=%.4f)\n",
                stats.afterNoProgMean, stats.afterNoProgStd, stats.improvementNoProgPct,
                stats.tStatisticNoProg, stats.pValueNoProg);
            System.out.println();
        }

        // Aggregate stats
        double totalBefore = 0, totalAfter = 0, totalAfterNoProg = 0;
        for (ImageStats s : allStats) {
            totalBefore += s.beforeMean;
            totalAfter += s.afterMean;
            totalAfterNoProg += s.afterNoProgMean;
        }
        double aggImprovement = (totalBefore - totalAfter) / totalBefore * 100.0;
        double aggImprovementNoProg = (totalBefore - totalAfterNoProg) / totalBefore * 100.0;
        System.out.printf("=== AGGREGATE ===\n");
        System.out.printf("  Before:          %.6f\n", totalBefore);
        System.out.printf("  After (all 6):   %.6f  (%.2f%%)\n", totalAfter, aggImprovement);
        System.out.printf("  After (no prog): %.6f  (%.2f%%)\n", totalAfterNoProg, aggImprovementNoProg);

        // Generate COMPARISON.md
        generateMarkdown(outDir, allStats, aggImprovement, aggImprovementNoProg);

        System.out.println("\nAll images and report saved to " + outDir.getAbsolutePath());
    }

    private static void generateMarkdown(File outDir, List<ImageStats> stats,
                                          double aggImprovement, double aggImprovementNoProg) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("# Final Comparison: All 6 Proposals Combined\n\n");
        md.append("This comparison shows the combined effect of enabling all 6 feature proposals:\n\n");
        md.append("1. **Simulated Annealing** (Proposal 1) - Escapes local minima during shape optimization\n");
        md.append("2. **Error-Guided Placement** (Proposal 2) - Biases circle placement toward high-error regions\n");
        md.append("3. **Adaptive Size Selection** (Proposal 3) - Small circles near edges, large in smooth areas\n");
        md.append("4. **Batch-Parallel Energy** (Proposal 4) - Combined color+energy pass with spatial batching\n");
        md.append("5. **TSP Optimization** (Proposal 5) - 2-opt local search to reduce drawing cost\n");
        md.append("6. **Progressive Resolution** (Proposal 6) - Multi-resolution generation pyramid\n\n");
        md.append("## Configuration\n\n");
        md.append("- **Shapes per image:** ").append(MAX_SHAPES).append("\n");
        md.append("- **Runs per configuration:** ").append(NUM_RUNS).append(" (for statistical significance)\n");
        md.append("- **Alpha:** ").append(ALPHA).append("\n");
        md.append("- **Background:** white (#FFFFFF)\n");
        md.append("- **Image size:** 128x128 px\n\n");

        // Energy scores table
        md.append("## Energy Scores (lower is better)\n\n");
        md.append("### All 6 Proposals (with Progressive Resolution)\n\n");
        md.append("| Image | Before (mean +/- std) | After (mean +/- std) | Improvement | t-stat | p-value | Sig? |\n");
        md.append("|-------|----------------------|---------------------|-------------|--------|---------|------|\n");
        for (ImageStats s : stats) {
            md.append(String.format("| %s | %.6f +/- %.6f | %.6f +/- %.6f | %.2f%% | %.3f | %.4f | %s |\n",
                s.name, s.beforeMean, s.beforeStd, s.afterMean, s.afterStd,
                s.improvementPct, s.tStatistic, s.pValue,
                s.pValue < 0.05 ? "Yes" : "No"));
        }
        md.append(String.format("\n**Aggregate improvement: %.2f%%**\n\n", aggImprovement));

        md.append("### Proposals 1-5 Only (without Progressive Resolution)\n\n");
        md.append("| Image | Before (mean +/- std) | After (mean +/- std) | Improvement | t-stat | p-value | Sig? |\n");
        md.append("|-------|----------------------|---------------------|-------------|--------|---------|------|\n");
        for (ImageStats s : stats) {
            md.append(String.format("| %s | %.6f +/- %.6f | %.6f +/- %.6f | %.2f%% | %.3f | %.4f | %s |\n",
                s.name, s.beforeMean, s.beforeStd, s.afterNoProgMean, s.afterNoProgStd,
                s.improvementNoProgPct, s.tStatisticNoProg, s.pValueNoProg,
                s.pValueNoProg < 0.05 ? "Yes" : "No"));
        }
        md.append(String.format("\n**Aggregate improvement: %.2f%%**\n\n", aggImprovementNoProg));

        // Statistical notes
        md.append("## Statistical Analysis\n\n");
        md.append("Each configuration was run ").append(NUM_RUNS).append(" times per image to account for stochastic variation.\n");
        md.append("A paired one-sided t-test was used to test whether the \"after\" configuration\n");
        md.append("produces significantly lower energy scores than the \"before\" configuration.\n\n");
        md.append("- **Null hypothesis (H0):** After scores >= Before scores (no improvement)\n");
        md.append("- **Alternative hypothesis (H1):** After scores < Before scores (improvement)\n");
        md.append("- **Significance level:** alpha = 0.05\n\n");

        int sig1 = 0, sig2 = 0;
        for (ImageStats s : stats) {
            if (s.pValue < 0.05) sig1++;
            if (s.pValueNoProg < 0.05) sig2++;
        }
        md.append(String.format("**All 6 proposals:** %d/%d images show statistically significant improvement.\n\n", sig1, stats.size()));
        md.append(String.format("**Proposals 1-5:** %d/%d images show statistically significant improvement.\n\n", sig2, stats.size()));

        md.append("### Notes on Progressive Resolution\n\n");
        md.append("Progressive resolution (Proposal 6) trades some quality for significantly faster generation time.\n");
        md.append("The first 10% of shapes are generated at quarter resolution and the next 30% at half resolution,\n");
        md.append("which means the overall energy score may be slightly higher than single-resolution with all\n");
        md.append("other optimizations. The speed benefit makes this worthwhile for interactive use.\n\n");

        // Visual comparison
        md.append("## Visual Comparison\n\n");
        for (ImageStats s : stats) {
            md.append("### ").append(s.name).append("\n\n");
            md.append("| Target | Before (all OFF) | After (all ON) | Diff Heatmap |\n");
            md.append("|--------|-----------------|----------------|-------------|\n");
            md.append(String.format("| ![target](%s_target.png) | ![before](%s_before.png) | ![after](%s_after.png) | ![diff](%s_diff.png) |\n\n",
                s.name, s.name, s.name, s.name));
        }

        md.append("## Diff Heatmap Legend\n\n");
        md.append("- **Green** = After is closer to target (improvement)\n");
        md.append("- **Red** = Before was closer to target (regression)\n");
        md.append("- **Black** = No significant difference\n\n");
        md.append("More green overall indicates the combined proposals produce better approximations.\n");

        File mdFile = new File(outDir, "COMPARISON.md");
        java.nio.file.Files.writeString(mdFile.toPath(), md.toString());
    }

    // JUnit test entry point
    @org.junit.jupiter.api.Test
    void runFinalComparison() throws Exception {
        main(new String[]{});
    }
}
