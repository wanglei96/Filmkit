# Vulkan 共享纹理真机实验：RAW → Hanatos

2026-11-26，vivo V2509A / Mali-G1-Ultra MC12。手机保持 0.14.56（853），未重装、未触发快门；仅在桌面运行独立 native 回放。

## 实现与画质

- 现有 Vulkan：从 GLES 读取原始 RGBA16F 条带至 Vulkan 映射缓冲。候选：用同规格 RGBA16F 的 Android Hardware Buffer，让 GLES blit 一次，等该纹理的 fence 后由 Vulkan 导入、逐像素读取；没有输入侧的 CPU 全图读回。不是完全零拷贝：仍有一次 GPU blit、所有权切换和投影后向 CPU 的 FP64 展开。
- 保留完整 Hanatos 16 tap 投影、异常全帧回到原 CPU 路径和后续 FP64 算法。原始 GLES 纹理仍供 HDR 和回退。RGBA32F 不强转成 RGBA16F；走原分带 Vulkan。另对比了共享路线的 NEON 精确 FP32→FP64 展开。
- 用既定四 DNG：人像 110832、室内绿植 110846、室外建筑 110904、鲜花 110936。四场景 × Gold 200 / Endura 和 Provia 100F 直扫 × 8 位与 RGB10_A2 共 **16 组**，同 Bayer 数据、标定和设置。共享版与 0.14.56 已归档的 Vulkan 输出 **48/48 个 SHA-256 一致**（RGBA、HDR 增益图、回放 JPEG 各 16 个）。这是对原 Vulkan 路径逐字节等效，不将其表述成对 CPU FP64 完全逐字节等效。旧版对 CPU 的最大像素差 1 码，见 [0.14.56 报告](spektrafilm-vulkan-01456-20261126.md)。
- Vulkan 1.0/1.1 SPIR-V 分别通过 NDK `spirv-val`；Android ARM64 默认及探针构建通过。共享模式仅在 `FILMKIT_VULKAN_AHB_PROBE=ON` 的 native 回放构建中由环境变量启用，不替换应用现有开关路径。未验证其他机型。

## 四帧 RAW 交错回放

固定已有自然室内四帧 RAW（3072×4080、关颗粒、8 位、HDR），同步跑 30 Hz 合成取景；每次拉起进程连续回放两张。四路顺序 CPU／原 Vulkan／共享／共享+NEON／共享+NEON／共享／原 Vulkan／CPU。每配方每路线两次有效进程，取首张与第二张各自的中位数。Gold 有一进程热状态从 0 变 1，丢弃该进程两张，冷却后补缺的两路；其余有效进程均为 0→0。不是快门到相册的实拍时间。

| 配方 / 阶段（ms） | CPU FP64 首张／第二张 | 现有 Vulkan 首张／第二张 | 共享纹理首张／第二张 | 共享＋NEON 首张／第二张 |
|---|---:|---:|---:|---:|
| Gold 200 / Endura：交接＋Hanatos | 257 / 203 | 197 / 116 | 161 / 111 | 134 / 106 |
| Provia 100F：交接＋Hanatos | 234 / 196 | 205 / 132 | 144 / 110 | 135 / 111 |
| Gold 200 / Endura：RAW 引擎 | 3017 / 2745 | 2770 / 2578 | 2847 / 2503 | 2599 / 2450 |
| Provia 100F：RAW 引擎 | 2453 / 2219 | 2489 / 2126 | 2366 / 2045 | 2402 / 2155 |

- 相对现有 Vulkan，纯共享的交接＋Hanatos 首张少 36／62 ms（两配方中位数），第二张只少 5／23 ms。首张输入交接 54–55 → 4–5 ms；每张重新分配和导入共享纹理在第二张增加约 12–18 ms setup，抵销了一部分局部收益。两路 shader 做的是同样的完整投影，不把整链差都记到账上。
- 纯共享第二张 RAW 引擎观察早约 76／80 ms，但首张 Gold 晚约 78 ms、Provia 早约 123 ms。NEON 的热态 FP64 展开在 Gold 约 42→35 ms、Provia 约 39→40 ms，没有跨配方的稳定收益。所有 Vulkan 路线的两张 RGBA、增益图及 JPEG 对应 SHA-256 均一致。
- 合成取景第二张的间隔 P95（现有 Vulkan → 共享）Gold 37.4→35.1 ms、Provia 35.2→35.9 ms；Gold 的最长间隔中位数 66.7→83.9 ms。仅为回放观察，不承诺真实取景不变。
- 不因一次约 0.08 秒引擎收益替换现有 Vulkan：局部走通且逐字节匹配，但首张、取景峰值和整链均未稳定受益。共享及 NEON 仅作回放探针，Gradle 出包明确关掉探针；原有应用开关和回退不变。下一项优先验证能连贯驻留 GPU 的多阶段方案（例如扫描颜色变换、原尺寸过滤、打包/HDR 基底），按含上传、读回的整段净时间取舍；不再单独搬运曝光/DIR 的 IIR。

## 复现与清理

证据与脚本在 `test_temp/spektrafilm-vulkan-ahb-20261126/`：`quality/`、`perf*`、`summary.json`、`replay.sh`、`summarize.py`，含手机输出 SHA-256、回放源 SHA-256 和热状态。两套实测 ARM64 实验库保留，对照 0.14.56 库仍在已签 APK。复现用已配置的 native ARM64 build 目录，`cmake -S core/engine -B app/build/filmengine/arm64-v8a -DFILMKIT_VULKAN_AHB_PROBE=ON` 后编译，再将 `LIBRARY` 指向该库。回放环境 0=CPU、2=原 Vulkan、3=共享、4=共享＋NEON。固定四 DNG 的预处理文件可用 `tools/prepare-spektrafilm-regression-dng.py` 重建；生成的 4 个 RAW 已确认 SHA-256 与送测输入相同后清理，未删除任何原 DNG。手机上只清理了本轮回放创建的临时目录。
