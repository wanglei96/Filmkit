#!/usr/bin/env python3
"""Independent FilmKit SDR print-look reference. Not a stock simulation."""

import argparse
import hashlib
import json
from pathlib import Path
import time

import numpy as np


def rgb_to_xyz(primaries):
    xy = np.asarray(primaries).T
    xyz = np.vstack((xy, 1 - xy.sum(axis=0))) / xy[1]
    white = np.array([0.3127, 0.3290, 0.3583]) / 0.3290
    return xyz * np.linalg.solve(xyz, white)


REC2020 = rgb_to_xyz(((0.708, 0.292), (0.170, 0.797), (0.131, 0.046)))
SRGB = rgb_to_xyz(((0.640, 0.330), (0.300, 0.600), (0.150, 0.060)))
TO_SRGB = np.linalg.solve(SRGB, REC2020)
# Authored neutral-preserving response coefficients, not measured film data.
FILM = np.array([[.966, .029, .005], [.012, .974, .014], [.005, .043, .952]])
DYE = np.array([[.983, .012, .005], [.018, .975, .007], [.009, .026, .965]])
TOE, FILM_GAMMA, PAPER_GAMMA, GRAY, KNEE = .10, .85, 1.65, .18, .85
GRAY_LOG = -FILM_GAMMA * PAPER_GAMMA * np.log1p(GRAY / TOE)
PAPER_PIVOT = (-np.expm1(GRAY_LOG) / GRAY - 1) / np.exp(GRAY_LOG)


def film_density(rgb, matrix=FILM):
    exposure = rgb @ matrix.T
    # Contract signed sensor RGB toward equal-valued RGB at the same Y only
    # when a receptor would otherwise receive negative exposure.
    y = np.maximum(exposure @ REC2020[1], 0)[..., None]
    lo = exposure.min(axis=-1, keepdims=True)
    weight = np.divide(y, y - lo, out=np.zeros_like(y), where=lo < 0)
    exposure = np.where(lo < 0, y + weight * (exposure - y), exposure)
    return FILM_GAMMA * np.log1p(np.maximum(exposure, 0) / TOE) / np.log(10)


def paper_reflectance(density, matrix=DYE):
    # Density attenuates the enlarger: log10(T) = -D. Relative paper
    # exposure is modeled as a weighted geometric mixture of transmissions.
    # Subtract and normalize the paper's zero-scene reflectance analytically.
    log_paper_exposure = -(density @ matrix.T) * np.log(10)
    log_response = PAPER_GAMMA * log_paper_exposure
    return -np.expm1(log_response) / (1 + PAPER_PIVOT * np.exp(log_response))


def output_gamut(rgb):
    # Exact RGB-cube intersection along the constant-Y ray from neutral.
    # Below the knee the color is unchanged; beyond it compress with C1 slope.
    y = np.clip(rgb @ SRGB[1], 0, 1)[..., None]
    delta = rgb - y
    low = np.maximum(-delta.min(axis=-1, keepdims=True), 0)
    high = np.maximum(delta.max(axis=-1, keepdims=True), 0)
    q = np.maximum(
        np.divide(low, y, out=np.zeros_like(y), where=y > 0),
        np.divide(high, 1 - y, out=np.zeros_like(y), where=y < 1),
    )
    # Algebraically equivalent to compressing relative chroma with a rational knee.
    t = np.maximum(q - KNEE, 0)
    mapped = KNEE + (1 - KNEE) * t / (1 - KNEE + t)
    scale = np.divide(mapped, q, out=np.ones_like(q), where=q > KNEE)
    return np.where((y > 0) & (y < 1), y + scale * delta, y)


def render(rgb):
    linear = output_gamut(paper_reflectance(film_density(rgb)) @ TO_SRGB.T)
    linear = np.clip(linear, 0, 1)  # numerical guard after analytic gamut mapping
    return np.where(linear <= .0031308, 12.92 * linear, 1.055 * linear ** (1 / 2.4) - .055)


def self_test():
    np.testing.assert_allclose(FILM.sum(axis=1), 1, atol=1e-15, rtol=0)
    np.testing.assert_allclose(DYE.sum(axis=1), 1, atol=1e-15, rtol=0)
    assert PAPER_PIVOT > 0
    values = np.r_[0, np.geomspace(1e-8, 128, 1024)]
    gray = np.repeat(values[:, None], 3, axis=-1)
    rgb = render(gray)
    np.testing.assert_allclose(rgb, np.repeat(rgb[:, :1], 3, axis=-1), atol=1e-14, rtol=0)
    assert np.all(np.diff(rgb[:, 0]) > 0) and np.all(rgb[0] == 0)
    np.testing.assert_allclose(paper_reflectance(film_density([[GRAY]*3])), GRAY, atol=1e-14, rtol=0)
    # Check gamut bounds before encoding (without relying on the final clip).
    rng = np.random.default_rng(149)
    source = rng.uniform(-.3, 2.0, (33, 41, 3)) * np.geomspace(.001, 64, 33)[:, None, None]
    work = paper_reflectance(film_density(source)) @ TO_SRGB.T
    mapped = output_gamut(work)
    assert np.isfinite(mapped).all() and mapped.min() >= -1e-14 and mapped.max() <= 1 + 1e-14
    np.testing.assert_allclose(mapped @ SRGB[1], work @ SRGB[1], atol=1e-14, rtol=0)
    np.testing.assert_allclose(render(source), np.concatenate([render(source[i:i+7])
                               for i in range(0, len(source), 7)]), atol=1e-14, rtol=0)
    print('PASS: anchor, neutral ramp, gamut bounds/luminance, stripe invariance')


def sha(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('d0', type=Path, nargs='?', help='base_engine_input JSON sidecar')
    parser.add_argument('out', type=Path, nargs='?', help='new TIFF path')
    parser.add_argument('--rows', type=int, default=64)
    args = parser.parse_args()
    self_test()
    if args.d0 is None and args.out is None:
        return
    if not (args.d0 and args.out and args.rows > 0 and args.out.suffix.lower() == '.tif'):
        parser.error('requires D0, a new .tif path, and positive row count')
    from PIL import ImageCms
    import tifffile

    meta = json.loads(args.d0.read_text())
    w, h = meta['w'], meta['h']
    expected = dict(components=4, stride_bytes=16*w, type='float32_le',
                    orientation='gl_bottom_up', space='scene-linear Rec.2020 D65',
                    stage='base_engine_input', complete=True, alpha=1)
    raw = args.d0.with_suffix('.rgba32f')
    if not (w > 0 and h > 0 and raw.stat().st_size == 16*w*h
            and all(meta.get(k) == v for k, v in expected.items())):
        parser.error('invalid D0 contract')
    if args.out.exists() or args.out.with_suffix('.json').exists():
        parser.error('output already exists')
    image = np.memmap(raw, dtype='<f4', mode='r', shape=(h, w, 4))[::-1]
    icc = ImageCms.ImageCmsProfile(ImageCms.createProfile('sRGB')).tobytes()
    digest = hashlib.sha256()
    total = 0.0

    def strips():
        nonlocal total
        for i in range(0, h, args.rows):
            block = image[i:i+args.rows]
            if not np.isfinite(block).all() or not np.all(block[..., 3] == 1):
                raise ValueError(f'invalid pixel at rows starting {i}')
            tick = time.perf_counter()
            rgb = render(block[..., :3])
            total += time.perf_counter() - tick
            data = np.rint(rgb * 65535).astype('<u2').tobytes()
            digest.update(data)
            yield data

    try:
        tifffile.imwrite(args.out, strips(), shape=(h, w, 3), dtype='<u2', byteorder='<',
                         photometric='rgb', rowsperstrip=args.rows, metadata=None, iccprofile=icc)
        check = hashlib.sha256()
        with tifffile.TiffFile(args.out) as f:
            page = f.pages[0]
            assert page.shape == (h, w, 3) and page.dtype == np.uint16
            assert page.tags[34675].value == icc
            for pixels, _, _ in page.segments():
                check.update(pixels.astype('<u2', copy=False).tobytes())
        assert check.digest() == digest.digest()
    except BaseException:
        args.out.unlink(missing_ok=True)
        raise
    report = dict(input_sha256=sha(raw), engine_sha256=sha(Path(__file__)),
                  output_sha256=sha(args.out), pixel_sha256=digest.hexdigest(),
                  shape=[h, w, 3], rows=args.rows, render_seconds=total,
                  film=FILM.tolist(), dye=DYE.tolist(), toe=TOE, film_gamma=FILM_GAMMA,
                  paper_gamma=PAPER_GAMMA, gray=GRAY, gamut_knee=KNEE,
                  verified_uint16_values=h*w*3)
    args.out.with_suffix('.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
