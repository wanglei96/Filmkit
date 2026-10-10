# P-2a 报告：拍照界面「预设」做实

> 子代理交付 ｜ 2026-10-11 ｜ 工作区未 commit（按纪律留给架构师复核）
> 前置：`docs/reports/preset-DESIGN.md` §7 裁决 A1–A5、`preset-shooting-side-RESEARCH.md`

---

## 0. 结论摘要

拍照界面底部面板的「预设」tab 从"只有一个硬编码「标准」"变成**真实预设列表**：

- **数据源**：`PresetStore.load(prefs)`（与查看页「胶片」/ 编辑器「预设」**同一套**）+ 首项内置「标准」；
- **套用**：`标准基线 + 预设稀疏覆盖` ⇒ 完整 96 槽 → `controller.setFilmParams`；
- **LUT**：含 ⇒ 选中 + 下发预设强度；不含 ⇒ **不动**；引用的已不在库 ⇒ **不动 + Toast 提示**；
- **选中态**：prefs 键 `camera_preset_id` 持久化，冷启动按 id 恢复，预设被删则回退「标准」。

---

## 1. 改了什么（文件:行号）

### 1.1 新增：`app/src/main/java/com/filmkit/app/camera/CameraPresetApply.kt`（115 行）

拍照侧预设的**纯逻辑层**（不依赖 Compose / prefs / 引擎 ⇒ JVM 单测可直接驱动）：

| 行 | 符号 | 作用 |
|---|---|---|
| `:20` | `CAMERA_STANDARD_PRESET_ID = "builtin_standard"` | 内置标准的固定 id（前缀与用户预设 `p_` 不撞） |
| `:28` | `CAMERA_STANDARD_PRESET` | 内置「标准」= 中性基线、无 LUT、`source="builtin"` |
| `:44` | `cameraPresetParams(preset)` | `presetSnapshot(CameraPresets.standard(), preset.slots)` ⇒ 完整 96 槽 |
| `:53` | `enum PresetLutAction { KEEP, APPLY, MISSING }` | LUT 三态 |
| `:71` | `presetLutAction(preset, lutExists)` | 三态判定（`lutExists` 由调用侧注入 ⇒ 可测） |
| `:87` | `resolveCameraPreset(savedId, presets)` | 冷启动恢复（null/命中/**悬空** ⇒ 恒返回一条，绝不 null） |
| `:98` | `presetSubtitle(preset)` | 卡片小字「分组 · LUT」（都无 ⇒ null） |
| `:111` | `presetSlotsText(preset)` | 日志用 `语义名=值;…`（验收证据，见 §5.2） |

> 为什么新开一个文件而不是塞进 `CameraScreen.kt`（2835 行）或 `CameraPresets.kt`：
> 任务书要求"能抽成纯函数就抽出来测"，而 `EditorExport.kt` 本卡禁改。放 `camera` 包是为了
> 与 `PresetStore` / `presetSnapshot` 同包（数据层），单测也落在任务书指定的测试目录。

### 1.2 `app/src/main/java/com/filmkit/app/ui/CameraScreen.kt`

| 行 | 改动 |
|---|---|
| `:116`、`:135-146` | import：`TextOverflow` + 12 个 `camera` 包符号 |
| `:228-231` | `presetIdx: Int` → `presets: List<EditorPreset>` + `selectedPreset: EditorPreset`（**remember + prefs 回落**，与 `photoUri` 同款，非 saveable） |
| `:455-500` | 新增 `applyCameraPreset(p)` —— 本卡核心（参数 / LUT / 选中态三件事） |
| `:502-512` | 启动 `LaunchedEffect`：冷启动按 prefs id 恢复 + 悬空 id 清理；`setFilmParams(cameraPresetParams(selectedPreset))` |
| `:1124` | LUT 入口小圆点判据 `presetIdx != 0` → `selectedPreset.id != CAMERA_STANDARD_PRESET_ID` |
| `:1396-1397`、`:1437` | `LutSheet` 调用点：传 `presets` / `selectedPreset`；`onPick` 改走 `applyCameraPreset` |
| `:1973-1992` | `LutSheet` 签名：`presetIdx: Int` → `presets` + `selectedPreset`；`onPick: (EditorPreset) -> Unit` |
| `:2026` | 顶行标题：`CameraPresets.names.getOrElse(presetIdx)` → `selectedPreset.name` |
| `:2100-2126` | 预设 tab 列表：`items(listOf(CAMERA_STANDARD_PRESET) + presets)`，按 **id** 判选中；空列表给一句人话 |
| `:2591-2657` | `LutCard` 新增 `sub: String? = null`（第二行 9sp 小字，宽 76dp + 省略号） |

`itemsIndexed` 的 import 随旧列表一并删除（无其它调用点）。

### 1.3 新增：`app/src/test/java/com/filmkit/app/camera/CameraPresetApplyTest.kt`（9 用例）

---

## 2. 套用语义（逐条对照任务书 §2）

| 任务书要求 | 实现 | 位置 |
|---|---|---|
| 标准基线 + 稀疏覆盖 ⇒ 96 槽 | `cameraPresetParams`（复用 `presetSnapshot`） | `CameraPresetApply.kt:44` |
| `lutId == null` ⇒ **不动**当前 LUT | `PresetLutAction.KEEP` ⇒ 不写 `lut_strength`、不设 `lutPending` | `:71`、`CameraScreen.kt:476-486` |
| LUT 已不在库 ⇒ 降级"不动" + 明确提示 | `MISSING` ⇒ Toast「…LUT 已不在库中 · 保留当前 LUT」，**不碰** `lut_id` / `lut_strength` / `setLut` | `CameraScreen.kt:480-484` |
| 套用 = 完整风格（含 LUT） | 参数整数组重建 + LUT 走既有 `lutPending` 链路（IO 解析 → `setLut` → 写 `lut_id`） | `CameraScreen.kt:475-477` |
| `[0]/[28]/[30]/[34]/[35]` 仍被 prefs 覆盖层重写 | **一行未改** `CameraController`；带 LUT 时**先写 prefs(`lut_strength`) 再 `setFilmParams`**，让覆盖层读到预设强度 | `CameraScreen.kt:472-475` |
| 选中态有明确标记 | `LutCard(selected = p.id == selectedPreset.id)`（琥珀描边 + 琥珀字，沿用 LUT 卡片语言） | `:2111` |
| 切回「标准」回中性基线 | 标准 = `emptyMap` 稀疏 ⇒ 展开即 `CameraPresets.standard()` | `CameraPresetApply.kt:28-32` |
| 列表显示 名称 + 分组 + 是否含 LUT | `presetSubtitle` = `"柯达 · LUT"` 形态 | `:98`、`CameraScreen.kt:2110` |
| 空列表给人话 | `"还没有预设 · 在编辑器里保存"` | `CameraScreen.kt:2117-2125` |

**与任务书伪代码的一处实现差异（等价，非设计偏离）**：伪代码写 `controller.setLut(entry)`，
实际走既有的 `lutPending = entry` → `LaunchedEffect` 里 `LutLibrary.load`（IO）→ `setLut`。
理由：`LutLibrary.byId` 只给 `Entry`（清单项），引擎要的是解析后的 `CubeLut`，而解析是 IO；
这条链路已经承担了 `lut_id` 持久化与"切镜头后重传纹理"，新开一条同步路径等于把这三件事
再实现一遍。

---

## 3. 单测（用例数 + 运行结果）

```bash
cd "/Users/wl/Downloads/胶片相机"
rm -rf app/build/test-results
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest   # EXIT=0
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew testDebugUnitTest --rerun        # EXIT=0（强制真跑，见下）
```

- **退出码**：两条命令均 `EXIT=0`。
- **首次 `testDebugUnitTest` 是 `FROM-CACHE`**（Gradle 构建缓存命中，不会重写 `test-results/`）⇒
  按任务书"确认 XML 是本次新生成"的要求，追加 `--rerun` **真跑一次**（`1 executed`）。
- **用例数**：`38` 个测试类 / **404 用例 / 0 失败 / 0 错误 / 0 跳过**（`app/build/test-results/testDebugUnitTest/*.xml` 解析）。
- **本卡用例**：`CameraPresetApplyTest` **9/9 通过**，XML 时间戳 = 本次运行（距统计时刻 5 秒）。

| 用例 | 钉住的规格 |
|---|---|
| `standardIsTheNeutralBaseline` | ① 标准逐位 == `CameraPresets.standard()`、长度 96、无 LUT |
| `sparseSlotsOnlyOverrideListedSlots` | ② `{26:55}` ⇒ `[26]=55.0`，其余逐槽 == 基线 |
| `nonZeroIdentitySlotsAreNotClobberedByExpansion` | ② 恒等值为 1.0 的槽（`[1][4][34][69]`）不被零初值打回 |
| `lutWithoutReferenceKeepsCurrentLut` | ③ `lutId == null` ⇒ KEEP（不是"关掉"） |
| `lutInLibraryIsApplied` | ③ 资产在库 ⇒ APPLY |
| `missingLutDegradesToKeepInsteadOfClearing` | ③ **降级分支专属**（mutation 见 §4） |
| `selectionRoundTripThroughPersistedId` | ④ 存→读往返 + **悬空 id 回退标准** |
| `subtitleShowsGroupAndLutPresence` | 列表小字四种组合（含空白分组不算分组） |
| `slotsTextNamesTheOverriddenSlotsWithTheirRealValues` | 日志文本 `contrast=1.2;grain_amount=55.0` == 下发数组的值 |

**编译产物包含本次改动**（不只是"编译通过"）：
- `app/build/tmp/kotlin-classes/debug/.../CameraScreenKt.class` 11:01:36 > 源码 `CameraScreen.kt` 11:00:10；
- APK 11:01:43；`classes4.dex` 内可检出 `applyCameraPreset` / `camera_preset_id` / `builtin_standard` 符号。

---

## 4. Mutation 自检（证明降级用例不是恒真断言）

在隔离副本（§7.1）里把 `CameraPresetApply.kt:73` 的降级分支去掉：

```diff
- return if (lutExists(id)) PresetLutAction.APPLY else PresetLutAction.MISSING
+ return if (lutExists(id)) PresetLutAction.APPLY else PresetLutAction.KEEP
```

```
> Task :app:testDebugUnitTest FAILED
CameraPresetApplyTest > missingLutDegradesToKeepInsteadOfClearing FAILED
9 tests completed, 1 failed          # EXIT=1
```

**恰好 1 条红**（另外 8 条仍绿 ⇒ 断言精确指向降级分支，不是"整类连带失败"）。
恢复后 `BUILD SUCCESSFUL / EXIT=0`，且恢复后的文件与工作区版本 `diff` 为空。

---

## 5. 验收判据逐条证据

### 5.1 判据 1：编译 + 单测退出码 0，用例数
见 §3：`assembleDebug testDebugUnitTest` EXIT=0；`testDebugUnitTest --rerun` EXIT=0；
**404 用例 / 0 失败**，本卡 9 条。

### 5.2 判据 2：参数与预设一致（**未真机验证**）

**离线证据（硬）**：
- `sparseSlotsOnlyOverrideListedSlots` 断言 `presetSnapshot` 展开结果 `[26] == 55.0` 且其余 95 槽逐位等于基线
  —— 而这个数组就是 `controller.setFilmParams(...)` 的唯一实参（`CameraScreen.kt:475`）；
- `slotsTextNamesTheOverriddenSlotsWithTheirRealValues` 断言日志文本里的数值 == 数组里的数值（证据链闭合）。

**运行期证据（需真机执行，本次无设备）**：套用时记一条 `camera.preset.apply`：

```
app  camera.preset.apply  {"id":"p_…","name":"午后暖调","slots":2,
                           "slotsText":"contrast=1.2;grain_amount=55.0",
                           "lut":"kodak_portra_400","lutAction":"APPLY"}
```

`slotsText` 的键是 `FilmParamKeys` 的**语义名**（与 P-1 线格式同一维护点），值是下发值本身。

**为什么没有更强的"引擎回读"**：`CameraController.filmParams` 是私有数组，仓库里**没有**参数回读的
JNI（`EngineBridge` 只有 `nativeEngineGetStats` / `nativeRecentLogs`），本卡也不改引擎接口。
`adb devices` 本机为空 ⇒ 无法安装实测。**未真机验证**；真机验证方式：

```bash
adb logcat -d | grep camera.preset.apply                  # 参数与预设是否一致
adb shell run-as com.filmkit.app cat /data/data/com.filmkit.app/shared_prefs/filmkit.xml \
  | grep -E "camera_preset_id|lut_strength|lut_id"        # 选中态与 LUT 是否落盘
```

### 5.3 判据 3：LUT 缺失降级有单测 + mutation 自检
见 §3 表 + §4（去掉分支 ⇒ 精确 1 条红 ⇒ 恢复）。

### 5.4 判据 4：冷启动后选中态保持（**未真机验证**）

- **实现**：`prefs("camera_preset_id")` = 预设 id（**「标准」= 键不存在**，与既有 `lut_id` 的
  "null ⇒ remove" 哨兵约定同款，未新造一套）；初值 `resolveCameraPreset(prefs.getString(...), presets)`
  用 `remember`（非 saveable）+ prefs 回落 —— 即 ADR §5.2 G-1 那条教训的正确写法（与 `photoUri` 同款）。
- **单测**：`selectionRoundTripThroughPersistedId` 覆盖"标准 / 命中 / 悬空"三态往返。
- **未真机验证**：无设备（`adb devices` 空）。验证方式见 §5.2 的 `run-as … grep camera_preset_id`：
  杀进程重进后该键应仍在，且面板顶行显示同名预设。

---

## 6. 未做 / 存疑项

### 6.1 本卡明确不做（按任务书 §4）
预设强度滑杆、内置胶片预设包（P-2b）、拍照界面其它 UI（取景 / 快门 / 胶卷位）、引擎与 GLSL（零改动）。

### 6.2 存疑项（请架构师复核）

1. **★ 选「标准」时 LUT 保留**：标准 `lutId == null` ⇒ 按"缺失 = 保留当前值"**不动 LUT**。
   于是从「Portra 400（带 LUT）」切回「标准」，参数回到中性基线但**LUT 还在**。
   这是裁决 A4 的直接推论（我按任务书执行），但与"切回标准 = 回到中性基线"的直觉有落差。
   若要"选标准 = 一并清 LUT"，是一行改动（标准分支显式 `setLut(null)` + 清 `lut_id`）——请拍板。
2. **与编辑器侧的口径差异**：编辑器 `applyPreset` 在"LUT 已不在库"时**清空 LUT**，
   拍照侧按任务书**保留**。两处行为不同，确认是有意为之（我按任务书写了注释说明）。
3. **手动改 LUT 不清除预设选中标记**：与编辑器"手动改参数不清 `appliedPreset`"一致（裁决 A4 的
   "套用 = 一次性写入快照，之后手动改不回写预设"）。若希望"手动选 LUT = 退出预设"，需另加逻辑。
4. **`CameraPresets.names` / `byIndex` 已成死代码**（无调用点，`names` 仍是 `listOf("标准")`）。
   本卡未删（避免与 P-2b 抢文件）——建议 P-2b 或收尾卡清理，否则下一个人会误以为它是数据源。
5. **预设的 `[28]` 影调强度在拍照侧不生效**：被 `applyTonePref` 从 prefs 覆盖（既有设计，任务书确认）。
   即预设的"影调"维度在拍照侧实际由用户的影调开关决定。
6. **预设列表的刷新时机**：`PresetStore.load` 在 `CameraScreen` 进入组合时读一次。从编辑器返回
   （路由切回 `camera`）会重新组合 ⇒ 自动刷新 ✓；但若将来在别处改预设而不离开拍照页，不会自动刷新。

---

## 7. 外部阻塞记录（运行期一度存在，**已由对方修复**；记录备查）

### 7.1 P-3 并发中间态导致工作区无法编译
10:59–11:01 期间工作区 `compileDebugKotlin` 失败于 **P-3 正在改的文件**（非本卡）：
- `PhotoViewScreen.kt:215` `onEditWithPreset: (Uri, String) -> Unit = {}` ⇒ lambda 参数数量不匹配；
- `PhotoViewerModel.kt:205` `Unresolved reference 'EditorPreset'`（缺 import）；
- 测试侧 `PhotoViewPresetTest.kt` 引用尚未落地的 `initialPresetOf`。

**我没有改这些文件**（纪律）。隔离验证办法：`/tmp/fk-p2a` 副本 = 工作区源码
**但把 P-3 的 4 个文件回退到 `HEAD`**（`MainActivity.kt` / `EditorScreen.kt` / `PhotoViewScreen.kt` /
`PhotoViewerModel.kt`）+ 删除其未跟踪测试文件，保留 P-1 的 `EditorExport.kt` 与 P-1 测试。
副本结果：`compileDebugKotlin` EXIT=0；`testDebugUnitTest` **387 用例 / 0 失败 / 0 跳过**，
本卡 9 条全绿（§4 的 mutation 自检也在这个副本里做）。

### 7.2 最终证据以**工作区**为准
P-3 修复后，在工作区复跑：`assembleDebug testDebugUnitTest` EXIT=0、
`testDebugUnitTest --rerun` EXIT=0、**404 用例 / 0 失败**、APK 内含本次符号（§3）。
副本仅用于绕过阻塞期间的中间态，**不是**交付口径。

### 7.3 另一处并发提示（给 P-2b / 架构师）
`app/src/main/java/com/filmkit/app/camera/BuiltinPresets.kt`、`app/src/main/assets/presets/` 在本次
交付期间出现（P-2b 已开工）。P-2b 若要往拍照界面预设列表里塞内置包，**请基于本卡的
`LutSheet(presets=…, selectedPreset=…)` 形态继续**（列表已按 id 判选中，加内置项只需扩展
`listOf(CAMERA_STANDARD_PRESET) + presets` 这一行）。
