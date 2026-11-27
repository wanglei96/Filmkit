package com.filmkit.app.camera

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class SpektrafilmSettingsTest {
    private fun prefs(values: Map<String, Any> = emptyMap()): SharedPreferences =
        Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString", "getFloat", "getBoolean" -> values[args!![0]] ?: args[1]
                else -> error("Unexpected preferences access: ${method.name}")
            }
        } as SharedPreferences

    @Test fun absentSettingsDisableGrainWithoutChangingSharedFilmParams() {
        val s = SpektrafilmSettings.read(prefs())
        assertFalse(s.grain)
        assertFalse(s.vulkan)
        assertEquals("kodak_portra_400", s.film)
        assertEquals("kodak_portra_endura", s.paper)
        assertEquals(12, s.nativeOptions().size)
        assertEquals(0.0, s.nativeOptions()[1], 0.0)
        assertArrayEquals(doubleArrayOf(1.0, 0.0, 0.45, 0.8, 0.5, 0.5, 1.0,
            0.0, 0.0, 0.7, 0.7, 35.0), s.nativeOptions(), 1e-7)
    }

    @Test fun vulkanOptInIsFrozenWithTheCaptureWithoutChangingRecipeControls() {
        val recipe = SpektrafilmRecipe("id", "film", "film", false, "paper", "paper",
            "profile", "checksum", "response", "checksum")
        val on = SpektrafilmSettings.read(prefs(mapOf("spektra_vulkan" to true)))
        val capture = SpektrafilmCapture(recipe, on, byteArrayOf(), byteArrayOf())
        val next = SpektrafilmCapture(recipe, on.copy(vulkan = false), byteArrayOf(), byteArrayOf())
        assertTrue(capture.settings.vulkan)
        assertEquals(13, capture.options.size)
        assertEquals(1.0, capture.options[12], 0.0)
        assertEquals(0.0, next.options[12], 0.0)
        assertArrayEquals(on.nativeOptions(), capture.options.copyOf(12), 0.0)
    }

    @Test fun privateOptionsHaveFiniteBoundedInputs() {
        val s = SpektrafilmSettings.read(prefs(mapOf("spektra_option_2" to Float.NaN,
            "spektra_option_7" to 100f, "spektra_option_8" to -2f)))
        assertEquals(0.45, s.grainArea, 1e-7)
        assertEquals(3.0, s.printEv, 0.0)
        assertEquals(0.0, s.scanBlur, 0.0)
    }

    @Test fun capturedSelectionAndOptionsSurviveLaterPreferenceChanges() {
        val values = mutableMapOf<String, Any>("spektra_grain" to true,
            "spektra_film" to "fujifilm_provia_100f",
            "spektra_option_2" to 1.0f, "spektra_option_3" to 0.65f,
            "spektra_option_4" to 1.0f, "spektra_option_5" to 1.0f,
            "spektra_option_6" to 1.0f)
        val captured = SpektrafilmSettings.read(prefs(values))
        values["spektra_grain"] = false
        values["spektra_film"] = "kodak_portra_400"
        assertTrue(captured.grain)
        assertEquals("fujifilm_provia_100f", captured.film)
        assertArrayEquals(doubleArrayOf(1.0, 1.0, 1.0, 0.65, 1.0, 1.0, 1.0,
            0.0, 0.0, 0.7, 0.7, 35.0), captured.nativeOptions(), 1e-7)
        val out = captured.nativeOptions()
        out[1] = 0.0
        assertEquals(1.0, captured.nativeOptions()[1], 0.0)
    }
}
