package com.filmkit.app.ui

/**
 * M1 内置胶片预设（简化模型的参数集，索引见 C++ film/FilmParams.h）。
 * M4 起接入 JSON 预设系统。
 *
 * 教训（X300 事故）：DoubleArray 默认全 0，而对比度 0 = 灰板、串扰矩阵 0 = 信号清零、
 * 白平衡 0 = 全黑——所有预设必须从 neutral() 基线出发再覆盖。
 */
object CameraPresets {

    val names = listOf("标准", "负片 400", "黑白 400")

    fun byIndex(i: Int): DoubleArray = when (i) {
        0 -> standard()
        2 -> mono400()
        else -> negative400()
    }

    private fun put3(a: DoubleArray, i: Int, v0: Double, v1: Double, v2: Double) {
        a[i] = v0; a[i + 1] = v1; a[i + 2] = v2
    }

    /** 中性基线：所有参数处于"无效果"的正确默认值。 */
    private fun neutral(): DoubleArray = DoubleArray(28).apply {
        put3(this, 1, 1.0, 1.0, 1.0)     // 白平衡增益
        this[4] = 1.0                     // 对比度
        // 染料串扰 = 单位阵
        put3(this, 11, 1.0, 0.0, 0.0)
        put3(this, 14, 0.0, 1.0, 0.0)
        put3(this, 17, 0.0, 0.0, 1.0)
        put3(this, 20, 1.0, 1.0, 1.0)     // 分区饱和度
    }

    fun standard(): DoubleArray = neutral().apply {
        this[21] = 1.1                    // 中间调略提饱和
    }

    fun negative400(): DoubleArray = neutral().apply {
        this[4] = 1.12                    // contrast
        put3(this, 5, 0.32, 0.30, 0.26)   // shoulder
        put3(this, 8, 0.55, 0.48, 0.38)   // toe
        put3(this, 11, 1.04, 0.02, -0.02) // 串扰矩阵
        put3(this, 14, -0.01, 1.02, 0.01)
        put3(this, 17, 0.02, -0.01, 1.03)
        put3(this, 20, 0.88, 1.08, 0.96)  // 分区饱和度
        this[23] = 0.45                   // 阴影暖
        this[24] = 0.15                   // 高光冷
        this[26] = 0.10                   // grain
        this[27] = 1.2
    }

    fun mono400(): DoubleArray = neutral().apply {
        this[4] = 1.18
        put3(this, 5, 0.30, 0.30, 0.30)
        put3(this, 8, 0.45, 0.45, 0.45)
        this[21] = 0.6
        this[25] = 1.0                    // 黑白
        this[26] = 0.16
        this[27] = 1.4
    }
}
