#!/usr/bin/env python3
"""Emit pinned Spektrafilm CAM16-UCS output-compression oracle samples."""

import argparse
import subprocess
import sys
from pathlib import Path

import numpy as np


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("upstream", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    def git(*cmd):
        return subprocess.check_output(
            ["git", "-C", str(args.upstream), *cmd], text=True
        ).strip()

    if git("rev-parse", "HEAD") != "3bb2c2d2801ff68b92019cf1dbcbb133d60832bc" or git("status", "--porcelain"):
        parser.error("expected the pinned, unmodified Spektrafilm reference")

    sys.path.insert(0, str(args.upstream.resolve() / "src"))
    from spektrafilm.utils.gamut_compression import (
        OutputGamutCompressSpec,
        compress_rgb,
    )

    # Includes black/near-black, white, super-white, primary/secondary
    # boundaries, negative and above-one channels, and the hue seam.
    rgb = np.array(
        [
            [0.0, 0.0, 0.0],
            [1e-8, 2e-8, 1e-8],
            [0.18, 0.18, 0.18],
            [1.0, 1.0, 1.0],
            [1.35, 1.22, 1.1],
            [1.0, 0.0, 0.0],
            [0.0, 1.0, 0.0],
            [0.0, 0.0, 1.0],
            [1.0, 1.0, 0.0],
            [0.0, 1.0, 1.0],
            [1.0, 0.0, 1.0],
            [-0.08, 0.16, 0.84],
            [1.08, 0.03, 0.72],
            [0.02, 0.91, 1.14],
            [0.5, 0.500001, 0.499999],
            [0.92, 0.11, 0.19],
            [0.19, 0.91, 0.11],
            [0.11, 0.19, 0.92],
        ],
        dtype=np.float64,
    )
    levels = np.array([0.0, 1e-10, 0.0031308, 0.025, 0.1, 0.18, 0.5, 1.0, 2.0, 4.0])
    grid = np.stack(np.meshgrid(levels, levels, levels, indexing="ij"), axis=-1)
    rgb = np.concatenate((rgb, grid.reshape(-1, 3)))
    spec = OutputGamutCompressSpec(algorithm="cam16ucs")
    expected = compress_rgb(rgb, spec, output_color_space="sRGB")
    with args.output.open("xb") as out:
        np.array([len(rgb)], dtype="<u4").tofile(out)
        np.column_stack((rgb, expected)).astype("<f8").tofile(out)


if __name__ == "__main__":
    main()
