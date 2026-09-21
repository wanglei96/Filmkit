#!/usr/bin/env python3
"""DNG 色彩矩阵导出（M3 P2 DNG 标准链）。

为什么不用 rawpy：rawpy 只暴露 libraw 归一化后的单个矩阵（本机实测 `rgb_xyz_matrix`
全 0），而 P2 需要 **成对的** ColorMatrix1/2、CameraCalibration1/2、ForwardMatrix1/2
与 CalibrationIlluminant1/2 做双光源 CCT 插值 —— 这些只在 DNG 标签里。

用法：python3 tools/dng-color-dump.py <file.dng> [...]
"""

import struct
import sys

TAGS = {
    50708: "UniqueCameraModel", 50710: "CFAPlaneColor", 50711: "CFALayout",
    50713: "BlackLevelRepeatDim", 50714: "BlackLevel", 50717: "WhiteLevel",
    50721: "ColorMatrix1", 50722: "ColorMatrix2",
    50723: "CameraCalibration1", 50724: "CameraCalibration2",
    50728: "AsShotNeutral", 50730: "BaselineExposure",
    50778: "CalibrationIlluminant1", 50779: "CalibrationIlluminant2",
    50931: "ForwardMatrix1", 50932: "ForwardMatrix2",
}
ILLUM = {1: "Daylight", 2: "Fluorescent", 3: "Tungsten", 4: "Flash",
         9: "FineWeather", 11: "Shade", 17: "StandardLightA", 18: "StandardLightB",
         21: "D65", 22: "D50", 23: "D55", 24: "D75"}
TYPE_SIZE = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8}


def read_values(buf, endian, typ, count, offset):
    size = TYPE_SIZE[typ] * count
    data = buf[offset:offset + size]
    if typ == 2:
        return data.split(b"\0")[0].decode("ascii", "replace")
    fmt = {1: "B", 3: "H", 4: "I", 6: "b", 8: "h", 9: "i", 11: "f", 12: "d"}.get(typ)
    if typ in (5, 10):
        vals = []
        for i in range(count):
            n, d = struct.unpack(endian + ("II" if typ == 5 else "ii"), data[i * 8:i * 8 + 8])
            vals.append(n / d if d else 0.0)
        return vals
    if fmt is None:
        return f"<type {typ}>"
    vals = list(struct.unpack(endian + fmt * count, data))
    return vals


def read_values_at(buf, endian, typ, count, value_or_offset, base=0):
    size = TYPE_SIZE[typ] * count
    off = value_or_offset if size > 4 else base
    return read_values(buf, endian, typ, count, off)


def dump(path):
    with open(path, "rb") as f:
        buf = f.read()
    order = buf[:2]
    endian = "<" if order == b"II" else ">"
    if order not in (b"II", b"MM"):
        print("不是 TIFF/DNG"); return
    magic, ifd0 = struct.unpack(endian + "HI", buf[2:8])
    print(f"== {path.split('/')[-1]}  ({'little' if endian == '<' else 'big'} endian, TIFF magic {magic})")
    n = struct.unpack(endian + "H", buf[ifd0:ifd0 + 2])[0]
    found = {}
    for i in range(n):
        e = ifd0 + 2 + i * 12
        tag, typ, count = struct.unpack(endian + "HHI", buf[e:e + 8])
        valoff = struct.unpack(endian + "I", buf[e + 8:e + 12])[0]
        if tag in TAGS:
            vals = read_values_at(buf, endian, typ, count, valoff, e + 8)
            found[TAGS[tag]] = vals
    for name in ("UniqueCameraModel", "ColorMatrix1", "ColorMatrix2",
                 "ForwardMatrix1", "ForwardMatrix2", "CameraCalibration1",
                 "CameraCalibration2", "CalibrationIlluminant1",
                 "CalibrationIlluminant2", "AsShotNeutral", "BaselineExposure",
                 "BlackLevel", "WhiteLevel", "CFAPlaneColor", "CFALayout"):
        if name not in found:
            print(f"  {name:22} —（缺失）")
            continue
        v = found[name]
        if name.startswith("CalibrationIlluminant"):
            print(f"  {name:22} {v[0]} = {ILLUM.get(v[0], '?')}")
        elif name in ("UniqueCameraModel",):
            print(f"  {name:22} {v}")
        elif isinstance(v, list) and len(v) == 9:
            for r in range(3):
                print(f"  {name:22} " + " ".join(f"{v[r*3+c]:9.5f}" for c in range(3)))
        else:
            print(f"  {name:22} {v}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(2)
    for p in sys.argv[1:]:
        dump(p)
