package com.filmkit.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filmkit.app.EngineBridge
import com.filmkit.app.camera.EditorEngineSession
import com.filmkit.app.camera.OverwriteResult
import com.filmkit.app.camera.StillSnapshot
import com.filmkit.app.camera.StillSnapshotStore
import com.filmkit.app.camera.editorExportName
import com.filmkit.app.camera.editorOpenParams
import com.filmkit.app.camera.encodeEditorJpeg
import com.filmkit.app.camera.overwriteOriginalPhoto
import com.filmkit.app.camera.refreshOverwrittenExif
import com.filmkit.app.camera.saveEditorCopy
import com.filmkit.app.camera.vigFeatherSlot
import com.filmkit.app.camera.vigFeatherUi
import com.filmkit.app.camera.vigRadiusSlot
import com.filmkit.app.camera.vigRadiusUi
import com.filmkit.app.camera.vigStrengthSlot
import com.filmkit.app.camera.vigStrengthUi
import com.filmkit.app.kv
import com.filmkit.app.lut.LutLibrary
import com.filmkit.app.lut.LutPreview
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 内建编辑器（E1-4）：成片再风格化 —— 引擎后段（3D LUT → 颗粒 → TPDF dither）跑在
 * **显示域**，输入就是成片本身的 sRGB 像素（ADR-7：链路里任何地方都不得插入
 * OETF/EOTF；也绝不走 `submitStill`，那会对成片再跑一遍 filmic = 双重影调映射）。
 *
 * 素材分级（ADR-2）：编辑对象是**成片 JPEG**（OETF 之后），只能做后段风格化，
 * 改不了 WB/曝光 —— 底部文案必须把这条讲清楚，否则用户会以为在"重新显影"。
 *
 * 分辨率（E2-8，用户 2026-10-08 拍板）：**预览与导出同为全分辨率**（不再降采样到长边
 * 1600）—— 两者同分辨率、同参数、同 shader ⇒ "预览与导出不一致"这一整类问题从根上消失。
 * 代价是常驻内存从 ~9MB 抬到 ~96MB 级（12MP：输入 direct buffer + 输出位图各 ≈50MB），
 * 所以配套三条纪律：① 滑杆**拖动中不渲染、松手才渲染一次**（[SliderRenderGate]）；
 * ② 换输出位图前先回收旧的那张；③ 解码位图拷进 buffer 后立刻回收（[loadPreview]）。
 *
 * 参数起点（集成点 I-3）：打开时读该片的参数快照（`StillSnapshotStore`）；没有快照
 * 时回退 [CameraPresets.standard] 并在界面上明确提示"已用标准基线"（ADR-4）。
 * **打开时的画面 = 原片本身**（E2-1 架构师拍板）：LUT 强度初值强制 0（旁路），
 * 快照里的 LUT 与强度改为"原片风格"只读展示 + 一键套用，见 [editorOpenParams]。
 *
 * 参数面板（E2-1）：底部操作区上方的**可折叠区**（不是覆盖式浮层）—— 展开时预览区
 * 自动让位，画面完整可见。暗角最明显的地方是四角，浮层挡住底边两点等于让用户盲调。
 * 分组按"完整编辑器"预留（见 [EditorParamGroup]），但**只渲染引擎已实现并验收的组**
 *（ADR-12：不许假滑杆）。本轮 = 色彩（LUT）+ 暗角（[66][67][68]）。
 *
 * ⚠ 编辑器**不写任何拍摄用的 prefs**（`lut_id` / `lut_strength`）：这里的 LUT 选择
 * 只对本次编辑会话有效，退出即丢 —— 否则用户在编辑器里试个滤镜，回到相机页拍照
 * 也被套上（架构师 2026-10-08 明确要求隔离）。下次打开这张照片仍从快照的 LUT 起。
 *
 * @param uri 待编辑成片的 MediaStore URI；为 null（如进程重建后状态丢失）时显示失败态而非白屏。
 * @param onOpenGallery 顶栏最左侧菜单图标 → 相册页（用户报的第 5 点）。素材仍由本页
 *   自己从 [uri] 全尺寸解码，相册网格里的缩略图**不会**流到这里（用户明确禁止）。
 */
@Composable
fun EditorScreen(
    uri: Uri?,
    onBack: () -> Unit = {},
    onOpenGallery: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("filmkit", 0) }
    // 滑杆 → 渲染闸门（E2-8）：拖动中只更新参数与读数，松手才渲染一次
    val gate = remember { SliderRenderGate() }

    // 编辑器自己的渲染线程 + 引擎会话（见 EditorEngineSession 的 KDoc：相机页的
    // 渲染线程此时已随 onDispose 退出，预览实例 g_renderer 必须由编辑器重建）
    val engine = remember { EditorEngineSession() }
    // 导出用独立单线程：onDispose 里 shutdown() 只拒绝新任务，**在飞的导出照常跑完**
    //（用户点完保存顺手返回，不该丢结果）。全分辨率解码/编码/落盘都不占主线程。
    val exportExec = remember {
        Executors.newSingleThreadExecutor { r -> Thread(r, "FilmEditExport") }
    }
    DisposableEffect(Unit) {
        onDispose {
            engine.close()
            exportExec.shutdown()
        }
    }

    var phase by remember { mutableStateOf<EditorPhase>(EditorPhase.Loading) }
    var source by remember { mutableStateOf<EditorSource?>(null) }
    // 当前显示的位图：先显示解码图，首次渲染完成后替换为引擎输出
    var shown by remember { mutableStateOf<Bitmap?>(null) }
    // 按住看原图时显示的输入像素（从常驻 buffer 重建，见 LaunchedEffect(showOriginal)）
    var origBmp by remember { mutableStateOf<Bitmap?>(null) }
    // 参数数组（长度 = CameraPresets.COUNT，编辑器只改得动 [34][66][67][68]；其余槽位原样带着
    // —— 引擎侧只读
    // [26][27][34][60][61][66][67][68]，其余槽位改了也不影响输出，ADR §5.1 I-2 契约）
    var params by remember { mutableStateOf<DoubleArray?>(null) }
    var lutId by remember { mutableStateOf<String?>(null) }   // 本会话的 LUT（不持久化）
    var lutK by remember { mutableFloatStateOf(0f) }          // = params[34]；初值 0 = 与原片一致
    // 快照里的"原片风格"（只读展示 + 一键套用）：不随滑杆变化，故与 lutId/lutK 分开存
    var snapLutId by remember { mutableStateOf<String?>(null) }
    var snapLutK by remember { mutableFloatStateOf(0f) }
    var hasSnapshot by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }     // 按住预览 = 看输入原图
    var lutPreviews by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var exporting by remember { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    // 导出期间被挡下的预览请求（导出独占引擎，见 rerender / finish）
    var pendingRender by remember { mutableStateOf(false) }

    /**
     * 渲染诊断（**单一出口**：分辨率 / 耗时 / 拖动状态 / 暗角三条 / LUT 强度全在这一条里）。
     *
     * 为什么不拆成几个函数各记各的：本项目踩过"字段写进别的函数 ⇒ 真机日志缺项"的坑
     *（ADR §5.4 T-2）—— 一条日志里字段齐全，真机一次运行就能核对全部规格。
     *
     * `dragging` 恒 false 是**回归哨兵**：E2-8 的规格是"拖动中不渲染"，真机日志里若出现
     * dragging=true，说明有人把渲染又接回了 onValueChange。`dragTicks` = 本次渲染之前
     * 拖动中收到的值变更次数（拖动 60 次只产生 1 条渲染日志 = 规格成立）。
     */
    fun logRender(w: Int, h: Int, ms: Long, ticks: Int, ok: Boolean, dragging: Boolean = false) {
        val film = params ?: return
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "app", "editor.render",
            kv(
                "w" to w, "h" to h, "ms" to ms, "dragging" to dragging,
                "dragTicks" to ticks, "ok" to ok, "memMb" to previewResidentMb(w, h),
                "strength" to film[66], "radius" to film[67], "feather" to film[68],
                "lut" to film[34],
            ),
        )
    }

    /**
     * 延迟一帧回收位图：刚换下来的那张可能还在本帧的绘制管线里（硬件加速的纹理上传在
     * RenderThread 上异步进行），当场 recycle 有概率踩崩。等 64ms（≈4 帧）再还，内存上
     * 没有任何区别。scope 随编辑器销毁取消 ⇒ 没来得及回收的那张交给 GC（不漏也不崩）。
     */
    fun recycleLater(bmp: Bitmap) {
        scope.launch {
            delay(64)
            bmp.recycle()
        }
    }

    /**
     * 提交一次预览渲染（**全分辨率**，E2-8）。调用点只有三类：打开时的首帧、离散操作
     *（选 LUT / 套用原片风格）、**滑杆松手**（[onSliderRelease]）—— 拖动中一律不调。
     */
    fun rerender() {
        val src = source ?: return
        val film = params ?: return
        // ★ 导出期间**不再发预览请求**（架构师要求落实到这一侧）：预览与导出现在同为
        // 全分辨率（纹理尺寸一致，不再反复重建），但导出必须独占 g_renderer ——
        // 让预览插进去排队只会把导出拖长。被挡下的这一次记成 pending：导出收尾时
        //（finish）补渲染一次，否则用户在导出中拖完滑杆松手，看到的还是导出前那张
        //（参数变了、画面不变 = 假滑杆，ADR-12 明确禁止）。
        if (exporting) {
            pendingRender = true
            return
        }
        pendingRender = false
        val ticks = gate.takeTicks()
        val t0 = System.currentTimeMillis()
        engine.requestPreview(src.buffer, src.w, src.h, film.copyOf()) { out ->
            logRender(src.w, src.h, System.currentTimeMillis() - t0, ticks, out != null)
            if (out != null) {
                val old = shown
                shown = out
                // 输入侧两张显示位图（首帧那张解码位图 / 按住看原图重建的那张）到此作废：
                // 引用先断（Image 改用输出图），内存延迟一帧再还。
                // 连同上一张输出一起回收 —— 全分辨率下每张都是 50MB，一张都不能攒
                //（distinct = 同一张只回收一次，Bitmap 没有重写 equals ⇒ 就是同一性去重）
                origBmp = null
                (listOfNotNull(old) + src.takeDisplayBitmaps()).distinct()
                    .forEach { recycleLater(it) }
                status = ""
            } else {
                status = "渲染失败，显示原图"
            }
        }
    }

    /** 滑杆松手（E2-8）：这次拖动真的改过值才渲染一次 —— 只点一下滑杆不白跑一次全分辨率 */
    fun onSliderRelease() {
        if (gate.onFinished()) rerender()
    }

    /**
     * 下发 LUT 资产（null = 关闭）与强度，然后重渲染。两个入口共用：手动选卡片、
     * 「套用原片风格」。解析在 IO 线程（读 .cube 字节），上传与重渲染在渲染线程。
     */
    fun applyLut(entry: LutLibrary.Entry?, k: Float) {
        val film = params ?: return
        if (entry == null) {
            lutId = null
            lutK = 0f
            film[34] = 0.0                    // 关闭 = 强度 0（引擎 uLutOn = 0 整段旁路）
            engine.setLut(null)
            rerender()
            return
        }
        scope.launch {
            val lut = withContext(Dispatchers.IO) { LutLibrary.load(context, entry) }
            if (lut == null) {
                status = "LUT 解析失败，已保持原状"
                return@launch
            }
            lutId = entry.id
            lutK = k
            film[34] = k.toDouble()
            engine.setLut(lut)
            rerender()
        }
    }

    /**
     * 选卡片。强度沿用当前值；**当前是 0（编辑器初值）时给到 1.0 = 100%** ——
     * 否则用户"选了 LUT 却什么都没发生"（ADR-12 的假滑杆精神：不许让操作看起来无效）。
     */
    fun pickLut(entry: LutLibrary.Entry?) {
        if (entry == null) {
            applyLut(null, 0f)
            return
        }
        applyLut(entry, if (lutK > 0f) lutK else 1f)
    }

    /**
     * 一键套用原片风格：把快照里的 LUT + 强度原样下发（等价于手动把强度拖到那个值）。
     * 资产在打开编辑器时已上传过，但用户可能已切到别的 LUT/关闭 ⇒ 这里重新走一遍
     * [applyLut]（重复上传一次 3D 纹理，代价是一次 IO + 一次纹理上传，换"任何状态下都正确"）。
     */
    fun applySnapshotStyle() {
        val id = snapLutId ?: return
        if (lutId == id && lutK == snapLutK) return   // 已经一致：不重复上传/渲染
        val entry = LutLibrary.byId(context, id)
        if (entry == null) {
            status = "原片用的 LUT 已不在库中"
            return
        }
        applyLut(entry, snapLutK)
    }

    // ── 暗角三条滑杆（E2-1 / E2-8）：只改同一份 params 的 [66][67][68] ⇒ 预览与导出天然一致 ──
    // E2-8（用户 2026-10-08 拍板）：拖动中**只更新参数与滑杆读数、一次都不渲染**（全分辨率
    // 一次约百毫秒，每帧渲染 = 画面抖动 + 反复分配 50MB 位图）；松手走 [onSliderRelease]
    // 渲染一次。滑杆读数由 StrengthRow 自己持有 state，不依赖这里触发重组。
    fun setVigStrength(ui: Float) {
        params?.let { it[66] = vigStrengthSlot(ui) }
        gate.onValueChange()
    }

    fun setVigRadius(ui: Float) {
        params?.let { it[67] = vigRadiusSlot(ui) }
        gate.onValueChange()
    }

    fun setVigFeather(ui: Float) {
        params?.let { it[68] = vigFeatherSlot(ui) }
        gate.onValueChange()
    }

    /** 导出收尾：状态改动一律回主线程（导出线程 / 渲染线程都会调到这里） */
    fun finish(msg: String) {
        scope.launch {
            exporting = false
            status = msg
            // 导出期间被挡下的预览请求在这里补渲染一次：否则用户在导出中拖完滑杆松手，
            // 画面会停在旧参数上（滑杆读数变了、画面没变 = 假滑杆）
            if (pendingRender) rerender()
        }
    }

    /** 编码 + 落盘（导出线程）。任何失败都必须让用户能分辨"原片有没有被动过"。 */
    fun saveResult(
        out: Bitmap,
        outName: String,
        snapshot: DoubleArray,
        now: Long,
        overwrite: Boolean,
        target: Uri,
        origBytes: ByteArray?,
    ) {
        val jpeg = encodeEditorJpeg(out, prefs.getInt("jpeg_quality", 100))
        if (jpeg == null || jpeg.isEmpty()) {
            finish("编码失败，未做任何改动")
            return
        }
        if (overwrite) {
            val res = overwriteOriginalPhoto(context, target, jpeg, origBytes)
            when (res) {
                OverwriteResult.Ok -> {
                    // 像素变了，拍摄信息（ISO/机型/光圈）与方向补回来
                    refreshOverwrittenExif(context, target, now, origBytes)
                    // 快照更新为这次编辑的参数：下次从编辑器打开即是"上一次的样子"
                    StillSnapshotStore.save(
                        context,
                        StillSnapshotStore.fileNameOf(context, target) ?: source?.name.orEmpty(),
                        StillSnapshot(snapshot, lutId.orEmpty(), lutK.toDouble(), now),
                    )
                    finish("已覆盖原片")
                }
                OverwriteResult.FailedOriginalIntact -> finish("覆盖失败，原片未改动")
                OverwriteResult.FailedOriginalDamaged ->
                    finish("覆盖失败，原片可能已损坏——请在相册确认")
            }
        } else {
            val saved = saveEditorCopy(context, jpeg, outName, now)
            if (saved == null) {
                finish("保存失败，原片未改动")
            } else {
                // 新片也留一份参数快照：下次编辑它时起点就是这次的参数（E1-2 的落盘侧口径）
                StillSnapshotStore.save(
                    context,
                    StillSnapshotStore.fileNameOf(context, saved) ?: outName,
                    StillSnapshot(snapshot, lutId.orEmpty(), lutK.toDouble(), now),
                )
                finish("已另存为 $outName")
            }
        }
    }

    /**
     * 导出（ADR-3）：[overwrite] = false 另存新片（默认，原片不动）；
     * true = 覆盖原片（必须先渲染+编码成功，见 [overwriteOriginalPhoto] 的安全顺序）。
     */
    fun exportNow(overwrite: Boolean) {
        val src = source ?: return
        val snapshot = params?.copyOf() ?: return
        val target = uri
        if (exporting || target == null) return
        exporting = true
        status = "正在生成全分辨率成片…"
        val now = System.currentTimeMillis()
        val outName = editorExportName(now)
        exportExec.execute {
            // ① 覆盖要用的原片字节：读**一次**，既作回滚备份又作 EXIF 来源
            val origBytes = if (overwrite) readImageBytes(context, target) else null
            // ② 全分辨率解码（E2-8 起预览也是全分辨率，但这里仍**独立**解一份：导出期间
            //    两条链路的位图生命周期必须解耦）。⚠ 峰值内存 = 预览 buffer + 预览输出 +
            //    这份解码位图 + 导出 buffer + 导出输出 ≈ 5×48MiB ≈ 240MiB —— 12MP 设备上
            //    已接近堆上限；后续卡若改成复用 src.buffer（预览与导出同分辨率同像素），
            //    可直接省掉两份 48MiB
            val full = decodeFull(context, target)
            if (full == null) {
                finish("原图解码失败，未做任何改动")
                return@execute
            }
            val w = full.width
            val h = full.height
            // ③ 像素拷进 direct buffer 后立刻 recycle 输入位图：50MB 越早还回越好
            val buf = try {
                ByteBuffer.allocateDirect(w * h * 4).also { full.copyPixelsToBuffer(it) }
            } catch (t: Throwable) {
                full.recycle()
                finish("图片过大，无法生成")
                return@execute
            }
            full.recycle()
            buf.rewind()
            // ④ 渲染（渲染线程）→ 主线程回调 → 回导出线程编码落盘
            engine.requestFull(buf, w, h, snapshot) { out ->
                if (out == null) {
                    finish("渲染失败，未做任何改动")
                    return@requestFull
                }
                exportExec.execute {
                    try {
                        saveResult(out, outName, snapshot, now, overwrite, target, origBytes)
                    } finally {
                        out.recycle()
                    }
                }
            }
        }
    }

    // 全分辨率解码一次（E2-8）：key 只有 uri —— 转屏不再整张重解（旧 key 里带着按屏幕
    // 尺寸算出的 previewLong，转屏 = 再解一次 12MP）。
    LaunchedEffect(uri) {
        phase = EditorPhase.Loading
        status = "正在读取成片…"
        val res = withContext(Dispatchers.IO) { loadPreview(context, uri) }
        when (res) {
            is LoadResult.Err -> {
                phase = EditorPhase.Failed(res.reason)
                return@LaunchedEffect
            }
            is LoadResult.Ok -> {
                val src = res.src
                source = src
                // 首帧画面 = 输入本身（打开时 [34] 等参数就是"原片参数"，输出 ≈ 输入）：
                // 解码那张位图还在就直接显示（零成本），首次渲染成功后才还回内存
                shown = src.displayBitmap()
                phase = EditorPhase.Ready
                status = "正在渲染…"
                // 参数起点 + 初始化引擎 + 上传 LUT 资产，全在 IO 线程做完再回主线程
                val init = withContext(Dispatchers.IO) {
                    // I-3：快照存在 ⇒ 用这张片子当初的参数；不存在 ⇒ 标准基线（ADR-4）
                    val snap = StillSnapshotStore.load(context, src.name)
                    // ★ 打开编辑器 = 原片本身：[34] 强制 0（引擎侧 LUT 整段旁路）。
                    // 快照里的 LUT 与强度不丢 —— 另存为"原片风格"，由面板给一键套用
                    //（E2-1 架构师拍板，理由见 editorOpenParams 的 KDoc）。
                    val film = editorOpenParams(snap)
                    val snapId = snap?.lutId?.takeIf { it.isNotEmpty() }
                    val entry = snapId?.let { id -> LutLibrary.byId(context, id) }
                    val lut = entry?.let { LutLibrary.load(context, it) }
                    val started = engine.start()
                    // 引擎的 LUT 是进程级状态，可能残留着拍摄时的那一套：按快照决定去留。
                    // 资产先传上去（此时强度是 0，仍不生效）⇒ 点「套用原片风格」只需改 [34]。
                    engine.setLut(lut)
                    EditorInit(
                        film = film,
                        hasSnapshot = snap != null,
                        // 资产加载失败时不算"选中"：否则 UI 显示选中、引擎里却没有这个 LUT
                        lutId = if (lut != null) entry.id else null,
                        snapLutId = snapId,
                        snapLutK = (snap?.lutK ?: 0.0).toFloat(),
                        engineStarted = started,
                    )
                }
                params = init.film
                hasSnapshot = init.hasSnapshot
                lutId = init.lutId
                snapLutId = init.snapLutId
                snapLutK = init.snapLutK
                lutK = init.film[34].toFloat()   // 恒 0（打开时与原片逐像素一致）
                if (!init.engineStarted) {
                    status = "引擎不可用，显示原图"
                    return@LaunchedEffect
                }
                status = ""
                rerender()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(FilmTheme.Body),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            SubPageTopBar("编辑", onBack, onMenu = onOpenGallery)

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                when (val p = phase) {
                    EditorPhase.Loading -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = FilmTheme.Accent)
                            Spacer(Modifier.height(12.dp))
                            Text("正在读取成片…", color = FilmTheme.CreamDim, fontSize = 13.sp)
                        }
                    }
                    is EditorPhase.Failed -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(p.reason, color = FilmTheme.Cream, fontSize = 14.sp)
                            Text("可返回相机页重新拍摄", color = FilmTheme.CreamDim, fontSize = 12.sp)
                        }
                    }
                    EditorPhase.Ready -> {
                        val bmp = shown
                        // 按住看原图：显示输入像素 —— 由 LaunchedEffect(showOriginal) 在 IO
                        // 线程从常驻 buffer 重建（见 EditorSource.displayBitmap），主线程零成本；
                        // 还没就绪时退回显示成片，绝不白屏
                        val orig = if (showOriginal) origBmp else null
                        if (bmp != null) {
                            // 按住画面 = 看输入原图（before/after）。用 onPress + tryAwaitRelease
                            // 而非 detectTapGestures(onLongPress)：按下即见、松手即回，不必等长按
                            // 延时；预览区没有别的手势（pressScale 是给按钮的），不会冲突。
                            val image = remember(bmp, orig, showOriginal) {
                                (if (showOriginal && orig != null) orig else bmp).asImageBitmap()
                            }
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Image(
                                    bitmap = image,
                                    contentDescription = if (showOriginal) "原图" else "成片预览",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(bmp) {
                                            detectTapGestures(onPress = {
                                                showOriginal = true
                                                tryAwaitRelease()
                                                showOriginal = false
                                            })
                                        },
                                )
                                // 提示只在参数面板展开时给：用户正在调参时才需要 before/after
                                if (panelOpen && !showOriginal) {
                                    Text(
                                        "按住画面看原图",
                                        color = FilmTheme.CreamDim,
                                        fontSize = 10.sp,
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── 参数面板（E2-1）：嵌在预览与操作区之间，**不是覆盖式浮层** ——
            // 展开时预览区（上面的 weight(1f)）自动让位，画面完整可见。暗角最明显的地方
            // 是四角，用底部浮层挡住底边两点等于让用户盲调（这是不沿用 LutSheet 形态的原因）。
            AnimatedVisibility(
                visible = panelOpen,
                enter = FilmMotion.panelEnter,
                exit = FilmMotion.panelExit,
            ) {
                EditorParamsPanel(
                    lutId = lutId,
                    lutK = lutK,
                    previews = lutPreviews,
                    snapLutId = snapLutId,
                    snapLutK = snapLutK,
                    vigStrength = params?.let { vigStrengthUi(it[66]) } ?: 0f,
                    vigRadius = params?.let { vigRadiusUi(it[67]) } ?: 0.8f,
                    vigFeather = params?.let { vigFeatherUi(it[68]) } ?: 0.5f,
                    onLutStrength = { v ->
                        lutK = v
                        params?.let { it[34] = v.toDouble() }
                        gate.onValueChange()   // E2-8：拖动中只更新读数，松手才渲染
                    },
                    onLutStrengthFinished = { onSliderRelease() },
                    onPickLut = { e -> pickLut(e) },
                    onApplySnapshot = { applySnapshotStyle() },
                    onVigStrength = { v -> setVigStrength(v) },
                    onVigRadius = { v -> setVigRadius(v) },
                    onVigFeather = { v -> setVigFeather(v) },
                    onVigFinished = { onSliderRelease() },
                    onClose = { panelOpen = false },
                )
            }

            // ── 底部：读数 + 参数来源提示 + 操作按钮 ──
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(FilmTheme.PanelSolid)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                val src = source
                Text(
                    text = src?.let { "${it.name} · ${it.srcW} × ${it.srcH}" } ?: "—",
                    color = FilmTheme.Cream,
                    fontFamily = FilmTheme.Mono,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = editorHint(
                        status = status,
                        hasSnapshot = hasSnapshot,
                        lutLabel = lutId?.let { id -> LutLibrary.byId(context, id)?.label ?: id },
                        lutK = lutK,
                    ),
                    color = FilmTheme.CreamDim,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { panelOpen = !panelOpen },
                        enabled = !exporting && phase == EditorPhase.Ready,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (panelOpen) FilmTheme.Accent else FilmTheme.Cream,
                            disabledContentColor = FilmTheme.CreamDim,
                        ),
                    ) {
                        Text("参数", fontFamily = FilmTheme.Mono, fontSize = 12.sp)
                    }
                    Button(
                        onClick = { exportNow(false) },
                        enabled = !exporting && phase == EditorPhase.Ready,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = FilmTheme.Accent,
                            contentColor = FilmTheme.OnAccent,
                            disabledContainerColor = FilmTheme.ChipBg,
                            disabledContentColor = FilmTheme.CreamDim,
                        ),
                    ) {
                        Text("另存为新片", fontFamily = FilmTheme.Mono, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { confirmOverwrite = true },
                        enabled = !exporting && phase == EditorPhase.Ready,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = FilmTheme.Cream,
                            disabledContentColor = FilmTheme.CreamDim,
                        ),
                    ) {
                        Text("覆盖原片", fontFamily = FilmTheme.Mono, fontSize = 12.sp)
                    }
                    if (exporting) {
                        Spacer(Modifier.width(4.dp))
                        CircularProgressIndicator(
                            color = FilmTheme.Accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.width(18.dp).height(18.dp),
                        )
                    }
                }
            }
        }

        if (confirmOverwrite) {
            AlertDialog(
                onDismissRequest = { confirmOverwrite = false },
                shape = RoundedCornerShape(18.dp),
                title = { Text("覆盖原片？", color = FilmTheme.Cream, fontFamily = FilmTheme.Serif) },
                text = {
                    Text(
                        "会用当前编辑结果替换这张原片，原图无法恢复。\n想保留原片请改用「另存为新片」。",
                        color = FilmTheme.CreamDim,
                        fontSize = 13.sp,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmOverwrite = false
                        exportNow(true)
                    }) { Text("覆盖", color = FilmTheme.Accent, fontFamily = FilmTheme.Mono) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmOverwrite = false }) {
                        Text("取消", color = FilmTheme.CreamDim, fontFamily = FilmTheme.Mono)
                    }
                },
            )
        }
    }

    // 按住看原图：在 IO 线程从常驻 buffer 重建输入像素（12MP ≈20~40ms；解码那张位图若还在
    // 则零成本直接命中）。松手即断引用并回收 —— 不留给下一次按住，也不让它跟输出位图一起
    // 常驻（全分辨率下每个 50MB 都要算清楚）。
    // key 只用 showOriginal：把 source 也放进 key 会在解码完成的瞬间误触发一次回收，
    // 而那一刻 shown 正指向那张解码位图（首帧还没渲染回来）。
    LaunchedEffect(showOriginal) {
        val src = source ?: return@LaunchedEffect
        if (showOriginal) {
            origBmp = withContext(Dispatchers.IO) { src.displayBitmap() }
        } else {
            origBmp = null
            // 只还回"按需重建"的那张：它从不作为 shown 显示，回收是安全的。
            // （解码那张归 [EditorSource.takeDisplayBitmaps] 管 —— 首帧渲染成功时统一还）
            src.dropRebuilt()?.let { recycleLater(it) }
        }
    }

    // 面板首次打开时才生成 LUT 卡片预览（复用拍摄页的一次性缓存，见 LutPreview）
    LaunchedEffect(panelOpen) {
        if (!panelOpen || lutPreviews.isNotEmpty()) return@LaunchedEffect
        lutPreviews = withContext(Dispatchers.IO) {
            LutLibrary.entries(context).mapNotNull { e ->
                LutLibrary.load(context, e)?.let { lut ->
                    LutPreview.load(context, e.id, lut)?.let { bmp -> e.id to bmp }
                }
            }.toMap()
        }
    }
}

/**
 * 底部提示文案（ADR-4 的无快照提示 + 当前 LUT 状态）。
 *
 * E2-1 起语义变了：打开编辑器时 LUT 强度是 0（画面 = 原片），所以这里描述的是
 * **当前状态**，不再解释"叠加在原片已有的风格之上"那种别扭的语义。
 */
private fun editorHint(status: String, hasSnapshot: Boolean, lutLabel: String?, lutK: Float): String = when {
    status.isNotEmpty() -> status
    !hasSnapshot -> "这张没有参数记录，已用标准基线"
    lutLabel == null -> "当前：无 LUT（与原片一致）"
    lutK <= 0f -> "当前：$lutLabel · 0%（与原片一致）"
    else -> "当前：$lutLabel · ${(lutK * 100).roundToInt()}%（叠加在原片之上）"
}

/** LaunchedEffect 里初始化结果的打包（避免在协程里零散赋值） */
private class EditorInit(
    val film: DoubleArray,
    val hasSnapshot: Boolean,
    val lutId: String?,
    /** 快照里**原片**用的 LUT id（≠ [lutId]：用户可能已改选或关闭；null = 原片没记 LUT） */
    val snapLutId: String?,
    val snapLutK: Float,
    val engineStarted: Boolean,
)

/**
 * 参数面板的分组（E2-1）。架构师 2026-10-08 拍板：**按"完整编辑器"预留分组**，
 * 但只渲染 [implemented] = true 的组。
 *
 * [implemented] 的含义是"引擎已实现且已验收"（ADR-12 硬约束：**不许假滑杆**）——
 * 用户滑了没反应比没有滑杆更糟。历史事故：[4] contrast、[11..19] cross 曾是 shader
 * 零引用的死参数，照调研文档直接做 UI 的结果就是一排滑了没反应的滑杆。
 *
 * 加新组 = 加一个枚举项 + 在 [EditorParamsPanel] 的 when 里加一个分支（数据驱动列表，
 * 组多到一屏放不下时靠面板自身的纵向滚动兜住，不需要改结构）。
 */
private enum class EditorParamGroup(val title: String, val implemented: Boolean) {
    // TODO(E2-2 基础调整)：曝光 / 白平衡 / 对比度 / 饱和度 / 清晰度。E2-2 正在扩契约
    //  （`FilmParams.h` 的 [78] clarity 等）并在 `kFsStyle` 内实现 —— 等它的滑杆落地后
    //  把本项置 true、在 [EditorParamsPanel] 里加分支。曝光与 WB 要走"真 sRGB 解码 →
    //  线性域增益 → 真 sRGB 编码"的往返，对比度/饱和度/清晰度是显示域操作。
    BASIC("基础", false),
    COLOR("色彩", true),
    VIGNETTE("暗角", true),
    // TODO(E4-3 颗粒)：[26] 强度 / [27] ISO / [60] 密度偏置 / [61] 枢轴。E4-3 验收后置 true。
    GRAIN("颗粒", false),
    // TODO(E4-5 光晕)：[62] 强度 / [63] 阈值 / [64] 半径 / [65] 暖度。E4-5 验收后置 true。
    HALATION("光晕", false),
    // 注：E4-4 趾肩（[5..10] latitude）在架构师给的 5 组里没有归口（它是影调/风格层参数）——
    // 等 E4-4 验收后再决定进"基础"还是单开一组，本轮不猜。
}

/**
 * 参数面板（E2-1）：**可折叠区**，嵌在预览区与底部操作区之间（不是覆盖式浮层，
 * 见调用点注释：浮层会挡住画面底部两角，正好是看暗角的位置）。
 *
 * 高度 = 内容自适应、上限屏高 45% + 纵向滚动：组变多也不会把预览区挤没。
 * 分组标题复用设置页的 [SectionHeader]（全 App 统一形态）。
 */
@Composable
private fun EditorParamsPanel(
    lutId: String?,
    lutK: Float,
    previews: Map<String, Bitmap>,
    snapLutId: String?,
    snapLutK: Float,
    vigStrength: Float,
    vigRadius: Float,
    vigFeather: Float,
    onLutStrength: (Float) -> Unit,
    /** LUT 强度滑杆**松手**（E2-8：拖动中不渲染，松手渲染一次） */
    onLutStrengthFinished: () -> Unit,
    onPickLut: (LutLibrary.Entry?) -> Unit,
    onApplySnapshot: () -> Unit,
    onVigStrength: (Float) -> Unit,
    onVigRadius: (Float) -> Unit,
    onVigFeather: (Float) -> Unit,
    onVigFinished: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(FilmTheme.PanelSolid)
            .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.45f).dp)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "参数",
                color = FilmTheme.Cream,
                fontSize = 17.sp,
                fontFamily = FilmTheme.Serif,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "收起",
                color = FilmTheme.CreamDim,
                fontSize = 12.sp,
                fontFamily = FilmTheme.Mono,
                modifier = Modifier
                    .pressScale(onTap = onClose)
                    .padding(6.dp),
            )
        }
        // 只渲染引擎已实现并验收的组（ADR-12）；未实现的组连标题都不给 —— 空标题会让
        // 用户以为"面板坏了"，而底部那行"下一版支持"已经把预期讲清楚了。
        EditorParamGroup.entries.filter { it.implemented }.forEach { g ->
            SectionHeader(g.title)
            when (g) {
                EditorParamGroup.COLOR -> EditorColorGroup(
                    lutId = lutId,
                    lutK = lutK,
                    previews = previews,
                    snapLutId = snapLutId,
                    snapLutK = snapLutK,
                    onStrength = onLutStrength,
                    onStrengthFinished = onLutStrengthFinished,
                    onPick = onPickLut,
                    onApplySnapshot = onApplySnapshot,
                )
                EditorParamGroup.VIGNETTE -> EditorVignetteGroup(
                    strength = vigStrength,
                    radius = vigRadius,
                    feather = vigFeather,
                    onStrength = onVigStrength,
                    onRadius = onVigRadius,
                    onFeather = onVigFeather,
                    onFinished = onVigFinished,
                )
                // 未实现的组在上面已过滤掉；这里仅为 when 的穷尽性兜底
                else -> Unit
            }
        }
        Text(
            "基础（曝光 / 白平衡 / 对比度…）下一版支持",
            color = FilmTheme.CreamDim,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

/**
 * 色彩组：LUT 选择 + 强度 + **原片风格**（参数快照记录的 LUT 与强度，只读）与一键套用。
 *
 * 为什么必须显示"原片风格"：打开编辑器时 [34] 被强制 0（画面 = 原片），若不给用户一个
 * 入口把当初的风格套回来，快照里的 LUT 信息就等于白存了。
 */
@Composable
private fun EditorColorGroup(
    lutId: String?,
    lutK: Float,
    previews: Map<String, Bitmap>,
    snapLutId: String?,
    snapLutK: Float,
    onStrength: (Float) -> Unit,
    onStrengthFinished: () -> Unit,
    onPick: (LutLibrary.Entry?) -> Unit,
    onApplySnapshot: () -> Unit,
) {
    val context = LocalContext.current
    // 快照里的 LUT 可能已被用户从 LUT 库删掉（byId = null）：如实说明"已不在库中"，
    // 并且**不给**套用按钮（点了注定失败的操作比没有按钮更糟）
    val snapEntry = snapLutId?.let { LutLibrary.byId(context, it) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                snapLutId == null -> "原片未使用 LUT"
                snapEntry == null -> "原片风格：$snapLutId（已不在库中）"
                // 强度 0 = 当初就没叠加（编辑器导出的新片会落在这一档）：说"未叠加"比说 "0%" 清楚
                snapLutK <= 0f -> "原片风格：${snapEntry.label}（未叠加）"
                else -> "原片风格：${snapEntry.label} · ${(snapLutK * 100).roundToInt()}%"
            },
            color = FilmTheme.CreamDim,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (snapEntry != null) {
            Text(
                "套用",
                color = FilmTheme.Accent,
                fontSize = 12.sp,
                fontFamily = FilmTheme.Mono,
                modifier = Modifier
                    .pressScale(onTap = onApplySnapshot)
                    .padding(6.dp),
            )
        }
    }
    // 强度只对已选中的 LUT 有意义；0% = 与原片一致（引擎侧 [34] = 0 整段旁路）
    // 松手（onFinished）才渲染：E2-8 —— 拖动中只更新 [34] 与读数
    if (lutId != null) {
        StrengthRow(initial = lutK, onCommit = onStrength, onFinished = { onStrengthFinished() })
    }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        item {
            LutCard(label = "无", selected = lutId == null, preview = null) { onPick(null) }
        }
        items(LutLibrary.entries(context)) { e ->
            LutCard(
                label = e.label,
                selected = lutId == e.id,
                preview = previews[e.id],
            ) { onPick(e) }
        }
    }
}

/**
 * 暗角组（E2-1 本轮核心）：强度 [66] / 半径 [67] / 羽化 [68]。
 *
 * 三条滑杆的初值都来自同一份 `params`（即参数快照 → `editorParams` 的结果，
 * 无快照时 = `CameraPresets.standard()` 的 0 / 80 / 50），映射见 `EditorExport.kt`
 * 里的 `vigStrengthUi/Slot` 等函数。
 *
 * 量程与读数：强度 0..100%（0 = 关，引擎侧 [66] = 0 整段跳过）；半径/羽化 0..200%
 *（darktable `scale` / `falloff_scale` 本身就是百分比语义，默认 80 / 50）。
 *
 * E2-8：三条滑杆**拖动中只更新参数与读数、一次都不渲染**，松手（[StrengthRow] 的
 * `onFinished` = RelativeSlider 的 onDragEnd）才渲染一次 —— 接线见 EditorScreen 的
 * [SliderRenderGate] 与 `onSliderRelease`。所以 `onStrength`/`onRadius`/`onFeather` 是
 * "拖动中"回调，`onFinished` 是"松手"回调，两者语义不可互换。
 */
@Composable
private fun EditorVignetteGroup(
    strength: Float,
    radius: Float,
    feather: Float,
    onStrength: (Float) -> Unit,
    onRadius: (Float) -> Unit,
    onFeather: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    Text(
        "强度 0% = 关闭 · 半径越大暗角越靠外 · 羽化越大过渡越柔",
        color = FilmTheme.CreamDim,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 2.dp),
    )
    StrengthRow(
        initial = strength,
        onCommit = onStrength,
        onFinished = { onFinished() },
        label = "强度",
        range = 0f..1f,
    )
    StrengthRow(
        initial = radius,
        onCommit = onRadius,
        onFinished = { onFinished() },
        label = "半径",
        range = 0f..2f,
    )
    StrengthRow(
        initial = feather,
        onCommit = onFeather,
        onFinished = { onFinished() },
        label = "羽化",
        range = 0f..2f,
    )
}

/**
 * 滑杆 → 渲染的触发闸门（E2-8，用户 2026-10-08 拍板："可以拖完松手再渲染，不用实时渲染"）。
 *
 * 纯逻辑、不依赖 Android ⇒ JVM 单测能守住本卡最容易错的一条：**松手必须渲染**
 *（拖动中漏渲染是规格本身，松手漏渲染 = 用户调完参数画面纹丝不动，属于致命 bug）。
 *
 * 为什么还要判"这次拖动改过值"：RelativeSlider 按下不改值（相对拖动），用户点一下滑杆
 * 再松手时值根本没变 ⇒ 没必要白跑一次全分辨率渲染（≈百毫秒 + 两张 50MB 位图）。
 */
internal class SliderRenderGate {
    private var dirty = false
    private var ticks = 0

    /** 拖动中（onValueChange）：只记状态 —— 调用方**不要**在这里渲染 */
    fun onValueChange() {
        dirty = true
        ticks++
    }

    /** 松手（onValueChangeFinished）：这次拖动真的改过值才返回 true（= 该渲染一次） */
    fun onFinished(): Boolean {
        val render = dirty
        dirty = false
        return render
    }

    /** 取走并清零"自上次渲染以来拖动中收到的值变更次数"（诊断字段 dragTicks） */
    fun takeTicks(): Int {
        val n = ticks
        ticks = 0
        return n
    }
}

/**
 * 全分辨率预览的常驻内存估算（MB，纯函数）：输入 direct buffer + 输出位图各 4 字节/像素
 *（RGBA8）。12MP（4080×3072）≈ 96MB —— 这就是 E2-8 把预览改成全分辨率后必须盯住的量级
 *（首帧那一刻还要多一张解码位图，见 [EditorSource]）。向上取整：估算宁可高估不可低估。
 */
internal fun previewResidentMb(w: Int, h: Int): Int =
    ((4L * w * h * 2 + (1024 * 1024 - 1)) / (1024 * 1024)).toInt()

/** 编辑器页面状态：三态显式建模，避免"位图与错误同时非空"这类非法组合 */
private sealed interface EditorPhase {
    data object Loading : EditorPhase
    data object Ready : EditorPhase
    data class Failed(val reason: String) : EditorPhase
}

/**
 * 编辑素材（E2-8：**全分辨率**）。
 *
 * 像素只留**一份常驻**：[buffer] = 解码像素的 direct 副本（渲染输入，也是"看原图"的唯一
 * 像素来源）。解码那张位图（[decoded]）只为首帧显示而活，首次渲染成功即由
 * [takeDisplayBitmaps] 取走回收。
 *
 * 内存账（12MP = 4080×3072，见 [previewResidentMb]）：buffer ≈50MB + 输出位图 ≈50MB
 * ⇒ 常驻 ≈96MB；首帧渲染那一刻再多一张解码位图（≈150MB 峰值，之后回落）。
 *
 * [srcW]/[srcH] 是**原图**尺寸（底部读数用）；[w]/[h] 是实际解码尺寸 —— 正常两者相等，
 * 只有解码 OOM 降档后 w/h 才小于原图（宁可糊也不崩）。
 */
private class EditorSource(
    /** 渲染输入：解码像素的 direct 副本（RGBA_8888 的内存布局即 R,G,B,A，与引擎约定同序） */
    val buffer: ByteBuffer,
    val w: Int,
    val h: Int,
    val name: String,
    val srcW: Int,
    val srcH: Int,
) {
    /** 解码位图：首帧画面（也是渲染失败 / 引擎不可用时的兜底画面） */
    var decoded: Bitmap? = null

    /** "按住看原图"按需重建的位图（解码那张已回收后才会出现）。跨线程读写 ⇒ Volatile */
    @Volatile
    private var rebuilt: Bitmap? = null
    private var dead = false

    /**
     * 输入像素的显示位图：解码那张还在就直接用（零成本），否则从常驻 [buffer] 重建一份并
     * 缓存。调用方应在 IO 线程调用（12MP 重建 ≈20~40ms）。失败（OOM）返回 null 且不再重试。
     */
    fun displayBitmap(): Bitmap? {
        decoded?.let { return it }
        rebuilt?.let { return it }
        if (dead) return null
        return try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(buffer.duplicate().apply { rewind() })
            }.also { rebuilt = it }
        } catch (t: Throwable) {
            dead = true
            null
        }
    }

    /** 取走输入侧全部显示位图（调用方负责回收）。首次成功渲染后调 —— 此时画面已是引擎输出 */
    fun takeDisplayBitmaps(): List<Bitmap> {
        val out = listOfNotNull(decoded, rebuilt)
        decoded = null
        rebuilt = null
        return out
    }

    /** 取走"按需重建"那张（松开"看原图"时调）：它从不作为主画面显示，回收是安全的 */
    fun dropRebuilt(): Bitmap? = rebuilt?.also { rebuilt = null }
}

private sealed interface LoadResult {
    class Ok(val src: EditorSource) : LoadResult
    class Err(val reason: String) : LoadResult
}

/**
 * 读图（IO 线程）：**全分辨率解码**（E2-8 用户 2026-10-08 拍板：预览与导出同分辨率，
 * 见文件头 KDoc）。不再按屏幕尺寸降采样，只保留 OOM 兜底 —— 解不出来（超大图 / 低内存
 * 设备）就**降一档重试一次**（inSampleSize ×2 = 长边减半，宁可糊也不崩），仍失败才报错。
 * 尺寸预读（inJustDecodeBounds）只为拿**原图**尺寸做底部读数。
 *
 * 解码位图同时是首帧画面（[EditorSource.decoded]），首次渲染成功后由 rerender 回收 ——
 * 首帧总得显示点什么，这张并不额外抬高常驻峰值。
 */
private fun loadPreview(context: Context, uri: Uri?): LoadResult {
    if (uri == null) return LoadResult.Err("没有可编辑的成片")
    val resolver = context.contentResolver

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    } catch (_: Throwable) {
        return LoadResult.Err("图片已失效或无法读取")
    }
    val srcW = bounds.outWidth
    val srcH = bounds.outHeight
    if (srcW <= 0 || srcH <= 0) return LoadResult.Err("图片已失效或无法读取")

    // 文件名一律走 StillSnapshotStore.fileNameOf（ADR 集成点 I-1）：参数快照的键就是它，
    // 两处各写一份实现，一旦行为不一致，编辑器查到的键就不是落盘时那个（静默取不到快照）。
    // 查不到时的兜底保留原样（URI 末段 → "成片"）。
    val name = StillSnapshotStore.fileNameOf(context, uri) ?: uri.lastPathSegment ?: "成片"
    // 第一趟原尺寸（1），OOM 后降一档（2 = 长边减半）重试一次
    var sample = 1
    repeat(2) {
        var bmp: Bitmap? = null
        try {
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            bmp = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return LoadResult.Err("图片已失效或无法读取")
            val w = bmp.width
            val h = bmp.height
            val bytes = ByteBuffer.allocateDirect(w * h * 4)
            bmp.copyPixelsToBuffer(bytes)
            bytes.rewind()
            return LoadResult.Ok(
                EditorSource(bytes, w, h, name, srcW, srcH).also { it.decoded = bmp },
            )
        } catch (_: OutOfMemoryError) {
            // 降档前先把这一份还回去，否则重试时两份同时在（全分辨率下每份 50MB）
            bmp?.recycle()
            sample *= 2
        } catch (_: Throwable) {
            bmp?.recycle()
            return LoadResult.Err("图片已失效或无法读取")
        }
    }
    return LoadResult.Err("图片过大，无法加载")
}

/** 全分辨率解码（导出用，IO 线程）。失败/OOM 一律返回 null，由调用方提示且不动原片。 */
private fun decodeFull(context: Context, uri: Uri): Bitmap? = try {
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
} catch (t: Throwable) {
    null
}

/** 读整份原片字节（覆盖时既作回滚备份、又作 EXIF 来源，只读一次） */
private fun readImageBytes(context: Context, uri: Uri): ByteArray? = try {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
} catch (t: Throwable) {
    null
}
