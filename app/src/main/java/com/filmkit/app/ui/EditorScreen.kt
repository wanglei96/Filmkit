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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
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
import com.filmkit.app.camera.BuiltinPresets
import com.filmkit.app.camera.CLARITY_UI_RANGE
import com.filmkit.app.camera.CONTRAST_UI_RANGE
import com.filmkit.app.camera.CropAspect
import com.filmkit.app.camera.CropHandle
import com.filmkit.app.camera.CropSpec
import com.filmkit.app.camera.CropView
import com.filmkit.app.camera.EXP_UI_RANGE
import com.filmkit.app.camera.EditorEngineSession
import com.filmkit.app.camera.EditorHistory
import com.filmkit.app.camera.EditorPreset
import com.filmkit.app.camera.EditorSnapshot
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
import com.filmkit.app.camera.PRESET_AMOUNT_UI_RANGE
import com.filmkit.app.camera.PresetStore
import com.filmkit.app.camera.SAT_UI_RANGE
import com.filmkit.app.camera.StillSnapshot
import com.filmkit.app.camera.StillSnapshotStore
import com.filmkit.app.camera.TEMP_UI_PIVOT
import com.filmkit.app.camera.TEMP_UI_RANGE
import com.filmkit.app.camera.TINT_UI_RANGE
import com.filmkit.app.camera.TONE_UI_RANGE
import com.filmkit.app.camera.WB_GAIN_UI_RANGE
import com.filmkit.app.camera.ZERO_UI_PIVOT
import com.filmkit.app.camera.applySnapshotParams
import com.filmkit.app.camera.claritySlot
import com.filmkit.app.camera.clarityUi
import com.filmkit.app.camera.contrastSlot
import com.filmkit.app.camera.contrastUi
import com.filmkit.app.camera.cropHandlePoints
import com.filmkit.app.camera.cropHitHandle
import com.filmkit.app.camera.cropInside
import com.filmkit.app.camera.cropScreenRect
import com.filmkit.app.camera.cropSpecOf
import com.filmkit.app.camera.cropViewMove
import com.filmkit.app.camera.cropViewOf
import com.filmkit.app.camera.cropViewRotate
import com.filmkit.app.camera.cropViewScale
import com.filmkit.app.camera.cropViewSize
import com.filmkit.app.camera.cropViewWithAspect
import com.filmkit.app.camera.discardNeedsConfirm
import com.filmkit.app.camera.editorExportName
import com.filmkit.app.camera.editorOpenParams
import com.filmkit.app.camera.editorParams
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
import com.filmkit.app.camera.mergePresets
import com.filmkit.app.camera.overwriteOriginalPhoto
import com.filmkit.app.camera.paramsUnchanged
import com.filmkit.app.camera.passthroughAllowed
import com.filmkit.app.camera.pivotFraction
import com.filmkit.app.camera.presetDeletable
import com.filmkit.app.camera.presetId
import com.filmkit.app.camera.presetSnapshot
import com.filmkit.app.camera.presetSparseSlots
import com.filmkit.app.camera.radialOutline
import com.filmkit.app.camera.refreshOverwrittenExif
import com.filmkit.app.camera.resetBasicParams
import com.filmkit.app.camera.resetColorParams
import com.filmkit.app.camera.resetLocalGeometry
import com.filmkit.app.camera.saveEditorCopy
import com.filmkit.app.camera.scalePresetSlots
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
import kotlin.random.Random
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
 * 参数起点（集成点 I-3，**E2-21 修正**）：**全恒等基线** —— [editorOpenParams] 返回
 * [CameraPresets.standard]（[34] LUT 强度额外归 0）。成片在拍摄时已经把当时的**全部**参数
 *（曝光 / WB / 对比度 / 饱和度 / 清晰度 / 色温 / 色调 / 影调分区 / 褪色 / LUT / 暗角）烘焙进
 * 像素，打开时再从快照恢复 = 画面被**二次应用**（E2-1 只处理了 LUT，因为当时 `kFsStyle` 只有
 * 那几条；E2-2 / E2-11 之后读的是一整串）。
 * **打开时的画面 = 原片本身**：参数快照仍然读，但只用于「滤镜」组的"原片风格：xxx · 85%"
 * 只读展示 + 一键套用（用户主动点才把快照参数下发，见 [editorOpenParams] /
 * [applySnapshotParams]）；没有快照时提示"已用标准基线"（ADR-4）。
 *
 * 参数面板（E2-10 重做为**专业面板**，ADR-14）：五层结构 —— 顶部栏（取消 / **撤销·重做
 *（E2-25 已做实）** / 覆盖 / 完成）→ 预览区（weight 1f）→ 参数名（居中大字）→ **单滑杆**
 *（满宽 + 密集刻度 + 中点标记 + 物理量读数）→ 参数图标行（横向滚动圆形按钮）→ 底部导航
 *（调整 / 滤镜 / 暗角 / 局部 / **裁剪（E2-12）** / **预设（E3-1a）**）。
 *
 * E3-1a（预设，本卡）：底栏「预设」由置灰留位改为**可点**，打开**预设面板**（取代参数面板，
 * 与裁剪工具栏同一层）：保存当前 / 套用 / 删除（二次确认）。数据层（稀疏字段集 + schema 版本
 * + prefs 单键 JSON）全在 `EditorExport.kt`（纯逻辑，JVM 单测覆盖）；套用**复用**
 * [applySnapshotParams] + `applyLut`（与 E2-21「套用原片风格」同一条调用序列）⇒ 天然
 * "部分套用"、天然一步撤销（E2-25）、参数变了 ⇒ 导出走渲染而非直通（E2-9）。
 * 完整面板（缩略图 / 分组筛选 / 长按菜单）是 E3-1b，强度滑杆是 E3-1c。
 *
 * E2-15（用户真机反馈，本页四处修正）：
 *  ① **面板可收起**：顶部/底部把手（下拉收起·上拉展开·点击切换）+ **返回键分层**（展开时
 *     系统返回先收起面板，已收起才退出编辑器）。收起后面板整段不组合 ⇒ 预览区吃满全屏。
 *     状态机是纯逻辑（[EditorPanelState]），JVM 单测覆盖。
 *  ② **色温/色调移入「调整」组**（用户："怎么放 lut 里了应该是调整里面"），褪色留在「滤镜」
 *     组；滤镜组的 LUT 与参数**拆成两行**（LUT 42 个，同行会把参数埋掉）。
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
 * ★ P-3（查看页「胶片」→ 选预设 → **直接进编辑器**，用户 2026-10-11 拍板）：本页新增
 * [initialPresetId] —— 非空时在**参数起点就绪之后**（[editorOpenParams] 的全恒等基线已写进
 * `params`、历史栈基线已 reset）套用该预设**一次**，走的仍是既有的 `applyPreset` 通路
 * （LUT 降级 / 一步撤销 / 状态提示全部复用，本卡一行没重写）。顺序不可颠倒：先基线、再预设
 * 的稀疏覆盖（[presetSnapshot]），反过来会把预设整个抹掉。id 查不到（面板打开期间被删 /
 * 数据变了）⇒ 与普通「编辑」入口**逐位一致**（什么都不做，见 [initialPresetOf]）。
 *
 * @param uri 待编辑成片的 MediaStore URI；为 null（如进程重建后状态丢失）时显示失败态而非白屏。
 * @param initialPresetId 打开时要套用的预设 id（null = 普通入口，行为与 P-3 之前完全一致）。
 * @param onOpenGallery 顶栏最左侧菜单图标 → 相册页（用户报的第 5 点）。素材仍由本页
 *   自己从 [uri] 全尺寸解码，相册网格里的缩略图**不会**流到这里（用户明确禁止）。
 */
@Composable
fun EditorScreen(
    uri: Uri?,
    initialPresetId: String? = null,
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
    // 参数数组（长度 = CameraPresets.COUNT）：编辑器只改得动参数表里列出的那些槽位
    //（调整 / 滤镜 / 暗角 / 局部四组，见文件末尾的参数表）；其余槽位带着基线值
    //（E2-21 起打开时是**全恒等基线**，不再从快照继承）→ 导出时整体存回快照
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
    // ★ E2-21：快照里的**参数**（完整长度数组）。打开编辑器时它**不下发**（起点是恒等基线，
    // 成片已含这一套参数）；只有用户主动点「套用原片风格」才整槽写回（[applySnapshotParams]）。
    var snapParams by remember { mutableStateOf<DoubleArray?>(null) }
    var hasSnapshot by remember { mutableStateOf(false) }
    // 当前选中的功能大类（E2-10）：底部导航 = 调整 / 滤镜 / 暗角 / 局部。面板常驻
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
    // ── E2-24 离开编辑器的**统一收口** ─────────────────────────────────────
    // 三条离开路径（「取消」/ 顶栏菜单图标 / 系统返回）全部走 [requestExit]；`pendingExit` =
    // 正在等用户确认的**那一个出口**（null = 没有待确认的出口 ⇒ 确认框不显示）。
    // 为什么是一个状态而不是 E2-15 那样的 Boolean：确认框「放弃」之后要去哪，取决于**谁**触发的
    // 离开（返回键 ⇒ 回上一页；菜单 ⇒ 回相册）—— 只记"要不要确认"会把两者混成一条路。
    // 单一状态也保证**只有一个**确认框（不存在两个对话框同时挂着的可能）。
    var pendingExit by remember { mutableStateOf<ExitTarget?>(null) }
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
    // ── E2-12 裁剪（ADR-16）：几何变换，**不进 FilmParams** ──────────────────
    // `crop` = 已确认生效的裁剪（引擎入参）；`cropDraft` = 裁剪模式下的草稿框（未确认）；
    // `cropMode` = 是否在裁剪模式（预览区显示框与遮罩、参数区换成裁剪工具栏）。
    // ⚠ 直通（E2-9 `paramsUnchanged`）比的是 **FilmParams 数组** ⇒ 裁剪**不在**比较范围
    //   内 ⇒ 凡"有没有修改"的判据都必须显式带上 `!crop.isFull`（见 exportNow /
    //   requestCancel / 覆盖警告），否则**裁了图却走直通 = 导出原片字节，裁剪被静默丢弃**。
    // 草稿独立于 cropMode 保存：点别的 tab 退出裁剪模式时草稿留着，再进来不用重拖；
    // 「取消」才把它复位成 `crop`。
    var crop by remember { mutableStateOf(CropSpec.FULL) }
    var cropMode by remember { mutableStateOf(false) }
    var cropDraft by remember { mutableStateOf(CropView.FULL) }
    var cropAspect by remember { mutableStateOf(CropAspect.FREE) }
    var cropHandle by remember { mutableStateOf<CropHandle?>(null) }
    // ── E3-1a 预设（Preset）────────────────────────────────────────────────
    // `presetMode` = 预设面板取代参数面板（与 `cropMode` **同构的独立模式**，两者互斥）；
    // `presets` = 内存镜像（打开面板时读一次 `BuiltinPresets.all` = 用户预设 + 内置三条；
    // 保存 / 删除后由 PresetStore 返回新列表 —— 那两条路径只动用户预设，内置包不参与）。
    // 数据模型 / 序列化 / 稀疏化 / 套用映射全在 EditorExport.kt（纯逻辑，JVM 单测覆盖）。
    var presetMode by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf<List<EditorPreset>>(emptyList()) }
    // ── E3-1c 预设强度（Amount）──────────────────────────────────────────────
    // `appliedPreset` = 当前生效的预设（**强度滑杆只在它非空时出现**：没有"正在生效的预设"就
    //   没有强度可言）；`presetBase` = **套用前**的参数快照（强度每次从它重算 ⇒ 幂等、不累积
    //   浮点误差、来回拖到同一个值得到同一画面）；`presetAmount` = 强度（0..1.5，1.0 = 预设原样）。
    // 三者都**不进**预设 JSON（强度是"套用时的参数"，不是预设的一部分）。
    var appliedPreset by remember { mutableStateOf<EditorPreset?>(null) }
    var presetBase by remember { mutableStateOf<DoubleArray?>(null) }
    var presetAmount by remember { mutableStateOf(1f) }

    // ── E2-25 撤销 / 重做 ─────────────────────────────────────────────────
    // 栈本身是**纯逻辑**（[EditorHistory]，JVM 单测覆盖）；这里只留两个"能不能点"的镜像
    // 状态供顶栏重组 —— 栈内部状态（ArrayDeque）不是 Compose state，读了不会触发重组。
    val history = remember { EditorHistory() }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }

    /**
     * 记一步（**操作生效之后**调）。快照三件套 = 参数副本 + 裁剪 + LUT 资产 id
     *（[EditorSnapshot] 的 KDoc 讲了为什么缺一不可）。
     *
     * 无变化 ⇒ [EditorHistory.record] 自己丢弃（不会出现"点撤销画面不动"的空步）。
     * `params` 还没就绪（首帧前）⇒ 不记。
     */
    fun recordHistory() {
        val film = params ?: return
        history.record(EditorSnapshot(film, crop, lutId))
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    /** 撤销 / 重做栈的镜像状态同步（[recordHistory] 之外的两个入口：undo / redo） */
    fun syncHistory() {
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

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
     *
     * E2-12 字段：`crop`（原图 UV 子矩形 "x0,y0,cw,ch"）/ `cropRot` / `outW`/`outH`（**输出**
     * 尺寸）—— 与引擎侧 `render.style_diag` 的 `crop`/`cropRot`/`outW`/`outH` 同口径，
     * 真机可一眼核对"框拖到哪、引擎收到哪、输出多大"。
     */
    fun logRender(w: Int, h: Int, ms: Long, ticks: Int, ok: Boolean, dragging: Boolean = false) {
        val film = params ?: return
        val (outW, outH) = crop.outputSize(w, h)
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "app", "editor.render",
            kv(
                "w" to w, "h" to h, "ms" to ms, "dragging" to dragging,
                "dragTicks" to ticks, "ok" to ok, "memMb" to previewResidentMb(w, h),
                "crop" to "${crop.x0},${crop.y0},${crop.cw},${crop.ch}",
                "cropRot" to crop.rot, "outW" to outW, "outH" to outH,
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
        engine.requestPreview(src.buffer, src.w, src.h, film.copyOf(), crop) { out ->
            logRender(src.w, src.h, System.currentTimeMillis() - t0, ticks, out != null)
            if (out != null) {
                val old = shown
                shown = out
                // 输入侧两张显示位图（首帧那张解码位图 / 按住看原图重建的那张）到此作废：
                // 引用先断（Image 改用输出图），内存延迟一帧再还。
                // 连同上一张输出一起回收 —— 全分辨率下每张都是 50MB，一张都不能攒
                //（distinct = 同一张只回收一次，Bitmap 没有重写 equals ⇒ 就是同一性去重）
                //
                // ⚠ E2-12：裁剪模式下预览的底图**就是**输入位图（裁剪框画在它上面）⇒
                // 这一轮绝不能回收它，否则 Image 会去画一张已 recycle 的 Bitmap（直接崩）。
                if (cropMode) {
                    listOfNotNull(old).distinct().forEach { recycleLater(it) }
                } else {
                    origBmp = null
                    (listOfNotNull(old) + src.takeDisplayBitmaps()).distinct()
                        .forEach { recycleLater(it) }
                }
                status = ""
            } else {
                status = "渲染失败，显示原图"
            }
        }
    }

    /**
     * 滑杆松手（E2-8）：这次拖动真的改过值才渲染一次 —— 只点一下滑杆不白跑一次全分辨率。
     *
     * ★ E2-25：**松手时记一步撤销**（拖动过程一次都不记，否则一次拖动 = 每帧一步）。
     * 与渲染同一判据（`gate.onFinished()`）：拖动中参数已经写进数组了，这里记的是"松手后的
     * 状态"，栈里存下的则是"按下前的状态"⇒ 撤销回到拖动前。局部拖动定位走同一条路径。
     */
    fun onSliderRelease() {
        if (gate.onFinished()) {
            recordHistory()
            rerender()
        }
    }

    /**
     * 下发 LUT 资产（null = 关闭）与强度，然后重渲染。两个入口共用：手动选卡片、
     * 「套用原片风格」。解析在 IO 线程（读 .cube 字节），上传与重渲染在渲染线程。
     *
     * @param onReady 资产**加载成功、即将下发**时回调。E2-21：「套用原片风格」要等 LUT 就绪
     *   才写参数 —— 加载失败时参数与画面都不该动，否则会留下"参数已套用、LUT 还是旧那张"
     *   的半套用状态（而提示还写着"已保持原状"）。
     * @param record E2-25：默认 true = 下发成功后记一步撤销（换 LUT / 关闭 LUT 都是用户操作）。
     *   **撤销 / 重做回放 LUT 时必须传 false** —— 否则撤销自己变成可撤销的一步，而且
     *   [EditorHistory.record] 会清空 redo 栈（重做从此永远不可用）。
     */
    fun applyLut(
        entry: LutLibrary.Entry?,
        k: Float,
        /** ★ E2-25：false = **不记撤销步**（撤销 / 重做自己回放 LUT 时用，见 [applyHistorySnapshot]） */
        record: Boolean = true,
        onReady: () -> Unit = {},
    ) {
        val film = params ?: return
        if (entry == null) {
            lutId = null
            film[34] = 0.0                    // 关闭 = 强度 0（引擎 uLutOn = 0 整段旁路）
            engine.setLut(null)
            panelRefresh++                    // 强度行消失/读数归位（见 panelRefresh 的 KDoc）
            if (record) recordHistory()       // ★ E2-25：关闭 LUT 也是一步（换 LUT 撤得回）
            rerender()
            return
        }
        scope.launch {
            val lut = withContext(Dispatchers.IO) { LutLibrary.load(context, entry) }
            if (lut == null) {
                status = "LUT 解析失败，已保持原状"
                return@launch
            }
            onReady()
            lutId = entry.id
            film[34] = k.toDouble()
            engine.setLut(lut)
            // 强度是"绕过滑杆"改的（本函数直接写 [34]）：不重建的话，用户点卡片/套用原片
            // 风格后滑杆读数还停在旧值（画面变了、读数没变 = ADR-12 禁止的假滑杆的镜像）
            panelRefresh++
            // ★ E2-25：换 LUT / 改 LUT 强度各记一步。**LUT 资产 id 不在 FilmParams 里**
            //（数组里只有强度 [34]）⇒ 快照必须带上 lutId，否则撤销后"参数回退了、LUT 还是新的"
            if (record) recordHistory()
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
     * 一键套用原片风格（E2-1 的功能；**E2-21 起同时下发参数**）：把快照里的参数（曝光 /
     * 白平衡 / 对比度 / 饱和度 / 清晰度 / 色温 / 色调 / 影调分区 / 褪色 / LUT 强度）与 LUT
     * 资产原样下发。
     *
     * 为什么参数也要下发：打开编辑器时起点是**恒等基线**（成片已含当初的全部参数，见
     * [editorOpenParams]）—— 想复现原片风格只能由用户主动点这里。这也是"快照信息不丢"的
     * 第二个出口（第一个是「滤镜」组那行只读展示）。
     *
     * 资产在打开编辑器时已上传过，但用户可能已切到别的 LUT / 关闭 / 调过滑杆 ⇒ 这里重新走
     * 一遍 [applyLut]（重复上传一次 3D 纹理，代价是一次 IO + 一次纹理上传，换"任何状态下都
     * 正确"）。
     */
    fun applySnapshotStyle() {
        val snapFilm = snapParams ?: return
        // ① LUT：快照记了 id 且资产还在 ⇒ 下发（**资产就绪后才写参数**，见 applyLut 的 onReady）；
        //    否则**显式关掉** —— 引擎的 LUT 是进程级状态，可能残留着用户刚选的那张
        //    ⇒ 不关就是"套用出一张别的风格"。
        val entry = snapLutId?.let { LutLibrary.byId(context, it) }
        if (entry == null) {
            if (snapLutId != null) status = "原片用的 LUT 已不在库中"
            // 参数整体回快照（**原地**逐槽写：滑杆持有这个数组对象，换对象 = 读数脱钩），
            // 但 [34] 归 0（没有 LUT 资产 ⇒ 强度再高也不生效，留着只会误导读数）
            params?.let {
                applySnapshotParams(it, snapFilm)
                it[34] = 0.0
            }
            lutId = null
            engine.setLut(null)
            panelRefresh++
            recordHistory()   // ★ E2-25：「套用参数」= 离散操作，一步（与 LUT 分支同一节奏）
            rerender()
            return
        }
        // ② 参数在 LUT 就绪后整体回快照（加载失败 ⇒ 参数与画面都不动，不留半套用状态）
        applyLut(entry, snapLutK) { params?.let { applySnapshotParams(it, snapFilm) } }
    }

    // ── E3-1a 预设：保存 / 套用 / 删除（数据层 = EditorExport.kt 的纯逻辑 + [PresetStore]）──
    // 本卡只做"核心闭环 + 最小入口"：完整面板（当前照片缩略图 / 分组筛选 / 长按菜单）是 E3-1b，
    // 强度滑杆是 E3-1c。这里的三件事全部**复用**既有机制（applySnapshotParams / applyLut /
    // recordHistory / rerender），没有一条新的渲染或撤销通路。

    /** 打开 / 收起预设面板（底栏「预设」）：打开时读一次 prefs；与裁剪模式互斥（两块都取代参数面板） */
    fun togglePresets() {
        presetMode = !presetMode
        if (presetMode) {
            // ★ P-2c：用户预设 + 内置三条（`BuiltinPresets.all`）—— 直接用 `PresetStore.load`
            // 会让内置包在面板里根本看不见。
            presets = BuiltinPresets.all(context, prefs)
            cropMode = false
        }
    }

    /**
     * 丢弃"当前生效的预设"（强度滑杆随之消失）。三个调用点：**撤销 / 重做**（历史快照里没有
     * "生效中的预设 + 强度"这一层状态 ⇒ 留着就是过期信息）与**删除**（预设没了）。
     * 只清 UI 状态，**不动参数、画面与撤销栈**。
     */
    fun clearAppliedPreset() {
        appliedPreset = null
        presetBase = null
        presetAmount = 1f
    }

    /**
     * 保存当前参数为预设。**只写与恒等值不同的槽**（[presetSparseSlots]，恒等值逐槽取
     * `CameraPresets.standard()`）—— 用户没动过的槽一个都不进库，这样套用时才不会把别人的
     * 画面打回默认（尤其是恒等值为 1.0 / 0.01 / 单位阵的那几组槽）。
     *
     * LUT 强度只在"当前真的选了 LUT"时才有意义：没选 LUT 时 `[34]` 是编辑器初值 0
     *（见 [editorOpenParams]），存下来会让"套用这条预设"凭空关掉用户当前的 LUT。
     *
     * ★ E3-1c：存的是**满强度**的槽值 —— 强度是"套用时的参数"，**不写进预设 JSON**。
     */
    fun savePreset(name: String, group: String) {
        val film = params ?: return
        val now = System.currentTimeMillis()
        val p = EditorPreset(
            id = presetId(now, Random.nextInt()),
            name = name.trim().ifEmpty { "未命名" },
            group = group.trim(),
            createdAt = now,
            slots = presetSparseSlots(film),
            lutId = lutId,
            lutStrength = if (lutId != null) film[34] else 1.0,
        )
        // ★ P-2c：**不能**直接把 PresetStore 的返回值赋给 `presets` —— 那个列表只含用户预设，
        // 赋值会让内置三条当场从面板上消失（保存一条预设后"内置不见了"，直到重开面板）。
        // 合并口径与 [BuiltinPresets.all] 完全一致（用户在前 + 内置在后 + 按 id 去重）。
        presets = mergePresets(PresetStore.upsert(prefs, p), BuiltinPresets.load(context))
        status = "已保存预设：${p.name}（${p.slots.size} 项参数）"
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "app", "preset.save",
            kv("id" to p.id, "slots" to p.slots.size, "lut" to (p.lutId ?: ""), "group" to p.group),
        )
    }

    /**
     * ★ E3-1c：把"预设 @ 强度"落到参数上（套用与强度重算的**唯一**落地点）。
     *
     * 从 [base]（**套用前**的参数快照）重算，而不是在上一次的结果上继续乘 —— 幂等：强度
     * 0.8 → 0.6 → 0.4 与直接 0.4 得到完全相同的参数（不会累积浮点误差，也不会因为来回拖而漂移）。
     *
     * 只覆盖 [EditorPreset.slots] 里记下的槽（[presetSnapshot] 的"缺失 = 保留当前值"），
     * 其余槽保持 [base] ⇒ 部分套用天然成立。
     */
    fun applyPresetAt(p: EditorPreset, base: DoubleArray, amount: Float) {
        val film = params ?: return
        val scaled = scalePresetSlots(p.slots, amount.toDouble())
        applySnapshotParams(film, presetSnapshot(base, scaled))
    }

    /**
     * 套用预设（面板里点「套用」）。三件事，**全部复用既有机制**：
     *  ① **参数**：`applySnapshotParams(params, presetSnapshot(base, scaled))` —— 按 idx 覆盖
     *     = 只改预设里记下的那几个槽，其余保持当前值 ⇒ **部分套用天然成立**；
     *  ② **LUT**：`applyLut(entry, strength, onReady)`（与 E2-21「套用原片风格」同一套调用序列，
     *     含"资产就绪后才写参数"这条顺序约束）；
     *  ③ **撤销**：落地后 `recordHistory()` **一次** = 一步撤销（E2-25）—— 带 LUT 的分支由
     *     [applyLut] 内部记（`record` 默认 true），不带 LUT 的分支在这里显式记。
     *
     * ★ E3-1c：套用 = **强度 100% 的那一次缩放**（`scalePresetSlots(slots, 1.0)` 原样返回，
     * 所以逐位等于 E3-1a 的行为 = 零回归），同时把**套用前的参数**记进 [presetBase]，供强度
     * 滑杆重算；强度不是预设的一部分，预设 JSON 里没有它。
     *
     * 套用后参数变了 ⇒ `paramsUnchanged` 为假 ⇒ 导出走**渲染**而不是直通（E2-9）、「取消」会弹
     * 「放弃本次修改？」（E2-24）—— 两条都是既有判据自动生效，本卡一行没改。
     */
    fun applyPreset(p: EditorPreset) {
        val film = params ?: return
        val base = film.copyOf()          // ★ E3-1c：套用前快照（强度滑杆从它重算）
        val entry = p.lutId?.let { LutLibrary.byId(context, it) }
        // 记录"当前生效的预设"（强度滑杆的数据源）：①② 两个同步分支直接记；③ 带 LUT 的分支
        // 在**资产就绪之后**才记 —— LUT 解析失败时参数与画面都不动（applyLut 的既有语义），
        // 此时强度行也不该冒出来（否则用户拖强度会"套用一个刚才明明失败了的预设"）。
        when {
            // ① 预设不含 LUT ⇒ **不动**当前 LUT 与 [34]（缺失字段 = 保留当前值）
            p.lutId == null -> {
                appliedPreset = p
                presetBase = base
                presetAmount = 1f
                applyPresetAt(p, base, 1f)
                panelRefresh++
                recordHistory()
                rerender()
                status = "已套用预设：${p.name}"
            }
            // ② 预设带 LUT 但资产已不在库中 ⇒ 降级"无 LUT" + 提示（与 applySnapshotStyle 的
            //    失败分支同口径：绝不留"UI 说选了 LUT、引擎里没有"的半状态）
            entry == null -> {
                status = "预设「${p.name}」的 LUT 已不在库中"
                appliedPreset = p
                presetBase = base
                presetAmount = 1f
                applyPresetAt(p, base, 1f)
                film[34] = 0.0
                lutId = null
                engine.setLut(null)
                panelRefresh++
                recordHistory()
                rerender()
            }
            // ③ 正常路径：参数在 LUT 就绪后才写（加载失败 ⇒ 参数与画面都不动，不留半套用状态）
            else -> applyLut(entry, p.lutStrength.toFloat()) {
                appliedPreset = p
                presetBase = base
                presetAmount = 1f
                applyPresetAt(p, base, 1f)
                status = "已套用预设：${p.name}"
            }
        }
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "app", "preset.apply",
            kv(
                "id" to p.id, "slots" to p.slots.size, "lut" to (p.lutId ?: ""),
                "lutFound" to (entry != null),
            ),
        )
    }

    /**
     * ★ E3-1c 强度滑杆**拖动中**：从 [presetBase] 重算参数，**只写槽位、不渲染**
     *（[SliderRenderGate]，与其它滑杆同节奏 —— 拖动中画面不动，松手才渲染一次）。
     *
     * 注意这里**不碰 LUT 强度**：`[34]` 不在预设的 `slots` 里（由 [EditorPreset.lutStrength]
     * 承载，见 [presetSlotIncluded]）⇒ 它由松手时的 [onPresetAmountRelease] 统一处理
     *（拖动中重新上传 3D 纹理是纯浪费）。
     */
    fun dragPresetAmount(v: Float) {
        val p = appliedPreset ?: return
        val base = presetBase ?: return
        presetAmount = v.coerceIn(PRESET_AMOUNT_UI_RANGE.start, PRESET_AMOUNT_UI_RANGE.endInclusive)
        applyPresetAt(p, base, presetAmount)
        gate.onValueChange()
    }

    /**
     * ★ E3-1c 强度滑杆**松手**：渲染一次 + 记**一步**撤销（E2-25，与其它滑杆同口径：
     * 一次松手 = 一步，拖动过程一次都不记）。
     *
     * LUT 强度必须在这里显式处理 —— 它不在 `slots` 里（[34] 由 `lutStrength` 承载），参数域的
     * 重算够不到它。两种情形：
     *  - **LUT 还在位**（`lutId == p.lutId`）⇒ 只写 `[34] = lutStrength * amount`，引擎的 LUT
     *    强度是 uniform ⇒ **不需要重新上传纹理**（与既有的 LUT 强度滑杆同一条路）；
     *  - **用户中途换了别的 LUT** ⇒ 重走 [applyLut]（= 重套预设；`record` 默认 true ⇒ 内部
     *    记一步并渲染，不在外面重复记）。
     */
    fun onPresetAmountRelease() {
        if (!gate.onFinished()) return
        val p = appliedPreset ?: return
        val film = params ?: return
        // LUT 强度同样要夹到 0..1.5：预设自身的强度最大就是 1.5，再乘外推的 1.5 会到 2.25
        //（引擎侧 t > 1 是外推混合，2.25 会把 LUT 变换推得离谱）—— 与 LUT 强度滑杆同一量程。
        val k = (p.lutStrength * presetAmount)
            .coerceIn(0.0, LUT_STRENGTH_UI_RANGE.endInclusive.toDouble()).toFloat()
        val entry = p.lutId?.let { LutLibrary.byId(context, it) }
        if (p.lutId != null && entry != null && lutId != p.lutId) {
            applyLut(entry, k)          // 重套：内部 recordHistory() + rerender()
        } else {
            if (p.lutId != null && entry != null) film[34] = k.toDouble()
            panelRefresh++
            recordHistory()
            rerender()
        }
        EngineBridge.nativeAppendLog(
            EngineBridge.LEVEL_INFO, "app", "preset.amount",
            kv("id" to p.id, "amount" to presetAmount, "lutK" to (p.lutId?.let { film[34] } ?: -1.0)),
        )
    }

    /** 删除预设（二次确认在面板里）：只动 prefs 与列表，**不动当前参数、画面与撤销栈** */
    fun deletePreset(p: EditorPreset) {
        // ★ P-2c：同 savePreset —— 合并内置包，否则删一条用户预设会让内置三条从面板上消失。
        presets = mergePresets(PresetStore.delete(prefs, p.id), BuiltinPresets.load(context))
        if (appliedPreset?.id == p.id) clearAppliedPreset()   // ★ E3-1c：生效中的预设被删 ⇒ 强度行收起
        status = "已删除预设：${p.name}"
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

    /** 重置**单个参数**到基准值（E2-10 新增）—— 离散操作，直接渲染一次（E2-25：记一步） */
    fun resetParam(p: EditorParam) {
        params?.let { p.write(it, p.pivot) }
        panelRefresh++
        recordHistory()
        rerender()
    }

    /** 「调整」组重置（E2-3）：本组参数回恒等值 —— 离散操作，直接渲染一次（不经过 gate） */
    fun resetBasic() {
        params?.let { resetBasicParams(it) }
        panelRefresh++
        recordHistory()   // ★ E2-25：四组重置都是离散操作，各记一步
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
        recordHistory()
        rerender()
    }

    /**
     * 「滤镜」组重置（E2-15 改）：褪色 [94] 回恒等值 + LUT **强度归 0**，但
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
        recordHistory()
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
        recordHistory()   // ★ E2-25：切模式是离散操作（改 [79]）⇒ 一步
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
        recordHistory()
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

    // ── E2-25 撤销 / 重做的落地 ─────────────────────────────────────────────

    /**
     * 把一份历史快照**落回**编辑器（撤销 / 重做共用）。三件套按同一顺序回退：
     * 参数（原地逐槽）→ 裁剪 → LUT 资产，最后**重渲染一次**（与滑杆松手同一条路径
     * [rerender]，所以"撤销了但画面没变"不可能发生）。
     *
     * 参数为什么原地写（`copyInto`）而不是换数组：滑杆 / 轮廓 / 面板读的都是 `params` 这个
     * 数组对象，换对象 = 读数与槽位脱钩（与 [applySnapshotParams] 同一条纪律）。
     *
     * 裁剪：`crop` 是引擎入参，回退它 = 画面回退；**crop 真的变了**且正处裁剪模式时草稿一并
     * 跟随 —— 否则用户"撤销裁剪 → 点确认"会用旧草稿把刚撤销掉的裁剪又装回去（撤销看起来
     * 没生效）。只在 crop 变化时动草稿：用户正在裁剪模式里拖框时点了撤销（撤的是别的操作），
     * 草稿不该被无端复位。
     *
     * LUT：**id 变了才重新加载资产**（没变 ⇒ 引擎里那张纹理还是对的，直接渲染）。资产已不在
     * 库中 ⇒ 清选择并把强度归 0（与 [applySnapshotStyle] 的失败分支同口径），绝不留下
     * "UI 说选了 LUT、引擎里没有"的半状态。
     */
    fun applyHistorySnapshot(s: EditorSnapshot) {
        val film = params ?: return
        s.params.copyInto(film)
        val cropChanged = s.crop != crop
        crop = s.crop
        if (cropMode && cropChanged) cropDraft = cropViewOf(s.crop)
        panelRefresh++                       // 绕过滑杆改了参数 ⇒ 滑杆读数重建（同四组重置）
        val want = s.lutId
        when {
            want == lutId -> rerender()
            want == null -> {
                lutId = null
                engine.setLut(null)
                rerender()
            }
            else -> {
                val entry = LutLibrary.byId(context, want)
                if (entry == null) {
                    lutId = null
                    film[34] = 0.0
                    engine.setLut(null)
                    rerender()
                } else {
                    // ★ record = false：**撤销 / 重做本身绝不能进撤销栈** —— 否则撤销一步
                    // 自己变成可撤销的一步，而且 [EditorHistory.record] 会清空 redo 栈
                    // ⇒ 重做永远不可用（"撤销后重做没反应"）。
                    applyLut(entry, s.params[34].toFloat(), record = false)
                }
            }
        }
    }

    /** 撤销一步（顶栏「撤销」）：无可撤销内容时什么都不做（按钮已置灰，这里是第二道保险） */
    fun undoEdit() {
        // ★ E3-1c：撤销回放的是参数 / 裁剪 / LUT 三件套，**不含"生效中的预设 + 强度"** ⇒ 一并
        //   丢弃（留着就是过期信息：强度行还写着"某某预设 120%"，参数其实已经回退了）。
        history.undo()?.let { applyHistorySnapshot(it); clearAppliedPreset() }
        syncHistory()
    }

    /** 重做一步（顶栏「重做」）：与 [undoEdit] 对称 */
    fun redoEdit() {
        history.redo()?.let { applyHistorySnapshot(it); clearAppliedPreset() }
        syncHistory()
    }

    /**
     * 面板把手拖动落定（E2-15）：累计位移超过阈值才切换 —— 判定是纯函数
     * [EditorPanelState.afterDrag]（JVM 单测覆盖），这里只做 dp→px 与状态落地。
     */
    fun onHandleDrag(dy: Float) {
        val threshold = with(density) { EditorPanelState.DRAG_THRESHOLD_DP.dp.toPx() }
        EditorPanelState.afterDrag(panel, dy, threshold)?.let { panel = it }
    }

    /** 真正切屏：出口 → 宿主回调（BACK = 回上一页，GALLERY = 相册）。 */
    fun leave(target: ExitTarget) {
        when (target) {
            ExitTarget.BACK -> onBack()
            ExitTarget.GALLERY -> onOpenGallery()
        }
    }

    /**
     * 离开编辑器的**唯一请求入口**（E2-24，用户反馈："编辑一半直接点菜单会直接丢失全部编辑并
     * 回到相册"）。三条路径都从这里走：
     *  - 「取消」按钮 ⇒ [ExitTarget.BACK]（语义不变，仍回上一页）
     *  - 顶栏菜单图标 ⇒ [ExitTarget.GALLERY]（去相册）
     *  - 系统返回（面板已收起时）⇒ [ExitTarget.BACK]
     *
     * 判据是 E2-9 的 [discardNeedsConfirm]（= `paramsUnchanged` 的包装 + 裁剪），与导出直通
     * **同一份逻辑**：无修改 ⇒ 直接走该出口（不打扰）；有修改 ⇒ 挂起出口弹确认（[pendingExitFor]）。
     */
    fun requestExit(target: ExitTarget) {
        val needsConfirm = discardNeedsConfirm(params, openParams, cropDirty = !crop.isFull)
        val pending = pendingExitFor(needsConfirm, target)
        if (pending == null) leave(target) else pendingExit = pending
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

        // ★ 参数判据：基准 = openParams（**打开时**的参数，即 editorOpenParams 的返回值：
        // E2-21 起是全恒等基线），不是快照原文；值比较 + 容差 ⇒ "改了又改回原值"也算无修改。
        // openParams 为 null（理论上只在首帧就绪前，此时导出按钮不可点）⇒ 保守走渲染。
        //
        // ★ E2-12（本卡的关键接线）：`paramsUnchanged` 比的是 **FilmParams 数组** ⇒
        // **裁剪不在它的比较范围内**。裁剪是几何变换、走 submitDisplay 的独立通道
        //（ADR-16），所以这里必须显式把它并进"有没有修改"：裁剪非全幅 ⇒ 强制走渲染 +
        // 编码路径。否则用户裁完图点「完成」，直通会把**原片字节原样复制**出去 ——
        // 裁剪被静默丢弃（而且直通只对 JPEG 生效，DNG 那一路本来就走渲染）。
        val unchanged = (openParams?.let { paramsUnchanged(snapshot, it) } ?: false) && crop.isFull

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
            engine.requestFull(src.buffer, src.w, src.h, snapshot, crop) { out ->
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
                    // I-3（**E2-21 修正**）：快照照读，但打开时的**起点**是全恒等基线 ——
                    // 成片在拍摄时已把当时的全部参数烘焙进像素，再从快照恢复 = 画面被二次
                    // 应用（E2-1 只处理了 LUT，因为当时 kFsStyle 只有那几条）。
                    // 快照参数另存为 snapParams，只由「套用原片风格」主动下发
                    //（见 editorOpenParams / applySnapshotParams 的 KDoc）。
                    val snap = StillSnapshotStore.load(context, src.name)
                    val film = editorOpenParams()
                    val snapId = snap?.lutId?.takeIf { it.isNotEmpty() }
                    val entry = snapId?.let { id -> LutLibrary.byId(context, id) }
                    val lut = entry?.let { LutLibrary.load(context, it) }
                    val started = engine.start()
                    // 引擎的 LUT 是进程级状态，可能残留着拍摄时的那一套：按快照决定去留。
                    // 资产先传上去（此时强度是 0，仍不生效）⇒ 点「套用原片风格」只需改 [34]。
                    engine.setLut(lut)
                    EditorInit(
                        film = film,
                        snapFilm = snap?.let { editorParams(it.params) },
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
                snapParams = init.snapFilm
                hasSnapshot = init.hasSnapshot
                lutId = init.lutId
                snapLutId = init.snapLutId
                snapLutK = init.snapLutK
                // 注：LUT 强度（lutK）自 E2-10 起是 `params[34]` 的**派生值**，不再单独赋值
                // ★ E2-25：撤销 / 重做栈的**基线** = 刚打开时的状态（参数恒等基线 + 全幅裁剪
                // + 打开时选中的 LUT）。基线进栈 ⇒ "改若干 → 全部撤销"能精确回到打开时的画面
                // ⇒ `paramsUnchanged` 重新为真、`cropDirty` 为假 ⇒ 导出仍走直通（E2-9 的
                // "我撤销回原样了，导出却还是二代损失"这一条）。
                history.reset(EditorSnapshot(init.film, CropSpec.FULL, init.lutId))
                syncHistory()
                if (!init.engineStarted) {
                    status = "引擎不可用，显示原图"
                    return@LaunchedEffect
                }
                status = ""
                // ★ P-3：从查看页「胶片」进来时带着初始预设 ⇒ 在**基线就绪之后**套用一次。
                // 顺序铁律（E2-21）：上一行刚把 editorOpenParams()（全恒等基线）写进 `params`
                // 并 reset 了历史栈基线，applyPreset 只把预设的**稀疏槽**覆盖上去
                //（presetSnapshot）。反过来（先预设、再基线）会把预设整个抹掉 ⇒ 用户点了预设、
                // 画面还是原片。applyPreset 三个分支内部都会 rerender() ⇒ 这里不重复调用。
                // ★ P-2c：列表必须与查看页那一份**同一个来源**（`BuiltinPresets.all`）——
                // 查看页能列出内置三条、这里却查不到，表现就是"点了内置预设、进编辑器毫无变化"。
                val startPreset = initialPresetOf(BuiltinPresets.all(context, prefs), initialPresetId)
                if (startPreset != null) applyPreset(startPreset) else rerender()
            }
        }
    }

    // ★ E2-15 返回键**分层** + E2-24 **统一收口**（用户反馈："编辑一半直接点菜单会直接丢失全部
    // 编辑并回到相册"）：本 handler **无条件启用** —— 以前只在 `panel.expanded` 时启用，面板一
    // 收起返回事件就落到宿主（MainActivity 的 Nav BackHandler）⇒ 不弹确认直接换页，编辑全丢。
    //
    // 分层原样保留（E2-15 的验收点，用户明确说过"返回手势没问题"）：
    //   面板展开 ⇒ 只收面板（[EditorPanelState.onBack] 消费掉这次返回，**绝不**退出编辑器）；
    //   面板已收起 ⇒ requestExit(BACK)：有修改先弹确认，无修改直接退出。
    BackHandler {
        // ★ E3-1a：预设面板打开时，返回键先关面板（与"面板展开时先收面板"同一分层精神）——
        // 预设面板是临时面板，用户按返回的意图是"退出预设"，不是"退出编辑器"。
        if (presetMode) {
            presetMode = false
        } else {
            val collapsed = EditorPanelState.onBack(panel)
            if (collapsed != null) panel = collapsed else requestExit(ExitTarget.BACK)
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
            // ① 顶部栏（ADR-14 层①）：取消 ｜ 撤销/重做（**E2-25 已做实**，无内容时置灰）｜ 覆盖 ｜ 完成
            // 「完成」= 黄色圆角矩形 + 黑字（主操作 = 另存新片，语义与原底部主按钮一致）；
            // 「覆盖」= 深灰圆角 + 白字（危险操作，仍走二次确认对话框）。
            EditorTopBar(
                enabled = !exporting && phase == EditorPhase.Ready,
                exporting = exporting,
                canUndo = canUndo,
                canRedo = canRedo,
                onCancel = { requestExit(ExitTarget.BACK) },
                // 相册入口（E2-24）：不再直接切屏 —— 有修改先弹确认，否则同样"点一下就丢光编辑"
                onGallery = { requestExit(ExitTarget.GALLERY) },
                onUndo = { undoEdit() },
                onRedo = { redoEdit() },
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
                        // 看原图：显示输入像素 —— 由 LaunchedEffect(showOriginal, cropMode) 在 IO
                        // 线程从常驻 buffer 重建（见 EditorSource.displayBitmap），主线程零成本；
                        // 还没就绪时退回显示成片，绝不白屏
                        val orig = if (showOriginal) origBmp else null
                        // ★ E2-12：裁剪模式的底图是**原始输入位图**（未裁剪、未旋转）——
                        // 裁剪的语义是"相对原图"（引擎的 crop_uv 就是原图 UV 子矩形）。
                        // 拿已裁剪的画面当底图 = 让用户在"裁剪结果上再裁剪"，语义混乱。
                        val cropSrc = if (cropMode) origBmp else null
                        val cropReady = cropMode && cropSrc != null
                        if (bmp != null) {
                            // E2-17：预览区**不再注册任何手势** —— 长按画面看原图已按用户要求
                            // 移除（"也不用长按图片查看原图"）。显示哪张只由右下角按钮的按住态
                            // 决定（[OriginalViewState]），局部模式下则由拖动定位独占。
                            val image = remember(bmp, orig, showOriginal) {
                                (if (showOriginal && orig != null) orig else bmp).asImageBitmap()
                            }
                            val cropImage = remember(cropSrc) { cropSrc?.asImageBitmap() }
                            // E2-7：局部模式下的拖动定位。坐标换算（含 letterbox 黑边）与轮廓
                            // 绘制**共用** EditorExport.kt 里的同一套纯函数 —— 两处各写一份，
                            // 一旦有偏差用户看到的就是"拖了没对准"。
                            val localMode = params?.let { localTypeUi(it[79]) } ?: LocalMode.OFF
                            // 裁剪模式下局部拖动让位：两种手势都要吃整个预览区
                            val dragMode = localMode != LocalMode.OFF && !cropMode
                            // ★ E2-12：局部调整的坐标基准 = **引擎输出**（裁剪后）的画面 ——
                            // 引擎里 localMask 用的是 vUV0（裁剪后的归一化坐标），所以 letterbox
                            // 换算 rect 与正圆 aspect 都必须按输出尺寸算，否则裁剪后拖局部中心会偏
                            //（这正是 ADR-16 说的"局部调整中心自动落在用户拖的位置"）。
                            val outSize = source?.let { crop.outputSize(it.w, it.h) }
                            val rect = remember(previewSize, outSize) {
                                val (sw, sh) = outSize ?: (1 to 1)
                                imageFitRect(
                                    previewSize.width.toFloat(), previewSize.height.toFloat(), sw, sh,
                                )
                            }
                            // 裁剪框的屏幕几何：基准 = **旋转后**画面（rot 奇数 ⇒ 宽高交换）
                            val cropSize = source?.let { cropViewSize(it.w, it.h, cropDraft.rot) }
                            val cropFit = remember(previewSize, cropSize) {
                                val (vw, vh) = cropSize ?: (1 to 1)
                                imageFitRect(
                                    previewSize.width.toFloat(), previewSize.height.toFloat(), vw, vh,
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
                                    }
                                    // ★ E2-12 裁剪手势：拖框内 = 移动整框，拖 8 个手柄 = 缩放。
                                    // 拖动中**只更新框、不渲染**（与 E2-8 的滑杆闸门同一精神：
                                    // 零引擎开销 + 实时反馈），点「确认」才渲染一次。
                                    .pointerInput(cropMode, cropDraft.rot, cropFit) {
                                        if (!cropMode) return@pointerInput
                                        val touch = 24.dp.toPx()
                                        var grabbed = false
                                        detectDragGestures(
                                            onDragStart = { pos ->
                                                cropHandle = cropHitHandle(
                                                    cropDraft, cropFit, pos.x, pos.y, touch,
                                                )
                                                grabbed = cropHandle != null || cropInside(
                                                    cropDraft, cropFit, pos.x, pos.y,
                                                )
                                            },
                                            onDragEnd = { cropHandle = null },
                                            onDragCancel = { cropHandle = null },
                                            onDrag = { change, delta ->
                                                change.consume()
                                                if (!grabbed || cropFit.width <= 0f ||
                                                    cropFit.height <= 0f
                                                ) {
                                                    return@detectDragGestures
                                                }
                                                val (vw, vh) = cropSize ?: (1 to 1)
                                                val dx = delta.x / cropFit.width
                                                val dy = delta.y / cropFit.height
                                                val h = cropHandle
                                                cropDraft = if (h == null) {
                                                    cropViewMove(cropDraft, dx, dy)
                                                } else {
                                                    cropViewScale(
                                                        cropDraft, h, dx, dy,
                                                        cropAspect.ratio(vw, vh), vw, vh,
                                                    )
                                                }
                                            },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (cropReady && cropImage != null) {
                                    // 旋转显示：位图按草稿 rot 旋转 90° 步进（图形层旋转，不重
                                    // 解码）。显示尺寸 = **旋转前**尺寸，使旋转后的视觉矩形正好
                                    // 等于 cropFit（旋转后画面的 Fit 矩形），两者同心同大小。
                                    val odd = cropDraft.rot % 2 == 1
                                    val dispW = if (odd) cropFit.height else cropFit.width
                                    val dispH = if (odd) cropFit.width else cropFit.height
                                    Image(
                                        bitmap = cropImage,
                                        contentDescription = "裁剪预览",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .size(
                                                with(density) { dispW.toDp() },
                                                with(density) { dispH.toDp() },
                                            )
                                            .graphicsLayer(rotationZ = 90f * cropDraft.rot),
                                    )
                                    // ★ 裁剪框 + 遮罩：**Compose 叠加层** —— 导出走引擎输出位图，
                                    // 这里的东西**结构上不可能**进导出（与 E2-7 局部轮廓同款保证，
                                    // 不是"记得别画进去"）。
                                    Canvas(Modifier.fillMaxSize()) {
                                        if (cropFit.width <= 0f || cropFit.height <= 0f) {
                                            return@Canvas
                                        }
                                        val s = cropScreenRect(cropDraft, cropFit)
                                        // 框外压暗（整幅预览区，含 letterbox 黑边）
                                        val mask = Color.Black.copy(alpha = 0.62f)
                                        drawRect(mask, Offset(0f, 0f), Size(size.width, s.top))
                                        drawRect(
                                            mask, Offset(0f, s.top + s.height),
                                            Size(size.width, size.height - s.top - s.height),
                                        )
                                        drawRect(mask, Offset(0f, s.top), Size(s.left, s.height))
                                        drawRect(
                                            mask, Offset(s.left + s.width, s.top),
                                            Size(size.width - s.left - s.width, s.height),
                                        )
                                        // 三分线（构图辅助）
                                        val thin = Color.White.copy(alpha = 0.35f)
                                        for (k in 1..2) {
                                            val gx = s.left + s.width * k / 3f
                                            val gy = s.top + s.height * k / 3f
                                            drawLine(
                                                thin, Offset(gx, s.top),
                                                Offset(gx, s.top + s.height), 1f,
                                            )
                                            drawLine(
                                                thin, Offset(s.left, gy),
                                                Offset(s.left + s.width, gy), 1f,
                                            )
                                        }
                                        drawRect(
                                            FilmTheme.Accent, Offset(s.left, s.top),
                                            Size(s.width, s.height), style = Stroke(2f),
                                        )
                                        // 8 个手柄（4 角 + 4 边）
                                        cropHandlePoints(cropDraft, cropFit).forEach { (hx, hy) ->
                                            val tl = Offset(hx - 9f, hy - 9f)
                                            drawRect(Color.White, tl, Size(18f, 18f))
                                            drawRect(
                                                FilmTheme.Accent, tl, Size(18f, 18f),
                                                style = Stroke(2f),
                                            )
                                        }
                                    }
                                } else {
                                    Image(
                                        bitmap = image,
                                        contentDescription = if (showOriginal) "原图" else "成片预览",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                // ★ 辅助轮廓（E2-7）：**Compose 叠加层**，与 Image 同尺寸的
                                // 兄弟节点 —— 导出走的是引擎输出位图，这里的东西**结构上
                                // 不可能**进导出（不是"记得别画进去"）。拖动中只画它、不渲染
                                //（零引擎开销、实时反馈），松手才 rerender。
                                val film = params
                                if (dragMode && film != null) {
                                    // H/W（不是 W/H）：正圆修正要求 ry = rx×W/H（见 radialOutline 的 KDoc）
                                    val aspect = outSize?.let { (ow, oh) ->
                                        if (ow > 0) oh.toFloat() / ow else 1f
                                    } ?: 1f
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
                                // ★ E2-19（用户真机反馈："我觉得不需要界面给按住查看原图的文字提示，
                                // 正常人都会用"）：看原图的两条文案（未按的"按住右下角按钮看原图"与
                                // 按住中的"原图（松手返回效果）"）**一并删除** —— 按钮自身的按下态
                                //（黄底 + 深色图标 + 0.88 缩放）已经足够说明，文字只是噪音。
                                // ⚠ 按钮本身与 press-and-hold 行为**一个字都没动**（见 [OriginalPressButton]）。
                                // ⚠ 只保留**局部拖动**的提示：它讲的是"画面可以拖"（删了用户不知道能拖），
                                // 与看原图无关。
                                if (dragMode) {
                                    Text(
                                        if (localDragging) "拖动定位中…松手生效" else "拖动画面移动局部区域",
                                        color = if (localDragging) FilmTheme.Accent else FilmTheme.CreamDim,
                                        fontSize = 11.sp,
                                        style = TextStyle(shadow = TextShadow),
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(top = 8.dp, end = 12.dp),
                                    )
                                }
                                if (cropMode) {
                                    Text(
                                        "拖动画面移动裁剪框 · 拖角/边缩放",
                                        color = FilmTheme.CreamDim,
                                        fontSize = 11.sp,
                                        style = TextStyle(shadow = TextShadow),
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(top = 8.dp, end = 12.dp),
                                    )
                                }
                                // ★ E2-15 原图按钮（用户："查看原图给个按钮"）→ E2-17 改成**按住看**
                                //（用户："按住按钮 = 看原图；松手 = 恢复效果图"，明确否掉点一下切换）。
                                // 局部模式下**隐藏**：拖动定位要用整个预览区（含右下角），按钮会抢走那
                                // 一片的按下事件 ⇒ 用户在那儿拖不动（父代理已提示这个冲突）。
                                // E2-12：裁剪模式下同样隐藏（"看原图"与裁剪底图是同一张，没有意义）。
                                if (!dragMode && !cropMode) {
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
            // ★ E2-12 裁剪模式：参数面板整段让位给**裁剪工具栏**（互斥的两块，不叠在
            // 预览上 —— 与 E1-4 起"面板不遮挡预览"的约束一致）。
            if (cropMode) {
                CropToolBar(
                    aspect = cropAspect,
                    dirty = cropDraft != cropViewOf(crop),
                    onAspect = { a ->
                        cropAspect = a
                        source?.let { src ->
                            val (vw, vh) = cropViewSize(src.w, src.h, cropDraft.rot)
                            cropDraft = cropViewWithAspect(cropDraft, a.ratio(vw, vh), vw, vh)
                        }
                    },
                    // ★ E2-12c：旋转 = 画面转 90° **且框跟着走**（以前只改 rot、框不动 ⇒
                    // 「锁 1:1 → 旋转 90° → 确认」导出 2313×4080，锁定比例静默失效）。
                    // 自由 = 框跟随内容转（保持"框住哪块区域"）；锁定比例 = 保持中心、按新
                    // 朝向重排成该比例（不越界）—— 两种行为都在 cropViewRotate 里。
                    onRotate = {
                        val (vw, vh) = source?.let {
                            cropViewSize(it.w, it.h, cropDraft.rot + 1)
                        } ?: (1 to 1)
                        cropDraft = cropViewRotate(
                            cropDraft, cropAspect.ratio(vw, vh), vw, vh,
                        )
                    },
                    // 取消 = 草稿复位成**已确认**的裁剪（画面回到进入裁剪模式前的样子）
                    onCancel = {
                        cropDraft = cropViewOf(crop)
                        cropAspect = CropAspect.FREE
                        cropMode = false
                    },
                    // 确认 = 草稿写入 crop（引擎入参）并渲染一次。
                    // ⚠ 先退裁剪模式再 rerender：rerender 的回收分支按 cropMode 决定要不要
                    //   回收输入位图（裁剪模式下它是裁剪框的底图，不能回收）。
                    // ★ E2-25：**裁剪确认记一步**（裁剪是几何量、不进 FilmParams ⇒ 快照必须
                    //   单独带 CropSpec）。粒度 = 一次「确认」= 用户视角的一次裁剪操作：
                    //   拖动框 / 旋转都在草稿阶段，不产生可撤销步（草稿不是生效状态，记了会
                    //   出现"点撤销画面不动"）；撤销一次即回到进裁剪模式前的框（含那次旋转）。
                    onConfirm = {
                        source?.let { src -> crop = cropSpecOf(cropDraft, src.w, src.h) }
                        cropMode = false
                        recordHistory()
                        rerender()
                    },
                )
            } else if (presetMode) {
                // ★ E3-1a：预设面板（取代参数面板，与裁剪工具栏同一层）—— 最小入口：
                // 保存当前 / 套用 / 删除（二次确认）。列表缩略图、分组筛选、长按菜单是 E3-1b。
                PresetPanel(
                    presets = presets,
                    canSave = params != null,
                    status = status,
                    // ★ E3-1c 强度：只在"有生效中的预设"时出现（Lightroom 的 Preset Amount 同款：
                    // 套用之后才谈得上强度）；拖动中不渲染、松手渲染一次（SliderRenderGate）。
                    applied = appliedPreset,
                    amount = presetAmount,
                    onAmount = { dragPresetAmount(it) },
                    onAmountFinished = { onPresetAmountRelease() },
                    onSave = { n, g -> savePreset(n, g) },
                    onApply = { applyPreset(it) },
                    onDelete = { deletePreset(it) },
                    onHandleDrag = { dy -> onHandleDrag(dy) },
                    onTogglePanel = { panel = panel.toggled() },
                )
            } else {
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
                        hasSnapshot = hasSnapshot,
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
                        onCrop = {
                            // 进入裁剪模式：草稿 = 已确认的裁剪（再次进入时框还在原处）
                            cropDraft = cropViewOf(crop)
                            cropAspect = CropAspect.FREE
                            cropMode = true
                            presetMode = false   // ★ E3-1a：与预设面板互斥（两块都取代参数面板）
                        },
                        // ★ E3-1a：底栏「预设」入口（打开 / 收起预设面板）
                        onPreset = { togglePresets() },
                        presetActive = presetMode,
                    )
                } else {
                    CollapsedPanelBar(
                        onDragEnd = { dy -> onHandleDrag(dy) },
                        onTogglePanel = { panel = panel.toggled() },
                    )
                }
            }
            }
        }

        if (confirmOverwrite) {
            // ★ E2-19：源图是 DNG（非 JPEG）**且有修改** ⇒ 这次覆盖会把 RAW 数据永久换掉，
            // 同一个确认框换成警告文案（不再多弹一个框：一次操作只打扰一次）。
            // 判据 = [overwriteNeedsRawWarning]（与「能不能直通」共用 E2-18b 的 isJpegSource
            // 口径）；"有修改"复用 [discardNeedsConfirm]（= paramsUnchanged 的包装，与
            // exportNow 的直通判据同一份逻辑）。
            // 无修改时 rawLoss 恒 false ⇒ 这里仍是原来的「覆盖原片？」—— 但那种情况 exportNow
            // 会直接拒绝（"没有修改，无需覆盖"），用户确认后也不会写一个字节。
            val rawLoss = overwriteNeedsRawWarning(
                paramsUnchanged = !discardNeedsConfirm(
                    params, openParams, cropDirty = !crop.isFull,
                ),
                jpegSource = source?.jpegSource ?: false,
            )
            AlertDialog(
                onDismissRequest = { confirmOverwrite = false },
                shape = RoundedCornerShape(18.dp),
                title = {
                    Text(
                        if (rawLoss) "覆盖会丢失 RAW 数据" else "覆盖原片？",
                        color = FilmTheme.Cream, fontFamily = FilmTheme.Serif,
                    )
                },
                text = {
                    Text(
                        if (rawLoss) {
                            "覆盖会丢失 RAW 数据且无法恢复。\n建议改用「完成」（另存为新片）。"
                        } else {
                            "会用当前编辑结果替换这张原片，原图无法恢复。\n想保留原片请改用「完成」（另存为新片）。"
                        },
                        color = FilmTheme.CreamDim,
                        fontSize = 13.sp,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmOverwrite = false
                        exportNow(true)
                    }) {
                        Text(
                            if (rawLoss) "仍要覆盖" else "覆盖",
                            color = FilmTheme.Accent, fontFamily = FilmTheme.Mono,
                        )
                    }
                },
                dismissButton = {
                    // 取消 = 什么都不做：只关掉这个框（参数、原片、快照全不动）
                    TextButton(onClick = { confirmOverwrite = false }) {
                        Text("取消", color = FilmTheme.CreamDim, fontFamily = FilmTheme.Mono)
                    }
                },
            )
        }

        // ★ E2-24：三条离开路径**共用这一个**确认框（由 [pendingExit] 驱动，不存在第二个对话框）。
        // 只有 [discardNeedsConfirm] 为真（参数与打开时不同，**或**裁了图）才会挂起出口走到这里；
        // 无修改时三条路径都直接离开，不弹窗打扰。
        // 「放弃」⇒ 走挂起的那个出口（[exitAfterConfirm]：BACK 回上一页 / GALLERY 回相册）；
        // 「继续编辑」或点框外 ⇒ 只清空出口，留在编辑器（参数、裁剪、快照一律不动）。
        val exit = pendingExit
        if (exit != null) {
            AlertDialog(
                onDismissRequest = { pendingExit = null },
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
                        pendingExit = null
                        exitAfterConfirm(exit)?.let { leave(it) }
                    }) { Text("放弃", color = FilmTheme.Accent, fontFamily = FilmTheme.Mono) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingExit = null }) {
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
    LaunchedEffect(showOriginal, cropMode) {
        val src = source ?: return@LaunchedEffect
        // E2-12：裁剪模式的底图也是这张输入位图（见预览区 cropSrc）⇒ 两个入口共用同一份
        // 重建缓存，退出时一并还回。
        if (showOriginal || cropMode) {
            if (origBmp == null) origBmp = withContext(Dispatchers.IO) { src.displayBitmap() }
        } else {
            origBmp = null
            // 只还回"按需重建"的那张：它从不作为 shown 显示，回收是安全的。
            // （解码那张归 [EditorSource.takeDisplayBitmaps] 管 —— 首帧渲染成功时统一还）
            src.dropRebuilt()?.let { recycleLater(it) }
        }
    }

    // LUT 卡片预览按需生成（一次性缓存，见 LutPreview）：切到「滤镜」组时才做 ——
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
    /** 快照里的**参数**（E2-21）：打开时不下发，只由「套用原片风格」主动写回；null = 无快照 */
    val snapFilm: DoubleArray?,
    val hasSnapshot: Boolean,
    val lutId: String?,
    /** 快照里**原片**用的 LUT id（≠ [lutId]：用户可能已改选或关闭；null = 原片没记 LUT） */
    val snapLutId: String?,
    val snapLutK: Float,
    val engineStarted: Boolean,
)


// ─────────────────────────────────────────────────────────────────────────────
// 编辑器面板骨架（E2-10 / ADR-14）：Filmix 五层结构
//   ① 顶部栏：取消 ｜ 撤销/重做（**E2-25 已做实**，无内容时置灰）｜ 覆盖 ｜ 完成（黄底黑字）—— [EditorTopBar]
//   ② 预览区：weight(1f)，约屏高 55%（在 EditorScreen 里，面板常驻但不遮挡画面）
//   ③ 参数名：居中大字（当前选中参数）+ 1px 黑阴影 —— [ParamNameRow]
//   ④ 单滑杆：**一屏只出当前选中参数的那一条**，满宽 + 密集刻度 + 中点标记 + 物理量读数 —— [EditorSlider]
//   ⑤ 参数图标行：横向滚动圆形按钮（未选中 = 深灰圆 + 白图标；选中 = 深灰圆 + 黄描边 + 黄图标）—— [ParamIconRow]
//   ⑥ 底部导航：调整 / 滤镜 / 暗角 / 局部 / **裁剪**（E2-12）＋ **预设**（E3-1a）—— [EditorTabBar]
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

/**
 * 禁用态前景（顶栏「覆盖 / 完成」不可用时、「撤销/重做」无可撤销内容时、预设面板的禁用按钮
 * 与输入框占位文字）：30% 奶油白，不新造色值
 */
private val DisabledInk = FilmTheme.CreamDim.copy(alpha = 0.35f)

/**
 * 底部导航的功能大类（E2-10）。**只有引擎已实现并验收的组才进枚举**（ADR-12：不许假控件）。
 * 预设（E3 预设管理）在 [EditorTabBar] 里置灰留位，不进枚举、不可点；
 * **裁剪（E2-12）已激活**：可点、进裁剪模式时高亮（`cropActive`），不再是留位项。
 *
 * ★ E2-19：第二组的**显示名**从「色彩」改为「滤镜」（用户真机反馈："色彩名称改为滤镜"，
 * 与 Filmix 底栏命名一致）。**只改这一处字符串**：枚举名 `COLOR`、组内参数（LUT 选择 +
 * LUT 强度 + 褪色）与任何逻辑都没动。
 */
private enum class EditorTab(val label: String, val icon: ImageVector) {
    ADJUST("调整", Icons.Outlined.Tune),
    COLOR("滤镜", Icons.Outlined.Palette),
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
    // ── E2-15：色温 / 色调从「滤镜」组**移进来**（用户反馈："色温方向正确，放的位置不对，
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
 * 「滤镜」组的**引擎参数**（E2-14；E2-15 只剩褪色 [94]）：褪色。
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
 *  - 径向：半径 / 羽化 / 区域内曝光 / 区域内饱和度
 *  - 线性：角度 / 宽度 / 羽化 / 区域内曝光 / 区域内饱和度
 *  - 未选模式：全部六条（用户可以先设参数再选模式）
 *
 * 中心 [80][81] 没有滑杆 —— 定位在预览图上拖动完成（E2-7 的核心交互，本卡保留）。
 *
 * 读数口径：半径/宽度/羽化 `35`（0..100）；**角度 `90°`**（方向用度数，E2-7 定的口径）；
 * 区域内曝光 `+1.0 EV`；区域内饱和度 `+12`（±100）。
 *
 * E2-21 改名：`曝光` → `区域内曝光`、`饱和度` → `区域内饱和度`（半径 / 羽化 / 角度 / 宽度
 * 是几何参数，本来就只描述"圈在哪"，不改名）。理由见 [LOCAL_SCOPE_HINT]。
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
            // E2-21：加「区域内」前缀 —— 用户问"调节里有曝光和饱和度了，局部里也有是做什么的？"
            // 说明只写"曝光"讲不清"只作用于圈定区域"（ADR-12：界面要自己说清楚，不靠用户猜）
            key = "locEv", label = "区域内曝光", icon = Icons.Outlined.Exposure,
            range = LOCAL_EV_UI_RANGE, pivot = 0f,
            format = { formatEv(it) },
            read = { localEvUi(it[86]) },
            write = { p, v -> p[86] = localEvSlot(v) },
        ),
    )
    add(
        EditorParam(
            key = "locSat", label = "区域内饱和度", icon = Icons.Outlined.WaterDrop,
            range = LOCAL_SAT_UI_RANGE, pivot = 1f,
            format = { formatOffset100(it, 1f) },
            read = { localSatUi(it[87]) },
            write = { p, v -> p[87] = localSatSlot(v) },
        ),
    )
}

/**
 * 局部组的**一行说明**（E2-21）：全局组（调整）里的「曝光 / 饱和度」作用于整张图，局部组的
 * 两条只作用于圈定的区域 —— 两条滑杆同名时用户会问"这不是重复了吗"，所以参数名加「区域内」
 * 前缀之外，组顶部再直说一次。文案刻意短（面板高度有限，且提示行已经占了一条）。
 */
private const val LOCAL_SCOPE_HINT = "以下参数只作用于圈出的区域"

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
        // E2-21：滑杆改名「区域内曝光 / 区域内饱和度」后，这行提示里的称呼必须跟着走 ——
        // 否则用户按提示去找一条叫「曝光」的滑杆，在局部组里根本找不到。
        // 文案压到 28 字以内：这行是 maxLines=1 + Ellipsis，窄屏上尾部被截掉就白写了
        !active -> "已选模式：调「区域内曝光 / 饱和度」后才会变化（未调强度）"
        mode == LocalMode.RADIAL -> "预览上拖动移动中心（当前 $cx%, $cy%）· 半径 100 = 图像宽度"
        else -> "预览上拖动移动轴线（当前 $cx%, $cy%）· 角度 0° = 条带竖直"
    }
}

/**
 * ① 顶部栏（ADR-14 层①）：`取消 ｜ 撤销 / 重做 ｜ 覆盖 ｜ 完成`。
 *
 * 「完成」= **黄色圆角矩形 + 黑字**（主操作，= 原来的「另存为新片」，语义不变）；
 * 「覆盖」= 深灰圆角 + 白字（危险操作，仍走二次确认对话框）。
 *
 * **撤销 / 重做（E2-25 起做实）**：栈非空才可点，否则置灰（[DisabledInk]，与「覆盖 / 完成」
 * 同一口径）—— 这正是 ADR-12 要的"不做假控件"：置灰是**诚实**的（此刻确实没得撤销），
 * 而不是"能点但没反应"。导出中 / 未就绪时（[enabled] = false）同样不可点：导出独占引擎，
 * 此时撤销只会在导出收尾时补一次渲染（[pendingRender] 机制），不如等它做完。
 */
@Composable
private fun EditorTopBar(
    enabled: Boolean,
    exporting: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onCancel: () -> Unit,
    onGallery: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
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
        // 撤销 / 重做（E2-25）：有可撤销 / 可重做内容才可点，否则置灰（复用既有 DisabledInk）
        UndoRedoButton(
            icon = Icons.AutoMirrored.Outlined.Undo,
            desc = "撤销",
            active = enabled && canUndo,
            onTap = onUndo,
        )
        UndoRedoButton(
            icon = Icons.AutoMirrored.Outlined.Redo,
            desc = "重做",
            active = enabled && canRedo,
            onTap = onRedo,
        )
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
 * 顶栏的一个撤销 / 重做图标钮（E2-25）。`active = false` ⇒ 置灰 **且不注册任何手势**
 *（[pressScale] 的 enabled 语义：禁用态还带按下缩放 = 用户以为点上了）。
 */
@Composable
private fun UndoRedoButton(icon: ImageVector, desc: String, active: Boolean, onTap: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .pressScale(enabled = active, onTap = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = desc,
            tint = if (active) FilmTheme.Cream else DisabledInk,
            modifier = Modifier.size(20.dp),
        )
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
    /** 有参数快照 ⇒ 显示「套用参数」按钮（E2-23：不再以"快照里有 LUT"为条件） */
    hasSnapshot: Boolean,
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
    /** E2-12：底栏「裁剪」项（进入裁剪模式） */
    onCrop: () -> Unit,
    /** ★ E3-1a：底栏「预设」项（打开 / 收起预设面板） */
    onPreset: () -> Unit,
    /** ★ E3-1a：预设面板是否打开（底栏项高亮，与 `cropActive` 同一套选中态） */
    presetActive: Boolean,
) {
    val context = LocalContext.current
    val list = when (group) {
        EditorTab.ADJUST -> AdjustParams
        // 滤镜组（E2-15）：褪色（[ColorParams]）+ LUT 强度（选了 LUT 才有）。
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
        // 滤镜组专属：原片风格（只读）+ 一键套用 —— E2-1 的功能，一个都没丢
        if (group == EditorTab.COLOR) {
            SnapshotStyleRow(context, hasSnapshot, snapLutId, snapLutK, onApplySnapshot)
        }
        // 局部组专属：一行**范围说明**（E2-21）+ 模式选择（关 / 径向 / 线性）
        if (group == EditorTab.LOCAL) {
            Text(
                LOCAL_SCOPE_HINT,
                color = FilmTheme.CreamDim,
                fontSize = 11.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 1.dp),
            )
            LocalModeRow(localMode, onLocalMode)
        }

        ParamNameRow(current, group, onResetParam, onResetGroup)

        // ④ 单滑杆：**只显示当前选中参数的那一条**（ADR-14 最关键的结构差异）
        if (current != null && film != null) {
            // key：换参数 / 重置 / 套用原片风格后重建滑杆 state（否则读数与槽位不符 = 假滑杆）
            key(current.key, refreshTick) {
                EditorSlider(current, film, onCommit, onFinished)
            }
        } else {
            // 无参数可调：参数还没加载完（E2-14 起滤镜组常驻三条引擎参数，不再有"未选 LUT
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
        // ⑤ 参数图标行。滤镜组**拆成两行**（E2-15）：LUT 一行（42 个素材，横滚很深）+
        // 本组参数一行（褪色 / 强度）。挤在同一行时，参数被埋在几十张 LUT 卡片之后 ——
        // 用户横滚半天找不到「褪色」，等于参数不存在（ADR 记的"LUT 与参数挤一行"问题）。
        // E2-16 再给 LUT 行加一条**分类标签行**（ADR-17）：从"横滚几十屏找徕卡"变成点一下。
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
        EditorTabBar(
            group, onGroup, onCrop,
            presetActive = presetActive, onPreset = onPreset,
        )
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
 *
 * E2-23：按钮可见性从"快照里有 LUT 资产"放宽到 **"有快照"** —— E2-21 起「套用」下发的是
 * **参数 + LUT**，标准基线拍的原片（没记 LUT）同样有整套参数可套；按 LUT 决定可见性会让这类
 * 照片的"套用参数"**根本不可达**。无快照仍不给按钮（提示行已说明"已用标准基线"）。
 * 快照里的 LUT 若已被用户从库中删掉，按钮也保留：参数仍能套用，点下去会明确提示 LUT 那部分
 * 失败（`applySnapshotStyle` 的 `entry == null` 分支）——"点了注定失败"的前提已不成立。
 */
@Composable
private fun SnapshotStyleRow(
    context: Context,
    hasSnapshot: Boolean,
    snapLutId: String?,
    snapLutK: Float,
    onApply: () -> Unit,
) {
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
        if (hasSnapshot) {
            Text(
                // E2-23 文案：E2-21 起这个按钮下发的是**参数 + LUT**（不只是风格素材），而左侧
                // 在"原片未使用 LUT"时只说到了 LUT —— "套用"会指向不明，故写明"参数"。
                "套用参数",
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
 * 滤镜组的 **LUT 图标行**（E2-1 的 LUT 圆形按钮；E2-15 从"与参数同行"拆出来独立一行；
 * E2-16 按 ADR-17 加**分类标签行**）：上面一行是分类标签（「全部」+ [LutLibrary] 的分类），
 * 下面一行是「无」（= 清 LUT）+ 当前分类的 LUT 圆形缩略图。
 *
 * 为什么拆行（E2-15 用户反馈的连带修正）：LUT 有 42 个，横滚很深 —— 参数挤在同一行时
 * 用户要滑过几十张卡片才看得到「褪色」，等于参数不存在。拆开后 LUT 一行、参数一行，
 * 两者都一眼可见；代价只是面板高一点点（本组参数少，两行加起来仍矮于其他组的滑杆区）。
 *
 * E2-16：分类标签多占一行（≈30dp），换来的是"42 个 LUT 里找徕卡"从横滚几十屏变成点一下。
 * 分类数据**全部来自 [LutLibrary]**（资产头部品牌 + 用户覆盖 / 自定义分类），此处不硬编码
 * 任何分类名（ADR-17 第 6 条）；过滤与「全部」语义见 [lutInCategory] / [lutCategoryTabs]。
 */
@Composable
private fun LutIconRow(
    lutId: String?,
    previews: Map<String, Bitmap>,
    onPick: (LutLibrary.Entry?) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("filmkit", 0) }
    val entries = LutLibrary.entries(context)
    // 每次重组现算（42 次 prefs 内存读 + distinct，微秒级）：不做 remember 缓存就不会有
    // "别处改了分类、这里还显示旧标签"的陈旧态。分类 = 条目分类 + 自定义分类，去重保序。
    val categories = lutCategoryTabs(
        entries.map { LutLibrary.categoryOf(prefs, it) },
        LutLibrary.customCategories(prefs),
    )
    var category by remember { mutableStateOf<String?>(null) } // null = 「全部」（不过滤）
    val shown = lutInCategory(entries, category) { LutLibrary.categoryOf(prefs, it) }
    val listState = rememberLazyListState()
    // 切分类后缩略图行回到行首：42 个 LUT 滚到深处再切分类，若保留旧滚动位置会被夹到列表末尾
    // ——用户看到的是"这个分类没东西"，而内容其实在左边看不见的地方（"原图"也就跟着被滚走）。
    LaunchedEffect(category) { listState.scrollToItem(0) }
    Column(Modifier.fillMaxWidth()) {
        LutCategoryRow(categories, category) { category = it }
        Spacer(Modifier.height(6.dp))
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 「无」（= 清 LUT，ADR-17 说的「原图」）**固定在第一位且不随分类过滤** ——
            // 任何时候都能一键回到"无 LUT"（既有能力，E2-16 只是确认保留）。
            item(key = "__none__") {
                ParamChip(label = "无", selected = lutId == null, icon = Icons.Outlined.Close) { onPick(null) }
            }
            items(shown, key = { it.id }) { e ->
                ParamChip(
                    label = e.label,
                    selected = lutId == e.id,
                    bitmap = previews[e.id],
                ) { onPick(e) }
            }
            // 空分类（用户新建后没把任何 LUT 归进去，或原本归入的 LUT 被改走）：给一句明确
            // 说明而不是留一行空白（ADR-17 验收允许"不显示空分类"或"显示空态提示"，取后者 ——
            // 分类行是用户自己建的，凭空消失比"这里没东西"更让人困惑）。
            if (shown.isEmpty()) {
                item(key = "__empty__") {
                    Box(Modifier.height(56.dp), contentAlignment = Alignment.Center) {
                        Text("该分类下暂无 LUT", color = FilmTheme.CreamDim, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * LUT 分类标签行（E2-16 / ADR-17）：横向滚动，「全部」+ 各分类，**选中态 = 强调色下划线**。
 *
 * [selected] 用 `String?` 表达三态（null = 「全部」），与 [lutInCategory] 的入参同源 ——
 * "不过滤"只有一种表示，不需要额外的布尔标志。
 */
@Composable
private fun LutCategoryRow(
    categories: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item(key = "__all__") {
            LutCategoryTab(label = "全部", selected = selected == null, dot = null) { onSelect(null) }
        }
        items(categories, key = { it }) { c ->
            LutCategoryTab(
                label = c,
                selected = selected == c,
                dot = LutDotColors[categoryDotIndex(c, LutDotColors.size)],
            ) { onSelect(c) }
        }
    }
}

/**
 * 单个分类标签：`● 分类名` + 选中下划线。
 *
 * 未选中时下划线画成透明（占位不塌陷）—— 否则切换分类时整行高度会跳 2dp，横滚位置也跟着抖。
 */
@Composable
private fun LutCategoryTab(label: String, selected: Boolean, dot: Color?, onTap: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .pressScale(pressedScale = 0.94f, onTap = onTap)
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dot),
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                label,
                color = if (selected) FilmTheme.Accent else FilmTheme.CreamDim,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 96.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .width(22.dp)
                .height(2.dp)
                .background(if (selected) FilmTheme.Accent else Color.Transparent),
        )
    }
}

/**
 * 分类圆点色板（E2-16 / ADR-17 第 5 条）：**纯装饰**，颜色不携带任何分类语义 ——
 * 只帮眼睛在横滚时区分相邻标签。取色规则见 [categoryDotIndex]（同名恒同色，与顺序无关）。
 *
 * ⚠ 这 7 个色值没有进 [FilmTheme]（该文件要求"UI 只允许引用这里声明的颜色"）：本卡约束是
 * "只改 EditorScreen.kt"，故暂放此处；要收口到单一色源就整块搬进 FilmTheme.kt，无其他引用点。
 */
private val LutDotColors = listOf(
    Color(0xFFE8842C), // 琥珀
    Color(0xFF6FA8DC), // 天蓝
    Color(0xFF8FBF6F), // 苔绿
    Color(0xFFD98CB3), // 藕粉
    Color(0xFFB69CE0), // 藤紫
    Color(0xFF7FCBC4), // 青瓷
    Color(0xFFD9B25F), // 麦黄
)

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
 * ⑥ 底部导航（ADR-14 层⑥）：调整 / 滤镜 / 暗角 / 局部，**选中 = 黄色圆角矩形底 + 深色图标与文字**。
 *
 * 选中态用「强调色实底 + [FilmTheme.OnAccent] 前景」而不是"黄底 + 黄字"：黄底黄字的对比度
 * 约 1.4:1（WCAG 远不达标），实底 + 深字是 6.8:1。同一个"黄色圆角矩形底"的语言，可读性优先。
 *
 * ★ E2-12：「裁剪」项**已激活**（可点，进裁剪模式时高亮 —— 由 `cropActive` 传入）。
 * ★ E3-1a：「预设」项**也已激活**（可点，面板打开时高亮 —— 由 `presetActive` 传入）：
 * 预设的保存入口就在它打开的面板里（**不新增顶栏按钮** —— 调研核查过 `EditorTopBar` 只有
 * 取消 / 相册 / 撤销 / 重做 / 覆盖 / 完成，硬塞一个「保存预设」会挤占主操作位）。
 */
/**
 * ★ E2-12 裁剪工具栏（ADR-16）：裁剪模式下**取代参数面板**（两块互斥，不叠在预览上 ——
 * 与 E1-4 起"面板不遮挡预览"的约束一致）。
 *
 * 这里只放**离散动作**：比例锁定 / 旋转 90° / 取消 / 确认。拖动框与缩放在预览区完成
 *（见预览区的裁剪手势）—— 拖动中只更新框、不渲染，点「确认」才渲染一次（与 E2-8 的
 * 滑杆闸门同一精神：零引擎开销 + 实时反馈）。
 *
 * 比例锁定：选中后**立即**按该比例重排当前框（[cropViewWithAspect]），后续拖动按比例联动
 *（[cropViewScale]）；「原图」= 当前画面比例 ⇒ 等价于"整幅不裁"。
 */
@Composable
private fun CropToolBar(
    aspect: CropAspect,
    /** 草稿与已确认状态不同 ⇒ 提示行与「确认」按钮高亮（与 E2-9 的"有修改"同一精神） */
    dirty: Boolean,
    onAspect: (CropAspect) -> Unit,
    onRotate: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(FilmTheme.PanelSolid)
            .navigationBarsPadding()
            .padding(bottom = 6.dp),
    ) {
        Text(
            if (dirty) "裁剪已修改 · 点「确认」生效" else "拖动画面移动裁剪框 · 拖角/边缩放",
            color = if (dirty) FilmTheme.Accent else FilmTheme.CreamDim,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = TextStyle(shadow = TextShadow),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyRow(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(CropAspect.entries) { a ->
                val on = a == aspect
                Text(
                    a.label,
                    color = if (on) FilmTheme.OnAccent else FilmTheme.CreamDim,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) FilmTheme.Accent else Color.Transparent)
                        .border(
                            1.dp,
                            if (on) FilmTheme.Accent else FilmTheme.CreamDim.copy(alpha = 0.35f),
                            RoundedCornerShape(10.dp),
                        )
                        .pressScale(pressedScale = 0.94f, onTap = { onAspect(a) })
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 旋转 90°（顺时针步进，4 个方向循环）：画面转，框跟着走 —— 自由 = 框跟随内容
            // 转；锁定比例 = 保持中心按新朝向重排（见 [cropViewRotate]，E2-12c）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        1.dp, FilmTheme.CreamDim.copy(alpha = 0.35f), RoundedCornerShape(10.dp),
                    )
                    .pressScale(pressedScale = 0.94f, onTap = onRotate)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.RotateRight,
                    contentDescription = "旋转 90°",
                    tint = FilmTheme.Cream,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("旋转 90°", color = FilmTheme.Cream, fontSize = 12.sp, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "取消",
                color = FilmTheme.CreamDim,
                fontSize = 13.sp,
                fontFamily = FilmTheme.Mono,
                modifier = Modifier
                    .pressScale(onTap = onCancel)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "确认",
                color = FilmTheme.OnAccent,
                fontSize = 13.sp,
                fontFamily = FilmTheme.Mono,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(FilmTheme.Accent)
                    .pressScale(pressedScale = 0.94f, onTap = onConfirm)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * ★ E3-1a 预设面板（**最小入口**）：与裁剪工具栏同一层 —— 取代参数面板，不叠在预览上。
 *
 * 本卡只做核心闭环：**保存当前 / 套用 / 删除（二次确认）**。缩略图（用用户自己的照片实时
 * 渲染）、分组筛选 / 搜索、长按菜单（重命名 / 复制 / 收藏 / 更新为当前）留给 E3-1b，
 * 强度滑杆留给 E3-1c —— 面板上每一个动作背后都已经有一条跑得通的纯逻辑（EditorExport.kt）。
 *
 * ★ P-2c：列表 = 用户预设 + **内置三条**（`BuiltinPresets.all`）。内置预设**不可删** ——
 * 它们的删除按钮根本不渲染（[PresetRow] 按 [presetDeletable] 分支，不是"点了才报错"）；
 * 其余操作（套用 / 强度）与用户预设完全一致。重命名 / 更新为当前这类操作本面板**还没有**
 *（E3-1b 才做），所以本卡不需要为它们加保护。
 *
 * 为什么保存入口在这里而不是顶栏：`EditorTopBar` 只有 取消 / 相册 / 撤销 / 重做 / 覆盖 /
 * 完成（调研 §5.4 的事实核对），硬塞一个「保存预设」会挤占主操作位；而底栏「预设」本来就是
 * ADR-14 底栏六项之一（此前置灰留位），"在预设面板里保存"也正是 Lightroom 的组织方式
 *（Presets 面板底部的 +）。
 *
 * 列表项显示"名称 + 分组 + 槽位数"而不是缩略图：缩略图必须用**用户自己的照片**渲染
 *（Lightroom / Capture One 都如此），预烘焙的通用图反而会误导（调研 §5.1 D5）。
 */
@Composable
private fun PresetPanel(
    presets: List<EditorPreset>,
    /** 参数已就绪（首帧前不能保存） */
    canSave: Boolean,
    /** 与参数面板共用同一行提示（保存 / 套用 / 删除的结果都在这里回显） */
    status: String,
    /** ★ E3-1c：当前生效的预设（null = 还没有套用过 ⇒ 不显示强度行） */
    applied: EditorPreset?,
    /** ★ E3-1c：强度（0..1.5，1.0 = 预设原样） */
    amount: Float,
    onAmount: (Float) -> Unit,
    onAmountFinished: () -> Unit,
    onSave: (String, String) -> Unit,
    onApply: (EditorPreset) -> Unit,
    onDelete: (EditorPreset) -> Unit,
    onHandleDrag: (Float) -> Unit,
    onTogglePanel: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<EditorPreset?>(null) }
    val canCommit = canSave && name.isNotBlank()
    Column(
        Modifier
            .fillMaxWidth()
            .background(FilmTheme.PanelSolid)
            .navigationBarsPadding()
            .padding(bottom = 6.dp),
    ) {
        PanelHandle(onDragEnd = onHandleDrag, onToggle = onTogglePanel)
        Text(
            status.ifEmpty { "预设 · 只保存与默认值不同的项；套用时其余参数保持不动" },
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
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PresetField(name, { name = it }, "预设名称", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            PresetField(group, { group = it }, "分组（可空）", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(
                "保存当前",
                color = if (canCommit) FilmTheme.OnAccent else DisabledInk,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (canCommit) FilmTheme.Accent else Color.Transparent)
                    .border(
                        1.dp,
                        if (canCommit) FilmTheme.Accent else DisabledInk,
                        RoundedCornerShape(10.dp),
                    )
                    .pressScale(enabled = canCommit, onTap = {
                        if (canCommit) {
                            onSave(name, group)
                            name = ""
                            group = ""
                        }
                    })
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        // ★ E3-1c 强度（Amount）：**套用之后**出现，改它 = 立即重套（(b) 方案：滑杆常驻在
        // 生效中的预设上，Lightroom Preset Amount 的语义）。读数 0..150 与 LUT 强度同口径
        // （[formatZero150]）；量程 0..1.5 且**中点标记画在 100%**（[pivotFraction]），
        // 让"预设原样"这条基准一眼可见 —— 与其它滑杆的"不变点"是同一种语言。
        //
        // 拖动中不渲染、松手渲染一次：`RelativeSlider` 的 onValueChange → 外部只写槽位 +
        // SliderRenderGate.onValueChange()；onDragEnd → onPresetAmountRelease()（渲染 + 记一步）。
        applied?.let { p ->
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "强度 · ${p.name}",
                    color = FilmTheme.CreamDim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatZero150(amount),
                    color = FilmTheme.Accent,
                    fontSize = 13.sp,
                    fontFamily = FilmTheme.Mono,
                    fontWeight = FontWeight.SemiBold,
                    style = TextStyle(shadow = TextShadow),
                )
            }
            RelativeSlider(
                value = amount,
                onValueChange = onAmount,
                onDragEnd = onAmountFinished,
                range = PRESET_AMOUNT_UI_RANGE,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                rowHeight = 40.dp,
                ticks = true,
                midFraction = pivotFraction(PRESET_AMOUNT_UI_RANGE, 1f),
                barThumb = true,
            )
        }
        if (presets.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (canSave) "还没有预设：填名称 → 点「保存当前」" else "参数加载中…",
                    color = FilmTheme.CreamDim,
                    fontSize = 12.sp,
                )
            }
        } else {
            // 限高：面板嵌在布局流里（预览区 weight(1f)），列表再长也不该把预览挤没
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 168.dp),
            ) {
                items(presets, key = { it.id }) { p ->
                    PresetRow(p, onApply = { onApply(p) }, onDelete = { pendingDelete = p })
                }
            }
        }
    }
    // 删除二次确认（本卡硬要求）：取消 = 什么都不做，只关框（预设、参数、画面全不动）
    pendingDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            shape = RoundedCornerShape(18.dp),
            title = { Text("删除预设？", color = FilmTheme.Cream, fontFamily = FilmTheme.Serif) },
            text = {
                Text(
                    "「${p.name}」将被删除，无法恢复。\n当前画面与参数不受影响。",
                    color = FilmTheme.CreamDim,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(p)
                }) {
                    Text("删除", color = FilmTheme.Accent, fontFamily = FilmTheme.Mono)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消", color = FilmTheme.CreamDim, fontFamily = FilmTheme.Mono)
                }
            },
        )
    }
}

/** 预设面板的单行输入（名称 / 分组）：自绘底 + 描边，颜色只用 FilmTheme 的既有 token */
@Composable
private fun PresetField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(FilmTheme.ChipBg)
            .border(1.dp, FilmTheme.ChipBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty()) {
            Text(hint, color = DisabledInk, fontSize = 12.sp, maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = FilmTheme.Cream, fontSize = 12.sp),
            cursorBrush = SolidColor(FilmTheme.Accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 预设列表的一行：名称 + 副标题（分组 · 槽位数 · LUT）+「套用」/「删除」。
 *
 * ★ P-2c：**内置预设不可删**（[presetDeletable]）—— 删除按钮的位置换成一枚**不可点**的
 * 「内置」标记。**不是"点了才报错"**：删除按钮根本不渲染（也就没有"点了才发现删不掉"的路径）。
 * 理由：内置包随包分发，删了下次启动又回来；留一个能点的删除按钮，用户会以为删掉了。
 * 用户自建预设（`source == "user"`）的渲染与行为**逐位不变**。
 */
@Composable
private fun PresetRow(p: EditorPreset, onApply: () -> Unit, onDelete: () -> Unit) {
    val sub = buildString {
        if (p.group.isNotEmpty()) append(p.group).append(" · ")
        append(p.slots.size).append(" 项参数")
        if (p.lutId != null) append(" · LUT")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                p.name,
                color = FilmTheme.Cream,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                sub,
                color = FilmTheme.CreamDim,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "套用",
            color = FilmTheme.OnAccent,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(FilmTheme.Accent)
                .pressScale(pressedScale = 0.94f, onTap = onApply)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Spacer(Modifier.width(6.dp))
        // ★ P-2c：内置预设没有删除按钮（只有一枚说明性的「内置」标记）——
        // 判据是 presetDeletable，不在 UI 里重写一遍 source 比较。
        if (presetDeletable(p)) {
            Text(
                "删除",
                color = FilmTheme.CreamDim,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .border(1.dp, FilmTheme.ChipBorder, RoundedCornerShape(9.dp))
                    .pressScale(pressedScale = 0.94f, onTap = onDelete)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        } else {
            Text(
                "内置",
                color = DisabledInk,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .border(1.dp, DisabledInk, RoundedCornerShape(9.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun EditorTabBar(
    selected: EditorTab,
    onSelect: (EditorTab) -> Unit,
    onCrop: () -> Unit,
    cropActive: Boolean = false,
    /** ★ E3-1a：预设面板打开 / 收起 */
    onPreset: () -> Unit = {},
    presetActive: Boolean = false,
) {
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
        // ★ E3-1a 预设：**已激活**（可点，面板打开时高亮）—— 不再是置灰留位项。
        // 「保存当前参数为预设」的入口就在它打开的面板里：顶栏没有这个位置（调研 §5.4 的事实
        // 核对：顶栏只有 取消 / 相册 / 撤销 / 重做 / 覆盖 / 完成），而底栏「预设」本来就是
        // Filmix 的产品位（ADR-14 的底栏六项之一）。
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(if (presetActive) FilmTheme.Accent else Color.Transparent)
                .pressScale(pressedScale = 0.94f, onTap = onPreset)
                .padding(vertical = 6.dp),
        ) {
            Icon(
                Icons.Outlined.Collections,
                contentDescription = "预设",
                tint = if (presetActive) FilmTheme.OnAccent else FilmTheme.CreamDim,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "预设",
                color = if (presetActive) FilmTheme.OnAccent else FilmTheme.CreamDim,
                fontSize = 10.sp, maxLines = 1,
            )
        }
        // ★ E2-12 裁剪：可点（进裁剪模式），裁剪模式下高亮（与其余四组同一套选中态）
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(if (cropActive) FilmTheme.Accent else Color.Transparent)
                .pressScale(pressedScale = 0.94f, onTap = onCrop)
                .padding(vertical = 6.dp),
        ) {
            Icon(
                Icons.Outlined.Crop,
                contentDescription = "裁剪",
                tint = if (cropActive) FilmTheme.OnAccent else FilmTheme.CreamDim,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "裁剪",
                color = if (cropActive) FilmTheme.OnAccent else FilmTheme.CreamDim,
                fontSize = 10.sp, maxLines = 1,
            )
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
 * 编辑器的离开出口（E2-24）。三条离开路径收敛成这两个出口，**出口本身**决定确认后去哪：
 *  - [BACK]：「取消」按钮、系统返回（面板已收起时）⇒ 回上一页（查看页 / 相机页，由路由层决定）
 *  - [GALLERY]：顶栏菜单图标 ⇒ 回相册
 *
 * 为什么不直接存两个 lambda（`pendingExit: (() -> Unit)?`）：出口必须能在 JVM 单测里被断言
 * "确认后走的是不是它"（[exitAfterConfirm]），lambda 断言不了。
 */
internal enum class ExitTarget { BACK, GALLERY }

/**
 * 请求离开时的**决策**（E2-24，纯逻辑）：需确认 ⇒ 返回待确认的那个出口（调用方弹确认框，
 * 记住出口本身）；不需确认 ⇒ 返回 null，调用方**直接走** [target]（不打扰用户）。
 *
 * 判据（`needsConfirm`）由调用方从 [discardNeedsConfirm] 取 —— 与导出直通同一份逻辑，这里
 * 不重复实现，只负责"要不要弹"这一层的选择。
 */
internal fun pendingExitFor(needsConfirm: Boolean, target: ExitTarget): ExitTarget? =
    if (needsConfirm) target else null

/**
 * 用户在确认框里点「放弃」之后**实际走的出口**（E2-24，纯逻辑）：就是挂起的那个出口本身；
 * `null`（出口已被清掉 / 框被点掉）⇒ 不动作。
 *
 * 这个恒等映射**就是分流的规格**：从菜单进来的（[ExitTarget.GALLERY]）确认后必须回相册，
 * 从返回键进来的（[ExitTarget.BACK]）必须回上一页 —— 两者不许串（单测钉住，串了就是
 * "点菜单确认后回上一页 / 按返回确认后跳相册"这种一眼可见的错）。
 */
internal fun exitAfterConfirm(pending: ExitTarget?): ExitTarget? = pending

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
 * 覆盖前是否必须弹「RAW 数据丢失」警告（E2-19，用户真机反馈："DNG 覆盖时给个警告"）。
 *
 * 场景：源图是 DNG（非 JPEG）、用户改了参数后点「覆盖原片」—— 覆盖写回的是**渲染出的
 * JPEG**，DNG 里的 RAW 数据就此永久消失（不可恢复）。这一枪必须先亮明后果再让用户按。
 *
 * 两条判据缺一不可（与 [passthroughAllowed] 是同一对输入的**反向**用法）：
 *  - [paramsUnchanged] = true（没改过）⇒ **根本不会覆盖**：`exportNow` 直接拒绝
 *    （"没有修改，无需覆盖"，一个字节都不写）⇒ 不该弹警告 —— 弹了是虚惊一场，
 *    还挡不住任何数据损失；
 *  - [jpegSource] = true（源图本来就是 JPEG）⇒ 覆盖的代价只是"再一次有损重编码"，
 *    不是"RAW 永久丢失"级别 ⇒ 走既有的「覆盖原片？」确认框即可，不必多吓一次。
 *
 * 源图格式判据**复用 E2-18b 的 [isJpegSource]**（不另写一套判断）：两处口径同源，
 * 才不会出现"直通说这不是 JPEG、警告却说它是 JPEG"的自相矛盾。
 *
 * 纯逻辑、无 Android 依赖 ⇒ JVM 单测直接钉住三种组合（见 `EditorOverwriteWarnTest`）。
 */
internal fun overwriteNeedsRawWarning(paramsUnchanged: Boolean, jpegSource: Boolean): Boolean =
    !paramsUnchanged && !jpegSource

// ── E2-16 LUT 分类（ADR-17）：纯逻辑，无 Android 依赖 ⇒ JVM 单测直接钉住 ─────────

/**
 * 分类标签行的分类列表（E2-16 / ADR-17）：条目分类 + 自定义分类，**去空白、去重、保序**。
 *
 * 与 [LutLibrary.allCategories]（需要 Context，JVM 测不了）**同规则**：这里把规则抽成纯函数，
 * 数据仍全部来自 LutLibrary（`entries` 的分类 + `customCategories`），不硬编码分类名。
 * 顺序 = 资产扫描顺序（内置品牌按文件名序出现）+ 自定义分类按创建顺序追加 —— 不另排序：
 * 排序规则一变，用户记的"徕卡在哪"就失效，且 42 个 LUT 的分类本来就是按品牌聚簇的。
 */
internal fun lutCategoryTabs(
    entryCategories: List<String>,
    customCategories: List<String>,
): List<String> = (entryCategories + customCategories).filter { it.isNotBlank() }.distinct()

/**
 * 按分类过滤 LUT（E2-16 / ADR-17 第 3 条）：[category] 为 null（「全部」）⇒ **原样返回不过滤**；
 * 否则按 [categoryOf] 精确匹配。无匹配 ⇒ 返回空表（调用侧显示空态提示，见 `LutIconRow`）。
 *
 * 泛型而非直接收 [LutLibrary.Entry]：让这条规则在 JVM 单测里零 Android 依赖可验。
 */
internal fun <T> lutInCategory(
    items: List<T>,
    category: String?,
    categoryOf: (T) -> String,
): List<T> = if (category == null) items else items.filter { categoryOf(it) == category }

/**
 * 分类圆点的色板下标（E2-16 / ADR-17 第 5 条）：按分类名哈希取色 —— 同名恒同色、与顺序无关。
 *
 * `and 0x7FFFFFFF` 而不是 `abs()`：`Int.MIN_VALUE` 的 `abs()` 仍是负数（溢出），取模会得到
 * 负下标 ⇒ 越界崩溃。掩掉符号位没有这个洞。`paletteSize <= 0` 返回 0（防御，正常不会发生）。
 */
internal fun categoryDotIndex(name: String, paletteSize: Int): Int =
    if (paletteSize <= 0) 0 else (name.hashCode() and 0x7FFFFFFF) % paletteSize

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
