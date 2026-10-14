#!/usr/bin/env python3
"""阶段 2：引擎 1（sigmoid）GLSL 实现的独立数值验证 + 两引擎高光端对照（**ramp 口径**）。

为什么需要这个脚本（两个洞一起补）：
  ① 默认 ROI（1800,2100,600,1000）**不含高光**（引擎 0 最大仅 174 LSB）⇒ 成片域的
     "高光端层次"检查在真实 ROI 上做不了，必须用可控输入补。
  ② 卡 B 的 `kFsSigmoidDef` 是手写/移植的 GLSL，`tools/sigmoid-params-ref.py` 是卡 A 的
     参考实现。两者若不一致，真机成片就与判据①②③ 的纯曲线结论不符，而这种偏差在
     图像统计里会被场景内容掩盖 ⇒ 用**灰阶 ramp** 逐值比对。

做法（**不重写任何公式，也不重写组装逻辑**）：
  · 构造合成 D4：`R=G=B=s=0.1845·2^EV`（EV 网格命令行给定），存成 `<out>/ccm_d4.npy`；
  · 直接调 `engine-shader-offline.py` 的 `film_stage()`（= `--engine 0/1` 的同一条路径，
    uniform 构造与 shader 组装零重复）⇒ `film_d5.npy` / `film_d5_sigmoid.npy`；
  · 引擎 1 的输出与 `sigmoid_curve()` 逐值比对（灰阶输入下 hue-preservation 段是恒等，
    见报告 §5 推导 ⇒ 这个比对直接检验曲线实现）；
  · 两引擎在同一 ramp 上的显示域 LSB 曲线 → 死白起点 / 层次对照。

⚠ 口径：ramp 走的是**成片域**（shader 实跑，含公共中段/外观层），但灰阶输入不触发
  gamut 与外观层 ⇒ 它的 LSB 数值与 `tools/sigmoid-criteria-scan.py` 的**纯曲线**口径
  在引擎 1 上应当一致（这就是本脚本的判据之一），在引擎 0 上**不一致**
  （离线链默认 filmic 参数 ≠ 判据① 的 17 号 R-2 M9 口径）—— 报告里分开写。

用法：python3 tools/stage2-sigmoid-glsl-check.py --out /tmp/stage2-ramp
"""
import argparse
import importlib.util
import json
import os

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
GREY = 0.1845


def _load(fname, modname):
    spec = importlib.util.spec_from_file_location(modname, os.path.join(HERE, fname))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


eso = _load('engine-shader-offline.py', 'engine_shader_offline')
sig = _load('sigmoid-params-ref.py', 'sigref')


def _lsb(y):
    return sig.srgb_oetf_8bit(np.asarray(y, dtype=np.float64))


def _span(ev, lsb, start_ev=2.30, step=0.1, min_lsb=1.0):
    """从 start_ev 起，每 step EV 输出差 ≥min_lsb 能持续多远（单位 EV）—— 判据① 同口径。"""
    de = ev[1] - ev[0]
    i0 = int(round((start_ev - ev[0]) / de))
    n = int(round(step / de))
    k = i0
    while k + n < len(lsb) and (lsb[k + n] - lsb[k]) >= min_lsb:
        k += n
    return float(ev[k] - ev[i0])


def _flat_from(ev, y):
    """首次"输出不再增长"的 EV（引擎 0 的硬平顶；float32 严格相等判定）。"""
    for i in range(len(y) - 1):
        if y[i + 1] == y[i]:
            return float(ev[i + 1])
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default='/tmp/stage2-ramp')
    ap.add_argument('--ev-lo', type=float, default=-6.0)
    ap.add_argument('--ev-hi', type=float, default=8.0)
    ap.add_argument('--ev-step', type=float, default=0.02)
    ap.add_argument('--film-white-ev', type=float, default=None,
                    help='覆盖离线链默认的 FilmParams[38]（默认 4.56）；'
                         '给 4.0 可与架构师的端到端 214.20 LSB 口径交叉核对')
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    ev = np.arange(a.ev_lo, a.ev_hi + a.ev_step * 0.5, a.ev_step)
    s = (GREY * np.power(2.0, ev)).astype(np.float32)
    # 输入纹理是 RGBA16F ⇒ 输入本身先被量化到 float16（参考值也必须用同一量化值）
    s16 = s.astype(np.float16).astype(np.float32)
    img = np.zeros((1, len(ev), 4), np.float32)
    img[0, :, :3] = s16[:, None]
    img[0, :, 3] = 1.0
    np.save(os.path.join(a.out, 'ccm_d4.npy'), img)

    args = eso._film_cli([])          # 全部默认值（与离线链默认逐项相同）
    args.out = a.out
    if a.film_white_ev is not None:
        args.film_white_ev = a.film_white_ev

    args.engine = 1
    eso.film_stage(args)
    # fbo 是 (w,h)=(1,N) ⇒ 读回 shape (N,1,4)；展平后 y 升序 = EV 升序（下方有方向自检）
    y1 = np.load(os.path.join(a.out, 'film_d5_sigmoid.npy')).reshape(-1, 4)[:, 0].astype(np.float64)
    args.engine = 0
    eso.film_stage(args)
    y0 = np.load(os.path.join(a.out, 'film_d5.npy')).reshape(-1, 4)[:, 0].astype(np.float64)
    if not (y1[-1] > y1[0] and y0[-1] > y0[0]):
        raise SystemExit('ramp 方向自检失败（输出非递增）⇒ 行序/展平口径不对，勿信本脚本数字')

    l0, l1 = _lsb(y0), _lsb(y1)
    ref = sig.compute_sigmoid_params(args.sig_contrast, args.sig_skew, args.sig_white,
                                     args.sig_black, args.sig_hue)
    yref = np.asarray(sig.sigmoid_curve(s16, ref), dtype=np.float64)

    d = np.abs(y1 - yref)
    ulp = np.abs(np.spacing(yref.astype(np.float16)).astype(np.float64))
    worst = int(np.argmax(d))
    rep = {
        'ramp': {'ev': [float(ev[0]), float(ev[-1]), float(ev[1] - ev[0])], 'n': int(len(ev)),
                 'input': 'R=G=B=0.1845·2^EV（RGBA16F 量化后）'},
        'glsl_vs_ref': {
            'note': '引擎 1 shader 实跑输出 vs tools/sigmoid-params-ref.py（float32 复刻）',
            'max_abs_linear': float(d.max()),
            'max_abs_linear_at_ev': float(ev[worst]),
            'max_ulp_f16': float((d / ulp).max()),
            'max_abs_lsb': float(np.abs(l1 - _lsb(yref)).max()),
            'n_over_1ulp_f16': int(np.sum(d > ulp)),
            'n': int(len(ev)),
        },
        'engine1_vs_pure_curve': {
            'note': 'ramp（shader 实跑，灰阶不触发 gamut/外观层）vs 纯曲线参考 —— 应当一致',
            'max_abs_lsb': float(np.abs(l1 - _lsb(yref)).max()),
        },
        'highlight_behaviour': {
            'engine0_first_flat_ev': _flat_from(ev, y0),
            'engine1_first_flat_ev': _flat_from(ev, y1),
            'lsb_at_ev': {('%.2f' % e): {'engine0': float(l0[int(round((e - ev[0]) / (ev[1] - ev[0])))]),
                                         'engine1': float(l1[int(round((e - ev[0]) / (ev[1] - ev[0])))])}
                          for e in (0.0, 2.3, 3.0, 4.0, 5.0, 6.0, 8.0)},
            'span_from_white_ev': {'engine0': _span(ev, l0), 'engine1': _span(ev, l1)},
        },
        'cli_defaults': {'film_black_ev': args.film_black_ev, 'film_white_ev': args.film_white_ev,
                         'film_contrast': args.film_contrast, 'film_lat_sh': args.film_lat_sh,
                         'sig': {'contrast': args.sig_contrast, 'skew': args.sig_skew,
                                 'white': args.sig_white, 'black': args.sig_black,
                                 'hue': args.sig_hue}},
        'sigmoid_params': {k: float(v) for k, v in ref.items()},
    }
    json.dump(rep, open(os.path.join(a.out, 'ramp.json'), 'w'), indent=2, ensure_ascii=False)
    print(json.dumps(rep, indent=2, ensure_ascii=False))


if __name__ == '__main__':
    main()
