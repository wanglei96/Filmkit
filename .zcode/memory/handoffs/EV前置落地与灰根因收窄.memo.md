---
summary: EV前置落地与灰根因收窄
created: 2026-09-28 20:13
updated: 2026-09-28 20:13
status: active
---

# EV前置落地与灰根因收窄

## Summary

落地 v0.10.22-m4：EV 语义改为 filmic 前线性域（PC/Darktable BaselineExposure 语义），
预览/静拍统一（修复 EV≠0 的 WYSIWYG 分叉）。随后用户多轮界面 EV 实验（+0.3/−0.3/
+0.507/−1.1）全部无效，证明全局参数改不了观感结构。"灰 vs PC"的差异收窄到区域级硬指标：
白壶区域 PC 亮 1.6×（≈0.7EV）、木桌区域 PC 局部对比 cv+26%/HF+25%；**根因定位未完成**，
用户明确要求先找到根因再动手。

## Done

- **v0.10.22-m4（173）**：kFsFilm EV 前置（`out3 = max(lin,0)×uExposure` 链首、删链末乘）；
  预览分支历来前置、两侧统一后 EV≠0 WYSIWYG 分叉（v0.7.8 引入）一并修复；注释债修正
  （FrameRenderer gamut 段 v0.10.14"已移除"残留、CameraPresets[40]）；`869ffa9` 已推；
  `filmkit-0.10.22-m4.apk`；**未打 tag**（验收版规则）。docs/开发计划.md 补十七·续节
- **离线验证**：EV=0 新旧链逐位恒等（0.00e+00）；EV−0.7 前置死黑 8.0%→11.6%（后置同值
  只平移）——形状语义成立；shader 30/30、构建+单测过
- **min 机制三连**（链级模拟 + PC 源码）：①gamut min 贡献 100% 暗端灰化（V1a 无 min 与
  V0 逐位一致；V1b 无 desat 与 V1 一致）②min 只在**输入域** luma<0.26 触发（<0.14 触发
  99%、0.26 以上 0%）、钳制比 1.19 ③PC 的 gamutMapV5 与 darktable 原版逐行同构
  （Ych_original 同取 tone 前，dt-filmicrgb.c:1890）——PC 的 min 同触发同钳制，min 不是差异
- **排除项**：工作域（BT.2020 vs sRGB，含 PC 同款 Ych 矩阵/输出变换）rel 比 0.94–1.15
  非主因；乘性 EV 下 Ych chroma 严格不变（2e-8）；结构分解（域/EV 位置/rolloff/EV 大小）
  全部 ±10%
- **用户真机实测全部无效**：界面 EV +0.3/−0.3/+0.507/−1.1（ev=-1.1 全区域等比变暗、局部
  对比反降 cv 0.415→0.286）；+0.507 前置把红杯 rel 0.708→0.286（提亮稀释极端案例）
- **区域级同矩形对比**（195052 vs PC_185013，最可信口径）：红杯区域亮度两侧相同
  （0.167/0.166）；木桌区域亮度相同（0.223/0.226）但 PC **cv 0.217 vs 0.172、HF 0.0060
  vs 0.0048**；白壶区域 PC **0.726 vs 0.589（1.6× ≈ PC 引擎默认 0.7EV）**
- **新发现**：PC 有 Photon Local Laplacian 实现（DngPhotonLocalToneMapper.kt，Photon HDR
  路径，SDR 生效条件未确认）；我们的 local_tone（M4-7）是抬暗部（ratio 小的暗部乘增益），
  方向与"局部对比"相反——曾被误当局部对比让用户实测，被用户纠正

## Decisions

- **EV 语义改造（0.10.22）保留**：它是"欠曝采集+曲线内提亮"通路的引擎侧配套件（PC 同语义）
- **全局参数不是"灰"的解**（EV 原位/工作域/曲线参数/rolloff）——用户实测 + 离线模拟双重排除
- **gamut min 保留**（PC 同样带着，暗部灰化是其原版行为）
- **用户要求（铁律级）**：不要再让用户做实验当"实验员"，先自主找到与 PC 不一致的根因

## Pitfalls

- **跨链分布级对比被亮度映射污染**：同一批脚本换校准 g 后结论反转——固定绝对 luma 箱的
  rel 剖面在链间比较不可用；**同矩形区域统计 + 多尺度频段**才是稳口径
- **色相+C* 掩膜有幸存者偏差**：PC"红杯更浓"（掩膜 0.103 vs 0.192）是假象，区域统计显示
  红杯区域亮度两侧相同
- **inv_curve 反推输入亮度不可靠**：shoulder 段失效 + 跨链 CCM 亮度标尺混杂（"PC 红杯
  输入暗 1.5EV"结论作废）
- **把 local_tone 当"局部对比增强"让用户测**：它是抬暗部功能，方向与需求相反（被用户
  纠正）——推荐功能前必须先确认其实际语义（读 shader 不要凭名字）
- 多尺度带能量脚本有 shape 广播 bug（reshape 对齐）未跑完——频段分解待补

## Next

1. 修 /tmp 多尺度带能量脚本（h//2*2 对齐 reshape），对 195052/PC 跑木桌/白壶/红杯区域
   4 层频段分解——判定差异在细/中/低频哪段（决定"细节增强"还是"暗角/LSC"路线）
2. 读 PhotonCamera 源码确认 Local Laplacian 在**普通 SDR filmic** 出图的生效条件
   （RawDemosaicProcessor 的 photonHdrRequested / hasProfileGainTableMap / PGTM 生成触发）；
   同查 "shading"/LSC——PC 是否保留暗角（我们 LSC 全开 corner 5.9×，画面均匀无暗角）
3. 用 test_temp/FILMKIT_20260928-184956.dng 离线模拟链（无降噪）与真实成片对比 HF——
   量化 MFNR/降噪/锐化（sh=0.5）对细节的净贡献（排除"我们链自己丢细节"）
4. 白壶 0.7EV 高光差：确认 PC 引擎默认 0.7EV（DarktableFilmic defaultExposureCompensationEv
   =0.7, Linear）在其链中的确切位置与 AE 净曝光；**不要用全局 EV 硬套**（红杯会粉，实测）
5. 根因定位后向用户汇报数据，得拍板再动手

## Refs

- 版本：v0.10.22-m4（173），commit `869ffa9`，main 已推，未打 tag；filmkit-0.10.22-m4.apk
- 代码：`FrameRenderer.cpp`（kFsFilm EV 前置/GamutMapV5）、`CameraPresets.kt`（[40]）、
  `CameraController.kt`（FilmParams[0]/base_ev 注释）、`docs/开发计划.md`（十七·续节）
- 分析脚本（/tmp）：gray_split.py（链级模拟+分离）、gray_domain.py（工作域对照+BT 矩阵）、
  gray_trigger.py（min 触发率）、gray_final.py（对拍）、gray_decomp.py（结构分解）、
  gray_ev_ab.py（EV A/B）、gray_object.py（区域/物体级）
- 样片：test_temp/FILMKIT_20260928-184956.dng+184958.jpg（统一场景）；/tmp/pc_185013.jpg
  （PC HEIC 转出）；用户新样片 195052/195203/195804/200056（手机 Pictures/FilmKit/）
- PC 源码：/tmp/photoncamera（DarktableFilmicToneAlgorithm.kt、RawToneMappingGl.kt、
  RawRenderingEngine.kt、DngPhotonLocalToneMapper.kt、RawDemosaicProcessor.kt）
- 日志：vivo办公套件 filmkit-logs-20260928-195242 / 200141
