# 阶段 3 独立审计报告：**filmic 专属参数的全部消费路径**

> 审计人：验证代理（卡 V） · 日期：2026-10-14 · 依据 = **代码实读 + 项目自己的离线执行器实跑**（非 UI 标签推断）
>
> ## ✅ 三条发现**全部已修复**（架构师 2026-10-14 裁决并落地）
>
> | # | 发现 | 状态 | 修复 | 修复后判据 |
> |---|---|---|---|---|
> | **P0** | `NEUTRALIZE[28] = 0.0` ⇒ **引擎 1 空转** | ✅ 已修 | `put(28, 1.0)` | 引擎 1@`uToneK=1.0` = **`0x8fdb4e38`**（= 阶段 2 引擎 1 基线）；引擎 0 仍是 **`0x589f8364`**（零回归未受影响） |
> | **P1** | `[22] sat_high` 未入表 ⇒ 饱和度补偿**半旁路** | ✅ 已修 | `put(22, 1.0)`（`NEUTRALIZE` 13→**14 槽**） | `Stage3EngineScopeAuditTest` 的"uSat 三分量整组在表里"断言常绿 |
> | **P2** | 未注册 `[98]` 时 Kotlin 旁路 vs C++ 降级语义不一致 | ✅ 已修 | 新增 `KNOWN_ENGINE_SLOTS = {0,1}`，未注册 ⇒ **按 0 处理、不旁路** | `unregisteredEngineSlotIsTreatedAsSlotZeroAndNotBypassed` 常绿 |
>
> 连带：P1 修好后「饱和度」三槽**全部**失效 ⇒ 架构师**删掉了**临时加的 `UI_HIDDEN_PARAM_KEYS`
> 产品级例外，改由 UI 侧通用判据（"涉及的槽全部失效才隐藏"）自动隐藏。**本报告 §P1 的"同一组三槽拿到
> 互相矛盾的判据"那句批评正是这条的催因。** 卡 B 同时把 `slotActiveInEngine` 也做了 `effective` 收敛
> （未注册 ⇒ 不隐藏），与 P2 的语义对齐一致。
>
> 审计基线（本报告全部行号锚在这些版本上；文件被改动后行号需重锚）：
>
> | 文件 | 审计时的 sha256（前 16） |
> |---|---|
> | `core/engine/src/render/FrameRenderer.cpp` | `198cbf7c7e0c7263`（**未变** → 行号仍有效） |
> | `app/src/main/java/com/filmkit/app/camera/EngineScope.kt` | `355397df1bae62df` → **`7743b2dde9d0108c`**（三条修复） |
> | `app/src/main/java/com/filmkit/app/camera/EditorExport.kt` | `03ea7d1d448fdc29` |
> | `app/src/main/java/com/filmkit/app/camera/CameraController.kt` | `053f4826d2daea7a` |
> | `app/src/main/java/com/filmkit/app/ui/CameraPresets.kt` | `c081756ea926bc4b` |
>
> 目的：判据 2/3 的**完备性依据** —— 防"旁路了但漏了一条路"。§8.1 B 的架构师结论本报告**独立复核**
> （不照抄）；复核结果：**4 条假设被推翻**（§3）**+ 3 条漏径被找到**（P0/P1/P2，均已修）。

---

## 0. 一句话结论

> **引擎 1 生效时，改 filmic 专属槽位会不会改变成片？**
>
> **修复后：不会。** `NEUTRALIZE` 现为 **14 槽**（`28`=1.0 / `20`,`21`,`22`=1.0 / `36`=0.0 / `35`=0.0 /
> `5..10`=0.01 / `37`,`38`=pivot），三条**不受 uniform location 保护**的通路已全部盖住。
>
> **修复前会，而且是三条路**：`[28]` 的恒等值取错（0.0 让引擎 1 空转，§P0）、`[22] sat_high` 不在表里
> （饱和度补偿半旁路，§P1）、未注册 `[98]` 的降级语义两侧不一致（§P2）。

三条**不受 uniform location 保护**的通路（这是本审计的核心方法论；**加新引擎前必读**）：

| 通路 | 机制 | 代表槽位 | 是否已盖住 |
|---|---|---|---|
| ① 公共段 uniform | 声明在 `head` / 使用在 `mid`·`engine_main`·`tail` 三段 | `[28]` `[20]` `[21]` `[22]` `[36]` | ✅（`[28]` 靠 §P0 修复、`[22]` 靠 §P1 修复） |
| ② **成员变量副作用** | uniform 落 −1，但 `updateFilmicEV` 是**无条件调用**的，它写的成员变量逃出引擎边界 | `[35]` | ✅（2026-10-14 卡 A 抓出后已入表） |
| ③ 编辑器 pass 不随引擎换 | `kFsStyle` 的 `computeEditorCurve` 独立于 `[98]` | `[5..10]` `[37]` `[38]` | ✅ |

---

## P0（阻断级）✅ 已修：`NEUTRALIZE[28] = 0.0` 让**引擎 1 空转**

### 事实

`kFsSigmoidMain`（`FrameRenderer.cpp:6603`）—— 引擎 1 的 main 注入段**全文**：

```glsl
[[maybe_unused]] const char* kFsSigmoidMain = R"(    if (uToneK > 0.0) {
        vec3 toned = sigmoidTone(out3);
        out3 = mix(out3, toned, uToneK);
    })";
```

而 `sigmoidTone` 在全文件**只有这一个调用点**（`grep 'sigmoidTone\s*\(\s*out3\s*\)'` ⇒ 1 处）。

⇒ **`uToneK` 在引擎 1 里不是"基准影调强度"，而是 sigmoid 整条曲线的应用权重**：
`uToneK = 0` ⇒ `if` 恒假 ⇒ `sigmoidTone` **一次都不执行** ⇒ 引擎 1 = 纯直通（无任何影调曲线）。

`EngineScope.NEUTRALIZE` 把它归零的理由写的是"`mix(lin, toned, 0) = lin` ⇒ 恒等" —— 这条推理对
**引擎 0** 成立，但 `applyEngineScope` 对 `engineSlot == 0` **提前返回**（`:102`）⇒ `NEUTRALIZE` 的取值
**只在非 0 引擎下生效**。也就是说：把它设成 0.0，受影响的**只有引擎 1**，而引擎 1 恰恰需要它 > 0。

### 实跑证据（项目自己的离线执行器，`moderngl` 真执行引擎 shader）

```bash
cd /Users/wl/Downloads/胶片相机
S="--dng test_temp/FILMKIT_20261009-090803.dng --roi 1800,2100,600,1000"
python3 tools/engine-shader-offline.py $S --out /tmp/aud/e0tk1 --engine 0 --film-tone-k 1.0
python3 tools/engine-shader-offline.py $S --out /tmp/aud/e0tk0 --engine 0 --film-tone-k 0.0
python3 tools/engine-shader-offline.py $S --out /tmp/aud/e1tk1 --engine 1 --film-tone-k 1.0
python3 tools/engine-shader-offline.py $S --out /tmp/aud/e1tk0 --engine 1 --film-tone-k 0.0   # = 契约现状
```

| 臂 | `film_d5*.npy` crc32 | 说明 |
|---|---|---|
| 引擎 0 `uToneK=1.0`（**出厂**） | **`0x589f8364`** | = 阶段 2 基线 ✅ 口径对齐 |
| 引擎 0 `uToneK=0.0` | `0x189e4126` | |
| 引擎 1 `uToneK=1.0` | `0x8fdb4e38` | sigmoid 真的生效 |
| **引擎 1 `uToneK=0.0`** | **`0x189e4126`** | ⛔ 与"引擎 0 + 关影调"逐字节相同 ⇒ **这就是空转的指纹**（修复前 = 契约 `NEUTRALIZE[28]=0.0` 的后果；修复后仅作探针保留） |

差异矩阵（真 sRGB OETF → 8bit LSB）：

| 对比 | bitwise | max | mean | >1 LSB |
|---|---|---|---|---|
| 引擎 0(1.0) vs 引擎 1(1.0) | 不同 | 5.64 | 0.301 | 9.7% |
| 引擎 0(1.0) vs **引擎 1(0.0)** | 不同 | 10.22 | 0.903 | 32.9% |
| 引擎 0(0.0) vs **引擎 1(0.0)** | **完全相同** | 0.00 | 0 | 0% |
| 引擎 1(1.0) vs 引擎 1(0.0) | 不同 | 11.96 | 0.761 | 25.4% |

### 为什么这是最危险的一类失败

**它让判据 1–6 全部"通过"。** 判据 2/3 只要求"改 filmic 槽位成片不变" —— 引擎 1 空转时当然不变；
判据 5（A→B→A）也通过；判据 4（通用槽位仍生效）[0]/外观层走公共段，也通过。
**没有任何一条判据能发现"引擎 1 什么都没做"** —— 除非把"引擎 0 与引擎 1 的成片必须不同"单列成闸
（本报告 §5 建议新增**判据 0**，并给出设备侧判据）。

⚠ 更隐蔽的是：设备 A/B 会看到"引擎 1 与引擎 0 画面不同"（上表 32.9% 像素 >1 LSB）—— 但那是
**丢掉基准影调**造成的差异（发灰），不是 sigmoid 的差异。**"两引擎画面不同"不构成引擎 1 生效的证据。**

### 修法（一行，零风险）—— ✅ **已执行（2026-10-14，架构师）**

```kotlin
// EngineScope.kt NEUTRALIZE
put(28, 1.0)   // 原 put(28, 0.0)
```

- `1.0` 恰好是 `FilmParams.h:112` 的默认值 ⇒ B2 的"唯一真源 = 默认值"口径反而更顺。
- `applyEngineScope` 对 `engineSlot == 0` 提前返回 ⇒ 对引擎 0 **逐位零影响**（判据 1 不动摇）。
- 语义上它是"**强制引擎 1 的影调开着**"，正好满足判据 3 要求的"改 `[28]` 不影响成片"（用户改不到）。
- 回归护栏：`Stage3EngineScopeAuditTest.p0ToneKIdentityMustNotDisableTheSigmoidEngine`
  （`@Ignore` **已删除**，现为常绿断言；断言 `NEUTRALIZE[28] == 1.0` 且 > 0）。

#### 修复带来的一个**口径红利**：B2 表的例外从 2 条降到 1 条

修复前 `[28]` 是**唯一**一个"恒等值（0.0）≠ `FilmParams.h` 默认值（1.0）"的**公共段**条目 ——
即 B2 表名义上写"唯一真源 = 默认值"，实际有 2 条例外（`[28]`、`[36]`）。修复后：

| | 修复前 | **修复后** |
|---|---|---|
| 例外数 | 2（`[28]`→0.0、`[36]`→0.0） | **1（只剩 `[36]`→0.0）** |
| `[28]` | 例外，靠一条"mix 恒等"的**引擎 0 局部推理**支撑（错的那条） | **符合默认值**，无需例外 |
| `[36]` | 例外，依据 = `FrameRenderer.cpp:4096` 的"0 = 关（A/B 通路）" | 不变（依据明确） |

⇒ **"恒等值 = `FilmParams.h` 默认值"现在真的是一条无公共段例外的纪律**（唯一例外 `[36]` 有明确的物理依据）。
`Stage3EngineScopeAuditTest` 把这个例外集合**推导**出来（逐个与 `FilmParams.h` 对拍）而不是手抄，
所以下次有人再引入第 3 条例外时**一定是红的**。

**修复后的实测指纹**（架构师独立复跑，与我给出的三条一致）：

| 臂 | crc32 |
|---|---|
| 引擎 1 @`uToneK=1.0` | **`0x8fdb4e38`**（= 阶段 2 引擎 1 基线） |
| 引擎 1 @`uToneK=0.0`（空转） | `0x189e4126` |
| 引擎 0 @`uToneK=1.0` | **`0x589f8364`**（= 阶段 2 基线 ⇒ 零回归未受影响） |

> ⚠ **`0x189e4126` 这个指纹要留着**：它是"引擎 1 空转"的特征值 —— 引擎 0 与引擎 1 在同参数下
> 给出**同一个** crc32，只有"两个引擎都没执行影调段"才可能发生。判据 0 就是拿它当探针。

---

## P1（新漏径，本报告发现）✅ 已修：`[22] sat_high` —— 饱和度补偿只被旁路了 2/3

### 事实链

1. **三个槽一起写**：一个饱和度滑杆同写 `[20][21][22]`（`EditorExport.kt:424-433`，ADR §5.6④ 硬约束）。
2. **只有两个进了旁路表**（修复前）：`NEUTRALIZE` 有 `put(20, 1.0)` / `put(21, 1.0)`，**没有 `[22]`**。
3. **三个一起下发给两引擎共用的 shader 段**：`FrameRenderer.cpp:14138`
   `glUniform3f(u_film_.sat, p.sat_shadow, p.sat_mid, p.sat_high);` —— `uSat` 声明在 `head`（`:4067`）、
   使用在 **`tail`**（公共后段）。
4. **tail 里的合成式**（`:4503-4507`）：

```glsl
float l  = luma(out3);
float ws = exp(-l * 3.0);              // 阴影权重
float wh = smoothstep(0.5, 1.0, l);    // 高光权重（l ≥ 1.0 时 = 1）
float wm = max(0.0, 1.0 - ws - wh);    // 中间调权重
float sat = clamp(uSat.x * ws + uSat.y * wm + uSat.z * wh, 0.0, 2.0);
out3 = mix(vec3(l), out3, sat);
```

⇒ 引擎 1 下实际得到的是 `sat = 1·ws + 1·wm + sat_high·wh = (ws+wm+wh) + wh·(sat_high − 1)`：
**`sat_high ≠ 1` 时，`l > 0.5` 的像素（高光/亮部）仍被改变**。这是一次**半旁路**：阴影与中间调被
钉死、高光档仍在跟着用户的饱和度值走。

### 为什么当时没被抓住（⚠ 加新引擎/新参数组前必读的**同类失效模式**）

- `FILMIC_OWNED_SLOTS` 里没有 `[22]`，`NEUTRALIZE` 里没有 `[22]` ⇒
  `slotActiveInEngine(22, 1) == true` ⇒ **同一组的三个槽拿到两个互相矛盾的判据**
  （`[20]`/`[21]` = 隐藏，`[22]` = 照常显示/可调）。`EngineSlotUiTest` 的"设置页恰好五项"用例只钉了
  `[28]/[20]/[37]/[38]/[36]`（饱和补偿那一项挂在 `[20]` 上）⇒ **看不出来**。
- 判据 3 的槽位清单（§8.1 D）写的是 `[28]/[20]/[21]/[36]` —— **`[22]` 不在任何一条判据里**。
- `[22]` 是**进预设**的（`presetSlotIncluded(22) == true`）⇒ 一条含 `sat_high` 的预设、
  或"在引擎 0 下调过饱和度再切引擎 1"，都能让这条路径真的发生（不是理论路径）。
- **一般化教训**：当**一个 UI 控件同时写多个槽**（ADR §5.6④ 的"同写三槽"硬约束）时，
  旁路必须**按控件整组**判断，不能按"哪个槽看起来像 filmic"逐槽判断。本报告 §5 把它变成一条常绿断言。

### 修法（一行）—— ✅ **已执行（2026-10-14，架构师）**

```kotlin
put(22, 1.0)   // 与 [20]/[21] 同组同值：三槽都为基线 ⇒ sat == ws+wm+wh == 出厂口径
```

> ⚠ **B2 表口径（采纳本报告建议）**：`[20]/[21]/[22]` 的"乘性恒等"是**近似表述** ——
> 三槽全 `1.0` 时 `sat = ws + wm + wh`，在高光端 `ws + wh ≈ 1.0498 > 1` ⇒ `sat ≈ 1.0498`，
> **严格说不是"数学恒等"**。但那正是出厂默认（`CameraPresets.neutral()` 的 `put3(this,20,1,1,1)`）
> 的行为，也就是"与引擎 0 的默认完全一致"—— **旁路要的正是这个，不是数学恒等**。
> 判据必须是"与出厂默认逐位一致"，不能写成"shader 里的乘法恒等"。

**连带修复（P1 的"后果"而不只是"值"）**：三槽全部失效之后，架构师**删掉了**临时加的
`UI_HIDDEN_PARAM_KEYS`（"引擎 1 下显式隐藏「饱和度」"的产品级例外）—— 现在一个「饱和度」滑杆
同写的三槽**全部**在 `NEUTRALIZE` 里 ⇒ UI 侧"涉及的槽全部失效才隐藏"这条**通用**判据即可自动隐藏它。
**这正是本报告 §P1 那句"同一组三槽拿到互相矛盾的判据"的催因**；原则是"能自动推导的判据不要显式声明"。

**回归护栏**：`Stage3EngineScopeAuditTest` 里新增
"uSat 的三分量必须整组在 NEUTRALIZE"断言 —— 下次有人只钉两槽时它会红。

---

## P2（低）✅ 已修：`[98]` 落在未注册值时的"降级"两侧不一致

方案 §4 契约 5 说"未注册 ⇒ 降级到引擎 0 + 记 WARN"。但 **Kotlin 侧先按"非 0 即旁路"把
filmic 参数归零了**，C++ 侧再把 program 降级到引擎 0 ⇒ 得到的不是"引擎 0 的正常画面"，而是
"**引擎 0 + filmic 参数被清零**"（`[28]=0` ⇒ 影调段整段跳过；`[5..10]`/`[37]`/`[38]` 变成编辑器侧的
旁路值）。两侧对同一个未注册值给出不同语义。

- 触发面很小：`ENGINE_SLOT_CHOICES = listOf(0, 1)`（`ParamControls.kt:718`），UI 造不出 2；
  但**预设 JSON 是数据**（`engine_slot` 是已知语义名 ⇒ `presetsFromJson` 不会拒绝它的任意值），
  手改/未来版本写入 `"engine_slot": 2.0` 即可触发。

### 修法 —— ✅ **已执行（2026-10-14，架构师）**：采用方案 ①

```kotlin
/** 已注册的引擎槽位（与 core/engine/src/film/EngineRegistry.cpp 的 kEngines 表一致）。 */
val KNOWN_ENGINE_SLOTS: Set<Int> = setOf(0, 1)

fun applyEngineScope(slots: DoubleArray, engineSlot: Int): DoubleArray {
    val effective = if (engineSlot in KNOWN_ENGINE_SLOTS) engineSlot else 0   // ← P2 修复
    if (effective == 0) return slots
    ...
}
```

⇒ 未注册的 `[98]` 现在与 C++ 的 `warnEngineSlotUnavailable`（降级到引擎 0 + WARN）**同语义**：
两条链都当它是引擎 0，拿到的就是"引擎 0 的正常画面"，不再有第三种状态。

**回归护栏**（两条，分别在两个文件里）：
- `Stage3EngineScopeAuditTest.unregisteredEngineSlotIsTreatedAsSlotZeroAndNotBypassed`
  —— 未注册值（负数 / 越界 / 极值）必须**返回同一实例、逐位不变**；
- `Stage3EngineSlotPresetAuditTest.unregisteredEngineSlotFromPresetDataIsTreatedAsEngineZero`
  —— 从**预设 JSON** 来的 `"engine_slot":7.0` 是数据（不拒项），但旁路侧按 0 处理、`[28]` 不得被清零。

**UI 侧的对齐（卡 B 主动做，架构师确认）**：`slotActiveInEngine` 也做了同样的 `effective` 收敛
（未注册值按 0 ⇒ **不隐藏**）。理由与 P2 同源："渲染走引擎 0 全参数、UI 却把参数藏起来 = 假读数"。
⇒ 这条修复的价值不只是"少一种状态"，而是把"Kotlin / C++ / UI"三处的未注册语义**统一到同一个口径**。

---

## 1. 消费点全表（C++，`FrameRenderer.cpp`）

图例：**E1?** = 引擎 1 下**是否仍影响像素**。`判定 = 否` 的依据分三类：
`[loc−1]` = uniform 在引擎 1 program 里不存在（GL 安全 no-op）；`[离线]` = 离线实跑确认被跳过；
`[日志]` = 只进诊断字段。

### 拍照链（`passFilm`）

| 槽 | 字段 | 行号 | 具体落点 | 所属段 | **E1?** | 依据 |
|---|---|---|---|---|---|---|
| `[4]` | `contrast` | `:14016` | `updateFilmicEV(..., p.contrast, ...)` → `computeFilmicSpline` | 引擎 0 注入 | **否** | `:14111-14116` 的 `uFilmic*` 在引擎 1 落 −1（离线实跑：跳过 22 个，含全部 `uFilmic*`） |
| `[5..7]` | `shoulder[3]` | `:14016` | 同上 → `filmic_.m1..m5 / lat_*` | 引擎 0 注入 | **否** | 同上 |
| `[8..10]` | `toe[3]` | `:14016` | 同上 | 引擎 0 注入 | **否** | 同上 |
| `[28]` | `tone_strength` | `:14141` | `u_film_.tone_k` → `head:4070` 声明 | **公共**（`engine_main` 使用，两引擎各有一份同形块） | ⚠ **是** | `kFsSigmoidMain:6603` 的 `if (uToneK > 0.0)` 就是 sigmoid 的应用门控 ⇒ 见 **§P0** |
| `[36]` | `local_tone` | `:14066` → `:14150` | `local = (p.local_tone > 0 ? … : 0)` → `uLocalStrength`（`head:4096`，用于 `mid`） | **公共** | **是**（已入表） | `mid` 段不随引擎换 |
| `[35]` | `auto_tone` | `:14014` | `updateFilmicEV(src, still, p.auto_tone > 0.5f, …)` | 引擎 0 注入**入参**，但函数**无条件调用** | ⚠ **是（副作用）** | 见下面 §2 |
| `[37]` | `filmic_black_ev` | `:14014` | `updateFilmicEV` 的 `user_black` → `ev_black_` → `filmic_` | 引擎 0 注入 | **否** `[loc−1]` | `uFilmicBlackEv` 落 −1 |
| `[38]` | `filmic_white_ev` | `:14014` | 同上 → `ev_white_` → `filmic_` | 引擎 0 注入 | **否** `[loc−1]` | 同上 |
| `[38]` | （同上，第二落点） | `:14030-14032` | `white_ev_in = ev_white_ − p.exposure_ev` → `hr_white_ev_` | 成片 **HR**（`still && p.hr_wavelet>0.5`） | **否** | `runHighlightReconstruct:12439` 是 `(void)white_ev_input_domain;` ⇒ 该参数自 v0.10.145 起**只写日志**（`hrWhiteEv`，`:11371`）；threshold 改由 `kSaturationNormCalib × \|clip(CCM·(0,gain_G,0))\|` 决定 **⇒ 与白点无关** |
| `[40]` | `gamut` | `:14130` | `u_film_.gamut` → GLSL `:4499` `if (uGamut > 0.5) gamutMapV5(...)` | **引擎 0 注入②（`engine_main`）** | **否** `[loc−1]` | `uGamut` 声明在 `engine_def:4175`、唯一使用在 `engine_main:4499` ⇒ 两段都被引擎 1 的注入段整体替换（离线实跑：`uGamut` 在被跳过的 22 个里） |
| `[42]` | `white_hl` | `:14015` | `updateFilmicEV` 的 `white_hl`（仅 `auto_on` 分支） | 引擎 0 注入 | **否** | 它只经 `w` → `ev_white_` → ①`filmic_`（`[loc−1]`）②`hrWhiteEv`（日志）⇒ **无像素路径** |
| `[43]` | `white_floor` | `:14015` | 同上（`w_floor`） | 引擎 0 注入 | **否** | 同上 |
| `[44]` | `white_probe` | `:14015` | 同上（`probe`） | 引擎 0 注入 | **否** | 同上 |
| `[20]` | `sat_shadow` | `:14138` | `glUniform3f(u_film_.sat, …)` | **公共 `tail`** | **是**（已入表） | `tail` 不随引擎换 |
| `[21]` | `sat_mid` | `:14138` | 同上 | **公共 `tail`** | **是**（已入表） | 同上 |
| **`[22]`** | **`sat_high`** | **`:14138`** | **同上** | **公共 `tail`** | ✅ **是（已入表，§P1 修复）** | 见 **§P1**：修复前三槽只钉两个 ⇒ 高光档半旁路 |

### 编辑器链（`passStyle` / `submitDisplay`）

| 槽 | 字段 | 行号 | 落点 | **E1?** | 依据 |
|---|---|---|---|---|---|
| `[4]` | `contrast` | `:14680-14681` | `u_style_.contrast` / `u_style_.contrast_on` | **是（刻意保留）** | §8.1 B 的"保留"行；`passStyle` 独立于 `[98]` |
| `[5..10]` | `shoulder`/`toe` | `:14703` | `computeEditorCurve(p.shoulder, p.toe, …)`① | **是**（已入表） | `passStyle` 不随引擎换 |
| `[37]` | `filmic_black_ev` | `:14703` | 同上的 ③④ 入参 | **是**（已入表） | 同上 |
| `[38]` | `filmic_white_ev` | `:14703-14704` | 同上 | **是**（已入表） | 同上 |
| `[20]` | `sat_shadow` | `:14691` | `u_style_.sat_shadow` | **是**（已入表） | 2026-10-14 卡 A 复核纠正了 §8.1 B 原写的"不消费"；**本审计独立复核一致**（`tools/check-style-passthrough.py` 第④项的白名单含 `sat_shadow`/`sat_mid`/`sat_high`） |
| `[21]` | `sat_mid` | `:14692` | `u_style_.sat_mid` | **是**（已入表） | 同上 |
| `[22]` | `sat_high` | `:14695` | `u_style_.sat_off` 的门控条件（`p.sat_shadow != 1 \|\| p.sat_mid != 1 \|\| p.sat_high != 1`） | ⚠ **是**（门控项） | 三个 `[20]/[21]` 归零后此门控依赖 `[22]` ⇒ 与 §P1 同源 |
| `[28]` | `tone_strength` | —— | **零引用** | **否** | `passStyle` 函数体里无 `p.tone_strength` |
| `[35]` | `auto_tone` | —— | **零引用** | **否** | 同上 |
| `[36]` | `local_tone` | —— | **零引用** | **否** | 同上 |
| `[40]` | `gamut` | —— | **零引用** | **否** | 同上 |

> ① 行号 `:14703` 的函数名：`computeEditorCurve`（定义在 `:13707`），在 `passStyle` 体内被调用。

### 输出链（`kFsOut`）

`[4]/[5..10]/[20..22]/[28]/[35][36]/[37][38]/[40]` 在 `kFsOut` 里**零引用** ——
本审计对 `kFsOut` 段做了 `p.<字段>` 扫描，命中为空（与 `CameraStyleWhitelistTest` 的
`CAMERA_PATH_SLOTS` 由 `passFilm`/`passOutput` 共同构成的结论一致）。

### 诊断 / 日志段（**不影响像素，但会误导验收人**）

| 槽 | 行号 | 日志事件 | 字段 |
|---|---|---|---|
| `[28]` | `:11276` / `:6375` | `engine.raw.render` / `engine.diag.stats` | `tone` |
| `[35]` | `:11350` | `engine.raw.render` | `auto` |
| `[36]` | `:11351` | `engine.raw.render` | `lt` |
| `[40]` | `:11353` / `:6381` | `engine.raw.render` / `engine.diag.stats` | `gm` |
| `[4]` | `:11290` / `:8755` | `engine.raw.render` / `engine.render.style_diag` | `ct` |
| `[20][21][22]` | `:6375-6376` / `:8756` | `engine.diag.stats` / `engine.render.style_diag` | `sat` |
| `[5..10]` | `:11345-11346`（`latToe`/`latSh`）、`:11347-11348`（`fPow`/`fCon`） | `engine.raw.render` | 来自 `filmic_`（**引擎 1 下是死值**） |
| `[38]` | `:11371` | `engine.raw.render` | `hrWhiteEv`（来自 `hr_white_ev_`） |

> ⚠⚠ **给验收人的警告**：引擎 1 下 `raw.render` 里的 `latSh`/`latToe`/`fPow`/`fCon`/`blackEv`/`whiteEv`
> **仍然有值且照常变化** —— 因为 `updateFilmicEV` 在 `passFilm` 里是**无条件调用**的
> （`:14005-14016` 的注释原文："刻意放在 passFilm 内 —— 预览 / YUV 静拍 / RAW 静拍三条路径都经过这里"）。
> 它在引擎 1 下算完的系数被 `glUniform*(−1, …)` 静默丢弃，**但 `filmic_` 成员里的值是真的**。
> ⇒ **"日志里 filmic 参数在变"不能推断"filmic 在影响画面"**；反过来，"引擎 1 下日志仍在报 filmic"
> 也不是缺陷（只是每帧浪费一次直方图 + 6 次 5×5 高斯消元）。

---

## 2. `[35] auto_tone` 的副作用链（独立复核，结论与卡 A 一致）

**为什么它不能靠 uniform location 保护**：`updateFilmicEV`（`:13811`）在 `passFilm` 里被**无条件调用**，
它写的是**成员变量**，成员变量不受 program 切换保护。

`auto_on` 门控下"逃出引擎边界"的写入有三处，本审计逐一定位了它们的**下游读者**：

| 成员 | 写入 | 门控 | 下游读者 | 是否到像素 |
|---|---|---|---|---|
| `last_still_w999_`（`:13865`） | `= w999` | `still && auto_on` | `FrameRenderer.h:256 stillW999()` → `Bridge.cpp:913` `"sw999"` → `CameraController.kt:233` `sceneHighlightEv()` → `ExposurePlanner.plan(sceneHighlightEv=…)`（`CameraController.kt:2011`，受设置页"高光保护"开关 `hlOn` 门控） | ✅ **是**（⇒ 欠曝量 ⇒ 成片 `[0]`） |
| `last_w999_`（`:13963`） | `= w999` | 恒写（但 `w999` 仅 `auto_on` 时非 0） | `sceneW999()` → `Bridge.cpp:911` `"w999"` | ❌ Kotlin 侧**只读 `sw999`**，`w999` 无人读（只进日志） |
| `last_wmax_`（`:13965`） | `= log2(p_max)` | `st.valid && auto_on` | `sceneWMax()` → `Bridge.cpp:914` `"wmax"` | ❌ 无 Kotlin 读者 |

⇒ **`[35]` 的像素级漏径只有一条**（`sw999` → ExposurePlanner），**与卡 A 的结论一致**；
`w999`/`wmax` 是死字段（只有日志），把它们也算成漏径会**高估**面。
⇒ 因为只剩一条、且它在"高光保护"开关之下（默认关），`[35]` 的严重度低于 `[28]` 与 `[22]`：
`[35]` 只在"auto_tone 开 且 高光保护开"时才改变成片。**但归零 `[35]` 仍是正确的**（它就是引擎旋钮）。

---

## 3. 已被本审计**推翻**的假设（避免后人重走）

| 假设 | 结论 | 依据 |
|---|---|---|
| "`[37]/[38]` 在**编辑器和 HR 两处**都仍生效 ⇒ 必须旁路" | ⛔ **HR 那一半不成立** | `runHighlightReconstruct:12439` `(void)white_ev_input_domain;` + v0.10.145 起 threshold 只依赖 CCM 第二列（`:12386-12400` 注释逐行实读）。`[37]/[38]` 在引擎 1 下**只剩编辑器链路**一条漏径（+ `hrWhiteEv` 日志字段） |
| "预览域比 `g_domain_ratio` 会被 filmic 参数污染" | ⛔ **不成立** | `g_domain_ratio` 的唯一输入是 `last_lin_mid_`（`:14049-14052`），而 `last_lin_mid_ = st.p_mid`（`:13457`）= **图像直方图中位**，与任何 `FilmParams` 字段无关（`measureSceneStats` 只吃 `src` 像素）。⇒ `[37]/[38]/[5..10]` 不会经域比泄漏到预览 |
| "`uGamut` 可能被公共段消费" | ⛔ **不成立** | `uGamut` 只在 `:4175`（`engine_def`）声明、只在 `:4499`（`engine_main` 内）使用；两段都被引擎 1 替换。**离线实跑确认 `uGamut` 在"被跳过的 22 个 uniform"里** |
| "程序切换后 `u_film_*` 是旧 program 的 location ⇒ 会写错 uniform（串扰）" | ⛔ **不成立** | `buildFilmProgram:6900` 在换 program 之后**重查全部 location**（`queryFilmUniforms()`，注释原文"location 是 per-program 的 ⇒ 必须重查"）。这是判据 5（无串扰）的**机制级依据**，本审计实读确认 |

---

## 4. 调用链归属（回答"预览是否也走 `updateFilmicEV`"）

> ⚠ **先把两个"调用点"分清**（2026-10-14 架构师表述修正；写错会让下面那条发现失去价值）：
>
> | 函数 | 调用点 | 说明 |
> |---|---|---|
> | **`updateFilmicEV`** | **全文件只有 1 个：`:14014`** | 在 `passFilm` 体内、**无条件**调用（`:13990` 的 `if (engine_slot != film_engine_slot_)` 只管要不要**重建 program**，与它无关） |
> | **`passFilm`** | **4 个**：见下表 | 预览 / YUV 静拍 / RAW 静拍 |
>
> ⇒ 正确表述是：**`updateFilmicEV` 的唯一调用点是 `passFilm:14014`（无条件）；而 `passFilm` 有 4 个
> 调用点 ⇒ 四条链都会跑到它。**
>
> **为什么这个区分是本质的**：它**只有一个、且无条件的**调用点 —— 所以**躲不开**。这正是 P0/`[35]`
> 的机制：函数体无条件执行 ⇒ 它写的**成员变量**（`last_still_w999_` 等）逃出引擎边界，
> 不受"uniform location = −1"的保护。若按字面读成"`updateFilmicEV` 在 4 处被调用"，
> 就会误以为"可以在某一处加条件把它跳过"，从而丢掉这条发现。

**答：是 —— 预览也会走到它，且与引擎无关。** `passFilm` 的 4 个调用点：

| 行号（`passFilm` 的调用点） | `still` | `hal_domain` | 链路 |
|---|---|---|---|
| `:8286` | `false` | `true` | 预览（主） |
| `:8307` | `false` | `true` | 预览（另一分支） |
| `:8486` | `true` | `true` | YUV 静拍 |
| `:11091` | `true` | `false` | RAW 静拍（成片） |

`updateFilmicEV` 在 `passFilm` 体内、**在引擎选路（`:13989-14004`）之后但不在任何分支里**
⇒ 引擎 1 下它照常：
1. `measureSceneStats(src)` 跑一遍直方图（写 `last_lin_mid_` —— **参数无关**，见 §3）；
2. 算 `ev_black_`/`ev_white_`（吃 `[37]/[38]/[35]/[42..44]/[0]`）；
3. `computeFilmicSpline(...)` 解 6 组 5×5 线性系统（吃 `[4]/[5..10]`）；
4. 把结果 `glUniform*` 到 `uFilmic*` ⇒ **引擎 1 下 location = −1，静默丢弃**。

⇒ **对引擎 1 的画面影响 = 0**（除 `[35]` 的 `sw999` 副作用，已入表；`[22]` 那条不在本函数内，见 §P1）；
剩下的两个可见后果是**成本**（每帧白算）与**日志**（`raw.render` 的 filmic 字段仍有值，见 §1 末尾的陷阱）。

---

## 5. 正式判据表（本审计的输出；**建议与 §8.1 D 合并**）

> 下表是 §8.1 D 六条判据的**修订版**：新增**判据 0**（正是它发现了 P0），判据 3 的槽位清单补
> `[22]` 与 `[35]`。逐条的执行办法、期望值与失败判据在
> [stage3-acceptance-CHECKLIST.md](stage3-acceptance-CHECKLIST.md)（真机）与本报告 §附（本机）里。

| # | 判据 | 变更 | 为什么 |
|---|---|---|---|
| **0** | **`engine_slot=1` 的成片必须与 `engine_slot=0` 不同，且差异与 sigmoid 生效一致** | ★ **新增（前置闸）** | §P0 证明原六条判据**全部无法发现"引擎 1 空转"**：判据 2/3 只要求"改 filmic 槽位不变"，空转当然满足。**没有这条闸，一个什么都没做的引擎能拿六条全绿。** |
| 1 | `engine_slot=0`：阶段 2 全部基线不变 | 不变 | 零回归的硬门槛 |
| 2 | `engine_slot=1` + 改 `[37]`/`[38]`/`[5..10]` ⇒ 成片逐字节不变 | 不变 | 覆盖编辑器曲线段 + `uFilmic*` |
| 3 | `engine_slot=1` + 改 `[28]`/`[20]`**`/`[21]`/`[22]`/`[35]`**`/`[36]` ⇒ 成片逐字节不变 | **补 `[22]`、`[35]`** | `[22]` = §P1 的半旁路（`uSat` 第三分量）；`[35]` = 卡 A 的副作用漏径（`sw999` → 曝光） |
| 4 | `engine_slot=1` + 改 `[0]`/`[19]`/外观层 ⇒ **正常生效**（防误旁路） | 不变 | 防"把通用槽也旁路掉" |
| 5 | 互切 A→B→A ⇒ 成片回到 A 的原值（无串扰） | 不变（**口径**见清单：必须用成片、不能用预览帧） | 预览链有 EMA 历史状态 |
| 6 | 编辑器：编辑 `engine_slot=1` 的照片，改 `[37]`/`[5..10]` ⇒ 导出结果不变 | 不变 | `kFsStyle` 不随引擎换 = **独立落点**，不能靠拍照链外推 |

**判据 0 的可执行形式**：

- **本机**：`tools/engine-shader-offline.py --engine {0,1} --film-tone-k 1.0` 两次的 `film_d5*.npy`
  crc32 必须**不同** —— 修复后期望 **`0x589f8364`（引擎 0）vs `0x8fdb4e38`（引擎 1）**。
  同时留一条**反证探针**：`--film-tone-k 0` 时两者必须**相同**（`0x189e4126`）——
  这个"两引擎给出同一指纹"只可能来自"两个引擎都没执行影调段"，它就是空转的特征值。
- **真机**：见清单的判据 0（含"**不要用『两包画面不同』当证据**"的警告 —— P0 下两包也会不同，
  但那是"丢掉基准影调"的差异；唯一可靠证据是"差异与 sigmoid 特征一致"或上面的 crc32 对照）。

### 另一条建议（未被采纳与否都请看一眼）

`[42]/[43]/[44]`（白点高光感知）**不需要**入 `NEUTRALIZE`：它们在引擎 1 下**没有像素路径**
（§1 表），只有 `hrWhiteEv` 日志字段会变。但**若将来 `[35]` 被从表里拿掉**，它们会立刻经
`w → filmic_` 复活 ⇒ 属"依赖 `[35]` 归零"的**间接保护**。建议在 §8.1 B 表里注明这层依赖，
否则后人读到"`[42..44]` 不旁路"会以为它们天然安全。

---

## 6. 覆盖边界（诚实标注：这些**没有**被本审计证明）

| 维度 | 状态 |
|---|---|
| 真机 / 设备端 GLSL 编译与像素 | ❌ **未验证**（本机无设备：`adb devices` 为空）。全部像素级结论来自 `moderngl` 离线执行（真跑引擎自己的 GLSL 字符串，但**只跑 FILM 段**） |
| 离线链覆盖 | ⚠ FILM 段**单段**。`hr_raw`/`uLocal*`/PC3D 标定/`kFsOut`/MFNR **不在链内**（方案 §10.6 已声明）⇒ 本报告的"成片"结论严格说只到 `kFsFilm` 的输出 |
| 预览链 | ⚠ 只做了**代码路径**判定（`updateFilmicEV` 的唯一调用点 `:14014` 在 `passFilm` 内无条件 + `passFilm` 的 4 个调用点覆盖预览 + `g_domain_ratio` 参数无关）⇒ 未跑预览域实测 |
| Kotlin 侧组装顺序 | ⚠ 只读了 `engineScoped` 的调用点与 KDoc（`EditorExport.kt:103-113` 要求"必须在所有 `applyXxxPref()` 之后"）；**顺序是否在所有调用点都满足**未逐点核（由卡 A/架构师的用例守） |
| `[22]` 的实际视觉幅度 | ⚠ **未量化**（需要一条 `sat_high≠1` 的离线跑；离线链默认 `--film-sat 1,1,1`）。**"存在漏径"已证、"漏多少"未测** —— 修复后它已不可能生效，故不再回填量化 |
| 引擎 2（ACES）/ per-engine 表 | ❌ 未涉及。`NEUTRALIZE` 仍是**单表**：P2 修复后语义变成"**已注册**的非 0 引擎共用同一组恒等值"（未注册 ⇒ 按 0、不旁路），**仍不是 per-engine 表** ⇒ "引擎 2 也吃 `[28]`"这类情形在加第 3 个引擎时仍须升级成 `Map<Int, Map<Int, Double>>` |

---

## 附：复跑命令（本审计的全部可执行证据）

```bash
cd /Users/wl/Downloads/胶片相机
export JAVA_HOME=/opt/homebrew/opt/openjdk@23

# ① 判据 1–4 的纯函数性质 + 边界 + 完备性（17 项，全绿）
./gradlew :app:testDebugUnitTest --tests "com.filmkit.app.camera.Stage3EngineScopeAuditTest" --console=plain

# ①b 预设 schema 的 `[98]` 向后兼容（8 项）
./gradlew :app:testDebugUnitTest --tests "com.filmkit.app.camera.Stage3EngineSlotPresetAuditTest" --console=plain

# ② 全量单测（实测 **647 tests / 0 failures / 0 errors / 0 skipped**）
#    本卡两个文件 = 17 + 8 = 25 项，全绿
./gradlew :app:testDebugUnitTest --console=plain

# ③ 两条既有守卫
python3 tools/check-filmparams-contract.py
python3 tools/check-diag-fields.py

# ④ §P0 的实跑证据（四条臂 → 矩阵见 §P0）
S="--dng test_temp/FILMKIT_20261009-090803.dng --roi 1800,2100,600,1000"
for tk in 0.0 1.0; do
  python3 tools/engine-shader-offline.py $S --out /tmp/aud/e0tk$tk --engine 0 --film-tone-k $tk
  python3 tools/engine-shader-offline.py $S --out /tmp/aud/e1tk$tk --engine 1 --film-tone-k $tk
done
# 期望：e0tk0 与 e1tk0 的 film_d5*.npy **crc32 相同（0x189e4126）= 引擎 1 空转的指纹**
```
