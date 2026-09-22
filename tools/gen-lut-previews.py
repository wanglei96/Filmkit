#!/usr/bin/env python3
"""为内置 LUT 生成卡片预览图（M4，v0.6.5）。

为什么要离线生成：卡片里若做「当前取景帧 × 每套 LUT」的实时缩略图，每打开一次面板就要
6 次渲染 + GPU 读回，场景一变还得重算 —— 费电。改成**编译期把固定图套上各 LUT 生成 PNG**
打包进 assets，运行时零渲染，只是显示一张图。

输入图：默认用脚本合成的测试场景（无版权、且肤色/天空/植被/暖木/灰阶齐备，便于分辨每套
LUT 的偏色与对比特征）；也可 `--input 你的照片.jpg` 换成实拍 —— 注意必须是**未套过 LUT 的
中性图**（display 域 / sRGB），否则会双重套用。

用法：
    python3 tools/gen-lut-previews.py                     # 合成测试场景
    python3 tools/gen-lut-previews.py --input ~/x.jpg      # 用实拍图
    python3 tools/gen-lut-previews.py --size 128           # 改输出边长
"""

import argparse
import glob
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LUT_DIR = os.path.join(ROOT, "app/src/main/assets/luts")
SRC_OUT = os.path.join(ROOT, "app/src/main/assets/lut-preview-src.png")


def synth_scene(n=256):
    """合成测试场景：天空渐变 / 远山 / 肤色 / 植被 / 暖木 / 灰阶条。

    选这些元素是因为它们正好覆盖 LUT 最容易出问题的区域：高光天空（偏色）、
    肤色（记忆色，偏一点就难看）、绿植（饱和色域）、暖木（中间调偏色）、
    底部灰阶（中性是否被染色 + 对比/黑位变化）。
    """
    img = np.zeros((n, n, 3), dtype=np.float32)
    yy = np.linspace(0.0, 1.0, n)[:, None]

    # 天空：顶部深蓝 → 地平线暖白
    sky = np.zeros((n, n, 3), dtype=np.float32)
    sky[..., 0] = 0.35 + 0.55 * yy
    sky[..., 1] = 0.52 + 0.42 * yy
    sky[..., 2] = 0.80 + 0.15 * yy
    img[:] = sky

    # 远山（青绿，中间调）
    horizon = int(n * 0.42)
    img[horizon:horizon + int(n * 0.10), :] = np.array([0.34, 0.45, 0.36])

    # 暖木桌面（下半）
    wood_top = int(n * 0.52)
    wl = np.linspace(0.0, 1.0, n - wood_top)[:, None]
    wood = np.zeros((n - wood_top, n, 3), dtype=np.float32)
    wood[..., 0] = 0.30 + 0.18 * wl
    wood[..., 1] = 0.19 + 0.12 * wl
    wood[..., 2] = 0.12 + 0.08 * wl
    img[wood_top:] = wood

    im = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))
    d = ImageDraw.Draw(im)
    # 肤色（浅/中/深三档，记忆色）
    for i, c in enumerate([(240, 208, 184), (198, 148, 110), (120, 82, 58)]):
        cx = int(n * (0.18 + 0.22 * i))
        cy = int(n * 0.62)
        r = int(n * 0.075)
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=c)
    # 植被（饱和绿）与暖色花（饱和红）
    d.rectangle([int(n * 0.66), int(n * 0.56), int(n * 0.82), int(n * 0.70)], fill=(70, 132, 62))
    d.ellipse([int(n * 0.84), int(n * 0.56), int(n * 0.96), int(n * 0.66)], fill=(196, 60, 52))
    # 底部灰阶条（10 级）+ 黑白块：检查中性是否被染色
    bar_h = int(n * 0.10)
    for i in range(10):
        x0 = int(n * i / 10)
        x1 = int(n * (i + 1) / 10)
        v = int(255 * i / 9)
        d.rectangle([x0, n - bar_h, x1, n], fill=(v, v, v))
    return np.asarray(im, dtype=np.float32) / 255.0


def load_cube(path):
    """解析 .cube → (N, N, N, 3)，索引顺序 [b][g][r]（红最快）"""
    size, rows = None, []
    with open(path, encoding="utf-8") as f:
        for line in f:
            s = line.strip()
            if not s or s.startswith("#"):
                continue
            if s.startswith("LUT_3D_SIZE"):
                size = int(s.split()[1])
                continue
            if not (s[0].isdigit() or s[0] in "-."):
                continue
            rows.append([float(x) for x in s.split()])
    d = np.asarray(rows, dtype=np.float32)
    if size is None or d.shape[0] != size ** 3:
        raise ValueError(f"{path}: 数据行数 {d.shape[0]} 与 LUT_3D_SIZE {size} 不符")
    return d.reshape(size, size, size, 3)


def apply_lut(img, lut):
    """三线性采样（与渲染侧四面体的差异远小于卡片预览所需精度）"""
    n = lut.shape[0]
    p = np.clip(img, 0.0, 1.0) * (n - 1)
    i0 = np.clip(np.floor(p).astype(np.int32), 0, n - 2)
    f = p - i0
    r0, g0, b0 = i0[..., 0], i0[..., 1], i0[..., 2]
    fr, fg, fb = f[..., 0:1], f[..., 1:2], f[..., 2:3]

    def C(dr, dg, db):
        return lut[b0 + db, g0 + dg, r0 + dr]

    def lerp(a, b, t):
        return a + (b - a) * t

    c00 = lerp(C(0, 0, 0), C(1, 0, 0), fr)
    c10 = lerp(C(0, 1, 0), C(1, 1, 0), fr)
    c01 = lerp(C(0, 0, 1), C(1, 0, 1), fr)
    c11 = lerp(C(0, 1, 1), C(1, 1, 1), fr)
    return lerp(lerp(c00, c10, fg), lerp(c01, c11, fg), fb)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", help="中性输入图（未套过 LUT 的 sRGB 照片）；省略则用合成场景")
    ap.add_argument("--dump", metavar="DIR", help="额外把每套 LUT 的预览输出到该目录（离线审查用，不入库）")
    ap.add_argument("--size", type=int, default=108, help="--dump 的输出边长（默认 108）")
    args = ap.parse_args()

    if args.input:
        src = Image.open(os.path.expanduser(args.input)).convert("RGB")
        # 居中裁方再缩放，保证卡片里构图稳定
        w, h = src.size
        s = min(w, h)
        src = src.crop(((w - s) // 2, (h - s) // 2, (w + s) // 2, (h + s) // 2))
        base = np.asarray(src.resize((256, 256), Image.LANCZOS), dtype=np.float32) / 255.0
        print(f"输入：{args.input}")
    else:
        base = synth_scene(256)
        print("输入：合成测试场景（肤色 / 天空 / 植被 / 暖木 / 灰阶条）")

    os.makedirs(os.path.dirname(SRC_OUT), exist_ok=True)
    src_im = Image.fromarray((np.clip(base, 0, 1) * 255 + 0.5).astype(np.uint8))
    if src_im.size != (256, 256):
        src_im = src_im.resize((256, 256), Image.LANCZOS)
    src_im.save(SRC_OUT, optimize=True)
    print(f"测试图 → {os.path.relpath(SRC_OUT, ROOT)}  ({os.path.getsize(SRC_OUT) / 1024:.1f} KB)")
    print("App 运行时用它 × 各 LUT 生成卡片图并缓存（内置与用户导入同一路径，见 LutPreview.kt）")

    if args.dump:
        os.makedirs(args.dump, exist_ok=True)
        for path in sorted(glob.glob(os.path.join(LUT_DIR, "*.cube"))):
            lut = load_cube(path)
            out = np.clip(apply_lut(base, lut), 0.0, 1.0)
            im = Image.fromarray((out * 255 + 0.5).astype(np.uint8))
            im = im.resize((args.size, args.size), Image.LANCZOS)
            dst = os.path.join(args.dump, os.path.basename(path)[:-5] + ".png")
            im.save(dst, optimize=True)
            print(f"  dump {os.path.basename(dst):<22} {os.path.getsize(dst) / 1024:5.1f} KB")


if __name__ == "__main__":
    main()
