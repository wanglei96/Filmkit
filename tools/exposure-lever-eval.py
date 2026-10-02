#!/usr/bin/env python3
"""曝光杠杆离线评估：曝光时间倍数 N × 帧内模糊 d 的净收益（img01 CFA 域）。

模型（先写下，防事后解释）
--------------------------
* 域：img01 = (raw − black)/(white − black)，CFA mosaic 保相位（同 diag_255b.load_b01）。
* 采集「曝光时间 ×N + ISO ÷N」保持输出亮度不变 ⇒ **信号 x 不变，只有噪声变**：
      σ²(x, N) = (s·x + o/N) / N
  N=1 退化为实测标定式 s·x + o；散粒项 ÷N、读出项 ÷N² —— 长曝光的物理收益。
  s/o 取该场景 DNG tag 51041（= Camera2 SENSOR_NOISE_PROFILE）标定值（meta.json）。
* 帧内模糊 = 曝光时间内手抖轨迹的时间积分 ⇒ 对信号卷积长度 d 的线性核
  （逐 CFA 平面 warp，避免混色；d 以 mosaic px 计）。
* clean 参照 c = 同场景 n 帧对齐平均，残余 σ_c² = σ0²/n_eff ⇒ 可合成的最大 N
  受 σ_target > σ_c 约束（n_eff 由 multiframe_clean 日志给出）。

判据（先写下）
--------------
* σ_hi  暗部（c<0.15）高频残差（平面内 3×3 去均值）稳健 std —— 噪声口径
* tex_keep 平面内 lag-2 差分对 clean 同口径差分的**最小二乘投影系数**
        Σ(Δx·Δc)/Σ(Δc²) —— 真实纹理保留率（不相关噪声不抬高分子，故对噪声免疫）
* ssim  OETF(γ2.2) 显示域、8×8 均匀窗 SSIM(vs clean) —— 综合保真
* 决策边界 d*(N)：使 SSIM(N,d) 落到当前曝光基准 SSIM(1,0) 的帧内模糊幅度

用法
----
  python3 tools/exposure-lever-eval.py \
      --clean test_temp/exposure_lever/scene01_clean.npy \
      --meta  test_temp/burst/scene01_meta.json \
      --dng   test_temp/dng/FILMKIT_20261002-132956.dng \
      --n-eff 18 --out test_temp/exposure_lever/scene01_lever.md
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent / "nn"))
from multiframe_clean import cfa_planes, pack_planes, plane_pos  # noqa: E402


# ------------------------------------------------------------------ 基础算子

def box_mean(a, r):
    """半径 r 的 box 均值（边界按 reflect 补齐，够用且无 scipy 依赖）。"""
    p = np.pad(a, r, mode="reflect")
    c = np.cumsum(np.cumsum(p, 0), 1)
    c = np.pad(c, ((1, 0), (1, 0)))
    k = 2 * r + 1
    h, w = a.shape
    s = c[k:k + h, k:k + w] - c[0:h, k:k + w] - c[k:k + h, 0:w] + c[0:h, 0:w]
    return (s / (k * k)).astype(np.float32)


def warp_plane(p, dy, dx):
    """out(y,x)=p(y+dy, x+dx) 双线性（口径同 multiframe_clean.warp）。"""
    h, w = p.shape
    ys = np.arange(h, dtype=np.float32) + np.float32(dy)
    xs = np.arange(w, dtype=np.float32) + np.float32(dx)
    y0f, x0f = np.floor(ys), np.floor(xs)
    wy = (ys - y0f)[:, None]
    wx = (xs - x0f)[None, :]
    y0, x0 = y0f.astype(np.int32), x0f.astype(np.int32)
    yc, xc = np.clip(y0, 0, h - 2), np.clip(x0, 0, w - 2)
    r0, r1 = p[yc], p[yc + 1]
    top = r0[:, xc] + (r0[:, xc + 1] - r0[:, xc]) * wx
    bot = r1[:, xc] + (r1[:, xc + 1] - r1[:, xc]) * wx
    return (top + (bot - top) * wy).astype(np.float32)


def blur_frame(m, pos, d, theta, taps=None):
    """帧内模糊：沿 theta 方向、长度 d（mosaic px）的线性拖影，逐平面 warp。

    taps 随 d 自适应（d=32 时 7 个采样点间隔 5.3px 会混叠）。"""
    if d <= 0:
        return m
    if taps is None:
        taps = max(7, int(np.ceil(d)) + 1)
    out = []
    for p in cfa_planes(m, pos):
        acc = np.zeros_like(p)
        for i in range(taps):
            t = (i / (taps - 1) - 0.5) * d          # 相对中心的偏移（mosaic px）
            acc += warp_plane(p, t * np.sin(theta) / 2.0, t * np.cos(theta) / 2.0)
        out.append(acc / taps)
    return pack_planes(out, pos, m.shape)


def synth(clean, pos, s_map, o_map, N, sigma_c2, rng, d=0.0, theta=0.0):
    """合成「曝光倍数 N、帧内模糊 d」的等效帧（img01 域）。"""
    sig = blur_frame(clean, pos, d, theta)
    var_t = (s_map * sig + o_map / N) / N          # 目标噪声方差
    var_add = np.maximum(var_t - sigma_c2, 0.0)    # clean 自身残余噪声抵扣
    return sig + (rng.standard_normal(sig.shape) * np.sqrt(var_add)).astype(np.float32)


# ------------------------------------------------------------------ 评估口径

def hi_sigma(planes, mask_planes, r=1):
    """暗部高频残差稳健 std（逐平面 3×3 去均值 → MAD×1.4826）。"""
    vals = []
    for p, mk in zip(planes, mask_planes):
        res = (p - box_mean(p, r))[mk]
        if res.size:
            vals.append(1.4826 * np.median(np.abs(res - np.median(res))))
    return float(np.mean(vals)) if vals else float("nan")


def tex_keep(planes, ref_planes, mask_planes, lag=2):
    """lag 差分的最小二乘投影系数（真实纹理保留率，噪声免疫）。"""
    num = den = 0.0
    for p, c, mk in zip(planes, ref_planes, mask_planes):
        dx = (p[:, lag:] - p[:, :-lag])[mk[:, lag:] & mk[:, :-lag]]
        dc = (c[:, lag:] - c[:, :-lag])[mk[:, lag:] & mk[:, :-lag]]
        num += float(np.dot(dx, dc))
        den += float(np.dot(dc, dc))
    return num / den if den > 0 else float("nan")


def oetf(x, gamma=2.2):
    return np.clip(x, 0.0, 1.0) ** (1.0 / gamma)


def ssim_plane(a, b, win=8):
    """均匀窗 SSIM（显示域 [0,1]）。"""
    C1, C2 = 0.01 ** 2, 0.03 ** 2
    r = win // 2
    ma, mb = box_mean(a, r), box_mean(b, r)
    va = box_mean(a * a, r) - ma * ma
    vb = box_mean(b * b, r) - mb * mb
    cov = box_mean(a * b, r) - ma * mb
    s = ((2 * ma * mb + C1) * (2 * cov + C2)) / \
        ((ma * ma + mb * mb + C1) * (va + vb + C2))
    return float(s.mean())


def eval_frame(m, pos, clean_planes, mask_planes):
    pl = cfa_planes(m, pos)
    g = oetf(np.mean([p for p in pl], axis=0))
    cg = oetf(np.mean(clean_planes, axis=0))
    return dict(
        sigma=hi_sigma(pl, mask_planes),
        tex=tex_keep(pl, clean_planes, mask_planes),
        ssim=ssim_plane(g, cg),
    )


# ------------------------------------------------------------------ 主流程

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--clean", required=True, help="clean 参照 npy（img01 mosaic）")
    ap.add_argument("--meta", required=True, help="场景 meta.json（含 s_*/o_*）")
    ap.add_argument("--dng", required=True, help="任取该场景一张 DNG（读 CFA pattern）")
    ap.add_argument("--n-eff", type=float, required=True, help="clean 的有效帧数")
    ap.add_argument("--ns", default="1,2,4,8.66", help="曝光倍数扫描")
    ap.add_argument("--ds", default="0,1,2,4,8,16,32", help="帧内模糊扫描（mosaic px）")
    ap.add_argument("--frames", type=int, default=4, help="融合帧数")
    ap.add_argument("--iters", type=int, default=3, help="每组重复次数（取中位）")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--out", default=None, help="Markdown 报告输出路径")
    args = ap.parse_args()

    clean = np.load(args.clean).astype(np.float32)
    meta = json.load(open(args.meta))
    import rawpy
    with rawpy.imread(args.dng) as raw:
        pat = raw.raw_pattern.tolist()
    pos = plane_pos(pat)
    print(f"clean {clean.shape} pattern {pat} pos {pos} meta iso={meta.get('iso')}")

    h, w = clean.shape
    s_rgb = (meta["s_r"], meta["s_g"], meta["s_b"])
    o_rgb = (meta["o_r"], meta["o_g"], meta["o_b"])
    s_map = np.empty((h, w), np.float32)
    o_map = np.empty((h, w), np.float32)
    for (y, x), sv, ov in zip(pos, (s_rgb[0], s_rgb[1], s_rgb[1], s_rgb[2]),
                              (o_rgb[0], o_rgb[1], o_rgb[1], o_rgb[2])):
        s_map[y::2, x::2] = sv
        o_map[y::2, x::2] = ov

    clean_planes = [np.ascontiguousarray(p) for p in cfa_planes(clean, pos)]
    mask_planes = [p < 0.15 for p in clean_planes]          # 暗部口径
    cov = {k: float(np.mean([m.mean() for m in mask_planes])) for k in ("dark",)}
    print(f"暗部占比 {cov['dark']*100:.1f}%")

    sigma_c2 = (s_map * clean + o_map) / args.n_eff        # clean 残余噪声方差

    def sigma_theory(N):
        """理想融合的暗部噪声解析值：σ² = (s·x + o/N)/N/F（不含 clean 残余相关项）。"""
        var = ((s_map * clean + o_map / N) / N) / args.frames
        vals = np.concatenate([p[m] for p, m in zip(cfa_planes(var, pos), mask_planes)])
        return float(np.sqrt(vals.mean()))
    ns = [float(v) for v in args.ns.split(",")]
    ds = [float(v) for v in args.ds.split(",")]

    rows = []
    for N in ns:
        for d in ds:
            acc = []
            for it in range(args.iters):
                rng = np.random.default_rng(args.seed + 1000 * it + int(N * 100) + int(d * 10))
                frames = [synth(clean, pos, s_map, o_map, N, sigma_c2, rng,
                                d=d, theta=(i * np.pi / args.frames) if d > 0 else 0.0)
                          for i in range(args.frames)]
                fused = np.mean(frames, axis=0).astype(np.float32)
                acc.append(eval_frame(fused, pos, clean_planes, mask_planes))
            rows.append(dict(
                N=N, d=d,
                sigma=float(np.median([a["sigma"] for a in acc])),
                sth=sigma_theory(N),
                tex=float(np.median([a["tex"] for a in acc])),
                ssim=float(np.median([a["ssim"] for a in acc])),
            ))
            print(f"  N={N:<5} d={d:<4} σ={rows[-1]['sigma']:.5f} "
                  f"(解析 {rows[-1]['sth']:.5f}) "
                  f"tex={rows[-1]['tex']:.3f} ssim={rows[-1]['ssim']:.4f}")

    base = next(r for r in rows if r["N"] == ns[0] and r["d"] == ds[0])
    lines = ["| N(曝光×) | 帧内模糊 d(px) | σ_hi | σ_解析 | tex_keep | SSIM | SSIM vs 基准 |",
             "|---|---|---|---|---|---|---|"]
    for r in rows:
        lines.append(f"| {r['N']:g} | {r['d']:g} | {r['sigma']:.5f} | {r['sth']:.5f} | "
                     f"{r['tex']:.3f} | {r['ssim']:.4f} | {r['ssim']-base['ssim']:+.4f} |")
    lines.append("")
    lines.append(f"基准（当前采集 N=1, d=0）：σ={base['sigma']:.5f} tex={base['tex']:.3f} "
                 f"ssim={base['ssim']:.4f}")
    lines.append(f"clean 参照 n_eff={args.n_eff:g} ⇒ 残余 σ_c=σ₀/√{args.n_eff:g}"
                 f"（{1/np.sqrt(args.n_eff):.3f}σ₀）在各融合帧间**相关**，"
                 f"故实测 σ 收益被系统性压低，应以 σ_解析 为准。")
    lines.append("")
    lines.append("**σ 收益（d=0）**：")
    for N in ns:
        if N > 1:
            b = next(r for r in rows if r["N"] == N and r["d"] == 0)
            lines.append(f"  - N={N:g}：实测 {base['sigma']/b['sigma']:.2f}× / "
                         f"解析 {base['sth']/b['sth']:.2f}× （物理上限 √N={np.sqrt(N):.2f}×）")
    lines.append("")
    lines.append("**决策边界 d\\*(N)**（SSIM 跌回基准 SSIM(1,0) 的帧内模糊幅度，线性内插）：")
    for N in ns:
        if N == 1:
            continue
        pts = sorted([r for r in rows if r["N"] == N], key=lambda r: r["d"])
        dstar = None
        for a, b in zip(pts, pts[1:]):
            if (a["ssim"] - base["ssim"]) >= 0 >= (b["ssim"] - base["ssim"]):
                t = (a["ssim"] - base["ssim"]) / ((a["ssim"] - base["ssim"]) - (b["ssim"] - base["ssim"]))
                dstar = a["d"] + t * (b["d"] - a["d"])
                break
        if dstar is None:
            dstar = pts[-1]["d"] if pts[-1]["ssim"] >= base["ssim"] else 0.0
            tag = "（扫描范围内未交叉，取端点）" if pts[-1]["ssim"] >= base["ssim"] else "（起步即劣于基准）"
        else:
            tag = ""
        lines.append(f"  - N={N:g}：d* ≈ {dstar:.2f} px {tag}")

    text = "\n".join(lines)
    print("\n" + text)
    if args.out:
        Path(args.out).write_text(text + "\n", encoding="utf-8")
        print(f"\n→ {args.out}")


if __name__ == "__main__":
    main()
