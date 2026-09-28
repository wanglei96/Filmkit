#!/usr/bin/env python3
"""两条色彩链的饱和度对拍（v0.10.24 诊断日志配套）。

用法：python3 tools/ccm-chain-ab.py <日志.jsonl.txt> <对应.dng>
日志里 raw.meta 现在同时带 ccmStd（ForwardMatrix 双光源插值链）与 ccmHal
（HAL COLOR_CORRECTION_TRANSFORM），本脚本用同一个 DNG 分别过两条链，
比较"进入 filmic 之前"的色度（CIELAB C*），并给出各自预测的成片色度差。

为什么要比"进 filmic 之前"：filmic 的 gamut min 输出 = min(orig, fin)，而逐通道
凸幂使 fin ≥ orig ⇒ 成片色度 ≈ CCM 输出色度 —— 所以这一层的差就是成片的差。
"""
import json
import struct
import sys

import numpy as np
from PIL import Image

BLACK, WHITE = 64.0, 1023.0          # 由 DNG 标签读出后覆盖
XYZ2SRGB_D65 = np.array([[3.2404542, -1.5371385, -0.4985314],
                         [-0.9692660, 1.8760108, 0.0415560],
                         [0.0556434, -0.2040259, 1.0572252]])


def parse_ccm(s):
    return np.array([float(x) for x in s.split(",")], np.float64).reshape(3, 3)


def read_dng(path):
    buf = open(path, "rb").read()
    endian = "<" if buf[:2] == b"II" else ">"
    ifd0 = struct.unpack(endian + "I", buf[4:8])[0]
    n = struct.unpack(endian + "H", buf[ifd0:ifd0 + 2])[0]
    tags = {}
    for i in range(n):
        e = ifd0 + 2 + i * 12
        tag, typ, cnt = struct.unpack(endian + "HHI", buf[e:e + 8])
        tags[tag] = (typ, cnt, struct.unpack(endian + "I", buf[e + 8:e + 12])[0])
    w = tags[256][2]
    h = tags[257][2]
    strip_off = tags[273][2]
    cnt = tags[279][2]
    offs = np.frombuffer(buf, dtype=endian + "u4", count=cnt, offset=strip_off)
    raw = np.frombuffer(buf, dtype=endian + "u2", count=w * h,
                        offset=int(offs[0])).reshape(h, w).astype(np.float32)
    black = BLACK
    if 50714 in tags:
        _, bc, bo = tags[50714]
        typ = tags[50714][0]
        if typ == 5:
            vals = []
            for k in range(bc):
                num, den = struct.unpack(endian + "II", buf[bo + k * 8:bo + k * 8 + 8])
                vals.append(num / den if den else 0.0)
            black = float(np.mean(vals))
    white = float(tags[50717][2]) if 50717 in tags else WHITE
    neutral = np.array([0.4812, 1.0, 0.6206])
    if 50728 in tags:
        _, nc, no = tags[50728]
        vals = []
        for k in range(nc):
            num, den = struct.unpack(endian + "II", buf[no + k * 8:no + k * 8 + 8])
            vals.append(num / den if den else 0.0)
        neutral = np.array(vals)
    return raw, black, white, neutral


def rgb_of(raw, black, white, neutral, ccm):
    lin = np.clip((raw - black) / (white - black), 0, None)
    # CFA=1 (GRBG)：row0 = G R G R / row1 = B G B G
    R = lin[0::2, 1::2]
    G = 0.5 * (lin[0::2, 0::2] + lin[1::2, 1::2])
    B = lin[1::2, 0::2]
    g = 1.0 / neutral
    g = g / g[1]
    rgb = np.stack([R * g[0], G * g[1], B * g[2]], -1)
    return (ccm @ rgb.reshape(-1, 3).T).T.reshape(R.shape[0], R.shape[1], 3)


def lab_stats(rgb, mask_red=None):
    flat = rgb.reshape(-1, 3)
    lin = np.clip(flat, 0, None)
    xyz = lin @ np.array([[0.4124564, 0.3575761, 0.1804375],
                          [0.2126729, 0.7151522, 0.0721750],
                          [0.0193339, 0.1191920, 0.9503041]]).T
    xyz = xyz / np.array([0.95047, 1.0, 1.08883])
    e, k = 216 / 24389, 24389 / 27
    f = np.where(xyz > e, np.cbrt(np.maximum(xyz, 0)), (k * xyz + 16) / 116)
    L = 116 * f[:, 1] - 16
    A = 500 * (f[:, 0] - f[:, 1])
    Bc = 200 * (f[:, 1] - f[:, 2])
    C = np.sqrt(A * A + Bc * Bc)
    H = np.degrees(np.arctan2(Bc, A))
    red = (H > -40) & (H < 40) & (C > 20)
    return L[red].mean(), C[red].mean(), red.mean() * 100


def main():
    log_path, dng_path = sys.argv[1], sys.argv[2]
    ccm_std = ccm_hal = ccm_used = None
    for line in open(log_path, encoding="utf-8", errors="replace"):
        if '"raw.meta"' not in line:
            continue
        kv = json.loads(line)["kv"]
        if "ccmStd" in kv:
            ccm_std = parse_ccm(kv["ccmStd"]) if kv["ccmStd"] != "null" else None
            ccm_hal = parse_ccm(kv["ccmHal"])
            ccm_used = kv["ccmSrc"]
    if ccm_hal is None:
        sys.exit("日志里没有 ccmStd/ccmHal 字段 —— 需要 v0.10.24+ 的包拍的照片")
    raw, black, white, neutral = read_dng(dng_path)
    print(f"DNG {raw.shape[1]}x{raw.shape[0]}  black={black:.1f} white={white:.0f} "
          f"neutral={neutral.round(4)}   日志当前使用: {ccm_used}\n")
    print(f"{'链':26s} {'红杯 C*':>9s} {'红杯 L*':>9s} {'红像素%':>8s}")
    rows = []
    for name, M in (("StdColorMatrix(ForwardMatrix)", ccm_std), ("HAL COLOR_CORRECTION", ccm_hal)):
        if M is None:
            print(f"{name:26s}   (缺数据)")
            continue
        L, C, pct = lab_stats(rgb_of(raw, black, white, neutral, M))
        rows.append((name, L, C))
        print(f"{name:26s} {C:9.2f} {L:9.2f} {pct:8.2f}")
    if len(rows) == 2:
        (n1, l1, c1), (n2, l2, c2) = rows
        print(f"\n→ 两条链的红杯 C* 差 {abs(c1-c2)/max(c1,c2)*100:.1f}%  "
              f"(std {c1:.2f} vs hal {c2:.2f})")
        print(f"   参考：我们(HAL 或 std)与 PhotonCamera 实测差 17~21%")


if __name__ == "__main__":
    main()
