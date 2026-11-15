#!/usr/bin/env python3
"""Reproduce the existing MFNR clipping bug on GPU, without fixing it.

Uses a fixed HISTORICAL kFsMfnrMismatch via the existing GL helper.
Current validity/UNKNOWN behavior is tested by check-highlight-temporal.py.
Run: python3 tools/check-mfnr-clipping.py [--out /tmp/check-mfnr-clipping.json]
Installed python3 numpy/moderngl/glfw suffice. No shader/engine edits.
"""
import argparse
import hashlib
import importlib.util
import json
import re
import subprocess
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
HISTORICAL_REVISION = "74dd03f"
HISTORICAL_SHADER = None


def run_case(engine, frames, scales, gains=(2.0, 1.0, 1.5),
             gain_ratio=(1.0, 1.0, 1.0, 1.0)):
    h, w = frames[0].shape
    grid = ((w + 31) // 32, (h + 31) // 32)
    gl = engine.GL(w, h)
    textures = {}
    try:
        for i, a in enumerate(frames):
            t = gl.ctx.texture((w, h), 1, dtype="u2")
            t.write(np.asarray(a, dtype=np.uint16).tobytes())
            t.filter = (engine.moderngl.NEAREST, engine.moderngl.NEAREST)
            textures[f"uRaw{i}"] = t
        # Constant zero displacement avoids registration and off-grid confounds.
        # Allocate full cell grid: existing off2/3 read uTiles, off1 reads uOffTiles.
        for i in range(1, 4):
            t = gl.ctx.texture(grid, 3, dtype="f4")
            t.write(np.zeros((grid[1], grid[0], 3), np.float32).tobytes())
            t.filter = (engine.moderngl.NEAREST, engine.moderngl.NEAREST)
            textures[f"uOff{i}"] = t
        fs = HISTORICAL_SHADER
        assert fs is not None, "historical shader not loaded"
        uniforms = {
            "uSize": (w, h), "uTiles": grid, "uOffTiles": grid,
            "uTileM": 32.0, "uNoiseA": (1e-5,) * 3, "uNoiseB": (1e-6,) * 3,
            "uGainRatio": tuple(gain_ratio), "uS": 16.0, "uCfaLut": 97,
            "uBlack": (64.0,) * 4, "uWhite": 1023.0, "uGain": tuple(gains),
            "uFrameScale": tuple(scales),
        }
        written = []
        m = gl.run(fs, uniforms, textures, size=grid, report=written)[..., :3]
        return m, {
            "renderer": gl.ctx.info.get("GL_RENDERER"),
            "shader_sha256": hashlib.sha256(fs.encode()).hexdigest(),
            "uniforms": uniforms, "uniforms_written": written,
            "output": "GL helper RGBA16F; production mismatch map is RGBA8",
        }
    finally:
        for t in textures.values():
            t.release()
        gl.close()


def synthetic_frames(n=128):
    # GRBG: G,R / B,G => packed CFA LUT 1 | 0<<2 | 2<<4 | 1<<6 = 97.
    gains = np.array([[1.0, 2.0], [1.5, 1.0]])
    signal = np.tile(1.2 / gains, (n // 2, n // 2))
    def expose(exposure):
        return np.rint(64.0 + 959.0 * np.minimum(signal * exposure, 1.0)).astype(np.uint16)
    return expose(1.0), expose(0.25)


def summary(m):
    return {
        "mean": float(np.mean(m)), "median": float(np.median(m)),
        "min": float(np.min(m)), "max": float(np.max(m)),
        "per_alt_mean": np.mean(m, axis=(0, 1)).tolist(),
        "cells_m_ge_production_hard_reject_0_35": int(np.count_nonzero(m >= 0.35)),
        "cells_total": int(m.size), "map_rgb": m.tolist(),
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", default="/tmp/check-mfnr-clipping.json")
    ap.add_argument("--fixture", type=Path, default=ROOT / "test_temp/magenta-0143/matched/burst")
    args = ap.parse_args()
    global HISTORICAL_SHADER
    commit = subprocess.check_output(['git', 'rev-parse', '--verify',
        '--end-of-options', HISTORICAL_REVISION + '^{commit}'], cwd=ROOT, text=True).strip()
    old_source = subprocess.check_output(['git', 'show',
        commit + ':core/engine/src/render/FrameRenderer.cpp'], cwd=ROOT, text=True)
    match = re.search(r'const char\* kFsMfnrMismatch\s*=\s*R"\((.*?)\)";', old_source, re.S)
    if not match:
        raise RuntimeError('Historical mismatch shader not found')
    HISTORICAL_SHADER = match.group(1)
    spec = importlib.util.spec_from_file_location("engine_shader_offline", ROOT / "tools/engine-shader-offline.py")
    engine = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(engine)
    ref, short = synthetic_frames()
    control, control_meta = run_case(engine, [ref] * 4, (1.0,) * 4)
    hdr, hdr_meta = run_case(engine, [ref, short, short, short], (1.0, 4.0, 4.0, 4.0))
    g_ref = (int(ref[0, 0]) - 64) / 959
    g_short = (int(short[0, 0]) - 64) / 959 * 4
    reproduced = bool(np.max(control) < 0.01 and np.min(hdr) > 0.9)
    result = {
        "status": "EXISTING_BUG_REPRODUCED" if reproduced else "REPRODUCTION_NOT_OBSERVED",
        "not_a_fix": True,
        "historical_only": True,
        "historical_commit": commit,
        "current_check": "tools/check-highlight-temporal.py",
        "synthetic": {
            "scene": "same static neutral WB RGB=(1.2,1.2,1.2); GRBG; WB=(2,1,1.5)",
            "size": [128, 128], "zero_displacement": True,
            "reference_G": {"raw": int(ref[0, 0]), "clipped": True, "normalized": g_ref},
            "short_G": {"raw": int(short[0, 0]), "clipped": False, "scale": 4.0, "normalized": g_short},
            "incomparable_clip_residual": g_ref - g_short,
            "same_exposure_control": summary(control), "hdr_fixture": summary(hdr),
            "control_gpu": control_meta, "hdr_gpu": hdr_meta,
            "evidence": "Identical static scene: same-exposure control gives m=0; clipped reference G versus valid normalized short G>1 produces high m. The shader treats censored clipping residual as mismatch, falsely rejecting useful short exposure data.",
            "scope": "Production dx/dy stride 4 samples only even/even G in this GRBG fixture. Constant high m survives dilation/median. With residual fade=1, production hard rejection at m>=0.35 gives zero alternate weight; full CPU weight chain is not replayed.",
        },
    }
    if args.fixture.is_dir():
        fx = []
        for i in range(4):
            with (args.fixture / f"f{i}.meta").open() as f:
                meta = json.load(f)
            a = np.memmap(args.fixture / f"f{i}.raw", dtype="<u2", mode="r", shape=(meta["h"], meta["stridePx"]))
            fx.append(a[1658:1722, 744:808].copy())
        # Rounded matched capture metadata supplied from its log; not a device parity run.
        scales = (1.0, 1.0, 0.21, 2.82)
        fm, fmeta = run_case(engine, fx, scales, gains=(2.09375, 1.0, 1.787109),
                             gain_ratio=(1.0, 1.0, 100.0 / 72.0, 1.0))
        result["supplemental_fixture"] = {
            "path": str(args.fixture), "roi_rawxy_wh": [744, 1658, 64, 64],
            "summary": summary(fm), "gpu": fmeta,
            "limitation": "Zero displacement, rounded log exposure scales, illustrative noise coefficients (not capture calibrated), small ROI. Residual includes actual registration/noise/exposure errors; this supplement alone cannot establish clipping causality or full-device parity. Synthetic controlled pair provides the causal evidence.",
        }
    else:
        result["supplemental_fixture"] = {"skipped": "matched burst not present"}
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if not reproduced:
        raise SystemExit("Expected existing clipping bug was not reproduced; inspect GPU evidence.")


if __name__ == "__main__":
    main()
