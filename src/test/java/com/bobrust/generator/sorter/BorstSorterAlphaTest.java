package com.bobrust.generator.sorter;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bobrust.util.RustUtil;
import com.bobrust.util.data.AppConstants;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Q2 sorter coverage: the greedy next-blob cache key was extended from
 * (size, color) to (size, color, alpha, shape). Verifies the sorter still
 * produces a valid ordering with mixed alphas, that reordering same-color
 * blobs of different alpha cannot change the render (the blend model
 * commutes for equal colors — the sorter's order-independence assumption),
 * and that the extended key actually groups alpha runs.
 */
class BorstSorterAlphaTest {

	/** Sorting mixed-alpha data must be a permutation — nothing dropped or duplicated. */
	@Test
	void mixedAlphaSortIsAPermutation() {
		BlobList list = new BlobList();
		for (int i = 0; i < 200; i++) {
			list.add(Blob.of(
				(i * 37) % 480, (i * 53) % 480,
				com.bobrust.generator.BorstUtils.SIZES[i % 6],
				com.bobrust.generator.BorstUtils.COLORS[i % 16].rgb,
				com.bobrust.generator.BorstUtils.ALPHAS[i % 6],
				AppConstants.CIRCLE_SHAPE));
		}

		BlobList sorted = BorstSorter.sort(list, 512);
		assertEquals(list.size(), sorted.size());

		Map<Blob, Integer> counts = new HashMap<>();
		for (Blob b : list.getList()) {
			counts.merge(b, 1, Integer::sum);
		}
		for (Blob b : sorted.getList()) {
			Integer c = counts.computeIfPresent(b, (k, v) -> v - 1);
			assertNotNull(c, "sorted output contains a blob not in the input: " + b);
			assertTrue(c >= 0, "blob duplicated by sort: " + b);
		}
	}

	/**
	 * Same-color order-independence with mixed alpha: for blobs of ONE color,
	 * the alpha blend commutes (the composite coefficient (1-a1)(1-a2) is
	 * symmetric), so painting the sorted order must render exactly the painted
	 * input order — even where different-alpha blobs overlap. This is the
	 * assumption BorstSorter.get_intersections relies on when it treats
	 * equal-color blobs as freely swappable.
	 */
	@Test
	void mixedAlphaSameColorRenderIsOrderIndependent() {
		int color = com.bobrust.generator.BorstUtils.COLORS[33].rgb;
		BlobList list = new BlobList();
		// Heavily overlapping cluster, all one color, alternating alphas
		for (int i = 0; i < 40; i++) {
			list.add(Blob.of(
				40 + (i * 7) % 30, 40 + (i * 11) % 30,
				com.bobrust.generator.BorstUtils.SIZES[3 + (i % 3)],
				color,
				com.bobrust.generator.BorstUtils.ALPHAS[i % 6],
				AppConstants.CIRCLE_SHAPE));
		}

		BlobList sorted = BorstSorter.sort(list, 128);

		int[] original = render(list.getList(), 128, 128);
		int[] reordered = render(sorted.getList(), 128, 128);
		assertArrayEquals(original, reordered,
			"same-color blobs must render identically in any order, alphas included");
	}

	/**
	 * The extended (size, color, alpha, shape) key pays off: two spatially
	 * separated groups that differ only in alpha must not end up interleaved —
	 * the greedy pass should paint one alpha run, switch once, and finish the
	 * other. RustUtil.getScore counts control changes (4 initial + 1 per
	 * size/color/alpha/shape transition).
	 */
	@Test
	void alphaAwareCacheGroupsAlphaRuns() {
		BlobList interleaved = new BlobList();
		for (int i = 0; i < 20; i++) {
			// Group A: alpha index 2, left side. Group B: alpha index 5, right side.
			interleaved.add(Blob.of(20 + (i % 5) * 30, 20 + (i / 5) * 30,
				com.bobrust.generator.BorstUtils.SIZES[4],
				com.bobrust.generator.BorstUtils.COLORS[10].rgb,
				com.bobrust.generator.BorstUtils.ALPHAS[2],
				AppConstants.CIRCLE_SHAPE));
			interleaved.add(Blob.of(280 + (i % 5) * 30, 20 + (i / 5) * 30,
				com.bobrust.generator.BorstUtils.SIZES[4],
				com.bobrust.generator.BorstUtils.COLORS[10].rgb,
				com.bobrust.generator.BorstUtils.ALPHAS[5],
				AppConstants.CIRCLE_SHAPE));
		}

		BlobList sorted = BorstSorter.sort(interleaved, 512);
		// 4 initial changes + exactly one alpha switch between the two runs
		assertEquals(5, RustUtil.getScore(sorted),
			"expected two clean alpha runs, got: " + describeAlphaRuns(sorted));
		assertTrue(RustUtil.getScore(sorted) < RustUtil.getScore(interleaved),
			"sort must beat the interleaved input order");
	}

	private static String describeAlphaRuns(BlobList list) {
		StringBuilder sb = new StringBuilder();
		int last = -1;
		for (Blob b : list.getList()) {
			if (b.alphaIndex != last) {
				sb.append(b.alphaIndex).append(' ');
				last = b.alphaIndex;
			}
		}
		return sb.toString().trim();
	}

	/** Replicates BorstCore.drawLines' blend (>>>8 divisor) for circles on an ARGB canvas. */
	private static int[] render(List<Blob> blobs, int width, int height) {
		int[] pixels = new int[width * height];
		java.util.Arrays.fill(pixels, 0xFFFFFFFF);
		for (Blob blob : blobs) {
			int alpha = blob.alpha;
			int cr = ((blob.color >> 16) & 0xff) * alpha;
			int cg = ((blob.color >>  8) & 0xff) * alpha;
			int cb = ((blob.color      ) & 0xff) * alpha;
			int pa = 255 - alpha;
			int r = blob.size;
			for (int dy = -r; dy <= r; dy++) {
				int y = blob.y + dy;
				if (y < 0 || y >= height) continue;
				for (int dx = -r; dx <= r; dx++) {
					int x = blob.x + dx;
					if (x < 0 || x >= width || dx * dx + dy * dy > r * r) continue;
					int p = pixels[y * width + x];
					int ar = (cr + ((p >>> 16 & 0xff) * pa)) >>> 8;
					int ag = (cg + ((p >>>  8 & 0xff) * pa)) >>> 8;
					int ab = (cb + ((p        & 0xff) * pa)) >>> 8;
					pixels[y * width + x] = 0xFF000000 | (ar << 16) | (ag << 8) | ab;
				}
			}
		}
		return pixels;
	}
}
