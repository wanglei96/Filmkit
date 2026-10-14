# 阶段 3 真机验收清单（引擎插槽：**切换与预设**）

> 状态：**待真机执行**（本机无设备：`adb devices` 为空）。本清单是**可复跑的验收步骤**，
> 不是已完成项。判据 1–4 中**纯函数可达的部分已由 JVM 单测执行**（见 §6）；
> **判据 5 / 6 与全部像素级判据只能真机**（离线链只覆盖 `kFsFilm` 单段，见
> [stage3-scope-audit-REPORT.md](stage3-scope-audit-REPORT.md) §6）。
>
> ⛔ **执行前必读 §0** —— 阶段 3 审计曾抓到 3 条缺陷（P0 会让引擎 1 **空转**，而原六条判据
> **全部照样通过**）；**三条已修**，但 **判据 0 是它们的常驻守门人，必须执行**。
>
> 设计依据：[engine-slots-design.md](../engine-slots-design.md) §8.1（B 旁路清单 / B2 恒等值表 / D 判据）·
> 审计依据：[stage3-scope-audit-REPORT.md](stage3-scope-audit-REPORT.md)（含**修订后的正式判据表**：新增判据 0、判据 3 补 `[22]`/`[35]`）
> 历史口径：[stage2-acceptance-CHECKLIST.md](stage2-acceptance-CHECKLIST.md)（判据 E/F/G/B/C 的读法与"验证包"做法沿用）

---

## 0. 前置闸：三条缺陷的现状（**P0/P1/P2 已修，判据 0 常驻**）

| # | 缺陷 | 现象（若带病验收） | 状态 |
|---|---|---|---|
| **P0** | `EngineScope.NEUTRALIZE` 的 `put(28, 0.0)` | 引擎 1 的 `sigmoidTone` 只在 `if (uToneK > 0.0)` 内被调用 ⇒ **[28]=0 让引擎 1 的影调段整段跳过**。离线实测：引擎 1@`uToneK=0` 与引擎 0@`uToneK=0` 的 FILM 段输出**逐字节相同**（`crc32 0x189e4126`）。⇒ 引擎 1 = 纯直通（丢基准影调、发灰），而**判据 2/3/4/5/6 全部照样通过** | ✅ **已修**（`put(28, 1.0)`；引擎 1@`uToneK=1.0` = `0x8fdb4e38`，引擎 0 仍 `0x589f8364`）。依据见审计报告 §P0 |
| **P1** | `[22] sat_high` 未入 `NEUTRALIZE` | 一个饱和度滑杆**同写 `[20][21][22]`**，但表里只钉了 `[20]/[21]` ⇒ 引擎 1 下 `sat = 1 + wh·(sat_high − 1)`，**高光档仍在跟着用户值走**（半旁路）。判据 3 的槽位清单也没列 `[22]` ⇒ 抓不到 | ✅ **已修**（`put(22, 1.0)`；`NEUTRALIZE` 13→**14 槽**）。依据见审计报告 §P1 |
| **P2** | 未注册 `[98]` 的降级语义两侧不一致 | Kotlin 先按"非 0 即旁路"清零、C++ 再降级 ⇒ 得到第三种状态"引擎 0 + filmic 参数被清零" | ✅ **已修**（`KNOWN_ENGINE_SLOTS`，未注册 ⇒ 按 0 处理、不旁路）。依据见审计报告 §P2 |

> ⚠ **P0/P1/P2 虽已修，判据 0 **仍必须执行** —— 它是这三条的**常驻守门人**：
> 任何一次后续改动（换 GLSL、动 `NEUTRALIZE`、加引擎）都可能让引擎 1 重新变成空转，
> 而其余六条判据**察觉不到**。见下。

---

## 判据 0（★ 新增，最高优先级）：**引擎 1 必须真的改变画面**

> **为什么必须新增**：原六条判据**没有任何一条**能发现"引擎 1 什么都没做"。判据 2/3 只要求
> "改 filmic 槽位成片不变" —— 一个空转的引擎当然满足；判据 5（A→B→A）也满足；判据 4 的槽位
> 走公共段，也满足。⇒ **六条全绿 ≠ 引擎 1 生效**。这条闸就是 2026-10-14 发现 §P0 的那一条。

### 本机可执行形式（离线链，0.6 s，**先跑这条**）

```bash
cd /Users/wl/Downloads/胶片相机
S="--dng test_temp/FILMKIT_20261009-090803.dng --roi 1800,2100,600,1000"
python3 tools/engine-shader-offline.py $S --out /tmp/c0 --engine 0 --film-tone-k 1.0
python3 tools/engine-shader-offline.py $S --out /tmp/c1 --engine 1 --film-tone-k 1.0
```

| 项 | 期望值 | 失败判据 |
|---|---|---|
| 引擎 0 的 `film_d5.npy` crc32 | **`0x589f8364`**（= 阶段 2 基线） | 不等于基线 ⇒ 判据 1（零回归）**先红了**，停止后续验收 |
| 引擎 1 的 `film_d5_sigmoid.npy` crc32 | **`0x8fdb4e38`**（`--film-tone-k 1.0`，= 阶段 2 引擎 1 基线） | 见下 |
| **两者必须不同** | 差异 ~9.7% 像素 > 1 LSB（max 5.64 / mean 0.301） | **相同 ⇒ 引擎 1 空转**（就是 §P0 的形态） |

**空转的特征指纹（留作探针；`NEUTRALIZE[28]` 一旦被改回 0，这条就会复现）**：

```bash
python3 tools/engine-shader-offline.py $S --out /tmp/c0z --engine 0 --film-tone-k 0.0
python3 tools/engine-shader-offline.py $S --out /tmp/c1z --engine 1 --film-tone-k 0.0
# 两个 npy 的 crc32 **必须相同 = 0x189e4126**
# ⇒ "uToneK=0 时两引擎输出逐字节相同" = 引擎 1 的影调段被整段跳过。
# ⚠ 修复后这两条命令**仍然给出相同的 0x189e4126** —— 这是**预期**（它探的是"uToneK=0 的语义"），
#   不是失败。失败的是**判据 0 主体**（`--film-tone-k 1.0` 那两条）给出相同 crc32。
```

> ✅ **修复后实测（2026-10-14，架构师独立复跑，与卡 C 一致）**：
> 引擎 0@1.0 = **`0x589f8364`**（零回归未受影响）· 引擎 1@1.0 = **`0x8fdb4e38`**（sigmoid 生效）
> · 引擎 1@0.0 = `0x189e4126`（空转，保留为探针）。

### 真机可执行形式

| 项 | 操作 | 期望值 | 失败判据 |
|---|---|---|---|
| 0-a | 同场景、同曝光、同参数，**引擎 0 拍一张、引擎 1 拍一张** | 两张成片**必须不同**，且引擎 1 的观感 = sigmoid（高光层次更多，见 §判据④的读法） | ① 两张**完全相同** ⇒ 引擎 1 空转（P0）；② 引擎 1 明显**发灰/无对比**（像"关掉影调"）⇒ 也是 P0 |
| 0-b | 日志 `engine.film_src` 的 `slot` / `engine` / `crc32` | 切到引擎 1 后出现 `slot:1, engine:"sigmoid", crc32:"0xbf298386"` | 长期停在 `slot:0` ⇒ 引擎选择没走到 C++（Kotlin 侧没写进 `[98]` / 写了但被覆盖） |

> ⚠⚠ **不要用"两包画面不同"当引擎 1 生效的证据**：P0 下两引擎画面**也会不同**（因为引擎 1
> 丢了基准影调，32.9% 像素 > 1 LSB）—— 那是"少了一条曲线"，不是"换了一条曲线"。
> **唯一可靠的证据是"差异与 sigmoid 的特征一致"**（高光层次增加、暗部略浮起）或上面的离线 crc32 对照。

---

## 判据 1（硬）：`engine_slot = 0` 零回归

| 项 | 看什么 | 期望值 | 失败判据 |
|---|---|---|---|
| 1-a | App 日志 Tab 搜 `film_src` | 每一条都是 `{"slot":0,"engine":"filmic","bytes":35154,"chars":25140,"crc32":"0x4fa1ebd3","identical":true,"zero_regression":"ok"}` | 任一条 `identical:false` 或 `zero_regression` 非 `ok`（level=Error）⇒ **立即停止验收并回报**（阶段 1/2 的零回归被破坏） |
| 1-b | 全新安装后**不碰任何引擎开关**，直接拍照 | 与阶段 2 的成片一致（`[98]` 默认 0） | 画面与阶段 2 不同 ⇒ 默认档被动过 |
| 1-c | （本机已执行 ✅）`applyEngineScope(arr, 0)` | 返回**同一实例**、逐位不变 | 见 §6 的单测 |

> 1-a 的读法与"每次 engine init 记一条（不是整个生命周期一条）"的口径，与
> [stage2-acceptance-CHECKLIST.md](stage2-acceptance-CHECKLIST.md) 判据 E **完全一致**，不重复解释。

---

## 判据 2 / 3：`engine_slot = 1` 下 filmic 槽位**真的失效**（旁路）

### 方法论（**这一节决定验收是否有效，先读**）

引擎 1 下这些控件**不进 UI 树**（`slotActiveInEngine` ⇒ `ParamControls.kt:738`），所以**没法在引擎 1
下"拖滑杆"来构造差异**。可执行的做法只有一条，而且它正好是真实用户路径：

> **① 在引擎 0 下把参数改成非默认（那里 UI 可见）→ ② 存成预设（或改设置页开关）→
> ③ 切到引擎 1 → ④ 套用该预设 / 拍照 → ⑤ 与"不套该预设"的引擎 1 成片逐字节比。**

**每一步都必须配一条「正对照」**：同样的操作在**引擎 0** 下做一遍 ⇒ **必须不同**。
若引擎 0 下也相同，说明这条操作**根本没进引擎**（预设没套上 / 开关没生效）⇒ **该次验收作废（假绿）**。

| 判据 | 槽位 | 构造方式 | 建议值 | 引擎 1 期望 | **正对照（引擎 0）** | 失败判据 |
|---|---|---|---|---|---|---|
| **2** | `[5..7]` shoulder / `[8..10]` toe | 预设（在引擎 0 下把「胶片曲线」相关项调离默认后保存） | `0.6`（默认 `0.01`） | **逐字节相同** | **必须不同** | ① 引擎 1 下不同 ⇒ `computeEditorCurve` 旁路失效；② 引擎 0 下相同 ⇒ 构造无效 |
| **2** | `[37]` 黑点 | 设置页「黑点（多暗算黑）」滑杆（引擎 0 下调）→ 切引擎 1 | `-5.0`（默认 `-7.65`） | **逐字节相同** | **必须不同** | 同上 |
| **2** | `[38]` 白点 | 设置页「白点（多亮算白）」滑杆（引擎 0 下调）→ 切引擎 1 | `+3.0`（默认 `+4.56`） | **逐字节相同** | **必须不同** | 同上 |
| **3** | `[28]` 基准影调 | 设置页「基准影调」开关（引擎 0 下关掉）→ 切引擎 1 | 关 | **逐字节相同** | **必须不同** | 不同 ⇒ `uToneK` 公共段旁路失效。**⚠ 这条现在同时是 §P0 的守门人**：`[28]` 的恒等值是 **1.0**（= 默认），若它被写成 0，引擎 1 会空转 —— 用判据 0 交叉确认 |
| **3** | `[20]`/`[21]`/**`[22]`** 饱和补偿 | 预设（引擎 0 下把「饱和度」调离 100%）→ 切引擎 1 套用 | `1.4`（默认 `1.0`） | **逐字节相同** | **必须不同** | 不同 ⇒ `uSat` 公共段旁路失效。**⚠ 必须三档一起验**：一个滑杆同写三槽，只验 `[20]`/`[21]` 会漏掉 **§P1 的半旁路**（`[22]` 是高光档权重 `wh`） |
| **3** | `[36]` 局部色调 | 设置页「局部色调映射」开关（引擎 0 下开）→ 切引擎 1 | 开（`[36]=0.25`） | **逐字节相同** | **必须不同** | 不同 ⇒ `uLocalStrength` 公共段旁路失效 |
| **3** | `[35]` auto_tone | 设置页「自适应影调」开关（引擎 0 下开）+ **高光保护开** → 切引擎 1 | 开（`[35]=1`） | **逐字节相同** | **必须不同** | 不同 ⇒ `sw999` 副作用通道旁路失效（卡 A 的漏径 A） |
| **3** | 未注册 `[98]`（**P2 回归**） | 手改预设 JSON 为 `"engine_slot":7.0` 后套用 | 7 | **与该预设的 `engine_slot:0` 版逐字节相同**（两侧都走引擎 0） | —— | 不同 ⇒ P2 回归（"引擎 0 + filmic 参数被清零"的第三种状态） |

> `[40] gamut` 不需要单独验：它只被引擎 0 的注入段消费（`uGamut` 的声明段与使用段都被引擎 1 的整体
> 替换吞掉 ⇒ `location = −1`），离线实跑已确认它在"被跳过的 22 个 uniform"里（审计报告 §1）。

### 怎么看"逐字节相同"

成片的 JPEG EXIF 里含拍摄时间 ⇒ **文件 sha256 一定会不同**，必须比**像素**：

```bash
adb pull /sdcard/DCIM/FilmKit/A.jpg && adb pull /sdcard/DCIM/FilmKit/B.jpg
python3 - <<'PY'
import numpy as np, sys
from PIL import Image
a=np.asarray(Image.open('A.jpg').convert('RGB'), dtype=np.int16)
b=np.asarray(Image.open('B.jpg').convert('RGB'), dtype=np.int16)
print('shape', a.shape, b.shape)
if a.shape!=b.shape: print('FAIL: 尺寸不同（连拍被裁切/旋转不同）'); sys.exit(1)
d=np.abs(a-b); print('max|d|=%d  mean=%.4f  diff_px=%d/%d'%(d.max(), d.mean(), (d.any(-1)).sum(), a.shape[0]*a.shape[1]))
print('VERDICT:', 'SAME(逐像素)' if d.max()==0 else 'DIFFERENT')
PY
```

- **"逐字节相同"的验收口径 = `max|d| == 0`**（逐像素相同）。
- **拍摄条件必须锁死**：三脚架/固定手机 + 固定光源 + **固定曝光**（关自动曝光，或确认两次
  `engine.raw.render` 日志的 `"ev"` 字段相同）。AE 漂 1/3 EV 就会让"旁路生效"看起来像"没生效"。
- 更稳的替代（若现场条件难锁）：**用同一张 DNG 在编辑器里导出**（源固定 ⇒ 完全确定），
  但那只覆盖编辑器链（见判据 6），**不能**替代拍照链的判据 2/3。

---

## 判据 4：`engine_slot = 1` 下**通用**参数必须正常生效（防误旁路）

| 项 | 构造方式 | 引擎 1 期望 | 失败判据 |
|---|---|---|---|
| 4-a | 预设里放 `exposure_ev = +0.5`（或直接拖 EV 滑杆） | **必须不同**（且方向正确：更亮） | 相同 ⇒ **误旁路**（把通用槽也归零了）——比判据 2/3 更严重 |
| 4-b | 外观/风格层：颗粒强度 `[26]`、暗角 `[66]`、分离色调 `[23]/[24]`、色温 `[88]` 各改一项 | **必须不同** | 同上 |
| 4-c | 预设里放 `contrast = 1.5`（`[4]`） | **拍照侧**：引擎 1 下**应不同**（`[4]` 被刻意保留）；**编辑器侧**：也必须不同 | 相同 ⇒ 把 `[4]` 误当 filmic 专属旁路掉了（§8.1 B 的"保留"行被破坏） |

---

## 判据 5：两引擎互切 **A→B→A 无串扰**

| 项 | 操作 | 期望值 | 失败判据 |
|---|---|---|---|
| 5-a | 引擎 0 拍 A1 → 切引擎 1（拍 2–3 张）→ 切回引擎 0 → 拍 A2 | **A1 与 A2 逐像素相同**（`max\|d\| == 0`） | 不同 ⇒ 有跨引擎残留状态 |
| 5-b | 反向：引擎 1 拍 B1 → 切引擎 0 → 切回引擎 1 → 拍 B2 | **B1 == B2** | 不同 ⇒ 同上（反向也要查 —— 单向通过不能排除"只在某个方向残留"） |
| 5-c | 切换后日志 `engine.slot_built` | 每次换 slot 出现一条，`slot` 与当前引擎一致 | 不出现 ⇒ program 没换（会表现为"切了引擎画面不变"） |
| 5-d | 切换后 `film_src` 的 `crc32` | 引擎 0 = `0x4fa1ebd3`；引擎 1 = `0xbf298386`；来回切都应回到同一值 | 来回切给出**不同**的 crc32 ⇒ 源码组装有状态污染 |

> ### ⚠ 判据 5 必须用**成片**比，不能用预览帧
>
> 预览链有**历史状态**：`ev_smooth_black_/ev_smooth_white_`（`:13934-13937` 的 EMA）与
> `g_domain_ratio`（`:14051` 的在线 EMA，仅预览域生效）。它们**不随引擎切换重置** ⇒ 预览帧的
> "逐字节"没有定义。成片走 `still=true` 分支，**直通、不过 EMA**（`:13925-13927`）⇒ 确定性。
>
> ### 机制级依据（审计已实读确认，可当作"为什么应当通过"的解释）
>
> `buildFilmProgram()`（`:6890`）在换 program 之后**重查全部 uniform location**
> （`:6900 queryFilmUniforms()`，注释原文"location 是 per-program 的 ⇒ 必须重查"）
> ⇒ 不存在"拿旧 program 的 location 往新 program 写"的隐性串扰。

---

## 判据 6：编辑器侧旁路（`kFsStyle` 不随引擎切换）

> 这条是**独立落点**（§8.1 C 落点 2）：编辑器链的参数在 `EditorExport`/`EditorEngineSession`
> 组装，`passStyle` 的 `computeEditorCurve`（`FrameRenderer.cpp:14703`）不随 `[98]` 换。
> **不能靠拍照链的结论外推**（两条链各自有一处 `engineScoped`）。

| 项 | 操作 | 期望值 | 失败判据 |
|---|---|---|---|
| 6-a | 打开一张 `[98]=1` 的照片 → 套用一条含非恒等 `[5..10]`/`[37]`/`[38]` 的预设 → 导出 | 与"套用前导出"**逐像素相同** | 不同 ⇒ 编辑器侧旁路失效（`computeEditorCurve` 仍在吃这些槽） |
| 6-b | 同上但 `[98]=0` 的照片（**正对照**） | **必须不同** | 相同 ⇒ 预设没生效/没套上 ⇒ 6-a **作废** |
| 6-c | `[98]=1` 的照片，改 `[20]/[21]`（`passStyle` 的 `u_style_.sat_*` 仍生效）后导出 | **逐像素相同** | 不同 ⇒ 编辑器侧 `uSat` 旁路失效 |
| 6-d | `[98]=1` 的照片，改**通用**项（曝光 `[0]`、对比度 `[4]`、颗粒、暗角、色温）后导出 | **必须不同** | 相同 ⇒ 误旁路 |

- **怎么得到"`[98]=1` 的照片"**：在引擎 1 下拍一张（`[98]` 会随参数快照存进照片），或对任意照片
  套用一条带 `engine_slot:1` 的预设。
- **导出比像素**：导出是 JPEG/HEIF ⇒ 用 §"怎么看逐字节相同"的同一段脚本（EXIF 时间戳会让
  文件 sha256 不同，**不要**比文件字节）。

---

## 附 A：怎么构造"引擎 1 验证包"（沿用阶段 2 的路径）

> 阶段 3 **已经有 UI 引擎选择入口**（`ENGINE_SLOT_CHOICES = listOf(0, 1)`，`ParamControls.kt:718`），
> 正常情况下**直接切**即可，不需要验证包。验证包仍有用武之地：① UI 入口不可用/被 gate 掉时；
> ② 需要"装上去默认就是引擎 1"的干净对照臂（免去"先切再拍"引入的人为差异）。

1. 改 `app/src/main/java/com/filmkit/app/ui/CameraPresets.kt` 的 `neutral()`：
   `this[98] = 0.0` → `this[98] = 1.0`（`:309-311` 附近）。
2. `./gradlew :app:assembleDebug`（`JAVA_HOME` 见 §6）→ 安装。
3. ⚠ 改后 `CameraPresetsTest`（断言 `[98]` 必须为 0）会红 ⇒ **验证包不得合并，验完即弃**，
   并**立即把源码恢复成 `0.0`**（`git diff` 应为空）。
4. 不推荐的路：adb 改 `SharedPreferences("filmkit")` 的快照 JSON —— 需 debug 包 + `run-as`，
   且会被下一次拍照覆盖。

## 附 B：日志字段速查（⚠ 含**一个会误导人的陷阱**）

| 事件 | 字段 | 用途 |
|---|---|---|
| `engine.film_src` | `slot`/`engine`/`bytes`/`chars`/`crc32`/`identical`/`zero_regression` | 判据 1（`identical:true`）、判据 0-b、判据 5-d |
| `engine.slot_built` | `slot` | 判据 5-c（每次换引擎一条） |
| `engine.slot_unavailable` | `requested` | **出现即异常**（`[98]` 被写成注册表里没有的值） |
| `engine.slot_build_failed` | `requested`/`fallback` | **出现即异常**（设备端 GLSL 编译/链接失败 ⇒ 保持引擎 0 出图） |
| `engine.film_split_missing` | —— | **出现即异常**（`kFsFilm` 切分锚点坏了）⇒ 退回整体字面量 |
| `engine.raw.render` | **`tone`**（`[28]` 的生效值）、`auto`（`[35]`）、`gm`（`[40]`）、`ct`（`[4]`）、`lt`（`[36]`）、`ev`（`[0]`） | **判据 3 的机器判据**：引擎 1 下 `tone` 必须是 **1.0**（P0 未修时是 **0.0**）；`auto`/`gm`/`lt` 应是恒等值 |
| `engine.raw.render` | `latSh`/`latToe`/`fPow`/`fCon`/`blackEv`/`whiteEv`/`hrWhiteEv` | ⚠⚠ **陷阱**：见下 |

> ### ⚠⚠ 陷阱：引擎 1 下 `whiteEv`/`fPow`/`latSh` 等字段**仍然有值、还会变**
>
> `updateFilmicEV` 在 `passFilm` 里是**无条件调用**的（`:14014`；`:14005-14006` 注释原文
> "刻意放在 passFilm 内 —— 预览 / YUV 静拍 / RAW 静拍三条路径都经过这里"）。它在引擎 1 下
> 照样跑直方图统计、算 EV、解 6 组 5×5 线性系统，只是算完的系数被 `glUniform*(−1, …)`
> **静默丢弃**。
>
> ⇒ **"日志里 filmic 参数在变"不能推断"filmic 在影响画面"**；
> ⇒ 反过来，"引擎 1 下日志仍在报 filmic"**不是缺陷**（只是每帧白跑一次 CPU 成本）。
>
> 同理 `hrWhiteEv` 是**纯日志字段**：`runHighlightReconstruct` 内是
> `(void)white_ev_input_domain;`（`:12439`），白点自 v0.10.145 起**不再是 HR 阈值来源**
> ⇒ `[37]/[38]` 不会经 HR 影响引擎 1 的像素（审计报告 §3）。

## 附 C：回报模板（若发现问题）

```
包：filmkit-x.y.z.apk（versionCode / versionName；注明"正常包 / 引擎 1 验证包"）
判据：0 / 1 / 2 / 3 / 4 / 5 / 6 中的哪一条（多项就分开写）
现象：（一句话）
复现：场景 + 操作步骤 + 是否每次必现
正对照：同操作在引擎 0 下的结果（**必须附** —— 没有正对照的"相同"不构成证据）
量化：max|d| / diff_px（用 §"怎么看逐字节相同"的脚本）
日志：engine.raw.render 那一行的原文（尤其 tone/auto/gm/lt/ev）+ film_src 那一行
照片：若有成片异常，附一张原图
```

---

## 6. 已由本机 JVM 单测执行的部分（判据 1–4 的纯函数可达面）

```bash
cd /Users/wl/Downloads/胶片相机
export JAVA_HOME=/opt/homebrew/opt/openjdk@23
./gradlew :app:testDebugUnitTest --tests "com.filmkit.app.camera.Stage3EngineScopeAuditTest" --console=plain
./gradlew :app:testDebugUnitTest --tests "com.filmkit.app.camera.Stage3EngineSlotPresetAuditTest" --console=plain
./gradlew :app:testDebugUnitTest --console=plain      # 全量：实测 647 tests / 0 failures / 0 skipped
python3 tools/check-filmparams-contract.py            # 既有守卫（PASS）
python3 tools/check-diag-fields.py                    # 既有守卫（全部通过）
```

`Stage3EngineSlotPresetAuditTest` 覆盖**预设 schema 的 `[98]` 向后兼容**（任务 §2 的判据）：

| 覆盖 | 内容 |
|---|---|
| 旧预设（无 `engine_slot` 键） | 套到默认底上 ⇒ `[98]` 落 0，**预设提到的槽照常生效、其余槽（含 `[98]`）逐位不变**；重新序列化**不得多出** `engine_slot` 键 |
| 旧预设 + 引擎 1 的照片 | 缺失 key ⇒ **保留目标的引擎选择**（不得把照片悄悄拖回 filmic） |
| `[98]` 往返 | `[98]=1` → 稀疏 → `"engine_slot":1.0` → 读回 → 套用 == 1 |
| 恒等值过滤 | 显式 `"engine_slot":0` 被当恒等丢弃（**不产生 skipped 诊断**、不入库） |
| 非法值现状 | `"engine_slot":7` 被当数据收下（不拒项）—— 锁住审计 §P2 的现状 |
| **两卡接缝** | 含 filmic 的旧预设套到 `[98]=1` 照片 ⇒ filmic 槽全部回到恒等、通用槽（颗粒）照常生效、其余逐位不变；**正对照**：同一条预设套到 `[98]=0` 照片必须真的改变参数 |

`Stage3EngineScopeAuditTest` 覆盖（**边界与完备性**；主用例在 `EngineScopeTest`）：

| 覆盖 | 内容 |
|---|---|
| 判据 1 | `engineSlot == 0` ⇒ **同一实例** + 逐位不变；且只有 0 是恒等档（负数/越界/`Int.MIN·MAX` 全走旁路） |
| 判据 2/3 | **对 `NEUTRALIZE` 的每一个槽**做成对构造（只在该槽取不同值）⇒ 结果逐字节相同（"死参数"性质，不是"值等于表里的值"） |
| 判据 4 | `0 until kCount` 中**表外全部槽位**逐位保留（防误旁路），含 `[98]` 自身 |
| 边界 | 空数组 / 长度 0…40 的每个截断 / 超过 `kCount` 的数组 / 恒等槽上的 `NaN`·`±Inf` / 表外槽的非有限值传播 / 幂等 + 确定性 + 不修改入参 |
| 单一真源 | B2 表逐条抄成断言，并**回到 `FilmParams.h` 读默认值**对拍（含 `[28]`/`[36]` 两条例外的原文钉住；Float 口径） |
| 完备性账本 | `FILMIC_OWNED_SLOTS` 的每个槽 ∈ `NEUTRALIZE` ∪ {`[40]`}；`[22]` 之外的 `uSat` 组现状 |
| 跨卡接缝 | 旁路后的数组仍被 `presetSparseSlots` 认作"恒等"（否则会把旁路值写进预设 ⇒ 切回引擎 0 污染） |
| **§P0 回归** | `p0ToneKIdentityMustNotDisableTheSigmoidEngine` —— **常绿**（架构师当日修复后已删 `@Ignore`）；断言 `NEUTRALIZE[28] == 1.0` 且 > 0。它防的是"六条判据全绿但引擎 1 空转"的复现 |
| **§P1 回归** | "uSat 的三分量必须整组在 NEUTRALIZE" —— 防有人只钉 `[20]`/`[21]` 留下高光档半旁路 |
| **§P2 回归** | `unregisteredEngineSlotIsTreatedAsSlotZeroAndNotBypassed`（+ 预设侧 `unregisteredEngineSlotFromPresetDataIsTreatedAsEngineZero`）—— 未注册值必须按 0 处理、不旁路 |
| **`[28]` 口径红利** | 例外集合**推导**出来（逐个与 `FilmParams.h` 默认值对拍）⇒ 修复后**只剩 `[36]`** 一条例外；`[28]` 已回归常规组 |

**本机无法执行、只在本清单的**：全部像素级判据（判据 0 的真机部分 / 1-a / 2 / 3 / 4 / 5 / 6），
以及设备端 GLSL 编译（判据 B 的不黑屏）。
