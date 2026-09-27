#!/usr/bin/env python3
"""filmic 离线验证的可视化：曲线图 + 画面对比图。

用法: python3 tools/tuning/filmic_preview.py <dng> [PC成片.jpg]
输出: tools/tuning/filmic_curve.png / tools/tuning/filmic_frames.png
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, "tools/tuning")
from filmic_offline import (GREY, apply_filmic, auto_ev, compute_spline,  # noqa: E402
                            encode, load_raw, rolloff)

LUM = np.array([0.2126, 0.7152, 0.0722])
W, H = 820, 520
EV_LO, EV_HI = -13.0, 4.0


def curve_png(out3, path):
    ev_b, ev_w = auto_ev(out3, 0.5, 99.5)
    plans = [
        ("filmic PC params (-7.65/+4.56)", (220, 120, 40)),
        ("filmic auto EV (%.2f/%.2f)" % (ev_b, ev_w), (30, 150, 90)),
    ]

    sp_pc = compute_spline(-7.65, 4.56)
    sp_auto = compute_spline(ev_b, ev_w)
    fns = [lambda x: encode(apply_filmic(np.stack([x, x, x], -1), sp_pc)[..., 0]),
           lambda x: encode(apply_filmic(np.stack([x, x, x], -1), sp_auto)[..., 0])]

    im = Image.new("RGB", (W, H), (250, 250, 248))
    d = ImageDraw.Draw(im)
    px = lambda ev: (ev - EV_LO) / (EV_HI - EV_LO) * (W - 60) + 50
    py = lambda v: H - 40 - v / 255.0 * (H - 80)

    for ev in range(-12, 5, 2):
        d.line([(px(ev), py(0)), (px(ev), py(255))], fill=(226, 226, 222))
        d.text((px(ev) - 8, H - 32), f"{ev:+d}", fill=(120, 120, 120))
    for v in range(0, 256, 51):
        d.line([(px(EV_LO), py(v)), (px(EV_HI), py(v))], fill=(226, 226, 222))
        d.text((8, py(v) - 5), f"{v:3d}", fill=(120, 120, 120))

    # 参考竖线：桌面 RAW 0.028（-2.7EV 相对中灰）、中灰 0.18
    for x_lin, label in ((0.028, "桌面 0.028"), (GREY, "中灰 0.18")):
        ev = np.log2(x_lin / GREY)
        d.line([(px(ev), py(0)), (px(ev), py(255))], fill=(200, 60, 60))
        d.text((px(ev) + 3, py(255) + 2), label, fill=(200, 60, 60))

    evs = np.linspace(EV_LO, EV_HI, 400)
    xs = GREY * 2.0 ** evs
    for (label, color), fn in zip(plans, fns):
        ys = fn(xs)
        d.line([(px(e), py(v)) for e, v in zip(evs, ys)], fill=color, width=3)
    for i, (label, color) in enumerate(plans):
        d.text((60, 14 + i * 16), "■ " + label, fill=color)
    d.text((60, H - 20), "横轴：输入（相对中灰的 EV，对数）　纵轴：输出 8bit 显示值", fill=(90, 90, 90))
    im.save(path)
    print("saved", path)


def frames_png(out3, pc_jpg, path):
    # CLI 入口路径消毒：realpath 归一化（消除 ../ 与符号链接）
    if pc_jpg:
        pc_jpg = os.path.realpath(pc_jpg)
    ev_b, ev_w = auto_ev(out3, 0.5, 99.5)
    fil_pc = apply_filmic(out3, compute_spline(-7.65, 4.56))
    fil_auto = apply_filmic(out3, compute_spline(ev_b, ev_w))
    # v0.7.9 链 = filmic + pass3 rolloff（高光去饱和已删：判据挂输出域的 rolloff 覆盖）
    tiles = [
        ("filmic PC (完整链)", encode(rolloff(np.clip(fil_pc, 0, 1)))),
        ("filmic auto EV (完整链)", encode(rolloff(np.clip(fil_auto, 0, 1)))),
    ]
    imgs = []
    for label, arr in tiles:
        a = np.clip(arr, 0, 255).astype(np.uint8)
        img = Image.fromarray(a).resize((760, 570), Image.BOX)
        d = ImageDraw.Draw(img)
        d.rectangle([0, 0, 250, 20], fill=(0, 0, 0))
        d.text((6, 5), label, fill=(255, 255, 255))
        imgs.append(img)
    if pc_jpg:
        pc = Image.open(pc_jpg).convert("RGB")
        w = int(pc.width * 570 / pc.height)
        pc = pc.resize((min(w, 760), 570), Image.BOX)
        d = ImageDraw.Draw(pc)
        d.rectangle([0, 0, 190, 20], fill=(0, 0, 0))
        d.text((6, 5), "PC (reference)", fill=(255, 255, 255))
        imgs.append(pc)
    total_w = sum(i.width for i in imgs) + 8 * (len(imgs) - 1)
    out = Image.new("RGB", (total_w, 570), (255, 255, 255))
    x = 0
    for i in imgs:
        out.paste(i, (x, 0))
        x += i.width + 8
    out.save(path)
    print("saved", path)


if __name__ == "__main__":
    out3 = load_raw(sys.argv[1])
    curve_png(out3, "tools/tuning/filmic_curve.png")
    frames_png(out3, sys.argv[2] if len(sys.argv) > 2 else None, "tools/tuning/filmic_frames.png")
