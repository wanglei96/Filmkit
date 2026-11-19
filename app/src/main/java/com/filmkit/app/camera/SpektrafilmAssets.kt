package com.filmkit.app.camera

import android.content.Context
import com.filmkit.app.EngineBridge
import com.filmkit.app.kv
import java.security.MessageDigest
import org.json.JSONObject

internal object SpektrafilmAssets {
    const val PROFILE_ID = "portra400-endura-v1"
    const val ASSET = "spektrafilm/portra400-endura.skp"
    const val SHA256 = "436c0050838d7aa3ef2dbba235b55502aec16423bfe0f2013f4bcecfb67f84c9"
    @Volatile private var ready = false
    private var recipes: List<SpektrafilmRecipe>? = null
    private val cache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 6
    }

    @Synchronized fun catalog(context: Context): List<SpektrafilmRecipe> {
        recipes?.let { return it }
        val root = JSONObject(context.assets.open("spektrafilm/catalog.json").bufferedReader().use { it.readText() })
        check(root.getInt("schema") == 3)
        val entries = root.getJSONArray("profiles")
        return List(entries.length()) { i ->
            val p = entries.getJSONObject(i)
            SpektrafilmRecipe(p.getString("id"), p.getString("film"), p.getString("filmName"),
                p.getBoolean("positive"), p.getString("paper"), p.getString("paperName"),
                p.getString("asset"), p.getString("sha256"), p.getString("response"), p.getString("responseSha256"))
        }.also { recipes = it }
    }

    @Synchronized fun prepare(context: Context, settings: SpektrafilmSettings): SpektrafilmCapture {
        val recipe = catalog(context).firstOrNull {
            it.film == settings.film && (it.positive || it.paper == settings.paper)
        } ?: error("Unknown Spektrafilm recipe: ${settings.film} / ${settings.paper}")
        fun asset(name: String, expected: String): ByteArray = cache[name] ?: run {
            val bytes = context.assets.open("spektrafilm/$name").use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            check(digest == expected) { "Spektrafilm checksum mismatch: $name" }
            bytes.also { cache[name] = it }
        }
        return SpektrafilmCapture(recipe, settings,
            asset(recipe.asset, recipe.sha256), asset(recipe.response, recipe.responseSha256))
    }

    @Synchronized fun ensureReady(context: Context): Boolean {
        if (ready) return true
        return try {
            val bytes = context.assets.open(ASSET).use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            check(digest == SHA256) { "Spektrafilm profile checksum mismatch" }
            check(EngineBridge.nativeLoadSpektrafilmProfile(bytes)) { "Spektrafilm profile rejected" }
            ready = true
            true
        } catch (e: Exception) {
            EngineBridge.nativeAppendLog(EngineBridge.LEVEL_ERROR, "engine", "spektrafilm.asset_failed",
                kv("profile" to PROFILE_ID, "error" to e.toString()))
            false
        }
    }
}
