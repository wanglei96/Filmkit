#!/usr/bin/env python3
"""255 过度降噪定因：通道序 × KSigma 增益 × 输入噪声水平（模拟 MFNR）矩阵。

真机事实（0.10.104-nnint3, ISO2057, MFNR neff=4.0）：
  nzPreMed 0.003866 → nzPostMed 0.000156（÷24.8）；用户观感"细节下降极大、文字全糊"。
离线 diag_255.py 已复现同量级（σ ÷19.5），故非引擎实现 bug。

三个候选根因，本脚本逐一量化：
  H1 通道序：引擎 pack (B,Gr,Gb,R) vs 官方 bggr2rggb(rot180)+bayer2rggb → (R,Gr,Gb,B)
  H2 增益标定：cvt_k=S_anchor(X300)/S_iso vs 官方 K(1600)/K(iso) vs 匹配 SIDD anchor
  H3 输入噪声水平：真机 NN 输入是 MFNR 融合后（σ 已折减 ~0.45），而 cvt_k 按单帧算

判据：纹理保留率 ret = band(out)/band(clean)，clean 用 DNG 原图近似（含自身噪声，
故只看相对比较）；σ 为平坦块去梯度 std。
"""
import argparse
from pathlib import Path

import numpy as np
import rawpy
from PIL import Image

TILE, OVERLAP = 512, 16
STEP = TILE - OVERLAP
INP_SCALE = 256.0
K_OFFICIAL = (0.0005995267, 0.00868861)   # run_benchmark.py KSigma K_coeff


def load_b01(dng, crop):
    with rawpy.imread(str(dng)) as raw:
        bayer = raw.raw_image_visible.astype(np.float32)
        bl = np.array(raw.black_level_per_channel, dtype=np.float32).reshape(2, 2)
        white = float(raw.white_level)
        pat = raw.raw_pattern.tolist()
    h, w = bayer.shape
    blf = np.repeat(np.repeat(bl, h // 2, 0), w // 2, 1)
    b01 = np.clip((bayer - blf) / max(white - float(np.median(blf)), 1.0), 0, 1)
    cy, cx = h // 2, w // 2
    return b01[cy - crop:cy + crop, cx - crop:cx + crop], pat


def phase_blocks(b01, pat):
    rpos = next((y, x) for y in range(2) for x in range(2) if pat[y][x] == 0)
    bpos = next((y, x) for y in range(2) for x in range(2) if pat[y][x] == 2)
    get = lambda p: b01[p[0]::2, p[1]::2]
    return {"R": get(rpos), "Gr": get((rpos[0], 1 - rpos[1])),
            "Gb": get((bpos[0], 1 - bpos[1])), "B": get(bpos)}


def synth_noise(clean01, A, B, rng):
    lam = np.clip(clean01, 0, 1) / max(A, 1e-9)
    shot = rng.poisson(lam).astype(np.float32) * A
    read = rng.normal(0, B ** 0.5, clean01.shape).astype(np.float32)
    return np.clip(shot + read, 0.0, 1.0)


def run_tiles(interp, packed):
    hh, ww, _ = packed.shape
    ny, nx = -(-hh // STEP), -(-ww // STEP)
    out = np.zeros_like(packed)
    weight = np.zeros((hh, ww, 1), dtype=np.float32)
    ramp = np.ones((TILE, TILE, 1), dtype=np.float32)
    e = OVERLAP
    ramp[:e, :, 0] *= np.linspace(0, 1, e, dtype=np.float32)[:, None]
    ramp[-e:, :, 0] *= np.linspace(0, 1, e, dtype=np.float32)[::-1][:, None]
    ramp[:, :e, 0] *= np.linspace(0, 1, e, dtype=np.float32)[None, :]
    ramp[:, -e:, 0] *= np.linspace(0, 1, e, dtype=np.float32)[::-1][None, :]
    idet, odet = interp.get_input_details()[0], interp.get_output_details()[0]
    for iy in range(ny):
        for ix in range(nx):
            y0, x0 = iy * STEP, ix * STEP
            y1, x1 = min(y0 + TILE, hh), min(x0 + TILE, ww)
            t = np.pad(packed[y0:y1, x0:x1],
                       ((0, TILE - (y1 - y0)), (0, TILE - (x1 - x0)), (0, 0)))
            interp.set_tensor(idet["index"], t[None].astype(np.float32))
            interp.invoke()
            out[y0:y1, x0:x1] += interp.get_tensor(odet["index"])[0][:y1 - y0, :x1 - x0] \
                * ramp[:y1 - y0, :x1 - x0]
            weight[y0:y1, x0:x1] += ramp[:y1 - y0, :x1 - x0]
    return out / np.maximum(weight, 1e-6)


def band_rms(a, r1, r2):
    from PIL import ImageFilter
    def blur(x, r):
        return np.asarray(Image.fromarray(x.clip(0, 255).astype(np.uint8))
                          .filter(ImageFilter.GaussianBlur(r)), dtype=np.float32)
    b = (a - blur(a, r2)) if r1 == 0 else (blur(a, r1) - blur(a, r2))
    return float(b.std())


def flat_sigma(a01, bs=64):
    """去梯度 std：取最平坦 10% 块的中位数（代表平坦区噪声底）。"""
    yy, xx = np.mgrid[0:bs, 0:bs]
    A = np.stack([xx.ravel(), yy.ravel(), np.ones(bs * bs)], 1)
    vals = []
    for by in range(0, a01.shape[0] - bs, 48):
        for bx in range(0, a01.shape[1] - bs, 48):
            blk = a01[by:by + bs, bx:bx + bs]
            coef, *_ = np.linalg.lstsq(A, blk.ravel(), rcond=None)
            vals.append(float((blk.ravel() - A @ coef).std()))
    vals.sort()
    k = max(1, len(vals) // 10)
    return float(np.median(vals[:k]))


def gray(packed):
    return packed.mean(axis=2) * 255.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dng", default="test_temp/FILMKIT_20261002-121335.dng")
    ap.add_argument("--iso", type=float, default=2057.0)
    ap.add_argument("--s-iso", type=float, default=6.334e-4)
    ap.add_argument("--o-iso", type=float, default=3.967e-7)
    ap.add_argument("--s-anchor", type=float, default=4.5e-4)
    ap.add_argument("--crop", type=int, default=1024)
    ap.add_argument("--model", default="tools/nn/out/pmrid_512_fp16.tflite")
    ap.add_argument("--out", default="/tmp/nn_diag255b")
    args = ap.parse_args()
    outdir = Path(args.out); outdir.mkdir(parents=True, exist_ok=True)

    b01, pat = load_b01(Path(args.dng), args.crop)
    blk = phase_blocks(b01, pat)
    print(f"DNG {Path(args.dng).name} pattern={pat} crop={b01.shape}")

    k_iso = K_OFFICIAL[0] * args.iso + K_OFFICIAL[1]
    k_anchor = K_OFFICIAL[0] * 1600.0 + K_OFFICIAL[1]
    cvt_engine = args.s_anchor / args.s_iso
    cvt_offk = k_anchor / k_iso
    cvt_match = float(np.sqrt((k_anchor / 959.0) / args.s_iso))
    print(f"cvt_k: engine={cvt_engine:.3f}  officialK={cvt_offk:.3f}  matchSIDD={cvt_match:.3f}")

    import tensorflow as tf
    interp = tf.lite.Interpreter(model_path=args.model, num_threads=4)
    interp.allocate_tensors()

    rng = np.random.default_rng(20261002)
    # 合成噪声：以 DNG 为 clean 近似（自身仍有噪声，故仅作相对比较）
    noisy_full = {k: synth_noise(v, args.s_iso, args.o_iso, rng) for k, v in blk.items()}
    noisy_half = {k: synth_noise(v, args.s_iso * 0.25, args.o_iso * 0.25, rng)  # σ×0.5 ⇒ 方差×0.25
                  for k, v in blk.items()}

    def pack(d, order):
        return np.stack([d[c] for c in order], -1).clip(0, 1)

    OFFICIAL, ENGINE = ["R", "Gr", "Gb", "B"], ["B", "Gr", "Gb", "R"]
    cases = [
        ("clean_DNG",      None, None, None),
        ("noisy_full",     None, None, None),
        ("A_engine_0.71",  ENGINE,   noisy_full, cvt_engine),
        ("B_official_0.71", OFFICIAL, noisy_full, cvt_engine),
        ("C_official_offK", OFFICIAL, noisy_full, cvt_offk),
        ("D_official_match", OFFICIAL, noisy_full, cvt_match),
        ("E_engine_half",  ENGINE,   noisy_half, cvt_engine),
        ("F_official_match_half", OFFICIAL, noisy_half, cvt_match),
    ]

    ref_clean = gray(pack(blk, OFFICIAL))
    c2, c4, c8 = band_rms(ref_clean, 0, 1), band_rms(ref_clean, 1, 2), band_rms(ref_clean, 2, 4)
    csig = flat_sigma(ref_clean / 255.0)
    print(f"\n{'case':<24}{'σ(0-255)':>10}{'2px':>7}{'4px':>7}{'8px':>7}"
          f"{'ret2':>7}{'ret4':>7}{'ret8':>7}{'mean':>7}")
    print(f"{'clean(DNG)':<24}{csig*255:>10.2f}{c2:>7.2f}{c4:>7.2f}{c8:>7.2f}"
          f"{1.0:>7.2f}{1.0:>7.2f}{1.0:>7.2f}{1.0:>7.3f}")
    for name, order, src, cvt in cases:
        if order is None:
            if src is None and name == "clean_DNG":
                continue
            g = gray(pack(noisy_full, OFFICIAL)); cmean = ref_clean.mean()
        else:
            p = pack(src, order)
            o = run_tiles(interp, p * (cvt * INP_SCALE)) / (cvt * INP_SCALE)
            g = gray(o); cmean = ref_clean.mean()
            Image.fromarray(g.clip(0, 255).astype(np.uint8)).save(outdir / f"{name}.png")
        b2, b4, b8 = band_rms(g, 0, 1), band_rms(g, 1, 2), band_rms(g, 2, 4)
        sig = flat_sigma(g / 255.0)
        print(f"{name:<24}{sig*255:>10.2f}{b2:>7.2f}{b4:>7.2f}{b8:>7.2f}"
              f"{b2/c2:>7.2f}{b4/c4:>7.2f}{b8/c8:>7.2f}{g.mean()/cmean:>7.3f}")
    print("→", outdir)


if __name__ == "__main__":
    main()
