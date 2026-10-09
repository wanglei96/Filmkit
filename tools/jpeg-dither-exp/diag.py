#!/usr/bin/env python3
"""诊断 1：真实导出更接近 out_B（纯二次编码）还是 out_C（二次编码+dither）？
诊断 2：dither 坐标假设搜索 —— 用最敏感的统计量
        E[Δ | δ>0.5] - E[Δ | δ<-0.5]（正确假设下理论值 ≈ +2.0）
        在 4 种翻转组合 × 小范围像素偏移上搜索。"""
import numpy as np
from PIL import Image
import json

Image.MAX_IMAGE_PIXELS = None
A  = np.asarray(Image.open("test_temp/FILMKIT_20261009-090810.jpg"), dtype=np.uint8)
BR = np.asarray(Image.open("test_temp/FILMKIT_20261009-090827_edit.jpg"), dtype=np.uint8)
OB = np.asarray(Image.open("tools/jpeg-dither-exp/out_B.jpg"), dtype=np.uint8)
OC = np.asarray(Image.open("tools/jpeg-dither-exp/out_C.jpg"), dtype=np.uint8)
OD = np.asarray(Image.open("tools/jpeg-dither-exp/out_D.jpg"), dtype=np.uint8)
H, W = A.shape[:2]
print(f"shape A={A.shape} BR={BR.shape} OB={OB.shape} OC={OC.shape} OD={OD.shape}")

def diffstat(X, Y, name):
    d = X.astype(np.int16) - Y.astype(np.int16)
    ad = np.abs(d)
    print(f"  {name:28s} =0 {100*(ad==0).mean():7.3f}%  =1 {100*(ad==1).mean():7.3f}%  "
          f"=2 {100*(ad==2).mean():6.3f}%  >=3 {100*(ad>=3).mean():6.3f}%  "
          f"mean|Δ| {ad.mean():.4f}  RMS {np.sqrt((d.astype(np.float64)**2).mean()):.4f}  max {ad.max()}")
    return {"eq0": float(100*(ad==0).mean()), "eq1": float(100*(ad==1).mean()),
            "eq2": float(100*(ad==2).mean()), "ge3": float(100*(ad>=3).mean()),
            "mean_abs": float(ad.mean()),
            "rms": float(np.sqrt((d.astype(np.float64)**2).mean())), "max": int(ad.max())}

print("\n== 诊断 1：真实导出 vs 各实验产物 ==")
res = {}
res["BR_vs_A"]  = diffstat(BR, A,  "真实导出 - 原片")
res["OB_vs_A"]  = diffstat(OB, A,  "out_B(纯二次编码) - 原片")
res["OC_vs_A"]  = diffstat(OC, A,  "out_C(+dither) - 原片")
res["OD_vs_A"]  = diffstat(OD, A,  "out_D(独立dither) - 原片")
res["BR_vs_OB"] = diffstat(BR, OB, "真实导出 - out_B")
res["BR_vs_OC"] = diffstat(BR, OC, "真实导出 - out_C")
res["BR_vs_OD"] = diffstat(BR, OD, "真实导出 - out_D")
res["OC_vs_OB"] = diffstat(OC, OB, "out_C - out_B")

# ---------------- 诊断 2：dither 坐标假设搜索 ----------------
print("\n== 诊断 2：dither 坐标假设搜索 ==")
f32 = np.float32
def dhash(px, py):
    def fract(a): return a - np.floor(a).astype(np.float32)
    p3x = fract(px * f32(0.1031)); p3y = fract(py * f32(0.1031)); p3z = p3x.copy()
    d = (p3x*(p3y+f32(33.33)) + p3y*(p3z+f32(33.33)) + p3z*(p3x+f32(33.33)))
    p3x = p3x + d; p3y = p3y + d; p3z = p3z + d
    return fract((p3x + p3y) * p3z)

# 子区域（绝对坐标，保证 dither 图案位置正确）
R0, R1, C0, C1 = 1200, 2200, 800, 1800
sub = (slice(R0, R1), slice(C0, C1))
rows = np.arange(R0, R1, dtype=np.float32)[:, None]
cols = np.arange(C0, C1, dtype=np.float32)[None, :]
dreal = (BR[sub].astype(np.int16) - A[sub].astype(np.int16))
print(f"  子区域 {R1-R0}x{C1-C0} = {(R1-R0)*(C1-C0):,} px; 子区域内 mean|Δ|={np.abs(dreal).mean():.4f}")

def test(name, gx, gy):
    a = dhash(gx, gy)
    b = dhash(gx + f32(37.0), gy + f32(17.0))
    dl = (a + b) - f32(1.0)                       # ∈[-1,1] LSB
    d = np.asarray(dl, dtype=np.float32)
    hi = d > 0.5; lo = d < -0.5
    n_hi = int(hi.sum()); n_lo = int(lo.sum())
    if n_hi == 0 or n_lo == 0:
        print(f"  {name:34s} n_hi={n_hi} n_lo={n_lo}  (退化)"); return None
    # 用三个通道一起平均（dither 对三通道相同）
    ehi = float(dreal[hi].mean()); elo = float(dreal[lo].mean())
    sep = ehi - elo
    # 命中率：预测 round(d) 与实际 Δ 相同
    pred = np.rint(d).astype(np.int16)[..., None]
    hit = float((pred == dreal).mean() * 100)
    print(f"  {name:34s} E[Δ|δ>+0.5]={ehi:+.4f}  E[Δ|δ<-0.5]={elo:+.4f}  "
          f"分离度={sep:+.4f} (理论+2)  命中率={hit:5.2f}%  n_hi={n_hi}")
    return {"name": name, "ehi": ehi, "elo": elo, "sep": sep, "hit": hit, "n_hi": n_hi}

cands = []
# 4 种翻转组合（无额外偏移）
cands.append(("H1 gy=H-row-.5 gx=col+.5",  cols + f32(0.5), (f32(H) - rows - f32(0.5))))
cands.append(("H2 gy=row+.5     gx=col+.5", cols + f32(0.5), (rows + f32(0.5))))
cands.append(("H3 gy=H-row-.5 gx=W-col-.5", (f32(W) - cols - f32(0.5)), (f32(H) - rows - f32(0.5))))
cands.append(("H4 gy=row+.5   gx=W-col-.5", (f32(W) - cols - f32(0.5)), (rows + f32(0.5))))
outc = []
for nm, gx, gy in cands:
    r = test(nm, gx, gy)
    if r: outc.append(r)

print("\n  -- 加像素偏移搜索（在最佳组合上）--")
base = max(outc, key=lambda r: abs(r["sep"])) if outc else None
print(f"  最佳无偏移组合 = {base['name'] if base else 'N/A'}")
for dy in (-2, -1, 0, 1, 2):
    for dx in (-2, -1, 0, 1, 2):
        gx = cols + f32(0.5) + f32(dx)
        gy = f32(H) - rows - f32(0.5) + f32(dy)
        a = dhash(gx, gy); b = dhash(gx + f32(37.0), gy + f32(17.0))
        d = np.asarray((a + b) - f32(1.0), dtype=np.float32)
        hi = d > 0.5; lo = d < -0.5
        if hi.sum() == 0 or lo.sum() == 0: continue
        sep = float(dreal[hi].mean() - dreal[lo].mean())
        if abs(sep) > 0.25:
            print(f"    dx={dx:+d} dy={dy:+d}  分离度={sep:+.4f}  "
                  f"E[Δ|hi]={dreal[hi].mean():+.4f} E[Δ|lo]={dreal[lo].mean():+.4f}")

# 参考基线：完全随机的 dither（应给出 分离度 ≈ 0）
rng = np.random.default_rng(7)
drnd = (rng.random(dreal.shape[:2], dtype=np.float32) + rng.random(dreal.shape[:2], dtype=np.float32)) - f32(1.0)
hi = drnd > 0.5; lo = drnd < -0.5
print(f"\n  随机 dither 基线: 分离度={float(dreal[hi].mean()-dreal[lo].mean()):+.4f} (应≈0)")
# 参考基线：用 out_C - out_B 的差（已知真实含 dither 的效果）
dcb = (OC[sub].astype(np.int16) - OB[sub].astype(np.int16))
hi = drnd > 0.5; lo = drnd < -0.5
print(f"  随机 dither 对 (out_C-out_B) 基线: 分离度={float(dcb[hi].mean()-dcb[lo].mean()):+.4f}")

json.dump({"diag1": res, "diag2": outc}, open("tools/jpeg-dither-exp/06_diag.json", "w"),
          ensure_ascii=False, indent=2)
print("\n已写出 tools/jpeg-dither-exp/06_diag.json")
