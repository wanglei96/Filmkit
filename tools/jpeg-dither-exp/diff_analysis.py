#!/usr/bin/env python3
"""第 2/3/5 项：逐像素差异直方图、高频能量、局部对比度、暗部直方图、量化台阶打散度。"""
import numpy as np, sys, json
from PIL import Image

Image.MAX_IMAGE_PIXELS = None
A_PATH = "test_temp/FILMKIT_20261009-090810.jpg"          # 原片
B_PATH = "test_temp/FILMKIT_20261009-090827_edit.jpg"     # 编辑器导出

def load(p):
    im = Image.open(p)
    assert im.mode == "RGB", im.mode
    return np.asarray(im, dtype=np.uint8)

def luma(rgb):
    # 与常用 BT.601 一致（引擎 avgLuma 口径未知，此处用整数近似）
    r = rgb[..., 0].astype(np.int32); g = rgb[..., 1].astype(np.int32); b = rgb[..., 2].astype(np.int32)
    return (77 * r + 150 * g + 29 * b) >> 8

print("== 解码 ==")
A = load(A_PATH); B = load(B_PATH)
print("A", A.shape, A.dtype, "B", B.shape, B.dtype)
assert A.shape == B.shape

out = {}
out["shape"] = list(A.shape)

# ---------- 第 2 项：逐像素差异直方图 ----------
d = A.astype(np.int16) - B.astype(np.int16)   # A=原片, B=导出; d = A-B
ad = np.abs(d)
tot = ad.size
out["diff"] = {
    "samples": int(tot),
    "eq0_pct": float((ad == 0).sum() / tot * 100),
    "eq1_pct": float((ad == 1).sum() / tot * 100),
    "eq2_pct": float((ad == 2).sum() / tot * 100),
    "ge3_pct": float((ad >= 3).sum() / tot * 100),
    "max_abs": int(ad.max()),
    "mean_abs": float(ad.mean()),
    "rms": float(np.sqrt((d.astype(np.float64) ** 2).mean())),
}
print("\n== 第 2 项 逐像素差异 (A=原片, B=导出) ==")
print(f"  样本数        = {tot:,}")
print(f"  |Δ|=0         = {out['diff']['eq0_pct']:.4f}%  ({(ad==0).sum():,})")
print(f"  |Δ|=1         = {out['diff']['eq1_pct']:.4f}%  ({(ad==1).sum():,})")
print(f"  |Δ|=2         = {out['diff']['eq2_pct']:.4f}%  ({(ad==2).sum():,})")
print(f"  |Δ|>=3        = {out['diff']['ge3_pct']:.4f}%  ({(ad>=3).sum():,})")
print(f"  max|Δ|        = {out['diff']['max_abs']}")
print(f"  mean|Δ|       = {out['diff']['mean_abs']:.5f}")
print(f"  RMS(Δ)        = {out['diff']['rms']:.5f} LSB")
# 完整直方图（0..10）
hist = np.bincount(ad.ravel(), minlength=11)[:11]
print(f"  |Δ| 0..10 计数 = {hist.tolist()}")
out["diff"]["hist_0_10"] = hist.tolist()
# 带符号分布
sh = np.bincount((d.ravel().astype(np.int32) + 8), minlength=17)
print(f"  Δ=-8..8 计数   = {sh.tolist()}")
out["diff"]["signed_m8_p8"] = sh.tolist()

# ---------- 第 3 项：高频能量 ----------
def lap_var(gray):
    g = gray.astype(np.float64)
    # 3x3 拉普拉斯 [0,1,0;1,-4,1;0,1,0]
    lap = np.zeros_like(g)
    lap[1:-1, 1:-1] = (g[:-2, 1:-1] + g[2:, 1:-1] + g[1:-1, :-2] + g[1:-1, 2:]
                       - 4.0 * g[1:-1, 1:-1])
    c = lap[1:-1, 1:-1]
    return float(c.var()), float(np.abs(c).mean())

LA = luma(A); LB = luma(B)
print("\n== 第 3 项 高频能量 (luma) ==")
va, ma = lap_var(LA); vb, mb = lap_var(LB)
print(f"  原片   3x3 拉普拉斯方差 = {va:.3f}   mean|lap| = {ma:.4f}")
print(f"  导出   3x3 拉普拉斯方差 = {vb:.3f}   mean|lap| = {mb:.4f}")
print(f"  比值 (导出/原片) 方差 = {vb/va:.4f}   mean|lap| = {mb/ma:.4f}")
out["hf"] = {"A_lapvar": va, "B_lapvar": vb, "ratio_lapvar": vb / va,
             "A_meanabs": ma, "B_meanabs": mb, "ratio_meanabs": mb / ma}

# 每个通道分别算
for ci, cn in enumerate("RGB"):
    va2, _ = lap_var(A[..., ci]); vb2, _ = lap_var(B[..., ci])
    print(f"    {cn}: A={va2:.2f} B={vb2:.2f} B/A={vb2/va2:.4f}")
    out["hf"][f"{cn}_A"] = va2; out["hf"][f"{cn}_B"] = vb2

# ---------- 第 5 项 ----------
print("\n== 第 5 项 细节真伪 ==")
# 5a 相邻像素相同占比（水平方向）——量化台阶被打散的度量
def flat_ratio(gray):
    h = gray[:, 1:] == gray[:, :-1]
    return float(h.mean() * 100)
fa = flat_ratio(LA); fb = flat_ratio(LB)
print(f"  水平相邻 luma 完全相同占比: 原片 {fa:.3f}%  导出 {fb:.3f}%  (差 {fb-fa:+.3f} pp)")
out["flat"] = {"A_pct": fa, "B_pct": fb, "delta_pp": fb - fa}
for ci, cn in enumerate("RGB"):
    fa2 = flat_ratio(A[..., ci]); fb2 = flat_ratio(B[..., ci])
    print(f"    {cn}: A={fa2:.3f}% B={fb2:.3f}% ({fb2-fa2:+.3f} pp)")
    out["flat"][f"{cn}_A"] = fa2; out["flat"][f"{cn}_B"] = fb2

# 5b 暗部直方图 (luma < 64)
darkA = LA < 64; darkB = LB < 64
print(f"  暗部 luma<64 占比: 原片 {darkA.mean()*100:.4f}%  导出 {darkB.mean()*100:.4f}%")
print(f"  暗部像素数: A={int(darkA.sum()):,} B={int(darkB.sum()):,}  交并: {int((darkA&darkB).sum()):,}  仅A={int((darkA&~darkB).sum()):,} 仅B={int((~darkA&darkB).sum()):,}")
out["dark"] = {"A_pct": float(darkA.mean() * 100), "B_pct": float(darkB.mean() * 100),
               "A_n": int(darkA.sum()), "B_n": int(darkB.sum()),
               "both": int((darkA & darkB).sum()), "onlyA": int((darkA & ~darkB).sum()),
               "onlyB": int((~darkA & darkB).sum())}
# 暗部 luma 直方图（0..63）对比
hA = np.bincount(LA[darkA].ravel(), minlength=64)[:64]
hB = np.bincount(LB[darkB].ravel(), minlength=64)[:64]
print(f"  暗部 luma 0..15 计数 A={hA[:16].tolist()}")
print(f"  暗部 luma 0..15 计数 B={hB[:16].tolist()}")
out["dark"]["histA_0_15"] = hA[:16].tolist()
out["dark"]["histB_0_15"] = hB[:16].tolist()
# 全图 luma 均值/标准差
print(f"  全图 luma mean: A={LA.mean():.4f} B={LB.mean():.4f} (Δ={LB.mean()-LA.mean():+.4f})")
print(f"  全图 luma std : A={LA.std():.4f} B={LB.std():.4f} (Δ={LB.std()-LA.std():+.4f})")
out["luma_stats"] = {"A_mean": float(LA.mean()), "B_mean": float(LB.mean()),
                     "A_std": float(LA.std()), "B_std": float(LB.std())}

# 5c 局部对比度分布：5x5 窗口 std 的分位数
def local_contrast(gray, k=5):
    g = gray.astype(np.float32)
    # 用积分图算局部均值/方差
    ii = np.cumsum(np.cumsum(g, 0), 1)
    ii2 = np.cumsum(np.cumsum(g * g, 0), 1)
    ii = np.pad(ii, ((1, 0), (1, 0))); ii2 = np.pad(ii2, ((1, 0), (1, 0)))
    H, W = g.shape
    ys = np.arange(0, H - k + 1); xs = np.arange(0, W - k + 1)
    Y, X = np.meshgrid(ys, xs, indexing="ij")
    s = ii[Y + k, X + k] - ii[Y, X + k] - ii[Y + k, X] + ii[Y, X]
    s2 = ii2[Y + k, X + k] - ii2[Y, X + k] - ii2[Y + k, X] + ii2[Y, X]
    n = k * k
    var = np.maximum(s2 / n - (s / n) ** 2, 0)
    return np.sqrt(var)

cA = local_contrast(LA); cB = local_contrast(LB)
qs = [10, 25, 50, 75, 90, 99]
qA = np.percentile(cA, qs); qB = np.percentile(cB, qs)
print(f"  5x5 局部 std 分位数 {qs}:")
print(f"    A = {[round(float(x),3) for x in qA]}")
print(f"    B = {[round(float(x),3) for x in qB]}")
print(f"    B/A = {[round(float(b/a),4) for a,b in zip(qA,qB)]}")
out["local_contrast"] = {"q": qs, "A": [float(x) for x in qA], "B": [float(x) for x in qB],
                         "ratio": [float(b / a) for a, b in zip(qA, qB)]}

# 5d 平坦区 vs 纹理区分离：用 A 的局部 std 分区，看各区 diff 幅度
flat_mask = cA < 1.0
tex_mask = cA > 8.0
print(f"  平坦区(cA<1) 占比 {flat_mask.mean()*100:.2f}%  纹理区(cA>8) 占比 {tex_mask.mean()*100:.2f}%")
for nm, m in (("平坦区", flat_mask), ("纹理区", tex_mask)):
    dd = ad[1:-1, 1:-1][m]
    print(f"    {nm}: mean|Δ|={dd.mean():.4f}  |Δ|>=2 占比={(dd>=2).mean()*100:.4f}%  max={dd.max()}")
    out.setdefault("region_diff", {})[nm] = {"mean_abs": float(dd.mean()),
                                             "ge2_pct": float((dd >= 2).mean() * 100),
                                             "max": int(dd.max()),
                                             "n": int(m.sum())}

# 5e 与"真实场景"的距离：无法直接判定（无 ground truth），但可用
#     "B 是否比 A 更接近 RAW 渲染" 的代理指标 —— 见报告说明
json.dump(out, open("tools/jpeg-dither-exp/02_diff_stats.json", "w"), ensure_ascii=False, indent=2)
print("\n已写出 tools/jpeg-dither-exp/02_diff_stats.json")
