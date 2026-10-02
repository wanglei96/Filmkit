---
summary: NN集成口径定案离线恒等
created: 2026-10-02 12:20
updated: 2026-10-02 12:20
status: active
---

# NN集成口径定案离线恒等

## Summary

NN 降噪接拍照链三轮迭代：首包(253) JNI 桥未初始化全回退 → 二包(254)修 4 bug
真机跑通（gpu 1217ms/12MP、噪声 ÷3）但成片发绿+网格+点阵 → 对照官方源码定案
两处预处理误读（cvt_k×256 增益、BGGR 通道语义），离线复现恒等 0.997 →
0.10.104-nnint3 (255) 待真机复测。

## Done

- **v0.10.102-nnint (253)**：NN pass 完整接入——MFNR+单帧两路（pack→读回→
  512/496 tile→JNI 推理→16px ramp→unpack）、NnInferEngine 常驻推理线程（GPU
  delegate 强制+CPU 回退+运行失败熔断）、失败整张回退 P0、nn_dn/nn_ab prefs、
  设置页开关、SpikeUi 自检按钮、raw.render 诊断族；提交 27923f7。
- **真机 253 判读**：nnBridgeInfer 从不调用 nnBridgeInit → 每张 tile 0 即回退，
  三张照片零丢失（回退链真机验证 ✓）。
- **v0.10.103-nnint2 (254)**：修 4 bug（桥 init、自检多除 VST、tile 缓冲按需分配、
  nn_ab 重复落盘+_nn 后缀+nnFail 置位）→ **NN 第一次真正出片**：nnOk:1、
  nnDel:gpu、1217ms/20 tiles、nzPreMed 0.0061→0.0020；但成片发绿+4×3 网格亮线
  +16px 点阵；提交 0b9b0b2。
- **官方口径考证**：拉 MegEngine/PMRID run_benchmark.py/utils.py 源码逐行对照 +
  同刻 DNG 离线复现四组对照，两处误读定案（见 Decisions）；旧口径亮度比 0.41、
  修正后 **0.997 恒等**（G 相位逐位稳定，伪影全消）。
- **v0.10.104-nnint3 (255)**：引擎换官方口径（cvt_k×256 + 语义序相位映射
  uPhX/uPhY/uChMap，CFA 通用表）+ 自检同步 + nnGain 诊断；提交 77ef496/cc6f18d。

## Decisions

- [2026-10-02] **推理层与范围（用户拍板）**：Java TFLite API + JNI 逐 tile 同步
  回调（GPU delegate 已实测、零构建改动；专用 NnInfer 线程避 EGL current 抢占）；
  MFNR+单帧都接。
- [2026-10-02] **NN 开 = P0 空域降噪被替代**（PMRID 原版语义）：P0 走 k=0 直读
  展开（输入 tex_nn_fused_，与 tex_fused_ 同域）、Ts 关；失败整张回退 P0 原链。
- [2026-10-02] **官方预处理口径定案**：inp = img01 × cvt_k × 256，cvt_k =
  S_anchor/S_iso（方差线性模型 shot 项之比；X300 anchor S=4.5e-4 @ISO1600 G
  通道，引擎常量 kNnAnchorS，clamp[0.25,4]，模型无效按 anchor）；通道序 =
  BGGR 空间序语义 ch0=B/Gr/Gb/R（nnSemTables 按 CFA 映射，CFA 无关）；cvt_b
  （read 项平移）img01 域 ~7e-4 实测可忽略。959/256 只是 anchor 恒等点的中间量。

## Pitfalls

- **JNI 回调桥「检查就绪却从不初始化」**：nnBridgeInfer 只查 g_nn.ready 而无人
  调 nnBridgeInit → 整轮静默回退；判据 = 应有的首次日志（nn.init）缺失。
- **自检失败先算理论误差对号**：无推理路径多除一次增益，maxRel 0.7335 与
  1−256/959 精确吻合——自检第一道闸当场拦截了域错配流入正式链。
- **注释里的常数不是公式**：959/256 被当固定 VST（实为官方 KSigma 在 anchor 的
  中间量）；bayer2rggb 是纯空间序打包、通道语义由训练数据的 bayer pattern
  （BGGR）决定——预处理必须溯源到官方代码。
- **去直流指标测不出全局亮度偏移**：带通保留率/去梯度 σ 全部"达标"的离线评估，
  实际压暗 44%（eval_inputs.npy 均值比 0.563 一直在档案里）——离线验收必须加
  均值比对或目视 npy。
- **通道语义错位的症状 = 弱通道被拉向强通道**（R/B 抬向 G）；跨通道卷积网络对
  packed 通道位置敏感。
- nn_ab 双落盘被 done 回调又存一遍（三张同名冲突）——诊断双跑的落盘走独立
  分支 + 后缀（_nn）区分。
- shell 管道吞退出码本轮又犯（tail RC=0 掩盖 BUILD FAILED）——校验必须 RC=$?。

## Next

1. **真机复测 255**：同场景（暗光 ISO3900 + 白天 ≤1600）各拍一张——发绿/网格/
   点阵应消失；raw.render 核对 nnGain（~107 @ISO3900 / ~256 @≤1600）、nnOk:1；
   DNG 交回做离线同刻对比。
2. **nn_ab 正式 A/B**：同帧两遍（基线 + _nn 后缀）→ texture-fidelity-eval /
   ab-100pct 对比 4px 纹理；锚点 PC 2.24 / FX 2.19 / 我们 P0 1.47。
3. **R/B 暗部抬亮待裁决**：修正口径后极端暗部 R/B 相位均值仍 +40~84%（拉向
   G）——离线无 clean 参照无法定性，真机成片目视裁决；治理路径 = X300 噪声
   模型 finetune（已在 backlog，最大质量提升项）。
4. 并行原计划：finetune 立项、HDR+ bracketing 评估、M5 诊断。

## Refs

- 版本：253（0.10.102-nnint）→ 254（0.10.103-nnint2）→ **255
  （0.10.104-nnint3，当前待测）**；验证包均未打 tag
- 提交：27923f7（集成）/ 0b9b0b2（4 bug）/ 77ef496（口径）/ cc6f18d
- 代码：app/src/main/java/com/filmkit/app/nn/NnInferEngine.kt、
  core/engine/src/render/FrameRenderer.{h,cpp}（kFsNnPack/Unpack、runNnPass、
  nnSemTables、kNnAnchorS=4.5e-4）、Bridge.cpp（nnBridgeInfer/Init）、
  tools/check-diag-fields.py（nnOk/nnFail/nnMs/nnTiles/nnGain）
- 离线复现：/tmp/nn_repro2.py（口径对照脚本，临时；结论已定案入 decisions）
- 外部参照：github.com/MegEngine/PMRID（run_benchmark.py 的 KSigma 类 =
  口径权威出处；utils.py bayer2rggb = 纯空间序打包）
- 关键实测：修正口径离线恒等 0.997；254 真机 gpu 1217ms/12MP（含 pack/读回/
  ramp 搬运）、20 tiles、零温升；回退链真机验证零丢照片
