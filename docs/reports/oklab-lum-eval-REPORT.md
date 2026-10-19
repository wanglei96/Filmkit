# Oklab 亮度域 tone mapping（C 方案）离线实验

日期：2026-10-19 · 输入：`test_temp/FILMKIT_20261019-163823.dng`（红杯 ROI，RAW `Y 1570–1970, X 2300–2700`，高饱和像素 58%，C* 中位 49.8）

> ⚠ **2026-10-19 口径修正**：期 B 后 `film_d5` / `film_d5_sigmoid` 是 **Rec.2020 线性**（D5 工作空间），不是 sRGB 线性。此前直接套 sRGB OETF 的口径是错的（等价于把 Rec.2020 原色当 sRGB 编码）。下表已改为「Rec.2020 → sRGB 线性 → OETF」的正确口径；旧报告里的 sigmoid 7.21° 等数字作废。

## 方法

**Oklab L-only**：线性 Rec.2020 → Oklab（预乘矩阵）→ 对 **L** 应用 sigmoid 的亮度响应（`Y_out = sigmoid_curve(Y_in)`，`L' = cbrt(Y_out)`）→ 保持 a/b 比例（`a' = a·L'/L`）→ 转回线性 Rec.2020（工作空间）→ 公共段 `workToDisplayLinear` → sRGB OETF。

对比对象：
- `sigmoid`（per-channel，改前）：引擎 1 旧路径。
- `sigmoid + Oklab`（改后）：本次 GLSL 实现（`sigmoidOklabTone`，`uSigHuePreservation=1.0`）。
- `filmic`：引擎 0（M9 参数）。
- `ACES 重锚`：ACES 2.0 100nit × 1.7919（18% 灰对齐 119 LSB）。

## 结果（vs 场景参考 `ccm_d4` 的 Lab，高饱和像素 C* > 40）

| 链路 | 平均 ΔE2000 | 平均 Δh | 8bit 可分辨跨度 | 8bit 死白起点 |
|---|---|---|---|---|
| sigmoid（per-channel，改前） | **3.69** | 4.43° | 1.40 EV | 3.70 EV |
| **sigmoid + Oklab（改后）** | 5.42 | **1.83°** | 1.40 | 3.70 |
| filmic | **2.11** | **1.50°** | 1.50 | 3.85 |
| ACES 2.0 重锚 | 4.69 | 1.58° | 0.70 | 3.00 |

## 结论

- **Oklab 把 sigmoid 的色相偏移从 4.43° 降到 1.83°**（接近 filmic 的 1.50°），高光/中灰与改前完全一致（span 1.40 / dead 3.70）。
- **代价是 ΔE 从 3.69 升到 5.42**：亮度曲线作用在 Y 上，彩色像素的亮度/饱和度关系与 per-channel 不同 ⇒ 整体色差变大。ΔE 变差主要来自亮度/色度，不是色相。
- **filmic 仍然是“最准”的**（ΔE 2.11 / Δh 1.50），但有硬平顶高光；sigmoid 的高光更好。
- 所以 C 不是“全面更好”，而是“用 ΔE 换 Δh”：如果用户更在意高饱和色相（红杯/紫边），值得；如果更在意整体色差，filmic 更优。

## 待决

- 是否继续调 C（目标：Δh 保持 ~1.8° 的同时把 ΔE 拉回 ≤3.7）？可试的方向：
  1. `a/b` 不按 `L'/L` 缩放（保持 C 不变，只改 L）——可能改善 ΔE，但饱和度/亮度关系变。
  2. 用 Oklab 的 L 曲线替代 `cbrt(sigmoid(Y))`（直接对 L 做 S 曲线，重新标定）。
  3. 混合：`uSigHuePreservation` 在 0.5~0.8 之间，per-channel 与 Oklab 折中。
- 当前 shader 已改（默认 Oklab），**尚未出包**；若不做 C，回滚这一处即可（diff 只在 `kFsSigmoidDef`）。

## 变体测试（2026-10-19，按建议执行）

| 变体 | 平均 ΔE2000 | 平均 Δh |
|---|---|---|
| A 比例缩放（当前实现） | 5.42 | 1.83° |
| B `a/b` 不变 | 4.71 | 3.48° |
| C `a/b`×0.8 | 6.24 | 1.55° |
| 混合 w=0.3（per-channel 70% + Oklab 30%） | 3.76 | 4.11° |
| 混合 w=0.5 | 3.85 | 3.31° |
| 混合 w=0.7 | 4.25 | 2.54° |
| 混合 w=0.8 | 4.57 | 2.21° |

目标：ΔE ≤ 3.7 **且** Δh < 2.5°。**没有任何配置同时满足**——w=0.3 的 ΔE 3.76 略超、Δh 4.11 超；w=0.7 的 Δh 2.54 略超、ΔE 4.25 超。

⇒ 按预定判据**回滚**（`git checkout -- core/engine/src/render/FrameRenderer.cpp`）：shader 恢复期 B 基线（`kFsSigmoid` chars 16175 / bytes 24028 / crc32 `0xfbab6a8c`），未出包。

## 复现

- GPU 实现：`core/engine/src/render/FrameRenderer.cpp` 的 `sigmoidOklabTone`（`kFsSigmoidDef` 内）。
- 离线产物：`/tmp/oklab_gpu`（`engine-shader-offline.py --engine 1`）；对比脚本 `tools/aces2-offline-eval.py`。
