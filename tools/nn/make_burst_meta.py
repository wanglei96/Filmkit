#!/usr/bin/env python3
"""为每个 burst 场景生成 meta（噪声模型 S/O + ISO），供 per-scene cvt_k 使用。

为什么需要：5 个场景 ISO 从 216 到 4781（差 22 倍），S_sensor 差 6 倍，
而 cvt_k = S_train·neff/S_sensor 必须**按每个场景自己的噪声水平**算 ——
用单一 --s-sensor 训练会让 4/5 的场景处在错误的输入域。

S/O 来源：DNG tag 51041 NoiseProfile（= Camera2 SENSOR_NOISE_PROFILE，与引擎
`nzSrc:"cam2"` 同源）。DNG 规范按 CFAPlaneColor 排（R,G,B），G 取中间项 ——
已用 4 个场景的数值规律核对（G 最小），ISO4781 例外属真实高 ISO 行为。
"""
import glob
import json
import os
import struct
import sys

WANT = (34855, 51041, 33434)


def tiff_tags(path):
    with open(path, "rb") as f:
        d = f.read()
    bo = "<" if d[:2] == b"II" else ">"
    off = struct.unpack(bo + "I", d[4:8])[0]
    out = {}

    def rd(typ, val, cnt):
        if typ == 3:
            return struct.unpack(bo + "H", val[:2])[0]
        if typ == 4:
            return struct.unpack(bo + "I", val[:4])[0]
        if typ == 12:
            if cnt <= 1:
                return struct.unpack(bo + "d", val[:8])[0]
            o = struct.unpack(bo + "I", val[:4])[0]
            return list(struct.unpack(bo + f"{cnt}d", d[o:o + 8 * cnt]))
        if typ == 5:
            o = struct.unpack(bo + "I", val[:4])[0]
            return [struct.unpack(bo + "II", d[o + 8 * i:o + 8 * i + 8]) for i in range(cnt)]
        return None

    def walk(o, dep=0):
        if dep > 3 or o <= 0 or o >= len(d) - 2:
            return
        n = struct.unpack(bo + "H", d[o:o + 2])[0]
        for i in range(n):
            e = o + 2 + i * 12
            if e + 12 > len(d):
                return
            tag, typ, cnt = struct.unpack(bo + "HHI", d[e:e + 8])
            val = d[e + 8:e + 12]
            if tag in WANT:
                out[tag] = rd(typ, val, cnt)
            if tag in (34665, 330, 50706):
                walk(struct.unpack(bo + "I", val[:4])[0], dep + 1)
        walk(struct.unpack(bo + "I", d[o + 2 + n * 12:o + 6 + n * 12])[0], dep + 1)

    walk(off)
    return out


def main():
    dng_dir, out_dir = sys.argv[1], sys.argv[2]
    import datetime
    files = sorted(glob.glob(os.path.join(dng_dir, "*.dng")))
    scenes, cur, prev = [], [], None
    for f in files:
        t = datetime.datetime.strptime(os.path.basename(f).split("-")[1].split(".")[0], "%H%M%S")
        if prev and (t - prev).total_seconds() > 8:
            scenes.append(cur)
            cur = []
        cur.append(f)
        prev = t
    scenes.append(cur)

    for k, sc in enumerate(scenes, 1):
        tg = tiff_tags(sc[0])
        npf = tg.get(51041) or []
        iso = tg.get(34855)
        et = tg.get(33434)
        exp = (et[0][0] / et[0][1]) if isinstance(et, list) and et and et[0][1] else None
        if len(npf) < 6:
            print(f"  场景{k}: 无 NoiseProfile，跳过"); continue
        # (R, G, B) 顺序：每通道 (S, O)
        meta = {
            "scene": k, "iso": iso, "exposure_s": exp,
            "s_r": npf[0], "o_r": npf[1], "s_g": npf[2], "o_g": npf[3],
            "s_b": npf[4], "o_b": npf[5],
            "n_frames": len(sc), "first": os.path.basename(sc[0]),
        }
        p = os.path.join(out_dir, f"scene{k:02d}_meta.json")
        with open(p, "w") as f:
            json.dump(meta, f, indent=1)
        print(f"  场景{k}: ISO{iso:>5} S_G={meta['s_g']:.4e} O_G={meta['o_g']:.2e} "
              f"曝光={exp} → {os.path.basename(p)}")


if __name__ == "__main__":
    main()
