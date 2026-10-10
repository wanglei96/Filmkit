# E4-3b 修正 `grain_pivot`（[61]）的方向 —— 交付报告

> 实现子代理：DSH 会话（E4-3b，2026-10-10）。工作目录 `/Users/wl/Downloads/胶片相机`。
> 输入：架构师对 E4-3 三处"规格自相矛盾"的裁决（`docs/reports/e4-3-grain-REPORT.md` §6.1–§6.3）。
> 本卡只执行**裁决 1**（需改代码的一行数学）；裁决 2 / 裁决 3 是"批准现状 / 确认规格有误"，**未改代码**。
> **代码改动全部留在工作区，未 commit**（等架构师独立复核）。

---

## 0. 结论摘要（5 项验证全部真跑）

| # | 验证项 | 结论 | 关键实测数字 |
|---|---|---|---|
| 1 | `pivot = 0` **逐位不变** | ✅ | 改前/改后 C++ LUT 二进制 **md5 相同**（`d6efddf40aba24235803b99d00b57f8b`）；numpy ↔ C++ 相对误差 **0.000e+00**；`[26]=0` 3072² `max\|Δ\| = 1 LSB`，`>1LSB 像素 = 0/28311552` |
| 2 | **方向反转已生效** | ✅ | C++ LUT 峰值亮度 **0.496 → 0.362 → 0.268**（pivot 0/0.15/0.3）；真跑 shader 端到端峰值 **0.453 → 0.391 → 0.266**、RMS² 质心 **0.500 → 0.420 → 0.369**（全部单调移向**暗部**） |
| 3 | `gu = 0` 时增量恒 0（裁决 2 护栏） | ✅ | mb=100 → `\|LUT\|max = 1.20e-03`、mb=0 → `1.31e-04`（判据 `<1e-2`）；LUT 值域对称 ±46.21 |
| 4 | 完整构建 + 单测 | ✅ | `assembleDebug` 删 `.o`/`.so` 后**真编**两 ABI，`GRADLE_EXIT=0`；单测 **404 tests / 0 failures / 0 errors**（38 个 XML，本次生成 `2026-10-10T03:07:33Z`） |
| 5 | 三个 tools 检查脚本 | ✅ | `validate-shaders.sh` = 0、`check-diag-fields.py` = 0、`check-style-passthrough.py` = 0；另 `grain_lut_ref.py` = 0、`grain-exp.py` = 0 |

**改动规模**：生产代码 **1 行**（`GrainLut.h` 的 `k`）+ 注释 + 2 个离线脚本的同步判据 + 设计文档 2 处更正段。

---

## 1. 改了什么（文件 : 行号）

| 文件 | 位置 | 改动 |
|---|---|---|
| `core/engine/src/render/GrainLut.h` | 84 | `k = 1.0f + 3.0f * pivot` → **`k = 1.0f / (1.0f + 3.0f * pivot)`**（唯一的生产代码改动） |
| 同上 | 54 | 头部注释里的公式说明同步为 `k = 1 / (1 + 3·pivot)` |
| 同上 | 69–82 | 新增 **★★ 裁决段**：矛盾描述 + 裁决 + 三条理由（需求优先 / 数学验证 / 零回归）+ `pivot` 已由调用方 clamp ⇒ 分母 ∈ [1,4] |
| `tools/grain_lut_ref.py` | 57–59 | numpy 复算的 `k` 同步为倒数式（**必须同式**，否则 `grain-exp.py` ① 的 C++ ↔ numpy 对照立刻红） |
| 同上 | 126–135 | ⑤ 判据方向反转（原为"单调移向亮部"），档位 0.1 → **0.15**（与任务卡要求的三档一致） |
| `tools/grain-exp/grain-exp.py` | 22–23, 290–319 | 新增 **⑩ [61] 方向**判据：真跑 `kFsStyle`，32 档灰阶的颗粒 RMS 峰值 + RMS² 加权质心都必须随 pivot **单调移向暗部** |
| `docs/reports/editor-E4-film-params-DESIGN.md` | 259–268 | §3.1.3 第 1 项**更正段**（原文保留，见 §4） |
| 同上 | 296–316 | §3.1.5 **更正段**（原文公式保留，标注裁决与理由） |

**未改**：`FrameRenderer.cpp`（调用处 `grain::buildGrainLut(lut, mb, pivot)` 无需动；`pivot` 的 `std::clamp(…, 0, 1)` 已在）、`FrameRenderer.h`、`FilmParams.h`、任何 Kotlin 文件、任何 shader 源码。
**未 commit**（按任务卡：架构师独立复核后提交）。

### 1.1 ⚠️ 关于"改了 2 个离线脚本"的说明（超出文件纪律的字面清单）

任务卡的文件纪律写的是"只改 `GrainLut.h`（必要时 `FrameRenderer.cpp`）+ 设计文档 + 报告"，但
`tools/grain_lut_ref.py` 与 `tools/grain-exp/grain-exp.py` 是 E4-3 建立的**验收工具**，二者都硬编码了
`k` 的表达式与**方向判据**：

- 不改 `grain_lut_ref.py` ⇒ `grain-exp.py` ① 的"C++ ↔ numpy 逐点对照"必然红（两边不同式）；
- 不改方向判据 ⇒ 脚本会继续断言"峰值移向亮部"，**新的正确实现会被判为失败**（假红）。

即：这两处是**验证要求 1/2/3 能被重跑的必需品**，不是功能改动。二者均**不在**并行 agent 的地盘
（并行 agent 改 Kotlin 侧与 `assets/presets/`）。如果架构师认为这超出了授权范围，回退方式是把这两个
文件的 `k` 与判据恢复原样，但那样**本报告的第 1–3 项验证就无法复现**。

---

## 2. 裁决 1 的落地：为什么取倒数

规格 `docs/reports/editor-E4-film-params-DESIGN.md` §3.1.5 自相矛盾：

- **公式**：`l' = pow(l, 1 + 3·pivot)`（指数 > 1 对 `l ∈ (0,1)` 是**压暗**）
- **文字**：`pivot > 0` ⇒ 低亮度 `l` 被**抬**到 `l'` 的高位 ⇒ 暗部落在相纸曲线**陡段** ⇒ **颗粒峰值移向暗部**

E4-3 按公式字面实现并实测：峰值 `l` = 0.496 → 0.583 → 0.693（**移向亮部**）—— 与文字相反。

**裁决 = 以文字意图为准，指数取倒数**（已写进 `GrainLut.h:69-82` 的代码注释）：

1. **需求优先**：规格的**文字**承载的是调研需求（`editor-preset-RESEARCH.md` §2.4"负片暗部颗粒重"），
   公式只是实现细节 —— 二者矛盾时**需求优先**。（倒数式下 `k < 1 ⇒ l' > l`，"抬到高位"的描述才自洽。）
2. **数学验证**：相纸响应 `paper_resp'(e)` 的峰值在 `e = 0.5`（中灰）。要让"暗部颗粒最重"，需要把
   **暗部亮度**映射到曲线峰值：`f(0.3) = 0.5 ⇒ k = ln(0.5)/ln(0.3) ≈ 0.577`。
   `k = 1/(1+3p)` 在 `p = 0.3` 时给 `k ≈ 0.526` ✓ 方向正确；`k = 1 + 3p = 1.9` 给 `f(0.3) = 0.1`（偏离峰值）✗。
3. **零回归**：`pivot = 0` 时两式同为 `k = 1.0f` ⇒ 走短路分支（不调 `pow`）⇒ **逐位不变**。
   这不是"实测碰巧"，而是**结构性**保证：改动的分支在 `pivot = 0` 时根本不执行。

---

## 3. 验证 1：`pivot = 0` 逐位不变（A/B 通路纪律）

**方法**：用改前的 `grain-lut-dump`（include 改前的 `GrainLut.h`）先 dump 基线，改完重编再 dump，
比对**二进制**（不是"数值接近"）。

```bash
cd tools/grain-exp
# ① 改前基线（在编辑 GrainLut.h **之前**执行）
for p in 0 0.15 0.3; do ./grain-lut-dump 100 $p out/e4-3b-base/lut_mb100_p${p}.raw; done
# ② 改完 GrainLut.h 后重编 + 再 dump
clang++ -O2 -std=c++17 -o grain-lut-dump grain-lut-dump.cpp
for p in 0 0.15 0.3; do ./grain-lut-dump 100 $p out/e4-3b-new/lut_mb100_p${p}.raw; done
# ③ 逐字节比对
for p in 0 0.15 0.3; do md5 -q out/e4-3b-base/lut_mb100_p${p}.raw; md5 -q out/e4-3b-new/lut_mb100_p${p}.raw; done
```

| pivot | 改前 md5 | 改后 md5 | 结论 |
|---|---|---|---|
| **0** | `d6efddf40aba24235803b99d00b57f8b` | `d6efddf40aba24235803b99d00b57f8b` | **逐位相同** ✅ |
| 0.15 | `80f695ba88723cd0e84756b34fbe18d4` | `4dc70c0647c25f517d74d1eae3e70662` | 已变化（预期） |
| 0.3 | `1fd82c51a77f1aa5ce99a5a5731801b9` | `458286112f7f2900a63d8f18c6fc318f` | 已变化（预期） |

**旁证（同一次改动后的完整离线验收）**：

```
$ python3 tools/grain-exp/grain-exp.py --quick     # EXIT=0
① LUT mb=100 pivot=0：C++ ↔ numpy 相对误差 max = 0.000e+00、绝对误差 max = 0.000e+00
   ⑥ pivot=0 ↔ darktable 原版公式（无 pivot 项）：max|Δ| = 0.000e+00
   gu≈0 处 |LUT| max = 1.20e-03 L 单位
① LUT mb=0  pivot=0：… 0.000e+00 …
① LUT mb=100 pivot=0.3：… 0.000e+00 …

$ python3 tools/grain-exp/grain-exp.py              # EXIT=0（3072² 真跑 shader）
② [26]=0（含 [60]=0/[61]=1 极端值）3072²：max|Δ| = 1 LSB，>1LSB 像素 = 0/28311552
```

`1 LSB` = 既有的 TPDF dither（与颗粒无关，E4-3 改前同值）。

---

## 4. 验证 2：方向反转已生效（实测数字）

### 4.1 LUT 侧（C++ 真输出，mb=100，128 级亮度轴）

判据量 = `|LUT(gu≈0, l)|` 的峰值所在亮度（与 E4-3 §6.3 同口径，便于直接对照）。
四个独立口径**全部**单调移向暗部：

| pivot | k = 1/(1+3p) | 列 64（gu≈+0.004） | 半差 (64−63)/2 | 列 95（gu≈+0.25） | 列 32（gu≈−0.25） |
|---|---|---|---|---|---|
| 0 | 1.000 | **0.496** | 0.496 | 0.378 | 0.622 |
| 0.15 | 0.690 | **0.362** | 0.362 | 0.244 | 0.504 |
| 0.3 | 0.526 | **0.268** | 0.268 | 0.157 | 0.402 |

**改前对照**（E4-3 报告 §6.3 实测，`k = 1+3p`，档位 pivot = 0 / 0.1 / 0.3）：0.496 → 0.583 → **0.693**（移向亮部）
⇒ 本次改动**确实把方向反转了**，且 `pivot = 0` 的 0.496 完全一致。

> ⚠ 测量口径注记：`gu≈0` 处的 LUT 值本身趋 0（±1.2e-3），**不能**用"左右两格取平均再找峰值"
> （那会把两个反号的量平均掉，只剩浮点噪声 —— 实测该口径给出 0.780/0.102/0.654，**非单调**，是伪结果）。
> 正确口径是**单列**（列 64 即 gu = +1/254）或其半差，两者在此处给出完全相同的峰值位置。

### 4.2 shader 端到端（真跑 `kFsStyle`，新固化判据 ⑩）

```
$ python3 tools/grain-exp/grain-exp.py              # EXIT=0
⑩ [61] 方向（32 档灰阶的颗粒 RMS 峰值亮度）：pivot=0 → 0.453、0.15 → 0.391、0.3 → 0.266
   （单调移向**暗部** = True）；RMS² 加权质心 = 0.500 → 0.420 → 0.369（单调 = True）
```

方法：32 档灰阶（档位按**固定种子的随机掩码**分配到像素，避免竖条让各档落在不同的噪声空间位置），
`grain = 50`、`grain_size = 400`、`grain_mb = 100`，测每档的颗粒 RMS 与 RMS² 加权质心。

- 峰值与质心**两个统计量都单调移向暗部** ✅
- `pivot = 0` 的峰值 0.453 落在中灰判据区间 `[0.3, 0.7]` 内 ✅（与 LUT 侧的 0.496 相差 1 个档位 = 32 档量化 + 亮度轴插值的正常偏差）
- 该判据已写进 `grain-exp.py`（⑩），后续任何回归都会在 `python3 tools/grain-exp/grain-exp.py` 里红。

---

## 5. 验证 3：`gu = 0` 时增量恒 0（裁决 2 的护栏）

裁决 2 批准了 E4-3 的 `− l'`（而非规格字面的 `− l`）。新 `k` 下这条护栏**仍然成立**：

```
① LUT mb=100 pivot=0：  gu≈0 处 |LUT| max = 1.20e-03 L 单位（判据 <1e-2）
① LUT mb=100 pivot=0.3：gu≈0 处 |LUT| max = 1.20e-03 L 单位（判据 <1e-2）
① LUT mb=0   pivot=0：  gu≈0 处 |LUT| max = 1.31e-04 L 单位
$ python3 tools/grain_lut_ref.py     # EXIT=0
④ pivot=0.0: |gu≈0 处的 LUT| max = 1.20e-03；值域 = [-46.21, 46.21]（对称 ⇒ 无偏移）
④ pivot=0.3: |gu≈0 处的 LUT| max = 1.20e-03；值域 = [-46.21, 46.20]（对称 ⇒ 无偏移）
```

值域**对称**（`|min| ≈ |max|`）⇒ 没有系统性亮度偏移；`pivot = 0.3` 的 1.20e-03 与 `pivot = 0` 同量级
⇒ 倒数的 `k` 没有引入新的偏移路径（若减数取 `l`，此处会是 −23.5 L 单位 ≈ −60/255，E4-3 §6.2 实测）。

---

## 6. 验证 4：完整构建与单测（自己看退出码）

```bash
# 强制真编：先删 native 产物（路径已核对，均在 app/build/ 下）
rm -rf app/build/test-results
rm -f app/build/filmengine/{arm64-v8a,x86_64}/CMakeFiles/filmengine.dir/src/render/FrameRenderer.cpp.o
find "$PWD/app/build" -name 'libfilmengine.so' -delete      # 删除后计数 = 0
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest
# → GRADLE_EXIT=0
#   日志：arm64-v8a 与 x86_64 各 [ 7%] Building CXX object …FrameRenderer.cpp.o
#                                  [ 15%] Linking CXX shared library libfilmengine.so
#   产物时间戳 = 本次（11:06）：FrameRenderer.cpp.o 4.66 MB、libfilmengine.so 8.69 MB

# 单测必须**真跑**（第一次跑出 FROM-CACHE ⇒ 不信缓存，删结果 + 禁用缓存重跑）
rm -rf app/build/test-results
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest --no-build-cache
# → BUILD2_EXIT=0，Task :app:testDebugUnitTest 执行（非 UP-TO-DATE、非 FROM-CACHE）
```

**单测结果**（XML 本次生成，`app/build/test-results/testDebugUnitTest/`）：

```
XML 文件数 = 38，用例总数 = 404，失败 = 0，错误 = 0，跳过 = 0
timestamp = 2026-10-10T03:07:33.933Z → 2026-10-10T03:07:34.269Z（= 本次运行）
```

用例数 404 > E4-3 的 378：差值是**并行 agent** 新增的 Kotlin 侧用例（`BuiltinPresetsTest` /
`CameraPresetApplyTest` / `FilmParamKeysTest` / `PhotoViewPresetTest` 等），与本卡无关。

> 第一次 `./gradlew assembleDebug testDebugUnitTest` 的 `testDebugUnitTest` 报 **FROM-CACHE**
> （我删了 `test-results/`，但 Gradle 从 build cache 恢复），**不能**算"本次真跑" ⇒ 已用
> `--no-build-cache` 重跑并以上表为准。这条记录在此，避免后续复核时被"看起来跑过了"误导。

---

## 7. 验证 5：三个 tools 检查脚本

| 命令 | 退出码 | 关键输出 |
|---|---|---|
| `bash tools/validate-shaders.sh` | **0** | `全部 shader 校验通过` |
| `python3 tools/check-diag-fields.py` | **0** | `全部通过`（`grainMb`/`grainPivot`/`grainSeed` 仍在） |
| `python3 tools/check-style-passthrough.py` | **0** | `全部通过`；`⑯a 颗粒 [26]=0（且 [60]=0、[61]=1 极端值）：max\|Δ\| = 1 LSB，>1LSB 像素 = 0/27648`；`⑯c [60] 调制曲线：mb=100 三段 RMS = 6.43/11.85/5.81（中间最大 = True）` |
| `python3 tools/grain_lut_ref.py` | **0** | `自检通过`（7 项；⑤ 已改为"单调移向暗部"） |
| `python3 tools/grain-exp/grain-exp.py` | **0** | `全部通过`（含新增 ⑩） |

`check-style-passthrough.py` 的 ⑨b（`kFsOut` ↔ `kFsStyle` 颗粒段逐字一致）仍然通过 ⇒ 两条链路的颗粒段未分叉。

---

## 8. 裁决 2 / 裁决 3：仅确认，未改代码

### 8.1 裁决 2（LUT 减数用 `− l'`）—— 批准现状，保持

架构师批准：LUT 的定义是"相对于**输入亮度**的增量"，y 轴既然用了重映射后的 `l'`，减数就必须是 `l'`；
否则 `pivot` 会污染整体亮度。**本卡未改**（`GrainLut.h:77` 仍是 `paper_resp(gu + inv, delta) - lp`），
并把该护栏在新 `k` 下重验（本报告 §5）。

### 8.2 裁决 3（规格 §3.1.3 第 1 项事实描述有误）—— 已在设计文档更正

- 规格原文"size 越大越细"**保留**（不删原文，保留"规格错过一次"的记录，与项目既有风格一致）。
- 紧随表格插入更正段（`editor-E4-film-params-DESIGN.md:259-268`），写明：
  - 实测是"**size 越大越粗**"（E4-3 质心特征周期 3.99 → 68.8 px）；
  - 复核 `gp = gl_FragCoord.xy * 6.0 / max(uGrainSize, 0.25)`：`6/size` 是"**每像素的噪声坐标增量**"，
    与"每格像素数"是**倒数**关系 ⇒ size 大 ⇒ 增量小 ⇒ 格子大 ⇒ 更粗；
  - **"必须改"的判定仍成立**，真实理由是**数值标定与物理尺度脱节**（默认 size=1.0 时格子 0.167 px，
    且没有上限 ⇒ `size = 1e6` 全图加同一灰度）；
  - 依据 = E4-3 实测数据 + 架构师复核。

### 8.3 裁决 1 也已写进设计文档（同风格，原文保留）

`editor-E4-film-params-DESIGN.md:296-316` 新增更正段：矛盾描述 + 倒数式 + 三条理由 +
`pivot = 1` 例子在倒数式下的换算（`k = 0.25`，`l = 0.18 → 0.651`、`l = 0.05 → 0.473` ⇒
"过强、可用区间 0.1–0.4、须真机标定"的结论不变）。

---

## 9. 未做 / 存疑

| 项 | 状态 | 说明 |
|---|---|---|
| `[61]` 真机标定 | **未做（按规格）** | 规格 §3.1.5：第一版只实现、默认 0，不做标定。本卡只改方向 |
| `[61]` 真机观感 | **未做** | 无连接设备、SDK 无 emulator（E4-3 §7 同限制）；离线用"真跑引擎 shader"替代到能力上限 |
| `[61]` 的 `pow` 折进 LUT 的离散化代价 | **不变** | E4-3 §6.1 的已知代价（`pivot > 0` 时插值在原始亮度空间）；倒数式**不改变**该代价的量级 |
| 方向判据的"最优 k" | **未标定** | 数学上的理想值 `k = ln(0.5)/ln(0.3) ≈ 0.577` 对应 `pivot ≈ 0.245`（`1/(1+3p) = 0.577`）；`pivot = 0.3` 给 0.526 ⇒ 曲线略偏。**这是标定问题，不是方向问题**，留给真机 |
| 并行 agent 的 Kotlin 改动 | **未碰** | 本卡未改任何 `.kt` / `assets/presets/` |

---

## 10. 复现命令（一键）

```bash
cd /Users/wl/Downloads/胶片相机

# ── 离线数学与方向（秒级）
python3 tools/grain_lut_ref.py                 # numpy 复算自检（⑤ 已改为"单调移向暗部"）
python3 tools/grain-exp/grain-exp.py --quick   # C++ ↔ numpy LUT 逐点对照（含 pivot=0 原版对照）

# ── 端到端（真跑 kFsStyle，3072²，数分钟）：含新增判据 ⑩ 方向
python3 tools/grain-exp/grain-exp.py

# ── 工具链
bash    tools/validate-shaders.sh
python3 tools/check-diag-fields.py
python3 tools/check-style-passthrough.py

# ── 构建（强制真编 + 单测真跑，看退出码，不要用管道吞）
rm -rf app/build/test-results
rm -f app/build/filmengine/{arm64-v8a,x86_64}/CMakeFiles/filmengine.dir/src/render/FrameRenderer.cpp.o
find "$PWD/app/build" -name 'libfilmengine.so' -delete
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest; echo "EXIT=$?"

# ── pivot=0 逐位不变的独立复核（改前基线已存于 out/e4-3b-base/）
cd tools/grain-exp && clang++ -O2 -std=c++17 -o grain-lut-dump grain-lut-dump.cpp
./grain-lut-dump 100 0 out/e4-3b-new/lut_mb100_p0.raw >/dev/null
md5 -q out/e4-3b-base/lut_mb100_p0.raw out/e4-3b-new/lut_mb100_p0.raw   # 两行必须相同
```

产物（**不入库**，`.gitignore` 已排除 `tools/grain-exp/out/`）：
`out/e4-3b-base/`（改前基线）、`out/e4-3b-new/`（改后）、`out/report.json`、`out/lut_cpp_*.raw`。
