#!/usr/bin/env python3
"""Prepare the fixed FilmKit single-RAW quality suite without modifying originals."""
import argparse
import hashlib
import json
from pathlib import Path
import runpy

import numpy as np
import rawpy

ROOT = Path(__file__).resolve().parents[1]
# Use the corresponding raw.meta CCM and capture levels from the 2026-11-19
# device log. It was printed to four decimals; this is not an old JPEG replay.
SCENES = {
    "110832": ("人像", "4a0222164983e3af0c89757987d650b2d235b5f05c371643ceb818c0d0eed08e",
               ".9659 .0734 -.0393 -.0207 1.1841 -.1634 .0194 -.4252 1.4058"),
    "110846": ("室内绿植", "91ae2a1300e18232a4e5dfa908470b1ca3de90591928b20deace165400cbe894",
               ".9646 .0742 -.0389 -.0214 1.1829 -.1615 .0190 -.4288 1.4099"),
    "110904": ("室外＋建筑", "aa519b3ccbf06a6a3b8c9f713f863851a559af22095394ab4dcadce3dcd6d0b4",
               ".9756 .0670 -.0426 -.0152 1.1934 -.1782 .0228 -.3958 1.3730"),
    "110936": ("鲜花", "aab311424547815be5298f79f979f7ddcb0f656bd670e524c980049d1e9bc2a2",
               ".9695 .0710 -.0405 -.0186 1.1876 -.1689 .0207 -.4141 1.3935"),
}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("output", type=Path)
    ap.add_argument("--source", type=Path, default=ROOT / "test_temp")
    ap.add_argument("--scene", choices=SCENES, action="append")
    args = ap.parse_args()
    calibration = runpy.run_path(str(ROOT / "tools/analyze-highlight-diagnostic.py"))["dng_calibration"]
    for key in args.scene or SCENES:
        description, expected, ccm = SCENES[key]
        source = args.source / f"FILMKIT_20261119-{key}.dng"
        with source.open("rb") as stream:
            assert hashlib.file_digest(stream, "sha256").hexdigest() == expected, source
        with rawpy.imread(str(source)) as raw:
            assert raw.color_desc == b"RGBG" and raw.raw_pattern.tolist() == [[1, 0], [2, 3]]
            assert raw.white_level == 1023 and raw.sizes.flip in (0, 6)
            rotation = {0: 3, 6: 0}[raw.sizes.flip]
            mosaic = raw.raw_image.astype("<u2", copy=True)
        assert mosaic.shape == (3072, 4080)
        height, width = mosaic.shape
        neutral, lsc = calibration(source)
        rows, cols, channels = lsc.shape
        assert channels == 3
        fixture = args.output / key
        fixture.mkdir(parents=True, exist_ok=True)
        mosaic.tofile(fixture / "f0.raw")
        # No synthetic frame, DFT, fusion or exposure bracket. DN strength 0
        # matches the archived raw.meta for these captures.
        replay = [
            "FILMKIT_RAW_REPLAY_1", f"{width} {height} 1 1023 {rotation} 0",
            f"{width} {width} {width} {width}", "64 64 64 64",
            " ".join(format(float(x), ".10g") for x in neutral), ccm,
            "0 0 0 0 0 0", "0 0 0 0", "1 1 1 1", f"{cols} {rows}",
            " ".join(format(float(x), ".10g") for x in lsc.ravel()),
        ]
        replay_text = "\n".join(replay) + "\n"
        (fixture / "replay.txt").write_text(replay_text)
        (fixture / "manifest.json").write_text(json.dumps({
            "scene": description, "dng": source.name, "dngSha256": expected,
            "mosaicSha256": hashlib.sha256(mosaic).hexdigest(),
            "replaySha256": hashlib.sha256(replay_text.encode()).hexdigest(),
            "scope": "full sensor mosaic, single RAW only, fixed current recipe/controls",
        }, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
