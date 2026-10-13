# 引擎插槽「阶段 0」基线（零回归对照基准）

> 上位方案：`docs/engine-slots-design.md` §5（`[98] engine_slot`）/ §6（引擎注册表）/ §8（阶段 0 验收）。
> 本文件记录**改动前**（提交 `a6adb35`）采集到的基线，供阶段 1「模板化重构」与阶段 2「sigmoid 引擎」
> 做逐字节回归对照。**它不是新的判据来源**，判据仍以方案 §8 为准。

---

## 1. 本阶段能采到什么（先说局限，再说数据）

方案 §8 阶段 0 的验收判据是「四处同步通过 + `CameraPresetsTest` 绿 + **`[98]=0` 时同帧 CRC32
与基线逐字节一致**」。但**"同帧成片 CRC32"必须由真机拍照链路产生**，本机（macOS，无 adb、无
Android GPU 运行时）采不到。实测下来，本机可采的基线只有**离线链路到 D4 为止的那一段**：

| 段 | 本机能否采基线 | 依据 |
|---|---|---|
| P0（`kFsRawDenoise`）→ RCD → CCM（D3→D4） | ✅ 可采（本文件） | `tools/engine-shader-offline.py` 已接入并跑通 |
| **`kFsFilm`（FILM，含 filmic + gamut + 外观层）** | ❌ **未接入离线链** | 见 §4 —— 这是阶段 1 的**前置缺口** |
| HR（高光重建）/ PC3D（`kFsPc3d`）/ `kFsOut`（OETF/颗粒/LUT/锐化） | ❌ 未接入 | 同上（方案 §10.6） |

**因此本基线对阶段 0 的作用是有限的、必须如实标注**：
阶段 0 **没有改动任何 shader**（`FrameRenderer.{h,cpp}` 零改动），`[98]` 也**没有任何 kernel 读取**，
所以「`[98]=0` 时与改动前逐字节一致」在阶段 0 是**结构性成立**的（见 §3 的三条机械依据），
本基线在这一阶段主要起*哨兵*作用 —— 若有人误改了 RCD/CCM/P0 段，下面四个摘要会立刻变化。
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

## 4. 缺口与阶段 1 的前置条件（**必须先解决**）

`tools/engine-shader-offline.py` 的文件头声称"已接入 4 个 pass（…… 4. FILM `kFsFilm`）"，
但**实读该脚本（295 行）没有任何 `kFsFilm` 代码路径**：它只跑到 CCM（`ccm_d4.npy`）就结束，
`steps.json` 也只有 `p0/rcd/ccm` 三项。**文件头注释与实现不符**。

后果：方案 §8 阶段 1 的硬判据是「拆 `kFsFilm` 为模板 + 引擎 0 注入段后，`[98]=0` 时 CRC32 逐字节等价」。
**在这条离线链上，该判据当前无法执行** —— 能证明的只有"不涉及 FILM 的段没退化"。

阶段 1 开工前需要（二选一，由用户/架构师拍板）：

- **A（推荐，与既有并行待办合并做一次）**：给离线执行器补上 FILM 段 —— 直接执行 `kFsFilm`，
  其中 filmic 系数由**照搬 `computeFilmicSpline` 的 CPU 侧求解**产生（需逐行对照 C++，避免
  "numpy 直译"式偏差），同时按 `docs/engine-slots-design.md` §10.6 把 PC3D 一并接上（LSC 暂恒等）。
  风险：FILM 段依赖 `uToneK/uSat/uLook/…` 等十余个 uniform 与 CPU 侧统计值，接线本身要一轮真机对拍。
- **B（更省，但证明力弱）**：阶段 1 只做"结构等价"论证（拼接后的 shader 源码与改造前**逐字符相同**）+ 真机同帧 A/B CRC32。

> 无论选哪条，**"离线好看、真机判死"的历史教训（`lessons.md` 10-06 那条）要求：离线链的缺口必须
> 在判据里写明"哪一段不在链内"**，不能拿部分链的绿去主张整链零回归。

## 5. 与方案 §8 的对应

| 方案 §8 阶段 0 交付物 | 状态 | 落点 |
|---|---|---|
| 建 `EngineRegistry`（只含引擎 0） | 已交付 | `core/engine/src/film/EngineRegistry.{h,cpp}` |
| `FilmParams` 加 `[98]` | 已交付 | `core/engine/src/film/FilmParams.h`（`kCount` 98→99） |
| 采集基线：成片 CRC32 | **部分**（离线到 D4；真机成片待采） | 本文件 §3 |
| 采集基线：filmic 参数快照 | 见下 | 本文件 §5.1 |
| 四处同步 + `CameraPresetsTest` 绿 | 已交付（六处同步，见 §3.2） | Kotlin 侧同批改动 |

### 5.1 filmic 参数快照（改动前的取值来源）

改动前**没有**离线 fixture 形式的 filmic 参数快照；现成的只有运行期日志：

- 预览链：`engine.preview.filmic`（每 120 帧一条，`FrameRenderer.cpp:13656`），字段
  `blackEv / whiteEv / w99 / w999 / wmax / cp / linMid / ratio / pLo / pHi / localRef / w / h`。
- **拍照链没有对应日志**（该事件在 `if (!still)` 分支内）⇒ 若阶段 1 需要"拍照时的 filmic 参数快照"，
  得先补一条 `engine.still.filmic`（或把该事件的 `still` 维度带上）。**这是阶段 1 的准备工作，不在阶段 0 内。**
- 当前生效的白点取值（记忆 `project.md` 2026-10-13）：内置 `[37][38]` = `−8 / 4.0`（死白起点 3.55 EV，
  见 `docs/reports/r3-white-point-refit-REPORT.md`）。

## 6. 变更记录

| 日期 | 提交 | 说明 |
|---|---|---|
| 2026-10-13 | `a6adb35`（基线点） | 阶段 0 前的仓库状态；本文件 §2/§3 的基线在此采集 |
| 2026-10-13 | （阶段 0 卡） | `[98] engine_slot` + `EngineRegistry` 骨架 + 本基线 |

## 7. 本阶段新增的守卫脚本

`tools/check-filmparams-contract.py`（**改参数契约时与单测一起跑**）：

```bash
python3 tools/check-filmparams-contract.py    # 0 = 通过
```

它补的是两个既有守卫之间的缝：`CameraPresetsTest` 断言的是 Kotlin 常量**硬编码值**（防"Kotlin 忘了改"），
`FilmParamKeysTest` 对拍的是**语义名表 ↔ 字段声明行**（不看 `kCount`）——
**没有任何既有测试会因"C++ `kCount` 单独漂移"而变红**。本脚本把 `kCount` / 字段声明覆盖（0..kCount−1，
[29] 留空例外）/ Kotlin `COUNT` / 语义名表 四条事实摆在一起对。已做 mutation 自检（4 种改坏方式全部报错，
复原后 PASS）：`kCount` 99→100、`COUNT` 99→98、字段名 `engine_slot`→`engine_slotX`、bySlot 98→97。
