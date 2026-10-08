package com.filmkit.app.camera

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.filmkit.app.EngineBridge
import com.filmkit.app.kv
import org.json.JSONArray
import org.json.JSONObject

/**
 * 成片参数快照（E1-2 / ADR-4）：拍下这张成片时**真正下发给引擎**的那份 FilmParams，
 * 加上 LUT 选择与强度，供内建编辑器读取为起点（没有快照 = 编辑器每次从零猜参数）。
 *
 * @param params 下发数组的**冻结副本**（长度 = `core/engine/src/film/FilmParams.h::kCount`，
 *               槽位语义见 `ui/CameraPresets.kt`）；必须经所有 `applyXxxPref` 覆盖后的
 *               最终值，不是 `CameraPresets.standard()` 的原始值。长度不写死 —— 契约加
 *               槽位时快照跟着变（本文件的编解码与长度无关）。
 * @param lutId  LUT id（`LutLibrary` 的 id 命名空间，编辑器靠它反查资产）；未选 = ""
 * @param lutK   LUT 强度（= 下发数组的 [34]）；未选 = 1.0
 * @param timeMs 成片落盘时刻（epochMs，与文件名里的时间戳同源）；下发点冻结时还不知道
 *               （0），由 `saveJpeg` 落盘成功后填。
 */
data class StillSnapshot(
    val params: DoubleArray,
    val lutId: String,
    val lutK: Double,
    val timeMs: Long = 0L,
)

/**
 * 快照读写（ADR-4）：`SharedPreferences("filmkit")` 的键 [KEY] 下**一个 JSON 字符串**，
 * 形如 `{ "<成片文件名>": { "p": [kCount 个 double], "lut": "<id 或空>", "lutK": 1.0,
 * "t": <epochMs> }, ... }`。
 *
 * 为什么键用**文件名**而不是完整 URI：MediaStore URI 会随重扫/迁移变化，文件名稳定
 *（落盘时反查一次 MediaStore 的真实 DISPLAY_NAME，保证与编辑器侧查到的是同一个键）。
 * 为什么不写 MediaStore / sidecar 文件：不污染相册、不要额外权限。
 *
 * 编辑器用法（E1-1/E1-3 接线，本卡只提供接口）：
 * ```
 * val name = StillSnapshotStore.fileNameOf(context, uri) ?: return
 * val snap = StillSnapshotStore.load(context, name)
 *     ?: StillSnapshot(CameraPresets.standard(), "", 1.0, 0L)  // null = 无记录，回退基线并提示用户
 * ```
 */
object StillSnapshotStore {

    /** prefs 键：所有快照共用一个 JSON 字符串（约 1KB/张，ADR-4：不设上限、不主动清理） */
    const val KEY = "still_snapshots"

    private const val PREFS = "filmkit"

    // 读-改-写必须整体互斥：拍照落盘在 saver 单线程，编辑器读取可能在 UI 线程（ADR-4）
    private val lock = Any()

    /**
     * 落盘**成功后**写一条（读-改-写，保留其它条目）。
     *
     * 任何失败只记日志、绝不外抛 —— 用户红线：不能因为存快照失败让用户丢照片。
     * 由调用方保证在后台线程（saver 队列）执行。
     */
    fun save(context: Context, fileName: String, snap: StillSnapshot) {
        if (fileName.isEmpty()) return
        try {
            synchronized(lock) {
                val prefs = context.getSharedPreferences(PREFS, 0)
                val raw = prefs.getString(KEY, null)
                val root = if (raw.isNullOrBlank()) JSONObject() else try {
                    JSONObject(raw)
                } catch (t: Throwable) {
                    // 已有内容损坏：重建空表继续写 —— 否则从这张起**永远**写不进快照。
                    // 记一条便于事后定位（不静默降级）
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_WARN, "camera", "snapshot.corrupt",
                        kv("bytes" to raw.length, "error" to (t.message ?: t.toString())),
                    )
                    JSONObject()
                }
                root.put(fileName, encodeEntry(snap))
                // 用 commit（而非 apply）：后台线程里能拿到写失败的结果并记日志
                if (!prefs.edit().putString(KEY, root.toString()).commit()) {
                    EngineBridge.nativeAppendLog(
                        EngineBridge.LEVEL_WARN, "camera", "snapshot.save_failed",
                        kv("name" to fileName, "error" to "commit=false"),
                    )
                }
            }
        } catch (t: Throwable) {
            EngineBridge.nativeAppendLog(
                EngineBridge.LEVEL_WARN, "camera", "snapshot.save_failed",
                kv("name" to fileName, "error" to (t.message ?: t.toString())),
            )
        }
    }

    /** 读一张的快照；**null = 这张没有记录**（编辑器回退标准基线并提示用户，ADR-4）。 */
    fun load(context: Context, fileName: String): StillSnapshot? = try {
        synchronized(lock) {
            val raw = context.getSharedPreferences(PREFS, 0).getString(KEY, null)
            val o = if (raw.isNullOrBlank()) null else JSONObject(raw).optJSONObject(fileName)
            // p 缺失/畸形一律当"没有记录"：宁可回退基线，也不给编辑器半份参数
            val p = o?.let { decodeParams(it.optJSONArray("p")?.toString() ?: "") }
            if (o == null || p == null) null
            else StillSnapshot(
                params = p,
                lutId = o.optString("lut", ""),
                lutK = o.optDouble("lutK", 1.0),
                timeMs = o.optLong("t", 0L),
            )
        }
    } catch (t: Throwable) {
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_WARN, "camera", "snapshot.load_failed",
            kv("name" to fileName, "error" to (t.message ?: t.toString())),
        )
        null
    }

    /**
     * URI → 成片文件名（= 快照的键）。编辑器打开成片时先走这一步。
     *
     * 落盘路径本身有 `name` 变量、不用查；但落盘后要反查一次 MediaStore 的真实
     * DISPLAY_NAME —— 同名时系统会自动加 "(1)"，那才是编辑器日后查得到的键。
     */
    fun fileNameOf(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(
            uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null,
        )?.use { c ->
            val i = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i)?.takeIf { it.isNotEmpty() } else null
        }
    } catch (t: Throwable) {
        null // 取不到 = 当作没有快照，编辑器回退基线
    }

    private fun encodeEntry(snap: StillSnapshot): JSONObject = JSONObject().apply {
        // 结构化写入（`JSONArray.put(double)`，Android org.json 有该重载）：不走
        // `JSONArray(String)` 的文本解析路径，没有"数字文本被解析成别的值"的余地
        put("p", JSONArray().apply { sanitizeParams(snap.params).forEach { put(it) } })
        put("lut", snap.lutId)
        put("lutK", snap.lutK)
        put("t", snap.timeMs)
    }

    /**
     * JSON 没有 NaN/Infinity，而 `JSONArray.put(double)` 遇到非有限值会抛 JSONException
     *（整条快照写不进去）⇒ 写入前净化成 0.0。
     *
     * 纯 Kotlin、不依赖 org.json，可 JVM 单测（见 `JsonKv.kt` 顶部）；读取侧的
     * [decodeParams] 同理。生产路径的参数净化与解析就是这两个函数
     *（写入 = sanitizeParams + `JSONArray.put`，读取 = `JSONArray.toString` + decodeParams）。
     */
    internal fun sanitizeParams(p: DoubleArray): DoubleArray = DoubleArray(p.size) { i ->
        val v = p[i]
        if (v.isFinite()) v else 0.0
    }

    /** 解析失败返回 null（调用侧当"没有记录"，不静默给半份参数） */
    internal fun decodeParams(text: String): DoubleArray? {
        val t = text.trim()
        if (t.length < 2 || t.first() != '[' || t.last() != ']') return null
        val body = t.substring(1, t.length - 1).trim()
        if (body.isEmpty()) return DoubleArray(0)
        val parts = body.split(',')
        val out = DoubleArray(parts.size)
        for (i in parts.indices) out[i] = parts[i].trim().toDoubleOrNull() ?: return null
        return out
    }
}
