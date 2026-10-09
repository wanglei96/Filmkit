#!/usr/bin/env python3
"""反事实对照：如果多出来的那次 dither 是【独立】随机 TPDF（而非同一个 dhash），
体积会是多少？—— 用来定量隔离"相干叠加"本身的贡献。
同时生成 raw_indep.rgba。"""
import numpy as np
from PIL import Image

Image.MAX_IMAGE_PIXELS = None
SRC = "test_temp/FILMKIT_20261009-090810.jpg"
W, H = 3072, 4080

rgb = np.asarray(Image.open(SRC), dtype=np.uint8)
rng = np.random.default_rng(20261009)
# 两个独立 U[0,1) 之和 - 1 = TPDF，值域 [-1,1)，单位 LSB（与引擎同分布、不同实现）
d = (rng.random((H, W, 1), dtype=np.float32) + rng.random((H, W, 1), dtype=np.float32)) - np.float32(1.0)
print(f"独立 TPDF: min={d.min():.6f} max={d.max():.6f} std={d.std():.6f} "
      f"|d|>0.5={ (np.abs(d)>0.5).mean()*100:.3f}%")

out = np.rint(np.clip(rgb.astype(np.float32) + d, 0.0, 255.0)).astype(np.uint8)
rgba = np.empty((H, W, 4), dtype=np.uint8)
rgba[..., :3] = out
rgba[..., 3] = 255
rgba.tofile("tools/jpeg-dither-exp/raw_indep.rgba")
print("wrote raw_indep.rgba", rgba.nbytes)
