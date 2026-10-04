# bjzhou/PhotonCamera 厂商渲染引擎取证 + 阿莱（ARRI）风格引擎方案

> 取证对象：`https://github.com/bjzhou/PhotonCamera`，本地副本 `/tmp/photoncamera`，
> `git log -1` = `d584bc9`（2026-09-30，"feat: MGC standard gamma output curve for HDRNet PGTM"）。
> 行号仅对该 commit 有效。本文承接 06 号文档（基础色彩链取证），是其"厂商 look 引擎"部分的扩展。

---

## 0. 结论速览

1. bjzhou fork 共 10 个 RAW 渲染引擎（`RawRenderingEngine.kt:10-81`）。其中"厂商 look"类 **5 个**：
   `Hncs`（哈苏）、`Lumix`（松下）、`Canon`（佳能）、`Fuji`（富士）、`Leica`（徕卡）；
   另有 `AdobeCurve`（DCP 标准）、`AgX`、`Spektrafilm`（光谱胶片）、`DarktableSigmoid/Filmic`（影调科学）。
2. 五个厂商引擎共用**同一个架构模式**：
   **等效相机（Equivalent Camera）+ 从官方软件/固件提取的真实渲染数据**。
   即：先把手机 RAW 变换到目标相机的传感器色彩域（等效相机矩阵），再套用从
   哈苏 Phocus / 松下 LumixLab / 佳能 DPP / 富士固件里逆向或提取出来的真实曲线与 LUT 数据。
3. **阿莱与它们有本质区别：ARRI 的色彩科学是官方公开的**（LogC3/LogC4 白皮书、AWG3/AWG4、
   官方 LUT 包、Look Library、ACES 官方输入变换）。阿莱引擎**不需要逆向**，
   可以用 100% 官方公开数据实现，且仍然完全符合"等效相机 + 真实数据"的模式。

---

## 1. 总体架构：公共管线 + 引擎插槽

所有厂商引擎挂在**同一个 tone pass 插槽**上（`RawEngineTonePass.kt`），顺序：

```text
公共 RAW 解码 / MFNR 堆栈 / 去马赛克 / 降噪 / 白平衡 / 源相机标定
  → 线性 ProPhoto（共享工作空间，测光/PGTM/曝光都在这里）
  → 公共 PGTM（局部色调映射，usePhotonHdr 策略）
  → 公共曝光（含各引擎独立 EV 域设置）
  → uProfileToEngineTransform：ProPhoto → 目标相机白平衡 RGB（等效相机矩阵，逐像素 mat3）
  → applyEngineTone()：各厂商引擎专属渲染（独立 shader）
  → 线性 sRGB（引擎输出契约：所有引擎必须输出线性 sRGB，编码只做一次）
  → 公共编辑（ColorRecipe）/ 单次 sRGB 编码 / 锐化 / HDR / 保存
```

关键设计（`docs/research/canon-rendering-engine-integration.md`）：

- 引擎不接管 RAW 前端、PGTM、公共曝光或 HDR——只接管"profile 域渲染"这一段；
- 等效相机矩阵是**线性 3×3**，可处理负值和 HDR 超范围，不 clamp、不做 LUT 查表
  （旧版用 65³ 标定 LUT + 四面体插值，已删除，"矩阵可直接处理负值和 HDR 超范围值，标量曝光与该线性转换可交换"）；
- 每个引擎有自己的资产校验、渲染计划缓存（render plan cache）、shader 纹理单元分配。

等效相机标定来源（`EquivalentCameraCalibration.kt:101-108`）：

| 目标 | 标定资产 | 来源 |
|---|---|---|
| Lumix S9 | `dcp/Panasonic DC-S9 Adobe Standard.dcp` | Adobe 官方 DCP，只取 ColorMatrix1/2 |
| Hasselblad X2D II 100C | `hncs/x2d_ii_100c_calibration.json` | 从参考 RAW 的 DNG IFD0 ColorMatrix1（tag 0xc621）提取 |
| Canon EOS R5 | `canon/eos_r5/calibration.json` | 从 `Canon EOS R5 Adobe Standard.dcp` 提取，仅 ColorMatrix |
| Leica M9 | `leica/m9/PROCESS/LUTS.bin` | 徕卡固件资源 |

矩阵方向规则（`docs/research/lumix-rendering-engine.md`）：ColorMatrix 是 XYZ→camera，
沿 DNG color-spec 无 ForwardMatrix 路径：先按倒色温插值 ColorMatrix，再算当前白点的
白平衡与色适应，求逆得到 `B_target`（WB 目标 RGB → ProPhoto），渲染时用 `inverse(B_target)`。
白平衡只施加一次（源 RAW 的 gain 不乘进目标矩阵，否则 WB 双重施加）。

---

## 2. 三个引擎逐一拆解

### 2.1 哈苏 HNCS（`HncsProfile.kt` 751 行 + `HncsToneAlgorithm.kt` + `docs/hncs-rendering-engine.md`）

**数据来源（全部逆向自 Phocus 3.7 官方软件）：**

- 2D 色度表：Phocus `Colormaps/*.xml`（26 个中 21 个合规），`CXMLLut::DecryptArray`
  解密公式 `decoded = encrypted × 0.5 + 1.0`；选 `LUTTable100MP3`（CFV 100C / X2D II 100C）；
- FilmCurve：从 `libcrosssdk.so` 运行时导出的 65,536 点 uint16 单调表
  （`CGradationManager` 静态表：Standard=C=type6/companding2，Reproduction=E=type7/companding2）；
- HNCS RGB→XYZ(D50) 矩阵：从 Phocus 二进制 `CRawColorCorrection::GetColorSpaceChangeMatrix`
  恢复（行和 = D50 白点，Y 行即 luma 系数 Kr=0.28804/Kg=0.71188/Kb=0.00009）；
- 条件 Gamma 常量（gamma=2.19921875 等）：恢复但**判定不启用**（Phocus 默认
  correction version=2 不插 CGammaFilter）——"没有真实数据的分支宁可不做"。

**资产格式（`.hncs` 可追溯二进制）：**
`magic HNCSMAP1` + JSON header（源文件 SHA-256、网格 105×89、CbS/CrS/CbE/CrE/DivFactor、
每个表的意图/色温锚点/payload 偏移）+ zlib 压缩 float32 payload。运行时全部重新校验
（magic/schema/manifest/源哈希/payload SHA-256），任一不符 → **拒绝渲染，不回退**。

**渲染链（`hncs-rendering-engine.md` §等效 X2D II 100C 相机色彩路径）：**

```text
手机 RAW → 源 WB+headroom → 源 ColorMatrix → 线性 ProPhoto
  → (PGTM/曝光) → ProPhoto→X2D II 100C WB RGB（等效矩阵）
  → cameraToHncs = inverse(HNCS_RGB→XYZ_D50) × M_Phocus(CCT)   // Phocus 矩阵按 CCT 锚点插值
  → RGB→Ycc（用 HNCS 矩阵第二行推导的 YCbCr）
  → Cb/Y–Cr/Y 二维色度表查询（105×89 网格，DivFactor 缩放，GPU 四点双线性）
     position = DivFactor×[Cb/Y, Cr/Y] − [CbS, CrS]；重建时 Cb'Cr' = mapped×Y/DivFactor
  → gray 阈值权重 + 低照度去饱和权重（Phocus 常量：权重恒 1）
  → Ycc→RGB → FilmCurve（65,536 点 nearest 查表，256×256 纹理）
  → companding 解码 → HNCS→线性 sRGB 矩阵 → 线性 sRGB
```

**色温插值**（`CXMLLut::CalculateLUT` 复刻）：LUT 接受**实测 RAW 白点 CCT**（不猜），
整数 Kelvin，clamp 2000–6200K（v3 profile 到 10000K），在 TS/Flash(/HT) 锚点间线性插值；
矩阵锚点 2400/3050/5550/9100K。xy→CCT 用 Robertson 倒温表。

### 2.2 松下 Lumix S9 PhotoStyle（`LumixProfile.kt` + `LumixToneAlgorithm.kt` + `LumixColorTemperature.kt`）

**数据来源：** LumixLab（松下官方 app）的 `libtechorfilter.so`，`cnvOnlyProcess` 路径。
资产为 PSL1 二进制：88 字节头（magic+kind+dimension+format）+ payload。
16 种 PhotoStyle（Standard/Vivid/Natural/…/L.ClassicNeo/Flat/Cinelike D2/V2/Like709/V-Log/Leica Mono）。

- 主曲线：2048 点 1D LUT（逐通道 shape）；
- low/high 两张 33³ 3D LUT（half/float 双格式），按色温权重混合：
  `highWeight = ((coordinate−256)/256).coerceIn(0,1)`，coordinate 由 Kelvin 分段插值
  （3000K→256, 5000K→512, 6500K→618, 7500K→725，来自 `GetCTempInfoForColorCorrection`）；
- V-Log 有 ISO 相关输出 clip（320→3300 … else→3596，码值域 14bit→12bit）；
- 家族曝光尺度：Standard 家族 → Cinelike −7/6 EV、V-Log −8/3 EV
  （"S9 的原始 STD→目标家族转换，不是拟合的亮度校正"）。

**渲染链：** `clamp(相机RGB×styleGain) → 2048 点曲线 → mix(lowLUT, highLUT, highWeight)
→ clamp(0, outputClip) → floor(×4095)/4095 量化 → sRGB OETF 反解码 → 线性 sRGB`。
3D LUT 显式八角三线性插值（匹配 `cnvOnlyProcess`，含最后一个网格节点）。

### 2.3 富士 Film Simulation（`FujiProfile.kt` + `FujiToneAlgorithm.kt`）

**数据来源：富士相机固件 GXUP0008（X100VI/X-T50 世代）。** 逆向产物 `profiles.json`（format v2）+ `lut-N.bin`：

- `curve40`：40 个 Q9 增益系数（÷512）——亮度响应曲线，**以 gain 形式施加**（保持色度）；
- `tables31`：3×31 点 u14 输出系数（÷16384）——R/G/B 三条分段贝塞尔曲线，
  10 个共享节点间隔（`edges[i+1]=edges[i]+2^(controls[i]+5)`）；
- `coefficients9`：3×3 矩阵（÷1024）；`rgb_to_ycc_coefficients9` + `chroma_coefficients4`：
  推导出 Cr-first 四系数色度算子 `op`，`color = inv(forward) × op × forward`；
- `lut_index`：18³×16bit 固件 LUT，解码为 17³ float 3D LUT（Nostalgic Neg→lut-2, Reala Ace→lut-3）。

7 种胶片模拟：Provia/Velvia/Astia/Classic Chrome/Classic Neg/Nostalgic Neg/Reala Ace
（`FujiFilmSimulation.kt`，firmwareMode/renderer ID 与固件核对）。

**渲染链（`FujiToneAlgorithm.kt:67-77`）：**

```text
luminance = dot(RGB, uFujiLuminance)
gain = curve40(lum)/lum（亮度曲线作为增益，保护色度）
mixed = uFujiMatrix × (RGB × gain)
encoded[r,g,b] = 31 点分段贝塞尔（tables31，通道独立）
encoded = 17³ 3D LUT（uFujiColorMatrix × encoded，显式三线性）
→ sRGB OETF 反解码 → 线性 sRGB
```

### 2.4 佳能 / 徕卡（简）

佳能：EOS R5 固定目标，Picture Style 数据来自 DPP（官方软件）逆向的颜色内核
（RawRecipe 整数内核、真实 MLIB ICC 链、ISO100 配方 ModelID 0x80000421）；
输入缩放：线性白 1.0 → 14bit 码 16383，超白保留后内部限幅。六种风格（0x81–0x86）。
徕卡：M9 固件 PROCESS 资源（矩阵/LUT 消费 WB 相机 RGB）。

### 2.5 Spektrafilm（另一类思路）

`assets/spektrafilm/`：上游 [andreavolpato/spektrafilm](https://github.com/andreavolpato/spektrafilm)
commit `3bb2c2d2`（v0.3.4），**CC BY-SA 4.0**。基于胶片染料**光谱吸收**的物理模拟
（film stock × 相纸两段光谱模型），native 移植生成 65³ LUT（ProPhoto in/out），
支持 20 种胶片（Portra/Ektar/Vision3…）+ 8 种相纸 + 密度增益调参。不走"等效相机"路线。

### 2.6 工程纪律（值得本项目沿用的约束）

1. **全程哈希校验**：资产 SHA-256、源软件库 SHA-256、payload SHA-256，写入 manifest；
2. **严格失败**：白点/CCT 求不出、表缺失、校验不符 → 拒绝渲染。无默认 5000K 回退、无 identity 纹理伪装；
3. **"真实数据约束"**：文档明说"不能用经验曲线替代"、"不绘制 identity pass 与原图数值等价"、
   "加入'自然/人像'会生成不存在的模式，违背真实数据约束"；
4. **域标注显式化**：每步写清在什么色彩域（`RawRenderingEngine.kt` 每个 enum 带 workingColorSpace 注释）；
5. JVM 回归测试覆盖矩阵方向、白平衡往返、HDR 负值、双光源插值等（`RawHncsShadersTest` 等）。

---

## 3. 阿莱风格引擎实现方案

### 3.1 核心判断：不需要逆向，数据全是官方公开的

| 哈苏/松下/佳能/富士 | ARRI |
|---|---|
| 色彩处理数据封闭，需逆向官方软件/固件 | **官方白皮书公开精确数学**：LogC3（ALEXA 世代）、LogC4（ALEXA 35）、AWG3/AWG4 色域 |
| Look 数据藏在 colormap XML / PSL1 / 固件 | **官方免费 LUT 包**：LUT Generator 的 LogC3/LogC4 Package（.cube，LogC→Rec709/P3/HDR）；**Look Library 87 个官方 look**（9 大组：黑白/胶片/对比/时代…，各 3 档强度，log-to-log 3D LUT） |
| 传感器标定靠 DNG ColorMatrix（Adobe DCP 或参考 RAW 提取） | ACES 官方输入变换（ampas/aces-dev CTL、ARRI 官方提供）：LogC3-EI/AWG3→ACES、LogC4/AWG4→ACES；K1S1 经典 look 以"ALEXA Classic 709"名义在官方工具中提供 |

需要面对的缺口：**ARRI 不发布 ALEXA 的 DNG ColorMatrix/DCP**（ACR 不支持 .ari 转换），
所以"等效相机标定"不能用 S9 那样的 Adobe DCP 路线。替代：**官方 ACES ↔ AWG 变换矩阵**
是 ARRI 对 ACES 项目背书的公开数据，用它做色度桥接——等效相机近似发生在
"手机传感器 RGB → AWG"这一步，与哈苏"手机 → ProPhoto → X2D II 域"完全同构。

### 3.2 推荐架构（对齐 bjzhou 引擎插槽，全官方数据）

```text
公共管线 … → 线性 ProPhoto → (PGTM/曝光)
  → uProfileToEngineTransform：ProPhoto → AWG4 白平衡线性 RGB
     （B_arri：官方 AWG4↔ACES↔XYZ(D50) 矩阵链在当前白点的实例化）
  → ArriToneAlgorithm.applyEngineTone()：
     ① LogC4 编码（官方白皮书分段公式：线性小信号段 + log10 对数段，EI1000 基准；
        精确常数以白皮书/aces-dev CTL 为准，不从记忆/推测手写）
     ② ARRI Look Library 3D LUT（log-to-log，按 look×强度选择；LogC4 域）
     ③ 官方显示变换 LUT（LogC4/AWG4 → Rec709，来自官方 LUT 包；或 LogC3 路线的 K1S1）
     ④ 显示域 → sRGB OETF 反解码 → 输出线性 sRGB（引擎契约）
  → 公共编辑/编码/锐化/保存
```

两条机型路线（需拍板）：

- **路线 A：ALEXA 35（LogC4/AWG4 + REVEAL）** —— 工具链最新最全（LogC4 白皮书、
  官方 LogC4 LUT 包、Look Library LogC4 版），推荐为默认；
- **路线 B：经典 ALEXA（LogC3/AWG3 + K1S1）** —— "阿莱胶片感"的社区记忆锚点
  （K1S1/ALEXA Classic 709），官方 LUT Generator 直接产出 .cube。可作为第二个 look。

LogC3 版需要 EI 参数化（LogC3 曲线依赖 EI），固定 EI800（社区最常用）即可起步。

### 3.3 资产与实现组件清单

新增资产（`assets/arri/`，仿 HNCS 的 manifest+哈希结构）：

1. `manifest.json`：LUT 文件名、SHA-256、来源包名、look 元数据（组/强度/机型）；
2. `lut_*.cube`：官方 LUT（Look Library look LUT + 显示变换 LUT，或合成后的单张）；
3. LogC4 曲线：**用公式程序化生成 65536 点表**（与 HNCS FilmCurve 同样的 uint16+SHA256 纹理格式），
   白皮书常数进生成脚本并写入文档（不同于哈苏必须逆向表——这里公式即原始数据）。

新增代码（对应 PC 的文件角色）：

| 组件 | 对应 PC 文件 | 内容 |
|---|---|---|
| `EquivalentCameraTarget.ArriAlexa35` | `EquivalentCameraCalibration.kt:101` | AWG4 标定：官方矩阵 JSON（来源/SHA 记录） |
| `ArriProfile` + `ArriRenderPlan` | `HncsProfile.kt` | 资产加载/校验、LUT 选择、渲染计划缓存 |
| `ArriToneAlgorithm` | `HncsToneAlgorithm.kt` | shader：LogC4 编码 + 3D LUT 采样 + 反解码 |
| 引擎枚举 `Arri` | `RawRenderingEngine.kt` | shaderId 顺延；workingColorSpace=AWG4（或 SRGB 输出契约标注）；Linear EV 域 |
| JVM 测试 | `RawHncsShadersTest.kt` | LogC4 往返、AWG4 白点矩阵、LUT 域校验 |

### 3.4 落地到本项目（filmkit）

本项目与 PC 架构不同（native C++/GLSL `libfilmengine`，Kotlin 壳），但能力已具备：

- 已有标准色彩链（CCM/影调/gamut，`StdColorMatrix.kt` + 引擎内 pass）；
- 已有 3D LUT 加载与应用（`app/src/main/java/com/filmkit/app/lut/CubeLut.kt`、
  `FrameRenderer.setLut()`）；
- 分阶段落地建议：
  1. **离线验证**（Python/PC 端）：手机样张 RAW → 当前链到 AWG4/LogC4 → 官方 LUT → Rec709，
     与官方工具（ARRI Reference Tool / Resolve 用同一 LUT）渲染结果对 ΔE——先证域链正确；
  2. **引擎内挂 LUT**：把官方 LogC4 显示 LUT + look LUT 以现有 `setLut` 通道接入，
     当前链末端增加"LogC4 域编码→LUT→反解码" pass；
  3. **独立引擎化**（对齐 PC 模式）：等效 AWG4 矩阵替代直通、LogC4 生成表、
     look 选择 UI、资产校验——此时才有资格叫"阿莱渲染引擎"。

### 3.5 必须拍板的决策点

1. **目标机型/look 基准**：ALEXA 35+LogC4（推荐）还是经典 ALEXA+K1S1，或两者；
2. **官方 LUT 分发方式**：直接打进 APK（需先读官方 LUT 包的 EULA——Look Library 对
   ALEXA 35 世代免费、对 Mini/AMIRA 曾是付费授权，LUT 文件再分发条款未确认）vs
   **用户自行从 ARRI 官网下载后导入**（合规稳妥，且本项目已有 .cube 导入能力）；
3. **look 范围**：先上 1 个基准 look（K1S1 或 REVEAL 默认）还是接 Look Library 87 个。

### 3.6 风险与边界（沿用 PC 文档的诚实口径）

- 等效相机极限：PC 文档自述"这仍是跨传感器的色度近似，不等同于对源传感器重新实测标定
  或恢复光谱响应"。手机 → ALEXA 35（S14 双增益架构）同理；风格还原主要来自 look/影调层，
  传感器色度差异次之，但不能宣称"等于真 ALEXA"；
- **LUT 输入域必须严格对上**（LogC4 编码 + AWG4 线性域、含 EI 语义）。域错配会整体毁掉 look
  ——这正是 PC 在每个引擎文档里反复强调域标注的原因，也是我们 NN 链路踩过的同类坑（域错配 2px 能量爆 10×）；
- 路线不匹配的偷懒做法（如把官方 Rec709 输出 LUT 挂在我们当前显示域输出上）**不做**——
  违反"严禁简化关键质量管线"与"真实数据约束"。

### 3.7 实现第一步行动项

1. 下载 ARRI 官方包并清点（LUT Generator 的 LogC4/LogC3 Package、Look Library 包），
   逐文件记录 SHA-256 与 EULA 条款；
2. 从 aces-dev / ARRI 白皮书提取 LogC4 公式常数与 AWG4↔ACES↔XYZ 矩阵，Python 离线复算 LUT 对照官方 .cube（逐点验证 <1e-6）；
3. 用本项目样张 DNG 走离线链出图，与 Resolve+同一 LUT 对照；
4. 再进入引擎实现。

---

## 附：关键文件索引（/tmp/photoncamera @ d584bc9）

| 文件 | 内容 |
|---|---|
| `app/.../raw/RawRenderingEngine.kt` | 10 引擎枚举、工作空间、EV 域 |
| `app/.../raw/EquivalentCameraCalibration.kt` | 等效相机框架与 4 个目标 |
| `app/.../raw/HncsProfile.kt` / `HncsToneAlgorithm.kt` / `docs/hncs-rendering-engine.md` | 哈苏全套 |
| `app/.../raw/LumixProfile.kt` / `LumixToneAlgorithm.kt` / `LumixColorTemperature.kt` | 松下全套 |
| `app/.../raw/FujiProfile.kt` / `FujiToneAlgorithm.kt` / `FujiFilmSimulation.kt` | 富士全套 |
| `app/.../raw/SpectralFilmProfile.kt` + `app/src/main/cpp/spektrafilm/` | 光谱胶片 |
| `docs/research/lumix-rendering-engine.md`、`canon-rendering-engine-integration.md`、`lumixlab-raw-rendering-analysis.md` | 逆向取证自述 |
| `app/src/main/assets/{hncs,lumix/s9,fuji/gxup0008,spektrafilm}/` | 全部厂商数据资产 |
