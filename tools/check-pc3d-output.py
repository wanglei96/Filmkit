#!/usr/bin/env python3
"""Inspect lossless full-pipeline PC3D mode outputs; statistics are not quality scores."""

import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--width", type=int, default=3072)
    parser.add_argument("--height", type=int, default=4096)
    parser.add_argument("--edge-gate", default="OFF")
    parser.add_argument("--regions", type=Path, help="JSON object mapping crop names to [x, y]")
    args = parser.parse_args()
    arrays = [np.fromfile(args.directory / f"{mode}.rgba", dtype=np.uint8)
              .reshape(args.height, args.width, 4)[..., :3] for mode in ("off", "on")]
    images = [Image.fromarray(a) for a in arrays]
    delta = np.abs(arrays[1].astype(np.int16) - arrays[0].astype(np.int16))
    report = {"warning": "No ground truth: residual variation includes real texture and noise.",
              "global": {"mae_codes": float(delta.mean()),
                         "p99_codes": float(np.percentile(delta, 99)),
                         "changed_pixel_percent": float(np.any(delta, axis=2).mean() * 100)},
              "regions": {}}
    regions = {"sky": (1250, 900), "cloud": (350, 750),
               "sun-edge": (1100, 1500), "bar-edge": (870, 2000),
               "shadow": (150, 3290), "roof": (1700, 3400)}
    if args.regions:
        regions = json.loads(args.regions.read_text())
    if not regions:
        parser.error("at least one region is required")
    for name, (x, y) in regions.items():
        if not (isinstance(x, int) and isinstance(y, int) and
                0 <= x <= args.width - 512 and 0 <= y <= args.height - 512):
            parser.error(f"region {name!r} must fit a 512x512 crop")
        box = (x, y, x + 512, y + 512)
        sheet = Image.new("RGB", (1024, 540), "white")
        draw = ImageDraw.Draw(sheet)
        entries = []
        labels = (f"OFF, edge gate {args.edge_gate}", f"ON, edge gate {args.edge_gate}")
        for column, (mode, image, data) in enumerate(zip(labels, images, arrays)):
            sheet.paste(image.crop(box), (column * 512, 28))
            draw.text((column * 512 + 8, 8), mode, fill="black")
            a = data[y:y+512, x:x+512].astype(np.float32)
            luma = a @ np.array([.2126, .7152, .0722], dtype=np.float32)
            chroma = a[..., (0, 2)] - a[..., 1:2]
            residual = chroma[1:-1, 1:-1] - .25 * (chroma[:-2, 1:-1] +
                       chroma[2:, 1:-1] + chroma[1:-1, :-2] + chroma[1:-1, 2:])
            entries.append({"rgb_mean": a.mean(axis=(0, 1)).tolist(),
                            "luma_gradient_rms": float(np.sqrt(np.mean(np.diff(luma, axis=1)**2))),
                            "chroma_highpass_rms": float(np.sqrt(np.mean(residual**2)))})
        sheet.save(args.directory / f"crop-{name}.png")
        report["regions"][name] = {"box": box, "off": entries[0], "on": entries[1]}
    preview_height = round(args.height * 614 / args.width)
    overview = Image.new("RGB", (1228, preview_height + 29), "white")
    draw = ImageDraw.Draw(overview)
    for column, (mode, image) in enumerate(zip(labels, images)):
        image = image.resize((614, preview_height), Image.Resampling.LANCZOS)
        overview.paste(image, (column * 614, 29))
        draw.text((column * 614 + 8, 8), mode, fill="black")
    overview.save(args.directory / "comparison.png")
    (args.directory / "quality-observations.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
