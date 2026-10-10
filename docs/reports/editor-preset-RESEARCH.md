# FilmKit 专业预设（Preset）调研报告

> 调研日期：2026-10-10 ｜ 调研子代理交付 ｜ 唯一产出文件 = 本报告
> 任务口径：用户明确要求「胶片预设怎么做要充分调研，一定要实现专业的预设」；
> 架构师上一版简化规格（ADR-19：只存 `FilmParams` 96 槽 + LUT id，prefs JSON）**已被用户否掉**，
> 本报告**不沿用**该规格，一切结论从证据出发。
> 用户追加指示：**"这方面 PC 做得一般"** ⇒ PC 降为参考（只记它做了什么 + 判断它一般在哪），
> 重心移到 Lightroom / Capture One / darktable / RawTherapee / VSCO / RNI 的专业做法。

---

## 0. 摘要（先看这 12 条）

| # | 结论 | 关键证据 |
|---|---|---|
| 1 | **PC 根本没有"预设"功能**。全仓 `grep -i preset` 只有 2 处命中，且都是 Camera2 的 `TONEMAP_MODE_PRESET_CURVE` / ISO 分段注释，与用户预设无关。它只有"全局设置备份/恢复" | `/tmp/pc_probe`（PhotonCamera 0.97，**Java 项目**，0 个 `.kt`）|
| 2 | 专业软件的预设**不是"全参数快照"**，而是**字段级子集**：Lightroom 保存时让用户逐项勾选（Check All / Check None），RawTherapee 的 `.pp3` **只写被编辑过的字段** | Adobe 官方 apply-presets；`procparams.cc:268 saveToKeyfile(!pedited \|\| edited.enabled, …)` |
| 3 | 预设**必须能引用资源**：darktable 的 style_item 存 `op_params` **+ `blendop_params`**；RawTherapee 的 `FilmSimulationParams = {enabled, clutFilename, strength}` | `database.c:235-239`；`rtengine/procparams.h:1336-1345` |
| 4 | **"强度 / Amount" 有两条互斥的技术路线**，业界**两条都在用**：<br>**(a) 参数域缩放**（Lightroom）、**(b) 输出混合**（darktable / RawTherapee / Capture One） | Lightroom Queen（150% of exposure value）；`blendif_rgb_hsl.c:381 out = a*(1-mask)+b*mask`；`clutstore.cc:228 out = lerp(原像素, CLUT结果, strength)` |
| 5 | 选择依据：**参数是"有语义的命名键值" ⇒ 参数缩放可行**（Lightroom）；**参数是不透明二进制 blob ⇒ 只能输出混合**（darktable 的 `op_params` 是 blob，无法安全逐字段插值） | `presets.c: _preset_encode(stmt,0)` 把 op_params 当 blob；Lightroom `.xmp` 是命名键值 |
| 6 | **我们的参数是命名槽位（96 槽、每槽有语义与恒等值）⇒ 与 Lightroom 同构 ⇒ 强度可走参数域缩放，零额外渲染成本**（第 5 节的逐项分析） | `core/engine/src/film/FilmParams.h:29-440` |
| 7 | 预设的**版本兼容**是专业软件的硬需求，标准解法是**版本号 + 逐版本迁移函数**：darktable 有 v1→v14 的完整迁移链 + DB schema `ALTER TABLE` 演进 + "全零 blob 视为未初始化" | `src/develop/blend.c: dt_develop_blend_legacy_params`（12 个历史 struct）；`database.c:279-283` |
| 8 | 预设列表的**预览缩略图用"用户当前这张照片"**，不用标准测试图：Lightroom 官方"hover 预览作用在你的照片上"；Capture One 官方"预览作用于 primary variant 与 browser 缩略图" | Adobe helpx；support.captureone.com Applying a Style |
| 9 | **darktable 有"整图预设"（styles）**，结构 = `styles(id,name,description)` + `style_items(styleid, module, operation, op_params, enabled, blendop_params, …)` —— 这才是我们"预设"的对标物（模块 preset 只是单模块参数） | `database.c:230-242` |
| 10 | 胶片类 App 的"型号 + 强度"有两种打包法：**预烘焙变体**（VSCO Film 的 `(-)` / `(+)` / `(++)` 三档）与**运行时强度滑杆**（我们的 LUT 强度 `[34]` 已是后者） | vsco.co preset-packs 官方页 |
| 11 | 用户否掉 ADR-19 的核心问题不是"存什么字段"，而是**它没有"预设该存什么"的取舍机制**：全 96 槽硬覆盖 + 无强度 + 无分组 + 无版本号 + 无缩略图 = 不专业 | 对比第 3 节各家做法 |
| 12 | **推荐方案 = B（稀疏字段集 + 类别勾选 + 资源引用 + 版本号）**，强度走"参数域缩放（主）+ LUT 强度缩放（资源域）"，存储 prefs JSON 起步但结构按可导出文件设计 | 第 5 节 |

---

## 1. 调研方法与证据口径

- **联网可用**：`web_search` 工具在本会话**不可用**（缺 `DEEPSEEK_API_KEY`），改用 `web_fetch` + DuckDuckGo HTML 端点检索，`bash` 内 `curl` 直连（已实测可用）做精准取证。
- **PC 源码**：`/tmp/pc` 是空壳（checkout 被清空、`.git` 不完整），改用 **`/tmp/pc_probe`**（PhotonCamera `versionName '0.97'`，完整）。
- **darktable 源码**：本机 `/tmp/dt` 的 checkout 已被清空（`.git/objects/pack` 为空、无 `HEAD`，`git show` 报 `not a git repository`）⇒ 改为**联网抓 master 源码**逐段核对，本报告引用的行号均为 `raw.githubusercontent.com/darktable-org/darktable/master/...` 的当前行号。
- **RawTherapee 源码**：`dev` 分支（默认分支）联网抓取。
- **诚实性约定**：查不到的一律写"**没查到**"，不做推测性陈述；推测性判断标注"（推断）"并给出推断依据。

---

## 2. ① PC（PhotonCamera）：它做了什么，以及它"一般"在哪

### 2.1 事实：PC 没有预设功能

```bash
# /tmp/pc_probe（PhotonCamera 0.97）
$ find . -name "*.kt" | wc -l
0                       # ← 是 Java 项目，不是 Kotlin（与 ADR-14 里"读 PC 源码"的印象不同）
$ grep -rn "preset" --include=*.java -i . | head
.../processing/parameters/IsoExpoSelector.java:42:  // changes smoothly with light level instead of jumping between presets.
.../api/Camera2ApiAutoFix.java:34: import static android.hardware.camera2.CameraMetadata.TONEMAP_MODE_PRESET_CURVE;
```

**两处命中都与"用户预设"无关**：一处是 ISO 分段注释，一处是 Camera2 的 tonemap 模式常量。
⇒ **PC 没有"保存/套用风格预设"这个功能**，UI 里也没有预设入口。

### 2.2 PC 有的是：全局设置 + 备份/恢复 + 参数迁移

| 机制 | 文件 | 做法 |
|---|---|---|
| 设置存储 | `app/src/main/java/com/particlesdevs/photoncamera/settings/SettingsManager.java` | `SharedPreferences`（`PreferenceManager.getDefaultSharedPreferences`） |
| **备份 / 恢复** | `settings/BackupRestoreUtil.java` | 导出**全部** prefs 为 **JSON**（Gson `setPrettyPrinting`）：`exportData.put("main_preferences", filteredMainPrefs)`、per-lens 段、tunable 段 |
| 参数迁移 | `settings/MigrationManager.java` | 版本升级时迁移旧键 |
| 恢复默认 | `ui/settings/custompreferences/{BackupPreferences,RestorePreference,ResetPreferences}.java` | 设置页里的三个按钮 |

`BackupRestoreUtil.java` 片段（导出的是"整包设置"，不是风格）：

```java
Map<String, Object> exportData = new HashMap<>();
SharedPreferences mainPrefs = PreferenceManager.getDefaultSharedPreferences(context);
Map<String, ?> mainPrefsMap = mainPrefs.getAll();
...
exportData.put("main_preferences", filteredMainPrefs);
```

### 2.3 判断：PC 为什么"一般"（反面教材清单）

1. **粒度错**：它是"**整包设置**备份/恢复"（含镜头、降噪、tunable 等设备相关参数），不是"**风格**"。
   风格预设要求"可跨照片复用、只含与画面风格相关的量"—— PC 这个模型做不到。
2. **没有 UI 概念**：没有命名、没有分组、没有列表、没有缩略图、没有强度。用户无法"看一眼就知道这个预设长什么样"。
3. **没有部分套用**：备份是 all-or-nothing，不能"只套色彩不套降噪"。
4. **没有版本契约**：JSON 是 prefs 的直出，键随代码改名而失效，靠 `MigrationManager` 手工兜。

⇒ **不能学 PC 的"整包 JSON 备份"**；要学的是第 3 节各家的"字段级子集 + 元数据 + 版本"。

---

## 3. ② 业界专业做法（主攻）

### 3.1 Adobe Lightroom（Classic + Mobile）

#### (a) 预设包含什么：**用户勾选的设置子集**

Adobe 官方《Apply Presets》原文：

> "Select **Check All** to select everything or select **Check None** to deselect everything, and then select to select each of the settings to include in the preset."

⇒ 预设 = **从当前照片的全部显影设置里挑一个子集**，不是"固定全量"。
证据：<https://helpx.adobe.com/lightroom-classic/desktop/process-and-develop-photos/apply-presets.html>

同一页还给了这些专业能力（都是 ADR-19 缺的）：

| 能力 | 官方原文（节选） |
|---|---|
| 分组 | "type a name, specify the **group** in which the preset should appear" |
| 显示/隐藏分组 | "**Manage Presets** option, you can show or hide various Develop preset groups" |
| 更新（覆盖） | "Right-click a preset … **Update With Current Settings**" |
| 导出 | "**Export**… **Export Group**"（内置预设不可导出，只可导出自定义/第三方） |
| 导入 | "select the preset files or a **.zip** file containing presets"；`.lrtemplate` **不能**放进 zip 导入 |
| 名称过滤 | "You can **filter** Presets and Preset groups based on name" |
| 重复名处理 | Replace / Duplicate / Rename 三选项 |
| 收藏 | Favorite 组（导出 Favorite 组时只导出自定义/第三方） |
| **ISO 自适应预设** | "apply this preset to an image with ISO 800, the Luminance Noise Reduction will be set to **5**"（ISO400→0、ISO1600→10 之间**线性插值**） |
| 部分兼容预设 | 不兼容当前照片的预设"shown as **faded and in italic**" |

**ISO 自适应预设是本报告最重要的一条旁证**：Adobe 明确按 ISO 在参数值之间插值（0 → 5 → 10），
这就是**参数域插值**存在的官方证据。

#### (b) 存储格式：`.xmp` sidecar，命名键值 + 元数据块

真实预设文件（GitHub `9bic/lrpresets/9bichrome.xmp`，HTTP 200，5468 字节）头部：

```xml
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.6-c140 …">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
   crs:PresetType="Normal"
   crs:Cluster=""
   crs:UUID="2C755543116267A9A10A8ECF7B344C10"
   crs:SupportsAmount="False"
   crs:SupportsColor="True"
   crs:SupportsMonochrome="True"
   crs:SupportsHighDynamicRange="True"
   crs:SupportsNormalDynamicRange="True"
   crs:SupportsSceneReferred="True"
   crs:SupportsOutputReferred="True"
   crs:CameraModelRestriction=""
   crs:Copyright=""
   crs:ContactInfo=""
   crs:Version="10.3"
   crs:ProcessVersion="10.0"
   ...
```

该文件共 **134 个 `crs:` 字段**，含：`WhiteBalance/Temperature/Tint/Exposure/Contrast/Highlights/Shadows/Whites/Blacks/Texture/Clarity/Dehaze/Vibrance/Saturation`、`Sharpen*`、`LuminanceSmoothing`、`ColorNoiseReduction`、`VignetteAmount`（暗角）、`ShadowTint/RedHue/RedSaturation/...`（相机校准）、以及 **`crs:ToneCurvePV2012` 的 `rdf:Seq` 点列**（曲线）、尾部 `crs:Look crs:Name=""`。

另一份更"轻"的真实预设（`elchinhuseynovv/Lightroom_xmp/70s_Vintage_Look.xmp`，1993 字节）证明**预设会打包"胶片质感"三件套**：

```xml
crs:CameraProfile="Adobe Standard"
crs:GrainAmount="30"      crs:GrainSize="35"     crs:GrainFrequency="50"     ← 颗粒
crs:PostCropVignetteAmount="-15"  crs:VignetteMidpoint="50"                  ← 裁剪后暗角
crs:SplitToningHighlightHue="40"  crs:SplitToningShadowSaturation="30"       ← 分离色调
crs:ToneCurvePV2012="0,0 32,28 64,58 128,128 192,200 255,255"                ← 主曲线
crs:ToneCurveRed/Green/Blue="…"                                              ← 三通道曲线
crs:ProcessVersion="11.0"                                                    ← 参数契约版本
```

**该文件里没有的东西同样重要**：没有裁剪/几何（`crs:CropTop` 等）、没有局部调整（`crs:MaskGroupBasedCorrections`）、没有修复画笔。
⇒ **专业预设的默认边界 = "显影 + 质感"，几何与局部不在内**（与 ADR-19 的裁剪判定一致，但局部调整的处理见 §5.1 D7）。

#### (c) ★ Amount（强度）：**参数域缩放**，且**逐预设声明是否支持**

Adobe 官方原文：

> "Adjust the intensity of the applied preset using the **Preset Amount slider**."
> "When you create or update a preset, you can choose to enable the **Amount** slider. In the **New Develop Preset** or **Update Develop Preset** dialog box, select **Support Amount Slider**. If this option is deselected, the **Amount** slider will be greyed out for that preset."
> "**If you are unable to select the Support Amount Slider option, that indicates the selected settings don't support the Amount slider.**"

⇒ 三条硬结论：
1. Amount 是**预设文件的一个能力标记**（`crs:SupportsAmount="True"/"False"`），不是全局开关。
2. **并非所有设置都支持按比例**——如果它是"输出混合"，那所有设置天然都支持；"某些设置不支持"恰恰说明它是**逐参数缩放**（几何、离散枚举这类无法按比例缩放的项会拒绝）。
3. 取值范围 0–100%，且实测可超过 100%（第三方：0–200%，见下）。

**"参数缩放"的直接证据**（Lightroom Queen 论坛，Victoria Bampton 社区，权威第三方）：

> "The amount slider sets the total effect to **150% of that**. If the **Exposure slider would change to -5.10 (150% of 3.40)**, then that would be confusing because it would look like the preset sets the slider to -5.10 and the amount slider increases that to 150% of -5.10."

⇒ 明确按**参数值**成比例（3.40 × 150% = 5.10）。
URL: <https://www.lightroomqueen.com/community/threads/amount-slider-question.48168/>

第三方对范围与语义的描述：
- "It controls the strength of a preset — from **0% (no effect) to 100% (full preset)** — letting you blend presets at any intensity **between the original photo and the full look**." — <https://theeditingstudio.co/blog/lightroom-preset-amount-slider>
- "**0% to 200%**" — <https://www.erikaswafford.com/blog/lightroom-preset-amount-slider-customize-your-presets-to-your-style>
- "works like an **opacity control** for presets and tools you use in Lightroom, **even those that include multiple masks** in the Masking Panel" — <https://archipelagopresets.com/archipelago-field-guide/lightroom-preset-profile-amount-sliders>
  （注意：这条把 Amount 类比成 opacity，但它同时说"含多个 mask 的预设也支持"——若真是像素级混合，则与"某些设置不支持"矛盾。**两条第三方说法冲突，官方未明确公式**；本报告采信"参数缩放"，因为 Adobe 自己的 ISO 自适应预设就是参数插值，且 `SupportsAmount` 的存在只能用参数域解释。）

`crs:SupportsAmount` 的实际用法（第三方转换指南，用于把"预设"改成"配置文件(Look)"）：

> "crs:PresetType="Normal" → crs:PresetType="Look"；**crs:SupportsAmount="False" → crs:SupportsAmount="True"**；crs:CameraProfile="Camera Standard" → crs:CameraProfile="Adobe Standard""
> — <https://www.reddit.com/r/Lightroom/comments/kgiefm/convert_presets_to_profiles_the_guide/>

#### (d) 预览缩略图：**在用户自己的照片上**

官方原文："Hover over the preset, **to preview the effects of a preset on your image**."
（同页图注即 Presets 面板展开在用户照片旁）
⇒ Lightroom 的预设预览/缩略图基准 = **当前选中的用户照片**，不是标准测试图。

#### (e) 版本兼容

- `crs:ProcessVersion="11.0"`：显影引擎版本（2012 版 vs 更早），旧预设带旧版本号，引擎按该版本语义解释参数。
- `crs:Version="10.3"/"13.0"`：写出该文件的 Camera Raw 版本。
- 兼容性由 `SupportsColor / SupportsMonochrome / SupportsHighDynamicRange / SupportsNormalDynamicRange / SupportsSceneReferred / SupportsOutputReferred / CameraModelRestriction` 一组**能力标记**表达 ⇒ 不兼容的预设**仍显示**（灰色斜体），不是静默丢弃。

### 3.2 Capture One：Style / Preset / Style Brush

官方《Applying a Style》原文（<https://support.captureone.com/hc/en-us/articles/360002611857-Applying-a-Style>，经 r.jina.ai 取正文）：

| 主题 | 官方原文 |
|---|---|
| Style 是什么 | "Capture One Styles **do not dynamically alter adjustments** based on the image they were applied to. Each style comprises **a group of fixed settings or values**." |
| 预览 | "You can **preview the effect of the Style in the Viewer by hovering** the cursor over the named Style in the list. … after a short delay, the effect will be previewed **on the primary variant** or all selected variants and **corresponding thumbnails in the browser**." |
| **强度** | "Capture One Pro 23 … you can now **add multiple Layers to your image through Styles and adjust the opacity of each Layer** for the best look. Only version 23 supports Layers in Styles." |
| 局限 | "**Due to technical limitations, specific tools cannot be applied to a layer or saved as part of a layered Style.** Instead, they will be applied directly to the background." |
| 组织 | "**Styles and Presets** tool"、"**Built-in-Styles** folder (or **Custom Styles** folder)"、已套用的 Style 列在工具顶部 |
| 局部化 | **Style Brushes**（Capture One 21 引入，`support.captureone.com/…/360017633157-Style-Brushes`）——把样式当笔刷局部刷 |
| 安装包 | `.costylepack` 打包导入（第三方教程：<https://help.luxelens.com/hc/en-us/articles/38063237163543>) |

**"强度 = 图层不透明度"** ⇒ Capture One 走的是**输出混合**语义（Layer opacity 在像素域）。
`.costyle` 的**官方格式说明：没查到**（官方只讲导入导出操作；第三方存在格式转换器仓库 `EyWuddup/lightroom_xmp-to-captureone_costyle`，说明是可解析的结构化文件，但本报告未深挖其字段）。

### 3.3 darktable：**两种预设**，且强度是**输出混合**

darktable 里有两个不同层级的东西，做预设前必须先分清：

| 层级 | 表 | 含义 |
|---|---|---|
| **模块预设**（preset） | `data.presets` | **单个模块**的参数存档（例如"曝光 +0.5 的我的常用值"） |
| **整图预设**（**style**） | `styles` + `style_items` | **一整组模块参数**（= 我们说的"预设/风格"） |

#### (a) 整图预设 style 的表结构

```c
// src/common/database.c:230-242
"CREATE TABLE IF NOT EXISTS main.styles (id INTEGER, name VARCHAR, description VARCHAR)"
"CREATE TABLE IF NOT EXISTS main.style_items (styleid INTEGER, num INTEGER, module "
"INTEGER, operation VARCHAR(256), op_params BLOB, "
"enabled INTEGER, blendop_params BLOB, blendop_version INTEGER, multi_priority "
"INTEGER, multi_name VARCHAR(256))"
```

⇒ **每个 item = 一个模块的 `op_params` + `blendop_params`**；`styles` 表只有 `name/description`，
**没有缩略图字段**（推断：darktable 的预设/样式列表是文本菜单，不做图像预览——见 `dt_insert_preset_in_menu_hierarchy` 构建 `GMenu` 的代码）。

#### (b) 模块预设的完整字段（`src/common/presets.c`）

`dt_presets_save_to_file()` 把一行 preset 写成 `.dtpreset` **XML**（`<darktable_preset version="1.0">`）：

```c
xmlTextWriterWriteFormatElement(writer, BAD_CAST "name", "%s", name);
xmlTextWriterWriteFormatElement(writer, BAD_CAST "operation", "%s", operation);
xmlTextWriterWriteFormatElement(writer, BAD_CAST "op_params", "%s", _preset_encode(stmt, 0));
xmlTextWriterWriteFormatElement(writer, BAD_CAST "op_version", "%d", op_version);
xmlTextWriterWriteFormatElement(writer, BAD_CAST "autoapply", "%d", autoapply);
xmlTextWriterWriteFormatElement(writer, BAD_CAST "model"/"maker"/"lens", …);
xmlTextWriterWriteFormatElement(writer, BAD_CAST "iso_min"/"iso_max", …);
xmlTextWriterWriteFormatElement(writer, BAD_CAST "blendop_params", "%s", _preset_encode(stmt, 1));
xmlTextWriterWriteFormatElement(writer, BAD_CAST "blendop_version", "%d", blendop_version);
xmlTextWriterWriteFormatElement(writer, BAD_CAST "multi_priority"/"multi_name", …);
xmlTextWriterWriteFormatElement(writer, BAD_CAST "filter", "%d", filter);
```

关键点：
- `op_params` / `blendop_params` 是**二进制 blob**，经 `dt_exif_xmp_encode()` 编码成文本（`_preset_encode`）。
  ⇒ **参数不是命名键值，是不透明结构体** —— 这直接决定了 darktable 的强度只能做输出混合（§3.3d）。
- **`autoapply` + `filter`**：按相机/镜头/ISO/光圈/焦距**自动套用**（`dt_presets_get_filter()` 用 `FOR_RAW/FOR_LDR/FOR_MATRIX/FOR_HDR/FOR_NOT_MONO/FOR_NOT_COLOR` 位标记生成匹配表达式）。
- 预设名用 `|` 分层，`dt_insert_preset_in_menu_hierarchy()` 把 `"A|B|C"` 变成**子菜单层级** ⇒ darktable 的"分组"= 名称前缀。
- `writeprotect` 字段区分内置（不可删）与用户预设（`dt_get_active_preset_name` 按 `ORDER BY writeprotect ASC` 优先返回用户预设）。

#### (c) ★ 强度：**输出混合**（铁证）

每个 darktable 模块都自带 blend（不透明度/蒙版）参数，`blendop_params` 里 `opacity` 默认 `100.0f`：

```c
// src/develop/blend.c:41-46（默认 blend 参数：opacity = 100.0f）
static dt_develop_blend_params_t _default_blendop_params
    = { DEVELOP_MASK_DISABLED, DEVELOP_BLEND_CS_NONE, DEVELOP_BLEND_NORMAL2,
        0.0f, 100.0f, … };
// src/develop/blend.c:619
const float opacity = CLIP(d->opacity / 100.0f);
// src/develop/blend.c:637  ← 全局 opacity 被当作"常数 mask"
dt_iop_image_fill(mask, opacity, owidth, oheight, 1); // mask[k] = value;
// src/develop/blend.c:815
// now apply blending with per-pixel opacity value as defined in mask
```

混合公式（`src/develop/blends/blendif_rgb_hsl.c:378-383`，`a` = 模块输入、`b` = 模块输出）：

```c
const float local_opacity = mask[i];
out[j + k] = clamp_simd(a[j + k] * (1.0f - local_opacity) + b[j + k] * local_opacity);
```

⇒ `out = lerp(原像素, 处理结果, opacity)` —— **像素域输出混合**，不是参数缩放。
OpenCL 路径同构（`blend.c:1396` 注释 "apply blending with per-pixel opacity value as defined in dev_mask"）。

**darktable 有没有"套用预设时按百分比"？** —— **没查到**（`dt_presets_*` 没有 amount 参数）。
它的等价替代是：套用预设后**手动**给该模块设 blend opacity。这条对我们是重要提示：
**"强度"在专业工具里常常就是"把整组效果按不透明度混合"**，而不是必须做"参数插值"。

#### (d) ★ 版本兼容：教科书级三件套

1. **版本字段参与匹配**：
   ```c
   // src/common/presets.c: dt_get_active_preset_name()
   "SELECT name, op_params, blendop_params, enabled, writeprotect FROM data.presets"
   " WHERE operation=?1 AND op_version=?2 ORDER BY writeprotect ASC, LOWER(name), rowid"
   ```
   并且判断"当前参数是否等于某预设"时用 `MIN(op_params_size, module->params_size)` **只比较公共前缀**
   ⇒ 旧预设（blob 短）与新版参数（blob 长）**能兼容比较**。
2. **逐版本迁移链**（`src/develop/blend.c: dt_develop_blend_legacy_params()`）：
   `old_version == 1 … 13` 各有独立的 `dt_develop_blend_paramsN_t` struct 定义 + 转换代码，
   每引入一个字段就 bump 版本、保留旧 struct、写一段升级逻辑（本文件里 12 段）。
   其中还有**"全零 blob = 未初始化"**的特判：
   ```c
   // these occurred in previous darktable versions when modules without blend support
   // stored zero-initialized data in history stack. … remedy: we simply initialize with
   // the current default blend params in this case.
   if(_develop_blend_params_is_all_zero(old_params, length)) { … }
   ```
3. **DB schema 渐进迁移**（`src/common/database.c:279-283, 539-558`）：
   `ALTER TABLE main.presets ADD COLUMN op_version / blendop_params / blendop_version / …`，
   以及 `main.presets → data.presets` 的表搬迁 + 唯一索引 `presets_idx (name, operation, op_version)`。

### 3.4 RawTherapee：`.pp3` 稀疏字段 + ParamsEdited + HaldCLUT strength

#### (a) `.pp3` 是纯文本、且**只写被编辑过的字段**

真实内置 profile（`rtdata/profiles/Standard Film Curve - ISO Low.pp3`，472 字节，全文）：

```ini
[Exposure]
Auto=false
HistogramMatching=false
CurveMode=FilmLike
Curve=1;0;0;0.11;0.089999999999999997;0.32000000000000001;…

[HLRecovery]
Enabled=true
Method=Coloropp

[LensProfile]
LcMode=lfauto
UseDistortion=true
UseVignette=true
UseCA=false

[Color Management]
ToneCurve=false
ApplyLookTable=true
…
```

整个文件只有 5 个段 ⇒ **未列出的段一律用默认值**（`rtengine/procparams.cc` 的 `load`）。
写入侧由 `ParamsEdited` 位掩码控制（`procparams.cc:268` 等 **200+ 处**同一模式）：

```cpp
saveToKeyfile(!pedited || edited.enabled, group, TOOL_ENABLED, params.enabled, keyFile);
saveToKeyfile(!pedited || edited.framingMethod, group, FRAMING_METHOD, …);
saveToKeyfile(!pedited || edited.aspectRatio, group, ASPECT_RATIO, …);
```

读取侧对称（`procparams.cc:66-81`）：

```cpp
bool assignRgbFromKeyfile(…, bool& params_edited_value) {
    if (keyfile.has_key(group_name, key)) {
        getFromKeyfile(keyfile, group_name, key, value);
        params_edited_value = true;      // ← 只有真的读到才标记"已编辑"
        return true;
    }
    return false;                        // ← 缺失 = 保留默认值 + 不标记已编辑
}
```

⇒ **缺失字段 = 默认值 + "该项不属于本预设"**。这是"部分套用"的干净实现：
粘贴时只覆盖 `params_edited == true` 的项，其余保持目标照片的原值。
UI 侧对应 `rtgui/windows/partialpastedlg.cc`（**Paste partial** 对话框）。
`[Version]` 段（`procparams.cc:3815-3819` 读 `AppVersion` / `Version`）承载版本信息。

#### (b) ★ 胶片模拟 = **LUT 文件 + 强度**，强度是**输出混合**

参数结构（`rtengine/procparams.h:1336-1345`）：

```cpp
struct FilmSimulationParams {
    bool enabled;
    Glib::ustring clutFilename;   // ← 资源引用（HaldCLUT PNG）
    int strength;                 // ← 强度（0..100）
};
```

应用与混合（`rtengine/improcfun.cc:2222-2249` + `rtengine/clutstore.cc:228-230`，SSE 路径同义见 `:268`）：

```cpp
// improcfun.cc
if (params->filmSimulation.enabled && !params->filmSimulation.clutFilename.empty()) {
    hald_clut = CLUTStore::getInstance().getClut(params->filmSimulation.clutFilename);
    …
}
const float film_simulation_strength = static_cast<float>(params->filmSimulation.strength) / 100.0f;
…
hald_clut->getRGB(film_simulation_strength, std::min(TS, tW - jstart), clutr, clutg, clutb, out_rgbx);

// clutstore.cc:228-230（三线性插值得到 CLUT 结果后，与原始像素插值）
out_rgbx[0] = intp<float>(strength, out_rgbx[0], *r);
out_rgbx[1] = intp<float>(strength, out_rgbx[1], *g);
out_rgbx[2] = intp<float>(strength, out_rgbx[2], *b);
// clutstore.cc:268（SSE 路径，等价）
STVF(*out_rgbx, vintpf(v_strength, v_out, v_in));
```

⇒ `out = lerp(原像素, CLUT(原像素), strength)` —— **输出混合**，且**这正是我们 `[34] lut_strength` 的同类做法**
（我们引擎里 LUT 已经有强度混合，见 §5.6）。

补充：RawTherapee 的 Film Simulation 走 **HaldCLUT PNG**（8/16/64 级），官方合集 `HaldCLUT.zip` **402 MB**
（<https://rawtherapee.com/shared/>，索引页实测列出该文件大小）—— 资源体积是"LUT 作为预设资产"必须面对的成本，
我们的做法（内置 42 个二进制 `.lut` 共 4.3 MB，见 `LutLibrary.kt` KDoc）在这个维度上明显更优。

### 3.5 五家横向对比

| 维度 | Lightroom | Capture One | darktable | RawTherapee | PC（参考） |
|---|---|---|---|---|---|
| 预设粒度 | 勾选式字段子集 | Style = 固定设置组 | **模块 preset + 整图 style** | 稀疏 `.pp3` 段 | 无 |
| 参数编码 | **命名键值**（`crs:*`） | 结构化文件（`.costyle`，格式未公开） | **二进制 blob**（`op_params`） | 命名键值（INI） | prefs 键值 |
| 资源引用 | `crs:Look` / `CameraProfile` | 内置 | `blendop_params` | **`clutFilename`** | 无 |
| **强度** | **参数缩放**（`SupportsAmount` + 0–200%） | **图层 opacity（输出混合）** | 模块 blend opacity（**输出混合**） | LUT strength（**输出混合**） | 无 |
| 局部调整入预设 | 可（含 mask，第三方称 Amount 也支持） | Style Brush 局部化 | 随 item 的 blendop | 靠 partial paste | 无 |
| 分组 | Group + Manage + Favorite + 名称过滤 | 文件夹（Built-in/Custom） | 名称 `\|` 分层 | profiles 目录 | 无 |
| 缩略图 | **用户照片**（hover 预览） | **primary variant + browser 缩略图** | 无（文本菜单） | 无（列表） | 无 |
| 导入导出 | 单 `.xmp` / zip / `.lrtemplate` 旧格式 | `.costylepack` | `.dtpreset` XML | `.pp3` 文本 | 整包 JSON |
| 版本兼容 | `ProcessVersion` + `Supports*` 能力标记 + 灰色斜体展示 | 版本升级后旧 style 行为：**没查到官方说明** | `op_version` 匹配 + v1→v14 迁移链 + schema ALTER + 全零特判 | `[Version]` + 缺失字段默认值 | `MigrationManager` |
| 覆盖/更新 | Update With Current Settings | 覆盖保存 | 同名 INSERT OR REPLACE | 另存 | 无 |
| 重复名 | Replace/Duplicate/Rename | — | 唯一索引 `(name, operation, op_version)` | — | — |

---

## 4. ③ 胶片模拟类 App 的特色

### 4.1 胶片预设打包哪些东西

| 来源 | 打包内容 |
|---|---|
| **VSCO Film**（官方 preset packs 页） | "cover the most trusted professional and consumer **film stocks** … from Kodak Portra and Fuji Pro to Agfa Vista and Ilford Delta 3200, **with (-), (+), and (++) variations**" ⇒ **型号 × 强度变体**（预烘焙三档） |
| **VSCO** 技术描述（第三方技术文） | "engineered to replicate the specific characteristics of each film stock — its **grain structure, highlight rolloff, shadow behavior, and color channel response**" ⇒ 颗粒 + 高光滚降 + 阴影 + 通道响应 |
| **RNI Films**（官方站） | "**film presets**, **film profiles** and photo editing tools derive from real film emulsions"；产品同时供 Lightroom / ACR / **Capture One** / Mobile ⇒ profile（相机校准）+ preset 双层 |
| 现代胶片插件（第三方综述） | "models film characteristics including **halation, bloom, and grain structure, not just color**" |
| **Lightroom 真实胶片预设文件**（§3.1b 实测） | 颗粒（`GrainAmount/Size/Frequency`）+ 暗角（`PostCropVignette*`）+ 分离色调 + 主/三通道曲线 + 相机配置文件 |
| **RawTherapee** | 纯 LUT（HaldCLUT）+ strength，**不含**颗粒/暗角（那些是独立模块，靠 `.pp3` 的稀疏段一起带） |

⇒ **胶片预设的"专业感" = 型号（LUT/profile）+ 强度 + 颗粒 + 暗角 + 光晕 + 曲线/色温**。
我们的 `FilmParams` 已经**全部具备**（LUT `[34]`、颗粒 `[26][27][60][61]`、暗角 `[66..68]`、
halation `[62..65]`、影调/色温 `[88..94]`、层间效应 `[69..77]`）—— **缺的只是"把它们打包成预设"**。

### 4.2 "胶片型号 + 强度"的组合模型

两种业界形态：

| 形态 | 代表 | 优点 | 缺点 |
|---|---|---|---|
| **预烘焙变体**（型号 × N 档） | VSCO Film `(-) / (/) / (+) / (++)` | 实现最简单（就是多份预设）；每档可人工精修 | 数量爆炸（型号 × 档位）；无法连续调 |
| **运行时强度**（型号 + 一个滑杆） | 我们的 `[34] lut_strength`、RawTherapee `strength`、Capture One 图层 opacity | 连续可调；预设数量少 | 需要引擎支持混合（我们**已有**） |

⇒ **我们应当走"运行时强度"**（引擎已支持，见 §5.6），**并把 VSCO 的"变体"作为可选的产品形态**
（例如同一 LUT 预置"淡/标准/浓"三个预设条目 = 三个 `amount` 不同的预设），二者不冲突。

### 4.3 Filmix 的 UI 记录（本仓库既有，非本次新证据）

用户提供的 Filmix 实机截图已固化在 `docs/reports/editor-ADR.md`：

- **ADR-14**：编辑器顶栏 = `取消 ｜ 撤销/重做 ｜ 保存预设 + 完成`；底部导航 = `调整 / 预设 / 滤镜 / 裁剪 / 相框 / 转化`
  ⇒ **"保存预设"在顶栏、"预设"在底部导航**，与我们现有布局的留位一致。
- **ADR-15**：查看页底栏 = `编辑 / 胶片 / 分享 / 信息 / 删除`，其中「胶片」= 预设/风格入口。

**⚠ 与代码的事实核对（本次勘察发现）**：
`EditorScreen.kt` 的 `EditorTopBar`（`:2174-2230`）当前只有 `取消 / 相册 / 撤销 / 重做 / 覆盖 / 完成` ——
**顶栏没有"保存预设"按钮**（ADR-19 说"顶栏「保存预设」现置灰"与代码不符）。
底栏「预设」的置灰留位确实存在（`EditorScreen.kt:3036-3050`，`contentDescription = "预设（暂未开放）"`）。
⇒ 实现卡需要**先决定顶栏是否真的加"保存预设"入口**（Filmix 有；我们顶栏右侧已被"覆盖/完成"占满，
可考虑放进预设面板里，或占用「完成」左侧的一个图标位）。

---

## 5. ④ 我们的方案

### 5.1 设计判断（每条挂证据）

**D1｜预设 = 字段级子集，不是"全 96 槽快照"。**
证据：Lightroom 官方"Check All / Check None / 逐项勾选"（§3.1a）；RawTherapee `saveToKeyfile(!pedited || edited.x)`（§3.4a）。
理由：全量覆盖会**清掉用户当前调整**，而专业语义是"叠加/替换所选类别"。
补充证据（本仓库）：`FilmParams` 96 槽里 `[39..59]` 是**拍摄/解码链路**参数（`sharpen / gamut / white_hl / hr_raw / rcd_cdfix / defringe / moire / hl_grecon / ca_corr / chroma_gate / df_gate / pc3d / rcd_g_orig / hl_rb_recon / color_smooth / rcd_p3_split`），
它们是"相机/RAW 解码"属性而非"风格"（`FilmParams.h:119-360`）⇒ **默认不进预设**。

**D2｜预设必须带资源引用（LUT）。**
证据：RawTherapee `clutFilename` + `strength`（`procparams.h:1336-1345`）；darktable 每 item 带 `blendop_params`。
本仓库对应：`lutId`（内置 = 文件名去扩展名；用户 = `u_` 前缀，见 `LutLibrary.kt:39-45`）+ `params[34]`（强度）。

**D3｜强度做，但分两层：参数域缩放（主）+ 资源域缩放（LUT/颗粒）。**
理由与逐项分析见 §5.6。

**D4｜存储先用 prefs JSON，但结构与"可导出文件"一致（预留 `.fkpreset`）。**
证据：Lightroom/RawTherapee 都是"文件即预设"（可分享）；darktable 是 DB + 导出 `.dtpreset`。
我们的 `LutLibrary` 已经在用 prefs 存元数据（`lut_cats` / `lut_cat_<id>`，`LutLibrary.kt:47-48,208-233`），
沿用同一风格可零新增依赖；但**字段设计必须能直接序列化成文件**（不依赖 prefs 的键命名）。

**D5｜列表预览用"用户当前这张照片"实时渲染，不预烘焙缩略图。**
证据：Lightroom 官方"preview … on your image"；Capture One 官方"previewed on the primary variant … and corresponding thumbnails"。
成本理由：96×96 PNG ≈ 8–15 KB/张，100 个预设 = 1 MB+，且**预烘焙图无法反映用户照片的色调**（对比失真）。
实现上我们已有现成能力：`LutPreview.kt`（LUT 预览）+ 引擎 `requestFull/submitDisplay` 可做小图渲染。

**D6｜版本兼容：预设带 `schemaVersion` + 槽位语义变更时的迁移表。**
证据：darktable `op_version` + v1→v14 迁移链 + schema `ALTER TABLE`（§3.3d）；Lightroom `ProcessVersion` + `Supports*` 能力标记（§3.1e）。
本仓库的真实教训：`FilmParams.h:37-44` 记录 `[5..10]` 从"高光软肩/阴影 toe 0..1"**重定义为**"filmic latitude（默认 0.01）"——
**同一个槽位、同一批数值、语义完全不同**。旧预设里的 `0` 在新语义下是"极端 S 曲线"而不是"无效果"。
⇒ 预设必须记 `schemaVersion`，并在槽位语义变更时提供**逐槽迁移**（darktable 的 `legacy_params` 就是这个问题的标准答案）。

**D7｜局部调整默认不进预设（可选包含），裁剪绝不进预设。**
证据：Lightroom 真实预设文件里**没有** crop / mask 字段（§3.1b 实测）；Adobe 官方称几何类设置不支持 Amount（§3.1c）。
但 Lightroom 12+ 支持把 masking 存进预设（第三方证据），Capture One 用 Style Brush 做局部。
⇒ 建议：**v1 不做局部调整入预设**；局部是"构图相关"的（位置虽归一化，但语义依赖画面内容）。
裁剪：本仓库 ADR-16 已把裁剪放在独立通道（不进 `FilmParams`），天然隔离 ✅。

**D8｜"套用预设"必须是一步可撤销的操作，并走渲染（非直通）。**
证据（本仓库）：`EditorHistory.record()` 在**操作落地后**记一步、`sameAs` 判无变化不入栈（`EditorExport.kt:663-720`）；
`applyLut(..., record = false)` 用于"撤销/重做回放时不要重复入栈"（`EditorScreen.kt:581-600`）。
套用预设 = `recordHistory()` 一次（复用 E2-21「套用原片风格」的既有模式）。

**D9｜预设的"专业感"来自可管理性，不只是能存能套。**
证据：五家全部有分组/重命名/覆盖/导入导出（§3.5 表）；Lightroom 还有 Favorite、名称过滤、重复名三选项、Manage Presets 显隐。

**D10｜不学 darktable 的"二进制 blob 参数"路线。**
理由：blob 导致 (i) 参数无法逐字段插值（只能输出混合）、(ii) 跨版本必须写逐版本 struct 迁移、(iii) 人不可读、不可 diff、不可手改。
我们的参数是**命名槽位数组**（`FilmParams.h` 每个槽都有名字/语义/恒等值注释）⇒ 走 Lightroom 路线（命名键值 + 参数缩放）。

### 5.2 数据模型（三方案对比）

#### 方案 A：全量 96 槽 + LUT id（= 被否掉的 ADR-19）
```json
{"name":"Portra 400","lutId":"kodak_portra","params":[96 个 double],"createdAt":...}
```
- 代价：低（复用 `EditorSnapshot` 结构，直接 `params.copyOf()`）
- 问题：① 硬覆盖全部槽（含拍摄解码槽 `[39..59]`，套用会把别人的解码参数带过来）；② 无强度；③ 无版本号；④ 无分组；⑤ 无法表达"这个预设只改色彩"
- **已被用户否掉，不推荐**

#### 方案 B（★ 推荐）：稀疏字段集 + 类别 + 资源引用 + 版本
```jsonc
{
  "schema": 1,                     // 预设格式版本（D6）
  "id": "p_1728543600000_ab12",    // 稳定 id（重命名不改）
  "name": "Portra 400 暖调",
  "group": "胶片",                  // 分组（可空 = 未分组）
  "favorite": false,
  "createdAt": 1728543600000,
  "updatedAt": 1728543600000,
  "source": "user",                // user | builtin | imported（内置只读）
  "slots": {                       // ★ 只存"与恒等值不同"的槽（稀疏）
    "0": 0.35, "4": 1.12, "34": 0.8, "90": -0.2, "94": 0.15
  },
  "categories": ["tone","color","film"],   // 便于 UI 展示/勾选（D1）
  "lut": { "id": "kodak_portra", "strength": 0.8 },  // 资源引用（D2），可空
  "amount": 1.0,                   // 保存时的强度（可选，见 §5.6）
  "thumbRef": null                 // 不存图（D5）；留字段位以便未来"锁定封面"
}
```
- **优点**：① 稀疏 = 体积小、语义清楚（"这个预设只管这几项"）；② `categories` 让 UI 能做 Lightroom 式勾选；③ 缺字段 = 不套用该槽（RawTherapee 语义）；④ `schema` 解决 D6
- **代价**：需要"中性值表"（`CameraPresets.neutral()` 已有）+ 逐槽判断"是否等于中性值"（浮点比较用现有 `paramsUnchanged` 的容差口径）

#### 方案 C：DB（Room）+ 文件导出
- 优点：查询/排序/大数据量（1000+ 预设）友好；darktable 就是这么做的
- 代价：引入 Room 依赖 + 迁移负担；我们**当前没有 DB**（全 app 用 prefs），且预设量级（几十~几百）不需要 SQL
- **不推荐做 v1**；等"社区预设导入 1000+"真出现再迁移（届时方案 B 的 JSON 可直接搬进 DB，`slots` 就是一行 blob）

**推荐：方案 B**。它同时满足"专业语义（稀疏子集 + 分组 + 版本）"与"零新依赖"。

### 5.3 存储：位置与体积估算

| 项 | 估算 | 说明 |
|---|---|---|
| 稀疏预设 JSON | **≈ 300–700 B/个** | 典型 20–30 个槽 × `"90":-0.2,` ≈ 11 B + meta ≈ 200 B |
| 100 个预设 | **≈ 30–70 KB** | SharedPreferences（XML）完全无压力 |
| 对比：全量 96 槽 | ≈ 1.3 KB/个 ⇒ 100 个 ≈ 130 KB | 也能跑，但白付 3–4 倍体积与"误带解码参数"的语义风险 |
| 缩略图（**不存**） | 0 | 若真要存：96² PNG ≈ 8–15 KB/张 ⇒ 100 个 = 1–1.5 MB（必须落文件，不能进 prefs） |

**存储建议**：
- v1：`prefs` 单键 JSON 数组（键名 `presets_v1`），与 `LutLibrary` 的 prefs 用法一致；
- 用户 LUT 资产已在 `filesDir/luts/`（`LutLibrary.kt:56`）⇒ 预设只引用 id，**不复制资产**；
- 导出文件（E3-3）用 `.fkpreset`（UTF-8 JSON，单文件 = 单预设；多预设打包 zip，与 Lightroom 一致）。

### 5.4 UI

| 位置 | 内容 | 依据 |
|---|---|---|
| **底栏「预设」**（现置灰）| 打开预设面板（主入口） | Filmix 截图（ADR-14 底部导航含「预设」）；代码留位 `EditorScreen.kt:3036-3050` |
| **预设面板** | 顶部：分组切换（全部/收藏/自定义分组）+ 搜索；主体：网格/列表，**每项 = 当前照片实时渲染的小图 + 名称**；底部：`+ 保存当前` / `导入` / `管理` | Lightroom Presets 面板 + Capture One hover 预览（§3.1d/3.2） |
| **保存流程** | 命名 + 选分组 + **勾选包含类别**（基础影调 / 色彩 / 胶片质感 / 局部）+ 是否包含 LUT | Lightroom「New Develop Preset」对话框（§3.1a） |
| **套用后** | 面板顶部出现 **强度滑杆**（默认 100%）+ 「撤销」可用 | Lightroom Preset Amount（§3.1c）；Capture One 图层 opacity |
| **单项长按/更多** | 重命名 / 复制 / 收藏 / 更新为当前设置 / 导出 / 删除（内置只读） | Lightroom 右键菜单（Update With Current Settings / Export / Delete） |
| **查看页「胶片」**（现置灰） | 打开同一份列表；套用后进编辑器 | ADR-15 |
| **顶栏「保存预设」** | ⚠ 需产品决策：Filmix 有，但当前 `EditorTopBar` 无此位（§4.3 事实核对）。建议 v1 放进预设面板内，避免挤占顶栏 | — |

### 5.5 与已有机制的关系（全部可复用，不重写）

| 已有机制 | 复用方式 | 证据 |
|---|---|---|
| `params: DoubleArray(96)` | 预设的 `slots` 直接写进它（按 idx 覆盖），其余槽保持不动 ⇒ **天然的"部分套用"** | `EditorScreen.kt:351` |
| `applyLut(entry, k, record, onReady)` | 套用预设里的 LUT：`LutLibrary.byId(context, id)` → `applyLut(entry, strength, record = false)`；**加载失败按现有语义回退"无 LUT"** | `EditorScreen.kt:581-620`；`LutLibrary.kt` KDoc「失败返回 null 并记日志，调用侧回退「无 LUT」」 |
| `applySnapshotParams` + `applyLut`（E2-21「套用原片风格」）| **套用预设 = 同一件事**（写一组槽 + 换 LUT），直接复用其调用序列 | `EditorScreen.kt:654, 665` |
| `EditorHistory.record()` / `applyHistorySnapshot` | 套用预设 = 落地后 `recordHistory()` 一次；**强度滑杆拖动松手 = 一步**（与滑杆同口径） | `EditorExport.kt:663-720`；`EditorScreen.kt:837-845` |
| 直通判定 `paramsUnchanged` + `crop.isFull` | 套用后参数变化 ⇒ 自动走渲染（无需新逻辑） | `EditorScreen.kt:973` |
| 裁剪独立通道（ADR-16） | 预设不含裁剪 ⇒ 套用预设**不会动裁剪**，零冲突 | ADR-16；`EditorSnapshot(params, crop, lutId)` |
| `LutLibrary` 的分类/重命名 prefs 机制 | 预设的分组可照抄同一套（`presets_groups` + `preset_group_<id>`），保持一致的用户心智 | `LutLibrary.kt:208-233` |
| `LutPreview.kt` | 预设列表的"当前照片小图"渲染可复用同一条小图渲染路径 | `app/src/main/java/com/filmkit/app/lut/LutPreview.kt` |

### 5.6 ★ "强度"要不要做？—— 要，技术路径如下

**结论：做，且用"参数域缩放"为主，不需要第二次渲染。**
理由链（全部有证据）：

1. **业界两种语义都存在**，且**由"参数编码方式"决定**：
   - Lightroom 参数是**命名键值** ⇒ 参数缩放（`SupportsAmount`、ISO 自适应插值、150% of 3.40 = 5.10）
   - darktable 参数是**二进制 blob** ⇒ 只能输出混合（`out = a*(1-mask)+b*mask`）
   - RawTherapee 的 LUT 强度同样是输出混合（`lerp(原像素, CLUT, strength)`），但它的**普通参数**走的是稀疏字段 + 默认值（不存在"参数缩放"需求）
2. **我们的参数是命名槽位（96 槽，每槽有明确语义与恒等值）⇒ 与 Lightroom 同构 ⇒ 参数缩放可行**。
3. **我们的引擎里，"强度"本来就大量以参数形式存在**：
   - LUT：`[34] lut_strength`（引擎内已做混合，`lut_strength = 1.0` 为恒等）
   - 颗粒：`[26] grain_amount`（0 = 整段旁路）
   - halation：`[62] halation_strength`（0 = 关）
   - 暗角：`[66] vignette_strength`（0 = 关）
   - 局部：`[86] local_ev` / `[87] local_sat`
   ⇒ 把这些值按 amount 缩放，**等价于强度**，且**零额外 GPU pass**。

**缩放公式（关键：相对"恒等值"，不是相对 0）**：

```
p_new[i] = neutral[i] + (p_preset[i] - neutral[i]) * amount
```

**为什么不能用 `lerp(当前值, 预设值, amount)`**：本仓库大量槽位的恒等值不是 0
（`wb_r = 1.0`、`contrast = 1.0`、`sat_* = 1.0`、`lut_strength = 1.0`、`shoulder/toe = 0.01`、
`cross` 是单位矩阵、`interlayer_* = 1.0`、`filmic_black_ev = -7.65`）——
用当前值做端点会让"amount=0"退化成"保持当前值"而不是"不生效"，且对矩阵类参数无意义。
证据：`FilmParams.h:29-440` 的恒等值注释 + `CameraPresets.neutral()`。

**逐类参数的缩放语义（实现卡必须逐项定，不能一刀切）**：

| 类别 | 槽位 | 缩放语义 | 备注 |
|---|---|---|---|
| 线性增益类 | `[0] exposure_ev`、`[1..3] wb_*`、`[20..22] sat_*`、`[78] clarity`、`[90..94]` | ✅ 相对恒等值线性缩放 | 曝光建议按 EV 缩放（`ev * amount`） |
| 乘性系数 | `[4] contrast` | ✅ `1 + (c-1)*amount` | |
| 强度型（0=关） | `[26][62][66][86][87]` | ✅ `v * amount`（0 恒等） | 最自然 |
| **LUT** | `[34] lut_strength` | ✅ `v * amount`（**资源域混合**，引擎已支持） | 若 amount=0 ⇒ 视为"不套用该 LUT" |
| 矩阵类 | `[11..19] cross`、`[69..77] interlayer_*` | ⚠️ 对"偏离单位阵的量"缩放：`M_new = I + (M-I)*amount` | 需实现卡专门处理 |
| 曲线/形状类 | `[5..10] latitude`、`[37][38] filmic black/white` | ⚠️ 语义非线性（latitude 0.01 是极端值） | **建议 v1 不参与缩放**（amount < 1 时按原值套用或跳过） |
| 色温/色调 | `[88] temp_mired`、`[89] tint` | ✅ `v * amount`（mired 是线性偏移量） | 与 UI 的 K 换算见 ADR-14 |
| **离散枚举** | `[79] local_type`（0/1/2） | ❌ 不可缩放 | amount < 阈值 ⇒ 整体不套用局部 |
| 几何/裁剪 | crop（不在 FilmParams） | ❌ 不参与 | 预设本就不含 |

**要写进实现卡的硬约束**：
- 强度**必须可撤销为一步**（松手记一步，与滑杆同口径）；
- 强度变化后**必须走渲染**（`paramsUnchanged` 为假）；
- 强度**不改变预设数据**（只在套用时计算），除非用户显式"另存为新预设"（VSCO 的 `(-)/(+)/(++)` 变体即此模式的固化）。

**不推荐的做法**：为了强度做"原图与预设结果 mix"的第二个渲染 pass。
理由：我们的引擎是单 pass 链（`kFsStyle` 内），加第二 pass 需要"渲染两次 + 混合"，
成本翻倍且会与 LUT/颗粒的既有混合叠加两次；而参数缩放已经能覆盖全部风格参数（上表）。

### 5.7 版本兼容策略（可直接落地）

1. **预设文件带 `schema` 整数**（v1 起）。
2. **加载时按 `schema` 迁移**：`migrate(json, from, to)` —— 逐版本函数（darktable `legacy_params` 模式）。
3. **未知槽位忽略、缺失槽位不套用**（RawTherapee 语义：缺失 = 保持目标当前值）。
4. **槽位语义重定义必须写迁移**：本仓库已有先例（`[5..10]` 高光肩/toe → filmic latitude，`FilmParams.h:37-44`），
   以及 `[26][27]` 颗粒语义修正（`FilmParams.h:58-66`）。**每次契约语义变更都要在预设迁移表里加一条**，
   否则旧预设在新引擎下会产生"看起来没坏但观感完全不同"的静默错误。
5. **能力标记**（Lightroom `Supports*` 模式）：预设可声明 `requires: ["lut:xxx", "schema>=2"]`；
   缺资源时**仍然显示**（灰色/斜体 + 提示），不静默丢弃（Lightroom 官方行为）。

### 5.8 分卡建议

| 卡号 | 内容 | 依赖 | 说明 |
|---|---|---|---|
| **E3-1a** | 预设 **schema + 持久化（prefs JSON `presets_v1`）+ 保存（命名/分组）+ 套用 + 删除 + 撤销一步** | 无 | 纯数据层 + 最小 UI；**验收 = 单测钉住 schema 序列化/迁移/稀疏套用语义** |
| **E3-1b** | **预设面板 UI**（底栏「预设」激活）：列表 + 分组 + 当前照片缩略图 + 空态 + 长按菜单（重命名/复制/删除/更新为当前） | E3-1a | 复用 `LutPreview` 的小图渲染路径 |
| **E3-1c** | **强度（amount）滑杆**：参数域缩放（§5.6 表），逐类语义实现 + 单测（amount=0/0.5/1.0 的逐槽期望） | E3-1a | **本报告新增的关键卡**；不做它就没有"专业预设"的核心手感 |
| **E3-2** | **导入 / 导出**（`.fkpreset` 单文件 + zip 多预设）+ schema 迁移 + 资源缺失降级 | E3-1a | 对齐 Lightroom 的导入导出能力 |
| **E3-3** | 查看页「胶片」入口（现置灰）→ 同一列表 → 套用后进编辑器 | E3-1b | ADR-15 |
| **E3-4** | 内置预设包（基于已有 42 个 LUT，做"型号 + 淡/标准/浓"三档 = VSCO 变体模式） | E3-1c | 产品增值；**不需要新引擎能力** |
| **E3-5**（可选） | 收藏 / 排序 / 搜索 / 分类管理（对齐 Lightroom Manage Presets + Favorite） | E3-1b | 预设多了才需要 |
| **E3-6**（可选） | 拍摄页套用预设（胶卷仓条） | E3-1c | ADR-19 原计划的 E3-3 |
| **不做（v1）** | 局部调整入预设、预烘焙缩略图、DB/Room、云同步/社区分享 | — | 见 §5.1 D5/D7 与 §5.2 方案 C |

**串行约束**：E3-1a 必须**独占**先做（schema 是所有后续卡的地基）；E3-1b/E3-1c/E3-2 都改 `EditorScreen.kt` 与预设面板文件 ⇒ 需串行或严格分区。

### 5.9 风险 / 未决 / 没查到的

**风险**
1. **槽位语义漂移**（最高风险）：`FilmParams` 在 E4 还会改语义（`[5..10]` 已重定义、`[26][27]` 已修正、E4-3~E4-6 还会接线颗粒/halation/层间效应）。
   ⇒ 预设迁移表必须与契约变更**同卡维护**（建议写进验收门禁）。
2. **浮点"是否等于恒等值"的判定**：稀疏存储依赖这个判定，容差口径必须与 `paramsUnchanged` 一致，否则会存进一堆"看起来是恒等"的槽。
3. **用户 LUT 被删**：预设引用 `u_xxx` 而用户删了 LUT ⇒ 必须降级为"无 LUT + 提示"，不能崩（`LutLibrary.byId` 已返回 null，语义现成）。
4. **强度对离散/曲线参数的语义争议**：v1 建议对 `[5..10]` 等非线性参数**不缩放**（宁可保守），由用户实测反馈再定。

**没查到的（诚实标注）**
- **Lightroom Amount 的官方实现公式**：Adobe 官方只给了行为描述与 `SupportsAmount` 开关，**没有**公布"参数插值 vs 像素混合"。
  本报告采信"参数缩放"，依据是：① Adobe 自己的 ISO 自适应预设按参数值插值；② `SupportsAmount` 的存在只能由参数域解释；③ Lightroom Queen 的 150% 参数算例。**这一条仍属"高置信推断"，不是官方直证。**
- **Capture One `.costyle` 的文件格式**：官方无格式文档（只有导入/导出操作说明）；本报告未深挖。
- **Capture One 版本升级后旧 Style 的兼容行为**：官方文档里**没查到**说明。
- **darktable 预设是否有缩略图**：源码里**没有**缩略图字段（`presets`/`style_items` 表 + `.dtpreset` 字段清单为证），推断其列表是纯文本菜单。
- **VSCO / RNI / Filmix 的内部存储格式**：闭源，**没查到**（VSCO 官方只讲型号与变体、RNI 只讲 profile/preset 双层；Filmix 仅凭用户截图）。
- **Lightroom 移动版预设的存储实现**：**没查到**（第三方提到"preset thumbnail"，说明移动版有预设缩略图，但无官方技术说明）。

---

## 6. 附录：证据索引

### 6.1 源码（本地 / 联网抓取）

| 证据 | 位置 |
|---|---|
| PC 无预设功能（Java 项目，2 处无关命中） | `/tmp/pc_probe`（PhotonCamera 0.97）；`processing/parameters/IsoExpoSelector.java:42`、`api/Camera2ApiAutoFix.java:34` |
| PC 整包设置备份（JSON） | `/tmp/pc_probe/app/src/main/java/com/particlesdevs/photoncamera/settings/BackupRestoreUtil.java` |
| darktable style / style_items 表结构 | `src/common/database.c:230-242`（master） |
| darktable 预设导出 `.dtpreset` XML 字段 | `src/common/presets.c: dt_presets_save_to_file()` |
| darktable 预设按 op_version 匹配 + 公共前缀比较 | `src/common/presets.c: dt_get_active_preset_name()` |
| darktable 预设按相机/镜头/ISO 自动套用 | `src/common/presets.c: dt_presets_get_filter()` |
| darktable 分组 = 名称 `\|` 分层 | `src/common/presets.c: dt_insert_preset_in_menu_hierarchy()` |
| darktable blend 默认 opacity=100 | `src/develop/blend.c:41-46, 619, 637, 815` |
| **darktable 输出混合公式** | `src/develop/blends/blendif_rgb_hsl.c:378-383`：`out = a*(1-mask) + b*mask` |
| darktable 版本迁移链 v1→v14 | `src/develop/blend.c: dt_develop_blend_legacy_params()` |
| darktable schema 渐进迁移 | `src/common/database.c:279-283, 539-558` |
| RawTherapee 胶片模拟参数（clut + strength） | `rtengine/procparams.h:1336-1345` |
| **RawTherapee strength 输出混合** | `rtengine/clutstore.cc:228-230, 268`（`intp(strength, clut结果, 原像素)`）；调用点 `rtengine/improcfun.cc:2249, 3209` |
| RawTherapee 稀疏写入（ParamsEdited） | `rtengine/procparams.cc:257-286`（`saveToKeyfile(!pedited \|\| edited.x, …)`，200+ 处） |
| RawTherapee 缺失字段 = 默认值 | `rtengine/procparams.cc:66-81`（`assignRgbFromKeyfile`） |
| RawTherapee 部分粘贴 | `rtgui/windows/partialpastedlg.cc`、`rtgui/paramsedited.h` |
| RawTherapee `[Version]` 段 | `rtengine/procparams.cc:3815-3819` |
| 真实 `.pp3` 稀疏样本 | `rtdata/profiles/Standard Film Curve - ISO Low.pp3`（472 B，5 段） |
| 本仓库 FilmParams 96 槽与恒等值 | `core/engine/src/film/FilmParams.h:29-440` |
| 本仓库 LUT 强度槽 = `[34]` | `core/engine/src/film/FilmParams.h:84` |
| 本仓库撤销栈（快照式） | `app/src/main/java/com/filmkit/app/camera/EditorExport.kt:645-720` |
| 本仓库套用原片风格（可复用） | `app/src/main/java/com/filmkit/app/ui/EditorScreen.kt:581-620, 654, 665` |
| 本仓库底栏「预设」置灰留位 | `app/src/main/java/com/filmkit/app/ui/EditorScreen.kt:3036-3050` |
| 本仓库顶栏**无**「保存预设」按钮 | `app/src/main/java/com/filmkit/app/ui/EditorScreen.kt:2174-2230` |
| 本仓库 LUT 库（prefs + filesDir） | `app/src/main/java/com/filmkit/app/lut/LutLibrary.kt:39-56, 208-233` |

### 6.2 URL

| 主题 | URL |
|---|---|
| Lightroom 官方《Apply Presets》（勾选/Amount/分组/导入导出/更新/ISO 自适应/兼容性） | <https://helpx.adobe.com/lightroom-classic/desktop/process-and-develop-photos/apply-presets.html> |
| 真实 LR 预设 `.xmp`（含 SupportsAmount/UUID/Cluster） | <https://raw.githubusercontent.com/9bic/lrpresets/master/9bichrome.xmp> |
| 真实 LR 预设 `.xmp`（含颗粒/暗角/曲线） | <https://raw.githubusercontent.com/elchinhuseynovv/Lightroom_xmp/main/70s_Vintage_Look.xmp> |
| LR Amount = 参数按比例（150% of 3.40 = 5.10） | <https://www.lightroomqueen.com/community/threads/amount-slider-question.48168/> |
| LR Amount 0–100% 描述 | <https://theeditingstudio.co/blog/lightroom-preset-amount-slider> |
| LR Amount 0–200% 描述 | <https://www.erikaswafford.com/blog/lightroom-preset-amount-slider-customize-your-presets-to-your-style> |
| LR Amount 与 mask（第三方，与上条有冲突，已标注） | <https://archipelagopresets.com/archipelago-field-guide/lightroom-preset-profile-amount-sliders> |
| LR 预设→Look 转换（SupportsAmount 用法） | <https://www.reddit.com/r/Lightroom/comments/kgiefm/convert_presets_to_profiles_the_guide/> |
| Capture One 官方《Applying a Style》 | <https://support.captureone.com/hc/en-us/articles/360002611857-Applying-a-Style> |
| Capture One 官方《Style Brushes》 | <https://support.captureone.com/hc/en-us/articles/360017633157-Style-Brushes> |
| Capture One Styles 产品页 | <https://www.captureone.com/en/products/styles> |
| Capture One `.costylepack` 安装（第三方） | <https://help.luxelens.com/hc/en-us/articles/38063237163543> |
| VSCO 官方 preset packs（(-)/(+)/(++) 变体） | <https://www.vsco.co/features/photo-filters/preset-packs> |
| VSCO Preset Guide（分组） | <https://support.vsco.co/en/articles/12698471-preset-guide> |
| VSCO 预设技术特征（颗粒/高光滚降/通道响应，第三方） | <https://www.shutternoise.com/articles/vsco-film-presets-lightroom-return.html> |
| RNI 官方（film presets + film profiles） | <https://reallyniceimages.com/> |
| RawTherapee HaldCLUT 合集（402 MB） | <https://rawtherapee.com/shared/> |

### 6.3 复现命令（供复核）

```bash
# PC 无预设功能
grep -rn "preset" --include=*.java -i /tmp/pc_probe | head

# darktable 输出混合公式
curl -s https://raw.githubusercontent.com/darktable-org/darktable/master/src/develop/blends/blendif_rgb_hsl.c \
  | sed -n '378,383p'

# darktable style 表结构
curl -s https://raw.githubusercontent.com/darktable-org/darktable/master/src/common/database.c \
  | sed -n '230,242p'

# RawTherapee strength 混合
curl -s https://raw.githubusercontent.com/RawTherapee/RawTherapee/dev/rtengine/clutstore.cc | sed -n '236,238p'

# RawTherapee 稀疏写入
curl -s https://raw.githubusercontent.com/RawTherapee/RawTherapee/dev/rtengine/procparams.cc | sed -n '268p'

# 真实 Lightroom 预设
curl -s https://raw.githubusercontent.com/9bic/lrpresets/master/9bichrome.xmp | head -20
```
