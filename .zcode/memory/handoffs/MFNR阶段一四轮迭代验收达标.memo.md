---
summary: MFNR阶段一四轮迭代验收达标
created: 2026-09-27 20:05
updated: 2026-09-27 20:05
status: active
---

# MFNR阶段一四轮迭代验收达标

## Summary

MFNR 阶段一（4 帧 Bayer 域对齐+均值，P0 降噪位）从开工到真机验收通过：v0.9.0 闪退 → v0.9.1
采集架构修复 → v0.9.2 等权均值 → v0.9.3 ns÷√N → v0.9.4 1px 量化 → v0.9.5 大 tile+每通道亚像
素双线性，六轮真机数据归因，最终融合降噪 0.44-0.45 ≈ √4 理论、σluma 追平 PC、细节差距 26%→10%，
tag v0.9.5-m4 已推送。

## Done

- **v0.9.0**：全链路落地——`Align.{h,cpp}`（tile 粗到细对齐纯函数）、kFsMfnrPyr/PyrDown/Fuse
  shader、kFsRawDenoiseFused（P0 变体）、submitRawBurst JNI（零拷贝）、Kotlin captureBurst×4、
  设置三档（关/自动暗光 ISO≥600 或 ≥25ms/强制）、「多帧合成中」提示
- **v0.9.1**：闪退修复——maxImages 5→4、帧 1-3 拷贝即 close（仅参考帧持 Image 供 DNG）
- **v0.9.2**：等权均值（自加置信度加权在暗部系统性失效，confMed 0.005 / 回退 9095/9216 tile）
- **v0.9.3**：P0/P4 的 ns 融合路径 ÷√N（单帧 σ 错配致"糊+降噪一般"双重观感）
- **v0.9.4**：位移 1px 取整 + phaseFix 相位补偿（2px 偶数量化残差 ±1px 是细节差距来源）
- **v0.9.5**：tile 32→64 + **每通道网格亚像素双线性融合**（2×2 打包点阵同相位 4-tap，零色度污染；
  python 镜像 27648 样本离线验证，抓出 floor(-1) 外推 bug）；uTileM uniform 替代硬编码
- **验收（无偏口径）**：纯融合 vs 裸帧严格 A/B（双侧无空间降噪）σ 比值 **0.442-0.451 ≈ √4**；
  σluma 1.48 vs PC 1.25（同场景）；彩噪低于 PC；细节 Lap 差距 26%→10%
- **文档**：开发计划 M4-9 第十五节（调研依据/实现/三轮迭代/无偏测量教训全记录）；mfnrShouldTrigger
  三档单测 4 断言；shader 校验 19/19

## Decisions

- [2026-09-27] **tile 对齐直接进阶段一**（用户拍板）——调研定案：HDR+/Night Sight/PhotonCamera
  全部 tile-based，手持下全局单一位移不成立（HDR+ Fig.5）
- [2026-09-27] **MFNR 设置三档**（用户定稿）：prefs `mfnr` 0 关 / 1 自动（暗光触发）/ 2 强制
- [2026-09-27] **融合 = 等权均值**（阶段一）；置信度加权否定——暗部残差与梯度均被噪声主导，
  比值系统性判低；防鬼影（mismatch/运动检测）归 P2
- [2026-09-27] **空间降噪 σ 融合后 ÷√N**（HDR+ σ²/N 同思路）——否则残余噪声漏过、纹理被抹
- [2026-09-27] **tile 64 + 每通道网格亚像素双线性**（用户拍板一步到位）——2px/1px 量化路线退役；
  打包点阵重排无损，R/G 各自插值零混色
- [2026-09-27] **采集 = maxImages 4 + 帧 1-3 拷贝即 close**（HAL buffer 压力是闪退根因）；
  DNG 用参考帧 Image（写完 DNG 再关闭）
- [2026-09-27] **噪声测量必须用与噪声水平无关的选块判据**（低频图梯度）——自适应梯度 mask 跨
  噪声水平比较有幸存者偏差（裸帧 400 块 vs 融合 77K 块，σ 被低估 45%）

## Pitfalls

- **maxImages=5 + 4 Image 全程持有 → HAL 一次预配 125MB graphic buffer**：闪退形态特殊——进程
  存活、Activity 反复销毁、capture() 从未执行（`mfnr.trigger` 与 `engine.stop` 同 FILE 缓冲 FIFO
  排除日志丢失）
- **Logger fwrite 缓冲崩溃时丢日志**：关键路径（拍照入口、mfnr.stage 各阶段）必须
  nativeFlushLogs()
- **GLSL ES 不支持数组初始化器**（desktop 4.2+ 特性）；**usampler2D/sampler 函数参数必须显式
  highp**——两处都是设备端黑屏级错误，glslang 校验器能拦（必须真跑）
- **shell 管道吞退出码**：`validate-shaders.sh | tail` 使 `&&` 链失效，带病 APK 曾被推送（已
  amend 重发）——校验类命令必须判断真实退出码
- **亚像素双线性边界**：负位移+图像左边缘时 floor(s)=-1 使权重 >1 变外推——网格坐标 s 先
  clamp 到 [0, gmax]（CLAMP_TO_EDGE 语义），tap 再 min 收敛
- **DNG = 参考帧底片（未融合）**：raw 域测不了融合结果，融合效果只能在成片（JPG）上测
- **自适应 mask 幸存者偏差**：跨噪声水平比较 σ 无效；无偏口径 = 低频判据选块（块数两组相当）
- 参考实现：timothybrooks/hdr-plus（MIT，DFT 鲁棒合并是阶段二首选参考）；IPOL HDR+ 分析；
  Sabre=MFSR 论文（超分路线，降噪不采用）；KPN 归 M6 后

## Next

1. **轻度锐化层**：filmic 后、OETF 前 luma USM（管线顺序已定稿）——补观感细节，缩小与 PC 的
   finishing 差距
2. **帧数 4→8 评估**：σ 再 ÷√2，采集时长约 +1.2s（PC 帧数更多的差距来源）
3. **阶段二 robust merge**：运动检测防鬼影，参考 hdr-plus 的 DFT 维纳收缩 + Night Sight
   mismatch map（暗光大 tile 已就位）
4. 色温估计精校（旧账）：待灰卡 + 标准光源

## Refs

- 版本：**v0.9.5-m4（versionCode 149）**，tag 已推送；main=22e37ac；根目录 filmkit-0.9.5-m4.apk
- commits：`e9558ab`(v0.9.0) → `db2397e`(0.9.1) → `fc0f810`(0.9.2) → `d0c9077`(0.9.3) →
  `1bcc5de`(0.9.4) → `67a9f4b`(0.9.5) → `22e37ac`(docs)
- 代码：`core/engine/src/render/Align.{h,cpp}`（kMfnrTile=64）、`FrameRenderer.cpp`
  （kFsMfnrPyr/Down/Fuse、kFsRawDenoiseFused、submitRawBurst、finishStillChain 共享链）、
  `Bridge.cpp`（nativeEngineSubmitRawBurst）、`CameraController.kt`（startBurst/帧桶/
  mfnrShouldTrigger/capturePhase）、`RenderClient.kt`（submitRawBurst）
- 日志字段：`mfnr.trigger`（mode/iso/sec）、`mfnr.burst_submit`、`mfnr.capture`（4 帧逐帧曝光）、
  `mfnr.stage`（upload/pyr/align/align_frame/fuse/chain，强制 flush）、`raw.render` 追加
  `mfnr/alignMs/fuseMs/gx/gy/confMed/dMed/dP90/tiles`
- 验收数据：纯融合/裸帧 σ 比值 0.442-0.451（√4 理论 0.500）；融合+P0 σluma 1.48 vs PC 1.25；
  彩噪 5.91 vs PC 6.23；dMed/dP90 0.3-2.6px（手持）
- prefs：`mfnr`（int 0/1/2，默认 1）
