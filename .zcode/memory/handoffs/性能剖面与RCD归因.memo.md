---
summary: 性能剖面与RCD归因
created: 2026-11-12 19:49
updated: 2026-11-12 19:49
status: absorbed
---

# 性能剖面与RCD归因

## Summary

性能专项首轮把账算清了：`dirMs` 的口径一直把 HR+P0 算进方向场，拆开后 RCD 只有 65~85ms（**不是热点**）；真正的大头是 DFT 1.2~1.5s、P0 300~350ms、P4/PC3D/D1 0.5~1.1s。编码换 libjpeg-turbo 后 492→190~290ms。同场景快门→落盘从 8.03~8.10s 降到 6.15~7.27s。体感卡顿首次被量化：快门后 4 次 218~264ms 画面冻结，`preview_gap` 的 400ms 阈值一直在漏报它。

## Done

- **计时口径修正（0.13.5/0.13.6）**：`d0` 原打在 HR/P0 之前、`d1` 打在 P1 的 glFinish 之后 ⇒ 旧 `dirMs` 909~1486ms 实为「HR+P0+P1」合计。现拆为 `preMs`/`p0Ms`/`dirMs`/`dirGpuMs`/`gGpuMs`（GPU 用 `EXT_disjoint_timer_query`）。
- **冷机支架基线 3 张（4080×3072、4 帧 MFNR+HDR）**：DFT 1201~1561、P0 300~352、HR 212~250、P4+PC3D+D1 542~1131、RCD 39~66（GPU 31~66）、P2 9~33、闭环 378~423、编码 190~290；渲染段 total 5162~6523ms。
- **编码换 libjpeg-turbo 4:4:4**：复用构建里已有的 `jpeg-static`（libultrahdr 依赖），`JCS_EXT_RGBA` 零拷贝、采样因子钉死 1×1，stb 保留回退。`photo.encode` 492~508 → **189/289/275ms**。
- **闭环读回 1/2 → 1/3**：12.5MB → 5.6MB（样本 310 万 → 140 万），`dispMs` 不再出现 713ms 尖峰。
- **对齐 CPU 多线程**：`alignCpuMs` 同场景 198~213 → 164~203ms（先前"633→190"是热降频造成的假收益）。
- **预览卡顿量化**：新 `render.preview_arrive_gap`（派发前记录到达间隔）+ `tools/preview-stall.py`（录屏帧差找画面冻结）。实测快门后 218 / 264 / 232 / 232ms 四次冻结，fps 30→21~26→15~17；`arrive_gap 597` 与 `preview_gap 614` 同时出现的那次是 HAL 停帧（`still_destroy`）。
- **调研**：PC 的 RAW 链路不是 RCD——`RcdDemosaicAlgorithm.kt` 零引用（死代码），真机走 MGC Spatial/Sabre 融合（内部全分辨率绿重建）+ VGN；AMaZE 只在 bundled LibRaw（`dcraw_process.cpp:189` quality=14），服务 `processDngNative` 的 DNG 文件路径。Spektrafilm（rawpy）/SpektraLab（C++ libraw）都只是 LibRaw 调用方，无自研 demosaic。开源无成熟 GPU 高质量实现。详见 `docs/reports/raw-demosaic-benchmark-RESEARCH.md`。
- **两个否定结论**：DFT 现场编译实测 0.5~2.5ms（不必改 init 缓存，且源码两处注释说法相反）；PC 的 strip 条带化套到 P0 后冻结 232→215ms（噪声级，无效）。
- **同场景总时长**：8.03/8.10s（0.13.0）→ 7.27/6.48/6.15s（0.13.9）。

## Decisions

- [2026-11-12] **RCD 方向场不改**：P1 净耗时 65~85ms（GPU 48~66），改半分辨率最多省 50ms，不值得动质量管线。
- [2026-11-12] **不移植 PC 的 Sabre/MGC**：逆向 GCam 专有二进制、许可不明、与 4 帧 DFT 融合强耦合。
- [2026-11-12] **编码换 libjpeg-turbo**（用户授权）：4:4:4 q100 色度误差 p99 从 stb 1.36 变 1.58（/255），4:2:0 是 9.77，安全区内换 2.5× 速度。
- [2026-11-12] **性能取舍先量后改**：DFT 编译、条带化两项都是先测才发现不值得，写进纪律。

## Pitfalls

- [2026-11-12] **跨场景/跨热状态对比会造出假收益**：我先用不同时间段的两次日志得出「对齐省 440ms、编码省 700ms」，同场景严格 A/B 后只有几十毫秒——手机热降频被当成了版本差异。
- [2026-11-12] **`dirMs` 口径错把 HR+P0 算进方向场**：`d0` 打在它们之前，导致 RCD 被误判为最大热点，白跑一轮半分辨率方案讨论。
- [2026-11-12] **`preview_gap` 的 400ms 阈值掩盖体感卡顿**：用户说的卡顿是 200~260ms 的反复断续，日志里一条都没有；固定机位也测不出（画面本来不动），必须录屏。
- [2026-11-12] **源码注释互相矛盾时以实测为准**：`passMfnrFft` 与 init 段注释对「init 创建的 compute program 会不会丢」说法相反，实测编译只 2ms，问题不存在。
- [2026-11-12] **PC 的 strip 手段在 Mali 上无效**：条带 + glFlush 不能让预览 context 插队，冻结只从 232ms 降到 215ms。

## Next

1. **DFT 1.2~1.5s（最大单项）**，三条路待用户拍板：A 换空间域加权融合（`kFsMfnrFuse`，预计 -1s，需 100% 裁剪 A/B）；B overlap 50%→25%（-300~400ms，块效应待验）；C 不动 DFT，转攻卡顿。
2. **拆 P4/PC3D/D1**：`dnMs` 542~1131ms 波动大，defringe 与 D1 没有独立计时。
3. **`preMs` 里 ~340ms 未归因**（satmask / hlInpaint / 其他小 pass）。
4. **卡顿**：查 `EGL_CONTEXT_PRIORITY_LEVEL_IMG` 是否可用（给预览上下文高优先级），这是 Mali 上少数能让预览真插队的手段。
5. 让用户翻一张新片确认 libjpeg 编码观感。

## Refs

- 包：`filmkit-0.13.9-JPEGT.apk`（717）；回退 0.13.0(708)
- 提交：`f8e1b11`（PC 去马赛克纠错）、`833ea35`（计时口径+编码去重+对齐并行）、`812a20f`（结论入记忆）、`6143844`（预览到达/DFT 编译计时）、`a405804`（preview-stall 工具）、`3b831bb`（libjpeg 编码 + 闭环读回）
- 文档：`docs/reports/raw-demosaic-benchmark-RESEARCH.md`；`docs/research/06`（RawLinearRcdPass 误读已改）、`docs/research/15`
- 工具：`tools/preview-stall.py`（录屏帧差 → 画面冻结区间）
- 样片：`test_temp/FILMKIT_20261112-*.jpg`（0.13.x 各轮）
