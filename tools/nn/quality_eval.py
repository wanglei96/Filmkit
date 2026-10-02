#!/usr/bin/env python3
"""离线质量对照(骨架):同一 RAW 喂「直通 / PMRID」跑噪声-纹理曲线。

噪声合成:X300 实测噪声模型 σ²=A·x+B(assets/noise_models 与 raw.noise 日志同源,
泊松-高斯;与 C 专项同参数化)。对照口径:midband-attrib 的归一带通 + 平坦块 σ。

输入源(二选一):
  --dng  <path>   我方 DNG(rawpy 解 linear Bayer,最贴合我们链路)
  --raw  <path> --shape H,W   PMRID benchmark 的 uint16 bayer .raw

用法示例:
  python3 tools/nn/quality_eval.py --dng ~/Downloads/x.dng --iso 3200 --out tools/nn/out
"""
import argparse
from pathlib import Path

import numpy as np

TILE = 512
OVERLAP = 16


def load_bayer(path, shape=None):
    import rawpy

    if path.suffix.lower() == ".dng":
        with rawpy.imread(str(path)) as raw:
            bayer = raw.raw_image_visible.astype(np.float32)
            black = np.array(raw.black_level_per_channel, dtype=np.float32).reshape(2, 2)
            black = np.repeat(np.repeat(black, bayer.shape[0] // 2, 0), bayer.shape[1] // 2, 1)
            white = float(raw.white_level)
    else:
        bayer = np.fromfile(str(path), dtype=np.uint16).astype(np.float32)
        if shape:
            bayer = bayer.reshape(shape)
        black = np.zeros_like(bayer)
        white = 1023.0
    return (bayer - black) / max(white - np.median(black), 1.0)


def bayer2rggb(b01):
    # packed 4ch(R0 Gr / Gb B),PMRID 输入口径
    h, w = b01.shape
    return b01.reshape(h // 2, 2, w // 2, 2).transpose(0, 2, 1, 3).reshape(h // 2, w // 2, 4)


def synth_noise(clean01, A, B, rng):
    """泊松-高斯:x·A 部分按泊松缩放(x·N_poisson 归一),B 部分加高斯。"""
    lam = np.clip(clean01, 0, 1) / max(A, 1e-9)  # 光子数(等效)
    shot = rng.poisson(lam).astype(np.float32) * A
    read = rng.normal(0, B ** 0.5, clean01.shape).astype(np.float32)
    return np.clip(shot + read, 0.0, 1.0)


def run_pmrid(rggb, model_path):
    import tensorflow as tf

def run_pmrid(rggb, model_path):
    import tensorflow as tf

    interp = tf.lite.Interpreter(model_path=str(model_path))
    interp.allocate_tensors()
    idet, odet = interp.get_input_details()[0], interp.get_output_details()[0]
    h, w, c = rggb.shape  # (H,W,C):解构错位会让 y1 越界切片静默截断(272 案)
    # 官方 Denoiser 口径(iso=anchor 1600 → VST 恒等):inp = rggb·V/inp_scale,
    # V=959、inp_scale=256;网络吃的是 ~0-数百量级,喂 [0,1] 会域错配
    SCALE = 959.0 / 256.0
    sy, sx = TILE - OVERLAP, TILE - OVERLAP
    ny, nx = -(-h // sy), -(-w // sx)
    out = np.zeros_like(rggb)
    weight = np.zeros((h, w, 1), dtype=np.float32)
    ramp = np.ones((TILE, TILE, 1), dtype=np.float32)
    e = OVERLAP
    r_y = np.linspace(0.0, 1.0, e, dtype=np.float32)[:, None]  # (e,1)
    r_x = np.linspace(0.0, 1.0, e, dtype=np.float32)[None, :]  # (1,e)
    ramp[:e, :, 0] *= r_y; ramp[-e:, :, 0] *= r_y[::-1]
    ramp[:, :e, 0] *= r_x; ramp[:, -e:, 0] *= r_x[:, ::-1]
    for iy in range(ny):
        for ix in range(nx):
            y0, x0 = iy * sy, ix * sx
            y1, x1 = min(y0 + TILE, h), min(x0 + TILE, w)
            pad_y, pad_x = TILE - (y1 - y0), TILE - (x1 - x0)
            tile_in = np.pad(rggb[y0:y1, x0:x1] * SCALE, ((0, pad_y), (0, pad_x), (0, 0)))
            interp.set_tensor(idet["index"], tile_in[None].astype(np.float32))
            interp.invoke()
            tile_out = interp.get_tensor(odet["index"])[0][: y1 - y0, : x1 - x0] / SCALE
            out[y0:y1, x0:x1] += tile_out * ramp[: y1 - y0, : x1 - x0]
            weight[y0:y1, x0:x1] += ramp[: y1 - y0, : x1 - x0]
    return out / np.maximum(weight, 1e-6)


def band_rms(Y, r1, r2):
    from PIL import Image, ImageFilter

    def blur(a, r):
        return np.asarray(
            Image.fromarray((a.clip(0, 255)).astype(np.uint8)).filter(ImageFilter.GaussianBlur(r)),
            dtype=np.float32)

    b = (Y - blur(Y, r2)) if r1 == 0 else (blur(Y, r1) - blur(Y, r2))
    return float(b.std())


def flat_sigma(c01, bs=64, n=6):
    """c01: [0,1] 单通道。返回去梯度后 std(0-1 域)。"""
    best = []
    Y = c01
    yy, xx = np.mgrid[0:bs, 0:bs]
    A = np.stack([xx.ravel(), yy.ravel(), np.ones(bs * bs)], 1)
    for by in range(0, Y.shape[0] - bs, 24):
        for bx in range(0, Y.shape[1] - bs, 24):
            blk = Y[by:by + bs, bx:bx + bs]
            coef, *_ = np.linalg.lstsq(A, blk.ravel(), rcond=None)
            best.append(((blk.ravel() - A @ coef).std(), by, bx))
    best.sort()
    return float(np.median([s for s, _, _ in best[:n]]))


def to_rgb_approx(rggb):
    """快速灰度近似(RGGB 均值)——粗口径,只看纹理/噪声量级,不做色彩。"""
    return rggb.mean(axis=2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dng")
    ap.add_argument("--raw")
    ap.add_argument("--shape", default="3072,4080")
    ap.add_argument("--iso", type=int, default=3200, help="查噪声模型用(X300 主摄内置标定)")
    ap.add_argument("--model", default="tools/nn/out/pmrid_512_fp16.tflite")
    ap.add_argument("--out", default="tools/nn/out")
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    src = Path(args.dng or args.raw)
    if src is None:
        raise SystemExit("需要 --dng 或 --raw")
    b01 = load_bayer(src, tuple(int(v) for v in args.shape.split(",")))
    b01 = b01.clip(0, 1)
    # 取中心 1536×2040(3×4 块无 pad)控制评估成本
    h, w = b01.shape
    cy, cx = h // 2, w // 2
    b01 = b01[cy - 768:cy + 768, cx - 1024:cx + 1024]
    rggb = bayer2rggb(b01)

    # X300 主摄噪声模型(raw.noise 实测;S 随 ISO 线性,O 恒定)
    noise_table = {
        3200: (8.98e-4, 3.9e-7),
        1600: (4.5e-4, 3.9e-7),
        640: (4.52e-4, 3.9e-7),   # 2026-10-02 09:33 实测(093334.dng 同刻)
    }
    A, B = noise_table.get(args.iso, noise_table[3200])

    rng = np.random.default_rng(2026)
    noisy = synth_noise(b01, A, B, rng)
    noisy_rggb = bayer2rggb(noisy.clip(0, 1))

    print(f"输入 {b01.shape}, 噪声模型 σ²=A·x+B: A={A:.2e} B={B:.2e}")
    # clean 参照:纹理保留率的真值锚点(noisy 的带通含噪声能量,不是纯纹理)
    rows = [("clean", rggb), ("noisy", noisy_rggb), ("pmrid", run_pmrid(noisy_rggb, Path(args.model)))]
    np.save(out / "eval_inputs.npy", np.stack([r[1] for r in rows]))

    print(f"\n{'路':<8}{'σ(平坦)':>10}{'2px':>8}{'4px':>8}{'8px':>8}")
    stats = {}
    for name, r in rows:
        g = to_rgb_approx(r)
        sig = flat_sigma(g)
        norm = 137.0 / 255.0
        y8 = (g * 255).astype(np.float32)
        b2 = band_rms(y8, 0, 1) * norm
        b4 = band_rms(y8, 1, 2) * norm
        b8 = band_rms(y8, 2, 4) * norm
        stats[name] = (sig, b2, b4, b8)
        print(f"{name:<8}{sig*255:>10.2f}{b2:>8.2f}{b4:>8.2f}{b8:>8.2f}")
    cs, c2, c4, c8 = stats["clean"]
    ps, p2, p4, p8 = stats["pmrid"]
    print(f"\n纹理保留率(PMRID/clean): 4px {p4/c4*100:.0f}%  8px {p8/c8*100:.0f}%  2px {p2/c2*100:.0f}%")
    print(f"PMRID 2px 增强倍数(vs noisy): {p2/stats['noisy'][1]:.2f}x")
    print(f"\n输出: {out}/eval_inputs.npy(供 100% 裁剪目视)")


if __name__ == "__main__":
    main()
