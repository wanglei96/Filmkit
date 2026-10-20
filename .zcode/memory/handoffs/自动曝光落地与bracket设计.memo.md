---
summary: 自动曝光落地与bracket设计
created: 2026-10-20 14:58
updated: 2026-10-20 14:58
status: active
---

# 自动曝光落地与 bracket 设计

## Summary

本窗口把「高光顶白」从归因推进到**落地**：0.11.59 修好 `sw999`（此前只在 auto_tone 开时写 ⇒
高光保护永远拿不到数据）+ 新增成片诊断；0.11.60 把查看页 HDR 开关改为**按单图记忆**；
0.11.61 落地**渲染端自动曝光**（本张 P50/P99.5 → EV，中灰锚定 18.45% + 高光优先）；
0.11.62 修掉「采集端预测 + 渲染端本张」**双重欠曝**；0.11.63 高光判据 P99.9→**P99.5**
（允许 0.5% 裁切，PC 口径）；0.11.64 修掉 MFNR **超时定时器误杀下一张**（导致第二张回退
单帧、无 HDR、明显更暗）。最终同场景两张逐项一致（`aeEv` 完全相同、`mean` 差 1）。
bracket（1 短 3 长 + 饱和感知融合）设计文档已写完，方向用户已拍板，下一窗口实施。

## Done

- [2026-10-20] **0.11.59 诊断包**：`FrameRenderer` 的 still 链**无条件**写 `last_still_w999_`
  （此前只在 `auto_on` 分支内）；`Bridge` 的 `stats.sw999` 改读成片实例
  （`g_renderer_still`，拍照走独立实例，读 `g_renderer` 恒 0）；`CameraController` 每张记
  `photo.stats`（`sw999` / BT.601 `mean` / `hi240` 占比）。单测 782/0。
- [2026-10-20] **0.11.60 查看页 HDR 按单图**：旧实现是全局 `view_hdr` ⇒ **关一张 = 全部关**
  （用户 2026-10-20 当场纠正"这个开关属于单张图片"）；改为 `view_hdr:<uri>`，面板按图提示
  「带 HDR 增益图 / 没有 HDR 数据」；设置页「HDR 成片」文案澄清**只管新拍**。
- [2026-10-20] **0.11.61 渲染端自动曝光**：新增 `core/engine/src/render/AeMetering.{h,cpp}`
  （纯函数 + 零依赖自测，失败写 `ae.selftest_failed`）；`FrameRenderer::passAutoExposure` 在
  `passFilm` **之前**对 `film_src` 取本张统计，`pe.exposure_ev += aeEv`；JNI
  `nativeSet/GetAutoExposure` + prefs `ae_auto`（默认开）+ 设置页「自动曝光（本张统计）」。
  日志 `raw.render` 新增 `aeOn/aeEv/aeMid/aeHl/aeP50/aeP995/aeP999/aeWhy`。
- [2026-10-20] **0.11.62 双重欠曝修复**：`ae_auto` 开时采集端高光保护**旁路**
  （`hlOn = hl_protect && !aeAuto`）。真机证据：第二张拿第一张 `sw999=4.321` 把采集压到
  2.1ms（`hlUnder 1.98`），补偿 +1.977 后渲染端又压 −2.0。
- [2026-10-20] **0.11.63 P99.5 判据**：`SceneStats` 加 `p_hi995`，`measureSceneStats` 多算
  一个分位；`AeMetering` 用它（P99.9 仍留给 filmic 白点上探）。依据 = PC
  `raw_legacy_auto_exposure_solver` 的 `maximumClippedFraction=0.005`。
- [2026-10-20] **0.11.64 MFNR 超时误杀修复**：`tryProcessBurst` 收满 4 帧立即
  `cancelBurstTimeout()`。真机：第一张渲染 6.6s，第二张 45.26 开拍，第一张的 5s 定时器
  45.32 到期把第二张误判超时（`mfnr.timeout images:0 waitMs:278`）⇒ 回退单帧。
- [2026-10-20] **bracket 设计文档** `docs/engine-bracket-design.md`（198 行）：目标 5 判据、
  现状架构、采集/对齐/融合两条路径的具体改法、参数标定表、验收判据、风险、4 步实施。
- [2026-10-20] **实测（0.11.64，台灯+窗）**：两张 `aeEv −1.11 / −1.11`、`aeP50 0.0696`、
  `aeP995 2.0273`、`mean 44.8 / 45.2`、`gainmap:true` ×2、`mfnr.capture frames:4` ×2。

## Decisions

- [2026-10-20] **自动曝光放在渲染端，不改采集**：本张 RAW 统计决定 film pass 的 EV，
  光子全保留、会话第一张也有效。采集端的高光保护（只能拿上一张预测）在 `ae_auto` 开时旁路。
  两层职责：采集 = 收光子，渲染 = 分配。
- [2026-10-20] **高光判据 = P99.5，不是 P99.9**：允许 0.5% 像素裁切（PC legacy matcher 口径），
  否则为 0.1% 像素压全图 2EV（真机实测）。P99.9 保留给 filmic 白点上探。
- [2026-10-20] **Auto EV 限幅 ±2EV，欠曝上限与 gainmap `[0.25,4.0]` 对齐**：压暗超过 2EV
  后 gainmap 表达不了，等于用 HDR 换 SDR；再大的动态范围交给 bracket。
- [2026-10-20] **查看页 HDR 开关按单图**（用户拍板）：每张图各自记忆，默认开；
  设置页总开关只管新拍是否带 gainmap。
- [2026-10-20] **bracket 方向拍板**（用户 2026-10-20 确认）：1 短 3 长、短帧放**帧 0 作参考帧**、
  `k=2` 初值；两条融合路径（`kFsMfnrFuse` / `kFsMfnrFft`）都要改，短帧先 ×4 归一、
  σ 按 ratio² 缩放、饱和像素权重 0。

## Pitfalls

- [2026-10-20] **`sw999` 曾是聋的**：只在 `auto_tone` 开时写，默认 `[35]=0` + 引擎 1 归零 ⇒
  `sceneHighlightEv()` 恒 NaN ⇒ 高光保护从没生效过。修法是把它移出 `auto_on` 分支。
- [2026-10-20] **两层压同一件事 = 双重欠曝**：采集端欠 1.98EV + 渲染端再压 2EV，同场景两张
  亮度不一致。**加第二层保护前先问"它和现有那层是什么关系"**。
- [2026-10-20] **超时定时器必须与"当前 burst"绑定**：`burstActive` 是共享标志，上一张的
  定时器会把下一张误杀；收满即摘 + 日志 `mfnr.timeout` 才看得见。
- [2026-10-20] **并发拍照的两张会互相覆盖实例状态**：`nativeEngineGetStats` 读 `g_renderer`、
  拍照走 `g_renderer_still` ⇒ 字段恒 0；gainmap 读回同理。
- [2026-10-20] **全局 prefs 冒充单图状态**：`view_hdr` 一关全关，用户一眼看出不对。UI 上的
  "单张"语义必须落到 per-item key。

## Next

1. **bracket 第 1 步**：采集 `1 短 3 长`（短帧帧 0、k=2）+ `shortRatio` 透传 + 日志
   `bracket.short/sec/ratio`；融合仍用同曝光（短帧仅记录），先验证采集与回退。
2. **加权路径 A**（`kFsMfnrFuse`）：`uFrameScale/uFrameSat` + 饱和/暗部权重；`[55]=0` 下标定 `k`。
3. **DFT 路径 B**（`kFsMfnrFft`）：归一 + σ×ratio² + 饱和处理；`[55]=1` 下同判据。
4. **判据回归**：`hi240 ≤ 0.5%`、`aeEv ∈ [−0.3, +0.1]`、暗部 σ ≤ 1.1×、`confMed ≥ 0.95`、
   关 bracket 与 0.11.64 逐字节一致。

## Refs

- 设计：`docs/engine-bracket-design.md`（198 行，含 5 判据 / 4 步实施 / 风险清单）
- 包：`filmkit-0.11.64-MFNR-FIX.apk`（413 / 0.11.64，签名+版本+JNI 已验证）·
  上一稳定 `filmkit-0.11.63-AE-P995.apk`
- 提交：`ec88bfe`（P99.5）· `5adf0bb`（MFNR 超时）· `1a0622f`（bracket 设计）·
  `4fe80de`（双重欠曝）· `a4e7a90`（自动曝光）· `4e36e24`（诊断包）· `840ffd5`（单图开关）
- 关键代码：`core/engine/src/render/AeMetering.{h,cpp}` · `FrameRenderer.cpp` 的
  `passAutoExposure`/`passFilm` · `CameraController.kt` 的 `hlOn`/`tryProcessBurst`
  · `PhotoViewScreen.kt` 的 `hdrKey`
- PC 一手：`/tmp/pc-bjzhou` 的 `raw_legacy_auto_exposure_solver.cpp`（0.5% 裁切口径）·
  `assets/mgc_ae/*.tflite`（专有资产，不可用）
- 真机日志：`filmkit-logs-20261020-144641.jsonl.txt`（0.11.64 一致）·
  `…-144019`（MFNR 误杀现场）· `…-110924`（双重欠曝现场）
