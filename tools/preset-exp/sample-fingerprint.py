#!/usr/bin/env python3
"""样片 / LUT 指纹提取 —— 为「目标风格指纹」与「我们 LUT 现状」提供同一套度量。

依赖：python3 + numpy + pillow（无其它第三方依赖）
    python3 -m pip install numpy pillow

用法
----
  # 1) LUT 指纹（读 app/src/main/assets/luts/*.lut）
  python3 tools/preset-exp/sample-fingerprint.py lut --ids leica_m9,ricoh_negative,leica_b_w_hc \
      --out tools/preset-exp/fingerprint/lut-fingerprint.json

  # 2) 样片目录指纹（目录内 jpg/png，只统计，不改图）
  python3 tools/preset-exp/sample-fingerprint.py samples --name ricoh-negative-official \
      --dir /tmp/fk-samples/ricoh --out tools/preset-exp/fingerprint/sample-ricoh-negative-official.json

  # 3) 成对样片净效应（同场景：风格图 vs 参考图）
  python3 tools/preset-exp/sample-fingerprint.py pair --styled a.jpg --ref b.jpg --out out.json

  # 4) 自检（合成图，不需要任何素材）
  python3 tools/preset-exp/sample-fingerprint.py selftest

LUT 资产格式（app/src/main/assets/luts/*.lut，由 tools/gen-lut-assets.py 生成）：
  [size:1B][titleLen:1B][title][catLen:1B][category][data: size^3 * 3B]
  数组维度 = [B][G][R]（R 是最快变化的轴，与 .cube 一致）。

口径说明（重要，报告里必须一起引用）
------------------------------------
* 样片没有「输入参考」，因此**样片侧只产出分布型指标**（分位数、chroma、色偏），
  不产出「影调曲线」；`mid_slope` 一类曲线斜率只有 LUT 侧有。
* 两侧**可比的维度**：黑白场位置、对比跨度、中性色偏移、平均 chroma、分区色偏。
* 颗粒只在样片侧测（LUT 是纯色彩映射，不含颗粒）。
"""
import argparse
import json
import pathlib
import sys

import numpy as np
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[2]
LUT_DIR = ROOT / 'app/src/main/assets/luts'
OUT_DIR = pathlib.Path(__file__).resolve().parent / 'fingerprint'

# ---------------------------------------------------------------- 基础工具

def srgb_to_linear(x):
    x = np.asarray(x, dtype=np.float64)
    return np.where(x <= 0.04045, x / 12.92, ((x + 0.055) / 1.055) ** 2.4)


def luma(rgb):
    """Rec.709 luma（**gamma 域**加权，0..1）。

    用 gamma 域而非线性光，是为了让「亮度分位数 / 直方图」与肉眼和常见工具的读数一致；
    Lab 相关的计算另走 `rgb_to_lab`（那里才必须线性化）。
    """
    rgb = np.asarray(rgb, dtype=np.float64)
    return np.clip(0.2126 * rgb[..., 0] + 0.7152 * rgb[..., 1] + 0.0722 * rgb[..., 2], 0, 1)


def rgb_to_lab(rgb):
    """sRGB(0..1) -> CIE L*a*b* (D65)。"""
    lin = srgb_to_linear(rgb)
    m = np.array([[0.4124564, 0.3575761, 0.1804375],
                  [0.2126729, 0.7151522, 0.0721750],
                  [0.0193339, 0.1191920, 0.9503041]])
    xyz = lin @ m.T
    wp = np.array([0.95047, 1.0, 1.08883])
    t = xyz / wp
    d = 6 / 29
    f = np.where(t > d ** 3, np.cbrt(np.maximum(t, 0)), t / (3 * d * d) + 4 / 29)
    L = 116 * f[..., 1] - 16
    a = 500 * (f[..., 0] - f[..., 1])
    b = 200 * (f[..., 1] - f[..., 2])
    return np.stack([L, a, b], -1)


def pct(a, q):
    return float(np.percentile(a, q))


# ---------------------------------------------------------------- LUT

def load_lut(path):
    d = path.read_bytes()
    i = 0
    size = d[i]; i += 1
    tl = d[i]; i += 1
    title = d[i:i + tl].decode('utf-8'); i += tl
    cl = d[i]; i += 1
    cat = d[i:i + cl].decode('utf-8'); i += cl
    data = np.frombuffer(d[i:], dtype=np.uint8)
    assert len(data) == size ** 3 * 3, f'{path.name}: {len(data)} != {size**3*3}'
    # 数组维度 [B][G][R]
    return size, title, cat, data.reshape(size, size, size, 3).astype(np.float64) / 255.0


def apply_lut(img, lut):
    n = lut.shape[0]
    t = np.clip(img, 0, 1) * (n - 1)
    i0 = np.clip(np.floor(t).astype(np.int64), 0, n - 2)
    f = t - i0
    out = np.zeros_like(img, dtype=np.float64)
    for db in (0, 1):
        for dg in (0, 1):
            for dr in (0, 1):
                w = ((f[..., 0] if dr else 1 - f[..., 0]) *
                     (f[..., 1] if dg else 1 - f[..., 1]) *
                     (f[..., 2] if db else 1 - f[..., 2]))[..., None]
                out += w * lut[i0[..., 2] + db, i0[..., 1] + dg, i0[..., 0] + dr]
    return np.clip(out, 0, 1)


def lut_fingerprint(lid, size, title, cat, lut):
    idx = np.arange(size)
    gray = lut[idx, idx, idx]                                  # 中性灰阶 [n][3]
    gray_l = gray.mean(axis=1)

    def slope(lo, hi):
        """输入 0..1 区间 [lo,hi] 上输出亮度的最小二乘斜率（1.0 = 线性）。"""
        m = (idx / (size - 1) >= lo) & (idx / (size - 1) <= hi)
        if m.sum() < 3:
            return None
        x = idx[m] / (size - 1)
        return float(np.polyfit(x, gray_l[m], 1)[0])

    # 全灰阶曲线（每 8 级一个采样点）
    steps = list(range(0, 256, 8))
    curve = []
    for s in steps:
        k = int(round(s / 255 * (size - 1)))
        curve.append([s] + [round(float(gray[k][c]) * 255, 2) for c in range(3)])

    # 中性轴色偏：对 R=G=B 输入，输出相对输入的通道偏移
    neutral_dev = (gray - (idx / (size - 1))[:, None]) * 255

    # 饱和色响应（注意轴序 [B][G][R]）；用三线性插值，避免格点量化误差
    def at(r, g, b):
        px = np.array([[[float(r), float(g), float(b)]]])
        return [round(float(v) * 255, 2) for v in apply_lut(px, lut)[0, 0]]

    # 饱和度增益：对一组测试色算 chroma 增益（Lab 半径比）
    tests = [(1, 0, 0), (0, 1, 0), (0, 0, 1), (1, 1, 0), (0, 1, 1), (1, 0, 1),
             (0.6, 0.3, 0.2), (0.3, 0.5, 0.7), (0.5, 0.5, 0.5)]
    gains = []
    for c in tests:
        cin = np.array(c, dtype=np.float64)
        cout = np.array(at(*c), dtype=np.float64) / 255
        li, lo = rgb_to_lab(cin), rgb_to_lab(cout)
        ci = np.hypot(li[1], li[2]); co = np.hypot(lo[1], lo[2])
        if ci > 3:
            gains.append(float(co / ci))

    return {
        'kind': 'lut',
        'id': lid,
        'title': title,
        'category': cat,
        'size': size,
        'tone': {
            'gray_curve_in_out_255': curve,
            'black_point_255': [round(float(gray[0][c]) * 255, 2) for c in range(3)],
            'white_point_255': [round(float(gray[-1][c]) * 255, 2) for c in range(3)],
            'gray_p25_p50_p75_255': [round(float(gray_l[int(size * q)]) * 255, 2) for q in (0.25, 0.5, 0.75)],
            'mid_slope': round(slope(0.35, 0.65), 4),
            'shadow_slope': round(slope(0.10, 0.35), 4),
            'highlight_slope': round(slope(0.65, 0.92), 4),
            'contrast_span_out255': round((float(gray_l[-1]) - float(gray_l[0])) * 255, 2),
        },
        'color': {
            'neutral_dev_rgb_255_at_25_50_75': [
                [round(float(neutral_dev[int(size * q)][c]), 2) for c in range(3)] for q in (0.25, 0.5, 0.75)
            ],
            'neutral_dev_mean_rgb_255': [round(float(neutral_dev[:, c].mean()), 2) for c in range(3)],
            'neutral_dev_max_abs_255': round(float(np.abs(neutral_dev).max()), 2),
            'warm_cool_r_minus_b_at_50': round(float(neutral_dev[int(size * 0.5)][0] - neutral_dev[int(size * 0.5)][2]), 2),
            'sat_gain_mean': round(float(np.mean(gains)), 4) if gains else None,
            'sat_gain_min': round(float(np.min(gains)), 4) if gains else None,
            'sat_gain_max': round(float(np.max(gains)), 4) if gains else None,
            'pure_red_in_out_255': at(1, 0, 0),
            'pure_green_in_out_255': at(0, 1, 0),
            'pure_blue_in_out_255': at(0, 0, 1),
            'is_monochrome': bool(max(np.abs(lut[..., 0] - lut[..., 1]).max(),
                                      np.abs(lut[..., 1] - lut[..., 2]).max()) < 0.02),
        },
    }


# ---------------------------------------------------------------- 图像

MAX_SIDE = 1600          # 统计前下采样上限（颗粒分析另用原分辨率中心区）


def _load_rgb(path, max_side=MAX_SIDE):
    im = Image.open(path).convert('RGB')
    orig = im.size
    if max(im.size) > max_side:
        s = max_side / max(im.size)
        im = im.resize((max(1, round(im.width * s)), max(1, round(im.height * s))), Image.LANCZOS)
    return np.asarray(im).astype(np.float64) / 255.0, orig


def tone_metrics(rgb):
    y = luma(rgb)
    flat = y.ravel()
    return {
        'luma_percentiles_255': {k: round(pct(flat, q) * 255, 2) for k, q in
                                 (('p0_1', 0.1), ('p1', 1), ('p5', 5), ('p25', 25), ('p50', 50),
                                  ('p75', 75), ('p95', 95), ('p99', 99), ('p99_9', 99.9))},
        'luma_mean_255': round(float(flat.mean()) * 255, 2),
        'luma_std_255': round(float(flat.std()) * 255, 2),
        'contrast_p95_minus_p5_255': round((pct(flat, 95) - pct(flat, 5)) * 255, 2),
        'contrast_p99_minus_p1_255': round((pct(flat, 99) - pct(flat, 1)) * 255, 2),
        'shadow_clip_frac': round(float((flat < 2 / 255).mean()), 5),
        'highlight_clip_frac': round(float((flat > 253 / 255).mean()), 5),
        'skewness': round(float(((flat - flat.mean()) ** 3).mean() / (flat.std() ** 3 + 1e-12)), 3),
    }


def color_metrics(rgb):
    lab = rgb_to_lab(rgb)
    L, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    C = np.hypot(a, b)
    neutral = C < 6                       # 近中性像素（Lab chroma < 6）
    y = luma(rgb)

    def ab_med(mask):
        if mask.sum() < 50:
            return None
        return [round(float(np.median(a[mask])), 2), round(float(np.median(b[mask])), 2)]

    hi = y > 0.75
    sh = y < 0.25
    return {
        'mean_chroma': round(float(C.mean()), 2),
        'chroma_p50': round(pct(C, 50), 2),
        'chroma_p90': round(pct(C, 90), 2),
        'chroma_p99': round(pct(C, 99), 2),
        'neutral_frac': round(float(neutral.mean()), 4),
        'neutral_ab_median': ab_med(neutral),                    # 中性像素在 a*b* 上的落点（= 白平衡倾向）
        'neutral_a_mean': round(float(a[neutral].mean()), 2) if neutral.sum() > 50 else None,
        'neutral_b_mean': round(float(b[neutral].mean()), 2) if neutral.sum() > 50 else None,
        'overall_ab_median': [round(float(np.median(a)), 2), round(float(np.median(b)), 2)],
        'highlight_ab_median': ab_med(hi & neutral),
        'shadow_ab_median': ab_med(sh & neutral),
        'sat_frac_chroma_gt20': round(float((C > 20).mean()), 4),
    }


def grain_metrics(path, block=48, max_patches=400):
    """平场噪声：在**原分辨率**上取低梯度块，按亮度分箱给 RMS，并给径向功率谱斜率。

    只在长边 >= 1200 的图上做（下采样会把噪声抹平）。
    """
    im = Image.open(path).convert('RGB')
    if max(im.size) < 1200:
        return {'skipped': 'resolution too low'}
    g = luma(np.asarray(im).astype(np.float64) / 255.0)
    H, W = g.shape
    h, w = H // block, W // block
    if h < 4 or w < 4:
        return {'skipped': 'too few blocks'}
    blocks = g[:h * block, :w * block].reshape(h, block, w, block).transpose(0, 2, 1, 3).reshape(-1, block * block)
    bmean = blocks.mean(1)
    bstd = blocks.std(1)
    bgrad = np.abs(np.diff(blocks, axis=1)).mean(1)
    # 平场判据：块内标准差与梯度都低（排除纹理与边缘）
    flat = (bstd < np.percentile(bstd, 30)) & (bgrad < np.percentile(bgrad, 30)) & (bmean > 0.02) & (bmean < 0.98)
    sel = blocks[flat]
    bmean_f = bmean[flat]
    if len(sel) < 10:
        return {'skipped': 'no flat patches'}
    if len(sel) > max_patches:
        pick = np.random.default_rng(0).choice(len(sel), max_patches, replace=False)
        sel = sel[pick]
        bmean_f = bmean_f[pick]
    rms = sel.std(1)                                  # 每个平场块的噪声 RMS（0..1）
    # 亮度分箱
    bins = [(0.0, 0.15), (0.15, 0.3), (0.3, 0.45), (0.45, 0.6), (0.6, 0.75), (0.75, 0.9), (0.9, 1.0)]
    by_luma = {}
    for lo, hi in bins:
        m = (bmean_f >= lo) & (bmean_f < hi)
        if m.sum() >= 5:
            by_luma[f'{lo:.2f}-{hi:.2f}'] = {'rms_255': round(float(rms[m].mean()) * 255, 3), 'n': int(m.sum())}
    # 径向功率谱（对平场块去均值 + Hann 窗后平均）
    win = np.hanning(block)[:, None] * np.hanning(block)[None, :]
    psd = np.zeros((block, block))
    for p in sel[:80]:
        q = (p.reshape(block, block) - p.mean()) * win
        psd += np.abs(np.fft.fftshift(np.fft.fft2(q))) ** 2
    psd /= min(len(sel), 80)
    cy = cx = block // 2
    yy, xx = np.mgrid[0:block, 0:block]
    r = np.hypot(yy - cy, xx - cx).astype(int)
    prof = np.bincount(r.ravel(), psd.ravel()) / np.maximum(np.bincount(r.ravel()), 1)
    prof = prof[1:block // 2]                          # 去掉 DC 与最外圈
    k = np.arange(1, len(prof) + 1)
    slope = float(np.polyfit(np.log(k), np.log(prof + 1e-20), 1)[0])
    return {
        'block': block,
        'n_flat_patches': int(flat.sum()),
        'flat_luma_mean_255': round(float(bmean[flat].mean()) * 255, 2),
        'rms_by_luma_255': by_luma,
        'rms_all_255': round(float(rms.mean()) * 255, 3),
        'psd_radial_loglog_slope': round(slope, 3),
        'psd_radial_profile': [round(float(v / (prof.max() + 1e-20)), 4) for v in prof[:24]],
    }


def image_fingerprint(path):
    rgb, orig = _load_rgb(path)
    rep = {'file': pathlib.Path(path).name, 'orig_size': list(orig)}
    rep['tone'] = tone_metrics(rgb)
    rep['color'] = color_metrics(rgb)
    g = grain_metrics(path)
    rep['grain'] = g
    return rep


def samples_fingerprint(name, files):
    per = [image_fingerprint(f) for f in files]
    keys_tone = ['luma_mean_255', 'luma_std_255', 'contrast_p95_minus_p5_255',
                 'contrast_p99_minus_p1_255', 'shadow_clip_frac', 'highlight_clip_frac', 'skewness']
    keys_col = ['mean_chroma', 'chroma_p50', 'chroma_p90', 'chroma_p99', 'neutral_frac',
                'neutral_a_mean', 'neutral_b_mean', 'sat_frac_chroma_gt20']

    def agg(getter):
        v = [getter(p) for p in per]
        v = [x for x in v if x is not None]
        if not v:
            return None
        return {'mean': round(float(np.mean(v)), 3), 'median': round(float(np.median(v)), 3),
                'min': round(float(np.min(v)), 3), 'max': round(float(np.max(v)), 3), 'n': len(v)}

    grain_rms = [p['grain'].get('rms_all_255') for p in per if isinstance(p['grain'], dict) and 'rms_all_255' in p['grain']]
    psd_slope = [p['grain'].get('psd_radial_loglog_slope') for p in per
                 if isinstance(p['grain'], dict) and 'psd_radial_loglog_slope' in p['grain']]
    return {
        'kind': 'samples',
        'name': name,
        'n_images': len(per),
        'tone': {k: agg(lambda p, k=k: p['tone'][k]) for k in keys_tone},
        'tone_percentile_median_255': {
            k: round(float(np.median([p['tone']['luma_percentiles_255'][k] for p in per])), 2)
            for k in per[0]['tone']['luma_percentiles_255']},
        'color': {k: agg(lambda p, k=k: p['color'][k]) for k in keys_col},
        'grain': {
            'rms_all_255': agg(lambda p: p['grain'].get('rms_all_255')) if grain_rms else None,
            'psd_radial_loglog_slope': agg(lambda p: p['grain'].get('psd_radial_loglog_slope')) if psd_slope else None,
        },
        'per_image': per,
    }


# ---------------------------------------------------------------- 成对净效应

def pair_effect(styled_path, ref_path):
    """同场景 风格图 ÷ 参考图 的净效应：分位数映射 + 分区色偏 + 对比度比。"""
    a, sa = _load_rgb(styled_path)
    b, sb = _load_rgb(ref_path)
    if a.shape != b.shape:
        h = min(a.shape[0], b.shape[0]); w = min(a.shape[1], b.shape[1])
        a = a[:h, :w]; b = b[:h, :w]
    ya, yb = luma(a).ravel(), luma(b).ravel()
    qs = np.arange(2, 100, 2)
    pa, pb = np.percentile(ya, qs), np.percentile(yb, qs)
    mapping = [[round(float(x) * 255, 2), round(float(y) * 255, 2)] for x, y in zip(pb, pa)]
    # 在参考图的中性暗/中/亮三区，看风格图的 a*b* 位移
    labb, laba = rgb_to_lab(b), rgb_to_lab(a)
    Cb = np.hypot(labb[..., 1], labb[..., 2])
    yb2 = luma(b)
    zones = {'shadow': (yb2 < 0.25), 'mid': (yb2 > 0.35) & (yb2 < 0.65), 'high': (yb2 > 0.75)}
    shift = {}
    for zn, m in zones.items():
        m = m & (Cb < 10)
        if m.sum() > 200:
            shift[zn] = [round(float(np.median(laba[..., 1][m] - labb[..., 1][m])), 2),
                         round(float(np.median(laba[..., 2][m] - labb[..., 2][m])), 2)]
    return {
        'kind': 'pair',
        'styled': pathlib.Path(styled_path).name,
        'ref': pathlib.Path(ref_path).name,
        'luma_mapping_ref_to_styled_255': mapping,
        'contrast_ratio_p95_p5': round(float((np.percentile(ya, 95) - np.percentile(ya, 5)) /
                                             (np.percentile(yb, 95) - np.percentile(yb, 5) + 1e-9)), 4),
        'luma_mean_delta_255': round(float(ya.mean() - yb.mean()) * 255, 2),
        'ab_shift_styled_minus_ref': shift,
        'chroma_ratio': round(float(np.median(np.hypot(laba[..., 1], laba[..., 2])) /
                                    (np.median(np.hypot(labb[..., 1], labb[..., 2])) + 1e-9)), 4),
    }


# ---------------------------------------------------------------- 标准色卡（ColorChecker）

# Imaging Resource "Still Life" 标准场景里 ColorChecker 24 色块的中心像素坐标
# （在 5216x3472 的 M9 全分辨率原图上；IR 该场景固定不动，故所有 IR 样片共用）。
# 坐标由 tools/preset-exp/sample-fingerprint.py chart --mark 目视标定 + 色块内 sd 校验
# （实测 sd 0.6–2.3/255，说明采样窗完全落在色块内部）。
CC_COLS = [2450, 2530, 2613, 2696, 2779, 2864]
CC_ROWS = [2863, 2948, 3032, 3117]
CC_NAMES = ['dark_skin', 'light_skin', 'blue_sky', 'foliage', 'blue_flower', 'bluish_green',
            'orange', 'purplish_blue', 'moderate_red', 'purple', 'yellow_green', 'orange_yellow',
            'blue', 'green', 'red', 'yellow', 'magenta', 'cyan',
            'white', 'neutral_8', 'neutral_6_5', 'neutral_5', 'neutral_3_5', 'black']
# X-Rite ColorChecker 24 标称 sRGB（D65/2°，X-Rite 公布值）—— 仅作参考列，
# 注意：样片是**在具体照明下**拍的，与标称值的差 = 照明 × 相机色彩科学，不能直接读成色准。
CC_NOMINAL_SRGB = [
    (115, 82, 68), (194, 150, 130), (98, 122, 157), (87, 108, 67), (133, 128, 177), (103, 189, 170),
    (214, 126, 44), (80, 91, 166), (193, 90, 99), (94, 60, 108), (157, 188, 64), (224, 163, 46),
    (56, 61, 150), (70, 148, 73), (175, 54, 60), (231, 199, 31), (187, 86, 149), (8, 133, 161),
    (243, 243, 242), (200, 200, 200), (160, 160, 160), (122, 122, 121), (85, 85, 85), (52, 52, 52)]
# ColorChecker 末行 6 级灰的标称 L*（用于把「参考图输出」映射成「输入亮度」）
CC_GRAY_LSTAR = [96.539, 81.734, 66.766, 50.867, 35.656, 20.797]


def _sample_patches(path, cols, rows, half=14, dx=0, dy=0, arr=None):
    im = arr if arr is not None else np.asarray(Image.open(path).convert('RGB')).astype(np.float64)
    vals, sds = [], []
    for r in rows:
        for c in cols:
            p = im[r + dy - half:r + dy + half + 1, c + dx - half:c + dx + half + 1].reshape(-1, 3)
            vals.append(np.median(p, 0))
            sds.append(float(p.std(0).mean()))
    return np.array(vals), sds


def _best_align(path, cols, rows, span=8, arr=None):
    """RAW 转换版比机内 JPEG 小几像素，用色块内方差最小的位移对齐。"""
    arr = arr if arr is not None else np.asarray(Image.open(path).convert('RGB')).astype(np.float64)
    best = None
    for dx in range(-span, span + 1):
        for dy in range(-span, span + 1):
            _, sds = _sample_patches(path, cols, rows, dx=dx, dy=dy, arr=arr)
            m = float(np.mean(sds))
            if best is None or m < best[0]:
                best = (m, dx, dy)
    return best


def chart_fingerprint(styled, ref=None, cols=None, rows=None):
    cols, rows = cols or CC_COLS, rows or CC_ROWS
    vals, sds = _sample_patches(styled, cols, rows)
    lab = rgb_to_lab(vals / 255)
    rep = {
        'kind': 'chart',
        'styled': pathlib.Path(styled).name,
        'patch_names': CC_NAMES,
        'styled_srgb_255': [[round(float(v), 2) for v in x] for x in vals],
        'styled_lab': [[round(float(v), 2) for v in x] for x in lab],
        'nominal_srgb_255': [list(x) for x in CC_NOMINAL_SRGB],
        'patch_sd_255': [round(s, 2) for s in sds],
        'patch_sd_max_255': round(max(sds), 2),
    }
    gray_idx = list(range(18, 24))
    rep['styled_gray_lab_L'] = [round(float(lab[i][0]), 2) for i in gray_idx]
    if ref:
        rarr = np.asarray(Image.open(ref).convert('RGB')).astype(np.float64)
        _, dx, dy = _best_align(ref, cols, rows, arr=rarr)
        rv, rsd = _sample_patches(ref, cols, rows, dx=dx, dy=dy, arr=rarr)
        rlab = rgb_to_lab(rv / 255)
        dE = np.sqrt(((lab - rlab) ** 2).sum(1))
        rep['ref'] = pathlib.Path(ref).name
        rep['ref_align_dxdy'] = [dx, dy]
        rep['ref_sd_max_255'] = round(max(rsd), 2)
        rep['ref_srgb_255'] = [[round(float(v), 2) for v in x] for x in rv]
        rep['ref_lab'] = [[round(float(v), 2) for v in x] for x in rlab]
        rep['net_delta_lab'] = [[round(float(v), 2) for v in (lab[i] - rlab[i])] for i in range(len(lab))]
        rep['net_deltaE'] = [round(float(v), 2) for v in dE]
        rep['net_deltaE_mean'] = round(float(dE.mean()), 2)
        rep['net_deltaE_max'] = round(float(dE.max()), 2)
        # 影调净效应：以 ref 的灰阶输出亮度（Y）为横轴、styled 的为纵轴 —— 6 点映射
        yref = luma(rv[gray_idx] / 255) * 255
        ysty = luma(vals[gray_idx] / 255) * 255
        rep['net_gray_curve_ref_to_styled_255'] = [[round(float(a), 2), round(float(b), 2)]
                                                   for a, b in zip(yref, ysty)]
        rep['net_gray_curve_nominalL_to_styled_255'] = [
            [CC_GRAY_LSTAR[i], round(float(ysty[i]), 2)] for i in range(6)]
    return rep


def _mark_chart(styled, out_png, cols, rows, half=14):
    im = Image.open(styled).convert('RGB')
    a = np.asarray(im).copy()
    for r in rows:
        for c in cols:
            a[r - half:r + half + 1, c - half:c - half + 3] = [255, 0, 0]
            a[r - half:r + half + 1, c + half - 2:c + half + 1] = [255, 0, 0]
            a[r - half:r - half + 3, c - half:c + half + 1] = [255, 0, 0]
            a[r + half - 2:r + half + 1, c - half:c + half + 1] = [255, 0, 0]
    out = Image.fromarray(a).crop((min(cols) - 160, min(rows) - 160, max(cols) + 160, max(rows) + 160))
    out.thumbnail((1200, 1200), Image.LANCZOS)
    out.save(out_png)
    return out_png


# ---------------------------------------------------------------- 自检

def selftest():
    n = 33
    idx = np.arange(n) / (n - 1)
    bb, gg, rr = np.meshgrid(idx, idx, idx, indexing='ij')   # 轴序 [B][G][R]
    ident = np.stack([rr, gg, bb], -1)                       # 输出通道序 = RGB
    r = lut_fingerprint('identity', n, 'identity', 'test', ident)
    assert abs(r['tone']['mid_slope'] - 1.0) < 0.02, r['tone']['mid_slope']
    assert abs(r['color']['neutral_dev_max_abs_255']) < 0.6, r['color']['neutral_dev_max_abs_255']
    assert abs(r['color']['sat_gain_mean'] - 1.0) < 0.02, r['color']['sat_gain_mean']

    gray = np.linspace(0, 1, 256)[None, :, None].repeat(64, 0).repeat(3, 2)
    t = tone_metrics(gray)
    assert abs(t['luma_mean_255'] - 127.5) < 3, t['luma_mean_255']
    assert t['luma_std_255'] > 60, t['luma_std_255']

    rng = np.random.default_rng(0)
    flat = np.clip(0.5 + rng.normal(0, 4 / 255, (1200, 1200, 3)), 0, 1)
    Image.fromarray((flat * 255).astype(np.uint8)).save('/tmp/_fk_selftest_flat.png')
    g = grain_metrics('/tmp/_fk_selftest_flat.png')
    assert g['rms_all_255'] > 2.5, g['rms_all_255']          # 注入 σ=4/255
    assert g['rms_all_255'] < 6.0, g['rms_all_255']
    print('selftest OK', {'identity_mid_slope': r['tone']['mid_slope'],
                          'gray_mean': t['luma_mean_255'], 'noise_rms': g['rms_all_255']})


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)

    p = sub.add_parser('lut'); p.add_argument('--ids', required=True); p.add_argument('--out', required=True)
    p = sub.add_parser('samples'); p.add_argument('--name', required=True); p.add_argument('--dir', required=True)
    p.add_argument('--out', required=True)
    p = sub.add_parser('pair'); p.add_argument('--styled', required=True); p.add_argument('--ref', required=True)
    p.add_argument('--out', required=True)
    p = sub.add_parser('chart', help='ColorChecker 24 色块采样；给 --ref 则算净效应')
    p.add_argument('--styled', required=True); p.add_argument('--ref'); p.add_argument('--out', required=True)
    p.add_argument('--mark', help='输出带采样框标记的校验图（目视确认坐标）')
    sub.add_parser('selftest')
    a = ap.parse_args()

    if a.cmd == 'selftest':
        selftest(); return

    if a.cmd == 'lut':
        reps = []
        for lid in [s.strip() for s in a.ids.split(',') if s.strip()]:
            f = LUT_DIR / f'{lid}.lut'
            if not f.exists():
                print(f'!! 不存在: {f}', file=sys.stderr); continue
            size, title, cat, lut = load_lut(f)
            reps.append(lut_fingerprint(lid, size, title, cat, lut))
        out = {'kind': 'lut-set', 'luts': reps}
    elif a.cmd == 'samples':
        files = sorted([p for p in pathlib.Path(a.dir).iterdir()
                        if p.suffix.lower() in ('.jpg', '.jpeg', '.png', '.tif', '.tiff')])
        assert files, f'{a.dir} 下没有图片'
        out = samples_fingerprint(a.name, files)
    elif a.cmd == 'chart':
        out = chart_fingerprint(a.styled, a.ref)
        if a.mark:
            print('→ 校验图', _mark_chart(a.styled, a.mark, CC_COLS, CC_ROWS))
    else:
        out = pair_effect(a.styled, a.ref)

    op = pathlib.Path(a.out)
    op.parent.mkdir(parents=True, exist_ok=True)
    op.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'→ {op}  ({op.stat().st_size} B)')
    if a.cmd == 'lut':
        for r in out['luts']:
            print(f"  {r['id']}: mid_slope={r['tone']['mid_slope']} "
                  f"black={r['tone']['black_point_255']} white={r['tone']['white_point_255']} "
                  f"sat_gain={r['color']['sat_gain_mean']} mono={r['color']['is_monochrome']}")
    elif a.cmd == 'samples':
        print(f"  n={out['n_images']} luma_p50={out['tone_percentile_median_255']['p50']} "
              f"chroma_mean={out['color']['mean_chroma']['mean']} "
              f"neutral_ab={out['color']['neutral_a_mean']['mean']},{out['color']['neutral_b_mean']['mean']}")
    elif a.cmd == 'chart':
        print(f"  采样 sd 最大 {out['patch_sd_max_255']}/255（>6 说明采样框偏出色块）")
        print(f"  灰阶 L*: {out['styled_gray_lab_L']}")
        if a.ref:
            print(f"  对齐偏移 dxdy={out['ref_align_dxdy']}  净 ΔE 均值={out['net_deltaE_mean']} 最大={out['net_deltaE_max']}")
            print(f"  净影调映射 (ref→styled, 0-255): {out['net_gray_curve_ref_to_styled_255']}")


if __name__ == '__main__':
    main()
