---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-09-30 12:30
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务（2026-09-30：A 专项攻坚中）

- [2026-09-30] **降噪专项 A（DFT 逐频收缩合并）** | 最高 | 内核完整实现 +
  12 轮真机攻坚定位"Mali init 创建的 compute program 写入全丢"规律；
  0.10.49 改现场创建（spike 模式）**待真机验证**（store_check sum>0 → 进
  验收：σ/edge 同刻 A/B + 100% 裁剪 + 无鬼影/环状伪影；dftGpu 对照 250ms，
  超了动 8×8 tile 储备）| handoffs/A专项DFT合并与Mali写入丢失攻坚.memo.md
- [2026-09-30] **MFNR 阶段二收尾** | 待做 | 参考帧回最锐帧 + 8 帧档位
- [2026-09-30] **MGC-AE 曝光决策** | 远期 | 采集侧高光保护（渲染侧修不到的
  那一半）
- [2026-09-30] **降级/低优先队列** | 色彩主观类（弱彩差再定位、ColorChecker
  ΔE2000）+ PC 自拍设置（待问用户）+ 色温估计校准（待灰卡）——随时可叫回
- [2026-09-30] 高光线复验通过（0.10.31 销项，不打 tag）

## 下一步

- 装filmkit-0.10.49-m4.apk（201）开「频域合并」拍一组：`mfnr.fft.store_check`
  sum>0 → DFT 首次工作进验收；micro:true+sum=0 → 与 spike 逐行 diff
- 验收工具就绪：`tools/denoise-strength-eval.py`（成片 σ/edge）、
  `tools/dng-noise-fit.py`、`tools/fft-merge-mirror.py`（离线镜像）
- 口径提醒：跨镜头/跨时刻对比只用「σ 定量 + 同一物体 100% 裁剪目视」，成片
  σ 比须分解 neff；A/B 必须同刻连拍

## 最近快照

- [2026-09-30 12:30] handoffs/A专项DFT合并与Mali写入丢失攻坚.memo.md — active
- [2026-09-29 21:35] handoffs/C 专项噪声模型全链与多摄标定.memo.md — absorbed
- [2026-09-29 19:50] handoffs/频域降噪调研立项与实测达标.memo.md — absorbed
- [2026-09-29 17:45] handoffs/WB复测与高光白根治收线.memo.md — absorbed
- [2026-09-29 12:00] handoffs/M档WB断链修复与色度差复测待办.memo.md — absorbed
