#!/usr/bin/env python3
"""位深单一真源守卫（卡 A1 起，卡 H2 扩展为**两档交付**）。

为什么需要（`docs/rec2020-heif-design.md` §4.2）：位深此前是**编译期常量**，散落在成片
target 格式、readback 类型、dither 量化常量、Kotlin 位图 Config 各处。提位深时漏一处 =
静默降位（画面看着正常、位深悄悄掉回 8bit，最难查的一类回归）。本脚本把"散落"变成 CI 红。

真源：`core/engine/src/render/OutputBitDepth.h`（C++ / GLSL 文本）
      `app/src/main/java/com/filmkit/app/EngineBridge.kt` 的 `OUTPUT_BITMAP_CONFIG`（Kotlin）

── 卡 H2（2026-10-14）：8bit 与 10bit **并存**，不是"把位深从 8 改成 10" ──────────
· 8bit 档 = JPEG 默认链（用户拍板 P3：默认 JPEG，现有链一字不动）⇒ `kOutputBitDepth = 8`
  与其派生值、GL 对、`kOutputMaxGlsl = "255.0"` 全部**冻结不变**（零回归守卫）；
· 10bit 档 = HEIF 可选链（R1 选项 A：sRGB 显示域 10bit）⇒ 真源里新增一整套
  （`kOutput10*` + `outputInternalOf / outputReadTypeOf / outputMaxOf / outputUploadTypeOf`），
  三条成片入口按 `ten_bit` 一个 bool 切换，**三处口径同源**（target / readback / dither）。

检查八组（`verify()` 是**纯函数** ⇒ `--selftest` 能喂变异体验证"守卫真的会红"）：
  ① 真源自洽：kOutputMax == (1<<bit)-1、kOutputInvMax == 1/kOutputMax、GLSL 文本 == kOutputMax
  ② GL 交付对随位深：8bit⇒(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE)、10bit⇒(GL_RGB10_A2, GL_RGBA, GL_UNSIGNED_INT_2_10_10_10_REV)
  ③ shader dither 除数锚定：kFsStyle 逐字 == kOutputMaxGlsl；kFsOut = CPU 下发的
     `uOutMax`（卡 H2 两档共用同一行），**兜底**必须 == kOutputMaxGlsl，且 uniform 声明在
     —— GLSL 引用不到 C++ 常量、shader 文本又被契约冻结（见 OutputBitDepth.h 文件头），
        故此处只能「锚定」；锚定失败 = 有人改了位深却忘了 shader。
  ④ 输出交付链落点必须引用真源（MUST 存在 + FORBID 裸字面量）
  ⑤ Kotlin 侧 Config 与位深匹配，且两个引擎输出落点不再写裸 ARGB_8888
  ⑥ 冻结计数：`makeTex(…, GL_RGBA8, …)` 的总数不得增长（新落点必须显式决定去留）
  ⑦ 两档登记齐全：10bit 那一整套必须存在，且与 GL_PAIRS[10] 逐值一致（防"只写半边"）
  ⑧ 10bit 交付的**绝对**读回自检必须存在（防"静默交付错文件"），且三条成片入口都
     fail-closed（自检未通过就拒绝 10bit 交付 ⇒ 调用方回退）

用法：python3 tools/check-bitdepth-single-source.py [--selftest]
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
HDR = ROOT / "core/engine/src/render/OutputBitDepth.h"
FR = ROOT / "core/engine/src/render/FrameRenderer.cpp"
BRIDGE = ROOT / "app/src/main/java/com/filmkit/app/EngineBridge.kt"
KT_OUT = [
    ROOT / "app/src/main/java/com/filmkit/app/camera/CameraController.kt",
    ROOT / "app/src/main/java/com/filmkit/app/camera/EditorEngineSession.kt",
]

# ⑥ 冻结计数：当前 `makeTex(…, GL_RGBA8, …)` 落点数（全部是**非**输出交付链的
# 内部工作缓冲，逐条理由见 docs/reports/r-next-bitdepth-single-source-REPORT.md §1.2 B 组）。
FROZEN_RGBA8_MAKETEX = 8

# ② 位深 → GL 交付对（ES 3.0 只保证 GL_RGBA+GL_UNSIGNED_BYTE 可读回；10bit 见 OutputBitDepth.h）
GL_PAIRS = {
    8: ("GL_RGBA8", "GL_RGBA", "GL_UNSIGNED_BYTE"),
    10: ("GL_RGB10_A2", "GL_RGBA", "GL_UNSIGNED_INT_2_10_10_10_REV"),
}
# ⑤ 位深 → Android Bitmap.Config
KT_CONFIGS = {8: "ARGB_8888", 10: "RGBA_1010102", 16: "RGBA_F16"}

# ④ 输出交付链落点（收口后应有的**引用真源**形态；文件内出现次数）
MUST_REF = [
    # 成片 target：两条成片路径各自按交付档（ten_bit / c.ten_bit）选内部格式
    ("makeTex(&tex_cap_, &fbo_cap_, outputInternalOf(ten_bit), dst_w, dst_h);", 1),
    ("makeTex(&tex_cap_, &fbo_cap_, outputInternalOf(c.ten_bit), dst_w, dst_h);", 1),
    # 编辑器通路恒 8bit（JPEG 交付）—— 必须写 8bit 档真源，不许跟成片档走
    ("makeTex(&tex_cap_, &fbo_cap_, kOutputGlInternalFormat, out_w, out_h);", 1),
    ("makeTex(&tex_out_s_, &fbo_out_s_, kOutputGlInternalFormat, w, h);", 1),
    # 成片 readback：format/type 都随交付档
    ("glReadPixels(0, 0, dst_w, dst_h, outputReadFormatOf(ten_bit),", 1),
    ("glReadPixels(0, 0, dst_w, dst_h, outputReadFormatOf(c.ten_bit),", 1),
    ("glReadPixels(0, 0, out_w, out_h, kOutputGlReadFormat, kOutputGlReadType,", 1),
    ("glReadPixels(0, 0, 1, 1, kOutputGlReadFormat, kOutputGlReadType, b4);", 1),
    # 纹理上传类型也必须走真源（10bit 档 = GL_UNSIGNED_INT_2_10_10_10_REV）
    ("const GLenum type = outputUploadTypeOf(internal);", 1),
]
# 字面量（**非**正则；含 `(` 的裸模式当正则会直接语法错）
FORBID_REF = [
    ("makeTex(&tex_cap_, &fbo_cap_, GL_RGBA8", "成片/编辑器 target 又写死 GL_RGBA8"),
    ("makeTex(&tex_out_s_, &fbo_out_s_, GL_RGBA8", "预览降档 target 又写死 GL_RGBA8"),
    ("glReadPixels(0, 0, dst_w, dst_h, GL_RGBA, GL_UNSIGNED_BYTE", "成片 readback 又写死 8bit 对"),
    ("glReadPixels(0, 0, out_w, out_h, GL_RGBA, GL_UNSIGNED_BYTE", "编辑器 readback 又写死 8bit 对"),
    # 卡 H2 反向守卫：dither 除数不许在 shader 里写死（两档共用同一行，必须由 CPU 下发）
    ("- 1.0) / 255.0;", None),   # 由 ③ 单独判定（kFsStyle 里合法、kFsOut 里非法）
]
RAW_STR = re.compile(r'const char\*\s+(\w+)\s*=\s*R"\((.*?)\)";', re.S)

# ⑦ 10bit 档必须登记的符号（真源头；缺一个就是"只写半边"）
DUP_SYMBOLS = [
    "kOutput10BitDepth", "kOutput10Max", "kOutput10GlInternalFormat",
    "kOutput10GlReadFormat", "kOutput10GlReadType", "kOutput10MaxGlsl",
    "kOutputMaxUniform", "outputInternalOf", "outputReadFormatOf",
    "outputReadTypeOf", "outputMaxOf", "outputUploadTypeOf",
]
# ⑧ 10bit 绝对读回自检的锚点（必须逐字存在）+ 三条成片入口的 fail-closed 调用
SELFCHECK_READ = "glReadPixels(0, 0, kN, 1, kOutput10GlReadFormat, kOutput10GlReadType, raw);"
SELFCHECK_HOOK = "if (ten_bit && !selfCheck10BitOnce()) return false;"
SELFCHECK_HOOK_MIN = 3


def parse_header(hdr):
    """从真源头抓各值；抓不到返回 None（由 ① 报红，不静默跳过）。"""
    def grab(pat, cast=str):
        m = re.search(pat, hdr)
        if not m:
            return None
        return cast(m.group(1)) if m.groups() else True

    return {
        "bit": grab(r"kOutputBitDepth\s*=\s*(\d+)", int),
        # 这两个只验"是否仍由 kOutputBitDepth 派生"（值由 ① 的算术语义检查兜住）
        "max_derived": grab(r"kOutputMax\s*=\s*\(\s*1\s*<<\s*kOutputBitDepth\s*\)\s*-\s*1"),
        "inv_derived": grab(r"kOutputInvMax\s*=\s*1\.0f\s*/\s*static_cast<float>\(kOutputMax\)"),
        "glsl": grab(r'kOutputMaxGlsl\s*=\s*"([^"]+)"'),
        "ifmt": grab(r"kOutputGlInternalFormat\s*=\s*(\w+)"),
        "rfmt": grab(r"kOutputGlReadFormat\s*=\s*(\w+)"),
        "rtype": grab(r"kOutputGlReadType\s*=\s*(\w+)"),
        # ── 卡 H2：10bit 档 ──
        "maxuni": grab(r'kOutputMaxUniform\s*=\s*"([^"]+)"'),
        "bit10": grab(r"kOutput10BitDepth\s*=\s*(\d+)", int),
        "max10_derived": grab(r"kOutput10Max\s*=\s*\(\s*1\s*<<\s*kOutput10BitDepth\s*\)\s*-\s*1"),
        "inv10_derived": grab(r"kOutput10InvMax\s*=\s*1\.0f\s*/\s*static_cast<float>\(kOutput10Max\)"),
        "glsl10": grab(r'kOutput10MaxGlsl\s*=\s*"([^"]+)"'),
        "ifmt10": grab(r"kOutput10GlInternalFormat\s*=\s*(\w+)"),
        "rfmt10": grab(r"kOutput10GlReadFormat\s*=\s*(\w+)"),
        "rtype10": grab(r"kOutput10GlReadType\s*=\s*(\w+)"),
    }


def verify(hdr, fr, bridge, kt_outs):
    """纯函数：返回失败信息列表（空 = 全部通过）。"""
    bad = []

    def fail(msg):
        bad.append(msg)

    # ① 真源自洽
    v = parse_header(hdr)
    bit = v["bit"]
    if bit is None:
        fail("① 真源头缺少 kOutputBitDepth（位深不再是单一真源？）")
        return bad
    if v["max_derived"] is not True or v["inv_derived"] is not True:
        fail("① kOutputMax / kOutputInvMax 必须由 kOutputBitDepth 派生（不是各自写死的字面量）")
    expect_max = (1 << bit) - 1
    if v["glsl"] is None:
        fail("① 真源头缺少 kOutputMaxGlsl")
    else:
        try:
            lit = float(v["glsl"])
        except ValueError:
            fail("① kOutputMaxGlsl = %r 不是 GLSL 浮点字面量" % v["glsl"])
            lit = None
        if lit is not None and abs(lit - expect_max) > 1e-6:
            fail("① kOutputMaxGlsl = %r ≠ (1<<%d)-1 = %d（dither 除数与位深脱钩）"
                 % (v["glsl"], bit, expect_max))
        elif lit is not None and "." not in v["glsl"]:
            fail("① kOutputMaxGlsl = %r 缺小数点（GLSL 会当 int）" % v["glsl"])

    # ② GL 交付对随位深
    if bit in GL_PAIRS:
        want = GL_PAIRS[bit]
        got = (v["ifmt"], v["rfmt"], v["rtype"])
        if got != want:
            fail("② %dbit 的 GL 交付对应为 %s，实际 %s" % (bit, want, got))
    else:
        fail("② 位深 %d 未在 GL_PAIRS 登记 ⇒ 提位深时请补登记（否则本条形同虚设）" % bit)

    # ③ shader dither 除数锚定（卡 H2：两条链路形态不同）
    bodies = dict(RAW_STR.findall(fr))
    # ③a kFsStyle（编辑器，恒 8bit）：除数逐字 == kOutputMaxGlsl
    body_style = bodies.get("kFsStyle")
    if body_style is None:
        fail("③ 找不到 shader 原始字面量 kFsStyle（提取正则与写法脱钩？）")
    else:
        # 只看 dither 行（`float dhash(vec2 p)` 的函数定义也含 dhash( ⇒ 必须排除）
        hits = [ln for ln in body_style.split("\n") if "dhash(" in ln and "- 1.0)" in ln]
        if len(hits) != 1:
            fail("③ kFsStyle 里 dhash( 行数 = %d（应恰好 1 行 dither）" % len(hits))
        else:
            m = re.search(r"/\s*([0-9][0-9.]*)\s*;", hits[0])
            if not m:
                fail("③ kFsStyle 的 dither 行找不到数字除数：%s" % hits[0].strip())
            elif m.group(1) != v["glsl"]:
                fail("③ kFsStyle dither 除数 = %s ≠ 真源 kOutputMaxGlsl = %s"
                     % (m.group(1), v["glsl"]))
    # ③b kFsOut（成片，8bit/10bit 两档共用同一行）：除数 = CPU 下发的 uOutMax，
    #     兜底必须 == 真源（离线校验脚本不传该 uniform ⇒ 靠兜底逐位零回归）
    body_out = bodies.get("kFsOut")
    if body_out is None:
        fail("③ 找不到 shader 原始字面量 kFsOut（提取正则与写法脱钩？）")
    else:
        hits = [ln for ln in body_out.split("\n") if "dhash(" in ln and "- 1.0)" in ln]
        if len(hits) != 1:
            fail("③ kFsOut 里 dhash( 行数 = %d（应恰好 1 行 dither）" % len(hits))
        else:
            uni = v["maxuni"]
            m = re.search(r"/\s*([A-Za-z_][A-Za-z0-9_]*)\s*;", hits[0])
            if uni is None:
                fail("③ 真源头缺少 kOutputMaxUniform（dither 除数的 uniform 名）")
            elif not m:
                fail("③ kFsOut 的 dither 除数既不是变量也不是被 CPU 下发的 uniform：%s"
                     % hits[0].strip())
            else:
                var = m.group(1)
                if not re.search(r"uniform\s+float\s+%s\s*;" % re.escape(uni), body_out):
                    fail("③ kFsOut 缺少 `uniform float %s;` 声明（CPU 下发的除数没有落点）" % uni)
                defs = [ln for ln in body_out.split("\n")
                        if re.search(r"\b%s\s*=" % re.escape(var), ln) and uni in ln]
                if len(defs) != 1:
                    fail("③ kFsOut 找不到 `%s = %s > 0.0 ? %s : <兜底>`（命中 %d 行）"
                         % (var, uni, uni, len(defs)))
                else:
                    fb = re.search(r":\s*([0-9][0-9.]*)\s*;", defs[0])
                    if not fb:
                        fail("③ kFsOut 的 dither 除数兜底不是数字字面量：%s" % defs[0].strip())
                    elif fb.group(1) != v["glsl"]:
                        fail("③ kFsOut dither 除数兜底 = %s ≠ 真源 kOutputMaxGlsl = %s"
                             % (fb.group(1), v["glsl"]))

    # ④ 输出交付链落点
    for pat, want in MUST_REF:
        got = fr.count(pat)
        if got != want:
            fail("④ 落点 %r 出现 %d 次（应 %d）—— 收口点被改回裸字面量，或成片路径增删"
                 % (pat[:60], got, want))
    for pat, why in FORBID_REF:
        if why is None:
            continue   # 该模式由 ③ 按链路分别判定
        if pat in fr:
            fail("④ %s（应引用 kOutputGlInternalFormat / kOutputGlRead*）" % why)

    # ⑤ Kotlin 侧
    if bit in KT_CONFIGS:
        want_cfg = KT_CONFIGS[bit]
        m = re.search(r"OUTPUT_BITMAP_CONFIG\s*:\s*Bitmap\.Config\s*=\s*Bitmap\.Config\.(\w+)", bridge)
        if not m:
            fail("⑤ EngineBridge 缺少 OUTPUT_BITMAP_CONFIG（Kotlin 侧真源没了）")
        elif m.group(1) != want_cfg:
            fail("⑤ OUTPUT_BITMAP_CONFIG = %s ≠ %dbit 应对应的 %s" % (m.group(1), bit, want_cfg))
    else:
        fail("⑤ 位深 %d 未在 KT_CONFIGS 登记" % bit)
    for path, txt in zip(KT_OUT, kt_outs):
        if "Bitmap.Config.ARGB_8888" in txt:
            fail("⑤ %s 又写死 Bitmap.Config.ARGB_8888（应引用 EngineBridge.OUTPUT_BITMAP_CONFIG）"
                 % path.name)

    # ⑥ 冻结计数
    n = len(re.findall(r"makeTex\([^;\n]*GL_RGBA8", fr))
    if n != FROZEN_RGBA8_MAKETEX:
        fail("⑥ makeTex(…, GL_RGBA8, …) 落点数 = %d ≠ 冻结值 %d —— 新增/删除了 RGBA8 纹理落点："
             "属输出交付链 ⇒ 改引用 kOutputGlInternalFormat；属内部工作缓冲 ⇒ 更新本脚本的"
             " FROZEN_RGBA8_MAKETEX 并在报告 §1.2 B 组登记理由" % (n, FROZEN_RGBA8_MAKETEX))

    # ⑦ 两档登记齐全（10bit 那整套必须存在，且与 GL_PAIRS[10] 逐值一致）
    # 用**词边界**匹配标识符：子串匹配会被 `outputReadTypeOfGONE` 这类改名骗过
    #（--selftest 的「整套被删」变异体正是抓这个口径）
    missing = [sym for sym in DUP_SYMBOLS
               if not re.search(r"\b%s\b" % re.escape(sym), hdr)]
    if missing:
        fail("⑦ 真源头缺 10bit 档符号：%s（卡 H2 的 10bit 交付档被删/改名 —— 两档必须并存）"
             % ", ".join(missing))
    if v["bit10"] != 10:
        fail("⑦ kOutput10BitDepth = %r ≠ 10" % (v["bit10"],))
    if v["max10_derived"] is not True or v["inv10_derived"] is not True:
        fail("⑦ kOutput10Max / kOutput10InvMax 必须由 kOutput10BitDepth 派生")
    if v["glsl10"] != "1023.0":
        fail("⑦ kOutput10MaxGlsl = %r ≠ \"1023.0\"" % (v["glsl10"],))
    got10 = (v["ifmt10"], v["rfmt10"], v["rtype10"])
    if got10 != GL_PAIRS[10]:
        fail("⑦ 10bit 档 GL 对 = %s ≠ GL_PAIRS[10] = %s（两处登记必须逐值一致）"
             % (got10, GL_PAIRS[10]))

    # ⑧ 10bit 交付的绝对读回自检（防静默交付错文件）+ 三条成片入口 fail-closed
    if fr.count(SELFCHECK_READ) != 1:
        fail("⑧ 10bit 交付档的**绝对**读回自检缺失（应逐字含 `%s`，实际 %d 次）—— "
             "该自检是「驱动位序与 Kotlin 解包口径一致」的唯一判据，不许省"
             % (SELFCHECK_READ, fr.count(SELFCHECK_READ)))
    hooks = fr.count(SELFCHECK_HOOK)
    if hooks < SELFCHECK_HOOK_MIN:
        fail("⑧ 成片入口的 fail-closed 钩子 = %d 处 < %d（submitStill / submitRaw / "
             "submitRawBurst 三条都要：自检未通过 ⇒ 拒绝 10bit 交付并回退）"
             % (hooks, SELFCHECK_HOOK_MIN))
    return bad


def load():
    return (HDR.read_text(encoding="utf-8"),
            FR.read_text(encoding="utf-8"),
            BRIDGE.read_text(encoding="utf-8"),
            [p.read_text(encoding="utf-8") for p in KT_OUT])


def selftest():
    """守卫自检：每条检查都喂一个变异体，必须各自报红（照 engine-shader-offline.py
    `--splice-selftest` 的先例 —— 没有自检的守卫可能是个「永远通过」的假守卫）。"""
    hdr, fr, bridge, kts = load()
    cases = [
        ("① 位深改了、kOutputMaxGlsl 没跟",
         lambda h, f, b, k: (h.replace("kOutputBitDepth = 8", "kOutputBitDepth = 10"), f, b, k), "①"),
        ("① kOutputMax 被写成字面量",
         lambda h, f, b, k: (h.replace("kOutputMax = (1 << kOutputBitDepth) - 1", "kOutputMax = 255"), f, b, k), "①"),
        ("③ kFsStyle 的 dither 除数被改",
         lambda h, f, b, k: (h, f.replace("- 1.0) / 255.0;", "- 1.0) / 1023.0;", 1), b, k), "③"),
        ("③ kFsOut 的 dither 除数被写死（不再由 CPU 下发）",
         lambda h, f, b, k: (h, f.replace("- 1.0) / ditherMax;", "- 1.0) / 1023.0;", 1), b, k), "③"),
        ("③ kFsOut 的 dither 除数兜底常量被改",
         lambda h, f, b, k: (h, f.replace("? uOutMax : 255.0;", "? uOutMax : 1023.0;", 1), b, k), "③"),
        ("③ kFsOut 的 uOutMax uniform 声明被删",
         lambda h, f, b, k: (h, f.replace("uniform float uOutMax;", "", 1), b, k), "③"),
        ("④ 成片 target 改回裸 GL_RGBA8",
         lambda h, f, b, k: (h, f.replace("makeTex(&tex_cap_, &fbo_cap_, outputInternalOf(ten_bit), dst_w, dst_h);",
                                          "makeTex(&tex_cap_, &fbo_cap_, GL_RGBA8, dst_w, dst_h);", 1), b, k), "④"),
        ("④ 成片 readback 改回裸 8bit 对",
         lambda h, f, b, k: (h, f.replace("glReadPixels(0, 0, dst_w, dst_h, outputReadFormatOf(ten_bit),",
                                          "glReadPixels(0, 0, dst_w, dst_h, GL_RGBA, GL_UNSIGNED_BYTE,", 1), b, k), "④"),
        ("④ 纹理上传类型不再走真源",
         lambda h, f, b, k: (h, f.replace("const GLenum type = outputUploadTypeOf(internal);",
                                          "const GLenum type = GL_HALF_FLOAT;", 1), b, k), "④"),
        ("⑤ Kotlin Config 与位深脱钩",
         lambda h, f, b, k: (h, f, b.replace("= Bitmap.Config.ARGB_8888", "= Bitmap.Config.RGBA_F16"), k), "⑤"),
        ("⑤ 输出落点又写死 ARGB_8888",
         lambda h, f, b, k: (h, f, b, [k[0].replace("EngineBridge.OUTPUT_BITMAP_CONFIG",
                                                   "Bitmap.Config.ARGB_8888")] + k[1:]), "⑤"),
        ("⑥ 新增一个 RGBA8 makeTex 落点",
         lambda h, f, b, k: (h, f.replace("makeTex(&tex_mis_, &fbo_mis_, GL_RGBA8, cw, chh);",
                                          "makeTex(&tex_mis_, &fbo_mis_, GL_RGBA8, cw, chh);\n"
                                          "  makeTex(&tex_new_, &fbo_new_, GL_RGBA8, cw, chh);", 1), b, k), "⑥"),
        ("⑦ 10bit 档的 GL 读回类型被改（与 GL_PAIRS 脱钩）",
         lambda h, f, b, k: (h.replace("kOutput10GlReadType = GL_UNSIGNED_INT_2_10_10_10_REV",
                                       "kOutput10GlReadType = GL_UNSIGNED_BYTE"), f, b, k), "⑦"),
        ("⑦ 10bit 档整套被删（退回「只有 8bit」）",
         lambda h, f, b, k: (h.replace("outputReadTypeOf", "outputReadTypeOfGONE"), f, b, k), "⑦"),
        ("⑧ 10bit 绝对读回自检被删",
         lambda h, f, b, k: (h, f.replace(SELFCHECK_READ, "", 1), b, k), "⑧"),
        ("⑧ 成片入口的 fail-closed 钩子被摘",
         lambda h, f, b, k: (h, f.replace(SELFCHECK_HOOK, "if (false) return false;"), b, k), "⑧"),
    ]
    ok = True
    for title, mutate, tag in cases:
        try:
            bad = verify(*mutate(hdr, fr, bridge, kts))
        except Exception as e:                                    # noqa: BLE001
            bad = ["%s 变异导致异常：%r" % (tag, e)]
        caught = [m for m in bad if m.startswith(tag)]
        if caught:
            print("  OK  %s\n        → 抓到：%s" % (title, caught[0][:100]))
        else:
            ok = False
            print("  XX  %s → 没抓到（守卫在此维度失效）" % title)
    # 反向：未变异体必须全绿，否则守卫是「永远红」的假守卫
    bad0 = verify(hdr, fr, bridge, kts)
    if bad0:
        ok = False
        print("  XX  未变异体竟然报红（假守卫）：%s" % bad0[:2])
    else:
        print("  OK  未变异体全绿（不是「永远红」的假守卫）")
    return ok


def main():
    if "--selftest" in sys.argv:
        print("守卫自检（每条检查喂一个变异体，必须各自报红）：")
        ok = selftest()
        print("自检%s" % ("通过" if ok else "**失败**"))
        return 0 if ok else 1

    hdr, fr, bridge, kts = load()
    bad = verify(hdr, fr, bridge, kts)
    v = parse_header(hdr)
    print("位深单一真源：bit=%s  max=(1<<bit)-1  inv=1/max  glsl=%s  GL=(%s,%s,%s)"
          % (v["bit"], v["glsl"], v["ifmt"], v["rfmt"], v["rtype"]))
    print("            10bit 档：bit=%s  glsl=%s  GL=(%s,%s,%s)  dither uniform=%s"
          % (v["bit10"], v["glsl10"], v["ifmt10"], v["rfmt10"], v["rtype10"], v["maxuni"]))
    if bad:
        print("FAIL（%d 条）：" % len(bad))
        for m in bad:
            print("  · %s" % m)
        return 1
    print("PASS：两档真源自洽 · shader dither 锚定 · 输出落点全部引用真源 · Kotlin 同源 · "
          "冻结计数未变 · 10bit 自检与 fail-closed 在位")
    return 0


if __name__ == "__main__":
    sys.exit(main())
