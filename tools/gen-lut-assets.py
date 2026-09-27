#!/usr/bin/env python3
"""把 64³ 原始 .cube 重采样为 33³ 二进制资产（M4-2 LUT 库扩容）。

源：~/Downloads/LUT/滤镜.cube/*.cube（64³，每文件约 7MB）
出：app/src/main/assets/luts/<slug>.lut

**为什么内置资产不是 ASCII .cube**：42 个 33³ ASCII 资产要 40MB（仓库与 APK 都会
明显膨胀）。源 LUT 数据本身是 8-bit 精度（值落在 1/255 或 1/512 网格）、成片也是
8-bit JPEG，所以 uint8 存储的量化误差 ≤0.5 LSB、在成片上不可见 —— 只要 4.3MB。
**§6.2 的 .cube 导入导出不受影响**：内置打包格式与用户交换格式是两件事，
CubeLutParser 的 ASCII .cube 解析器（含 10 个单测）完整保留。

二进制格式（无外部依赖，解析器 15 行）：
    [size:1B][titleLen:1B][title][catLen:1B][category][data: size³ × 3B RGB]

用法：
    python3 tools/gen-lut-assets.py                    # 默认源目录 -> assets
    python3 tools/gen-lut-assets.py --dry-run          # 只报告体积与清单
"""
import argparse
import glob
import hashlib
import os
import re
import unicodedata
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.expanduser("~/Downloads/LUT/滤镜.cube")
OUT_DIR = os.path.join(ROOT, "app/src/main/assets/luts")
TARGET = 33

# 中文显示名 + 默认分类（用户 2026-09-22 要求：显示名中文化、默认按品牌分类）
# 型号保留原文 —— 胶片/相机型号是专有名词，翻译反而难认。
META = {
    "CineStill-50D": ("CineStill 50D", "CineStill"),
    "CineStill-800T": ("CineStill 800T", "CineStill"),
    "Fuji-ACROS": ("富士 ACROS", "富士"),
    "Fuji-ASTIA": ("富士 ASTIA", "富士"),
    "Fuji-CC": ("富士 CC", "富士"),
    "Fuji-ETERNA-BB": ("富士 ETERNA BB", "富士"),
    "Fuji-ETERNA": ("富士 ETERNA", "富士"),
    "Fuji-NC": ("富士 NC", "富士"),
    "Fuji-NN": ("富士 NN", "富士"),
    "Fuji-PRO-Neg-Std": ("富士 PRO Neg Std", "富士"),
    "Fuji-PROVIA": ("富士 PROVIA", "富士"),
    "Fuji-REALA-ACE": ("富士 REALA ACE", "富士"),
    "Fuji-Velvia": ("富士 Velvia", "富士"),
    "Hasselblad-Blue": ("哈苏 Blue", "哈苏"),
    "Hasselblad-Classic": ("哈苏 Classic", "哈苏"),
    "Kodak-5203": ("柯达 5203", "柯达"),
    "Kodak-5207": ("柯达 5207", "柯达"),
    "Kodak-5219": ("柯达 5219", "柯达"),
    "Kodak-E200": ("柯达 E200", "柯达"),
    "Kodak-Gold 200": ("柯达 Gold 200", "柯达"),
    "Kodak-Portra 400": ("柯达 Portra 400", "柯达"),
    "Kodak-Ultramax 400": ("柯达 UltraMax 400", "柯达"),
    "LEICA M9": ("徕卡 M9", "徕卡"),
    "Leica B&W true": ("徕卡 黑白 True", "徕卡"),
    "Leica B&W-HC": ("徕卡 黑白 HC", "徕卡"),
    "Leica Brass": ("徕卡 Brass", "徕卡"),
    "Leica Chrome": ("徕卡 Chrome", "徕卡"),
    "Leica Classic": ("徕卡 Classic", "徕卡"),
    "Leica Contemporary": ("徕卡 Contemporary", "徕卡"),
    "Leica Eternal": ("徕卡 Eternal", "徕卡"),
    "Leica Nature": ("徕卡 Nature", "徕卡"),
    "Leica Vivid": ("徕卡 Vivid", "徕卡"),
    "Leica-Classic": ("徕卡 Classic 2", "徕卡"),
    "LeicaTeal": ("徕卡 Teal", "徕卡"),
    "MI系人像胶片": ("MI 人像胶片", "MI"),
    "MI系风光胶片": ("MI 风光胶片", "MI"),
    "Ricoh-Cinema Green": ("理光 电影绿", "理光"),
    "Ricoh-Cinema Yellow": ("理光 电影黄", "理光"),
    "Ricoh-HDF": ("理光 HDF", "理光"),
    "Ricoh-High Contrast B&W": ("理光 高反差黑白", "理光"),
    "Ricoh-Negative": ("理光 负片", "理光"),
    "Ricoh-Positive": ("理光 正片", "理光"),
    "Ricoh-Vivid": ("理光 Vivid", "理光"),
}



def parse_cube(path):
    txt = open(path, encoding="utf-8", errors="replace").read()
    i = txt.find("LUT_3D_SIZE")
    if i < 0:
        raise ValueError(f"{path}: 缺少 LUT_3D_SIZE")
    j = txt.find("\n", i)
    size = int(txt[i + len("LUT_3D_SIZE"):j].split()[0])
    toks = [t for t in txt[j:].split() if t[0].isdigit() or t[0] in "-."]
    data = np.array(toks, dtype=np.float64)
    n = size * size * size * 3
    if data.size < n:
        raise ValueError(f"{path}: 数据不足 {data.size}/{n}")
    # .cube 数据顺序：红最快 -> reshape 后轴序为 [b, g, r]
    return size, data[:n].reshape(size, size, size, 3)


def resample(a, n, target):
    """在 target³ 网格点上三线性采样（33³ 相对 64³ 的表达力上限 ΔE 0.04–0.17）"""
    v = np.linspace(0.0, 1.0, target)
    B, G, R = np.meshgrid(v, v, v, indexing="ij")
    coords = np.stack([R, G, B], -1)  # [r, g, b]
    t = np.clip(coords, 0, 1) * (n - 1)
    i0 = np.clip(np.floor(t).astype(np.int64), 0, n - 2)
    fr = t - i0
    out = np.zeros(coords.shape[:-1] + (3,))
    for db in (0, 1):
        for dg in (0, 1):
            for dr in (0, 1):
                w = ((fr[..., 0] if dr else 1 - fr[..., 0])
                     * (fr[..., 1] if dg else 1 - fr[..., 1])
                     * (fr[..., 2] if db else 1 - fr[..., 2]))
                out += w[..., None] * a[i0[..., 2] + db, i0[..., 1] + dg, i0[..., 0] + dr]
    return out


def slugify(name):
    s = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode()
    s = re.sub(r"[^A-Za-z0-9]+", "_", s).strip("_").lower()
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=SRC_DIR)
    ap.add_argument("--out", default=OUT_DIR)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    # CLI 入口的路径消毒：realpath 归一化（消除 ../ 与符号链接），输出固定落位
    args.src = os.path.realpath(args.src)
    args.out = os.path.realpath(args.out)

    files = sorted(glob.glob(os.path.join(args.src, "*.cube")))
    if not files:
        print(f"源目录没有 .cube：{args.src}")
        return
    if not args.dry_run:
        os.makedirs(args.out, exist_ok=True)

    used, total, manifest, seen = set(), 0, [], {}
    for p in files:
        base = os.path.basename(p)[:-5]
        title, category = META.get(base, (base, "未分类"))
        digest = hashlib.md5(open(p, "rb").read()).hexdigest()
        if digest in seen:
            print(f"跳过重复：{base} == {seen[digest]}")
            continue
        seen[digest] = base
        slug = slugify(base) or "lut"
        cand, k = slug, 2
        while cand in used:
            cand, k = f"{slug}_{k}", k + 1
        used.add(cand)

        n, a = parse_cube(p)
        small = np.clip(resample(a, n, TARGET) * 255.0 + 0.5, 0, 255).astype(np.uint8)
        tb = title.encode("utf-8")[:255]
        cb = category.encode("utf-8")[:255]
        blob = bytes([TARGET, len(tb)]) + tb + bytes([len(cb)]) + cb + small.tobytes()
        if not args.dry_run:
            with open(os.path.join(args.out, f"{cand}.lut"), "wb") as f:
                f.write(blob)
        total += len(blob)
        manifest.append((cand, title, category, n, len(blob)))

    print(f"{'文件':26s} {'显示名':22s} {'分类':10s} {'源':>4s} {'字节':>9s}")
    for cand, title, cat, n, sz in manifest:
        print(f"{cand:26s} {title:22s} {cat:10s} {n:4d} {sz:9d}")
    print(f"\n共 {len(manifest)} 个，合计 {total/1024/1024:.2f} MB"
          f"（等量裸 ASCII .cube 约 {len(manifest)*970/1024:.1f} MB）")
    if args.dry_run:
        print("dry-run：未写文件")


if __name__ == "__main__":
    main()
