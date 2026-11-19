package com.filmkit.app.camera

import android.content.SharedPreferences

data class SpektrafilmSettings(
    val film: String = "kodak_portra_400",
    val paper: String = "kodak_portra_endura",
    val grain: Boolean = false,
    val grainArea: Double = 0.45,
    val grainBlur: Double = 0.8,
    val halation: Double = 0.5,
    val scatter: Double = 0.5,
    val dir: Double = 1.0,
    val printEv: Double = 0.0,
    val scanBlur: Double = 0.0,
    val unsharpRadius: Double = 0.7,
    val unsharpAmount: Double = 0.7,
    val formatMm: Double = 35.0,
) {
    fun nativeOptions() = doubleArrayOf(1.0, if (grain) 1.0 else 0.0, grainArea,
        grainBlur, halation, scatter, dir, printEv, scanBlur, unsharpRadius, unsharpAmount, formatMm)

    fun save(prefs: SharedPreferences) {
        prefs.edit().putString("spektra_film", film).putString("spektra_paper", paper)
            .putBoolean("spektra_grain", grain).apply {
                val values = nativeOptions()
                for (i in 2 until values.size) putFloat("spektra_option_$i", values[i].toFloat())
            }.apply()
    }

    companion object {
        fun read(prefs: SharedPreferences): SpektrafilmSettings {
            val defaults = SpektrafilmSettings().nativeOptions()
            val ranges = listOf(0.1..4.0, 0.0..3.0, 0.0..3.0, 0.0..3.0, 0.0..2.0,
                -3.0..3.0, 0.0..3.0, 0.0..3.0, 0.0..2.0, 10.0..100.0)
            fun value(i: Int): Double = prefs.getFloat("spektra_option_$i", defaults[i].toFloat())
                .toDouble().let { if (it.isFinite()) it.coerceIn(ranges[i - 2]) else defaults[i] }
            return SpektrafilmSettings(prefs.getString("spektra_film", "kodak_portra_400").orEmpty(),
                prefs.getString("spektra_paper", "kodak_portra_endura").orEmpty(),
                prefs.getBoolean("spektra_grain", false), value(2), value(3), value(4), value(5),
                value(6), value(7), value(8), value(9), value(10), value(11))
        }
    }
}

data class SpektrafilmRecipe(
    val id: String, val film: String, val filmName: String, val positive: Boolean,
    val paper: String, val paperName: String, val asset: String, val sha256: String,
    val response: String, val responseSha256: String,
) {
    val title: String get() = if (positive) "$filmName · 正片直扫" else "$filmName / $paperName"
}

// Internal byte arrays are never mutated; captures retain their own recipe and options.
class SpektrafilmCapture(
    val recipe: SpektrafilmRecipe, val settings: SpektrafilmSettings,
    val profile: ByteArray, val response: ByteArray,
) {
    val options: DoubleArray = settings.nativeOptions()
}
