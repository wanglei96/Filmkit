#!/usr/bin/env python3
"""sat-fix 对齐采样（0.12.102）的 1:1 镜像自检。

验证 kFsMfnrSatFix 里 shortAt() 的三条性质，它们直接决定"会不会引入回归/错位"：

  1. d=0 时与旧式 texelFetch(uShort, p) **逐位相等**（旧 bracket 路径传零位移的
     保底性质，也是"只改采样不改数值"的前提）；
  2. 偶数整数位移 d=(2k,2j) 恰好等于取 p+d（相位不翻转、无插值模糊）；
  3. 任意亚像素位移 + 图边界的 4 个 tap 都在图内（linSub 在 0.11.x 修过的
     "负位移 + 边缘 floor(s)=-1 让权重>1 变外推"不能在 sat-fix 里复发）。

镜像对象（逐行对应，改 shader 时同步改这里）：
  FrameRenderer.cpp kFsMfnrSatFix 的 offAt() / shRaw() / shortAt()
  FrameRenderer.cpp kFsMfnrFuse 的 linSub()

用法：python3 tools/check-satfix-align.py   （退出码 0 = 通过）
"""

import random

W, H = 64, 48


def make_img(seed):
    rnd = random.Random(seed)
    return [[rnd.randint(0, 65535) for _ in range(W)] for _ in range(H)]


def sh_raw(img, p):
    x = min(max(p[0], 0), W - 1)
    y = min(max(p[1], 0), H - 1)
    return img[y][x]


def short_at(img, p, d):
    """与 shader 的 shortAt 同式（返回 (值, smax)）。"""
    ph = (p[0] & 1, p[1] & 1)
    gmax = ((W - 1 - ph[0]) * 0.5, (H - 1 - ph[1]) * 0.5)
    sx = min(max((p[0] + d[0] - ph[0]) * 0.5, 0.0), gmax[0])
    sy = min(max((p[1] + d[1] - ph[1]) * 0.5, 0.0), gmax[1])
    s0 = (int(sx // 1), int(sy // 1))
    w = (sx - s0[0], sy - s0[1])
    hi = (ph[0] + ((W - 1 - ph[0]) & ~1), ph[1] + ((H - 1 - ph[1]) & ~1))
    b = (ph[0] + s0[0] * 2, ph[1] + s0[1] * 2)
    taps = [
        (b[0], b[1]),
        (min(b[0] + 2, hi[0]), b[1]),
        (b[0], min(b[1] + 2, hi[1])),
        (min(b[0] + 2, hi[0]), min(b[1] + 2, hi[1])),
    ]
    v00, v10, v01, v11 = (sh_raw(img, t) for t in taps)
    smax = max(v00, v10, v01, v11)
    lo = v00 * (1 - w[0]) + v10 * w[0]
    hi_v = v01 * (1 - w[0]) + v11 * w[0]
    return lo * (1 - w[1]) + hi_v * w[1], smax


def check_identity(img):
    """1) d=0 逐位等于 texelFetch(p)。"""
    for y in range(0, H, 3):
        for x in range(0, W, 3):
            got, _ = short_at(img, (x, y), (0.0, 0.0))
            assert got == float(img[y][x]), f"d=0 mismatch at {(x, y)}: {got} != {img[y][x]}"


def check_even_shift(img):
    """2) 偶数整数位移 = 精确取 p+d。"""
    for (k, j) in ((1, 0), (0, 1), (-1, -2), (2, 3)):
        for y in range(6, H - 8, 5):
            for x in range(6, W - 8, 5):
                d = (2.0 * k, 2.0 * j)
                got, _ = short_at(img, (x, y), d)
                want = float(img[y + 2 * j][x + 2 * k])
                assert got == want, f"even shift {d} at {(x, y)}: {got} != {want}"


def check_bounds(img):
    """3) 任意亚像素位移 + 边界，4 个 tap 都必须落在图内（坐标必为合法采样点）。"""
    for d in ((0.5, 0.5), (-1.5, 2.25), (7.75, -3.5), (-9.0, -9.0), (9.0, 9.0)):
        for y in (0, 1, H - 2, H - 1, H // 2):
            for x in (0, 1, W - 2, W - 1, W // 2):
                ph = (x & 1, y & 1)
                gmax = ((W - 1 - ph[0]) * 0.5, (H - 1 - ph[1]) * 0.5)
                sx = min(max((x + d[0] - ph[0]) * 0.5, 0.0), gmax[0])
                sy = min(max((y + d[1] - ph[1]) * 0.5, 0.0), gmax[1])
                assert 0.0 <= sx <= gmax[0] and 0.0 <= sy <= gmax[1], "s 越界"
                assert 0.0 <= sx - int(sx // 1) <= 1.0, "w 越界"
                # tap 坐标在 [ph, hi] 内 ⇒ 图内
                b = (ph[0] + int(sx // 1) * 2, ph[1] + int(sy // 1) * 2)
                hi = (ph[0] + ((W - 1 - ph[0]) & ~1), ph[1] + ((H - 1 - ph[1]) & ~1))
                assert b[0] <= hi[0] and b[1] <= hi[1], f"tap 越界 {b} > {hi}"
                short_at(img, (x, y), d)  # 内部 clamp 不得抛异常


def main():
    img = make_img(7)
    check_identity(img)
    check_even_shift(img)
    check_bounds(img)
    print("sat-fix 对齐采样自检通过（d=0 逐位 / 偶数位移精确 / 边界 tap 在图内）")


if __name__ == "__main__":
    main()
