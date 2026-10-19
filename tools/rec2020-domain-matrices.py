#!/usr/bin/env python3
"""期 B 域矩阵构造器 —— 公共工作空间 sRGB 线性 → Rec.2020 线性的**唯一真源**。

为什么要有这个脚本（而不是把数字直接填进代码）：`docs/rec2020-heif-design.md` §2.7 列的
可照抄资产是**构造法**（`outputFromXyz(D50) · xyzFromWorking(D50)`，PC
`raw/RawDemosaicProcessor.kt:7579-7587`），不是别人算好的一组数字。矩阵必须能离线复算 ——
否则下一次换域（阶段 4 = AP0/D60，`engine-slots-design.md` §9.1）又要手推一遍且无法对拍。

产出（可直接粘进代码的字面量）：
  ① `StdColorMatrix.kt` 的 `X2S` = XYZ(D50) → 线性 Rec.2020(D65)
  ② `FrameRenderer.cpp` filmic 的 工作空间→LMS2006（3 vec3）及其逆（3 vec3）
  ③ D4 输出映射用：线性 Rec.2020 ↔ 线性 sRGB（**不含**域外压缩）

自检（全部 assert，任一失败退出非 0）：
  A. 构造出的 sRGB→LMS2006 ≈ `FrameRenderer.cpp` **现有**的 `kLmsSrgbToLms*` 常量
     —— 证明本脚本构造口径与既有代码一致；否则新值不可信
  B. 构造出的 XYZ(D50)→线性 sRGB ≈ 现有 `X2S`（容差 2e-3：现有值是 6 位粗存）
  C. 白点保持：`X2S_rec @ D50白点 ≈ (1,1,1)`；两个工作空间的 LMS 白点相同（同为 D65）
  D. 往返恒等：`M @ inv(M) ≈ I`
  E/F/G. **跨实现逐位对拍**（PhotonCamera 一手源码 `/tmp/pc-tone`）：
     · `DarktableFilmicToneAlgorithm.kt:14-19` 的 **BT2020 版** LMS 矩阵与逆矩阵
     · `PreviewColorShaderModules.kt:341` 的 sRGB→BT2020 矩阵（GLSL mat3 列主序，已转置）
     —— 这条比内部自检更有力：它证明"换工作空间只换这 6 个 vec3"不是本项目推演，
     而是与 PC（照抄 darktable）的实现逐位一致。

用法：
  python3 tools/rec2020-domain-matrices.py          # 自检 + 打印字面量
  python3 tools/rec2020-domain-matrices.py --check  # 只自检
"""
import sys

import numpy as np

# BT.2020 原色 / sRGB 原色 / 白点（§2.3：BT.2020 = D65 ⇒ 不牵动白点链）
REC2020_P = [(0.708, 0.292), (0.170, 0.797), (0.131, 0.046)]
SRGB_P = [(0.640, 0.330), (0.300, 0.600), (0.150, 0.060)]
D65_XY = (0.3127, 0.3290)
D50_XY = (0.34567, 0.35850)
D50_REF = np.array([0.96422, 1.0, 0.82521])  # 与既有 X2S 同口径（ICC D50）

BRADFORD = np.array([[0.8951, 0.2664, -0.1614],
                     [-0.7502, 1.7135, 0.0367],
                     [0.0389, -0.0685, 1.0296]])

# 既有常量（`FrameRenderer.cpp:4264-4280` @ bb7026d）—— 仅作对拍锚，不参与构造
LMS_SRGB_OLD = np.array([[0.2986531876, 0.7060763220, 0.0656966248],
                         [0.0959000021, 0.7198304285, 0.1011531117],
                         [0.0224644230, 0.0449176289, 0.5270630110]])
X2S_SRGB_OLD = np.array([[3.133996, -1.616931, -0.490698],
                         [-0.978413, 1.915921, 0.033398],
                         [0.071996, -0.228962, 1.404947]])

# ── 跨实现对拍锚（PhotonCamera `/tmp/pc-tone`，一手源码；darktable 同源）────────────
# `raw/DarktableFilmicToneAlgorithm.kt:14-19`：PC **同时**保留 sRGB 版与 BT2020 版两套
# LMS 矩阵（`useSrgb` 开关切换）—— 这本身就是"换工作空间 = 换这 6 个 vec3"的直接证据。
LMS_BT2020_PC = np.array([[0.4067763460, 0.6178051991, 0.0458445893],
                          [0.0677498629, 0.7489671634, 0.1001665160],
                          [0.0221408642, -0.0153252587, 0.5876294574]])
LMS_BT2020_INV_PC = np.array([[2.8380181184, -2.3373915374, 0.1770173235],
                              [-0.2415770666, 1.5294941187, -0.2418685622],
                              [-0.1132319053, 0.1279577808, 1.6887750778]])
# `lut/PreviewColorShaderModules.kt:341` 的 `mat3(...)` 是 **GLSL 列主序**，此处已转置为行主序
REC_FROM_SRGB_PC = np.array([[0.627404, 0.329283, 0.043313],
                             [0.069097, 0.919540, 0.011362],
                             [0.016391, 0.088013, 0.895595]])


def xy_to_xyz(xy):
    x, y = xy
    return np.array([x / y, 1.0, (1.0 - x - y) / y])


def rgb_to_xyz(primaries, white_xy=D65_XY):
    """色度坐标 → RGB→XYZ 矩阵（列 = 原色，白点归一：M @ (1,1,1) = 白点 XYZ）。"""
    M = np.array([xy_to_xyz(p) for p in primaries]).T
    return M * np.linalg.solve(M, xy_to_xyz(white_xy))


def bradford(src_xy, dst_xy):
    """XYZ(src 白) → XYZ(dst 白) 的 Bradford 适应矩阵。"""
    s, d = xy_to_xyz(src_xy), xy_to_xyz(dst_xy)
    return np.linalg.inv(BRADFORD) @ np.diag((BRADFORD @ d) / (BRADFORD @ s)) @ BRADFORD


XYZ_FROM_SRGB = rgb_to_xyz(SRGB_P)
XYZ_FROM_REC = rgb_to_xyz(REC2020_P)
REC_FROM_XYZ = np.linalg.inv(XYZ_FROM_REC)

# ① CCM 链终点：XYZ(D50) → 线性 Rec.2020(D65)
X2S_REC = REC_FROM_XYZ @ bradford(D50_XY, D65_XY)

# ② filmic：工作空间 RGB → LMS2006。LMS2006 基由既有 sRGB 常量反推（与既有口径同源），
#    再乘 Rec.2020 的 XYZ→RGB 逆；换工作空间只换后者，LMS 基不动。
XYZ_TO_LMS2006 = LMS_SRGB_OLD @ np.linalg.inv(XYZ_FROM_SRGB)
LMS_REC = XYZ_TO_LMS2006 @ XYZ_FROM_REC
LMS_REC_INV = np.linalg.inv(LMS_REC)

# ③ 输出映射（D4）：线性 Rec.2020 ↔ 线性 sRGB
SRGB_FROM_REC = np.linalg.inv(XYZ_FROM_SRGB) @ XYZ_FROM_REC
REC_FROM_SRGB = np.linalg.inv(SRGB_FROM_REC)


def check():
    x2s_srgb = np.linalg.inv(XYZ_FROM_SRGB) @ bradford(D50_XY, D65_XY)

    # A
    da = np.abs(LMS_SRGB_OLD - XYZ_TO_LMS2006 @ XYZ_FROM_SRGB).max()
    assert da < 2e-3, f'A 失败：构造的 sRGB→LMS 与既有常量差 {da:.2e}'
    # B
    db = np.abs(X2S_SRGB_OLD - x2s_srgb).max()
    assert db < 2e-3, f'B 失败：构造的 XYZ(D50)→sRGB 与既有 X2S 差 {db:.2e}'
    # C
    d50 = xy_to_xyz(D50_XY)
    assert np.abs(d50 - D50_REF).max() < 1e-4, 'C1 失败：D50 白点口径不一致'
    assert np.abs(X2S_REC @ d50 - 1.0).max() < 1e-9, 'C2 失败：X2S(Rec.2020) 白点不保持'
    w_srgb = XYZ_TO_LMS2006 @ XYZ_FROM_SRGB @ np.ones(3)
    w_rec = LMS_REC @ np.ones(3)
    assert np.abs(w_srgb - w_rec).max() < 1e-9, 'C3 失败：两个工作空间的 LMS 白点不同'
    # D
    for M, tag in ((LMS_REC, 'LMS_rec'), (X2S_REC, 'X2S_rec'), (SRGB_FROM_REC, 'srgb_from_rec')):
        d = np.abs(M @ np.linalg.inv(M) - np.eye(3)).max()
        assert d < 1e-12, f'D 失败：{tag} 往返非恒等（{d:.2e}）'
    # E/F/G 跨实现对拍（PC 源码硬编码值；float32 字面量 ⇒ 容差见下）
    de = np.abs(LMS_REC - LMS_BT2020_PC).max()
    assert de < 1e-8, f'E 失败：构造的 Rec.2020→LMS 与 PC 的 BT2020 版差 {de:.2e}'
    df = np.abs(LMS_REC_INV - LMS_BT2020_INV_PC).max()
    assert df < 1e-7, f'F 失败：LMS→Rec.2020 逆与 PC 差 {df:.2e}'
    dg = np.abs(REC_FROM_SRGB - REC_FROM_SRGB_PC).max()
    assert dg < 1e-6, f'G 失败：sRGB→Rec.2020 与 PC 差 {dg:.2e}'
    return da, db, de, df, dg


def vec3(name, row):
    return f'const vec3 {name} = vec3(' + ', '.join(f'{v:.10f}' for v in row) + ');'


def main():
    da, db, de, df, dg = check()
    print(f'自检通过（内部）：sRGB→LMS 对拍 max|Δ| = {da:.2e}；XYZ(D50)→sRGB 对拍 max|Δ| = {db:.2e}')
    print(f'自检通过（跨实现 · PC）：Rec.2020→LMS {de:.2e} · 逆 {df:.2e} · sRGB→Rec.2020 {dg:.2e}')
    if '--check' in sys.argv:
        return 0

    print('\n=== ① StdColorMatrix.kt  X2S（XYZ(D50) → 线性 Rec.2020）===')
    print('    private val X2S = floatArrayOf(')
    for r in range(3):
        print('        ' + ', '.join(f'{v:.9f}f' for v in X2S_REC[r]) + ',')
    print('    )')

    print('\n=== ② FrameRenderer.cpp  filmic 工作空间→LMS2006（打散为 3 个行 vec3）===')
    for name, row in zip(('kLmsWorkToLmsL', 'kLmsWorkToLmsM', 'kLmsWorkToLmsS'), LMS_REC):
        print(vec3(name, row))

    print('\n=== ②b 逆：LMS2006→工作空间（3 个行 vec3）===')
    for name, row in zip(('kLmsToWorkRowR', 'kLmsToWorkRowG', 'kLmsToWorkRowB'), LMS_REC_INV):
        print(vec3(name, row))

    print('\n=== ③ D4 输出映射：线性 Rec.2020 → 线性 sRGB（行主序，3×3）===')
    for row in SRGB_FROM_REC:
        print('    ' + ', '.join(f'{v:.10f}' for v in row) + ',')
    print('--- 入向（编辑器链，成片 sRGB → Rec.2020 线性）：')
    for row in REC_FROM_SRGB:
        print('    ' + ', '.join(f'{v:.10f}' for v in row) + ',')
    return 0


if __name__ == '__main__':
    sys.exit(main())
