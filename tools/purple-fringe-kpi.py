#!/usr/bin/env python3
"""紫边 / 边缘伪彩 KPI —— FilmKit 长期测量工具。

方法论来源：2026-10-05 成像测量审计（口径锁定 + 零假设检验 + 边缘对齐剖面）。
本脚本是那次审计唯一尺子的可执行沉淀，口径**逐行移植**，不得擅自改动 ——
改口径 = 换结论（见下"口径三陷阱"，每一种换个写法都能把读数挪 2 倍）。
改动本文件口径前必须先跑 --self-check，改完必须仍能 PASS。

======================================================================
三个 KPI：是什么、何时用哪个
======================================================================

[KPI1] ∇>60 紫边均值 v 与占比 frac          —— 禁作跨图 KPI
    v    = mean( max(chroma, 0) | grad > 60 )
    frac = P(grad > 60) * 100%
    只用于：同一张图、同尺度处理前后的快速 A/B，以及口径锚定自检。
    为什么禁作跨图 KPI（2026-10-05 审计实测，REPORT.md §3.1/§3.6/§7.4）：
      * 场景主导：同一地点相隔 7 秒的两张成片，曝光/平坦区噪声/占比几乎
        相同，读数却差 25.8 倍；
      * 分辨率主导：同一张 PC 图，1777 宽占比 0.446%，放大到 3072 后塌到
        0.029%~0.062% —— 仅重采样就读数可动 15 倍；
      * 全局白平衡线性平移：整图偏绿则 chroma 恒负，v 恒为 0（不是"没紫边"）。
    跨图 / 跨版本 / 跨参数比较一律用 KPI2、KPI3。

[KPI2] 零假设选择超额 = V(∇>60 mask) − V(同数量随机像素)   —— 跨图 KPI
    V_edge = mean(max(chroma,0) | mask)
    V_rand = 同 n 个随机像素（无放回）的同一读数，固定种子重复 repeats 次，
             报告均值 ± std；excess = V_edge − mean(V_rand)。
    含义：读数里真正"与边缘有关"的部分，免疫全局色度偏置与白平衡平移。
    审计基准（统一 1777 宽）：PC excess = +1.668 —— PC 的 3.684 里有 55%
    是与边缘无关的全局偏置，把 3.684 当对标目标会把 PC 高估约 2.2 倍；
    ours 152207 = +9.72。

[KPI3] 边缘对齐剖面 core（局域超额）          —— 跨图 KPI + 环节归因
    取 ∇ 的 3x3 山脊且 ∇>60 的点为边缘，沿中心差分梯度方向、以边缘为原点、
    把亮侧统一对齐到 +t，累加 ±12px 的平均 chroma，再扣远端基线：
        core = mean(profile[|t|<=2]) − mean(profile 远端基线)
    （基线的实现是剖面两端各 4 个点，即 |t|>=9 —— 以实现为准。）
    N 约几千条边缘平均后零均值噪声被消掉，剩下的是结构性响应。
    同时输出峰值位置 / FWHM / 最陡单步上升：形状本身能区分高频环节
    （窄峰 1~3px：去马赛克伪彩 / 锐化 / 横向色差）与低频环节（跨 10+px 的
    宽缓斜坡：色度降噪尺度 / 色调链 / 局部对比度）。
    审计基准（统一 1777 宽）：PC core = +1.08（宽缓斜坡，无局部峰）；
    ours 152207 core = +4.62（峰在 t=-1 暗侧 1px、FWHM 4px，教科书紫边形状）。

跨图比较必须统一尺度：默认把所有图重采样到宽 1777（BOX 下采样，--width 可调）。
3072 宽成片的边缘是 ~2px 过渡，不统一尺度则 ∇ 读数与占比都不可比。

======================================================================
口径三陷阱（审计已逐一实测 PC 读数的漂移，全部锁定为左列写法）
======================================================================
1. 梯度：前向差分 |I(x+1,y)-I(x,y)|、max(|dx|,|dy|)，末行/列置 0（本项目口径）
   != 中心差分 |I(x+1)-I(x-1)|/2 —— 后者把 PC 占比 0.446% 砍到 0.222%。
2. 色度：chroma = (R+B)/2 - G（本项目口径）
   != max(R,B) - G —— 后者把 PC 读数 3.684 抬到 7.307（旧数字 7.39 的来源）。
3. 截断：clip(chroma, 0) 之后再取均值（本项目口径）
   != 先取均值 —— 后者把 PC 读数 3.684 变成 1.908。
其余已锁定细节：显示域 RGB float64 0..255，不做任何线性化；亮度 =
(R+G+B)/3（用户指定权重，非 rec601/709）；mask 为 grad > 60 严格大于。

======================================================================
用法
======================================================================
  # 自检：PC 基准样张必须复现 3.68413 / 0.44607% (n=18707)，否则口径已坏
  python3 tools/purple-fringe-kpi.py --self-check
  python3 tools/purple-fringe-kpi.py --self-check --pc-path <path>

  # 测量（可一次多图；跨图比较请保持同一 --width）
  python3 tools/purple-fringe-kpi.py <img.jpg> [<img2.jpg> ...]
  python3 tools/purple-fringe-kpi.py <img.jpg> --width 1777 --json
  python3 tools/purple-fringe-kpi.py <img.jpg> --no-rescale   # 原生尺度（A/B 同尺度时用）

样张（用户照片）一律不入库：本脚本只接受任意输入路径，不内置任何 ours
样张路径；--pc-path 的默认值仅是审计锚定用的那张 PC 附件图（metric.py 原样）。
样张缺失时自检明确报错退出，绝不跳过或编造。

依赖：python3 + numpy + PIL（项目离线工具链，勿引入新依赖）。
"""

import argparse
import json
import os
import sys

import numpy as np
from PIL import Image

# ---------------------------------------------------------------------------
# 自检锚定（与 2026-10-05 审计 metric.py 完全同源）
# ---------------------------------------------------------------------------
PC_SELF_CHECK_PATH = ('/Users/wl/.dsh/attachments/v1/objects/ee/'
                      'ee40b981402be28dea011223c75ceaee5d402d13a5acc896ae13170fb34c1862')
SELF_CHECK_TARGET = {'v': 3.68413, 'frac': 0.44607, 'n': 18707}
SELF_CHECK_TOL = {'v': 0.01, 'frac': 0.002}   # 与审计 metric.py 的 assert 同容差；n 必须精确

KPI1_WARNING = ('禁作跨图 KPI: 同地点相隔 7s 的两张成片读数可差 25.8x; '
                '同一张图 1777<->3072 重采样读数/占比可动 15x; 整图偏绿时恒为 0. '
                '跨图比较用 KPI2(零假设超额) / KPI3(剖面 core).')

# ---------------------------------------------------------------------------
# 口径（逐行移植自审计 metric.py —— 勿改）
# ---------------------------------------------------------------------------


def load(path):
    """JPEG/PNG -> float64 RGB (0..255)，显示域（gamma 编码），不线性化。"""
    return np.asarray(Image.open(path).convert('RGB')).astype(np.float64)


def luma(rgb):
    """亮度 = (R+G+B)/3 —— 用户指定权重，非 rec601/709。"""
    return rgb.mean(axis=2)


def grad_fwd(lum):
    """前向差分梯度 max(|dx|,|dy|)；最后一行/列置 0 -> 永不入选 mask。"""
    fx = np.zeros_like(lum)
    fy = np.zeros_like(lum)
    fx[:, :-1] = np.abs(lum[:, 1:] - lum[:, :-1])
    fy[:-1, :] = np.abs(lum[1:, :] - lum[:-1, :])
    return np.maximum(fx, fy)


def chroma(rgb):
    """紫边色度: (R+B)/2 - G，紫边方向为正，未 clip。"""
    return (rgb[..., 0] + rgb[..., 2]) / 2.0 - rgb[..., 1]


def measure(rgb, thr=60.0, topq=None):
    """[KPI1] 核心读数 —— 逐行移植自审计 metric.py。

    thr  : grad > thr 的绝对阈值（严格大于）
    topq : 若给定，忽略 thr，改取梯度最高的 topq 百分位（消分布位置偏差）
    """
    g = grad_fwd(luma(rgb))
    if topq is None:
        m = g > thr
        used = thr
    else:
        used = float(np.percentile(g, 100.0 - topq))
        m = g >= used
    ch = chroma(rgb)
    n = int(m.sum())
    v = float(np.clip(ch[m], 0, None).mean()) if n else float('nan')
    return {
        'v': v, 'n': n, 'frac': float(m.mean() * 100), 'thr': used,
        'lum_med': float(np.median(luma(rgb))),
        'g_p999': float(np.percentile(g, 99.9)),
        'g_p9999': float(np.percentile(g, 99.99)),
        'px': rgb.shape[0] * rgb.shape[1],
    }


def resized(rgb, size, resample=Image.LANCZOS):
    """先 clip 回 uint8 再重采样（逐行移植 —— 先 float 重采样结果会不同）。"""
    im = Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), 'RGB').resize(size, resample)
    return np.asarray(im).astype(np.float64)


# ---------------------------------------------------------------------------
# [KPI2] 零假设检验（逐行移植自审计 run_null.py）
# ---------------------------------------------------------------------------


def _v_of(ch, idx):
    """像素下标集合上的 V = mean(clip(chroma, 0))。"""
    return float(np.clip(ch.ravel()[idx], 0, None).mean())


def null_excess(rgb, thr=60.0, repeats=5, seed=0, flat_thr=8.0):
    """[KPI2] V_edge / V_rand / V_flat 与选择超额。

    V_edge: mask = grad > thr 上的 V
    V_rand: 同 n 个随机像素（无放回）上的 V，repeats 次重复
    V_flat: 同 n 个平坦像素（grad < flat_thr）上的 V
    excess = V_edge - mean(V_rand)：读数中真正与边缘有关的部分。
    每图独立重建 RandomState(seed)，保证单图结果可复现（审计 run_null.py
    用的是跨图共享的随机序列，均意义上等价，std 量级 ~0.03 远小于读数差）。
    """
    rs = np.random.RandomState(seed)
    g = grad_fwd(luma(rgb))
    ch = chroma(rgb)
    idx_edge = np.flatnonzero((g > thr).ravel())
    flat = np.flatnonzero((g < flat_thr).ravel())
    n = len(idx_edge)
    if n == 0:
        return None
    ve = _v_of(ch, idx_edge)
    vr = [_v_of(ch, rs.choice(ch.size, n, replace=False)) for _ in range(repeats)]
    vf = None
    if len(flat) >= n:
        vf = [_v_of(ch, rs.choice(flat, n, replace=False)) for _ in range(repeats)]
    out = {
        'v_edge': ve, 'n_edge': n,
        'v_rand_mean': float(np.mean(vr)), 'v_rand_std': float(np.std(vr)),
        'repeats': repeats, 'seed': seed,
        'excess': ve - float(np.mean(vr)),
        'edge_over_rand': ve / float(np.mean(vr)),
        'cross_image_kpi': True,
    }
    if vf is not None:
        out['v_flat_mean'] = float(np.mean(vf))
        out['v_flat_std'] = float(np.std(vf))
    return out


# ---------------------------------------------------------------------------
# [KPI3] 边缘对齐剖面（逐行移植自审计 run_step3.py profile + run_step4.py shape）
# ---------------------------------------------------------------------------


def _sample(img, xs, ys):
    """双线性采样（含 w-1.001 clip 的边界处理，逐行移植）。"""
    h, w = img.shape[:2]
    xs = np.clip(xs, 0, w - 1.001)
    ys = np.clip(ys, 0, h - 1.001)
    x0 = np.floor(xs).astype(int)
    y0 = np.floor(ys).astype(int)
    fx = xs - x0
    fy = ys - y0
    return (img[y0, x0] * (1 - fx) * (1 - fy) + img[y0, x0 + 1] * fx * (1 - fy) +
            img[y0 + 1, x0] * (1 - fx) * fy + img[y0 + 1, x0 + 1] * fx * fy)


def profile(rgb, thr=60.0, R=12, max_edges=4000, seed=0):
    """边缘对齐平均色度剖面。返回 (t 轴, 平均 chroma, 平均 luma, n_edges)。

    边缘点 = 前向差分梯度 g 的 3x3 局部极大值且 g > thr（山脊 NMS）。
    方向   = 中心差分梯度方向。t 轴正方向 = 亮侧（符号对齐）。
    np.roll 的边界环绕行为保留原样 —— 口径一致性优先。
    """
    lum = luma(rgb)
    ch = chroma(rgb)
    g = grad_fwd(lum)
    gx = np.zeros_like(lum)
    gy = np.zeros_like(lum)
    gx[:, 1:-1] = (lum[:, 2:] - lum[:, :-2]) / 2
    gy[1:-1, :] = (lum[2:, :] - lum[:-2, :]) / 2
    # 3x3 局部极大值（纯 numpy，免 scipy）
    gm = g.copy()
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            gm = np.maximum(gm, np.roll(np.roll(g, dy, 0), dx, 1))
    ridge = (g >= gm) & (g > thr)
    mag = np.hypot(gx, gy)
    nx = gx / (mag + 1e-6)
    ny = gy / (mag + 1e-6)
    h, w = lum.shape
    ys, xs = np.mgrid[0:h, 0:w]
    p1 = _sample(lum, xs + nx, ys + ny)
    p2 = _sample(lum, xs - nx, ys - ny)
    idx = np.flatnonzero(ridge.ravel())
    if len(idx) == 0:
        return None
    if len(idx) > max_edges:
        idx = np.random.RandomState(seed).choice(idx, max_edges, replace=False)
    ey, ex = np.unravel_index(idx, lum.shape)
    sgn = np.sign(p1[ey, ex] - p2[ey, ex])
    sgn[sgn == 0] = 1.0
    dnx = nx[ey, ex] * sgn
    dny = ny[ey, ex] * sgn
    prof = np.zeros((2 * R + 1, len(idx)))
    lprof = np.zeros_like(prof)
    for i, t in enumerate(range(-R, R + 1)):
        sx = ex + dnx * t
        sy = ey + dny * t
        prof[i] = _sample(ch, sx, sy)
        lprof[i] = _sample(lum, sx, sy)
    return np.arange(-R, R + 1), prof.mean(axis=1), lprof.mean(axis=1), len(idx)


def shape_metrics(t, prof):
    """剖面形状指标。

    core / near / peak / FWHM / 基线逐行移植自审计 run_step4.py；额外补充
    max_step_rise（扣基线后整条剖面的最大单步上升）。
    注意：审计 REPORT §4.1 的"前沿最大斜率"（PC 0.15/px、ours 1.80/px）
    在 /tmp/pcfix 全部脚本中都没有实现、定义不可考，故本工具用它自己的
    明确定义替代，数字只用于跨图相对比较，不能与 REPORT §4.1 的那两行直接对数。
    """
    base = float(np.r_[prof[:4], prof[-4:]].mean())   # 远端基线：两端各 4 点（|t|>=9）
    pn = prof - base
    core = float(pn[10:15].mean())                    # |t| <= 2
    near = float(pn[8:17].mean())                     # |t| <= 4
    pk = int(np.argmax(pn))
    peak_t = int(t[pk])
    peak_h = float(pn[pk])
    fwhm = 0
    if pn[pk] > 0:
        half = pn[pk] / 2.0
        left = pk
        while left > 0 and pn[left] > half:
            left -= 1
        right = pk
        while right < len(pn) - 1 and pn[right] > half:
            right += 1
        fwhm = right - left
    steps = np.diff(pn)
    max_rise = float(steps.max()) if len(steps) else 0.0
    return {
        'core': core, 'near': near, 'baseline': base,
        'peak_t': peak_t, 'peak_height': peak_h, 'fwhm_px': fwhm,
        'max_step_rise': max_rise, 'cross_image_kpi': True,
        'profile_minus_baseline': [round(float(x), 4) for x in pn],
    }


# ---------------------------------------------------------------------------
# 单图分析 + 输出
# ---------------------------------------------------------------------------


def analyze(path, width=1777, rescale=True, thr=60.0, repeats=5, seed=0, max_edges=4000):
    rgb_native = load(path)
    h, w = rgb_native.shape[:2]
    out = {'path': path, 'width': w, 'height': h}

    out['kpi1_native'] = measure(rgb_native, thr)
    out['kpi1_native']['scale'] = f'{w}x{h}'

    rgb = rgb_native
    if rescale and w != width:
        size = (int(width), int(round(h * width / w)))
        rgb = resized(rgb_native, size, Image.BOX)
        out['rescaled_to'] = list(size)
    m_sc = measure(rgb, thr)
    m_sc['scale'] = f'{rgb.shape[1]}x{rgb.shape[0]}'
    out['kpi1_scaled'] = m_sc

    out['kpi2'] = null_excess(rgb, thr, repeats, seed)

    pr = profile(rgb, thr, max_edges=max_edges, seed=seed)
    out['kpi3'] = None
    if pr is not None:
        t, prof, _lprof, n_edges = pr
        out['kpi3'] = shape_metrics(t, prof)
        out['kpi3']['n_edges'] = n_edges
    return out


def print_report(r, thr, width, rescale, repeats, seed, max_edges):
    name = os.path.basename(r['path'])
    print(f"=== {name}  {r['width']}x{r['height']}  thr={thr:g} ===")
    k1n, k1s = r['kpi1_native'], r['kpi1_scaled']
    print('  [KPI1] grad>60 均值/占比  [禁作跨图 KPI]')
    print(f"    native {k1n['scale']:>11s}: v={k1n['v']:8.3f}  frac={k1n['frac']:7.3f}%  "
          f"n={k1n['n']:7d}  Lmed={k1n['lum_med']:6.1f}  gP99.9={k1n['g_p999']:6.1f}")
    if 'rescaled_to' in r:
        print(f"    scaled {k1s['scale']:>11s}: v={k1s['v']:8.3f}  frac={k1s['frac']:7.3f}%  "
              f"n={k1s['n']:7d}  (-> 宽{width} BOX)")
    k2 = r['kpi2']
    if k2 is None:
        print('  [KPI2] 零假设选择超额: 无 mask 像素, N/A')
    else:
        line = (f"  [KPI2] 零假设选择超额 = V_edge - V_rand  [跨图 KPI]\n"
                f"    V_edge={k2['v_edge']:8.3f}  V_rand={k2['v_rand_mean']:7.3f}"
                f"+-{k2['v_rand_std']:.3f} ({k2['repeats']} reps, seed={k2['seed']})  ")
        if 'v_flat_mean' in k2:
            line += f"V_flat={k2['v_flat_mean']:7.3f}  "
        line += (f"edge/rand={k2['edge_over_rand']:5.2f}x  excess={k2['excess']:+8.3f}")
        print(line)
    k3 = r['kpi3']
    if k3 is None:
        print('  [KPI3] 边缘对齐剖面: 无边缘, N/A')
    else:
        print('  [KPI3] 边缘对齐剖面 core  [跨图 KPI, 兼作环节归因]')
        print(f"    n_edges={k3['n_edges']:5d}  core(|t|<=2)={k3['core']:+7.2f}  "
              f"near(|t|<=4)={k3['near']:+7.2f}  baseline={k3['baseline']:6.2f}")
        print(f"    peak@t={k3['peak_t']:+3d}(高 {k3['peak_height']:+6.2f})  "
              f"FWHM={k3['fwhm_px']}px  max_step_rise={k3['max_step_rise']:+5.2f}/px")
        print('    profile(扣基线, t=-12..+12): '
              + ' '.join(f'{v:+5.1f}' for v in k3['profile_minus_baseline']))
    print()


# ---------------------------------------------------------------------------
# 自检
# ---------------------------------------------------------------------------


def self_check(pc_path, thr=60.0, repeats=5, seed=0, max_edges=4000):
    """口径自检：PC 基准样张必须复现 3.68413 / 0.44607% (n=18707)。

    样张缺失时明确报错退出 —— 绝不跳过自检，也绝不编造数字。
    通过后顺带打印该图的 KPI2/KPI3 作为跨图比较的基准卡。
    """
    if not os.path.isfile(pc_path):
        sys.stderr.write(f'[self-check] FAIL: 找不到 PC 基准样张:\n  {pc_path}\n')
        sys.stderr.write('  这是口径锚定的唯一外部文件（2026-10-05 审计所用 PC 附件图）。\n')
        sys.stderr.write('  用 --pc-path <path> 指向它后重试。本工具在样张缺失时拒绝自检，\n'
                         '  不会跳过自检，也不会输出任何替代数字。\n')
        return None
    rgb = load(pc_path)
    m = measure(rgb, thr)
    checks = {
        'v': abs(m['v'] - SELF_CHECK_TARGET['v']) <= SELF_CHECK_TOL['v'],
        'frac': abs(m['frac'] - SELF_CHECK_TARGET['frac']) <= SELF_CHECK_TOL['frac'],
        'n': m['n'] == SELF_CHECK_TARGET['n'],
    }
    result = {
        'pc_path': pc_path,
        'target': SELF_CHECK_TARGET,
        'measured': {'v': m['v'], 'frac': m['frac'], 'n': m['n']},
        'pass': all(checks.values()),
    }
    print(f"[self-check] PC 基准样张: {pc_path}")
    print(f"  measured: v={m['v']:.7f}  frac={m['frac']:.7f}%  n={m['n']}")
    print(f"  target  : v={SELF_CHECK_TARGET['v']}(+-{SELF_CHECK_TOL['v']})  "
          f"frac={SELF_CHECK_TARGET['frac']}%(+-{SELF_CHECK_TOL['frac']})  n={SELF_CHECK_TARGET['n']}")
    if result['pass']:
        print('  PASS')
    else:
        bad = [k for k, ok in checks.items() if not ok]
        print(f"  FAIL (mismatch: {', '.join(bad)}) —— 口径已被改动或样张不对, 禁止使用本工具读数")
    print()
    # 顺带输出 PC 的 KPI2/KPI3 基准（不做断言, 仅供跨图对照）
    r = analyze(pc_path, width=1777, rescale=False, thr=thr,
                repeats=repeats, seed=seed, max_edges=max_edges)
    print('  [参考] PC 跨图 KPI 基准 (2026-10-05 审计: excess=+1.668, core=+1.08):')
    if r['kpi2'] is not None:
        k2 = r['kpi2']
        print(f"    KPI2 excess={k2['excess']:+.3f}  (V_edge={k2['v_edge']:.3f}, "
              f"V_rand={k2['v_rand_mean']:.3f}+-{k2['v_rand_std']:.3f}, n={k2['n_edge']})")
    if r['kpi3'] is not None:
        k3 = r['kpi3']
        print(f"    KPI3 core={k3['core']:+.2f}  peak@t={k3['peak_t']:+d}  FWHM={k3['fwhm_px']}px  "
              f"n_edges={k3['n_edges']}")
    print()
    return result


# ---------------------------------------------------------------------------


def main():
    ap = argparse.ArgumentParser(
        description='紫边/边缘伪影三 KPI 测量（口径=2026-10-05 审计唯一尺子）',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog='详见文件头 docstring：三个 KPI 的含义、何时用哪个、口径三陷阱。')
    ap.add_argument('images', nargs='*', help='待测样张 (JPEG/PNG, 任意路径, 不入库)')
    ap.add_argument('--self-check', action='store_true',
                    help='用 PC 基准样张复现 3.68413/0.44607%%(n=18707), 校验口径未被改动')
    ap.add_argument('--pc-path', default=PC_SELF_CHECK_PATH,
                    help='自检用 PC 基准样张路径 (默认=审计 metric.py 所用附件)')
    ap.add_argument('--width', type=int, default=1777,
                    help='跨图比较统一到的目标宽度, BOX 重采样 (默认 1777 = PC 基准宽度)')
    ap.add_argument('--no-rescale', action='store_true',
                    help='不做统一尺度, 全按原生尺寸测 (仅限同尺度 A/B)')
    ap.add_argument('--thr', type=float, default=60.0, help='梯度阈值 (默认 60, 口径锁定值)')
    ap.add_argument('--repeats', type=int, default=5, help='KPI2 随机重复次数 (默认 5)')
    ap.add_argument('--seed', type=int, default=0, help='随机种子 (默认 0)')
    ap.add_argument('--max-edges', type=int, default=4000,
                    help='KPI3 剖面最多参与的边缘条数 (默认 4000)')
    ap.add_argument('--json', action='store_true', help='机器可读 JSON 输出')
    args = ap.parse_args()

    if not args.self_check and not args.images:
        ap.print_help()
        sys.exit(2)

    payload = {
        'tool': 'tools/purple-fringe-kpi.py',
        'metric': ('2026-10-05 审计口径: 显示域RGB0..255不线性化 / lum=(R+G+B)/3 / '
                   '前向差分max(|dx|,|dy|)末行列置0 / chroma=(R+B)/2-G / mask=grad>60 / '
                   'clip后取均值'),
        'params': {'width': args.width, 'rescale': not args.no_rescale, 'thr': args.thr,
                   'repeats': args.repeats, 'seed': args.seed, 'max_edges': args.max_edges},
    }

    exit_code = 0
    if args.self_check:
        sc = self_check(args.pc_path, args.thr, args.repeats, args.seed, args.max_edges)
        payload['self_check'] = sc
        if sc is None:
            exit_code = 2
        elif not sc['pass']:
            exit_code = 1

    images = []
    for p in args.images:
        if not os.path.isfile(p):
            sys.stderr.write(f'错误: 找不到样张文件: {p}\n')
            exit_code = exit_code or 2
            continue
        r = analyze(p, args.width, not args.no_rescale, args.thr,
                    args.repeats, args.seed, args.max_edges)
        images.append(r)
        if not args.json:
            print_report(r, args.thr, args.width, not args.no_rescale,
                         args.repeats, args.seed, args.max_edges)
    payload['images'] = images

    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))

    sys.exit(exit_code)


if __name__ == '__main__':
    main()
