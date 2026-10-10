# U-7 报告：预设曝光的「偏移」语义（方案 B）

- **基线 HEAD**：`8367e6b`（改动留在工作区，**未 commit** —— 按任务书交付方式）
- **用户拍板**（2026-10-11 原话）：**"B（我推荐）预设的曝光 = 相对你当前曝光补偿的偏移（拍前能看到效果，又不冲掉你正在调的曝光）"**
- **依据**：`docs/reports/preset-DESIGN.md` §9.2 第 1 行（曝光 `[0]` 是唯一一个语义天然冲突的参数）+ §9.4 方案 B
- **范围**：拍照侧 `FilmParams[0]` 的预设曝光语义。**只改 Kotlin**：3 个源文件 + 1 个测试文件
- **没动**（逐条核对见 §6）：`EditorScreen.kt` / `PhotoViewScreen.kt` / `PresetScope.kt` / `EditorExport.kt` 的预设数据层 / 引擎（`core/`）

## 0. 结论摘要

1. **语义四条逐条落地**（§1）：拍照侧 `[0] = base_ev + 用户 EV + 预设偏移`；编辑器侧 `[0]` 一字未动（仍是后期曝光，直接生效）。
2. **偏移的载体 = 显式参数 + Controller 字段**，冷启动复用既有 `camera_preset_id` 链路 ⇒ **不需要新的 prefs key**（§2.3），预设被删/悬空 ⇒ 偏移自动归零。
3. **三处 `[0]` 算式收敛到一个函数**（`exposureEv()`）：套用预设 / 用户拖 EV / **高光保护判据的"将要施加的提亮"**（漏这一处会让成片高光裁切，§2.2）。
4. **不踩既有三个坑**（§2.1 / §3.3）：与 ADR §5.1 **I-5 引用赋值隐患解耦**（偏移来源不是被覆盖的数组槽）；E2-21 的"编辑器全恒等基线"不涉及；`filmParamsForStill()` 只加高光保护欠曝量，**偏移不会被算两次**。
5. **测试**：新增 4 条纯逻辑用例（`CameraPresetApplyTest` 11 → 15），覆盖 ①含曝光取偏移 ②不含 ⇒ 0 ③切预设/切标准 ⇒ 归零 ④用户调 EV ⇒ 叠加保留。
6. **构建 + 单测真跑**（`--rerun-tasks`，非 FROM-CACHE）：**BUILD SUCCESSFUL / 46 actionable tasks: 46 executed / 425 tests / 0 failures / 退出码 0**（§4）。
7. **Mutation 自检 2 项**：M1 去掉偏移叠加 ⇒ 1 用例红；M2 偏移恒为 0 ⇒ 2 用例红（均退出码 1，已还原）。
8. ⚠ **未真机验证**（无设备）—— 真机步骤 + "怎么造一个带曝光的用户预设"见 §5。

## 1. 语义四条 → 代码落点

| # | 要求 | 实现 | 位置 |
|---|---|---|---|
| 1 | **偏移的载体**：套用预设时把该预设的 `[0]` 记成"当前预设曝光偏移" | `presetEvOffset(p.slots)` 提取 → 作为第二个实参传给 `setFilmParams` → 存入 `CameraController.presetEvOffset` | `CameraScreen.kt:509`、`CameraScreen.kt:517`、`CameraController.kt:1078`（签名）、`CameraController.kt:1093`（赋值）、`CameraController.kt:1142`（字段） |
| 2 | **改变换位置**：在 `applyBaseEvPref` 之后把它加进去（不是写进数组再被覆盖层丢掉） | `filmParams[0] = exposureEv()`，其中 `exposureEv() = effectiveExposureEv(baseEvPref, evSteps*evStep, presetEvOffset)` | `CameraController.kt:1099`、`CameraController.kt:1146`、`CameraPresetApply.kt:84` |
| 3 | **用户调曝光补偿时偏移保留**（叠加而非覆盖） | `setEv()` 也走 `exposureEv()`（原来直写 `base + user`，会把偏移抹掉） | `CameraController.kt:647` |
| 4 | **切到不含曝光的预设 / 切回「标准」⇒ 偏移归零** | `presetEvOffset(slots) = slots[0] ?: 0.0`（缺失 ⇒ 0）+ `setFilmParams` 里**无条件** `presetEvOffset = evOffset`（不是"有值才更新"） | `CameraPresetApply.kt:71`、`CameraController.kt:1091-1093` |

纯逻辑层（JVM 可测，`CameraPresetApply.kt`）：

```kotlin
internal fun presetEvOffset(slots: Map<Int, Double>): Double = slots[0] ?: 0.0          // :71
internal fun effectiveExposureEv(baseEv: Double, userEv: Double, presetOffset: Double)   // :84
    : Double = baseEv + userEv + presetOffset
```

**编辑器侧 `[0]` 完全未动**：本卡没有碰 `EditorScreen.kt` / `EditorScreen.applyPresetAt` / `EditorExport.presetSnapshot` 的任何一行
⇒ 编辑器链路上 `[0]` 仍是"后期曝光、直接生效"（§9.4 的两侧含义一致性由此成立：两侧都是"相对当前画面再亮/暗多少 EV"，
只是拍照侧叠加在用户的曝光补偿之上）。

## 2. 三个实现决策（为什么不是别的写法）

### 2.1 偏移来源 = 显式参数，**不读 `p[0]`**（避开 I-5）

`setFilmParams` 是 `filmParams = p` 的**引用赋值**，随后原地写 `filmParams[0]`（= 写调用方的数组）——
ADR §5.1 **I-5** 已记录该隐患（当前安全：`cameraPresetParams`/`standard()` 每次返回新数组，无缓存）。

如果偏移从 `p[0]` 反推，就会把这个隐患**引进来**：调用方一旦复用/缓存数组，第二次套用会把
"上次算出的绝对值（base+user+offset）"当成新偏移 ⇒ 曝光逐次翻倍（正是 I-5 描述的症状）。

因此 `setFilmParams(p: DoubleArray, evOffset: Double)` 的偏移来自**预设定 slots 的显式参数**，
与数组槽完全解耦；且**故意不给默认值** ⇒ 漏传 = 编译错误，而不是静默丢掉预设曝光（当前仅 2 个调用点，都已改）。

### 2.2 写 `[0]` 与"将要施加的提亮"必须同源

拍照侧 `[0]` 有三个消费者，全部收敛到 `exposureEv()`：

| # | 调用点 | 漏掉偏移的后果 |
|---|---|---|
| ① | 套用预设 `setFilmParams`（`CameraController.kt:1099`） | 预设曝光静默失效（本卡修的原始缺陷） |
| ② | 用户拖 EV `setEv`（`CameraController.kt:647`） | 用户一动曝光，预设偏移就被抹掉（= 覆盖而非叠加） |
| ③ | **高光保护判据** `hlCompEv`（`CameraController.kt:2014`） | 这是"将要施加给成片的提亮"，少算预设那部分 EV ⇒ 判据放行 ⇒ **成片高光裁切** |

`filmParamsForStill()`（`CameraController.kt:184`）保持 `q[0] = filmParams[0] + u` 不变 ——
偏移已经在 `filmParams[0]` 里，再叠一次就是成片曝光算错（已加注释钉住）。

### 2.3 冷启动：复用 `camera_preset_id`，不加 prefs key

链路（无新状态、无新键）：

```
prefs["camera_preset_id"] → resolveCameraPreset(...)      // 悬空 id ⇒ 「标准」
  → selectedPreset → presetEvOffset(selectedPreset.slots)  // 缺失/标准 ⇒ 0.0
  → setFilmParams(cameraPresetParams(selectedPreset), 偏移) // CameraScreen.kt:560
```

为什么不在 prefs 里单存一个 `camera_preset_ev`：那是**第二个真源**，要与 `camera_preset_id` 手工同步
（切预设、预设被删、编辑器改了预设内容三种情况都要跟着改），漏一个就是"预设没了但曝光还在"的脏状态。
现在偏移**由预设本身派生** ⇒ 偏移与预设同生共死，天然满足"切预设/切标准归零"。

## 3. 既有机制逐条核对（任务书点名的三个坑）

| # | 坑 | 结论 |
|---|---|---|
| 1 | `setFilmParams(p)` 引用赋值（I-5） | **未引入新隐患**：偏移来源是显式参数，不读被覆盖的 `p[0]`（§2.1）。I-5 本身**未改**（`filmParams = p` 保持原样 —— 修它是另一张卡的事，本卡不动） |
| 2 | E2-21「编辑器打开 = 全恒等基线」 | 与拍照侧无关，未触碰编辑器任何代码 |
| 3 | `filmParamsForStill()` 的高光保护欠曝量 | `q[0] = filmParams[0] + u` **不变**（偏移已在 `filmParams[0]`）⇒ 成片曝光不会被加两次；反而修好了高光保护判据漏算预设 EV 的问题（§2.2 ③） |

## 4. 构建 / 单测 / Mutation 证据

### 4.1 真跑（`--rerun-tasks`，规避 FROM-CACHE 假绿）

```
$ rm -rf app/build/test-results
$ JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest --rerun-tasks <39 个测试类>
BUILD SUCCESSFUL in 17s
46 actionable tasks: 46 executed          ← 无 FROM-CACHE，全量执行
GRADLE_EXIT=0
FINAL classes=39 tests=425 failures=0 errors=0 skipped=0
产物：app/build/outputs/apk/debug/app-debug.apk
```

- **基线**（任务书给）：420 用例。本卡 +4（纯逻辑用例）⇒ 424；本次实跑 425，差额来自**其它卡同期新增**的用例。
- 本卡测试类 `CameraPresetApplyTest`：**15 用例全绿**（原 11 + 新 4，XML 已核对用例名）。
- ⚠ **验收运行排除了 `PresetDetailTest`**：见 §4.3（他人卡的红，与本卡无交集）。

### 4.2 Mutation 自检（每项都改代码 → 期望红 → 已还原）

| # | 变异 | 结果 |
|---|---|---|
| **M1** | `effectiveExposureEv` 里去掉 `+ presetOffset`（= 旧行为"偏移被覆盖层丢弃"） | `CameraPresetApplyTest > userEvAddsOnTopOfPresetOffset` **FAILED**；`15 tests completed, 1 failed`；退出码 **1** |
| **M2** | `presetEvOffset` 恒返回 `0.0`（= 旧行为"预设曝光读不出来"） | `presetWithExposureYieldsThatOffset` + `switchingToExposureFreePresetOrDefaultZeroesTheOffset` **FAILED**；`15 tests completed, 2 failed`；退出码 **1** |
| 还原后 | 只跑本卡测试类 | `BUILD SUCCESSFUL`，退出码 **0**；`grep MUTATION` 无残留 |

### 4.3 与其它卡的红：`PresetDetailTest.detailLabelsMatchScopeNoticeWording`

全量跑（含 `PresetDetailTest`）为 **436 tests / 1 failed**，唯一失败：

```
PresetDetailTest > detailLabelsMatchScopeNoticeWording FAILED (PresetDetailTest.kt:216)
  java.lang.AssertionError: 槽 23 应在某条链路上被标为不生效（P-5 表驱动的前提）
```

**与本卡无交集**，证据链：
1. 断言对象是 `[23]`（分离色调）在 `PresetScope` 提示表里的存在性，与 `[0]`/曝光/预设套用路径无关；
2. `PresetScope.kt:61-63`（**U-1 卡**的改动）已把 `[23..25]` 从 `SLOT_LABELS` 删除（理由写在注释里："U-1 已接线 ⇒ 两侧都生效 ⇒ 留着就是死条目"），
   而 `PresetDetailTest.kt:211`（**U-6a 卡**新增的未跟踪测试文件）仍在断言这三个槽必须出现在提示里 ⇒ **两张卡的口径冲突**；
3. 本卡 `git status` 只碰 4 个文件（§6），未触碰 `PresetScope.kt` / `PresetDetail.kt` / `PresetDetailTest.kt`。
   ⇒ **需要 U-1 与 U-6a 的卡主对齐**（U-7 不越界改）。

> 环境注记：验收时段有其它卡并发跑 gradle（本卡每次运行前都用 `pgrep GradleWrapperMain` 等待，串行执行）；
> 中途 `app/build/test-results/` 曾被对方 `rm -rf` 清掉一次，因此本卡的关键结果已复制到 `/tmp/u7-final/`、`/tmp/u7-evidence-all/` 留存。

## 5. ⚠ 未真机验证 + 验证步骤

**本卡未做真机验证**（无设备可用）。以下判据全部由纯逻辑单测 + 代码走查支撑，**观感/成片亮度需真机确认**。

### 5.1 先造一条"带曝光"的用户预设（内置三条都不含 `[0]`）

已核对 `app/src/main/assets/presets/builtin.json`：三条内置（`builtin_leica_m9` / `builtin_ricoh_negative` / `builtin_leica_bw`）
的 `slots` **全为空** ⇒**内置预设永远走偏移 0 的路径**（本卡对它们是零行为变化），真机验证必须自己造一条：

1. 打开编辑器（任选一张照片进入）→ 「调整」组 → **曝光**滑杆拖到 **+1.0**（编辑器里的曝光是后期曝光，基线 0 = 恒等）；
2. 点「保存当前」→ 命名如 `U7测试+1EV` → 保存；
3. 该预设线格式里会写下 `"params": {"exposure_ev": 1.0}`。判据：
   - 槽 0 **参与预设**（`EditorExport.kt:1654 presetSlotIncluded(0) == true`，不在任何排除集里）；
   - 保存时按"恒等值不入库"（`EditorExport.kt:1891`），`neutral[0] = 0.0` ⇒ 非 0 的曝光一定会被记下；
   - 保存后引擎日志 `preset.save` 的 `slots` 数应 ≥ 1。

> 想造 −1EV 的版本同理（滑杆往负方向），用于验证负向偏移。

### 5.2 真机验证步骤（判据逐条）

| 步 | 操作 | 期望（判据） |
|---|---|---|
| 1 | 拍照页把用户曝光补偿 EV 归到 **0.0**，先选「标准」 | 记下当前画面亮度 = 基准 |
| 2 | 选 `U7测试+1EV` | 预览**当场变亮约 1EV**（"拍前能看到效果"）；日志 `camera.preset.apply` 里 **`evOffset=1.0`** |
| 3 | 把用户 EV 拖到 **−1.0** | 画面回到第 1 步的基准亮度（`0.7 + (−1.0) + 1.0 = 0.7`）⇒ 证明是**叠加**而不是覆盖（若是覆盖，画面会比基准暗 1EV） |
| 4 | EV 拖回 0.0，再切到**不含曝光**的预设（任一条内置） | 亮度**回落**到 `base + user`（预设那 1EV 消失）⇒ 证明切预设归零、不残留 |
| 5 | 再切回「标准」 | 同上，偏移保持 0；LUT 也一并被清（P-2c 裁决 ①，不受本卡影响） |
| 6 | 重新选中 `U7测试+1EV` → **杀进程重开 App** | 相机页亮度与杀进程前一致 ⇒ 冷启动偏移仍生效（§2.3 链路） |
| 7 | 拍一张成片，与预览对比 | 成片中间调与预览一致（`filmParamsForStill` 只加高光保护欠曝量，偏移不会算两次） |
| 8 | 把该预设删掉 → 回拍照页 | 选中项回落「标准」（`resolveCameraPreset`）⇒ **偏移归零**，不残留 +1EV |

日志字段（`app/src/main/java/com/filmkit/app/ui/CameraScreen.kt:541`）：`camera.preset.apply` 现带
`evOffset=<预设偏移>`，可与 `slotsText` 里的 `exposure_ev=<值>` 逐条对拍。

## 6. 纪律核对（改了什么 / 没碰什么）

**本卡改动的文件（`git diff` 逐条核对过，只这 4 个）**：

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/filmkit/app/camera/CameraPresetApply.kt` | +`presetEvOffset` / `effectiveExposureEv`（纯逻辑，含 KDoc）+ `cameraPresetParams` KDoc 补 U-7 口径 |
| `app/src/main/java/com/filmkit/app/camera/CameraController.kt` | `setFilmParams(p, evOffset)` + `presetEvOffset` 字段 + `exposureEv()` + 三处算式收敛 + 注释 |
| `app/src/main/java/com/filmkit/app/ui/CameraScreen.kt` | 2 处调用传偏移 + `evOffset` 日志字段 + KDoc |
| `app/src/test/java/com/filmkit/app/camera/CameraPresetApplyTest.kt` | +4 用例、类 KDoc 第 5 条 |

**未碰**（任务书红线）：`EditorScreen.kt` / `PhotoViewScreen.kt` / `PresetScope.kt` / `EditorExport.kt`（预设数据层）/ `core/`（引擎）/ `EditorEngineSession.kt`。
`git status` 里的其它改动（`PresetScope.kt`、`PresetDetail.kt`、`EditorScreen.kt`、`FrameRenderer.*` 等）属于并发的其它卡。

## 7. 留给架构师的两点

1. **`PresetDetailTest` 的红**（§4.3）是 U-1 与 U-6a 的口径冲突，不由 U-7 修 —— 建议让两张卡对齐后再跑一次全量。
2. **`[0]` 偏移的 UI 可见性**：当前用户只能"从画面亮度"感知预设带的曝光（本卡按任务书范围未加 UI 文案）。
   若要显式提示（如预设卡片小字 `+1EV`），是独立小卡：`presetSubtitle` 加一段即可。
