---
summary: 色相路线收口与HDR路线定案
created: 2026-10-19 17:20
updated: 2026-10-20 09:45
status: absorbed
---

# 色相路线收口与HDR路线定案

## Summary

本窗口把 ACES 2.0 与 Oklab L-only 两条"高饱和色相"路线做完并关闭：先发现并修正 **D5 域口径 bug**（`film_d5` 是 Rec.2020 线性，直接套 sRGB OETF 会得出假结论），修正后 **ACES 没有色相优势**（Δh 1.58° vs filmic 1.50°，ΔE 4.69 vs 2.11）；Oklab 能把 sigmoid 的 Δh 4.43°→1.83°，但 ΔE 3.69→5.42，变体/混合均不满足"ΔE≤3.7 且 Δh<2.5°"⇒ 回滚。HDR 路线定案：**按 PC 做 4:4:4 JPEG + libultrahdr（JPEG/R）**，不做探针。

## Done

- [2026-10-19] **ACES 2.0 离线实验**（`tools/aces2-offline-eval.py`）：用 OCIO 2.6 内置 `ocio://default` 的官方 `ACES 2.0 - SDR 100 nits (Rec.709)` view，链路 `Linear Rec.2020 → ACES2065-1 → view → sRGB`；红杯 ROI（RAW `Y 1570–1970, X 2300–2700`，高饱和像素 58%）。
- [2026-10-19] **D5 域口径 bug**：`film_d5` / `film_d5_sigmoid` 是 **Rec.2020 线性**（期 B 工作空间），脚本此前直接 `srgb_oetf` ⇒ 等价于把 Rec.2020 原色当 sRGB 编码。修正为「Rec.2020 → sRGB 线性 → OETF」后重算，此前报告的数字作废。
- [2026-10-19] **修正后的红杯数据**：sigmoid（per-channel）ΔE 3.69 / Δh 4.43°；filmic ΔE 2.11 / Δh 1.50°；ACES 原生 ΔE 11.71 / Δh 3.44°；ACES 重锚（×1.7919）ΔE 4.69 / Δh 1.58°。⇒ **ACES 无优势**，路线关闭。
- [2026-10-19] **Oklab L-only（C 方案）实现**：GLSL 加 `sigmoidOklabTone`（Rec.2020→Oklab 预乘矩阵，只改 L，a/b 按 L 比例缩放）；离线 GPU 跑通，Δh 4.43°→1.83°，高光/中灰不变（span 1.40 / dead 3.70）。
- [2026-10-19] **C 方案变体测试**：A 比例缩放 ΔE 5.42/Δh 1.83°；B `a/b` 不变 ΔE 4.71/Δh 3.48°；C `a/b×0.8` ΔE 6.24/Δh 1.55°；混合 w=0.3 ΔE 3.76/Δh 4.11°，w=0.7 ΔE 4.25/Δh 2.54°。**没有配置同时满足 ΔE≤3.7 且 Δh<2.5°** ⇒ 回滚（`git checkout -- FrameRenderer.cpp`），`kFsSigmoid` 恢复 chars 16175 / crc32 `0xfbab6a8c`。
- [2026-10-19] **HDR 路线定案**：PC 的 HDR JPEG = 自研 4:4:4 JPEG（`writeNative`）+ gainmap JPEG + **libultrahdr** `packageJpegR`（`jpeg_r_encoder.cpp`，vendored `libultrahdr`/`libjpeg-turbo`）；`UltraHdrWriter`（`Bitmap.compress` + `Gainmap`）是 4:2:0 的另一条。x300 成片无 HEIC；用户不喜欢 HEIC（4:2:0 色度差、亮度优势不明显、细节差）。
- [2026-10-19] **待重标 7 项梳理**（除预设外 5 项）：分离色调/黑白 `[23][24][25]`、分区饱和度 `[20..22]`、光晕 `[62..65]`、场景统计/自动 EV `kFsHist`、局部色调映射 `kFsLocal`；其中 `[36]` 在引擎 1 下被 `EngineScope.NEUTRALIZE` 置 0 旁路，默认成片不受影响。
- [2026-10-19] **默认 AE 结论**：`sw999` 只在 `[35] auto_tone` 开启时写（`FrameRenderer.cpp` 的 `if (st.valid && auto_on)`），默认 `[35]=0` 且引擎 1 归零 ⇒ 高光保护默认不参与，期 B 换域**不影响默认 AE**；开启自动影调后才需要重调。
- [2026-10-19] **HDR 探针脚本** `tools/hdr-probe.py`（adb 显示/编码/属性 + 人工清单）；无设备时只输出待办。**结论：不需要探针**（PC 已验证下游兼容）。

## Decisions

- [2026-10-19] **ACES 路线关闭**：口径修正后它没有色相优势（1.58° vs filmic 1.50°），ΔE 更差（4.69 vs 2.11），且需要输入变换 + 中灰重锚（牺牲高光）+ 表。
- [2026-10-19] **Oklab L-only 不做**：它能把 sigmoid 的 Δh 压到接近 filmic，但 ΔE 从 3.69 升到 5.42；任何混合比例都过不了"ΔE 不升、Δh 降"的门槛 ⇒ 不在 sigmoid 上继续加复杂度。
- [2026-10-19] **HDR 按 PC 路线做，不做探针**：vendored `libultrahdr` + `libjpeg-turbo` + 移植 `jpeg_r_encoder.cpp`（Apache-2.0），base 用我们的 4:4:4 `JpegEncoder.cpp`，gainmap 从 HDR 线性 + SDR 显示算亮度比。工作量约 1–2 周（独立一期）。
- [2026-10-19] **HEIC 降级**：默认 JPEG（已是）；用户明确不喜欢 HEIC 的 4:2:0 色度。UI 待定：加提示（推荐）或移除选项。

## Pitfalls

- [2026-10-19] **D5 域口径**：`film_d5`/`film_d5_sigmoid` 是 Rec.2020 线性，不是 sRGB 线性；直接 `srgb_oetf` 会得出"ACES 显著更好"的假结论。任何拿 D5 比色的脚本都必须先转 sRGB。
- [2026-10-19] **离线 ROI 坐标**：DNG RAW 是横的（4080×3072），预览是竖的（rawpy flip=6 旋转 90°）；红杯 RAW 坐标 `Y 1570–1970, X 2300–2700`，按预览坐标直接取会跑到叶子上。
- [2026-10-19] **`Bitmap.compress` 的 Ultra HDR 是 4:2:0**：4:4:4 + HDR 必须走 libultrahdr + 自研编码器（PC 的 `Jpeg444ExportEncoder`），不能用平台 `Bitmap.compress` 路径。
- [2026-10-19] **Oklab 的 ΔE/Δh 是折中曲线**：A/B/C 与混合 w 都试过，没有交点；继续调参数只会在曲线上滑动。
- [2026-10-19] **`film_d5_sigmoid` CRC 随 shader 变**：回滚后 `kFsSigmoid` 源 CRC 恢复 `0xfbab6a8c`；ROI 不同时 `film_d5_sigmoid.npy` CRC 不同是正常的。

## Next

1. **HDR 实施（按 PC 路线）**：引入 `libultrahdr` + `libjpeg-turbo`，移植 `jpeg_r_encoder.cpp`，接 4:4:4 base + gainmap，出包真机看相册/微信。
2. **待重标 5 项**（除预设）：分离色调/黑白、分区饱和度、光晕、场景统计/自动 EV、局部色调映射；先做默认参数 A/B 量化。
3. **HEIC UI 决策**：加提示（"10bit 但 4:2:0，色度弱于 JPEG 4:4:4"）或移除选项。
4. **真机待验**：判据 F（HEIC 四端）+ 阶段 3 判据 0（0.11.49 已验三项）。
5. **默认 AE**：确认高光保护默认未启用是否符合设计意图（v0.10.110 用户拍板"从曝光策略解决高光"）。

## Refs

- 工具：`tools/aces2-offline-eval.py`（ACES/Oklab 对比）· `tools/hdr-probe.py`（HDR 探针）
- 报告：`docs/reports/aces2-offline-eval-REPORT.md` · `docs/reports/aces2-offline-eval-redcup-REPORT.md` · `docs/reports/oklab-lum-eval-REPORT.md` · `docs/reports/hdr-probe-REPORT.md`
- 图：`docs/reports/aces2-offline-redcup-compare.png` · `docs/reports/oklab-lum-eval-compare.png`
- PC 源码：`gallery/Jpeg444ExportEncoder.kt` · `cpp/jpeg_r_encoder.cpp` · `hdr/RawGainmapMath.kt` · `hdr/UltraHdrWriter.kt`
- 提交：`6d7e60f`（docs）· `56c3fa1`（tools）
