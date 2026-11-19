#!/usr/bin/env python3
"""Generate Spektrafilm's pinned CAM16-UCS sRGB boundary table."""

import argparse
import subprocess
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("upstream", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    revision = subprocess.check_output(
        ["git", "-C", str(args.upstream), "rev-parse", "HEAD"], text=True
    ).strip()
    dirty = subprocess.check_output(
        ["git", "-C", str(args.upstream), "status", "--porcelain"], text=True
    ).strip()
    if revision != "3bb2c2d2801ff68b92019cf1dbcbb133d60832bc" or dirty:
        parser.error("expected the pinned, unmodified Spektrafilm reference")

    sys.path.insert(0, str(args.upstream.resolve() / "src"))
    import numpy as np
    from spektrafilm.utils.gamut_compression import _get_output_c_max_table

    lightness, hue, cmax = _get_output_c_max_table("cam16ucs", "sRGB")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="ascii") as out:
        out.write("// Generated from Spektrafilm 3bb2c2d2801ff68b92019cf1dbcbb133d60832bc.\n")
        out.write("inline constexpr double kCam16SrgbLightness[64] = {\n")
        out.writelines(f"{value:.17g},\n" for value in lightness)
        out.write("};\ninline constexpr double kCam16SrgbHue[720] = {\n")
        out.writelines(f"{value:.17g},\n" for value in hue)
        out.write("};\ninline constexpr double kCam16SrgbCmax[46080] = {\n")
        out.writelines(f"{value:.17g},\n" for value in np.ravel(cmax))
        out.write("};\n")


if __name__ == "__main__":
    main()
