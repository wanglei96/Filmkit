---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-10-02 10:05
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务（2026-10-02：NN spike 闭环，待集成立项）

- [2026-10-02] **NN 降噪集成** | 最高 | PMRID pass 接拍照链（融合后 RAW 域、
  去马赛克前，GPU 0.5s + CPU 回退）；先 100% 原版接入再同帧 A/B
- [2026-10-02] **X300 噪声模型 finetune PMRID** | 高 | σ²=A·x+B 合成训练对，
  纹理保留率 78–86% 再上一档（超越 PC/FX 的算法层主力）
- [2026-10-02] **HDR+ bracketing 评估** | 高 | 1×/4×/16× 曝光阶梯（超越 FX 的
  进光杠杆）；hdr-plus-swift 为参考实现
- [2026-10-01] **M5 诊断与基准** | 待做 | 逐 pass GPU 计时、成像会话摘要、
  golden image 接 CI（开发计划 §11），与上面并行
- [2026-10-01] **更强 NN 模型评估** | 降级 | GPU 裕量 5–10×,AP-BSN/CPR <2s

## 下一步

- **NN 集成立项**实施：引擎融合后 RAW 域 pass + GPU/CPU 双路回退 + 同帧 A/B
- **finetune 训练管线**：X300 噪声模型合成训练对 → 云 GPU 微调 PMRID
- 留观：PMRID 大平坦区低频云纹（盲点网络残留）在引擎链真实输入下是否可见；
  128³ 用户 LUT 真机纹理验证；`mfnr.mode=2 trigger=false` 成因未查
- 验收口径照旧：出包前 `tools/check-diag-fields.py`，分析日志前
  `tools/check-log-json.py`，拍照线程 `tools/still-thread-verify.py`

## 最近快照

- [2026-10-02 10:05] handoffs/NN降噪spike达标与中频归因定案.memo.md — active
- [2026-10-01 22:05] handoffs/M4无损LUT导入导出与预览降载收线.memo.md — absorbed
- [2026-10-01 13:05] handoffs/拍照独立线程落地与连拍崩溃定案.memo.md — absorbed
- [2026-09-30 21:50] handoffs/对齐精度亚像素落地与快门异步化.memo.md — absorbed
- [2026-09-30 18:05] handoffs/D2时域空间联合与LSC软knee收线.memo.md — absorbed
