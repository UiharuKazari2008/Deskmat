"""Builds the launcher icon's background layer from the generated artwork (desk_walnut_source.png,
made with gpt-image-2). The chosen framing is the mat's bottom-left corner; the adaptive icon shows the
middle 72 of 108 dp, so the layer adds 18 dp of bleed around that framing, mirroring the wood where it
runs past the source image."""
import os
from PIL import Image, ImageOps

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "..", "app", "src", "main", "res")
src = Image.open(os.path.join(HERE, "desk_walnut_source.png")).convert("RGB")
W, H = src.size

VISIBLE = (20, 330, 640, 950)  # the framing picked: bottom-left corner of the mat
cx, cy = (VISIBLE[0] + VISIBLE[2]) / 2, (VISIBLE[1] + VISIBLE[3]) / 2
full = (VISIBLE[2] - VISIBLE[0]) * 108 / 72
box = [round(cx - full / 2), round(cy - full / 2), round(cx + full / 2), round(cy + full / 2)]

# Fill the bleed past the source's edges by reflecting wood only: on the left the mat starts about
# 85 px in, so reflections stay inside that strip (ping-pong); the other edges are plain wood.
import numpy as np

SAFE_LEFT = 75
arr = np.asarray(src)


def reflect(v, lo, hi):
    """Maps v into [lo, hi) by bouncing off the ends."""
    span = hi - lo
    v = (v - lo) % (2 * span)
    return lo + np.where(v < span, v, 2 * span - 1 - v)


xs = np.arange(box[0], box[2])
ys = np.arange(box[1], box[3])
xs = np.where(xs < 0, reflect(xs, 0, SAFE_LEFT), np.where(xs >= W, reflect(xs, 0, W), xs))
ys = np.where((ys < 0) | (ys >= H), reflect(ys, 0, H), ys)
layer = Image.fromarray(arr[np.ix_(ys, xs)])

for density, px in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
    d = os.path.join(RES, f"mipmap-{density}")
    os.makedirs(d, exist_ok=True)
    layer.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher_background.png"), optimize=True)
print("bleed box", box)
