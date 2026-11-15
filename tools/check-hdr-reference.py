#!/usr/bin/env python3
"""Independent PC HDR curve oracle and continuity/reconstruction regressions.

Uses the existing Filmic spline reference only to supply synthetic neutral curves.
Does not certify camera/display behavior; checks the independent CPU oracle only.
"""
import importlib.util
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parents[1]


def solve_pc(samples):
    """RawHdrReferenceMath.solve: half-float-aware join and quadratic fallback."""
    curve = np.maximum.accumulate(np.nan_to_num(np.asarray(samples, dtype=float), nan=0.0))
    curve = np.maximum(curve, 0.0)
    last = len(curve) - 1
    start = int(np.ceil((20000 / 65535) * last))
    span = max(1, int((20 / 65535) * last))
    white = max(curve[-1], 1e-4)

    def uncertainty(v):
        # Kotlin Math.ulp(Float) * 4096: 1/2 half-float step, minimum subnormal.
        v32 = np.float32(v)
        ulp = float(np.nextafter(v32, np.float32(np.inf)) - v32)
        return max(ulp * 4096, 1 / 33554432)

    for i in range(start, last):
        lo = max(0, i - span)
        x, lx = i / last, lo / last
        width = max(x - lx, 1e-4)
        slope = (curve[i] - curve[lo]) / width
        conservative = (curve[i] - curve[lo] - uncertainty(curve[i]) - uncertainty(curve[lo])) / width
        tangent = curve[i] - uncertainty(curve[i]) + conservative * (1 - x)
        q = (white * np.sqrt(2) - curve[i] - slope * (1 - x)) / max((1 - x) ** 2, 1e-8)
        ws = slope + 2 * q * (1 - x)
        if slope >= 0 and tangent >= white and np.isfinite(ws) and ws >= 0:
            return curve, (x, curve[i], slope, q, white * np.sqrt(2), ws, 0)
    x = start / last
    q = (white * np.sqrt(2) - curve[-1]) / (1 - x) ** 2
    return curve, (x, curve[start], (curve[start] - curve[start - 1]) * last,
                   q, white * np.sqrt(2), (curve[-1] - curve[-2]) * last + 2 * q * (1 - x), 1)


def evaluate(curve, ext, inputs):
    x = np.maximum(np.asarray(inputs, dtype=float), 0)
    join, out, slope, q, white, ws, fallback = ext
    base = np.interp(np.minimum(x, 1), np.linspace(0, 1, len(curve)), curve)
    d = x - join
    middle = (base if fallback else out + slope * d) + q * d * d
    return np.where(x <= join, base, np.where(x <= 1, middle, white + ws * (x - 1)))


def selfcheck():
    spec = importlib.util.spec_from_file_location('spline', ROOT / 'tools/filmic-spline-ref.py')
    spline = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(spline)
    x = np.linspace(0, 1, 1024)
    sp = spline.compute_filmic_spline(-7.65, 4.56, 1.0, [0.01] * 3, [0.01] * 3)
    e = np.clip((np.log2(np.maximum(x, 2 ** -16) / .1845) - sp['black_ev']) / sp['dynamic_range'], 0, 1)
    filmic = np.array([max(0, min(1, spline.spline_at(sp, v))) ** sp['output_power'] for v in e])
    for name, samples in [('filmic', filmic), ('convex', x * x), ('linear', x), ('flat', x * 0)]:
        c, ext = solve_pc(samples.astype(np.float16))
        dense = np.linspace(0, 8, 65537)
        out = evaluate(c, ext, dense)
        assert np.all(np.isfinite(out)), name
        assert np.min(np.diff(out)) >= -1e-10, (name, 'non-monotonic')
        assert abs(float(evaluate(c, ext, 1)) - ext[4]) < 1e-10
        j = ext[0]
        assert abs(float(evaluate(c, ext, j + 1e-7)) - ext[1]) < 1e-5, name
        # Continuous first derivative at white for both quadratic branches.
        h = 1e-5
        left = (float(evaluate(c, ext, 1)) - float(evaluate(c, ext, 1 - h))) / h
        right = (float(evaluate(c, ext, 1 + h)) - float(evaluate(c, ext, 1))) / h
        assert abs(left - right) < 1e-3, (name, 'white slope')
        print(f'{name}: join={j:.6f} white={ext[4]:.6f} fallback={ext[-1]} monotonic/C0/C1 PASS')
    # A compressed SDR plateau can correctly reconstruct a smooth HDR gradient.
    target = np.linspace(1, 3.5, 4096)
    base = np.full(target.shape, .92)
    gain = target / base
    encoded = np.rint((np.log2(gain) + 2) / 4 * 255) / 255
    reconstructed = base * 2 ** (-2 + encoded * 4)
    error = np.max(abs(reconstructed / target - 1))
    assert error < .006
    assert np.min(np.diff(reconstructed)) >= 0
    print(f'SDR plateau: reconstructed monotonic, max relative error={error:.4%} PASS')
    c, ext = solve_pc(filmic.astype(np.float16))
    points = np.array([.18, .5, 1, 2, 4])
    base = np.interp(np.minimum(points, 1), x, c)
    ratios = evaluate(c, ext, points) / np.maximum(base, 1e-6)
    print('PC coordinate ratio:', dict(zip(points.tolist(), ratios.round(4).tolist())))


def gpu_check():
    """Execute the production Film shader for neutral probes and prepared pixels."""
    def load(name):
        spec = importlib.util.spec_from_file_location(name.replace('-', '_'), ROOT / 'tools' / (name + '.py'))
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        return mod
    offline, ref = load('engine-shader-offline'), load('filmic-spline-ref')
    shaders = offline.extract_shaders()
    gl = offline.GL(1024, 16)
    sp = ref.compute_filmic_spline(-7.65, 4.56, 1., [.01] * 3, [.01] * 3)
    uniforms = {}
    for name, seg in [('uFilmicToeM', 0), ('uFilmicShM', 1)]:
        for i in range(1, 6):
            uniforms[f'{name}{i}'] = tuple(float(sp[f'm{i}'][seg][ch]) for ch in range(3))
    for i in [1, 2]:
        uniforms[f'uFilmicLinM{i}'] = tuple(float(sp[f'm{i}'][2][ch]) for ch in range(3))
    for name, key in [('LatMin', 'lat_min'), ('LatMax', 'lat_max')]:
        uniforms['uFilmic' + name] = tuple(sp[key])
    for name, key in [('BlackEv', 'black_ev'), ('Dr', 'dynamic_range'), ('InputMin', 'input_min'),
                      ('InputMax', 'input_max'), ('Power', 'output_power'), ('BlackDisp', 'black_disp')]:
        uniforms['uFilmic' + name] = sp[key]
    uniforms.update(uExposure=1., uGamut=1., uHalDomainMap=0., uDomainRatio=1.,
                    uSat=(1., 1., 1.), uToneK=1., uLook=0., uLocalStrength=0.)
    pixels = np.ones((16, 1024, 4), np.float32)
    pixels[:, :, :3] = np.linspace(0, 8, 1024)[None, :, None]
    tex = gl.ctx.texture((1024, 16), 4, pixels.astype('f2').tobytes(), dtype='f2')
    tex.filter = (offline.moderngl.NEAREST,) * 2
    local = gl.ctx.texture((1, 1), 4, dtype='f2')
    source = shaders['kFsFilm']
    textures = {'uTex': tex, 'uLocal': local}
    actual = gl.run(source, uniforms, textures)
    old = Path('/tmp/hdr-sdr-before.npy')
    if old.exists():
        baseline = np.load(old)
        assert np.array_equal(actual, baseline), 'SDR GPU baseline changed'
        print('SDR actual GPU baseline: bit-identical PASS')
    else:
        print('SDR baseline unavailable: comparison SKIPPED')
    if 'uHdrProbeMode' not in source:
        raise AssertionError('Production GPU probe not implemented')
    prepared = gl.run(source, dict(uniforms, uHdrProbeMode=2), textures)
    np.testing.assert_array_equal(prepared[:, :, :3], pixels.astype('f2').astype('f4')[:, :, :3])
    for ev in [-2, 0, 2]:
        exposed = gl.run(source, dict(uniforms, uHdrProbeMode=2, uExposure=2. ** ev), textures)
        expected = (pixels[:, :, :3].astype('f2').astype('f4') * 2. ** ev).astype('f2').astype('f4')
        np.testing.assert_array_equal(exposed[:, :, :3], expected)
    probe = gl.run(source, dict(uniforms, uHdrProbeMode=1), textures, size=(1024, 1))
    luma = np.sum(probe[0, :, :3] * np.array([.2627, .6780, .0593]), axis=1)
    curve, ext = solve_pc(luma)
    assert curve[-1] > .1, 'GPU probe returned no engine response'
    assert np.max(abs(probe[0, :, 0] - probe[0, :, 1])) < .01
    print(f'Filmic GPU probe: white={curve[-1]:.6f} join={ext[0]:.6f} PASS')
    # Execute production gainmap shader, including its spatial reduction and dither.
    lut = np.ones((1, 1024, 4), np.float32)
    lut[0, :, :3] = curve[:, None]
    curve_tex = gl.ctx.texture((1024, 1), 4, lut.astype('f2').tobytes(), dtype='f2')
    curve_tex.filter = (offline.moderngl.NEAREST,) * 2
    sdr = np.full((16, 1024, 4), .8, np.float32)
    sdr[:, :, 3] = 1
    sdr_tex = gl.ctx.texture((1024, 16), 4, sdr.astype('f2').tobytes(), dtype='f2')
    for t in [tex, sdr_tex, curve_tex]:
        t.repeat_x = t.repeat_y = False
        t.filter = (offline.moderngl.NEAREST,) * 2
    names = ['JoinInput', 'JoinOutput', 'JoinSlope', 'Quadratic', 'WhiteOutput', 'WhiteSlope', 'ExtendsBase']
    gu = {'uHdrCurve' + name: (int(v) if i == 6 else float(v)) for i, (name, v) in enumerate(zip(names, ext))}
    gu['uSrcSize'] = (1024., 16.)
    encoded = gl.run(shaders['kFsGainmap'], gu,
                     {'uHdrBaseCurveTexture': curve_tex, 'uScene': tex, 'uSdr': sdr_tex}, size=(512, 8))
    decoded = 2 ** (encoded[:, :, 0] * 4 - 2)
    values = pixels[0, :, 0].astype('f2').astype(float)
    x = np.linspace(0, 1, 1024)
    c16 = curve.astype('f2').astype(float)
    ratios = np.maximum(evaluate(c16, ext, values), np.interp(np.minimum(values, 1), x, c16)) / np.maximum(np.interp(np.minimum(values, 1), x, c16), 1e-6)
    expected = np.array([np.mean(ratios[np.clip(np.arange(2 * i - 1, 2 * i + 3), 0, 1023)]) for i in range(512)])
    sdr_linear = ((float(np.float16(.8)) + .055) / 1.055) ** 2.4
    expected = np.clip((sdr_linear * expected + 1e-4) / (sdr_linear + 1e-4), 1, 4)
    error = float(np.max(abs(decoded / expected[None, :] - 1)))
    assert error < .014, ('production gainmap mismatch', error)
    assert float(np.quantile(decoded, .9)) > 2., 'HDR extension silently disabled'
    print(f'Production gainmap GPU vs independent curve oracle: max relative error={error:.4%} PASS')
    print('Prepared RGB matches same source at EV=-2/0/+2 PASS')
    # Asymmetric horizontal/vertical highlight edges catch accidental Y flips and
    # prevent a uniform gray ramp from hiding a wrong spatial correspondence.
    edge = np.ones((16, 1024, 4), np.float32)
    edge[:, :, :3] = .18
    edge[2:7, 256:768, :3] = 2.
    tex.write(edge.astype('f2').tobytes())
    edge_gain = gl.run(shaders['kFsGainmap'], gu,
                       {'uHdrBaseCurveTexture': curve_tex, 'uScene': tex, 'uSdr': sdr_tex}, size=(512, 8))
    edge_ratio = 2 ** (edge_gain[:, :, 0] * 4 - 2)
    assert float(edge_ratio[5:7, 150:350].mean()) > 2., 'Y-flipped highlight missing'
    assert float(edge_ratio[:3, 150:350].max()) < 1.03, 'highlight gain displaced to opposite side'
    assert float(edge_ratio[:, :120].max()) < 1.03, 'gain leaks far outside highlight edge'
    assert float(edge_ratio[:, 390:].max()) < 1.03, 'gain leaks beyond spatial reduction footprint'
    print('Asymmetric high-contrast edge: orientation/localization PASS')
    tex.write(pixels.astype('f2').tobytes())
    sig = load('sigmoid-params-ref').compute_sigmoid_params(1.5, 0., 100., .0152, 100.)
    for name, key in [('WhiteTarget', 'white_target'), ('BlackTarget', 'black_target'),
                      ('PaperExposure', 'paper_exposure'), ('FilmFog', 'film_fog'),
                      ('FilmPower', 'film_power'), ('PaperPower', 'paper_power'),
                      ('HuePreservation', 'hue_preservation')]:
        uniforms['uSig' + name] = float(sig[key])
    blocks, _ = offline.anchor_film_blocks()
    head, _, mid, _, tail = blocks
    sigmoid_source = head + shaders['kFsSigmoidDef'] + mid + shaders['kFsSigmoidMain'] + tail
    sig_probe = gl.run(sigmoid_source, dict(uniforms, uHdrProbeMode=1), textures, size=(1024, 1))
    sig_curve, sig_ext = solve_pc(np.sum(sig_probe[0, :, :3] * np.array([.2627, .6780, .0593]), axis=1))
    assert np.max(abs(sig_curve - curve)) > .01, 'engine selection did not change measured HDR curve'
    assert sig_curve[-1] > .1
    print(f'Sigmoid GPU probe: white={sig_curve[-1]:.6f} join={sig_ext[0]:.6f} PASS')
    gl.close()


if __name__ == '__main__':
    import sys
    selfcheck()
    if '--gpu' in sys.argv:
        gpu_check()
