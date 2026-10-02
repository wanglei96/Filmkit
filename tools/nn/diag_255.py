#!/usr/bin/env python3
"""255 真机复测诊断：通道序 / KSigma 增益口径在真实 DNG 上的定量对照。

问题（用户 2026-10-02 真机复测 0.10.104-nnint3）：画面恢复正常但"细节下降极大、
文字全糊 + 物体边缘格纹"。日志 nzPreMed 0.003866 → nzPostMed 0.000156（÷25）。

本脚本回答两件事：
  1. 引擎口径（ch0=B,ch1=Gr,ch2=Gb,ch3=R + cvt_k=S_anchor/S_iso）与官方口径
     （bggr2rggb rot180 → bayer2rggb → [R,Gr,Gb,B] + cvt_k=K(1600)/K(iso)）
     在真实 DNG 上的输出差异有多大；
  2. 降噪强度（输出/输入带通比）是否与真机 ÷25 同量级 —— 区分"引擎实现 bug"
     与"口径/标定问题"。

统计口径沿用 tools/nn/quality_eval.py（归一带通 + 去梯度 σ），但修正其
两处已知错误：packed 通道序未按 CFA 语义、增益用 959/256。
"""
import argparse
import sys
from pathlib import Path

import numpy as np
import rawpy
from PIL import Image

TILE, OVERLAP = 512, 16
STEP = TILE - OVERLAP
INP_SCALE = 256.0

# 官方 run_benchmark.py 的 KSigma（SIDD 标定，anchor=1600）
K_OFFICIAL = (0.0005995267, 0.00868861)


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
    """rawpy/LibRaw 编码：0=R 1=G 2=B 3=G2。返回 {'R','Gr','Gb','B'} 四个半分辨率平面。"""
    rpos = next((y, x) for y in range(2) for x in range(2) if pat[y][x] == 0)
    bpos = next((y, x) for y in range(2) for x in range(2) if pat[y][x] == 2)
    grpos = (rpos[0], 1 - rpos[1])          # 与 R 同行的 G
    gbpos = (bpos[0], 1 - bpos[1])          # 与 B 同行的 G
    get = lambda p: b01[p[0]::2, p[1]::2]
    return {"R": get(rpos), "Gr": get(grpos), "Gb": get(gbpos), "B": get(bpos)}


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
    idet = interp.get_input_details()[0]
    odet = interp.get_output_details()[0]
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
    from PIL import Image, ImageFilter
    def blur(x, r):
        return np.asarray(Image.fromarray(x.clip(0, 255).astype(np.uint8))
                          .filter(ImageFilter.GaussianBlur(r)), dtype=np.float32)
    b = (a - blur(a, r2)) if r1 == 0 else (blur(a, r1) - blur(a, r2))
    return float(b.std())


def flat_sigma(a01, bs=64, n=6):
    yy, xx = np.mgrid[0:bs, 0:bs]
    A = np.stack([xx.ravel(), yy.ravel(), np.ones(bs * bs)], 1)
    best = []
    for by in range(0, a01.shape[0] - bs, 32):
        for bx in range(0, a01.shape[1] - bs, 32):
            blk = a01[by:by + bs, bx:bx + bs]
            coef, *_ = np.linalg.lstsq(A, blk.ravel(), rcond=None)
            best.append((float((blk.ravel() - A @ coef).std()), by, bx))
    best.sort()
    return float(np.median([s for s, _, _ in best[:n]]))


def gray(packed):
    """4 通道平均 → 半分辨率灰度（0-255 域，仅作纹理/噪声量级口径）。"""
    return packed.mean(axis=2) * 255.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dng", default="test_temp/FILMKIT_20261002-121335.dng")
    ap.add_argument("--iso", type=float, default=2057.0)
    ap.add_argument("--s-iso", type=float, default=6.334e-4, help="G 通道 shot 项（raw.noise）")
    ap.add_argument("--o-iso", type=float, default=3.967e-7)
    ap.add_argument("--s-anchor", type=float, default=4.5e-4, help="引擎 kNnAnchorS")
    ap.add_argument("--crop", type=int, default=1024, help="中心裁剪半径（mosaic px）")
    ap.add_argument("--model", default="tools/nn/out/pmrid_512_fp16.tflite")
    ap.add_argument("--out", default="/tmp/nn_diag255")
    args = ap.parse_args()
    outdir = Path(args.out); outdir.mkdir(parents=True, exist_ok=True)

    b01, pat = load_b01(Path(args.dng), args.crop)
    print(f"DNG {args.dng}  pattern={pat}  crop={b01.shape}")
    blk = phase_blocks(b01, pat)
    print("相位均值(0-255):", {k: round(float(v.mean() * 255), 1) for k, v in blk.items()})

    # 引擎口径 cvt_k（S_anchor/S_iso） vs 官方 KSigma（SIDD 标定）
    k_iso = K_OFFICIAL[0] * args.iso + K_OFFICIAL[1]
    k_anchor = K_OFFICIAL[0] * 1600.0 + K_OFFICIAL[1]
    cvt_engine = args.s_anchor / args.s_iso
    cvt_official = k_anchor / k_iso
    # 让输入噪声水平匹配 SIDD anchor（跨传感器标定：σ ∝ sqrt(S)）
    s_sidd_anchor = k_anchor / 959.0          # V 域 → img01 域
    cvt_match = float(np.sqrt(s_sidd_anchor / args.s_iso))
    print(f"cvt_k: engine={cvt_engine:.4f} (×256={cvt_engine*INP_SCALE:.1f})  "
          f"officialK={cvt_official:.4f} (×256={cvt_official*INP_SCALE:.1f})  "
          f"matchSIDD={cvt_match:.4f} (×256={cvt_match*INP_SCALE:.1f})")

    import tensorflow as tf
    interp = tf.lite.Interpreter(model_path=args.model, num_threads=4)
    interp.allocate_tensors()

    sem = np.stack([blk["R"], blk["Gr"], blk["Gb"], blk["B"]], -1).clip(0, 1)   # 官方
    eng = np.stack([blk["B"], blk["Gr"], blk["Gb"], blk["R"]], -1).clip(0, 1)   # 引擎

    cases = {
        "E1_engine_sem_BGrGbR_g182": (eng, cvt_engine * INP_SCALE),
        "E2_official_sem_RGrGbB_g182": (sem, cvt_engine * INP_SCALE),
        "E3_official_sem_RGrGbB_gOff": (sem, cvt_official * INP_SCALE),
        "E4_official_sem_RGrGbB_gMatch": (sem, cvt_match * INP_SCALE),
    }

    ref = gray(eng)                       # 输入（含真实噪声）参照
    ref_sig = flat_sigma(ref / 255.0)
    print(f"\n输入(含噪) 平坦σ={ref_sig*255:.2f}  带通 2px={band_rms(ref,0,1):.2f} "
          f"4px={band_rms(ref,1,2):.2f} 8px={band_rms(ref,2,4):.2f}")
    print(f"\n{'case':<32}{'σ':>8}{'2px':>8}{'4px':>8}{'8px':>8}{'4px比':>8}{'均值比':>8}")
    for name, (packed, gain) in cases.items():
        o = run_tiles(interp, packed * gain) / gain
        og = gray(o)
        b2, b4, b8 = band_rms(og, 0, 1), band_rms(og, 1, 2), band_rms(og, 2, 4)
        sig = flat_sigma(og / 255.0)
        in4 = band_rms(ref, 1, 2)
        print(f"{name:<32}{sig*255:>8.2f}{b2:>8.2f}{b4:>8.2f}{b8:>8.2f}"
              f"{b4/in4:>8.2f}{float(og.mean()/ref.mean()):>8.3f}")
        Image.fromarray(og.clip(0, 255).astype(np.uint8)).save(outdir / f"{name}.png")
    Image.fromarray(ref.clip(0, 255).astype(np.uint8)).save(outdir / "input_noisy.png")
    print("→", outdir)


if __name__ == "__main__":
    sys.exit(main())
