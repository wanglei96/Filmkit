---
summary: 域映射曝光链定案与MFNR立项
created: 2026-09-27 16:10
updated: 2026-09-27 20:05
status: absorbed
---

# 域映射曝光链定案与MFNR立项

## Summary

本窗口从"预览高光爆了"出发，先修 EV 链（Kotlin→JNI 断链 + 统计归一冲突 → EV 改为 filmic 后的显示亮度补偿、撤 HAL AE 补偿），再删旧 log 域胶片链（域外延展非恒等 = 老补丁篡改 filmic 输入的实锤）；随后把 v0.6.48 的"预览直通"退役，上线**域映射**（预览 ÷ 在线估计的 HAL/RAW 域比 → 与成片同曲线 filmic，真 WYSIWYG）；新增**基准曝光**设置项（±2EV，机型 AE 基准差校准，用户标定 +0.50 验收通过）；局部色调映射 A/B 定案（保留，无噪声代价）；域比提为进程级 static 修冷启动。评估后 **MFNR 立 P0**（优先于 AgX），管线顺序与用户确认定稿。

## Done

- **v0.7.8 EV 重构**（`32f5c64`）：逐像素诊断证实 +0.7EV 成片纹丝不动——HAL 层只执行 +0.1EV、引擎层 `filmParams[0]` 从未被 Kotlin 赋值（断链）、且 EV 乘在统计前会被 auto 黑点归一。修：EV=显示亮度补偿（kFsFilm 影调链后 ×2^EV）、Kotlin `setEv`/`setFilmParams` 维护 [0]、撤 `CONTROL_AE_EXPOSURE_COMPENSATION`；+0.7EV 室外 P50 125.6→153.6（线性比 1.593≈2^0.7）
- **v0.7.9 清理**（并入 `d19a877`）：删旧 log 域胶片链（±4EV 编码+filmCurve contrast/toe/shoulder/cross——域外半斜率延展非恒等，pLo≈-5.6EV 被压缩后才进 filmic，标定域与渲染域不一致）；删恒等的「色彩外观层」设置项（UI+prefs+applyLookPref）；blackEv/whiteEv 日志 substr 放宽修 filmic_verify 假阳性；脚本迁 `tools/tuning/`（whole_cmp/sky/filmic_offline/filmic_verify/filmic_preview），删旧引擎专属脚本（ag_scan/desat_scan/chain_probe/preview_domain）；test_temp 只剩样片；CLI 工具 realpath 消毒
- **v0.8.0 预览域映射**（`d19a877`）：直通退役——HAL 显示域直通×EV 把预览中位推到 229/255（域差 1.70–3.87× 是根，直通从未解决只是掩盖）。新链：`filmicTone(lin / uDomainRatio × uExposure)`，域比在线估计（pv_mid_ema_ EMA α=0.3 / 静拍配对 / clamp 1.2–4.5 / 初值 2.0）；auto_black_ev 关时预览与成片**严格同曲线**；filmicTone 输出有界 → 预览结构上不可能爆白。真机验收通过
- **v0.8.1 基准曝光**（`9621c69`）：设置页「基准曝光」滑杆 −2..+2EV（EvSliderRow 复用），`FilmParams[0] = base_ev + 用户EV`（applyBaseEvPref 哨兵 + 统一合成）。语义 = Lightroom/DNG BaselineExposure。**用户标定 +0.50 验收通过**（EV 归 0 时 raw.render ev:0.498）
- **v0.8.2 域比持久化**（`48ed77d`）：nativeEngineStart 每次 new FrameRenderer → ratio 随会话归零（日志实测长期钉 2.0）→ `g_pv_mid_ema/g_domain_ratio` 提为进程级 static；App 冷启动回落 2.0、拍一张收敛
- **局部色调映射 A/B 定案**（同场景同曝光 ISO 50，lt 0.25 vs 0）：P5/P25/P50 +3.4/+5.6/+6.6（P5 相对 +31%）、P95 ±0（高光零影响）；噪声 σ 0.68 vs 0.71、彩噪 0.38 vs 0.47（无代价）；彩色 C* 11.48 vs 10.94、a*/b*≈0（无偏色）→ **保留**，"噪声爆炸"是 0.6 强度+旧曲线时代的问题
- **MFNR 评估与立项**：vs AgX 的取舍 → **MFNR P0 优先**（彩噪根治 √N SNR、可砍 P4 色度平滑恢复色度边缘；AgX 是无具体痛点的风格重构，会作废三轮标定，放可切换引擎选项待 MFNR 后）；分阶段：P1 Bayer 域平移对齐+N=4 均值（OIS 残余小、1.0–1.5s）→ P2 金字塔对齐+运动检测防鬼影
- **管线顺序与用户确认定稿**：多帧融合(Bayer) → 黑阶+WB → 去拜耳 → LSC → 高光重建 → CCM → 局部色调映射(可选) → filmic/AgX → 基准曝光+EV → 高光滚降 → **轻度锐化(新增,线性显示域 luma USM)** → OETF → 3D LUT → 颗粒 → JPEG。关键域约束：LUT 必须 OETF 之后（42 个 .lut 设计域 sRGB）；锐化不能在 sRGB 后
- **mimosa 插件禁用**（用户授权）：官方市场 ZIP（09-16 装入，非用户配置），L3 git gate 的 6 项 path-traversal 为 CLI 工具误报且无豁免通道 → `~/.zcode/cli/config.json` enabledPlugins=false（备份 config.json.bak-mimosa），**重启 ZCode 生效**；本会话提交走 plumbing（write-tree/commit-tree 也被拦 → hash-object+update-ref）
- 全部推送 GitHub（`bed03de..48ed77d`，11 个提交含 v0.7.3–0.7.7 未交接系列），tag **v0.8.2-m4**（versionCode 143）

## Decisions

- [2026-09-27] **EV = 显示亮度补偿，单一来源**（filmic 之后 ×2^EV，统计豁免）；HAL `CONTROL_AE_EXPOSURE_COMPENSATION` 撤除（X300 对 RAW 流打折且双源无法归因；DNG 底片保持原始曝光）
- [2026-09-27] **预览直通退役，域映射上线**——直通只是掩盖域差；预览走与成片同曲线 filmic（auto_black_ev 关时严格一致，开时预览按 HAL 域自标定近似）
- [2026-09-27] **基准曝光做成设置项**（用户拍板）：±2EV 覆盖机型间差异（X300 实测最大 0.9），默认 0，对齐参照后 EV 滑杆归零
- [2026-09-27] **删除旧 log 域胶片链与色彩外观层设置项**——老补丁影响 filmic 的实锤：域外延展非恒等；恒等层设置项只控制空操作。FilmParams 槽位保留（契约不移位），引擎不再消费
- [2026-09-27] **局部色调映射保留**（A/B 数据健康），去留不再议，等逆光场景实战反馈
- [2026-09-27] **MFNR 立 P0，优先于 AgX**——AgX 归入"可切换渲染引擎"选项，等 MFNR 落地、降噪策略更新后再评估影调层
- [2026-09-27] **管线新增"轻度锐化"层**（线性显示域、luma USM，OETF 前），待 MFNR 后实现
- [2026-09-27] **DNG 标准色彩链（ccm_std）保留**——色准部件与影调引擎正交，A/B 开关保留

## Pitfalls

- **C++ uniform 接好 ≠ 链路通**：v0.7.7 只在 C++ 侧接了 uExposure，Kotlin→JNI 的 FilmParams[0] 无人赋值，`exp2(0)=1` 静默无效——跨语言参数必须端到端验证（raw.render 加 ev 字段才暴露）
- **乘在统计前的曝光会被自动标定归一**（auto 黑点跟分位漂移）——一切"用户曝光意图"必须放统计之后；与旧链 `baseTone(linMid×ag)≡baseTone(kTarget)` 同型
- **旧 log 域链的域外延展不是恒等**：±4EV 外半斜率压缩悄悄改变 filmic 输入（pLo≈-5.6EV 的最暗 0.5%），"恒等的旧层"在域外是失真源——删链而非保留
- **直通掩盖域差**：HAL 域与 RAW 域差 1.7–3.9×，EV=0 时亮度巧合接近；任何叠加放大（如 EV）立即暴露
- **Mimosa git gate 的 env 传不进 hook 进程**（继承宿主）、realpath 消毒不改变其 Python profile、MCP 密封扫描结论不被消费；plumbing 里 commit-tree 也被语义识别，只有 hash-object+update-ref 可行
- **成员变量跨 engine 会话丢失**：nativeEngineStart 每次 new FrameRenderer，跨会话状态必须进程级 static 或外部持久化

## Next

1. **MFNR 阶段一立项开工（P0）**：Bayer 域平移对齐（相位相关/局部搜索）+ N=4 时域均值，插在 P0 降噪位（demosaic 前）；采集 4 帧 burst + 进度提示；验收 = chroma-denoise-eval 三指标 + 真机暗光 A/B（目标：彩噪根治后砍 P4 色度平滑、色度边缘恢复）
2. **轻度锐化层**：filmic 后、OETF 前，luma USM（轻度），MFNR 后做
3. **可切换渲染引擎**：我们的 filmic / 哈苏式 / AgX 三选，设置里切换（AgX 实现放此后）
4. **真机验收 v0.8.2 的 ratio 持久化**：离开相机页再回来，preview.filmic 的 ratio 应保持上次值不回落 2.0
5. **色温估计精校**（旧账）：待灰卡+标准光源

## Refs

- 版本：**v0.8.2-m4（versionCode 143）**，远端 main=48ed77d、tag v0.8.2-m4（6 个 tag）；出包 filmkit-0.8.2-m4.apk
- commits：`32f5c64`(0.7.8) → `d19a877`(0.7.9+0.8.0) → `9621c69`(0.8.1) → `48ed77d`(0.8.2)
- 代码：`FrameRenderer.cpp`（kFsFilm 域映射/域比 static、kFsOut、无 uContrast/uCross）、`CameraController.kt`（setEv/setFilmParams 合成 base+user、applyBaseEvPref）、`SettingsScreen.kt`（基准曝光 EvSliderRow）、`tools/tuning/`（5 脚本）、docs/开发计划.md §十~十四（v0.7.4–0.8.2 全记录）
- 分析工具：`tools/tuning/whole_cmp.py`（全图对比）、`sky.py`（天空 C*）、`filmic_verify.py`（spline 自检，日志 substr 已修精度）
- 关键数据：域比实测 1.70–3.87×、用户域比收敛 2.19；+0.7EV 线性比 1.593；AE 基准差室外 0.67EV/室内 ~0.9EV；局部色调映射 A/B 全表见开发计划 §十四
