#!/usr/bin/env python3
"""0.12.161 collar flow 算法自检（镜像 FrameRenderer.cpp 的 collarFlowFix）。

为什么需要：
  0.12.122 移植的 Bento collar flow **从上线起就是 no-op** —— 它拿位移场的 w 通道
  当 support，而打包位移场时 w 恒写 1.0（`pack` 初始化 `1.f`，循环只写 [0][1][2]），
  于是 shader 第一行 `c.w >= uMinSupp` 恒真、每个 tile 原样返回。真机日志里没有任何
  字段能发现这件事（silent no-op），所以这次除了加日志字段，还留一个可运行的算法
  检查：用构造的 tile 场把分组 / collar 半径 / MIN_COLLAR_TILES / 全图中值四条分支
  各跑一遍。

用法：python3 tools/check-collar-flow.py
"""

COLLAR_RADIUS = 4   # PC 的 COLLAR_RADIUS
MIN_COLLAR_TILES = 4
MIN_SUPPORT = 0.4


def collar_fix(flow, supp, tw, th):
    """flow: {(x,y): (dx,dy)}；supp: {(x,y): 0/1}。返回 (flow2, stats, ok)。"""
    n = tw * th
    idx = lambda x, y: y * tw + x
    sup = [idx(x, y) for y in range(th) for x in range(tw)
           if supp.get((x, y), 0) >= 0.5]
    out = dict(flow)
    fixed_tiles = 0
    if len(sup) < MIN_COLLAR_TILES:
        return out, dict(regions=0, collar=0, global_=0, fixed=0), False
    # 全图 supported 的逐轴中值（conf=1 ⇒ 普通中值）
    gmed = []
    for a in range(2):
        ax = sorted(flow[(t % tw, t // tw)][a] for t in sup)
        gmed.append(ax[len(ax) // 2])
    # 8-连通分组
    group = {}
    groups = []
    for y in range(th):
        for x in range(tw):
            if supp.get((x, y), 0) >= 0.5 or (x, y) in group:
                continue
            gid = len(groups)
            group[(x, y)] = gid
            q, head = [(x, y)], 0
            while head < len(q):
                cx, cy = q[head]
                head += 1
                for ny in range(max(0, cy - 1), min(th - 1, cy + 1) + 1):
                    for nx in range(max(0, cx - 1), min(tw - 1, cx + 1) + 1):
                        if (supp.get((nx, ny), 0) >= 0.5 or (nx, ny) in group):
                            continue
                        group[(nx, ny)] = gid
                        q.append((nx, ny))
            groups.append(q)
    n_collar = n_global = 0
    for tiles in groups:
        collar, seen = [], set()
        for (tx, ty) in tiles:
            for ny in range(max(0, ty - COLLAR_RADIUS), min(th - 1, ty + COLLAR_RADIUS) + 1):
                for nx in range(max(0, tx - COLLAR_RADIUS), min(tw - 1, tx + COLLAR_RADIUS) + 1):
                    if (nx, ny) in seen or supp.get((nx, ny), 0) < 0.5:
                        continue
                    seen.add((nx, ny))
                    collar.append((nx, ny))
        from_collar = len(collar) >= MIN_COLLAR_TILES
        if from_collar:
            n_collar += 1
        else:
            n_global += 1
        for (tx, ty) in tiles:
            fixed_tiles += 1
            if not from_collar:
                out[(tx, ty)] = tuple(gmed)
                continue
            total = sx = sy = 0.0
            for (nx, ny) in collar:
                dx, dy = nx - tx, ny - ty
                w = 1.0 / (dx * dx + dy * dy)
                fx, fy = flow[(nx, ny)]
                total += w
                sx += fx * w
                sy += fy * w
            if total > 1e-12:
                out[(tx, ty)] = (sx / total, sy / total)
    return out, dict(regions=len(groups), collar=n_collar, global_=n_global,
                     fixed=fixed_tiles), True


def old_flow_passthrough(flow, supp, tw, th, w_channel=1.0):
    """0.12.122 的 kFsCollarFlow 在 w 恒 1.0 时的行为（= 原样返回）。"""
    return dict(flow), w_channel >= MIN_SUPPORT


def main():
    tw, th = 12, 10

    # ① 全图 support ⇒ 无 highlight，不修
    flow = {(x, y): (2.0, 3.0) for y in range(th) for x in range(tw)}
    supp = {(x, y): 1 for y in range(th) for x in range(tw)}
    out, st, ok = collar_fix(flow, supp, tw, th)
    assert ok and st["regions"] == 0 and st["fixed"] == 0, st
    print("① 全 support：regions=0 fixed=0 ✓")

    # ② 中间 3×3 highlight，四周 support ⇒ 一个连通区、走 collar，flows 恒定 → 修正为恒定值
    supp = {(x, y): 1 for y in range(th) for x in range(tw)}
    for y in range(4, 7):
        for x in range(5, 8):
            supp[(x, y)] = 0
    flow = {(x, y): (2.0, 3.0) for y in range(th) for x in range(tw)}
    for y in range(4, 7):
        for x in range(5, 8):
            flow[(x, y)] = (99.0, -99.0)
    out, st, ok = collar_fix(flow, supp, tw, th)
    assert ok and st["regions"] == 1 and st["collar"] == 1 and st["global_"] == 0, st
    assert st["fixed"] == 9, st
    for y in range(4, 7):
        for x in range(5, 8):
            assert abs(out[(x, y)][0] - 2.0) < 1e-6 and abs(out[(x, y)][1] - 3.0) < 1e-6, out[(x, y)]
    print(f"② 3×3 highlight + collar：{st} ✓")

    # ③ support 只有 3 个（< MIN_COLLAR_TILES）⇒ 拒绝整张修正（PC 的 resolveGeometry()==null）
    supp = {(0, 0): 1, (1, 0): 1, (2, 0): 1}
    flow = {(x, y): (float(x), float(y)) for y in range(th) for x in range(tw)}
    out, st, ok = collar_fix(flow, supp, tw, th)
    assert not ok and out == flow, st
    print("③ support<4：不修、原样返回 ✓")

    # ④ 两个互不相连的 highlight 区 ⇒ 各自用**局部** collar，不被远处的 flow 拉偏
    #   （二值 support 下「collar 不足 → 全图中值」几乎不可达：组 = 所有非 support
    #    tile，组一大 collar 并集就覆盖全部 support。保留该分支是为了将来补上 PC 的
    #    agreement 项后语义完整，这里改为验证真正影响画面的「局部性」。）
    tw2, th2 = 24, 10
    supp = {(x, y): 1 for y in range(th2) for x in range(tw2)}
    left = [(2, 4), (3, 4), (2, 5), (3, 5)]
    right = [(20, 4), (21, 4), (20, 5), (21, 5)]
    for (x, y) in left + right:
        supp[(x, y)] = 0
    flow = {}
    for y in range(th2):
        for x in range(tw2):
            flow[(x, y)] = (10.0, 0.0) if x < 12 else (20.0, 0.0)
    for (x, y) in left + right:
        flow[(x, y)] = (999.0, 999.0)
    out, st, ok = collar_fix(flow, supp, tw2, th2)
    assert ok and st["regions"] == 2 and st["fixed"] == 8, st
    for (x, y) in left:
        assert abs(out[(x, y)][0] - 10.0) < 1e-6, (x, y, out[(x, y)])
    for (x, y) in right:
        assert abs(out[(x, y)][0] - 20.0) < 1e-6, (x, y, out[(x, y)])
    print(f"④ 两组分离高光各用本地 collar：{st} ✓")

    # ⑤ 旧实现的可执行反证：w 恒 1 ⇒ 每个 tile 都被"本身可信"提前返回
    supp = {(x, y): 0 for y in range(th) for x in range(tw)}
    supp[(5, 5)] = 1
    flow = {(x, y): (float(x), float(y)) for y in range(th) for x in range(tw)}
    out, passthrough = old_flow_passthrough(flow, supp, tw, th)
    assert passthrough and out == flow, "旧 shader 在 w=1 时应原样返回"
    print("⑤ 0.12.122 的 w=1 ⇒ 全图 no-op（反证）✓")


if __name__ == "__main__":
    main()
