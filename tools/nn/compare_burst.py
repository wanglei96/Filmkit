#!/usr/bin/env python3
"""对比不同重建核生成的 burst 产物（同批帧、同一参考帧 → 唯一变量 = 插值核）。

口径（所有图共用同一批块，绝不各图自选）：
* 平坦块：在 --base 版 noisy 上两级挑选 —— 先按块内 lag-1 平均绝对差粗筛，再按
  "去平面拟合残差 std"精选 K 个 32×32 平面块，记录 (平面, y, x)；所有图共用。
* σ_flat = 这些块上去平面拟合残差 std 的中位（全带噪声口径，越高 = 噪声越多）；
* 4px/8px = 平面域 lag-1/lag-2 差分 RMS/√2 的全图中位（峰值对应 mosaic 周期 4/8px）。

用法：
  python3 tools/nn/compare_burst.py --base test_temp/burst \
      --new test_temp/burst_hi --new test_temp/burst_hi3
"""
import argparse
import sys
from pathlib import Path

import numpy as np

BS, ST, K, COARSE = 32, 32, 40, 300


def planes4(m):
    return [m[0::2, 0::2], m[0::2, 1::2], m[1::2, 1::2], m[1::2, 0::2]]


def plane_fit_sigma(blk):
    """块内去平面拟合残差 std（全带，≈该块噪声 σ）。"""
    h, w = blk.shape
    yy, xx = np.mgrid[0:h, 0:w]
    A = np.stack([xx.ravel(), yy.ravel(), np.ones(h * w)], 1).astype(np.float32)
    coef, *_ = np.linalg.lstsq(A, blk.ravel(), rcond=None)
    return float((blk.ravel() - A @ coef).std())


def pick_blocks(m):
    cand = []
    for pi, p in enumerate(planes4(m)):
        for y in range(0, p.shape[0] - BS + 1, ST):
            for x in range(0, p.shape[1] - BS + 1, ST):
                blk = p[y:y + BS, x:x + BS]
                cand.append((float(np.abs(np.diff(blk, axis=1)).mean()), pi, y, x))
    cand.sort()
    fine = [(plane_fit_sigma(planes4(m)[pi][y:y + BS, x:x + BS]), pi, y, x)
            for _, pi, y, x in cand[:COARSE]]
    fine.sort()
    return [(pi, y, x) for _, pi, y, x in fine[:K]]


def sigma_flat(m, blocks):
    pl = planes4(m)
    return float(np.median([plane_fit_sigma(pl[pi][y:y + BS, x:x + BS]) for pi, y, x in blocks]))


def band(m, lag, sl=(slice(64, -64), slice(64, -64))):
    return float(np.median([float(np.std(p[sl][:, lag:] - p[sl][:, :-lag])) / np.sqrt(2)
                            for p in planes4(m)]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="test_temp/burst", help="参照目录（默认现有 bilinear 产物）")
    ap.add_argument("--new", action="append", required=True, help="待比较目录，可多次")
    ap.add_argument("--tag", action="append", default=None, help="与 --new 对应的名字")
    args = ap.parse_args()
    base = Path(args.base)
    dirs = [(args.tag[i] if args.tag and i < len(args.tag) else Path(d).name, Path(d))
            for i, d in enumerate(args.new)]
    scenes = sorted({f.name.split("_")[0] for f in base.glob("scene*_clean.npy")})
    if not scenes:
        sys.exit(f"{base} 下没有 scene*_clean.npy")

    hdr = (f"{'场景':<7}{'版本':<12}{'σ_noisy':>10}{'σ_clean':>10}{'下降×':>8}"
           f"{'4px_noisy':>11}{'4px_clean':>11}{'4px保留':>9}{'8px保留':>9}"
           f"{'4px/混':>9}{'8px/混':>9}")
    print(f"平坦块口径：{K} 个 {BS}×{BS} 块，坐标取自 {base.name} 的 noisy（所有图共用）")
    print(f"σ = 块内去平面残差 std 中位；4px/8px = 平面 lag-1/lag-2 差分 RMS（mosaic 4/8px）")
    print(f"'保留' = clean/noisy（同核）；'混' = clean/该场景双线性版 noisy"
          f"（= 若推理输入是双线性 MFNR，训练目标相对输入的高频）")
    print(hdr)
    summary = {}
    for sc in scenes:
        rows = []
        for tag, d in [("bilinear", base)] + dirs:
            f = d / f"{sc}_clean.npy"
            fn = d / f"{sc}_noisy.npy"
            if not f.exists() or not fn.exists():
                print(f"  ⚠ 缺失 {fn} / {f}")
                continue
            cn, ni = np.load(f), np.load(fn)
            if tag == "bilinear" or not rows:
                blocks = pick_blocks(ni)          # 只挑一次：所有版本共用同一批块
            s_n, s_c = sigma_flat(ni, blocks), sigma_flat(cn, blocks)
            b_n1, b_c1 = band(ni, 1), band(cn, 1)
            b_n2, b_c2 = band(ni, 2), band(cn, 2)
            rows.append((tag, s_n, s_c, b_n1, b_c1, b_n2, b_c2))
            mix1 = rows[0][3]
            mix2 = rows[0][5]
            print(f"{sc:<7}{tag:<12}{s_n:>10.5f}{s_c:>10.5f}{s_n / s_c:>8.3f}"
                  f"{b_n1:>11.5f}{b_c1:>11.5f}{b_c1 / b_n1:>9.3f}{b_c2 / b_n2:>9.3f}"
                  f"{b_c1 / mix1:>9.3f}{b_c2 / mix2:>9.3f}")
            del cn, ni
        if rows:
            summary[sc] = rows
        print()

    # 汇总：相对 bilinear 的 clean 噪声 / 高频
    print("汇总（相对 bilinear 版 clean）")
    print(f"{'版本':<12}{'σ_clean比':>11}{'4px比':>9}{'8px比':>9}{'4px保留比':>11}"
          f"{'4px/混比':>10}")
    for i, (tag, _) in enumerate(dirs, 1):
        rs = [r for r in (summary.get(s, []) for s in scenes) if len(r) > i]
        if not rs:
            continue
        sc_ = float(np.median([r[i][2] / r[0][2] for r in rs]))
        c4 = float(np.median([r[i][4] / r[0][4] for r in rs]))
        c8 = float(np.median([r[i][6] / r[0][6] for r in rs]))
        k4 = float(np.median([(r[i][4] / r[i][3]) / (r[0][4] / r[0][3]) for r in rs]))
        m4 = float(np.median([(r[i][4] / r[0][3]) / (r[0][4] / r[0][3]) for r in rs]))
        print(f"{tag:<12}{sc_:>11.3f}{c4:>9.3f}{c8:>9.3f}{k4:>11.3f}{m4:>10.3f}")
    print("σ_clean比 = 新核 clean 噪声 / 旧核（>1 = 换核后 clean 更脏）；"
          "4px/8px 比 = 新核 clean 的高频能量 / 旧核；")
    print("4px保留比 = (clean/noisy)@4px 之比（同核，度量网络要补的高频是否变多）；"
          "4px/混比 = 混合口径下训练目标高频相对输入之比")
    return 0


if __name__ == "__main__":
    sys.exit(main())
