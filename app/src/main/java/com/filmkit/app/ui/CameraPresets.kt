package com.filmkit.app.ui

/**
 * 内置预设：只保留「标准」= 无风格化中性基线（基准色彩科学：log 域 + 胶片曲线骨架，
 * 全部参数中性）。
 *
 * 预设机制定位（2026-09-20 拍板）：预设 = 用户自建（LUT + 参数调节保存），内置风格化
 * 预设（负片/黑白）无意义已删除。
 *
 * 教训（X300 事故）：DoubleArray 默认全 0，而对比度 0 = 灰板、串扰矩阵 0 = 信号清零、
 * 白平衡 0 = 全黑——必须从 neutral() 基线出发再覆盖。
 */
object CameraPresets {

    val names = listOf("标准")

    fun byIndex(i: Int): DoubleArray = standard()

    private fun put3(a: DoubleArray, i: Int, v0: Double, v1: Double, v2: Double) {
        a[i] = v0; a[i + 1] = v1; a[i + 2] = v2
    }

    /** 中性基线：所有参数处于"无效果"的正确默认值。 */
    private fun neutral(): DoubleArray = DoubleArray(34).apply {
        put3(this, 1, 1.0, 1.0, 1.0)     // 白平衡增益
        this[4] = 1.0                     // 对比度
        // 染料串扰 = 单位阵
        put3(this, 11, 1.0, 0.0, 0.0)
        put3(this, 14, 0.0, 1.0, 0.0)
        put3(this, 17, 0.0, 0.0, 1.0)
        put3(this, 20, 1.0, 1.0, 1.0)     // 分区饱和度
        // 28 = 基准影调强度：1.0 = 开（黑位 + 中灰锚定 S + 高光 rolloff，
        // 非风格化；治"发灰"）。0 = 恒等/旧行为。
        this[28] = 1.0
        // 29 = 高光重建强度：1.0 = 开（CCM 前用未饱和通道外推饱和通道）。
        this[29] = 1.0
        // 30 = 过曝提示阈值（显示域）：0.95 ≈ 242/255，0 = 关；仅预览生效。
        this[30] = 0.95
        // 31..33 = 色彩外观层（M3 P1）：强度 / 饱和增益 / 色度 S 端点。
        // 2026-09-21 复核后**参数回中性 = 占位**：真机 A/B 证明"寡淡"是 libraw
        // 默认 auto_bright 造成的伪测量（见 docs「v0.5.44 复核」节）；浓淡属风格，
        // 按 §0-D8/D9 交给 M4 的理光系 LUT。开关与通路保留，便于 A/B 与 M4 接管。
        this[31] = 1.0
        this[32] = 1.0
        this[33] = 1.0
    }

    /** 标准 = 原片：全中性（含饱和度 1.0），偏差全部归零便于色彩问题归因 */
    fun standard(): DoubleArray = neutral()
}
