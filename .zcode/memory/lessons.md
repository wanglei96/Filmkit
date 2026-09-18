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
