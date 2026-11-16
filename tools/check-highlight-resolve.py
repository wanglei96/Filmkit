#!/usr/bin/env python3
"""Read-only CPU/GLSL-contract checks for the post-demosaic source-white resolve.

No full build, device, or third-party Python dependencies. A CPU model is checked
against an independent sensor-domain RGB reference. This is NOT GPU replay or a
claim that PC's output-white policy preserves every unclipped coloured sample.
If glslangValidator is installed, also compile the actual extracted ES shader.
"""
import math
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HEADER = ROOT / "core/engine/src/render/HighlightResolveShader.h"


def mix(a, b, t):
    return a + (b - a) * t


def shader_uv(x, y, dw, dh, rot):
    cx, cy = (x + .5) / dw - .5, (y + .5) / dh - .5
    rx, ry = ((cx, cy), (-cy, cx), (-cx, -cy), (cy, -cx))[rot]
    return rx + .5, .5 - ry


def independent_raw(x, y, width, height, rot):
    # Integer index mapping derived directly from the RCD rotated output grid,
    # not by reusing the candidate shader's UV function.
    return ((x, height - 1 - y),
            (width - 1 - y, height - 1 - x),
            (width - 1 - x, y),
            (y, x))[rot]


def bilinear(image, u, v):
    """GL LINEAR+CLAMP_TO_EDGE, same half-pixel coordinates as manual LSC."""
    height, width = len(image), len(image[0])
    fx, fy = u * width - .5, v * height - .5
    x, y = math.floor(fx), math.floor(fy)
    tx, ty = fx - x, fy - y
    fetch = lambda xx, yy: image[min(max(yy, 0), height - 1)][min(max(xx, 0), width - 1)]
    return mix(mix(fetch(x, y), fetch(x + 1, y), tx),
               mix(fetch(x, y + 1), fetch(x + 1, y + 1), tx), ty)


def independent_sample(image, u, v):
    # Independent weighted-sum implementation rather than nested interpolation.
    height, width = len(image), len(image[0])
    xx, yy = u * width - .5, v * height - .5
    bx, by = math.floor(xx), math.floor(yy)
    result = 0.
    for dy in (0, 1):
        for dx in (0, 1):
            weight = (1 - abs(xx - (bx + dx))) * (1 - abs(yy - (by + dy)))
            result += image[min(max(by + dy, 0), height - 1)][min(max(bx + dx, 0), width - 1)] * weight
    return result


def close(actual, expected, label):
    assert math.isclose(actual, expected, rel_tol=2e-12, abs_tol=2e-12), (label, actual, expected)


def check_pixels():
    checks = 0
    # Non-square, odd, partial/padded RAW/4 masks, and degenerate source axes.
    for width, height in ((12, 8), (13, 9), (18, 11), (4, 4), (1, 7), (7, 1)):
        mw, mh = max(width // 4, 1), max(height // 4, 1)
        affine = [[(.07 + .11 * x + .19 * y) % 1 for x in range(mw)] for y in range(mh)]
        threshold = [[float(x * 2 >= mw) for x in range(mw)] for _ in range(mh)]
        quantized = [[round(v * 255) / 255 for v in row] for row in affine]
        # RGB-dependent asymmetric LSC gradients detect rotations and half-pixel
        # mistakes which a 1x1/uniform LSC test would miss.
        lsc = [[[1. + .13 * x + .07 * y + .03 * c for x in range(4)] for y in range(3)] for c in range(3)]
        for rot in range(4):
            dw, dh = (height, width) if rot & 1 else (width, height)
            for y in range(dh):
                for x in range(dw):
                    u, v = shader_uv(x, y, dw, dh, rot)
                    rx = min(max(int(u * width), 0), width - 1)
                    ry = min(max(int(v * height), 0), height - 1)
                    ix, iy = independent_raw(x, y, width, height, rot)
                    assert (rx, ry) == (ix, iy), (width, height, rot, x, y, rx, ry, ix, iy)
                    ru, rv = (rx + .5) / width, (ry + .5) / height
                    close(u, (ix + .5) / width, "RAW mask u")
                    close(v, (iy + .5) / height, "RAW mask v")
                    for mask in (affine, threshold, quantized):
                        # Accepted, rejected, no-highlight; rejection must ignore
                        # stale nonzero texture values, not merely trust a mask.
                        for has_short in (False, True):
                            m = min(max(bilinear(mask, u, v), 0.), 1.) if has_short else 0.
                            im = min(max(independent_sample(mask, (ix + .5) / width, (iy + .5) / height), 0.), 1.) if has_short else 0.
                            close(m, im, "mask domain/rotation")
                            for gain in ((2., 1., 1.7), (.8, 1., 2.3)):
                                camera_white = (.5, 1., .6)
                                ref_scale, short_scale = 1., 4.
                                white_scale = mix(ref_scale, short_scale, m)
                                alpha = .37
                                for c in range(3):
                                    shading = bilinear(lsc[c], ru, rv)
                                    independent_shading = independent_sample(lsc[c], (ix + .5) / width, (iy + .5) / height)
                                    close(shading, independent_shading, "LSC sensor position")
                                    for native in (.08, .7, 1., 3.8, -.01):
                                        source = native * gain[c] * shading
                                        ceiling = camera_white[c] * gain[c] * white_scale
                                        actual = min(source / shading, ceiling) * shading
                                        expected = min(native, camera_white[c] * (ref_scale + (short_scale - ref_scale) * im)) * gain[c] * independent_shading
                                        close(actual, expected, "PC RGB resolve transported to WB+LSC")
                                        result = (actual, alpha)
                                        assert result[1] == alpha
                                        checks += 1
    return checks


def check_policy():
    # Below-bound colours survive exactly; source-neutral fully clipped RGB
    # becomes neutral with source WB (inverse neutral), without a Bayer phase cap.
    neutral, gains = (.5, 1., .6), (2., 1., 1. / .6)
    low_native = (.1, .2, .15)
    output = [min(low_native[c] * gains[c], neutral[c] * gains[c]) for c in range(3)]
    for c in range(3):
        close(output[c], low_native[c] * gains[c], "below-ceiling genuine colour")
    all_clip = [min(gains[c], neutral[c] * gains[c]) for c in range(3)]
    for value in all_clip:
        close(value, 1., "all-clipped neutral ceiling")
    # Explicitly document the unavoidable policy: unclipped native red=.7 is
    # above source-neutral=.5 and is reduced (WB red 1.4 -> 1). Not a fullwell veto.
    assert .7 < 250 / 255
    close(min(.7 * gains[0], neutral[0] * gains[0]), 1., "unclipped colour can be capped")
    assert .7 * gains[0] > 1.
    # Creative WB changes transport the same source neutral, not the policy's
    # underlying source-white chromaticity; the WB-domain ceiling need not white.
    creative = (.8, 1., 2.3)
    assert [neutral[c] * creative[c] for c in range(3)] == [.4, 1., 1.38]
    # A fractional selected share has a continuous ceiling, no m>0 hard switch.
    for m in (0., 1 / 255, .2, .5, 254 / 255, 1.):
        close(mix(1., 4., m), 1. + 3. * m, "continuous exposure ceiling")


def check_shader():
    text = HEADER.read_text(encoding="utf-8")
    shader = text.split('R"(', 1)[1].split(')";', 1)[0]
    uniforms = ("uTex", "uBentoMask", "uLsc", "uDstSize", "uRawSize", "uRot", "uLscSize", "uCameraWhite", "uGain", "uReferenceScale", "uShortScale", "uHasShort")
    for name in uniforms:
        assert any(line.startswith("uniform ") and line.endswith(" " + name + ";") for line in shader.splitlines()), name
    for contract in ("source.rgb / shading", "uCameraWhite * uGain * whiteScale", "min(unshaded, ceiling) * shading, source.a", "uHasShort != 0 ? clamp(texture(uBentoMask, uv).r", "vec2(r.x + 0.5, 0.5 - r.y)"):
        assert contract in shader, contract
    assert "usampler" not in shader and "uCfaLut" not in shader, "Resolve must consume full RGB, never native CFA clipping"
    validator = shutil.which("glslangValidator")
    if validator:
        with tempfile.TemporaryDirectory(prefix="highlight-resolve-") as directory:
            path = Path(directory) / "resolve.frag"
            path.write_text(shader, encoding="utf-8")
            subprocess.run([validator, "-S", "frag", str(path)], check=True)
        return "compiled with glslangValidator"
    return "glslangValidator unavailable; source contract only (not GPU compilation)"


def check_caller():
    source = (ROOT / "core/engine/src/render/FrameRenderer.cpp").read_text()
    start = source.index("bool FrameRenderer::passHighlightResolve(")
    resolve = source[start:source.index("bool FrameRenderer::ensureHighlightMergeResources", start)]
    assert "if (!highlight_resolve_ready_) return true;" in resolve
    assert "if (!highlight_merge_ready_) return true;" not in resolve
    assert "has_short ? tex_highlight_mask_ : lsc_tex_" in resolve
    burst = source[source.index("bool FrameRenderer::submitRawBurst("):]
    assert "highlight_resolve_ready_ = true;" in burst
    assert "passMfnrSatFix(" not in burst
    assert "demosaic == 0 && highlight_resolve_ready_" in burst
    single = source[source.index("void FrameRenderer::finishStillChain("):start]
    assert "highlight_resolve_ready_ = false;" in single


def main():
    result = check_shader()
    check_caller()
    checks = check_pixels()
    check_policy()
    print(f"PASS: {checks} independent RGB/rotation/mask/LSC comparisons; {result}")
    print("POLICY: below-ceiling colours preserved; above-source-white unclipped colours CAN be reduced (not sensor fullwell detection).")
    print("SCOPE: CPU/reference + shader contract only; on-device GPU replay and integrated P0/noise validation remain required.")
    print("CALLER: no-short bracket retains RGB source-white; typed dummy, single reset and demosaic routing guarded.")


if __name__ == "__main__":
    main()
