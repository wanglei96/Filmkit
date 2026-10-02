#!/usr/bin/env bash
# 按场景切分 burst → 对齐平均 → 生成 (noisy, clean) 训练对
#
# 切分口径（与推理条件对齐，关键）：
#   noisy = f1..f4 平均  —— 模拟推理时 NN 的输入（MFNR 4 帧融合，噪声方差 = 单帧/4）
#   clean = f1 + f5..fN  —— 17 帧平均，比 noisy 干净 ~2.3x
# 两者都以 f1 为对齐参考，因此处于**同一坐标系**（像素对齐）；
# clean 只与 noisy 共享 f1 一帧，噪声相关性 ~0.14（可忽略）。
# f2..f4 刻意不进 clean，避免相关性升高。
set -u
ROOT="/Users/wl/Downloads/胶片相机"
DNG="$ROOT/test_temp/dng"
OUT="${OUT_DIR:-$ROOT/test_temp/burst}"
INTERP="${INTERP:-bilinear}"
TOOL="$ROOT/tools/nn/multiframe_clean.py"
mkdir -p "$OUT"

# 场景切分（按时间戳间隔 >8s），与前面统计一致
python3 - "$DNG" "$OUT" "$TOOL" <<'PY'
import glob, os, subprocess, sys, datetime
dng_dir, out_dir, tool = sys.argv[1], sys.argv[2], sys.argv[3]
interp = os.environ.get("INTERP", "bilinear")
files = sorted(glob.glob(os.path.join(dng_dir, "*.dng")))
scenes, cur, prev = [], [], None
for f in files:
    t = datetime.datetime.strptime(os.path.basename(f).split('-')[1].split('.')[0], '%H%M%S')
    if prev and (t - prev).total_seconds() > 8:
        scenes.append(cur); cur = []
    cur.append(f); prev = t
scenes.append(cur)
print(f"共 {len(files)} 帧 → {len(scenes)} 个场景")
for k, sc in enumerate(scenes, 1):
    if len(sc) < 8:
        print(f"  场景{k}: 仅 {len(sc)} 帧，跳过"); continue
    noisy_in = sc[:4]                     # f1..f4
    clean_in = [sc[0]] + sc[4:]           # f1 + f5..fN
    n_out = os.path.join(out_dir, f"scene{k:02d}_noisy.npy")
    c_out = os.path.join(out_dir, f"scene{k:02d}_clean.npy")
    print(f"\n=== 场景{k}: {len(sc)} 帧  noisy←{len(noisy_in)}帧  clean←{len(clean_in)}帧  核={interp}", flush=True)
    for inp, outp, tag in ((noisy_in, n_out, "noisy"), (clean_in, c_out, "clean")):
        cmd = ["python3", tool, *inp, "--ref", "0", "--max-shift", "200", "--min-wn", "0.6",
               "--interp", interp, "--out", outp]
        r = subprocess.run(cmd, capture_output=True, text=True)
        tail = [l for l in r.stdout.splitlines() if any(s in l for s in
                ("保留", "剔除", "有效像素", "耗时", "→ clean", "重建核"))]
        print(f"  [{tag}] rc={r.returncode} " + " | ".join(tail[-4:]), flush=True)
        if r.returncode != 0:
            print("   STDERR:", r.stderr[-400:], flush=True)
PY
echo "=== 产物 ==="; ls -la "$OUT"/*.npy 2>/dev/null | head -12
