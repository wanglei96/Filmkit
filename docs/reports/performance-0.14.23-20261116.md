# 0.14.23：JPEG/R RGBA 直编与完整输出对照

## 结论

2026-11-16，构建、签名并覆盖安装 **0.14.23 / 820**。本轮只改 JPEG/R 编码的
输入排列和临时缓冲，不改 RAW、融合、去马赛克、PC3D、色彩、锐化或 gainmap 数学。
PC3D 设置仍为用户选择的“自动”，没有清除应用数据、删除照片、提交代码或写项目记忆。

- 864 个完整 JNI 编码对照通过，产出的整个 JPEG/R 逐字节相等，包含带行填充和失败回退。
- 真实照片**解码输入**的同进程 ABBA/BAAB：完整封装路径中位
  **315.067 -> 212.970ms**，四个区组均快约 100--114ms。
  这是带模拟 JNI/Bitmap 的原生基准，不是 App 保存耗时，也不是原始 RAW 回放。
- App 实拍旧版 3 张、新版 6 张：`photo.encode` 中位 **235 -> 193ms**，
  观察到减少 **42ms / 17.87%**。不是 App 交替测试，温度不同，不把 42ms 当作无混杂的因果估计。
- 新版快门到保存中位 **5.005s**，范围 **4.634--5.489s**，仅 3/6 小于 5s。
  **没有达到稳定 5s 内保存目标**，也没有证明预览停顿已解决。

## 修改边界

`core/engine/src/jni/UltraHdrEncoder.cpp` 原路径：

1. 无论 Bitmap 是否紧密排列，都分配并复制整幅 RGBA。
2. 再分配 RGB 缓冲、逐像素去掉 alpha，以 3 通道编码 base。
3. base / gainmap 的 turbo 临时输出再各复制到最终 vector。

现在：

1. 在锁 Bitmap 前校验并复制 gainmap，防止读 Java 数组失败时留下像素锁。
2. 紧密排列 Bitmap 保持锁定，以 `JCS_EXT_RGBA` 直接编码；仅有行填充时逐行压紧。
3. 只有 turbo 失败、进入原 stb RGB 回退时才分配和转换 RGB。仍按原政策将 stb 的低质量
   参数钳到 91，以保持 4:4:4；没有借本次修改调整回退政策。
4. base / gainmap 直接写入最终 vector，不再复制 turbo 输出。
5. 完成 base 编码后解锁，后续 gainmap 编码、HDR 封装和 Java 返回保持原流程。

沿用生产 `JpegEncode.cpp` 已有的 4 通道能力；普通 JPEG 的 JNI 路径已经采用这种输入。
没有更换编码库、DCT、量化表、采样因子、q100、gainmap q95、XMP/ISO/MPF 或 EXIF 政策。
补充了尺寸、stride 和 gainmap 大小溢出检查，以及 lock 成功但 pixels 为空时的配对解锁。

3072x4080 的紧密排列、turbo 成功路径，不再申请 50,135,040 字节 RGBA 和
37,601,280 字节 RGB 临时缓冲，合计约 **83.67MiB 临时容量**，另去掉两次压缩输出复制。
这不是实测 App RSS 或峰值内存下降值；本轮没有采集堆/RSS 峰值。

成像链未采用 ponytail 的简化方法。本轮的数学和质量参数没有删减。

## 完整输出与失败路径验证

新增 `tools/check-ultrahdr-input.cpp` 和可运行的 `tools/check-ultrahdr-input.sh`。
测试分别编译原版与候选的实际 `UltraHdrEncoder.cpp`，只重命名 JNI 导出入口，链接
App 使用的 ARM64 `libuhdr_core.a`、`libuhdr_image_io.a`、libjpeg-turbo 及实际
`JpegEncode.cpp`。Bitmap 与 JNI 数组调用采用受控模拟，编码和封装不采用替身。

864 个组合：两种 gainmap 数学元数据、紧密/每行额外 28 字节、8x8/63x47/128x96、
随机/渐变/常量 RGB、随机 alpha、quality -1/1/90/95/100/101，以及 turbo 正常、
base 失败、gainmap 失败、两者都失败。失败注入会留下脏的部分输出，验证旧回退能正确覆盖。

- 864 个完整 JPEG/R 逐字节一致；解锁时毒化输入，检测解锁后的非法像素读取。
- 对其中 48 个组合分别比较 SDR RGBA8888 与 HDR 线性 RGBA 半浮点解码，
  共 96 对解码结果逐字节一致，并回读三通道 boost、gamma、offset、HDR capacity、
  use_base_cg、base/gainmap 尺寸及 base 4:4:4。
- 校验 getInfo/lock 失败、空 pixels、非法格式/宽高/stride、短 gainmap、gainmap 溢出、
  Java 数组读异常、返回数组分配失败、数组写异常及局部引用清理。失败后仍可正常编码。
- 基准中的 12.53MP 完整输出也逐字节核对，每组 16 个计时输出均通过。

原生测试以 C++20、O3、DNDEBUG、`-Wall -Wextra -Werror` 编译。
测试用 stb 的 aggregate 初始化告警局部关闭；生产编码源码未因此关闭告警。
本轮没有运行 ASan/UBSan 或完整 App 单元测试，模拟 Bitmap 不是 Android 实际锁的证明；
实际 App 九张成片验证用于补足设备路径，不将模拟测试当作对 Android 的全输入证明。

复现（先按 HDR Release 构建生成静态库）：

```sh
bash tools/check-ultrahdr-input.sh
bash tools/check-ultrahdr-input.sh --benchmark
```

默认 baseline 从当前 HEAD 读取原编码源码。HEAD 已含候选时必须显式给原版：

```sh
ULTRAHDR_REFERENCE_SOURCE=/tmp/UltraHdrEncoder-01422.cpp \
  bash tools/check-ultrahdr-input.sh --benchmark
```

该脚本只推送和执行独立原生工具，不启动 App、不拍照、不改设置。

## 原生交错基准

Vivo V2509A，ARM64，未锁温/锁频；App 相机预览仍存在。参考与候选同进程，先各做
一次完整尺寸编码核对，再执行四个 ABBA/BAAB 区组，每版 8 个计时样本。
计时包含 JNI 包装中的输入复制、编码、封装和模拟 Java 数组分配/复制，不包含 GPU 渲染、
MediaStore 或 EXIF。模拟数组内存管理不同于 JVM，不能把其差值直接套到 App。

|输入|JPEG/R 字节|原版中位 ms|候选中位 ms|减少 ms|
|---|---:|---:|---:|---:|
|确定性合成图及随机 gainmap|27,505,704|514.981|396.9345|118.0465|
|本轮真实照片的解码 RGBA/gainmap|9,310,237|315.067|212.970|102.097|

真实照片输入来自 `FILMKIT_20261116-203728.jpg`，Pillow 解码成 3072x4080 RGBA 和
1536x2040 灰度 gainmap，再由两版编码。**不是 GPU 原始未压缩输出**，因为从 JPEG
解码后重新编码，体积比原成片约 8.85MB 大；主要用于消除合成输入大小/纹理差异的疑问。

真实输入四个区组均值差（候选减原版）：
**-100.747、-100.168、-114.4945、-104.832ms**。
合成输入第二块原版有 993.310ms 离群值，保留在证据中；不据此声称常态收益达 359ms。
先前一轮未存文件的合成试运行只用于正确性检查，不混入本表。

证据：`test_temp/perf-stage-sync-20261116/jpeg23-native-{synthetic,real}.txt`、
`jpeg23-native-summary.json`、`jpeg23-real.rgba` / `.gain`；
设备可执行文件 `/data/local/tmp/filmkit-check-ultrahdr-input`。

## App 实拍与目标差距

用户本轮重新确认固定静止场景和允许自动采样后，拍摄旧版三张、新版六张，每张至少间隔 30s。
最初另拍三张 `jpeg23-A0`；因独立工具启动与最后一张边界不能严格排除重叠，整组不纳入对照，
重新采集 `jpeg23-A-clean`。额外照片保留，总共拍摄 12 张，不删除任何成片。

原计划旧—新—旧回测，但设备拒绝 `adb install -r -d`，报
`INSTALL_FAILED_VERSION_DOWNGRADE`。**没有卸载清数据绕过，也没有取得 App ABA 结果**。
降级失败后检查手机仍为 0.14.23/820。原生同输入 ABBA 是另一类证据，不冒充 App ABA。

|版本 / 快门时间|JPEG/R ms|引擎 ms|deliver 到保存 ms|编码结束到保存 ms|快门到保存 s|
|---|---:|---:|---:|---:|---:|
|22 / 20:37:23.408|237|4077.55|461|204|5.135|
|22 / 20:37:53.462|235|3752.78|369|106|4.562|
|22 / 20:38:23.561|216|4296.26|399|160|5.151|
|23 / 20:40:30.753|196|4532.86|457|215|5.489|
|23 / 20:41:00.798|187|4153.36|442|230|5.104|
|23 / 20:41:30.907|203|3830.69|364|147|4.634|
|23 / 20:42:01.163|211|3930.08|453|220|4.930|
|23 / 20:42:31.251|190|4149.56|381|165|4.991|
|23 / 20:43:01.352|190|4127.47|354|135|5.019|

编码中位下降 42ms；但 deliver 到保存的中位 **399 -> 411.5ms**，编码后的尾段
**160 -> 190ms**，引擎中位 **4077.55 -> 4138.515ms**。因此不能只看编码就声称
整链同幅提升。快门到保存中位 **5.135 -> 5.005s**，0.130s 的差不全归因于这次修改。

两组实际四帧均为 ISO **260/260/3200/260**，曝光 **.1/.1/.01/.0354s**，
由 `mfnr.capture` 回读；`photo.saved` 的 ISO1298、1/50s 是展示参数，不混作 burst 参数。
PC3D 自动实际选 **11 / scale1**。与上一轮无 clipping 的场景不同，
本轮九张均接受高光分支，clippedPixels 275--399、regions 3--4，不能和前轮直接拼表。

HAL SKIN 旧版 **38.496 -> 39.174C**，新版 **39.644 -> 41.202C**；
CPU 分别 48.831 -> 53.262C、50.836 -> 51.765C。顺序和温度是混杂因素。
九个采样窗口未检出 error/fail 命名事件；两份完整日志各有同样的 11 行历史非法 JSON，
分析器跳过，不计为本轮新增错误。

新版处理期最低完整窗口 FPS 为 **25.78--27.94**，全流程重叠窗口最大帧间隔
**288--353ms**，histogram 读回等待最高约 **140.46ms**。不能声称没有掉帧。

## 实际成片质量检查

复制九张主对照成片作本地检查，不改手机原图。各文件：

- 两张 JPEG、有效 MPF association、base/gainmap 尺寸 3072x4080 / 1536x2040。
- base SOF 三分量均 1x1，q100 所有量化表元素为 1，gainmap 为灰度。
- XMP boost -2/+2 log2、Gamma=1；品牌/机型/拍摄日期、曝光/ISO、尺寸 EXIF 存在且正确。
- 使用生产 libultrahdr 对**写完 EXIF 后的实际文件**分别完成 SDR 与 HDR 半浮点解码，
  元数据、4:4:4 与尺寸检查全部通过。

实拍图片受传感器噪声和不同采集时刻影响，不要求旧新版不同照片的 JPEG 字节相同。
“逐字节一致”的结论仅针对同一输入的编码对照。

证据：`jpeg23-photo-checks.json`、`jpeg23-native-inspect.txt`、`jpeg23-photos/`；
测试脚本 `test_temp/perf-stage-sync-20261116/check-jpeg23-photos.py`。
采样日志、thermals、shots、统计及驱动均在该目录，前缀 `jpeg23-A-clean` / `jpeg23-B`，
汇总 `jpeg23-summary.json`；`summarize-jpeg23.py` 调用现有预览分析器，不把保存后的窗口算入处理期。

## 构建与包检查

HDR Release 离线构建成功，ARM64/x86_64 均编译；APK 按既有过滤仅含 ARM64。
debug 密钥签名，v2/v3 验证通过、v4 关闭。aapt 回读 820/0.14.23，DEX 查到 0.14.23，
APK 内 ARM64 SO 与构建 SO 逐字节一致。四个关键 JNI 符号全部为导出定义：
`nativeEngineSubmitRawBurst`、`nativeEncodeUltraHdr`、`nativeHdrAvailable`、`nativeReadGainmap`。

- SO SHA-256：`9769953f2015eabfd8fd2e028650f7eb5a2f4286c1589a9b559c1ae62adf1d2b`
- APK SHA-256：`17fc1de65e6ecb63340703fdc1aee052d07c7431950d4c4dfaa9dff758955a80`

`check-capture-diagnostics.py`、`check-performance-analysis.py`、`check-diag-threading.py`、
shell 语法和 `git diff --check` 通过。高光源码与 22 版快照 `cmp` 相等，未重新启用被否决的
mask cache。没有启用阶段去等待或关闭诊断统计。

项目根只保留 **0.14.23 + 稳定回退 0.14.20**；旧 22 安装件仅留在忽略的测试证据目录。
采样已结束并告知用户可移动手机，不再自动拍照。

## 下一轮优先项

本场景高光准入中位约 **845.68ms**，其中 guide **373.68ms**、mask **216.55ms**，
比继续盯着 JPEG 的几十毫秒更值得查；同时编码后写盘/EXIF 仍有 **135--230ms**。
优先核对重复遍历、缓冲生命周期和可正确重叠的工作，不降低采样或改变高光接受政策。
本轮没有实现这些候选，不预估可节省多少，更不将几个阶段的中位直接相加为整链收益。
