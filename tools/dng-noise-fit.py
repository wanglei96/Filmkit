#!/usr/bin/env python3
"""DNG 传感器噪声模型拟合（C 专项 2026-09-29）。

用途：从真机 DNG（单帧）拟合 `var(x) = S·x + O` 的 (S, O)，用于
  ① 验证 HAL 的 SENSOR_NOISE_PROFILE 是否准确（尤其隐藏物理镜头——X300 实测
     HAL 对三摄返回同一模型，而长焦真实噪声是主摄 3.6×）；
  ② 生成 GCam `.c` 标定（配合 tools/ 流程 + assets/noise_models/）。

方法：水平差分法 —— 同 CFA 相位水平相邻像素差 d → var_noise = var(d)/2，
消除块内低频（场景结构）污染；块级 (mean, var_noise) 线性回归得 S/O。

注意（重要）：
  · 绝对值含系统偏差（PSF/像素相关 → 低估；残留低频 → 高估 O）。**横向比较
    （同方法、同场景不同镜头）可靠**；跨镜头标定建议用"实测比值 × HAL 模型"
    的口径（见 assets/noise_models/*.c 的说明）。
  · 最佳实践是平场标定（均匀光源、多 ISO）——本工具也适用于此类样张。

用法：
  python3 tools/dng-noise-fit.py shot.dng [more.dng ...]
  python3 tools/dng-noise-fit.py --iso 1625 shot.dng   # 覆盖 ISO（默认读 EXIF）
"""
import struct
import sys

import numpy as np

try:
    import rawpy
except ImportError:
    sys.exit("需要 rawpy（pip install rawpy）")


def dng_iso(path):
    """从 DNG 读 ISOSpeed（tag 34855）与 ExposureTime（33434）。"""
    with open(path, "rb") as f:
        d = f.read(8)
        endian = "<" if d[:2] == b"II" else ">"
        f.seek(4)
        off = struct.unpack(endian + "I", f.read(4))[0]
        f.seek(off)
        n = struct.unpack(endian + "H", f.read(2))[0]
        TYP = {3: ("H", 2), 4: ("I", 4), 5: ("II", 8)}
        for _ in range(n):
            pos = f.tell()
            e = f.read(12)
            t, typ, cnt = struct.unpack(endian + "HHI", e[:8])
            v = struct.unpack(endian + "I", e[8:12])[0]
            if t == 34855:
                fmt, sz = TYP[typ]
                f.seek(v if cnt * sz > 4 else pos + 8)
                return int(struct.unpack(endian + fmt * cnt, f.read(cnt * sz))[0])
    return 0


def fit(path, blk=64, iso_override=None):
    rp = rawpy.imread(path)
    raw = rp.raw_image_visible.astype(np.float64)
    bl = np.array(rp.black_level_per_channel, dtype=np.float64)
    wl = float(rp.white_level)
    pat = rp.raw_pattern
    per_plane = {}
    for py in (0, 1):
        for px in (0, 1):
            ch = int(pat[py, px])
            x = (raw[py::2, px::2] - bl[ch]) / (wl - bl[ch])
            d = x[:, :-1] - x[:, 1:]
            hh = (d.shape[0] // blk) * blk
            ww = (d.shape[1] // blk) * blk
            xb = x[:hh, :ww].reshape(hh // blk, blk, ww // blk, blk)
            db = d[:hh, :ww].reshape(hh // blk, blk, ww // blk, blk)
            mean = xb.mean(axis=(1, 3))
            var_noise = db.var(axis=(1, 3)) / 2.0
            gy, gx = np.gradient(mean)
            g = np.hypot(gy, gx)
            flat = g < np.percentile(g, 70)
            m, v = mean[flat], var_noise[flat]
            keep = v < np.percentile(v, 99)
            m, v = m[keep], v[keep]
            A = np.vstack([m, np.ones_like(m)]).T
            S, O = np.linalg.lstsq(A, v, rcond=None)[0]
            per_plane[(py, px, ch)] = (S, O, len(m))
    return per_plane, rp, (iso_override or dng_iso(path))


def main():
    args = [a for a in sys.argv[1:]]
    iso_override = None
    if "--iso" in args:
        i = args.index("--iso")
        iso_override = int(args[i + 1])
        del args[i:i + 2]
    if not args:
        print(__doc__)
        return
    for path in args:
        planes, rp, iso = fit(path, iso_override=iso_override)
        # DNG 的 NoiseProfile tag（HAL 模型）供对比
        print(f"=== {path.split('/')[-1]}  ISO {iso}  raw {rp.raw_image_visible.shape} ===")
        print("   rawpy ch: 0=R 1=G1 2=B 3=G2（相位 → 通道）")
        for (py, px, ch), (S, O, n) in sorted(planes.items()):
            print(f"   phase({py},{px}) ch{ch}: S={S:.4e}  O={O:.4e}  (n={n})")
        print()


if __name__ == "__main__":
    main()
