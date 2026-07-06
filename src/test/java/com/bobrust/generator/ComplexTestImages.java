package com.bobrust.generator;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Complex benchmark targets for the click-budget study (docs/SPEED-QUALITY-
 * PROPOSALS.md). The {@link TestImageGenerator} corpus is 128px and mostly
 * low-entropy; these are 384x384 and deliberately stress a 18k-click budget:
 * high-frequency texture, text-like hard edges, a face-like structure, a
 * smooth-gradient-plus-detail mix and a color-diverse mosaic.
 *
 * <p>Everything is computed with direct per-pixel math from seeded
 * {@link Random}/integer-hash noise — no fonts, no Graphics2D, no
 * antialiasing — so the images are byte-identical on every platform and JVM.
 * {@link #main} writes them to {@code src/test/resources/test-images-complex/}
 * as the committed reference copies; the sweep harness regenerates them
 * in-memory (identical bytes) so it needs no resource loading.
 */
public final class ComplexTestImages {
	public static final int SIZE = 384;

	private ComplexTestImages() {
	}

	/** The corpus in stable order, keyed by the name used in benchmark output. */
	public static Map<String, BufferedImage> corpus() {
		Map<String, BufferedImage> corpus = new LinkedHashMap<>();
		corpus.put("texture", createTexture());
		corpus.put("glyphs", createGlyphs());
		corpus.put("portrait", createPortrait());
		corpus.put("skyline", createSkyline());
		corpus.put("mosaic", createMosaic());
		return corpus;
	}

	// ------------------------------------------------------------------
	// 1. texture — multi-octave noise terrain with pebbles and grain.
	// High-frequency detail everywhere; no flat region to coast on.
	// ------------------------------------------------------------------
	public static BufferedImage createTexture() {
		int[] px = new int[SIZE * SIZE];
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				double base = fbm(x / 96.0, y / 96.0, 4, 11);       // large forms
				double mid = fbm(x / 24.0, y / 24.0, 3, 23);        // mid detail
				double fine = fbm(x / 6.0, y / 6.0, 2, 37);         // fine detail
				double v = 0.45 * base + 0.35 * mid + 0.20 * fine;

				// Terrain-ish ramp: dark soil -> moss green -> dry yellow
				int r, g, b;
				if (v < 0.45) {
					double t = v / 0.45;
					r = (int) (55 + 40 * t);
					g = (int) (40 + 70 * t);
					b = (int) (30 + 25 * t);
				} else {
					double t = (v - 0.45) / 0.55;
					r = (int) (95 + 115 * t);
					g = (int) (110 + 85 * t);
					b = (int) (55 + 35 * t);
				}
				// Pixel grain
				double grain = hash(x, y, 51) - 0.5;
				r = clamp(r + (int) (26 * grain));
				g = clamp(g + (int) (26 * grain));
				b = clamp(b + (int) (26 * grain));
				px[y * SIZE + x] = rgb(r, g, b);
			}
		}
		// Scattered pebbles: small discs with rim shading
		Random rng = new Random(1001);
		for (int i = 0; i < 240; i++) {
			int cx = rng.nextInt(SIZE);
			int cy = rng.nextInt(SIZE);
			int rad = 2 + rng.nextInt(5);
			int shade = 70 + rng.nextInt(130);
			int pr = clamp(shade + rng.nextInt(40) - 10);
			int pg = clamp(shade + rng.nextInt(30) - 10);
			int pb = clamp(shade + rng.nextInt(20) - 5);
			fillDisc(px, cx, cy, rad, pr, pg, pb, 0.35);
		}
		return toImage(px);
	}

	// ------------------------------------------------------------------
	// 2. glyphs — a document: headline, pseudo-text lines, rules, a chart.
	// Hard 1-2px edges and text-like clutter; worst case for big circles.
	// ------------------------------------------------------------------
	public static BufferedImage createGlyphs() {
		int[] px = new int[SIZE * SIZE];
		// Paper: warm off-white with a faint vertical gradient
		for (int y = 0; y < SIZE; y++) {
			int shade = 246 - y / 24;
			for (int x = 0; x < SIZE; x++) {
				int n = (int) (6 * (hash(x, y, 71) - 0.5));
				px[y * SIZE + x] = rgb(clamp(shade + n), clamp(shade - 2 + n), clamp(shade - 8 + n));
			}
		}
		Random rng = new Random(2002);
		// Headline: large glyphs
		drawGlyphLine(px, rng, 24, 22, 20, 13, rgb(25, 22, 30));
		fillRect(px, 24, 52, 336, 2, rgb(120, 30, 30));
		// Body text: 14 lines of small glyphs
		for (int line = 0; line < 14; line++) {
			int y = 72 + line * 16;
			drawGlyphLine(px, rng, 24, y, 9, 6, rgb(35, 33, 40));
		}
		// A bar chart block bottom-left
		int chartY = 306, chartH = 58;
		fillRect(px, 24, chartY - 6, 168, 1, rgb(90, 90, 100));
		for (int i = 0; i < 8; i++) {
			int h = 10 + rng.nextInt(chartH - 12);
			int hue = rng.nextInt(3);
			int c = hue == 0 ? rgb(178, 60, 50) : hue == 1 ? rgb(50, 100, 160) : rgb(60, 140, 70);
			fillRect(px, 28 + i * 20, chartY + chartH - h, 14, h, c);
		}
		fillRect(px, 24, chartY + chartH, 168, 2, rgb(40, 40, 48));
		// A "photo" block bottom-right: tight concentric rings (fine curves)
		for (int y = chartY - 6; y < chartY + chartH + 2; y++) {
			for (int x = 216; x < 360; x++) {
				double d = Math.sqrt((x - 288) * (x - 288) + (y - 335) * (y - 335));
				int v = (int) (128 + 110 * Math.sin(d * 0.9));
				px[y * SIZE + x] = rgb(v, clamp(255 - v), (v / 2 + 70));
			}
		}
		return toImage(px);
	}

	private static void drawGlyphLine(int[] px, Random rng, int x0, int y0, int glyphH, int glyphW, int ink) {
		int x = x0;
		while (x < SIZE - x0 - glyphW) {
			if (rng.nextInt(6) == 0) {
				x += glyphW; // word gap
				continue;
			}
			int strokes = 2 + rng.nextInt(3);
			for (int s = 0; s < strokes; s++) {
				switch (rng.nextInt(4)) {
					case 0 -> fillRect(px, x + rng.nextInt(glyphW - 1), y0, 1 + rng.nextInt(2), glyphH, ink); // vertical
					case 1 -> fillRect(px, x, y0 + (glyphH / 3) * rng.nextInt(3), glyphW - 1, 1 + rng.nextInt(2), ink); // horizontal
					case 2 -> { // diagonal
						for (int i = 0; i < glyphH; i++) {
							int dx = x + i * (glyphW - 1) / Math.max(1, glyphH - 1);
							setPx(px, dx, y0 + i, ink);
							setPx(px, dx + 1, y0 + i, ink);
						}
					}
					default -> { // bowl: box outline
						fillRect(px, x, y0 + glyphH / 3, glyphW - 2, 1, ink);
						fillRect(px, x, y0 + glyphH - 1, glyphW - 2, 1, ink);
						fillRect(px, x, y0 + glyphH / 3, 1, glyphH - glyphH / 3, ink);
						fillRect(px, x + glyphW - 3, y0 + glyphH / 3, 1, glyphH - glyphH / 3, ink);
					}
				}
			}
			x += glyphW + 2;
		}
	}

	// ------------------------------------------------------------------
	// 3. portrait — face-like structure: smooth shaded skin (gradients),
	// sharp small features (eyes), hair texture. The classic "faces need
	// both smooth blends and precise detail" regime.
	// ------------------------------------------------------------------
	public static BufferedImage createPortrait() {
		int[] px = new int[SIZE * SIZE];
		double cx = 192, cyFace = 205, rx = 92, ry = 120;
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				// Background: dusky vertical gradient with vignette
				double t = y / (double) SIZE;
				double vign = 1.0 - 0.55 * dist01(x, y, 192, 192, 300);
				int r = (int) ((60 + 40 * t) * vign);
				int g = (int) ((70 + 30 * t) * vign);
				int b = (int) ((95 + 25 * t) * vign);

				double dx = (x - cx) / rx;
				double dy = (y - cyFace) / ry;
				double d = dx * dx + dy * dy;

				// Shoulders / clothing
				double sh = (y - 330) / 54.0 - 0.55 * Math.abs(x - 192) / 192.0;
				if (sh > 0) {
					double n = hash(x / 3, y / 3, 91);
					r = (int) (70 + 18 * n);
					g = (int) (30 + 10 * n);
					b = (int) (34 + 10 * n);
				}

				// Hair: ellipse shell above/around the face
				double hx = (x - cx) / (rx * 1.22);
				double hy = (y - (cyFace - 28)) / (ry * 1.02);
				double hd = hx * hx + hy * hy;
				if (hd <= 1.0 && (dy < -0.28 || d > 0.86)) {
					double strand = fbm(x / 4.0, y / 26.0, 2, 77);
					int base = (int) (38 + 55 * strand);
					r = base + 14;
					g = (int) (base * 0.72);
					b = (int) (base * 0.5);
				}

				// Face
				if (d <= 1.0) {
					// Smooth directional shading + edge falloff
					double light = 0.86 + 0.20 * (-dx * 0.55 - dy * 0.30) - 0.26 * d * d;
					double n = 1.0 + 0.045 * (hash(x, y, 13) - 0.5);
					r = clamp((int) (232 * light * n));
					g = clamp((int) (186 * light * n));
					b = clamp((int) (152 * light * n));
					// Cheek blush
					double blushL = Math.exp(-sqr((x - 148) / 22.0) - sqr((y - 240) / 16.0));
					double blushR = Math.exp(-sqr((x - 236) / 22.0) - sqr((y - 240) / 16.0));
					r = clamp((int) (r + 26 * (blushL + blushR)));
					g = clamp((int) (g - 6 * (blushL + blushR)));
					// Forehead hairline overwrite
					if (dy < -0.52 + 0.18 * Math.sin(x * 0.12)) {
						double strand = fbm(x / 4.0, y / 26.0, 2, 77);
						int base = (int) (38 + 55 * strand);
						r = base + 14;
						g = (int) (base * 0.72);
						b = (int) (base * 0.5);
					}
				}
				px[y * SIZE + x] = rgb(r, g, b);
			}
		}
		// Eyes (sclera, iris, pupil, highlight), brows, nose, mouth
		drawEye(px, 155, 192);
		drawEye(px, 229, 192);
		for (int ex = 132; ex <= 176; ex++) { // brows
			int ey = 172 - (int) (5 * Math.sin((ex - 132) / 44.0 * Math.PI));
			fillRect(px, ex, ey, 1, 4, rgb(52, 36, 26));
			fillRect(px, ex + 76, ey, 1, 4, rgb(52, 36, 26));
		}
		for (int ny = 200; ny < 236; ny++) { // nose shadow
			int w = 1 + (ny - 200) / 14;
			for (int nx = 0; nx < w; nx++) {
				blend(px, 196 + nx, ny, rgb(170, 128, 100), 0.5);
			}
		}
		fillDisc(px, 186, 240, 2, 150, 105, 85, 0);
		fillDisc(px, 198, 240, 2, 150, 105, 85, 0);
		for (int mx = 165; mx <= 219; mx++) { // mouth
			double mt = (mx - 165) / 54.0;
			int my = 265 + (int) (4 * Math.sin(mt * Math.PI));
			int lipH = 3 + (int) (3 * Math.sin(mt * Math.PI));
			for (int i = -lipH; i <= lipH; i++) {
				blend(px, mx, my + i, rgb(168, 62, 66), i == 0 ? 0.9 : 0.65);
			}
			setPx(px, mx, my, rgb(120, 40, 44));
		}
		return toImage(px);
	}

	private static void drawEye(int[] px, int ex, int ey) {
		for (int y = -7; y <= 7; y++) {
			for (int x = -15; x <= 15; x++) {
				double d = sqr(x / 15.0) + sqr(y / 7.0);
				if (d <= 1.0) {
					int v = (int) (250 - 40 * d);
					setPx(px, ex + x, ey + y, rgb(v, v, v - 6));
				}
			}
		}
		fillDisc(px, ex, ey, 6, 70, 96, 130, 0.35);   // iris
		fillDisc(px, ex, ey, 3, 18, 16, 20, 0);       // pupil
		fillDisc(px, ex + 2, ey - 2, 1, 245, 245, 250, 0); // highlight
		for (int x = -15; x <= 15; x++) {             // lash line
			int yy = ey - (int) (7 * Math.sqrt(Math.max(0, 1 - sqr(x / 15.0))));
			setPx(px, ex + x, yy, rgb(40, 30, 28));
			setPx(px, ex + x, yy - 1, rgb(40, 30, 28));
		}
	}

	// ------------------------------------------------------------------
	// 4. skyline — smooth sunset gradient + sun glow on top, dense
	// high-frequency city detail (windows, antennas, stars) below.
	// The smooth/detail split is where adaptive sizing must earn its keep.
	// ------------------------------------------------------------------
	public static BufferedImage createSkyline() {
		int[] px = new int[SIZE * SIZE];
		int horizon = 268;
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				double t = Math.min(1.0, y / (double) horizon);
				// Deep blue -> violet -> orange
				int r = (int) (18 + 215 * Math.pow(t, 2.2));
				int g = (int) (24 + 96 * Math.pow(t, 2.6));
				int b = (int) (66 + 60 * t - 68 * Math.pow(t, 3));
				// Sun glow (additive) around (270, 225)
				double sd = Math.sqrt(sqr(x - 270.0) + sqr((y - 225.0) * 1.15));
				double glow = Math.exp(-sd / 55.0);
				r = clamp((int) (r + 200 * glow));
				g = clamp((int) (g + 120 * glow));
				b = clamp((int) (b + 40 * glow));
				if (sd < 20) { // sun disc
					r = 255;
					g = 214;
					b = 120;
				}
				// Thin cloud bands
				double cl = fbm(x / 70.0, y / 9.0, 2, 41);
				if (y < horizon - 10 && cl > 0.62) {
					double a = Math.min(1.0, (cl - 0.62) * 5) * 0.5;
					r = (int) (r * (1 - a) + 235 * a);
					g = (int) (g * (1 - a) + 150 * a);
					b = (int) (b * (1 - a) + 120 * a);
				}
				px[y * SIZE + x] = rgb(r, g, b);
			}
		}
		// Stars, top third only
		for (int i = 0; i < 160; i++) {
			int sx = (int) (hash(i, 7, 3) * SIZE);
			int sy = (int) (hash(i, 19, 3) * 130);
			int v = 120 + (int) (hash(i, 31, 3) * 135);
			setPx(px, sx, sy, rgb(v, v, clamp(v + 20)));
		}
		// Buildings: stepwise silhouette with lit windows
		Random rng = new Random(4004);
		int x = 0;
		while (x < SIZE) {
			int w = 18 + rng.nextInt(30);
			int top = horizon - 25 - rng.nextInt(120);
			int shade = 14 + rng.nextInt(14);
			for (int bx = x; bx < Math.min(SIZE, x + w); bx++) {
				for (int by = top; by < SIZE; by++) {
					px[by * SIZE + bx] = rgb(shade, shade + 2, shade + 8);
				}
			}
			// Windows: 3x2 lit cells on a 6x8 grid
			for (int wy = top + 4; wy < SIZE - 4; wy += 8) {
				for (int wx = x + 3; wx < x + w - 4; wx += 6) {
					if (rng.nextInt(10) < 3) {
						int warm = 170 + rng.nextInt(85);
						fillRect(px, wx, wy, 3, 4, rgb(warm, (int) (warm * 0.78), 60));
					}
				}
			}
			if (rng.nextInt(3) == 0) { // antenna
				int ax = x + w / 2;
				fillRect(px, ax, top - 14 - rng.nextInt(12), 1, 14 + rng.nextInt(12), rgb(10, 10, 14));
			}
			x += w;
		}
		return toImage(px);
	}

	// ------------------------------------------------------------------
	// 5. mosaic — quadtree color tiles with grout lines: maximal palette
	// diversity + hard edges at every scale. Stresses color tool changes.
	// ------------------------------------------------------------------
	public static BufferedImage createMosaic() {
		int[] px = new int[SIZE * SIZE];
		Random rng = new Random(5005);
		splitTile(px, rng, 0, 0, SIZE, 0);
		return toImage(px);
	}

	private static void splitTile(int[] px, Random rng, int x, int y, int s, int depth) {
		boolean split = s > 96 || (s > 24 && rng.nextInt(100) < 62 - depth * 4);
		if (split) {
			int h = s / 2;
			splitTile(px, rng, x, y, h, depth + 1);
			splitTile(px, rng, x + h, y, h, depth + 1);
			splitTile(px, rng, x, y + h, h, depth + 1);
			splitTile(px, rng, x + h, y + h, h, depth + 1);
			return;
		}
		float hue = rng.nextFloat();
		float sat = 0.45f + 0.5f * rng.nextFloat();
		float val = 0.45f + 0.5f * rng.nextFloat();
		int c = java.awt.Color.HSBtoRGB(hue, sat, val);
		int cr = (c >> 16) & 0xff, cg = (c >> 8) & 0xff, cb = c & 0xff;
		int grout = rgb(28, 26, 32);
		for (int ty = y; ty < y + s; ty++) {
			for (int tx = x; tx < x + s; tx++) {
				if (tx == x || ty == y || tx == x + s - 1 || ty == y + s - 1) {
					setPx(px, tx, ty, grout);
				} else {
					int n = (int) (16 * (hash(tx, ty, 61) - 0.5));
					setPx(px, tx, ty, rgb(clamp(cr + n), clamp(cg + n), clamp(cb + n)));
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------
	/** Deterministic integer hash to [0, 1) — platform-stable pixel noise. */
	private static double hash(int x, int y, int seed) {
		long h = x * 374761393L + y * 668265263L + seed * 1274126177L;
		h = (h ^ (h >>> 13)) * 1442695040888963407L;
		h ^= (h >>> 16);
		return (h & 0xffffff) / (double) 0x1000000;
	}

	/** Bilinear value noise from the lattice hash. */
	private static double valueNoise(double x, double y, int seed) {
		int x0 = (int) Math.floor(x);
		int y0 = (int) Math.floor(y);
		double fx = x - x0;
		double fy = y - y0;
		double sx = fx * fx * (3 - 2 * fx);
		double sy = fy * fy * (3 - 2 * fy);
		double a = hash(x0, y0, seed);
		double b = hash(x0 + 1, y0, seed);
		double c = hash(x0, y0 + 1, seed);
		double d = hash(x0 + 1, y0 + 1, seed);
		return a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy;
	}

	/** Fractal Brownian motion: {@code octaves} of value noise, halving amplitude. */
	private static double fbm(double x, double y, int octaves, int seed) {
		double sum = 0;
		double amp = 0.5;
		double freq = 1;
		double norm = 0;
		for (int i = 0; i < octaves; i++) {
			sum += amp * valueNoise(x * freq, y * freq, seed + i * 101);
			norm += amp;
			amp *= 0.5;
			freq *= 2;
		}
		return sum / norm;
	}

	private static void fillDisc(int[] px, int cx, int cy, int rad, int r, int g, int b, double rimDarken) {
		for (int y = -rad; y <= rad; y++) {
			for (int x = -rad; x <= rad; x++) {
				double d = Math.sqrt(x * x + y * y) / rad;
				if (d <= 1.0) {
					double k = 1.0 - rimDarken * d;
					setPx(px, cx + x, cy + y, rgb(clamp((int) (r * k)), clamp((int) (g * k)), clamp((int) (b * k))));
				}
			}
		}
	}

	private static void fillRect(int[] px, int x, int y, int w, int h, int color) {
		for (int yy = y; yy < y + h; yy++) {
			for (int xx = x; xx < x + w; xx++) {
				setPx(px, xx, yy, color);
			}
		}
	}

	private static void blend(int[] px, int x, int y, int color, double a) {
		if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) {
			return;
		}
		int old = px[y * SIZE + x];
		int r = (int) (((old >> 16) & 0xff) * (1 - a) + ((color >> 16) & 0xff) * a);
		int g = (int) (((old >> 8) & 0xff) * (1 - a) + ((color >> 8) & 0xff) * a);
		int b = (int) ((old & 0xff) * (1 - a) + (color & 0xff) * a);
		px[y * SIZE + x] = rgb(r, g, b);
	}

	private static void setPx(int[] px, int x, int y, int color) {
		if (x >= 0 && y >= 0 && x < SIZE && y < SIZE) {
			px[y * SIZE + x] = color;
		}
	}

	private static int rgb(int r, int g, int b) {
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private static int clamp(int v) {
		return v < 0 ? 0 : (v > 255 ? 255 : v);
	}

	private static double sqr(double v) {
		return v * v;
	}

	private static double dist01(int x, int y, double cx, double cy, double norm) {
		return Math.min(1.0, Math.sqrt(sqr(x - cx) + sqr(y - cy)) / norm);
	}

	private static BufferedImage toImage(int[] px) {
		BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		img.setRGB(0, 0, SIZE, SIZE, px, 0, SIZE);
		return img;
	}

	/** Write the committed reference PNGs. */
	public static void main(String[] args) throws IOException {
		File dir = new File("src/test/resources/test-images-complex");
		dir.mkdirs();
		for (Map.Entry<String, BufferedImage> entry : corpus().entrySet()) {
			ImageIO.write(entry.getValue(), "png", new File(dir, entry.getKey() + ".png"));
		}
		System.out.println("Complex test images written to " + dir.getAbsolutePath());
	}
}
