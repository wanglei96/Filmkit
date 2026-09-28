---
summary: filmic原生件迁移与黑白点自动撤销
created: 2026-09-28 19:30
updated: 2026-09-28 19:30
status: active
---

# filmic原生件迁移与黑白点自动撤销

## Summary

完成 darktable 原生件三轮迁移 + 一次方向纠正：① **原生小波高光重建**（mask/inpaint
noise/à trous/RGB+ratios 两轮全链，v0.10.15 落地、0.10.18 阈值解耦后触发正常）；
② **gamut mapping 完整五步**（色度 min+desaturate 曾被预删，用户铁律后 0.10.17 全量恢复）；
③ 黑白点自动三版真机证伪 → 0.10.19 默认关撤销，**"PC 场景归一"误判纠正（实锤：
sceneExposureGain 是 HDR 链专用，PC SDR 链无场景归一，差距在 MGC-AE 曝光决策）**；
④ 旧 hl_recon 删除（用户拍板，自研简化，小波接管）。
**遗留：原生色度 min 的全局灰化未决**（固定曲线下暗部色度 −17%，uSat 补偿滑杆方案被用户
否决"又打补丁"）。

## Done

- **v0.10.13** gamut mapping 引入（kFsFilm，filmicTone 后）；**0.10.14** 预删 min/desaturate
  （链级模拟泛白归因）；**0.10.17 恢复完整五步**（用户批评"先完整按人家原版做出来再评估
  删不删"→ AGENTS.md 铁律：先 100% 原版跑通→真机→数据评估，禁止预删）
- **v0.10.15 原生小波 HR**：10 shader + 10 张全分辨率纹理（mask/inpaint/blur/HF/recon-
  init/recon-step(uVariant)/norms/ratios/restore/stat）；参数 = darktable 默认（threshold
  2^white×grey、feather 16、noise 0.2、γ/γc 0.5/0.5、β/βc 1/0、δ1、HQ 1 轮、scales
  clamp 1..10）；FilmParams[41]、prefs hr_wavelet、设置页「高光重建·小波」
- **0.10.16** 黑白点成对自动（P0.5/P99.5 默认开）+ HR 移入 passFilm；**0.10.17** 分位
  P0.1/P99；**0.10.18** 钳物理界（black≥−7.65、white∈[2.438,4.556]）+ **HR 阈值恒 1.0**
  （RAW 饱和物理量，与曲线解耦）——HR 触发正常：clipB 137~1435、hrMs 270~304ms
- **0.10.19** 黑白点自动默认关（三版证伪：0.10.16 中间调低/水壶过曝/天空过饱和、0.10.17
  全面糟糕化 power 8.2、0.10.18 power 5.17 无改善）；**0.10.20** hl_recon 删除
- 验证数据：0.10.18 灯管场景粉边"不发红"（用户确认；但主要是旧 hl_recon 关闭+曲线变化）
- 版本链：0.10.13(164)→…→0.10.21(172)，main 全部已推；**未打 tag**

## Decisions

- [2026-09-28] **先 100% 原版跑通→真机→数据评估**（AGENTS.md 铁律；预删原版 = 销毁实验对照组）
- [2026-09-28] **黑白点自动撤销**：自动分位定曲线端点把 power 推离固定曲线 3.62 设计值；
  darktable 的 autodetect 本就是手动拾取器；PC 不用它。固定曲线 = PC 行为，亮度校准回归
  base_ev（+0.50 用户已验收）
- [2026-09-28] **"PC 场景归一"误判纠正**：hdrNetSceneExposureGain = HDR 参考渲染坐标增益
  （RawHdrReferencePass 注释原文 "scalar HDR/SDR ratio"）；PC SDR 链 = 固定 filmic 默认
  参数 + DngBaselineExposure + MGC-AE 曝光决策。"亮度差在 tone 链"归因作废，"场景曝光
  归一"专项撤销，改列 **MGC-AE 复刻**为远期专项
- [2026-09-28] **HR 裁剪阈值恒 1.0**（与 filmic 白点解耦）——"裁剪重建"的物理语义
- [2026-09-28] **hl_recon 删除**（用户拍板）；[29] 槽位保留引擎不消费
- [2026-09-28] **饱和补偿滑杆方案被用户否决**（"针对一个机型标定有什么用？又打补丁？"）
  ——uSat 标定补偿不是正解，灰化问题回到架构层待定

## Pitfalls

- **R16F attachment + GL_UNSIGNED_BYTE readback 在 ES 3.0 不保证**（X300 静默回零）：
  0.10.15/16 的 HR 裁剪统计因此恒 0、HR 两版从未触发——浮点 FBO 回读改 RGBA8 目标
- **自动判据必须钳物理量**：white=P99 无饱和场景掉 1.44EV→power 8.2；black=P0.1 暗光=
  噪声底（预览撞 −16 clamp）；HR 阈值跟 white_ev → 47% 块被重建（clipB=1435/3072）
- **原生色度 min 的跨域误伤是原版行为**：固定曲线下暗部色度 −17%（真机）+ 91% 触发/26.8%
  损失（链级模拟）；darktable 同款（论坛投诉），其工作流靠 color balance rgb（用户手动
  调色板）解——**本链的"灰"无自动解，预删/风格补偿两个方向都被否**
- **shader 注释里的 `)"` 原始字符串终止**（min(orig,fin)" 案例）——GLSL 注释内数字后接
  括号一律全角

## Next

1. **"灰"的正解（用户拍板方向）**：候选 = ① MGC-AE 场景亮度模型复刻（让 min 的语境
   正确，大工程远期专项）② 接受原版行为 ③ 其他思路；uSat 补偿方案已否
2. **HR 时延**：hrMs≈280ms（110 pass 全屏）影响拍照时延，需管线优化评估
3. **0.10.12 vs 0.10.21 色度 A/B**：确认灰化量与"发红"现状（固定曲线 + HR 阈值 1.0 下）
4. **队列**：降噪 NLM（darktable denoiseprofile，PC 692 行 compute 移植可对照，需引擎加
   GLES 3.1 compute 基建）→ MFNR 频域融合（NS 式 7）→ 小件（Phocus USM/运动区补偿/
   参考帧最锐帧）
5. 旧「高光重建」设置行已删；若用户报残留 prefs 无害（无消费者）

## Refs

- 版本：**v0.10.21-m4（versionCode 172）**，根目录 filmkit-0.10.21-m4.apk；main 已推；未打 tag
- commits：`5374dbb`(0.10.13) → `943726b` 前 0.10.14/15 → `eab8260`(0.10.14) →
  `62971df`(0.10.15) → 0.10.16/17/18 → 0.10.19/20 → 0.10.21（见 git log）
- 代码：`FrameRenderer.cpp`（kFsHr* 10 shader、runHighlightReconstruct、gamutMapV5 五步、
  updateFilmicEV 自动分支）、`FilmParams.h`（[35] auto/恒 0 默认、[40] gamut、[41] hr_wavelet、
  [29] 槽位保留）、`CameraController.kt`（applyGamutPref/applyHrWaveletPref/applySatCompPref）
- darktable 源码副本：/tmp/dt-filmicrgb.c、/tmp/dt-gamut.h、/tmp/dt-csic.h、/tmp/dt-bspline.h、
  /tmp/dt-exposure.c；PC 源码 /tmp/photoncamera（DarktableFilmicToneShader.kt、
  DarktableFilmicHighlightReconstructionAlgorithm.kt、RawHdrReferenceMath.kt、
  DenoiseProfileAlgorithm.kt=darktable NLM 移植 692 行 GLES3.1 compute）
- 验证脚本：/tmp/gamut_verify*.py（链级模拟）；日志字段 hr/clipB/hrMs/blackEv/whiteEv/fPow
- 真机样片：test_temp/（0.10.16~18 各轮 A/B）
