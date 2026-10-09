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

用法：python3 tools/check-style-passthrough.py [--size 96]
依赖：moderngl + glfw（与 tools/engine-shader-offline.py 相同）
"""
import argparse
import importlib.util
import os
import pathlib
import re
import sys

import numpy as np

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FR = os.path.join(REPO, 'core/engine/src/render/FrameRenderer.cpp')
ESO = os.path.join(REPO, 'tools/engine-shader-offline.py')


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


def grey_input(w, h, v=180):
    """纯灰输入：暗角是确定性径向增益，纯灰 ⇒ 剖面可逐点核对（dither 只 ±1 LSB）。"""
    return np.full((h, w, 3), v, np.uint8)


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


def style_uniforms(w, h, ev=0.0, wb=(1.0, 1.0, 1.0), contrast=1.0, sat=1.0,
                   clarity=0.0, local_type=0.0, local_center=(0.5, 0.5),
                   local_radius=0.35, local_feather=0.5, local_angle=0.0,
                   local_width=0.25, local_ev=0.0, local_sat=1.0):
    """E2-2 基础调整 + E2-6 局部调整的 CPU 侧下发值（**与 FrameRenderer::passStyle
    同式**，含门控）。

    门控在 CPU 侧算 ⇒ 默认恒等（ev=0、WB=(1,1,1)、contrast=1、sat=1、clarity=0、
    local_type=0）时 shader 内整段跳过。这里刻意重算一遍 ⇒ 本脚本同时验收 CPU 侧
    的下发公式（E2-6 的三个门控：local_on / local_ev_on / local_sat_on）。
    """
    radial = 0.5 <= local_type < 1.5
    linear = local_type >= 1.5
    ev_on = local_ev != 0.0
    sat_on = local_sat != 1.0
    return {
        'uEv': float(ev),
        'uWbGain': tuple(float(v) for v in wb),
        'uExpWbOn': 1.0 if (ev != 0.0 or tuple(wb) != (1.0, 1.0, 1.0)) else 0.0,
        'uContrast': float(contrast),
        'uContrastOn': 1.0 if contrast != 1.0 else 0.0,
        'uSat': float(sat),
        'uSatOn': 1.0 if sat != 1.0 else 0.0,
        'uClarity': float(clarity),
        # 环半径 = max(1px, 0.5% × min(W,H))，转成 UV 步长（passStyle 同式）
        'uClarStep': (max(1.0, 0.005 * min(w, h)) / w,
                      max(1.0, 0.005 * min(w, h)) / h),
        # E2-6 局部调整（passStyle 同式）
        'uLocalOn': 1.0 if ((radial or linear) and (ev_on or sat_on)) else 0.0,
        'uLocalType': float(local_type),
        'uLocalCenter': tuple(float(v) for v in local_center),
        'uLocalRadius': float(local_radius),
        'uLocalFeather': float(local_feather),
        'uLocalAngle': float(local_angle),
        'uLocalWidth': float(local_width),
        'uLocalEv': float(local_ev),
        'uLocalEvOn': 1.0 if ev_on else 0.0,
        'uLocalSat': float(local_sat),
        'uLocalSatOn': 1.0 if sat_on else 0.0,
    }


def run_style(gl, eso, sh, inp, lut_tex, lut_size, *, lut_on, lut_strength,
              grain, seed=0.0, vig=(0.0, 80.0, 50.0), adj=None):
    h, w = inp.shape[:2]
    # 归一化 RGB 纹理 ≈ 真机 still_tex_（GL_RGB8：上传 8bit，采样得 v/255）；
    # float16 存 v/255 的量化误差 ≤ 0.13 LSB，不影响 ±1 LSB 判定
    tex = gl.ctx.texture((w, h), 3, dtype='f2')
    tex.write((inp.astype(np.float32) / np.float32(255.0)).astype(np.float16).tobytes())
    tex.filter = (eso.moderngl.LINEAR, eso.moderngl.LINEAR)
    uni = {
        'uSeed': seed, 'uGrainAmount': grain, 'uGrainSize': 1.0,
        'uLutOn': lut_on, 'uLutStrength': lut_strength,
        'uLutDomainMin': (0.0, 0.0, 0.0), 'uLutDomainInv': (1.0, 1.0, 1.0),
        'uLutSize': float(lut_size),
    }
    uni.update(vig_uniforms(w, h, *vig))
    uni.update(style_uniforms(w, h, **(adj or {})))
    out = gl.run(sh['kFsStyle'], uni, {'uTex': tex, 'uLut': lut_tex})
    tex.release()
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

    像素中心约定与 shader 的 vUV0 一致：像素 (row=y, col=x) ⇒ uv = ((x+.5)/w, (y+.5)/h)
    —— 本 shader 无 flip（kFsStyle 注释 ②），所以 numpy 行序与 GL 读回行序同向。
    """
    y, x = np.mgrid[0:h, 0:w]
    px = (x + 0.5) / w - center[0]
    py = (y + 0.5) / h - center[1]
    if type_ < 1.5:
        d = np.hypot(px, py) / max(radius, 1e-3)
    else:
        d = np.abs(px * np.cos(angle) + py * np.sin(angle)) / max(width, 1e-3)
    return 1.0 - smoothstep_np(1.0, 1.0 + max(feather, 1e-3), d)


def local_expect(inp, mask, ev_g=0.0, ev_l=0.0, sat_g=1.0, sat_l=1.0):
    """E2-6 的解析期望（kFsStyle 主流程同序）：一次往返（全局+局部曝光合并）→ 饱和度。

    局部曝光 = 线性域 `exp2(ev_g + ev_l × mask)`；局部饱和度 = 有效饱和度按 mask
    在全局与局部之间插值（与 shader 的 `s = uSat + (uLocalSat − uSat) × m` 同式）。
    """
    x = inp.astype(np.float64) / 255.0
    lin = srgb_decode_np(x) * (2.0 ** (ev_g + ev_l * mask))[..., None]
    out = srgb_encode_np(lin)
    if sat_g != 1.0 or sat_l != 1.0:
        s = (sat_g + (sat_l - sat_g) * mask)[..., None]
        l = (out @ np.array([0.299, 0.587, 0.114]))[..., None]
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
            'vignette_strength', 'vignette_radius', 'vignette_feather',
            # E2-2 基础调整：曝光[0] / WB[1..3]（线性域往返）+ 对比度[4] /
            # 饱和度[21] / 清晰度[78]（显示域）。饱和度读 [21]（UI 一个滑杆同写
            # [20..22]，ADR §5.6④ 的伪代码是单值 uSat）。
            'exposure_ev', 'wb_r', 'wb_g', 'wb_b', 'contrast', 'sat_mid',
            'clarity',
            # E2-6 局部调整：type[79] / center[80][81] / radius[82] / feather[83]
            # / angle[84] / width[85] / ev[86] / sat[87]。
            'local_type', 'local_center_x', 'local_center_y', 'local_radius',
            'local_feather', 'local_angle', 'local_width', 'local_ev',
            'local_sat'}
    return refs == want, refs, ''


def shader_src(src, name):
    """从 FrameRenderer.cpp 抽出某个 shader 的 GLSL 源码。"""
    m = re.search(r'const char\* ' + name + r' = R"\((.*?)\)";', src, re.S)
    return m.group(1) if m else ''


def slice_from(text, start, end):
    i = text.index(start)
    return text[i: text.index(end, i) + len(end)]


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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--size', type=int, default=96, help='测试图边长')
    args = ap.parse_args()
    n = args.size

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
    o3 = run_style(gl, eso, sh, inp, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                   grain=0.5)
    o3b = run_style(gl, eso, sh, inp, lut_off, 1, lut_on=0.0, lut_strength=0.0,
                    grain=0.5)
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

    # ⑬f 结构性证明：**没有第二次 decode/encode**。kFsStyle 内 `srgbDecode(` 只应有
    #     3 处：函数定义 + chainLuma 的标量往返 + main 主路径往返。局部曝光若被写成
    #     独立的第二次往返，这里会立刻变 4 ⇒ 这就是 ADR-7「一次往返」的哨兵。
    n_dec = shader_src(src_txt, 'kFsStyle').count('srgbDecode(')
    print(f'⑬f kFsStyle 内 srgbDecode( 出现次数 = {n_dec}'
          f'（应为 3 = 定义 1 + chainLuma 1 + 主路径 1 ⇒ 全局与局部曝光共用同一次往返）')
    if n_dec != 3:
        fails.append('⑬f 单次往返')

    gl.close()
    print('全部通过' if not fails else f'失败项：{fails}')
    return 0 if not fails else 1


if __name__ == '__main__':
    sys.exit(main())
