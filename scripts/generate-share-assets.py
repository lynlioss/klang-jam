#!/usr/bin/env python3
"""Rasterize the existing Klang logo and display font for invite PNGs (Pillow)."""
import base64
import json
import string
import zlib
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parent.parent
source = root / "scripts/share-assets"
output = root / "web/assets"
output.mkdir(parents=True, exist_ok=True)
background = Image.new("RGB", (1200, 630), "#000000")
logo = Image.open(source / "klang-logo.png").convert("RGBA")
logo = logo.crop((110, 110, 875, 875)).resize((400, 400), Image.Resampling.LANCZOS)
background.paste(logo, (70, 90), logo)
draw = ImageDraw.Draw(background)
font_path = str(source / "klang-display.ttf")
draw.text((270, 530), "Klang", font=ImageFont.truetype(font_path, 64), fill="#f5f5f5", anchor="mm")
# Keep non-code copy in the same plain, readable typeface used by the web page.
label_font = ImageFont.truetype("DejaVuSans.ttf", 27)
draw.text((835, 155), "INVITE CODE", font=label_font, fill="#aaa3b3", anchor="mm")
draw.text((835, 455), "Join this Jam on Klang!", font=label_font, fill="#d2cbd9", anchor="mm")

def compressed(value):
    return base64.b64encode(zlib.compress(value, 9)).decode("ascii")

font = ImageFont.truetype(font_path, 240)
glyphs = {}
for letter in string.ascii_lowercase + string.digits:
    bounds = font.getbbox(letter)
    width = max(bounds[2] - bounds[0], round(font.getlength(letter)))
    glyph = Image.new("L", (width, 260))
    # Preserve a common baseline; ascenders and short letters must align naturally.
    ImageDraw.Draw(glyph).text((-bounds[0], -25), letter, font=font, fill=255)
    glyphs[letter] = {"width": width, "height": glyph.height, "pixels": compressed(glyph.tobytes())}

(output / "share-data.json").write_text(json.dumps({"base": compressed(background.tobytes()), "glyphs": glyphs}, separators=(",", ":")) + "\n")
fallback = background.copy()
ImageDraw.Draw(fallback).rounded_rectangle((540, 110, 1140, 495), radius=20, fill="#000000")
ImageDraw.Draw(fallback).text((835, 310), "Jam", font=font, fill="#f5f5f5", anchor="mm")
fallback.save(output / "klang-share.png", optimize=True)
print("Generated", output)
