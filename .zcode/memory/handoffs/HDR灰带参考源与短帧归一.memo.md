---
summary: HDR灰带参考源与短帧归一
created: 2026-11-14 22:55
updated: 2026-11-14 22:55
status: active
---

# HDR灰带参考源与短帧归一

## Summary

本窗口 0.13.64→0.13.77 共 14 包，全程追 HDR 灯面灰带。结论：**灰带 = HDR reference 的结构与 SDR 的结构不同形**。SDR 在灯面被 filmic 肩部压成 80px 平台（8bit 246 恒定），任何比它"更有结构"的 HDR reference（裸 scene / 曲线延伸 / HL 重建值 / 邻域最大）都会在平台边界显形。换 reference 只是换灰带形状。定版 0.13.77 用**短帧 + 正确归一**（HDR+ 的高光本来就该用短帧），接受"真实该多亮就多亮"。

## Done

- **0.13.65 找到真错位**：gainmap 的 `uHdr` 从 0.11.50 起一直绑 `film_src`（D4 场景线性，filmic **之前**），于是 `gainTrue` 退化成 `ratio = scene/clamp(scene,1)`（scene≤1 恒 1、scene>1 = scene）。184017 实测 97.6% 像素 ratio=1.005、2.4% 是 1.0→4.0。改绑 `tex_work_`（kFsFilm 输出）后语义才对。
- **0.13.66 发现第二层坑**：`filmicTone` 里的 ext 被下游 `gamutMapV5` 吃干净——Y 钳 `kYchY1931To2006`(≈1.058)、`gamutCheckRgb` 再钳 [0,1]。证据：`gmMax=0.50` 而 `aeP999=9.81`。延伸必须放在 gamut **之后**，且用 gamut 前的 `out3` 算 overEv。
- **0.13.66 反向事故**：ext 用全局标量 `ext/mxT` 拉伸 `tex_work_`，SDR 灯面出现横向波纹+白边（`tex_work_` 同时是 SDR 成片源，`min(c,1)` 把中等亮度拉爆后截断）。SDR 逐位不变是硬约束。
- **0.13.70 完整接入 PC `CurveExtension`**：CPU 侧 `computeHdrCurveExtension()` 按 `RawHdrReferenceMath.solve()` 求 join/quadratic/whiteSlope，shader 按 sceneX 三段求值。**灰带仍在**。
- **0.13.71~0.13.75 短帧链**：`hdr_scene_gain` 先乘在融合 scene 上（二次曝光，gmMax 1.00 顶满）→ 0.13.72 的 `tex_hdr_side_` 是假修复（`ChainCtx::hdr_side_raw` 全仓从未赋值，side 链从不执行）→ 0.13.73 接线后 side 链的 `passCcm` 覆盖 `tex_scene_` 成 −1.5EV 短帧，成片整体变暗 → 0.13.74 补 `fbo_hdr_zero_` + `glBlitFramebuffer` 恢复 zero D4，亮度复原但短帧未归一（ratio≈1）→ 0.13.75 短帧 ×`frame_scale[short_idx]`(2.82) 归一，`gmMax=0.62`(ratio 1.4)。
- **0.13.76 反例**：回 `film_src` + 12×12 邻域最大 → 提亮恢复但灰带换形状（最大窗口把邻域亮度搬进平台）。
- **0.13.77 定版**：`tex_hdr_side_ × frame_scale[short_idx]` + 4×4 盒式平均 + 完整曲线扩展；SDR 侧 zero D4 恢复；Mertens 关闭。

## Decisions

- [2026-11-14] **HDR 高光用短帧，不用 HL 重建值**：`film_src` 的 8~9 含高光重建（偏高），短帧归一后 6.1 才是物理值。用户拍板"真实该多亮就多亮"。
- [2026-11-14] **短帧必须乘 `frame_scale[short_idx]`**：−1.5EV 短帧 ×2.82 才回到 frame 0 曝光域。乘在已融合 scene 上是二次曝光（0.13.71 的 gmMax 顶满就是这样来的）。
- [2026-11-14] **HDR 延伸不能写在 `kFsFilm` 里**：`tex_work_` 同时是 SDR 成片源，任何 >1 的改动都会经 `kFsOut` 的 `min(c,1)` 变成硬截断。
- [2026-11-14] **不再用"邻域最大/更大采样核"救灰带**：kK=12 平均（0.13.68）、12×12 最大（0.13.76）都试过，只会把灰带换形状。

## Pitfalls

- [2026-11-14] **`ChainCtx::hdr_side_raw` 曾全仓无赋值**：`tex_hdr_side_` 这条"独立 HDR reference"从 0.12.77 起就是死路径，日志里没有任何 `hdr.side` 事件可查。判"某条链路在跑"要看事件日志，不能只看代码存在。
- [2026-11-14] **side 链会覆盖 `tex_scene_`**：`passCcm` 硬编码写 `fbo_scene_`，Mertens 关闭时没人写回 zero ⇒ 成片暗 2.8 倍。只取 reference 时必须 blit 恢复。
- [2026-11-14] **短帧在灯面部分饱和**：`satmCov=2.42%`、`satmMax=1023`(满阱)。归一后只能到 6.1，比 HL 重建的 8~9 低——这是物理上限，不是 bug。
- [2026-11-14] **`gmMean` 会掩盖分布**：0.13.64 的 gmMean 0.51 看着和 PC 一致，实际分布是"97.6% 恒 1 + 2.4% 剧变"。判 gainmap 要看 `gmMax` 与 base 的分箱对应，不能只看均值。
- [2026-11-14] **PC 的 boost 3.3× 不是公式，是曝光策略**：`recoveryWhiteGain = HDR scene exposure / SDR render exposure`。PC 的 SDR base 只有 228，我们的 246，留给 HDR 的空间本来就更小。

## Next

1. **灰带根因仍在 SDR 平台**：灯面 8bit 246 恒定 80px，是 filmic 肩部 + LUT 共同压出来的。要让 HDR 有更多提升空间且无边界，必须让 SDR 在灯面留渐变（提高白点或放缓肩部）——会影响所有成片，需用户单独拍板。
2. 若不动曲线，当前 0.13.77 是"短帧真实值"的终态：灯面 HDR ≈1.4× SDR 白点，灰带不回来。
3. 旧账未动：编辑器整页路由、Spektrafilm 引擎 + 预设标定、理光曲线重标、绿植/绿段、AE 高光保护效果。

## Refs

- 包：`filmkit-0.13.77-HDRSHORT.apk`（785/0.13.77）；回退 `filmkit-0.13.64-HDRHALF.apk`
- 提交：`89a5b97` fix(hdr): short-frame side reference normalized to frame-0 exposure（已 push dev）
- 代码：`FrameRenderer::computeHdrCurveExtension`（新增）、`passGainmap(scene_tex, scene_gain, ...)`、`ensureHdrSideTargets` 的 `fbo_hdr_zero_`、`kFsGainmap` 的 `uScene/uHdrSceneGain/uHdrCurve*`
- 一手源码：`https://github.com/bjzhou/PhotonCamera` —— `raw/RawHdrReferenceMath.kt`（solve/CurveExtension）、`raw/RawEngineTonePass.kt`（hdrReferenceFragmentShader）、`hdr/GpuReferenceGainmapProducer.kt`（RAW_LUMA_RESIDUAL，ratio = hdrLuma/sdrBeforeLut）、`gallery/PhotoProcessor.kt:471`（`raw_linear_srgb_selected_engine_highlight_reference`）
- 工具：`tools/hdr-baseline-eval.py`（base/gainmap/boost 统计）、`tools/validate-shaders.sh`
- 样片：`test_temp/FILMKIT_20261114-203518.jpg` 等；诊断图 `test_temp/hdr-grayband-diag/`
- 日志关键字段：`brkRatio/hdrFs/hdrSceneGain/gmMax/gmMin`、`aeP999`、`satmCov`、`whiteEv`
