#!/usr/bin/env python3
"""日志 JSON 合法性检查（2026-09-30，被非法 JSON 坑过之后固化）。

背景：`std::to_string(1139.4).substr(0, 5)` = `"1139."` 是非法 JSON ——
整行会被任何 JSON 解析工具**静默丢弃**。实测一次 4 张照片的 raw.render
只有 1 条能被 json.loads 解析，另 3 条消失，差点被误判成"日志链路缺数据"。
修复见 FrameRenderer.cpp 的 jnum()。这个脚本是它的回归守卫：拿到任何
导出日志，先跑一遍，再谈数据分析。

用法：
  python3 tools/check-log-json.py <log.jsonl> [更多日志...]
  python3 tools/check-log-json.py test_temp/logs/*.jsonl     # 退出码 1 = 有非法行
"""
import json
import pathlib
import sys


def check(path: pathlib.Path) -> int:
    bad = 0
    total = 0
    events: dict[str, int] = {}
    for i, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
        if not line.strip():
            continue
        total += 1
        try:
            o = json.loads(line)
        except Exception as e:
            bad += 1
            col = getattr(e, "pos", 0)
            print(f"  {path.name}:{i} 非法 JSON ({e})")
            print(f"      {line[max(0, col - 40):col + 20]!r}")
            continue
        ev = o.get("event", "?")
        events[ev] = events.get(ev, 0) + 1
    rate = f"{100.0 * bad / total:.2f}%" if total else "-"
    flag = "OK  " if bad == 0 else "FAIL"
    print(f"{flag} {path.name}: {total} 行, 非法 {bad} ({rate})")
    if bad == 0 and events:
        keys = ("raw.render", "mfnr.stage", "photo.saved", "settings.align_l1")
        shown = ", ".join(f"{k}={events[k]}" for k in keys if k in events)
        if shown:
            print(f"     关键事件: {shown}")
    return bad


def main() -> int:
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 2
    total_bad = 0
    for a in args:
        p = pathlib.Path(a)
        if not p.exists():
            print(f"跳过（不存在）: {a}")
            continue
        total_bad += check(p)
    return 1 if total_bad else 0


if __name__ == "__main__":
    sys.exit(main())
