#!/usr/bin/env python3
"""Generate pinned Spektrafilm scanner-output RGB fixtures."""

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
    revision = subprocess.check_output(
        ["git", "-C", str(args.upstream), "rev-parse", "HEAD"], text=True
    ).strip()
    if revision != "3bb2c2d2801ff68b92019cf1dbcbb133d60832bc":
        parser.error("expected the pinned Spektrafilm reference")

    sys.path.insert(0, str(args.upstream.resolve() / "src"))
    import colour
    from spektrafilm.model.diffusion import apply_gaussian_blur, apply_unsharp_mask
    from spektrafilm.utils.gamut_compression import OutputGamutCompressSpec, compress_rgb

    width, height = 7, 5
    white_xy = np.array([0.34567, 0.35850])
    white_xyz = np.array([white_xy[0] / white_xy[1], 1.0,
                          (1.0 - white_xy.sum()) / white_xy[1]])
    y, x = np.mgrid[0:height, 0:width]
    xyz = np.stack(
        [0.12 + 0.18 * x + 0.015 * y,
         0.08 + 0.14 * y + 0.012 * x,
         0.16 + 0.11 * (width - x) + 0.01 * y],
        axis=-1,
    ).astype(np.float64)
    xyz[0, 0] = [0.001, 0.001, 0.001]
    xyz[-1, -1] *= 2.1
    spec = OutputGamutCompressSpec(algorithm="cam16ucs")
    cases = ((0.0, 0.7, 0.7), (0.45, 0.7, 0.7))
    with args.output.open("xb") as out:
        np.array([len(cases), width, height], dtype="<u4").tofile(out)
        white_xyz.astype("<f8").tofile(out)
        for lens_blur, unsharp_sigma, unsharp_amount in cases:
            np.array([lens_blur, unsharp_sigma, unsharp_amount], dtype="<f8").tofile(out)
            xyz.astype("<f8").tofile(out)
            rgb = colour.XYZ_to_RGB(
                xyz, colourspace="sRGB", illuminant=white_xy,
                apply_cctf_encoding=False,
            )
            rgb = compress_rgb(rgb, spec, output_color_space="sRGB")
            rgb = apply_gaussian_blur(rgb, lens_blur)
            rgb = apply_unsharp_mask(rgb, sigma=unsharp_sigma, amount=unsharp_amount)
            encoded = colour.RGB_to_RGB(
                rgb, "sRGB", "sRGB", apply_cctf_decoding=False,
                apply_cctf_encoding=True,
            )
            encoded.astype("<f8").tofile(out)


if __name__ == "__main__":
    main()
