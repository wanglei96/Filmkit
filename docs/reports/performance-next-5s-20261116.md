# 下一轮性能：5 秒目标与非 PC3D 候选复核

## 当前目标与结论

用户在 2026-11-16 将目标放宽为 **快门到保存 5 秒内**。质量优先，
完整成像链、当前帧统计、帧数、核、采样、精度和高光准入政策均不因此放宽。
不只看引擎 `totalMs`，也不把 5.1s 四舍五入成达标。

0.14.22 上次三张实拍为 **5.419 / 5.498 / 5.141s**，三张都超过新目标。
本轮不自动拍照，不改用户偏好，不安装新包，手机仍为 0.14.22 / 819。
本轮继续避开 PC3D，做了两个独立 CPU 对照及一个真实 RAW 全链诊断开关对照。

|方向|测量结果|处理|
|---|---|---|
|高光 clipping footprint 与 tile flow 缓存|无 clipping 中位约省 16.88ms；稀疏 clipping 基本持平；全 clipping 反而慢 14.55ms|撤回全部生产改动，不采纳|
|完整阶段诊断开关|真实 RAW 全链后两组关闭约省 82.42 / 103.46ms；输出相同|仅确认诊断成本，默认与用户设置未改|
|JPEG/R 的 RGBA 直编|独立入口中位 151.832 -> 118.1245ms，四区组均快；JPEG 字节相同|可用候选，尚未接入 JNI / App，不声称已交付收益|

**没有证明达成 5 秒**。不把不同输入、不同环境的独立收益直接相加，
也不把 cold 首样、CPU 单组件耗时或中间阶段耗时等同快门到保存。

## 1. 高光 mask 精确缓存：拒绝采纳

先读完本项目 guide/assessment 的生产路径，以及本地 PC 参考的
`bentoGenerateHighlightMask`、`bentoAdjustHighlightMask`、
`alignedRawClippingMask`、`convertAlignment`。不更改 RGB 误差、
3x3 footprint、逐 phase integer threshold、roundEven、binary16 位移或整帧准入。

候选只做两项精确缓存：

- 先缓存每个 RAW quad 的四相位 clipping，再做可分离 3x3 OR；
  保留坐标 -1 / extent 的 halo，不能先把锚点 clamp 到图内再膨胀。
- 同一 tile 的归一化位移和 binary16 round-trip 只计算一次，逐像素继续按原 tile 读取。

新增 `tools/check-highlight-mask-cache.cpp`，与 22 版原 `HighlightRecovery.cpp`
重命名后的参考函数链接，精确比较 guide 三域、variance、clipping/highlight、
adjusted/inpainting/short-clipping、完整 RGB error 和所有准入摘要。
Host ASan/UBSan 通过 240 组对照，原 4635 项高光断言也通过。
Android O3 同样通过 240 组；无 clipping 和全 clipping 的 4096x3072 全尺寸
输出逐字节相同。含四 CFA、奇偶尺寸、padding、图外 flow、半像素位移、
非有限/半精度溢出失败清空和 null-flow 诊断路径。

4096x3072、相同参考/候选同进程、四个交替 ABBA/BAAB 区组，每版八次。
三种密度分开生成相同输入，不与上次 App 实拍拼接。

|clipping 输入|参考 guide+mask 中位 ms|候选中位 ms|候选减参考 ms|
|---|---:|---:|---:|
|无 clipping|328.1965|311.3185|-16.8780|
|稀疏随机 clipping|340.9105|339.9195|-0.9910|
|全 clipping|249.1995|263.7495|+14.5500|

无 clipping 四个配对区组均值差：-27.6595、-10.6910、-18.7135、-14.9980ms。
稀疏：-3.4705、-8.0870、+1.0230、-17.5930ms，不构成稳定的大收益。
全 clipping：+7.1255、+11.9555、+16.2490、+20.0415ms，四组均退化。
预计算会失去原始 clipping 检测的早退出优势，并增加临时内存/线程调度成本；
本轮未进一步量化这些因素各自贡献。

因此候选 **全部撤回**，`HighlightRecovery.cpp` 与实验前副本 `cmp` 一致，
没有把某一场景的几十毫秒收益换成另一分支退化。候选源留在隔离实验目录，
不参与生产构建；生产算法未新增缓存或分支。

证据目录 `test_temp/perf-mask-cache-20261116/`：
`HighlightRecovery-footprint-candidate.cpp`、`mask-benchmark-release.txt`、
`mask-benchmark-no-clips.txt`、`mask-benchmark-full-clips.txt`。
测试链接参考时，将五个 public API 名分别以 `-Dname=nameReference` 重命名；
生产参考源码为实验前 `/tmp/HighlightRecovery-01422.cpp`。
ARM64 编译用 NDK 29、C++20、O3、DNDEBUG，与 Release 优化级别一致。

## 2. 真实 RAW 的完整阶段诊断成本

使用 **同一个 22 版包内 SO** 和既有 `check-renderer-stage-sync`，仅切换工具的
`--diag-stats`。本轮始终 `stageTimingSync=true`，未复测/启用上轮不确定的
去计时等待，也未使用完整纹理 warmup，以免把不同实验变量混入。

输入为已存档的 4096x3072 真实 no-short 四帧 RAW 与原 replay 标定。
每次是新 CLI 进程，不是 App 常驻资源；三个区组交替 ABBA/BAAB，
每条件六次，间隔 2 秒。设备执行用 80s timeout、5s 后强制终止兜底，
本轮所有 12 次均正常完成，没有触发超时。

|区组|诊断 on 两次 totalMs|诊断 off 两次 totalMs|off 减 on 区组均值 ms|
|---|---|---|---:|
|0 / ABBA|2695.44、2134.64|2063.91、2068.98|-348.595|
|1 / BAAB|2096.56、2170.39|2039.50、2062.61|-82.420|
|2 / ABBA|2151.15、2119.89|2053.43、2010.69|-103.460|

全部中位 2142.895 -> 2058.020ms，差 -84.875ms。
明确把第一组作为 cold/首样敏感组单列，不用第一次 on 与第一次 off 的
631.53ms 差宣称稳态提速。后两组各四样本中位为
2135.520 -> 2046.465ms，差 **-89.055ms / -4.17%**。
后两组是补充敏感性分析，不删除第一组原始数据，不把八次回放冒称八张新照片。

12 次完整 RGBA/JPEG 的 SHA256 均为同一对：

```text
RGBA ea53c2d422242f4ac1bc5fcb21db9e01750963ccd316f6bf5759a530c39520ed
JPEG 8bb87598ba206f4ec6ab715dde1302462c4528590eecbea27c5c4a75f4aceb4a
```

GL0、source-white 断言、kernel11、同步模式和诊断事件开关断言均通过。
起止 thermal status0，HAL 实时 SKIN 34.340 -> 35.244C；没有锁频、锁温。
本组不是实时预览、显示域闭环、编码写盘俱全的 App 实拍性能结论。

开关所有者检查：`diagOn()` 只进入诊断 frame scope、`diagStatsTex` 和
`diagStatsU8`；当前帧曝光/中灰闭环/天空护栏不由该开关控制。
目前默认仍开启，用户 prefs 未改。可选后续方案是暴露工程诊断开关，
或优化诊断的同步执行；本轮不替用户决定牺牲排障信息，也不删功能统计。

证据：上述隔离目录下 `check-diag-cost.py`、`diag-results.json`、
`diag-block*.txt`、`diag-thermal-start.txt`、`diag-thermal-end.txt`。
SO SHA256 与 22 报告一致：
`7ac91bd6b8216f95c963672784d61dbd6e56817a6085584fa7ec11ed2a688f4f`。

## 3. JPEG/R 重复输入拷贝：入口候选

当前 `UltraHdrEncoder.cpp` 的 base 路径：Bitmap 锁定后复制整张 RGBA，解锁，
再逐像素转为 RGB，然后交给 `encodeJpegTurbo444(..., channels=3)`。
同一个生产编码器已支持 `channels=4 / JCS_EXT_RGBA`，普通 JPEG 入口也已使用。
因此可以考虑在保持 Bitmap 锁定/有效生命周期的前提下直编 RGBA，
仅非 tight stride 或 fallback 时分配必要缓冲。

本轮 **没有改 JNI 消费链**，只新增独立工具 `tools/check-jpeg-input.cpp`，
直接链接生产 `JpegEncode.cpp` 和 App 构建产出的相同 ARM64 libjpeg-turbo 静态库。
比较当前“整帧复制 + RGB 转换 + 编码”与“RGBA 直接编码”。
q1/90/95/100、小尺寸/奇数/非整 MCU、随机/渐变/恒定 RGB、随机 alpha，共
60 组 JPEG 字节完全相同；3072x4080/q100 全尺寸预跑及正式 16 次也完全相同。
未减少色度采样或 JPEG quality，未替换 DCT、量化表、gainmap 或封装政策。

|指标|参考复制+RGB|RGBA 直编|
|---|---:|---:|
|四个 ABBA/BAAB 区组、各八样本中位 ms|151.8320|118.1245|
|中位差|-| -33.7075ms / -22.20%|

四区组配对均值差：**-34.4740、-34.2625、-33.1575、-43.6275ms**。
合成输入的 JPEG 大小 24,350,118 字节，比当前 App 照片约 8.5MB 更大，
不能将这个耗时直接换算成真实照片的固定收益。
工具不覆盖 Android Bitmap 锁生命周期、非 tight stride、stb fallback、
完整 JPEG/R/gainmap/EXIF 和相册写盘，接入前必须补齐这些验证。

潜在减少的是约 50.1MB RGBA 和 37.6MB RGB 两个全帧临时缓冲，
不是省掉必要的最终像素或降精度；具体 App 峰值内存和时间还未测。
证据：`test_temp/perf-mask-cache-20261116/jpeg-input-benchmark.txt`。

## 更大的后续目标

从 22 版已采三张日志直接拆出交付尾段，不假称是本轮新拍：

|快门时间|photo.deliver -> saved ms|JPEG/R encode ms|encode 结束 -> saved ms|
|---|---:|---:|---:|
|19:57:03.002|609|337|232|
|19:57:33.121|421|214|178|
|19:58:03.255|600|304|267|

尾段包含 Bitmap、编码、MediaStore 与补写 EXIF；目前没有单独的 EXIF/写盘细分计时，
不能把最后一列全部归因为 EXIF。RAW 采集阶段 `mfnr.capture` 为 403/393/373ms，
`raw.copy` 单次约 6--12ms，不应凭线程迁移就假设能省几百毫秒。
另注意 `photo.saved` 的 iso/sec 是展示侧参数；实际四帧参数应看
`mfnr.capture.iso/sec`，本组三次均为 ISO276/276/3200/276、0.1/0.1/0.01/0.0354s。

下一轮顺序：

1. 给 JPEG/R base、gainmap、封装、Bitmap 拷贝、MediaStore 和 EXIF 分段非同步计时，
   先确认尾段大头，再接入 RGBA 直编并完整验证。保留 q100/444、HDR 元数据与失败回退。
2. 诊断作为独立工程模式或优化实现，和功能统计严格分开；不默认改用户设置。
3. 当前帧中灰/预览 histogram 的同步仍需处理。必须保持同一输入、完整量化和消费链，
   不能拿上一帧统计代替，也不能把等待读回挪到别处就当作 GPU 工作消失。

到 5 秒尚缺稳定裕量；目前约 90ms 诊断成本和约 34ms 独立编码候选，
不足以证明弥补实拍最慢约 498ms 的超标。下次 App 采样前需重新确认手机固定，
并同时检查每张保存延迟与预览长尾，不能只挑中位数。

本轮只保留两个对照工具与本报告，生产成像行为仍为 22 版，未升版本、未出包、
未提交/推送、未写项目记忆；未运行完整 App 单测。
