#!/usr/bin/env python3
"""Design mock for the Game of Life watch face.

Mirrors the Kotlin renderer's pipeline exactly so the layout can be judged
without flashing the watch:
  - 64x64 bitboard Life (one Python int per row, torus wrap)
  - the field is painted into a 64x64 image and scaled NEAREST to 450px
  - overlays (digits, text, rim arc) drawn on top at full resolution
"""
import math, os, random, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

N        = 64                 # grid is N x N
SIZE     = 450                # Galaxy Watch 5 screen
CELL     = SIZE / N
FULL     = (1 << N) - 1

# ── 5x7 pixel font ────────────────────────────────────────────────────────────
FONT = {
 '0': ".###.|#...#|#...#|#...#|#...#|#...#|.###.",
 '1': "..#..|.##..|..#..|..#..|..#..|..#..|.###.",
 '2': ".###.|#...#|....#|...#.|..#..|.#...|#####",
 '3': "####.|....#|....#|.###.|....#|....#|####.",
 '4': "...#.|..##.|.#.#.|#..#.|#####|...#.|...#.",
 '5': "#####|#....|####.|....#|....#|#...#|.###.",
 '6': "..##.|.#...|#....|####.|#...#|#...#|.###.",
 '7': "#####|....#|...#.|..#..|.#...|.#...|.#...",
 '8': ".###.|#...#|#...#|.###.|#...#|#...#|.###.",
 '9': ".###.|#...#|#...#|.####|....#|...#.|.##..",
 ':': ".|#|.|.|.|#|.",
}

def glyph_cells(ch):
    rows = FONT[ch].split('|')
    return [[c == '#' for c in r] for r in rows]

def stamp_text(text, scale, ox, oy):
    """Return set of (col,row) cells covered by `text` at cell offset (ox,oy)."""
    cells, x = set(), ox
    for ch in text:
        g = glyph_cells(ch)
        w = len(g[0])
        for r, row in enumerate(g):
            for c, on in enumerate(row):
                if not on: continue
                for dy in range(scale):
                    for dx in range(scale):
                        cells.add((x + c*scale + dx, oy + r*scale + dy))
        x += (w + 1) * scale
    return cells

def text_width(text, scale):
    return sum((len(glyph_cells(c)[0]) + 1) for c in text) * scale - scale

# ── bitboard Life ─────────────────────────────────────────────────────────────
def rotl(x, k=1):  return ((x << k) | (x >> (N - k))) & FULL
def rotr(x, k=1):  return ((x >> k) | (x << (N - k))) & FULL

def step(rows, birth, survive, mask):
    """One generation. `mask` is per-row bits allowed to be alive."""
    out = []
    for i in range(N):
        u, m, d = rows[(i-1) % N], rows[i], rows[(i+1) % N]
        # horizontal 3-sums; middle row excludes itself
        u0, u1 = _sum3(rotl(u), u, rotr(u))
        d0, d1 = _sum3(rotl(d), d, rotr(d))
        m0, m1 = rotl(m) ^ rotr(m), rotl(m) & rotr(m)
        n = _add3(u0, u1, d0, d1, m0, m1)          # 4 bit-planes, count 0..8
        nxt = 0
        for k in birth:   nxt |= _eq(n, k) & ~m
        for k in survive: nxt |= _eq(n, k) & m
        out.append(nxt & FULL & mask[i])
    return out

def _sum3(a, b, c):
    ab = a ^ b
    return ab ^ c, (a & b) | (ab & c)

def _add3(a0, a1, b0, b1, c0, c1):
    s0, k0 = _sum3(a0, b0, c0)
    t0, tc = _sum3(a1, b1, c1)
    s1, k1 = t0 ^ k0, t0 & k0
    s2, s3 = tc ^ k1, tc & k1
    return (s0, s1, s2, s3)

def _eq(n, k):
    r = FULL
    for b in range(4):
        r &= n[b] if (k >> b) & 1 else ~n[b]
    return r & FULL

# ── palettes ──────────────────────────────────────────────────────────────────
def ramp(hi, lo, steps=6):
    return [tuple(int(hi[j] + (lo[j]-hi[j]) * i/(steps-1)) for j in range(3)) for i in range(steps)]

THEMES = {
  'phosphor': dict(accent=(90, 255, 130),  ramp=ramp((200,255,215), (14,62,30)),  bg=(3,6,4)),
  'amber':    dict(accent=(255, 156, 46),  ramp=ramp((255,225,180), (60,30,4)),   bg=(6,4,2)),
  'ice':      dict(accent=(95, 216, 255),  ramp=ramp((215,245,255), (10,44,64)),  bg=(2,5,8)),
  'bone':     dict(accent=(235, 235, 235), ramp=ramp((255,255,255), (44,44,48)),  bg=(4,4,5)),
}

# ── render one frame ──────────────────────────────────────────────────────────
def render(rows, age, hh, mm, ss, theme, gen, pop, date_str):
    T = THEMES[theme]
    field = Image.new('RGB', (N, N), T['bg'])
    px = field.load()
    for r in range(N):
        w = rows[r]
        if not w: continue
        for c in range(N):
            if (w >> c) & 1:
                px[c, r] = T['ramp'][min(age[r][c], len(T['ramp'])-1)]
    img = field.resize((SIZE, SIZE), Image.NEAREST).convert('RGB')

    # ── digit layer, drawn as cell-aligned blocks so it sits on the same grid
    glow = Image.new('RGB', (SIZE, SIZE), (0,0,0))
    gd = ImageDraw.Draw(glow)
    for (c, r) in digit_cells(hh, mm):
        gd.rectangle([c*CELL, r*CELL, (c+1)*CELL-1, (r+1)*CELL-1], fill=T['accent'])
    img = Image.blend(img, Image.new('RGB',(SIZE,SIZE),(0,0,0)), 0.0)
    img = add(img, glow.filter(ImageFilter.GaussianBlur(7)), 0.55)
    img = add(img, glow, 1.0)

    d = ImageDraw.Draw(img)
    try:
        f_sm = ImageFont.truetype("/System/Library/Fonts/Menlo.ttc", 19)
        f_xs = ImageFont.truetype("/System/Library/Fonts/Menlo.ttc", 15)
    except Exception:
        f_sm = f_xs = ImageFont.load_default()

    a = T['accent']
    dim = tuple(int(v*0.55) for v in a)
    d.text((SIZE/2, ROW_TOP*SIZE), date_str, font=f_sm, fill=a, anchor="mm")
    d.text((SIZE/2, ROW_GEN*SIZE), f"GEN {gen:06d}   POP {pop:04d}", font=f_xs, fill=dim, anchor="mm")
    d.text((SIZE*0.30, ROW_BOT*SIZE), "87%", font=f_sm, fill=a, anchor="mm")
    d.text((SIZE*0.70, ROW_BOT*SIZE), "6.2K", font=f_sm, fill=a, anchor="mm")

    # seconds arc on the rim
    d.arc([8, 8, SIZE-8, SIZE-8], -90, -90 + ss/60*360, fill=a, width=5)
    d.ellipse([4, 4, SIZE-4, SIZE-4], outline=tuple(int(v*0.25) for v in a), width=2)

    return round_crop(img)

def add(base, layer, k):
    b, l = base.load(), layer.load()
    out = Image.new('RGB', base.size)
    o = out.load()
    for y in range(base.size[1]):
        for x in range(base.size[0]):
            br, bg_, bb = b[x,y]; lr, lg, lb = l[x,y]
            o[x,y] = (min(255,int(br+lr*k)), min(255,int(bg_+lg*k)), min(255,int(bb+lb*k)))
    return out

def round_crop(img):
    m = Image.new('L', img.size, 0)
    ImageDraw.Draw(m).ellipse([0,0,img.size[0]-1,img.size[1]-1], fill=255)
    out = Image.new('RGB', img.size, (0,0,0))
    out.paste(img, (0,0), m)
    return out

DIGIT_SCALE = 2
ROW_TOP = 0.135   # date / top complication
ROW_GEN = 0.690   # generation + population readout
ROW_BOT = 0.790   # bottom complication row
# (centreX, centreY, width, height) in screen fractions, for the dead-zone carve
TEXT_BOXES = [
    (0.50, ROW_TOP, 0.42, 0.070),   # date
    (0.50, ROW_GEN, 0.56, 0.055),   # gen/pop readout
    (0.30, ROW_BOT, 0.22, 0.070),   # left complication
    (0.70, ROW_BOT, 0.22, 0.070),   # right complication
]
def digit_cells(hh, mm):
    txt = f"{hh:02d}:{mm:02d}"
    w = text_width(txt, DIGIT_SCALE)
    ox = (N - w)//2
    oy = (N - 7*DIGIT_SCALE)//2
    return stamp_text(txt, DIGIT_SCALE, ox, oy)

def band(y0, y1):
    """Rows of cells covered by a horizontal text band, in screen fractions."""
    return range(max(0,int(y0*N)), min(N,int(y1*N)+1))

def build_mask(hh, mm, halo=1):
    """Cells forced dead: the glyphs plus a halo, so digits stay crisp."""
    dead = set()
    for (c, r) in digit_cells(hh, mm):
        for dy in range(-halo, halo+1):
            for dx in range(-halo, halo+1):
                dead.add(((c+dx) % N, (r+dy) % N))
    mask = [FULL]*N
    for r in range(N):
        m = FULL
        for c in range(N):
            if (c, r) in dead: m &= ~(1 << c)
        mask[r] = m & FULL
    # Small type is not cell-aligned, so clear only the box each string occupies
    # (plus a cell of halo) rather than a full-width band -- the field stays whole.
    for (cx, cy, w, h) in TEXT_BOXES:
        carve(mask, cx - w/2, cy - h/2, cx + w/2, cy + h/2)
    return mask

def carve(mask, x0, y0, x1, y1, halo=1):
    c0 = max(0, int(x0*N) - halo); c1 = min(N-1, int(x1*N) + halo)
    r0 = max(0, int(y0*N) - halo); r1 = min(N-1, int(y1*N) + halo)
    strip = ~(((1 << (c1-c0+1)) - 1) << c0) & FULL
    for r in range(r0, r1+1):
        mask[r] &= strip

OUT_DIR = os.environ.get("GOL_PREVIEW_OUT", ".")

def main():
    theme = sys.argv[1] if len(sys.argv) > 1 else 'phosphor'
    out = sys.argv[2] if len(sys.argv) > 2 else None
    random.seed(7)
    rows = [random.getrandbits(N) & random.getrandbits(N) for _ in range(N)]
    age  = [[0]*N for _ in range(N)]
    hh, mm = 10, 42
    mask = build_mask(hh, mm)
    for g in range(120):
        prev = rows
        rows = step(rows, {3}, {2,3}, mask)
        for r in range(N):
            ch = prev[r] & rows[r]
            for c in range(N):
                if (rows[r] >> c) & 1:
                    age[r][c] = age[r][c] + 1 if (ch >> c) & 1 else 0
        # With an output path given, write the single settled frame the app ships
        # as its preview drawable; otherwise dump a few frames to look at.
        if out and g == 55:
            pop = sum(bin(w).count('1') for w in rows)
            render(rows, age, hh, mm, 37, theme, 142891, pop, "THU 04 SEP").save(out)
            print("preview ->", out); return
        if not out and g in (10, 40, 119):
            pop = sum(bin(w).count('1') for w in rows)
            img = render(rows, age, hh, mm, 37, theme, g, pop, "THU 04 SEP")
            path = os.path.join(OUT_DIR, f"mock_{theme}_{g:03d}.png")
            img.save(path)
            print("wrote", path, "pop", pop)

main()
