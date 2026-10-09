#!/usr/bin/env python3
"""第 5 项修正版：局部对比度必须用 float64 积分图（float32 在 12.5M 像素的
cumsum 上 ULP≈256，方差会被灾难性抵消吞掉）。同时做 dither 预测 vs 实测的逐像素验证。"""
import numpy as np, json
from PIL import Image

Image.MAX_IMAGE_PIXELS = None
A = np.asarray(Image.open("test_temp/FILMKIT_20261009-090810.jpg"), dtype=np.uint8)
B = np.asarray(Image.open("test_temp/FILMKIT_20261009-090827_edit.jpg"), dtype=np.uint8)
out = {}

def luma(rgb):
    r = rgb[..., 0].astype(np.int32); g = rgb[..., 1].astype(np.int32); b = rgb[..., 2].astype(np.int32)
    return (77 * r + 150 * g + 29 * b) >> 8

LA = luma(A); LB = luma(B)

def local_std(gray, k=5):
    g = gray.astype(np.float64)
    ii = np.zeros((g.shape[0] + 1, g.shape[1] + 1), np.float64)
    ii[1:, 1:] = g.cumsum(0).cumsum(1)
    ii2 = np.zeros_like(ii)
    ii2[1:, 1:] = (g * g).cumsum(0).cumsum(1)
    H, W = g.shape
    ys = np.arange(0, H - k + 1); xs = np.arange(0, W - k + 1)
    Y, X = np.meshgrid(ys, xs, indexing="ij")
    s = ii[Y + k, X + k] - ii[Y, X + k] - ii[Y + k, X] + ii[Y, X]
    s2 = ii2[Y + k, X + k] - ii2[Y, X + k] - ii2[Y + k, X] + ii2[Y, X]
    n = float(k * k)
    var = np.maximum(s2 / n - (s / n) ** 2, 0.0)
    return np.sqrt(var)

print("== 第 5 项（修正：float64 积分图）5x5 局部 std ==")
cA = local_std(LA); cB = local_std(LB)
qs = [1, 10, 25, 50, 75, 90, 99]
qA = np.percentile(cA, qs); qB = np.percentile(cB, qs)
print(f"  分位 {qs}")
print(f"  A = {[round(float(x),4) for x in qA]}")
print(f"  B = {[round(float(x),4) for x in qB]}")
print(f"  B-A = {[round(float(b-a),4) for a,b in zip(qA,qB)]}")
out["local_std"] = {"q": qs, "A": [float(x) for x in qA], "B": [float(x) for x in qB],
                    "diff": [float(b - a) for a, b in zip(qA, qB)]}
print(f"  全图 5x5 局部 std 均值: A={cA.mean():.5f}  B={cB.mean():.5f}  (Δ={cB.mean()-cA.mean():+.5f})")
out["local_std"]["mean_A"] = float(cA.mean()); out["local_std"]["mean_B"] = float(cB.mean())

# 分区：平坦区（A 的局部 std 极小）
d = np.abs(A.astype(np.int16) - B.astype(np.int16))
c = cA
for lo, hi, nm in ((0.0, 0.5, "极平坦 0<=cA<0.5"), (0.5, 1.0, "平坦 0.5<=cA<1"),
                   (1.0, 4.0, "弱纹理 1<=cA<4"), (4.0, 16.0, "中纹理 4<=cA<16"),
                   (16.0, 1e9, "强纹理 cA>=16")):
    m = (c >= lo) & (c < hi)
    n = int(m.sum())
    if n == 0: continue
    dm = d[2:-2, 2:-2][m]
    hf = (cB[m] - cA[m])
    print(f"  {nm:22s} 占比 {n/d[1:-1,1:-1].size*100:6.2f}%  mean|Δ|={dm.mean():.4f}  "
          f"|Δ|>=1={(dm>=1).mean()*100:6.2f}%  |Δ|>=2={(dm>=2).mean()*100:5.3f}%  "
          f"局部std Δ均值={hf.mean():+.4f}")
    out.setdefault("region", {})[nm] = {"pct": n / d[2:-2, 2:-2].size * 100,
                                        "mean_abs": float(dm.mean()),
                                        "ge1_pct": float((dm >= 1).mean() * 100),
                                        "ge2_pct": float((dm >= 2).mean() * 100),
                                        "localstd_delta": float(hf.mean())}

# ---------- 决定性验证：用 dhash 预测 Δ，与实测 Δ 比对 ----------
print("\n== 决定性验证：dither 预测 vs 实测逐像素 Δ ==")
dh = np.load("tools/jpeg-dither-exp/dither_field.npy")   # (H,W) ∈[-1,1] LSB
src = A.astype(np.float32)
pred = np.rint(np.clip(src + dh[..., None], 0.0, 255.0)).astype(np.uint8)
dpred = pred.astype(np.int16) - A.astype(np.int16)
dreal = B.astype(np.int16) - A.astype(np.int16)
agree = (dpred == dreal)
print(f"  预测Δ 与 实测Δ 完全相同的像素占比 = {agree.mean()*100:.4f}%")
print(f"  预测Δ 与 实测Δ 相差 <=1 的占比     = {(np.abs(dpred-dreal)<=1).mean()*100:.4f}%")
print(f"  实测Δ 的分布: =0 {(dreal==0).mean()*100:.3f}%  =±1 {(np.abs(dreal)==1).mean()*100:.3f}%  "
      f"=±2 {(np.abs(dreal)==2).mean()*100:.3f}%  >=3 {(np.abs(dreal)>=3).mean()*100:.3f}%")
print(f"  预测Δ 的分布: =0 {(dpred==0).mean()*100:.3f}%  =±1 {(np.abs(dpred)==1).mean()*100:.3f}%  "
      f"=±2 {(np.abs(dpred)==2).mean()*100:.3f}%")
# 逐通道
for ci, cn in enumerate("RGB"):
    ag = agree[..., ci]
    print(f"    {cn}: 预测==实测 {ag.mean()*100:.4f}%")
    out.setdefault("verify", {})[cn] = float(ag.mean() * 100)
out["verify"]["all_pct"] = float(agree.mean() * 100)
out["verify"]["within1_pct"] = float((np.abs(dpred - dreal) <= 1).mean() * 100)
out["verify"]["dreal_hist"] = {"eq0": float((dreal == 0).mean() * 100),
                               "eq1": float((np.abs(dreal) == 1).mean() * 100),
                               "eq2": float((np.abs(dreal) == 2).mean() * 100),
                               "ge3": float((np.abs(dreal) >= 3).mean() * 100)}
out["verify"]["dpred_hist"] = {"eq0": float((dpred == 0).mean() * 100),
                               "eq1": float((np.abs(dpred) == 1).mean() * 100),
                               "eq2": float((np.abs(dpred) == 2).mean() * 100)}

# 相干 vs 独立 的定量判别
rms = float(np.sqrt((dreal.astype(np.float64) ** 2).mean()))
print(f"\n  RMS(实测Δ) = {rms:.5f} LSB")
print(f"  相干叠加(2×TPDF)理论 RMS = 2*sqrt(1/6) = {2*np.sqrt(1/6):.5f} LSB")
print(f"  独立叠加(√2×TPDF)理论 RMS = sqrt(2/6)  = {np.sqrt(2/6):.5f} LSB")
print(f"  单次TPDF理论 RMS          = sqrt(1/6)  = {np.sqrt(1/6):.5f} LSB")
out["rms"] = {"measured": rms, "coherent_2tpdf": 2 * np.sqrt(1 / 6),
              "independent": np.sqrt(2 / 6), "single": np.sqrt(1 / 6)}

json.dump(out, open("tools/jpeg-dither-exp/05_detail_stats.json", "w"), ensure_ascii=False, indent=2)
print("\n已写出 tools/jpeg-dither-exp/05_detail_stats.json")
