#!/usr/bin/env python3
"""Replay captured P0 through production RCD/CameraWhite/CCM, not a full JPEG replay.

Requires the one-shot edge dumps, matching DNG and capture log. DNG GainMap
opcodes are decoded with their big-endian field layout; the application LSC
damping and RGB16F upload are reproduced before running production GLSL.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import struct

import moderngl
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def dng_calibration(path):
    parser = module("dng_tags", ROOT / "tools/dng-color-dump.py")
    data = path.read_bytes()
    endian = {b"II": "<", b"MM": ">"}[data[:2]]
    assert struct.unpack_from(endian + "H", data, 2)[0] == 42
    offset = struct.unpack_from(endian + "I", data, 4)[0]
    tags = {}
    for i in range(struct.unpack_from(endian + "H", data, offset)[0]):
        pos = offset + 2 + i * 12
        tag, typ, count, value = struct.unpack_from(endian + "HHII", data, pos)
        if tag in (50728, 51009):
            if tag == 51009:
                tags[tag] = data[value:value + count]
            else:
                tags[tag] = parser.read_values_at(data, endian, typ, count, value, pos + 8)
    opcodes = tags[51009]
    count = struct.unpack_from(">I", opcodes)[0]
    offset = 4
    maps = []
    for _ in range(count):
        opcode, version, flags, size = struct.unpack_from(">IIII", opcodes, offset)
        offset += 16
        body = opcodes[offset:offset + size]
        offset += size
        if opcode != 9:
            continue
        top, left, bottom, right, plane, planes, row_pitch, col_pitch = struct.unpack_from(">8I", body)
        rows, cols = struct.unpack_from(">II", body, 32)
        spacing_y, spacing_x, origin_y, origin_x = struct.unpack_from(">4d", body, 40)
        map_planes = struct.unpack_from(">I", body, 72)[0]
        assert planes == map_planes == 1 and row_pitch == col_pitch == 2
        assert plane == 0 and top in (0, 1) and left in (0, 1)
        assert origin_x == origin_y == 0 and spacing_x > 0 and spacing_y > 0
        gains = np.frombuffer(body, dtype=">f4", offset=76).astype(np.float32)
        assert len(gains) == rows * cols
        maps.append(((top, left), gains.reshape(rows, cols)))
    assert len(maps) == 4 and len({p for p, _ in maps}) == 4
    phases = dict(maps)
    # This diagnostic currently accepts the captured GRBG layout only.
    rgb = np.stack((phases[(0, 1)], .5 * (phases[(0, 0)] + phases[(1, 1)]),
                    phases[(1, 0)]), axis=-1)
    maximum = rgb.max(axis=-1)
    delta = maximum - np.float32(2.5)
    factor = (maximum - np.float32(.25) *
              (delta + np.sqrt(delta * delta + np.float32(.3) ** 2))) / maximum
    lsc = (rgb * factor[..., None]).astype("<f2")
    return np.array(tags[50728], np.float32), lsc


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("directory", type=Path)
    ap.add_argument("--token", required=True)
    ap.add_argument("--dng", required=True)
    ap.add_argument("--log", default="filmkit-engine-diagnostic.log")
    args = ap.parse_args()
    folder = args.directory
    events = []
    for line in (folder / args.log).read_text().splitlines():
        try:
            events.append(json.loads(line))
        except ValueError:
            pass
    stage_event = next(e for e in events if e.get("event") == "edge_dump.stage" and
                       args.token + "_D4-CCM" in e["kv"]["path"])
    preceding = [e for e in events if e.get("ts", "") < stage_event["ts"]]
    meta = next(e["kv"] for e in reversed(preceding) if e.get("event") == "raw.meta")
    render = next(e["kv"] for e in events if e.get("event") == "raw.render" and
                  e["ts"] > stage_event["ts"])
    lsc_log = next(e["kv"] for e in reversed(preceding) if e.get("event") == "raw.lsc.damp")
    assert meta["cfa"] == 1 and render["dem"] == "rcd" and render["rcdSplit"] == 0
    assert render["caApplied"] == 0 and lsc_log["on"] is True
    neutral, lsc = dng_calibration(folder / args.dng)
    gains = np.array(render["nzG"], np.float32)
    assert np.max(np.abs(gains - neutral[1] / neutral)) < .0002
    ccm = np.array([float(x) for x in meta["ccmStd"].split(",")], np.float32).reshape(3, 3)
    bracket = next(e["kv"] for e in reversed(preceding) if e.get("event") == "bracket.actual")
    burst_folder = folder / "diagnostic-burst"
    frame_meta = [json.loads((burst_folder / f"f{i}.meta").read_text()) for i in range(4)]
    assert all(m["w"] == render["w"] and m["h"] == render["h"] for m in frame_meta)
    calibration = [
        "FILMKIT_RAW_REPLAY_1", [render["w"], render["h"], meta["cfa"], meta["white"], 0, render["dn"]],
        [m["stridePx"] for m in frame_meta], [float(x) for x in meta["black"].split(",")],
        neutral, ccm.ravel(), render["nzS"] + render["nzO"],
        [float(x) for x in bracket["ev"].split(",")], [1., 1., 1., 1.],
        [lsc.shape[1], lsc.shape[0]], lsc.ravel(),
    ]
    (burst_folder / "replay.txt").write_text("\n".join(
        row if isinstance(row, str) else " ".join(format(float(v), ".10g") for v in row)
        for row in calibration) + "\n")

    def read_stage(name):
        base = folder / ("edge_dump_" + args.token + "_" + name)
        info = json.loads(base.with_suffix(".json").read_text())
        assert info["complete"] and info["orientation"] == "gl_bottom_up"
        data = np.fromfile(base.with_suffix(".rgba16f"), "<f2")
        return data.reshape(info["h"], info["w"], info["components"]).astype(np.float32)

    p0 = read_stage("mosaic-after-P0")
    actual = read_stage("D4-CCM")
    height, width = p0.shape[:2]
    dh, dw = actual.shape[:2]
    assert (dw, dh) == (height, width)
    offline = module("highlight_diagnostic_gl", ROOT / "tools/engine-shader-offline.py")
    gl = offline.GL(width, height)
    shaders = offline.extract_shaders()
    textures = []

    def texture(array, dtype="f2"):
        if array.ndim == 2:
            array = array[..., None]
        tex = gl.ctx.texture((array.shape[1], array.shape[0]), array.shape[2],
                             array.astype("<" + dtype).tobytes(), dtype=dtype)
        tex.filter = (moderngl.NEAREST,) * 2
        tex.repeat_x = tex.repeat_y = False
        textures.append(tex)
        return tex

    try:
        common = {"uSize": (width, height), "uCfaLut": 97,
                  "uBlack": (64.,) * 4, "uWhite": 1023., "uGain": tuple(gains),
                  "uMosaic": 1., "uGOrig": 0., "uCdFix": 1.}
        mos = texture(p0)
        raw_dummy = texture(np.zeros((1, 1), np.uint16), "u2")
        direction = gl.run(shaders["kFsRcdDir"], common, {"uRaw": raw_dummy, "uMos": mos})
        direct = texture(direction)
        green = gl.run(shaders["kFsRcdG"], common, {"uRaw": raw_dummy, "uMos": mos, "uDir": direct})
        green_tex = texture(green)
        shading = texture(lsc)
        dummy = texture(np.zeros((1, 1), np.float32))
        source = (ROOT / "core/engine/src/render/FrameRenderer.cpp").read_text()
        vertex = re.search(r'const char\* kVertRot\s*=\s*R"\((.*?)\)";', source, re.S).group(1)
        gl.vs_src = offline.to_core(vertex)
        rgb = gl.run(shaders["kFsRcdChroma"], {
            **common, "uRot": 1, "uScale": (1., 1.), "uST": tuple(np.eye(4).ravel()),
            "uLscSize": (lsc.shape[1], lsc.shape[0]), "uMaskOn": 0,
        }, {"uRaw": raw_dummy, "uMos": mos, "uDir": direct, "uG": green_tex, "uLsc": shading,
            "uHlMask": dummy}, size=(dw, dh))
        assert gl.ctx.error == "GL_NO_ERROR" and rgb[..., :3].max() > .1
        # Resolve and CCM use gl_FragCoord; their vertex shader must be plain.
        gl.vs_src = offline.to_core(re.search(
            r'const char\* kVertPlain\s*=\s*R"\((.*?)\)";', source, re.S).group(1))
        header = (ROOT / "core/engine/src/render/HighlightResolveShader.h").read_text()
        resolve = header.split('R"(', 1)[1].split(')";', 1)[0]
        resolved = gl.run(resolve, {
            "uDstSize": (dw, dh), "uRawSize": (width, height), "uRot": 1,
            "uLscSize": (lsc.shape[1], lsc.shape[0]),
            "uCameraWhite": tuple(neutral / neutral.max()), "uGain": tuple(gains),
            "uReferenceScale": 1., "uShortScale": 1., "uHasShort": 0,
        }, {"uTex": texture(rgb), "uBentoMask": dummy, "uLsc": shading}, size=(dw, dh))
        results = {}
        arrays = {}
        for name, values in (("before", rgb), ("after", resolved)):
            out = gl.run(shaders["kFsCcm"], {"uSize": (dw, dh), "uCcm": tuple(ccm.T.ravel())},
                         {"uTex": texture(values)}, size=(dw, dh))
            arrays[name] = out
            assert gl.ctx.error == "GL_NO_ERROR" and out[..., :3].max() > .1
            y = out[..., :3] @ np.array([.2627, .678, .0593])
            magenta = np.minimum(out[..., 0] - out[..., 1], out[..., 2] - out[..., 1])
            results[name] = {"highmag_pixels": int(((y > .8) & (magenta > .1)).sum()),
                             "luma_quantiles": np.quantile(y, [.5, .9, .99]).tolist()}
            out.astype("<f2").tofile(folder / ("diagnostic-" + name + "-d4.rgba16f"))
            # Fixed common display transform for inspection, not the captured film preset.
            linear = np.maximum(out[::-1, ..., :3], 0)
            display = linear / (1 + linear)
            srgb = np.where(display <= .0031308, 12.92 * display,
                            1.055 * display ** (1 / 2.4) - .055)
            image = Image.fromarray(np.rint(srgb * 255).clip(0, 255).astype(np.uint8))
            image.thumbnail((1200, 1600))
            image.save(folder / ("diagnostic-" + name + "-d4.jpg"))
        difference = np.abs(arrays["before"][..., :3] - actual[..., :3])
        results["captured_d4_baseline_abs_error"] = {
            "quantiles": np.quantile(difference, [.5, .99, 1.]).tolist(),
            "mean": float(difference.mean()),
        }
        results["token"] = args.token
        results["lsc_grid"] = list(lsc.shape)
        results["limitations"] = [
            "Starts from captured P0, so does not test RAW fusion or sat-fix removal.",
            "Desktop GL rather than Mali; CCM and WB read from rounded capture log.",
            "No PC3D, film, LUT, final JPEG or HDR display replay; previews use fixed Reinhard/sRGB.",
        ]
        (folder / "diagnostic-color-report.json").write_text(json.dumps(results, indent=2) + "\n")
        print(json.dumps(results, indent=2))
    finally:
        for tex in textures:
            tex.release()
        gl.close()


if __name__ == "__main__":
    main()
