# E4-3 颗粒（grain）离线验收

颗粒引擎按 darktable `src/iop/grain.c` 重写（3 八度 simplex + 128² 相纸响应 LUT + ISO 尺寸 +
短边归一化），本目录是它的**离线验收工具**。规格见
`docs/reports/editor-E4-film-params-DESIGN.md` §3.1，交付报告见 `docs/reports/e4-3-grain-REPORT.md`。

## 文件

| 文件 | 作用 |
|---|---|
| `grain-exp.py` | 主验收脚本：真跑引擎自己的 `kFsOut`/`kFsStyle` 颗粒段，输出频谱、按亮度分箱 RMS、方向判据、目视截图 |
| `grain-lut-dump.cpp` | 把 C++ 侧 `ensureGrainLut` 的实际 LUT 二进制 dump 出来（`#include` 的是生产代码同一份 `GrainLut.h`）—— 否则"numpy ↔ C++ 对照"证明不了真机数据 |
| `out/` | **产物，不入库**（`.raw` / `.npy` / `.png`，约 1 MB/次） |
| `grain-lut-dump` | 编译出的二进制，**不入库** |

## 复现

```bash
# ① C++ 侧 LUT 与 numpy 独立复算对拍（判据：相对误差 0.000e+00）
python3 tools/grain_lut_ref.py

# ② 主验收（频谱 / 调制曲线 / 方向判据 / 目视截图 → out/）
python3 tools/grain-exp/grain-exp.py

# ③ 需要重新编译 dump 工具时
c++ -O2 -std=c++17 tools/grain-exp/grain-lut-dump.cpp -o tools/grain-exp/grain-lut-dump
```

## 关键判据（E4-3 实测值）

| 判据 | 期望 | 实测 |
|---|---|---|
| `[26]=0` 零回归 | 逐位不变 | max\|Δ\| = 1 LSB（= 既有 TPDF dither），>1LSB 像素 **0/28311552** |
| LUT C++ ↔ numpy | 逐位相同 | 相对误差 **0.000e+00**（mb=100/0、pivot=0/0.3） |
| `[27]` 单调（ISO 越大越粗） | 单调 | 100→5.24 / 400→5.66 / 1600→7.61 / 6400→15.97 px |
| 颗粒感 vs 块状 | 低频能量占比低 | 新 **8.8–14.7%** vs 旧实现同尺度 **51.7%** |
| `[60]` 中间调最重 | 两端趋 0 | mb=100 → 6.39/**11.89**/5.74；mb=0 → 11.76/11.97/11.74 |
| `[61]=0` ↔ darktable 原版 | 逐位相同 | **0.000e+00** |
| `[61]` 方向（E4-3b 修正后） | 峰值随 pivot 移向**暗部** | LUT 峰值 0.496→0.362→0.268；shader 峰值 0.453→0.391→0.266 |
| 短边归一化（ADR-11） | 跨分辨率一致 | 3072px vs 1536px 相对偏差 **0.1%** |
