#!/usr/bin/env python3
"""可视化证据：同一块平坦区（无细节）与一块纹理区，原片 vs 导出的放大对比。
若"细节更好"为真，增量应集中在纹理区；若为噪声，增量在平坦区同样显著。"""
import numpy as np
from PIL import Image, ImageDraw

Image.MAX_IMAGE_PIXELS = None
P = "test_temp/"
A  = np.asarray(Image.open(P+"FILMKIT_20261009-090810.jpg"), dtype=np.uint8)
BR = np.asarray(Image.open(P+"FILMKIT_20261009-090827_edit.jpg"), dtype=np.uint8)
H, W = A.shape[:2]

def luma(rgb):
    r = rgb[...,0].astype(np.int32); g = rgb[...,1].astype(np.int32); b = rgb[...,2].astype(np.int32)
    return (77*r + 150*g + 29*b) >> 8
LA = luma(A)

K = 240
best_flat = None; best_tex = None
for r in range(0, H-K, K):
    for c in range(0, W-K, K):
        s = float(LA[r:r+K, c:c+K].std())
        if best_flat is None or s < best_flat[0]: best_flat = (s, r, c)
        if best_tex is None or s > best_tex[0]: best_tex = (s, r, c)
print(f"最平坦块: std={best_flat[0]:.3f} @ ({best_flat[1]},{best_flat[2]})")
print(f"最纹理块: std={best_tex[0]:.3f} @ ({best_tex[1]},{best_tex[2]})")

SCALE = 2
def tile(img, r, c, label):
    t = img[r:r+K, c:c+K]
    im = Image.fromarray(t).resize((K*SCALE, K*SCALE), Image.NEAREST)
    canvas = Image.new("RGB", (K*SCALE, K*SCALE+22), (16,16,16))
    canvas.paste(im, (0, 22))
    ImageDraw.Draw(canvas).text((6, 6), label, fill=(255,255,255))
    return canvas

def diff_tile(r, c):
    d = np.abs(BR[r:r+K, c:c+K].astype(np.int16) - A[r:r+K, c:c+K].astype(np.int16))
    v = np.clip(d.max(axis=2) * 40, 0, 255).astype(np.uint8)   # ×40 增益
    im = Image.fromarray(np.stack([v]*3, -1)).resize((K*SCALE, K*SCALE), Image.NEAREST)
    canvas = Image.new("RGB", (K*SCALE, K*SCALE+22), (16,16,16))
    canvas.paste(im, (0, 22))
    ImageDraw.Draw(canvas).text((6, 6), "|export-photo| x40", fill=(255,255,255))
    return canvas

for nm, (s, r, c) in (("flat", best_flat), ("tex", best_tex)):
    row = [tile(A, r, c, f"photo  (std={s:.2f})"),
           tile(BR, r, c, "export"),
           diff_tile(r, c)]
    sheet = Image.new("RGB", (K*SCALE*3 + 16, K*SCALE+22), (16,16,16))
    for i, t in enumerate(row):
        sheet.paste(t, (i*(K*SCALE+8), 0))
    sheet.save(f"tools/jpeg-dither-exp/viz_{nm}.png")
    print(f"wrote tools/jpeg-dither-exp/viz_{nm}.png")

# 统计这两块区域
for nm, (s, r, c) in (("平坦块", best_flat), ("纹理块", best_tex)):
    d = np.abs(BR[r:r+K, c:c+K].astype(np.int16) - A[r:r+K, c:c+K].astype(np.int16))
    la = luma(A[r:r+K, c:c+K]).astype(np.float64); lb = luma(BR[r:r+K, c:c+K]).astype(np.float64)
    def lstd(g, k=5):
        ii = np.zeros((g.shape[0]+1, g.shape[1]+1)); ii[1:,1:] = g.cumsum(0).cumsum(1)
        Y, X = np.meshgrid(np.arange(0, g.shape[0]-k+1), np.arange(0, g.shape[1]-k+1), indexing="ij")
        ss = ii[Y+k,X+k]-ii[Y,X+k]-ii[Y+k,X]+ii[Y,X]
        s2 = np.zeros_like(ii); s2[1:,1:] = (g*g).cumsum(0).cumsum(1)
        ss2 = s2[Y+k,X+k]-s2[Y,X+k]-s2[Y+k,X]+s2[Y,X]
        return np.sqrt(np.maximum(ss2/(k*k) - (ss/(k*k))**2, 0))
    ca, cb = lstd(la), lstd(lb)
    print(f"  {nm} @({r},{c}) luma std={s:.2f}: 局部5x5std 原片={ca.mean():.4f} 导出={cb.mean():.4f} "
          f"(Δ={cb.mean()-ca.mean():+.4f}); mean|Δ|={d.mean():.4f}")
