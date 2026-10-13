# FilmKit 引擎插槽设计（公共管线 + 可切换影调引擎）

> 目标：把"影调 + 外观"这一段重构成 **公共管线 + 引擎插槽**，对齐 `bjzhou/PhotonCamera`（下称 PC）的
> `RawEngineTonePass` 形态，使**加引擎 = 加一段 GLSL + 一份资产 + 注册表一行**。
> 用户 2026-10-13 拍板："引擎切换要做，第 2 步直接对齐 PC。"
>
> 与既有文档的关系：本方案是 `docs/refactor-architecture.md` §3.2 链路分层中
> **阶段 11（影调）及其后处理边界的细化**，**不修改**其域字典（D0–D5 / A1–A8）与 12 阶段分层，
> 且必须满足其 §2 的 8 条设计原则。新增 pass/target 一律带 `@domain:` 标签并通过
> `tools/domain-contract-check.py`。

---

## 0. 一句话方案

**`kFsFilm` 现在是"曝光 + 局部色调映射 + filmic 影调 + gamut + 外观层"的混合 pass。**
本方案把它拆成三段：**公共前段**（曝光 / 局部色调映射 / 域映射 / 引擎输入准备）、
**引擎段**（影调曲线 + gamut，可替换）、**公共后段**（外观层 sat/split/mono/look + 黑白电平），
三者在**同一个 pass** 内执行（对齐 PC"引擎 = 1 个 pass"），但 shader 源码由
**公共模板字符串 + 引擎注入字符串**在初始化时拼接而成。

---

## 1. 现状拆解（代码级，全部实读）

### 1.1 shader 与 pass 落点

| 项 | 位置 | 内容 |
|---|---|---|
| `kFsFilm` | `FrameRenderer.cpp:4046` | 局部色调映射 → 曝光 → filmic spline（per-channel 三段多项式）→ **darktable filmic v5 gamut（五步）** → 外观层（sat/split/mono/look）→ 域映射 |
| `kFsOut` | `:4544` | 显示域：OETF 编码 + 颗粒 + 清晰度/影调分区/褪色 + 3D LUT + 暗角 + 柔光 + 锐化 |
| `kFsStyle` | `:5014` | **编辑器链路**（另一条，不改） |
| `prog_film_` | `:6526`（`linkProgram(vs_plain_, fs_film)`） | filmic pass 的 program |
| `u_film_` | `FrameRenderer.h:1334` | 该 pass 全部 uniform location |
| `FilmicSpline` | `FrameRenderer.h:574` | 15×2 分段多项式系数（CPU 侧算好下发） |
| `computeFilmicSpline` | `FrameRenderer.cpp:13044` | CPU 侧求解 spline |
| `derive_power` | `:13442` | **白点→output_power 的耦合点**（与 darktable `_compute_output_power` 同式，17 号报告已证 float32 逐位相同） |

### 1.2 引擎段的边界（本方案的核心判断）

**属于引擎**（可替换，且不同引擎语义不同）：
- 影调曲线本体：`filmicSplineR/G/B` + `filmicScalarR/G/B` + `filmicTone()`（`kFsFilm` 内，相对行 126–182）
- **gamut mapping**（相对行 183–372）：它是 **darktable filmic v5 的一部分**（五步逐行对齐），
  换引擎后（sigmoid / ACES）gamut 策略随之改变 ⇒ 必须归引擎

**属于公共**（所有引擎共用，写在模板里）：
- 曝光（`uExposure`）
- 局部色调映射（`uLocal*`，M4-7）
- 域映射（`uHalDomainMap` / `uDomainRatio`，第 6 期 S11/S12）
- 外观层：`uSat`（阴影/中间/高光饱和度）、`uSplit`（分离色调）、`uMono`（单色）、
  `uLook` / `uLookSat` / `uLookChroma`
- 引擎输入准备（`uProfileToEngineTransform` 等）

**保持在 kFsOut（不动）**：编码、颗粒、LUT、暗角、柔光、清晰度、锐化。

> 为什么 gamut 归引擎而不是公共：若把 gamut 留在公共段，ACES 引擎会被套上一层
> filmic v5 的 gamut 压缩（双份色域处理），且 sigmoid 引擎也需要自己的 primaries 段
> （darktable 4.6 给 sigmoid 加了 primaries）。归引擎后，"引擎自己负责域内色域行为"
> 这条契约才自洽 —— 与 PC 每个引擎自带 profile/gamut 的做法一致。

### 1.3 现状的架构问题（为什么必须改）

1. **不可切换**：引擎逻辑与公共逻辑在同一个 `const char*` 字面量里，加一个引擎 = 复制整个 pass。
2. **参数面污染**：filmic 参数走 `FilmParams`（`[4]/[5..10]/[37]/[38]`），而 `kCount=98` 且
   **四处同步**（`FilmParams.h:35` / `CameraPresets.kt:22` / `fromDoubles` 防呆 / 测试守卫；
   `CameraPresets.kt:30` 记录 v0.10.196 只改 C++ 未改 Kotlin ⇒ **启动崩溃**）⇒ 新引擎参数
   **绝不能再进这个数组**。
3. **白点耦合**：`[38]` 同时是"风格参数"和"曲线端点"，`derive_power` 把它焊进曲线形状（17 号报告）。

---

## 2. 对齐目标：PC 的模板形态（一手取证）

`RawEngineTonePass.kt:417-505` 的公共骨架（逐字结构，非转述）：

```glsl
#version 300 es
precision highp float; precision highp int; precision highp sampler2D;
in vec2 vTexCoord; out vec4 fragColor;

uniform sampler2D uInputTexture;
uniform float uBlacks; uniform float uWhites;
$shadowsHighlightsUniforms
uniform mat3 uProfileToEngineTransform;      // 公共：工作空间 → 引擎工作空间

${shaderDefinition.engineUniforms}           // ← 引擎注入 ① 私有 uniforms
${shaderDefinition.profileUniforms}          // ← 引擎注入 ② profile/资产

vec3 linearToSrgb(vec3) { ... }              // 公共工具

${shaderDefinition.profileFunctions}         // ← 引擎注入 ③ profile 函数
${shaderDefinition.engineFunctions}          // ← 引擎注入 ④ 引擎算法函数
$prepareEngineInputFunction                  // ← 引擎注入 ⑤ 输入准备
$shadowsHighlightsFunctions

/* 公共：黑白电平、luma 工具 ... */

void main() {
    vec3 color = texture(uInputTexture, vTexCoord).rgb;
    color = prepareEngineInput(color);       // 引擎段：输入准备（含 uProfileToEngineTransform）
    color = applyEngineTone(color);          // ★ 引擎段：一行调用
    $shadowsHighlightsApply
    color = applyBlackWhiteLevels(color);    // 公共：黑白电平
    fragColor = vec4(color, 1.0);            // ★ 输出契约 = 线性 RGB（不编码）
}
```

关键契约（14/15 号报告已取证）：
- **引擎 = 1 个 pass**；接口 5 方法（`RawRenderingEngineToneAlgorithm.kt:17-58`）
- **输出契约固定 = 线性 sRGB**，OETF 只做一次（`:499-501` 注释逐字）
- 每引擎声明 `workingColorSpace` + `exposureCompensationDomain`(Linear/Curve) + `defaultEV`
  （`RawRenderingEngine.kt:10-74`）
- 资产 manifest 全量 `require`，失败拒绝渲染不回退；单引擎资产 0.5–9 MB
- 引擎注册表 + 私有参数**不进 DB 列**（PC 的 Fuji 下线 = 33 文件改动 + Room DB v49→v50 迁移，15 号报告 §1.8）

---

## 3. 目标形态（FilmKit 版）

### 3.1 三段式 pass（单 pass，源码拼接）

```cpp
// 初始化时（一次）：
std::string src = kFilmTemplateHead;          // 公共：precision/IO/公共 uniforms/公共函数
src += engine.uniforms;                        // 引擎①：私有 uniforms
src += engine.profileAssets;                   // 引擎②：资产声明（LUT 采样器/表）
src += kFilmTemplateMid;                       // 公共：曝光/局部色调映射/域映射/引擎输入准备
src += engine.functions;                       // 引擎③：算法函数（含 gamut）
src += kFilmTemplateTail;                      // 公共：外观层 + 黑白电平 + main 骨架
// main 内：
//   prepareEngineInput(color) → applyEngineTone(color) → 公共外观层
prog_film_ = linkProgram(vs_plain_, compileFragment(src));
```

- **保持 1 个 pass**（不新增全分辨率纹理、不新增 draw call）⇒ 符合 PC 形态，
  且不触碰 `refactor-architecture.md` 的域字典与显存预算。
- 拼接只在初始化发生；运行期零额外开销（与现状编译产物同规模）。
- 引擎 0（内置 filmic）= 把现有 `kFsFilm` 的 filmic + gamut 段原样搬进注入段 ⇒ 行为不变（见 §8 阶段 1 判据）。

### 3.2 域契约

| 项 | 约定 |
|---|---|
| pass 输入 | **D4**（场景线性 sRGB，`refactor-architecture.md` §3.1），沿用现有 `tex_scene_` 语义 |
| 引擎域桥接 | `uProfileToEngineTransform`（mat3）：D4 → 引擎声明的 `workingColorSpace`。**阶段 1 传单位阵**（引擎域 = D4），需要时再引入更宽工作空间（也是 ACES 的挂点） |
| pass 输出 | **线性显示域（D5 前）**，编码仍由 `kFsOut` 做一次 |
| 域标签 | 不新增 render target ⇒ 不改 `@domain:` 台账；若某引擎需要额外中间 target，必须登记并通过 `domain-contract-check.py` |

### 3.3 与 §2 八条原则的合规性

- **原则 2（白点单一真相源）**：本方案**减少**白点用途——`[38]` 从"曲线端点参数"降级为
  "引擎私有参数/场景估计"，不再由它驱动形状（sigmoid 引擎里它直接消失）。
- **原则 3/4（判据与作用量同域、禁止跨域 mix）**：引擎输入准备是**显式**的矩阵变换，
  跨域在代码里看得见（对齐 `runHighlightReconstruct` 那个"唯一做对跨域判据"的正面模板）。
- **原则 8（删除优先）**：阶段 1 落地时删掉 `kFsFilm` 中已被注入段取代的硬编码路径，不留双份。

---

## 4. 引擎契约（5 项，每项都要在注册表里显式声明）

| # | 契约 | 内容 | 判据 |
|---|---|---|---|
| 1 | **域** | `workingColorSpace` + 到 D4 的 `mat3`（可为单位阵） | 离线往返残差 < 1e-6 |
| 2 | **曝光补偿域** | `Linear` / `Curve`（对齐 PC 的 `exposureCompensationDomain`）+ `defaultEV` | 切换引擎时中灰漂移 ≤ 1.3 LSB |
| 3 | **参数通道** | 引擎私有参数（有序 KV / JSON），**不进 `FilmParams`** | 字节稳定：同参数两次下发 sha256 相同 |
| 4 | **资产** | manifest（源 sha256 + payload sha256 + magic + schema + 单调性 + 端点） | 篡改 1 字节 → 拒绝渲染、不回退 |
| 5 | **失败策略** | 引擎内部**严格失败**（绝不 identity 伪装）；外部捕获 → 该引擎 unavailable + 日志，照片回落引擎 0 | 注入故障引擎：出图 = 引擎 0 结果，且日志有记录 |

---

## 5. `FilmParams` 扩展（只加 1 槽）

```
[98] engine_slot   // 0 = 内置 filmic（兼容档，默认）；N = 注册表第 N 个引擎
```

- **不新增任何其它槽**。引擎私有参数走独立通道：
  `nativeSetEngineParams(int slot, byte[] orderedKv)`（新增 JNI 方法，与现有 `nativeStillStart` 并列）。
- **四处同步点**只剩一处变化（`kCount` 98→99），且必须同批改：
  `FilmParams.h:35` / `CameraPresets.kt:22` / `fromDoubles` 防呆 / `CameraPresetsTest` 守卫。
- 旧预设（长度 98 的数组）按现有"按实际长度读取（下限 26）"语义自然得到 `engine_slot=0`
  ⇒ **旧预设默认走引擎 0，行为不变**。

---

## 6. 引擎注册表（集中化，防 PC 式扩散）

**C++ 侧** `core/engine/src/film/EngineRegistry.{h,cpp}`：

```cpp
struct EngineEntry {
  int          slot;              // = FilmParams[98] 取值
  const char*  name;              // 持久化名（与 Kotlin 对称）
  const char*  workingSpace;      // 域声明
  ExposureDomain exposureDomain;  // Linear / Curve
  float        defaultEV;
  const char*  uniformsSrc;       // 注入①
  const char*  profileSrc;        // 注入②
  const char*  functionsSrc;      // 注入③
  bool       (*loadAssets)(const std::string& dir);  // manifest 校验
  bool       (*applyParams)(const OrderedKv& kv);    // 参数下发
};
```

**Kotlin 侧** `app/src/main/java/com/filmkit/app/ui/EngineRegistry.kt`：与 C++ 表**一一对应**
（name/slot/参数 schema/UI 显示名），由一个测试断言两份表**逐项一致**（同 `CameraPresets.COUNT` 守卫的做法）。

**硬约束**：加/删引擎只改注册表 + 资产 + UI 列表；
- **不改** `FilmParams` 槽位布局
- **不改** Room DB schema（引擎私有参数不进 DB 列 —— PC 的 Fuji 下线代价就是没做这层）

---

## 7. 第一个引擎：sigmoid（落点）

| 层 | 改动 | 依据 |
|---|---|---|
| GLSL | 注入段实现 generalized log-logistic：`film = pow(film_fog + max(x,0), film_power)`；`paper = white * pow(film/(paper_exp+film), paper_power)` | darktable `sigmoid.c`；**PC 的 `DarktableSigmoidToneAlgorithm.kt` 有逐行可对照的 GLSL 实现**（常量：`white_target=1.0`、`paper_exposure=0.354355423`、`film_fog=0.00142637086`、`film_power=1.5`、`paper_power=1.0`） |
| GLSL（细节） | 负值去饱和（`desaturateNegativeValues`）+ per-channel hue preservation（通道排序）| PC 同文件；darktable 源码 |
| CPU 侧 | 新增 `computeSigmoidParams(contrast, skew, displayWhiteTarget, displayBlackTarget)`：由三锚点解析求解（~20 次 pow，亚毫秒），**取代 `computeFilmicSpline` 在引擎 1 路径上的调用** | 16 号报告 §6.3 公式；`FilmicSpline` 结构体不删（引擎 0 仍用） |
| 参数 | 引擎 1 使用 `contrast` / `skew` / `display_white_target`；**`[37][38]` 在此路径下不参与** | 17 号报告：白点参数消失是"换架构"最稳的收益 |
| 预设 | 旧预设 `engine_slot=0` 不变；新预设可标 `engine_slot=1` + 私有 KV | 向后兼容 |
| 资产 | **零新增资产**（纯解析曲线） | 16 号报告 |

**判据（sigmoid 引擎验收）**：
1. **可分辨跨度**（从白纸起每 0.1 EV ≥1 LSB 的持续距离）≥ 引擎 0，**同白纸亮度口径**
   （判据口径与已对齐数据见 §10 第 1 条）；
2. 中灰 0EV 偏差 ≤ 1.3 LSB；
3. 线性域严格单调（数值扫描，无平台段）；
4. 真机 A/B：白瓷/釉面红杯/白墙逆光/阴天天空/暗光室内/高饱和红光 × 3 曝光；
5. `engine_slot=0` 时 CRC32 与改造前**逐字节等价**（见 §8 阶段 1）。

---

## 8. 阶段划分与验收

| 阶段 | 内容 | 验收判据（硬） |
|---|---|---|
| **0 契约与基线** | 建 `EngineRegistry`（只含引擎 0）；`FilmParams` 加 `[98]`；采集基线：现有成片 CRC32 + filmic 参数快照 | 六处同步通过 + `CameraPresetsTest` 绿；**`[98]=0` 时同帧 CRC32 与基线逐字节一致** |
| **1 模板化重构** | `kFsFilm` 拆成 `kFilmTemplateHead/Mid/Tail`，filmic+gamut 搬进引擎 0 注入段；初始化期拼接 | 同阶段 0 的 CRC32 判据（**这是零回归的唯一硬门槛**）；`validate-shaders.sh` 通过；`prog_film_ != 0` |
| **2 sigmoid 引擎** | 加引擎 1（GLSL + CPU 参数求解 + 注册表两项） | §7 的五条判据 |
| **3 切换与预设** | UI 切换入口（对齐 PC 的引擎选择面）；预设 schema 支持 `engine_slot` + 私有 KV | 两引擎互切无串扰；旧预设逐字节等价；删资产 → 该引擎灰显、其余正常 |
| **4 ACES 预留** | 引入 `workingColorSpace` 非 D4 的引擎支持（`uProfileToEngineTransform` 实际启用） | 域往返残差 < 1e-6；单独一期实测（不在前四期内） |

> **阶段 0 已交付**（2026-10-13）：`[98] engine_slot`（`FilmParams.h`，`kCount` 98→99）+ 六处同步 +
> `EngineRegistry.{h,cpp}` 骨架（只含引擎 0，渲染路径零引用）+ Kotlin 侧同步与测试护栏。
> **基线**落在 `docs/reports/stage0-engine-slot-BASELINE.md` —— 记录离线链 P0/RCD/CCM/**FILM** 的
> CRC32 与 SHA-256（§3、§3.3，两组白点口径）+ 链外清单（§4.2）。
> **两个缺口的处置**：① 离线执行器实际**未接入 `kFsFilm`**（文件头注释与实现不符）⇒
> **已补齐**（2026-10-13，纯增量 +383 行 + `tools/film-offline-REPORT.md`）⇒ 阶段 1 的 CRC32 判据可执行；
> ② 拍照链**没有 filmic 参数日志**（`engine.preview.filmic` 只在预览分支）⇒ **仍未解决**，阶段 1 前补。

---

## 9. ACES 2.0 的预留接口（为"要不要上 C"留门）

本方案**不实现 ACES**，但阶段 0–3 的结构必须让它可插：

1. **域挂点**：契约 1 的 `workingColorSpace` + `uProfileToEngineTransform` 就是 ACES 的
   "相机 → ACES2065-1" 输入变换位置（**换矩阵即可**：现有 `StdColorMatrix.kt` 的
   `XYZ(D50) → 线性 sRGB(D65)` 换成 `→ AP0(D60)`）。
2. **绝对亮度锚**：ACES tone scale 按显示峰值亮度（nits）参数化 ⇒ 引擎私有 KV 里加
   `display_peak_nits` + 一条"我们的 0EV/中灰 ↔ nits"口径（**待拍板**，16 号报告 §6.4）。
3. **资产**：hue 表（360~364 项 RGBA32F ≈ 6 KB）+ cusp 表，走契约 4 的 manifest。
4. **对齐判据**：ACES 引擎的验收沿用 §7 五条 + 高饱和色 hue 偏移（ΔE2000 / Δh）——
   这是 ACES 唯一可能拉开差距的维度（17 号报告：统一口径下层次只差 0.25~0.30 EV）。

---

## 10. 未决与风险（需拍板/需实测）

1. **判据口径（架构师建议，待用户点头）**：改用「**可分辨跨度**」= 从白纸起每 0.1 EV ≥1 LSB
   能持续多远，作为主判据。17 号 §1.5 已证"死白起点"换阈值会翻转排序
   （≤1 时 sigmoid 4.25 > filmic 3.55；≤2 时 filmic 3.90 > sigmoid 3.10），而"可分辨跨度"在
   ≤0.5/≤1/≤2 三种阈值下都单调、且与感知一致。**若保留"死白起点"，必须写明阈值。**
   对齐白纸 204 LSB 后（17 号 §1.6）：filmic 1.65 EV / sigmoid c=1.2 1.95 EV / ACES2 1.90 EV
   —— 优势 0.25~0.30 EV，且三者只用掉 8bit 信息论上界的 32~41%。
2. **交付位深：已拍板 10bit HEIF（用户，2026-10-13）**。连带三项：
   ① 引擎输出需 10bit 路径 + HEIF 编码（现为 8bit `JpegEncoder.cpp`）；
   ② 交付色域应与公共工作空间**联动**（见第 3 条）；
   ③ 17 号 §3.5：**10bit 只能救 sigmoid/ACES 的"量化不可逆"，救不了 filmic 的连续不可逆平顶**
   ⇒ "上 10bit"本身就要求换掉 filmic，否则位深收益被 plateau 吞掉。
3. **公共工作空间（待拍板）**：质量层面**宽色域更优**（per-channel 影调对饱和色的色相漂移更小、
   超范围色更晚被截断），但**必须配输出端 gamut mapping**，且对"白瓷/釉面死白"这一当前痛点
   **无直接影响**（那是亮度/影调问题，不是色域问题）。推荐 **Rec.2020 线性**：
   ① PC 的 `DarktableSigmoid`/`AgX`/`Filmic` 引擎**即在 BT2020 域**（`RawRenderingEngine.kt`），
   照做可直接对照其参数与行为；② Rec.2020 是 HEIF 交付的标准宽色域容器。
   ⚠ **换工作空间 = 整条链处理域改变（`refactor-architecture.md` 的 D4 定义变化）= 所有预设重标**，
   属**管线级改动**，必须单独一期 + 真机实测，**不可与插槽重构同期**
   —— 否则丢掉"`[98]=0` 时 CRC32 逐字节等价"这条零回归证明力。
4. **`kFsOut` 是否也要插槽化**：目前假定外观层（LUT/颗粒/暗角/柔光）公共且不动；
   若将来"胶片外观引擎"也要切换，那是第二个插槽点（不在本方案范围）。
5. **PC 式"引擎预览 LUT 图集"**：PC 最新 commit（`b9cf590` 附近）有
   `raw engine preview LUT atlas and shader sampling` —— 若我们也要"预设/引擎预览缩略图"，
   需要额外的资产管线（不在本方案范围，但注册表设计需预留字段）。
6. **离线链的覆盖边界（2026-10-13 已实测澄清）**：原以为只是"PC3D 用恒等 LSC"，实测发现
   **FILM 段当时根本没接入**（文件头注释与实现不符）⇒ 已补齐（含 PC3D 真接入，LSC 仍恒等）。
   现行边界见 `docs/reports/stage0-engine-slot-BASELINE.md` §4.2：**HR 高光重建 / `uLocal*` /
   PC3D 标定 / `kFsOut` / MFNR 不在链内** ⇒ 阶段 1/2 的离线对照只能证明"`kFsFilm` 自身输出没变"，
   **不能**主张成片零回归（后者必须真机同帧 A/B）—— 否则重演"离线好看真机判死"。

---

## 附：本方案引用的取证报告

| 报告 | 用途 |
|---|---|
| `docs/research/15-引擎架构与执行框架.md` | PC 引擎插槽契约、Fuji 下线代价、Vulkan/Halide 选型 |
| `docs/research/16-影调科学前沿与自动白点.md` | sigmoid / ACES2 公式与参数、候选架构判断 |
| `docs/research/17-独立复核-影调架构量化.md` | 量化修正、判据敏感度、守恒律反例 |
| `docs/research/13-学习型影调与自适应色彩映射.md` | 学习型形态（本期不实现，留作第三引擎候选） |
| `docs/refactor-architecture.md` | 域字典 / 12 阶段分层 / 8 条原则（本方案的上位约束） |
