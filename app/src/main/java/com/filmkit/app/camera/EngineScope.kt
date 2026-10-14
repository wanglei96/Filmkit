package com.filmkit.app.camera

/**
 * 引擎作用域（阶段 3）：**哪个参数属于哪个影调引擎**，以及切换引擎时的**旁路恒等值**。
 *
 * ## 为什么需要它（架构师 2026-10-14 实读结论）
 *
 * "隐藏 UI 开关" **≠** "旁路"。三条链里**只有拍照链**的引擎注入段随 `[98] engine_slot` 换
 * （`kFsFilm` 的 `filmSlices()` 切片 + `EngineRegistry` 注入段）：
 *
 * | 链 | pass | 随引擎换？ |
 * |---|---|---|
 * | 拍照 | `kFsFilm`（引擎注入段 + 公共段） | ✅ |
 * | 编辑器 | `kFsStyle`（`passStyle` → `submitDisplay`） | ❌ **不随** |
 * | 输出 | `kFsOut` | ❌ |
 *
 * 于是 `[28]` / `[20]` / `[21]` / `[36]`（**公共段**，两引擎共用 shader 段）在引擎 1 下
 * **仍然生效**；`[5..10]` / `[37]` / `[38]` 在**编辑器链路**（`computeEditorCurve`）也仍然生效。
 * 这些必须在参数组装处**置恒等值**才算旁路。
 *
 * ## 单一真源
 *
 * UI 显隐（`ParamControls` / `SettingsScreen`）与两条链路的参数归零
 * （`CameraController` 拍照 / `EditorExport` 编辑器）**都读这里** —— 不许各自抄一份。
 *
 * ## 恒等值口径
 *
 * **唯一真源 = `core/engine/src/film/FilmParams.h` 的默认值**；例外逐条注明。
 * 依据表见 `docs/engine-slots-design.md` §8.1 B2。
 *
 * ## 实测依据
 *
 * 参数归属由"`kFsFilm` 按 `filmSlices()` 锚点切 5 段 + 逐段提取 uniform + 反查赋值点"得出：
 * 引擎 0 注入段独占 21 个 uniform（`uFilmic*`×20 + `uGamut`，来源 `FrameRenderer.cpp:14014`
 * 的 `updateFilmicEV` 与 `:14130` 的 `p.gamut`）；公共段 15 个
 * （`uExposure`/`uToneK`/`uSat`/`uLocal*`/`uMono`/`uSplit`/`uLook*`/`uHalDomainMap`/`uDomainRatio`/`uTex`）。
 */
internal object EngineScope {

    /**
     * 引擎 0（filmic）独占的槽位：切到其它引擎 ⇒ **UI 隐藏 + 参数旁路**。
     *
     * - `[5..7]` `shoulder[3]` / `[8..10]` `toe[3]` —— filmic latitude（`updateFilmicEV` 的入参）
     * - `[35]` `auto_tone` —— 黑白点成对自动（`FrameRenderer.cpp:14014`）
     * - `[37]` `filmic_black_ev` / `[38]` `filmic_white_ev` —— 黑白点
     * - `[40]` `gamut` —— filmic 之后的 gamut 收尾（`:14130` → `uGamut`）
     *
     * ⚠ 不含 `[4]` contrast：它在**编辑器链路**（`kFsStyle:14680` 的 `uContrast`）里是通用的，
     * 切引擎后**照样生效** ⇒ 保留显示（拍照侧作用域由 `PresetScope` 标注）。
     */
    val FILMIC_OWNED_SLOTS: Set<Int> = setOf(
        5, 6, 7, 8, 9, 10,
        35,
        37, 38,
        40,
    )

    /** 引擎 1（sigmoid）独占的槽位：阶段 3 **无 UI 参数**（固定默认 `contrast=1.2`）⇒ 空。 */
    val SIGMOID_OWNED_SLOTS: Set<Int> = emptySet()

    // ⚠ 此处原有一条 `UI_HIDDEN_PARAM_KEYS = setOf("sat")`（"引擎 1 下显式隐藏「饱和度」"），
    //    在卡 C 抓到 P1（`[22] sat_high` 未入表 ⇒ 半旁路）之后**已删除**：[22] 归零后，一个
    //    「饱和度」滑杆同写的三槽 `[20][21][22]` **全部**失效 ⇒ UI 侧"涉及的槽全部失效才隐藏"
    //    这条**通用**判据即可自动隐藏它，不需要产品级的显式例外。
    //    （原则：能自动推导的判据不要显式声明 —— 否则将来会被误当冗余删掉，或被误读成"技术必然"。）
    //    用户 2026-10-14 的选择仍是"整体隐藏「饱和度」"，只是现在由通用判据达成。

    /**
     * 切换引擎时必须置**恒等**的槽位 → 恒等值。
     *
     * 含两类（这正是"隐藏开关不等于旁路"的落点）：
     *  1. **公共段**（`[28]` / `[20]` / `[21]` / `[36]`）—— 不置恒等就仍在生效；
     *  2. **编辑器曲线段**（`[5..10]` / `[37]` / `[38]`）—— `kFsStyle` 的 `computeEditorCurve`
     *     不随引擎切换（`FrameRenderer.cpp:14703`）。
     *
     * `[40]` gamut **不在此表**：`uGamut` 声明在 `kFsFilm` 的 engine_def 段、唯一 GLSL 使用在
     * engine_main 段 ⇒ 两段都被引擎 1 的注入段整体替换、引擎 1 shader 里没有这个 uniform
     * ⇒ `:14130` 落 location=−1（GL 安全 no-op）。卡 A 2026-10-14 独立复核：无遗漏路径
     * （`gamutMapV5` 全仓只有定义 + 那一处调用）。
     *
     * ⚠ `[35]` auto_tone **在此表**（我原判"由 shader 结构自动旁路"是**错的**，卡 A 审计抓出）——
     * 它靠**成员变量副作用**生效，不受 uniform location 保护。依据见 NEUTRALIZE 内该行的注释。
     */
    val NEUTRALIZE: Map<Int, Double> = buildMap {
        // ── 公共段：不置恒等就仍生效 ──────────────────────────────────────────────
        // [28] tone_strength → uToneK
        // ⛔ **必须 = 1.0（默认值），绝不能 = 0.0** —— 卡 C 2026-10-14 审计（离线链真跑）：
        //    引擎 1 的 main 注入段（`FrameRenderer.cpp:6603` 的 `kFsSigmoidMain`）**复用** uToneK
        //    作为 sigmoid 整条曲线的应用权重：
        //        if (uToneK > 0.0) { vec3 toned = sigmoidTone(out3); out3 = mix(out3, toned, uToneK); }
        //    且 `sigmoidTone` 在**全文件只有这一个调用点** ⇒ 置 0 会让**引擎 1 一次都不执行 sigmoid**
        //    （空转），其结果与"引擎 0 + uToneK=0"**逐字节相同**（crc32 `0x189e4126`）。
        //    ⚠ 我原先按 `mix(lin, toned, 0) = lin ⇒ 恒等 = 0` 的推理是**错的** —— 那只在引擎 0 成立
        //    （引擎 0 的 `toned` 才是"基准影调"）；引擎 1 里 `toned` 就是 sigmoid 本身。
        //    最危险的是：**六条验收判据全部会通过**（改 filmic 槽位当然"成片不变"）⇒ 卡 C 已补**判据 0**
        //    （引擎 1 必须真的改变画面）。
        put(28, 1.0)
        // [20]/[21]/[22] sat_shadow / sat_mid / sat_high（`FilmParams.h:93-95`，默认 1.0 = 乘性恒等）
        // ⚠ [22] 是卡 C 抓到的 P1 漏径：**一个「饱和度」滑杆同写三槽**（`EditorExport.writeSaturation`），
        //    只钉 [20]/[21] 会留下"高光档仍跟用户值走"的**半旁路**（`tail:4503` 的 uSat 三分量加权
        //    `sat = uSat.x*ws + uSat.y*wm + uSat.z*wh`）。
        put(20, 1.0)
        put(21, 1.0)
        put(22, 1.0)
        // [36] local_tone → uLocalStrength（FrameRenderer.cpp:4096 "0 = 关（A/B 通路）"）
        put(36, 0.0)
        // [35] auto_tone —— **卡 A 2026-10-14 审计抓到的漏径**：它不靠 uniform 生效，靠
        //      **成员变量副作用**：updateFilmicEV 在 passFilm 里**无条件调用**（:14014，
        //      只有 program 重建在 if 分支里）⇒ 引擎 1 下 uniform 落 −1 但
        //      `last_still_w999_`（:13865，由 auto_on 门控）照写 ⇒ stillW999() →
        //      Bridge.cpp:913 → CameraController.sceneHighlightEv() → ExposurePlanner 的
        //      欠曝量 ⇒ **仍会改变成片曝光**。归零 = 该通道钉在"auto_tone 关"。
        put(35, 0.0)
        // ── 编辑器曲线段：kFsStyle 不随引擎切换 ────────────────────────────────────
        // [5..10] latitude：computeEditorCurve 的门控要求"全 = 默认 0.01"才整段跳过
        for (s in 5..10) put(s, LATITUDE_UI_PIVOT.toDouble())
        // [37]/[38] 黑白点：同上（门控比较的是 FilmParams{} 的默认值）
        put(37, FILMIC_BLACK_EV_PIVOT.toDouble())
        put(38, FILMIC_WHITE_EV_PIVOT.toDouble())
    }

    /**
     * 按引擎作用域归零（**纯函数**，JVM 可测）。
     *
     * `engineSlot == 0` ⇒ **原样返回入参**（同一实例、不复制、不触碰任何槽）—— 这是判据 1
     * （零回归）的硬要求：阶段 2 基线的逐字节等价不许被本函数破坏。
     *
     * `engineSlot != 0` ⇒ 返回**副本**（不改调用方的数组），把 [NEUTRALIZE] 里的槽位置为恒等。
     *
     * @param slots 组装好的 `FilmParams` 槽位数组（长度 = `kCount`）
     * @param engineSlot `[98] engine_slot` 的值
     */
    /**
     * **当前生效的引擎槽位** = 预设值 与 设置页全局开关 的合成（**单一真源**）。
     *
     * 设置页三档开关（prefs 键 **`engine_override`**，`Int`）：
     *  - **`-1`（默认）跟随预设** ⇒ 用预设的 `[98]`（「完整参数」页那个入口继续逐条控制）；
     *  - **`0` / `1` 强制引擎** ⇒ 全局覆盖所有预设（用户 2026-10-14 拍板的语义）。
     *
     * ⚠ **只作用于拍照链路**（相机预览 / 成片 / DNG）：设置页的语义是"这台相机现在用哪个引擎"。
     * **编辑器链路不参与** —— 编辑一张照片应当用它**拍摄时**的引擎（照片元数据里的 `[98]`），
     * 否则"编辑旧照片"会因设置页的开关而突然变样。
     *
     * **两个消费方都必须读本函数**，不许各自组合（否则会出现"渲染用 A 引擎、UI 按 B 引擎隐藏
     * 参数"的假读数 —— 与卡 C 报的 P2 同型）：
     *  1. **渲染侧** `CameraController.engineScoped(...)` —— 合成后还要把 `[98]` **真的写成**
     *     `effective`（`FrameRenderer` 读 `p[98]` 决定用哪个 program），只旁路不改 `[98]` 是**不够**的；
     *  2. **UI 侧** `ParamControls.slotActiveInEngine` / `CameraPresetFullEdit.paramActiveInEngine` /
     *     设置页各 gate —— 用同一个 `effective` 决定显隐。
     */
    fun effectiveEngineSlot(presetSlot: Int, override: Int): Int =
        if (override in KNOWN_ENGINE_SLOTS) override else presetSlot

    /**
     * 已注册的引擎槽位（与 `core/engine/src/film/EngineRegistry.cpp` 的 `kEngines` 表一致）。
     *
     * `EngineSlotUiTest.engineChoicesMatchTheCppRegistry` 已有"直接解析 C++ 表对拍"的纪律 ——
     * 加第 3 个引擎时两处要同步。
     */
    val KNOWN_ENGINE_SLOTS: Set<Int> = setOf(0, 1)

    fun applyEngineScope(slots: DoubleArray, engineSlot: Int): DoubleArray {
        // 未注册的 engine_slot ⇒ **按 0 处理、不旁路**，与 C++ 侧的降级语义一致
        // （`FrameRenderer.cpp` 的 `warnEngineSlotUnavailable`：降级到引擎 0 + 记 WARN）。
        // 否则会得到"引擎 0 + filmic 参数被清零"的**第三种状态** —— 即卡 C 报的 P2
        // （Kotlin 旁路 vs C++ 降级两侧语义不一致；触发面 = 预设 JSON 手改 `engine_slot`）。
        val effective = if (engineSlot in KNOWN_ENGINE_SLOTS) engineSlot else 0
        if (effective == 0) return slots
        val out = slots.copyOf()
        for ((slot, v) in NEUTRALIZE) if (slot in out.indices) out[slot] = v
        return out
    }
}
