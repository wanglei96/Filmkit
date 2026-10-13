#!/usr/bin/env python3
"""参数契约一致性（FilmParams 槽位 ↔ Kotlin 镜像）——出包前必跑。

为什么需要它（v0.10.196 真实事故）：`FilmParams.h::kCount` 59→60 时 Kotlin 侧
`CameraPresets` 仍是 59 ⇒ 下发点写 `filmParams[59]` 越界 ⇒ **装包后应用启动即崩**。
JVM 单测读不到 C++ 常量，两个既有守卫各只覆盖一半：
  - `CameraPresetsTest.lengthMatchesContract` 断言的是 Kotlin 常量**硬编码值**（98/99…），
    它防的是"Kotlin 忘了改"，防不住"C++ kCount 漂移了而没人发现"；
  - `FilmParamKeysTest` 把语义名表与 `FilmParams.h` **字段声明行**逐项对拍，
    但它压根不看 `kCount`。
于是存在一个两边都不管的缝：**`kCount` 本身**。本脚本把三条事实摆在一起对：
`kCount`、字段声明的数量/覆盖（0..kCount-1 且不重号）、Kotlin `COUNT`。

用法：python3 tools/check-filmparams-contract.py   （0 = 通过，1 = 不一致）

自检（本脚本的 mutation 验证）：置 `FILMPARAMS_ROOT` 指向一份改坏的副本即可 ——
  FILMPARAMS_ROOT=/tmp/bad python3 tools/check-filmparams-contract.py
"""
import os
import pathlib
import re
import sys

ROOT = pathlib.Path(os.environ.get("FILMPARAMS_ROOT") or pathlib.Path(__file__).resolve().parent.parent)
CPP = ROOT / "core/engine/src/film/FilmParams.h"
KOTLIN_PRESETS = ROOT / "app/src/main/java/com/filmkit/app/ui/CameraPresets.kt"
KOTLIN_KEYS = ROOT / "app/src/main/java/com/filmkit/app/camera/EditorExport.kt"

# 与 FilmParamKeysTest.declaredSlots() 同一条正则（改一处必须改两处）。
DECL = re.compile(
    r"float\s+(\w+)(?:\[(\d+)])?\s*=\s*(?:\{[^}\n]*}|[^;\n]*);\s*//\s*(\d+)(?:\.\.(\d+))?"
)


def main() -> int:
    src = CPP.read_text(encoding="utf-8")
    bad: list[str] = []

    # ── 1. kCount ──────────────────────────────────────────────────────────
    m = re.search(r"static constexpr int kCount = (\d+);", src)
    if not m:
        sys.exit("FAIL: 找不到 `static constexpr int kCount = N;`")
    kcount = int(m.group(1))
    print(f"C++  FilmParams.h::kCount = {kcount}")

    # ── 2. 字段声明覆盖 0..kCount-1，且不重号 ─────────────────────────────
    slots: dict[int, str] = {}
    dup: list[int] = []
    for mm in DECL.finditer(src):
        name, ln, start, end = mm.group(1), mm.group(2), int(mm.group(3)), mm.group(4)
        if ln:  # 数组字段按 `名字[下标]` 展开
            n = int(ln)
            want = int(end) - start + 1
            if n != want:
                bad.append(f"数组声明区间与下标数不符：{mm.group(0)!r}")
                continue
            for i in range(n):
                if start + i in slots:
                    dup.append(start + i)
                slots[start + i] = f"{name}[{i}]"
        else:
            if start in slots:
                dup.append(start)
            slots[start] = name
    if dup:
        bad.append(f"槽位重复声明：{sorted(set(dup))}")
    # [29] 是已删除的 hl_recon，索引**留空不复用**（FilmParams.h:68-71）⇒ 不算缺声明。
    missing = [i for i in range(kcount) if i not in slots and i != 29]
    extra = sorted(i for i in slots if i >= kcount)
    print(f"C++  声明覆盖 {len(slots)} 个槽（max = {max(slots) if slots else '-'}）")
    if missing:
        bad.append(f"kCount = {kcount} 但这些槽位在 FilmParams.h 里没有声明：{missing}")
    if extra:
        bad.append(f"这些槽位 ≥ kCount（{kcount}）却仍有声明：{extra}")

    # ── 3. Kotlin COUNT ───────────────────────────────────────────────────
    kt = KOTLIN_PRESETS.read_text(encoding="utf-8")
    m2 = re.search(r"const val COUNT = (\d+)", kt)
    if not m2:
        sys.exit("FAIL: 找不到 `const val COUNT = N`")
    count = int(m2.group(1))
    print(f"Kt   CameraPresets.COUNT = {count}")
    if count != kcount:
        bad.append(
            f"长度不一致：kCount = {kcount} vs COUNT = {count}"
            "（v0.10.196 的事故形态，下发点会越界写 ⇒ 启动即崩）"
        )

    # ── 4. 语义名表覆盖（不重号、数量与槽位对齐；[29] 是留空不复用的历史槽） ──
    keys_src = KOTLIN_KEYS.read_text(encoding="utf-8")
    pairs = re.findall(r"^\s*(\d+) to \"(\w+(?:\[\d+])?)\",", keys_src, re.M)
    named = {int(k): v for k, v in pairs}
    print(f"Kt   FilmParamKeys.bySlot = {len(named)} 项")
    if len(named) != len(pairs):
        bad.append("bySlot 里有重复槽位键")
    unnamed = [i for i in range(kcount) if i not in named and i != 29]
    if unnamed:
        bad.append(f"这些槽位没有语义名（[29] 除外）：{unnamed}")
    # C++ 字段名 ↔ Kotlin 语义名必须逐字相同
    mismatch = [
        (i, slots[i], named[i]) for i in sorted(named) if i in slots and slots[i] != named[i]
    ]
    if mismatch:
        bad.append(f"字段名与语义名不一致（槽, C++, Kotlin）：{mismatch}")

    print()
    if bad:
        for b in bad:
            print(f"FAIL: {b}")
        return 1
    print("PASS: kCount / 字段声明覆盖 / Kotlin COUNT / 语义名表 四处一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
