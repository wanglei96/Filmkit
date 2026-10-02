#!/usr/bin/env bash
# Noise2Noise 配对：input/target 两组**互不重叠**、同噪声水平、同坐标系。
#
# 为什么不用"多帧平均当 clean"（此前三条路线全否证）：
#   多帧平均的目标同时是"更干净"**和**"更平滑"（亚像素插值低通），网络把两者
#   一起学了 —— 实测三次微调 ret4 全部下降（0.919→0.846 / 0.920→0.835 /
#   0.896→0.826），三联图目视"更软"。只要有 clean 参照，就逃不掉这个耦合。
#
# Noise2Noise：不设 clean，让网络学 E[target|input]，噪声独立时最优解 = 信号本身，
#   且两组低通同源 ⇒ 网络学不到低通。
#   参考帧（帧1）只作对齐基准、不参与平均（--ref-exclude）⇒ 两组共享坐标系却
#   完全独立，噪声水平同为 4 帧平均（σ₀/2，与推理时 MFNR neff=4 一致）。
set -u
ROOT="/Users/wl/Downloads/胶片相机"
DNG="$ROOT/test_temp/dng"
OUT="$ROOT/test_temp/burst_n2n"
TOOL="$ROOT/tools/nn/multiframe_clean.py"
mkdir -p "$OUT"

python3 - "$DNG" "$OUT" "$TOOL" <<'PY'
import glob, os, subprocess, sys, datetime
dng_dir, out_dir, tool = sys.argv[1], sys.argv[2], sys.argv[3]
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
    if len(sc) < 10:
        print(f"  场景{k}: 仅 {len(sc)} 帧（N2N 需要 ≥10），跳过"); continue
    ref = sc[0]
    a_in = [ref] + sc[1:5]        # 帧1 作参考不参与 + 帧2-5
    b_in = [ref] + sc[5:9]        # 帧1 作参考不参与 + 帧6-9
    print(f"\n=== 场景{k}: {len(sc)} 帧  input←帧2-5  target←帧6-9（参考帧1 不参与）", flush=True)
    ok = True
    for inp, outp, tag in ((a_in, f"{out_dir}/scene{k:02d}_noisy.npy", "input"),
                           (b_in, f"{out_dir}/scene{k:02d}_clean.npy", "target")):
        cmd = ["python3", tool, *inp, "--ref", "0", "--ref-exclude",
               "--max-shift", "200", "--min-wn", "0.6", "--out", outp]
        r = subprocess.run(cmd, capture_output=True, text=True)
        tail = [l for l in r.stdout.splitlines()
                if any(s in l for s in ("保留", "剔除", "有效像素", "耗时", "→ clean"))]
        print(f"  [{tag}] rc={r.returncode} " + " | ".join(tail[-4:]), flush=True)
        if r.returncode != 0:
            ok = False
            print("   STDERR:", r.stderr[-300:], flush=True)
    print(f"  场景{k}: {'OK' if ok else '失败'}", flush=True)
PY
echo "=== 产物 ==="; ls -la "$OUT"/*.npy 2>/dev/null | head -12
