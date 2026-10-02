#!/usr/bin/env python3
"""cvt_k 扫描：以 PSNR(out, clean) + 纹理保留率定最优网络输入增益。

理论：PMRID 训练时输入被 KSigma 归一化到 SIDD anchor(ISO1600) 的噪声水平
（img01 域 S_a = K(1600)/959 = 1.0094e-3，σ²=k·x+b）。引擎用 X300 自己的
S(ISO1600)=4.5e-4 当 anchor，使输入噪声低于训练水平 → 网络按"训练噪声量"
去减 → 过度平滑。跨传感器方差模型匹配应为 cvt_k = S_a/S_X。

本脚本扫 cvt_k 网格，报告 σ/ret/PSNR，定位最优增益与"是否单调"。
"""
import argparse
from pathlib import Path

import numpy as np
import rawpy
from PIL import Image

import sys
sys.path.insert(0, str(Path(__file__).parent))
from diag_255b import (INP_SCALE, K_OFFICIAL, band_rms, flat_sigma, gray,
                       load_b01, phase_blocks, run_tiles, synth_noise)


def psnr(a, b, peak=255.0):
    mse = float(np.mean((a - b) ** 2))
    return 99.0 if mse <= 1e-9 else 10 * np.log10(peak ** 2 / mse)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dng", default="test_temp/FILMKIT_20261002-121335.dng")
    ap.add_argument("--iso", type=float, default=2057.0)
    ap.add_argument("--s-iso", type=float, default=6.334e-4)
    ap.add_argument("--o-iso", type=float, default=3.967e-7)
    ap.add_argument("--crop", type=int, default=1024)
    ap.add_argument("--neff", type=float, default=1.0, help="模拟 MFNR 融合帧数（合成噪声方差 ÷neff）")
    ap.add_argument("--model", default="tools/nn/out/pmrid_512_fp16.tflite")
    ap.add_argument("--out", default="/tmp/nn_scan")
    args = ap.parse_args()
    outdir = Path(args.out); outdir.mkdir(parents=True, exist_ok=True)

    b01, pat = load_b01(Path(args.dng), args.crop)
    blk = phase_blocks(b01, pat)
    rng = np.random.default_rng(20261002)
    noisy = {k: synth_noise(v, args.s_iso / args.neff, args.o_iso / args.neff, rng)
             for k, v in blk.items()}

    ORDER = ["R", "Gr", "Gb", "B"]
    clean_g = gray(np.stack([blk[c] for c in ORDER], -1))
    noisy_g = gray(np.stack([noisy[c] for c in ORDER], -1))
    packed = np.stack([noisy[c] for c in ORDER], -1).clip(0, 1)

    c2, c4, c8 = band_rms(clean_g, 0, 1), band_rms(clean_g, 1, 2), band_rms(clean_g, 2, 4)
    s_anchor_sidd = (K_OFFICIAL[0] * 1600 + K_OFFICIAL[1]) / 959.0
    print(f"clean 带通 2px={c2:.2f} 4px={c4:.2f} 8px={c8:.2f}  σ={flat_sigma(clean_g/255)*255:.2f}")
    print(f"noisy 带通 2px={band_rms(noisy_g,0,1):.2f} 4px={band_rms(noisy_g,1,2):.2f} "
          f"σ={flat_sigma(noisy_g/255)*255:.2f}  PSNR(vs clean)={psnr(noisy_g, clean_g):.2f}")
    print(f"SIDD anchor S(img01)={s_anchor_sidd:.4e}  X300 S={args.s_iso:.4e}  "
          f"理论 cvt_k=S_a/S_X={s_anchor_sidd/args.s_iso:.3f}  sqrt式={np.sqrt(s_anchor_sidd/args.s_iso):.3f}")
    print(f"\n{'cvt_k':>7}{'gain':>8}{'σ':>7}{'ret2':>7}{'ret4':>7}{'ret8':>7}{'PSNR':>8}{'均值比':>8}")

    import tensorflow as tf
    interp = tf.lite.Interpreter(model_path=args.model, num_threads=4)
    interp.allocate_tensors()

    for cvt in [0.50, 0.71, 1.59, 3.19, 4.50, 6.37, 9.00, 12.0]:
        g = cvt * INP_SCALE
        o = run_tiles(interp, packed * g) / g
        og = gray(o)
        b2, b4, b8 = band_rms(og, 0, 1), band_rms(og, 1, 2), band_rms(og, 2, 4)
        print(f"{cvt:>7.2f}{g:>8.1f}{flat_sigma(og/255)*255:>7.2f}"
              f"{b2/c2:>7.2f}{b4/c4:>7.2f}{b8/c8:>7.2f}"
              f"{psnr(og, clean_g):>8.2f}{og.mean()/clean_g.mean():>8.3f}")
        if cvt in (0.71, 1.59, 6.37):
            Image.fromarray(og.clip(0, 255).astype(np.uint8)).save(outdir / f"cvt{cvt:.2f}.png")
    Image.fromarray(clean_g.clip(0, 255).astype(np.uint8)).save(outdir / "clean.png")
    Image.fromarray(noisy_g.clip(0, 255).astype(np.uint8)).save(outdir / "noisy.png")
    print("→", outdir)


if __name__ == "__main__":
    main()
