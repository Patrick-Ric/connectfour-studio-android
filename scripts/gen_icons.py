#!/usr/bin/env python3
"""Render the program icon (same geometry as the desktop icon
data/connectfour-studio.png: blue rounded square, 4x4 stone grid) as PNG:
launcher fallbacks for API < 26 (mipmap-*dpi) and the F-Droid icon.

Usage: python3 scripts/gen_icons.py   (needs Pillow)
"""
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BLUE, LIGHT, YELLOW, RED = (30, 80, 190), (235, 240, 250), (245, 200, 30), (230, 40, 40)
GRID = [[LIGHT] * 4, [LIGHT, LIGHT, YELLOW, LIGHT], [LIGHT, RED, YELLOW, LIGHT], [RED, RED, YELLOW, RED]]


def render(size):
    ss = 8  # supersampling
    s = size * ss / 256.0
    im = Image.new("RGBA", (size * ss, size * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([8 * s, 8 * s, 248 * s, 248 * s], radius=26 * s, fill=BLUE)
    for row in range(4):
        for col in range(4):
            cx, cy = (44.5 + 56 * col) * s, (44.5 + 56 * row) * s
            r = 22.5 * s
            d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=GRID[row][col])
    return im.resize((size, size), Image.LANCZOS)


def main():
    for name, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        d = os.path.join(ROOT, "app", "src", "main", "res", f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        render(px).save(os.path.join(d, "ic_launcher.png"), optimize=True)
    for lang in ("en-US", "de-DE"):
        d = os.path.join(ROOT, "fastlane", "metadata", "android", lang, "images")
        os.makedirs(d, exist_ok=True)
        render(512).save(os.path.join(d, "icon.png"), optimize=True)


if __name__ == "__main__":
    main()
