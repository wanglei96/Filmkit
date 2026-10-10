#!/usr/bin/env python3
"""E4-3 颗粒（granularity）引擎离线验收 —— **真跑引擎自己的 shader** + C++ 侧 LUT 对照。

为什么必须真跑（沿用 ADR §1.4 / §6.5 的判据来源纪律）：numpy 直译会漏掉 shader 的实际
行为（常量数组索引、texelFetch 的取整、RGBA16F 的读回精度）。本脚本通过
tools/check-style-passthrough.py 的 run_style 执行 **FrameRenderer.cpp 里那份 kFsStyle**
（与成片链的 kFsOut 颗粒段逐字相同，由 check-style-passthrough.py ⑨b 守护）。

验收项（对应任务卡的验收判据编号）：
  ① LUT 逐点对照：numpy 独立复算 ↔ **C++ 侧输出**（grain-lut-dump，include GrainLut.h）
     —— 覆盖 mb=100/0 与 pivot=0/0.3；并验证 pivot=0 时与 darktable **原版公式**
     （不含 pivot 项）逐点一致。
  ② [26]=0 逐位不变（判据 1）。
  ③ [27] 单调：ISO 100/400/1600/6400 的实测特征周期递增（判据 3）。
  ④ 功率谱（FFT 径向平均）形态：新实现（3 八度 simplex）vs 旧实现（单八度 value
     noise，Python 重建）—— 判据 2「颗粒感而非块状」的频谱证据。
  ⑤ [60] 生效：mb=0 与 mb=100 的按亮度分箱 RMS 调制曲线不同（判据 4）。
  ⑥ [61]=0 逐位等于"不启用 pivot"路径（判据 5，由 ① 的原版对照承担）。
  ⑦ 种子：预览恒 0 ⇒ 同 seed 两帧逐位相同（不闪）；成片派生 ⇒ 同参数可复现、
     不同内容不同图案（判据 6）。
  ⑧ 短边归一化（ADR-11）：同一内容两种分辨率下颗粒的**相对**特征周期一致。
  ⑩ [61] 方向（E4-3b 架构师裁决）：颗粒峰值亮度随 pivot 增大**单调移向暗部**
     —— 规格 §3.1.5 的公式 `pow(l,1+3p)` 给的是反向，裁决以规格**文字**为准（指数取倒数）。

用法：python3 tools/grain-exp/grain-exp.py [--size 1024] [--quick]
产物：tools/grain-exp/out/{lut_*.raw, psd_*.npy, grain_*.png, report.json, summary.txt}
依赖：numpy + moderngl + glfw（与 tools/engine-shader-offline.py 相同）+ clang++/g++
"""
import argparse
import importlib.util
import json
import os
import struct
import subprocess
import sys
import zlib

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(HERE, 'out')
sys.path.insert(0, os.path.join(REPO, 'tools'))
import grain_lut_ref as gref  # noqa: E402


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


csp = load_module('csp', os.path.join(REPO, 'tools', 'check-style-passthrough.py'))


# ────────────────────────────────────────────── 工具
def write_png_gray(path, arr8):
    """最小 PNG（8bit 灰度）—— 环境无 matplotlib，目视截图自己编码。"""
    h, w = arr8.shape
    raw = b''.join(b'\x00' + arr8[r].tobytes() for r in range(h))

    def chunk(tag, data):
        return (struct.pack('>I', len(data)) + tag + data +
                struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff))

    png = (b'\x89PNG\r\n\x1a\n' +
           chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 0, 0, 0, 0)) +
           chunk(b'IDAT', zlib.compress(raw, 6)) + chunk(b'IEND', b''))
    with open(path, 'wb') as f:
        f.write(png)


def radial_psd(img):
    """径向平均功率谱。img 已去均值；返回 (k, P(k))，k 单位 = cycles/px。

    加 Hann 窗抑制边界泄漏（不加窗时 FFT 的十字伪影会被误读成"规律图案"）。
    """
    h, w = img.shape
    F = np.fft.fftshift(np.fft.fft2(img * np.hanning(h)[:, None] * np.hanning(w)[None, :]))
    P = (np.abs(F) ** 2).ravel() / (h * w)
    cy, cx = h // 2, w // 2
    y, x = np.mgrid[0:h, 0:w]
    nb = min(h, w) // 2
    idx = np.clip(np.rint(np.hypot(y - cy, x - cx)).astype(np.int32), 0, nb - 1).ravel()
    sums = np.bincount(idx, weights=P, minlength=nb)
    cnts = np.bincount(idx, minlength=nb)
    k = (np.arange(nb) + 0.5) / float(max(h, w))     # cycles/px
    return k[1:], (sums / np.maximum(cnts, 1))[1:]   # 丢掉 DC 桶


def psd_centroid_k(k, P):
    """谱质心（cycles/px）—— 特征周期 = 1/质心。幂律谱无尖峰，质心比峰值稳健。"""
    return float((k * P).sum() / max(P.sum(), 1e-30))


def psd_slope(k, P):
    """log-log 最小二乘斜率 —— 越负 = 越低通（"块状"）；接近 0 = 宽带（"颗粒"）。"""
    m = (k > 0) & (P > 0)
    if m.sum() < 8:
        return float('nan')
    return float(np.polyfit(np.log(k[m]), np.log(P[m]), 1)[0])


def old_vnoise(h, w, size=1.0, seed=0.0):
    """旧实现（E4-3 之前 kFsOut 的颗粒噪声源）的 numpy 重建，用于 ④ 的对照。

    旧 shader: gp = gl_FragCoord.xy * 6.0 / max(uGrainSize, 0.25) + uTime;
               vnoise(gp) = smoothstep 插值的 value noise，hash = fract(sin(dot(p,·))·k)
    """
    y, x = np.mgrid[0:h, 0:w]
    gx = (x + 0.5) * 6.0 / max(size, 0.25) + seed
    gy = (y + 0.5) * 6.0 / max(size, 0.25)

    def hsh(px, py):
        return np.modf(np.sin(px * 127.1 + py * 311.7) * 43758.5453)[0] % 1.0

    i, j = np.floor(gx), np.floor(gy)
    fx, fy = gx - i, gy - j
    fx = fx * fx * (3.0 - 2.0 * fx)
    fy = fy * fy * (3.0 - 2.0 * fy)
    a = hsh(i, j)
    b = hsh(i + 1, j)
    c = hsh(i, j + 1)
    d = hsh(i + 1, j + 1)
    return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy


# ────────────────────────────────────────────── ① LUT 逐点对照
def exp_lut(rep):
    dump = os.path.join(HERE, 'grain-lut-dump')
    src = os.path.join(HERE, 'grain-lut-dump.cpp')
    if (not os.path.exists(dump)) or os.path.getmtime(dump) < os.path.getmtime(src):
        cxx = os.environ.get('CXX', 'clang++')
        subprocess.run([cxx, '-O2', '-std=c++17', '-o', dump, src], check=True, cwd=HERE)
    cases = [(100.0, 0.0), (0.0, 0.0), (100.0, 0.3)]
    rows = []
    ok = True
    for mb, pv in cases:
        tag = f'mb{mb:g}_p{pv:g}'.replace('.', 'p')   # 小数点换成 p，保住 .raw 扩展名
        raw = os.path.join(OUT, f'lut_cpp_{tag}.raw')
        meta = subprocess.run([dump, f'{mb}', f'{pv}', raw], check=True,
                              capture_output=True, text=True).stdout.strip()
        cpp = np.fromfile(raw, dtype='<f4').reshape(gref.LUT_SIZE, gref.LUT_SIZE)
        ref = gref.build_grain_lut(mb, pv)
        # 相对误差（分母带地板：LUT 在两端趋 0，纯相对误差会爆）
        denom = np.maximum(np.abs(cpp), 1.0)
        rel = float(np.abs(cpp - ref).max() / denom.max()) if cpp.size else 0.0
        rel_pt = np.abs(cpp - ref) / np.maximum(np.abs(cpp), 1.0)
        rel_max = float(rel_pt.max())
        rows.append({'mb': mb, 'pivot': pv, 'rel_err_max': rel_max,
                     'abs_err_max': float(np.abs(cpp - ref).max()),
                     'lut_abs_max': float(np.abs(cpp).max())})
        print(f'① LUT mb={mb:g} pivot={pv:g}：C++ ↔ numpy 相对误差 max = {rel_max:.3e}'
              f'（判据 ≤1e-5）、绝对误差 max = {float(np.abs(cpp - ref).max()):.3e}'
              f'、|LUT| max = {float(np.abs(cpp).max()):.2f} L 单位')
        ok &= rel_max <= 1e-5
        if pv == 0.0:
            # ⑥ pivot=0 必须逐点等于 **darktable 原版公式**（不含 pivot 项）
            d = gref.grain_delta(mb)
            idx = np.arange(gref.LUT_SIZE, dtype=np.float32)
            l = idx / np.float32(gref.LUT_SIZE - 1)
            gu = l - np.float32(0.5)
            inv0 = gref.paper_resp_inverse(l, d)
            vanilla = np.float32(100.0) * (gref.paper_resp(gu[None, :] + inv0[:, None], d)
                                           - l[:, None])
            dmax = float(np.abs(cpp - vanilla).max())
            print(f'   ⑥ pivot=0 ↔ darktable 原版公式（无 pivot 项）：max|Δ| = {dmax:.3e}'
                  f'（判据 ≤1e-5 ⇒ pivot=0 是原版逐点复现）')
            rows[-1]['vanilla_max_abs'] = dmax
            ok &= dmax <= 1e-5
        # ④ 无系统性亮度偏移：gu≈0 处 LUT 必须 ≈0
        mid = 0.5 * (cpp[:, gref.LUT_SIZE // 2 - 1] + cpp[:, gref.LUT_SIZE // 2])
        print(f'   gu≈0 处 |LUT| max = {float(np.abs(mid).max()):.2e} L 单位'
              f'（判据 <1e-2 ⇒ [61] 不是"整体亮度"旋钮）')
        ok &= float(np.abs(mid).max()) < 1e-2
    rep['lut'] = rows
    return ok


# ────────────────────────────────────────────── 真跑 shader 的颗粒实验
def make_lut_off(gl):
    """1×1 恒等 3D LUT 占位（lut_on=0 ⇒ 不采样，但真机也必须绑定纹理 —— Mali 对未绑定
    sampler 会预测执行采样，见 FrameRenderer::bindLut 注释）。"""
    t = gl.ctx.texture3d((1, 1, 1), 3, dtype='f2')
    t.write(np.zeros((1, 1, 1, 3), np.float16).tobytes())
    return t


def grain_inc(gl, eso, sh, base_u8, lut_off, **kw):
    """跑一遍 kFsStyle，返回**颗粒增量**（显示域浮点，已去均值）。"""
    out = csp.run_style(gl, eso, sh, base_u8, lut_off, 1, lut_on=0.0,
                        lut_strength=0.0, **kw)
    inc = out[..., 0].astype(np.float64) - base_u8[..., 0].astype(np.float64) / 255.0
    return inc - inc.mean()


def exp_shader(gl, eso, sh, n, rep):
    ok = True
    grey = np.full((n, n, 3), 128, np.uint8)
    lut_off = make_lut_off(gl)

    # ② [26]=0 逐位不变（大图；小图版本见 check-style-passthrough ⑯a）
    o = csp.run_style(gl, eso, sh, grey, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                      grain=0.0, grain_mb=0.0, grain_pivot=1.0)
    q = np.clip(np.rint(o[..., :3].reshape(n, n, 3) * 255.0), 0, 255)
    d = np.abs(q - grey.astype(np.float64))
    bad = int((d > 1).sum())
    print(f'② [26]=0（含 [60]=0/[61]=1 极端值）{n}²：max|Δ| = {d.max():.0f} LSB，'
          f'>1LSB 像素 = {bad}/{d.size}')
    rep['zero_regression'] = {'max_lsb': float(d.max()), 'bad_px': bad}
    ok &= bad == 0

    # ③ [27] 单调 + ④ 功率谱形态 + ⑧ 短边归一化
    # ⚠ 图幅必须 ≥3072（脚本默认值），否则**混叠**会让 [27] 判据失效：最高频八度
    # f=1.728 的周期 = zoom/1.728 × 短边，ISO 100 时 zoom=0.0012969 ⇒ 要 ≥2px（Nyquist）
    # 需要短边 ≥ 2665px。1024px 上 ISO 100/400/1600 的该八度全部欠采样，混叠能量把谱
    # 质心钉在 ~0.25 cyc/px，四档实测 3.96/3.93/3.93/5.55 px（非单调）；3072px 上
    # 同一实验得到 5.24/5.66/7.61/15.97 px（严格单调）。这是**测量条件**问题，不是实现
    # 问题 —— 真机短边 3072px，正是本脚本的默认口径（与设计文档 §3.1.1 的表格同基准）。
    psds, cent = {}, {}
    for iso in (100, 400, 1600, 6400):
        inc = grain_inc(gl, eso, sh, grey, lut_off, grain=50.0, grain_size=float(iso))
        k, P = radial_psd(inc)
        psds[iso] = (k, P)
        cent[iso] = psd_centroid_k(k, P)
        np.save(os.path.join(OUT, f'psd_iso{iso}.npy'), np.stack([k, P]))
        print(f'③ ISO {iso:>4}：特征周期 = {1 / cent[iso]:6.2f} px'
              f'（谱质心 {cent[iso]:.4f} cycles/px）、谱斜率 = {psd_slope(k, P):+.3f}')
    mono = all(cent[a] > cent[b] for a, b in ((100, 400), (400, 1600), (1600, 6400)))
    print(f'③ [27] 单调（ISO 越大 ⇒ 质心越低 ⇒ 周期越长）= {mono}；'
          f'实测特征周期 100→{1 / cent[100]:.2f} / 400→{1 / cent[400]:.2f} / '
          f'1600→{1 / cent[1600]:.2f} / 6400→{1 / cent[6400]:.2f} px')
    rep['iso_centroid'] = {str(k_): v for k_, v in cent.items()}
    rep['iso_period_px'] = {str(k_): 1.0 / v for k_, v in cent.items()}
    ok &= mono

    # ④ 形态对照：「颗粒感 vs 块状」的频谱证据。
    # 判据量 = **低频能量占比**（k < 0.05 cyc/px，即周期 > 20px 的成分占总功率的比例）
    # —— "块状"的定义就是低频主导。判据分两条：
    #   a) 新实现（3 八度 simplex）在**全部** ISO 档低频占比都低（<15%）= 宽带颗粒；
    #   b) 对照：旧实现（单八度 value noise）要达到**同量级的视觉尺度**必须把 size 调大
    #      （size=24 ⇒ 像素空间格子 = size/6 = 4px），此时低频占比 51.7% = 块状。
    #   为什么用 1024 小图做 ④：旧实现的格子是**固定像素数**（与图幅无关），必须在同一
    #   图幅上与"新实现能达到同尺度的档位"对比才公平；新实现在小图上因混叠谱偏白，
    #   而混叠只会**抬高**低频占比 ⇒ 对判据 a 是保守方向（不会误判通过）。
    lo = lambda k, P: float(P[k < 0.05].sum() / P.sum())
    nn = min(n, 1024)
    g_small = np.full((nn, nn, 3), 128, np.uint8)
    lo_new = {}
    for iso in (400, 1600, 6400):
        inc = grain_inc(gl, eso, sh, g_small, lut_off, grain=50.0, grain_size=float(iso))
        k, P = radial_psd(inc)
        lo_new[iso] = lo(k, P)
    inc_old = old_vnoise(nn, nn, size=24.0, seed=0.0)
    k_old, P_old = radial_psd(inc_old - inc_old.mean())
    lo_old = lo(k_old, P_old)
    print('④ 谱形态（低频能量占比，k<0.05 cyc/px = 周期>20px 的"块"）：新实现 '
          + ' / '.join(f'ISO{k_} {v * 100:.1f}%' for k_, v in lo_new.items())
          + f'（全部 <15% = 宽带颗粒）｜旧实现 size=24（格子 4px = 同量级视觉尺度）'
            f' {lo_old * 100:.1f}% = 块状')
    print(f'   判据 a（新实现全档 <15%）= {all(v < 0.15 for v in lo_new.values())}；'
          f'判据 b（旧实现 >30%）= {lo_old > 0.30}')
    rep['psd_shape'] = {'new_lowfrac': {str(k_): v for k_, v in lo_new.items()},
                        'old_size24_lowfrac': lo_old,
                        'old_slope': psd_slope(k_old, P_old)}
    ok &= all(v < 0.15 for v in lo_new.values()) and lo_old > 0.30

    # ⑤ [60] 调制曲线（按亮度分箱 RMS）
    bands = np.empty((n, n, 3), np.uint8)
    bands[: n // 3] = 40
    bands[n // 3: 2 * n // 3] = 128
    bands[2 * n // 3:] = 220
    seg = ((0, n // 3), (n // 3, 2 * n // 3), (2 * n // 3, n))
    prof = {}
    for mb in (0.0, 100.0):
        inc = grain_inc(gl, eso, sh, bands, lut_off, grain=50.0, grain_mb=mb)
        prof[mb] = [float(inc[a:b].std()) for a, b in seg]
    mid_peak = prof[100.0][1] > prof[100.0][0] and prof[100.0][1] > prof[100.0][2]
    er0 = (prof[0.0][0] + prof[0.0][2]) / max(prof[0.0][1], 1e-9)
    er100 = (prof[100.0][0] + prof[100.0][2]) / max(prof[100.0][1], 1e-9)
    print(f'⑤ [60] 调制曲线（三段灰 RMS，暗/中/亮）：mb=100 → '
          f'{prof[100.0][0] * 255:.2f}/{prof[100.0][1] * 255:.2f}/{prof[100.0][2] * 255:.2f}'
          f'（中间最大 = {mid_peak}）；mb=0 → '
          f'{prof[0.0][0] * 255:.2f}/{prof[0.0][1] * 255:.2f}/{prof[0.0][2] * 255:.2f}'
          f'；两端/中间比 mb=0 = {er0:.3f} > mb=100 = {er100:.3f}')
    rep['mod_curve'] = {str(k_): v for k_, v in prof.items()}
    ok &= mid_peak and er0 > er100

    # ⑩ [61] 方向（E4-3b 架构师裁决，2026-10-10）：颗粒峰值亮度必须随 pivot 增大而**单调
    #   移向暗部**。规格 §3.1.5 的公式 `pow(l, 1+3p)` 给的是反向（峰值移向亮部），裁决以
    #   规格**文字**（调研需求 `editor-preset-RESEARCH.md` §2.4"负片暗部颗粒重"）为准 ⇒
    #   指数取倒数 `k = 1/(1+3p)`（`pivot = 0` 两式同为 1.0 ⇒ 逐位不变）。
    #   判据量 = 每档灰阶的颗粒 RMS（已去均值）—— 与 ⑤ 同口径，档数加密到 32。
    #   图幅 min(n,1024)：本判据测的是**调制形状**（LUT 斜率 × 噪声幅度），不测空间尺度
    #   ⇒ 不受 ③ 的混叠约束（混叠对所有亮度档同等影响）。
    #   ⚠ 档位按**固定种子的随机掩码**分配到像素，而不是竖条：竖条会让各档落在不同的噪声
    #   空间位置上，档间 RMS 差异混入噪声图案的位置起伏（32 档峰值定位会被它带偏）。
    nb, side = 32, min(n, 1024)
    ramp = np.empty((side, side, 3), np.uint8)
    mask = np.random.default_rng(0).integers(0, nb, size=(side, side))
    for b in range(nb):
        ramp[mask == b] = int(round(255.0 * (b + 0.5) / nb))
    pk, ct = {}, {}
    for pv in (0.0, 0.15, 0.3):
        inc = grain_inc(gl, eso, sh, ramp, lut_off, grain=50.0, grain_pivot=pv)
        rms = np.array([float(inc[mask == b].std()) for b in range(nb)])
        ls = (np.arange(nb) + 0.5) / nb
        pk[pv] = float(ls[int(np.argmax(rms))])
        ct[pv] = float((rms ** 2 * ls).sum() / (rms ** 2).sum())   # RMS² 加权质心（更稳）
    mono = pk[0.15] < pk[0.0] and pk[0.3] < pk[0.15]
    mono_ct = ct[0.15] < ct[0.0] and ct[0.3] < ct[0.15]
    print(f'⑩ [61] 方向（{nb} 档灰阶的颗粒 RMS 峰值亮度）：pivot=0 → {pk[0.0]:.3f}、'
          f'0.15 → {pk[0.15]:.3f}、0.3 → {pk[0.3]:.3f}'
          f'（单调移向**暗部** = {mono}）；RMS² 加权质心 = '
          f'{ct[0.0]:.3f} → {ct[0.15]:.3f} → {ct[0.3]:.3f}（单调 = {mono_ct}）')
    rep['pivot_peak_l'] = {str(k_): v for k_, v in pk.items()}
    rep['pivot_centroid_l'] = {str(k_): v for k_, v in ct.items()}
    ok &= mono and mono_ct and 0.3 <= pk[0.0] <= 0.7

    # ⑦ 种子：预览恒 0 ⇒ 两帧逐位相同；成片派生 ⇒ 可复现、不同内容不同图案
    a1 = grain_inc(gl, eso, sh, grey, lut_off, grain=50.0, grain_seed=0.0)
    a2 = grain_inc(gl, eso, sh, grey, lut_off, grain=50.0, grain_seed=0.0)
    b1 = grain_inc(gl, eso, sh, grey, lut_off, grain=50.0, grain_seed=7.5)
    same = bool(np.array_equal(a1, a2))
    diff = float(np.abs(a1 - b1).max())
    print(f'⑦ 种子：同 seed 两帧逐位相同 = {same}（预览不闪）；seed 0 vs 7.5 图案差异 '
          f'max|Δ| = {diff * 255:.1f} LSB（应显著 > 0 ⇒ 不同照片不同图案）')
    rep['seed'] = {'same_seed_identical': same, 'diff_seed_lsb': diff * 255}
    ok &= same and diff > 1e-4

    # ⑧ 短边归一化（ADR-11）：同一内容两种分辨率 ⇒ **相对**特征周期一致。
    # 判据：周期/短边 的比值在 n 与 n/2 下接近（相差 < 15%）。
    # ⚠ 用 ISO 6400 而不是 1600：小图（n/2）上最高频八度必须仍 >2px 才不混叠 ——
    # ISO 1600 需要短边 ≥1728，n/2=1536 不满足；ISO 6400 只需 ≥813 ✓（见 ③ 的说明）。
    small = n // 2
    grey_s = np.full((small, small, 3), 128, np.uint8)
    inc_s = grain_inc(gl, eso, sh, grey_s, lut_off, grain=50.0, grain_size=6400.0)
    ks, Ps = radial_psd(inc_s)
    cs = psd_centroid_k(ks, Ps)
    r_big, r_small = (1 / cent[6400]) / n, (1 / cs) / small
    dev = abs(r_big - r_small) / max(r_big, r_small)
    print(f'⑧ 短边归一化（ISO 6400）：{n}px 上周期/短边 = {r_big:.5f}、{small}px 上 = {r_small:.5f}'
          f'（相对偏差 {dev * 100:.1f}%，判据 <15%）')
    rep['short_edge_norm'] = {'big': r_big, 'small': r_small, 'dev': dev}
    ok &= dev < 0.15

    # ⑨ 目视截图（判据 2 的另一半证据）。全图看不出颗粒（3072² 缩到屏幕尺寸后被重采样
    # 抹掉）⇒ 裁中心区。对照必须**归一化到相同 RMS**，否则差异只是"幅度"而不是"形态"
    #（旧实现 value noise 的幅度天然大得多：21.6 vs 2.3 L 单位，直接比会看成"更明显"）。
    nn = 512
    g_s = np.full((nn, nn, 3), 128, np.uint8)
    crop = slice(nn // 4, 3 * nn // 4)

    def rms_norm_crop(arr):
        c = arr[crop, crop]
        return np.clip(128.0 + c / max(float(c.std()), 1e-9) * 12.0, 0, 255).astype(np.uint8)

    for tag, kw in (('new_iso1600_s25', dict(grain=25.0, grain_size=1600.0)),
                    ('new_iso100_s25', dict(grain=25.0, grain_size=100.0))):
        arr = grain_inc(gl, eso, sh, g_s, lut_off, **kw)
        write_png_gray(os.path.join(OUT, f'crop_{tag}_rmsnorm.png'), rms_norm_crop(arr))
    inc_old = old_vnoise(nn, nn, size=24.0, seed=0.0)
    inc_old -= inc_old.mean()
    write_png_gray(os.path.join(OUT, 'crop_old_size24_rmsnorm.png'), rms_norm_crop(inc_old))
    print(f'⑨ 目视截图（512² 中心 256² 裁切，均归一化到 RMS = 12 L 单位）：'
          f'out/crop_new_iso1600_s25_rmsnorm.png、out/crop_new_iso100_s25_rmsnorm.png、'
          f'out/crop_old_size24_rmsnorm.png（后者 = 旧实现调到同量级视觉尺度，块状）')
    lut_off.release()
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--size', type=int, default=3072,
                    help='实验图边长；**必须 ≥3072** 才无混叠（见 ③ 的说明）')
    ap.add_argument('--quick', action='store_true', help='只跑 LUT 对照（跳过真跑 shader）')
    args = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    rep = {'size': args.size}
    ok = exp_lut(rep)
    if not args.quick:
        eso = csp.load_eso()
        sh = eso.extract_shaders()
        gl = eso.GL(args.size, args.size)
        try:
            ok &= exp_shader(gl, eso, sh, args.size, rep)
        finally:
            gl.close()
    with open(os.path.join(OUT, 'report.json'), 'w') as f:
        json.dump(rep, f, indent=2, ensure_ascii=False)
    print(f'\n产物：{OUT}/（report.json + psd_*.npy + grain_*.png）')
    print('全部通过' if ok else '存在失败项')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
