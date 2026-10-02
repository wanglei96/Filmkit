#!/usr/bin/env python3
"""验证 burst 训练对质量：噪声下降倍数 + 错位残影检测。

两个判据：
  1. 噪声下降 —— clean 必须比 noisy 干净（同一批平坦块口径，不可各图自选块，
     否则 clean 会挑到自己的伪影块，口径不可比）。
  2. 无错位残影 —— (noisy − clean) 应该是**白噪声**（两帧独立噪声之差）。
     若对齐有亚像素残留或场景非刚性变化，差异里会混入**结构**，此时白噪性 wn
     下降。wn 高 = 差异只是噪声（好）；wn 低 = 有残影（坏，训练会学到鬼影）。
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from diag_255b import flat_sigma, gray  # noqa: E402


def wn_of(a):
    """白噪性 = 高频σ/总σ（同 multiframe_clean 判据）：纯噪声≈1，有结构则显著降低。"""
    hp = a - np.asarray(__import__("PIL.Image", fromlist=["Image"]).fromarray(
        np.clip(a * 255, 0, 255).astype(np.uint8)).filter(
        __import__("PIL.ImageFilter", fromlist=["ImageFilter"]).GaussianBlur(2)),
        dtype=np.float32) / 255.0
    t = a.std()
    return float(hp.std() / t) if t > 1e-9 else 1.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default="test_temp/burst")
    ap.add_argument("--png", default="tools/nn/out")
    args = ap.parse_args()
    root = Path(args.dir)
    outd = Path(args.png); outd.mkdir(parents=True, exist_ok=True)

    print(f"{'场景':<10}{'noisy σ':>10}{'clean σ':>10}{'下降':>7}"
          f"{'差异wn':>9}{'差异/噪声':>11}  判读")
    for nf in sorted(root.glob("*_noisy.npy")):
        cf = nf.with_name(nf.name.replace("_noisy.npy", "_clean.npy"))
        if not cf.exists():
            continue
        n = np.load(nf, mmap_mode="r")
        c = np.load(cf, mmap_mode="r")
        # 固定取中心 1024²（同一批块口径）
        h, w = n.shape
        ys, xs = h // 2 - 512, w // 2 - 512
        nz = np.asarray(n[ys:ys + 1024, xs:xs + 1024], dtype=np.float32)
        cz = np.asarray(c[ys:ys + 1024, xs:xs + 1024], dtype=np.float32)
        gn, gc = gray(nz.reshape(512, 2, 512, 2).transpose(0, 2, 1, 3).reshape(512, 512, 4)) \
            if False else None, None
        # 直接在半分辨率灰度上算（4 相位块平均）
        def g(m):
            return m.reshape(m.shape[0] // 2, 2, m.shape[1] // 2, 2).mean(axis=(1, 3))
        gn, gc = g(nz), g(cz)
        sn, sc = flat_sigma(gn / 255.0), flat_sigma(gc / 255.0)
        d = gn - gc
        wnd = wn_of(d / 255.0)
        # 差异幅度 vs 单帧噪声幅度（理论：√2·σ_clean）
        ratio = float(d.std() / max(gc.std(), 1e-9))
        tag = "OK" if (sc < sn * 0.75 and wnd > 0.85) else \
              ("噪声降了但有残影" if sc < sn * 0.75 else "clean 不够干净")
        print(f"{nf.stem.replace('_noisy',''):<10}{sn*255:>10.2f}{sc*255:>10.2f}"
              f"{sn/max(sc,1e-9):>6.2f}x{wnd:>9.3f}{ratio:>11.2f}  {tag}")
        # 预览（噪声归一化后并排）
        from PIL import Image
        def norm(x):
            p1, p2 = np.percentile(x, [1, 99])
            return np.clip((x - p1) / max(p2 - p1, 1e-6) * 255, 0, 255).astype(np.uint8)
        st = np.concatenate([norm(gn), norm(gc)], axis=1)
        Image.fromarray(st).resize((st.shape[1] // 2, st.shape[0] // 2)).save(
            outd / f"pair_{nf.stem.replace('_noisy','')}.png")
    print(f"\n预览（左 noisy / 右 clean，对比拉伸）→ {outd}/pair_scene*.png")


if __name__ == "__main__":
    main()
