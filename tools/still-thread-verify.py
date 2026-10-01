#!/usr/bin/env python3
"""拍照独立线程验收（v0.10.85）：一条命令出关键判据，并按「开关切换点」自动分组。

用法： python3 tools/still-thread-verify.py <日志文件或目录> [...]
（分析前先跑 tools/check-log-json.py —— 非法 JSON 行会被解析工具静默丢弃）

背景：拍照那条 1.6~3.4s 的合成链原来跑在预览渲染线程上，"拍完挪手机画面
完全冻住 1~2 秒"。修法 = 独立线程 + 独立 EGL context（prefs still_thread）。

判据（每组 = 一次开关状态下的拍摄）：
  1. 预览停更   render.preview_gap（相邻两次渲染间隔 400ms~10s 才记；
                >10s 视为切后台/切镜头重建预览；采集期 150~250ms 正常停顿被挡住）
                关开关：应出现 ≈1600~3400ms；开开关：应消失
  2. 拍照线程   render.still_thread_start(ok/initMs) / render.still_done(ms/queuedMs)
                + render.burst_done 按 path 的中位耗时（冷机连拍 3 张取中位）
  3. 同帧 A/B   render.still_ab 的 same（CRC32 逐字节等价）与两遍均值
  4. 落盘       photo.save_queued vs photo.saved（queued 有、saved 无 = 丢照片）

分组靠设置页写下的 settings.still_thread 事件；日志里没有该事件时（旧版本或
没切过开关）全部并成一组。
"""
import json
import statistics
import sys
from pathlib import Path

WATCH = (
    "settings.still_thread",
    "settings.still_ab",
    "render.preview_gap",
    "render.fps",
    "render.still_thread_start",
    "render.still_done",
    "render.still_ab",
    "render.burst_done",
    "render.still_fallback",
    "render.still_retry",
    "raw.upload_failed",
    "render.still_disabled",
    "engine.still_start",
    "engine.still_init_failed",
    "engine.still_missing",
    "render.raw_burst_error",
    "render.raw_burst_failed",
    "render.burst_dropped",
    "render.still_failed",
    "render.raw_failed",
    "capture.busy",
    "photo.save_queued",
    "photo.saved",
)

FAILS = (
    "engine.still_init_failed", "engine.still_missing", "render.raw_burst_error",
    "raw.upload_failed",
    "render.raw_burst_failed", "render.burst_dropped", "render.still_failed",
    "render.raw_failed",
)


def load(paths):
    rows = []
    for p in paths:
        files = sorted(p.rglob("*")) if p.is_dir() else [p]
        for f in files:
            if not f.is_file():
                continue
            for line in f.read_text(errors="replace").splitlines():
                line = line.strip()
                if not line.startswith("{"):
                    continue
                try:
                    d = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if d.get("event") in WATCH:
                    rows.append(d)
    rows.sort(key=lambda d: d.get("ts") or "")
    return rows


def group(rows):
    """按 settings.still_thread 切换点切段（事件已按时间排序）。"""
    groups, cur = [], ("（未记录开关切换）", [])
    for r in rows:
        if r["event"] == "settings.still_thread":
            on = (r.get("kv") or {}).get("on")
            cur = (f"still_thread {'开' if on else '关'}", [])
            groups.append(cur)
        else:
            cur[1].append(r)
    return groups or [cur]


def med(xs):
    return f"{statistics.median(xs):.0f}" if xs else "—"


def report(label, ev, fps_rows):
    print(f"\n════ 分组：{label} ────（{sum(len(v) for v in ev.values())} 条事件）")

    gaps = [k.get("gapMs") for k in ev["render.preview_gap"] if k.get("gapMs")]
    if gaps:
        top = sorted(gaps, reverse=True)[:5]
        print(f"[1] 预览停更 {len(gaps)} 次：中位 {med(gaps)}ms，最大 {max(gaps)}ms，前五 {top}")
        print("    → 出现 ≈1600~3400ms 说明长链仍在预览线程上（开关没生效？）")
    else:
        print("[1] 预览停更：无 ✓（>400ms 的停更没有发生）")

    print("[1b] 预览帧率（每 5 秒一条；数组 = 逐秒实际渲染帧数，<20 即肉眼可见卡顿）")
    if not fps_rows:
        print("    无（0.10.87 之前没有该事件）")
    for ts, fs in fps_rows:
        vals = [int(x) for x in fs.split(",") if x.strip().isdigit()]
        lo = min(vals) if vals else 0
        print(f"    {ts}  {fs}" + ("   ← 卡顿" if lo < 20 else ""))

    print("[2] 拍照线程")
    for k in ev["render.still_thread_start"]:
        ok = "OK" if k.get("ok") else "失败→回退预览线程"
        print(f"    启动 {ok}，initMs={k.get('initMs')}")
    for k in ev["engine.still_start"]:
        print(f"    引擎 engine.still_start fresh={k.get('fresh')}")
    ms = [k.get("ms") for k in ev["render.still_done"] if isinstance(k.get("ms"), (int, float))]
    qs = [k.get("queuedMs") for k in ev["render.still_done"]
          if isinstance(k.get("queuedMs"), (int, float))]
    if ms:
        print(f"    拍照线程合成 {len(ms)} 张：中位 {med(ms)}ms（连拍取中位口径），"
              f"排队中位 {med(qs)}ms")
    by_path = {}
    for k in ev["render.burst_done"]:
        by_path.setdefault(str(k.get("path")), []).append(k)
    for path, ks in by_path.items():
        m = [k.get("ms") for k in ks if isinstance(k.get("ms"), (int, float))]
        okc = sum(1 for k in ks if k.get("ok"))
        print(f"    burst[{path}] {len(ks)} 张：中位 {med(m)}ms，成功 {okc}/{len(ks)}")
    if not by_path and not ms:
        print("    （本组没有拍照线程/burst 记录）")

    print("[3] 同帧 A/B（CRC32 逐字节等价）")
    if not ev["render.still_ab"]:
        print("    无（未开独立线程 A/B 诊断，或还没拍照）")
    for k in ev["render.still_ab"]:
        verdict = "等价 ✓" if k.get("same") else "不一致 ✗（查 LUT/实例状态）"
        print(f"    {verdict}  crc {k.get('crcStill')} vs {k.get('crcPreview')}"
              f"  均值 {k.get('meanStill')} vs {k.get('meanPreview')}"
              f"  尺寸 {k.get('sizeStill')} / {k.get('sizePreview')}"
              f"  abMs={k.get('abMs')}")

    q, s = len(ev["photo.save_queued"]), len(ev["photo.saved"])
    print(f"[4] 落盘 queued={q} / saved={s}")
    for k in ev["photo.saved"]:
        luma = k.get("avgLuma")
        flag = "  ← 疑似全黑！" if isinstance(luma, (int, float)) and luma < 5 else ""
        print(f"    {k.get('name')} {k.get('w')}x{k.get('h')} avgLuma={luma}{flag}")
    if q > s:
        print(f"    !! 有 {q - s} 张入队但未落盘 —— 先看下面的失败/回退事件")
    elif s > q and q > 0:
        print("    注：saved 多于 queued —— 0.10.80 之前没有异步保存队列，"
              "老日志的 queued 只覆盖后半段")
    for name in FAILS:
        if ev[name]:
            print(f"    !! {name} × {len(ev[name])}")
    if ev["render.still_retry"]:
        ok = sum(1 for k in ev["render.still_retry"] if k.get("ok"))
        print(f"    · 合成失败后自愈重试 × {len(ev['render.still_retry'])}（成功 {ok}）")
    if ev["render.still_fallback"]:
        print(f"    ! 拍照线程合成失败、已回退预览线程重跑 × "
              f"{len(ev['render.still_fallback'])}（照片没丢，但该查原因）")
    if ev["render.still_disabled"]:
        print(f"    !! 拍照线程熔断 × {len(ev['render.still_disabled'])}（连续 init 失败）")
    if ev["capture.busy"]:
        print(f"    注：capture.busy × {len(ev['capture.busy'])}（在飞上限 2，第 3 张被忽略）")


def main():
    args = [Path(a) for a in sys.argv[1:]]
    if not args:
        print(__doc__)
        return 2
    rows = load(args)
    if not rows:
        print("没有找到相关事件（确认路径，或先跑 check-log-json.py 看是否有非法行被丢弃）")
        return 1
    print(f"== 拍照独立线程验收（v0.10.85） · {len(rows)} 条相关事件 · "
          f"{rows[0].get('ts')} → {rows[-1].get('ts')} ==")
    for label, ev in group(rows):
        buckets = {k: [] for k in WATCH}
        fps_rows = []
        for r in ev:
            buckets[r["event"]].append(r.get("kv") or {})
            if r["event"] == "render.fps":
                fps_rows.append((r.get("ts", "")[11:23],
                                 str((r.get("kv") or {}).get("fps"))))
        report(label, buckets, fps_rows)
    return 0


if __name__ == "__main__":
    sys.exit(main())
