# 实现卡 A1：8bit 假设收口成单一真源 —— 报告

> 状态：**已完成**。四条零回归判据全部实测通过（§4）。
> 上位：`docs/rec2020-heif-design.md` §4.2（本卡任务定义）
> 清单来源：`docs/reports/r-next-10bit-heif-REPO-AUDIT.md` §③/§4.2 —— **已逐条 grep 复核，该清单有三类问题（见 §1 引言）**
> 铁律：**行为零变化**（唯一验收口径）。改动 = 4 个既有文件 + 1 个新头 + 1 个新守卫脚本（§2）。

---

## 1. 收口前清单（grep 复核后，行号 = 本卡开工时 HEAD `47c3875` 实测）

报告 17 处清单有三类问题，故本卡**以自测为准**：① **行号漂移**（`kFsHist` 标的 `6023/6026` 是 shader 定义行，纹理落点实为 `13378`）；
② **数值有误**（直方图标 "256×96"，实测 **512×288**，`FrameRenderer.cpp:13375`）；③ **漏项**（成片降位路径实为 **3 条** —— YUV 静拍 /
RAW 静拍 / 编辑器导出，报告只列 1 条；`/255.0` 族实为 10 处，报告只列 2 处）。

### 1.1 A 组：输出交付位深（= 本卡收口目标，13 处）

| # | 位置（收口前） | 收口前写法 | 用途 |
|---|---|---|---|
| A1 | `FrameRenderer.cpp:8503` | `makeTex(&tex_cap_, &fbo_cap_, GL_RGBA8, …)` | `submitStill`（YUV 静拍）成片 target |
| A2 | `FrameRenderer.cpp:8519` | `glReadPixels(…, GL_RGBA, GL_UNSIGNED_BYTE, …)` | 同上 —— 报告所称"唯一真降位点" |
| A3 | `FrameRenderer.cpp:11116` | 同 A1 | `finishStillChain`（RAW 静拍）成片 target |
| A4 | `FrameRenderer.cpp:11134` | 同 A2 | 同上（**报告漏项**） |
| A5 | `FrameRenderer.cpp:8625` | 同 A1 | `submitDisplay`（编辑器导出）target |
| A6 | `FrameRenderer.cpp:8647` | 同 A2 | 同上（**报告漏项**） |
| A7 | `FrameRenderer.cpp:8222` | `makeTex(&tex_out_s_, &fbo_out_s_, GL_RGBA8, …)` | 预览降档输出 target |
| A8 | `FrameRenderer.cpp:8142` | `internal == GL_RGBA8 ? GL_UNSIGNED_BYTE : GL_HALF_FLOAT` | `makeTex` 的「格式↔类型必须匹配」判定 |
| A9 | `FrameRenderer.cpp:8164` | `glReadPixels(1×1, GL_RGBA, GL_UNSIGNED_BYTE, b4)` | `logPassDiag` 输出格式探针 |
| A10 | `FrameRenderer.cpp:5183` | shader `kFsOut`：`… - 1.0) / 255.0` | TPDF dither，量化前 ±1LSB |
| A11 | `FrameRenderer.cpp:5814` | shader `kFsStyle`：同上 | 编辑器链 dither |
| A12 | `CameraController.kt:3469` | `Bitmap.Config.ARGB_8888` | 成片 JPEG 的位图 |
| A13 | `EditorEngineSession.kt:284` | `Bitmap.Config.ARGB_8888` | 编辑器引擎输出位图（**报告漏项**） |

### 1.2 B 组：**非**输出交付链的 8bit 落点（只记录、不动，理由见 §5）

| # | 位置（收口前） | 内容 | 为什么不属于"输出交付位深" |
|---|---|---|---|
| B1 | `FrameRenderer.cpp:7868 / 8916 / 8929` | `tex_local_` = `GL_RGBA8`（局部色调映射低频图） | 算法内部工作缓冲；报告 ⑦-8 自述"**未确认其承载量**" ⇒ 本卡只记录 |
| B2 | `FrameRenderer.cpp:6663 / 6757` | 诊断位打包 `8×2 RGBA8` + readback | 传输编码（ES 3.0 唯一保证可读回的组合），非交付位深 |
| B3 | `FrameRenderer.cpp:8432 / 8476` | `still_tex_` = `GL_RGB8` + 上传 | **输入**位深：YUV→RGB 在 CPU 侧，HAL 给的就是 8bit |
| B4 | `FrameRenderer.cpp:8596 / 8611` | `edit_tex_` = `GL_RGBA8` + 上传 | 编辑器**输入**纹理 |
| B5 | `FrameRenderer.cpp:11519 / 11573 / 11584 / 12711 / 12869 / 13086` | `tex_mis_` / `tex_dil_` / halation 读回 | halation 内部工作缓冲 |
| B6 | `FrameRenderer.cpp:12343 / 12497` | `tex_hr_stat_` = `GL_RGBA8` | HR 统计读回 |
| B7 | `FrameRenderer.cpp:13378 / 13385` | `tex_hist_` = `GL_RGBA8`，**512×288** | 直方图读回（UI），报告数值 256×96 有误 |
| B8 | `FrameRenderer.cpp:14084` | local 低频图读回 | 同 B1 |
| B9 | `FrameRenderer.cpp:111 / 118 / 124-126` | `255.0f`：相机 WB 增益归一（CPU） | 增益比值口径（报告 §③-9 自述"否"） |
| B10 | `FrameRenderer.cpp:6811 / 8463-8467 / 12978 / 13053 / 13056 / 14091` | `/255.0f` 归一**读回的 u8 通道** | 读回通道 → 浮点，不是输出位深 |
| B11 | `FrameRenderer.cpp:1505 / 1585 / 13076` | `*255.0` / `*255.0f` | 梯度、权重可视化口径 |
| B12 | `LutIO.kt:258` / `LutPreview.kt:106` / `EditorScreen.kt:4475` | `ARGB_8888` | LUT 资产 / **输入**预览位图，非引擎输出 |
| B13 | `JpegEncoder.cpp:62-66` | 要求 `RGBA_8888` 否则返回 nullptr | JPEG 编码器的**入参**契约（10bit 期走 HEVC Main10，不经此处） |
| B14 | `tools/check-style-passthrough.py:136`、`check-halation.py:27`、`check-softglow.py:19` | 脚本内 8bit 量化判据 | 守卫自身口径，非产品代码 |
| B15 | `tools/check-halation.py:311` / `check-softglow.py:294` / `check-style-passthrough.py` / `engine-shader-offline.py` | `gl.run(sh["kFsOut"…])` **执行**提取出的原文 | 见 §5.1：shader 文本不可改的**约束来源** |

---

## 2. 收口后落点

### 2.1 单一真源

- **C++ / GLSL 侧**：`core/engine/src/render/OutputBitDepth.h`（新增，45 行，`namespace filmkit`）

  | 行 | 常量 | 8bit 值 |
  |---|---|---|
  | 28 | `kOutputBitDepth` | `8` |
  | 29 | `kOutputMax = (1 << kOutputBitDepth) - 1` | `255` |
  | 32 | `kOutputInvMax = 1.0f / static_cast<float>(kOutputMax)` | `1/255` |
  | 37 | `kOutputGlInternalFormat` | `GL_RGBA8` |
  | 38 | `kOutputGlReadFormat` | `GL_RGBA` |
  | 39 | `kOutputGlReadType` | `GL_UNSIGNED_BYTE` |
  | 43 | `kOutputMaxGlsl` | `"255.0"`（shader dither 除数文本，见 §5.1） |

- **Kotlin 侧**：`EngineBridge.kt:37` `val OUTPUT_BITMAP_CONFIG: Bitmap.Config = Bitmap.Config.ARGB_8888`
  （放既有 `object EngineBridge` 内 —— **不新建 Util 类**；该 object 已是 C++/Kotlin 边界，且原注释就在讲 JpegEncoder 的位图格式要求）

### 2.2 A 组 13 处的收口结果（收口后行号）

| # | 收口后位置 | 新写法 |
|---|---|---|
| A1 | `FrameRenderer.cpp:8505` | `makeTex(&tex_cap_, &fbo_cap_, kOutputGlInternalFormat, dst_w, dst_h);` |
| A2 | `FrameRenderer.cpp:8521` | `glReadPixels(0, 0, dst_w, dst_h, kOutputGlReadFormat, kOutputGlReadType, out_rgba->data());` |
| A3 | `FrameRenderer.cpp:11119` | 同 A1 |
| A4 | `FrameRenderer.cpp:11137` | 同 A2 |
| A5 | `FrameRenderer.cpp:8628` | `makeTex(&tex_cap_, &fbo_cap_, kOutputGlInternalFormat, out_w, out_h);` |
| A6 | `FrameRenderer.cpp:8650` | 同 A2（`out_w/out_h`） |
| A7 | `FrameRenderer.cpp:8224` | `makeTex(&tex_out_s_, &fbo_out_s_, kOutputGlInternalFormat, w, h);` |
| A8 | `FrameRenderer.cpp:8144` | `internal == kOutputGlInternalFormat ? kOutputGlReadType : GL_HALF_FLOAT;` |
| A9 | `FrameRenderer.cpp:8166` | `glReadPixels(0, 0, 1, 1, kOutputGlReadFormat, kOutputGlReadType, b4);` |
| A10 / A11 | `FrameRenderer.cpp:5184 / 5815` | **文本未改**，由守卫 ③ 锚定 `kOutputMaxGlsl`（§5.1） |
| A12 | `CameraController.kt:3469` | `Bitmap.createBitmap(w, h, EngineBridge.OUTPUT_BITMAP_CONFIG)` |
| A13 | `EditorEngineSession.kt:284` | `Bitmap.createBitmap(outW, outH, EngineBridge.OUTPUT_BITMAP_CONFIG)` |
| — | `FrameRenderer.cpp:24` | `#include "OutputBitDepth.h"` |

**零变化论证**：`kOutputGlInternalFormat` = `GL_RGBA8`（同 enum 值）、`kOutputGlReadType` = `GL_UNSIGNED_BYTE`（同值）、
`OUTPUT_BITMAP_CONFIG` = `ARGB_8888`（同值）⇒ 替换前后**完全同一个常量**，不含任何算术。
shader 文本一字未动 ⇒ `kFsFilm` crc32 `0x4fa1ebd3` 与 `film_d5` 判据不受影响（§4 实测确认）。

---

## 3. 新增守卫与其自检

`tools/check-bitdepth-single-source.py`（照 `check-filmparams-contract.py` / `check-srgb-oetf.py` 的形态；**不新建框架**）
`verify()` 是纯函数，六组检查：

| 组 | 检查 |
|---|---|
| ① | 真源自洽：`kOutputMax == (1<<bit)-1`、`kOutputInvMax == 1/kOutputMax`、`kOutputMaxGlsl` 的数值 == `kOutputMax` 且带小数点（GLSL 会当 int） |
| ② | GL 交付对随位深：8bit⇒`(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE)`；10bit⇒`(GL_RGB10_A2, GL_RGBA, GL_UNSIGNED_INT_2_10_10_10_REV)`；位深未登记 ⇒ 报红 |
| ③ | shader dither 除数（kFsOut / kFsStyle）逐字 == `kOutputMaxGlsl`（排除 `float dhash(vec2)` 函数定义行） |
| ④ | 输出交付链 7 条 MUST 落点必须引用真源 + 4 条 FORBID（裸 `GL_RGBA8` / 裸 8bit readback 对） |
| ⑤ | Kotlin：`OUTPUT_BITMAP_CONFIG` 与位深匹配（8⇒ARGB_8888 / 10⇒RGBA_1010102 / 16⇒RGBA_F16）；两个引擎输出落点不得写裸 `ARGB_8888` |
| ⑥ | 冻结计数：`makeTex(…, GL_RGBA8, …)` 总数不得增长（当前 8，全部是 B 组内部缓冲）⇒ 新增 RGBA8 纹理落点必须显式决定去留 |

**自检（`--selftest`，照 `engine-shader-offline.py --splice-selftest` 先例）**：喂 8 个变异体，**逐个都被对应组抓到**，且未变异体全绿：

```
① 位深改 10 ⇒ kOutputMaxGlsl '255.0' ≠ (1<<10)-1=1023 ｜ ① kOutputMax 写字面量 ⇒ 报红
③ kFsOut dither 除数改 1023.0 ⇒ ≠ 真源 255.0    ｜ ④ 成片 target/readback 改回裸字面量 ⇒ 报红
⑤ Kotlin Config 改 RGBA_F16 / 输出落点又写死 ARGB_8888 ⇒ 报红 ｜ ⑥ 新增 RGBA8 makeTex ⇒ 9 ≠ 8
未变异体全绿（不是「永远红」的假守卫）⇒ **自检通过**
```

实测：`python3 tools/check-bitdepth-single-source.py` → **PASS**（RC=0）。

---

## 4. 四条零回归判据（实测值）

| # | 判据 | 命令 | 期望 | 改前基线 | **改后实测** | 结论 |
|---|---|---|---|---|---|---|
| ① | 全量单测 | `JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest` | 现有 664 条全绿 | — | `BUILD SUCCESSFUL`；`suites=64 tests=668 failures=0 errors=0 skipped=0`（664 基线 + 并行卡新增 4 条 diag 测试）；随后 `:app:testDebugUnitTest --rerun` 强制重跑仍 **0 失败** | ✅ |
| ② | 离线零回归（引擎 0） | `python3 tools/engine-shader-offline.py --dng test_temp/FILMKIT_20261009-090803.dng --roi 1800,2100,600,1000 --out /tmp/a1-e0 --engine 0` | `film_d5.npy` crc32 = `0x589f8364`；拼接 shader crc32 = `0x4fa1ebd3` | `0x589f8364` / `0x4fa1ebd3` | **`0x589f8364` / `0x4fa1ebd3`**（sha256 `ce1c9a90…` / `f1ebb317…`，与改前逐位同） | ✅ |
| ③ | 引擎 1 | 同上 `--engine 1 --out /tmp/a1-e1` | `film_d5_sigmoid.npy` crc32 = `0x7c2646ef` | `0x7c2646ef` | **`0x7c2646ef`**（sha256 `c166153c…`） | ✅ |
| ④ | shader 校验 | `bash tools/validate-shaders.sh` | 全部通过 | — | **全部通过**（含 `kFsFilm` 整份、`kFsSigmoid` head+def+mid+main+tail 拼回整份） | ✅ |

> 注：`validate-shaders.sh` 是 **bash** 脚本（任务卡里写成 `python3 tools/validate-shaders.sh`，照抄会 `SyntaxError`）。
> 判据命令一律显式 `RC=$?` 判定（macOS 无 `timeout`）。

**附加验证（本卡自加，成本低、证明力高）**

| 项 | 命令 | 实测 |
|---|---|---|
| C++ 真的编过（`buildFilmEngine` 默认 UP-TO-DATE，不吃源改动） | `./gradlew :app:buildFilmEngine --rerun` | `BUILD SUCCESSFUL`，arm64-v8a + x86_64 均 `[100%] Built target filmengine`；`FrameRenderer.cpp.o`(16:08:41) 新于源(16:06:53) ⇒ 改动确被编入 |
| 判据文件级复核 | `cmp` 改前/改后 npy（基线另存 `/tmp/a1-pre-e0`、`/tmp/a1-pre-e1`） | `film_d5.npy` 与 `film_d5_sigmoid.npy` 均**逐字节相同**（强于 CRC 相同） |
| 诊断字段守卫 | `python3 tools/check-diag-fields.py` | **全部通过** |
| 本卡守卫 + 自检 | `python3 tools/check-bitdepth-single-source.py [--selftest]` | **PASS** / 自检通过 |

---

## 5. 未收口的项与原因

### 5.1 shader 内 dither 除数（A10/A11，2 处）—— 只锚定，不改文本

**为什么不改**：GLSL 引用不到 C++ 常量，而在 `R"(...)"` 里做字符串拼接会**破坏全部 shader 提取器**：

1. 契约冻结：`kFsFilm` 整体 crc32 `0x4fa1ebd3`（`engine-shader-offline.py` 的 `FILM_SRC_BASELINE`，含逐块 chars/bytes/crc32/sha256）；
2. `tools/validate-shaders.sh` 用 `const char\*\s+(\w+)\s*=\s*R"\((.*?)\)";` **逐字**提取全部 shader；
3. **4 个**脚本（`check-halation.py:311` / `check-softglow.py:294` / `check-style-passthrough.py` / `engine-shader-offline.py`）直接 `gl.run(…)` **执行**提取出的原文。

把 `R"GLSL(…255.0…)GLSL"` 拆成 `R"GLSL(…/)GLSL" kOutputMaxGlsl R"GLSL(;…)GLSL"` 后，非贪婪正则 `(.*?)\)";` 会跨段吞掉中间的 C++ 源码 ⇒ 上述 3 类守卫**同时失效**（不是变红，是提取到垃圾文本）。
**替代**：真源仍唯一（`OutputBitDepth.h`），shader 侧一致性由守卫 ③ **强制**——改位深却忘改 shader ⇒ 立刻红。
**代价**：提位深时须同批改 shader 的 `255.0`，这是**被契约强制的**人工同步点，已写进 `OutputBitDepth.h` 文件头与守卫输出。

### 5.2 B 组 15 类内部 8bit 落点（§1.2）—— 语义上不该收进"输出位深"

它们是**各自算法的内部格式或输入位深**，收进来是语义错误（例：把 HAL 给的 8bit YUV 输入当"输出位深"，
或让 halation 内部缓冲跟着交付位深跑）。防散落改由守卫 ⑥ 的冻结计数兜住：新增 `makeTex(…, GL_RGBA8, …)`
必须显式决定"改引用真源 or 登记为内部缓冲"。

### 5.3 明确不动的三处（任务纪律）

- `FrameRenderer.cpp:7869 tex_local_ = GL_RGBA8`：报告 ⑦-8 自述"**未确认其承载量**"⇒ 本卡遵任务要求**只记录、不动**。
- 直方图 `tex_hist_` 512×288（B7）：UI 传输格式，非交付位深；报告数值 256×96 已纠正为 512×288。
- `app/src/main/java/com/filmkit/app/diag/`（并行卡）：本卡**零改动**（`git status` 确认为其 untracked 新目录）。

### 5.4 已知注释债（不改，仅登记）

`kFsStyle` 内注释 `// TPDF dither ±1LSB（与 kFsOut 4559 同式；RGBA8 量化前）`（现 `FrameRenderer.cpp:5814`）
里的 `4559` 是过时行号（kFsOut 的 dither 现已在 `5184`）。它在 **shader 文本内** ⇒ 同 §5.1 理由**不可改**。低危（纯注释）。

---

## 6. 复现（全部命令照抄可跑）

```bash
cd "/Users/wl/Downloads/胶片相机"
python3 tools/check-bitdepth-single-source.py --selftest          # 守卫自检
python3 tools/check-bitdepth-single-source.py                     # 守卫 PASS
python3 tools/check-diag-fields.py                                # 诊断字段守卫
bash tools/validate-shaders.sh                                    # shader 守卫
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew :app:buildFilmEngine --rerun   # C++ 重编
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug :app:testDebugUnitTest --rerun
python3 tools/engine-shader-offline.py --dng test_temp/FILMKIT_20261009-090803.dng \
        --roi 1800,2100,600,1000 --out /tmp/a1-e0 --engine 0    # ⇒ 0x589f8364 / 0x4fa1ebd3
python3 tools/engine-shader-offline.py --dng test_temp/FILMKIT_20261009-090803.dng \
        --roi 1800,2100,600,1000 --out /tmp/a1-e1 --engine 1    # ⇒ 0x7c2646ef
```

改动（`app/build.gradle.kts`、`ui/SettingsScreen.kt`、`docs/reports/r-next-10bit-heif-*.md` 属**其他卡**，本卡未碰）：
**新增** `core/engine/src/render/OutputBitDepth.h`（45 行真源）+ `tools/check-bitdepth-single-source.py`（守卫 & 自检）；
**修改** `FrameRenderer.cpp`(+13/−9：include 1 + 落点 8 组) · `EngineBridge.kt`(+6) · `CameraController.kt`(±1) · `EditorEngineSession.kt`(±1)。
