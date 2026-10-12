# 拍照界面预设面板 · **交互**规格调研（PR-2）

> 卡号 PR-2 ｜ 用户口径（2026-10-11，v0.11.19 真机）："预设面板交互逻辑太差，**全是滑杆**"、
> "预设这块**不是让你参考别的开源实现吗**"。
> **本报告只补交互缺口**（数据模型 / 强度机制 / 存储格式见 `editor-preset-RESEARCH.md`，不重复）。
> 证据口径：**官方文档 / 官方界面截图 / 源码行号**；查不到的一律写"查不到"（§4），不推测。

---

## 0. 一页结论（先看这 8 条）

1. **"全是滑杆"的根因被定位了**：业界把两件事分成两个动作 ——
   **(a) "这条预设包含哪些参数" = 勾选**（Lightroom Classic / Capture One / darktable / RawTherapee **四家一致**）；
   **(b) "某个已包含的参数调到多少" = 点开那一项再调**。
   PS-3 把 (a) 做成了"把 35 项全铺开、每项一条滑杆"，**四家里没有任何一家这么做**。
2. **未包含的字段在四家都照样显示**（以"未勾选"的形态），只有我们把它显示成"未设置 + 也有一条滑杆"。
3. **Adobe 的 New Develop Preset 对话框里一个值控件都没有** —— 对话框只有
   `Preset Name` / `Group` / **两级（局部三级）复选框树** / `Check All` / `Check None` / `Support Amount Slider` / `Create`（官方截图，§1.3）。
   即：**值来自"当前照片的当前状态"，用户只决定"哪些进预设"**。
4. **勾选的默认态 = 全选**：Capture One 官方："all of the applied adjustments and layers are **automatically selected** …
   **Uncheck** the settings that you do not want to include"。⇒ 新建/编辑的默认是"全勾上，用户取消不要的"，
   不是"从零开始一项项勾"。
5. **"不变"也要能表达**：darktable 的 style 对话框每行有 `include` + `reset` **两列**复选框
   （`reset` = 包含该模块但用默认值）；RawTherapee 用 `Everything` + 9 个分组 + 组内逐项的三态级联。
6. **强度滑杆的位置，移动端两家完全一致**：LR Mobile 官方"**Select the applied preset thumbnail to reveal the Amount slider**"；
   VSCO 官方"**Tap a preset to preview it on your image. Tap it again to see its full name and adjust Strength**"。
   ⇒ 点一次=套用，**再点一次**才出强度。
7. **内置预设**：Lightroom **不能改也不能删**（官方原文，导出也被禁），替代 = 右键 `Update With Current Settings`（只对 user preset）/ 复制；
   darktable 是"用户同名预设**覆盖**内置，删掉用户的后内置自动回来"（官方 note）。
   ⇒ 我们 PS-3 的"覆盖层 + 恢复默认"**= darktable 语义，有先例，保留**；LrC 的等价物是"复制"（我们已有 COPY）。
8. **移动端开源对照**：找到 `256x/film`（Kotlin + Compose，含 `PresetEditorScreen.kt`）——
   它**也是"全字段预设 + 一列滑杆"**。这说明：**全滑杆只在"预设 = 全字段快照"时才是对的**；
   我们是稀疏子集模型，所以必须用勾选树。（该文件见 §1.6）

---

## 1. 交互问题 × 各家做法 × 我们的裁决

### 表 1 · 预设列表形态

| 问题 | 各家怎么做（证据） | 我们的裁决 |
|---|---|---|
| 列表 or 网格 | **桌面专业工具 = 可折叠分组的文字树**（LrC 官方截图：`Filter Presets` 搜索框 + `▸ Adaptive: Portrait` 分组树，`apply-presets-lrc` 图）；**移动端 = 横向/网格缩略图条**（VSCO 官方 GIF：顶部 `All Presets / Favorites / Recent / For This Photo` 分类 + 缩略图条） | **文字行列表**（现状），不引缩略图（见下条） |
| 每项显示什么 | LrC = 名称 + 分组树 + 小图标；**移动端 = 用"当前照片"渲染的缩略图**（VSCO GIF 里同一朵花的不同色彩；LR Mobile 官方称 "the applied preset **thumbnail**"）。darktable 官方手册：`Hover over the style name … view a **preview of the first selected image with that style applied**` | 现状：**名称 + 来源/分组 + 参数摘要**（`presetSummaryText`）。✅ 保留。**不做实时缩略图**：拍照界面底部面板高度预算（现状 150dp）+ N 张实时渲染成本；LrC 桌面同为纯文字树，不算破例 |
| 分组 | LrC：`Group` 字段 + `Manage Presets` 显隐分组；darktable：**名称里用 `\|` 造层级**（官方手册原文："`print\|tone curve +0.5 EV` will create a **category**"）；LR Mobile：`Manage Presets` 开关显隐 | 现状：`group` 字段 + 行内显示。✅ 保留。不引入 `\|` 层级（YAGNI，用户未提） |
| 搜索 / 收藏 | LrC：`Filter Presets` 框；**Favorite 组**（导出时只导自定义/第三方）；LR Mobile：`Manage presets` 开关；darktable styles：搜索框可搜**名称或描述** | 现状：预设数量是"个位数~几十"，**不做搜索**；收藏不做（YAGNI）。若用户后续要求，LrC 的 Favorite 组 + 显隐是范本 |
| 空列表 / 新增入口 | LrC：面板顶部 `+`（Add）→ `Create Presets`；darktable：`create` 按钮 | 现状：顶部常驻「+ 新建预设」。✅ 保留 |

### 表 2 · 选中与套用 / 强度 / 撤销

| 问题 | 各家怎么做（证据） | 我们的裁决 |
|---|---|---|
| 点一项 = 套用还是仅选中 | **四家都是立即套用**：LrC 官方"**Select one of the presets to apply to the image**"；darktable 官方"**Double-click on a style name to apply**"（源码 `src/libs/styles.c:225 _styles_row_activated_callback` → `dt_styles_apply_to_dev`）；C1 官方"preview the effect … by **hovering**"（鼠标预览，点击即用）；VSCO 官方"**Tap a preset to preview it on your image**" | 现状：**点行 = 套用**。✅ 保留 |
| 有选中态吗 | 有：darktable 模块预设"currently selected preset is shown in **bold** and with a **small check mark**"（官方手册）；flave 开源实现用高亮（`preset_selector_widget.dart:36-44`） | 现状：列表行有选中标记。✅ 保留（并明确：**选中态 = 当前生效的那条**） |
| 强度滑杆放哪 | LrC：**面板顶部常驻** `Amount`（官方截图 `apply-presets-lrc`：`Preset: None` + `Amount ——●—— 100`），per-preset 能力标记 `SupportsAmount`；**LR Mobile / VSCO：点已套用的项才展开**（官方原文见 §0.6） | **保持现状**（LUT 强度仍在 LUT tab，`[34]` 归 `lut.strength`）。列表页**不做**"再点展开强度"：会与 LUT tab 重复（YAGNI）。若用户要求，照 VSCO/LRM 的"再点一次" |
| 撤销 / revert | LrC：标准 Undo + 面板顶部 `Preset: None`（= 取消套用）；Snapseed：官方有独立 `Undo, Redo, Revert` 帮助页 + Stacks 可回改任一步；VSCO GIF 底部有重置图标 | 现状：**拖动中的改动可由「移除该项」/重进详情清掉**，但没有显式"还原"。（`overrides` 是内存态，切预设即清空。）裁决：详情页保留"未保存改动"提示（现状截图里那句"未保存不写入预设"），**不新增全局撤销**（YAGNI） |
| 对比原图 | Snapseed 官方："**Tap and hold within a Tool or Filter to reveal the before image**"；`256x/film` 开源实现：长按预览 = 传 `preset = null`（`PresetEditorScreen.kt:68-81`） | 现状有"实时效果 开/关"（参考截图）。裁决：**保留开关形式**，长按对比作为可选（用户没提） |

### 表 3 · ★ 预设详情 / 编辑（本次核心）

| 问题 | 各家怎么做（证据） | 我们的裁决 |
|---|---|---|
| **参数集合怎么展示** | **Adobe 官方截图**（`new-develop-presets-apply-presets-lrc`，本报告实际下载读图确认）：`Settings` 区 = **三列布局的复选框树**；一级 = `Treatment & Profile / Basic / Curve / Color Mixer / Color Grading / Detail / Lens Corrections / Transform / Effects / Remove / Calibration / High Dynamic Range / Lens Blur / Masking`；二级 = `White Balance / Exposure / Contrast / Highlights / Shadows / Whites / Blacks / Texture / Clarity / Dehaze / Vibrance / Saturation`…；`High Dynamic Range` 下还有第三级（`HDR Mode: Off → SDR Settings`） | ★ **就是一张"分组 + 参数"的勾选树**（替换 PS-3 的"全量清单 + 逐项滑杆"）。分组沿用现有 5 组（`cameraParamSections`） |
| **全量 vs 只显示已包含** | **全量**：LrC 树里所有设置都在（未勾选的也显示）；RawTherapee `Everything` + 9 组 + 组内全部项（源码 `rtgui/windows/partialpastedlg.h:118-186`）；darktable 每行一个 `include` 复选框（`src/gui/styles_dialog.c:619-622`） | **全量显示**（未包含的以"未勾选"出现）——这与 PS-3 的"未设置也列出来"方向一致，**只改控件形态与位置** |
| **未包含的字段怎么出现** | 未勾选的复选框；**当前不适用的项 = 灰显复选框但仍显示**（官方截图里 `Masking` 复选框是灰的） | 未包含 = `☐ + 名称 + 「—」`；拍照链路不能调的项 = **可勾选但值灰显 + 一行原因**（= 现状 ADR-12 的做法，与 LrC 灰显 Masking 同型） |
| **增删字段怎么操作** | 勾选 = 加入、取消 = 移除（四家一致）；darktable 编辑态还有第二列 `reset`（包含但用默认值）；RawTherapee：点组头**级联**全组（源码 `partialpastedlg.cc:613-640` `everythingToggled` 阻塞连接后逐组 `set_active`；`:726-745` 组内逐项级联；`set_inconsistent(false)` 处理三态） | **勾选框 = 加入/移除**（复用现有 `afterEdit` / `removeSlot`）；**组头 = 三态整组勾选**（照 RawTherapee 级联）；**不做** darktable 的 `reset` 第二列（我们的"包含 + 值为基线"没有可观察差异，见 §2.5 不变量 I3） |
| **一次调一项 vs 整屏可调** | LrC 的值调整不在预设对话框里（对话框没有值控件）；值调整在正常 Develop 面板（整屏滑杆） | **一次只展开一项**（现状 `openKey`，与用户参考截图 Filmix 的"选择一项开始调整"一致）。**这一条是"看上去不再是全是滑杆"的关键** |
| **用什么控件** | LrC/C1/darktable 的值控件都是**滑杆 + 数字直读**（LrC 面板截图：`Exposure 0.00 / Contrast …` + 数值框）；darktable 模块预设对话框里**没有任何值控件**（官方截图 `presets/new_preset.png`：只有 name/description/3 个复选框/Exif 过滤条件） | 滑杆 + 直读，**沿用 `EditorSlider`**（我们的 35 项全是连续量：如"黑白 [25]"引擎是 `mix()` 连续混合，`ParamControls.kt:364-372`——不存在布尔/枚举项，因此**不需要开关或步进**） |
| **值从哪来** | Adobe 官方："**Presets you create are based on the current settings of the selected photo**"；C1：从 Adjustments Clipboard（当前调整） | 现状：新建 = 当前相机参数；编辑 = 预设记录 + 用户改动。✅ 保留 |
| **有个"查看内容"的只读入口吗** | 有：darktable styles 悬停出内容对话框（`src/libs/styles.c:165 dt_gui_style_content_dialog(name, imgid)`） | 现状：「查看配置」菜单项 + 长按。✅ 保留 |

### 表 4 · 保存流程

| 问题 | 各家怎么做（证据） | 我们的裁决 |
|---|---|---|
| 对话框里问什么 | LrC：`Preset Name` + `Group` + 勾选 + `Support Amount Slider` + `Create ISO adaptive preset` + `Subset` 下拉 + `Check All / Check None`，底部 `Cancel / Create`（官方截图）；C1：`Save Style clipboard window`（取消勾选）→ `Name and save the Style`；darktable：`name` + `description` + 勾选 → `save`（`styles_dialog.c:553-556` 按钮 `select all / select none / cancel / save`） | **名称 + 分组 + 勾选结果**，值不再追问（现状的"保存到预设"一步完成）。✅ 保留，**不新增任何配值向导** |
| 命名校验 | darktable：名称为空 ⇒ `save` 按钮不可点（`styles_dialog.c:512-517 _name_changed` 控制 `GTK_RESPONSE_ACCEPT` 敏感度）；重名 ⇒ 改名提示框（`:259-271`） | 现状：`defaultPresetName` 预填 + `presetEditorButtons` 控制可用性。✅ 保留 |

### 表 5 · 内置 / 出厂预设

| 问题 | 各家怎么做（证据） | 我们的裁决 |
|---|---|---|
| 能改吗 | LrC：**不能**（官方："**You cannot delete built-in** Lightroom Classic presets"、"**You cannot export built-in** … presets"；`Update With Current Settings` 只对 user preset）；darktable：内置有 `writeprotect`，**用户同名预设覆盖内置**（官方 note："your preset will **override** the built-in version"） | 现状（PS-3）：**内置可改**（覆盖层 `builtin_overrides`）+ **可恢复默认**。✅ **保留** —— 这正是 darktable 的覆盖语义，有先例 |
| 不能改时给什么替代 | LrC：右键复制/`Update With Current Settings`（user）；C1：`Save Custom Style`（自建） | 现状：菜单有 `COPY`（复制为我的预设）。✅ 保留（= LrC 的 Duplicate） |
| 恢复出厂 | darktable 官方 note："If you **delete** a preset that has the same name as one of the built-in presets, … **The next time you start darktable, the corresponding built-in preset will once again become visible**" | 现状：「恢复默认」删覆盖层 ⇒ 立刻回出厂。✅ 保留（比 darktable 的"重启才回来"更快） |

### 表 6 · 移动端开源实现（有没有可参考的代码）

| 项目 | 结论（证据） | 我们照做什么 |
|---|---|---|
| **`256x/film`**（Kotlin + Compose，★0，含 `PresetEditorScreen.kt`） | **有**：预设编辑页 = **10 条滑杆**（`PresetEditorScreen.kt:94-106`）+ `Monochrome` 开关 + 长按对比原图（`:68-81`）+ 命名对话框（`:115-126`）。其数据模型是**全字段非稀疏**（`domain/model/FilmPreset.kt:12-42`，每个字段都有默认值，无"未包含"概念） | **不照抄它的编辑页**（它是全字段模型 ⇒ 全滑杆才成立）；但**照抄它的两处**：① 长按 = 对比原图；② 保存对话框**只问名称**（它的 `PresetEditorScreen.kt:115-126` 只有 name），不在保存时追问参数 |
| **`semeikhan-t/flave`**（Flutter） | **有**：`lib/features/camera/preset_selector_widget.dart` = **底部横向滚动列表**（宽 70 项），`onTap → setPreset`，`isSelected` 高亮（`:28-55`）；只有"选择"，**没有编辑/新建/增删字段** | 列表页的"横向条 + 选中高亮"形态我们**不用**（我们用纵向列表，摘要在行内更好读）；确认了"点即套用 + 高亮"是通例 |
| **GrapheneOS/Camera** | **没有**：`main` 分支 269 个路径，`preset/recipe` 零命中；`profile` 的 3 个命中全是 `.idea/inspectionProfiles/*`（IDE 配置），与预设无关 | — |
| **FossifyOrg/Camera** | **没有**：`main` 分支 464 个路径，`preset/profile/recipe` 零命中（**已核对默认分支，非假阴性**） | — |
| **OpenCamera**（镜像 `kusl/markharmanopencamera`，官方源在 SourceForge） | **没有**：`res/values/strings.xml` 中无 `save settings` / `load settings` / `custom` 类条目（命中全是 `save_location` / `save_photo_prefix` 等"保存照片"项） | — |
| **android/camera-samples**（CameraX） | **没有**：`main` 分支 792 个路径，零命中（已核对默认分支） | — |
| 其它 GitHub 命中（`adityawarmanfw/rawr`、`ryuheiyokokawa/FilmFrame`） | `rawr` 的 tree 里只有 `FrameRecipe.h` / `ProcessingRecipe.h` / `PreviewLookController` 等**持久化与预览控制**文件，**未见预设编辑 UI 文件**（未深入读其内容，故不下"没有编辑功能"的断言）；`FilmFrame` tree 内无 preset 相关文件名 | 无新交互可抄 |

---

## 2. 落地规格（实现卡照着写，不需要再做设计决策）

### 2.1 界面 A：预设列表页（现状保留，**0 处改动**）

现状已符合业界（点行套用 / 选中态 / 摘要 / ⋯ 菜单 / 长按进详情 / 顶部 + 新建）。
**本次唯一明确不做的事**：列表页"再点一次展开强度"（理由见 §1 表 2）。LUT 强度仍归 LUT tab。

### 2.2 界面 B：预设详情 / 编辑页（**参数区重做**）

```
‹ 返回   编辑预设 · 内置                  [恢复默认]
 名称 [徕卡时刻            ]  分组 [胶片        ]
 ─────────────────────────────────────────────────────────
 已包含 12 · 未包含 23                 [全选] [全不选]
 ─────────────────────────────────────────────────────────
 ▾ 基础调整                             ▣ 4/9        ← 组头：三态框 + 计数 + 折叠箭头
    ☑ 曝光                      +0.30 EV              ← 已包含
      ┌───────────────────────────────────────────┐
      │ +0.30 EV                                  │      ← 点行体才展开（一次一项）
      │ ──────────────●─────────────              │      ← 全页唯一出现滑杆的地方
      │ [不包含此项]                               │
      └───────────────────────────────────────────┘
    ☐ 对比度                    —                     ← 未包含：值列「—」，没有滑杆
    ☑ 颗粒 · 强度               20                    ← 可包含但拍照不能调
      拍照生效 · 暂无滑杆                              ← 灰显 + 原因（ADR-12，= LrC 灰显 Masking）
 ▸ 色彩                                 0/4
 ▸ 影调曲线                             3/8
 ▸ 胶片质感                             4/10
 ─────────────────────────────────────────────────────────
 [保存到预设] [从当前相机参数更新] [保存名称 / 分组] [删除]
```

**没有滑杆的行 = 绝大多数行**；滑杆只在"用户点开的那一项"里出现 ⇒ 直接消灭"全是滑杆"。

### 2.3 组件清单与代码落点

| # | 组件 | 说明 | 落点 |
|---|---|---|---|
| C1 | `CamParamCheckTree` | 分组勾选树（组头 + 参数行） | 重写现有 `CamPresetParamList`（`CameraPresetPanel.kt:960-1159`） |
| C2 | `CamParamRow` | `☑/☐ + 名称 + 值/「—」`；点勾选框 = 加/移除，点行体 = 展开 | 现有 `CamPresetField` 扩展一个勾选位（`:886-914`） |
| C3 | `CamParamExpand` | 展开区：读数 + `EditorSlider` + 「不包含此项」 | 抽出现有展开块的 Composable |
| C4 | `SectionTriCheck` | 组头三态框（全勾 / 半勾 / 全不勾） | 新增（纯逻辑可测：`sectionCheckState(...)`） |
| C5 | `CheckAllRow` | `[全选] [全不选]` + 「已包含 n · 未包含 m」 | 新增（纯逻辑：`checkAll(...)` / `checkNone(...)`） |
| C6 | 纯逻辑 | `toggleSlot(slot)` / `toggleSection(title)` / `checkStateOf(...)` | 新增到 `CameraPresetPanel.kt` 的纯函数区（JVM 可测，与现有 5 个函数同处） |

**复用不改**：`EditorSlider`（`ParamControls.kt`）、`cameraParamSections` / `CameraParamRow`（`PresetDetail.kt:257-313`）、
`CameraParamOverrides` / `afterEdit` / `removeSlot` / `forPreset`（`CameraPresetPanel.kt:254-350`）、
`presetEditorButtons` / `presetReadOnlyNote`（`PresetDetail.kt`）、`DragHolder` 拖动不写 state（PS-3 §1.1 决策 2）。

### 2.4 交互流程（**点哪里 ⇒ 出什么**）

| # | 用户动作 | 画面（取景） | 面板 | 数据 |
|---|---|---|---|---|
| 1 | 点 `☐` 行的**勾选框** | 不变（值就是当前值） | 变 `☑`，值列出现读数 | 槽写入 `overrides.values`（`afterEdit`） |
| 2 | 点 `☑` 行的**勾选框** | 该参数立即回基线 | 变 `☐`，值列 `—`，若正展开则收起 | `removeSlot`（进 `removed`） |
| 3 | 点 `☑` 行的**行体** | 不变 | 展开该行滑杆区（`openKey = key`），别的行自动收起 | 不变 |
| 4 | 点 `☐` 行的**行体** | 不变 | 展开滑杆区 **并同时勾选**（= "我要调它 ⇒ 它进预设"） | 槽写入当前读数（等价 #1） |
| 5 | 拖动展开行里的滑杆 | 每帧实时生效（`onPreview` 直通） | 读数实时变 | 松手才提交 `onOverrides`（一次） |
| 6 | 把值拖回基线 | 该参数的效果消失 | 行自动变 `☐`（值列 `—`） | 稀疏化自动丢弃（§2.5 I3），**可见反馈** |
| 7 | 点「不包含此项」 | 该参数回基线 | 行收起 + 变 `☐` | `removeSlot` |
| 8 | 点组头**勾选框** | 整组加入的项生效 / 移除的项回基线 | 组内全部行一起变 `☑`/`☐` + 计数更新 | 逐槽 `afterEdit` 或 `removeSlot`（**一次提交**，不要每槽一次重组） |
| 9 | 点组头**箭头/标题** | 不变 | 只折叠/展开该组 | 不变 |
| 10 | 点 `[全选]` | 全部可调项按各行读数生效 | 全部 `☑`，计数 `n/n` | = LrC `Check All`（逐槽加入） |
| 11 | 点 `[全不选]` | 回到"无预设覆盖"的相机参数 | 全部 `☐`，值列全 `—` | = LrC `Check None`（全部 `removed`） |
| 12 | 点「保存到预设」 | 不变 | 存完回列表 | 落盘 = 勾选集合 + 各行值（`cameraPresetSlotsFor`，含 `[0]` 纪律） |
| 13 | 点「从当前相机参数更新」 | 不变 | 勾选树刷新为"当前全部生效项" | 现状 `onUpdate` |
| 14 | 点「恢复默认」（内置改过时） | 回出厂画面 | 勾选树回出厂 | 删覆盖层（现状） |
| 15 | 长按取景区 | 临时看无预设原图（可选，照 `256x/film`） | 不变 | 不变 |

**默认态（新建 / 编辑）**：
- **勾选默认 = 全选**（C1 官方："all of the applied adjustments and layers are automatically selected"）——
  新建时"全选"的范围 = `cameraPresetParams(selected)` 里现有的槽（**不新增语义**，即现有稀疏口径）；
  编辑时 = 该预设 `slots`。
- **行级展开默认 = 全部收起**（现状 `openKey = null`，保持）。
- **分组默认展开 = 含已勾选项的组**（现状 `openSections`，保持）。

### 2.5 状态与不变量（实现时不许破）

| # | 不变量 | 依据 |
|---|---|---|
| I1 | **勾选态的唯一真相 = 槽在不在 `slots` 里**（`slots = 预设记录 − removed + values`） | 现有 `forPreset` / `cameraPresetSlotsFor` 口径 |
| I2 | 编辑对象 == 生效对象（进详情先套用） | PS-3 §1.1 决策 4，保留 |
| I3 | 值 == 基线 ⇒ 稀疏化丢弃 ⇒ **勾选自动变 `☐`**（与 LrC 的"包含但值=0"语义**有意不同**：我们的相机链路里"包含 + 值 = 基线"与"不包含"没有任何可观察差异） | `EditorExport.kt:2128-2141`（`abs(v - neutral[i]) > PARAM_EPS`） |
| I4 | 拖动**不写** Compose state（`DragHolder`），松手提交一次 | PS-3 §1.1 决策 2（v0.10.96 教训） |
| I5 | 组头三态：全勾 / 半勾（`▣`）/ 全不勾；半勾点一下 = **全勾**（RawTherapee `everythingToggled` 的 `set_inconsistent(false)` 同义） | `partialpastedlg.cc:628-640` |
| I6 | 拍照链路不可调的项：**可勾选**（值灰显 + 原因），不提供滑杆 | 现状 ADR-12 + LrC 灰显 `Masking` |

### 2.6 与现状的差异清单（改 / 不改）

**改（3 处）**：① 每个参数行加**勾选框**（原来只有"移除"按钮）；② 加**组头三态框**与 `[全选]/[全不选]`；
③ 「移除该项」从"展开区里的按钮"升级为"勾选框"（按钮保留为同义冗余，避免用户找不到）。

**不改**：数据层（`PresetStore` / 覆盖层 / 稀疏化）、列表页、LUT 强度归属、拖动不写 state、编辑对象 == 生效对象、
内置可改 + 恢复默认、`[0]` 曝光纪律、参数表与编辑器同一套定义。

### 2.7 验收判据（可测，供实现卡直接用）

1. 源码/UI 哨兵：**未勾选行不渲染 `EditorSlider`**（"不是全是滑杆"的机器判据）。
2. 单测：`sectionCheckState` 三态（0/n、k/n、n/n）；`toggleSection` 一次提交后 `slots` 集合正确；`checkNone` 后画面参数 == 基线。
3. 单测：值拖回基线 ⇒ 该槽从 `slots` 消失且勾选态为 `☐`（I3）。
4. 单测：内置预设「全不选 + 保存」后仍可「恢复默认」回出厂（覆盖层语义不被破坏）。
5. 真机：进入详情页**首屏可见的滑杆数量 = 0**（默认全收起）。

---

## 3. 找不到的 / 不确定的（如实声明）

1. **RawTherapee 官方文档没拿到**：`rawpedia.rawtherapee.com` 全站 404（含 `/Main_Page`）、`www.rawtherapee.com` 返回 HTTP 466、
   archive.org 无快照。⇒ RawTherapee 一节**只有源码 + 官方多语言文案**（`rtgui/windows/partialpastedlg.*`、
   `rtdata/languages/default:1987-2035`），**没有官方文字说明**。
2. **Snapseed 官方文档没有 Looks 面板的交互描述**：`support.google.com/snapseed` 只有工具/滤镜清单
   （`answer/6183571`）与"Tap and hold … reveal the before image"（`answer/6155517`）；
   **"Looks 缩略图网格 + 强度"这一步没有官方文字**，本报告不写细节（不推测）。
3. **VSCO 只有"官方文档 + 官方 GIF"两件**：帮助中心其余页面被 Cloudflare 拦（403）；`.costyle` 内部格式官方未公开（既有调研已记）。
4. **Lightroom Classic 的 "Edit Preset"/`Update Develop Preset` 对话框没有独立官方截图**：
   官方只有文字（"Specify the settings you want to include in the preset and select Update"）；
   本报告用的截图是 **New Develop Preset** 对话框（同一套 Settings 树）。
5. **LrC 新建预设时各复选框的"默认勾选态"官方没写**（截图里 `Treatment & Profile/Basic/Curve` 勾着、`Lens Blur/Remove/Transform` 没勾 —— 但那是**当前照片的实际状态**，不能当默认规则）。
   我们采用 C1 的"默认全选"口径（有官方原文），并在 §2.4 明确。
6. **没有找到"开源 + 稀疏子集预设 + 勾选树"的移动端实现**（这是最接近我们形态的组合）：`256x/film` 是全字段模型，
   `flave` 只有选择器。⇒ 勾选树部分我们照 **桌面专业工具**（LrC / darktable / RawTherapee），**不是**照移动端开源。
7. 移动端两家（LRM/VSCO）的"再点一次出强度"我们**没有采纳**（理由：与 LUT tab 重复），属于**有意偏离**，已记录在 §1 表 2。
8. **darktable 官方手册用的是文档仓库源文件**：`docs.darktable.org/usermanual/{5.0,4.6,4.4}/…/styles.html` 本轮均返回 404，
   因此 §4.1 给的是官方文档仓库（`darktable-org/dtdocs`）里的 markdown 与 PNG 源文件 —— 内容同源，但**不是渲染后的网页 URL**。

---

## 4. 证据索引

### 4.1 官方文档（URL）

| 来源 | URL | 引用要点 |
|---|---|---|
| Adobe LrC · Apply Presets | <https://helpx.adobe.com/lightroom-classic/desktop/process-and-develop-photos/apply-presets.html> | `Select Check All … or select Check None`；`Presets you create are based on the current settings of the selected photo`；`Support Amount Slider`；`You cannot delete/export built-in … presets`；`Update With Current Settings`；`Hover over the preset, to preview`；`filter Presets … based on name`；`Manage Presets`；`Duplicate Preset Name` |
| Adobe LR Mobile · Apply presets | <https://helpx.adobe.com/lightroom/mobile/work-with-presets/apply-presets.html> | `Select the applied preset thumbnail to reveal the Amount slider`；分类 → 分组 → 变体 |
| Adobe LR Mobile · Manage presets | <https://helpx.adobe.com/lightroom/mobile/work-with-presets/manage-presets.html> | 预设分组显隐开关；不兼容预设斜体 |
| Adobe 官方截图（本报告已下载读图） | `https://helpx-prod.scene7.com/is/image/HelpxProdLoc/new-develop-presets-apply-presets-lrc`、`…/apply-presets-lrc`、`…/apply-presets-manage-presets-lrc` | 三列复选框树 / `Check All`+`Check None` / `Advanced Settings` / 灰显 `Masking`；面板顶部 `Preset: None`+`Amount` |
| darktable 官方手册 · styles | <https://raw.githubusercontent.com/darktable-org/dtdocs/master/content/module-reference/utility-modules/shared/styles.md> | 搜索框（名称或描述）；双击应用；hover 预览（第一张选中图）；`select which history stack items you want to include`；`reset`；`\|` 造层级 |
| darktable 官方手册 · presets | <https://raw.githubusercontent.com/darktable-org/dtdocs/master/content/darkroom/processing-modules/presets.md> | 当前预设粗体 + 勾；`edit/delete/update/store new preset`；`reset all module parameters`；`auto apply`；同名覆盖内置（删掉后内置回来） |
| darktable 官方截图 · new_preset.png | <https://raw.githubusercontent.com/darktable-org/dtdocs/master/content/darkroom/processing-modules/presets/new_preset.png> | 对话框只有 name/description/3 复选框/Exif 过滤条件 —— **无值控件** |
| Capture One · Saving a Custom Style | <https://support.captureone.com/hc/en-us/articles/360002619518-Saving-a-Custom-Style> | `all of the applied adjustments and layers are automatically selected`；`Uncheck the settings that you do not want to include`；`edit the Style from a Save Style clipboard window` |
| Capture One · Applying a Style | <https://support.captureone.com/hc/en-us/articles/360002611857-Applying-a-Style> | hover 预览；`a group of fixed settings or values`；Layer opacity 强度（既有调研引） |
| VSCO · Using and Managing Presets | <https://support.vsco.co/en/articles/12698476-using-and-managing-presets> | `Tap a preset to preview it on your image. Tap it again to see its full name and adjust Strength` + 官方 GIF（分类 tab + 当前照片缩略图条 + 选中项下方强度条） |
| Snapseed · 官方帮助 | <https://support.google.com/snapseed/answer/6155517>、`…/answer/6183571`、`…/answer/6155543` | `Tap and hold within a Tool or Filter to reveal the before image`；工具/滤镜清单；Stacks 可回改任一步（**无 Looks 面板细节**） |

### 4.2 源码（文件:行号）

| 来源 | 位置 | 要点 |
|---|---|---|
| darktable `styles_dialog.c` | `:512-517` 名称非空控制 save 敏感度；`:553-556` 按钮 `select all / select none / cancel / save`；`:571-583` name+description 输入；`:619-622` `include` 复选列；`:630-632` `reset` 复选列；`:641-654` 编辑态"新增项"树；`:665-667` `update` 列；`:726-730` 单选；`:744-749` `module order` 行；`:540-544` `duplicate style` 复选框 | 勾选树 + 两列复选 + 全选/全不选 |
| darktable `styles.c` | `:47-49` 控件成员；`:145-165` tooltip → `dt_gui_style_content_dialog`；`:225-256` 双击应用；`:286-330` `apply` 按钮 + `duplicate` 复选；`:337-359` 重命名 | 列表 + 立即应用 + 内容对话框 |
| RawTherapee `partialpastedlg.h` | `:118-186` `everything` + 9 个分组 CheckButton + 组内逐项 CheckButton；`:213-240` 各项 `sigc::connection` | "Everything + 分组 + 逐项"三级勾选 |
| RawTherapee `partialpastedlg.cc` | `:613-640` `everythingToggled`（阻塞连接 → 逐组 `set_active` → 逐组级联子项，`set_inconsistent(false)`）；`:715-745` 组内级联 | 组级/全局级联语义 |
| RawTherapee `rtdata/languages/default` | `:1987-2035` | 官方 UI 文案：`Everything` / `Basic Settings` / `Detail Settings` / `Color Related Settings` / `Lens Related Settings` / `Composition Settings` / `Advanced Settings` / `Selective Editing Settings` … |
| 我们 · `CameraPresetPanel.kt` | `:474-593` `CameraPresetTab` / `openNew` / `openEdit`；`:960-1159` `CamPresetParamList`（**要重做的地方**）；`:1161+` `CameraPresetEditorPanel`；`:254-350` `forPreset` / `afterEdit` / `removeSlot` | 现状落点 |
| 我们 · `PresetDetail.kt` | `:257-313` `CameraParamRow` / `cameraParamSections`；`:381-434` 菜单/只读口径；`:496-523` 编辑按钮 | 复用 |
| 我们 · `EditorExport.kt` | `:2128-2141` `presetSparseSlots`（`abs(v - neutral[i]) > PARAM_EPS`） | I3 的依据 |
| 我们 · `ParamControls.kt` | `:161-175` `EditorParam`；`:364-372` 黑白 `[25]` 是连续 `mix()` | 控件只需滑杆 |
| 开源 `256x/film` | `app/src/main/java/fumi/day/literalfilm/editor/PresetEditorScreen.kt:68-126`、`domain/model/FilmPreset.kt:12-42` | 全字段模型 + 10 滑杆 + 只问名称的保存对话框 |
| 开源 `flave` | `camera/flutter_app/lib/features/camera/preset_selector_widget.dart:28-55` | 横向列表 + `onTap → setPreset` + 选中高亮，无编辑 |

### 4.3 复现命令（供复核）

```bash
# Adobe 官方截图（本报告读图用的就是这两张）
curl -sSL -o lr_newpreset.jpg "https://helpx-prod.scene7.com/is/image/HelpxProdLoc/new-develop-presets-apply-presets-lrc?\$pjpeg\$&jpegSize=300&wid=1500"
curl -sSL -o lr_panel.jpg     "https://helpx-prod.scene7.com/is/image/HelpxProdLoc/apply-presets-lrc?\$pjpeg\$&jpegSize=300&wid=1500"
# darktable 源码
curl -sSL https://raw.githubusercontent.com/darktable-org/darktable/master/src/gui/styles_dialog.c | sed -n '519,560p;619,632p'
# RawTherapee 源码（注意路径是 rtgui/windows/）
curl -sSL https://raw.githubusercontent.com/Beep6581/RawTherapee/dev/rtgui/windows/partialpastedlg.cc | sed -n '613,640p'
# Capture One 官方原文（Zendesk API；限速时会 403，重试即可）
curl -sS "https://support.captureone.com/api/v2/help_center/articles/360002619518.json"
```
