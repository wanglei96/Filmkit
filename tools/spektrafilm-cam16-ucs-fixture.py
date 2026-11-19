#!/usr/bin/env python3
"""Generate normalized XYZ <-> CAM16-UCS vectors from pinned colour-science."""

import argparse
from pathlib import Path

import colour
import numpy as np


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    white = np.array([0.3127 / 0.329, 1.0, (1.0 - 0.3127 - 0.329) / 0.329])
    xyz = np.array(
        [
            [0.0, 0.0, 0.0],
            [1e-8, 2e-8, 1e-8],
            [0.2, 0.18, 0.1],
            white,
            [0.4124, 0.2126, 0.0193],
            [0.3576, 0.7152, 0.1192],
            [0.1805, 0.0722, 0.9505],
            [0.3, 0.7, 1.2],
            [1.4, 0.08, 0.02],
        ],
        dtype=np.float64,
    )
    jab = colour.XYZ_to_CAM16UCS(xyz, XYZ_w=white, L_A=64.0, Y_b=20.0)
    roundtrip = colour.CAM16UCS_to_XYZ(jab, XYZ_w=white, L_A=64.0, Y_b=20.0)
    with args.output.open("xb") as out:
        np.array([len(xyz)], dtype="<u4").tofile(out)
        np.column_stack((xyz, jab, roundtrip)).astype("<f8").tofile(out)


if __name__ == "__main__":
    main()
