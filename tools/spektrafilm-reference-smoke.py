#!/usr/bin/env python3
"""Run the pinned Spektrafilm topology on a synthetic or D0 scene-linear fixture."""

import argparse
import hashlib
import json
import subprocess
import sys
from dataclasses import replace
from importlib.metadata import distributions
from pathlib import Path

import numpy as np


UPSTREAM = "3bb2c2d2801ff68b92019cf1dbcbb133d60832bc"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("upstream", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--d0-sidecar", type=Path)
    parser.add_argument("--case", choices=("print-fixed", "print-auto", "positive-scan"))
    args = parser.parse_args()

    def git(*argv):
        return subprocess.check_output(["git", "-C", args.upstream, *argv], text=True).strip()

    if git("rev-parse", "HEAD") != UPSTREAM or git("status", "--porcelain"):
        parser.error("expected a clean checkout of the pinned revision")

    sys.path.insert(0, str(args.upstream.resolve() / "src"))
    from spektrafilm.config import SPECTRAL_SHAPE
    from spektrafilm.runtime.params_builder import digest_params, init_params
    from spektrafilm.runtime.pipeline import SimulationPipeline

    if args.d0_sidecar:
        if args.case not in ("print-fixed", "positive-scan"):
            parser.error("D0 requires a single fixed-exposure case")
        meta = json.loads(args.d0_sidecar.read_text())
        h, w = meta["h"], meta["w"]
        expected = dict(type="float32_le", stride_bytes=w * 16, components=4,
                        orientation="gl_bottom_up", stage="base_engine_input",
                        space="scene-linear Rec.2020 D65", complete=True)
        raw = args.d0_sidecar.with_suffix(".rgba32f")
        if h <= 0 or w <= 0 or any(meta.get(k) != v for k, v in expected.items()) or raw.stat().st_size != h*w*16:
            parser.error("invalid D0 sidecar or pixel size")
        rgba = np.memmap(raw, dtype="<f4", mode="r", shape=(h, w, 4))
        if not np.isfinite(rgba).all() or not np.all(rgba[..., 3] == 1):
            parser.error("invalid D0 RGBA values")
        image = np.ascontiguousarray(rgba[::-1, :, :3])
        with raw.open("rb") as handle:
            fixture = dict(sha256=hashlib.file_digest(handle,"sha256").hexdigest(),
                           sidecar=meta, orientation="top_down after GL row flip")
    else:
        x, y = np.meshgrid(np.geomspace(0.002, 4.0, 160), np.linspace(0, 1, 96))
        image = np.stack((x, x * (1 - 0.8 * y), x * (0.2 + 0.8 * y)), axis=-1)
        fixture = "synthetic, not production parity"
    args.output.mkdir(parents=True, exist_ok=False)
    if not args.d0_sidecar:
        np.save(args.output / "rgb_in.npy", image, allow_pickle=False)
    report = dict(upstream=UPSTREAM, fixture=fixture,
                  python=sys.version, versions={d.metadata["Name"]: d.version for d in distributions()},
                  wavelengths=SPECTRAL_SHAPE.range().tolist(), cases={})

    for name, film, scan_film, auto_exposure in (
        ("print-fixed", "kodak_portra_400", False, False),
        ("print-auto", "kodak_portra_400", False, True),
        ("positive-scan", "fujifilm_provia_100f", True, False),
    ):
        if args.case and args.case != name:
            continue
        params = init_params(film_profile=film)
        params.camera.auto_exposure = auto_exposure
        params.io.input_color_space = "ITU-R BT.2020"
        params.io.input_cctf_decoding = False
        params.io.scan_film = scan_film
        params.settings.preview_mode = False
        params.debug.lut_mode = False
        params.settings.use_enlarger_lut = False
        params.settings.use_scanner_lut = False
        pipeline = SimulationPipeline(digest_params(params))
        case_output = args.output / name
        case_output.mkdir()
        tapped = {}

        def tap(run, label):
            def observe(*values):
                result = run(*values)
                if not np.isfinite(result).all():
                    raise ValueError(f"{label}: non-finite output")
                path = case_output / f"{label}.npy"
                np.save(path, result, allow_pickle=False)
                with path.open("rb") as handle:
                    sha = hashlib.file_digest(handle, "sha256").hexdigest()
                tapped[label] = dict(shape=result.shape, dtype=result.dtype.str, sha256=sha)
                print(name, label, result.shape, result.dtype, flush=True)
                return result
            return observe

        # Observe one traversal without reinjecting arrays or changing stage state.
        pipeline._topology = [replace(node, run=tap(node.run, node.writes[0]))
                              for node in pipeline._topology]
        result = pipeline.process(image)
        expected = ["rgb_pre", "log_e_film", "cmy_film"]
        if not scan_film:
            expected += ["log_e_print", "cmy_print"]
        expected += ["rgb_out"]
        if list(tapped) != expected or result.shape != image.shape or np.ptp(result) == 0:
            raise RuntimeError(f"{name}: unexpected traversal or output")
        report["cases"][name] = dict(film=film, paper=params.print.info.stock, scan_film=scan_film,
                                     auto_exposure=auto_exposure, stages=tapped)
        print(name, result.shape, tapped["rgb_out"]["sha256"], flush=True)

    (args.output / "manifest.json").write_text(json.dumps(report, indent=2, allow_nan=False) + "\n")


if __name__ == "__main__":
    main()
