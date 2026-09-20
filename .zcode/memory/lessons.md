---
summary: 踩坑记录:现象、根因与规避方式
created: 2026-09-18 10:20
updated: 2026-09-18 10:20
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
