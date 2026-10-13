---
summary: 轻度锐化与MFNR阶段二十轮迭代
created: 2026-09-28 10:45
updated: 2026-09-28 19:30
status: archived
---

# 轻度锐化与MFNR阶段二十轮迭代

## Summary

完成两件事 + 一场调查：① 轻度锐化层 v0.9.6（luma USM，用户观感验收通过，未 tag）；
② MFNR 阶段二 robust merge 从立项到 v0.10.12 共十轮真机迭代（mismatch map → 位移判据
→ 诊断插桩定案 → 一致性门控，**手部达标（用户确认）、暗光融合预算恢复**）。
③ 对标 PC 全链路调查：PC 用 darktable filmic 默认固定参数（与我们曲线一致）+
**sceneExposureGain 场景曝光归一**——`base_ev` 常数补丁的通解方向已定，待立项。

## Done

- **v0.9.6-m4 轻度锐化**（`dea6b33`）：kFsOut 内滚降后、OETF 前 luma USM（3×3
  1-2-1 高斯、乘法式相对增益保色度、邻域同过 rolloff、±0.4 限幅）；FilmParams[39]
  （kCount 40）；prefs `sharpen` 默认开；raw.render 加 `sh`。真机 A/B：Lap99.5 ×1.25、
  σ ×1.21（上界含混杂），用户观感通过。**未打 tag**（用户说"不用 tag 直接阶段二"）。
- **MFNR 阶段二**（v0.10.0→v0.10.12，commit `60003ea`→`e4f9da0`，全部已推 GitHub）：
  - **v0.10.0 mismatch map**（Night Sight 式）：kFsMfnrMismatch pass，m=d²/(d²+s·σ²)，
    采样与 Fuse 同构（linSub）；CPU 算 w 写权重纹理。调研证实 **hdr-plus 实际不是
    DFT**（2× 下采样 L1 反比加权），且 HDR+ Fig.6 只批"朴素 DFT"。
  - **v0.10.1 短参考帧**（帧 0 压短 min(AE,15ms)+ISO 守恒，后加门控）；**v0.10.2
    子块 max**；**v0.10.3 32px cell 网格 + 独立权重纹理（RGBA8 LINEAR）**；
    **v0.10.4 暗光门控 + 压缩比上限 1.5× + m 场膨胀**；**v0.10.5 位移幅度判据
    （m_mot）+ 分段权重**；v0.10.6 插值（缺肉失败）；**v0.10.7 三步重构（tile 投票
    + 3×3 max 传播 + box 平滑）→ 手部达标**；v0.10.8 残差衰减+中值（错误假设）；
    **v0.10.9 诊断插桩（misD/dilD/motD/msmD 每帧分布）→ 定案误报 100% 来自位移判据**；
    **v0.10.10 位移一致性门控（|off−3×3 中值|>6px 清零）+ 压短门控修正（range.upper
    触顶判定）** → motD 高 m 0-1%、neff 3.58 恢复。
  - **v0.10.12 ISO 滑杆修正**：id0 `SENSITIVITY_RANGE`=50..19200（用户对——参照 PC 语义，
    `MAX_ANALOG_SENSITIVITY`=11467 无参考价值）；全 id 探针 `camera.iso_caps_id`；
    曝光量守恒反算（iso clamp 后 sec 反算，修 ref 欠曝 0.26EV）。
- **对标 PC 调查（数据级）**：PC 暗光墙噪声 σ0.29 vs 我们 2.34（**8×**，亮度仅差 15%）；
  PC 降噪 = **GCam SpatialDenoise 移植**（`GlesMgcRawSpatialStacker.kt` 9919 行 GLES，
  噪声功率谱维纳收缩）；PC 亮度链 = **darktable filmic 默认固定参数**（black -7.65/
  white 4.56/power 3.6148/中灰锚定，**与我们关自适应后逐位一致**）+ tone 前
  `sceneExposureGain`（场景曝光归一）+ `DngBaselineExposure`。

## Decisions

- [2026-09-28] **手部验收标准（用户定义）**：允许自然拖影（曝光内运动模糊是物理），
  **不容重叠错误**（错位叠影/缺肉/锯齿）。据此权重改分段：m≤0.10 全融合（保细节）、
  m≥0.35 硬清零。
- [2026-09-28] **"细节优先"不可动摇**（用户）：拒绝"强"降噪档（细节丢失太多）；
  噪声与细节冲突时选细节。PC 的标准（保边强降噪）是认可的标杆方向。
- [2026-09-28] **亮度通解 = 对标 PC 的 sceneExposureGain 场景曝光归一**，替换
  `base_ev` 常数（它是在缺该环节时的补丁）。曲线不用动（两边 filmic 参数已一致）。
- [2026-09-28] **ISO 滑杆/手动 ISO = SENSITIVITY_RANGE（19200）**，MAX_ANALOG 仅作
  "别进数字增益"判断——对齐 PhotonCamera 语义。
- [2026-09-28] **压短门控 = liveIso 未触 ISO 顶（range.upper）且 isoRef 不被 clamp**；
  曝光量守恒优先于快门锚（clamp 后反算 sec）。
- [2026-09-28] **频域降噪专项待立项**（用户批评"不要什么都简单化"后重开）：
  GCam SpatialDenoise 思路（功率谱维纳收缩）的工程化评估，GPU 成本必须实测不能拍脑袋；
  与"场景曝光归一"专项并列，优先级用户定。
- [2026-09-28] **车次：帧数档位（8 帧）** 仍在队列（原顺序 1-3-2 的第 3 项）；
  帧数到 8 后再评估暗光压短（噪声预算才够）。

## Pitfalls

- **"看日志推机制再打补丁"两轮全错**（v0.10.8 残差衰减/中值滤波基于"误报是散点"的
  错误假设，而 misD 实测残差本来就干净）：**机制假设必须先用插桩/实验验证再改代码**。
- **同型错误再犯两次**：黑点方向（以为关自适应会压暗，实测更亮——darktable 黑点越深
  暗部映射越高）；亮度归因（以为 auto blackEv 是暗光偏亮主因，复算开关不改变中位）。
- **插值会稀释信号**（v0.10.6 逐 cell 插值把大位移抹平 → 运动边缘 mag 掉到阈值下 →
  缺肉）；**tile 级方波会产生锯齿**（v0.10.5）——正解 = tile 投票 + max 传播 + 平滑。
- **位移判据的假峰**（弱纹理/暗部 SAD 无区分度，argmin 随机落在搜索边界 ±8-15px）
  是暗光误报的唯一来源（插桩定案）；一致性门控（邻域中值）是正解。
- **对比实验的混杂**：A/B 亮度不同（PC 暗 24%）时噪声绝对 σ 不可直接比；先做 EXIF/
  曝光量核对（PC 曝光量 60933 vs 我们 54870——**PC RAW 更亮而成片更暗**）。
- **简化方案的代价**（用户严厉批评）：阶段二立项时"DFT 维纳 GPU 不现实"的判断过度
  保守且替用户做了取舍；性能账必须认真做（16×16 分块 FFT 在 GPU 是成熟技术）。
- **权重回写路径的坑**：v0.10.3 起权重独立成图（tex_w_，LINEAR 可用 8bit 纹理），
  位移场 z 通道退役；m_motion 曾因"tile 值复制到 cell"产生 128px 方波锯齿。

## Next

1. **亮度验证（用户拍两张）**：base_ev 归零 + 自适应影调关（最接近 PC 配置）拍
   暗光 + 白天各一张——预期暗光中位 ~116（与 PC 98 差 18% = sceneExposureGain 的量）、
   白天验证方向反转。数据回来后立项"场景曝光归一"专项。
2. **专项队列（用户定优先级）**：① 场景曝光归一（对标 PC sceneExposureGain）；
   ② 频域降噪对标（GCam SpatialDenoise 简化 → GLSL 实测预算）；③ 帧数档位（8 帧）。
3. **101754 残余**：暗光静止场景 msmD 高 m 17-37%、neff 2.97（101741 已 0-1%/3.58）
   ——s/阈值可再收。
4. **阶段二收尾**：手部达标 + 暗光门控已验证，待用户最终确认后打 tag（v0.10.12）。
5. PC 对比遗留：DNG `BaselineExposure` vivo 未写入（PC 的该环节对我们是空）；
   PC 的 `sceneExposureGain` 算法细节（HdrReferencePass）未读完。

## Refs

- 版本：**v0.10.12-m4（versionCode 163）**，根目录 filmkit-0.10.12-m4.apk；main=`e4f9da0`
  已推送（14 commit 本轮同步）。**未打 tag**（等阶段二最终确认）。
- commits：`dea6b33`(v0.9.6) → `60003ea`(0.10.0) → `00ec5cd`(0.10.1) → `3d71ff1/04a6c63`
  (0.10.2) → `69045ad`(0.10.3) → `b178b8e`(0.10.4) → `a77f6f9`(0.10.5) → `8029098`(0.10.6)
  → `d16a50d`(0.10.7) → `212e84f`(0.10.8) → `94c39c9`(0.10.9) → `be07c06`(0.10.10) →
  `e4f9da0`(0.10.12)
- 代码：`FrameRenderer.cpp`（kFsMfnrMismatch/kFsMfnrFuse/kFsOut 锐化、m_mot 一致性门控、
  诊断字段）；`Align.cpp`（gradTile 输出待加）；`CameraController.kt`（startBurst 短参考帧、
  isoRangeUpper、isо_caps 探针）；`FrameRenderer.h`（tex_w_/mismatch 网格）
- 日志字段新增：`motMed/resFade/nsSng/misD/dilD/motD/msmD`（m 链分布，每帧三档占比）、
  `altIso/altSec`、`camera.iso_caps_id`（per-id range/maxAnalog/boost）、`rn`
- PC 参照（/tmp/photoncamera，bjzhou/hinnka 版）：`MultiFrameExposurePlanner.kt`（长帧
  10ms 上限+模拟增益 cap）、`DarktableFilmicToneAlgorithm.kt`（filmic v5 固定参数）、
  `RawToneMappingGl.computeFilmicToneCurveUniforms`（中灰锚定：linearIntercept =
  greyDisplay − contrast×greyLog）、`MeteringSystem.kt`、`GlesMgcRawSpatialStacker.kt`
  （GCam SpatialDenoise 移植 9919 行）
- 验收数据：手部达标（v0.10.7，用户确认）；101741 motD 高 m 0-1%/neff 3.58；PC 对比
  墙 σ 0.29 vs 2.34、叶缘 p99.5 17.1 vs 20.8；PC HEIC EXIF 2011/30.3ms vs 我们 1829/30ms
- 待验证清单：base_ev=0 + 自适应关（暗光/白天两拍）；101754 残余优化
