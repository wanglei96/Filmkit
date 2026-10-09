#!/usr/bin/env python3
"""核心问题：拍照链路的 dither 与编辑器链路的 dither 是否【相干】（同坐标同图案）？

逻辑：A = round(S + δ_photo)（拍照链路已固化）。在"原始信号平坦"的区域，
      x = A - localmean(A) ≈ round(f + δ_photo) - 常数，即 x 主要由 δ_photo 驱动。
      y = BR - A ≈ round(δ_edit)，已由诊断 2 证实 δ_edit 用 H2 坐标。
      ⇒ corr(x, δ_H2) > 0  ⟺ δ_photo 也是 H2 ⟺ 相干
         corr(x, δ_H1) ≈ 0  ⟺ 拍照用的是 H1（与编辑器不同）⟺ 独立
阳性对照：out_C - A = round(δ_H2)（我自己构造，必定相干），其 corr 给出上界。
"""
import numpy as np
from PIL import Image
import json

Image.MAX_IMAGE_PIXELS = None
A  = np.asarray(Image.open("test_temp/FILMKIT_20261009-090810.jpg"), dtype=np.uint8)
BR = np.asarray(Image.open("test_temp/FILMKIT_20261009-090827_edit.jpg"), dtype=np.uint8)
OC = np.asarray(Image.open("tools/jpeg-dither-exp/out_C.jpg"), dtype=np.uint8)
H, W = A.shape[:2]
f32 = np.float32

def dhash(px, py):
    def fract(a): return a - np.floor(a).astype(np.float32)
    p3x = fract(px * f32(0.1031)); p3y = fract(py * f32(0.1031)); p3z = p3x.copy()
    d = (p3x*(p3y+f32(33.33)) + p3y*(p3z+f32(33.33)) + p3z*(p3x+f32(33.33)))
    p3x = p3x + d; p3y = p3y + d; p3z = p3z + d
    return fract((p3x + p3y) * p3z)

def delta(gx, gy):
    return np.asarray((dhash(gx, gy) + dhash(gx + f32(37.0), gy + f32(17.0))) - f32(1.0),
                      dtype=np.float32)

# 全图坐标
rows = np.arange(H, dtype=np.float32)[:, None]
cols = np.arange(W, dtype=np.float32)[None, :]
d_H2 = delta(cols + f32(0.5), rows + f32(0.5))                 # 编辑器实测坐标
d_H1 = delta(cols + f32(0.5), f32(H) - rows - f32(0.5))        # GL 左下原点
d_H3 = delta(f32(W) - cols - f32(0.5), f32(H) - rows - f32(0.5))
np.save("tools/jpeg-dither-exp/d_H2.npy", d_H2)

print("== 区域选择：原始信号平坦区 ==")
def luma(rgb):
    r = rgb[...,0].astype(np.int32); g = rgb[...,1].astype(np.int32); b = rgb[...,2].astype(np.int32)
    return (77*r + 150*g + 29*b) >> 8
LA = luma(A)
def local_std(g, k=5):
    g = g.astype(np.float64)
    ii = np.zeros((g.shape[0]+1, g.shape[1]+1)); ii[1:,1:] = g.cumsum(0).cumsum(1)
    ii2 = np.zeros_like(ii); ii2[1:,1:] = (g*g).cumsum(0).cumsum(1)
    Y, X = np.meshgrid(np.arange(0, g.shape[0]-k+1), np.arange(0, g.shape[1]-k+1), indexing="ij")
    s  = ii[Y+k,X+k]-ii[Y,X+k]-ii[Y+k,X]+ii[Y,X]
    s2 = ii2[Y+k,X+k]-ii2[Y,X+k]-ii2[Y+k,X]+ii2[Y,X]
    n = float(k*k)
    return np.sqrt(np.maximum(s2/n - (s/n)**2, 0.0))
cA = local_std(LA)
# 对齐：cA[r,c] 对应原图 [r+2, c+2]
flat = np.zeros((H, W), bool); flat[2:-2, 2:-2] = cA < 1.0
print(f"  平坦区(cA<1) 占比 = {flat.mean()*100:.2f}%  ({int(flat.sum()):,} px)")

# 局部均值（大窗口平均掉 dither，保留 S）
def boxmean(g, k=15):
    g = g.astype(np.float64)
    ii = np.zeros((g.shape[0]+1, g.shape[1]+1)); ii[1:,1:] = g.cumsum(0).cumsum(1)
    Y, X = np.meshgrid(np.arange(0, g.shape[0]-k+1), np.arange(0, g.shape[1]-k+1), indexing="ij")
    s = ii[Y+k,X+k]-ii[Y,X+k]-ii[Y+k,X]+ii[Y,X]
    m = np.zeros_like(g); m[k//2:k//2+s.shape[0], k//2:k//2+s.shape[1]] = s/(k*k)
    return m
mA = np.stack([boxmean(A[...,ci]) for ci in range(3)], -1)

x = (A.astype(np.float64) - mA)          # A 相对局部均值的偏差（主要是 δ_photo）
print(f"  x=A-localmean: std={x.std():.4f}  平坦区内 std={x[flat].std():.4f}")

def corr(a, b, mask):
    aa = a[mask]; bb = b[mask]
    aa = aa - aa.mean(); bb = bb - bb.mean()
    den = np.sqrt((aa*aa).sum() * (bb*bb).sum())
    return float((aa*bb).sum()/den) if den > 0 else 0.0

print("\n== corr( A 的 dither 残差 x , δ_hypothesis )  —— 平坦区内 ==")
res = {}
for nm, d in (("δ_H2 (row+0.5, 编辑器实测坐标)", d_H2),
              ("δ_H1 (H-row-0.5, GL左下原点)", d_H1),
              ("δ_H3 (双翻转)", d_H3)):
    dd = np.repeat(d[..., None], 3, axis=2)
    r = corr(x, dd, flat)
    print(f"  corr(x, {nm:34s}) = {r:+.5f}")
    res[nm] = r

print("\n== 阳性对照：corr( out_C - A , δ_H2 ) ==")
yC = (OC.astype(np.float64) - A.astype(np.float64))
r_pos = corr(yC, np.repeat(d_H2[...,None],3,2), flat)
print(f"  corr(out_C-A, δ_H2) = {r_pos:+.5f}   ← 已知必定相干，给出相关系数上界")
res["positive_control_outC"] = r_pos

print("\n== 实际导出：corr( BR - A , δ_H2 ) ==")
yBR = (BR.astype(np.float64) - A.astype(np.float64))
r_br = corr(yBR, np.repeat(d_H2[...,None],3,2), flat)
print(f"  corr(BR-A, δ_H2) = {r_br:+.5f}")
res["BR_minus_A_vs_H2"] = r_br

# 关键判据：用 δ_H2 预测的 round(δ) 与实际 (BR-A) 的一致性，按 |δ| 分档
print("\n== 关键判据：δ_H2 分档 vs 实际 (BR-A) 与 (out_C-A) ==")
for lo, hi, nm in ((-1.01,-0.5,"δ<-0.5"), (-0.5,-0.25,"-0.5..-0.25"), (-0.25,0.25,"-0.25..0.25"),
                   (0.25,0.5,"0.25..0.5"), (0.5,1.01,"δ>+0.5")):
    m = flat & (d_H2 >= lo) & (d_H2 < hi)
    if m.sum() == 0: continue
    print(f"  {nm:12s} n={int(m.sum()):8,}  mean(BR-A)={yBR[m].mean():+.4f}  "
          f"mean(out_C-A)={yC[m].mean():+.4f}  理论 round(δ)={np.rint((lo+hi)/2 if lo>=-0.5 else -1):+.0f}")

json.dump({"corr": res, "flat_pct": float(flat.mean()*100)}, open("tools/jpeg-dither-exp/07_coherence.json","w"),
          ensure_ascii=False, indent=2)
print("\n已写出 tools/jpeg-dither-exp/07_coherence.json")
