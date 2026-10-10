#!/usr/bin/env python3
"""三条内置预设的**离线示意预览**（LUT + 颗粒 + 暗角合成）。

⚠️ 这是**示意**，不是引擎输出：
  - LUT 用四面体/三线性插值近似引擎的四面体插值；
  - 颗粒用**简化噪声**（高斯白噪声按颗粒周期上采样 + 中间调密度调制）近似 E4-3 的
    3 八度 simplex + 相纸响应 LUT —— 观感接近但**不是同一个噪声**；
  - 暗角用径向 smoothstep 近似 darktable 的正圆 falloff。
真实效果以真机引擎输出为准（`docs/reports/preset-DESIGN.md` §4 的配方）。
"""
import pathlib
import numpy as np
from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = pathlib.Path(__file__).resolve().parent / 'preview'

# 与 builtin.json 的配方一一对应（改配方时同步这里）
RECIPES = [
    dict(lut='leica_m9',      label='徕卡一瞬 (M9)',  grain=20, iso=160, vig=-0.18, vr=85, vf=55),
    dict(lut='ricoh_negative', label='理光负片',       grain=30, iso=400, vig=-0.28, vr=75, vf=48),
    dict(lut='leica_b_w_hc',  label='徕卡黑白 HC',     grain=40, iso=400, vig=-0.24, vr=80, vf=52),
]
# E4-3 实测的颗粒特征周期（3072px 短边，单位 px）
PERIOD_PX = {100: 5.24, 160: 5.4, 400: 5.66, 1600: 7.61, 6400: 15.97}
# 标定：E4-3 实测 [26]=25 时中间调颗粒 RMS ≈ 4.9/255
GRAIN_K = 4.9 / 255 / 0.25


def load_lut(lid):
    d = (ROOT / f'app/src/main/assets/luts/{lid}.lut').read_bytes()
    i = 0; size = d[i]; i += 1
    tl = d[i]; i += 1; i += tl
    cl = d[i]; i += 1; i += cl
    a = np.frombuffer(d[i:], dtype=np.uint8)
    return a.reshape(size, size, size, 3).astype(np.float32) / 255.0


def apply_lut(img, lut):
    n = lut.shape[0]
    t = np.clip(img, 0, 1) * (n - 1)
    i0 = np.clip(np.floor(t).astype(np.int64), 0, n - 2)
    f = t - i0
    out = np.zeros_like(img)
    for db in (0, 1):
        for dg in (0, 1):
            for dr in (0, 1):
                w = ((f[..., 0] if dr else 1 - f[..., 0]) *
                     (f[..., 1] if dg else 1 - f[..., 1]) *
                     (f[..., 2] if db else 1 - f[..., 2]))[..., None]
                out += w * lut[i0[..., 2] + db, i0[..., 1] + dg, i0[..., 0] + dr]
    return np.clip(out, 0, 1)


def apply_vignette(img, strength, radius_pct, feather_pct):
    h, w = img.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    cx, cy = (w - 1) / 2, (h - 1) / 2
    # 正圆：按长边归一化（darktable 的 pv 尺度 = (W,H)/max(W,H)）
    m = max(w, h) / 2
    r = np.sqrt(((xx - cx) / m) ** 2 + ((yy - cy) / m) ** 2) / np.sqrt(2)
    r0, r1 = radius_pct / 100 * 0.85, (radius_pct + feather_pct) / 100 * 0.85
    t = np.clip((r - r0) / max(r1 - r0, 1e-6), 0, 1)
    vw = t * t * (3 - 2 * t)                      # smoothstep
    return np.clip(img * (1.0 + vw[..., None] * strength), 0, 1)


def apply_grain(img, amount, iso, seed=7):
    if amount <= 0:
        return img
    h, w = img.shape[:2]
    rng = np.random.default_rng(seed)
    period = PERIOD_PX.get(iso, 5.7) * (min(w, h) / 3072.0)   # 按短边缩放（ADR-11）
    nh, nw = max(4, int(h / max(period, 1))), max(4, int(w / max(period, 1)))
    noise = rng.normal(0, 1, (nh, nw)).astype(np.float32)
    noise = np.asarray(Image.fromarray(noise).resize((w, h), Image.BICUBIC), dtype=np.float32)
    noise = noise / (noise.std() + 1e-9)
    l = img @ np.array([0.299, 0.587, 0.114], dtype=np.float32)
    wgt = 4 * l * (1 - l)                          # 中间调最重（近似相纸响应 mb=100）
    delta = noise * wgt * (amount / 100.0) * GRAIN_K
    return np.clip(img + delta[..., None], 0, 1)


def main():
    src = ROOT / 'test_temp/FILMKIT_20261009-090810.jpg'
    im = Image.open(src).convert('RGB')
    w = 460
    im = im.resize((w, round(im.height * w / im.width)), Image.LANCZOS)
    base = np.asarray(im).astype(np.float32) / 255.0

    tiles = []
    for r in RECIPES:
        out = apply_lut(base, load_lut(r['lut']))
        out = apply_vignette(out, r['vig'], r['vr'], r['vf'])
        out = apply_grain(out, r['grain'], r['iso'])
        p = OUT / f"recipe_{r['lut']}.png"
        Image.fromarray((out * 255 + 0.5).astype(np.uint8)).save(p)
        print('写出', p)
        tiles.append((r['label'], out))

    # 拼图：原图 + 三条
    tiles = [('原图（无预设）', base)] + tiles
    hh = tiles[0][1].shape[0]
    canvas = Image.new('RGB', (w * len(tiles), hh + 26), (18, 18, 20))
    for k, (label, arr) in enumerate(tiles):
        canvas.paste(Image.fromarray((arr * 255 + 0.5).astype(np.uint8)), (k * w, 26))
        ImageDraw.Draw(canvas).text((k * w + 6, 7), label, fill=(240, 220, 120))
    canvas.save(OUT / 'recipe_compare.png')
    print('拼图 →', OUT / 'recipe_compare.png', canvas.size)


if __name__ == '__main__':
    main()
