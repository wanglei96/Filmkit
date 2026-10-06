> 存档自 /tmp/cacorr,2026-10-06

# 镜头横向色差校正（FilmKit v0.10.170）实施报告

工作区：`/Users/wl/Downloads/胶片相机`
基准样片：`/Users/wl/.dsh/attachments/v1/objects/80/803b74ec…2fce2a`（JPEG 1777×2360，无 EXIF）
脚本与原始输出：`/tmp/cacorr/`（`base.py` `diag2.py` `diag3.py` `diag4.py` `dtca.py` `mine.py` `final_est.py`）

---

## 0. 一句话结论

**模块已按需求实现、可 A/B、clean 构建通过；但离线数据表明：这张基准样片的紫边不是横向色差，位移模型对它没有任何作用对象 —— 判据把它正确地判为「不动作」（`caApplied=0`，输出逐位等于改动前）。因此建议的使用方式是「保持默认开（对无 LCA 场景零动作），但不要指望它消除这张图的紫边」；真正对症的方向见 §9。**

预期的真机读数：`caKraw≈[0.0001, 0.0003]`、`caK=[0,0]`、`caShiftR=caShiftB=0`、`caApplied=0`、`caMs≈1~6`（估计开销）。

---

## 1. PhotonCamera 的 ChromaticFlow：**未接线（确认）**

核实方法与结果（`/tmp/pc`）：

| 检验 | 命令 | 结果 |
|---|---|---|
| 是否有实例化 | `grep -rn "new ChromaticFlow" --include=*.java .` | **0 处**（唯一命中是 `ChromaticFlow.java:8` 的构造函数自身） |
| 管线装配表 | `PostPipeline.BuildDefaultPipeline()`（`PostPipeline.java:1477-1547`） | `Bayer2Float → KernelNetPrep → Demosaic/Amaze → (ESD3D2) → ABLC → AutoExposureCurve/LinearExposure → Initial/ModernInitial → UpscaleCrop → LocalLaplacian2 → CaptureSharpening → **CorrectingFlow** → Sharpen2 → RotateWatermark`。**无 ChromaticFlow** |
| 字符串引用 | `grep -rin chromatic -l .` | 只命中 `ChromaticFlow.java`、`esd3d2*.glsl`（那里的 chromatic 指色度降噪，不是色差） |

**并且这段代码本身是未完成品**：`chromaticcomp.glsl:4` 是 `#define SIZE 0,0`，第 31-32 行 `texture(InputBuffer, (gl_FragCoord.xy+vec2(xyr))/vec2(SIZE))` —— **除以 0**，uv 恒为 inf/NaN。即便把它接进管线也只会得到垃圾。同一 pass 的 `chromaticgrad.glsl` 逻辑（±2 中心差分算 R/B 梯度）本身可读，但下游消费者没写对。

**⇒ 结论：PC 没有启用横向色差校正。不能用它解释「PC 没有紫边」。**

我的判断（**推断，未在 PC 上跑过基准样片，属 §9 的「无法判定」**）：PC 紫边少更可能来自 ① 去马赛克用 **Amaze**（伪彩特征与我们的 RCD 不同）；② **ESD3D2** 在 RAW/Bayer 域做时域+空域**色度引导**降噪（`esd3d2_steered.glsl:94-98` 的 `sigZ` 机制我们已移植为档 A `[48] moire`），高频色度伪影被平均掉一部分；③ `CorrectingFlow` 的镜头畸变重采样顺带轻微改变了色差；④ 场景/曝光不同。**① ② 是可以验证的方向，③④ 无法离线判定。**

---

## 2. darktable 两套算法对比与选择

| | `cacorrect.c`（旧） | `cacorrectrgb.c`（新） | 我们的选择 |
|---|---|---|---|
| 作用域 | **RAW/Bayer 镶嵌**（`description`：linear, raw）| **线性 RGB**（linear, RGB）| D3 = 线性相机 RGB ⇒ 只有 rgb 版域匹配 |
| 位移模型 | 128×128 分块（`ts=128`）的**逐块移位搜索** + `_LinEqSolve` 二次曲面拟合（`fitparams[2][2][16]`），`blockwt` 加权 | **没有位移概念**：以 guide(=G) 的局部均值为界把像素分 higher/lower 两个 **manifold**，模糊后取"通道比"的局部均值 | — |
| 校正数学 | 按拟合出的块位移场重采样 R/B | `out_c = pixel_g · ratio`，`ratio = ratio_low^w_low · ratio_high^w_high`（`apply_correction`，权重由 G 在两条 manifold 之间的 log 位置决定） | `p_src = p + r̂·clamp(k·r, ±4)`，只重采样 R/B |
| 参数 | `avoidshift`(bool)、`iterations`(2/3/5) | `guide`(R/G/B, 默认 G)、`radius`(1–500, 默认 5)、`strength`(0–4, 默认 0.5)、`mode`(standard/darken/brighten)、`refine_manifolds` | `ca_corr`（0=关，>0=强度倍率，默认 1.0）；判据常量 `kCaMinShift/kCaConsist/kCaMaxShift` |
| 迭代 | **有**（`iterations`，正为大位移下的估计低估而设） | 无 | 无（理由见 §5.3、§9） |
| GPU 适配 | 分块搜索 + 位移场上传，且域是去马赛克**前** ⇒ 不适用 | 域匹配，但需要 σ=5 的多趟高斯（全分辨率）+ manifold 上下采样 | 单 pass（8 次 texelFetch/像素）+ 一次 1/8 readPixels |
| 红线风险 | 位移模型不改色度结构 | **改写 R/B 的高频结构** ⇒ 会削正常色边（dt 自己因此加了 `reduce_artifacts` + `safety = 20^(1-strength)`） | 位移 < 0.5px 时重采样近似恒等 ⇒ 天然安全 |

### 选择依据（含被数据否掉的部分）

**照搬 `cacorrectrgb` 被实测否掉**：离线复现其完整算法（σ=5、downsize=3、manifold + normalize + apply + reduce_artifacts，线性域，见 `dtca.py`），对基准样片扫描：

| radius / strength | purple mean | purple P99 | >10 像素占比 | 紫边掩膜内 mean | **clean\|R−G\|** | **clean\|B−G\|** |
|---|---|---|---|---|---|---|
| 基线 | 2.0588 | 29.50 | 3.323% | — | 5.6961 | 5.1075 |
| 5.0 / 0.25 | −2.2% | −5.4% | −2.5% | −6.1% | −0.36% | −1.86% |
| 5.0 / 0.5（dt 默认） | −3.7% | −10.0% | −4.0% | −10.3% | −0.34% | −2.49% |
| 5.0 / 1.0 | −5.6% | −17.9% | −5.1% | −16.4% | −0.21% | −2.97% |
| **5.0 / 2.0** | **−6.4%** | **−22.6%** | −5.7% | **−19.0%** | −0.13% | **−3.04%** |
| 2.0 / 2.0 | −4.0% | −11.7% | −2.5% | −9.9% | −0.09% | −1.22% |

即：即便把 strength 推到 dt 推荐值 4 倍，紫边 mean 只降 6.4%，而 **clean 区 `mean|B−G|` 掉 3.04%**（= 正常色边被一起削掉，正是用户划的红线方向）。**收益/代价比不可接受。**

**最终选择「径向位移模型 + 自动估计」**，理由：
1. **域匹配且物理正确**：横向色差就是径向单调的几何位移，自由度只有 1 个系数/通道 ⇒ 噪声无法伪装成位移（这也是它比 PC 的逐像素梯度估计稳健一个量级的原因，PC 那段代码还根本没接线）；
2. **GPU 成本可忽略**（单 pass + 一次小 readPixels），无镜头配置文件也能自动定标；
3. **不改色度结构** ⇒ 红线天然守住（§5.4）；
4. 需要多系数时再升级（`cacorrect.c` 的分块位移场 = 我们的下一档）。

---

## 3. 实现：域选择、算法、参数

### 3.1 插入位置的论证（为什么是 D3 = 去马赛克后 / CCM 前）

- **色差是纯几何位移**。D3（`tex_work_`，RCD 后、`passCcm` 前）通道**尚未被 CCM 混合** ⇒ 改 R/B 不会污染颜色矩阵的输入；放到 CCM 之后（D4/场景线性）改，等于在已混合的通道上叠加位移，会把"位移"错误地翻译成"色偏"。
- **G 作参考通道逐位不动**（PC 与 dt 的共同口径）：G 采样最密、信噪比最高；动 G 会把整幅画面的亮度结构一起带走（这正是"过曝边缘品红"一类事故的温床）。
- **具体位置在 `diagStatsTex("D3-camrgb")` 之后、`passCcm` 之前**：保持既有诊断基线的语义（统计到的是校正前的相机 RGB，历史基线可比）。
- `passCcm` 新增 `src` 参数；不动作时传 `tex_work_`（**新纹理只在动作时才成为 CCM 的输入**）。

### 3.2 算法（自动估计 + 径向位移校正）

**① 估计**（`kFsCaDown` + `estimateCaRadial`，CPU）
- 1/8 box 降采样（8×8 均值，同时把估计噪声压到 1/8）→ `glReadPixels`（RGBA16F + GL_FLOAT，与 `passDefringe` 同组合，X300 已验证）。
- 逐像素一维**径向 Lucas-Kanade**：约束 `d = k·r·r̂`，最小化 `Σ(k·r·(∇G·r̂) − (G−ch))²`
  ⇒ `k = Σ[r·g_r·(G−ch)] / Σ[r²·g_r²]`，`g_r = ∇G·r̂`。
- `∇G` 代替 `∇ch`（PC 与 dt 共同口径）；`It = G−ch` 先做 3×3 box 高通（缓变色偏不该被读成位移）。
- **只用强梯度像素**（能量 ≥ P50 分位）：LCA 只在高对比边缘显现，平坦区只有噪声。
- 累加分三组（全体 / 内半区 r<0.6R / 外半区）。

**② 判据**（两条都必须满足，否则该通道不动作）
| 常量 | 值 | 含义 |
|---|---|---|
| `kCaMinShift` | **0.5 px** | 画面角部位移下限。低于半个像素的横向色差在成片上看不出来；而"看起来像 LCA"的噪声拟合恰好落在这一区间 |
| `kCaConsist` | **0.5 ×max(\|k_in\|,\|k_out\|)** | 内/外半区一致性：真实 LCA 由**同一个 k** 支配所有半径（位移 ∝ r），噪声/局部伪影拟合的 k 在内外半区互不相容 |
| `kCaMaxShift` | **4.0 px** | 单通道位移上限（1/8 估计图的梯度尺度 8px ⇒ 可测 ±4px 的保守半幅） |

**③ 校正**（`kFsCaCorr`，单 pass）
- `p_src = p + r̂ · clamp(k·r, ±4)`，只重采样 R/B，G 逐位原样。
- 双线性**手写**（4×`texelFetch` + `mix`）：本项目 `makeTex` 对 RGBA16F 只用 `GL_NEAREST`（X300 quirk）；边缘按纹素中心范围 clamp（等价 `CLAMP_TO_EDGE`）。
- 中心 r<1px 不动（位移恒 0 的物理必然）。

### 3.3 边缘处理方式（用户要求项）
- **采样点**：clamp 到 `[0.5, size−0.5]`（纹素中心范围），与 `GL_CLAMP_TO_EDGE` 语义一致；
- **估计**：排除图像最外 1 像素圈（中心差分需要邻居），排除 r<1px 的中心区；
- **是否按半径缩放**：是 —— 位移 = `k·r`（线性 ∝ 半径，近轴横向色差的标准模型），**不是**固定平移、也不是 `k·r²`；
- **最大位移**：4.0 px（可改，`kCaMaxShift`）。

---

## 4. 改动坐标

| 文件 | 位置 | grep 串 |
|---|---|---|
| `core/engine/src/film/FilmParams.h` | `kCount = 51`（L14）；`float ca_corr`（L189）；`fromDoubles` 的 `if (n > 50)`（L246） | `grep -n "ca_corr\|kCount = "` |
| `core/engine/src/render/FrameRenderer.h` | prog 成员 L514；纹理/dbg 成员 L585-596；`passCcm(..., GLuint src=0)` L384；`estimateCaRadial` L388；`passCaCorrect/ensureCaTargets` L411-412；uniform 结构体 L875-882 | `grep -n "prog_ca_\|u_ca_\|dbg_ca_\|passCaCorrect"` |
| `core/engine/src/render/FrameRenderer.cpp` | shader `kFsCaDown` L1051、`kFsCaCorr` L1076；compile L4383；link L4455；uniform 查询 L4887；释放 L5050/L5087；判据常量 L6417-6419；`passCaCorrect` L6430；`ensureCaTargets` L6494；`estimateCaRadial` L6525；**调用点** L7180（`GLuint ccm_src = tex_work_`）；日志字段 L7429 | `grep -n "kFsCa\|ccm_src\|caKraw"` |
| `app/src/main/java/com/filmkit/app/ui/CameraPresets.kt` | `DoubleArray(51)` L24；`this[50] = 1.0` L158（注释里的硬契约说明同步改成 51） | `grep -n "DoubleArray(\|this\[50\]"` |
| `app/src/main/java/com/filmkit/app/camera/CameraController.kt` | `CA_CORR` L1086、`caCorrPref` L1092、`applyCaCorrPref()` L1183 调用 + L1372 定义 | `grep -n "CA_CORR\|applyCaCorrPref"` |
| `app/src/main/java/com/filmkit/app/ui/SettingsScreen.kt` | 「镜头色差校正」开关（`prefs("ca_corr")`），defringe 开关之后 | `grep -n "ca_corr"` |
| `app/build.gradle.kts` | `versionCode 309` / `versionName "0.10.170"` | `grep -n versionName` |

APK：`filmkit-0.10.170.apk`（仓库根）。

---

## 5. 离线验证（全部数据可复跑）

### 5.1 基准样片到底是什么（决定性测量）

**① 半径分带：紫边强度随半径 `r` 递减，与横向色差相反**

| 半径带 | 像素数 | purple mean | >10 占比 | 强边缘像素上的 purple mean | 强边缘 >25 占比 |
|---|---|---|---|---|---|
| 0–246 | 190 240 | **6.92** | **20.45%** | **14.01** | 19.3% |
| 246–492 | 570 640 | 3.25 | 7.56% | 5.26 | 4.1% |
| 492–738 | 951 124 | 2.29 | 4.62% | 3.23 | 1.7% |
| 738–984 | 1 222 272 | 1.26 | 0.77% | 2.65 | 0.45% |
| 984–1230 | 974 048 | 1.54 | 0.40% | 3.89 | 0% |
| 1230–1476 | 285 392 | 0.86 | 0% | 2.41 | 0% |

（"强边缘"= `|∇G|` 的 P90 以上，占 9.98%；此归一化排除了"边缘只在中心"的场景解释。）

紫边**集中在画面中心**（r<246 带 20% 的像素 >10），而横向色差的位移与可见性**随半径增强**。目视确认：中心 = 逆光窗（暗衣物/晾衣杆 vs 极亮天空的高对比边缘），外围 = 暗室内墙（无高对比边缘）。

**② 逐块 Lucas-Kanade 位移场（432 个 96×96 块，全图）**

| | \|d\| mean | median | P90 | **径向分量 mean** | **corr(r, 径向分量)** | 拟合斜率 |
|---|---|---|---|---|---|---|
| R vs G | 0.129 px | 0.070 | 0.264 | **−0.005** | **0.040** | 0.00002 px/px |
| B vs G | 0.154 px | 0.069 | 0.351 | **+0.011** | **−0.076** | −0.00004 px/px |
| 紫边密集块（41 个） | 0.230 / 0.163 | — | — | +0.020 / +0.042 | — | — |

**R/B 相对 G 的位移是 0.1–0.15 px 量级，且与半径零相关（斜率 ~2e-5 px/px ⇒ r=1000 处 0.02 px）。真横向色差在边缘处应有 1–3 px 且斜率 ≈ k。**

**③ 全图径向缩放扫描 & 全局平移搜索：最优解 = 零位移**
- `sR` 扫描：`1.0005` 时 purple mean **−0.1%**，其余全部变差（`0.999` → +5.5%，`1.002` → +6.4%）；
- `sB` 同样：`1.0005` → **−0.1%**；
- 全局整数平移 `(dx,dy) ∈ [−4,4]²`：**最优就是 (0,0)**，任何非零平移都让紫边变差（`(0,−1)` → +5.6%）。

**⇒ 这张成片里不存在可被位移模型消除的横向色差。**

**④ 附带发现：成片是 JPEG 4:2:0**（解析 SOF0：Y `h=2,v=2`，Cb/Cr `1,1`）。强亮度边缘的色度下采样渗漏会产生"与边缘对比度相关、与半径无关、不产生位移"的彩色镶边 —— 与上述全部观测一致，是本样片紫边的一个强嫌疑源（**推断，见 §9**）。

### 5.2 我的模块在这张图上的输出（= 真机预期）

Python 逐行复现 `estimateCaRadial`（`final_est.py`）：

| 域 | kR（角部） | kB（角部） | 内半区 kB | 外半区 kB | 判据 | 结果 |
|---|---|---|---|---|---|---|
| **线性（= 我们的 D3）** | +0.00009（**0.127 px**） | +0.00028（**0.408 px**） | 0.488 px | 0.241 px | R：位移 < 0.5px 否决；B：位移 < 0.5px 否决 | **`caApplied=0`、`caShiftR=caShiftB=0`** |
| 显示域（成片自身） | +0.00010（0.141 px） | +0.00017（0.249 px） | 0.315 px | 0.148 px | 同上，全部否决 | `caApplied=0` |

**红线（核心要求）：因为 `caApplied=0`，`ccm_src` 仍是 `tex_work_`，`passCcm` 读的原纹理 ⇒ 输出与加本模块之前逐位相同。** 因此：

| 指标 | 校正前 | 校正后 | 变化 |
|---|---|---|---|
| purple mean（全图） | 2.0588 | 2.0588 | **0** |
| purple P99 | 29.50 | 29.50 | **0** |
| >10 像素占比 | 3.323% | 3.323% | **0** |
| **clean 区 `mean\|R−G\|`** | **5.6961** | **5.6961** | **0（红线守住）** |
| **clean 区 `mean\|B−G\|`** | **5.1075** | **5.1075** | **0（红线守住）** |
| 左右扇区紫边占比 | [18.8, 8.4, 8.1, 15.1, 26.3, 9.9, 5.8, 7.7]% | 同左 | **0** |

（扇区口径：紫边掩膜 `purple>10` 相对画面中心的 8 扇区分布，0° = 右、顺时针。与用户给的 [25.7, 9.9, 6.2, 8.3, 19.5, 7.9, 6.4, 16.2]% 同型 —— 左右侧的晾衣杆/窗框是竖直边缘，故按位置落进左右扇区，**这是场景内容而非光学特征**。）

### 5.3 合成 LCA 对照：判据会触发吗？校正有效吗？

向基准样片**人为注入**已知径向色差（线性域，`1/(1+k)` 重采样），再跑同一套估计 → 校正：

| 注入 | 判据判决 | 估计出的 k | 去除的注入误差（RMSE vs 干净图） | purple mean |
|---|---|---|---|---|
| R +2.2px / B −1.2px | R **ACCEPT**（1.205px）；B 否决（−0.255px，低于 0.5px） | 1.205 px（= 注入的 55%） | **28%** | +10.1% → +7.0% |
| R、B 都 +3.7px | R、B **都 ACCEPT**（1.904 / 2.449 px = 注入的 52% / 66%） | — | **49%** | **+45.0% → +20.7%** |
| R 仅 +1.2px | R ACCEPT（0.704px） | — | **28%** | +5.1% → +3.6% |

**结论**：判据的判别力成立（真实片全否决、合成 LCA 触发），但**估计器系统性低估 35–48%**，因此校正只**部分**有效（去除 28–49% 的注入误差，不是全额）。低估来源是「用锐利的 ∇G 去匹配被重采样钝化的 R/B 边缘」+ 一维线性化在大位移下的经典衰减；**修法是迭代**（`dt cacorrect.c` 的 `iterations` 参数正是为此存在），本版未做（成本/复杂度 vs 本项目场景用不到，见 §9 的升级路径）。

**重要限定**：合成 LCA 是**重采样**出来的（会把 R/B 边缘磨钝），真实光学 LCA 的 R/B 高频损失通常更小 ⇒ 上表是**悲观下界**；乐观情形需真机/RAW 数据才能定。

### 5.4 真机判据

**日志字段**（`finishStillChain` 的 JSON kv，与 `hlGRecon` 同一批）：

| 字段 | 含义 | 预期读数（本样片 / X300） |
|---|---|---|
| `caKraw` | 原始估计的径向系数 `[kR, kB]` | `[≈0.0001, ≈0.0003]`（位移 = k·r，r 自画面中心） |
| `caK` | **实际采用**的系数（0 = 判据否决） | `[0, 0]` |
| `caShiftR` / `caShiftB` | 画面角部实际位移（px，已乘强度并 clamp ±4） | `0` / `0` |
| `caApplied` | 1 = 真的跑了校正 pass | **`0`** |
| `caMs` | 本模块端到端耗时（**含估计**：`glFinish` + 1/8 降采样 + readPixels + LK） | 预计 1–6 ms（**待真机确认**；这是本模块唯一新增的常驻成本） |

**判读规则**：`caApplied=0` 且 `caKraw≈0` ⇒ 判据正确识别"无横向色差"（本样片的预期）；若 `caKraw` 与 `caK` 不同 ⇒ 看是"位移太小"还是"内外半区不一致"（`caKraw` 有值而 `caK=0` 即后者）。

**成片目视**（`caApplied=1` 的真 LCA 场景）：
- 画面**角部**的紫/青边应减轻（位移 ∝ r ⇒ 中心几乎不动，符合横向色差的物理）；
- 正常彩色物体**不应褪色**（G 逐位不动、R/B 只做亚像素几何重采样，不触碰色度结构）；
- 若 `caShiftR/B < 1px`，视觉上应几乎看不出变化（预期之内）；
- A/B：设置页「镜头色差校正」开关（`prefs("ca_corr")`）同场景连拍两张对比。

### 5.5 红线专项结论

**校正前后在基准样片上逐位相同**（§5.2）⇒ 非紫边区色度保真度**不可能下降**，这是本设计相对 manifold 法（`clean|B−G|` −3.04%）的核心优势。真正的红线风险只存在于 `caApplied=1` 的场景，而那需要角部位移 ≥ 0.5px 且内外一致 —— 此时位移本身已经可见，校正方向是把通道**拉回对齐**而不是"抹掉色边"。

（另用 `test_temp/*.dng` 做过对照意图：本模块的运行域在 RAW 链内部，离线 Python 无法复现 RCD/WB 之后的 D3 数值，故未用于红线量化 —— 见 §9。）

---

## 6. 默认值与 A/B

- **默认 = 开（`ca_corr = 1.0`，`prefs("ca_corr")` 默认 true）**。
- 依据：① 双重判据保证**无 LCA 场景零动作**（§5.2 离线实测：本样片 `caApplied=0`、输出逐位不变）；② 只有默认开，真实存在 LCA 的镜头/场景才能被自动校正（默认关 = 模块永不生效）；③ UI 开关 + `FilmParams[50]` 双通道 A/B 齐全。
- **但它不是本样片紫边的解**：离线证据明确显示位移 ≈ 0（§5.1），所以**不要**用"开了色差校正"来解释或期待这张图的紫边消失。
- **改默认关的一行位置**：`app/src/main/java/com/filmkit/app/ui/CameraPresets.kt:158` 的 `this[50] = 1.0` → `0.0`（同时把 `CameraController.applyCaCorrPref` 的 `getBoolean("ca_corr", true)` 改 `false`）。触发条件：真机发现 `caMs` 明显（>15ms）或出现误动作（`caApplied=1` 而画面无色差）。

---

## 7. 自查清单（逐条）

| # | 项 | 结果 |
|---|---|---|
| 1 | **新 uniform 有非零下发路径** | ✓ `uKR/uKB` 在动作时 = `k·strength`（离线实测合成场景 0.00082 / 0.00249，非零）；`uMaxShift` 恒 4.0；`uCenter` 恒 = 尺寸/2；`uSize` 恒非零；`uTex` 绑 `tex_work_` |
| 2 | **开关关闭时逐位等于改动前** | ✓ 三级保证：① `p.ca_corr ≤ 0.5` ⇒ 根本不调用 `passCaCorrect`；② 判据否决 ⇒ 返回 false；③ 返回 false ⇒ `ccm_src = tex_work_`，`passCcm` 读的原纹理。新增的 `tex_ca_/tex_ca_ds_` 只在动作时才被 CCM 消费 |
| 3 | **`DoubleArray` 长度与索引一致** | ✓ `FilmParams::kCount = 51`（`FilmParams.h:14`）↔ `CameraPresets.neutral()` 的 `DoubleArray(51)`（`CameraPresets.kt:24`）；新参数在索引 **50**（`this[50] = 1.0`）；索引 29 未复用（仍是留空槽）；`fromDoubles` 用 `if (n > 50)` 防呆（旧长度不丢参数） |
| 4 | **日志字段真的出现** | ✓ `arm64-v8a/libfilmengine.so` 的 `strings` 里有 `caShiftR` / `caShiftB` / `caApplied` / `caKraw` / `caMs`；写入位置在 `finishStillChain` 内、与 `hlGRecon` 同一批 kv（日志发出前） |
| 5 | **`git diff` 只落在预期位置** | ✓ 我的改动只落在 §4 的 7 个文件；`git diff --stat` 里 `EngineBridge.kt`/`Bridge.cpp`/`Logger.*` 的改动是**并发任务**的（我未触碰）。关键标记 grep 与开工前基线一致：`diag_stats 5`、`scEff 14`、`moGain 3`、`hlPre 3`、`hlWClamp 2`、`kSceneLinearNorm 2` 全部不变；`diagStatsTex 5→6` **仅因我新增的一条注释**（L6427），5 个代码调用点未动 |
| 6 | shader 校验 | ✓ `bash tools/validate-shaders.sh` 全绿（含新增 2 个 shader；注释全部避开半角引号与 `)"` 序列） |
| 7 | clean 构建 | ✓ `./gradlew clean :app:buildFilmEngine :app:assembleDebug` → BUILD SUCCESSFUL；`.so` 含新代码（`strings` 验证） |
| 8 | 无残留/无泄漏 | ✓ `prog_ca_ds_/prog_ca_corr_` 在 release 路径 `glDeleteProgram`；`tex_ca_/tex_ca_ds_` + 两个 FBO 在 release 路径 `glDeleteTextures/Framebuffers` |
| 9 | 日志不残留 | ✓ 调用点每次成片先清零 `dbg_ca_*`（关闭时 pass 不被调用，否则会残留上一帧的值） |

---

## 8. 性能

| 项 | 成本 | 依据 |
|---|---|---|
| 估计降采样 | 1/8 图 × 64 次 texelFetch = 全图 1 次采样量 | `kFsCaDown` |
| readPixels | 223×295×4 float ≈ **1.05 MB** | 离线实测 ds 尺寸 |
| CPU LK | ~33 k 有效边缘像素 × ~15 flops ≈ 可忽略 | `estimateCaRadial` |
| 校正 pass（仅动作时） | 全分辨率 × 8 texelFetch | `kFsCaCorr` |
| **合计（不动作，即本样片）** | `glFinish` + 1 次小 pass + 1.05MB 回读 | **真机读数看 `caMs`**（离线无法测 GPU/驱动同步开销） |

---

## 9. 无法判定的部分 + 建议的下一步

### 9.1 无法判定（诚实边界）

1. **这张成片的紫边到底是哪一个机制**：4:2:0 色度下采样渗漏 / 逆光散射（veiling glare）/ 高光通道裁剪 / 去马赛克伪彩，我只能证明**不是几何位移型色差**，不能在三者中定论。判别实验未做：把同一场景的 **RAW（DNG）** 走我们自己的链，与成片对比 —— RAW 链里没有 JPEG 编码，若紫边消失则 4:2:0 是主因。
2. **PC 为什么紫边少**：§1 的推断（Amaze / ESD3D2 色度引导降噪）**没有在 PC 上跑过基准样片**验证。
3. **本模块在真机 GPU 上的数值行为**：C++ 双线性（`texelFetch` + `mix`）与离线 Python 实现是纸面等价，**没有逐位对拍**；`tex_ca_` 的 RGBA16F 中间精度损失也未测。
4. **估计器低估的准确修法**：合成实验显示低估 35–48%，但合成用的是重采样（会磨钝边缘），真实光学 LCA 的低估幅度未知 ⇒ 迭代次数无法离线定标。
5. **成片是否出自 FilmKit**：该 JPEG **无任何 EXIF**，尺寸 1777×2360；若是 Android `Bitmap.compress(JPEG, q)`（`CameraController.kt:3208/3219`）产出，则 4:2:0 是 libjpeg-turbo 的行为 —— 但我无法确认这张图的来源。
6. **`test_temp/*.dng` 未被用于红线量化**：本模块运行在 RAW 链内部（RCD+WB 之后的 D3），离线 Python 无法复现该域的数值，故只用成片做红线验证。

### 9.2 建议的下一步（按性价比排序）

1. **先做 9.1-① 的 RAW 对照实验**（成本最低、结论最硬）：把同一逆光场景的 DNG 用我们的链出一张成片，看紫边是否仍在。
2. **若紫边来自 4:2:0 编码**：`CameraController.kt:3208/3219` 换用 4:4:4 编码器（Android `Bitmap.compress` 无法指定采样因子 ⇒ 需换 libjpeg-turbo 直编或改存 PNG）—— **这可能比任何色彩链改动都更对症**，且几乎是纯收益（质量优先原则下值得拍板）。
3. **若紫边来自逆光散射/去马赛克伪彩**：强化已有的 `defringe`（[47]，本就对症"贴边品红"，离线口径紫边 ↓63%）—— 调阈值/强度比新加色差模块划算得多。
4. **本模块的升级（仅在确认存在真 LCA 的证据后做）**：
   - **加迭代**（恢复率 28–49% → 预期 60–80%）：在 `passCaCorrect` 内把 ①→③ 循环两遍，第二遍源换 `tex_ca_`、k 累加 —— `dt cacorrect.c` 的 `iterations` 同款；
   - 若出现**非径向/偏心**色差（角落不一致）：升级为 `cacorrect.c` 的分块位移场 + 曲面拟合；
   - 更好的估计尺度：用 1/4 降采样（读回 4 MB）换取更小的线性化偏差 —— 需真机 `caMs` 权衡。
