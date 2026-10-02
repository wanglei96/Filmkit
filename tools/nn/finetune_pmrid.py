#!/usr/bin/env python3
"""PMRID 微调（合成数据 / 真机 burst 两条数据路径）；tile=512 与推理一致。

口径（与引擎 FrameRenderer::runNnPass 逐项对齐，任一项写错整轮微调作废）：
  · 输入 = img01 × cvt_k × 256，img01 = clip((raw-black)/(white-black), 0, 1)（WB 前，
    与 NoiseModel 的 S/O 同域；融合路径在 shader 里除回 WB，故两边一致）
  · cvt_k = clamp(S_train·neff/S_sensor, 0.25, 16)，S_train = kNnAnchorS = 1.0093e-3
  · 通道序 = 官方语义 (R, Gr, Gb, B)，packed 半分辨率、不翻转（GRBG 直取相位）
  · noisy 必须是 neff 帧均值的噪声水平（σ² = S·x/neff + O/neff），clean 用另 M 帧均值，
    两组帧不重叠 —— 与推理时 NN 吃 MFNR 融合图的条件一致
  · loss = |pred-label|.mean() / (cvt_k·256)（官方 get_loss_l1 的 norm_k 口径）

自检:  python3 tools/nn/finetune_pmrid.py --synth --steps 20
lr 试探: python3 tools/nn/finetune_pmrid.py --synth --lr-sweep
真机数据: python3 tools/nn/finetune_pmrid.py --dng-dir <burst 目录> --s-sensor <S>
            --epochs 30 --steps-per-epoch 500 --lr 3e-5
权重→TFLite: python3 tools/nn/finetune_pmrid.py --steps 0 --init <out>/pmrid_finetuned.pt
            --export-onnx --out <out>          # 生成 .onnx + .onnx.data
          python3 tools/nn/convert_pmrid.py --onnx <out>/pmrid_finetuned.onnx --out <out2>
"""
import argparse
import contextlib
import io
import json
import sys
import time
from collections import OrderedDict
from pathlib import Path

import numpy as np
import rawpy
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from diag_255b import band_rms, flat_sigma, gray, phase_blocks, synth_noise  # noqa: E402
from pmrid_torch import Network, load_official_weights  # noqa: E402

SEM_ORDER = ["R", "Gr", "Gb", "B"]      # 官方语义通道序（勿改）
KNN_ANCHOR_S = 1.0093e-3                # 官方训练 anchor shot 系数（img01 域）
INP_SCALE = 256.0                       # 官方 inp_scale


# ── DNG 按 patch 读取 ────────────────────────────────────────────────────
class Dng:
    """一个 DNG 的 patch 读取器。句柄常驻时整帧 uint16 驻留（libraw 解码必然整帧），
    但永不展开整幅 float 图 —— 取 patch 后才 float 化并打包。"""

    def __init__(self, path):
        self.path = str(path)
        with rawpy.imread(self.path) as r:
            self.h, self.w = (int(v) for v in r.raw_image_visible.shape)
            self.pat = r.raw_pattern.tolist()
            bl = np.array(r.black_level_per_channel, dtype=np.float32)
            self.white = float(r.white_level)
        # 黑阶按 CFA 相位取（pattern[y][x] → 该通道黑阶）
        self.bl = np.array([[float(bl[self.pat[y][x]]) for x in range(2)]
                            for y in range(2)], dtype=np.float32)
        self.denom = max(self.white - float(np.median(self.bl)), 1.0)
        self.ph, self.pw = self.h // 2, self.w // 2   # packed 尺寸
        self._r = None

    def _raw(self):
        if self._r is None:
            self._r = rawpy.imread(self.path)
        return self._r

    def close(self):
        if self._r is not None:
            self._r.close()
            self._r = None

    def patch(self, y0, x0, n):
        """(y0,x0) 为 RAW 偶数原点，n 为 RAW 边长（偶）→ 4 相位平面各 n/2 见方。"""
        b = np.asarray(self._raw().raw_image_visible[y0:y0 + n, x0:x0 + n],
                       dtype=np.float32)
        blf = np.tile(self.bl, (n // 2, n // 2))
        b01 = np.clip((b - blf) / self.denom, 0.0, 1.0)
        return phase_blocks(b01, self.pat)


_POOL: "OrderedDict[str, Dng]" = OrderedDict()


def dng(path, cache=3):
    """按路径复用已解码的 DNG（LRU，避免每步重开 25MB 文件）。"""
    key = str(path)
    d = _POOL.get(key)
    if d is None:
        d = Dng(path)
        _POOL[key] = d
    _POOL.move_to_end(key)
    while len(_POOL) > max(1, cache):
        _POOL.popitem(last=False)[1].close()
    return d


def stack_sem(planes):
    """相位平面 dict → (h, w, 4) 官方语义序数组。"""
    return np.stack([planes[c] for c in SEM_ORDER], -1).clip(0.0, 1.0)


def to_net(packed, cvt_k):
    """(h,w,4) img01 → (4,h,w) 网络域（× cvt_k × 256）。"""
    return np.ascontiguousarray((packed * (cvt_k * INP_SCALE)).transpose(2, 0, 1),
                                dtype=np.float32)


# ── 训练对采样 ──────────────────────────────────────────────────────────
class SynthPairs:
    """noisy = clean(DNG 近似) + 泊松-高斯，σ² = (S/neff)·x + O/neff。

    合成模式只校验管线；clean 用的是单帧 DNG（自带噪声），故 loss 地板 ≠ 0，
    权重最终仍需真机 burst 微调。
    """

    def __init__(self, dngs, tile, neff, s_sensor, o_sensor, cache, holdout_rows):
        self.dngs, self.tile, self.cache = [Path(p) for p in dngs], tile, cache
        self.A, self.Bn = s_sensor / neff, o_sensor / neff
        self.holdout = {}
        if len(self.dngs) > 1:
            print(f"[warn] 合成模式给了 {len(self.dngs)} 个 DNG，全部按同一 S/O 处理"
                  f"（多 ISO 请分开跑）")
        for p in self.dngs:
            ph = dng(p, cache).ph
            self.holdout[p] = max(tile, min(holdout_rows or tile, ph - tile))

    def train_pos(self, p, rng):
        ph = dng(p, self.cache).ph
        ph = max(self.tile, ph - self.holdout[p])
        return int(rng.integers(0, ph - self.tile + 1)), self._x(p, rng)

    def _x(self, p, rng):
        pw = dng(p, self.cache).pw
        return int(rng.integers(0, pw - self.tile + 1))

    def sample(self, rng):
        p = self.dngs[int(rng.integers(len(self.dngs)))]
        y0, x0 = self.train_pos(p, rng)
        clean = dng(p, self.cache).patch(y0 * 2, x0 * 2, self.tile * 2)
        noisy = {k: synth_noise(v, self.A, self.Bn, rng) for k, v in clean.items()}
        return noisy, clean


class BurstPairs:
    """真机 burst：每个子目录 = 一个场景；排序后前 --noisy-frames 帧均值 = noisy，
    接着 --clean-frames 帧均值 = clean（两组不重叠）。帧需同场景同构图（脚架/短曝光串）。"""

    def __init__(self, root, n_noisy, n_clean, tile, cache, holdout_rows, per_visit):
        self.scenes = []
        root = Path(root)
        subs = sorted(d for d in root.iterdir() if d.is_dir())
        for d in (subs or [root]):
            frames = sorted(d.glob("*.dng")) + sorted(d.glob("*.DNG"))
            need = n_noisy + n_clean
            if len(frames) < need:
                print(f"[warn] {d} 只有 {len(frames)} 帧，需要 {need}（跳过）")
                continue
            self.scenes.append((frames[:n_noisy], frames[n_noisy:need]))
        if not self.scenes:
            sys.exit(f"{root} 下没有可用 burst（每个场景需 ≥ noisy+clean 帧）")
        self.tile, self.cache, self.per_visit = tile, cache, per_visit
        self.holdout = {}
        for nf, cf in self.scenes:
            for p in nf + cf:
                d = dng(p, cache)
                self.holdout[p] = max(tile, min(holdout_rows or tile, d.ph - tile))
        self._buf = []
        self.n_frames = (n_noisy, n_clean)

    def _mean_frames(self, frames, y0, x0):
        acc = None
        for p in frames:                      # 顺序逐个打开 → 常驻句柄数由 cache 控制
            pl = dng(p, self.cache).patch(y0 * 2, x0 * 2, self.tile * 2)
            acc = pl if acc is None else {k: acc[k] + pl[k] for k in pl}
        return {k: v / len(frames) for k, v in acc.items()}

    def _refill(self, rng):
        nf, cf = self.scenes[int(rng.integers(len(self.scenes)))]
        d = dng(nf[0], self.cache)
        lim = max(self.tile, d.ph - self.holdout[nf[0]])
        for _ in range(self.per_visit):
            y0 = int(rng.integers(0, lim - self.tile + 1))
            x0 = int(rng.integers(0, d.pw - self.tile + 1))
            self._buf.append((self._mean_frames(nf, y0, x0),
                              self._mean_frames(cf, y0, x0)))

    def sample(self, rng):
        if not self._buf:
            self._refill(rng)
        return self._buf.pop()


def eval_pairs(sampler, is_synth, rng, n, tile, cache):
    """固定留出 patch（每场景底部 holdout 行内取；噪声一次性生成、前后复用）。"""
    out = []
    if is_synth:
        for p in sampler.dngs:
            d = dng(p, cache)
            hold = sampler.holdout[p]
            for _ in range(n):
                y0 = int(rng.integers(d.ph - hold, d.ph - tile + 1))
                x0 = int(rng.integers(0, d.pw - tile + 1))
                clean = d.patch(y0 * 2, x0 * 2, tile * 2)
                noisy = {k: synth_noise(v, sampler.A, sampler.Bn, rng)
                         for k, v in clean.items()}
                out.append((noisy, clean))
    else:
        for nf, cf in sampler.scenes:
            d = dng(nf[0], cache)
            hold = sampler.holdout[nf[0]]
            for _ in range(n):
                y0 = int(rng.integers(d.ph - hold, d.ph - tile + 1))
                x0 = int(rng.integers(0, d.pw - tile + 1))
                out.append((sampler._mean_frames(nf, y0, x0),
                            sampler._mean_frames(cf, y0, x0)))
    return out


# ── 评测 / 训练 ─────────────────────────────────────────────────────────
def metrics(pred01, noisy01, clean01):
    """口径：四通道均值 gray×255（同 diag_255b.gray）；
    PSNR=10log10(255²/MSE)；ret4 = 4px 带通保留率 band_rms(·,2,4) 相对 clean；
    σ = 去梯度最平坦 10% 块 std（0-255）。clean 自身含噪 ⇒ ret4 仅作相对比较。"""
    gp, gn, gc = (gray(x) for x in (pred01, noisy01, clean01))  # 输入已是 (h,w,4)
    c4 = max(band_rms(gc, 2, 4), 1e-9)
    psnr = 10 * np.log10(255.0 ** 2 / max(float(((gp - gc) ** 2).mean()), 1e-12))
    return dict(psnr=float(psnr), ret4=float(band_rms(gp, 2, 4) / c4),
                sig=float(flat_sigma(gp / 255.0) * 255.0),
                in_psnr=float(10 * np.log10(255.0 ** 2 /
                                            max(float(((gn - gc) ** 2).mean()), 1e-12))),
                in_ret4=float(band_rms(gn, 2, 4) / c4),
                in_sig=float(flat_sigma(gn / 255.0) * 255.0))


def evaluate(net, pairs, cvt, dev):
    net.eval()
    agg = {}
    for noisy, clean in pairs:
        x = torch.from_numpy(to_net(stack_sem(noisy), cvt)[None]).to(dev)
        with torch.no_grad():
            y = net(x).float().cpu().numpy()[0].transpose(1, 2, 0)
        y01 = np.clip(y / (cvt * INP_SCALE), 0.0, 1.0)
        m = metrics(y01, stack_sem(noisy), stack_sem(clean))
        m["l1"] = float(np.abs(y01 - stack_sem(clean)).mean())  # img01 MAE = loss 口径
        for k, v in m.items():
            agg[k] = agg.get(k, 0.0) + v / len(pairs)
    net.train()
    return agg


def sync(dev):
    if dev.type == "mps":
        torch.mps.synchronize()


def build_net(dev, init=None, verbose=True):
    if init:
        net = Network()
        sd = torch.load(init, map_location="cpu", weights_only=True)
        sd = sd.get("state_dict", sd) if isinstance(sd, dict) else sd
        net.load_state_dict(sd)
        if verbose:
            print(f"[init] 从 {init} 载入微调权重")
    else:
        with contextlib.redirect_stdout(io.StringIO()):   # 吞掉 141 行映射表
            net = load_official_weights()
        if verbose:
            print("[init] 官方权重（tools/nn/out/pmrid_512.onnx）")
    return net.to(dev)


def export_onnx(net, outdir, tile):
    """权重 → 固定 [1,4,tile,tile] ONNX（.onnx + .onnx.data 同目录），接 convert_pmrid.py。"""
    onnx_path = outdir / "pmrid_finetuned.onnx"
    exp = Network()
    exp.load_state_dict({k2: v.detach().cpu() for k2, v in net.state_dict().items()})
    exp.eval()
    torch.onnx.export(exp, torch.randn(1, 4, tile, tile), str(onnx_path),
                      opset_version=17, input_names=["input"], output_names=["output"],
                      do_constant_folding=True)
    print(f"[ONNX] {onnx_path}（+ .onnx.data）→ 转 TFLite：\n"
          f"  python3 tools/nn/convert_pmrid.py --onnx {onnx_path} --out <tflite 输出目录>")


def train(net, sampler, args, cvt, dev, steps, lr, log=True):
    opt = torch.optim.Adam(net.parameters(), lr=lr)
    rng = np.random.default_rng(args.seed)
    net.train()
    losses, times = [], []
    t_start = time.perf_counter()
    for step in range(steps):
        nb, cb = [], []
        for _ in range(args.batch):
            n, c = sampler.sample(rng)
            nb.append(to_net(stack_sem(n), cvt))
            cb.append(to_net(stack_sem(c), cvt))
        x = torch.from_numpy(np.stack(nb)).to(dev)
        y = torch.from_numpy(np.stack(cb)).to(dev)
        t0 = time.perf_counter()
        pred = net(x)
        loss = (pred - y).abs().mean() / (cvt * INP_SCALE)   # 官方 get_loss_l1 口径
        loss.backward()
        opt.step()
        opt.zero_grad(set_to_none=True)
        sync(dev)
        dt = time.perf_counter() - t0
        losses.append(float(loss.detach().cpu()))
        times.append(dt)
        if log and (step % args._log_every == 0 or step == steps - 1):
            mem = (f" {torch.mps.driver_allocated_memory() / 2**20:.0f}MiB"
                   if dev.type == "mps" else "")
            print(f"  step {step + 1:4d}/{steps}  loss={losses[-1]:.6f}  "
                  f"{dt * 1e3:5.0f}ms{mem}")
    return losses, times, time.perf_counter() - t_start


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--synth", action="store_true", help="合成模式（无 --dng-dir 时自动）")
    ap.add_argument("--dng", nargs="+", default=["test_temp/FILMKIT_20261002-124812.dng"],
                    help="合成模式源 DNG")
    ap.add_argument("--dng-dir", default=None, help="真机 burst 目录（子目录=场景）")
    ap.add_argument("--noisy-frames", type=int, default=4, help="noisy 平均帧数（=推理 neff）")
    ap.add_argument("--clean-frames", type=int, default=12, help="clean 平均帧数（与 noisy 不重叠）")
    ap.add_argument("--s-sensor", type=float, default=9.635e-4,
                    help="单帧 shot 系数（img01 域，WB 前）；真机模式必须按 ISO 给对")
    ap.add_argument("--o-sensor", type=float, default=3.9e-7, help="单帧 read 方差")
    ap.add_argument("--s-train", type=float, default=KNN_ANCHOR_S, help="训练 anchor S")
    ap.add_argument("--neff", type=float, default=None, help="有效帧数（默认= --noisy-frames）")
    ap.add_argument("--cvt-k", type=float, default=None, help="直接指定 cvt_k（跳过公式）")
    ap.add_argument("--tile", type=int, default=512, help="packed tile（= 推理 tile，勿改）")
    ap.add_argument("--batch", type=int, default=1)
    ap.add_argument("--lr", type=float, default=1e-4, help="自检 20 步用 1e-4；真机建议 3e-5 起")
    ap.add_argument("--epochs", type=int, default=10)
    ap.add_argument("--steps-per-epoch", type=int, default=200)
    ap.add_argument("--steps", type=int, default=-1,
                    help="总步数（≥0 覆盖 epochs×steps-per-epoch；0 = 不训练，配合 --init/--export-onnx）")
    ap.add_argument("--holdout-rows", type=int, default=None,
                    help="底部留出的 packed 行数（默认 = tile）")
    ap.add_argument("--eval-patches", type=int, default=4, help="留出 patch 数")
    ap.add_argument("--dng-cache", type=int, default=3, help="常驻已解码 DNG 数（LRU）")
    ap.add_argument("--patches-per-visit", type=int, default=8,
                    help="真机模式每次开文件取多少个 patch")
    ap.add_argument("--device", default="mps" if torch.backends.mps.is_available() else "cpu")
    ap.add_argument("--init", default=None, help="从已有 .pt 继续（默认官方权重）")
    ap.add_argument("--out", default="tools/nn/out")
    ap.add_argument("--seed", type=int, default=20261002)
    ap.add_argument("--lr-sweep", action="store_true", help="只做 lr 试探，不保存")
    ap.add_argument("--export-onnx", action="store_true", help="结束时导出 ONNX（喂 TFLite 链路）")
    args = ap.parse_args()
    args._log_every = 1

    synth = args.synth or args.dng_dir is None
    dev = torch.device(args.device)
    torch.manual_seed(args.seed)
    outdir = Path(args.out)
    tile = args.tile
    neff = args.neff if args.neff is not None else float(args.noisy_frames)
    cvt = args.cvt_k if args.cvt_k is not None else float(
        np.clip(args.s_train * neff / max(args.s_sensor, 1e-12), 0.25, 16.0))
    holdout = args.holdout_rows

    print(f"[模式] {'合成（DNG 近似 clean + 泊松-高斯噪声）' if synth else '真机 burst'}"
          f"  device={dev}  tile={tile}(packed)")
    print(f"[口径] cvt_k={cvt:.4f} = clamp(S_train·neff/S_sensor) = "
          f"{args.s_train:.4e}·{neff:g}/{args.s_sensor:.4e}；"
          f"网络输入尺度 cvt_k×256={cvt * INP_SCALE:.1f}；通道序 {SEM_ORDER} 不翻转")
    if synth:
        sampler = SynthPairs(args.dng, tile, neff, args.s_sensor, args.o_sensor,
                             args.dng_cache, holdout)
        print(f"[数据] {len(sampler.dngs)} 个 DNG；噪声 σ²=(S/{neff:g})·x+O/{neff:g}"
              f"（等价 {neff:g} 帧平均）")
        print("[warn] 合成模式 clean 用单帧 DNG 近似 ⇒ label 自带单帧噪声（方差是输入的 "
              f"{neff:g}×），loss 最优解偏向\"少降噪/保噪声\"；本模式只验管线，"
              "成品权重必须用真机 burst")
    else:
        sampler = BurstPairs(args.dng_dir, args.noisy_frames, args.clean_frames, tile,
                             args.dng_cache, holdout, args.patches_per_visit)
        print(f"[数据] {len(sampler.scenes)} 场景；noisy={args.noisy_frames} 帧均值、"
              f"clean={args.clean_frames} 帧均值（不重叠）")

    net = build_net(dev, args.init)
    ev = eval_pairs(sampler, synth, np.random.default_rng(args.seed + 1),
                    args.eval_patches, tile, args.dng_cache)
    print(f"[留出] {len(ev)} 个 patch（每场景底部 {holdout or tile} 行内），"
          f"噪声一次性生成，训练前后复用同一批")
    before = evaluate(net, ev, cvt, dev)
    print(f"[训练前] loss={before['l1']:.6f}  PSNR={before['psnr']:.2f}dB  "
          f"ret4={before['ret4']:.3f}  σ={before['sig']:.2f}   "
          f"| 参照 noisy: PSNR={before['in_psnr']:.2f}dB ret4={before['in_ret4']:.3f} "
          f"σ={before['in_sig']:.2f}")

    if args.lr_sweep:
        sweep_steps = max(5, min(20, args.steps if args.steps > 0 else 20))
        print(f"\n[lr 试探] 每档 {sweep_steps} 步；判据 = 同一批留出 patch 的确定性 loss"
              f"（同一数据流，仅 lr 变）")
        print(f"  {'lr':>8} {'留出loss':>10} {'Δ%':>8} {'train loss 前/后':>22} "
              f"{'PSNR':>7} {'σ':>6} {'ms/step':>8}")
        for lr in (0.0, 1e-5, 3e-5, 1e-4, 3e-4, 1e-3):
            net_i = build_net(dev, args.init, verbose=False)
            ls, ts, _ = train(net_i, sampler, args, cvt, dev, sweep_steps, lr, log=False)
            a = evaluate(net_i, ev, cvt, dev)
            h = max(1, len(ls) // 5)
            print(f"  {lr:>8.0e} {a['l1']:>10.6f} "
                  f"{(a['l1'] / before['l1'] - 1) * 100:>+7.2f}% "
                  f"{np.mean(ls[:h]):>10.6f}/{np.mean(ls[-h:]):<10.6f} "
                  f"{a['psnr']:>7.2f} {a['sig']:>6.2f} {np.median(ts) * 1e3:>8.0f}")
        print("→ 取留出 loss 降幅最大且未发散（train loss 不反弹、无 NaN）的 lr")
        return

    total = args.steps if args.steps >= 0 else args.epochs * args.steps_per_epoch
    args._log_every = max(1, total // 25)
    print(f"\n[训练] Adam lr={args.lr:g} batch={args.batch} 总步数={total}"
          f"（{'--steps' if args.steps >= 0 else f'--epochs {args.epochs} × {args.steps_per_epoch}'}）")
    losses, times, elapsed = train(net, sampler, args, cvt, dev, total, args.lr)

    after = evaluate(net, ev, cvt, dev)
    k = min(len(losses), max(3, len(losses) // 5)) if losses else 0
    print(f"\n[训练后] loss={after['l1']:.6f}  PSNR={after['psnr']:.2f}dB  "
          f"ret4={after['ret4']:.3f}  σ={after['sig']:.2f}")
    print(f"[留出 patch] loss {before['l1']:.6f} → {after['l1']:.6f} "
          f"({(after['l1'] / before['l1'] - 1) * 100:+.2f}%) | "
          f"PSNR {before['psnr']:.2f} → {after['psnr']:.2f} dB "
          f"({after['psnr'] - before['psnr']:+.2f}) | "
          f"ret4 {before['ret4']:.3f} → {after['ret4']:.3f} | "
          f"σ {before['sig']:.2f} → {after['sig']:.2f}")
    if losses:
        print(f"[训练 loss] 首步={losses[0]:.6f} 前{k}步均值={np.mean(losses[:k]):.6f} "
              f"→ 末步={losses[-1]:.6f} 后{k}步均值={np.mean(losses[-k:]):.6f} "
              f"({(np.mean(losses[-k:]) / np.mean(losses[:k]) - 1) * 100:+.2f}%) "
              f"min={min(losses):.6f} {'下降 ✓' if np.mean(losses[-k:]) < np.mean(losses[:k]) else '未下降 ✗'}")
        steady = times[max(1, len(times) // 5):] or times
        print(f"[性能] 总 {elapsed:.1f}s  稳态 {np.median(steady) * 1e3:.0f} ms/step"
              f"（首步 {times[0] * 1e3:.0f}ms 含图构建）"
              + (f"  驱动分配 {torch.mps.driver_allocated_memory() / 2**20:.0f} MiB"
                 f"  活跃 {torch.mps.current_allocated_memory() / 2**20:.0f} MiB"
                 if dev.type == "mps" else ""))

    outdir.mkdir(parents=True, exist_ok=True)
    pt = outdir / "pmrid_finetuned.pt"
    if total == 0:
        # --steps 0 = 只加载/评测/导出：别用"没训练过"的 meta 覆盖已有权重
        print("[保存] --steps 0：不写权重（已有 .pt 保持原样）")
        if args.export_onnx:
            export_onnx(net, outdir, tile)
        return
    torch.save({k2: v.detach().cpu() for k2, v in net.state_dict().items()}, pt)
    meta = dict(steps=total, lr=args.lr, batch=args.batch, tile=tile, cvt_k=cvt,
                s_train=args.s_train, s_sensor=args.s_sensor, o_sensor=args.o_sensor,
                neff=neff, mode="synth" if synth else "burst",
                source=args.dng if synth else args.dng_dir,
                loss_before=before["l1"], loss_after=after["l1"],
                metrics_before=before, metrics_after=after)
    (outdir / "pmrid_finetuned.json").write_text(json.dumps(meta, indent=2))
    print(f"[保存] {pt}（state_dict）+ {outdir / 'pmrid_finetuned.json'}")

    if args.export_onnx:
        export_onnx(net, outdir, tile)


if __name__ == "__main__":
    main()
