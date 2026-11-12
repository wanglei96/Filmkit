#!/usr/bin/env python3
"""预览卡顿（体感）测量：从录屏里找「画面冻结」区间，并与日志时间轴对齐。

为什么需要它（2026-11-12）：
  `render.preview_gap` / `render.preview_arrive_gap` 量的是**帧**的间隔，固定机位
  能测（画面不变也照测），但用户感知的卡顿是"画面应该跟手动却没动"——固定机位
  时画面本来就不动，所以体感测不出来。录屏 + 帧差能直接量出"屏幕上真实冻结了多久"。

用法：
  python3 tools/preview-stall.py rec.mp4 [--min-ms 250] [--scene 0.004] [--json]
  可选：--log shot.log 同时打印日志里同一时间窗的 preview gap 事件

判读：
  · 冻结区间（scene 分数持续低于阈值）落在快门按下之后 ⇒ 体感卡顿坐标
  · 与 logs 的 preview_arrive_gap 对得上 ⇒ HAL 停帧；只有 preview_gap 对得上 ⇒ 我们的渲染
"""
import argparse
import json
import re
import subprocess
import sys

# ffmpeg 的 scene 分数是「与上一帧的差异」，0..1。摇镜时通常 0.05+，静止 0.001 量级。
DEFAULT_SCENE = 0.004
DEFAULT_MIN_MS = 250


def frame_scores(path):
    """返回 [(pts_seconds, scene_score)]。用 metadata=print 把 select 的 scene 打出来。"""
    cmd = [
        "ffmpeg", "-hide_banner", "-nostats", "-i", path,
        "-vf", "select='gte(scene,0)',metadata=print:file=-",
        "-an", "-f", "null", "-",
    ]
    p = subprocess.run(cmd, capture_output=True, text=True)
    text = p.stdout + p.stderr
    out = []
    cur_t = None
    for line in text.splitlines():
        m = re.search(r"pts_time:([0-9.]+)", line)
        if m:
            cur_t = float(m.group(1))
            continue
        m = re.search(r"lavfi\.scene_score=([0-9.]+)", line)
        if m and cur_t is not None:
            out.append((cur_t, float(m.group(1))))
            cur_t = None
    return out


def stalls(scores, scene_th, min_ms):
    """连续低分区间 → [(start_s, end_s)]。"""
    if not scores:
        return []
    res = []
    start = None
    for t, s in scores:
        if s < scene_th:
            if start is None:
                start = t
        else:
            if start is not None and (t - start) * 1000.0 >= min_ms:
                res.append((start, t))
            start = None
    if start is not None:
        last = scores[-1][0]
        if (last - start) * 1000.0 >= min_ms:
            res.append((start, last))
    return res


def log_events(path):
    """抓日志里的两族 gap 事件（ts 秒级字符串原样返回）。"""
    ev = []
    for line in open(path, encoding="utf-8", errors="ignore"):
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            d = json.loads(line)
        except Exception:
            continue
        if d.get("event") in ("render.preview_gap", "render.preview_arrive_gap"):
            ev.append((d["ts"], d["event"], d.get("kv", {}).get("gapMs")))
    return ev


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("--min-ms", type=int, default=DEFAULT_MIN_MS)
    ap.add_argument("--scene", type=float, default=DEFAULT_SCENE)
    ap.add_argument("--log")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()

    scores = frame_scores(a.video)
    if not scores:
        print("没读到帧（ffmpeg 解析失败？）", file=sys.stderr)
        return 2
    st = stalls(scores, a.scene, a.min_ms)
    if a.json:
        print(json.dumps({"frames": len(scores), "stalls": st}, ensure_ascii=False))
    else:
        print(f"帧数 {len(scores)}，录屏时长 {scores[-1][0]:.2f}s")
        if not st:
            print("没有 >=%dms 的画面冻结（摇镜是否够快？）" % a.min_ms)
        for s, e in st:
            print(f"  冻结 {s:7.2f}s → {e:7.2f}s  ({(e - s) * 1000:.0f}ms)")
    if a.log:
        for ts, name, gap in log_events(a.log):
            print(f"  log {ts} {name} {gap}ms")
    return 0


if __name__ == "__main__":
    sys.exit(main())
