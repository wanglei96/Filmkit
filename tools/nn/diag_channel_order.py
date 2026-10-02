#!/usr/bin/env python3
"""通道序对 2px 棋盘伪影的影响（真机口径复测）。

背景：0.10.105 修好增益后真机仍见"格纹"。A/B diff 的墙面主频 = 2px，
与 PMRID decoder 的 ConvTranspose2d(k=2,s=2) 棋盘伪影周期一致；而引擎
pack 的通道序是 (B,Gr,Gb,R)，官方 bggr2rggb(rot180)+bayer2rggb 是 (R,Gr,Gb,B)
—— R/B 对调会让网络对 R 位置与 B 位置给出不同残差 ⇒ 相邻像素（不同 CFA
相位）被不同程度修改 ⇒ 2px 棋盘。

上一轮 diag_255b 在 cvt=0.71（过度平滑区间）测得差异 <1%；本轮用真机同值
cvt_k 复测，并直接量 2px 周期能量。
"""
import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from diag_255b import (INP_SCALE, band_rms, flat_sigma, gray, load_b01,
                       phase_blocks, run_tiles)


def band2_energy(a, r=2.0):
    """2px 尺度能量（去低频后 2px 带通），用 FFT 更精确地量 2px 周期分量。"""
    x = a - a.mean()
    F = np.abs(np.fft.rfft2(x * np.hanning(x.shape[0])[:, None]
                            * np.hanning(x.shape[1])[None, :])) ** 2
    fy = np.fft.fftfreq(x.shape[0])[:, None]
    fx = np.fft.rfftfreq(x.shape[1])[None, :]
    fr = np.sqrt(fy ** 2 + fx ** 2)
    # 周期 2-3px ⇒ 频率 1/3 .. 1/2
    m = (fr > 1 / 3) & (fr <= 0.5)
    return float(F[m].sum() / F.sum())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dng", default="test_temp/FILMKIT_20261002-124812.dng")
    ap.add_argument("--iso", type=float, default=3444.0)
    ap.add_argument("--s-iso", type=float, default=9.635e-4)
    ap.add_argument("--s-anchor", type=float, default=1.0093e-3)
    ap.add_argument("--neff", type=float, default=4.0)
    ap.add_argument("--crop", type=int, default=1024)
    ap.add_argument("--model", default="tools/nn/out/pmrid_512_fp16.tflite")
    ap.add_argument("--out", default="/tmp/nn_chorder")
    args = ap.parse_args()
    outdir = Path(args.out); outdir.mkdir(parents=True, exist_ok=True)

    b01, pat = load_b01(Path(args.dng), args.crop)
    blk = phase_blocks(b01, pat)
    cvt = args.s_anchor * args.neff / args.s_iso
    print(f"DNG {Path(args.dng).name}  cvt_k(真机口径)={cvt:.3f}  gain={cvt*INP_SCALE:.1f}")

    import tensorflow as tf
    interp = tf.lite.Interpreter(model_path=args.model, num_threads=4)
    interp.allocate_tensors()

    ref = gray(np.stack([blk[c] for c in ["R", "Gr", "Gb", "B"]], -1))
    print(f"\n输入(真实单帧噪声) σ={flat_sigma(ref/255)*255:.2f}  "
          f"2px带通={band_rms(ref,0,1):.3f}  2pxFFT占比={band2_energy(ref):.5f}")
    print(f"\n{'case':<26}{'σ':>7}{'2px':>7}{'4px':>7}{'8px':>7}{'2pxFFT':>10}{'均值比':>8}")

    for name, order in [("ENGINE_(B,Gr,Gb,R)", ["B", "Gr", "Gb", "R"]),
                        ("OFFICIAL_(R,Gr,Gb,B)", ["R", "Gr", "Gb", "B"])]:
        packed = np.stack([blk[c] for c in order], -1).clip(0, 1)
        o = run_tiles(interp, packed * (cvt * INP_SCALE)) / (cvt * INP_SCALE)
        g = gray(o)
        print(f"{name:<26}{flat_sigma(g/255)*255:>7.2f}{band_rms(g,0,1):>7.3f}"
              f"{band_rms(g,1,2):>7.3f}{band_rms(g,2,4):>7.3f}"
              f"{band2_energy(g):>10.5f}{g.mean()/ref.mean():>8.4f}")
        Image.fromarray(g.clip(0, 255).astype(np.uint8)).save(outdir / f"{name}.png")

    # 残差（网络改了多少）的 2px 周期能量 —— 棋盘伪影直接判据
    print("\n残差(输入-输出)的 2px 周期能量占比：")
    for name, order in [("ENGINE_(B,Gr,Gb,R)", ["B", "Gr", "Gb", "R"]),
                        ("OFFICIAL_(R,Gr,Gb,B)", ["R", "Gr", "Gb", "B"])]:
        packed = np.stack([blk[c] for c in order], -1).clip(0, 1)
        o = run_tiles(interp, packed * (cvt * INP_SCALE)) / (cvt * INP_SCALE)
        res = gray(packed) - gray(o)
        print(f"  {name:<26} 残差std={res.std():6.3f}  2pxFFT占比={band2_energy(res):.5f}")
    print("→", outdir)


if __name__ == "__main__":
    main()
