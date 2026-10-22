---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-10-22 21:00
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务（2026-10-22）

- [2026-10-22] **暗部局部对比度偏低** | 待验证 | 同区域实测相对对比度：rawpy 单帧 19.4% / 我们 17.6% / **PC 25.9%**。我们只比"完全不处理"低 1.8 个百分点（降噪/融合不是主因），**PC 高出 6.5 个点** ⇒ 它在链路里增加了细节。候选：去马赛克档（我们 RCD、PC 为 AMaZE 系）。验证法：设置→画质→去马赛克切非 RCD 档，同场景拍，量对比度。用户暂定"留到预设里调对比度" | 快照「高光链路收口与暗部对比度定位」
- [2026-10-22] **天空复验** | 待晴天 | `allSat` 亮度锚改实测 luma 后，天空同属"大面积饱和"，需确认没被带偏。判据：天空蓝 + `hlGRecon 1` + `teqHl -2.2` + `hi240 ≤ 0.5%` | docs/reports/highlight-chain-CHECKLIST.md
- [2026-10-22] **PC HEIC HDR 死链**（审查发现，未修）| `passGainmap` 只在 `!c.ten_bit` 时跑，而 HEIF 档恒 `ten_bit=true` ⇒ HEIC 永远拿不到本帧 gainmap；先拍过 JPEG-HDR 再切 HEIF 会读到上一张的（stale）。C++ 注释"HEIC 走平台 HeifWriter"与 Kotlin 用法矛盾 | 待定：修链路或移除 HEIC 选项
- [2026-10-20] **HDR 剩余真机验证** | 待用户执行 | ① HEIC + HDR（8bit 降位）② JPEG 4:2:0 + HDR ③ 导出 SDR 版 ④ gainmap 数学 PC/官方双档 | docs/reports/hdr-acceptance-CHECKLIST.md
- [2026-10-19] **待重标 5 项（除预设）** | 待重估 | 分离色调/黑白 `[23][24][25]`、分区饱和度 `[20..22]`、光晕 `[62..65]`、场景统计/自动 EV `kFsHist`、局部色调映射 `kFsLocal` | docs/reports/phaseB-old-domain-baseline-ARCHIVE.md §7

## 下一步

1. **去马赛克档 A/B**：同场景拍 RCD 与 AMaZE/AHD，量局部对比度是否接近 PC 的 25.9%。这是暗部"平"唯一还在台面上的候选。
2. **天空复验**（等晴天）：判据见上，重点看 `teqHl` 是否 −2.2、天空是否蓝。
3. **HEIC HDR 死链**：决定修链路（把 gainmap 也产给 ten_bit）还是直接移除 HEIC 选项（用户本就不喜欢 HEIC）。

## 最近快照

- [2026-10-22 20:35] handoffs/高光链路收口与暗部对比度定位.memo.md — active
- [2026-10-20 14:58] handoffs/自动曝光落地与bracket设计.memo.md — absorbed
- [2026-10-20 09:29] handoffs/HDR成片交付链落地与App内HDR显示.memo.md — absorbed
- [2026-10-19 17:20] handoffs/色相路线收口与HDR路线定案.memo.md — absorbed
- [2026-10-19 15:55] handoffs/期B域切换落地与域错配修复.memo.md — absorbed

> **本次交接（s2 × d3，2026-10-22）**：新建快照 1 份（高光链路收口与暗部对比度定位）；四个分层全量吸收 + 压行
> （decisions／facts／lessons 各 120 行、project 112 行，均 ≤120）；11 份旧 active 快照标 `absorbed`；
> 「品红带机理闭环与替换档转正」created 超 14 天 ⇒ 归档（archive 44 份）。工作区已提交并打 tag `contrast1.5`。
