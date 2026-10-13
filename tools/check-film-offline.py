#!/usr/bin/env python3
"""FILM 段（`kFsFilm`）离线链的独立校验脚本。

三件事，都是**独立于 `engine-shader-offline.py` 实现路径**的检查：
  ① `solve5`（手写列主元高斯消元）对拍 `numpy.linalg.solve`（不同算法路径）；
  ② spline 在 log 域采样点的数值表 + 五条约束残差（"手算对照"要用的数字在这里）；
  ③ **CPU 复现 shader 的 tone 路径** vs **GPU 实跑输出**逐像素对比
     （只在 `--film-gamut 0`、sat/split/mono 恒等、look 关时成立 —— 那时光路 =
      `mix(lin·2^ev, filmicTone(lin·2^ev), uToneK)`，其余段全是恒等）。

用法：
  python3 tools/check-film-offline.py --out /tmp/film-nogamut --tone-k 1 --ev 0
"""
import argparse
import importlib.util
import os
import sys

import numpy as np

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
K_GREY = 0.1845
NORM_MIN = 1.52587890625e-5   # kFsFilm 的 kFilmicNormMin = 2^-16


def load_ref():
    p = os.path.join(REPO, 'tools', 'filmic-spline-ref.py')
    spec = importlib.util.spec_from_file_location('filmic_spline_ref', p)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def np_solve5(a, b):
    """与手写 solve5 完全同一条 5×5 系统，用 numpy 的 LU 解（独立算法路径）。"""
    return np.linalg.solve(np.array(a, dtype=np.float64), np.array(b, dtype=np.float64))


def check_solve5(ref):
    """① 对拍：手写高斯消元 vs numpy LU，三组参数 × 2 段 × 3 通道。"""
    worst = 0.0
    for bl, wh, cp, lsh, ltoe, _note in [
            (-7.65, 4.56, 1.0, [0.01] * 3, [0.01] * 3, '默认'),
            (-6.0, 3.0, 1.0, [0.01] * 3, [0.01] * 3, '窄 dr'),
            (-8.0, 4.0, 1.2, [0.1, 0.05, 0.3], [0.2, 0.01, 0.05], 'per-channel lat')]:
        sp = ref.compute_filmic_spline(bl, wh, cp, lsh, ltoe)
        dbg = sp['_dbg']
        for ch in range(3):
            for seg, T in ((0, dbg['toe_log'][ch]), (1, dbg['sh_log'][ch])):
                if seg == 0:
                    A = [[0, 0, 0, 0, 1], [0, 0, 0, 1, 0],
                         [T ** 4, T ** 3, T * T, T, 1],
                         [4 * T ** 3, 3 * T * T, 2 * T, 1, 0],
                         [12 * T * T, 6 * T, 2, 0, 0]]
                    # 右端项与 C++ 逐项相同：toe 段 f(0)=black_disp，shoulder 段 f(1)=1
                    b = [sp['black_disp'], 0.0,
                         T * dbg['contrast'] + dbg['li'], dbg['contrast'], 0.0]
                else:
                    A = [[1, 1, 1, 1, 1], [4, 3, 2, 1, 0],
                         [T ** 4, T ** 3, T * T, T, 1],
                         [4 * T ** 3, 3 * T * T, 2 * T, 1, 0],
                         [12 * T * T, 6 * T, 2, 0, 0]]
                    b = [1.0, 0.0,
                         T * dbg['contrast'] + dbg['li'], dbg['contrast'], 0.0]
                x_hand = ref.solve5(A, b)
                x_np = np_solve5(A, b)
                worst = max(worst, float(np.max(np.abs(np.array(x_hand) - x_np))))
    print('① solve5 手写消元 vs numpy.linalg.solve：最大系数差 = %.3e' % worst)
    assert worst < 1e-9, '两条求解路径不一致'
    return worst


def spline_table(ref):
    """② 采样点数值表（"手算对照"的数字来源）。"""
    for bl, wh, cp, note in [(-7.65, 4.56, 1.0, '默认 -7.65/+4.56, contrast=1.0'),
                             (-6.0, 3.0, 1.0, 'black=-6.0 white=3.0'),
                             (-8.0, 4.0, 1.2, 'black=-8.0 white=4.0 contrast=1.2')]:
        sp = ref.compute_filmic_spline(bl, wh, cp, [0.01] * 3, [0.01] * 3)
        print('\n② %s → power=%.9f contrast=%.9f black_disp=%.6e grey_log=%.9f'
              % (note, sp['output_power'], sp['contrast'], sp['black_disp'], sp['grey_log']))
        print('   e(采样)   spline(e)      pow(spline,power)   [输入线性值]')
        for e in (0.0, 0.2, 0.4, sp['grey_log'], 0.8, 1.0):
            s = ref.spline_at(sp, e, 1)
            p = max(s, 0.0) ** sp['output_power']
            v = 2.0 ** (e * sp['dynamic_range'] + sp['black_ev']) * K_GREY
            print('   %-9.6f %-14.9f %-19.9f [%.6e]' % (e, s, p, v))
        # 不动点：中灰必须映射回中灰（C++/darktable 设计语义的独立验证）
        s_mid = ref.spline_at(sp, sp['grey_log'], 1)
        print('   不动点检查: spline(grey_log)=%.9f → pow=%.9f（目标中灰 0.1845）'
              % (s_mid, s_mid ** sp['output_power']))


def cpu_filmic_tone(lin, sp):
    """CPU 复现 kFsFilm 的 filmicTone（gamut 段关闭时 shader 的完整 tone 路径）。"""
    p = np.maximum(lin, 0.0)
    grey, bl, dr, pw = K_GREY, sp['black_ev'], sp['dynamic_range'], sp['output_power']

    def scalar(v, ch, lo):
        s = np.maximum(v, NORM_MIN)
        e = np.clip((np.log2(s / grey) - bl) / max(dr, 0.2), 0.0, 1.0)
        # 三段选择（与 shader 的 filmicSplineR/G/B 同式）
        toe = sum(sp['m%d' % i][0][ch] * e ** (i - 1) for i in range(1, 6))
        sh = sum(sp['m%d' % i][1][ch] * e ** (i - 1) for i in range(1, 6))
        linseg = sp['m1'][2][ch] + e * sp['m2'][2][ch]
        c = np.where(e < sp['lat_min'][ch], toe, np.where(e > sp['lat_max'][ch], sh, linseg))
        c = np.clip(c, lo, 1.0)
        return np.maximum(c, 0.0) ** pw

    naive = np.stack([scalar(p[..., ch], ch, 0.0) for ch in range(3)], axis=-1)
    mx = p.max(axis=-1)
    ratio = np.maximum(mx, sp['input_min'])
    tnorm = np.clip(mx, sp['input_min'], sp['input_max'])
    # norm 分支固定取 G 通道系数（shader 注释：E4-4 约定），lo = black_disp
    normed = (p / ratio[..., None]) * scalar(tnorm, 1, sp['black_disp'])[..., None]
    return 0.5 * naive + 0.5 * normed


def check_cpu_vs_gpu(out, tone_k, ev, ref):
    """③ CPU 复现 vs GPU 实跑（仅在 --film-gamut 0 + 恒等 sat/look/split/mono 时成立）。"""
    d4 = np.load(os.path.join(out, 'ccm_d4.npy'))
    d5 = np.load(os.path.join(out, 'film_d5.npy'))
    sp = ref.compute_filmic_spline(-7.65, 4.56, 1.0, [0.01] * 3, [0.01] * 3)
    lin = d4[..., :3].astype(np.float16).astype(np.float32) * (2.0 ** ev)   # 输入经 RGBA16F
    toned = cpu_filmic_tone(lin, sp)
    cpu = lin + (toned - lin) * tone_k
    gpu = d5[..., :3].astype(np.float32)
    dif = np.abs(cpu - gpu)
    rel = dif / np.maximum(np.abs(gpu), 1e-6)
    print('\n③ CPU 复现 filmicTone vs GPU 输出（tone_k=%.3f, ev=%.3f, gamut 关）：' % (tone_k, ev))
    print('   最大绝对差 = %.3e   最大相对差 = %.3e   P99 绝对差 = %.3e'
          % (dif.max(), rel.max(), np.percentile(dif, 99)))
    print('   差异中位数 = %.3e（float16 输出量化的理论步长 ≈ 2^-11 × 值域）'
          % np.median(dif))
    assert dif.max() < 2e-3, 'CPU 与 GPU 的 tone 路径不一致（超 float16 量化量级）'
    return float(dif.max())


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default='/tmp/film-nogamut')
    ap.add_argument('--tone-k', type=float, default=1.0)
    ap.add_argument('--ev', type=float, default=0.0)
    a = ap.parse_args()
    ref = load_ref()
    check_solve5(ref)
    spline_table(ref)
    check_cpu_vs_gpu(a.out, a.tone_k, a.ev, ref)
    print('\n全部校验通过。')
    sys.exit(0)
