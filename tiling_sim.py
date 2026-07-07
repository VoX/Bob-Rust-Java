#!/usr/bin/env python3
"""Click-budget simulation for the BobRust pixel mode.

Model:
- Brush footprint: in-game size s paints a square of side ~= PITCH*s sign-texels
  (PITCH = 100/32 = 3.125, from CircleCache measured diameters {3,6,12,25,50,100}
  at in-game sizes {1,2,4,8,16,32}).
- The paintable "virtual grid" has pitch PITCH texels; brush size s covers an
  s x s block of virtual cells, s in 1..32.
- Quantize the image at the virtual grid resolution to N colors.
- Per color (ordered by area desc), greedily cover its mask with squares.
  Painter's algorithm: a stamp may also cover cells of colors painted LATER
  (they get overwritten), never cells of colors already painted.
"""
import sys, time
import numpy as np
from PIL import Image

PITCH = 100.0 / 32.0
MAX_SIDE = 32

def quantize(img, grid_w, grid_h, n_colors, dither):
    im = img.convert("RGB").resize((grid_w, grid_h), Image.LANCZOS)
    d = Image.Dither.FLOYDSTEINBERG if dither else Image.Dither.NONE
    q = im.quantize(colors=n_colors, method=Image.Quantize.MEDIANCUT, kmeans=8, dither=d)
    labels = np.array(q, dtype=np.int32)
    return labels

def tile(labels, dontcare=True, max_side=MAX_SIDE):
    """Greedy square covering. Returns (total_stamps, per_color_stats)."""
    H, W = labels.shape
    vals, counts = np.unique(labels, return_counts=True)
    order = vals[np.argsort(-counts)]                      # area desc
    rank = np.zeros(int(labels.max()) + 1, dtype=np.int32)
    for r, v in enumerate(order):
        rank[v] = r
    rank_map = rank[labels]

    total = 0
    stats = []  # (colorLabel, cells, stamps, distinct_sizes)
    for r, v in enumerate(order):
        M = labels == v
        A = (rank_map >= r) if dontcare else M
        # dp[i][j] = largest square with top-left (i,j) inside A
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
        Ml = M
        for i in range(H):
            js = np.flatnonzero(Ml[i] & ~covered[i])
            for j in js:
                if covered[i, j]:
                    continue
                s = dpl[i][j]
                if s > max_side: s = max_side
                covered[i:i + s, j:j + s] = True
                stamps += 1
                sizes.add(s)
        total += stamps
        stats.append((int(v), int(M.sum()), stamps, len(sizes)))
    return total, stats

def run(path, name, fw, fh, n_colors, pitch=PITCH, dontcare=True, dither=False, cps=30):
    img = Image.open(path)
    gw, gh = max(1, round(fw / pitch)), max(1, round(fh / pitch))
    labels = quantize(img, gw, gh, n_colors, dither)
    t0 = time.time()
    stamps, stats = tile(labels, dontcare=dontcare)
    size_clicks = sum(d for (_, _, _, d) in stats)
    ncol = len(stats)
    hex_focus = ncol          # 1 focus click per color
    keystrokes = ncol * 7     # 6 hex digits + enter
    autosaves = stamps // 1000
    setup = 21
    clicks = stamps + size_clicks + hex_focus + autosaves + setup
    actions = clicks + keystrokes
    mins = actions / cps / 60.0
    print(f"{name:10s} {fw}x{fh} N={n_colors:3d} grid={gw}x{gh} ({gw*gh:6d} cells) "
        f"{'dc' if dontcare else 'strict':6s} {'dither' if dither else 'flat':6s} | "
        f"stamps={stamps:6d} sizeClk={size_clicks:4d} hex={hex_focus:3d} keys={keystrokes:4d} "
        f"total={actions:6d} ~{mins:5.1f}min@{cps}cps  ({time.time()-t0:.1f}s)")
    return actions, stamps

BASE = "/home/ec2-user/projects/bobrust-fix/src/test/resources/"
IMGS = {
    "portrait": BASE + "test-images-complex/portrait.png",
    "skyline":  BASE + "test-images-complex/skyline.png",
    "glyphs":   BASE + "test-images-complex/glyphs.png",
    "texture":  BASE + "test-images-complex/texture.png",
    "photo":    BASE + "test-images/photo_detail.png",
}

print("== XL 512x512, measured pitch 3.125 (virtual grid ~164x164) ==")
for nm in IMGS:
    for n in (8, 16, 32, 64):
        run(IMGS[nm], nm, 512, 512, n)

print("\n== painter's-algorithm (dc) vs strict masks, XL N=32 ==")
for nm in ("portrait", "glyphs"):
    run(IMGS[nm], nm, 512, 512, 32, dontcare=False)

print("\n== dithering blowup, XL N=32 ==")
for nm in ("portrait", "glyphs"):
    run(IMGS[nm], nm, 512, 512, 32, dither=True)

print("\n== other frames, N=32 ==")
for nm in ("portrait", "skyline"):
    run(IMGS[nm], nm, 256, 128, 32)   # landscape frame
    run(IMGS[nm], nm, 1024, 512, 32)  # XXL

print("\n== hypothetical 1-texel brush (owner's implicit model), XL N=32 ==")
for nm in ("portrait", "glyphs"):
    run(IMGS[nm], nm, 512, 512, 32, pitch=1.0)
