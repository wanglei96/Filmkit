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
        mergePresets(PresetStore.load(prefs), load(context))

    /** 诊断日志（与 [PresetStore] 同一出口：`nativeAppendLog` 的 `app` 模块）。 */
    private fun log(event: String, vararg detail: Pair<String, Any?>) {
        EngineBridge.nativeAppendLog(EngineBridge.LEVEL_WARN, "app", event, kv(*detail))
    }
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
 * 该预设**用户是否可删**（P-2c）：内置包只读（随包分发，删了下次启动又回来），
 * 因此编辑器预设面板对 `source == [PRESET_SOURCE_BUILTIN]` 的条目**不显示删除按钮**。
 *
 * 判据只有这一处：面板按它决定是否渲染删除按钮，测试按它钉住"内置不可删 / 用户可删"。
 */
internal fun presetDeletable(p: EditorPreset): Boolean = p.source != PRESET_SOURCE_BUILTIN
