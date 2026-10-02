#!/usr/bin/env python3
"""与官方口径对齐的三种偏差 + 增益对"伪影引入比"的影响。

判据：art = band4(out)/band4(in)。网络正确降噪时应 <1（去掉噪声）；>1 说明
在 4px 尺度引入伪影（真机 A/B 实测 nn-on/nn-off 的 4px 比 = 1.408）。

官方口径（run_benchmark.py）：
  bggr2rggb = rot180 → bayer2rggb 打包 ⇒ 通道 (R,Gr,Gb,B) 且 packed 图空间翻转
  等价于：四个 CFA 平面各自 180° 翻转后按 (R,Gr,Gb,B) 堆叠
引擎现状：不翻转 + (B,Gr,Gb,R)
"""
import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from diag_255b import (INP_SCALE, band_rms, flat_sigma, gray, load_b01,
                       phase_blocks, run_tiles)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dng", default="test_temp/FILMKIT_20261002-124812.dng")
    ap.add_argument("--s-iso", type=float, default=9.635e-4)
    ap.add_argument("--s-anchor", type=float, default=1.0093e-3)
    ap.add_argument("--neff", type=float, default=4.0)
    ap.add_argument("--crop", type=int, default=1024)
    ap.add_argument("--model", default="tools/nn/out/pmrid_512_fp16.tflite")
    ap.add_argument("--out", default="/tmp/nn_align")
    args = ap.parse_args()
    outdir = Path(args.out); outdir.mkdir(parents=True, exist_ok=True)

    b01, pat = load_b01(Path(args.dng), args.crop)
    blk = phase_blocks(b01, pat)
    base_cvt = args.s_anchor * args.neff / args.s_iso

    import tensorflow as tf
    interp = tf.lite.Interpreter(model_path=args.model, num_threads=4)
    interp.allocate_tensors()

    def pack(order, flip):
        planes = [blk[c][::-1, ::-1] if flip else blk[c] for c in order]
        return np.stack(planes, -1).clip(0, 1)

    inp = gray(pack(["R", "Gr", "Gb", "B"], False))
    i2, i4, i8 = band_rms(inp, 0, 1), band_rms(inp, 1, 2), band_rms(inp, 2, 4)
    print(f"输入 DNG(单帧真实噪声) σ={flat_sigma(inp/255)*255:.2f}  "
          f"2px={i2:.3f} 4px={i4:.3f} 8px={i8:.3f}")
    print(f"理论 cvt_k={base_cvt:.3f} (gain {base_cvt*INP_SCALE:.0f})\n")
    print(f"{'case':<34}{'σ':>7}{'2px':>7}{'4px':>7}{'8px':>7}{'art4':>7}{'mean':>7}")

    cases = [
        ("1_ENGINE(B,Gr,Gb,R)_noflip", ["B", "Gr", "Gb", "R"], False, base_cvt),
        ("2_OFFICIAL(R,Gr,Gb,B)_noflip", ["R", "Gr", "Gb", "B"], False, base_cvt),
        ("3_OFFICIAL_flip(=官方)", ["R", "Gr", "Gb", "B"], True, base_cvt),
        ("4_OFFICIAL_flip_cvt8", ["R", "Gr", "Gb", "B"], True, base_cvt * 2),
        ("5_OFFICIAL_flip_cvt16", ["R", "Gr", "Gb", "B"], True, base_cvt * 4),
        ("6_ENGINE_cvt16", ["B", "Gr", "Gb", "R"], False, base_cvt * 4),
    ]
    for name, order, flip, cvt in cases:
        p = pack(order, flip)
        o = run_tiles(interp, p * (cvt * INP_SCALE)) / (cvt * INP_SCALE)
        g = gray(o)
        b2, b4, b8 = band_rms(g, 0, 1), band_rms(g, 1, 2), band_rms(g, 2, 4)
        print(f"{name:<34}{flat_sigma(g/255)*255:>7.2f}{b2:>7.3f}{b4:>7.3f}{b8:>7.3f}"
              f"{b4/i4:>7.3f}{g.mean()/inp.mean():>7.4f}")
        Image.fromarray(g.clip(0, 255).astype(np.uint8)).save(outdir / f"{name}.png")
    Image.fromarray(inp.clip(0, 255).astype(np.uint8)).save(outdir / "input.png")
    print("→", outdir)


if __name__ == "__main__":
    main()
