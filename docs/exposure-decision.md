# 曝光决策专项（v0.10.107-exp · 2026-10-02）

> 起因：NN 路线判死后主线转 HDR+ bracketing（`handoffs/NN集成定因与判死转HDR+.memo.md`）。
> 本轮**先离线定量再实现**，避免重复"先建管线、再发现零收益"的老路。
> 离线数据与判据：`test_temp/exposure_lever/findings.md`；评估器：`tools/exposure-lever-eval.py`。

---

## 1. 一手取证：PC 的曝光决策系统（`/tmp/photoncamera`，hinnka 版 MGC）

PC **不是"没做曝光策略"，它自己就在做曝光阶梯**；实现类不在该检出里，但测试文件把行为规格钉死：

| 部件 | 规格（测试断言） | 出处 |
|---|---|---|
| `MultiFrameExposurePlanner` | 长帧目标 **+2.5 EV**、快门上限 **10ms**、撞上限后用 ISO 补足，ISO 也撞顶则诚实降 EV | `camera/MultiFrameExposurePlannerTest.kt` |
| `HdrBracketConfig` | YUV HDR 阶梯 **长 +2.2EV / 短 −1.5EV**，长帧 ≤10ms，**短帧不受该上限约束** | `camera/HdrBracketConfigTest.kt` |
| `GyroExposureWindowIntegrator` | 曝光窗口陀螺积分：`coverageRatio` / `integratedRotationRad[3]` / `angularEnergy` / `jerkEnergy` | `camera/GyroExposureWindowIntegratorTest.kt` |
| `MeteringSystem` | **18% 灰**锚（中性场景 autoHighlights 中性）+ 高光压缩自动调整 | `raw/MeteringSystemTest.kt` |
| `RawExposureMath` | 曝光乘积 = 时间 × ISO（跨帧归一化） | `camera/RawExposureMathTest.kt` |
| `RawAdaptiveExposureMode` | `PHOTON_HDR` / `LEGACY_AUTO_EXPOSURE` 互斥模式解析 | `raw/RawAdaptiveExposureModeTest.kt` |

**决定性事实**：`planManualExposure` 收到的硬件快门上限是 `1_000_000_000L`（1s），
但长帧仍被 `YUV_LONG_MAX_SHUTTER_NS = 10ms` 卡住 ⇒ **10ms 是 PC 的配置常数，不是硬件枷锁**
（同机 Filmix 用 ISO166 + 1/4s 拍出样片，反向印证）。
且 PC 算了 `angularEnergy`/`jerkEnergy`，**却没有把运动信息接到曝光上限上** —— 上限与 EV 偏移都是常数。

⇒ 超越点不是"另起炉灶"，而是**同一架构 + 三处按数据求解**：动态上限（陀螺 + 高光）、
按场景定 EV、帧数×每帧曝光联合规划（Night Sight §2.3）。

## 2. 离线定量（先写下判据，再跑）

**合成器**：真机 18 帧 → 15 帧对齐平均作 clean 参照（场景 1，ISO 4781 / 38.46ms）；
采集「曝光 ×N + ISO ÷N」保持输出亮度 ⇒ 信号不变、噪声变 `σ²(x,N) = (s·x + o/N)/N`
（s/o 取 DNG tag 51041 标定）；帧内模糊 = 逐 CFA 平面线性拖影（长度 d）。

**结论一 · 噪声收益 = √N，物理保证**

| N | σ 解析 | 物理上限 √N | 实测（被 clean 残余压制） |
|---|---|---|---|
| 2 | **1.42×** | 1.41× | 1.33× |
| 4 | **2.00×** | 2.00× | 1.71× |
| 8.66 | **2.95×** | 2.94× | 2.17× |

解析值与 √N 逐位吻合 ⇒ 模型正确；实测偏低源于 4 帧共用同一 clean 参照（残余噪声帧间相关），
真实采集无此项，**产品收益取解析值**。

**结论二 · 纹理代价只与帧内模糊 d 有关**（tex_keep = lag-2 差分对 clean 的最小二乘投影系数，噪声免疫）：
d = 1/2/4/8/16px → 0.976 / 0.956 / 0.918 / 0.842 / 0.695。

**结论三 · 决策边界取决于判据口径（需拍板）**：

| 口径 | d\*（N=8.66） | 净效果 |
|---|---|---|
| 综合 SSIM | 13.0 px | σ 2.95×↑，SSIM 持平 |
| 折中 tex≥0.90 | ≈ 4.5 px | σ 2.95×↑，纹理 −8~10% |
| **细节优先 tex≥0.95**（用户 2026-09-28 口径） | **≈ 2.2 px** | σ 2.95×↑，纹理 −<5% |

**结论四 · 换算到真实手持**：1 mosaic px ≈ 0.017°（4080×3072 / 等效 23mm）；
本批实测帧间漂移 0.34–3.4 px/s ⇒ 250–333ms 曝光的低频漂移贡献仅 0.1–1.1 px，**在 2.2px 预算内**。
剩余风险 = 高频抖动（帧间位移测不到）与主体运动（mismatch 兜底）；OIS 未计入（偏保守）。

## 3. 实现（v0.10.107-exp，versionCode 258）

* `app/.../camera/ExposurePlanner.kt` —— 纯函数决策器（+ `ExposurePlannerTest` 6 项）：
  **曝光 ×N + ISO ÷N ⇒ 亮度与高光裁切不变，只赚 SNR**；三重约束：
  ① ISO 下限 `N ≤ baseIso/isoLower` ② 快门上限 ③ 运动预算
  `t ≤ budget·RAD_PER_PX/ω`（ω = 采集前 1s 陀螺角速度 RMS）。只做拉长，不缩短。
* `CameraController` —— 陀螺仪 `TYPE_GYROSCOPE`（`SENSOR_DELAY_GAME`，1s 环形缓冲 → RMS）；
  长曝光生效时**取消参考帧压短**（压短会让参考帧噪声变差，拖影已由运动预算统一约束）。
* **护栏**：`mfnr.burst_actual` 记录 HAL **实际**曝光/ISO —— 请求被 clamp 时照片会偏暗
  （ISO 已按规划值下调），必须能从日志一眼发现。
* prefs：`exp_plan`（0 关 / 1 开，默认开）；设置页「长曝光进光」开关 —— **手动开关做 A/B**
  （自动交替模式已删除，原因见 §3.5）。
* **保守起步**：目标 250ms（同机 Filmix 实证 1/4s 可用）；Night Sight 标准 333ms 待真机确认后放开。

## 3.5 真机首测（2026-10-02 日志）与修正 —— v0.10.107-exp2（versionCode 259）

首次真机（258）暴露两个问题，均已定位并修正：

| 现象 | 日志证据 | 根因 | 修正 |
|---|---|---|---|
| **长曝光从未生效** | 两次拍照均 `expN:"1.00"` / `expWhy:"none"`；`gyro:"0.030"`、`mtnLim:"0.022"` | 运动判据用了**角速度 RMS**（保留高频）⇒ 预算被压到 22ms，比预览 AE 的 40ms 还短 ⇒ `nEff` 恒 1 | 改用 **Night Sight §2.3 语义：矢量平均角速度**（高频在 1s 窗口内正负抵消，只有低频漂移会累积成帧内模糊）；RMS 降级为诊断字段 `gyroR` |
| **"连按两下只有 1 张"** | 无任何 `capture.busy` 记录；`render.burst_done ms:4318` | `capture()` 的 `if (capturing) return` 是**静默忽略** —— MFNR 渲染 ~4.3s 期间按快门既无反应也无日志 | 该分支补 `capture.busy` 日志；**删除自动交替 A/B**（其前提"连按两次"根本不成立），改为设置页手动开关 |

**运动预算保持 2.2px（细节优先口径，纹理是红线）**：一度放宽到 4.5px 属**误读用户意见后
替用户做质量取舍**（"偏一点没关系"指的是不必严格 A/B，不是画质可妥协），已撤回。
修正运动判据后，正常手持（矢量平均 ω≈0.005 rad/s）下 2.2px 即允许 **~131ms** 曝光
（expN≈3.4），**无需牺牲纹理**。

**好消息**：`mfnr.capture` 的 `iso:"7049,4699,4699,4699"` / `sec:"0.0267,0.0400,0.0400,0.0400"` 与请求
**逐位一致** ⇒ HAL 忠实执行手动曝光/ISO，**没有 clamp**（250ms 长曝光的"被 clamp 变暗"风险相应降低）。

**方法论教训**：① 判据量纲必须与物理过程一致 —— "帧内模糊 = 曝光窗口内角度积分"，积分对低频
敏感、对高频不敏感，RMS 是错的量；② 真机日志必须在**功能未生效**时也能自证原因（本次靠
`expN/expWhy/mtnLim` 三个字段直接定位，否则只能猜）；③ 静默 `return` 是诊断黑洞；
④ **不许把用户的"不必严格 A/B"读成"画质可以妥协"** —— 任何质量取舍（含降噪/纹理预算的
放宽）都必须由用户拍板，质量优先是项目大前提。



1. `camera.mfnr.burst_submit`：`baseIso/baseSec`（AE 基准）、`altIso/altSec`（规划请求）、
   `expN`（曝光倍数）、`expWhy`（target/motion/iso/shutter/none）、`expDv`（应≈0，亮度守恒）、
   `gyro`（角速度 RMS）、`mtnLim`（运动预算允许的最长曝光）
2. `camera.mfnr.burst_actual`：HAL 实际执行的 `secs`/`isos` —— 与 `reqSec/reqIso` 比对，
   不一致即说明被 clamp（画面会偏暗，需下调目标）
3. 成片层：`raw.render` 的 `nzPreMed/nzPostMed` 等噪声字段 + 目视纹理/拖影
4. A/B：设置页开「曝光 A/B 交替」，同场景连按两次快门 ⇒ 日志 `expN` 区分两组

## 5. 未做 / 下一步

* 帧数×每帧曝光**联合**规划（Night Sight 6–13 帧）—— 当前仍是固定 4 帧
* 高光约束驱动的 EV 规划（HDR+ §4）：当前只做亮度守恒，未按高光裁切反推
* bracketing（长短帧混合）：PC 已有（+2.2/−1.5），我们的对齐在曝光不一致时会失效
  （v0.10.35 实测 `confMed=0 → neff=1.45`），须先解决对齐的曝光不变性
* 降噪档位联动：σ 改善 2.95× 的另一半价值在"可以调轻降噪"（NN 归因：关降噪 4px +42%）
