# 0.14.25：性能专项、色度修复与切镜头黑图收线

日期：2026-11-17。版本：0.14.25 / 822。设备：Vivo V2509A。

## 发布内容

- 切镜头 / RAW 尺寸变化时，饱和掩码目标删除后立即清空句柄和尺寸，消除纹理名复用后
  被第二次删除、导致 CCM 输出全黑的问题；release 同步清理这组资源状态。
- 同一 CameraController 从后台恢复时保留实际镜头与有效数字变焦；非手势期间 UI
  同步实际等效焦距。session_ready 日志记录镜头 ID、等效焦距及倍率，避免只看 UI 判定。
- 汇入 0.14.21--0.14.23 已验证的完整六分量 PC3D 协方差累加、等样本 NEON SAD、
  JPEG/R RGBA 直接编码；保留原有编码质量、4:4:4、gainmap 与失败回退。
- 汇入 0.14.24 P4 / D1 修复：全分辨率六尺度 FP32 B3 starlet、完整噪声协方差传播、
  BT.2020 亮度轴、强度连续性、饱和核保护及非负域亮度守恒。
- 阶段计时同步开关仍默认开启，实验性关闭未在 App 启用；被否决的 mask cache
  没有接入生产。没有减少关键成像核、帧、样本或精度。

前几版的独立实验及限制见同目录 performance-0.14.21/22/23、guide-mask-pc3d 和
p4-d1-algorithm-audit 报告。历史报告中的“未提交/推送”指各次实验交付当时状态，
本次统一收线发布。不能宣称所有场景稳定 5s 内，也不能宣称 P4/D1 全面优于 PC3D。

## 黑图根因证据

两张实际异常 JPEG（13:00:31、13:00:49）解码为 3072x4096，RGB 范围 0--3，
均值 0.522263209，并非相册显示问题。两次 D3 相机 RGB 正常，D4 CCM 输出全零；
当时 CA 校正均未应用，一次高光接受、一次拒绝，不能归因于高光准入或 P4 强度。

ensureRawTex 在 RAW 尺寸变化时删除 tex_satm_dst_，但旧代码保留这个 GL 名字及尺寸。
后续目标分配复用该名字，passSatMask 再删除一次，实际删掉新建的图像目标。
LD_PRELOAD 纹理跟踪在旧库复现：

```text
TEXTURE_DELETE id=59 size=3072x4080 owner=passSatMask deleter=ensureRawTex
TEXTURE_DELETE id=59 size=3072x4096 owner=makeTex deleter=passSatMask
```

这解释了不必现：具体结果依赖分配顺序及驱动名字复用。最初每次拍摄前都 warmup
的独立测试未触发，不能作为否定证据；回归改为只在初次 warmup，符合 App 的实际使用。

修复没有改成像数学。相同完整尺寸首张对照 RGBA SHA256 两版一致：
`e328eedeebb5ef460e477ac85e3c0c7d61b00371247ebd41008ed978e6ea8363`。

## 回归与实拍

tools/check-renderer-device.cpp 新增 --lens-switch-cases：一个 renderer 连续执行主摄、
长焦、重复长焦、强度 0/1、回主摄、旋转及再切长焦，共八例；开启饱和掩码和色差路径。
已知明亮输入要求 RGB 均值 >10，不能再把黑底上的抖动判作正常图像。
内置 color_smooth=1 / engine_slot=0 以覆盖实际分配顺序，不依赖私人预设文件。

```sh
# 按 tools/check-renderer-device.cpp 头部说明编译并部署工具及原生库。
LD_LIBRARY_PATH=. ./check-renderer-device --lens-switch-cases --no-dft \
  --pc3d 0 --chroma-gate 0 --ae-hal --diag-stats --output .
# 追加 --size 4096 验证 4080x3072 与 4096x3072 的真实传感器尺寸变化。
```

- 旧 0.14.24 库：内置小尺寸及预设快照完整尺寸均在第二例变黑并失败。
- 修复库：内置小尺寸八例通过；完整尺寸快照八例通过，RGB 均值约 165.6--170.9。
- 真机 App 五张：主摄、主摄切长焦、重复长焦、Home 后恢复长焦、长焦回主摄全部正常。
  RGB 均值分别 54.708 / 97.008 / 88.966 / 99.254 / 65.326。此处均值只作非黑图检查，
  不用于比较不同焦段的画质或曝光。
- 后台恢复时实际 session 镜头为 3，等效 70.25037mm、倍率 1；UI 70mm 选中，
  实拍也是更窄视场，不再仅是 UI 假保持。回主摄实际为 24mm。
- 841 项 App 单元测试：0 失败、0 错误、2 跳过。旧 Pv3StaleFrameTest 源码哨兵遗漏
  十一月已有的同页删除交叉淡入条件，本次仅更新测试并锁住同页限制，未改相册运行逻辑。
- 生产 GLSL 数值测试（chroma-wavelet / highlight-noise）、85 份主体 shader 与高光
  shader 校验通过；ARM64 / x86_64 Release 构建通过。
- 864 个完整 JNI JPEG/R 旧新输出对照通过；采集诊断、性能分析、诊断线程检查通过。
- git diff --check 通过。尚无跨设备压力测试；实拍与离线用例不能证明所有切镜头时序均已覆盖。

自动拍照已结束。未清除 App 数据或删除手机成片，保留用户 PC3D 关闭、强边缘门关闭、
核尺寸自动等设置。手机最终停在主摄，可正常使用。

## APK 校验

文件：filmkit-0.14.25-LENS.apk，版本 822 / 0.14.25；v2 / v3 签名通过，v4 未启用。
DEX 存在 0.14.25，包内引擎与已构建引擎 SHA256 一致，关键 JNI 导出
nativeEngineSubmitRawBurst / nativeEncodeUltraHdr / nativeReadGainmap 存在。
已覆盖安装，设备版本确认 822 / 0.14.25。

APK SHA256：`4578b3a95df196e2a5c5c6d64d1e054f0aa9e3200ee015ec95608d9d3d9d2e9c`。

## 磁盘清理

只清理已确认可再生或重复的测试数据，不清空 /tmp、不删除不明临时挂载或其他任务数据。
项目无 tmp 目录，检查的是 macOS /private/tmp 以及项目 test_temp。

- SHA256 去重的生成物、已确认手机仍留原片的临时照片副本、旧诊断派生数组和旧 APK
  解包目录：153 文件，3,397,116,589 字节，约 3.164 GiB。
- 另外清理过期独立引擎库提取副本；根目录淘汰含已知黑图问题的 0.14.24 APK，
  仅保留最新 0.14.25 与上一稳定回退 0.14.20。
- 完整 RAW burst、唯一 DNG、标定与参数、唯一输出、源码参考及当前黑图证据均保留。
  重复输出的旧路径可能不再存在，但保留同内容文件，删除清单记录了对应保留路径。
- 清理后可用空间约 14 GiB，清理前约 9.4 GiB；期间还运行构建，差额不等于精确删除量。

本地未纳入 Git 的证据在 test_temp/black-20261117，包括旧/新库回归日志、纹理跟踪、
五张实拍、UI XML、完整 App 日志及 cleanup-applied.json。照片、RAW 与二进制不上传源码仓库。
