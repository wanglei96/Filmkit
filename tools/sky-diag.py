#!/usr/bin/env python3
"""0.12.252 天空诊断包读数：把 `skyD4` / `skyFm` / `skyOut` 三段拉成一张表。

背景（2026-11-11）：DNG 天空 RAW 线性 0.147（rawpy 渲染 L* 39.5，正常蓝天），
成片却 L* 84.7 / G>=240 13.8%。用日志参数离线回放 filmic 只到 8bit 113-133，
中间还有一段没归因的抬亮 —— 0.12.252 在 D4 / filmic 输出 / 成片三处对
**同一块顶部天空**打剖面，这个脚本负责把三段对齐着看。

用法：
  python3 tools/sky-diag.py <日志.jsonl>            # 所有 raw.render
  python3 tools/sky-diag.py <日志.jsonl> --tail 3   # 只看最后 3 张

判读（先看亮度，再看 sep）：
  D4 就亮、Fm 正常          -> 前链（曝光/CCM/LSC）
  D4 正常、Fm 亮            -> filmic 段
  D4/Fm 都正常、Out 亮      -> output 段（OETF/LUT/闭环/滚降）
  亮度都对、只有 sep 塌     -> 纯去饱和（高光趋白 / LUT 蓝段）
"""
import json
import sys


def parse(field: str) -> dict:
    out = {}
    for part in field.split("|"):
        if "=" not in part:
            continue
        k, v = part.split("=", 1)
        try:
            out[k] = float(v) if k != "n" else int(v)
        except ValueError:
            out[k] = v
    return out


def quad(d: dict) -> str:
    if not d:
        return "—"
    return (f"{d.get('r', 0):.3f}/{d.get('g', 0):.3f}/{d.get('b', 0):.3f}"
            f" p50={d.get('p50', 0):.2f} p90={d.get('p90', 0):.2f} "
            f"sep={d.get('sep', 0):.3f}")


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    path = sys.argv[1]
    tail = 0
    if "--tail" in sys.argv:
        tail = int(sys.argv[sys.argv.index("--tail") + 1])
    rows = []
    for line in open(path, encoding="utf-8"):
        try:
            e = json.loads(line)
        except json.JSONDecodeError:
            continue
        if e.get("event") != "raw.render":
            continue
        kv = e["kv"]
        if "skyD4" not in kv:
            continue
        rows.append((e["ts"], kv))
    if tail:
        rows = rows[-tail:]
    if not rows:
        print("没有 skyD4/skyFm/skyOut：确认装的是 0.12.252+，且拍摄时导出的是同一份日志")
        return 1
    for ts, kv in rows:
        print(f"\n== {ts}  ev={kv.get('ev')} whiteEv={kv.get('whiteEv')} "
              f"dispAdd={kv.get('dispAdd')} hdrEv={kv.get('hdrEv')}")
        for name, key in (("D4  (线性, 曝光前)", "skyD4"),
                          ("Fm  (线性, filmic后)", "skyFm"),
                          ("Out (sRGB, 成片)", "skyOut")):
            d = parse(kv.get(key, ""))
            print(f"  {name:24s} n={d.get('n', 0):6d}  {quad(d)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
