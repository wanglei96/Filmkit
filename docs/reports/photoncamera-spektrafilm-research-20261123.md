# bjzhou/PhotonCamera Spektrafilm 调研

日期：2026-11-23。范围：代码调研，未移植算法、未出包、未连接或修改手机。

## 版本与证据范围

- PhotonCamera 默认分支 `main` 经 GitHub API、实际检出和 `git ls-remote` 交叉核对，HEAD 为 `a3f61fd0604cb1ff574d74be1586f4131268643f`，提交时间为 2026-11-20 19:48:38 +08:00。
- 本文研究的是用户指定的 `bjzhou/PhotonCamera`，包路径 `com.hinnka.mycamera`。不将其他同名项目的实现或历史测试混入结论。
- Photon 的 Spektrafilm manifest 固定上游 `3bb2c2d2801ff68b92019cf1dbcbb133d60832bc` / 0.3.4，与 FilmKit 当前参考版本相同。
- FilmKit 当前源码为 `ff85def1f50f6a8c6b608329c32d6cbb68ff56c3` / 0.14.38。历史验收见 `spektrafilm-mobile-spectral-probe-20261119.md`，本次没有新增真机性能或画质实测。
- 三个子代理分别核查成像执行、预览调度、直扫语义，主代理核对关键源码并整合。仓库检出位于 `/tmp/photon-spektrafilm-research-X2niWX/PhotonCamera`；长期证据以下列固定提交链接为准。

[Photon 固定提交](https://github.com/bjzhou/PhotonCamera/commit/a3f61fd0604cb1ff574d74be1586f4131268643f)；[Spektrafilm manifest](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/assets/spektrafilm/manifest.json#L1-L12)。

## 为什么计算能更快

### 成片使用分阶段 GPU 查表图

Photon 并非只在预览中使用 LUT。最终 RAW 保存链会请求 `buildSpatialGraph=true`，进入 `RawEngineTonePass` 的 Spektrafilm 分支，再编码 JPEG。

原生代码根据选中的物理模型生成多个阶段表：胶片曝光、显影曲线、相纸曝光、相纸显影、扫描，以及需要时单独的 gamut 表。三维表边长固定为 65，每表有 274,625 个格点。实际全尺寸图像在 GPU 上插值查表，空间阶段在表之间执行。无空间效果时另走较短的胶片/印相 LUT 链。

这减少了每张照片每个像素重新进行光谱积分、指数和完整扫描色域变换的工作。它不是单张外观 LUT，也不是所有像素都执行与 FilmKit 相同的全链 FP64 直接计算。表格点数量不能转换为真实加速倍率。

依据：[原生建图](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/cpp/spektrafilm/spectral_graph.cpp#L52-L119)；[最终 RAW 资源准备](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/raw/RawDemosaicProcessor.kt#L2623-L2633)；[成片分支](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/raw/RawEngineTonePass.kt#L75-L85)。

### 缓存与 GPU 资源复用

模型、上游版本、胶片、相纸及相关参数参与缓存键；复用当前 LUT、磁盘 LUT 和内存空间图。表键不变时不重新上传 GPU。程序、FBO 和工作纹理按用途复用。生成表的 OpenMP 并发限制为两线程。

这类缓存针对不可变模型与完整配置，不等于复用上一张照片的曝光、白平衡、亮度或整帧统计。FilmKit 可借鉴其缓存失效和资源生命周期组织，但仍需先测本项目重复初始化/分配所占时间，不能承诺大幅收益。

依据：[缓存键](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/raw/SpektrafilmRuntime.kt#L23-L72)；[缓存命中与构建](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/raw/SpektrafilmRuntime.kt#L119-L191)；[GPU 资源](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/raw/SpektrafilmGraphGl.kt#L7-L35)；[两线程建表](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/cpp/spektrafilm/spectral_graph.cpp#L15-L36)。

### 空间计算仍存在，但有近似与不同默认值

空间图保留晕光、散射、DIR、颗粒和扫描滤波等阶段，按带支撑边界的窗口处理，常规工作边长为 768。宽卷积核使用降采样与重建并补偿额外滤波方差；较宽柔光分量可通过 stride=8 整帧场共享，减少窗口内重复计算。不能把这种方式直接视为与当前滤波核逐值等价。

三维表上传为 `RGBA16F` 并使用线性采样；显影曲线为 `RGBA32F`，曝光工作场使用 FP32，密度/扫描等中间结果使用 FP16。无空间图且无扫描光学支撑需求时，部分配置还允许低分辨率影调后引导上采样；空间图启用时避开该路径。

默认配置也不同：Photon 默认关闭晕光/散射、颗粒、柔光及 glare；DIR 默认开启，但其空间扩散默认零。FilmKit 当前晕光、散射、DIR 设置不能被默默改成这些默认值来换速度。

依据：[窗口和工作纹理](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/raw/SpektrafilmGraphToneStage.kt#L55-L171)；[核规划](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/raw/SpektrafilmOpticsKernels.kt#L178-L191)；[卷积执行](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/raw/SpektrafilmOpticsPass.kt#L188-L298)；[表精度](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/raw/SpektrafilmGraphGl.kt#L152-L170)；[上采样门控](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/raw/RawDemosaicProcessor.kt#L1544-L1557)；[效果默认值](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/raw/SpectralFilmEffects.kt#L45-L146)；[DIR 默认值](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/raw/SpectralFilmTuning.kt#L17-L60)。

### 可见照片与全部保存不是同一计时终点

RAW 堆栈成功路径先编码并发布 SDR JPEG，标记照片可显示，再启动延迟 DNG 写入、可选 HDR gainmap/缓存及导出等工作。预览使用不带空间图的资源，不等于对最终完整效果的逐帧渲染。

本次没有找到与 FilmKit 四帧 RAW MFNR、相同效果、分辨率、曝光及 JPEG/R 全部保存结束可直接比较的受控计时。建表 `elapsed` 只代表准备阶段；atlas `submitMs` 只代表提交耗时。代码可以解释计算量和可见延迟差异，不能证明速度倍率。

依据：[SDR JPEG 发布到 HDR 后处理](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/gallery/GalleryManager.kt#L3998-L4061)；[预览资源请求](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/raw/RawEnginePreviewPlan.kt#L45-L54)；[atlas 计时](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/raw/SpektrafilmDiffusionAtlas.kt#L63-L69)。

## 为什么后台成片时预览仍响应

- 采集由 `CameraBackground` Handler 驱动；普通照片预览走独立 YUV Reader、`acquireLatestImage()`、原子 latest-frame 通道和 GLSurfaceView GL 线程。旧预览帧及时关闭，避免越积越慢；EIS/录像的 FIFO 路径不适用这一概括。
- 成片队列协调器为 `Main.immediate` 单消费者，并非所有准备/通知都离开主线程；重型 RAW 工作切至独立 `RawDemosaicProcessor-GL` executor。
- 成片 GL 线程设置 `THREAD_PRIORITY_BACKGROUND`；独立离屏 EGL context 支持时请求 LOW 优先级，失败可回退普通 context。堆栈也使用持久成片 context。
- 后台 GPU 使用 `glFlush()`/`Thread.yield()`，外层 tile 边界插 fence，按 1 ms 等待并在超时后休眠，完成 checkpoint 后另休眠 2 ms。它控制提交节奏，不能保证驱动立即抢占或每帧固定留下多少 GPU 时间。
- 采集序列完成即执行预览 AF/AE reset 和 repeating 更新，不等成片/保存结束。普通 still request 没有附加预览 target，所以不能推导 burst 采集期间也绝无预览间隙。
- 成片 Channel 为 UNLIMITED，但前端按未关闭图像、HAL 预留和本次帧数执行 50 个 image slot 上限。这个上限并非总内存字节预算。

依据：[最新帧通道](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/camera/PreviewYuvStream.kt#L6-L27)；[预览 GL 初始化](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/ui/camera/CameraGLSurfaceView.kt#L75-L100)；[队列](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/processor/CaptureProcessingQueue.kt#L19-L48)；[RAW 线程优先级](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/raw/RawDemosaicProcessor.kt#L316-L325)；[EGL context](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/raw/RawDemosaicProcessor.kt#L630-L647)；[主动让路](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/processor/GlesGpuScheduler.kt#L276-L338)；[采集结束恢复](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/camera/Camera2Controller.kt#L681-L713)；[still targets](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/camera/Camera2Controller.kt#L8516-L8524)；[槽位上限](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/camera/Camera2Controller.kt#L568-L595)。

FilmKit 已有 `FilmStill` 独立线程、预览 DISPLAY 回调优先级、预览 HIGH/成片 LOW EGL context。当前 Spektrafilm 成片却会把图像从 GPU 读回，转换为整帧 FP64 三通道缓冲，再以最多八个 CPU workers 执行物理模型，之后为增益图重新与 GPU 交互。因此需要衡量 CPU 核心、内存带宽和 GPU 提交的共同竞争，不能将差异简单归为缺少独立线程。

FilmKit 证据：`app/src/main/java/com/filmkit/app/camera/StillRenderClient.kt:110`，`RenderClient.kt:94`，`core/engine/src/jni/Bridge.cpp:454`，`core/engine/src/render/FrameRenderer.cpp:15408`，`core/engine/src/film/spektrafilm/ParallelPixels.h:12`。源码注释中的历史优先级测试不替代本轮受控复测。

代码支持的是设计上保护预览响应。没有同步 present/采集时间戳/线程调度/GPU trace，不能宣称零掉帧、固定 30 fps 或所有设备均不受影响。

## 直扫开关与标准语义

### 上游允许负片直扫，输出是负像

本次远端核对 Spektrafilm `main`/HEAD 仍为 `3bb2c2d2801ff68b92019cf1dbcbb133d60832bc`。其 README 明确写有通过 `scan_film` 查看负片的虚拟扫描。GUI 根据片型设置直扫的默认值，但参数映射仍接受独立 `scan_film`，运行管线仅据此决定是否跳过印相。

扫描阶段对包含片基的光谱密度计算透射光，不会自动把负片变成正像。负片直扫分支明确跳过黑白点参考校正；开启该校正选项也不能变成负片转正。

依据：[上游 README](https://github.com/andreavolpato/spektrafilm/blob/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc/README.md#L368-L373)；[片型默认值](https://github.com/andreavolpato/spektrafilm/blob/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc/src/spektrafilm_gui/state.py#L340-L343)；[独立开关映射](https://github.com/andreavolpato/spektrafilm/blob/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc/src/spektrafilm_gui/params_mapper.py#L56-L62)；[是否经过印相](https://github.com/andreavolpato/spektrafilm/blob/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc/src/spektrafilm/runtime/pipeline.py#L174-L188)；[片基扫描](https://github.com/andreavolpato/spektrafilm/blob/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc/src/spektrafilm/runtime/stages/scanning.py#L100-L120)；[负片直扫不作校正](https://github.com/andreavolpato/spektrafilm/blob/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc/src/spektrafilm/runtime/services/color_reference.py#L116-L124)。

### Photon 与上述语义一致

负片 UI 显示独立 `scanFilm` 开关，开启后隐藏相纸选择。加载时正片或负片直扫都令 paper 为 null，JNI 据此接收 directScan；原生显影后直接调用胶片 scanner。扫描透射光按 `10^-D` 积分，保留片基，未做负片反相。

尤其其 RAW 消费端明确说明：负片直扫随场景曝光增加而变暗，不能用递增高光参考延展，因此关闭该路径的 HDR reference。这既证明了负像语义，也提醒 FilmKit 不能只增加开关却继续套用当前正像 HDR 参考。

依据：[UI 独立开关](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/ui/components/RawEditPanel.kt#L506-L518)；[paper 选择](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/raw/SpectralFilmProfile.kt#L150-L164)；[JNI 路由](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/cpp/spektrafilm/spectral_jni.cpp#L135-L147)；[显影后直扫](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/cpp/spektrafilm/spectral_generator.cpp#L57-L67)；[光谱扫描](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/cpp/spektrafilm/spectral_printing.cpp#L183-L224)；[正片专用校正](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/cpp/spektrafilm/spectral_printing.cpp#L324-L330)；[负片 HDR 门控](https://github.com/bjzhou/PhotonCamera/blob/a3f61fd0604cb1ff574d74be1586f4131268643f/app/src/main/java/com/hinnka/mycamera/raw/RawDemosaicProcessor.kt#L2682-L2686)。

### 数值验证

子代理直接链接 Photon 原有 C++ 显影/扫描代码和仓库光谱资产运行，主代理复跑得到相同结果。输入为线性 ProPhoto RGB 中性灰，表中为扫描 XYZ 的 Y；本组关闭黑白点校正及空间/随机效果，用于判定极性，不是最终 GPU 查表成片画质验收。

| 输入灰阶 | Portra 400 胶片直扫 | Portra 400 / Endura 印相扫描 | Velvia 100 胶片直扫 |
| ---: | ---: | ---: | ---: |
| 0.010 | 0.280219 | 0.004990 | 0.000413 |
| 0.050 | 0.138917 | 0.021773 | 0.015505 |
| 0.184 | 0.079744 | 0.179327 | 0.183122 |
| 0.500 | 0.051842 | 0.489917 | 0.550132 |
| 1.000 | 0.037966 | 0.688339 | 0.705295 |

负片直扫严格递减，印相扫描和反转正片直扫严格递增。中性输入的负片直扫编码 RGB 各通道也不相等，例如灰阶 0.184 输出约 `(0.318791, 0.210324, 0.122858)`，与保留有色片基的负像扫描一致。

子代理另跑上游完整 Python 管线，禁用空间/随机效果及扫描/放大机 LUT，即使请求黑白点校正，Portra 直扫的 Y 仍为 `0.280288, 0.138988, 0.079805, 0.051897, 0.038013`，严格递减。两套实现的数值略有差异，本次只验证极性，不声称逐值一致。

本地证据：`test_temp/photoncamera-spektrafilm-research-20261123/direct_scan_probe.cpp`、`direct-scan-native.txt`、`upstream_scan_probe.py` 和 `direct-scan-upstream.json`。原始上游检出及依赖位于临时研究目录；复现须固定上述提交。主代理分别复跑并保留两组 stdout。

### 我们哪里有限制，哪种才是标准

FilmKit 的 catalog 在 `tools/spektrafilm-catalog.py:29` 写死 `direct = film['type'] == 'positive'`，没有生成负片直扫配方；设置中也没有独立扫描模式。`SpektrafilmAssets.kt:34` 的选择逻辑延续该绑定。底层 `Profile.cpp:138` 分别读取片型与 directScan，`Render.cpp:69` 仅据 directScan 跳过印相，并不存在负片无法物理扫描的原则性限制。

因此，“Spektrafilm 标准规定只有正片可以直扫”是错误理解。我们当前是较窄的功能范围，不能把这个限制当作上游要求。负片默认走印相则是有效的完整模型路径，不构成成像错误。

应区分三种成品语义：

| 路径 | 输出语义 | 对应做法 |
| --- | --- | --- |
| 反转正片直接扫描 | 正像 | FilmKit、Photon 和上游已用路径 |
| 负片直接透射扫描 | 带片基的负像 | Photon 与上游 scan_film；FilmKit 尚未开放 |
| 负片数字扫描后转正 | 正像，需负片反相、片基/颜色与影调处理 | 现实扫描软件的常规工作流；不能把上面负像路径冒称此功能 |

SilverFast 官方 NegaFix 文档明确区分负片到正像的转换、胶片特性选择和 film mask recognition，说明负片数字化当然不需要先印相才能得到正像。但这些步骤是另一套完整转换，Photon 此开关和 Spektrafilm scan_film 并未自动提供它。依据：[SilverFast NegaFix 官方说明](https://www.silverfast.com/show/negafixguide/en.html)。

如果后续要求对齐上游能力，应把片型和扫描/印相模式分离，并补齐配方、冻结配置、命名/UI、负片递减响应及 HDR 路由的验收。若目标是正常正像的负片数字扫描，应单独实现并验证正确的负片转换，不采用随意 `1-RGB` 来填补。

## 对 FilmKit 的借鉴顺序

1. 先分离计时终点：快门到可见 SDR、完整 HDR 保存、后台任务清空；同时记录预览实际 present 的 P95/P99 帧间隔和最长停顿。不能把更早显示缩略图算成完整成片算法提速。
2. 保持完整模型与当前精度，审计不可变配方准备、内存缓冲、纹理、程序与中间数据的重复创建/搬运。缓存键必须包含影响结果的全部参数，不复用前次拍摄统计。
3. 按实测比较分块提交及让路边界；降低 CPU 优先级可能改善响应也可能延长成片，两者一起测，不直接照抄 sleep 或再增加线程。
4. 若评估分阶段查表/GPU 路线，先在独立候选中对全部可选配方和参数域做原版误差对照。FP16、65 格插值、宽核降采样及引导上采样分别验证，不捆绑为一次不可归因的改动，也不以关闭效果达标。
5. 质量对照至少包含灰阶/曝光扫描、饱和色/负值/超白、暗部、色域边界、点光源及灯边、窗口接缝；颗粒使用固定随机回放或统计验收。最后同 RAW 交错性能测试，再由用户实拍 Gold 200/Endura 和 Provia 100F 直扫验收。

当前可以认定具有借鉴意义；尚未证明将其查表/混合精度路线移入 FilmKit 后满足现有画质要求，也未证明可节省多少秒。

## 灰阶探针复现

原始 cwd 为 FilmKit 根目录。以下 `research_root` 为本次检出路径，临时目录失效后须重新检出固定版本并复制归档探针。Python 探针依赖其同目录下的 `spektrafilm-upstream/src`，不是默认安装版本。使用已有 `test_temp/engine-reference-20261118/spektrafilm-env/bin/python`；本次记录 Python 3.13.2、NumPy 2.5.5、SciPy 1.18.1、colour-science 0.4.7。

```bash
research_root=/tmp/photon-spektrafilm-research-X2niWX
spectral_src="$research_root/PhotonCamera/app/src/main/cpp/spektrafilm"
clang++ -std=c++17 -O2 -I "$spectral_src" \
  "$research_root/direct_scan_probe.cpp" \
  "$spectral_src/spectral_data.cpp" \
  "$spectral_src/spectral_filming.cpp" \
  "$spectral_src/spectral_printing.cpp" \
  "$spectral_src/spectral_gamut.cpp" -lz \
  -o "$research_root/direct_scan_probe"
"$research_root/direct_scan_probe" \
  "$research_root/PhotonCamera/app/src/main/assets/spektrafilm/model"
PYTHONDONTWRITEBYTECODE=1 NUMBA_CACHE_DIR="$research_root/numba-cache" \
  test_temp/engine-reference-20261118/spektrafilm-env/bin/python -B \
  "$research_root/upstream_scan_probe.py"
```
