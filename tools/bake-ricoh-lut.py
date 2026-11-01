#!/usr/bin/env python3
"""把「理光 GR Negative Film」的色相/色度特征烘焙为纯色彩 3D LUT（display 域）。

与 bake-m9-lut.py 的区别：徕卡有 PC 固件配方可逐位对照；理光没有固件可挖，只能从
**官方样片**反推。因此本脚本只烘"色彩映射"（色相旋转 + 饱和度缩放 + 灰轴偏色），
**不烘曲线、不压白点**——对比/黑白点/肩部归 filmic 参数（preset 的 contrast / toe /
shoulder / filmic_black_ev / white_ev），与本项目"影调归引擎、色彩归 LUT"的分层一致。

目标（来自 grblog.jp/en/article/7151 的 12 张官方样片 + 官方定性描述）：
  - 红：褪色橙（官方 "faded saturation"）
  - 蓝：灰蓝（官方 "slightly muted blue"，不是紫）
  - 绿：偏蓝的青绿（官方 "strong bluish tint"）
  - 灰轴：略偏青（暗部更明显），高光微蓝
  - 饱和度：C* 中位 ≈2.6、P90 ≈18（低饱和是主特征）
  - 高光：柔（≥240 占比 ≈0），但白点不塌（交给 filmic，LUT 不动）

用法：
    python3 tools/bake-ricoh-lut.py            # 烘焙 + 写资产 + 跑判据
    python3 tools/bake-ricoh-lut.py --check    # 只跑判据
    python3 tools/bake-ricoh-lut.py --preview test_temp/xxx.jpg   # 套一张图看观感
"""
import argparse
import os
import sys

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app/src/main/assets/luts/ricoh_negative_v2.lut")
SIZE = 64
TITLE, CATEGORY = "理光 负片 · 标定", "理光"

# ── 目标映射表（Lab 色相角，单位度；0=红 90=黄 180=绿 270=蓝）──────────────────
# 每项 = (原色相, 目标色相, 饱和度系数)。关键点之间线性插值，色相环首尾相接。
HUE_TABLE = [
    # (输入 Lab 色相°, 目标色相°, 饱和度系数)
    # ⚠ 数据来源（2026-11-01）：grblog 7151 两组**同场景 Positive↔Negative** 逐像素对齐
    #   （结构相关 0.99，位移 0）统计出蓝/绿的实测 delta；红/黄无配对数据，按文章定性
    #   （"红→褪色朱红"）取保守值。灰轴另由 19 张标准 Negative 样片的低饱和像素分亮度
    #   桶统计（见 B_STAR_TABLE）。
    #   蓝: Δh −4°，C×0.67（实测，官方 "muted blue"）
    #   绿: Δh +13~16°，C×0.90（实测，官方 "strong bluish tint"）
    (0.0,   -1.0, 0.92),
    (40.0,  37.0, 0.92),   # 红 → 略偏橙、轻褪色（无配对数据，保守）
    (55.0,  53.0, 0.95),   # 橙/肤色
    (80.0,  79.0, 0.92),
    (103.0, 103.0, 0.90),  # 黄
    (120.0, 128.0, 0.90),
    (136.0, 149.0, 0.90),  # 绿 → 偏蓝绿（实测 Δh +13，C×0.90）
    (170.0, 183.0, 0.88),
    (196.0, 200.0, 0.86),  # 青（无配对数据，与绿同向）
    (240.0, 245.0, 0.75),
    (280.0, 283.0, 0.68),
    (306.0, 302.0, 0.67),  # 蓝 → muted（实测 Δh −4，C×0.67）
    (328.0, 326.0, 0.85),  # 品红
    (359.0, 358.0, 0.92),
]

# 灰轴偏色：b* 偏移随 L（0..100）变化 —— 暗部偏青、中间调轻青、高光微蓝
B_STAR_TABLE = [(0.0, 1.05), (15.0, 0.02), (35.0, -0.35), (55.0, -1.30), (75.0, -0.85), (95.0, -1.10)]
A_STAR_TABLE = [(0.0, 0.55), (15.0, 0.55), (35.0, 0.75), (55.0, 0.0), (75.0, -0.4), (95.0, -0.8)]

# ── 理光正片（Positive Film, GR III/IIIx 三代）────────────────────────────────
# 目标表 = 负片表 ⊕ 「同场景 Positive↔Negative 配对」实测 delta（grblog 7151 三组，
# 像素对齐、亮度匹配 |ΔL|<4 后按色相桶取中位）：
#   红 h0-60:   Δh −4.5~−7.9°,  C×1.04~1.07
#   绿 h90-180: Δh −10~−14.3°,  C×1.07~1.20
#   蓝 h240-300: Δh −0.9~+2.4°, C×1.41~1.45
# 实测与文章定性同向：正片 = 正红（非褪色朱红）、黄绿（非蓝绿）、蓝比负片浓。
# 灰轴由 12 张默认参数 Positive 样片的低饱和像素分 L 桶中位统计（2026-11-01）。
POSITIVE_HUE_TABLE = [
    (0.0,   -2.0, 0.98),
    (25.0,  18.0, 0.99),   # 红→正红（配对 Δh −7.9 ⇒ 相对负片的 37° 回到 29°）
    (40.0,  29.0, 1.00),
    (55.0,  47.0, 1.01),   # 橙/肤色（配对 Δh −4.5）
    (80.0,  72.0, 1.02),
    (103.0, 96.0, 1.02),   # 黄（配对 Δh −10）
    (120.0, 116.0, 1.02),
    (136.0, 134.0, 1.00),  # 绿→黄绿（配对 Δh −14.1，相对负片的 149°）
    (170.0, 163.0, 0.98),
    (196.0, 194.0, 0.96),  # 青
    (240.0, 242.0, 0.95),
    (280.0, 282.0, 0.94),
    (306.0, 303.0, 0.96),  # 蓝：色相几乎不动、比负片浓（配对 C×1.45）
    (328.0, 323.0, 0.95),  # 品红
    (359.0, 357.0, 0.98),
]
# 正片灰轴（12 张默认参数样片，低饱和像素 C*<8、按亮度桶加权）：
#   L 0-10 b*+1.36 / 20-30 −1.20 / 50-60 −1.06 / 90-100 +0.64（暗暖、中间略冷、高光回暖）
POSITIVE_B_STAR_TABLE = [(0.0, 1.20), (15.0, 0.30), (35.0, -0.55), (55.0, -0.95), (75.0, 0.10), (95.0, 0.55)]
POSITIVE_A_STAR_TABLE = [(0.0, 0.85), (15.0, 0.85), (35.0, 0.45), (55.0, 0.0), (75.0, -0.30), (95.0, -0.30)]

# 预设 = 一组目标表 + 输出路径 + 标题（`--preset` 选择；默认 negative 保持旧行为兼容）
PRESETS = {
    "negative": dict(hue=HUE_TABLE, b=B_STAR_TABLE, a=A_STAR_TABLE,
                     out="ricoh_negative_v2.lut", title="理光 负片 · 标定"),
    "positive": dict(hue=POSITIVE_HUE_TABLE, b=POSITIVE_B_STAR_TABLE, a=POSITIVE_A_STAR_TABLE,
                     out="ricoh_positive_v1.lut", title="理光 正片 · 标定"),
}


def srgb_decode(c):
    c = np.clip(c, 0.0, 1.0)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def srgb_encode(l):
    l = np.clip(l, 0.0, 1.0)
    return np.where(l <= 0.0031308, l * 12.92, 1.055 * l ** (1.0 / 2.4) - 0.055)


M_RGB2XYZ = np.array([
    [0.4124564, 0.3575761, 0.1804375],
    [0.2126729, 0.7151522, 0.0721750],
    [0.0193339, 0.1191920, 0.9503041],
])
M_XYZ2RGB = np.linalg.inv(M_RGB2XYZ)
WHITE = np.array([0.95047, 1.0, 1.08883])


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
    rgb = (xyz * WHITE) @ M_XYZ2RGB.T
    return rgb


def _smooth360(xs, vals, wins=7):
    """周期滑动平均：色相环首尾相接，避免表关键点处的斜率突变（格点跳变）。"""
    out = np.zeros_like(vals)
    for i, x in enumerate(xs):
        idx = (np.arange(i - wins, i + wins + 1)) % len(xs)
        out[i] = vals[idx].mean()
    return out


def hue_curve(h_deg):
    """色相旋转量 + 饱和度系数（按 HUE_TABLE 关键点插值，再周期平滑）。"""
    xs = np.linspace(0.0, 360.0, 361)
    kxs = np.array([t[0] for t in HUE_TABLE], dtype=np.float64)
    tgt = np.array([t[1] for t in HUE_TABLE], dtype=np.float64)
    sat = np.array([t[2] for t in HUE_TABLE], dtype=np.float64)
    dh = _smooth360(xs, np.interp(xs, kxs, tgt - kxs), 9)
    sc = _smooth360(xs, np.interp(xs, kxs, sat), 9)
    idx = np.clip(np.round(h_deg).astype(np.int64) % 361, 0, 360)
    return dh[idx], sc[idx]


def gray_shift(L):
    b = np.interp(L, [t[0] for t in B_STAR_TABLE], [t[1] for t in B_STAR_TABLE])
    a = np.interp(L, [t[0] for t in A_STAR_TABLE], [t[1] for t in A_STAR_TABLE])
    # 端点保护：L→0 时偏移归零，否则纯黑会被染成带色 (0,0,0.02) 且灰轴不单调
    guard = np.clip(L / 8.0, 0.0, 1.0)
    return a * guard, b * guard


def map_lab(lab):
    L, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    C = np.hypot(a, b)
    h = (np.degrees(np.arctan2(b, a)) + 360.0) % 360.0
    dh, sc = hue_curve(h)
    # 饱和度越低，色相旋转越不可信（噪声色）⇒ 按 C 平滑衰减旋转量
    w = np.clip(C / 12.0, 0.0, 1.0)
    h2 = h + dh * w
    C2 = C * (1.0 + (sc - 1.0) * np.clip(C / 8.0, 0.0, 1.0))
    # 灰轴偏色：低饱和处按 (1-w) 全额生效，饱和色只吃一点
    ga, gb = gray_shift(L)
    a2 = C2 * np.cos(np.radians(h2)) + ga * (1.0 - 0.65 * w)
    b2 = C2 * np.sin(np.radians(h2)) + gb * (1.0 - 0.65 * w)
    return np.stack([L, a2, b2], axis=-1)


def gamut_clip(rgb):
    """沿 L 方向压回 sRGB 域（保持色相，避免硬裁出彩边）。"""
    lo = rgb.min(axis=-1)
    hi = rgb.max(axis=-1)
    # 先硬裁（少量），负值抬到 0、超 1 压到 1 后再做一次保色相压缩
    rgb = np.clip(rgb, 0.0, 1.0)
    excess = np.maximum(rgb.max(axis=-1) - 1.0, 0.0)
    if np.any(excess > 0):
        lum = rgb @ np.array([0.2126, 0.7152, 0.0722])
        scale = np.where(lum > 1e-6, (1.0 - excess) / np.maximum(lum, 1e-6), 1.0)
        rgb = rgb * np.clip(scale, 0.0, 1.0)[..., None]
    return np.clip(rgb, 0.0, 1.0)


def build():
    v = np.linspace(0.0, 1.0, SIZE)
    B, G, R = np.meshgrid(v, v, v, indexing="ij")  # [b][g][r]，R 最快（.cube 语义）
    enc = np.stack([R, G, B], axis=-1)
    lin = srgb_decode(enc)
    lab = rgb2lab(lin)
    out_lab = map_lab(lab)
    out_lin = gamut_clip(lab2rgb(out_lab))
    out = srgb_encode(out_lin)
    return np.clip(out, 0.0, 1.0)


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


def check(lut):
    axis = np.linspace(0.0, 1.0, 257)[:, None].repeat(3, axis=1)
    g = trilinear(lut, axis)
    # 灰轴允许灰轴偏色（这是设计），只检查"单调 + 不过冲"（容差 1 LSB 内的量化抖动）
    mono = np.all(np.diff(g, axis=0) >= -1.0 / 255.0)
    print(f"判据 1 灰轴单调: {'PASS' if mono else 'FAIL'}；端点 black={g[0].round(3)} white={g[-1].round(3)}")
    print(f"判据 2 灰轴偏色（b* 设计值）: 暗部 {np.round((g[40] - g[40].mean()) * 255, 1)}、"
          f"中调 {np.round((g[128] - g[128].mean()) * 255, 1)}（R/B 应为负）")
    for name, rgb in (("红", (1, 0, 0)), ("橙", (.9, .45, .2)), ("黄", (1, 1, 0)),
                      ("绿", (0, 1, 0)), ("青", (0, 1, 1)), ("蓝", (0, 0, 1))):
        probe = np.array([rgb], dtype=np.float64)
        o = trilinear(lut, probe)[0]
        L0, a0, b0 = rgb2lab(srgb_decode(probe))[0]
        L1, a1, b1 = rgb2lab(srgb_decode(o[None]))[0]
        h0 = (np.degrees(np.arctan2(b0, a0)) + 360) % 360
        h1 = (np.degrees(np.arctan2(b1, a1)) + 360) % 360
        print(f"  {name}: C* {np.hypot(a0,b0):5.1f} → {np.hypot(a1,b1):5.1f}  "
              f"h {h0:5.1f}° → {h1:5.1f}°  出 {np.round(o*255,1)}")
    d = max(np.abs(np.diff(lut, axis=a)).max() for a in (0, 1, 2)) * 255
    print(f"判据 3 格点最大跳变: {d:.1f} LSB（平滑映射应 < 12）")


def write_asset(lut):
    lut8 = np.clip(lut * 255.0 + 0.5, 0, 255).astype(np.uint8)
    tb, cb = TITLE.encode(), CATEGORY.encode()
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
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--preview", default=None)
    ap.add_argument("--preset", choices=sorted(PRESETS), default="negative",
                    help="negative=理光负片（默认，旧行为）；positive=理光正片")
    args = ap.parse_args()
    # 目标表/输出路径按预设切换（模块级常量，hue_curve/gray_shift 直接读全局）
    global HUE_TABLE, B_STAR_TABLE, A_STAR_TABLE, OUT, TITLE
    cfg = PRESETS[args.preset]
    HUE_TABLE, B_STAR_TABLE, A_STAR_TABLE = cfg["hue"], cfg["b"], cfg["a"]
    OUT = os.path.join(ROOT, "app/src/main/assets/luts/" + cfg["out"])
    TITLE = cfg["title"]
    lut = build()
    if not args.check:
        write_asset(lut)
    check(lut)
    if args.preview:
        preview(lut, args.preview)


if __name__ == "__main__":
    sys.exit(main())
