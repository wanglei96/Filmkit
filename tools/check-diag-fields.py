#!/usr/bin/env python3
"""诊断字段可验证性核对（2026-09-30 三次"改错位置"事故后固化）。

背景：新增 GPU pass 的生效性只能靠日志字段验证，而字段写错位置会**静默失效** ——
本会话连犯三次：
  ① 0.10.56 `uL` 忘了置 0（shader 门控恒真，核一直在跑，报告却说已停用）
  ② 0.10.58 `prog_raw_dn_ts_` 按错误的 shader 名替换，链接语句静默没落上
  ③ 0.10.59 字段写进 submitRawBurst 的 c.mfnr_kv，而日志在 finishStillChain 内发出
     → 真机 p0s 直接缺失

本脚本按"字段 — 所属函数 — 与关键调用/日志点的先后"三条一起查，任一条不满足即报错。
用法： python3 tools/check-diag-fields.py            # 核对该组字段
       python3 tools/check-diag-fields.py p0s cms    # 核对指定字段名
"""
import re
import sys
from pathlib import Path

SRC = Path("core/engine/src/render/FrameRenderer.cpp")

# 期望：字段名 → (发出日志的函数, 必须先于字段执行的调用特征)
EXPECT = {
    "p0s": ("finishStillChain", ["passRawDenoiseFused(", "passChromaMultiScale("]),
    "cms": ("finishStillChain", ["passChromaMultiScale("]),
    "cmsK": ("finishStillChain", ["passChromaMultiScale("]),
    "p0w": ("finishStillChain", ["passRawDenoiseFused("]),
    # v0.10.102 NN 降噪 pass：runNnPass 必须在 finishStillChain 内、日志行之前
    "nnOk": ("finishStillChain", ["runNnPass("]),
    "nnFail": ("finishStillChain", ["runNnPass("]),
    "nnMs": ("finishStillChain", ["runNnPass("]),
    "nnTiles": ("finishStillChain", ["runNnPass("]),
    "nnGain": ("finishStillChain", ["runNnPass("]),
    # v0.10.121 HR 白点（threshold = 2^hrWhiteEv × 0.1845）：passFilm 内的 HR 必须在
    # finishStillChain 内、日志行之前跑完，否则字段是上一帧的陈旧值
    "hrWhiteEv": ("finishStillChain", ["passFilm("]),
    # E4-4 趾部/肩部（filmic latitude per-channel，FilmParams[5..10]）：字段 =
    # **实际生效值**（clamp 到 [0,1] 后的逐通道 latitude）。生效点在
    # passFilm → updateFilmicEV → computeFilmicSpline，字段发在 finishStillChain 的
    # raw.render 出口 ⇒ 与 hrWhiteEv 同型，必须晚于 passFilm（否则是上一帧的系数）。
    "latToe": ("finishStillChain", ["passFilm("]),
    "latSh": ("finishStillChain", ["passFilm("]),
    # v0.10.122 opposed chrominance 分子/分通道样本（定位 chroma 偏大 3.4×）
    "hlSum": ("finishStillChain", ["passHighlightRaw("]),
    "hrStatRaw": ("finishStillChain", ["passHighlightRaw("]),
    "hlCnt3": ("finishStillChain", ["passHighlightRaw("]),
    # v0.10.179：uRbRecon（FilmParams[57]）实际下发值，与 hlGRecon 同口径
    "hlRb": ("finishStillChain", ["passHighlightRaw("]),
    # v0.10.180：P3 拆双 pass（FilmParams[58]）的生效性与分 pass 成本；
    # 3a/3b 都必须在 finishStillChain 内、日志行之前执行
    "rcdSplit": ("finishStillChain", ["passRcdChromaA(", "passRcdChromaB("]),
    "rcdAMs": ("finishStillChain", ["passRcdChromaA("]),
    "rcdBMs": ("finishStillChain", ["passRcdChromaB("]),
    "clipB": ("finishStillChain", ["passFilm("]),
    "hrMs": ("finishStillChain", ["passFilm("]),
    # E1-3 编辑器通路（2026-10-08）：诊断三件套（styleOk = shader 链接成功 /
    # styleOn = 本帧真的执行）+ 下发参数值（lutK/grain/grainSz）。passStyle 必须在
    # submitDisplay 内、日志字段行之前跑完，否则 styleOn 是"没执行却报执行"。
    "styleOk": ("submitDisplay", ["passStyle("]),
    "styleOn": ("submitDisplay", ["passStyle("]),
    "lutK": ("submitDisplay", ["passStyle("]),
    "grain": ("submitDisplay", ["passStyle("]),
    "grainSz": ("submitDisplay", ["passStyle("]),
    # E4-3 颗粒（granularity）：密度调制 [60] / 亮度枢轴 [61] / 本帧实际下发的种子。
    # ⚠ 这五个字段**也**出现在 finishStillChain 的 raw.render 出口（成片链），而本脚本
    # 取 `hits[0]` = 第一个匹配行 ⇒ 核对的始终是 submitDisplay 出口（行号更小）。
    # 成片侧的同名字段（尤其是 grainSeed = "两次导出逐位相同"的依据）由真机日志核对。
    "grainMb": ("submitDisplay", ["passStyle("]),
    "grainPivot": ("submitDisplay", ["passStyle("]),
    "grainSeed": ("submitDisplay", ["passStyle("]),
    # E4-5 halation（光晕）：[62..65] 的生效性 + 四参数。字段 = **实际下发值**，
    # 其中 haloOn = 本帧 halo 链**真的执行了**（0 且 [62] > 0 ⇒ 静默失败：program
    # 缺失 / 纹理分配失败 / FBO 不完整 —— 这是本卡最重要的真机失败判据）。
    # halo 链由 passStyle（编辑器）与 passOutput（拍照）各自在内部调用 ⇒ 字段行必须
    # 晚于 passStyle。同名字段也出现在 finishStillChain 出口（成片链路），而本脚本取
    # hits[0] = 行号更小的 submitDisplay ⇒ 与上面 grain* 同一条纪律。
    "haloOn": ("submitDisplay", ["passStyle("]),
    "haloMs": ("submitDisplay", ["passStyle("]),
    "haloStrength": ("submitDisplay", ["passStyle("]),
    "haloThr": ("submitDisplay", ["passStyle("]),
    "haloRadius": ("submitDisplay", ["passStyle("]),
    "haloWarmth": ("submitDisplay", ["passStyle("]),
    # E4-5b（R1）：halo 半径归一化的**基准短边**（= 成片短边）。核对"预览与成片是否用
    # 同一基准"就靠它 —— 同一进程内两侧应恒等（成片调用在 passOutput 之前更新它）。
    "haloRef": ("submitDisplay", ["passStyle("]),
    # U-4（2026-10-11）：显示域五项（[78] 清晰度 + [90..94] 影调分区/褪色）的**生效值**。
    # 三段的 shader 数学与门控都在 passOutput 内（kFsOut 的 srgbEncode 之后 = 显示域）
    # ⇒ 字段必须晚于 finishStillChain 内的 passOutput 调用（成片路径：passFilm →
    # passOutput → 本日志行），否则读的是上一帧的下发值（同 p0s/颗粒的那条纪律）。
    # ⚠ 名字全部带 `tone`/`clar` 前缀：submitDisplay 的 diag.stats 快照已占用
    # `toneOn`/`hl`/`sh`/`wh`/`fade`（编辑器链路的同义字段），而本脚本取 hits[0] ⇒
    # 同名会让核对指到 submitDisplay 上（U-1 的 splitW/splitC/monoOn 是同一条纪律）。
    # clarR = 实际下发的清晰度环半径（px，与 passOutput 同源 = clarityStepPx）。
    "clar": ("finishStillChain", ["passOutput("]),
    "clarR": ("finishStillChain", ["passOutput("]),
    "toneGate": ("finishStillChain", ["passOutput("]),
    "toneHi": ("finishStillChain", ["passOutput("]),
    "toneSh": ("finishStillChain", ["passOutput("]),
    "toneWh": ("finishStillChain", ["passOutput("]),
    "toneBl": ("finishStillChain", ["passOutput("]),
    "toneFade": ("finishStillChain", ["passOutput("]),
    # U-1（[23][24] 分离色调 + [25] 单色）：编辑器链路的**生效值**。字段名带 W/C/On 后缀，
    # 刻意不叫 `split`/`mono` —— 那两个名字已被 diag.stats 的 kFsFilm 快照占用（行号更小，
    # 而本脚本取 hits[0]）⇒ 同名会让核对指到错误的函数上（假红）。
    "splitW": ("submitDisplay", ["passStyle("]),
    "splitC": ("submitDisplay", ["passStyle("]),
    "monoOn": ("submitDisplay", ["passStyle("]),
    # E1-5（2026-10-08 P0「真机编辑器打开全黑」）：render.style_diag 一次定位。
    # 字段行统一发在 passStyle **之后**（否则"没执行却报执行"），并要求
    # glReadPixels 也已在字段行之前 —— 输出统计才是本帧的真实读回值。
    "inMean": ("submitDisplay", ["passStyle(", "glReadPixels("]),
    "inP0": ("submitDisplay", ["passStyle(", "glReadPixels("]),
    "texNew": ("submitDisplay", ["passStyle(", "glReadPixels("]),
    "texIfmt": ("submitDisplay", ["passStyle(", "glReadPixels("]),
    "texW": ("submitDisplay", ["passStyle(", "glReadPixels("]),
    "texH": ("submitDisplay", ["passStyle(", "glReadPixels("]),
    "eTex": ("submitDisplay", ["passStyle(", "glReadPixels("]),
    "eSub": ("submitDisplay", ["passStyle(", "glReadPixels("]),
    "fbStatus": ("submitDisplay", ["passStyle(", "glReadPixels("]),
    "eFbo": ("submitDisplay", ["passStyle(", "glReadPixels("]),
    "capIfmt": ("submitDisplay", ["passStyle(", "glReadPixels("]),
    "ePass": ("submitDisplay", ["passStyle(", "glReadPixels("]),
    "eRead": ("submitDisplay", ["passStyle(", "glReadPixels("]),
    "outMean": ("submitDisplay", ["passStyle(", "glReadPixels("]),
    "outP0": ("submitDisplay", ["passStyle(", "glReadPixels("]),
}


def owner(lines, lineno):
    """向上找最近的 FrameRenderer 成员函数定义。"""
    for i in range(lineno - 1, -1, -1):
        m = re.match(r"^\w[\w:<>,&\s\*]*FrameRenderer::(\w+)\(", lines[i])
        if m:
            return m.group(1), i + 1
    return "?", 0


def main():
    names = sys.argv[1:] or list(EXPECT)
    lines = SRC.read_text().split("\n")
    ok = True
    for name in names:
        want_fn, prereqs = EXPECT.get(name, ("finishStillChain", []))
        # 找到"写入日志串"的那一行（形如 kv += ",\"<name>\":"）
        hits = [n for n, l in enumerate(lines, 1) if f'\\"{name}\\":' in l and "kv" in l]
        if not hits:
            print(f"FAIL {name:<6} 未找到写入日志串的语句")
            ok = False
            continue
        ln = hits[0]
        fn, fn_ln = owner(lines, ln)
        status = []
        if fn != want_fn:
            status.append(f"所属函数 {fn} != {want_fn}")
        for pre in prereqs:
            call_lines = [n for n, l in enumerate(lines, 1)
                          if pre in l and "FrameRenderer::" not in l]
            if not call_lines:
                status.append(f"找不到调用 {pre}")
            elif not any(fn_ln <= c < ln for c in call_lines):
                status.append(f"{pre} 不在本函数内或晚于字段行")
        if status:
            print(f"FAIL {name:<6} 行 {ln}（函数 {fn}）: " + "; ".join(status))
            ok = False
        else:
            print(f"OK   {name:<6} 行 {ln} 函数 {fn}（定义 {fn_ln}），前置调用齐备且更早")
    # 顺带查 GPU 程序生命周期 —— 只查白名单里的**本次新增** pass（对全仓历史程序
    # 检查会因合并赋值清零/变量名差异产生大量假阳性，噪音掩盖真问题）。
    WATCH_PROGS = ["raw_dn_ts", "chroma_down", "chroma_at", "chroma_apply",
                   "nn_pack", "nn_unpack"]
    txt = SRC.read_text()
    for prog in WATCH_PROGS:
        miss = []
        if f"glUseProgram(prog_{prog}_)" not in txt:
            miss.append("use")
        if f"if (prog_{prog}_) glDeleteProgram" not in txt:
            miss.append("delete")
        if not re.search(r"prog_%s_\s*=[^;]*=\s*0;|prog_%s_\s*=\s*0;" % (prog, prog), txt):
            miss.append("zero")
        if miss:
            print(f"FAIL prog_{prog}_ 生命周期缺项: {miss}")
            ok = False
    print("全部通过" if ok else "存在失败项")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
