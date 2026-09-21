#!/usr/bin/env python3
"""LSC 网格采样公式自检 —— 与 FrameRenderer.cpp 的 lscAt()（kFsRaw / kFsRcdChroma）同式。

为什么需要：网格坐标口径（节点位置、边界 clamp、关闭档）错了，真机上只会看到
"暗角没治好 / 反而加重"，不好定位。这里用合成网格把边界行为逐点核对掉。

用法：python3 tools/check-lsc-mapping.py
"""

import math

COLS, ROWS = 17, 13  # X300 HAL 实测网格（camera.raw.lsc）
W, H = 4080, 3072    # 主摄 RAW 尺寸
CORNER = 6.236       # 实测角落增益（R 通道）


def grid(x, y):
    """合成径向增益场：中心 1.0、四角 CORNER（用于核对采样位置，不是真实数据）。"""
    fx = abs(x / (COLS - 1) - 0.5) * 2.0
    fy = abs(y / (ROWS - 1) - 0.5) * 2.0
    return 1.0 + (CORNER - 1.0) * max(fx, fy) ** 2


def lsc_at(p, size, gsize, lut):
    """复刻 shader：g = (p+0.5)/size * gsize - 0.5，4-tap 双线性 + clamp。"""
    gx = (p[0] + 0.5) / size[0] * gsize[0] - 0.5
    gy = (p[1] + 0.5) / size[1] * gsize[1] - 0.5
    bx, by = math.floor(gx), math.floor(gy)
    tx, ty = gx - bx, gy - by
    mx, my = gsize[0] - 1, gsize[1] - 1

    def t(x, y):
        return lut[min(max(y, 0), my)][min(max(x, 0), mx)]

    c00, c10 = t(bx, by), t(bx + 1, by)
    c01, c11 = t(bx, by + 1), t(bx + 1, by + 1)
    return (c00 * (1 - tx) + c10 * tx) * (1 - ty) + (c01 * (1 - tx) + c11 * tx) * ty


LUT = tuple(tuple(grid(x, y) for x in range(COLS)) for y in range(ROWS))


if __name__ == "__main__":
    # 四角取到网格角落值：口径正确的必要条件（反了会让暗角更重）
    for p in ((0, 0), (W - 1, 0), (0, H - 1), (W - 1, H - 1)):
        v = lsc_at(p, (W, H), (COLS, ROWS), LUT)
        assert abs(v - CORNER) < 0.05, (p, v)
    # 图像中心 = 1.0
    c = lsc_at((W // 2, H // 2), (W, H), (COLS, ROWS), LUT)
    assert abs(c - 1.0) < 0.02, c
    # 关闭档：1×1 全 1 网格 → 任意位置恒等 1.0（A/B 关必须真的无效果）
    ones = ((1.0,),)
    for p in ((0, 0), (W - 1, H - 1), (1234, 567)):
        assert lsc_at(p, (W, H), (1, 1), ones) == 1.0
    # 中心→角落单调不减（低频网格不该出现振荡）
    prev = 0.0
    for i in range(101):
        v = lsc_at((W // 2 + (W // 2 - 1) * i // 100, H // 2), (W, H), (COLS, ROWS), LUT)
        assert v >= prev - 1e-9, (i, v, prev)
        prev = v
    print(f"OK  LSC 采样自检：四角 {CORNER} / 中心 1.0 / 关闭档恒等 / 中心→角落单调")
