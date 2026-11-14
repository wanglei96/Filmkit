---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-11-14 18:45
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务（2026-11-14）

- [2026-11-14] **HDR 灯面边缘灰带** | 进行中（用户已叫停本轮）| gainmap 已对齐 PC 变成纯比值
  （gmMean 0.51），紫边已降到 11；**灰带在延伸幅度 4.0×/2.66×/1.414× 三档都无改善** ⇒
  根因不在斜率，下个窗口先复现真机 HDR 显示口径 | 快照「预设覆盖层、删除切图与HDR灰带」
- [2026-11-14] **预设覆盖层** | 已修（0.13.55）| 「标准」走 `forCamera()`，杀进程不再还原
- [2026-11-12] **Spektrafilm 引擎 + 预设标定** | 待做 | slot=2、场景线性输入、65³ LUT；
  先 Portra 400，再 Ektar / Vision3 500T / Velvia
- [2026-11-11] **理光目标曲线重标** | 待做 | 用新 LUT 样片重跑 `recipe-fit.py`，把摘掉的
  `BuiltinPresetCurveTest` 理光断言挂回去
- [2026-11-11] **绿植/绿段对齐** | 待量 | 同一张图分别量绿植与天空（掩码两次取错区域）

## 下一步

0. **HDR 灰带**：离线复现 Android 真机 HDR 显示口径（gainmap 应用 + 屏幕 tonemap），
   确认灰带是像素层还是显示层；对比 SDR/HDR 同图在灯面边缘的差异。**不要再调斜率**。
1. 灯面白（SDR 顶白 247，PC 199）：白点 4.56 把高光压进 220-247；用户暂不许调 shoulder/白点。
2. 旧账：编辑器整页路由；Spektrafilm；理光曲线重标；绿植/绿段；AE 高光保护效果。

## 最近快照

- [2026-11-14 18:42] handoffs/预设覆盖层、删除切图与HDR灰带.memo.md — active
- [2026-11-13 15:40] handoffs/HDR-gainmap与高光判据归因.memo.md — absorbed
- [2026-11-13 10:40] handoffs/快门动画与保活预热.memo.md — absorbed
- [2026-11-12 22:10] handoffs/预览卡顿定档与T3评估.memo.md — absorbed
- [2026-11-12 19:49] handoffs/性能剖面与RCD归因.memo.md — absorbed

> **本次交接（s2 × d3，2026-11-14）**：新建快照 1 份；吸收进 project/decisions/lessons/facts
> （各新增 1 段，decisions 125 行 / lessons 126 行 / facts 79 行）；旧 active
> 「HDR gainmap与高光判据归因」标 absorbed；归档 4 份超 14 天的 absorbed 快照
> （品红修复与HDR增益归属 / 高光三症修复与sat-mask接线 / 预设开关归位与理光负片重标 /
> 胶片预设群与LUT归一方法论）；提交 `0f16ff1` `1f07f60` `f9ff090` 已 push dev。
> ⚠ 设备 adb 长期掉线，0.13.64 未安装验证；HDR 灰带未解决。
