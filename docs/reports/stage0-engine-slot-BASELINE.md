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
