#!/usr/bin/env python3
"""Compare every output pixel and summarize the production still A/B replay."""
import argparse
import json
from pathlib import Path
import re

import numpy as np
from PIL import Image

# Normalized ROIs on the correctly oriented CPU reference. Each sample also
# undergoes the complete per-channel comparison; these are not masks/exclusions.
ROIS = {
    "110832": {"skin": (.37, .38, .65, .61)},
    "110846": {"foliage": (.11, .32, .75, .59)},
    "110904": {"building": (.46, .51, .86, .93), "sky": (.19, .025, .81, .33)},
    "110936": {"petals": (.10, .12, .665, .37)},
}


def rgb(path, bits, width, height):
    if bits == 8:
        a = np.memmap(path, np.uint8, "r").reshape(height, width, 4)
        assert (a[..., 3] == 255).all()
        return a[..., :3]
    a = np.memmap(path, "<u4", "r").reshape(height, width)
    assert (a >> 30 == 3).all()
    return np.stack([(a >> (10 * ch)) & 1023 for ch in range(3)], axis=-1).astype(np.uint16)


def difference(a, b):
    assert a.shape == b.shape
    hist = np.zeros(1024, dtype=np.int64)
    for start in range(0, a.shape[0], 128):
        delta = np.abs(a[start:start+128].astype(np.int16) - b[start:start+128].astype(np.int16))
        counts = np.bincount(delta.ravel())
        hist[:len(counts)] += counts
    return {
        "max": int(np.flatnonzero(hist)[-1]), "mae": float(hist @ np.arange(len(hist)) / hist.sum()),
        "changed": int(hist[1:].sum()), "count": int(hist.sum()),
    }


def log_entry(log, event):
    matches = [e["kv"] for l in log.splitlines() if l.startswith("{")
               for e in [json.loads(l)] if e["event"] == event]
    assert len(matches) == 1, (event, len(matches))
    return matches[0]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("path", type=Path)
    ap.add_argument("--require-count", type=int)
    args = ap.parse_args()
    result = {}
    for a in sorted(args.path.glob("*-0-A")):
        key, _, _ = a.name.rsplit("-", 2)
        b = a.with_name(key + "-1-B")
        if not (b / "render.jpg").exists():
            continue
        bits = int(key.rsplit("-", 1)[-1])
        logs = [(p / "render.log").read_text() for p in (a, b)]
        film = [log_entry(l, "spektrafilm.render") for l in logs]
        raw = [log_entry(l, "raw.render") for l in logs]
        vk = log_entry(logs[1], "spektrafilm.vulkan_hanatos")
        w, h = film[0]["w"], film[0]["h"]
        assert all((f["w"], f["h"], f["bits"]) == (w, h, bits) and not f["mfnr"] and
                   not f["grain"] and f["scanCam16LutHits"] > 0 for f in film)
        assert vk["pixels"] == vk["totalPixels"] == w * h
        assert 'VULKAN probe=pass captureOption=on' in logs[1]
        assert all(r["ok"] and not r["mfnr"] for r in raw)
        rgba = [rgb(p / "render.rgba", bits, w, h) for p in (a, b)]
        jpg = [np.asarray(Image.open(p / "render.jpg").convert("RGB")) for p in (a, b)]
        gains = [np.memmap(p / "render.gainmap", np.uint8, "r") for p in (a, b)]
        roi = {}
        for name, box in ROIS[key.split("-", 1)[0]].items():
            x0, y0, x1, y1 = [int(v * d) for v, d in zip(box, (w, h, w, h))]
            crop = np.s_[y0:y1, x0:x1]
            roi[name] = {"rgb": difference(*(a[crop] for a in rgba)),
                         "decodedJpeg": difference(*(a[crop] for a in jpg))}
        result[key] = {
            "size": [w, h],
            "rgb": difference(*rgba),
            "gainmap": difference(*gains),
            "decodedJpeg": difference(*jpg),
            "roi": roi,
            "rawMs": [r["totalMs"] for r in raw],
            "firstReadMs": [f["firstReadMs"] for f in film],
            "readPlusHanatosMs": [f["readMs"] + f["hanatosMs"] for f in film],
            "branchMs": [f["branchMs"] for f in film],
            "stagesMs": {k: [f[k] for f in film] for k in (
                "filmExposureMs", "dirMs", "paperExposureMs", "paperDevelopmentMs",
                "scanSpectralMs", "scanGlareMs", "scanOutputMs", "rgbaMs", "hdrMs")},
            "vulkan": vk,
        }
    print(json.dumps(result, indent=2))
    if args.require_count is not None:
        assert len(result) == args.require_count, len(result)
        assert all(r["rgb"]["max"] <= 1 and r["gainmap"]["max"] <= 1 for r in result.values())


if __name__ == "__main__":
    main()
