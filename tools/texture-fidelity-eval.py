#!/usr/bin/env python3
"""纹理保真评估（2026-09-30 v0.10.54 抹纹理事故后固化口径）。

事故复盘：当时手上两个指标都说"没退化"，但成片肉眼可见纹理被抹平 ——
  · σY（denoise-strength-eval）只在**平坦块**内测噪声：把平坦块抹平不损失任何东西
  · 边缘等效宽度只在**强边缘**（P99.5）上测：切向长核本来就保强边缘
  · 中等对比度纹理（叶片边缘、表面质感）两边都不覆盖
  · 8px 带通能量同样不可用：噪声与纹理都贡献能量，混淆两者

本工具的口径：
  ① 纹理块 = **用低噪参照（PC）**的低频梯度选（> thr）—— 纹理位置的真值来自参照，
     避免"我们噪声大所以到处都是纹理"的自证陷阱
  ② 块内**局部对比度** = P95 − P5（分位数对噪声尾部不敏感，对纹理调制敏感）
  ③ 块内**中尺度带通能量**（8px）作辅证
  ④ 与参照同块的比值：< 1 表示我们的纹理被压平

用法：
  python3 tools/texture-fidelity-eval.py pc.jpg=PC ours.jpg=ours [other.jpg=tag]
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image


def gray_img(p):
    return np.asarray(Image.open(p).convert("RGB"), np.float32) @ np.array(
        [0.2126, 0.7152, 0.0722], np.float32)


def low_grad(y, blk=32):
    h, w = y.shape
    bh, bw = h // blk, w // blk
    yc = y[: bh * blk, : bw * blk].reshape(bh, blk, bw, blk).mean(axis=(1, 3))
    gy, gx = np.gradient(yc)
    return np.hypot(gy, gx), (bh, bw)


def band(y, s):
    """s 像素尺度带通（box 下采样再上采样相减）。"""
    lo = np.asarray(Image.fromarray(y).resize((max(1, y.shape[1] // s), max(1, y.shape[0] // s)),
                                              Image.BOX).resize(y.shape[::-1], Image.BILINEAR))
    return y - lo


def band8(y):
    return band(y, 8)


def band_multi(y, scales=(2, 4, 8, 16)):
    """多尺度带通 std —— 细纹（木纹/布纹）在 2–4px 尺度，8px 单尺度会漏检
    （v0.10.55 事故：8px 只掉 12%，但 2–4px 的木纹几乎被抹平）。"""
    return {s: float(band(y, s).std()) for s in scales}


def stats(path, mask, bh, bw, blk=32):
    y = gray_img(path)
    d = band8(y)
    con, ban = [], []
    for i in range(bh):
        for j in range(bw):
            if not mask[i, j]:
                continue
            p = y[i*blk:(i+1)*blk, j*blk:(j+1)*blk]
            con.append(np.percentile(p, 95) - np.percentile(p, 5))
            ban.append(d[i*blk:(i+1)*blk, j*blk:(j+1)*blk].std())
    return float(np.mean(con)), float(np.mean(ban)), len(con)


def main():
    items = []
    for a in sys.argv[1:]:
        p, _, tag = a.rpartition("=")
        items.append((tag or Path(p).stem, p))
    if len(items) < 2:
        print(__doc__)
        sys.exit(2)
    ref_tag, ref_path = items[0]
    g, (bh, bw) = low_grad(gray_img(ref_path))
    mask = g > 5.0                     # 参照认定的纹理块
    print(f"纹理块来源 = {ref_tag}（低频梯度 > 5，共 {int(mask.sum())} / {bh*bw} 块）")
    print(f"{'样片':<14}{'局部对比度':>12}{'8px带通':>10}{'对比度比':>10}{'带通比':>9}")
    base = None
    for tag, p in items:
        con, ban, n = stats(p, mask, bh, bw)
        if base is None:
            base = (con, ban)
        print(f"{tag:<14}{con:>12.2f}{ban:>10.2f}{con/base[0]:>10.2f}{ban/base[1]:>9.2f}")
    print("\n判据：对比度比 < 0.9 = 纹理被压平（v0.10.54 事故形态）；≥ 0.95 视为保持")


if __name__ == "__main__":
    main()
