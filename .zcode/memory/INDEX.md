---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-10-22 22:20
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务（2026-10-22）

- [2026-10-22] **镜头去重后真机验证** | 待用户 | 0.12.42 把镜头表按等效焦距去重（物理优先），默认主摄从逻辑 id 0 换成物理 id 2。要确认：镜头配置只剩 4 个（15.4 / 23.6 / 70.3 / 165.1）、3x 以上仍能自动切长焦（原先靠逻辑相机代劳） | 快照「M档裁切、相册多选与ISZ收口」
- [2026-10-22] **暗部局部对比度偏低** | 待验证 | 同区域相对对比度 rawpy 19.4% / 我们 17.6% / **PC 25.9%**；我们只比"不处理"低 1.8 点，PC 高 6.5 点 ⇒ 它在链路里增加了细节。候选 = 去马赛克档（我们 RCD、PC 为 AMaZE 系）。用户暂定"留到预设里调对比度" | 快照「高光链路收口与暗部对比度定位」
- [2026-10-22] **天空复验** | 待晴天 | `allSat` 亮度锚改实测 luma 后，天空同属"大面积饱和"，需确认没被带偏。判据：天空蓝 + `hlGRecon 1` + `teqHl -2.2` + `hi240 ≤ 0.5%` | docs/reports/highlight-chain-CHECKLIST.md
- [2026-10-22] **PC HEIC HDR 死链** | 待定 | `passGainmap` 只在 `!c.ten_bit` 时跑，HEIF 档恒 `ten_bit=true` ⇒ HEIC 永远拿不到本帧 gainmap（会读到上一张的 stale）。决定：修链路还是移除 HEIC 选项 | 快照「M档裁切、相册多选与ISZ收口」
- [2026-10-20] **HDR 剩余真机验证** | 待用户执行 | ① HEIC + HDR（8bit 降位）② JPEG 4:2:0 + HDR ③ 导出 SDR 版 ④ gainmap 数学 PC/官方双档 | docs/reports/hdr-acceptance-CHECKLIST.md

## 下一步

1. **真机验 0.12.42**：镜头表 4 个 + 3x 以上长焦切换（本轮唯一可能回归的点）。
2. **去马赛克档 A/B**：RCD vs AMaZE，量暗部同区域局部对比度能否接近 PC 的 25.9%。
3. **天空复验**（等晴天）：判据见上，重点看 `teqHl` 是否 −2.2、天空是否蓝。

## 最近快照

- [2026-10-22 22:15] handoffs/M档裁切、相册多选与ISZ收口.memo.md — active
- [2026-10-22 20:35] handoffs/高光链路收口与暗部对比度定位.memo.md — absorbed
- [2026-10-20 14:58] handoffs/自动曝光落地与bracket设计.memo.md — absorbed
- [2026-10-20 09:29] handoffs/HDR成片交付链落地与App内HDR显示.memo.md — absorbed
- [2026-10-19 17:20] handoffs/色相路线收口与HDR路线定案.memo.md — absorbed

> **本次交接（s2 × d2，2026-10-22 晚）**：新建快照 1 份（M档裁切、相册多选与ISZ收口）；
> project 追加「拍照/相册收口与 ISZ」一节，decisions / lessons / facts 各追加本轮条目并压缩旧条目到 120 行上限；
> 「高光链路收口与暗部对比度定位」标 absorbed；「边缘彩边专项收尾与v0.11.0发布」created 超 14 天 ⇒ 归档。
> 活跃任务移除「待重标 5 项（除预设）」（两个周期未动，依据留在 docs/reports/phaseB-old-domain-baseline-ARCHIVE.md §7）。
