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
