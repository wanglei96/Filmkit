# E4 胶片特有参数 · 引擎设计文档（槽位契约 + 算法规格 + 拆分）

> 架构师：DSH 会话（E4-0，2026-10-08）。上游：`docs/research/10-内建编辑器与胶片预设.md`（10-04 调研）、
> `docs/reports/editor-ADR.md`（E1–E4 立项与门禁）。
> 本文档 = **硬契约（槽位表）+ 逐参数算法规格 + 子任务拆分 + 验证方案**。
> 只读代码调研产出，**未修改任何源码**。
>
> 代码基线：`core/engine/src/render/FrameRenderer.cpp`（11555 行）、
> `core/engine/src/film/FilmParams.h`（406 行）、
> `app/src/main/java/com/filmkit/app/ui/CameraPresets.kt`（231 行）。
>
> ⚠️ **行号引用说明**：`core/engine/` 下的行号是**稳定基线**（本次未改动，可直接引用）。
> `app/` 下的行号会随 E1/E2/E3 线**并行推进而漂移**（本次调研期间 `CameraController.kt`
> 已从 1033 行漂到 1064 行）⇒ **引用 `app/` 下代码时请按符号名搜索，不要用行号**。

---

## 0. 结论摘要（先看这一段）

1. **调研清单里 7 个"已存在但未启用"的槽位，实际状态分三类**：
   - `shoulder[3]`[5..7] / `toe[3]`[8..10] = **死槽位**：旧 log 域胶片链 v0.7.9 整体删除后，shader 内**零引用**（`grep` 全仓只有 `FilmParams.h` 的声明与解析）。它们是本次要"复活"的 6 个槽位。
   - `split_warm`[23] / `split_cool`[24] / `monochrome`[25] / `grain_amount`[26] / `grain_size`[27] = **已实现且 shader 内生效**，但 `CameraPresets.neutral()` 未设置 ⇒ 走 C++ 结构体默认值（0/0/0/0/1.0）⇒ **默认全部无效果**，且**无任何 UI**。
2. **暗角（vignette）与 halation 在引擎里完全不存在**（全仓 `grep -i "vignette|halation"` 在 `core/`、`app/src/main/` 下 **零命中**；只有文档提到）。bloom 也只在 `docs/开发计划.md` 的规划里。
3. **现有颗粒实现与 darktable `grain.c` 有 6 处实质差异**，其中 3 处是"必须改"（尺寸语义倒置、噪声源不是颗粒频谱、无相纸响应密度调制），3 处是"待实测"（调制曲线形态、叠加域、单色形式）。详见 §3.1.3。
4. **槽位分配**：复用 [5..10]（6 个）+ [26][27]（2 个），新增 **[60..77]（18 个）**，`kCount` 60 → **78**。逐行分配见 §2。
5. **最大风险 = halation 的 GPU 代价与纹理内存**：16 个新 pass、峰值额外 ~58MB RGBA16F、约 122M texel fetch（≈9.7 个全分辨率等效 pass）。Mali-G1-Ultra 上必须实测，且**降级方案必须在设计阶段就备好**（§3.2.8），删不删由真机数据与用户拍板。
6. **一个必须先说清的调研偏差**：调研 §2.4 写"负片**暗部**颗粒最重、亮部最少"；而 darktable `grain.c` 的密度调制是**相纸响应（photographic paper response）**，默认参数下**中间调最重、两端衰减**。两者方向不同。本文档的处理 = **第一版逐行对齐 darktable 原版**（默认行为可复现），另加 [61] `grain_pivot`（默认 0 = 原版逐位不变）作为"把调制峰值移向暗部"的可选维度，**是否启用等真机数据**（§3.1.5）。

---

## 1. 现状核对表

### 1.1 逐槽核对（截至 v0.10.196）

| 槽位 | 名称 | shader 是否实现 | 位置（函数 + 行号） | 实现完整度 | C++ 默认值 | Kotlin `neutral()` | UI |
|---|---|---|---|---|---|---|---|
| [5..7] | `shoulder[3]` | ❌ **无** | — | **死槽位**：旧 log 域链（±4EV 编码 + contrast/toe/shoulder/cross）v0.7.9 整体删除，见 `FrameRenderer.cpp:3941-3945`、`FrameRenderer.h:1098` | `{0,0,0}` | 未设置（=0） | 无 |
| [8..10] | `toe[3]` | ❌ **无** | — | 同上 | `{0,0,0}` | 未设置（=0） | 无 |
| [11..19] | `cross[9]` | ❌ **无**（同为死槽位） | — | 同属被删的旧 log 域链；`FrameRenderer.h:1098` 明示 | 单位阵 | 单位阵 | 无 |
| [20..22] | `sat_shadow/mid/high` | ✅ | `kFsFilm`，权重 `FrameRenderer.cpp:4337-4342` | 完整：三分区加权标量饱和度，`out3 = mix(vec3(l), out3, sat)` | `1,1,1` | `1,1,1` | 无 |
| [23] | `split_warm` | ✅ | `kFsFilm:4343-4344` | 完整但**硬编码色向量**：`out3 += uSplit.x*ws*vec3(0.05,0.02,-0.04)` | `0.0` | 未设置（=0） | 无 |
| [24] | `split_cool` | ✅ | 同上 | `+ uSplit.y*wh*vec3(-0.03,0.0,0.05)` | `0.0` | 未设置（=0） | 无 |
| [25] | `monochrome` | ✅ | `kFsFilm:4390` | 完整：`out3 = mix(out3, vec3(luma(clamp(out3,0,4))), uMono)` | `0.0` | 未设置（=0） | 无 |
| [26] | `grain_amount` | ✅ | `kFsOut:4548-4553` | 见 §3.1.3 差异清单（**不完整**） | `0.0` | 未设置（=0） | 无 |
| [27] | `grain_size` | ✅ | `kFsOut:4549` | **语义倒置**（值越大颗粒越细）+ 无范围夹取 | `1.0` | 未设置（=1.0） | 无 |

**核对方法**：`grep -rn "uShoulder|uToe|uSplitWarm|split_warm|uMono|uGrain" core/engine/src/`；
`grep -n "uSplit\|uSat\|uMono\|uGrain" core/engine/src/render/FrameRenderer.cpp`。

### 1.2 关键补充事实（都影响设计）

| 事实 | 证据 | 对设计的影响 |
|---|---|---|
| `uTime` **有下发**，值 = `frames_.load() % 512` | `FrameRenderer.cpp:11540` | 颗粒位置每帧变（预览会闪）；成片取拍照瞬间值。**颗粒需要自己的确定性种子策略**（§3.1.6） |
| 颗粒用的 `hash()` 是 `sin` 版 | `FrameRenderer.cpp:4443` | 大坐标（4080×3072）下 highp `sin` 精度不足会产生**规律图案** —— 项目已为 dither 换过 `dhash`（`4444-4451`），颗粒**没换** |
| `kFsOut` 同时服务预览与成片 | 调用点 `6384`（预览）/ `6533`、`8841`（成片） | 新增的 OETF 后效果（颗粒/暗角）**预览也跑**，性能要按预览帧率一起评估 |
| `kFsFilm` 输出 = **线性显示域** `[0,1]` | `filmicTone` 数学有界（`4062` `clamp(...,lo,1.0)`），`oColor = vec4(out3,1.0)`（`4391`） | halation 若落在此域，阈值语义直观、叠加不会产生不可控 HDR 值 |
| `kFsOut` 内已有**指数软肩** `highlightRolloff`（knee 0.80） | `FrameRenderer.cpp:4473-4481` | halation 的"加光后过曝压缩"可**直接复用它**，不需要在 halation pass 里另做 shoulder（emulsion 是自带的，我们白拿） |
| GLSL ES **不支持数组初始化器** | 仓库已踩坑两次，注释见 `4499` | 新增 shader 的权重表**不能**写成 `const float w[5] = float[5](...)`，必须用独立 uniform / 独立常量 |
| 3D LUT 在 `kFsOut` 内、OETF **之后** | `4546-4547`（`if (uLutOn>0.5) c = mix(c, lutApply(c), uLutStrength)`） | "LUT 必须在 OETF 之后"的硬约束**当前满足**；新参数不得破坏它 |
| filmic 是 darktable v3 spline，`latitude` **已是函数参数**，当前调用只传 3 个实参 | 声明 `FrameRenderer.h:548-549`（`latitude = 0.01f` 默认）；调用 `FrameRenderer.cpp:11362` | 趾部/肩部形状**可以**落在 CPU 侧 spline 求解，**零 GPU 成本**（§3.4） |

### 1.3 暗角 / halation / bloom 搜索结果（结论：全部不存在）

```
grep -rni "vignette|uVig" core/engine/src/ app/src/main/     → 0 命中
grep -rni "halation|halat" core/engine/src/ app/src/main/    → 0 命中（仅 docs/）
grep -rni "bloom|uBloom|glow" core/engine/src/ app/src/main/ → 1 命中：
    FrameRenderer.cpp:3245  uniform float uDelta;  // bloom_vs_details   ← PC 移植的变量名，非 bloom pass
```

- **vignette**：引擎内不存在。唯一相关物是 **LSC（镜头阴影校正）**，那是**补偿**真实失光，方向与暗角相反（`SettingsScreen.kt:339` 提到"消暗角"）。
- **halation**：引擎内不存在。`docs/开发计划.md:198` 有规划（"红通道高光阈值 → 多级可分离高斯模糊 → 加性复合"），未实现。
- **bloom**：不存在。PC 的 `bloom_vs_details` 是 ESD3D2 的噪声参数名，与光学 bloom 无关。

---

## 2. 参数槽位分配表（硬契约）

### 2.1 契约三处同改（漏一处 = 启动崩溃，2026-10-08 真实事故）

| 处 | 文件 | 当前 | 改后 |
|---|---|---|---|
| ① 长度常量 | `FilmParams.h:14` `static constexpr int kCount` | `60` | **`78`** |
| ② Kotlin 数组长度 | `CameraPresets.kt:29` `DoubleArray(60)` | `60` | **`DoubleArray(78)`** |
| ③ 解析防呆 | `FilmParams.h::fromDoubles`（`327-403`） | 最大 `if (n > 59)` | 补 `if (n > 60) … if (n > 77)` |

#### 2.1.1 ⚠️ 第四处：`CameraController` 的按索引下发点（**35+ 处，本次新发现**）

`grep -n "filmParams\[[0-9]" app/src/main/java/com/filmkit/app/camera/CameraController.kt` ⇒ **35 处**，
形如 `applyXxxPref()` 的"每个 prefs 键写一个索引"模式（`1200`、`1231`、`1256`、`1298`、`1465`、`1473`、`1491`…）。
**其中只有 `applyColorSmoothPref`（`1465`）带长度保护**（`if (filmParams.size > 59)`），其余**裸写**
（如 `filmParams[57] = v`，`1473`）—— 即：**v0.10.196 的崩溃模式在 34 处仍然潜伏**。

**E4-1 必须做两件事**：
1. **把裸写改为统一的受保护入口**（例如 `private fun put(i: Int, v: Double) { if (i < filmParams.size) filmParams[i] = v }`），
   一次性消灭这一类崩溃（18 个新槽位若继续裸写，风险 ×18）。
2. **新槽位 [60..77] 不要走"每个 prefs 一个 apply 方法"的模式** —— 理由：
   - ① 18 个新槽位 × 每槽一个方法 + 一个 prefs key = 维护成本高且必漏（这正是 ADR §1.1"单一参数源"要防的）；
   - ② 这些参数是**风格参数**（属于预设/编辑器状态），不是 `hr_raw`/`defringe` 那类**全局开关**；
   - ③ E3-1 的"预设 schema + 持久化"正好承载它们：**编辑器直接改 `filmParams[i]` 后重渲染（不落 prefs）**，
      **预设走 `setFilmParams(parsed)` 批量下发**，只有确实需要"跨会话记住"的才加 prefs。
   ⇒ **规则：新槽位的下发路径 = 预设 JSON / 编辑器状态；只有"性能开关"（如 halation 默认关）才进 prefs。**

**额外要求**：`setFilmParams`（`CameraController.kt`，**本次调研时为 1033 行；E1 线并行改动后已漂移至 1064 行 —— 引用请按符号名搜索，不要用行号**）拿到的是调用方数组，
**必须加 `require(p.size >= FilmParams.kCount)` 兜底**，否则预设/快照路径传短数组会在 `p[0] = …` 处越界。
`filmParamsForStill()`（`179-186`）用 `copyOf()` 是安全的。

### 2.2 复用与保持的既有槽位（11 个，**不新增**）

> 分三类：**① 死槽位复用**（[5..10]，shader 内零引用，本次语义重定义）；
> **② 已实现槽位复用**（[26][27]，语义修正）；
> **③ 已实现且语义正确、保持不动**（[23][24][25]，只补 UI 与预设）。
> 另：`cross[9]`[11..19] 同为死槽位（旧 log 域染料串扰矩阵），**本次不动**（是否复活另议）。

| 槽 | 名称 | 单位 | 范围 | 默认 | 所属 pass | **为什么必须独立**（不能合并的理由） |
|---|---|---|---|---|---|---|
| [5] | `shoulder_r` | latitude 0..1 | 0..1 | **0.01** | CPU `computeFilmicSpline` | R 层肩部过渡位置。**不能与 [6][7] 合并**：三层乳剂肩部位置差异 = 高光色偏（Kodak 高光偏暖的机制之一）；合并即丢掉"层间"维度 |
| [6] | `shoulder_g` | 同上 | 0..1 | 0.01 | 同上 | G 层参考通道 |
| [7] | `shoulder_b` | 同上 | 0..1 | 0.01 | 同上 | B 层肩部（Fuji 冷高光的机制） |
| [8] | `toe_r` | 同上 | 0..1 | 0.01 | 同上 | R 层趾部位置（阴影偏暖） |
| [9] | `toe_g` | 同上 | 0..1 | 0.01 | 同上 | G 层参考 |
| [10] | `toe_b` | 同上 | 0..1 | 0.01 | 同上 | B 层趾部（阴影偏青/蓝） |
| [23] | `split_warm` | 强度 | −1..1 | 0.0 | `kFsFilm` | 阴影加法偏移（**已实现**，语义 = 分离色调，非层间效应） |
| [24] | `split_cool` | 强度 | −1..1 | 0.0 | `kFsFilm` | 高光加法偏移（同上） |
| [25] | `monochrome` | 0/1 | 0..1 | 0.0 | `kFsFilm` | 去色（黑白胶片预设用；在 LUT 前 ⇒ 可与彩色 LUT 组合出"黑白 + 调色"） |
| [26] | `grain_amount` | % | 0..100 | 0.0 | `kFsOut` | 颗粒强度（**复用**，语义改为 darktable `strength`） |
| [27] | `grain_size` | ISO | 20..6400 | **400** | `kFsOut` | 颗粒尺寸/粗度（**复用**，语义改为 darktable `scale`=ISO；**必须改默认值** 1.0 → 400） |

> **[5..10] 的语义重定义说明**：槽位形状（per-channel 数组）与位置**不动**（契约不移位），
> 但值语义从"高光软肩强度 0..1（0=关）"改为 **"filmic latitude（0..1，0.01 = darktable 默认）"**。
> **默认值必须从 0 改为 0.01**（0 在 latitude 语义下 = toe/shoulder 紧贴中灰，是**极端**曲线，不是恒等！）
> —— 这是本次改造最容易踩的坑，`neutral()` 里必须显式写 0.01。

### 2.3 新增槽位 [60..77]（18 个）

| 槽 | 名称 | 单位 | 范围 | 默认 | 所属 pass | **为什么必须独立**（不能合并的理由） |
|---|---|---|---|---|---|---|
| [60] | `grain_midtone_bias` | % | 0..100 | **100** | `kFsOut` | darktable `midtones_bias`：控制相纸响应曲线两端 falloff 的**强度**。与 [61] 正交（一个管"衰减多强"，一个管"峰值在哪"）—— 合并即无法表达"颗粒均匀的数码感"与"颗粒集中中间调"两种形态 |
| [61] | `grain_pivot` | 0..1 | 0..1 | **0.0** | `kFsOut` | 密度调制曲线的**亮度枢轴**（0 = darktable 原版逐位不变；>0 = 把调制峰值移向暗部）。**存在理由**：调研 §2.4 明确要求"负片暗部颗粒重"，而 darktable 的相纸响应是中间调重 —— 这是**调研需求与参考实现的差异**，必须有独立旋钮承载，且默认 0 保证不改变原版行为 |
| [62] | `halation_strength` | 0..1 | 0..1 | 0.0 | `kFsHaloMix` | 强度（0 = 整链旁路，A/B 通路） |
| [63] | `halation_threshold` | 显示域 0..1 | 0..1 | **0.80** | `kFsHaloThr` | 高光触发阈值。**不能与 [62] 合并**：强度管"叠多少"，阈值管"哪些像素算高光"—— 夜景点光源与白天逆光需要**相反**的阈值/强度组合 |
| [64] | `halation_radius` | 0..1 归一 | 0..1 | **0.45** | `kFsHaloMix` | 光晕半径。**不能与 [63] 合并**：半径决定形态（紧贴轮廓 vs 大面积泛光），与触发无关 |
| [65] | `halation_warmth` | 0..1 | 0..1 | **0.40** | `kFsHaloMix` | 色相（红橙程度）。**不能与 [64] 合并**：色相与半径是两个独立物理量（乳剂对不同波长的散射比 vs 片基厚度） |
| [66] | `vignette_strength` | 增益 | −1..0 | 0.0 | `kFsOut` | 暗角强度（0 = 关） |
| [67] | `vignette_radius` | % | 0..200 | **80** | `kFsOut` | falloff 起点（darktable `scale`）。**不能与 [66] 合并**：强度管"暗多少"，半径管"从哪开始暗" |
| [68] | `vignette_feather` | % | 0..200 | **50** | `kFsOut` | falloff 宽度（darktable `falloff_scale`）。**不能与 [67] 合并**：半径 + 宽度是两端独立控制的过渡带（暗角"硬边 vs 柔和"） |
| [69..71] | `interlayer_shadow[3]` | 增益 | 0.5..1.5 | 1.0 | `kFsFilm` | **暗部** R/G/B 三通道相对增益（层间效应 = 按亮度分区的分通道抑制） |
| [72..74] | `interlayer_mid[3]` | 增益 | 0.5..1.5 | 1.0 | `kFsFilm` | **中间调**三通道增益 |
| [75..77] | `interlayer_high[3]` | 增益 | 0.5..1.5 | 1.0 | `kFsFilm` | **高光**三通道增益 |

**`kCount` = 78（索引 0..77）。[29] 仍留空（历史原因，见 `FilmParams.h:35-38`），不复用。**

#### 2.3.1 为什么层间效应是 9 个槽位而不是 2 个（回答 §3.5 的前置）

调研写"3×3 系数**或**简化两轴"。选 3×3 的理由：
1. **两轴方案需要先定义色彩空间**（哪两轴？暖-冷 / 绿-品红？）—— 那是**自研**，没有可对齐的参考实现；3×3 通道增益是**物理量**（三层乳剂的 DIR 耦合抑制强度），且与现有 `cross[9]`（log 域染料串扰）同构，语义可类比。
2. **可退化**：若真机实测发现 9 个旋钮过多，把三区设成相同值即退化为 3 个（全局 RGB 增益）；把 R/G/B 设成相同值即退化为 3 个（纯亮度分区）。**9 槽是超集，不阻塞简化**。
3. 调研给的是"3×3 系数"，9 槽是**照做**。

### 2.4 明确**不进** `FilmParams` 的参数（走预设 JSON / 引擎内部 / UI 层）

| 参数 | 去处 | 理由 |
|---|---|---|
| 预设名 / 分类 / 说明 / 版本 | 预设 JSON | 纯元数据 |
| LUT **资产 id** | 预设 JSON + 独立 JNI（`nativeEngineSetLut`） | LUT 是 3D 纹理，不进 double 数组（`FilmParams.h:47-50` 已确立） |
| LUT 强度 | 已在 [34] | 不重复 |
| **颗粒随机种子 / 位置** | **引擎内部** | 见 §3.1.6：按"源图 hash + 预设 id"派生，**不进契约**（避免每加一个视觉细节就扩契约） |
| 暗角中心点 / shape / 去饱和量 | 第一版**不做** | 见 §3.3.5：与分区饱和 [20..22] 职责重叠，避免"两套互相打架的旋钮" |
| 漏光样式 / 边框样式 | **UI / 导出层**（不进引擎） | 见 §3.6：纯 2D 装饰叠加，与成像链无关 |
| UI 滑杆范围 / 分组 / 单位显示 | UI 层常量 | 与引擎契约解耦 |

---

## 3. 每个参数的规格与算法

> 通用约定：本文档所有"参考实现"均为**逐行对照**，引用 URL 见 §8。
> "落点"一节给出 pass 名、域、与现有链的插入点。

### 3.1 颗粒（granularity）★★★

#### 3.1.1 数学定义（对齐 darktable `src/iop/grain.c`）

**① 噪声源：3 八度 2D simplex 噪声（匹配真实颗粒扫描功率谱）**

```
_simplex_2d_noise(x, y, z) = Σ_{oct=0..2} _simplex_noise(x·f[oct]/z, y·f[oct]/z, oct) · a[oct]
f = {0.4910, 0.9441, 1.7280}      // 八度频率，darktable 注释：匹配真实颗粒扫描的功率谱
a = {0.2340, 0.7850, 1.2150}      // 八度幅度
```
- `_simplex_noise` 是标准 3D simplex（Perlin 1998 型，Gustavson 的 `grad3[12]` + `permutation[256]` 表），输出归一化到 `[-1,1]`（`return 32.0*(n0+n1+n2+n3)`）。
- **z 轴被用作"八度标签"**（传 `octave` 常量），使三个八度在 3D 噪声空间里互不相关。

**② 尺度（粗度）**

```
scale_norm = ISO / 213.2            // GRAIN_SCALE_FACTOR = 213.2，UI 显示 ISO
zoom       = (1 + 8·scale_norm/100) / 800
x = (像素世界坐标 x) / wd           // wd = min(图宽, 图高)，归一化到短边
噪声输入 = (x/hash偏移, y, zoom)
```
- `zoom` 越大 → 空间频率越低 → **颗粒越粗**（单调、方向正确）。
- 换算：某八度的特征周期（占短边比例）= `zoom / f[oct]`。短边 3072px 时：

| ISO | zoom | f=0.4910 周期 | f=1.7280 周期 |
|---|---|---|---|
| 100 | 0.0012969 | 8.1 px | 2.3 px |
| 400 | 0.0014376 | 9.0 px | 2.6 px |
| 800 | 0.0016252 | 10.2 px | 2.9 px |
| 1600（darktable 默认） | 0.0020005 | 12.5 px | 3.6 px |
| 6400 | 0.0042519 | 26.6 px | 7.6 px |

**③ 密度调制：相纸响应（photographic paper response）**

```
delta      = 2.0 · exp( (mb/100) · ln(0.0001) )            // mb = midtones_bias，0..100
density(e) = (1 + 2δ) / (1 + exp( 4·gp·(0.5 − e) / (1 + 2δ) )) − δ     // gp = 1.0（GRAIN_LUT_PAPER_GAMMA）
inverse(d) = −ln( (1+2δ)/(d+δ) − 1 ) · (1+2δ)/(4·gp) + 0.5
```
- **预计算 128×128 LUT**（`GRAIN_LUT_SIZE=128`）：
  ```
  grain_lut[j][i] = 100 · ( paper_resp( gu + paper_resp_inverse(l) ) − l )
  gu = i/(127) − 0.5        // 噪声轴，±0.5
  l  = j/(127)              // 亮度轴，0..1（Lab 的 L/100）
  ```
- **应用**：`out_L = in_L + lut2d( noise · strength · 0.15 , in_L/100 )`（`GRAIN_LIGHTNESS_STRENGTH_SCALE=0.15`，`strength = 参数/100`）。
- **调制曲线形态**（关键）：
  - `d(density)/de` 在 `e=0.5` 处取最大值 `4·gp·0.25 = 1.0`，两端 → 0。
  - `mb=100`（darktable 默认）→ `δ=0.0002` → 曲线最陡 ⇒ **颗粒集中在中间调，暗部与高光几乎无颗粒**（"两端 falloff 最强"）。
  - `mb=0` → `δ=2` → 曲线平缓 ⇒ 颗粒幅度小但**覆盖整个影调范围**。
  - ⇒ **darktable 的模型是"中间调最重"，不是"暗部最重"**。这是与调研 §2.4 表述的直接冲突（见 §3.1.5）。
- **量级标定**（离线可复算）：`strength=25`（darktable 默认）、`mb=100` 时，中间调的 L 通道颗粒 RMS ≈ `100 × 1.0 × (0.513 × 0.25 × 0.15)` ≈ **1.9 L 单位 ≈ 4.9/255 显示域**（其中 0.513 = 三八度叠加后的 RMS 估计）。

**④ 单色性**：darktable **只改 Lab 的 L 通道**，a/b 原样（`out[1]=in[1]; out[2]=in[2];`）⇒ 纯亮度颗粒，**无假彩噪**。符合调研"不走 RGB 独立通道"。

**⑤ 位置随机**：`hash = DJBX33X(image.filename) % max(w·0.3, 1)`，从字符串**末尾**往前哈希（让视频序列相邻帧的哈希差异最大），噪声坐标 `x + hash`。

#### 3.1.2 落点

| 项 | 决定 | 理由 |
|---|---|---|
| **pass** | `kFsOut`（`FrameRenderer.cpp:4395-4571`），**不新增 pass** | 颗粒是纯逐像素运算，现有位置已经在输出 pass |
| **域** | **sRGB 编码显示域**（`srgbEncode` 之后、3D LUT 之后） | ① 物理上颗粒是胶片本身的纹理，LUT 模拟的是"含颗粒平均效果"的胶片响应 ⇒ 颗粒应叠加在 LUT **之后**；② 若放 LUT 前，同一预设在不同 LUT 下颗粒观感会被 LUT 的非线性改变（不可控）；③ 与 Dazz/VSCO 的"颗粒是最后叠加层"一致 |
| **调制亮度 l** | 显示域 luma（`dot(c, vec3(0.299,0.587,0.114))`，现有式） | darktable 用 Lab L（感知均匀）；sRGB 编码域与 L 近似，可直接标定。**保留现有 luma 权重**，不改（改动面最小） |
| **相纸 LUT** | 新增 128×128 `GL_R16F`（或 RGBA8）纹理 `tex_grain_lut_`，CPU 侧按 [60] 重算 | 避免在 shader 里算 `exp/log`（12.5M px × 2 次 exp/log 是纯浪费）；darktable 也是 CPU 侧 LUT |
| **shader 采样** | 1 次 LUT 采样 + simplex 算术（**0 额外纹理采样**） | 见性能 |

#### 3.1.3 当前实现 vs darktable —— 差异清单（6 项）

| # | 维度 | 现状（`kFsOut:4548-4553`） | darktable `grain.c` | 判定 |
|---|---|---|---|---|
| 1 | **尺寸语义** | `gp = gl_FragCoord.xy * 6.0 / max(uGrainSize,0.25)` ⇒ size **越大越细**（size=0.25 → 24px/格；size=4 → 1.5px/格） | `zoom = (1+8·ISO/213.2/100)/800`，ISO 越大越粗 | **必须改**（语义倒置 = 用户调不明白） |
| 2 | **噪声源** | `vnoise` = 单八度 **value noise**（`hash(sin)` + smoothstep 插值），频谱低通、格子边界 C1 不连续 | 3 八度 simplex，频率/幅度按真实颗粒功率谱参数化 | **必须改**（观感是"块状糊"而非"颗粒"） |
| 3 | **密度调制** | `w = 4·l·(1−l)`：抛物线，峰值 l=0.5，两端 →0。**恰好也是"中间调最重"**，但是**固定形状、无参数、无相纸模型** | `paper_resp` LUT，形状由 `mb` 控制，含"相纸 gamma 响应"的物理含义 | **必须改**（要参数化，且要与参考实现一致以便对标） |
| 4 | **hash 质量** | `hash(p) = fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453)` —— 大坐标（4080×3072）下 highp `sin` 精度不足 ⇒ **规律图案** | 整数 `permutation[512]` 表 | **必须改**（项目已为 dither 踩过同型坑，`4444-4451`） |
| 5 | **位置/种子** | `+ uTime`，`uTime = frames_ % 512` ⇒ 预览每帧变（闪）、成片取拍照瞬间值、512 帧后重复 | 文件名 hash（同文件可复现、不同文件不同） | **待实测**（§3.1.6 给方案） |
| 6 | **单色形式** | RGB **同加一个标量**（保色差，改亮度） | Lab **L 通道**加（保色度坐标，改亮度） | **待实测**（两者都无假彩噪；差异在高饱和区：Lab-L 加噪声会改变色度坐标的视觉亮度，RGB 同加不会。先保留现状，真机看高饱和区是否"发灰"） |

**不改的两项**（明确记录，避免后续误改）：
- 落点保持在 LUT 之后（§3.1.2 理由 ①②）。
- 保留现有 luma 权重系数（`0.299/0.587/0.114`），不改成 Rec.709（`0.2126/0.7152/0.0722`）—— 后者在 `kFsOut:4482` 另有定义（`luma()`），改成一致会**同时影响斑马纹判定**，属于计划外改动。

#### 3.1.4 参数映射（槽位值 → 算法常数）

| 槽位 | 参数 | 映射 | 备注 |
|---|---|---|---|
| [26] | `grain_amount` 0..100 | `strength = v/100`，进 LUT x 轴乘 `0.15` | 0 = 整段旁路（`if (uGrainAmount > 0.0)` 保留） |
| [27] | `grain_size` ISO 20..6400 | `scale_norm = clamp(v,20,6400)/213.2`；`zoom = (1+8·scale_norm/100)/800` | **默认 400**（不是 1600：手机画幅下 1600 的 12.5px 颗粒过大；400 ≈ 9px，待真机标定） |
| [60] | `grain_midtone_bias` 0..100 | `mb = clamp(v,0,100)`，CPU 侧重算 LUT | 默认 100 = darktable 默认 |
| [61] | `grain_pivot` 0..1 | 见 §3.1.5 | 默认 0 = 原版逐位不变 |

#### 3.1.5 `grain_pivot` 的数学形式（调研需求 ↔ darktable 差异的桥）

**问题**：调研要"暗部颗粒重、亮部轻"，darktable 给"中间调重、两端轻"。二者不可互相表达（`mb` 只改 falloff 强度，不改峰值位置）。

**方案**（最小侵入，默认恒等）：把 `paper_resp_inverse(l)` 的**亮度输入**先做一次亮度重映射：

```
l' = pow(l, 1 + 3·pivot)        // pivot = [61]，0 → l' = l（恒等，逐位等于 darktable）
```
- `pivot = 0` ⇒ `l' = l` ⇒ **逐位等于 darktable 原版**（A/B 通路纪律）。
- `pivot > 0` ⇒ 低亮度 `l` 被抬到 `l'` 的高位 ⇒ 暗部落在相纸曲线的**陡段** ⇒ **颗粒峰值移向暗部**。
- 指数 `1 + 3·pivot` 的 3 是标定常数：`pivot=1` 时指数 4，`l=0.18`（中灰）→ `l'=0.00105`，暗部 `l=0.05` → `l'=6.25e-6`（几乎无颗粒）—— **过强**，故 `pivot` 的实际可用区间预期在 0.1–0.4，**范围与标定值必须真机定，文档不预设**。
- ⚠️ 这是**唯一一处非 darktable 原版的数学**。按项目铁律：**第一版默认 0，先跑通原版并真机实测**，是否启用由数据决定（不是由本文档决定）。

#### 3.1.6 种子策略（不进契约）

| 方案 | 可复现 | 每张不同 | 预览稳定 | 判定 |
|---|---|---|---|---|
| A. 现有 `frames_ % 512` | ❌ | ✅ | ❌（预览闪） | 需改 |
| B. 源图 hash（darktable 式） | ✅ | ✅ | ✅ | **推荐**：成片用"输出尺寸 + 拍摄时间戳 ms"派生；预览用固定 seed（如 0）⇒ 预览不闪、成片每张不同 |
| C. 用户可见 seed 槽位 | ✅ | ✅ | ✅ | ❌ 过度设计（不进契约） |

**决定 = B**。引擎侧在 `passOutput` 传入的 `uTime` 之外，新增 `uGrainSeed`（float，由 `finishStillChain` 用时间戳派生；预览路径恒 0）。**这是引擎内部 uniform，不是 FilmParams 槽位。**

#### 3.1.7 边界情况

| 情况 | 行为 |
|---|---|
| 纯黑 `l=0` | `paper_resp_inverse(0)` 有定义（`ln` 参数 `(1+2δ)/δ − 1 > 0`）；`paper_resp` 输出 → `−δ`；LUT 值 = `100·(−δ − 0) ≈ −0.02` ⇒ **几乎无颗粒** ✓（暗部纯黑不该有颗粒） |
| 纯白 `l=1` | 同理趋 0 ⇒ 高光无颗粒 ✓（但注意：**halation 的过曝区会因此没有颗粒** —— 真实胶片高光区也有颗粒，这是 darktable 模型的已知特性，**不改**，记录为差异） |
| 高饱和色 | 现状（RGB 同加）不改色度坐标；**若真机觉得高饱和区颗粒发灰**，备选方案是改为"加在 luma 上、RGB 等比缩放"（保持 CIE xy），但那是 A/B 选项，非默认 |
| `grain_amount = 0` | `if (uGrainAmount > 0.0)` 整段跳过（现有门控保留）⇒ 零成本 |
| `grain_size` 非法 | 必须 `clamp(v, 20, 6400)`；现状 `max(uGrainSize, 0.25)` 只防了 0，**没有上限**（size=1e6 会让 `gp` 全部落在同一格 → 常数偏移 = 全图加同一灰度，**是一个真实的静默失败路径**） |

#### 3.1.8 性能量级

- **新增 pass：0**。新增纹理：1 张 128×128 R16F（64KB，可忽略）。
- **每 pass 采样数**：现状 0（纯算术）；新增 1 次 128×128 LUT 采样（缓存友好）。
- **算术**：3 次 simplex（每次 ≈ 60–100 ALU）≈ 250 ALU/px ⇒ 12.53M px ≈ 3.1 G ALU。**必须实测**（预览每帧跑，是实时路径）。
- **CPU 侧**：`[60]` 变化时重算 128×128 LUT = 16384 次 `exp/log` ≈ 微秒级，可在参数变更时同步做。

---

### 3.2 halation ★★★

#### 3.2.1 物理与参考实现

**物理**：光穿过乳剂后在**片基**内全反射，重新曝光乳剂。CineStill 800T = Kodak Vision3 500T **撕掉 remjet 防光晕层**（碳黑涂层）⇒ 反射光不被吸收 ⇒ 点光源周围出现红橙晕。**乳剂对红光的散射距离最长、蓝光最短**（这是"白→橙→红"径向渐变的成因）。

**参考实现（一手，逐行对照）**：`jie60517/emulsion` `src/render/effects/library/halation.ts`。

| 步骤 | emulsion 实现 | 我们的落地 |
|---|---|---|
| ① 高光提取 | `lin = srgbToLinear(src)`；`w = smoothstep(thr−knee, thr+knee, luma(lin))`；`out = lin·w`。**在线性光里做**（注释："blurring gamma-encoded values spreads the wrong amount of energy"） | 同（我们已在**线性显示域**，无需 sRGB 解码）。⚠️ emulsion 此 pass 是**单点采样 + 依赖硬件 LINEAR 过滤**完成降采样；**X300 的 RGBA16F 无线性过滤**（项目已多处手写，见 `4297-4299`）⇒ 我们必须**手写 2×2 盒式 = 4 采样/px** |
| ② 软 knee | `knee = max(0.02, thr·0.55)` | 同（硬编码，不给槽位） |
| ③ 金字塔 | `LEVELS=5`，`level_i = (w >> (i+1), h >> (i+1))` ⇒ 1/2, 1/4, 1/8, 1/16, 1/32 | 同 |
| ④ 降采样 | 4-tap 盒式（`±1` 四角 ×0.25） | 同（避免硬件 LINEAR —— X300 的 RGBA16F **无线性过滤**，项目已多处手写采样，见 `4297-4299`） |
| ⑤ 逐级模糊 | 5-tap 可分离高斯：`0.227027·c + 0.3162162·(c±1.3846) + 0.0702703·(c±3.2308)`，先横后纵 | 同 |
| ⑥ 层权重（**核心**） | `CHANNEL_SPREAD = [1.0, 0.6, 0.32]`（R/G/B）；`centre_c = radius01·(LEVELS−1)·SPREAD[c]`；`w_i,c = exp(−(i−centre_c)²/(2·0.95²))` 归一化 | 同（**硬编码，不给槽位** —— 这是乳剂的物理常数） |
| ⑦ 合成 | `added = Σ halo_i·w_i · uTint · uAmount`；`sum = lin(src) + added`（**add，线性域**）；按 `w = clamp(luma(added)·4,0,1)` 与 `shoulder(sum)`（knee 0.8 指数压缩）混合 | `added` 同；`sum = lin + added` 同；**shoulder 复用 `kFsOut` 的 `highlightRolloff`**（同 knee 0.80、同指数形式）⇒ halation pass 内**不做**压缩 |
| ⑧ tint | `(1.0, 0.24+0.34·warmth, 0.44−0.22·warmth)`；默认 warmth=0.4 ⇒ `(1.0, 0.376, 0.352)` | 同 |
| ⑨ 默认值 | `strength=0, radius=45, threshold=62, warmth=40`（0..100） | strength 0（关）；threshold 映射见 §3.2.3 |

**第二参考（darktable `src/iop/bloom.c`，用于对照"标准做法"的另一支）**：
- 域 = Lab（L 通道），`blurlightness = (L·scale > threshold) ? L·scale : 0`（**硬阈值**）
- 模糊 = `dt_box_mean`（**多次盒式均值**近似高斯），`rad = 256·(size+1)/100`（最大 256px）
- 合成 = **screen blend**：`out_L = 100 − (100−L)(100−blurL)/100`（只在 L 通道）
- ⇒ **darktable bloom 不含通道扩散**（无"白→橙→红"），是 Orton 效应；**halation 必须用 emulsion 的通道相关半径**。两者都保留为参考，我们采用 emulsion（唯一有通道扩散的公开实现）。

#### 3.2.2 落点（**这是本设计最关键的架构决定**）

**决定：halation 全部在 3D LUT 之前、`kFsFilm` 之后的线性显示域完成。**

```
… → kFsFilm（filmic + gamut + sat/split/mono/interlayer，输出线性显示域 [0,1]）
   → 【新增】kFsHaloThr → kFsHaloDown×4 → kFsHaloBlur×10 → kFsHaloMix
   → kFsOut（highlightRolloff → sharpen → srgbEncode → 3D LUT → grain → dither）
```

**四条理由（逐条对应硬约束）**：

1. **"halation 必须在 LUT 之前做高光判断"** ⇒ 满足：高光提取在 `kFsHaloThr`，位于 LUT 之前。
2. **"不能进 3D LUT"** ⇒ 满足：LUT 是逐像素色彩映射，**物理上无法表达空间模糊**；halation 是形态学效果，必须在 pass 链里做。
3. **关于调研的"之后叠色"**：本设计的解读是 —— "叠色"指 **halo 的色相（tint）施加在高光判断的结果上**，**不是**"在 LUT 之后叠加"。理由：3D LUT 是**逐像素非线性**映射，若把 halo 放到 LUT 之后，则 halo 的亮度不再受胶片响应曲线约束（真实 halation 是**曝光阶段**的现象，必然经过显影/扫描的色彩响应）。**若真机实测发现 LUT 把 halo 的红橙改坏**，备选方案 = 在 `kFsOut` 的 LUT 之后、用 `kFsHaloMix` 输出的 halo **亮度 mask** 再叠一次 tint（保留形态、色相绕开 LUT）—— 这是 **A/B 选项，非默认**，需真机数据支持。
4. **线性域 vs 显示域**：emulsion 明确在线性光里模糊（能量守恒）；我们的 `kFsFilm` 输出**就是**线性显示域 ⇒ 直接可用，无需 sRGB 解码。阈值语义也直观（0.80 线性 ≈ 显示域 231/255 ≈ "接近过曝"）。

#### 3.2.3 参数映射

| 槽位 | 参数 | 映射 | 默认 | 依据 |
|---|---|---|---|---|
| [62] | `halation_strength` 0..1 | `uAmount = v`（乘在 `added` 上） | 0.0 | 0 = 整链旁路 |
| [63] | `halation_threshold` **显示域 0..1** | `thr_linear = srgbToLinear(v)`；`knee = max(0.02, thr_linear·0.55)` | **0.80** | 槽位单位 = 显示域 sRGB 编码值（用户直觉"多亮算高光"）。`srgbToLinear(0.80) = 0.6038`。emulsion 默认 0.62 → 线性 0.3423；调研给"阈值 0.7–1.0"（更接近过曝点光源语义）⇒ 取 0.80 为起点，**待真机标定** |
| [64] | `halation_radius` 0..1 | `radius01 = v` ⇒ `centre_c = v·4·SPREAD[c]` | **0.45** | 对齐 emulsion 默认（45/100）。R 通道 `centre = 1.8` ⇒ 权重峰值在 level2（1/8 ⇒ 等效 σ≈13px） |
| [65] | `halation_warmth` 0..1 | `tint = (1.0, 0.24+0.34·v, 0.44−0.22·v)` | **0.40** | 对齐 emulsion 默认 |

**半径 → 像素的映射表**（每级 5-tap 高斯等效 σ = **1.637** texel @ 该级 —— 由权重 `[0.227027, 0.3162162×2, 0.0702703×2]` 与偏移 `[0, ±1.3846154, ±3.2307692]` 算得方差 2.6793；短边 3072、长边 4080 时）：

| level | 尺寸（长边） | 等效全分辨率 σ | 说明 |
|---|---|---|---|
| 0 | 1/2 = 2040 | ≈ 3.3 px | B 通道主力（SPREAD 0.32） |
| 1 | 1/4 = 1020 | ≈ 6.5 px | G 通道主力（0.6） |
| 2 | 1/8 = 510 | ≈ 13.1 px | R 通道主力（1.0） |
| 3 | 1/16 = 255 | ≈ 26.2 px | R 通道远端 |
| 4 | 1/32 = 128 | ≈ 52.3 px | 大面积泛光 |

⇒ `radius=0.45` 时 R 的有效半径 ≈ 13–26px，落在调研给的"8–32px"区间内 ✓。

**物理量级校验（估算，非一手）**：35mm 画幅（36mm 宽）上 halation 扩散典型 50–200µm ⇒ 0.14%–0.55% 画幅宽 ⇒ 在 4080px 上 = **5.6–22px**，与上表一致。

#### 3.2.4 域与值域边界

| 情况 | 行为 |
|---|---|
| 高光 `l ≥ thr+knee` | `w=1` ⇒ 全额进金字塔 |
| 阈值附近 | `smoothstep` 软过渡（knee 内线性化）⇒ 无硬边 |
| 暗部 | `w=0` ⇒ 不贡献 halo ✓ |
| 叠加后 >1.0 | `sum` 可超 1（如 1.3）→ 交给 `kFsOut` 的 `highlightRolloff`（knee 0.80 指数软肩 + 趋白）压缩 ✓ **不需要在 halation pass 内做 shoulder** |
| halo 未到达的像素 | `added=0` ⇒ `sum = src` 逐位不变（emulsion 的 `w` 加权混合；我们因为 shoulder 在 kFsOut，直接 `src + added` 即可，**但必须验证 `added=0` 时输出逐位等于输入** —— 中间经过 RGBA16F 纹理往返，会有 1 次量化，需实测确认不可见） |
| 纯色高光（如红色 LED） | R 通道 halo 最强 ⇒ 更红；这是物理正确的 |

#### 3.2.5 分辨率无关性（必须显式声明）

金字塔基于**相对尺寸**（`w >> i`）⇒ halo 半径随输出分辨率**等比缩放**。
- 我们的成片是固定画幅（4080×3072 ≈ 传感器全宽）⇒ "相对归一化" = "固定画幅比例"，**物理正确** ✓。
- **但如果将来引入裁切/数码变焦**：halo 半径会跟着裁切变大（错误 —— 真实 halo 是片基上的固定物理尺寸）。**必须按"原画幅"而非"输出画幅"归一化**（引擎需传 `uFrameW/uFrameH`）。**本版无裁切，记录为已知约束**。
- 预览（`g_preview_scale` 0.25–1.0）走同一相对归一化 ⇒ halo 比例与成片一致 ✓（这是相对归一化的额外好处）。

#### 3.2.6 移动 GPU 三禁的逐条规避（**必须在实现前确认**）

| 禁令 | 本设计的规避 |
|---|---|
| 数组作为函数参数 | 5 级 halo 用 **5 个独立 `uniform sampler2D uHalo0..uHalo4`**（emulsion 同款），不传数组 |
| 循环内动态索引 | 合成 pass 用 **5 个独立 `uniform vec3 uW0..uW4`**（`uWeight[LEVELS]` 数组 uniform 在 ES 3.0 里是允许的，但**为规避风险一律展开**）；模糊 pass 的 5-tap 手动展开（现有 `sharpenLuma` 已是手动展开 8 邻，`4500-4521`，照此风格） |
| 多行宏（`\` 续行） | 不使用宏 |
| **额外**：GLSL ES 不支持数组初始化器 | 权重**不能**写成 `const float w[5] = float[5](…)`（仓库两次踩坑，`4499`）；全部用独立 uniform 或**逐项写死的常量表达式** |

#### 3.2.7 pass 编排与新增资源

| pass 名 | 输入 | 输出 | 尺寸 | 采样/px |
|---|---|---|---|---|
| `kFsHaloThr` | `tex_work_`（kFsFilm 输出） | `tex_halo_[0]` | 1/2 | **4**（手写 2×2 盒式，见上表①注） |
| `kFsHaloDown` ×4 | `tex_halo_[i-1]` | `tex_halo_[i]` | 1/4…1/32 | 4 |
| `kFsHaloBlur` ×10 | `tex_halo_[i]` ↔ `tex_halo_tmp_` | 同上（原地往返） | 各级 | 5 |
| `kFsHaloMix` | `tex_work_` + `tex_halo_[0..4]` | `tex_halo_out_` | 全分辨率 | 6 |

- **新增纹理**：`tex_halo_[5]` + `fbo_halo_[5]` + `tex_halo_tmp_` + `tex_halo_out_`（全分辨率 RGBA16F）。
- **内存峰值**：`(1/4+1/16+1/64+1/256+1/1024)×12.53M ≈ 4.18M px` × 8B = **33.4MB**；`tmp` 取最大级（1/2）= 3.13M px = **25.1MB**；输出全分辨率 = **100.2MB**（⚠️ 这是大头）。
  - **优化**：`tex_halo_out_` 可与 `tex_work_` **乒乓复用**（halation 是链上最后一步写入 `tex_work_` 的操作；`kFsOut` 读它）⇒ 省 100MB。**但会引入"一帧内多域复用"的标签混乱**（`6254-6258` 已记录同类问题）。**建议：先按独立纹理实现（正确优先），实测内存后再决定是否复用** —— 按铁律不预删。
  - 合计新增：独立纹理方案 ≈ **158.7MB**；乒乓方案 ≈ **58.5MB**。
- **执行门控**：`[62] <= 0` 时**整链不执行**（`tex_work_` 直通 `kFsOut`），零成本、零纹理分配。

#### 3.2.8 性能量级与降级路径（**实测前不拍板**）

**采样总量（全分辨率 4080×3072 = 12.53M px）**：

| 段 | 计算 | texel fetch |
|---|---|---|
| 阈值提取（含手写降采样） | 4 × 1/4 | 12.53M |
| 4 次降采样 | 4 × (1/16+1/64+1/256+1/1024) | 4.16M |
| 10 次模糊 | 5 × 2 × (1/4+…+1/1024) | 41.73M |
| 合成 | 6 × 1 | 75.20M |
| **合计** | | **≈ 133.6M** |

⇒ **≈ 10.7 个"全分辨率等效 pass"**。加上 16 个 pass 的驱动开销（Mali 的 pass 切换在移动端是真实成本）。
粗算带宽下限：`133.6M × 8B ≈ 1.07GB` 读 + 写回 ⇒ 在共享 LPDDR4X 上 **≥ 60ms 量级**。**这是设计阶段能给出的最强警告：halation 可能是"拍照慢 0.1s+"的模块。**

**降级路径（备好但默认不启用，等实测数据）**：

| 方案 | 改动 | 采样降至 | 副作用 |
|---|---|---|---|
| D1 金字塔 5 → 4 级 | 去掉 1/32 级 | ≈ 88M（−29%） | 最大半径从 52px → 26px（大面积泛光消失） |
| D2 合成降到 1/2 分辨率 | halo 是低频信号，1/2 足够 | ≈ 90M | 全分辨率细节处 halo 边缘略糊（需目视判） |
| D3 预览用 3 级 | 预览路径单独配置 | 预览 −40% | 预览与成片观感不一致（WYSIWYG 破绽，**需用户拍板是否接受**） |
| D4 `tex_halo_out_` 复用 `tex_work_` | 乒乓 | 0（省内存） | 域标签混乱 |

> **铁律提醒**：以上 4 条**都不许在首版实现时预先套用**。首版必须完整实现 5 级 + 全分辨率合成，
> 真机实测耗时与内存后，把数据与选项交给用户拍板。

---

### 3.3 暗角（vignette）★★

#### 3.3.1 数学定义（对齐 darktable `src/iop/vignette.c`）

**几何**（`process()`，`autoratio=TRUE` 分支 = 椭圆；我们采用 `autoratio=FALSE, whratio=1` = **正圆**，见下）：

```
xscale = 2 / (W · out_scale)          // autoratio=TRUE：跟随画幅（椭圆）
yscale = 2 / (H · out_scale)
── 或（我们采用）──
basis  = 2 / (max(W,H) · out_scale)   // autoratio=FALSE, whratio=1：正圆，按长边归一化
xscale = yscale = basis

pv      = ( |i·xscale − cx| , |j·yscale − cy| )     // cx,cy = 画面中心（归一化后）
cplen   = ( pv.x^exp1 + pv.y^exp1 )^exp2            // exp1 = 2/shape, exp2 = shape/2
                                                     // shape=1 ⇒ cplen = √(x²+y²) = 欧氏距离
dscale  = scale/100                                  // falloff 起点（内圈半径，画面半宽为单位）
fscale  = max(falloff_scale, 100/min(W,H)) / 100     // falloff 宽度（含抗锯齿下限）
weight  = clamp( (cplen − dscale) / fscale , 0, 1 )
weight  = 0.5 − cos(π·weight)/2                      // 余弦平滑（= smoothstep 形）
```

**应用**（darktable `brightness < 0` 分支 = 乘法衰减）：
```
falloff = 1 + weight·brightness          // brightness ∈ [−1, 0]
col    *= falloff
```
- darktable 还支持 `brightness > 0`（加法提亮）与 `saturation`（去饱和，`col -= (mv−col)·weight·sat`，`mv = (r+g+b)/3`）。
- **我们第一版只做 `brightness < 0` 的乘法衰减**，不做 saturation（§3.3.5）。

#### 3.3.2 为什么用 darktable 的多项式模型、**不用 cos⁴**（用户明确问到的对比）

| 模型 | 数学形式 | 物理含义 | 在我们链上的判定 |
|---|---|---|---|
| **cos⁴ 定律**（自然渐晕） | `E(θ) = E₀·cos⁴θ`，θ = 像场入射角；等价于 `E(r) ≈ E₀/(1 + r²/f²)²` | 光轴外照度的**理论**衰减（孔径投影面积 × 距离平方） | ❌ **不用**：① 只描述"自然渐晕"，真实镜头还有**机械渐晕**（镜筒/光阑遮挡）与像素级光学设计补偿，cos⁴ 在广角端**过强**；② **最关键**：我们的链上**已经有 LSC 在补偿真实失光**（逐机标定的增益网格，`SettingsScreen.kt:339`），再做 cos⁴ 暗角等于"把刚补掉的失光按一个**不精确的**理论公式加回去"—— 冗余且更差 |
| **lensfun 多项式** | `corr(r) = 1 + k1·r² + k2·r⁴ + k3·r⁶`（PA 模型另用 `r, r², r³` 项），`k` 由**镜头标定数据**拟合 | 对**具体镜头**实测失光的经验拟合 | ❌ **不用**：需要镜头数据库/标定，而我们没有；且它同样是"补偿真实失光"的模型（与 LSC 职责重叠） |
| **darktable `vignette.c`** | 超椭圆距离 `cplen = (|x|^exp1 + |y|^exp1)^exp2` + 余弦平滑权重 + **线性乘法衰减** | **风格化**的"接近镜头失光的暗角"（模块自述：`simulate a lens fall-off close to edges`），域 = `non-linear, RGB, display-referred` | ✅ **用**：① 它是**创意层**模型，与 LSC 的**补偿**职责正交（一个补、一个加）；② 参数语义清晰（起点/宽度/强度）；③ 有可逐行对照的一手源码；④ 与调研的"暗角强度 0–0.5"量级吻合 |

**结论**：本项目的暗角定位 = **成片风格化**（不是镜头失光建模）。
若将来要做"真实镜头失光"（例如编辑器里"模拟某镜头"），那是 **LSC 的逆 + 镜头标定数据**，属于另一个模块，**不要**塞进本参数。

#### 3.3.2b 为什么用**正圆**而不是椭圆（与 darktable 默认设置的差异，必须说明）

| 方案 | darktable 设置 | 物理含义 | 判定 |
|---|---|---|---|
| 椭圆（跟随画幅） | `autoratio=TRUE` | 失光**不是**径向对称的 —— 这是**风格化**处理 | ❌ 不用 |
| 正圆（按长边） | `autoratio=FALSE, whratio=1` | 镜头失光相对光轴**径向对称**，在画幅上投影为圆（光轴居中） | ✅ **物理正确**，且是 darktable 的**出厂默认**（`scale=80, falloff_scale=50, brightness=−0.5, saturation=−0.5, autoratio=FALSE, whratio=1.0`） |

⇒ **第一版 = 正圆，半径按长边归一化**（`basis = 2/max(W,H)`）。
**"是否随画幅/裁切变化"的回答**：用 `max(W,H)` 归一化 ⇒ 长边的暗角比例固定；**裁切后暗角跟着裁切走**（因为我们按输出画幅算）。真实镜头在裁切后暗角位置应保持原画幅坐标 —— **本版无裁切，记录为约束**（与 §3.2.5 同型）。

#### 3.3.3 落点

| 项 | 决定 | 理由 |
|---|---|---|
| **pass** | `kFsOut` 内，**OETF 之后、3D LUT 之前** | 见下 |
| **域** | sRGB 编码显示域，**乘法** | darktable vignette 的声明域就是 `non-linear, RGB, display-referred`；且风格化暗角是"成片观感"层 |
| **与 LUT 的顺序** | **LUT 之前** | 物理上失光发生在**曝光阶段**（LUT 之前）；且暗角在 LUT 前可避免 LUT 的非线性对暗角渐变的二次放大（若在 LUT 后，同一暗角在不同 LUT 下形状不同） |
| **与颗粒的顺序** | **颗粒之前**（暗角 → 颗粒） | 物理上颗粒是胶片纹理，覆盖在含暗角的画面上；且颗粒叠在暗角上能避免"暗角把颗粒也压暗"导致的颗粒不均匀（真实胶片正是如此：颗粒在暗部可见度更高，因为暗角区曝光少 ⇒ 这实际上与 §3.1.5 的 pivot 议题相关，**记录为待观察**） |

#### 3.3.4 参数映射

| 槽位 | 参数 | 映射 | 默认 | 依据 |
|---|---|---|---|---|
| [66] | `vignette_strength` −1..0 | `brightness = clamp(v, −1, 0)` | 0.0 | darktable 默认 −0.5；调研给"0–0.5"⇒ 我们用负号表示衰减 |
| [67] | `vignette_radius` 0..200 | `dscale = clamp(v,0,200)/100` | **80** | darktable `scale` 默认 80 |
| [68] | `vignette_feather` 0..200 | `fscale = max(v, 100/min(W,H))/100` | **50** | darktable `falloff_scale` 默认 50（含抗锯齿下限） |

**shape 硬编码 = 1**（圆/椭圆），**center 固定 (0,0)**（画面中心），**saturation 不做**。

#### 3.3.5 边界情况

| 情况 | 行为 |
|---|---|
| `strength = 0` | `if (uVigStrength < 0.0)` 整段跳过 ⇒ 零成本 |
| 画面中心 | `cplen < dscale` ⇒ `weight = 0` ⇒ **逐位不变** ✓ |
| 画面角落 | `cplen` 最大 = `√2·max/min` 相关 ⇒ 可能 `weight = 1` ⇒ 全额衰减 |
| `feather = 0` | `fscale` 取下限 `100/min(W,H)` ⇒ 防硬边混叠（darktable 同款保护） |
| **banding 风险** | 渐变区若在 8bit 上跨越不足 1 LSB 会出现同心圆环。darktable 提供 `dithering` 选项（TEA + TPDF，1/256 或 1/65536）。**我们在 `kFsOut` 末尾已有 TPDF dither（±1LSB，`4559`），且在暗角之后** ⇒ **天然覆盖** ✓ 但要真机确认（若暗角幅度大、渐变长，TPDF 可能不足 ⇒ 需评估 darktable 式的 TEA 抖动） |
| 与 LSC 的关系 | LSC 是**补偿**真实失光（`SettingsScreen.kt:339`），暗角是**添加**风格化失光。**两者同时开 = 先补后加**，语义清晰，不冲突 |

#### 3.3.6 性能量级

- **新增 pass：0**；**新增纹理：0**；**采样数：0**（纯算术，约 20 ALU/px）。
- ⇒ **性能可忽略**（12.5M px × 20 ALU = 250M ALU）。
- **这是 E4 里性价比最高的一项**（★★ 贡献、零 pass、零内存）。

---

### 3.4 趾部/肩部形状（toe / shoulder）★★

#### 3.4.1 现状与"不做两套影调旋钮"的前提

**现状**：`shoulder[3]`[5..7] / `toe[3]`[8..10] 是**死槽位**（旧 log 域链 v0.7.9 已整体删除，`FrameRenderer.cpp:3941-3945`）。
**现有影调链**：`filmicTone`（darktable v3 spline）由以下旋钮控制：
- `[37] filmic_black_ev` / `[38] filmic_white_ev` = **曲线端点**（动态范围）
- `[4] contrast` = `contrast_param`（lat 段斜率）
- `[28] tone_strength` = 曲线混合强度（0 = 恒等）
- `latitude`（**当前硬编码 0.01**，`FrameRenderer.h:549`）

**用户明确要求**："不要做两套互相打架的影调旋钮"（历史事故：4 个白点旋钮互相干扰）。

#### 3.4.2 决定：**[5..10] = filmic `latitude` 的 per-channel 拆分**（不新增曲线）

**数学依据**（`computeFilmicSpline`，`FrameRenderer.cpp:11129-11229`）：

```cpp
const double toe_log = (1.0 - kLatitude) * grey_log + kLatitude * xmin;   // 11166
const double sh_log  = (1.0 - kLatitude) * grey_log + kLatitude * xmax;   // 11167
```
- `latitude` 是 darktable v3 的**原生参数**（一个滑杆），它控制 **toe/shoulder 过渡区离中灰的距离**：
  - `lat = 0` ⇒ 过渡区**紧贴中灰**（toe/shoulder 覆盖几乎整个曲线 → 极端 S 形）
  - `lat = 1` ⇒ 过渡区**贴住端点**（曲线中间几乎线性 → 平）
  - `lat = 0.01`（darktable 默认）= 我们当前行为
- **v3 源码里 toe 与 shoulder 的插值本来就是两个独立表达式**，只是共用一个 `lat` 值。
  ⇒ **拆成 `lat_toe` / `lat_sh` 是原版参数维度的自然展开，不是自研**。✓

**为什么 per-channel（6 个槽位全用）**：
- 三层乳剂（R/G/B 感光层）的 toe/shoulder 位置**不完全相同** ⇒ 阴影/高光的**色偏**（Kodak 阴影偏暖、Fuji 高光偏青的机制之一）。
- 槽位形状（`float toe[3]` / `float shoulder[3]`）**原生就是 per-channel**，不浪费。
- **默认全 0.01** ⇒ 三通道相同 ⇒ **逐位等于当前行为**（A/B 通路）✓

#### 3.4.3 与现有 filmic 旋钮的正交性（**必须写进实现注释**）

| 旋钮 | 控制的数学量 | 是否改变端点 | 与 [5..10] 的关系 |
|---|---|---|---|
| `[37][38]` black/white EV | `black` / `white` → `dr` → `xmin` / `xmax` | ✅ **定义端点** | **[5..10] 不改变端点**：`f(0)=black_disp`、`f'(0)=0`、`f'(Tl)=contrast`、`f''(Tl)=0` 五条约束不变，只改 `Tl` 的位置 ⇒ 端点与端点斜率不动，**只改中段形状** ✓ |
| `[4]` contrast | `slope → contrast` → 5×5 系统的 `b[3]` | ❌ | **有交互**（都影响曲线中部）：`contrast` 改**斜率**，`latitude` 改**过渡区位置**。⚠️ 需真机 A/B 确认"两个旋钮是否互相干扰"（这正是历史上出过事故的模式） |
| `[28]` tone_strength | `mix(lin, toned, k)` | ❌ | 正交（[5..10] 改 `toned` 的形状，`[28]` 改混合比例） |

⇒ **结论：不新增曲线、不新增 pass、不新增 shader 求值**。[5..10] 只影响 CPU 侧 `solve5` 的输入 `Tl`/`Sl`。

#### 3.4.4 参数映射与边界

| 槽位 | 名称 | 映射 | 默认 | 边界 |
|---|---|---|---|---|
| [5..7] | `shoulder_r/g/b` | `lat_sh[c] = clamp(v, 0, 1)` | 0.01 | `lat_sh=0` ⇒ `sh_log = grey_log` ⇒ shoulder 从**中灰**开始（极端：高光全被压缩）—— **合法但极端**，UI 需给出提示 |
| [8..10] | `toe_r/g/b` | `lat_toe[c] = clamp(v, 0, 1)` | 0.01 | `lat_toe=0` ⇒ toe 从中灰开始（暗部全被抬/压）—— 同上 |

**求解顺序**（改造点，`11166-11167` 附近）：
```cpp
// 现在（单值）：
const double toe_log = (1.0 - lat) * grey_log + lat * xmin;
const double sh_log  = (1.0 - lat) * grey_log + lat * xmax;
// 改后（per-channel）：对 c ∈ {R,G,B} 各算一组，得到 3 套 (m1..m5) + lat_min/lat_max
```
⚠️ **shader 侧要一起改**：`kFsFilm` 里 `filmicSpline(e)` 用**单一** `uFilmicLatMin/LatMax` + 单套 `uFilmicM1..M5`（`4052-4058`）。
per-channel 后需要 **3 套系数**（`uFilmicM1..M5` 各 3 个 → 各 9 个）或**逐通道求值**（`filmicScalar` 三次，每次带自己的系数）。
- **推荐**：`uFilmicM1..M5` 从 `vec3` 扩为 `mat3`（9 个 `vec3`），`filmicSpline` 按通道选列 —— 但**注意 GPU 三禁**：不能用 `m[i]` 动态索引 ⇒ 展开成三个 `vec3` 或三个独立函数调用。
- **成本**：`filmicTone` 里 `filmicScalar` 调用次数不变（本来就是逐通道 3 次，`4071-4072`），只是每次多带一组系数 ⇒ **ALU 增加可忽略**。
- **零新增 pass** ✓

#### 3.4.5 性能量级

- **新增 pass：0；新增纹理：0；新增采样：0**。
- CPU：每次参数变更重解 3×2 = 6 个 5×5 线性系统（现在 2 个）⇒ 微秒级。
- GPU：`filmicTone` 的求值次数不变，系数从 uniform 读取（+9 个 vec3 uniform = 108B）⇒ **可忽略**。
- ⇒ **性能可忽略**。

---

### 3.5 层间效应（interlayer effects）★

#### 3.5.1 问题回答：`split_warm`/`split_cool`/分区饱和 [20..22] **能否**覆盖？

**结论：不能。** 逐条给证据：

| 现有机制 | shader 实现（行号） | 数学形式 | 自由度 | 为什么覆盖不了 |
|---|---|---|---|---|
| [20..22] 分区饱和 | `kFsFilm:4337-4342` | `sat = Σ w_i·uSat_i`；`out3 = mix(vec3(luma), out3, sat)` | **3 个标量** | 只沿"向 luma 收敛"这一条轴移动 ⇒ **无法产生通道间的不对称偏移**（无法做到"阴影 R 抬、B 压"而同时保持饱和度不变） |
| [23][24] 分离色调 | `kFsFilm:4343-4344` | `out3 += uSplit.x·ws·vec3(0.05,0.02,−0.04) + uSplit.y·wh·vec3(−0.03,0,0.05)` | **2 个标量** + **硬编码色向量** | ① 只有"阴影/高光"两端，**中间调无独立自由度**（`wm` 权重被 `uSat` 用掉，`uSplit` 没有中间调项）；② 色向量**不可配**（`vec3(0.05,0.02,−0.04)` 写死）⇒ 无法表达 Kodak（暖阴影 + 洋红中性）与 Fuji（青绿冷高光）的**不同色相方向** |

**调研要求的自由度**：3 亮度区 × 3 通道 = **9**。

#### 3.5.2 最小扩展方案：[69..77] `interlayer[9]`（乘法通道增益）

**数学定义**（新增在 `kFsFilm` 内，**在 `uSat` 之前**）：

```
ws = exp(−l·3.0)                    // 复用现有权重（4338）
wh = smoothstep(0.5, 1.0, l)        // 复用现有权重（4339）
wm = max(0, 1 − ws − wh)            // 复用现有权重（4340）

g_r = uInter[0]·ws + uInter[3]·wm + uInter[6]·wh     // 暗/中/亮 三区加权
g_g = uInter[1]·ws + uInter[4]·wm + uInter[7]·wh
g_b = uInter[2]·ws + uInter[5]·wm + uInter[8]·wh
out3 *= vec3(g_r, g_g, g_b)                          // 乘法（保色相方向，改亮度与饱和度）
── 然后才是现有的 uSat 分区饱和度（4337-4342）──
```

- **为什么乘法而不是加法**：层间效应是 DIR 耦合层对**显影抑制剂**的释放 ⇒ 对**该层显影量**的**比例**抑制 ⇒ 物理上是乘性；加法偏移是"分离色调"（创意），已由 [23][24] 承载。
- **为什么在 `uSat` 之前**：先做通道增益（改变原始通道关系），再调饱和度（"当前浓淡"）⇒ 两个旋钮语义不交叉。若反过来，饱和度处理后的结果再乘通道增益会让"饱和度"的观感不可预测。
- **默认全 1.0** ⇒ `g = 1` ⇒ `out3 *= 1.0` **逐位不变**（A/B 通路）✓
- **与 [23][24] 的分工**（**必须写进 UI 文案**）：层间 = **型号底色（物理）**；分离色调 = **创意风格（加法）**。同一预设里不建议两者都给强值（会互相掩盖，但不会"打架" —— 一个是乘法、一个是加法，数学上可分离）。

#### 3.5.3 边界情况

| 情况 | 行为 |
|---|---|
| `interlayer = 全 1` | `out3 *= 1.0` ⇒ 逐位不变（可做 A/B 基准） |
| 极端增益（如 R×1.5） | `out3` 可超 1.0（在 filmic **之后**）⇒ 由 `kFsOut` 的 `highlightRolloff`（knee 0.80）兜住 ✓ |
| 高饱和区 | 乘法会改变色度（与 [20..22] 的"保 luma 改色差"不同）—— **这是设计意图**（层间效应确实改变色相/饱和度），但需在 UI 提示"会改变色相" |
| 与 gamut 检查的顺序 | ⚠️ **必须处理的边界问题**。`kFsFilm` 的实际结构是：`4329 if (uToneK>0)` → `4333 toned = filmicTone(out3)` → `4334 if (uGamut) toned = gamutMapV5(out3, toned)` → `4335 out3 = mix(out3, toned, uToneK)` → **`4337` 起才是显示域**（`l = luma(out3)`）。`gamutMapV5` 是**逐像素的色域收尾**，一旦 interlayer 在它之后乘通道增益，颜色可能被推出 sRGB 色域（负值/超 1），而后续 `kFsOut` 的 `highlightRolloff` **只处理亮度上界，不修色域**。⇒ **落点 = `4336` 与 `4337` 之间（显示域、`uSat` 之前），并紧随一次 `gamutCheckRgb(gamutRgbToYch(out3))`**（函数已存在，纯算术 ≈30 ALU/px，成本可忽略）。**不要**放在 `4333-4334` 之间 —— 那里 interlayer 会作用在 `toned` 上，而 `gamutMapV5(out3, toned)` 的 `original` 参数仍是未处理的 `out3`，色度 min 的基准会错。 |
| 与 `uToneK` 的关系 | 若 `[28] tone_strength = 0`（A/B 通路），`out3` 是**线性场景域**，interlayer 的"显示域乘法"语义会变成"场景域乘法"。这是现有 `uSat`/`uSplit`/`uLook` 的**同型既有特性**（它们都在同一位置），**不新引入问题**，但**必须在实现注释里写明**，避免将来误判 |

#### 3.5.4 性能量级

- **新增 pass：0；新增纹理：0；新增采样：0**；ALU ≈ 9 乘 + 9 加 + 3 乘 = 21/px ⇒ 260M ALU。
- ⇒ **性能可忽略**。

---

### 3.6 漏光 / 边框 ★（**建议：本轮不做**）

#### 3.6.1 结论与理由

| 项 | 建议 | 理由 |
|---|---|---|
| **漏光（light leak）** | **不做** | ① 漏光是**随机空间事件**（光从片盒/背盖缝隙漏入），**没有可对齐的物理模型** —— 社区做法全是叠素材图（`docs/research/10` §2.1 的 Dazz 也是"样式"选择）；② 无法用参数表达（每张的位置/形状都不同）；③ 调研标 ★ 最低优先 |
| **边框（frame/border）** | **不做（引擎侧）**；若要做，**放在导出层**（UI/JPEG 后处理，叠 PNG） | ① 纯 2D 装饰，与成像链无关；② 进引擎会污染 `FilmParams` 契约（边框样式是枚举/资产，不是 double）；③ 编辑器（E1）已经在做"后段风格化"，边框在导出层实现成本更低、且可独立迭代 |

#### 3.6.2 若将来要做（记录，不实现）

- 漏光：预设 JSON 里放**素材 id**（一张 RGBA PNG）+ 混合模式 + 强度；引擎侧作为**最后一个 pass**（OETF 后）叠加。**需要新的 JNI 资产通道**（与 LUT 同型）。
- 边框：导出层（Kotlin/CPU 侧）用 `Canvas` 叠加，不进引擎。

---

## 4. 内置胶片预设库方案

### 4.1 现有 LUT 资产实况（**先纠正调研文档的两个数字**）

- 调研 §1 写"42 内置 .lut" ✓ **正确**（`ls app/src/main/assets/luts/ | wc -l` = 42）。
- 格式 = **33³ uint8 二进制**：`[size:1B][titleLen:1B][title][catLen:1B][category][data: size³×3B]`，
  由 `tools/gen-lut-assets.py` 从 **64³ ASCII .cube** 重采样生成（`resample()` 三线性，注释称 ΔE 0.04–0.17）。
- **源目录 = `~/Downloads/LUT/滤镜.cube/`（本机，43 个 .cube，每个 7,077,905 B）** ——
  ⚠️ **来源不可追溯**：这是第三方"滤镜包"（无作者、无授权说明、无生成方法文档）。
  43 个源里 `Ricoh-Positive`（理光 正片）**没有对应资产** ⇒ 42 个。
- **这对"胶片预设库"的含义**：**LUT 的色彩准确性无法验证**（不知道作者用什么机身/镜头/照明/流程做的）。
  ⇒ 本文档的预设方案定位为 **"用现有资产拼出有意义的型号"**，**不宣称色彩学正确**；
  关键型号（CineStill 800T / Portra 400）的**一手重建**单列为后续任务（§5 的 E4-7b）。

**资产分类清单**（按 `gen-lut-assets.py` 的 `META` 品牌分类）：

| 品牌 | 数量 | 型号 |
|---|---|---|
| CineStill | 2 | 50D、800T |
| 富士 | 13 | ACROS、ASTIA、CC、ETERNA、ETERNA BB、NC、NN、PRO Neg Std、PROVIA、REALA ACE、Velvia |
| 柯达 | 7 | 5203、5207、5219、E200、Gold 200、Portra 400、UltraMax 400 |
| 哈苏 | 2 | Blue、Classic |
| 徕卡 | 13 | M9、B&W true、B&W-HC、Brass、Chrome、Classic、Classic 2、Contemporary、Eternal、Nature、Vivid、Teal |
| MI | 2 | 人像胶片、风光胶片 |
| 理光 | 6 | 电影绿、电影黄、HDF、高反差黑白、负片、Vivid |

### 4.2 预设 JSON schema（最终版）

**设计原则**：
1. **参数键 = 语义名**（可读、可导入导出、可跨版本迁移），**但代码里必须有一张唯一的"语义名 ↔ 槽位索引"映射表**（`FilmParamKeys`），且**解析时遇未知键必须报错**（项目有"静默失败"的多次事故）。
2. **`params` 只允许 FilmParams 槽位**；非引擎参数进独立字段。
3. `base = "standard"` 保留（继承中性基线，防"全 0 数组"事故）。

```jsonc
{
  "schema": 1,
  "id": "cinestill_800t",                 // 稳定标识（文件名/持久化键）
  "name": "CineStill 800T",
  "category": "CineStill",
  "base": "standard",                     // 继承基线（必填）
  "note": "Vision3 500T 去 remjet；halation 为标志性特征",  // UI 说明（可选）

  "params": {                             // ← 语义名 → FilmParams 槽位（映射表见 4.3）
    "filmic_black_ev": -7.65,             // [37]
    "filmic_white_ev": 4.56,              // [38]
    "toe_r": 0.03, "toe_g": 0.01, "toe_b": 0.02,        // [8][9][10]
    "shoulder_r": 0.05, "shoulder_g": 0.01, "shoulder_b": 0.03,  // [5][6][7]
    "sat_shadow": 1.00, "sat_mid": 1.05, "sat_high": 0.95,       // [20..22]
    "split_warm": 0.15, "split_cool": 0.10,                      // [23][24]
    "monochrome": 0.0,                                            // [25]
    "grain_amount": 55.0, "grain_size": 800.0,                    // [26][27]
    "grain_midtone_bias": 100.0, "grain_pivot": 0.0,              // [60][61]
    "halation_strength": 0.35, "halation_threshold": 0.80,        // [62][63]
    "halation_radius": 0.45, "halation_warmth": 0.40,             // [64][65]
    "vignette_strength": -0.18, "vignette_radius": 80.0,          // [66][67]
    "vignette_feather": 50.0,                                     // [68]
    "interlayer_shadow_r": 1.02, "interlayer_shadow_g": 1.00, "interlayer_shadow_b": 0.98,  // [69..71]
    "interlayer_mid_r": 1.00, "interlayer_mid_g": 1.00, "interlayer_mid_b": 1.00,           // [72..74]
    "interlayer_high_r": 1.00, "interlayer_high_g": 1.00, "interlayer_high_b": 0.98        // [75..77]
  },

  "lut": "cinestill_800t",                // LUT 资产 id（= assets/luts 文件名去扩展名）
  "lut_strength": 0.90,                   // → [34]

  "ui": {                                 // 非引擎参数（UI 层元数据，不进 FilmParams）
    "grain_label": "ISO 800",
    "halation_hint": "点光源/夜景最强",
    "hidden": []                          // 该预设下隐藏的滑杆（可选）
  }
}
```

**明确不进 `params` 的字段**：`id` / `name` / `category` / `base` / `note` / `lut` / `lut_strength`（→[34]）/ `ui.*`。
**参数快照**（ADR-4）复用**同一 schema**（快照 = 预设 + 时间戳 + 源图路径），避免第二套格式。

### 4.3 语义名 ↔ 槽位映射表（**代码里唯一维护**）

| 语义名 | 槽 | 语义名 | 槽 | 语义名 | 槽 |
|---|---|---|---|---|---|
| `shoulder_r/g/b` | 5/6/7 | `grain_amount` | 26 | `vignette_strength` | 66 |
| `toe_r/g/b` | 8/9/10 | `grain_size` | 27 | `vignette_radius` | 67 |
| `sat_shadow/mid/high` | 20/21/22 | `grain_midtone_bias` | 60 | `vignette_feather` | 68 |
| `split_warm` / `split_cool` | 23 / 24 | `grain_pivot` | 61 | `interlayer_shadow_r/g/b` | 69/70/71 |
| `monochrome` | 25 | `halation_strength` | 62 | `interlayer_mid_r/g/b` | 72/73/74 |
| `lut_strength` | 34 | `halation_threshold` | 63 | `interlayer_high_r/g/b` | 75/76/77 |
| `filmic_black_ev` / `filmic_white_ev` | 37 / 38 | `halation_radius` | 64 | | |
| `contrast` / `tone_strength` | 4 / 28 | `halation_warmth` | 65 | | |

### 4.4 内置预设清单建议（用现有 LUT 能拼出什么）

**判据**：① LUT 资产存在；② 有**公开的胶片特性**可依据（正片/负片/电影卷、ISO、是否有 remjet/防光晕层、是否黑白）；③ 参数组合有物理依据（不是随便填）。

| # | 预设名 | LUT | 颗粒 ISO | halation | 依据与要点 |
|---|---|---|---|---|---|
| 1 | **CineStill 800T** | `cinestill_800t` | 800 | **强**（0.35–0.45） | Vision3 500T **去 remjet** ⇒ halation 是标志。CineStill 800T 是 500T 的"去 remjet + 推一档"版 |
| 2 | **CineStill 50D** | `cinestill_50d` | 50 | 中（0.2） | 同为去 remjet 的日用卷；颗粒细、halation 弱于 800T（曝光充足、点光源少） |
| 3 | **Kodak Vision3 500T (5219)** | `kodak_5219` | 500 | **弱**（0.05–0.1） | ⭐ **与 #1 构成 A/B 对照**：同一乳剂、**有 remjet** ⇒ halation 被抑制。**这一对是验证 halation 模块的最佳素材**（同 LUT 家族、只差 halation） |
| 4 | **Kodak Vision3 250D (5207)** | `kodak_5207` | 250 | 弱 | 电影日光卷 |
| 5 | **Kodak Vision3 50D (5203)** | `kodak_5203` | 50 | 弱 | 电影日光卷，颗粒最细 |
| 6 | **Kodak Portra 400** | `kodak_portra_400` | 400 | 无 | 负片（有防光晕层）；暖肤色、低饱和 ⇒ 层间效应用"阴影暖" |
| 7 | **Kodak Gold 200** | `kodak_gold_200` | 200 | 无 | 消费负片，暖黄 |
| 8 | **Kodak UltraMax 400** | `kodak_ultramax_400` | 400 | 无 | 高饱和消费负片 |
| 9 | **Kodak Ektachrome E200** | `kodak_e200` | 200 | **无** | ⭐ **正片（反转片）有防光晕层** ⇒ halation 必须为 0（物理正确性检查点） |
| 10 | **Fuji Velvia 50** | `fuji_velvia` | 50 | 无 | 正片，高饱和、颗粒极细 |
| 11 | **Fuji Provia 100F** | `fuji_provia` | 100 | 无 | 正片，中性 |
| 12 | **Fuji ASTIA 100F** | `fuji_astia` | 100 | 无 | 正片，柔和肤色 |
| 13 | **Fuji ACROS 100** | `fuji_acros` + `monochrome=1` | 100 | 无 | ⭐ 黑白：`monochrome=1` **在 LUT 之前** ⇒ 先去色再走 LUT ⇒ 若 LUT 本身是黑白则不冲突；颗粒细 |
| 14 | **Fuji ETERNA 500T** | `fuji_eterna` | 500 | 弱 | 电影负片，低饱和青调 |
| 15 | **Ilford HP5 / Kodak Tri-X 400**（**近似**） | 无对应 LUT ⇒ 用 `leica_b_w_true` 或 `ricoh_high_contrast_b_w` + `monochrome=1` | 400 | 无 | ⚠️ **不是真型号 LUT**，只是"黑白 + 中粗颗粒"的组合。**必须标注"近似"**，或不做 |

**建议首批（有物理依据、可 A/B 验证）**：**#1 #2 #3 #9 #6 #13** —— 6 个，覆盖"halation 有/无"（#1 vs #3、#9）、"黑白"（#13）、"主流负片"（#6）。

**明确不做**：徕卡 13 个（相机 JPEG 风格，不是胶片型号）、哈苏 2 个、MI 2 个、理光 6 个 ——
它们是**机身色彩风格**，不是胶片型号；可以作为"创意 LUT"留在现有 LUT 库里，**不进"胶片预设"分类**。

---

## 5. 实现顺序与拆分建议（E4-1 … E4-9）

> 铁律：每个子任务 = **一次出包 + 一轮真机 A/B**；先 100% 按参考实现完整跑通 → 真机实测 → 用数据评估。
> 每个子任务都必须带：A/B 开关（默认值由实测定）、诊断字段、`validate-shaders.sh` + `check-diag-fields.py` 通过。

| 卡号 | 标题 | 依赖 | 风险 | 交付判据 |
|---|---|---|---|---|
| **E4-1** | **契约扩容**：`kCount` 60→78 + `neutral()` `DoubleArray(78)` + `fromDoubles` 补 `if (n>60..77)` + `setFilmParams` size 兜底 + **`CameraController` 35 处裸写收敛为受保护入口**（§2.1.1）+ **全槽位非零默认值**（清单：[5..10]=0.01、[27]=400、[60]=100、[63]=0.80、[64]=0.45、[65]=0.40、[67]=80、[68]=50、[69..77]=1.0；其余 = 0） | — | **中**（漏一处 = 启动崩溃） | 装包启动不崩；日志回读全部 78 槽（逐槽打印默认值，与本文档 §2.2/§2.3 表格逐行核对）；`check-diag-fields.py` 过 |
| **E4-2** | **颗粒重写（对齐 darktable `grain.c`）**：simplex 3 八度 + 128² 相纸 LUT + ISO 尺寸 + [60]/[61] | E4-1 | 低–中（算法明确；风险在观感标定） | 真机裁图：颗粒呈"颗粒感"非"块状"；暗/中/亮可见度符合相纸曲线；RGB 通道差 = 0（单色） |
| **E4-3** | **暗角（对齐 darktable `vignette.c`）**：[66][67][68]，正圆、乘法、LUT 前 | E4-1 | **低** | 真机裁图：四角自然衰减、无同心圆 banding；关 = 逐位不变 |
| **E4-4** | **趾部/肩部**：[5..10] = per-channel `latitude`；CPU spline 拆 3 套系数 + shader `filmicSpline` 改逐通道 | E4-1 | **中**（与 [4] contrast 有交互，历史事故模式） | 真机 A/B：默认 0.01 逐位等于改前；调 [8] 时暗部色偏可见且黑位不抬 |
| **E4-5** | **halation**：5 级金字塔 + 阈值 + 通道扩散 + 合成（16 pass） | E4-1 | **高**（GPU 代价、内存、Mali 三禁） | 真机：日志 `haloOn=1`、`haloMs` 可接受；点光源呈"白→橙→红"径向渐变；关 = 零 pass 零纹理 |
| **E4-6** | **层间效应**：[69..77] 乘法通道增益（在 `uSat` 前、`gamutMapV5` 后需确认） | E4-1 | 低–中 | 默认全 1 = 逐位不变；调值后型号色偏可见且不过曝（rolloff 兜住） |
| **E4-7a** | **预设 schema + 内置预设库**（用现有 LUT 拼 6 个） | E4-2、E4-5 | 中（LUT 来源不可追溯） | 6 个预设可加载/可拍；快照可回读 |
| **E4-7b** | **关键型号 LUT 一手重建**（CineStill 800T / Portra 400）：用公开一手数据（扫描对比 / 开源 darktable 预设）重建，替代来源不明的第三方 LUT | E4-7a | 中 | 与现有 LUT 版 A/B，用户目视拍板 |
| **E4-8** | **参数面板 UI**：颗粒/暗角/halation 滑杆 + 分组 + 单位（ISO / %）+ 逐参数 A/B 长按 | E4-2、E4-3、E4-5 | 低 | 滑杆联动渲染；单位与范围与 §2 一致 |
| **E4-9** | **性能实测与拍板**：每项的真机耗时（成片 + 预览）+ 内存峰值，输出选项表交用户 | E4-2…E4-6 | — | 数据表 + 用户拍板记录 |

**并行度**：E4-2 / E4-3 / E4-4 / E4-6 **互不依赖**，E4-1 完成后可并行派工；E4-5 独立但最重；E4-7a 依赖 E4-2/E4-5；E4-8 最后。

**建议派工顺序**：`E4-1` →（并行）`E4-3` + `E4-4` + `E4-2` → `E4-6` → `E4-5` → `E4-7a` → `E4-8` → `E4-9`。
理由：先用**零风险、零 pass** 的 E4-3/E4-4 把"契约 + 出包 + 真机回读"这条链路验证一遍，再上 E4-5 这个高风险项。

---

## 6. 离线验证方案

### 6.1 `tools/engine-shader-offline.py` 现状

**已接入**（脚本 docstring，`tools/engine-shader-offline.py:12-21`）：
`kFsRawDenoise`（P0）→ `kFsRcdDir/G/Chroma(A+B)` → `kFsCcm` → `kFsFilm` →（可选）`kFsDefringe*` →（可选）`kFsPc3d`。
产物 = 每 pass 的 `.npy`（float32）+ `steps.json`。整图 4080×3072 约 2.8s。

**未接入**：HR（高光重建）、PC3D（需 LSC 纹理）、**`kFsOut`（OETF / 3D LUT / 颗粒 / dither）** —— 脚本用 `srgb_encode` 近似。

### 6.2 需要扩展的内容（按参数分）

| 参数 | 需要扩展 | 离线能否决策 |
|---|---|---|
| **颗粒** | ① 跑 `kFsOut`（至少 OETF + grain 段）；② 用 Python 复算 darktable 的 `evaluate_grain_lut` 与 shader 输出的 LUT 对拍；③ 输出**噪声功率谱**（FFT 径向平均）与**调制曲线**（按亮度分箱的 RMS） | **部分**：频谱/调制曲线/单色性 ✅ 可决策；**观感**（是否像真颗粒）❌ 只能真机 |
| **halation** | ① 新增 `kFsHaloThr/Down/Blur/Mix` 五个 shader 的离线执行（纯多 pass，脚本框架已支持 `size=(w,h)` 与多纹理）；② 若要验证 halo **颜色**，需同时接入 3D LUT（`.lut` 资产 33³ uint8，Python 15 行可解析 + moderngl 3D 纹理） | **形态与半径** ✅ 可决策（几何 + 阈值）；**颜色** ⚠️ 需接 LUT 才可决策；**性能** ❌ 离线是桌面 GL，与 Mali 无关 |
| **暗角** | 无（纯几何，可在 `kFsOut` 离线跑一次） | ✅ **完全可决策**（离线指标与真机同域同公式） |
| **趾部/肩部** | ① **CPU 侧**：Python 复算 `computeFilmicSpline` 的三套系数，与 C++ 对拍（脚本已有 `--ccm` 这类参数注入的先例）；② 离线跑 `kFsFilm` 比较曲线形状 | ✅ **完全可决策**（纯数学，域无关） |
| **层间效应** | 无（乘法增益） | ✅ **完全可决策** |

### 6.3 ⚠️ 离线链路的局限（**对每项逐一说明是否成立**）

ADR 原则 §1.6：**"离线链路不含 PC3D/P4 ⇒ 后链判据的离线指标不可用于决策。"**
按当前默认档 `[54] pc3d = 1`（**替换档**：PC3D 输出直接进 film，P4/defringe/D-1 旁路），这条局限的具体含义是：

| 参数 | 该局限是否成立 | 说明 |
|---|---|---|
| **颗粒** | ⚠️ **部分成立** | 离线图的**噪声底**与真机不同（PC3D 是唯一降噪 pass，离线不跑它 ⇒ 离线图残余噪声更高）。但颗粒是在 **LUT 之后**叠加的、幅度由 [26] 控制 ⇒ **"颗粒自身的频谱与调制曲线"可决策**；**"颗粒与真机残余噪声叠加后的观感"不可决策**。⇒ 判据分层：频谱/调制用离线，观感用真机 |
| **halation** | ✅ **成立** | halation 在 `kFsFilm` 之后，其输入（线性显示域图）在离线链上**与真机同域**；PC3D 影响的是上游色度噪声，对"高光提取 + 大半径模糊"的形态影响可忽略。**形态与半径可决策** |
| **暗角** | ❌ **不成立** | 纯几何，与上游任何 pass 无关 ⇒ **离线指标可直接用于决策** |
| **趾部/肩部** | ❌ **不成立** | 纯 CPU 曲线系数 + 逐像素求值 ⇒ 离线可逐位对拍 |
| **层间效应** | ❌ **不成立** | 纯逐像素乘法 ⇒ 离线可决策 |

### 6.4 真机验证判据（看什么指标、什么算通过、什么算失败）

**通用前置**（每张卡）：`validate-shaders.sh` 过 → `check-diag-fields.py` 过 → 出包 → `aapt dump badging` 版本核对 → 真机日志回读**实际生效值**（不能只看 Kotlin 侧下发值）。

| 参数 | 通过判据 | 失败判据（立刻回退） |
|---|---|---|
| **颗粒** | ① 日志 `grainOn=1`、`grainMs` 与基线同量级；② 裁图 100%：颗粒为**不规则颗粒**（非块状/条带）；③ 暗/中/亮三区可见度符合预期曲线；④ 放大看**无彩色斑点**（RGB 通道差 = 0） | 出现规律图案（hash 精度问题）／颗粒呈方块（value noise 未换）／彩色噪点（通道独立） |
| **halation** | ① 日志 `haloOn=1`、`haloMs`；② 点光源周围出现**"白→橙→红"径向渐变**（不是均匀红雾）；③ 关掉时画面**逐位不变** | 全图泛红（阈值过低）／光晕发灰发紫（tint 或通道扩散错）／`haloOn=0` 而 `[62]>0`（shader 编译失败或纹理分配失败 —— 静默失败） |
| **暗角** | ① 日志 `vigOn=1`；② 四角自然衰减、**无同心圆环**；③ 关 = 逐位不变 | 可见 banding 环（需评估 TEA 抖动）／角落出现色偏（应只衰减亮度） |
| **趾部/肩部** | ① 默认值下成片与改前**逐位相同**；② 调 [8]（toe_r）后暗部出现**可辨色偏**且**黑位不抬**（P0.1 不变） | 黑位被抬高（发灰）／高光失去层次／与 [4] contrast 互相干扰（同一旋钮两处响应） |
| **层间效应** | 默认全 1 时**逐位不变**；调值后型号色偏可见、无过曝破口 | 肤色变脏／偏绿／高光爆（rolloff 未兜住） |

**每项都必须有 A/B 通路**（ADR §1.8）：关掉 = 逐位回到改前。**"逐位不变"是本项目的硬判据**（历史多次"注释说关了其实没关"）。

---

## 7. 未验证的假设清单（**禁止当结论用**）

| # | 假设 | 状态 | 如何验证 |
|---|---|---|---|
| A1 | `shoulder[3]`/`toe[3]`/`cross[9]` 在 shader 内**零引用** | ✅ **已验证**（`grep` 全仓 + `FrameRenderer.h:1098` 注释） | — |
| A2 | `split_warm`/`split_cool`/`monochrome`/`grain_amount`/`grain_size` 已实现但默认关、无 UI | ✅ **已验证**（行号见 §1.1） | — |
| A3 | 引擎内**无** vignette / halation / bloom | ✅ **已验证**（`grep -i`，§1.3） | — |
| A4 | 现有颗粒在 4080×3072 下会因 `hash(sin)` 产生规律图案 | ⚠️ **未实测**（同型问题在 dither 上已发生，`4444-4451`） | 离线跑 `kFsOut` 的 grain 段，看频谱是否有规则峰 |
| A5 | darktable 的 `paper_resp` 模型**能**满足"胶片感"（调研要的"暗部重"需 [61]） | ⚠️ **未验证** | 真机 A/B：`pivot=0` vs `pivot=0.2/0.3`，裁图目视 |
| A6 | halation 在**线性显示域**做（而非场景线性域）观感正确 | ⚠️ **未验证**（emulsion 在显示域线性化后做，是可对齐的先例，但我们的链不同） | 真机 A/B：显示域线性 vs 场景线性（后者需在 `passFilm` 前插入，成本高，仅作反证） |
| A7 | 5 级金字塔 + 全分辨率合成在 Mali-G1-Ultra 上**可接受** | ⚠️ **完全未验证**（本文档估算 ≈124M texel fetch / ≥60ms 量级） | **E4-9 真机实测**（成片 `haloMs` + 预览帧率 + 内存峰值） |
| A8 | halation 的 `added=0` 像素经过 RGBA16F 往返后**不可见** | ⚠️ **未验证** | 离线逐位对拍（halation 关闭 vs 开启但阈值=1.0） |
| A9 | `interlayer` 放在 `gamutMapV5` 之后不会把颜色推出 sRGB 色域 | ⚠️ **未验证**（§3.5.3 已标为待确认） | 离线跑 `kFsFilm`，统计域外像素占比 |
| A10 | `[5..10]` 的 latitude 拆分与 `[4]` contrast **不互相干扰** | ⚠️ **未验证**（历史上"多旋钮互相干扰"出过事故） | 离线扫参数网格（lat × contrast）看曲线族是否单调可预期 |
| A11 | 42 个 LUT 的**色彩准确性** | ❌ **无法验证**（来源不可追溯，§4.1） | E4-7b：一手重建关键型号 |
| A12 | 真 halation 半径 50–200µm（⇒ 5.6–22px @4080） | ⚠️ **估算，非一手来源**（本文档 §3.2.3 标注） | 查 Kodak Vision3 技术文档 / CineStill 官方说明 |
| A13 | `kFsOut` 的 TPDF dither（±1LSB）足以掩盖暗角渐变的 banding | ⚠️ **未验证** | 离线跑暗角 + dither，看 8bit 输出的直方图是否有空隙 |
| A14 | 预览路径跑 halation 不影响交互帧率 | ⚠️ **未验证** | E4-9 实测预览帧率（预览分辨率随 `g_preview_scale` 变） |
| A15 | 调研文档"42 内置 .lut"与实际一致 | ✅ **已验证**（42 个；源 43 个，`Ricoh-Positive` 缺资产） | — |

**联网检索说明**：本次调研中 `web_search` 工具因**本机未配置 API key 而不可用**（报错 `DEEPSEEK_API_KEY` 缺失）。
已通过 `web_fetch` 直接抓取以下**一手源码/文档**（URL 见 §8）：darktable `grain.c` / `vignette.c` / `bloom.c`、
emulsion `halation.ts`、CineStill 社区配方集。
**未能联网核实**的项已在 A12 等条目显式标注为"估算"。

---

## 8. 来源（URL）

| 用途 | 来源 |
|---|---|
| 颗粒参考实现（逐行对齐） | darktable `src/iop/grain.c` — https://raw.githubusercontent.com/darktable-org/darktable/master/src/iop/grain.c |
| 暗角参考实现（逐行对齐） | darktable `src/iop/vignette.c` — https://raw.githubusercontent.com/darktable-org/darktable/master/src/iop/vignette.c |
| bloom 对照（screen blend / box_mean） | darktable `src/iop/bloom.c` — https://raw.githubusercontent.com/darktable-org/darktable/master/src/iop/bloom.c |
| halation 参考实现（通道扩散 / 金字塔 / tint） | jie60517/emulsion `src/render/effects/library/halation.ts` — https://raw.githubusercontent.com/jie60517/emulsion/main/src/render/effects/library/halation.ts |
| CineStill 800T 社区配方（Lightroom 参数 + "halation 无法在 LR 实现"） | peva3/Lightroom-Presets `research/Cinestill-800T/community-recipes.md` — https://raw.githubusercontent.com/peva3/Lightroom-Presets/main/research/Cinestill-800T/community-recipes.md |
| 项目内调研（参数清单来源） | `docs/research/10-内建编辑器与胶片预设.md` §2.4 / §3.2 / §3.3 |
| filmulator 取证（**grain 不存在**的负面证据） | `docs/research/filmulator-filmsim-forensics.md:555`（`grep -rni "grain"` 零命中） |
| LUT 资产生成与来源 | `tools/gen-lut-assets.py`（源 = `~/Downloads/LUT/滤镜.cube/`，64³ → 33³ uint8） |

---

## 附录 A：本次设计**没有**做的事（明确边界）

1. **未修改任何源码**（只读调研 + 本设计文档）。
2. **未实测任何性能**（A7 等标记为"未验证"）。
3. **未拍板任何默认值**（§2.3 的默认值是"起点建议"，最终默认值按 ADR §1.8 = **实测决定**）。
4. **未设计** 漏光/边框的引擎实现（§3.6 给出"不做"的建议与理由）。
5. **未验证** LUT 资产的色彩准确性（§4.1 指出来源不可追溯，E4-7b 单列）。
