#!/usr/bin/env python3
"""GPU regression for production kFsMfnrMismatch observability/motion separation.

Run: python3 tools/check-highlight-temporal.py [--out /tmp/highlight-temporal.json]
Requires the existing numpy/moderngl/glfw environment. Only reads engine source;
executes its shader through engine-shader-offline.GL without rewriting the shader.
--cpu-only validates fixtures/oracle, NOT production, and reports CPU_ONLY.

UNKNOWN is encoded as m=1: ordinary temporal consumers conservatively exit. It is
not measured motion. Selected-short highlight recovery uses independent Bento
admission M and must NOT consume this m, nor force unknown to m=0. This test covers
the mismatch producer, not the full Bento/fuse consumer or Android device parity.
"""
import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import sys

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
CELL = 32
# The shared GL helper writes RGBA16F, not production RGBA8. One half-float ULP
# near m=1 plus a small arithmetic allowance; do not use an RGBA8-sized tolerance.
HALF_TOL = 0.00051


def uniforms(w, h, **changes):
    values = {
        "uSize": (w, h), "uTiles": ((w + CELL - 1) // CELL, (h + CELL - 1) // CELL),
        # Production displacement tiles are 128 mosaic px; never allocate a
        # dummy full mismatch-sized field (which hides the old off2/3 grid bug).
        "uOffTiles": ((w + 127) // 128, (h + 127) // 128),
        "uTileM": float(CELL), "uNoiseA": (1e-5,) * 3,
        "uNoiseB": (1e-6,) * 3, "uGainRatio": (1.0,) * 4,
        "uS": 16.0, "uCfaLut": 97, "uBlack": (64.0,) * 4,
        "uWhite": 1023.0, "uGain": (2.0, 1.0, 1.5),
        "uFrameScale": (1.0,) * 4,
    }
    values.update(changes)
    return values


def offset_at(field, uv):
    height, width = field.shape[:2]
    point = np.asarray(uv) * (width, height) - 0.5
    low = np.floor(point).astype(int)
    fx, fy = point - low
    high = np.clip(low + 1, (0, 0), (width - 1, height - 1))
    low = np.clip(low, (0, 0), (width - 1, height - 1))
    a, b = field[low[1], low[0], :2], field[low[1], high[0], :2]
    c, d = field[high[1], low[0], :2], field[high[1], high[0], :2]
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy


def cpu_reference(frames, fields, u):
    """Independent native-code validity, nonzero same-phase taps, pair means."""
    w, h = u["uSize"]
    cw, ch = u["uTiles"]
    result = np.ones((ch, cw, 3), dtype=float)
    counts = np.zeros((ch, cw, 3, 4), dtype=int)
    residual = np.zeros((ch, cw, 3), dtype=float)
    variance = np.zeros_like(residual)
    limits = [math.ceil(b + (u["uWhite"] - b) * .98) for b in u["uBlack"]]

    def valid(raw, x, y):
        return 0 <= x < w and 0 <= y < h and int(raw[y, x]) < limits[(y % 2) * 2 + x % 2]

    def linear(raw, x, y):
        phase = (y % 2) * 2 + x % 2
        channel = (u["uCfaLut"] >> (phase * 2)) & 3
        black = u["uBlack"][phase]
        return max((int(raw[y, x]) - black) / max(u["uWhite"] - black, 1), 0) * u["uGain"][channel]

    def sample(raw, x, y, delta):
        phase_x, phase_y = x % 2, y % 2
        sx, sy = (np.array((x, y)) + delta - (phase_x, phase_y)) / 2
        bx, by = math.floor(sx), math.floor(sy)
        fx, fy = sx - bx, sy - by
        value = 0.0
        for tx, ty, weight in ((0, 0, (1-fx)*(1-fy)), (1, 0, fx*(1-fy)),
                               (0, 1, (1-fx)*fy), (1, 1, fx*fy)):
            if weight == 0:
                continue
            px, py = phase_x + 2*(bx+tx), phase_y + 2*(by+ty)
            if not valid(raw, px, py):
                return None
            value += weight * linear(raw, px, py)
        return value

    for cy in range(ch):
        for cx in range(cw):
            offsets = [offset_at(f, ((cx+.5)/cw, (cy+.5)/ch)) for f in fields]
            for dy in range(0, CELL, 4):
                for dx in range(0, CELL, 4):
                    for phase in range(4):
                        x, y = cx*CELL+dx+phase%2, cy*CELL+dy+phase//2
                        if not valid(frames[0], x, y):
                            continue
                        channel = (u["uCfaLut"] >> (phase*2)) & 3
                        a, b = u["uNoiseA"][channel], u["uNoiseB"][channel]
                        s0, g0 = u["uFrameScale"][0], u["uGainRatio"][0]
                        r = linear(frames[0], x, y) * s0
                        for alt in range(3):
                            value = sample(frames[alt+1], x, y, offsets[alt])
                            if value is None:
                                continue
                            scale, gain = u["uFrameScale"][alt+1], u["uGainRatio"][alt+1]
                            value *= scale
                            residual[cy, cx, alt] += (r-value)**2
                            variance[cy, cx, alt] += a*(r*s0+value*scale) + b*((g0*s0)**2+(gain*scale)**2)
                            counts[cy, cx, alt, phase] += 1
            for alt in range(3):
                phase_counts = counts[cy, cx, alt]
                n = int(phase_counts.sum())
                if n >= 4 and np.all(phase_counts >= 1):
                    # Independent mean normalization by each alternate's OWN n.
                    d2, noise = residual[cy, cx, alt]/n, variance[cy, cx, alt]/n
                    result[cy, cx, alt] = d2/(d2+u["uS"]*max(noise, 1e-12/n))
    return result, counts, residual, variance


def make_cases():
    cases = []

    def add(name, frames, *, offsets=((0., 0.),)*3, expect=None, **changes):
        h, w = frames[0].shape
        u = uniforms(w, h, **changes)
        ow, oh = u["uOffTiles"]
        fields = [np.broadcast_to(np.array((*d, 1.), dtype=np.float32), (oh, ow, 3)).copy() for d in offsets]
        case = {"name": name, "frames": frames, "fields": fields, "uniforms": u, "expect": expect}
        cases.append(case)
        return case

    def flat(value, n=64):
        return np.full((n, n), value, np.uint16)

    ref = flat(400)
    add("same_exposure_static", [ref]*4, expect="zero")
    add("static_hdr_all_phases_observable", [ref, flat(148), flat(148), flat(148)],
        uFrameScale=(1., 4., 4., 4.), expect="zero")
    # Match the old reproducer's static scene: native G clips in the reference,
    # while WB red/blue remain observable. Missing G phases -> UNKNOWN, not motion.
    gains = np.tile(np.array(((1., 2.), (1.5, 1.))), (32, 32))
    scene = 1.2/gains
    bright = np.rint(64+959*np.minimum(scene, 1)).astype(np.uint16)
    short = np.rint(64+959*np.minimum(scene*.25, 1)).astype(np.uint16)
    add("static_hdr_clipped_reference_valid_short_unknown", [bright, short, short, short],
        uFrameScale=(1., 4., 4., 4.), expect="unknown")
    add("valid_reference_clipped_alternates_unknown", [ref, flat(1023), flat(1023), flat(1023)], expect="unknown")
    # Repro's clipped same-exposure control is now also UNKNOWN, not m=0.
    add("same_exposure_clipped_unobservable_unknown", [bright]*4, expect="unknown")
    red = ref.copy()
    red[0::2, 1::2] += 120
    add("red_only_motion_green_identical", [ref, red, ref, ref], expect="red_motion")
    blue = ref.copy()
    blue[1::2, 0::2] += 120
    add("blue_only_motion_green_identical", [ref, blue, ref, ref], expect="red_motion")
    missing = ref.copy()
    missing[0::2, 1::2] = 1023
    add("one_missing_cfa_phase_unknown", [ref, missing, missing, missing], expect="unknown")

    # Four valid samples, one per phase, are sufficient. Different ALT counts
    # (4,8,256) and gain ratios exercise independent d² AND noise accumulation.
    small = flat(400, 32)
    alts = []
    for alt, quads in enumerate((1, 2, 64)):
        raw = flat(1023, 32)
        for index in range(quads):
            y, x = (index//8)*4, (index%8)*4
            raw[y:y+2, x:x+2] = 400+20*(alt+1)
        alts.append(raw)
    add("constant_noise_per_alt_pair_normalization", [small, *alts],
        uNoiseA=(0.,)*3, uNoiseB=(1e-4,)*3, uGain=(1.,)*3,
        uGainRatio=(1., 1., 2., 3.), expect="counts")
    add("exact_four_pairs_at_image_edge", [flat(400, 2)]*4, expect="zero")
    add("less_than_four_pairs_unknown", [np.full((1, 4), 400, np.uint16)]*4, expect="unknown")

    clipped_taps = flat(400)
    yy, xx = np.indices(clipped_taps.shape)
    clipped_taps[(xx % 4 >= 2) | (yy % 4 >= 2)] = 1023
    add("fractional_tap_clip_cannot_hide_in_average", [ref, clipped_taps, clipped_taps, clipped_taps],
        offsets=((1., 1.),)*3, expect="unknown")
    add("zero_weight_clipped_taps_do_not_invalidate", [ref, clipped_taps, clipped_taps, clipped_taps], expect="zero")
    add("outside_support_unknown_not_edge_clamp", [ref]*4, offsets=((-256., 0.),)*3, expect="unknown")
    add("fractional_outside_support_unknown", [flat(400, 2)]*4, offsets=((-0.5, -0.5),)*3, expect="unknown")

    # Exercise integer threshold at every phase with distinct black levels.
    black = (64., 80., 96., 112.)
    limits = [math.ceil(b+(1023-b)*.98) for b in black]
    below = np.empty((64, 64), np.uint16)
    at = below.copy()
    for phase, limit in enumerate(limits):
        below[phase//2::2, phase%2::2] = limit-1
        at[phase//2::2, phase%2::2] = limit
    add("native_integer_threshold_below_is_valid", [below]*4, uBlack=black, expect="zero")
    add("native_integer_threshold_at_is_unknown", [at]*4, uBlack=black, expect="unknown")
    # Native validity is BEFORE WB/scale: observed values above one are valid.
    add("valid_native_above_one_after_wb_scale", [ref]*4, uGain=(4., 4., 4.),
        uFrameScale=(4.,)*4, expect="zero")

    add("non_clipped_legacy_formula_half_tolerance", [ref, flat(420), flat(440), flat(460)],
        uGain=(1.,)*3, uNoiseA=(2e-4,)*3, uNoiseB=(1e-4,)*3, expect="legacy")
    add("per_color_shot_read_exposure_gain_noise", [ref, flat(160), flat(300), flat(640)],
        uFrameScale=(1.25, 4., 1.5, .75), uGainRatio=(1.2, 1.8, 2.5, .8),
        uNoiseA=(2e-4, 5e-4, 8e-4), uNoiseB=(1e-4, 2e-4, 3e-4))
    # Nonuniform field, REAL 2x2 offset grid versus 8x8 mismatch grid, catches
    # coordinate/OOB mistakes in off2/3 as well as fractional sampling arithmetic.
    yy, xx = np.indices((256, 256))
    ramp = (150+xx+yy).astype(np.uint16)
    varying = add("fractional_offsets_real_coarse_grid", [ramp]*4,
                  uGain=(1.,)*3, uNoiseA=(0.,)*3, uNoiseB=(2e-6,)*3)
    for alt in range(3):
        for y in range(2):
            for x in range(2):
                varying["fields"][alt][y, x, :2] = (.25+alt*.5+x*.5, .5+y*.25)
    return cases


def fixture_check(case, expected, counts):
    kind = case["expect"]
    if kind == "zero":
        return bool(np.max(expected) == 0)
    if kind == "unknown":
        return bool(np.min(expected) == 1)
    if kind == "red_motion":
        return bool(np.min(expected[..., 0]) > .1 and np.max(expected[..., 1:]) == 0)
    if kind == "counts":
        return bool(np.array_equal(counts.sum(axis=-1)[0, 0], (4, 8, 256))
                    and np.all(expected > 0) and np.all(expected < 1))
    if kind == "legacy":
        u = case["uniforms"]
        r = (400-64)/959
        old = []
        for code in (420, 440, 460):
            a = (code-64)/959
            old.append((r-a)**2/((r-a)**2+u["uS"]*(2e-4*(r+a)+2e-4)))
        return bool(np.max(np.abs(expected-np.array(old))) < 1e-12)
    return True


def gpu_case(engine, gl, shader, case):
    textures = {}
    w, h = case["uniforms"]["uSize"]
    ow, oh = case["uniforms"]["uOffTiles"]
    try:
        for i, raw in enumerate(case["frames"]):
            textures[f"uRaw{i}"] = gl.ctx.texture((w, h), 1, np.asarray(raw, dtype=np.uint16).tobytes(), dtype="u2")
        for i, field in enumerate(case["fields"], 1):
            textures[f"uOff{i}"] = gl.ctx.texture((ow, oh), 3, field.astype(np.float32).tobytes(), dtype="f4")
        for texture in textures.values():
            texture.filter = (engine.moderngl.NEAREST,)*2
            texture.repeat_x = texture.repeat_y = False
        written = []
        actual = gl.run(shader, case["uniforms"], textures,
                        size=case["uniforms"]["uTiles"], report=written)[..., :3]
        return actual, written
    finally:
        for texture in textures.values():
            texture.release()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, help="Optional JSON report")
    parser.add_argument("--cpu-only", action="store_true", help="Check oracle/fixtures only; does not validate production")
    args = parser.parse_args()
    sys.dont_write_bytecode = True
    gl = None
    summary = {"status": "CPU_ONLY" if args.cpu_only else "PASS", "device_verified": False,
               "scope": "Production mismatch producer on desktop GL; independent Bento admission/consumer is NOT replayed",
               "unknown_semantics": "m=1 is unobserved, not motion; ordinary temporal conservative exit; selected Bento independent",
               "output": "RGBA16F via shared GL helper; production RGBA8; no engine or old reproducer edits",
               "half_float_absolute_tolerance": HALF_TOL, "cases": []}
    try:
        if not args.cpu_only:
            spec = importlib.util.spec_from_file_location("engine_shader_offline", ROOT/"tools/engine-shader-offline.py")
            engine = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(engine)
            shader = engine.extract_shaders()["kFsMfnrMismatch"]
            summary["shader_sha256"] = hashlib.sha256(shader.encode()).hexdigest()
            gl = engine.GL(64, 64)
            summary["renderer"] = gl.ctx.info.get("GL_RENDERER")
            summary["gl_version"] = gl.ctx.info.get("GL_VERSION")
        for case in make_cases():
            expected, counts, d2, var = cpu_reference(case["frames"], case["fields"], case["uniforms"])
            fixture_pass = fixture_check(case, expected, counts)
            observed = np.all(counts >= 1, axis=-1) & (counts.sum(axis=-1) >= 4)
            item = {"name": case["name"], "fixture_passed": fixture_pass, "passed": fixture_pass,
                    "uniforms": case["uniforms"], "cpu_per_alt_mean": expected.mean(axis=(0, 1)).tolist(),
                    "per_alt_pair_count_min": counts.sum(axis=-1).min(axis=(0, 1)).tolist(),
                    "per_alt_pair_count_max": counts.sum(axis=-1).max(axis=(0, 1)).tolist(),
                    "per_alt_phase_count_min": counts.min(axis=(0, 1)).tolist(),
                    "unknown_alt_cells": int(np.count_nonzero(~observed)),
                    "cpu_map_rgb": expected.tolist()}
            if case["expect"] == "counts":
                item["sum_d2"] = d2.tolist()
                item["sum_variance"] = var.tolist()
            if gl is not None:
                actual, written = gpu_case(engine, gl, shader, case)
                error = np.abs(actual-expected)
                finite = bool(np.isfinite(actual).all())
                item.update(gpu_per_alt_mean=actual.mean(axis=(0, 1)).tolist(),
                            gpu_map_rgb=actual.tolist(), max_abs_error=float(error.max()),
                            uniforms_written=written, finite=finite,
                            passed=fixture_pass and finite and bool(np.all(error <= HALF_TOL)))
            summary["cases"].append(item)
        summary["passed_cases"] = sum(c["passed"] for c in summary["cases"])
        summary["total_cases"] = len(summary["cases"])
        if summary["passed_cases"] != summary["total_cases"]:
            summary["status"] = "FAIL"
    except Exception as exc:
        summary["status"] = "ERROR"
        summary["error"] = f"{type(exc).__name__}: {exc}"
    finally:
        if gl is not None:
            gl.close()
    report = json.dumps(summary, indent=2, allow_nan=False)
    if args.out:
        args.out.write_text(report+"\n", encoding="utf-8")
    print(report)
    return 0 if summary["status"] in ("PASS", "CPU_ONLY") else 1


if __name__ == "__main__":
    raise SystemExit(main())
