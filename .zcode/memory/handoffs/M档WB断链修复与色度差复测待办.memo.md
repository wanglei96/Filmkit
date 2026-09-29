---
summary: M档WB断链修复与色度差复测待办
created: 2026-09-29 12:00
updated: 2026-09-29 12:00
status: absorbed
---

# M档WB断链修复与色度差复测待办

## Summary

用户报 M 档两问题：① 手动白平衡对 M 档无效——实锤 bug，根因 = RAW 成片链的
WB 增益只取 asShotNeutral、FilmParams 的用户补偿无入口（预览 OES / YUV 静拍在
pass-1 shader 乘 uWb，RAW 链漏接），已修（`1c437dc`）；② M 档饱和度偏低——排查
确认即"色度差"任务本身（sat 参数链路三条链共用、无第二 bug），主项已由同日早些的
`6d0dbab`（+0.7EV 工作点）修复，用户实测包大概率仍是 ≤176 旧版。**出 v0.10.26-m4
(177) 待真机复测两项。**

## Done

- **M 档 WB 断链修复（v0.10.26-m4，commit `1c437dc` 已推）**：
  `prepareRawCommon` 增加 wb_r/g/b 参数，用户补偿以 G 归一（`gains[0]*=wb_r/wb_g`、
  `gains[2]*=wb_b/wb_g`）乘进 asShotNeutral 增益；`submitRaw` 与 `submitRawBurst`
  （MFNR）两个调用点全覆盖；防呆：单值 ≤0 跳过该通道。
- **raw.render 日志加 `wb:[r,g,b]` 字段**：真机拍一张即可核对成片链实际收到的
  补偿值；与预览 OES 的 uWb 不一致即"M 档 WB 与预览不符"的直接证据。
- **饱和度偏低排查定性**：`uSat`（FilmParams[20..22]）经 `passFilm` 三链共用，
  sat_comp 滑杆→prefs→拍摄前批刷新→数组的链路在 M 档完整；saveJpeg 直通无色彩
  变换；确认无第二个独立 bug ⇒ 就是色度差任务（同亮度 C* 低 17~21%）。
- **出包**：`filmkit-0.10.26-m4.apk`（29.1MB）放仓库根目录（用户要求打包放项目根）。

## Decisions

- **WB 补偿作用点 = raw 域（黑阶扣除后、CCM 前）**：darktable temperature /
  libraw scale_colors 的标准位置；与预览 OES / YUV 静拍在 pass-1 乘 uWb 语义一致
  （wb_g 恒 1.0，G 归一除以 wb_g 保证数学完备、不改整体亮度）。
- **DNG 底片口径不变**：引擎侧 WB 补偿不进 DNG（延续 v0.5.9 决策），只动渲染链。
- **饱和度残余不预调参数**：+0.7EV 主项修复后预期红杯 C* +6~8%，残余 ~6% 的出路
  （sat_comp 风格层标定 / 灰卡 CCM 精校）等 177 真机数据，遵守"实测数据说话"铁律。

## Pitfalls

- **引擎侧参数三条渲染链只接两条 = 静默漏第三条**：WB 补偿在预览 OES（`u_oes_.wb`）、
  YUV 静拍（`u_still_.wb`）都在 pass-1 下发，RAW 静拍链却无人接——用户可感知（预览
  变、成片不变）但构建/单测全绿。新增引擎参数必须按"预览 / YUV / RAW"三条链逐一
  核对（与 2026-09-27「Kotlin→JNI 断链」同族教训，那处断在语言边界，这处断在链间）。
- 构建 JDK：系统默认 Java 8 跑不动 AGP 8.13，用
  `JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`（JDK 23）。

## Next

1. **177 真机复测（重点 = WB）**：M 档拉 WB 滑杆，成片应随预览同步变色，raw.render
   的 `wb` 字段应有非 1 值。（+0.7EV 的饱和度收敛**已被同窗口前的另一会话同刻三方
   验收通过**：红橙族 C* 差 14.5%→−1.1%、亮度差 0.57EV→0.04EV，见
   handoffs/配平落地与弱彩对标转实物.memo.md）
2. **弱彩对标转实物**：用户已拍板"色准对照物 = 实物本身"（成片比实物淡是事实），
   ColorChecker ΔE2000 专项已立项——残余饱和度问题的新出口，指针见同上快照。
3. **亮度配平**：LSC(+0.52EV) 与 base_ev(+0.7) 的叠加复核——不能用全局 EV（已证
   无效），从曲线工作点/采集曝光入手；高光不足 0.3~0.66EV（PC 采集曝光更高）同批看。

## Refs

- 版本：**v0.10.26-m4（177）**；commits `1c437dc`（本轮）+ `6d0dbab`/`38ef8c5`
  （同日色度差主项与 LSC damp）；均已推 main，未打 tag
- 代码：`core/engine/src/render/FrameRenderer.{h,cpp}`（prepareRawCommon /
  finishStillChain 日志）、`app/build.gradle.kts`（版本）
- 产物：仓库根 `filmkit-0.10.26-m4.apk`（`*.apk` 已 gitignore 不入库）
- 对照数据：同 DNG 双渲染 C* 37.9(PC 渲我们 RAW) vs 33.1(我们)；std 链 32.5 /
  hal 29.2 / PC 37.2；中性区净差 +0.57EV
