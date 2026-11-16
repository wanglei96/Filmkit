#!/usr/bin/env python3
"""Summarize saved-photo latency, processing windows, and capture-wide gaps."""

import argparse
from datetime import datetime, timedelta
import json
from pathlib import Path
from statistics import median


def timestamp(event):
    return datetime.fromisoformat(event["ts"])


def analyze(path, since):
    events, invalid = [], 0
    for line in path.read_text().splitlines():
        try:
            event = json.loads(line)
            timestamp(event)
            assert isinstance(event["kv"], dict)
            event["event"]
        except (ValueError, KeyError, TypeError, AssertionError):
            invalid += 1
            continue
        if since is None or timestamp(event) >= since:
            events.append(event)
    events.sort(key=timestamp)
    captures = [i for i, e in enumerate(events) if e["event"] == "capture.begin"]
    results = []
    for number, index in enumerate(captures):
        stop = captures[number + 1] if number + 1 < len(captures) else len(events)
        shot = events[index:stop]
        begin = timestamp(shot[0])
        by_name = {}
        for event in shot:
            by_name.setdefault(event["event"], []).append(event)
        if any(len(by_name.get(name, [])) != 1 for name in ("raw.render", "photo.saved")):
            results.append({"capture": shot[0]["ts"], "complete": False})
            continue
        render = by_name["raw.render"][0]
        saved = by_name["photo.saved"][0]
        uploads = [e for e in by_name.get("mfnr.stage", []) if e["kv"].get("s") == "upload"]
        processing_start = timestamp(uploads[0]) if uploads else begin
        processing_end = timestamp(render)
        workflow_end = timestamp(saved)
        workflow_windows = [e["kv"] for e in by_name.get("render.timing", [])
                            if timestamp(e) - timedelta(milliseconds=e["kv"]["windowMs"]) < workflow_end]
        gaps = {name: [{"ts": e["ts"], "gapMs": e["kv"]["gapMs"]}
                       for e in by_name.get(name, []) if timestamp(e) <= workflow_end]
                for name in ("render.preview_gap", "render.preview_arrive_gap")}
        windows = [e["kv"] for e in by_name.get("render.timing", [])
                   if timestamp(e) <= processing_end and timestamp(e) - timedelta(
                       milliseconds=e["kv"]["windowMs"]) >= processing_start]
        slow = [e["kv"] for e in by_name.get("preview.slow_draw", [])
                if processing_start <= timestamp(e) <= processing_end]
        summary = {}
        for key in ("totalMs", "targetsMs", "oesMs", "teqMs", "filmMs", "outputMs",
                    "histRenderMs", "histReadWaitMs", "histCpuMs", "filmicMs"):
            values = [e[key] for e in slow if key in e]
            if values:
                summary[key] = {"median": median(values), "max": max(values)}
        kv = render["kv"]
        results.append({
            "capture": shot[0]["ts"], "complete": True,
            "shutter_to_saved_s": (timestamp(saved) - begin).total_seconds(),
            "engine_ms": kv["totalMs"], "pc3d_ms": kv.get("pc3dMs"),
            "upload_ms": kv.get("uploadMs"), "denoise_ms": kv.get("dnMs"),
            "full_preview_windows": len(windows),
            "minimum_window_fps": min((e["frames"] * 1000 / e["windowMs"]
                                       for e in windows), default=None),
            "maximum_window_frame_ms": max((e["frameMaxMs"] for e in windows), default=None),
            # Overlapping windows may include pre-shutter/post-save samples;
            # retain them separately instead of hiding burst/transition stalls.
            "workflow_overlapping_max_frame_ms": max(
                (e["frameMaxMs"] for e in workflow_windows), default=None),
            "workflow_gap_events": gaps,
            "thresholded_slow_draw_count": len(slow),
            "thresholded_slow_draw_ms": summary,
            "hr_diagnostic_count": sum(e["kv"].get("stage") == "HR-filmsrc"
                                       for e in by_name.get("diag.stats", [])),
            "admission": [e["kv"] for e in by_name.get("highlight.admission", [])],
            "saved": saved["kv"],
        })
    return {"log": str(path), "invalid_json_lines": invalid, "captures": results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--since", type=datetime.fromisoformat)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = json.dumps(analyze(args.log, args.since), indent=2, ensure_ascii=True)
    if args.output:
        args.output.write_text(result + "\n")
    print(result)


if __name__ == "__main__":
    main()
