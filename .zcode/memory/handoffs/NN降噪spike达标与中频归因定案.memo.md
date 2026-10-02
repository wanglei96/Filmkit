---
summary: NN降噪spike达标与中频归因定案
created: 2026-10-02 10:05
updated: 2026-10-02 12:20
status: absorbed
---

# NN降噪spike达标与中频归因定案

## Summary

三 APP 样片对比定位中频/噪声差距 → 5 组开关 A/B 归因(P0 否证、MFNR 保留、
锐化只盖 2px)+ 失焦事故判读;Filmix 长曝(+3.6EV 进光)定案为物理差距。
NN 路线可行性验证全部闭环:PMRID 官方权重 → TFLite FP16 全链,真机 X300
GPU delegate **26ms/512tile(7× CPU),12MP 全图 0.51s,零温升**;离线质量
对照(合成噪声 + clean 参照)纹理保留率 78–86%,交换比优于 P0(−70%/−30%)。

## Done

- **三 APP 对比**(test_temp 三张同刻样片):归一带通 + 配准 + CIELAB 口径,
  我们 4px 比 PC 低 33–53%;Filmix 实为 ISO166+1/4s(+3.6EV 进光)物理优势;
  三家 JPEG 均 q100 排除压缩混淆。
- **中频归因实验**(5 组开关 A/B × 3 张,tools/midband-attrib.py):B(关降噪)
  4px +42% 恢复与缺口同量级;D vs E(锐化/时域)对 4px 净效应 ≈0;C 组(半
  强度)证明强度滑杆不是纹理杠杆。A 组作废(AF 锁手指致失焦)。
- **NN spike**(v0.10.100/101,versionCode 251/252):NnSpike.kt 进设置页
  「能力/Spike」;GPU delegate 强制创建绕过 CompatibilityList 误拒;实测
  GPU 26ms/tile、CPU(XNNPACK×4)181ms,均零温升。
- **离线质量对照**(tools/nn/quality_eval.py,X300 噪声模型合成 σ²=A·x+B):
  clean 参照下 PMRID 纹理保留率 4px 78%(暗光)/86%(白天),8px 91–97%,
  平坦 σ −77~80%,2px 增强 1.7–2×;交换比优于我们 P0。
- 提交 7f1fa56 / a91df35 已 push;APK 根目录仅留 0.10.101-nnspike2(252)
  与 0.10.99-m4(250)。

## Decisions

- [2026-10-02] **归因实验用"信号/噪声分离"控混杂**:跨张 A/B 有场景混杂
  (±40%@4px),判读靠 σ2(场景不敏感)+ 引擎 nzPre/nzPost + 梯度分布交叉
  验证;失焦只衰减信号不衰减噪声,是判失焦的硬指标。
- [2026-10-02] **NN 路线三问闭环**:质量上限 ✓(保留率 78–86%)、CPU 兜底 ✓
  (3.45MP/s)、GPU 加速 ✓(24.86MP/s,7×);集成形态 = 融合后 RAW 域 pass、
  去马赛克前,失败回退 CPU。
- [2026-10-02] **PMRID 是 spike 代表不是天花板**:GPU 剩 5–10× 算力裕量,
  可上 AP-BSN/CPR 级;X300 噪声模型 finetune 是最大质量提升项(训练对现成)。
- [2026-10-02] **超越 FX 必须两条腿**:NN(算法层,可追平 FX/PC 的 4px)+
  HDR+ bracketing(采集进光,1×/4×/16× 阶梯)——后处理无法造出被噪声淹没的
  信息,FX 的 +3.6EV 是物理差。

## Pitfalls

- **AF 锁前景致 ROI 失焦**:床单前手指让 A 组全废;判据 = 信号2px(带通−σ)
  崩半而 σ2 正常 + 梯度中位崩(1.50 vs 3.33)。定量前必须逐张目视 ROI 落点
  (grid 拼图一次查 15 张)。
- **shell 管道吞退出码本轮三中**:`cmd | tail` 后看 tail 的退出码,gradle/
  pip 两次假成功;校验必须 `RC=$?` 或 grep BUILD 标志(教训在册仍犯)。
- **TFLite CompatibilityList 对 Mali-G1 误拒**(静态检查保守):强制
  `GpuDelegate()` 即工作;真不支持时 Interpreter.run 抛错由 catch 记录。
- **电池温度 API**:无 BATTERY_PROPERTY_TEMPERATURE 常量(javap 实锤),在
  ACTION_BATTERY_CHANGED sticky 广播 EXTRA_TEMPERATURE(0.1°C)。
- **PMRID 移植四坑**:权重是 torch.save(pickle 报 persistent id,用
  torch.load weights_only);onnx2tf 1.28 不声明依赖(psutil/ai_edge_litert/
  sng4onnx 手动补)且产物是 **NHWC**;网络输入 = 01 域 × 959/256(直接喂
  [0,1] 会域错配,2px 能量爆 10×);GPU delegate 失败不静默。
- **numpy shape 解构错位**:`c,h,w = (H,W,C)` 会让 y1 切片越界静默截断
  (272 案);onnx2tf 转换守卫(相对误差 0.76%)拦住了走样模型上机。
- pip 大包下载两镜像均超时:换 uv(断点+重试)成功。

## Next

1. **NN 集成立项**:PMRID pass 接拍照链(融合后 RAW 域、去马赛克前,GPU
   0.5s + 失败回退 CPU);按铁律先 100% 原版接入再真机同帧 A/B。
2. **X300 噪声模型 finetune**(最大质量提升项):σ²=A·x+B 合成训练对微调
   PMRID,预期保留率再上一档。
3. **HDR+ bracketing 评估**(采集端进光,超越 FX 的必要项):1×/4×/16×
   阶梯实拍,OIS+现有对齐兜底。
4. **更强模型评估**:GPU 裕量 5–10×,AP-BSN/CPR 级 <2s/12MP。
5. M5 诊断与基准(原计划)与上述可并行;验收口径照旧(check-diag-fields/
   check-log-json)。

## Refs

- 版本:251(0.10.100-nnspike,用户已装,内含 GPU 误拒版)→ **252
  (0.10.101-nnspike2,GPU 强制版,功能与用户已装相同,仅版本号修正)**
- 提交:7f1fa56(tools)/ a91df35(App NN spike);tag 未打(验证包)
- 代码:`tools/midband-attrib.py`、`tools/nn/{convert_pmrid,quality_eval}.py`、
  `app/src/main/java/com/filmkit/app/NnSpike.kt`、`ui/SpikeUi.kt`(FlowRow 换行)、
  `assets/pmrid_512_fp16.tflite`(2.1MB,守卫 0.76%)
- 关键实测:GPU 26ms/tile / 0.51s/12MP / 24.86MP/s;CPU 181ms / 3.65s;
  纹理保留 4px 78–86%、8px 91–97%;对比锚点 PC 4px=2.24 / FX=2.19 / 我们=1.47
- 外部参照:PMRID(ECCV20,Megvii,官方 torch 权重)/ hdr-plus-swift
  (bracketing 参考)/ AP-BSN(CVPR22,更强模型候选)
