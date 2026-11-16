#!/usr/bin/env python3
"""Keep burst/transition stalls visible beside fully contained GPU windows."""
import importlib.util
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from datetime import datetime, timedelta, timezone

spec = importlib.util.spec_from_file_location(
    "capture_analysis", Path(__file__).with_name("analyze-capture-performance.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
base = datetime(2026, 11, 16, tzinfo=timezone(timedelta(hours=8)))


def event(seconds, name, **kv):
    return json.dumps({"ts": (base + timedelta(seconds=seconds)).isoformat(),
                       "event": name, "kv": kv})


with TemporaryDirectory() as directory:
    path = Path(directory) / "engine.log"
    path.write_text("\n".join([
        "truncated historical event",
        event(0, "capture.begin"),
        event(.75, "render.preview_gap", gapMs=750),
        event(.8, "mfnr.stage", s="upload"),
        event(.9, "render.timing", windowMs=1100, frames=10, frameMaxMs=750),
        event(2, "render.timing", windowMs=1000, frames=30, frameMaxMs=50),
        event(3, "raw.render", totalMs=2200),
        event(4, "photo.saved"),
        event(5, "render.timing", windowMs=500, frames=1, frameMaxMs=999),
        event(6, "capture.begin"),
    ]))
    result = module.analyze(path, None)
    shot = result["captures"][0]
    assert result["invalid_json_lines"] == 1
    assert shot["shutter_to_saved_s"] == 4
    assert shot["full_preview_windows"] == 1
    assert shot["maximum_window_frame_ms"] == 50
    assert shot["workflow_overlapping_max_frame_ms"] == 750
    assert shot["workflow_gap_events"]["render.preview_gap"][0]["gapMs"] == 750
    assert not result["captures"][1]["complete"]
print("PASS contained windows, shutter-wide transition gap, post-save exclusion, incomplete capture")
