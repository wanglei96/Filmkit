# U-6a 预设详情 + 编辑/更新 —— 实现报告

> 卡号 U-6a ｜ 用户需求（2026-10-11）："**预设应该展示全部可调参数，用户可编辑和新增预设。**"
> 交付物：**预设只读详情** + 「**更新为当前设置**」｜数据层 schema / 引擎 / 拍照界面 / 查看页**零改动**
> 状态：代码完成、构建 + 单测真跑通过 ｜ ⚠ **未真机验证**（见 §8）

---

## 0. 一句话

预设面板里点**预设名称区**进**详情**：显示这条预设的元数据 + **它真正包含的**参数（中文名 +
物理量读数 + 单位，按 `preset-DESIGN.md` §9.2 的四组排）+ LUT 与强度；user 预设的详情底部多一个
「**更新为当前设置**」= 用当前编辑器参数重写这条预设（复用既有稀疏化与落盘链路）。

---

## 1. 改了什么（3 个文件，全部在工作区，未 commit）

| 文件 | 性质 | 内容 |
|---|---|---|
| `app/src/main/java/com/filmkit/app/ui/PresetDetail.kt` | **新建** | 纯逻辑（无 Compose / 无 Android）：`presetDetailSections(slots)`、`presetLutLine(p)`、`presetSourceText(source)`、`presetCreatedAtText(ms)`；**槽位 → 分组 / 中文名 / 格式化**表 `DETAIL_META` = **唯一维护点** |
| `app/src/main/java/com/filmkit/app/ui/EditorScreen.kt` | 改 | ① `updatePreset(p)`（新函数）；② `PresetPanel`：加 `onUpdate` 参数 + `detailId` 状态 + 详情分支；③ `PresetRow`：加 `onOpenDetail`、名称区可点、名称右侧「›」；④ 新 `PresetDetailPanel` composable |
| `app/src/test/java/com/filmkit/app/ui/PresetDetailTest.kt` | **新建** | 11 个 JVM 用例（稀疏 / 文案 / 单位 / 未知槽位 / [34] / 与面板文案对拍 / 与 P-5 用词对拍 / 元数据） |

**没碰**：`PhotoViewScreen.kt`、`CameraScreen.kt`、`PresetScope.kt`、`EditorExport.kt`、`BuiltinPresets.kt`、
引擎（`FrameRenderer.*`）、数据层 schema、拍照界面、查看页。

### 1.1 为什么纯逻辑单独一个文件

详情条目的组装（`Map<Int, Double>` → 人话）是**有分支、有表、有格式化**的逻辑，放进 4200 行的
`EditorScreen.kt` 就只能靠真机看。抽出来后 JVM 单测直接驱动 —— 与 E2-10 把读数格式化抽进
`EditorExport.kt`（`formatEv` / `formatKelvin`…）是同一条理由。

---

## 2. 详情长什么样（验收判据 1：真实预设的实际输出）

输入 = `app/src/main/assets/presets/builtin.json` 的**第一条**（"徕卡一瞬"），
`params = {grain_amount 20, grain_size 160, vignette_strength −0.18, vignette_radius 85,
vignette_feather 55, split_warm 0.1, split_cool 0.05}` + `lut = {leica_m9, 1.0}`。
以下文本由单测 `builtinLeicaMomentRendersHumanReadableRows` **逐字断言**：

```
‹ 返回   徕卡一瞬
内置 · 徕卡 · —

色彩
  分离色调 · 暖          +0.10
  分离色调 · 冷          +0.05
胶片质感
  颗粒 · 强度            20
  颗粒 · 尺寸            ISO 160
  暗角 · 强度            18
  暗角 · 半径            85
  暗角 · 羽化            55
LUT                     Leica M9 · 强度 100

[套用]  [内置预设 · 只读]
```

- **只列它真正包含的 7 项**（稀疏语义）—— 不是把 96 槽铺一遍；
- `grain_size 160` → `ISO 160`（**物理量**，E2-10 口径）；`vignette_strength −0.18` → `18`
  （槽位是负值 = 变暗，走 `vigStrengthUi` 才是滑杆读数）；
- 创建时间 `—`：内置三条的 JSON **没有 `createdAt`**（随包分发，"创建时刻"无意义），
  显示 1970 比显示 `—` 更容易误导；
- LUT 行显示的是 `LutLibrary` 的中文名（查不到时回落 id = 资产被删的降级提示）。

另外两条内置预设（"理光负片" / "徕卡黑白"）由单测
`allBuiltinPresetsHaveChineseNamesAndUnits` 保证：**行数 = 槽位数**（一项都不许丢）且**名字都是中文**
（露出英文语义名即测试红）。

---

## 3. 文案来源逐条（**不新造译名**）

| 槽位 | 详情里的名字 | 读数示例 | 来源 |
|---|---|---|---|
| `[0]` | 曝光 | `+1.5 EV` | `EditorParam` 表 label（面板「调整」组）；读数复用 `formatEv` |
| `[1..3]` | 红 / 绿 / 蓝 | `+12` | `EditorParam` label（字母图标 R/G/B） |
| `[4]` | 对比度 | `+12` | `EditorParam` label |
| `[5..7]` | 胶片曲线 · 肩部 红/绿/蓝 | `0.01` | "胶片曲线" = `PresetScope.SLOT_LABELS`（P-5 的用户可懂术语）；"肩部" = `preset-DESIGN.md` §1 第 2 层 |
| `[8..10]` | 胶片曲线 · 趾部 红/绿/蓝 | `0.01` | 同上 |
| `[20..22]` | 饱和度 · 暗部 / 中间调 / 高光 | `+20` | "饱和度" = `EditorParam` label；三档 = §1 第 2 层"分区饱和（暗 / 中 / 高）" |
| `[23][24]` | 分离色调 · 暖 / 冷 | `+0.10` | `preset-DESIGN.md` §9.2 第 13 项"分离色调"（P-5 报告同词）。⚠ ★ **U-1 已把 `[23..25]` 接到编辑器链路** ⇒ 两侧都生效 ⇒ `PresetScope` 不再为它们出提示（名字沿用 P-5 已确立的术语，不是新造） |
| `[25]` | 黑白 | `开` / `关`（⚠ **U-6b 起改为与面板同口径**：0 → `关`、否则 0..100 整数 —— 面板补上连续滑杆后 `>= 0.5 才算开` 会把 0.35 读成"关"） | §9.2 第 14 项"单色（黑白）"（同上；U-1 后无作用域提示） |
| `[26]` | 颗粒 · 强度 | `20` | §1 第 3 层"颗粒 强度" |
| `[27]` | 颗粒 · 尺寸 | `ISO 160` | §1 第 3 层"颗粒 尺寸" |
| `[37][38]` | 黑点 / 白点 | `-7.6 EV` | `PresetScope`（= `SettingsScreen`「黑点（多暗算黑）」） |
| `[60]` | 颗粒 · 中间调偏置 | `50` | §1 第 3 层 |
| `[61]` | 颗粒 · 枢轴 | `0.50` | §1 第 3 层"枢轴（峰值移向暗部）" |
| `[62..65]` | 光晕 · 强度 / 触发阈值 / 半径 / 暖度 | `50` | §1 第 3 层 |
| `[66..68]` | 暗角 · 强度 / 半径 / 羽化 | `18` / `85` / `55` | `EditorParam` 表「暗角」组 label（强度/半径/羽化）+ 分组前缀消歧 |
| `[69..77]` | 层间 · 暗部 / 中间调 / 高光 红/绿/蓝 | `+12` | §1 第 2 层"暗部 / 中间调 / 高光 R/G/B 增益" |
| `[78]` | 清晰度 | `35` | `EditorParam` label |
| `[88]` | 色温 | `5200K` | `EditorParam` label；读数复用 `formatKelvin` |
| `[89]` | 色调 | `+12` | `EditorParam` label |
| `[90..93]` | 高光 / 阴影 / 白色色阶 / 黑色色阶 | `+50` | `EditorParam` label |
| `[94]` | 褪色 | `35` | `EditorParam` label |
| 其它（`[11..19]` cross / `[95]` / 未来槽 / 坏数据） | **`FilmParamKeys` 语义名** → `槽N` | `0.50` | 回落链，**绝不静默消失** |

**"与面板文案一致"不是靠人眼核对的**：单测 `detailLabelsReuseEditorPanelWording` 用正则解析
`EditorScreen.kt` 的 `EditorParam` 表（`label = "…"` + `read = { … it[N] }`），逐槽断言
**详情名包含面板 label**（≥10 槽真的比到，否则测试自己红）。
同理 `detailLabelsMatchScopeNoticeWording` 拿 `presetScopeNotice` 的输出做**反向一致性**：
遍历全部 96 槽，**由作用域提示自己决定**哪些槽还有"跨链路差异"、提示里用的是什么词，再要求
详情用同一个词 ⇒ **提示里不可能出现一个"没有名字"的参数**。清单**不硬编码**（U-1 把
`[23..25]` 接线后 P-5 就不再提示它们 —— 写死清单的写法会在每次接线时假红，把"接线完成"
报成"文案漂移"）。另加一条反向断言：两侧都生效的 `[23..25]` **不得**出现在任何作用域提示里。

### 3.1 分组（§9.2 的四组 + 兜底）

`基础调整`（0..4 / 78 / 88..93）、`色彩`（20..25 / 94）、`影调曲线`（5..10 / 37 / 38）、
`胶片质感`（26 / 27 / 60..77）、`其它`（未映射槽）。**只输出非空组**；组内按槽位升序。

---

## 4. 「更新为当前设置」（验收判据 2）

```kotlin
fun updatePreset(p: EditorPreset) {
    if (!presetDeletable(p)) return                  // 内置只读 —— 与删除**同一判据**
    val film = params ?: return
    val next = p.copy(
        slots = presetSparseSlots(film),             // ★ 复用保存那一套稀疏化
        lutId = lutId,
        lutStrength = if (lutId != null) film[34] else 1.0,
    )
    presets = mergePresets(PresetStore.upsert(prefs, next), BuiltinPresets.load(context))
    status = "已更新预设：${next.name}（${next.slots.size} 项参数）"
    …
}
```

- **只对 `source == "user"`**：判据复用 `presetDeletable`；内置预设的详情里**这个按钮根本不渲染**
  （右侧是一枚不可点的「内置预设 · 只读」）—— 不做"点了才报错"（P-2c 同款纪律）。
- **列表立即正确**：`mergePresets(PresetStore.upsert(...), BuiltinPresets.load(context))` ——
  与 P-2c 的保存/删除逐字同款，**内置三条不会消失**（P-2c 踩过的坑，注释里点명）。
- **详情不会显示过期数据**：面板存的是 `detailId`（**id 不是对象**），`presets` 刷新后详情
  自动渲染新内容；预设被删（不在列表里）⇒ 自动回列表。"更新完详情还是旧值"这类 bug 在结构上不存在。
- **不进编辑历史、不渲染**：它改的是**预设库**，当前参数与画面一位没动 ⇒ 记一步撤销会让用户
  "撤销一次画面不动"（E2-25 的反面）。
- **id / 名称 / 分组 / 创建时间 / source 全不动**：更新的是"配方内容"，不是身份。

---

## 5. 新增预设入口（验收判据 3）

**在预设面板的列表页顶部**（底栏「预设」→ 面板）：`预设名称` + `分组（可空）` 两个输入框 +
右边「**保存当前**」按钮（E3-1a 已做，本卡**未改一行**）。可达性说明：

- 打开面板即可见（不需要滚到底、不需要展开二级页）；
- 详情打开时它**不在屏幕上**（详情取代列表）—— 顶部「‹ 返回」回列表即见；
- 按钮在 `canSave && name.isNotBlank()` 之前是禁用态（不注册手势，E2-10 的既有行为）。

---

## 6. 明确**不做**（本卡边界）

| 不做 | 归属 |
|---|---|
| 逐项编辑预设参数 | 参数面板的职责；详情是**只读**展示 + 套用/更新 |
| 重命名 / 复制 | E3-1b（若已做则另说）—— 本卡详情里**没有**重命名按钮 |
| 导入 / 导出（`.fkpreset`） | E3-2 |
| 分组筛选 / 搜索 / 缩略图 | E3-1b |
| 详情里再抄一遍"仅拍照 / 仅后期"作用域提示 | P-5 已在**列表行**上给了（`presetScopeNotice`）；详情再抄一遍 = 两处文案各自漂移 |

---

## 7. 存疑 / 发现的文档冲突（**请架构师裁决**）

1. ★ **`preset-DESIGN.md` §1 第 2 层的槽位号写反了**：文档写 `[5..7]` 趾部、`[8..10]` 肩部；
   引擎 `FilmParams.h:58-59` 是 **`float shoulder[3] = {…}; // 5..7`** 与
   **`float toe[3] = {…}; // 8..10`**，`FilmParams.h:470-471` 的读取也是 `v[5+i] → shoulder`、
   `v[8+i] → toe`。**本卡以引擎为准**（详情里 `[5..7]` = 肩部、`[8..10]` = 趾部），
   文档建议顺手订正（我**没有**改 `preset-DESIGN.md` —— 它不在本卡的可改范围）。
2. **`PresetScope.kt` 的 `SLOT_LABELS` 与 `PresetDetail.kt` 的 `DETAIL_META` 是两张名字表**：
   前者是 P-5 的作用域提示用词（private，**U-1 接线后只剩 `[5..10]` `[37][38]` `[78]`
   `[88..94]` 这 16 个槽**），后者是详情用词（覆盖全部可进预设的槽）。
   本卡**不能碰 `PresetScope.kt`**（U-1 的地盘），所以没有把它抽成共用表；一致性靠单测
   `detailLabelsMatchScopeNoticeWording` **反向**钉死（遍历 96 槽、由提示自己决定清单 ⇒
   提示里不可能出现没名字的参数；P-5 换词 ⇒ 本卡测试红）。
   将来若要合并，建议把两张表都收敛到 `FilmParamKeys` 旁边的一张 `SlotLabels`。
3. **`[11..19]`（`cross[0..8]` log 域染料串扰）与 `[95]` `reserved_95` 没有中文名**：
   面板没有滑杆、P-5 表也没给名字，本卡**不发明译名**，走 `FilmParamKeys` 语义名兜底
   （`cross[0]`）。正常路径下它们不会进预设（用户无从改到）——出现即代表手改 JSON / 坏数据。
4. **任务举例里的"角度 `90°`"在预设详情里不可能出现**：`[84] local_angle` 属于局部调整，
   `presetSlotIncluded` 把 `[79..87]` 整个排除在预设之外（v1 决策）。所以本卡的单位覆盖是
   EV / K / ISO / 无符号整数，没有跑 `formatDegree` 的槽。若坏数据里出现 `[84]`，详情显示
   `local_angle = 0.50`（语义名兜底，不静默）。
5. **`[34]` LUT 强度不进参数列表**（硬要求 ④）：它有另一个落点 —— 详情里的 `LUT` 行
   （`Leica M9 · 强度 100`）。这与 `presetSlotIncluded` 把它排除在 `slots` 之外是同一条口径；
   即使坏数据里出现 `[34]`，详情也**不**把它当参数行（单测钉住），但信息不丢（LUT 行）。
6. **更新预设不会重套画面、也不动强度基准**：`appliedPreset` / `presetBase` 保持原样
   （E3-1c 的强度滑杆语义是"套用那一刻的基准"）。用户若想让新配方生效，点详情里的「套用」。
   这是选择，不是遗漏 —— 换成"更新即重套"会**静默改画面**（并且多记一步撤销）。

---

## 8. ⚠ 未真机验证（明确标注）

本卡**没有**在真机 / 模拟器上跑过。以下全部是纸面结论，需要真机确认：

| 项 | 为什么需要真机 |
|---|---|
| 详情面板的**布局与可读性** | 300dp 级窄屏上「颗粒 · 中间调偏置 `ISO 6400`」这类长行会不会换行/截断、`heightIn(max = 208.dp)` 够不够、深色底上的对比度 |
| **底部三按钮**（套用 / 更新为当前设置 / 删除）是否挤 | 中文 7 字按钮 + 两个按钮在 320–360dp 宽下的排布；本卡只在代码里按 12sp 估算 |
| **点名称区进详情**的命中区域（含「›」）| 手势与既有 `pressScale` 的按下缩放是否冲突、误触率 |
| 更新后**详情自动刷新**的观感 | `detailId` + `presets` 派生是结构性保证，但"刷新瞬间有没有闪"要看真机 |
| 详情 LUT 行显示的是 `LutLibrary.label` | 中文名是否真的存在（否则显示 id）—— 依赖 LUT 库资产，本卡未逐一核对 42 个 LUT |
| 内置三条的实际渲染 | 单测用的是 `builtin.json` 的真实解析（`BuiltinPresets.parse`），但"屏幕上好不好看"没验 |

真机验收清单（建议）：
1. 底栏「预设」→ 点「徕卡一瞬」名称 → 详情应显示 §2 那张清单（7 项 + LUT 100）；
2. 详情底部**没有**「更新为当前设置 / 删除」，只有「内置预设 · 只读」；
3. 返回 → 保存一条用户预设 → 改几个参数 → 进它的详情 → 「更新为当前设置」→ 详情里的参数
   立刻变成刚改的那些，且**内置三条还在列表里**；
4. 更新后按「撤销」应该**没有反应**（更新不进编辑历史）；
5. 详情里按「删除」→ 二次确认 → 确认后回到列表且那条消失。

---

## 9. 构建 + 单测（真跑）

```
rm -rf app/build/test-results
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest --rerun-tasks
```

（`--rerun-tasks` 是必须的：本项目单跑测试会 `FROM-CACHE` 假绿 —— 报告里的数字只认真跑。）

结果（**最终一轮，退出码 0**；这轮是在"注释按 U-1 新口径更正"之后重跑的）：

```
BUILD SUCCESSFUL in 17s
46 actionable tasks: 46 executed          ← 全部真跑（--rerun-tasks，无 FROM-CACHE）
XML: 40 个测试类 / 436 tests / 0 failures / 0 errors / 0 skipped
```

| 数字 | 值 | 说明 |
|---|---|---|
| 总用例 | **436** | 基线 **418**（任务给的数字） |
| 本卡新增 | **11** | `com.filmkit.app.ui.PresetDetailTest`（用例数取自 XML） |
| 其余增量 | 7 | 同工作区其它卡在飞的用例（`PresetScopeTest` 等未跟踪的测试文件） |
| 失败 | **0** | 本卡引入的失败：**0** |

⚠ 中间几轮出现过的 3 个非绿：2 个是**本卡自己的**断言错误（`evText` 丢负号、白平衡 label
用了 `R/G/B` 而非面板原词"红/绿/蓝"），已修；1 个是**其它卡的在飞改动**（见 §9.1）。
最终轮全绿。

### 9.1 与 U-1 的口径对齐（架构师 2026-10-11 同步）

**变更事实**：U-1 已把 `[23][24]` 分离色调 + `[25]` 单色接到编辑器链路 `kFsStyle` ⇒ 这两组槽
**两侧都生效** ⇒ `PresetScope.kt` 删掉了它们的 `CAMERA_ONLY` 条目（"仅拍照"提示随之消失）。

**本卡怎么对齐的**（只改自己的文件，**没碰 `PresetScope.kt`**）：

| 项 | 处理 |
|---|---|
| 详情里的名字 | **不变**：`[23][24]` = "分离色调 · 暖 / 冷"、`[25]` = "黑白"（U-1 前由 P-5 提示确立的术语，现改由 §9.2 第 13/14 项溯源；**不因为提示消失就退回工程名** `split_warm` / `monochrome`） |
| 详情里的**作用域灰显 / "仅拍照"标注** | **本来就没有**（详情是纯只读清单，作用域提示只在列表行上，由 `presetScopeNotice` 出）⇒ 无需改。这也是当初把提示留在列表行的收益：引擎接线后**详情一行都不用动** |
| 测试 `detailLabelsMatchScopeNoticeWording` | 从"硬编码 19 个槽 + 断言必须有提示"改成**反向一致性**：遍历 96 槽，**由 `presetScopeNotice` 自己决定**哪些槽还有跨链路差异、提示里用什么词，再要求详情用同一个词（⇒ 提示里不可能出现"没有名字的参数"）；清单不再硬编码 ⇒ U-2 接线 `[5..10]` / `[37][38]` 时不会假红 |
| 另加**反向断言** | `[23][24][25]` **不得**出现在任何场景的作用域提示里（谁把它们写回 `CAMERA_ONLY`，本测试与 `PresetScopeTest` 同时红） |
| 是否认为 `[23..25]` 不该接线 | **不认为**。用户 2026-10-11 已拍板"不统一的要统一掉，缺的补上"（§9 开头），`[25]` 单色在编辑器侧无落点是 §1.6 记录的真实缺陷（"黑白预设套进编辑器不变黑白"）。接线后详情反而更准确：详情显示"黑白 开"与画面一致 |
| 注释口径更正（架构师点名的"顺手事"）| ① `EditorScreen.kt` 的 `PresetRow` KDoc（U-1 指出 :3590 附近）—— 旧文写"含 `[23][24]`/`[25]`/`[5..10]` 的预设在本页没有视觉效果（内置的徕卡一瞬 / 理光负片就命中）"，已更正为"只剩 `[5..10]` / `[37][38]`（等 U-2）"，并注明**槽位清单的唯一维护点是 `PresetScope.kt`**（本文件不复述第二份）；② `PresetDetail.kt` 里 `[23][24]`/`[25]` 的**行内注释**同步改为 U-1 口径（名字溯源从"`SLOT_LABELS` 现存条目"改为"§9.2 第 13/14 项 + `SLOT_LABELS` 的历史字面"）；③ 顺带把 `[5..10]` 注释里"R/G/B 逐通道标注"改成"通道用面板原词（红/绿/蓝）"（实现早已是中文，注释滞后）|
| 归属澄清 | `PhotoViewScreen.kt:870` 有同类过时注释 —— **该文件不归本卡**，未动（架构师自行处理）|

### 9.2 并发环境说明（**为什么某一轮可能不是全绿**）

本卡与另外两张卡（查看页 bug、引擎 U-1）**共享同一个工作区**，跑构建的窗口里遇到过两种
与本卡无关的现象，逐条记录以免误判：

| 现象 | 归属 | 证据 |
|---|---|---|
| `CameraController.kt` 语法错误（`Expecting member declaration`）⇒ `compileDebugKotlin` 失败 | **另一张卡在飞**（正在写该文件；文件里出现重复代码块 = 编辑中间态）| 本卡从未打开过 `CameraController.kt`；`git status` 里它是 `M`，且修改时间在构建前后跳动 |
| `EditorStyleWhitelistTest > kdocListMatchesScriptWhitelist` 失败（KDoc 清单 vs 脚本 want **顺序不同**）| **另一张卡在飞**（`tools/check-style-passthrough.py` 被改 189 行）| 该测试读 `tools/check-style-passthrough.py` / `core/engine/src/render/FrameRenderer.cpp` / `EditorEngineSession.kt` / `FilmParams.h` —— **四个文件本卡一个都没碰**（本卡 diff 不含 `grain_amount` / `lut_strength` / `split_warm` 任一字符串） |
| `PresetDetailTest.detailLabelsMatchScopeNoticeWording` 在 U-1 落地瞬间红 | **本卡**（口径过期）| 见 §9.1 —— 已按新口径改成反向一致性断言，最终轮全绿。⚠ 期间 **U-1 的 agent 也直接改过本卡的测试文件**（把我的硬编码清单换成它的 16 槽清单 + 3 条独立断言）—— 本卡在此基础上改成"不硬编码"版本，并保留它对 `[23..25]` 的意图 |
