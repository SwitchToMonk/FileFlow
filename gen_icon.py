#!/usr/bin/env python3
"""Build FileFlow's launcher icons from the supplied folder artwork."""
from PIL import Image, ImageDraw

SRC = '/scratch/input/minimalist-flat-design-folder-icon-element-for-file-management-system-vector.jpg'
SIZES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}

im = Image.open(SRC).convert('RGBA')
px = im.load()
w, h = im.size
# the artwork sits on flat white — knock it out, leaving the folder itself
for y in range(h):
    for x in range(w):
        r, g, b, a = px[x, y]
        if r > 240 and g > 240 and b > 240:
            px[x, y] = (r, g, b, 0)

bbox = im.getbbox()
art = im.crop(bbox)
aw, ah = art.size
print('art trimmed to', art.size)

def square(art, size, fill=0.78):
    """centre the art on a transparent square canvas"""
    box = int(size * fill)
    scale = min(box / art.width, box / art.height)
    nw, nh = max(1, int(art.width * scale)), max(1, int(art.height * scale))
    scaled = art.resize((nw, nh), Image.LANCZOS)
    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    canvas.paste(scaled, ((size - nw) // 2, (size - nh) // 2), scaled)
    return canvas

def rounded(art, size):
    """for ic_launcher_round: art kept well inside a circle so nothing clips"""
    inner = square(art, size, 0.58)
    mask = Image.new('L', (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.ellipse((0, 0, size - 1, size - 1), fill=255)
    out = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    out.paste(inner, (0, 0), inner)
    out.putalpha(mask)
    return out

for name, size in SIZES.items():
    d = f'res/mipmap-{name}'
    square(art, size).save(f'{d}/ic_launcher.png')
    rounded(art, size).save(f'{d}/ic_launcher_round.png')

# a big one for reference / the repo
square(art, 512).save('assets/www/logo.png')
print('icons written for', ', '.join(SIZES))
