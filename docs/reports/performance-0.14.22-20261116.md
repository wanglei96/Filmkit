# 0.14.22：CPU 对齐 SAD 等价向量化

## 结论与交付

2026-11-16，交付并覆盖安装 **0.14.22 / 819**。本轮不继续修改 PC3D，
保留 21 版实现，不拆 shader/program；也未改降噪、融合、色彩或高光政策。
用户新设的 PC3D 核尺寸“自动”保持不变，未清除应用数据或删除照片。

可确认的成果是 ARM64 CPU 对齐计算的等价 NEON 实现：独立完整对齐的
Release O3 交替基准中位 **42.264 -> 17.6045ms，减少 58.35%**。
App 各三张实拍中，`alignCpuMs` 中位 **190.4 -> 97.96ms**，
观察到减少 **92.44ms / 48.55%**，但实拍温度不一致，不能将该百分比泛化。

App 保存中位为 7.348 -> 5.419s，**不能把 1.929s 全算作本轮收益**：
PC3D 源码没有再改，却从约 3s 降到约 1.6s，且候选组手机更凉。
本轮没有证明达成 4s 内出片或预览几乎不掉帧。

## 实现与质量边界

`core/engine/src/render/Align.cpp::sadTile`：

- 仅 ARM64，且原有 step 为 1 或 2、参考与目标整段横向范围都在图内时向量化。
- step1 每批读取连续 16 个样本；step2 用 `vld2q_u8(...).val[0]` 读取原算法
  已规定的 16 个偶数位置样本，**没有在原算法基础上额外跳样本**。
- 每行仍用原 y-clamp；尾部不足一批和横向边界 tile 走原标量计算。
- 无符号绝对差以扩宽整数归约到 32 位，保留全部原样本；不改 SAD 定义、
  金字塔层、搜索范围、候选顺序、平手判定、子像素估计、置信度或残差。
- 非 ARM64 保留原实现；`FILMKIT_ALIGN_SCALAR` 仅供测试构建参考实现。

完整阅读并借鉴了 libvpx 的整数扩宽 SAD 归约方式，未采用其中 SAD_SKIP 的近似路径：
<https://github.com/webmproject/libvpx/blob/main/vpx_dsp/arm/sad_neon.c>。
成像链未采用 ponytail 的质量简化策略。

## 正确性验证

新增可执行测试 `tools/check-align-sad.cpp`，链接单独编译并重命名导出符号的
生产标量 `Align.cpp`，而不是仅与另写的对齐算法比较。

- 12,000 个确定性随机 SAD 用例：小尺寸、奇数尾部、负坐标、偏移越界、
  step1/2/3、0 对 255 的最大逐样本差。
- 72 个完整对齐用例：65x67、129x131、256x192、513x385；相同/随机/平坦输入；
  L1 开关与 tile16/32/64。所有位移、置信度、全局摘要和残差精确相等。
- Host ASan/UBSan、`-Wall -Wextra -Werror` 通过；Host 不执行 ARM NEON，
  其 sanitizer 结果不能当作 NEON 的 sanitizer 证明。
- Android ARM64 O2 与 Release O3 均通过上述精确相等检查。

Host 复现：

```sh
clang++ -std=c++17 -O2 -Wall -Wextra -Werror \
  -fsanitize=address,undefined -fno-omit-frame-pointer \
  -DFILMKIT_ALIGN_SCALAR \
  -DmfnrAlign=mfnrAlignReference \
  -DmfnrAlignResidual=mfnrAlignResidualReference \
  -c core/engine/src/render/Align.cpp -o /tmp/align-reference.o
clang++ -std=c++17 -O2 -Wall -Wextra -Werror \
  -fsanitize=address,undefined -fno-omit-frame-pointer \
  tools/check-align-sad.cpp /tmp/align-reference.o -o /tmp/check-align-sad
/tmp/check-align-sad
```

生产 SO 与原 21 包 SO 在 Mali 真机上比较了 **34 个输出，全部逐字节一致**：

- 八组连续 2048 平方协方差用例，scale1/2/4、PC3D off/replace/stack、噪声变化，
  共 16 个 RGBA/JPEG。
- 六组连续 1024 平方白界状态转换，包含有/无短帧、尺寸变更、plain/single，
  共 12 个输出，白界调用断言通过。
- 真实 4096x3072 无短帧 RAW 回放，2 个 RGBA/JPEG。
- 两种引擎、全/半尺寸预览，共 4 个 RGBA。

GL0、字节数、非空亮度范围、JPEG 和状态断言通过。范围内逐位一致不等于全输入证明。
该回归未新增 color-median 预热，未把回归运行的耗时当作性能成绩。
驱动与输出记录位于 `test_temp/perf-stage-sync-20261116/check-production.py`
及 `production-*.txt`，设备隔离目录 `/data/local/tmp/fkperf-sad/`。

## 独立 Release CPU 基准

Vivo V2509A，输入金字塔第一层 2048x1536，32x24 tile，L1 开，tile64。
与生产 Release 一致的 O3、DNDEBUG、C++20 优化级别，标量参考和候选同进程。
四区组交替 ABBA/BAAB，每版 8 个样本；无实时预览，未锁频或锁温。

|指标|标量|NEON|
|---|---:|---:|
|中位 ms|42.2640|17.6045|
|范围 ms|32.949--51.144|12.338--30.273|

四个区组内均值差，候选减标量：**-21.8955、-20.038、-23.294、-27.123ms**。
全部同方向，且全部完整对齐检查通过。这是一次完整 CPU 对齐，不是整张照片。
先前 O2 结果 54.064 -> 23.1115ms 仅作补充，不混入 O3 统计。

证据：`test_temp/perf-stage-sync-20261116/sad-benchmark-release.txt`；
工具加 `--benchmark` 运行，设备副本在 `/data/local/tmp/fkperf-stage-sync/`。

## App 实拍

用户确认手机固定、允许自动采样后，各拍三张，至少间隔 30s。
先 21 后 22，**不是交替同 RAW 实验**。24mm、4080x3072 RAW、1/50s；
21 ISO1389，22 ISO1379；保存为 3072x4080，平均亮度分别 104/103/104 与 102/102/104。
均无 clipping，高光准入理由 `no_clipped_pixels`，没有强行造短帧分支。
用户设置始终是“自动”，六张实际 `pc3dM=11`、`pc3dSc=1`。
22 三张 `stageTimingSync=true`，没有启用实验性去等待。

|版本 / 快门时间|CPU 对齐 ms|完整对齐 ms|PC3D ms|引擎 ms|快门到保存 s|
|---|---:|---:|---:|---:|---:|
|21 / 19:52:49.369|149.40|424.1|3323.94|7161.88|8.414|
|21 / 19:53:19.511|190.70|296.6|2951.34|6003.31|7.063|
|21 / 19:53:49.657|190.40|321.4|3052.86|6399.00|7.348|
|22 / 19:57:03.002|64.76|240.6|1475.15|4305.51|5.419|
|22 / 19:57:33.121|121.90|305.1|1711.13|4485.82|5.498|
|22 / 19:58:03.255|97.96|192.1|1630.00|4052.26|5.141|

完整对齐包含 GPU 金字塔及读回等成本，中位 321.4 -> 240.6ms，不能和 CPU 中位差
重复相加。高光准入自己的 `alignMs` 中位 125.355 -> 128.995ms，未观察到一致改善，
不能把 SAD 基准提升等同于所有名为 align 的计时都有同等收益。

两组 thermal status 都为 2，但 HAL 实时 SKIN：21 为 **42.618 -> 42.400C**，
22 为 **40.408 -> 40.956C**；CPU 起点约 56.859 与 51.845C。温度和先后顺序是
明显混杂因素。CPU 独立交替基准支持方向，但整链 1.929s 的差不能归因于本轮。

|版本 / 次序|处理期最低完整窗口 FPS|处理期最大帧间隔 ms|全流程重叠窗口最大间隔 ms|
|---|---:|---:|---:|
|21 / 1|23.83|266.64|385.30|
|21 / 2|23.64|266.51|288.31|
|21 / 3|24.96|245.47|291.26|
|22 / 1|24.76|132.41|324.54|
|22 / 2|27.64|158.22|324.49|
|22 / 3|28.02|145.98|285.25|

没有记录到该采样窗口内的 preview_gap/preview_arrive_gap 阈值事件，
**不等于没有掉帧**。22 全流程仍有 285--325ms 间隔，不能声称预览问题已解决。
22 慢 draw 中 histogram 等待最高约 123.43ms，仍是后续目标之一。
两份完整日志共同含 11 行历史非法 JSON；分析器跳过，不能把它们计为本次新增错误。
22 采样区间未检出 error/fail 事件。

证据位于 `test_temp/perf-stage-sync-20261116/`：
`baseline-01421` / `candidate-01422` 的 `*-shots.json`、`.log`、`*-summary.json`、
`*-thermal-start.txt`、`*-thermal-end.txt`；分析器为
`tools/analyze-capture-performance.py`，按表中首个精确 capture 时间过滤。
自动采样已结束，三张新照片保留，手机留在可用相机页。

## 未启用的候选与保留诊断

1. **阶段计时去等待**：新增仅 C++/CLI 可调的 `setStillStageTimingSync`，
   默认仍 true，App 没有暴露 JNI 设置入口。测试工具支持 `--no-stage-sync`
   和 `--diag-stats`。仅门控 P0/RCD/CCM/CA/AMaZE 的计时等待；PC3D 等待、
   功能读回、最终完成、跨 context 同步和条带调度均不动。
   false 时不把 CPU 提交时间冒充 GPU 阶段时间，受影响字段记 -1。
2. 真 RAW 顺序测试前六次输出一致，但机器状态波动明显，下一次在 **原同步模式**
   出现异常长等待；定向终止该测试进程后恢复。不能区分驱动等待、内存压力等原因，
   因此不宣称去同步有性能收益，也不宣称是去同步导致卡住。原计划未完成。
3. **color-median 预分配**未进入 22 的 warmup。保留分配 helper 提取及 FBO 失败时
   清理重试逻辑；实际两次完整中值 pass、RGBA16F 和首次使用分配行为不变。
   额外大纹理预热在本轮没有可交付的性能结论，未计入成果。

阶段实验原始记录：`run-abba.py`、`results.json`、`diag0-block*.txt`。
其中旧 `results.json` 的 `kernel` 键取自浮点 `pc3dK`，不是实际核尺寸，
不能据此分组；正确尺寸应取 `pc3dM`。本报告 App 表使用后者。

## 构建、包校验与剩余工作

- arm64/x86_64 Release 构建通过，原有 `cw` unused warning 未改；未运行完整 App 单测。
- 诊断/DNG 独立性、快门不停预览、性能窗口边界、500 个并发 scope 检查通过；
  `git diff --check` 通过。
- APK v2/v3 签名通过、v4 关闭；AAPT 及手机安装均为 819 / 0.14.22，
  DEX 中精确版本字符串通过。
- 包内 ARM64 SO 与生产回归所用 SO 相同，SHA256：
  `7ac91bd6b8216f95c963672784d61dbd6e56817a6085584fa7ec11ed2a688f4f`。
- `nm -D` 检查 RAW burst、UltraHDR encode、HDR available、gainmap read 四个 JNI 通过。
- 根目录交付 `filmkit-0.14.22-PERF.apk`，保留 `filmkit-0.14.20-PERF.apk` 回退，
  清理过期 21 APK。未提交或推送代码，未修改项目记忆。

后续优先继续非 PC3D：当前帧 histogram 读回、RAW producer 与渲染队列隔离、
高光 guide/mask 的 CPU 执行和资源生命周期。当前实拍 guide 约 391--453ms，
仍有成本，但任何改动都应先完整保留算法和采样，再逐位验证与交替测量。
