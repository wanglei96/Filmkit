---
summary: C 专项噪声模型全链与多摄标定
created: 2026-09-29 21:35
updated: 2026-09-30 12:30
status: archived
---

# C 专项噪声模型全链与多摄标定

## Summary

本窗口把降噪专项 C（噪声模型 σ²=S·x+O）从零实现到多摄标定闭环，并在过程中修掉
四个真机 bug（含一个我自己引入的 MFNR 回归）。链路：Kotlin 解析 SENSOR_NOISE_PROFILE
→ 引擎逐像素模型（P0/P4/mismatch 全换）→ 强度滑杆 0–2 → 可导入/可切换标定 →
多摄实测标定（70MM/15MM）。同刻三镜头 A/B 验证：70MM 残余噪声 4.4×→**1.22×**、
15MM 2.2×→**1.45×**（与标定预测 √2.16 精确吻合）。tag `v0.10.36-m4`（188）已推 main。

## Done

- **模型链落地**（v0.10.32）：`NoiseModel`（σ²=S·x+O 为方差域系数）成为一等输入；
  P0 两个 shader 改 `sr=√(A_c·x+B_c)` 逐像素；P4 逐像素模型 + **LSC 增益传播**
  （shot 项随信号、read 项 ×g²）；MFNR mismatch 噪声底改逐像素 `var(ref)+var(alt)`；
  融合折减 ÷neff 作用于系数；**tile RMS 归约 pass**（HDR+ §5，A 的 σ 输入底座）
  + 诊断读回（raw.render 的 `rms/rmsSig`）。
- **降噪强度滑杆**（v0.10.34）：0–2、步长 0.01；**1.0 = 真实 σ**、0 = 关（A=B=0 精确
  跳过）、2 = 2×σ（k_p0=s、k_p4=1.5s，legacy 路径同语义）；旧三档 prefs 自动迁移
  （denoiseStrength 纯函数 + 单测）；JNI 全链 Float。
- **可导入/可切换**（v0.10.33）：GCam `.c` 解析器（S=A·iso+B、O=C·iso²+D·dg²，
  缺 divisor 时 dg 不启用）；设置页「噪声模型」选择器 + 导入（SAF，先解析校验）+
  删除；优先级 = 用户标定 > 内置实测 > Camera2 > ISO 估算。
- **相机模型正确解析**（v0.10.33/34）：真机形态 = **4 个 (S,O) 标量对**（CFA 相位序，
  per-frame 无 gain 字段）；兼容文档形态 (gain, Double[]) 作回退；`raw.noise` 每次
  拍照全量 dump（src/len/pairs/S/O）。
- **多摄实测标定**（v0.10.35/36）：HAL 对隐藏物理镜头（id3=70MM、id4=15MM）返回
  主摄模型（三摄 pairs 逐位相同）→ DNG 差分拟合比值法得 3.56×/2.16× → 内置
  `assets/noise_models/x300_{70mm,15mm}.c`，按"V2509A + 等效焦距"自动匹配。
- **MFNR alt 帧锁 AE**（v0.10.35）：参考帧外的 3 帧同样 AE_OFF + 预览收敛 ISO/曝光
  → 曝光量严格守恒（修复前 70MM 偏差 10% → confMed=0 → 位移场全零 → neff=1.45；
  修复后 neff 3.0–3.6）。
- **验证**：四档强度 A/B（0.70/1.00/1.50/2.00，σ 单调降、edge 稳定）；同刻三镜头
  A/B（见 Summary）；红杯 100% 裁剪确认 70MM 细节无涂抹；DNG 闭环自检（标定与 HAL
  同一方法下低估率一致 0.846 vs 0.832 → 标定不过强）。
- **工具**：`tools/denoise-strength-eval.py`（成片 σ/edge 对比）、`tools/dng-noise-fit.py`
  （DNG 差分拟合）。
- **收线**：6 提交推 main；tag `v0.10.36-m4`（188）。

## Decisions

- [2026-09-29] **C 落地形态**：模型 = 逐通道 (A,B) 方差系数，**强度滑杆值即为 σ 乘子**
  （1.0 = 真实 σ 的理论正确点）；mismatch/tile-RMS 用 k=1 系数（与降噪档位无关）。
- [2026-09-29] **噪声模型优先级**：用户导入标定 > 内置实测（按型号+焦距）> Camera2 >
  ISO 估算（0.012+8e-6·ISO，机型无关经验式，保底不崩）。
- [2026-09-29] **多摄标定方法学**：DNG 水平差分拟合的**横向比值** × HAL 主摄模型
  （绝对值含 PSF 相关系统偏差，两来源同向 ~15%；比值抵消）。O 项沿用 HAL 读出噪声。
- [2026-09-29] **auto 路径不需要 per-lens 绑定**（模型 per-frame 从当前 device 读，
  天然分镜头）；per-lens 只在导入标定时有意义——**用户拍板先不做设备内自动标定**。
- [2026-09-29] **降噪默认强度 1.0**；主摄继续走 Camera2（其 HAL 模型实测准确）。

## Pitfalls

- **SDK 声明 ≠ 真机**：SENSOR_NOISE_PROFILE 文档签名 `Pair<Double, Double[]>`，X300
  真机 second 是**标量 Double**（4 个 (S,O) 对、CFA 相位序）。`as? DoubleArray`
  对装箱数组永败——自 v0.5.32 潜伏至今，叠加旧 `(s[0]+s[1]+s[2])/3 / whiteLevel`
  再被 clamp 0.004，**σ 自适应从未真正生效**（多重错误互相掩盖）。
- **burst 必须锁所有帧的 AE**：只锁参考帧不够——alt 帧复用预览请求（AE AUTO）时
  HAL 会重新收敛（70MM 实测 +10%），曝光量不守恒直接击穿对齐（残差被亮度差淹没，
  置信度全零）。修复 = 所有帧 AE_OFF + 预览收敛值。
- **HAL 对隐藏物理镜头继承主摄元数据**（三摄 pairs 连 O 都逐位相同）——"多镜头模型
  完全相同"是继承的指纹，可作为通用异常检测信号。
- **跨镜头 edge 指标不可比**（即使同刻同场景）：70mm 浅景深虚化 vs 15mm 超广畸变
  主导梯度分布（同刻三张 9.3/15.8/23.2）。跨镜头正确口径 = σ 定量 + **同一物体
  100% 裁剪目视**。
- **成片 σ 对比必须分解 neff**：成片残余比 = 传感器噪声比 × √neff 比（曾把成片
  4.4× 当归一验证，实为两缺陷叠加的合成效果）。
- **`.c` 通道序 = R, Gr, B, Gb**（不是 R,G1,G2,B）——写错会让 G/B 互换（真机日志
  的 S 向量坐实）；**测试跟着错误实现写同样假设会"通过但错"**（改用不对称系数专测）。
- **KDoc 里写 `/*` 开嵌套注释吞文件**（`noise_models/*.c` 触发）——项目已知坑，
  2026-09-29 又踩一次。
- **float 断言用相对容差**（1e-4 的 float 实为 9.9999997e-5，绝对容差 1e-12 必挂）。

## Next

1. **A 专项（DFT 逐频收缩合并）**：σ 输入底座（模型 + tile RMS）已就位并经真机
   数值验证；spike 载体 207.5ms 达标（v2 寄存器 FFT 架构）。
2. **D 专项（补偿性空间降噪）**：15MM 融合不足是现网最明显缺口（`mP90=0.811`、
   第三帧 46% tile 硬清零 → neff 2.79）；也根治"静物边缘噪点块"（帧间亚像素错位
   → mismatch 判据压权重 → 边缘回退单帧）。
3. **高光线复验（仍未做）**：0.10.31 的阳台+水壶同刻 A/B，通过后补打 tag。
4. 可选小项：70MM 的 `confMed` 恒 0（conf 判据在高噪声镜头饱和，仅诊断指标不影响
   融合）；`builtin` 生效时 `pairs` 未 dump HAL 原始值（诊断缺口）。

## Refs

- 提交：`6f25c16`（模型）`0a49137`（回归修复+导入）`a429897`（结构 A+滑杆）
  `9bb6e50`（评估工具）`5e9f10d`（AE 锁定+多摄标定）`0eda6e8`（通道序）— 全推 main
- tag：`v0.10.36-m4`（188，已推）
- 标定资产：`app/src/main/assets/noise_models/x300_{70mm,15mm}.c`
- 解析/存储：`app/src/main/java/com/filmkit/app/camera/NoiseProfile.kt` / `NoiseCalib.kt`
- 引擎：`core/engine/src/render/FrameRenderer.{h,cpp}`（NoiseModel/NoiseCoef/4 shader/
  tile RMS）；`Align.cpp`（对齐；conf 判据饱和见 Pitfalls）
- 工具：`tools/denoise-strength-eval.py`、`tools/dng-noise-fit.py`、`tools/chroma-denoise-eval.py`
- 日志：`~/Downloads/vivo办公套件/filmkit-logs-20260929-{200802,202512,204214,211423,211640,212700}.jsonl.txt`
- 样片：`test_temp/FILMKIT_20260929-{2039..2126}*.jpg/.dng`（三镜头同刻对比在 2126 组）
- 分析图：`test_temp/analysis/strength_ab/`（四档对比 + 红杯 100% 裁剪）
