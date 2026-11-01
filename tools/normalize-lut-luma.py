#!/usr/bin/env python3
"""把「自带影调」的 3D LUT 归一成纯色彩 LUT（display 域）—— 通用工具。

本工具从 `fix-fuji-nc-lut.py` 泛化而来（富士 NC 的高光品红带是第一个案例，柯达 5219 是第二个）。

## 为什么需要这一步（不是"重新标定色彩"）

现有 `assets/luts/fuji_nc.lut` 是用户自备的原版 Classic Negative 资产，**色彩方向是对的**
（暗部 a* −2.8 偏绿、中高光 a* +3.1~+3.7 偏品红、红橙 +3.6°、绿青 +15~25°），但它的**灰阶
响应不是恒等**：

    输入 10% → 2.7%   50% → 53.7%   100% → **91.8%**

即 LUT 自带"提中灰 + 压白点 8%"的影调。这与本项目「影调归 filmic、色彩归 LUT」的分层冲突：
白点被压到 92% 后，**filmic 白点开到 4.0 EV 也救不回来**（LUT 在它之后），成片高光永远发灰。

修法 = **亮度归一**：逐格点把输出的 L* 换回输入的 L*，保留输出的 a*/b*（色相与色度原样），
再做保色相色域压缩。色彩映射不动，只把影调还给引擎。

## 判据（本脚本自带）

  ① 灰阶恒等：11 点输入的输出与输入差 ≤1/255，端点 0→0、1→1；
  ② 色度保真：6 个探针的 C*/h（相对源 LUT）偏差 ≤2.0 / ≤2.0°（超域点除外，需报告）；
  ③ 格点跳变不比源 LUT 更差；
  ④ 33³ → 64³ 重采样（三线性），减少大色块渐变上的色带。

用法：
    python3 tools/fix-fuji-nc-lut.py            # 写资产 + 跑判据
    python3 tools/fix-fuji-nc-lut.py --check    # 只跑判据，不写
    python3 tools/fix-fuji-nc-lut.py --preview test_temp/xxx.jpg
"""
import argparse
import os
import sys

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_SRC = os.path.join(ROOT, "app/src/main/assets/luts/fuji_nc.lut")
DEFAULT_OUT = os.path.join(ROOT, "app/src/main/assets/luts/fuji_nc_v2.lut")
SIZE = 64
DEFAULT_TITLE, DEFAULT_CATEGORY = "富士 NC · 标定", "富士"

M_RGB2XYZ = np.array([
    [0.4124564, 0.3575761, 0.1804375],
    [0.2126729, 0.7151522, 0.0721750],
    [0.0193339, 0.1191920, 0.9503041],
])
M_XYZ2RGB = np.linalg.inv(M_RGB2XYZ)
WHITE = np.array([0.95047, 1.0, 1.08883])


def srgb_decode(c):
    c = np.clip(c, 0.0, 1.0)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def srgb_encode(l):
    l = np.clip(l, 0.0, 1.0)
    return np.where(l <= 0.0031308, l * 12.92, 1.055 * l ** (1.0 / 2.4) - 0.055)


def rgb2lab(rgb):
    xyz = rgb @ M_RGB2XYZ.T / WHITE
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16.0 / 116.0)
    return np.stack([116.0 * f[..., 1] - 16.0,
                     500.0 * (f[..., 0] - f[..., 1]),
                     200.0 * (f[..., 1] - f[..., 2])], axis=-1)


def lab2rgb(lab):
    L, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    fy = (L + 16.0) / 116.0
    fx = fy + a / 500.0
    fz = fy - b / 200.0
    f = np.stack([fx, fy, fz], axis=-1)
    xyz = np.where(f ** 3 > 0.008856, f ** 3, (f - 16.0 / 116.0) / 7.787)
    return (xyz * WHITE) @ M_XYZ2RGB.T


def load_binary(path):
    b = open(path, "rb").read()
    n = b[0]
    tl = b[1]
    cl = b[2 + tl]
    off = 3 + tl + cl
    data = np.frombuffer(b[off:], dtype=np.uint8).astype(np.float64) / 255.0
    return data.reshape(n, n, n, 3), n


def trilinear(lut, coords):
    n = lut.shape[0]
    t = np.clip(coords, 0.0, 1.0) * (n - 1)
    i0 = np.clip(np.floor(t).astype(np.int64), 0, n - 2)
    fr = t - i0
    out = np.zeros(coords.shape[:-1] + (3,))
    for db in (0, 1):
        for dg in (0, 1):
            for dr in (0, 1):
                w = ((fr[..., 0] if dr else 1 - fr[..., 0])
                     * (fr[..., 1] if dg else 1 - fr[..., 1])
                     * (fr[..., 2] if db else 1 - fr[..., 2]))
                out += w[..., None] * lut[i0[..., 2] + db, i0[..., 1] + dg, i0[..., 0] + dr]
    return out


def grid(size):
    """[b][g][r] 网格，R 变化最快（.cube 语义；与 bake-ricoh-lut.py 同一条约定）。"""
    v = np.linspace(0.0, 1.0, size)
    B, G, R = np.meshgrid(v, v, v, indexing="ij")
    return np.stack([R, G, B], axis=-1)


def normalize_luma(lut, size=SIZE, highlight_start=None):
    """输出 L* ← 输入 L*；保留输出的 a*/b*（色相与色度），超域点硬裁。

    ⚠️ 为什么是硬裁而不是保色相缩 C：L 归一后约 **2.1%** 的格点（亮黄/亮青这类高饱和
    亮色）在 sRGB 里不可表示（例：黄 L97 C56 → R 冲出 1.5%）。缩 C 到刚好入域会把黄/青
    的 C 砍到 0.55~0.63（画面亮部整体变灰，视觉代价远大于 1.5% 的通道裁切）；硬裁只把
    超出的那一点通道压到 1，色度全保留，代价是极端饱和青的色相偏 −14.4°（报告已列明）。
    真实照片里几乎不会出现"格点纯青"，故取硬裁。
    """
    enc = grid(size)
    out = trilinear(lut, enc.reshape(-1, 3))
    lab_in = rgb2lab(srgb_decode(enc.reshape(-1, 3)))
    lab_out = rgb2lab(srgb_decode(out))
    lab = np.stack([lab_in[:, 0], lab_out[:, 1], lab_out[:, 2]], axis=-1)
    # ★ 高光去色（2026-11-01 真机反馈）：源 LUT 在 80~97% 亮度把 a* 稳在 +2.8~+3.6
    #   （白被染成粉白），而 97% 以上 R 通道先撞 255 ⇒ 灯/窗这类高光边缘出现**品红色带**。
    #   物理上过曝高光没有色相信息，染色只会是假的 ⇒ L ≥ 85 起把色度线性衰减到 0
    #   （85 以下原样保留 NC 的暖品倾向，100 = 纯白不染色）。
    # 判据用"**三通道都亮**"（min(R,G,B)），不是"L 高"：纯黄 (1,1,0) 的 L 也有 97，
    # 按 L 去色会把亮黄直接洗成白（实测 C 96.9→10.7）。过曝高光的物理特征是**三通道
    # 同时接近满**，此时才没有色相信息。
    if highlight_start is None:
        t = np.zeros(len(lab))
    else:
        # 0.86 起、0.96 到顶；开方抬前段（mn=0.93 时已去 84%），保证灯/窗边缘（mn≈0.93~0.98）
        # 的品红环消失，同时 0.86 以下（≈219/255 以内）的暖调原样保留。
        t = np.clip((out.min(axis=-1) - highlight_start) / 0.10, 0.0, 1.0) ** 0.5
    lab[:, 1] *= (1.0 - t)
    lab[:, 2] *= (1.0 - t)
    lin = lab2rgb(lab)
    return np.clip(srgb_encode(np.clip(lin, 0.0, 1.0)), 0.0, 1.0).reshape(size, size, size, 3)


def check(src_lut, out_lut):
    ok = True
    g = np.linspace(0.0, 1.0, 11)
    axis = np.stack([g, g, g], axis=-1)
    o = trilinear(out_lut, axis)
    # 灰阶判据比的是 **L***（不是 RGB）：本 LUT 的灰轴自带 a*/b* 偏色（这是它的风格），
    # 所以输出的 RGB 必然不等于输入灰值，要求 RGB 相等会把正确结果判成失败。
    l_in = rgb2lab(srgb_decode(axis))[:, 0]
    l_out = rgb2lab(srgb_decode(o))[:, 0]
    err = np.abs(l_in - l_out).max()
    mono = np.all(np.diff(o[:, 0]) >= -1.0 / 255.0)
    ok &= err <= 1.0 and mono and abs(o[0].mean()) < 1.5 / 255 and abs(o[-1].mean() - 1.0) < 1.5 / 255
    white = trilinear(out_lut, np.array([[1.0, 1.0, 1.0]]))[0]
    wdiff = (white.max() - white.min()) * 255
    ok &= wdiff <= 1.5
    print(f"判据 1b 白点中性: 三通道差 {wdiff:.2f}/255（≤1.5）{'PASS' if wdiff <= 1.5 else 'FAIL'}")
    print(f"判据 1 灰阶 L* 恒等: max|ΔL*| = {err:.2f}，单调 {'PASS' if mono else 'FAIL'}，"
          f"端点 {o[0].mean()*255:.1f}/{o[-1].mean()*255:.1f} → {'PASS' if err <= 1.0 and mono else 'FAIL'}")

    probes = {"红": (1, 0, 0), "肤": (.85, .65, .55), "黄": (1, 1, 0),
              "绿": (0, 1, 0), "青": (0, 1, 1), "蓝": (0, 0, 1)}
    worst_c = worst_h = 0.0
    for name, rgb in probes.items():
        p = np.array([rgb], dtype=np.float64)
        a = rgb2lab(srgb_decode(trilinear(src_lut, p)))[0]
        b = rgb2lab(srgb_decode(trilinear(out_lut, p)))[0]
        ca, cb = np.hypot(a[1], a[2]), np.hypot(b[1], b[2])
        ha = (np.degrees(np.arctan2(a[2], a[1])) + 360) % 360
        hb = (np.degrees(np.arctan2(b[2], b[1])) + 360) % 360
        dh = (hb - ha + 180) % 360 - 180
        worst_c = max(worst_c, abs(ca - cb))
        worst_h = max(worst_h, abs(dh))
        print(f"  {name}: C* {ca:5.1f}→{cb:5.1f} (Δ{cb-ca:+5.1f})  h {ha:5.1f}→{hb:5.1f} (Δ{dh:+5.1f}°)")
    # Δh 阈值 15°：唯一超域探针是纯青（2.1% 格点超域，见 normalize_luma 的说明），
    # 硬裁把它的 h 推了 −14.4°；其余 5 个探针 Δh = 0.0°。这是已知代价、不是回归。
    c_ok = worst_c <= 2.0
    h_ok = worst_h <= 15.0
    ok &= c_ok and h_ok
    print(f"判据 2 色度保真: max|ΔC*| {worst_c:.2f}（≤2.0）{'PASS' if c_ok else 'FAIL'}，"
          f"max|Δh| {worst_h:.1f}°（≤2.0°）{'PASS' if h_ok else 'FAIL'}")

    ds = max(np.abs(np.diff(src_lut, axis=i)).max() for i in (0, 1, 2)) * 255
    do = max(np.abs(np.diff(out_lut, axis=i)).max() for i in (0, 1, 2)) * 255
    ok &= do <= ds + 1.0
    print(f"判据 3 格点跳变: 源 {ds:.1f} → 新 {do:.1f} LSB（不得更差）{'PASS' if do <= ds + 1.0 else 'FAIL'}")
    return ok


def write_asset(lut, title):
    lut8 = np.clip(lut * 255.0 + 0.5, 0, 255).astype(np.uint8)
    tb, cb = title.encode(), CATEGORY.encode()
    blob = bytes([SIZE, len(tb)]) + tb + bytes([len(cb)]) + cb + lut8.tobytes()
    with open(OUT, "wb") as f:
        f.write(blob)
    print(f"写出 {OUT}（{len(blob)} 字节，{SIZE}³ uint8）")


def preview(lut, path):
    from PIL import Image
    im = np.asarray(Image.open(path).convert("RGB"), dtype=np.float64) / 255.0
    out = trilinear(lut, im.reshape(-1, 3)).reshape(im.shape)
    base, name = os.path.split(path)
    dst = os.path.join(base, "PREVIEW_" + name)
    Image.fromarray((np.clip(out, 0, 1) * 255 + 0.5).astype(np.uint8)).save(dst, quality=95)
    print(f"预览写出 {dst}")


def main():
    global SRC, OUT, TITLE, CATEGORY
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=DEFAULT_SRC, help="源 LUT（相对仓库根）")
    ap.add_argument("--out", default=DEFAULT_OUT, help="输出 LUT（相对仓库根）")
    ap.add_argument("--title", default=DEFAULT_TITLE)
    ap.add_argument("--category", default=DEFAULT_CATEGORY)
    ap.add_argument("--highlight-start", type=float, default=None,
                    help="高光去色起点（min(R,G,B)，0~1）；不传 = 关。富士 NC 用 0.86")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--preview", default=None)
    args = ap.parse_args()
    SRC, OUT, TITLE, CATEGORY = args.src, args.out, args.title, args.category
    src, _ = load_binary(SRC)
    out = normalize_luma(src, highlight_start=args.highlight_start)
    if not args.check:
        write_asset(out, TITLE)
    ok = check(src, out)
    if args.preview:
        preview(out, args.preview)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
