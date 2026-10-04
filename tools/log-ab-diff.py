#!/usr/bin/env python3
"""raw.render 日志 A/B 对拍（重构第 1 期验收工具，对应 docs/refactor-architecture.md §5 第 1b 期）。

为什么需要它：第 1 期承诺"成片不变"，但引擎没有"同一张 DNG 重渲染"入口，成片又受拍摄
时刻影响 ⇒ 真机无法逐位对比像素。可复现的中间证据只剩 raw.render 日志：同一场景、同一
拍摄链路下，除时间/帧数外，其余字段应当在两版之间保持一致。

用法：
    python3 tools/log-ab-diff.py 改动前.jsonl 改动后.jsonl [更多.jsonl ...]
    python3 tools/log-ab-diff.py --tol 1.0 --scene-tol 10 --color always A.jsonl B.jsonl
    python3 tools/log-ab-diff.py --selftest          # 内置合成样本自检（不读真机日志）
    第 1 份为基线，其余各与基线对拍。
    退出码：0 = 等价 / 1 = 有差异 / 2 = 无法判定（两者都非零：不能证明等价就不算过）。

日志格式（字段名取自 2026-10-04 真机日志 0.10.150，非臆造）：
    {"ts":"2026-10-04T18:26:35.659+08:00","level":"info","module":"engine",
     "event":"raw.render","kv":{...113 字段...}}
只取 event == raw.render 的记录；逐字段对比 kv（顶层 ts 属时间类，level/module 不参与）。
日志数值为 %.8g 打印，本工具同精度显示，便于与原文逐字核对。

字段分类：
  不变量   跨版本应逐位相同。布尔/整型开关：hlOk、hrStatRaw、auto、df、nn、cms、
           alignL1、w、h、tiles、p0s、nzN。字符串枚举（自动识别，无需登记）：
           demosaic、nzSrc、mfnr、fuse、lsc、misD/dilD/motD/msmD。样本中逐位相同的
           配置常量：dn、kP0/kP4、tone、cp、lt、sh、gm、neff、rn、fPow、fCon、wb、
           ⚠ ns **不是不变量**（噪声模型输出，随 ISO 变）—— 见 ISO_DEPENDENT_KEYS。
           fM1..fM5、fLat、hlRef2Hit、hlRefGHit、hrWhiteEv。
           判定容差 1e-6%（样本内实测 0.0000%）。字符串比取值集合，数值比中位。
  数值量   其余全部数值/数组字段（hlChroma、nzS/nzO/nzG、p0*/p4*、rms*、hlSum、hlCnt3、
           dftProbe、hlCnt、clipB、hlRefW、hrThr、dMed/dP90、confMed…）：报中位 / 极值 /
           相对差，超 --tol 标红。多帧时数组展平后取中位。
  允许变化 时间/性能量（*Ms 结尾：uploadMs、passMs、totalMs、hlMs、dirMs、dnMs、hrMs、
           alignMs、alignRbMs、alignCpuMs、misMs、fuseMs；非 Ms 结尾的耗时 dftGpu、
           dftRb、dftDsp）与帧数：单独列出、**不计入失败**。

判定口径：
  · 任一版无 raw.render 记录                              → 无法判定（样本不足）
  · 场景指纹相对差超 --scene-tol                          → 无法判定（场景不同，数值不可比）
  · 不变量差异 / 数值量超阈值 / 字段增删 / 数组长度变化    → 有差异
  · 以上皆无                                              → 等价
  场景指纹 = linMid、pLo、pHi、ev、blackEv、whiteEv。前四者是输入侧影调/曝光统计，跨场景
  差异大；blackEv/whiteEv 实测在两份**不同场景**日志中逐位相同（-7.654144 / 5.551635，
  设备标定常量），只在换机型或改标定时才变。
  ⚠ 实测：两份样本日志场景不同 → linMid 差 31.8%、pLo 差 49.4%、pHi 差 5.4%，本工具据此
    判"不可比"而不是把上百项数值差异报成回归。
  ⚠ 若重构本身改动了自动影调/曝光，指纹也会变 ⇒ 报"不可比"而非"等价"，方向保守。

采集姿势（决定工具能不能给出结论）：
  两份日志必须**同场景、同曝光、同构图、时刻紧邻**地各拍一张。实测反例：样本 A 版两帧
  （同一次拍摄会话内相隔 40 秒）linMid 差 10.3%、pHi 差 10.3%、hlCnt 差 61.7%、
  dMed 差 52.4% —— 连"同一次会话的两张"都可能采样条件不同，本工具会（正确地）判为
  不可比。切勿把不同时刻/不同构图的两张日志当作 A/B 基线。

阈值来源（工程初值，按下列实测标定；均可用参数覆盖）：
  · --tol 1.0%：口径是"同一次曝光"。样本内确定性字段（fM*、fLat、fPow、tone…）在不同
    场景间差 0.0000%，场景/噪声相关字段（linMid、rms、hlCnt、dftDsp…）差 5%~63%，
    1% 落在两簇之间。docs/refactor-architecture.md §5 第 1b 期明确"GLSL 重编译允许 FMA
    contraction 与指令重排、逐位无保证"⇒ 阈值不能取 0；1% 远离 float32 重排噪声
    （~1e-6 相对），又小于真实回归的量级。跨时刻拍摄请自行放宽（见上"采集姿势"）。
  · --scene-tol 10%：判"同场景"的门槛。实测跨场景样本 linMid 31.8% / pLo 49.4%，
    同一会话相隔 40 秒的另一对 linMid、pHi 10.3%。10% 取在已观测到的条件下沿之下，
    方向保守：宁可判"不可比"（2），也不把采样差异说成回归。放宽到 20% 会把上述 10.3%
    那对误判成"同场景、有差异"。
  · 不变量容差 1e-6%：样本内不变量逐位相同（44 项跨两个不同场景全部一致），容差只吸收
    浮点打印/存储误差。
  · 浮点噪声地板 1e-9：日志内最小量级字段为 nzO≈7e-7，低两个数量级；两值绝对值都低于
    地板时相对差记 0，避免 0 vs 1e-13 这类假差异。

局限：字段分类靠字段名白名单，未登记的字段默认落"数值量"（安全降级：同值不报、差异才
报）；若新字段属不变量，请加进 INVARIANT_KEYS 并同步"日志字段冻结清单"。--event 可对拍
其他 event，但那些 event 的分类表未标定，只应看"字段增删"与"不变量/数值量"粗略分组。
"""
import argparse
import json
import math
import os
import re
import statistics
import sys
from collections import defaultdict

EVENT_DEFAULT = "raw.render"
TIME_SUFFIX = re.compile(r"Ms$")
# 非 Ms 结尾的耗时（样本实测 532.6 / 13.65 / 772.3，与 fuseMs 同量级）
PERF_EXTRA = frozenset({"dftGpu", "dftRb", "dftDsp"})
SCENE_KEYS = ("linMid", "pLo", "pHi", "ev", "blackEv", "whiteEv")
INVARIANT_KEYS = frozenset({
    # 整型/布尔开关与结构常量
    "hlOk", "hrStatRaw", "auto", "df", "nn", "cms", "alignL1", "w", "h", "tiles", "p0s", "nzN",
    # 样本中逐位相同的浮点配置常量
    "dn", "kP0", "kP4", "tone", "cp", "lt", "sh", "gm", "neff", "rn", "fPow", "fCon",
    "hlRef2Hit", "hlRefGHit", "hrWhiteEv", "wb",
    "fM1", "fM2", "fM3", "fM4", "fM5", "fLat",
})
# 随 ISO / AWB 变化，**不是**不变量（2026-10-04 实测更正）。
# `ns = nc.ns_typ`（噪声模型输出，由 NoiseModel::afterWb(gains) 决定）：
# 同场景内逐位一致（0.0034/0.0034、0.0024/0.0024），但跨 ISO 就变
# （1a: 触发 ISO 3223 / 拍摄 3557 → ns 0.0034；1b: 拍摄 2464 → ns 0.0024）。
# 原先 ns 在 INVARIANT_KEYS 里，会把这种正常的 ISO 差异误报成"跨版本回归"。
ISO_DEPENDENT_KEYS = frozenset({"ns", "nsHal", "nsSng", "nzS", "nzO", "nzG"})
NOISE_FLOOR = 1e-9   # 绝对值低于此量级视为浮点噪声
INV_TOL = 1e-6       # 不变量相对容差 %


class Paint:
    """ANSI 上色。always/never 无条件生效（显式参数优先），auto 看 tty 并遵守 NO_COLOR。"""

    def __init__(self, mode):
        self.on = mode == "always" or (mode == "auto" and sys.stdout.isatty()
                                       and not os.environ.get("NO_COLOR"))

    def _wrap(self, code, s):
        return f"\033[{code}m{s}\033[0m" if self.on else s

    def red(self, s):
        return self._wrap("31", s)

    def yellow(self, s):
        return self._wrap("33", s)

    def green(self, s):
        return self._wrap("32", s)

    def dim(self, s):
        return self._wrap("2", s)

    def bold(self, s):
        return self._wrap("1", s)


def fmt(x):
    """按日志同精度（%.8g）显示，整数去掉 .0。"""
    if isinstance(x, bool):
        return str(x)
    if isinstance(x, int):
        return str(x)
    if x == int(x) and abs(x) < 1e15:
        return str(int(x))
    return f"{x:.8g}"


def rel_pct(a, b):
    """对称相对差（%）；两值都在浮点噪声地板内记 0。"""
    m = max(abs(a), abs(b))
    return 0.0 if m < NOISE_FLOOR else abs(a - b) / m * 100.0


def flat(values):
    """逐帧值展平成标量列表：数组按元素展开；bool/字符串/None/NaN 丢弃（NaN 丢弃后该字段
    会成为"数值缺失"并标红，避免 nan 比较恒 False 造成静默漏报）。"""
    out = []
    for v in values:
        if isinstance(v, bool):
            continue
        if isinstance(v, (int, float)):
            if math.isfinite(v):
                out.append(v)
        elif isinstance(v, list):
            out.extend(x for x in v if not isinstance(x, bool) and isinstance(x, (int, float))
                       and math.isfinite(x))
    return out


def has_str(values):
    return any(isinstance(v, str) or (isinstance(v, list) and any(isinstance(x, str) for x in v))
               for v in values)


def classify(key, values):
    if TIME_SUFFIX.search(key) or key in PERF_EXTRA:
        return "time"
    if key in SCENE_KEYS:
        return "scene"
    if has_str(values) or key in INVARIANT_KEYS:
        return "invariant"
    return "numeric" if flat(values) else "invariant"   # 非数值非字符串 → 按不变量严格比


def load(path, event):
    """读 JSONL，收集该 event 的逐帧 kv 值、数组长度、帧数与时间戳。坏行跳过并计数。"""
    vals, arity = defaultdict(list), defaultdict(set)
    frames, stamps, bad = 0, [], 0
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                bad += 1
                continue
            if not isinstance(rec, dict) or rec.get("event") != event:
                continue
            frames += 1
            if rec.get("ts"):
                stamps.append(str(rec["ts"]))
            for k, v in (rec.get("kv") or {}).items():
                vals[k].append(v)
                if isinstance(v, list):
                    arity[k].add(len(v))
    return {"path": str(path), "vals": vals, "arity": arity,
            "frames": frames, "stamps": stamps, "bad": bad}


def inv_same(va, vb):
    """不变量比较：(是否相同, A 显示, B 显示)。字符串/非数值比取值集合，数值比中位。"""
    sa = {v for v in va if isinstance(v, str)}
    sb = {v for v in vb if isinstance(v, str)}
    if sa or sb:
        return sa == sb, "/".join(sorted(sa)) or "-", "/".join(sorted(sb)) or "-"
    xa, xb = flat(va), flat(vb)
    if not xa and not xb:
        return repr(va) == repr(vb), repr(va), repr(vb)
    if not xa or not xb:
        return False, f"n={len(xa)}", f"n={len(xb)}"
    ma, mb = statistics.median(xa), statistics.median(xb)
    return rel_pct(ma, mb) < INV_TOL, fmt(ma), fmt(mb)


def compare(base, cur, event, tol, scene_tol, paint):
    """对拍一份基线 vs 一份对比，打印报告，返回 0/1/2。"""
    print(paint.bold(f"════ {event} 日志对拍 ════"))
    for tag, lg in (("基线 A", base), ("对比 B", cur)):
        span = f"{lg['stamps'][0]} ~ {lg['stamps'][-1]}" if lg["stamps"] else "无时间戳"
        note = f"，跳过非 JSON 行 {lg['bad']}" if lg["bad"] else ""
        print(f"{tag}：{lg['path']}  ({event} {lg['frames']} 帧，{span}{note})")
    print(f"阈值  ：数值 {tol:g}% | 不变量 {INV_TOL:g}% | 场景指纹 {scene_tol:g}%")
    if base["frames"] == 0 or cur["frames"] == 0:
        print(paint.red(f"\n✗ 缺少 {event} 记录（A {base['frames']} 帧 / B {cur['frames']} 帧）"))
        print(paint.red(f"\n════ 判定：无法判定（样本不足）════"))
        return 2

    common = sorted(set(base["vals"]) & set(cur["vals"]))
    only_a = sorted(set(base["vals"]) - set(cur["vals"]))
    only_b = sorted(set(cur["vals"]) - set(base["vals"]))
    groups = defaultdict(list)
    for k in common:
        groups[classify(k, base["vals"][k] + cur["vals"][k])].append(k)

    # ---- 场景指纹 ----
    print(paint.bold(f"\n── 场景指纹（决定数值对比是否可靠）──"))
    scene_bad = []
    for k in groups["scene"]:
        xa, xb = flat(base["vals"][k]), flat(cur["vals"][k])
        if not xa or not xb:
            continue
        ma, mb = statistics.median(xa), statistics.median(xb)
        d = rel_pct(ma, mb)
        row = f"  {'✗' if d >= scene_tol else '✓'} {k:<9} A {fmt(ma):<12} B {fmt(mb):<12} Δ {d:6.2f}%"
        if d >= scene_tol:
            scene_bad.append(k)
            print(paint.red(row))
        else:
            print(paint.dim(row))
    if not groups["scene"]:
        print(paint.yellow("  ⚠ 本 event 无已标定的场景指纹字段：无法确认两份日志同场景，"
                           "数值差异可能含场景噪声"))
    elif scene_bad:
        print(paint.yellow(f"  ⚠ 指纹不匹配（{', '.join(scene_bad)} 超 {scene_tol:g}%）："
                           f"两者可能不是同一场景/曝光，数值对比不可靠"))
    else:
        print(paint.green("  ✓ 指纹一致：两份日志可视为同场景同曝光"))

    # ---- 不变量 ----
    inv_bad = []
    for k in groups["invariant"]:
        same, sa, sb = inv_same(base["vals"][k], cur["vals"][k])
        if not same:
            inv_bad.append(k)
    print(paint.bold(f"\n── 不变量 {len(groups['invariant'])} 项（跨版本应逐位相同）──"))
    if inv_bad:
        for k in inv_bad:
            _, sa, sb = inv_same(base["vals"][k], cur["vals"][k])
            print(paint.red(f"  ✗ {k:<14} A {sa:<22} B {sb}"))
        print(paint.red(f"  → {len(inv_bad)} 项不变量被改动（与场景无关，可信）"))
    else:
        print(paint.green(f"  ✓ 全部一致：{', '.join(groups['invariant'])}"))

    # ---- 数值量 ----
    num_bad, num_ok = [], []
    for k in groups["numeric"]:
        xa, xb = flat(base["vals"][k]), flat(cur["vals"][k])
        if not xa or not xb:
            num_bad.append((k, xa, xb))
            continue
        if rel_pct(statistics.median(xa), statistics.median(xb)) >= tol:
            num_bad.append((k, xa, xb))
        else:
            num_ok.append(k)
    print(paint.bold(f"\n── 数值量 {len(groups['numeric'])} 项（阈值 {tol:g}%，超阈值标红）──"))
    for k, xa, xb in num_bad:
        if not xa or not xb:
            print(paint.red(f"  ✗ {k:<14} 数值缺失：A n={len(xa)} B n={len(xb)}"))
            continue
        ma, mb = statistics.median(xa), statistics.median(xb)
        rng = ""
        if min(xa) != max(xa) or min(xb) != max(xb):
            rng = f"  [A {fmt(min(xa))}~{fmt(max(xa))} | B {fmt(min(xb))}~{fmt(max(xb))}]"
        print(paint.red(f"  ✗ {k:<14} A {fmt(ma):<12} B {fmt(mb):<12} "
                        f"Δ {rel_pct(ma, mb):6.2f}%{rng}"))
    if num_bad:
        print(paint.dim(f"  （{len(num_bad)} 项超阈值，{len(num_ok)} 项一致）"))
    else:
        print(paint.green(f"  ✓ 全部在阈值内：{', '.join(num_ok)}"))

    # ---- 允许变化 ----
    print(paint.bold(f"\n── 允许变化 {len(groups['time'])} 项（时间/性能，不计失败）──"))
    for k in groups["time"]:
        xa, xb = flat(base["vals"][k]), flat(cur["vals"][k])
        if not xa or not xb:
            print(paint.dim(f"  ~ {k:<14} 缺失：A n={len(xa)} B n={len(xb)}"))
            continue
        ma, mb = statistics.median(xa), statistics.median(xb)
        print(paint.dim(f"  ~ {k:<14} A {fmt(ma):<12} B {fmt(mb):<12} Δ {rel_pct(ma, mb):6.2f}%"))
    print(paint.dim(f"  ~ 帧数           A {base['frames']:<12} B {cur['frames']}"))
    if base["frames"] != cur["frames"]:
        print(paint.dim("  （帧数不同：中位对比样本量不对称，极值列仅供参考）"))

    # ---- 字段增删 / 结构 ----
    print(paint.bold(f"\n── 字段增删（日志字段冻结清单）──"))
    if only_a or only_b:
        for k in only_a:
            print(paint.red(f"  − 仅 A 有：{k}"))
        for k in only_b:
            print(paint.red(f"  + 仅 B 有：{k}"))
    else:
        print(paint.green(f"  ✓ 字段集合一致（{len(common)} 项）"))
    arity_bad = [k for k in common if base["arity"].get(k, set()) != cur["arity"].get(k, set())]
    if arity_bad:
        for k in arity_bad:
            print(paint.red(f"  ✗ {k} 数组长度变化：A {sorted(base['arity'].get(k, []))} "
                            f"B {sorted(cur['arity'].get(k, []))}"))

    # ---- 判定 ----
    if scene_bad:
        code, verdict = 2, f"无法判定（场景不同：{', '.join(scene_bad)} 超 {scene_tol:g}%）"
    elif inv_bad or num_bad or only_a or only_b or arity_bad:
        code, verdict = 1, "有差异"
    else:
        code, verdict = 0, "等价"
    detail = []
    if inv_bad:
        detail.append(f"不变量 {len(inv_bad)} 项")
    if num_bad:
        detail.append(f"数值超阈值 {len(num_bad)} 项")
    if only_a or only_b:
        detail.append(f"字段增删 {len(only_a) + len(only_b)} 项")
    if arity_bad:
        detail.append(f"数组长度变化 {len(arity_bad)} 项")
    if code == 2 and detail:      # 场景不同时仍要点出不变量差异（与场景无关）
        verdict += "；另：" + "、".join(detail)
    elif detail:
        verdict += "：" + "、".join(detail)
    mark = {0: paint.green("✓"), 1: paint.red("✗"), 2: paint.yellow("?")}[code]
    line = f"\n════ 判定：{mark} {verdict} ════"
    print(paint.red(line) if code == 1 else (paint.yellow(line) if code == 2 else paint.green(line)))
    return code


def selftest():
    """合成样本自检：等价 / 不变量改动 / 场景不同 / 字段增删 四条路径。"""
    import contextlib
    import io
    import tempfile

    base_kv = {
        "hlOk": 1, "hrStatRaw": -1, "w": 4080, "h": 3072, "demosaic": "rcd", "tone": 1.0,
        "fM1": [0.058565, 1.343685, -0.390346], "linMid": 0.25, "pLo": 0.0075, "pHi": 0.7,
        "blackEv": -7.654144, "whiteEv": 5.551635, "ev": 0.296, "nzPost": [0.0031, 0.00075],
        "dftDsp": 772.3, "uploadMs": 917.9, "tiles": 768, "rms": [0.048, 0.031],
    }

    def write(kvs):
        fh = tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False, encoding="utf-8")
        for kv in kvs:
            fh.write(json.dumps({"ts": "2026-10-04T18:00:00.000+08:00", "level": "info",
                                 "module": "engine", "event": EVENT_DEFAULT, "kv": kv}) + "\n")
        fh.close()
        return fh.name

    def run(ka, kb):
        pa, pb = write(ka), write(kb)
        try:
            a, b = load(pa, EVENT_DEFAULT), load(pb, EVENT_DEFAULT)
            with contextlib.redirect_stdout(io.StringIO()):
                return compare(a, b, EVENT_DEFAULT, 1.0, 10.0, Paint("never"))
        finally:
            os.unlink(pa)
            os.unlink(pb)

    cases = [
        ("等价（时间量变化不计失败）", [base_kv], [{**base_kv, "uploadMs": 1500.0}], 0),
        ("不变量改动 hlOk 1→0", [base_kv], [{**base_kv, "hlOk": 0}], 1),
        ("数值超阈值 nzPost 翻倍", [base_kv], [{**base_kv, "nzPost": [0.0062, 0.0015]}], 1),
        ("场景不同 linMid -40%", [base_kv], [{**base_kv, "linMid": 0.15}], 2),
        ("字段增删", [base_kv], [{**base_kv, "newField": 1}], 1),
        ("NaN 不静默漏报", [base_kv], [{**base_kv, "nzPost": [float("nan"), 0.00075]}], 1),
        ("缺 raw.render 记录", [], [base_kv], 2),
    ]
    bad = []
    for name, ka, kb, want in cases:
        got = run(ka, kb)
        print(f"  {'OK  ' if got == want else 'FAIL'} {name}: 期望 {want} 实得 {got}")
        if got != want:
            bad.append(name)
    print("自检通过" if not bad else f"自检失败：{bad}")
    return 1 if bad else 0


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="raw.render 日志 A/B 对拍：判定两版引擎在同场景拍摄下行为是否等价",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="退出码：0=等价 1=有差异 2=无法判定。第 1 份日志为基线。")
    ap.add_argument("logs", nargs="*", help="引擎日志 JSONL；第 1 份为基线，其余与它对比")
    ap.add_argument("--event", default=EVENT_DEFAULT, help=f"对拍的 event（默认 {EVENT_DEFAULT}）")
    ap.add_argument("--tol", type=float, default=1.0, help="数值量相对差阈值 %%，默认 1.0")
    ap.add_argument("--scene-tol", type=float, default=10.0,
                    help="场景指纹相对差阈值 %%，默认 10.0")
    ap.add_argument("--color", choices=("auto", "always", "never"), default="auto",
                    help="标红开关；auto=仅 tty 上色（默认）")
    ap.add_argument("--selftest", action="store_true", help="跑内置合成样本自检后退出")
    args = ap.parse_args(argv)

    if args.selftest:
        return selftest()
    if len(args.logs) < 2:
        ap.error("至少需要两份日志（基线 + 对比）")

    paint = Paint(args.color)
    base = load(args.logs[0], args.event)
    if args.event != EVENT_DEFAULT:
        print(paint.yellow(f"⚠ --event {args.event}：字段分类表按 {EVENT_DEFAULT} 标定，仅供参考"))
    codes = []
    for i, path in enumerate(args.logs[1:]):
        if i:
            print()
        codes.append(compare(base, load(path, args.event), args.event,
                             args.tol, args.scene_tol, paint))
    if len(codes) > 1:
        print(paint.bold(f"\n合计：{codes.count(0)} 等价 / {codes.count(1)} 有差异 / "
                         f"{codes.count(2)} 无法判定"))
    return 1 if 1 in codes else (2 if 2 in codes else 0)


if __name__ == "__main__":
    sys.exit(main())
