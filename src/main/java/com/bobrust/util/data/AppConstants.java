package com.bobrust.util.data;

import java.awt.Color;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.List;

import com.bobrust.util.ResourceUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public interface AppConstants {
	Logger LOGGER = LogManager.getLogger(AppConstants.class);
	
	// Used to debug
	boolean IS_IDE = !AppConstants.class.getProtectionDomain()
		.getCodeSource()
		.getLocation()
		.getPath().endsWith(".jar");

	// Used by BorstGenerator
	boolean DEBUG_AUTO_IMAGE = true;
	boolean DEBUG_GENERATOR = false;
	boolean DEBUG_DRAWN_COLORS = false;
	boolean DEBUG_TIME = false;
	int MAX_SORT_GROUP = 1000; // Max 1000 elements per sort

	// NOTE: The four USE_* generator flags below are no longer read directly by
	// the generator — they are the DEFAULT values of the runtime GeneratorConfig
	// (com.bobrust.generator.GeneratorConfig), which is what Model/Worker/
	// HillClimbGenerator actually consult. Override at runtime through the
	// SettingsGeneratorConfig property or the benchmark harness.

	// When true, use simulated annealing instead of pure hill climbing for shape
	// optimization. Default false: SA measured +25% refine cost at equal-or-worse
	// quality on the F1 corpus (PERFORMANCE-PLAN.md item 3). The SA path stays
	// reachable via GeneratorConfig ("sa=true").
	boolean USE_SIMULATED_ANNEALING = false;

	// When true, bias random circle placement toward high-error regions using importance sampling
	boolean USE_ERROR_GUIDED_PLACEMENT = true;

	// When true, use local gradient magnitude to bias circle size selection:
	// small circles near edges/detail, large circles in smooth areas
	boolean USE_ADAPTIVE_SIZE = true;

	// When true, use the combined single-pass color+energy evaluation in
	// BorstCore.differencePartialThread (verified byte-identical to the classic
	// two-pass implementation by BatchParallelEnergyTest)
	boolean USE_BATCH_PARALLEL = true;

	// When true, rank the per-step random candidates with a strided (subsampled)
	// energy kernel and re-evaluate only the winner exactly. Ranking is the
	// measured 61-71% of step time and only needs an ordering, not exact values;
	// the refine and commit paths always use the exact kernels, so the committed
	// geometry, color and running score are unaffected (ProxyRankingTest).
	boolean USE_PROXY_RANKING = true;

	// Q1: when true, the generator objective and the palette snap both use the
	// channel-weighted perceptual RGB metric (BorstUtils.PERCEPTUAL_WEIGHT_*)
	// instead of uniform RGB. Snap and energy share ONE metric by construction
	// (PerceptualColorTest) — never enable one side without the other.
	// F1-measured on top of per-shape alpha (300/800 shapes): corpus dE00
	// -3.5%/-1.2%, SSIM +1.6%/+1.2% (photo_detail SSIM +12%/+5%), at ~1.3-1.4x
	// generation wall — same trade Phase G accepted when it kept age=100.
	boolean USE_PERCEPTUAL_COLOR = true;

	// Q2: when true, each candidate shape searches its own alpha in
	// [MIN_ALPHA_INDEX, 5] instead of inheriting the single global alpha
	// setting. The downstream pipeline (Blob, sorter, painter alpha clicks)
	// always supported per-shape alpha; this unlocks the generator side.
	// F1-measured vs the single-alpha default: corpus dE00 -28%/-35% at
	// 300/800 shapes (edges: dE00 7.40 -> 0.62 at 800), SSIM up on every hard
	// image, AND ~25% faster (opaque stamps converge better). NOTE: the
	// PAINTED-sign gain is partly hostage to P9 calibration — low alpha is
	// where the uncalibrated blend model is least trustworthy; the sim-space
	// numbers above are the honest claim until P9 lands.
	boolean USE_PER_SHAPE_ALPHA = true;

	// Floor for the per-shape alpha search (index into BorstUtils.ALPHAS).
	// Very low alphas need many layered stamps to move a pixel, ballooning
	// shape counts (= paint time), and are where the uncalibrated blend model
	// is least trustworthy until P9 lands. F1-measured: floor 1 (alpha 48)
	// beats floor 2 by -8%/-16% corpus dE00 at 300/800 shapes and fixes the
	// smooth-image regressions; floor 0 (alpha 23) gains another ~3% on
	// trivial synthetics but regresses edges +11% and lives in the least
	// calibrated blend regime — kept config-reachable, not default.
	int MIN_ALPHA_INDEX = 1;

	// DISABLED: 2-opt reorders blobs on palette+travel cost with no awareness of
	// the sorter's overlap-precedence invariant (a blob may only be painted after
	// every earlier-generated blob it overlaps). Reversing a segment can swap two
	// overlapping blobs, so the robot composites them in the wrong order and the
	// painted sign no longer matches the preview. The travel distance it optimizes
	// is also free — Robot.mouseMove teleports the cursor. Do not re-enable unless
	// TwoOptOptimizer is made precedence-aware (only accept reversals whose segment
	// contains no ordered overlap pair).
	boolean USE_TSP_OPTIMIZATION = false;

	// TSP cost function weights
	float TSP_W_PALETTE = 3.0f;   // Weight for palette change cost
	float TSP_W_DISTANCE = 1.0f;  // Weight for Euclidean distance cost

	// DISABLED: MultiResModel was never wired into BorstGenerator (nothing in
	// src/main reads this flag or constructs MultiResModel), so the feature does
	// not exist in the app. MultiResModel also has an up-scaling bug — see the
	// TODO in MultiResModel.scaleCircle — that must be fixed before wiring it in.
	boolean USE_PROGRESSIVE_RESOLUTION = false;
	
	// Average canvas colors. Used as default colors
	Color CANVAS_AVERAGE = new Color(0xb3aba0);
	Color WOODEN_AVERAGE = new Color(0xa89383);
	Color TOWN_POST_AVERAGE = new Color(0x94624d);
	Color HANGING_METAL_AVERAGE = new Color(0x534c46);
	
	// Shapes
	int CIRCLE_SHAPE = 1;
	int SQUARE_SHAPE = 3;
	
	// Used by JStyledButton, JStyledToggleButton
	int BUTTON_BORDER_RADIUS = 20;
	Color BUTTON_DEFAULT_COLOR = new Color(242, 242, 242);
	Color BUTTON_HOVER_COLOR = new Color(229, 243, 255);
	Color BUTTON_DISABLED_COLOR = new Color(192, 192, 192);
	Color BUTTON_SELECTED_COLOR = new Color(204, 232, 255);
	
	BufferedImage COLOR_PALETTE = ResourceUtil.loadImageFromResources("/mapping/palette_2.png", LOGGER);
	Image DIALOG_ICON = ResourceUtil.loadMultiIconImageFromResources(
		"/icons/",
		List.of("16.png", "32.png", "64.png", "128.png"),
		LOGGER);
	String VERSION = ResourceUtil.readTextFromResources("/version", LOGGER);
}
