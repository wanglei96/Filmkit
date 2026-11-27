# FilmKit 0.14.57：成片工作集优化交付

2026-11-27 · vivo V2509A / Mali-G1-Ultra MC12 · **0.14.57（854）已签名并覆盖安装**。保留 0.14.56 回退包，未发布到远端。没有代按快门或调整取景线程。

## 交付内容

- RAW 协方差由两张 RGBA32F 改为 RGBA32F＋RG32F，六个 FP32 分量全部保留。生产、滤波、PC3D 和色度消费同步使用新布局。
- 全尺寸 DIR 使用 FP64 抑制平面，保留完整两次显影、滤波、边界和原来的运算顺序。保留有颗粒时所需的独立输出缓冲。
- 光谱 RAW 只预热当时会走到的可选目标；拍摄时设置若已改变，原消费者仍按需完整分配。非光谱预热不变。
- Vulkan 设置和现版 Hanatos 保留；没有启用 AHardwareBuffer 共享或连续 Vulkan 扫描。

## 回放与实拍口径

同一个前台测试包、固定室内四帧、HDR 开、关颗粒、8 位、源码默认滤镜开关、现版 Vulkan Hanatos 开。每臂均为新进程预热后的首张；两批反序交错，每配方四对。测量从提交 RAW 至 JPEG/R 返回，不含相机采集、真实取景、EXIF 或 MediaStore。正值为更快：

| 配方 | RAW→JPEG/R 各臂中位数 | 四对配对节省 | 配对中位数 | 预热后进程峰值下降 |
|---|---:|---:|---:|---:|
| Gold 200 / Endura | 3861 → 3037 ms | 831、455、861、793 ms | **812 ms** | 604 MiB |
| Provia 100F | 3072 → 2962 ms | 130、384、−446、490 ms | **257 ms** | 606 MiB |

Gold 四对均改善，Provia 不宣称稳定提速或慢帧改善。VmHWM 是进程指标，并非独立 GPU 内存。回放各配方内 RGBA、HDR 增益图、JPEG/R 哈希全部一致。用发布包中的 ARM64 库补测指定人像、绿植、建筑、鲜花四张 DNG，两配方各 8/10 位，共 16 组；相对已存 0.14.56 基线，48 项输出哈希一致。JVM：852 项，850 通过、2 跳过、无失败。

## 0.14.57 实拍验收（11 月 27 日 09:13，UTC+08）

用户按快门，两张均为 3072×4080、四帧 MFNR、关颗粒、8 位 HDR JPEG/R，Vulkan Hanatos 全量处理 12,533,760 像素。配方以 `photo.saved` 文件名确认；快门到保存取同张 `capture.begin` → `photo.saved` 的日志时间差。单位 ms。

| 指标 | Gold 200 / Portra Endura | Provia 100F 直扫 |
|---|---:|---:|
| 快门到保存 | **5507** | **5748** |
| 快门到入保存队列 | 5036 | 4737 |
| 入保存队列到保存 | 471 | 1011 |
| RAW 引擎 | 4468 | 4165 |
| JPEG/R 编码（不含 EXIF／MediaStore） | 88 | 116 |
| MediaStore 发布 | 265 | 715 |
| 0.14.56 历史实拍快门到保存（仅作参考） | 6818 | 6326 |

两张较历史样本分别短 1311／578 ms。并非同 RAW、同热态的受控实拍对照，不将该差值归给版本改动或 Vulkan；版本净收益以同 RAW 交错回放为准。Provia 比 Gold 更早入保存队列，但该次 MediaStore 发布耗时更长，最终晚保存。用户反馈取景比 0.14.56 好；日志仍记录到采集期约 403／338 ms 的取景帧间隔，不记作无卡顿。

实拍日志摘录：`test_temp/spektrafilm-workset-delivery-20261127/01457-live-20261127.log`，SHA-256 `cea0a45c013226dc31fc4e21ceaaef858a667c75220b498101da6b5daa56d2c2`。

## Vulkan 整段试跑

让已有完整 GLES RAW 路径由系统 ANGLE 转到 Vulkan，与同样使用 AHardwareBuffer 交接的 Mali 原生 GLES 路径做初筛。Gold 首张单次 RAW→JPEG/R 为 2.71→3.37 秒，输出哈希也不同；该路线不交付。这不是手写原生 Vulkan 后端，也不能外推其性能。测试包已卸载，ANGLE 设置恢复。

## 留存

回放日志、输入与输出 SHA-256、发布验签、版本、JNI 和手机安装哈希、清理记录位于 `test_temp/spektrafilm-workset-delivery-20261127/`；ANGLE 试跑位于 `test_temp/spektrafilm-raw-vulkan-backend-20261127/`。四张原始 DNG 保留且复验 SHA-256；两轮回放目录合计约 14 MiB。
