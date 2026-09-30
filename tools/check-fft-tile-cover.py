#!/usr/bin/env python3
"""守卫 kFsMfnrFft 的 tile 分组 / 层分配：每个 mosaic texel 必须被 4 个相位组层
各写恰好 1 次，且 4 层窗值之和恒 1（overlap-add 的前提）。

为什么需要它：v0.10.50 真机成片出现绿色网格纹 —— shader 的 tile 索引写成
`4·wg.x + 2·gx − 1`（host 端列举是 tx = (gx−1) + 2k），gx=1/gy=1 的组重复处理了
gx=0/gy=0 的 tile 集合、偶数 tile 整批漏写，收口相加时混入未定义内容。这类
映射错误在离线算式镜像（tools/fft-merge-mirror.py）里看不出来，只有把
host 列举 + shader 反推 + 收口相加一起算才暴露。

本脚本是**性质验证**（不复用 C++ 代码）：host 规则与 shader 公式在此各写一份，
改实现后请同步核对；覆盖/窗和不满足即 exit 1。

  python3 tools/check-fft-tile-cover.py            # 当前公式，期望 OK
  python3 tools/check-fft-tile-cover.py --buggy    # 复现 v0.10.50 故障（期望 FAIL）
"""
import sys
import numpy as np

BUGGY = "--buggy" in sys.argv
W, H = 512, 256            # mosaic 尺寸（覆盖性质与 4080×3072 同构）

pw, ph = (W + 1) // 2, (H + 1) // 2
tx_max = (pw + 7) // 8 - 1
ty_max = (ph + 7) // 8 - 1


def host_tiles(limit, g):
    """passMfnrFft 列举：组 g 的 tile = t ∈ [-1, limit] 且 ((t+1)&1) == g。"""
    ts = [t for t in range(-1, limit + 1) if ((t + 1) & 1) == g]
    return ts, (len(ts) + 1) // 2      # x 维每 wg 2 个 tile；y 维每行 1 个


def win(i):
    """修改升余弦窗（kFsMfnrFft 载入时施加）。"""
    return 0.5 - 0.5 * np.cos(2 * np.pi * (i + 0.5) / 16.0)


def layer_map(gx, gy):
    """该层每个 mosaic texel 的窗权累加（正确实现下每 texel 至多 1 次写入）。"""
    acc = np.zeros((H, W), np.float32)
    _, nx = host_tiles(tx_max, gx)
    ty_list, _ = host_tiles(ty_max, gy)
    ny = len(ty_list)
    wx = win(np.arange(16, dtype=np.float32))
    for wgx in range(nx):
        for wgy in range(ny * 4):          # plane 并入 y 维
            plane, wgr = divmod(wgy, ny)
            if BUGGY:
                tx0 = 4 * wgx + 2 * gx - 1
                ty = 2 * wgr + 2 * gy - 1
            else:
                tx0 = 4 * wgx + gx - 1
                ty = 2 * wgr + gy - 1
            ox, oy = plane & 1, plane >> 1
            for tx in (tx0, tx0 + 2):
                if tx > tx_max:
                    continue
                mx, my = 2 * (tx * 8 + np.arange(16)) + ox, 2 * (ty * 8 + np.arange(16)) + oy
                kx, ky = (mx >= 0) & (mx < W), (my >= 0) & (my < H)
                if not kx.any() or not ky.any():
                    continue
                acc[np.ix_(my[ky], mx[kx])] += np.outer(wx[ky], wx[kx])
    return acc


maps = [layer_map(gx, gy) for gy in (0, 1) for gx in (0, 1)]
cnt = sum((m > 0).astype(np.int8) for m in maps)
tot = sum(maps)

print(f"公式 = {'BUGGY (v0.10.50 真机故障)' if BUGGY else '当前实现'}  "
      f"tx_max={tx_max} ty_max={ty_max}")
for n in range(5):
    k = int((cnt == n).sum())
    if k:
        print(f"  被 {n} 层写入: {k} texel ({100.0 * k / (W * H):.2f}%)")

full = cnt == 4
covered, dev = bool(full.all()), 0
if full.any():
    ws = tot[full]
    dev = int((np.abs(ws - 1.0) > 1e-3).sum())
    print(f"  4 层窗和: min={ws.min():.4f} max={ws.max():.4f} "
          f"mean={float(ws.mean()):.4f}（期望恒 1.0，偏离 {dev} 个）")
else:
    print("  无 texel 被 4 层覆盖：收口相加必然混入未定义内容")
passes = covered and dev == 0

if BUGGY:
    print("FAIL（已复现 v0.10.50 故障）" if not passes else "意外通过：buggy 公式本该失败")
    sys.exit(0 if not passes else 1)
print("OK" if passes else "FAIL（覆盖或窗和不满足 —— 收口会混入未定义内容）")
sys.exit(0 if passes else 1)
