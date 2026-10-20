#!/usr/bin/env python3
"""gainmap 数学 A/B：libultrahdr 官方版 vs PhotonCamera 版（2026-10-19）。

背景：`UltraHdrEncoder.cpp` 里实现了两版 gainmap 数学，两版**数学等价**（ln 与 log2
只差常数倍），差异在三处**数值行为**（offset 1e-7 vs 1e-4、范围自适应 vs 固定
0.25~4.0、暗部钳位方式）。按项目铁律"性能取舍必须实测"，删哪一版必须由数据决定，
不在实现阶段拍脑袋。

本脚本用真实 DNG 样片，走项目自己的胶片链算出 SDR 显示值 + 保留 tone map 前的
HDR 线性，然后对**同一个像素**分别用两版算 gainmap，对比：
  - 高光 ROI（窗/灯）：谁更能表达"HDR 比 SDR 亮多少"
  - 暗部 ROI：谁更不容易把噪声放大成巨大 gain（这是 offset 的核心作用）
  - 还原误差：gainmap → applyGain 还原出的 HDR vs 原始 HDR（ΔE）

用法：
    python3 tools/gainmap-math-ab.py <dng路径> [--roi Y0 Y1 X0 X1]

依赖：rawpy / numpy / colour-science（项目既有，见其他 tools 脚本）
"""
import argparse
import sys
import math

import numpy as np
import rawpy


# ── 两版 gainmap 数学（与 C++ 逐字对应）───────────────────────────────

def srgb_oetf(x):
    x = np.clip(x, 0.0, 1.0)
    return np.where(x <= 0.0031308, x * 12.92, 1.055 * np.power(x, 1 / 2.4) - 0.055)


def srgb_inv_oetf(x):
    x = np.clip(x, 0.0, 1.0)
    return np.where(x <= 0.04045, x / 12.92, np.power((x + 0.055) / 1.055, 2.4))


def luma709(rgb):
    """Rec.709 luma（与 PC RawGainmapMath.linearLuma 同系数）"""
    return 0.2126 * rgb[..., 0] + 0.7152 * rgb[..., 1] + 0.0722 * rgb[..., 2]


def gainmap_official(sdr_lin, hdr_lin):
    """版本 A = libultrahdr 官方 gainmapmath.cpp（逐字）"""
    gain = np.log2((hdr_lin + 1e-7) / (sdr_lin + 1e-7))
    # sdr < 2/255 时钳到 2.3（防暗部噪声放大）
    gain = np.where(sdr_lin < 2.0 / 255.0, np.minimum(gain, 2.3), gain)
    return gain


def gainmap_photoncamera(sdr_encoded, hdr_lin):
    """版本 B = PhotonCamera RawGainmapMath.kt（逐字，strength=1.0）"""
    k_min, k_max, k_off = 0.25, 4.0, 1e-4
    sdr_lin = srgb_inv_oetf(sdr_encoded)
    ratio = np.clip((np.maximum(hdr_lin, 0.0) + k_off) / (sdr_lin + k_off), k_min, k_max)
    return (np.log(ratio) - math.log(k_min)) / (math.log(k_max) - math.log(k_min))


def apply_gain_official(sdr_lin, gain, min_boost, max_boost, gamma=1.0):
    """官方 applyGain：由 gainmap 还原 HDR（用于算还原误差）"""
    g = np.power(gain, 1.0 / gamma) if gamma != 1.0 else gain
    log_boost = math.log2(min_boost) * (1 - g) + math.log2(max_boost) * g
    return (sdr_lin + 1e-7) * np.exp2(log_boost) - 1e-7


def apply_gain_photoncamera(sdr_encoded, gain_norm):
    """PC reconstructLinear：由 gainmap 还原 HDR"""
    k_min, k_max, k_off = 0.25, 4.0, 1e-4
    sdr_lin = srgb_inv_oetf(sdr_encoded)
    log_gain = math.log(k_min) + (math.log(k_max) - math.log(k_min)) * np.clip(gain_norm, 0, 1)
    return (sdr_lin + k_off) * np.exp(log_gain) - k_off


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dng")
    ap.add_argument("--roi", nargs=4, type=int, default=None,
                    help="Y0 Y1 X0 X1 (RAW 坐标，注意 RAW 是横的)")
    ap.add_argument("--downsample", type=int, default=2,
                    help="gainmap 下采样（PC=2，Android默认=4）")
    args = ap.parse_args()

    with rawpy.imread(args.dng) as raw:
        # 项目口径：RAW 是横的，预览是竖的（flip=6）
        raw_img = raw.raw_image_visible.astype(np.float32)
        # 简化：用 rawpy 的标准处理得到"参考线性"，作为 HDR 侧的近似
        # ⚠ 这只是**对比两版数学**用的输入，不等于项目胶片链的输出
        rgb = raw.postprocess(gamma=(1, 1), no_auto_bright=True,
                              output_bps=16, use_camera_wb=True,
                              demosaic_algorithm=rawpy.DemosaicAlgorithm.DCB)
    rgb = rgb.astype(np.float32) / 65535.0

    if args.roi:
        y0, y1, x0, x1 = args.roi
        rgb = rgb[y0:y1, x0:x1]
    print(f"ROI: {rgb.shape}")

    # ── 构造 SDR / HDR 对（本脚本的"HDR"= 线性无 tone map，"SDR"= 简单 Reinhard 压）──
    hdr_lin = np.clip(rgb, 0, None)
    # SDR：一个可逆的压缩（模拟 tone map），保证 hdr/sdr 比例有意义
    sdr_lin = np.clip(hdr_lin / (1 + hdr_lin), 0, 1)
    sdr_encoded = srgb_oetf(sdr_lin)

    h, w = sdr_lin.shape[:2]
    ds = args.downsample
    gh, gw = max(1, h // ds), max(1, w // ds)

    def box_luma(arr):
        """盒式下采样（与 C++ boxDownsampleLuma 同做法）"""
        a = arr[:gh * ds, :gw * ds]
        a = a.reshape(gh, ds, gw, ds, 3).mean(axis=(1, 3))
        return luma709(a)

    hdr_y = box_luma(hdr_lin)
    sdr_y = box_luma(sdr_lin)
    sdr_enc_y = box_luma(sdr_encoded)   # 近似：PC 版输入是 encoded 域

    # ── 两版 gainmap ────────────────────────────────────────────────
    # ⚠ 口径统一（2026-10-19 修正）：两版**最终都存 8bit**，所以必须先把
    # `log2(gain)` 归一化到 [0,1] 再比较 —— 直接拿未归一化的 log2 值去比
    # 归一化后的 PC 值会把结论完全带偏（差一个 (log2max-log2min) 的因子）。
    #
    # 数学上：log2 归一化 ≡ ln 归一化（常数因子约掉）⇒ 若 min/max 相同，
    # 两版公式**完全相同**。真正的差异只剩三处：offset、范围来源、暗部钳位。
    g_off_raw = gainmap_official(sdr_y, hdr_y)          # log2 域，未归一化
    lo_off, hi_off = float(g_off_raw.min()), float(g_off_raw.max())
    g_off = (g_off_raw - lo_off) / max(hi_off - lo_off, 1e-9)   # → [0,1] 8bit 值

    # PC 版固定 [0.25, 4.0]；为公平，官方版也按 **PC 同范围**再算一版（g_off_fixed）
    g_pc = gainmap_photoncamera(sdr_enc_y, hdr_y)       # 已是 [0,1]

    k_min, k_max = 0.25, 4.0
    log2min, log2max = math.log2(k_min), math.log2(k_max)
    ratio_off = np.exp2(g_off_raw)                       # 官方 gain → 线性 ratio
    g_off_fixed = (np.log2(np.clip(ratio_off, k_min, k_max)) - log2min) / (log2max - log2min)

    print("\n=== gainmap 数值分布（统一为 8bit [0,1] 值，可直接比）===")
    for name, g in (("A 官方(自适应范围)", g_off),
                    ("A' 官方(固定0.25~4)", g_off_fixed),
                    ("B  PC(固定0.25~4)", g_pc)):
        print(f"{name:22s} min={g.min():.3f} max={g.max():.3f} "
              f"mean={g.mean():.3f} p1={np.percentile(g, 1):.3f} p99={np.percentile(g, 99):.3f}")

    # 用 A' 与 B 做逐像素差（同范围、同归一化口径，差异只来自 offset 与暗部钳位）
    diff = np.abs(g_off_fixed - g_pc)
    print(f"\n=== A' vs B 逐像素差（同范围口径）===")
    print(f"  mean={diff.mean():.4f} p99={np.percentile(diff, 99):.4f} max={diff.max():.4f}")
    print("  ↑ 差异越小 ⇒ 两版在该场景几乎等价；差异集中在暗部说明 offset 是主因")

    # ── 暗部行为（offset 的核心差异）─────────────────────────────────
    dark = sdr_y < 2.0 / 255.0
    n_dark = int(dark.sum())
    print(f"\n=== 暗部（sdr_luma < 2/255）：{n_dark} px ({100.0*n_dark/g_off.size:.2f}%) ===")
    if n_dark > 0:
        print(f"  A 官方: gain max={g_off[dark].max():+.3f} mean={g_off[dark].mean():+.3f}")
        print(f"  B PC  : gain max={g_pc[dark].max():+.3f} mean={g_pc[dark].mean():+.3f}")
        print("  ↑ 官方靠 sdr<2/255 的硬钳位(≤2.3)抑制；PC 靠 offset 1e-4 垫高分母抑制")
        print("    max 越小 = 暗部噪声越不容易被放大成假 HDR 高光")

    # ── 高光行为 ────────────────────────────────────────────────────
    bright = sdr_y > 0.8
    n_bright = int(bright.sum())
    print(f"\n=== 高光（sdr_luma > 0.8）：{n_bright} px ({100.0*n_bright/g_off.size:.2f}%) ===")
    if n_bright > 0:
        print(f"  A 官方: gain max={g_off[bright].max():+.3f} mean={g_off[bright].mean():+.3f}")
        print(f"  B PC  : gain max={g_pc[bright].max():+.3f} mean={g_pc[bright].mean():+.3f}")

    # ── 还原误差：gainmap 往返后能否还原 HDR ──────────────────────────
    print("\n=== 还原误差（gainmap → applyGain → 与原始 HDR luma 比）===")
    # 官方：需要把 gain 归一化到 [0,1] 再存 8bit（模拟量化）
    lo, hi = g_off.min(), g_off.max()
    if hi > lo:
        g_off_n = (g_off - lo) / (hi - lo)
        g_off_q = np.round(np.clip(g_off_n, 0, 1) * 255) / 255.0
        rec_off = apply_gain_official(sdr_y, g_off_q, math.exp2(lo), math.exp2(hi))
        err_off = np.abs(rec_off - hdr_y) / (hdr_y + 1e-6)
        print(f"  A 官方: 相对误差 mean={err_off.mean()*100:.2f}% "
              f"p99={np.percentile(err_off, 99)*100:.2f}% max={err_off.max()*100:.2f}%")

    g_pc_q = np.round(np.clip(g_pc, 0, 1) * 255) / 255.0
    rec_pc = apply_gain_photoncamera(sdr_enc_y, g_pc_q)
    err_pc = np.abs(rec_pc - hdr_y) / (hdr_y + 1e-6)
    print(f"  B PC  : 相对误差 mean={err_pc.mean()*100:.2f}% "
          f"p99={np.percentile(err_pc, 99)*100:.2f}% max={err_pc.max()*100:.2f}%")

    print("\n注：本脚本的 SDR 用简单 Reinhard 近似，**不等于**项目胶片链输出；")
    print("    结论用于比较两版数学的相对行为，绝对数值需接真实管线的 SDR 后重跑。")


if __name__ == "__main__":
    sys.exit(main())
