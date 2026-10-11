#!/usr/bin/env python3
"""E1-3 编辑器渲染通路（kFsStyle）离线验收：**真跑引擎自己的 shader** 验"通路无损"。

为什么必须真跑（不是 numpy 直译）：ADR §1.4 / §6.5 —— 判据必须来自引擎的 shader 本身。
历史事故：在显示域量的阈值 5.0 拿去比线性域（恒 false，三版全无效）。

验收项（任务卡 E1-3 第 4 条）：
  ① 通路无损：LUT 强度 0 + 颗粒 0 ⇒ 输出 = 输入（逐像素，允许 TPDF dither 的 ±1 LSB）。
     测试图上下强不对称 ⇒ 同时验证行序**没有**被翻转（kFsStyle 刻意无 uFlipY）。
  ② LUT 通路可用：恒等 33³ LUT + 强度 1 ⇒ 输出仍 = 输入（域归一/四面体插值没写错；
     若 LUT 采样代码有坐标/顺序错误，这一项会立刻失败）。
  ③ 颗粒真生效且可复现：颗粒 0.5 ⇒ 输出显著变化（不是死代码），且**两次运行逐像素
     完全一致**（uSeed 固定 ⇒ 报告 R3 的"同一图两次导出颗粒不同"已消除）。
  ④ 结构性检查：passStyle 只读 FilmParams[26]/[27]/[34] ⇒ 其余槽位不可能影响输出
     （ADR §5.1 的 I-2 验收：同一输入 + 只改无关槽位 ⇒ 输出逐像素一致）。
  ⑤ 行序（独立于 ①）：纯"上白下黑"输入 ⇒ 输出同向（首行 255 / 末行 0）。
     为什么单列：kFsOut 有 uFlipY（抵消 kFsStill 自己的预翻转），照抄过来会让编辑器
     输出上下颠倒 —— 这一项就是那个错误的哨兵。

  ⑥ E4-2 暗角默认关（[66]=0）：uVigBrightness=0 ⇒ shader 整段跳过 ⇒ 输出与输入逐位
      一致。**含 [67]/[68] 取极端值的组合**（证明门控只由 [66] 决定，与半径/羽化无关）。
  ⑦ E4-2 暗角开启：中心区域（cplen < dscale）**逐位不变**、角落显著变暗（给出 LSB
      变化量）、沿半径**单调不减**（衰减从中心到角落不回头）。
  ⑧ E4-2 边界：feather=0（fscale 取下限 100/min(W,H)，不得 NaN/不得硬边振铃）、
      strength=-1（乘法系数 ≥ 0 ⇒ 不出现负值）。
      ⚠ ⑥⑦⑧ 只覆盖 `kFsStyle`：`kFsOut` 无离线执行器 ⇒ 拍照链路的暗角靠"与
      kFsStyle 逐字相同 + 真机"保证（两段一致性由本脚本 ⑨ 项做纯文本比对）。
  ⑨ E4-2 一致性：kFsOut / kFsStyle 的暗角段（shader 逻辑 + uniform 声明 + CPU 侧
      下发）逐字相同 ⇒ 上一条"逐字相同"不靠人工目视维持。

  E2-2（基础调整引擎）新增：
  ⑩ 往返无损（本卡技术风险点）：ev=0 / WB=(1,1,1) / contrast=1 / sat=1 / clarity=0
      ⇒ 输出与输入 max|Δ| ≤ 1 LSB（只剩 TPDF dither）。与 ① 同口径，但**显式列出
      五个新参数的默认值** —— E2-2 之后 ① 不再覆盖"新参数是否真的恒等"。
  ⑪ **真 sRGB 曲线**的证据（对照 numpy 解析解）：ev=+1 ⇒ shader 输出必须匹配
      **真 sRGB 分段曲线**的期望值（≤2 LSB），且与 **pow(2.2) 近似**的期望值在暗部
      显著不同（>3 LSB）。这一项就是"暗部往返误差 9/255"事故的哨兵。
  ⑫ 显示域三项真生效（不是死代码）+ 平坦区无副作用：contrast=1.5 显著改图；
      sat=0 ⇒ 输出逐像素 R=G=B；clarity=0.5 ⇒ 噪声/边缘图变化，纯灰图逐位不变。

  E2-6（局部调整引擎）新增：
  ⑬ 局部调整（mask 程序化生成，ADR §5.7）：
     a) 关闭零回归：[79]=0 ⇒ 其余 9 个 local 参数取极端值也只剩 dither（门控只由 [79] 定）；
     b) 径向 mask **真生效且与 ADR 公式一致**：与 numpy 直译的解析期望 max|Δ| ≤ 3 LSB；
        mask 外（d ≥ 1+feather）只剩 dither（≤1 LSB）⇒ 局部曝光段对 m=0 的像素整体跳过；
     c) **边界平滑（无硬边）**：沿半径剖面逐点相邻跳变 ≤ 解析跳变 + 3 LSB、不回升；
     d) 线性 mask 的 [84] 方向真生效（angle=0 ⇒ 条带竖直；π/2 ⇒ 水平；条带外只剩 dither）；
     e) 局部饱和度 [87] 真生效（=0 ⇒ 中心区真灰度、mask 外只剩 dither、与解析期望一致）；
     f) **只有一次往返**的结构性哨兵：kFsStyle 内 `srgbDecode(` 恰好 3 处
        （定义 / chainLuma / 主路径）—— 局部曝光若另起一次往返会立刻变 4。

  E2-7（局部调整 UI）新增：
  ⑬h **正圆修正**（非方形画幅）：径向半径在 CPU 侧拆成 `(rx, ry) = (r, r×H/W)`
     ⇒ mask 的**像素**半径水平与垂直相等。⑬b/⑬c 跑方形画幅 ⇒ 拆分前后数值相同，
     测不出这个 bug；本项用 2:3 竖幅，旧椭圆写法会差 1/3（解析对照也按同式拆开）。

  E2-11（色温/色调 + 影调分区 + 褪色）新增：
  ⑭a 默认零回归：[88..94] 全为恒等值（0）⇒ 输出与输入 max|Δ| ≤ 1 LSB（只剩 dither）。
  ⑭b 色温 [88] ±50 mired ⇒ 与解析期望（Tanner Helland 黑体近似 + 参考点差分）≤3 LSB，
     且方向正确（正 mired ⇒ 画面变冷）；含方向取反的区分度对照。
  ⑭c 色调 [89] ±0.5 ⇒ 同上（正 = 品红 ⇒ G 增益 < 1）。
  ⑭d 影调分区 [90..93]：高光 l²、阴影 s²、白 l³、黑 s³（乘性 luma 权重）四项各一项 +
     四项同开，全部与解析期望 ≤3 LSB，并给出"最大变化发生在哪一档 luma"（分区性证据）。
  ⑭e 褪色 [94]：抬黑场 `lift + c×(1−lift)`，lift = fade×0.25 ⇒ 黑场抬到 32/255、
     白点保持 255，解析对照 ≤3 LSB。
  ⑭f 色温与 [1..3] 是**相乘**（同属线性域增益、并入同一次往返）而不是覆盖；含
     "覆盖 WB"的区分度对照。

  E2-12（裁剪 + 90° 步进旋转，ADR-16）新增：
  ⑮a 不裁剪零回归：crop=(0,0,1,1) + rot=0 ⇒ 输出与输入 max|Δ| ≤ 1 LSB。与 ① 同口径，
     但**显式列出裁剪参数的默认值** —— E2-12 之后 shader 里多了一次 `origin + o×size`
     的乘加，① 不再覆盖"裁剪是否真的恒等"。
  ⑮b **裁剪后暗角仍相对画面居中**（ADR-16 的头号验收项）：纯灰输入 + 暗角，裁中间 50%
     ⇒ 衰减只由**输出**坐标（vUV0）与**输出**尺寸决定，与解析式逐点相符。含区分度
     对照：若暗角误按"源图坐标 + 源图尺寸"算（= 最后裁输出位图的写法），裁剪后画面的
     角落会落在源图中心附近 ⇒ 衰减几乎为 0，差异 100+ LSB。
  ⑮c 90° 步进旋转：4 个方向的画面朝向正确（源左上 → rot=1 时输出**右上**）+ 输出尺寸
     按旋转交换（非方形画幅）。
  ⑮d 裁剪后局部调整中心仍在**用户拖的位置**：非对称裁剪 + 局部中心 (0.5,0.5) ⇒ 输出
     画面中心仍是最暗点（引擎里 localMask 用 vUV0 = 裁剪后坐标）。

用法：python3 tools/check-style-passthrough.py [--size 96]
依赖：moderngl + glfw（与 tools/engine-shader-offline.py 相同）
"""
import argparse
import importlib.util
import math
import os
import pathlib
import re
import sys

import numpy as np

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FR = os.path.join(REPO, 'core/engine/src/render/FrameRenderer.cpp')
ESO = os.path.join(REPO, 'tools/engine-shader-offline.py')
sys.path.insert(0, os.path.join(REPO, 'tools'))
import grain_lut_ref as grain_ref  # noqa: E402  E4-3：相纸响应 LUT 的 numpy 复算


def load_eso():
    """复用 engine-shader-offline.py 的 shader 提取与 GL 执行器（不重复实现）。"""
    spec = importlib.util.spec_from_file_location('eso', ESO)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def make_input(w, h):
    """合成输入：上亮下暗 + 饱和色块 + 细噪声（覆盖暗部/中灰/高光/强边缘）。"""
    rng = np.random.default_rng(7)
    y, x = np.mgrid[0:h, 0:w]
    top = y < h // 2
    ramp = (x / max(w - 1, 1) * 60.0).astype(np.int16)
    img = np.empty((h, w, 3), np.int16)
    img[..., 0] = np.where(top, 220 - ramp, 30 + ramp)
    img[..., 1] = np.where(top, 200, 40)
    img[..., 2] = np.where(top, 180, 50)
    img[h // 2:, : w // 4] = (255, 0, 0)
    img[h // 2:, w // 4: w // 2] = (0, 255, 0)
    img[h // 2:, w // 2: 3 * w // 4] = (0, 0, 255)
    img[h // 2:, 3 * w // 4:] = (0, 0, 0)
    img = img + rng.integers(-3, 4, img.shape)
    return np.clip(img, 0, 255).astype(np.uint8)


def identity_lut(n):
    """恒等 3D LUT，数据布局 = .cube 顺序（R 变化最快 ⇒ flat 下标 = r + g·n + b·n²）。"""
    t = np.linspace(0.0, 1.0, n, dtype=np.float32)
    ib, ig, ir = np.meshgrid(t, t, t, indexing='ij')   # 第 0 维最慢（= b）
    return np.stack([ir, ig, ib], axis=-1).astype(np.float32)


def quant(out_f, shape):
    """浮点输出 → 8bit（等价 glReadPixels RGBA8 的量化）→ 前 3 通道。"""
    return np.clip(np.rint(out_f[..., :3].reshape(shape) * 255.0), 0, 255)


def vig_uniforms(w, h, strength, radius, feather):
    """E4-2 暗角：**与 FrameRenderer::passOutput/passStyle 同式**的 CPU 侧域常数。

    这里刻意重算一遍而不是写死数字 —— ⑥⑦⑧ 同时把 CPU 侧的下发公式也纳入验收。
    """
    vmax, vmin = float(max(w, h)), float(min(w, h))
    return {
        'uVigScaleXY': (w / vmax, h / vmax),
        'uVigDscale': min(max(radius, 0.0), 200.0) / 100.0,
        'uVigFscale': max(feather, 100.0 / vmin) / 100.0,
        'uVigBrightness': min(max(strength, -1.0), 0.0),
    }


def vig_falloff_np(w, h, strength, radius, feather, uv0=(0.0, 0.0), uv1=(1.0, 1.0)):
    """kFsStyle 暗角段的 numpy 直译（与 [vig_uniforms] 同式）。

    w/h = **输出**尺寸（uVigScaleXY 的基准）；uv0/uv1 = 该像素在**源图**里的归一化范围
    （默认全幅 = 不裁剪）。E2-12 的 ⑮b 用它做两件事：
      · 正确实现 ⇒ uv 就是输出坐标（vUV0 覆盖 [0,1]）⇒ 裁剪后暗角仍相对画面居中；
      · 区分度对照 ⇒ 传源图子矩形（"先全幅渲染、最后裁输出位图"的模型）⇒ 数值显著不同。
    """
    y, x = np.mgrid[0:h, 0:w]
    u = uv0[0] + (x + 0.5) / w * (uv1[0] - uv0[0])
    v = uv0[1] + (y + 0.5) / h * (uv1[1] - uv0[1])
    vmax, vmin = float(max(w, h)), float(min(w, h))
    cplen = np.hypot((w / vmax) * np.abs(2.0 * u - 1.0),
                     (h / vmax) * np.abs(2.0 * v - 1.0))
    dscale = min(max(radius, 0.0), 200.0) / 100.0
    fscale = max(feather, 100.0 / vmin) / 100.0
    vw = np.clip((cplen - dscale) / fscale, 0.0, 1.0)
    vw = 0.5 - 0.5 * np.cos(np.pi * vw)
    return 1.0 + vw * min(max(strength, -1.0), 0.0)


def grey_input(w, h, v=180):
    """纯灰输入：暗角是确定性径向增益，纯灰 ⇒ 剖面可逐点核对（dither 只 ±1 LSB）。"""
    return np.full((h, w, 3), v, np.uint8)


def bands_input(w, h):
    """三段灰（暗 40 / 中 128 / 亮 220）—— E4-3 ⑯c 按亮度分箱观测 [60] 的调制曲线。"""
    img = np.empty((h, w, 3), np.uint8)
    img[: h // 3] = 40
    img[h // 3: 2 * h // 3] = 128
    img[2 * h // 3:] = 220
    return img


def cplen_map(w, h):
    """每像素的 darktable 归一化半径（CPU 侧同式）—— 用于"中心区域"掩码。

    uv = 像素中心。暗角径向对称 ⇒ 行序（GL 底行在前）对 cplen 无影响。
    """
    y, x = np.mgrid[0:h, 0:w]
    u = (x + 0.5) / w
    v = (y + 0.5) / h
    vmax = float(max(w, h))
    return np.hypot(w / vmax * np.abs(2.0 * u - 1.0),
                    h / vmax * np.abs(2.0 * v - 1.0))


def wb_gain_from_kelvin(kelvin):
    """E2-11：`FrameRenderer.cpp::wbGainFromKelvin` 的直译（Tanner Helland 黑体近似）。

    与 Kotlin `CameraController.wbGains()` 同式（拍照链路同模型）；唯一差异是 C++ 侧
    分母多了 `max(rn, 1e-3)` 防除零（1500..12000K 内不可达，此处照样保留以逐行对齐）。
    返回"把该色温的光源还原为中性"的增益（G 归一）。
    """
    t = min(max(kelvin, 1500.0), 12000.0) / 100.0
    if t <= 66.0:
        r = 255.0
        g = 99.4708025861 * math.log(t) - 161.1195681661
    else:
        r = 329.698727446 * (t - 60.0) ** -0.1332047592
        g = 288.1221695283 * (t - 60.0) ** -0.0755148492
    if t >= 66.0:
        b = 255.0
    elif t <= 19.0:
        b = 0.0
    else:
        b = 138.5177312231 * math.log(t - 10.0) - 305.0447927307
    rn = min(max(r, 0.0), 255.0)
    gn = min(max(g, 0.0), 255.0)
    bn = max(min(max(b, 0.0), 255.0), 1.0)
    return (min(max(gn / max(rn, 1e-3), 0.3), 3.0), 1.0,
            min(max(gn / bn, 0.3), 3.0))


def temp_tint_gain(mired, tint):
    """E2-11：`FrameRenderer.cpp::tempTintGain` 的直译（[88] mired / [89] tint）。

    参考点（6500K ≈ D65）**差分** ⇒ mired=0 且 tint=0 时严格 (1,1,1)（零回归的根据）。
    方向：mired 是目标白点的 mired 偏移 ⇒ 正 = 目标色温更低（更暖的光源）⇒ 画面变冷。
    """
    k_ref = 6500.0
    m_ref = 1e6 / k_ref
    k_t = 1e6 / max(m_ref + mired, 1.0)
    gt, gr = wb_gain_from_kelvin(k_t), wb_gain_from_kelvin(k_ref)
    t = min(max(tint, -1.0), 1.0)
    return (gt[0] / gr[0] * (1.0 + 0.5 * t), 1.0 * (1.0 - t),
            gt[2] / gr[2] * (1.0 + 0.5 * t))


def tone_expect(inp, *, ev=0.0, wb=(1.0, 1.0, 1.0), contrast=1.0,
                highlights=0.0, shadows=0.0, whites=0.0, blacks=0.0, fade=0.0):
    """E2-11 的解析期望（`kFsStyle` 主流程同序）：往返 → 对比度 → 影调分区 → 褪色。

    ⚠️ 传进来的 `wb` 必须是**含色温/色调折算后的总增益**（= passStyle 的 `wb_tot`），
    因为引擎把 [88][89] 并进了同一次往返 —— 本函数的入参口径必须与下发口径一致。
    影调分区 = 乘性 luma 权重（高光 l²、阴影 s²、白 l³、黑 s³，s = 1−l）；褪色 =
    加性抬黑 `lift + c×(1−lift)`，lift = fade×0.25。饱和度/清晰度不在本函数内
    （它们各自有单测项，混进来会让"哪一段错了"无法归因）。
    """
    x = inp.astype(np.float64) / 255.0
    if ev != 0.0 or tuple(wb) != (1.0, 1.0, 1.0):
        lin = srgb_decode_np(x) * (2.0 ** ev) * np.array(wb)[None, None, :]
        x = srgb_encode_np(lin)
    if contrast != 1.0:
        x = np.clip((x - 0.5) * contrast + 0.5, 0.0, 1.0)
    if highlights or shadows or whites or blacks:
        l = x @ np.array([0.299, 0.587, 0.114])
        s = 1.0 - l
        g = (1.0 + highlights * l * l + shadows * s * s
             + whites * l * l * l + blacks * s * s * s)
        x = x * np.maximum(g, 0.0)[..., None]
    if fade > 0.0:
        lift = fade * 0.25
        x = lift + x * (1.0 - lift)
    return np.clip(x, 0.0, 1.0) * 255.0


def style_uniforms(w, h, ev=0.0, wb=(1.0, 1.0, 1.0), contrast=1.0, sat=1.0,
                   clarity=0.0, local_type=0.0, local_center=(0.5, 0.5),
                   local_radius=0.35, local_feather=0.5, local_angle=0.0,
                   local_width=0.25, local_ev=0.0, local_sat=1.0,
                   temp_mired=0.0, tint=0.0, highlights=0.0, shadows=0.0,
                   whites=0.0, blacks=0.0, fade=0.0,
                   split=(0.0, 0.0), mono=0.0,
                   crop=(0.0, 0.0, 1.0, 1.0), crop_rot=0):
    """E2-2 基础调整 + E2-6 局部调整的 CPU 侧下发值（**与 FrameRenderer::passStyle
    同式**，含门控）。

    门控在 CPU 侧算 ⇒ 默认恒等（ev=0、WB=(1,1,1)、contrast=1、sat=1、clarity=0、
    local_type=0、[88..94]=0）时 shader 内整段跳过。这里刻意重算一遍 ⇒ 本脚本同时
    验收 CPU 侧的下发公式（E2-6 的三个门控：local_on / local_ev_on / local_sat_on；
    E2-7 的 radius 拆分；E2-11 的色温/色调并入 uWbGain + 影调分区门控）。

    ⚠ w/h 是**输出**尺寸（E2-12 起 passStyle 的入参 = 裁剪后的尺寸）：uClarStep 与
    uLocalRadius 的正圆拆分都由它标定 —— 这正是"裁剪后暗角/局部仍正确"的一半原因
    （另一半是 shader 内一律用 vUV0 当坐标基准）。
    """
    radial = 0.5 <= local_type < 1.5
    linear = local_type >= 1.5
    ev_on = local_ev != 0.0
    sat_on = local_sat != 1.0
    # E2-11：色温/色调折算成增益后**并入 [1..3]**（passStyle 同式）⇒ 门控只看 wb_tot。
    tt = (temp_tint_gain(temp_mired, tint)
          if (temp_mired != 0.0 or tint != 0.0) else (1.0, 1.0, 1.0))
    wb_tot = tuple(float(wb[i]) * tt[i] for i in range(3))
    tone_on = (highlights != 0.0 or shadows != 0.0 or whites != 0.0
               or blacks != 0.0)
    # E2-7 正圆修正（passStyle 同式）：rx = [82]（相对图像宽）、ry = [82]×W/H
    #（相对图像高）⇒ shader 内 `length(p/r)` 在**像素空间**等距。
    # ⚠ 这项必须与 C++ 侧逐字同式：⑬b/⑬c 的解析对照就是靠它把"椭圆 bug"照出来的
    #（E2-6 的 ⑬b/⑬c 是方形画幅 ⇒ 拆分前后数值相同，测不出差异；⑬h 用非方形画幅补上）。
    aspect = (float(w) / float(h)) if h > 0 else 1.0
    return {
        'uEv': float(ev),
        'uWbGain': wb_tot,
        'uExpWbOn': 1.0 if (ev != 0.0 or wb_tot != (1.0, 1.0, 1.0)) else 0.0,
        'uContrast': float(contrast),
        'uContrastOn': 1.0 if contrast != 1.0 else 0.0,
        # ★ U-2：[20..22] 三档（替代旧单值 uSat）。本脚本的 `sat` 参数 = "三档同值"
        # （UI 单滑杆的真实形态，ADR §5.6④）⇒ 三个 uniform 下发同一个值。
        # 需要"只开一档"的用例请直接用 run_style 的 uni 覆盖（当前无此需求）。
        'uSatShadow': float(sat),
        'uSatMid': float(sat),
        'uSatHigh': float(sat),
        'uSatOn': 1.0 if sat != 1.0 else 0.0,
        # ★ U-2 胶片曲线 [5..10] + [37][38]：本脚本的既有判据全部在默认值下跑
        # （uCurveOn = 0 ⇒ shader 整段不进）⇒ 只需下发门控；曲线专项目测在
        # `tools/check-u2-editor-curve-sat.py`（那里会算 12 个 5×5 系统并把 34 个
        # uniform 全部下发）。
        'uCurveOn': 0.0,
        'uClarity': float(clarity),
        # 环半径 = max(1px, 0.5% × min(W,H))，转成 UV 步长（passStyle 同式）
        'uClarStep': (max(1.0, 0.005 * min(w, h)) / w,
                      max(1.0, 0.005 * min(w, h)) / h),
        # E2-6 局部调整（passStyle 同式）
        'uLocalOn': 1.0 if ((radial or linear) and (ev_on or sat_on)) else 0.0,
        'uLocalType': float(local_type),
        'uLocalCenter': tuple(float(v) for v in local_center),
        'uLocalRadius': (float(local_radius), float(local_radius) * aspect),
        'uLocalFeather': float(local_feather),
        'uLocalAngle': float(local_angle),
        'uLocalWidth': float(local_width),
        'uLocalEv': float(local_ev),
        'uLocalEvOn': 1.0 if ev_on else 0.0,
        'uLocalSat': float(local_sat),
        'uLocalSatOn': 1.0 if sat_on else 0.0,
        # E2-11 影调分区 + 褪色（passStyle 同式；fade 由 shader 内 uFade>0 自行门控）
        'uHighlights': float(highlights),
        'uShadows': float(shadows),
        'uWhites': float(whites),
        'uBlacks': float(blacks),
        'uFade': float(fade),
        'uToneOn': 1.0 if tone_on else 0.0,
        # U-1 [23][24] 分离色调 + [25] 单色（passStyle / passFilm 同式：**裸值下发**，
        # 门控在 shader 内 = 三槽全默认）⇒ 这里不做任何折算。
        'uSplit': (float(split[0]), float(split[1])),
        'uMono': float(mono),
        # E2-12 裁剪 + 90° 步进旋转（passStyle 同式）：nullptr/全幅 ⇒ (0,0)/(1,1)/0
        # ⇒ shader 内 cropUV 逐位恒等（`0.0 + o × 1.0`）。
        'uCropOrigin': (float(crop[0]), float(crop[1])),
        'uCropSize': (float(crop[2]), float(crop[3])),
        'uCropRot': float(crop_rot),
    }


def crop_out_size(w, h, crop, crop_rot):
    """E2-12 输出尺寸：裁剪矩形的**像素**尺寸 + 旋转交换（与 Kotlin 的
    `CropSpec.outputSize` 同式；4080×3072 裁中间 50% ⇒ 2040×1536，再转 90° ⇒ 1536×2040）。"""
    pw = max(1, int(round(crop[2] * w)))
    ph = max(1, int(round(crop[3] * h)))
    return (ph, pw) if crop_rot % 2 == 1 else (pw, ph)


def run_style(gl, eso, sh, inp, lut_tex, lut_size, *, lut_on, lut_strength,
              grain, grain_seed=0.0, vig=(0.0, 80.0, 50.0), adj=None,
              crop=(0.0, 0.0, 1.0, 1.0), crop_rot=0,
              grain_size=400.0, grain_mb=100.0, grain_pivot=0.0):
    h, w = inp.shape[:2]
    # E2-12：输出尺寸 = 裁剪矩形（+ 旋转交换）。暗角尺度、清晰度环半径、局部半径拆分
    # 全部按**输出**尺寸下发 —— 与 FrameRenderer::passStyle 的 w/h 语义一致。
    out_w, out_h = crop_out_size(w, h, crop, crop_rot)
    # 归一化 RGB 纹理 ≈ 真机 still_tex_（GL_RGB8：上传 8bit，采样得 v/255）；
    # float16 存 v/255 的量化误差 ≤ 0.13 LSB，不影响 ±1 LSB 判定
    tex = gl.ctx.texture((w, h), 3, dtype='f2')
    tex.write((inp.astype(np.float32) / np.float32(255.0)).astype(np.float16).tobytes())
    tex.filter = (eso.moderngl.LINEAR, eso.moderngl.LINEAR)
    uni = {
        'uGrainAmount': grain, 'uGrainSeed': grain_seed,
        # E4-3：尺度与 LUT 坐标按 FrameRenderer::passStyle 的**同式**下发
        # （zoom 由 ISO 换算、norm = 1/短边 —— 短边归一化 ADR-11）。
        'uGrainZoom': grain_ref.grain_zoom(grain_size),
        'uGrainNorm': 1.0 / float(min(out_w, out_h)),
        'uLutOn': lut_on, 'uLutStrength': lut_strength,
        'uLutDomainMin': (0.0, 0.0, 0.0), 'uLutDomainInv': (1.0, 1.0, 1.0),
        'uLutSize': float(lut_size),
    }
    uni.update(vig_uniforms(out_w, out_h, *vig))
    uni.update(style_uniforms(out_w, out_h, **(adj or {}),
                              crop=crop, crop_rot=crop_rot))
    # E4-3：相纸响应 LUT（128² R16F，与 ensureGrainLut 同一份 numpy 复算；
    # 真机纹理是 NEAREST + shader 侧手写双线性，这里照同规格建）。
    gtex = gl.ctx.texture((grain_ref.LUT_SIZE, grain_ref.LUT_SIZE), 1, dtype='f2')
    gtex.write(grain_ref.build_grain_lut(grain_mb, grain_pivot).astype(np.float16).tobytes())
    gtex.filter = (eso.moderngl.NEAREST, eso.moderngl.NEAREST)
    # size=(out_w, out_h) 必传：不传则用执行器初始化时的方形 FBO ⇒ **非方形画幅会被静默
    # 裁成正方形**（⑬h 正圆修正就靠非方形画幅，这里不传等于把判据变成空转）。
    out = gl.run(sh['kFsStyle'], uni,
                 {'uTex': tex, 'uLut': lut_tex, 'uGrainLut': gtex},
                 size=(out_w, out_h))
    tex.release()
    gtex.release()
    return out


def srgb_decode_np(x):
    """真 sRGB EOTF（IEC 61966-2-1）—— 与 kFsStyle 的 srgbDecode 同式。"""
    x = np.clip(x, 0.0, 1.0)
    return np.where(x <= 0.04045, x / 12.92, ((x + 0.055) / 1.055) ** 2.4)


def srgb_encode_np(x):
    """真 sRGB OETF —— 与 kFsOut/kFsStyle 的 srgbEncode 同式。"""
    x = np.clip(x, 0.0, 1.0)
    return np.where(x <= 0.0031308, 12.92 * x, 1.055 * x ** (1.0 / 2.4) - 0.055)


def smoothstep_np(e0, e1, x):
    """GLSL smoothstep 的 numpy 直译（含 e0 == e1 的退化保护，同 shader 的 max）。"""
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def local_mask_np(w, h, type_, center, radius, feather, angle, width):
    """E2-6 局部 mask 的 **ADR §5.7 公式直译**（含 [82]/[83]/[85] 的 1e-3 下限）。

    E2-7 正圆修正：径向的除数是 CPU 侧拆开的 `(rx, ry) = (r, r×W/H)`
    —— 与 `passStyle` / `style_uniforms` **同式**，这样 `length(p/r)` 在像素空间等距
    （旧写法 `hypot(px, py)/r` 是 UV 空间的椭圆）。

    像素中心约定与 shader 的 vUV0 一致：像素 (row=y, col=x) ⇒ uv = ((x+.5)/w, (y+.5)/h)
    —— 本 shader 无 flip（kFsStyle 注释 ②），所以 numpy 行序与 GL 读回行序同向。
    """
    y, x = np.mgrid[0:h, 0:w]
    px = (x + 0.5) / w - center[0]
    py = (y + 0.5) / h - center[1]
    if type_ < 1.5:
        aspect = (float(w) / float(h)) if h > 0 else 1.0
        d = np.hypot(px / max(radius, 1e-3), py / max(radius * aspect, 1e-3))
    else:
        d = np.abs(px * np.cos(angle) + py * np.sin(angle)) / max(width, 1e-3)
    return 1.0 - smoothstep_np(1.0, 1.0 + max(feather, 1e-3), d)


def local_expect(inp, mask, ev_g=0.0, ev_l=0.0, sat_g=1.0, sat_l=1.0):
    """E2-6 的解析期望（kFsStyle 主流程同序）：一次往返（全局+局部曝光合并）→ 饱和度。

    局部曝光 = 线性域 `exp2(ev_g + ev_l × mask)`；局部饱和度 = 有效饱和度按 mask
    在全局与局部之间插值（与 shader 的 `s = s0 + (uLocalSat − s0) × m` 同式）。

    ★ U-2（2026-10-11）两处口径变更，**必须与 kFsStyle 逐字同式**：
      · luma 从 Rec.601 改为 **Rec.709**（分区权重与 mix 中心同源，与 kFsFilm 一致）；
      · 全局饱和度从"单值"改为 **`[20..22]` 三档加权**
        （`s0 = clamp(sat_g·(ws+wm+wh), 0, 2)`，ws = exp(−3l) / wh = smoothstep(0.5,1,l)
        / wm = max(0, 1−ws−wh)）—— 本脚本的参数 `sat_g` 是"三档同值"的简写
        （UI 单滑杆的真实形态：同写三槽 ⇒ 加权和 = 该值，只是权重和在最亮处 1.0498
        而非 1，与 kFsFilm 同款，不是 bug）。
      ⚠ 未改的：**影调分区段**（`tone_expect`）仍用 Rec.601 —— shader 里那一段也没改。
    """
    x = inp.astype(np.float64) / 255.0
    lin = srgb_decode_np(x) * (2.0 ** (ev_g + ev_l * mask))[..., None]
    out = srgb_encode_np(lin)
    if sat_g != 1.0 or sat_l != 1.0:
        l = (out @ np.array([0.2126, 0.7152, 0.0722]))[..., None]
        ws = np.exp(-l * 3.0)
        wh = smoothstep_np(0.5, 1.0, l)
        wm = np.maximum(0.0, 1.0 - ws - wh)
        s0 = np.clip(sat_g * (ws + wm + wh), 0.0, 2.0)
        s = s0 + (sat_l - s0) * mask[..., None]
        out = l + (out - l) * s
    # 与 shader 末尾的 `oColor = vec4(clamp(c, 0.0, 1.0), 1.0)` 同口径
    return np.clip(out, 0.0, 1.0) * 255.0


def code_ladder(w, h):
    """编码域阶梯（0..255，密集覆盖 sRGB 线性段边界 0.04045 ≈ 10.3/255 两侧）。

    ⚠ 必须密集覆盖 **10~14/255**：pow(2.2) 近似与真 sRGB 的最大分歧就在分段点
    附近（pow 没有线性段 ⇒ 把 10/255 解码成 8e-4 而真值是 3.0e-3，差 4 倍），
    这正是"暗部往返误差 9/255"的物理位置。
    """
    vals = np.array([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16, 20, 24,
                     32, 48, 64, 96, 128, 192, 255], np.float32) / np.float32(255.0)
    img = np.empty((h, w, 3), np.float32)
    img[:] = vals[np.arange(w) % len(vals)][None, :, None]
    return img


# 区分度对照用的 pow(2.2) 近似（**绝不允许进引擎**，只用来证明 ⑪ 的判据有效）
POW22_DECODE = """vec3 srgbDecode(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return pow(c, vec3(2.2));
}"""


def extract_fn(src, shader, sig):
    """从 FrameRenderer.cpp 的某个 shader 里抠出一个函数定义（含函数体）。"""
    m = re.search(re.escape(sig) + r' \{.*?\n\}', shader_src(src, shader), re.S)
    return m.group(0) if m else None


def run_fn(gl, eso, inp, fn_src, body):
    """把引擎源码里的函数单独跑一遍：输入 = 编码域 float 图，输出 = RGBA16F 读回。

    为什么不经过 kFsStyle：曲线必须能被**单独观测** —— 混在往返段里时，
    "自洽但错误的曲线对"（pow(2.2) 解码 + pow(1/2.2) 编码）往返也是恒等的，
    单看往返无法区分（这正是 ⑪ 必须直接对照解析解的原因）。
    """
    h, w = inp.shape[:2]
    test = ('#version 300 es\nprecision highp float;\nin vec2 vUV0;\n'
            'layout(location = 0) out vec4 oColor;\nuniform highp sampler2D uTex;\n'
            + fn_src + '\nvoid main() {\n    vec3 c = texture(uTex, vUV0).rgb;\n'
            '    ' + body + '\n}\n')
    tex = gl.ctx.texture((w, h), 3, dtype='f2')
    tex.write(inp.astype(np.float16).tobytes())
    tex.filter = (eso.moderngl.LINEAR, eso.moderngl.LINEAR)
    out = gl.run(test, {}, {'uTex': tex}, size=(w, h))
    tex.release()
    return out[..., :3].astype(np.float64)


def rel_err(got, want):
    """相对误差（RGBA16F 读回的精度是相对 4.9e-4 ⇒ 绝对判据在中高值处不可用）。"""
    return np.abs(got - want) / np.maximum(np.abs(want), 1e-6)



def check_pass_style_slots():
    """passStyle 的槽位**白名单**（精确匹配，不是子集）：只允许读这几项。

    ⚠ 这是"显式声明"式约束：**每新增一个编辑器链路支持的参数，必须同步加进 want**
    （E4-2 暗角 = 加 [66][67][68]；E2-2 基础调整 = 加 [0][1][2][3][4][21][78]）；
    否则 passStyle 读到别处的槽位却没人发现 —— ADR §5.1 的 I-2 要防的正是
    "数组里有值就莫名生效"。
    """
    src = pathlib.Path(FR).read_text(encoding='utf-8')
    m = re.search(r'void FrameRenderer::passStyle\(.*?\n\}', src, re.S)
    if not m:
        return False, set(), 'passStyle 函数体未找到'
    refs = set(re.findall(r'\bp\.(\w+)', m.group(0)))
    want = {'grain_amount', 'grain_size', 'lut_strength',
            # E4-3 颗粒（granularity）：[60] 密度调制（CPU 侧重算相纸 LUT）+
            # [61] 亮度枢轴（同样折进 LUT）。[26]/[27] 见上一行。
            'grain_midtone_bias', 'grain_pivot',
            'vignette_strength', 'vignette_radius', 'vignette_feather',
            # E2-2 基础调整：曝光[0] / WB[1..3]（线性域往返）+ 对比度[4] /
            # 清晰度[78]（显示域）。
            # ★ U-2（2026-10-11）：饱和度从"只读 [21]"扩为 [20..22] 三档
            # （与 kFsFilm 4866-4873 同口径：Rec.709 luma + exp(-3l)/smoothstep/
            # wm 三段权重；默认三槽 = 1.0 ⇒ `sat = 1` ⇒ mix 恒等 ⇒ 逐位零回归）。
            'exposure_ev', 'wb_r', 'wb_g', 'wb_b', 'contrast',
            'sat_shadow', 'sat_mid', 'sat_high',
            'clarity',
            # ★ U-2：[5..10] 趾肩 latitude（数组字段 shoulder[3]/toe[3]）+ [37][38]
            # filmic 黑白点 —— 编辑器侧补"显示域胶片曲线"（`computeEditorCurve` 算
            # 12 个 5×5 系统、kFsStyle 端点重映射 + 差值多项式）。此前 passStyle 零
            # 引用 ⇒ 含这 8 个槽位的预设"在编辑器里没反应"。
            'shoulder', 'toe', 'filmic_black_ev', 'filmic_white_ev',
            # E2-6 局部调整：type[79] / center[80][81] / radius[82] / feather[83]
            # / angle[84] / width[85] / ev[86] / sat[87]。
            'local_type', 'local_center_x', 'local_center_y', 'local_radius',
            'local_feather', 'local_angle', 'local_width', 'local_ev',
            'local_sat',
            # E2-11：色温[88] / 色调[89]（CPU 侧折成增益并入 uWbGain）+ 影调分区
            # [90..93] + 褪色[94]。⚠️ [95] reserved **不在**白名单 —— 它确实零引用，
            # 这正是本项要守的（"每新增一个编辑器链路支持的参数必须显式加进来"）。
            'temp_mired', 'tint', 'highlights', 'shadows', 'whites', 'blacks',
            'fade',
            # U-1：[23][24] 分离色调 + [25] 单色（用户 2026-10-11"不统一的要统一掉"）。
            # 此前 passStyle 零引用 ⇒ 含 [25] 的"黑白预设"在编辑器里不变黑白。
            'split_warm', 'split_cool', 'monochrome',
            # E4-5 halation（光晕）：passStyle 里只读 [62]（门控 = 是否跑金字塔链）。
            # ⚠ 另三个参数（[63] 阈值 / [64] 半径 / [65] 暖度）在 passStyle 调用的
            #   `runHalation` 内读取 —— 那是**两条链路共享**的函数（passOutput 也调
            #   它）⇒ 四参数的生效性与单调性由 tools/check-halation.py 覆盖。
            'halation_strength'}
    return refs == want, refs, ''


def shader_src(src, name):
    """从 FrameRenderer.cpp 抽出某个 shader 的 GLSL 源码。"""
    m = re.search(r'const char\* ' + name + r' = R"\((.*?)\)";', src, re.S)
    return m.group(1) if m else ''


def slice_from(text, start, end):
    i = text.index(start)
    return text[i: text.index(end, i) + len(end)]


def check_grain_parity():
    """E4-3 ⑨：两条链路的颗粒实现必须**逐字相同**（shader 函数段 / uniform 声明 /
    主段 / CPU 下发段）。

    为什么必须一致：颗粒是"最终观感层"—— 预览（kFsOut 预览路径）、成片（kFsOut）、
    编辑器（kFsStyle）三处看到的图案必须同一套数学，否则所见非所得。E4-2 暗角用同一
    机制守住了，颗粒照做（这是设计上的直接收益：改一处必须改两处，漏了立刻红）。
    """
    src = pathlib.Path(FR).read_text(encoding='utf-8')
    sh_start = '// ── E4-3 颗粒（granularity；darktable src/iop/grain.c 逐行移植）'
    sh_end = '    return mix(xy0, xy1, f.x);\n}'
    un_start = '// ── E4-3 颗粒（granularity；darktable src/iop/grain.c）'
    un_end = 'uniform highp sampler2D uGrainLut;  // 128² R16F 相纸响应 LUT（CPU 侧按 [60][61] 重算）'
    mn_start = '// ── E4-3 颗粒（granularity，darktable grain.c）'
    mn_end = 'c += grainLutFetch(vec2(grainNoise(gp, uGrainZoom) * strength * 0.15, l)) * 0.01;\n    }'
    pairs = [
        ('shader 颗粒函数段',
         slice_from(shader_src(src, 'kFsOut'), sh_start, sh_end),
         slice_from(shader_src(src, 'kFsStyle'), sh_start, sh_end)),
        ('uniform 声明',
         slice_from(shader_src(src, 'kFsOut'), un_start, un_end),
         slice_from(shader_src(src, 'kFsStyle'), un_start, un_end)),
        ('shader 主段',
         slice_from(shader_src(src, 'kFsOut'), mn_start, mn_end),
         slice_from(shader_src(src, 'kFsStyle'), mn_start, mn_end)),
    ]
    # CPU 侧下发段：仅 u_out_ / u_style_ 前缀不同 ⇒ 归一后逐字比对
    cpu = []
    for fn in ('passOutput', 'passStyle'):
        body = re.search(r'void FrameRenderer::' + fn + r'\(.*?\n\}', src, re.S).group(0)
        body = body.replace('u_out_', 'u_xxx_').replace('u_style_', 'u_xxx_')
        seg = slice_from(body, '// ── E4-3 颗粒：CPU 侧算好尺度常数',
                         'glUniform1i(u_xxx_.grain_lut, 2);')
        cpu.append(seg)
    pairs.append(('CPU 下发段', cpu[0], cpu[1]))
    return [name for name, a, b in pairs if a != b]


def check_vignette_parity():
    """E4-2 ⑨：两条链路的暗角实现必须**逐字相同**（shader 段 / uniform 声明 / CPU 下发）。

    差异应为**零**：本实现两处都用 `vUV0`（径向对称 ⇒ 与 kFsOut 里 flip 后的 uv 等价），
    所以连"uv 变量来源"这一点差异都不存在。
    """
    src = pathlib.Path(FR).read_text(encoding='utf-8')
    sh_start = '// ── E4-2 暗角（vignette，darktable'
    un_start = '// ── E4-2 暗角（vignette；darktable'
    sh_end = 'c *= (1.0 + vw * uVigBrightness);\n    }'
    un_end = 'uniform float uVigBrightness;'
    pairs = [
        ('shader 暗角段',
         slice_from(shader_src(src, 'kFsOut'), sh_start, sh_end),
         slice_from(shader_src(src, 'kFsStyle'), sh_start, sh_end)),
        ('uniform 声明',
         slice_from(shader_src(src, 'kFsOut'), un_start, un_end),
         slice_from(shader_src(src, 'kFsStyle'), un_start, un_end)),
    ]
    # CPU 侧下发段：仅 u_out_ / u_style_ 前缀不同 ⇒ 归一后逐字比对
    cpu = []
    for fn in ('passOutput', 'passStyle'):
        body = re.search(r'void FrameRenderer::' + fn + r'\(.*?\n\}', src, re.S).group(0)
        seg = slice_from(body, '// E4-2 暗角（vignette）：域常数在 CPU 侧算好',
                         'std::clamp(p.vignette_strength, -1.0f, 0.0f));')
        cpu.append(seg.replace('u_out_', 'u_xxx_').replace('u_style_', 'u_xxx_'))
    pairs.append(('CPU 下发段', cpu[0], cpu[1]))
    return [name for name, a, b in pairs if a != b]


def check_split_mono_parity():
    """U-1 ⑰d：`kFsFilm` 与 `kFsStyle` 的 [23][24][25] 数学必须**逐字相同**。

    与 ⑨（暗角）/ ⑨b（颗粒）同一手法：这是"效果对齐"的直接证据 —— 比"两个 shader 各跑
    一遍比对数字"更强（域不同、数字本来就不会逐位相等；但**公式**必须逐字相同）。
    比对四段（变量名归一化 out3/c 后）：
      · `float luma(vec3 c)` 定义（718 权重）· ws/wh 权重定义 · split 加法偏移 · mono 去色。
    """
    src = pathlib.Path(FR).read_text(encoding='utf-8')
    film, style = shader_src(src, 'kFsFilm'), shader_src(src, 'kFsStyle')

    def grab(text):
        """抠出四段原文；split/mono 段把左值变量名（out3 / c）归一化成 X 后比对。"""
        pats = {
            'luma 定义': r'float luma\(vec3 c\) \{ return dot\(c, vec3\(0\.2126, '
                         r'0\.7152, 0\.0722\)\); \}',
            'ws/wh 权重': r'float ws = exp\(-l \* 3\.0\);\s*\n\s*'
                          r'float wh = smoothstep\(0\.5, 1\.0, l\);',
            'split 加法': r'(\w+) \+= uSplit\.x \* ws \* vec3\(0\.05, 0\.02, -0\.04\)\s*\n\s*'
                          r'\+ uSplit\.y \* wh \* vec3\(-0\.03, 0\.0, 0\.05\);',
            'mono 去色': r'(\w+) = mix\(\1, vec3\(luma\(clamp\(\1, 0\.0, 4\.0\)\)\), uMono\);',
        }
        out = {}
        for k, p in pats.items():
            m = re.search(p, text)
            if not m:
                out[k] = None
                continue
            seg = re.sub(r'\s+', ' ', m.group(0))
            out[k] = (re.sub(r'\b%s\b' % re.escape(m.group(1)), 'X', seg)
                      if m.groups() else seg)
        return out

    a, b = grab(film), grab(style)
    return [f'{k}（film={a[k]!r} vs style={b[k]!r}）' for k in a if a[k] != b[k]]


def split_mono_np(c0, split, mono):
    """kFsStyle 的 U-1 段 numpy 直译（输入/输出都是 **显示域编码值 0..1**）。"""
    c = c0.astype(np.float64).copy()
    l = c @ np.array([0.2126, 0.7152, 0.0722])
    ws = np.exp(-l * 3.0)
    wh = smoothstep_np(0.5, 1.0, l)
    c = c + (split[0] * ws)[..., None] * np.array([0.05, 0.02, -0.04]) \
          + (split[1] * wh)[..., None] * np.array([-0.03, 0.0, 0.05])
    lum = np.clip(c, 0.0, 4.0) @ np.array([0.2126, 0.7152, 0.0722])
    return c + (lum[..., None] - c) * mono


def expect8(c01):
    """解析期望 → **8bit 输出口径**（与 [quant] 同式：rint + clamp 到 0..255）。

    ⚠ 这一步不能省：shader 末尾是 `clamp(c, 0.0, 1.0)` + RGBA8 量化 ⇒ 解析期望若停在
    浮点域，"超白被截断"的段落（如 luma250 吃满 +0.05 的高光冷偏移）会差出 7.7 LSB，
    看起来像实现错了（本卡第一次跑就踩到）。它不是实现差异，是 8bit 边界的本征行为。
    """
    return np.clip(np.rint(np.asarray(c01) * 255.0), 0, 255)


def grey_steps(w, h, vals):
    """横向灰阶阶梯（每段一个编码值 0..255）—— U-1 ⑰c 的"分区"判据要看**逐段**偏移。"""
    img = np.empty((h, w, 3), np.uint8)
    seg = max(1, w // len(vals))
    for i, v in enumerate(vals):
        img[:, i * seg: (i + 1) * seg] = v
    img[:, seg * len(vals):] = vals[-1]
    return img


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--size', type=int, default=96, help='测试图边长')
    args = ap.parse_args()
    n = args.size
    # ⑬h 正圆修正用的**非方形**画幅（2:3 竖幅 ≈ 竖拍 3:4 的同类场景）：
    # 方形画幅下"UV 空间椭圆"与"像素空间正圆"数值完全相同 ⇒ 必须换画幅才测得出来。
    n2w, n2h = n, (n * 3) // 2

    eso = load_eso()
    sh = eso.extract_shaders()
    if 'kFsStyle' not in sh:
        print('FAIL 未能从 FrameRenderer.cpp 提取 kFsStyle')
        return 1

    inp = make_input(n, n)
    gl = eso.GL(n, n)
    fails = []

    # 关闭态占位（uLutOn=0 时 shader 不采样，但纹理必须绑定 —— Mali 上未绑定的
    # sampler3D 会被预测执行采样，见 bindLut 注释）
    lut_off = gl.ctx.texture3d((1, 1, 1), 3, dtype='f2')
    lut_off.write(np.zeros((1, 1, 1, 3), np.float16).tobytes())
    lut_id = gl.ctx.texture3d((33, 33, 33), 3, dtype='f2')   # 恒等 33³（真机 LUT 尺寸）
    lut_id.write(identity_lut(33).astype(np.float16).tobytes())

    # ① 通路无损：LUT 0 + 颗粒 0
    o1 = run_style(gl, eso, sh, inp, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                   grain=0.0)
    q1 = quant(o1, inp.shape)
    d1 = np.abs(q1 - inp.astype(np.float64))
    bad1 = int((d1 > 1).sum())
    print(f'① 通路无损（LUT=0 颗粒=0）：max|Δ| = {d1.max():.0f} LSB, '
          f'>1LSB 像素 = {bad1}/{d1.size}, 零差占比 = {(d1 == 0).mean() * 100:.1f}%')
    if bad1:
        fails.append('① 通路无损')

    # ② 恒等 LUT + 强度 1
    o2 = run_style(gl, eso, sh, inp, lut_id, 33, lut_on=1.0, lut_strength=1.0,
                   grain=0.0)
    q2 = quant(o2, inp.shape)
    d2 = np.abs(q2 - inp.astype(np.float64))
    bad2 = int((d2 > 1).sum())
    print(f'② 恒等 33³ LUT（强度 1）：max|Δ| = {d2.max():.0f} LSB, '
          f'>1LSB 像素 = {bad2}/{d2.size}')
    if bad2:
        fails.append('② 恒等 LUT')

    # ③ 颗粒生效 + 可复现
    # ⚠ E4-3 语义变更：[26] 现在是 darktable 的 `strength`（0..100，进 LUT 时 ×0.15），
    # 旧测试传 0.5 在新语义下 = 强度 0.005 ⇒ 几乎不可见。用 darktable 的默认强度 25。
    o3 = run_style(gl, eso, sh, inp, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                   grain=25.0)
    o3b = run_style(gl, eso, sh, inp, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                    grain=25.0)
    q3 = quant(o3, inp.shape)
    changed = float((q3 != inp).mean()) * 100.0
    same_run = bool(np.array_equal(o3, o3b))
    print(f'③ 颗粒 0.5：改动像素 = {changed:.1f}%（应显著 > 0）, '
          f'两次运行逐像素一致 = {same_run}')
    if changed < 5.0 or not same_run:
        fails.append('③ 颗粒生效/可复现')

    # ④ 结构性检查（无关槽位不可能串味）
    ok4, refs, err4 = check_pass_style_slots()
    print(f'④ passStyle 读到的 FilmParams 槽位 = {sorted(refs)}'
          + (f'  [{err4}]' if err4 else ''))
    if not ok4:
        fails.append('④ 槽位隔离')

    # ⑤ 行序（独立哨兵）：上白下黑 ⇒ 输出必须同向
    half = np.zeros((n, n, 3), np.uint8)
    half[: n // 2] = 255
    o5 = run_style(gl, eso, sh, half, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                   grain=0.0)
    q5 = quant(o5, half.shape)
    top_m, bot_m = float(q5[: n // 2].mean()), float(q5[n // 2:].mean())
    print(f'⑤ 行序（上白下黑输入）：输出上半均值 = {top_m:.1f}（应 ≈255）, '
          f'下半均值 = {bot_m:.1f}（应 ≈0）, 首行 = {q5[0].mean():.0f}, '
          f'末行 = {q5[-1].mean():.0f}')
    if top_m < 250.0 or bot_m > 5.0:
        fails.append('⑤ 行序')

    # ⑥ E4-2 暗角默认关：[66]=0 ⇒ uVigBrightness=0 ⇒ shader 整段跳过。
    # 三种组合一起跑，证明门控**只由 [66] 决定**（与 [67]/[68] 无关，[66]>0 也被 clamp 到 0）。
    worst6, bad6 = 0.0, 0
    for vig in ((0.0, 80.0, 50.0), (0.0, 200.0, 0.0), (0.5, 120.0, 10.0)):
        o6 = run_style(gl, eso, sh, inp, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                       grain=0.0, vig=vig)
        d6 = np.abs(quant(o6, inp.shape) - inp.astype(np.float64))
        worst6 = max(worst6, float(d6.max()))
        bad6 += int((d6 > 1).sum())
    print(f'⑥ 暗角默认关（[66]=0；另两种组合 [67]/[68] 极端 + [66]=+0.5）：'
          f'max|Δ| = {worst6:.0f} LSB, >1LSB 像素 = {bad6}')
    if bad6:
        fails.append('⑥ 暗角默认关')

    # ⑦ 暗角开启（纯灰 180 ⇒ 剖面可逐点核对）：中心逐位不变 / 角落显著变暗 / 沿半径单调
    g180 = grey_input(n, n, 180)
    o7 = run_style(gl, eso, sh, g180, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                   grain=0.0, vig=(-0.8, 80.0, 50.0))
    q7 = quant(o7, g180.shape)[..., 0]
    cl = cplen_map(n, n)
    dscale, fscale = 0.8, 0.5                       # [67]=80 / [68]=50 的期望值
    centre = cl <= dscale - 0.3                     # 留 0.3 余量：确定在 falloff 起点内
    corner = np.zeros_like(q7, bool)
    corner[:4, :4] = corner[:4, -4:] = corner[-4:, :4] = corner[-4:, -4:] = True
    dc = float(np.abs(q7[centre] - 180.0).max())
    c_cen, c_cor = float(q7[centre].mean()), float(q7[corner].mean())
    prof = [float(q7[(cl >= a) & (cl < b)].mean())
            for a, b in ((0.0, 0.4), (0.4, 0.8), (0.8, 1.0), (1.0, 1.2), (1.2, 1.45))
            if ((cl >= a) & (cl < b)).any()]
    dprof = np.diff(prof)
    diag = q7.astype(np.float64)[np.arange(n // 2, n), np.arange(n // 2, n)]
    ddiag = np.diff(diag)
    print(f'⑦ 暗角开启（[66]=-0.8 [67]=80 [68]=50，输入 180 灰）：'
          f'中心区（cplen≤{dscale - 0.3:.1f}，{int(centre.sum())}px）max|Δ| = {dc:.0f} LSB, '
          f'中心均值 = {c_cen:.1f}, 角落均值 = {c_cor:.1f}（Δ = {180 - c_cor:.1f} LSB，'
          f'理论 180×(1-0.8) = 36.0）')
    print(f'   沿半径分桶均值（cplen 0→0.4/0.4→0.8/0.8→1.0/1.0→1.2/1.2→1.45）：'
          f'{" / ".join(f"{v:.1f}" for v in prof)}；分桶回升次数 = '
          f'{int((dprof > 1.0).sum())}, 对角线逐点回升次数 = {int((ddiag > 1.0).sum())}')
    if dc > 1.0 or c_cor > 180.0 * 0.4 or (dprof > 1.0).any() or (ddiag > 1.0).any():
        fails.append('⑦ 暗角开启')

    # ⑧ 边界：feather=0（fscale 取到下限 100/min(W,H)，不得 NaN/不得振铃）
    #          strength=-1（falloff = 1-weight ∈ [0,1] ⇒ 不得出现负值）
    o8a = run_style(gl, eso, sh, g180, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                    grain=0.0, vig=(-0.6, 80.0, 0.0))
    q8a = quant(o8a, g180.shape)[..., 0]
    f8 = np.isfinite(o8a).all()
    diag8 = q8a.astype(np.float64)[np.arange(n // 2, n), np.arange(n // 2, n)]
    jump = float(np.abs(np.diff(diag8)).max())
    o8b = run_style(gl, eso, sh, g180, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                    grain=0.0, vig=(-1.0, 80.0, 50.0))
    q8b = quant(o8b, g180.shape)[..., 0]
    # 过渡宽度（解析，单位 = 像素）：fscale ÷ (每像素的 cplen 步长 2/max(W,H))。
    # 下限 100/min(W,H) 的语义 = "过渡不窄于 min 边的 1%" ⇒ 在 max/min 的常见画幅上
    # 只有 0.5~1.3 px —— 即 darktable 同款保护防的是**亚像素跳变（混叠）**，不是让
    # 渐变变宽；8bit 上这一步台阶是本征行为。判据取"无过冲"而非"无台阶"。
    span_px = (max(0.0, 100.0 / n) / 100.0) / (2.0 / float(n))
    over8 = 180.0 * 0.6 + 1.0
    print(f'⑧ 边界：feather=0 ⇒ fscale = max(0, 100/{n})/100 = {max(0.0, 100.0 / n) / 100.0:.4f}'
          f'（下限生效，非 0）, 过渡宽度（解析）= {span_px:.2f} px, 输出 finite = {bool(f8)}, '
          f'min = {q8a.min():.0f}, 中心区 max|Δ| = '
          f'{float(np.abs(q8a[centre] - 180.0).max()):.0f} LSB, 角落均值 = {float(q8a[corner].mean()):.1f}')
    print(f'   对角线最大相邻跳变 = {jump:.0f} LSB（理论全幅度 180×0.6 = 108；'
          f'过冲上限 {over8:.0f} ⇒ {"无振铃" if jump <= over8 else "有过冲"}）')
    print(f'   strength=-1 ⇒ falloff = 1-weight ∈ [0,1]：输出 min = {q8b.min():.0f}（不得为负）, '
          f'角落均值 = {float(q8b[corner].mean()):.1f}（应 ≈0）, finite = {bool(np.isfinite(o8b).all())}')
    if (not f8) or q8a.min() < 0 or float(np.abs(q8a[centre] - 180.0).max()) > 1.0 \
            or float(q8a[corner].mean()) > 180.0 * 0.5 or (np.diff(diag8) > 1.0).any() \
            or jump > over8 or span_px < 0.4 \
            or q8b.min() < 0 or float(q8b[corner].mean()) > 1.0:
        fails.append('⑧ 边界')

    # ⑨ 两处暗角代码逐字一致（shader 段 / uniform 声明 / CPU 下发段）
    diff9 = check_vignette_parity()
    print(f'⑨ kFsOut 与 kFsStyle 的暗角实现逐字比对：差异段 = {diff9 or "无（0 处）"}')
    if diff9:
        fails.append('⑨ 两处一致性')

    # ⑨b E4-3 颗粒：两条链路逐字一致（shader 函数段 / uniform 声明 / 主段 / CPU 下发段）
    diff9b = check_grain_parity()
    print(f'⑨b kFsOut 与 kFsStyle 的颗粒实现逐字比对：差异段 = {diff9b or "无（0 处）"}')
    if diff9b:
        fails.append('⑨b 颗粒两处一致性')

    # ⑩ E2-2 往返无损（本卡技术风险点）：五个新参数取默认值 ⇒ 输出 = 输入。
    # 与 ① 同口径，但**显式列出五个参数的默认值**（E2-2 之后 ① 只覆盖 LUT/颗粒）。
    o10 = run_style(gl, eso, sh, inp, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                    grain=0.0, adj=dict(ev=0.0, wb=(1.0, 1.0, 1.0),
                                        contrast=1.0, sat=1.0, clarity=0.0))
    q10 = quant(o10, inp.shape)
    d10 = np.abs(q10 - inp.astype(np.float64))
    bad10 = int((d10 > 1).sum())
    print(f'⑩ E2-2 往返无损（ev=0 WB=(1,1,1) ct=1 sat=1 clr=0）：'
          f'max|Δ| = {d10.max():.0f} LSB, >1LSB 像素 = {bad10}/{d10.size}, '
          f'零差占比 = {(d10 == 0).mean() * 100:.1f}%')
    if bad10:
        fails.append('⑩ 往返无损')

    # ⑪ 真 sRGB 曲线（本卡门禁）：把引擎源码里的 srgbDecode / srgbEncode **单独抠出来
    #    跑**，对照 numpy 解析解。为什么不能只看往返：自洽但错误的曲线对
    #    （pow(2.2) 解码 + pow(1/2.2) 编码）往返同样恒等 —— 单看往返**无区分度**。
    #    末项用 pow(2.2) 近似做区分度对照（ADR §6.5：判据定案前量化区分度）。
    src_txt = pathlib.Path(FR).read_text(encoding='utf-8')
    lad = code_ladder(n, n)
    dec_src = extract_fn(src_txt, 'kFsStyle', 'vec3 srgbDecode(vec3 c)')
    enc_src = extract_fn(src_txt, 'kFsOut', 'vec3 srgbEncode(vec3 c)')
    if dec_src is None or enc_src is None:
        print('⑪ FAIL 未能从 FrameRenderer.cpp 抠出 srgbDecode / srgbEncode')
        fails.append('⑪ 真 sRGB 曲线')
    else:
        want_dec = srgb_decode_np(lad.astype(np.float64))
        want_enc = srgb_encode_np(lad.astype(np.float64))
        rel_dec = rel_err(run_fn(gl, eso, lad, dec_src,
                                 'oColor = vec4(srgbDecode(c), 1.0);'), want_dec)
        rel_enc = rel_err(run_fn(gl, eso, lad, enc_src,
                                 'oColor = vec4(srgbEncode(c), 1.0);'), want_enc)
        # 跨 shader 往返：kFsOut.srgbEncode → kFsStyle.srgbDecode 必须严格互逆
        rt = run_fn(gl, eso, lad, enc_src + '\n' + dec_src,
                    'oColor = vec4(srgbDecode(srgbEncode(c)), 1.0);')
        d_rt = np.abs(rt - lad.astype(np.float64))
        # 区分度对照：decode 换成 pow(2.2) ⇒ 相对误差必须显著（否则判据无效）
        rel_pow = rel_err(run_fn(gl, eso, lad, POW22_DECODE,
                                 'oColor = vec4(srgbDecode(c), 1.0);'), want_dec)
        print(f'⑪ 真 sRGB 曲线（抠出源码单独跑 vs numpy 解析解，相对误差）：'
              f'srgbDecode max = {rel_dec.max():.2e}, '
              f'srgbEncode max = {rel_enc.max():.2e}'
              f'（判据 ≤2e-3 = RGBA16F 读回精度 4.9e-4 的 4 倍）')
        print(f'   跨 shader 往返（kFsOut.encode → kFsStyle.decode）max|Δ| = '
              f'{d_rt.max() * 255:.2f} LSB（判据 ≤1）｜ 区分度对照（decode 换 pow(2.2)）：'
              f'相对误差 max = {rel_pow.max():.2e}（须 >2e-3）')
        if rel_dec.max() > 2e-3 or rel_enc.max() > 2e-3 or d_rt.max() * 255 > 1.0 \
                or rel_pow.max() <= 2e-3:
            fails.append('⑪ 真 sRGB 曲线')

    # ⑫ 显示域三项真生效（不是死代码）+ 平坦区无副作用
    o12c = run_style(gl, eso, sh, inp, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                     grain=0.0, adj=dict(contrast=1.5))
    ch12c = float((quant(o12c, inp.shape) != inp).mean()) * 100.0
    o12s = run_style(gl, eso, sh, inp, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                     grain=0.0, adj=dict(sat=0.0))
    q12s = quant(o12s, inp.shape)
    d_rg = float(np.abs(q12s[..., 0] - q12s[..., 1]).max())
    d_gb = float(np.abs(q12s[..., 1] - q12s[..., 2]).max())
    o12k = run_style(gl, eso, sh, inp, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                     grain=0.0, adj=dict(clarity=0.5))
    q12k = quant(o12k, inp.shape)
    ch12k = float((q12k != inp).mean()) * 100.0
    d12k = float(np.abs(q12k - inp.astype(np.float64)).max())
    o12f = run_style(gl, eso, sh, g180, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                     grain=0.0, adj=dict(clarity=0.5))
    d12f = np.abs(quant(o12f, g180.shape) - 180.0)
    print(f'⑫ 显示域三项：contrast=1.5 ⇒ 改动像素 = {ch12c:.1f}%；'
          f'sat=0 ⇒ max|R−G| = {d_rg:.0f}, max|G−B| = {d_gb:.0f}（应为 0 ⇒ 真灰度）；'
          f'clarity=0.5 ⇒ 噪声图改动 = {ch12k:.1f}%（max|Δ| = {d12k:.0f} LSB）, '
          f'纯灰图 max|Δ| = {d12f.max():.0f} LSB（平坦区无副作用）')
    if ch12c < 30.0 or d_rg > 1.0 or d_gb > 1.0 or ch12k < 5.0 or d12f.max() > 1.0:
        fails.append('⑫ 显示域三项')

    # ⑬ E2-6 局部调整（本卡门禁）：mask 真生效 + **边界平滑（无硬边）** + 关闭零回归
    #    + 线性方向真生效 + 局部饱和度真生效 + **只有一次往返**的结构性证明。
    #    判据 = numpy 直译 ADR §5.7 的 mask 公式做**解析对照**（不是"看起来变暗了"）。
    # ⑬a 关闭零回归：[79]=0（默认）⇒ mask 恒 0 ⇒ 其余 9 个 local 参数取极端值也无效
    o13a = run_style(gl, eso, sh, inp, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                     grain=0.0,
                     adj=dict(local_type=0.0, local_center=(0.1, 0.9),
                              local_radius=0.05, local_feather=0.0,
                              local_angle=1.0, local_width=0.02,
                              local_ev=3.0, local_sat=0.0))
    d13a = np.abs(quant(o13a, inp.shape) - inp.astype(np.float64))
    bad13a = int((d13a > 1).sum())
    print(f'⑬a 局部调整关闭（[79]=0；其余 9 个 local 参数取极端值）：'
          f'max|Δ| = {d13a.max():.0f} LSB, >1LSB 像素 = {bad13a}/{d13a.size}')
    if bad13a:
        fails.append('⑬a 局部关闭零回归')

    # ⑬b 径向 mask 真生效 + 解析对照（纯灰 180 ⇒ 剖面可逐点核对；ev=-1.5 压暗）
    r13, f13, cx13 = 0.30, 0.50, (0.5, 0.5)
    m13 = local_mask_np(n, n, 1, cx13, r13, f13, 0.0, 0.25)
    o13b = run_style(gl, eso, sh, g180, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                     grain=0.0,
                     adj=dict(local_type=1.0, local_center=cx13,
                              local_radius=r13, local_feather=f13,
                              local_ev=-1.5))
    q13b = quant(o13b, g180.shape)[..., 0]
    exp13b = local_expect(g180, m13, ev_l=-1.5)[..., 0]
    d13b = np.abs(q13b - exp13b)
    inside, outside = m13 > 0.999, m13 < 1e-9
    d13b_out = float(np.abs(q13b[outside] - 180.0).max())
    print(f'⑬b 径向 mask（[82]={r13} [83]={f13} [86]=-1.5，输入 180 灰）：'
          f'与解析期望 max|Δ| = {d13b.max():.1f} LSB（判据 ≤3 = dither±1 + 量化余量）；'
          f'中心区（{int(inside.sum())}px）均值 = {q13b[inside].mean():.1f}'
          f'（解析 {exp13b[inside].mean():.1f}，输入 180）；'
          f'mask 外（{int(outside.sum())}px）max|Δ| = {d13b_out:.0f} LSB'
          f'（应 ≤1 = 只剩 dither，证明局部段对 m=0 的像素整体跳过）')

    # ⑬c 边界平滑（无硬边）：沿过中心的水平剖面，逐点相邻跳变 ≤ 解析跳变 + dither。
    #     方向：负 EV ⇒ 中心最暗、向外单调**变亮** ⇒ 异常是"向外回落"（< −2 LSB）。
    row13 = n // 2
    prof13 = q13b[row13, n // 2:].astype(np.float64)
    eprof13 = exp13b[row13, n // 2:]
    jump13, ejump13 = np.abs(np.diff(prof13)), np.abs(np.diff(eprof13))
    drop13 = int((np.diff(prof13) < -2.0).sum())
    # 分桶均值（压掉 dither）沿半径单调不回落 —— 与 ⑦ 同款口径
    rmap13 = np.hypot((np.mgrid[0:n, 0:n][1] + 0.5) / n - cx13[0],
                      (np.mgrid[0:n, 0:n][0] + 0.5) / n - cx13[1]) / r13
    buckets13 = [float(q13b[(rmap13 >= a) & (rmap13 < b)].mean())
                 for a, b in ((0.0, 0.5), (0.5, 1.0), (1.0, 1.25), (1.25, 1.5),
                              (1.5, 2.5)) if ((rmap13 >= a) & (rmap13 < b)).any()]
    print(f'⑬c 边界平滑（同一径向剖面，中心→右边缘）：实测最大相邻跳变 = '
          f'{jump13.max():.1f} LSB, 解析期望 = {ejump13.max():.1f} LSB'
          f'（判据 ≤ 解析 + 3 = dither 余量 ⇒ 无硬边）；向外回落（< −2 LSB）次数 = '
          f'{drop13}；沿半径分桶均值（d/r = 0→0.5/0.5→1/1→1.25/1.25→1.5/1.5→2.5）：'
          f'{" / ".join(f"{v:.1f}" for v in buckets13)}')
    if (d13b.max() > 3.0 or d13b_out > 1.0
            or jump13.max() > ejump13.max() + 3.0 or drop13 > 0
            or any(np.diff(buckets13) < -0.5)):
        fails.append('⑬b/c 径向 mask')

    # ⑬c2 端点边界：[83]=0（ADR 允许的端点 ⇒ shader 内 max(_,1e-3) 下限生效）与
    #      [82]=0（半径 0 ⇒ 除中心像素外 mask 恒 0）—— 不得 NaN / 不得负值 /
    #      解析对照仍成立（ADR §6.5：边界必须实测，不靠"应该没问题"）。
    edge13 = []
    for lbl, rad, fea in (('[83]=0（硬边端点）', 0.30, 0.0),
                          ('[82]=0（半径 0）', 0.0, 0.5)):
        oe = run_style(gl, eso, sh, g180, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                       grain=0.0,
                       adj=dict(local_type=1.0, local_center=(0.5, 0.5),
                                local_radius=rad, local_feather=fea,
                                local_ev=-2.0))
        qe = quant(oe, g180.shape)[..., 0]
        ee = local_expect(g180, local_mask_np(n, n, 1, (0.5, 0.5), rad, fea, 0.0,
                                              0.25), ev_l=-2.0)[..., 0]
        de = float(np.abs(qe - ee).max())
        fin = bool(np.isfinite(oe).all())
        edge13.append(fin and qe.min() >= 0.0 and de <= 3.0)
        print(f'⑬c2 端点边界 {lbl}：finite = {fin}, min = {qe.min():.0f}'
              f'（不得为负）, 与解析期望 max|Δ| = {de:.1f} LSB（判据 ≤3）')
    if not all(edge13):
        fails.append('⑬c2 端点边界')

    # ⑬d 线性 mask：dir 是**条带法线** ⇒ angle=0 时条带竖直（左右边缘不变、中心列变），
    #     angle=π/2 时条带水平（上下边缘不变、中心行变）—— 证明 [84] 真的生效
    ok13d = []
    for ang, name, edge, mid in ((0.0, 'angle=0（条带竖直）', np.s_[:, :n // 8],
                                  np.s_[:, n // 2]),
                                 (np.pi / 2, 'angle=π/2（条带水平）', np.s_[:n // 8, :],
                                  np.s_[n // 2, :])):
        o13d = run_style(gl, eso, sh, g180, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                         grain=0.0,
                         adj=dict(local_type=2.0, local_center=(0.5, 0.5),
                                  local_width=0.25, local_feather=0.5,
                                  local_angle=ang, local_ev=-1.5))
        q13d = quant(o13d, g180.shape)[..., 0]
        m13d = local_mask_np(n, n, 2, (0.5, 0.5), 0.3, 0.5, ang, 0.25)
        e13d = local_expect(g180, m13d, ev_l=-1.5)[..., 0]
        de = float(np.abs(q13d - e13d).max())
        d_edge = float(np.abs(q13d[edge] - 180.0).max())
        d_mid = float(np.abs(q13d[mid] - 180.0).max())
        ok13d.append(de <= 3.0 and d_edge <= 1.0 and d_mid > 20.0)
        print(f'⑬d 线性 mask {name}：与解析期望 max|Δ| = {de:.1f} LSB；'
              f'条带外边缘区 max|Δ| = {d_edge:.0f} LSB（应 ≤1 = 只剩 dither）；'
              f'中心列/行 max|Δ| = {d_mid:.1f} LSB（应显著 > 20）')
    if not all(ok13d):
        fails.append('⑬d 线性 mask')

    # ⑬e 局部饱和度（显示域，按 mask 混合；[87]=0 ⇒ 局部完全去饱和）
    m13e = local_mask_np(n, n, 1, (0.5, 0.5), 0.30, 0.5, 0.0, 0.25)
    o13e = run_style(gl, eso, sh, inp, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                     grain=0.0,
                     adj=dict(local_type=1.0, local_center=(0.5, 0.5),
                              local_radius=0.30, local_feather=0.5, local_sat=0.0))
    q13e = quant(o13e, inp.shape)
    in13e, out13e = m13e > 0.999, m13e < 1e-9
    rg13 = float(np.abs(q13e[..., 0] - q13e[..., 1])[in13e].max())
    gb13 = float(np.abs(q13e[..., 1] - q13e[..., 2])[in13e].max())
    d13e_out = float(np.abs(q13e[out13e] - inp.astype(np.float64)[out13e]).max())
    e13e = local_expect(inp, m13e, sat_l=0.0)
    d13e = float(np.abs(q13e - e13e).max())
    print(f'⑬e 局部饱和度（[87]=0，径向）：中心区 max|R−G| = {rg13:.0f}, '
          f'max|G−B| = {gb13:.0f}（应为 ≤1 ⇒ 局部真灰度）；mask 外 max|Δ| = '
          f'{d13e_out:.0f} LSB（应 ≤1 = 只剩 dither）；与解析期望 max|Δ| = '
          f'{d13e:.1f} LSB')
    if rg13 > 1.0 or gb13 > 1.0 or d13e_out > 1.0 or d13e > 3.0:
        fails.append('⑬e 局部饱和度')

    # ⑬g 全局与局部**合并**（不是覆盖、也不是两次往返）：全局 ev=+0.5 与局部 ev=−1.0
    #     同时非零 ⇒ 解析期望 = exp2(0.5 − 1.0×mask)。若实现成"局部覆盖全局"或
    #     "全局/局部各做一次往返"，解析对照会偏离。饱和度同理（全局 1.5 + 局部 0
    #     ⇒ 有效饱和度按 mask 在 1.5 与 0 之间插值）。
    o13g = run_style(gl, eso, sh, g180, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                     grain=0.0,
                     adj=dict(ev=0.5, local_type=1.0, local_center=(0.5, 0.5),
                              local_radius=0.30, local_feather=0.5,
                              local_ev=-1.0))
    q13g = quant(o13g, g180.shape)[..., 0]
    e13g = local_expect(g180, m13, ev_g=0.5, ev_l=-1.0)[..., 0]
    d13g = float(np.abs(q13g - e13g).max())
    o13g2 = run_style(gl, eso, sh, inp, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                      grain=0.0,
                      adj=dict(sat=1.5, local_type=1.0, local_center=(0.5, 0.5),
                               local_radius=0.30, local_feather=0.5,
                               local_sat=0.0))
    d13g2 = float(np.abs(quant(o13g2, inp.shape)
                         - local_expect(inp, m13, sat_g=1.5, sat_l=0.0)).max())
    print(f'⑬g 全局+局部合并：ev 全局 +0.5 与局部 −1.0 同开 ⇒ 与解析期望（一次往返、'
          f'指数相加）max|Δ| = {d13g:.1f} LSB；饱和度全局 1.5 + 局部 0 ⇒ '
          f'max|Δ| = {d13g2:.1f} LSB（判据均 ≤3）')
    if d13g > 3.0 or d13g2 > 3.0:
        fails.append('⑬g 全局+局部合并')

    # ⑬h **正圆修正**（E2-7 裁决 1，非方形画幅）：⑬b/⑬c 跑的是方形画幅 ⇒ 拆分前后数值
    #     完全相同，**测不出**这个 bug。这里用 64×96 的竖幅：判据是"mask 的像素半径在
    #     水平与垂直方向相等"—— 旧写法（UV 空间椭圆）在 2:3 画幅下垂直半径会小 1/3，
    #     这一项会直接失败。同时给出"与解析期望 max|Δ|"（解析侧已按同式拆开）。
    gh13 = grey_input(n2w, n2h, 180)
    # [82] 取 0.20（不是 ⑬b 的 0.30）：判据要求**半强度点（d = 1+feather/2）落在画内**，
    # 竖幅的 y 方向最大只能到 d = 0.5/(r×H/W) ⇒ r < 0.25。取 0.20 ⇒ d_max = 1.875
    #（内边界 1.0 与外边界 1.5 都在画内，两个方向都能量到同一个 d）。
    r13h = 0.20
    o13h = run_style(gl, eso, sh, gh13, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                     grain=0.0,
                     adj=dict(local_type=1.0, local_center=(0.5, 0.5),
                              local_radius=r13h, local_feather=f13,
                              local_ev=-1.5))
    m13h = local_mask_np(n2w, n2h, 1, (0.5, 0.5), r13h, f13, 0.0, 0.25)
    q13h = quant(o13h, gh13.shape)[..., 0]
    e13h = local_expect(gh13, m13h, ev_l=-1.5)[..., 0]
    d13h = float(np.abs(q13h - e13h).max())
    # 实测像素半径：从中心沿水平/垂直方向找 **mask 半强度点（d = 1 + feather/2）** 的
    # 穿越位置。判据 = 两个方向的像素半径相等（正圆）。旧写法（UV 空间椭圆）在 2:3
    # 画幅下垂直半径会小 1/3，这一项会直接失败。
    c13y, c13x = n2h // 2, n2w // 2
    row13h = q13h[c13y, c13x:].astype(np.float64)
    col13h = q13h[c13y:, c13x].astype(np.float64)
    # 半强度亮度：mask 内（m = 1）与 mask 外（m = 0）的中点。两者都取**解析值**：
    #  - m = 1 ⇒ 输入 180 经 sRGB 解码 × 2^−1.5 再编码（= 112，不是 180/2^1.5 = 63.6 ——
    #    显示域亮度必须走 sRGB 曲线，直接按线性比例算会把基准压低 30 LSB，
    #    于是"第一个像素就跌破基准"⇒ 实测半径恒 0）；
    #  - m = 0 ⇒ 180（⑬b 已验证 mask 外 ≤1 LSB）。
    inside13 = float(srgb_encode_np(srgb_decode_np(np.array(180.0 / 255.0)) * 2.0 ** -1.5) * 255.0)
    half13 = 0.5 * (inside13 + 180.0)

    def half_cross(prof, half_val):
        """沿剖面（从中心向外）找**第一次升过**半强度的位置。

        剖面形状：mask 内 = 暗（局部压暗）、向外单调**变亮**到输入值。所以穿越点 =
        第一个 ≥ 半强度的采样点（半径按此计）。两个坑都踩过：
         - `argmin(|prof − half|)` 会在**剖面末端**取到最小值（外侧恒为 180 > 半强度）
           ⇒ 量到的是画幅边界；
         - `nonzero(prof < half)` 会命中**剖面开头**（mask 内本来就比半强度暗）⇒ 恒 0。
        """
        idx = np.nonzero(prof >= half_val)[0]
        return float(idx[0]) if len(idx) else float(len(prof) - 1)

    prx13 = half_cross(row13h, half13)
    pry13 = half_cross(col13h, half13)
    # 解析侧的同口径半径（像素）：**正圆** ⇒ 两个方向的半强度半径都等于
    # mid × r × **宽**（半强度在 d = 1 + feather/2；而 d 的像素尺度 = r × 宽，
    # 与方向无关 —— 这正是"像素空间等距"的定义）。
    # 旧写法（UV 空间椭圆）在 2:3 画幅下垂直半径会**大** H/W = 1.5 倍：ry_px = mid × r × H
    #（实测 36px vs 正圆的 24px —— 已用"改回椭圆"的负对照验证过本项确实会失败）。
    mid13 = 1.0 + 0.5 * f13
    prx_expect = mid13 * r13h * n2w
    pry_expect = mid13 * r13h * n2w
    pry_ellipse = mid13 * r13h * n2h
    print(f'⑬h 正圆修正（{n2w}×{n2h} 竖幅，[82]={r13h} [83]={f13}）：与解析期望 '
          f'max|Δ| = {d13h:.1f} LSB（判据 ≤3）；实测半强度像素半径 水平 = {prx13:.0f}px / '
          f'垂直 = {pry13:.0f}px（正圆 ⇒ 都应为 {prx_expect:.0f}px；'
          f'旧椭圆写法垂直会是 {pry_ellipse:.0f}px）')
    if (d13h > 3.0 or abs(prx13 - pry13) > 2.0
            or abs(prx13 - prx_expect) > 2.0 or abs(pry13 - pry_expect) > 2.0):
        fails.append('⑬h 正圆修正')

    # ⑬f 结构性证明：**没有第二次 decode/encode**。kFsStyle 内 `srgbDecode(` 只应有
    #     3 处：函数定义 + chainLuma 的标量往返 + main 主路径往返。局部曝光若被写成
    #     独立的第二次往返，这里会立刻变 4 ⇒ 这就是 ADR-7「一次往返」的哨兵。
    n_dec = shader_src(src_txt, 'kFsStyle').count('srgbDecode(')
    print(f'⑬f kFsStyle 内 srgbDecode( 出现次数 = {n_dec}'
          f'（应为 3 = 定义 1 + chainLuma 1 + 主路径 1 ⇒ 全局与局部曝光共用同一次往返）')
    if n_dec != 3:
        fails.append('⑬f 单次往返')

    # ══ E2-11（色温/色调 + 影调分区 + 褪色）═════════════════════════════════════
    # 判据一律 = 与 numpy 解析期望（本文件顶部 temp_tint_gain / tone_expect，与 C++ /
    # shader 逐行同式）的 max|Δ| ≤ 3 LSB（dither ±1 + 量化余量）。
    # ⚠ `code_ladder` 返回的是 **0..1 的 float**（⑪ 直接喂 `run_fn` 用）；而
    # `run_style` / `tone_expect` 的输入口径是 **uint8 0..255** ⇒ 必须转回来，否则
    # 整图接近全黑、⑭d/⑭e 的"作用域"判据全变 0 = 空转（实测踩过一次）。
    lad14 = np.clip(np.rint(code_ladder(n, n) * 255.0), 0, 255).astype(np.uint8)
    vals14 = lad14[0, :, 0].astype(np.float64)      # 每列的编码值（0..255 循环）
    i_blk, i_wht = int(np.argmin(vals14)), int(np.argmax(vals14))

    # ⑭a 默认零回归：8 个新槽位全为恒等值 ⇒ 输出与输入 ≤1 LSB（只剩 dither）。
    #     与 ①/⑩ 同口径，但**显式列出 [88..94]** —— E2-11 之后 ① 不再覆盖"新参数是否
    #     真的恒等"（ADR-14 硬约束 2 的哨兵）。
    o14a = run_style(gl, eso, sh, inp, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                     grain=0.0,
                     adj=dict(temp_mired=0.0, tint=0.0, highlights=0.0, shadows=0.0,
                              whites=0.0, blacks=0.0, fade=0.0))
    d14a = np.abs(quant(o14a, inp.shape) - inp.astype(np.float64))
    bad14a = int((d14a > 1).sum())
    print(f'⑭a E2-11 默认零回归（[88..94] 全 0）：max|Δ| = {d14a.max():.0f} LSB, '
          f'>1LSB 像素 = {bad14a}/{d14a.size}, 零差占比 = {(d14a == 0).mean() * 100:.1f}%')
    if bad14a:
        fails.append('⑭a E2-11 零回归')

    # ⑭b 色温 [88]：±50 mired ⇒ 与解析期望对照 + **方向判据**（正 mired = 目标色温更低
    #     = 更暖的光源 ⇒ 画面变冷 ⇒ B/R 增益比 > 1）。末项是区分度对照：把方向写反
    #     （增益取倒数）后与实测的偏离必须 > 3 LSB，否则"方向判据"是空转。
    ok14b, d_rev = [], 0.0
    for mired in (50.0, -50.0):
        o = run_style(gl, eso, sh, inp, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                      grain=0.0, adj=dict(temp_mired=mired))
        q = quant(o, inp.shape)
        wb_tot = temp_tint_gain(mired, 0.0)
        d = float(np.abs(q - tone_expect(inp, wb=wb_tot)).max())
        br = wb_tot[2] / wb_tot[0]
        ok14b.append(d <= 3.0 and (br > 1.0 if mired > 0 else br < 1.0))
        print(f'⑭b 色温 [88]={mired:+.0f} mired ⇒ 折算增益 = ({wb_tot[0]:.4f}, 1, '
              f'{wb_tot[2]:.4f})，B/R = {br:.4f}（判据：正 mired ⇒ >1 = 偏冷 / '
              f'负 mired ⇒ <1 = 偏暖）；与解析期望 max|Δ| = {d:.1f} LSB（判据 ≤3）')
        if mired > 0:
            d_rev = float(np.abs(q - tone_expect(
                inp, wb=tuple(1.0 / v for v in wb_tot))).max())
    print(f'   区分度对照（方向取反的期望）：max|Δ| = {d_rev:.1f} LSB（须 >3 ⇒ 判据有效）')
    if not all(ok14b) or d_rev <= 3.0:
        fails.append('⑭b 色温')

    # ⑭c 色调 [89]：±0.5 ⇒ 与解析期望对照 + 方向判据（正 = 品红 ⇒ G 增益 < 1）
    ok14c = []
    for tint in (0.5, -0.5):
        o = run_style(gl, eso, sh, inp, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                      grain=0.0, adj=dict(tint=tint))
        g = temp_tint_gain(0.0, tint)
        d = float(np.abs(quant(o, inp.shape) - tone_expect(inp, wb=g)).max())
        ok14c.append(d <= 3.0 and (g[1] < 1.0 if tint > 0 else g[1] > 1.0))
        print(f'⑭c 色调 [89]={tint:+.1f} ⇒ 折算增益 = ({g[0]:.4f}, {g[1]:.4f}, '
              f'{g[2]:.4f})（G {"<" if g[1] < 1 else ">"} 1 = '
              f'{"品红" if tint > 0 else "绿"}）；与解析期望 max|Δ| = {d:.1f} LSB（判据 ≤3）')
    if not all(ok14c):
        fails.append('⑭c 色调')

    # ⑭d 影调分区 [90..93]：四项各一项 + 四项同开，全部与解析期望对照；并给出**作用域**
    #     数字（最大变化发生在哪一档 luma）—— "分区"必须真的分区，不是整体提亮。
    ok14d = []
    for name, adj in (('高光 [90]=+0.5', dict(highlights=0.5)),
                      ('阴影 [91]=+0.5', dict(shadows=0.5)),
                      ('白 [92]=−0.5', dict(whites=-0.5)),
                      ('黑 [93]=−0.5', dict(blacks=-0.5)),
                      ('四项同开', dict(highlights=0.5, shadows=0.5, whites=-0.5,
                                    blacks=-0.5))):
        o = run_style(gl, eso, sh, lad14, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                      grain=0.0, adj=adj)
        e = tone_expect(lad14, **adj)
        d = float(np.abs(quant(o, lad14.shape) - e).max())
        cd = np.abs(e - lad14.astype(np.float64)).mean(axis=(0, 2))   # 每列的平均变化
        j = int(cd.argmax())
        ok14d.append(d <= 3.0 and cd.max() > 5.0)
        print(f'⑭d 影调分区 {name}：与解析期望 max|Δ| = {d:.1f} LSB（判据 ≤3）；'
              f'最大变化 {cd.max():.1f} LSB 发生在 luma = {vals14[j]:.0f}/255，'
              f'两端（luma 0 / 255）变化 = {cd[i_blk]:.1f} / {cd[i_wht]:.1f} LSB')
    if not all(ok14d):
        fails.append('⑭d 影调分区')

    # ⑭e 褪色 [94]：fade=0.5 ⇒ 黑场抬到 0.125（≈32/255）、白点保持 255；解析对照 ≤3。
    o14e = run_style(gl, eso, sh, lad14, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                     grain=0.0, adj=dict(fade=0.5))
    q14e = quant(o14e, lad14.shape)
    e14e = tone_expect(lad14, fade=0.5)
    d14e = float(np.abs(q14e - e14e).max())
    blk_got = float(q14e[:, i_blk, 0].mean())
    wht_got = float(q14e[:, i_wht, 0].mean())
    print(f'⑭e 褪色 [94]=0.5（抬黑场）：与解析期望 max|Δ| = {d14e:.1f} LSB（判据 ≤3）；'
          f'luma 0 列实测 = {blk_got:.1f}（期望 {0.5 * 0.25 * 255:.1f}）, '
          f'luma 255 列实测 = {wht_got:.1f}（期望 255 ⇒ 白点不动）')
    if d14e > 3.0 or abs(blk_got - 31.9) > 2.0 or abs(wht_got - 255.0) > 2.0:
        fails.append('⑭e 褪色')

    # ⑭f 色温与 [1..3] 是**相乘**（同属线性域增益），不是覆盖：wb=(1.2,1,0.9) +
    #     [88]=+50 ⇒ 期望 = wb × tt。末项是区分度对照：若实现成"色温覆盖 WB"会差多少。
    wb14 = (1.2, 1.0, 0.9)
    o14f = run_style(gl, eso, sh, inp, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                     grain=0.0, adj=dict(wb=wb14, temp_mired=50.0))
    q14f = quant(o14f, inp.shape)
    tt14 = temp_tint_gain(50.0, 0.0)
    wb_tot14 = tuple(wb14[i] * tt14[i] for i in range(3))
    d14f = float(np.abs(q14f - tone_expect(inp, wb=wb_tot14)).max())
    d_cov = float(np.abs(q14f - tone_expect(inp, wb=tt14)).max())
    print(f'⑭f 色温 × WB 相乘（wb=(1.2,1,0.9) + [88]=+50）：总增益 = '
          f'({wb_tot14[0]:.4f}, {wb_tot14[1]:.4f}, {wb_tot14[2]:.4f}），与解析期望 '
          f'max|Δ| = {d14f:.1f} LSB（判据 ≤3）；区分度对照（若为"覆盖 WB"）'
          f'max|Δ| = {d_cov:.1f} LSB（须 >3）')
    if d14f > 3.0 or d_cov <= 3.0:
        fails.append('⑭f 色温×WB 相乘')

    # ── E2-12 裁剪 + 90° 步进旋转（ADR-16）──────────────────────────────────
    full = (0.0, 0.0, 1.0, 1.0)

    # ⑮a 不裁剪（默认全幅）零回归：crop=(0,0,1,1) + rot=0 ⇒ 输出与输入 max|Δ| ≤ 1 LSB。
    o15a = run_style(gl, eso, sh, inp, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                     grain=0.0, crop=full, crop_rot=0)
    d15a = np.abs(quant(o15a, inp.shape) - inp.astype(np.float64))
    print(f'⑮a 不裁剪零回归（crop=(0,0,1,1) rot=0，LUT/颗粒关）：max|Δ| = '
          f'{d15a.max():.0f} LSB, >1LSB 像素 = {(d15a > 1).sum()}/{d15a.size}'
          f'（判据 ≤1）')
    if d15a.max() > 1.0:
        fails.append('⑮a 不裁剪零回归')

    # ⑮b 裁剪后暗角仍相对画面居中（ADR-16 头号验收项）
    vig15 = (-0.8, 80.0, 50.0)
    crop15 = (0.25, 0.25, 0.5, 0.5)
    cw15, ch15 = crop_out_size(n, n, crop15, 0)
    g15 = grey_input(n, n)
    o15b = run_style(gl, eso, sh, g15, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                     grain=0.0, vig=vig15, crop=crop15, crop_rot=0)
    q15b = quant(o15b, (ch15, cw15, 3))
    want15b = 180.0 * vig_falloff_np(cw15, ch15, *vig15)
    d15b = float(np.abs(q15b[..., 0] - want15b).max())
    # 区分度对照：暗角若按"源图坐标 + 源图尺寸"算（最后裁输出位图）
    wrong15b = 180.0 * vig_falloff_np(cw15, ch15, *vig15,
                                      uv0=(crop15[0], crop15[1]),
                                      uv1=(crop15[0] + crop15[2], crop15[1] + crop15[3]))
    d15b_wrong = float(np.abs(q15b[..., 0] - wrong15b).max())
    # 中心-角落的衰减幅度（人可读的证据）
    cen15 = float(q15b[ch15 // 2, cw15 // 2, 0])
    cor15 = float(q15b[0, 0, 0])
    print(f'⑮b 裁剪后暗角居中（裁中间 50% ⇒ 输出 {cw15}×{ch15}，纯灰 180 + 暗角 '
          f'[66]=-0.8 [67]=80 [68]=50）：与**按输出坐标/输出尺寸**的解析式 max|Δ| = '
          f'{d15b:.0f} LSB（判据 ≤3）；区分度对照（若按源图坐标算 = 最后裁输出位图）'
          f'max|Δ| = {d15b_wrong:.0f} LSB（须 >3）')
    print(f'   裁剪后画面：中心 = {cen15:.0f}（无衰减 ⇒ 应 ≈180）、角落 = {cor15:.0f}'
          f'（满衰减 ⇒ 应 ≈{180 * 0.2:.0f}）⇒ 暗角仍相对**裁剪后**画面居中')
    if d15b > 3.0 or d15b_wrong <= 3.0:
        fails.append('⑮b 裁剪后暗角居中')

    # ⑮c 90° 步进旋转：画面朝向 + 输出尺寸交换（非方形画幅 n2w×n2h）
    #     四象限图：只有**源图左上**是 255，其余 100 ⇒ 每个 rot 下最亮象限的位置唯一。
    quad = np.full((n2h, n2w, 3), 100, np.uint8)
    quad[:n2h // 2, :n2w // 2] = 255
    ok15c = True
    # rot=0 ⇒ 左上；1 ⇒ 右上；2 ⇒ 右下；3 ⇒ 左下（顺时针）
    want_quad = {0: (0, 0), 1: (0, 1), 2: (1, 1), 3: (1, 0)}
    for rot in (0, 1, 2, 3):
        o = run_style(gl, eso, sh, quad, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                      grain=0.0, crop=full, crop_rot=rot)
        ow, oh = crop_out_size(n2w, n2h, full, rot)
        q = quant(o, (oh, ow, 3))
        # 四象限均值 → 最亮象限（行, 列）
        qs = np.array([[q[:oh // 2, :ow // 2, 0].mean(), q[:oh // 2, ow // 2:, 0].mean()],
                       [q[oh // 2:, :ow // 2, 0].mean(), q[oh // 2:, ow // 2:, 0].mean()]])
        got = tuple(int(v) for v in np.unravel_index(int(qs.argmax()), qs.shape))
        exp = want_quad[rot]
        size_ok = (ow, oh) == ((n2h, n2w) if rot % 2 == 1 else (n2w, n2h))
        ok15c = ok15c and (got == exp) and size_ok
        print(f'⑮c 旋转 {rot * 90}°：输出尺寸 = {ow}×{oh}'
              f'（{"交换 ✓" if size_ok else "错 ✗"}），源左上亮块落在'
              f'{"左上/右上/右下/左下"[rot * 3:rot * 3 + 3]}'
              f'（实测 {got} / 期望 {exp} {"✓" if got == exp else "✗"}）')
    if not ok15c:
        fails.append('⑮c 90° 旋转方向/尺寸')

    # ⑮d 裁剪后局部调整中心仍在**用户拖的位置**（非对称裁剪 ⇒ 才测得出来）
    crop15d = (0.10, 0.10, 0.50, 0.50)
    dw15, dh15 = crop_out_size(n, n, crop15d, 0)
    o15d = run_style(gl, eso, sh, g15, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                     grain=0.0, crop=crop15d, crop_rot=0,
                     adj=dict(local_type=1.0, local_center=(0.5, 0.5),
                              local_radius=0.25, local_feather=0.3, local_ev=-1.5))
    q15d = quant(o15d, (dh15, dw15, 3))[..., 0]
    # 判据 1：画面中心（vUV0=(0.5,0.5)）的压暗量与解析期望逐点相符
    mask15 = local_mask_np(dw15, dh15, 1.0, (0.5, 0.5), 0.25, 0.3, 0.0, 0.25)
    want15d = local_expect(g15[:dh15, :dw15], mask15, ev_l=-1.5)
    d15d = float(np.abs(q15d - want15d[..., 0]).max())
    # 判据 2：最暗点必须落在 mask 内（半径 0.25×48 = 12px，dither ±1 ⇒ 圆内多点并列最暗，
    #        所以只要求"在半径内"，不要求恰好是中心像素）
    cen = (int(q15d.argmin()) // dw15, int(q15d.argmin()) % dw15)
    rad_px = 0.25 * dw15
    near = math.hypot(cen[0] - dh15 / 2.0, cen[1] - dw15 / 2.0) <= rad_px + 1.0
    cen_val = float(q15d[dh15 // 2, dw15 // 2])
    # 区分度对照：局部中心若被当成**源图坐标**解释（中心会跑到输出的 (0.8,0.8) 处）
    yy, xx = np.mgrid[0:dh15, 0:dw15]
    su = crop15d[0] + (xx + 0.5) / dw15 * crop15d[2]
    sv = crop15d[1] + (yy + 0.5) / dh15 * crop15d[3]
    d_wrong = np.hypot((su - 0.5) / 0.25, (sv - 0.5) / 0.25)
    mask_wrong = 1.0 - smoothstep_np(1.0, 1.3, d_wrong)
    want_wrong = local_expect(g15[:dh15, :dw15], mask_wrong, ev_l=-1.5)
    d15d_wrong = float(np.abs(q15d - want_wrong[..., 0]).max())
    print(f'⑮d 裁剪后局部中心（非对称裁剪 (0.10,0.10,0.50,0.50) ⇒ 输出 {dw15}×{dh15}，'
          f'局部中心 (0.5,0.5) 径向 ev=-1.5）：与解析期望 max|Δ| = {d15d:.0f} LSB'
          f'（判据 ≤3）；中心实测 {cen_val:.0f}（期望 '
          f'{float(want15d[dh15 // 2, dw15 // 2, 0]):.0f}）；最暗点 = {cen}'
          f'（mask 半径 {rad_px:.0f}px 内 {"✓" if near else "✗"}）；'
          f'区分度对照（局部中心若按**源图坐标**解释）max|Δ| = {d15d_wrong:.0f} LSB（须 >3）')
    if d15d > 3.0 or not near or d15d_wrong <= 3.0:
        fails.append('⑮d 裁剪后局部中心')

    # ══ E4-3 颗粒（granularity，darktable grain.c）════════════════════════════
    # ⑯a 默认零回归（ADR-10 红线）：[26]=0 ⇒ 整段跳过。**含 [60]/[61] 取极端值**，
    # 证明门控只由 [26] 决定（否则"关着颗粒却因为 [60] 变了而改画面"）。
    o16a = run_style(gl, eso, sh, inp, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                     grain=0.0, grain_mb=0.0, grain_pivot=1.0)
    q16a = quant(o16a, inp.shape)
    d16a = np.abs(q16a - inp.astype(np.float64))
    bad16a = int((d16a > 1).sum())
    print(f'⑯a 颗粒 [26]=0（且 [60]=0、[61]=1 极端值）：max|Δ| = {d16a.max():.0f} LSB，'
          f'>1LSB 像素 = {bad16a}/{d16a.size} ⇒ 门控只由 [26] 决定')
    if bad16a:
        fails.append('⑯a 颗粒默认零回归')

    # ⑯b [27] 单调 / 短边归一化 / 功率谱形态：**不在本脚本测**。
    # 为什么：噪声坐标按短边归一化（ADR-11）⇒ 特征周期 = zoom/f × 短边像素数。
    # 本脚本默认 n=96，此时最低频八度的周期 ≈ 0.25 px（ISO 20）~ 0.83 px（ISO 6400）
    # —— **全部欠采样**，四档 ISO 在 96px 上都退化成逐像素白噪声，指标无区分度
    #（实测差分/总量比 1.998/2.011/2.009/2.008，非单调）。这不是实现缺陷，是判据
    # 与分辨率的匹配问题：真机短边 3072px 下同一 ISO 档的周期是 8.1~26.6 px。
    # ⇒ 尺度类判据（[27] 单调、短边归一化、FFT 径向平均谱、按亮度分箱 RMS）放在
    #    tools/grain-exp/grain-exp.py（专用离线验收，用 1024² 以上的图 + 真跑 shader）。
    # 本脚本保留与分辨率无关的两条：⑯a 默认零回归、⑯c 调制曲线形状。

    # ⑯c [60] 生效：mb=100（默认）⇒ 中间调最重、两端 falloff 强；mb=0 ⇒ 曲线更宽
    #（两端衰减弱）。输入 = 三段灰（暗 40 / 中 128 / 亮 220），比较各段颗粒 RMS。
    # 判据：① mb=100 时中间段 RMS 最大（两端趋 0）；② mb=0 的**两端/中间比**更大
    #（= 覆盖整个影调范围）⇒ 两条曲线形状确实不同。
    b_in = bands_input(n, n)
    seg = ((0, n // 3), (n // 3, 2 * n // 3), (2 * n // 3, n))
    prof = {}
    for mb in (0.0, 100.0):
        oo = run_style(gl, eso, sh, b_in, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                       grain=50.0, grain_mb=mb)
        inc = oo[..., 0].astype(np.float64) - b_in[..., 0].astype(np.float64) / 255.0
        prof[mb] = [float(inc[r0:r1].std()) for r0, r1 in seg]
    mid_peak = prof[100.0][1] > prof[100.0][0] and prof[100.0][1] > prof[100.0][2]
    edge_ratio0 = (prof[0.0][0] + prof[0.0][2]) / max(prof[0.0][1], 1e-9)
    edge_ratio100 = (prof[100.0][0] + prof[100.0][2]) / max(prof[100.0][1], 1e-9)
    print(f'⑯c [60] 调制曲线：mb=100 三段 RMS = ' +
          '/'.join(f'{v * 255:.2f}' for v in prof[100.0]) +
          f'（暗/中/亮，中间最大 = {mid_peak}）；mb=0 = ' +
          '/'.join(f'{v * 255:.2f}' for v in prof[0.0]) +
          f'；两端/中间比：mb=0 → {edge_ratio0:.3f} > mb=100 → {edge_ratio100:.3f}'
          f' = {edge_ratio0 > edge_ratio100}')
    if not mid_peak or not (edge_ratio0 > edge_ratio100):
        fails.append('⑯c [60] 调制曲线')

    # ══ U-1（[23][24] 分离色调 + [25] 单色）════════════════════════════════════
    # 用户 2026-10-11："影调重建拍照侧和编辑器侧不一样？不统一的要统一掉，缺的补上。"
    # 本段四个判据：零回归（逐位）/ 单色生效 / 分离色调生效+分区 / 与 kFsFilm 逐字同式。

    # ⑰a **零回归（硬判据）**：三槽默认 ⇒ 与"把 U-1 段整段删掉的 shader"**逐位相同**。
    # 为什么不能只测"与输入 ≤1 LSB"（①⑩⑭a 那种口径）：dither 是 ±1 LSB 的确定性噪声，
    # 它会掩盖"新增段在默认值下真的没跑"这一点。这里的"删段 shader"就是"这段不存在"的
    # **可执行定义** ⇒ 逐位相同 = 门控短路且对链路其它段零影响（不依赖 git 旧版本）。
    style_src = shader_src(src_txt, 'kFsStyle')
    u1_start = '    if (uSplit.x != 0.0'
    u1_end = 'c = mix(c, vec3(luma(clamp(c, 0.0, 4.0))), uMono);\n    }'
    u1_seg = (slice_from(style_src, u1_start, u1_end)
              if u1_start in style_src and u1_end in style_src else '')
    if 'uSplit' not in u1_seg:
        print('⑰a FAIL 未能从 kFsStyle 定位 U-1 主段（源码结构变了）')
        fails.append('⑰a U-1 零回归')
    else:
        sh_no_u1 = {'kFsStyle': style_src.replace(u1_seg, '')}
        # 参数取"非平凡"：LUT 开 + 颗粒开 + 对比度/饱和度/影调分区/暗角全部非默认
        # ⇒ 任何串味（例如 U-1 段意外改动 l 或 c 的后续使用者）都会露出来。
        adj_u1 = dict(split=(0.0, 0.0), mono=0.0, contrast=1.15, sat=1.1,
                      highlights=0.2, shadows=-0.1, fade=0.1, clarity=0.3)
        vig_u1 = (-0.6, 80.0, 50.0)
        o_new = run_style(gl, eso, sh, inp, lut_id, 33, lut_on=1.0, lut_strength=0.8,
                          grain=25.0, vig=vig_u1, adj=adj_u1)
        o_cut = run_style(gl, eso, sh_no_u1, inp, lut_id, 33, lut_on=1.0,
                          lut_strength=0.8, grain=25.0, vig=vig_u1, adj=adj_u1)
        same = bool(np.array_equal(o_new, o_cut))
        dmax = float(np.abs(o_new - o_cut).max() * 255.0)
        print(f'⑰a U-1 默认零回归（[23]=[24]=[25]=0，LUT/颗粒/对比度/影调分区/暗角全开）：'
              f'与"删掉 U-1 段的 shader"逐位相同 = {same}（max|Δ| = {dmax:.0f} LSB，'
              f'非零像素 = {int((o_new != o_cut).any(axis=-1).sum())}）')
        if not same:
            fails.append('⑰a U-1 零回归')

    # ⑰b [25] 单色生效（显示域去色到 Rec.709 luma）+ 与解析期望对照（判据 ≤1 LSB）
    # ⚠ 期望值直接来自 [split_mono_np]（shader 同式的 numpy 直译）⇒ 同时验收"口径"。
    lut_off_u1 = lut_off
    o_m = run_style(gl, eso, sh, inp, lut_off_u1, 1, lut_on=0.0, lut_strength=0.0,
                    grain=0.0, adj=dict(mono=1.0))
    q_m = quant(o_m, inp.shape)
    c0 = inp.astype(np.float64) / 255.0
    want_m = expect8(split_mono_np(c0, (0.0, 0.0), 1.0))
    d_m = float(np.abs(q_m - want_m).max())
    rg = float(np.abs(q_m[..., 0] - q_m[..., 1]).max())
    gb = float(np.abs(q_m[..., 1] - q_m[..., 2]).max())
    # luma 保持：去色不改变 709 luma（shader 就是 mix 到 vec3(luma)）
    lum_i = float((c0 @ np.array([0.2126, 0.7152, 0.0722])).mean() * 255.0)
    lum_o = float((q_m / 255.0 @ np.array([0.2126, 0.7152, 0.0722])).mean())
    print(f'⑰b [25]=1 单色：实测 max|R−G| = {rg:.0f}, max|G−B| = {gb:.0f} LSB'
          f'（判据 ≤1 = 只剩 dither ⇒ 真灰）；与解析期望（709 luma 去色）max|Δ| = '
          f'{d_m:.1f} LSB（判据 ≤3 = dither±1 + 舍入模式差 ≤1 + 16F 余量，与 ⑬b 同口径）；'
          f'平均 luma 输入 {lum_i:.1f} → 输出 {lum_o * 255:.1f}（应保持）')
    if rg > 1.0 or gb > 1.0 or d_m > 3.0:
        fails.append('⑰b 单色')

    # ⑰c [23]/[24] 分离色调生效 + **真的分区**（逐段灰阶）：阴影暖 [23]=+1 / 高光冷
    # [24]=+1 各跑一次，逐段打印实测偏移与解析偏移 —— "阴影段吃暖、高光段吃冷"是判据。
    steps = [16, 32, 64, 96, 128, 160, 192, 224, 250]
    g_steps = grey_steps(n, n, steps)
    segw = max(1, n // len(steps))
    ok17c = True
    for name, sp in (('[23]=+1 阴影暖', (1.0, 0.0)), ('[24]=+1 高光冷', (0.0, 1.0))):
        o = run_style(gl, eso, sh, g_steps, lut_off_u1, 1, lut_on=0.0,
                      lut_strength=0.0, grain=0.0, adj=dict(split=sp))
        q = quant(o, g_steps.shape).astype(np.float64)
        want = expect8(split_mono_np(g_steps.astype(np.float64) / 255.0, sp, 0.0))
        d = float(np.abs(q - want).max())
        ok17c = ok17c and d <= 3.0
        parts = []
        for i, v in enumerate(steps):
            sl = np.s_[:, i * segw:(i + 1) * segw]
            dg = (q[sl] - g_steps[sl].astype(np.float64)).mean(axis=(0, 1))
            parts.append(f'luma{v}: ΔRGB=({dg[0]:+.1f},{dg[1]:+.1f},{dg[2]:+.1f})')
        print(f'⑰c {name}：与解析期望 max|Δ| = {d:.1f} LSB（判据 ≤3 = dither±1 + '
              f'舍入模式差 ≤1 + 16F 余量，与 ⑬b 同口径）')
        print('   ' + ' | '.join(parts))
        # 方向判据：阴影暖 ⇒ 暗段 ΔR > 0 且 ΔB < 0；高光冷 ⇒ 亮段 ΔB > 0 且 ΔR < 0。
        # 亮段取 luma224（不是 luma250）：250 段（0.98）吃满高光偏移后会**撞白点 clamp**
        #（8bit 边界的本征行为，见 [expect8]），方向判据在那里会退化成"看截断"。
        dark = (q[:, :segw] - g_steps[:, :segw].astype(np.float64)).mean(axis=(0, 1))
        bright = (q[:, -2 * segw:-segw]
                  - g_steps[:, -2 * segw:-segw].astype(np.float64)).mean(axis=(0, 1))
        good = (dark[0] > 0.5 and dark[2] < -0.5) if sp[0] > 0 else \
               (bright[2] > 0.5 and bright[0] < -0.5)
        if not good:
            ok17c = False
        print(f'   方向：暗段({steps[0]}/255) ΔR={dark[0]:+.1f} ΔB={dark[2]:+.1f}；'
              f'亮段({steps[-2]}/255) ΔR={bright[0]:+.1f} ΔB={bright[2]:+.1f}'
              f' ⇒ {"方向正确" if good else "方向错误"}')
    if not ok17c:
        fails.append('⑰c 分离色调')

    # ⑰d 与 kFsFilm **逐字同式**（四段文本比对）+ 域差异量化（同一物理亮度下两侧的
    # 权重与偏移幅度）—— "方向与幅度量级一致"的可核对数字就在这里。
    diff17d = check_split_mono_parity()
    print(f'⑰d kFsFilm 与 kFsStyle 的 U-1 数学逐字比对：差异段 = '
          f'{diff17d or "无（0 处）"}')
    if diff17d:
        fails.append('⑰d 两处一致性')
    print('   域差异量化（同一**物理**亮度：线性域 l 与其显示域编码 srgbEncode(l)）：')
    for l_lin in (0.02, 0.05, 0.18, 0.5, 0.9):
        l_disp = float(srgb_encode_np(np.array(l_lin)))
        ws_f, wh_f = math.exp(-l_lin * 3.0), float(smoothstep_np(0.5, 1.0, np.array(l_lin)))
        ws_s, wh_s = math.exp(-l_disp * 3.0), float(smoothstep_np(0.5, 1.0, np.array(l_disp)))
        print(f'   l_lin={l_lin:<5} l_disp={l_disp:.4f} | kFsFilm ws={ws_f:.4f} '
              f'wh={wh_f:.4f} ΔR(阴影暖)={0.05 * ws_f * 255:+.2f} ΔB(高光冷)={0.05 * wh_f * 255:+.2f}'
              f' | kFsStyle ws={ws_s:.4f} wh={wh_s:.4f} ΔR={0.05 * ws_s * 255:+.2f} '
              f'ΔB={0.05 * wh_s * 255:+.2f} LSB')
    print('   ⇒ 系数/权重公式逐字相同（⑰d 文本比对），差异 **100% 来自 luma 的域**：'
          '同一物理亮度下显示域 l 更大 ⇒ 阴影权重更小（中灰 0.18：film 0.583 vs '
          'style 0.250 = 43%）、高光权重更早起跳（l_disp ≥ 0.5 即 l_lin ≥ 0.214）。')

    gl.close()
    print('全部通过' if not fails else f'失败项：{fails}')
    return 0 if not fails else 1


if __name__ == '__main__':
    sys.exit(main())
