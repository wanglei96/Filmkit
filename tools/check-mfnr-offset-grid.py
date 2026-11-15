#!/usr/bin/env python3
"""Check production MFNR offset sampling against correct CPU bilinear sampling.

Run: python3 tools/check-mfnr-offset-grid.py [--legacy] [--out result.json]
Requires the existing numpy/moderngl/glfw environment; never edits engine shaders.
A failing production comparison exits 1. --legacy additionally replays the old
wrong-grid functions IN MEMORY only; its undefined OOB values are driver-specific
and are not a portable assertion. This is desktop GL evidence, not device parity.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
TW, TH = 8, 6
CW, CH = 4 * TW, 4 * TH
W, H = 128 * TW, 128 * TH
# GL helper stores RGBA16F: allow one half-float ULP plus shader arithmetic.
OFFSET_TOL = 0.004
MISMATCH_TOL = 0.0003


def offset_functions(src):
    return src[src.index("vec3 off1At"):src.index("float linSub")]


def legacy_shader(src):
    """Recreate precisely the old off1 clamp / off2+3 scale+clamp defects."""
    funcs = offset_functions(src)
    old = []
    for i in range(1, 4):
        start = funcs.index(f"vec3 off{i}At")
        end = funcs.index(f"vec3 off{i + 1}At") if i < 3 else len(funcs)
        part = funcs[start:end].replace("uOffTiles", "uTiles")
        if i == 1:
            part = part.replace("uv * uTiles", "uv * uOffTiles")
        old.append(part)
    return src.replace(funcs, "".join(old), 1)


def probe_source(src, i, uv_expr):
    # Keep the production functions byte-for-byte; only replace main to observe
    # their returned offsets instead of masking errors with constant zero fields.
    return """#version 300 es
precision highp float;
layout(location=0) out vec4 oColor;
uniform highp sampler2D uOff1;
uniform highp sampler2D uOff2;
uniform highp sampler2D uOff3;
uniform vec2 uTiles;
uniform vec2 uOffTiles;
""" + offset_functions(src) + f"""
void main() {{
    vec3 off = off{i}At({uv_expr});
    oColor = vec4(off.xy, 0.0, 1.0);
}}
"""


def cpu_offset(uv, field):
    """Correct edge-clamped bilinear sampling in actual offset texture dimensions."""
    px = uv * np.array([TW, TH]) - 0.5
    lo = np.floor(px).astype(int)
    frac = px - lo
    hi = np.clip(lo + 1, [0, 0], [TW - 1, TH - 1])
    lo = np.clip(lo, [0, 0], [TW - 1, TH - 1])
    fx, fy = frac[..., 0, None], frac[..., 1, None]
    a = field[lo[..., 1], lo[..., 0], :2].astype(float)
    b = field[lo[..., 1], hi[..., 0], :2].astype(float)
    c = field[hi[..., 1], lo[..., 0], :2].astype(float)
    d = field[hi[..., 1], hi[..., 0], :2].astype(float)
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy


def cpu_mismatch(raw, offsets):
    # Same-phase mosaic 4-tap sampling, 8x8 real samples/cell; no affine
    # shortcut, so bounds and nonlinear future test fields remain observable.
    def lin_sub(px, py, delta):
        phx, phy = px % 2, py % 2
        sx = np.clip((px + delta[0] - phx) / 2, 0, (W - 1 - phx) / 2)
        sy = np.clip((py + delta[1] - phy) / 2, 0, (H - 1 - phy) / 2)
        bx, by = phx + int(np.floor(sx)) * 2, phy + int(np.floor(sy)) * 2
        hx, hy = phx + ((W - 1 - phx) & ~1), phy + ((H - 1 - phy) & ~1)
        fx, fy = sx % 1, sy % 1
        x1, y1 = min(bx + 2, hx), min(by + 2, hy)
        return ((float(raw[by, bx]) * (1 - fx) + float(raw[by, x1]) * fx) * (1 - fy)
                + (float(raw[y1, bx]) * (1 - fx) + float(raw[y1, x1]) * fx) * fy) / 16383

    result = np.empty((CH, CW))
    for y in range(CH):
        for x in range(CW):
            residuals = []
            for dy in range(0, 32, 4):
                for dx in range(0, 32, 4):
                    px, py = x * 32 + dx, y * 32 + dy
                    delta = float(raw[py, px]) / 16383 - lin_sub(px, py, offsets[y, x])
                    residuals.append(delta * delta)
            variance = float(np.mean(residuals))
            result[y, x] = variance / (variance + 8e-6)  # uS * (B_ref + B_alt)
    return result


def error_stats(actual, expected, tolerance):
    error = np.max(np.abs(actual - expected), axis=-1)
    finite = bool(np.isfinite(actual).all())
    return {"max_abs_error": float(error.max()), "bad_pixels": int(np.sum(error > tolerance)),
            "pixels": int(error.size), "tolerance": tolerance, "finite": finite,
            "passed": finite and bool(np.all(error <= tolerance))}


def run(engine, shaders, include_legacy):
    gl = engine.GL(W, H)
    textures = []
    try:
        y, x = np.mgrid[:TH, :TW]
        field = np.stack((2 + .5 * x + .25 * y, -1 - .25 * x + .5 * y,
                          np.ones_like(x), np.ones_like(x)), axis=-1).astype("float16")
        off = gl.ctx.texture((TW, TH), 4, field.tobytes(), dtype="f2")
        textures.append(off)
        off.filter = (engine.moderngl.NEAREST,) * 2
        off.repeat_x = off.repeat_y = False
        off_tex = {f"uOff{i}": off for i in range(1, 4)}
        y, x = np.mgrid[:CH, :CW]
        expected = cpu_offset(np.stack(((x + .5) / CW, (y + .5) / CH), axis=-1), field)
        y, x = np.mgrid[:H, :W]
        fuse_expected = cpu_offset(np.stack(((x + .5) / W, (y + .5) / H), axis=-1), field)
        raw = (3000 + 3 * x + 2 * y).astype("uint16")
        raw_tex = gl.ctx.texture((W, H), 1, raw.tobytes(), dtype="u2")
        textures.append(raw_tex)
        raw_tex.filter = (engine.moderngl.NEAREST,) * 2
        full_tex = {**{f"uRaw{i}": raw_tex for i in range(4)}, **off_tex}
        cpu = cpu_mismatch(raw, expected)
        uniforms = {"uSize": (W, H), "uTiles": (CW, CH), "uOffTiles": (TW, TH),
                    "uTileM": 32., "uNoiseA": (0.,) * 3, "uNoiseB": (1e-6,) * 3,
                    "uGainRatio": (1.,) * 4, "uS": 4., "uCfaLut": 0,
                    "uBlack": (0.,) * 4, "uWhite": 16383., "uGain": (1.,) * 3,
                    "uFrameScale": (1.,) * 4}
        summary = {"device_verified": False, "renderer": gl.ctx.info.get("GL_RENDERER"),
                   "version": gl.ctx.info.get("GL_VERSION"), "raw_size": [W, H],
                   "off_size": [TW, TH], "mismatch_size": [CW, CH],
                   "field": "dx=2+.5*x+.25*y; dy=-1-.25*x+.5*y; RGBA16F",
                   "output": "GL helper RGBA16F; production mismatch is RGBA8",
                   "mismatch_uniforms": uniforms,
                   "shader_sha256": {k: hashlib.sha256(v.encode()).hexdigest() for k, v in shaders.items()}}
        variants = {"production": shaders["kFsMfnrMismatch"]}
        if include_legacy:
            variants["legacy"] = legacy_shader(shaders["kFsMfnrMismatch"])
        for label, src in variants.items():
            probes = {}
            for i in range(1, 4):
                out = gl.run(probe_source(src, i, "gl_FragCoord.xy/uTiles"), uniforms,
                             off_tex, size=(CW, CH))[..., :2]
                probes[f"off{i}"] = error_stats(out, expected, OFFSET_TOL)
            written = []
            mismatch = gl.run(src, uniforms, full_tex, size=(CW, CH), report=written)[..., :3]
            summary[label] = {"offset_probes": probes,
                              "mismatch": error_stats(mismatch, cpu[..., None], MISMATCH_TOL),
                              "per_channel_mean": mismatch.mean(axis=(0, 1)).tolist(),
                              "cpu_mean": float(cpu.mean()), "uniforms_written": written}
        summary["fuse_offset_probes"] = {}
        for i in range(1, 4):
            src = probe_source(shaders["kFsMfnrFuse"], i, f"gl_FragCoord.xy/vec2({W},{H})")
            out = gl.run(src, {"uTiles": (TW, TH)}, off_tex)[..., :2]
            summary["fuse_offset_probes"][f"off{i}"] = error_stats(out, fuse_expected, OFFSET_TOL)
        checks = [*summary["production"]["offset_probes"].values(),
                  summary["production"]["mismatch"], *summary["fuse_offset_probes"].values()]
        summary["status"] = "PASS" if all(c["passed"] for c in checks) else "FAIL"
        return summary
    finally:
        for tex in textures:
            tex.release()
        gl.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, help="Optional JSON report; no default output file")
    parser.add_argument("--legacy", action="store_true", help="Also replay historical wrong-grid functions in memory")
    args = parser.parse_args()
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location("engine_shader_offline", ROOT / "tools/engine-shader-offline.py")
    engine = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(engine)
    shaders = engine.extract_shaders()
    summary = run(engine, {k: shaders[k] for k in ("kFsMfnrMismatch", "kFsMfnrFuse")}, args.legacy)
    report = json.dumps(summary, indent=2, allow_nan=False)
    if args.out:
        args.out.write_text(report + "\n", encoding="utf-8")
    print(report)
    return 0 if summary["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
