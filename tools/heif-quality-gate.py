#!/usr/bin/env python3
"""HEIF 交付的**质量判据**（卡 FIX4 判据 3 的可执行部分）。

背景：真机 `FILMKIT_20261015-163849.heic` = 198 KB，同场景 JPEG = 5.1 MB ⇒ 1/25。
逐像素比过：影调无偏（p50 164.63 vs 164.65），但**高频只剩 JPEG 的 30%** ⇒ 压过头。
这类"看着正常、细节被抹平"的事故事前没判据、事后只能肉眼 ⇒ 本脚本把三条判据固化：

  ① 体积比    ：HEIF / 同场景 JPEG ∈ [0.40, 0.60]（<0.40 压过头，>0.60 HEIF 白做）
  ② 高频能量比：HEIF / JPEG ≥ 0.70（分**平滑区**与**细节区**两块分别报，
                 平滑区天然低 ⇒ 只看细节区；抹平事故在细节区最明显）
  ③ 影调偏差  ：两者亮度分位数（p1/p50/p99）差 ≤ 1 LSB

为什么用"高频能量比"而不是 PSNR/SSIM：压过头时 PSNR 仍能很高（低频全对），
SSIM 对结构变化也不敏感（v0.10.54 事故：两个指标都说没退化，肉眼明显抹平）。
带通能量（本仓 `texture-fidelity-eval.py` 同口径）对"细纹被抹平"最敏感。

用法：
    python3 tools/heif-quality-gate.py jpeg=参考.jpg heif=待判.heic [.png...]
    python3 tools/heif-quality-gate.py --selftest          # 判据自身的变异自检

HEIC：macOS 上 PIL 读不了，脚本自动用 `sips` 转 PNG 再读（不修改原文件）。
退出码 0 = 三条全 PASS；1 = 至少一条 FAIL；2 = 用法/输入错。
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image

LUMA = np.array([0.2126, 0.7152, 0.0722], np.float32)

# 判据阈值（与 HeifQuality.kt 的常量**同源**；改一处必须改两处）
RATIO_MIN, RATIO_MAX = 0.40, 0.60
HF_MIN = 0.70          # 细节区高频能量比下界
TONE_MAX_LSB = 1.0     # 影调分位数偏差上界（LSB）

# 分块与选块口径（沿用 tools/texture-fidelity-eval.py）
BLK = 32
GRAD_THR = 5.0         # 参照认定的"细节块"：低频梯度 > 该值


def load_gray(path: str | Path) -> np.ndarray:
    """读图 → 亮度 float32（HEIC 自动用 sips 转 PNG）。"""
    p = Path(path)
    if p.suffix.lower() in (".heic", ".heif"):
        with tempfile.TemporaryDirectory() as td:
            png = Path(td) / "x.png"
            r = subprocess.run(
                ["sips", "-s", "format", "png", "--out", str(png), str(p)],
                capture_output=True, text=True,
            )
            if r.returncode != 0 or not png.exists():
                sys.exit(f"FAIL: sips 转不了 {p}：{r.stderr.strip()}")
            return _gray(png)
    return _gray(p)


def _gray(p: Path) -> np.ndarray:
    return np.asarray(Image.open(p).convert("RGB"), np.float32) @ LUMA


def band(y: np.ndarray, s: int) -> np.ndarray:
    """s 像素尺度带通（box 下采样再上采样相减）—— 与 texture-fidelity-eval 同口径。"""
    lo = np.asarray(
        Image.fromarray(y).resize((max(1, y.shape[1] // s), max(1, y.shape[0] // s)), Image.BOX)
        .resize(y.shape[::-1], Image.BILINEAR)
    )
    return y - lo


def detail_mask(ref: np.ndarray) -> tuple[np.ndarray, int, int]:
    """用**参照**的低频梯度选细节块（自证陷阱：用待判图选会"噪声大=到处是细节"）。"""
    h, w = ref.shape
    bh, bw = h // BLK, w // BLK
    yc = ref[: bh * BLK, : bw * BLK].reshape(bh, BLK, bw, BLK).mean(axis=(1, 3))
    gy, gx = np.gradient(yc)
    return np.hypot(gy, gx) > GRAD_THR, bh, bw


def block_hf(y: np.ndarray, mask: np.ndarray, bh: int, bw: int, s: int = 4) -> float:
    """细节块内 s 像素带通能量的均值（4px：细纹所在尺度，8px 会漏检，见 v0.10.55）。"""
    d = band(y, s)
    vals = [d[i * BLK:(i + 1) * BLK, j * BLK:(j + 1) * BLK].std()
            for i in range(bh) for j in range(bw) if mask[i, j]]
    return float(np.mean(vals)) if vals else 0.0


def tone_lsb(a: np.ndarray, b: np.ndarray) -> dict[str, float]:
    """亮度分位数差（LSB；两图同为 8bit 量级 ⇒ 直接比码值）。"""
    out = {}
    for q in (1, 50, 99):
        out[f"p{q}"] = float(abs(np.percentile(a, q) - np.percentile(b, q)))
    return out


def gate(jpeg: np.ndarray, heif: np.ndarray, jpeg_bytes: int, heif_bytes: int) -> dict:
    """跑三条判据，返回结构化结果。"""
    if jpeg.shape != heif.shape:
        sys.exit(f"FAIL: 尺寸不同 {jpeg.shape} vs {heif.shape}（必须同场景同尺寸）")
    mask, bh, bw = detail_mask(jpeg)
    ratio = heif_bytes / jpeg_bytes
    hf_j = block_hf(jpeg, mask, bh, bw)
    hf_h = block_hf(heif, mask, bh, bw)
    hf_ratio = hf_h / hf_j if hf_j > 0 else 0.0
    tone = tone_lsb(jpeg, heif)
    res = {
        "size": jpeg.shape[::-1],           # (w, h)
        "jpeg_bytes": jpeg_bytes,
        "heif_bytes": heif_bytes,
        "ratio": ratio,
        "ratio_ok": RATIO_MIN <= ratio <= RATIO_MAX,
        "hf_detail": hf_ratio,
        "hf_ok": hf_ratio >= HF_MIN,
        "hf_smooth": block_hf(heif, ~mask, bh, bw) / max(block_hf(jpeg, ~mask, bh, bw), 1e-6),
        "tone": tone,
        "tone_ok": max(tone.values()) <= TONE_MAX_LSB,
        "blocks": int(mask.sum()),
    }
    res["ok"] = res["ratio_ok"] and res["hf_ok"] and res["tone_ok"]
    return res


def report(tag: str, r: dict) -> None:
    print(f"\n── {tag} ──")
    print(f"  尺寸 {r['size'][0]}x{r['size'][1]}   细节块 {r['blocks']}")
    print(f"  ① 体积比   {r['ratio']:.3f}   ({r['heif_bytes']} / {r['jpeg_bytes']})"
          f"   区间 [{RATIO_MIN}, {RATIO_MAX}]   {'PASS' if r['ratio_ok'] else 'FAIL'}")
    print(f"  ② 高频比   细节区 {r['hf_detail']:.3f}  (平滑区 {r['hf_smooth']:.3f} 参考)"
          f"   下界 {HF_MIN}   {'PASS' if r['hf_ok'] else 'FAIL'}")
    t = r["tone"]
    print(f"  ③ 影调偏差 p1={t['p1']:.2f} p50={t['p50']:.2f} p99={t['p99']:.2f} LSB"
          f"   上界 {TONE_MAX_LSB}   {'PASS' if r['tone_ok'] else 'FAIL'}")


def selftest() -> int:
    """判据自身的变异自检：每条判据**各自**有一条只让它红的变异体，外加未变异全绿。

    为什么逐条隔离（而不是断言三条一起）：体积比与影调是耦合量 —— 抹平高频也会轻微
    挪动分位数（box 缩放的边界效应）⇒ 一条变异同时红两项是**预期**，自检若要求
    "只红一条"反而会假红。这里对每条判据只断言它自己的那一位。
    """
    rng = np.random.default_rng(7)
    h = w = 256
    base = np.linspace(0, 255, w, dtype=np.float32)[None, :].repeat(h, 0)
    fine = (rng.normal(0, 6, (h, w)) * np.sin(np.arange(w) / 3.0)).astype(np.float32)
    ref = np.clip(base + fine, 0, 255)
    mid = int(0.5 * ref.nbytes)        # 体积比 0.5 ⇒ ① 过（在区间内）

    # 变异 A：抹平高频（模拟压过头）⇒ ② 必须 FAIL
    flat = np.asarray(Image.fromarray(ref).resize((w // 8, h // 8), Image.BOX)
                      .resize((w, h), Image.BILINEAR))
    # 变异 B：整体亮度 −3 LSB ⇒ ③ 必须 FAIL
    dark = np.clip(ref - 3.0, 0, 255)
    # 变异 C：体积 5%（远小于 0.40）⇒ ① 必须 FAIL
    tiny = int(0.05 * ref.nbytes)
    # 变异 D：体积 95%（远大于 0.60）⇒ ① 也必须 FAIL（HEIF 白做了）
    fat = int(0.95 * ref.nbytes)

    # (名字, 待判图, 待判体积, 期望的 ①②③)
    cases = [
        ("未变异（①②③ 全 PASS）", ref, mid, (True, True, True)),
        ("A 抹平高频（② 红）", flat, mid, (None, False, None)),
        ("B 亮度偏 3LSB（③ 红）", dark, mid, (None, None, False)),
        ("C 体积 5%（① 红：压过头）", ref, tiny, (False, None, None)),
        ("D 体积 95%（① 红：HEIF 白做）", ref, fat, (False, None, None)),
    ]
    bad = []
    for name, cand, nbytes, want in cases:
        r = gate(ref, cand, ref.nbytes, nbytes)
        got = (r["ratio_ok"], r["hf_ok"], r["tone_ok"])
        for i, wv in enumerate(want):
            if wv is not None and got[i] != wv:
                bad.append(f"{name}: 判据 {i + 1} 期望 {wv} 实得 {got[i]}")
        print(f"  {'OK ' if not any(wv is not None and got[i] != wv for i, wv in enumerate(want)) else 'BAD'}"
              f" {name} → ①②③={got}")

    if bad:
        print("\n自检失败：")
        for b in bad:
            print("  " + b)
        return 1
    print("\n自检通过（三条判据各自会红，且未变异体全绿）")
    return 0


def main() -> int:
    args = sys.argv[1:]
    if args and args[0] == "--selftest":
        return selftest()
    items = []
    for a in args:
        # `tag=path` 形式；不带 `=` 时整串是路径、tag 取文件名主干。
        # ⚠ 别用 rpartition：它返回 (head, sep, tail) —— 把 head 当路径、tail 当标签就正好反了。
        tag, sep, p = a.partition("=")
        if not sep:
            tag, p = Path(a).stem, a
        items.append((tag, p))
    if len(items) < 2:
        print(__doc__)
        return 2
    (ref_tag, ref_path), *rest = items
    print(f"参照 = {ref_tag}（JPEG；细节块与影调分位数都以它为真值）")
    ref = load_gray(ref_path)
    ref_bytes = Path(ref_path).stat().st_size
    rc = 0
    for tag, p in rest:
        r = gate(ref, load_gray(p), ref_bytes, Path(p).stat().st_size)
        report(tag, r)
        if not r["ok"]:
            rc = 1
    print("\n" + ("PASS：三条判据全过（质量接近无损 + 体积仍在 40~60%）" if rc == 0 else
                  "FAIL：至少一条判据未过 —— 见上面各项"))
    return rc


if __name__ == "__main__":
    sys.exit(main())
