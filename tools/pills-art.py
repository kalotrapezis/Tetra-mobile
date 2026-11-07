# Pills' Home art (7 Nov 2026), drawn in the other tiles' manner until the user gives one: a soft pink tile, a cream
# capsule across it, half of it rose, and a round tablet. Usage: python3 tools/pills-art.py app/src/main/res/drawable-nodpi/pills_art.png
import sys
from PIL import Image, ImageDraw, ImageFilter
S = 4 * 627
def lerp(a, b, t): return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))
tile = Image.new("RGBA", (S, S), (0, 0, 0, 0))
grad = Image.new("RGBA", (S, S))
gd = ImageDraw.Draw(grad)
for y in range(S): gd.line([(0, y), (S, y)], fill=lerp((250, 120, 160), (226, 66, 118), y / S) + (255,))
mask = Image.new("L", (S, S), 0); ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.24), fill=255)
tile.paste(grad, (0, 0), mask)
def capsule(size, angle):
    w, h = size
    c = Image.new("RGBA", (w, h), (0, 0, 0, 0)); d = ImageDraw.Draw(c)
    d.rounded_rectangle([0, 0, w - 1, h - 1], radius=h // 2, fill=(250, 244, 230, 255))
    half = Image.new("L", (w, h), 0); ImageDraw.Draw(half).rectangle([w // 2, 0, w, h], fill=255)
    rose = Image.new("RGBA", (w, h), (196, 40, 92, 255)); body = Image.new("L", (w, h), 0)
    ImageDraw.Draw(body).rounded_rectangle([0, 0, w - 1, h - 1], radius=h // 2, fill=255)
    from PIL import ImageChops
    c.paste(rose, (0, 0), ImageChops.multiply(half, body))
    d.line([(w // 2, 0), (w // 2, h)], fill=(160, 30, 75, 255), width=max(2, h // 40))
    d.rounded_rectangle([int(w * 0.1), int(h * 0.16), int(w * 0.44), int(h * 0.3)], radius=h // 12, fill=(255, 255, 255, 170))  # shine
    return c.rotate(angle, expand=True, resample=Image.BICUBIC)
def shadowed(img, pos, base):
    m = S // 15  # room around it, so the blur is not cut square
    a = Image.new("L", (img.width + 2 * m, img.height + 2 * m), 0); a.paste(img.getchannel("A").point(lambda v: v * 0.45), (m, m))
    sh = Image.new("RGBA", a.size, (90, 10, 40, 0)); sh.putalpha(a.filter(ImageFilter.GaussianBlur(S // 60)))
    base.alpha_composite(sh, (pos[0] - m + S // 70, pos[1] - m + S // 45)); base.alpha_composite(img, pos)
cap = capsule((int(S * 0.62), int(S * 0.24)), 38)
shadowed(cap, ((S - cap.width) // 2 - S // 18, (S - cap.height) // 2 - S // 22), tile)
r = int(S * 0.17)
tab = Image.new("RGBA", (r, r), (0, 0, 0, 0)); td = ImageDraw.Draw(tab)
td.ellipse([0, 0, r - 1, r - 1], fill=(250, 244, 230, 255)); td.line([(r * 0.22, r / 2), (r * 0.78, r / 2)], fill=(210, 190, 170, 255), width=r // 14)
shadowed(tab, (int(S * 0.6), int(S * 0.6)), tile)
tile.putalpha(Image.composite(tile.getchannel("A"), Image.new("L", (S, S), 0), mask))  # the shadows kept inside the tile
tile.resize((627, 627), Image.LANCZOS).save(sys.argv[1], optimize=True)
