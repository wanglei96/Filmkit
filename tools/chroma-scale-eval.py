#!/usr/bin/env python3
"""多尺度色度噪声评估（成片 JPEG 对比；2026-09-30 绿网格/色斑事故后固化口径）。

为什么不能只看 3×3 高通残差（tools/denoise-strength-eval.py 的 σC）：
那条口径量的是 **1px 尺度彩点**，而肉眼说的"墙壁彩色斑块"是 **8–32px 的低频
色度噪声**，高通滤波对这一频段响应≈0 —— 于是出现"定量说差不多、肉眼说差很多"
（2026-09-30 用户指正）。同理，PC 样片若是 HEIC/低码率，高频彩点会被压缩抹平，
只测高频会系统性高估我们与 PC 的接近程度。

口径：
  ① 平坦块 = 32×32 luma 块、低频梯度 < 1.5（与 denoise-strength-eval 一致）
  ② 对 Cb/Cr 做 s×s box 平均（s = 1/4/8/16/32）后在块内取 std，
     σC(s) = hypot(std(Cb_s), std(Cr_s)) —— s 越大越接近"色斑"尺度
  ③ 可选 --vis 输出色度低频残差可视化（Cb/Cr 减 48px 均值 ×12，绿/品红=色斑）

用法：
  python3 tools/chroma-scale-eval.py pc.jpg=PC ours.jpg=DFT other.jpg=MM
  python3 tools/chroma-scale-eval.py --vis out_dir/ pc.jpg=PC ours.jpg=DFT
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw


def box_mean(a: np.ndarray, s: int) -> np.ndarray:
    h, w = a.shape
    bh, bw = h // s, w // s
    return a[: bh * s, : bw * s].reshape(bh, s, bw, s).mean(axis=(1, 3))


def flat_mask(y: np.ndarray, block: int = 32, thr: float = 1.5) -> np.ndarray:
    yc = box_mean(y, block)
    gy, gx = np.gradient(yc)
    return np.hypot(gy, gx) < thr


def chroma_by_scale(path: str, scales=(1, 4, 8, 16, 32)) -> dict:
    im = np.asarray(Image.open(path).convert("RGB"), np.float32)
    r, g, b = im[..., 0], im[..., 1], im[..., 2]
    y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    cb, cr = b - y, r - y
    mask = flat_mask(y)
    out = {}
    for s in scales:
        cb_s, cr_s = box_mean(cb, s), box_mean(cr, s)
        step = max(2, 32 // s)            # 每块至少 2×2 个下采样点才能算 std
        ni, nj = cb_s.shape[0] // step, cb_s.shape[1] // step
        vals = []
        for i in range(ni):
            for j in range(nj):
                gi, gj = i * step // max(1, 32 // s), j * step // max(1, 32 // s)
                if gi >= mask.shape[0] or gj >= mask.shape[1] or not mask[gi, gj]:
                    continue
                p_cb = cb_s[i * step:(i + 1) * step, j * step:(j + 1) * step]
                p_cr = cr_s[i * step:(i + 1) * step, j * step:(j + 1) * step]
                vals.append(np.hypot(p_cb.std(), p_cr.std()))
        out[s] = (float(np.mean(vals)) if vals else float("nan"), len(vals))
    return out


def vis_panel(items, out_path: Path, size: int = 560) -> None:
    """色度低频残差 ×12：绿/品红块 = 肉眼说的色斑。"""
    canvas = Image.new("RGB", (size * len(items) + 10 * (len(items) - 1), size + 26),
                       (20, 20, 20))
    d = ImageDraw.Draw(canvas)
    for i, (tag, p) in enumerate(items):
        im = Image.open(p).convert("RGB")
        W, H = im.size
        # 取画面左上偏中的平坦墙面区（按内容经验位置；对齐仅供目视参考）
        box = (int(0.13 * W) - size // 2, int(0.21 * H) - size // 2,
               int(0.13 * W) + size // 2, int(0.21 * H) + size // 2)
        a = np.asarray(im.crop(box), np.float32)
        y = 0.2126 * a[..., 0] + 0.7152 * a[..., 1] + 0.0722 * a[..., 2]
        cb, cr = a[..., 2] - y, a[..., 0] - y

        def low(x):
            s = 48
            m = box_mean(x, s)
            return np.asarray(Image.fromarray(m).resize((x.shape[1], x.shape[0]),
                                                        Image.BILINEAR))

        vis = np.clip(np.stack([(cr - low(cr)) * 12 + 128,
                                ((cb - low(cb)) + (cr - low(cr))) * 6 + 128,
                                (cb - low(cb)) * 12 + 128], -1), 0, 255).astype(np.uint8)
        canvas.paste(Image.fromarray(vis), (i * (size + 10), 26))
        d.text((i * (size + 10) + 6, 7), f"{tag}  色度低频残差 ×12", fill=(255, 255, 255))
    canvas.save(out_path, quality=95)
    print(f"vis: {out_path}")


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    vis_dir = None
    if "--vis" in sys.argv:
        k = sys.argv.index("--vis")
        vis_dir = Path(sys.argv[k + 1])
        args = [a for a in args if a != sys.argv[k + 1]]
    items = []
    for a in args:
        p, _, tag = a.rpartition("=")
        items.append((tag or Path(p).stem, p))
    if not items:
        print(__doc__)
        sys.exit(2)

    scales = (1, 4, 8, 16, 32)
    print("多尺度色度噪声 σC（平坦块内；s 越大越接近肉眼说的“色斑”）")
    print(f"{'样片':<12}" + "".join(f"{str(s)+'px':>10}" for s in scales) + f"{'块数':>8}")
    res = {}
    for tag, p in items:
        r = chroma_by_scale(p, scales)
        res[tag] = r
        print(f"{tag:<12}" + "".join(f"{r[s][0]:>10.3f}" for s in scales) + f"{r[1][1]:>8}")
    base = items[0][0]
    for tag, _ in items[1:]:
        print(f"{tag}/{base:<8}" + "".join(
            f"{res[tag][s][0]/res[base][s][0]:>9.1f}×" for s in scales))
    if vis_dir:
        vis_dir.mkdir(parents=True, exist_ok=True)
        vis_panel(items, vis_dir / "chroma-scale-vis.jpg")


if __name__ == "__main__":
    main()
