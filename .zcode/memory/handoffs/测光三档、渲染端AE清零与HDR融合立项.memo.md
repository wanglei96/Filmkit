---
summary: 测光三档、渲染端AE清零与HDR融合立项
created: 2026-10-24 19:05
updated: 2026-10-24 22:00
status: absorbed
---

# 测光三档、渲染端 AE 清零与 HDR 融合立项

## Summary

0.12.67→0.12.74：新增三档测光（系统/点测/高光优先）+ 长按取景器对焦曝光锁定（黄框 + 提示），
并**删除渲染端 AE**（`g_still_ae_ema` + `decideAe` 不再参与曝光），回到 PC 的"HAL 单套 AE"架构；
拍照后左下角占位 + 查看页"处理中"态落地；18 号 DCP 调研评估为**不做**；
HDR 曝光融合（PC 的 0/+2.2/−1.5 + Mertens）写成独立专项设计文档，P0 从 0.12.75 起。

## Done

- **0.12.67 测光三档**：`MeteringTargets`（纯坐标换算 + 5 条单测）；`CameraController.setMeteringMode/setAeLock/currentMeteringRect`；`AE_REGIONS` 与 `AF_REGIONS` 分离（点按只对焦）。
- **0.12.68 高光优先改 PC 算法**：照搬 `LutRenderer.calculateMeteringResults` —— 32×32 luma 网格 + top2% 动态阈值 + 5×5 簇 + 焦点/历史偏置 + 8% 位移门限；点测光区域 = PC `SPOT_AE_REGION_FRACTION = 0.06`（此前自创 15% 已改）。
- **0.12.69/0.12.70**：预览不再叠上一张 EMA；`nativeSetMeterManual` 走进程快照，静拍实例懒创建也能拿到（`Bridge.cpp` 的 `g_meter_manual` / `g_meter_focus_x/y`）。
- **0.12.71 删渲染端 AE**：`g_still_ae_ema` 删除、`pe.exposure_ev += ae` 删除（`aeEv` 只写日志）；`decideAe` 保留作诊断。
- **0.12.72/0.12.73/0.12.74**：长按 = 对焦+曝光一起锁（黄框常驻 + 顶部"对焦和曝光已锁定"1s）；按快门左下角圆环占位；点占位进查看页显示"正在处理照片…"（`viewerPending` + 轮询 `last_photo_uri`，30s 超时回相机），完成后原地变图。
- **18 号 DCP 调研评估**：结论 DCP 生态不做（详见 Decisions）；AdobeCurve 降级为可选实验。
- **HDR 曝光融合设计**：`docs/engine-hdr-exposure-fusion-design.md`（PC 源码取证 + 4/6 帧策略 + J1–J10 判据 + P0–P4）。

## Decisions

- **渲染端 AE 全删，回到 PC 架构（用户拍板）**：曝光 = HAL AE + planner + baseEv + 用户 EV，逐张独立、预览即成片。`decideAe` 只留诊断，不再改曝光。
- **测光三档 + 分离**：系统默认（AE 区域交给 HAL）/ 点测（6% 框，点按位置）/ 高光优先（动态最亮簇，16% 框）；点按永远只设 AF；长按 = 对焦+曝光锁定，点按自动解锁。
- **DCP 生态不做**：DCP 是"相机原厂色彩身份"不是"胶片 stock"；跨相机套 HueSatMap 是二次校正；破坏 D8/D9"基准中性 + 风格 LUT"分工；Adobe camera matching 不可随包分发。AdobeCurve 只作 P2 可选实验。
- **HDR 曝光融合立项**：采集改 0/0/+2.2/−1.5 + Mertens 逐像素融合（PC 同款），4 帧起步、6 帧封顶；**P0 从 0.12.75 起**，开关默认关。
- **高光优先必须用 PC 的算法**（用户明确"不要自创"）：动态最亮簇 + EMA/位移门限，固定顶部区域被否决。

## Pitfalls

- **懒创建实例必须走进程快照**：`nativeSetMeterManual` 只设当时存在的实例 ⇒ 后建的静拍实例回默认 false，点测光下 `aeEv` 仍 0.22/1.268（日志实证）。修复 = 全局快照 + 实例创建时补（与 LUT/YUV 同款）。
- **任何跨张状态都会让"预览≠成片"**：`g_still_ae_ema` 是上一张的 aeEv，点测光后预览亮、成片暗。用户红线："任何一张照片都应该是独立的"。
- **自创测光不可接受**：第一版高光优先用"固定顶部中央"，用户当场否决；PC 的实际机制是逐像素权重（Mertens）+ `highlightValidity(max3 > 0.96)`，没有场景识别。
- **全屏 overlay 会阻塞构图**：处理中态不能挡住取景器（上一版因此不可接受），必须进独立页面。
- **`private companion object` 与已有 companion 冲突会级联 unresolved**（`MFNR_FRAMES`/`denoiseStrength` 全红）；改用文件级 `private const val`。
- **单次高光斑块（5902）不改算法**：短帧满阱（`bracket.short p999 1.0`）+ 手动压 EV −0.54 + `hlChroma B −0.318` 叠出的重建边界；0612 同参数正常。按纪律等复现再查。

## Next

1. **HDR 曝光融合 P0**（0.12.75）：`bracket.actual.ev[]` 诊断日志，确认真实 EV 与饱和率；再开 P1 采集。
2. **PC HEIC HDR 死链**：`passGainmap` 只在 `!c.ten_bit` 跑 ⇒ HEIC 永远无 gainmap，修链路或删选项（待用户拍板）。
3. **5902 斑块观察**：若同机位复现，查 `hl` 的 inpaint 邻域在窗框横梁处的色度补偿。
4. 暗部局部对比度：用户已明确**不再对标 PC**，挂起。

## Refs

- 设计：`docs/engine-hdr-exposure-fusion-design.md`
- 调研：`docs/research/18-adobe-dcp-engine-eval.md`
- PC 源码：`/tmp/pc-full`（HdrBracketConfig.kt:8-9、GlesYuvStacker.kt:346-460/3451、Camera2Controller.kt:7774-7781、GalleryManager.kt:2960-3090）
- 包：`filmkit-0.12.74-PENDVIEW.apk`（523）；回退稳定版 `filmkit-0.12.66-REVERT-HEADROOM.apk`（515）
- 日志：`filmkit-engine-20261024(1)~(5).log`（vivo 办公套件）
