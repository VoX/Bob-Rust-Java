package com.bobrust.generator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import com.bobrust.generator.sorter.Blob;

/**
 * S1b: dead-blob pruning and hard-budget selection over a generated blob list.
 * Paint time is linear in the blob count (the robot clicks every blob), so
 * dropping low-value blobs is the single biggest real-world paint-time lever.
 *
 * <p><b>The substrate correction (from the validation panel):</b> a blob's
 * recorded marginal contribution is measured against the composite it was
 * committed over. Dropping a blob changes the substrate every LATER
 * overlapping blob blended against, so per-blob marginals are a ranking
 * heuristic only. Every drop this class commits is therefore verified by
 * re-rendering the candidate kept-set with the exact paint kernel and
 * measuring the TRUE resulting error — a drop is only accepted if the true
 * score stays within tolerance (or budget pressure forces it, cheapest first).
 *
 * <p><b>Exact bbox-limited replay:</b> compositing is per-pixel independent —
 * the final color of a pixel depends only on the ordered stamps that cover
 * it. Dropping blob {@code i} can therefore only change pixels inside blob
 * {@code i}'s stamp bounding box, so the true delta is computed by replaying
 * all surviving stamps that intersect that box, in original order, into a
 * box-sized buffer. The replay reuses {@link BorstCore#drawLines} — the exact
 * committed-render kernel, NOT ShapeRender's antialiased preview rasterizer —
 * by translating stamp offsets into box coordinates; drawLines' own bounds
 * clipping then restricts the stamp to the box exactly like canvas clipping
 * restricts it to the sign. This replay engine is the same one P12 (refit)
 * and P16 (plan round-trip) need later.
 *
 * <p>Scores here are the paint-space simulation: palette-quantized color,
 * size and alpha (what the robot will actually click), scored with the
 * uniform RGBA metric of {@code ImageMetrics.rmse} so results are directly
 * comparable to the F1 benchmark columns.
 */
public final class BlobPruner {
	private BlobPruner() {
	}

	/**
	 * Opt-in pruning parameters. {@link #NONE} (the default) disables pruning
	 * entirely — the paint pipeline behaves exactly as before.
	 *
	 * @param budget        hard cap on kept candidate blobs; {@code <= 0} means
	 *                      no absolute budget. Cheapest true-delta blobs are
	 *                      dropped first until the cap is met.
	 * @param budgetPercent relative hard cap: keep at most this percent of the
	 *                      candidate chunk ({@code <= 0} = none; ignored when
	 *                      an absolute {@code budget} is set). S3 presets use
	 *                      this form so one preset scales across sign sizes
	 *                      and shape counts; serialized as {@code budget=70%}.
	 * @param maxScoreLoss  maximum allowed relative increase of the true
	 *                      re-rendered RMSE over the unpruned render (e.g.
	 *                      {@code 0.01} = 1%); negative means no tolerance
	 *                      pass. {@code 0.0} still drops free blobs (fully
	 *                      occluded / off-canvas / score-improving).
	 */
	public record Options(int budget, int budgetPercent, double maxScoreLoss) {
		public static final Options NONE = new Options(0, 0, -1.0);

		public Options {
			if (budgetPercent > 100) {
				budgetPercent = 100;
			}
		}

		/** Absolute-budget form (the pre-S3 constructor). */
		public Options(int budget, double maxScoreLoss) {
			this(budget, 0, maxScoreLoss);
		}

		public boolean enabled() {
			return budget > 0 || budgetPercent > 0 || maxScoreLoss >= 0;
		}

		/**
		 * The hard cap for a chunk of {@code candidateCount} blobs: the
		 * absolute budget if set, else the percent of the chunk, else 0
		 * (no cap).
		 */
		public int effectiveBudget(int candidateCount) {
			if (budget > 0) {
				return budget;
			}
			if (budgetPercent > 0) {
				return Math.max(1, candidateCount * budgetPercent / 100);
			}
			return 0;
		}

		public String serialize() {
			String budgetText = budgetPercent > 0 && budget <= 0
				? budgetPercent + "%"
				: Integer.toString(budget);
			return "budget=" + budgetText + ";maxLoss=" + maxScoreLoss;
		}

		/**
		 * Parse {@code "budget=1500;maxLoss=0.01"} or the percent form
		 * {@code "budget=70%;maxLoss=0.03"} (any subset of keys).
		 * Null/blank/malformed input yields {@link #NONE} semantics per key.
		 */
		public static Options parse(String text) {
			if (text == null || text.isBlank()) {
				return NONE;
			}
			int budget = NONE.budget();
			int budgetPercent = NONE.budgetPercent();
			double maxLoss = NONE.maxScoreLoss();
			for (String entry : text.split(";")) {
				int split = entry.indexOf('=');
				if (split < 0) {
					continue;
				}
				String key = entry.substring(0, split).trim();
				String value = entry.substring(split + 1).trim();
				try {
					switch (key) {
						case "budget" -> {
							if (value.endsWith("%")) {
								budgetPercent = Integer.parseInt(value.substring(0, value.length() - 1).trim());
							} else {
								budget = Integer.parseInt(value);
							}
						}
						case "maxLoss" -> maxLoss = Double.parseDouble(value);
					}
				} catch (NumberFormatException ignored) {
					// Malformed value — keep the disabled default for this key
				}
			}
			return new Options(budget, budgetPercent, maxLoss);
		}
	}

	/**
	 * @param kept          surviving candidate blobs, in their original order
	 * @param originalCount candidate count before pruning
	 * @param originalScore true uniform RMSE of the unpruned render (pinned +
	 *                      all candidates) against the target
	 * @param prunedScore   true uniform RMSE of the pruned render, verified by
	 *                      a full recompute from the kept list
	 */
	public record Result(List<Blob> kept, int originalCount, double originalScore, double prunedScore) {
		public int droppedCount() {
			return originalCount - kept.size();
		}
	}

	/** Prune {@code candidates} with no pinned substrate. */
	public static Result prune(List<Blob> candidates, BorstImage target, int background, Options options) {
		return prune(List.of(), candidates, target, background, options);
	}

	/**
	 * Prune {@code candidates} against a {@code pinned} substrate — blobs that
	 * are already painted or already planned and can never be dropped, but
	 * whose stamps are part of the composite the candidates blend over.
	 * {@code pinned} and {@code candidates} must each be in paint order, with
	 * every pinned stamp preceding every candidate stamp.
	 */
	public static Result prune(List<Blob> pinned, List<Blob> candidates, BorstImage target, int background, Options options) {
		final int budget = options.effectiveBudget(candidates.size());
		if (!options.enabled() || candidates.isEmpty()
				|| (budget <= 0 || budget >= candidates.size()) && options.maxScoreLoss() < 0) {
			double score = renderScore(concat(pinned, candidates), target, background);
			return new Result(List.copyOf(candidates), candidates.size(), score, score);
		}

		Session session = new Session(pinned, candidates, target, background);
		final long originalTotal = session.total();
		final double originalScore = rmse(originalTotal, target.width, target.height);
		// rmse is monotone in the total, so the tolerance line translates to
		// total <= originalTotal * (1 + loss)^2 — exact long comparisons, no
		// per-drop float drift.
		final double limitTotal = options.maxScoreLoss() >= 0
			? originalTotal * (1.0 + options.maxScoreLoss()) * (1.0 + options.maxScoreLoss())
			: -1;

		// Lazy greedy over verified true deltas. Heap keys are exact at the
		// version they were computed; a key only goes stale when a committed
		// drop's box intersects the candidate's box (per-pixel independence
		// again), in which case the candidate is re-evaluated and re-queued.
		// Every COMMITTED drop uses a delta that is exact for the current
		// composite; the greedy order itself is the only heuristic part.
		// Heap indices are in the session's combined (pinned + candidates)
		// index space; only candidate indices are ever queued.
		final int candidateBase = pinned.size();
		PriorityQueue<long[]> heap = new PriorityQueue<>(
			Comparator.<long[]>comparingLong(e -> e[0]).thenComparingLong(e -> e[1]));
		for (int i = 0; i < candidates.size(); i++) {
			int index = candidateBase + i;
			heap.add(new long[] { session.evaluateDrop(index).delta(), index, session.version() });
		}

		int keptCandidates = candidates.size();
		while (!heap.isEmpty()) {
			long[] entry = heap.poll();
			int index = (int) entry[1];
			if (session.staleSince((int) entry[2], index)) {
				Session.Eval fresh = session.evaluateDrop(index);
				heap.add(new long[] { fresh.delta(), index, session.version() });
				continue;
			}

			boolean overBudget = budget > 0 && keptCandidates > budget;
			boolean withinTolerance = limitTotal >= 0 && session.total() + entry[0] <= limitTotal;
			if (!overBudget && !withinTolerance) {
				// Cheapest verified candidate no longer fits: budget satisfied
				// and (no tolerance pass, or it would breach the line). Stop.
				break;
			}

			session.commitDrop(session.evaluateDrop(index));
			keptCandidates--;
		}

		List<Blob> kept = session.keptCandidates();

		// Belt and suspenders: the reported pruned score comes from a full
		// re-render of the kept list, not the incrementally maintained
		// composite (tests additionally pin both to be identical).
		double prunedScore = renderScore(concat(pinned, kept), target, background);
		return new Result(kept, candidates.size(), originalScore, prunedScore);
	}

	/**
	 * Render a blob list exactly as the committed model render would draw it:
	 * background fill + {@link BorstCore#drawLines} per blob, in list order,
	 * with the blob's palette-quantized color/alpha/size.
	 */
	public static BorstImage render(List<Blob> blobs, int width, int height, int background) {
		BorstImage image = new BorstImage(new int[width * height], width);
		Arrays.fill(image.pixels, background);
		for (Blob blob : blobs) {
			drawBlob(image, blob, 0, 0);
		}
		return image;
	}

	/** True uniform RMSE of rendering {@code blobs} against {@code target}. */
	public static double renderScore(List<Blob> blobs, BorstImage target, int background) {
		BorstImage rendered = render(blobs, target.width, target.height, background);
		return rmse(uniformError(target.pixels, 0, 0, rendered.pixels, rendered.width, 0, 0,
			target.width, target.height, target.width), target.width, target.height);
	}

	/** Uniform RGBA rmse — the exact definition of {@code ImageMetrics.rmse}. */
	static double rmse(long total, int width, int height) {
		return Math.sqrt(total / (width * height * 4.0)) / 255.0;
	}

	private static void drawBlob(BorstImage image, Blob blob, int originX, int originY) {
		BorstCore.drawLines(image, BorstUtils.COLORS[blob.colorIndex], blob.alpha, blob.sizeIndex,
			blob.x - originX, blob.y - originY);
	}

	private static List<Blob> concat(List<Blob> a, List<Blob> b) {
		List<Blob> out = new ArrayList<>(a.size() + b.size());
		out.addAll(a);
		out.addAll(b);
		return out;
	}

	/**
	 * Uniform squared error between two pixel windows of the same size.
	 * Each buffer is addressed as {@code (originX + x, originY + y)} in its own
	 * stride space for the {@code w * h} window.
	 */
	private static long uniformError(int[] aPixels, int aOriginX, int aOriginY,
			int[] bPixels, int bStride, int bOriginX, int bOriginY,
			int w, int h, int aStride) {
		long total = 0;
		for (int y = 0; y < h; y++) {
			int ai = (aOriginY + y) * aStride + aOriginX;
			int bi = (bOriginY + y) * bStride + bOriginX;
			for (int x = 0; x < w; x++) {
				int aa = aPixels[ai + x];
				int bb = bPixels[bi + x];
				int da = ((aa >>> 24) & 0xff) - ((bb >>> 24) & 0xff);
				int dr = ((aa >>> 16) & 0xff) - ((bb >>> 16) & 0xff);
				int dg = ((aa >>>  8) & 0xff) - ((bb >>>  8) & 0xff);
				int db = ((aa       ) & 0xff) - ((bb       ) & 0xff);
				total += dr * dr + dg * dg + db * db + da * da;
			}
		}
		return total;
	}

	/** Per size index: {minX, maxX, minY, maxY} stamp extents relative to the blob center. */
	private static final int[][] SIZE_BOUNDS = buildSizeBounds();

	private static int[][] buildSizeBounds() {
		int[][] bounds = new int[CircleCache.CIRCLE_CACHE.length][];
		for (int size = 0; size < bounds.length; size++) {
			Scanline[] lines = CircleCache.CIRCLE_CACHE[size];
			int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
			int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
			for (Scanline line : lines) {
				minX = Math.min(minX, line.x1);
				maxX = Math.max(maxX, line.x2);
				minY = Math.min(minY, line.y);
				maxY = Math.max(maxY, line.y);
			}
			bounds[size] = new int[] { minX, maxX, minY, maxY };
		}
		return bounds;
	}

	/**
	 * One pruning session: the ordered blob list (pinned prefix + candidates),
	 * the exact composite of every surviving blob, and the exact uniform error
	 * total against the target. Package-private so tests can pin the replay
	 * exactness directly ({@code evaluateDrop} vs a full recompute).
	 */
	static final class Session {
		/** One evaluated drop: exact delta + the replayed box for a commit. */
		record Eval(int index, long delta, int[] boxPixels, int x0, int y0, int x1, int y1) {
		}

		private final List<Blob> blobs;      // pinned + candidates, paint order
		private final int pinnedCount;
		private final BorstImage target;
		private final int background;
		private final boolean[] dropped;     // only indices >= pinnedCount can be set
		private final int[][] boxes;         // clipped stamp bbox per blob, null if off-canvas
		private final BorstImage composite;  // exact render of all surviving blobs
		private final List<int[]> droppedBoxes = new ArrayList<>();
		private long total;

		Session(List<Blob> pinned, List<Blob> candidates, BorstImage target, int background) {
			this.blobs = concat(pinned, candidates);
			this.pinnedCount = pinned.size();
			this.target = target;
			this.background = background;
			this.dropped = new boolean[blobs.size()];
			this.boxes = new int[blobs.size()][];
			for (int i = 0; i < blobs.size(); i++) {
				this.boxes[i] = clippedBox(blobs.get(i), target.width, target.height);
			}
			this.composite = render(blobs, target.width, target.height, background);
			this.total = uniformError(target.pixels, 0, 0, composite.pixels, composite.width, 0, 0,
				target.width, target.height, target.width);
		}

		long total() {
			return total;
		}

		int version() {
			return droppedBoxes.size();
		}

		/** Has any drop committed since {@code version} intersected blob {@code index}'s box? */
		boolean staleSince(int version, int index) {
			int[] box = boxes[index];
			if (box == null) {
				return false;
			}
			for (int v = version; v < droppedBoxes.size(); v++) {
				int[] other = droppedBoxes.get(v);
				if (other != null && intersects(box, other)) {
					return true;
				}
			}
			return false;
		}

		/**
		 * TRUE delta of dropping candidate {@code index} from the current
		 * surviving set: replays every surviving stamp intersecting the
		 * candidate's box (except the candidate) into a box-sized buffer with
		 * the exact kernel, and compares the box error before/after.
		 */
		Eval evaluateDrop(int index) {
			int[] box = boxes[index];
			if (box == null) {
				// Fully off-canvas stamp: dropping it changes nothing.
				return new Eval(index, 0, null, 0, 0, -1, -1);
			}
			int x0 = box[0], x1 = box[1], y0 = box[2], y1 = box[3];
			int bw = x1 - x0 + 1;
			int bh = y1 - y0 + 1;

			BorstImage scratch = new BorstImage(new int[bw * bh], bw);
			// The composite outside any stamp is the background; inside the box
			// we rebuild from the background exactly like the full render does.
			Arrays.fill(scratch.pixels, background);
			for (int j = 0; j < blobs.size(); j++) {
				if (j == index || dropped[j]) {
					continue;
				}
				int[] other = boxes[j];
				if (other == null || !intersects(box, other)) {
					continue;
				}
				// drawLines clips to the scratch image bounds, which IS the box
				// restriction — per-pixel compositing is position-independent,
				// so every box pixel receives the same stamps in the same order
				// as in the full-canvas render.
				drawBlob(scratch, blobs.get(j), x0, y0);
			}

			long before = uniformError(target.pixels, x0, y0, composite.pixels, composite.width, x0, y0,
				bw, bh, target.width);
			long after = uniformError(target.pixels, x0, y0, scratch.pixels, bw, 0, 0,
				bw, bh, target.width);
			return new Eval(index, after - before, scratch.pixels, x0, y0, x1, y1);
		}

		/** Apply a (fresh) evaluated drop: update composite, total and bookkeeping. */
		void commitDrop(Eval eval) {
			int index = eval.index();
			if (index < pinnedCount) {
				throw new IllegalArgumentException("Cannot drop pinned blob " + index);
			}
			dropped[index] = true;
			droppedBoxes.add(boxes[index]);
			total += eval.delta();
			if (eval.boxPixels() != null) {
				int bw = eval.x1() - eval.x0() + 1;
				for (int y = eval.y0(); y <= eval.y1(); y++) {
					System.arraycopy(eval.boxPixels(), (y - eval.y0()) * bw,
						composite.pixels, y * composite.width + eval.x0(), bw);
				}
			}
		}

		List<Blob> keptCandidates() {
			List<Blob> kept = new ArrayList<>();
			for (int i = pinnedCount; i < blobs.size(); i++) {
				if (!dropped[i]) {
					kept.add(blobs.get(i));
				}
			}
			return kept;
		}

		int[] compositePixels() {
			return composite.pixels;
		}
	}

	private static int[] clippedBox(Blob blob, int width, int height) {
		int[] bounds = SIZE_BOUNDS[blob.sizeIndex];
		int x0 = Math.max(blob.x + bounds[0], 0);
		int x1 = Math.min(blob.x + bounds[1], width - 1);
		int y0 = Math.max(blob.y + bounds[2], 0);
		int y1 = Math.min(blob.y + bounds[3], height - 1);
		if (x0 > x1 || y0 > y1) {
			return null;
		}
		return new int[] { x0, x1, y0, y1 };
	}

	private static boolean intersects(int[] a, int[] b) {
		return a[0] <= b[1] && b[0] <= a[1] && a[2] <= b[3] && b[2] <= a[3];
	}
}
