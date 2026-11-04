"""Renders the Library of Alexandria icon (a classical column portico) at every launcher density.

Usage: python3 scripts/make_icon.py   (requires Pillow)
"""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
BG = (24, 33, 56)
GOLD = (222, 178, 92)
LIGHT = (245, 226, 180)


def render(size: int) -> Image.Image:
    s = size * 4  # supersample for smooth edges
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, s - 1, s - 1], radius=int(s * 0.22), fill=BG)
    u = s / 24.0
    # pediment
    d.polygon([(4 * u, 8.2 * u), (12 * u, 3.6 * u), (20 * u, 8.2 * u)], fill=GOLD)
    d.rectangle([4 * u, 8.6 * u, 20 * u, 9.8 * u], fill=GOLD)
    # columns
    for x in (5.4, 9.2, 13.0, 16.8):
        d.rectangle([x * u, 10.4 * u, (x + 1.8) * u, 17.6 * u], fill=LIGHT)
    # base steps
    d.rectangle([4 * u, 18.0 * u, 20 * u, 19.2 * u], fill=GOLD)
    d.rectangle([3 * u, 19.6 * u, 21 * u, 20.8 * u], fill=GOLD)
    return img.resize((size, size), Image.LANCZOS)


for density, px in SIZES.items():
    out = ROOT / "extension" / "res" / f"mipmap-{density}" / "ic_launcher.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    render(px).save(out)
render(512).save(ROOT / "docs" / "icon-512.png")
print("icons written")
