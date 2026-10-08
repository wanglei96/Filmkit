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


def run_style(gl, eso, sh, inp, lut_tex, lut_size, *, lut_on, lut_strength,
              grain, seed=0.0):
    h, w = inp.shape[:2]
    # 归一化 RGB 纹理 ≈ 真机 still_tex_（GL_RGB8：上传 8bit，采样得 v/255）；
    # float16 存 v/255 的量化误差 ≤ 0.13 LSB，不影响 ±1 LSB 判定
    tex = gl.ctx.texture((w, h), 3, dtype='f2')
    tex.write((inp.astype(np.float32) / np.float32(255.0)).astype(np.float16).tobytes())
    tex.filter = (eso.moderngl.LINEAR, eso.moderngl.LINEAR)
    out = gl.run(sh['kFsStyle'], {
        'uSeed': seed, 'uGrainAmount': grain, 'uGrainSize': 1.0,
        'uLutOn': lut_on, 'uLutStrength': lut_strength,
        'uLutDomainMin': (0.0, 0.0, 0.0), 'uLutDomainInv': (1.0, 1.0, 1.0),
        'uLutSize': float(lut_size),
    }, {'uTex': tex, 'uLut': lut_tex})
    tex.release()
    return out


def check_pass_style_slots():
    """passStyle 只允许读 [26] grain_amount / [27] grain_size / [34] lut_strength。"""
    src = pathlib.Path(FR).read_text(encoding='utf-8')
    m = re.search(r'void FrameRenderer::passStyle\(.*?\n\}', src, re.S)
    if not m:
        return False, set(), 'passStyle 函数体未找到'
    refs = set(re.findall(r'\bp\.(\w+)', m.group(0)))
    want = {'grain_amount', 'grain_size', 'lut_strength'}
    return refs == want, refs, ''


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

    gl.close()
    print('全部通过' if not fails else f'失败项：{fails}')
    return 0 if not fails else 1


if __name__ == '__main__':
    sys.exit(main())
