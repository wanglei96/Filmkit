#!/usr/bin/env python3
"""E4-3 颗粒：相纸响应 LUT 的 **numpy 独立复算**（darktable src/iop/grain.c 的直译）。

为什么单独成模块：同一份公式有两处消费方，各自重复实现会漂移 ——
  ① tools/check-style-passthrough.py（生成喂给 kFsStyle 的 uGrainLut 纹理）；
  ② tools/grain-exp/grain-exp.py（与 **C++ 侧真输出**逐点对照）。
②的对照对象是 `tools/grain-exp/grain-lut-dump.cpp`（include 引擎自己的 GrainLut.h）
—— 不是"两份 Python 互相印证"。

与 C++ 侧的对照判据：**相对误差 ≤ 1e-5**（不要求逐位 —— 两边的 libm exp/log 实现
不同，ULP 级差异不可避免；1e-5 远小于 LUT 自身 1/127 的采样分辨率）。

用法：
  python3 tools/grain_lut_ref.py            # 自检（解析性质 + pivot 恒等）
依赖：numpy
"""
import numpy as np

LUT_SIZE = 128
DELTA_MAX = 2.0        # GRAIN_LUT_DELTA_MAX
DELTA_MIN = 0.0001     # GRAIN_LUT_DELTA_MIN
PAPER_GAMMA = 1.0      # GRAIN_LUT_PAPER_GAMMA
SCALE_FACTOR = 213.2   # GRAIN_SCALE_FACTOR
LIGHTNESS_STRENGTH_SCALE = 0.15   # GRAIN_LIGHTNESS_STRENGTH_SCALE

F32 = np.float32


def grain_delta(mb):
    """darktable `paper_resp` 内的 delta = 2·exp((mb/100)·ln(0.0001))。"""
    return F32(DELTA_MAX) * np.exp((F32(mb) / F32(100.0)) * np.log(F32(DELTA_MIN)))


def paper_resp(exposure, delta):
    """darktable `paper_resp(exposure, mb, gp)`（delta 由调用方算好）。"""
    one2d = F32(1.0) + F32(2.0) * delta
    return one2d / (F32(1.0) + np.exp(
        (F32(4.0) * F32(PAPER_GAMMA) * (F32(0.5) - exposure)) / one2d)) - delta


def paper_resp_inverse(density, delta):
    """darktable `paper_resp_inverse(density, mb, gp)`（delta 由调用方算好）。"""
    one2d = F32(1.0) + F32(2.0) * delta
    return (-np.log(one2d / (density + delta) - F32(1.0)) * one2d /
            (F32(4.0) * F32(PAPER_GAMMA)) + F32(0.5))


def build_grain_lut(mb=100.0, pivot=0.0):
    """darktable `evaluate_grain_lut` + [61] pivot 折入（与 GrainLut.h 同式）。

    返回 float32 的 (LUT_SIZE, LUT_SIZE) 数组，下标 [j, i]：
      j = 亮度轴 l ∈ [0,1]（= 显示域 luma ≈ Lab L/100）
      i = 噪声轴 gu ∈ [-0.5, 0.5]
    内存布局与 darktable 的 `grain_lut[j*SIZE + i]` 一致（行主序展平即可上传）。
    """
    delta = grain_delta(mb)
    # E4-3b 架构师裁决：指数取**倒数**（规格 §3.1.5 的公式与文字矛盾，以文字"峰值移向暗部"
    # 为准）。与 core/engine/src/render/GrainLut.h 同式 —— 两边必须逐字对应，否则 ① 对照红。
    k = F32(1.0) / (F32(1.0) + F32(3.0) * F32(pivot))
    idx = np.arange(LUT_SIZE, dtype=np.float32)
    l = idx / F32(LUT_SIZE - 1)              # 亮度轴
    gu = l - F32(0.5)                        # 噪声轴（同为 128 点，独立含义）
    # pivot = 0 ⇒ k = 1 ⇒ 短路（与 C++ 侧同一条纪律：不依赖 pow(x,1) 的精度）
    lp = l if float(k) == 1.0 else l ** k
    inv = paper_resp_inverse(lp, delta)      # (LUT_SIZE,)
    # ⚠ 减数是 **lp（pow 之后）而不是 l** —— 见 GrainLut.h 的 ⚠⚠ 段：LUT 的物理含义是
    # "噪声 gu 带来的亮度增量"，`paper_resp(inverse(y)) == y` ⇒ gu=0 时增量恒 0。
    # 若减 l，pivot>0 会让 gu=0 处 LUT = 100·(l'−l) ≠ 0 ⇒ [61] 变成整体亮度旋钮
    #（pivot=0.3 实测全图 −23.5 L 单位 ≈ −60/255）。
    return (F32(100.0) * (paper_resp(gu[None, :] + inv[:, None], delta) - lp[:, None]))


def grain_zoom(iso):
    """[27] ISO → darktable 的 zoom（单调：ISO 越大 ⇒ 颗粒越粗）。"""
    scale = min(max(float(iso), 20.0), 6400.0) / SCALE_FACTOR
    return (1.0 + 8.0 * scale / 100.0) / 800.0


def grain_perm():
    """darktable 的 permutation[256]（simplex 用；此处供离线脚本核对 shader 表）。"""
    return np.array([
        151, 160, 137, 91, 90, 15, 131, 13, 201, 95, 96, 53, 194, 233, 7, 225, 140, 36, 103, 30,
        69, 142, 8, 99, 37, 240, 21, 10, 23, 190, 6, 148, 247, 120, 234, 75, 0, 26, 197, 62,
        94, 252, 219, 203, 117, 35, 11, 32, 57, 177, 33, 88, 237, 149, 56, 87, 174, 20, 125, 136,
        171, 168, 68, 175, 74, 165, 71, 134, 139, 48, 27, 166, 77, 146, 158, 231, 83, 111, 229, 122,
        60, 211, 133, 230, 220, 105, 92, 41, 55, 46, 245, 40, 244, 102, 143, 54, 65, 25, 63, 161,
        1, 216, 80, 73, 209, 76, 132, 187, 208, 89, 18, 169, 200, 196, 135, 130, 116, 188, 159, 86,
        164, 100, 109, 198, 173, 186, 3, 64, 52, 217, 226, 250, 124, 123, 5, 202, 38, 147, 118, 126,
        255, 82, 85, 212, 207, 206, 59, 227, 47, 16, 58, 17, 182, 189, 28, 42, 223, 183, 170, 213,
        119, 248, 152, 2, 44, 154, 163, 70, 221, 153, 101, 155, 167, 43, 172, 9, 129, 22, 39, 253,
        19, 98, 108, 110, 79, 113, 224, 232, 178, 185, 112, 104, 218, 246, 97, 228, 251, 34, 242, 193,
        238, 210, 144, 12, 191, 179, 162, 241, 81, 51, 145, 235, 249, 14, 239, 107, 49, 192, 214, 31,
        181, 199, 106, 157, 184, 84, 204, 176, 115, 121, 50, 45, 127, 4, 150, 254, 138, 236, 205, 93,
        222, 114, 67, 29, 24, 72, 243, 141, 128, 195, 78, 66, 215, 61, 156, 180], dtype=np.int64)


def _selfcheck():
    """解析性质自检（不依赖 C++ 侧；grain-exp.py 才做跨语言对照）。"""
    ok = True
    # ① mb 的两个端点：mb=100 ⇒ δ=2e-4（曲线最陡）；mb=0 ⇒ δ=2（最平）
    d100, d0 = float(grain_delta(100.0)), float(grain_delta(0.0))
    print(f'① delta: mb=100 → {d100:.6g}（期望 2e-4）；mb=0 → {d0:.6g}（期望 2）')
    ok &= abs(d100 - 2e-4) < 1e-9 and abs(d0 - 2.0) < 1e-9
    # ② paper_resp 严格**递增**（相纸响应：曝光↑ ⇒ 密度↑），且 paper_resp(0.5) ≈ 0.5
    e = np.linspace(0.0, 1.0, 1001, dtype=np.float32)
    r = paper_resp(e, grain_delta(100.0))
    mono = bool(np.all(np.diff(r) > 0))
    print(f'② paper_resp 严格递增 = {mono}；paper_resp(0.5) = '
          f'{float(paper_resp(F32(0.5), grain_delta(100.0))):.6f}（≈0.5）')
    ok &= mono and abs(float(paper_resp(F32(0.5), grain_delta(100.0))) - 0.5) < 1e-3
    # ③ 往返：paper_resp_inverse(paper_resp(e)) ≈ e
    rt = paper_resp_inverse(paper_resp(e, grain_delta(100.0)), grain_delta(100.0))
    print(f'③ paper_resp 往返 max|Δ| = {float(np.abs(rt - e).max()):.3g}（判据 <1e-4）')
    ok &= float(np.abs(rt - e).max()) < 1e-4
    # ④ **无系统性亮度偏移**（折入 pivot 后的关键判据）：gu = 0 处 LUT 必须为 0。
    #    gu 的采样点不含精确 0（i/127 − 0.5），故用左右两格之和 ≈ 0（paper_resp 在此
    #    尺度上近似线性，残差 ≤ 1e-3 L 单位）。
    for pv in (0.0, 0.3):
        lut = build_grain_lut(100.0, pv)
        mid = 0.5 * (lut[:, 63] + lut[:, 64])
        print(f'④ pivot={pv}: |gu≈0 处的 LUT| max = {float(np.abs(mid).max()):.2e} L 单位'
              f'（判据 <1e-2 = 无整体亮度偏移）；值域 = '
              f'[{lut.min():.2f}, {lut.max():.2f}]（对称 ⇒ 无偏移）')
        ok &= float(np.abs(mid).max()) < 1e-2
        ok &= abs(float(lut.min()) + float(lut.max())) < 1.0
    # ⑤ 调制峰值位置：pivot=0 在 l≈0.5；pivot>0（k=1/(1+3p)<1）移向**暗部**
    #    （= 规格 §3.1.5 的**文字**意图，E4-3b 架构师裁决；原公式 pow(l,1+3p) 给出的是反向）
    peaks = {}
    for pv in (0.0, 0.15, 0.3):
        col = np.abs(build_grain_lut(100.0, pv)[:, 64])   # gu≈+0.004 列
        peaks[pv] = int(np.argmax(col)) / (LUT_SIZE - 1)
    print(f'⑤ |LUT(gu≈0,·)| 峰值位置：pivot=0 → l={peaks[0.0]:.3f}（期望 ≈0.5）、'
          f'pivot=0.15 → {peaks[0.15]:.3f}、pivot=0.3 → {peaks[0.3]:.3f}'
          f'（k=1/(1+3p)<1 ⇒ 单调移向**暗部**，与规格文字一致）')
    ok &= 0.45 <= peaks[0.0] <= 0.55 and peaks[0.15] < peaks[0.0] and peaks[0.3] < peaks[0.15]
    # ⑥ pivot=0 ⇒ k=1 ⇒ 走短路分支（不调 pow）⇒ 与"不启用 pivot"逐位相同
    k1 = F32(1.0) / (F32(1.0) + F32(3.0) * F32(0.0))
    lut_a = build_grain_lut(100.0, 0.0)
    lut_b = build_grain_lut(100.0, 1e-9)   # k = 1/(1+3e-9) → float32 下 k == 1.0f
    print(f'⑥ pivot=0 ⇒ k={float(k1)}（短路，不调 pow）；'
          f'pivot=1e-9（float32 下 k 仍 == 1.0f）⇒ 与 pivot=0 逐位相同 = '
          f'{bool(np.array_equal(lut_a, lut_b))}')
    ok &= float(k1) == 1.0 and bool(np.array_equal(lut_a, lut_b))
    # ⑦ grain_zoom 单调（ISO 越大越粗）+ clamp 上限
    zs = [grain_zoom(v) for v in (20, 100, 400, 1600, 6400, 1e6)]
    print(f'⑦ grain_zoom: ' + ' '.join(f'{v:g}→{grain_zoom(v):.7f}' for v in (20, 400, 1600, 6400))
          + f'；ISO=1e6 → {zs[-1]:.7f}（= ISO 6400 的值 ⇒ 上限 clamp 生效，'
            f'否则噪声坐标全部落进同一格 = 全图加同一灰度）')
    ok &= all(zs[i] < zs[i + 1] for i in range(4)) and abs(zs[4] - zs[5]) < 1e-12
    print('自检通过' if ok else '自检失败')
    return 0 if ok else 1


if __name__ == '__main__':
    import sys
    sys.exit(_selfcheck())
