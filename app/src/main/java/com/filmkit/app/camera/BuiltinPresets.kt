package com.filmkit.app.camera

import android.content.Context
import android.content.SharedPreferences
import com.filmkit.app.EngineBridge
import com.filmkit.app.kv

/**
 * 内置胶片预设包（P-2b）：`assets/presets/builtin.json`，随包分发、**只读**（用户不可删改，
 * [EditorPreset.source] = [PRESET_SOURCE_BUILTIN]）。
 *
 * 首批三条 = 徕卡一瞬（Leica M9 CCD 风格）/ 理光负片（Ricoh GR 负片模式）/ 徕卡黑白
 * （Leica B&W HC）。配方依据 = 架构师对 LUT 二进制数据的实测（对比度斜率、通道响应、
 * 白场截断）+ 胶片物理常识；**量值未经真机标定**（见 `docs/reports/p2b-builtin-presets-REPORT.md`
 * 的「未做/存疑项」）。
 *
 * ## ★ 为什么黑白预设**故意不加** `[25] monochrome`（反直觉，但别"顺手补上"）
 *
 * [EditorPreset.slots] 的 `[25]` 是引擎的单色开关（`FilmParams.h` 的 `monochrome`）：它把画面
 * **去色成灰**。而内置的 `leica_b_w_hc` LUT **自带通道混合** —— 实测通道响应跨度 233/255
 * （纯绿 → 233 最亮、**纯蓝 → 0 全黑**、纯红 → 154），这正是黑白胶片配黄绿滤镜的响应曲线
 * （蓝压黑、绿提亮）。
 *
 * 两条路的**结果完全不同**，不是"重复但都对"：
 *  - 先 `[25]` 去色 ⇒ 三通道被抹成同一条灰阶 ⇒ LUT 的通道混合**输入已经是灰的**，
 *    混合矩阵作用在灰上仍是灰 ⇒ 黄绿滤镜的响应被**整段抹平**，出来的是一张平淡灰照片；
 *  - 不设 `[25]` ⇒ LUT 拿到真实 RGB，按自己的通道权重合成黑白 ⇒ 才是 LUT 设计的观感。
 *
 * 这条口径由单测 `BuiltinPresetsTest.bwPresetHasNoMonochrome` 钉死（注释会腐烂，测试不会）。
 * 将来若换成"中性黑白 LUT + 引擎去色"的配方，那时**同时**改 JSON 与那条测试。
 *
 * ## 加载纪律
 *
 *  - 解析**必须**走 [presetsFromJson]（见 [parse] 的 KDoc），内置与用户预设同一条路径；
 *  - 任何失败（assets 缺失 / JSON 坏 / 解析出 0 条）⇒ **空列表 + WARN 日志，绝不抛异常**；
 *  - **不做缓存**：assets 读取很便宜（约 1 KB 的 JSON），而静态缓存（`neutral()` 那类）
 *    会带来跨测试 / 跨版本的状态污染，收益远小于风险。
 */
internal object BuiltinPresets {

    /** assets 内路径（与源码树 `app/src/main/assets/presets/builtin.json` 对应） */
    const val ASSET_PATH = "presets/builtin.json"

    /**
     * 纯函数：内置预设 JSON → 预设列表（**无 Android 依赖** ⇒ JVM 单测直接喂字符串）。
     *
     * ★ **复用 [presetsFromJson]，不另写解析器**：内置包与用户预设走同一条解析路径 ⇒
     * schema 校验（只认 [PRESET_SCHEMA]）、未知参数名**整项拒绝**、逐槽三档容错
     * （被策略排除的槽 / 非法值 / 等于恒等值）对内置包同样生效。若给内置包开一条"更宽松"的
     * 专用解析器，两边的容错口径必然漂移，而漂移的表现是"内置包能加载、用户包加载不了"
     * 这类只在一边复现的诡异 bug。
     *
     * 坏 JSON / 顶层不是数组 / 全部条目被拒 ⇒ **空列表，不抛异常**（[presetsFromJson] 本身
     * 就不抛）。空列表的语义是"这次没有内置预设可用"，不是错误 —— 诊断日志由 [load] 落。
     */
    internal fun parse(json: String): List<EditorPreset> = presetsFromJson(json).presets

    /**
     * Android 侧：读 assets + 解析。
     *
     * 失败路径全部**降级为空列表 + 一条 WARN 日志**（事件名 `preset.builtin_*`），
     * 绝不抛：内置包坏掉是"少三条预设"，不该让拍照页 / 查看页 / 编辑器崩掉或空手而归 ——
     * [all] 仍会返回用户自己的预设。
     *
     * 记日志的两个时机不同，别合并：`builtin_load_failed` = 资产读不出来（包坏了）；
     * `builtin_skipped` = 读出来了但有条目/槽被解析器拒绝（数据与代码版本不匹配）——
     * 后者是**内置包自己写错**的信号，必须能在诊断日志里看见，而不是静默少一条。
     */
    fun load(context: Context): List<EditorPreset> {
        val json = try {
            context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (t: Throwable) {
            log("preset.builtin_load_failed", "why" to "读取 $ASSET_PATH 失败：${t.message}")
            return emptyList()
        }
        val res = presetsFromJson(json)
        if (res.skipped.isNotEmpty()) {
            log(
                "preset.builtin_skipped",
                "count" to res.skipped.size,
                "why" to res.skipped.joinToString("；").take(500),
            )
        }
        if (res.presets.isEmpty()) {
            log("preset.builtin_empty", "asset" to ASSET_PATH, "chars" to json.length)
            return emptyList()
        }
        return res.presets
    }

    /**
     * 完整预设列表 = **用户预设（在前）+ 内置预设（在后）**。
     *
     * 顺序的理由：用户自己存的更常用（最近保存的在最前，见 [PresetStore.upsert]），
     * 内置包是"打底"的只读内容。分组 / 收藏置顶是 UI 层的排序，不在这里做
     *（数据层给稳定顺序，UI 给呈现顺序 —— 两处各排一次必然出现"同一个列表两种顺序"）。
     *
     * ★ **内置包坏掉不能连累用户数据**：[load] 已保证不抛且失败返回空列表 ⇒ 这里的 `+`
     * 永远能返回用户预设。
     *
     * ★ **这是三处列表（拍照 / 查看页 / 编辑器）唯一的数据源入口**（P-2c）：任何一处直接写
     * `PresetStore.load(prefs)` 都会让内置三条在界面上消失 —— 那是本卡修掉的原始缺陷。
     */
    fun all(context: Context, prefs: SharedPreferences): List<EditorPreset> =
        mergePresets(
            PresetStore.load(prefs),
            applyBuiltinOverrides(load(context), BuiltinOverrides.load(prefs)),
        )

    /** 诊断日志（与 [PresetStore] 同一出口：`nativeAppendLog` 的 `app` 模块）。 */
    private fun log(event: String, vararg detail: Pair<String, Any?>) {
        EngineBridge.nativeAppendLog(EngineBridge.LEVEL_WARN, "app", event, kv(*detail))
    }
}

/**
 * ★ PS-3：**内置预设的用户覆盖层** —— 用户 2026-10-11 拍板："内置预设为什么不让修改？
 * 我觉得没有必要，**最多限制不能删除就行**"。
 *
 * ## 为什么用覆盖层而不是"直接改内置常量 / 写进 PresetStore"
 *
 * [BuiltinPresets.load] 每次从 assets 读**出厂定义**（随包分发、版本升级会变）。用户对内置的
 * 修改写进 prefs 的**按 id 覆盖表**（[KEY]），加载时叠加 ⇒ 三条性质同时成立：
 *
 *  1. **版本升级后，未被改过的内置跟着更新**（出厂配方的改进用户能吃到）；
 *  2. **改过的内置保留用户版本**（升级不会把用户的调整冲掉）；
 *  3. **「恢复默认」= 删掉那条覆盖**（[clear]）⇒ 立刻回到出厂定义，不需要第二份"原始副本"。
 *
 * 若改成"把内置也写进 `PresetStore`"，上面三条全废：出厂改进永远到不了用户手上（同 id 的
 * 用户副本"先到者胜"），而且删除保护会失去依据 —— `PresetStore` 里的 `source` 是**用户可写**
 * 的字段（JSON 反序列化不校验），手改 prefs 就能把内置变成可删除的普通预设。
 *
 * ## 存什么 / 不存什么
 *
 * 存**整条** [EditorPreset] 的 JSON（复用 [presetsToJson] / [presetsFromJson]，与用户预设
 * 同一条序列化路径），但 [applyBuiltinOverrides] 只取**用户可改的字段**（名称 / 分组 /
 * 配方 / LUT）—— **身份字段（id / source / createdAt）永远取出厂值** ⇒ 覆盖层不可能把内置
 * 变成"可删除的用户预设"（删除保护在数据层就被钉住，不靠 UI 自觉）。
 *
 * ## 容错
 *
 * 坏 JSON / 坏条目 ⇒ **空表 + WARN 日志，绝不抛**（与 [BuiltinPresets.load] 同一条纪律）：
 * 覆盖表坏掉的表现是"内置回到出厂值"，而不是拍照页崩掉。
 */
internal object BuiltinOverrides {

    /** prefs key：覆盖表（一个 JSON 数组，元素 = 被用户改过的内置预设） */
    const val KEY = "builtin_overrides"

    /** 读取覆盖表（id → 覆盖后的预设）。坏数据 ⇒ 空表 + 一条 WARN 日志。 */
    fun load(prefs: SharedPreferences): Map<String, EditorPreset> {
        val res = presetsFromJson(prefs.getString(KEY, null))
        if (res.skipped.isNotEmpty()) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_WARN, "app", "preset.builtin_override_skipped",
                kv("count" to res.skipped.size, "why" to res.skipped.joinToString("；").take(500)),
            )
        }
        return res.presets.associateBy { it.id }
    }

    /** 写入 / 覆盖一条（新的放最前，与 [PresetStore.upsert] 同一顺序纪律）。 */
    fun save(prefs: SharedPreferences, p: EditorPreset) {
        val next = listOf(p) + load(prefs).values.filter { it.id != p.id }
        prefs.edit().putString(KEY, presetsToJson(next)).apply()
    }

    /** 「恢复默认」：删掉该 id 的覆盖 ⇒ 下次加载得到出厂定义。 */
    fun clear(prefs: SharedPreferences, id: String) {
        val next = load(prefs).values.filter { it.id != id }
        prefs.edit().apply {
            if (next.isEmpty()) remove(KEY) else putString(KEY, presetsToJson(next))
        }.apply()
    }
}

/**
 * 覆盖层的**纯内核**（JVM 单测直接驱动）：出厂列表 + 覆盖表 ⇒ 生效列表。
 *
 * 只取用户可改的字段（名称 / 分组 / 配方 / LUT / 收藏），**身份字段取出厂值**：
 *  - `id` 必须与出厂一致（否则列表里会出现一条"野生内置"）；
 *  - `source` 必须仍是 [PRESET_SOURCE_BUILTIN]（[presetDeletable] 的唯一判据 ⇒ 删除保护）；
 *  - `createdAt` 取出厂（内置没有"创建时刻"这个语义，覆盖层也不该发明一个）。
 *
 * 覆盖表里有、出厂列表里没有的 id（旧版本的内置被删掉了）⇒ **忽略**（不凭空造预设）。
 */
internal fun applyBuiltinOverrides(
    builtin: List<EditorPreset>,
    overrides: Map<String, EditorPreset>,
): List<EditorPreset> = builtin.map { b ->
    val o = overrides[b.id] ?: return@map b
    b.copy(
        name = o.name,
        group = o.group,
        slots = o.slots,
        lutId = o.lutId,
        lutStrength = o.lutStrength,
        favorite = o.favorite,
    )
}

/**
 * 合并两个列表（[BuiltinPresets.all] 的纯内核，JVM 单测直接驱动）：**用户在前 + 内置在后**，
 * 且 **id 全局唯一**（先到者胜 ⇒ 用户那份留下）。
 *
 * 为什么要去重（不是洁癖）：id 是 UI 的**唯一判据** —— 列表 `key = { it.id }`（Compose 的
 * `LazyColumn` 遇到重复 key **直接抛异常**）、选中态比对 id、`initialPresetOf` 按 id 取第一条。
 * 一旦出现两条同 id，"用户看到两条一样的预设"只是表面，真机表现是拍照页 / 编辑器整片崩掉。
 * 而两条来源（prefs 反序列化的 `id` 字段**不受格式约束** / 未来的导入功能）本来就能撞车。
 *
 * 用户优先的理由：用户数据是他的显式选择，内置包是随包分发的打底内容 —— 同 id 时丢掉内置那份，
 * 用户不会"看到自己刚存的预设不见了"。
 */
internal fun mergePresets(
    user: List<EditorPreset>,
    builtin: List<EditorPreset>,
): List<EditorPreset> {
    val seen = user.mapTo(HashSet(user.size * 2)) { it.id }
    return user + builtin.filter { seen.add(it.id) }
}

/**
 * 该预设**用户是否可删**（P-2c）：内置包随包分发（删了下次启动又回来，且「恢复默认」要有
 * 出厂定义可回退），因此对 `source == [PRESET_SOURCE_BUILTIN]` 的条目**删除受限**。
 *
 * ★ **PS-3 口径收窄**（用户 2026-10-11："内置预设为什么不让修改？我觉得没有必要，**最多限制
 * 不能删除就行**"）：本函数现在**只**回答"能不能删"。旧口径下它还被当作"内置整体只读"的
 * 判据（编辑 / 重命名 / 复制全部置灰）—— 那三条已经放开，别再把它当"只读"用。
 *
 * 判据只有这一处：UI 按它决定删除项的可用性与那句实话，写路径按它做第二道防线，
 * `applyBuiltinOverrides` 靠它（经 `source`）保证覆盖层改不出可删除的内置。
 */
internal fun presetDeletable(p: EditorPreset): Boolean = p.source != PRESET_SOURCE_BUILTIN
