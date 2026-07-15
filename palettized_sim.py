#!/usr/bin/env python3
"""Click-budget + color-accuracy simulation for the BobRust PALETTIZED mode
(HSV-picker color entry, continuous SIZE field, square brush).

Companion to tiling_sim.py (the pixel-mode/hex-entry sim). Differences:
- Pitch: the SIZE field accepts fractional values, so the base brush size can
  be chosen to make the footprint an exact texel count. Default here:
  pitch 3.2 texels (SIZE = 1.024 under the linear 3.125*s model) -> 160x160
  grid on XL, integer-texel stamp alignment. 3.125 kept for comparison.
- Color entry: hue-bar click + SV-square click + closed-loop swatch verify
  (avg nudge clicks parameterized), instead of hex typing.
- Size change: numeric-field entry (click, Ctrl+A, ~3 keys, Enter) instead of
  a slider click.

Part B simulates the closed loop itself: a "true" picker (integer-pixel
clicks, game-side 8-bit rounding) vs a calibrated model with injected error,
measuring snap distance (CIEDE2000), byte-exact convergence rate and reads.
"""
import colorsys
import math
import numpy as np
from PIL import Image

MAX_SIDE = 32

# ---------- action pricing (documented in PLAN-PALETTIZED-MODE.md) ----------
SETUP_ACTIONS = 67        # 4 focus + 1 clear + 1 square brush + 5 opacity-field entry
                          # + 2 slack + 54 probe-calibration clicks (27 probes + pacing)
COLOR_CLICKS = 2          # hue click + SV click
NUDGE_AVG = 2.2           # measured in Part E (mean 3.2 swatch reads => ~2.2 nudge clicks)
SIZE_ENTRY_ACTIONS = 6    # click field + Ctrl+A + ~3 keys + Enter (paste path: 4)
FINAL_SAVE = 4

# ---------------- quantize + greedy tiling (from tiling_sim.py) -------------

def quantize(img, grid_w, grid_h, n_colors, dither=False):
    im = img.convert("RGB").resize((grid_w, grid_h), Image.LANCZOS)
    d = Image.Dither.FLOYDSTEINBERG if dither else Image.Dither.NONE
    q = im.quantize(colors=n_colors, method=Image.Quantize.MEDIANCUT, kmeans=8, dither=d)
    labels = np.array(q, dtype=np.int32)
    pal = q.getpalette()
    centroids = [tuple(pal[i * 3:i * 3 + 3]) for i in range(n_colors)]
    return labels, centroids


def tile(labels, max_side=MAX_SIDE, vocab=None):
    """Greedy square covering with the painter's-algorithm relaxation.
    vocab: optional sorted list of allowed sides (must contain 1)."""
    H, W = labels.shape
    vals, counts = np.unique(labels, return_counts=True)
    order = vals[np.argsort(-counts)]
    rank = np.zeros(int(labels.max()) + 1, dtype=np.int32)
    for r, v in enumerate(order):
        rank[v] = r
    rank_map = rank[labels]

    if vocab is not None:
        vocab = sorted(vocab)
        snap = np.zeros(max_side + 1, dtype=np.int32)
        for s in range(1, max_side + 1):
            snap[s] = max(v for v in vocab if v <= s)

    total = 0
    stats = []  # (label, cells, stamps, distinct_sizes)
    for r, v in enumerate(order):
        M = labels == v
        A = rank_map >= r
        dp = np.zeros((H + 1, W + 1), dtype=np.int32)
        Al = A.tolist()
        dpl = dp.tolist()
        for i in range(H - 1, -1, -1):
            row, below = dpl[i], dpl[i + 1]
            ai = Al[i]
            for j in range(W - 1, -1, -1):
                if ai[j]:
                    m = below[j]
                    if row[j + 1] < m: m = row[j + 1]
                    if below[j + 1] < m: m = below[j + 1]
                    row[j] = m + 1
        covered = np.zeros_like(M)
        stamps = 0
        sizes = set()
        for i in range(H):
            js = np.flatnonzero(M[i] & ~covered[i])
            for j in js:
                if covered[i, j]:
                    continue
                s = dpl[i][j]
                if s > max_side: s = max_side
                if vocab is not None: s = int(snap[s])
                covered[i:i + s, j:j + s] = True
                stamps += 1
                sizes.add(s)
        total += stamps
        stats.append((int(v), int(M.sum()), stamps, len(sizes)))
    return total, stats


def budget(path, name, fw, fh, n_colors, pitch, cps=30, vocab=None, tag=""):
    img = Image.open(path)
    gw, gh = max(1, round(fw / pitch)), max(1, round(fh / pitch))
    labels, _ = quantize(img, gw, gh, n_colors)
    stamps, stats = tile(labels, vocab=vocab)
    ncol = len(stats)
    size_entries = sum(d for (_, _, _, d) in stats)
    color_actions = ncol * COLOR_CLICKS + round(ncol * NUDGE_AVG)
    autosaves = stamps // 1000
    actions = (stamps + size_entries * SIZE_ENTRY_ACTIONS + color_actions
               + autosaves + SETUP_ACTIONS + FINAL_SAVE)
    mins = actions / cps / 60.0
    print(f"{name:9s} {fw}x{fh} N={n_colors:3d} pitch={pitch:5.3f} grid={gw}x{gh}"
          f" {tag:14s}| stamps={stamps:6d} sizeEntries={size_entries:4d}"
          f" colorActions={color_actions:4d} total={actions:6d} ~{mins:5.1f}min@{cps}cps")
    return actions, stamps, size_entries


# ------------------------- CIEDE2000 (standard) ------------------------------

def srgb_to_lab(rgb):
    def f(c):
        c = c / 255.0
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (f(c) for c in rgb)
    x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
    y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b)
    z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
    def g2(t):
        return t ** (1 / 3) if t > 0.008856 else 7.787 * t + 16 / 116
    fx, fy, fz = g2(x), g2(y), g2(z)
    return (116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))


def ciede2000(rgb1, rgb2):
    L1, a1, b1 = srgb_to_lab(rgb1)
    L2, a2, b2 = srgb_to_lab(rgb2)
    C1 = math.hypot(a1, b1); C2 = math.hypot(a2, b2)
    Cm = (C1 + C2) / 2
    G = 0.5 * (1 - math.sqrt(Cm ** 7 / (Cm ** 7 + 25 ** 7)))
    a1p, a2p = (1 + G) * a1, (1 + G) * a2
    C1p = math.hypot(a1p, b1); C2p = math.hypot(a2p, b2)
    h1p = math.degrees(math.atan2(b1, a1p)) % 360 if (b1 or a1p) else 0
    h2p = math.degrees(math.atan2(b2, a2p)) % 360 if (b2 or a2p) else 0
    dLp = L2 - L1
    dCp = C2p - C1p
    if C1p * C2p == 0:
        dhp = 0
    else:
        dh = h2p - h1p
        if dh > 180: dh -= 360
        elif dh < -180: dh += 360
        dhp = dh
    dHp = 2 * math.sqrt(C1p * C2p) * math.sin(math.radians(dhp) / 2)
    Lm = (L1 + L2) / 2; Cmp = (C1p + C2p) / 2
    if C1p * C2p == 0:
        hm = h1p + h2p
    else:
        hm = (h1p + h2p) / 2
        if abs(h1p - h2p) > 180:
            hm += 180 if hm < 180 else -180
    T = (1 - 0.17 * math.cos(math.radians(hm - 30)) + 0.24 * math.cos(math.radians(2 * hm))
         + 0.32 * math.cos(math.radians(3 * hm + 6)) - 0.20 * math.cos(math.radians(4 * hm - 63)))
    dTheta = 30 * math.exp(-(((hm - 275) / 25) ** 2))
    Rc = 2 * math.sqrt(Cmp ** 7 / (Cmp ** 7 + 25 ** 7))
    Sl = 1 + 0.015 * (Lm - 50) ** 2 / math.sqrt(20 + (Lm - 50) ** 2)
    Sc = 1 + 0.045 * Cmp
    Sh = 1 + 0.015 * Cmp * T
    Rt = -math.sin(math.radians(2 * dTheta)) * Rc
    return math.sqrt((dLp / Sl) ** 2 + (dCp / Sc) ** 2 + (dHp / Sh) ** 2
                     + Rt * (dCp / Sc) * (dHp / Sh))


# --------------------- Part B: picker model + closed loop --------------------

class Picker:
    """Integer-pixel HSV picker. Geometry: SV square W x H px, hue bar Hh px.
    Click at integer (x, y[, yh]) -> game computes s,v,h from pixel centers and
    renders the swatch as round(255 * hsv_to_rgb)."""
    def __init__(self, x0, y0, W, H, yh0, Hh):
        self.x0, self.y0, self.W, self.H, self.yh0, self.Hh = x0, y0, W, H, yh0, Hh

    def hue_at(self, yh):
        t = min(max((yh - self.yh0 + 0.5) / self.Hh, 0.0), 1.0)
        return (1.0 - t) * 360.0          # measured: H=360 top -> 0 bottom

    def color(self, x, y, yh):
        s = min(max((x - self.x0 + 0.5) / self.W, 0.0), 1.0)
        v = 1.0 - min(max((y - self.y0 + 0.5) / self.H, 0.0), 1.0)
        r, g, b = colorsys.hsv_to_rgb(self.hue_at(yh) / 360.0, s, v)
        return (round(r * 255), round(g * 255), round(b * 255))

    def clicks_for(self, rgb):
        h, s, v = colorsys.rgb_to_hsv(rgb[0] / 255, rgb[1] / 255, rgb[2] / 255)
        x = self.x0 + round(s * self.W - 0.5)
        y = self.y0 + round((1 - v) * self.H - 0.5)
        yh = self.yh0 + round((1 - h) * self.Hh - 0.5)
        clamp = lambda a, lo, hi: max(lo, min(hi, a))
        return (clamp(x, self.x0, self.x0 + self.W - 1),
                clamp(y, self.y0, self.y0 + self.H - 1),
                clamp(yh, self.yh0, self.yh0 + self.Hh - 1))


def closed_loop(true_p, model_p, target_rgb, max_reads=8):
    """Plan under model_p, execute under true_p, nudge until byte-exact.
    Returns (reads, exact, best_dE). Palette color := model-predicted reachable
    color (snap through model_p), i.e. what the preview shows."""
    x, y, yh = model_p.clicks_for(target_rgb)
    goal = model_p.color(x, y, yh)              # the promised (preview) color
    gh, gs, gv = colorsys.rgb_to_hsv(goal[0] / 255, goal[1] / 255, goal[2] / 255)
    best = None
    reads = 0
    seen = set()
    while reads < max_reads:
        got = true_p.color(x, y, yh)
        reads += 1
        d = ciede2000(goal, got)
        if best is None or d < best[0]:
            best = (d, got)
        if got == goal:
            return reads, True, 0.0
        rh, rs, rv = colorsys.rgb_to_hsv(got[0] / 255, got[1] / 255, got[2] / 255)
        dh = gh - rh
        if dh > 0.5: dh -= 1.0
        if dh < -0.5: dh += 1.0
        # px corrections via the model's scale; clamp step to +-3 px
        step = lambda e: max(-3, min(3, round(e)))
        dx = step((gs - rs) * model_p.W)
        dy = step(-(gv - rv) * model_p.H)
        dyh = step(-dh * model_p.Hh)
        if dx == 0 and dy == 0 and dyh == 0:
            # sub-pixel disagreement: single-px probe in the largest error dim
            errs = {'x': abs(gs - rs) * model_p.W, 'y': abs(gv - rv) * model_p.H,
                    'yh': abs(dh) * model_p.Hh}
            dim = max(errs, key=errs.get)
            if dim == 'x': dx = 1 if gs > rs else -1
            elif dim == 'y': dy = -1 if gv > rv else 1
            else: dyh = -1 if dh > 0 else 1
        x, y, yh = x + dx, y + dy, yh + dyh
        if (x, y, yh) in seen:
            break
        seen.add((x, y, yh))
    return reads, False, best[0]


def accuracy_study(palette, sv_px, offsets, scale_err=0.0, label=""):
    true_p = Picker(0, 0, sv_px, sv_px, 0, sv_px)
    snap_d = []
    for c in palette:
        x, y, yh = true_p.clicks_for(c)
        snap_d.append(ciede2000(c, true_p.color(x, y, yh)))
    results = []
    for (dx, dy) in offsets:
        model = Picker(dx, dy, sv_px * (1 + scale_err), sv_px * (1 + scale_err),
                       dy, sv_px * (1 + scale_err))
        for c in palette:
            results.append(closed_loop(true_p, model, c))
    reads = [r for r, _, _ in results]
    exact = sum(1 for _, e, _ in results if e)
    resid = [d for _, e, d in results if not e]
    print(f"  {label:34s} snapDE00 mean={np.mean(snap_d):.3f} max={np.max(snap_d):.3f} | "
          f"exact={100*exact/len(results):5.1f}% reads mean={np.mean(reads):.2f} "
          f"max={max(reads)} | residDE00 max={max(resid) if resid else 0:.3f}")


BASE = "/home/ec2-user/projects/bobrust-fix/src/test/resources/"
IMGS = {
    "portrait": BASE + "test-images-complex/portrait.png",
    "skyline":  BASE + "test-images-complex/skyline.png",
    "glyphs":   BASE + "test-images-complex/glyphs.png",
    "texture":  BASE + "test-images-complex/texture.png",
    "photo":    BASE + "test-images/photo_detail.png",
}

if __name__ == "__main__":
    print("== Part A: click budget, XL 512x512, pitch 3.2 (grid 160x160), full side vocab ==")
    for nm in IMGS:
        for n in (32, 40, 64):
            budget(IMGS[nm], nm, 512, 512, n, 3.2)

    print("\n== pitch comparison, N=40 ==")
    for nm in ("portrait", "glyphs"):
        budget(IMGS[nm], nm, 512, 512, 40, 3.125, tag="(old pitch)")
        budget(IMGS[nm], nm, 512, 512, 40, 4.0, tag="(economy)")

    print("\n== restricted side vocab {1,2,4,8,16,32}, N=40, pitch 3.2 ==")
    for nm in ("portrait", "photo", "glyphs"):
        budget(IMGS[nm], nm, 512, 512, 40, 3.2, vocab=[1, 2, 4, 8, 16, 32], tag="(pow2 vocab)")

    print("\n== other frames, N=40, pitch 3.2 ==")
    for nm in ("portrait", "skyline"):
        budget(IMGS[nm], nm, 256, 128, 40, 3.2)
        budget(IMGS[nm], nm, 1024, 512, 40, 3.2)

    print("\n== Part B: color accuracy (palette = portrait+photo N=40 centroids) ==")
    pal = []
    for nm in ("portrait", "photo"):
        _, cent = quantize(Image.open(IMGS[nm]), 160, 160, 40)
        pal.extend(cent)
    pal = list(dict.fromkeys(pal))
    print(f"palette: {len(pal)} distinct colors")
    grid5 = [(dx, dy) for dx in (-2, 0, 2) for dy in (-2, 0, 2)]
    for sv in (205, 312, 410):
        print(f" SV square/hue bar = {sv}px:")
        accuracy_study(pal, sv, [(0, 0)], 0.0,   "perfect model")
        accuracy_study(pal, sv, grid5, 0.0,      "offset +-2px")
        accuracy_study(pal, sv, grid5, 0.01,     "offset +-2px + 1% scale err")
    print("\n 256 random colors, 205px, offset +-2px + 1% scale:")
    rng = np.random.default_rng(7)
    rand = [tuple(int(v) for v in rng.integers(0, 256, 3)) for _ in range(256)]
    accuracy_study(rand, 205, grid5, 0.01, "random colors")


# ---------------- Part C: online model refit during a paint run --------------

def sequential_run(true_p, model0, palette, max_reads=8):
    """Enter the palette in order; after each color, refit the model per-axis by
    linear regression on (clicked px -> read-back s/v/h), seeded with the
    calibration prior. Only observations where the axis is well-conditioned
    are used (S,V observable when v>0.1; hue when s*v>0.08)."""
    obs = {'x': [], 'y': [], 'yh': []}
    x0, W = model0.x0, model0.W
    y0, Hh = model0.y0, model0.H
    yh0, Hb = model0.yh0, model0.Hh
    total_reads, exacts, resid = 0, 0, []
    for c in palette:
        model = Picker(x0, y0, W, Hh, yh0, Hb)
        r, e, d = closed_loop(true_p, model, c, max_reads)
        total_reads += r
        exacts += 1 if e else 0
        if not e: resid.append(d)
        # observe: where did the FINAL read land (approximate: recompute)
        px, py, pyh = model.clicks_for(c)
        got = true_p.color(px, py, pyh)
        gh, gs, gv = colorsys.rgb_to_hsv(got[0]/255, got[1]/255, got[2]/255)
        if gv > 0.1:
            obs['x'].append((px, gs)); obs['y'].append((py, 1 - gv))
        if gs * gv > 0.08:
            obs['yh'].append((pyh, 1 - gh))
        def refit(pairs, prior_off, prior_scale):
            if len(pairs) < 3:
                return prior_off, prior_scale
            xs = np.array([p for p, _ in pairs], float)
            ts = np.array([t for _, t in pairs], float)
            if np.ptp(xs) < 8:  # need spread to identify scale
                return prior_off, prior_scale
            A = np.vstack([ts, np.ones_like(ts)]).T
            scale, off = np.linalg.lstsq(A, xs + 0.5, rcond=None)[0]
            return off, scale
        x0, W = refit(obs['x'], x0, W)
        y0, Hh = refit(obs['y'], y0, Hh)
        yh0, Hb = refit(obs['yh'], yh0, Hb)
    return total_reads / len(palette), 100 * exacts / len(palette), max(resid) if resid else 0.0


if len(__import__('sys').argv) > 1 and __import__('sys').argv[1] == 'refit':
    pal = []
    for nm in ("portrait", "photo"):
        _, cent = quantize(Image.open(IMGS[nm]), 160, 160, 40)
        pal.extend(cent)
    pal = list(dict.fromkeys(pal))
    print("== Part C: online refit, worst case (205px, +-2px offset, 1% scale err) ==")
    for (dx, dy) in [(-2, -2), (2, 2), (-2, 2), (2, -2)]:
        true_p = Picker(0, 0, 205, 205, 0, 205)
        model0 = Picker(dx, dy, 205 * 1.01, 205 * 1.01, dy, 205 * 1.01)
        reads, exact, worst = sequential_run(true_p, model0, pal)
        print(f"  offset=({dx:+d},{dy:+d})+1%: reads/color={reads:.2f} exact={exact:.1f}% "
              f"worst residual DE00={worst:.3f}")
    # second pass through the same palette with the settled model (a repaint/resume)
    print("  (second sequential pass = converged model expected near 100%)")


# ------- Part D: probe-grid calibration (the actual designed flow) ----------

def probe_calibrate(true_p, guess: 'Picker', n_probe=5):
    """Robot clicks n_probe points spread across each axis (using the rough
    user-marked rects as the guess), reads back the swatch, and fits the
    per-axis linear map. Returns the fitted Picker model."""
    xs, ss = [], []
    ys, vs = [], []
    yhs, hs = [], []
    for i in range(n_probe):
        t = i / (n_probe - 1)
        # S/V probes at hue=whatever current: use diagonal-avoiding points with V high for S, S high for V
        px = int(guess.x0 + 2 + t * (guess.W - 5))
        py = int(guess.y0 + 2 + t * (guess.H - 5))
        pyh = int(guess.yh0 + 2 + t * (guess.Hh - 5))
        # probe S along the top edge (V~1), V along the right edge (S~1)
        c1 = true_p.color(px, true_p.y0, true_p.yh0 + true_p.Hh // 3)
        h1, s1, v1 = colorsys.rgb_to_hsv(c1[0]/255, c1[1]/255, c1[2]/255)
        xs.append(px); ss.append(s1)
        c2 = true_p.color(true_p.x0 + true_p.W - 1, py, true_p.yh0 + true_p.Hh // 3)
        h2, s2, v2 = colorsys.rgb_to_hsv(c2[0]/255, c2[1]/255, c2[2]/255)
        ys.append(py); vs.append(1 - v2)
        c3 = true_p.color(true_p.x0 + true_p.W - 1, true_p.y0, pyh)
        h3, s3, v3 = colorsys.rgb_to_hsv(c3[0]/255, c3[1]/255, c3[2]/255)
        yhs.append(pyh); hs.append(1 - h3)
    def fit(pxs, ts):
        A = np.vstack([np.array(ts, float), np.ones(len(ts))]).T
        scale, off = np.linalg.lstsq(A, np.array(pxs, float) + 0.5, rcond=None)[0]
        return off, scale
    x0, W = fit(xs, ss)
    y0, H = fit(ys, vs)
    yh0, Hh = fit(yhs, hs)
    return Picker(x0, y0, W, H, yh0, Hh)


if len(__import__('sys').argv) > 1 and __import__('sys').argv[1] == 'probe':
    pal = []
    for nm in ("portrait", "photo"):
        _, cent = quantize(Image.open(IMGS[nm]), 160, 160, 40)
        pal.extend(cent)
    pal = list(dict.fromkeys(pal))
    print("== Part D: probe calibration (user rects off by +-3px & 2% scale), then palette run ==")
    for sv in (205, 312, 410):
        true_p = Picker(0, 0, sv, sv, 0, sv)
        guess = Picker(-3, 3, sv * 1.02, sv * 1.02, 3, sv * 1.02)
        for n_probe in (5, 9):
            fitted = probe_calibrate(true_p, guess, n_probe)
            res = [closed_loop(true_p, fitted, c) for c in pal]
            reads = [r for r, _, _ in res]
            exact = 100 * sum(1 for _, e, _ in res if e) / len(res)
            resid = [d for _, e, d in res if not e]
            print(f"  {sv}px probes/axis={n_probe}: fitted err off=({fitted.x0:+.2f},{fitted.y0:+.2f}) "
                  f"scale={(fitted.W/sv-1)*100:+.2f}% | exact={exact:5.1f}% reads mean={np.mean(reads):.2f} "
                  f"max={max(reads)} residDE00 max={max(resid) if resid else 0:.3f}")


# ---- Part E: centroid-tolerance acceptance (the actual executor contract) ---
# Palette color := best swatch readback within <=K reads, judged by DE00 vs the
# QUANTIZER CENTROID. Reported against the theoretical floor (true snap).

def enter_color(true_p, model_p, c0, max_reads=4):
    x, y, yh = model_p.clicks_for(c0)
    gh, gs, gv = colorsys.rgb_to_hsv(c0[0] / 255, c0[1] / 255, c0[2] / 255)
    best = (1e9, None); first = None; reads = 0
    while reads < max_reads:
        got = true_p.color(x, y, yh)
        reads += 1
        d = ciede2000(c0, got)
        if first is None: first = d
        if d < best[0]: best = (d, got)
        rh, rs, rv = colorsys.rgb_to_hsv(got[0]/255, got[1]/255, got[2]/255)
        dh = gh - rh
        if dh > 0.5: dh -= 1.0
        if dh < -0.5: dh += 1.0
        moves = {'x': (gs - rs) * model_p.W, 'y': -(gv - rv) * model_p.H,
                 'yh': -dh * model_p.Hh}
        dim, mv = max(moves.items(), key=lambda kv: abs(kv[1]))
        if abs(mv) < 0.35:   # sub-third-pixel: converged to grid best
            break
        stepv = max(-3, min(3, round(mv))) or (1 if mv > 0 else -1)
        if dim == 'x': x += stepv
        elif dim == 'y': y += stepv
        else: yh += stepv
    return first, best[0], reads

if len(__import__('sys').argv) > 1 and __import__('sys').argv[1] == 'accept':
    pal = []
    for nm in ("portrait", "photo"):
        _, cent = quantize(Image.open(IMGS[nm]), 160, 160, 40)
        pal.extend(cent)
    pal = list(dict.fromkeys(pal))
    print("== Part E: DE00-vs-centroid acceptance, probe-calibrated model ==")
    print("   (user rects off +-3px & 2% scale before probing; K=4 reads max)")
    for sv in (205, 312, 410):
        true_p = Picker(0, 0, sv, sv, 0, sv)
        guess = Picker(-3, 3, sv * 1.02, sv * 1.02, 3, sv * 1.02)
        fitted = probe_calibrate(true_p, guess, 9)
        floor = []
        for c in pal:
            x, y, yh = true_p.clicks_for(c)
            floor.append(ciede2000(c, true_p.color(x, y, yh)))
        rs = [enter_color(true_p, fitted, c) for c in pal]
        firsts = [f for f, _, _ in rs]; bests = [b for _, b, _ in rs]
        reads = [r for _, _, r in rs]
        print(f"  {sv}px: floor mean={np.mean(floor):.3f} max={np.max(floor):.3f} | "
              f"first-click DE00 mean={np.mean(firsts):.3f} max={np.max(firsts):.3f} | "
              f"after nudge mean={np.mean(bests):.3f} max={np.max(bests):.3f} | "
              f"reads mean={np.mean(reads):.2f} max={max(reads)}")
