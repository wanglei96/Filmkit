#!/usr/bin/env python3
"""微调前后对比评估：重点是**伪影**（art4）而非只看 PSNR。

背景：官方权重在 X300 上产生块状伪影（art4>1，即网络在 4px 尺度增加能量），
这是本次微调要解决的核心问题。loss/PSNR 会掩盖它 —— 所以本脚本以 art4 为主判据。

art4 = band4(输出) / band4(输入)。正确降噪时应 ≤1（去掉噪声）；
>1 表示引入了 4px 结构性伪影。真机 A/B 实测官方权重 nn-on/nn-off 的 4px 比为 1.408。

用法：
  python3 tools/nn/eval_finetune.py --pairs-dir test_temp/burst \
      --finetuned tools/nn/out_ft_real/pmrid_finetuned.pt
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).parent))
from diag_255b import band_rms, flat_sigma, gray, phase_blocks  # noqa: E402
from finetune_pmrid import (CFA_PATTERNS, INP_SCALE, KNN_ANCHOR_S,  # noqa: E402
                            SEM_ORDER, build_net, stack_sem, to_net)
from pmrid_torch import Network, load_official_weights  # noqa: E402


def infer(net, mosaic, pat, cvt, dev, tile=512):
    """整幅 mosaic → 网络输出（分 tile + 16px ramp 融合，与引擎同口径）。"""
    h, w = mosaic.shape[0] // 2, mosaic.shape[1] // 2
    step, ov = tile - 16, 16
    out = np.zeros((h, w, 4), np.float32)
    wt = np.zeros((h, w, 1), np.float32)
    ramp = np.ones((tile, tile, 1), np.float32)
    ramp[:ov, :, 0] *= np.linspace(0, 1, ov, dtype=np.float32)[:, None]
    ramp[-ov:, :, 0] *= np.linspace(0, 1, ov, dtype=np.float32)[::-1][:, None]
    ramp[:, :ov, 0] *= np.linspace(0, 1, ov, dtype=np.float32)[None, :]
    ramp[:, -ov:, 0] *= np.linspace(0, 1, ov, dtype=np.float32)[::-1][None, :]
    for iy in range(0, max(1, -(-h // step))):
        for ix in range(0, max(1, -(-w // step))):
            y0, x0 = iy * step, ix * step
            y1, x1 = min(y0 + tile, h), min(x0 + tile, w)
            sub = mosaic[y0 * 2:y1 * 2, x0 * 2:x1 * 2]
            pl = phase_blocks(sub, pat)
            x = torch.from_numpy(to_net(stack_sem(pl), cvt)[None]).to(dev)
            with torch.no_grad():
                y = net(x).float().cpu().numpy()[0].transpose(1, 2, 0)
            y = y[:y1 - y0, :x1 - x0] / (cvt * INP_SCALE)
            out[y0:y1, x0:x1] += y * ramp[:y1 - y0, :x1 - x0]
            wt[y0:y1, x0:x1] += ramp[:y1 - y0, :x1 - x0]
    return out / np.maximum(wt, 1e-6)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pairs-dir", default="test_temp/burst")
    ap.add_argument("--finetuned", default="tools/nn/out_ft_real/pmrid_finetuned.pt")
    ap.add_argument("--cfa-pattern", default="GRBG")
    ap.add_argument("--device", default="mps")
    ap.add_argument("--crop", type=int, default=512, help="评估用中心裁剪半径（packed px）")
    ap.add_argument("--out", default="tools/nn/out")
    args = ap.parse_args()
    pat = CFA_PATTERNS[args.cfa_pattern]
    dev = torch.device(args.device)
    outd = Path(args.out); outd.mkdir(parents=True, exist_ok=True)

    off = load_official_weights().to(dev).eval()
    ft = Network().to(dev)
    ft.load_state_dict(torch.load(args.finetuned, map_location=dev))
    ft.eval()

    print(f"{'场景':<9}{'cvt_k':>7}{'输入σ':>8} | "
          f"{'官方σ':>7}{'微调σ':>7} | {'ret2':>7}{'ret4':>7}{'ret8':>7} | "
          f"{'官方art4':>9}{'微调art4':>9}   （ret*=相对 clean 的保留率）")
    rows = []
    for nf in sorted(Path(args.pairs_dir).glob("*_noisy.npy")):
        mp = nf.with_name(nf.name.replace("_noisy.npy", "_meta.json"))
        cf = nf.with_name(nf.name.replace("_noisy.npy", "_clean.npy"))
        if not mp.exists() or not cf.exists():
            continue
        s = json.loads(mp.read_text())["s_g"]
        cvt = float(np.clip(KNN_ANCHOR_S * 4.0 / s, 0.25, 16.0))
        m = np.load(nf, mmap_mode="r")
        cma = np.load(cf, mmap_mode="r")
        h, w = m.shape
        cy, cx = h // 2, w // 2
        c = args.crop
        mos = np.asarray(m[cy - c:cy + c, cx - c:cx + c], dtype=np.float32)
        mcs = np.asarray(cma[cy - c:cy + c, cx - c:cx + c], dtype=np.float32)
        pl = phase_blocks(mos, pat)
        cl = phase_blocks(mcs, pat)
        inp_g = gray(np.stack([pl[k] for k in SEM_ORDER], -1))
        cl_g = gray(np.stack([cl[k] for k in SEM_ORDER], -1))
        i4 = band_rms(inp_g, 2, 4)
        ref = {r: max(band_rms(cl_g, *b), 1e-9) for r, b in
               (("2", (1, 2)), ("4", (2, 4)), ("8", (4, 8)))}
        res = {}
        for name, net in (("off", off), ("ft", ft)):
            o = infer(net, mos, pat, cvt, dev)
            g = gray(o)
            res[name] = (g, band_rms(g, 2, 4), flat_sigma(g / 255.0) * 255,
                         {r: band_rms(g, *b) for r, b in
                          (("2", (1, 2)), ("4", (2, 4)), ("8", (4, 8)))})
        tag = nf.stem.replace("_noisy", "")
        print(f"{tag:<9}{cvt:>7.2f}{flat_sigma(inp_g/255)*255:>8.2f} | "
              f"{res['off'][2]:>7.2f}{res['ft'][2]:>7.2f} | "
              f"{res['off'][3]['2']/ref['2']:>7.3f}{res['off'][3]['4']/ref['4']:>7.3f}"
              f"{res['off'][3]['8']/ref['8']:>7.3f} | "
              f"{res['off'][1]/i4:>9.3f}{res['ft'][1]/i4:>9.3f}")
        rows.append((tag, res['off'][1] / i4, res['ft'][1] / i4))
        from PIL import Image
        def nm(x):
            p1, p2 = np.percentile(x, [1, 99])
            return np.clip((x - p1) / max(p2 - p1, 1e-6) * 255, 0, 255).astype(np.uint8)
        st = np.concatenate([nm(inp_g), nm(res['off'][0]), nm(res['ft'][0])], axis=1)
        Image.fromarray(st).save(outd / f"ftcmp_{tag}.png")
    if rows:
        a = np.mean([r[1] for r in rows]); b = np.mean([r[2] for r in rows])
        print(f"\n平均 art4：官方 {a:.3f} → 微调 {b:.3f}  "
              f"({'改善' if b < a else '变差'} {abs(b-a)/a*100:.1f}%)")
    print(f"→ 三联对比图（左输入/中官方/右微调）{outd}/ftcmp_*.png")


if __name__ == "__main__":
    main()
