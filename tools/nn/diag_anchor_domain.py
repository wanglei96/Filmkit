#!/usr/bin/env python3
"""网络能力上限对照：喂"网络期望的输入分布"（SIDD anchor 噪声 + cvt_k=1）。

如果此时仍糊/有伪影 ⇒ 网络或实现本身有问题；
如果此时干净且保细节 ⇒ X300 域不匹配是根因（解 = finetune）。

方法：clean 用 DNG 近似（自身噪声 σ≈0.0023 img01，远小于 anchor 的 0.0102@x=0.1），
按 SIDD anchor 模型 σ²=S_a·x+O_a 合成噪声，cvt_k=1（anchor 恒等，与官方
run_benchmark 对 ISO1600 的处理完全一致），输出后直接与 clean 比。
"""
import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from diag_255b import (INP_SCALE, band_rms, flat_sigma, gray, load_b01,
                       phase_blocks, run_tiles, synth_noise)

S_A = 1.0093e-3     # SIDD anchor img01 域
O_A = 3.2397e-6


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dng", default="test_temp/FILMKIT_20261002-124812.dng")
    ap.add_argument("--crop", type=int, default=1024)
    ap.add_argument("--model", default="tools/nn/out/pmrid_512_fp16.tflite")
    ap.add_argument("--out", default="/tmp/nn_anchor")
    args = ap.parse_args()
    outdir = Path(args.out); outdir.mkdir(parents=True, exist_ok=True)

    b01, pat = load_b01(Path(args.dng), args.crop)
    blk = phase_blocks(b01, pat)
    ORDER = ["R", "Gr", "Gb", "B"]
    clean = np.stack([blk[c] for c in ORDER], -1).clip(0, 1)

    rng = np.random.default_rng(7)
    noisy = np.stack([synth_noise(blk[c], S_A, O_A, rng) for c in ORDER], -1).clip(0, 1)

    import tensorflow as tf
    interp = tf.lite.Interpreter(model_path=args.model, num_threads=4)
    interp.allocate_tensors()

    cg, ng = gray(clean), gray(noisy)
    c2, c4, c8 = band_rms(cg, 0, 1), band_rms(cg, 1, 2), band_rms(cg, 2, 4)
    print(f"clean(DNG)  σ={flat_sigma(cg/255)*255:.2f}  2px={c2:.3f} 4px={c4:.3f} 8px={c8:.3f}")
    print(f"anchor噪声  σ={flat_sigma(ng/255)*255:.2f}  2px={band_rms(ng,0,1):.3f} "
          f"4px={band_rms(ng,1,2):.3f} 8px={band_rms(ng,2,4):.3f}")
    print(f"\n{'case':<30}{'σ':>7}{'2px':>7}{'4px':>7}{'8px':>7}{'ret4':>7}{'art4':>7}{'mean':>7}")

    # cvt_k=1 = anchor 恒等（网络训练分布）；另跑 anchor 序的对照
    for name, order, cvt in [("anchor域_cvt1_official序", ORDER, 1.0),
                             ("anchor域_cvt1_engine序", ["B", "Gr", "Gb", "R"], 1.0),
                             ("anchor域_cvt2(过度)", ORDER, 2.0),
                             ("anchor域_cvt0.5(不足)", ORDER, 0.5)]:
        p = np.stack([noisy[..., ORDER.index(c)] for c in order], -1) if order != ORDER \
            else noisy
        o = run_tiles(interp, p * (cvt * INP_SCALE)) / (cvt * INP_SCALE)
        g = gray(o)
        b2, b4, b8 = band_rms(g, 0, 1), band_rms(g, 1, 2), band_rms(g, 2, 4)
        n4 = band_rms(ng, 1, 2)
        print(f"{name:<30}{flat_sigma(g/255)*255:>7.2f}{b2:>7.3f}{b4:>7.3f}{b8:>7.3f}"
              f"{b4/c4:>7.3f}{b4/n4:>7.3f}{g.mean()/cg.mean():>7.4f}")
        Image.fromarray(g.clip(0, 255).astype(np.uint8)).save(outdir / f"{name}.png")
    Image.fromarray(cg.clip(0, 255).astype(np.uint8)).save(outdir / "clean.png")
    Image.fromarray(ng.clip(0, 255).astype(np.uint8)).save(outdir / "noisy_anchor.png")
    print("→", outdir)


if __name__ == "__main__":
    main()
