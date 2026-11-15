---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-11-15 14:20
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务（2026-11-15）

- [2026-11-15 21:20] **0.14.5 高光融合实现与离线验收完成** | 待日光实拍 | 包 802；手机仍0.14.4未替用户安装。
  native clip/全CFA有效残差、真实双向FB+RGB/collar准入、整帧hole拒绝、dual-kernel Bayer merge、
  去马赛克后RGB CameraWhite解析、局部V/完整六cov与多尺度消费已接；defringe/旧scalar inpaint仍关。
  两组原RAW完整guide→merge重放：matched按285px大hole拒绝，17:07按24px孔洞接受、G高光约97%恢复；
  Mali原生58checks/16draw通过，12生产shaderlink通过；未证明完整App日光外观，详见 `docs/reports/highlight-fusion-0.14.5.md`。
- [2026-11-12] **Spektrafilm 引擎 + 预设标定** | 待做 | slot=2、场景线性输入、65³ LUT；
  先 Portra 400，再 Ektar / Vision3 500T / Velvia
- [2026-11-11] **理光目标曲线重标** | 待做 | 用新 LUT 样片重跑 `recipe-fit.py`，把摘掉的
  `BuiltinPresetCurveTest` 理光断言挂回去
- [2026-11-11] **绿植/绿段对齐** | 待量 | 同一张图分别量绿植与天空（掩码两次取错区域）

## 下一步

0. **日光验收待条件**：0.14.5实现不再等天气；条件允许再验极高光、云朵、花瓣、栏杆、灯面和运动。
   CameraWhite是输出白界政策非满阱检测，保守cov非精确非线性RCD噪声，风险见报告；不因阴天停离线工作。
1. 旧账：编辑器整页路由；Spektrafilm；理光曲线重标；绿植/绿段；AE 高光保护效果。
2. 若长期不用：`kFsHlInpaint` 代码与 `edge_dump` 诊断可评估删除；注意
   `tools/check-hl-inpaint-kernel.py` 测的是已禁用的核，不能当生产判据。

## 最近快照

- [2026-11-15 14:20] handoffs/HDR灰带定位到高光inpaint并0.14.0发版.memo.md — active
- [2026-11-14 22:55] handoffs/HDR灰带参考源与短帧归一.memo.md — absorbed
- [2026-11-14 18:42] handoffs/预设覆盖层、删除切图与HDR灰带.memo.md — absorbed
- [2026-11-13 15:40] handoffs/HDR-gainmap与高光判据归因.memo.md — absorbed
- [2026-11-13 10:40] handoffs/快门动画与保活预热.memo.md — absorbed

> **本次交接（s3 × d3，2026-11-15）**：新建快照 1 份（62 行）；吸收进 project（94 行）/
> decisions（139 行）/ lessons（144 行）/ facts（95 行）；旧 active
> 「HDR灰带参考源与短帧归一」标 absorbed；无超 14 天快照需归档（最早 11-10）。
> 修正两处过期事实：设备版本（现 0.14.0/797）、快门动画（0.13.40 已装上）。
> 发版 `465a4b4` + tag `v0.14.0` 已 push。
> ⚠ 本轮 0.13.79→0.13.88 共 10 包，仅 0.13.88 通过真机验收（其余均有已知缺陷，已删）。
