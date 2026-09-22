---
summary: 踩坑记录:现象、根因与规避方式
created: 2026-09-18 10:20
updated: 2026-09-21 19:46
status: active
---

# 教训

<!-- 踩过的坑:现象 → 根因 → 修复/规避方式。未经验证的猜测不写。 -->

- [2026-09-18] **中文路径下 AGP externalNativeBuild 报 CXX1400（路径中文段丢失）**：AGP 路径解析 bug；规避：自定义 CMake Exec 任务直驱 NDK 工具链，产物经 jniLibs 打包
- [2026-09-18] **X300 Mali 上 RG8 TexSubImage2D 静默输出全黑**（无 GL 错误）：驱动缺陷；规避：预览改 SurfaceTexture 系统路径，静拍 CPU 转 RGB888 单纹理上传；零拷贝 AHardwareBuffer 列 M3
- [2026-09-18] **RGBA8 FBO 永不完整 → 照片全黑**：makeTex 给 RGBA8 误用 GL_HALF_FLOAT；规避：按内部格式选类型（RGBA8=UNSIGNED_BYTE）
- [2026-09-18] **照片灰度无色彩**：CPU YUV→RGB 中 Y 未归一化（0–255 直接乘系数），亮度比色度大 255 倍；规避：Y/255 后再乘，RGB×255
- [2026-09-18] **照片/预览左右颠倒**：Y 平面行序（顶行在上）与 GL 纹理原点（底行）不一致叠加旋转；规避：OES 路径由 STMatrix 接管，静拍输出 pass 做 v 翻转
- [2026-09-18] **GLSL ES3.1 image uniform 编译失败 S0032**：image 类型无默认精度，Mali 强制显式；规避：全部显式 highp；ES3.0 禁用 uniform layout(location)
- [2026-09-18] **暗部整体灰化**：log 域映射 log2(lin)*0.5+0.5 仅覆盖 lin∈[0.25,4]，场景暗部全被夹到 0 再抬回 0.5 中灰；规避：以 0.18 中灰为轴 ±4EV（log2(lin/0.18)/8+0.5）
- [2026-09-18] **预设参数全零 → 黑片/灰板**：DoubleArray 沉默零值（wb=0 全黑、contrast=0 灰板、cross=0 信号清零）；规避：中性基线+覆盖模式，引擎 fromDoubles 防呆回退
- [2026-09-18] **后台恢复黑屏**：LaunchedEffect(surface) 以对象相等性为 key，OriginOS 恢复不换 Surface 实例；规避：surface 回调内直接幂等 start + ON_PAUSE/ON_RESUME 生命周期感知
- [2026-09-18] **帧闸竞态致恢复后无帧**：stop 期间 post 失败 pending 永久卡 true；规避：post 失败回滚 + stopInternal 强制复位
- [2026-09-18] **预览压缩拉伸**：STMatrix 已含生产者裁剪/翻转，引擎再叠加自算 center-crop = 双重裁剪；规避：预览几何完全信任 STMatrix
- [2026-09-18] **控制项预览不生效但实拍生效**：setter 只改字段未调 rebuildRepeating；规避：所有 setter 必须触发重建 + 签名去重防刷屏
- [2026-09-18] **焦段条"24"切到长焦**：变焦比误用物理焦距（8.6mm）做分母；规避：按当前镜头 35mm 等效焦距换算
- [2026-09-18] **X300 隐藏物理镜头（超广 id4）持续输出黑帧**：厂商隐藏镜头流兼容性；规避：设置页按镜头启停（用户自关），探测保留用于审计
- [2026-09-18] **手动对焦 FIXED**：HAL 上报屈光度 0 但硬件支持；规避：默认 10D 试值开放滑杆
- [2026-09-18] **API36 移除 COLOR_CORRECTION_RB_GAINS**：手动 WB 改 CONTROL_COLOR_CORRECTION_MODE_CCT + COLOR_TEMPERATURE 直填 K
- [2026-09-18] **触屏对焦从不触发（日志零条 focus.set）**：setOnTouchListener 处理 ACTION_UP 却返回 false，不消费事件流则 UP 永不送达；规避：返回 true 消费
- [2026-09-18] **切镜头后黑屏（session_ready 从不出现）**：setFocal/setLens 置 started=false 后 openCamera 不恢复，onConfigured 的 !started 守卫放弃建 session；规避：openCamera 提交前恢复 started，openGen 代次丢弃快速连点的过期回调
- [2026-09-18] **异步清理误删新资源**：切镜头 post 清理 runnable 读字段会拿到 openCamera 刚赋的新实例；规避：post 前在调用线程捕获旧引用并摘下字段
- [2026-09-18] **对焦圆圈中心偏离点击点**：偏移写死像素（-28）与 dp 尺寸（56.dp）不匹配，高密度屏漂 (密度-1)×28px；规避：Dp.roundToPx() 动态换算
- [2026-09-18] **AF/AE 区域落点与点击位置差 90°**：视图竖屏坐标直填横向 active array（X300 sensorOrientation=90）；规避：按总旋转 k=(sensorOrientation/90+orientationStep)%4 反推，k=1:(ny,1-nx)、k=2:双反、k=3:(1-ny,nx)；focus.set 日志记录 rot/sx/sy 供核对
- [2026-09-18] **通用教训：跨语言参数数组沉默零值**（wb/contrast/cross）与「设置器必须触发应用」是本项目的两类高危模式，新参数一律走中性基线+防呆
- [2026-09-19] **X300 HAL 手动 WB 三路径全不可用**：CCT 直填被忽略；GAINS/COLOR_TEMPERATURE 结果键不回填（GAINS 作为结果键 API36 仍在但 X300 不给值）；变换矩阵直发（对角/行/列缩放）观感不可控（行缩放=CCM 后通道增益会发绿）——解法是引擎侧补偿，勿再回头调 HAL
- [2026-09-19] **预览 center-crop sx/sy 是四象限映射**：分配取决于着色器自转奇偶 ⊕ STMatrix 换轴（|m[4]|>0.5，X300 把 sensor 90° 藏在矩阵里）；只看 rotation 参数必错；4:3 时 a==srcAspect 会掩盖 bug，务必多画幅验证
- [2026-09-19] **跨镜头变焦倍率虚高**：focalBase 轮询慢半拍，读数处须即时同步 currentLensEquiv()
- [2026-09-19] **ColorSpaceTransform API**：构造收 Rational[]（行主序），读取 getElement(col,row)；无下标/getColor
- [2026-09-19] **Box 内 AnimatedVisibility 被 ColumnScope 扩展遮蔽**：全限定 androidx.compose.animation.AnimatedVisibility 调用顶层函数
- [2026-09-19] **闪退无 Java 栈时先装捕获再复现**：未捕获异常处理器写 crash-latest.txt + JSONL app.crash（v0.5.6 已装）；XPAN 闪退装捕获后未再复现
- [2026-09-19] **EV 补偿范围因机而异**：UI 滑杆禁写死 ±4，用 evInfo()（characteristics 范围×步进）自适应，session_ready 日志记 evRange/evStep 供核对
- [2026-09-20] **引擎 shader 改动构建/单测全绿仍可能设备端全黑**：shader 是设备运行时编译——GLSL 非法重载 step(vec3,float) 致 prog_film_ 编译失败→init 失败→openCamera 不执行（黑屏+焦段全空，v0.5.13 事故）。铁律：shader 改动必须设备验证后才发版；M3 考虑抽独立 .glsl 用 glslangValidator 构建期校验
- [2026-09-20] **log 域 ±4EV 硬截断的观感代价巨大**：14.2% 像素钉死同一灰度=直方图单尖峰+暗部色带+文件体积 1/4 的共同主因；域外须分段延展，且 filmCurve 的 toe/shoulder exp 在域外发散，延展段必须绕开该函数
- [2026-09-20] **Bitmap.compress 不写任何 EXIF**：保存后用 ExifInterface 经可写 FileDescriptor（MediaStore openFileDescriptor "rw"）补写；InputStream 构造不支持 saveAttributes；ORIENTATION 不显式写会落非法 0
- [2026-09-20] **阈值判定无滞回必闪**：水平仪对齐 ±0.5° 单阈值在手抖下持续切色——进入 0.4°/退出 1.2° 滞回解决；平移气泡式水平仪结构上不可用（指示线滑出刻度区成半截+无旋转语义）
- [2026-09-20] **docs/开发计划.md 插入新版本节**：old_string 含「旧节标题+空行」会吞掉旧标题（本窗口发生 3 次）——插入新节时把旧标题保留在 new_string 尾部
- [2026-09-20] **行主序 CCM 数组传 glUniformMatrix3fv 必须 transpose=GL_TRUE**：GL 按列主序读取，GL_FALSE=作用转置矩阵→全局严重偏色（v0.5.20 品红根因，真机确认修复）
- [2026-09-20] **DngCreator.setOrientation 只收 EXIF orientation 1..8**：传 sensor 角度值（90）抛异常→DNG 保存失败；映射 0/90/180/270→1/6/3/8
- [2026-09-20] **Camera2 RAW 元数据键名/类型凭记忆易错，javap 对 SDK 核实**：WB 键=SENSOR_NEUTRAL_COLOR_POINT（非 JPEG_ 前缀）；SENSOR_DYNAMIC_BLACK_LEVEL 返回 float[4]（[R,Ge,Go,B] 顺序，非 BlackLevelPattern）；pattern 方法=getOffsetForIndex(col,row)
- [2026-09-20] **rememberSaveable 状态随子页进出销毁丢失**（本项目导航进出设置页销毁重建 CameraScreen）：跨会话 UI 状态（画幅 capture_aspect）必须 prefs 持久化
- [2026-09-20] **预览框 weight(1f) 弹性占满是"快门调不上移"根因**：剩余空间全被吃掉、底部行永远贴屏底；自适应场景用 weight(1f, fill=false)——4:3 按内容占高、16:9 受可用高度约束
- [2026-09-20] **shader 静态校验须分 stage**（含 gl_Position=vert），glslang 新版二进制名 glslang；brew 目录 root 属主装不了时用 Khronos release 二进制放 tools/bin（gitignore）
- [2026-09-20] **Bayer 结构事实**：G 位的 R/B 都在四正邻 ±1（两种 G 相位哪个轴是 R 不同）；R/B 位的对角才是对侧色。G 位按"对角/±2"采样必采到 G——色度被系统性稀释（936 饱和度 3.15→1.48 根因）
- [2026-09-21] **CFA 查表编码引入时必须用解码侧最小脚本逐相位验证**：k1/k2 位写反（GRBG 编成 GBRG 的 0x49）= R/B 相位对调+WB 增益错乘 = 系统性发红（R×1.33/B×0.75）；"bilinear/旧版不受影响（无 LUT）"是关键排查线索
- [2026-09-21] **色偏类离线复现必须选彩色显著区域**：中性区域对 R/B 对调不敏感，复现"无差异"会误导排查
- [2026-09-21] 逐行翻译 GLSL 做端到端复现时，LUT/查表类编码也要照翻，否则复现不了编码错误
- [2026-09-20] **fragment 内联重复卷积是性能杀手**：12MP 下 estAx 内联 9-tap LPF×4 方向未共享 → gMs 1414ms；中间图预计算+1-tap 读取 → 7ms（一半像素走轻路径+L2 命中）
- [2026-09-20] 确认引擎 so 是否重编看产物时间戳而非构建耗时——9s 增量构建也可能正确重编了单文件（自定义 CMake 任务 inputs.dir(src) 覆盖源码）
- [2026-09-21] **噪声测量用平坦块法**（8×8 块、梯度阈值 3.5、均值>40 过滤）——全图去均值残差混入纹理会高估 3–4 倍；降噪/色偏评估必须同链路 A/B（开/关），跨版本绝对值受 ISO/场景混杂
- [2026-09-21] **降噪强度过猛的量化特征**：细节 Laplacian 降幅 ≈ 降噪幅度（-48%/-48%）= 值域权重太松；σr/σc 系数收紧 + Y 值域收紧，保 RCD 细节优势
- [2026-09-21] **SENSOR_NOISE_PROFILE 的类型是 Java 数组 `Pair<Double,Double[]>[]`（不是 List）**：写成 `as? List<Any?>` 会永远失败（编译器 This cast can never succeed 警告），σ 自适应静默失效、恒走回退；正确写法 `as? Array<*>` + `android.util.Pair<*,*>` 逐元素防御解包。**修好后才敢断言 X300 不回填**（noise=0、nsHal=0.03）——同位置的旧结论「需 as? List<Any?>」已被本窗口证伪
- [2026-09-21] 给既有 shader 链加 sampler 时注意纹理单元重排（P2 dir 1→2、P3 dir/g 1,2→2,3 给新纹理让位），漏绑静默采错
- [2026-09-21] **全图 Laplacian 判"细节损失"会系统性高估**——它把噪声当细节计（σY 2.30 时梯度 P90≈5.7 几乎全是噪声，降噪必然压低它）；正确口径 = **强边缘锐度（梯度 top 0.5% 均值）**，噪声进不了该分位
- [2026-09-21] **降噪"过猛/两档无差别"的量化特征**：σY 恰降 1/2.8≈1/3（= 9 点平均理论值）+ 强档再乘系数也无变化 ⇒ 值域权重已饱和、滤波退化成无条件平均；JPEG 体积腰斩是同一根因
- [2026-09-21] **norm-controlled 保色会让过曝区过饱和**（真机：台灯灯泡变红/粉）：亮度压缩与去饱和**必须成对**（Filmic/AgX 同理）；硬截断之所以看着正常，只是因为它把三通道一起顶到 1
- [2026-09-21] **对称 S 曲线对"中灰以下为主"的暗光场景必然整体压暗**（该场景 P50 线性 0.11 < 中灰 0.18）：提亮只能靠中间调 gamma `pow(f,g)`，移枢轴几乎无效
- [2026-09-21] **RAW 饱和必须在 WB 域判定**（阈值 = black+(white−black)/gain；本例 R≥520/G≥1023/B≥747，而非各通道自己的 1023）；**从 JPEG 通道反推 RAW 饱和是错的**（曾据此误判 R/B 先饱和；DNG 实测 R+B 饱和而 G 未饱和 = 0.0000%）
- [2026-09-21] **DNG OpcodeList2 内部是大端、而 TIFF 头是小端 II**（DngCreator 产物）——第三方（Lightroom）兼容性待验
- [2026-09-21] **Camera2 键名凭记忆必错，用 javap 核 SDK**：Android 没有 STATISTICS_LENS_SHADING_MAP，正确键是 `STATISTICS_LENS_SHADING_CORRECTION_MAP`（返回 LensShadingMap，`getGainFactor(color,col,row)`）
- [2026-09-21] **libraw 参照默认 `auto_bright=on`**：rawpy `postprocess()` 把亮度自动拉高约 2.5×（本场景 37.6→94.4）——v0.5.42「饱和 −21% / 分离 −29%」的"寡淡"诊断是**亮度差被当成色彩差**。测色度首选 **CIELAB C\***（与亮度解耦）；`sep`/`neutral` 是绝对量，跨图必须同亮度，且"把暗方拉亮"本身会抬高其 C*（libraw 8.42→13.58，凭空造出 −33% 差距）
- [2026-09-21] **"文件里没有" ≠ "API 拿不到"**：DNG 无 `ForwardMatrix1/2` 是 DngCreator 不写，`CameraCharacteristics.SENSOR_FORWARD_MATRIX1/2` 给全了 —— 数据可得性必须直接探 API，别从产物反推
- [2026-09-21] **Camera2 标定矩阵键只在 `CameraCharacteristics`**（`CaptureResult` 没有 `SENSOR_COLOR_TRANSFORM1/2` 等）；`SENSOR_REFERENCE_ILLUMINANT2` 类型是 **Byte**（illuminant1 是 Integer）—— 编译期 Unresolved 才暴露，javap 一查即明
- [2026-09-21] **Kotlin 字符串里写英文双引号会截断字符串**（报 `Unresolved reference '寡淡'`）：中文文案改用「」
- [2026-09-21] **离线参照必须与实现同式**：Python 参照用"先插值矩阵再作用"，Kotlin 用"各光源相机白点插值"，单测立刻抓出 0.0023 偏差（> 容差）—— 用真机数据 + 离线参照的单测能提前抓实现偏差
- [2026-09-21] **ForwardMatrix 自洽性检查**：`FM·(1,1,1)` 应等于 D50 白点 (0.9642, 1.0, 0.8251)（不是"行和=1"）；用它验证 HAL/标定矩阵可信度
- [2026-09-21] **Gradle 构建需写工作区外的 `~/.gradle`**：DSH 沙箱 workspace-write 下直接失败（`gradle-8.13-bin.zip.lck: Operation not permitted`），需更宽权限
- [2026-09-22] **sampler 必须始终有合法绑定（v0.6.0-m4 全黑根因）**：给既有 shader 链加 sampler3D 后，关闭态提前 return 不绑纹理 → 纹理单元残留上一个 pass 的 **2D 纹理** → Mali **预测执行**分支里的 `texelFetch` → draw 报 `GL_INVALID_OPERATION(1282)`、整帧被丢弃、全黑；构建/单测/glslang 全绿都拦不住。诊断特征：`render.diag` 里 pass1/pass2 正常而 **pass3_out glErr=1282 且 r=g=b=0**。修法＝init() 建 1×1×1 恒等纹理 + 无论开关都绑定并下发全部 uniform（与 LSC「关闭档绑 1×1 全 1 网格」同类）
- [2026-09-22] **ExifInterface 的「兼容标签」只接受十进制数**（FNumber / DigitalZoomRatio / ExposureTime / SubjectDistance / GPSTimeStamp 走 `Double.parseDouble`）：传分数串 `"1/125"` 会 `NumberFormatException` → `Log.w` + 静默 return，标签被丢弃且无异常外露（21 张成片因此全缺 ExposureTime）
- [2026-09-22] **Kotlin 块注释支持嵌套**：KDoc 里写 `assets/luts/*.cube` 的那个 `/*` 会开启嵌套注释、把整个文件吞进注释（报 `Unclosed comment`，且指向文件末尾而非真凶行）——写路径通配时避开 `/*`
- [2026-09-22] **预览图分辨率必须 ≥ 显示像素**：卡片 76dp 在 3–3.5x 密度下是 228–266px，v0.6.5 输出 108px → 放大 2.2× 明显发糊；且缓存文件名要带尺寸（`<key>@320.png`），否则改了分辨率仍会读旧糊图
- [2026-09-22] **`git filter-repo` 的 `reset --hard` 会删掉工作区副本**：本轮 3 张误提交截图因此不可恢复（GitHub 也不允许按任意 SHA fetch 旧对象）→ 重写历史前先把这些文件复制到 /tmp
- [2026-09-22] **纯 2.2 幂 OETF 是"发闷"的独立成因**：W3C sRGB 规范原文 `The reason that a viewing gamma of 1.125 is used instead of 1.0 is to compensate for the viewing environment conditions`；纯 2.2 幂配 2.2 显示 = 系统 viewing gamma **1.0**，中间调系统性对比不足。且 sRGB 分段在暗部比 2.2 幂高 **11%–235%**，而 LUT 在 sRGB 编码域制作 → 喂进去的采样坐标整体偏低（修复方向：输出 OETF 改精确 sRGB 分段）
- [2026-09-22] **"只增强已浓的色"是倒置的色度增强**：`t=clamp(max|cc|/0.25)` + `sk=t²(3-2t)` 的 knee 0.25（≈64/255）意味着中低饱和区几乎不动，而画面大多数颜色恰在该区；工业做法是 darktable `vibrance.c` 的 `sw=sqrt(g²+b²)/256; ss=1+amount*sw`（**增益 ∝ 当前饱和度**）与 DCP `ProfileHueSatMap`（hue×sat×val 三维表）
- [2026-09-22] **AE 用单点分位且不重置 → 切镜头亮度跳变**：实测同机位主摄 luma 81.7 / 长焦 104.1；libcamera 做法是**区域加权平均**锚定 0.16（`kDefaultRelativeLuminanceTarget`）+ 死区 + 换 mode 时 `filtered_.totalExposure *= ratio; target_ = filtered_;`（按灵敏度比例守恒并**丢弃历史**）
- [2026-09-22] **局部对比增强若不保护全局对比就会发灰**：Night Sight 论文自己的反面证据 —— `we use CLAHE on the V channel... This increases local contrast but the global contrast is flattened, resulting in a low-contrast appearance.`；正面对策是"限制暗部提亮、保住黑位"与"低光下饱和度与场景亮度**反比**"
- [2026-09-22] **"从 PhotonCamera 导出"的资产 ≠ 其内部 `.plut`**：内部是 v1 无元数据（`.plut`，33³ 或 UINT16、`dtype` 区分），导出是 **64³（Hald level 8）且会烘进 `colorRecipeParams`**，命名规则也不同（`LEICA M9.cube` vs `Leica_M9_STD.plut`）——我曾据此误判"资产来源不同"，实际是同一项目的两条导出路径
- [2026-09-22] **在成片上二次套影调曲线是错的**：成片已过 OETF，再套曲线等于二次压缩（对比会爆到 90）；正确做法是从成片**反推** baseTone 的输入（`lin=(d/255)^2.2=baseTone(x)`，标准预设下整链 = baseTone + OETF），反推必须用**生成该成片的那个参数版本**（`tone-scan.py --legacy` 对应 ≤0.6.13 的旧归一化公式）
- [2026-09-22] **离线 CLI 模拟与真机实测有 5–8 个 L\* 的偏差**（场景光随时间变化 + 反推的量化损失）——离线只能判断趋势与方向，**绝对亮度必须真机**
- [2026-09-22] **`JAVA_HOME` 必须是 Homebrew openjdk**：本机 `/usr/libexec/java_home` 默认给的是 JDK 8，而 AGP 8.13 要求 ≥11（报 `Dependency requires at least JVM runtime version 11`）；用 `/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`
- [2026-09-22] **子代理调研会往工作区克隆大仓库**：本轮 `filmulator-gui/` 达 **184MB**（含 .git），提交前必须检查未跟踪目录体积；另 `tools/__pycache__/` 也会出现
