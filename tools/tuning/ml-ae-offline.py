#!/usr/bin/env python3
"""PhotonCamera ML-AE 离线验证：DNG → 64x64x9 输入 → short/long/portrait 三个 TFLite → ideal gain。

口径逐条对照 PhotonCamera/RawSceneExposureEstimator.kt：
  - 输入 [1,64,64,9] = [ln R, ln G, ln B, ln(scene), face, 0, 0, saliency=0.05, 0]
  - RGB 先 U15 量化（floor(v*32767+0.5)/32768）再取 ln(v + 1e-6)
  - logScene = ln(geoSignal / normalizedExposure + 1e-4)
    normalizedExposure = (17.61/14.6) * (tetMs/1000), tetMs = exposureMs * iso/100
  - 输出 logGain，gain = exp(logGain) - 1e-6，idealTet = currentTet * gain

模型来自 PhotonCamera 的 assets/mgc_ae/（Google MGC 资产，商用许可需自行确认）。
下载到 <model-dir> 后运行：

  python3 tools/tuning/ml-ae-offline.py <dng> [--model-dir DIR] [--json]

用法:
  python3 tools/tuning/ml-ae-offline.py test_temp/FILMKIT_20261103-152558.dng
"""
import argparse
import json
import os
import struct
import sys

import numpy as np

SIGNAL_FLOOR = 10 / 32768
U15_MAX = 32767
U15_SCALE = 32768
RGB_LOG_FLOOR = 1e-6
MISSING_SALIENCY = 0.05
SENSOR_SENSITIVITY = 17.61
SCENE_BRIGHTNESS_CAL = 14.6
INPUT = 64

TYPES = {1: ('B', 1), 2: ('c', 1), 3: ('H', 2), 4: ('I', 4), 5: ('II', 8),
         6: ('b', 1), 7: ('B', 1), 8: ('h', 2), 9: ('i', 4), 10: ('ii', 8),
         11: ('f', 4), 12: ('d', 8)}


def read_dng_tags(path):
    d = open(path, 'rb').read()
    bo = '<' if d[:2] == b'II' else '>'
    off = struct.unpack(bo + 'I', d[4:8])[0]
    n = struct.unpack(bo + 'H', d[off:off + 2])[0]
    tags = {}
    for i in range(n):
        e = off + 2 + i * 12
        tag, typ, cnt = struct.unpack(bo + 'HHI', d[e:e + 8])
        fmt, sz = TYPES.get(typ, ('B', 1))
        total = cnt * sz
        raw = d[e + 8:e + 8 + total] if total <= 4 else d[struct.unpack(bo + 'I', d[e + 8:e + 12])[0]:][:total]
        if fmt == 'c':
            val = raw.split(b'\0')[0].decode('latin-1')
        elif fmt in ('II', 'ii'):
            pair = 'II' if fmt == 'II' else 'ii'
            pairs = [struct.unpack(bo + pair, raw[k * 8:k * 8 + 8]) for k in range(cnt)]
            val = [v[0] / v[1] if v[1] else 0.0 for v in pairs]
        else:
            val = list(struct.unpack(bo + fmt * cnt, raw[:total]))
        tags[tag] = val
    return tags


def load_linear_rgb(path, size=INPUT, awb=True):
    """DNG → 64x64 线性 camera RGB（0..1，已做 AWB，未做 CCM）。"""
    import rawpy

    tags = read_dng_tags(path)
    exposure_ms = tags[0x829A][0] * 1000.0
    iso = tags[0x8827][0] if isinstance(tags[0x8827], list) else tags[0x8827]
    with rawpy.imread(path) as raw:
        img = raw.raw_image_visible.astype(np.float32)
        black = np.array(raw.black_level_per_channel, dtype=np.float32)
        wl = float(raw.white_level)
        wb = np.array(raw.camera_whitebalance[:3], dtype=np.float32)
    h, w = img.shape
    h2, w2 = h // 2, w // 2
    # DNG pattern [[1,0],[2,3]] => (0,0)=G (0,1)=R (1,0)=B (1,1)=G
    G1 = img[0:2 * h2:2, 0:2 * w2:2]
    R = img[0:2 * h2:2, 1:2 * w2:2]
    B = img[1:2 * h2:2, 0:2 * w2:2]
    G2 = img[1:2 * h2:2, 1:2 * w2:2]
    R = (R - black[0]) / (wl - black[0])
    B = (B - black[2]) / (wl - black[2])
    G1 = (G1 - black[1]) / (wl - black[1])
    G2 = (G2 - black[3]) / (wl - black[3])
    rgb = np.stack([R, 0.5 * (G1 + G2), B], axis=-1).clip(0, 1)
    fy, fx = rgb.shape[0] // size, rgb.shape[1] // size
    rgb = rgb[:fy * size, :fx * size].reshape(size, fy, size, fx, 3).mean(axis=(1, 3))
    if awb:
        wb = wb / wb[1]
        rgb = (rgb * wb).clip(0, 1)
    return rgb, exposure_ms, iso


def build_inputs(rgb, exposure_ms, iso, log_scene_override=None, saliency=MISSING_SALIENCY):
    geo = np.exp(np.mean(np.log(np.maximum(rgb, 0.0) + SIGNAL_FLOOR))) - SIGNAL_FLOOR
    tet_ms = exposure_ms * (iso / 100.0)
    norm_exp = (SENSOR_SENSITIVITY / SCENE_BRIGHTNESS_CAL) * (tet_ms / 1000.0)
    log_scene = np.log(geo / norm_exp + 1e-4) if log_scene_override is None else log_scene_override

    q = np.floor(np.clip(rgb, 0, 1) * U15_MAX + 0.5).clip(0, U15_MAX) / U15_SCALE
    log_rgb = np.log(np.maximum(q, 0.0) + RGB_LOG_FLOOR)

    combined = np.zeros((1, INPUT, INPUT, 9), np.float32)
    combined[0, :, :, 0:3] = log_rgb
    combined[0, :, :, 3] = log_scene
    combined[0, :, :, 7] = saliency

    color = np.zeros((1, INPUT, INPUT, 4), np.float32)
    color[0, :, :, 0:3] = log_rgb
    color[0, :, :, 3] = log_scene

    semantic = np.zeros((1, INPUT, INPUT, 5), np.float32)
    semantic[0, :, :, 3] = saliency
    return combined, color, semantic, dict(geo=float(geo), tet_ms=float(tet_ms),
                                           log_scene=float(log_scene))


def run_models(combined, color, semantic, model_dir):
    import tensorflow as tf

    out = {}
    for name in ('short', 'long', 'portrait'):
        path = os.path.join(model_dir, f'{name}_scene_exposure.tflite')
        it = tf.lite.Interpreter(model_path=path)
        it.allocate_tensors()
        if name == 'long':
            for d in it.get_input_details():
                it.set_tensor(d['index'], color if d['shape'][-1] == 4 else semantic)
        else:
            it.set_tensor(it.get_input_details()[0]['index'], combined)
        it.invoke()
        log_gain = float(it.get_tensor(it.get_output_details()[0]['index']).flatten()[0])
        gain = float(np.exp(log_gain) - 1e-6)
        out[name] = dict(log_gain=log_gain, gain=gain, ev=float(np.log2(gain)))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('dng', nargs='+')
    ap.add_argument('--model-dir', default=os.environ.get('FILMKIT_ML_AE_DIR',
                                                         os.path.join(os.path.dirname(__file__), 'mgc_ae')))
    ap.add_argument('--json', action='store_true')
    args = ap.parse_args()

    results = []
    for path in args.dng:
        rgb, exposure_ms, iso = load_linear_rgb(path)
        combined, color, semantic, dbg = build_inputs(rgb, exposure_ms, iso)
        out = run_models(combined, color, semantic, args.model_dir)
        row = dict(file=os.path.basename(path), exposure_ms=exposure_ms, iso=iso, **dbg, **out)
        results.append(row)
        if not args.json:
            print(f'\n=== {row["file"]}')
            print(f'  exposure={exposure_ms:.3f}ms iso={iso} tet={dbg["tet_ms"]:.4f}ms')
            print(f'  geoSignal={dbg["geo"]:.6f} logSceneBrightness={dbg["log_scene"]:.4f}')
            for k in ('short', 'long', 'portrait'):
                r = out[k]
                print(f'  {k:8s} logGain={r["log_gain"]:+.4f} gain={r["gain"]:.3f}x EV={r["ev"]:+.3f}')
    if args.json:
        print(json.dumps(results, indent=2))


if __name__ == '__main__':
    sys.exit(main())
