"""The TV widget's frames (8 Nov): the pixel mascot on an old TV, blinking, twinkling and now and then wiping the glass
from inside; and the TV switched off. Run: python3 -I tools/tv_widget_frames.py — writes app/src/main/res/drawable-nodpi/tv_*.png"""
import os, sys
from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "drawable-nodpi")
W, H, S = 80, 62, 6  # logical pixels, and how much each is scaled
MASCOT = [
    "............................", "...........DDD..............", "........DDDYYYDDD...........", ".......DYLYYYYYYYD..........",
    ".....DDYLLLYYYYYYYDD........", ".....DYLLLLLYYYYYYYD........", "....DYYYLLLYYYYYYYYYD.......", "....DYYYYLYYYYYYYYYOD.......",
    "..DODYYYVVVVVVVVVVYOODOD...W", "..OHOYYVVVVVVVVVVVVYOOHO...W", "..OHOYYVVEEVVVVEEVVYOOHO.W.W", "..OHOYYVEVVEVVEVVEVYOOHO.W.W",
    "..OHOYYVEVVEVVEVVEVYOOHO.W.W", "..OHOYYVVVVVVVVVVVVOOOHO...W", "..DODYYVVVVVVVVVVVVODDOD...W", "....DYYYVVVVVVVVVVOOD.......",
    ".....DYYYYYYYYYYYOOD........", ".....DDYYYYYYYYYOODD..S.....", ".......DOOOOOOOOOD....S.....", "........DDDOOODDD...SSHSS...",
    "...........DDD........S.....", "......................S.....", "........GGGGGGGGGG..........", "..........GGGGGG............",
]
P = {'D': (180, 82, 10), 'O': (245, 158, 27), 'Y': (255, 201, 60), 'L': (255, 229, 138), 'V': (59, 36, 24), 'E': (255, 216, 107),
     'H': (255, 241, 194), 'W': (255, 226, 160), 'S': (255, 246, 208), 'G': (255, 179, 71)}
CAB, TRIM, GLASS, LINE = (59, 52, 48), (90, 78, 70), (18, 32, 29), (12, 22, 20)
SMUDGES = [(10, 9), (11, 9), (50, 12), (14, 44), (15, 45), (47, 47), (48, 47), (30, 8)]
GX0, GY0, GX1, GY1 = 4, 4, 61, 57


def tv(on=True):
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    d.rounded_rectangle((0, 0, W - 1, H - 1), 7, fill=CAB, outline=TRIM)
    d.rounded_rectangle((GX0, GY0, GX1, GY1), 6, fill=GLASS if on else (10, 14, 13))
    for k, y in enumerate((14, 30)):
        d.ellipse((66, y - 5, 75, y + 4), fill=TRIM); d.ellipse((68, y - 3, 73, y + 2), fill=(42, 36, 32)); d.line((70, y - 3, 70, y - 1), fill=(191, 179, 166))
    for y in range(40, 56, 2): d.line((66, y, 75, y), fill=(30, 26, 24))
    d.point((70, 37), fill=(229, 57, 53) if on else (85, 34, 34))
    return im, d


def mascot(im, x0, y0, blink=False, sparkle=True, waves=False):
    for y, row in enumerate(MASCOT):
        for x, c in enumerate(row):
            if c == '.' or (c == 'S' and not sparkle) or (c == 'W' and not waves): continue
            col = P['V'] if (c == 'E' and blink) else P[c]
            if c == 'G': col = (120, 90, 40)
            im.putpixel((x0 + x, y0 + y), col + (255,))


def mascot_clip(im, x0, y0, **kw):
    """The mascot, its pixels outside the glass left out."""
    tmp = Image.new("RGBA", (W + 40, H), (0, 0, 0, 0)); mascot(tmp, x0 + 40, y0, **kw)
    for y in range(H):
        for x in range(W):
            px = tmp.getpixel((x + 40, y))
            if px[3] and GX0 < x < GX1 and GY0 < y < GY1: im.putpixel((x, y), px)


def lines(im):
    for y in range(GY0 + 1, GY1, 2):
        for x in range(GX0 + 1, GX1):
            r, g, b, a = im.getpixel((x, y)); im.putpixel((x, y), (int(r * .82), int(g * .82), int(b * .82), a))


def smudges(im, keep):
    for (x, y) in SMUDGES:
        if keep(x, y): im.putpixel((GX0 + x, GY0 + y), (46, 74, 67, 255))


def save(im, name):
    im.resize((W * S, H * S), Image.NEAREST).save(os.path.join(OUT, f"tv_{name}.png"), optimize=True)


def frame(blink=False, sparkle=True, cloth=None, wiped=0, at=(19, 15)):
    im, d = tv(True)
    smudges(im, lambda x, y: x > wiped)  # what the cloth has gone over is clean
    mascot_clip(im, at[0], at[1], blink=blink, sparkle=sparkle)
    if at[0] < 19:  # walking in: the glass's edge hides what is still outside
        for y in range(GY0, GY1 + 1):
            for x in range(0, GX0 + 1): im.putpixel((x, y), CAB + (255,))
    if cloth is not None:  # its arm out to the glass, the cloth in its hand, a shine behind it
        cx, cy = cloth
        hand = (21, 26) if cx < 32 else (42, 26)  # from the ear pad on that side
        d.line((hand[0], hand[1], cx, cy), fill=P['O'], width=1)
        d.rectangle((cx - 2, cy - 2, cx + 2, cy + 2), fill=(120, 190, 240), outline=(230, 240, 250))
        for k in range(3): d.line((cx - 6 - k * 3, cy - 4 + k, cx - 3 - k * 3, cy + 4 - k), fill=(70, 110, 100))
    lines(im)
    return im


os.makedirs(OUT, exist_ok=True)
save(frame(), "idle")
save(frame(blink=True), "blink")
save(frame(sparkle=False), "twinkle")
for i, (cx, cy, wiped) in enumerate([(14, 14, 10), (28, 10, 25), (44, 16, 40), (56, 30, 60)]):
    save(frame(cloth=(GX0 + cx, GY0 + cy), wiped=wiped), f"clean{i}")
save(frame(wiped=99), "clean")
# switched on (8 Nov: "coming on the TV from the side"): walking in from the left, a hop at each step
for i, x in enumerate((-20, -8, 4, 13)):
    save(frame(at=(x, 15 - (i % 2))), f"enter{i}")  # the glass clean, a moment
off, d = tv(False)
d.point((W // 2 - 8, H // 2), fill=(255, 255, 255)); d.point((W // 2 - 9, H // 2), fill=(120, 120, 120)); d.point((W // 2 - 7, H // 2), fill=(120, 120, 120))
save(off, "off")
print("frames in", os.path.normpath(OUT))

# talking on the widget itself (8 Nov): listening rings, a thought cloud filling with dots, its voice's waves, and snow
def talk(rings=0, dots=-1, waves=False, bars=None):
    im, d = tv(True)
    smudges(im, lambda x, y: False)
    if rings:
        for r in ([12] if rings == 1 else [12, 17]):
            d.ellipse((32 - r - 1, 27 - r, 32 + r - 1, 27 + r), outline=(253, 106, 4))
    mascot_clip(im, 19, 15, waves=waves, sparkle=not waves)
    if bars:  # its voice: three bars beside it, a different height each frame
        for k, h in enumerate(bars): d.rectangle((50 + k * 3, 27 - h, 51 + k * 3, 27 + h), fill=(255, 226, 160))
    if dots >= 0:
        d.rounded_rectangle((6, 6, 21, 13), 3, fill=(244, 241, 234))
        d.rectangle((17, 14, 18, 15), fill=(244, 241, 234)); d.point((20, 17), fill=(244, 241, 234))
        for k in range(dots + 1): d.rectangle((9 + k * 4, 9, 10 + k * 4, 10), fill=(59, 52, 48))
    lines(im)
    return im


save(talk(rings=1), "listen0"); save(talk(rings=2), "listen1")
for k in range(3): save(talk(dots=k), f"think{k}")
save(talk(bars=(2, 5, 3)), "speak0")
save(talk(waves=True, bars=(5, 2, 6)), "speak1")
import random
for k in range(3):
    im, d = tv(True); rnd = random.Random(k)
    for y in range(GY0 + 1, GY1):
        for x in range(GX0 + 1, GX1):
            v = rnd.randint(30, 230) if rnd.random() > 0.35 else 20
            im.putpixel((x, y), (v, v, v, 255))
    save(im, f"snow{k}")
