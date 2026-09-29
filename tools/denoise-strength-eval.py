#!/usr/bin/env python3
"""降噪强度 A/B 评估（C 专项 2026-09-29；口径与 chat 分析一致）。

对同一场景、不同「RAW 降噪强度」滑杆值的成片 JPEG：
  ① 平坦块高频残差 σ（暗部 / 中间调分层）—— 噪声代理；
     低频判据选块（32×32 box 下采样 8× 后梯度 < 1.5，与历史口径一致）
  ② 梯度 P99/P99.5 —— 强边缘锐度（细节代理，全图 Laplacian 会高估噪声，
     固定用强边缘分位）
  ③ 可选：生成 100% 裁剪的横向对比图（人眼复核涂抹/水彩感）

用法：
  python3 tools/denoise-strength-eval.py out.jpg=1.00 out.jpg=1.50 ...
  python3 tools/denoise-strength-eval.py --crops dir/ a.jpg=0.7 b.jpg=1.5 ...
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
from numpy.lib.stride_tricks import sliding_window_view


def hf_residual(chan: np.ndarray, k: int = 3) -> np.ndarray:
    """高频残差：chan − 3×3 box 均值（去低频，留噪声/细节）。"""
    pad = k // 2
    yp = np.pad(chan, pad, mode="reflect")
    win = sliding_window_view(yp, (k, k))
    return chan - win.mean(axis=(2, 3))


def flat_mask(y: np.ndarray, block: int = 32, grad_thr: float = 1.5):
    h, w = y.shape
    bh, bw = h // block, w // block
    yc = y[: bh * block, : bw * block].reshape(bh, block, bw, block).mean(axis=(1, 3))
    gy, gx = np.gradient(yc)
    return yc, np.hypot(gy, gx) < grad_thr


def analyze(path: str) -> dict:
    im = np.asarray(Image.open(path), dtype=np.float32)
    r, g, b = im[..., 0], im[..., 1], im[..., 2]
    y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    cb, cr = b - y, r - y
    hy, hcb, hcr = hf_residual(y), hf_residual(cb), hf_residual(cr)
    yc, mask = flat_mask(y)
    bh, bw = yc.shape
    block = 32
    sig_y, sig_c, means = [], [], []
    for i in range(bh):
        for j in range(bw):
            if not mask[i, j]:
                continue
            sl = (slice(i * block, (i + 1) * block), slice(j * block, (j + 1) * block))
            sig_y.append(hy[sl].std())
            sig_c.append(float(np.hypot(hcb[sl].std(), hcr[sl].std())))
            means.append(yc[i, j])
    sig_y, sig_c, means = np.array(sig_y), np.array(sig_c), np.array(means)
    dark, mid = means < 60, (means >= 60) & (means < 140)
    gy, gx = np.gradient(y)
    edge = np.hypot(gy, gx)
    return {
        "blocks": len(sig_y),
        "dark_n": int(dark.sum()),
        "mid_n": int(mid.sum()),
        "dark_y": float(sig_y[dark].mean()) if dark.any() else float("nan"),
        "dark_c": float(sig_c[dark].mean()) if dark.any() else float("nan"),
        "mid_y": float(sig_y[mid].mean()) if mid.any() else float("nan"),
        "mid_c": float(sig_c[mid].mean()) if mid.any() else float("nan"),
        "edge_p99": float(np.percentile(edge, 99)),
        "edge_p995": float(np.percentile(edge, 99.5)),
    }


def make_crops(items, out_dir: Path):
    out_dir.mkdir(parents=True, exist_ok=True)
    ims = [(tag, Image.open(p)) for p, tag in items]
    W, H = ims[0][1].size
    # 100% 裁剪两块：暗部（桌面/阴影）与中间调（墙面/植物）
    for tag, (x0f, y0f) in [("dark", (0.28, 0.60)), ("mid", (0.35, 0.28))]:
        x0, y0, s = int(W * x0f), int(H * y0f), 700
        canvas = Image.new("RGB", (s * len(ims) + 10 * (len(ims) - 1), s + 28), (20, 20, 20))
        d = ImageDraw.Draw(canvas)
        for i, (t, im) in enumerate(ims):
            canvas.paste(im.crop((x0, y0, x0 + s, y0 + s)), (i * (s + 10), 28))
            d.text((i * (s + 10) + 8, 6), f"strength {t}", fill=(255, 255, 255))
        canvas.save(out_dir / f"crop_{tag}.jpg", quality=95)
        print(f"crop: {out_dir / f'crop_{tag}.jpg'}")


def main():
    args = sys.argv[1:]
    out_dir = None
    if args and args[0] == "--crops":
        out_dir = Path(args[1])
        args = args[2:]
    items = []
    for a in args:
        path, _, tag = a.rpartition("=")
        if not path:
            path, tag = a, Path(a).stem[-6:]
        items.append((path, tag))
    if not items:
        print(__doc__)
        return
    print(f"{'strength':>9} {'blocks':>7} {'dark σY':>8} {'dark σC':>8} "
          f"{'mid σY':>8} {'mid σC':>8} {'edgeP99':>8} {'edgeP99.5':>9}")
    for path, tag in items:
        m = analyze(path)
        print(f"{tag:>9} {m['blocks']:>7} {m['dark_y']:>8.3f} {m['dark_c']:>8.3f} "
              f"{m['mid_y']:>8.3f} {m['mid_c']:>8.3f} "
              f"{m['edge_p99']:>8.2f} {m['edge_p995']:>9.2f}")
    if out_dir is not None:
        make_crops(items, out_dir)


if __name__ == "__main__":
    main()
