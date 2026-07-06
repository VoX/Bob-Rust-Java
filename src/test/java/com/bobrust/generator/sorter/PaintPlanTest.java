package com.bobrust.generator.sorter;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import com.bobrust.generator.BlobPruner;
import com.bobrust.generator.TestBlobs;
import com.bobrust.generator.TestImageGenerator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S1c invariants:
 * <ul>
 *   <li>UNPRUNED PARITY: the plan produces the exact instruction sequence and
 *       paint segments of the legacy {@code previouslyUsed}/{@code drawnShapes}
 *       bookkeeping it replaces (DrawDialog's old prefix logic, simulated
 *       verbatim here) — including chunked extension and slider moves</li>
 *   <li>resume: painting always continues from the frozen resume cursor;
 *       extension never disturbs already-painted instructions</li>
 *   <li>pruned plans stay resumable: the instruction list is the unit of
 *       truth, not the generated index</li>
 * </ul>
 */
class PaintPlanTest {
	private static final int BACKGROUND = TestBlobs.BACKGROUND;

	/**
	 * Legacy algorithm, verbatim from the old DrawDialog.startDrawingAction:
	 * append sort(generated[previouslyComputed, count)) when extending, paint
	 * previouslyUsed[drawnShapes, count).
	 */
	private static void legacyExtend(BlobList previouslyUsed, List<Blob> generated, int drawnShapes, int count) {
		int previouslyComputed = previouslyUsed.size();
		if (previouslyComputed <= drawnShapes || previouslyComputed <= count) {
			int missing = count - previouslyComputed;
			int offset = count - missing;
			int len = Math.max(0, Math.min(generated.size(), offset + missing) - offset);
			BlobList chunk = new BlobList();
			chunk.assign(generated, offset, len);
			previouslyUsed.getList().addAll(BorstSorter.sort(chunk).getList());
		}
	}

	private static List<Blob> legacyPaintList(BlobList previouslyUsed, int drawnShapes, int count) {
		BlobList list = new BlobList();
		list.assign(previouslyUsed.getList(), drawnShapes, count - drawnShapes);
		return list.getList();
	}

	/**
	 * Full parity walk: three draw sessions with interrupts and slider moves.
	 * Instruction list and every paint segment must be identical (same Blob
	 * instances, same order) to the legacy bookkeeping.
	 */
	@Test
	void unprunedPlanMatchesLegacyBookkeeping() {
		List<Blob> generated = TestBlobs.generate(TestImageGenerator.createNature(), 300).blobs();

		BlobList legacy = new BlobList();
		PaintPlan plan = new PaintPlan();
		int drawnShapes = 0;

		// Session 1: slider at 120, interrupted after 45 painted
		// Session 2: slider raised to 250, interrupted after 130 more
		// Session 3: slider LOWERED to 220 (below built), painted to the end
		int[][] sessions = { { 120, 45 }, { 250, 130 }, { 220, 45 } };
		for (int[] session : sessions) {
			int count = session[0];
			int painted = session[1];

			legacyExtend(legacy, generated, drawnShapes, count);
			plan.extend(generated, count, BlobPruner.Options.NONE, null, BACKGROUND);

			List<Blob> expected = legacyPaintList(legacy, drawnShapes, count);
			List<Blob> actual = plan.paintList(count).getList();
			assertEquals(expected, actual, "paint segment must match legacy for count=" + count);

			drawnShapes += painted;
			plan.advancePainted(painted);
			assertEquals(drawnShapes, plan.getPainted());
		}

		assertEquals(legacy.getList(), plan.getInstructions(),
			"full instruction list must be byte-identical to the legacy previouslyUsed list");
	}

	/** Resume: the segment after an interrupt starts exactly at the resume cursor. */
	@Test
	void paintListResumesAtCursor() {
		List<Blob> generated = TestBlobs.generate(TestImageGenerator.createEdges(), 200).blobs();

		PaintPlan plan = new PaintPlan();
		plan.extend(generated, 200, BlobPruner.Options.NONE, null, BACKGROUND);
		List<Blob> all = List.copyOf(plan.getInstructions());
		assertEquals(200, all.size());

		assertEquals(all, plan.paintList(200).getList(), "first pass paints everything");

		plan.advancePainted(75);
		assertEquals(all.subList(75, 200), plan.paintList(200).getList(),
			"resume must continue at instruction 75");

		// Painted instructions are frozen: extension appends only
		plan.extend(generated, 200, BlobPruner.Options.NONE, null, BACKGROUND);
		assertEquals(all, plan.getInstructions(), "re-extending to a covered count is a no-op");

		plan.advancePainted(125);
		assertTrue(plan.paintList(200).getList().isEmpty(), "fully painted plan has nothing left");
		assertEquals(200, plan.getPainted());
		plan.advancePainted(50);
		assertEquals(200, plan.getPainted(), "cursor never runs past the plan");
	}

	/**
	 * Pruned plans: the budget shrinks the instruction list, resume indexes
	 * into the KEPT instructions, and a later extension chunk is pruned
	 * against the already-planned substrate without touching the painted
	 * prefix.
	 */
	@Test
	void prunedPlanStaysResumable() {
		TestBlobs.Generated data = TestBlobs.generate(TestImageGenerator.createNature(), 300);
		List<Blob> generated = data.blobs();
		BlobPruner.Options options = new BlobPruner.Options(0, 0.02);

		PaintPlan plan = new PaintPlan();
		plan.extend(generated, 200, options, data.target(), BACKGROUND);
		int planned = plan.size();
		assertTrue(planned <= 200, "pruning can only shrink a chunk");
		assertNotNull(plan.getLastPruneResult());

		// planIndexFor inside a pruned chunk maps to the chunk end
		if (planned < 200) {
			assertEquals(planned, plan.planIndexFor(150));
		}

		// Interrupt mid-paint, then extend with the remaining generated blobs
		plan.advancePainted(40);
		List<Blob> prefix = List.copyOf(plan.getInstructions().subList(0, 40));
		plan.extend(generated, 300, options, data.target(), BACKGROUND);

		assertEquals(prefix, plan.getInstructions().subList(0, 40),
			"extension must never disturb painted instructions");
		assertEquals(300, plan.coveredGenerated());

		List<Blob> remaining = plan.paintList(300).getList();
		assertEquals(plan.getInstructions().subList(40, plan.size()), remaining,
			"resume paints exactly the un-painted instruction suffix");

		// Every planned instruction is a generated blob, each at most once
		List<Blob> pool = new ArrayList<>(generated);
		for (Blob blob : plan.getInstructions()) {
			assertTrue(pool.removeIf(candidate -> candidate == blob),
				"plan must only contain generated blobs, each at most once");
		}
	}

	/** Hard budget: the plan's instruction count equals the budget. */
	@Test
	void budgetLimitsPlanSize() {
		TestBlobs.Generated data = TestBlobs.generate(TestImageGenerator.createPhotoDetail(), 250);
		PaintPlan plan = new PaintPlan();
		plan.extend(data.blobs(), 250, new BlobPruner.Options(150, -1), data.target(), BACKGROUND);
		assertEquals(150, plan.size(), "the instruction list is capped at the budget");
		assertEquals(250, plan.coveredGenerated(), "the slider still covers all generated shapes");
	}

	@Test
	void resetClearsEverything() {
		List<Blob> generated = TestBlobs.generate(TestImageGenerator.createSolid(), 50).blobs();
		PaintPlan plan = new PaintPlan();
		plan.extend(generated, 50, BlobPruner.Options.NONE, null, BACKGROUND);
		plan.advancePainted(20);
		plan.reset();
		assertEquals(0, plan.size());
		assertEquals(0, plan.getPainted());
		assertEquals(0, plan.coveredGenerated());
		assertTrue(plan.paintList(50).getList().isEmpty());
	}
}
