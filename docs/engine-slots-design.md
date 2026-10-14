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
4. 真机 A/B：**实际执行版 = 釉面红杯（0 / +1.5 EV）+ 暗光室内，2 场景 / 每包 3 张**
   （2026-10-14 已执行，判据 E/F/G/④ 全 PASS，实测数据见
   [stage2-sigmoid-REPORT.md](reports/stage2-sigmoid-REPORT.md) §9）。
   ⚠ **原文清单的「白瓷 / 白墙逆光 / 阴天天空」从未被实拍过**（用户 2026-10-14 反馈：从未拍过白杯子）
   —— 将来派活**请勿再按这三项派**；「高饱和红光」已并入釉面红杯（红杯本身即高饱和红 + 釉面高光）。
   ⚠ 该场景**未充分体现判据 ①**（+1.5 EV 下两包死白仅 0.001% / 0%，缺足够强的死白区）⇒
   要拿判据 ① 的真机强证据，需**直射灯 / 窗**这类真正过曝的场景（判据 ① 现有硬证据仍是本机合成 EV 阶梯）；
5. `engine_slot=0` 时 CRC32 与改造前**逐字节等价**（见 §8 阶段 1）。

### 7.1 阶段 2 落地定案（2026-10-13，架构师）

| 项 | 定案 | 理由 |
|---|---|---|
| 实现模式 | darktable **per-channel** 路径（`process_loglogistic_per_channel`） | PC 同款；rgb-ratio 路径不在本期 |
| primaries inset/rotation | **本期省略**（只保留 hue preservation） | darktable 默认 `inset=0 / rotation=0 / purity=0` ⇒ `_calculate_adjusted_primaries` 是**恒等变换**，PC 亦省略 ⇒ 默认参数下与官方逐像素等价。非默认 inset 是"Rec.2020 基础 primaries"那期的挂点 |
| 参数求解位置 | **全在 CPU**（`film/SigmoidParams.{h,cpp}`，逐行照抄 `commit_params`） | GLSL 只做曲线求值（吃 7 个标量 uniform）⇒ 公式**单处实现**，无 CPU/GPU 漂移面（对齐 `computeFilmicSpline` 既有做法） |
| 精度口径 | **全程 float32**（`powf`） | 与 darktable 实跑一致（其 data 字段与 `commit_params` 局部量皆 `float`）⇒ contrast=1.5 的 `film_power` = **1.490909**，非 double 的 1.5 |
| 默认参数 | `contrast=**1.5** / skew=0 / white=100 / black=0.0152 / hue=100` = **darktable / PC 官方默认**（2026-10-14 用户拍板"跟 PC 一致"，取代原 c=1.2） | **PC 的硬编码常量就是这个默认下的 double 解**（`paper_exposure=0.354355423` / `film_fog=0.00142637086` / `film_power=1.5`）；本实现走 float32 求解得 `0.359695 / 0.001384322 / 1.490909`，**8bit 输出上逐点差 ≤0.41 LSB**（−6…+8 EV 全域实测，mean 0.168）⇒ 与 PC 在 8bit 下不可分辨。默认档白纸 **218.84 LSB**。⚠ **代价（如实记录）**：不再同白纸口径 ⇒ 判据 ① 跨度由 2.00 EV（c=1.2）降为 **1.40 EV**；这是**判据口径的产物、不是质量下降** —— PC 从不做跨引擎白纸对齐（各引擎用自己的标准参数），"切引擎观感差异"正是这么来的。**历史**：c=1.2 是 2026-10-13 为判据 ① 的"同白纸口径"人为取的值（白纸 203.601 LSB），该人为约束已按用户拍板作废 |
| uniform 命名 | `uSig{WhiteTarget,BlackTarget,PaperExposure,FilmFog,FilmPower,PaperPower,HuePreservation}` | 前缀隔离；引擎 0 的 program 里 location = −1 ⇒ 无条件查询/下发即安全 no-op，零分支 |
| 引擎 0 路径 | **零改动**（注入段仍从 `kFsFilm` 切片） | 保住判据 ⑤（`engine_slot=0` 逐字节等价） |
| shader 校验 | 引擎 1 的段必须被 `validate-shaders.sh` 覆盖 + 负向自检 | 该脚本原只覆盖 `kFsFilm`（含"拆段后拼回整份"分支）⇒ 新增字面量会漏校验，语法错误只能到真机才发现 |

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

### 8.1 阶段 3 落地定案（2026-10-14，架构师；依据 = **代码实读**，非 UI 标签推断）

> 定案起因：用户指出"切引擎后要**旁路** Filmic 的功能并隐藏开关"。首轮我按 UI 标签归类，
> 被用户当场纠正（"对比度怎么会是 filmic 专属"）⇒ 改为**按消费链实读**，本节的每条都有行号。

#### A. 参数归属（`kFsFilm` 按 `filmSlices()` 锚点切 5 段 + 逐段提取 uniform + 反查赋值点）

| 归属 | uniform（实读） | 槽位来源 |
|---|---|---|
| **引擎 0 注入段独占** | `uFilmic*` × 20 | `FrameRenderer.cpp:14014` `updateFilmicEV(…, p.auto_tone, p.filmic_black_ev, p.filmic_white_ev, p.white_hl/floor/probe, p.exposure_ev, p.contrast, p.shoulder, p.toe)` |
| 同上 | `uGamut` | `:14130` `glUniform1f(u_film_.gamut, p.gamut)` |
| **公共段（两引擎都吃）** | `uExposure` · `uToneK` · `uSat` · `uLocal*` · `uMono` · `uSplit` · `uLook*` · `uHalDomainMap` · `uDomainRatio` · `uTex` | `[0]` · `[28]`(`:14141`) · `[19..21]` · `[36]` · `[25]` · `[23][24]` · …… |

⚠ **三条链不是一回事**（这是"隐藏开关 ≠ 旁路"的根因）：

| 链 | pass | 随引擎切换？ |
|---|---|---|
| 拍照 | `kFsFilm`（引擎注入段 + 公共段） | ✅ 引擎段随 `[98]` 换 |
| **编辑器** | `kFsStyle`（`passStyle` → `submitDisplay`，`:8634`/`:14592`） | ❌ **不随** |
| 输出 | `kFsOut` | ❌ 不随 |

#### B. 旁路清单（用户 2026-10-14 拍板 ✅）

| 参数 | 拍照链路 | 编辑器链路 | **处置** |
|---|---|---|---|
| `[37]`/`[38]` 黑白点 | 自动失效 | `computeEditorCurve`(`:14703`) **仍生效** | **隐藏 + 旁路** |
| `[5..10]` 肩/趾 latitude | 自动失效 | `computeEditorCurve` **仍生效** | **隐藏 + 旁路** |
| `[40]` gamut · `[42]+` 白点高光感知 | 自动失效（`uGamut` 的声明段与使用段都被引擎 1 的整体替换吞掉 ⇒ location=−1） | 不消费 | **隐藏** |
| **`[35]` auto_tone** | **仍生效** ⚠ **不走 uniform**：`updateFilmicEV` 在 `passFilm` 里**无条件调用**（`:14014`，只有 program 重建在 `if` 里）⇒ 成员变量 `last_still_w999_`（`:13865`，由 auto_on 门控）→ 曝光决策 → 成片 `[0]` | 不消费 | **隐藏 + 旁路**（2026-10-14 卡 A 审计抓出，原判"自动旁路"是错的） |
| **`[28]` 基准影调**（`uToneK`，**公共段**） | **仍生效** | 不消费 | **隐藏 + 旁路**（拍板） |
| **`[20]/[21]` 饱和补偿**（`uSat`，**公共段**） | **仍生效** | **仍生效**（`passStyle` 的透传白名单含 `sat_shadow`/`sat_mid`/`sat_high`；2026-10-14 卡 A 复核，纠正本表原写的"不消费"） | **隐藏 + 旁路**（拍板） |
| `[36]` 局部色调（`uLocal*`，公共段） | 仍生效 | 不消费 | **隐藏 + 旁路**（2026-10-14 二次拍板） |
| `[0]` 曝光（公共段） | 仍生效 | — | **保留**（通用） |
| `[4]` 对比度 | 仅 filmic spline 那一份失效 | **`kFsStyle:14680` 仍生效** | **保留**（编辑器有效）+ `PresetScope` 标注拍照侧作用域 |
| 外观/风格层 `[19]`/`[23..25]`/`[90..93]` | 仍生效 | 仍生效 | **保留**（通用） |

#### B2. 旁路恒等值表（**唯一真源 = `FilmParams.h` 的默认值**，不许在别处抄常数）

| 槽位 | 字段 | uniform | **旁路值** | 依据 |
|---|---|---|---|---|
| `[28]` | `tone_strength` | `uToneK` | **1.0** | ⛔ **绝不能是 0.0**：引擎 1 的 `kFsSigmoidMain`（`FrameRenderer.cpp:6603`）**复用 `uToneK` 作为 sigmoid 整条曲线的应用权重**（`if (uToneK > 0.0) { toned = sigmoidTone(out3); out3 = mix(out3, toned, uToneK); }`，`sigmoidTone` 全文件只此一个调用点）⇒ 置 0 会让**引擎 1 完全空转**（实跑指纹 `0x189e4126`，与"引擎 0 + uToneK=0"逐字节相同）。1.0 = `FilmParams.h:112` 默认值 |
| `[20]` | `sat_shadow` | `uSat` 分区 | **1.0** | `FilmParams.h:93` 默认（"乘性恒等"是**近似**表述：三槽全 1.0 时 `sat = ws+wm+wh`，高光端略 >1 —— 那正是出厂默认行为，也正是旁路要的口径） |
| `[21]` | `sat_mid` | `uSat` 分区 | **1.0** | 同上（`FilmParams.h:94`） |
| `[22]` | `sat_high` | `uSat` 分区 | **1.0** | 同上（`FilmParams.h:95`）。⚠ 卡 C 抓到的 **P1**：**一个「饱和度」滑杆同写三槽**（`writeSaturation`）⇒ 只钉 `[20]/[21]` 会留下"高光档仍跟用户值走"的**半旁路** |
| `[36]` | `local_tone` | `uLocalStrength` | **0.0** | `FrameRenderer.cpp:4096` "0 = 关（A/B 通路）" |
| `[35]` | `auto_tone` | **无 uniform 生效路径** | **0.0** | `FilmParams.h:141` 默认 = 关；生效靠 `:13865` 的 `last_still_w999_` **成员副作用**（→ 曝光决策），不受 uniform location 保护（卡 A 审计） |
| `[5..7]` | `shoulder[3]` | （编辑器曲线） | **0.01** | `computeEditorCurve` 门控（`:13719`） |
| `[8..10]` | `toe[3]` | （编辑器曲线） | **0.01** | 同上 |
| `[37]` | `filmic_black_ev` | （编辑器曲线） | **`FilmParams{}` 默认** | `computeEditorCurve` 门控（`:13722`） |
| `[38]` | `filmic_white_ev` | （编辑器曲线） | **`FilmParams{}` 默认** | 同上 |

#### C. 三个落点

1. **UI 显隐**：`when(engineSlot)` 形态（对照 PC `RawEditPanel.kt:991` 的分段；非当前引擎的控件**不进 UI 树**，不是置灰）。
2. **编辑器链路旁路**（**新增**）：`passStyle` / `computeEditorCurve` 按照片的 `engine_slot` 决定是否启用胶片曲线段。
3. **公共段旁路**（**新增**）：`engine_slot=1` 时按 B2 表下发恒等值。
   ⚠ **落点修正（架构师调研结论）**：三条链的参数**都在 Kotlin 侧组装**（拍照 = `CameraController`
   的 `filmParams[...]`；编辑器 = `EditorExport.kt`，其槽位表 `:2098` 已含 `[98] engine_slot`）
   ⇒ **归零可在 Kotlin 侧完成，C++ 不必改**（改 C++ 反而要多一处漂移面）。

#### D. 验收判据（硬，可自动化）

0. **【新增 · 卡 C 审计后补】引擎 1 必须真的在工作**：`engine_slot=1` 的成片**不得**等于"引擎 0 且 filmic 参数被清零"的结果。实跑指纹：引擎 1 @`uToneK=1.0` = **`0x8fdb4e38`** ≠ 空转指纹 **`0x189e4126`**。
   ⚠ **为什么非有这条不可**：下面六条判据**全部会通过**一个"引擎 1 空转"的实现 —— 判据 2/3 只要求"改 filmic 槽位 ⇒ 成片不变"（空转当然满足），判据 4 走公共段也满足。**没有任何一条能发现引擎 1 什么都没做** —— 这正是 P0 差点整条漏过去的原因。
1. `engine_slot=0`：阶段 2 全部基线不变（`film_d5` `0x589f8364` / 拼接 `0x4fa1ebd3`）；
2. `engine_slot=1` + 改 `[37]`/`[38]`/`[5..10]` ⇒ 成片**逐字节不变**；
3. `engine_slot=1` + 改 `[28]`/`[20]`/`[21]`/**`[22]`**/`[36]`/**`[35]`** ⇒ 成片**逐字节不变**（旁路生效）；
4. `engine_slot=1` + 改 `[0]`/`[19]`/外观层 ⇒ **正常生效**（防误旁路）；
5. 互切 A→B→A ⇒ 成片回到 A 的原值（无串扰）；
6. 编辑器：编辑 `engine_slot=1` 的照片，改 `[37]`/`[5..10]` ⇒ 导出结果不变；
7. **未注册 `engine_slot`**（预设 JSON 手改成 2 之类）⇒ 与 C++ 降级语义一致（**按 0 处理、不旁路**），不得出现"引擎 0 + filmic 参数被清零"的第三种状态。

#### E. 已知边界（阶段 3 不解决；架构师 2026-10-14 定案）

1. **`[20]/[21]/[22]` 是双重用途槽位** —— 既承载「饱和补偿」补丁（`CameraController.applySatCompPref`
   写 `1.0 + sat_comp`），又承载用户「**饱和度**」滑杆（`EditorExport.writeSaturation` **同写三槽**）。
   ⇒ 阶段 3 把三槽一并归零（"饱和补偿旁路"）后，引擎 1 下「饱和度」的**全部槽位失效** ⇒
   UI 侧那条**通用**判据（"涉及的槽全部失效才隐藏"）**自动隐藏它**（用户 2026-10-14 拍板：整体隐藏，
   宁可丢掉 `[22]` 的真能力）。⚠ 该决定**无需产品级例外** —— 卡 C 修掉 P1（`[22]` 入表）之后，
   它就由通用判据自然达成（我一度加过的 `UI_HIDDEN_PARAM_KEYS` 已因此删除）。
   **彻底解法 = 拆槽位**（补丁与用户参数分离），**超出阶段 3 范围**。
2. **UI 显隐的判据必须是"并集"**（卡 B 发现，架构师采纳）：`FILMIC_OWNED_SLOTS`（引擎独占）
   与 `NEUTRALIZE`（公共段被置恒等）是**两类**判据，UI 要的是 `独占 ∪ NEUTRALIZE`；
   且**参数项级**用"**涉及的槽全部失效才隐藏**"—— 否则写 `[20][21][22]` 的「饱和度」会被误藏，
   而 `[22]` 仍生效。实现见 `ParamControls.slotActiveInEngine`。
3. ~~**设置页的引擎真源** = "已保存的预设"~~ ⇒ **2026-10-14 已收口**：设置页加了**三档全局开关**
   （prefs **`engine_override`**：`-1` 跟随预设〔默认〕/ `0` 强制 filmic / `1` 强制 sigmoid，用户拍板），
   3 处 gate（设置页 / 完整参数页 / 一级页）统一走 `effectiveEngineSlotOf(prefs, presetSlot)`
   （内部调契约 `EngineScope.effectiveEngineSlot`）；渲染侧 `CameraController.engineScopedForCapture`
   **每次下发前读同一个键**（无缓存 ⇒ 改档下一次下发即生效）⇒ 两侧同键同哨兵，且有一条源码对拍守着。
   ⚠ **本次改造的要害**：强制档下**必须把 `[98]` 真的改写成 `effective`** —— 只旁路参数不改 `[98]`
   等于"引擎没换、filmic 参数被清零"（正是 E-2 那类第三态）；渲染侧做了变异检验证明这条判据能红。
   ⚠ **仍存的两条边界**：① 键名字符串在渲染侧硬编码、UI 侧为 `ENGINE_OVERRIDE_PREFS_KEY`
   （靠"源码对拍 `CameraController.kt`"的测试守着；收成一份需改渲染侧，收益小于改动面）；
   ② 一级页/完整参数页**不订阅** prefs 变化（每次重组读一次）⇒ 相机页长期不重组时，
   **白点行的显隐可能晚一步更新**（不会显示错误引擎的参数）；**渲染侧永远正确**。
   ⚠ **编辑器链路不参与 override**（按拍摄时的 `[98]`）—— 否则编辑旧照片会因设置页开关突然变样。
4. **`slotActiveInEngine` / `NEUTRALIZE` 目前都是"非 0 即恒等"** ⇒ 加第 3 个引擎时须升级为 per-engine 表
   （`KNOWN_ENGINE_SLOTS` 已是显式集合，`EngineSlotUiTest.engineChoicesMatchTheCppRegistry` 有"C++ 表 ↔ Kotlin 常量 ↔ UI 选项"三方对拍守着）。
5. **判据自身的假绿陷阱**（卡 A 2026-10-14 抓到自己用例里的，写测试必读）：
   **探针取值不得等于恒等值** —— `[20]/[21]/[22]/[28]` 的恒等值**都是 1.0**（`[35]/[36]` 是 0.0）。
   若用例的探针恰好取到恒等值，"旁路生效"与"没生效"会给出**同一个数组** ⇒ **假绿**。
   ⚠ 这个坑是 **`[28]` 从 0.0 改成 1.0 之后才出现的**（改之前 1.0 是"非恒等"，探针取 1.0 合法）——
   **契约演进会翻转既有用例的假绿面**，改契约值之后必须重扫所有探针取值。
   修法（卡 A 的写法，建议照抄）：逐槽守卫断言
   `assertTrue("探针 $v 恰好等于恒等值 ⇒ 本用例对该槽假绿", v != NEUTRALIZE[slot])`。

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

1. **判据口径：已拍板（用户，2026-10-13）** —— 主判据 = 「**可分辨跨度**」。定义**写死**如下
   （可执行、无歧义，任何人复跑应得同值）：
   - **输入口径**：`s = 0.1845 · 2^EV`（中灰 18.45% = 0 EV），EV 网格 −1.0 ~ +9.0、步长 0.05；
   - **输出口径**：线性显示值 → **真 sRGB OETF** → **8bit**（`c ≤ 0.0031308: 12.92c`；否则
     `1.055·c^(1/2.4) − 0.055`）；
   - **可分辨跨度** = 从**白纸亮度**起，每 0.1 EV 的输出差 **≥1 LSB** 能持续多远（单位 EV）；
   - ⚠ **2026-10-14 起引擎 1 默认 = `contrast 1.5`（用户拍板"跟 PC 一致"）⇒ 本「同白纸亮度
     前提」在默认参数下不再满足**：sigmoid 白纸 **218.84 LSB** vs 引擎 0 的 205.13（纯曲线）
     / 214.20（端到端）。判据 ① 因此**不可拿默认档与引擎 0 直接比**；本节下文的 c=1.2 各档
     一律降为**历史对照**（要复现同口径须显式 `--sig-contrast 1.2`，或按上一条查表标定 white%）。
     实测后果（`tools/stage2-engine-diff.py`，同一 ROI）：切引擎差异由"中位 **0.0** LSB"
     变为"中位 **+2.45** / p90 **6.22** / max **12.9** LSB"（该 ROI 无高光；白纸处纯曲线 +15.24）。
   - **同白纸亮度前提（历史口径，c=1.2 时期；必须满足才可比）**：比较两个引擎前，把白纸对齐到同一 LSB。基准 =
     **204.0 LSB**。⚠ 精度与可解性（2026-10-14 实测订正，取代本文早先的"99.4 精确命中"）：
     float32 下 sigmoid c=1.2 的**精确解是 `display_white_target = 99.525`** → 204.0 LSB；
     默认 100 给 **203.601**、99.4 给 **204.567**。**白纸 LSB 对 `display_white_target` 非单调**
     （`film_power` 对 white 是阶梯常数，而 `white_target` 单调抬白，净效果非单调）⇒
     **标定必须查表，不能二分**（阶段 3 预设标定同样适用）。
   - **两个"白纸"口径不可混用**：纯曲线口径（本判据用的）下引擎 0（filmic，真机
     `white_ev=4.0`）白纸 = **205.126 LSB**；而**端到端口径（含 `gamutMapV5`）**下是
     **214.20 LSB** —— gamut mapping 不是恒等变换。**引用"白纸 LSB"必须同时写明是否含 gamut**，
     否则同一引擎会出现 9 LSB 的"矛盾"数字（本项由阶段 2 实测暴露）。
   - **⚠ filmic 复刻的 latitude 口径（2026-10-14 定位）**：复现 16/17 号的 filmic 数值时，
     `latitude` 必须取 `builtin.json` 里 **M9 的真实值**（shoulder `[0.5278, 0.4253, 0.5806]`
     / toe `[0.9219, 0.8216, 0.9488]`），**不是 darktable 默认的 0.01**。用 M9 值可**逐位命中**
     17 号 §1.2 的两行（205.126 vs 205.13；220.450 vs 220.45）；误用 0.01 会差 **5.4 LSB**。
     （17 号原文未写明该口径 ⇒ 任何人复跑都会踩到。）
   - **为什么不用"死白起点"**：17 号 §1.5 已证该判据的阈值恰好落在"每 0.1 EV 正好 1 LSB"这条
     **理想均匀曲线**的等号上（理想曲线会被判"从起点就死白"），且只看斜率上限、不看累积层次
     ⇒ 换阈值即翻转排序（≤1：sigmoid1.2 4.25 > filmic 3.55；≤2：filmic 3.90 > sigmoid1.2 3.10）。
     **若任何文档仍引用"死白起点"，必须同时写明阈值**，否则该数字不可比。
   - **对齐后的基准值**（17 号 §1.6 独立复算，白纸 = 204.0 LSB，**非本仓实测**）：
     filmic **1.65 EV** / sigmoid c=1.2 **1.95 EV** / ACES2 1.90 EV ⇒ 优势 **0.25~0.30 EV**，
     且三者只用掉 8bit 信息论上界的 **32~41%**。
   - **精度口径**：曲线数值一律标 **float32 还是 double**。darktable 实跑是 float32
     （`dt_iop_sigmoid_data_t` 字段与 `commit_params` 局部量皆 `float`）⇒ contrast=1.5 的
     `film_power` = **1.490909**（double 才是 1.5）。两套数值差 ~0.6%，混用会让"逐位对齐"
     类判据假红/假绿。
2. **交付位深：已拍板 10bit HEIF（用户，2026-10-13）**。连带三项：
   ① 引擎输出需 10bit 路径 + HEIF 编码（现为 8bit `JpegEncoder.cpp`）；
   ② 交付色域应与公共工作空间**联动**（见第 3 条）；
   ③ 17 号 §3.5：**10bit 只能救 sigmoid/ACES 的"量化不可逆"，救不了 filmic 的连续不可逆平顶**
   ⇒ "上 10bit"本身就要求换掉 filmic，否则位深收益被 plateau 吞掉。
3. **公共工作空间：已拍板（用户，2026-10-13）—— Rec.2020 线性，但必须单独一期**，不与插槽重构
   同期。质量层面**宽色域更优**（per-channel 影调对饱和色的色相漂移更小、超范围色更晚被截断），
   但**必须配输出端 gamut mapping**，且对"白瓷/釉面死白"这一当前痛点**无直接影响**（那是亮度/
   影调问题，不是色域问题）。选 **Rec.2020 线性**的理由：① PC 的 `DarktableSigmoid`/`AgX`/
   `Filmic` 引擎**即在 BT2020 域**（`RawRenderingEngine.kt`），照做可直接对照其参数与行为；
   ② Rec.2020 是 HEIF 交付的标准宽色域容器。
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
