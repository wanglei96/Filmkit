#!/usr/bin/env python3
"""Summarize production Spektrafilm timings without mixing render paths."""

from __future__ import annotations

import argparse
import json
import statistics
from collections import defaultdict
from datetime import datetime
from pathlib import Path

PATHS = {
    "negative_print_scan",
    "positive_direct_scan",
}

PHASES = (
    "readMs",
    "firstReadMs",
    "glReadMs",
    "readConvertMs",
    "hanatosMs",
    "filmExposureMs",
    "dirMs",
    "grainMs",
    "grainParticlesMs",
    "grainDyeCloudMs",
    "grainFinishMs",
    "paperExposureMs",
    "paperDevelopmentMs",
    "scanSpectralMs",
    "scanGlareMs",
    "scanOutputMs",
    "scanCam16Ms",
    "scanFilterMs",
    "scanSrgbEncodeMs",
    "renderMs",
    "rgbaMs",
    "hdrProbeMs",
    "hdrGainmapMs",
    "hdrMs",
    "branchMs",
    "frontSubmitMs",
    "uploadMs",
    "rawTotalMs",
    "saveAfterRenderMs",
)


def timing_path(record: dict) -> str | None:
    if record.get("event") != "spektrafilm.render":
        return None
    kv = record.get("kv")
    if not isinstance(kv, dict):
        return None
    path = kv.get("path")
    if path in PATHS:
        return path
    if kv.get("directScan") is True:
        return "positive_direct_scan"
    if kv.get("directScan") is False:
        return "negative_print_scan"
    return None


def percentile(values: list[float], fraction: float) -> float:
    if len(values) == 1:
        return values[0]
    index = round((len(values) - 1) * fraction)
    return values[index]


def collect_samples(records: list[dict]) -> tuple[dict[str, list[dict]], int, int]:
    grouped: dict[str, list[dict]] = defaultdict(list)
    pending: dict | None = None
    correlation_errors = 0
    unclassified = 0

    def finish() -> None:
        nonlocal pending, correlation_errors
        if pending:
            if pending.get("rawTotalMs") is not None:
                grouped[pending["path"]].append(pending)
            else:
                correlation_errors += 1
        pending = None

    for record in records:
        event = record.get("event")
        kv = record.get("kv")
        path = timing_path(record)
        if path:
            finish()
            pending = {"path": path, "timestamp": record.get("ts"), **kv}
            continue
        if event == "spektrafilm.render":
            unclassified += 1
            finish()
            continue
        if not pending or not isinstance(kv, dict):
            continue
        if event == "raw.render" and kv.get("engine") == "spektrafilm":
            for field in ("frontSubmitMs", "uploadMs", "totalMs"):
                if field in kv:
                    pending["rawTotalMs" if field == "totalMs" else field] = float(kv[field])
            continue
        if event == "photo.saved" and pending.get("rawTotalMs") is not None:
            pending["name"] = kv.get("name")
            pending["iso"] = kv.get("iso")
            pending["sec"] = kv.get("sec")
            start = datetime.fromisoformat(pending["timestamp"])
            end = datetime.fromisoformat(record["ts"])
            pending["saveAfterRenderMs"] = (end - start).total_seconds() * 1000
            finish()
    finish()
    return grouped, correlation_errors, unclassified


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("logs", nargs="+", type=Path)
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()

    grouped: dict[str, list[dict]] = defaultdict(list)
    records: list[dict] = []
    malformed = 0
    for log in args.logs:
        with log.open(encoding="utf-8") as source:
            for line in source:
                try:
                    record = json.loads(line)
                except json.JSONDecodeError:
                    malformed += 1
                else:
                    records.append(record)

    grouped, correlation_errors, unclassified = collect_samples(records)

    report = {
        "malformed": malformed,
        "correlationErrors": correlation_errors,
        "unclassifiedRenderEvents": unclassified,
        "paths": {},
    }
    for path in sorted(PATHS):
        samples = grouped[path]
        summary = {
            "samples": len(samples),
            "grainSamples": sum(sample.get("grain") is True for sample in samples),
            "mfnrSamples": sum(sample.get("mfnr") is True for sample in samples),
            "exposureEv": sorted({float(sample["exposureEv"]) for sample in samples
                                  if "exposureEv" in sample}),
            "phases": {},
            "samples": [
                {
                    "name": sample.get("name"),
                    "timestamp": sample.get("timestamp"),
                    "grain": sample.get("grain"),
                    "iso": sample.get("iso"),
                    "sec": sample.get("sec"),
                    **{field: float(sample[field]) for field in PHASES if field in sample},
                }
                for sample in samples
            ],
        }
        for field in PHASES:
            values = sorted(float(sample[field]) for sample in samples if field in sample)
            if not values:
                continue
            summary["phases"][field] = {
                "median": statistics.median(values),
                "min": values[0],
                "max": values[-1],
                "p90": percentile(values, 0.9),
            }
        report["paths"][path] = summary

    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.json:
        args.json.write_text(text + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
