package com.filmkit.app.ui

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.Brightness7
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Details
import androidx.compose.material.icons.outlined.Exposure
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoFilter
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Vignette
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filmkit.app.EngineBridge
import com.filmkit.app.camera.CLARITY_UI_RANGE
import com.filmkit.app.camera.CONTRAST_UI_RANGE
import com.filmkit.app.camera.EXP_UI_RANGE
import com.filmkit.app.camera.EditorEngineSession
import com.filmkit.app.camera.FADE_UI_RANGE
import com.filmkit.app.camera.ImageFitRect
import com.filmkit.app.camera.LOCAL_ANGLE_UI_RANGE
import com.filmkit.app.camera.LOCAL_EV_UI_RANGE
import com.filmkit.app.camera.LOCAL_FEATHER_UI_RANGE
import com.filmkit.app.camera.LOCAL_RADIUS_UI_RANGE
import com.filmkit.app.camera.LOCAL_SAT_UI_RANGE
import com.filmkit.app.camera.LOCAL_WIDTH_UI_RANGE
import com.filmkit.app.camera.LUT_STRENGTH_UI_RANGE
import com.filmkit.app.camera.LocalMode
import com.filmkit.app.camera.LocalOutline
import com.filmkit.app.camera.OverwriteResult
import com.filmkit.app.camera.SAT_UI_RANGE
import com.filmkit.app.camera.StillSnapshot
import com.filmkit.app.camera.StillSnapshotStore
import com.filmkit.app.camera.TEMP_UI_PIVOT
import com.filmkit.app.camera.TEMP_UI_RANGE
import com.filmkit.app.camera.TINT_UI_RANGE
import com.filmkit.app.camera.TONE_UI_RANGE
import com.filmkit.app.camera.WB_GAIN_UI_RANGE
import com.filmkit.app.camera.ZERO_UI_PIVOT
import com.filmkit.app.camera.claritySlot
import com.filmkit.app.camera.clarityUi
import com.filmkit.app.camera.contrastSlot
import com.filmkit.app.camera.contrastUi
import com.filmkit.app.camera.discardNeedsConfirm
import com.filmkit.app.camera.editorExportName
import com.filmkit.app.camera.editorOpenParams
import com.filmkit.app.camera.encodeEditorJpeg
import com.filmkit.app.camera.expSlot
import com.filmkit.app.camera.expUi
import com.filmkit.app.camera.fadeSlot
import com.filmkit.app.camera.fadeUi
import com.filmkit.app.camera.formatDegree
import com.filmkit.app.camera.formatEv
import com.filmkit.app.camera.formatKelvin
import com.filmkit.app.camera.formatOffset100
import com.filmkit.app.camera.formatZero100
import com.filmkit.app.camera.formatZero150
import com.filmkit.app.camera.formatZero200
import com.filmkit.app.camera.imageFitRect
import com.filmkit.app.camera.isJpegSource
import com.filmkit.app.camera.linearOutline
import com.filmkit.app.camera.localActive
import com.filmkit.app.camera.localAngleSlot
import com.filmkit.app.camera.localAngleUi
import com.filmkit.app.camera.localCenterFromScreen
import com.filmkit.app.camera.localCenterSlot
import com.filmkit.app.camera.localCenterUi
import com.filmkit.app.camera.localEvSlot
import com.filmkit.app.camera.localEvUi
import com.filmkit.app.camera.localFeatherSlot
import com.filmkit.app.camera.localFeatherUi
import com.filmkit.app.camera.localRadiusSlot
import com.filmkit.app.camera.localRadiusUi
import com.filmkit.app.camera.localSatSlot
import com.filmkit.app.camera.localSatUi
import com.filmkit.app.camera.localTypeName
import com.filmkit.app.camera.localTypeSlot
import com.filmkit.app.camera.localTypeUi
import com.filmkit.app.camera.localWidthSlot
import com.filmkit.app.camera.localWidthUi
import com.filmkit.app.camera.overwriteOriginalPhoto
import com.filmkit.app.camera.paramsUnchanged
import com.filmkit.app.camera.passthroughAllowed
import com.filmkit.app.camera.pivotFraction
import com.filmkit.app.camera.radialOutline
import com.filmkit.app.camera.refreshOverwrittenExif
import com.filmkit.app.camera.resetBasicParams
import com.filmkit.app.camera.resetColorParams
import com.filmkit.app.camera.resetLocalGeometry
import com.filmkit.app.camera.saveEditorCopy
import com.filmkit.app.camera.satUi
import com.filmkit.app.camera.tempSlot
import com.filmkit.app.camera.tempUi
import com.filmkit.app.camera.tintSlot
import com.filmkit.app.camera.tintUi
import com.filmkit.app.camera.toneSlot
import com.filmkit.app.camera.toneUi
import com.filmkit.app.camera.vigFeatherSlot
import com.filmkit.app.camera.vigFeatherUi
import com.filmkit.app.camera.vigRadiusSlot
import com.filmkit.app.camera.vigRadiusUi
import com.filmkit.app.camera.vigStrengthSlot
import com.filmkit.app.camera.vigStrengthUi
import com.filmkit.app.camera.wbGainSlot
import com.filmkit.app.camera.wbGainUi
import com.filmkit.app.camera.writeSaturation
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
 * 内建编辑器（E1-4）：成片再风格化 —— 引擎后段（基础调整 → 暗角 → 3D LUT → 颗粒 →
 * TPDF dither）跑在**显示域**，输入就是成片本身的 sRGB 像素（ADR-7：链路里任何地方都
 * 不得插入**单程** OETF/EOTF；也绝不走 `submitStill`，那会对成片再跑一遍 filmic =
 * 双重影调映射）。
 *
 * 素材分级（ADR-2）：编辑对象是**成片 JPEG**（OETF 之后）—— **丢失的信息找不回来**
 *（已裁切的高光、被降噪/去马赛克抹掉的细节），但**可逆的调整照做**（ADR-7 订正 v2）：
 * 曝光/白平衡走线性域**成对往返**，对比度/饱和度/清晰度是显示域操作，LUT/暗角同理
 *（E2-2 已实现并独立验收，E2-3 接入 UI）。
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
 * 参数面板（E2-10 重做为**专业面板**，ADR-14）：五层结构 —— 顶部栏（取消 / 撤销·重做留位 /
 * 覆盖 / 完成）→ 预览区（weight 1f）→ 参数名（居中大字）→ **单滑杆**（满宽 + 密集刻度 +
 * 中点标记 + 物理量读数）→ 参数图标行（横向滚动圆形按钮）→ 底部导航（调整 / 色彩 / 暗角 /
 * 局部；预设·裁剪置灰留位）。
 *
 * E2-15（用户真机反馈，本页四处修正）：
 *  ① **面板可收起**：顶部/底部把手（下拉收起·上拉展开·点击切换）+ **返回键分层**（展开时
 *     系统返回先收起面板，已收起才退出编辑器）。收起后面板整段不组合 ⇒ 预览区吃满全屏。
 *     状态机是纯逻辑（[EditorPanelState]），JVM 单测覆盖。
 *  ② **色温/色调移入「调整」组**（用户："怎么放 lut 里了应该是调整里面"），褪色留在「色彩」
 *     组；色彩组的 LUT 与参数**拆成两行**（LUT 42 个，同行会把参数埋掉）。
 *  ③ **「查看原图」按钮**（用户："给个按钮"）：预览区右下角圆形按钮，**按住看原图 / 松手恢复**
 *     （E2-17 改成 press-and-hold，见下）；局部模式下隐藏（不跟拖动定位抢事件）。
 *  ④ **「取消」二次确认**：有修改才弹「放弃本次修改？」—— 判据复用 E2-9 的 `paramsUnchanged`
 *     （[discardNeedsConfirm]），与导出直通同一份逻辑。
 *
 * E2-17（v0.11.5 验收反馈：三条里改两条 + 一条动画打磨）：
 *  ① **面板出入场过渡**（用户："面板上下拉加过渡动画，现在是瞬变"）：收起/展开改由
 *     [AnimatedContent] 承载 —— 内容交叉（面板 ⇄ 收起条）+ **尺寸过渡**（[SizeTransform]）
 *     + 位移 + 淡入淡出，统一 240ms 减速曲线（[PANEL_ANIM_MS]）。尺寸过渡是关键：预览区是
 *     `weight(1f)`，面板容器高度平滑变化时它**跟着伸缩**，不会"面板瞬间消失、预览区跳一下"。
 *     **动画期间不触发任何渲染**：渲染调用点仍是打开首帧 / 滑杆松手 / 离散操作那几处，
 *     面板的进出场只改变布局，不进 [rerender]（见该函数的 KDoc）。
 *  ② **原图按钮 = 按住看**（用户："不要点一下切原图再点切回；按住按钮看原图，松手恢复效果图"）：
 *     [OriginalViewState] 由 toggle（`locked` + `pressed` 两路）改成 **press/release 单路**语义；
 *     按钮用 `onPress` + `tryAwaitRelease`（与原来长按预览区同一套 API），按下态 = 黄底 + 深色
 *     图标 + 0.88 缩放（用户必须一眼看出"按住了"）。
 *  ③ **取消「长按预览区看原图」**（用户："也不用长按图片查看原图"）：预览区的 Image 不再注册
 *     任何 tap/long-press 手势。E2-7 的局部拖动定位（`detectDragGestures`）与轮廓绘制走的是
 *     另一套 pointerInput 与同一批纯函数（[imageFitRect] / [localCenterFromScreen] /
 *     [radialOutline] / [linearOutline]），本卡**一行未动** ⇒ 拖动定位与轮廓不受影响。
 *
 * 与 E2-1/E2-3/E2-7 那版（可折叠区 + 纵向堆 7~19 条"标签｜细滑杆｜百分比"三段式表单行）的
 * 区别就是 ADR-14 的三条关键修正：**满宽滑杆 + 标签在上方**、**中点标记**、**读数用物理量**
 *（曝光 `+1.0 EV` 而不是 `100%`）。**只渲染引擎已实现并验收的参数**（ADR-12：不许假滑杆），
 * 参数清单与槽位映射见文件末尾的参数表（[EditorParam]）。
 *
 * 面板仍**不是覆盖式浮层**（嵌在预览与底栏之间）：暗角最明显的地方是四角，浮层挡住底边
 * 两点等于让用户盲调 —— 这是 E1-4 起的设计约束，本卡不改（收起是"整段不渲染"，不是浮层化）。
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
    // 把手的拖动阈值是 dp，判定在纯逻辑里按 px 比较（[EditorPanelState.afterDrag]）
    val density = LocalDensity.current
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
    // 参数数组（长度 = CameraPresets.COUNT）：编辑器只改得动基础五类 [0][1..3][4][20..22][78]、
    // LUT 强度 [34] 与暗角 [66][67][68]；其余槽位原样带着（快照继承 → 导出时整体存回快照，
    // 所以"没碰过的风格参数"不会因为进了一次编辑器就丢）
    var params by remember { mutableStateOf<DoubleArray?>(null) }
    // 打开编辑器时的参数基准（E2-9 / ADR-13）：`params` 的**副本**，导出时逐槽比一次值
    //（同一个数组对象会被滑杆原地改写 ⇒ 必须 copy）。"无修改"⇒ 导出直通原片字节。
    var openParams by remember { mutableStateOf<DoubleArray?>(null) }
    var lutId by remember { mutableStateOf<String?>(null) }   // 本会话的 LUT（不持久化）
    // LUT 强度 = **派生值**（E2-10）：唯一数据源是 `params[34]`，不再单独存一份 state ——
    // 存两份的代价是"滑杆写了 [34]、hint / 套用原片风格读到的还是旧值"这类状态不同步。
    val lutK = params?.let { it[34].toFloat() } ?: 0f
    // 快照里的"原片风格"（只读展示 + 一键套用）：不随滑杆变化，故与 lutId/lutK 分开存
    var snapLutId by remember { mutableStateOf<String?>(null) }
    var snapLutK by remember { mutableFloatStateOf(0f) }
    var hasSnapshot by remember { mutableStateOf(false) }
    // 当前选中的功能大类（E2-10）：底部导航 = 调整 / 色彩 / 暗角 / 局部。面板常驻
    //（不再是可折叠区），所以这里取代了 E2-1 的 `panelOpen`。
    var tab by remember { mutableStateOf(EditorTab.ADJUST) }
    // 面板"重建计数"（E2-3）：滑杆的读数 state 只在首次组合时读初值（组件内部 remember，
    // 由 `key(param.key, refreshTick)` 控制生命周期）。所以凡是**绕过滑杆**改了参数的操作
    //（各组「重置」/ 单参数重置 / 套用原片风格），都要 ++ 让滑杆子树重建一次 —— 否则画面
    // 变了、滑杆读数还停在原处，等于把 ADR-12 的假滑杆反过来演一遍（读数与状态不符）。
    var panelRefresh by remember { mutableIntStateOf(0) }
    // ── E2-15 面板收起/展开 + E2-17 原图对比（用户真机反馈）──────────────────
    // 面板：唯一数据源 = 不可变状态（转换全是纯函数，见文件末尾 [EditorPanelState]）。
    // 收起后面板整段不组合 ⇒ 预览区（weight 1f）自动吃满剩余空间；E2-17 起进出场有过渡
    //（[AnimatedContent] + 尺寸过渡，见下方参数区的用法）。
    var panel by remember { mutableStateOf(EditorPanelState()) }
    // 原图对比（E2-17）：**按住 = 看原图，松手 = 回效果图**（用户明确否掉了 toggle 语义）。
    // 唯一来源就是这一个 pressed（[OriginalViewState]）—— 不再有"按钮锁定"那一路。
    var origView by remember { mutableStateOf(OriginalViewState()) }
    val showOriginal = origView.showing
    // 「取消」的二次确认（E2-15）：有修改才弹（判据 = E2-9 的 paramsUnchanged，见 [discardNeedsConfirm]）
    var confirmDiscard by remember { mutableStateOf(false) }
    var lutPreviews by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var exporting by remember { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    // 导出期间被挡下的预览请求（导出独占引擎，见 rerender / finish）
    var pendingRender by remember { mutableStateOf(false) }
    // ── E2-7 局部调整 ──────────────────────────────────────────────────────
    // 预览区尺寸（Fit 换算要用）与"正在拖动定位"标志（拖动中只画轮廓、松手才渲染）
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var localDragging by remember { mutableStateOf(false) }

    /**
     * 渲染诊断（**单一出口**：分辨率 / 耗时 / 拖动状态 / 基础五类 / 暗角三条 / LUT 强度
     * / **E2-7 局部调整** 全在这一条里）。
     *
     * 为什么不拆成几个函数各记各的：本项目踩过"字段写进别的函数 ⇒ 真机日志缺项"的坑
     *（ADR §5.4 T-2）—— 一条日志里字段齐全，真机一次运行就能核对全部规格。
     *
     * `dragging` 恒 false 是**回归哨兵**：E2-8 的规格是"拖动中不渲染"，真机日志里若出现
     * dragging=true，说明有人把渲染又接回了 onValueChange。`dragTicks` = 本次渲染之前
     * 拖动中收到的值变更次数（拖动 60 次只产生 1 条渲染日志 = 规格成立）—— E2-7 的
     * 拖动定位走同一个 gate，所以这条哨兵同时守住"拖动定位中不渲染"。
     *
     * E2-7 字段：`locType`（off/radial/linear）+ 中心/半径/羽化/角度/宽度/ev/sat —— 与
     * 引擎侧 `kFsStyle` 的 `locType`/`locEv`/`locSat` 同口径，真机可一眼核对"UI 拖到哪、
     * 引擎收到哪"。
     */
    fun logRender(w: Int, h: Int, ms: Long, ticks: Int, ok: Boolean, dragging: Boolean = false) {
        val film = params ?: return
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "app", "editor.render",
            kv(
                "w" to w, "h" to h, "ms" to ms, "dragging" to dragging,
                "dragTicks" to ticks, "ok" to ok, "memMb" to previewResidentMb(w, h),
                "ev" to film[0], "wbR" to film[1], "wbG" to film[2], "wbB" to film[3],
                "contrast" to film[4], "sat" to film[21], "clarity" to film[78],
                "strength" to film[66], "radius" to film[67], "feather" to film[68],
                "lut" to film[34],
                "locType" to localTypeName(film[79]), "locCx" to film[80], "locCy" to film[81],
                "locR" to film[82], "locF" to film[83], "locA" to film[84],
                "locW" to film[85], "locEv" to film[86], "locSat" to film[87],
                "locOn" to localActive(film),
            ),
        )
    }

    /**
     * 导出诊断（**单一出口**，E2-9）：`dirty` = 是否走了**渲染 + 编码**路径（写出字节数、
     * 耗时）。`dirty=false` 时 `bytes` = 原片字节数、`ms` = 一次读 + 一次写（无渲染无编码）。
     *
     * E2-18b 起 `dirty` 的语义仍是"**有没有渲染**"（不是"用户有没有改参数"）：源图不是 JPEG
     * （DNG 等）时，即使参数一个都没动也必须渲染 + 编码 ⇒ 这一趟记 `dirty=true`，日志里
     * "参数没动却渲染了"是可解释的（见 [passthroughAllowed]）。
     * 落盘失败另有 `photo.edit_save_failed` / `photo.edit_overwrite_failed`，不在这里重复。
     */
    fun logExport(dirty: Boolean, bytes: Int, ms: Long, overwrite: Boolean) {
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "app", "editor.export",
            kv("dirty" to dirty, "bytes" to bytes, "ms" to ms, "overwrite" to overwrite),
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
            film[34] = 0.0                    // 关闭 = 强度 0（引擎 uLutOn = 0 整段旁路）
            engine.setLut(null)
            panelRefresh++                    // 强度行消失/读数归位（见 panelRefresh 的 KDoc）
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
            film[34] = k.toDouble()
            engine.setLut(lut)
            // 强度是"绕过滑杆"改的（本函数直接写 [34]）：不重建的话，用户点卡片/套用原片
            // 风格后滑杆读数还停在旧值（画面变了、读数没变 = ADR-12 禁止的假滑杆的镜像）
            panelRefresh++
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
        applyLut(entry, snapLutK)   // 强度读数归位由 applyLut 统一负责
    }

    // ── 参数写入（E2-10：数据驱动，取代 E2-3/E2-7 的 16 个一模一样的 setter）────────────
    // 每个参数一行 [EditorParam]（见文件末尾的参数表），槽位映射全在 EditorExport.kt 的纯函数里
    //（含对比度"端点禁用 0"、饱和度"同写三槽"、局部"角度 = 弧度"这几个陷阱）。
    // 范式不变（E2-8）：拖动中只写槽位 + gate.onValueChange()，松手走 [onSliderRelease] 渲染一次。
    //
    // 为什么合并：19 个参数写成 19 个几乎相同的函数 ⇒ 新增一个参数要改三处（setter / 面板签名 /
    // 调用点），漏一处的表现是"滑杆动了、画面不动"（ADR-12 的假滑杆）。

    /** 滑杆拖动中：写槽位（不渲染） */
    fun commit(p: EditorParam, ui: Float) {
        params?.let { p.write(it, ui) }
        gate.onValueChange()
    }

    /** 重置**单个参数**到基准值（E2-10 新增）—— 离散操作，直接渲染一次 */
    fun resetParam(p: EditorParam) {
        params?.let { p.write(it, p.pivot) }
        panelRefresh++
        rerender()
    }

    /** 「调整」组重置（E2-3）：本组参数回恒等值 —— 离散操作，直接渲染一次（不经过 gate） */
    fun resetBasic() {
        params?.let { resetBasicParams(it) }
        panelRefresh++
        rerender()
    }

    /**
     * 「暗角」组重置：强度归 0 = **关**（引擎侧 [66] ≥ 0 ⇒ 整段跳过）。半径/羽化在强度为 0
     * 时对画面没有任何影响，保持原值即可 —— 少一次无意义的写入，语义也更清楚（"重置"=
     * 回到恒等 = 没有暗角）。
     */
    fun resetVignette() {
        params?.let { it[66] = vigStrengthSlot(0f) }
        panelRefresh++
        rerender()
    }

    /**
     * 「色彩」组重置（E2-15 改）：褪色 [94] 回恒等值 + LUT **强度归 0**，但
     * **保留 LUT 选择与纹理**。
     *
     * 为什么不卸载 LUT（用户确认："这个你判断的对，不应该清"）：LUT 是用户挑的**风格素材**，
     * 重置参数不该把素材本身丢掉 —— 旧实现走 `applyLut(null, 0f)` 会连 `lutId` 一起清空，
     * 用户点一下「重置本组」就丢了刚选好的风格，还得重新翻 42 个卡片。
     * 强度归 0 同样让画面回到原片（引擎侧 `uLutOn = 0` 整段旁路），但素材还在、强度滑杆还在。
     *
     * 色温/色调已随 E2-15 归入「调整」组（见 [resetBasic]）⇒ 本组只剩褪色一条引擎参数。
     */
    fun resetColor() {
        params?.let { resetColorParams(it) }   // 褪色 [94] → 0
        params?.let { it[34] = 0.0 }           // LUT 强度 → 0（**不动** lutId / 引擎纹理）
        panelRefresh++
        rerender()
    }

    // ── 局部调整（E2-7）────────────────────────────────────────────────────
    // 与上面两组同一范式：滑杆拖动中只写槽位 + gate.onValueChange()，松手统一走
    // [onSliderRelease] 渲染一次。**模式选择与拖动定位**是离散操作 ⇒ 直接 rerender()
    //（拖动定位走 gate + onSliderRelease，见预览区的手势）。

    /**
     * 选模式（关 / 径向 / 线性）⇒ 写 [79]。离散操作 ⇒ 直接渲染一次。
     *
     * 注意 **A/B 语义**（ADR 交互规格第 6 条）：引擎侧 `uLocalOn = type>0 && (ev≠0 ||
     * sat≠1)`，所以"选了类型但没调强度"时画面**不变**（正确行为，不是 bug）——面板上
     * 用 [localActive] 给出提示文案，不让用户以为滑杆坏了。
     */
    fun setLocalType(mode: Int) {
        params?.let { it[79] = localTypeSlot(mode) }
        localDragging = false
        panelRefresh++
        rerender()
    }

    /**
     * 拖动定位（预览上）：写 [80][81]（归一化图像坐标，顶行 = 0）。
     * **只写槽位、不渲染**（走 gate）—— 拖动中画面不动、只有 Compose 画的轮廓跟着走，
     * 松手由 [onSliderRelease] 渲染一次（与 E2-8 滑杆同一闸门，`dragTicks` 日志可证）。
     */
    fun dragLocalCenter(u: Float, v: Float) {
        params?.let { p ->
            val (x, y) = localCenterSlot(u, v)
            p[80] = x
            p[81] = y
        }
        gate.onValueChange()
    }

    /** 「局部」组重置：几何回默认（中心/半径/羽化/角度/宽度），模式与强度不动（见纯函数 KDoc） */
    fun resetLocal() {
        params?.let { resetLocalGeometry(it) }
        panelRefresh++
        rerender()
    }

    /**
     * 重置**当前组**（E2-10）：四条既有重置路径的**统一入口**（各组重置一个都没丢），
     * 放在参数名行左侧的「重置本组」。
     *
     * 声明位置在四条路径之后是 Kotlin 局部函数的硬要求（局部函数不能前向引用）。
     */
    fun resetGroup(g: EditorTab) {
        when (g) {
            EditorTab.ADJUST -> resetBasic()
            EditorTab.COLOR -> resetColor()
            EditorTab.VIGNETTE -> resetVignette()
            EditorTab.LOCAL -> resetLocal()
        }
    }

    /**
     * 面板把手拖动落定（E2-15）：累计位移超过阈值才切换 —— 判定是纯函数
     * [EditorPanelState.afterDrag]（JVM 单测覆盖），这里只做 dp→px 与状态落地。
     */
    fun onHandleDrag(dy: Float) {
        val threshold = with(density) { EditorPanelState.DRAG_THRESHOLD_DP.dp.toPx() }
        EditorPanelState.afterDrag(panel, dy, threshold)?.let { panel = it }
    }

    /**
     * 「取消」（E2-15 用户反馈："如果改过参数，弹一句『放弃本次修改？』→ 确认才退出"）：
     * 无修改直接退出（不打扰），有修改先弹确认 —— 判据是 E2-9 的 [discardNeedsConfirm]
     * （= `paramsUnchanged` 的包装），与导出直通判据**同一份逻辑**。
     */
    fun onCancel() {
        if (discardNeedsConfirm(params, openParams)) confirmDiscard = true else onBack()
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

    /**
     * 编码 + 落盘（导出线程）。**只服务"有修改"路径**（无修改走 [exportNow] 的直通分支，
     * 不渲染不编码）。任何失败都必须让用户能分辨"原片有没有被动过"。
     */
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
        logExport(dirty = true, bytes = jpeg.size, ms = System.currentTimeMillis() - now, overwrite = overwrite)
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
     *
     * **E2-9 分流**（ADR-13 真机实测归因）：参数与打开编辑器时**逐槽相同** ⇒ 画面就是原片
     * 本身，**直接复制原片字节**（不渲染、不编码）。重渲染一次的代价实测是：JPEG 二次编码
     * （体积增长的 55.16%）+ 多叠一层 dither（43.40%）+ 一次全分辨率渲染（百毫秒）+ 峰值内存
     * 240MiB —— 全是白付的（"细节更好"是平坦区噪声被读成质感的错觉，强纹理区仅 +0.016）。
     *
     * **E2-18b 收口**：直通是"复制原片字节"，所以还要求**源图是 JPEG** —— DNG 源图即使
     * 参数没动，也必须渲染 + 编码（否则 DNG 字节会被存成 `.jpg`，见 [passthroughAllowed]）。
     */
    fun exportNow(overwrite: Boolean) {
        val src = source ?: return
        val snapshot = params?.copyOf() ?: return
        val target = uri
        if (exporting || target == null) return

        // ★ 参数判据：基准 = openParams（**打开时**的参数，即 editorOpenParams 的返回值，
        // 已把 [34] 强制置 0），不是快照原文；值比较 + 容差 ⇒ "改了又改回原值"也算无修改。
        // openParams 为 null（理论上只在首帧就绪前，此时导出按钮不可点）⇒ 保守走渲染。
        val unchanged = openParams?.let { paramsUnchanged(snapshot, it) } ?: false

        // 覆盖 = 把原片字节写回原片本身：白写一遍（还平添写坏的风险），直接说清楚。
        // ⚠ 这条**只看参数**、与源图格式无关：它一个字节都不写。若把"非 JPEG 源图"也算成
        // 有修改而放它过去，一张没动过的 DNG 会被渲染成 JPEG 覆盖回去（原片被换掉）= 数据损坏。
        if (unchanged && overwrite) {
            logExport(dirty = false, bytes = 0, ms = 0, overwrite = true)
            status = "没有修改，无需覆盖"
            return
        }

        // ★ E2-18b：另存直通 = 复制原片字节，**只有 JPEG 源图合法**（落盘固定 `.jpg` +
        // MIME image/jpeg，见 saveEditorCopy）。DNG（及其他非 JPEG）源图一律落到下面的
        // 渲染 + 编码路径 —— 真出一张 JPEG，内容与扩展名才对得上。
        if (passthroughAllowed(unchanged, src.jpegSource)) {
            exporting = true
            status = "正在保存原片…"
            val now = System.currentTimeMillis()
            val outName = editorExportName(now)
            exportExec.execute {
                val bytes = readImageBytes(context, target)
                if (bytes == null || bytes.isEmpty()) {
                    finish("原图读取失败，未做任何改动")
                    return@execute
                }
                // rewriteExif = false：直通的意义就是**字节原样**，EXIF 里的拍摄参数与拍摄
                // 时间天然完整；重写反而会把拍摄时间改成现在、方向硬写成 1（比原样更差）
                val saved = saveEditorCopy(context, bytes, outName, now, rewriteExif = false)
                logExport(
                    dirty = false, bytes = bytes.size,
                    ms = System.currentTimeMillis() - now, overwrite = false,
                )
                if (saved == null) {
                    finish("保存失败，原片未改动")
                } else {
                    // 与渲染路径同一口径：新片也留一份参数快照（下次编辑它时起点一致）
                    StillSnapshotStore.save(
                        context,
                        StillSnapshotStore.fileNameOf(context, saved) ?: outName,
                        StillSnapshot(snapshot, lutId.orEmpty(), lutK.toDouble(), now),
                    )
                    finish("已另存为 $outName")
                }
            }
            return
        }

        exporting = true
        status = "正在生成全分辨率成片…"
        val now = System.currentTimeMillis()
        val outName = editorExportName(now)
        exportExec.execute {
            // ① 覆盖要用的原片字节：读**一次**，既作回滚备份又作 EXIF 来源
            val origBytes = if (overwrite) readImageBytes(context, target) else null
            // ② ★ E2-9 内存：**复用预览的常驻 buffer**（`src.buffer`）。E2-8 起预览与导出
            //    同分辨率、同像素 —— 它就是这张图的全分辨率 RGBA，再独立解一份纯属浪费。
            //    省掉"独立解码位图 + 导出 direct buffer"两份 ≈ 2×47.8MiB（12MP），导出峰值
            //    从 ≈239MiB 降到 ≈143MiB。⚠ 复用后**绝不回收** src.buffer：它是编辑器的
            //    常驻输入（预览 / "按住看原图"都读它），回收 = 后续预览读到野内存。
            engine.requestFull(src.buffer, src.w, src.h, snapshot) { out ->
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
                // E2-9：导出直通的比较基准 = **打开时**的参数（`params` 会被滑杆原地改写
                // ⇒ 必须留一份副本，且必须是 editorOpenParams 的结果，不是快照原文）
                openParams = init.film.copyOf()
                hasSnapshot = init.hasSnapshot
                lutId = init.lutId
                snapLutId = init.snapLutId
                snapLutK = init.snapLutK
                // 注：LUT 强度（lutK）自 E2-10 起是 `params[34]` 的**派生值**，不再单独赋值
                if (!init.engineStarted) {
                    status = "引擎不可用，显示原图"
                    return@LaunchedEffect
                }
                status = ""
                rerender()
            }
        }
    }

    // ★ E2-15 返回键**分层**（用户反馈："系统返回手势要可以收起编辑面板。目前编辑面板打开所有
    // 照片点开默认展开收不起来！"）：面板展开时本 handler 启用 ⇒ 系统返回先**收起面板**（消费掉，
    // 不退编辑器）；面板已收起时本 handler 不启用 ⇒ 返回事件落到宿主（MainActivity 的 Nav
    // BackHandler）才退出编辑器。一次返回绝不会"面板还开着就直接退出"。
    BackHandler(enabled = panel.expanded) {
        panel = EditorPanelState.onBack(panel) ?: panel
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
            // ① 顶部栏（ADR-14 层①）：取消 ｜ 撤销/重做（**本卡留位**，置灰不可点）｜ 覆盖 ｜ 完成
            // 「完成」= 黄色圆角矩形 + 黑字（主操作 = 另存新片，语义与原底部主按钮一致）；
            // 「覆盖」= 深灰圆角 + 白字（危险操作，仍走二次确认对话框）。
            EditorTopBar(
                enabled = !exporting && phase == EditorPhase.Ready,
                exporting = exporting,
                onCancel = { onCancel() },
                onGallery = onOpenGallery,
                onOverwrite = { confirmOverwrite = true },
                onDone = { exportNow(false) },
            )

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
                        // 看原图：显示输入像素 —— 由 LaunchedEffect(showOriginal) 在 IO
                        // 线程从常驻 buffer 重建（见 EditorSource.displayBitmap），主线程零成本；
                        // 还没就绪时退回显示成片，绝不白屏
                        val orig = if (showOriginal) origBmp else null
                        if (bmp != null) {
                            // E2-17：预览区**不再注册任何手势** —— 长按画面看原图已按用户要求
                            // 移除（"也不用长按图片查看原图"）。显示哪张只由右下角按钮的按住态
                            // 决定（[OriginalViewState]），局部模式下则由拖动定位独占。
                            val image = remember(bmp, orig, showOriginal) {
                                (if (showOriginal && orig != null) orig else bmp).asImageBitmap()
                            }
                            // E2-7：局部模式下的拖动定位。坐标换算（含 letterbox 黑边）与轮廓
                            // 绘制**共用** EditorExport.kt 里的同一套纯函数 —— 两处各写一份，
                            // 一旦有偏差用户看到的就是"拖了没对准"。
                            val localMode = params?.let { localTypeUi(it[79]) } ?: LocalMode.OFF
                            val dragMode = localMode != LocalMode.OFF
                            val srcSize = source?.let { it.w to it.h }
                            val rect = remember(previewSize, srcSize) {
                                val (sw, sh) = srcSize ?: (1 to 1)
                                imageFitRect(
                                    previewSize.width.toFloat(), previewSize.height.toFloat(), sw, sh,
                                )
                            }
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .onSizeChanged { previewSize = it }
                                    .pointerInput(dragMode) {
                                        if (dragMode) {
                                            detectDragGestures(
                                                onDragStart = { localDragging = true },
                                                onDragEnd = {
                                                    localDragging = false
                                                    onSliderRelease()
                                                },
                                                // 取消（手势被抢）也要收尾：否则轮廓一直停在
                                                // "正在拖动"的高亮态
                                                onDragCancel = { localDragging = false },
                                                onDrag = { change, _ ->
                                                    change.consume()
                                                    val (u, v) = localCenterFromScreen(
                                                        change.position.x, change.position.y, rect,
                                                    )
                                                    dragLocalCenter(u, v)
                                                },
                                            )
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    bitmap = image,
                                    contentDescription = if (showOriginal) "原图" else "成片预览",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                // ★ 辅助轮廓（E2-7）：**Compose 叠加层**，与 Image 同尺寸的
                                // 兄弟节点 —— 导出走的是引擎输出位图，这里的东西**结构上
                                // 不可能**进导出（不是"记得别画进去"）。拖动中只画它、不渲染
                                //（零引擎开销、实时反馈），松手才 rerender。
                                val film = params
                                if (dragMode && film != null) {
                                    // H/W（不是 W/H）：正圆修正要求 ry = rx×W/H（见 radialOutline 的 KDoc）
                                    val aspect = source?.let { it.h.toFloat() / it.w } ?: 1f
                                    val (cu, cv) = localCenterUi(film[80], film[81])
                                    val outline = if (localMode == LocalMode.RADIAL) {
                                        radialOutline(
                                            cu, cv, localRadiusUi(film[82]),
                                            localFeatherUi(film[83]), rect, aspect,
                                        )
                                    } else {
                                        linearOutline(
                                            cu, cv, localWidthUi(film[85]), film[84],
                                            localFeatherUi(film[83]), rect,
                                        )
                                    }
                                    Canvas(Modifier.fillMaxSize()) {
                                        drawLocalOutline(outline, FilmTheme.Accent, localDragging)
                                    }
                                }
                                // 提示：**右上角**（研究 §3.5 —— 底部容易被手指遮）+ PC 式 1px 黑阴影
                                //（ADR-14 抄的 `BoldTextWithShadow`：文字叠在预览图上的可读性技巧）。
                                // E2-17：看原图时也保留提示（文案切换）—— 按钮的按下态 + 文案双保险，
                                // 用户任何时候都知道自己看的是哪张；未按时的文案指向**按钮**
                                //（预览区已不看原图，文案再写"按住画面"就是骗人）。
                                Text(
                                    when {
                                        showOriginal -> "原图（松手返回效果）"
                                        dragMode && localDragging -> "拖动定位中…松手生效"
                                        dragMode -> "拖动画面移动局部区域"
                                        else -> "按住右下角按钮看原图"
                                    },
                                    color = if (dragMode && localDragging) FilmTheme.Accent else FilmTheme.CreamDim,
                                    fontSize = 11.sp,
                                    style = TextStyle(shadow = TextShadow),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 8.dp, end = 12.dp),
                                )
                                // ★ E2-15 原图按钮（用户："查看原图给个按钮"）→ E2-17 改成**按住看**
                                //（用户："按住按钮 = 看原图；松手 = 恢复效果图"，明确否掉点一下切换）。
                                // 局部模式下**隐藏**：拖动定位要用整个预览区（含右下角），按钮会抢走那
                                // 一片的按下事件 ⇒ 用户在那儿拖不动（父代理已提示这个冲突）。
                                if (!dragMode) {
                                    OriginalPressButton(
                                        pressed = origView.pressed,
                                        onPress = { v -> origView = origView.press(v) },
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(end = 14.dp, bottom = 14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ③④⑤⑥ 参数区（E2-10）：参数名 / 单滑杆 / 参数图标行 / 底部导航，四层顺序照
            // ADR-14 的 Filmix 结构。仍嵌在预览与底栏之间、**不是覆盖式浮层**：暗角最明显的
            // 地方是四角，浮层挡住底边两点等于让用户盲调（E1-4 起的设计约束，本卡不改）。
            //
            // ★ E2-15 可收起（用户反馈："系统返回手势要可以收起编辑面板…收不起来"）：收起后
            // 整段不组合 ⇒ 上面的预览区（weight 1f）自动吃满剩余空间；底部只留一条把手，
            // 上拉/点击即可恢复（展开恢复原状态：tab / 选中参数 / 滑杆读数都在 remember 里）。
            //
            // ★ E2-17 过渡（用户："面板上下拉加过渡动画"，此前是瞬变）：[AnimatedContent] 一次
            // 做三件事 —— 内容交叉（面板 ⇄ 收起条）、**尺寸过渡**（[SizeTransform]：全高 ⇄ 42dp）、
            // 位移 + 淡入淡出。尺寸过渡是关键：预览区是 `weight(1f)`，容器高度每帧都在变，预览区
            // 跟着**平滑伸缩**（而不是"面板瞬间没了、预览区跳一下"）。全部用同一条 240ms 减速曲线
            //（[PANEL_ANIM_MS]）—— 位移/淡入/尺寸若各配各的时长，会出现"内容先到位、容器后展开"的撕裂。
            //
            // 为什么不复用 FilmTheme.FilmMotion.panelEnter/panelExit：那套是**浮层**动效（相机页的
            // 覆盖式面板），只做位移 + 淡入淡出、**不改变布局尺寸**；编辑器面板嵌在布局流里，没有
            // 尺寸过渡就必然跳变。本卡限定只改本文件，故这套转场定义在这里（未动 FilmTheme.kt）。
            //
            // ⚠ 动画期间**不触发渲染**：这里只做布局与组合，渲染调用点仍是打开首帧 / 滑杆松手 /
            // 离散操作（见 [rerender] 的 KDoc）；面板子树里没有任何 `rerender()`。
            AnimatedContent(
                targetState = panel.expanded,
                transitionSpec = {
                    val slide = tween<IntOffset>(PANEL_ANIM_MS, easing = FastOutSlowInEasing)
                    val fade = tween<Float>(PANEL_ANIM_MS, easing = FastOutSlowInEasing)
                    val size = tween<IntSize>(PANEL_ANIM_MS, easing = FastOutSlowInEasing)
                    if (targetState) {
                        // 展开：面板从下方推上来，收起条往上退。位移取内容高度的 1/3 —— 面板
                        // 约 380dp ⇒ ≈125dp，正贴 ADR-14 记的 PC 源码面板出入场位移；收起条
                        // 只有 42dp 高，自适应成 ≈14dp，不会从屏幕外飞进来。
                        (slideInVertically(slide) { it / 3 } + fadeIn(fade)) togetherWith
                            (slideOutVertically(slide) { -it / 3 } + fadeOut(fade))
                    } else {
                        // 收起：面板往下退，收起条从上方落回
                        (slideInVertically(slide) { -it / 3 } + fadeIn(fade)) togetherWith
                            (slideOutVertically(slide) { it / 3 } + fadeOut(fade))
                    } using SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> size })
                },
                label = "editorPanel",
            ) { expanded ->
                if (expanded) {
                    EditorPanel(
                        group = tab,
                        onGroup = { tab = it },
                        params = params,
                        refreshTick = panelRefresh,
                        status = status,
                        baseHint = editorHint(
                            status = "",
                            hasSnapshot = hasSnapshot,
                            lutLabel = lutId?.let { id -> LutLibrary.byId(context, id)?.label ?: id },
                            lutK = lutK,
                        ),
                        lutId = lutId,
                        previews = lutPreviews,
                        snapLutId = snapLutId,
                        snapLutK = snapLutK,
                        onPickLut = { e -> pickLut(e) },
                        onApplySnapshot = { applySnapshotStyle() },
                        localMode = params?.let { localTypeUi(it[79]) } ?: LocalMode.OFF,
                        localCenter = params?.let { localCenterUi(it[80], it[81]) } ?: (0.5f to 0.5f),
                        localOn = params?.let { localActive(it) } ?: false,
                        onLocalMode = { m -> setLocalType(m) },
                        onCommit = { p, v -> commit(p, v) },
                        onFinished = { onSliderRelease() },
                        onResetParam = { p -> resetParam(p) },
                        onResetGroup = { g -> resetGroup(g) },
                        onHandleDrag = { dy -> onHandleDrag(dy) },
                        onTogglePanel = { panel = panel.toggled() },
                    )
                } else {
                    CollapsedPanelBar(
                        onDragEnd = { dy -> onHandleDrag(dy) },
                        onTogglePanel = { panel = panel.toggled() },
                    )
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
                        "会用当前编辑结果替换这张原片，原图无法恢复。\n想保留原片请改用「完成」（另存为新片）。",
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

        // ★ E2-15 「取消」的二次确认（用户反馈："如果改过参数，弹一句『放弃本次修改？』→ 确认才退出"）：
        // 只有 [discardNeedsConfirm] 为真（= 参数与打开时不同）才会走到这里；无修改时「取消」
        // 直接退出，不弹窗打扰。退出后回哪一页由路由层决定（E2-13 查看页），本页只管"放弃 + 退出"。
        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                shape = RoundedCornerShape(18.dp),
                title = { Text("放弃本次修改？", color = FilmTheme.Cream, fontFamily = FilmTheme.Serif) },
                text = {
                    Text(
                        "当前的调整还没有保存，退出后不会保留。",
                        color = FilmTheme.CreamDim,
                        fontSize = 13.sp,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDiscard = false
                        onBack()
                    }) { Text("放弃", color = FilmTheme.Accent, fontFamily = FilmTheme.Mono) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDiscard = false }) {
                        Text("继续编辑", color = FilmTheme.CreamDim, fontFamily = FilmTheme.Mono)
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

    // LUT 卡片预览按需生成（一次性缓存，见 LutPreview）：切到「色彩」组时才做 ——
    // 面板常驻后不再有"展开面板"这个时机，用 tab 代替（其余组不生成，进编辑器更快）。
    LaunchedEffect(tab) {
        if (tab != EditorTab.COLOR || lutPreviews.isNotEmpty()) return@LaunchedEffect
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
 * 画局部调整的辅助轮廓（E2-7）。**纯预览叠加层**：导出走引擎输出位图，本函数只被
 * 预览区的 Canvas 调用 ⇒ "轮廓不进导出"是结构性保证，不靠约定。
 *
 * 几何全部来自 [com.filmkit.app.camera.LocalOutline]（纯函数算好的屏幕像素坐标，与
 * 坐标换算共用同一套换算）⇒ 轮廓中心与引擎 mask 中心重合。
 *
 * 画法：
 *  - **径向**：实线椭圆（正圆修正后 rx/ry 由像素宽高比决定 ⇒ 屏幕上是正圆）+
 *    羽化带外圈虚线（`d = 1+feather`，只在 feather > 0 时画）
 *  - **线性**：两条实线边线（`d = ±1`，垂直于法线 [84]）+ 两侧羽化外沿虚线（`d = ±(1+feather)`）
 *  - 拖动中加粗并提亮（用户能看出"正在定位"）
 */
private fun DrawScope.drawLocalOutline(outline: LocalOutline, color: Color, dragging: Boolean) {
    val alpha = if (dragging) 1f else 0.7f
    val main = color.copy(alpha = alpha)
    val dash = color.copy(alpha = alpha * 0.55f)
    val w = (if (dragging) 3f else 2f) * density
    val c = Offset(outline.cx, outline.cy)
    if (!outline.linear) {
        drawOval(
            color = main,
            topLeft = Offset(outline.cx - outline.rx, outline.cy - outline.ry),
            size = Size(outline.rx * 2f, outline.ry * 2f),
            style = Stroke(width = w),
        )
        if (outline.feather > 0f) {
            val k = 1f + outline.feather
            drawOval(
                color = dash,
                topLeft = Offset(outline.cx - outline.rx * k, outline.cy - outline.ry * k),
                size = Size(outline.rx * 2f * k, outline.ry * 2f * k),
                style = Stroke(width = w * 0.7f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f * density, 8f * density))),
            )
        }
        return
    }
    // 线性：边线方向 = (−sin a, cos a)（由纯函数给出），两条线沿**法线**偏移 ±half
    val nx = -outline.lineDy
    val ny = outline.lineDx
    val ext = 4000f // 足够覆盖任何屏幕（超出部分由 Canvas 裁剪）
    listOf(1f, -1f).forEach { sgn ->
        val off = sgn * outline.half
        drawLine(
            color = main,
            start = Offset(c.x + nx * off - outline.lineDx * ext, c.y + ny * off - outline.lineDy * ext),
            end = Offset(c.x + nx * off + outline.lineDx * ext, c.y + ny * off + outline.lineDy * ext),
            strokeWidth = w,
        )
        if (outline.feather > 0f) {
            val off2 = sgn * outline.half * (1f + outline.feather)
            drawLine(
                color = dash,
                start = Offset(c.x + nx * off2 - outline.lineDx * ext, c.y + ny * off2 - outline.lineDy * ext),
                end = Offset(c.x + nx * off2 + outline.lineDx * ext, c.y + ny * off2 + outline.lineDy * ext),
                strokeWidth = w * 0.7f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f * density, 8f * density)),
            )
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
    lutK <= 0f -> "当前：$lutLabel · 强度 0（与原片一致）"
    else -> "当前：$lutLabel · 强度 ${formatZero150(lutK)}（叠加在原片之上）"
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


// ─────────────────────────────────────────────────────────────────────────────
// 编辑器面板骨架（E2-10 / ADR-14）：Filmix 五层结构
//   ① 顶部栏：取消 ｜ 撤销/重做（**留位**，置灰不可点）｜ 覆盖 ｜ 完成（黄底黑字）—— [EditorTopBar]
//   ② 预览区：weight(1f)，约屏高 55%（在 EditorScreen 里，面板常驻但不遮挡画面）
//   ③ 参数名：居中大字（当前选中参数）+ 1px 黑阴影 —— [ParamNameRow]
//   ④ 单滑杆：**一屏只出当前选中参数的那一条**，满宽 + 密集刻度 + 中点标记 + 物理量读数 —— [EditorSlider]
//   ⑤ 参数图标行：横向滚动圆形按钮（未选中 = 深灰圆 + 白图标；选中 = 深灰圆 + 黄描边 + 黄图标）—— [ParamIconRow]
//   ⑥ 底部导航：调整 / 色彩 / 暗角 / 局部（预设 / 裁剪**置灰留位**）—— [EditorTabBar]
//
// 三条关键修正（"专业感"的真正来源，ADR-14「我们的差距」头三条）：
//   1. **满宽滑杆 + 标签在上方**（不再是"标签｜细滑杆｜百分比"的三段式表单行）
//   2. **中点标记**（对比度 / 饱和度的"不变点"必须看得见）—— 位置由纯函数 `pivotFraction` 算
//   3. **读数用物理量**（曝光 `+1.0 EV` 而不是 `100%`）—— 格式化纯函数在 EditorExport.kt
//
// 一屏只出一条滑杆的理由（ADR-14）：Filmix 是"底部导航（大类）→ 参数图标行（具体参数）→
// 只显示当前选中参数的那一条滑杆"。纵向堆 7 条滑杆是"设置页"的组织方式，不是"调色台"的。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 面板文字阴影（ADR-14 从 PC 源码抄的 `BoldTextWithShadow`：dx / dy / radius = 1px）。
 * 参数名 / 读数 / 提示都可能叠在预览图上，1px 黑阴影是"叠加时的可读性技巧"。
 */
private val TextShadow = Shadow(Color.Black, Offset(1f, 1f), 1f)

/** 禁用态前景（顶栏「撤销/重做」、底栏「预设/裁剪」留位用）：30% 奶油白，不新造色值 */
private val DisabledInk = FilmTheme.CreamDim.copy(alpha = 0.35f)

/**
 * 底部导航的功能大类（E2-10）。**只有引擎已实现并验收的组才进枚举**（ADR-12：不许假控件）。
 * 预设（E3 预设管理）/ 裁剪（E2-12）在 [EditorTabBar] 里置灰留位，不进枚举、不可点。
 */
private enum class EditorTab(val label: String, val icon: ImageVector) {
    ADJUST("调整", Icons.Outlined.Tune),
    COLOR("色彩", Icons.Outlined.Palette),
    VIGNETTE("暗角", Icons.Outlined.Vignette),
    LOCAL("局部", Icons.Outlined.CenterFocusStrong),
}

/**
 * 一个可调参数（E2-10 的数据驱动核心）：图标行的一项 + 唯一那条滑杆 + 「重置」的目标。
 *
 * 为什么做成表：E2-3 / E2-7 的写法是"每个参数一个 setter + 面板签名里一个 lambda + 调用点
 * 一处绑定"（19 个参数 × 3 处）—— 新增参数漏改任何一处，表现都是"滑杆动了、画面不动"
 *（ADR-12 的假滑杆）。表里每项自带槽位读写（[read] / [write] 都是 EditorExport.kt 的纯函数），
 * 加一个参数只加一行。
 */
private class EditorParam(
    /** 稳定 key：滑杆 state 的 `key(...)`（换参数必须重建，否则新参数沿用上一条的读数） */
    val key: String,
    val label: String,
    /** 圆形按钮里的图标。与 [letter] 二选一 */
    val icon: ImageVector? = null,
    /** 字母图标（白平衡 R/G/B）：不为三个通道硬凑三个语义不符的图标，也不新造颜色 */
    val letter: String? = null,
    val range: ClosedFloatingPointRange<Float>,
    /** 基准值（UI 单位）= **不变点 / 默认值**：中点标记画在这里，「重置」回到这里 */
    val pivot: Float,
    val format: (Float) -> String,
    val read: (DoubleArray) -> Float,
    val write: (DoubleArray, Float) -> Unit,
)

/**
 * 「调整」组（E2-3 的「基础」组改名；E2-14 加影调分区四条；E2-15 纳色温/色调）：
 * 曝光 [0] / 色温 [88] / 色调 [89] / 对比度 [4] / 高光 [90] / 阴影 [91] /
 * 白色色阶 [92] / 黑色色阶 [93] / 饱和度 [20..22] / 清晰度 [78] / 红 [1] / 绿 [2] / 蓝 [3]。
 * 槽位语义与恒等值见 `EditorExport.kt` 的映射段。
 *
 * **排序理由（E2-15，用户要求"按逻辑排但要说明理由"）**——按用户调色的**实际决策顺序**：
 *  1. **曝光**：先定整体明暗 —— 后面所有参数（尤其影调分区与饱和度）的观感都建立在它之上；
 *  2. **色温 → 色调**：白平衡两条紧跟曝光。它们决定"这张照片的色偏"，而色偏会污染后面
 *     每一个关于颜色的判断（饱和度、清晰度看起来"发灰/发黄"常常只是白平衡没对）；
 *     两条**必须相邻**（同一套白平衡的两轴，分开找不着）；
 *  3. **对比度**：全局反差 —— 白平衡定色之后才谈得上"反差合不合适"；
 *  4. **高光 → 阴影 → 白色色阶 → 黑色色阶**：四个影调分区按**从亮部到暗部**（Lightroom
 *     Basic 面板的既有顺序，用户肌肉记忆最省）；都在对比度之后，因为它们是"对比度不够
 *     精细"时的分区微调；
 *  5. **饱和度**：全局色彩强度 —— 影调定完再谈色彩浓度（影调变了，饱和的观感也变）；
 *  6. **清晰度**：局部质感（中频），放在全局色彩之后 —— 它是"最后一道质感调味"；
 *  7. **红 / 绿 / 蓝**：手动通道增益放最后。它们是白平衡的"手动挡"，与色温/色调功能重叠，
 *     属于进阶微调 —— 放前面会诱导用户用三条增益去调色偏（比色温/色调难用得多）。
 *
 * 读数口径（E2-10 全卡统一，格式化函数都在 `EditorExport.kt`）：
 *  - **曝光** `+1.0 EV`（物理量，不再是 `100%`）；中点 = `0.0 EV`
 *  - **色温** `5000K`（**物理量 K，不是 mired**）；中点 = `6500K`
 *  - **色调** `+12`（±100，正 = 品红）；中点 = `0`
 *  - **红/绿/蓝** `+12`（相对增益 ±100）；中点 = `0`（= 增益 1.00 不变）
 *  - **对比度 / 饱和度** `+12`（±100）；中点 = `0`（= 1.00 不变）
 *  - **清晰度** `35`（0..100 整数，0 = 关）；基准在最左端 ⇒ 不画中点标记（端点本身就是标记）
 *  - **高光 / 阴影 / 白色色阶 / 黑色色阶** `+50`（±100，正 = 该分区提亮）；中点 = `0`
 *
 * ⚠️ 色温滑杆的值**直接就是 K**（右移 = K 大 = 画面变暖）—— 槽位 [88] 存的是 mired 偏移，
 * 方向相反，换算在 `EditorExport.kt` 的 `tempSlot` / `tempUi` 里（见那里的方向约定）。
 */
private val AdjustParams: List<EditorParam> = listOf(
    EditorParam(
        key = "ev", label = "曝光", icon = Icons.Outlined.Exposure,
        range = EXP_UI_RANGE, pivot = 0f,
        format = { formatEv(it) },
        read = { expUi(it[0]) },
        write = { p, v -> p[0] = expSlot(v) },
    ),
    // ── E2-15：色温 / 色调从「色彩」组**移进来**（用户反馈："色温方向正确，放的位置不对，
    // 怎么放 lut 里了应该是调整里面"）。它们是白平衡的两条，与 [1..3] 通道增益同属基础调整；
    // 两条**必须在一起**（分开会让用户找不到另一半）。
    EditorParam(
        key = "temp", label = "色温", icon = Icons.Outlined.Thermostat,
        range = TEMP_UI_RANGE, pivot = TEMP_UI_PIVOT,
        format = { formatKelvin(it) },
        read = { tempUi(it[88]) },
        write = { p, v -> p[88] = tempSlot(v) },
    ),
    EditorParam(
        key = "tint", label = "色调", icon = Icons.Outlined.InvertColors,
        range = TINT_UI_RANGE, pivot = ZERO_UI_PIVOT,
        format = { formatOffset100(it, ZERO_UI_PIVOT) },
        read = { tintUi(it[89]) },
        write = { p, v -> p[89] = tintSlot(v) },
    ),
    EditorParam(
        key = "contrast", label = "对比度", icon = Icons.Outlined.Contrast,
        range = CONTRAST_UI_RANGE, pivot = 1f,
        format = { formatOffset100(it, 1f) },
        read = { contrastUi(it[4]) },
        write = { p, v -> p[4] = contrastSlot(v) },
    ),
    // ── E2-14：影调分区四条（[90..93]，引擎 `kFsStyle` 的 `uToneOn` 段）────────────
    // 量程 −1..+1 直通槽位，恒等 0；读数 = ±100 整数（`+50` / `0` / `-50`），中点 0。
    // 方向：正 = 该分区提亮（引擎 `g = 1 + hl·l² + sh·s² + wh·l³ + bl·s³`，乘性保色度比）。
    EditorParam(
        key = "highlights", label = "高光", icon = Icons.Outlined.LightMode,
        range = TONE_UI_RANGE, pivot = ZERO_UI_PIVOT,
        format = { formatOffset100(it, ZERO_UI_PIVOT) },
        read = { toneUi(it[90]) },
        write = { p, v -> p[90] = toneSlot(v) },
    ),
    EditorParam(
        key = "shadows", label = "阴影", icon = Icons.Outlined.DarkMode,
        range = TONE_UI_RANGE, pivot = ZERO_UI_PIVOT,
        format = { formatOffset100(it, ZERO_UI_PIVOT) },
        read = { toneUi(it[91]) },
        write = { p, v -> p[91] = toneSlot(v) },
    ),
    EditorParam(
        key = "whites", label = "白色色阶", icon = Icons.Outlined.Brightness7,
        range = TONE_UI_RANGE, pivot = ZERO_UI_PIVOT,
        format = { formatOffset100(it, ZERO_UI_PIVOT) },
        read = { toneUi(it[92]) },
        write = { p, v -> p[92] = toneSlot(v) },
    ),
    EditorParam(
        key = "blacks", label = "黑色色阶", icon = Icons.Outlined.Brightness4,
        range = TONE_UI_RANGE, pivot = ZERO_UI_PIVOT,
        format = { formatOffset100(it, ZERO_UI_PIVOT) },
        read = { toneUi(it[93]) },
        write = { p, v -> p[93] = toneSlot(v) },
    ),
    // 全局色彩强度 → 质感 → 手动通道增益（顺序理由见 [AdjustParams] 的 KDoc）
    EditorParam(
        key = "sat", label = "饱和度", icon = Icons.Outlined.WaterDrop,
        range = SAT_UI_RANGE, pivot = 1f,
        format = { formatOffset100(it, 1f) },
        // 一个滑杆同写 [20][21][22] 三槽（ADR §5.6④ 的硬约束，见 writeSaturation 的 KDoc）
        read = { satUi(it[21]) },
        write = { p, v -> writeSaturation(p, v) },
    ),
    EditorParam(
        key = "clarity", label = "清晰度", icon = Icons.Outlined.Details,
        range = CLARITY_UI_RANGE, pivot = 0f,
        format = { formatZero100(it) },
        read = { clarityUi(it[78]) },
        write = { p, v -> p[78] = claritySlot(v) },
    ),
    EditorParam(
        key = "wbR", label = "红", letter = "R",
        range = WB_GAIN_UI_RANGE, pivot = 1f,
        format = { formatOffset100(it, 1f) },
        read = { wbGainUi(it[1]) },
        write = { p, v -> p[1] = wbGainSlot(v) },
    ),
    EditorParam(
        key = "wbG", label = "绿", letter = "G",
        range = WB_GAIN_UI_RANGE, pivot = 1f,
        format = { formatOffset100(it, 1f) },
        read = { wbGainUi(it[2]) },
        write = { p, v -> p[2] = wbGainSlot(v) },
    ),
    EditorParam(
        key = "wbB", label = "蓝", letter = "B",
        range = WB_GAIN_UI_RANGE, pivot = 1f,
        format = { formatOffset100(it, 1f) },
        read = { wbGainUi(it[3]) },
        write = { p, v -> p[3] = wbGainSlot(v) },
    ),
)

/**
 * 「色彩」组的**引擎参数**（E2-14；E2-15 只剩褪色 [94]）：褪色。
 *
 * **不依赖 LUT**（引擎侧这条在 `kFsStyle` 内独立生效）⇒ 没选 LUT 时它照样可调 —— 这也是
 * 本组从"未选 LUT 就空表"变成常驻的原因。LUT 强度 [34] 是单独一条（[LutStrengthParam]），
 * 选了 LUT 才进表。
 *
 * 色温 [88] / 色调 [89] 已随 E2-15 移入「调整」组（用户反馈见 [AdjustParams] 的 KDoc）；
 * **褪色留在这里** —— 它是风格化参数（抬黑场、做"褪色胶片感"），不是基础调整。
 *
 * 读数口径：**褪色** `35`（0..100 整数，0 = 关）；基准在最左端 ⇒ 不画中点标记。
 */
private val ColorParams: List<EditorParam> = listOf(
    EditorParam(
        key = "fade", label = "褪色", icon = Icons.Outlined.Gradient,
        range = FADE_UI_RANGE, pivot = ZERO_UI_PIVOT,
        format = { formatZero100(it) },
        read = { fadeUi(it[94]) },
        write = { p, v -> p[94] = fadeSlot(v) },
    ),
)

/**
 * LUT 强度 [34]（E2-1）：**只有选了 LUT 才有意义** ⇒ 未选时整条不进参数表（ADR-12：不给假滑杆）。
 *
 * 量程 **0..1.5**（E2-15 用户反馈："LUT强度应该统一可调至150"）：与拍照页的 LUT 强度滑杆
 * 同一量程（`RelativeSlider` 默认就是 `0f..1.5f`）。读数 `150`（整数 0..150，[formatZero150]）；
 * 基准 0 = 关 ⇒ 不画中点标记。
 */
private val LutStrengthParam = EditorParam(
    key = "lutK", label = "强度", icon = Icons.Outlined.PhotoFilter,
    range = LUT_STRENGTH_UI_RANGE, pivot = 0f,
    format = { formatZero150(it) },
    read = { p -> if (p[34].isFinite()) p[34].toFloat().coerceIn(0f, 1.5f) else 0f },
    write = { p, v -> p[34] = v.coerceIn(0f, 1.5f).toDouble() },
)

/**
 * 「暗角」组（E2-1）：强度 [66] / 半径 [67] / 羽化 [68]。量程与读数口径：
 *  - **强度** `50`（0..100，0 = 关，引擎侧 [66] ≥ 0 整段跳过）
 *  - **半径 / 羽化** `80` / `50`（0..200 —— darktable 的 `scale` / `falloff_scale` 本身就是
 *    百分比语义）；基准 = darktable 默认 80 / 50（画中点标记，告诉用户"默认在哪"）
 */
private val VignetteParams: List<EditorParam> = listOf(
    EditorParam(
        key = "vigStrength", label = "强度", icon = Icons.Outlined.Vignette,
        range = 0f..1f, pivot = 0f,
        format = { formatZero100(it) },
        read = { vigStrengthUi(it[66]) },
        write = { p, v -> p[66] = vigStrengthSlot(v) },
    ),
    EditorParam(
        key = "vigRadius", label = "半径", icon = Icons.Outlined.RadioButtonUnchecked,
        range = 0f..2f, pivot = 0.8f,
        format = { formatZero200(it) },
        read = { vigRadiusUi(it[67]) },
        write = { p, v -> p[67] = vigRadiusSlot(v) },
    ),
    EditorParam(
        key = "vigFeather", label = "羽化", icon = Icons.Outlined.BlurOn,
        range = 0f..2f, pivot = 0.5f,
        format = { formatZero200(it) },
        read = { vigFeatherUi(it[68]) },
        write = { p, v -> p[68] = vigFeatherSlot(v) },
    ),
)

/**
 * 「局部」组的六条参数（E2-7 的滑杆，一个没少），**按模式过滤**（ADR-12：径向模式下"角度"
 * 对画面没有任何作用，就不该出现在界面上）：
 *  - 径向：半径 / 羽化 / 曝光 / 饱和度
 *  - 线性：角度 / 宽度 / 羽化 / 曝光 / 饱和度
 *  - 未选模式：全部六条（用户可以先设参数再选模式）
 *
 * 中心 [80][81] 没有滑杆 —— 定位在预览图上拖动完成（E2-7 的核心交互，本卡保留）。
 *
 * 读数口径：半径/宽度/羽化 `35`（0..100）；**角度 `90°`**（方向用度数，E2-7 定的口径）；
 * 曝光 `+1.0 EV`；饱和度 `+12`（±100）。
 */
private fun localParams(mode: Int): List<EditorParam> = buildList {
    if (mode != LocalMode.LINEAR) {
        add(
            EditorParam(
                key = "locRadius", label = "半径", icon = Icons.Outlined.RadioButtonUnchecked,
                range = LOCAL_RADIUS_UI_RANGE, pivot = 0.35f,
                format = { formatZero100(it) },
                read = { localRadiusUi(it[82]) },
                write = { p, v -> p[82] = localRadiusSlot(v) },
            ),
        )
    }
    if (mode != LocalMode.RADIAL) {
        add(
            EditorParam(
                key = "locAngle", label = "角度", icon = Icons.AutoMirrored.Outlined.RotateRight,
                range = LOCAL_ANGLE_UI_RANGE, pivot = 0f,
                format = { formatDegree(it) },
                read = { localAngleUi(it[84]) },
                write = { p, v -> p[84] = localAngleSlot(v) },
            ),
        )
        add(
            EditorParam(
                key = "locWidth", label = "宽度", icon = Icons.Outlined.SwapHoriz,
                range = LOCAL_WIDTH_UI_RANGE, pivot = 0.25f,
                format = { formatZero100(it) },
                read = { localWidthUi(it[85]) },
                write = { p, v -> p[85] = localWidthSlot(v) },
            ),
        )
    }
    add(
        EditorParam(
            key = "locFeather", label = "羽化", icon = Icons.Outlined.BlurOn,
            range = LOCAL_FEATHER_UI_RANGE, pivot = 0.5f,
            format = { formatZero100(it) },
            read = { localFeatherUi(it[83]) },
            write = { p, v -> p[83] = localFeatherSlot(v) },
        ),
    )
    add(
        EditorParam(
            key = "locEv", label = "曝光", icon = Icons.Outlined.Exposure,
            range = LOCAL_EV_UI_RANGE, pivot = 0f,
            format = { formatEv(it) },
            read = { localEvUi(it[86]) },
            write = { p, v -> p[86] = localEvSlot(v) },
        ),
    )
    add(
        EditorParam(
            key = "locSat", label = "饱和度", icon = Icons.Outlined.WaterDrop,
            range = LOCAL_SAT_UI_RANGE, pivot = 1f,
            format = { formatOffset100(it, 1f) },
            read = { localSatUi(it[87]) },
            write = { p, v -> p[87] = localSatSlot(v) },
        ),
    )
}

/**
 * 局部组的说明行（E2-7 的提示文案，随面板重做挪到顶部提示位，内容一字未改）。
 * **A/B 语义**（ADR 交互规格第 6 条）：只有 [86] ≠ 0 或 [87] ≠ 1 时画面才变 —— 所以
 * "选了径向但没调强度"时预览不会变（正确行为），这行提示就是为了不让用户以为坏了。
 */
private fun localHint(mode: Int, active: Boolean, center: Pair<Float, Float>): String {
    val cx = (center.first * 100).roundToInt()
    val cy = (center.second * 100).roundToInt()
    return when {
        mode == LocalMode.OFF -> "选「径向」或「线性」后，在预览上拖动定位"
        !active -> "已选模式：调「曝光」或「饱和度」后画面才会变化（当前未调强度）"
        mode == LocalMode.RADIAL -> "预览上拖动移动中心（当前 $cx%, $cy%）· 半径 100 = 图像宽度"
        else -> "预览上拖动移动轴线（当前 $cx%, $cy%）· 角度 0° = 条带竖直"
    }
}

/**
 * ① 顶部栏（ADR-14 层①）：`取消 ｜ 撤销 / 重做（留位）｜ 覆盖 ｜ 完成`。
 *
 * 「完成」= **黄色圆角矩形 + 黑字**（主操作，= 原来的「另存为新片」，语义不变）；
 * 「覆盖」= 深灰圆角 + 白字（危险操作，仍走二次确认对话框）。
 *
 * **撤销 / 重做只留位、不做功能**（本卡范围）：图标给到、置灰、不注册点击 —— 撤销栈是后续卡。
 * 为什么不干脆不渲染：ADR-14 的截图结构里它们就在那个位置，留位让"专业面板"的骨架完整；
 * 置灰 + 无点击响应是**诚实的**（ADR-12 的精神：不做假控件），用户点了不会有任何反应。
 */
@Composable
private fun EditorTopBar(
    enabled: Boolean,
    exporting: Boolean,
    onCancel: () -> Unit,
    onGallery: () -> Unit,
    onOverwrite: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .height(40.dp)
                .pressScale(onTap = onCancel)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("取消", color = FilmTheme.Cream, fontSize = 15.sp)
        }
        // 相册入口（用户报的第 5 点，E1-4 起就在顶栏）：保留，只是换成图标钮
        Box(
            Modifier
                .size(40.dp)
                .pressScale(onTap = onGallery),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Menu,
                contentDescription = "相册",
                tint = FilmTheme.Cream,
                modifier = Modifier.size(20.dp),
            )
        }
        // 撤销 / 重做：**留位**（见 KDoc）
        listOf(
            Icons.AutoMirrored.Outlined.Undo to "撤销",
            Icons.AutoMirrored.Outlined.Redo to "重做",
        ).forEach { (ic, desc) ->
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Icon(
                    ic,
                    contentDescription = desc,
                    tint = DisabledInk,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (exporting) {
            CircularProgressIndicator(
                color = FilmTheme.Accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Box(
            Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(FilmTheme.ChipBg)
                .pressScale(enabled = enabled, onTap = onOverwrite)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "覆盖",
                color = if (enabled) FilmTheme.Cream else DisabledInk,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (enabled) FilmTheme.Accent else FilmTheme.ChipBg)
                .pressScale(enabled = enabled, onTap = onDone)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "完成",
                color = if (enabled) FilmTheme.OnAccent else DisabledInk,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * ③④⑤⑥ 参数区（E2-10）：四层顺序照 ADR-14。
 *
 * 选中参数是**派生**的（`list.firstOrNull { key == selKey } ?: list.first()`）：换组 / 换局部
 * 模式后原选中项可能不在新表里，自动回落到第一项 —— 不需要 `LaunchedEffect` 去"修"选中项，
 * 那种写法本身就是状态不同步的来源。
 *
 * E2-15：顶部多一条**拖拽把手**（[PanelHandle]）—— 用户不必依赖系统返回手势就能收起面板
 *（用户反馈："目前编辑面板打开所有照片点开默认展开收不起来"）。
 */
@Composable
private fun EditorPanel(
    group: EditorTab,
    onGroup: (EditorTab) -> Unit,
    params: DoubleArray?,
    refreshTick: Int,
    /** 导出结果 / 失败原因（非空时**优先**显示在提示行） */
    status: String,
    /** 无 status 时的默认提示（快照缺失 / 当前 LUT 状态，[editorHint] 的结果） */
    baseHint: String,
    lutId: String?,
    previews: Map<String, Bitmap>,
    snapLutId: String?,
    snapLutK: Float,
    onPickLut: (LutLibrary.Entry?) -> Unit,
    onApplySnapshot: () -> Unit,
    localMode: Int,
    localCenter: Pair<Float, Float>,
    localOn: Boolean,
    onLocalMode: (Int) -> Unit,
    onCommit: (EditorParam, Float) -> Unit,
    onFinished: () -> Unit,
    onResetParam: (EditorParam) -> Unit,
    onResetGroup: (EditorTab) -> Unit,
    /** 把手拖动落定（累计位移，px）：判定在 [EditorPanelState.afterDrag] */
    onHandleDrag: (Float) -> Unit,
    /** 把手点击 = 收起面板 */
    onTogglePanel: () -> Unit,
) {
    val context = LocalContext.current
    val list = when (group) {
        EditorTab.ADJUST -> AdjustParams
        // 色彩组（E2-15）：褪色（[ColorParams]）+ LUT 强度（选了 LUT 才有）。
        // 色温/色调已移入「调整」组 —— 它们是白平衡的两条，不属于"风格素材"这一类。
        EditorTab.COLOR -> if (lutId != null) ColorParams + LutStrengthParam else ColorParams
        EditorTab.VIGNETTE -> VignetteParams
        EditorTab.LOCAL -> localParams(localMode)
    }
    var selKey by remember { mutableStateOf<String?>(null) }
    val current = list.firstOrNull { it.key == selKey } ?: list.firstOrNull()
    val hint = when {
        status.isNotEmpty() -> status
        group == EditorTab.LOCAL -> localHint(localMode, localOn, localCenter)
        else -> baseHint
    }
    val film = params
    Column(
        Modifier
            .fillMaxWidth()
            .background(FilmTheme.PanelSolid)
            .navigationBarsPadding()
            .padding(bottom = 4.dp),
    ) {
        // ★ E2-15 拖拽把手：下拉收起 / 点击收起（展开态的把手在面板**顶部**）
        PanelHandle(
            onDragEnd = onHandleDrag,
            onToggle = onTogglePanel,
        )
        // 提示行（status / 快照缺失 / 当前 LUT / 局部说明）：11sp 居中 + 1px 黑阴影
        Text(
            hint,
            color = FilmTheme.CreamDim,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = TextStyle(shadow = TextShadow),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
        )
        // 色彩组专属：原片风格（只读）+ 一键套用 —— E2-1 的功能，一个都没丢
        if (group == EditorTab.COLOR) {
            SnapshotStyleRow(context, snapLutId, snapLutK, onApplySnapshot)
        }
        // 局部组专属：模式选择（关 / 径向 / 线性）
        if (group == EditorTab.LOCAL) LocalModeRow(localMode, onLocalMode)

        ParamNameRow(current, group, onResetParam, onResetGroup)

        // ④ 单滑杆：**只显示当前选中参数的那一条**（ADR-14 最关键的结构差异）
        if (current != null && film != null) {
            // key：换参数 / 重置 / 套用原片风格后重建滑杆 state（否则读数与槽位不符 = 假滑杆）
            key(current.key, refreshTick) {
                EditorSlider(current, film, onCommit, onFinished)
            }
        } else {
            // 无参数可调：参数还没加载完（E2-14 起色彩组常驻三条引擎参数，不再有"未选 LUT
            // 就空表"这一档）—— 给一句明确说明，**不给假滑杆**（ADR-12）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (film == null) "参数加载中…" else "本组暂无可调参数",
                    color = FilmTheme.CreamDim,
                    fontSize = 12.sp,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // ⑤ 参数图标行。色彩组**拆成两行**（E2-15）：LUT 一行（42 个素材，横滚很深）+
        // 本组参数一行（褪色 / 强度）。挤在同一行时，参数被埋在几十张 LUT 卡片之后 ——
        // 用户横滚半天找不到「褪色」，等于参数不存在（ADR 记的"LUT 与参数挤一行"问题）。
        if (group == EditorTab.COLOR) {
            LutIconRow(
                lutId = lutId,
                previews = previews,
                onPick = { e ->
                    onPickLut(e)
                    // 选了 LUT ⇒ 顺手选中「强度」（否则用户点完 LUT 还得自己找强度滑杆）；
                    // 选「无」时强度条不在表里，选中项自动回落到第一条
                    if (e != null) selKey = LutStrengthParam.key
                },
            )
            Spacer(Modifier.height(10.dp))
            ParamIconRow(list, current?.key) { selKey = it.key }
        } else {
            ParamIconRow(list, current?.key) { selKey = it.key }
        }
        Spacer(Modifier.height(8.dp))
        // ⑥ 底部导航
        EditorTabBar(group, onGroup)
    }
}

/**
 * ③ + 两个重置入口：`重置本组 ｜ 参数名（居中大字）｜ 重置`。
 *
 * 「重置本组」= E2-3 / E2-7 各组重置的统一入口（四条路径一个都没丢）；
 * 「重置」= 当前参数回基准值（E2-10 新增，研究 §3.5 的"单条重置"，用明确按钮而不是双击 ——
 * 双击手势与相对拖动的事件消费要真机验证，不值得为它冒滑杆拖不动的手感风险）。
 */
@Composable
private fun ParamNameRow(
    current: EditorParam?,
    group: EditorTab,
    onResetParam: (EditorParam) -> Unit,
    onResetGroup: (EditorTab) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "重置本组",
            color = FilmTheme.CreamDim,
            fontSize = 11.sp,
            modifier = Modifier
                .pressScale(onTap = { onResetGroup(group) })
                .padding(vertical = 6.dp, horizontal = 2.dp),
        )
        Text(
            current?.label ?: "—",
            color = FilmTheme.Cream,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            style = TextStyle(shadow = TextShadow),
            modifier = Modifier.weight(1f),
        )
        Text(
            "重置",
            color = if (current != null) FilmTheme.CreamDim else DisabledInk,
            fontSize = 11.sp,
            modifier = Modifier
                .pressScale(enabled = current != null, onTap = { current?.let(onResetParam) })
                .padding(vertical = 6.dp, horizontal = 2.dp),
        )
    }
}

/**
 * ④ 单滑杆（E2-10 的核心控件）：**满宽 + 密集刻度 + 中点标记 + 物理量读数**。
 *
 * 与旧形态（`StrengthRow` 的"标签｜细滑杆｜百分比"三段式）的三点区别，正好是 ADR-14 的
 * 三条关键修正：标签已在上一行居中大字；滑杆满宽；读数在上方右侧且是物理量。
 *
 * 拖动 state 留在本组件内（[RelativeSlider] 的相对拖动要求手势内累加、不依赖重组回流）；
 * 外层 `key(param.key, refreshTick)` 决定它何时重读槽位（换参数 / 重置 / 套用风格）。
 */
@Composable
private fun EditorSlider(
    param: EditorParam,
    params: DoubleArray,
    onCommit: (EditorParam, Float) -> Unit,
    onFinished: () -> Unit,
) {
    var v by remember { mutableFloatStateOf(param.read(params)) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                param.format(v),
                color = FilmTheme.Accent,
                fontSize = 13.sp,
                fontFamily = FilmTheme.Mono,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(shadow = TextShadow),
            )
        }
        RelativeSlider(
            value = v,
            onValueChange = { nv ->
                v = nv
                onCommit(param, nv)
            },
            onDragEnd = { onFinished() },
            range = param.range,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            rowHeight = 44.dp,
            ticks = true,
            // 中点标记的位置由**纯函数**算（EditorExport.kt 的 pivotFraction）：基准落在端点
            // 或量程外时返回 null = 不画（清晰度 0 / 暗角强度 0 都在最左端，画了只是糊）
            midFraction = pivotFraction(param.range, param.pivot),
            barThumb = true,
        )
    }
}

/** 局部模式选择（关 / 径向 / 线性）：复用拍摄页的 [SheetPill]，不另写一套 pill（两页观感必须一致） */
@Composable
private fun LocalModeRow(mode: Int, onMode: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            LocalMode.OFF to "关",
            LocalMode.RADIAL to "径向",
            LocalMode.LINEAR to "线性",
        ).forEach { (m, label) ->
            SheetPill(label = label, selected = mode == m) { onMode(m) }
        }
    }
}

/**
 * 「原片风格」只读展示 + 一键套用（E2-1 的功能，形态随面板重做而变、语义不变）：
 * 打开编辑器时 [34] 被强制 0（画面 = 原片），不给这个入口，快照里的 LUT 就等于白存了。
 */
@Composable
private fun SnapshotStyleRow(
    context: Context,
    snapLutId: String?,
    snapLutK: Float,
    onApply: () -> Unit,
) {
    // 快照里的 LUT 可能已被用户从库中删掉（byId = null）：如实说明，并且**不给**套用按钮
    //（点了注定失败的操作比没有按钮更糟）
    val snapEntry = snapLutId?.let { LutLibrary.byId(context, it) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when {
                snapLutId == null -> "原片未使用 LUT"
                snapEntry == null -> "原片风格：$snapLutId（已不在库中）"
                // 强度 0 = 当初就没叠加（编辑器导出的新片会落在这一档）：说"未叠加"比 "0" 清楚
                snapLutK <= 0f -> "原片风格：${snapEntry.label}（未叠加）"
                else -> "原片风格：${snapEntry.label} · 强度 ${formatZero150(snapLutK)}"
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
                    .pressScale(onTap = onApply)
                    .padding(4.dp),
            )
        }
    }
}

/** ⑤ 参数图标行：横向滚动，一屏约 5 个（ADR-14 层⑤） */
@Composable
private fun ParamIconRow(
    list: List<EditorParam>,
    selectedKey: String?,
    onSelect: (EditorParam) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(list, key = { it.key }) { p ->
            ParamChip(
                label = p.label,
                selected = p.key == selectedKey,
                icon = p.icon,
                letter = p.letter,
            ) { onSelect(p) }
        }
    }
}

/**
 * 色彩组的 **LUT 图标行**（E2-1 的 LUT 圆形按钮；E2-15 从"与参数同行"拆出来独立一行）：
 * 「无」+ 各 LUT（有预览图时显示圆形缩略图）。
 *
 * 为什么拆行（E2-15 用户反馈的连带修正）：LUT 有 42 个，横滚很深 —— 参数挤在同一行时
 * 用户要滑过几十张卡片才看得到「褪色」，等于参数不存在。拆开后 LUT 一行、参数一行，
 * 两者都一眼可见；代价只是面板高一点点（本组参数少，两行加起来仍矮于其他组的滑杆区）。
 */
@Composable
private fun LutIconRow(
    lutId: String?,
    previews: Map<String, Bitmap>,
    onPick: (LutLibrary.Entry?) -> Unit,
) {
    val context = LocalContext.current
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item(key = "__none__") {
            ParamChip(label = "无", selected = lutId == null, icon = Icons.Outlined.Close) { onPick(null) }
        }
        items(LutLibrary.entries(context), key = { it.id }) { e ->
            ParamChip(
                label = e.label,
                selected = lutId == e.id,
                bitmap = previews[e.id],
            ) { onPick(e) }
        }
    }
}

/**
 * 圆形参数按钮（ADR-14 层⑤）：**未选中 = 深灰圆 + 白图标；选中 = 深灰圆 + 黄色描边 + 黄色图标**，
 * 下方白色小字。56dp 圆（研究 §3.3.2 的 56–64dp 区间下沿：一屏能多放一个）。
 */
@Composable
private fun ParamChip(
    label: String,
    selected: Boolean,
    icon: ImageVector? = null,
    letter: String? = null,
    bitmap: Bitmap? = null,
    onTap: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(FilmTheme.ChipBg)
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) FilmTheme.Accent else FilmTheme.ChipBorder,
                    CircleShape,
                )
                .pressScale(pressedScale = 0.9f, onTap = onTap),
            contentAlignment = Alignment.Center,
        ) {
            when {
                bitmap != null -> Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                icon != null -> Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) FilmTheme.Accent else FilmTheme.Cream,
                    modifier = Modifier.size(24.dp),
                )
                letter != null -> Text(
                    letter,
                    color = if (selected) FilmTheme.Accent else FilmTheme.Cream,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FilmTheme.Mono,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = if (selected) FilmTheme.Accent else FilmTheme.Cream,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 64.dp),
        )
    }
}

/**
 * ⑥ 底部导航（ADR-14 层⑥）：调整 / 色彩 / 暗角 / 局部，**选中 = 黄色圆角矩形底 + 深色图标与文字**。
 *
 * 选中态用「强调色实底 + [FilmTheme.OnAccent] 前景」而不是"黄底 + 黄字"：黄底黄字的对比度
 * 约 1.4:1（WCAG 远不达标），实底 + 深字是 6.8:1。同一个"黄色圆角矩形底"的语言，可读性优先。
 *
 * 「预设」「裁剪」**置灰留位、不注册点击**：预设（E3 预设管理）与裁剪（E2-12）都还没实现 ——
 * ADR-12 不许假控件，所以它们只占位、点不动，颜色用 30% 奶油白（一眼看出是禁用态）。
 */
@Composable
private fun EditorTabBar(selected: EditorTab, onSelect: (EditorTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditorTab.entries.forEach { t ->
            val on = t == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) FilmTheme.Accent else Color.Transparent)
                    .pressScale(pressedScale = 0.94f, onTap = { onSelect(t) })
                    .padding(vertical = 6.dp),
            ) {
                Icon(
                    t.icon,
                    contentDescription = t.label,
                    tint = if (on) FilmTheme.OnAccent else FilmTheme.CreamDim,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    t.label,
                    color = if (on) FilmTheme.OnAccent else FilmTheme.CreamDim,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
        }
        listOf(
            Icons.Outlined.Collections to "预设",
            Icons.Outlined.Crop to "裁剪",
        ).forEach { (ic, label) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp),
            ) {
                Icon(
                    ic,
                    contentDescription = "$label（暂未开放）",
                    tint = DisabledInk,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(label, color = DisabledInk, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

/**
 * ★ E2-15 面板拖拽把手（用户反馈："目前编辑面板打开所有照片点开默认展开收不起来"）：
 * 一条小横条，**下拉收起 / 上拉展开**，**点击也能切换**（不是所有用户都想到去拖它）。
 *
 * 为什么不做成"点击预览区切换"：预览区在局部调整模式下已被拖动定位占用，再叠一个
 * "点击 = 收起"就会跟定位抢事件（父代理已明确排除该方案）。把手的触摸区只有 26dp 高、
 * 且贴在面板/屏幕边缘，谁也不抢。
 *
 * 手势：两个独立 pointerInput（tap 与 vertical drag 互不干扰）；拖动位移在组件内累计，
 * **松手才判定**（阈值判定是纯函数 [EditorPanelState.afterDrag]，JVM 单测覆盖）。
 */
@Composable
private fun PanelHandle(
    onDragEnd: (Float) -> Unit,
    onToggle: () -> Unit,
) {
    var accum by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .pointerInput(Unit) { detectTapGestures { onToggle() } }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { accum = 0f },
                    onDragEnd = {
                        onDragEnd(accum)
                        accum = 0f
                    },
                    onDragCancel = { accum = 0f },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        accum += dy
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // 把手本体：44×4dp 圆角小横条（一眼看出"这里可以拖"）
        Box(
            Modifier
                .width(44.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(FilmTheme.CreamDim.copy(alpha = 0.6f)),
        )
    }
}

/**
 * ★ E2-15 收起态底栏：只剩把手 + 一行提示（上拉 / 点击展开）。面板整段不组合 ⇒
 * 预览区（weight 1f）自动吃满剩余空间 —— "收起后预览区扩展到全屏"这条由此**结构性**成立。
 */
@Composable
private fun CollapsedPanelBar(onDragEnd: (Float) -> Unit, onTogglePanel: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(FilmTheme.PanelSolid)
            .navigationBarsPadding()
            .padding(bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PanelHandle(onDragEnd = onDragEnd, onToggle = onTogglePanel)
        Text("上拉或点击展开参数面板", color = FilmTheme.CreamDim, fontSize = 10.sp)
    }
}

/**
 * ★ E2-15 「看原图」按钮（用户："查看原图给个按钮"；形态照 Filmix 预览区右下角的圆形对比
 * 按钮）→ **E2-17 改成 press-and-hold**（用户验收反馈："不要点一下切原图、再点切回；按住
 * 按钮 = 看原图，松手 = 恢复效果图"）。
 *
 * 为什么不用全 App 的按压反馈规范 [pressScale]：它只暴露 `onTap`（点击回调），表达不了
 * "按下期间持续为真"——而这里的按下态必须**贯穿整个按住过程**（[OriginalViewState.pressed]
 * 就是"用户正按着"）。手势用 `onPress` + `tryAwaitRelease`（与 E2-15 长按预览区同一套 API）：
 * 手指滑出按钮或手势被抢时 `tryAwaitRelease` 返回 false，**同样**往下走 `onPress(false)` ——
 * 松手路径只有一条，不存在"按下了但没复位"（那会让画面永远停在原图，只能退出编辑器）。
 *
 * 按下态做三件事（用户必须一眼看出"按住了"）：黄底 + 深色图标 + 0.88 缩放（[PRESS_ANIM_MS]
 * 的短过渡，比按钮自身的 240ms 面板动画快得多 —— 按压反馈要跟手）。
 */
@Composable
private fun OriginalPressButton(
    pressed: Boolean,
    onPress: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = tween(PRESS_ANIM_MS),
        label = "originalButtonScale",
    )
    Box(
        modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(if (pressed) FilmTheme.Accent else FilmTheme.ChipBg.copy(alpha = 0.85f))
            .border(1.dp, if (pressed) FilmTheme.Accent else FilmTheme.ChipBorder, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onPress(true)
                    tryAwaitRelease()
                    onPress(false)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Compare,
            contentDescription = "按住查看原图",
            tint = if (pressed) FilmTheme.OnAccent else FilmTheme.Cream,
            modifier = Modifier.size(22.dp),
        )
    }
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
 * 面板收起/展开的**纯逻辑状态机**（E2-15，用户真机反馈："系统返回手势要可以收起编辑面板。
 * 目前编辑面板打开所有照片点开默认展开收不起来！"）。
 *
 * 为什么抽成不可变状态 + 纯函数：这三条规格错了用户一眼就能看见，但真机验证要装包点半天 ——
 * 抽出来 JVM 单测直接钉住（与 [SliderRenderGate] 同一套路）：
 *  1. **返回键分层**：展开 ⇒ 先收起面板（消费返回）；已收起 ⇒ 交宿主退出编辑器
 *     （[onBack] 返回 null = 不消费）。一次返回绝不会"面板还开着就直接退出"。
 *  2. **把手拖动**：累计位移超过阈值才切换（[afterDrag]）—— 轻微抖动不该收起面板。
 *  3. **点击把手**：显式切换（[toggled]）—— 用户反馈明确要求"不要只依赖返回手势"。
 *
 * 不可变 + `copy`：Compose 侧只需一个 `mutableStateOf`（单一数据源，不存在两份状态不同步）。
 */
internal data class EditorPanelState(val expanded: Boolean = true) {
    /** 点击把手：收起 ⇄ 展开 */
    fun toggled(): EditorPanelState = copy(expanded = !expanded)

    companion object {
        /** 把手拖动阈值（dp）：小于它视为抖动/误触。24dp ≈ 手指按下时的自然抖动上限。 */
        const val DRAG_THRESHOLD_DP = 24f

        /**
         * 返回键分层：**展开 ⇒ 收起面板并消费返回**（返回新状态）；**已收起 ⇒ 返回 null
         * = 不消费**，事件继续往上传给宿主（退出编辑器）。
         */
        fun onBack(s: EditorPanelState): EditorPanelState? =
            if (s.expanded) s.copy(expanded = false) else null

        /**
         * 把手拖动落定（[dy] = 本次手势的累计位移，px；向下为正）：超过阈值才切换 ——
         * **下拉收起 / 上拉展开**；未达阈值或坏数据返回 null（保持原状态）。
         */
        fun afterDrag(s: EditorPanelState, dy: Float, thresholdPx: Float): EditorPanelState? {
            if (!dy.isFinite() || !thresholdPx.isFinite() || thresholdPx <= 0f) return null
            return when {
                dy >= thresholdPx -> s.copy(expanded = false)   // 下拉 = 收起
                dy <= -thresholdPx -> s.copy(expanded = true)   // 上拉 = 展开
                else -> null
            }
        }
    }
}

/**
 * 「看原图」的状态（E2-15 引入，**E2-17 改成 press/release 语义**）。
 *
 * 用户 v0.11.5 验收原话："不要点一下切原图、再点切回；按住按钮 = 看原图，松手 = 恢复效果图"，
 * 并明确"也不用长按图片查看原图"。所以这里只剩**一路**来源：预览区右下角按钮的按住态。
 *
 * 为什么还留成一个不可变状态类、而不是直接用一个 Boolean（与 [SliderRenderGate] 同一套路）：
 * 这条语义错了用户一眼就看见，但真机验证要点半天 —— 抽成纯逻辑，JVM 单测直接钉住
 * "按下即见 / 松手即回 / 松手路径唯一（手势被取消也复位）"。
 */
internal data class OriginalViewState(val pressed: Boolean = false) {
    /** 是否显示输入原图（= 用户正按着按钮） */
    val showing: Boolean get() = pressed

    /** 按住（[v] = true 按下 / false 松手，**含手势被取消的松手**）：唯一的状态迁移 */
    fun press(v: Boolean): OriginalViewState = copy(pressed = v)
}

/**
 * 面板出入场时长（E2-17，ms）。为什么是 240：
 *  - 区间取 200~300ms —— 短于 200ms 在"容器尺寸 + 内容位移"双层动画下会被看成瞬变（用户
 *    反馈的就是瞬变）；长于 300ms 会让"收起面板腾出预览区"这件小事显得拖沓。
 *  - 240 落在区间中段：展开时够看清位移与伸缩的连贯，收起时也不拖手。
 *  - 位移 / 淡入淡出 / 尺寸过渡**共用这一个时长**（配 [FastOutSlowInEasing] 减速曲线）——
 *    三者时长不一致会出现"内容先到位、容器后展开"的撕裂感。
 */
private const val PANEL_ANIM_MS = 240

/** 按钮按下态的缩放过渡（ms）：按压反馈要跟手，取面板动画的零头都不到 */
private const val PRESS_ANIM_MS = 90

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
 * [srcW]/[srcH] 是**原图**尺寸的读数（**已按 EXIF 方向转正**，E2-18b，见 [orientedReadout]）；
 * [w]/[h] 是实际解码尺寸 —— 正常两者相等，只有解码 OOM 降档后 w/h 才小于原图（宁可糊也不崩）。
 *
 * [jpegSource] 是**直通路径的通行证**（E2-18b）：只有 JPEG 源图允许"复制原片字节"另存，
 * 判据见 [passthroughAllowed]。
 */
private class EditorSource(
    /** 渲染输入：解码像素的 direct 副本（RGBA_8888 的内存布局即 R,G,B,A，与引擎约定同序） */
    val buffer: ByteBuffer,
    val w: Int,
    val h: Int,
    val name: String,
    val srcW: Int,
    val srcH: Int,
    /** 源图是 JPEG（文件头魔数判定，见 [isJpegSource]）⇒ 参数无修改时可直通复制字节 */
    val jpegSource: Boolean,
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
 * 底部尺寸读数（E2-18b）：把**转正前**的原图尺寸 [rawW]×[rawH] 换算成**转正后**的读数。
 *
 * 为什么必须换：[decodeOriented] 出来的像素已按 EXIF 转正（90°/270° 时宽高互换），而
 * `inJustDecodeBounds` 读到的 [rawW]/[rawH] 是转正**前**的存储尺寸 —— 直接拿它显示，
 * 竖画面会配一个"4080×3072"的横读数（E2-18a 交接时点名的坑）。
 *
 * 判据是"**横竖方向**与解码结果是否一致"（不是比具体数值）：不一致 ⇒ 转过 90°/270° ⇒ 交换。
 * 用方向而不是数值，是因为解码 OOM 降档后尺寸是原图的 1/2、1/4（`inSampleSize` 是整数，
 * 不会把横变竖）—— 比数值会把"降过档"误判成"转过向"。正方形（w == h）交换也无差别。
 */
internal fun orientedReadout(decodedW: Int, decodedH: Int, rawW: Int, rawH: Int): Pair<Int, Int> =
    if ((decodedW > decodedH) != (rawW > rawH)) rawH to rawW else rawW to rawH

/**
 * 读源图前 3 字节（判格式的魔数用，E2-18b）。只读 3 字节：不把整张图拉进内存。
 * 读不到 / 打不开返回 null —— 调用方按"不是 JPEG"处理（白名单的失败方向，见 [isJpegSource]）。
 */
private fun readMagic(resolver: ContentResolver, uri: Uri): ByteArray? = try {
    resolver.openInputStream(uri)?.use { ins ->
        val buf = ByteArray(3)
        var off = 0
        while (off < 3) {
            val r = ins.read(buf, off, 3 - off)
            if (r <= 0) break
            off += r
        }
        buf.copyOf(off)
    }
} catch (_: Throwable) {
    null
}

/**
 * 读图（IO 线程）：**全分辨率解码**（E2-8 用户 2026-10-08 拍板：预览与导出同分辨率，
 * 见文件头 KDoc）。不再按屏幕尺寸降采样，只保留 OOM 兜底 —— 解不出来（超大图 / 低内存
 * 设备）就**降一档重试一次**（inSampleSize ×2 = 长边减半，宁可糊也不崩），仍失败才报错。
 * 尺寸预读（inJustDecodeBounds）只为拿**原图**尺寸做底部读数。
 *
 * **E2-18b 起解码走 [decodeOriented]**（E2-18a 的统一入口）：DNG 写的是 sensor 方向，
 * 不按 EXIF 转正就会与成片朝向不一致（用户真机反馈）。`reqW/reqH` 传**原图尺寸** ⇒
 * 采样率恒为 1（全分辨率口径不变）；**解码**的 OOM 由 [decodeOriented] 内部降一档重试，
 * 外层 `repeat(2)` 守的是它之后那两步（`allocateDirect` + `copyPixelsToBuffer`，同样会 OOM）。
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
    val rawW = bounds.outWidth
    val rawH = bounds.outHeight
    if (rawW <= 0 || rawH <= 0) return LoadResult.Err("图片已失效或无法读取")

    // 文件名一律走 StillSnapshotStore.fileNameOf（ADR 集成点 I-1）：参数快照的键就是它，
    // 两处各写一份实现，一旦行为不一致，编辑器查到的键就不是落盘时那个（静默取不到快照）。
    // 查不到时的兜底保留原样（URI 末段 → "成片"）。
    val name = StillSnapshotStore.fileNameOf(context, uri) ?: uri.lastPathSegment ?: "成片"
    // 源图格式（E2-18b）：直通只对 JPEG 合法，判据是文件头魔数（不靠 MIME，见 isJpegSource）
    val jpegSource = isJpegSource(readMagic(resolver, uri))
    // 第一趟原尺寸，OOM 后降一档（长边减半）重试一次
    var reqW = rawW
    var reqH = rawH
    repeat(2) {
        var bmp: Bitmap? = null
        try {
            bmp = decodeOriented(context, uri, reqW, reqH)
                ?: return LoadResult.Err("图片已失效或无法读取")
            val w = bmp.width
            val h = bmp.height
            val bytes = ByteBuffer.allocateDirect(w * h * 4)
            bmp.copyPixelsToBuffer(bytes)
            bytes.rewind()
            // ★ 读数跟着转正（E2-18b）：画面已是转正后的 w×h，读数不能还是转正前的存储尺寸
            val (srcW, srcH) = orientedReadout(w, h, rawW, rawH)
            return LoadResult.Ok(
                EditorSource(bytes, w, h, name, srcW, srcH, jpegSource).also { it.decoded = bmp },
            )
        } catch (_: OutOfMemoryError) {
            // 降档前先把这一份还回去，否则重试时两份同时在（全分辨率下每份 50MB）
            bmp?.recycle()
            reqW /= 2
            reqH /= 2
        } catch (_: Throwable) {
            bmp?.recycle()
            return LoadResult.Err("图片已失效或无法读取")
        }
    }
    return LoadResult.Err("图片过大，无法加载")
}

/** 读整份原片字节（覆盖时既作回滚备份、又作 EXIF 来源；E2-9 直通另存也走它，只读一次） */
private fun readImageBytes(context: Context, uri: Uri): ByteArray? = try {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
} catch (t: Throwable) {
    null
}
