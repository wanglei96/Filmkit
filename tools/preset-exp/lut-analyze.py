#!/usr/bin/env python3
"""分析内置 LUT 的实际色彩特性，为内置预设的参数设计提供依据。

LUT 资产格式（app/src/main/assets/luts/*.lut，由 tools/gen-lut-assets.py 生成）：
  [size:1B][titleLen:1B][title][catLen:1B][category][data: size^3 * 3B]
数组维度 = [B][G][R]（R 是最快变化的轴，与 .cube 一致）。

用法：python3 tools/preset-exp/lut-analyze.py [--preview]
"""
import argparse, json, pathlib
import numpy as np
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[2]
LUT_DIR = ROOT / 'app/src/main/assets/luts'
OUT = pathlib.Path(__file__).resolve().parent


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
    return size, title, cat, data.reshape(size, size, size, 3).astype(np.float32) / 255.0


def apply_lut(img, lut):
    """img: HxWx3 float 0..1 → 三线性插值。"""
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


def analyze(name, size, title, cat, lut):
    rep = {'file': name, 'title': title, 'category': cat, 'size': size}
    # ① 中性灰阶响应（r=g=b）
    idx = np.arange(size)
    gray = lut[idx, idx, idx]                     # [n][3]
    rep['gray_in_0_25_50_75_100'] = [
        [round(float(gray[k][c]) * 255, 1) for c in range(3)]
        for k in (0, size // 4, size // 2, 3 * size // 4, size - 1)
    ]
    # ② 黑白判定：所有网格点上 R/G/B 的最大差
    rg = np.abs(lut[..., 0] - lut[..., 1]).max()
    gb = np.abs(lut[..., 1] - lut[..., 2]).max()
    rep['is_monochrome'] = bool(max(rg, gb) < 0.02)
    rep['max_channel_spread'] = round(float(max(rg, gb)) * 255, 2)
    # ③ 黑场 / 白场（抬黑 = 褪色感）
    rep['black_point_255'] = [round(float(gray[0][c]) * 255, 1) for c in range(3)]
    rep['white_point_255'] = [round(float(gray[-1][c]) * 255, 1) for c in range(3)]
    # ④ 对比度：中灰附近的斜率（输出亮度对输入亮度的导数）
    lin = gray.mean(axis=1)
    mid = size // 2
    slope = (lin[mid + 2] - lin[mid - 2]) / (4.0 / (size - 1))
    rep['contrast_slope_at_mid'] = round(float(slope), 3)   # 1.0 = 线性
    # ⑤ 全图平均色偏（对所有网格点）
    m_in = np.stack(np.meshgrid(np.linspace(0, 1, size), np.linspace(0, 1, size),
                                np.linspace(0, 1, size), indexing='ij'), -1)
    d = (lut - m_in).mean(axis=(0, 1, 2)) * 255
    rep['mean_shift_rgb_255'] = [round(float(x), 2) for x in d]
    rep['warm_cool_r_minus_b'] = round(float(d[0] - d[2]), 2)
    # ⑥ 饱和色响应（纯红/绿/蓝输入）
    for label, coord in (('red', (size - 1, 0, 0)), ('green', (0, size - 1, 0)), ('blue', (0, 0, size - 1))):
        b, g, r = coord
        rep[f'pure_{label}_out'] = [round(float(lut[b, g, r][c]) * 255, 1) for c in range(3)]
    return rep


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--preview', action='store_true', help='生成测试图预览')
    ap.add_argument('--ids', default='leica_m9,ricoh_negative,leica_b_w_true,leica_b_w_hc,leica_classic')
    args = ap.parse_args()

    ids = [s.strip() for s in args.ids.split(',') if s.strip()]
    luts, reports = {}, []
    for lid in ids:
        p = LUT_DIR / f'{lid}.lut'
        if not p.exists():
            print(f'!! 不存在: {p}'); continue
        size, title, cat, lut = load_lut(p)
        luts[lid] = lut
        reports.append(analyze(p.name, size, title, cat, lut))

    (OUT / 'lut-analysis.json').write_text(
        json.dumps(reports, ensure_ascii=False, indent=2), encoding='utf-8')
    for r in reports:
        print(f"\n=== {r['title']} ({r['file']}, {r['category']}) ===")
        print(f"  灰阶 0/25/50/75/100% 输出: {r['gray_in_0_25_50_75_100']}")
        print(f"  黑场 {r['black_point_255']}  白场 {r['white_point_255']}")
        print(f"  中灰对比度斜率 {r['contrast_slope_at_mid']}（1.0=线性）")
        print(f"  平均色偏 RGB {r['mean_shift_rgb_255']}  暖冷(R-B) {r['warm_cool_r_minus_b']}")
        print(f"  黑白? {r['is_monochrome']}（通道最大差 {r['max_channel_spread']}/255）")
        print(f"  纯色响应 红{r['pure_red_out']} 绿{r['pure_green_out']} 蓝{r['pure_blue_out']}")

    if args.preview:
        src = ROOT / 'test_temp/FILMKIT_20261009-090810.jpg'
        im = Image.open(src).convert('RGB')
        w = 760
        im = im.resize((w, round(im.height * w / im.width)), Image.LANCZOS)
        arr = np.asarray(im).astype(np.float32) / 255.0
        Image.fromarray((arr * 255).astype(np.uint8)).save(OUT / 'preview/00_original.png')
        for lid, lut in luts.items():
            out = apply_lut(arr, lut)
            Image.fromarray((out * 255 + 0.5).astype(np.uint8)).save(OUT / f'preview/{lid}.png')
            print(f'  预览 → tools/preset-exp/preview/{lid}.png')


if __name__ == '__main__':
    main()
