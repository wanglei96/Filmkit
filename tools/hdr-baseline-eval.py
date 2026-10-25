#!/usr/bin/env python3
"""HDR 基线测量（P4 前置）：从 JPEG/R（UltraHDR）无损提取 base + gainmap，固化统计口径。

背景：2026-10-20 的"高光顶白"实测（base 平均亮度我们 85.2/98.4 vs PC 50.7、>=240 占比
3.38%/2.22% vs 0.37%）是 inline 做的，口径没有固化；且当时渲染端 AE 还在，0.12.71 已全删。
P4（gainmap 改用跨曝光 HDR 参考）动手前需在同一口径上重测基线，确认差距还剩多少。

解析逻辑逐条对照一手实现：
- MPF：core/engine/third_party/ultrahdr/lib/src/jpegr.cpp `hasValidMpfAssociation`
  （tiff = APP2 payload + "MPF\\0" 之后；secondary 绝对位置 = tiff + dataOffset；
  校验 = 第二张 JPEG 的起始与长度）。
- 元数据：XMP `hdrgm:GainMapMin/Max`（log2 域，boost = exp2）；
  GainMapGamma/BaseRenditionIsHDR/HDRCapacityMin/Max 一并回读。
- 重建：官方 gainmapmath.cpp applyGain（gamma=1、W=1 满档 HDR 显示口径）：
    log_boost = log2(min_boost)*(1-g) + log2(max_boost)*g
    hdr = (sdr_lin + 1e-7) * 2^log_boost - 1e-7     （sdr_lin = 反 OETF(base/255)）

统计口径（与 10-20 账目对齐）：
- base：8bit [0,255] 的 mean / p50 / p95 / p99 / >=240 占比 / >=250 占比
- gainmap：8bit 归一化 [0,1] 的 mean / p90 / p99 / max，及换算的 boost 线性倍数
- HDR 重建 luma（Rec.709，线性域）：p50 / p90 / p99 / >=1.0 占比

用法：
  python3 tools/hdr-baseline-eval.py <jpg>...            # 表格输出
  python3 tools/hdr-baseline-eval.py <jpg>... --json out # 另存 JSON
"""
import argparse
import io
import json
import math
import re
import sys

import numpy as np
from PIL import Image

MPF_SIG = b"MPF\x00"
XMP_SIG = b"http://ns.adobe.com/xap/1.0/\x00"
ISO_SIG = b"urn:iso:std:iso:ts:21496:-1\x00"


# ── JPEG 扫描：所有顶层图像范围（SOI..EOI）与段表 ─────────────────────────────
def scan_jpeg(data):
    """返回 (images, segments)：images=[(begin,end)]，segments=[(marker,payload_begin,payload_end)]。"""
    n = len(data)
    images = []
    segments = []
    i = 0
    while i < n - 1:
        if data[i] != 0xFF or data[i + 1] != 0xD8:  # 只从 SOI 进入
            i += 1
            continue
        j = i + 2
        while j < n - 1:
            if data[j] != 0xFF:
                j += 1
                continue
            m = data[j + 1]
            if m == 0xD9:  # EOI
                images.append((i, j + 2))
                i = j + 2
                break
            if m == 0x01 or 0xD0 <= m <= 0xD7:  # TEM/RST：无长度字段
                j += 2
                continue
            if j + 4 > n:
                return images, segments
            seg_len = int.from_bytes(data[j + 2:j + 4], "big")
            if seg_len < 2:
                return images, segments
            segments.append((m, j + 4, min(j + 2 + seg_len, n)))
            if m == 0xDA:  # SOS：跳过熵编码（FF00 转义、RST 除外）
                j += 2 + seg_len
                while j < n - 1:
                    if data[j] == 0xFF and data[j + 1] != 0x00 and not (0xD0 <= data[j + 1] <= 0xD7):
                        break
                    j += 1
                continue
            j += 2 + seg_len
        else:
            break
    return images, segments


# ── MPF 解析（对照 jpegr.cpp hasValidMpfAssociation）──────────────────────────
def parse_mpf_secondary(data, segments, images):
    """返回 (sec_begin, sec_end) 或 None。primary 段内的 APP2 MPF → secondary 范围。"""
    if len(images) != 2:
        return None
    p0, p1 = images[0]
    for marker, b, e in segments:
        if marker != 0xE2 or b < p0 or e > p1:  # APP2 且落在 primary 内
            continue
        if data[b:b + 4] != MPF_SIG:
            continue
        tiff = b + 4
        # 官方常量：kMpEndianSize=4（"MM\x00*" 合 4B），ifd_offset 在 tiff+4；
        # kMPEntrySize=16（attributes/size/offset/dep 各 u32）
        endian = data[tiff:tiff + 4]
        if endian == b"II\x2a\x00":
            u16 = lambda o: int.from_bytes(data[o:o + 2], "little")
            u32 = lambda o: int.from_bytes(data[o:o + 4], "little")
        elif endian == b"MM\x00\x2a":
            u16 = lambda o: int.from_bytes(data[o:o + 2], "big")
            u32 = lambda o: int.from_bytes(data[o:o + 4], "big")
        else:
            continue
        ifd = tiff + u32(tiff + 4)
        tag_count = u16(ifd)
        entries_off = None
        for k in range(tag_count):
            tag = ifd + 2 + k * 12
            tid, typ, cnt = u16(tag), u16(tag + 2), u32(tag + 4)
            if tid == 0xB002:  # MPEntry
                if cnt >= 2 * 16:
                    entries_off = u32(tag + 8)
        if entries_off is None:
            continue
        ent = tiff + entries_off
        sec_off = u32(ent + 16 + 8)
        sec_size = u32(ent + 16 + 4)
        primary_off = u32(ent + 8)
        if primary_off != 0:
            continue
        sec_begin = tiff + sec_off
        # 校验：与扫描出的第二张 JPEG 范围一致（同官方 association 判据；
        # size 字段存在 Apple 式记账偏差，提取范围以扫描为准）
        s0, s1 = images[1]
        if sec_begin == s0 and abs(sec_size - (s1 - s0)) <= 4:
            return s0, s1
        return None


# ── XMP 元数据 ────────────────────────────────────────────────────────────────
def parse_xmp(data, segments, images):
    """回读 hdrgm:GainMapMin/Max/Gamma 等（log2 域）。在所有段里找 XMP APP1。"""
    for marker, b, e in segments:
        if marker != 0xE1:
            continue
        if data[b:b + len(XMP_SIG)] != XMP_SIG:
            continue
        xml = data[b + len(XMP_SIG):e].decode("utf-8", "ignore")
        meta = {}
        for key in ("GainMapMin", "GainMapMax", "GainMapGamma",
                    "HDRCapacityMin", "HDRCapacityMax", "BaseRenditionIsHDR"):
            m = re.search(r'hdrgm:%s="([^"]+)"' % key, xml)
            if m:
                meta[key] = m.group(1)
        if meta:
            return meta
    return {}


# ── 数学（官方 gainmapmath.cpp 口径）─────────────────────────────────────────
def srgb_inv_oetf(x):
    x = np.clip(x, 0.0, 1.0)
    return np.where(x <= 0.04045, x / 12.92, np.power((x + 0.055) / 1.055, 2.4))


def luma709(rgb):
    return 0.2126 * rgb[..., 0] + 0.7152 * rgb[..., 1] + 0.0722 * rgb[..., 2]


def pct(a, q):
    return float(np.percentile(a, q))


# ── 单文件评估 ────────────────────────────────────────────────────────────────
def eval_file(path):
    data = open(path, "rb").read()
    images, segments = scan_jpeg(data)
    if len(images) != 2:
        return {"file": path, "error": f"expected 2 embedded images, found {len(images)}"}
    sec = parse_mpf_secondary(data, segments, images)
    if sec is None:
        return {"file": path, "error": "no valid MPF association (not a JPEG/R?)"}
    meta = parse_xmp(data, segments, images)

    base = np.asarray(Image.open(io.BytesIO(data[images[0][0]:images[0][1]])).convert("RGB"),
                      dtype=np.float32) / 255.0
    gm_img = Image.open(io.BytesIO(data[sec[0]:sec[1]]))
    gm_mode = gm_img.mode
    gm_orig = np.asarray(gm_img.convert("RGB" if gm_mode != "L" else "L"),
                         dtype=np.float32) / 255.0
    gm = gm_orig
    if gm.ndim == 2:  # 灰度 → 广播到三通道
        gm = np.repeat(gm[..., None], 3, axis=2)
    if gm.shape[:2] != base.shape[:2]:
        gm = np.stack([np.asarray(
            Image.fromarray((gm[..., c] * 255).astype(np.uint8)).resize(
                (base.shape[1], base.shape[0]), Image.BILINEAR), dtype=np.float32) / 255.0
            for c in range(3)], axis=2)

    gmin = float(meta.get("GainMapMin", -2.0))
    gmax = float(meta.get("GainMapMax", 2.0))
    gamma = float(meta.get("GainMapGamma", 1.0))

    min_boost, max_boost = math.exp2(gmin), math.exp2(gmax)
    g = np.clip(gm, 0.0, 1.0) ** (1.0 / gamma) if gamma != 1.0 else np.clip(gm, 0.0, 1.0)
    log_boost = math.log2(min_boost) * (1 - g) + math.log2(max_boost) * g
    hdr_lin = (srgb_inv_oetf(base) + 1e-7) * np.exp2(log_boost) - 1e-7
    hdr_y = luma709(np.clip(hdr_lin, 0.0, None))

    base8 = base * 255.0
    base_y8 = luma709(base) * 255.0
    gl = luma709(g)  # 报告口径：gainmap 用 Rec.709 luma（灰度图三通道相同）
    ratio = np.exp2(log_boost)

    return {
        "file": path,
        "meta": {"GainMapMin": gmin, "GainMapMax": gmax, "GainMapGamma": gamma,
                 "BaseRenditionIsHDR": meta.get("BaseRenditionIsHDR", "-")},
        "base_size": f"{base.shape[1]}x{base.shape[0]}",
        "gainmap_size": f"{gm_img.size[0]}x{gm_img.size[1]}", "gainmap_mode": gm_mode,
        "gainmap_downsample": round(base.shape[1] / gm_img.size[0], 1),
        "gain_rgb_spread": (0.0 if gm_mode == "L"
                            else float(np.abs(gm_orig - gm_orig[..., :1]).max())),
        # base（8bit 口径）
        "base_mean": pct(base_y8, 50) * 0 + float(base_y8.mean()),
        "base_p50": pct(base_y8, 50), "base_p95": pct(base_y8, 95), "base_p99": pct(base_y8, 99),
        "base_ge240_pct": 100.0 * float((base8 >= 240).any(axis=2).mean()),
        "base_ge250_pct": 100.0 * float((base8 >= 250).any(axis=2).mean()),
        # gainmap（8bit 归一化 [0,1] 口径）
        "gain_mean": float(gl.mean()), "gain_p90": pct(gl, 90), "gain_p99": pct(gl, 99),
        "gain_max": float(gl.max()),
        "boost_p90": pct(ratio, 90), "boost_p99": pct(ratio, 99), "boost_max": float(ratio.max()),
        # HDR 重建（线性 luma，W=1 满档显示）
        "hdr_p50": pct(hdr_y, 50), "hdr_p90": pct(hdr_y, 90), "hdr_p99": pct(hdr_y, 99),
        "hdr_ge1_pct": 100.0 * float((hdr_y >= 1.0).mean()),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jpegs", nargs="+")
    ap.add_argument("--json", default=None, help="把逐文件统计另存 JSON")
    args = ap.parse_args()

    results = []
    for p in args.jpegs:
        try:
            results.append(eval_file(p))
        except Exception as e:  # 单张失败不拖垮整批
            results.append({"file": p, "error": f"{type(e).__name__}: {e}"})

    for r in results:
        print("=" * 76)
        if "error" in r:
            print(f"{r['file']}\n  ERROR: {r['error']}")
            continue
        print(f"{r['file']}")
        print(f"  meta: Min={r['meta']['GainMapMin']:+g} Max={r['meta']['GainMapMax']:+g} "
              f"gamma={r['meta']['GainMapGamma']:g} baseIsHDR={r['meta']['BaseRenditionIsHDR']}  "
              f"base={r['base_size']} gainmap={r['gainmap_size']} ({r['gainmap_mode']}, "
              f"1/{r['gainmap_downsample']:g}, rgbSpread={r['gain_rgb_spread']:.3f})")
        print(f"  base 8bit : mean={r['base_mean']:.1f}  p50={r['base_p50']:.1f} "
              f"p95={r['base_p95']:.1f} p99={r['base_p99']:.1f}")
        print(f"              >=240: {r['base_ge240_pct']:.2f}%   >=250: {r['base_ge250_pct']:.2f}%")
        print(f"  gainmap   : mean={r['gain_mean']:.3f}  p90={r['gain_p90']:.3f} "
              f"p99={r['gain_p99']:.3f} max={r['gain_max']:.3f}")
        print(f"  boost     : p90={r['boost_p90']:.2f}x p99={r['boost_p99']:.2f}x "
              f"max={r['boost_max']:.2f}x")
        print(f"  HDR (W=1) : luma p50={r['hdr_p50']:.3f} p90={r['hdr_p90']:.3f} "
              f"p99={r['hdr_p99']:.3f}  >=1.0: {r['hdr_ge1_pct']:.2f}%")

    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(results, f, ensure_ascii=False, indent=1)
        print(f"\nJSON -> {args.json}")


if __name__ == "__main__":
    sys.exit(main())
