# P2 卡报告：默认影调引擎 filmic → sigmoid

**卡片**：实现卡 P2（依据 `docs/rec2020-heif-design.md` §1b P2 行，用户 2026-10-14 拍板）
**纪律**：改动最小化 = 两个默认值 + 读取缺省一致性 + 测试口径；`app/src/main/assets/presets/builtin.json` **零改动**
**版本**：0.11.32（381）→ **0.11.33（382）**
**出包**：`filmkit-0.11.33-DEFAULT-SIGMOID.apk`（项目根目录，42,210,092 B，versionCode=382/versionName=0.11.33 经 `aapt2 dump badging` 核对）

---

## 0. 结论（TL;DR）

| 项 | 结果 |
|---|---|
| Kotlin 默认模板 `[98]` | **1**（`CameraPresets.DEFAULT_ENGINE_SLOT`，`neutral()`/`standard()`） |
| C++ `FilmParams` 结构体默认 | **1**（`float engine_slot = 1.0f;`，全文件唯一一处默认值定义） |
| 缺 `[98]` 的**读取缺省**（3 处：拍照下发前 / 设置页抽屉 / 一级预设页） | **1**（同一个常量，不再硬编码 0） |
| `[98] = 0`（filmic） | 仍是**合法档位**；逐字节零回归判据保留（判据 2 守卫，未删） |
| 内置预设 `builtin.json` | **零改动**（`git status` 未列出该文件） |
| 判据 1 全量单测 | **672 tests / 0 failures / 0 errors / 0 skipped** |
| 判据 2 引擎 0 离线零回归 | `film_d5.npy` crc32 **`0x589f8364`** ✔ · 拼接 shader crc32 **`0x4fa1ebd3`** ✔ |
| 判据 3 引擎 1 基线 | `film_d5_sigmoid.npy` crc32 **`0x7c2646ef`** ✔（未变） |
| 判据 4 位深单一真源 | **PASS**（RC=0，冻结计数未变） |
| 判据 5 shader 校验 / 诊断字段 | `validate-shaders.sh` **全部通过**（RC=0）· `check-diag-fields.py` **全部通过**（RC=0） |

---

## 1. 改动落点（文件:行）

### 1.1 生产代码（本卡的全部行为改动 = 两个默认值 + 三处读取缺省）

| 文件:行 | 改动 | 理由 |
|---|---|---|
| `app/src/main/java/com/filmkit/app/ui/CameraPresets.kt:38` | 新增 `const val DEFAULT_ENGINE_SLOT = 1` | "缺 `[98]` 时按哪一档解释"的**单一真源**（Kotlin 侧） |
| `app/src/main/java/com/filmkit/app/ui/CameraPresets.kt:330` | `this[98] = 0.0` → `DEFAULT_ENGINE_SLOT.toDouble()` | 本卡核心：默认参数模板切 sigmoid |
| `core/engine/src/film/FilmParams.h:518` | `float engine_slot = 0.0f;` → `1.0f;` | 本卡核心：C++ 结构体默认（缺槽时的行为） |
| `core/engine/src/film/FilmParams.h:507-517 / 647-651` | 注释改准（默认档 = 1；缺槽语义 = 默认档） | 旧注释写着"默认、兼容档 = 0" |
| `app/src/main/java/com/filmkit/app/camera/EditorExport.kt:135` | `else 0` → `else CameraPresets.DEFAULT_ENGINE_SLOT` | 短数组（历史快照）读不到 `[98]` 时，与 C++ `fromDoubles` 同值 |
| `app/src/main/java/com/filmkit/app/ui/SettingsScreen.kt:505` | `?: 0` → `?: CameraPresets.DEFAULT_ENGINE_SLOT` | 设置页抽屉读预设**稀疏** slots；内置预设缺键 ⇒ 原会显示"引擎 0"而实际渲染引擎 1（假读数，卡 C P2 同型） |
| `app/src/main/java/com/filmkit/app/ui/CameraPresetPanel.kt:1301` | `getOrElse(ENGINE_SLOT) { 0.0 }` → `{ DEFAULT_ENGINE_SLOT.toDouble() }` | 一级预设页同上 |
| `docs/engine-slots-design.md:174-187` | 槽位注释 + 旧预设缺省语义改准 | 过期说明（"0 = …默认"） |

**未改动（明确核实过）**：`EngineRegistry.*`、`SigmoidParams.*`、`filmSlices()`、`FrameRenderer.cpp` 的选路逻辑、`EngineScope.NEUTRALIZE` / `FILMIC_OWNED_SLOTS` / `KNOWN_ENGINE_SLOTS`、`builtin.json`、`OutputBitDepth.h`、`EngineBridge.kt` 的 `OUTPUT_BITMAP_CONFIG`、`CameraController.kt` 的输出落点。
`FrameRenderer.h:1122` 的 `film_engine_slot_ = 0` 是"**当前已编译的 program 是哪个**"的成员状态（不是参数默认值）：首帧 `p[98]=1 ≠ 0` ⇒ 触发 `buildFilmProgram(1)`（`FrameRenderer.cpp:14001-14013`），保持 0 才是正确的初始状态，故不动。

### 1.2 测试口径（改 vs 新增，全部是"按新语义更新"而非删除）

| 文件:行 | 处置 |
|---|---|
| `ui/CameraPresetsTest.kt:50` | **改**：`stage0EngineSlotDefaultsToBuiltinFilmic` → `defaultEngineSlotIsSigmoidAndFilmicStaysAValidSlot`（断言 = 1 + `[98]=0` 仍是注册档位） |
| `camera/EngineScopeTest.kt:74` | **新增**：`stockDefaultTemplateIsEngineOneAndTakesTheBypassPath`（出厂模板未改任何槽 ⇒ 直接走旁路） |
| `camera/EngineScopeTest.kt:284` | **改**：短数组 ⇒ 按**默认档**解释并走旁路（原：视作引擎 0、同实例） |
| `camera/EngineOverrideTest.kt:204` | **新增**：`theThreeOverrideLevelsOnTheStockDefaultTemplate`（跟随 = sigmoid / 强制 0 = filmic 且参数不归零 / 强制 1 = 与跟随逐位相同） |
| `camera/EngineOverrideTest.kt:225` | **改**：短数组三档语义（含 `[98]` 不在长度内 ⇒ 只复制不越界） |
| `camera/Stage3EngineSlotPresetAuditTest.kt:62` | **改**：缺 `engine_slot` ⇒ 落**默认档 1**；并新增反向"filmic 照片不被拽回默认档" |
| `camera/Stage3EngineSlotPresetAuditTest.kt:110` | **新增**：读**真实** `builtin.json` 三条 ⇒ 均无 `[98]`、展开后 `[98] = 1` |
| `camera/Stage3EngineSlotPresetAuditTest.kt:131` | **新增**：`FilmParams.h` 的 `float engine_slot = …f;` 与 Kotlin 常量**跨文件对拍** + 默认值定义唯一性 |
| `camera/Stage3EngineSlotPresetAuditTest.kt:162` | **改**：稀疏往返探针由 `[98]=1` 改为 `[98]=0`（非默认档才入库） |
| `camera/Stage3EngineSlotPresetAuditTest.kt:182` | **改**：语义翻转 —— `engine_slot:1`（默认档）不入库；`engine_slot:0`（filmic）**必须入库** |
| `camera/Stage3EngineSlotPresetAuditTest.kt:264` | **改**：正对照显式写 `[98]=0`（原依赖 `neutral` 的默认 0） |
| `camera/Stage3EngineScopeAuditTest.kt:439` | **改**：默认档不入稀疏集；非默认档（0）入库 |
| `ui/EngineSlotUiTest.kt:262` | **改**：选择面探针由 1 改 0（默认档 1 写 1 = "无改动"，原用例会假绿成空集） |
| `ui/EngineSlotUiTest.kt:258` | **改**：措辞"引擎 0（默认档）"→"引擎 0（合法档位）" |

**保留的守卫（判据 2 的安全网，未删未弱化）**：`EngineScopeTest.slotZeroReturnsSameInstanceWithEveryScopedSlotUntouched`、`Stage3EngineScopeAuditTest.slotZeroIsTheOnlyIdentityPathAndReturnsTheSameInstance`、`EngineOverrideTest.followPresetWithFilmicPresetReturnsSameInstance`、`forceFilmicRewritesSlot98AndKeepsEverySlot`，以及 §3 判据 2 的离线链 crc32。

---

## 2. 预设缺 `engine_slot` 键的行为（实测结论）

**结论（三层口径，均已实测/对拍）**：

1. **机制层**（`presetSnapshot(base, slots)`，`EditorExport.kt:2272`）：**缺失 key = 保留 `base` 数组在该槽的当前值**（RawTherapee 口径）。它既不是"重置为默认值"、也不是"保留 UI 上一次的状态"，而是"保留调用方传进来的那份数组"。
2. **拍照链路**（内置预设的实际使用路径，`cameraPresetParams`，`CameraPresetApply.kt:116-127`）：`base` **恒为** `CameraPresets.standard()`（每次新建）⇒ 缺键 ⇒ **取默认档 = `[98] = 1` = sigmoid**。即：对内置/老预设，**最终效果 = 重置为默认档**。
3. **编辑器链路**（`EditorScreen.kt:832` 的 `presetSnapshot(base, …)`，`base` = 照片**套用前**的参数快照）⇒ 缺键 ⇒ **保持该照片拍摄时的引擎**（旧 filmic 照片套预设仍是 filmic，不因套预设而换引擎；这是有意的既有语义）。
4. **C++ 侧**（`Bridge.cpp:244-250` 的 `paramsFromJava` 每次构造新 `FilmParams`）⇒ 数组长度 ≤ 98（旧快照 / 旧预设）时 `engine_slot` = 结构体默认 = **1** ⇒ 同样是"**重置为默认档**"（不存在"保持上一次引擎"的状态；`film_engine_slot_` 只记已编译的 program）。

**"两种情形下结果都是 1"的理由**：两种情形都收敛到"**默认档**"这一个概念 ——
- "重置为默认值"分支（拍照链路 / C++ 缺槽）：默认值本身就是 1；
- "保持当前值"分支（编辑器链路 / `presetSnapshot` 语义）：被保持的对象也是**默认档产出的数组**（`CameraPresets.standard()` 是拍照侧一切数组的基线；照片元数据里的 `[98]` 在 P2 之后默认写 1）。
⇒ 唯一天然例外是"用户**显式**选了 filmic 的照片/预设"：那一路 `[98] = 0` 是**数据**，两条链路都原样保留（这正是 `[98]=0` 零回归守卫存在的意义）。

**内置预设实测**（`Stage3EngineSlotPresetAuditTest.builtinPresetsWithoutTheEngineSlotKeyExpandToTheDefaultEngine`，读真实资产）：`builtin_leica_m9` / `builtin_ricoh_negative` / `builtin_leica_bw` 三条 **均不含 `[98]`**，`cameraPresetParams(preset)[98] == 1`。`builtin.json` 文件本身 `git status` 无改动。

---

## 3. 五条硬判据实测值

### 判据 1 · 全量单测
```
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest
⇒ BUILD SUCCESSFUL（RC=0）
⇒ app/build/test-results/testDebugUnitTest/*.xml 汇总：tests=672 failures=0 errors=0 skipped=0
```
（改默认值后**首轮**实测为 `668 tests completed, 9 failed` —— 9 条全是"钉住旧默认档"的断言，已逐条按新语义更新，见 §1.2；本轮 672 = 668 + 4 条新增断言。）

### 判据 2 · 引擎 0 离线零回归
```
python3 tools/engine-shader-offline.py --dng test_temp/FILMKIT_20261009-090803.dng \
  --roi 1800,2100,600,1000 --out /tmp/p2-e0 --engine 0
⇒ FILM 段（引擎 0 / filmic）：film_d5.npy crc32 = 0x589f8364   ← 要求值 ✔
                                  sha256 = ce1c9a90c11dbb69278593cd20854970c7a99fc2bf0ee9da90fc10e3136e5391
  拼接 shader crc32 = 0x4fa1ebd3                                ← 要求值 ✔
                                  sha256 = f1ebb3178bfb985ed3e0ca19fef95341cfe3be401cb2cc73ea8ae3377c13563c
  uniform 写入 36 个；跳过 0 个
```

### 判据 3 · 引擎 1 基线（未因本卡改动而变化）
```
python3 tools/engine-shader-offline.py --dng test_temp/FILMKIT_20261009-090803.dng \
  --roi 1800,2100,600,1000 --out /tmp/p2-e1 --engine 1
⇒ FILM 段（引擎 1 / sigmoid）：film_d5_sigmoid.npy crc32 = 0x7c2646ef   ← 要求值 ✔
                                  sha256 = c166153c1dc2196619218781f89eaaff006f7309f1c1f4e0ba1d2f51067935ce
  uniform 写入 21 个；跳过 22 个（uFilmic*×20 + uGamut + uSigBlackTarget）
```
> 说明：离线脚本是 shader/数学链的 Python 复刻，本身不读 `FilmParams.h`；引擎 1 crc32 未变即证明**我改的两个默认值没有触碰渲染路径**（引擎 1 的均匀量仍由脚本默认 contrast=1.5 决定）。

### 判据 4 · 位深单一真源守卫（另一张卡的资产，未触碰）
```
python3 tools/check-bitdepth-single-source.py
⇒ 位深单一真源：bit=8 max=(1<<bit)-1 inv=1/max glsl=255.0 GL=(GL_RGBA8,GL_RGBA,GL_UNSIGNED_BYTE)
⇒ PASS：真源自洽 · shader dither 锚定 · 输出落点全部引用真源 · Kotlin 同源 · 冻结计数未变
RC=0
```

### 判据 5 · shader 校验 + 诊断字段
```
bash tools/validate-shaders.sh
⇒ … OK shader_kFsFilm.frag [kFsFilm（引擎 0 filmic，整份）]
     OK shader_kFsSigmoid.frag [kFsSigmoid（引擎 1 sigmoid，head+def+mid+main+tail 拼回整份）]
⇒ 全部 shader 校验通过（含引擎 1 sigmoid）   RC=0

python3 tools/check-diag-fields.py
⇒ … 全部通过   RC=0
```
额外（不在清单内，但本卡改了 `FilmParams.h`，顺手跑）：`python3 tools/check-filmparams-contract.py` ⇒ **PASS**（`kCount=99` / 声明覆盖 98 槽 / `CameraPresets.COUNT=99` / 语义名表 98 项 四处一致，RC=0）。

---

## 4. 未覆盖项 / 风险

1. **无真机验证**：本机 `adb` 不在 PATH、无可用设备 ⇒ "默认档真的在真机上走 `engine.slot_built {"slot":1,…}`"只能由装机后自证（用户侧一步：安装 APK 拍照后看 `engine.slot_built` 日志）。单测与离线链已覆盖到"参数组装出口"这一层。
2. **UI 侧的"假读数"根因已修但未截图验证**：三处读取缺省改为同一常量后，逻辑上不再分叉（`Stage3EngineSlotPresetAuditTest` 跨文件对拍 C++/Kotlin），但 Compose 界面的实际显示（设置页抽屉显示"引擎 1"）未做 UI 层快照/仪器测试（本仓库无 instrumentation 测试基建）。
3. **编辑器"套预设不改引擎"这条语义的可见后果**：编辑一张 P2 之前拍摄的 filmic 照片（元数据 `[98]=0`）套用任何预设（含内置三条）后**仍是 filmic**。这是既有设计（编辑旧照片不该因套预设换引擎），但用户若期望"套内置预设 = 新默认观感"，需要在编辑器里显式切引擎 —— 属于产品口径，未在本卡改动。
4. **`docs/engine-slots-design.md` 的其它历史行**（§7 表格里"旧预设 `engine_slot=0` 不变"等表述）只改了 §6 的槽位定义与旧预设缺省段；其余章节是"阶段 0–3 当时的历史记录"，保留原样（改动历史记录会让后续卡失去可追溯性）。
5. **工作区共存**：本卡改动与另一张卡（10bit HEIF：`OutputBitDepth.h`、`EngineBridge.kt`、`CameraController.kt`、`diag/`、`tools/check-bitdepth-single-source.py` 等未提交改动）共存于同一工作区。`SettingsScreen.kt` / `EditorExport.kt` / `build.gradle.kts` 三个文件同时含两张卡的改动：本卡只改上表 §1.1 的行号处（`DEFAULT_ENGINE_SLOT` 相关 4 行 + 版本号 2 行），其余 diff 属另一张卡。
6. **版本号推进**：`381/0.11.32` → `382/0.11.33`；根目录旧包 `filmkit-0.11.32-HEIF10-PROBE.apk`（另一张卡的产物）保留未动。
