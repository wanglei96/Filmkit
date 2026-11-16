---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-11-16 15:59
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务（2026-11-16 15:59）

- [2026-11-16] **性能目标仍未达标，下一轮攻 PC3D/预热/采集空档** | 待继续 |
  0.14.20 保存中位 5.599s，PC3D 1.8--2.3s；详见
  `handoffs/0.14.20性能收线与临时清理.memo.md` 与 `docs/reports/performance-0.14.20-20261116.md`。
- [2026-11-16] **高光外观扩展复验** | 待条件 | 0.14.19 长焦云边已验收；彩灯、花瓣、栏杆、
  跨镜头/预设、运动重影仍待视觉检查，CameraWhite 不是无损满阱恢复。
- [2026-11-12] **Spektrafilm 引擎 + 预设标定** | 待做 | slot=2、场景线性输入、65³ LUT；
  先 Portra 400，再 Ektar / Vision3 500T / Velvia
- [2026-11-11] **理光目标曲线重标** | 待做 | 用新 LUT 样片重跑 `recipe-fit.py`，把摘掉的
  `BuiltinPresetCurveTest` 理光断言挂回去
- [2026-11-11] **绿植/绿段对齐** | 待量 | 同一张图分别量绿植与天空（掩码两次取错区域）

## 下一步

0. 从 `d6f6ede` / 0.14.20(817) 开始，先读最新性能报告；拆 PC3D/共享 GPU 调度、warmup 分配争用、
   RAW producer 空档与 histogram 同步等待，禁止删样本、降精度或复用前张统计。
1. 自动采样前重新确认手机固定、静止场景、相机页；已删 RAW/照片 fixture 需重新采集。
2. 旧账：日光高光扩展复验；Spektrafilm；理光曲线重标；绿植/绿段；既有 `Pv3StaleFrameTest`。

## 最近快照

- [2026-11-16 15:59] handoffs/0.14.20性能收线与临时清理.memo.md — active
- [2026-11-15 22:20] handoffs/高光融合落地与出片回归.memo.md — absorbed
- [2026-11-15 14:20] handoffs/HDR灰带定位到高光inpaint并0.14.0发版.memo.md — absorbed
- [2026-11-14 22:55] handoffs/HDR灰带参考源与短帧归一.memo.md — absorbed
- [2026-11-14 18:42] handoffs/预设覆盖层、删除切图与HDR灰带.memo.md — absorbed
- [2026-11-13 15:40] handoffs/HDR-gainmap与高光判据归因.memo.md — absorbed

> **本次交接（s2 × d3，2026-11-16 15:59）**：新建快照 1 份（0.14.20性能收线与临时清理）；
> 全量范围已扫描，长期结论增量吸收进 project/facts/decisions/lessons；旧 active
> 「高光融合落地与出片回归」标 absorbed；修正 2 份 archive 内快照状态为 archived。
> 版本事实更新为 0.14.20(817)，`d6f6ede` 已 push；大体积临时证据已清理但摘要/parity 保留。
