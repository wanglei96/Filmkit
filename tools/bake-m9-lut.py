#!/usr/bin/env python3
"""把 PC 固件考古的 M9 配方分支矩阵烘焙为纯色彩 display 域 3D LUT（徕卡一瞬标定 19 号调研落地）。

源：/tmp/pc-full/app/src/main/assets/leica/m9/PROCESS/LUTS.bin（427744B，SHA256 锁定，
    解码校验逐条对照 vendored 语义的 PC `LeicaProfile.kt`：offset 关系 / 矩阵索引
    `COLOR_SPACE*5+SATURATION` / 曲线索引 `COLOR_SPACE*5+CONTRAST`）。
出：app/src/main/assets/luts/leica_m9_ccd.lut（64³ uint8，格式同 gen-lut-assets）。

**烘焙语义（2026-10-25 数据验证定案，不做猜测）**：
PC 引擎 10 的 M9 段 = 线性 RGB → ×16383 取整（14-bit）→ 按 R≥G 选分支矩阵
（Q16 整数，行和 ≈ 8192 = 浮点 1.000/8，即输出 = 线性值/8 的 11-bit 编码）→
`>>16` → clamp[0,2047] → 查 2048 点曲线 → sRGB decode 输出线性。
曲线输入域 = 线性值的 11-bit 编码（table[368]=0.588 与"0.18→0.59"吻合）、输出
sRGB 编码——是影调曲线，职责归 filmic latitude（调研定案"只烘矩阵不烘曲线"）。
剥离曲线后矩阵输出 `/2047` 恰好还原线性值 ⇒ LUT = 纯线性域色彩映射，行和=1
保证中性恒等（灰轴零偏色）、分支边界两侧矩阵输出近似连续（R 行差 [1152,-1147,0]
在 R=G 处归零）。

**应用域偏差（已知、真机 A/B 验证）**：PC 在 M9 传感器白平衡域应用矩阵；我们
没有 M9 传感器，LUT 输入 = 我们成片的 display 线性域（sRGB decode 后）——风格
迁移近似，一期判据 = 配方数学本体逐位对照（不依赖小米样张）。

用法：
    python3 tools/bake-m9-lut.py                 # 烘焙 + 写资产 + 跑判据
    python3 tools/bake-m9-lut.py --check-only    # 只跑判据（不写资产）
"""
import argparse
import hashlib
import os
import struct
import sys

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = "/tmp/pc-full/app/src/main/assets/leica/m9/PROCESS/LUTS.bin"
OUT = os.path.join(ROOT, "app/src/main/assets/luts/leica_m9_ccd.lut")
SIZE = 64
# PC LeicaProfile.kt 同款：M9 sRGB / 标准饱和 / 标准对比
COLOR_SPACE, SATURATION, CONTRAST = 0, 2, 2
EXPECT_SHA256 = "25debaf581d3fdbeca6ad7c6426ca5d9e597b98856c540ceddcc912b79409d8d"
TITLE, CATEGORY = "徕卡 M9 CCD", "徕卡"

# PC shader 语义：14-bit 输入量化、11-bit 索引钳制（曲线索引域）
QUANT = 16383.0
IDX_MAX = 2047


def srgb_decode(c):
    """精确 sRGB EOTF（与引擎 kFsOut 同一公式族）。"""
    c = np.clip(c, 0.0, 1.0)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def srgb_encode(l):
    l = np.clip(l, 0.0, 1.0)
    return np.where(l <= 0.0031308, l * 12.92, 1.055 * l ** (1.0 / 2.4) - 0.055)


def decode_luts_bin(path):
    """解码 LUTS.bin：校验与布局逐条对照 PC LeicaProfile.kt。"""
    data = open(path, "rb").read()
    digest = hashlib.sha256(data).hexdigest()
    assert len(data) == 427744, f"size {len(data)}"
    assert digest == EXPECT_SHA256, f"sha256 {digest}"

    def i32(off):
        return struct.unpack_from("<i", data, off)[0]

    def i16(off):
        return struct.unpack_from("<h", data, off)[0]

    curve_off, matrix_off, cal_off = 0xCF8, 0xB44, 0xCD0
    assert i32(0) == 13 and i32(0x18) == 20 and i32(0x1C) == 2048
    assert i32(0x54) == 2 and i32(0x58) == 5 and i32(0x5C) == 5
    assert i32(4) == curve_off and i32(8) == matrix_off and i32(12) == cal_off
    assert matrix_off + 11 * 36 == cal_off and cal_off + 40 == curve_off
    assert curve_off + 20 * 2048 == i32(0x10)

    matrix_index = COLOR_SPACE * 5 + SATURATION
    branches = []
    for branch in (0, 1):
        coefs = np.array(
            [i16(matrix_off + matrix_index * 36 + branch * 18 + k * 2) for k in range(9)],
            dtype=np.int64,
        )
        # PC 端 require：带符号系数 × 14-bit 输入不得溢出 32-bit（逐行）
        for row in range(3):
            assert np.abs(coefs[row * 3 : row * 3 + 3]).sum() * 16383 <= 2**31 - 1
        branches.append(coefs.reshape(3, 3))
    curve_index = COLOR_SPACE * 5 + CONTRAST
    curve = np.frombuffer(
        data[curve_off + curve_index * 2048 : curve_off + (curve_index + 1) * 2048],
        dtype=np.uint8).astype(np.float64) / 255.0
    assert curve[0] == 0.0 and curve[-1] == 1.0
    assert np.all(np.diff(curve) >= 0.0)
    return branches, curve


def bake(branches, size):
    """全格点向量化烘焙：格点 = display 编码域均匀网格（i/(N-1)，引擎 texelFetch 同口径）。"""
    m0, m1 = branches  # branch0 = R≥G, branch1 = R<G（行 = 输出通道，列 = R,G,B）
    v = np.linspace(0.0, 1.0, size)
    B, G, R = np.meshgrid(v, v, v, indexing="ij")  # 轴序 [b][g][r]，内存 R 最快
    cam = [np.floor(srgb_decode(c) * QUANT).astype(np.int64) for c in (R, G, B)]
    camR, camG, camB = cam
    branch0 = camR >= camG

    out = np.empty((size, size, size, 3), dtype=np.float64)
    for ch in range(3):
        idx0 = (m0[ch, 0] * camR + m0[ch, 1] * camG + m0[ch, 2] * camB) >> 16
        idx1 = (m1[ch, 0] * camR + m1[ch, 1] * camG + m1[ch, 2] * camB) >> 16
        idx = np.where(branch0, idx0, idx1).clip(0, IDX_MAX)
        out[..., ch] = idx / IDX_MAX  # 剥离曲线：11-bit 索引 / 2047 = 线性输出
    return srgb_encode(out)


def trilinear(lut, coords):
    """引擎同款三线性（t = c×(N-1)，格点口径 i/(N-1)）；coords[...,3] ∈ [0,1]。"""
    n = lut.shape[0]
    t = np.clip(coords, 0.0, 1.0) * (n - 1)
    i0 = np.clip(np.floor(t).astype(np.int64), 0, n - 2)
    fr = t - i0
    out = np.zeros(coords.shape[:-1] + (3,))
    for db in (0, 1):
        for dg in (0, 1):
            for dr in (0, 1):
                w = ((fr[..., 0] if dr else 1 - fr[..., 0])
                     * (fr[..., 1] if dg else 1 - fr[..., 1])
                     * (fr[..., 2] if db else 1 - fr[..., 2]))
                out += w[..., None] * lut[i0[..., 2] + db, i0[..., 1] + dg, i0[..., 0] + dr]
    return out


def direct(coords, branches):
    """配方直算（矩阵段）：同一整数语义、不经 LUT。coords 为编码域。"""
    m0, m1 = branches
    cam = [np.floor(srgb_decode(coords[..., k]) * QUANT).astype(np.int64) for k in range(3)]
    branch0 = cam[0] >= cam[1]
    out = np.empty(coords.shape[:-1] + (3,))
    for ch in range(3):
        idx0 = (m0[ch, 0] * cam[0] + m0[ch, 1] * cam[1] + m0[ch, 2] * cam[2]) >> 16
        idx1 = (m1[ch, 0] * cam[0] + m1[ch, 1] * cam[1] + m1[ch, 2] * cam[2]) >> 16
        out[..., ch] = np.where(branch0, idx0, idx1).clip(0, IDX_MAX) / IDX_MAX
    return srgb_encode(out)


def check(lut, branches):
    """管线正确性判据（判据双轨之一，不依赖样张）：LUT 插值 ≈ 配方直算。"""
    rng = np.random.default_rng(20261025)
    g = rng.random((200_000, 3))
    # 分支边界带（R≈G）专门构造：随机点自然落带的太少，统计力不足。
    # lin 域取 R=G±{0..8}/16383 再 encode，覆盖切换面两侧。
    gl, bl = rng.random(20_000), rng.random(20_000)
    eps = (rng.integers(-8, 9, 20_000) / QUANT)[:, None]
    lin = np.stack([gl, gl, bl], axis=1) + np.stack([eps[:, 0], np.zeros_like(gl), np.zeros_like(gl)], axis=1)
    border = np.clip(srgb_encode(np.clip(lin, 0.0, 1.0)), 0.0, 1.0)

    for name, pts in (("随机 200k", g), ("分支边界带 20k", border)):
        ref = direct(pts, branches)
        got = trilinear(lut.astype(np.float64), pts)
        err = np.abs(got - ref) * 255.0
        print(f"判据 1 {name} LUT 插值 vs 配方直算（编码域 LSB）: "
              f"max={err.max():.2f} mean={err.mean():.4f} p99.9={np.percentile(err, 99.9):.2f}")

    # 灰轴恒等：行和 ≠ 精确 8192（8198/8192/8185，配方固有 ±0.09%）+ uint8 量化
    axis = np.linspace(0.0, 1.0, 257)[:, None].repeat(3, axis=1)
    gerr = np.abs(trilinear(lut.astype(np.float64), axis) - axis) * 255.0
    print(f"判据 2 灰轴恒等（257 点）: max={gerr.max():.3f} LSB（配方行和固有偏差 + 量化）")

    # 格点跳变 = 映射固有斜率（格点=直算，忠实配方）；插值误差才是 LUT 表达力（判据 1）
    d = max(np.abs(np.diff(lut, axis=a)).max() for a in (0, 1, 2))
    print(f"判据 3 格点最大跳变: {d * 255:.1f} LSB（钳制面/暗端陡斜率区，配方固有）")

    # 顶点保真：黑白点
    print(f"判据 4 端点: black {lut[0,0,0].round(3)} → 期望 [0,0,0]；"
          f"white {lut[-1,-1,-1].round(3)} → 期望 [1,1,1]")


def write_asset(lut, out_path):
    lut8 = np.clip(lut * 255.0 + 0.5, 0, 255).astype(np.uint8)
    tb, cb = TITLE.encode(), CATEGORY.encode()
    blob = bytes([SIZE, len(tb)]) + tb + bytes([len(cb)]) + cb + lut8.tobytes()
    with open(out_path, "wb") as f:
        f.write(blob)
    print(f"写出 {out_path}（{len(blob)} 字节，{SIZE}³ uint8）")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=SRC)
    ap.add_argument("--out", default=OUT)
    ap.add_argument("--check-only", action="store_true")
    args = ap.parse_args()

    branches, curve = decode_luts_bin(args.src)
    m0, m1 = branches
    print(f"矩阵（Q16 整数，÷65536；Q13 视角 ÷8192 行和=1 保 WB）matrixIndex="
          f"{COLOR_SPACE * 5 + SATURATION}，curveIndex={COLOR_SPACE * 5 + CONTRAST}")
    for name, m in (("branch0 (R≥G)", m0), ("branch1 (R<G)", m1)):
        print(f"  {name}:")
        for r in range(3):
            row = m[r]
            print(f"    [{row[0]:>6d} {row[1]:>6d} {row[2]:>6d}]  行和={row.sum()}  "
                  f"浮点={[round(c / 8192.0, 4) for c in row]}")
    print(f"曲线（不烘焙，职责归 filmic）：table[368]={curve[368]:.3f}（线性 0.18→0.59 吻合）、"
          f"table[255]={curve[255]:.3f}、table[1023]={curve[1023]:.3f}")

    lut = bake(branches, SIZE)
    check(lut, branches)
    if not args.check_only:
        write_asset(lut, args.out)


if __name__ == "__main__":
    sys.exit(main())
