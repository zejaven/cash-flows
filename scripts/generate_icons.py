"""Renders the PWA PNG icons with the same geometry as frontend/public/icon.svg.

Pillow cannot rasterize SVG, so the shapes are drawn directly. Run once from the
repo root; the PNGs are committed:

    python scripts/generate_icons.py
"""
from pathlib import Path

from PIL import Image, ImageDraw

BG = "#0d1017"
MINT = "#3ddc97"
BLUE = "#5a83f7"
BASE = 512  # design grid of icon.svg

OUT_DIR = Path(__file__).resolve().parent.parent / "frontend" / "public"


def draw_arrows(draw: ImageDraw.ImageDraw, scale: float, offset: float = 0.0) -> None:
    def s(value: float) -> float:
        return value * scale + offset

    # top arrow: shaft + right-pointing head, mint
    draw.rounded_rectangle([s(96), s(176), s(336), s(216)], radius=s(20), fill=MINT)
    draw.polygon([(s(320), s(136)), (s(416), s(196)), (s(320), s(256))], fill=MINT)
    # bottom arrow: shaft + left-pointing head, blue
    draw.rounded_rectangle([s(176), s(296), s(416), s(336)], radius=s(20), fill=BLUE)
    draw.polygon([(s(192), s(256)), (s(96), s(316)), (s(192), s(376))], fill=BLUE)


def tile_icon(size: int) -> Image.Image:
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    scale = size / BASE
    draw.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(96 * scale), fill=BG)
    draw_arrows(draw, scale)
    return image


def maskable_icon(size: int) -> Image.Image:
    """Full-bleed background, artwork inside the 80% safe zone."""
    image = Image.new("RGBA", (size, size), BG)
    draw = ImageDraw.Draw(image)
    scale = size / BASE * 0.8
    offset = size * 0.1
    draw_arrows(draw, scale, offset)
    return image


def main() -> None:
    tile_icon(192).save(OUT_DIR / "icon-192.png")
    tile_icon(512).save(OUT_DIR / "icon-512.png")
    maskable_icon(512).save(OUT_DIR / "icon-512-maskable.png")
    print(f"icons written to {OUT_DIR}")


if __name__ == "__main__":
    main()
