package com.bobrust.generator.sorter;

import java.util.ArrayList;
import java.util.List;

import com.bobrust.generator.BlobPruner;
import com.bobrust.generator.BorstImage;

/**
 * S1c: the explicit paint instruction list — the ordered, sorted, possibly
 * pruned blobs that will actually be painted — plus the resume cursor into it.
 *
 * <p>This replaces DrawDialog's old {@code previouslyUsed}/{@code drawnShapes}
 * bookkeeping (the {@code // TODO: 'previouslyUsed' should start at
 * 'drawnShapes'}), which assumed the painted set is a pure prefix of the
 * generated list. Budget selection and pruning break that assumption; here the
 * plan itself is the unit of truth: painting always consumes instructions from
 * {@link #getPainted()} forward, and instructions already added are FROZEN —
 * extension only ever appends, so resume-after-interrupt indexes stay valid no
 * matter how a chunk was pruned.
 *
 * <p>Legacy parity: with pruning disabled, {@link #extend} appends exactly
 * {@code BorstSorter.sort(generated[covered, count))} — byte-for-byte the old
 * behavior including its chunked sorting — and {@link #planIndexFor} is the
 * identity, so the painted sequence is identical to the old code path.
 *
 * <p>With pruning enabled, each new chunk is pruned by {@link BlobPruner}
 * against the substrate of everything already planned (already-planned stamps
 * are pinned: they may already be paint on the sign), then sorted and
 * appended. The shape-count slider keeps working as the fine control: it still
 * selects how many generated shapes the plan covers; the budget/tolerance then
 * decides how many of those are worth painting.
 */
public class PaintPlan {
	/**
	 * One extension chunk: generated blobs {@code [genStart, genEnd)} became
	 * plan instructions {@code [planStart, planEnd)}. {@code pruned} marks
	 * chunks where the 1:1 generated-to-instruction mapping no longer holds.
	 */
	private record Chunk(int genStart, int genEnd, int planStart, int planEnd, boolean pruned) {
	}

	private final List<Blob> instructions = new ArrayList<>();
	private final List<Chunk> chunks = new ArrayList<>();
	private int covered; // generated blobs consumed into the plan
	private int painted; // instructions successfully painted (resume cursor)
	private BlobPruner.Result lastPruneResult;

	public void reset() {
		instructions.clear();
		chunks.clear();
		covered = 0;
		painted = 0;
		lastPruneResult = null;
	}

	/**
	 * Extend the plan so it covers the first {@code generatedCount} generated
	 * blobs (no-op if already covered). Call with the generator's data lock
	 * held, as {@code generated} is the live blob list.
	 *
	 * @param target the scaled target image; only consulted when
	 *               {@code options} enables pruning (may be null otherwise)
	 */
	public void extend(List<Blob> generated, int generatedCount, BlobPruner.Options options, BorstImage target, int background) {
		int end = Math.min(generatedCount, generated.size());
		if (end <= covered) {
			return;
		}

		List<Blob> chunk = List.copyOf(generated.subList(covered, end));
		List<Blob> kept = chunk;
		boolean pruned = false;
		if (options != null && options.enabled() && target != null) {
			BlobPruner.Result result = BlobPruner.prune(List.copyOf(instructions), chunk, target, background, options);
			lastPruneResult = result;
			kept = result.kept();
			pruned = kept.size() < chunk.size();
		}

		BlobList sorted = BorstSorter.sort(new BlobList(kept));
		int planStart = instructions.size();
		instructions.addAll(sorted.getList());
		chunks.add(new Chunk(covered, end, planStart, instructions.size(), pruned));
		covered = end;
	}

	/**
	 * The plan index covering {@code generatedCount} generated blobs. Inside
	 * an unpruned chunk this is the identity mapping (legacy slider
	 * semantics); a pruned chunk has no per-shape mapping, so any count inside
	 * it maps to the chunk's end.
	 */
	public int planIndexFor(int generatedCount) {
		int index = 0;
		for (Chunk chunk : chunks) {
			if (generatedCount >= chunk.genEnd()) {
				index = chunk.planEnd();
				continue;
			}
			if (generatedCount > chunk.genStart()) {
				index = chunk.pruned()
					? chunk.planEnd()
					: chunk.planStart() + (generatedCount - chunk.genStart());
			}
			break;
		}
		return index;
	}

	/**
	 * The instructions to paint now: from the resume cursor up to the plan
	 * index covering {@code generatedCount}.
	 */
	public BlobList paintList(int generatedCount) {
		int end = Math.max(painted, planIndexFor(generatedCount));
		BlobList list = new BlobList();
		list.assign(instructions, painted, end - painted);
		return list;
	}

	/** Record {@code count} more instructions as successfully painted. */
	public void advancePainted(int count) {
		painted = Math.min(instructions.size(), painted + Math.max(0, count));
	}

	/** Number of generated blobs the plan covers. */
	public int coveredGenerated() {
		return covered;
	}

	/** Total instructions in the plan. */
	public int size() {
		return instructions.size();
	}

	/** The resume cursor: instructions already painted. */
	public int getPainted() {
		return painted;
	}

	/** The full instruction list (live, read-only use). */
	public List<Blob> getInstructions() {
		return instructions;
	}

	/** The most recent chunk's pruning result, or null if never pruned. */
	public BlobPruner.Result getLastPruneResult() {
		return lastPruneResult;
	}
}
