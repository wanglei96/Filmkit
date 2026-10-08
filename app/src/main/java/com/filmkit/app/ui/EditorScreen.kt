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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filmkit.app.camera.EditorEngineSession
import com.filmkit.app.camera.OverwriteResult
import com.filmkit.app.camera.StillSnapshot
import com.filmkit.app.camera.StillSnapshotStore
import com.filmkit.app.camera.editorExportName
import com.filmkit.app.camera.editorParams
import com.filmkit.app.camera.encodeEditorJpeg
import com.filmkit.app.camera.overwriteOriginalPhoto
import com.filmkit.app.camera.refreshOverwrittenExif
import com.filmkit.app.camera.saveEditorCopy
import com.filmkit.app.lut.LutLibrary
import com.filmkit.app.lut.LutPreview
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
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
 * 分辨率分级（架构师拍板）：**预览用降采样图**（长边 ≤1600，交互实时），
 * **导出时才解码全分辨率原图**（成片质量优先）。这样编辑器停留期间常驻内存是
 * 预览级的几 MB，而不是 12MP 的 50MB。
 *
 * 参数起点（集成点 I-3）：打开时读该片的参数快照（`StillSnapshotStore`）；没有快照
 * 时回退 [CameraPresets.standard] 并在界面上明确提示"已用标准基线"（ADR-4）。
 *
 * ⚠ 编辑器**不写任何拍摄用的 prefs**（`lut_id` / `lut_strength`）：这里的 LUT 选择
 * 只对本次编辑会话有效，退出即丢 —— 否则用户在编辑器里试个滤镜，回到相机页拍照
 * 也被套上（架构师 2026-10-08 明确要求隔离）。下次打开这张照片仍从快照的 LUT 起。
 *
 * @param uri 待编辑成片的 MediaStore URI；为 null（如进程重建后状态丢失）时显示失败态而非白屏。
 */
@Composable
fun EditorScreen(
    uri: Uri?,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("filmkit", 0) }
    // 预览解码目标：长边 ≤ 1600px（"屏幕尺寸级"，架构师拍板）。inSampleSize 只认
    // 2 的幂 ⇒ 实际落在 800~1600 之间（12MP 的 4080×3072 解成 1020×768≈3.1MB）。
    // 全分辨率 12MP 的 RGBA8 是 50MB/张（输入+输出+Bitmap 同时持有 = 150MB+，
    // 勘察报告 U7），预览一律不碰那个量级。
    val previewLong = max(
        1600,
        max(
            with(density) { configuration.screenWidthDp.dp.roundToPx() },
            with(density) { configuration.screenHeightDp.dp.roundToPx() },
        ),
    )

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
    // 参数数组（78 槽，编辑器只改得动 [34]；其余槽位原样带着 —— 引擎侧只读
    // [26][27][34]，其余槽位改了也不影响输出，E4-1 契约）
    var params by remember { mutableStateOf<DoubleArray?>(null) }
    var lutId by remember { mutableStateOf<String?>(null) }   // 本会话的 LUT（不持久化）
    var lutK by remember { mutableFloatStateOf(1f) }          // = params[34]
    var hasSnapshot by remember { mutableStateOf(false) }
    var lutOpen by remember { mutableStateOf(false) }
    var lutPreviews by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var exporting by remember { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    /** 提交一次预览渲染（合并/节流语义见 [EditorEngineSession.requestPreview]） */
    fun rerender() {
        val src = source ?: return
        val film = params ?: return
        // ★ 导出期间**不再发预览请求**（架构师要求落实到这一侧）：导出走全分辨率，
        // 而引擎的 still_tex_/tex_cap_ 按尺寸复用 —— 两种分辨率交替会反复重建
        // 数十 MB 的纹理，既慢又平白增加失败面。
        if (exporting) return
        engine.requestPreview(
            src.buffer, src.bitmap.width, src.bitmap.height, film.copyOf(),
        ) { out ->
            if (out != null) {
                shown = out
                status = ""
            } else {
                status = "渲染失败，显示原图"
            }
        }
    }

    /** 选 LUT（null = 关闭）。解析在 IO 线程，上传与重渲染在渲染线程。 */
    fun pickLut(entry: LutLibrary.Entry?) {
        val film = params ?: return
        if (entry == null) {
            lutId = null
            film[34] = 0.0
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
            film[34] = lutK.toDouble()
            engine.setLut(lut)
            rerender()
        }
    }

    /** 导出收尾：状态改动一律回主线程（导出线程 / 渲染线程都会调到这里） */
    fun finish(msg: String) {
        scope.launch {
            exporting = false
            status = msg
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
            // ② 全分辨率解码（成片质量优先；预览那张只有长边 ≤1600）
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

    LaunchedEffect(uri, previewLong) {
        phase = EditorPhase.Loading
        status = "正在读取成片…"
        val res = withContext(Dispatchers.IO) { loadPreview(context, uri, previewLong) }
        when (res) {
            is LoadResult.Err -> {
                phase = EditorPhase.Failed(res.reason)
                return@LaunchedEffect
            }
            is LoadResult.Ok -> {
                val src = res.src
                source = src
                shown = src.bitmap
                phase = EditorPhase.Ready
                status = "正在渲染…"
                // 参数起点 + 初始化引擎 + 恢复 LUT，全在 IO 线程做完再回主线程
                val init = withContext(Dispatchers.IO) {
                    // I-3：快照存在 ⇒ 用这张片子当初的参数与 LUT；不存在 ⇒ 标准基线（ADR-4）
                    val snap = StillSnapshotStore.load(context, src.name)
                    val film = editorParams(snap?.params)
                    val entry = snap?.lutId?.takeIf { it.isNotEmpty() }
                        ?.let { id -> LutLibrary.byId(context, id) }
                    val lut = entry?.let { LutLibrary.load(context, it) }
                    val started = engine.start()
                    // 引擎的 LUT 是进程级状态，可能残留着拍摄时的那一套：按快照决定去留
                    engine.setLut(lut)
                    EditorInit(film, snap != null, lut, entry?.id, started)
                }
                params = init.film
                hasSnapshot = init.hasSnapshot
                lutId = init.lutId
                if (init.lut != null) lutK = init.film[34].toFloat()
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
            SubPageTopBar("编辑", onBack)

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
                        if (bmp != null) {
                            val image = remember(bmp) { bmp.asImageBitmap() }
                            Image(
                                bitmap = image,
                                contentDescription = "成片预览",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
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
                    text = editorHint(status, hasSnapshot, lutId != null),
                    color = FilmTheme.CreamDim,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { lutOpen = !lutOpen },
                        enabled = !exporting && phase == EditorPhase.Ready,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (lutOpen) FilmTheme.Accent else FilmTheme.Cream,
                            disabledContentColor = FilmTheme.CreamDim,
                        ),
                    ) {
                        Text(
                            lutId?.let { id -> "LUT · ${LutLibrary.byId(context, id)?.label ?: id}" }
                                ?: "LUT",
                            fontFamily = FilmTheme.Mono, fontSize = 12.sp, maxLines = 1,
                        )
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

        // ── LUT 面板（底部弹出，复用拍摄页的卡片/强度滑杆形态）──
        // 用弹出面板而不是整页跳转：拖强度时必须能同时看到画面变化。
        if (lutOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { lutOpen = false } },
            )
        }
        AnimatedVisibility(
            visible = lutOpen,
            enter = FilmMotion.panelEnter,
            exit = FilmMotion.panelExit,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            EditorLutPanel(
                lutId = lutId,
                lutK = lutK,
                previews = lutPreviews,
                onStrength = { v ->
                    lutK = v
                    params?.let { it[34] = v.toDouble() }
                    rerender()
                },
                onPick = { e -> pickLut(e) },
                onClose = { lutOpen = false },
            )
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

    // 面板首次打开时才生成 LUT 卡片预览（复用拍摄页的一次性缓存，见 LutPreview）
    LaunchedEffect(lutOpen) {
        if (!lutOpen || lutPreviews.isNotEmpty()) return@LaunchedEffect
        lutPreviews = withContext(Dispatchers.IO) {
            LutLibrary.entries(context).mapNotNull { e ->
                LutLibrary.load(context, e)?.let { lut ->
                    LutPreview.load(context, e.id, lut)?.let { bmp -> e.id to bmp }
                }
            }.toMap()
        }
    }
}

/** 底部提示文案（ADR-2 的素材分级 + ADR-4 的无快照提示，必须显式说给用户） */
private fun editorHint(status: String, hasSnapshot: Boolean, hasLut: Boolean): String = when {
    status.isNotEmpty() -> status
    !hasSnapshot -> "这张没有参数记录，已用标准基线"
    hasLut -> "已按拍摄参数还原 · LUT 会叠加在原片已有的风格之上（拖到 0% 即关闭）"
    else -> "成片编辑 · 仅后段风格化（LUT / 颗粒 / 暗角），不改曝光与白平衡"
}

/** LaunchedEffect 里初始化结果的打包（避免在协程里零散赋值） */
private class EditorInit(
    val film: DoubleArray,
    val hasSnapshot: Boolean,
    val lut: com.filmkit.app.lut.CubeLut?,
    val lutId: String?,
    val engineStarted: Boolean,
)

/** LUT 选择面板：强度 + 横向卡片（形态与拍摄页 LutSheet 的 LUT 页一致） */
@Composable
private fun EditorLutPanel(
    lutId: String?,
    lutK: Float,
    previews: Map<String, Bitmap>,
    onStrength: (Float) -> Unit,
    onPick: (LutLibrary.Entry?) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(FilmTheme.Panel)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                lutId?.let { id -> LutLibrary.byId(context, id)?.label ?: id } ?: "无 LUT",
                color = FilmTheme.Cream,
                fontSize = 20.sp,
                fontFamily = FilmTheme.Serif,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
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
        // 强度只对已选中的 LUT 有意义；拖到 0 = 旁路（引擎侧 [34]=0）
        if (lutId != null) {
            StrengthRow(initial = lutK, onCommit = onStrength, onFinished = {})
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 10.dp),
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
}

/** 编辑器页面状态：三态显式建模，避免"位图与错误同时非空"这类非法组合 */
private sealed interface EditorPhase {
    data object Loading : EditorPhase
    data object Ready : EditorPhase
    data class Failed(val reason: String) : EditorPhase
}

/**
 * 解码出来的预览素材。
 *
 * [buffer] 是 [bitmap] 像素的 **direct 副本**，渲染输入复用它 —— 滑杆每次变化只改参数、
 * 不重拷像素（省掉每次 3MB 的 copyPixelsToBuffer）。
 * [srcW]/[srcH] 是**原图**像素尺寸（底部读数用），不是预览尺寸。
 */
private class EditorSource(
    val bitmap: Bitmap,
    val buffer: ByteBuffer,
    val name: String,
    val srcW: Int,
    val srcH: Int,
)

private sealed interface LoadResult {
    class Ok(val src: EditorSource) : LoadResult
    class Err(val reason: String) : LoadResult
}

/**
 * 读图（IO 线程）：先只读尺寸（inJustDecodeBounds）算采样比，再按采样比解码。
 * 先解码再缩是不行的 —— 大图在解码那一步就已经 OOM 了。
 */
private fun loadPreview(context: Context, uri: Uri?, targetLong: Int): LoadResult {
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

    // inSampleSize 只认 2 的幂（给别的值也会向下取整）：取"长边首次不超过目标"的那一档
    var sample = 1
    while (max(srcW, srcH) / sample > targetLong) sample *= 2

    // 文件名一律走 StillSnapshotStore.fileNameOf（ADR 集成点 I-1）：参数快照的键就是它，
    // 两处各写一份实现，一旦行为不一致，编辑器查到的键就不是落盘时那个（静默取不到快照）。
    // 查不到时的兜底保留原样（URI 末段 → "成片"）。
    val name = StillSnapshotStore.fileNameOf(context, uri) ?: uri.lastPathSegment ?: "成片"
    // OOM 兜底：极端大图 / 低内存设备上降一档重试一次，仍失败才报错（宁可糊也不崩）
    repeat(2) {
        try {
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return LoadResult.Err("图片已失效或无法读取")
            // 渲染输入：像素的一次性 direct 副本（RGBA_8888 的内存布局即 R,G,B,A，
            // 与引擎的 RGBA8 约定一致，不需要任何通道重排）
            val bytes = ByteBuffer.allocateDirect(bitmap.width * bitmap.height * 4)
            bitmap.copyPixelsToBuffer(bytes)
            bytes.rewind()
            return LoadResult.Ok(EditorSource(bitmap, bytes, name, srcW, srcH))
        } catch (_: OutOfMemoryError) {
            sample *= 2
        } catch (_: Throwable) {
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
