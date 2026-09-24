---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-09-24 10:20
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务

- [2026-09-24] **基础色彩引擎（主线）** | 进行中 | v0.6.9→**v0.6.44-m4**。本轮把基准换成 **PhotonCamera 同机位成片**并重标定（高光去饱和 (0.85,1.30)→(0.40,0.75)（旧区间从未触发）、`kTarget 0.38`/`kMax 1.9`）；修方向链路（五版收敛，成片侧已修）；文档全面规整。**未打 tag** | handoffs/对标PhotonCamera与方向链路修复.memo.md
- [2026-09-24] **UI 旋转方案（方向链路收尾）** | 待做（最高优先）| 横拿时取景框仍是竖的、预览只见画面中央，**WYSIWYG 不成立**。正解＝照抄 PC 两段式：`Modifier.autoRotate`（整页旋转 + 90° 交换约束）+ 预览 GL 旋转（`setDeviceRotation` 对应引擎 rot）。⚠️ 动 `passOES` 前必须先读清 PC 预览 shader 里 `deviceRotation` 的用法（v0.6.39 就在此处翻车）| 同上快照
- [2026-09-24] **暗部提色** | 待做 | 我们暗部 rel 只有 PC 的 52%；降噪占 **24.8%**（A/B 实测），剩余候选＝外观层暗部加权不足（暗部 +11% vs 天空 +17%）与 CCM 负值截断 `clamp(rgb,0,64)`。⚠️ 乘性放大白平衡残余有历史事故（v0.6.14 全局 ×1.5），单独一轮做 | 同上快照
- [2026-09-23] **M 档 RAW 所见即所得预览** | 待立项（用户先不做）| 卡点是**性能非功能**：RAW 全链路 12.5MP 需 210–500ms、实时需 <33ms。未知量＝**X300 的 RAW 输出尺寸列表**（`CameraAudit.kt:86` 已记录，导审计即判）| 同上快照
- [2026-09-23] **多帧融合（MFNR）＝彩噪根治** | 待立项 | 彩噪跨帧独立、信号跨帧相关 ⇒ 时间维度完美可分离；HDR+ 一手原话 "Fusing a burst…**makes aggressive spatial denoising unnecessary**"。N=4 约 0.7–1.0s | 同上快照

## 下一步

- **先验 v0.6.44**：装包横拍一张，看 `photo.crop` 是否 `dstA:1.333`（或所选画幅）、`photo.saved` 宽>高、DNG 在相册里横向正常 —— 确认成片侧修复成立后，再做 UI 旋转
- **UI 旋转方案（PC 两段式）**：`Modifier.autoRotate` + 预览 GL 旋转；**先读 PC 预览 shader 里 `deviceRotation` 的用法再动 `passOES`**
- **v0.6.38 色彩改动仍未经真机验证**：去饱和 (0.40,0.75) 与 `kTarget 0.38`/`kMax 1.9` 只有离线模拟（天空 C\* 命中 PC 8.46）；需确认天空是否过曝死白、暗部是否因提亮 1.5× 而彩噪变明显

## 最近快照

- [2026-09-24 10:20] handoffs/对标PhotonCamera与方向链路修复.memo.md — active
- [2026-09-23 17:15] handoffs/自适应影调回归中间调与降噪换径.memo.md — absorbed
- [2026-09-22 22:13] handoffs/调研复核与色度判据三次修正.memo.md — absorbed
- [2026-09-22 20:00] handoffs/基础色彩引擎实测调优与独立调研定案.memo.md — absorbed
- [2026-09-22 10:15] handoffs/M4内置LUT引擎落地与tag规则收紧.memo.md — absorbed
