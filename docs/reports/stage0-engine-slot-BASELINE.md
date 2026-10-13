# 引擎插槽「阶段 0」基线（零回归对照基准）

> 上位方案：`docs/engine-slots-design.md` §5（`[98] engine_slot`）/ §6（引擎注册表）/ §8（阶段 0 验收）。
> 本文件记录**改动前**（提交 `a6adb35`）采集到的基线，供阶段 1「模板化重构」与阶段 2「sigmoid 引擎」
> 做逐字节回归对照。**它不是新的判据来源**，判据仍以方案 §8 为准。

---

## 1. 本阶段能采到什么（先说局限，再说数据）

方案 §8 阶段 0 的验收判据是「六处同步通过 + `CameraPresetsTest` 绿 + **`[98]=0` 时同帧 CRC32
与基线逐字节一致**」。但**"同帧成片 CRC32"必须由真机拍照链路产生**，本机（macOS，无 adb、无
Android GPU 运行时）采不到。本机可采的是**离线链路**：

| 段 | 本机能否采基线 | 依据 |
|---|---|---|
| P0（`kFsRawDenoise`）→ RCD → CCM（D3→D4） | ✅ 可采（本文件 §3） | `tools/engine-shader-offline.py` 已接入并跑通 |
| **`kFsFilm`（FILM，含 filmic + gamut + 外观层）** | ✅ **已补齐**（本文件 §3.3） | 2026-10-13 补接，见 §4 与 `tools/film-offline-REPORT.md` |
| PC3D（`kFsPc3d`） | ⚠ 已接入但 **LSC 恒等**（与真机有偏差） | §4 |
| HR（高光重建）/ `kFsOut`（OETF/颗粒/LUT/锐化）/ MFNR | ❌ 不在链内 | §4（方案 §10.6） |

**阶段 0 自身的零回归是结构性成立的**（见 §3.2 三条机械依据）：
阶段 0 **没有改动任何 shader**（`FrameRenderer.{h,cpp}` 零改动），`[98]` 也**没有任何 kernel 读取**。
本基线在阶段 0 起*哨兵*作用（谁误改 P0/RCD/CCM/FILM 段，摘要立刻变化）；
**它对阶段 1 才是真正的硬判据载体**（阶段 1 要拆 `kFsFilm`）。

---

## 2. 复现命令（本机可跑，0.7 s）

```bash
cd /Users/wl/Downloads/胶片相机
mkdir -p /tmp/stage0-baseline
python3 tools/engine-shader-offline.py \
  --dng test_temp/FILMKIT_20261009-090803.dng \
  --roi 1800,2100,600,1000 \
  --out /tmp/stage0-baseline
```

- 输入 DNG `sha256 = 00ebcbcd9ee61411b5b21eb9cbd63bc8c5d39a1296d0638a68afa6721aed6291`
  （4080×3072，BlackLevel 63/63/63/64，WhiteLevel 1023，AsShot G 归一增益 `[2.080078125, 1.0, 1.453125]`）。
- ROI `Y 1800..2100, X 600..1000` = 300×400（默认 ROI，sensor 域）。
- 执行器版本 `tools/engine-shader-offline.py`
  `sha256 = a29ac323759e9fe266fe2b43d8cfdd0f19e4dff7e38f8438926447e264fbee60`
  （**改动该脚本的既有 pass 逻辑时基线失效**；只在文件尾部新增 FILM 段不影响下面四个摘要）。
- **复现性已实测**：同一命令独立跑两次，四个产物的字节摘要完全一致（确定性成立，可作判据）。

## 3. 基线摘要（float32 原始字节，`a.tobytes()` 口径）

| 产物 | shape | bytes | CRC32 | SHA-256（前 16 位，全值见 §3.1） |
|---|---|---|---|---|
| `p0.npy`（`kFsRawDenoise`） | 300×400×4 | 1 920 000 | `0x0fb730bb` | `b8a5b348f02e2121…` |
| `rcd_g.npy`（`kFsRcdG`） | 300×400×4 | 1 920 000 | `0x6f85ff96` | `a8338b467478f508…` |
| `rcd_d3.npy`（`kFsRcdChromaB`） | 300×400×4 | 1 920 000 | `0x594e2a05` | `e4dd5c7265962f5e…` |
| `ccm_d4.npy`（`kFsCcm` → D4） | 300×400×4 | 1 920 000 | `0x189e4126` | `bf7388d97217aa19…` |

### 3.1 全值

```
p0       sha256=b8a5b348f02e2121e637d470919725cb60ad8ce17c9b92c85719a67e3783ecf5
rcd_g    sha256=a8338b467478f5086c24fcb5114978258dff630958a02eef360ba13fc84ad567
rcd_d3   sha256=e4dd5c7265962f5e0f7b472ef7a76f0fa35ef087dbd08a3fbcee91a0082e5fba
ccm_d4   sha256=bf7388d97217aa1970b575d0af2d21a84ad1818b5f89fd4ac6bffed372a32353
```

### 3.2 阶段 0「零回归」的三条机械依据（不依赖 GPU 的部分）

1. **shader 零改动**：`git diff a6adb35 -- core/engine/src/render/FrameRenderer.cpp core/engine/src/render/FrameRenderer.h`
   必须为空（阶段 0 的 diff 只落在 `FilmParams.h` / `EngineRegistry.{h,cpp}` / `CMakeLists.txt` 与 Kotlin 侧）。
2. **`[98]` 在渲染路径零消费者**：改后全仓 grep `engine_slot`，只允许出现在
   `FilmParams.h`（声明 + `fromDoubles` 写入）、`EngineRegistry.*`（文档性注释）、
   Kotlin（`CameraPresets` / `FilmParamKeys` / 测试）。任何 `FrameRenderer` / `Bridge` 的读取都是越界。
3. **缺槽不触发**：`fromDoubles` 的新行必须是 `if (n > 98)`（长度 98 的旧数组读不到该槽 ⇒ 保持结构体
   默认 `0.0f`），且 `kCount` 与 Kotlin `CameraPresets.COUNT` 两侧同时为 99。

---

### 3.3 FILM 段基线（`kFsFilm`，阶段 1 的**主判据**）

2026-10-13 补接（`tools/engine-shader-offline.py` 纯增量 +383 行，原 295 行未动；
交付与逐行对照见 `tools/film-offline-REPORT.md`）。产物 `<out>/film_d5.npy`
（H×W×4 float32，**OETF 前的线性显示域**，与真机 `tex_work_` 同域）。

| 口径 | 命令差异 | `film_d5.npy` CRC32 | SHA-256 |
|---|---|---|---|
| **代码默认**（`[37]/[38]` = −7.65 / +4.56，与 `CameraPresets.neutral()`、darktable/PC 固化值一致） | 默认 | **`0x589f8364`** | `ce1c9a90c11dbb69278593cd20854970c7a99fc2bf0ee9da90fc10e3136e5391` |
| 白点 4.0（2026-10-13 定档值；真机 prefs 若为 4.0 则用这组对照） | `--film-white-ev 4.0` | **`0xd46d7b5e`** | `7811827393c4af94fd206547d97a8a6b333e516464db8c5de97ff7832b2295ab` |

- 两组均经**两次独立运行**校验字节一致（本机同驱动下逐位可比；**跨机器不保证** —— float16 与超越函数实现差异）。
- 阶段 1 判据：拆 `kFsFilm` 为模板 + 引擎 0 注入段后，**同参数下上述摘要必须不变**。
- 判据链自洽性已独立复核：`--film-tone-k 0 --film-look 0`（整段旁路）时 `film_d5` 与 `ccm_d4`
  **逐位相同**（crc 都是 `0x189e4126`）⇒ FILM 段在旁路档确实是恒等，不是"看起来对"。
- ⚠ **默认值口径修正**：`[37]/[38]` 的代码默认是 **−7.65 / +4.56**（`FilmParams.h:161-162` 与
  `CameraPresets.kt:122-123` 两侧一致），**不是**记忆里"内置白点已定档 4.0"的那个值 ——
  4.0 是 R-3 定档的**真机设置值**（经 prefs 覆盖），不是代码默认。两组都采，避免拿错口径对照。

## 4. 缺口与阶段 1 的前置条件

### 4.1 已解决：FILM 段补齐（2026-10-13）

原状：`tools/engine-shader-offline.py` 的文件头声称"已接入 4 个 pass（…… 4. FILM `kFsFilm`）"，
但**实读该脚本（295 行）没有任何 `kFsFilm` 代码路径** —— 它只跑到 CCM（`ccm_d4.npy`）就结束，
`steps.json` 只有 `p0/rcd/ccm` 三项。**文件头注释与实现不符** ⇒ 阶段 1 的核心判据当时无法执行。

**处置（用户拍板 A 方案）**：补接 FILM 段 + PC3D（纯增量 +383 行，原 295 行未动）。
交付与逐行对照见 `tools/film-offline-REPORT.md`；基线见本文件 §3.3；判据链自洽性由
"旁路档逐位恒等"独立复核。**阶段 1 的 CRC32 判据现在可执行。**

### 4.2 仍未在离线链内的部分（判据必须写明"哪一段不在链内"）

| 不在链内 | 原因 | 对判据的影响 |
|---|---|---|
| **HR 高光重建**（`[45]` 真机默认 1.0） | 需要块级参考场；**真机上 FILM 的输入是它的输出** | FILM 基线的输入口径与真机不同 ⇒ **不能**用它判断成片观感 |
| `uLocal*`（`[36]` 真机 0.25） | 依赖真机统计 | 离线按 `uLocalStrength = 0` 旁路；`>0` 档未验证 |
| PC3D 的 **LSC / 噪声标定** | 需真机标定数据 | 离线 LSC = 恒等、sigA/sigB 非标定值 ⇒ PC3D 段只作结构验证 |
| defringe / P5、`kFsOut`（OETF/颗粒/LUT/锐化）、MFNR、`uDomainRatio`、`auto_tone=1` | 未接入 / 需真机 | 同上 |
| **同帧成片 CRC32** | 需真机拍照链路（本机无 adb） | 阶段 1 的最终零回归仍需**真机同帧 A/B** 兜底 |

> **"离线好看、真机判死"的历史教训（`lessons.md` 2026-10-06）要求：不能拿部分链的绿去主张整链零回归。**
> 本基线的正确用法 = 证明"拆 `kFsFilm` 这一步没有改变 `kFsFilm` 的输出"（同输入下逐字节等价），
> **不是**证明"成片没变"。

## 5. 与方案 §8 的对应

| 方案 §8 阶段 0 交付物 | 状态 | 落点 |
|---|---|---|
| 建 `EngineRegistry`（只含引擎 0） | 已交付 | `core/engine/src/film/EngineRegistry.{h,cpp}` |
| `FilmParams` 加 `[98]` | 已交付 | `core/engine/src/film/FilmParams.h`（`kCount` 98→99） |
| 采集基线：成片 CRC32 | **部分**：离线链已覆盖 P0/RCD/CCM/FILM（§3、§3.3）；**真机成片待采**（需 adb） | 本文件 §3 / §3.3 |
| 采集基线：filmic 参数快照 | 见下 | 本文件 §5.1 |
| 六处同步 + `CameraPresetsTest` 绿 | 已交付（`tests=596 failures=0`） | Kotlin 侧同批改动 + `tools/check-filmparams-contract.py` |

### 5.1 filmic 参数快照（改动前的取值来源）

改动前**没有**离线 fixture 形式的 filmic 参数快照；现成的只有运行期日志：

- 预览链：`engine.preview.filmic`（每 120 帧一条，`FrameRenderer.cpp:13656`），字段
  `blackEv / whiteEv / w99 / w999 / wmax / cp / linMid / ratio / pLo / pHi / localRef / w / h`。
- **拍照链没有对应日志**（该事件在 `if (!still)` 分支内）⇒ 若阶段 1 需要"拍照时的 filmic 参数快照"，
  得先补一条 `engine.still.filmic`（或把该事件的 `still` 维度带上）。**这是阶段 1 的准备工作，不在阶段 0 内。**
- **白点口径两层，别混**：① **代码默认** `[37][38]` = `−7.65 / +4.56`（`FilmParams.h:161-162` +
  `CameraPresets.kt:122-123`，darktable/PC 固化值）；② **2026-10-13 真机定档值** = 白点 `4.0`
  （死白起点 3.55 EV，经 prefs 覆盖，见 `docs/reports/r3-white-point-refit-REPORT.md`）。
  §3.3 两组基线各对应一层。

## 6. 变更记录

| 日期 | 提交 | 说明 |
|---|---|---|
| 2026-10-13 | `a6adb35`（基线点） | 阶段 0 前的仓库状态；本文件 §2/§3 的基线在此采集 |
| 2026-10-13 | `6a11833` | 阶段 0：`[98] engine_slot` + `EngineRegistry` 骨架 + 本基线（§2/§3） |
| 2026-10-13 | （离线链卡） | 补接 FILM 段 + PC3D ⇒ §3.3 新增 FILM 基线（两组白点口径）、§4 改写；交付见 `tools/film-offline-REPORT.md` |
| 2026-10-13 | `46906f4` | 记忆库校准（kCount 99 / 引擎插槽 / 离线链缺口） |

## 7. 本阶段新增的守卫与离线工具

`tools/check-filmparams-contract.py`（**改参数契约时与单测一起跑**）：

```bash
python3 tools/check-filmparams-contract.py    # 0 = 通过
```

它补的是两个既有守卫之间的缝：`CameraPresetsTest` 断言的是 Kotlin 常量**硬编码值**（防"Kotlin 忘了改"），
`FilmParamKeysTest` 对拍的是**语义名表 ↔ 字段声明行**（不看 `kCount`）——
**没有任何既有测试会因"C++ `kCount` 单独漂移"而变红**。本脚本把 `kCount` / 字段声明覆盖（0..kCount−1，
[29] 留空例外）/ Kotlin `COUNT` / 语义名表 四条事实摆在一起对。已做 mutation 自检（4 种改坏方式全部报错，
复原后 PASS）：`kCount` 99→100、`COUNT` 99→98、字段名 `engine_slot`→`engine_slotX`、bySlot 98→97。

### 7.1 离线链（阶段 1 判据载体）

| 文件 | 作用 |
|---|---|
| `tools/engine-shader-offline.py` | 离线执行器：P0 → RCD → CCM → **FILM**（+ `--pc3d`）。2026-10-13 纯增量 +383 行，原 295 行未动 |
| `tools/filmic-spline-ref.py` | `computeFilmicSpline` + `solve5` 的 Python 逐行对照实现（可独立自检） |
| `tools/check-film-offline.py` | 独立校验：`solve5` 对拍 numpy / spline 采样表 / **CPU 复现 vs GPU 实跑**（最大绝对差 2.44e-04 = RGBA16F 量化量级） |
| `tools/film-offline-REPORT.md` | 交付报告：逐行对照、uniform 全表、shader 机械改写逐字记录、8 条链外清单 |

**用法（阶段 1 零回归判据）**：

```bash
python3 tools/engine-shader-offline.py --dng test_temp/FILMKIT_20261009-090803.dng \
  --roi 1800,2100,600,1000 --out /tmp/stage1-check
# 然后比对 film_d5.npy 的 CRC32 与 §3.3 的 0x589f8364（默认口径）
```

### 7.2 阶段 1 的「拼接等价」对照基准（架构师实测，2026-10-13）

阶段 1 把 `kFsFilm`（`FrameRenderer.cpp:4046`）拆成「公共模板三段 + 引擎注入段」、初始化期拼接。
**零回归由构造保证**：拼接结果必须与原 `kFsFilm` 体内内容**逐字节相同**。

| 口径 | 值 |
|---|---|
| 行数 | 496 |
| **字符数**（Python `len(str)`） | **25140** |
| **字节数**（UTF-8 / C++ `std::string::size()`） | **35154** |
| **CRC32**（对 UTF-8 字节） | **`0x4fa1ebd3`** |
| **SHA-256** | `f1ebb3178bfb985ed3e0ca19fef95341cfe3be401cb2cc73ea8ae3377c13563c` |

切分点（体内 1-based 行号）与拼接顺序：

```
head        = 1..78      （公共前段：precision/IO/公共 uniforms/luma/局部色调映射）
engine0_def = 79..372    （引擎 0：filmic + gamut 全部函数定义）
mid         = 373..432   （公共中段：main 开头 —— tex/曝光/域映射/局部色调映射）
engine0_main= 433..439   （引擎 0：main 内的 if (uToneK>0) { … } 调用段）
tail        = 440..496   （公共后段：外观层 sat/split/look/mono + 输出）

拼接 = head + engine0_def + mid + engine0_main + tail  ⇒ 35154 字节 / crc32 0x4fa1ebd3
```

⚠ **口径坑（已踩过一次）**：`25140` 是**字符数**、`35154` 才是**字节数**（文件含大量中文注释）。
`std::string::size()` 与 `zlib.crc32` 都是**字节**口径 ⇒ C++ 自检必须对 `35154 / 0x4fa1ebd3`，
写成 `25140` 会误报"不等"。

提取路径自证：`tools/validate-shaders.sh` 的宽松提取（`R"\((.*?)\)"`，66 段 GLSL）与
`engine-shader-offline.py` 的严格提取（`const char* kFsXxx`，64 段）在 `kFsFilm` 上**得到同一份内容**；
`kFsFilm` 体内不含 `)"` 与 `R"(` ⇒ 两种提取都不会被截断。

## 8. 阶段 1 判据与执行清单

> 上位：`docs/engine-slots-design.md` §8 阶段 1「模板化重构」。§7.2 给的是**口径基准**，
> 本节给的是**可执行判据**（命令 + 期望值 + 出处 + 本机实测状态）。

### 契约表（双口径；**判据以 `anchor-splice` 为准**）

阶段 1 的切分有两条口径 —— 它们是**同一份源码**的两种切法，唯一差异是 `engine0_main` 是否
包含收尾的 `    }`（5 字符）：

| 块 | `line-splice`（按行号，交叉验证） | 字符 | `anchor-splice`（**C++ 实际实现 = 判据基准**） | 字符 |
|---|---|---|---|---|
| head | 1..78 | 3839 | `[0, a)`，a = 3839 | 3839 |
| engine0_def | 79..372 | 14778 | `[a, b)`，b = 18617 | 14778 |
| mid | 373..432 | 3188 | `[b, c)`，c = 21805 | 3188 |
| engine0_main | 433..439 | **343** | `[c, d)`，d = 22153 | **348**（含收尾 `    }`） |
| tail | 440..496 | **2992** | `[d, end)` | **2987** |

- `anchor-splice` = `FrameRenderer.cpp` 的 `filmSlices()` 语义：四个锚点字符串在 `kFsFilm` 体内
  `find` 出 a/b/c/e（3839 / 18617 / 21805 / 22115），`d = e + len(kFilmSplitMainBlockEnd)`；
  `kFilmTemplateHead/Mid/Tail` 即其中三段的 `string_view`（单真源、零拷贝）。
- **结论（写死）**：两条路径的**拼接结果都等于原文**（字节 **35154** / crc32 **`0x4fa1ebd3`** /
  sha256 `f1ebb317…`）⇒ **判据等价**；但**以 `anchor-splice` 为准**（那是产品代码的实际行为），
  `line-splice` 只作交叉验证。
- §7.2 的行号表 = `line-splice` 口径，保留作交叉验证；**本表为权威口径** —— 后人按行号实现切分时，
  不要再把 `    }` 划给 tail（否则与 C++ 差 5 字符）。

### 判据 1（源等价）：拼接结果逐字节 == `kFsFilm` 原文

```bash
python3 tools/engine-shader-offline.py --verify-splice --out /tmp/stage1-splice
python3 tools/engine-shader-offline.py --verify-splice --splice-selftest   # 守卫自检
```

2026-10-13 本机实测（`FrameRenderer.cpp` `sha256=452809b0f000c049…`，C++ 侧已按
`filmSlices()` 锚点切片落盘）：

| 项 | 实测值 |
|---|---|
| **路径 A** `line-splice`：按行号**字符偏移**切分（§7.2 的五个行号区间） | 5 块字节和 **35154**；拼接 == 原文 **True** |
| **路径 B** `anchor-splice`：C++ 锚点切分复刻（`filmSlices()`；a/b/c/e = **3839 / 18617 / 21805 / 22115**，d = 22153）**= 判据基准** | 拼接 == 原文 **True**；== 基线锚点 **True** |
| 逐块摘要 == 块级锚点 | **5/5**（切分点未漂移） |
| 切分点语义哨兵 | **8/8** |
| 结论 | **PASS**，退出码 **0** |

- **证明力在哪（重要）**："按偏移切片再拼回"**恒等于**原文，属平凡真。判据 1 的非平凡部分是
  ① 拼接结果摘要 == `0x4fa1ebd3`（证明 shader 真没被改）；② **逐块**摘要 == 块级锚点
  （证明切分点没漂移）—— 只锚整体不够：切分点整体偏 1 行时 head/engine0_def 的边界行都只是注释，
  整体摘要与语义哨兵**都抓不到**（该漏检由 `--splice-selftest` 的 ① 号 mutation 实测抓出后，
  才补上块级锚点）。
- **两条路径都跑、都判 PASS 才算通过**：C++ 锚点路径（`mode: anchor-splice`）证明的是
  "**产品代码切出来的东西**拼回去 == 原文"，比脚本自己按行号切更有证明力；锚点缺失/错序 ⇒
  路径 B 判 `BROKEN` 并**退出码非 0**（不回退到行号切分，否则判据退化成"永远通过"）。
  ⚠ 它是 C++ 语义的 **Python 复刻**，不是执行 C++；真机侧另有 `engine.film_src` 日志佐证。
- ⚠ **契约行号口径 vs C++ 锚点口径差 5 字符**（不影响判据）：C++ 的 `engine_main` 含收尾 `    }`
  （`d = e + len(kFilmSplitMainBlockEnd)`）⇒ 该块 348 vs 343 字符、tail 2987 vs 2992 字符；
  **拼接结果一致**，故两条路径都 PASS。
- 守卫自检：`--splice-selftest` 的 4 项 mutation（切分点漂移 / 丢换行 / 篡改源基线 / 篡改锚点）
  必须**全部检出** —— 验证工具自身出错 = 假绿，比不验证更危险。
- 产物：`<out>/steps.json` → `steps.splice`（`verdict` / `path_a_line_split` / `path_b_anchor_splice`）。

### 判据 2（链等价）：`film_d5.npy` 的 CRC32 不变

```bash
python3 tools/engine-shader-offline.py --dng test_temp/FILMKIT_20261009-090803.dng \
  --roi 1800,2100,600,1000 --out /tmp/stage1
# 白点 4.0 口径（R-3 真机定档值）加 --film-white-ev 4.0
```

| 口径 | 命令差异 | `film_d5.npy` CRC32（期望） | SHA-256 | 出处 |
|---|---|---|---|---|
| **代码默认**（`[37]/[38]` = −7.65 / +4.56） | 默认 | **`0x589f8364`** | `ce1c9a90c11dbb69…` | §3.3 |
| 白点 4.0 | `--film-white-ev 4.0` | **`0xd46d7b5e`** | `7811827393c4af94…` | §3.3 |

- 两口径已于 2026-10-13（C++ 落盘后）**复跑确认**与 §3.3 一致；摘要同时写进
  `<out>/steps.json` 的 `steps.film.digest`。
- **链外清单**（§4.2）：HR 高光重建 / `uLocal*` / PC3D 标定 / `kFsOut` / MFNR / defringe 不在链内
  ⇒ 判据 2 只证明"`kFsFilm` 自身输出没变"，**不能**主张成片零回归。

### 判据 3：shader 静态校验全绿 + 真机 program 链接成功

```bash
bash tools/validate-shaders.sh        # 期望：全部 shader 校验通过（退出码 0）
```

- 本机 2026-10-13 实测：提取 **66** 个 GLSL 字符串、**66 OK / 0 FAIL**（glslang = `tools/bin/glslang`，
  该目录已 gitignore；缺失时脚本退出码 2 并提示 `brew install glslang`）。
- **双形态适配**（阶段 1 后 `kFsFilm` 可能被真拆成模板常量 + 注入段）：形态 1（`kFsFilm` 完整）
  走原逻辑、行为一字不变；形态 2（真拆）把模板三段 + 注入段**拼回整份**再校验，散段不再单独校验
  （逐段过滤只有 head 带 `#version` ⇒ 会把 `main` 丢掉、校验一个残缺 shader = **假绿**；该 bug 已用
  构造文件实测抓出并修复）；半拆（`kFilmTemplate*` 只落盘一部分）直接报错退出，不静默漏检。
  脚本可带一个可选参数指定源文件，用于上述形态回归：`bash tools/validate-shaders.sh <path>`。
- **真机**：`prog_film_ != 0`（`linkProgram(vs_plain_, fs_film)` 的结果）。v0.5.13 教训 = 设备端
  编译失败即全链路黑屏，构建与单测全绿都不代表 GLSL 合法。

### 判据 4（兜底，必须真机）：同帧 A/B 成片 CRC32

离线链不含 HR / `uLocal*` / PC3D 标定 / `kFsOut` / MFNR（§4.2）⇒ 判据 1–3 全绿**仍不能**主张
"成片零回归"。最终判据 = 真机同帧（同一 RAW、同参数、`[98]=0`）改造前 vs 改造后的**成片 CRC32
逐字节一致**，并附两条日志：`prog_film_ != 0`、`engine.film_src` 的
`"bytes":35154,"chars":25140,"crc32":"0x4fa1ebd3","identical":true,"zero_regression":"ok"`。
> 本机（macOS，无 adb）**未跑**此条 —— 属真机/CI 项。

### 判据速查

| # | 命令 | 期望 | 本机状态 |
|---|---|---|---|
| 1 | `python3 tools/engine-shader-offline.py --verify-splice` | 两路径拼接 == 原文；CRC32 `0x4fa1ebd3`；退出 0 | ✅ 实测 PASS |
| 1b | `… --verify-splice --splice-selftest` | **6** 项 mutation 全部检出（切分点漂移 / 丢换行 / 篡改源基线 / 篡改锚点 / 差异打印 / CLI 退出码） | ✅ 实测 |
| 2 | `python3 tools/engine-shader-offline.py --dng … --roi … --out /tmp/stage1` | `0x589f8364`；白点 4.0 `0xd46d7b5e` | ✅ 实测一致 |
| 3 | `bash tools/validate-shaders.sh` | 全绿（66/66）+ 真机 `prog_film_ != 0` | ✅ 本机全绿；真机项未跑 |
| 3b | 真机日志 `engine.film_src` | `identical:true` / `zero_regression:"ok"` | ⬜ 未跑（需真机） |
| 4 | 真机同帧 A/B 成片 CRC32 | 逐字节一致 | ⬜ 未跑（需真机） |

### 本阶段抓出的两个假绿（教训）

1. **只锚整体摘要不够**：切分点整体偏 1 行时，head 末行与 engine0_def 首行**都只是注释** ⇒ 整体摘要
   与语义哨兵**都抓不到**，判据会给出假绿。**必须**给 5 块各锚 crc32/sha256（**块级锚点**）；
   `--splice-selftest` 的 ① 号 mutation 就是这条的回归守卫。
2. **`validate-shaders.sh` 形态 2 的逐段过滤**：模板真拆后若逐段判断 `#version`，则只有 head 带头 ⇒
   拼回整份**根本没进候选**、filmic **实际未被校验**（假绿；glslang 对缺 `main` 的片段并不报错）。
   **必须**先拼回整份再判断；半拆（`kFilmTemplate*` 只落盘一部分）应直接报错退出，不静默漏检。

> 共同点：**验证工具自身出错 = 假绿，比不验证更危险**。两条都是靠**负向自检（mutation）**抓出来的，
> 而不是靠正向跑绿 ⇒ `--splice-selftest` 与形态回归应与判据同跑（判据速查 1b）。
