package com.bobrust.generator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import com.bobrust.generator.sorter.Blob;
import com.bobrust.util.data.AppConstants;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S1b invariants:
 * <ul>
 *   <li>the bbox-limited replay is EXACT — a drop's delta and the maintained
 *       composite/total equal a full from-scratch recompute of the kept set</li>
 *   <li>tolerance pruning never lets the true re-rendered score exceed the
 *       configured loss line</li>
 *   <li>budget selection keeps the budget exactly and drops verified-free
 *       (occluded) blobs before load-bearing ones</li>
 *   <li>kept blobs preserve their original (paint precedence) order</li>
 *   <li>pinned substrate blobs are never dropped but shape the decisions</li>
 * </ul>
 */
class BlobPrunerTest {
	private static final int BACKGROUND = TestBlobs.BACKGROUND;

	/**
	 * THE core exactness guarantee: evaluateDrop's bbox-limited replay delta
	 * equals the delta of a full re-render without that blob, and after
	 * committing drops the maintained composite + total equal a full
	 * recompute of the kept list.
	 */
	@Test
	void bboxReplayEqualsFullRecompute() {
		TestBlobs.Generated data = TestBlobs.generate(TestImageGenerator.createPhotoDetail(), 250);
		List<Blob> blobs = data.blobs();
		BorstImage target = data.target();

		BorstImage full = BlobPruner.render(blobs, target.width, target.height, BACKGROUND);
		long fullError = TestBlobs.uniformError(target, full);

		BlobPruner.Session session = new BlobPruner.Session(List.of(), blobs, target, BACKGROUND);
		assertEquals(fullError, session.total(), "session total must equal a full recompute");

		// Every 10th blob: replayed drop delta == full-recompute drop delta
		for (int i = 0; i < blobs.size(); i += 10) {
			List<Blob> without = new ArrayList<>(blobs);
			without.remove(i);
			long expected = TestBlobs.uniformError(target,
				BlobPruner.render(without, target.width, target.height, BACKGROUND)) - fullError;
			assertEquals(expected, session.evaluateDrop(i).delta(),
				"bbox replay delta for blob " + i + " must equal the full recompute");
		}

		// Commit a chain of drops; composite and total must track a full
		// recompute exactly (this is what makes sequential decisions sound).
		List<Blob> kept = new ArrayList<>(blobs);
		for (int i : new int[] { 240, 200, 120, 60, 3 }) {
			session.commitDrop(session.evaluateDrop(i));
			kept.remove(i);
		}
		BorstImage keptRender = BlobPruner.render(kept, target.width, target.height, BACKGROUND);
		assertArrayEquals(keptRender.pixels, session.compositePixels(),
			"maintained composite must be pixel-identical to a full re-render of the kept set");
		assertEquals(TestBlobs.uniformError(target, keptRender), session.total(),
			"maintained total must equal a full recompute of the kept set");
	}

	/** Tolerance mode: the true re-rendered score never exceeds the loss line. */
	@Test
	void tolerancePruningKeepsTrueScoreWithinTolerance() {
		for (var image : new java.awt.image.BufferedImage[] {
				TestImageGenerator.createPhotoDetail(), TestImageGenerator.createNature() }) {
			TestBlobs.Generated data = TestBlobs.generate(image, 300);
			double tolerance = 0.01;

			BlobPruner.Result result = BlobPruner.prune(data.blobs(), data.target(), BACKGROUND,
				new BlobPruner.Options(0, tolerance));

			assertTrue(result.prunedScore() <= result.originalScore() * (1 + tolerance) + 1e-12,
				"true pruned score " + result.prunedScore() + " must stay within " + tolerance
					+ " of " + result.originalScore());
			// The reported score IS a full recompute of the kept list
			assertEquals(BlobPruner.renderScore(result.kept(), data.target(), BACKGROUND),
				result.prunedScore(), 0.0);
			assertOrderPreserved(data.blobs(), result.kept());
		}
	}

	/** Budget mode: exactly the budget survives, in original order, verified score reported. */
	@Test
	void budgetSelectionHonorsBudgetExactly() {
		TestBlobs.Generated data = TestBlobs.generate(TestImageGenerator.createNature(), 300);
		int budget = 210; // 70%

		BlobPruner.Result result = BlobPruner.prune(data.blobs(), data.target(), BACKGROUND,
			new BlobPruner.Options(budget, -1));

		assertEquals(budget, result.kept().size(), "hard budget must be met exactly");
		assertEquals(300, result.originalCount());
		assertEquals(BlobPruner.renderScore(result.kept(), data.target(), BACKGROUND),
			result.prunedScore(), 0.0);
		assertOrderPreserved(data.blobs(), result.kept());

		// Greedy verified selection must beat naive prefix truncation (keeping
		// the first `budget` generated blobs), which is what the shape-count
		// slider alone would do.
		double prefixScore = BlobPruner.renderScore(data.blobs().subList(0, budget), data.target(), BACKGROUND);
		assertTrue(result.prunedScore() <= prefixScore,
			"budget selection (" + result.prunedScore() + ") must not lose to prefix truncation (" + prefixScore + ")");
	}

	/**
	 * Deterministic value-ranking check: a blob fully occluded by a later
	 * opaque stamp is provably worthless (its drop delta is exactly zero) —
	 * budget selection must drop it and keep the two load-bearing blobs.
	 */
	@Test
	void budgetDropsOccludedBlobBeforeLoadBearingOnes() {
		// A is fully covered by the later opaque B (same center, bigger stamp);
		// C stands alone. Target = render([B, C]) so B and C are load-bearing
		// (dropping either raises the error) and A contributes exactly nothing.
		Blob a = Blob.of(32, 32, BorstUtils.SIZES[3], 0x000000, 255, AppConstants.CIRCLE_SHAPE);
		Blob b = Blob.of(32, 32, BorstUtils.SIZES[4], 0xff3334, 255, AppConstants.CIRCLE_SHAPE);
		Blob c = Blob.of(12, 52, BorstUtils.SIZES[2], 0x000000, 255, AppConstants.CIRCLE_SHAPE);
		List<Blob> blobs = List.of(a, b, c);

		BorstImage target = BlobPruner.render(List.of(b, c), 64, 64, BACKGROUND);

		BlobPruner.Result budget = BlobPruner.prune(blobs, target, BACKGROUND, new BlobPruner.Options(2, -1));
		assertEquals(List.of(b, c), budget.kept(), "the occluded blob must be the one dropped");
		assertEquals(budget.originalScore(), budget.prunedScore(), 0.0,
			"dropping a fully occluded blob must not change the true score");

		// Zero tolerance drops free blobs only: A goes, B and C stay.
		BlobPruner.Result free = BlobPruner.prune(blobs, target, BACKGROUND, new BlobPruner.Options(0, 0.0));
		assertEquals(List.of(b, c), free.kept(), "zero tolerance must still harvest occluded blobs");
	}

	/** A stamp entirely off the canvas is free to drop and must not crash the replay. */
	@Test
	void offCanvasBlobIsDroppedForFree() {
		Blob visible = Blob.of(20, 20, BorstUtils.SIZES[2], 0x000000, 255, AppConstants.CIRCLE_SHAPE);
		Blob offCanvas = Blob.of(-500, -500, BorstUtils.SIZES[5], 0xff3334, 255, AppConstants.CIRCLE_SHAPE);
		List<Blob> blobs = List.of(visible, offCanvas);
		BorstImage target = BlobPruner.render(List.of(visible), 40, 40, BACKGROUND);

		BlobPruner.Result result = BlobPruner.prune(blobs, target, BACKGROUND, new BlobPruner.Options(0, 0.0));
		assertEquals(List.of(visible), result.kept());
		assertEquals(result.originalScore(), result.prunedScore(), 0.0);
	}

	/** Pinned substrate blobs shape the composite but can never be dropped. */
	@Test
	void pinnedSubstrateIsNeverDroppedButMakesCandidatesRedundant() {
		// The pinned big stamp already matches the target; the candidate paints
		// the same color inside it, contributing exactly nothing.
		Blob pinned = Blob.of(32, 32, BorstUtils.SIZES[4], 0xff3334, 255, AppConstants.CIRCLE_SHAPE);
		Blob candidate = Blob.of(32, 32, BorstUtils.SIZES[2], 0xff3334, 255, AppConstants.CIRCLE_SHAPE);
		BorstImage target = BlobPruner.render(List.of(pinned), 64, 64, BACKGROUND);

		BlobPruner.Result result = BlobPruner.prune(List.of(pinned), List.of(candidate), target, BACKGROUND,
			new BlobPruner.Options(0, 0.0));
		assertTrue(result.kept().isEmpty(), "the redundant candidate must be dropped");
		assertEquals(result.originalScore(), result.prunedScore(), 0.0);
	}

	/** Disabled options are a true no-op: all candidates kept, order untouched. */
	@Test
	void disabledOptionsKeepEverything() {
		TestBlobs.Generated data = TestBlobs.generate(TestImageGenerator.createEdges(), 60);
		BlobPruner.Result result = BlobPruner.prune(data.blobs(), data.target(), BACKGROUND, BlobPruner.Options.NONE);
		assertEquals(data.blobs(), result.kept());
		assertEquals(result.originalScore(), result.prunedScore(), 0.0);
	}

	@Test
	void optionsParseAndSerializeRoundTrip() {
		assertEquals(BlobPruner.Options.NONE, BlobPruner.Options.parse(null));
		assertEquals(BlobPruner.Options.NONE, BlobPruner.Options.parse(""));
		assertEquals(BlobPruner.Options.NONE, BlobPruner.Options.parse("garbage"));
		assertFalse(BlobPruner.Options.NONE.enabled());

		BlobPruner.Options options = BlobPruner.Options.parse("budget=1500;maxLoss=0.01");
		assertEquals(1500, options.budget());
		assertEquals(0.01, options.maxScoreLoss(), 0.0);
		assertTrue(options.enabled());
		assertEquals(options, BlobPruner.Options.parse(options.serialize()));

		assertTrue(BlobPruner.Options.parse("budget=100").enabled());
		assertTrue(BlobPruner.Options.parse("maxLoss=0.0").enabled());
		assertFalse(BlobPruner.Options.parse("budget=0;maxLoss=-1").enabled());
	}

	/** S1a: recorded marginal contributions are exposed per blob and sum to the total improvement. */
	@Test
	void contributionsAreRecordedPerShape() {
		BorstImage target = new BorstImage(TestBlobs.ensureArgb(TestImageGenerator.createPhotoDetail()));
		Model model = new Model(target, BACKGROUND, TestBlobs.ALPHA, GeneratorConfig.DEFAULT);
		long initialError = model.getTotalError();
		for (int i = 0; i < 40; i++) {
			model.processStep();
		}

		List<Long> contributions = model.getShapeContributions();
		assertEquals(model.shapes.size(), contributions.size(), "one contribution per committed shape");

		long sum = 0;
		for (long contribution : contributions) {
			sum += contribution;
		}
		assertEquals(initialError - model.getTotalError(), sum,
			"contributions must sum exactly to the total error reduction");
	}

	private static void assertOrderPreserved(List<Blob> original, List<Blob> kept) {
		int cursor = 0;
		for (Blob blob : kept) {
			int found = -1;
			for (int i = cursor; i < original.size(); i++) {
				if (original.get(i) == blob) {
					found = i;
					break;
				}
			}
			assertTrue(found >= 0, "kept blobs must be a subsequence of the original order");
			cursor = found + 1;
		}
	}
}
