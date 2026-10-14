package com.filmkit.app.heif

/**
 * 「HEIF ⇄ 4:4:4 色度」的**自动关 / 精确恢复**（用户 2026-10-14 实机行为对齐）。
 *
 * ## 为什么替用户动开关
 * HEIF 恒 4:2:0（10bit 档 = 硬件 P010，8bit 档 = YUV420Flexible；见 [HeifChroma]），
 * 而设置页的 `jpeg_444` 只作用于 JPEG ⇒ 两个开关同时"看起来开着"是静默的假象。
 * PhotonCamera 在用户实机上就是**选 HEIF 时替用户关掉 4:4:4**（用户实机验证），本项目对齐，
 * 但必须**可逆**：切回 JPEG 时精确恢复用户原值（不是"一律恢复成默认"）。
 *
 * ## 与"不许永久改掉用户设置"的关系
 * 原值记在独立键 [KEY_BACKUP] 里，只在**没有备份**时写（连续切档不覆盖备份）⇒ 不会把
 * 用户真正的选择改丢。备份键缺失/类型非法（老 prefs / 手工改坏）⇒ 回落 [DEFAULT]，
 * **不抛异常**（设置页不许因为一个脏键崩）。
 *
 * 纯逻辑 + [Prefs] 注入 ⇒ JVM 单测覆盖三态（见 `HeifJpeg444Test`）。
 */
object HeifJpeg444 {

    /** 既有开关键（`CameraController.saveJpeg` 的读取点）。 */
    const val KEY = "jpeg_444"

    /** 备份键：切到 HEIF 前的原值。 */
    const val KEY_BACKUP = "jpeg_444_before_heif"

    /** 键缺失/非法时的默认值（与 `SettingsScreen` 的读取默认一致）。 */
    const val DEFAULT = true

    /** prefs 的最小抽象（生产 = `SharedPreferences` 适配；单测 = Map fake）。 */
    interface Prefs {
        fun getBoolean(key: String, def: Boolean): Boolean
        fun has(key: String): Boolean
        fun putBoolean(key: String, value: Boolean)
        fun remove(key: String)
    }

    /**
     * 切到 HEIF：**备份当前值**（仅当尚无备份）并把 [KEY] 置 `false`。
     * @return 切换后的 `jpeg_444` 值（恒 `false`），供 UI 状态同步与日志。
     */
    fun onHeif(p: Prefs): Boolean {
        if (!p.has(KEY_BACKUP)) p.putBoolean(KEY_BACKUP, read(p, KEY, DEFAULT))
        p.putBoolean(KEY, false)
        return false
    }

    /**
     * 切回 JPEG：**精确恢复**备份值（缺失/非法 ⇒ [DEFAULT]）并清掉备份键。
     * @return 恢复后的 `jpeg_444` 值，供 UI 状态同步与日志。
     */
    fun onJpeg(p: Prefs): Boolean {
        val restored = read(p, KEY_BACKUP, DEFAULT)
        p.putBoolean(KEY, restored)
        p.remove(KEY_BACKUP)
        return restored
    }

    /** 备份键当前是否存在（调用 [onJpeg] **之前**取，用于日志区分"恢复"与"回落默认"）。 */
    fun hasBackup(p: Prefs): Boolean = p.has(KEY_BACKUP)

    /** 容错读取：键缺失/类型非法（`ClassCastException`）⇒ [def]；绝不抛、绝不改丢用户值。 */
    private fun read(p: Prefs, key: String, def: Boolean): Boolean =
        try {
            p.getBoolean(key, def)
        } catch (t: Throwable) {
            def
        }
}
