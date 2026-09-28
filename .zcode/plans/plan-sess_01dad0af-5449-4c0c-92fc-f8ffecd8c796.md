# MFNR 阶段一实施计划（v0.9.0-m4）

## 调研结论（设计依据）

- **HDR+**（Hasinoff et al. 2016，`docs/research/07` 有逐字取证）：对齐 = **2×2 Bayer 块平均→灰度**（12MP→3MP），四层高斯金字塔 coarse-to-fine，tile 32×32（该域），最大位移 64px/支持 169px；**恒定曝光** burst 2–8 帧使对齐鲁棒。
- **Night Sight**（Liba et al. 2019, arXiv:1910.11336）：tile 16/32/64 **随噪声增大**（暗光 64）；参考帧取最锐；合并用 DFT 维纳收缩 + mismatch map（式 7/8）——防鬼影是 P2 内容；方差 σ²/N（√N SNR）；手持单帧上限 333ms。
- **开源**：IPOL《Analysis and Implementation of HDR+》（AGPL，只学算法）、timothybrooks/hdr-plus、PhotonCamera（eszdman，GPL-3.0，GPU/hybrid 对齐 + MFNR，**算法可学代码不可抄**——沿用 RCD 先例）、kunzmi/ImageStackAlignator。
- 结论：**tile 级金字塔对齐是业界共识**（用户已拍板直接进阶段一）；融合阶段一取空间域加权均值，DFT 合并与运动检测留 P2。

## 设计定稿

**管线**（MFNR 生效时；关闭走现有路径不变）：
```
4 帧 RAW（captureBurst，恒曝光同 request）
→ [GPU/帧] kFsMfnrPyr：2×2 Bayer 块平均 → 1/2 灰度(R16F) → kFsMfnrPyrDown：→L2(1/4)→L3(1/8)→L4(1/16)
→ [CPU] glReadPixels L2-L4（~0.5MB/帧）→ alignTiles()（新文件 Align.cpp，纯函数无 GL 依赖）：
    L4 全局粗搜 ±16 → 作每 tile 初值 → L3/L2 每 tile ±2 精化
    输出：tile 位移场（1/2 域整数 ×2 = mosaic 域偶数位移，**CFA 相位天然安全**，无亚像素）+ 每 tile 置信度
→ [GPU] kFsMfnrFuse：4 帧 RAW + 位移场纹理(RGBA16F, RG=位移/B=权重, 双线性) →
    每帧线性化+黑阶+白阶+WB（复用 P0 的 raw0 同式，统一用帧 0 元数据）→ 置信度加权均值 → tex_fused_（R16F mosaic）
→ [GPU] kFsRawDenoiseFused：P0 变体（读 float mosaic，bilateral 与现 P0 相同）→ tex_mos_
→ RCD P1–P3 → P4 → filmic → … 现有链**零改动**
```
关键常量：N=4；tile 32×32（1/2 域）= 64×64 mosaic；位移场 ≈64×48 tiles；融合帧 0 恒为参考（位移 0、w=1）；残差超限的 tile 回退参考帧（防鬼影兜底，P2 完整版）。

**内存增量**（~115MB，ensureMfnrTargets 按需建+复用）：3 张 RAW R16UI（帧 1-3，帧 0 复用 raw_tex_）+ 金字塔 ~14MB + tex_fused_ 25MB + 位移场。X300 可承受。

**触发（用户定稿：设置三档）**：prefs `mfnr`（int，默认 1=自动）——**0=关 / 1=自动 / 2=开（强制）**，照「RAW 降噪」三态循环行的既有模式。判定逻辑（纯函数 `mfnrShouldTrigger(mode, rawMode, isRcd, iso, exposureNs)` 供单测）：
- mode 0 → 永不触发（路径与现状完全一致）
- mode 1 → rawMode && demosaic=RCD && 暗光（预览 live 值：ISO≥600 或曝光≥25ms，常量可调）
- mode 2 → rawMode && demosaic=RCD 即触发（白天也 4 帧，便于随时 A/B）

## 实施步骤

1. **C++ 引擎**：新文件 `core/engine/src/render/Align.{h,cpp}`（alignTiles 纯函数，**加进 CMakeLists 显式源列表**）；FrameRenderer.cpp：新 shader（kFsMfnrPyr/PyrDown/MfnrFuse/RawDenoiseFused，全部写完整字符串——**保证 validate-shaders.sh 自动覆盖**）、ensureMfnrTargets、passMfnr*、submitRawBurst + 抽取共享后半段复用不复制；Bridge.cpp 新 JNI；日志 `raw.render` 加 mfnr/alignMs/fuseMs，新事件 `mfnr.align`（全局/tile 位移统计、回退比例）、`mfnr.capture`（4 帧逐帧 ISO/曝光）。
2. **Kotlin 采集**：CameraController——maxImages 2→5（rawMode）、acquireNextImage 替换 acquireLatestImage（防丢帧）、capture() 触发判定、captureBurst 4×同参 request、4 帧 Image+Result 桶（同 looper 串行按计数配对）、5s 超时兜底（释放+解锁快门+日志）、DNG 用帧 0、Compose state `capturePhase`；RenderClient/EngineBridge 加 submitRawBurst。
3. **UI/设置**：SettingsScreen「RAW 降噪」后加「多帧融合」**三态行**（关/自动/开，prefs mfnr，日志 settings.mfnr `{"mode":$v}`，说明"暗光自动连拍 4 帧合成降噪，M 档 RAW 生效；「开」= 每次都 4 帧"）；CameraScreen 取景框内加「多帧合成中…」（capturePhase==2）。
4. **质量闸**：`JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest` + `bash tools/validate-shaders.sh` 全绿；新增触发判定三档单测；出包前 `rm -rf app/build/outputs/apk`。
5. **版本与文档**：versionName 0.9.0-m4 / versionCode 144 / FilmKitApp.VERSION 同步；`docs/开发计划.md` M4-9 组内加「#### 十五、MFNR 阶段一」；出包 filmkit-0.9.0-m4.apk，commit+tag v0.9.0-m4 推送。
6. **真机验收**（用户配合，清单如下）。

## 验收清单

- 冒烟：`mfnr.capture` 4 帧到齐、逐帧曝光一致、采集 ≤2s、无快门锁死
- 对齐：`mfnr.align` 位移合理（静止≈0、手持小幅）、回退比例不高
- 画质 A/B（暗光同机位，自动/关各 3+ 张成片；强制档便于白天功能验证）：暗部噪声/彩噪显著下降（目标趋近 √4≈2×）、色度边缘优于现 P4、无明显鬼影错位
- 回归：mode=0 时画质时延与 v0.8.2 无差；白天 mode=1 不触发；DNG 仍为原始帧 0
- P0/P4 是否因 MFNR 下调参数：验收数据出来后单独决策（本轮不动）

## 风险与对策

- X300 HAL 对 RAW captureBurst 的 in-flight 限制 → 首装冒烟日志定位；退化路径为逐发 4×capture()（同 request）
- 帧间 AE 漂移 → 逐帧曝光日志验证；若漂移则第二步加"锁定首帧曝光"（AE off 直填）
- 弱纹理对齐失败 → 置信度回退参考帧，最坏等效单帧不劣于现状
- P0 变体若用字符串拼接会逃过 shader 校验器 → 全部完整字符串规避