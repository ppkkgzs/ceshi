#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Render the 1.5.0-style vector launcher icon to PNG."""
from PIL import Image, ImageDraw

S = 512
img = Image.new("RGB", (S, S), "#1A73E8")
d = ImageDraw.Draw(img)

def scale(coords):
    xs = [p[0] / 108.0 * S for p in coords]
    ys = [p[1] / 108.0 * S for p in coords]
    return list(zip(xs, ys))

# folder body (white)
d.polygon(scale([(24,30),(48,30),(56,38),(84,38),(84,74),(24,74)]), fill="#FFFFFF")

# cyan play triangle
d.polygon(scale([(44,50),(34,58),(44,66)]), fill="#00BCD4")

# folder detail stripes (white)
d.polygon(scale([(50,50),(74,50),(74,54),(50,54)]), fill="#FFFFFF")
d.polygon(scale([(50,58),(74,58),(74,62),(50,62)]), fill="#FFFFFF")

img.save("/workspace/ic_launcher_1.5.0.png")
print("saved", img.size)