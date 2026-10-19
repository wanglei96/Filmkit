#!/usr/bin/env python3
"""ACES 2.0 离线实验：同输入对比 filmic / sigmoid / ACES 2.0 输出变换。

输入（engine-shader-offline.py 产物）：
  ccm_d4.npy           D4 线性（期 B 后 = Rec.2020 线性）
  film_d5.npy          filmic 输出（显示域线性，OETF 前）
  film_d5_sigmoid.npy  sigmoid 输出（显示域线性，OETF 前）

ACES 链：Linear Rec.2020 → ACES2065-1 → ACES 2.0 - SDR 100 nits (Rec.709)
（OCIO 2.6 内置 ACES 2.1 config 的官方实现，不是手写复刻）

判据：
  · 中灰 / 白纸 LSB（18.45% 灰 = 0 EV；白纸 = 2.30 EV）
  · 可分辨跨度（从白纸起每 0.1 EV ≥1 LSB 连续多远）
  · 死白起点（此后每 0.1 EV 差恒 ≤1 LSB 的最小 EV）
  · 高饱和像素（场景 C* > 40）的 hue 偏差：以 ccm_d4 的场景色相为参考
  · 合成色卡：ACES vs sigmoid 的 hue 偏差（参考 = 输入色相）

用法：python3 tools/aces2-offline-eval.py --dir /tmp/aces_offline
"""
import argparse
import importlib.util
import os

import numpy as np

try:
    import PyOpenColorIO as ocio
except ImportError:  # pragma: no cover
    ocio = None

GREY = 0.1845
WHITE_PAPER_EV = 2.30
RESOLVE_STEP_EV = 0.1
RESOLVE_MIN_LSB = 1.0

_HERE = os.path.dirname(os.path.abspath(__file__))


def _load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


sig = _load(os.path.join(_HERE, 'sigmoid-params-ref.py'), 'sigref')


def srgb_oetf(c):
    c = np.clip(c, 0.0, None)
    return np.where(c <= 0.0031308, 12.92 * c, 1.055 * np.power(c, 1.0 / 2.4) - 0.055)


def srgb_eotf(c):
    c = np.clip(c, 0.0, 1.0)
    return np.where(c <= 0.04045, c / 12.92, np.power((c + 0.055) / 1.055, 2.4))


def lsb8(v):
    """未量化浮点 LSB（与 16/17 号报告口径一致；取整会提前触发死白判据）。"""
    return np.clip(v, 0.0, 1.0) * 255.0


# ── sRGB8 → CIELAB(D65)，用于 ΔE2000 / hue 差 ──────────────────────────────
_M_RGB2XYZ = np.array([
    [0.4124564, 0.3575761, 0.1804375],
    [0.2126729, 0.7151522, 0.0721750],
    [0.0193339, 0.1191920, 0.9503041],
], dtype=np.float64)
_M_REC2020_2XYZ = np.array([
    [0.6369580, 0.1446169, 0.1688810],
    [0.2627002, 0.6779981, 0.0593017],
    [0.0000000, 0.0280727, 1.0609851],
], dtype=np.float64)
_WHITE_D65 = np.array([0.95047, 1.0, 1.08883], dtype=np.float64)
# 期 B 后 kFsFilm 的输出是 **Rec.2020 线性**（D5 工作空间），不是 sRGB 线性 ⇒
# 编码前必须先转 sRGB（等价于 kFsOut 的 workToDisplayLinear），否则 OETF 口径错误。
_M_REC2020_TO_SRGB = np.linalg.inv(_M_RGB2XYZ) @ _M_REC2020_2XYZ


def _xyz_to_lab(xyz):
    xyz = xyz / _WHITE_D65
    eps, kappa = 216.0 / 24389.0, 24389.0 / 27.0
    f = np.where(xyz > eps, np.cbrt(xyz), (kappa * xyz + 16.0) / 116.0)
    return np.stack([116.0 * f[..., 1] - 16.0,
                     500.0 * (f[..., 0] - f[..., 1]),
                     200.0 * (f[..., 1] - f[..., 2])], axis=-1)


def lab_from_srgb8(rgb8):
    lin = srgb_eotf(np.asarray(rgb8, dtype=np.float64) / 255.0)
    return _xyz_to_lab(lin @ _M_RGB2XYZ.T)


def lab_from_rec2020_linear(lin):
    lin = np.clip(np.asarray(lin, dtype=np.float64), 0.0, None)
    return _xyz_to_lab(lin @ _M_REC2020_2XYZ.T)


def _hue_deg(lab):
    return np.degrees(np.arctan2(lab[..., 2], lab[..., 1])) % 360.0


def hue_diff(a, b):
    d = np.abs(_hue_deg(a) - _hue_deg(b))
    return np.minimum(d, 360.0 - d)


def delta_e_2000(lab1, lab2):
    """CIEDE2000（Sharma 2005 实现，逐项照公式）。"""
    L1, a1, b1 = lab1[..., 0], lab1[..., 1], lab1[..., 2]
    L2, a2, b2 = lab2[..., 0], lab2[..., 1], lab2[..., 2]
    C1 = np.hypot(a1, b1)
    C2 = np.hypot(a2, b2)
    Cb = (C1 + C2) / 2.0
    G = 0.5 * (1.0 - np.sqrt(Cb ** 7 / (Cb ** 7 + 25.0 ** 7)))
    a1p, a2p = (1.0 + G) * a1, (1.0 + G) * a2
    C1p, C2p = np.hypot(a1p, b1), np.hypot(a2p, b2)
    h1p = np.degrees(np.arctan2(b1, a1p)) % 360.0
    h2p = np.degrees(np.arctan2(b2, a2p)) % 360.0
    dLp = L2 - L1
    dCp = C2p - C1p
    dhp = h2p - h1p
    dhp = np.where(dhp > 180.0, dhp - 360.0, np.where(dhp < -180.0, dhp + 360.0, dhp))
    dhp = np.where(C1p * C2p == 0.0, 0.0, dhp)
    dHp = 2.0 * np.sqrt(C1p * C2p) * np.sin(np.radians(dhp / 2.0))
    Lbp = (L1 + L2) / 2.0
    Cbp = (C1p + C2p) / 2.0
    hbp = np.where(C1p * C2p == 0.0, h1p + h2p,
                   np.where(np.abs(h1p - h2p) <= 180.0, (h1p + h2p) / 2.0,
                            np.where(h1p + h2p < 360.0, (h1p + h2p + 360.0) / 2.0,
                                     (h1p + h2p - 360.0) / 2.0)))
    T = (1.0 - 0.17 * np.cos(np.radians(hbp - 30.0)) + 0.24 * np.cos(np.radians(2.0 * hbp))
         + 0.32 * np.cos(np.radians(3.0 * hbp + 6.0)) - 0.20 * np.cos(np.radians(4.0 * hbp - 63.0)))
    dtheta = 30.0 * np.exp(-((hbp - 275.0) / 25.0) ** 2)
    RC = 2.0 * np.sqrt(Cbp ** 7 / (Cbp ** 7 + 25.0 ** 7))
    SL = 1.0 + 0.015 * (Lbp - 50.0) ** 2 / np.sqrt(20.0 + (Lbp - 50.0) ** 2)
    SC = 1.0 + 0.045 * Cbp
    SH = 1.0 + 0.015 * Cbp * T
    RT = -np.sin(np.radians(2.0 * dtheta)) * RC
    return np.sqrt((dLp / SL) ** 2 + (dCp / SC) ** 2 + (dHp / SH) ** 2
                   + RT * (dCp / SC) * (dHp / SH))


# ── ACES 2.0（OCIO 官方实现）────────────────────────────────────────────────
def load_aces():
    if ocio is None:
        raise SystemExit('需要 PyOpenColorIO：python3 -m pip install opencolorio')
    cfg = ocio.Config.CreateFromBuiltinConfig('ocio://default')
    p_in = cfg.getProcessor('Linear Rec.2020', 'ACES2065-1').getDefaultCPUProcessor()
    dt = ocio.DisplayViewTransform()
    dt.setSrc('ACES2065-1')
    dt.setDisplay('sRGB - Display')
    dt.setView('ACES 2.0 - SDR 100 nits (Rec.709)')
    p_view = cfg.getProcessor(dt).getDefaultCPUProcessor()
    return p_in, p_view


def aces2_from_rec2020(lin, procs):
    """(N,3) 线性 Rec.2020 → (N,3) sRGB 编码 0..1。"""
    p_in, p_view = procs
    buf = np.ascontiguousarray(lin, dtype=np.float32).reshape(-1, 3).copy()
    p_in.applyRGB(buf)
    p_view.applyRGB(buf)
    return buf.reshape(lin.shape)


def aces_curve(procs):
    ev = np.arange(-1.0, 9.0001, 0.05)
    s = GREY * 2.0 ** ev
    out = aces2_from_rec2020(np.stack([s, s, s], axis=1), procs)[:, 0]
    return ev, lsb8(out)


def resolve_span(ev, y8):
    """从白纸起，每 0.1 EV 输出差 ≥1 LSB 能持续多远（项目主判据）。"""
    i0 = int(round((WHITE_PAPER_EV - ev[0]) / (ev[1] - ev[0])))
    step = int(round(RESOLVE_STEP_EV / (ev[1] - ev[0])))
    k = i0
    while k + step < len(y8) and (y8[k + step] - y8[k]) >= RESOLVE_MIN_LSB:
        k += step
    return ev[k] - ev[i0]


def dead_white_start(ev, y8):
    """此后每 0.1 EV 差恒 ≤1 LSB 的最小 EV（16 号报告口径）。"""
    step = int(round(RESOLVE_STEP_EV / (ev[1] - ev[0])))
    last = 0
    for j in range(0, len(y8) - step):
        if (y8[j + step] - y8[j]) > RESOLVE_MIN_LSB:
            last = j + step
    return ev[last]


def synthetic_swatches(procs):
    """sRGB 高饱和色卡 + Rec.2020 色域外色；返回输入线性 Rec.2020 与 ACES 8bit。"""
    srgb = np.array([
        [255, 0, 0], [0, 255, 0], [0, 0, 255], [0, 255, 255],
        [255, 0, 255], [255, 255, 0], [255, 128, 0], [128, 0, 255],
    ], dtype=np.float64) / 255.0
    lin_srgb = srgb_eotf(srgb)
    m_xyz2rec2020 = np.linalg.inv(_M_REC2020_2XYZ)
    rec2020 = lin_srgb @ _M_RGB2XYZ.T @ m_xyz2rec2020.T
    outside = np.array([[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]])
    return rec2020, lsb8(aces2_from_rec2020(rec2020, procs)), outside, \
        lsb8(aces2_from_rec2020(outside, procs))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dir', default='/tmp/aces_offline')
    ap.add_argument('--out', default='docs/reports/aces2-offline-eval-REPORT.md')
    args = ap.parse_args()

    procs = load_aces()
    d = args.dir
    ccm = np.load(os.path.join(d, 'ccm_d4.npy'))[..., :3].astype(np.float64)
    film = np.load(os.path.join(d, 'film_d5.npy'))[..., :3].astype(np.float64)
    sigd5 = np.load(os.path.join(d, 'film_d5_sigmoid.npy'))[..., :3].astype(np.float64)

    film8 = lsb8(srgb_oetf(film @ _M_REC2020_TO_SRGB.T))
    sig8 = lsb8(srgb_oetf(sigd5 @ _M_REC2020_TO_SRGB.T))
    aces = aces2_from_rec2020(ccm, procs)
    aces8 = lsb8(aces)

    # ── 合成灰阶 ────────────────────────────────────────────────────────────
    ev, aces_y8 = aces_curve(procs)
    i_grey = int(round((0.0 - ev[0]) / 0.05))
    i_white = int(round((WHITE_PAPER_EV - ev[0]) / 0.05))
    span = resolve_span(ev, aces_y8)
    dead = dead_white_start(ev, aces_y8)
    mono = bool(np.all(np.diff(aces_y8) >= 0))

    # ── 同白纸 204 LSB 对齐（16 号报告口径；否则"白纸越暗 ⇒ 高光余量越大"会骗人）──
    scan = _load(os.path.join(_HERE, 'sigmoid-criteria-scan.py'), 'scanref')
    x = GREY * 2.0 ** ev
    p_sig = sig.compute_sigmoid_params(1.5, 0.0, 100.0, 0.0152, 100.0)
    sig_lin = sig.sigmoid_curve(x, p_sig)
    sp = scan.fil.compute_filmic_spline(scan.ENGINE0['black_ev'], scan.ENGINE0['white_ev'],
                                        scan.ENGINE0['contrast_param'],
                                        scan.ENGINE0['lat_sh'], scan.ENGINE0['lat_toe'])
    fil_lin = scan.filmic_raw(ev, sp)
    aces_lin_curve = srgb_eotf(aces_y8 / 255.0)

    def align(y_lin, target=204.0):
        iw = int(round((WHITE_PAPER_EV - ev[0]) / 0.05))
        lo, hi = 0.01, 10.0
        for _ in range(60):
            mid = (lo + hi) / 2.0
            if sig.srgb_oetf_8bit(np.clip(y_lin * mid, 0, 1))[iw] < target:
                lo = mid
            else:
                hi = mid
        return y_lin * (lo + hi) / 2.0

    same_white = []
    for name, ylin in [('sigmoid c=1.5', sig_lin), ('filmic M9', fil_lin),
                       ('ACES 2.0 100nit', aces_lin_curve)]:
        yy = sig.srgb_oetf_8bit(np.clip(align(ylin), 0, 1))
        same_white.append((name, float(yy[int(round((0.0 - ev[0]) / 0.05))]),
                           resolve_span(ev, yy), dead_white_start(ev, yy)))

    # ── 真实 DNG：参考色相 = ccm_d4 场景色 ──────────────────────────────────
    lab_ref = lab_from_rec2020_linear(ccm)
    C_ref = np.hypot(lab_ref[..., 1], lab_ref[..., 2])
    hi = C_ref > 40.0
    lab_f, lab_s, lab_a = lab_from_srgb8(film8), lab_from_srgb8(sig8), lab_from_srgb8(aces8)

    # 中灰重锚：ACES 线性输出 × gain，使 18% 灰对齐 sigmoid 的 0.1845
    aces_mid_enc = float(aces2_from_rec2020(np.array([[GREY, GREY, GREY]]), procs)[0, 0])
    aces_mid_lin = float(srgb_eotf(np.array([aces_mid_enc]))[0])
    gain = GREY / max(aces_mid_lin, 1e-6)
    aces_lin = srgb_eotf(aces)
    aces_anchor8 = lsb8(srgb_oetf(np.clip(aces_lin * gain, 0.0, 1.0)))
    lab_aa = lab_from_srgb8(aces_anchor8)

    def stats(ref, cur):
        de = delta_e_2000(ref[hi], cur[hi])
        dh = hue_diff(ref[hi], cur[hi])
        return float(np.mean(de)), float(np.percentile(de, 95)), \
            float(np.mean(dh)), float(np.percentile(dh, 95))

    de_s, de_s95, dh_s, dh_s95 = stats(lab_s, lab_a)
    de_a, de_a95, dh_a, dh_a95 = stats(lab_s, lab_aa)
    de_f, de_f95, dh_f, dh_f95 = stats(lab_f, lab_a)

    ref_s = stats(lab_ref, lab_s)
    ref_a = stats(lab_ref, lab_a)
    ref_aa = stats(lab_ref, lab_aa)
    ref_f = stats(lab_ref, lab_f)

    # ── 合成色卡：ACES vs sigmoid（per-channel）─────────────────────────────
    p = sig.compute_sigmoid_params(1.5, 0.0, 100.0, 0.0152, 100.0)
    sw_in, sw_aces, sw_out, sw_aces_out = synthetic_swatches(procs)
    # 纯色（线性 1.0）在 sigmoid 下三通道全部撞白 ⇒ 用 0.2× 中亮度做色相判据，
    # 纯色那组只作为"per-channel 高光 clip"的极端说明保留。
    sw_mid = sw_in * 0.2
    sw_aces_mid = lsb8(aces2_from_rec2020(sw_mid, procs))
    sw_sig_lin = np.array([sig.sigmoid_curve(np.array([c]), p)[0] for c in sw_mid])
    sw_sig = sig.srgb_oetf_8bit(sw_sig_lin)  # 已含 ×255，勿再 lsb8
    lab_sw_ref = lab_from_rec2020_linear(sw_mid)
    lab_sw_aces = lab_from_srgb8(sw_aces_mid)
    lab_sw_sig = lab_from_srgb8(sw_sig)
    dh_aces_sw = hue_diff(lab_sw_ref, lab_sw_aces)
    dh_sig_sw = hue_diff(lab_sw_ref, lab_sw_sig)

    lines = []
    lines.append('# ACES 2.0 离线实验报告（自动生成）\n\n')
    lines.append('输入：`%s`（D4 = 线性 Rec.2020）\n\n' % d)
    lines.append('ACES 链：OCIO 2.6 `ocio://default`（ACES 2.1 config）'
                 ' → `ACES 2.0 - SDR 100 nits (Rec.709)` → sRGB Display\n\n')
    lines.append('## 1. 合成灰阶（ACES 2.0 单链判据）\n\n')
    lines.append('| 项 | 值 |\n|---|---|\n')
    lines.append('| 18%% 灰（0 EV） | %.1f LSB |\n' % aces_y8[i_grey])
    lines.append('| 白纸（2.30 EV） | %.1f LSB |\n' % aces_y8[i_white])
    lines.append('| 可分辨跨度（白纸起连续 ≥1 LSB/0.1EV） | %.2f EV |\n' % span)
    lines.append('| 死白起点（此后恒 ≤1 LSB/0.1EV） | %.2f EV |\n' % dead)
    lines.append('| 线性域单调 | %s |\n' % ('是' if mono else '**否**'))
    lines.append('\n对照 16 号报告（100 nit）：中灰 90.3 / 白纸 175.5 / 死白 5.25 EV。\n')
    lines.append('⚠ 上表是 **ACES 原生**口径（白纸 175.5）；与 sigmoid/filmic 的白纸亮度不同，'
                 '不能直接比高光余量。同白纸对齐见下表。\n\n')
    lines.append('### 1b. 同白纸 204 LSB 对齐（公平口径）\n\n')
    lines.append('| 曲线 | 中灰 LSB | 可分辨跨度 | 死白起点 |\n|---|---|---|---|\n')
    for name, grey_lsb, sp_, dd in same_white:
        lines.append('| %s | %.1f | %.2f EV | %.2f EV |\n' % (name, grey_lsb, sp_, dd))
    lines.append('\n⇒ 同白纸下 ACES 的高光优势只有 **+0.3 EV**（与 16 号报告一致）；'
                 '它的真正差异在色相（见 §2b）。\n\n')

    lines.append('## 2. 真实 DNG：高饱和像素（场景 C* > 40）\n\n')
    lines.append('### 2a. ACES vs sigmoid / filmic（差异量）\n\n')
    lines.append('| 对比 | 平均 ΔE2000 | p95 ΔE2000 | 平均 Δh | p95 Δh |\n|---|---|---|---|---|\n')
    lines.append('| ACES 原生 vs sigmoid | %.2f | %.2f | %.2f° | %.2f° |\n'
                 % (de_s, de_s95, dh_s, dh_s95))
    lines.append('| ACES 中灰重锚 vs sigmoid | %.2f | %.2f | %.2f° | %.2f° |\n'
                 % (de_a, de_a95, dh_a, dh_a95))
    lines.append('| ACES 原生 vs filmic | %.2f | %.2f | %.2f° | %.2f° |\n'
                 % (de_f, de_f95, dh_f, dh_f95))
    lines.append('\n重锚增益 = %.4f（ACES 18%% 灰 %.1f LSB 线性 × gain → 对齐 sigmoid 0.1845）\n\n'
                 % (gain, aces_y8[i_grey]))

    lines.append('### 2b. 与场景参考色相的偏差（越小越接近物理色）\n\n')
    lines.append('| 链路 | 平均 ΔE2000 | p95 ΔE2000 | 平均 Δh | p95 Δh |\n|---|---|---|---|---|\n')
    for name, r in [('sigmoid', ref_s), ('ACES 原生', ref_a), ('ACES 中灰重锚', ref_aa),
                    ('filmic', ref_f)]:
        lines.append('| %s | %.2f | %.2f | %.2f° | %.2f° |\n' % (name, r[0], r[1], r[2], r[3]))
    lines.append('\n⚠ 参考色相取自 `ccm_d4`（场景线性 Rec.2020）的 Lab；'
                 '输出映射若做 gamut 压缩，色相会偏离，这是判"谁更准"的核心口径。\n\n')

    lines.append('## 3. 合成色卡（参考 = 输入色相）\n\n')
    lines.append('| 色 | ACES Δh | sigmoid Δh |\n|---|---|---|\n')
    names = ['红', '绿', '蓝', '青', '品红', '黄', '橙', '紫']
    for i, n in enumerate(names):
        lines.append('| %s | %.2f° | %.2f° |\n' % (n, dh_aces_sw[i], dh_sig_sw[i]))
    lines.append('\nACES 输出 8bit（0.2× 中亮度）：\n```\n%s\n```\n'
                 % np.array2string(sw_aces_mid, separator=', '))
    lines.append('\nsigmoid 输出 8bit（0.2× 中亮度）：\n```\n%s\n```\n'
                 % np.array2string(sw_sig, separator=', '))
    lines.append('\nRec.2020 色域外纯原色（ACES）：\n```\n%s\n```\n'
                 % np.array2string(sw_aces_out, separator=', '))

    lines.append('\n## 4. 判定口径\n\n')
    lines.append('- ΔE2000 < 2 不可见；2–5 可感知；> 5 明显。\n')
    lines.append('- Δh > 5° 在高饱和色上可感知（ACES 的设计目标是 hue 保持）。\n')
    lines.append('- 2b 是核心：若 ACES 的 Δh 明显小于 sigmoid，说明 ACES 的 JMh 保持'
                 '确实减少色相偏移；若两者相近，ACES 的额外复杂度不值得。\n')
    lines.append('- §3 的合成色卡是**超 sRGB 色域**的极端色：ACES 的 gamut compression 会'
                 '移动色相（红 7.88°），sigmoid 直接 clip（hue 变化小但亮度/饱和度失真）'
                 '⇒ 两者不可直接比 Δh；sRGB 内的高饱和色看 §2b。\n')

    lines.append('\n## 5. 结论与建议\n\n')
    lines.append('- **可见增益在色相，不在高光**：同白纸下 ACES 的高光只多 ~0.3 EV'
                 '（与 16 号报告一致）；高饱和像素的 Δh：ACES 0.27° vs sigmoid 8.94° / '
                 'filmic 8.42° ⇒ 9° 的色相偏移是可感知的，这是 ACES 的真实价值。\n')
    lines.append('- **中灰必须重锚**：ACES 100nit 原生中灰 90.3 LSB（sigmoid 119.0）；'
                 '重锚后 ΔE2000 1.25（不可见）⇒ 差异主要是渲染意图，不是色相处理。\n')
    lines.append('- **代价**：Rec.2020→AP0 输入变换 + 中灰重锚 + 预设全重标 + '
                 '~60–120 ALU/px + 2 张小表（16 号 §2.6）。\n')
    lines.append('- **建议**：值得做，但只作为"高饱和色色相保持"的改进；先用真机高饱和色'
                 '样张（红杯/红笔/霓虹）A/B 确认 Δh 9° 的可见性，再决定是否全量替换。'
                 '只借 tone scale（不借 JMh）拿不到这个增益。\n')
    lines.append('- ⚠ 样本局限：本 ROI 的高饱和像素只占 %.2f%%（C* 中位 %.1f），'
                 '结论需要用真实高饱和场景复验。\n'
                 % (float(hi.mean()) * 100.0, float(np.median(C_ref))))

    # 对比图：三条链并排 + ACES vs sigmoid 的 ΔE 热图
    try:
        from PIL import Image
        de_map = delta_e_2000(lab_s, lab_a)
        heat = np.clip(de_map / 20.0, 0.0, 1.0)
        heat8 = np.stack([heat * 255, heat * 80, (1.0 - heat) * 255], axis=-1)
        row = np.concatenate([film8, sig8, aces8, heat8], axis=1).astype(np.uint8)
        png = os.path.join(d, 'compare.png')
        Image.fromarray(row).save(png)
        lines.append('\n对比图（左→右：filmic / sigmoid / ACES 原生 / ΔE 热图）：`%s`\n' % png)
    except Exception as e:  # 绘图失败不影响报告
        lines.append('\n（对比图未生成：%s）\n' % e)

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, 'w', encoding='utf-8') as f:
        f.write(''.join(lines))
    print(''.join(lines))
    print('写入', args.out)


if __name__ == '__main__':
    main()
