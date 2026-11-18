#!/usr/bin/env python3
"""Build and run the pinned darktable CPU tone path, outside the FilmKit runtime."""

import argparse
import ctypes
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

import numpy as np


REVISION = "395aa7d5156b68a69d8f2d25a3e6fc66c525a3c2"
FILMIC = "iop/filmicrgb.c"
COLOR = "common/colorspaces.c"
PROFILE = "common/iop_profile.h"
COLORSPACE = "common/colorspaces.h"
COLOROUT = "iop/colorout.c"
ACTIVE = (
    "grey_point_source", "black_point_source", "white_point_source",
    "grey_point_target", "black_point_target", "white_point_target",
    "output_power", "latitude", "contrast", "saturation", "balance",
    "auto_hardness", "custom_grey", "shadows", "highlights",
)
TARGETS = ("srgb", "p3", "rec2020", "work")


def build(upstream, out, cc):
    def git(*args):
        return subprocess.check_output(["git", "-C", str(upstream), *args])

    source_hashes = {}

    def source(path):
        data = git("show", f"{REVISION}:src/{path}")
        source_hashes[path] = hashlib.sha256(data).hexdigest()
        return data

    def section(path, *ranges):
        lines = source(path).decode().splitlines(keepends=True)
        return "\n".join(f'#line {lo} "src/{path}"\n' + "".join(lines[lo - 1:hi])
                         for lo, hi in ranges)

    def emit(path, text):
        dst = out / path
        dst.parent.mkdir(exist_ok=True, parents=True)
        dst.write_text(text, encoding="utf-8")

    # Use Git objects, including for the transitive math headers. Do not rely on
    # working-tree content or distribute generated sources with FilmKit.
    for path in ("common/dttypes.h", "common/math.h", "common/sse.h",
                 "common/colorspaces_inline_conversions.h", "common/chromatic_adaptation.h",
                 "common/gamut_mapping.h"):
        dst = out / path
        dst.parent.mkdir(exist_ok=True, parents=True)
        dst.write_bytes(source(path))

    emit("common/darktable.h", '#pragma once\n#include "common/dttypes.h"\n'
         + section("common/darktable.h", (126, 126), (130, 139), (306, 306), (318, 322), (915, 919)))
    emit(COLORSPACE, '#pragma once\n#include <lcms2.h>\n' + section(COLORSPACE, (35, 112)))
    emit(PROFILE, '#pragma once\n#include "common/chromatic_adaptation.h"\n'
         + section(PROFILE, (39, 58), (327, 368), (426, 453)))

    params = section(FILMIC, (168, 199))
    metadata = {}
    assignments = []
    for line in params.splitlines():
        if "$DEFAULT:" not in line:
            continue
        field = line.split(";")[0].split()[-1]
        values = dict(re.findall(r"\$(MIN|MAX|DEFAULT):\s*(\S+)", line))
        assignments.append(f". {field} = {values['DEFAULT']},")
        if field in ACTIVE:
            metadata[field] = ([float(values[x]) for x in ("MIN", "MAX")]
                               if "MIN" in values else [0, 2 if field in ("shadows", "highlights") else 1])
    if set(metadata) != set(ACTIVE):
        raise RuntimeError("Incomplete upstream parameter metadata")

    power_min, power_max = metadata["output_power"]
    emit("reference.inc", "\n".join((
        section("develop/openmp_maths.h", (83, 87)),
        section("develop/noise_generator.h", (22, 27)),
        section("develop/imageop.h", (641, 687)),
        section("iop/gaussian_elimination.h", (33, 104)),
        section(FILMIC, (61, 62), (98, 147), (159, 200), (285, 310), (826, 999),
                (1621, 1844), (1953, 2011)),
        section("common/matrices.c", (22, 51)),
        section(COLOR, (54, 86), (105, 250), (282, 330), (400, 423), (674, 685), (739, 752)),
        f"#define POWER_MIN {power_min}\n#define POWER_MAX {power_max}",
        section(FILMIC, (2592, 2602), (2756, 3132)),
        "static const dt_iop_filmicrgb_params_t defaults = { " + " ".join(assignments) + " };",
        "static const size_t n_active = " + str(len(ACTIVE)) + ";",
        "static void apply_overrides(dt_iop_filmicrgb_params_t *p, const float *v) {\n"
        + "\n".join(f"  if(isfinite(v[{i}])) p->{field} = v[{i}];" for i, field in enumerate(ACTIVE))
        + "\n}",
    )))
    emit("colorout.inc", "\n".join((
        section(COLOROUT, (45, 45)),
        "typedef struct { dt_colormatrix_t cmatrix; float lut[3][LUT_SAMPLES];"
        " float unbounded_coeffs[3][3]; } dt_iop_colorout_data_t;",
        section("develop/imageop_math.h", (104, 144)),
        section(COLOROUT, (307, 331), (457, 522)),
        "static void init_colorout_extrapolation(dt_iop_colorout_data_t *d) {",
        section(COLOROUT, (1306, 1320)),
        "}",
    )))
    flags = subprocess.check_output(["pkg-config", "--cflags", "--libs", "gmodule-2.0", "lcms2"],
                                    text=True).split()
    library = out / "filmic-v7-reference.so"
    command = [cc, "-std=gnu11", "-O2", "-fno-fast-math", "-ffp-contract=off", "-fPIC", "-shared",
               "-Werror=implicit-function-declaration", "-I", str(out),
               str(Path(__file__).with_suffix(".c")), "-o", str(library), *flags, "-lm"]
    subprocess.run(command, check=True)
    lib = ctypes.CDLL(str(library))
    ptr = np.ctypeslib.ndpointer(dtype=np.float32, flags=("C_CONTIGUOUS", "ALIGNED"))
    lib.render_v7.argtypes = [ctypes.c_int, ptr, ptr, ctypes.c_size_t, ctypes.c_size_t, ptr, ptr, ptr, ptr]
    lib.render_v7.restype = ctypes.c_int
    lib.create_colorout.argtypes = [ctypes.c_int]
    lib.create_colorout.restype = ctypes.c_void_p
    lib.run_colorout.argtypes = [ctypes.c_void_p, ptr, ptr, ptr, ptr, ctypes.c_size_t]
    lib.run_colorout.restype = None
    lib.free_colorout.argtypes = [ctypes.c_void_p]
    lib.free_colorout.restype = None
    provenance = {
        "upstream_commit": REVISION, "upstream_blobs_sha256": source_hashes,
        "compiler": subprocess.check_output([cc, "--version"], text=True).splitlines()[0],
        "pkg_versions": subprocess.check_output(["pkg-config", "--modversion", "gmodule-2.0", "lcms2"],
                                               text=True).splitlines(),
        "command": command, "scope": "serial CPU Filmic V7; fixed input; no highlight reconstruction or OETF",
    }
    return lib, metadata, provenance


def render(fn, metadata, source, output, profile, overrides):
    unknown = overrides.keys() - metadata.keys()
    if unknown:
        raise ValueError(f"Unknown parameters: {sorted(unknown)}")
    for key, val in overrides.items():
        lo, hi = metadata[key]
        if not isinstance(val, (bool, int, float)) or not lo <= val <= hi:
            raise ValueError(f"{key} must be in [{lo}, {hi}]")
        if key in ("shadows", "highlights", "custom_grey", "auto_hardness") and val != int(val):
            raise ValueError(f"{key} must be integral")
    overrides_vec = np.array([overrides.get(k, np.nan) for k in ACTIVE], dtype=np.float32)
    effective = np.empty_like(overrides_vec)
    derived = np.zeros(35, dtype=np.float32)
    to_target = np.empty((3, 3), dtype=np.float32)
    height, width, _ = source.shape
    if fn(TARGETS.index(profile), source, output, width, height, overrides_vec, effective, derived, to_target):
        raise ValueError("Reference failed: invalid parameters, profile, or nonfinite RGB output")
    # The module returns work-space channels, not target-space channels. Check
    # legality after the colorout matrix; do not clamp the reference in the harness.
    low, high = float("inf"), -float("inf")
    for row in range(0, height, 64):
        target_rgb = output[row:row + 64, :, :3] @ to_target.T
        low = min(low, float(target_rgb.min()))
        high = max(high, float(target_rgb.max()))
    if low < -0.001 or high > float(derived[5]) + 0.001:
        raise RuntimeError(f"Output exceeds target gamut: [{low}, {high}], white={derived[5]}")
    return {
        "effective_parameters": dict(zip(ACTIVE, effective.tolist())),
        "tone": dict(zip(("grey_source", "black_ev", "dynamic_range", "mix", "black_display",
                          "white_display", "norm_min", "norm_max", "latitude_min", "latitude_max"),
                         derived[:10].tolist())),
        "spline_x": derived[10:15].tolist(), "spline_y": derived[15:20].tolist(),
        "spline_coefficients_M1_to_M5": derived[20:35].reshape(5, 3).tolist(),
        "work_to_target_matrix": to_target.tolist(), "target_rgb_range_before_oetf": [low, high],
    }


def sha256(path):
    with path.open("rb") as file:
        return hashlib.file_digest(file, "sha256").hexdigest()


def srgb_encode(linear):
    x = np.maximum(linear, 0.0)
    return np.where(x <= 0.0031308, 12.92 * x, 1.055 * x ** (1 / 2.4) - 0.055)


def nominal_work_to_target(profile):
    def xyz(x, y):
        return [x / y, 1., (1. - x - y) / y]

    def rgb_to_xyz(primaries):
        columns = np.array([xyz(x, y) for x, y in primaries]).T
        return columns * np.linalg.solve(columns, xyz(0.3127, 0.3290))

    rec2020 = rgb_to_xyz(((0.708, 0.292), (0.170, 0.797), (0.131, 0.046)))
    red, green = ((0.64, 0.33), (0.30, 0.60)) if profile == "srgb" else ((0.68, 0.32), (0.265, 0.69))
    return np.linalg.solve(rgb_to_xyz((red, green, (0.15, 0.06))), rec2020)


def check_colorout(lib, profile, work, to_target, native=None):
    matrix = np.array(to_target, dtype=np.float64)
    nominal_error = float(np.max(np.abs(nominal_work_to_target(profile) - matrix)))
    # Native ICC uses D50 PCS and Little CMS's profile construction, not the
    # nominal direct D65 transform. Compare coefficients with that distinction.
    if nominal_error > 4e-5:
        raise RuntimeError(f"Unexpected {profile} profile coefficients: {nominal_error=}")
    handle = lib.create_colorout(TARGETS.index(profile))
    if not handle:
        raise RuntimeError(f"Could not create {profile} matrix/shaper profile")
    max_matrix, max_trc, max_joint, min_double, count = 0., 0., 0., float("inf"), 0
    encoded_sha256 = hashlib.sha256()
    native_errors = {}

    def compare(stage, reference, candidate):
        if not np.isfinite(candidate).all():
            raise RuntimeError(f"Nonfinite native {stage} output")
        diff = np.abs(reference.astype(np.float64) - candidate)
        stats = native_errors.setdefault(stage, dict(max_abs=0., channels_above_1e_5=0, channels_above_1e_4=0))
        stats["max_abs"] = max(stats["max_abs"], float(diff.max()))
        stats["channels_above_1e_5"] += int(np.count_nonzero(diff > 1e-5))
        stats["channels_above_1e_4"] += int(np.count_nonzero(diff > 1e-4))

    try:
        for start in range(0, len(work), 64):
            source = np.ascontiguousarray(work[start:start + 64])
            lab, linear, encoded = (np.empty_like(source) for _ in range(3))
            lib.run_colorout(handle, source, lab, linear, encoded, source.size // 4)
            target = linear[..., :3].astype(np.float64)
            actual = encoded[..., :3].astype(np.float64)
            direct = source[..., :3].astype(np.float64) @ matrix.T
            within = target <= 1.0  # Above 1, upstream uses a fitted extrapolation, not the ICC TRC.
            if not all(np.isfinite(v).all() for v in (direct, target, actual)):
                raise RuntimeError("Nonfinite colorout output")
            if np.any(actual[target > 1.001] <= 1.):
                raise RuntimeError("Unbounded shaper output was unexpectedly clipped")
            expected = srgb_encode(target)
            max_matrix = max(max_matrix, float(np.max(np.abs(target - direct))))
            max_trc = max(max_trc, float(np.max(np.abs(actual[within] - expected[within]), initial=0)))
            max_joint = max(max_joint, float(np.max(np.abs(actual[within] - srgb_encode(direct[within])), initial=0)))
            mid = (target >= 0.10) & (target <= 0.30)
            if mid.any():
                min_double = min(min_double, float(np.min(np.abs(actual[mid] - srgb_encode(expected[mid])))))
            count += int(within.size - np.count_nonzero(within))
            encoded_sha256.update(np.ascontiguousarray(encoded[..., :3]).tobytes())
            if native:
                native_tone, native_encoded = (v[start:start + len(source)] for v in native)
                compare("filmic", source[..., :3], native_tone[..., :3])
                compare("colorout", encoded[..., :3], native_encoded[..., :3])
                lib.run_colorout(handle, native_tone, lab, linear, encoded, source.size // 4)
                compare("colorout_fixed_input", encoded[..., :3], native_encoded[..., :3])
    finally:
        lib.free_colorout(handle)
    # Separate budgets for the float Lab round trip and the tabulated ICC shaper.
    if max_matrix > 5e-6 or max_trc > 5e-5 or max_joint > 1e-4 or not 0.1 <= min_double < float("inf"):
        raise RuntimeError(f"colorout mismatch: {max_matrix=}, {max_trc=}, {max_joint=}, {min_double=}")
    result = dict(nominal_d65_matrix_max_abs_coefficient_error=nominal_error,
                  matrix_max_abs_error=max_matrix, trc_max_abs_error_in_icc_domain=max_trc,
                  matrix_and_trc_max_abs_error_in_icc_domain=max_joint,
                  min_separation_from_double_encode_midtones=min_double,
                  unbounded_channels=count, encoded_rgb_sha256=encoded_sha256.hexdigest())
    if native:
        result["native_module_comparison"] = native_errors
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--cc", default="cc")
    parser.add_argument("--profile", choices=TARGETS, default="srgb")
    parser.add_argument("--params", type=Path, help="optional JSON object of overrides in upstream units")
    parser.add_argument("--check-colorout", action="store_true", help="check isolated sRGB/P3 matrix-shaper output")
    parser.add_argument("--native-outputs", type=Path, nargs=2, metavar=("TONE", "ENCODED"),
                        help="compare darktable probe outputs against the isolated reference")
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--input", type=Path, help="D0 base_engine_input .rgba32f plus matching .json sidecar")
    inputs.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if sys.byteorder != "little":
        parser.error("little endian host required")
    if args.self_test and args.params:
        parser.error("--params applies to --input only")
    if args.check_colorout and args.profile not in ("srgb", "p3"):
        parser.error("--check-colorout only supports sRGB and Display P3")
    if args.native_outputs and (not args.check_colorout or not args.input or args.params):
        parser.error("--native-outputs requires --input and --check-colorout with no parameter overrides")
    args.out = args.out.resolve()
    args.out.mkdir(parents=True, exist_ok=False)
    lib, metadata, manifest = build(args.upstream, args.out, args.cc)
    fn = lib.render_v7

    if args.self_test:
        # Neutral ramp, primaries, saturated highlights, negatives, and black.
        ramp = np.concatenate(([0.0], np.geomspace(1e-5, 16, 64))).astype(np.float32)
        bars = np.array([[1, 1, 1], [1, 0, 0], [0, 1, 0], [0, 0, 1], [1, 0.2, 0.8],
                         [1, 0.8, -0.1], [-1, -0.2, -0.8]], dtype=np.float32)
        source = np.ones((len(bars), len(ramp), 4), dtype=np.float32)
        source[:, :, :3] = bars[:, None, :] * ramp[None, :, None]
        output = np.empty_like(source)
        cases = []
        for p in TARGETS:
            for toe in range(3):
                for shoulder in range(3):
                    for mix in (-200, -50, -25, 0, 25, 50, 200):
                        params = {"shadows": toe, "highlights": shoulder, "saturation": mix}
                        result = render(fn, metadata, source, output, p, params)
                        cases.append({"target": p, **result,
                                      "rgb_sha256": hashlib.sha256(output[:, :, :3].tobytes()).hexdigest()})
        for p in TARGETS:
            result = render(fn, metadata, source, output, p, {
                "black_point_source": -10, "white_point_source": 6, "auto_hardness": False,
                "custom_grey": True, "grey_point_source": 20, "grey_point_target": 16,
                "black_point_target": 0.01, "white_point_target": 105, "output_power": 3.1,
                "contrast": 1.3, "latitude": 55, "balance": 17,
            })
            cases.append({"target": p, **result,
                          "rgb_sha256": hashlib.sha256(output[:, :, :3].tobytes()).hexdigest()})
        for p in TARGETS:
            if len({c["rgb_sha256"] for c in cases if c["target"] == p}) != 64:
                raise RuntimeError(f"Unexpectedly invariant synthetic outputs for {p}")
        first = render(fn, metadata, source, output, "srgb", {})
        snapshot = output.copy()
        second = render(fn, metadata, source, output, "srgb", {})
        if first != second or not np.array_equal(snapshot, output) or not np.array_equal(source[..., 3], output[..., 3]):
            raise RuntimeError("Call repeatability or fixture alpha check failed")
        manifest.update(cases=cases, synthetic_pixels_per_case=len(bars) * len(ramp))
        if args.check_colorout:
            manifest["colorout"] = {}
            for profile in ("srgb", "p3"):
                result = render(fn, metadata, source, output, profile, {})
                matrix = result["work_to_target_matrix"]
                # Exercise exact target-domain black, primaries, and the OETF knee
                # without first remapping them through Filmic's tone/gamut stages.
                levels = np.array([-0.001, 0., 0.001, 0.00312, 0.0031308, 0.00314, 0.18, 0.5, 0.99, 1., 1.05])
                target = np.array([[1, 1, 1], *np.eye(3)])[:, None, :] * levels[None, :, None]
                probe = np.ones((*target.shape[:2], 4), dtype=np.float32)
                probe[..., :3] = target @ np.linalg.inv(matrix).T
                manifest["colorout"][profile] = {
                    "direct_target_probes": check_colorout(lib, profile, probe, matrix),
                    "after_filmic": check_colorout(lib, profile, output, matrix),
                }
    else:
        sidecar = json.loads(args.input.with_suffix(".json").read_text())
        keys = {"type": "float32_le", "orientation": "gl_bottom_up", "alpha": 1, "components": 4,
                "space": "scene-linear Rec.2020 D65", "stage": "base_engine_input", "complete": True}
        if any(sidecar.get(k) != v for k, v in keys.items()):
            raise ValueError("Not a complete D0 base_engine_input")
        width, height = sidecar["w"], sidecar["h"]
        if (not all(type(d) is int and d > 0 for d in (width, height))
                or sidecar["stride_bytes"] != width * 16 or args.input.stat().st_size != width * height * 16):
            raise ValueError("Inconsistent D0 buffer size or stride")
        source = np.memmap(args.input, dtype="<f4", mode="r", shape=(height, width, 4))
        destination = args.out / "filmic-v7-work-rgb.rgba32f"
        output = np.memmap(destination, dtype="<f4", mode="w+", shape=source.shape)
        overrides = json.loads(args.params.read_text()) if args.params else {}
        manifest.update(render(fn, metadata, source, output, args.profile, overrides))
        output.flush()
        if args.check_colorout:
            native = None
            if args.native_outputs:
                if any(p.stat().st_size != source.nbytes for p in args.native_outputs):
                    raise ValueError("Unexpected native probe output length")
                native = tuple(np.memmap(p, dtype="<f4", mode="r", shape=source.shape) for p in args.native_outputs)
                manifest["native_outputs_sha256"] = {str(p): sha256(p) for p in args.native_outputs}
            manifest["colorout"] = check_colorout(lib, args.profile, output, manifest["work_to_target_matrix"], native)
        manifest.update(target=args.profile, requested_parameters=overrides, input_sha256=sha256(args.input),
                        input_sidecar=sidecar, output_sha256=sha256(destination),
                        output_space="display-referred RGB represented in linear Rec.2020/D65 primaries",
                        output_orientation=sidecar["orientation"], output_alpha="copied from fixture by harness",
                        output_rgb_min=float(output[:, :, :3].min()), output_rgb_max=float(output[:, :, :3].max()))

    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2, allow_nan=False) + "\n")
    print(f"Wrote {args.out / 'manifest.json'}")


if __name__ == "__main__":
    main()
