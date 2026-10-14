#!/usr/bin/env python3
"""阶段 2：引擎 0（filmic）vs 引擎 1（sigmoid）离线输出的逐像素差异统计。

⚠ **口径声明（报告里必须分开写）**：本脚本是**成片域**统计 —— 输入是
`film_d5.npy` / `film_d5_sigmoid.npy`（`kFsFilm` pass 的输出，OETF 前的线性显示域，
含 gamut / 外观层 / 域映射）。它**不是**卡 A 的「纯曲线口径」
（`tools/sigmoid-criteria-scan.py` 只算曲线本体、不含 gamut 与外观层）⇒ 两套数字
不可混为一谈，也不可互相印证。

显示域 LSB = 真 sRGB OETF + 8bit（`tools/sigmoid-params-ref.py` 的 `srgb_oetf_8bit`，
未量化浮点，与 §10 判据口径逐字同源）—— 只有这个口径才与判据 ② 的 1.3 LSB 可比。

用法：
    python3 tools/stage2-engine-diff.py \
        --a /tmp/chain-c0/film_d5.npy --b /tmp/chain-c1/film_d5_sigmoid.npy
"""
import argparse
import importlib.util
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))

# 中灰区（判据② 的成片域佐证）：引擎 0 的显示域 LSB 落在 119±9
MID_LSB = (110.0, 128.0)
# 高光区：引擎 0 接近上限
HL_LSB = 250.0
# 死白：8bit 量化后必为 255
CLIP_LSB = 254.5


def _load(fname, modname):
    spec = importlib.util.spec_from_file_location(modname, os.path.join(HERE, fname))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


sig = _load('sigmoid-params-ref.py', 'sigref')
lsb_of = sig.srgb_oetf_8bit


def luma(rgb):
    """Rec.709 luma（与 shader 内 `luma()` 同系数）。"""
    return 0.2126 * rgb[..., 0] + 0.7152 * rgb[..., 1] + 0.0722 * rgb[..., 2]


def _pct(a, q):
    return float(np.percentile(a, q)) if a.size else None


def _diff(x, y):
    """带符号中位差 + |差| 的 P90/P99/最大。"""
    d = (y - x).astype(np.float64)
    ad = np.abs(d)
    return {'median_signed': float(np.median(d)), 'mean_signed': float(d.mean()),
            'median_abs': float(np.median(ad)), 'p90_abs': _pct(ad, 90),
            'p99_abs': _pct(ad, 99), 'max_abs': float(ad.max()),
            'n_gt_1lsb': int(np.sum(ad > 1.0))}


def analyse(pa, pb):
    A = np.load(pa).astype(np.float32)
    B = np.load(pb).astype(np.float32)
    if A.shape != B.shape:
        sys.exit('shape 不一致：%s vs %s' % (A.shape, B.shape))
    a3, b3 = A[..., :3].astype(np.float64), B[..., :3].astype(np.float64)
    la, lb = lsb_of(a3), lsb_of(b3)
    # 分区掩码一律用 **luma 的显示域 LSB**（一个像素一个值；Rec.709 与 shader 同系数）
    la_l, lb_l = lsb_of(luma(a3)), lsb_of(luma(b3))

    rep = {'inputs': {'a': pa, 'b': pb}, 'shape': list(A.shape),
           'domain': '成片域（OETF 前线性显示域 → 真 sRGB OETF → 8bit，未量化浮点）'}

    # ── 可跑性：NaN / Inf / 值域 ────────────────────────────────────────────
    rep['health'] = {}
    for nm, X, L in (('engine0', A, la), ('engine1', B, lb)):
        rep['health'][nm] = {
            'nan': int(np.sum(np.isnan(X))), 'inf': int(np.sum(np.isinf(X))),
            'linear_min': float(X[..., :3].min()), 'linear_max': float(X[..., :3].max()),
            'linear_p50': _pct(X[..., :3], 50), 'linear_p99': _pct(X[..., :3], 99),
            'lsb_min': float(L.min()), 'lsb_max': float(L.max()),
            'lsb_p50': _pct(L, 50), 'lsb_p99': _pct(L, 99),
            'n_linear_gt1': int(np.sum(X[..., :3] > 1.0)),
            'n_lsb_ge_250': int(np.sum(L >= HL_LSB)),
            'n_lsb_clipped': int(np.sum(L >= CLIP_LSB)),
        }

    # ── 全图逐像素差异 ─────────────────────────────────────────────────────
    rep['diff_all'] = {'linear': _diff(a3, b3), 'lsb': _diff(la, lb)}

    # ── 中灰区（引擎 0 的显示域 luma LSB ∈ [110,128]，即线性 ≈0.15~0.22）──────
    m = (la_l >= MID_LSB[0]) & (la_l <= MID_LSB[1])
    rep['midgrey'] = {
        'mask': 'engine0 luma LSB ∈ [%.0f, %.0f]（≈ 线性 0.15~0.22，中灰 0.1845=119.0 LSB）'
                % MID_LSB,
        'n_px': int(m.sum()),
        'engine0_lsb_mean': float(la_l[m].mean()) if m.any() else None,
        'engine1_lsb_mean': float(lb_l[m].mean()) if m.any() else None,
        'engine0_luma_linear_mean': float(luma(a3)[m].mean()) if m.any() else None,
        'engine1_luma_linear_mean': float(luma(b3)[m].mean()) if m.any() else None,
        'lsb_diff': _diff(la_l[m], lb_l[m]) if m.any() else None,
    }

    # ── 高光区（引擎 0 接近上限）：引擎 1 是否保留层次 ──────────────────────
    mh = la_l >= HL_LSB
    if mh.any():
        rep['highlight'] = {
            'mask': 'engine0 luma LSB ≥ %.0f' % HL_LSB, 'n_px': int(mh.sum()),
            'engine0_lsb': {'p10': _pct(la_l[mh], 10), 'p50': _pct(la_l[mh], 50),
                            'p90': _pct(la_l[mh], 90), 'std': float(la_l[mh].std()),
                            'n_unique': int(np.unique(np.round(la_l[mh], 3)).size)},
            'engine1_lsb': {'p10': _pct(lb_l[mh], 10), 'p50': _pct(lb_l[mh], 50),
                            'p90': _pct(lb_l[mh], 90), 'std': float(lb_l[mh].std()),
                            'n_unique': int(np.unique(np.round(lb_l[mh], 3)).size)},
            'engine0_luma_linear': {'p50': _pct(luma(a3)[mh], 50),
                                    'std': float(luma(a3)[mh].std())},
            'engine1_luma_linear': {'p50': _pct(luma(b3)[mh], 50),
                                    'std': float(luma(b3)[mh].std())},
        }
    else:
        rep['highlight'] = {'mask': 'engine0 luma LSB ≥ %.0f' % HL_LSB, 'n_px': 0,
                            'note': '本 ROI 无高光像素 ⇒ 本项 n/a'}
    return rep


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--a', required=True, help='引擎 0 的 film_d5.npy')
    ap.add_argument('--b', required=True, help='引擎 1 的 film_d5_sigmoid.npy')
    ap.add_argument('--json', help='把完整统计写到该路径')
    a = ap.parse_args()
    rep = analyse(a.a, a.b)
    if a.json:
        json.dump(rep, open(a.json, 'w'), indent=2, ensure_ascii=False)
    print(json.dumps(rep, indent=2, ensure_ascii=False))


if __name__ == '__main__':
    main()
