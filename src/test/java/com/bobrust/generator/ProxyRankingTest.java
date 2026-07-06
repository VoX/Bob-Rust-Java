package com.bobrust.generator;

import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the G2 proxy (strided) candidate-ranking kernel and the G4
 * commit-path color reuse. The invariants under test:
 * <ul>
 *   <li>the proxy is used ONLY to rank candidates — the committed geometry,
 *       color and running score stay exactly what the exact kernels produce</li>
 *   <li>sizes with stride 1 delegate to the exact kernel (proxy == exact)</li>
 *   <li>the proxy ranks well enough that the exact-best candidate lands in
 *       the proxy's top ranks</li>
 *   <li>the color cached from the winner's exact evaluation equals what
 *       Model.addShape's computeColor re-run used to produce</li>
 * </ul>
 */
class ProxyRankingTest {
	private static final int ALPHA = 128;
	private static final int BACKGROUND = 0xFFFFFFFF;

	/** Sizes whose proxy stride is 1 must produce bit-identical scores to the exact kernel. */
	@Test
	void strideOneSizesAreExact() {
		BorstImage target = new BorstImage(ensureArgb(TestImageGenerator.createPhotoDetail()));
		BorstImage current = new BorstImage(target.width, target.height);
		Arrays.fill(current.pixels, BACKGROUND);
		long total = BorstCore.differenceFullTotal(target, current);

		for (int size = 0; size < BorstCore.PROXY_STRIDE.length; size++) {
			if (BorstCore.PROXY_STRIDE[size] != 1) {
				continue;
			}
			for (int[] pos : new int[][] {{16, 16}, {64, 64}, {100, 30}, {-2, 50}}) {
				float exact = BorstCore.differencePartialThreadCombined(
					target, current, total, ALPHA, size, pos[0], pos[1]);
				float proxy = BorstCore.differencePartialProxy(
					target, current, total, ALPHA, size, pos[0], pos[1]);
				assertEquals(exact, proxy, 0.0f,
					"stride-1 size " + size + " at (" + pos[0] + "," + pos[1] + ") must be exact");
			}
		}
	}

	/** A fully out-of-bounds circle must behave like the exact kernels (returns the base score). */
	@Test
	void outOfBoundsMatchesExactKernel() {
		BorstImage target = new BorstImage(ensureArgb(TestImageGenerator.createSolid()));
		BorstImage current = new BorstImage(target.width, target.height);
		Arrays.fill(current.pixels, BACKGROUND);
		long total = BorstCore.differenceFullTotal(target, current);

		for (int size = 0; size < 6; size++) {
			float exact = BorstCore.differencePartialThreadCombined(
				target, current, total, ALPHA, size, -500, -500);
			float proxy = BorstCore.differencePartialProxy(
				target, current, total, ALPHA, size, -500, -500);
			assertEquals(exact, proxy, 0.0f, "out-of-bounds size " + size);
		}
	}

	/**
	 * Rank fidelity: over a large pool of random candidates (all sizes, all
	 * positions), the exact-best candidate must land in the proxy's top ranks.
	 * A mis-ranked near-tie costs nothing visible (the winner is refined with
	 * exact math either way), but the proxy ordering must track the exact
	 * ordering closely for the search to keep converging.
	 */
	@Test
	void exactBestIsInProxyTop() {
		BorstImage target = new BorstImage(ensureArgb(TestImageGenerator.createPhotoDetail()));
		BorstImage current = new BorstImage(target.width, target.height);
		Arrays.fill(current.pixels, BACKGROUND);
		long total = BorstCore.differenceFullTotal(target, current);

		Random rnd = new Random(7);
		int candidates = 500;
		int topK = 16;

		record Scored(int index, float exact, float proxy) {}
		List<Scored> pool = new ArrayList<>();
		for (int i = 0; i < candidates; i++) {
			int x = rnd.nextInt(target.width);
			int y = rnd.nextInt(target.height);
			int size = rnd.nextInt(6);
			float exact = BorstCore.differencePartialThreadCombined(target, current, total, ALPHA, size, x, y);
			float proxy = BorstCore.differencePartialProxy(target, current, total, ALPHA, size, x, y);
			pool.add(new Scored(i, exact, proxy));
		}

		int exactBest = pool.stream().min(Comparator.comparingDouble(Scored::exact)).orElseThrow().index();

		List<Scored> byProxy = new ArrayList<>(pool);
		byProxy.sort(Comparator.comparingDouble(Scored::proxy));
		int proxyRank = -1;
		for (int i = 0; i < byProxy.size(); i++) {
			if (byProxy.get(i).index() == exactBest) {
				proxyRank = i;
				break;
			}
		}

		assertTrue(proxyRank >= 0 && proxyRank < topK,
			"exact-best candidate must be within the proxy top " + topK + " but ranked " + proxyRank);
	}

	/**
	 * THE core guarantee of G2: with proxy ranking enabled (the default), the
	 * committed running total stays exactly equal to a full recompute — the
	 * approximation never leaks into the committed state.
	 */
	@Test
	void committedTotalIsExactWithProxyRanking() {
		BorstImage target = new BorstImage(testImage());

		Model model = new Model(target, BACKGROUND, ALPHA,
			GeneratorConfig.DEFAULT.withUseProxyRanking(true));
		for (int i = 0; i < 30; i++) {
			model.processStep();
		}

		assertEquals(BorstCore.differenceFullTotal(target, model.current), model.getTotalError(),
			"proxy ranking must not leak into the committed exact total");
		assertFalse(Float.isNaN(model.getScore()));
	}

	/**
	 * G4 color reuse: the winner's cached exact-eval color must be exactly the
	 * color the removed computeColor re-run would have produced. Verified by
	 * replaying the committed shapes on a fresh canvas, recomputing every color
	 * from scratch — colors and final pixels must match the model bit-for-bit.
	 */
	@Test
	void committedColorsMatchFreshRecompute() {
		BorstImage target = new BorstImage(testImage());

		Model model = new Model(target, BACKGROUND, ALPHA, GeneratorConfig.DEFAULT);
		for (int i = 0; i < 30; i++) {
			model.processStep();
		}

		BorstImage replay = new BorstImage(target.width, target.height);
		Arrays.fill(replay.pixels, BACKGROUND);
		for (int i = 0; i < model.shapes.size(); i++) {
			Circle shape = model.shapes.get(i);
			int cacheIndex = BorstUtils.getClosestSizeIndex(shape.r);
			BorstColor recomputed = BorstCore.computeColor(target, replay, ALPHA, cacheIndex, shape.x, shape.y);
			assertSame(model.colors.get(i), recomputed,
				"shape " + i + ": cached winner color must equal a fresh computeColor");
			BorstCore.drawLines(replay, recomputed, ALPHA, cacheIndex, shape.x, shape.y);
		}

		assertArrayEquals(model.current.pixels, replay.pixels,
			"replaying the committed shapes with recomputed colors must reproduce the render");
	}

	/** Proxy ranking is deterministic: two default-config runs are byte-identical. */
	@Test
	void proxyRankingIsDeterministic() {
		BorstImage targetA = new BorstImage(testImage());
		BorstImage targetB = new BorstImage(testImage());

		Model a = new Model(targetA, BACKGROUND, ALPHA, GeneratorConfig.DEFAULT.withUseProxyRanking(true));
		Model b = new Model(targetB, BACKGROUND, ALPHA, GeneratorConfig.DEFAULT.withUseProxyRanking(true));
		for (int i = 0; i < 15; i++) {
			a.processStep();
			b.processStep();
		}

		assertArrayEquals(a.current.pixels, b.current.pixels, "same seed + proxy must render identically");
	}

	private static BufferedImage testImage() {
		BufferedImage img = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(java.awt.Color.WHITE);
		g.fillRect(0, 0, 128, 128);
		g.setColor(java.awt.Color.RED);
		g.fillOval(8, 8, 70, 70);
		g.setColor(java.awt.Color.BLUE);
		g.fillRect(60, 60, 56, 56);
		g.setColor(java.awt.Color.GREEN);
		g.fillOval(20, 80, 30, 30);
		g.dispose();
		return img;
	}

	private static BufferedImage ensureArgb(BufferedImage img) {
		if (img.getType() == BufferedImage.TYPE_INT_ARGB) return img;
		BufferedImage argb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = argb.createGraphics();
		g.drawImage(img, 0, 0, null);
		g.dispose();
		return argb;
	}
}
