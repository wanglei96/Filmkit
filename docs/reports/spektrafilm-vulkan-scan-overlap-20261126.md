# Vulkan 扫描尾段：CPU 准备下一条带与 GPU 同时运行

2026-11-26 · vivo V2509A / Mali-G1-Ultra MC12 · 手机保持 FilmKit 0.14.56（853）。

## 改动和对照

不改单像素计算。现有 Vulkan 扫描尾段连续执行 CAT02/CAM16、完整镜像 FIR/USM、量化和打包，写出实际供 GLES 增益图使用的 HDR 底图。串行模式用单输入：CPU 准备 XYZ 条带，记录并提交，等待，读回，再处理下一带。候选保留同样的 512 行条带与完整 halo，只把下一带的 CPU XYZ 转换及输入写入移到上一带提交之后、等待之前。

候选增加一块独立映射输入（本夹具约 24.2 MiB）。CPU 不写正在被 GPU 使用的 allocation；只有一张 fence、一条提交在途，等待后才更换 descriptor、读回或复用工作缓冲。外部 HDR 图像保持原 acquire / release，GLES 仍等最后一带完成后使用。失败时由已存在的 pending drain 先等 GPU 再释放图像，CPU 回退不使用部分结果。不改 shader、精度、滤波半径、边界、颗粒、曝光或 DIR。

三组使用**同一 native 库**，Vulkan Hanatos 都开、RAW 仍走现有 GLES/CPU，PC3D 和颗粒关。每配方同一组 3072×4080 室内四帧、8 位、HDR、30 Hz 合成预览，按 CPU / Vulkan 串行 / Vulkan 重叠 / Vulkan 重叠 / Vulkan 串行 / CPU 跑独立进程；每进程两张。两种 Vulkan 都做同样的后台管线初始化。12 个进程两端均亮屏、热状态均为 0。这里的引擎时间含实际 HDR 增益图消费，不含快门等待、App JPEG/R 封装和 MediaStore。

## 真机结果

中位数，单位 ms，首张／第二张；每格每张次两次独立进程。

| 配方、指标 | CPU 尾段 | 串行 Vulkan | 重叠 Vulkan |
|---|---:|---:|---:|
| Gold 200 / Endura：扫描＋打包＋HDR 增益图 | 176／180 | 185／129 | **131／107** |
| Gold 200 / Endura：RAW 引擎 | 2805／2662 | 2599／2280 | 2432／2578 |
| Gold 200 / Endura：合成取景最长交付间隔 | 74／80 | 73／65 | 95／84 |
| Provia 100F：扫描＋打包＋HDR 增益图 | 166／186 | 168／135 | **130／101** |
| Provia 100F：RAW 引擎 | 2035／1942 | 2077／1860 | 1983／1937 |
| Provia 100F：合成取景最长交付间隔 | 65／66 | 64／51 | 63／69 |

- **局部收益复现**：第二张含增益图，Gold 129→107 ms，Provia 135→101 ms。CPU 在已有提交尚未回收时准备下一带，累计约 19 ms／19 ms；余下 fence 等待约 65→40 ms／65→38 ms。GPU 时间戳区间也从 57→52 ms／57→50 ms，首张还混有资源设置和 GPU 区间差异；不能把全部墙钟差都解释成重叠所得。
- **没有确认稳定净收益**：相对串行 Vulkan，两配方首张引擎中位数变快，第二张却慢 299 ms／76 ms。第二张去掉被改尾段后，其余阶段合计中位数 Gold 2150→2472 ms，Provia 1726→1835 ms；差异远大于尾段节省，不能把变化归咎于尾段或当成加速。相对当前 CPU 尾段，Gold 引擎样本中位数是正向，Provia 第二张只差 5 ms。取景也未过线：对串行 Vulkan 的第二张最长间隔约都增 18 ms；这不是实拍取景。
- **去向**：不合入，不发包；恢复普通 ARM64 构建，候选只保留可回放补丁。Vulkan 开关与预览未改。这个结构不减少 XYZ 的 CPU 转换或读写，仅尝试隐藏当次提交后约 19 ms 的准备，不值得继续用更多缓冲把它当主要收益来源。下一次若继续做 Vulkan，先对共同的 RAW 驻留段量清楚输入就绪到 Hanatos 可读的交接与等待；只搬 RCD 的旧试验不是这个问题的替代。Gold 印相和扫描光谱热态约 0.50 s，也有更大余量，但不应把它外推成 Provia 的收益。

## 画质、复现和清理

按用户指定原片：110832 人像、110846 室内绿植、110904 室外建筑、110936 鲜花。全分辨率同 Bayer、标定与方向，Gold 200 / Endura 和 Provia 100F 各跑 8 位和 RGB10_A2。**16 组、48 个文件**的 RGBA、HDR 增益图、回放 JPEG 在串行与重叠之间全部 SHA-256 相同，也全部匹配[上轮同四张 DNG 的 Vulkan 原尺寸归档和 1:1 检视图](spektrafilm-vulkan-front-scan-20261126.md#画质与证据)。重复四帧性能回放的 Vulkan 两路三个输出也逐字节复现。这里是相对串行 Vulkan 的像素无变化，不推断成相对 CPU FP64 逐字节一致或跨机型等价。

证据：`test_temp/spektrafilm-vulkan-scan-overlap-20261126/`。保留 `stripe-overlap.patch`、压缩实验库及校验和、脚本、原始日志、性能汇总和 `verified-hashes.json`。用普通 Vulkan still-probe 源码应用补丁，并将已配置的 ARM64 build 切到 `FILMKIT_VULKAN_STILL_PROBE=ON` 后编译；或解压目录中的实测库。`replay.sh` 中 `SWEEP_SCAN=1` 时模式 0=CPU、2=预热串行、3=预热双输入重叠。回放固定屏幕状态，结束恢复原来充电保持唤醒设置。重建四 DNG 的 Bayer 使用 `tools/prepare-spektrafilm-regression-dng.py`。

已核对 DNG 原始哈希、送测输入与保留副本后，清理本轮生成的 Bayer、重复件和未压缩实验库；本轮目录最终约 8.9 MiB，见 `cleanup.json`。没有清理原 DNG 或其他实验。默认 ARM64 库无 `FILMKIT_VK_SCAN` 入口，Vulkan 能力检测和 Ultra HDR JNI 均在，手机未安装任何新包。
