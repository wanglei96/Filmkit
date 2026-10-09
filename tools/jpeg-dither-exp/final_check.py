#!/usr/bin/env python3
"""最终交叉验证：H2 坐标下的实验产物与真实导出的逐像素一致性 + 体积汇总。"""
import numpy as np, json
from PIL import Image
Image.MAX_IMAGE_PIXELS = None

P = "test_temp/"
A   = np.asarray(Image.open(P+"FILMKIT_20261009-090810.jpg"), dtype=np.uint8)
BR  = np.asarray(Image.open(P+"FILMKIT_20261009-090827_edit.jpg"), dtype=np.uint8)
OB  = np.asarray(Image.open("tools/jpeg-dither-exp/out_B.jpg"), dtype=np.uint8)
C2  = np.asarray(Image.open("tools/jpeg-dither-exp/out_C2.jpg"), dtype=np.uint8)
C1  = np.asarray(Image.open("tools/jpeg-dither-exp/out_C_h1.jpg"), dtype=np.uint8)
OD  = np.asarray(Image.open("tools/jpeg-dither-exp/out_D.jpg"), dtype=np.uint8)

def ds(X, Y, nm):
    d = X.astype(np.int16) - Y.astype(np.int16); ad = np.abs(d)
    r = {"eq0": float(100*(ad==0).mean()), "eq1": float(100*(ad==1).mean()),
         "eq2": float(100*(ad==2).mean()), "ge3": float(100*(ad>=3).mean()),
         "mean_abs": float(ad.mean()), "rms": float(np.sqrt((d.astype(np.float64)**2).mean())),
         "max": int(ad.max())}
    print(f"  {nm:34s} =0 {r['eq0']:7.3f}%  =1 {r['eq1']:7.3f}%  =2 {r['eq2']:6.3f}%  "
          f">=3 {r['ge3']:6.3f}%  mean|Δ| {r['mean_abs']:.4f}  RMS {r['rms']:.4f}  max {r['max']}")
    return r

print("== 最终验证：实验产物 vs 真实导出（越小越说明机制被复现）==")
r = {}
r["BR_vs_C2"]  = ds(BR, C2, "真实导出 - out_C2 (H2,相干)")
r["BR_vs_C1"]  = ds(BR, C1, "真实导出 - out_C_h1 (H1)")
r["BR_vs_D"]   = ds(BR, OD, "真实导出 - out_D (独立随机)")
r["BR_vs_OB"]  = ds(BR, OB, "真实导出 - out_B (无dither)")
r["BR_vs_A"]   = ds(BR, A,  "真实导出 - 原片")
print()
r["C2_vs_A"]   = ds(C2, A,  "out_C2 - 原片")
r["C1_vs_A"]   = ds(C1, A,  "out_C_h1 - 原片")

# 用 H2 预测 Δ 的命中率（修正此前用 H1 的错误预测）
d_H2 = np.load("tools/jpeg-dither-exp/d_H2.npy")
pred = np.rint(np.clip(A.astype(np.float32) + d_H2[..., None], 0, 255)).astype(np.uint8)
dp = pred.astype(np.int16) - A.astype(np.int16)
dr = BR.astype(np.int16) - A.astype(np.int16)
print(f"\n== 用 H2 坐标预测 Δ vs 实测 Δ（修正后）==")
print(f"  完全相同 = {100*(dp==dr).mean():.3f}%   相差<=1 = {100*(np.abs(dp-dr)<=1).mean():.3f}%")
print(f"  实测Δ 分布: =0 {100*(dr==0).mean():.3f}%  =±1 {100*(np.abs(dr)==1).mean():.3f}%  =±2 {100*(np.abs(dr)==2).mean():.3f}%")
print(f"  预测Δ 分布: =0 {100*(dp==0).mean():.3f}%  =±1 {100*(np.abs(dp)==1).mean():.3f}%  =±2 {100*(np.abs(dp)==2).mean():.3f}%")
r["predict_H2"] = {"exact_pct": float(100*(dp==dr).mean()), "within1_pct": float(100*(np.abs(dp-dr)<=1).mean())}

# 体积汇总
import os
def sz(p): return os.path.getsize(p)
A_B  = sz(P+"FILMKIT_20261009-090810.jpg")
BR_B = sz(P+"FILMKIT_20261009-090827_edit.jpg")
OB_B = sz("tools/jpeg-dither-exp/out_B.jpg")
C2_B = sz("tools/jpeg-dither-exp/out_C2.jpg")
C1_B = sz("tools/jpeg-dither-exp/out_C_h1.jpg")
OD_B = sz("tools/jpeg-dither-exp/out_D.jpg")
print("\n== 体积汇总 ==")
tot = BR_B - A_B
rows = [("A  原片（真机拍照成片）", A_B, None),
        ("B  仅二次编码 (PIL解码→stb q100)", OB_B, OB_B-A_B),
        ("C1 二次编码 + H1坐标dither", C1_B, C1_B-A_B),
        ("D  二次编码 + 独立随机TPDF", OD_B, OD_B-A_B),
        ("C2 二次编码 + H2坐标dither(相干)", C2_B, C2_B-A_B),
        ("BR 真实编辑器导出", BR_B, BR_B-A_B)]
for nm, b, dl in rows:
    if dl is None:
        print(f"  {nm:36s} {b:>10,} B   ({b/A_B*100:6.2f}% of A)")
    else:
        print(f"  {nm:36s} {b:>10,} B   ({b/A_B*100:6.2f}% of A, 增长 {dl:+,} B = {dl/tot*100:5.2f}% 的总增长)")
print(f"\n  总增长 = {tot:,} B  (BR/A = {BR_B/A_B:.4f} = +{(BR_B/A_B-1)*100:.2f}%)")
print(f"  C2 解释的体积 = {C2_B/BR_B*100:.2f}% of 真实导出")
print(f"  分解：二次编码 {OB_B-A_B:,} B ({(OB_B-A_B)/tot*100:.2f}%)")
print(f"        额外一次dither(独立分量, 用H1估计) {C1_B-OB_B:,} B ({(C1_B-OB_B)/tot*100:.2f}%)")
print(f"        相干增强(H1→H2) {C2_B-C1_B:,} B ({(C2_B-C1_B)/tot*100:.2f}%)")
print(f"        未解释 {BR_B-C2_B:,} B ({(BR_B-C2_B)/tot*100:.2f}%)")

out = {"diff": r, "bytes": {"A": A_B, "B": OB_B, "C1": C1_B, "C2": C2_B, "D": OD_B, "BR": BR_B},
       "total_growth": tot}
json.dump(out, open("tools/jpeg-dither-exp/08_final.json","w"), ensure_ascii=False, indent=2)
print("\n已写出 tools/jpeg-dither-exp/08_final.json")
