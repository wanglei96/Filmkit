# Spektrafilm：从快门到保存的关键路径

2026-11-24，vivo V2509A，0.14.50（847）。目标是缩短实拍从 `capture.begin` 到 `photo.saved` 的时间，同时不增加移动取景停顿，不改变 RAW、光谱、HDR 或交付质量。局部墙钟用于定位，不作版本收益。

## 从实拍还原整条链

同一天的 Gold 200 / Endura 与 Provia 100F 直扫，各一张、四帧 RAW、3072×4080、Q100 4:4:4 JPEG/R、颗粒和 PC3D 关闭。两张不是同 RAW，不拿差值归因。以下四段按墙钟拼接，不重复计时。

| 墙钟段 | Gold 印相 | Provia 直扫 | 边界 |
| --- | ---: | ---: | --- |
| 快门到 RAW 引擎入口 | 0.615 s | 0.410 s | `raw.render` 时间戳减 `totalMs` 估算入口；含采集及入口前的交接 |
| RAW 前链 | 2.267 s | 2.065 s | `totalMs - branchMs`；含 MFNR、去马赛克及交接前杂项，不是 GPU 净算时 |
| Spektrafilm 分支 | 3.615 s | 2.621 s | `branchMs`，含同步读回、FP64 光谱、打包、HDR；首条读回可等尚未完成的前链 GPU 工作 |
| RAW 引擎结束到保存 | 0.945 s | 1.178 s | `raw.render` 到 `photo.saved`；含输出缓冲交接、编码、写入、媒体库发布 |
| 合计 | **7.442 s** | **6.274 s** | 快门到 `photo.saved` |

尾段已计时：JPEG/R 编码 146 / 140 ms，媒体库插入 81 / 83 ms、文件写入 33 / 32 ms、发布 377 / 745 ms。不能把 `photo.encode` 到 `photo.saved` 全归给 JPEG，也不能跳过发布或提前暴露未写完照片。两张 `render.still_done.queuedMs` 均为零，不代表连续拍摄无排队。

源码边界：`render.burst_done` 在 JNI `rgbaToJavaArray` **之前**；`saveJpeg` 又执行 `Bitmap.copyPixelsFromBuffer`。3072×4080 的 RGBA 为 50,135,040 B。这是可以测量的全帧交接，不把两条日志之间的全部时间直接归为复制。MFNR 的 `uploadMs` 到 burst 融合提交前为止，也包含对齐、mismatch 等工作，不是纯上传。`frontSubmitMs` 含前链的同步和 CPU 工作，但不保证计到最后一条 GPU 指令完成。

## 这轮同 RAW 核对

| 细分耗时中位数 | Gold 印相 | Provia 直扫 |
| --- | ---: | ---: |
| GPU→CPU 整次读回与转换 | 141.4 ms | 118.8 ms |
| `glReadPixels` 调用墙钟（含同步等候） | 78.9 ms | 72.1 ms |
| CPU 读回格式转换 | 56.9 ms | 44.1 ms |
| 扫描输出：CAM16 / 滤波 / sRGB OETF | 532.7 / 27.6 / 49.2 ms | 531.4 / 27.5 / 48.7 ms |
| 扫描完成后 RGBA 打包 | 78.7 ms | 83.4 ms |

表中为含高光的同一 4 帧 RAW、PC3D 关、合成取景并发，诊断库每路线 3 次，和 APK 原库交错。加的是计时，不是提速：新旧每路线的 6 个样本 RGBA、增益图和 JPEG 逐字节一致。ARM64 和 x86_64 Release native 构建通过；原版两组扫描输出 fixture 最大误差 `1.96e-14`；六次细分计时通过加和检查。实拍 `photo.encode` 是 JPEG/R，不等同回放中的 sRGB OETF。

DIR 初显影到抑制矩阵的像素块 16384→2048 做了独立 A/B。两份 RAW、PC3D 开、每路新旧各 3 次／每份，RGBA、增益图和 JPEG 逐字节一致。无高光 Gold 的 DIR 中位数 758→570 ms，但整链 3.736→3.797 s；有高光 Gold 的 DIR 347→359 ms。Provia 的 DIR 在无高光组不变；有高光组中位数较快，但候选有一次 9.415 s 的引擎长尾，同次合成取景间隔 606 ms，原因未归属。未测得稳定的端到端收益，已撤回，不作为发布候选。

CPU 光谱全段 8→6 worker 的试验按含高光、PC3D 关启动。5 次完成的回放中，4 次结束时设备 Thermal Status 已从 0 升到 1；Provia 未完成交错对照。已完成的 4 次 Gold 样本输出逐字节一致，但不据不一致的热状态判断性能。停止继续加热设备；保持原生产配置 8 worker。

## CPU 与 GPU 的比较口径

历史测试比较过 CPU AMaZE 与 GPU RCD 两条**不同算法**的路径。历史 12.5 MP 单帧 AMaZE 共约 1.06 s，包括读回 116 ms、CPU 算法 458 ms、转换/上传 463 ms；GPU RCD 另一次历史测量的 P1 方向场是 65–85 ms 墙钟（GPU 48–66 ms）。二者非同版、非同算法且不是同一范围，不能推导 CPU 与 GPU 完整 RAW 前链的速度比。现有 MFNR 也并非全程 GPU：对齐有 CPU 计算与读回，融合和去马赛克在 GPU。Spektrafilm 的光谱前段没有完成全算法 GPU 对照；PBO 实验只改变搬运，不能回答计算后端孰快。

## 下一组实验：先改等待关系，再选内核

1. **两个共享边界优先。** 把四帧 `RAW ready` / `copy` / `upload complete` 和对齐、融合、still GPU 到 CPU 首次读回的等待分清；检查能否让已到的帧提前完成不依赖后帧的准备。再核对 native RGBA → JNI → Bitmap → JPEG/R 现有的实际复制和等待，单独测 MediaStore 发布尾段。不先假设传输时间都可省或可重叠。
2. **CPU 以完整 FP64 光谱段为调度单元。** 以相同算法和参数 A/B 整段 worker 数，观察引擎中位数/尾延迟、取景窗口和内存；GPU/CPU 来回搬运、争用和同步都计入端到端。只有全链定位表明仍值得做时，再回到 CAM16、曝光或 DIR 做内核优化。同 RAW 里 CAM16 是扫描输出的主项；不把 27–28 ms 的扫描滤波作为优先目标。
3. **回到整条链验收。** 用同 RAW 交错回放作因果检查，另做真实移动手机两条路线，分别看快门到保存与取景，不用合成预览替代实拍。交付须继续检验完整配方、固定随机流、SDR/HDR 和色深；不删帧、减采样、降精度或换格式来换速度。

证据：`test_temp/spektrafilm-01450/device-20261124.log`、同目录 `*-cpu-*-20261124/`、`test_temp/spektrafilm-cpu-20261124/scan-breakdown.json`。本轮不发包、不安装，手机保持 0.14.50。

## 续测：RAW 齐帧与 JPEG/R 边界

同一批实拍时间戳（不是两路线互为 A/B）：

| 边界／墙钟 | Gold | Provia |
| --- | ---: | ---: |
| 首个 RAW 回调结束 → slot 3 复制完成（按顺序推定首个为 slot 0） | 172 ms | 178 ms |
| slot 1 复制完成 → slot 3 复制完成 | 60 ms | 70 ms |
| slot 3 复制完成 → native RAW 入口 `mfnr.stage:upload` | 45 ms | 27 ms |
| 齐帧后 slot 0 复制本身 | 14.63 ms | 8.10 ms |
| native RAW 入口 → `mfnr.stage:pyr` | 76 ms | 46 ms |
| 第三次 `align_frame` → `mismatch`，含高光准入 | 528 ms | 512 ms |
| `render.burst_done` → `render.still_done`，含 JNI 回传和日志 | 32 ms | 41 ms |

slot 1–3 已在到达时分别拷贝并释放 HAL Image，不是齐帧后才搬四张。参考 Image 留到齐帧后取校准、按需写 DNG，然后复制并释放。完整融合还要依赖四帧的实测 EV/ISO、角色和参考标定；高光 guide 至少要实际短帧及参考帧，不能用请求值先算。172/178 ms 是回调之间的理论窗口，不保证参考帧对应 `TotalCaptureResult` 同时已到、CPU 有余量，或能在这个窗口做完 guide。若试提前暖资源/搬运，应识别每个 burst 的所有权、持有原始缓冲供重试，并在独立 still GL 线程提交，不能为了省等待把工作塞回取景线程。表里的 46–76 ms 含资源准备、上传和条件参考帧 CPU 副本，不是提前上传可追回的收益。

### RGBA 交接：去掉 50 MB 临时数组只省约 9 ms

同一台手机、同一组四帧 RAW，分别导出两条配方的 native RGBA 和 HDR 增益图，每个配方的两个交接路径共用同一份输出。Android `app_process` 中对比 `NewByteArray + SetByteArrayRegion → Bitmap.copyPixelsFromBuffer` 与 `AndroidBitmap_lockPixels → 按 stride 复制`；两组各路径 12 次，ABBA/BAAB 交错，两个配方试验始末 Thermal Status 均为 0。Bitmap 构造和复制单列；首次触碰页仍可能在复制列计入。

| 3072×4080 | 旧路径 JNI 数组 | Bitmap 构造（旧／候选） | Bitmap 复制（旧／候选） | 总耗时（旧 → 候选） |
| --- | ---: | ---: | ---: | ---: |
| Gold | 11.30 ms | 0.34 / 0.30 ms | 9.46 / 11.37 ms | **20.90 → 11.72 ms** |
| Provia | 11.23 ms | 0.30 / 0.35 ms | 9.75 / 11.54 ms | **21.26 → 11.99 ms** |

每块比较的时间方向均一致（Gold 节省 7.45–12.31 ms；Provia 7.96–14.61 ms）。锁定像素与 RAW 回放 RGBA 逐行比较；原尺寸和居中各裁 127 px 后，现有 Q100 4:4:4 JPEG 与 JPEG/R 均逐字节一致。这个隔离测试不含 app 内连续拍摄的 Java GC、EXIF 写入或 MediaStore；不能把历史日志里的 32/41 ms 全算作本次测到的复制。该方案少一次全帧分配与复制，但单张延迟收益只有约 9 ms，暂无连续拍摄 GC/内存证据，不为此重写生产 JPEG/HDR 回退链。保持原始交付。

### RAW 前链：小块 SIMD 未过整链门槛

高光双向对齐使用 8 个 Bayer quad 的网格；原 NEON SAD 批宽 16 个采样位置，小块的部分搜索实际落在标量。试验补了 8-lane 尾部，保留原本整数累加、采样相位、边界和 tie-break。ARM64 真机通过 12,000 组随机、1,134 组全宽、9,000 组小块边缘和 72 组完整对齐对照。同 RAW 回放两配方、含／无高光共 24 次，所有对照的 RGBA、gainmap、JPEG 逐字节一致。

含高光组均 Thermal Status 0→0。高光双向对齐中位数 Gold 78.7→79.7 ms，Provia 81.5→76.2 ms；引擎整段 Gold 3.536→3.695 s，Provia 2.793→2.738 s。两条路线的收益没有复现，无高光组另有温升样本。撤回候选，不把任何整段波动归因于 SIMD。对照源码补丁、真机断言和运行记录保留。

### RAW 前链：尝试重叠 CPU 高光准备与 GPU mismatch

从第二个共享边界回到整链，改的是先后依赖而不是算法：`passMfnrMismatch` 只读取原始 RAW、已经上传的时域位移场及噪声/曝光参数，不读取高光准入结果。把 GPU residual 提交提前到最后一帧时域对齐之后、等待 CPU guide 之前；残差的读回、融合和输出仍等高光准入完成，失败仍拒绝该次引擎输出。候选未改帧数、网格、掩模、融合公式或光谱精度。

首轮 ABBA（回放默认 `stageSync=1`，每配方每版 2 次，含高光组均 Thermal Status 0→0）：

| 同 RAW，毫秒中位数 | Gold 旧 → 候选 | Provia 旧 → 候选 |
| --- | ---: | ---: |
| CPU guide join 等待 | 64.53 → 0.01 | 70.95 → 0.01 |
| 最后一次时域对齐结束 → 融合开始 | 322 → 222 | 319.5 → 234 |
| RAW 引擎总墙钟 | 3488 → 3226 | 3218 → 2873 |

前两行证明重叠可行；不能把第三行全部归给调度，其中 Spektrafilm 分支中位数也浮动了约 147/296 ms。无高光原本等待已近零，无净收益，不应无条件改时序。首轮两份 RAW 的 16 次回放均通过 RGBA、增益图、JPEG 和诊断统计逐字节比较。

复测只在独立 still context 且 CPU guide 尚未完成时提前提交，关闭回放专用同步（`--no-stage-sync`），改用 BAAB/ABBA。完成含高光 Gold 7 次后因 Thermal Status 升到 1 暂停；本组 Provia、无高光及关闭时域 L1 的计划未执行。Gold 的 7 次（含 2 次 0→1）输出仍逐字节一致。仅看 5 次始末状态均为 0 的样本，旧 2 次/候选 3 次的 guide 等待中位数约 51→0.01 ms，但同一窗口对齐结束→融合开始只约 248→230 ms。引擎总墙钟与合成取景间隔没有稳定改善，部分候选有更大长尾；不能解释成 GPU 纯算力变慢或真实取景变差。暂不合入，保留候选补丁与回放记录。ARM64/x86_64 Release 构建通过，JVM 测试 851 项（2 跳过，0 失败），高光几何 301 项、高光恢复 4715 项通过。

证据目录：`test_temp/spektrafilm-handoff-20261124/`（复现脚本、报告中位数、补丁、构建与断言日志）、`test_temp/spektrafilm-01450/*-handoff-*-20261124/`（同 RAW 对照原始日志、热状态和逐字节检查）。实验改动已回退；保留既有计时插桩，手机仍是 **0.14.50（847）**，未出包或安装。

下一轮优先把高光 `guide` 的可前移部分与等待实测短帧的部分拆开：参考帧的完整 RGB guide 和裁切掩模只依赖本次参考 RAW/CFA/WB/噪声，短帧归一和双向几何必须等真实短帧。先测是否能用参考帧实际到达的窗口隐藏足够墙钟、且不拖慢取景或延长 HAL 占用；若窗口不足，回到两路共用的 FP64 曝光和 CAM16。GPU 对照以同输入、同算法的明确边界为单位，纳入提交、同步、读回、下一段等待和取景长尾，不把不同去马赛克算法的用时换算成 CPU/GPU 倍率。
