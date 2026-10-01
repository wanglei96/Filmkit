---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-10-01 22:05
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务（2026-10-01：M4 资产互通收线，转 M5）

- [2026-10-01] **M5 诊断与基准** | 最高 | 下一里程碑：逐 pass GPU 计时、成像会话
  摘要、一键诊断包、性能基准脚本 + golden image 接 CI（开发计划 §11）
- [2026-10-01] **渲染链后段 GPU** | 待做（降级）| `passMs` 711~2021ms 是合成耗时
  大头；动它必须先出画质 A/B（用户红线：画质不降）
- [2026-09-30] **DFT 继续挖（冷机 500~550ms）** | 待做（降级）| 先做冷机分段测量
  定位真热点，不凭代码推断
- [2026-09-29] **MGC-AE 曝光决策 / 弱彩差** | 远期·降级 | 采集侧高光保护；色彩主观类

## 下一步

- **M5 诊断与基准**立项实施（开发计划 §11）；其"性能基准 + golden image"会把
  帧率/合成耗时固化成可对比的数字，M6 的温控降级表现也需要它的观测手段
- 待办清单备查：.3dl 导入（M6 可选）、多帧堆栈（M6 后评估）、16bit TIFF（M6 可选）、
  可打包切换渲染引擎（filmic/哈苏式/AgX）
- 留观：温控嫌疑（空闲 17fps 出现过一次未复现）；mfnr.mode=2 trigger=false 成因；
  大尺寸（128³）用户 LUT 的真机纹理验证
- 验收口径照旧：出包前 `tools/check-diag-fields.py`，分析日志前
  `tools/check-log-json.py`，拍照线程数据用 `tools/still-thread-verify.py`

## 最近快照

- [2026-10-01 22:05] handoffs/M4无损LUT导入导出与预览降载收线.memo.md — active
- [2026-10-01 13:05] handoffs/拍照独立线程落地与连拍崩溃定案.memo.md — absorbed
- [2026-09-30 21:50] handoffs/对齐精度亚像素落地与快门异步化.memo.md — absorbed
- [2026-09-30 18:05] handoffs/D2时域空间联合与LSC软knee收线.memo.md — absorbed
- [2026-09-30 15:35] handoffs/A专项DFT收线与D专项色度超越PC.memo.md — absorbed
