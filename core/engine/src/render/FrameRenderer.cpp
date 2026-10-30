#include "FrameRenderer.h"

#include <sys/stat.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <mutex>
#include <string>
#include <string_view>
#include <vector>

#include "../log/GpuTimer.h"
#include "../log/Logger.h"
#include "../film/EngineRegistry.h"
#include "../film/SigmoidParams.h"
#include "Align.h"
#include "AmazeDemosaic.h"
#include "AeMetering.h"
#include "GrainLut.h"
#include "OutputBitDepth.h"

#ifndef GL_TEXTURE_EXTERNAL_OES
#define GL_TEXTURE_EXTERNAL_OES 0x8D65
#endif

namespace filmkit {

// 引擎 1（sigmoid）的两个 GLSL 注入段 —— **定义在本文件后部**（紧跟 `kFsFilm` 的
// 切分/组装块之后），此处前置声明供下面的 `filmSource()` 使用。
// 为什么放在 `filmkit` 作用域（而非本文件的匿名 namespace）：注册表
// `film/EngineRegistry.cpp` 的表项要按这两个名字链接引用它们（见其 extern 声明与
// `EngineRegistry.h` 顶部的"链接期依赖"说明）。**必须在匿名 namespace 之外**，
// 否则是内部链接的另一个实体 ⇒ 链接期 undefined symbol。
// 命名固定为 `kFsSigmoid{Def,Main}`：离线链（`tools/engine-shader-offline.py` 的
// `_collect_raw_strings`）与 `tools/validate-shaders.sh` 都按**字面量名**提取。
extern const char* kFsSigmoidDef;
extern const char* kFsSigmoidMain;

namespace {

// E4-3 颗粒：成片种子的**内容指纹**（FNV-1a 采样）。目的 = 成片的颗粒图案"同一张可
// 复现、不同张不同"（darktable 用文件名 hash 达到同一目的）。
// 为什么不用时间戳：引擎内**没有任何时间源**（全仓 grep timestamp / time() 零命中，
// ChainCtx / submitStill 都没有该参数）；而验收判据 6 要求"同参数两次导出逐位相同"
// ⇒ 时间戳反而会破坏它（两次导出时刻不同）。内容指纹同时满足两条。
// 采样 ≤1024 点（步长 = 总长/1024）⇒ 12.5M px 的 RAW 也只读 1KB，成本可忽略。
// ⚠ 必须定义在本匿名 namespace 内（调用点 submitStill/submitRaw/finishStillChain 都在
// 文件前部，前置声明在这里）：定义放到文件后部会落到 `filmkit` 而非匿名 namespace，
// 与声明是两个不同实体 ⇒ **链接期 undefined symbol**（本次已踩，见报告）。
static uint32_t grainFingerprint(const void* p, size_t n_bytes) {
  if (p == nullptr || n_bytes == 0) return 0;
  const auto* b = static_cast<const uint8_t*>(p);
  const size_t step = n_bytes > 1024 ? n_bytes / 1024 : 1;
  uint32_t h = 2166136261u;  // FNV-1a offset basis
  for (size_t i = 0; i < n_bytes; i += step) h = (h ^ b[i]) * 16777619u;
  return h;
}

// E4-3：成片颗粒种子 = f(输出尺寸, 内容指纹) → [0, 64) 的**相对** x 偏移。
// 为什么是相对量：噪声坐标已按短边归一化（uGrainNorm = 1/min(W,H)）⇒ 偏移也必须是
// 无量纲的相对量，否则同一张图在不同输出分辨率下图案不同（ADR-11 的同一条要求）。
static float grainSeedFrom(uint32_t fp, int w, int h) {
  uint32_t x = fp * 2654435761u;
  x ^= static_cast<uint32_t>(w) * 2246822519u;
  x ^= static_cast<uint32_t>(h) * 3266489917u;
  x ^= x >> 15;
  x *= 2246822519u;
  x ^= x >> 13;
  return static_cast<float>(x & 0xffffu) / 65536.0f * 64.0f;
}

// 数值 → JSON 合法字符串（截断到 n 字符）。
// 必须去掉截断后**孤立的尾部符号**：jnum(1139.4, 5) = "1139."
// 是非法 JSON —— 整行日志会被任何 JSON 解析工具静默丢弃。2026-09-30 实测：
// 4 次拍照只有 1 条 raw.render 能被 python json.loads 解析出来，另 3 条（dftDsp
// 四位数 + 5 字符截断）全部消失，一度被误判成"日志链路缺数据"。历史日志抽样
// 12145 行里 25 行（0.2%）非法，全部是这种尾部孤立 '.'。
inline std::string jnum(float v, size_t n) {
  std::string s = std::to_string(v);
  if (s.size() > n) s.resize(n);
  while (!s.empty() && (s.back() == '.' || s.back() == 'e' || s.back() == 'E' ||
                        s.back() == '-' || s.back() == '+')) {
    s.pop_back();
  }
  return s.empty() ? std::string("0") : s;
}

// E2-11：编辑器链路的色温/色调 → 线性 RGB 增益（模型与依据见下面两个函数的注释）。
namespace {
// ── E2-11 色温/色调 → 线性 RGB 增益（CPU 侧每帧一次；shader 侧零成本）──────────
// 模型 = **Tanner Helland 黑体近似**（tannerhelland.com/4435 "Convert Temperature to
// RGB"），与拍照链路的 `CameraController.wbGains()` **逐行同式**（唯一差异：分母加
// `max(rn,1e-3)` 防除零，该分支在 1500..12000K 内不可达）。
// 依据（为什么不用 DNG / darktable 的 CIE 轨迹 + Bradford 色适应）：
//   ① **同一 App 只能有一套"色温"语义** —— 编辑器与拍照链路同模型，用户在两处拨
//      同一档看到的暖冷才一致；换更精确的模型反而让两条链路打架。
//   ② 它是**仓库里已有**的实现（`CameraController.kt:648`，含反向扫描 `kFromGains`），
//      逐行对照即可核对；CIE CMF 表 / Robertson 表 / 色适应矩阵常量在离线环境无法
//      核对（写了就是幻觉源）。
//   ③ 纯分段解析式 ⇒ 验证脚本能用 numpy 精确复算（判据 ≤3 LSB 可达）。
// 已知瑕疵（如实记录）：t≤66 与 t>66 两段在 **6600K 处 G 通道不连续**（255 → 251.3，
//   ≈1.5%）。滑杆跨过该温度会有一级台阶，见交付报告 §3 的范围建议与升级路径。
// 语义：返回"把色温 kelvin 的光源还原为中性"的增益（G 归一，与拍照链路同向）。
void wbGainFromKelvin(float kelvin, float g[3]) {
  const float t = std::clamp(kelvin, 1500.0f, 12000.0f) / 100.0f;
  float r, gg, b;
  if (t <= 66.0f) {
    r = 255.0f;
    gg = 99.4708025861f * std::log(t) - 161.1195681661f;
  } else {
    r = 329.698727446f * std::pow(t - 60.0f, -0.1332047592f);
    gg = 288.1221695283f * std::pow(t - 60.0f, -0.0755148492f);
  }
  if (t >= 66.0f) {
    b = 255.0f;
  } else if (t <= 19.0f) {
    b = 0.0f;
  } else {
    b = 138.5177312231f * std::log(t - 10.0f) - 305.0447927307f;
  }
  const float rn = std::clamp(r, 0.0f, 255.0f);
  const float gn = std::clamp(gg, 0.0f, 255.0f);
  const float bn = std::max(std::clamp(b, 0.0f, 255.0f), 1.0f);
  g[0] = std::clamp(gn / std::max(rn, 1e-3f), 0.3f, 3.0f);
  g[1] = 1.0f;
  g[2] = std::clamp(gn / bn, 0.3f, 3.0f);
}

// [88] temp_mired / [89] tint → 相对参考白点（6500K ≈ D65）的 RGB 增益。
// **参考点差分**是零回归的关键：[88]=[89]=0 时分子分母是同一次调用、同一输入 ⇒
// 比值逐位 = 1.0f ⇒ 与 [1..3] 相乘后仍是原值 ⇒ 输出逐位不变（硬约束 2）。
// 方向：mired 是**目标白点的 mired 偏移**（mired_target = 1e6/6500 + Δmired、
// K_target = 1e6/mired_target）⇒ Δmired > 0 = 目标色温更低（更暖的光源）⇒ 按
// "把该白点适应到中性"计算 ⇒ **画面变冷**。与 Lightroom/ACR 的 K 读数同向
//（K 小 = 冷）⇒ UI 直接显示 K_target 即可（ADR-14 要求物理量读数）。
void tempTintGain(float mired, float tint, float g[3]) {
  const float k_ref = 6500.0f;
  const float m_ref = 1e6f / k_ref;
  const float k_target = 1e6f / std::max(m_ref + mired, 1.0f);
  float gt[3], gr[3];
  wbGainFromKelvin(k_target, gt);
  wbGainFromKelvin(k_ref, gr);
  g[0] = gt[0] / gr[0];
  g[1] = 1.0f;
  g[2] = gt[2] / gr[2];
  // tint（绿 ↔ 品红）：RGB 增益空间沿该轴的线性近似（正 = 品红 = 压 G、抬 R/B）。
  // R/B 各取一半 ⇒ 色度往品红走而**亮度近似守恒**（加权和 = 1 − 0.38·tint）；残留
  // 偏差是有意保留的：Lightroom 的 tint 同样轻微改亮度，不额外补偿（少一层猜测）。
  const float t = std::clamp(tint, -1.0f, 1.0f);
  g[0] *= (1.0f + 0.5f * t);
  g[1] *= (1.0f - t);
  g[2] *= (1.0f + 0.5f * t);
}
}  // namespace

// 预览域映射的在线标定状态（v0.8.2 提为**进程级**）：nativeEngineStart 每次
// new FrameRenderer，放在成员里会随 engine stop/start 归零 —— 真机日志实测
// ratio 长期钉在初值 2.0 不收敛。进程存活期间跨实例保持；App 冷启动回落
// 初值、拍一张静拍即重新收敛（EMA α=0.3）。
float g_pv_mid_ema = 0.0f;   // 预览统计中位的 EMA（0 = 未初始化）
// v0.11.92：预览的**几何平均** EMA —— 成片 AE 的目标亮度直接用它（"对齐取景器"）。
// 为什么不是 P50：P50 只说"一半像素比它暗"，对"半暗半亮"的构图会严重偏低（真机
// 2147：P50 0.1076 → 锚到固定 0.45 要 +2.0EV → mean 184、hi240 2.67%）。预览的
// 中位是厂商 AE 已经权衡过的结果（它做了区域测光），拿它当目标等于"成片与取景器
// 一样亮"—— PhotonCamera 的 Huber 网格匹配在做的也是这件事。
float g_pv_geo_ema = 0.0f;
// v0.11.93：成片 AE 的 EMA —— 预览也叠上它，取景器才与成片同亮度。
// 之前预览恒用 exposure_ev=0、成片 +0.6~1.7EV，用户看到的是"预览暗、成片亮"，
// 而这恰好破坏了 v0.11.92 的取景器匹配前提（目标来自预览，预览却不是最终亮度）。
// v0.12.71：g_still_ae_ema **已删除** —— 跨张状态（上一张的 aeEv EMA）会让
// 取景器和成片不一致、且"换场景后一张滞后"。曝光只由 HAL + planner + baseEv 决定。
float g_domain_ratio = 2.0f; // HAL 域 / RAW 域 中位比（初值 = 实测范围 1.70–3.87 的中位）
// 0.12.51：**显示域**中位（sRGB 编码 0..1）的预览 EMA —— 显示域 AE 闭环的实测值。
// 为什么必须用显示域：`p_robust` 是 RAW 域的统计，而"够不够亮"是显示域的观感；
// 两者之间隔着 sigmoid 肩部/teq/LUT，映射不恒定（真机 132502 输入 0.18 → mean 91，
// 0601 输入 0.17 → mean 115）。PC 的 `MeteringSystem.DISPLAY_TARGET_LUMA = 0.18`
// 也是显示域（sRGB 缩略图线性化后的 luma）—— 这一层对齐后才能跨场景用同一个目标。
float g_pv_disp_p50 = 0.0f;
// 0.12.66：最近一张的 RAW 中灰（`ae_p50_` 的跨线程快照）—— Kotlin 侧用它决定
// "是否允许长帧过曝"（阴天/白墙这类场景必须排除）。
float g_disp_ae_ev = 0.0f;   // 显示域闭环累计 EV（诊断）

// 预览降载（v0.10.93）：1.0 = 全分辨率。进程级 atomic —— 写方（Kotlin 的
// capture 入口/完成回调，任意线程）与读方（渲染线程 submitFrame 每帧开头）
// 天然解耦：写后下一帧生效，无锁、无 post、无生命周期竞态（g_renderer 的
// delete 也碰不到它）。拍照结束必须回 1.0，否则预览一直糊（见 CameraController
// 的 releasePreviewScaleGuard，快门到合成结束全程 0.5）。
std::atomic<float> g_preview_scale{1.0f};

// NN tile 推理回调（v0.10.102）：进程级单槽，Bridge.cpp 在每次 submit 入口注入。
// 只在拍照合成调用栈内被调（JNIEnv 契约由 Bridge 维护），无并发风险。
NnInferFn& nn_infer_slot() {
  static NnInferFn fn = nullptr;
  return fn;
}

NnInfoFn& nn_info_slot() {
  static NnInfoFn fn = nullptr;
  return fn;
}

// ── NN 降噪 pass（v0.10.102/104）标定常数（对照官方 run_benchmark.py）─────
constexpr int kNnTile = 512;      // PMRID tile 尺寸
constexpr int kNnOverlap = 16;    // tile 重叠（盲点网络边界伪影的标准对策）
// 官方 Denoiser：inp = img01 × cvt_k(iso) × inp_scale(256)，cvt_k = K_a/K(iso)
//（var 线性模型 var_dn = K(iso)·x_dn + Σ(iso) 的 shot 项之比，anchor=1600 恒等）。
// K(iso) ∝ 传感器 shot 增益 → 用 X300 噪声模型的 S（G 通道，[0,1] 域）之比。
constexpr float kNnInpScale = 256.0f;
// anchor = **训练数据的噪声水平**（网络见过的输入分布），不是本机传感器自己的 S。
// 由官方 run_benchmark.py 的 K_coeff 换算到 img01 域（V=959）：
//   S_train = K(1600)/V = 0.96793/959 = 1.0093e-3
// v0.10.104 曾误用 X300 自己的 S(ISO1600)=4.5e-4 当 anchor —— 输入噪声只有训练
// 水平的 1/2.24；再叠加 MFNR 融合的 ÷neff（输入是 tex_fused_）共差 ~9×，网络按
// 训练噪声量去减 ⇒ 细节全糊 + decoder 块状伪影（255 真机复测：文字糊、边缘格纹）。
constexpr float kNnAnchorS = 1.0093e-3f;

// 语义序（官方训练口径）的相位下发与反查表：ch0=R、ch1=Gr（与 R 同行的 G）、
// ch2=Gb（与 B 同行的 G）、ch3=B —— 官方 run_benchmark.py 对 BGGR 先 bggr2rggb
// (rot180→RGGB) 再 bayer2rggb 打包，得到的就是这组语义；其 rot180 只是"把 BGGR
// 摆成 RGGB"的手段，等价于不翻转、直接按语义取相位（逐通道一致，已核对）。
// v0.10.106：0.10.102–0.105 用的是 (B,Gr,Gb,R)，ch0/ch3 与官方对调 —— 会让网络
// 对 R 位置与 B 位置给出不同残差（相邻 CFA 相位被不同程度修改），实测 art4 略升。
// ph = (y&1)*2 + (x&1)：0=(0,0) 1=(1,0) 2=(0,1) 3=(1,1)。
//   phSem[cfa][ch] = {dx,dy}：通道 ch 的相位偏移（pack 用）
//   phChMap[cfa][ph]：相位 → packed 通道 idx（unpack 反查）
void nnSemTables(int cfa, int ph_sem[4][2], int ph_chmap[4]) {
  switch (cfa) {
    case 0:  // RGGB「R G / G B」
      ph_sem[0][0] = 0; ph_sem[0][1] = 0;  // R =(0,0)
      ph_sem[1][0] = 1; ph_sem[1][1] = 0;  // Gr=(0,1)
      ph_sem[2][0] = 0; ph_sem[2][1] = 1;  // Gb=(1,0)
      ph_sem[3][0] = 1; ph_sem[3][1] = 1;  // B =(1,1)
      ph_chmap[0] = 0; ph_chmap[1] = 1; ph_chmap[2] = 2; ph_chmap[3] = 3;
      break;
    case 1:  // GRBG「G R / B G」
    default:
      ph_sem[0][0] = 1; ph_sem[0][1] = 0;  // R =(0,1)
      ph_sem[1][0] = 0; ph_sem[1][1] = 0;  // Gr=(0,0)
      ph_sem[2][0] = 1; ph_sem[2][1] = 1;  // Gb=(1,1)
      ph_sem[3][0] = 0; ph_sem[3][1] = 1;  // B =(1,0)
      ph_chmap[0] = 1; ph_chmap[1] = 0; ph_chmap[2] = 3; ph_chmap[3] = 2;
      break;
    case 2:  // GBRG「G B / R G」
      ph_sem[0][0] = 0; ph_sem[0][1] = 1;  // R =(1,0)
      ph_sem[1][0] = 1; ph_sem[1][1] = 1;  // Gr=(1,1)
      ph_sem[2][0] = 0; ph_sem[2][1] = 0;  // Gb=(0,0)
      ph_sem[3][0] = 1; ph_sem[3][1] = 0;  // B =(0,1)
      ph_chmap[0] = 2; ph_chmap[1] = 3; ph_chmap[2] = 0; ph_chmap[3] = 1;
      break;
    case 3:  // BGGR「B G / G R」
      ph_sem[0][0] = 1; ph_sem[0][1] = 1;  // R =(1,1)
      ph_sem[1][0] = 0; ph_sem[1][1] = 1;  // Gr=(1,0)
      ph_sem[2][0] = 1; ph_sem[2][1] = 0;  // Gb=(0,1)
      ph_sem[3][0] = 0; ph_sem[3][1] = 0;  // B =(0,0)
      ph_chmap[0] = 3; ph_chmap[1] = 2; ph_chmap[2] = 1; ph_chmap[3] = 0;
      break;
  }
}

// M4-7 局部色调映射的低频亮度图尺寸与 knee（都是设备端标定旋钮）。
// 32×24 在 4080×3072 上等效模糊半径约 128px ≈ 3% 画面宽：再小会退化成全局曲线
// 并引入光晕，再大（如直接复用 256×96 的直方图目标）就等于逐像素亮度，提亮量会
// 跟着细节走、把暗部内部对比压平。
constexpr int kLocalW = 32, kLocalH = 24;
// ── MFNR 阶段二（v0.10.0）robust merge 标定常数 ─────────────────────────
// s：mismatch 的 σ 缩放 —— m = d²/(d²+s·σ²)。噪声底（d²≈2σ²，两独立噪声样本之
// 差）时 m = 2/(2+s)：s=16 → m≈0.11（权重 0.89，基本满融）；运动 tile 的 d² 远超
// 噪声底 → m→1（权重→0，回退参考帧）。离线扫描（tools/tuning 同法）真机再调。
constexpr float kMfnrMisS = 16.0f;
// 0.12.195：**打开**（0.12.135 起默认关）。用户 2026-10-30 的判断是"高光一塌糊涂、
// PC 同场景没问题"——而 PC 的 MGC 高光正是走"短帧真值 + inpainting mask"
// （Mask_AdjustHighlightMaskAndGenerateInpaintingMask），不是参考场色比外推。
// 关着它时，三通道全饱和区只剩 opposed 外推 ⇒ 大面积平色（灰斑）与色比错（品红/绿边）
// 都是这条唯一路径的产物。打开后其余链路一字不改：只有"三通道都顶到满阱"的 2×2 块
// 会用短帧标量亮度替换，部分饱和仍由 opposed 从 R/B 外推（0.12.134 的口径）。
constexpr bool kHlInpaintOn = true;
// v0.10.5：权重从线性压制改**分段**（用户验收标准：允许自然拖影、不容重叠错误）：
//   m ≤ kMisLo 全融合（静止/准静止 —— 比线性式更满，保细节）
//   m ≥ kMisHi 权重硬清零（运动区域整体回退参考帧 —— 宁可糊/噪，不容叠影）
// 线性中间带只留过渡。v0.10.4 及以前的 w = max(1−c·m,0) 线性式退役。
constexpr float kMisLo = 0.10f;   // ≤ 全融合
constexpr float kMisHi = 0.35f;   // ≥ 全回退
// 位移幅度运动检测（v0.10.5）：残差判据对"平滑皮肤"有物理盲区 —— 手掌内部
// 帧间错位后残差≈0（皮肤自相似），alt 帧的手指边缘以全权叠进参考帧的手掌
// （用户指认的"重叠错误"，v0.10.4 的 32px cell + 膨胀无法覆盖手掌宽 500px
// 的内部）。物体运动（挥手 20-30px）与相机抖动（全局一致 ~6px）的差异在
// **位移场**里清晰可见：局部位移显著超过全图中位 = 运动，与纹理无关。
// m_motion = clamp((mag − magMed − kMotLo) / (kMotHi − kMotLo), 0, 1)
constexpr float kMotLo = 8.f;     // 超出全图中位位移 8px（mosaic）起步
constexpr float kMotHi = 20.f;    // 超出 20px 满分（硬回退）
constexpr int kMisDilate = 2;     // m 场膨胀半径（cell）：2 = 5×5，覆盖 64px 带
// 暗光残差衰减（v0.10.8）：暗光的对齐置信度趋零（confMed 0.004 @ISO3347）、实际
// 噪声/对齐误差超过 HAL profile 假设 —— 残差判据大面积误报（无运动的场景
// mMed 0.68/mP90 1.0），回退块（单帧高噪）与融合块交错 = "块状噪点"。
// ns 代理 ISO：ns_alt > 0.02（≈ISO 1000+）开始衰减，0.04 时降到 0.3 —— 暗光
// 只信位移判据（与噪声无关）。
constexpr float kResFadeLo = 0.02f;
constexpr float kResFadeHi = 0.04f;
constexpr float kResFadeMin = 0.3f;
// 位移一致性门控（v0.10.10）：真运动位移连片（刚体平移，邻域一致）；对齐假峰
// （弱纹理/暗部 SAD 无区分度，argmin 随机落在搜索边界 ±8-15px）是孤立随机的。
// dev = |off − 3×3 邻域中值| > kOffDev → 假峰 → m_mot 清零。插桩数据（v0.10.9）：
// 暗光误报 100% 来自位移判据（misD 0.99 干净、motD 高 m 20-56%、motMed 仅
// 1.8-3.3px 的双峰分布）；残差侧重修两轮全都看错方向。
constexpr float kOffDev = 6.f;
// ratio knee 区间：低于下界满额提亮，高于上界完全不提亮。区间要**宽** —— 真机反馈
// "画面有亮斑"：原 0.7→1.0（宽 0.3）在"大面积白墙 + 暗桌面"的场景里让 27% 的画面
// 满额提亮 60%、而一半区域几乎不提亮（lift 0.011），提亮量空间分布过陡 → 可见分区。
// 放宽到 0.6→1.3（宽 0.7）后分布收窄到 0.18–0.40，满额占比 27%→21%。
constexpr float kLocalKnee = 0.6f, kLocalKneeHi = 1.3f;

// 顶点：全屏三角 + 90° 步进旋转 + center-crop，最后经 SurfaceTexture 矩阵
// （含生产者翻转）映射到 OES 纹理坐标。
const char* kVertRot = R"(#version 300 es
precision highp float;
out vec2 vUV0;
uniform mat4 uST;
uniform int uRot;
uniform vec2 uScale;
void main() {
    int id = gl_VertexID;
    vec2 p = vec2(float((id << 1) & 2), float(id & 2));
    vec2 c = p - 0.5;
    vec2 r = c;
    if (uRot == 1) r = vec2(-c.y, c.x);
    else if (uRot == 2) r = vec2(-c.x, -c.y);
    else if (uRot == 3) r = vec2(c.y, -c.x);
    vec2 uv = r * uScale + 0.5;
    vUV0 = (uST * vec4(uv, 0.0, 1.0)).xy;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
})";

// 顶点：无变换全屏三角（内部 pass 输出用）
const char* kVertPlain = R"(#version 300 es
precision highp float;
out vec2 vUV0;
void main() {
    int id = gl_VertexID;
    vec2 p = vec2(float((id << 1) & 2), float(id & 2));
    vUV0 = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
})";

// Pass1：SurfaceTexture(OES) → 场景线性 RGB。
// 参照 PhotonCamera：不做 YUV 平面手动上传（X300 Mali 上 RG8 TexSub
// 静默失败），由系统 SurfaceTexture 路径完成 YUV→RGB。
const char* kFsOes = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform samplerExternalOES uTex;
uniform vec3 uWb;
// ── 期 B：预览链路的域补齐 ───────────────────────────────────────────────────
// HAL 预览流是 **sRGB 编码**，pow(2.2) 之后是"近似 sRGB 线性"；而本链路**不进 CCM**
//（实读：`uCcm` 只出现在 kFsRaw / kFsCcm，本 shader 没有）⇒ 数据一直停在 sRGB 线性，
// 而下游 kFsFilm / kFsOut 已按 **Rec.2020** 工作 ⇒ 域错配（症状：预览与成片偏差拉大）。
// 这里补一次 sRGB 线性 → Rec.2020 线性；矩阵与 kFsOut 的 workToSrgb **互逆**
//（真源 `tools/rec2020-domain-matrices.py`）。
// ⚠ uWb 保持在原位置（解码之后、域转换之前）：它是既有标定过的通道增益，不动作用域。
vec3 srgbToWork(vec3 c) {
    return vec3(dot(c, vec3(0.6274038959, 0.3292830384, 0.0433130657)),
                dot(c, vec3(0.0690972894, 0.9195403951, 0.0113623156)),
                dot(c, vec3(0.0163914389, 0.0880133079, 0.8955952532)));
}
// 期 B（D1 债清偿）：输入去 gamma 从 `pow(2.2)` 近似换成**真 sRGB EOTF**（IEC 61966-2-1
// 分段，与 kFsOut / kFsStyle 的 `srgbEncode` 严格互逆）。
// 为什么现在才换：换域**之前**输出端已是真 sRGB、输入端却是 2.2 幂 ⇒ 两侧不同步。
// 实测差异（EOTF 方向 = display→线性）：display 0.2 处 真 sRGB 0.033105 vs 2.2 幂
// 0.028991，差约 **1 LSB**；**2.2 幂在暗部一律偏暗**，中高光几乎重合（1.0 处完全一致）。
// 用户实测"预览与成片仍有差距"⇒ 这是可消除的那一半；另一半是**架构性**的（预览拿不到
// RAW、走厂商 ISP，成片走我们的 CCM）⇒ 消不掉。
// ⚠ 会改变预览/静拍的**暗部亮度**；预览域比 `uDomainRatio` 是 EMA 在线标定，会自行收敛。
vec3 srgbDecode(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
    vec3 lo = c / 12.92;
    return mix(hi, lo, step(c, vec3(0.04045)));
}
void main() {
    vec3 rgb = clamp(texture(uTex, vUV0).rgb, 0.0, 1.0);
    vec3 lin = srgbDecode(rgb) * uWb;
    oColor = vec4(srgbToWork(lin), 1.0);
})";

// 静拍 pass1：采样 CPU 转换后的 RGB888 全分辨率纹理。
// 坐标系对齐：普通纹理 (0,0) 在左上、OES 在左下——vUV0.y 取反对齐 OES 习惯，
// 使静拍与预览共用同一套 uRot 旋转几何（静拍 rot = 界面方向 + 传感器旋转）。
const char* kFsStill = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform sampler2D uTex;
uniform vec3 uWb;
// 期 B：与 kFsOes 同一处域补齐（静拍链同样不进 CCM、同样被下游按 Rec.2020 消费）
// —— 两处必须同式，否则预览与静拍会互相不一致。
vec3 srgbToWork(vec3 c) {
    return vec3(dot(c, vec3(0.6274038959, 0.3292830384, 0.0433130657)),
                dot(c, vec3(0.0690972894, 0.9195403951, 0.0113623156)),
                dot(c, vec3(0.0163914389, 0.0880133079, 0.8955952532)));
}
// 期 B（D1）：与 kFsOes **同式** —— 静拍链与预览链的去 gamma 口径必须一致，否则两者
// 自己就先不一致了（真实 sRGB EOTF 而非 2.2 幂，理由见 kFsOes 的注释）。
vec3 srgbDecode(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
    vec3 lo = c / 12.92;
    return mix(hi, lo, step(c, vec3(0.04045)));
}
void main() {
    vec2 uv = vec2(vUV0.x, 1.0 - vUV0.y);
    vec3 rgb = clamp(texture(uTex, uv).rgb, 0.0, 1.0);
    vec3 lin = srgbDecode(rgb) * uWb;
    oColor = vec4(srgbToWork(lin), 1.0);
})";

// Pass1·填充测试：绕过 OES 采样（真机二分定位用）。
const char* kFsFillTest = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
void main() {
    float v = 0.5 + 0.25 * sin(vUV0.x * 40.0) + 0.25 * cos(vUV0.y * 40.0);
    oColor = vec4(vUV0, v, 1.0);
})";

// Pass1·RAW（M2-3）：u16 Bayer 整型纹理 → 黑阶扣除 → 白阶归一 → WB →
// bilinear demosaic → CCM → 场景线性。RAW 是传感器线性读数（无 gamma），
// 与 YUV pass1 的 pow(2.2) 展开不同；demosaic 相位判定与采样坐标一致自洽
// （垂直翻转映射后的纹理坐标奇偶仍与上传数据对齐）。
const char* kFsRaw = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp usampler2D uRaw;
uniform highp sampler2D uLsc;  // LSC 增益网格（RGB，源自 Camera2 4 平面）
uniform ivec2 uLscSize;
uniform ivec2 uSize;
uniform int uCfa;      // Camera2: 0=RGGB 1=GRBG 2=GBRG 3=BGGR
uniform vec4 uBlack;   // 按相位 (0,0)(1,0)(0,1)(1,1)
uniform float uWhite;
uniform vec3 uGain;    // asShotNeutral 倒数，G 归一
uniform mat3 uCcm;     // sensor→sRGB 行主序
int chanAt(ivec2 p) {
    int px = p.x & 1, py = p.y & 1;
    if (uCfa == 0) return py == 0 ? (px == 0 ? 0 : 1) : (px == 0 ? 1 : 2);
    if (uCfa == 1) return py == 0 ? (px == 0 ? 1 : 0) : (px == 0 ? 2 : 1);
    if (uCfa == 2) return py == 0 ? (px == 0 ? 1 : 2) : (px == 0 ? 0 : 1);
    return py == 0 ? (px == 0 ? 2 : 1) : (px == 0 ? 1 : 0);
}
// 镜头阴影增益（LSC，M3 P0）：网格双线性手写 4-tap（float 纹理过滤在 X300 上
// 不可依赖，见 makeTex 注释）；关闭/无数据时绑 1×1 全 1 纹理 → 恒等，零分支。
vec3 lscAt(ivec2 p) {
    vec2 g = ((vec2(p) + 0.5) / vec2(uSize)) * vec2(uLscSize) - 0.5;
    ivec2 b = ivec2(floor(g));
    vec2 t = g - vec2(b);
    ivec2 mx = uLscSize - 1;
    vec3 c00 = texelFetch(uLsc, clamp(b, ivec2(0), mx), 0).rgb;
    vec3 c10 = texelFetch(uLsc, clamp(b + ivec2(1, 0), ivec2(0), mx), 0).rgb;
    vec3 c01 = texelFetch(uLsc, clamp(b + ivec2(0, 1), ivec2(0), mx), 0).rgb;
    vec3 c11 = texelFetch(uLsc, clamp(b + ivec2(1, 1), ivec2(0), mx), 0).rgb;
    return mix(mix(c00, c10, t.x), mix(c01, c11, t.x), t.y);
}
float s(ivec2 p) {
    p = clamp(p, ivec2(0), uSize - 1);
    float v = float(texelFetch(uRaw, p, 0).r);
    float bl = uBlack[(p.y & 1) * 2 + (p.x & 1)];
    return max((v - bl) / max(uWhite - bl, 1.0), 0.0);
}
float dem(int ch, ivec2 p) {
    if (chanAt(p) == ch) return s(p);
    float h1 = chanAt(p + ivec2(-1, 0)) == ch ? 1.0 : 0.0;
    float h2 = chanAt(p + ivec2(1, 0)) == ch ? 1.0 : 0.0;
    float v1 = chanAt(p + ivec2(0, -1)) == ch ? 1.0 : 0.0;
    float v2 = chanAt(p + ivec2(0, 1)) == ch ? 1.0 : 0.0;
    float hs = h1 + h2, vs = v1 + v2;
    // R/B 位置的 G：上下左右 4 邻；单侧邻（水平/垂直 G 位）取 2 邻加权；对角 4 角
    if (hs > 0.5 && vs > 0.5)
        return (s(p + ivec2(-1, 0)) + s(p + ivec2(1, 0)) +
                s(p + ivec2(0, -1)) + s(p + ivec2(0, 1))) * 0.25;
    if (hs > 0.5) return (s(p + ivec2(-1, 0)) * h1 + s(p + ivec2(1, 0)) * h2) / hs;
    if (vs > 0.5) return (s(p + ivec2(0, -1)) * v1 + s(p + ivec2(0, 1)) * v2) / vs;
    return (s(p + ivec2(-1, -1)) + s(p + ivec2(1, -1)) +
            s(p + ivec2(-1, 1)) + s(p + ivec2(1, 1))) * 0.25;
}
void main() {
    // 2D 纹理坐标 (0,0) 在左上，vUV0 翻转对齐 OES 习惯（与 kFsStill 一致）
    vec2 tuv = vec2(vUV0.x, 1.0 - vUV0.y);
    ivec2 p = clamp(ivec2(tuv * vec2(uSize)), ivec2(0), uSize - 1);
    vec3 rgb = vec3(dem(0, p), dem(1, p), dem(2, p));
    // LSC 在 CCM 前乘（通道增益差异即角落色偏来源）；与 WB 同为乘性，次序无关
    rgb = uCcm * (rgb * uGain * lscAt(p));
    oColor = vec4(clamp(rgb, vec3(0.0), vec3(64.0)), 1.0);
})";

// ── RCD 质量档（M3-1）────────────────────────────────────────────────
// 算法出处：RCD（Ratio Corrected Demosaicing，Luis Sanz Rodriguez 开源算法，
// RawTherapee/darktable 采用）。自写 GLSL，仅采用其公开数学思想（GPL 隔离）：
// ① 跨色五点修正高通做方向判别（消常数/线性项，对局部色偏不敏感）；
// ② CFA 低通图的乘性比值迁移邻居信息（硬边缘不过冲，"Ratio Corrected" 得名处）；
// ③ 方向置信度连续混合 + 中心/邻域取更确定者（噪声区不翻转）；
// ④ 色度走"色差 + 方向加权"，并限幅到邻域同色实测范围（伪彩抑制）。
// 单趟三段：P1 方向场+低通图 → P2 G@RB → P3 色度填充+CCM；WB 在线性化时
// 前置（色差恒定假设要求白平衡域）。无迭代——参考实现本就是单趟流水。
//
// 性能版（v0.5.29，真机 gMs 1414ms 定向优化）：
// - CFA 相位判定改 uniform 查表（uCfaLut，4×2bit），替代逐次调用分支链；
// - P1 输出预归一化 (vConf, pConf, lpf)：方向置信度与 CFA 低通图一次算全图，
//   P2/P3 读纹理 1 tap，免每像素重复 9-tap 卷积（P2 采样 ~91→~24 taps）；
//   注：预归一化后 3×3 均值为"比值的平均"（非"和的比值"），方向场是启发式
//   统计，近似可接受；
// - P2 轴向梯度改两项式（|C(d)-C(-d)|+|C-C(2d)|，Hamilton-Adams 判别主体），
//   同坐标采样手动共享变量。
// 三个 shader 的公共段（CFA 相位/线性化采样）按 validate-shaders.sh 的
// 提取方式各写一份完整自包含字符串。

// P1：方向场 + CFA 低通图。输出 rgba16f = (vStat/(vStat+hStat), pStat/(pStat+qStat),
// LPF, 1)：V/H 与 P/Q 五点修正高通的相邻 3 行(列)平方和之比（置信度 ∈(0,1)）。
const char* kFsRcdDir = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp usampler2D uRaw;
uniform ivec2 uSize;
uniform int uCfaLut;   // CFA 2×2 相位→通道 LUT（4×2bit，C++ 侧由 cfa 编码）
uniform highp sampler2D uMos;  // P0 降噪后线性 mosaic（uMosaic>0.5 时启用）
uniform float uMosaic;
uniform vec4 uBlack;   // 按相位 (0,0)(1,0)(0,1)(1,1)
uniform float uWhite;
uniform vec3 uGain;    // asShotNeutral 倒数，G 归一（WB 前置）
const float EPS = 1e-9;
int chanAt(ivec2 p) {
    int k = ((p.y & 1) << 1) | (p.x & 1);
    return (uCfaLut >> (k << 1)) & 3;
}
float gainAt(int c) { return c == 0 ? uGain.x : (c == 1 ? uGain.y : uGain.z); }
float s(ivec2 p) {
    p = clamp(p, ivec2(0), uSize - 1);
    if (uMosaic > 0.5) {  // 降噪开：P0 已线性化+WB+压噪
        vec3 m = texelFetch(uMos, p, 0).rgb;
        int c = chanAt(p);
        return c == 0 ? m.r : (c == 1 ? m.g : m.b);
    }
    float v = float(texelFetch(uRaw, p, 0).r);
    float bl = uBlack[(p.y & 1) * 2 + (p.x & 1)];
    return max((v - bl) / max(uWhite - bl, 1.0), 0.0) * gainAt(chanAt(p));
}
// 五点修正高通：C(d-3)-C(d-1)-C(d+1)+C(d+3)-3[C(d-2)+C(d+2)]+6C(0)
float hpV(ivec2 p) {
    return s(p + ivec2(0, -3)) - s(p + ivec2(0, -1)) - s(p + ivec2(0, 1))
         + s(p + ivec2(0, 3)) - 3.0 * (s(p + ivec2(0, -2)) + s(p + ivec2(0, 2)))
         + 6.0 * s(p);
}
float hpH(ivec2 p) {
    return s(p + ivec2(-3, 0)) - s(p + ivec2(-1, 0)) - s(p + ivec2(1, 0))
         + s(p + ivec2(3, 0)) - 3.0 * (s(p + ivec2(-2, 0)) + s(p + ivec2(2, 0)))
         + 6.0 * s(p);
}
float hpP(ivec2 p) {
    return s(p + ivec2(-3, -3)) - s(p + ivec2(-1, -1)) - s(p + ivec2(1, 1))
         + s(p + ivec2(3, 3)) - 3.0 * (s(p + ivec2(-2, -2)) + s(p + ivec2(2, 2)))
         + 6.0 * s(p);
}
float hpQ(ivec2 p) {
    return s(p + ivec2(3, -3)) - s(p + ivec2(1, -1)) - s(p + ivec2(-1, 1))
         + s(p + ivec2(-3, 3)) - 3.0 * (s(p + ivec2(2, -2)) + s(p + ivec2(-2, 2)))
         + 6.0 * s(p);
}
// CFA 低通图：中心 1 + 四正邻 ½ + 四对角 ¼（跨色亮度代理，比值分母）
float lpf(ivec2 p) {
    return s(p)
         + 0.5 * (s(p + ivec2(1, 0)) + s(p + ivec2(-1, 0)) + s(p + ivec2(0, 1)) + s(p + ivec2(0, -1)))
         + 0.25 * (s(p + ivec2(1, 1)) + s(p + ivec2(-1, -1)) + s(p + ivec2(1, -1)) + s(p + ivec2(-1, 1)));
}
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float vs = 0.0, hs = 0.0, ps = 0.0, qs = 0.0;
    for (int i = -1; i <= 1; ++i) {
        float v = hpV(p + ivec2(0, i));
        float h = hpH(p + ivec2(i, 0));
        float pq = hpP(p + ivec2(i, i));
        float q = hpQ(p + ivec2(i, -i));
        vs += v * v;
        hs += h * h;
        ps += pq * pq;
        qs += q * q;
    }
    oColor = vec4(vs / (vs + hs + EPS), ps / (ps + qs + EPS), lpf(p), 1.0);
})";

// P2：G@RB——比值修正核心。读 P1 的低通图/方向置信度（各 1 tap）；
// R/B 位置乘性比值迁移轴向邻居，梯度反比交叉加权，V/H 置信度（中心 vs
// 3×3 邻域取更确定者）连续混合；G 位置直通。结果限幅到四邻实测范围。
const char* kFsRcdG = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp usampler2D uRaw;
uniform highp sampler2D uDir;  // P1 输出 (vConf, pConf, lpf, 1)
uniform ivec2 uSize;
uniform int uCfaLut;
uniform highp sampler2D uMos;
uniform float uMosaic;
uniform vec4 uBlack;
uniform float uWhite;
uniform vec3 uGain;
uniform float uGOrig;  // [56]：1 = 原版（不做四邻 G 范围限幅）；0 = 旧行为
const float EPS = 1e-6;
int chanAt(ivec2 p) {
    int k = ((p.y & 1) << 1) | (p.x & 1);
    return (uCfaLut >> (k << 1)) & 3;
}
float gainAt(int c) { return c == 0 ? uGain.x : (c == 1 ? uGain.y : uGain.z); }
float s(ivec2 p) {
    p = clamp(p, ivec2(0), uSize - 1);
    if (uMosaic > 0.5) {
        vec3 m = texelFetch(uMos, p, 0).rgb;
        int c = chanAt(p);
        return c == 0 ? m.r : (c == 1 ? m.g : m.b);
    }
    float v = float(texelFetch(uRaw, p, 0).r);
    float bl = uBlack[(p.y & 1) * 2 + (p.x & 1)];
    return max((v - bl) / max(uWhite - bl, 1.0), 0.0) * gainAt(chanAt(p));
}
float dirFetch(ivec2 p, int ch) {
    return texelFetch(uDir, clamp(p, ivec2(0), uSize - 1), 0)[ch];
}
// 方向置信度 = 中心与 3×3 邻域均值中更确定者（离 0.5 更远；噪声区防翻转）
float dirMix(ivec2 p, int ch) {
    float c = dirFetch(p, ch);
    float acc = 0.0;
    for (int dy = -1; dy <= 1; ++dy)
        for (int dx = -1; dx <= 1; ++dx) acc += dirFetch(p + ivec2(dx, dy), ch);
    float nb = acc / 9.0;
    return abs(c - 0.5) > abs(nb - 0.5) ? c : nb;
}
float lpfAt(ivec2 p) { return dirFetch(p, 2); }
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float g;
    if (chanAt(p) == 1) {
        g = s(p);
    } else {
        float l0 = lpfAt(p);
        float c0 = s(p);
        // 垂直 N/S：两项式梯度（Hamilton-Adams 判别主体）+ 比值估计（同坐标共享变量）
        float cN = s(p + ivec2(0, -1)), cS = s(p + ivec2(0, 1));
        float gn = abs(cN - cS) + abs(c0 - s(p + ivec2(0, -2))) + EPS;
        float gs = abs(cS - cN) + abs(c0 - s(p + ivec2(0, 2))) + EPS;
        float eN = cN * 2.0 * l0 / (l0 + lpfAt(p + ivec2(0, -1)) + EPS);
        float eS = cS * 2.0 * l0 / (l0 + lpfAt(p + ivec2(0, 1)) + EPS);
        float vEst = (gs * eN + gn * eS) / (gn + gs);
        // 水平 E/W
        float cE = s(p + ivec2(1, 0)), cW = s(p + ivec2(-1, 0));
        float ge = abs(cE - cW) + abs(c0 - s(p + ivec2(2, 0))) + EPS;
        float gw = abs(cW - cE) + abs(c0 - s(p + ivec2(-2, 0))) + EPS;
        float eE = cE * 2.0 * l0 / (l0 + lpfAt(p + ivec2(1, 0)) + EPS);
        float eW = cW * 2.0 * l0 / (l0 + lpfAt(p + ivec2(-1, 0)) + EPS);
        float hEst = (gw * eE + ge * eW) / (ge + gw);
        g = mix(vEst, hEst, dirMix(p, 0));
        // 伪彩抑制：限幅到四邻实测 G 范围（参考实现 STEP 3 无此限幅，只有
        // LIM 0..1 —— D4 收口钳制承担）。[56]=1 时不执行。
        if (uGOrig < 0.5) {
            float lo = min(min(cN, cS), min(cE, cW));
            float hi = max(max(cN, cS), max(cE, cW));
            g = clamp(g, lo, hi);
        }
    }
    oColor = vec4(0.0, g, 0.0, 1.0);
})";

// P3：色度填充 + CCM。R/B 位置补对侧色度、G 位置 R 用对角色差、B 用轴向
// 色差；方向判别读 P1 置信度（十字 5-tap 平滑）；全部色差限幅到邻域同色
// 实测范围（伪彩抑制）。输出 = WB 域 RGB → CCM → 场景线性（汇流 pass2/3）。
const char* kFsRcdChroma = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp usampler2D uRaw;
uniform highp sampler2D uDir;
uniform highp sampler2D uG;
uniform highp sampler2D uLsc;  // LSC 增益网格（RGB，与 kFsRaw 同源）
uniform ivec2 uLscSize;
uniform ivec2 uSize;
uniform int uCfaLut;
uniform highp sampler2D uMos;
uniform float uMosaic;
uniform vec4 uBlack;
uniform float uWhite;
uniform vec3 uGain;
// ⚠ 第 4a 期后本 pass 内**已不使用** uCcm（CCM 移至独立 pass kFsCcm）。
// 声明与 CPU 侧下发（passRcdChroma 里的 glUniformMatrix3fv）一并保留以维持零接口
// 改动：编译器消除后 location = -1，glUniform* 按 GL 规范静默 no-op，行为安全。
// 清理属独立小项（牵连 shader 声明 / location / 下发 / 函数签名 / 调用点共 5 处）。
uniform mat3 uCcm;     // sensor→sRGB 行主序
// M3-3（v0.10.117）：色差段回归 RawTherapee RCD 原版 —— 1 = 原版行为
//（梯度三项 + 无限幅），0 = 旧行为（梯度两项 + clamp 限幅）。
// 真机实测（2026-10-03）：杯子红白边界与窗户过曝区的大面积色块 = clamp 把
// **跨边界**邻居的色差锁定进允许区间 ⇒ 红色扩散进白瓷（白瓷深处 Cr +4.3，
// 边界被抬到 +20~26，偏红 16~22 级）。原版靠梯度反比加权天然边缘感知
//（跨边界邻居梯度大 ⇒ 权重小），本来不需要限幅。
uniform float uCdFix;
uniform float uGOrig;  // [56]：1 = G 位轴向填充走原版 STEP 4.3 同轴口径；0 = 旧行为
// ── 0.12.133（方案 B 块 1）：RAW 域饱和掩码沿 alpha 下传 ─────────────────────
// 源 = HL 的块级膨胀掩码 tex_hl_dil_（3×3 photosite 一块，块内任一通道饱和即置位）。
// 为什么复用 HL 的掩码而不是在 RCD 里重算：它就是 PC/Bento 语义里那张 RAW 域
// highlight mask（kFsHlMask 逐字对应 pc 的 hlVal >= 0.987），且已经做过 dilate；
// 在 RCD 里重算等于同一事实算两遍，还会引入第二套阈值。
// 消费者：kFsCcm 透传 → kFsPc3d 第二趟按 alpha 收手（高光是重建出来的色度，
// 没有噪声可降，让它参与色度平滑就会把高光色度摊进邻近像素 = 绿边/灰斑）。
uniform highp sampler2D uHlMask;
uniform int uMaskOn;
const float EPS = 1e-6;
// 块网格索引：p / 3，上界 = uSize/3（HL 建 mask 时用 mw = w/3）
float hlMaskAt(ivec2 p) {
    if (uMaskOn == 0) return 0.0;
    ivec2 mx = max(uSize / 3 - 1, ivec2(0));
    vec3 m = texelFetch(uHlMask, clamp(p / 3, ivec2(0), mx), 0).rgb;
    return max(m.r, max(m.g, m.b)) > 0.5 ? 1.0 : 0.0;
}
// ── 高光重建（M3 §3.2 阶段 4）——v0.10.20 移除 ──────────────────────
// 自研"中性外推"（2026-09-28 审计定性为 darktable 原生高光处理的简化替代），
// 原生 à trous 小波重建（kFsHr* 组，filmic 前）已完整接管：裁剪像素的通道
// 修复由小波插值完成（含 ratios 域色相修正），本处不再外推。
int chanAt(ivec2 p) {
    int k = ((p.y & 1) << 1) | (p.x & 1);
    return (uCfaLut >> (k << 1)) & 3;
}
float gainAt(int c) { return c == 0 ? uGain.x : (c == 1 ? uGain.y : uGain.z); }
float s(ivec2 p) {
    p = clamp(p, ivec2(0), uSize - 1);
    if (uMosaic > 0.5) {
        vec3 m = texelFetch(uMos, p, 0).rgb;
        int c = chanAt(p);
        return c == 0 ? m.r : (c == 1 ? m.g : m.b);
    }
    float v = float(texelFetch(uRaw, p, 0).r);
    float bl = uBlack[(p.y & 1) * 2 + (p.x & 1)];
    return max((v - bl) / max(uWhite - bl, 1.0), 0.0) * gainAt(chanAt(p));
}
// 镜头阴影增益（LSC，M3 P0）：与 kFsRaw 同款手写 4-tap 双线性
vec3 lscAt(ivec2 p) {
    vec2 g = ((vec2(p) + 0.5) / vec2(uSize)) * vec2(uLscSize) - 0.5;
    ivec2 b = ivec2(floor(g));
    vec2 t = g - vec2(b);
    ivec2 mx = uLscSize - 1;
    vec3 c00 = texelFetch(uLsc, clamp(b, ivec2(0), mx), 0).rgb;
    vec3 c10 = texelFetch(uLsc, clamp(b + ivec2(1, 0), ivec2(0), mx), 0).rgb;
    vec3 c01 = texelFetch(uLsc, clamp(b + ivec2(0, 1), ivec2(0), mx), 0).rgb;
    vec3 c11 = texelFetch(uLsc, clamp(b + ivec2(1, 1), ivec2(0), mx), 0).rgb;
    return mix(mix(c00, c10, t.x), mix(c01, c11, t.x), t.y);
}
float gAt(ivec2 p) {
    return texelFetch(uG, clamp(p, ivec2(0), uSize - 1), 0).g;
}
float dirFetch(ivec2 p, int ch) {
    return texelFetch(uDir, clamp(p, ivec2(0), uSize - 1), 0)[ch];
}
// 十字 5-tap 均值：方向场低通（伪彩本质是方向场高频翻转）
float dir5(ivec2 p, int ch) {
    float v = dirFetch(p, ch)
            + dirFetch(p + ivec2(0, -1), ch) + dirFetch(p + ivec2(0, 1), ch)
            + dirFetch(p + ivec2(1, 0), ch) + dirFetch(p + ivec2(-1, 0), ch);
    return v / 5.0;
}
// 对角色差填充：R/B 位置补对侧色度、G 位置补 R（Bayer 结构保证 4 对角
// 同为目标色实测位）。P/Q 两方向色差按对角梯度反比交叉加权，pq 方向混合。
// uCdFix=1（原版）：梯度用 RawTherapee Step 4.2 的三项（对角跨中心 + 同色隔 3 +
//   中心 G 与对角 G 隔 2），**不做限幅** —— 边缘感知由梯度反比加权承担。
// uCdFix=0（旧）：梯度两项 + clamp 到 4 对角色差范围（当时当"伪彩抑制"加的）。
float chromaDiag(ivec2 p) {
    float g0 = gAt(p);
    ivec2 nw = p + ivec2(-1, -1), se = p + ivec2(1, 1);
    ivec2 ne = p + ivec2(1, -1), sw = p + ivec2(-1, 1);
    float cnw = s(nw), cse = s(se), cne = s(ne), csw = s(sw);
    float gnw, gse, gne, gsw;
    if (uCdFix > 0.5) {
        gnw = abs(cnw - cse) + abs(cnw - s(p + ivec2(-3, -3)))
            + abs(g0 - gAt(p + ivec2(-2, -2))) + EPS;
        gse = abs(cse - cnw) + abs(cse - s(p + ivec2(3, 3)))
            + abs(g0 - gAt(p + ivec2(2, 2))) + EPS;
        gne = abs(cne - csw) + abs(cne - s(p + ivec2(3, -3)))
            + abs(g0 - gAt(p + ivec2(2, -2))) + EPS;
        gsw = abs(csw - cne) + abs(csw - s(p + ivec2(-3, 3)))
            + abs(g0 - gAt(p + ivec2(-2, 2))) + EPS;
    } else {
        gnw = abs(cnw - cse) + abs(g0 - gAt(p + ivec2(-3, -3))) + EPS;
        gse = abs(cse - cnw) + abs(g0 - gAt(p + ivec2(3, 3))) + EPS;
        gne = abs(cne - csw) + abs(g0 - gAt(p + ivec2(3, -3))) + EPS;
        gsw = abs(csw - cne) + abs(g0 - gAt(p + ivec2(-3, 3))) + EPS;
    }
    float pEst = (gnw * (cse - gAt(se)) + gse * (cnw - gAt(nw))) / (gnw + gse);
    float qEst = (gne * (csw - gAt(sw)) + gsw * (cne - gAt(ne))) / (gne + gsw);
    float d = mix(pEst, qEst, dir5(p, 1));
    if (uCdFix > 0.5) return d;  // 原版：无限幅
    float lo = min(min(cnw - gAt(nw), cse - gAt(se)), min(cne - gAt(ne), csw - gAt(sw)));
    float hi = max(max(cnw - gAt(nw), cse - gAt(se)), max(cne - gAt(ne), csw - gAt(sw)));
    return clamp(d, lo, hi);
}
// 轴向色差填充（G 位置专用）：Bayer 中 G 位的 R/B 都在四正邻（±1），
// 哪个轴是 R 由 G 的两种相位决定（水平邻 R 或垂直邻 R），按 chanAt 实测
// 分派；同轴 ±3 为第二对。近对反梯度加权为主、远对弱混合，限幅防过冲。
// uGOrig=1（原版 STEP 4.3 同轴口径）：只用 ±1 近对，梯度三项（同轴隔 2 的 G 差
//   + 近对色差 + 同色隔 2 位距差）反比交叉加权，**无 ±3 远对混合、无限幅**。
//   与参考实现的已知差异：原版还把交叉轴 ±1（对角段 STEP 4.2 已填充的对侧色）
//   纳入梯度/估计 —— 我们 P3 单 pass 并行无中间纹理读不到，信息面窄一档。
// uGOrig=0（旧）：近对 0.75 + ±3 均值 0.25 固定混合，clamp 到 ±1/±3 色差范围。
float axialFill(ivec2 p, int c) {
    ivec2 d = chanAt(p + ivec2(1, 0)) == c ? ivec2(1, 0) : ivec2(0, 1);
    float c1 = s(p + d), c2 = s(p - d);
    float d1 = c1 - gAt(p + d), d2 = c2 - gAt(p - d);
    if (uGOrig > 0.5) {
        float c3 = s(p + 3 * d), c4 = s(p - 3 * d);
        float gN = EPS + abs(gAt(p) - gAt(p - 2 * d)) + abs(c2 - c1) + abs(c2 - c4);
        float gS = EPS + abs(gAt(p) - gAt(p + 2 * d)) + abs(c1 - c2) + abs(c1 - c3);
        return (gN * d1 + gS * d2) / (gN + gS);
    }
    float c3 = s(p + 3 * d), c4 = s(p - 3 * d);
    float d3 = c3 - gAt(p + 3 * d), d4 = c4 - gAt(p - 3 * d);
    float g1 = abs(c1 - c2) + EPS;
    float g2 = abs(c3 - c4) + EPS;
    float near = (g2 * d1 + g1 * d2) / (g1 + g2);
    float far = 0.5 * (d3 + d4);
    float lo = min(min(d1, d2), min(d3, d4));
    float hi = max(max(d1, d2), max(d3, d4));
    return clamp(mix(far, near, 0.75), lo, hi);
}
void main() {
    // 2D 纹理坐标 (0,0) 在左上，vUV0 翻转对齐 OES 习惯（与 kFsRaw 一致）
    vec2 tuv = vec2(vUV0.x, 1.0 - vUV0.y);
    ivec2 p = clamp(ivec2(tuv * vec2(uSize)), ivec2(0), uSize - 1);
    int ch = chanAt(p);
    float g = gAt(p);
    float r, b;
    if (ch == 0) {
        r = s(p);
        b = g + chromaDiag(p);
    } else if (ch == 2) {
        b = s(p);
        r = g + chromaDiag(p);
    } else {
        r = g + axialFill(p, 0);
        b = g + axialFill(p, 2);
    }
    // LSC 在 CCM 前（网格低频，去马赛克前后差异 <1%）
    // ── 第 4a 期（S14）域拆分：本 pass 的输出到此为止 = **D3**（相机 RGB：
    // LSC 后、CCM 前）。CCM 已拆为独立 pass kFsCcm（读 tex_work_ D3 → 写
    // tex_scene_ D4），D3→D4 的收口 clamp 随 CCM 一起下移。
    // 此处**刻意不截断**（保持拆分前语义）：拆分前不存在 D3 clamp，加上它是引入
    // 一次原本没有的语义（负色差在 CCM 之前就被吃掉）。实测 D3 负分量占比
    // 0.0000%（包络口径亦然）⇒ 两种写法数值等价；但 D3 是第 4b 期色度滤镜的
    // 取数域，**提前截断会掩盖该期要观察的负色度过冲**，故原样写出。
    // 截断只在 D4 收口做（kFsCcm，上限 64 = 拆分前的 kFsRcdChroma 口径）。
    vec3 rgb = vec3(r, g, b) * lscAt(p);
    // alpha = RAW 域饱和掩码（0.12.133，见 uniform 区注释）：随 RGB 一起被旋转，
    // 下游 CCM 只透传、PC3D 读它决定"这些像素不参与色度平滑"。
    oColor = vec4(rgb, hlMaskAt(p));
})";

// ── P3 拆双 pass（v0.10.180，FilmParams[58] rcd_p3_split）──────────────────
// 机理（docs/research/11 号 §8）：单 pass 的 kFsRcdChroma 里 G 位轴向填充与
// R/B 位对角填充**并行执行**，读不到本 pass 内对角段刚填好的对侧色 —— 原版
// STEP 4.3 的 G 位色度估计是**四正邻双轴**（同轴 ±1 实测 + 交叉轴 ±1 的
// STEP 4.2 已填充色），信息面窄一档 ⇒ 原版就有的 B 缓 G 陡错位（品红峰
// +43.8 WB 域）被放大 2 倍（+85.6）。拆成 3a/3b 后 3b 从中间纹理读 3a 填充
// 色，恢复原版四邻估计（Python 直译对照：品红峰回落 +44）。
// 3a：对角段填充（= 原版 STEP 4.2）。R 位补 B、B 位补 R（chromaDiag 原样，
// uCdFix 语义不变）；G 位 R/B 占位（轴向填充在 3b）。**不乘 LSC**（中间域 =
// WB 域相机 RGB 无 LSC，LSC 挪到 3b 出口）；刻意不截断（D3 域口径，收口在
// kFsCcm）。输出 tex_rcd_a_（源几何 RGBA16F），仅 [58]=1 时执行。
// 几何：vs_plain_ + gl_FragCoord 直写（与 kFsRcdDir/kFsRcdG 同构），保证
// tex_rcd_a_ 与 tex_g_/tex_dir_ 同一源几何坐标语义，3b 的 texelFetch 直接对齐。
const char* kFsRcdChromaA = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp usampler2D uRaw;
uniform highp sampler2D uDir;
uniform highp sampler2D uG;
uniform ivec2 uSize;
uniform int uCfaLut;
uniform highp sampler2D uMos;
uniform float uMosaic;
uniform vec4 uBlack;
uniform float uWhite;
uniform vec3 uGain;
uniform float uCdFix;
uniform vec3 uSatClip;  // 满阱 = WB 增益 gain[c]：对角填充值的 per-channel 上界
const float EPS = 1e-6;
int chanAt(ivec2 p) {
    int k = ((p.y & 1) << 1) | (p.x & 1);
    return (uCfaLut >> (k << 1)) & 3;
}
float gainAt(int c) { return c == 0 ? uGain.x : (c == 1 ? uGain.y : uGain.z); }
float s(ivec2 p) {
    p = clamp(p, ivec2(0), uSize - 1);
    if (uMosaic > 0.5) {
        vec3 m = texelFetch(uMos, p, 0).rgb;
        int c = chanAt(p);
        return c == 0 ? m.r : (c == 1 ? m.g : m.b);
    }
    float v = float(texelFetch(uRaw, p, 0).r);
    float bl = uBlack[(p.y & 1) * 2 + (p.x & 1)];
    return max((v - bl) / max(uWhite - bl, 1.0), 0.0) * gainAt(chanAt(p));
}
float gAt(ivec2 p) {
    return texelFetch(uG, clamp(p, ivec2(0), uSize - 1), 0).g;
}
float dirFetch(ivec2 p, int ch) {
    return texelFetch(uDir, clamp(p, ivec2(0), uSize - 1), 0)[ch];
}
// 十字 5-tap 均值：方向场低通（与 kFsRcdChroma 逐字同款）
float dir5(ivec2 p, int ch) {
    float v = dirFetch(p, ch)
            + dirFetch(p + ivec2(0, -1), ch) + dirFetch(p + ivec2(0, 1), ch)
            + dirFetch(p + ivec2(1, 0), ch) + dirFetch(p + ivec2(-1, 0), ch);
    return v / 5.0;
}
// 对角色差填充：与 kFsRcdChroma 的 chromaDiag 逐字同款（uCdFix 语义不变）
float chromaDiag(ivec2 p) {
    float g0 = gAt(p);
    ivec2 nw = p + ivec2(-1, -1), se = p + ivec2(1, 1);
    ivec2 ne = p + ivec2(1, -1), sw = p + ivec2(-1, 1);
    float cnw = s(nw), cse = s(se), cne = s(ne), csw = s(sw);
    float gnw, gse, gne, gsw;
    if (uCdFix > 0.5) {
        gnw = abs(cnw - cse) + abs(cnw - s(p + ivec2(-3, -3)))
            + abs(g0 - gAt(p + ivec2(-2, -2))) + EPS;
        gse = abs(cse - cnw) + abs(cse - s(p + ivec2(3, 3)))
            + abs(g0 - gAt(p + ivec2(2, 2))) + EPS;
        gne = abs(cne - csw) + abs(cne - s(p + ivec2(3, -3)))
            + abs(g0 - gAt(p + ivec2(2, -2))) + EPS;
        gsw = abs(csw - cne) + abs(csw - s(p + ivec2(-3, 3)))
            + abs(g0 - gAt(p + ivec2(-2, 2))) + EPS;
    } else {
        gnw = abs(cnw - cse) + abs(g0 - gAt(p + ivec2(-3, -3))) + EPS;
        gse = abs(cse - cnw) + abs(g0 - gAt(p + ivec2(3, 3))) + EPS;
        gne = abs(cne - csw) + abs(g0 - gAt(p + ivec2(3, -3))) + EPS;
        gsw = abs(csw - cne) + abs(g0 - gAt(p + ivec2(-3, 3))) + EPS;
    }
    float pEst = (gnw * (cse - gAt(se)) + gse * (cnw - gAt(nw))) / (gnw + gse);
    float qEst = (gne * (csw - gAt(sw)) + gsw * (cne - gAt(ne))) / (gne + gsw);
    float d = mix(pEst, qEst, dir5(p, 1));
    if (uCdFix > 0.5) return d;  // 原版：无限幅
    float lo = min(min(cnw - gAt(nw), cse - gAt(se)), min(cne - gAt(ne), csw - gAt(sw)));
    float hi = max(max(cnw - gAt(nw), cse - gAt(se)), max(cne - gAt(ne), csw - gAt(sw)));
    return clamp(d, lo, hi);
}
void main() {
    ivec2 p = clamp(ivec2(gl_FragCoord.xy), ivec2(0), uSize - 1);
    int ch = chanAt(p);
    float g = gAt(p);
    float r, b;
    if (ch == 0) {
        r = s(p);
        b = clamp(g + chromaDiag(p), 0.0, uSatClip.z);
    } else if (ch == 2) {
        b = s(p);
        r = clamp(g + chromaDiag(p), 0.0, uSatClip.x);
    } else {
        r = g;  // G 位占位：轴向填充在 3b —— 本 pass 并行执行时对角段结果
        b = g;  // 还没落盘，占位值不会被任何下游读取（3b 的 G 位重算）
    }
    // 中间域 = WB 域相机 RGB **无 LSC**（LSC 挪到 3b 出口，与旧 P3 输出域一致）。
    // 填充值钳到 per-channel 满阱（uSatClip[c]）——原版 STEP4.2 输出的 LIM(0,1)
    // 在其归一化域（0..满阱）的域正确翻译；本体实测值不钳（原版同款）。
    // 真机依据（2026-10-07 灯盘 A/B）：[58] 双 pass 无钳时高光区品红 +31%
    //（70.3 vs 53.5，docs/research/11 号 §9）。
    oColor = vec4(r, g, b, 1.0);
})";

// 3b：G 位轴向填充（= 原版 STEP 4.3 逐行）。读 3a 的 tex_rcd_a_ 取交叉轴
// 已填充色 → 四正邻双轴估计；R/B 位直通 3a。输出乘 LSC → D3（写 tex_work_，
// 与旧 kFsRcdChroma 的输出域完全一致）。仅 [58]=1 时执行。
const char* kFsRcdChromaB = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uRcdA;  // 3a 输出：WB 域 RGB（对角段已填，无 LSC）
uniform highp sampler2D uDir;   // P1 方向场（vh=[0]，pq=[1]）
uniform highp sampler2D uG;     // P2 全图 G
uniform highp sampler2D uLsc;   // LSC 增益网格（RGB，与 kFsRaw 同源）
uniform ivec2 uLscSize;
uniform ivec2 uSize;
uniform int uCfaLut;
uniform vec3 uSatClip;  // 满阱 = WB 增益 gain[c]：轴向填充值的 per-channel 上界
// 0.12.133（方案 B 块 1）：RAW 饱和掩码（与 kFsRcdChroma 逐字同款）——
// 本 pass 的 p 是**源 mosaic 坐标**，所以 p/3 直接落在 tex_hl_dil_ 的块网格上；
// alpha 随 RGB 一起经 fbo 旋转进 D3/D4。
uniform highp sampler2D uHlMask;
uniform int uMaskOn;
const float EPS = 1e-6;
float hlMaskAt(ivec2 p) {
    if (uMaskOn == 0) return 0.0;
    ivec2 mx = max(uSize / 3 - 1, ivec2(0));
    vec3 m = texelFetch(uHlMask, clamp(p / 3, ivec2(0), mx), 0).rgb;
    return max(m.r, max(m.g, m.b)) > 0.5 ? 1.0 : 0.0;
}
int chanAt(ivec2 p) {
    int k = ((p.y & 1) << 1) | (p.x & 1);
    return (uCfaLut >> (k << 1)) & 3;
}
float at3(vec3 v, int c) { return c == 0 ? v.x : (c == 1 ? v.y : v.z); }
float gAt(ivec2 p) {
    return texelFetch(uG, clamp(p, ivec2(0), uSize - 1), 0).g;
}
float dirFetch(ivec2 p, int ch) {
    return texelFetch(uDir, clamp(p, ivec2(0), uSize - 1), 0)[ch];
}
// 3a 取值（源几何 texelFetch，与 gAt/dirFetch 同一 clamp 口径）
vec3 aAt(ivec2 p) {
    return texelFetch(uRcdA, clamp(p, ivec2(0), uSize - 1), 0).rgb;
}
// 镜头阴影增益（LSC）：与 kFsRaw/kFsRcdChroma 同款手写 4-tap 双线性
vec3 lscAt(ivec2 p) {
    vec2 g = ((vec2(p) + 0.5) / vec2(uSize)) * vec2(uLscSize) - 0.5;
    ivec2 b = ivec2(floor(g));
    vec2 t = g - vec2(b);
    ivec2 mx = uLscSize - 1;
    vec3 c00 = texelFetch(uLsc, clamp(b, ivec2(0), mx), 0).rgb;
    vec3 c10 = texelFetch(uLsc, clamp(b + ivec2(1, 0), ivec2(0), mx), 0).rgb;
    vec3 c01 = texelFetch(uLsc, clamp(b + ivec2(0, 1), ivec2(0), mx), 0).rgb;
    vec3 c11 = texelFetch(uLsc, clamp(b + ivec2(1, 1), ivec2(0), mx), 0).rgb;
    return mix(mix(c00, c10, t.x), mix(c01, c11, t.x), t.y);
}
// 原版 STEP 4.3 逐行（单通道 c ∈ {0(R), 2(B)}；RawTherapee rcd_demosaicing.c
// 173-206 行的公开数学，GLSL 自写）。四方向梯度（同轴隔 2 的 G 差 + 近对色差
// + 同色隔 2 位距差）+ 四正邻色差 Est + V/H 双轴梯度反比交叉加权。
// ⚠ 拆双 pass 的根因：单 pass 版并行读不到本 pass 内对角段填充 ⇒ 轴向估计只剩
// 同轴 ±1 实测信息，而原版把交叉轴 ±1 的 STEP 4.2 已填充色也纳入四邻估计 ——
// 缺这一半 ⇒ B 缓 G 陡错位放大 2×（品红峰 +43.8 → +85.6，docs/research/11 §8）。
// 本函数对**全部四正邻**读 3a 的 c 通道：Bayer G 位的四正邻 = 2R+2B 相位，
// 同色轴 = 实测值（3a 直通）、交叉轴 = 3a 对角填充，与原版 rgb[·][c] 的取值
// 逐一同构（G(·) 读 P2 的 tex_g_，±2 保持 G 相位）。
float axialC(ivec2 p, int c, float disc) {
    float g0 = gAt(p);
    ivec2 pn = p + ivec2(0, -1), ps = p + ivec2(0, 1);
    ivec2 pw = p + ivec2(-1, 0), pe = p + ivec2(1, 0);
    float cn = aAt(pn)[c], cs = aAt(ps)[c];
    float cw = aAt(pw)[c], ce = aAt(pe)[c];
    // Cardinal gradients：N_Grad = eps + |G(p)−G(p−2dy)| + |C(p−dy)−C(p+dy)|
    //                     + |C(p−dy)−C(p−3dy)|（S/W/E 同构换轴）
    float ng = EPS + abs(g0 - gAt(p + ivec2(0, -2)))
             + abs(cn - cs) + abs(cn - aAt(p + ivec2(0, -3))[c]);
    float sg = EPS + abs(g0 - gAt(p + ivec2(0, 2)))
             + abs(cs - cn) + abs(cs - aAt(p + ivec2(0, 3))[c]);
    float wg = EPS + abs(g0 - gAt(p + ivec2(-2, 0)))
             + abs(cw - ce) + abs(cw - aAt(p + ivec2(-3, 0))[c]);
    float eg = EPS + abs(g0 - gAt(p + ivec2(2, 0)))
             + abs(ce - cw) + abs(ce - aAt(p + ivec2(3, 0))[c]);
    // Cardinal colour differences（邻居 G = P2 的 G@RB 填充，tex_g_ 已有）
    float nEst = cn - gAt(pn);
    float sEst = cs - gAt(ps);
    float wEst = cw - gAt(pw);
    float eEst = ce - gAt(pe);
    // V/H 双轴估计（N·S / E·W 梯度反比交叉加权）
    float vEst = (ng * sEst + sg * nEst) / (ng + sg);
    float hEst = (eg * wEst + wg * eEst) / (eg + wg);
    // R@G / B@G 填充值钳到 per-channel 满阱 —— 原版 STEP4.3 输出 LIM(0,1) 在其
    // 归一化域（0..满阱）的域正确翻译（⚠ 不是 [46] 当时去掉的"四邻范围 clamp"，
    // 那是跨边界锁定；这里是绝对界）。真机依据（2026-10-07 灯盘 A/B）：无钳时
    // 高光区品红 +31%（70.3 vs 53.5，docs/research/11 号 §9）。
    return clamp(g0 + (1.0 - disc) * vEst + disc * hEst, 0.0, at3(uSatClip, c));
}
void main() {
    // 2D 纹理坐标 (0,0) 在左上，vUV0 翻转对齐 OES 习惯（与 kFsRcdChroma 逐字同款）
    vec2 tuv = vec2(vUV0.x, 1.0 - vUV0.y);
    ivec2 p = clamp(ivec2(tuv * vec2(uSize)), ivec2(0), uSize - 1);
    int ch = chanAt(p);
    vec3 rgb;
    if (ch == 1) {
        // 细化 VH 判别（与原版 4.2 的 PQ 细化同款）：中心 vh 与 0.25×四对角
        // 均值，取离 0.5 更远者（更确定）。每像素算一次，c 两轮共用。
        float vhc = dirFetch(p, 0);
        float vhn = 0.25 * (dirFetch(p + ivec2(-1, -1), 0)
                          + dirFetch(p + ivec2(1, -1), 0)
                          + dirFetch(p + ivec2(-1, 1), 0)
                          + dirFetch(p + ivec2(1, 1), 0));
        float disc = abs(0.5 - vhc) < abs(0.5 - vhn) ? vhn : vhc;
        rgb = vec3(axialC(p, 0, disc), gAt(p), axialC(p, 2, disc));
    } else {
        // R/B 位：直通 3a（实测 + 对角填充 = 原版 STEP 4.2 的结果）
        rgb = aAt(p);
    }
    // LSC 在 CCM 前（与旧 P3 输出域完全一致：D3 = 相机 RGB，CCM 已拆独立 pass）
    oColor = vec4(rgb * lscAt(p), hlMaskAt(p));
})";

// ── 第 4a 期（S14）：D3 → D4 独立 CCM pass ─────────────────────────────────
// 动机：原 kFsRcdChroma 在**一个 pass 内**依次跨 D2→D3（LSC）→D4（CCM）三域，
// 使色度降噪无法在 D3（相机 RGB：LSC 后、CCM 前）取数（第 4b 期的前提）。
// 本 pass 是整条链路里唯一的 D3→D4 收口：读 tex_work_（D3，RCD 段输出）→
// 乘 CCM → 写 tex_scene_（D4，场景线性）。tex_work_ 在 RCD→CCM 区间本是空闲的
// （它只在 passFilm 被写，:passFilm），借用它承载 D3 **零新增显存**。
// 几何与 passChromaDenoise 同构：kVertRot + uST 单位阵，frag 用 gl_FragCoord
// 逐像素定位（输入输出同尺寸同布局，纯逐像素映射）。
// 代价：D3 多一次 RGBA16F（half）往返；量化误差实测见第 4a 期报告。
// ── 0.12.112：高光区**不降噪**（按 PC 的做法：高光退出平均）─────────────────
// 用户判定（2026-10-28，多轮真机）：「高光太亮了，不需要降噪，里面根本没多少细节」。
// 物理上成立：σ² = A·x + B 在亮部虽然变大，但**信号更大** —— 灰块区 x≈0.36 时
//   σ≈0.0017、SNR≈210；降噪能改善的信噪比 < 0.5%，而代价是把反光里仅剩的
//   色度/纹理起伏一起抹平（实测 PC3D 让该区色度局部 σ 从 0.0551 掉到 0.0478，
//   视觉上"颗粒感"消失 ⇒ 一块光滑平板）。
// 与 PC 的对应：PC 用 Bento mask 把高光区从多帧融合权重里摘掉
//   （`oWeight = existingWeight * (1 - maskWeight)`），语义就是"高光不参与平均"。
//   我们没有 Bento，但判据可以自洽：**用 SNR 判"还有没有降噪收益"**（只有一个物理
//   量，不引入新阈值表）—— SNR 高的像素直接取原图。
// 位置：降噪链**出口**（film 入口之前）。放这里的原因：
//   · 降噪各段（P0/P4/PC3D/D-1）在各自域里都用噪声模型，单段判断不出"最终是不是高光"；
//   · 出口处 dn 结果与原图同域同几何，一次采样即可，且不触碰任何照抄 PC 的 pass
//     （PC3D/P4/D-1 一字节不改 —— 项目铁律：不预删原版实现）。
const char* kFsDnBlend = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uDn;    // 降噪链输出（D4 场景线性）
uniform highp sampler2D uOrig;  // 同一域的原图（D4）
uniform vec3 uNoiseA;           // σ² = A·x + B（D4 近似：沿用 P4 的 WB 后系数）
uniform vec3 uNoiseB;
uniform float uSnrLo;           // SNR ≤ Lo ⇒ 全降噪
uniform float uSnrHi;           // SNR ≥ Hi ⇒ 原图
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 d = texelFetch(uDn, p, 0).rgb;
    vec3 o = texelFetch(uOrig, p, 0).rgb;
    // 逐通道 SNR 取最小（任一通道"亮到不需要降噪"就足够）
    float snr = 1e9;
    for (int c = 0; c < 3; ++c) {
        float x = max(o[c], 0.0);
        float av = c == 0 ? uNoiseA.x : (c == 1 ? uNoiseA.y : uNoiseA.z);
        float bv = c == 0 ? uNoiseB.x : (c == 1 ? uNoiseB.y : uNoiseB.z);
        float sg = sqrt(max(av * x + bv, 1e-12));
        snr = min(snr, x / sg);
    }
    // ── 0.12.115：判据 = **G 是否异常地成为最大通道**（饱和伪彩的指纹）──────
    // 三轮真机实证：
    //   0.12.112（整像素取原图）    → 灰块消失、绿斑出现；
    //   0.12.113（只跳过亮度）      → 灰块回来、绿斑仍在；
    //   0.12.114（按"相对满阱"分）  → 绿斑解决、**灰块还在**。
    // 114 的教训：灰块在 D4 的绝对值并不低（离线的 ccm_d4 是 0.36，真机带 LSC 后
    // 已接近满阱估计），`sat > 0.75` 把它和饱和云一起判进了降噪。
    // 但饱和伪彩有比"亮度"更硬的指纹：**B/R 先撞满阱被钳住、G 继续涨**
    //   ⇒ G 反而成为最大通道（画面上一片偏绿）。
    //   绿斑：G > max(R,B)（gEx > 0）
    //   灰块：R、B 都高于 G（gEx < 0）—— 粉/品红倾向，是真实色度
    // 所以判据换成 gEx = (G − max(R,B)) / G：
    //   gEx < 0      → 用原图（保真实色度，灰块消失）
    //   gEx > 0.08   → 用降噪（压饱和伪彩，绿斑消失）
    // 与 114 的饱和判据是"或"关系：真饱和到三者都钳满时（G 也可能被钳）仍要降噪。
    // 阈值来自实测分布，不是画面参数：
    //   灰块区（离线 ccm_d4 实测）G/R-1 = (0.3654/0.3575-1) ≈ **0.022**
    //   绿斑（成片 a* ≈ −6~−10）对应 G 高出 0.05~0.12
    // ⇒ 0.05 以下一律视为真实色度（灰块完全走原图），0.15 以上一律视为伪彩。
    // （0.12.115 首版用 0→0.08，灰块 gEx=0.022 会拿到 18% 降噪权重 ⇒ 灰块会剩三成，
    //   所以这一版把区间抬到 0.05→0.15。）
    float gEx = (o.g - max(o.r, o.b)) / max(o.g, 1e-3);
    float kG = smoothstep(0.05, 0.15, gEx);
    // 饱和兜底：三者都接近单通道上限（D4 满阱 ≈ 1.1）时也走降噪
    float sat = max(max(o.r, o.g), o.b) / 1.1;
    float kS = smoothstep(1.05, 1.25, sat);
    float k = max(kG, kS);
    oColor = vec4(mix(o, d, k), 1.0);
})";

// ── 0.12.122：collar flow —— 裁剪区位移场修正（照抄 BentoRegionAdmission）──────
// 为什么必须有这一层（真机 2026-10-28 逐版实证）：
//   sat-fix 用 DFT 的位移场采样短帧。该场是 **tile 级**（kMfnrTile=64 @1/2 域 ⇒
//   128×128 mosaic 一块一个位移值），而 highlight 内部的参考帧是平坦 plateau ——
//   SAD 在那里没有几何，算出来的位移**不可信**；边界处再叠加 1~2px 误差，就会把
//   邻区颜色搬进来（B 偏低 ⇒ 黄绿；或中性 ⇒ 灰带）。
// Bento 的解法（`BentoRegionAdmission.kt`）：highlight 区的流**不从内部测**，
//   而是从它**周围的 collar**（可观测、前后向一致的 tile）插值：
//     weight = conf / (dx² + dy²)；collar 太少时退全图 supported 中值。
//
// ── 0.12.161：0.12.122 的移植**从未生效**（用户 2026-10-29 要求审查 Bento 实现）──
// 排查记录（源码级，可复算）：
//   ① PC 的 Bento 用的是**两张**纹理 —— 位移场（alignment.xy）与 support
//      （`renderBentoCollarSupport` 的独立输出，confidence ∈ [0,1]）。support 的
//      硬条件是 `clipped == 0`：tile 内只要有一个采样点落在高光掩码上，
//      confidence 恒 0 ⇒ 该 tile 既不选作 collar、也不会被"本身可信"提前返回。
//   ② 我们的打包（capture 里 `pack` 初始化 `1.f`，循环只写 [0]/[1]/[2]）让位移场
//      的 **w 恒为 1.0**（原注释写的就是"z=权重恒 1"）。
//   ③ 而 0.12.122 的 kFsCollarFlow 第一行判据是 `c.w >= uMinSupp` ⇒ 恒真 ⇒
//      `oColor = c; return;` ⇒ **每个 tile 都原样返回，整个 pass 是 no-op**。
//      同样因为 w 恒 1，collar 收集里的 `o.w < uMinSupp` 也从不命中 ——
//      即使去掉入口，highlight tile 也会被当成"可信邻居"，插值结果仍是错的。
//   ④ 附带三处偏差：clamp 上界写成 `uTiles-1` 却传的是尺寸（越界 1 格）；
//      `cnt` 数的是采样次数（clamp 后同一 tile 被重复计数），MIN_COLLAR_TILES
//      形同虚设；缺 PC 的 8-连通分组与全图中值兜底。
// 现结构 = 与 PC 同构：
//   · GPU 只做 support（本 shader，读参考帧 RAW 判裁剪，4×4 采样同 PC）；
//   · CPU 做分组 + collar + 全图中值（tiles 只有数百个，成本可忽略，且可日志）。
// **未实现**：PC support 里的 agreement 项（需要反向金字塔对齐）⇒ 本 support
// 是二值，不是 PC 的质量加权 confidence。这一点写进日志 collarSupp 便于区分。
const char* kFsCollarSupport = R"(#version 300 es
precision highp float;
precision highp int;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uOff;      // 原位移场（tiles 网格，RG = 位移）
uniform highp usampler2D uRef;     // 参考帧 RAW（R16UI）
uniform ivec2 uSize;               // RAW 尺寸（mosaic）
uniform ivec2 uTiles;              // tile 网格
uniform int uTileM;                // tile 边长（mosaic px）
uniform float uWhite;              // 白点（与 kFsMfnrSatFix 同一判据）
void main() {
    ivec2 t = ivec2(gl_FragCoord.xy);
    vec2 flow = texelFetch(uOff, t, 0).xy;
    float valid = 0.0;
    float clipped = 0.0;
    // 采样位置与 PC 逐字同构：tile 内 4×4 个等距点
    for (int y = 0; y < 4; ++y) {
        for (int x = 0; x < 4; ++x) {
            ivec2 p = t * uTileM +
                      ivec2((2 * x + 1) * uTileM / 8, (2 * y + 1) * uTileM / 8);
            if (p.x < 0 || p.y < 0 || p.x >= uSize.x || p.y >= uSize.y) continue;
            valid += 1.0;
            float v = float(texelFetch(uRef, p, 0).r);
            if (v >= max(uWhite, 1.0) * 0.98) clipped += 1.0;
        }
    }
    // PC 的 isSupported 硬条件：采样不足或出现裁剪像素 ⇒ support = 0
    float supp = (valid >= 4.0 && clipped == 0.0) ? 1.0 : 0.0;
    oColor = vec4(flow, valid / 16.0, supp);
})";

const char* kFsCcm = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;  // D3：相机 RGB（LSC 后、CCM 前）
uniform ivec2 uSize;
uniform mat3 uCcm;     // sensor→sRGB 行主序
void main() {
    ivec2 p = clamp(ivec2(gl_FragCoord.xy), ivec2(0), uSize - 1);
    vec4 s = texelFetch(uTex, p, 0);
    vec3 rgb = uCcm * s.rgb;
    // D3→D4 域收口（审计 S14）：负值与超白在 CCM 之后一次截断。上限 64 是拆分前
    // kFsRcdChroma 的 D4 域口径，原样搬来（不重标定、不调参）。
    // alpha = RCD 下传的 RAW 饱和掩码（0.12.133，方案 B 块 1）：**只透传，不参与
    // 任何计算**。它必须活到 PC3D —— D4 的高光色度是重建值、没有噪声可降。
    oColor = vec4(clamp(rgb, vec3(0.0), vec3(64.0)), s.a);
})";

// ── HDR 曝光融合 P2（0.12.77，docs/engine-hdr-exposure-fusion-design.md §4.2）──
// Mertens 曝光融合的 P2 形态：zero（0EV 堆叠 + 前链的 D4）与侧帧（+2.2EV 前链
// 的 D4）两张，逐像素加权融合写回 tex_scene_（passCcm 之后、P4/PC3D 之前）。
// 权重公式**照抄 PC**（GlesYuvStacker.kt:3451 MERTENS_WEIGHT_FRAGMENT_SHADER）：
//   contrast = |4 邻域拉普拉斯|（luma 权重 0.299/0.587/0.114，reflect101 边界）
//   saturation = RGB 相对均值的标准差
//   wellExposedness = ∏c exp(−(c−0.5)²/0.08)
//   admissibility = highlightValidity：任一通道进 0.960~0.995 平滑带 ⇒ 该曝光
//     的色度不可信，权重归零（高光交给更短曝光）
// 归一照抄 PC 的三级回退（MERTENS_NORMALIZE）：primary（质量权重）→ fallback
// （wellExposed，平坦/无色区）→ admissibility → 全拒时**保 zero**（不与裁切
// 侧帧均分）。wc/ws/we = PC DEFAULT_MERTENS_* = 1.0（uWc/uWs/uWe 留 A/B 口）。
// deghost（census/log/linear 残差门 + referenceStructure）与 pyramid 融合是 P3
// （设计 §6）；PC 的 uExposureScale 只进 deghost，P2 无消费。
// ponytail: P2 临时形态 —— 逐像素直接加权在高反差边界可能出接缝（设计 §4.2
// 已知并允许），P3 的 Laplacian/Gaussian pyramid（PC PYR_DOWN/LAPLACIAN/
// RECONSTRUCT 全 texelFetch，天然兼容我们的 RGBA16F 无线性过滤约束）解决。
// 域 = D4 场景线性（Rec.2020 线性，与 film 输入同域 —— 设计 §4.2 定案）。
// 0.96/0.995 阈值照抄 PC（其语义来自显示域）；线性域下的阈值适配由真机 J 判据
// 评估后再定，不在 P2 预调（铁律：先原版跑通、实测数据说话）。
// ── 0.12.165：D4 层大半径 USM（收窄暗部软边，不改 tone curve）──────────────
// 真机 2026-10-29：阳台地砖上护栏投影的亮缝，RAW 域本身就是 34px 的软坡
//（0.035→0.150，对比 4.3×），DNG(AHD) 把它压成 14px/2.6× 的硬边。
// 我们的 RCD/AMaZE 只到 26px，于是成片看着"糊成一片"（用户："渗水边"）。
// 关键：用户实测 **切 sigmoid 引擎也照样有** ⇒ 不是 tone curve 的问题，
// 是 D4（去马赛克后）本身就比 AHD 软。所以在 tone 之前补一次大半径 USM。
// 离线实测（同 ROI，r=8 的 1/8 box 低频、k=1.0）：亮缝 25px→18px、
// 对比 5.80×→7.69×、背景不变（无暗环）。r=6 会把背景压掉 65%（暗环），不用。
// 相对增益（÷l）与 sharpenLuma 同式 ⇒ 感知均匀；clamp 防极暗处爆。
const char* kFsUsmDown = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform ivec2 uSize;
void main() {
    ivec2 o = ivec2(gl_FragCoord.xy) * 8;
    vec3 s = vec3(0.0);
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 8; ++x) {
            ivec2 p = min(o + ivec2(x, y), uSize - 1);
            s += texelFetch(uTex, p, 0).rgb;
        }
    oColor = vec4(s * 0.015625, 1.0);
})";

const char* kFsUsm = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform highp sampler2D uLow;
uniform ivec2 uSize;
uniform ivec2 uLowSize;
uniform float uK;
const vec3 LW = vec3(0.2126, 0.7152, 0.0722);
void main() {
    ivec2 p = clamp(ivec2(gl_FragCoord.xy), ivec2(0), uSize - 1);
    vec4 c = texelFetch(uTex, p, 0);
    vec2 uv = (vec2(p) + 0.5) / vec2(uSize);
    vec2 t = uv * vec2(uLowSize) - 0.5;
    vec2 i0 = floor(t);
    vec2 fr = t - i0;
    ivec2 b = ivec2(i0);
    ivec2 mx = uLowSize - 1;
    vec3 s00 = texelFetch(uLow, clamp(b, ivec2(0), mx), 0).rgb;
    vec3 s10 = texelFetch(uLow, clamp(b + ivec2(1, 0), ivec2(0), mx), 0).rgb;
    vec3 s01 = texelFetch(uLow, clamp(b + ivec2(0, 1), ivec2(0), mx), 0).rgb;
    vec3 s11 = texelFetch(uLow, clamp(b + ivec2(1, 1), ivec2(0), mx), 0).rgb;
    vec3 low = mix(mix(s00, s10, fr.x), mix(s01, s11, fr.x), fr.y);
    float l = dot(c.rgb, LW);
    float lb = dot(low, LW);
    float g = uK * (l - lb) / max(l, 0.02);
    float k = 1.0 + clamp(g, -0.35, 0.55);
    oColor = vec4(clamp(c.rgb * k, vec3(0.0), vec3(64.0)), c.a);
})";

const char* kFsHdrMertens = R"(#version 300 es
precision highp float;
precision highp int;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uZero;  // zero 帧 D4（tex_hdr_zero_，读）
uniform highp sampler2D uSide;  // 侧帧 D4（tex_hdr_side_，+2.2EV）
uniform ivec2 uSize;            // 成片几何（dst_w, dst_h）
uniform float uWc;              // contrast 指数（PC DEFAULT 1.0）
uniform float uWs;              // saturation 指数（1.0）
uniform float uWe;              // wellExposed 指数（1.0）
uniform float uSideScale;       // 侧帧/zero 曝光乘积比（PC uExposureScale 语义）
// 0.12.79：升级为 PC MERTENS_WEIGHT_FRAGMENT_SHADER 完整版（GlesYuvStacker.kt:3451）——
// 0.12.78 真机（201852）高光斑块：侧帧肩部（0.5~0.96）脏数据（ISO14336 无 HR 无降噪
// + RCD 饱和边缘外推）被 highlightValidity 放行、权重独大；手持微动在高 contrast
// 高光边缘无 deghost 检测。修复 = 侧帧链补 HR/P0（C++ 侧）+ 本 shader 照抄 PC 的
// deghostValidity / censusMismatch / referenceStructure / deghostScoreAt 全套。
// pyramid 融合仍留 P3（接缝是另一症状，本轮验收后再定）。
int reflect101(int p, int size) {
    if (size <= 1) return 0;
    int period = size * 2 - 2;
    int m = p;
    if (m < 0) m = -m;
    m = m - (m / period) * period;
    return m >= size ? period - m : m;
}
float luma(vec3 rgb) { return dot(rgb, vec3(0.299, 0.587, 0.114)); }
float max3(vec3 v) { return max(max(v.r, v.g), v.b); }
float grayAt(sampler2D t, ivec2 coord) {
    ivec2 c = ivec2(reflect101(coord.x, uSize.x), reflect101(coord.y, uSize.y));
    return luma(texelFetch(t, c, 0).rgb);
}
float highlightValidity(vec3 rgb) {
    return 1.0 - smoothstep(0.960, 0.995, max3(rgb));
}
// ── deghost（照抄 PC）─────────────────────────────────────────────────
float deghostValidity(vec3 rgb) {
    float y = luma(rgb);
    float blackGate = smoothstep(0.055, 0.145, y);
    float whiteGate = 1.0 - smoothstep(0.865, 0.975, max3(rgb));
    return clamp(blackGate * whiteGate, 0.0, 1.0);
}
float referenceStructure(ivec2 coord) {
    float c = luma(texelFetch(uZero, coord, 0).rgb);
    float l = luma(texelFetch(uZero, ivec2(reflect101(coord.x - 1, uSize.x), coord.y), 0).rgb);
    float r = luma(texelFetch(uZero, ivec2(reflect101(coord.x + 1, uSize.x), coord.y), 0).rgb);
    float u = luma(texelFetch(uZero, ivec2(coord.x, reflect101(coord.y - 1, uSize.y)), 0).rgb);
    float d = luma(texelFetch(uZero, ivec2(coord.x, reflect101(coord.y + 1, uSize.y)), 0).rgb);
    float gradient = abs(r - l) + abs(d - u);
    float laplacian = abs(l + r + u + d - 4.0 * c);
    return smoothstep(0.030, 0.095, gradient + 0.5 * laplacian);
}
float censusMismatch(ivec2 coord, float refCenter, float sideCenter) {
    float mismatch = 0.0;
    float count = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            if (x == 0 && y == 0) continue;
            ivec2 p = ivec2(reflect101(coord.x + x, uSize.x), reflect101(coord.y + y, uSize.y));
            float refNeighbor = luma(texelFetch(uZero, p, 0).rgb);
            float sideNeighbor = luma(texelFetch(uSide, p, 0).rgb);
            float refRank = refNeighbor > refCenter ? 1.0 : 0.0;
            float sideRank = sideNeighbor > sideCenter ? 1.0 : 0.0;
            mismatch += abs(refRank - sideRank);
            count += 1.0;
        }
    }
    return mismatch / max(count, 1.0);
}
float deghostScoreAt(ivec2 coord) {
    int x = reflect101(coord.x, uSize.x);
    int y = reflect101(coord.y, uSize.y);
    vec3 side = texelFetch(uSide, ivec2(x, y), 0).rgb;
    vec3 reference = texelFetch(uZero, ivec2(x, y), 0).rgb;
    float scale = clamp(uSideScale, 0.03125, 32.0);
    float sideLuma = luma(side);
    float referenceLuma = luma(reference);
    float comparable = deghostValidity(side) * deghostValidity(reference) *
                       referenceStructure(ivec2(x, y));
    if (comparable <= 0.001) return 0.0;
    float exposureGap = abs(log2(scale));
    float logResidual = abs(log(max(sideLuma, 1e-4)) - log(max(referenceLuma, 1e-4)) - log(scale));
    float logMotion = smoothstep(0.28 + 0.04 * min(exposureGap, 3.0), 0.72, logResidual);
    float expectedSide = referenceLuma * scale;
    float linearResidual = abs(sideLuma - expectedSide) / max(max(sideLuma, expectedSide), 1e-4);
    float linearMotion = smoothstep(0.12, 0.42, linearResidual);
    float rankMotion = smoothstep(0.32, 0.68, censusMismatch(ivec2(x, y), referenceLuma, sideLuma));
    float score = max(max(linearMotion, logMotion), rankMotion);
    return comparable * score;
}
float computeDeghostAlpha(ivec2 coord) {
    float score = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            score = max(score, deghostScoreAt(coord + ivec2(x, y)));
        }
    }
    float reject = smoothstep(0.36, 0.76, score);
    return mix(1.0, 0.0, reject);
}
// 三元组权重（PC 的 vec4 打包拆成 vec3：primary / fallback / admissibility）。
// zero 恒 uUseDeghostMask=0（alpha=1，参考帧不被拒绝——PC 同语义）；side 过 deghost。
vec3 weightOf(sampler2D t, ivec2 coord, float alpha) {
    vec3 rgb = texelFetch(t, coord, 0).rgb;
    float center = luma(rgb);
    float contrast = abs(
        grayAt(t, coord + ivec2(-1, 0)) + grayAt(t, coord + ivec2(1, 0)) +
        grayAt(t, coord + ivec2(0, -1)) + grayAt(t, coord + ivec2(0, 1)) -
        center * 4.0);
    float mean = (rgb.r + rgb.g + rgb.b) / 3.0;
    vec3 dev = rgb - vec3(mean);
    float saturation = sqrt(dot(dev, dev));
    vec3 d = rgb - vec3(0.5);
    vec3 expo = exp(-(d * d) / 0.08);
    float well = expo.r * expo.g * expo.b;
    float adm = highlightValidity(rgb) * alpha;
    return vec3(
        pow(max(contrast, 0.0), uWc) * pow(max(saturation, 0.0), uWs) *
            pow(max(well, 0.0), uWe) * adm,
        well * adm,
        adm);
}
void main() {
    ivec2 coord = ivec2(gl_FragCoord.xy);
    vec3 w0 = weightOf(uZero, coord, 1.0);
    vec3 w1 = weightOf(uSide, coord, computeDeghostAlpha(coord));
    float ps = w0.x + w1.x;
    float fs = w0.y + w1.y;
    float as = w0.z + w1.z;
    float wz, ws;
    if (ps > 1e-6) {
        wz = w0.x / ps; ws = w1.x / ps;
    } else if (fs > 1e-6) {
        wz = w0.y / fs; ws = w1.y / fs;
    } else if (as > 1e-6) {
        wz = w0.z / as; ws = w1.z / as;
    } else {
        wz = 1.0; ws = 0.0;  // 全拒：保 zero（PC 同款，不与裁切侧帧均分）
    }
    vec4 zt = texelFetch(uZero, coord, 0);
    vec3 s = texelFetch(uSide, coord, 0).rgb;
    // 0.12.133（方案 B 块 1）：alpha 携带 zero 帧 D4 的 RAW 饱和掩码，
    // 融合后必须继续透传 —— 否则 HDR 档下 PC3D 收手会静默失效。
    oColor = vec4(zt.rgb * wz + s * ws, zt.a);
})";

// ── AMaZE 档（v0.10.172）─────────────────────────────────────────────
// 算法出处：Emil Martinec 的 AMaZE（2008-2010，Ingo Weyrich 优化版），参考实现
// RawTherapee `rtengine/amaze_demosaic_RT.cc`。**本项目的 AMaZE 在 CPU 侧跑**
// （src/render/AmazeDemosaic.cpp：逐段对齐参考实现的标量分支 + tile 并行），
// 本 pass 只承担"CPU 结果 → 成片几何"的搬运：
//   CPU AMaZE（源几何、WB 域相机 RGB）→ tex_amaze_（RGB16F）
//   → 本 pass（kVertRot + uST 单位阵，与 kFsRcdChroma 同款坐标变换）→ tex_work_ D3
//   → 既有 passCcm → tex_scene_ D4 → 既有胶片链（零改动）
// 为什么不做 GLSL 多 pass 版：AMaZE 在 tile 内需要 14 张 ts×ts + 12 张半网格中间场
// （见参考实现的 buffer 清单），GLES 3.0 fragment 版等价于 15+ pass、全图级中间
// 纹理显存峰值按 12MP 估 ≈1.7GB（RGBA16F），且 Nyquist 判据对半精度敏感。成本表
// 与备选方案见 docs/reports/amaze-REPORT.md「GPU 移植策略与成本」。
const char* kFsAmazeUp = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uSrc;  // AMaZE 输出（源几何，WB 域相机 RGB）
uniform ivec2 uSize;           // 源尺寸 (raw_w, raw_h)
// ── 0.12.150：alpha 承载 HL 的块级饱和掩码（与 kFsRcdChroma 的 hlMaskAt 同口径）──
// Amaze 档不经过 RCD，若不在这里补写，D3/D4 的 alpha 恒 1 ⇒ color_smooth 的
// 探测点全部被判成"饱和核" ⇒ nv==0 直接 return，伪彩抑制整档失效。
uniform highp sampler2D uHlMask;
uniform int uMaskOn;
uniform ivec2 uMaskSize;       // 块网格 (raw_w/3, raw_h/3)
float hlMaskAt(ivec2 q) {
    if (uMaskOn == 0) return 0.0;
    ivec2 mx = max(uMaskSize - 1, ivec2(0));
    vec3 m = texelFetch(uHlMask, clamp(q / 3, ivec2(0), mx), 0).rgb;
    return max(m.r, max(m.g, m.b)) > 0.5 ? 1.0 : 0.0;
}
void main() {
    // 2D 纹理坐标 (0,0) 在左上，vUV0 翻转对齐 OES 习惯（与 kFsRcdChroma 逐字同款）
    vec2 tuv = vec2(vUV0.x, 1.0 - vUV0.y);
    ivec2 p = clamp(ivec2(tuv * vec2(uSize)), ivec2(0), uSize - 1);
    oColor = vec4(texelFetch(uSrc, p, 0).rgb, hlMaskAt(p));
})";

// ── M3-2 降噪（v0.5.32 引入 / v0.5.33 两档调参）─────────────────────
// 量化基线（真机 ISO~1200 平坦区）：亮噪 σ(Y)≈1.5–1.8（达标优），彩噪
// σ(chroma)≈1.8–2.1（未达优，B-Y 最重）——彩噪是短板。
// v0.5.32 首版压制到位（彩噪 1.41→0.74）但细节 Laplacian -48% 过猛；
// v0.5.33 两档调参真机实测失效：两档无差别、细节 -59%、JPEG 体积腰斩——值域
// σ 远大于真实噪声，权重饱和退化成无条件 9 点平均；v0.5.34 按"P0 温和保细节 /
// P4 较强只压色度"重标定（P4 输出保 Y，色度平滑不损 Laplacian 细节）。
// P0：RAW 域同色联合 bilateral。任意 Bayer 相位下"步长 2 的 3×3 网格"
// 恰为 9 个同色位（含中心），无需相位条件；值域 σ 随信号 √ 增长
// （shot noise 主导）。输出线性 mosaic（rgb = 该相位通道值），同时让
// P1–P3 免去整型解码/黑阶/WB 重复计算。
const char* kFsRawDenoise = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp usampler2D uRaw;
uniform ivec2 uSize;
uniform int uCfaLut;
uniform vec4 uBlack;
uniform float uWhite;
uniform vec3 uGain;
// C 专项（2026-09-29）：σ²(x) = A·x + B（逐通道；x = 本 pass 域信号——黑阶后/
// 白阶归一/WB 后。A = shot·g、B = read·g²；**v0.10.108 起强度 k 不再乘进 σ**
// （σ 报小 ⇒ d 放大 ⇒ w=exp(−0.5d²) 塌陷 ⇒ 降噪近似失效，且噪声越大的亮部越惨）。
uniform vec3 uNoiseA;
uniform vec3 uNoiseB;
uniform float uMix;   // v0.10.108：强度<1 的输出混合（σ 恒为真实噪声）
int chanAt(ivec2 p) {
    int k = ((p.y & 1) << 1) | (p.x & 1);
    return (uCfaLut >> (k << 1)) & 3;
}
float gainAt(int c) { return c == 0 ? uGain.x : (c == 1 ? uGain.y : uGain.z); }
float raw0(ivec2 p) {
    p = clamp(p, ivec2(0), uSize - 1);
    float v = float(texelFetch(uRaw, p, 0).r);
    float bl = uBlack[(p.y & 1) * 2 + (p.x & 1)];
    return max((v - bl) / max(uWhite - bl, 1.0), 0.0) * gainAt(chanAt(p));
}
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float c0 = raw0(p);
    float v = c0;
    int ch = chanAt(p);
    float av = ch == 0 ? uNoiseA.x : (ch == 1 ? uNoiseA.y : uNoiseA.z);
    float bv = ch == 0 ? uNoiseB.x : (ch == 1 ? uNoiseB.y : uNoiseB.z);
    // v0.6.34：关降噪档 = A=B=0，只做线性化+WB、**不取任何邻居**（1 次采样，
    // 其余 8 个同色位全跳过）。这条路径与 P1 直读 RAW 整型纹理完全等价，但省掉了
    // P1 在那条路上的重复解码 —— 真机实测关降噪反而比开降噪慢 4-7 倍的原因。
    float sr2 = av * c0 + bv;
    if (sr2 > 1e-12) {
        // 值域 σ 逐像素由模型给出（C 专项）——旧式 ns·k·√(0.2+4x) 是纯经验项，
        // 且 ns 长期被夹到下限 0.004（σ 只有真实值的 1/5~1/2），权重近透传；
        // 真实 σ 恢复"1σ 内平均、之外衰减"的选择性（标准做法）。
        float sr = sqrt(sr2);
        float wsum = 1.0, acc = c0;
        for (int dy = -4; dy <= 4; dy += 2)
            for (int dx = -4; dx <= 4; dx += 2) {
                if (dx == 0 && dy == 0) continue;
                float c = raw0(p + ivec2(dx, dy));
                float d = (c - c0) / sr;
                float w = exp(-0.5 * d * d
                              - 0.5 * float(dx * dx + dy * dy) / 6.48);  // σs=1.8
                acc += w * c;
                wsum += w;
            }
        v = mix(c0, acc / wsum, uMix);   // v0.10.108：强度<1 = 线性混合（各亮度段一致）
    }
    oColor = vec4(ch == 0 ? v : 0.0, ch == 1 ? v : 0.0, ch == 2 ? v : 0.0, 1.0);
})";

// P4：色度平滑（彩噪主修）。场景线性域（CCM 后）：色差 = RGB − Y(luma)，
// 色差联合 bilateral（Y 值域防边缘色溢 + 色差值域），Y 原样保留 —— 亮度
// 细节零损失，只平滑色彩。
const char* kFsChromaDenoise = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform ivec2 uSize;
// C 专项（2026-09-29）：σ²(y) = A·y + B（luma 权重合并的逐像素模型；系数 =
// WB 后 raw 域；**v0.10.108 起强度 k 不再折入 σ**，<1 走 uMix 输出混合）。
uniform vec3 uNoiseA;
uniform vec3 uNoiseB;
uniform float uMix;   // v0.10.108：强度<1 的输出混合（σ 恒为真实噪声）
// LSC 增益网格（C 专项：增益传播）。shot 项随被放大的信号 y0 自然增长；
// read 项不随信号，需显式 ×g²（var' = A·g·x + B·g²，见 NoiseModel::afterWb）。
uniform highp sampler2D uLsc;
uniform ivec2 uLscSize;
// ── 档 A（v0.10.165）：伪彩感知色度降噪 ─────────────────────────────────────
// uMoire = 强度（0 = 关，A/B 通路）；uMoireK = 伪彩度量的归一化尺度
// （"K 倍 σc 量级的色度起伏"给 moire = 0.5）。封顶 = 4·σc（常量；uMoire ≤ 3 时
// 恒不触发 —— 见 FilmParams[48] 注释）。
uniform float uMoire;
uniform float uMoireK;
// ── 强边缘色度门 v2（v0.10.174）：**逐像素、有界、平坦区严格 0** ────────────
// PC 原文（esd3d2.glsl:78-82 = esd3d2_steered.glsl:94-98，两版逐字相同）：
//   chromaDiff *= max(abs(xDelta), abs(yDelta));
//   float sigZ = max(sigY, min(abs(chromaNoise) * MOIRE, 0.2));
//   vec4 w2 = (1.0 - d*d/(d*d + sigZ));    ← 色度路，**无独立亮度项**
// v0.10.173 的错法（真机实测"一片红一片黄一片紫"）：把 PC 的**绝对** cap 0.2
// 直接当 scEff，并让 wy 的阈值也用它（uCgScope=1）。两个后果都是**全图性**的：
//   ① 绝对 0.2 与真实噪声 σc（中灰 ≈0.01、暗部 ≈0.005）相比是 20~40×
//      ⇒ `min(κ·|∇L|, 0.2)` 在 |∇L| ≥ 0.1 处就吃满 ⇒ **73.7% 的像素**被放宽
//      （真机同源：p4scEff+ = 34~87%）；
//   ② 门只看 |∇L|、不看色度是否异常 ⇒ 亮部平坦区（sRGB 斜率大，显示域 ∇<10
//      也对应线性域 |∇L| ≈0.07）同样开门。
//   ③ D-1 的同一门在 5 级 à trous（step 1..16 半分辨率 = 2..32 px）上是
//      100% 覆盖 ⇒ 每一级的色度 detail 被整片抹掉 ⇒ 输出色度 ≈ 最粗尺度均值。
//      **"片"量级的色块只能由这一级产生**（离线：去掉 D-1 的门后 hue15blk
//      11.90% → 3.20%；只留 D-1 的门则是 12.65%）。
// v2 的三条修正：
//   ① **逐像素门**：gate = smoothstep(kCgG0, kCgG1, |∇L|)，平坦区**严格 0**；
//   ② **有界放宽**：上限 = kCgRel·σc（相对量），不再用绝对 0.2；
//   ③ **权重换成 PC 的 w2 形式**（不再"放宽 wy 的阈值"）：d² = |c − c0|²（3D
//      距离，含亮度差），w2 = 1 − d²/(d² + σ)，唯一阈值 σ。这样跨亮度取色由
//      w2 分支**自己**承担，gauss 路径的 wy 恒用 σc ⇒ **彻底放弃"放宽 wy"**。
//      w = mix(w_gauss, w2, gate)：gate = 0 ⇒ 逐位等于改动前（含 uCgK = 0）。
// kCgG0 的依据（可复算）：显示域（sRGB 8-bit）中心差分 ∇ ≤ 10 是本链红线判据
// "平坦区"的定义；线性域 |∇L| = (∇/255)·d(lin)/d(disp)，而 d(lin)/d(disp) 在
// disp = 1.0（纯白）取最大 2.46 ⇒ ∇ = 10 对应 |∇L| ≤ 0.0965 < kCgG0 = 0.10
// ⇒ **∇≤10 的像素在数学上必然 gate = 0**。kCgG1 = 3·kCgG0 与 PC 的
// convdiff.glsl:34-36 同一个中心差分算子（PC 用 Rec.601 luma，本链统一 Rec.709，
// 同一梯度上差 < 3%）。
// ★ 亮度零损失：P4 的输出亮度**恒为** y0（末行 oColor = y0 + 平滑后的色度），
//   gate 只出现在色度采样权重里 ⇒ 改它不改变输出的亮度通道（线性域逐位）。
uniform float uCgK;      // κ：门控强度（0 = 关 ⇒ 逐位回到改动前）
uniform float uCgScope;  // 1 = PC 的 w2 形式（v2 默认）；0 = 字面版（只放大 wc）
const float kMoireCap = 4.0;
const float kCgG0 = 0.10;   // 门的下阈（线性域 |∇L|；依据见上，∇≤10 ⇒ gate=0）
const float kCgG1 = 0.30;   // 门的上阈
const float kCgRel = 16.0;  // 放宽上限 = 16·σc（相对；旧版绝对 0.2 ≈ 20~40·σc，
                            //   暗部（σc≈0.005）现在是 0.08 ⇒ 自动收紧）
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
vec3 lscAt(ivec2 p) {
    vec2 g = ((vec2(p) + 0.5) / vec2(uSize)) * vec2(uLscSize) - 0.5;
    ivec2 b = ivec2(floor(g));
    vec2 t = g - vec2(b);
    ivec2 mx = uLscSize - 1;
    vec3 c00 = texelFetch(uLsc, clamp(b, ivec2(0), mx), 0).rgb;
    vec3 c10 = texelFetch(uLsc, clamp(b + ivec2(1, 0), ivec2(0), mx), 0).rgb;
    vec3 c01 = texelFetch(uLsc, clamp(b + ivec2(0, 1), ivec2(0), mx), 0).rgb;
    vec3 c11 = texelFetch(uLsc, clamp(b + ivec2(1, 1), ivec2(0), mx), 0).rgb;
    return mix(mix(c00, c10, t.x), mix(c01, c11, t.x), t.y);
}
// 单个邻居的值域权重累加（P4 原式；档 A 起色度阈值用 scEff = 放大后的 σc）。
// v0.10.174：亮度项阈值**恒为 sc**（不再接受"放宽后的 sy" —— 同步步放宽 wy 的
// 路径已删除）；gate > 0 时按 PC 的 w2 形式（1 − d²/(d² + scEff)）逐像素混合。
void p4Accum(vec3 c, int dx, int dy, float y0, vec3 cd0, float sc, float scEff,
             float pcGate, inout vec3 acc, inout float wsum) {
    float y = dot(c, LUMA);
    vec3 cd = c - y;
    float wy = (y - y0) / (sc * 1.2);
    float wc = length(cd - cd0) / scEff;   // ← 档 A 起 = 放大后的 σc
    float w = exp(-0.5 * (wy * wy + wc * wc)
                  - 0.5 * float(dx * dx + dy * dy) / 9.68);  // σs=2.2（v0.6.32）
    if (pcGate > 0.0) {
        // PC esd3d2.glsl:104-106 的 w2：d = |c − c0|（3D，含亮度差），唯一阈值
        // scEff，**没有独立的亮度项** ⇒ 跨亮度边缘的邻居在此分支重新获得权重。
        vec3 dv = c - (cd0 + y0);
        float d2 = dot(dv, dv);
        float w2 = 1.0 - d2 / (d2 + scEff);
        w = w + (w2 - w) * pcGate;
    }
    acc += w * cd;
    wsum += w;
}
// 0.12.187：独立饱和掩码（纹理单元 2）—— 饱和核内**不动色度**。
// P4 是 1-3px 的色度降噪，会把高光核边缘的品红/绿边摊成宽带；核内本来就没有
// 可恢复的色度（信息已丢），唯一安全的做法是原样输出。
uniform highp sampler2D uSatMask;
uniform int uSatMaskOn;
void main() {
    ivec2 p = clamp(ivec2(gl_FragCoord.xy), ivec2(0), uSize - 1);
    vec3 c0 = texelFetch(uTex, p, 0).rgb;
    if (uSatMaskOn > 0 && texelFetch(uSatMask, p, 0).r > 0.5) {
        oColor = vec4(c0, 1.0);
        return;
    }
    float y0 = dot(c0, LUMA);
    vec3 cd0 = c0 - y0;
    // 色度可保持较强：Y 原样输出，压彩噪不损 Laplacian 细节；Y 值域权重
    // sc*1.2 防边缘色溢。
    // ── v0.6.32：靠**空间半径**降彩噪，靠**收紧 σc** 把色度代价补回来 ────
    // 离线评估（DNG→libraw 重建 P4 输入域，样片暗部，口径 lag-1 差分/√2）：
    //   变体                彩噪残余   真实色度边缘保留
    //   r2 σs1.6（旧）       28.5%     0.624
    //   r2 σs2.2             27.1%     （几乎没用）
    //   r3 σs2.2 σc=0.035    18.5%     0.477
    //   r3 σs2.2 σc=0.024    20.0%     0.506   ← 采用
    // 两条结论都**反直觉**，写下来防止再犯：
    //  ① **σs 不是旋钮**：半径锁死 2 时 σs 1.6→2.2 仅改善 6% —— σs 超过半径就被
    //     截断（σs=3.0/4.0 同样停在 ~6%）。真正的杠杆是把半径放到 3（7×7）。
    //  ② **σc 调大是错方向**：旧值 0.035 在暗部已是"真实色度差 0.014"的 2.5 倍、
    //     逐像素噪声 0.0033 的 10 倍 —— 真实色度被当噪声吞掉（这正是"桌面颜色不如
    //     PhotonCamera"的一半原因）。收紧到 0.024 后边缘保留 0.477→0.506。
    // 地板 0.015 → 0.008：它占暗部 σc 的 43% 且与噪声无关，是"暗部必然抹色"的主因。
    // 降噪改由半径承担，值域阈值只负责保住真实色度边缘。
    // ── C 专项（2026-09-29）：σc 从"经验式 + 常数地板"改为真实噪声模型 ──
    // σc² = Ā·y0 + B̄·g_lsc²（Ā/B̄ = 模型 3 通道的 luma 权重合并；g_lsc = 该点
    // LSC 实际施加增益的 luma 值，边缘 3~5× ⇒ read 项贡献随之放大）。地板由
    // 模型 B 项自然给出（暗部 √B̄·g），旧的 +0.008 常数地板删除。
    float ay = dot(uNoiseA, LUMA);
    float by = dot(uNoiseB, LUMA);
    vec3 gl = lscAt(p);
    float glY = dot(gl, LUMA);
    // v0.10.120 回退 v0.10.119 的 "A 项乘 glY" 修正：物理推导上它更自洽，但真机实测
    // （2026-10-03 14:30）降噪从 21~32% 猛增到 43~62% ⇒ 画面发糊，而色块**毫无改善**。
    // 结论：σ 不是杯体色块的根因，该修正只有副作用，回退。
    float sc = sqrt(max(ay * y0 + by * glY * glY, 1e-12));
    // ── 档 A（v0.10.165）：逐像素伪彩强度 → 色度 σc 放大 ────────────────────
    // PC 依据 esd3d2_steered.glsl:94-98（sigZ = max(sigY, min(chromaNoise·MOIRE,
    // 0.2))）。四条可搬运规则照抄：① 只增强不减弱 ② 结构门控 ③ 通道差异 ④ 封顶。
    // 度量 = 「3×3 内色差相对局部基线的偏离 → 取 Cb/Cr 两路极差（彩色性）→ 乘
    // 结构门 gate = |∇Y|/(|∇Y|+σc)」，归一成 moire ∈ [0,1) 后放大 σc。
    // **零新增采样**：内圈 3×3 的 8 个邻居在这里取一次（显式变量，不用数组动态
    // 索引 —— 避免移动 GPU 把它 spill 到 local memory），主循环只补取外圈 40 个
    // ⇒ texelFetch 次数与改动前同为 48。
    vec3 cA = texelFetch(uTex, clamp(p + ivec2(-1, -1), ivec2(0), uSize - 1), 0).rgb;
    vec3 cB = texelFetch(uTex, clamp(p + ivec2( 0, -1), ivec2(0), uSize - 1), 0).rgb;
    vec3 cC = texelFetch(uTex, clamp(p + ivec2( 1, -1), ivec2(0), uSize - 1), 0).rgb;
    vec3 cD = texelFetch(uTex, clamp(p + ivec2(-1,  0), ivec2(0), uSize - 1), 0).rgb;
    vec3 cE = texelFetch(uTex, clamp(p + ivec2( 1,  0), ivec2(0), uSize - 1), 0).rgb;
    vec3 cF = texelFetch(uTex, clamp(p + ivec2(-1,  1), ivec2(0), uSize - 1), 0).rgb;
    vec3 cG = texelFetch(uTex, clamp(p + ivec2( 0,  1), ivec2(0), uSize - 1), 0).rgb;
    vec3 cH = texelFetch(uTex, clamp(p + ivec2( 1,  1), ivec2(0), uSize - 1), 0).rgb;
    float yB = dot(cB, LUMA), yD = dot(cD, LUMA);
    float yE = dot(cE, LUMA), yG = dot(cG, LUMA);
    vec3 cd3 = cd0 + (cA - dot(cA, LUMA)) + (cB - yB) + (cC - dot(cC, LUMA))
             + (cD - yD) + (cE - yE) + (cF - dot(cF, LUMA))
             + (cG - yG) + (cH - dot(cH, LUMA));   // 3×3 色差基线（含中心）
    // 只取 Cb/Cr 两路（G 是 Y 守恒反解的残差，同 D-1 的逐路口径）
    vec2 dev = abs(cd0.br - (cd3 / 9.0).br);
    float chromaDev = max(dev.x, dev.y) - min(dev.x, dev.y);   // R3：通道差异
    float gmag = length(vec2(yE - yD, yG - yB) * 0.5);         // 中心差分（convdiff.glsl:34 同式）
    float gate = gmag / (gmag + sc);                           // R2：结构门控
    float m = chromaDev * gate;
    float moire = m / (m + uMoireK * sc);
    // R1（只增强：uMoire = 0 ⇒ scEff 逐位 = sc）+ R4（封顶 4·σc）
    float scEff0 = min(sc * (1.0 + uMoire * moire), kMoireCap * sc);
    // ── v0.10.174：强边缘色度门 v2（照 PC 的 sigZ 架构，但逐像素 + 有界）────
    // |∇L| = gmag（上一段的中心差分；与 PC convdiff.glsl:34-35 的
    // (sum3(x-1) − sum3(x+1))/2 同式，PC 的 sum3 = Rec.601 luma，此处沿用引擎
    // 全链的 Rec.709 —— 两者在本场景梯度上差 < 3%）。
    // cgGate = 逐像素门：∇≤10（显示域）的像素在数学上必然为 0（见 kCgG0 注释）。
    // cgRelax = min(κ·|∇L|, kCgRel·σc)·cgGate ⇒ ① **有界**（旧版绝对 0.2 在暗部
    // 是 40·σc，现在最多 8·σc）；② **门控**（平坦区 scEff 逐位 = scEff0）。
    // uCgK = 0 ⇒ cgGate = 0 ⇒ cgRelax = 0 ⇒ scEff 逐位 = scEff0、gate = 0
    // ⇒ 权重逐位回到改动前（P4 路径的"关闭即逐位"由这两条保证）。
    float cgGate = (uCgK > 0.0) ? smoothstep(kCgG0, kCgG1, gmag) : 0.0;
    float cgRelax = min(uCgK * gmag, kCgRel * sc) * cgGate;
    float scEff = max(scEff0, cgRelax);
    // 作用域：1 = PC 的 w2 形式（跨亮度取色由 w2 分支承担，v2 默认）；
    // 0 = 字面版（**只**放大 wc 的阈值，权重仍是 gauss；实测近无效，留作 A/B 反证）。
    // 注意 pcGate 只在 scope = 1 时非零：字面版不切分支，但 scEff 仍按 cgGate 门控。
    float pcGate = (uCgScope > 0.5) ? cgGate : 0.0;
    vec3 acc = cd0;
    float wsum = 1.0;
    p4Accum(cA, -1, -1, y0, cd0, sc, scEff, pcGate, acc, wsum);
    p4Accum(cB,  0, -1, y0, cd0, sc, scEff, pcGate, acc, wsum);
    p4Accum(cC,  1, -1, y0, cd0, sc, scEff, pcGate, acc, wsum);
    p4Accum(cD, -1,  0, y0, cd0, sc, scEff, pcGate, acc, wsum);
    p4Accum(cE,  1,  0, y0, cd0, sc, scEff, pcGate, acc, wsum);
    p4Accum(cF, -1,  1, y0, cd0, sc, scEff, pcGate, acc, wsum);
    p4Accum(cG,  0,  1, y0, cd0, sc, scEff, pcGate, acc, wsum);
    p4Accum(cH,  1,  1, y0, cd0, sc, scEff, pcGate, acc, wsum);
    for (int dy = -3; dy <= 3; ++dy)
        for (int dx = -3; dx <= 3; ++dx) {
            if (dx >= -1 && dx <= 1 && dy >= -1 && dy <= 1) continue;  // 内圈已取
            vec3 c = texelFetch(uTex, clamp(p + ivec2(dx, dy), ivec2(0), uSize - 1), 0).rgb;
            p4Accum(c, dx, dy, y0, cd0, sc, scEff, pcGate, acc, wsum);
        }
    // a 通道 = 逐像素诊断量（下游只读 rgb；供 kFsMoireStat 统计）。
    // v0.10.174：uCgK > 0 时写**门控开度** cgGate（1 = 强边缘吃满门）
    // ⇒ cgMean/cgCov 直接读作"门控覆盖率"（旧版写的是 cg/scEff = 阈值里的门控
    // 占比，那个量在 v2 里恒 ≤1 但语义不同）；否则维持档 A 的 moire 语义。
    // 两者都不开写 1.0 ⇒ 输出与 v0.10.172 逐位一致（含 alpha）。
    oColor = vec4(y0 + mix(cd0, acc / wsum, uMix),
                  uCgK > 0.0 ? cgGate : (uMoire > 0.0 ? moire : 1.0));
})";

// 档 A 诊断（v0.10.165）：伪彩强度图的全局统计（只读 P4 输出的 a 通道 = moire）。
// 32×32 网格 × 每格 16×16 等距采样（= 262144 样本 ≈ 全图 2%，采样成本 ≈ P4 的
// 1/2300；每张成片一次）。每格输出 4 个**比例**通道（已 ÷256），CPU 按格覆盖的
// 像素数加权后得到全图统计：
//   r = mean(moire)                    → moMean（精确）
//   g = P(moire>0.25)、b = P(>0.5)、a = P(>0.75)
//                                      → moCov = b（精确）；moP95 = 在锚点
//                                        (0,0.25,0.5,0.75,1.0) 上做分段线性 CDF 插值
// （口径边界：P95 是 4 锚点分段线性近似，段内按均匀分布假设；判读量级足够）。
const char* kFsMoireStat = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oStat;
uniform highp sampler2D uSrc;
uniform ivec2 uSize;
const int kGrid = 32;
void main() {
    ivec2 g = clamp(ivec2(gl_FragCoord.xy), ivec2(0), ivec2(kGrid - 1, kGrid - 1));
    ivec2 lo = (g * uSize) / kGrid;
    ivec2 hi = ((g + 1) * uSize + (kGrid - 1)) / kGrid;
    ivec2 span = max(hi - lo, ivec2(1, 1));
    vec4 acc = vec4(0.0);
    for (int j = 0; j < 16; ++j)
        for (int i = 0; i < 16; ++i) {
            ivec2 q = clamp(lo + (ivec2(i, j) * span) / 16, ivec2(0), uSize - 1);
            float m = texelFetch(uSrc, q, 0).a;
            acc += vec4(m, m > 0.25 ? 1.0 : 0.0, m > 0.5 ? 1.0 : 0.0,
                        m > 0.75 ? 1.0 : 0.0);
        }
    oStat = acc * (1.0 / 256.0);
})";

// ── 紫边抑制（v0.10.134）：逆光边缘紫边 = 贴着物体轮廓的品红渐晕 ─────────
// 2026-10-04 样片定量（tools 离线，基准 154402 对）：品红伪影 56~66% 贴边、
// 平坦区孤立点 2~3%；P4/D-1 的保边判据恰好保护它，整链近零压制；RawTherapee
// Defringe（PF_correct_RT）的核心机制对症：检测量 = 色度相对**局部模糊基线**
// 的偏离（高饱和物体内部均匀 → 不命中，天然保红），替换 = 低偏离邻居加权
// 平均（L 零损失）。三处与原版的差异都有数据支撑：
// ① 作用域 = 引擎 P4 后的场景线性 RGB → Lab（RT 在其管线 demosaic 后，同位）；
// ② 色相门 = Lab a∈[15,30] 反向 smoothstep（离线定稿：紫边 ↓63%、红物体
//    色度 +1%；RT 用可选 hue curve 实现同职，默认全 0 不动作）；
// ③ 参数：检测模糊 σ8 / 替换窗口 13×13 / 阈值 t23（k=(23/33)²·5=2.43，RT
//    默认 t13 对自动管线命中 21% = 全图色度大清洗，毁图实锤后重标定）。
// 实现（半分辨率，紫边为 2-8px 低频结构）：
//   down（2×2 box + 转 Lab + 门）→ detect（11×11 box 基线 + chroma）→
//   avg（16×16 网格均值，CPU 读回 chromave）→ apply（命中替换）。
const char* kFsDefringeDown = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oLab;   // (lum, br, gr, gate)  v0.10.194
uniform highp sampler2D uTex;         // 全分辨率场景线性 RGB
uniform ivec2 uSize;
const vec3 LW = vec3(0.2126, 0.7152, 0.0722);
void main() {
    ivec2 p = clamp(ivec2(gl_FragCoord.xy) * 2, ivec2(0), uSize - 1);
    vec3 acc = vec3(0.0);
    for (int i = 0; i < 4; ++i) {
        ivec2 q = clamp(p + ivec2(i & 1, i >> 1), ivec2(0), uSize - 1);
        acc += texelFetch(uTex, q, 0).rgb;
    }
    vec3 rgb = acc * 0.25;
    // ── v0.10.194：色度量改 **B−R / R−G 双线性量**，不再走 Lab ─────────────
    // 为什么弃 Lab（v0.10.186~193 一直用 Lab a/b 的教训）：
    //   ① Lab 在低饱和区数值噪声极大（灰像素 chroma_abs 也上百），判据阈值
    //      与视觉脱钩；
    //   ② 紫边与「真紫色物体」在 Lab 同象限（a>0,b<0），色相判据天生分不开；
    //   ③ 每次改判据都要重标 Lab 阈值，量纲翻译错一次就整版失效（193 即此）。
    // 线性 RGB 两轴判据则天然分象限且量纲稳定：
    //   紫/品红 (要治) : B > R > G  ⇒ br>0 且 gr>0
    //   青/蓝   (要保) : B > G > R  ⇒ br>0 但 gr<0
    //   红/橙/黄(要保) : R > G > B  ⇒ br<0
    //   绿      (要保) : G 最大     ⇒ gr<0
    // ⇒ **紫象限 (br>0 && gr>0) 一条判据就把暖色/绿/青全部排除**（离线实测
    //    800×1200 样片：暖色误伤 0、绿向误伤 0）。
    float br = rgb.b - rgb.r;
    float gr = rgb.r - rgb.g;
    float lum = max(dot(LW, rgb), 1e-4);
    // 平滑紫象限门（仅诊断/未来加权用；apply 用硬判据 br>thr && gr>0）
    float gate = smoothstep(0.0, 0.05, br / lum) * smoothstep(-0.02, 0.03, gr / lum);
    oLab = vec4(lum, br, gr, gate);
})";

const char* kFsDefringeDetect = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oDet;   // (br, gr, |br|, base_std)
uniform highp sampler2D uSrc;         // 半分辨率 (lum, br, gr, gate)
uniform ivec2 uHalf;
void main() {
    ivec2 p = clamp(ivec2(gl_FragCoord.xy), ivec2(0), uHalf - 1);
    vec4 c0 = texelFetch(uSrc, p, 0);
    // base_std(B−R) = 邻域 br 的**标准差** —— 区分窄紫边（色度跳变剧烈）与
    // 大片紫色物体（色度均匀）。离线实测（线性域，cavg = 全图 |B−R| 均值）：
    //   强紫边  base_std P10/P50/P90 = 12.7×/15.7×/19.5× cavg
    //   紫物体内部 base_std ≈ 0.1× cavg ⇒ 被 std 判据干净排除
    // （v0.10.190~192 用的是「邻域 Lab 均值」，在紫线成片处恒高 ⇒ 把所有紫线
    //   挡在门外，是 192 命中 0/727 的根因。）
    float sqsum = 0.0;
    float sum = 0.0;
    for (int dy = -5; dy <= 5; ++dy)
        for (int dx = -5; dx <= 5; ++dx) {
            ivec2 q = clamp(p + ivec2(dx, dy), ivec2(0), uHalf - 1);
            float br_n = texelFetch(uSrc, q, 0).y;
            sum += br_n;
            sqsum += br_n * br_n;
        }
    float mu = sum / 121.0;
    float var = sqsum / 121.0 - mu * mu;
    float base_std = sqrt(max(var, 0.0));
    oDet = vec4(c0.y, c0.z, abs(c0.y), base_std);
})";

const char* kFsDefringeAvg = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oAvg;   // (chroma 均值, 0, 0, 1)
uniform highp sampler2D uSrc;         // 半分辨率 (a,b,chroma,gate)
uniform ivec2 uHalf;
void main() {
    // 16×16 box：CPU 对网格再取均值 = 全图 chromave（边缘 box 略欠权，可忽略）
    ivec2 p = ivec2(gl_FragCoord.xy) * 16;
    float acc = 0.0;
    for (int dy = 0; dy < 16; ++dy)
        for (int dx = 0; dx < 16; ++dx) {
            ivec2 q = clamp(p + ivec2(dx, dy), ivec2(0), uHalf - 1);
            acc += texelFetch(uSrc, q, 0).z;
        }
    oAvg = vec4(acc / 256.0, 0.0, 0.0, 1.0);
})";

const char* kFsDefringeApply = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;  // 线性 RGB（进 D-1 / film）
uniform highp sampler2D uTex;          // 全分辨率场景线性 RGB
uniform highp sampler2D uDet;          // 半分辨率 (br, gr, |br|, base_std)
uniform ivec2 uHalf;
uniform float uChromaAvg;              // 全图 |B−R| 均值（线性域，CPU 读回）
uniform float uThreshK;                // |B−R| 相对阈值倍数（× uChromaAvg）
uniform float uThreshMin;              // |B−R| 绝对下限（防 cavg 极小的暗场景过检）
uniform float uThreshMax;              // |B−R| 绝对上限（防 cavg 异常大时阈值失效）
uniform float uStdK;                   // base_std 相对阈值倍数（× uChromaAvg）
uniform float uStdMax;                 // base_std 绝对上限
uniform float uGradMin;                // 亮度梯度下限（线性域 ×255 口径）
void main() {
    ivec2 p = clamp(ivec2(gl_FragCoord.xy), ivec2(0), uHalf * 2 - 1);
    vec3 lin0 = texelFetch(uTex, p, 0).rgb;
    vec4 d0 = texelFetch(uDet, clamp(p >> 1, ivec2(0), uHalf - 1), 0);
    // ── v0.10.194：阈值改为**相对 uChromaAvg 的倍数**（与量纲无关）────────
    // 193 真机紫线原样保留的根因：阈值写成绝对值 5.0，而那是**显示域**离线量出
    // 的数；shader 里跑的是**线性域**（linMid≈0.07、pHi≈3.3）⇒ d0.z(≈0.14) > 5.0
    // 恒 false ⇒ 全部紫线像素未命中。191/192/193 连续三版"离线看着对、真机无效"
    // 是同一个量纲陷阱：离线用显示域 JPG 定标，shader 跑线性域。
    // ── v0.10.195：阈值再降（3.0 → 0.8），修 194 真机残留 ────────────────────
    // 194 真机三张样片（10-08 084712/084719/084732）紫边仍在的根因：
    //   **真机 cavg 比离线大 1.4~2.3×**（真机 0.035/0.0453/0.0651 vs 离线
    //   0.0244/0.0281/0.0286 —— 差异来自 PC3D 等真机独有环节），而判据是
    //   `br > 3×cavg` ⇒ 真机阈值被同比抬高 ⇒ 可见紫边（br/cavg ≈ 2.1 真机口径）
    //   整段落在阈值下方。用真机 cavg 复算覆盖率：thr=3× 只有 27~53%，
    //   thr=0.8× 有 78~97%（三张样片）。
    // 为什么可以直接降而不用怕误伤：判据②（B>R>G 紫象限）是**结构性**的 ——
    //   暖色 B<R、绿/青/蓝 R<G，与阈值无关；离线全图复算 thr=0.6~3.0 全区间
    //   暖色误伤 0 / 绿青误伤 0。
    // uThreshMin 绝对下限：防 cavg 极小的暗场景把噪声当紫边（0.012 线性域
    //   ≈ 显示域 3~5，低于任何可见紫边）。
    // uStdK 2.0 → 1.2：std 阈值同样以 cavg 为基准，真机 cavg 偏大会误挡；
    //   紫边像素 std/cavg 真机口径仍有 4.7，1.2 有充足余量。
    // 上下限都要（v0.10.195）：下限防暗场景 cavg 极小把噪声当紫边；上限防
    // cavg 异常大（真机实测有一张 dfCavg=0.1717，是常规值 0.03~0.065 的 3~5 倍）
    // 把阈值抬到紫边之上 —— 那种帧会让整个判据静默失效。
    // 上限取值依据：紫边像素 |B−R| 绝对值实测 0.09~0.11（线性域），
    // thr 上限 0.06 保证任何帧都不会把真实紫边挡在外面。
    float thr = clamp(uThreshK * uChromaAvg, uThreshMin, uThreshMax);
    float std_thr = min(uStdK * uChromaAvg, uStdMax);
    // 命中条件（四条结构性判据；①② 用**全分辨率** lin0，③④ 用半分辨率检测量）：
    //   ① br0 > thr           全分辨率 B−R 高于场景色度基线 ×3（B>R 是紫象限必要条件）
    //   ② gr0 > 0             全分辨率 R > G（青/蓝 gr<0、暖色 br<0 全排除）
    //   ③ d0.w > std_thr      窄带性：邻域 br 标准差高（大片紫物体内部 ≈ 0）
    //   ④ gL > uGradMin       贴着强亮度边缘（平坦紫光斑/墙面反光排除）
    // ⚠ ①② 必须用**全分辨率** lin0 而不是半分辨率的 d0.x/d0.y：
    //   紫边只有 1~3px 宽，2×2 box 平均会把 br 稀释掉一半以上 ⇒ 用 d0.x 判
    //   「br > 3·cavg」时全图 5764 个强紫像素只有 2191 个（38%）过阈，紫边
    //   大部分漏改（离线全图实测）。
    // ⚠ ① 必须用**带符号** br0 而非 |B−R|：用绝对值时负 br（红/暖色，B<R）
    //   也会通过，随后 B_new = R + br·k 会把 B 抬高到接近 R ⇒ 红色变品红
    //   （离线实测：0.01 阈值下 11304 个暖色像素被改写、B−G 中位 15→78 更紫）。
    //   d0.z(=|br|) 仅供 avg shader 统计 chromaAvg 用。
    // 零误伤是结构性的，不是调参调出来的：
    //   · 红/橙/黄物体：B < R ⇒ br0 < 0 ⇒ ① 不通过
    //   · 绿/青物体  ：R < G ⇒ gr0 < 0 ⇒ ② 不通过
    //   · 蓝物体     ：B > G > R ⇒ gr0 < 0 ⇒ ② 不通过
    //   · 大片紫色物体：邻域 br 均匀 ⇒ base_std ≈ 0 ⇒ ③ 不通过
    //   · 平坦紫光斑  ：|∇L| 低 ⇒ ④ 不通过
    // 唯一会命中 = 「B>R>G + 色度高于基线 + 色度跳变 + 贴强边缘」= 紫边定义本身。
    float br0 = lin0.b - lin0.r;
    float gr0 = lin0.r - lin0.g;
    float yL = dot(texelFetch(uTex, clamp(p - ivec2(1, 0), ivec2(0), uHalf * 2 - 1), 0).rgb, vec3(0.2126, 0.7152, 0.0722));
    float yR = dot(texelFetch(uTex, clamp(p + ivec2(1, 0), ivec2(0), uHalf * 2 - 1), 0).rgb, vec3(0.2126, 0.7152, 0.0722));
    float yU = dot(texelFetch(uTex, clamp(p - ivec2(0, 1), ivec2(0), uHalf * 2 - 1), 0).rgb, vec3(0.2126, 0.7152, 0.0722));
    float yD = dot(texelFetch(uTex, clamp(p + ivec2(0, 1), ivec2(0), uHalf * 2 - 1), 0).rgb, vec3(0.2126, 0.7152, 0.0722));
    float gL = length(vec2(yR - yL, yD - yU)) * 255.0;
    if (br0 <= thr || gr0 <= 0.0 || d0.w < std_thr || gL < uGradMin) {
        oColor = vec4(lin0, 1.0);
        return;
    }
    // 替换：**保亮度去饱和**（把 R、B 同时拉向 G，再把损失的亮度补回 G）。
    //   ① 去紫：R' = G + (R−G)·k，B' = G + (B−G)·k —— 直接压**彩度**而不是只压
    //      B−R。为什么不能只压 B−R：视觉上的紫 = R 与 B 双双高于 G，B−G 里有一半
    //      是 R−G 贡献的（离线全图实测：只压 B−R 时显示域 B−G 只从 80 降到 55、
    //      R−G 完全不动；R/B 同时压则 B−G→12、R−G→7，彩度 92→14）。
    //   ② 保亮度：**三通道等量加法补偿** δ = L₀ − L₁，out = desat + δ。
    //      为什么不是把亮度差全补到 G（先试过的形态）：G 被抬高后 R/B 相对偏低，
    //      紫边过冲成**淡绿线**（离线实测 B−G 从 +80 冲到 −10、R−G +50→−14）。
    //      等量加法有恒等式：R−G 与 B−G 在加同一标量后**完全不变**，故色差严格
    //      等于去饱和后的目标值、零过冲；而 Σw=1 ⇒ ΔL ≡ 0，亮度逐位不变。
    // 为什么不用 Lab 重建（v0.10.188/189）：Lab 在低 RGB 区数值不稳定，去饱和后
    // 常出负值，需要 clamp + XYZ 重建，反而引入新噪声。此处数值稳定、零转换误差。
    // k_desat 0.12 = 保留 12% 彩度（0 = 完全中性化）。
    const vec3 LW = vec3(0.2126, 0.7152, 0.0722);
    float k_desat = 0.12;
    float gg = lin0.g;
    vec3 desat = vec3(gg + gr0 * k_desat, gg, gg + (br0 + gr0) * k_desat);
    vec3 out_lin = desat + (dot(lin0, LW) - dot(desat, LW));
    oColor = vec4(clamp(out_lin, vec3(0.0), vec3(16.0)), 1.0);
})";

// ── 色差域中值滤波（v0.10.196）：darktable color_smoothing 逐行移植 ──────────
// 出处：darktable `src/iop/demosaicing/basics.c` :: color_smoothing()
// 语义：在 (R−G) 与 (B−G) **色差域**做 3×3 中值滤波，G 逐位不变，R/B = 中值 + G。
// 为什么它是"所有色向伪彩"的通解（而 defringe 只能治紫）：
//   中值滤波**不依赖任何色相判据** —— 紫/蓝/青/绿伪彩只要在色差域表现为
//   "1~3px 的窄带异常"，3×3 中值就会用邻域的主导色差替换它；而中值对阶跃
//   是**保边**的（不像均值/高斯那样把边缘糊开），所以真实的色度边界不受影响。
//   darktable 把它作为 demosaic 模块的可选后处理（0~5 次迭代），默认关
//   （代价是 1px 尺度的真实色度细节会被抹掉）；我们默认 1 次。
// 与 defringe 的分工：defringe 治"已知色相（紫/品红）的窄带"（色相门 + 去饱和），
//   本 pass 治"任意色相的窄带"（含蓝/青/绿向）。串联 = 色相定向 + 无偏中值。
const char* kFsColorSmooth = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform ivec2 uSize;
uniform float uGradLo;
uniform float uGradHi;
uniform float uSideK;       // 两侧色度必须 < 中心 × 该系数
uniform float uChromaMin;   // 相对亮度的色度下限（低于此值视为中性，不动）
uniform float uPeakK;       // 中心色度必须 > 该系数 × 两侧最大色度（"局部色度峰"）
const vec3 LW = vec3(0.2126, 0.7152, 0.0722);
// ── 边缘伪彩抑制 v0.10.206：**前置到 PC3D 之前 + 尺度 [3,6,12]** ───────────
// v0.10.205 用 [3,6] 两尺度后真机仍有紫线（用户 15:09 样片）：剖面实测该紫线
// **宽 6~8px**（x=931~937 连续 B−R=+14~+39，y 方向延伸很长），±3/±6 的探测点
// 总有一侧落在带内（x=936 B−R=+30）⇒ 判据不成立 ⇒ color_smooth 漏检 ⇒ PC3D
// 再把它摊开 ⇒ defringe 面对宽带也治不了。**加回 12 尺度**覆盖 6~12px 的带。
// v0.10.205 的关键改动：本 pass 的输入从"PC3D 输出"改成 **PC3D 之前的 D4**。
// 原因：PC3D 的色度平滑会把 1~3px 伪彩**摊成 12~90px 宽带**（真机 112643 剖面
// 实测），一旦摊开，任何尺度的探测点都落在带内 ⇒ 判据恒不成立（203 的 ±3px
// 覆盖率实测 **0.0%**，204 的多尺度也只到 43%）。前置后伪彩仍是窄带，只需
// ±3/±6 两个小尺度即可命中，且探测点必然落在带外 ⇒ 代价远小于宽带处理。
// （以下为 v0.10.204 的多尺度记录，保留作依据）
//   真机实测（112643 成片）用户所见彩边是 **PC3D 把 1~3px 伪彩摊开后的
//   12~90px 宽钟形带**（例：y=300 的 x=757~767，峰色度 44、两侧 x=755/769
//   色度 10/8）。单尺度 ±3px 的探测点**全落在带内** ⇒ 判据恒不成立 ⇒
//   覆盖率 **0.0%**（这就是 203 仍有残留的原因）。
//   多尺度让"带中心"能被大尺度命中、"带边缘"能被小尺度命中。
// 为什么 sideK 从 0.35 放宽到 0.7：相邻色带会污染探测点（本场景 y=300 行
//   就有 4 条带：675-749 / 757-767 / 824-843 / 868-878），0.35 时带内 11 个
//   像素只有 1 个命中；0.7 时 6/11。全图蓝边覆盖率 16.8% → **43.1%**，
//   色度总量代价 −2.5% → **−6.0%**（仍属"只削伪彩"量级）。
// 替换值 = 8 个探测点色差的平均（都偏中性 ⇒ 目标接近中性）；
// 保亮度 = 三通道等量加法补偿（色差加同一标量后不变，ΔL ≡ 0）；
// 只降不升 = 目标色度不得大于原色度。
void main() {
    ivec2 p = clamp(ivec2(gl_FragCoord.xy), ivec2(0), uSize - 1);
    ivec2 mx = uSize - 1;
    vec4 c0 = texelFetch(uTex, p, 0);
    float yL = dot(texelFetch(uTex, clamp(p - ivec2(1, 0), ivec2(0), mx), 0).rgb, LW);
    float yR = dot(texelFetch(uTex, clamp(p + ivec2(1, 0), ivec2(0), mx), 0).rgb, LW);
    float yU = dot(texelFetch(uTex, clamp(p - ivec2(0, 1), ivec2(0), mx), 0).rgb, LW);
    float yD = dot(texelFetch(uTex, clamp(p + ivec2(0, 1), ivec2(0), mx), 0).rgb, LW);
    vec2 grad = vec2(yR - yL, yD - yU);
    float glen = length(grad) * 255.0;
    float gate = smoothstep(uGradLo, uGradHi, glen);
    if (gate <= 0.0) { oColor = vec4(c0.rgb, c0.a); return; }
    float lum = max(dot(c0.rgb, LW), 1e-4);
    float ch0 = abs(c0.r - c0.g) + abs(c0.b - c0.g);
    if (glen <= 1e-6 || ch0 <= uChromaMin * lum) { oColor = vec4(c0.rgb, c0.a); return; }
    vec2 nd = grad / length(grad);
    ivec2 o3 = ivec2(round(nd * 3.0));
    ivec2 o6 = ivec2(round(nd * 6.0));
    ivec2 o12 = ivec2(round(nd * 12.0));
    // ── 0.12.147：饱和核（clip 白）不能当"中性旁证" ─────────────────────────
    // 真机证据（213210，粉勺镜面高光）：RAW 2×2 块 (0.714, 0.210, 0.523) ⇒ R/G 3.40、
    // 未饱和、与粉底同色相；成片同一位置 R/G 1.03（中性）—— 色度被洗掉 90%，
    // 在粉底上就显出那圈"淡绿"。
    // 机理：饱和核三通道全顶到满阱 ⇒ 重建后是中性白（ch≈0），而本判据把
    // "一侧 ch≈0" 当成"中心是局部色度峰"的证据 ⇒ 真实的暖色过渡被判成伪彩、
    // 压到 15%（= 中性）。
    // 2×2 块级饱和掩码（tex_hl_mask_ 经 RCD 写进 alpha、CCM 透传）就是现成的
    // "这个探测点是不是 clip 白"判据：落在掩码上的样本没有真实色度信息，
    // 不能参与"两侧中性"的判定。掩码不存在时 alpha 恒 0 ⇒ 逐位回到旧行为。
    vec4 a3 = texelFetch(uTex, clamp(p + o3, ivec2(0), mx), 0);
    vec4 b3 = texelFetch(uTex, clamp(p - o3, ivec2(0), mx), 0);
    vec4 a6 = texelFetch(uTex, clamp(p + o6, ivec2(0), mx), 0);
    vec4 b6 = texelFetch(uTex, clamp(p - o6, ivec2(0), mx), 0);
    vec4 a12 = texelFetch(uTex, clamp(p + o12, ivec2(0), mx), 0);
    vec4 b12 = texelFetch(uTex, clamp(p - o12, ivec2(0), mx), 0);
    float c3a = abs(a3.r - a3.g) + abs(a3.b - a3.g);
    float c3b = abs(b3.r - b3.g) + abs(b3.b - b3.g);
    float c6a = abs(a6.r - a6.g) + abs(a6.b - a6.g);
    float c6b = abs(b6.r - b6.g) + abs(b6.b - b6.g);
    float c12a = abs(a12.r - a12.g) + abs(a12.b - a12.g);
    float c12b = abs(b12.r - b12.g) + abs(b12.b - b12.g);
    // ── v0.10.206：判据改为"**局部色度峰**" ─────────────────────────────────
    // 原判据要求"两侧色度都 < sideK×中心"。真机实测（150917 成片 y=340 剖面）
    // 该紫线峰值 ch0=41 而**右侧背景本身就有色度 30** ⇒ 原判据永远不成立 ⇒
    // 漏检（这正是 205 前置后紫线仍在的原因）。
    // 新判据 = 中心是**局部色度峰**：ch0 > peakK × max(两侧)，且至少一侧显著低
    //（min(两侧) < sideK × ch0）—— 前者排除"色度渐变区"，后者排除"真实色度
    // 边缘"（真实边缘两侧都有色度、中心不是峰）。
    // 实测（成片，尺度 [3,6,12]）：peakK=1.05/sideK=0.5 → 色度总量 **−1.25%**
    //（204 的宽带判据是 −6%）、紫向边缘覆盖 23.1%、蓝向 16.6%。
    float cs[6];
    bool satp[6];
    cs[0] = c3a;   satp[0] = a3.a > 0.5;
    cs[1] = c3b;   satp[1] = b3.a > 0.5;
    cs[2] = c6a;   satp[2] = a6.a > 0.5;
    cs[3] = c6b;   satp[3] = b6.a > 0.5;
    cs[4] = c12a;  satp[4] = a12.a > 0.5;
    cs[5] = c12b;  satp[5] = b12.a > 0.5;
    float mx_side = -1.0;
    float mn_side = 1.0e9;
    int nv = 0;
    for (int i = 0; i < 6; ++i) {
        if (satp[i]) continue;      // clip 白的 ch≈0 是"信息缺失"，不是"中性"
        mx_side = max(mx_side, cs[i]);
        mn_side = min(mn_side, cs[i]);
        ++nv;
    }
    if (nv == 0) { oColor = vec4(c0.rgb, c0.a); return; }  // 全是饱和核：无真值，不动
    bool side_ok = (mx_side < uSideK * ch0) && (nv >= 4);
    bool peak_ok = (ch0 > uPeakK * mx_side) && (mn_side < uSideK * ch0) && (nv >= 2);
    if (!(side_ok || peak_ok)) {
        oColor = vec4(c0.rgb, c0.a);
        return;
    }
    // ── v0.10.207：替换值改为"**最中性的那个探测点**"（不再是 6 点平均）──────
    // 真机实测（152300 成片）紫边峰值 ch=47 而两侧是 **33 / 0**（一侧带色、
    // 一侧中性）⇒ 6 点平均 = 16.5，**色度只降到 1/3，肉眼仍见彩边**（这正是
    // 206 判据命中却"还是明显"的原因）。改用最中性探测点的色差作目标值 ⇒
    // 色度降到 ~0。判据已保证 min(两侧) < 0.5×ch0，故最中性点必然比中心
    // 中性得多，不会把远处真实色度搬进来。
    // ── v0.10.209：替换策略改回**去饱和**（不再搬"最中性探测点"的色度）──────
    // v0.10.207 用最中性点的色差作目标值，在"该点落在有色背景上"时会把背景色
    // **搬进**被处理像素（真机实测把中性灰染成蓝色）。去饱和只压本像素自己的
    // 色度（保色相、保亮度），**不引入任何外来色度** ⇒ 结构性安全。
    // 保留 15% 色度（k_desat=0.15）：完全归零会在色度渐变区留下可见的"灰线"。
    const float k_desat = 0.15;
    float r_t = (c0.r - c0.g) * k_desat;
    float b_t = (c0.b - c0.g) * k_desat;
    vec3 med = vec3(max(0.0, r_t + c0.g), c0.g, max(0.0, b_t + c0.g));
    float c_med = abs(med.r - c0.g) + abs(med.b - c0.g);
    vec3 comp = med + (dot(c0.rgb, LW) - dot(med, LW));
    // ── 0.12.158：**撤销 156 的「±12 端点亮度插值」** ───────────────────────
    // 156 对"贴饱和核"的命中像素改用 ±12 端点按亮度插值，真机反馈：
    //   · 「高光混入背景色，看着怪」—— 端点落在背景上时会把背景色搬进高光边缘
    //     （与 v0.10.207 的教训同一个坑，只是端点更远）
    //   · 灰块更严重 —— 插值把背景的中性色摊到高光边缘
    // 撤销理由成立：**它是在错误的位置修错误的东西**。真正的洗色度发生在 P0
    // （0.12.157 已修），D4 层不该再引入外来色度。恢复"只压本像素自己的色度"。
    if (c_med >= ch0) { oColor = vec4(c0.rgb, c0.a); return; }
    oColor = vec4(clamp(mix(c0.rgb, comp, gate), vec3(0.0), vec3(16.0)), c0.a);
})";

// ── 0.12.162：色差域 3×3 中值（darktable color_smoothing 原版）──────────────
// 为什么加它（真机 2026-10-29 09:47 阳台地砖，护栏阴影边缘的紫/粉光晕）：
//   同一 DNG 离线对照（rawpy AHD 2.50% vs 我们 AMaZE+CCM 5.37% 的"紫像素占比"）
//   ⇒ 紫色是在 **D4（去马赛克+CCM）** 就有的，不是降噪链造的；而现有
//   color_smooth 只替 0.05% 的像素 —— 它的梯度门 `glen=|∇L|·255 > 6` 是按
//   显示域标定的，**在暗部（L≈0.05、∇≈0.01 ⇒ glen≈2.5）永远打不开**。
//   阴影正是暗部 ⇒ 判据式去饱和在这个场景结构性失效。
// 为什么是中值：darktable `demosaicing/basics.c::color_smoothing()` 的原版做法 ——
//   在 (R−G)、(B−G) 色差域做 3×3 中值、G 逐位不变、R/B = 中值 + G。中值**不判色相、
//   不判幅度**，只假设"1~3px 的色度异常是窄带"；对真实的色度阶跃是保边的。
//   离线实测（同一地砖 D4，650×700）：紫像素 5.86% → 4.15%（2 次），
//   平均色度只从 0.01258 掉到 0.01227（−2.5%）—— 压伪彩的同时几乎不动真实色度。
// 与 color_smooth 的分工：那段保留（判据式去饱和，管"强边缘 + 局部色度峰"），
//   本段管"暗部/低梯度的宽域色度过冲"。串联，互不替代。
const char* kFsColorMedian = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform ivec2 uSize;
uniform float uMinChroma;   // 相对亮度的色度下限（中性区不采样）
const vec3 LW = vec3(0.2126, 0.7152, 0.0722);
void main() {
    ivec2 p = clamp(ivec2(gl_FragCoord.xy), ivec2(0), uSize - 1);
    ivec2 mx = uSize - 1;
    vec4 c0 = texelFetch(uTex, p, 0);
    float lum = max(dot(c0.rgb, LW), 1e-4);
    float ch0 = abs(c0.r - c0.g) + abs(c0.b - c0.g);
    if (ch0 <= uMinChroma * lum) { oColor = c0; return; }
    float dr[9];
    float db[9];
    int k = 0;
    for (int y = -1; y <= 1; ++y)
        for (int x = -1; x <= 1; ++x) {
            vec4 c = texelFetch(uTex, clamp(p + ivec2(x, y), ivec2(0), mx), 0);
            dr[k] = c.r - c.g;
            db[k] = c.b - c.g;
            ++k;
        }
    // 排序内联（不用数组参数：部分 ES 3.0 驱动对 in 数组参数支持不完整）
    for (int i = 0; i < 9; ++i)
        for (int j = i + 1; j < 9; ++j) {
            if (dr[j] < dr[i]) { float t = dr[i]; dr[i] = dr[j]; dr[j] = t; }
            if (db[j] < db[i]) { float t2 = db[i]; db[i] = db[j]; db[j] = t2; }
        }
    float g = c0.g;
    vec3 v = vec3(g + dr[4], g, g + db[4]);
    // 保亮度：三通道等量加法（色差加同一标量后不变，与 color_smooth 同式）
    v += dot(c0.rgb, LW) - dot(v, LW);
    oColor = vec4(clamp(v, vec3(0.0), vec3(16.0)), c0.a);
})";


// 对标 PhotonCamera FullResolutionDenoise（多尺度金字塔 + luma/chroma 独立 +
// detail revert factor）与 darktable wavelet denoise：undecimated à trous 分解
// （B3 样条、逐级 dilate 2^l）+ Wiener 逐点收缩 + detail reconstruction。
// 在 **1/2 分辨率**上做：色斑是 8-32px 低频，半分辨率足够，显存/成本降到 1/4。
// 离线镜像（test_temp/analysis/atrous_tune.py 半分辨率验证）：
//   原始 σC 1/4/8/16/32px = 9.87/8.29/5.99/3.15/1.78；PC = 1.56/1.08/0.94/0.66/0.56
//   L=5 k=8 revert=0.08 → 1.01/0.86/0.62/0.34/0.35（**全尺度优于 PC**）
//   边缘等效宽度 4.05 vs PC 4.11（细节不退化）
// 分级职责：P4（kFsChromaDenoise，半径 3）管 1-3px 彩点；本 pass 管 4-32px 色斑。
// ① 全分辨率 → 半分辨率：Cb/Cr 2×2 box 平均 + 该处噪声 σ（方差平均后 ÷2）
// ── 横向色差校正（v0.10.170）──────────────────────────────────────────────
// ① 估计输入：D3（RCD 后 / CCM 前）1/8 box 降采样。梯度尺度 8px ⇒ 可测 ±4px 位移，
// 覆盖真实横向色差量级；8×8 均值同时把估计噪声压到 1/8。
const char* kFsCaDown = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform ivec2 uSize;
out vec4 fragColor;
const int kDs = 8;
void main() {
  ivec2 o = ivec2(gl_FragCoord.xy);
  ivec2 base = o * kDs;
  ivec2 mx = uSize - ivec2(1);
  vec3 s = vec3(0.0);
  for (int j = 0; j < kDs; ++j) {
    for (int i = 0; i < kDs; ++i) {
      s += texelFetch(uTex, clamp(base + ivec2(i, j), ivec2(0), mx), 0).rgb;
    }
  }
  fragColor = vec4(s * (1.0 / float(kDs * kDs)), 1.0);
}
)";

// ② 校正：只重采样 R/B，G 逐位原样（G 是参考通道）。采样点 =
// p + r̂·clamp(k·r, ±uMaxShift)：k>0 表示该通道相对 G 向外偏移（放大率更大），
// 需向外取样才能把它拉回与 G 对齐。RGBA16F 在本项目驱动上无线性过滤
//（makeTex 的 X300 quirk：RGBA16F 只有 NEAREST），故双线性在此手写
//（4 次 texelFetch + mix）；边缘按纹素中心范围 clamp（等价 CLAMP_TO_EDGE）。
const char* kFsCaCorr = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform ivec2 uSize;
uniform vec2 uCenter;
uniform float uKR;
uniform float uKB;
uniform float uMaxShift;
out vec4 fragColor;
vec3 bilerp(ivec2 t0, vec2 f, ivec2 mx) {
  ivec2 t1 = min(t0 + ivec2(1), mx);
  vec3 a = texelFetch(uTex, t0, 0).rgb;
  vec3 b = texelFetch(uTex, ivec2(t1.x, t0.y), 0).rgb;
  vec3 c = texelFetch(uTex, ivec2(t0.x, t1.y), 0).rgb;
  vec3 d = texelFetch(uTex, t1, 0).rgb;
  return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
void main() {
  ivec2 ip = ivec2(gl_FragCoord.xy);
  vec4 s = texelFetch(uTex, ip, 0);
  vec3 c = s.rgb;
  vec2 d = gl_FragCoord.xy - uCenter;
  float r = length(d);
  if (r > 1.0) {
    vec2 rh = d / r;
    ivec2 mx = uSize - ivec2(1);
    vec2 lim = vec2(uSize) - vec2(0.5);
    vec2 pR = clamp(gl_FragCoord.xy + rh * clamp(uKR * r, -uMaxShift, uMaxShift),
                    vec2(0.5), lim);
    vec2 pB = clamp(gl_FragCoord.xy + rh * clamp(uKB * r, -uMaxShift, uMaxShift),
                    vec2(0.5), lim);
    vec2 qR = pR - vec2(0.5);
    vec2 qB = pB - vec2(0.5);
    ivec2 tR = clamp(ivec2(floor(qR)), ivec2(0), mx);
    ivec2 tB = clamp(ivec2(floor(qB)), ivec2(0), mx);
    c.r = bilerp(tR, fract(qR), mx).r;
    c.b = bilerp(tB, fract(qB), mx).b;
  }
  // alpha 透传 RAW 饱和掩码（0.12.133）：CA 只重采样 R/B，掩码与位置无关
  fragColor = vec4(c, s.a);
}
)";

const char* kFsChromaDown = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oChroma;
layout(location = 1) out vec4 oSigma;
uniform highp sampler2D uTex;
uniform ivec2 uSize;
uniform vec3 uNoiseA;
uniform vec3 uNoiseB;
uniform highp sampler2D uLsc;
uniform ivec2 uLscSize;
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
vec3 lscAt(ivec2 p) {
    vec2 g = ((vec2(p) + 0.5) / vec2(uSize)) * vec2(uLscSize) - 0.5;
    ivec2 b = ivec2(floor(g));
    vec2 t = g - vec2(b);
    ivec2 mx = uLscSize - 1;
    vec3 c00 = texelFetch(uLsc, clamp(b, ivec2(0), mx), 0).rgb;
    vec3 c10 = texelFetch(uLsc, clamp(b + ivec2(1, 0), ivec2(0), mx), 0).rgb;
    vec3 c01 = texelFetch(uLsc, clamp(b + ivec2(0, 1), ivec2(0), mx), 0).rgb;
    vec3 c11 = texelFetch(uLsc, clamp(b + ivec2(1, 1), ivec2(0), mx), 0).rgb;
    return mix(mix(c00, c10, t.x), mix(c01, c11, t.x), t.y);
}
// ── D-1 σ 双路修复（Cb/Cr 分离）────────────────────────────────────────
// 投影到 (Cb, Cr) 的权重 = 行向量的**平方**：
//   B−Y = −0.2126R − 0.7152G + 0.9278B ⇒ (0.0452, 0.5115, 0.8608)，Σ = 1.4175
//   R−Y = +0.7874R − 0.7152G − 0.0722B ⇒ (0.6200, 0.5115, 0.0052)，Σ = 1.1367
// **两路本不相等**，而旧实现给两路共用同一个 `Σᵢwᵢσᵢ²`（luma 合并恒 = 1.0）——
// 这是"彩噪偏蓝紫"的算术来源：Cb 路径的 σ 被低估最多，降噪最弱。
// 与 P4 的区别：P4 在**三维 cd 向量**上取 length（三通道一起），这里是**两条
// 独立标量路径**，故必须逐路给 σ（P4 的合并口径是另一个问题，见 σ 核实报告）。
const vec3 WCB2 = vec3(0.0452, 0.5115, 0.8608);
const vec3 WCR2 = vec3(0.6200, 0.5115, 0.0052);
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy) * 2;
    vec2 acc = vec2(0.0);
    vec2 sacc = vec2(0.0);          // 逐路 (Σσ_Cb, Σσ_Cr)
    float yacc = 0.0;               // v0.10.173：2×2 的 Y（梯度门引导，见下）
    for (int i = 0; i < 4; ++i) {
        ivec2 q = clamp(p + ivec2(i & 1, i >> 1), ivec2(0), uSize - 1);
        vec3 c = texelFetch(uTex, q, 0).rgb;
        float y = dot(c, LUMA);
        yacc += y;
        acc += vec2(c.b - y, c.r - y);
        // 逐通道方差 σᵢ² = Aᵢ·cᵢ + Bᵢ·gᵢ²：shot 项随**该通道**信号；read 项不随
        // 信号但经**该通道**的 LSC 增益放大。旧实现把两者都折成 luma，丢掉了
        // 通道差异（WB 增益把三通道噪声拉开后，这个差异正是要修的东西）。
        vec3 gl = lscAt(q);
        vec3 v = max(uNoiseA * c + uNoiseB * gl * gl, vec3(0.0));
        sacc += sqrt(max(vec2(dot(v, WCB2), dot(v, WCR2)), vec2(1e-12)));
    }
    // b 通道 = 半分辨率 Y（v0.10.173）：kFsChromaAtrous 的梯度门需要 |∇L|，而它的
    // uPrev 只有 Cb/Cr ⇒ 这里顺带把 2×2 的 Y 均值带下去（零新增纹理、零新增采样）。
    oChroma = vec4(acc * 0.25, yacc * 0.25, 1.0);
    // 2×2 box 平均使白噪 σ 减半：σ_half = mean(σ_full)/2（两路同一规则）
    oSigma = vec4(sacc * 0.125, 0.0, 1.0);
})";

// ② 每级：a_l = B3_dilated(a_{l-1}, step)；d = a_{l-1} − a_l；收缩 + revert
//    MRT 输出 (a_l, dp_l)；σ 逐像素取自 ① 的 σ 图（乘 uK）
const char* kFsChromaAtrous = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oNext;
layout(location = 1) out vec4 oDetail;
uniform highp sampler2D uPrev;
uniform highp sampler2D uSigma;
uniform ivec2 uSize;
uniform int uStep;
uniform float uK;
uniform float uRevert;
uniform float uCgK;    // v0.10.174：强边缘色度门强度（0 = 关，逐位回到改动前）
const float kCgG0 = 0.10;   // 门的下阈（半分辨率 Y 的中心差分，与 P4 同一常量）
const float kCgG1 = 0.30;   // 门的上阈
const float kCgRel = 16.0;  // 放宽上限 = 16·σ（相对；旧版绝对 0.2 是 100% 覆盖的来源）
const float B3[5] = float[5](0.0625, 0.25, 0.375, 0.25, 0.0625);
void main() {
    ivec2 p = clamp(ivec2(gl_FragCoord.xy), ivec2(0), uSize - 1);
    vec4 p0 = texelFetch(uPrev, p, 0);
    vec2 a0 = p0.rg;
    vec2 s = vec2(0.0);
    for (int i = 0; i < 5; ++i)
        for (int j = 0; j < 5; ++j) {
            ivec2 q = clamp(p + ivec2((j - 2) * uStep, (i - 2) * uStep),
                            ivec2(0), uSize - 1);
            s += (B3[i] * B3[j]) * texelFetch(uPrev, q, 0).rg;
        }
    vec2 d = a0 - s;
    // σ **逐路**（Cb/Cr 的噪声方差本不相等；见 kFsChromaDown 的 WCB2/WCR2）。
    // 旧实现取 .r 单标量给两路共用 ⇒ Cb 路径欠降噪。
    vec2 sig = max(texelFetch(uSigma, p, 0).rg * uK, vec2(1e-6));
    // ── v0.10.174：强边缘门 v2（**只在前两级**，且有界 + 逐像素门）──────────
    // 引导量 = 半分辨率 Y 的中心差分（步长 uStep，等效全分辨率 2·uStep px）。
    // v0.10.173 在这里用了 PC 的**绝对** cap 0.2 且不限级别 ⇒ 5 级全部 100% 覆盖
    //（离线实测 d1gate = [100,100,100,100,100]），每一级的 detail 被整片抹掉
    // ⇒ 输出色度 ≈ 最粗尺度（32 px）的均值 ⇒ **"一片红一片黄一片紫"**。
    // 离线归因（152207，同一链）：只 D-1 的门 hue15blk = 12.65%（≈ 现状 11.90%），
    // 只 P4 的门 = 3.20%，全关 = 0%。
    // v2 的两条修正：
    //   ① **uStep ≤ 2**：紫边是 1~3 px 的现象，与 step 1/2（= 2/4 px）同尺度；
    //      大尺度级（step 8/16 → 16/32 px）抹掉的是真实色度结构，不产生紫边收益
    //      只产生色块（实测：放开全级多降 8% 紫边，flatRG 从 −0.7% 恶化到 −4.6%）。
    //   ② **有界 + 门控**：放宽量 = min(κ·|∇L|, 16·σ)·smoothstep(kCgG0,kCgG1,|∇L|)
    //      —— 上限由绝对 0.2 改为相对 16·σ，且平坦区严格 0。
    // uCgK = 0 时整段不执行（sig 逐位不变）。
    if (uCgK > 0.0 && uStep <= 2) {
        ivec2 mx = uSize - 1;
        float yL = texelFetch(uPrev, clamp(p - ivec2(uStep, 0), ivec2(0), mx), 0).b;
        float yR = texelFetch(uPrev, clamp(p + ivec2(uStep, 0), ivec2(0), mx), 0).b;
        float yU = texelFetch(uPrev, clamp(p - ivec2(0, uStep), ivec2(0), mx), 0).b;
        float yD = texelFetch(uPrev, clamp(p + ivec2(0, uStep), ivec2(0), mx), 0).b;
        float gmag = 0.5 * length(vec2(yR - yL, yD - yU));
        float cgGate = smoothstep(kCgG0, kCgG1, gmag);
        sig = max(sig, min(vec2(uCgK * gmag), kCgRel * sig) * cgGate);
    }
    vec2 dp = d * (d * d) / (d * d + sig * sig + vec2(1e-12));
    dp += uRevert * (d - dp);          // detail reconstruction（PC revert factor）
    oNext = vec4(s, p0.b, 1.0);        // b = Y 原样传下去（梯度门引导，不参与滤波）
    oDetail = vec4(dp, 0.0, 1.0);
})";

// ③ 收口：色度 Cb/Cr = a_L + Σ dp_l（手动双线性上采样，RGBA16F 无线性过滤）
//    + **亮度各向异性长核**（D-2，v0.10.54）：结构张量切向采样 + 逐像素噪声
//    阈值（σ(x)=√(A·x+B)，暗部自适应）。离线实测（L=16/kr=3.5，同一 4 帧口径）：
//    σY 暗 2.87→1.08 / 中 2.03→1.12 / 亮 1.44→0.55（PC 0.72/0.94/0.63），
//    边缘等效宽度 4.86 vs PC 4.62。并入本 pass = 不新增纹理与 pass。
const char* kFsChromaApply = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform highp sampler2D uCoarse;
uniform highp sampler2D uD0;
uniform highp sampler2D uD1;
uniform highp sampler2D uD2;
uniform highp sampler2D uD3;
uniform highp sampler2D uD4;
uniform ivec2 uSize;
uniform ivec2 uHalf;
uniform vec3 uNoiseA;
uniform vec3 uNoiseB;
uniform highp sampler2D uLsc;
uniform ivec2 uLscSize;
uniform float uKr;     // 值域阈值系数（× 逐像素 σ）
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
vec2 sampleUp(highp sampler2D t, vec2 uv) {
    vec2 g = uv * vec2(uHalf) - 0.5;
    ivec2 i0 = ivec2(floor(g));
    vec2 f = g - vec2(i0);
    ivec2 mx = uHalf - 1;
    vec2 c00 = texelFetch(t, clamp(i0, ivec2(0), mx), 0).rg;
    vec2 c10 = texelFetch(t, clamp(i0 + ivec2(1, 0), ivec2(0), mx), 0).rg;
    vec2 c01 = texelFetch(t, clamp(i0 + ivec2(0, 1), ivec2(0), mx), 0).rg;
    vec2 c11 = texelFetch(t, clamp(i0 + ivec2(1, 1), ivec2(0), mx), 0).rg;
    return mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y);
}
vec3 lscAt(ivec2 p) {
    vec2 g = ((vec2(p) + 0.5) / vec2(uSize)) * vec2(uLscSize) - 0.5;
    ivec2 b = ivec2(floor(g));
    vec2 t = g - vec2(b);
    ivec2 mx = uLscSize - 1;
    vec3 c00 = texelFetch(uLsc, clamp(b, ivec2(0), mx), 0).rgb;
    vec3 c10 = texelFetch(uLsc, clamp(b + ivec2(1, 0), ivec2(0), mx), 0).rgb;
    vec3 c01 = texelFetch(uLsc, clamp(b + ivec2(0, 1), ivec2(0), mx), 0).rgb;
    vec3 c11 = texelFetch(uLsc, clamp(b + ivec2(1, 1), ivec2(0), mx), 0).rgb;
    return mix(mix(c00, c10, t.x), mix(c01, c11, t.x), t.y);
}
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 c = texelFetch(uTex, p, 0).rgb;
    float y = dot(c, LUMA);
    vec2 uv = (vec2(p) + 0.5) / vec2(uSize);
    vec2 ch = sampleUp(uCoarse, uv) + sampleUp(uD0, uv) + sampleUp(uD1, uv) +
              sampleUp(uD2, uv) + sampleUp(uD3, uv) + sampleUp(uD4, uv);
    // ── 亮度各向异性核（v0.10.56 起**默认关闭**；v0.10.57 CPU 侧把 uL 置 0 ⇒ 整段
    // 恒等于 dyn≡0；第 1b 期已删除该段，此处仅留事故记录）────────────────────
    // 事故记录：0.10.54（L=16 无条件）与 0.10.55（L=6 + coherence 门控）两版
    // 真机的 8px 尺度纹理能量都掉（0.10.55 同刻实测 3.17 vs PC 3.59 = −12%），
    // 用户两次判定"画面比 PC 糊"。根因是**单帧去马赛克后**的空间核在噪声与
    // 中尺度纹理同量级时必然二选一 —— 收益（σY 2.87→1.30）是拿纹理买的，
    // 细节优先是红线，故整条路径停用。真正的杠杆在 merge 阶段的多帧空间核
    //（PC：每帧 3×3 quad × 4 帧 = 加样本不加空间模糊），待那边落地后以
    //"小核 + 多帧"的形态重写，而不是这里放大核。
    vec3 o = vec3(y + ch.y, 0.0, y + ch.x);   // R = Y+Cr, B = Y+Cb
    // ── 第 5c 期（S2 · 反推收口加闸）──────────────────────────────────
    // G 是**反解**出来的残差：R/B 独立给出，G 由 Y 守恒倒推 —— 当 R/B 的色度
    // 把 Y 的预算吃满时 G 变负。离线实测（真实样片，4b 期基建）：负 G 像素占
    // 0.0046%~0.0673%，而 4b 的迁移实验显示负 G 会放大 6.5~9 倍 ⇒ 必须加闸。
    // 本闸 = 原 S2 计划中"第 4b 期先加 max(·,0) 下限"那一步，因 4b 被离线预验证
    // 否决而并入本期（设计文档 §5 第 5c 期）。
    // ⚠ 已知代价：clamp 后 Y 不再严格守恒（这些像素整体偏亮）。这是有意取舍 ——
    //    负 G 无物理意义，且下游 filmic/gamut 本来就会截断；相比之下"让非法值
    //    流下去"更糟。R/B 的负值未处理（实测极少见）。
    // 后续（独立项，未排期）：S2 完整解是"**不丢 G 的对称组合**"—— 即重构色度
    // 参数化，让三通道对称参与滤波，而不是让 G 当残差（设计文档 §3.4 第 10 项）。
    o.g = max((y - 0.2126 * o.r - 0.0722 * o.b) / 0.7152, 0.0);
    oColor = vec4(o, 1.0);
})";

// ══ PC ESD3D2 完整移植（v0.10.175）：核尺寸 + 尺度结构 + 伪彩强度逻辑 ═══════
// 一手来源（逐行读，不凭记忆）：
//   /tmp/pc/app/src/main/assets/shaders/denoise/esd3d2_steered.glsl（PC 默认档）
//   /tmp/pc/app/src/main/java/.../postpipeline/ESD3D2.java（核尺寸公式 + 两趟编排）
//   /tmp/pc/app/src/main/assets/shaders/utils/convdiff.glsl（GradBuffer 的算子）
//   /tmp/pc/app/src/main/assets/shaders/denoise/guidedupsample.glsl（尺度结构收口）
//   /tmp/pc/app/src/main/java/.../opengl/GLUtils.java:238-280（gaussdown，k 倍）
// 与我们现状（P4 7×7 双边 + D-1 半分辨率 5 级 à trous）差三件事，本块**全部搬**：
//   ① **核尺寸**：MSIZE×MSIZE，且**对称折叠**（一个 (i,j) 格同时取 4 个象限点，
//      四象限共享权重）；MSIZE 由噪声模型按 PC 公式算出（7..21），不是固定 7×7；
//   ② **尺度结构**：噪声自适应降采样（scale 1..4）→ 低分辨率先降噪（MOIRE=0）
//      → guided upsample 回全分辨率 → 全分辨率第二趟（MOIRE=1.5）；
//   ③ **伪彩强度逻辑**：chromaDiff =（2×2 quad 的四点非均匀度均值）×
//      **convdiff 梯度幅值 max(|Δx|,|Δy|)** ⇒ 取 3 通道**极差** × MOIRE ⇒
//      **绝对 cap 0.2** ⇒ **只进色度路 sigZ**；亮度路 sigY 恒 = 噪声方差模型
//      ⇒ 强边缘处色度被整核（≤21px）平均，而亮度逐点保真。
// 输出 = (色度路归一化到 luma=1) × br，br = mix(中心 luma, 亮度路 luma, LUMA)。
// ⚠ 机型相关输入（**不照抄数值**）：PC 的 NOISES/NOISEO 来自它自己的
//   NoiseModeler（方差模型，已按帧数折算）。我们的等价物 = 本链的方差模型
//   （uSigA/uSigB = A/B 按 PC 的 (0.25,0.5,0.25) 权重合并，含 LSC 传播）。
// ⚠ 与 PC 的实现差异（非算法删减，逐位等价）：
//   ① PC 用独立 pass 产出 GradBuffer（RG16F 全分辨率 ≈48MB），本实现把同一
//      convdiff 算子内联（缓存 5×5 的 sum3 luma = 25 次采样，9 个梯度点全部由
//      它组合）⇒ 省 1 个全屏 pass + 1 张 RG16F；
//   ② 主循环上界写常量 10（MSIZE ≤ 21 ⇒ KSIZE ≤ 10）+ break，避免非恒定循环
//      上界在某些移动驱动上的编译风险（执行格数与动态上界一致）。
const char* kFsPc3d = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform ivec2 uSize;
uniform int uK;          // KSIZE = (MSIZE-1)/2（PC 的 #define KSIZE）
uniform float uKernel;   // KERNELSIZE = 1 + sqrt(σ²/noiseTarget)·noiseToKernel
uniform float uSigA;     // sigY 斜率（A/B 按 PC 的 (0.25,0.5,0.25) 权重合并）
uniform float uSigB;     // sigY 截距（read 项，含 LSC 增益²）
uniform float uMoire;    // MOIRE（PC Tunable；pass1 = 0、pass2 = 1.5）
uniform float uLuma;     // LUMA（PC Tunable，默认 0.8）
uniform highp sampler2D uLsc;
uniform ivec2 uLscSize;
const vec3 LW3 = vec3(0.25, 0.5, 0.25);        // esd3d2_steered.glsl:64/157 的权重
const vec3 SUM3 = vec3(0.299, 0.587, 0.114);   // convdiff.glsl:15 的 sum3
const float kCap = 0.2;                        // esd3d2_steered.glsl:98 的绝对 cap
const float kSeed = 0.01;                      // esd3d2_steered.glsl:100-101 的 Z/Z2
float normpdf(float x, float sigma) {
    return 0.39894 * exp(-0.5 * x * x / (sigma * sigma)) / sigma;
}
// ── 0.12.143：低分辨率首趟的**样本排除**（只在这一趟启用）──────────────────
// 真机（142）：光晕像素本身其实是中性（R/G 1.03 / B/G 1.00），绿是粉底上的
// 色彩诱导；但它被 PC3D 摊成 3~10px 的雾，范围一大就压不住了。
// scale=2 时的链是 gaussdown → **低分辨率平滑** → guided upsample：高光色度在
// 1/2 域被平均，再被上采样摊到全分辨率，这就是"雾"的宽度来源。
// 所以只在**首趟**把高光样本的权重排掉（第二趟保持 132 的逐位行为 → 亮部色噪
// 不会被放大，135 的 3.5× 回归不会重演）。
uniform highp sampler2D uOrig;   // 全分辨率原图（mask 在 alpha）
uniform int uMaskOn;
uniform int uDown;               // 本趟坐标 → 全分辨率坐标的倍数
uniform ivec2 uFullSize;
// ── 0.12.181：独立饱和掩码（R8，全分辨率）—— 优先于 uOrig.a ─────────────────
// 为什么必须独立：D4 的 alpha 是 CCM 透传的块级掩码（3×3 photosite 块 + darktable
// 大膨胀），在天空的零星饱和处会给出**块状**收手区 ⇒ 该区不降噪 ⇒ 方块亮斑
//（真机 17:14/17:20 天空两处）。本纹理是逐 photosite 的物理饱和判定（满阱−3σ），
// 经 1px 膨胀 + 2×2 聚合，边界与真实饱和核对齐。
uniform highp sampler2D uSatMask;
uniform int uSatMaskOn;
float maskAt(ivec2 p) {
    ivec2 q = clamp(p * uDown, ivec2(0), uFullSize - 1);
    if (uSatMaskOn > 0) return texelFetch(uSatMask, q, 0).r;
    if (uMaskOn == 0) return 0.0;
    return clamp(texelFetch(uOrig, q, 0).a, 0.0, 1.0);
}
vec3 lscAt(ivec2 p) {
    vec2 g = ((vec2(p) + 0.5) / vec2(uSize)) * vec2(uLscSize) - 0.5;
    ivec2 b = ivec2(floor(g));
    vec2 t = g - vec2(b);
    ivec2 mx = uLscSize - 1;
    vec3 c00 = texelFetch(uLsc, clamp(b, ivec2(0), mx), 0).rgb;
    vec3 c10 = texelFetch(uLsc, clamp(b + ivec2(1, 0), ivec2(0), mx), 0).rgb;
    vec3 c01 = texelFetch(uLsc, clamp(b + ivec2(0, 1), ivec2(0), mx), 0).rgb;
    vec3 c11 = texelFetch(uLsc, clamp(b + ivec2(1, 1), ivec2(0), mx), 0).rgb;
    return mix(mix(c00, c10, t.x), mix(c01, c11, t.x), t.y);
}
void main() {
    ivec2 mx = uSize - ivec2(1);
    ivec2 xy = clamp(ivec2(gl_FragCoord.xy), ivec2(0), mx);
    // ① GradBuffer 的等价物：convdiff.glsl:33-36（TEXSIZE==3）的算子
    //    g = (sum3(x−1) − sum3(x+1))/2。9 个梯度点需要 5×5 的 sum3 luma（25 个
    //    唯一坐标），先缓存再组合 ⇒ 采样数与 PC 的「1 次梯度 pass + 9 次读回」同量级。
    float L25[25];
    for (int j = 0; j < 5; ++j)
        for (int i = 0; i < 5; ++i)
            L25[j * 5 + i] = dot(texelFetch(
                uTex, clamp(xy + ivec2(i - 2, j - 2), ivec2(0), mx), 0).rgb, SUM3);
    vec2 g9[9];
    for (int j = -1; j <= 1; ++j)
        for (int i = -1; i <= 1; ++i) {
            float yl = L25[(j + 2) * 5 + (i + 1)];
            float yr = L25[(j + 2) * 5 + (i + 3)];
            float yu = L25[(j + 1) * 5 + (i + 2)];
            float yd = L25[(j + 3) * 5 + (i + 2)];
            g9[(j + 1) * 3 + (i + 1)] = vec2((yl - yr) * 0.5, (yu - yd) * 0.5);
        }
    // ② 2×2 quad 与 chromaDiff 的基准（esd3d2_steered.glsl:59-63）
    vec3 cin = texelFetch(uTex, xy, 0).rgb;
    vec3 cinX = texelFetch(uTex, clamp(xy + ivec2(1, 0), ivec2(0), mx), 0).rgb;
    vec3 cinY = texelFetch(uTex, clamp(xy + ivec2(0, 1), ivec2(0), mx), 0).rgb;
    vec3 cinXY = texelFetch(uTex, clamp(xy + ivec2(1, 1), ivec2(0), mx), 0).rgb;
    vec3 cavg = (cin + cinX + cinY + cinXY) * 0.25;
    float noisefactor = dot(cin, LW3);
    // ③ 结构张量（:68-87）：xDelta/yDelta = 3×3 的 length(g) 一阶矩 / 3；
    //    sxx/sxy/syy → 相干度 coh（平方）与切向单位向量 tng。
    float xDelta = 0.0;
    float yDelta = 0.0;
    float sxx = 0.0;
    float sxy = 0.0;
    float syy = 0.0;
    for (int i = -1; i <= 1; ++i) {
        for (int j = -1; j <= 1; ++j) {
            vec2 g = g9[(j + 1) * 3 + (i + 1)];
            xDelta += float(i) * length(g);
            yDelta += float(j) * length(g);
            sxx += g.x * g.x;
            sxy += g.x * g.y;
            syy += g.y * g.y;
        }
    }
    float tr = sxx + syy;
    float dlt = sqrt(max((sxx - syy) * (sxx - syy) + 4.0 * sxy * sxy, 0.0));
    float lam1 = 0.5 * (tr + dlt);
    float lam2 = max(0.5 * (tr - dlt), 0.0);
    float coh = (lam1 - lam2) / max(lam1 + lam2, 0.000001);
    coh *= coh;
    vec2 tng = vec2(sxy, lam1 - sxx);
    tng = mix(tng, vec2(1.0, 0.0), step(dot(tng, tng), 0.000000000001));
    tng /= max(length(tng), 0.000001);
    xDelta /= 3.0;
    yDelta /= 3.0;
    // ④ 双路阈值（:92-98）：sigY = 噪声**方差**；sigZ = max(sigY,
    //    min(|chromaNoise|·MOIRE, 0.2))，chromaNoise = 3 通道 chromaDiff 的极差，
    //    chromaDiff 先被 convdiff 梯度幅值缩放（伪彩强度逻辑的核心）。
    vec3 gl = lscAt(xy);
    float glY = dot(gl, LW3);
    float sigY = max(uSigA * noisefactor + uSigB * glY * glY, 0.0000001);
    vec3 chromaDiff = (abs(cavg - cinX) + abs(cavg - cinY) +
                       abs(cavg - cinXY) + abs(cavg - cin)) * 0.25;
    chromaDiff *= max(abs(xDelta), abs(yDelta));
    float chromaNoise = max(chromaDiff.r, max(chromaDiff.g, chromaDiff.b)) -
                        min(chromaDiff.r, min(chromaDiff.g, chromaDiff.b));
    float sigZ = max(sigY, min(abs(chromaNoise) * uMoire, kCap));
    // ⑤ 自引导参考 gref（:104-117）：3×3 相似度加权均值，权重形状与主滤波同。
    vec3 gref = vec3(0.0);
    float gW = 0.0;
    for (int i = -1; i <= 1; ++i) {
        for (int j = -1; j <= 1; ++j) {
            ivec2 qg = clamp(xy + ivec2(i, j), ivec2(0), mx);
            vec3 c = texelFetch(uTex, qg, 0).rgb;
            float dd = length(abs(c - cin));
            float ww = max(0.0, 1.0 - dd * dd / (dd * dd + sigY))
                     * (1.0 - maskAt(qg));
            gref += c * ww;
            gW += ww;
        }
    }
    vec3 cref = gref / max(gW, 0.0001);
    vec3 final_colour = cin * kSeed;
    vec3 final_colour2 = cin * kSeed;
    float Z = kSeed;
    float Z2 = kSeed;
    // ⑥ 主循环（:121-152）：对称折叠 —— 一格同时取 4 个象限点，四者共享 f1；
    //    w（sigY 路）与 w2（sigZ 路）各自做「减去四者最小值再归一化」再乘回。
    for (int i = 0; i <= 10; ++i) {
        if (i > uK) { break; }
        for (int j = 0; j <= 10; ++j) {
            if (j > uK) { break; }
            ivec2 pos = ivec2(i, j);
            ivec2 q0 = clamp(xy + pos, ivec2(0), mx);
            ivec2 q1 = clamp(xy - pos, ivec2(0), mx);
            ivec2 q2 = clamp(xy + ivec2(i, -j), ivec2(0), mx);
            ivec2 q3 = clamp(xy + ivec2(-i, j), ivec2(0), mx);
            vec3 cc0 = texelFetch(uTex, q0, 0).rgb;
            vec3 cc1 = texelFetch(uTex, q1, 0).rgb;
            vec3 cc2 = texelFetch(uTex, q2, 0).rgb;
            vec3 cc3 = texelFetch(uTex, q3, 0).rgb;
            vec4 d = vec4(length(abs(cc0 - cref)), length(abs(cc1 - cref)),
                          length(abs(cc2 - cref)), length(abs(cc3 - cref)));
            vec4 w = (1.0 - d * d / (d * d + vec4(sigY)));
            vec4 w2 = (1.0 - d * d / (d * d + vec4(sigZ)));
            // 高光样本权重 0（仅首趟）；wm 必须跳过被排除项，否则排除会经
            // (w−wm)/|w−wm| 归一化把其余样本**放大**（135 踩过的坑）。
            vec4 keep = vec4(1.0)
                      - vec4(maskAt(q0), maskAt(q1), maskAt(q2), maskAt(q3));
            w *= keep;
            w2 *= keep;
            float wm = 1.0e9;
            if (keep[0] > 0.5) wm = min(wm, w[0]);
            if (keep[1] > 0.5) wm = min(wm, w[1]);
            if (keep[2] > 0.5) wm = min(wm, w[2]);
            if (keep[3] > 0.5) wm = min(wm, w[3]);
            if (wm > 1.0e8) wm = 0.0;
            vec4 ws = (w - wm) * keep;
            ws /= length(ws) + 0.000001;
            vec4 w2s = (w2 - wm) * keep;
            w2s /= length(w2s) + 0.000001;
            w *= ws;
            w2 *= w2s;
            float ut = float(i) * tng.x + float(j) * tng.y;
            float f1 = mix(normpdf(float(i), uKernel) * normpdf(float(j), uKernel),
                           normpdf(ut, uKernel), coh);
            final_colour += f1 * mat4x3(cc0, cc1, cc2, cc3) * w;
            final_colour2 += f1 * mat4x3(cc0, cc1, cc2, cc3) * w2;
            Z += dot(vec4(f1), w);
            Z2 += dot(vec4(f1), w2);
        }
    }
    // ⑦ 输出（:157-164）：色度比 × 亮度。steered 版**没有上钳制**（原文注释：
    //    scene-referred 的值要活过降噪），照抄。
    float br = dot(final_colour / Z, LW3);
    br = mix(dot(cin, LW3), br, uLuma);
    vec3 resColour = final_colour2 / Z2;
    resColour /= max(1e-6, dot(resColour, LW3));
    resColour = max(resColour * br, 0.0);
    // ── 0.12.137：亮部去饱和（治"高光边缘淡绿光晕"）──────────────────────────
    // 真机证据（粉色陶瓷椅背的镜面反光，DNG 逐 photosite，WB 域）：
    //   光晕环   R 1.32  G 0.81  B 1.02   ⇒ R/G 1.64（**粉白，G 最低**）
    //   光晕外圈 R 1.02  G 0.62  B 0.75   ⇒ R/G 1.64
    // 而成片里这一圈是**淡绿**（G 相对最高）—— 色相被翻了 180°。RAW 里 G 最低、
    // 成片里 G 最高，只可能来自 RCD 在饱和边缘的插值伪彩（G 有 2× 采样密度，
    // 亮度陡变处沿梯度被拉高），PC3D 再把它摊成 3~10px 的光晕。
    // 为什么在 D4（PC3D 输出）做而不是回 RCD：RCD 的伪彩是结构性的（换 AMaZE
    // 才根治），而在**亮部**去饱和是影像学的通用做法（ACR 的高光恢复同样把
    // 接近白点的区域推向白），且这里只动 luma ≥ 0.70 的部分：
    //   · 光晕（luma≈0.93）→ 去饱和 77% ⇒ 淡白 ✓
    //   · 灯面/镜面（luma≥1.0）→ 全去饱和 ⇒ 纯白 ✓
    //   · 灰块/粉底（luma≈0.25~0.36）→ gate=0，逐位不变 ✓（0.12.106 的教训：
    //     l∈[0.8,1.0] 整段洗白会吃掉过渡带色相 —— 这里起点是 0.70 但**只作用于
    //     高 luma 端**，且用 smoothstep 连续过渡，不产生台阶）
    // 0.12.141：**删除 D4 层的一切色度修补**（G 封顶 / 去饱和 / 混背景色全部移除）。
    // 单变量证据（136→140）：这三版每一次"修"都换来另一个症状 ——
    //   去饱和 ⇒ 中性灰（粉底上显脏）；混背景色 ⇒ 太粉 + 灰背景处发灰 + 反光色块。
    // 根因不在 D4：光晕是 RAW 的真实测量（未饱和），它的色度本来就该原样保留；
    // 真正错的是 136 的 allSat 判据**跨块取值**，把光晕误判成"全饱和"并改写成中性白
    // （见 blockRGB 注释，0.12.141 已修）。高光趋白由 film 的 highlightRolloff 负责，
    // 不在色度降噪里做。
    oColor = vec4(resColour, 1.0);    oColor = vec4(resColour, 1.0);
})";

// PC gaussdown（GLUtils.java:246-280，k 倍）—— 尺度结构的入口。5×5 高斯
// （σ = blur/stride，stride = max(1, k/2)，blur = k·0.5）+ 采样点 ×k 的抽取。
const char* kFsPc3dDown = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform ivec2 uSize;
uniform int uResize;    // k
uniform int uStride;    // max(1, k/2)
uniform float uSigma;   // blur / stride
float normpdf(float x, float sigma) {
    return 0.39894 * exp(-0.5 * x * x / (sigma * sigma)) / sigma;
}
void main() {
    ivec2 mx = uSize - ivec2(1);
    ivec2 xy = clamp(ivec2(gl_FragCoord.xy), ivec2(0), uSize - 1) * uResize;
    vec3 mask = vec3(0.0);
    float pdfsize = 0.00001;
    for (int i = -2; i <= 2; ++i) {
        for (int j = -2; j <= 2; ++j) {
            float pdf = normpdf(float(j), uSigma) * normpdf(float(i), uSigma);
            vec3 inp = texelFetch(uTex, clamp(xy + ivec2(i * uStride, j * uStride),
                                              ivec2(0), mx), 0).rgb;
            mask += inp * pdf;
            pdfsize += pdf;
        }
    }
    oColor = vec4(mask / pdfsize, 1.0);
})";

// PC guidedupsample（guidedupsample.glsl，逐行同构）—— 尺度结构的收口：
// 低分辨率去噪结果按**全分辨率引导**做高斯加权最小二乘拟合（导引 = RGB 均值），
// 再把结果缩放到「输出亮度 = 引导亮度」。
// ⚠ 与原文的两处工程差异（非算法删减）：
//   ① uR（= SCALE）由 #define 改成 uniform，上界写常量 4 + continue；
//   ② 分母加 1e-6 下限（原文 max(outL, 0.25*guideLightness) 在引导亮度 = 0 的
//      纯黑像素上是 0/0）；输出加 max(·,0) 是引擎契约（下游 filmic 假设非负）。
const char* kFsPc3dUp = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uLow;
uniform highp sampler2D uGuideHi;
uniform ivec2 uLowSize;
uniform ivec2 uHiSize;
uniform int uR;
vec3 fitModel(ivec2 center, float guideLightness) {
    float R = float(uR);
    float sigma = 0.4 * R;
    float sigmaSq2 = 2.0 * sigma * sigma;
    float momentX = 0.0;
    float momentX2 = 0.0;
    float ws = 0.0;
    vec3 momentY = vec3(0.0);
    vec3 momentXY = vec3(0.0);
    ivec2 hiMax = uHiSize - ivec2(1);
    ivec2 loMax = uLowSize - ivec2(1);
    for (int i = -4; i <= 4; ++i) {
        if (i < -uR || i > uR) { continue; }
        float wi = exp(-float(i * i) / sigmaSq2);
        for (int j = -4; j <= 4; ++j) {
            if (j < -uR || j > uR) { continue; }
            float w = wi * exp(-float(j * j) / sigmaSq2);
            ivec2 pos = clamp(center + ivec2(i, j), ivec2(0), hiMax);
            float lightness = dot(texelFetch(uGuideHi, pos, 0).rgb, vec3(1.0 / 3.0));
            vec3 lowresVal = texelFetch(uLow, clamp(pos / uR, ivec2(0), loMax), 0).rgb;
            momentX += lightness * w;
            momentY += lowresVal * w;
            momentX2 += lightness * lightness * w;
            momentXY += lowresVal * (lightness * w);
            ws += w;
        }
    }
    float invWs = 1.0 / ws;
    float meanX = momentX * invWs;
    vec3 meanY = momentY * invWs;
    float varX = momentX2 * invWs - meanX * meanX;
    vec3 covXY = momentXY * invWs - meanX * meanY;
    vec3 a = covXY / (max(varX, 0.0) + 3e-04);
    vec3 b = meanY - a * meanX;
    return a * guideLightness + b;
}
void main() {
    ivec2 mx = uHiSize - ivec2(1);
    ivec2 xy = clamp(ivec2(gl_FragCoord.xy), ivec2(0), mx);
    float guideLightness = dot(texelFetch(uGuideHi, xy, 0).rgb, vec3(1.0 / 3.0));
    vec3 o = fitModel(xy, guideLightness);
    float outL = dot(o, vec3(1.0 / 3.0));
    o *= guideLightness / max(outL, max(0.25 * guideLightness, 1e-6));
    oColor = vec4(max(o, 0.0), 1.0);
})";

// ── MFNR 阶段一（v0.9.0）────────────────────────────────────────────
// 多帧融合降噪：4 帧 RAW 的 Bayer 域对齐 + 时域均值，插在 P0 降噪位（demosaic 前）。
// 依据：HDR+（Hasinoff 2016 §4-5）与 Night Sight（Liba 2019 §3）——彩噪跨帧独立、
// 信号跨帧相关 ⇒ 时域均值 √N 提 SNR，是空间降噪给不了的低频彩噪根治手段。
// 域约定：对齐在 2×2 Bayer 块平均的灰度金字塔（1/4、1/8、1/16 readback）上做，
// 位移场以 mosaic 域表示，融合采样 round 到**最近偶数**——1/2 域整数位移 ×2 恒
// 为偶数，Bayer 相位不翻转（奇数位移会把 R 位采到 B，等价于把色度打乱）。
// kFsMfnrSatFix（期 B bracket，0.11.72；0.12.127 重写）：参考帧饱和修复。
// 参考帧（长帧）在窗外/灯芯这类高光处已饱和（= 白点），信息已丢；短帧同位置
// 往往还没饱和 ⇒ 把短帧归一后的真实值移植过来。
//
// ── 0.12.127：删掉三样东西，只留"短帧有真值就注入，没有就不注入" ─────────────
// 真机实测（2026-10-28，日志 16）把这条链的因果钉死了：
//
//  ① 0.12.124 把保守值 sValCons 换成 clipped inpaint。Bento 的 inpaint 是从
//     **周围未裁剪像素**扩散 —— 用在"粉色物体上的孤立高光点"时，周围是粉色，
//     于是注入"粉色 × uScale" ⇒ 最先饱和的 G 被单独抬高 ⇒ 高光点变绿。
//     大面积饱和区则退到 `wl0 * uScale`（−1EV 短帧 = 2.82×满阱）⇒ 三通道一起
//     被顶到 2.82 ⇒ 高光过亮，再被 filmic 压成色块。
//  ② 注入值（可达 3~4，超过满阱）进入 HL 的 chroma 统计：refavg 的 3×3 邻域被
//     抬高 ⇒ 统计样本的 `v − refavg` 被压成大负 ⇒ uChroma 崩坏。0.12.126 真机
//     `hlChroma = [-2.191, -0.037, -0.328]`，而本域 refavg 上限就是满阱
//     (1.75/1.0/1.65)、单样本最低只到 −1.4 —— **平均值 −2.19 物理上不存在**。
//     后果：`max(v, refavg + chroma)` 对 R 永不触发、对 G 照旧抬 ⇒ 粉底上成片的
//     绿斑/绿边。（0.12.127 同时把统计口径限定为"标记 2 = 长帧本身未饱和"的
//     邻域样本，见 kFsHlChroma。）
//  ③ 5×5 satFrac 面积插值、4-photosite L2 fallback，都是在①②上叠的补丁。
//
// 现在只有一个判据，而且是**2×2 块级**的（0.12.128）：长帧四个 photosite 全饱和
// **且**短帧四个都未饱和，才注入 sNorm（可 > 满阱，那是真实的 scene-referred 高光，
// 由 filmic 负责压进显示域）；否则不注入，保持长帧饱和值，交给 HL 的 opposed。
//
// 为什么"不注入"优于"注入一个保守值"：保守值在通道间是常量，而饱和是**逐通道**
// 发生的（G 的 WB 满阱 = 1.0，R/B = 1.75/1.65），任何逐通道常量注入都会破坏通道
// 比例；HL 的 opposed 在 apply 里对三通道用同一套 refavg + chroma，天然保比例。
// 0.12.118 的真机对照（只关掉注入 → 黄绿完全消失）就是这条的证据。
//
// 三态标记（R16UI 的 G 通道，供 HL 统计口径使用）：
//   0 = 短帧也饱和 ⇒ 无真值（值是长帧饱和值）
//   1 = 短帧有真值且已注入（值可能 > 满阱）
//   2 = 长帧本身未饱和（值是真测量）
// 只有 2 参与 opposed 的 refavg 统计 —— 1 虽然也是真测量，但超过满阱，会破坏
// "局部同色、三通道可比"的外推前提。
// ── 0.12.129：sat-fix **不再注入，只生成三态标记** ──────────────────────────
// 真机 2026-10-28 18:xx（圆灯）暴露了最后一个结构性问题：成片的灯内部有一条
// **水平分界线**，上方白、下方偏暖。分界线的位置 = **短帧的饱和边界**穿进灯内部
// 的地方：
//   · 线上方：短帧也饱和 ⇒ 不注入 ⇒ 走 HL 的 opposed（三通道同一套 refavg）
//   · 线下方：短帧还有真值 ⇒ 注入短帧归一值（逐 photosite，各自独立）
// 两条路径给出的**色比不同**（HL 的重建是"满阱比例的 opposed 外推"，注入是
// "短帧实测的逐通道值"），所以只要两条路径并存，接缝就必须存在 —— 这不是阈值
// 能调掉的，是机制本身的矛盾。0.12.119~128 的每一版都在挪这条接缝的位置。
//
// 更根本的一层：本 pass 工作在 **WB 之后**（满阱逐通道 = gain[c]，本机 2.25/1.0/1.53），
// 而 opposed 的语义建立在"三通道满阱相同"之上。darktable 的 highlights 跑在
// **white balance 之前**（raw 域，clips 全通道 = 1.0），所以它的 refavg 外推与
// 注入值天然同尺度。我们在 WB 之后做，任何"注入的真值"与"外推的估计值"都不在
// 同一套色比语义里 —— 这是 0.12.102~128 反复出现黄绿/暖斑的公共根因。
//
// 因此本版把注入整条移除，只保留三态标记（HL 的统计口径要用它）：
//   0 = 长帧饱和（重建交给 opposed）
//   2 = 长帧本身未饱和（真测量）
// 短帧在这里不再参与着色：它的真值如果有效，本来就应该由**融合**（kFsMfnrFft 的
// 收缩权重 / kFsMfnrFuse 的 satW）在融合阶段接管，而不是在融合之后做二次替换。
//
// 代价（已知、且与用户取向一致）：大面积过曝区（灯、天空、窗）失去短帧带来的
// 高光层次，统一渲染为 opposed 重建的平色。这类区域本来就没有可分辨的细节。
const char* kFsMfnrSatFix = R"(#version 300 es
precision highp float;
precision highp int;

layout(location = 0) out uvec4 oColor;
uniform highp usampler2D uRef;
uniform ivec2 uSize;
uniform float uWhite;

void main() {
    ivec2 p = clamp(ivec2(gl_FragCoord.xy), ivec2(0), uSize - 1);
    float r = float(texelFetch(uRef, p, 0).r);
    float wl = max(uWhite, 1.0);
    uint mark = (r < wl * 0.98) ? 2u : 0u;
    oColor = uvec4(uint(clamp(r, 0.0, 65535.0) + 0.5), mark, 0u, 1u);
})";

// ── 0.12.133（方案 B 块 3 / 上）：短帧真值图 ────────────────────────────────
// 为什么需要它：DFT 频域收缩（kFsMfnrFft）是为**等曝光** burst 设计的，多曝光下
// 长短帧在高光处相差整档 ⇒ 收缩只能判成不一致并退回参考帧，短帧真值没有出口。
// 0.12.130 试过用空间域 satW 加权让短帧接管，但它"有真值/没真值"的二分正好沿
// 短帧饱和边界切成一条水平分界线（0.12.131 回滚）。本 pass 只把短帧按**同一套
// 位移场**采出来、标出有效性，不参与融合；扩散交给 kFsHlInpaint 做（连续）。
// 输出 RGBA16F：rgb = 短帧归一后的 WB 线性值（可 > 满阱，真实 scene-referred），
//              a = 1 表示该位置短帧未饱和（真值可信）。
// 采样与 kFsMfnrFuse/kFsMfnrMismatch **逐字同构**（linSub 4 同相位 tap + off 场
// 双线性），保证"同一像素、同一位移"下与融合看到的短帧值完全一致。
const char* kFsMfnrShortVal = R"(#version 300 es
precision highp float;
precision highp int;
layout(location = 0) out vec4 oColor;
uniform highp usampler2D uShort;
uniform highp sampler2D uOff;
uniform ivec2 uSize;
uniform vec2 uTiles;
uniform float uTileM;   // mosaic 域 tile 尺寸（= 2×1/2 域 tile）
uniform int uCfaLut;
uniform vec4 uBlack;
uniform float uWhite;
uniform vec3 uGain;
uniform float uScale;   // 短帧曝光归一（长/短）
int chanAt(ivec2 p) {
    int k = ((p.y & 1) << 1) | (p.x & 1);
    return (uCfaLut >> (k << 1)) & 3;
}
float gainAt(int c) { return c == 0 ? uGain.x : (c == 1 ? uGain.y : uGain.z); }
float linAt(ivec2 p) {
    p = clamp(p, ivec2(0), uSize - 1);
    float v = float(texelFetch(uShort, p, 0).r);
    float bl = uBlack[(p.y & 1) * 2 + (p.x & 1)];
    return max((v - bl) / max(uWhite - bl, 1.0), 0.0) * gainAt(chanAt(p));
}
// 位移场双线性（与 kFsMfnrFuse::off1At 逐字同式）
vec3 offAt(vec2 uv) {
    vec2 px = uv * uTiles - 0.5;
    vec2 fr = fract(px);
    ivec2 i0 = ivec2(floor(px));
    ivec2 lo = clamp(i0, ivec2(0), ivec2(uTiles) - 1);
    ivec2 hi = clamp(i0 + ivec2(1), ivec2(0), ivec2(uTiles) - 1);
    vec3 a = texelFetch(uOff, lo, 0).rgb;
    vec3 b = texelFetch(uOff, ivec2(hi.x, lo.y), 0).rgb;
    vec3 c = texelFetch(uOff, ivec2(lo.x, hi.y), 0).rgb;
    vec3 d = texelFetch(uOff, hi, 0).rgb;
    return mix(mix(a, b, fr.x), mix(c, d, fr.x), fr.y);
}
// 每通道网格亚像素双线性（与 kFsMfnrFuse::linSub 逐字同式）：mosaic 视为 2×2
// 打包网格，4 个 tap 全部同相位 ⇒ 零色度污染。
float linSub(ivec2 p, vec2 d) {
    ivec2 ph = ivec2(p.x & 1, p.y & 1);
    vec2 gmax = vec2(uSize - 1 - ph) * 0.5;
    vec2 s = clamp((vec2(p) + d - vec2(ph)) * 0.5, vec2(0.0), gmax);
    vec2 s0 = floor(s);
    vec2 w = s - s0;
    ivec2 hi = ph + ((uSize - 1 - ph) & ~1);
    ivec2 b = ph + ivec2(s0) * 2;
    float v00 = linAt(b);
    float v10 = linAt(min(b + ivec2(2, 0), hi));
    float v01 = linAt(min(b + ivec2(0, 2), hi));
    float v11 = linAt(min(b + ivec2(2, 2), hi));
    return mix(mix(v00, v10, w.x), mix(v01, v11, w.x), w.y);
}
void main() {
    ivec2 p = clamp(ivec2(gl_FragCoord.xy), ivec2(0), uSize - 1);
    int ch = chanAt(p);
    vec2 uv = (vec2(p) + 0.5) / (uTiles * uTileM);
    vec2 d = offAt(uv).xy;
    float v = linSub(p, d) * uScale;
    // 有效判据 = 短帧本身未饱和。满阱在归一后也 ×scale（短帧饱和值 = scale·gain），
    // 与 kFsMfnrFuse::satW 的 sat 定义同一口径。
    float full = gainAt(ch) * uScale;
    float valid = (v < full * 0.98) ? 1.0 : 0.0;
    // 0.12.134：存**归一化亮度**（除 WB 增益）⇒ 三个通道可直接比较/平均，
    // inpaint 侧才能用一个标量 k 表示"真实亮度 / 长帧满阱"，而不是逐通道注入
    //（逐通道注入 = 色比撕裂 = 133 的灯面发绿）。
    oColor = vec4(max(v, 0.0) / max(gainAt(ch), 1e-4), valid, 0.0, 1.0);
})";

// ── 0.12.133（方案 B 块 3 / 下）：全饱和区亮度 inpaint ───────────────────────
// 输入 = 融合输出（tex_fused_，长帧饱和处 = 满阱）+ 短帧真值图 + 三态标记。
// 语义（PC 的 oInpaintingMask 那一支）：
//   · 长帧未饱和（mark != 0）      → 原样保留（真测量，任何改动都是倒退）
//   · 长帧饱和 + 短帧有真值        → 用短帧值（真实 scene-referred 高光）
//   · 长帧饱和 + 短帧也饱和        → 归一化卷积从周围有真值的像素扩散（1/d²），
//                                    一个都没有就退回短帧满阱（物理下界：真实
//                                    亮度 ≥ scale×满阱，比"长帧满阱"高一整档）
// 为什么这样不会再有分界线（0.12.130 的病）：三条分支的值在短帧饱和边界处**收敛**
// —— 边界上的真值本来就是 scale×满阱，扩散值也是同一量级，深处退回的也是它。
// 而 132 的"满阱 luma 锚"把灯面钉在 1.0×满阱，正是用户看到的"白但灰"。
const char* kFsHlInpaint = R"(#version 300 es
precision highp float;
precision highp int;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uFused;   // R16F 融合 mosaic（值在 .r）
uniform highp sampler2D uShort;   // 短帧真值图（.r = 归一化亮度，.g = 有效）
uniform ivec2 uSize;
uniform vec3 uGain;               // WB 满阱（gain[c]）
uniform float uShortK;            // 短帧归一比例（长/短）= 无有效样本时的下界
uniform int uCfaLut;
int chanAt(ivec2 p) {
    int k = ((p.y & 1) << 1) | (p.x & 1);
    return (uCfaLut >> (k << 1)) & 3;
}
float at3(vec3 v, int c) { return c == 0 ? v.x : (c == 1 ? v.y : v.z); }
void main() {
    ivec2 p = clamp(ivec2(gl_FragCoord.xy), ivec2(0), uSize - 1);
    float base = texelFetch(uFused, p, 0).r;
    // ── 0.12.134：判据从"本 photosite 饱和"收紧到"2×2 块**三通道全饱和**" ────
    // 0.12.133 用前者 ⇒ 灯面 G 已饱和、R/B 未饱和的那些 photosite 被单独填了
    // 短帧满阱值（2.82×满阱），而 R/B 仍是真值（1.2×满阱）⇒ 三通道色比被撕开
    // ⇒ 真机：整个灯面 G 高 8.7%、B 低 7.8%（黄绿），边缘的短帧噪声成了彩点。
    // 正确判据与 kFsHlApply 的 allSat **同一口径**：只有三通道都顶到满阱，
    // 真实色度才不可知，此时注入一个**标量亮度**才是安全的；部分饱和时 R/B
    // 有真值，G 必须由 opposed 从 R/B 外推 —— 那正是 0.12.129 的路径。
    float rv = -1.0, gv = -1.0, bv = -1.0;
    for (int k = 0; k < 9; ++k) {
        ivec2 d = k == 0 ? ivec2(0, 0)
                : (k == 1 ? ivec2(1, 0)
                : (k == 2 ? ivec2(-1, 0)
                : (k == 3 ? ivec2(0, 1)
                : (k == 4 ? ivec2(0, -1)
                : (k == 5 ? ivec2(1, 1)
                : (k == 6 ? ivec2(-1, 1)
                : (k == 7 ? ivec2(1, -1) : ivec2(-1, -1))))))));
        ivec2 q = clamp(p + d, ivec2(0), uSize - 1);
        int c = chanAt(q);
        float v = texelFetch(uFused, q, 0).r;
        if (c == 0) { if (rv < 0.0) rv = v; }
        else if (c == 1) { if (gv < 0.0) gv = v; }
        else { if (bv < 0.0) bv = v; }
    }
    if (rv < 0.0) rv = base;
    if (gv < 0.0) gv = base;
    if (bv < 0.0) bv = base;
    bool all_sat = rv >= uGain.x * 0.99 && gv >= uGain.y * 0.99
                && bv >= uGain.z * 0.99;
    // ── 0.12.196：**部分饱和**也要短帧真值（品红/绿边的真正位置）────────────
    // 0.12.134 只填"三通道全饱和"，因为当时是逐 photosite 填 ⇒ G 用短帧、
    // R/B 用长帧，两块数据混在一个 2×2 块里 ⇒ 色比撕裂（黄绿）。那不是"部分
    // 饱和不能用短帧"，而是"不能用半块短帧"。
    // 正确判据（PC Bento 的 mask 语义）：只要短帧在**本 2×2 块的四相位全都
    // 未饱和**，整块就交给短帧 —— 四个 photosite 同源，色比天然正确；长帧此时
    // 有几个通道顶到满阱都无所谓。真机 10:14 那张（satmHit 122113）：品红全部
    // 贴着白壶/背景高光的边缘，正是"长帧部分饱和、短帧全有真值"的带。
    bool any_sat = rv >= uGain.x * 0.99 || gv >= uGain.y * 0.99
                || bv >= uGain.z * 0.99;
    bool quad_ok = true;
    for (int dy = 0; dy < 2; ++dy) {
        for (int dx = 0; dx < 2; ++dx) {
            ivec2 q = clamp(ivec2((p.x & ~1) + dx, (p.y & ~1) + dy),
                            ivec2(0), uSize - 1);
            if (texelFetch(uShort, q, 0).g <= 0.5) quad_ok = false;
        }
    }
    vec4 s0 = texelFetch(uShort, p, 0);
    // 短帧 5×5 双边平滑（只平均同亮度样本）。两处共用，见下。
    float sacc = 0.0, swsum = 0.0;
    for (int dy = -2; dy <= 2; ++dy) {
        for (int dx = -2; dx <= 2; ++dx) {
            ivec2 q = clamp(p + ivec2(dx, dy), ivec2(0), uSize - 1);
            vec4 t = texelFetch(uShort, q, 0);
            if (t.g <= 0.5) continue;
            if (abs(t.r - s0.r) > 0.25) continue;   // 双边：跨亮度边界不混
            float w = 1.0 / (float(dx * dx + dy * dy) + 1.0);
            sacc += t.r * w;
            swsum += w;
        }
    }
    float sSmooth = (swsum > 1e-9) ? sacc / swsum : s0.r;
    if (any_sat && quad_ok) {
        // 整块同源短帧 ⇒ 保留真实色比（这正是 opposed 在边缘带做不到的事）。
        // ⚠ uShort.r 是**归一化亮度**（kFsMfnrShortVal 里除过 gain）—— 必须乘回
        // 本通道的满阱 gain 才是 WB 域通道值。0.12.196 漏了这一步 ⇒ R/B 被除成
        // 1/2.1、1/1.46、只有 G 原样 ⇒ 高光边缘整圈泛绿（真机 10:21）。
        oColor = vec4(at3(uGain, chanAt(p)) * sSmooth, 0.0, 0.0, 1.0);
        return;
    }
    if (!all_sat) {
        oColor = vec4(base, 0.0, 0.0, 1.0);
        return;
    }
    // ── 标量亮度 k（只取标量：三通道天然同比例，不可能再制造色噪）──────────
    // 两条支路是**分开的**，不能合并成一个大卷积：
    //   中心有真值 → 只做 3×3（±1 photosite）平滑 ⇒ 把单点噪声与 1px 对齐误差
    //     压掉，又不会跨过明暗边界（灯的外沿、窗框）把邻区的低亮度搬进来。
    //   中心无真值（短帧也饱和 = 灯芯最亮处）→ 只能从大半径扩散（3 环 × 8 方
    //     向，1/(d²+1)）；环上也没有有效样本时才退回 uShortK（短帧满阱下界）。
    float acc = 0.0;
    float wsum = 0.0;
    if (s0.g > 0.5) {
        // 中心有真值 → 复用上面的 5×5 双边平滑（0.12.196 提到前面）。
        acc = sacc;
        wsum = swsum;
    } else {
        for (int r = 8; r <= 24; r += 8) {
            for (int dy = -1; dy <= 1; ++dy) {
                for (int dx = -1; dx <= 1; ++dx) {
                    if (dx == 0 && dy == 0) continue;
                    ivec2 q = clamp(p + ivec2(dx, dy) * r, ivec2(0), uSize - 1);
                    vec4 t = texelFetch(uShort, q, 0);
                    if (t.g <= 0.5) continue;
                    float dd = float(dx * dx + dy * dy) * float(r * r);
                    float w = 1.0 / (dd + 1.0);
                    acc += t.r * w;
                    wsum += w;
                }
            }
        }
    }
    // 全饱和 ⇒ 真实亮度 ≥ 1.0×满阱（这是"三通道都到满阱"的定义），所以 k 的
    // 合法下界就是 1.0、上界是短帧满阱 scale。夹一下只防噪声/对齐误差越界，
    // 不会压掉真实的高光（0.12.134 未夹 ⇒ 个别 photosite 的 k<1 会把该处压暗，
    // 在灯面上就是"亮度台阶/色块"）。
    float k = (wsum > 1e-9) ? acc / wsum : uShortK;
    k = clamp(k, 1.0, uShortK);
    // 输出 = 满阱 × k：三通道比例恒为 gain（= 传感器满阱比例），色度由下游
    // allSat 统一改写成中性白 —— 与 0.12.132 的"白"是同一套语义，只是亮度不再
    // 钉死在 1.0×满阱。
    oColor = vec4(at3(uGain, chanAt(p)) * k, 0.0, 0.0, 1.0);
})";

// kFsMfnrPyr：raw → 1/2 灰度（RGBA8）。sqrt 预编码：8bit 灰度域里暗部分辨率
// 太低（线性 0.01 → 2.5 级），sqrt 把它抬到 25 级——对齐度量在感知域也更稳。
const char* kFsMfnrPyr = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp usampler2D uRaw;
uniform ivec2 uSize;
uniform int uCfaLut;
uniform vec4 uBlack;
uniform float uWhite;
uniform vec3 uGain;
// 期 B bracket（0.11.66）：短帧曝光归一（长/短）。1 = 未归一（帧 1-3 与无 bracket）。
// 归一放在扣黑阶之后 —— 短帧的 (v−bl) 才是线性信号，直接乘原始 u16 会带进 3×黑阶偏差。
uniform float uScale;
int chanAt(ivec2 p) {
    int k = ((p.y & 1) << 1) | (p.x & 1);
    return (uCfaLut >> (k << 1)) & 3;
}
float gainAt(int c) { return c == 0 ? uGain.x : (c == 1 ? uGain.y : uGain.z); }
float raw0(ivec2 p) {
    p = clamp(p, ivec2(0), uSize - 1);
    float v = float(texelFetch(uRaw, p, 0).r);
    float bl = uBlack[(p.y & 1) * 2 + (p.x & 1)];
    return max((v - bl) / max(uWhite - bl, 1.0), 0.0) * gainAt(chanAt(p)) * uScale;
}
void main() {
    ivec2 q = ivec2(gl_FragCoord.xy);
    ivec2 p = q * 2;
    float s = raw0(p) + raw0(p + ivec2(1, 0))
            + raw0(p + ivec2(0, 1)) + raw0(p + ivec2(1, 1));
    float g = clamp(s * 0.25, 0.0, 1.0);
    float enc = sqrt(g);
    oColor = vec4(enc, enc, enc, 1.0);
})";

// kFsMfnrDown：金字塔 2× box（sqrt 域进、线性域平均、sqrt 域出）
const char* kFsMfnrDown = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform ivec2 uSize;
void main() {
    ivec2 q = ivec2(gl_FragCoord.xy);
    ivec2 p = clamp(q * 2, ivec2(0), uSize - 1);
    ivec2 px = clamp(q * 2 + ivec2(1, 0), ivec2(0), uSize - 1);
    ivec2 py = clamp(q * 2 + ivec2(0, 1), ivec2(0), uSize - 1);
    ivec2 pxy = clamp(q * 2 + ivec2(1, 1), ivec2(0), uSize - 1);
    vec4 a = texelFetch(uTex, p, 0);
    vec4 b = texelFetch(uTex, px, 0);
    vec4 c = texelFetch(uTex, py, 0);
    vec4 d = texelFetch(uTex, pxy, 0);
    float v = sqrt((a.r * a.r + b.r * b.r + c.r * c.r + d.r * d.r) * 0.25);
    oColor = vec4(v, v, v, 1.0);
})";

// kFsMfnrFuse：4 帧 RAW + 3 帧位移场 → 置信度加权时域均值 → R16F 线性 mosaic。
// 位移场 RGBA16F（RG=mosaic 域位移、B=置信度），NEAREST 纹理 + 手写双线性
//（X300 上 16F 无硬件线性过滤，见 makeTex）；采样结果 round 到最近偶数保相位。
const char* kFsMfnrFuse = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp usampler2D uRaw0;
uniform highp usampler2D uRaw1;
uniform highp usampler2D uRaw2;
uniform highp usampler2D uRaw3;
uniform highp sampler2D uOff1;
uniform highp sampler2D uOff2;
uniform highp sampler2D uOff3;
uniform highp sampler2D uW;  // v0.10.3：mismatch 权重图（4×tiles 网格、RGBA8
                             // LINEAR——8bit 纹理硬件双线性，32px 过渡带）
uniform ivec2 uSize;
uniform vec2 uTiles;
uniform float uTileM;   // mosaic 域 tile 尺寸（= 2×1/2 域 tile，kMfnrTile×2）
uniform int uCfaLut;
uniform vec4 uBlack;
uniform float uWhite;
uniform vec3 uGain;
// 期 B bracket：逐帧曝光归一（短帧 = 长/短，其余 1）。**不 clamp**：高光值可 >1
// （真实 scene-referred 高光），交给引擎曲线压进显示域。
uniform vec4 uFrameScale;
// 期 B bracket（设计 §3.3 路径 A）：饱和阈值（占白点比例）。本帧在该像素饱和
// ⇒ 信息已丢，权重 0；短帧归一后暗部噪声 ×ratio，SNR 低 ⇒ 暗部权重压低。
uniform float uSatK;
float frameLin(float x, float sc) { return x * sc; }
int chanAt(ivec2 p) {
    int k = ((p.y & 1) << 1) | (p.x & 1);
    return (uCfaLut >> (k << 1)) & 3;
}
float gainAt(int c) { return c == 0 ? uGain.x : (c == 1 ? uGain.y : uGain.z); }
float linAt(highp usampler2D t, ivec2 p) {
    p = clamp(p, ivec2(0), uSize - 1);
    float v = float(texelFetch(t, p, 0).r);
    float bl = uBlack[(p.y & 1) * 2 + (p.x & 1)];
    return max((v - bl) / max(uWhite - bl, 1.0), 0.0) * gainAt(chanAt(p));
}
// 饱和惩罚：lin 是 WB 后值。饱和点 = gain(chan)·white·frameScale —— 短帧归一后
// 的白点也 ×scale（短帧饱和值 = scale·gain），不乘就会把整个短帧误判成饱和。
float satW(float lin, int ch, float sc) {
    float sat = gainAt(ch) * uSatK * sc;
    return 1.0 - smoothstep(sat * 0.92, sat, lin);
}
// 短帧暗部惩罚：只在 scale > 1（短帧）时生效；0.02/0.08 是 3σ 噪声底附近（设计 §4）
float darkW(float lin, float sc) {
    return sc > 1.001 ? smoothstep(0.02, 0.08, lin) : 1.0;
}
vec3 off1At(vec2 uv) {
    vec2 px = uv * uTiles - 0.5;
    vec2 fr = fract(px);
    ivec2 i0 = ivec2(floor(px));
    ivec2 lo = clamp(i0, ivec2(0), ivec2(uTiles) - 1);
    ivec2 hi = clamp(i0 + ivec2(1), ivec2(0), ivec2(uTiles) - 1);
    vec3 a = texelFetch(uOff1, lo, 0).rgb;
    vec3 b = texelFetch(uOff1, ivec2(hi.x, lo.y), 0).rgb;
    vec3 c = texelFetch(uOff1, ivec2(lo.x, hi.y), 0).rgb;
    vec3 d = texelFetch(uOff1, hi, 0).rgb;
    return mix(mix(a, b, fr.x), mix(c, d, fr.x), fr.y);
}
vec3 off2At(vec2 uv) {
    vec2 px = uv * uTiles - 0.5;
    vec2 fr = fract(px);
    ivec2 i0 = ivec2(floor(px));
    ivec2 lo = clamp(i0, ivec2(0), ivec2(uTiles) - 1);
    ivec2 hi = clamp(i0 + ivec2(1), ivec2(0), ivec2(uTiles) - 1);
    vec3 a = texelFetch(uOff2, lo, 0).rgb;
    vec3 b = texelFetch(uOff2, ivec2(hi.x, lo.y), 0).rgb;
    vec3 c = texelFetch(uOff2, ivec2(lo.x, hi.y), 0).rgb;
    vec3 d = texelFetch(uOff2, hi, 0).rgb;
    return mix(mix(a, b, fr.x), mix(c, d, fr.x), fr.y);
}
vec3 off3At(vec2 uv) {
    vec2 px = uv * uTiles - 0.5;
    vec2 fr = fract(px);
    ivec2 i0 = ivec2(floor(px));
    ivec2 lo = clamp(i0, ivec2(0), ivec2(uTiles) - 1);
    ivec2 hi = clamp(i0 + ivec2(1), ivec2(0), ivec2(uTiles) - 1);
    vec3 a = texelFetch(uOff3, lo, 0).rgb;
    vec3 b = texelFetch(uOff3, ivec2(hi.x, lo.y), 0).rgb;
    vec3 c = texelFetch(uOff3, ivec2(lo.x, hi.y), 0).rgb;
    vec3 d = texelFetch(uOff3, hi, 0).rgb;
    return mix(mix(a, b, fr.x), mix(c, d, fr.x), fr.y);
}
// 每通道网格亚像素双线性（v0.9.5）：把 mosaic 视为 2×2 打包网格（无损重排，
// R/G/Gb/B 各自是间隔 2px 的完整采样网格）。在 p+d 处估计通道 chanAt(p) 的值
// = 该通道点阵上的双线性插值 —— 4 个 tap 全部同相位，R 只与 R 插值，零色度
// 污染。v0.9.4 的 1px 取整+phaseFix 退役：亚像素位移天然覆盖奇偶，量化残差
// 归零（此前 dMed/dP90 实测 8-21px 位移下 ±0.5px 残差仍把细节磨掉）。
// 边界处理 = CLAMP_TO_EDGE：网格坐标 s 先 clamp 进 [0, gmax]（离线验证抓出
// 的问题：负位移+边缘像素时 floor(s)=-1 会让权重 >1 变外推），右端 tap 用
// min 收敛到同相位边界。python 镜像 27648 样本（线性场景）逐点精确通过。
float linSub(highp usampler2D t, ivec2 p, vec2 d) {
    ivec2 ph = ivec2(p.x & 1, p.y & 1);
    vec2 gmax = vec2(uSize - 1 - ph) * 0.5;
    vec2 s = clamp((vec2(p) + d - vec2(ph)) * 0.5, vec2(0.0), gmax);
    vec2 s0 = floor(s);
    vec2 w = s - s0;
    ivec2 hi = ph + ((uSize - 1 - ph) & ~1);  // 该相位在图内的最大坐标
    ivec2 b = ph + ivec2(s0) * 2;             // mosaic 域左上采样点（≥ph、≤hi）
    float v00 = linAt(t, b);
    float v10 = linAt(t, min(b + ivec2(2, 0), hi));
    float v01 = linAt(t, min(b + ivec2(0, 2), hi));
    float v11 = linAt(t, min(b + ivec2(2, 2), hi));
    return mix(mix(v00, v10, w.x), mix(v01, v11, w.x), w.y);
}
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int ch = chanAt(p);
    // 参考帧权重不再恒 1：饱和帧（信息已丢）权重 0，让未饱和的短帧接住高光
    float r0 = frameLin(linAt(uRaw0, p), uFrameScale.x);
    float wr0 = satW(r0, ch, uFrameScale.x) * darkW(r0, uFrameScale.x);
    float acc = wr0 * r0;
    float wsum = wr0;
    vec2 uv = (vec2(p) + 0.5) / (uTiles * uTileM);
    // 权重（v0.10.3）：独立权重图（32px cell，LINEAR 双线性过渡），与位移场解耦
    // —— 128px tile 级权重对叶缘带局部运动分辨率不足（重影残留）。
    vec4 wv = texture(uW, uv);
    vec3 f1 = off1At(uv);
    float l1 = frameLin(linSub(uRaw1, p, f1.xy), uFrameScale.y);
    float w1 = wv.r * satW(l1, ch, uFrameScale.y) * darkW(l1, uFrameScale.y);
    acc += w1 * l1;
    wsum += w1;
    vec3 f2 = off2At(uv);
    float l2 = frameLin(linSub(uRaw2, p, f2.xy), uFrameScale.z);
    float w2 = wv.g * satW(l2, ch, uFrameScale.z) * darkW(l2, uFrameScale.z);
    acc += w2 * l2;
    wsum += w2;
    vec3 f3 = off3At(uv);
    float l3 = frameLin(linSub(uRaw3, p, f3.xy), uFrameScale.w);
    float w3 = wv.b * satW(l3, ch, uFrameScale.w) * darkW(l3, uFrameScale.w);
    acc += w3 * l3;
    wsum += w3;
    // 全部饱和（长帧+短帧都烧了）⇒ 退回参考帧：如实记录裁切，不凭空造信息
    float v = wsum > 1e-4 ? acc / wsum : r0;
    oColor = vec4(v, v, v, 1.0);
})";

// kFsMfnrMismatch：阶段二 robust merge（v0.10.0）—— Night Sight 式 mismatch map。
// 每 tile 归约"对齐后残差" mean(d²)：d = ref(p) − alt_sub(p+off)，采样与 kFsMfnrFuse
// **逐字同构**（同相位 linSub 4-tap）—— d 恰是融合权重为 1 时该帧实际注入的误差，
// 这保证 mismatch 度量与融合行为严格一致。m = d²/(d² + s·σ²)：σ = 单帧线性域噪声
//（WB 后同域），噪声自适应 —— 噪声越大容忍度越高（纯噪声 tile 的 d²≈2σ² 只给
// 小 mismatch），运动/鬼影 tile 的 d² 远超噪声底才被压权重。
// 为什么不用 hdr-plus 的 L1 硬阈值反比（其实现 factor 8 / min 10 / max 300 是
// 针对自身域的常数，不随噪声走）：Night Sight §3.2 的 m_tz = d²/(d²+s·σ_tz²)
// 是同一思想的噪声归一化形式，暗光（主战场）必需。为什么不做 DFT 维纳收缩：
// HDR+ 论文 Fig.6 自己证明 DFT 域收缩对单个离群帧脆弱（离群抬高全部系数使信号
// 非稀疏），Night Sight 也演进为 pairwise+mismatch；且 16×16 DFT 在 GPU 上
// 不现实（hdr-plus 是 CPU OpenMP 实现）。
// 输出 RGBA8：R/G/B = 帧 1/2/3 的 m ∈ [0,1]（8bit 量化 0.4% 对权重可忽略），
// A = 1 占位。CPU 读回后算 w = max(1−c·m, 0) 写回位移场 z 通道（Fuse 零改动，
// z 的双线性插值天然给出 64px 平滑过渡），并统计 N_eff 供 σ 联动。
const char* kFsMfnrMismatch = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp usampler2D uRaw0;
uniform highp usampler2D uRaw1;
uniform highp usampler2D uRaw2;
uniform highp usampler2D uRaw3;
uniform highp sampler2D uOff1;
uniform highp sampler2D uOff2;
uniform highp sampler2D uOff3;
uniform ivec2 uSize;
uniform vec2 uTiles;    // mismatch 网格 = 4×tile 网格（cell = 32 mosaic px，v0.10.3）
uniform vec2 uOffTiles; // 位移场网格（tile 级，采样 off 用）
uniform float uTileM;   // cell 尺寸（mosaic px，= kMfnrTile/2）
// C 专项（2026-09-29）：σ² 从常数改为逐像素模型 var(x) = A·x + B（WB 后域）。
// d = ref − alt 的噪声底 = var(ref) + var(alt) = A·(r+a) + 2B（两独立样本），
// 随信号空间变化（暗部小、亮部大）——比旧的全局 ns_raw² 更贴合真实。
uniform vec3 uNoiseA;
uniform vec3 uNoiseB;
uniform vec4 uGainRatio;  // 0.12.83：per-frame 增益比（读噪声 B×(gr·s)²）
uniform float uS;      // mismatch 的 σ 缩放常数（标定旋钮）
uniform int uCfaLut;
uniform vec4 uBlack;
uniform float uWhite;
uniform vec3 uGain;
// 期 B bracket：逐帧曝光归一，与 kFsMfnrFuse 同口径（不 clamp，见其注释）。
uniform vec4 uFrameScale;
float frameLin(float x, float sc) { return x * sc; }
int chanAt(ivec2 p) {
    int k = ((p.y & 1) << 1) | (p.x & 1);
    return (uCfaLut >> (k << 1)) & 3;
}
float gainAt(int c) { return c == 0 ? uGain.x : (c == 1 ? uGain.y : uGain.z); }
float linAt(highp usampler2D t, ivec2 p) {
    p = clamp(p, ivec2(0), uSize - 1);
    float v = float(texelFetch(t, p, 0).r);
    float bl = uBlack[(p.y & 1) * 2 + (p.x & 1)];
    return max((v - bl) / max(uWhite - bl, 1.0), 0.0) * gainAt(chanAt(p));
}
vec3 off1At(vec2 uv) {
    vec2 px = uv * uOffTiles - 0.5;
    vec2 fr = fract(px);
    ivec2 i0 = ivec2(floor(px));
    ivec2 lo = clamp(i0, ivec2(0), ivec2(uTiles) - 1);
    ivec2 hi = clamp(i0 + ivec2(1), ivec2(0), ivec2(uTiles) - 1);
    vec3 a = texelFetch(uOff1, lo, 0).rgb;
    vec3 b = texelFetch(uOff1, ivec2(hi.x, lo.y), 0).rgb;
    vec3 c = texelFetch(uOff1, ivec2(lo.x, hi.y), 0).rgb;
    vec3 d = texelFetch(uOff1, hi, 0).rgb;
    return mix(mix(a, b, fr.x), mix(c, d, fr.x), fr.y);
}
vec3 off2At(vec2 uv) {
    vec2 px = uv * uTiles - 0.5;
    vec2 fr = fract(px);
    ivec2 i0 = ivec2(floor(px));
    ivec2 lo = clamp(i0, ivec2(0), ivec2(uTiles) - 1);
    ivec2 hi = clamp(i0 + ivec2(1), ivec2(0), ivec2(uTiles) - 1);
    vec3 a = texelFetch(uOff2, lo, 0).rgb;
    vec3 b = texelFetch(uOff2, ivec2(hi.x, lo.y), 0).rgb;
    vec3 c = texelFetch(uOff2, ivec2(lo.x, hi.y), 0).rgb;
    vec3 d = texelFetch(uOff2, hi, 0).rgb;
    return mix(mix(a, b, fr.x), mix(c, d, fr.x), fr.y);
}
vec3 off3At(vec2 uv) {
    vec2 px = uv * uTiles - 0.5;
    vec2 fr = fract(px);
    ivec2 i0 = ivec2(floor(px));
    ivec2 lo = clamp(i0, ivec2(0), ivec2(uTiles) - 1);
    ivec2 hi = clamp(i0 + ivec2(1), ivec2(0), ivec2(uTiles) - 1);
    vec3 a = texelFetch(uOff3, lo, 0).rgb;
    vec3 b = texelFetch(uOff3, ivec2(hi.x, lo.y), 0).rgb;
    vec3 c = texelFetch(uOff3, ivec2(lo.x, hi.y), 0).rgb;
    vec3 d = texelFetch(uOff3, hi, 0).rgb;
    return mix(mix(a, b, fr.x), mix(c, d, fr.x), fr.y);
}
float linSub(highp usampler2D t, ivec2 p, vec2 d) {
    ivec2 ph = ivec2(p.x & 1, p.y & 1);
    vec2 gmax = vec2(uSize - 1 - ph) * 0.5;
    vec2 s = clamp((vec2(p) + d - vec2(ph)) * 0.5, vec2(0.0), gmax);
    vec2 s0 = floor(s);
    vec2 w = s - s0;
    ivec2 hi = ph + ((uSize - 1 - ph) & ~1);
    ivec2 b = ph + ivec2(s0) * 2;
    float v00 = linAt(t, b);
    float v10 = linAt(t, min(b + ivec2(2, 0), hi));
    float v01 = linAt(t, min(b + ivec2(0, 2), hi));
    float v11 = linAt(t, min(b + ivec2(2, 2), hi));
    return mix(mix(v00, v10, w.x), mix(v01, v11, w.x), w.y);
}
void main() {
    ivec2 t = ivec2(gl_FragCoord.xy);
    // cell 中心 → 整图 uv → 采样所属 tile（uOffTiles 网格）的位移。
    // v0.10.3：网格 4×tiles（cell = 32 mosaic px）。为什么细：128px tile 的
    // mean(d²) 把叶缘带的局部运动残差摊薄到检不出（离线：叶缘 32px cell 错位
    // 8px 时 m 中位 0.68，128px tile 只有 ~0.06）—— 残影叶子正是这种"边缘带
    // 运动 + tile 主体静止"的混合。cell 细到 32px 后 mean 归约即够（无需子块
    // max）：可见残影来自高对比叶缘（cell 检出强），暗部平滑区的错位不可见、
    // 不需要检出 —— 逻辑自洽。
    vec2 uvFull = (vec2(t) + 0.5) / uTiles;
    vec2 o1 = off1At(uvFull).xy;
    vec2 o2 = off2At(uvFull).xy;
    vec2 o3 = off3At(uvFull).xy;
    ivec2 base = t * int(uTileM);
    int cm = int(uTileM);
    float s1 = 0.0, s2 = 0.0, s3 = 0.0;
    float va1 = 0.0, va2 = 0.0, va3 = 0.0;  // 逐点噪声底 var(ref)+var(alt)
    float n = 0.0;
    for (int dy = 0; dy < cm; dy += 4) {
        for (int dx = 0; dx < cm; dx += 4) {
            ivec2 p = base + ivec2(dx, dy);
            if (p.x >= uSize.x || p.y >= uSize.y) continue;
            int ch = chanAt(p);
            float av = ch == 0 ? uNoiseA.x : (ch == 1 ? uNoiseA.y : uNoiseA.z);
            float bv = ch == 0 ? uNoiseB.x : (ch == 1 ? uNoiseB.y : uNoiseB.z);
            float r = frameLin(linAt(uRaw0, p), uFrameScale.x);
            float a1 = frameLin(linSub(uRaw1, p, o1), uFrameScale.y);
            float a2 = frameLin(linSub(uRaw2, p, o2), uFrameScale.z);
            float a3 = frameLin(linSub(uRaw3, p, o3), uFrameScale.w);
            float d1 = r - a1;
            float d2 = r - a2;
            float d3 = r - a3;
            s1 += d1 * d1;
            s2 += d2 * d2;
            s3 += d3 * d3;
            // var(r)+var(a_z) = A·r'+B·(gr0·s0)² + A·a'+B·(gr_z·s_z)²
            // （σ²=A·x_raw+B 归一 ×s 后的展开；r'/a' 已是归一域值）。
            // 0.12.83：旧式 A·(r+a)+2B 是 s=1/gr=1 特例 —— 长帧（gr≈9.3）的
            // 读噪声低估 → 残差被判"运动"整帧回退。与 DFT 同一公式。
            float g0 = uGainRatio.x;
            float sr0 = uFrameScale.x;
            float b0t = bv * g0 * g0 * sr0 * sr0;
            float g1 = uGainRatio.y, s1s = uFrameScale.y;
            float g2 = uGainRatio.z, s2s = uFrameScale.z;
            float g3 = uGainRatio.w, s3s = uFrameScale.w;
            va1 += av * (r * sr0 + a1 * s1s) + b0t + bv * g1 * g1 * s1s * s1s;
            va2 += av * (r * sr0 + a2 * s2s) + b0t + bv * g2 * g2 * s2s * s2s;
            va3 += av * (r * sr0 + a3 * s3s) + b0t + bv * g3 * g3 * s3s * s3s;
            n += 1.0;
        }
    }
    float inv = 1.0 / max(n, 1.0);
    float v1 = s1 * inv, v2 = s2 * inv, v3 = s3 * inv;
    float kk1 = uS * max(va1 * inv, 1e-12);
    float kk2 = uS * max(va2 * inv, 1e-12);
    float kk3 = uS * max(va3 * inv, 1e-12);
    oColor = vec4(v1 / (v1 + kk1), v2 / (v2 + kk2), v3 / (v3 + kk3), 1.0);
})";

// kFsRawDenoiseFused：P0 同色 bilateral 的 MFNR 变体——输入换成 tex_fused_
//（融合后的线性 mosaic 值，已扣黑阶/WB），bilateral 逻辑与 kFsRawDenoise 逐字
// 相同。CFA 掩码在输出端补上（fused 纹理只存单通道值）。写成完整独立 shader
// 而非字符串拼接：validate-shaders.sh 只扫完整 R"(...)" 字面量，拼接变体会逃过
// 构建期校验（v0.5.13 黑屏事故的闸门）。
const char* kFsRawDenoiseFused = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform ivec2 uSize;
uniform int uCfaLut;
// C 专项：σ²(x) = A·x + B（同 kFsRawDenoise；融合输入的系数已 ÷neff）
uniform vec3 uNoiseA;
uniform vec3 uNoiseB;
uniform float uMix;   // v0.10.108：强度<1 的输出混合（σ 恒为真实噪声）
// ── v0.10.167 值域保护（HR 前置的必备件）────────────────────────────────
// HR（RAW 域 opposed）的输出是 scene-referred、**不钳到 1**（饱和像素被重建抬到
// 满阱之上，实测落点 1.0~1.24、G 分支上限 uGCap=2.0），而本 pass 的噪声模型
// σ²(x) = A·x + B 是在 **[0,1] 域**标定的（x = 扣黑阶/白阶归一/WB 后的线性值）。
// 若把 >1 的 HR 输出直接喂进 σ 与 d，σ 被线性外推放大 ⇒ 权重窗口变宽 ⇒ 刚被
// HR 重建出来的饱和边缘又被平滑掉（= "前置了但没效果"的老路）。
// 保护口径：**权重用钳位值、信号用原值** ——
//   · σ 与 d 的输入一律 clamp(x, 0, 1)（留在噪声模型的标定域内）；
//   · 累加进 acc 的仍然是原值 c（不丢 HR 刚补回来的亮度）。
// uWClamp = 0（默认，非前置路径）时 wAt(x) ≡ x ⇒ 逐位等于改动前的实现。
uniform float uWClamp;
// ── 0.12.187：独立饱和掩码（P0 接线）─────────────────────────────────────
// 现有保护只认"c0 > 1"（= HL 重建过的值）；物理饱和但未被重建的 photosite
// （v 停在满阱）照样进 bilateral ⇒ 核内实测色比被邻域拉平（灰斑/绿晕的直接机制）。
// mask=1 ⇒ 直接输出原值：既不参与、也不被平滑。
uniform highp sampler2D uSatMask;
uniform int uSatMaskOn;
int chanAt(ivec2 p) {
    int k = ((p.y & 1) << 1) | (p.x & 1);
    return (uCfaLut >> (k << 1)) & 3;
}
float fused0(ivec2 p) {
    p = clamp(p, ivec2(0), uSize - 1);
    return texelFetch(uTex, p, 0).r;
}
// 权重域的取值（uWClamp > 0.5 时钳到 [0,1]），只用于 σ 与 d，不用于信号累加。
float wAt(float x) { return uWClamp > 0.5 ? clamp(x, 0.0, 1.0) : x; }
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float c0 = fused0(p);
    // ── 0.12.157：**HL 重建值不参与 P0 平滑** ─────────────────────────────
    // HL（RAW 域饱和重建）输出是 scene-referred 的**合成值**（> 1，本机实测
    // 落点 1.0~1.24），它没有 RAW 噪声、色度也是按参考比重构的 —— 把它当测量
    // 值平滑，只会把"中性白核"和"粉色过渡"抹平。真机实测（10-29）：关掉
    // 【raw 降噪】灰带 + 黄绿边就消失 ⇒ 本 pass 就是洗色度的位置。
    // 判据用 `> 1` 而不是 mask：本域中性白 ≡ 1.0、任何通道的满阱都 ≥ 1.0，
    // 真测量的归一化值不可能超过 1（kNoDn 路径同样成立）。
    // 中心是重建值 ⇒ 整块区域都是合成值，直接输出、不做邻域运算。
    if (c0 > 1.0 ||
        (uSatMaskOn > 0 && texelFetch(uSatMask, p, 0).r > 0.5)) {
        int chh = chanAt(p);
        oColor = vec4(chh == 0 ? c0 : 0.0, chh == 1 ? c0 : 0.0,
                      chh == 2 ? c0 : 0.0, 1.0);
        return;
    }
    float w0 = wAt(c0);
    float v = c0;
    int ch = chanAt(p);
    float av = ch == 0 ? uNoiseA.x : (ch == 1 ? uNoiseA.y : uNoiseA.z);
    float bv = ch == 0 ? uNoiseB.x : (ch == 1 ? uNoiseB.y : uNoiseB.z);
    float sr2 = av * w0 + bv;
    if (sr2 > 1e-12) {
        float sr = sqrt(sr2);
        float wsum = 1.0, acc = c0;
        for (int dy = -4; dy <= 4; dy += 2)
            for (int dx = -4; dx <= 4; dx += 2) {
                if (dx == 0 && dy == 0) continue;
                float c = fused0(p + ivec2(dx, dy));
                // 重建值（>1）既无噪声也不是测量，权重恒 0（否则 wAt 把它钳到
                // 1.0 ⇒ d=0 ⇒ **最高权重**混进真实测量值旁边）。
                if (c > 1.0) continue;
                float d = (wAt(c) - w0) / sr;
                float w = exp(-0.5 * d * d
                              - 0.5 * float(dx * dx + dy * dy) / 6.48);
                acc += w * c;   // 信号用原值（wAt 只进权重）
                wsum += w;
            }
        v = mix(c0, acc / wsum, uMix);   // v0.10.108：强度<1 = 线性混合（各亮度段一致）
    }
    oColor = vec4(ch == 0 ? v : 0.0, ch == 1 ? v : 0.0, ch == 2 ? v : 0.0, 1.0);
})";

// kFsRawDenoiseFusedTs（D-2 / v0.10.58）：融合阶段的**时域 × 空间联合**采样。
// 依据：PC 的 Spatial merge 是"每帧 3×3 quad × 多帧"，而我们的 P0 只能对**已融合**
// 的 tex_fused_ 取 9 个空间样本 —— 样本彼此不独立（融合已重塑其噪声结构），
// 这就是实测总降噪只有 2.3× 而理论应为 √(4×9)=6× 的原因。本 shader 改为对
// **4 张原始帧各自**取 3×3 同相位邻域 = 36 个独立噪声样本；空间核尺寸与现有 P0
// 完全相同（±4 mosaic、步长 2 = ±2 quad，真机已验证不糊纹理），只增加时域维度
// 的独立样本，不加大空间模糊。
// 值域阈值：样本 c 与参考 ref（tex_fused_）之差的方差 = σ_single² + σ_fused²
//   = σ_fused²·(neff + 1)，故 sr = σ_fused·√(neff+1) = √5·σ_fused（uSrScale）。
const char* kFsRawDenoiseFusedTs = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp usampler2D uRaw0;
uniform highp usampler2D uRaw1;
uniform highp usampler2D uRaw2;
uniform highp usampler2D uRaw3;
uniform highp sampler2D uOff1;
uniform highp sampler2D uOff2;
uniform highp sampler2D uOff3;
uniform highp sampler2D uFused;
// 0.12.187：独立饱和掩码（Ts 路径，纹理单元 8）
uniform highp sampler2D uSatMask;
uniform int uSatMaskOn;
// v0.10.167：HR 前置的值域保护开关（1 = σ 用 clamp(ref,0,1)；见 main 内注释）
uniform float uWClamp;
uniform ivec2 uSize;
uniform ivec2 uOffTiles;
uniform int uCfaLut;
uniform vec4 uBlack;
uniform float uWhite;
uniform vec3 uGain;
uniform vec3 uNoiseA;
uniform vec3 uNoiseB;
uniform float uMix;   // v0.10.108：强度<1 的输出混合（σ 恒为真实噪声）
uniform float uSrScale;
// v0.10.75（D-2 杠杆 B）：0 = 位移 round 到最近偶数（旧行为，保相位但量化
// 2 mosaic px）；1 = 逐通道网格亚像素双线性（linSubZ，量化降到亚像素）。
// 离线评估（tools/p0-subpixel-eval.py，与真值直接比误差）：σ=0.002 时旧行为
// 残余噪声 0.00136 **比不做邻域平均（0.00104）还差 31%** —— 错位偏差大于它
// 换来的降噪；亚像素 0.00107。σ=0.004/0.008 时亚像素分别好 14%/12%。
uniform float uSub;
int chanAt(ivec2 p) {
    int k = ((p.y & 1) << 1) | (p.x & 1);
    return (uCfaLut >> (k << 1)) & 3;
}
float gainAt(int c) { return c == 0 ? uGain.x : (c == 1 ? uGain.y : uGain.z); }
float linZ(int z, ivec2 p) {
    p = clamp(p, ivec2(0), uSize - 1);
    uint raw;
    if (z == 0) raw = texelFetch(uRaw0, p, 0).r;
    else if (z == 1) raw = texelFetch(uRaw1, p, 0).r;
    else if (z == 2) raw = texelFetch(uRaw2, p, 0).r;
    else raw = texelFetch(uRaw3, p, 0).r;
    float bl = uBlack[(p.y & 1) * 2 + (p.x & 1)];
    return max((float(raw) - bl) / max(uWhite - bl, 1.0), 0.0) * gainAt(chanAt(p));
}
vec2 offAt(highp sampler2D t, vec2 m) {
    vec2 px = (m / vec2(uSize)) * vec2(uOffTiles) - 0.5;
    vec2 fr = fract(px);
    ivec2 i0 = ivec2(floor(px));
    ivec2 lo = clamp(i0, ivec2(0), uOffTiles - 1);
    ivec2 hi = clamp(i0 + ivec2(1), ivec2(0), uOffTiles - 1);
    vec3 a = texelFetch(t, lo, 0).rgb;
    vec3 b = texelFetch(t, ivec2(hi.x, lo.y), 0).rgb;
    vec3 c = texelFetch(t, ivec2(lo.x, hi.y), 0).rgb;
    vec3 d = texelFetch(t, hi, 0).rgb;
    return mix(mix(a, b, fr.x), mix(c, d, fr.x), fr.y).xy;
}
// 逐通道网格亚像素双线性（与 kFsMfnrFuse 的 linSub 同式）：mosaic 视为 2×2
// 打包网格，R/G/Gb/B 各自是间隔 2px 的完整点阵 —— 4 个 tap 全部同相位，
// R 只与 R 插值，零色度污染。位移因此可以是任意亚像素，不必偶数化。
float linSubZ(int z, ivec2 p, vec2 d) {
    ivec2 ph = ivec2(p.x & 1, p.y & 1);
    vec2 gmax = vec2(uSize - 1 - ph) * 0.5;
    vec2 s = clamp((vec2(p) + d - vec2(ph)) * 0.5, vec2(0.0), gmax);
    vec2 s0 = floor(s);
    vec2 w = s - s0;
    ivec2 hi = ph + ((uSize - 1 - ph) & ~1);
    ivec2 b = ph + ivec2(s0) * 2;
    float v00 = linZ(z, b);
    float v10 = linZ(z, min(b + ivec2(2, 0), hi));
    float v01 = linZ(z, min(b + ivec2(0, 2), hi));
    float v11 = linZ(z, min(b + ivec2(2, 2), hi));
    return mix(mix(v00, v10, w.x), mix(v01, v11, w.x), w.y);
}
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float ref = texelFetch(uFused, p, 0).r;
    // 0.12.187：饱和核内不平滑（见 kFsRawDenoiseFused 的 uSatMask 注释）
    if (uSatMaskOn > 0 && texelFetch(uSatMask, p, 0).r > 0.5) {
        int chh = chanAt(p);
        oColor = vec4(chh == 0 ? ref : 0.0, chh == 1 ? ref : 0.0,
                      chh == 2 ? ref : 0.0, 1.0);
        return;
    }
    int ch = chanAt(p);
    float av = ch == 0 ? uNoiseA.x : (ch == 1 ? uNoiseA.y : uNoiseA.z);
    float bv = ch == 0 ? uNoiseB.x : (ch == 1 ? uNoiseB.y : uNoiseB.z);
    // v0.10.167 值域保护：ref 在 HR 前置后是 scene-referred、可 >1（tex_hl_），
    // 而噪声模型 σ²(x)=A·x+B 标定在 [0,1]。σ 用钳位值（留在标定域），
    // ref 本身仍以原值参与信号与 d 的分子 —— 与 kFsRawDenoiseFused 同口径。
    // uWClamp=0 时 rw ≡ ref ⇒ 逐位等于改动前。
    float rw = uWClamp > 0.5 ? clamp(ref, 0.0, 1.0) : ref;
    float sr = sqrt(max(av * rw + bv, 1e-12)) * uSrScale;
    float wsum = 1.0, acc = ref;
    vec2 ctr = vec2(p) + 0.5;
    for (int z = 0; z < 4; ++z) {
        ivec2 off = ivec2(0);
        vec2 dz = vec2(0.0);
        if (z > 0) {
            if (z == 1) dz = offAt(uOff1, ctr);
            else if (z == 2) dz = offAt(uOff2, ctr);
            else dz = offAt(uOff3, ctr);
            off = ivec2(floor(dz * 0.5 + 0.5)) * 2;   // round 偶数保相位（uSub=0）
        }
        for (int dy = -4; dy <= 4; dy += 2)
            for (int dx = -4; dx <= 4; dx += 2) {
                if (dx == 0 && dy == 0) continue;
                float c = uSub > 0.5
                              ? linSubZ(z, p + ivec2(dx, dy), dz)
                              : linZ(z, p + off + ivec2(dx, dy));
                float d = (c - ref) / sr;
                // v0.10.66：σ² 25 回退到 6.48。放宽权重后真机 nzPre→nzPost 从 2.38x
                // **降到** 1.68x（p0w 26.4→35.3），且用户观察到边缘"降噪断层"。
                // 原因：N_eff 模型只算噪声项，漏了偏差项 —— 外围样本落在略不同的
                // 场景位置（对齐仅 128px tile 精度），其差异是系统偏差而非噪声，
                // 纳入平均后方差反升、边缘被跨边缘样本抹平。真正的杠杆是**对齐
                // 精度**，不是权重分布。
                float w = exp(-0.5 * d * d - 0.5 * float(dx * dx + dy * dy) / 6.48);
                acc += w * c;
                wsum += w;
            }
    }
    float v = mix(ref, acc / wsum, uMix);   // v0.10.108：强度<1 线性混合
    // v0.10.61 诊断：alpha = 权重和 wsum（含中心 1.0）。样本数已足够（每帧 24 个
    // 同相位邻域 × 4 帧 = 96），实测收益却远低于 √96 —— 需知**实际采纳了多少**，
    // 故把 wsum 带出（tex_mos_ 的 alpha 无下游消费者），由 host 读回记入日志。
    oColor = vec4(ch == 0 ? v : 0.0, ch == 1 ? v : 0.0, ch == 2 ? v : 0.0, wsum);
})";

// ── NN 降噪 pass（v0.10.102/104）：PMRID TFLite 的域桥 shader ─────────────
// kFsNnPack：mosaic（R16F fused 域 / R16UI 原始域，uSrc 分支）→ packed Bayer
// 4ch 半分辨率 RGBA（× uScale）。供 glReadPixels 后 CPU 组 tile 喂推理。
// 域口径（v0.10.105 对照官方 run_benchmark.py 重定案）：
//   · 输入增益 uScale = cvt_k × 256，cvt_k = S_train/S_input（S_input = 单帧 S/neff，
//     融合路径方差已折减）。S_train = K(1600)/V = 1.0093e-3（img01 域，见 kNnAnchorS）。
//     0.10.102 误用 959/256=3.75（比官方小 68 倍，网络输出崩溃：压暗 44%、tile
//     网格、点阵、发绿）；0.10.104 修了量纲但 anchor 用本机 S(1600)=4.5e-4、且漏
//     算 MFNR 的 ÷neff ⇒ 输入噪声只有训练水平的 ~1/9，过度平滑（文字糊 + 块状伪影）。
//     cvt_b（read 项平移）在 img01 域 ~7e-4，实测并入无影响。
//   · 通道序 = 官方语义 (R,Gr,Gb,B)（v0.10.106 对齐）：官方 bggr2rggb(rot180) +
//     bayer2rggb 打包的等价形式（rot180 只是"把 BGGR 摆成 RGGB"的手段，逐通道
//     核对一致）。0.10.102–0.105 用 (B,Gr,Gb,R)，ch0/ch3 与官方对调。打包图的
//     空间朝向仍不翻转（官方是翻转的）—— 离线实测该项仅值 ~1%，按最小改动暂留。
//     各通道相位由 uPhX/uPhY 下发（CFA 无关）。
const char* kFsNnPack = R"(#version 300 es
precision highp float;
precision highp usampler2D;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uFused;   // R16F 融合 mosaic（线性，已扣黑阶+WB）
uniform highp usampler2D uRaw;    // R16UI 单帧原始 mosaic（未消费时绑 dummy）
uniform ivec2 uSize;              // mosaic 全尺寸
uniform int uCfaLut;
uniform vec4 uBlack;              // 按相位 (0,0)(1,0)(0,1)(1,1)
uniform float uWhite;
uniform vec3 uGain;
uniform int uSrc;                 // 0 = fused 域 / 1 = 原始域
uniform float uScale;             // cvt_k × 256（官方 inp_scale）
uniform ivec4 uPhX;               // 语义序 (R,Gr,Gb,B) 各通道的相位 x 偏移
uniform ivec4 uPhY;               // 同上，y 偏移
int chanAt(ivec2 p) {
    int k = ((p.y & 1) << 1) | (p.x & 1);
    return (uCfaLut >> (k << 1)) & 3;
}
float gainAt(int c) { return c == 0 ? uGain.x : (c == 1 ? uGain.y : uGain.z); }
float linFused(ivec2 p) {
    p = clamp(p, ivec2(0), uSize - 1);
    float v = texelFetch(uFused, p, 0).r;
    return clamp(v / max(gainAt(chanAt(p)), 1e-4), 0.0, 1.0);
}
float linRaw(ivec2 p) {
    p = clamp(p, ivec2(0), uSize - 1);
    float raw = float(texelFetch(uRaw, p, 0).r);
    float bl = uBlack[(p.y & 1) * 2 + (p.x & 1)];
    return clamp(max((raw - bl) / max(uWhite - bl, 1.0), 0.0), 0.0, 1.0);
}
float linAt(ivec2 p) { return uSrc > 0 ? linRaw(p) : linFused(p); }
float pick(float v0, float v1, float v2, float v3, int phx, int phy) {
    // 相位编号：0=(0,0) 1=(1,0) 2=(0,1) 3=(1,1)
    if (phy == 0) return phx == 0 ? v0 : v1;
    return phx == 0 ? v2 : v3;
}
void main() {
    ivec2 q = ivec2(gl_FragCoord.xy);   // 半分辨率 packed 网格
    ivec2 p = q * 2;
    float v0 = linAt(p);
    float v1 = linAt(p + ivec2(1, 0));
    float v2 = linAt(p + ivec2(0, 1));
    float v3 = linAt(p + ivec2(1, 1));
    float cR  = pick(v0, v1, v2, v3, uPhX.x, uPhY.x);
    float cGr = pick(v0, v1, v2, v3, uPhX.y, uPhY.y);
    float cGb = pick(v0, v1, v2, v3, uPhX.z, uPhY.z);
    float cB  = pick(v0, v1, v2, v3, uPhX.w, uPhY.w);
    oColor = vec4(cR, cGr, cGb, cB) * uScale;
})";

// kFsNnUnpack：推理结果（RGBA16F 半分辨率 packed Bayer，官方语义序）→ mosaic
// R16F（线性 + WB 域，与 tex_fused_ 同域 —— 下游 P0 直读展开 / RCD 零改动）。
// 通道反查：当前像素相位 → uChMap[相位] = packed 通道 idx；R16F attachment
// 只消费 r 通道。
const char* kFsNnUnpack = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uPacked;
uniform ivec2 uSize;
uniform vec3 uGain;
uniform int uCfaLut;
uniform float uInvScale;   // ÷(cvt_k × 256)
uniform ivec4 uChMap;      // 空间序相位 (0,0)(1,0)(0,1)(1,1) → packed 通道 idx
int chanAt(ivec2 p) {
    int k = ((p.y & 1) << 1) | (p.x & 1);
    return (uCfaLut >> (k << 1)) & 3;
}
float gainAt(int c) { return c == 0 ? uGain.x : (c == 1 ? uGain.y : uGain.z); }
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int ph = (p.y & 1) * 2 + (p.x & 1);
    int ch = ph == 0 ? uChMap.x : (ph == 1 ? uChMap.y : (ph == 2 ? uChMap.z : uChMap.w));
    vec4 t = texelFetch(uPacked, ivec2(p.x >> 1, p.y >> 1), 0);
    float v = ch == 0 ? t.x : (ch == 1 ? t.y : (ch == 2 ? t.z : t.w));
    float g = gainAt(chanAt(p));
    oColor = vec4(v * uInvScale * g, 0.0, 0.0, 1.0);
})";

// kFsMfnrFft（降噪专项 A，2026-09-29）：HDR+ §5 式 6/7 的 pairwise DFT 逐频
// 收缩合并，替换/并联 kFsMfnrFuse。与 spike（SpikeFftMerge.cpp v2 变体，真机
// 207.5ms/读回校验通过）同源的寄存器 FFT 架构，按真机管线扩展：
//   · 按 CFA 平面独立合并（HDR+ 原文 "merge each plane of the Bayer image
//     independently"）：workgroup z 维 = 平面 0..3，平面 p 的 mosaic 相位偏移
//     (ox,oy) = (p&1, p>>1)，平面像素 (px,py) ↔ mosaic (2px+ox, 2py+oy) ——
//     16×16 平面 tile 即 mosaic 32×32 区域，各平面输出像素集互不相交（CFA
//     分区），4 平面一次 dispatch 无写竞争。
//   · 对齐：现有 CPU 位移场（mosaic 域亚像素）在 tile 中心双线性采样后
//     **round 到最近偶数**（= 平面域整数平移，Bayer 相位不翻转；HDR+ 原版
//     "not any more precise than pixel level"），DFT 域因此无需相位校正。
//   · 半重叠（步长 8 平面 px）+ 修改升余弦窗 ½−½cos(2π(x+½)/16)；tile 网格
//     含 -1 行/列使每 mosaic 像素恰被 2×2 tile 覆盖、窗和恒 1（overlap-add
//     直接累加恢复无窗图）。写累加用 imageLoad+store —— 同平面同组
//     （tx≡2gx−1 mod 2）tile 互不重叠，故按 (gx,gy) 4 次 dispatch 串行化。
//   · σ：内核内归约参考帧 tile RMS（未加窗，HDR+ §5 "RMS of the samples in
//     the tile"），σ² = A_c·RMS + B_c（C 专项模型，WB 后域；host 按平面通道
//     展开成 uPlaneA/uPlaneB，强度系数 k² 已含）。收缩常数 c = 8·n²/4²·2 =
//     256（HDR+ §5 推导，spike 真机验证口径）。
//   · 式 6：T̃0 = (1/N)[T0 + Σz(Tz + Az(T0−Tz))]；式 7：Az = |Dz|²/(|Dz|²+cσ²)。
//   · per-tile neff（NS §3.3 "按实际合并更新 σ"）：out = (1/N)[(1+ΣAz)T0 +
//     Σ(1−Az)Tz] 的频率平均方差折减 f(ω) = N²/[(1+ΣAz)² + Σ(1−Az)²]，per-tile
//     均值写入 uNeff（层 = 平面；R = tx 偶 tile、G = tx 奇 tile，−1 = 越界）
//     供 CPU 读回 → P0/P4 系数折减与日志。
// 线程映射（同 spike，2 tile/workgroup）：载入/行 FFT/列 FFT = tid<128（每线程
// 1 行/列），收缩 = 全 256 线程（每线程 2 bin），IDFT/写回 = tid<32。
// 输出 = uOut（R32F scratch，ES 3.1 规范保证 image load+store 的三种格式之一；
// ES 3.2 的 r16f/rgba16f **禁止** load+store 混合、glslang ES profile 也只放行
// r32f）。overlap-add 按 (gx,gy) 4 次 dispatch **串行**累加（image read-modify-
// write 非原子，只有 dispatch 间 barrier 能保证互斥），最后 kFsMfnrFftSum 把
// scratch 转渲染进 tex_fused_（与 fuse 路径同域同格式，后段零改动）。
const char* kFsMfnrFft = R"(#version 310 es
precision highp float;
precision highp int;
precision highp usampler2D;

layout(local_size_x = 16, local_size_y = 16) in;

// 输入 = 4 张独立 usampler2D（v0.10.48）：usampler2DArray 是全部失败版本里
// 唯一从未单独验证过的采样路径 —— spike（sampler2DArray R16F）在同一拍照
// 上下文完美工作（spikeOk:true），而已验证的 usampler 采样全部是 2D 版
//（kFsMfnrFuse/Pyr/mismatch）。若 array 版返回全 0，则"输入全 0 → 输出全 0"
// 能解释 0.10.38 以来的全部观测（dftGpu 477-798ms = 真实计算、输出恒 0）。
uniform highp usampler2D uRaw0;
uniform highp usampler2D uRaw1;
uniform highp usampler2D uRaw2;
uniform highp usampler2D uRaw3;
uniform highp sampler2D uOff1;
uniform highp sampler2D uOff2;
uniform highp sampler2D uOff3;
uniform int uCfaLut;
// v0.10.43：image2DArray 的 imageStore 在 X300 Mali 上计算照跑、写入全丢
//（dftGpu 577ms vs dftCnt 0）—— spike 真机验证过的 writeonly 是 **image2D**。
// uOut 拆 4 张（每相位组一张，shader 内静态分支选择）；uLayer 保留作分支量。
layout(rgba16f, binding = 0) writeonly uniform highp image2D uOut0;
layout(rgba16f, binding = 1) writeonly uniform highp image2D uOut1;
layout(rgba16f, binding = 2) writeonly uniform highp image2D uOut2;
layout(rgba16f, binding = 3) writeonly uniform highp image2D uOut3;
uniform int uLayer;  // 本 dispatch 的输出层 = gy*2+gx（组内 tile 互不重叠）
uniform int uRows;   // 本组 tile 行数（plane 并入 y 维：wgRow = wg.y % uRows）
uniform ivec2 uSize;      // mosaic 尺寸
uniform ivec2 uOffTiles;  // 位移场网格（对齐 tile 128 mosaic px）
uniform int uTxMax;       // 平面 tile x 上限（ceil(pw/8)-1；ty 同理由 dispatch 保证）
uniform vec4 uBlack;
uniform float uWhite;
uniform vec3 uGain;
// 期 B bracket（0.11.66）：逐帧曝光归一（短帧 = 长/短），clamp 到白点。
uniform vec4 uFrameScale;
uniform vec4 uGainRatio;  // 0.12.83：per-frame 增益比（读噪声 B×(gr·s)²）
uniform vec4 uPlaneA;     // 平面 0..3 的 A 系数（按 cfaLut 展开，含强度 k²）
uniform vec4 uPlaneB;
uniform float uC;         // 收缩常数（256）
uniform ivec2 uGroup;     // 本轮 dispatch 的 tile 相位组 (gx, gy) ∈ {0,1}²
uniform highp vec2 uTw[8];

shared float sre[2][4][16][16];
shared float sim[2][4][16][16];
shared float sRmsRow[2][16];  // 参考帧行平方和（未加窗 RMS 归约用）
shared float sRms[2];
// 修改升余弦窗查表（v0.10.77 性能收口）：16 个值只有 16 种，原来在内层循环里
// 逐点算 wx/wyy —— 每个 workgroup **4096 次 cos**（128 行 × 16 点 × 2），而
// Mali 上 cos 走 SFU（吞吐远低于 FMA）。改为算 16 次存 shared。数值完全一致：
// 仍是同一个 0.5−0.5cos(2π(i+0.5)/16) 表达式，只是不再重复求值。
shared float sWin[16];

const float TWO_PI = 6.283185307179586;

int chanAt(ivec2 p) {
    int k = ((p.y & 1) << 1) | (p.x & 1);
    return (uCfaLut >> (k << 1)) & 3;
}
float gainAt(int c) { return c == 0 ? uGain.x : (c == 1 ? uGain.y : uGain.z); }
float linAt(int z, ivec2 p) {
    p = clamp(p, ivec2(0), uSize - 1);
    // image/sampler uniform 均须静态索引 —— z 是载入循环常量展开
    uint v;
    if (z == 0) {
        v = texelFetch(uRaw0, p, 0).r;
    } else if (z == 1) {
        v = texelFetch(uRaw1, p, 0).r;
    } else if (z == 2) {
        v = texelFetch(uRaw2, p, 0).r;
    } else {
        v = texelFetch(uRaw3, p, 0).r;
    }
    float bl = uBlack[(p.y & 1) * 2 + (p.x & 1)];
    float sc = z == 0 ? uFrameScale.x : (z == 1 ? uFrameScale.y
                       : (z == 2 ? uFrameScale.z : uFrameScale.w));
    float lin = max((float(v) - bl) / max(uWhite - bl, 1.0), 0.0) * gainAt(chanAt(p));
    // 不 clamp —— 与 kFsMfnrFuse 同口径（高光交给曲线）
    return lin * sc;
}
// 位移场双线性（与 kFsMfnrFuse 的 offNAt 同式；uOffTiles 为其网格）。
vec2 offAt(highp sampler2D t, vec2 m) {
    vec2 px = (m / vec2(uSize)) * vec2(uOffTiles) - 0.5;
    vec2 fr = fract(px);
    ivec2 i0 = ivec2(floor(px));
    ivec2 lo = clamp(i0, ivec2(0), uOffTiles - 1);
    ivec2 hi = clamp(i0 + ivec2(1), ivec2(0), uOffTiles - 1);
    vec3 a = texelFetch(t, lo, 0).rgb;
    vec3 b = texelFetch(t, ivec2(hi.x, lo.y), 0).rgb;
    vec3 c = texelFetch(t, ivec2(lo.x, hi.y), 0).rgb;
    vec3 d = texelFetch(t, hi, 0).rgb;
    return mix(mix(a, b, fr.x), mix(c, d, fr.x), fr.y).xy;
}
// alt 帧位移：tile 中心采样 → round 到最近偶数（平面域整数平移保相位）。
ivec2 tileOff(highp sampler2D t, vec2 centerM) {
    vec2 dz = offAt(t, centerM);
    return ivec2(floor(dz * 0.5 + 0.5)) * 2;
}

// 寄存器内 1D FFT（spike v2 逐字同构；sgn=-1 正变换、+1 逆变换）
void fftRow(int t, int z, int y, float sgn) {
    float re[16];
    float im[16];
    for (int i = 0; i < 16; ++i) {
        re[i] = sre[t][z][y][i];
        im[i] = sim[t][z][y][i];
    }
    int j = 0;
    for (int i = 1; i < 16; ++i) {
        int bit = 8;
        for (int b = 0; b < 4; ++b) {
            if ((j & bit) == 0) { break; }
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
        if (i < j) {
            float tr = re[i];
            re[i] = re[j];
            re[j] = tr;
            float ti = im[i];
            im[i] = im[j];
            im[j] = ti;
        }
    }
    for (int len = 2; len <= 16; len <<= 1) {
        int hlen = len >> 1;
        for (int blk = 0; blk < 16; blk += len) {
            for (int k = 0; k < hlen; ++k) {
                int idx = (16 * k) / len;
                float wr = uTw[idx].x;
                float wi = sgn * uTw[idx].y;
                int i0 = blk + k;
                int i1 = i0 + hlen;
                float xr = re[i1];
                float xi = im[i1];
                float tr = xr * wr - xi * wi;
                float ti = xr * wi + xi * wr;
                re[i1] = re[i0] - tr;
                im[i1] = im[i0] - ti;
                re[i0] += tr;
                im[i0] += ti;
            }
        }
    }
    for (int i = 0; i < 16; ++i) {
        sre[t][z][y][i] = re[i];
        sim[t][z][y][i] = im[i];
    }
}
void fftCol(int t, int z, int x, float sgn) {
    float re[16];
    float im[16];
    for (int i = 0; i < 16; ++i) {
        re[i] = sre[t][z][i][x];
        im[i] = sim[t][z][i][x];
    }
    int j = 0;
    for (int i = 1; i < 16; ++i) {
        int bit = 8;
        for (int b = 0; b < 4; ++b) {
            if ((j & bit) == 0) { break; }
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
        if (i < j) {
            float tr = re[i];
            re[i] = re[j];
            re[j] = tr;
            float ti = im[i];
            im[i] = im[j];
            im[j] = ti;
        }
    }
    for (int len = 2; len <= 16; len <<= 1) {
        int hlen = len >> 1;
        for (int blk = 0; blk < 16; blk += len) {
            for (int k = 0; k < hlen; ++k) {
                int idx = (16 * k) / len;
                float wr = uTw[idx].x;
                float wi = sgn * uTw[idx].y;
                int i0 = blk + k;
                int i1 = i0 + hlen;
                float xr = re[i1];
                float xi = im[i1];
                float tr = xr * wr - xi * wi;
                float ti = xr * wi + xi * wr;
                re[i1] = re[i0] - tr;
                im[i1] = im[i0] - ti;
                re[i0] += tr;
                im[i0] += ti;
            }
        }
    }
    for (int i = 0; i < 16; ++i) {
        sre[t][z][i][x] = re[i];
        sim[t][z][i][x] = im[i];
    }
}

void main() {
    ivec2 lid = ivec2(gl_LocalInvocationID.xy);
    ivec2 wg = ivec2(gl_WorkGroupID.xy);
    int tid = lid.y * 16 + lid.x;
    if (tid < 16) {
        sWin[tid] = 0.5 - 0.5 * cos(TWO_PI * (float(tid) + 0.5) / 16.0);
    }
    barrier();
    // plane 并入 y 维（z 恒 1 = spike 真机口径；v0.10.40/41 的 gl_WorkGroupID.z
    // 疑似被驱动放弃——实为 0.10.41 的 image 格式错配，双保险都做）
    int plane = int(wg.y) / uRows;
    int wgRow = int(wg.y) % uRows;
    int ox = plane & 1;
    int oy = plane >> 1;
    // 本 workgroup 的两个水平相邻同组 tile。组 gx 的 host 列举是
    // tx ∈ {-1, 1, 3, ...}（gx=0）/ {0, 2, 4, ...}（gx=1），即 tx = (gx−1) + 2k；
    // 每 wg 取 k = 2·wg.x, 2·wg.x+1 → **tx0 = 4·wg.x + gx − 1**。
    // ⚠️ v0.10.50 真机是 4·wg.x + 2·gx − 1：gx=1 也落到奇数 tile，两组重复处理
    // 同一批 tile、偶数 tile 整批漏写 → 每层覆盖错位，收口 4 层相加混入未定义
    // 内容（绿色网格纹 + 偏亮，avgLuma 191）。镜像复算见
    // test_temp/analysis/fft_tile_cover.py（修正后窗和恒 1.0000）。
    int tx0 = 4 * wg.x + uGroup.x - 1;
    int tx1 = tx0 + 2;
    int ty = 2 * wgRow + uGroup.y - 1;
    bool has1 = tx1 <= uTxMax;

    if (tid < 128) {
        int tt = tid / 64;
        int zz = (tid / 16) % 4;
        int yy = tid % 16;
        int tx = (tt == 0) ? tx0 : tx1;
        if ((tt == 0) || has1) {
            // 位移：tile 中心（mosaic 域）采样，round 偶数
            ivec2 doff = ivec2(0);
            if (zz > 0) {
                vec2 centerM = vec2(float(2 * (tx * 8 + 8) + ox) + 0.5,
                                    float(2 * (ty * 8 + 8) + oy) + 0.5);
                doff = (zz == 1) ? tileOff(uOff1, centerM)
                                 : (zz == 2 ? tileOff(uOff2, centerM)
                                            : tileOff(uOff3, centerM));
            }
            float rmsRow = 0.0;
            for (int lx = 0; lx < 16; ++lx) {
                int px = tx * 8 + lx;
                int py = ty * 8 + yy;
                ivec2 m = ivec2(2 * px + ox, 2 * py + oy) +
                          (zz > 0 ? doff : ivec2(0));
                float v = linAt(zz, m);
                if (zz == 0) rmsRow += v * v;  // 未加窗 RMS（HDR+ §5）
                sre[tt][zz][yy][lx] = v * sWin[lx] * sWin[yy];
                sim[tt][zz][yy][lx] = 0.0;
            }
            if (zz == 0) sRmsRow[tt][yy] = rmsRow;
            fftRow(tt, zz, yy, -1.0);
        } else if (zz == 0) {
            sRmsRow[tt][yy] = 0.0;
        }
    }
    barrier();

    if (tid < 128) {
        int tt = tid / 64;
        int zz = (tid / 16) % 4;
        int xx = tid % 16;
        if ((tt == 0) || has1) fftCol(tt, zz, xx, -1.0);
    }
    if (tid < 32) {
        // 参考帧 tile RMS（与列 FFT 并行；不同 shared 区）
        int tt = tid / 16;
        if ((tt == 0) || has1) {
            float s = 0.0;
            for (int y = 0; y < 16; ++y) s += sRmsRow[tt][y];
            sRms[tt] = sqrt(s * (1.0 / 256.0));
        } else {
            sRms[tt] = 0.0;
        }
    }
    barrier();

    // 式 6/7 收缩合并（spike 同构）+ per-bin 方差折减
    // 0.12.83：per-frame σ² = A·s·x' + B·(gr·s)²（σ²=A·x_raw+B 归一 ×s 后的
    // 展开；gr = ISO_i/ISO_0 的增益比，读噪声 ∝ gain²）。0.12.82 只做了 s²
    //（短帧正确），长帧（gr≈9.3）的读噪声低估 ~86× → 残差被判"运动"整帧
    // 回退参考帧，暗部 SNR 收益丢失 —— 本版补齐。
    float sig2 = uPlaneA[plane] * sRms[tid >> 7] + uPlaneB[plane];
    for (int rep = 0; rep < 2; ++rep) {
        int idx = tid * 2 + rep;
        int tt = idx / 256;   // tid<128 → 0、tid≥128 → 1
        int bin = idx % 256;
        if ((tt == 0) || has1) {
            int by = bin / 16;
            int bx = bin % 16;
            float r0 = sre[tt][0][by][bx];
            float i0 = sim[tt][0][by][bx];
            float accR = r0;
            float accI = i0;
            // v0.12.17：**去掉 const**。`uC` / `sig2` 是 uniform 与运行期量，不是常量
            // 表达式，GLSL ES 3.0 不允许 `const` 这样初始化 ⇒ 整个 shader 编译失败
            //（真机日志：`S0013: Initializer for const variable 'cs2_base' must be a
            // constant expression` + `mfnr.fft.compile_fail`），MFNR 一直在走降级路径。
            float cs2_base = uC * sig2 + 1e-12;  // 0.12.83 起弃用（见下 cs2）
            for (int zz = 1; zz < 4; ++zz) {
                float rz = sre[tt][zz][by][bx];
                float iz = sim[tt][zz][by][bx];
                float dr = r0 - rz;
                float di = i0 - iz;
                float d2 = dr * dr + di * di;
                // 期 B bracket（设计 §3.3 路径 B）：短帧归一后噪声 ×ratio ⇒ σ² ×ratio²。
                // 不缩放时短帧的噪声残差被当成"真实差异"，高光处会被判成不一致而回退。
                float scl = (zz == 1) ? uFrameScale.y
                            : (zz == 2 ? uFrameScale.z : uFrameScale.w);
                float gr = (zz == 1) ? uGainRatio.y
                           : (zz == 2 ? uGainRatio.z : uGainRatio.w);
                // σ²_i(x') = A·s·x' + B·(gr·s)²：A 项 = A·sRms·s²（sRms 是 x' 域
                // 统计，线性缩放）；B 项乘 (gr·s)²。scl² 在分母里已经乘了 s²，
                // 这里只需把 B 的 gr² 补进 cs2_base。
                float cs2 = uC * (uPlaneA[plane] * sRms[tid >> 7] +
                                  uPlaneB[plane] * gr * gr) *
                                scl * scl +
                            1e-12;
                float a = d2 / (d2 + cs2);
                accR += rz + a * dr;
                accI += iz + a * di;
            }
            sre[tt][0][by][bx] = accR * 0.25;
            sim[tt][0][by][bx] = accI * 0.25;
        }
    }
    barrier();

    if (tid < 32) {
        int tt = tid / 16;
        int xx = tid % 16;
        if ((tt == 0) || has1) fftCol(tt, 0, xx, 1.0);
    }
    // per-tile neff 输出删除（v0.10.43）：HDR+ 原版语义 = 假设 N 帧完美平均
    //（σ²/N，neff≡4）；NS §3.3 的"按实际合并更新 σ"是改进项，等输出链真机
    // 验证通过后再用 SSBO 方案做（image2DArray 写入在该 Mali 上丢失）。
    barrier();

    if (tid < 32) {
        int tt = tid / 16;
        int yy = tid % 16;
        if ((tt == 0) || has1) {
            fftRow(tt, 0, yy, 1.0);
            int tx = (tt == 0) ? tx0 : tx1;
            for (int lx = 0; lx < 16; ++lx) {
                int px = tx * 8 + lx;
                int py = ty * 8 + yy;
                ivec2 m = ivec2(2 * px + ox, 2 * py + oy);
                if (m.x >= 0 && m.y >= 0 && m.x < uSize.x && m.y < uSize.y) {
                    float v = sre[tt][0][yy][lx] * 0.00390625;  // 1/256 = IDFT 归一
                    // image uniform 必须静态索引 —— uLayer 是 dispatch 级常量
                    if (uLayer == 0) {
                        imageStore(uOut0, m, vec4(v, v, v, 1.0));
                    } else if (uLayer == 1) {
                        imageStore(uOut1, m, vec4(v, v, v, 1.0));
                    } else if (uLayer == 2) {
                        imageStore(uOut2, m, vec4(v, v, v, 1.0));
                    } else {
                        imageStore(uOut3, m, vec4(v, v, v, 1.0));
                    }
                }
            }
        }
    }
})";

// kFsMfnrFftSum（降噪专项 A）：overlap-add 的收口 pass —— 4 个相位组层
//（组内 tile 互不重叠、每 texel 至多 1 tile 写入）按窗和恒 1 直接相加，
// 渲染进 tex_fused_（R16F，与 fuse 路径同域同格式，后段零改动）。
// ⚠️ 必须 #version 300 es：vs_plain_ 与全部现有 fragment 均为 300 es，Mali
// 拒绝不同 GLSL 版本混链（"Shader languages do not match"，v0.10.37 首包
// init_failed 黑屏事故）—— sum 只是采样+相加，300 es 完全够用。
const char* kFsMfnrFftSum = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uG0;  // 4 张相位组图（R32F，NEAREST）
uniform highp sampler2D uG1;
uniform highp sampler2D uG2;
uniform highp sampler2D uG3;
uniform ivec2 uSize;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    if (p.x >= uSize.x || p.y >= uSize.y) { oColor = vec4(0.0); return; }
    float v = texelFetch(uG0, p, 0).r + texelFetch(uG1, p, 0).r +
              texelFetch(uG2, p, 0).r + texelFetch(uG3, p, 0).r;
    oColor = vec4(v, v, v, 1.0);
})";

const char* kFsHrMask = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform float uNormalize;  // feather / threshold
uniform float uFeather;    // 2^(12/reconstruct_feather)
// 期 B bracket（2026-10-21）：短帧饱和修复标记（sat-fix 的 G 通道）。
// G=1 ⇒ 该像素高光已由短帧救回（真实值，不是饱和），HR 必须**跳过**它 ——
// 否则 inpaint 会把有信息的高光填成灰块（0.11.74）；不跳过则饱和区色比失真
// 成彩色斑块（0.11.85）。条件重建是同时消掉两者的唯一解。
uniform highp usampler2D uFixMark;
uniform int uMarkOn;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    // v0.12.5：标记是三态（0 = 短帧也饱和、值只是下界；1 = 短帧真值；2 = 长帧未饱和）。
    // 只有 0（真无信息）才需要 HR 重建；1/2 都有可信值 ⇒ 跳过。
    if (uMarkOn == 1 && texelFetch(uFixMark, p, 0).g >= 1u) {
        oColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec3 c = max(texelFetch(uTex, p, 0).rgb, vec3(0.0));
    float pixMax = sqrt(dot(c, c));
    float arg = -pixMax * uNormalize + uFeather;
    float w = clamp(1.0 / (1.0 + exp2(arg)), 0.0, 1.0);
    oColor = vec4(w, w, w, 1.0);
})";

// darktable 噪声（splitmix32 播种 + xoshiro128+ + Box-Muller 正态），与
// darktable develop/noise_generator.h 的 DT_NOISE_GAUSSIAN 同分布、与 PC 的
// GLSL 移植逐字同构。64 位乘法用 32 位手工展开（ES 无 64 位整数）。
const char* kFsHrInpaint = R"(#version 300 es
precision highp float;
precision highp int;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform highp sampler2D uMask;
uniform float uNoiseLevel;  // darktable noise_level 默认 0.2
uniform float uThreshold;   // 2^(white_source) × grey_source
const float kFltMin = 1.1754943508222875e-38;
const float kUint24Scale = 5.960464477539063e-8;
uvec2 xor64(uvec2 a, uvec2 b) { return uvec2(a.x ^ b.x, a.y ^ b.y); }
uvec2 shr64(uvec2 v, int shift) {
    if (shift == 0) return v;
    if (shift < 32) return uvec2((v.x >> shift) | (v.y << (32 - shift)), v.y >> shift);
    return uvec2(v.y >> (shift - 32), 0u);
}
uvec2 mul32Wide(uint a, uint b) {
    const uint mask = 0xffffu;
    uint a0 = a & mask, a1 = a >> 16;
    uint b0 = b & mask, b1 = b >> 16;
    uint p0 = a0 * b0, p1 = a0 * b1, p2 = a1 * b0, p3 = a1 * b1;
    uint mid = (p0 >> 16) + (p1 & mask) + (p2 & mask);
    uint lo = (p0 & mask) | (mid << 16);
    uint hi = p3 + (p1 >> 16) + (p2 >> 16) + (mid >> 16);
    return uvec2(lo, hi);
}
uvec2 mul64(uvec2 a, uvec2 b) {
    uvec2 p0 = mul32Wide(a.x, b.x);
    uvec2 p1 = mul32Wide(a.x, b.y);
    uvec2 p2 = mul32Wide(a.y, b.x);
    return uvec2(p0.x, p0.y + p1.x + p2.x);
}
uint splitmix32(uint seed) {
    uvec2 r = uvec2(seed, 0u);
    r = xor64(r, shr64(r, 33));
    r = mul64(r, uvec2(0x799705f5u, 0x62a9d9edu));
    r = xor64(r, shr64(r, 28));
    r = mul64(r, uvec2(0xc88c35b3u, 0xcb24d0a5u));
    return r.y;
}
uint rol32(uint x, int k) { return (x << k) | (x >> (32 - k)); }
float xoshiro128plus(inout uint s0, inout uint s1, inout uint s2, inout uint s3) {
    uint result = s0 + s3;
    uint t = s1 << 9;
    s2 ^= s0; s3 ^= s1; s1 ^= s2; s0 ^= s3;
    s2 ^= t; s3 = rol32(s3, 11);
    return float(result >> 8) * kUint24Scale;
}
vec3 gaussianNoise(vec3 mu, vec3 sigma, ivec2 coord) {
    uint x = uint(coord.x), y = uint(coord.y);
    uint s0 = splitmix32(x + 1u);
    uint s1 = splitmix32((x + 1u) * (y + 3u));
    uint s2 = splitmix32(1337u);
    uint s3 = splitmix32(666u);
    xoshiro128plus(s0, s1, s2, s3);
    xoshiro128plus(s0, s1, s2, s3);
    xoshiro128plus(s0, s1, s2, s3);
    xoshiro128plus(s0, s1, s2, s3);
    vec3 u1 = vec3(xoshiro128plus(s0, s1, s2, s3),
                   xoshiro128plus(s0, s1, s2, s3),
                   xoshiro128plus(s0, s1, s2, s3));
    vec3 u2 = vec3(xoshiro128plus(s0, s1, s2, s3),
                   xoshiro128plus(s0, s1, s2, s3),
                   xoshiro128plus(s0, s1, s2, s3));
    u1 = max(u1, vec3(kFltMin));
    vec3 root = sqrt(-2.0 * log(u1));
    vec3 n = vec3(root.x * cos(6.283185307179586 * u2.x),
                  root.y * sin(6.283185307179586 * u2.y),
                  root.z * cos(6.283185307179586 * u2.z));
    return n * sigma + mu;
}
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 src = texelFetch(uTex, p, 0);
    vec3 i = max(src.rgb, vec3(0.0));
    vec3 sigma = i * uNoiseLevel / uThreshold;
    vec3 noise = gaussianNoise(i, sigma, p);
    float w = texelFetch(uMask, p, 0).r;
    vec3 o = max(i * (1.0 - w) + w * noise, vec3(0.0));
    oColor = vec4(o, 1.0);
})";

// blur_2D_Bspline 的可分实现（V/H 两次调用，uDir 切换）
const char* kFsHrBlur = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform ivec2 uSize;
uniform int uMult;  // 步长 = 2^scale（à trous）
uniform int uDir;   // 0 = 垂直，1 = 水平
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 stepC = (uDir == 0) ? ivec2(0, uMult) : ivec2(uMult, 0);
    ivec2 lo = ivec2(0);
    ivec2 hi = uSize - 1;
    vec4 r = (1.0 / 16.0) * texelFetch(uTex, clamp(p - 2 * stepC, lo, hi), 0)
           + (4.0 / 16.0) * texelFetch(uTex, clamp(p - stepC, lo, hi), 0)
           + (6.0 / 16.0) * texelFetch(uTex, p, 0)
           + (4.0 / 16.0) * texelFetch(uTex, clamp(p + stepC, lo, hi), 0)
           + (1.0 / 16.0) * texelFetch(uTex, clamp(p + 2 * stepC, lo, hi), 0);
    oColor = max(r, vec4(0.0));  // clip_negatives = TRUE（darktable blur_2D_Bspline 调用点）
})";

// HF = detail − LF（含负数保留——HF 的负值是合法高频）
const char* kFsHrHF = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uDetail;
uniform highp sampler2D uLF;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    oColor = texelFetch(uDetail, p, 0) - texelFetch(uLF, p, 0);
})";

// init_reconstruct：recon = max(in·(1−mask), 0)
const char* kFsHrReconInit = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform highp sampler2D uMask;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 i = texelFetch(uTex, p, 0);
    float w = 1.0 - texelFetch(uMask, p, 0).r;
    oColor = max(i * w, vec4(0.0));
})";

// wavelets_reconstruct_RGB / wavelets_reconstruct_ratios（uVariant 0/1）
const char* kFsHrReconStep = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uHFRGB;
uniform highp sampler2D uLF;
uniform highp sampler2D uHF;
uniform highp sampler2D uMask;
uniform highp sampler2D uRecon;
uniform float uGamma;      // structure_vs_texture
uniform float uGammaComp;  // 1 − gamma
uniform float uBeta;       // grey_vs_color
uniform float uBetaComp;   // 1 − beta
uniform float uDelta;      // bloom_vs_details
uniform int uVariant;      // 0 = RGB 轮，1 = ratios 轮
uniform int uScaleIndex;
uniform int uScaleCount;
// darktable fmaxabsf：|a|>|b| 取 a（NaN 回退）
float fmaxabsf(float a, float b) {
    return (abs(a) > abs(b) && !isnan(a)) ? a : (isnan(b) ? 0.0 : b);
}
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float alpha = texelFetch(uMask, p, 0).r;
    vec4 hf = texelFetch(uHFRGB, p, 0);
    vec4 lf = texelFetch(uLF, p, 0);
    vec4 tt = texelFetch(uHF, p, 0);
    bool last = (uScaleIndex == uScaleCount - 1);
    vec3 details;
    vec3 residual;
    if (uVariant == 0) {
        // 单/双通道裁剪时把未被裁通道的最锐纹理转移到其他通道
        float greyTexture = fmaxabsf(fmaxabsf(tt.r, tt.g), tt.b);
        float greyDetails = (hf.r + hf.g + hf.b) / 3.0;
        float greyHF = uBetaComp * (uGammaComp * greyDetails + uGamma * greyTexture);
        details = (uGammaComp * hf.rgb + uGamma * tt.rgb) * uBeta + greyHF;
        float greyResidual = uBetaComp * (lf.r + lf.g + lf.b) / 3.0;
        residual = last ? (greyResidual + lf.rgb * uBeta) : vec3(0.0);
    } else {
        float greyTexture = fmaxabsf(fmaxabsf(tt.r, tt.g), tt.b);
        float greyDetails = (hf.r + hf.g + hf.b) / 3.0;
        float greyHF = uGammaComp * greyDetails + uGamma * greyTexture;
        details = 0.5 * ((uGammaComp * hf.rgb + uGamma * tt.rgb) + greyHF);
        residual = last ? lf.rgb : vec3(0.0);
    }
    vec3 recon = texelFetch(uRecon, p, 0).rgb;
    oColor = vec4(recon + alpha * (uDelta * details + residual), 1.0);
})";

const char* kFsHrNorms = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 c = texelFetch(uTex, p, 0).rgb;
    float n = max(sqrt(dot(c, c)), 1.52587890625e-5);  // NORM_MIN
    oColor = vec4(n, n, n, 1.0);
})";

const char* kFsHrRatios = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform highp sampler2D uNorms;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 c = texelFetch(uTex, p, 0).rgb;
    float n = max(texelFetch(uNorms, p, 0).r, 1.52587890625e-5);
    oColor = vec4(c / n, 1.0);
})";

const char* kFsHrRestore = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uRatios;
uniform highp sampler2D uNorms;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 r = texelFetch(uRatios, p, 0);
    float n = texelFetch(uNorms, p, 0).r;
    oColor = clamp(r, vec4(0.0), vec4(1.0)) * n;
})";

// 裁剪像素统计（darktable: clipped <= 9 跳过重建）。64×48 降采样块内 max，
// readback 后 CPU 判定；块内 max > 1/17 ⇔ 存在 argument < 4 的像素。
const char* kFsHrStat = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uMask;
uniform ivec2 uSize;
uniform ivec2 uOut;  // 64×48
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 block = ivec2((vec2(uSize) + vec2(uOut) - 1.0) / vec2(uOut));
    ivec2 base = p * block;
    float m = 0.0;
    for (int y = 0; y < block.y; y += 2) {
        for (int x = 0; x < block.x; x += 2) {
            ivec2 q = min(base + ivec2(x, y), uSize - 1);
            m = max(m, texelFetch(uMask, q, 0).r);
        }
    }
    oColor = vec4(m, m, m, 1.0);
})";

// ── RAW 域高光重建：darktable highlights 模块 / "inpaint opposed"（2026-10-03）──
// 一手出处：darktable src/iop/highlights.c（模式表、clips 语义）+ hlreconstruct/
// opposed.c（_process_opposed 四步）+ hlreconstruct/segbased.c:186 `_calc_refavg`
// （3×3 photosite 对立通道外推）。逐行对照移植，不做自研改写。
// 为什么补这一层（真机实测驱动）：exp12「曝光给足」使 RAW 饱和像素 403→2372（6×），
// 被裁剪通道的颜色信息丢失后只由 film 域小波 HR 兜底 ⇒ 高光边缘留彩色伪影。
// darktable 的默认路径正是在 RAW 域（去马赛克前）做这一步，本层补上这一环。
// 数据域与 darktable 逐项对应（不新造域）：
//   输入 = tex_mos_（线性 mosaic，已扣黑阶 + WB，R16F/RGBA16F 同域）
//   白点 = 1.0 ⇒ clips[c] = 1.0 × gain[c]（darktable: d->clip × icoeffs[c]）
//   correction[c] = 1.0（我们只有 as-shot WB，无 late correction 通道）
// 开关 = FilmParams[45] hr_raw；0 = A/B 通路（完全跳过，行为同 exp12）。

// Pass1（块级 mask）：3×3 photosite = 一个 superpixel（_raw_to_cmap 同款 row/3、
// col/3），块内任一 photosite 达阈值即置位该通道。输出 (maskR, maskG, maskB, 0)。
// ── 0.12.181：饱和掩码（参考帧 RAW 逐 photosite 判定）──────────────────────
// 出处：记忆「灰斑高光链路排查与回退」的 Next #1（用户 2026-10-29 指定按原版做）。
// 为什么需要：P0/P4/PC3D 目前各用一套代理判据 —— `c0 > 1` 的值判据、"0.987×满阱"、
// CCM 透传的 alpha。三者在"物理饱和但未重建"的 photosite 上全部失效，于是降噪把
// 高光核的实测色比抹掉（灰斑/绿晕/渗水的共同根因）。
// 本 pass 只回答一个问题：这个 photosite 是否**物理饱和**（信息已丢）？
//   ① v >= 满阱[c] − 3σ（σ 取逐通道噪声模型 A·x+B，与 P0 同口径）
//   ② 1px 膨胀（吸收 MFNR 对齐残差）
//   ③ 2×2 聚合（一个 RGB 像素的四个 photosite 视为整体 —— Bayer 的最小物理单位）
// 输出 R8 全分辨率：1 = 饱和核。**不复用任何 alpha 通道**（0.12.176 的教训：
// 把 tex_scene_.a 当掩码 ⇒ 全图命中 ⇒ 全图收紧色度门）。
const char* kFsSatMask = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp usampler2D uRaw;   // 参考帧原始 RAW（R16UI），未扣黑阶
uniform ivec2 uSize;
uniform int uCfaLut;
uniform vec4 uBlack;             // 四相位黑阶，索引同 P0：(y&1)*2+(x&1)
uniform float uWhite;
uniform vec3 uGain;              // WB 增益 = 逐通道满阱（WB 域）
uniform vec3 uNoiseA;
uniform vec3 uNoiseB;
// 0.12.185 诊断：>0.5 时无条件输出 1 —— 用来区分"绘制/读回链路坏了"与"判据坏了"
uniform float uDbgConst;
int chanAt(ivec2 p) {
    int k = ((p.y & 1) << 1) | (p.x & 1);
    return (uCfaLut >> (k << 1)) & 3;
}
float at3(vec3 v, int c) { return c == 0 ? v.x : (c == 1 ? v.y : v.z); }
// 单个 photosite 是否饱和：WB 域值 >= 满阱 − 3σ（σ 在 [0,1] 标定域求，同 P0 的 wAt）
float satAt(ivec2 q) {
    if (q.x < 0 || q.y < 0 || q.x >= uSize.x || q.y >= uSize.y) return 0.0;
    int c = chanAt(q);
    float bl = uBlack[(q.y & 1) * 2 + (q.x & 1)];
    float v = (float(texelFetch(uRaw, q, 0).r) - bl) / max(uWhite - bl, 1.0);
    float g = at3(uGain, c);
    float wb = v * g;   // WB 域：中性白 ≡ 1，满阱 ≡ g
    float sig = sqrt(max(at3(uNoiseA, c) * clamp(wb, 0.0, 1.0) + at3(uNoiseB, c), 0.0));
    return (wb >= g - 3.0 * sig) ? 1.0 : 0.0;
}
// ② 1px 膨胀（3×3 max）
float dilAt(ivec2 q) {
    float m = 0.0;
    for (int dy = -1; dy <= 1; ++dy)
        for (int dx = -1; dx <= 1; ++dx)
            m = max(m, satAt(q + ivec2(dx, dy)));
    return m;
}
void main() {
    if (uDbgConst > 0.5) { oColor = vec4(1.0); return; }
    ivec2 p = ivec2(gl_FragCoord.xy);
    // ③ 2×2 聚合：块内任一 photosite（膨胀后）饱和 ⇒ 整块标记
    ivec2 q = ivec2(p.x & ~1, p.y & ~1);
    float m = 0.0;
    for (int k = 0; k < 4; ++k)
        m = max(m, dilAt(q + ivec2(k & 1, k >> 1)));
    oColor = vec4(m, 0.0, 0.0, 1.0);
})";

const char* kFsHlMask = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uMos;   // 判定源：本 pass 不读（保留声明，驱动会优化掉）
uniform highp sampler2D uStat;  // 统计源（v0.10.167 起**真正被读取**）
uniform int uStatRaw;
// v0.10.167：1 = 源为单通道 R16F（值在 .r，tex_fused_ 布局），0 = RGBA16F 相位布局。
uniform int uMono;
uniform ivec2 uSize;
uniform int uCfaLut;
// v0.10.161：uClips = **裁剪判据**（0.987 × 中性白），不是满阱。本域（tex_mos_ =
// 扣黑阶 + WB 的线性 mosaic）中性白恒等于 1.0（论证见 passHighlightRaw 的
// kHlClipThr 注释）⇒ 三分量同为 0.987，与 PC `hlVal >= HLCLIP(0.987)` 逐字等价。
uniform vec3 uClips;
// 0.12.105：**满阱**（本域 = WB 增益 gain[c]）—— 裁剪判据必须按通道归一。
// 旧式 `v >= uClips[c]` 把 WB 后域的中性白（≡1.0）当成了裁剪点，而 R 增益 1.77
// ⇒ RAW 只有 56% 满阱的像素就跨线。与 kFsHlApply 的判据是同一处错误的两份拷贝。
uniform vec3 uSatClip;
int chanAt(ivec2 p) {
    int k = ((p.y & 1) << 1) | (p.x & 1);
    return (uCfaLut >> (k << 1)) & 3;
}
// mosaic 纹理的通道约定（与 P0 的 kFsRawDenoise/Fused 逐字一致）：值写在**它所属
// CFA 通道**的槽位里，即 R 位置 = (v,0,0)、G 位置 = (0,v,0)、B 位置 = (0,0,v)。
// 下游 RCD 三段读 .rgb 后按相位取通道，所以这里必须按相位读、按相位写 ——
// 一律读 .r / 写 .r 会让 G/B 位置全变 0（2026-10-03 真机 exp13 全画面红的根因）。
// v0.10.167 前置：HR 的输入换成 P0 前的 mosaic。MFNR 路径的 tex_fused_ 是 R16F
// **单通道**（值恒在 .r），与相位布局不同 ⇒ 用 uMono 分支适配（uMono=0 时逐位
// 等于改动前的 mosAt）。uMono 只描述"源布局"，不改任何判定口径。
//
// 统计采样（v0.10.167）：mask 的饱和判定属于**统计口径**，改走 uStat。接线时
// 调用方仍传 stat_src == src（同一 GLuint、同一读取式）⇒ 与读 uMos 逐位一致
// （零行为变化）；前置到 P0 之前后二者同指 P0 前的 mosaic。exp21 翻车的结构性
// 根因正是"统计口径与判定口径共用 src、没有独立旋钮"（见 kFsHlApply 顶部注释）。
float statAt(ivec2 p) {
    vec4 t = texelFetch(uStat, p, 0);
    if (uMono > 0) return t.r;
    int c = chanAt(p);
    return c == 0 ? t.r : (c == 1 ? t.g : t.b);
}
// v0.10.123 统计源：darktable 的 highlights 跑在**降噪之前**，而我们的 P0 会做
// 空间 bilateral ⇒ 饱和像素旁的 G 被平滑拉低、v−refavg 被系统性拉负 ⇒ chrominance
// 偏负 ⇒ 修复力度被吃掉（离线同 DNG 实测：未降噪 chroma_G −0.047、P0 后 −0.138、
// 真机 −0.250；亮部 R/G 1.063 → 1.131）。MFNR 路径改用 tex_fused_（融合后、P0 前，
// 与 tex_mos_ 同域的 R16F 线性 mosaic 单通道）。MFNR 的时间平均不改空间结构，
// 所以它不引入这个偏差；单帧路径没有该中间纹理，退回 tex_mos_。
float clipAt(int c) {
    // ⚠ 不能用 const：初始化器含 uniform，GLSL ES 3.00 的 const 要求编译期常量
    float thr = c == 0 ? uClips.x : (c == 1 ? uClips.y : uClips.z);
    float full = c == 0 ? uSatClip.x : (c == 1 ? uSatClip.y : uSatClip.z);
    return thr * max(full, 1.0);
}
void main() {
    ivec2 mb = ivec2(gl_FragCoord.xy);
    ivec2 p0 = mb * 3;
    vec3 mbuff = vec3(0.0);
    for (int y = 0; y < 3; ++y) {
        for (int x = 0; x < 3; ++x) {
            ivec2 p = p0 + ivec2(x, y);
            if (p.x < uSize.x && p.y < uSize.y) {
                int c = chanAt(p);
                float v = statAt(p);   // v0.10.167：统计口径（原 mosAt(uMos)）
                if (v >= clipAt(c)) mbuff[c] += 1.0;
            }
        }
    }
    oColor = vec4(mbuff.x > 0.0 ? 1.0 : 0.0,
                  mbuff.y > 0.0 ? 1.0 : 0.0,
                  mbuff.z > 0.0 ? 1.0 : 0.0, 0.0);
})";

// Pass2（块级膨胀）：`_mask_dilated` 逐项 —— 中心 / 3×3 块邻域 / ±1 块行的 x=±2,±3 /
// ±2 块行的整段 ±3 / ±3 块行的 x=±2。边界块（距边 <3 块）按 darktable 语义**不膨胀**
// （保持原值），越界读按 0（darktable 的 calloc 语义）。
const char* kFsHlDilate = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uMask;
uniform ivec2 uMSize;
float m(ivec2 p, int c) {
    if (p.x < 0 || p.y < 0 || p.x >= uMSize.x || p.y >= uMSize.y) return 0.0;
    vec4 v = texelFetch(uMask, p, 0);
    return c == 0 ? v.r : (c == 1 ? v.g : v.b);
}
float dil(ivec2 p, int c) {
    if (m(p, c) > 0.5) return 1.0;
    for (int dy = -1; dy <= 1; ++dy)
        for (int dx = -1; dx <= 1; ++dx)
            if (m(p + ivec2(dx, dy), c) > 0.5) return 1.0;
    for (int s = -1; s <= 1; s += 2) {
        for (int dx = -2; dx <= 2; ++dx)
            if (m(p + ivec2(dx, 3 * s), c) > 0.5) return 1.0;
        for (int dx = -3; dx <= 3; ++dx)
            if (m(p + ivec2(dx, 2 * s), c) > 0.5) return 1.0;
        if (m(p + ivec2(-3, s), c) > 0.5 || m(p + ivec2(-2, s), c) > 0.5 ||
            m(p + ivec2(2, s), c) > 0.5 || m(p + ivec2(3, s), c) > 0.5) return 1.0;
    }
    if (m(p + ivec2(-3, 0), c) > 0.5 || m(p + ivec2(-2, 0), c) > 0.5 ||
        m(p + ivec2(2, 0), c) > 0.5 || m(p + ivec2(3, 0), c) > 0.5) return 1.0;
    return 0.0;
}
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 src = texelFetch(uMask, p, 0);
    if (!(p.x >= 3 && p.y >= 3 && p.x < uMSize.x - 3 && p.y < uMSize.y - 3)) {
        oColor = src;
        return;
    }
    oColor = vec4(dil(p, 0), dil(p, 1), dil(p, 2), src.a);
})";

// Pass3（chrominance 全局统计）：取"紧邻裁剪区、未裁剪且 > 0.2×阈值"的 photosite，
// 累加 (实测 − 对立外推)；16×16 块内归约 → (w/16)×(h/16)，host 读回后按 darktable
// 的 100 样本闸求均值。uMode 0 = 累加偏差，1 = 只计数（跳过最贵的 refavg，第二次
// 归约几乎免费）—— 两个标量场各占一趟，避免引入项目里没有任何先例的 MRT 状态。
const char* kFsHlChroma = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uMos;   // 判定源：本 pass 不读（保留声明，驱动会优化掉）
uniform highp sampler2D uDil;
uniform highp sampler2D uStat;  // 统计源（v0.10.167 起**真正被读取**）
uniform int uStatRaw;
// v0.10.167：1 = 源为单通道 R16F（值在 .r），0 = RGBA16F 相位布局。
uniform int uMono;
uniform ivec2 uSize;
uniform ivec2 uMSize;
uniform int uCfaLut;
// uClips = 裁剪判据（0.987 × 中性白），非满阱（同 kFsHlMask 的说明）。故下面的
// 样本窗 `0.2×判据 < v < 判据` 就是 PC opposedaccum.glsl:93 的原式。
uniform vec3 uClips;
// ── 0.12.179：**撤销 0.12.170 的归一化域** —— 回到 WB 域（中性白 = 1,1,1）────
// 170 把 refavg/chroma 除以逐通道满阱 gain[c]（2.10/1.0/1.46）。但 opposed 的立论
// 前提是"三通道满阱相同 ⇒ 中性白三通道相等"——WB 域满足（中性白 = 1,1,1），除以
// gain 后的 raw/1023 域**不满足**（中性白 = 0.476/1.0/0.684）。于是 refavg 对 G
// 的系统性外推值只有 ≈0.57，`MAX(v=1.0, 0.57)` 永不抬 G ⇒ 天空/云（G 先饱和、
// R/B 还留有超白值）停在满阱比例 ⇒ 成片品红。真机 2026-10-29 16:52 窗外粉云
// （hlCnt3=[891,76823,2361]、hlRgG 0.95）即此指纹。
uniform int uMode;
// ── 0.12.127：统计口径必须排除 sat-fix 注入过的像素 ─────────────────────
// 真机实证（0.12.126，日志 2026-10-28 17:20）：hlChroma = [-2.191, -0.037, -0.328]，
// 而本域 refavg 的物理上限 = 满阱 gain[c]（1.75/1.0/1.65）⇒ 单个样本的
// `v − refavg` 不可能低于 −1.4，**平均值 −2.19 在物理上不存在**。
// 唯一的来源：refavg 的 3×3 邻域里含 sat-fix 注入过的 photosite —— 那些值是真
// 短帧测量（可达 3~4，超过满阱），把 refavg 抬到满阱之上 ⇒ 统计样本全被判成
// "比外推暗很多" ⇒ uChroma 大负 ⇒ `max(v, refavg + chroma)` 对 R 永不触发、
// 对 G 仍按 chroma_G 抬 ⇒ 高光点只剩 G 被抬高 = **绿斑/绿边**。
// 修复：refavg 只采纳"长帧本身未饱和"（三态标记 = 2）的邻域样本 —— 这与
// opposed 的语义本来就一致（用未裁剪邻域外推），只是此前没有数据能区分
// "未裁剪"和"裁剪后被注入"，两者在值域上都 < 满阱。
uniform highp usampler2D uFixMark;
uniform int uMarkOn;
int chanAt(ivec2 p) {
    int k = ((p.y & 1) << 1) | (p.x & 1);
    return (uCfaLut >> (k << 1)) & 3;
}
float at3(vec3 v, int c) { return c == 0 ? v.x : (c == 1 ? v.y : v.z); }
// 统计采样（v0.10.167）：chrominance 是**统计口径**，与 mask 同走 uStat。
// 接线时 stat_src == src ⇒ 与改动前的 mosAt(uMos) 逐位一致（零行为变化）。
float statAt(ivec2 p) {
    vec4 t = texelFetch(uStat, p, 0);
    if (uMono > 0) return t.r;
    int c = chanAt(p);
    return c == 0 ? t.r : (c == 1 ? t.g : t.b);
}
// _calc_refavg（segbased.c:186）：3×3 photosite 邻域按通道求均值 → 各开立方 →
// 目标通道取**另两个通道**立方的均值（opposing）→ linear 时回立方。边界截断与
// darktable 同式（dymax = min(h-1, row+2)，非 +2 含端）。
// ok 输出 = 三个通道都取到了"干净"样本。任一通道取不到 ⇒ 这个样本无法外推，
// 必须整体跳过（否则 opp 退化成 0，d = v 会把 chroma 抬成正的巨大值）。
float refavg(ivec2 p, out bool ok) {
    int color = chanAt(p);
    vec3 mean = vec3(0.0), cnt = vec3(0.0);
    int dymin = max(0, p.y - 1), dxmin = max(0, p.x - 1);
    int dymax = min(uSize.y - 1, p.y + 2), dxmax = min(uSize.x - 1, p.x + 2);
    for (int dy = dymin; dy < dymax; ++dy) {
        for (int dx = dxmin; dx < dxmax; ++dx) {
            ivec2 q = ivec2(dx, dy);
            // 0.12.127：标记 2 = 长帧本身未饱和（值是真测量）。0/1 都不采纳。
            if (uMarkOn != 0 && texelFetch(uFixMark, q, 0).g != 2u) continue;
            int c = chanAt(q);
            float v = max(0.0, statAt(q));
            if (c == 0) { mean.x += v; cnt.x += 1.0; }
            else if (c == 1) { mean.y += v; cnt.y += 1.0; }
            else { mean.z += v; cnt.z += 1.0; }
        }
    }
    ok = (cnt.x > 0.0) && (cnt.y > 0.0) && (cnt.z > 0.0);
    vec3 mr = vec3(0.0);
    if (cnt.x > 0.0) mr.x = pow(mean.x / cnt.x, 1.0 / 3.0);
    if (cnt.y > 0.0) mr.y = pow(mean.y / cnt.y, 1.0 / 3.0);
    if (cnt.z > 0.0) mr.z = pow(mean.z / cnt.z, 1.0 / 3.0);
    vec3 opp = vec3(0.5 * (mr.y + mr.z), 0.5 * (mr.x + mr.z), 0.5 * (mr.x + mr.y));
    float r = at3(opp, color);
    return r * r * r;
}
void main() {
    ivec2 base = ivec2(gl_FragCoord.xy) * 16;
    vec3 acc = vec3(0.0);
    for (int y = 0; y < 16; ++y) {
        for (int x = 0; x < 16; ++x) {
            ivec2 p = base + ivec2(x, y);
            if (p.x >= uSize.x || p.y >= uSize.y) continue;
            int c = chanAt(p);
            float cl = at3(uClips, c);
            float v = statAt(p);
            if (v >= cl || v <= 0.2 * cl) continue;
            ivec2 mb = p / 3;
            if (mb.x >= uMSize.x || mb.y >= uMSize.y) continue;
            if (at3(texelFetch(uDil, mb, 0).rgb, c) < 0.5) continue;
            // 两趟（偏差 / 计数）必须用**同一个样本集合**，否则 sum/cnt 不是均值。
            // 因此计数趟也照付 refavg 的成本 —— HL 只在拍照时跑一次，不值得为它
            // 引入"两趟口径不一致"这种会静默撒谎的优化。
            bool ok = true;
            float ra = refavg(p, ok);
            if (!ok) continue;
            float d = (uMode == 0) ? (v - ra) : 1.0;
            if (c == 0) acc.x += d;
            else if (c == 1) acc.y += d;
            else acc.z += d;
        }
    }
    oColor = vec4(acc, 0.0);
})";

// Pass3.5（参考色度场，1/uBlock 网格）：只用**未被截断**的 photosite 统计局部色度
// 比例 R/G、B/G，供 Pass4 在 G 被截断时反推 G 的应有值。
// 为什么必须放在 RAW 域：CCM 是含负系数的混合矩阵，会把"G 截断"抹掉（实测 CCM 后
// G 的 ≥1.0 触发率从 93.7% 崩到 3.9%），判据一旦挂到 CCM 之后就失效。
// uBlock=32（2026-10-04 修正）：8×8 块（有效半径 8 RAW 像素）在**大面积饱和区必然
// 取不到未截断参考** —— 真机 14 张片实测 noRef 49%–87%，G 几乎不被重建。离线基准
// （σ8–σ96 高斯参考，有效半径 24–192 RAW 像素）证明参考范围必须跨块才够；32×32 块
// 有效半径 16 RAW 像素，是与之最接近的可实现口径，成本与 8×8 持平（每输出像素
// 1024 采样 × 输出 w·h/1024 ≈ 1 个全分辨率 pass，与原 64 采样 × w·h/64 相同）。
// 输出：(mR·w, mG·w, mB·w, w) —— **加权分子**而非比值（v0.10.148 起；此前是块内均值
// (mR,mG,mB,w) 由 apply 侧折成"块比值·w"，会放大小块比值的噪声 ⇒ 全局比偏红）。
// w = min(cR,cG,cB)/(uBlock²/4) 是"最稀缺通道的未截断样本占该通道期望样本数的比例"，
// 供 Pass4 判可靠性；存分子是为了让 Pass4 的手写双线性能对 (分子, 分母) 一起插值后
// 相除 —— 直接插值比值会在 w=0 的无参考块处造出假值。
const char* kFsHlRefChroma = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uMos;
uniform ivec2 uSize;
uniform int uCfaLut;
// uClips = 裁剪判据（0.987 × 中性白），非满阱（同 kFsHlMask 的说明）。
uniform vec3 uClips;
uniform int uBlock;
// v0.10.167：1 = 源为单通道 R16F（值在 .r），0 = RGBA16F 相位布局。
// 本 pass 仍读 uMos（判定口径，v0.10.167 未改统计源）—— 前置后 uMos 本身就是
// P0 前的 mosaic，故参考色度场自然用上未降噪数据（这正是本项要修的那 9.2%）。
uniform int uMono;
int chanAt(ivec2 p) {
    int k = ((p.y & 1) << 1) | (p.x & 1);
    return (uCfaLut >> (k << 1)) & 3;
}
float mosAt(ivec2 p) {
    vec4 t = texelFetch(uMos, p, 0);
    if (uMono > 0) return t.r;
    int c = chanAt(p);
    return c == 0 ? t.r : (c == 1 ? t.g : t.b);
}
void main() {
    ivec2 base = ivec2(gl_FragCoord.xy) * uBlock;
    float sR = 0.0, sG = 0.0, sB = 0.0;
    float cR = 0.0, cG = 0.0, cB = 0.0;
    for (int y = 0; y < 32; ++y) {
        if (y >= uBlock) break;
        for (int x = 0; x < 32; ++x) {
            if (x >= uBlock) break;
            ivec2 p = base + ivec2(x, y);
            if (p.x >= uSize.x || p.y >= uSize.y) continue;
            int c = chanAt(p);
            float cl = c == 0 ? uClips.x : (c == 1 ? uClips.y : uClips.z);
            float v = mosAt(p);
            if (v >= cl || v <= 0.0) continue;
            // v0.10.149：样本必须落在**同一亮度区间**。此前只筛"该 2×2 单元的 G 未截断"，
            // 但三通道的 clip 差异极大 —— G 的 clip = 满阱（gains[1] 恒 1.0），R/B 的 clip
            // 是 WB 增益（本机 2.09/1.44），后者几乎不截断。于是被采纳的样本天然是块内
            // **较暗**的像素（只有暗像素的 G 才不饱和），而 R/B 不受此限 ⇒ mR/mG、mB/mG
            // 被系统性抬高。真机 162411 按 w 分档是铁证：w>0.7 的块 mR/mG = 0.970（干净），
            // w 0.3–0.5 的块 = 1.797 —— 恰好就是"部分饱和、正要被重建"的那批块，参考比
            // 因此偏高约 9%，gest = 0.5·(Rv4/rg + Bv4/bg) 偏小，修复只做到应有幅度的一半
            // （离线同口径：未修像素 12.5%→1.7%，落点 R/G 1.003→0.954、B/G 1.114→1.066，
            // 目标 0.925/1.044）。改为要求该 2×2 单元的 R、G1、G2、B 四个 photosite
            // **全部**未截断；离线实测可用样本率仅从 95.40% 降到 95.31%，无覆盖损失。
            ivec2 q0 = ivec2(p.x & ~1, p.y & ~1);
            bool unit_ok = true;
            for (int k = 0; k < 4; ++k) {
                ivec2 q = q0 + ivec2(k & 1, k >> 1);
                int cq = chanAt(q);
                float qcl = cq == 0 ? uClips.x : (cq == 1 ? uClips.y : uClips.z);
                float qv = (q == p) ? v : mosAt(q);
                if (qv >= qcl) { unit_ok = false; break; }
            }
            if (!unit_ok) continue;
            if (c == 0) { sR += v; cR += 1.0; }
            else if (c == 1) { sG += v; cG += 1.0; }
            else { sB += v; cB += 1.0; }
        }
    }
    // 归一化占比（不是绝对样本数）：uBlock² 个 photosite 里每通道期望 uBlock²/4 个
    float w = min(min(cR, cG), cB) / max(1.0, float(uBlock) * float(uBlock) * 0.25);
    // v0.10.143 **池化口径**：输出未截断样本的**和** (ΣR, ΣG, ΣB) 与占比 w，不再输出
    // "块内均值比"。为什么改：比值先在小块内相除再加权，会让 allSat 像素（恰在参考
    // 样本最少的区域）吃到小块比值的噪声 —— 离线实测同一张台灯片，块比值 w 加权给
    // rg 1.168、池化给 1.042（12% 系统偏差）⇒ CCM 后落点 1.377 vs 1.056。
    // ⚠ 必须输出**均值**而不是和：Bayer 里 G 有两个 photosite/2×2（R、B 各一个），
    // ΣR/ΣG 会因样本数不等而差约 2 倍（离线实测 rg 0.52 而非 1.04，落点过冲到 0.37）。
    // 池化比 = 均值之比：mR/mG = (ΣR/cR)/(ΣG/cG)，等价于全体未截断样本的池化比值。
    float mR = cR > 0.0 ? sR / cR : 0.0;
    float mG = cG > 0.0 ? sG / cG : 0.0;
    float mB = cB > 0.0 ? sB / cB : 0.0;
    // v0.10.148 **池化口径**（真机窗户场景实锤）：输出加权分子 (mR·w, mG·w, mB·w, w)，
    // apply 侧四分量一起插值后再相除 ⇒ rg = Σ(mR·w)/Σ(mG·w)，即像素等权池化。
    // 为什么改：此前存块内均值、apply 侧先折算成"块比值·w"再插值，等于对**小块比值**
    // 做 w 加权平均 —— 小样本块的比值噪声被放大，全局比例系统性偏红。同一片饱和天空
    // 离线实测：块比值加权给 rg 1.0415、池化给 0.9466；修复落点 R/G 0.968（比该区域
    // 未饱和邻域的自然色度 0.9246 偏红 4.7%）vs 池化 0.935（差 1.1%）。
    // 台灯场景高光是三通道全饱和、走 allSat 分支（本来就用池化）所以没暴露；窗户
    // 大面积"仅 G 饱和"走普通路径才暴露 —— 两条路径必须同口径。
    // ⚠ 四分量语义变了：w 现在在 alpha，.y 是分母（mG·w），读它的三处必须同步改。
    oColor = vec4(mR * w, mG * w, mB * w, w);
})";

// Pass3.55（中尺度参考场）：对 Pass3.5 的加权三元组做 4×4 降采样 ——
// 每格覆盖 4×4 个 32 块 = 128×128 CFA 像素（≈256px 全分辨率范围）。
// 必须**累加**（直接求和，不在这一级相除）：参考场四分量 = (mR, mG, mB, w)，
// 只有把分子分母分别累加、留给 apply 相除，w=0 的无参考格才不会污染比值。
// 为什么需要这一级：大面积饱和区（32 块内 G 全截断）的局部参考为空，此前只能退到
// 全图比例；而全图比例在多光源场景被平均成偏红（真机 hlRgG 1.108），把饱和天空往
// 红里拉。中尺度保留约 128 CFA 像素的局部色温。
// 成本：32×24 输出 × 16 采样 ≈ 1.2 万次 texelFetch，可忽略。
const char* kFsHlRefDown = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uRef1;
uniform ivec2 uRefSize;
void main() {
    ivec2 base = ivec2(gl_FragCoord.xy) * 4;
    vec4 acc = vec4(0.0);
    for (int y = 0; y < 4; ++y) {
        for (int x = 0; x < 4; ++x) {
            ivec2 q = base + ivec2(x, y);
            if (q.x >= uRefSize.x || q.y >= uRefSize.y) continue;
            // v0.10.148：ref1 已是加权分子 (mR·w, mG·w, mB·w, w)，直接累加即可 ——
            // 与第 1 级同口径（池化），apply 侧相除后仍是池化比。
            acc += texelFetch(uRef1, q, 0);
        }
    }
    // 再同除 4×4=16（阈值 0.05 表示"该中尺度格平均 5% 样本可用"）。
    // 若将来改降采样倍数，此处必须同步。
    oColor = acc * (1.0 / 16.0);
})";

// Pass4（应用）：裁剪 photosite → max(实测, 对立外推 + 全局色度)；未裁剪保持原值。
// 输出 scene-referred 线性 mosaic（不钳到 1，darktable 同款 —— OPPOSED 保持场景
// 参考，白点变化由后续链路吸收）。
const char* kFsHlApply = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
// ── v0.10.167：本 pass 的**判定口径一个字节都没改** ──────────────────────────
// 它仍读 uMos（判定源）。v0.10.167 的改动只有两处、都不触碰判据：
//   ① uMono：源的**布局**适配（1 = R16F 单通道，0 = RGBA16F 相位布局）；
//   ② 输出布局：由"按 CFA 相位写回"改为**单通道 .r**（oColor.r = outv）。
//      为什么必须改：前置后本 pass 的输出 tex_hl_ 是 **P0 的输入**，而 P0 的
//      kFsRawDenoiseFused::fused0() 读 .r（与 tex_fused_ 同约定）。RCD 不再读
//      tex_hl_（它读 P0 的输出 tex_mos_），故旧的相位布局在本链路没有消费者。
//      输出布局变了 ≠ 判定口径变了：outv 的每一行计算（判据 0.987、refavg、
//      三级参考、软门控、allSat 重分配、uGCap）逐字未动。
uniform highp sampler2D uMos;
uniform ivec2 uSize;
uniform int uCfaLut;
// v0.10.167：1 = 源为单通道 R16F（值在 .r，tex_fused_ 布局），0 = RGBA16F 相位布局。
uniform int uMono;
// uSatClip = **满阱**（本域 = WB 增益 gain[c]，逐通道不同）：只用于下面 allSat
// 的"三通道都真的顶到传感器上限"判定与 luma 锚点 —— 语义是传感器饱和，不是裁剪判据。
uniform vec3 uSatClip;
// uHlClip = **裁剪判据** = 0.987 × 中性白（本域中性白 ≡ 1.0 ⇒ 标量 0.987）。
// PC 把它作为标量（OpposedGL/opposedaccum 的 u_clip）正是同一域语义的体现。
uniform float uHlClip;
uniform vec3 uChroma;
uniform highp sampler2D uStat;
uniform int uStatRaw;
uniform highp sampler2D uRef;
uniform vec2 uRefSize;
uniform highp sampler2D uRef2;
uniform vec2 uRef2Size;
uniform vec2 uRefGlobal;
// 旧口径 uRefGlobalW（v0.10.148 起普通路径第 3 级已改用 uRefGlobal（池化），本 uniform
// 不再参与着色（驱动会优化掉，CPU 侧下发是安全 no-op）：声明已于第 1b 期删除，本注仅记录旧口径值）
uniform float uGRecon;
uniform float uGCap;
// v0.10.179：clip 过渡带 R/B 满阱比例反推（FilmParams[57] hl_rb_recon）。
// 1 = 启用 allSat 三判据够不着的"本通道（R/B）满阱、其余通道未满"photosite 的
//     参考比反推替换（见 main() 内新分支注释）；0 = 该支不进（默认，旧行为逐位
//     不变 —— 新分支不触发时不执行任何新计算路径）。
uniform float uRbRecon;
// v0.12.8：采集侧 sat-fix 的三态信息标记（R16UI 的 G 通道）：
//   0 = 长帧与短帧**都**饱和 ⇒ 真值未知
//   1 = 长帧饱和、短帧救回 ⇒ 有真值
//   2 = 长帧本身未饱和      ⇒ 本来就是好值
// 本 pass 用它做**重建判据**（替换 `v >= uHlClip`），见 main() 开头。
uniform highp usampler2D uFixMark;
uniform int uMarkOn;
int chanAt(ivec2 p) {
    int k = ((p.y & 1) << 1) | (p.x & 1);
    return (uCfaLut >> (k << 1)) & 3;
}
float at3(vec3 v, int c) { return c == 0 ? v.x : (c == 1 ? v.y : v.z); }
// mosaic 按 CFA 相位取通道（理由见 kFsHlMask 的 mosAt 注释）。v0.10.167 加 uMono
// 分支：uMono=0 时逐位等于改动前的实现。
float mosAt(ivec2 p) {
    vec4 t = texelFetch(uMos, p, 0);
    if (uMono > 0) return t.r;
    int c = chanAt(p);
    return c == 0 ? t.r : (c == 1 ? t.g : t.b);
}
float refavg(ivec2 p) {
    int color = chanAt(p);
    vec3 mean = vec3(0.0), cnt = vec3(0.0);
    int dymin = max(0, p.y - 1), dxmin = max(0, p.x - 1);
    int dymax = min(uSize.y - 1, p.y + 2), dxmax = min(uSize.x - 1, p.x + 2);
    for (int dy = dymin; dy < dymax; ++dy) {
        for (int dx = dxmin; dx < dxmax; ++dx) {
            ivec2 q = ivec2(dx, dy);
            int c = chanAt(q);
            // 0.12.179：回 WB 域（中性白 = 1,1,1 ⇒ opposed 前提成立；见统计 shader 顶部）。
            float v = max(0.0, mosAt(q));
            if (c == 0) { mean.x += v; cnt.x += 1.0; }
            else if (c == 1) { mean.y += v; cnt.y += 1.0; }
            else { mean.z += v; cnt.z += 1.0; }
        }
    }
    vec3 mr = vec3(0.0);
    if (cnt.x > 0.0) mr.x = pow(mean.x / cnt.x, 1.0 / 3.0);
    if (cnt.y > 0.0) mr.y = pow(mean.y / cnt.y, 1.0 / 3.0);
    if (cnt.z > 0.0) mr.z = pow(mean.z / cnt.z, 1.0 / 3.0);
    vec3 opp = vec3(0.5 * (mr.y + mr.z), 0.5 * (mr.x + mr.z), 0.5 * (mr.x + mr.y));
    float r = at3(opp, color);
    return r * r * r;
}
// 4 邻域内找指定通道的样本（R/B 与 G 相位不同，必须跨相位取）
float neighborOfChan(ivec2 p, int want) {
    for (int k = 0; k < 4; ++k) {
        ivec2 d = k == 0 ? ivec2(1, 0)
                : (k == 1 ? ivec2(-1, 0) : (k == 2 ? ivec2(0, 1) : ivec2(0, -1)));
        ivec2 q = p + d;
        if (q.x < 0 || q.y < 0 || q.x >= uSize.x || q.y >= uSize.y) continue;
        if (chanAt(q) == want) return mosAt(q);
    }
    return -1.0;
}
// 8 邻域取通道（含对角）。为什么需要它：Bayer 的**第三个通道在对角上** ——
// R 位置的 B、B 位置的 R 都不在 4 邻域里 ⇒ 用 4 邻域做"三通道全饱和"判定时
// R/B 相位会拿到 -1 ⇒ 永远判不出 allSat ⇒ 只有 G 相位被改写 ⇒ 三通道不同步
// （离线实测：只改 G 时 luma 不守恒、CCM 后 R/G 仍是 2.28）。
// 现有 G 重建路径继续用 4 邻域的 neighborOfChan，逐位不变。
float neighborOfChan8(ivec2 p, int want) {
    for (int k = 0; k < 8; ++k) {
        ivec2 d = k == 0 ? ivec2(1, 0)
                : (k == 1 ? ivec2(-1, 0)
                : (k == 2 ? ivec2(0, 1)
                : (k == 3 ? ivec2(0, -1)
                : (k == 4 ? ivec2(1, 1)
                : (k == 5 ? ivec2(-1, 1)
                : (k == 6 ? ivec2(1, -1) : ivec2(-1, -1)))))));
        ivec2 q = p + d;
        if (q.x < 0 || q.y < 0 || q.x >= uSize.x || q.y >= uSize.y) continue;
        if (chanAt(q) == want) return mosAt(q);
    }
    return -1.0;
}
// ── 0.12.141：**同 2×2 块**的三通道取值 ─────────────────────────────────────
// neighborOfChan8 从 8 邻域按固定顺序找第一个匹配通道 —— R 位 (x,y) 的 B 会先命中
// 右下 (x+1,y+1)，而那个像素属于**下一个** 2×2 块。于是"本像素是否全饱和"的判定
// 被邻块的核心污染：
//   光晕像素自身 (R1.32/G0.81/B1.02) 未饱和，但右下邻居是饱和核心 (1.67/1.0/1.51)
//   ⇒ nsat=3 ⇒ 被 0.12.136 的 allSat 改写成中性白 ⇒ 成片 R/G 1.63→1.23（偏绿）。
// 2×2 块才是"一个 RGB 像素"的物理单位（Bayer 的 R/G/G/B 四个 photosite），
// 所以这里严格按块内 4 个位置取值，绝不跨块。
void blockRGB(ivec2 p, out float R, out float G, out float B) {
    ivec2 q = ivec2(p.x & ~1, p.y & ~1);
    R = -1.0;
    G = -1.0;
    float g2 = -1.0;
    B = -1.0;
    for (int k = 0; k < 4; ++k) {
        ivec2 t = q + ivec2(k & 1, k >> 1);
        float val = mosAt(t);
        int ch = chanAt(t);
        if (ch == 0) R = val;
        else if (ch == 1) { if (G < 0.0) G = val; else g2 = val; }
        else B = val;
    }
    if (g2 >= 0.0) G = 0.5 * (G + g2);
}
// v0.12.9：读**标记纹理**里指定相位的通道标记（与 neighborOfChan8 同构，但读 uFixMark.g）。
// 为什么需要它：重建判据必须按**整像素**决定，不能按单通道 —— 见 main() 里那段论证。
// 取不到（边界外）时返回 2u = "有真值"，即保守地不重建。
uint markOfChan(ivec2 p, int want) {
    for (int k = 0; k < 8; ++k) {
        ivec2 d = k == 0 ? ivec2(1, 0)
                : (k == 1 ? ivec2(-1, 0)
                : (k == 2 ? ivec2(0, 1)
                : (k == 3 ? ivec2(0, -1)
                : (k == 4 ? ivec2(1, 1)
                : (k == 5 ? ivec2(-1, 1)
                : (k == 6 ? ivec2(1, -1) : ivec2(-1, -1)))))));
        ivec2 q = p + d;
        if (q.x < 0 || q.y < 0 || q.x >= uSize.x || q.y >= uSize.y) continue;
        if (chanAt(q) == want) return texelFetch(uFixMark, q, 0).g;
    }
    return 2u;
}
// 手写双线性（Mali 上 RGBA16F 的硬件 LINEAR 不可靠，项目既有结论）：参考场存的是
// 加权分子 (mR·w, mG·w, mB·w, w)，必须对分子与分母一起插值、之后再相除 —— 直接插值
// 比值会在 w=0 的无参考格处造出假值。
vec4 refBilerp(highp sampler2D tex, vec2 size, vec2 uv) {
    vec2 t = uv * size - 0.5;
    vec2 i0 = floor(t);
    vec2 fr = t - i0;
    ivec2 sz = ivec2(size);
    ivec2 ib = ivec2(i0);
    vec4 a = texelFetch(tex, clamp(ib, ivec2(0), sz - 1), 0);
    vec4 b = texelFetch(tex, clamp(ib + ivec2(1, 0), ivec2(0), sz - 1), 0);
    vec4 c = texelFetch(tex, clamp(ib + ivec2(0, 1), ivec2(0), sz - 1), 0);
    vec4 d = texelFetch(tex, clamp(ib + ivec2(1, 1), ivec2(0), sz - 1), 0);
    return mix(mix(a, b, fr.x), mix(c, d, fr.x), fr.y);
}
// v0.10.148：参考场存的已是加权分子 (mR·w, mG·w, mB·w, w)，逐 texel 无需再折算 ——
// 直接插值四分量、之后再相除，等价于像素等权池化（口径论证见 kFsHlRefChroma）。
vec4 refToWeighted(vec4 t) { return t; }
vec4 refBilerpW(highp sampler2D tex, vec2 size, vec2 uv) {
    vec2 t = uv * size - 0.5;
    vec2 i0 = floor(t);
    vec2 fr = t - i0;
    ivec2 sz = ivec2(size);
    ivec2 ib = ivec2(i0);
    vec4 a = refToWeighted(texelFetch(tex, clamp(ib, ivec2(0), sz - 1), 0));
    vec4 b = refToWeighted(texelFetch(tex, clamp(ib + ivec2(1, 0), ivec2(0), sz - 1), 0));
    vec4 c = refToWeighted(texelFetch(tex, clamp(ib + ivec2(0, 1), ivec2(0), sz - 1), 0));
    vec4 d = refToWeighted(texelFetch(tex, clamp(ib + ivec2(1, 1), ivec2(0), sz - 1), 0));
    return mix(mix(a, b, fr.x), mix(c, d, fr.x), fr.y);
}
// 三级参考比例（v0.10.139；口径 v0.10.148）：① 32 块局部 → ② 中尺度（每格 128×128
// CFA 像素）→ ③ 全图。三级**统一池化口径**：r1/r2 已是 (mR·w, mG·w, mB·w, w)，
// 故 rg = x/y、bg = z/y；第 3 级与 allSat 分支同用 uRefGlobal（池化值），不再用
// 块比值加权的 uRefGlobalW —— 后者在小样本块上系统偏红（同片实测 1.0415 vs 池化
// 0.9466），正是窗户场景"修复不足、还是红"的来源。
// 返回 (rg, bg, 第 1 级占比)，占比供软门控当"邻域饱和率"（与选了哪级无关）。
vec3 refRatiosW(vec2 uv) {
    vec4 r1 = refBilerpW(uRef, uRefSize, uv);
    if (r1.w > 0.30) {
        // 0.30 = 最稀缺通道至少 30% 可用（32×32 块 → ≥77 个样本）
        float g = max(r1.y, 1e-6);
        return vec3(max(r1.x / g, 1e-3), max(r1.z / g, 1e-3), r1.w);
    }
    vec4 r2 = refBilerp(uRef2, uRef2Size, uv);
    if (r2.w > 0.05) {
        // 0.05：中尺度每格 4×4 个 32 块，门槛 = 平均 5% 样本可用
        float g = max(r2.y, 1e-6);
        return vec3(max(r2.x / g, 1e-3), max(r2.z / g, 1e-3), r1.w);
    }
    return vec3(max(uRefGlobal.x, 1e-3), max(uRefGlobal.y, 1e-3), r1.w);
}
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int c = chanAt(p);
    float v = mosAt(p);
    float outv = v;
    // ── v0.12.8：重建判据从"值是否超过 clip"换成"信息是否存在" ────────────────
    // 这是**判据的域错位**（bug），不是算法能力边界：
    //   darktable 原版 `v >= clips[c]` 在**单帧**域里等价于"该通道信息已丢失"——
    //   因为那时 v 就是被 clip 在满阱的值。
    //   bracket 下 sat-fix 已经把真值写回来了，那些像素的 v 可能是 2.5，**同样
    //   >= clips，但信息是完整的**。于是 opposed 对它们做 refavg 二次外推，把真值
    //   改掉；refavg 是 3×3 的**立方均值**（cbrt→avg→cube，凸函数 ⇒ 放大差异），
    //   在"真值 / 未修复原值"的交界处被放大，紧跟其后的 P0 bilateral 再把它糊成
    //   几十像素的带。
    // 真机 182707（绿袋镜面高光，短帧 sat 仅 0.02% ⇒ 几乎处处有真值）：实测过渡带
    //   14~28 px，而高光本体只有 53×65 px —— 这个尺度远大于 refavg 的 3px，
    //   正是"HL 造跳变 + P0 糊开"的特征。
    // 修法：标记 >= 1（短帧救回 = 1 / 长帧未饱和 = 2）⇒ 信息可信 ⇒ **原样输出**，
    //   连 max 都不做（max 也会改写"真值低于外推"的像素）。
    //   只有标记 0（两帧都饱和、真值未知）才进下面 of opposed 重建。
    // 与面积无关、与场景无关 ⇒ 不会再"修一个坏一个"。
    // ── v0.12.10：**删除"有真值就跳过 HL"**（v0.12.8/0.12.9），它由真机否决 ───────
    // 我把它当成"对真值做二次外推"，判断错了：`max(v, refavg + chroma)` 在这类场景里
    // 起的是**色度平衡**作用 —— 本机满阱 R/G/B = 2.15 / 1.0 / 1.62，R/B 比 G 高，
    // 所以 refavg 对 G 位算出的值天然高于 v，作用就是把偏低的 G 抬起来、把满阱比例
    // 拉回中性。跳过它 ⇒ G 不被抬 ⇒ 三通道停在满阱比例 ⇒ **品红**。
    // 真机 184216（绿袋镜面反光）实测：R≈247、B≈236、G≈207 —— R/B 顶到白点而 G
    // 低 40，正是"G 没被抬"的指纹。
    // 结论：refavg 对 sat-fix 修复过的像素**不是多余的**，必须继续跑。
    // 于是重建判据回到 darktable 原版（`v >= uHlClip`），下面这行不再被拦截。
    // ── v0.12.7：这里曾有一段"无真值区按三级参考比重新分配色度"（v0.12.5/0.12.6），
    // 已由真机否决并删除。记录原因，避免以后再犯：
    //   真机 181558（柜内 LED 灯珠，ISO 928，短帧 sat 0.17%）：灯珠约 3 万像素、短帧
    //   饱和区约 2 万，两者几乎重合 ⇒ 出大片灰斑。两个错误：
    //   ① 参考比是**大尺度**量（32 块 → 128×128 CFA → 全图），而灯珠周围是暗柜，
    //      那些样本的色度与灯珠内部毫无关系；"参考比"只对**大面积、内部同色**的
    //      饱和区（天空）成立；
    //   ② 亮度锚 lv 取自 8 邻域，而邻域里混着"未重分配"的像素 ⇒ sc 在空间上不稳定。
    //   而"大面积 vs 孤立小光源"这个区分在本层判不出来（灯珠 200px 在 3×3@1/2 的
    //   窗口里同样 satFrac≈1）—— 任何用局部面积近似它的写法都会重演本 bug。
    // 现状 = darktable opposed 原版（只抬不压 + 加性 chroma 补偿），与 0.12.4 一致。
    // ① darktable opposed 外推：**只抬不压**（PC tofloat.glsl:188 `max(hlVal, ...)`
    // 逐字口径）。判据 = 裁剪判据（0.987 × 中性白）：[中性白, 满阱) 这段过曝区必须
    // 纳入，否则被 P0 平滑拉到 1.0 以下的饱和 G 永远进不来。
    // ── 0.12.105：判据补上**逐通道满阱归一**（PC `hlVal = raw / whitePoint[c]` 口径）──
    // 旧式 `v >= uHlClip`(0.987) 判在 WB 后域，而本域中性白 ≡ 1.0、满阱 = gain[c]
    // （本机 R 1.77 / B 1.63）⇒ R 通道 RAW 仅 56% 满阱就算"裁剪"。真机 14:14 的
    // 粉色高光就是这样被 opposed 拿 refavg 抬 G ⇒ 中性灰斑；该帧参考帧 sat 0.0002
    // 而 hlCnt 12910，全是这一类。
    // 现式 = 该通道 RAW ≥ 98.7% 满阱才重建，与 PC 的 raw/whitePoint 同口径。
    // 标记 1（长帧饱和 + 短帧真值）的 v 已被 sat-fix 抬到满阱之上 ⇒ 仍触发，
    // 所以 v0.12.10 担心的"G 不被抬起 ⇒ 品红"不会重演。
    // ── 0.12.179：opposed 回到 WB 域（撤销 0.12.170 的逐通道满阱归一化）─────
    // 判据与 0.12.105 一致：v >= 0.987 × max(满阱[c], 1.0)，与 WB 域满阱同域。
    if (v >= uHlClip * max(at3(uSatClip, c), 1.0)) {
        // ── 0.12.190：refavg 抬 G 的强度按**参考场中性度**门控（治绿边）──────
        // WB 域 refavg 对 G 位 = ((R^⅓+B^⅓)/2)³。天空（R/G 0.95）是中性略蓝，
        // 抬 G 正确；粉色物体上的高光（R/G 1.15，真机 hlRgG=1.156）其 R 本身就高，
        // 抬 G 会把边缘推成**绿边**。判据：参考场离中性越远，抬升越少；≥0.12
        // 完全不抬（原值 = 满阱比例的粉色，正确）。
        // 0.12.192：**只在局部参考可信时**做中性度门控。rrH.z 是第 1 级参考场
        // 的可用样本比例；背景虚化高光点（如截图 1 的 ①）局部 32 块常全饱和 ⇒
        // 退到全图池化比（hlRgG 1.1 是整张粉蘑菇场景的统计），若拿它当"偏色"
        // 关掉抬升，中性高光就会被判成品红。局部不可信 ⇒ 按中性处理（照常抬）。
        vec3 rrH = refRatiosW((vec2(p) + 0.5) / vec2(uSize));
        float cenH = max(abs(rrH.x - 1.0), abs(rrH.y - 1.0));
        float wN = (rrH.z > 0.30) ? (1.0 - smoothstep(0.04, 0.12, cenH)) : 1.0;
        outv = max(v, mix(v, refavg(p) + at3(uChroma, c), wN));
    }
    // ── 0.12.179：中性大面积饱和核 —— 三通道**同步**按参考比 × 实测 luma 重建 ──
    // 真机 2026-10-29 16:52 窗外粉云（165153.dng 逐 photosite 复算）：
    //   G 先饱和（WB 域 1.000），R/B 却**没到**满阱判据（1.300 / 1.139）。
    //   分支① 只改 G 位 ⇒ R/B 的伪影原样保留 ⇒ 输出 R/G 1.30、B/G 1.14 = 品红。
    //   同一片云的周围未饱和样本给出参考比 R/G 0.944、B/G 1.036（中性略蓝）。
    // 判据：本 2×2 块内有通道到满阱（nsat≥1），且局部参考色比**接近中性**
    //   （|ratio−1| ≤ 0.09）—— 满足即"该核的真实色度 = 周围中性区"，按
    //   allSat 同一套式子（参考比 × 实测 luma）改写三通道，把 R/B 的伪影一起拉回。
    //   粉色物体上的镜面高光其参考场本身偏粉（hlRgG 1.1+）⇒ 不满足 ⇒ 保持原样
    //   （0.12.171 停 chroma 要护的正是它）。chroma 补偿维持停用：WK 域实测它会把
    //   该场景的 G 顶到 1.55（R/G 0.84、偏绿），量级远超真实缺口 0.08。
    if (uGRecon <= 0.5) {
        // 先做便宜的块内饱和度判定（4 次 fetch）；只有确有通道到满阱才查参考场
        //（refRatiosW 是三级纹理读，不能对全图每个像素付这个成本）。
        float Rn, Gn, Bn;
        blockRGB(p, Rn, Gn, Bn);
        if (Rn < 0.0) Rn = v;
        if (Gn < 0.0) Gn = v;
        if (Bn < 0.0) Bn = v;
        int nsN = 0;
        // 0.12.183：容差 0.99 → 0.90。灯面边缘的 R/B 常落在 0.90~0.98 满阱，
        // 若判成 nsat=1 就会走"参考比"路径（refavg 把 G 抬到 1.76），与核心的
        // nsat≥2（中性白 1.266）形成亮度台阶 = 灯面亮斑。统一按全饱和处理。
        if (Rn >= uSatClip.x * 0.90) ++nsN;
        if (Gn >= uSatClip.y * 0.90) ++nsN;
        if (Bn >= uSatClip.z * 0.90) ++nsN;
        if (nsN >= 1) {
            vec3 rrN = refRatiosW((vec2(p) + 0.5) / vec2(uSize));
            float cenN = max(abs(rrN.x - 1.0), abs(rrN.y - 1.0));
            // ── 0.12.180：**面积门控**（0.12.179 的块状亮斑事故）────────────────
            // 179 只判 nsN≥1 就整块改写。于是"块内只有一两个 photosite 饱和"的边缘块
            // 也被重建，而 luma 锚 lvN 含饱和通道（G=1.0）⇒ 整块 16×16 px 被提亮 ⇒
            // 成片出现硬边方块亮斑（真机 17:14 天空/窗玻璃两处）。
            // rrN.z = 第 1 级参考场"最稀缺通道的可用样本比例" ⇒ satFrac=1−z 就是
            // 该 32×32 CFA 块的饱和占比。只对**大面积饱和核**（smoothstep 0.55~0.85）
            // 重建，边缘块按权重混合 ⇒ 品红修复保留、块状台阶消失。
            float satFracN = 1.0 - clamp(rrN.z, 0.0, 1.0);
            float wAreaN = smoothstep(0.55, 0.85, satFracN);
            // ── 0.12.182：按"几个通道饱和"分流（灯面色块事故）─────────────────
            // nsat==1（G 饱和、R/B 有真值）⇒ 参考比正确（天空/云的品红）。
            // nsat>=2（两/三通道全饱和）⇒ **真实色度不可知**，参考比只会取到
            //   灯面周围暗环境的色偏 ⇒ 灯面被染成黄/蓝色块（0.12.132 的"偏暖米色"
            //   同一机制）。此时唯一不骗人的表示是**中性白 × 实测 luma**。
            // 0.12.183：nsat≥2（灯面/发光面）**无条件**按中性白重建 —— 不做面积
            // 混合。混合权重在 0.55~0.85 之间会让灯面内部出现"部分重建"的
            // 亮度/色度台阶 = 大面积色斑。nsat==1 才保留面积门控（防边缘块提亮）。
            bool useRefN = (nsN == 1) && (cenN <= 0.09);
            if (nsN >= 2 || (useRefN && wAreaN > 0.002)) {
                float rgN = useRefN ? rrN.x : 1.0;
                float bgN = useRefN ? rrN.y : 1.0;
                float lvN = 0.2126 * Rn + 0.7152 * Gn + 0.0722 * Bn;
                float denN = 0.2126 * rgN + 0.7152 + 0.0722 * bgN;
                float scN = lvN / max(denN, 1e-3);
                float refOut = (c == 0) ? rgN * scN : ((c == 1) ? scN : bgN * scN);
                outv = (nsN >= 2) ? refOut : mix(outv, refOut, wAreaN);
            }
        }
    }
    // ② RAW 域 G 通道色度重建 + 三通道全饱和同步重分配（v0.10.142）
    if (uGRecon > 0.5) {
        vec2 uv = (vec2(p) + 0.5) / vec2(uSize);
        // 0.12.141：三通道值取自**同一个 2×2 块**（blockRGB）。
        // 旧实现用 8 邻域（neighborOfChan8）会跨到相邻块，而"块内是否饱和"是块级的
        // 物理事实 —— 跨块取值让光晕像素被邻块的核心污染成"全饱和"（详见 blockRGB 注释）。
        float Rv, Gv, Bv;
        blockRGB(p, Rv, Gv, Bv);
        if (Rv < 0.0) Rv = v;
        if (Gv < 0.0) Gv = v;
        if (Bv < 0.0) Bv = v;
        // 0.99 容差：饱和边界上的相邻 photosite 可能差一两个 LSB 才到顶
        // ── 0.12.136：判据从"三通道全饱和"放宽到"**≥2 通道**饱和" ──────────────
        // 真机数据（粉色陶瓷椅背的镜面反光，DNG 逐 photosite）：
        //   WB 域 R 1.671 / G 1.000 / B 1.510，满阱 2.217 / 1.0 / 1.510
        //   ⇒ **R 未饱和、G/B 恰好满阱**（nsat = 2）。
        // opposed 的 `max(v, refavg+chroma)` 对 G 位算出 refavg_G ≈ 0.95 < v=1.0
        // ⇒ 三个通道都不被改写 ⇒ 输出色比 (0.754 : 1 : 1) = 青绿；
        // 若反光让 R 先满阱则得到 (1 : 0.85 : 0.9) = 品红。用户看到的
        // "绿色光晕 / 品红斑块"就是这两种通道饱和顺序的直接结果。
        // 为什么放宽是对的：WB 域满阱逐通道不同（2.217/1/1.510），三通道"同时"
        // 饱和只是恰好同比例的情形；**两个通道顶到满阱就已经说明真实亮度 ≥ 满阱**，
        // 此时未饱和的那个通道只剩"下界"信息，色比不可知 ⇒ 与全饱和同样处理
        //（按参考比 → 中性白 + 实测 luma 锚）。全饱和（nsat=3）是其特例。
        // 单通道饱和（nsat=1）**不动**：真实的红色 LED / 彩色光源只饱和一个通道，
        // 放宽到 1 会把它们洗白。
        int nsat = 0;
        if (Rv >= uSatClip.x * 0.99) ++nsat;
        if (Gv >= uSatClip.y * 0.99) ++nsat;
        if (Bv >= uSatClip.z * 0.99) ++nsat;
        if (nsat >= 2) {
            // 三通道全饱和（台灯真机：占 G 饱和像素的 35.9%）：三个锚点全是裁剪值
            // ⇒ 单通道反推失效（Gest≈1.0 被下界卡住）⇒ 经 WB + CCM 后必然品红
            //（本片实测 CCM 后 R/G 3.505）。
            // 真实色度已不可知，改为**按参考比例同步重分配三通道**并**保持 WB 域 luma
            // 不变**（三通道都在各自 clip 上，当前 luma 即 clip 的加权）。
            // ⚠ 判定与写回都必须在**所有** photosite 上做（不能只做自身饱和的那些）：
            // 0.99 容差意味着块内某个相位可能刚好没到顶，若它不被改写，三通道就不同步
            // ⇒ luma 不守恒（只抬 G = 上一版"亮度暴涨"的机制）且落点回不到中性
            //（离线实测：只写自身饱和的相位 → R/G 2.28；三相位同步 → 见交付报告）。
            // 这里不 clamp：目标值本就 >1（G≈1.25、R≈1.29），用 uGCap 去钳会把刚
            // 重建出来的亮度压回去。
            // allSat 走 **CPU 全局池化比**（uRefGlobal）：这类像素本来就无局部参考，
            // 全局是它最稳的估计；局部比值在小样本块上会爆大（离线：普通组池化
            // 1.617 vs 块比值 1.142），而 allSat 用全局池化落点 1.072/0.895（达标）。
            // ── 0.12.159：**按饱和区面积在「中性白」与「参考比」之间插值** ────────
            // 用户实测（10-29 08:43，粉色塑料上的镜面高光）：真实机高光中心被重建成
            // **粉色**，与物体混成一片，观感"高光里夹着粉色"。
            // 根因：本支用 uRefGlobal（**全图池化参考比**）重建色度。粉色物体主导的
            // 场景里这个比值偏粉 ⇒ 高光 = 粉 × 亮度。
            // 但两类饱和区的物理含义不同：
            //   · **镜面高光**（面积小）：反射的是**光源色**，不是物体色 ⇒ 应当趋白
            //   · **大面积饱和**（天空、灯面）：色度确实由周围同色区决定 ⇒ 参考比正确
            // 本层能拿到的面积代理 = 参考场的第 1 级饱和率（refRatiosW 的 z 分量，
            // 32×32 块的未截断样本占比）。小高光 ⇒ 饱和率低；天空/灯面 ⇒ 高。
            // 阈值 0.25~0.65：镜面高光在一块 32 块的窗口里占比通常 < 20%，天空 ≈ 100%。
            vec3 rrA = refRatiosW(uv);
            float satFrac = 1.0 - clamp(rrA.z, 0.0, 1.0);
            float wRef = smoothstep(0.25, 0.65, satFrac);
            float rg = mix(1.0, max(uRefGlobal.x, 1e-3), wRef);
            float bg = mix(1.0, max(uRefGlobal.y, 1e-3), wRef);
            // ── v0.12.14：亮度锚 **满阱 lc → 实测 lv**（灰斑的根因）──────────────
            // 原式 lc = luma(满阱) 是个**与场景无关的常数**（本机 = 1.237）。用它当锚，
            // 就把这个像素的亮度钉死在"满阱亮度"上 —— 哪怕它的真实亮度远高于此
            //（bracket + sat-fix 下可达 4.0）⇒ 值被压、显示成灰。
            // 改成实测 lv = luma(Rv, Gv, Bv)：三通道全饱和时它恰好等于 lc（与旧行为
            // 一致，天空不变），而短帧救回过或部分通道未饱和时它反映真实亮度（不压暗）。
            // 依据（灯面，真机 215028/215030）：RAW 三通道都饱和到同一数字 ⇒ WB 后
            // 必然是 (2.22, 1.0, 1.68) 的伪彩；opposed 的 refavg 只能把 G 抬到 1.936
            //（R/G 仍 1.147）⇒ 淡品红修不掉。而"三通道都饱和"时真实色度**不可知**，
            // 唯一不骗人的表示是**中性白 + 真实亮度**，正是本支 + lv 锚给出的结果。
            float lv = 0.2126 * Rv + 0.7152 * Gv + 0.0722 * Bv;
            float den = 0.2126 * rg + 0.7152 + 0.0722 * bg;
            float sc = lv / max(den, 1e-3);
            // ⚠ v0.10.161 **故意不改成 max(v, 估计值)**（与任务书 ① 的字面口径的
            // 唯一偏离，离线数据驱动）：本支的前提是"三个锚点全是裁剪值"，即
            // 本通道原值 = 满阱 = 不含场景信息。此处"保留原值"保留的是**满阱比例**
            // （本机 1.898 : 1.0 : 1.643 = WB 增益的伪影），必然偏品红。
            // 离线逐位仿真（/tmp/hl-fix/verify.py，shot.dng，含真实软门控）：
            //   高光区(2x2 单元 G≥0.987) R/G：本式 0.959 / max(v,估计) 1.212 /
            //   libraw 标准链 0.975 ⇒ max 版把高光推离中性 15 倍于本式。
            // 且"只抬不压"在本支救不了任何东西：本支输出对 G 恒为抬高（sc>1.0，
            // 除非参考场 rg>2.12 的极端偏红），被压低的是 R/B —— 那正是把满阱伪影
            // 拉回中性的**有益**动作。故本支保留改写；只抬不压只适用于"原值有信息"
            // 的路径（分支① 与普通 G 分支，二者已是 max）。
            outv = (c == 0) ? rg * sc : ((c == 1) ? sc : bg * sc);
        } else if (uRbRecon > 0.5 && (c == 0 || c == 2)
                   && v >= at3(uSatClip, c) * 0.99
                   && Gv > 0.0
                   && ((c == 0 && Bv > 0.0) || (c == 2 && Rv > 0.0))) {
            // v0.10.179：clip 过渡带的"部分满阱"品红窄带（2026-10-06 台灯样张 RAW
            // 域定案，docs/research/11 号 §6）。机理：本通道（R/B）已顶满阱、其余
            // 通道未满的 photosite —— allSat 三判据够不着（G 未满）、普通 G 分支
            // 只管 c==1、分支① 的 max 只抬不压 ⇒ 满阱比例（WB 增益 gainR 2.027 /
            // gainB 1.443 的伪影）原样保留，经 CCM 后成 4~6px 品红窄带。
            // 处置与 allSat 分支（v0.10.161）哲学同源：本支前提 = 本通道原值是
            // 满阱下界、不含场景信息，"保留原值"保留的是 WB 增益的满阱比例 ⇒
            // 直接按参考比反推替换（与普通 G 分支 gest = 0.5*(Rv4/rg + Bv4/bg)
            // 严格对称，复用上面 allSat 判定已取好的 8 邻域值 Rv/Gv/Bv），不做
            // max、不做软门控；darktable opposed 的"只抬不压" max 语义只适用于
            // 原值有信息的路径（分支① 与普通 G 分支，二者已是 max）。
            // ⚠ 判据用**满阱 uSatClip**（0.99 容差同 allSat）而不是裁剪判据
            // uHlClip=0.987：[0.987, 满阱) 是未 clip 的过曝值、有真实场景信息，
            // 归分支① 的 max 语义管，本支绝不能碰。
            vec3 rr = refRatiosW(uv);
            // 0.12.160：与 allSat 同口径 —— 小面积镜面高光用**中性白**（光源色），
            // 大面积饱和区才用参考比。见 allSat 分支的注释与 §39。
            float satFr = 1.0 - clamp(rr.z, 0.0, 1.0);
            float wR = smoothstep(0.25, 0.65, satFr);
            float rg = mix(1.0, rr.x, wR);
            float bg = mix(1.0, rr.y, wR);
            float est = (c == 0) ? 0.5 * (Gv + Bv / bg) * rg   // R 位：G + 对侧 B
                                 : 0.5 * (Gv + Rv / rg) * bg;  // B 位：G + 对侧 R
            outv = est;
        } else if (v >= uHlClip && c == 1) {
            // 普通 G 重建路径：块比值加权口径（逐位不变，除 half-float 折算舍入）
            vec3 rr = refRatiosW(uv);
            // 0.12.160：同上 —— 镜面高光的 G 反推也走中性白，不染物体色。
            float satFg = 1.0 - clamp(rr.z, 0.0, 1.0);
            float wRg = smoothstep(0.25, 0.65, satFg);
            float rg = mix(1.0, rr.x, wRg);
            float bg = mix(1.0, rr.y, wRg);
            // G 被截断但 R/B 还留有实测值：用参考色度比例反推 G（原有路径，4 邻域取值
            // 与改动前逐位一致 —— 上面那组是 8 邻域，仅用于 allSat 判定）
            float Rv4 = neighborOfChan(p, 0);
            float Bv4 = neighborOfChan(p, 2);
            if (Rv4 > 0.0 && Bv4 > 0.0) {
            float gest = clamp(0.5 * (Rv4 / rg + Bv4 / bg), v, uGCap);
            // v0.10.138 软门控（真机实锤驱动）：硬判据 v>=clips 会让饱和边界上受噪声
            // 影响的像素在「重建（G 抬升约 27%）」与「不重建（原值）」之间逐像素翻转
            // ⇒ 空间上出现斑点。用户真机 A/B：「关掉本功能斑点消失、但红色更重」。
            // 不能用 smoothstep(v) 软化：截断后 v 被钳在 1.0，所有饱和像素的 v 相同。
            // 正确门控量是**邻域饱和率**（ref 场 z 已是插值后的未截断样本占比，空间连续）：
            //   邻域大部分未饱和 ⇒ 该像素的饱和可能只是噪声 ⇒ 弱重建
            //   邻域大部分饱和   ⇒ 真饱和区 ⇒ 全强度重建
            // 门控量固定用第 1 级占比 rr.z（32 块邻域饱和率），与"最终选了哪一级
            // 参考"无关：它表达的是该像素邻域的空间连续性，不是参考的可信度。
            float satFrac = 1.0 - clamp(rr.z, 0.0, 1.0);
            // max 兜底（v0.10.161）：gest 的下界是 v，但 uGCap < v 的退化输入会让
            // clamp(x, v, uGCap) 返回 uGCap < v ⇒ mix 后低于原值。max 后不可能压低。
            outv = max(v, mix(v, gest, smoothstep(0.05, 0.30, satFrac)));
            }
        }
    }
    // ── v0.10.167 输出布局：单通道（值恒在 .r）─────────────────────────────
    // 前置前：按 CFA 相位写回（oColor = 相位槽位），供 RCD 三段读 .rgb 按相位取。
    // 前置后：本输出是 **P0 的输入**，P0 的 kFsRawDenoiseFused::fused0() 读 .r
    //   （与 tex_fused_ 同约定）；RCD 改读 P0 的输出 tex_mos_ ⇒ 相位布局无消费者。
    // ⚠ 变的只是写出布局，outv 的判定/计算公式逐字未动（见本 shader 顶部说明）。
    oColor = vec4(outv, 0.0, 0.0, 1.0);
})";

// Pass2：胶片模拟内核 —— v0.7.9 起 = **filmic 专属**。
// 旧的 log 域胶片链（±4EV 编码 + contrast/toe/shoulder/cross 染料串扰）已整体删除：
// filmic 主导后它在中性参数下域内恒等、纯烧 GPU；而**域外半斜率延展不是恒等** ——
// 实测最暗 0.5%（pLo≈-5.6EV）在 ±4EV 域外被压缩后才进 filmic，而 blackEv 标定用的是
// 原始分位 ⇒ 标定与渲染不一致（老补丁悄悄改变 filmic 的输入动态范围）。
// 分区饱和/分离色调/单色/色彩外观层保留：是风格能力（中性恒等），不是补丁。
const char* kFsFilm = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform vec3 uSat;    // 阴影/中间/高光 饱和度
uniform vec2 uSplit;  // 阴影暖 / 高光冷
uniform float uMono;
uniform float uToneK;      // 基准影调强度（0 = 恒等/旧行为，1 = 完整基准）
uniform float uLook;       // 色彩外观层强度（0 = 恒等/A-B 关，M3 P1）
uniform float uLookSat;    // 亮度保持的饱和增益
uniform float uLookChroma; // 色度 S 曲线端点增益
// v0.7.3：**场景曝光增益整体废除**（原 M4-6 uExposureGain）。filmic 的 black/white
// relative exposure 已经把"中灰落在显示域哪个位置"固定住，再叠一层按场景算的增益
// 就等于回到被否掉的"中位锚定"—— 那样压 EV / 降 AE 曝光都会被重新补偿掉，曝光这一维失效。
// 预览域映射（v0.8.0，取代 v0.6.48 的"直通"）：HAL 的 OES 预览流是**已被 ISP tone
// 过的 8bit 显示域**，与 RAW 链的线性域同场景差 1.70–3.87×（v0.6.48 实测）。直通在
// EV=0 时代勉强成立（两者亮度巧合接近）；EV 补偿上线后直通×2^EV 把预览中位推到
// 229/255，整体过亮+高光溢出（v0.7.9 真机）。v0.8.0 起预览输入先 **除以在线估计的
// 域比 uDomainRatio**（= 预览统计中位 / 最近一次静拍统计中位，EMA 平滑），映射回
// RAW 等效域后**走与成片完全相同的 filmic 链** —— 真 WYSIWYG，且 filmicTone 输出
// 数学有界（永不爆白）。auto_black_ev 关（黑白点为用户固定值）时曲线与成片严格一致；
// 开时预览按 HAL 域统计自标定（各自自洽，与成片近似）。
// 注：旧的"直通"理由（baseTone S 曲线压死 HAL 暗部 59×）已随旧链删除而消失；
// filmic 吃线性输入 + 域映射后不再有压死问题。
uniform float uHalDomainMap;
uniform float uDomainRatio;
// ── M4-7 局部色调映射 ────────────────────────────────────────────────
// 低频亮度图（32×24、显示域 luma、硬件 LINEAR 上采样）+ 强度 + knee。
// 为什么必须局部：全局曲线在"边缘高光 + 中央暗部"的高 DR 场景里两难 ——
// 保高光则中央暗（自适应影调 gain 被 P95 锁在 1.0）、提中央则高光爆
// （离线实测中位锚定会让 6.6%–13% 像素过曝）。局部图只让**暗区**吃到提亮，
// 亮区强度归零，因此高光不受影响、黑位乘性保持。
uniform highp sampler2D uLocal;
uniform float uLocalStrength;  // 0 = 关（A/B 通路）
uniform float uLocalKnee;      // 提亮衰减 knee 下界（局部亮度 / 画面基准 的比值）
uniform float uLocalKneeHi;    // 上界：ratio 高于它完全不提亮
uniform float uLocalRef;       // 画面基准亮度（局部图中位，CPU 侧下发）
uniform vec2 uLocalSize;       // 局部图尺寸（用于手写双线性）
// 期 B：工作空间 = 线性 Rec.2020 ⇒ luma 取 **BT.2020 的 Y 系数**（原 0.2126/0.7152/0.0722
// 是 Rec.709/sRGB 原色的；域与系数不一致会让 uSat 分区权重 / uLook / uMono 偏）。
// ⚠ 本 shader 内没有 OETF ⇒ 三处调用（uSat 分区、uLook、uMono）全在**线性工作空间域**。
float luma(vec3 c) { return dot(c, vec3(0.2627, 0.6780, 0.0593)); }
// ── 基准影调（M3 色彩内核 v1，治"发灰"）──────────────────────────────
// 中性参数下本 pass 数学上恒等（out3 = lin），全链没有任何影调工程 —— 直方图
// 挤中间（P0.1≈20、>224 占 0.00%、无真黑无真白）。三点锚定的 S 曲线：
// 黑点→0 / 中间调枢轴→枢轴 / 白点 1.0→1.0，端部导数→0（rolloff 而非硬截断，
// 避免暗部钉死出尖峰色带）。t^p/(t^p+(1-t)^p) 保证 t=0/1 精确取到端点。
// v0.5.37：枢轴 0.18→0.22、p 1.35→1.20 —— v0.5.36 真机实测"中灰锚定"对
// 中灰以下为主的暗光场景整体压暗 29%（均值 88.6→62.8），枢轴上移到 0.22 做
// 相机 JPEG 式中间调微提亮，黑位与高光 rolloff 保留。
// v0.6.14（M4-4，治"原片发灰"）：真机同场景 DNG 对照 libraw 标准链 —— 黑位
// P0.1 = L*31~35（libraw 6.3、厂商直出 0）、对比 45（libraw 61）。
// v0.6.16（M4-5，第二次修正）：v0.6.14 用"提高 p 到 2.00"去压黑位是**错的旋钮** ——
// 旧归一化把 [mn,1] 映射成 t∈[0,1] 后再套标准 S 曲线，**右半段的不动点跑到 xn≈0.609**，
// 于是中灰以上（画面主体）落进"S 曲线下半段"被**压暗**（实测 baseTone(0.4)=0.34 < 0.4、
// 显示域 0.70→0.66），画面整体变暗发闷，用户报"没眼看"。
// 修法：把 xn 归一化到"以 mn 为 0.5"的坐标再套 S 曲线 —— **不动点恒为 mn**，
// 暗部压暗、中灰不动、高光提亮；压黑位改用 blk（黑点），p 退回 1.20。
// 离线（同场景 DNG）：L* 64.1 / 黑位 0 / P95 86.8 / 对比 54.2；旧包 64.4 / 32.1 /
// 83.5 / 45.1 —— 亮度持平，黑位与高光同时改善。
// v0.6.17（M4-6，治"闷"）：v0.6.16 仍留着 g=0.82 幂提亮，而**幂提亮是降对比的旋钮** ——
// 线性域 pow(x,0.82) 把 0.01 放大 2.35×、把 0.5 只放大 1.13×，等于抬暗部 = 压缩动态范围。
// 真机实测 0.6.16：对比 66.6 / P5 4.5；厂商直出 89.2 / 2.1 —— 亮度靠 g 补，代价就是"平"。
// 修法：**提亮改用线性域曝光**（与 PhotonCamera 的 exposureCompensationDomain=Linear 同一约定），
// 对比交给 p，g 整段删除。三个旋钮至此正交：ev 管亮度、p 管对比、blk 管黑位。
// 离线（真机成片反推，同场景对照厂商直出）：
//   旧包 0.6.13  对比 68.4 / P5 13.1 / P95 81.5 / C* 4.14
//   ev+0.35 p1.60 对比 93.1 / P5  1.9 / P95 95.0 / C* 5.58   ← 对齐厂商直出
//   厂商直出 336 对比 89.2 / P5  2.1 / P95 91.4 / C* 5.96
// 注：ev 与 p 都受"高光硬截断"约束（p2.0 时 P95 已 97.2、ev+0.70 时 99.6 溢出），
// 所以取 p1.60 而非 2.00；高光滚降是下一步，做完才能放开这两个旋钮。
// v0.6.18（M4-6 补，治"暗"）：v0.6.17 删掉 g 之后**暗场景被整体压暗** —— 真机实拍
// 阳台 L* 38.3→24.5、书桌 22.5。根因正是 lesson 里早记过的机制：S 曲线对"中灰以下
// 为主"的场景必然整体压暗，而原来的提亮手段就是 g。真机三场景的场景白点（显示域
// P95）普遍偏低，等于**欠曝 1–1.2EV**；固定曝光补偿救不了（暗场景要 +1.2EV、
// 亮场景只能承受 +0.3EV，差 4 倍 —— 合成压力测试：+1EV 时正常曝光场景 27% 像素爆掉）。
// 本轮改为两条腿：① 引擎按场景白点自适应算曝光增益（updateAutoGain，限幅 [1,4]×，
// 只提亮不压暗；设置页"自适应影调"可关，关掉即退回固定 exp2(0.35)）；
// ② kFsOut 加高光滚降（软压缩 + 去饱和），replace 掉 clamp 硬截断 —— 它是放开
// 增益上限的前提，也让过曝的灯/窗不再变红变粉。
// ── filmic 基准影调（v0.7.3）：darktable v3 spline 取代 Reinhard ──────────────
// 为什么换：Reinhard 没有黑点 ⇒ 暗部被整体抬亮、画面"没有黑"（实测死黑 0.3%，
// PhotonCamera 同场景 16.8%）。filmic 的 **black relative exposure 把黑点做成曲线
// 端点**（不是硬截断 —— 旧 `blk=0.008` 那种截断会丢暗部信息），暗部能真落到黑
// （同场景死黑 → 11.7%）。参数是 **EV（相对中灰 18.45%）**，与机型无关：换手机/
// 换场景都由场景分位自动标定（v0.7.3 起不再有"归一到固定中位"的曝光增益）。
//
// 公式出处：darktable `src/iop/filmicrgb.c`（commit 92f6c28）——
//   `_compute_output_power` / `dt_iop_filmic_rgb_compute_spline` 的 v3 分支 /
//   POLY_4 toe·shoulder 的 5×5 线性系统（f(0)=black / f'(0)=0 / f(T)=y / f'(T)=c / f''(T)=0）。
// 系数在 CPU 侧算好（computeFilmicSpline），这里只做 log 编码 + 三段多项式求值；
// 与 PhotonCamera 的同名实现逐项一致（见其 RawDemosaicProcessor.kt / DarktableFilmicToneAlgorithm.kt）。
// ── E4-4：filmic latitude 的 per-channel 拆分（[5..7]=shoulder / [8..10]=toe）──────
// 数学依据（darktable v3 `dt_iop_filmic_rgb_compute_spline`）：
//   toe_log = (1 − lat)·grey_log + lat·xmin      ← 原版里 toe 与 shoulder **本来就是
//   sh_log  = (1 − lat)·grey_log + lat·xmax        两个独立表达式**，只是共用一个 lat
// ⇒ 拆成 lat_toe / lat_sh 并各给 R/G/B 一份，是**原版参数维度的自然展开**（三层乳剂
// 的 toe/shoulder 位置不同 = 阴影/高光色偏的物理机制），不是自研机制、不新增曲线、
// 不新增 pass。默认全 0.01（darktable 默认）⇒ 三通道系数逐位相同 ⇒ 与接线前逐位一致。
// ⚠️ **与现有旋钮的正交性**（规格 §3.4.3；改本段时勿引入改变端点的项）：
//   · [37][38] black/white EV → xmin/xmax = **端点**。本段只改 toe_log/sh_log 的**位置**，
//     五条约束（f(0)=black_disp / f'(0)=0 / f(Tl)=toe_d / f'(Tl)=contrast / f''(Tl)=0）
//     一条不动 ⇒ 端点与端点斜率不动，**只改中段形状**。
//   · [4] contrast → 线性段斜率：与 lat 都作用在曲线中部 ⇒ **有交互**（lat 改过渡区
//     位置、contrast 改斜率）；交互由 CPU 侧同一组求解自然表达，本段不读 contrast。
//   · [28] tone_strength → mix(lin, toned, k)：正交（本段改 toned 的形状，[28] 改混合比）。
// ⚠️ GPU 三禁（都是静默失败）：**手写 R/G/B 三份** —— 不用数组参数、不做动态索引、
//    不用多行宏。系数按**段**分组、vec3 的 .r/.g/.b = R/G/B 三层乳剂。
uniform vec3 uFilmicToeM1, uFilmicToeM2, uFilmicToeM3, uFilmicToeM4, uFilmicToeM5;
uniform vec3 uFilmicShM1, uFilmicShM2, uFilmicShM3, uFilmicShM4, uFilmicShM5;
uniform vec3 uFilmicLinM1, uFilmicLinM2;    // 中间线性段 y = m1 + m2·x
uniform vec3 uFilmicLatMin, uFilmicLatMax;  // 逐通道 toe_log / shoulder_log
uniform float uFilmicBlackEv, uFilmicDr, uFilmicInputMin, uFilmicInputMax;
uniform float uFilmicPower, uFilmicBlackDisp;
// v0.10.13：darktable filmic v5 gamut mapping 开关（1 = 开/标准，0 = A/B 关）
uniform float uGamut;
// v0.10.22：EV = **filmic 前线性域曝光**（PC/Darktable 的 BaselineExposure 语义）。
// 历史：v0.7.7 曾接在 CCM 后，被 auto 黑点统计归一（拉 ±1EV 成片纹丝不动）→ v0.7.8
// 改链末乘性；v0.10.21 真机验证链末乘性只整体平移亮度、黑位/对比不动（−0.3EV 复拍
// 仍灰），且与预览分支（历来前置）分叉。现两侧统一前置：EV 进曲线改变工作点，
// 负向沉黑位、正向顶高光。乘性 EV 不改 Ych 色度（2e-8），gamut min 语义不受影响；
// auto 黑点现默认关（v0.10.19），统计归一的旧前提已不成立。提亮逼近显示上限时
// 由 pass3 的输出域 rolloff 兜高光。
uniform float uExposure;
const float kFilmicGrey = 0.1845;               // darktable grey_source（18.45% 中灰）
const float kFilmicNormMin = 1.52587890625e-5;  // 2^-16，与 darktable NORM_MIN 一致
// 逐通道三段求值（Horner；vec3 的 .r/.g/.b = R/G/B）。手写三份的理由见上方三禁。
float filmicSplineR(float e) {
    if (e < uFilmicLatMin.r)
        return uFilmicToeM1.r + e * (uFilmicToeM2.r + e * (uFilmicToeM3.r + e * (uFilmicToeM4.r + e * uFilmicToeM5.r)));
    if (e > uFilmicLatMax.r)
        return uFilmicShM1.r + e * (uFilmicShM2.r + e * (uFilmicShM3.r + e * (uFilmicShM4.r + e * uFilmicShM5.r)));
    return uFilmicLinM1.r + e * uFilmicLinM2.r;
}
float filmicSplineG(float e) {
    if (e < uFilmicLatMin.g)
        return uFilmicToeM1.g + e * (uFilmicToeM2.g + e * (uFilmicToeM3.g + e * (uFilmicToeM4.g + e * uFilmicToeM5.g)));
    if (e > uFilmicLatMax.g)
        return uFilmicShM1.g + e * (uFilmicShM2.g + e * (uFilmicShM3.g + e * (uFilmicShM4.g + e * uFilmicShM5.g)));
    return uFilmicLinM1.g + e * uFilmicLinM2.g;
}
float filmicSplineB(float e) {
    if (e < uFilmicLatMin.b)
        return uFilmicToeM1.b + e * (uFilmicToeM2.b + e * (uFilmicToeM3.b + e * (uFilmicToeM4.b + e * uFilmicToeM5.b)));
    if (e > uFilmicLatMax.b)
        return uFilmicShM1.b + e * (uFilmicShM2.b + e * (uFilmicShM3.b + e * (uFilmicShM4.b + e * uFilmicShM5.b)));
    return uFilmicLinM1.b + e * uFilmicLinM2.b;
}
// 逐通道标量（log 编码 + 三段求值 + 幂）。三份**逐字复制**旧版 filmicScalar 的表达式
// 序列（只换 spline 函数）⇒ 默认三通道相同时与接线前的运算序列完全一致（逐位一致）。
float filmicScalarR(float v, float lo) {
    float s = max(v, kFilmicNormMin);
    float e = clamp((log2(s / kFilmicGrey) - uFilmicBlackEv) / max(uFilmicDr, 0.2), 0.0, 1.0);
    float c = clamp(filmicSplineR(e), lo, 1.0);
    return pow(max(c, 0.0), uFilmicPower);
}
float filmicScalarG(float v, float lo) {
    float s = max(v, kFilmicNormMin);
    float e = clamp((log2(s / kFilmicGrey) - uFilmicBlackEv) / max(uFilmicDr, 0.2), 0.0, 1.0);
    float c = clamp(filmicSplineG(e), lo, 1.0);
    return pow(max(c, 0.0), uFilmicPower);
}
float filmicScalarB(float v, float lo) {
    float s = max(v, kFilmicNormMin);
    float e = clamp((log2(s / kFilmicGrey) - uFilmicBlackEv) / max(uFilmicDr, 0.2), 0.0, 1.0);
    float c = clamp(filmicSplineB(e), lo, 1.0);
    return pow(max(c, 0.0), uFilmicPower);
}
// darktable 的颜色模式：0.5 × 逐通道 + 0.5 × max-RGB 归一。
// ⚠️ 本函数**只压亮度、保色相**：过曝的灯管（R/B 高、G 低）会留品红 —— 这是
// v0.5.37「过曝灯泡变红」与 v0.6.50 反复出现的老问题。所以后面的高光去饱和（w）
// **不是可选项**：离线实测同一场景，缺它时高光 R/G 1.277，补上后 1.000。
vec3 filmicTone(vec3 c) {
    vec3 p = max(c, vec3(0.0));
    vec3 naive = vec3(filmicScalarR(p.r, 0.0), filmicScalarG(p.g, 0.0),
                      filmicScalarB(p.b, 0.0));
    float mx = max(max(p.r, p.g), p.b);
    float ratioNorm = max(mx, uFilmicInputMin);
    float toneNorm = clamp(mx, uFilmicInputMin, uFilmicInputMax);
    // E4-4：norm 分支的输入是**标量** max-RGB，必须挑一套通道系数 —— 取 **G**
    //（主感光层，luma 权重 0.7152 最大）。默认三通道相同 ⇒ 与接线前逐位一致。
    vec3 normed = (p / ratioNorm) * filmicScalarG(toneNorm, uFilmicBlackDisp);
    return 0.5 * naive + 0.5 * normed;
}
// ── darktable filmic v5 gamut mapping（v0.10.13，标准算法补全）──────────────
// 出处：darktable src/iop/filmicrgb.c（filmic_v5 / gamut_mapping / gamut_check_RGB /
// filmic_desaturate_v4）+ src/common/colorspaces_inline_conversions.h（RGB↔Ych，
// Kirk 2006 LMS + Filmlight Yrg）+ src/common/gamut_mapping.h（clip chroma）。
// 2026-09-28 逐项核对 darktable master：Y 权重 0.68990272/0.34832189、D65 锚点
// 0.21902143/0.54371398、Y1931→2006 因子 1.05785528、clip 系数 0.97938…/0.42750…/
// 0.85649…/0.55499… 全部一致；PhotonCamera 的移植（DarktableFilmicToneShader.kt）
// 与 master 等价（管线工作域 BT.2020 vs 我们 sRGB —— 它 gamut check 后回 BT.2020，
// 我们管线本就是 sRGB 线性，check 完即输出，无需回转）。
// 为什么必须（v0.10.12 审核定案差距 #2）：tone mapping 之后色度可能被推到
// ① 超过 tone 前的色度（亮度压缩+max-RGB 归一在色度比上的次生效应）——标准做法
//   是色度取 min + 色相锁定；② Yrg 锥空间 r<0/g<0/r+g>1（物理不可实现的颜色）
//   —— 常色度常色相裁剪；③ Y 合法后仍可能单通道越界 —— 恒 Y 恒色相下解析解
//   最大合法色度（白/黑两边界）。此前"过曝灯管留品红/高饱和边缘过冲/色相漂移"
//   的直接机制就在这三步的缺失。
// 常量：来自 darktable 源码，不可调。
// ⚠ 期 B（公共工作空间切线性 Rec.2020）：下面 6 个 vec3 是**唯一随工作空间变**的一组
//   （工作空间 RGB ↔ LMS2006）。其余全部与工作空间无关：Y 1931↔2006 换算、D65 色度锚、
//   LMS2006↔Filmlight 变换、Vwright 加权，以及色域边界解析解内的系数（后者把"原色"
//   经 coeffs 参数传入，见 gamutClipChroma 调用点）。
//   新值由 `tools/rec2020-domain-matrices.py` 构造（构造法，非手填），并与 PhotonCamera
//   的 BT2020 版逐位对拍（max|Δ| = 9.8e-11）；阶段 4（AP0/D60）只需再换这一组。
const float kYchY1931To2006 = 1.05785528;
const float kYchD65R = 0.21902143;
const float kYchD65G = 0.54371398;
const vec3 kLmsWorkToLmsL = vec3(0.4067763459, 0.6178051992, 0.0458445893);
const vec3 kLmsWorkToLmsM = vec3(0.0677498630, 0.7489671634, 0.1001665159);
const vec3 kLmsWorkToLmsS = vec3(0.0221408643, -0.0153252588, 0.5876294574);
const vec3 kLmsToWorkRowR = vec3(2.8380181192, -2.3373915421, 0.1770173204);
const vec3 kLmsToWorkRowG = vec3(-0.2415770700, 1.5294941185, -0.2418685680);
const vec3 kLmsToWorkRowB = vec3(-0.1132319088, 0.1279577821, 1.6887750821);
const vec3 kLmsToFilmRowR = vec3(1.08771930, -0.66666667, 0.02061856);
const vec3 kLmsToFilmRowG = vec3(-0.08771930, 1.66666667, -0.05154639);
const vec3 kLmsToFilmRowB = vec3(0.0, 0.0, 1.03092784);
const vec3 kFilmToLmsRowL = vec3(0.95, 0.38, 0.0);
const vec3 kFilmToLmsRowM = vec3(0.05, 0.62, 0.03);
const vec3 kFilmToLmsRowS = vec3(0.0, 0.0, 0.97);
vec3 gamutWorkToLms(vec3 c) {
    return vec3(dot(kLmsWorkToLmsL, c), dot(kLmsWorkToLmsM, c), dot(kLmsWorkToLmsS, c));
}
vec3 gamutLmsToWork(vec3 l) {
    return vec3(dot(kLmsToWorkRowR, l), dot(kLmsToWorkRowG, l), dot(kLmsToWorkRowB, l));
}
vec3 gamutLmsToFilmlight(vec3 l) {
    return vec3(dot(kLmsToFilmRowR, l), dot(kLmsToFilmRowG, l), dot(kLmsToFilmRowB, l));
}
vec3 gamutFilmlightToLms(vec3 f) {
    return vec3(dot(kFilmToLmsRowL, f), dot(kFilmToLmsRowM, f), dot(kFilmToLmsRowS, f));
}
vec3 gamutLmsToYrg(vec3 lms) {
    // Y = Vwright 加权（未归一 LMS）；色度坐标用归一化 LMS（sum=1 的锥空间）
    float y = 0.68990272 * lms.x + 0.34832189 * lms.y;
    float sum = lms.x + lms.y + lms.z;
    vec3 n = abs(sum) > 1e-8 ? lms / sum : vec3(0.0);
    vec3 frgb = gamutLmsToFilmlight(n);
    return vec3(y, frgb.x, frgb.y);
}
vec3 gamutYrgToLms(vec3 yrg) {
    vec3 frgb = vec3(yrg.y, yrg.z, 1.0 - yrg.y - yrg.z);
    vec3 n = gamutFilmlightToLms(frgb);
    float denom = 0.68990272 * n.x + 0.34832189 * n.y;
    float s = abs(denom) > 1e-8 ? yrg.x / denom : 0.0;
    return n * s;
}
vec4 gamutYrgToYch(vec3 yrg) {
    // Ych = (Y, chroma, cos hue, sin hue)——存 cos/sin 免三角函数（darktable 同）
    float r = yrg.y - kYchD65R;
    float g = yrg.z - kYchD65G;
    float chroma = length(vec2(r, g));
    float cosH = chroma > 0.0 ? r / chroma : 1.0;
    float sinH = chroma > 0.0 ? g / chroma : 0.0;
    return vec4(yrg.x, chroma, cosH, sinH);
}
vec3 gamutYchToYrg(vec4 ych) {
    return vec3(ych.x, ych.y * ych.z + kYchD65R, ych.y * ych.w + kYchD65G);
}
vec4 gamutRgbToYch(vec3 c) { return gamutYrgToYch(gamutLmsToYrg(gamutWorkToLms(c))); }
vec3 gamutYchToRgb(vec4 ych) { return gamutLmsToWork(gamutYrgToLms(gamutYchToYrg(ych))); }
// 历史注（勿再误读）：v0.10.14 曾按链级模拟预删「Ych 色度取 min」与 desaturate(0)
// 两步，v0.10.17 起已按用户铁律**全量恢复**（先 100% 原版跑通→真机→数据评估）——
// 下面的 gamutMapV5 函数体是完整五步，本注释仅为历史记录。2026-09-28 定性：min 的
// 灰化是原版行为在本链语境的副作用（PC 同样带着它），根源在亮度结构不在 min。
// Yrg 锥空间合法性：r<0 / g<0 / r+g>1 时按常 Y 常色相解最大合法 chroma
vec4 gamutCheckYrg(vec4 ych) {
    vec3 yrg = gamutYchToYrg(ych);
    float maxC = max(ych.y, 0.0);
    float cosH = ych.z;
    float sinH = ych.w;
    if (yrg.y < 0.0 && abs(cosH) > 1e-8) maxC = min(-kYchD65R / cosH, maxC);
    if (yrg.z < 0.0 && abs(sinH) > 1e-8) maxC = min(-kYchD65G / sinH, maxC);
    if (yrg.y + yrg.z > 1.0 && abs(cosH + sinH) > 1e-8) {
        maxC = min((1.0 - kYchD65R - kYchD65G) / (cosH + sinH), maxC);
    }
    ych.y = max(maxC, 0.0);
    return ych;
}
// sRGB 色域边界上恒 Y 恒色相的最大 chroma（darktable gamut_mapping.h 的解析解，
// 系数为 LMS2006↔Filmlight 组合的常数，不可调）
float gamutClipChromaWhiteRaw(vec3 coeffs, float targetWhite, float y,
                              float cosH, float sinH) {
    float denY = coeffs.x * (0.979381443298969 * cosH + 0.391752577319588 * sinH)
               + coeffs.y * (0.0206185567010309 * cosH + 0.608247422680412 * sinH)
               - coeffs.z * (cosH + sinH);
    float denT = targetWhite * (0.68285981628866 * cosH + 0.482137060515464 * sinH);
    if (abs(denY) <= 1e-8) return 1.0e20;
    float yAsymptote = denT / denY;
    if (y <= yAsymptote) return 1.0e20;
    float den = y * denY - denT;
    if (abs(den) <= 1e-8) return 1.0e20;
    float num = -0.427506877216495
              * (y * (coeffs.x + 0.856492345150334 * coeffs.y + 0.554995960637719 * coeffs.z)
                 - 0.988237752433297 * targetWhite);
    float maxC = num / den;
    return maxC >= 0.0 ? maxC : 1.0e20;
}
float gamutClipChromaWhite(vec3 coeffs, float targetWhite, float y,
                           float cosH, float sinH) {
    const float eps = 0.001;
    float maxY = kYchY1931To2006 * targetWhite;
    float dy = max(maxY - y, 0.0);
    float maxC;
    if (dy < eps) {
        maxC = dy / max(eps * maxY, 1e-8)
             * gamutClipChromaWhiteRaw(coeffs, targetWhite, (1.0 - eps) * maxY, cosH, sinH);
    } else {
        maxC = gamutClipChromaWhiteRaw(coeffs, targetWhite, y, cosH, sinH);
    }
    return maxC >= 0.0 ? maxC : 1.0e20;
}
float gamutClipChromaBlack(vec3 coeffs, float cosH, float sinH) {
    float den = coeffs.x * (0.979381443298969 * cosH + 0.391752577319588 * sinH)
              + coeffs.y * (0.0206185567010309 * cosH + 0.608247422680412 * sinH)
              - coeffs.z * (cosH + sinH);
    if (abs(den) <= 1e-8) return 1.0e20;
    float num = -0.427506877216495
              * (coeffs.x + 0.856492345150334 * coeffs.y + 0.554995960637719 * coeffs.z);
    float maxC = num / den;
    return maxC >= 0.0 ? maxC : 1.0e20;
}
float gamutClipChroma(vec3 rowR, vec3 rowG, vec3 rowB, float targetWhite, float y,
                      float cosH, float sinH, float chroma) {
    float cw = min(min(gamutClipChromaWhite(rowR, targetWhite, y, cosH, sinH),
                       gamutClipChromaWhite(rowG, targetWhite, y, cosH, sinH)),
                   gamutClipChromaWhite(rowB, targetWhite, y, cosH, sinH));
    float cb = min(min(gamutClipChromaBlack(rowR, cosH, sinH),
                       gamutClipChromaBlack(rowG, cosH, sinH)),
                   gamutClipChromaBlack(rowB, cosH, sinH));
    return max(min(min(chroma, cw), cb), 0.0);
}
// gamut_check_RGB：Y 合法后仍可能单通道越界 —— 先把越界像素抬回域内测 Y，
// Y 取两态中点，再在恒 Y 恒色相下解最大合法 chroma
vec3 gamutCheckRgb(vec4 ychIn) {
    vec3 brightened = gamutYchToRgb(ychIn);
    float minP = min(brightened.x, min(brightened.y, brightened.z));
    brightened += max(-minP, 0.0);
    vec4 ychB = gamutRgbToYch(brightened);
    float y = clamp(0.5 * (ychIn.x + ychB.x),
                    kYchY1931To2006 * 0.0001517634, kYchY1931To2006);
    float newC = gamutClipChroma(kLmsToWorkRowR, kLmsToWorkRowG, kLmsToWorkRowB,
                                 1.0, y, ychIn.z, ychIn.w, ychIn.y);
    return clamp(gamutYchToRgb(vec4(y, newC, ychIn.z, ychIn.w)), 0.0, 1.0);
}
// filmic_desaturate_v4 的 saturation=0 特化（darktable filmic_v5 主循环调用
// gamut_mapping 时硬编码传 0.0f，与 PC 移植一致；风格饱和在 uSat/uLook 层）。
// 唯一生效分支 = filmic 同时"提亮且增饱和"时把绝对色度拉回中点。
vec4 gamutDesaturateV4(vec4 ychOrig, vec4 ychFin) {
    float chromaOrig = ychOrig.y * ychOrig.x;  // c×Y = 绝对色度
    float chromaFin = ychFin.y * ychFin.x;
    bool brightens = ychFin.x > ychOrig.x;
    bool resat = chromaOrig < chromaFin;
    if (brightens && resat) chromaFin = 0.5 * (chromaOrig + chromaFin);
    ychFin.y = max(chromaFin / max(ychFin.x, 1e-8), 0.0);
    return ychFin;
}
// ── darktable filmic_v5 主循环的 gamut 段，完整五步逐行对齐 ──────────────
// （filmicrgb.c filmic_v5：Ych_final[1] = fminf(orig, fin) → gamut_mapping
//   = 色相锁定 → Y clamp → filmic_desaturate_v4(saturation=0) → gamut_check_Yrg
//   → gamut_check_RGB。2026-09-28 用户定调：先 100% 原版跑起来，再按真机数据
//   评估——此前按自研分析预删两步的做法废弃。）
// original = tone 前线性输入（darktable 的 pix_in）、toned = filmicTone 输出。
vec3 gamutMapV5(vec3 original, vec3 toned) {
    vec4 ychOrig = gamutRgbToYch(original);
    vec4 ychFin = gamutRgbToYch(toned);
    // ① 色度收缩：tone mapping 不得增色度（相对色度域 min）
    ychFin.y = min(ychOrig.y, ychFin.y);
    // ② 色相锁定（gamut_mapping 开头）
    ychFin.z = ychOrig.z;
    ychFin.w = ychOrig.w;
    // ③ Y clamp 到显示域（CIE 1931→2006 因子）
    ychFin.x = clamp(ychFin.x, kYchY1931To2006 * 0.0001517634, kYchY1931To2006);
    // ④ desaturate(saturation=0)
    ychFin = gamutDesaturateV4(ychOrig, ychFin);
    // ⑤ Yrg 锥空间检查 + sRGB 色域映射
    ychFin = gamutCheckYrg(ychFin);
    return gamutCheckRgb(ychFin);
}
void main() {
    vec3 lin = texture(uTex, vUV0).rgb;
    // v0.10.22：EV = filmic **前**线性域曝光（PC/Darktable 的 BaselineExposure 语义）——
    // 预览分支历来如此（uDomainRatio 同域），静拍此前却是链末乘性（v0.7.8），EV≠0 时
    // 两侧分叉、WYSIWYG 破绽。乘性 EV 不改 Ych 色度（离线验证 2e-8），min 语义不变；
    // 改变的是曲线工作点：负向沉黑位、正向顶高光，滑杆有形状语义。v0.10.21 真机
    // 验证（−0.3EV）：后置乘性只整体平移亮度（黑位/对比不动，红杯 rel 0.65→0.76 仍灰），
    // 形状必须由曲线内的曝光决定。
    vec3 mapped = max(lin, vec3(0.0)) * uExposure;
    // ── 第 6 期（S11 / S12）：域映射**只影响输入域，不再短路后续链**──────────
    // HAL 显示域输入（预览链、YUV 静拍链）先除以在线估计的 HAL/RAW 域比，映射回
    // RAW 等效域；RAW 静拍链本来就是场景线性域，不动。
    // 旧写法（≤0.10.156）在这里直接 `return` —— 跳过 local_tone/sat/split/look/mono，
    // 而紧邻的注释却写着"预览与成片同链（真 WYSIWYG）"，**注释与代码矛盾**；
    // 同时 `uToneK = 0` 时预览无条件跑 filmic、成片却整段跳过，是第二处 WYSIWYG 破绽。
    // 另：本 uniform 原名 uPreviewMap，但它的真实语义是"输入是 HAL 显示域"——
    // 被当成"是否预览"用，正是 YUV 静拍链（HAL 域）被误当场景线性域的根因（S12）。
    // 现更名为 uHalDomainMap，与"是否静拍"完全解耦。
    vec3 out3 = (uHalDomainMap > 0.5) ? mapped / uDomainRatio : mapped;
    // v0.7.9：旧 log 域胶片链（log2 编码 / filmCurve / 域外延展 / 0.18·exp2 重构）已删，
    // filmic 直接吃线性场景输入 —— blackEv/whiteEv 的标定域与渲染域首次严格一致。
    // M4-7 局部色调映射：按**局部相对画面基准**的暗度补光 —— 比基准暗得越多补得越多，
    // 到 uLocalKnee 以上（含所有亮区/高光）归零。刻意放在 baseTone **之前**：这里还是
    // 线性域，S 曲线对暗部的弹性约 2（离线实测 +60% 线性 → 显示域 +45~55%），同样的
    // 强度比乘在曲线之后有效得多，语义也仍是"局部曝光补偿"。
    // 判据必须用**比值**而非绝对阈值：实拍场景普遍欠曝，绝对阈值（如"局部 < 0.45"）
    // 几乎全图命中，会退化成全局提亮（离线实测亮场景中央也被抬 20–32%）。
    if (uLocalStrength > 0.0) {
        // 手写双线性（texelFetch 绕过硬件过滤）：X300 的 Mali 上纹理过滤不可靠
        // （RGBA16F 实测无 LINEAR，项目 LSC 同样手写 4-tap）。若退回 NEAREST，
        // 32×24 会被放大成 128×128 的方块 —— 真机"亮斑"的另一半原因。
        vec2 t = vUV0 * uLocalSize - 0.5;
        vec2 i0 = floor(t);
        vec2 fr = t - i0;
        ivec2 sz = ivec2(uLocalSize);
        ivec2 b = clamp(ivec2(i0), ivec2(0), sz - 1);
        ivec2 bx = clamp(ivec2(i0) + ivec2(1, 0), ivec2(0), sz - 1);
        ivec2 by = clamp(ivec2(i0) + ivec2(0, 1), ivec2(0), sz - 1);
        ivec2 bxy = clamp(ivec2(i0) + ivec2(1, 1), ivec2(0), sz - 1);
        float ll = mix(mix(texelFetch(uLocal, b, 0).r, texelFetch(uLocal, bx, 0).r, fr.x),
                       mix(texelFetch(uLocal, by, 0).r, texelFetch(uLocal, bxy, 0).r, fr.x),
                       fr.y);
        float ratio = ll / max(uLocalRef, 1e-3);
        out3 *= 1.0 + uLocalStrength * (1.0 - smoothstep(uLocalKnee, uLocalKneeHi, ratio));
    }
    // 基准影调（norm-controlled + 高光去饱和）
    // filmic：内含 max-RGB 归一半段，直接作用在三通道上
    // ── v0.7.4：**删除高光去饱和**（原 v0.5.37 → v0.6.50 的 w / rel 保护 / luma 门限）──
    // 三条机制层理由（不是调参）：
    //   ① 判据 n0 是**影调前**的线性 max 通道，与"最终是否过曝"没有对应关系 ——
    //      同一个 n0 在不同曲线下对应完全不同的输出亮度，所以它**必然标不准**：
    //      v0.6.38→v0.6.45 三轮标定在换 filmic 后一次性全部作废，就是最直接的证据。
    //      实测（v0.7.3 真机）：n0=0.6 → 1.70EV → 当前 filmic 输出约 0.75（8bit≈225），
    //      **根本没到过曝**却是全量去饱和 → 天空 C* 2.77（PC 13.63），且因"向 max
    //      通道收敛"把色相推向紫（h 273.9 vs PC 262.1）。
    //   ② 原始动机（v0.5.37"过曝灯泡变红"＝硬截断保留色相）已被 filmic 覆盖：
    //      输出是 pow(clamp(spline, lo, 1.0), power) ∈ [0,1]，**数学上严格有界**。
    //   ③ 职责与 pass3 的 highlightRolloff 重复，而后者判据是**输出域** max 通道
    //      （就是"离显示上限多近"）—— 判断过曝该看输出，不该看输入。
    // 若将来实测发现"正常明亮区域仍过饱和"，再引入去饱和时判据必须挂在输出域。
    if (uToneK > 0.0) {
        // v0.10.17：filmicTone 之后接 darktable filmic_v5 的完整 gamut 段
        // （色度 min + 色相锁定 + Y clamp + desaturate(0) + Yrg/RGB 检查，
        // 逐行对齐原版；original = tone 前线性输入 out3）。
        vec3 toned = filmicTone(out3);
        if (uGamut > 0.5) toned = gamutMapV5(out3, toned);
        out3 = mix(out3, toned, uToneK);
    }
    float l = luma(out3);
    float ws = exp(-l * 3.0);
    float wh = smoothstep(0.5, 1.0, l);
    float wm = max(0.0, 1.0 - ws - wh);
    float sat = clamp(uSat.x * ws + uSat.y * wm + uSat.z * wh, 0.0, 2.0);
    out3 = mix(vec3(l), out3, sat);
    // ⚠ U-1 [23][24] split + [25] mono **不在这里** —— 期 B 已挪到 kFsOut 的显示域段
    //（LUT 之前），与 kFsStyle **同域同式**。原因：本 shader 是线性工作空间域，同一物理
    // 亮度下 luma 比显示域小（线性 0.18 ↔ 显示 0.46）⇒ 阴影权重 exp(-3l) 差 43%，
    // 编辑器里调出来的效果与拍照对不上（量化见归档文档 §8.1 的 ⑰d）。
    // ws/wh 本身留在本段：uSat 的分区权重仍用它（那是另一回事，不受影响）。
    // ── 色彩外观层（M3 P1，治"寡淡"）────────────────────────────────
    // v0.5.42 诊断：中性色准（R/G 0.987）但饱和 -21% / 通道分离 -29% /
    // 近中性像素多 10pp —— 颜色全挤在中性附近。这里补"外观"（不是风格）：
    //   ① 亮度保持的饱和增益：luma 不动，色差整体放大；
    //   ② 色度 S 曲线（v0.6.22 起用**亮度相对**判据）。rel = max|cc| / luma：
    //      0 = 中性，≈3.7 = 纯原色。rel < 1 走 smoothstep 的 t² 慢段，到 1 饱和于
    //      uLookChroma，即"不让已浓郁的色块过冲"。
    // v0.6.21：knee 0.25→0.06。原值把「线性 0.25」误当成「显示域 0.25＝64/255」，
    //      而 0.25 线性实为**显示域 136/255**；真机实测旧 knee 下有 99.8% 像素在 knee
    //      以下，中位像素只拿到 **0.5%** 增益（名义 25%），旋钮近乎惰性。
    // v0.6.22：改**亮度相对**判据。绝对 max|cc| 把"饱和度"与"亮度水平"混在一起 ——
    //      暗色物体即使很绿，绝对色度也小。真机实测（树叶 vs 易拉罐，均 look=关）：
    //        树叶 max|cc| P50 0.017 / P90 0.043 -> 旧判据仅 **5.05%** 增益
    //        罐体 max|cc| P50 0.032 / P90 0.184 -> **13.70%**
    //      树叶的 P90 仍在 knee 之下、永远吃不满；而树叶离 sRGB 色域上限只到 25%
    //      （罐体 51%），说明不是没空间。改相对判据后树叶 5.05%→**12.50%**、
    //      罐体 13.70%→**22.11%**，两者差距由 2.7× 收窄到 1.8×（darktable vibrance
    //      同样按当前饱和度归一）。噪声安全：暗部**中性**像素 rel 很小 -> 增益仅 0.06%，
    //      即相对判据**天然保护近中性色**，不必再靠 knee 承担该职责
    //      （v0.6.14「全局 ×1.5 把白平衡残余放大」的偏色事故不会因此重演）。
    //      强度仍是标定旋钮：设备端过浓/过淡调 uLookChroma（1.25 = 满额 +25%）。
    // v0.6.23：knee 1.0 → 0.4。真机三场景 + DNG 对照 libraw 标准链逐像素配对后发现
    //      **缺口剖面是"低饱和重、高饱和轻"**（rel 0.15–0.25 段我们只有参照的 0.59，
    //      而 rel 0.7–1.2 段已到 1.22 —— 即高饱和端已经够了、淡的是**中间调弱彩**）。
    //      knee=1.0 时低饱和像素落在 smoothstep 的 t² 慢段只拿到 1.5–2.6% 增益；
    //      降到 0.4 把这一段推上 ramp，增益剖面变为：rel0.15→+9%、0.2→+14%、0.3→+23%、
    //      0.5→+20%、0.8→+10%、1.2→+8% —— **低饱和段被加权最多**，正是缺口所在；
    //      近中性仍只 +0.3%~+3.6%（v0.6.14 偏色事故是 +50%），保护未失。
    //      注意：**不追求对齐 libraw 的低饱和 C\**（其 DNG 无 ForwardMatrix，libraw 只能
    //      退回 ColorMatrix1/2，而我们走的是 CameraCharacteristics 的 ForwardMatrix，
    //      与 CameraPresets.kt 既有结论一致：libraw 的 C\* 是它的偏好不是"正确"）。
    //      uLookChroma 同步 1.25→1.35（CameraPresets）：低饱和要补得多、高饱和补得少。
    //      强度仍是标定旋钮：设备端过浓/过淡调 uLookChroma。
    // uLook ∈ {0,1} 是 A/B 通路（v0.5.36 教训：影调/色彩类改动必须能关掉）。
    if (uLook > 0.0) {
        const float kRelKnee = 0.4;    // 相对饱和 knee（设备端标定旋钮）
        const float kLumaFloor = 0.02; // 防除零，并防暗部噪声被相对判据放大
        float la = luma(out3);
        vec3 cc = out3 - la;
        float dev = max(max(abs(cc.r), abs(cc.g)), abs(cc.b));
        float t = clamp(dev / (kRelKnee * max(la, kLumaFloor)), 0.0, 1.0);
        float sk = t * t * (3.0 - 2.0 * t);
        out3 = la + cc * (1.0 + (uLookSat - 1.0) * uLook)
                       * (1.0 + (uLookChroma - 1.0) * sk * uLook);
    }
    // U-1 [25] mono 与 split 一并挪到 kFsOut 的显示域段（同域同式，理由见上方 uSat 段注释）。
    oColor = vec4(out3, 1.0);
})";

// ── 引擎插槽：kFsFilm = 公共模板三段 + 引擎注入段（docs/engine-slots-design.md §3.1）──
// 阶段 1（2026-10-13）：初始化期按 `head + engine0_def + mid + engine0_main + tail`
// 拼接后编译 —— **仍是同一个 pass**（不新增纹理 / draw call / uniform 布局，运行期零
// 额外开销）。拼接产物与原 `kFsFilm` **逐字节相同**是阶段 1 的唯一硬门槛（方案 §8）。
//
// ★ 为什么是「单真源 + 切片」，而不是把 496 行物理拆成三段常量 ★
//   物理拆分 = 35154 字节的 GLSL 在源码里出现 3 处**人为接缝**：任何一处接缝多/少一个
//   换行或缩进，拼接结果就不再与改造前的 `kFsFilm` 逐字节相同 —— 而"是否相同"只能靠
//   人工核对，接缝一旦漂移，真机表现是"画面悄悄变了"而不是编译错误。
//   切片方式下五个片段是**同一字面量的连续区间划分**（[0,A) [A,B) [B,C) [C,D) [D,end)），
//   拼接恒等于原文 ⇒ 等价性**由构造保证**，拼接后的 GLSL 与改造前**逐字符相同**。
//   **请勿改成物理拆分**（架构师 2026-10-13 决定）。
//
// 切分点用**行首原文 `find` 定位**（字节偏移），不用行号计数：行号差一行是静默错误，
// 且上游改注释即失效；锚点原文变了则 `find` 失败，走下面的显式失败路径（不静默）。
//   A = 引擎 0 函数定义段起点（filmic spline + darktable filmic v5 gamut 全部函数）
//   B = 公共中段起点（`void main()` 开头：tex 读取 / 曝光 / 域映射 / 局部色调映射）
//   C = 引擎 0 main 调用段起点（`if (uToneK > 0.0) {`）
//   D = 该调用段之后（收尾 `    }` 的下一个字节）。`    }` 全文出现多次，单独 find 会
//       命中更早的同形行 ⇒ D 由「C 起 + 含主导语句的整块原文」定位。
namespace {

constexpr std::string_view kFilmSplitDefAnchor =
    "// ── filmic 基准影调（v0.7.3）：darktable v3 spline 取代 Reinhard ──";
constexpr std::string_view kFilmSplitMidAnchor = "void main() {";
constexpr std::string_view kFilmSplitMainAnchor = "    if (uToneK > 0.0) {";
constexpr std::string_view kFilmSplitMainBlockEnd = "out3 = mix(out3, toned, uToneK);\n    }";

// `kFsFilm` 的五个切片视图（全部指向同一字面量 ⇒ 零拷贝，不可能与真源漂移）。
struct FilmSlices {
  bool ok = false;               // 四锚点齐全且严格递增；false ⇒ 见 filmSource 的失败策略
  std::string_view head;         // 公共前段：precision / IO / 公共 uniforms / 公共函数
  std::string_view engine_def;   // 引擎 0 注入①：算法函数（filmic + gamut）
  std::string_view mid;          // 公共中段：main 前段（曝光 / 域映射 / 局部色调映射）
  std::string_view engine_main;  // 引擎 0 注入②：main 调用段
  std::string_view tail;         // 公共后段：外观层 sat/split/look/mono + 输出
};

const FilmSlices& filmSlices() {
  static const FilmSlices s = [] {
    FilmSlices r;
    const std::string_view full{kFsFilm};
    const size_t a = full.find(kFilmSplitDefAnchor);
    const size_t b = (a == std::string_view::npos) ? a : full.find(kFilmSplitMidAnchor, a);
    const size_t c = (b == std::string_view::npos) ? b : full.find(kFilmSplitMainAnchor, b);
    const size_t e = (c == std::string_view::npos) ? c : full.find(kFilmSplitMainBlockEnd, c);
    if (a == std::string_view::npos || b == std::string_view::npos ||
        c == std::string_view::npos || e == std::string_view::npos ||
        !(a < b && b < c && c < e)) {
      // 锚点缺失/错序 = 模板结构被改坏（有人重写了 kFsFilm 的注释或结构）。
      // 此处只记原因；filmSource 会退回整体字面量（= 改造前行为，不崩、不静默）。
      Logger::instance().log(LogLevel::kError, "engine", "engine.film_split_missing",
                             "{\"note\":\"kFsFilm split anchors not found\"}");
      return r;
    }
    const size_t d = e + kFilmSplitMainBlockEnd.size();
    r.head = full.substr(0, a);
    r.engine_def = full.substr(a, b - a);
    r.mid = full.substr(b, c - b);
    r.engine_main = full.substr(c, d - c);
    r.tail = full.substr(d);
    r.ok = true;
    return r;
  }();
  return s;
}

// 公共模板三段（= `kFsFilm` 的切片，即任务卡的 `kFilmTemplateHead/Mid/Tail`）。
// 阶段 2 起：注册表为选中的引擎提供 uniforms / profile / functions 注入段，插在
// `engine_def` 之后；三段模板本身不变。
const std::string_view kFilmTemplateHead = filmSlices().head;
const std::string_view kFilmTemplateMid = filmSlices().mid;
const std::string_view kFilmTemplateTail = filmSlices().tail;

// engine_slot 无对应引擎时的**明确降级**告警（阶段 2 起注册表里有 2 个引擎）。
// 方案 §4 契约 5：不静默、不崩、不做 identity 伪装 —— 降级到引擎 0 并留下日志。
// 去重：预览 30–60 fps 下每帧一条会刷爆日志环形缓冲（500 行）⇒ 只在取值变化时记。
void warnEngineSlotUnavailable(int slot) {
  static std::atomic<int> last{0};  // 0 = 尚无告警（0 本身是合法取值，不会进来）
  if (slot == 0 || last.exchange(slot) == slot) return;
  Logger::instance().log(LogLevel::kWarn, "engine", "engine.slot_unavailable",
                         "{\"requested\":" + std::to_string(slot) +
                             ",\"fallback\":0,\"stage\":2}");
}

// 按引擎插槽组装 film pass 的 fragment shader 源码（方案 §3.1）：
//   src = head + uniforms + profile + functions + mid + main + tail
// 阶段 2 起**注册表驱动**：4 个注入段取自 `EngineEntry`，`nullptr` 语义 = "用 `kFsFilm`
// 的对应切片"（引擎 0 四项全 nullptr ⇒ 拼回原文，逐字节等价不许破）。
// slot 未命中 ⇒ 按契约 5 降级到引擎 0 并记 WARN（不静默、不崩、不 identity 伪装）。
//
// ⚠ 类型口径（架构师 2026-10-13 特别标注）：`kFsFilm` 的切片是 `std::string_view`
//   （**不是** null-terminated），而表项里的是 `const char*` 字面量。两者统一成
//   `std::string_view` 再组装 —— 不能把 `const char*` 当切片用（`strlen` 会越过切片
//   边界），也不能把切片当 `const char*` 用。
std::string filmSource(int engine_slot) {
  const FilmSlices& s = filmSlices();
  if (!s.ok) {
    // 切分失败（锚点缺失）= 模板结构坏了。**不做 identity 伪装**：退回改造前的整体
    // 字面量（行为与未接插槽一致），原因已在 filmSlices() 里记为 kError。
    return std::string(kFsFilm);
  }
  const EngineEntry* e = findEngine(engine_slot);
  if (e == nullptr) {
    warnEngineSlotUnavailable(engine_slot);
    e = findEngine(0);  // 降级：注册表里必有引擎 0
    if (e == nullptr) return std::string(kFsFilm);  // 表被改坏：退回整体字面量
  }
  // 注入段：`nullptr` ⇒ 用 `kFsFilm` 的切片（引擎 0 的两个注入段就是这么来的）。
  const std::string_view uni =
      e->uniforms_src ? std::string_view{e->uniforms_src} : std::string_view{};
  const std::string_view prof =
      e->profile_src ? std::string_view{e->profile_src} : std::string_view{};
  const std::string_view func =
      e->functions_src ? std::string_view{e->functions_src} : s.engine_def;
  const std::string_view main =
      e->main_src ? std::string_view{e->main_src} : s.engine_main;
  std::string src;
  src.reserve(kFilmTemplateHead.size() + uni.size() + prof.size() + func.size() +
              kFilmTemplateMid.size() + main.size() + kFilmTemplateTail.size());
  src += kFilmTemplateHead;   // 公共前段
  src += uni;                 // 引擎注入①：私有 uniforms
  src += prof;                // 引擎注入②：profile / 资产声明
  src += func;                // 引擎注入③：算法函数（含 gamut）
  src += kFilmTemplateMid;    // 公共中段
  src += main;                // 引擎注入④：main 调用段
  src += kFilmTemplateTail;   // 公共后段
  return src;
}

// CRC32（IEEE 802.3 口径 = Python `zlib.crc32`：初值 ^0xFFFFFFFF、反射多项式
// 0xEDB88320、末取反）—— 只为自检口径与离线链对齐，故不为此引入 zlib 依赖。
uint32_t crc32Ieee(std::string_view s) {
  static const std::array<uint32_t, 256> kTable = [] {
    std::array<uint32_t, 256> t{};
    for (uint32_t i = 0; i < 256; ++i) {
      uint32_t c = i;
      for (int k = 0; k < 8; ++k) c = (c & 1u) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
      t[i] = c;
    }
    return t;
  }();
  uint32_t c = 0xFFFFFFFFu;
  for (const unsigned char ch : s) c = kTable[(c ^ ch) & 0xFFu] ^ (c >> 8);
  return c ^ 0xFFFFFFFFu;
}

// 开发期自检（**只在引擎编译期调用一次**，不在每帧路径上）：拼接结果 vs `kFsFilm`
// 定界符内的原文。`chars` = UTF-8 码位数，与 `bytes` 并列打印：这两个口径
// （25140 / 35154）在本方案里被混用过，并列可立刻发现口径错误。
//
// ⚠ **逐字节判据只对 slot == 0 生效**（阶段 1 的零回归硬门槛 = 引擎 0 的拼接结果与原
//   `kFsFilm` **逐字节相同**）。引擎 1 的拼接结果**必然**不等于 `kFsFilm` —— 它本来就
//   该是另一份 shader ⇒ slot != 0 记 kInfo，`zero_regression` 写明确的
//   `n/a (engine N)`，同时照常打印 crc32 / bytes / chars 供真机核对"引擎 1 的源码确实
//   变了"。**不许**把引擎 1 的正常状态报成 kError：那是一条假 error，会污染真机
//   "零回归"判据（任务书第 8 条）。
void logFilmSourceSelfCheck(const std::string& src, int engine_slot) {
  const std::string_view full{kFsFilm};
  const bool identical = std::string_view(src) == full;
  const bool engine0 = (engine_slot == 0);
  size_t chars = 0;
  for (const unsigned char ch : src) {
    if ((ch & 0xC0u) != 0x80u) ++chars;  // 非 UTF-8 续接字节 = 一个码位
  }
  char crc_hex[16];
  std::snprintf(crc_hex, sizeof(crc_hex), "0x%08x", crc32Ieee(src));
  const EngineEntry* e = findEngine(engine_slot);
  const std::string zero_regression =
      engine0 ? (identical ? "ok" : "FAILED (stage-1 criterion)")
              : "n/a (engine " + std::to_string(engine_slot) + ")";
  Logger::instance().log(
      (engine0 && !identical) ? LogLevel::kError : LogLevel::kInfo, "engine",
      "engine.film_src",
      "{\"slot\":" + std::to_string(engine_slot) + ",\"engine\":\"" +
          (e != nullptr ? e->name : "unregistered") +
          "\",\"bytes\":" + std::to_string(src.size()) +
          ",\"chars\":" + std::to_string(chars) + ",\"crc32\":\"" + crc_hex +
          "\",\"identical\":" + (identical ? "true" : "false") + ",\"zero_regression\":\"" +
          zero_regression + "\"}");
}

}  // namespace

// ⚠ 引擎 1（sigmoid）的 GLSL 注入段**不在这里**：它们定义在本文件后部的
//   `kFsSigmoidDef` / `kFsSigmoidMain`（`}  // namespace` 之后 = `filmkit` 作用域）。
//   为什么不能放在本匿名 namespace 内：注册表 `film/EngineRegistry.cpp` 的表项要按名字
//   **链接引用**它们，而匿名 namespace 里的名字是**内部链接** ⇒ 拿不到外部符号，链接期
//   undefined symbol（实测：放这里时 `nm FrameRenderer.o` 查不到这两个符号）。

// Pass3：输出变换（OETF + 颗粒 + dither），量化到 8bit
const char* kFsOut = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform vec2 uPx;
// 交付位深上限（dither 除数）：由 CPU 按交付档下发 —— 8bit JPEG 链 = 255.0（默认，
// 由 passOutput 显式下发）、10bit HEIF 链 = 1023.0。见下方 dither 行的兜底说明。
uniform float uOutMax;
// ── E4-3 颗粒（granularity；darktable src/iop/grain.c）────────────────────
// 域常数在 CPU 侧算好（shader 内不做 W/H 除法与 clamp，与 E4-2 暗角同款）：
//   uGrainZoom = grainZoom([27])、uGrainNorm = 1/min(W,H)、uGrainSeed = 噪声坐标的
//   **相对** x 偏移（成片按内容派生；预览/编辑器恒 0 ⇒ 取景时图案稳定不闪）。
uniform float uGrainAmount;  // [26] 0..100（strength = /100；0 ⇒ 整段跳过，零成本）
uniform float uGrainSeed;
uniform float uGrainZoom;
uniform float uGrainNorm;
uniform highp sampler2D uGrainLut;  // 128² R16F 相纸响应 LUT（CPU 侧按 [60][61] 重算）
uniform float uFlipY;  // 拍照读回 GL 底行在前，翻转后 JPEG 方向正确
// ── U-4 清晰度 + 影调分区 + 褪色（display 域 = 本 shader 的 srgbEncode **之后**）──
// 为什么是"照抄 kFsStyle"：这四项在两条链路上**同域**（编辑器链路的输入本就是已成片
// sRGB 显示域；本 shader 的 srgbEncode 之后也是）⇒ 系数、权重定义、区间划分、先后序
// 全部逐字照抄 kFsStyle 的 ①④ 段（U-1 起的两侧对齐就用这个手法，见其 chainLuma 注释）。
// ⚠️ 落点绝不能在 srgbEncode **之前**：那里是线性显示域，同一物理亮度下 luma 差 2.5 倍
//   （中灰 0.18 → 线性 0.18 / 显示 0.46）⇒ 分区权重曲线形状完全不同（U-1 的 split 段
//   报告里有实测对照）。
// 门控（默认值 ⇒ 整段跳过，零成本、逐位零回归）：clarity 与 fade 在 shader 内
// （`> 0.0`）、tone_on 由 CPU 侧算（四项任一非 0）—— 与 kFsStyle 逐字同判据。
uniform float uClarity;    // [78] 清晰度（显示域局部对比；0 = 关）
uniform vec2 uClarStep;    // 清晰度环半径（UV 单位）= r_px/(W,H)
uniform float uHighlights;   // [90] 高光提亮/压暗（0 = 不动）
uniform float uShadows;      // [91] 阴影提亮/压暗（0 = 不动）
uniform float uWhites;       // [92] 白色色阶（0 = 不动）
uniform float uBlacks;       // [93] 黑色色阶（0 = 不动）
uniform float uFade;         // [94] 褪色（抬起黑场；0 = 关）
uniform float uToneOn;       // 门控（CPU 侧算）：[90..93] 任一非 0
uniform float uZebra;  // 过曝提示阈值（显示域，0 = 关；仅预览路径传非 0）
// U-1 [23][24] 分离色调 + [25] 单色 —— 期 B 从 kFsFilm 挪来（那边是线性工作空间域、
// 与编辑器差 43%）；本 shader 的这几段都在 OETF **之后**的显示域 ⇒ 与 kFsStyle 同域。
// ⚠ 刻意放在 U-4 的 uniform 段（uClarity..uToneOn）**之外**：check-u4 判据①以"机械删除
// U-4 三段"构造对照 shader，删除范围的终点哨兵是 `uZebra` —— 插在它之前会被一起删掉，
// 于是对照 shader 里 U-1 段引用到未声明的 uniform，直接编译失败。
uniform vec2 uSplit;         // [23] split_warm / [24] split_cool（0 = 不动）
uniform float uMono;         // [25] 单色（0 = 关）
// 期 B：引擎 1 的对比度（`[4]`）。引擎 0 的 `[4]` 在 updateFilmicEV 里折算成 filmic 曲线
// 系数（引擎 1 的 program 里根本不存在那些 uniform）⇒ 引擎 1 此前**没有对比度实现**，
// 滑杆完全无效（用户实测反馈）。本 uniform 由 CPU 侧按引擎下发：
// 引擎 1 = `p.contrast`、引擎 0 = **1.0**（恒等 ⇒ 引擎 0 零回归，它的对比度已由曲线系数承担）。
uniform float uContrastOut;
uniform float uSharpen;  // v0.9.6 轻度锐化强度（0 = 关，A/B 通路；FilmParams[39]）
uniform float uRollOff;  // 0.12.166：高光滚降开关（1 = 开，0 = 关；FilmParams[99]）
// ── M4 3D LUT（display 域）────────────────────────────────────────────
// 域感知插桩：display 域 LUT 必须接在输出 OETF 之后（§0-D10 / §6.1）。
// 采样用四面体插值而非三线性 —— 三线性在 33³ 下有可见色差（§3.3-5）。
uniform highp sampler3D uLut;
uniform float uLutOn;        // 0 = 旁路（未选 LUT / 强度 0）
uniform float uLutStrength;  // 0..1 混合强度
uniform vec3 uLutDomainMin;  // .cube DOMAIN_MIN
uniform vec3 uLutDomainInv;  // 1/(DOMAIN_MAX - DOMAIN_MIN)
uniform float uLutSize;      // 3D 边长 N
// ── E4-2 暗角（vignette；darktable vignette.c 正圆模型，sRGB 显示域乘法）────
// 域常数在 CPU 侧算好（shader 内不做 W/H 除法）：pv 尺度 = (W,H)/max(W,H)。
// [66] ≥ 0（默认 0）⇒ uVigBrightness = 0 ⇒ 整段跳过（A/B 开关 = [66]，零成本）。
uniform vec2 uVigScaleXY;
uniform float uVigDscale;
uniform float uVigFscale;
uniform float uVigBrightness;
// ── SG-1 柔光（soft glow；darktable src/iop/bloom.c 的 Orton 支）──────────────
// 落点 = **本 shader 的显示域段**、褪色之后 / 暗角之前（规格 §2 的顺序：
// 影调分区/褪色 → 柔光 → 暗角 → 颗粒 → LUT → dither）：
//   · 在暗角之前 —— 柔光的高光外扩不该被暗角再次衰减（规格 §4）；
//   · 在颗粒之前 —— 颗粒是胶片纹理，叠在含柔光的画面上（规格 §4）。
// uGlow = 1/2 分辨率灰度柔光图（共享金字塔的加权和，**线性显示域**；由 passStyle/
// passOutput 在本 shader **之前**跑出来）。uGlowOn 由 CPU 侧算（[95] > 0 且链成功）
// ⇒ 默认（[95] = 0）时本段整段跳过（零成本、逐位零回归）。
uniform highp sampler2D uGlow;
uniform float uGlowOn;
uniform float uGlowStrength;  // [95] 0..1
uniform vec2 uGlowTexel;      // 1/glowW, 1/glowH（CPU 侧算好，shader 内不做除法）
vec3 lutTetra(vec3 f, vec3 c000, vec3 c100, vec3 c010, vec3 c001, vec3 c110,
              vec3 c101, vec3 c011, vec3 c111) {
    if (f.x >= f.y) {
        if (f.y >= f.z) return (1.0 - f.x) * c000 + (f.x - f.y) * c100 + (f.y - f.z) * c110 + f.z * c111;
        if (f.x >= f.z) return (1.0 - f.x) * c000 + (f.x - f.z) * c100 + (f.z - f.y) * c101 + f.y * c111;
        return (1.0 - f.z) * c000 + (f.z - f.x) * c001 + (f.x - f.y) * c101 + f.y * c111;
    }
    if (f.z >= f.y) return (1.0 - f.z) * c000 + (f.z - f.y) * c001 + (f.y - f.x) * c011 + f.x * c111;
    if (f.z >= f.x) return (1.0 - f.y) * c000 + (f.y - f.z) * c010 + (f.z - f.x) * c011 + f.x * c111;
    return (1.0 - f.y) * c000 + (f.y - f.x) * c010 + (f.x - f.z) * c110 + f.z * c111;
}
vec3 lutApply(vec3 c) {
    vec3 t = clamp((c - uLutDomainMin) * uLutDomainInv, 0.0, 1.0) * (uLutSize - 1.0);
    vec3 i0 = floor(t);
    vec3 f = t - i0;
    ivec3 i = ivec3(i0);
    ivec3 mx = ivec3(int(uLutSize) - 1);
    vec3 c000 = texelFetch(uLut, clamp(i, ivec3(0), mx), 0).rgb;
    vec3 c100 = texelFetch(uLut, clamp(i + ivec3(1, 0, 0), ivec3(0), mx), 0).rgb;
    vec3 c010 = texelFetch(uLut, clamp(i + ivec3(0, 1, 0), ivec3(0), mx), 0).rgb;
    vec3 c001 = texelFetch(uLut, clamp(i + ivec3(0, 0, 1), ivec3(0), mx), 0).rgb;
    vec3 c110 = texelFetch(uLut, clamp(i + ivec3(1, 1, 0), ivec3(0), mx), 0).rgb;
    vec3 c101 = texelFetch(uLut, clamp(i + ivec3(1, 0, 1), ivec3(0), mx), 0).rgb;
    vec3 c011 = texelFetch(uLut, clamp(i + ivec3(0, 1, 1), ivec3(0), mx), 0).rgb;
    vec3 c111 = texelFetch(uLut, clamp(i + ivec3(1, 1, 1), ivec3(0), mx), 0).rgb;
    return lutTetra(f, c000, c100, c010, c001, c110, c101, c011, c111);
}
// v0.10.67 dither 专用 hash：sin 版（`fract(sin(dot(p,…)))`）在 gl_FragCoord 大坐标
//（3072×4080）下 highp sin 精度不足，会产生**规律性图案** —— dither 一旦有规律就等于
// 没有。Dave Hoskins 的无 sin 整数风格 hash。E4-3：原 sin 版 hash 与其上的单八度
// value noise 只服务颗粒，已整体替换为下方 3 八度 simplex ⇒ 颗粒侧同型坑一并消除。
float dhash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
// ── E4-3 颗粒（granularity；darktable src/iop/grain.c 逐行移植）────────────
// ① 噪声源 = **3 八度 3D simplex**（Gustavson 的 permutation[256] + grad3[12] 整数表，
//    输出归一化 32·(n0+n1+n2+n3)），z 轴传八度序号 ⇒ 三阶在 3D 空间互不相关。
//    频率 f={0.4910,0.9441,1.7280} / 幅度 a={0.2340,0.7850,1.2150} 取 darktable 常量
//    （其注释：匹配真实颗粒扫描的功率谱）。旧的单八度 value noise（sin-hash +
//    smoothstep）频谱低通、格子边界 C1 不连续 ⇒ 观感是"块状糊"而不是"颗粒"。
// ② **严禁 sin-hash**：大坐标下 highp sin 精度不足会产生规律图案（本项目在 dither 上
//    踩过同型坑，见 dhash 注释）⇒ 用整数 permutation 表（GLSL ES 3.0 支持数组初始化
//    器与常量数组索引，同文件 kFsChromaDown 的 B3[5]/B3[i] 即先例）。
// ③ 尺度按**短边归一化**（ADR-11）：噪声坐标 = 像素/短边 ⇒ 预览（降采样）与导出
//    （全分辨率）的颗粒相对空间频率一致，与像素数无关。zoom 越大 ⇒ 频率越低 ⇒ 越粗。
// ④ 密度调制 = 相纸响应 LUT（CPU 侧预计算 128²，x 轴 = 噪声·strength·0.15，
//    y 轴 = 显示域 luma ≈ darktable 的 Lab L/100）⇒ shader 内**零** exp/log。
// 采样用 texelFetch 手写双线性而非 texture()：Mali 上 16F 的硬件 LINEAR 不可靠
//（项目多处实测，见 makeTex / LSC / kFsRcdChroma 注释）⇒ 依赖它会在 128 级台阶上
// 静默退化成最近邻。
const int kGrainPerm[256] = int[256](
    151, 160, 137, 91, 90, 15, 131, 13, 201, 95, 96, 53, 194, 233, 7, 225, 140, 36, 103, 30,
    69, 142, 8, 99, 37, 240, 21, 10, 23, 190, 6, 148, 247, 120, 234, 75, 0, 26, 197, 62,
    94, 252, 219, 203, 117, 35, 11, 32, 57, 177, 33, 88, 237, 149, 56, 87, 174, 20, 125, 136,
    171, 168, 68, 175, 74, 165, 71, 134, 139, 48, 27, 166, 77, 146, 158, 231, 83, 111, 229, 122,
    60, 211, 133, 230, 220, 105, 92, 41, 55, 46, 245, 40, 244, 102, 143, 54, 65, 25, 63, 161,
    1, 216, 80, 73, 209, 76, 132, 187, 208, 89, 18, 169, 200, 196, 135, 130, 116, 188, 159, 86,
    164, 100, 109, 198, 173, 186, 3, 64, 52, 217, 226, 250, 124, 123, 5, 202, 38, 147, 118, 126,
    255, 82, 85, 212, 207, 206, 59, 227, 47, 16, 58, 17, 182, 189, 28, 42, 223, 183, 170, 213,
    119, 248, 152, 2, 44, 154, 163, 70, 221, 153, 101, 155, 167, 43, 172, 9, 129, 22, 39, 253,
    19, 98, 108, 110, 79, 113, 224, 232, 178, 185, 112, 104, 218, 246, 97, 228, 251, 34, 242, 193,
    238, 210, 144, 12, 191, 179, 162, 241, 81, 51, 145, 235, 249, 14, 239, 107, 49, 192, 214, 31,
    181, 199, 106, 157, 184, 84, 204, 176, 115, 121, 50, 45, 127, 4, 150, 254, 138, 236, 205, 93,
    222, 114, 67, 29, 24, 72, 243, 141, 128, 195, 78, 66, 215, 61, 156, 180);
// darktable 的 perm[512]（= permutation[i & 255] 查表）等价形式 —— 表本身仍是 256 项。
int grainPerm(int i) { return kGrainPerm[i & 255]; }
// darktable `grad3[12][3]` 逐项照抄（12 个"面心"梯度）。
const vec3 kGrainGrad3[12] = vec3[12](
    vec3(1.0, 1.0, 0.0), vec3(-1.0, 1.0, 0.0), vec3(1.0, -1.0, 0.0), vec3(-1.0, -1.0, 0.0),
    vec3(1.0, 0.0, 1.0), vec3(-1.0, 0.0, 1.0), vec3(1.0, 0.0, -1.0), vec3(-1.0, 0.0, -1.0),
    vec3(0.0, 1.0, 1.0), vec3(0.0, -1.0, 1.0), vec3(0.0, 1.0, -1.0), vec3(0.0, -1.0, -1.0));
// darktable `_simplex_noise`（Perlin 1998 / Gustavson 型）。六路 if-else 顶点次序逐字照抄；
// floor() 对应其 FASTFLOOR（两者仅在**精确整数**输入上不同，噪声坐标不可能命中）。
float grainSimplex3(vec3 v) {
    const float F3 = 1.0 / 3.0;
    const float G3 = 1.0 / 6.0;
    float s = (v.x + v.y + v.z) * F3;
    vec3 ip = floor(v + vec3(s));
    float t = (ip.x + ip.y + ip.z) * G3;
    vec3 x0 = v - ip + vec3(t);
    int i1, j1, k1, i2, j2, k2;
    if (x0.x >= x0.y) {
        if (x0.y >= x0.z)      { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 1; k2 = 0; }
        else if (x0.x >= x0.z) { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 0; k2 = 1; }
        else                   { i1 = 0; j1 = 0; k1 = 1; i2 = 1; j2 = 0; k2 = 1; }
    } else {
        if (x0.y < x0.z)       { i1 = 0; j1 = 0; k1 = 1; i2 = 0; j2 = 1; k2 = 1; }
        else if (x0.x < x0.z)  { i1 = 0; j1 = 1; k1 = 0; i2 = 0; j2 = 1; k2 = 1; }
        else                   { i1 = 0; j1 = 1; k1 = 0; i2 = 1; j2 = 1; k2 = 0; }
    }
    vec3 x1 = x0 - vec3(float(i1), float(j1), float(k1)) + G3;
    vec3 x2 = x0 - vec3(float(i2), float(j2), float(k2)) + 2.0 * G3;
    vec3 x3 = x0 - 1.0 + 3.0 * G3;
    ivec3 ic = ivec3(ip) & 255;
    int gi0 = grainPerm(ic.x + grainPerm(ic.y + grainPerm(ic.z))) % 12;
    int gi1 = grainPerm(ic.x + i1 + grainPerm(ic.y + j1 + grainPerm(ic.z + k1))) % 12;
    int gi2 = grainPerm(ic.x + i2 + grainPerm(ic.y + j2 + grainPerm(ic.z + k2))) % 12;
    int gi3 = grainPerm(ic.x + 1 + grainPerm(ic.y + 1 + grainPerm(ic.z + 1))) % 12;
    float n0 = 0.0, n1 = 0.0, n2 = 0.0, n3 = 0.0;
    float t0 = 0.6 - dot(x0, x0);
    if (t0 > 0.0) { t0 *= t0; n0 = t0 * t0 * dot(kGrainGrad3[gi0], x0); }
    float t1 = 0.6 - dot(x1, x1);
    if (t1 > 0.0) { t1 *= t1; n1 = t1 * t1 * dot(kGrainGrad3[gi1], x1); }
    float t2 = 0.6 - dot(x2, x2);
    if (t2 > 0.0) { t2 *= t2; n2 = t2 * t2 * dot(kGrainGrad3[gi2], x2); }
    float t3 = 0.6 - dot(x3, x3);
    if (t3 > 0.0) { t3 *= t3; n3 = t3 * t3 * dot(kGrainGrad3[gi3], x3); }
    return 32.0 * (n0 + n1 + n2 + n3);
}
// darktable `_simplex_2d_noise`：f[]/a[] 逐一内联展开（**无数组、无循环** —— GLSL 三禁）。
// z = zoom，三个八度各占 z 轴的一个整数标签 ⇒ 互不相关。
float grainNoise(vec2 p, float z) {
    return grainSimplex3(vec3(p.x * 0.4910 / z, p.y * 0.4910 / z, 0.0)) * 0.2340
         + grainSimplex3(vec3(p.x * 0.9441 / z, p.y * 0.9441 / z, 1.0)) * 0.7850
         + grainSimplex3(vec3(p.x * 1.7280 / z, p.y * 1.7280 / z, 2.0)) * 1.2150;
}
// darktable `dt_lut_lookup_2d_1c` 逐字移植：x 轴 ±0.5 偏移、两端 clamp 到 SIZE-1、
// 索引上界 SIZE-2（末格靠 x_diff/y_diff = 1 外推）。
float grainLutFetch(vec2 s) {
    vec2 t = clamp(vec2((s.x + 0.5) * 127.0, s.y * 127.0), 0.0, 127.0);
    ivec2 i0 = min(ivec2(t), ivec2(126));
    vec2 f = t - vec2(i0);
    float l00 = texelFetch(uGrainLut, i0, 0).r;
    float l01 = texelFetch(uGrainLut, i0 + ivec2(1, 0), 0).r;
    float l10 = texelFetch(uGrainLut, i0 + ivec2(0, 1), 0).r;
    float l11 = texelFetch(uGrainLut, i0 + ivec2(1, 1), 0).r;
    float xy0 = mix(l00, l10, f.y);
    float xy1 = mix(l01, l11, f.y);
    return mix(xy0, xy1, f.x);
}
// M4-6 高光滚降（线性域）：clamp 硬截断会把 >1 的高光直接拍成死白，而且**保留
// 色相**，使过曝的灯/窗偏红偏粉（v0.5.37 用户反馈过）。
// v0.6.19 两处修正（真机报"高光没细节"）：
//   ① **knee 提到与 updateAutoGain 的 kTarget 相同（0.80）** —— 之前 knee 0.75 < 目标白点
//      0.90，等于让"正常亮部（P95）"也落进压缩区；对齐后**只有真正超过目标白点的部分
//      才被压缩**，P95 以下的层次原样保留。
//   ② 曲线由 t/(1+t) 换成**指数软肩**：两者都满足 f(knee)=knee、f'(knee)=1、l→∞ 趋 1，
//      但指数版在 l=1.0 处斜率 0.37，t/(1+t) 只有 0.25（压缩 4 倍）—— 高光层次差一倍以上。
//      指数版同样单调无过冲（Hermite 保斜率曲线会过冲，故不采用）。
// ── v0.7.4：趋白终点 1.6 → **1.0**（纠正量纲错误，不是调参）────────────────────
// 1.6 是旧链（Reinhard + 场景增益 ag，输出可被推到 2+）的经验终点；**filmic 链下输出
// 被 clamp 进 [0,1]**，`smoothstep(0.80, 1.6, l)` 在 l 最多到 1.0 时只给出 **15.6%**
// 的趋白 —— 顶满的像素（过曝灯管）拿不到足够趋白，仍会偏品红。
// 1.0 是显示域的物理上限，不是可调参数：l 顶到 1.0 就必须全白。
vec3 highlightRolloff(vec3 c) {
    const float knee = 0.80;  // 软肩起点（线性显示域）；与 kTarget 无关（该量 v0.7.3 已废除）
    // ── 0.12.106：**趋白起点与软肩起点解耦**（0.80 → 0.95）───────────────────
    // 0.7.4 把趋白终点定成 1.0 是对的（显示域上限），但起点沿用了 knee(0.80)：
    // 于是 l∈[0.80,1.0] 这段**根本没超上限**的亮部被按 smoothstep 向白色混合，
    // l=0.85 白化 15.6%、l=0.90 白化 50%、l=0.95 白化 84% ⇒ 色度按同比例被抹掉。
    // 灰块证据（DNG + 引擎 diag.stats，同一张 143528）：
    //   · 灰块 RAW = (0.699, 0.444, 0.698)（品红，R/G=1.59），只有 0.8% 真饱和；
    //   · CCM 后线性 sRGB 的 C* 实测 ≈ 10（同 ACR/libraw 的 10.3，不是"从粉变灰"）；
    //   · 成片 C* = 1.1 —— 差的 9 个 C* 就落在这段白化上（高光反光的 l 正好 0.85~0.95）。
    // 等比压缩 `cc = c·(lc/l)` 本身已经保证不超上限且**严格保色相**，趋白只是给
    // "真顶到过曝"的像素（灯管/太阳）一个去色终点。把起点挪到 0.95 后：
    //   · l < 0.95：只压缩、保色相（灰块恢复本来该有的粉/紫）；
    //   · l ≥ 0.95：仍然趋白（0.95~1.0 内从 0 到 100%），过曝灯窗不会偏红偏粉。
    // ⚠ 0.12.107 回滚（2026-10-28 用户指出）：高光中心是**镜面反射 = 光源色（白）**，
    //   不是物体色。把趋白起点推后 = 让高光保留物体色（品红）= 造假色，方向错误。
    //   正确的物理与 0.12.106 相反：靠近上限的部分应当**更彻底地趋白**。
    //   本节保留 0.12.106 的数据结论（去色就在本函数），但参数回到 knee。
    // ── 0.12.111：趋白只在**超过显示上限**时发生（删掉与 filmic 重复的去色层）──
    // 之前 `smoothstep(knee, 1, l)` 把 l∈[0.80,1.0] 整段向白混合（0.85→16% /
    // 0.90→50% / 0.95→84%），而 filmic 的输出**本来就严格 ∈[0,1]**（v0.7.4 注释：
    // pow(clamp(spline, lo, 1.0), power)）—— 即这一段根本没有溢出，白化纯属多余，
    // 代价是把镜面高光**过渡带**（物体色 + 光源色的混合）一起洗成中性。
    // 真机 diag.stats（15:45:29）逐段：D4 10.14 → PC3D 10.15 → DEFRINGE 10.15 →
    // FILM-out 22.74 → **D5 0.027**；本 shader 内其余显示域段日志全 0、锐化是乘性
    // 不改色比、OETF 逐通道单调不改色比 ⇒ 抹色只能来自这一句 mix。
    // 对照 PC / darktable 的做法（这才是正解，不是调参）：
    //   · PC `RawToneMappingGl`：`whiteDisplay = 1f`，白点**直接映射到 1.0**，
    //     曲线五条约束里 f(T)=1 且 f'(T)=0（末端 rolloff 在曲线内部完成）；
    //   · darktable filmicrgb v5 同理，输出域只做 gamut mapping（gamutMapV5：
    //     色度 min → 色相锁定 → Y clamp → desaturate(0) → Yrg/RGB 检查），
    //     **没有任何"mix 到白"**。
    //   ⇒ filmic 之后不需要第二层去色。这里保留的趋白只剩物理语义：
    //     l 真的超过 1.0（理论上不该发生）时才向白收敛，正常高光一律只做等比压缩。
    //   若真机出现"过曝灯/窗偏红粉"（per-channel 曲线的固有现象），正确旋钮是
    //   **打开 gamut mapping**（FilmParams[40]，darktable 原版色域压缩），而不是
    //   在这里洗白 —— 后者会把过渡带的色相一起抹掉。
    // ── 0.12.141：white0 1.00 → **0.88** ────────────────────────────────────
    // 0.12.111 把趋白起点定在 1.0 的前提是"filmic 输出严格 ∈[0,1] ⇒ 只有真超上限
    // 才会溢出"。但显示域里 0.88~1.0 这一段**已经是视觉上的白**（sRGB 0.94 以上
    // 肉眼几乎分不出色相），让这一段保留色度正是"高光周围一圈彩色雾"的来源：
    // 镜面光晕在显示域 luma ≈ 0.93，落在 1.0 以下 ⇒ whiteW=0 ⇒ 粉/绿原样进成片。
    // ACR 的做法是白点直接映射到 1.0（曲线内部 rolloff），等价于这一段就该趋白。
    // 只动 l > 0.88：灰块（显示域 0.36）、粉底（0.55）逐位不变 —— 0.12.106 的
    // "过渡带被洗白"教训不会重演（那时起点是 knee=0.80 且作用于**线性**亮部）。
    const float white0 = 0.88;
    float l = max(max(c.r, c.g), c.b);
    float whiteW = clamp((l - white0) * 12.5, 0.0, 1.0);  // 0.88→1.00 平滑到全白
    if (l <= knee) return c;
    float u = (l - knee) / (1.0 - knee);              // [0, ∞)
    float lc = knee + (1.0 - knee) * (1.0 - exp(-u)); // → [knee, 1)
    vec3 cc = c * (lc / l);                           // 等比压缩，保持色相
    // ── 0.12.146：**撤回** 0.12.144 的"高光区 B 封顶" ────────────────────────
    // 144 的依据只是一张截图（粉色椅背高光 B/G 1.02~1.03），我却把它当成
    // "所有高光场景都稳定偏高"。全库样片实测（14 张，luma>0.88 的像素）：
    //   B/G 中位 0.879 … 0.910 … 0.941 … 0.988 … **1.039 / 1.040**（跨度 18%）
    //   —— 大多数场景的高光是**暖白（B/G<1）**，只有粉色椅背那两张是青。
    // 也就是说"B 偏高"是场景特定的（粉色物体的冷调反光 + RCD 在饱和边缘的蓝
    // 通道偏置），不是普适规律。同样地 R/G 也在 0.92~1.09 之间跨 18%，方向不定。
    // ⇒ 任何"在显示域压某个通道"的规则都会在别的场景出错。这里撤回，回到
    //   "光晕色度由 RAW 域决定"的机制；真正的修法在 RCD 的饱和感知插值。
    return mix(cc, vec3(lc), whiteW);                 // 只有 l > 0.88 才趋白
}
// 期 B：本函数只服务下面的 sharpenLuma（**OETF 前的线性显示域** = 工作空间域）
// ⇒ 系数取 BT.2020 的 Y（原 0.2126/0.7152/0.0722 是 Rec.709/sRGB 原色的）。
// ⚠ 同 shader 内 U-4 的 tapLuma 用的是 **Rec.601**（那是 OETF **之后**的显示域；
//   两者故意不统一，理由见下方 tapLuma 注释）—— 不要把这两套混起来。
float lumaLin(vec3 c) { return dot(c, vec3(0.2627, 0.6780, 0.0593)); }
// 期 B：**显示域** luma（OETF 之后，Rec.709 系数）—— 与 kFsStyle 的同名函数**逐字相同**，
// 供 U-1（split/mono）逐字照抄比对用。两条链路的一致性靠"同一套数学"维持（项目既有做法）。
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
// ── v0.9.6 轻度锐化（luma USM，OETF 前线性显示域）─────────────────────────
// 管线定稿顺序（2026-09-27 决策）：filmic → EV → 高光滚降 → **锐化** → OETF。
// 为什么在 OETF 前（PC 的 sharpen 在 sRGB 显示域）：sRGB 的斜率在暗部 >1，
// 同一 USM 的 detail 被斜率放大，暗部噪声权重最高；线性域 detail 对应物理反差，
// 增益用"相对 luma"形式表达后暗部天然少拿增益。
// 为什么在滚降后：滚降先把高光压平，高反差亮边缘的 detail 随之变小 —— halo
// 天然更小；锐化若产生过冲，下一行的 pow 前值域已由滚降有界化。
// 核 = 3×3 的 1-2-1 外积高斯（中心 4/边 2/角 1，/16），步长恒 1 texel：
// 预览与成片同核（预览观感略强于成片，验收以成片为准）。邻域 tap **各自过
// highlightRolloff** 与中心同域 —— 直接用原始域邻域会与滚降后的中心混域，
// detail 在高光边缘系统性偏负（暗 halo）。
// 形式 = 乘法 luma USM：c *= 1 + gain，RGB 同乘一个标量 ⇒ CIE xy 色度坐标
// 不变（零色度污染，与 MFNR 每通道融合同一关注点）；÷l0 的相对形式近似
// 感知均匀（Weber ΔI/I，sRGB 域 USM 的观感等价物）。
// 标定旋钮：uSharpen 全局量（默认 0.5）；增益限幅 ±0.4 拦高反差边缘过冲与
// 暗部小 l0 处 ÷l0 的放大；luma 地板 0.01（显示域 ≈ 42/255）防除零。
// 8 邻手动展开 —— GLSL ES 不支持数组初始化器（desktop 4.2+ 特性，本仓库两次踩坑）。
void sharpenLuma(inout vec3 c, vec2 uv) {
    float l0 = lumaLin(c);
    float lb = 4.0 * l0;
    vec3 t = highlightRolloff(texture(uTex, uv + uPx * vec2(-1.0, 0.0)).rgb);
    lb += 2.0 * lumaLin(t);
    t = highlightRolloff(texture(uTex, uv + uPx * vec2(1.0, 0.0)).rgb);
    lb += 2.0 * lumaLin(t);
    t = highlightRolloff(texture(uTex, uv + uPx * vec2(0.0, -1.0)).rgb);
    lb += 2.0 * lumaLin(t);
    t = highlightRolloff(texture(uTex, uv + uPx * vec2(0.0, 1.0)).rgb);
    lb += 2.0 * lumaLin(t);
    t = highlightRolloff(texture(uTex, uv + uPx * vec2(-1.0, -1.0)).rgb);
    lb += lumaLin(t);
    t = highlightRolloff(texture(uTex, uv + uPx * vec2(1.0, -1.0)).rgb);
    lb += lumaLin(t);
    t = highlightRolloff(texture(uTex, uv + uPx * vec2(-1.0, 1.0)).rgb);
    lb += lumaLin(t);
    t = highlightRolloff(texture(uTex, uv + uPx * vec2(1.0, 1.0)).rgb);
    lb += lumaLin(t);
    float gain = uSharpen * (l0 - lb / 16.0) / max(l0, 0.01);
    // ── 0.12.163：限幅收紧 ±0.4 → ±0.12（边缘"描边/渗水"）───────────────────
    // 真机 2026-10-29 10:05（阳台地砖的护栏阴影，用户截图）：明暗交界处出现一条
    // 比两侧都亮/都浅的窄带（"像渗水"），且**与降噪无关**（关掉 RAW 降噪不消失）。
    // 机理：乘法 luma USM 在阶跃处 (l0 − lb/16) 是一阶导数 ⇒ 亮侧 gain>0 被提亮、
    // 暗侧 gain<0 被压暗，两侧各出一条描边。±0.4 的限幅意味着**最多 40% 的过冲**
    //（灰阶 0.2→0.45 的边缘：亮侧 +11%、暗侧 −25%，显示域约 20~50 LSB）。
    // 这是 USM 的经典 halo，与 denoise/HL 无关（它们在更上游，且关掉它不消失也印证）。
    // 收紧到 ±0.12：保留 3×3 小半径的锐度感知（detail/l0 在正常纹理上 < 0.1），
    // 只砍掉阶跃处的极端过冲。若仍可见，下一步把负半轴直接归零（只提不压）。
    c *= 1.0 + clamp(gain, -0.12, 0.12);
}
// ── v0.10.23：真 sRGB 传递函数（IEC 61966-2-1）取代 pow(c, 1/2.2) ──────────────
// 为什么必须换：pow(1/2.2) **不是 sRGB**。两者在中灰以上几乎重合（线性 0.18 处
// 0.4614 vs 0.4618；1.0 处同为 1.0），但**暗部差最多 ~9/255** —— pow 在 0 处斜率
// 无穷，任何极暗值都被抬成可见灰度；真 sRGB 的线性段（12.92·x）让黑保持是黑。
// 实测（FILMKIT_20260928-184958 vs PhotonCamera_185013，同机位）：我们 P1 = 11/255、
// PC = 1/255；把**同一张成片**按真 sRGB 重编码后 P1 → 3/255，而 P50/P95 几乎不动
// （test_temp/analysis/g_oetf.py）。JPEG 打的本来就是 sRGB ICC（此前名不副实），
// PC（TransferCurve.SRGB）与 darktable 也用真 sRGB ⇒ 这是"黑场不黑、画面发灰"的
// 独立来源之一（另一半在 filmic 之前的输入域，待逐层定位）。
// 常量全部取 IEC 61966-2-1：分段点 0.0031308、1.055、1/2.4、0.055、12.92。
// mix+step 而非 if：此处无性能顾虑，且避免 Mali 对分支的预测执行。
vec3 srgbEncode(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    vec3 hi = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;  // 幂段（c=0 → −0.055，被 step 丢弃）
    vec3 lo = 12.92 * c;                                // 线性段（暗部）
    return mix(hi, lo, step(c, vec3(0.0031308)));
}
// ── 期 B：线性工作空间（Rec.2020）→ 线性 sRGB（含域外色压缩）──────────────────
// 工作空间换成 Rec.2020 后，本 shader 的输入（tex_work_，滚降前的**线性显示域**）是
// Rec.2020 线性；而输出编码仍是 sRGB（交付域不变，见设计 §7 R1）⇒ 编码前必须做一次
// 色域收口。矩阵由 `tools/rec2020-domain-matrices.py` 构造，与 PhotonCamera 的
// `computeWorkingToOutputTransform(BT2020, SRGB)` 同式（sRGB→Rec.2020 方向对拍 4.4e-07）。
vec3 workToSrgb(vec3 c) {
    return vec3(dot(c, vec3(1.6604910021, -0.5876411388, -0.0728498633)),
                dot(c, vec3(-0.1245504745, 1.1328998971, -0.0083494226)),
                dot(c, vec3(-0.0181507634, -0.1005788980, 1.1187296614)));
}
// 域外色压缩（**必须与 workToSrgb 成对使用**，否则 srgbEncode 入口的 clamp 会硬切）。
// 为什么必须有：Rec.2020 装得下、sRGB 装不下的颜色（实物里的红花/霓虹），经 workToSrgb
// 后会出现**负分量**；clamp(c,0,1) 把负值切为 0 ⇒ **饱和度被削掉**、色相也偏。
// 真机实测（0.11.47）：开标准链「饱和偏低（对比实物肉眼观感）」正是这个硬切。
// 做法 = 朝中性轴去饱和，直到最小分量 = 0（**保亮度、保色相**，比 clamp 正确得多）：
//   解 mix(c, vec3(l), t) 的最小分量 = 0 ⇒ t = mn / (mn − l)（mn<0、l>0 ⇒ t∈(0,1]）。
// ⚠ 只压**下界**（负值 = 域外色）；上界仍由 srgbEncode 的 clamp 处理（那是过曝，本就该切）。
// ⚠ 升级路径：换 kFsFilm 里那套 gamutClipChroma（恒 Y 恒色相解析解），代价是要把 LMS
//   那组函数搬进本 shader —— 先用简版实测，数据说需要再上（见归档文档 §6 D4）。
vec3 gamutFit(vec3 c) {
    float mn = min(min(c.r, c.g), c.b);
    if (mn >= 0.0) return c;
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    // ⚠ 整色无正亮度（l ≤ 0）时，朝中性轴去饱和救不回来：t=1 的结果就是 vec3(l)，
    //   而 l 本身为负 ⇒ 必须直接压到黑（数值自检抓到过这个边界）。
    if (l <= 1e-8) return vec3(0.0);
    float denom = mn - l;
    if (denom > -1e-8) return vec3(0.0);   // 极端（整色无正亮度）⇒ 压到黑
    return mix(c, vec3(l), clamp(mn / denom, 0.0, 1.0));
}
/// 域收口 = 转换 + 压缩（唯一入口：调用方不要直接调 workToSrgb，见上方注释）
vec3 workToDisplayLinear(vec3 c) { return gamutFit(workToSrgb(c)); }
// ── SG-1 柔光图取值：手写双线性 + 线性→显示域编码 ────────────────────────────
// 手写双线性：RGBA16F 无 LINEAR 过滤（X300 实测，见 makeTex）⇒ 直接 texture() 采
// 1/2 分辨率的柔光图会退化成 2×2 最近邻块，Screen 混合会把块边放大成可见方块。
// 编码：金字塔在**线性显示域**提取+模糊（与 halation 同域；emulsion 注释：在 gamma
// 编码值上模糊会把 bloom 变成 grey mud），而 Screen 混合按规格 §2 落在**显示域**
// ⇒ 混合前过一次 OETF。逐像素一次 pow，比"另起一个全分辨率编码 pass + 一张全分辨率
// 纹理（12.6MP ≈ 100MB 显存）"便宜得多（见报告 §降级选项）。
// clamp(0,1)：提取 h 在源 > 1（RGBA16F 未截断）时可 > 1，而 Screen 要求 b ≤ 1。
float glowFetch(vec2 uv) {
    vec2 t = uv / uGlowTexel - 0.5;
    vec2 i = floor(t);
    vec2 f = t - i;
    vec2 p0 = (i + 0.5) * uGlowTexel;
    vec2 p1 = p0 + uGlowTexel;
    float s00 = texture(uGlow, vec2(p0.x, p0.y)).r;
    float s10 = texture(uGlow, vec2(p1.x, p0.y)).r;
    float s01 = texture(uGlow, vec2(p0.x, p1.y)).r;
    float s11 = texture(uGlow, vec2(p1.x, p1.y)).r;
    float b = clamp(mix(mix(s00, s10, f.x), mix(s01, s11, f.x), f.y), 0.0, 1.0);
    return (b <= 0.0031308) ? 12.92 * b : 1.055 * pow(b, 1.0 / 2.4) - 0.055;
}
// ── U-4 清晰度的 16-tap 环形 luma 模糊（2 环 × 8 方向 = 16 tap，**全手动展开**）──
// 与 kFsStyle 的 blurLuma16 **同偏移序列、同权重、同 0.0625 归一**；唯一的实现差异 =
// 每个 tap 要过 tapLuma（见下）—— 这是**域逼出来的**，不是自由发挥：
//   · kFsStyle 的 uTex 是**已成片 sRGB**（显示域）⇒ tap 直接取 luma 就与中心同域，
//     它只需要 chainLuma 把基准提升过"曝光/WB 往返 + 对比度 + 局部曝光"；
//   · 本 shader 的 uTex 是**滚降前的线性显示域**工作纹理（tex_work_）⇒ 中心 c 已经过
//     highlightRolloff + srgbEncode，tap 必须走**同一条链**才同域。否则 blur 基准偏低
//     （线性 0.18 vs 显示 0.46）⇒ 清晰度变成"整体提亮"而不是局部对比（U-1 的
//     chainLuma 注释里记的正是这一类事故）。
// chainLuma 在本 shader 里**退化为恒等**：它要提升的三项（曝光/WB、对比度、局部曝光）
//   在拍照链路全部位于更早的 pass（RAW 线性域）或根本不在本链路（局部调整只服务编辑器）
//   ⇒ 不存在"中心已过、基准没过"的差。**不写空的 chainLuma**（YAGNI：留着会让人以为
//   拍照链路也有域提升，改天有人往里加一项就出上面那个事故）。
// ⚠️ 权重是 **Rec.601**（0.299/0.587/0.114），与本 shader 顶部的 lumaLin()（Rec.709，
//   服务 sharpenLuma）**故意不统一** —— 必须与 kFsStyle 的 blurLuma16 逐字一致（kFsStyle
//   内部同样是"601 的局部对比 + 709 的 split/mono"两套，理由见那里的注释）。
// ⚠️ GLSL 三禁：无数组参数、无循环、无多行宏（本段 16 行手动展开是硬要求）。
// E2-12（对照 kFsStyle）：那里的 uv 是裁剪后坐标、逐 tap 经 cropUV 映射到原图；本 shader
//   的裁剪在**引擎输入侧**就做完了（tex_work_ 已是裁剪后画面）⇒ tap 直接用 uv + 偏移。
//   环偏移对称 ⇒ uFlipY 的 y 翻转不改变环形状（±d 仍是 ±d）。
float tapLuma(vec2 uv) {
    return dot(srgbEncode(workToDisplayLinear(highlightRolloff(texture(uTex, uv).rgb))),
               vec3(0.299, 0.587, 0.114));
}
float blurLuma16(vec2 uv) {
    vec2 d1 = uClarStep;
    vec2 d2 = uClarStep * 2.0;
    float lb = 0.0;
    lb += tapLuma(uv + vec2( d1.x, 0.0));
    lb += tapLuma(uv + vec2(-d1.x, 0.0));
    lb += tapLuma(uv + vec2(0.0,  d1.y));
    lb += tapLuma(uv + vec2(0.0, -d1.y));
    lb += tapLuma(uv + vec2( d1.x,  d1.y) * 0.70710678);
    lb += tapLuma(uv + vec2(-d1.x,  d1.y) * 0.70710678);
    lb += tapLuma(uv + vec2( d1.x, -d1.y) * 0.70710678);
    lb += tapLuma(uv + vec2(-d1.x, -d1.y) * 0.70710678);
    lb += tapLuma(uv + vec2( d2.x, 0.0));
    lb += tapLuma(uv + vec2(-d2.x, 0.0));
    lb += tapLuma(uv + vec2(0.0,  d2.y));
    lb += tapLuma(uv + vec2(0.0, -d2.y));
    lb += tapLuma(uv + vec2( d2.x,  d2.y) * 0.70710678);
    lb += tapLuma(uv + vec2(-d2.x,  d2.y) * 0.70710678);
    lb += tapLuma(uv + vec2( d2.x, -d2.y) * 0.70710678);
    lb += tapLuma(uv + vec2(-d2.x, -d2.y) * 0.70710678);
    return lb * 0.0625;
}
void main() {
    vec2 uv = vUV0;
    if (uFlipY > 0.5) uv.y = 1.0 - uv.y;
    vec3 c = texture(uTex, uv).rgb;
    if (uRollOff > 0.5) c = highlightRolloff(c);   // 0.12.166：设置页可关（A/B）
    if (uSharpen > 0.0) sharpenLuma(c, uv);
    c = srgbEncode(workToDisplayLinear(c));
    // ── 期 B：引擎 1 的对比度（`[4]`）──────────────────────────────────────────
    // 放在 display 域、OETF 之后：引擎 0 的 `[4]` 在 updateFilmicEV 里折算成 filmic 曲线
    // 系数，引擎 1 没有对应物 ⇒ 补一个与 kFsStyle **同式**的实现（display 域绕中灰 0.5
    // 线性拉伸）。引擎 0 时 CPU 侧下发 1.0 ⇒ 本段恒等，**逐位零回归**。
    // 位置 = U-4 段之前（与 kFsStyle 的次序一致：对比度在影调分区/清晰度之前）。
    // ⚠ 判据必须写 `> 0.0`、**不能写 `!= 1.0`**：uniform 的默认值是 0，而任何没走到下发点
    // 的路径（离线执行器 / 测试夹具）都会拿到它 —— 写 `!= 1.0` 就会执行
    // `clamp((c−0.5)×0+0.5)` 把画面拉成**平坦灰 0.5**（实测踩过：check-u4 判据②
    // max|Δ| = 119.5 LSB）。引擎 0 下发 1.0，clamp 在 [0,1] 内是恒等 ⇒ 零回归。
    if (uContrastOut > 0.0) c = clamp((c - 0.5) * uContrastOut + 0.5, 0.0, 1.0);
    // ── U-4 ① 清晰度（显示域局部对比；0 = 关）*[78]* ───────────────────────────
    // 形式 = **加性 luma USM**（照 kFsStyle 的 ④ 段 `c += (l0 - lb) * uClarity`，逐字同式）：
    // RGB 同加一个标量 ⇒ 只抬局部亮度，不逐通道拉伸。差值限幅 ±0.25 拦高反差边缘过冲
    //（标定旋钮，与 kFsStyle 同值）。
    // ⚠️ 与 [39] sharpen 的分工（ADR 已裁决"不复用"）：[39] 是 OETF **之前**的 3×3 小半径
    //   锐化、乘法 luma USM（保色度）、走相对增益（÷l0）；本段是显示域的 16-tap 中半径
    //   加性 USM。两者语义不同（半径、域、保色度方式都不同）⇒ 并存，互不替代。
    // 本段的 tap 用了 16 次额外纹理采样 + 每 tap 一次 highlightRolloff/srgbEncode
    //（≈ 16 exp + 48 pow/px）—— 这是本卡唯一的显著 GPU 成本，量级估算见
    // docs/reports/u4-camera-display-domain-REPORT.md。
    if (uClarity > 0.0) {
        float l0 = dot(c, vec3(0.299, 0.587, 0.114));
        float lb = blurLuma16(uv);
        c += clamp(l0 - lb, -0.25, 0.25) * uClarity;
    }
    // ── U-4 ② 影调分区（display 域 luma 分区**乘性**增益；全 0 = 整段跳过）──────
    // 权重曲线（l = display 域 luma，s = 1−l）：高光 l²、阴影 s²（宽范围，覆盖中灰两侧），
    // 白 l³、黑 s³（端部集中 = "白点/黑点"的窄作用域）。全是多项式 ⇒ 端点与一阶都连续，
    // 分区之间**无硬边**。**逐字照抄 kFsStyle 的同名段**（含"为什么乘性"的推导）：
    // 乘性保持色度比；端点行为 highlights=+1 ⇒ 纯白处 ×2（clamp 到 1）、blacks=-1 ⇒
    // 纯黑处 ×0。
    // 为什么在清晰度**之后**：kFsStyle 的顺序就是如此（那里的理由是 chainLuma 不必扩到
    // 本段；本 shader 里连 chainLuma 都退化为恒等，顺序照抄是为了"两侧同一套先后"）。
    if (uToneOn > 0.5) {
        float l = dot(c, vec3(0.299, 0.587, 0.114));
        float s = 1.0 - l;
        float g = 1.0 + uHighlights * l * l + uShadows * s * s +
                  uWhites * l * l * l + uBlacks * s * s * s;
        c *= max(g, 0.0);
    }
    // ── U-4 ③ 褪色（胶片褪色感 = 抬起黑场，白点保持 1）*[94]* ─────────────────
    // c' = lift + c×(1−lift)：c=0 ⇒ lift（黑场抬起）、c=1 ⇒ 1（白点不动）。
    // lift = uFade × 0.25（标定旋钮）：fade=1 ⇒ 黑场抬到 64/255（明显灰雾）、
    // fade=0.2 ⇒ 13/255（轻微）。与 [93] blacks 的区别：blacks 是**乘性**（保色度），
    // fade 是**加性抬黑**（发灰 = 褪色感本身），两者互补不重复。**逐字照抄 kFsStyle**。
    if (uFade > 0.0) {
        float lift = uFade * 0.25;
        c = lift + c * (1.0 - lift);
    }
    // ── SG-1 柔光（soft glow）[95..97]：显示域 **Screen** 混合 ─────────────────
    // 算法（规格 §2 ③）：out = 1 − (1−c)(1−b·strength)，b = 共享金字塔的单半径模糊
    // 高光（glowFetch 已把它编码到本段的显示域）。
    // 为什么 Screen 而不是加法（规格 §1 的判据）：加法是 halation 的混合方式（曝光
    // 阶段现象，能量叠加、过曝区更亮）；柔光是成片观感层，Screen 保高光不过曝
    //（c = 1 ⇒ out = 1，绝不溢出）。
    // 为什么落在**这里**：规格 §2 的顺序 —— 影调分区/褪色之后（柔光作用在"最终影调"
    // 上）、暗角与颗粒之前（§4）。门控 uGlowOn 由 CPU 侧算（[95] > 0 且链成功）
    // ⇒ 默认（[95] = 0）整段跳过，输出逐位不变。
    if (uGlowOn > 0.5) {
        float gb = glowFetch(uv) * uGlowStrength;
        c = 1.0 - (1.0 - c) * (1.0 - gb);
    }
    // ── E4-2 暗角（vignette，darktable vignette.c 正圆模型）────────────────
    // 域 = sRGB 显示域、乘法（ADR-7/ADR-9）：落在 LUT 之前 —— 与 darktable 的
    // 声明域（non-linear, RGB, display-referred）一致，且放 LUT 前可避免不同
    // LUT 把同一暗角渐变的形状改掉。
    // uv 用 vUV0（未 flip 的采样坐标）：暗角径向对称 ⇒ 与 flip 后**逐位等价**，
    // 同时让拍照/编辑器两条链路的这段代码**逐字相同**（E4-2 验收项）。
    if (uVigBrightness < 0.0) {
        vec2 pv = uVigScaleXY * abs(2.0 * vUV0 - 1.0);
        float cplen = length(pv);                  // shape=1：exp1=2, exp2=1/2
        float vw = clamp((cplen - uVigDscale) / uVigFscale, 0.0, 1.0);
        vw = 0.5 - 0.5 * cos(3.14159265 * vw);     // 余弦平滑（darktable 同式）
        c *= (1.0 + vw * uVigBrightness);
    }
    // ── U-1 [23][24] 分离色调 + [25] 单色（显示域；与 kFsStyle **逐字同式**）────────
    // 期 B 从 kFsFilm 挪来：那边是线性工作空间域，同一物理亮度下 luma 更小（线性 0.18
    // ↔ 显示 0.46）⇒ 阴影权重 exp(-3l) 差 43%，编辑器调出来的效果与拍照对不上。
    // 位置与 kFsStyle 完全同序（暗角之后、LUT 之前）；数学**逐字照抄 kFsStyle**，
    // 只用本 shader 的**显示域** `luma()`（不是服务 sharpenLuma 的 `lumaLin()`）。
    if (uSplit.x != 0.0 || uSplit.y != 0.0 || uMono > 0.0) {
        float l = luma(c);
        float ws = exp(-l * 3.0);
        float wh = smoothstep(0.5, 1.0, l);
        c += uSplit.x * ws * vec3(0.05, 0.02, -0.04)
           + uSplit.y * wh * vec3(-0.03, 0.0, 0.05);
        c = mix(c, vec3(luma(clamp(c, 0.0, 4.0))), uMono);
    }
    // M4：display 域 3D LUT（在 OETF 之后 —— 显示域 LUT 放到线性域会错色）
    if (uLutOn > 0.5) c = mix(c, lutApply(c), uLutStrength);
    // ── E4-3 颗粒（granularity，darktable grain.c）────────────────────────
    // 坐标 = 像素/短边（uGrainNorm = 1/min(W,H)）⇒ 与像素数无关（ADR-11）；seed 是
    // **相对** x 偏移（成片按内容派生、预览/编辑器恒 0 ⇒ 取景稳定不闪）。密度调制 =
    // 相纸响应 LUT：x 轴 = 噪声·strength·0.15（GRAIN_LIGHTNESS_STRENGTH_SCALE），
    // y 轴 = 显示域 luma（≈ darktable 的 Lab L/100）。LUT 输出单位是 darktable 的
    // L 单位（100×）⇒ ×0.01 回到显示域。
    if (uGrainAmount > 0.0) {
        float strength = uGrainAmount * 0.01;
        vec2 gp = gl_FragCoord.xy * uGrainNorm + vec2(uGrainSeed, 0.0);
        float l = dot(c, vec3(0.299, 0.587, 0.114));
        c += grainLutFetch(vec2(grainNoise(gp, uGrainZoom) * strength * 0.15, l)) * 0.01;
    }
    // v0.10.67：RPDF ±0.5LSB → **TPDF ±1LSB**（两个独立均匀分布之和 = 三角分布）。
    // 依据：真机成片最平坦块实测 44.7% 相邻像素完全相同、行间 |Δ| 恰好 1.000 ——
    // 规则量化斜坡被人眼 Mach band 放大成条带（用户报的"边缘断层"）。TPDF 把
    // 零变占比降到 35.6%，即把规则台阶打散为随机台阶。均匀分布(RPDF)只能移动
    // 台阶、不能打散，故必须用三角分布。
    // ── dither 幅度按**交付位深**重标（设计 §2.5 / §4.4：本期最易漏项）──────────
    // 除数 = 交付档上限，由 CPU 侧按档下发 uOutMax（OutputBitDepth.h 的 kOutputMaxUniform
    // / outputMaxOf：8bit JPEG = 255.0、10bit HEIF = 1023.0）。10bit 下若沿用 8bit 幅度
    // （±1/255 = ±4 LSB@10bit）等于把 10bit 抖回 ~8.6bit 有效精度 ⇒ 静默降位。
    // ⚠ 兜底 255.0 不可删：uniform 未下发时 GLSL 读 0 ⇒ 不加兜底会 /0 → NaN（既有
    //   调用方 check-halation/check-softglow/离线脚本**不传**该 uniform，靠兜底保持
    //   与改动前**逐位相同**）。兜底值必须 == 真源 kOutputMaxGlsl，
    //   由 tools/check-bitdepth-single-source.py 锚定（改位深忘了改这里 ⇒ 守卫红）。
    float ditherMax = uOutMax > 0.0 ? uOutMax : 255.0;
    c += (dhash(gl_FragCoord.xy) + dhash(gl_FragCoord.xy + vec2(37.0, 17.0)) - 1.0) / ditherMax;
    // 过曝斑马纹（仅预览；成片路径由引擎把 uZebra 置 0 —— 提示绝不进成片）：
    // 显示域亮度达阈值的区域叠 45° 黑白斜纹。高光一旦全饱和就不可恢复
    // （DNG 实测：高光 1.089% 三通道同时饱和），拍前能看见就能少拍废片。
    if (uZebra > 0.0) {
        float l8 = dot(c, vec3(0.2126, 0.7152, 0.0722));
        if (l8 >= uZebra) {
            float st = step(0.5, fract((gl_FragCoord.x - gl_FragCoord.y) / 12.0));
            c = mix(c, vec3(st), 0.9);
        }
    }
    oColor = vec4(clamp(c, 0.0, 1.0), 1.0);
})";

// ── E1-3 编辑器渲染通路（2026-10-08）────────────────────────────────────────
// 输入 = **已经过 OETF 的成片**（JPEG 解出的 RGBA8，显示域 sRGB 0..255）。只重跑
// kFsOut 的 `srgbEncode` **之后**的三段：3D LUT → 颗粒 → TPDF dither。
// ⚠ ADR-7 硬约束：本 shader 内**任何地方都不得插入 OETF/EOTF 转换**。输入已是 sRGB
//   编码值（上传为归一化纹理 ⇒ 0..1），输出直接写 RGBA8；插 OETF = 二次编码
//   ⇒ 颜色发灰变暗（ADR 里点名的"最容易犯的错"）。
// ⚠ 绝不复用 submitStill 的 passFilm：那会对已成片的图**再跑一遍 filmic 链**并做
//   错误的域比归一 ⇒ 双重影调映射（画面显著变暗变灰，可行性报告 R1）。
// 与 kFsOut 的差异（刻意的，勿"对齐"）：
//   ① 无 highlightRolloff / sharpenLuma / srgbEncode —— 已烘焙进输入，重跑 = 二次处理；
//   ② 无 uFlipY —— 本 shader 直接采样上传的 Bitmap 数据：上传数据第 0 行（Bitmap
//      **顶行**）落在纹理 t=0，屏幕底行（vUV0.y=0）就取 t=0 ⇒ glReadPixels 第 0 行
//      （= 屏幕底行）= 图像顶行 ⇒ 与 Bitmap 行序**恒等（净零翻转）**。
//      成片链需要 uFlipY 不是因为 GL 读回行序，而是因为 kFsStill **自己**预翻转了
//      一次（`vec2 uv = vec2(vUV0.x, 1.0 - vUV0.y);`），kFsOut 那一次是把它抵消掉；
//      编辑器不经 kFsStill ⇒ 这里再翻一次才会把图**上下颠倒**。
//      （离线验收 tools/check-style-passthrough.py 的 ①/⑤ 真跑本 shader 证明。）
//   ③ 无 uZebra —— 过曝提示绝不进成片；
//   ④ 颗粒种子 = uGrainSeed（外部固定）而非帧计数 ⇒ 同一输入 + 同一参数 ⇒ 输出可复现
//      （报告 R3：帧计数种子会让同一张图两次导出的颗粒图案不同，A/B 对比失真）。
// lutTetra/lutApply/dhash 与**整段颗粒（permutation 表 / simplex / LUT 采样 / 主段）**
// 逐字拷自 kFsOut —— 显示域 LUT 与颗粒的语义必须与成片一致（ADR-7 表格：LUT 资产本
// 就是 display 域、OETF 后；E4-3 颗粒同在显示域、同在 LUT 之后）。
const char* kFsStyle = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
// ── E4-3 颗粒（granularity；darktable src/iop/grain.c）────────────────────
// 域常数在 CPU 侧算好（shader 内不做 W/H 除法与 clamp，与 E4-2 暗角同款）：
//   uGrainZoom = grainZoom([27])、uGrainNorm = 1/min(W,H)、uGrainSeed = 噪声坐标的
//   **相对** x 偏移（成片按内容派生；预览/编辑器恒 0 ⇒ 取景时图案稳定不闪）。
uniform float uGrainAmount;  // [26] 0..100（strength = /100；0 ⇒ 整段跳过，零成本）
uniform float uGrainSeed;
uniform float uGrainZoom;
uniform float uGrainNorm;
uniform highp sampler2D uGrainLut;  // 128² R16F 相纸响应 LUT（CPU 侧按 [60][61] 重算）
// ── E2-12 裁剪 + 90° 步进旋转（ADR-16）：**引擎输入侧的 UV 子矩形** ──────────
// 所有对 uTex 的采样都经 cropUV() 映射；**后续处理（暗角 / 局部调整 / LUT /
// 颗粒）一律继续用 vUV0**（= 裁剪后的归一化坐标，仍覆盖 [0,1]）⇒ 暗角自动
// 相对裁剪后画面居中、局部调整中心自动落在用户拖的位置。
// ⚠ 这就是"裁剪必须在引擎输入侧做"的原因：若改为最后裁输出位图，暗角会跑到
//   画面外、局部调整中心会偏（ADR-14 裁决 3 / ADR-16）。
// 不裁剪 + rot=0 ⇒ origin=(0,0)、size=(1,1) ⇒ `0.0 + o × 1.0` 逐位恒等（零回归）。
uniform vec2 uCropOrigin;   // 裁剪矩形原点（原图归一化 UV；y 与 Bitmap 行序同向）
uniform vec2 uCropSize;     // 裁剪矩形尺寸（原图归一化 UV）
uniform float uCropRot;     // 顺时针 90° 步进（0..3），作用于裁剪后的画面
// 输出坐标 o（= vUV0，裁剪后归一化）→ 原图 UV。旋转 = 逆映射：rot=1 时
// 源左上 → 输出右上 ⇒ o=(0,0) 取源 (0,1)（左列底端）。四路都是纯仿射
//（无循环 / 无数组 / 无宏 ⇒ 符合 GLSL 三禁，ADR §6.1）。
vec2 cropUV(vec2 o) {
    if (uCropRot > 2.5) return uCropOrigin + vec2(1.0 - o.y, o.x) * uCropSize;
    if (uCropRot > 1.5) return uCropOrigin + vec2(1.0 - o.x, 1.0 - o.y) * uCropSize;
    if (uCropRot > 0.5) return uCropOrigin + vec2(o.y, 1.0 - o.x) * uCropSize;
    return uCropOrigin + o * uCropSize;
}
// ── M4 3D LUT（display 域）——与 kFsOut 同款，四面体插值
uniform highp sampler3D uLut;
uniform float uLutOn;
uniform float uLutStrength;
uniform vec3 uLutDomainMin;
uniform vec3 uLutDomainInv;
uniform float uLutSize;
// ── E4-2 暗角（vignette；darktable vignette.c 正圆模型，sRGB 显示域乘法）────
// 域常数在 CPU 侧算好（shader 内不做 W/H 除法）：pv 尺度 = (W,H)/max(W,H)。
// [66] ≥ 0（默认 0）⇒ uVigBrightness = 0 ⇒ 整段跳过（A/B 开关 = [66]，零成本）。
uniform vec2 uVigScaleXY;
uniform float uVigDscale;
uniform float uVigFscale;
uniform float uVigBrightness;
// ── SG-1 柔光（soft glow；darktable src/iop/bloom.c 的 Orton 支）──────────────
// 落点 = **本 shader 的显示域段**、褪色之后 / 暗角之前（规格 §2 的顺序：
// 影调分区/褪色 → 柔光 → 暗角 → 颗粒 → LUT → dither）：
//   · 在暗角之前 —— 柔光的高光外扩不该被暗角再次衰减（规格 §4）；
//   · 在颗粒之前 —— 颗粒是胶片纹理，叠在含柔光的画面上（规格 §4）。
// uGlow = 1/2 分辨率灰度柔光图（共享金字塔的加权和，**线性显示域**；由 passStyle/
// passOutput 在本 shader **之前**跑出来）。uGlowOn 由 CPU 侧算（[95] > 0 且链成功）
// ⇒ 默认（[95] = 0）时本段整段跳过（零成本、逐位零回归）。
uniform highp sampler2D uGlow;
uniform float uGlowOn;
uniform float uGlowStrength;  // [95] 0..1
uniform vec2 uGlowTexel;      // 1/glowW, 1/glowH（CPU 侧算好，shader 内不做除法）
vec3 lutTetra(vec3 f, vec3 c000, vec3 c100, vec3 c010, vec3 c001, vec3 c110,
              vec3 c101, vec3 c011, vec3 c111) {
    if (f.x >= f.y) {
        if (f.y >= f.z) return (1.0 - f.x) * c000 + (f.x - f.y) * c100 + (f.y - f.z) * c110 + f.z * c111;
        if (f.x >= f.z) return (1.0 - f.x) * c000 + (f.x - f.z) * c100 + (f.z - f.y) * c101 + f.y * c111;
        return (1.0 - f.z) * c000 + (f.z - f.x) * c001 + (f.x - f.y) * c101 + f.y * c111;
    }
    if (f.z >= f.y) return (1.0 - f.z) * c000 + (f.z - f.y) * c001 + (f.y - f.x) * c011 + f.x * c111;
    if (f.z >= f.x) return (1.0 - f.y) * c000 + (f.y - f.z) * c010 + (f.z - f.x) * c011 + f.x * c111;
    return (1.0 - f.y) * c000 + (f.y - f.x) * c010 + (f.x - f.z) * c110 + f.z * c111;
}
vec3 lutApply(vec3 c) {
    vec3 t = clamp((c - uLutDomainMin) * uLutDomainInv, 0.0, 1.0) * (uLutSize - 1.0);
    vec3 i0 = floor(t);
    vec3 f = t - i0;
    ivec3 i = ivec3(i0);
    ivec3 mx = ivec3(int(uLutSize) - 1);
    vec3 c000 = texelFetch(uLut, clamp(i, ivec3(0), mx), 0).rgb;
    vec3 c100 = texelFetch(uLut, clamp(i + ivec3(1, 0, 0), ivec3(0), mx), 0).rgb;
    vec3 c010 = texelFetch(uLut, clamp(i + ivec3(0, 1, 0), ivec3(0), mx), 0).rgb;
    vec3 c001 = texelFetch(uLut, clamp(i + ivec3(0, 0, 1), ivec3(0), mx), 0).rgb;
    vec3 c110 = texelFetch(uLut, clamp(i + ivec3(1, 1, 0), ivec3(0), mx), 0).rgb;
    vec3 c101 = texelFetch(uLut, clamp(i + ivec3(1, 0, 1), ivec3(0), mx), 0).rgb;
    vec3 c011 = texelFetch(uLut, clamp(i + ivec3(0, 1, 1), ivec3(0), mx), 0).rgb;
    vec3 c111 = texelFetch(uLut, clamp(i + ivec3(1, 1, 1), ivec3(0), mx), 0).rgb;
    return lutTetra(f, c000, c100, c010, c001, c110, c101, c011, c111);
}
// ── E2-2 基础调整（ADR §5.6；域约定 ADR-7 订正 v2）──────────────────────────
// **本 shader 内的成对往返只有两处**：① 主路径的曝光×WB（main 内，uExpWbOn 门控）；
// ② 清晰度把 blur 基准提升到同域的标量往返（chainLuma，仅 clarity>0 时执行）。
// **没有任何单程 OETF/EOTF** —— 对已编码值再编码一次 = "画面发灰变暗"（ADR-7 的
// 事故来源；本 shader 的输入已经是 OETF 后的显示域成片）。
uniform float uEv;         // [0] 曝光 EV（线性域）
uniform vec3 uWbGain;      // [1..3] WB RGB 增益（线性域）
uniform float uExpWbOn;    // 门控（CPU 侧算）：ev≠0 或 wb≠(1,1,1)
uniform float uContrast;   // [4] 对比度（显示域绕中灰；1 = 恒等）
uniform float uContrastOn;
// ── U-2 [20..22] 分区饱和三档（**与 kFsFilm 4486-4493 同口径**）──────────────────
// 拍照侧：`sat = clamp(uSat.x·ws + uSat.y·wm + uSat.z·wh, 0, 2)`，权重
// `ws = exp(−3l)` / `wh = smoothstep(0.5, 1, l)` / `wm = max(0, 1−ws−wh)`，
// `l = luma()`（Rec.709，见下方同名函数）。U-2 起编辑器侧**逐字同式**：
//   · 读数从"只读 [21]"改为三档（此前 UI 一个滑杆同写三槽 ⇒ 引擎只认中调；
//     现在三档都生效 ⇒ "只改 [22]"这类预设也能被如实执行）；
//   · luma 从 Rec.601 改为 **Rec.709**（与 kFsFilm 的分区判据同一个 luma）——
//     ⚠ 这是本段**唯一的行为变更**：默认三槽 = 1.0 时 `sat = 1` ⇒ `mix(vec3(l), c, 1)`
//     **恒等于 c**（与 luma 取值无关）⇒ 逐位零回归；非默认值下"中心 luma 与分区
//     权重同源"比旧版混用两套 luma 更自洽。
// 域差异（已知、刻意保留，与 U-1 的 split/mono 同款）：kFsFilm 的 l 是**线性域** luma
// （filmic 之后、OETF 之前），这里是**显示域** luma ⇒ 同一物理亮度下这里的 l 更大
// （线性 0.18 → 显示 0.4667）、阴影权重 exp(−3l) 更小。不把 c decode 回线性再算：
// ADR-7 禁止本 shader 内任何**单程** EOTF（⑬f 的调用次数哨兵，多一次即红）。
uniform float uSatShadow;  // [20] 暗部分区饱和度（1 = 恒等）
uniform float uSatMid;     // [21] 中间调分区饱和度
uniform float uSatHigh;    // [22] 高光分区饱和度
uniform float uSatOn;      // 门控（CPU 侧算）：三槽任一 ≠ 1
uniform float uClarity;    // [78] 清晰度（显示域局部对比；0 = 关）
uniform vec2 uClarStep;    // 清晰度环半径（UV 单位）= r_px/(W,H)
// ── E2-6 局部调整（ADR §5.7）：mask **程序化生成**（纯算术，零纹理、零额外 pass）
// 首版**只支持一个局部调整**（径向或线性二选一）：Mali 上"数组作函数参数"与
// "循环内动态索引"都是**静默失败**（ADR §6.1）⇒ 多组参数无法安全表达。
// 多组的扩展路径（展开 N 份 / N×1 RGBA16F 参数纹理）见 ADR §5.7，等首版真机
// 跑通、测过开销之后再定（铁律：先跑通一版，用数据决定）。
uniform float uLocalOn;      // 门控（CPU 侧算）：type>0 且 (ev≠0 或 sat≠1)
uniform float uLocalType;    // [79] 0 = 关，1 = 径向，2 = 线性
uniform vec2 uLocalCenter;   // [80][81] 归一化中心（vUV0 坐标系，见 localMask）
uniform vec2 uLocalRadius;   // [82] 径向半径 (rx, ry)：rx = r（相对图像宽）、ry = r×W/H
                             //（CPU 侧拆开，使 d = length(p/r) 在**像素空间**等距 = 正圆）
uniform float uLocalFeather; // [83] 羽化宽度（相对半径/半宽）
uniform float uLocalAngle;   // [84] 线性 mask 的法线方向（弧度）
uniform float uLocalWidth;   // [85] 线性半宽（UV 单位）
uniform float uLocalEv;      // [86] 局部曝光（EV；负 = 压暗）
uniform float uLocalEvOn;    // 门控：local_ev ≠ 0（只有它非 0 时才需要往返）
uniform float uLocalSat;     // [87] 局部饱和度（1 = 恒等）
uniform float uLocalSatOn;   // 门控：local_sat ≠ 1
// ── E2-11 影调分区 + 褪色（display 域；与 [4] contrast / [78] clarity 同域）──────
// 权重曲线（l = display 域 luma，s = 1−l）：高光 l²、阴影 s²（宽范围，覆盖中灰两侧），
// 白 l³、黑 s³（端部集中 = "白点/黑点"的窄作用域）。全是多项式 ⇒ 端点与一阶都连续，
// 分区之间**无硬边**（这是"影调分区"与"曲线分段"的关键区别）。
uniform float uHighlights;   // [90] 高光提亮/压暗（0 = 不动）
uniform float uShadows;      // [91] 阴影提亮/压暗（0 = 不动）
uniform float uWhites;       // [92] 白色色阶（0 = 不动）
uniform float uBlacks;       // [93] 黑色色阶（0 = 不动）
uniform float uFade;         // [94] 褪色（抬起黑场；0 = 关）
uniform float uToneOn;       // 门控（CPU 侧算）：[90..93] 任一非 0
// ── U-1 分离色调 [23][24] + 单色 [25]（display 域；**与 kFsFilm 同式**，见 §9.3）──
// 本 shader 的 **Rec.709** luma：逐字拷自 kFsFilm 4085/4733 的同名函数。
// ⚠ 本 shader 其它段（blurLuma16 / 饱和度 / 影调分区 / 颗粒）用 Rec.601
//   （0.299/0.587/0.114），这里**故意不统一** —— U-1 这三行的语义必须与 kFsFilm 逐字
//   对齐（ws/wh 的分区边界由它决定），用户"不统一的要统一掉"针对的是**参数**而不是
//   链路内部权重；E4-2 暗角 / E4-3 颗粒用的就是同一手法（两处逐字比对，改一处必红）。
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
// 门控在 shader 内：三槽全默认（split=(0,0) 且 mono=0）⇒ 整段跳过 ⇒ **逐位零回归**
//（A/B 通路口径就是这三个默认值；与 kFsFilm 一致 —— 那侧也是"数组里给 0 就等于不做"）。
uniform vec2 uSplit;         // [23] split_warm / [24] split_cool（0 = 不动）
uniform float uMono;         // [25] monochrome（0/1；0 = 不去色）
// ── U-2 胶片曲线 [5..10] + [37][38]（display 域；设计依据见 .cpp 的 computeEditorCurve）──
// 系数一律 CPU 侧算好（`computeEditorCurve`），这里只做"端点重映射 + 两段 Horner"：
//   t = clamp(a·x + b, 0, 1)          ← [37][38]：分段仿射（以显示域中灰为界、中灰不动）
//   y = clamp(t + cur(t) − base(t), 0, 1)   ← [5..10]：差值法（lat 默认 ⇒ 两项相消）
// 两组三段多项式（cur = 当前 latitude、base = 默认 0.01 的参考曲线）—— 为什么必须两组：
// 拍照链路的 filmic 是"场景线性 → 显示域"的完整映射，编辑器输入已是它的输出 ⇒ 直接
// 重跑 = 二次影调。差值法给出"相对默认 latitude 的变化量"，端点严格不动（两条曲线在
// 0/1 处同值）。若只用线性近似（k·(t − base)）省掉 cur，实测最大偏差 5.28/255 不可接受。
// ⚠ GPU 三禁：**逐通道手写 R/G/B 三份** —— 不用数组参数、不做动态索引、不用多行宏。
// 门控 uCurveOn 由 CPU 侧算（8 个槽位全默认 ⇒ 0）⇒ 整段不进 ⇒ 逐位零回归。
uniform float uCurveOn;
uniform vec4 uCurveLo;       // [37] 黑点段：t = .x·x + .y（x < 中灰）；默认 (1, 0)
uniform vec4 uCurveHi;       // [38] 白点段：t = .x·x + .y（x ≥ 中灰）；默认 (1, 0)
const float kCurveGrey = 0.4666961;   // srgbEncode(0.1845)：显示域中灰（分段界 + 中段锚点）
uniform vec3 uCurveToeAt, uCurveShAt;   // cur 的分段点（逐通道）
uniform vec3 uCurveToeM1, uCurveToeM2, uCurveToeM3, uCurveToeM4, uCurveToeM5;
uniform vec3 uCurveShM1, uCurveShM2, uCurveShM3, uCurveShM4, uCurveShM5;
uniform vec3 uCurveLinM1, uCurveLinM2;
uniform vec3 uBaseToeAt, uBaseShAt;     // base（差值参考）的分段点
uniform vec3 uBaseToeM1, uBaseToeM2, uBaseToeM3, uBaseToeM4, uBaseToeM5;
uniform vec3 uBaseShM1, uBaseShM2, uBaseShM3, uBaseShM4, uBaseShM5;
uniform vec3 uBaseLinM1, uBaseLinM2;
// cur 曲线：逐通道三段求值（Horner；vec3 的 .r/.g/.b = R/G/B）。三份的理由见上方三禁。
float curveR(float t) {
    if (t < uCurveToeAt.r)
        return uCurveToeM1.r + t * (uCurveToeM2.r + t * (uCurveToeM3.r + t * (uCurveToeM4.r + t * uCurveToeM5.r)));
    if (t > uCurveShAt.r)
        return uCurveShM1.r + t * (uCurveShM2.r + t * (uCurveShM3.r + t * (uCurveShM4.r + t * uCurveShM5.r)));
    return uCurveLinM1.r + t * uCurveLinM2.r;
}
float curveG(float t) {
    if (t < uCurveToeAt.g)
        return uCurveToeM1.g + t * (uCurveToeM2.g + t * (uCurveToeM3.g + t * (uCurveToeM4.g + t * uCurveToeM5.g)));
    if (t > uCurveShAt.g)
        return uCurveShM1.g + t * (uCurveShM2.g + t * (uCurveShM3.g + t * (uCurveShM4.g + t * uCurveShM5.g)));
    return uCurveLinM1.g + t * uCurveLinM2.g;
}
float curveB(float t) {
    if (t < uCurveToeAt.b)
        return uCurveToeM1.b + t * (uCurveToeM2.b + t * (uCurveToeM3.b + t * (uCurveToeM4.b + t * uCurveToeM5.b)));
    if (t > uCurveShAt.b)
        return uCurveShM1.b + t * (uCurveShM2.b + t * (uCurveShM3.b + t * (uCurveShM4.b + t * uCurveShM5.b)));
    return uCurveLinM1.b + t * uCurveLinM2.b;
}
// base 曲线（默认 latitude 0.01）：表达式序列与上面三份**逐字相同**，只换 uniform。
float baseR(float t) {
    if (t < uBaseToeAt.r)
        return uBaseToeM1.r + t * (uBaseToeM2.r + t * (uBaseToeM3.r + t * (uBaseToeM4.r + t * uBaseToeM5.r)));
    if (t > uBaseShAt.r)
        return uBaseShM1.r + t * (uBaseShM2.r + t * (uBaseShM3.r + t * (uBaseShM4.r + t * uBaseShM5.r)));
    return uBaseLinM1.r + t * uBaseLinM2.r;
}
float baseG(float t) {
    if (t < uBaseToeAt.g)
        return uBaseToeM1.g + t * (uBaseToeM2.g + t * (uBaseToeM3.g + t * (uBaseToeM4.g + t * uBaseToeM5.g)));
    if (t > uBaseShAt.g)
        return uBaseShM1.g + t * (uBaseShM2.g + t * (uBaseShM3.g + t * (uBaseShM4.g + t * uBaseShM5.g)));
    return uBaseLinM1.g + t * uBaseLinM2.g;
}
float baseB(float t) {
    if (t < uBaseToeAt.b)
        return uBaseToeM1.b + t * (uBaseToeM2.b + t * (uBaseToeM3.b + t * (uBaseToeM4.b + t * uBaseToeM5.b)));
    if (t > uBaseShAt.b)
        return uBaseShM1.b + t * (uBaseShM2.b + t * (uBaseShM3.b + t * (uBaseShM4.b + t * uBaseShM5.b)));
    return uBaseLinM1.b + t * uBaseLinM2.b;
}
// 与 kFsOut:4540 的 srgbEncode **严格互逆**（IEC 61966-2-1：线性 0.0031308 ↔
// 编码 0.04045，系数 12.92 / 1.055 / 2.4 / 0.055）。
// **禁止 pow(2.2) 近似**：暗部往返误差可达 9/255（项目实测，见 kFsOut 4530 注释）。
vec3 srgbDecode(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
    vec3 lo = c / 12.92;
    return mix(hi, lo, step(c, vec3(0.04045)));
}
vec3 srgbEncode(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    vec3 hi = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
    vec3 lo = 12.92 * c;
    return mix(hi, lo, step(c, vec3(0.0031308)));
}
// ── 期 B 说明：本 shader **刻意不做**工作空间域转换 ──────────────────────────
// 编辑器链的输入是**已成片 sRGB**（显示域），srgbDecode 得到 sRGB 线性；本链的线性
// 操作只有"曝光 × WB"（见下方 E2-2 往返），而拍照链路的曝光/WB 在 **RAW 域与预览
// 输入域**应用、根本不在工作空间里 ⇒ 这里不需要也不应该跟随工作空间。
// ⚠ 曾经的教训：加过一次 workToSrgb/srgbToWork，结果**只改变了色温/色调**的显示效果
//（实测 27~171 LSB），反而与拍照链路更不一致 ⇒ 已回滚（归档文档 §8.1）。
// ⚠ chainLuma 那处是**标量**往返：任何保白点矩阵对中性值恒等，本来也不需要转换。
// ── SG-1 柔光图取值：手写双线性 + 线性→显示域编码 ────────────────────────────
// 手写双线性：RGBA16F 无 LINEAR 过滤（X300 实测，见 makeTex）⇒ 直接 texture() 采
// 1/2 分辨率的柔光图会退化成 2×2 最近邻块，Screen 混合会把块边放大成可见方块。
// 编码：金字塔在**线性显示域**提取+模糊（与 halation 同域；emulsion 注释：在 gamma
// 编码值上模糊会把 bloom 变成 grey mud），而 Screen 混合按规格 §2 落在**显示域**
// ⇒ 混合前过一次 OETF。逐像素一次 pow，比"另起一个全分辨率编码 pass + 一张全分辨率
// 纹理（12.6MP ≈ 100MB 显存）"便宜得多（见报告 §降级选项）。
// clamp(0,1)：提取 h 在源 > 1（RGBA16F 未截断）时可 > 1，而 Screen 要求 b ≤ 1。
float glowFetch(vec2 uv) {
    vec2 t = uv / uGlowTexel - 0.5;
    vec2 i = floor(t);
    vec2 f = t - i;
    vec2 p0 = (i + 0.5) * uGlowTexel;
    vec2 p1 = p0 + uGlowTexel;
    float s00 = texture(uGlow, vec2(p0.x, p0.y)).r;
    float s10 = texture(uGlow, vec2(p1.x, p0.y)).r;
    float s01 = texture(uGlow, vec2(p0.x, p1.y)).r;
    float s11 = texture(uGlow, vec2(p1.x, p1.y)).r;
    float b = clamp(mix(mix(s00, s10, f.x), mix(s01, s11, f.x), f.y), 0.0, 1.0);
    return (b <= 0.0031308) ? 12.92 * b : 1.055 * pow(b, 1.0 / 2.4) - 0.055;
}
// 清晰度的 16-tap 环形 luma 模糊（2 环 × 8 方向 = 16 tap，**全手动展开**）：
// GLSL ES 无数组初始化器，且"循环内动态索引"在 Mali 上**静默失败**（ADR §6.1）
// ⇒ 不写循环、不用数组、不把数组当函数参数。对称环 ⇒ 平坦区与一阶梯度区的
// 环均值 = 中心值（清晰度在无细节处无副作用）。
// E2-12：uv 是**裁剪后**坐标（与 vUV0 同系），环偏移 uClarStep 也按输出尺寸标定
// ⇒ 每个 tap 先 `uv + 偏移` 再经 cropUV 映射到原图。**必须逐 tap 映射**（不能只在
// 中心映射一次）：旋转下"裁剪坐标 → 原图 UV"会置换轴，只映射中心会让 16 个 tap
// 采到错误的方位（旋转 90° 后清晰度环变成沿对角线）。
float blurLuma16(vec2 uv) {
    vec2 d1 = uClarStep;
    vec2 d2 = uClarStep * 2.0;
    vec3 w = vec3(0.299, 0.587, 0.114);
    float lb = 0.0;
    lb += dot(texture(uTex, cropUV(uv + vec2( d1.x, 0.0))).rgb, w);
    lb += dot(texture(uTex, cropUV(uv + vec2(-d1.x, 0.0))).rgb, w);
    lb += dot(texture(uTex, cropUV(uv + vec2(0.0,  d1.y))).rgb, w);
    lb += dot(texture(uTex, cropUV(uv + vec2(0.0, -d1.y))).rgb, w);
    lb += dot(texture(uTex, cropUV(uv + vec2( d1.x,  d1.y) * 0.70710678)).rgb, w);
    lb += dot(texture(uTex, cropUV(uv + vec2(-d1.x,  d1.y) * 0.70710678)).rgb, w);
    lb += dot(texture(uTex, cropUV(uv + vec2( d1.x, -d1.y) * 0.70710678)).rgb, w);
    lb += dot(texture(uTex, cropUV(uv + vec2(-d1.x, -d1.y) * 0.70710678)).rgb, w);
    lb += dot(texture(uTex, cropUV(uv + vec2( d2.x, 0.0))).rgb, w);
    lb += dot(texture(uTex, cropUV(uv + vec2(-d2.x, 0.0))).rgb, w);
    lb += dot(texture(uTex, cropUV(uv + vec2(0.0,  d2.y))).rgb, w);
    lb += dot(texture(uTex, cropUV(uv + vec2(0.0, -d2.y))).rgb, w);
    lb += dot(texture(uTex, cropUV(uv + vec2( d2.x,  d2.y) * 0.70710678)).rgb, w);
    lb += dot(texture(uTex, cropUV(uv + vec2(-d2.x,  d2.y) * 0.70710678)).rgb, w);
    lb += dot(texture(uTex, cropUV(uv + vec2( d2.x, -d2.y) * 0.70710678)).rgb, w);
    lb += dot(texture(uTex, cropUV(uv + vec2(-d2.x, -d2.y) * 0.70710678)).rgb, w);
    return lb * 0.0625;
}
// 把 blur 基准（未调整的显示域 luma）提升到与中心 c 同域：曝光/WB 是逐通道乘性
// ⇒ 标量 luma 上用 luma(WB 增益) 近似（一阶正确；二阶误差只影响清晰度的斜率标定，
// 不会像"不提升"那样把清晰度变成整体提亮）。饱和度**不改 luma**
//（mix(vec3(l), c, s) 的 luma 恒 = l）⇒ 不参与；对比度是 luma 域线性拉伸 ⇒ 参与。
// E2-6：局部曝光参与（mm = 中心像素的 mask）—— 不做这一项的话，"局部提亮 +
// 清晰度"会把清晰度变成局部整体提亮（与 E2-2 的 luma 提升同一类事故）。
// ⚠ 已知近似：16 个 tap 共用中心的 mm（逐 tap 算 mask = 16 次额外 mask 求值）；
//   羽化带内 mask 逐像素变化 ⇒ 该带内的清晰度标定有轻微误差。
float chainLuma(float x, float mm) {
    if (uExpWbOn > 0.5 || (uLocalEvOn > 0.5 && mm > 0.0)) {
        float g = exp2(uEv + uLocalEv * mm) *
                  dot(uWbGain, vec3(0.299, 0.587, 0.114));
        x = srgbEncode(srgbDecode(vec3(x)) * g).r;
    }
    if (uContrastOn > 0.5) x = clamp((x - 0.5) * uContrast + 0.5, 0.0, 1.0);
    return x;
}
// ── E2-6 局部 mask（ADR §5.7）：纯算术，无采样、无额外 pass、无额外显存 ────────
// uv 用 vUV0（与主采样同一坐标系：本 shader 无 flip ⇒ vUV0.y 与 Bitmap 行序同向，
// E2-7 的 UI 坐标可直接用"图像顶行 = 0"的归一化坐标）。
// 返回值：mask 内（d ≤ 1）= 1 → 羽化带（1 < d < 1+feather）平滑降到 0 → 外部 = 0。
// 径向：d = length(p / r)，r = (rx, ry) 由 CPU 侧按**像素宽高比**拆开
//（rx = [82]、ry = [82]×W/H）⇒ 像素空间等距 = **正圆**（E2-7 裁决 1；竖拍 3:4 不再变椭圆）。
// 推导：像素坐标 P = (u×W, v×H) ⇒ d² = (u/rx)² + (v/ry)² = (Pu/(rx·W))² + (Pv/(ry·H))²，
// 要让它 = (|P|/R)² 需 rx·W = ry·H = R ⇒ ry = rx·W/H（**不是** rx·H/W —— 反了就是椭圆）。
// 旧写法 `length(p / max(uLocalRadius, 1e-3))` 是 UV 空间的椭圆 —— 见 ADR §5.7 的公式原文。
// 线性：dir = (cos a, sin a) 是**条带法线方向** ⇒ d = |dot(p, dir)| / width 是到
// "过 center、垂直于 dir 的直线"的距离（即条带长轴垂直于 dir）。
// ⚠ GLSL 三禁：本函数无数组参数、无循环、无多行宏（ADR §6.1）。
float localMask(vec2 uv) {
    if (uLocalType < 0.5) return 0.0;
    vec2 p = uv - uLocalCenter;
    float d;
    if (uLocalType < 1.5) {
        d = length(p / max(uLocalRadius, vec2(1e-3)));
    } else {
        vec2 dir = vec2(cos(uLocalAngle), sin(uLocalAngle));
        d = abs(dot(p, dir)) / max(uLocalWidth, 1e-3);
    }
    return 1.0 - smoothstep(1.0, 1.0 + max(uLocalFeather, 1e-3), d);
}
// v0.10.67 dither 专用 hash：sin 版（`fract(sin(dot(p,…)))`）在 gl_FragCoord 大坐标
//（3072×4080）下 highp sin 精度不足，会产生**规律性图案** —— dither 一旦有规律就等于
// 没有。Dave Hoskins 的无 sin 整数风格 hash。E4-3：原 sin 版 hash 与其上的单八度
// value noise 只服务颗粒，已整体替换为下方 3 八度 simplex ⇒ 颗粒侧同型坑一并消除。
float dhash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
// ── E4-3 颗粒（granularity；darktable src/iop/grain.c 逐行移植）────────────
// ① 噪声源 = **3 八度 3D simplex**（Gustavson 的 permutation[256] + grad3[12] 整数表，
//    输出归一化 32·(n0+n1+n2+n3)），z 轴传八度序号 ⇒ 三阶在 3D 空间互不相关。
//    频率 f={0.4910,0.9441,1.7280} / 幅度 a={0.2340,0.7850,1.2150} 取 darktable 常量
//    （其注释：匹配真实颗粒扫描的功率谱）。旧的单八度 value noise（sin-hash +
//    smoothstep）频谱低通、格子边界 C1 不连续 ⇒ 观感是"块状糊"而不是"颗粒"。
// ② **严禁 sin-hash**：大坐标下 highp sin 精度不足会产生规律图案（本项目在 dither 上
//    踩过同型坑，见 dhash 注释）⇒ 用整数 permutation 表（GLSL ES 3.0 支持数组初始化
//    器与常量数组索引，同文件 kFsChromaDown 的 B3[5]/B3[i] 即先例）。
// ③ 尺度按**短边归一化**（ADR-11）：噪声坐标 = 像素/短边 ⇒ 预览（降采样）与导出
//    （全分辨率）的颗粒相对空间频率一致，与像素数无关。zoom 越大 ⇒ 频率越低 ⇒ 越粗。
// ④ 密度调制 = 相纸响应 LUT（CPU 侧预计算 128²，x 轴 = 噪声·strength·0.15，
//    y 轴 = 显示域 luma ≈ darktable 的 Lab L/100）⇒ shader 内**零** exp/log。
// 采样用 texelFetch 手写双线性而非 texture()：Mali 上 16F 的硬件 LINEAR 不可靠
//（项目多处实测，见 makeTex / LSC / kFsRcdChroma 注释）⇒ 依赖它会在 128 级台阶上
// 静默退化成最近邻。
const int kGrainPerm[256] = int[256](
    151, 160, 137, 91, 90, 15, 131, 13, 201, 95, 96, 53, 194, 233, 7, 225, 140, 36, 103, 30,
    69, 142, 8, 99, 37, 240, 21, 10, 23, 190, 6, 148, 247, 120, 234, 75, 0, 26, 197, 62,
    94, 252, 219, 203, 117, 35, 11, 32, 57, 177, 33, 88, 237, 149, 56, 87, 174, 20, 125, 136,
    171, 168, 68, 175, 74, 165, 71, 134, 139, 48, 27, 166, 77, 146, 158, 231, 83, 111, 229, 122,
    60, 211, 133, 230, 220, 105, 92, 41, 55, 46, 245, 40, 244, 102, 143, 54, 65, 25, 63, 161,
    1, 216, 80, 73, 209, 76, 132, 187, 208, 89, 18, 169, 200, 196, 135, 130, 116, 188, 159, 86,
    164, 100, 109, 198, 173, 186, 3, 64, 52, 217, 226, 250, 124, 123, 5, 202, 38, 147, 118, 126,
    255, 82, 85, 212, 207, 206, 59, 227, 47, 16, 58, 17, 182, 189, 28, 42, 223, 183, 170, 213,
    119, 248, 152, 2, 44, 154, 163, 70, 221, 153, 101, 155, 167, 43, 172, 9, 129, 22, 39, 253,
    19, 98, 108, 110, 79, 113, 224, 232, 178, 185, 112, 104, 218, 246, 97, 228, 251, 34, 242, 193,
    238, 210, 144, 12, 191, 179, 162, 241, 81, 51, 145, 235, 249, 14, 239, 107, 49, 192, 214, 31,
    181, 199, 106, 157, 184, 84, 204, 176, 115, 121, 50, 45, 127, 4, 150, 254, 138, 236, 205, 93,
    222, 114, 67, 29, 24, 72, 243, 141, 128, 195, 78, 66, 215, 61, 156, 180);
// darktable 的 perm[512]（= permutation[i & 255] 查表）等价形式 —— 表本身仍是 256 项。
int grainPerm(int i) { return kGrainPerm[i & 255]; }
// darktable `grad3[12][3]` 逐项照抄（12 个"面心"梯度）。
const vec3 kGrainGrad3[12] = vec3[12](
    vec3(1.0, 1.0, 0.0), vec3(-1.0, 1.0, 0.0), vec3(1.0, -1.0, 0.0), vec3(-1.0, -1.0, 0.0),
    vec3(1.0, 0.0, 1.0), vec3(-1.0, 0.0, 1.0), vec3(1.0, 0.0, -1.0), vec3(-1.0, 0.0, -1.0),
    vec3(0.0, 1.0, 1.0), vec3(0.0, -1.0, 1.0), vec3(0.0, 1.0, -1.0), vec3(0.0, -1.0, -1.0));
// darktable `_simplex_noise`（Perlin 1998 / Gustavson 型）。六路 if-else 顶点次序逐字照抄；
// floor() 对应其 FASTFLOOR（两者仅在**精确整数**输入上不同，噪声坐标不可能命中）。
float grainSimplex3(vec3 v) {
    const float F3 = 1.0 / 3.0;
    const float G3 = 1.0 / 6.0;
    float s = (v.x + v.y + v.z) * F3;
    vec3 ip = floor(v + vec3(s));
    float t = (ip.x + ip.y + ip.z) * G3;
    vec3 x0 = v - ip + vec3(t);
    int i1, j1, k1, i2, j2, k2;
    if (x0.x >= x0.y) {
        if (x0.y >= x0.z)      { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 1; k2 = 0; }
        else if (x0.x >= x0.z) { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 0; k2 = 1; }
        else                   { i1 = 0; j1 = 0; k1 = 1; i2 = 1; j2 = 0; k2 = 1; }
    } else {
        if (x0.y < x0.z)       { i1 = 0; j1 = 0; k1 = 1; i2 = 0; j2 = 1; k2 = 1; }
        else if (x0.x < x0.z)  { i1 = 0; j1 = 1; k1 = 0; i2 = 0; j2 = 1; k2 = 1; }
        else                   { i1 = 0; j1 = 1; k1 = 0; i2 = 1; j2 = 1; k2 = 0; }
    }
    vec3 x1 = x0 - vec3(float(i1), float(j1), float(k1)) + G3;
    vec3 x2 = x0 - vec3(float(i2), float(j2), float(k2)) + 2.0 * G3;
    vec3 x3 = x0 - 1.0 + 3.0 * G3;
    ivec3 ic = ivec3(ip) & 255;
    int gi0 = grainPerm(ic.x + grainPerm(ic.y + grainPerm(ic.z))) % 12;
    int gi1 = grainPerm(ic.x + i1 + grainPerm(ic.y + j1 + grainPerm(ic.z + k1))) % 12;
    int gi2 = grainPerm(ic.x + i2 + grainPerm(ic.y + j2 + grainPerm(ic.z + k2))) % 12;
    int gi3 = grainPerm(ic.x + 1 + grainPerm(ic.y + 1 + grainPerm(ic.z + 1))) % 12;
    float n0 = 0.0, n1 = 0.0, n2 = 0.0, n3 = 0.0;
    float t0 = 0.6 - dot(x0, x0);
    if (t0 > 0.0) { t0 *= t0; n0 = t0 * t0 * dot(kGrainGrad3[gi0], x0); }
    float t1 = 0.6 - dot(x1, x1);
    if (t1 > 0.0) { t1 *= t1; n1 = t1 * t1 * dot(kGrainGrad3[gi1], x1); }
    float t2 = 0.6 - dot(x2, x2);
    if (t2 > 0.0) { t2 *= t2; n2 = t2 * t2 * dot(kGrainGrad3[gi2], x2); }
    float t3 = 0.6 - dot(x3, x3);
    if (t3 > 0.0) { t3 *= t3; n3 = t3 * t3 * dot(kGrainGrad3[gi3], x3); }
    return 32.0 * (n0 + n1 + n2 + n3);
}
// darktable `_simplex_2d_noise`：f[]/a[] 逐一内联展开（**无数组、无循环** —— GLSL 三禁）。
// z = zoom，三个八度各占 z 轴的一个整数标签 ⇒ 互不相关。
float grainNoise(vec2 p, float z) {
    return grainSimplex3(vec3(p.x * 0.4910 / z, p.y * 0.4910 / z, 0.0)) * 0.2340
         + grainSimplex3(vec3(p.x * 0.9441 / z, p.y * 0.9441 / z, 1.0)) * 0.7850
         + grainSimplex3(vec3(p.x * 1.7280 / z, p.y * 1.7280 / z, 2.0)) * 1.2150;
}
// darktable `dt_lut_lookup_2d_1c` 逐字移植：x 轴 ±0.5 偏移、两端 clamp 到 SIZE-1、
// 索引上界 SIZE-2（末格靠 x_diff/y_diff = 1 外推）。
float grainLutFetch(vec2 s) {
    vec2 t = clamp(vec2((s.x + 0.5) * 127.0, s.y * 127.0), 0.0, 127.0);
    ivec2 i0 = min(ivec2(t), ivec2(126));
    vec2 f = t - vec2(i0);
    float l00 = texelFetch(uGrainLut, i0, 0).r;
    float l01 = texelFetch(uGrainLut, i0 + ivec2(1, 0), 0).r;
    float l10 = texelFetch(uGrainLut, i0 + ivec2(0, 1), 0).r;
    float l11 = texelFetch(uGrainLut, i0 + ivec2(1, 1), 0).r;
    float xy0 = mix(l00, l10, f.y);
    float xy1 = mix(l01, l11, f.y);
    return mix(xy0, xy1, f.x);
}
void main() {
    // 显示域直取：不 decode、不 encode（ADR-7）
    vec3 c = texture(uTex, cropUV(vUV0)).rgb;
    // ── E2-6 局部 mask（纯算术）────────────────────────────────────────────
    // uLocalOn 由 CPU 侧算（type>0 且 ev≠0 或 sat≠1）⇒ 默认（[79]=0）时 m 恒 0，
    // 下面的局部段与门控全部短路 ⇒ 输出逐位不变（零回归 / A/B 开关 = [79]）。
    float m = 0.0;
    if (uLocalOn > 0.5) m = localMask(vUV0);
    // ── E2-2 ① + E2-6 线性往返段（曝光 × WB × **局部曝光合并成一次**往返）──
    // 为什么合并：两者都是线性域乘性操作；两次往返 = 两次量化误差 + 双倍开销。
    // 门控 uExpWbOn（CPU 侧算）⇒ 默认恒等时整段跳过，输出逐位不变（零回归）。
    // E2-6：局部曝光按 mask 加权后与全局**相加**（totalEv = uEv + uLocalEv × m）
    // ⇒ 全局与局部在**线性域一次完成**，绝不出现第二次 decode/encode（ADR-7）。
    // m = 0 的像素在只有局部开启时也跳过往返 ⇒ 区域外逐位不变。
    // 必须用真 sRGB 分段曲线（srgbDecode/srgbEncode）：pow(2.2) 近似的暗部往返
    // 误差实测可达 9/255 —— 这是"画面发灰"的独立来源之一。
    if (uExpWbOn > 0.5 || (uLocalEvOn > 0.5 && m > 0.0)) {
        // 期 B 结论：本段**刻意保持 sRGB 线性域**，不跟随工作空间。
        // 实读 C++ 侧（`wbTempTint` → `wbCompGNorm` / `prepareRawCommon`）：拍照链路的
        // 曝光/WB 在 **RAW 域与预览输入域**应用，根本不在工作空间里；而曝光是标量乘法
        // ⇒ 在任何线性域等价。把本段改成 Rec.2020 线性只会**改变色温/色调的显示效果**
        //（实测 27~171 LSB = 8bit 下 10~67%），反而与拍照链路更不一致 ⇒ 保持原域。
        // 详见 `docs/reports/phaseB-old-domain-baseline-ARCHIVE.md` §8.1。
        vec3 lin = srgbDecode(c);
        lin *= exp2(uEv + uLocalEv * m) * uWbGain;
        c = srgbEncode(lin);
    }
    // ── E2-2 ② 对比度（显示域绕中灰 0.5 线性拉伸；1 = 恒等）────────────────
    if (uContrastOn > 0.5) c = clamp((c - 0.5) * uContrast + 0.5, 0.0, 1.0);
    // ── U-2 胶片曲线 [5..10] + [37][38]（display 域；**与 [4] 正交、排在 [4] 之后**）──
    // 为什么在 [4] 之后：`[4]` 决定"整体线性拉伸多少"，本段决定"拉伸后如何按 latitude
    // 重新分布"；两者互不读取对方的参数（斜率 c 不含 p.contrast，见 .cpp 的 ⑤）。
    // 为什么在饱和度**之前**：与 kFsFilm 的顺序一致（filmic → sat），且分区权重应当用
    // 重分布后的 luma。
    // 端点重映射（[37][38]）→ 差值曲线（[5..10]）；uCurveOn = 0 时整段不进（零回归）。
    if (uCurveOn > 0.5) {
        // 逐通道手写（GPU 三禁）：x < 中灰走 lo 段、x ≥ 中灰走 hi 段。
        // 两段都在中灰处取 t = 中灰 ⇒ 中灰不动（与拍照链路 filmic 的中灰锚定同语义）。
        vec3 t3 = vec3(c.r < kCurveGrey ? uCurveLo.x * c.r + uCurveLo.y
                                        : uCurveHi.x * c.r + uCurveHi.y,
                       c.g < kCurveGrey ? uCurveLo.x * c.g + uCurveLo.y
                                        : uCurveHi.x * c.g + uCurveHi.y,
                       c.b < kCurveGrey ? uCurveLo.x * c.b + uCurveLo.y
                                        : uCurveHi.x * c.b + uCurveHi.y);
        // clamp 到 [0,1]：分段仿射把"黑点以下/白点以上"推到界外 ⇒ 界外即硬端点
        //（黑点以下全黑、白点以上全白），同时保证喂给多项式的是合法定义域。
        t3 = clamp(t3, 0.0, 1.0);
        c = clamp(vec3(t3.r + curveR(t3.r) - baseR(t3.r),
                       t3.g + curveG(t3.g) - baseG(t3.g),
                       t3.b + curveB(t3.b) - baseB(t3.b)), 0.0, 1.0);
    }
    // ── E2-2 ③ + E2-6 饱和度（显示域绕 luma 插值；1 = 恒等）────────────────
    // 局部饱和度**合并进同一次 mix**：有效饱和度按 mask 在"全局值 ↔ 局部值"之间
    // 插值（mask 内 = 局部值，mask 外 = 全局值，边界随羽化平滑过渡）。
    // 数学上与 ADR §5.7 的 `mix(c, mix(vec3(l), c, uLocalSat), m)` 在**全局饱和度
    // 恒等时严格等价**；全局也开时本式语义更连贯（有效饱和度插值，而非两次叠加）。
    if (uSatOn > 0.5 || (uLocalSatOn > 0.5 && m > 0.0)) {
        // U-2 [20..22]：三档分区加权（与 kFsFilm 同式，推导见 uniform 声明处）。
        // 单滑杆场景（UI 同写三槽）⇒ 三档同值 ⇒ 加权和 = 该值 ⇒ 与单值语义等价。
        float l = luma(c);
        float wsv = exp(-l * 3.0);
        float whv = smoothstep(0.5, 1.0, l);
        float wmv = max(0.0, 1.0 - wsv - whv);
        float s = clamp(uSatShadow * wsv + uSatMid * wmv + uSatHigh * whv, 0.0, 2.0);
        if (uLocalSatOn > 0.5) s = s + (uLocalSat - s) * m;
        c = mix(vec3(l), c, s);
    }
    // ── E2-2 ④ 清晰度（显示域局部对比；0 = 关）────────────────────────────
    // 形式 = **加性 luma USM**（照 ADR §5.6 的 `c += (c - blur) * uClarity`）：
    // RGB 同加一个标量 ⇒ 只抬局部亮度，不逐通道拉伸。
    // ⚠ 加性**不是严格保色度**（严格保色度的是乘性 `c *= 1 + gain`，见 kFsOut
    //   的 sharpenLuma）；本段实际差值 |d| 通常 ≤ 数 LSB ⇒ 色度偏移可忽略。
    //   若要"零色度污染"，把本段换成 sharpenLuma 的乘性形式即可（一行改动）。
    // 差值限幅 ±0.25 拦高反差边缘过冲（标定旋钮）。
    // E2-6：blur 基准的域提升带上中心像素的 mask 值（邻域内 mask 近似不变 ⇒ 一阶
    // 正确；羽化带内 mask 逐像素变化 ⇒ 该带有轻微标定误差，属已知近似）。
    if (uClarity > 0.0) {
        float l0 = dot(c, vec3(0.299, 0.587, 0.114));
        float lb = chainLuma(blurLuma16(vUV0), m);
        c += clamp(l0 - lb, -0.25, 0.25) * uClarity;
    }
    // ── E2-11 影调分区（display 域 luma 分区**乘性**增益；全 0 = 整段跳过）──────
    // 为什么在清晰度**之后**：chainLuma 只需把 blur 基准提升过"往返 + 对比度"
    //（清晰度在影调分区之前执行）⇒ 本段不进入 chainLuma，E2-2 的域提升公式不用扩，
    // 少一处"新段忘记同步 ⇒ 清晰度变成整体提亮"的事故面。影调分区是逐像素乘性、
    // 与清晰度的加性差值互不干扰（乘积可交换），观感差异在移动端不可辨。
    // 为什么**乘性**（不是加性 `c += a·w`）：乘性保持色度比（加性抬亮会把暗部灰化、
    // 拉低饱和度 —— 与 clarity 段注释里那条同型）；且"色阶增益"的物理直觉就是乘法。
    // 端点行为：highlights=+1 ⇒ 纯白处 ×2（clamp 到 1）；blacks=-1 ⇒ 纯黑处 ×0。
    if (uToneOn > 0.5) {
        float l = dot(c, vec3(0.299, 0.587, 0.114));
        float s = 1.0 - l;
        float g = 1.0 + uHighlights * l * l + uShadows * s * s +
                  uWhites * l * l * l + uBlacks * s * s * s;
        c *= max(g, 0.0);
    }
    // ── E2-11 褪色（胶片褪色感 = 抬起黑场，白点保持 1）──────────────────────
    // c' = lift + c×(1−lift)：c=0 ⇒ lift（黑场抬起）、c=1 ⇒ 1（白点不动）。
    // lift = uFade × 0.25（标定旋钮）：fade=1 ⇒ 黑场抬到 64/255（明显灰雾），
    // fade=0.2 ⇒ 13/255（轻微）。与 [93] blacks 的区别：blacks 是**乘性**（保色度），
    // fade 是**加性抬黑**（发灰 = 褪色感本身），两者互补不重复。
    if (uFade > 0.0) {
        float lift = uFade * 0.25;
        c = lift + c * (1.0 - lift);
    }
    // ── SG-1 柔光（soft glow）[95..97]：显示域 **Screen** 混合 ─────────────────
    // 算法（规格 §2 ③）：out = 1 − (1−c)(1−b·strength)，b = 共享金字塔的单半径模糊
    // 高光（glowFetch 已把它编码到本段的显示域）。
    // 为什么 Screen 而不是加法（规格 §1 的判据）：加法是 halation 的混合方式（曝光
    // 阶段现象，能量叠加、过曝区更亮）；柔光是成片观感层，Screen 保高光不过曝
    //（c = 1 ⇒ out = 1，绝不溢出）。
    // 为什么落在**这里**：规格 §2 的顺序 —— 影调分区/褪色之后（柔光作用在"最终影调"
    // 上）、暗角与颗粒之前（§4）。门控 uGlowOn 由 CPU 侧算（[95] > 0 且链成功）
    // ⇒ 默认（[95] = 0）整段跳过，输出逐位不变。
    if (uGlowOn > 0.5) {
        float gb = glowFetch(cropUV(vUV0)) * uGlowStrength;
        c = 1.0 - (1.0 - c) * (1.0 - gb);
    }
    // ── E4-2 暗角（vignette，darktable vignette.c 正圆模型）────────────────
    // 域 = sRGB 显示域、乘法（ADR-7/ADR-9）：落在 LUT 之前 —— 与 darktable 的
    // 声明域（non-linear, RGB, display-referred）一致，且放 LUT 前可避免不同
    // LUT 把同一暗角渐变的形状改掉。
    // uv 用 vUV0（未 flip 的采样坐标）：暗角径向对称 ⇒ 与 flip 后**逐位等价**，
    // 同时让拍照/编辑器两条链路的这段代码**逐字相同**（E4-2 验收项）。
    if (uVigBrightness < 0.0) {
        vec2 pv = uVigScaleXY * abs(2.0 * vUV0 - 1.0);
        float cplen = length(pv);                  // shape=1：exp1=2, exp2=1/2
        float vw = clamp((cplen - uVigDscale) / uVigFscale, 0.0, 1.0);
        vw = 0.5 - 0.5 * cos(3.14159265 * vw);     // 余弦平滑（darktable 同式）
        c *= (1.0 + vw * uVigBrightness);
    }
    // ── U-1 [23][24] 分离色调 + [25] 单色（与 kFsFilm 4486-4493 / 4539 **同式**）──
    // 为什么这三行照抄 kFsFilm：用户 2026-10-11 指令"不统一的要统一掉" —— 效果对齐
    // 的最稳做法就是同一套数学（同系数、同分区权重定义、同先后序）。**刻意不重设计**：
    //   · 权重用 Rec.709（见顶部 luma()）= kFsFilm 的 `luma()`，换 601 就是"默默换一套"。
    //   · 域差异（**已知、刻意保留**）：kFsFilm 的 l 是**线性域** luma（ACEScg 工作
    //     空间），此处 c 已是 OETF 后的显示域 ⇒ 同一物理亮度下这里的 l 更大
    //     （线性 0.18 → 显示 0.46）、阴影权重 exp(-3l) 更小。不把 c decode 回线性再算：
    //     ADR-7 禁止本 shader 内任何**单程** EOTF（⑬f 的调用次数哨兵，多一次即红）
    //     ⇒ 按显示域口径实现，域差异在报告里给量化对照（tools 的 ⑰ 项实测）。
    // 位置 = **LUT 之前**（与拍照链路一致：kFsFilm 末端 → kFsOut 的 OETF → LUT）：
    // LUT 可能是黑白 LUT（自带通道混合）也可能是彩色 LUT，顺序不同结果不同。
    // 与 kFsFilm 的顺序也一致：split 先加偏移、mono 后去色（mono 会把 split 的偏移
    // 部分转成亮度变化 —— 这是拍照链路的既有行为，照抄，不改）。
    if (uSplit.x != 0.0 || uSplit.y != 0.0 || uMono > 0.0) {
        float l = luma(c);
        float ws = exp(-l * 3.0);
        float wh = smoothstep(0.5, 1.0, l);
        c += uSplit.x * ws * vec3(0.05, 0.02, -0.04)
           + uSplit.y * wh * vec3(-0.03, 0.0, 0.05);
        c = mix(c, vec3(luma(clamp(c, 0.0, 4.0))), uMono);
    }
    // M4：display 域 3D LUT（与 kFsOut 4547 同式；强度 0 ⇒ uLutOn=0 ⇒ 逐像素恒等）
    if (uLutOn > 0.5) c = mix(c, lutApply(c), uLutStrength);
    // ── E4-3 颗粒（granularity，darktable grain.c）────────────────────────
    // 坐标 = 像素/短边（uGrainNorm = 1/min(W,H)）⇒ 与像素数无关（ADR-11）；seed 是
    // **相对** x 偏移（成片按内容派生、预览/编辑器恒 0 ⇒ 取景稳定不闪）。密度调制 =
    // 相纸响应 LUT：x 轴 = 噪声·strength·0.15（GRAIN_LIGHTNESS_STRENGTH_SCALE），
    // y 轴 = 显示域 luma（≈ darktable 的 Lab L/100）。LUT 输出单位是 darktable 的
    // L 单位（100×）⇒ ×0.01 回到显示域。
    if (uGrainAmount > 0.0) {
        float strength = uGrainAmount * 0.01;
        vec2 gp = gl_FragCoord.xy * uGrainNorm + vec2(uGrainSeed, 0.0);
        float l = dot(c, vec3(0.299, 0.587, 0.114));
        c += grainLutFetch(vec2(grainNoise(gp, uGrainZoom) * strength * 0.15, l)) * 0.01;
    }
    // TPDF dither ±1LSB（与 kFsOut 4559 同式；RGBA8 量化前）
    c += (dhash(gl_FragCoord.xy) + dhash(gl_FragCoord.xy + vec2(37.0, 17.0)) - 1.0) / 255.0;
    oColor = vec4(clamp(c, 0.0, 1.0), 1.0);
})";

// ══ E4-5 halation（光晕）═════════════════════════════════════════════════════
// 物理：光穿过乳剂后在**片基**内全反射，重新曝光乳剂。CineStill 800T = Kodak
// Vision3 500T 撕掉 remjet 防光晕层 ⇒ 点光源周围出现红橙晕。乳剂对红光的散射距离
// 最长、蓝光最短 ⇒ "白 → 橙 → 红"的径向渐变（**通道相关半径**，不是均匀红雾）。
//
// 参考实现 = jie60517/emulsion `src/render/effects/library/halation.ts`（唯一有通道
// 扩散的公开实现；darktable `bloom.c` 是 Orton 效应、**不含通道扩散**，仅作对照）。
// 逐行对照见 docs/reports/editor-E4-film-params-DESIGN.md §3.2.1。
//
// 落点（规格 §3.2.2 = 本设计最关键的架构决定）：**3D LUT 之前、kFsFilm 之后的
// 线性显示域**。拍照链路 = passFilm 与 passOutput 之间；编辑器链路 = passStyle
// 之前（kFsStyle 是单 pass，无法做金字塔 ⇒ 金字塔独立成 pass 链，其"加法"落在
// kFsStyle 的 LUT 之前，与拍照链路同序）。
//
// ⚠ 输入域由 `uDecodeIn` 选择：拍照链路的 tex_work_ 已是**线性**显示域（kFsFilm
//   输出）⇒ 0；编辑器链路的 edit_tex_ 是**显示域 sRGB**（已成片 RGBA8）⇒ 1。
//   两条链路因此喂给金字塔的是**同一个线性量** ⇒ 同参数下逐位可比（验收判据 4）。
//   输出域跟随输入域（拍照 = 线性、编辑器 = 显示域），使 kFsOut/kFsStyle 各自
//   拿到与自己域约定一致的结果，**两侧下游代码零改动**。
//
// ⚠ 移动 GPU 三禁的逐条规避（规格 §3.2.6）：
//   · 数组作函数参数：**无**。5 级 halo 用 5 个独立 sampler2D，权重用 5 个独立 vec3。
//   · 循环内动态索引：**无循环、无数组**。所有 tap 与 5 级合成**全部手动展开**。
//   · 多行宏：**无宏**。
//   · 附加（GLSL ES 无数组初始化器，仓库两次踩坑）：无任何 `float[5](...)` 写法。
//   降采样/模糊的 tap 位置是**编译期常量表达式**（照 sharpenLuma 的手写风格）。

// 高光提取 + 盒式降采样（同时服务 kFsHaloThr 与 4 次 kFsHaloDown —— 数学逐字相同，
// 只有"是否过阈值/是否 sRGB 解码"两个门控不同；共用一份 = 少一个 program 与一份
// 硬失败检查，**pass 数与 GPU 行为不变**）。输出 = 源分辨率的 1/2。
// 为什么手写 2×2 盒式：emulsion 此 pass 靠硬件 LINEAR 过滤完成降采样，而 **X300 的
// RGBA16F 无线性过滤**（项目多处手写，见 makeTex / kFsRcdChroma 注释）⇒ 4 采样/px。
const char* kFsHaloThr = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uSrc;
uniform vec2 uTexel;      // 1/源宽, 1/源高（CPU 侧算好，shader 内不做除法）
uniform float uDecodeIn;  // 1 = 源是显示域 sRGB（编辑器链路）；0 = 已是线性
uniform float uThrOn;     // 1 = 本 pass 做高光提取；0 = 纯降采样（kFsHaloDown）
uniform float uThrLin;    // 线性显示域阈值 = srgbToLinear([63])
uniform float uKnee;      // max(0.02, uThrLin·0.55)（emulsion 同式，硬编码不给槽位）
// SG-1 柔光：1 = 本 pass 做**柔光提取**（标量灰度；与 halation 的彩色提取互斥）。
// 复用同一个 program 而不是新写一个：两者的 2×2 盒式降采样 + 域处理逐字相同，
// 只有"提取公式"一行不同（规格 §5 的共享金字塔）。
uniform float uGlowOn;
vec3 srgbDecode(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
    vec3 lo = c / 12.92;
    return mix(hi, lo, step(c, vec3(0.04045)));
}
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
void main() {
    vec2 uv = vUV0;
    vec3 s = texture(uSrc, uv + uTexel * vec2(-1.0, -1.0)).rgb;
    s += texture(uSrc, uv + uTexel * vec2(1.0, -1.0)).rgb;
    s += texture(uSrc, uv + uTexel * vec2(-1.0, 1.0)).rgb;
    s += texture(uSrc, uv + uTexel * vec2(1.0, 1.0)).rgb;
    s *= 0.25;
    // 高光提取在**线性光**里做（emulsion 注释：blurring gamma-encoded values
    // spreads the wrong amount of energy and turns bloom into grey mud）。
    vec3 lin = (uDecodeIn > 0.5) ? srgbDecode(s) : s;
    if (uGlowOn > 0.5) {
        // ── SG-1 柔光提取（规格 §2 ①）────────────────────────────────────────
        // h = max(0, luma − thr)/(1 − thr)，**标量**（三通道同值）。
        // 为什么标量：规格 §1 的判据 —— 柔光是**通道无关**的（三通道同半径、
        // 无通道扩散），且"发白外扩"正来自"提取量无通道差异"（彩色提取 × Screen
        // 只会把高光染成它自己的颜色，不会发白）。halation 的彩色提取（下方
        // `lin * w`，保留色度 + 逐通道权重）与之相反，两者**必须不同**才可分辨。
        // 分母 max(..., 0.001) 防 thr = 1 时 0/0（CPU 侧已 clamp 到 0.999，纯防御）。
        float h = max(0.0, luma(lin) - uThrLin) / max(1.0 - uThrLin, 0.001);
        oColor = vec4(h, h, h, 1.0);
        return;
    }
    float w = 1.0;
    if (uThrOn > 0.5) {
        w = smoothstep(uThrLin - uKnee, uThrLin + uKnee, luma(lin));
    }
    oColor = vec4(lin * w, 1.0);
})";

// 逐级可分离高斯（先横后纵，共 10 次 draw）。权重与偏移逐字照抄 emulsion：
// 0.227027·c + 0.3162162·(c±1.3846) + 0.0702703·(c±3.2308)（归一化 = 1）。
// 等效 σ = 1.637 texel @ 该级（方差 2.6793，见规格 §3.2.3 的半径映射表）。
// uScale：tmp 只有**最大级**（1/2）的尺寸（省 5 张逐级 tmp = 33MB），处理更小的
// 级时把归一化 UV 缩到已写入的左下角子矩形上。level0 时 uScale = (1,1)。
const char* kFsHaloBlur = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uSrc;
uniform vec2 uDir;    // (1/w, 0) 或 (0, 1/h)（当前级 texel 步长）
uniform vec2 uScale;  // 当前级尺寸 / tmp 尺寸
void main() {
    vec2 uv = vUV0 * uScale;
    vec3 s = texture(uSrc, uv).rgb * 0.227027;
    s += texture(uSrc, uv + uDir * 1.3846153846).rgb * 0.3162162162;
    s += texture(uSrc, uv - uDir * 1.3846153846).rgb * 0.3162162162;
    s += texture(uSrc, uv + uDir * 3.2307692308).rgb * 0.0702702703;
    s += texture(uSrc, uv - uDir * 3.2307692308).rgb * 0.0702702703;
    oColor = vec4(s, 1.0);
})";

// 合成（全分辨率，6 采样/px）：`added = Σ halo_i·w_i · tint · amount`，
// `sum = src + added`（**线性域加法** —— 真实 halation 是曝光阶段现象）。
//
// 通道相关半径 = 5 个权重向量的**逐通道不同峰值**（CPU 侧按 [64] 与乳剂常数
// SPREAD = {1.0, 0.6, 0.32} 算好）—— 这一项就是"白→橙→红"的成因，任何 tint
// 滑杆都模仿不出来（emulsion 原注释）。
//
// ⚠ shoulder **不在这里做**：规格 §3.2.1 ⑦ / §3.2.4 —— 拍照链路的 kFsOut 已有
//   `highlightRolloff`（同 knee 0.80、同指数形式），halo 加光交给它压缩。编辑器
//   链路的 kFsStyle 没有滚降（输入已成片）⇒ 两侧在 `sum > 0.8`（线性）的像素上
//   存在**已知且刻意**的差异，报告逐条列明（验收判据 4）。
//
// ⚠ `added = 0` 的像素（halo 未到达）仍会经 RGBA16F 往返一次 ⇒ 有半精度量化，
//   规格 §3.2.4 要求实测确认不可见。**[62] = 0 时整链旁路 ⇒ 逐位零回归不受影响。**
const char* kFsHaloMix = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uSrc;    // 全分辨率原图（与 uDecodeIn 同域）
uniform highp sampler2D uHalo0;
uniform highp sampler2D uHalo1;
uniform highp sampler2D uHalo2;
uniform highp sampler2D uHalo3;
uniform highp sampler2D uHalo4;
uniform vec3 uW0;   // 每级的逐通道权重（CPU 侧算好，5 个独立 uniform —— 三禁规避）
uniform vec3 uW1;
uniform vec3 uW2;
uniform vec3 uW3;
uniform vec3 uW4;
uniform vec3 uTint;      // (1, 0.24+0.34·warmth, 0.44−0.22·warmth)
uniform float uAmount;   // [62] 0..1
uniform float uDecodeIn; // 1 = uSrc 是显示域（编辑器链路）
vec3 srgbDecode(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
    vec3 lo = c / 12.92;
    return mix(hi, lo, step(c, vec3(0.04045)));
}
vec3 srgbEncode(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    vec3 hi = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
    vec3 lo = 12.92 * c;
    return mix(hi, lo, step(c, vec3(0.0031308)));
}
void main() {
    vec2 uv = vUV0;
    vec3 halo = texture(uHalo0, uv).rgb * uW0;
    halo += texture(uHalo1, uv).rgb * uW1;
    halo += texture(uHalo2, uv).rgb * uW2;
    halo += texture(uHalo3, uv).rgb * uW3;
    halo += texture(uHalo4, uv).rgb * uW4;
    vec3 added = halo * uTint * uAmount;
    vec3 s = texture(uSrc, uv).rgb;
    vec3 lin = (uDecodeIn > 0.5) ? srgbDecode(s) : s;
    vec3 sum = lin + added;
    oColor = vec4((uDecodeIn > 0.5) ? srgbEncode(sum) : sum, 1.0);
})";

// ══ SG-1 柔光（soft glow）合成 ═══════════════════════════════════════════════
// 共享金字塔（kFsHaloThr 的 glow 模式 → 4 次降采样 → 10 次可分离高斯）之上只多这
// **一个 1/2 分辨率 pass**：把 5 级加权成一张标量柔光图 b（规格 §5 的"少数 pass"）。
//
// 与 kFsHaloMix 的区别（**这就是柔光与光晕可分辨的根源**，规格 §1）：
//   · 权重是**标量**（uS0..uS4，三通道同一个值）⇒ 三通道同半径 = 通道无关；
//     halation 是 5 个 **vec3**（逐通道峰值不同）⇒ 通道相关"白→橙→红"。
//   · 输出 **1/2 分辨率、单通道**（R 通道 = 线性显示域模糊高光），Screen 混合在
//     kFsOut/kFsStyle 的显示域段内做（那里才拿得到"当前的 c"）。
//   · **不做加法、不 tint、不 shoulder** —— Screen 混合保高光不过曝（规格 §2 ③）。
//
// 为什么输出 1/2 分辨率而不是全分辨率：b 是"半径 ≥ 数十像素的高斯模糊"信号，1/2
// 分辨率远超其奈奎斯特需求（质量无损），而省下一张全分辨率 RGBA16F（12.6MP 时
// ≈ 100MB 显存）。kFsOut/kFsStyle 侧用手写双线性上采样（RGBA16F 无 LINEAR 过滤，
// 见 makeTex —— 直接 texture() 会在 Screen 后露出 2×2 方块）。
// 目标纹理 = halation 的 tex_halo_tmp_（横向模糊的中间量，blur 跑完即空闲）⇒
// **柔光零额外纹理分配**（报告"共享金字塔省了多少"一节）。
//
// ⚠ 三禁：无数组作函数参数、无循环（5 级手动展开）、无多行宏；权重是 5 个独立
//   uniform 而不是 `float[5]`（GLSL ES 无数组初始化器，仓库两次踩坑）。
const char* kFsGlowSum = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uG0;
uniform highp sampler2D uG1;
uniform highp sampler2D uG2;
uniform highp sampler2D uG3;
uniform highp sampler2D uG4;
uniform float uS0;   // 每级的**标量**权重（CPU 侧按 [96] 单半径高斯算好，和为 1）
uniform float uS1;
uniform float uS2;
uniform float uS3;
uniform float uS4;
void main() {
    float b = texture(uG0, vUV0).r * uS0;
    b += texture(uG1, vUV0).r * uS1;
    b += texture(uG2, vUV0).r * uS2;
    b += texture(uG3, vUV0).r * uS3;
    b += texture(uG4, vUV0).r * uS4;
    oColor = vec4(b, b, b, 1.0);
})";

// 实时直方图采样（M3）：胶片输出下采样到 256×96 RGBA8，OETF 与 kFsOut 一致
// —— 直方图必须是"显示域、用户实际看到"的分布。每次读回 24KB（UI 侧节流）。
// M4：与 kFsOut 同样走 LUT，否则选了 LUT 之后直方图/斑马纹会与画面不符。
const char* kFsHist = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform highp sampler3D uLut;
uniform float uLutOn;
uniform float uLutStrength;
uniform vec3 uLutDomainMin;
uniform vec3 uLutDomainInv;
uniform float uLutSize;
// v0.7.3：统计通道 —— 0 = luma（UI 直方图，用户看的亮度分布）；
// 1 = max 通道（filmic 自动 EV 的判据，见 measureSceneStats）。
uniform float uStatCh;
// v0.10.27：统计编码 —— 0 = 显示域 sRGB（UI 直方图，与画面同域）；
// 1 = **对数域** max 通道（自动 EV 判据专用）。为什么必须有 1：显示域编码前
// clamp(c,0,1) 把线性 ≥1.0 的强光源（窗/灯，占比常 <1%）全部并进顶桶 —— P99 撞顶
// 读回恒 1.0，白点判据对高光**完全失明**（真机实证：auto 模式 whiteEv 恒钉 2.438
// 下限、成片高光平顶白墙 8%）。对数域 [2^-12, 2^8] 线性映射 [0,1]，8bit readback
// 每级 0.078EV，高光段不再拥挤；暗部端撞底的读数由 black 判据的 −7.65 物理下限兜底。
uniform float uStatMode;
vec3 lutTetra(vec3 f, vec3 c000, vec3 c100, vec3 c010, vec3 c001, vec3 c110,
              vec3 c101, vec3 c011, vec3 c111) {
    if (f.x >= f.y) {
        if (f.y >= f.z) return (1.0 - f.x) * c000 + (f.x - f.y) * c100 + (f.y - f.z) * c110 + f.z * c111;
        if (f.x >= f.z) return (1.0 - f.x) * c000 + (f.x - f.z) * c100 + (f.z - f.y) * c101 + f.y * c111;
        return (1.0 - f.z) * c000 + (f.z - f.x) * c001 + (f.x - f.y) * c101 + f.y * c111;
    }
    if (f.z >= f.y) return (1.0 - f.z) * c000 + (f.z - f.y) * c001 + (f.y - f.x) * c011 + f.x * c111;
    if (f.z >= f.x) return (1.0 - f.y) * c000 + (f.y - f.z) * c010 + (f.z - f.x) * c011 + f.x * c111;
    return (1.0 - f.y) * c000 + (f.y - f.x) * c010 + (f.x - f.z) * c110 + f.z * c111;
}
vec3 lutApply(vec3 c) {
    vec3 t = clamp((c - uLutDomainMin) * uLutDomainInv, 0.0, 1.0) * (uLutSize - 1.0);
    vec3 i0 = floor(t);
    vec3 f = t - i0;
    ivec3 i = ivec3(i0);
    ivec3 mx = ivec3(int(uLutSize) - 1);
    vec3 c000 = texelFetch(uLut, clamp(i, ivec3(0), mx), 0).rgb;
    vec3 c100 = texelFetch(uLut, clamp(i + ivec3(1, 0, 0), ivec3(0), mx), 0).rgb;
    vec3 c010 = texelFetch(uLut, clamp(i + ivec3(0, 1, 0), ivec3(0), mx), 0).rgb;
    vec3 c001 = texelFetch(uLut, clamp(i + ivec3(0, 0, 1), ivec3(0), mx), 0).rgb;
    vec3 c110 = texelFetch(uLut, clamp(i + ivec3(1, 1, 0), ivec3(0), mx), 0).rgb;
    vec3 c101 = texelFetch(uLut, clamp(i + ivec3(1, 0, 1), ivec3(0), mx), 0).rgb;
    vec3 c011 = texelFetch(uLut, clamp(i + ivec3(0, 1, 1), ivec3(0), mx), 0).rgb;
    vec3 c111 = texelFetch(uLut, clamp(i + ivec3(1, 1, 1), ivec3(0), mx), 0).rgb;
    return lutTetra(f, c000, c100, c010, c001, c110, c101, c011, c111);
}
// 与 kFsOut 同一传递函数（v0.10.23：真 sRGB）—— 直方图/斑马纹必须与用户看到的
// 画面同域，否则"直方图与画面不符"会重现。
vec3 srgbEncode(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    vec3 hi = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
    vec3 lo = 12.92 * c;
    return mix(hi, lo, step(c, vec3(0.0031308)));
}
// 期 B：与 kFsOut 的 workToSrgb/gamutFit 同式（矩阵同源，见那里的说明）。直方图/斑马纹
// 必须与画面同域 —— 所以**连域外色压缩也要一样**：只用 workToSrgb 的话，直方图会把
// 被硬切过的值与画面里被压缩过的值算成两回事。
vec3 workToSrgb(vec3 c) {
    return vec3(dot(c, vec3(1.6604910021, -0.5876411388, -0.0728498633)),
                dot(c, vec3(-0.1245504745, 1.1328998971, -0.0083494226)),
                dot(c, vec3(-0.0181507634, -0.1005788980, 1.1187296614)));
}
vec3 gamutFit(vec3 c) {
    float mn = min(min(c.r, c.g), c.b);
    if (mn >= 0.0) return c;
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    // ⚠ 整色无正亮度（l ≤ 0）时，朝中性轴去饱和救不回来：t=1 的结果就是 vec3(l)，
    //   而 l 本身为负 ⇒ 必须直接压到黑（数值自检抓到过这个边界）。
    if (l <= 1e-8) return vec3(0.0);
    float denom = mn - l;
    if (denom > -1e-8) return vec3(0.0);
    return mix(c, vec3(l), clamp(mn / denom, 0.0, 1.0));
}
vec3 workToDisplayLinear(vec3 c) { return gamutFit(workToSrgb(c)); }
void main() {
    vec3 raw = texture(uTex, vUV0).rgb;
    vec3 c = srgbEncode(workToDisplayLinear(raw));
    if (uLutOn > 0.5) c = mix(c, lutApply(c), uLutStrength);
    // max 与 luma 都在显示域；sRGB 编码单调 ⇒ CPU 侧按 sRGB EOTF 折回线性后仍是
    // 同一像素的 max 通道值，不是近似。
    float l;
    if (uStatMode > 0.5) {
        // 对数域：enc = (log2(m) + 12) / 20，线性 [2^-12, 2^8] → [0,1]（CPU 侧同式反折）。
        // 取**编码前**的原始 max 通道 —— 这是 uStatMode 存在的全部意义。
        float m = max(max(raw.r, max(raw.g, raw.b)), 1.52587890625e-5);
        l = (log2(m) + 12.0) / 20.0;
    } else {
        l = (uStatCh > 0.5) ? max(max(c.r, c.g), c.b)
                            : dot(c, vec3(0.2126, 0.7152, 0.0722));
    }
    oColor = vec4(l, l, l, 1.0);
})";

// M4-7 局部色调映射的低频亮度图：从 src 做 8×8 盒式平均 → 显示域 luma。
// **不能用 kFsHist 的单点采样**：32×24 的每格覆盖源图约 128×128 像素，单点若恰好
// 落在深色物体上，整格被判为"暗"而满额提亮 —— 成片上就是亮斑（真机反馈）。
// 64 样本 × 768 输出像素 ≈ 49K 次采样/帧，成本可忽略。
const char* kFsLocal = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform vec2 uStep;  // 一个输出格在源纹理 UV 上的跨度 = 1/输出尺寸
void main() {
    vec3 acc = vec3(0.0);
    for (int y = 0; y < 8; ++y) {
        for (int x = 0; x < 8; ++x) {
            vec2 o = (vec2(float(x), float(y)) + 0.5) / 8.0 - 0.5;
            acc += texture(uTex, vUV0 + o * uStep).rgb;
        }
    }
    // 注意：这里**保留 pow(1/2.2)**（v0.10.23 只把 kFsOut / kFsHist / 统计折回换成真
    // sRGB）：这张低频图是 local_tone 的**判据域**，uLocalKnee/uLocalKneeHi 是在 2.2 域
    // 标定的，换编码会移动 knee 的生效点（暗部两域差可达 1.6×）。local_tone 当前默认关
    // （日志 lt=0.00），等它启用时连 knee 一起重新标定 —— 不要单独改这一处。
    vec3 c = pow(clamp(acc / 64.0, 0.0, 1.0), vec3(1.0 / 2.2));
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    oColor = vec4(l, l, l, 1.0);
})";

// ── 局部影调（tone equalizer，2026-10-20，darktable 口径）──────────────────
// 问题：全局曲线 + 全局 AE 下，"主体亮"与"天空蓝/高光有层次"互斥（真机
// 170249/170748 反复验证）。业界做法是局部影调：按**引导 mask 的亮度分区**做
// 曝光补偿（darktable `tone equalizer`）。
//
// 管线（全部在 scene 线性域、1/4 分辨率跑 mask）：
//   ① kFsTeqDown    scene → I = log2(luma / 白点)（EV，曝光无关）
//   ② kFsTeqBox×4   可分离盒式（H/V 各两次）：mean(I)、mean(I²) → 再平滑 a/b
//   ③ kFsTeqCoef    自引导 guided filter 系数 a = var/(var+eps)、b = (1−a)·mean
//   ④ kFsTeqGain    q = a·I + b（平滑后的曝光图）→ 9 分区曲线 → 增益（EV）
//   ⑤ kFsTeqApply   全分辨率采样增益（双线性）→ rgb × 2^gain
// mask 用**对数域**（I 是 EV）⇒ 天然曝光无关（darktable eigf 的动机）。
const char* kFsTeqDown = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform vec2 uSrcTexel;
void main() {
    // 4×4 盒式下采样（1/4 分辨率），取 Rec.2020 线性 luma
    float acc = 0.0;
    for (int y = 0; y < 4; ++y) {
        for (int x = 0; x < 4; ++x) {
            vec2 o = (vec2(float(x), float(y)) - 1.5) * uSrcTexel;
            vec3 c = texture(uTex, vUV0 + o).rgb;
            acc += dot(c, vec3(0.2126, 0.7152, 0.0722));
        }
    }
    float l = max(acc / 16.0, 1e-6);
    float e = log2(l);                        // EV（相对 scene 白点 1.0）
    oColor = vec4(e, e * e, 0.0, 1.0);        // (I, I²) —— box pass 直接平滑
})";

const char* kFsTeqBox = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform vec2 uTexel;
uniform int uDir;      // 0 = 水平，1 = 垂直
uniform int uRadius;
void main() {
    vec2 d = (uDir == 0) ? vec2(uTexel.x, 0.0) : vec2(0.0, uTexel.y);
    vec2 acc = vec2(0.0);
    for (int i = -uRadius; i <= uRadius; ++i) {
        acc += texture(uTex, vUV0 + d * float(i)).rg;
    }
    oColor = vec4(acc / float(2 * uRadius + 1), 0.0, 1.0);
})";

const char* kFsTeqCoef = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uMean;   // RG = mean(I), mean(I²)
uniform float uEps;
void main() {
    vec2 m = texture(uMean, vUV0).rg;
    float var = max(m.g - m.r * m.r, 0.0);
    float a = var / (var + uEps);
    oColor = vec4(a, (1.0 - a) * m.r, 0.0, 1.0);   // a, b（自引导）
})";

const char* kFsTeqGain = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uI;      // I = log2 luma（EV）
uniform highp sampler2D uAB;     // 平滑后的 a, b
uniform float uCurve[9];         // −8…0 EV 九分区的曝光补偿（EV）
void main() {
    // 曝光图 q = a·I + b（guided filter 输出，仍在 EV 域）
    float q = texture(uAB, vUV0).r * texture(uI, vUV0).r + texture(uAB, vUV0).g;
    // 映射到 [0,8]：0 = 黑（−8EV），8 = 白点（0EV）
    float x = clamp(q + 8.0, 0.0, 8.0);
    // 固定展开的线性插值（不用 uniform 数组的动态索引 —— 部分 ES 驱动对其支持
    // 不一致，且这里只有 8 段，展开成本可忽略）
    float g = uCurve[0];
    g = mix(g, uCurve[1], clamp(x - 0.0, 0.0, 1.0));
    g = mix(g, uCurve[2], clamp(x - 1.0, 0.0, 1.0));
    g = mix(g, uCurve[3], clamp(x - 2.0, 0.0, 1.0));
    g = mix(g, uCurve[4], clamp(x - 3.0, 0.0, 1.0));
    g = mix(g, uCurve[5], clamp(x - 4.0, 0.0, 1.0));
    g = mix(g, uCurve[6], clamp(x - 5.0, 0.0, 1.0));
    g = mix(g, uCurve[7], clamp(x - 6.0, 0.0, 1.0));
    g = mix(g, uCurve[8], clamp(x - 7.0, 0.0, 1.0));
    oColor = vec4(g, 0.0, 0.0, 1.0);
})";

const char* kFsTeqApply = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;    // scene 线性（全分辨率）
uniform highp sampler2D uAB;     // 1/4 分辨率 guided 系数（a, b）
uniform vec2 uGainSize;          // AB 图分辨率（手写双线性：RGBA16F 无硬件过滤）
uniform float uCurve[9];         // −8…0 EV 九分区的曝光补偿（EV）
// ── v0.11.95：**guided upsample**（原来是"1/4 算 gain 再双线性上采样 gain"）──
// 旧做法把"分区的结论"（标量 gain）上采样，边缘信息在 1/4 分辨率就被抹平了 ——
// 杯口/窗框这类高对比边缘会留下一条几十像素的**灰带/光晕**（darktable tone equalizer
// 的文档里明确说过：mask 必须上采样 a/b 而不是结果）。
// 新做法：a/b 上采样，q = a·I_full + b 用**全分辨率**的 I 算 ⇒ 边缘由原图决定。
float luma(vec3 c) { return dot(c, vec3(0.2627, 0.6780, 0.0593)); }
float curveAt(float q) {
    float x = clamp(q + 8.0, 0.0, 8.0);
    float g = uCurve[0];
    g = mix(g, uCurve[1], clamp(x - 0.0, 0.0, 1.0));
    g = mix(g, uCurve[2], clamp(x - 1.0, 0.0, 1.0));
    g = mix(g, uCurve[3], clamp(x - 2.0, 0.0, 1.0));
    g = mix(g, uCurve[4], clamp(x - 3.0, 0.0, 1.0));
    g = mix(g, uCurve[5], clamp(x - 4.0, 0.0, 1.0));
    g = mix(g, uCurve[6], clamp(x - 5.0, 0.0, 1.0));
    g = mix(g, uCurve[7], clamp(x - 6.0, 0.0, 1.0));
    g = mix(g, uCurve[8], clamp(x - 7.0, 0.0, 1.0));
    return g;
}
void main() {
    vec3 c = texture(uTex, vUV0).rgb;
    // 手写双线性（X300 的 RGBA16F 只能 NEAREST，直接 texture() 会出 4×4 块）
    vec2 p = vUV0 * uGainSize - 0.5;
    vec2 p0 = floor(p);
    vec2 f = p - p0;
    ivec2 imax = ivec2(uGainSize) - 1;
    ivec2 i0 = clamp(ivec2(p0), ivec2(0), imax);
    ivec2 i1 = clamp(ivec2(p0) + ivec2(1), ivec2(0), imax);
    vec2 ab00 = texelFetch(uAB, ivec2(i0.x, i0.y), 0).rg;
    vec2 ab10 = texelFetch(uAB, ivec2(i1.x, i0.y), 0).rg;
    vec2 ab01 = texelFetch(uAB, ivec2(i0.x, i1.y), 0).rg;
    vec2 ab11 = texelFetch(uAB, ivec2(i1.x, i1.y), 0).rg;
    vec2 ab = mix(mix(ab00, ab10, f.x), mix(ab01, ab11, f.x), f.y);
    // q 用**全分辨率**的 luma 算 ⇒ 高对比边缘不会被 1/4 分辨率抹平
    float I = log2(max(luma(c), 1e-6));
    float q = ab.x * I + ab.y;
    // ── v0.12.13：**接近白点的分区不压**（灰带的正确解法）──────────────────
    // v0.12.11 试过"按相对局部均值的偏离（I − q）减免"，实测无效：反光块尺寸从
    // 30px（灯珠）到 500px（窗）不等，而 q 的窗口是固定的 64px ⇒ 块内部 q ≡ I ⇒
    // excess 恒 ≈ 0。**这个判据依赖尺度，而尺度随场景变，固定半径不可能通用**。
    // v0.12.13 换成不依赖尺度的物理判据：**该分区是否已经接近白点**。
    // ── v0.12.20：这里曾经叠了三层"事后开关"（v0.12.13 wTop → v0.12.17 让路 →
    //    v0.12.19 拐点下移），全部删除。原因：**压深放错了 EV 分区**，而事后开关
    //    只是在补那个错位 —— 每补一次就把另一类高光弄坏（反光点 → 绿植背景 → 白云）。
    // 正位：云/镜面反光的 q ≈ −0.5（落在白点档），天空主体 q ≈ −2.0（落在第 6 档）。
    //    压深只要给第 6 档，白点档保持 0，就**天然**满足"云不压、天空压"，
    //    不需要任何权重函数。见 setTeqCurveFromAe 的 v0.12.20 段。
    float g = curveAt(q);          // ← v0.12.21 补回：v0.12.20 换注释段时被误删
    oColor = vec4(c * exp2(g), 1.0);
})";

GLuint compileShader(GLenum type, const char* src) {
  GLuint sh = glCreateShader(type);
  glShaderSource(sh, 1, &src, nullptr);
  glCompileShader(sh);
  GLint ok = 0;
  glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
  if (!ok) {
    char buf[512];
    glGetShaderInfoLog(sh, sizeof(buf), nullptr, buf);
    Logger::instance().log(LogLevel::kError, "engine", "render.shader_error",
                           "{\"log\":\"" + jsonEscape(buf) + "\"}");
    glDeleteShader(sh);
    return 0;
  }
  return sh;
}

GLuint linkProgram(GLuint vs, GLuint fs) {
  GLuint prog = glCreateProgram();
  glAttachShader(prog, vs);
  glAttachShader(prog, fs);
  glLinkProgram(prog);
  GLint ok = 0;
  glGetProgramiv(prog, GL_LINK_STATUS, &ok);
  if (!ok) {
    char buf[512];
    glGetProgramInfoLog(prog, sizeof(buf), nullptr, buf);
    Logger::instance().log(LogLevel::kError, "engine", "render.link_error",
                           "{\"log\":\"" + jsonEscape(buf) + "\"}");
    glDeleteProgram(prog);
    return 0;
  }
  return prog;
}

// IEEE754 半精度 → 单精度（诊断读回用）
float halfToFloat(unsigned short h) {
  const unsigned int sign = (h >> 15) & 1u;
  const unsigned int e = (h >> 10) & 0x1fu;
  const unsigned int m = h & 0x3ffu;
  float v;
  if (e == 0) {
    v = std::ldexp(static_cast<float>(m), -24);
  } else if (e == 31) {
    v = m != 0 ? 0.0f : 1e30f;
  } else {
    v = std::ldexp(static_cast<float>(m | 0x400u), static_cast<int>(e) - 25);
  }
  return sign != 0u ? -v : v;
}

// Camera2 CFA 枚举 → 2×2 相位查表（shader 按 ((y&1)<<1)|(x&1) 位移取 2bit 通道）。
// 位序：k0=(0,0) 在 bits[1:0]，k1=(1,0) 在 bits[3:2]，k2=(0,1) 在 bits[5:4]，
// k3=(1,1) 在 bits[7:6]——v0.5.29 曾把 k1/k2 写反（GRBG 误编码成 GBRG 的
// 0x49），R/B 相位对调 + WB 增益错乘 → 成片系统性发红（v0.5.31 修正）。
int cfaLutOf(int cfa) {
  switch (cfa) {
    case 0: return (2 << 6) | (1 << 4) | (1 << 2) | 0;  // RGGB: R G / G B
    case 1: return (1 << 6) | (2 << 4) | (0 << 2) | 1;  // GRBG: G R / B G
    case 2: return (1 << 6) | (0 << 4) | (2 << 2) | 1;  // GBRG: G B / R G
    default: return (0 << 6) | (1 << 4) | (1 << 2) | 2;  // BGGR: B G / G R
  }
}

// ── 诊断统计（v0.10.166）：GPU 内归约各阶段色度统计量，只读回标量 ──────────
// 目的（与 v0.10.163 相同）：定位"全图显示域 R−G 偏 +0.0145（≈3.6 个 8bit 级）"
// **产生在哪一步**。上一版走"把各阶段纹理挂到诊断 FBO 的 READ 附件 → glReadPixels
// 读浮点图 → 写 PFM"，真机（2026-10-05 12:20 日志）两处失败：
//   ① `diag.dump_fail reason=readpixels` —— ES 3.0 只保证 GL_RGBA+GL_UNSIGNED_BYTE
//      的 glReadPixels，浮点图像读回需要扩展（真机没有）；
//   ② 更严重：dump 失败还破坏了渲染状态 → `photo.saved avgLuma=0`（后续成片全黑）。
// 本版**彻底删除**该路径（不再有 PFM / 诊断 FBO 挂渲染纹理 / 读图像），改为：
//   pass A（kFsStatDown）：把阶段纹理按 kStatGridW×kStatGridH 分块，每块自适应取
//     kStatTap² 个 texel，GPU 内累加 ΣR/ΣG/ΣB、有效计数 n、按显示域亮度分 5 档的
//     Σ(R−G) 与档内计数 → 4 个 MRT 附件。Σ **用 float32 累加，不归一化到 8bit**
//     （这正是上一版要 PFM 的原因）；源里的 NaN/Inf 点只丢累加、仍计入总采样数。
//   pass B（kFsStatPack）：把 4 张归约网格合成 15 个 float32 标量，按 IEEE754 位
//     模式拆成 4 字节，写进一张 8×2 的 **RGBA8** 纹理（16 槽位）。
//   读回：glReadPixels(GL_RGBA, GL_UNSIGNED_BYTE) 取 **64 字节** —— 这是 ES 3.0
//     **保证可用**的组合，也是本项目已在跑的路径（tex_hr_stat_ 的 clipB、
//     tex_local_ 的局部亮度中位、tex_hist_ 的直方图）。位打包 ⇒ 浮点无损。
//   最终成片（D5-out-u8）在 CPU 侧按**同一分块口径**统计已读回的 u8 缓冲（零 GL 调用）。
// 触发：prefs `diag_stats`（**默认开**）→ JNI nativeEngineSetDiagStats → setDiagStats。
// 关闭时每个统计点唯一开销 = 一次 relaxed atomic load（无分配、无 pass、无 GL 调用）。
// 硬约束（静态可核对，见 tools/check-diag-stats.py）：
//   · 不把任何渲染纹理 attach 到诊断 FBO（源纹理只作 sampler 读）；
//   · 诊断只写自己的 4+1 张纹理，全文件唯一的诊断 glReadPixels 是 8×2 RGBA8；
//   · 进入/退出成对保存并恢复 FBO 绑定 / viewport / program / 纹理单元与绑定 /
//     blend / scissor / depth，退出时清空本段产生的 GL 错误；
//   · 任何一步失败（program 缺失、FBO 不完整、GL 错误、读回全零）⇒ 静默跳过，
//     只写一条 diag.stats_fail，成片链一像素不改。
std::atomic<bool> g_diag_stats{true};
// gainmap 数学版本（0 = PC / 1 = 官方）。**进程级**而非实例级：预览与拍照是两个
// FrameRenderer 实例，设置页改一次要两边同时生效（否则 A/B 会混进"实例不同"这个变量）。
std::atomic<int> g_gainmap_math{0};   // 0 = PC 版（offset 1e-4），默认
// 自动曝光（v0.11.61）：渲染端 EV（中灰锚定 + 高光约束）。进程级 —— 与 gainmap
// 数学同理，设置页改一次两个实例同时生效。默认开（用户 2026-10-20 要"真实记录"）。
std::atomic<bool> g_ae_auto{true};
std::string g_diag_params;  // 本帧 kFsFilm 色度开关快照（split/look/sat…）
bool g_diag_chain = false;  // 仅成片链（finishStillChain）内为 true
int g_diag_seq = 0;         // 本帧序号（日志配对用）

inline bool diagOn() { return g_diag_stats.load(std::memory_order_relaxed); }

// 归约网格与采样密度：块数 = 12288（块间近似独立 ⇒ 均值的有效样本数 ≈ 块数），
// 采样点 = 786432 ≈ 全图的 6.3%（4080×3072）。规则网格采样对低频统计量近似无偏，
// 标准误 ≈ σ/√12288（σ = 跨图 R−G 标准差 ~0.05 ⇒ ~4.5e-4），比要查的 0.0145 小
// 一个数量级以上；离线基准 tools/diag-stats-ref.py 复算同一口径供对照。
constexpr int kStatGridW = 128;
constexpr int kStatGridH = 96;
constexpr int kStatTap = 8;  // 每块每维采样点数（kStatTap² = 64 点/块）
constexpr int kStatSamples = kStatGridW * kStatGridH * kStatTap * kStatTap;
// 位打包输出：16 槽位 × 1 float32 = 8×2 个 RGBA8 像素 = 64 字节读回。
constexpr int kStatPackW = 8;
constexpr int kStatPackH = 2;
// 5 档分层（4 个边界，与离线口径一致）：[0,0.05) [0.05,0.15) [0.15,0.35)
// [0.35,0.60) [0.60,∞)。判据量 = 显示域亮度 dot(rgb, Rec.709)。
constexpr int kStatBands = 5;

// pass A：分块采样 + GPU 内累加。Σ 用 float32（附件格式 RGBA32F，不支持时回落
// RGBA16F —— 见 ensureDiagTargets），**不在 GPU 端做任何 8bit 归一化**。
const char* kFsStatDown = R"(#version 300 es
precision highp float;
precision highp int;
layout(location = 0) out vec4 oRGB;    // (ΣR, ΣG, ΣB, n)
layout(location = 1) out vec4 oBandA;  // (Σ(R−G)|b0, b1, b2, b3)
layout(location = 2) out vec4 oBandB;  // (n|b0, n|b1, n|b2, n|b3)
layout(location = 3) out vec4 oBandC;  // (Σ(R−G)|b4, n|b4, 0, 0)
uniform highp sampler2D uSrc;
uniform ivec2 uSize;
const ivec2 kGrid = ivec2(128, 96);
const int kTap = 8;
const vec3 kLw = vec3(0.2126, 0.7152, 0.0722);
void main() {
    ivec2 g = ivec2(gl_FragCoord.xy);
    ivec2 lo = (g * uSize) / kGrid;
    ivec2 hi = ((g + 1) * uSize + kGrid - 1) / kGrid;
    ivec2 span = max(hi - lo, ivec2(1, 1));
    vec3 acc = vec3(0.0);
    float n = 0.0;
    float rg0 = 0.0, rg1 = 0.0, rg2 = 0.0, rg3 = 0.0, rg4 = 0.0;
    float c0 = 0.0, c1 = 0.0, c2 = 0.0, c3 = 0.0, c4 = 0.0;
    for (int j = 0; j < kTap; ++j)
        for (int i = 0; i < kTap; ++i) {
            ivec2 q = clamp(lo + (ivec2(i, j) * span) / kTap, ivec2(0), uSize - 1);
            vec3 c = texelFetch(uSrc, q, 0).rgb;
            if (any(isnan(c)) || any(isinf(c))) continue;  // 只丢累加，仍计入总采样数
            acc += c;
            n += 1.0;
            float L = dot(c, kLw);
            float d = c.r - c.g;
            if (L < 0.05) { rg0 += d; c0 += 1.0; }
            else if (L < 0.15) { rg1 += d; c1 += 1.0; }
            else if (L < 0.35) { rg2 += d; c2 += 1.0; }
            else if (L < 0.60) { rg3 += d; c3 += 1.0; }
            else { rg4 += d; c4 += 1.0; }
        }
    oRGB = vec4(acc, n);
    oBandA = vec4(rg0, rg1, rg2, rg3);
    oBandB = vec4(c0, c1, c2, c3);
    oBandC = vec4(rg4, c4, 0.0, 0.0);
})";

// pass B：4 张归约网格 → 16 个 float32 标量 → IEEE754 位模式拆成字节写 RGBA8。
const char* kFsStatPack = R"(#version 300 es
precision highp float;
precision highp int;
layout(location = 0) out vec4 oPack;
uniform highp sampler2D uT0;
uniform highp sampler2D uT1;
uniform highp sampler2D uT2;
uniform highp sampler2D uT3;
const ivec2 kGrid = ivec2(128, 96);
void main() {
    int k = int(gl_FragCoord.y) * 8 + int(gl_FragCoord.x);
    int t = k >> 2;  // 0..3：读哪张归约网格
    int c = k & 3;   // 0..3：取该网格的哪个通道
    float v = 0.0;
    for (int j = 0; j < kGrid.y; ++j)
        for (int i = 0; i < kGrid.x; ++i) {
            ivec2 q = ivec2(i, j);
            vec4 s = texelFetch(uT0, q, 0);
            if (t == 1) s = texelFetch(uT1, q, 0);
            else if (t == 2) s = texelFetch(uT2, q, 0);
            else if (t == 3) s = texelFetch(uT3, q, 0);
            v += (c == 0) ? s.x : ((c == 1) ? s.y : ((c == 2) ? s.z : s.w));
        }
    uint bits = floatBitsToUint(v);
    oPack = vec4(float(bits & 0xFFu), float((bits >> 8) & 0xFFu),
                 float((bits >> 16) & 0xFFu), float((bits >> 24) & 0xFFu)) / 255.0;
})";

// 读回缓冲的槽位布局（与 kFsStatPack 的 k 一一对应）。
enum {
  kSlotSumR = 0, kSlotSumG = 1, kSlotSumB = 2, kSlotN = 3,
  kSlotRgBand = 4,   // 4..8：5 档的 Σ(R−G)
  kSlotNBand = 9,    // 9..13：5 档的计数
  kSlotCount = 16,
};

// IEEE754 位模式还原（与 kFsStatPack 的打包互为逆运算）。
float statUnpack(const unsigned char* p) {
  const uint32_t bits = static_cast<uint32_t>(p[0]) |
                        (static_cast<uint32_t>(p[1]) << 8) |
                        (static_cast<uint32_t>(p[2]) << 16) |
                        (static_cast<uint32_t>(p[3]) << 24);
  float v = 0.0f;
  std::memcpy(&v, &bits, sizeof(v));
  return v;
}

// 一条 diag.stats（成功）或 diag.stats_fail（跳过）。字段与离线基准同口径：
//   sumR/sumG/sumB = 采样点的 float 累加（**未做任何 8bit 归一化**）；
//   n = 有效采样点数；samples = 总采样点数（常数）；nan = samples − n（NaN/Inf）；
//   rgByBand[i] = 该档 Σ(R−G) / 该档计数（档内计数为 0 时记 0）；
//   nBand[i] = 该档计数；r/g/b = 全图均值；rg = b 与 r 的均值之比对应的 R−G。
// 开关快照（split/look/sat/mono/tone/gm）由 diagBeginFrame 攒好，逐条带上——
// 偏红若来自其中某一项，同一帧里可直接对照。
void diagStatsLog(const char* stage, const float* v, const FilmParams* p,
                  const char* fail) {
  std::string kv = "{\"stage\":\"" + std::string(stage) + "\",\"seq\":" +
                   std::to_string(g_diag_seq);
  if (fail) {
    kv += ",\"reason\":\"" + std::string(fail) + "\"}";
    Logger::instance().log(LogLevel::kWarn, "engine", "diag.stats_fail", kv);
    return;
  }
  const float n = v[kSlotN];
  const float inv = n > 0.0f ? 1.0f / n : 0.0f;
  kv += ",\"sumR\":" + jnum(v[kSlotSumR], 9);
  kv += ",\"sumG\":" + jnum(v[kSlotSumG], 9);
  kv += ",\"sumB\":" + jnum(v[kSlotSumB], 9);
  kv += ",\"n\":" + std::to_string(static_cast<long long>(n + 0.5f));
  kv += ",\"samples\":" + std::to_string(kStatSamples);
  kv += ",\"nan\":" + std::to_string(static_cast<long long>(
                          kStatSamples - static_cast<long long>(n + 0.5f)));
  kv += ",\"r\":" + jnum(v[kSlotSumR] * inv, 9);
  kv += ",\"g\":" + jnum(v[kSlotSumG] * inv, 9);
  kv += ",\"b\":" + jnum(v[kSlotSumB] * inv, 9);
  kv += ",\"rg\":" + jnum((v[kSlotSumR] - v[kSlotSumG]) * inv, 9);
  kv += ",\"rgByBand\":[";
  for (int i = 0; i < kStatBands; ++i) {
    const float c = v[kSlotNBand + i];
    if (i) kv += ",";
    kv += jnum(c > 0.0f ? v[kSlotRgBand + i] / c : 0.0f, 9);
  }
  kv += "],\"nBand\":[";
  for (int i = 0; i < kStatBands; ++i) {
    if (i) kv += ",";
    kv += std::to_string(static_cast<long long>(v[kSlotNBand + i] + 0.5f));
  }
  kv += "]";
  if (p) {  // 开关快照（由调用方传入，保证是**本帧**的参数）
    kv += ",\"sat\":[" + jnum(p->sat_shadow, 8) + "," + jnum(p->sat_mid, 8) + "," +
          jnum(p->sat_high, 8) + "],\"split\":[" + jnum(p->split_warm, 8) + "," +
          jnum(p->split_cool, 8) + "],\"look\":[" + jnum(p->look_strength, 8) + "," +
          jnum(p->look_sat, 8) + "," + jnum(p->look_chroma, 8) +
          "],\"mono\":" + jnum(p->monochrome, 8) +
          ",\"tone\":" + jnum(p->tone_strength, 8) +
          ",\"gm\":" + jnum(p->gamut, 8) + ",\"hrW\":" + jnum(p->hr_wavelet, 8);
  }
  kv += "}";
  Logger::instance().log(LogLevel::kInfo, "engine", "diag.stats", kv);
}

// 成片链入口（finishStillChain 开头）：开表。默认关闭时仅一次 atomic load。
void diagBeginFrame() {
  g_diag_chain = false;
  if (!diagOn()) return;
  g_diag_chain = true;
  ++g_diag_seq;
  g_diag_params.clear();
}

// 成片链出口：关表（统计逐阶段即时上报，这里只负责把链标志清掉）。
void diagEndFrame() { g_diag_chain = false; }

}  // namespace

// ═══════════════════════════════════════════════════════════════════════════════
// 引擎 1（darktable sigmoid）的 GLSL 注入段 —— 阶段 2（方案 §7）
//
// 一手依据（**逐行照抄，不做简化、不重新推导**）：
//   · PhotonCamera `DarktableSigmoidToneAlgorithm.kt` 的
//     `DARKTABLE_SIGMOID_COMBINED_FUNCTIONS`（本地副本
//     `/tmp/pc-tone/app/src/main/java/com/hinnka/mycamera/raw/`；包名是
//     `com.hinnka.mycamera`，不是 `com.bjzhou.*`）；
//   · 上游 = darktable `src/iop/sigmoid.c` 的 `_generalized_loglogistic_sigmoid`。
//
// 与 PC 的差异**只有两处机械改写**（函数体逐行等价，"顺手简化"一律禁止）：
//   ① 6 个 `DT_SIGMOID_*` 编译期常量 → `uSig*` uniform。数值由 CPU 侧
//      `computeSigmoidParams`（`film/SigmoidParams.h`，逐行照抄 darktable
//      `commit_params`）求解后下发 ⇒ **公式只有一处实现**，不存在"CPU 与 GPU 各写
//      一遍公式"的漂移面（与既有 `computeFilmicSpline` → `uFilmic*` 同款做法）。
//      ⚠ **PC 的 6 个常量 = darktable 默认 contrast 1.5 下的解**（paper_exposure
//        0.354355423 / film_fog 0.00142637086 / film_power 1.5，double 预算值）。
//        本项目**同取 1.5**（2026-10-14 用户拍板"跟 PC 一致"，见 `SigmoidParams.h`
//        顶部）：float32 求解得 paper_exposure 0.359695 / film_fog 0.001384322 /
//        film_power 1.490909（white_target 1 / paper_power 1 / hue 1 两者相同）
//        ⇒ 8bit 输出上与 PC 常量逐点差 **≤0.41 LSB**（−6…+8 EV 全域实测）。
//        差异是**参数精度口径**，不是公式不同 —— 公式逐行等价。
//        （历史：2026-10-13 曾取 contrast 1.2 以对齐引擎 0 的白纸 204 LSB，只为判据 ①
//        的"同白纸口径"服务；该人为约束已按用户拍板作废。）
//   ② 函数名改项目风格（`sigmoid*` 前缀，入口 `sigmoidTone`）：与公共段 / 引擎 0 段
//      的既有函数（`luma` / `filmic*` / `gamut*`）无重名（已全段 grep 核对）。
//
// ⚠ **必须显式标注的差异（不许默默省略）**：darktable 的 per-channel 路径还有
//   **primaries inset / rotation**（`_calculate_adjusted_primaries`：把工作空间原色按
//   inset / rotation 收缩旋转后再做 per-channel 曲线）。darktable 默认
//   `inset = 0 / rotation = 0 / purity = 0` ⇒ 该段是**恒等变换**，PC 也省略了它，
//   本阶段同样省略 —— **省略的是恒等段，默认参数下与官方逐像素等价**。
//   非默认 inset / rotation 是将来"公共工作空间切 Rec.2020 / rec2020 primaries"那一期
//   的挂点（方案 §10 第 3 条）：届时在本段函数之前插入 `_calculate_adjusted_primaries`
//   的矩阵即可，曲线本体不动。
//
// ⚠ `uSigBlackTarget`（display black target）**声明但不参与曲线**：PC 的 per-channel
//   路径没用到它，darktable 的 per-channel 路径**同样没用到**（只有 rgb-ratio 路径用它
//   做黑点抬升）。保留声明有两个理由：① 与 CPU 侧 7 标量契约一一对应（少一个就是
//   "求解了却不下发"的静默缺口）；② 将来 rgb-ratio 路径与完整性校验的挂点。
//   它被 GLSL 编译器优化掉 ⇒ `glGetUniformLocation` 返回 −1 ⇒ 下发是安全 no-op。
//
// ⚠ 7 个 uniform 名与 CPU 侧下发名**逐字一致**（`FrameRenderer.cpp` 的
//   `queryFilmUniforms()` / `passFilm()` 两处，`tools/validate-shaders.sh` 会核对）。
// ═══════════════════════════════════════════════════════════════════════════════
// ⚠ **本块必须在匿名 namespace 之外**（就放在上面那个 `}  // namespace` 之后）：
//   `film/EngineRegistry.cpp` 的表项按名字**链接引用**这两个指针，而匿名 namespace 里的
//   名字是**内部链接** ⇒ 本 TU 不产出外部符号，链接期 undefined symbol。实测口径：
//   放匿名 namespace 内时 `nm FrameRenderer.o | grep kFsSigmoid` **零命中**且 clang 报
//   "unused variable"；放这里后为 `D filmkit::kFsSigmoidDef` / `D filmkit::kFsSigmoidMain`
//   （`EngineRegistry.o` 侧的 `U` 由它们满足）。**移动本块前先看这条注释。**
// ── HDR gainmap（v0.11.50，Ultra HDR / JPEG-R 的辅助图）──────────────────────
// 目的：算出"HDR 应比 SDR 亮多少"的 8bit 比值图，供 libultrahdr 封装成 JPEG/R。
//
// ## 为什么在 GPU 算、而不是把 HDR 读回 CPU
// ES 3.0 **只保证** `glReadPixels(GL_RGBA, GL_UNSIGNED_BYTE)`，浮点图像读回需要扩展，
// 而真机没有（2026-10-05 实测：`diag.dump_fail reason=readpixels`，且失败后还破坏了
// 渲染状态导致成片全黑 —— 见 diagStatsTex 的注释）。所以 HDR 线性**不能**直接读回。
// 本 pass 在 GPU 内完成"HDR/SDR 比值 → 8bit"，读回的只是 1/2 分辨率的单通道 8bit
// 图（12MP 时约 3MB），走 ES 3.0 保证的路径。
//
// ## 域口径（**这是本 shader 唯一的坑，必须与 kFsOut 逐字一致**）
// `uHdr` = tex_scene_（D4 场景线性，**Rec.2020**）—— 期 B 换域后的工作空间；
// `uSdr` = tex_cap_（D5 显示域，sRGB **已编码** 8bit）。
// 两边必须落到**同一域**才能相除：HDR 侧先 workToSrgb（与 kFsOut 同一矩阵），
// SDR 侧先反 OETF 回 sRGB 线性，再各取 Rec.709 luma。
// ⚠ 直接拿 Rec.2020 的 Y 系数去算 HDR luma 会得到"ACES 有色相优势"那类假结论
//（D5 域口径 bug，2026-10-19 踩过）—— 故这里显式转 sRGB 再用 709 系数。
//
// ## 公式（= PhotonCamera RawGainmapMath，实测选定，见 UltraHdrEncoder.cpp 的 A/B 结论）
//   ratio = clamp((hdrY + 1e-4) / (sdrY + 1e-4), 0.25, 4.0)
//   v     = (ln(ratio) - ln(0.25)) / (ln(4.0) - ln(0.25))
// 输出 v 到 R 通道（G/B 同值，便于肉眼调试时直接看灰度）。
const char* kFsGainmap = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uHdr;    // tex_scene_：Rec.2020 线性（D4）
uniform highp sampler2D uSdr;    // tex_cap_：sRGB 已编码（D5，RGBA8）
uniform vec2 uSrcSize;           // 全分辨率尺寸（px）
// gainmap 数学版本（v0.12.85 起 **三档**，运行时切换）：
//   0 = **P4 真实跨曝光比值**（新默认）：hdrAligned/sdrY，中灰自锚定（见 gainTrue 注释）
//   1 = 旧 PhotonCamera 塑形版（0.11.90→0.12.21 的 sceneY 纯函数：中灰 1.1 + ×1.6 + 2.5 封顶）
//   2 = libultrahdr 官方版公式（同旧版塑形 + 暗部 sdr<2/255 钳 2.3；从未默认过，留作对照）
// 基线证据（docs/reports/hdr-baseline-20261025-REPORT.md）：旧版 gainmap p90 恒 0.533
//（=1.10x 中灰平台）、8bit 码值 136 占 96.5%、码值 212 钉死 2.5 封顶 —— 8bit 码值空间
// 浪费 ~70%，且高光增益到不了元数据声称的 4.0；PC 是真实比值（p90 顶格 4x）。
// 两版旧数学**最终都归一化到固定 [0.25, 4.0]**（官方"自适应范围"需要两遍扫描，本 pass
// 单遍做不到；且离线实测自适应范围在无高光场景会把 8bit 码值浪费在极窄区间）。
uniform int uGainmapMath;
uniform float uSceneMid;         // P4：本张 scene 域中灰实测（ae_p50_，与 uHdr 同一 film_src 测得）
const int kDS = 2;               // 下采样倍数（= PC RawGainmapMath.DOWNSAMPLE）
const float kMin = 0.25;         // PC MIN_GAIN_RATIO
const float kMax = 4.0;          // PC MAX_GAIN_RATIO
const float kOff = 1e-4;         // PC OFFSET

// 与 kFsOut 的 workToSrgb **逐字相同**（Rec.2020 线性 → sRGB 线性）
vec3 workToSrgb(vec3 c) {
    return vec3(dot(c, vec3(1.6604910021, -0.5876411388, -0.0728498633)),
                dot(c, vec3(-0.1245504745, 1.1328998971, -0.0083494226)),
                dot(c, vec3(-0.0181507634, -0.1005788980, 1.1187296614)));
}
float srgbInvOetf(float x) {
    x = clamp(x, 0.0, 1.0);
    return x <= 0.04045 ? x / 12.92 : pow((x + 0.055) / 1.055, 2.4);
}
float luma709(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// **2026-10-20 修正（0.11.74）**：hdrLin 是**未 tone map 的 scene 线性**（白点 1.0），
// sdrLin 是**已 tone map 的显示线性**（中灰 0.18）—— 直接相除，中灰 ratio ≈ 0.39
// （−1.36 EV）⇒ 开 HDR 整张变暗/发灰（真机实测 gainmap 中位 −1.03 log2、base mean 111）。
// Ultra HDR 的语义是"HDR 相对 base 只会更亮"：中灰及以下 ratio = 1（= base），
// 只有 scene 超白点（>1）的部分才原样延伸成高光增益。
// HDR 相对 base 的增益曲线：**中灰基线 1.1**（整体通透度，对齐 PC gainmap 实测
// 中位 ≈1.1×），随后按 scene 亮度平滑升到 kMax（高光）。0.11.74 只给 max(1, scene)
// 会让"高光面积小"的场景（日志 163517：aeP995 1.24）HDR 开关等于没有效果。
// v0.11.90：**按真实 scene 倍数延伸，不再用归一化 t²**。
// 旧式 `t = clamp((sceneY-0.2)/0.8)` 把"scene ≥1.0"全部压成 t=1 ⇒ 一律 4×。天空的
// scene 只有 1.38，却被给了 4×：base 190 → HDR 760，超出手机屏幕的可用 headroom，
// 系统 tonemap 压回来时丢色度 —— 真机 5619 的"SDR 天空有淡蓝、开 HDR 变灰白"。
// 新式：中灰及以下给 1.1（整体通透，对齐 PC gainmap 中位），白点以上按真实倍数
// （scene 1.38 ⇒ 1.38×1.6 = 2.2×），封顶 2.5 —— 在 1000nits 屏上约 0.4 峰值，
// 既看得出 HDR，又不会被 clip 成白。
float ratioFromScene(float sceneY) {
    return clamp(max(1.1, sceneY * 1.6), 1.1, 2.5);
}
// ── P4（0.12.85）：真实跨曝光比值 ─────────────────────────────────────────────
// 旧版（=1/2 档）的 ratio 是 sceneY 的纯函数，SDR base 不参与 —— 基线实测
//（docs/reports/hdr-baseline-20261025-REPORT.md）：p90 恒 0.533（1.10x 平台）、
// 码值 136 占 96.5%、码值 212 钉死 2.5 封顶；PC 是真实比值（p90 顶格 4x）。
// 关键是**中灰自锚定**（0.11.74 的教训：uHdr 域中灰 ≈0.072 与显示域 0.1845 差
// −1.36EV，直接相除全图 ratio×0.39 → 整张发灰）：uSceneMid = 本张 ae_p50_（与
// uHdr 同一 film_src 测得、随曝光自适应），把 scene 域按它折到显示域尺度后相除，
// 中灰像素 ratio 恒 = 1，锚定不随曝光策略漂。
// 边界：clamp [1.0, 4.0] —— 下限 1 = UltraHDR 语义"HDR 相对 base 只会更亮"（暗部
// 零放大，旧版 offset 暗部陷阱一并消失）；上限 4 = 元数据 kMax（PC 同款顶格）。
// ⚠ 上限 4 与 5619"天空灰白"：真实比值下 4x 只在小面积高光核心顶格（天空 ratio
// 连续 1~4x，随 ae_p50_ 自适应），且 base 比 0.11.90 时代暗 —— 但未真机证实前不算
// 数：A/B 档（=1）保留，若复现预案 = kHdrCeil 软限（HDR 亮度 ≤ 2×SDR 白，按 sdrY
// 收上限，不全局砍）。
float gainTrue(float sdrLin, float hdrLin) {
    // ── 0.12.202：**与 PC 同式，无锚定** ────────────────────────────────────
    // 一手出处：bjzhou/PhotonCamera `hdr/GpuReferenceGainmapProducer.kt` 的
    // RAW_LUMA_RESIDUAL_FRAGMENT_SHADER + `hdr/RawGainmapMath.kt::encode`：
    //   ratio = (hdrLinear + offset) / (sdrLinear + offset)，clamp [0.25, 4]，
    //   无 scene-mid、无 0.1845 之类的目标值。
    // 之所以能不锚定：它的 uHdr 是**线性 HDR 参考**（RAW 场景线性，未 tone map），
    // uSdr 是 SDR 的 sRGB 码值反 OETF —— 两者都按反射率线性计量，同域。
    // 我们这边同域的依据在 15043：uHdr 传的是 `film_src` = 降噪/去边之后的 RAW
    // 线性（tone-eq 与 filmic 之前），与 tex_cap_ 反 OETF 后的线性同尺度。
    // 0.12.85~0.12.201 用 `ae_p50` / 0.072 当锚，等于在这条同域式子上又乘了一次
    // 尺度：实测锚定 2.56 时中灰 ratio 2.4（整张亮 2.4 倍）、gmMax 恒 1.0，
    // 那不是 HDR 而是整体提亮。
    float ratio = clamp((hdrLin + kOff) / (sdrLin + kOff), kMin, kMax);
    // 0.12.203：PC 的 `HdrGainmapStrength`（默认 1.0，范围 0.25~2.0）：
    //   ratio' = 1 + (ratio − 1) × strength
    // 只放大高光偏移、中灰恒为 1 —— 真机 202 实测中灰 ratio 1.03（正确）但高光
    // 只有 1.4~1.8 倍，观感偏弱；1.5 档把高光推到 2.2~3.2 倍而中灰不动。
    const float kGainStrength = 1.3;   // GLSL：没有 constexpr
    ratio = clamp(1.0f + (ratio - 1.0f) * kGainStrength, kMin, kMax);
    return (log(ratio) - log(kMin)) / (log(kMax) - log(kMin));
}

float gainPc(float sdrLin, float hdrLin) {
    float ratio = clamp(ratioFromScene(hdrLin), kMin, kMax);
    return (log(ratio) - log(kMin)) / (log(kMax) - log(kMin));
}
// 官方版：同样的语义（只保留高光扩展），暗部钳位保留（暗部 ratio 恒 1，实际不触发）
float gainOfficial(float sdrLin, float hdrLin) {
    float gain = log2(ratioFromScene(hdrLin));
    if (sdrLin < 2.0 / 255.0) gain = min(gain, 2.3);
    float ratio = clamp(exp2(gain), kMin, kMax);
    return (log(ratio) - log(kMin)) / (log(kMax) - log(kMin));
}

void main() {
    // ⚠ **两张输入的方向相反**（v0.11.50 真机事故，症状 = HDR 增益上下错位/键盘鬼影）：
    //   uHdr = tex_scene_  ← kFsFilm 用 `texture(uTex, vUV0)` 直接采样 ⇒ 方向 A
    //   uSdr = tex_cap_    ← kFsOut 带 `uFlipY`（拍照读回 GL 底行在前）翻转输出 ⇒ 方向 B = flip(A)
    // 用同一个 UV 采两者 = 拿"上方像素的 HDR"去除"下方像素的 SDR" ⇒ 增益整体上下颠倒。
    // 真机实测：床面出现键盘的鬼影（下方真实键盘的增益被搬到上方）。
    // 输出走**方向 B**（与 tex_cap_ / 最终 glReadPixels 的 base 同向），所以：
    //   · uSdr 用 vUV0（B）
    //   · uHdr 用 (x, 1-y)（转回 A）
    // ⚠ **采样核 kK = kDS*2（步长仍是 kDS）⇒ 相邻 gainmap 像素的输入区重叠 50%，
    // 等于隐式做了一次 2×2 盒式平滑**（v0.11.53 真机事故的修复）。
    // 事故：2×2 无重叠采样时 gainmap 全是高频噪声点 —— 真机实测 1536×2040 的 gainmap
    // JPEG 有 **570KB**（正常应几十 KB），且 >=0.90 的像素占 1.61%（约 5 万个高增益点
    // 散布全图）；经 IDW 上采样后这些点连成环，把相邻的栏杆等暗部一起提亮
    // ——用户原话"太阳 HDR 显影把栏杆覆盖了"。
    // 重叠平均把孤立噪点摊薄，同时保留太阳区的整体高增益（offset 1e-7 的暗部抖动
    // 是另一半根因，那部分靠默认用 PC 版 offset 1e-4 解决，见 setGainmapMath 注释）。
    vec2 block = floor(vUV0 * uSrcSize / float(kDS)) * float(kDS);
    const int kK = kDS * 2;
    float hdrSum = 0.0, sdrSum = 0.0;
    for (int y = 0; y < kK; ++y) {
        for (int x = 0; x < kK; ++x) {
            vec2 pxB = (block + vec2(float(x), float(y)) - float(kDS) * 0.5 + 0.5) / uSrcSize;
            vec2 pxA = vec2(pxB.x, 1.0 - pxB.y);   // tex_scene_ 的方向
            // HDR：Rec.2020 线性 → sRGB 线性 → 709 luma
            hdrSum += luma709(workToSrgb(texture(uHdr, pxA).rgb));
            // SDR：sRGB 编码 → sRGB 线性 → 709 luma
            vec3 s = texture(uSdr, pxB).rgb;
            sdrSum += luma709(vec3(srgbInvOetf(s.r), srgbInvOetf(s.g), srgbInvOetf(s.b)));
        }
    }
    float n = float(kK * kK);
    float hdrY = hdrSum / n;
    // sdrY 是**线性** luma（上面已反过 OETF）—— 两版都吃线性域
    float sdrY = sdrSum / n;
    float v = (uGainmapMath == 0) ? gainTrue(sdrY, hdrY)
            : (uGainmapMath == 1) ? gainPc(sdrY, hdrY)
                                  : gainOfficial(sdrY, hdrY);
    oColor = vec4(v, v, v, 1.0);
}
)";

const char* kFsSigmoidDef = R"(// ── 引擎 1 私有 uniforms（7 个；前 6 个 = PC 的 6 个 DT_SIGMOID_* 常量）──────
uniform float uSigWhiteTarget;
uniform float uSigBlackTarget;
uniform float uSigPaperExposure;
uniform float uSigFilmFog;
uniform float uSigFilmPower;
uniform float uSigPaperPower;
uniform float uSigHuePreservation;

// ⚠ 公共 uniform，但**声明落在引擎 0 段内**（阶段 1 的切分点 A 在它之后）⇒ 引擎 1 必须
// 补声明，否则公共中段的 `vec3 mapped = max(lin, vec3(0.0)) * uExposure;` 编译期就报
// "undeclared identifier"（本卡实测：glslang 报 0:223 未声明，真机同错）。
// 为什么**不**把它移进 head 段：移动 = 改动 `kFsFilm` 的字节 ⇒ 引擎 0 的零回归硬门槛
// （25140 字符 / 35154 字节 / crc32 0x4fa1ebd3）当场红。正确修法是引擎 1 自带声明 ——
// 两个引擎各自是**独立编译的 shader**，同名 uniform 各声明一次互不影响。
// 全量核对过：engine0_def 段声明、而公共 mid/tail 段使用的 uniform **只有 uExposure**
// 这一个（其余 21 个 uFilmic*/uGamut 只被引擎 0 自己的函数使用）。
uniform float uExposure;

// 负值去饱和：把负通道（宽色域工作空间的域外色）拉回正值域，先抬到"平均亮度"再按
// 最小通道的负度缩放色差。逐行照抄 PC 的 `desaturateNegativeValues`。
vec3 sigmoidDesaturateNegative(vec3 color) {
    float pixelAverage = max((color.r + color.g + color.b) / 3.0, 0.0);
    float minValue = min(color.r, min(color.g, color.b));
    float saturationFactor =
        minValue < 0.0 ? -pixelAverage / (minValue - pixelAverage) : 1.0;
    return vec3(pixelAverage) + saturationFactor * (color - vec3(pixelAverage));
}

// generalized log-logistic：film 响应（幂律）+ paper 响应（有理式饱和）。
// darktable `_generalized_loglogistic_sigmoid`；PC 的 `darktableSigmoidScalar` 逐行同。
float sigmoidScalar(float value) {
    float clampedValue = max(value, 0.0);
    float filmResponse = pow(uSigFilmFog + clampedValue, uSigFilmPower);
    float paperResponse = uSigWhiteTarget *
        pow(filmResponse / (uSigPaperExposure + filmResponse), uSigPaperPower);
    return clamp(paperResponse, 0.0, uSigWhiteTarget);
}

vec3 sigmoidCurve(vec3 color) {
    return vec3(
        sigmoidScalar(color.r),
        sigmoidScalar(color.g),
        sigmoidScalar(color.b)
    );
}

// per-channel 曲线的通道排序（max / mid / min 三档）：darktable 的 per-channel 路径
// 用"亮度排序"而非固定 RGB 顺序做 hue preservation，保证结果与通道排列无关。
ivec3 sigmoidChannelOrder(vec3 color) {
    if (color.r >= color.g) {
        if (color.g > color.b) {
            return ivec3(2, 1, 0);
        } else if (color.b > color.r) {
            return ivec3(1, 0, 2);
        } else if (color.b > color.g) {
            return ivec3(1, 2, 0);
        }
        return ivec3(2, 1, 0);
    }
    if (color.r >= color.b) {
        return ivec3(2, 0, 1);
    } else if (color.b > color.g) {
        return ivec3(0, 1, 2);
    }
    return ivec3(0, 2, 1);
}

float sigmoidChannelValue(vec3 color, int index) {
    if (index == 0) return color.r;
    if (index == 1) return color.g;
    return color.b;
}

vec3 sigmoidWithChannelValue(vec3 color, int index, float value) {
    if (index == 0) {
        color.r = value;
    } else if (index == 1) {
        color.g = value;
    } else {
        color.b = value;
    }
    return color;
}

// 色相 + 能量保持：先按 midScale 求"全色相校正"中点，再在"朴素色相中点"与它之间按
// uSigHuePreservation 插值；能量目标 = 输入 min/mid 结构决定的混合因子 × per-channel
// 总能量与朴素总能量的混合。两支分别解 correctedMid（分母即约束方程的系数），
// 保证 outMin + outMid + outMax == energyTarget。逐行照抄 PC 的
// `preserveSigmoidHueAndEnergy`。
vec3 sigmoidPreserveHueAndEnergy(vec3 inputColor, vec3 perChannel) {
    ivec3 order = sigmoidChannelOrder(inputColor);
    float inputMin = sigmoidChannelValue(inputColor, order.x);
    float inputMid = sigmoidChannelValue(inputColor, order.y);
    float inputMax = sigmoidChannelValue(inputColor, order.z);
    float perMin = sigmoidChannelValue(perChannel, order.x);
    float perMid = sigmoidChannelValue(perChannel, order.y);
    float perMax = sigmoidChannelValue(perChannel, order.z);

    float chroma = inputMax - inputMin;
    float midScale = chroma != 0.0 ? (inputMid - inputMin) / chroma : 0.0;
    float fullHueCorrection = perMin + (perMax - perMin) * midScale;
    float naiveHueMid = mix(perMid, fullHueCorrection, uSigHuePreservation);
    float perChannelEnergy = perChannel.r + perChannel.g + perChannel.b;
    float naiveHueEnergy = perMin + naiveHueMid + perMax;
    float inputMinPlusMid = inputMin + inputMid;
    float blendFactor = inputMinPlusMid != 0.0 ? 2.0 * inputMin / inputMinPlusMid : 0.0;
    float energyTarget = blendFactor * perChannelEnergy + (1.0 - blendFactor) * naiveHueEnergy;

    float outMin;
    float outMid;
    float outMax;
    if (naiveHueMid <= perMid) {
        float correctedMid =
            ((1.0 - uSigHuePreservation) * perMid +
                uSigHuePreservation *
                (midScale * perMax + (1.0 - midScale) * (energyTarget - perMax))) /
            (1.0 + uSigHuePreservation * (1.0 - midScale));
        outMin = energyTarget - perMax - correctedMid;
        outMid = correctedMid;
        outMax = perMax;
    } else {
        float correctedMid =
            ((1.0 - uSigHuePreservation) * perMid +
                uSigHuePreservation *
                (perMin * (1.0 - midScale) + midScale * (energyTarget - perMin))) /
            (1.0 + uSigHuePreservation * midScale);
        outMin = perMin;
        outMid = correctedMid;
        outMax = energyTarget - perMin - correctedMid;
    }

    vec3 result = vec3(0.0);
    result = sigmoidWithChannelValue(result, order.x, outMin);
    result = sigmoidWithChannelValue(result, order.y, outMid);
    result = sigmoidWithChannelValue(result, order.z, outMax);
    return result;
}

// 引擎 1 入口（对应 PC 的 `applyDarktableSigmoid`；PC 尾部的 `uOutputTransform` 是它
// 自己的输出变换，本项目的输出契约由公共段负责 ⇒ 不搬）。
vec3 sigmoidTone(vec3 color) {
    vec3 positiveColor = sigmoidDesaturateNegative(color);
    vec3 perChannel = sigmoidCurve(positiveColor);
    return sigmoidPreserveHueAndEnergy(positiveColor, perChannel);
}
)";

// 引擎 1 注入段②：main 内的调用段（与引擎 0 的 engine0_main **同形**）。
// 只能用 `out3` 与 uniforms —— `float l = luma(out3);` 等变量在本段**之后**才定义。
// sigmoid 的色域行为 = 上面的 hue preservation（darktable 的 per-channel 路径自带
// 色相 / 能量守恒）⇒ **不接** `gamutMapV5`：gamut 归引擎（方案 §1.2），filmic v5 的
// gamut 段是 filmic 引擎的私有部分，套到 sigmoid 上就是双份色域处理。
[[maybe_unused]] const char* kFsSigmoidMain = R"(    if (uToneK > 0.0) {
        vec3 toned = sigmoidTone(out3);
        out3 = mix(out3, toned, uToneK);
    })";

// 诊断统计资源（4 张归约网格 + 1 张位打包输出）的惰性创建。**自带状态保存/恢复**：
// 调用方在读"进入前状态"之前调用它，绝不能让它留下的 FBO/纹理绑定冒充成片链的状态。
bool FrameRenderer::ensureDiagTargets() {
  if (tex_diag_out_ && fbo_diag_out_ && tex_diag_ds_[0] && fbo_diag_ds_) return true;
  GLint prev_fbo = 0, prev_unit = 0, prev_tex = 0;
  glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prev_fbo);
  glGetIntegerv(GL_ACTIVE_TEXTURE, &prev_unit);
  glActiveTexture(GL_TEXTURE0);
  glGetIntegerv(GL_TEXTURE_BINDING_2D, &prev_tex);
  releaseDiagTargets();
  // @domain: A5 —— 诊断统计域（Σ 用 float32 累加，绝不降到 8bit）
  // 首选 RGBA32F（EXT_color_buffer_float；Σ 可能到 1e5 量级且不能丢有效位）；
  // 真机若不可渲染则回落 RGBA16F（half 在 1e3 量级仍有 ~11bit 有效位，够比值用），
  // 两者都建不出 ⇒ 调用方静默跳过（diag.stats_fail）。
  for (int attempt = 0; attempt < 2; ++attempt) {
    const GLenum fmt = attempt == 0 ? GL_RGBA32F : GL_RGBA16F;
    tex_diag_ds_[3] = 0;
    glGenTextures(4, tex_diag_ds_);
    for (int i = 0; i < 4; ++i) {
      glBindTexture(GL_TEXTURE_2D, tex_diag_ds_[i]);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
      // 类型必须与内部格式匹配（X300 实测：RGBA8 + HALF_FLOAT 非法组合静默失败）
      glTexImage2D(GL_TEXTURE_2D, 0, static_cast<GLint>(fmt), kStatGridW, kStatGridH,
                   0, GL_RGBA, fmt == GL_RGBA32F ? GL_FLOAT : GL_HALF_FLOAT, nullptr);
    }
    if (fbo_diag_ds_ == 0) glGenFramebuffers(1, &fbo_diag_ds_);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_diag_ds_);
    for (int i = 0; i < 4; ++i) {
      glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i, GL_TEXTURE_2D,
                             tex_diag_ds_[i], 0);
    }
    const GLenum bufs[4] = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1,
                            GL_COLOR_ATTACHMENT2, GL_COLOR_ATTACHMENT3};
    glDrawBuffers(4, bufs);
    const bool ds_ok =
        glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    if (!ds_ok) {
      glDeleteTextures(4, tex_diag_ds_);
      for (int i = 0; i < 4; ++i) tex_diag_ds_[i] = 0;
    }
    while (glGetError() != GL_NO_ERROR) {
    }  // 探测期的错误不外泄
    if (ds_ok) {
      diag_ds_f32_ = attempt == 0;
      break;
    }
  }
  // 位打包输出：8×2 RGBA8（ES 3.0 保证可渲染；readback 用 GL_RGBA+GL_UNSIGNED_BYTE
  // = ES 3.0 **唯一保证可用**的组合，也是本项目 tex_hr_stat_/tex_local_ 在跑的路径）。
  // @domain: A5
  makeTex(&tex_diag_out_, &fbo_diag_out_, GL_RGBA8, kStatPackW, kStatPackH);
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_diag_out_);
  const GLenum one = GL_COLOR_ATTACHMENT0;
  glDrawBuffers(1, &one);
  const bool out_ok =
      tex_diag_out_ != 0 &&
      glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
  while (glGetError() != GL_NO_ERROR) {
  }
  if (!out_ok || !tex_diag_ds_[0]) {
    releaseDiagTargets();
  }
  glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(prev_fbo));
  glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(prev_tex));
  glActiveTexture(static_cast<GLenum>(prev_unit));
  return tex_diag_out_ != 0 && tex_diag_ds_[0] != 0;
}

void FrameRenderer::releaseDiagTargets() {
  if (tex_diag_ds_[0]) {
    glDeleteTextures(4, tex_diag_ds_);
    for (int i = 0; i < 4; ++i) tex_diag_ds_[i] = 0;
  }
  if (fbo_diag_ds_) {
    glDeleteFramebuffers(1, &fbo_diag_ds_);
    fbo_diag_ds_ = 0;
  }
  if (tex_diag_out_) {
    glDeleteTextures(1, &tex_diag_out_);
    tex_diag_out_ = 0;
  }
  if (fbo_diag_out_) {
    glDeleteFramebuffers(1, &fbo_diag_out_);
    fbo_diag_out_ = 0;
  }
}

// 阶段纹理 → GPU 内归约 → 64 字节读回 → 一条 diag.stats。
// **不得影响成片链**：源纹理只作 sampler 读、只写诊断自有的 4+1 张纹理，
// 进入/退出成对保存恢复 FBO/viewport/program/纹理单元与绑定/blend/scissor/depth，
// 任何一步失败只写 diag.stats_fail 并返回。
void FrameRenderer::diagStatsTex(const char* stage, GLuint tex, int w, int h,
                                 const FilmParams* p) {
  if (!diagOn() || !g_diag_chain) return;
  if (!tex || w <= 0 || h <= 0) return;
  if (!prog_diag_down_ || !prog_diag_pack_ || !ensureDiagTargets()) {
    diagStatsLog(stage, nullptr, p, "no_target");
    return;
  }
  GLint prev_fbo = 0, prev_vp[4] = {0, 0, 0, 0}, prev_prog = 0, prev_unit = 0;
  GLint prev_tex[4] = {0, 0, 0, 0};  // pass B 用 unit 0..3（项目其它 pass 用到 unit≥3），逐个还原
  glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prev_fbo);
  glGetIntegerv(GL_VIEWPORT, prev_vp);
  glGetIntegerv(GL_CURRENT_PROGRAM, &prev_prog);
  glGetIntegerv(GL_ACTIVE_TEXTURE, &prev_unit);
  for (int i = 0; i < 4; ++i) {
    glActiveTexture(GL_TEXTURE0 + i);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &prev_tex[i]);
  }
  const GLboolean prev_blend = glIsEnabled(GL_BLEND);
  const GLboolean prev_scissor = glIsEnabled(GL_SCISSOR_TEST);
  const GLboolean prev_depth = glIsEnabled(GL_DEPTH_TEST);
  // 进入前先读走历史 GL 错误：本段只对自己的错误负责，历史错误不冒充统计失败
  //（成片链无 glGetError 消费者，丢掉无信息损失；留在这里只为不误判 post_err）。
  while (glGetError() != GL_NO_ERROR) {
  }

  glDisable(GL_BLEND);
  glDisable(GL_SCISSOR_TEST);
  glDisable(GL_DEPTH_TEST);
  // pass A：源 → 4 张 kStatGridW×kStatGridH 归约网格（MRT，Σ 全程 float）
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_diag_ds_);
  glViewport(0, 0, kStatGridW, kStatGridH);
  glUseProgram(prog_diag_down_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, tex);
  glUniform1i(u_diag_down_.src, 0);
  glUniform2i(u_diag_down_.size, w, h);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  // pass B：4 张网格 → 16 个 float32，IEEE754 位模式拆成字节写 RGBA8
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_diag_out_);
  glViewport(0, 0, kStatPackW, kStatPackH);
  glUseProgram(prog_diag_pack_);
  for (int i = 0; i < 4; ++i) {
    glActiveTexture(GL_TEXTURE0 + i);
    glBindTexture(GL_TEXTURE_2D, tex_diag_ds_[i]);
  }
  glUniform1i(u_diag_pack_.t0, 0);
  glUniform1i(u_diag_pack_.t1, 1);
  glUniform1i(u_diag_pack_.t2, 2);
  glUniform1i(u_diag_pack_.t3, 3);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glFinish();
  unsigned char buf[kStatPackW * kStatPackH * 4] = {0};
  glReadPixels(0, 0, kStatPackW, kStatPackH, GL_RGBA, GL_UNSIGNED_BYTE, buf);
  GLenum post_err = GL_NO_ERROR;
  for (GLenum e = glGetError(); e != GL_NO_ERROR; e = glGetError()) post_err = e;

  glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(prev_fbo));
  glViewport(prev_vp[0], prev_vp[1], prev_vp[2], prev_vp[3]);
  glUseProgram(static_cast<GLuint>(prev_prog));
  for (int i = 0; i < 4; ++i) {
    glActiveTexture(GL_TEXTURE0 + i);
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(prev_tex[i]));
  }
  glActiveTexture(static_cast<GLenum>(prev_unit));
  if (prev_blend) glEnable(GL_BLEND);
  if (prev_scissor) glEnable(GL_SCISSOR_TEST);
  if (prev_depth) glEnable(GL_DEPTH_TEST);
  while (glGetError() != GL_NO_ERROR) {
  }  // 恢复动作自身的错误也不外泄

  if (post_err != GL_NO_ERROR) {
    diagStatsLog(stage, nullptr, p, "gl_error");
    return;
  }
  float v[kSlotCount];
  for (int i = 0; i < kSlotCount; ++i) v[i] = statUnpack(buf + i * 4);
  // 读回全零 = FBO/读回静默失效（X300 上遇到过同型：attachment 配错格式静默回零）
  if (!(v[kSlotN] > 0.0f)) {
    diagStatsLog(stage, nullptr, p, "readback");
    return;
  }
  diagStatsLog(stage, v, p, nullptr);
}

// 最终成片：引擎内本就是 RGBA8 读回（finishStillChain 的 glReadPixels），
// 在 CPU 侧按**与 GPU 端逐字相同的分块/采样/分档口径**统计已读回的缓冲，
// **不产生任何 GL 调用**。值 = u8/255 ⇒ 精度上限 8bit（1 级 = 0.0039）：
// 它只作"基准/终点"，判据一律用上面的 float 阶段。
void FrameRenderer::diagStatsU8(const char* stage, const unsigned char* px, int w,
                                int h, const FilmParams* p) {
  if (!diagOn() || !g_diag_chain || !px || w <= 0 || h <= 0) return;
  float v[kSlotCount] = {0};
  for (int gy = 0; gy < kStatGridH; ++gy) {
    const int y0 = gy * h / kStatGridH;
    const int y1 = std::max(((gy + 1) * h + kStatGridH - 1) / kStatGridH, y0 + 1);
    const int sy = y1 - y0;
    for (int gx = 0; gx < kStatGridW; ++gx) {
      const int x0 = gx * w / kStatGridW;
      const int x1 = std::max(((gx + 1) * w + kStatGridW - 1) / kStatGridW, x0 + 1);
      const int sx = x1 - x0;
      for (int j = 0; j < kStatTap; ++j) {
        const int y = std::min(y0 + j * sy / kStatTap, h - 1);
        const unsigned char* row = px + static_cast<size_t>(y) * w * 4;
        for (int i = 0; i < kStatTap; ++i) {
          const int x = std::min(x0 + i * sx / kStatTap, w - 1);
          const unsigned char* q = row + static_cast<size_t>(x) * 4;
          const float r = q[0] / 255.0f, g = q[1] / 255.0f, b = q[2] / 255.0f;
          v[kSlotSumR] += r;
          v[kSlotSumG] += g;
          v[kSlotSumB] += b;
          v[kSlotN] += 1.0f;
          const float L = 0.2126f * r + 0.7152f * g + 0.0722f * b;
          const int band = L < 0.05f ? 0 : (L < 0.15f ? 1 : (L < 0.35f ? 2
                                                              : (L < 0.60f ? 3 : 4)));
          v[kSlotRgBand + band] += r - g;
          v[kSlotNBand + band] += 1.0f;
        }
      }
    }
  }
  diagStatsLog(stage, v, p, nullptr);
}

FrameRenderer::~FrameRenderer() { release(); }

// `u_film_` 的**全部** location 查询（引擎插槽阶段 2 起独立成函数：换 program 后
// location 全部失效，必须重跑 —— 包括 uTex 这类公共 uniform）。
// 从 init() 原样搬出（阶段 1 之前它就写在那里），只**追加**引擎 1 的 7 个 uSig*。
void FrameRenderer::queryFilmUniforms() {
  u_film_.tex = glGetUniformLocation(prog_film_, "uTex");
  u_film_.hal_domain = glGetUniformLocation(prog_film_, "uHalDomainMap");
  u_film_.domain_ratio = glGetUniformLocation(prog_film_, "uDomainRatio");
  u_film_.sat = glGetUniformLocation(prog_film_, "uSat");
  u_film_.split = glGetUniformLocation(prog_film_, "uSplit");
  u_film_.mono = glGetUniformLocation(prog_film_, "uMono");
  u_film_.tone_k = glGetUniformLocation(prog_film_, "uToneK");
  u_film_.look = glGetUniformLocation(prog_film_, "uLook");
  u_film_.look_sat = glGetUniformLocation(prog_film_, "uLookSat");
  u_film_.look_chroma = glGetUniformLocation(prog_film_, "uLookChroma");
  // v0.7.8：用户 EV 滑杆（显示亮度补偿，乘在影调链之后，见 kFsFilm 注释）
  u_film_.exposure = glGetUniformLocation(prog_film_, "uExposure");
  // v0.7.3：filmic 的 spline 系数与 EV 标定（替代已废除的 uExposureGain）
  // E4-4：per-channel —— 系数按**段**分组、vec3 的 .r/.g/.b = R/G/B（见 u_film_ 注释）
  u_film_.filmic_toe_m1 = glGetUniformLocation(prog_film_, "uFilmicToeM1");
  u_film_.filmic_toe_m2 = glGetUniformLocation(prog_film_, "uFilmicToeM2");
  u_film_.filmic_toe_m3 = glGetUniformLocation(prog_film_, "uFilmicToeM3");
  u_film_.filmic_toe_m4 = glGetUniformLocation(prog_film_, "uFilmicToeM4");
  u_film_.filmic_toe_m5 = glGetUniformLocation(prog_film_, "uFilmicToeM5");
  u_film_.filmic_sh_m1 = glGetUniformLocation(prog_film_, "uFilmicShM1");
  u_film_.filmic_sh_m2 = glGetUniformLocation(prog_film_, "uFilmicShM2");
  u_film_.filmic_sh_m3 = glGetUniformLocation(prog_film_, "uFilmicShM3");
  u_film_.filmic_sh_m4 = glGetUniformLocation(prog_film_, "uFilmicShM4");
  u_film_.filmic_sh_m5 = glGetUniformLocation(prog_film_, "uFilmicShM5");
  u_film_.filmic_lin_m1 = glGetUniformLocation(prog_film_, "uFilmicLinM1");
  u_film_.filmic_lin_m2 = glGetUniformLocation(prog_film_, "uFilmicLinM2");
  u_film_.filmic_lat_min = glGetUniformLocation(prog_film_, "uFilmicLatMin");
  u_film_.filmic_lat_max = glGetUniformLocation(prog_film_, "uFilmicLatMax");
  u_film_.filmic_black_ev = glGetUniformLocation(prog_film_, "uFilmicBlackEv");
  u_film_.filmic_dr = glGetUniformLocation(prog_film_, "uFilmicDr");
  u_film_.filmic_input_min = glGetUniformLocation(prog_film_, "uFilmicInputMin");
  u_film_.filmic_input_max = glGetUniformLocation(prog_film_, "uFilmicInputMax");
  u_film_.filmic_power = glGetUniformLocation(prog_film_, "uFilmicPower");  u_film_.filmic_black_disp = glGetUniformLocation(prog_film_, "uFilmicBlackDisp");
  u_film_.local = glGetUniformLocation(prog_film_, "uLocal");
  u_film_.local_strength = glGetUniformLocation(prog_film_, "uLocalStrength");
  u_film_.local_knee = glGetUniformLocation(prog_film_, "uLocalKnee");
  u_film_.local_knee_hi = glGetUniformLocation(prog_film_, "uLocalKneeHi");
  u_film_.local_ref = glGetUniformLocation(prog_film_, "uLocalRef");
  u_film_.local_size = glGetUniformLocation(prog_film_, "uLocalSize");
  u_film_.gamut = glGetUniformLocation(prog_film_, "uGamut");
  // 引擎 1（sigmoid，阶段 2）：7 个 uSig*。引擎 0 的 program 里全部返回 −1（那些
  // uniform 不存在）⇒ 下发的 glUniform1f(−1, …) 是安全 no-op（GL 规范），零分支。
  // ⚠ 名字必须与 `kFsSigmoidDef` 段首的声明**逐字一致**（validate-shaders.sh 会核对）。
  u_film_.sig_white_target = glGetUniformLocation(prog_film_, "uSigWhiteTarget");
  u_film_.sig_black_target = glGetUniformLocation(prog_film_, "uSigBlackTarget");
  u_film_.sig_paper_exposure = glGetUniformLocation(prog_film_, "uSigPaperExposure");
  u_film_.sig_film_fog = glGetUniformLocation(prog_film_, "uSigFilmFog");
  u_film_.sig_film_power = glGetUniformLocation(prog_film_, "uSigFilmPower");
  u_film_.sig_paper_power = glGetUniformLocation(prog_film_, "uSigPaperPower");
  u_film_.sig_hue_preservation = glGetUniformLocation(prog_film_, "uSigHuePreservation");
}

// 引擎插槽（阶段 2）：按注册表把 `prog_film_` 换成 `slot` 的引擎。
// 调用点只有一个（passFilm 检测到请求的 slot != 已编译 slot 时），**不是每帧路径**：
// 一次引擎切换 = 一次编译（与 init() 编译引擎 0 同量级），之后每帧只是比一个 int。
// 失败语义（方案 §4 契约 5）：**保持原 program 不变**并返回 false —— 调用方继续用
// 引擎 0 渲染（照片优先，不崩、不 identity 伪装），失败原因已由 compileShader /
// linkProgram 记 kError。
bool FrameRenderer::buildFilmProgram(int slot) {
  const std::string src = filmSource(slot);
  const GLuint fs = compileShader(GL_FRAGMENT_SHADER, src.c_str());
  if (fs == 0) return false;  // 编译失败：原因已记 kError，保持原 program
  const GLuint prog = linkProgram(vs_plain_, fs);
  glDeleteShader(fs);
  if (prog == 0) return false;  // 链接失败：同上
  if (prog_film_ != 0) glDeleteProgram(prog_film_);
  prog_film_ = prog;
  film_engine_slot_ = slot;
  queryFilmUniforms();  // location 是 per-program 的 ⇒ 必须重查（含 uTex 等公共项）
  logFilmSourceSelfCheck(src, slot);  // 开发期自检：附 crc32/bytes/chars 供真机核对
  // 一行日志说清"引擎 1 用的是哪套参数"（2026-10-14 用户要求：别再靠推断）：
  // c=1.5（PC/darktable 默认）⇒ sig_pe 0.359695 / sig_fp 1.490909；c=1.2 ⇒ 0.592131 / 1.190909。
  Logger::instance().log(LogLevel::kInfo, "engine", "engine.slot_built",
                         "{\"slot\":" + std::to_string(slot) +
                             ",\"sig_pe\":" + std::to_string(sigmoid_.paper_exposure) +
                             ",\"sig_ff\":" + std::to_string(sigmoid_.film_fog) +
                             ",\"sig_fp\":" + std::to_string(sigmoid_.film_power) +
                             ",\"sig_wt\":" + std::to_string(sigmoid_.white_target) + "}");
  return true;
}

bool FrameRenderer::init() {
  vs_rot_ = compileShader(GL_VERTEX_SHADER, kVertRot);
  vs_plain_ = compileShader(GL_VERTEX_SHADER, kVertPlain);
  const GLuint fs_oes = compileShader(GL_FRAGMENT_SHADER, kFsOes);
  const GLuint fs_still = compileShader(GL_FRAGMENT_SHADER, kFsStill);
  // ── 引擎插槽（阶段 2）：film pass 的源码 = 公共模板三段 + 引擎注入段，此处拼接 ──
  // ⚠ **为什么 init() 恒按 slot 0 编译**（任务书第 7 条的落地口径，已实测核对）：
  //   `init()` **签名里没有 `FilmParams`**，调用方 `Bridge.cpp` 的 `nativeEngineStart`
  //   也在任何参数下发**之前**就 init（参数随预览/静拍帧才到）⇒ 初始化期物理上拿不到
  //   `[98] engine_slot`（阶段 1 的注释已写明这一点，本次复核确认无参数快照可用）。
  //   因此：init() 编译**引擎 0**（= 默认档，旧预设/未下发时就是它），运行期首个非 0
  //   取值由 `passFilm` 触发**一次性**重建（`buildFilmProgram`，见其注释）—— 那才是
  //   "按 `[98]` 取 slot" 的实际落点。每帧只多一次 int 比较，不重编译。
  // 引擎 1（sigmoid）的 CPU 侧参数：init() 期算一次（当前无 UI 通道 ⇒ 恒默认值，
  // 见 SigmoidParams.h 的 `kSigmoidDefault*`），每帧无条件下发 7 个 uSig*（passFilm）。
  // 公式只有一处实现（CPU 侧 computeSigmoidParams，逐行照抄 darktable commit_params）。
  sigmoid_ = computeSigmoidParams(kSigmoidDefaultContrast, kSigmoidDefaultSkew,
                                  kSigmoidDefaultWhiteTarget, kSigmoidDefaultBlackTarget,
                                  kSigmoidDefaultHuePreservation);
  const std::string fs_film_src = filmSource(0);
  logFilmSourceSelfCheck(fs_film_src, 0);  // 开发期自检：拼接 vs 原文逐字节（只跑一次）
  const GLuint fs_film = compileShader(GL_FRAGMENT_SHADER, fs_film_src.c_str());
  const GLuint fs_out = compileShader(GL_FRAGMENT_SHADER, kFsOut);
  // E1-3 编辑器通路（kFsStyle）：显示域输入 → LUT/颗粒/dither。**进硬失败检查**
  // —— 历史事故：漏加进下面的检查列表，shader 编译失败连"引擎起不来"都不会发生，
  // 只在真机上表现为"新 pass 没效果"（ADR §6.2）。
  const GLuint fs_style = compileShader(GL_FRAGMENT_SHADER, kFsStyle);
  // E4-5 halation（光晕）：3 个 fragment shader 服务 16 个 pass（Thr 与 4 次 Down
  // 共用 kFsHaloThr —— 数学逐字相同，只有 uThrOn/uDecodeIn 两个门控不同）。
  // **进硬失败检查**（同 fs_style 的理由：编译失败若不在 init 暴露，真机只表现为
  // "光晕没效果"= 静默失败）。
  const GLuint fs_halo_thr = compileShader(GL_FRAGMENT_SHADER, kFsHaloThr);
  const GLuint fs_halo_blur = compileShader(GL_FRAGMENT_SHADER, kFsHaloBlur);
  const GLuint fs_halo_mix = compileShader(GL_FRAGMENT_SHADER, kFsHaloMix);
  // SG-1 柔光：共享金字塔之上的单半径加权合成（1/2 分辨率）
  const GLuint fs_glow_sum = compileShader(GL_FRAGMENT_SHADER, kFsGlowSum);
  const GLuint fs_hist = compileShader(GL_FRAGMENT_SHADER, kFsHist);
  const GLuint fs_local = compileShader(GL_FRAGMENT_SHADER, kFsLocal);
  const GLuint fs_fill = compileShader(GL_FRAGMENT_SHADER, kFsFillTest);
  const GLuint fs_raw = compileShader(GL_FRAGMENT_SHADER, kFsRaw);
  const GLuint fs_rcd_dir = compileShader(GL_FRAGMENT_SHADER, kFsRcdDir);
  const GLuint fs_rcd_g = compileShader(GL_FRAGMENT_SHADER, kFsRcdG);
  const GLuint fs_rcd_chroma = compileShader(GL_FRAGMENT_SHADER, kFsRcdChroma);
  // P3 拆双 pass（v0.10.180，[58] rcd_p3_split）：3a 对角填充 / 3b G 位轴向
  const GLuint fs_rcd_chroma_a = compileShader(GL_FRAGMENT_SHADER, kFsRcdChromaA);
  const GLuint fs_rcd_chroma_b = compileShader(GL_FRAGMENT_SHADER, kFsRcdChromaB);
  // 第 4a 期（S14）：D3→D4 独立 CCM pass
  const GLuint fs_ccm = compileShader(GL_FRAGMENT_SHADER, kFsCcm);
  // HDR 曝光融合 P2（0.12.77）：可选能力（prefs hdr_fusion=2 才走），编译失败
  // 只禁用 Mertens（成片回退 zero 链），不进 init 硬失败判据（照 gainmap 处理）
  const GLuint fs_hdr_mertens = compileShader(GL_FRAGMENT_SHADER, kFsHdrMertens);
  const GLuint fs_amaze_up = compileShader(GL_FRAGMENT_SHADER, kFsAmazeUp);
  const GLuint fs_raw_dn = compileShader(GL_FRAGMENT_SHADER, kFsRawDenoise);
  const GLuint fs_chroma_dn = compileShader(GL_FRAGMENT_SHADER, kFsChromaDenoise);
  // 档 A（v0.10.165）：伪彩强度图统计（32×32 网格归约，passChromaDenoise 内）
  const GLuint fs_moire_stat = compileShader(GL_FRAGMENT_SHADER, kFsMoireStat);
  // 诊断统计（v0.10.166）：GPU 内归约 + 位打包（见 kFsStatDown/kFsStatPack 注释）
  const GLuint fs_diag_down = compileShader(GL_FRAGMENT_SHADER, kFsStatDown);
  const GLuint fs_diag_pack = compileShader(GL_FRAGMENT_SHADER, kFsStatPack);
  const GLuint fs_chroma_down = compileShader(GL_FRAGMENT_SHADER, kFsChromaDown);
  const GLuint fs_chroma_at = compileShader(GL_FRAGMENT_SHADER, kFsChromaAtrous);
  const GLuint fs_chroma_apply = compileShader(GL_FRAGMENT_SHADER, kFsChromaApply);
  const GLuint fs_mfnr_pyr = compileShader(GL_FRAGMENT_SHADER, kFsMfnrPyr);
  const GLuint fs_mfnr_down = compileShader(GL_FRAGMENT_SHADER, kFsMfnrDown);
  const GLuint fs_mfnr_fuse = compileShader(GL_FRAGMENT_SHADER, kFsMfnrFuse);
  const GLuint fs_mfnr_mis =
      compileShader(GL_FRAGMENT_SHADER, kFsMfnrMismatch);
  const GLuint fs_mfnr_satfix = compileShader(GL_FRAGMENT_SHADER, kFsMfnrSatFix);
  // 0.12.133（方案 B 块 3）：短帧真值图 + 全饱和区亮度 inpaint
  const GLuint fs_mfnr_sv = compileShader(GL_FRAGMENT_SHADER, kFsMfnrShortVal);
  const GLuint fs_hl_inp = compileShader(GL_FRAGMENT_SHADER, kFsHlInpaint);
  const GLuint fs_raw_dn_f = compileShader(GL_FRAGMENT_SHADER, kFsRawDenoiseFused);
  const GLuint fs_raw_dn_ts = compileShader(GL_FRAGMENT_SHADER, kFsRawDenoiseFusedTs);
  // NN 降噪 pass（v0.10.102）：域桥 shader（pack/unpack），纯 fragment 低风险
  const GLuint fs_nn_pack = compileShader(GL_FRAGMENT_SHADER, kFsNnPack);
  const GLuint fs_nn_unpack = compileShader(GL_FRAGMENT_SHADER, kFsNnUnpack);
  // 紫边抑制（v0.10.134）：down+Lab / 检测 / 均值 / 替换
  const GLuint fs_df_down = compileShader(GL_FRAGMENT_SHADER, kFsDefringeDown);
  const GLuint fs_df_det = compileShader(GL_FRAGMENT_SHADER, kFsDefringeDetect);
  const GLuint fs_df_avg = compileShader(GL_FRAGMENT_SHADER, kFsDefringeAvg);
  const GLuint fs_df_apply = compileShader(GL_FRAGMENT_SHADER, kFsDefringeApply);
  const GLuint fs_cs = compileShader(GL_FRAGMENT_SHADER, kFsColorSmooth);
  const GLuint fs_cm = compileShader(GL_FRAGMENT_SHADER, kFsColorMedian);
  const GLuint fs_usm_down = compileShader(GL_FRAGMENT_SHADER, kFsUsmDown);
  const GLuint fs_usm = compileShader(GL_FRAGMENT_SHADER, kFsUsm);
  const GLuint fs_gainmap = compileShader(GL_FRAGMENT_SHADER, kFsGainmap);
  // 横向色差校正（v0.10.170）：1/8 降采样（估计）+ 径向位移校正
  const GLuint fs_ca_ds = compileShader(GL_FRAGMENT_SHADER, kFsCaDown);
  const GLuint fs_ca_corr = compileShader(GL_FRAGMENT_SHADER, kFsCaCorr);
  // PC ESD3D2 完整移植（v0.10.175）：本体 + 尺度结构（gaussdown / guided upsample）
  const GLuint fs_pc3d = compileShader(GL_FRAGMENT_SHADER, kFsPc3d);
  const GLuint fs_pc3d_down = compileShader(GL_FRAGMENT_SHADER, kFsPc3dDown);
  const GLuint fs_pc3d_up = compileShader(GL_FRAGMENT_SHADER, kFsPc3dUp);
  // 降噪专项 A（v0.10.49）：compute program 一律**拍照时现场创建、用完删除**
  //（spike 模式）—— 实锤:init 创建的 compute program 在该 Mali 驱动上经
  // 预览链大量 program 创建后写入全丢（主内核 + 1-texel 微测试一致失败），
  // 现场创建的 spike program 一致成功（spikeOk:true,0.10.47/48 双轮）。
  // init 只留 sum 收口 pass（fragment,300 es,不受影响）。
  const GLuint fs_mfnr_fft_sum = compileShader(GL_FRAGMENT_SHADER, kFsMfnrFftSum);
  // v0.10.15：darktable filmic 高光重建（9 个）
  const GLuint fs_hr_mask = compileShader(GL_FRAGMENT_SHADER, kFsHrMask);
  const GLuint fs_hr_inp = compileShader(GL_FRAGMENT_SHADER, kFsHrInpaint);
  const GLuint fs_hr_blur = compileShader(GL_FRAGMENT_SHADER, kFsHrBlur);
  const GLuint fs_hr_hf = compileShader(GL_FRAGMENT_SHADER, kFsHrHF);
  const GLuint fs_hr_rin = compileShader(GL_FRAGMENT_SHADER, kFsHrReconInit);
  const GLuint fs_hr_step = compileShader(GL_FRAGMENT_SHADER, kFsHrReconStep);
  const GLuint fs_hr_norms = compileShader(GL_FRAGMENT_SHADER, kFsHrNorms);
  const GLuint fs_hr_ratios = compileShader(GL_FRAGMENT_SHADER, kFsHrRatios);
  const GLuint fs_hr_restore = compileShader(GL_FRAGMENT_SHADER, kFsHrRestore);
  const GLuint fs_hr_stat = compileShader(GL_FRAGMENT_SHADER, kFsHrStat);
  // RAW 域高光重建（darktable highlights "inpaint opposed"，2026-10-03）：5 个
  // fragment pass —— mask(3×3 photosite 块) / 膨胀(块) / 色度统计(16×16 归约) /
  // 参考色度场(1/32 块，G 重建用) / 应用
  const GLuint fs_satm = compileShader(GL_FRAGMENT_SHADER, kFsSatMask);   // 0.12.181
  const GLuint fs_hl_mask = compileShader(GL_FRAGMENT_SHADER, kFsHlMask);
  const GLuint fs_hl_dil = compileShader(GL_FRAGMENT_SHADER, kFsHlDilate);
  const GLuint fs_hl_chroma = compileShader(GL_FRAGMENT_SHADER, kFsHlChroma);
  const GLuint fs_hl_apply = compileShader(GL_FRAGMENT_SHADER, kFsHlApply);
  const GLuint fs_hl_ref = compileShader(GL_FRAGMENT_SHADER, kFsHlRefChroma);
  const GLuint fs_hl_refdown = compileShader(GL_FRAGMENT_SHADER, kFsHlRefDown);
  if (!vs_rot_ || !vs_plain_ || !fs_oes || !fs_still || !fs_film || !fs_out ||
      !fs_style ||
      !fs_fill || !fs_raw || !fs_rcd_dir || !fs_rcd_g || !fs_rcd_chroma ||
      !fs_rcd_chroma_a || !fs_rcd_chroma_b ||
      !fs_amaze_up ||
      !fs_ccm ||
      !fs_raw_dn || !fs_chroma_dn || !fs_chroma_down || !fs_chroma_at ||
      !fs_chroma_apply || !fs_hist || !fs_local ||
      !fs_mfnr_pyr || !fs_mfnr_down || !fs_mfnr_fuse || !fs_mfnr_mis ||
      !fs_raw_dn_f || !fs_raw_dn_ts || !fs_hr_mask ||
      !fs_nn_pack || !fs_nn_unpack ||
      !fs_hr_inp || !fs_hr_blur ||
      !fs_hr_hf || !fs_hr_rin || !fs_hr_step || !fs_hr_norms || !fs_hr_ratios ||
      !fs_hr_restore || !fs_hr_stat || !fs_hl_mask || !fs_hl_dil ||
      !fs_hl_chroma || !fs_hl_apply || !fs_hl_ref || !fs_hl_refdown)
    return false;

  prog_oes_ = linkProgram(vs_rot_, fs_oes);
  prog_still_ = linkProgram(vs_rot_, fs_still);
  prog_film_ = linkProgram(vs_plain_, fs_film);
  prog_out_ = linkProgram(vs_plain_, fs_out);
  // E1-3 编辑器通路：kFsStyle 与 kFsOut 同几何（vs_plain_，无旋转/无 ST）
  prog_style_ = linkProgram(vs_plain_, fs_style);
  // E4-5 halation：三个 pass 都是全屏三角形 + vUV0 归一化采样（无旋转/无 ST），
  // 与 kFsStyle / kFsOut 同几何。5 级金字塔各级尺寸不同 ⇒ 靠 glViewport 切换。
  prog_halo_thr_ = linkProgram(vs_plain_, fs_halo_thr);
  prog_halo_blur_ = linkProgram(vs_plain_, fs_halo_blur);
  prog_halo_mix_ = linkProgram(vs_plain_, fs_halo_mix);
  // SG-1 柔光：与 halation 同几何（vs_plain_ + 全屏三角形 + vUV0 归一化采样）
  prog_glow_sum_ = linkProgram(vs_plain_, fs_glow_sum);
  prog_hist_ = linkProgram(vs_plain_, fs_hist);
  prog_local_ = linkProgram(vs_plain_, fs_local);
  prog_fill_ = linkProgram(vs_rot_, fs_fill);
  prog_raw_ = linkProgram(vs_rot_, fs_raw);
  prog_rcd_dir_ = linkProgram(vs_plain_, fs_rcd_dir);
  prog_rcd_g_ = linkProgram(vs_plain_, fs_rcd_g);
  prog_rcd_chroma_ = linkProgram(vs_rot_, fs_rcd_chroma);
  // 3a 用 vs_plain_（源几何直写，与 kFsRcdDir/kFsRcdG 同构）；3b 用 kVertRot
  // （成片几何输出，与 kFsRcdChroma 同款坐标变换）
  prog_rcd_chroma_a_ = linkProgram(vs_plain_, fs_rcd_chroma_a);
  prog_rcd_chroma_b_ = linkProgram(vs_rot_, fs_rcd_chroma_b);
  // 与 passChromaDenoise 同构：kVertRot + uST 单位阵，frag 用 gl_FragCoord 定位
  prog_ccm_ = linkProgram(vs_rot_, fs_ccm);
  prog_amaze_up_ = linkProgram(vs_rot_, fs_amaze_up);
  prog_raw_dn_ = linkProgram(vs_plain_, fs_raw_dn);
  prog_chroma_dn_ = linkProgram(vs_rot_, fs_chroma_dn);
  prog_moire_stat_ = linkProgram(vs_plain_, fs_moire_stat);
  // 诊断统计（v0.10.166）：两个 pass 都用 vs_plain_（全屏三角形 + gl_FragCoord 定位）
  prog_diag_down_ = linkProgram(vs_plain_, fs_diag_down);
  prog_diag_pack_ = linkProgram(vs_plain_, fs_diag_pack);
  prog_chroma_down_ = linkProgram(vs_rot_, fs_chroma_down);
  prog_chroma_at_ = linkProgram(vs_rot_, fs_chroma_at);
  prog_chroma_apply_ = linkProgram(vs_rot_, fs_chroma_apply);
  // 紫边抑制（v0.10.134）：down 用 vs_rot_（需要旋转一致的几何？不需要——
  // defringe 紧跟 P4，同为全分辨率纹理域，用 vs_plain_ 与 chroma_apply 同构；
  // vs_rot_ 顶点层带 kVertRot，detect/avg 的 gl_FragCoord 语义随视口，plain 更直白）
  prog_df_down_ = linkProgram(vs_plain_, fs_df_down);
  prog_df_det_ = linkProgram(vs_plain_, fs_df_det);
  prog_df_avg_ = linkProgram(vs_plain_, fs_df_avg);
  prog_df_apply_ = linkProgram(vs_plain_, fs_df_apply);
  prog_cs_ = linkProgram(vs_plain_, fs_cs);
  // 0.12.162：色差域 3×3 中值（真正的 darktable color_smoothing）。
  // 与 color_smooth 一样**不进 init 硬失败判据**：缺了只是少一段压伪彩。
  prog_cm_ = linkProgram(vs_plain_, fs_cm);
  if (prog_cm_) {
    u_cm_.tex = glGetUniformLocation(prog_cm_, "uTex");
    u_cm_.size = glGetUniformLocation(prog_cm_, "uSize");
    u_cm_.chroma_min = glGetUniformLocation(prog_cm_, "uMinChroma");
  }
  // 0.12.165：D4 大半径 USM
  prog_usm_down_ = linkProgram(vs_plain_, fs_usm_down);
  prog_usm_ = linkProgram(vs_plain_, fs_usm);
  if (prog_usm_down_) {
    u_usm_down_.tex = glGetUniformLocation(prog_usm_down_, "uTex");
    u_usm_down_.size = glGetUniformLocation(prog_usm_down_, "uSize");
  }
  if (prog_usm_) {
    u_usm_.tex = glGetUniformLocation(prog_usm_, "uTex");
    u_usm_.low = glGetUniformLocation(prog_usm_, "uLow");
    u_usm_.size = glGetUniformLocation(prog_usm_, "uSize");
    u_usm_.low_size = glGetUniformLocation(prog_usm_, "uLowSize");
    u_usm_.k = glGetUniformLocation(prog_usm_, "uK");
  }
  // HDR gainmap（v0.11.50）：**不进 init 的硬失败判据** —— 它是可选交付能力，
  // 缺了只影响 HDR 成片（普通 JPEG 链零影响），不该让整个引擎起不来。
  prog_gainmap_ = linkProgram(vs_plain_, fs_gainmap);
  if (prog_gainmap_) {
    u_gainmap_.hdr = glGetUniformLocation(prog_gainmap_, "uHdr");
    u_gainmap_.sdr = glGetUniformLocation(prog_gainmap_, "uSdr");
    u_gainmap_.src_size = glGetUniformLocation(prog_gainmap_, "uSrcSize");
    u_gainmap_.math = glGetUniformLocation(prog_gainmap_, "uGainmapMath");
    u_gainmap_.scene_mid = glGetUniformLocation(prog_gainmap_, "uSceneMid");
  }
  // HDR 曝光融合 P2（0.12.77）：同 gainmap 的可选处理 —— link/编译失败只记日志
  // 置 0，passHdrMertens 直接返回（成片 = zero 链，J10 语义），不拖死引擎。
  if (fs_hdr_mertens) prog_hdr_mertens_ = linkProgram(vs_plain_, fs_hdr_mertens);
  if (prog_hdr_mertens_) {
    u_hdr_mertens_.zero = glGetUniformLocation(prog_hdr_mertens_, "uZero");
    u_hdr_mertens_.side = glGetUniformLocation(prog_hdr_mertens_, "uSide");
    u_hdr_mertens_.size = glGetUniformLocation(prog_hdr_mertens_, "uSize");
    u_hdr_mertens_.wc = glGetUniformLocation(prog_hdr_mertens_, "uWc");
    u_hdr_mertens_.ws = glGetUniformLocation(prog_hdr_mertens_, "uWs");
    u_hdr_mertens_.we = glGetUniformLocation(prog_hdr_mertens_, "uWe");
    u_hdr_mertens_.scale = glGetUniformLocation(prog_hdr_mertens_, "uSideScale");
  } else {
    Logger::instance().log(LogLevel::kError, "engine", "hdr_mertens.compile_fail",
                           "{\"stage\":\"init\"}");
  }
  // 横向色差校正（v0.10.170）
  prog_ca_ds_ = linkProgram(vs_plain_, fs_ca_ds);
  prog_ca_corr_ = linkProgram(vs_plain_, fs_ca_corr);
  // PC ESD3D2 完整移植（v0.10.175）：**不进 init 的硬失败判据** —— v0.10.177 起
  //默认 [54] = 2（叠加档，docs/reports/pc3d-reval-REPORT.md），但任何一环缺失仍只是
  //"少一条可选路径"，绝不允许它把默认配置拖成初始化失败（照 fs_mfnr_fft_sum 的
  //处理）；失败时三个 prog 全置 0，passPc3d 自动返回 false ⇒ 调用方沿用
  //tex_scene_（等价于 [54] = 0 的链路）。
  // vs_plain_：kVertRot 与 kVertPlain 的 gl_Position 相同（都铺满视口），fragment
  // 用 gl_FragCoord + texelFetch 定位 ⇒ 两者等价（与 df/chroma_* 同构）。
  if (fs_pc3d) prog_pc3d_ = linkProgram(vs_plain_, fs_pc3d);
  if (fs_pc3d_down) prog_pc3d_down_ = linkProgram(vs_plain_, fs_pc3d_down);
  if (fs_pc3d_up) prog_pc3d_up_ = linkProgram(vs_plain_, fs_pc3d_up);
  if (!prog_pc3d_ || !prog_pc3d_down_ || !prog_pc3d_up_) {
    Logger::instance().log(LogLevel::kError, "engine", "pc3d.compile_fail",
                           "{\"stage\":\"link\"}");
    prog_pc3d_ = prog_pc3d_down_ = prog_pc3d_up_ = 0;
  }
  prog_mfnr_pyr_ = linkProgram(vs_plain_, fs_mfnr_pyr);
  prog_mfnr_down_ = linkProgram(vs_plain_, fs_mfnr_down);
  prog_mfnr_fuse_ = linkProgram(vs_plain_, fs_mfnr_fuse);
  prog_mfnr_mis_ = linkProgram(vs_plain_, fs_mfnr_mis);
  prog_mfnr_satfix_ = linkProgram(vs_plain_, fs_mfnr_satfix);
  // 0.12.133（方案 B 块 3）：可选能力 —— link 失败只降级（inpaint 不执行），
  // 不阻断 init（照片优先红线：整条链仍在 132 语义）。
  prog_mfnr_sv_ = linkProgram(vs_plain_, fs_mfnr_sv);
  prog_hl_inp_ = linkProgram(vs_plain_, fs_hl_inp);
  const GLuint fs_teq_down = compileShader(GL_FRAGMENT_SHADER, kFsTeqDown);
  const GLuint fs_teq_box = compileShader(GL_FRAGMENT_SHADER, kFsTeqBox);
  const GLuint fs_teq_coef = compileShader(GL_FRAGMENT_SHADER, kFsTeqCoef);
  const GLuint fs_teq_gain = compileShader(GL_FRAGMENT_SHADER, kFsTeqGain);
  const GLuint fs_teq_apply = compileShader(GL_FRAGMENT_SHADER, kFsTeqApply);
  prog_teq_down_ = linkProgram(vs_plain_, fs_teq_down);
  prog_teq_box_ = linkProgram(vs_plain_, fs_teq_box);
  prog_teq_coef_ = linkProgram(vs_plain_, fs_teq_coef);
  prog_teq_gain_ = linkProgram(vs_plain_, fs_teq_gain);
  prog_teq_apply_ = linkProgram(vs_plain_, fs_teq_apply);
  prog_raw_dn_fused_ = linkProgram(vs_plain_, fs_raw_dn_f);
  prog_raw_dn_ts_ = linkProgram(vs_plain_, fs_raw_dn_ts);
  prog_hl_mask_ = linkProgram(vs_plain_, fs_hl_mask);
  prog_satm_ = linkProgram(vs_plain_, fs_satm);   // 0.12.181 饱和掩码
  prog_hl_dil_ = linkProgram(vs_plain_, fs_hl_dil);
  prog_hl_chroma_ = linkProgram(vs_plain_, fs_hl_chroma);
  prog_hl_apply_ = linkProgram(vs_plain_, fs_hl_apply);
  prog_hl_ref_ = linkProgram(vs_plain_, fs_hl_ref);
  prog_hl_refdown_ = linkProgram(vs_plain_, fs_hl_refdown);
  prog_nn_pack_ = linkProgram(vs_plain_, fs_nn_pack);
  prog_nn_unpack_ = linkProgram(vs_plain_, fs_nn_unpack);
  // 相位组求和 pass（fragment;主 compute program 见 passMfnrFft 现场创建）。
  if (fs_mfnr_fft_sum) {
    prog_mfnr_fft_sum_ = linkProgram(vs_plain_, fs_mfnr_fft_sum);
  } else {
    Logger::instance().log(LogLevel::kError, "engine", "mfnr.fft.compile_fail",
                           "{\"stage\":\"sum\"}");
  }
  prog_hr_mask_ = linkProgram(vs_plain_, fs_hr_mask);
  prog_hr_inpaint_ = linkProgram(vs_plain_, fs_hr_inp);
  prog_hr_blur_ = linkProgram(vs_plain_, fs_hr_blur);
  prog_hr_hf_ = linkProgram(vs_plain_, fs_hr_hf);
  prog_hr_rin_ = linkProgram(vs_plain_, fs_hr_rin);
  prog_hr_step_ = linkProgram(vs_plain_, fs_hr_step);
  prog_hr_norms_ = linkProgram(vs_plain_, fs_hr_norms);
  prog_hr_ratios_ = linkProgram(vs_plain_, fs_hr_ratios);
  prog_hr_restore_ = linkProgram(vs_plain_, fs_hr_restore);
  prog_hr_stat_ = linkProgram(vs_plain_, fs_hr_stat);
  glDeleteShader(fs_oes);
  glDeleteShader(fs_still);
  glDeleteShader(fs_film);
  glDeleteShader(fs_out);
  glDeleteShader(fs_style);
  glDeleteShader(fs_halo_thr);
  glDeleteShader(fs_halo_blur);
  glDeleteShader(fs_halo_mix);
  glDeleteShader(fs_glow_sum);
  glDeleteShader(fs_hist);
  glDeleteShader(fs_local);
  glDeleteShader(fs_fill);
  glDeleteShader(fs_raw);
  glDeleteShader(fs_rcd_dir);
  glDeleteShader(fs_rcd_g);
  glDeleteShader(fs_rcd_chroma);
  glDeleteShader(fs_ccm);
  glDeleteShader(fs_hdr_mertens);  // HDR 融合 P2（0=编译失败，Delete(0) 合法无操作）
  glDeleteShader(fs_amaze_up);
  glDeleteShader(fs_raw_dn);
  glDeleteShader(fs_chroma_dn);
  glDeleteShader(fs_moire_stat);   // 档 A（v0.10.165）
  glDeleteShader(fs_diag_down);    // 诊断统计（v0.10.166）
  glDeleteShader(fs_diag_pack);
  glDeleteShader(fs_chroma_down);
  glDeleteShader(fs_chroma_at);
  glDeleteShader(fs_chroma_apply);
  glDeleteShader(fs_mfnr_pyr);
  glDeleteShader(fs_mfnr_down);
  glDeleteShader(fs_mfnr_fuse);
  glDeleteShader(fs_mfnr_mis);
  glDeleteShader(fs_raw_dn_f);
  glDeleteShader(fs_raw_dn_ts);
  glDeleteShader(fs_hl_mask);
  glDeleteShader(fs_hl_dil);
  glDeleteShader(fs_hl_chroma);
  glDeleteShader(fs_hl_apply);
  glDeleteShader(fs_hl_ref);
  glDeleteShader(fs_hl_refdown);
  glDeleteShader(fs_nn_pack);
  glDeleteShader(fs_nn_unpack);
  glDeleteShader(fs_mfnr_fft_sum);
  glDeleteShader(fs_mfnr_sv);
  glDeleteShader(fs_hl_inp);
  glDeleteShader(fs_hr_mask);
  glDeleteShader(fs_hr_inp);
  glDeleteShader(fs_hr_blur);
  glDeleteShader(fs_hr_hf);
  glDeleteShader(fs_hr_rin);
  glDeleteShader(fs_hr_step);
  glDeleteShader(fs_hr_norms);
  glDeleteShader(fs_hr_ratios);
  glDeleteShader(fs_hr_restore);
  glDeleteShader(fs_hr_stat);
  glDeleteShader(fs_pc3d);
  glDeleteShader(fs_pc3d_down);
  glDeleteShader(fs_pc3d_up);
  if (!prog_oes_ || !prog_still_ || !prog_film_ || !prog_out_ || !prog_style_ ||
      !prog_halo_thr_ || !prog_halo_blur_ || !prog_halo_mix_ ||
      !prog_glow_sum_ ||   // SG-1 柔光合成（硬失败：缺它就等于柔光静默失效）
      !prog_fill_ ||
      !prog_raw_ || !prog_rcd_dir_ || !prog_rcd_g_ || !prog_rcd_chroma_ ||
      !prog_ccm_ || !prog_amaze_up_ ||
      !prog_raw_dn_ || !prog_chroma_dn_ || !prog_moire_stat_ || !prog_chroma_down_ ||
      !prog_chroma_at_ || !prog_chroma_apply_ || !prog_hist_ || !prog_local_ ||
      !prog_mfnr_pyr_ || !prog_mfnr_down_ || !prog_mfnr_fuse_ ||
      !prog_mfnr_mis_ || !prog_raw_dn_fused_ ||
      !prog_nn_pack_ || !prog_nn_unpack_ ||
      !prog_hr_mask_ ||
      !prog_hr_inpaint_ || !prog_hr_blur_ || !prog_hr_hf_ || !prog_hr_rin_ ||
      !prog_hr_step_ || !prog_hr_norms_ || !prog_hr_ratios_ ||
      !prog_hr_restore_ || !prog_hr_stat_ || !prog_hl_mask_ || !prog_hl_dil_ ||
      !prog_hl_chroma_ || !prog_hl_apply_ || !prog_hl_ref_ ||
      !prog_hl_refdown_)
    return false;

  u_oes_.st = glGetUniformLocation(prog_oes_, "uST");
  u_oes_.rot = glGetUniformLocation(prog_oes_, "uRot");
  u_oes_.scale = glGetUniformLocation(prog_oes_, "uScale");
  u_oes_.tex = glGetUniformLocation(prog_oes_, "uTex");
  u_oes_.wb = glGetUniformLocation(prog_oes_, "uWb");

  u_still_.st = glGetUniformLocation(prog_still_, "uST");
  u_still_.rot = glGetUniformLocation(prog_still_, "uRot");
  u_still_.scale = glGetUniformLocation(prog_still_, "uScale");
  u_still_.tex = glGetUniformLocation(prog_still_, "uTex");
  u_still_.wb = glGetUniformLocation(prog_still_, "uWb");
  if (u_still_.st >= 0) {  // uST 置单位阵（uniform 默认全 0）
    const float ident[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    glUseProgram(prog_still_);
    glUniformMatrix4fv(u_still_.st, 1, GL_FALSE, ident);
  }

  u_raw_.st = glGetUniformLocation(prog_raw_, "uST");
  u_raw_.rot = glGetUniformLocation(prog_raw_, "uRot");
  u_raw_.scale = glGetUniformLocation(prog_raw_, "uScale");
  u_raw_.tex = glGetUniformLocation(prog_raw_, "uRaw");
  u_raw_.size = glGetUniformLocation(prog_raw_, "uSize");
  u_raw_.cfa = glGetUniformLocation(prog_raw_, "uCfa");
  u_raw_.black = glGetUniformLocation(prog_raw_, "uBlack");
  u_raw_.white = glGetUniformLocation(prog_raw_, "uWhite");
  u_raw_.gain = glGetUniformLocation(prog_raw_, "uGain");
  u_raw_.ccm = glGetUniformLocation(prog_raw_, "uCcm");
  u_raw_.lsc = glGetUniformLocation(prog_raw_, "uLsc");
  u_raw_.lsc_size = glGetUniformLocation(prog_raw_, "uLscSize");
  if (u_raw_.st >= 0) {  // 同上：kVertRot 的 uST 默认全 0，必须置单位阵
    const float ident[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    glUseProgram(prog_raw_);
    glUniformMatrix4fv(u_raw_.st, 1, GL_FALSE, ident);
  }

  u_rcd_dir_.tex = glGetUniformLocation(prog_rcd_dir_, "uRaw");
  u_rcd_dir_.mos = glGetUniformLocation(prog_rcd_dir_, "uMos");
  u_rcd_dir_.mosaic = glGetUniformLocation(prog_rcd_dir_, "uMosaic");
  u_rcd_dir_.size = glGetUniformLocation(prog_rcd_dir_, "uSize");
  u_rcd_dir_.cfalut = glGetUniformLocation(prog_rcd_dir_, "uCfaLut");
  u_rcd_dir_.black = glGetUniformLocation(prog_rcd_dir_, "uBlack");
  u_rcd_dir_.white = glGetUniformLocation(prog_rcd_dir_, "uWhite");
  u_rcd_dir_.gain = glGetUniformLocation(prog_rcd_dir_, "uGain");

  u_rcd_g_.tex = glGetUniformLocation(prog_rcd_g_, "uRaw");
  u_rcd_g_.mos = glGetUniformLocation(prog_rcd_g_, "uMos");
  u_rcd_g_.mosaic = glGetUniformLocation(prog_rcd_g_, "uMosaic");
  u_rcd_g_.dir = glGetUniformLocation(prog_rcd_g_, "uDir");
  u_rcd_g_.size = glGetUniformLocation(prog_rcd_g_, "uSize");
  u_rcd_g_.cfalut = glGetUniformLocation(prog_rcd_g_, "uCfaLut");
  u_rcd_g_.black = glGetUniformLocation(prog_rcd_g_, "uBlack");
  u_rcd_g_.white = glGetUniformLocation(prog_rcd_g_, "uWhite");
  u_rcd_g_.gain = glGetUniformLocation(prog_rcd_g_, "uGain");
  u_rcd_g_.gorig = glGetUniformLocation(prog_rcd_g_, "uGOrig");

  u_rcd_chroma_.st = glGetUniformLocation(prog_rcd_chroma_, "uST");
  u_rcd_chroma_.rot = glGetUniformLocation(prog_rcd_chroma_, "uRot");
  u_rcd_chroma_.scale = glGetUniformLocation(prog_rcd_chroma_, "uScale");
  u_rcd_chroma_.tex = glGetUniformLocation(prog_rcd_chroma_, "uRaw");
  u_rcd_chroma_.mos = glGetUniformLocation(prog_rcd_chroma_, "uMos");
  u_rcd_chroma_.mosaic = glGetUniformLocation(prog_rcd_chroma_, "uMosaic");
  u_rcd_chroma_.dir = glGetUniformLocation(prog_rcd_chroma_, "uDir");
  u_rcd_chroma_.g = glGetUniformLocation(prog_rcd_chroma_, "uG");
  u_rcd_chroma_.size = glGetUniformLocation(prog_rcd_chroma_, "uSize");
  u_rcd_chroma_.cfalut = glGetUniformLocation(prog_rcd_chroma_, "uCfaLut");
  u_rcd_chroma_.black = glGetUniformLocation(prog_rcd_chroma_, "uBlack");
  u_rcd_chroma_.white = glGetUniformLocation(prog_rcd_chroma_, "uWhite");
  u_rcd_chroma_.gain = glGetUniformLocation(prog_rcd_chroma_, "uGain");
  u_rcd_chroma_.ccm = glGetUniformLocation(prog_rcd_chroma_, "uCcm");
  u_rcd_chroma_.lsc = glGetUniformLocation(prog_rcd_chroma_, "uLsc");
  u_rcd_chroma_.lsc_size = glGetUniformLocation(prog_rcd_chroma_, "uLscSize");
  u_rcd_chroma_.cd_fix = glGetUniformLocation(prog_rcd_chroma_, "uCdFix");
  u_rcd_chroma_.gorig = glGetUniformLocation(prog_rcd_chroma_, "uGOrig");
  // 0.12.133（方案 B 块 1）：RAW 饱和掩码（HL 块级膨胀掩码）→ alpha
  u_rcd_chroma_.hlmask = glGetUniformLocation(prog_rcd_chroma_, "uHlMask");
  u_rcd_chroma_.maskon = glGetUniformLocation(prog_rcd_chroma_, "uMaskOn");
  // P3 拆双 pass（v0.10.180，[58]）的 uniform 句柄
  u_rcd_chroma_a_.tex = glGetUniformLocation(prog_rcd_chroma_a_, "uRaw");
  u_rcd_chroma_a_.mos = glGetUniformLocation(prog_rcd_chroma_a_, "uMos");
  u_rcd_chroma_a_.mosaic = glGetUniformLocation(prog_rcd_chroma_a_, "uMosaic");
  u_rcd_chroma_a_.dir = glGetUniformLocation(prog_rcd_chroma_a_, "uDir");
  u_rcd_chroma_a_.g = glGetUniformLocation(prog_rcd_chroma_a_, "uG");
  u_rcd_chroma_a_.size = glGetUniformLocation(prog_rcd_chroma_a_, "uSize");
  u_rcd_chroma_a_.cfalut = glGetUniformLocation(prog_rcd_chroma_a_, "uCfaLut");
  u_rcd_chroma_a_.black = glGetUniformLocation(prog_rcd_chroma_a_, "uBlack");
  u_rcd_chroma_a_.white = glGetUniformLocation(prog_rcd_chroma_a_, "uWhite");
  u_rcd_chroma_a_.gain = glGetUniformLocation(prog_rcd_chroma_a_, "uGain");
  u_rcd_chroma_a_.cd_fix = glGetUniformLocation(prog_rcd_chroma_a_, "uCdFix");
  u_rcd_chroma_a_.satclip = glGetUniformLocation(prog_rcd_chroma_a_, "uSatClip");
  u_rcd_chroma_b_.st = glGetUniformLocation(prog_rcd_chroma_b_, "uST");
  u_rcd_chroma_b_.rot = glGetUniformLocation(prog_rcd_chroma_b_, "uRot");
  u_rcd_chroma_b_.scale = glGetUniformLocation(prog_rcd_chroma_b_, "uScale");
  u_rcd_chroma_b_.rcd_a = glGetUniformLocation(prog_rcd_chroma_b_, "uRcdA");
  u_rcd_chroma_b_.dir = glGetUniformLocation(prog_rcd_chroma_b_, "uDir");
  u_rcd_chroma_b_.g = glGetUniformLocation(prog_rcd_chroma_b_, "uG");
  u_rcd_chroma_b_.size = glGetUniformLocation(prog_rcd_chroma_b_, "uSize");
  u_rcd_chroma_b_.cfalut = glGetUniformLocation(prog_rcd_chroma_b_, "uCfaLut");
  u_rcd_chroma_b_.lsc = glGetUniformLocation(prog_rcd_chroma_b_, "uLsc");
  u_rcd_chroma_b_.lsc_size = glGetUniformLocation(prog_rcd_chroma_b_, "uLscSize");
  u_rcd_chroma_b_.satclip = glGetUniformLocation(prog_rcd_chroma_b_, "uSatClip");
  u_rcd_chroma_b_.hlmask = glGetUniformLocation(prog_rcd_chroma_b_, "uHlMask");
  u_rcd_chroma_b_.maskon = glGetUniformLocation(prog_rcd_chroma_b_, "uMaskOn");

  // 第 4a 期（S14）：D3→D4 CCM pass
  u_ccm_.tex = glGetUniformLocation(prog_ccm_, "uTex");
  u_ccm_.size = glGetUniformLocation(prog_ccm_, "uSize");
  u_ccm_.ccm = glGetUniformLocation(prog_ccm_, "uCcm");
  u_amaze_up_.tex = glGetUniformLocation(prog_amaze_up_, "uSrc");
  u_amaze_up_.size = glGetUniformLocation(prog_amaze_up_, "uSize");
  u_amaze_up_.st = glGetUniformLocation(prog_amaze_up_, "uST");
  u_amaze_up_.rot = glGetUniformLocation(prog_amaze_up_, "uRot");
  u_amaze_up_.scale = glGetUniformLocation(prog_amaze_up_, "uScale");
  u_amaze_up_.hlmask = glGetUniformLocation(prog_amaze_up_, "uHlMask");
  u_amaze_up_.maskon = glGetUniformLocation(prog_amaze_up_, "uMaskOn");
  u_amaze_up_.masksize = glGetUniformLocation(prog_amaze_up_, "uMaskSize");
  if (u_amaze_up_.st >= 0) {
    // AMaZE 档用 kVertRot（与 RCD chroma 同款）：uST 默认全 0 必须置单位阵
    const float ident[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    glUseProgram(prog_amaze_up_);
    glUniformMatrix4fv(u_amaze_up_.st, 1, GL_FALSE, ident);
  }

  u_raw_dn_.tex = glGetUniformLocation(prog_raw_dn_, "uRaw");
  u_raw_dn_.size = glGetUniformLocation(prog_raw_dn_, "uSize");
  u_raw_dn_.cfalut = glGetUniformLocation(prog_raw_dn_, "uCfaLut");
  u_raw_dn_.black = glGetUniformLocation(prog_raw_dn_, "uBlack");
  u_raw_dn_.white = glGetUniformLocation(prog_raw_dn_, "uWhite");
  u_raw_dn_.gain = glGetUniformLocation(prog_raw_dn_, "uGain");
  u_raw_dn_.noise_a = glGetUniformLocation(prog_raw_dn_, "uNoiseA");
  u_raw_dn_.noise_b = glGetUniformLocation(prog_raw_dn_, "uNoiseB");
  u_raw_dn_.mix = glGetUniformLocation(prog_raw_dn_, "uMix");

  u_raw_dn_f_.tex = glGetUniformLocation(prog_raw_dn_fused_, "uTex");
  u_raw_dn_f_.size = glGetUniformLocation(prog_raw_dn_fused_, "uSize");
  u_raw_dn_f_.cfalut = glGetUniformLocation(prog_raw_dn_fused_, "uCfaLut");
  u_raw_dn_f_.noise_a = glGetUniformLocation(prog_raw_dn_fused_, "uNoiseA");
  u_raw_dn_f_.noise_b = glGetUniformLocation(prog_raw_dn_fused_, "uNoiseB");
  u_raw_dn_f_.mix = glGetUniformLocation(prog_raw_dn_fused_, "uMix");
  u_raw_dn_f_.wclamp = glGetUniformLocation(prog_raw_dn_fused_, "uWClamp");
  u_raw_dn_f_.satmask = glGetUniformLocation(prog_raw_dn_fused_, "uSatMask");
  u_raw_dn_f_.satmask_on = glGetUniformLocation(prog_raw_dn_fused_, "uSatMaskOn");
  // D-2（v0.10.58）时域 × 空间联合 P0
  for (int i = 0; i < 4; ++i) {
    u_raw_dn_ts_.raw[i] = glGetUniformLocation(
        prog_raw_dn_ts_, (std::string("uRaw") + std::to_string(i)).c_str());
  }
  for (int i = 0; i < 3; ++i) {
    u_raw_dn_ts_.off[i] = glGetUniformLocation(
        prog_raw_dn_ts_, (std::string("uOff") + std::to_string(i + 1)).c_str());
  }
  u_raw_dn_ts_.fused = glGetUniformLocation(prog_raw_dn_ts_, "uFused");
  u_raw_dn_ts_.size = glGetUniformLocation(prog_raw_dn_ts_, "uSize");
  u_raw_dn_ts_.off_tiles = glGetUniformLocation(prog_raw_dn_ts_, "uOffTiles");
  u_raw_dn_ts_.cfalut = glGetUniformLocation(prog_raw_dn_ts_, "uCfaLut");
  u_raw_dn_ts_.black = glGetUniformLocation(prog_raw_dn_ts_, "uBlack");
  u_raw_dn_ts_.white = glGetUniformLocation(prog_raw_dn_ts_, "uWhite");
  u_raw_dn_ts_.gain = glGetUniformLocation(prog_raw_dn_ts_, "uGain");
  u_raw_dn_ts_.noise_a = glGetUniformLocation(prog_raw_dn_ts_, "uNoiseA");
  u_raw_dn_ts_.noise_b = glGetUniformLocation(prog_raw_dn_ts_, "uNoiseB");
  u_raw_dn_ts_.mix = glGetUniformLocation(prog_raw_dn_ts_, "uMix");
  u_raw_dn_ts_.wclamp = glGetUniformLocation(prog_raw_dn_ts_, "uWClamp");
  u_raw_dn_ts_.satmask = glGetUniformLocation(prog_raw_dn_ts_, "uSatMask");
  u_raw_dn_ts_.satmask_on = glGetUniformLocation(prog_raw_dn_ts_, "uSatMaskOn");
  u_raw_dn_ts_.sr_scale = glGetUniformLocation(prog_raw_dn_ts_, "uSrScale");
  u_raw_dn_ts_.sub = glGetUniformLocation(prog_raw_dn_ts_, "uSub");

  // RAW 域高光重建（darktable highlights）4 个 pass 的 uniform
  u_hl_mask_.mos = glGetUniformLocation(prog_hl_mask_, "uMos");
  u_hl_mask_.stat = glGetUniformLocation(prog_hl_mask_, "uStat");
  u_hl_mask_.statraw = glGetUniformLocation(prog_hl_mask_, "uStatRaw");
  u_hl_mask_.mono = glGetUniformLocation(prog_hl_mask_, "uMono");
  u_hl_mask_.size = glGetUniformLocation(prog_hl_mask_, "uSize");
  u_hl_mask_.cfalut = glGetUniformLocation(prog_hl_mask_, "uCfaLut");
  u_hl_mask_.clips = glGetUniformLocation(prog_hl_mask_, "uClips");
  u_hl_mask_.satclip = glGetUniformLocation(prog_hl_mask_, "uSatClip");
  // 0.12.181：饱和掩码 pass 的 uniform
  u_satm_.raw = glGetUniformLocation(prog_satm_, "uRaw");
  u_satm_.size = glGetUniformLocation(prog_satm_, "uSize");
  u_satm_.cfalut = glGetUniformLocation(prog_satm_, "uCfaLut");
  u_satm_.black = glGetUniformLocation(prog_satm_, "uBlack");
  u_satm_.white = glGetUniformLocation(prog_satm_, "uWhite");
  u_satm_.gain = glGetUniformLocation(prog_satm_, "uGain");
  u_satm_.na = glGetUniformLocation(prog_satm_, "uNoiseA");
  u_satm_.nb = glGetUniformLocation(prog_satm_, "uNoiseB");
  u_satm_.dbg = glGetUniformLocation(prog_satm_, "uDbgConst");
  u_hl_dil_.mask = glGetUniformLocation(prog_hl_dil_, "uMask");
  u_hl_dil_.msize = glGetUniformLocation(prog_hl_dil_, "uMSize");
  u_hl_chroma_.mos = glGetUniformLocation(prog_hl_chroma_, "uMos");
  u_hl_chroma_.stat = glGetUniformLocation(prog_hl_chroma_, "uStat");
  u_hl_chroma_.statraw = glGetUniformLocation(prog_hl_chroma_, "uStatRaw");
  u_hl_chroma_.mono = glGetUniformLocation(prog_hl_chroma_, "uMono");
  u_hl_chroma_.dil = glGetUniformLocation(prog_hl_chroma_, "uDil");
  u_hl_chroma_.size = glGetUniformLocation(prog_hl_chroma_, "uSize");
  u_hl_chroma_.msize = glGetUniformLocation(prog_hl_chroma_, "uMSize");
  u_hl_chroma_.cfalut = glGetUniformLocation(prog_hl_chroma_, "uCfaLut");
  u_hl_chroma_.clips = glGetUniformLocation(prog_hl_chroma_, "uClips");
  u_hl_chroma_.mode = glGetUniformLocation(prog_hl_chroma_, "uMode");
  // 0.12.127：统计口径排除 sat-fix 注入像素（refavg 只用标记 2 的邻域样本）
  u_hl_chroma_.fixmark = glGetUniformLocation(prog_hl_chroma_, "uFixMark");
  u_hl_chroma_.markon = glGetUniformLocation(prog_hl_chroma_, "uMarkOn");
  u_hl_apply_.mos = glGetUniformLocation(prog_hl_apply_, "uMos");
  u_hl_apply_.stat = glGetUniformLocation(prog_hl_apply_, "uStat");
  u_hl_apply_.statraw = glGetUniformLocation(prog_hl_apply_, "uStatRaw");
  u_hl_apply_.mono = glGetUniformLocation(prog_hl_apply_, "uMono");
  u_hl_apply_.size = glGetUniformLocation(prog_hl_apply_, "uSize");
  u_hl_apply_.cfalut = glGetUniformLocation(prog_hl_apply_, "uCfaLut");
  u_hl_apply_.clips = glGetUniformLocation(prog_hl_apply_, "uSatClip");
  u_hl_apply_.hlclip = glGetUniformLocation(prog_hl_apply_, "uHlClip");
  u_hl_apply_.chroma = glGetUniformLocation(prog_hl_apply_, "uChroma");
  u_hl_apply_.ref = glGetUniformLocation(prog_hl_apply_, "uRef");
  u_hl_apply_.grecon = glGetUniformLocation(prog_hl_apply_, "uGRecon");
  u_hl_apply_.gcap = glGetUniformLocation(prog_hl_apply_, "uGCap");
  u_hl_apply_.rbrecon = glGetUniformLocation(prog_hl_apply_, "uRbRecon");
  u_hl_apply_.refglobal = glGetUniformLocation(prog_hl_apply_, "uRefGlobal");
  u_hl_apply_.refsize = glGetUniformLocation(prog_hl_apply_, "uRefSize");
  u_hl_ref_.mos = glGetUniformLocation(prog_hl_ref_, "uMos");
  u_hl_ref_.size = glGetUniformLocation(prog_hl_ref_, "uSize");
  u_hl_ref_.cfalut = glGetUniformLocation(prog_hl_ref_, "uCfaLut");
  u_hl_ref_.clips = glGetUniformLocation(prog_hl_ref_, "uClips");
  u_hl_ref_.block = glGetUniformLocation(prog_hl_ref_, "uBlock");
  u_hl_ref_.mono = glGetUniformLocation(prog_hl_ref_, "uMono");
  u_hl_refdown_.ref1 = glGetUniformLocation(prog_hl_refdown_, "uRef1");
  u_hl_refdown_.refsize = glGetUniformLocation(prog_hl_refdown_, "uRefSize");
  u_hl_apply_.ref2 = glGetUniformLocation(prog_hl_apply_, "uRef2");
  u_hl_apply_.ref2size = glGetUniformLocation(prog_hl_apply_, "uRef2Size");
  // v0.12.8：采集侧三态信息标记（重建判据）
  u_hl_apply_.fixmark = glGetUniformLocation(prog_hl_apply_, "uFixMark");
  u_hl_apply_.markon = glGetUniformLocation(prog_hl_apply_, "uMarkOn");

  u_nn_pack_.fused = glGetUniformLocation(prog_nn_pack_, "uFused");
  u_nn_pack_.raw = glGetUniformLocation(prog_nn_pack_, "uRaw");
  u_nn_pack_.size = glGetUniformLocation(prog_nn_pack_, "uSize");
  u_nn_pack_.cfalut = glGetUniformLocation(prog_nn_pack_, "uCfaLut");
  u_nn_pack_.black = glGetUniformLocation(prog_nn_pack_, "uBlack");
  u_nn_pack_.white = glGetUniformLocation(prog_nn_pack_, "uWhite");
  u_nn_pack_.gain = glGetUniformLocation(prog_nn_pack_, "uGain");
  u_nn_pack_.src = glGetUniformLocation(prog_nn_pack_, "uSrc");
  u_nn_pack_.scale = glGetUniformLocation(prog_nn_pack_, "uScale");
  u_nn_pack_.phx = glGetUniformLocation(prog_nn_pack_, "uPhX");
  u_nn_pack_.phy = glGetUniformLocation(prog_nn_pack_, "uPhY");
  u_nn_unpack_.packed = glGetUniformLocation(prog_nn_unpack_, "uPacked");
  u_nn_unpack_.size = glGetUniformLocation(prog_nn_unpack_, "uSize");
  u_nn_unpack_.cfalut = glGetUniformLocation(prog_nn_unpack_, "uCfaLut");
  u_nn_unpack_.gain = glGetUniformLocation(prog_nn_unpack_, "uGain");
  u_nn_unpack_.iscale = glGetUniformLocation(prog_nn_unpack_, "uInvScale");
  u_nn_unpack_.chmap = glGetUniformLocation(prog_nn_unpack_, "uChMap");

  // 收口 pass 的采样器单元固定 0（prog 可能为 0 —— 实验路径失败不阻塞）
  if (prog_mfnr_fft_sum_) {
    glUseProgram(prog_mfnr_fft_sum_);
    const char* gnames[4] = {"uG0", "uG1", "uG2", "uG3"};
    for (int i = 0; i < 4; ++i) {
      u_mfnr_fft_sum_.g[i] =
          glGetUniformLocation(prog_mfnr_fft_sum_, gnames[i]);
      if (u_mfnr_fft_sum_.g[i] >= 0) glUniform1i(u_mfnr_fft_sum_.g[i], i);
    }
    u_mfnr_fft_sum_.size = glGetUniformLocation(prog_mfnr_fft_sum_, "uSize");
  }

  // v0.10.15：HR 各 shader 的 uniform
  u_hr_mask_ = glGetUniformLocation(prog_hr_mask_, "uTex");
  u_hr_mask_norm_ = glGetUniformLocation(prog_hr_mask_, "uNormalize");
  u_hr_mask_feather_ = glGetUniformLocation(prog_hr_mask_, "uFeather");
  u_hr_mask_fixmark_ = glGetUniformLocation(prog_hr_mask_, "uFixMark");
  u_hr_mask_markon_ = glGetUniformLocation(prog_hr_mask_, "uMarkOn");
  u_hr_inp_tex_ = glGetUniformLocation(prog_hr_inpaint_, "uTex");
  u_hr_inp_mask_ = glGetUniformLocation(prog_hr_inpaint_, "uMask");
  u_hr_inp_noise_ = glGetUniformLocation(prog_hr_inpaint_, "uNoiseLevel");
  u_hr_inp_thr_ = glGetUniformLocation(prog_hr_inpaint_, "uThreshold");
  u_hr_blur_tex_ = glGetUniformLocation(prog_hr_blur_, "uTex");
  u_hr_blur_size_ = glGetUniformLocation(prog_hr_blur_, "uSize");
  u_hr_blur_mult_ = glGetUniformLocation(prog_hr_blur_, "uMult");
  u_hr_blur_dir_ = glGetUniformLocation(prog_hr_blur_, "uDir");
  u_hr_hf_detail_ = glGetUniformLocation(prog_hr_hf_, "uDetail");
  u_hr_hf_lf_ = glGetUniformLocation(prog_hr_hf_, "uLF");
  u_hr_rin_tex_ = glGetUniformLocation(prog_hr_rin_, "uTex");
  u_hr_rin_mask_ = glGetUniformLocation(prog_hr_rin_, "uMask");
  u_hr_step_hfrgb_ = glGetUniformLocation(prog_hr_step_, "uHFRGB");
  u_hr_step_lf_ = glGetUniformLocation(prog_hr_step_, "uLF");
  u_hr_step_hf_ = glGetUniformLocation(prog_hr_step_, "uHF");
  u_hr_step_mask_ = glGetUniformLocation(prog_hr_step_, "uMask");
  u_hr_step_recon_ = glGetUniformLocation(prog_hr_step_, "uRecon");
  u_hr_step_gamma_ = glGetUniformLocation(prog_hr_step_, "uGamma");
  u_hr_step_gammac_ = glGetUniformLocation(prog_hr_step_, "uGammaComp");
  u_hr_step_beta_ = glGetUniformLocation(prog_hr_step_, "uBeta");
  u_hr_step_betac_ = glGetUniformLocation(prog_hr_step_, "uBetaComp");
  u_hr_step_delta_ = glGetUniformLocation(prog_hr_step_, "uDelta");
  u_hr_step_variant_ = glGetUniformLocation(prog_hr_step_, "uVariant");
  u_hr_step_si_ = glGetUniformLocation(prog_hr_step_, "uScaleIndex");
  u_hr_step_sc_ = glGetUniformLocation(prog_hr_step_, "uScaleCount");
  u_hr_norms_tex_ = glGetUniformLocation(prog_hr_norms_, "uTex");
  u_hr_ratios_tex_ = glGetUniformLocation(prog_hr_ratios_, "uTex");
  u_hr_ratios_norms_ = glGetUniformLocation(prog_hr_ratios_, "uNorms");
  u_hr_restore_ratios_ = glGetUniformLocation(prog_hr_restore_, "uRatios");
  u_hr_restore_norms_ = glGetUniformLocation(prog_hr_restore_, "uNorms");
  u_hr_stat_mask_ = glGetUniformLocation(prog_hr_stat_, "uMask");
  u_hr_stat_size_ = glGetUniformLocation(prog_hr_stat_, "uSize");
  u_hr_stat_out_ = glGetUniformLocation(prog_hr_stat_, "uOut");

  u_mfnr_pyr_.tex = glGetUniformLocation(prog_mfnr_pyr_, "uRaw");
  u_mfnr_pyr_.size = glGetUniformLocation(prog_mfnr_pyr_, "uSize");
  u_mfnr_pyr_.cfalut = glGetUniformLocation(prog_mfnr_pyr_, "uCfaLut");
  u_mfnr_pyr_.black = glGetUniformLocation(prog_mfnr_pyr_, "uBlack");
  u_mfnr_pyr_.white = glGetUniformLocation(prog_mfnr_pyr_, "uWhite");
  u_mfnr_pyr_.gain = glGetUniformLocation(prog_mfnr_pyr_, "uGain");
  u_mfnr_pyr_.scale = glGetUniformLocation(prog_mfnr_pyr_, "uScale");

  u_mfnr_down_.tex = glGetUniformLocation(prog_mfnr_down_, "uTex");
  u_mfnr_down_.size = glGetUniformLocation(prog_mfnr_down_, "uSize");

  for (int i = 0; i < 4; ++i) {
    u_mfnr_fuse_.raw[i] =
        glGetUniformLocation(prog_mfnr_fuse_, ("uRaw" + std::to_string(i)).c_str());
  }
  for (int i = 0; i < 3; ++i) {
    u_mfnr_fuse_.off[i] =
        glGetUniformLocation(prog_mfnr_fuse_, ("uOff" + std::to_string(i + 1)).c_str());
  }
  u_mfnr_fuse_.w = glGetUniformLocation(prog_mfnr_fuse_, "uW");
  u_mfnr_fuse_.size = glGetUniformLocation(prog_mfnr_fuse_, "uSize");
  u_mfnr_fuse_.tiles = glGetUniformLocation(prog_mfnr_fuse_, "uTiles");
  u_mfnr_fuse_.tile_m = glGetUniformLocation(prog_mfnr_fuse_, "uTileM");
  u_mfnr_fuse_.cfalut = glGetUniformLocation(prog_mfnr_fuse_, "uCfaLut");
  u_mfnr_fuse_.black = glGetUniformLocation(prog_mfnr_fuse_, "uBlack");
  u_mfnr_fuse_.white = glGetUniformLocation(prog_mfnr_fuse_, "uWhite");
  u_mfnr_fuse_.gain = glGetUniformLocation(prog_mfnr_fuse_, "uGain");
  u_mfnr_fuse_.frame_scale =
      glGetUniformLocation(prog_mfnr_fuse_, "uFrameScale");
  u_mfnr_fuse_.sat_k = glGetUniformLocation(prog_mfnr_fuse_, "uSatK");

  for (int i = 0; i < 4; ++i) {
    u_mfnr_mis_.raw[i] =
        glGetUniformLocation(prog_mfnr_mis_, ("uRaw" + std::to_string(i)).c_str());
  }
  for (int i = 0; i < 3; ++i) {
    u_mfnr_mis_.off[i] =
        glGetUniformLocation(prog_mfnr_mis_, ("uOff" + std::to_string(i + 1)).c_str());
  }
  u_mfnr_mis_.size = glGetUniformLocation(prog_mfnr_mis_, "uSize");
  u_mfnr_mis_.tiles = glGetUniformLocation(prog_mfnr_mis_, "uTiles");
  u_mfnr_mis_.off_tiles = glGetUniformLocation(prog_mfnr_mis_, "uOffTiles");
  u_mfnr_mis_.tile_m = glGetUniformLocation(prog_mfnr_mis_, "uTileM");
  u_mfnr_mis_.noise_a = glGetUniformLocation(prog_mfnr_mis_, "uNoiseA");
  u_mfnr_mis_.noise_b = glGetUniformLocation(prog_mfnr_mis_, "uNoiseB");
  u_mfnr_mis_.s = glGetUniformLocation(prog_mfnr_mis_, "uS");
  u_mfnr_mis_.cfalut = glGetUniformLocation(prog_mfnr_mis_, "uCfaLut");
  u_mfnr_mis_.black = glGetUniformLocation(prog_mfnr_mis_, "uBlack");
  u_mfnr_mis_.white = glGetUniformLocation(prog_mfnr_mis_, "uWhite");
  u_mfnr_mis_.gain = glGetUniformLocation(prog_mfnr_mis_, "uGain");
  u_mfnr_mis_.frame_scale = glGetUniformLocation(prog_mfnr_mis_, "uFrameScale");
  u_mfnr_mis_.gain_ratio = glGetUniformLocation(prog_mfnr_mis_, "uGainRatio");
  u_mfnr_satfix_.ref = glGetUniformLocation(prog_mfnr_satfix_, "uRef");
  u_mfnr_satfix_.shrt = glGetUniformLocation(prog_mfnr_satfix_, "uShort");
  u_mfnr_satfix_.size = glGetUniformLocation(prog_mfnr_satfix_, "uSize");
  u_mfnr_satfix_.black = glGetUniformLocation(prog_mfnr_satfix_, "uBlack");
  u_mfnr_satfix_.white = glGetUniformLocation(prog_mfnr_satfix_, "uWhite");
  u_mfnr_satfix_.scale = glGetUniformLocation(prog_mfnr_satfix_, "uScale");
  u_mfnr_satfix_.satmap = glGetUniformLocation(prog_mfnr_satfix_, "uSatMap");
  u_mfnr_satfix_.satsize = glGetUniformLocation(prog_mfnr_satfix_, "uSatSize");
  // 0.12.102：短帧对齐采样（位移场复用 DFT 的 tile 场）+ fallback 门限
  u_mfnr_satfix_.off = glGetUniformLocation(prog_mfnr_satfix_, "uOff");
  u_mfnr_satfix_.offtiles = glGetUniformLocation(prog_mfnr_satfix_, "uOffTiles");
  u_mfnr_satfix_.minerr = glGetUniformLocation(prog_mfnr_satfix_, "uMinErr");
  // 0.12.133（方案 B 块 3）：短帧真值图 + 全饱和区 inpaint
  u_mfnr_sv_.shrt = glGetUniformLocation(prog_mfnr_sv_, "uShort");
  u_mfnr_sv_.off = glGetUniformLocation(prog_mfnr_sv_, "uOff");
  u_mfnr_sv_.size = glGetUniformLocation(prog_mfnr_sv_, "uSize");
  u_mfnr_sv_.tiles = glGetUniformLocation(prog_mfnr_sv_, "uTiles");
  u_mfnr_sv_.tile_m = glGetUniformLocation(prog_mfnr_sv_, "uTileM");
  u_mfnr_sv_.cfalut = glGetUniformLocation(prog_mfnr_sv_, "uCfaLut");
  u_mfnr_sv_.black = glGetUniformLocation(prog_mfnr_sv_, "uBlack");
  u_mfnr_sv_.white = glGetUniformLocation(prog_mfnr_sv_, "uWhite");
  u_mfnr_sv_.gain = glGetUniformLocation(prog_mfnr_sv_, "uGain");
  u_mfnr_sv_.scale = glGetUniformLocation(prog_mfnr_sv_, "uScale");
  u_hl_inp_.fused = glGetUniformLocation(prog_hl_inp_, "uFused");
  u_hl_inp_.shrt = glGetUniformLocation(prog_hl_inp_, "uShort");
  u_hl_inp_.size = glGetUniformLocation(prog_hl_inp_, "uSize");
  u_hl_inp_.gain = glGetUniformLocation(prog_hl_inp_, "uGain");
  u_hl_inp_.shortk = glGetUniformLocation(prog_hl_inp_, "uShortK");
  u_hl_inp_.cfalut = glGetUniformLocation(prog_hl_inp_, "uCfaLut");
  u_teq_down_.tex = glGetUniformLocation(prog_teq_down_, "uTex");
  u_teq_down_.src_texel = glGetUniformLocation(prog_teq_down_, "uSrcTexel");
  u_teq_box_.tex = glGetUniformLocation(prog_teq_box_, "uTex");
  u_teq_box_.texel = glGetUniformLocation(prog_teq_box_, "uTexel");
  u_teq_box_.dir = glGetUniformLocation(prog_teq_box_, "uDir");
  u_teq_box_.radius = glGetUniformLocation(prog_teq_box_, "uRadius");
  u_teq_coef_.mean = glGetUniformLocation(prog_teq_coef_, "uMean");
  u_teq_coef_.eps = glGetUniformLocation(prog_teq_coef_, "uEps");
  u_teq_gain_.i = glGetUniformLocation(prog_teq_gain_, "uI");
  u_teq_gain_.ab = glGetUniformLocation(prog_teq_gain_, "uAB");
  u_teq_apply_.tex = glGetUniformLocation(prog_teq_apply_, "uTex");
  u_teq_apply_.gain = glGetUniformLocation(prog_teq_apply_, "uAB");

  {  // 0.12.112：降噪出口混合
    prog_dn_blend_ = prog_dn_blend_ ? prog_dn_blend_ : 0;
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFsDnBlend);
    if (fs) { if (prog_dn_blend_) glDeleteProgram(prog_dn_blend_); prog_dn_blend_ = linkProgram(vs_plain_, fs); glDeleteShader(fs); }
    if (prog_dn_blend_) {
      u_dn_blend_.dn = glGetUniformLocation(prog_dn_blend_, "uDn");
      u_dn_blend_.orig = glGetUniformLocation(prog_dn_blend_, "uOrig");
      u_dn_blend_.noise_a = glGetUniformLocation(prog_dn_blend_, "uNoiseA");
      u_dn_blend_.noise_b = glGetUniformLocation(prog_dn_blend_, "uNoiseB");
      u_dn_blend_.snr_lo = glGetUniformLocation(prog_dn_blend_, "uSnrLo");
      u_dn_blend_.snr_hi = glGetUniformLocation(prog_dn_blend_, "uSnrHi");
    }
  }
  {  // 0.12.161：collar support（0.12.122 的 kFsCollarFlow 是 no-op，见 shader 注释）
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFsCollarSupport);
    if (fs) {
      if (prog_collar_supp_) glDeleteProgram(prog_collar_supp_);
      prog_collar_supp_ = linkProgram(vs_plain_, fs);
      glDeleteShader(fs);
    }
    if (prog_collar_supp_) {
      u_collar_supp_.off = glGetUniformLocation(prog_collar_supp_, "uOff");
      u_collar_supp_.ref = glGetUniformLocation(prog_collar_supp_, "uRef");
      u_collar_supp_.size = glGetUniformLocation(prog_collar_supp_, "uSize");
      u_collar_supp_.tiles = glGetUniformLocation(prog_collar_supp_, "uTiles");
      u_collar_supp_.tilem = glGetUniformLocation(prog_collar_supp_, "uTileM");
      u_collar_supp_.white = glGetUniformLocation(prog_collar_supp_, "uWhite");
    }
  }
  u_chroma_dn_.tex = glGetUniformLocation(prog_chroma_dn_, "uTex");
  u_chroma_dn_.size = glGetUniformLocation(prog_chroma_dn_, "uSize");
  u_chroma_dn_.noise_a = glGetUniformLocation(prog_chroma_dn_, "uNoiseA");
  u_chroma_dn_.noise_b = glGetUniformLocation(prog_chroma_dn_, "uNoiseB");
  u_chroma_dn_.mix = glGetUniformLocation(prog_chroma_dn_, "uMix");
  u_chroma_dn_.lsc = glGetUniformLocation(prog_chroma_dn_, "uLsc");
  u_chroma_dn_.lsc_size = glGetUniformLocation(prog_chroma_dn_, "uLscSize");
  // 档 A（v0.10.165）
  u_chroma_dn_.moire = glGetUniformLocation(prog_chroma_dn_, "uMoire");
  u_chroma_dn_.moire_k = glGetUniformLocation(prog_chroma_dn_, "uMoireK");
  // v0.10.173：强边缘色度梯度门
  u_chroma_dn_.satmask = glGetUniformLocation(prog_chroma_dn_, "uSatMask");
  u_chroma_dn_.satmask_on = glGetUniformLocation(prog_chroma_dn_, "uSatMaskOn");
  u_chroma_dn_.cg_k = glGetUniformLocation(prog_chroma_dn_, "uCgK");
  u_chroma_dn_.cg_scope = glGetUniformLocation(prog_chroma_dn_, "uCgScope");
  u_moire_stat_.src = glGetUniformLocation(prog_moire_stat_, "uSrc");
  u_moire_stat_.size = glGetUniformLocation(prog_moire_stat_, "uSize");

  // 诊断统计（v0.10.166）：**不进 init 的 program 失败判据** —— 统计链路任何一环
  // 缺失都只是"少一条 diag.stats 日志"，绝不允许它把整个引擎拖成初始化失败。
  u_diag_down_.src = glGetUniformLocation(prog_diag_down_, "uSrc");
  u_diag_down_.size = glGetUniformLocation(prog_diag_down_, "uSize");
  u_diag_pack_.t0 = glGetUniformLocation(prog_diag_pack_, "uT0");
  u_diag_pack_.t1 = glGetUniformLocation(prog_diag_pack_, "uT1");
  u_diag_pack_.t2 = glGetUniformLocation(prog_diag_pack_, "uT2");
  u_diag_pack_.t3 = glGetUniformLocation(prog_diag_pack_, "uT3");
  // D 专项（v0.10.53）色度多尺度降噪
  u_chroma_down_.tex = glGetUniformLocation(prog_chroma_down_, "uTex");
  u_chroma_down_.size = glGetUniformLocation(prog_chroma_down_, "uSize");
  u_chroma_down_.noise_a = glGetUniformLocation(prog_chroma_down_, "uNoiseA");
  u_chroma_down_.noise_b = glGetUniformLocation(prog_chroma_down_, "uNoiseB");
  u_chroma_down_.lsc = glGetUniformLocation(prog_chroma_down_, "uLsc");
  u_chroma_down_.lsc_size = glGetUniformLocation(prog_chroma_down_, "uLscSize");
  u_chroma_at_.prev = glGetUniformLocation(prog_chroma_at_, "uPrev");
  u_chroma_at_.sigma = glGetUniformLocation(prog_chroma_at_, "uSigma");
  u_chroma_at_.size = glGetUniformLocation(prog_chroma_at_, "uSize");
  u_chroma_at_.step = glGetUniformLocation(prog_chroma_at_, "uStep");
  u_chroma_at_.k = glGetUniformLocation(prog_chroma_at_, "uK");
  u_chroma_at_.revert = glGetUniformLocation(prog_chroma_at_, "uRevert");
  u_chroma_at_.cg_k = glGetUniformLocation(prog_chroma_at_, "uCgK");
  u_chroma_apply_.tex = glGetUniformLocation(prog_chroma_apply_, "uTex");
  u_chroma_apply_.coarse = glGetUniformLocation(prog_chroma_apply_, "uCoarse");
  for (int i = 0; i < kChromaLevels; ++i) {
    u_chroma_apply_.d[i] = glGetUniformLocation(
        prog_chroma_apply_, (std::string("uD") + std::to_string(i)).c_str());
  }
  u_chroma_apply_.size = glGetUniformLocation(prog_chroma_apply_, "uSize");
  u_chroma_apply_.half = glGetUniformLocation(prog_chroma_apply_, "uHalf");
  u_chroma_apply_.noise_a = glGetUniformLocation(prog_chroma_apply_, "uNoiseA");
  u_chroma_apply_.noise_b = glGetUniformLocation(prog_chroma_apply_, "uNoiseB");
  u_chroma_apply_.lsc = glGetUniformLocation(prog_chroma_apply_, "uLsc");
  u_chroma_apply_.lsc_size = glGetUniformLocation(prog_chroma_apply_, "uLscSize");
  u_chroma_apply_.kr = glGetUniformLocation(prog_chroma_apply_, "uKr");
  // 紫边抑制（v0.10.134）
  u_df_down_.tex = glGetUniformLocation(prog_df_down_, "uTex");
  u_df_down_.size = glGetUniformLocation(prog_df_down_, "uSize");
  u_df_det_.src = glGetUniformLocation(prog_df_det_, "uSrc");
  u_df_det_.half = glGetUniformLocation(prog_df_det_, "uHalf");
  u_df_avg_.src = glGetUniformLocation(prog_df_avg_, "uSrc");
  u_df_avg_.size = glGetUniformLocation(prog_df_avg_, "uHalf");
  u_df_apply_.tex = glGetUniformLocation(prog_df_apply_, "uTex");
  u_df_apply_.det = glGetUniformLocation(prog_df_apply_, "uDet");
  u_df_apply_.size = glGetUniformLocation(prog_df_apply_, "uSize");
  u_df_apply_.half = glGetUniformLocation(prog_df_apply_, "uHalf");
  u_df_apply_.chroma_avg = glGetUniformLocation(prog_df_apply_, "uChromaAvg");
  u_df_apply_.thresh_k = glGetUniformLocation(prog_df_apply_, "uThreshK");
  u_df_apply_.thresh_min = glGetUniformLocation(prog_df_apply_, "uThreshMin");
  u_df_apply_.thresh_max = glGetUniformLocation(prog_df_apply_, "uThreshMax");
  u_df_apply_.std_max = glGetUniformLocation(prog_df_apply_, "uStdMax");
  u_df_apply_.std_k = glGetUniformLocation(prog_df_apply_, "uStdK");
  u_df_apply_.grad_min = glGetUniformLocation(prog_df_apply_, "uGradMin");
  // 色差域中值滤波（v0.10.196）
  u_cs_.tex = glGetUniformLocation(prog_cs_, "uTex");
  u_cs_.size = glGetUniformLocation(prog_cs_, "uSize");
  u_cs_.grad_lo = glGetUniformLocation(prog_cs_, "uGradLo");
  u_cs_.grad_hi = glGetUniformLocation(prog_cs_, "uGradHi");
  u_cs_.side_k = glGetUniformLocation(prog_cs_, "uSideK");
  u_cs_.chroma_min = glGetUniformLocation(prog_cs_, "uChromaMin");
  u_cs_.peak_k = glGetUniformLocation(prog_cs_, "uPeakK");
  // 横向色差校正（v0.10.170）
  u_ca_ds_.tex = glGetUniformLocation(prog_ca_ds_, "uTex");
  u_ca_ds_.size = glGetUniformLocation(prog_ca_ds_, "uSize");
  u_ca_corr_.tex = glGetUniformLocation(prog_ca_corr_, "uTex");
  u_ca_corr_.size = glGetUniformLocation(prog_ca_corr_, "uSize");
  u_ca_corr_.center = glGetUniformLocation(prog_ca_corr_, "uCenter");
  u_ca_corr_.kr = glGetUniformLocation(prog_ca_corr_, "uKR");
  u_ca_corr_.kb = glGetUniformLocation(prog_ca_corr_, "uKB");
  u_ca_corr_.maxshift = glGetUniformLocation(prog_ca_corr_, "uMaxShift");
  // PC ESD3D2 完整移植（v0.10.175）
  u_pc3d_.tex = glGetUniformLocation(prog_pc3d_, "uTex");
  u_pc3d_.size = glGetUniformLocation(prog_pc3d_, "uSize");
  u_pc3d_.k = glGetUniformLocation(prog_pc3d_, "uK");
  u_pc3d_.kernel = glGetUniformLocation(prog_pc3d_, "uKernel");
  u_pc3d_.sig_a = glGetUniformLocation(prog_pc3d_, "uSigA");
  u_pc3d_.sig_b = glGetUniformLocation(prog_pc3d_, "uSigB");
  u_pc3d_.moire = glGetUniformLocation(prog_pc3d_, "uMoire");
  u_pc3d_.luma = glGetUniformLocation(prog_pc3d_, "uLuma");
  // 0.12.141：PC3D 不消费 mask（见 kFsPc3d 顶部注释）。
  u_pc3d_.lsc = glGetUniformLocation(prog_pc3d_, "uLsc");
  u_pc3d_.lsc_size = glGetUniformLocation(prog_pc3d_, "uLscSize");
  u_pc3d_.satmask = glGetUniformLocation(prog_pc3d_, "uSatMask");
  u_pc3d_.satmaskon = glGetUniformLocation(prog_pc3d_, "uSatMaskOn");
  u_pc3d_down_.tex = glGetUniformLocation(prog_pc3d_down_, "uTex");
  u_pc3d_down_.size = glGetUniformLocation(prog_pc3d_down_, "uSize");
  u_pc3d_down_.resize = glGetUniformLocation(prog_pc3d_down_, "uResize");
  u_pc3d_down_.stride = glGetUniformLocation(prog_pc3d_down_, "uStride");
  u_pc3d_down_.sigma = glGetUniformLocation(prog_pc3d_down_, "uSigma");
  u_pc3d_up_.low = glGetUniformLocation(prog_pc3d_up_, "uLow");
  u_pc3d_up_.guide_hi = glGetUniformLocation(prog_pc3d_up_, "uGuideHi");
  u_pc3d_up_.low_size = glGetUniformLocation(prog_pc3d_up_, "uLowSize");
  u_pc3d_up_.hi_size = glGetUniformLocation(prog_pc3d_up_, "uHiSize");
  u_pc3d_up_.r = glGetUniformLocation(prog_pc3d_up_, "uR");
  {
    // P4 用 kVertRot（uST 默认全 0 必须置单位阵）
    const float ident[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    glUseProgram(prog_chroma_dn_);
    if (u_chroma_dn_.size >= 0) {
      GLint st = glGetUniformLocation(prog_chroma_dn_, "uST");
      if (st >= 0) glUniformMatrix4fv(st, 1, GL_FALSE, ident);
    }
  }
  if (u_rcd_chroma_.st >= 0) {  // 同上：kVertRot 的 uST 默认全 0，必须置单位阵
    const float ident[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    glUseProgram(prog_rcd_chroma_);
    glUniformMatrix4fv(u_rcd_chroma_.st, 1, GL_FALSE, ident);
  }
  {
    // P3 拆双 pass 的 3b 用 kVertRot，uST 默认全 0 必须置单位阵（v0.10.180）
    const float ident[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    if (u_rcd_chroma_b_.st >= 0) {
      glUseProgram(prog_rcd_chroma_b_);
      glUniformMatrix4fv(u_rcd_chroma_b_.st, 1, GL_FALSE, ident);
    }
  }
  {
    // 第 4a 期（S14）：kFsCcm 用 kVertRot，uST 默认全 0 必须置单位阵
    const float ident[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    const GLint st = glGetUniformLocation(prog_ccm_, "uST");
    if (st >= 0) {
      glUseProgram(prog_ccm_);
      glUniformMatrix4fv(st, 1, GL_FALSE, ident);
    }
  }

  queryFilmUniforms();

  u_out_.tex = glGetUniformLocation(prog_out_, "uTex");
  u_out_.px = glGetUniformLocation(prog_out_, "uPx");
  // E4-3 颗粒（granularity）：与 u_style_ 同名同义（见 FrameRenderer.h 的 u_out_ 注释）
  u_out_.grain_amount = glGetUniformLocation(prog_out_, "uGrainAmount");
  u_out_.grain_seed = glGetUniformLocation(prog_out_, "uGrainSeed");
  u_out_.grain_zoom = glGetUniformLocation(prog_out_, "uGrainZoom");
  u_out_.grain_norm = glGetUniformLocation(prog_out_, "uGrainNorm");
  u_out_.grain_lut = glGetUniformLocation(prog_out_, "uGrainLut");
  u_out_.flip_y = glGetUniformLocation(prog_out_, "uFlipY");
  u_out_.zebra = glGetUniformLocation(prog_out_, "uZebra");
  // U-1（期 B 从 u_film_ 挪来）：split + mono 现在由显示域段（kFsOut）消费
  u_out_.split = glGetUniformLocation(prog_out_, "uSplit");
  u_out_.mono = glGetUniformLocation(prog_out_, "uMono");
  u_out_.contrast_out = glGetUniformLocation(prog_out_, "uContrastOut");
  // 卡 H2：dither 除数（交付档上限）。shader 里是 uOutMax，名字的真源 =
  // OutputBitDepth.h 的 kOutputMaxUniform（改名字要同时改守卫脚本的解析口径）。
  u_out_.out_max = glGetUniformLocation(prog_out_, kOutputMaxUniform);
  u_out_.sharpen = glGetUniformLocation(prog_out_, "uSharpen");
  u_out_.rolloff = glGetUniformLocation(prog_out_, "uRollOff");   // 0.12.166
  u_out_.lut = glGetUniformLocation(prog_out_, "uLut");
  u_out_.lut_on = glGetUniformLocation(prog_out_, "uLutOn");
  u_out_.lut_strength = glGetUniformLocation(prog_out_, "uLutStrength");
  u_out_.lut_dmin = glGetUniformLocation(prog_out_, "uLutDomainMin");
  u_out_.lut_dinv = glGetUniformLocation(prog_out_, "uLutDomainInv");
  u_out_.lut_size = glGetUniformLocation(prog_out_, "uLutSize");
  // E4-2 暗角（vignette）
  u_out_.vig_scale = glGetUniformLocation(prog_out_, "uVigScaleXY");
  u_out_.vig_dscale = glGetUniformLocation(prog_out_, "uVigDscale");
  u_out_.vig_fscale = glGetUniformLocation(prog_out_, "uVigFscale");
  u_out_.vig_brightness = glGetUniformLocation(prog_out_, "uVigBrightness");
  // U-4 清晰度 + 影调分区 + 褪色（display 域 = OETF 之后，见 kFsOut 的同名声明）
  u_out_.clarity = glGetUniformLocation(prog_out_, "uClarity");
  u_out_.clar_step = glGetUniformLocation(prog_out_, "uClarStep");
  u_out_.highlights = glGetUniformLocation(prog_out_, "uHighlights");
  u_out_.shadows = glGetUniformLocation(prog_out_, "uShadows");
  u_out_.whites = glGetUniformLocation(prog_out_, "uWhites");
  u_out_.blacks = glGetUniformLocation(prog_out_, "uBlacks");
  u_out_.fade = glGetUniformLocation(prog_out_, "uFade");
  u_out_.tone_on = glGetUniformLocation(prog_out_, "uToneOn");
  // ── SG-1 柔光（soft glow）：与 u_style_ 同名同义（同一段 shader 数学）──────
  // uGlowTexel = 1/glowW, 1/glowH —— glowFetch 的手写双线性用它做"UV → 像素"换算。
  // 纹理尺寸 = halo_tmp_*（柔光图就写在 tex_halo_tmp_ 上，1/2 分辨率）。
  u_out_.glow = glGetUniformLocation(prog_out_, "uGlow");
  u_out_.glow_on = glGetUniformLocation(prog_out_, "uGlowOn");
  u_out_.glow_strength = glGetUniformLocation(prog_out_, "uGlowStrength");
  u_out_.glow_texel = glGetUniformLocation(prog_out_, "uGlowTexel");

  // E1-3 编辑器通路（kFsStyle）
  u_style_.tex = glGetUniformLocation(prog_style_, "uTex");
  // E4-3 颗粒（granularity）：见 u_out_。旧 uSeed 只服务颗粒（dither 走 dhash）⇒ 由
  // uGrainSeed 取代，无连带影响。
  u_style_.grain_amount = glGetUniformLocation(prog_style_, "uGrainAmount");
  u_style_.grain_seed = glGetUniformLocation(prog_style_, "uGrainSeed");
  u_style_.grain_zoom = glGetUniformLocation(prog_style_, "uGrainZoom");
  u_style_.grain_norm = glGetUniformLocation(prog_style_, "uGrainNorm");
  u_style_.grain_lut = glGetUniformLocation(prog_style_, "uGrainLut");
  // E4-5 halation（光晕）：三个 program 的 uniform。**全部逐项独立获取**（无数组：
  // 5 级 sampler 与 5 个权重向量都是独立 uniform —— 移动 GPU 三禁的规避，见
  // kFsHaloMix 顶部注释）。
  u_halo_thr_.src = glGetUniformLocation(prog_halo_thr_, "uSrc");
  u_halo_thr_.texel = glGetUniformLocation(prog_halo_thr_, "uTexel");
  u_halo_thr_.decode_in = glGetUniformLocation(prog_halo_thr_, "uDecodeIn");
  u_halo_thr_.thr_on = glGetUniformLocation(prog_halo_thr_, "uThrOn");
  u_halo_thr_.thr_lin = glGetUniformLocation(prog_halo_thr_, "uThrLin");
  u_halo_thr_.knee = glGetUniformLocation(prog_halo_thr_, "uKnee");
  // SG-1 柔光提取模式门控（与 halation 的彩色提取互斥）
  u_halo_thr_.glow_on = glGetUniformLocation(prog_halo_thr_, "uGlowOn");
  u_halo_blur_.src = glGetUniformLocation(prog_halo_blur_, "uSrc");
  u_halo_blur_.dir = glGetUniformLocation(prog_halo_blur_, "uDir");
  u_halo_blur_.scale = glGetUniformLocation(prog_halo_blur_, "uScale");
  u_halo_mix_.src = glGetUniformLocation(prog_halo_mix_, "uSrc");
  u_halo_mix_.halo0 = glGetUniformLocation(prog_halo_mix_, "uHalo0");
  u_halo_mix_.halo1 = glGetUniformLocation(prog_halo_mix_, "uHalo1");
  u_halo_mix_.halo2 = glGetUniformLocation(prog_halo_mix_, "uHalo2");
  u_halo_mix_.halo3 = glGetUniformLocation(prog_halo_mix_, "uHalo3");
  u_halo_mix_.halo4 = glGetUniformLocation(prog_halo_mix_, "uHalo4");
  u_halo_mix_.w0 = glGetUniformLocation(prog_halo_mix_, "uW0");
  u_halo_mix_.w1 = glGetUniformLocation(prog_halo_mix_, "uW1");
  u_halo_mix_.w2 = glGetUniformLocation(prog_halo_mix_, "uW2");
  u_halo_mix_.w3 = glGetUniformLocation(prog_halo_mix_, "uW3");
  u_halo_mix_.w4 = glGetUniformLocation(prog_halo_mix_, "uW4");
  u_halo_mix_.tint = glGetUniformLocation(prog_halo_mix_, "uTint");
  u_halo_mix_.amount = glGetUniformLocation(prog_halo_mix_, "uAmount");
  u_halo_mix_.decode_in = glGetUniformLocation(prog_halo_mix_, "uDecodeIn");
  // SG-1 柔光合成（5 级 + 5 个标量权重；三禁规避：独立 uniform，无数组）
  u_glow_sum_.g0 = glGetUniformLocation(prog_glow_sum_, "uG0");
  u_glow_sum_.g1 = glGetUniformLocation(prog_glow_sum_, "uG1");
  u_glow_sum_.g2 = glGetUniformLocation(prog_glow_sum_, "uG2");
  u_glow_sum_.g3 = glGetUniformLocation(prog_glow_sum_, "uG3");
  u_glow_sum_.g4 = glGetUniformLocation(prog_glow_sum_, "uG4");
  u_glow_sum_.s0 = glGetUniformLocation(prog_glow_sum_, "uS0");
  u_glow_sum_.s1 = glGetUniformLocation(prog_glow_sum_, "uS1");
  u_glow_sum_.s2 = glGetUniformLocation(prog_glow_sum_, "uS2");
  u_glow_sum_.s3 = glGetUniformLocation(prog_glow_sum_, "uS3");
  u_glow_sum_.s4 = glGetUniformLocation(prog_glow_sum_, "uS4");
  // E2-12 裁剪 + 90° 步进旋转（ADR-16）
  u_style_.crop_origin = glGetUniformLocation(prog_style_, "uCropOrigin");
  u_style_.crop_size = glGetUniformLocation(prog_style_, "uCropSize");
  u_style_.crop_rot = glGetUniformLocation(prog_style_, "uCropRot");
  u_style_.lut = glGetUniformLocation(prog_style_, "uLut");
  u_style_.lut_on = glGetUniformLocation(prog_style_, "uLutOn");
  u_style_.lut_strength = glGetUniformLocation(prog_style_, "uLutStrength");
  u_style_.lut_dmin = glGetUniformLocation(prog_style_, "uLutDomainMin");
  u_style_.lut_dinv = glGetUniformLocation(prog_style_, "uLutDomainInv");
  u_style_.lut_size = glGetUniformLocation(prog_style_, "uLutSize");
  // E4-2 暗角（vignette）
  u_style_.vig_scale = glGetUniformLocation(prog_style_, "uVigScaleXY");
  u_style_.vig_dscale = glGetUniformLocation(prog_style_, "uVigDscale");
  u_style_.vig_fscale = glGetUniformLocation(prog_style_, "uVigFscale");
  u_style_.vig_brightness = glGetUniformLocation(prog_style_, "uVigBrightness");
  // E2-2 基础调整（ADR §5.6）
  u_style_.ev = glGetUniformLocation(prog_style_, "uEv");
  u_style_.wb_gain = glGetUniformLocation(prog_style_, "uWbGain");
  u_style_.exp_wb_on = glGetUniformLocation(prog_style_, "uExpWbOn");
  u_style_.contrast = glGetUniformLocation(prog_style_, "uContrast");
  u_style_.contrast_on = glGetUniformLocation(prog_style_, "uContrastOn");
  // U-2 [20..22]：分区饱和三档（替代原来的单值 uSat；权重与 kFsFilm 同口径）
  u_style_.sat_shadow = glGetUniformLocation(prog_style_, "uSatShadow");
  u_style_.sat_mid = glGetUniformLocation(prog_style_, "uSatMid");
  u_style_.sat_high = glGetUniformLocation(prog_style_, "uSatHigh");
  u_style_.sat_on = glGetUniformLocation(prog_style_, "uSatOn");
  // U-2 胶片曲线 [5..10] + [37][38]（见 kFsStyle 的同名 uniform 声明）
  u_style_.curve_on = glGetUniformLocation(prog_style_, "uCurveOn");
  u_style_.curve_lo = glGetUniformLocation(prog_style_, "uCurveLo");
  u_style_.curve_hi = glGetUniformLocation(prog_style_, "uCurveHi");
  u_style_.curve_toe_at = glGetUniformLocation(prog_style_, "uCurveToeAt");
  u_style_.curve_sh_at = glGetUniformLocation(prog_style_, "uCurveShAt");
  u_style_.curve_toe_m1 = glGetUniformLocation(prog_style_, "uCurveToeM1");
  u_style_.curve_toe_m2 = glGetUniformLocation(prog_style_, "uCurveToeM2");
  u_style_.curve_toe_m3 = glGetUniformLocation(prog_style_, "uCurveToeM3");
  u_style_.curve_toe_m4 = glGetUniformLocation(prog_style_, "uCurveToeM4");
  u_style_.curve_toe_m5 = glGetUniformLocation(prog_style_, "uCurveToeM5");
  u_style_.curve_sh_m1 = glGetUniformLocation(prog_style_, "uCurveShM1");
  u_style_.curve_sh_m2 = glGetUniformLocation(prog_style_, "uCurveShM2");
  u_style_.curve_sh_m3 = glGetUniformLocation(prog_style_, "uCurveShM3");
  u_style_.curve_sh_m4 = glGetUniformLocation(prog_style_, "uCurveShM4");
  u_style_.curve_sh_m5 = glGetUniformLocation(prog_style_, "uCurveShM5");
  u_style_.curve_lin_m1 = glGetUniformLocation(prog_style_, "uCurveLinM1");
  u_style_.curve_lin_m2 = glGetUniformLocation(prog_style_, "uCurveLinM2");
  u_style_.base_toe_at = glGetUniformLocation(prog_style_, "uBaseToeAt");
  u_style_.base_sh_at = glGetUniformLocation(prog_style_, "uBaseShAt");
  u_style_.base_toe_m1 = glGetUniformLocation(prog_style_, "uBaseToeM1");
  u_style_.base_toe_m2 = glGetUniformLocation(prog_style_, "uBaseToeM2");
  u_style_.base_toe_m3 = glGetUniformLocation(prog_style_, "uBaseToeM3");
  u_style_.base_toe_m4 = glGetUniformLocation(prog_style_, "uBaseToeM4");
  u_style_.base_toe_m5 = glGetUniformLocation(prog_style_, "uBaseToeM5");
  u_style_.base_sh_m1 = glGetUniformLocation(prog_style_, "uBaseShM1");
  u_style_.base_sh_m2 = glGetUniformLocation(prog_style_, "uBaseShM2");
  u_style_.base_sh_m3 = glGetUniformLocation(prog_style_, "uBaseShM3");
  u_style_.base_sh_m4 = glGetUniformLocation(prog_style_, "uBaseShM4");
  u_style_.base_sh_m5 = glGetUniformLocation(prog_style_, "uBaseShM5");
  u_style_.base_lin_m1 = glGetUniformLocation(prog_style_, "uBaseLinM1");
  u_style_.base_lin_m2 = glGetUniformLocation(prog_style_, "uBaseLinM2");
  u_style_.clarity = glGetUniformLocation(prog_style_, "uClarity");
  u_style_.clar_step = glGetUniformLocation(prog_style_, "uClarStep");
  // E2-6 局部调整（ADR §5.7）：mask 程序化生成（纯算术，无纹理/无额外 pass）
  u_style_.local_on = glGetUniformLocation(prog_style_, "uLocalOn");
  u_style_.local_type = glGetUniformLocation(prog_style_, "uLocalType");
  u_style_.local_center = glGetUniformLocation(prog_style_, "uLocalCenter");
  u_style_.local_radius = glGetUniformLocation(prog_style_, "uLocalRadius");
  u_style_.local_feather = glGetUniformLocation(prog_style_, "uLocalFeather");
  u_style_.local_angle = glGetUniformLocation(prog_style_, "uLocalAngle");
  u_style_.local_width = glGetUniformLocation(prog_style_, "uLocalWidth");
  u_style_.local_ev = glGetUniformLocation(prog_style_, "uLocalEv");
  u_style_.local_ev_on = glGetUniformLocation(prog_style_, "uLocalEvOn");
  u_style_.local_sat = glGetUniformLocation(prog_style_, "uLocalSat");
  u_style_.local_sat_on = glGetUniformLocation(prog_style_, "uLocalSatOn");
  // E2-11 影调分区 + 褪色（色温/色调无独立 uniform：并入 uWbGain）
  u_style_.highlights = glGetUniformLocation(prog_style_, "uHighlights");
  u_style_.shadows = glGetUniformLocation(prog_style_, "uShadows");
  u_style_.whites = glGetUniformLocation(prog_style_, "uWhites");
  u_style_.blacks = glGetUniformLocation(prog_style_, "uBlacks");
  u_style_.fade = glGetUniformLocation(prog_style_, "uFade");
  u_style_.tone_on = glGetUniformLocation(prog_style_, "uToneOn");
  // ── SG-1 柔光（soft glow）：与 u_style_ 同名同义（同一段 shader 数学）──────
  // uGlowTexel = 1/glowW, 1/glowH —— glowFetch 的手写双线性用它做"UV → 像素"换算。
  // 纹理尺寸 = halo_tmp_*（柔光图就写在 tex_halo_tmp_ 上，1/2 分辨率）。
  u_style_.glow = glGetUniformLocation(prog_style_, "uGlow");
  u_style_.glow_on = glGetUniformLocation(prog_style_, "uGlowOn");
  u_style_.glow_strength = glGetUniformLocation(prog_style_, "uGlowStrength");
  u_style_.glow_texel = glGetUniformLocation(prog_style_, "uGlowTexel");
  // U-1 分离色调 [23][24] + 单色 [25]（见 kFsStyle 的同名 uniform 声明）
  u_style_.split = glGetUniformLocation(prog_style_, "uSplit");
  u_style_.mono = glGetUniformLocation(prog_style_, "uMono");
  u_hist_.tex = glGetUniformLocation(prog_hist_, "uTex");
  u_hist_.lut = glGetUniformLocation(prog_hist_, "uLut");
  u_hist_.lut_on = glGetUniformLocation(prog_hist_, "uLutOn");
  u_hist_.lut_strength = glGetUniformLocation(prog_hist_, "uLutStrength");
  u_hist_.lut_dmin = glGetUniformLocation(prog_hist_, "uLutDomainMin");
  u_hist_.lut_dinv = glGetUniformLocation(prog_hist_, "uLutDomainInv");
  u_hist_.lut_size = glGetUniformLocation(prog_hist_, "uLutSize");
  u_hist_.stat_ch = glGetUniformLocation(prog_hist_, "uStatCh");
  u_hist_.stat_mode = glGetUniformLocation(prog_hist_, "uStatMode");
  u_local_src_.tex = glGetUniformLocation(prog_local_, "uTex");
  u_local_src_.step = glGetUniformLocation(prog_local_, "uStep");

  u_fill_st_ = glGetUniformLocation(prog_fill_, "uST");
  if (u_fill_st_ >= 0) {  // 填充测试不采样：uST 置单位阵（uniform 默认全 0）
    const float ident[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    glUseProgram(prog_fill_);
    glUniformMatrix4fv(u_fill_st_, 1, GL_FALSE, ident);
  }
  u_fill_.rot = glGetUniformLocation(prog_fill_, "uRot");
  u_fill_.scale = glGetUniformLocation(prog_fill_, "uScale");

  glDisable(GL_BLEND);
  glDisable(GL_DEPTH_TEST);

  // M4-7 局部色调映射的低频亮度图：init 就建好（32×24、RGBA8）。
  // 与 LUT 同理 —— kFsFilm 里存在 sampler2D，关闭档也必须绑合法纹理，否则 Mali
  // 预测执行 `if (uLocalStrength > 0.0)` 分支里的 texture() 会让整帧 draw 报 1282
  // （v0.6.0-m4 全黑同族事故；LSC 关闭档绑 1×1 全 1 网格是同一处理）。
  // 过滤必须 LINEAR：makeTex 默认 NEAREST，会把 32×24 的格子放大成可见方块。
  // @domain: D5 —— local_tone 的低频亮度图（显示域，RGBA8 8bit）
  makeTex(&tex_local_, &fbo_local_, GL_RGBA8, kLocalW, kLocalH);
  local_w_ = kLocalW;
  local_h_ = kLocalH;
  glBindTexture(GL_TEXTURE_2D, tex_local_);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

  // M4：默认 1×1×1 恒等 LUT 纹理。
  // **必须始终**有一张完整的 3D 纹理绑在单元 1：kFsOut/kFsHist 里存在 sampler3D，
  // Mali 会预测执行 `if (uLutOn > 0.5)` 分支里的 texelFetch —— 未绑定 3D 纹理时
  // draw 报 GL_INVALID_OPERATION(1282) 并丢弃整帧输出（v0.6.0-m4 真机全黑根因，
  // 与 LSC「关闭档绑 1×1 全 1 网格」同一类坑）。
  {
    const float kIdentityWhite[4] = {1.0f, 1.0f, 1.0f, 1.0f};
    glGenTextures(1, &lut_tex_);
    glBindTexture(GL_TEXTURE_3D, lut_tex_);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
    glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA16F, 1, 1, 1, 0, GL_RGBA, GL_FLOAT,
                 kIdentityWhite);
    lut_size_ = 1;  // ≤1 = 关闭：uLutOn 恒 0
  }

  Logger::instance().log(LogLevel::kInfo, "engine", "render.init", "{}");
  return true;
}

void FrameRenderer::release() {
  if (tex_scene_) glDeleteTextures(1, &tex_scene_);
  if (tex_work_) glDeleteTextures(1, &tex_work_);
  if (tex_cap_) glDeleteTextures(1, &tex_cap_);
  if (tex_gainmap_) {
    glDeleteTextures(1, &tex_gainmap_);
    glDeleteFramebuffers(1, &fbo_gainmap_);
    tex_gainmap_ = fbo_gainmap_ = 0;
    gainmap_w_ = gainmap_h_ = 0;
  }
  // HDR 曝光融合 P2（0.12.77）：侧帧 Bayer 与 D4 保存纹理（惰性建，拍照用）
  if (tex_hdr_side_) glDeleteTextures(1, &tex_hdr_side_);
  if (tex_hdr_zero_) glDeleteTextures(1, &tex_hdr_zero_);
  tex_hdr_side_ = tex_hdr_zero_ = 0;
  hdr_side_dst_w_ = hdr_side_dst_h_ = 0;
  if (tex_dir_) glDeleteTextures(1, &tex_dir_);
  if (tex_g_) glDeleteTextures(1, &tex_g_);
  if (tex_mos_) glDeleteTextures(1, &tex_mos_);
  if (tex_rcd_a_) glDeleteTextures(1, &tex_rcd_a_);  // P3 拆双 pass 中间场
  if (tex_cs_) {
    glDeleteTextures(1, &tex_cs_);
    glDeleteFramebuffers(1, &fbo_cs_);
    tex_cs_ = fbo_cs_ = 0;
  }
  if (tex_cs_tmp_) {
    glDeleteTextures(1, &tex_cs_tmp_);
    glDeleteFramebuffers(1, &fbo_cs_tmp_);
    tex_cs_tmp_ = fbo_cs_tmp_ = 0;
  }
  if (tex_cm_) {   // 0.12.162：色差域中值
    glDeleteTextures(1, &tex_cm_);
    glDeleteFramebuffers(1, &fbo_cm_);
    tex_cm_ = fbo_cm_ = 0;
  }
  if (tex_cm_tmp_) {
    glDeleteTextures(1, &tex_cm_tmp_);
    glDeleteFramebuffers(1, &fbo_cm_tmp_);
    tex_cm_tmp_ = fbo_cm_tmp_ = 0;
  }
  if (tex_dn_) glDeleteTextures(1, &tex_dn_);
  if (tex_mo_stat_) glDeleteTextures(1, &tex_mo_stat_);   // 档 A 统计网格
  if (fbo_mo_stat_) glDeleteFramebuffers(1, &fbo_mo_stat_);
  releaseDiagTargets();  // v0.10.166：诊断统计的 4+1 张小纹理（~200KB）
  if (tex_hist_) glDeleteTextures(1, &tex_hist_);
  if (tex_out_s_) glDeleteTextures(1, &tex_out_s_);
  if (lut_tex_) glDeleteTextures(1, &lut_tex_);
  // E4-3 颗粒：相纸 LUT 纹理（128×128 R16F，64KB）+ 失效键复位（下次按需重建）
  if (tex_grain_lut_) glDeleteTextures(1, &tex_grain_lut_);
  grain_lut_mb_ = -1.0f;
  grain_lut_pivot_ = -1.0f;
  // E4-5 halation：5 级金字塔 + tmp + 全分辨率输出（releaseHaloTargets 同时复位
  // 缓存键 ⇒ 下次 [62] > 0 时按新尺寸重建）。
  releaseHaloTargets();
  if (fbo_scene_) glDeleteFramebuffers(1, &fbo_scene_);
  if (fbo_work_) glDeleteFramebuffers(1, &fbo_work_);
  if (fbo_cap_) glDeleteFramebuffers(1, &fbo_cap_);
  if (fbo_dir_) glDeleteFramebuffers(1, &fbo_dir_);
  if (fbo_g_) glDeleteFramebuffers(1, &fbo_g_);
  if (fbo_mos_) glDeleteFramebuffers(1, &fbo_mos_);
  if (fbo_rcd_a_) glDeleteFramebuffers(1, &fbo_rcd_a_);  // P3 拆双 pass 中间场
  if (fbo_dn_) glDeleteFramebuffers(1, &fbo_dn_);
  if (fbo_hist_) glDeleteFramebuffers(1, &fbo_hist_);
  if (fbo_out_s_) glDeleteFramebuffers(1, &fbo_out_s_);
  // 横向色差校正（v0.10.170）：全分辨率输出 + 1/8 估计图
  if (tex_ca_) glDeleteTextures(1, &tex_ca_);
  if (fbo_ca_) glDeleteFramebuffers(1, &fbo_ca_);
  if (tex_ca_ds_) glDeleteTextures(1, &tex_ca_ds_);
  if (fbo_ca_ds_) glDeleteFramebuffers(1, &fbo_ca_ds_);
  releaseHrTargets();  // v0.10.15：HR 的 10 张全分辨率纹理
  if (prog_hr_mask_) glDeleteProgram(prog_hr_mask_);
  if (prog_hr_inpaint_) glDeleteProgram(prog_hr_inpaint_);
  if (prog_hr_blur_) glDeleteProgram(prog_hr_blur_);
  if (prog_hr_hf_) glDeleteProgram(prog_hr_hf_);
  if (prog_hr_rin_) glDeleteProgram(prog_hr_rin_);
  if (prog_hr_step_) glDeleteProgram(prog_hr_step_);
  if (prog_hr_norms_) glDeleteProgram(prog_hr_norms_);
  if (prog_hr_ratios_) glDeleteProgram(prog_hr_ratios_);
  if (prog_hr_restore_) glDeleteProgram(prog_hr_restore_);
  if (prog_hr_stat_) glDeleteProgram(prog_hr_stat_);
  if (prog_oes_) glDeleteProgram(prog_oes_);
  if (prog_still_) glDeleteProgram(prog_still_);
  if (prog_film_) glDeleteProgram(prog_film_);
  if (prog_out_) glDeleteProgram(prog_out_);
  if (prog_style_) glDeleteProgram(prog_style_);  // E1-3 编辑器通路
  if (prog_fill_) glDeleteProgram(prog_fill_);
  if (prog_raw_) glDeleteProgram(prog_raw_);
  if (prog_rcd_dir_) glDeleteProgram(prog_rcd_dir_);
  if (prog_rcd_g_) glDeleteProgram(prog_rcd_g_);
  if (prog_rcd_chroma_) glDeleteProgram(prog_rcd_chroma_);
  if (prog_rcd_chroma_a_) glDeleteProgram(prog_rcd_chroma_a_);  // P3 拆双 pass
  if (prog_rcd_chroma_b_) glDeleteProgram(prog_rcd_chroma_b_);
  if (prog_ccm_) glDeleteProgram(prog_ccm_);
  if (prog_raw_dn_) glDeleteProgram(prog_raw_dn_);
  if (prog_chroma_dn_) glDeleteProgram(prog_chroma_dn_);
  if (prog_moire_stat_) glDeleteProgram(prog_moire_stat_);   // 档 A（v0.10.165）
  if (prog_diag_down_) glDeleteProgram(prog_diag_down_);     // 诊断统计（v0.10.166）
  if (prog_diag_pack_) glDeleteProgram(prog_diag_pack_);
  if (prog_chroma_down_) glDeleteProgram(prog_chroma_down_);
  if (prog_chroma_at_) glDeleteProgram(prog_chroma_at_);
  if (prog_chroma_apply_) glDeleteProgram(prog_chroma_apply_);
  if (prog_df_down_) glDeleteProgram(prog_df_down_);
  if (prog_df_det_) glDeleteProgram(prog_df_det_);
  if (prog_df_avg_) glDeleteProgram(prog_df_avg_);
  if (prog_df_apply_) glDeleteProgram(prog_df_apply_);
  if (prog_ca_ds_) glDeleteProgram(prog_ca_ds_);
  if (prog_ca_corr_) glDeleteProgram(prog_ca_corr_);
  if (prog_mfnr_pyr_) glDeleteProgram(prog_mfnr_pyr_);
  if (prog_mfnr_down_) glDeleteProgram(prog_mfnr_down_);
  if (prog_mfnr_fuse_) glDeleteProgram(prog_mfnr_fuse_);
  if (prog_mfnr_mis_) glDeleteProgram(prog_mfnr_mis_);
  if (prog_raw_dn_fused_) glDeleteProgram(prog_raw_dn_fused_);
  if (prog_raw_dn_ts_) glDeleteProgram(prog_raw_dn_ts_);
  if (prog_hl_mask_) glDeleteProgram(prog_hl_mask_);
  if (prog_satm_) glDeleteProgram(prog_satm_);   // 0.12.181
  if (tex_satm_) glDeleteTextures(1, &tex_satm_);
  if (tex_satm_dst_) glDeleteTextures(1, &tex_satm_dst_);
  if (fbo_satm_) glDeleteFramebuffers(1, &fbo_satm_);
  if (prog_hl_dil_) glDeleteProgram(prog_hl_dil_);
  if (prog_hl_chroma_) glDeleteProgram(prog_hl_chroma_);
  if (prog_hl_apply_) glDeleteProgram(prog_hl_apply_);
  if (prog_hl_ref_) glDeleteProgram(prog_hl_ref_);
  if (prog_hl_refdown_) glDeleteProgram(prog_hl_refdown_);
  if (tex_hl_mask_) glDeleteTextures(1, &tex_hl_mask_);
  if (fbo_hl_mask_) glDeleteFramebuffers(1, &fbo_hl_mask_);
  if (tex_hl_dil_) glDeleteTextures(1, &tex_hl_dil_);
  if (fbo_hl_dil_) glDeleteFramebuffers(1, &fbo_hl_dil_);
  if (tex_hl_sum_) glDeleteTextures(1, &tex_hl_sum_);
  if (fbo_hl_sum_) glDeleteFramebuffers(1, &fbo_hl_sum_);
  if (tex_hl_) glDeleteTextures(1, &tex_hl_);
  if (fbo_hl_) glDeleteFramebuffers(1, &fbo_hl_);
  if (tex_hl_ref_) glDeleteTextures(1, &tex_hl_ref_);
  if (fbo_hl_ref_) glDeleteFramebuffers(1, &fbo_hl_ref_);
  if (tex_hl_ref2_) glDeleteTextures(1, &tex_hl_ref2_);
  if (fbo_hl_ref2_) glDeleteFramebuffers(1, &fbo_hl_ref2_);
  if (prog_nn_pack_) glDeleteProgram(prog_nn_pack_);
  if (prog_nn_unpack_) glDeleteProgram(prog_nn_unpack_);
  if (prog_mfnr_fft_sum_) glDeleteProgram(prog_mfnr_fft_sum_);
  // 0.12.133（方案 B 块 3）：短帧真值图 + inpaint 目标
  if (prog_mfnr_sv_) glDeleteProgram(prog_mfnr_sv_);
  if (prog_hl_inp_) glDeleteProgram(prog_hl_inp_);
  if (tex_short_val_) glDeleteTextures(1, &tex_short_val_);
  if (fbo_short_val_) glDeleteFramebuffers(1, &fbo_short_val_);
  if (tex_fused_inp_) glDeleteTextures(1, &tex_fused_inp_);
  if (fbo_fused_inp_) glDeleteFramebuffers(1, &fbo_fused_inp_);
  prog_mfnr_sv_ = prog_hl_inp_ = 0;
  tex_short_val_ = fbo_short_val_ = 0;
  tex_fused_inp_ = fbo_fused_inp_ = 0;
  sv_w_ = sv_h_ = inp_w_ = inp_h_ = 0;
  short_val_scale_ = 0.f;
  short_val_ok_ = 0;
  for (auto& t : tex_fft_out_) {
    if (t) glDeleteTextures(1, &t);
    t = 0;
  }
  prog_mfnr_fft_sum_ = 0;
  burst_w_ = burst_h_ = 0;
  for (auto& t : tex_mfnr_) {
    if (t) glDeleteTextures(1, &t);
    t = 0;
  }
  for (auto& t : tex_off_) {
    if (t) glDeleteTextures(1, &t);
    t = 0;
  }
  for (auto& t : tex_off_ab_) {
    if (t) glDeleteTextures(1, &t);
    t = 0;
  }
  if (tex_mis_) glDeleteTextures(1, &tex_mis_);
  if (fbo_mis_) glDeleteFramebuffers(1, &fbo_mis_);
  if (tex_w_) glDeleteTextures(1, &tex_w_);
  tex_mis_ = fbo_mis_ = 0;
  tex_w_ = 0;
  if (tex_pyr1_) glDeleteTextures(1, &tex_pyr1_);
  if (fbo_pyr1_) glDeleteFramebuffers(1, &fbo_pyr1_);
  if (tex_pyr2_) glDeleteTextures(1, &tex_pyr2_);
  if (fbo_pyr2_) glDeleteFramebuffers(1, &fbo_pyr2_);
  if (tex_pyr3_) glDeleteTextures(1, &tex_pyr3_);
  if (fbo_pyr3_) glDeleteFramebuffers(1, &fbo_pyr3_);
  if (tex_pyr4_) glDeleteTextures(1, &tex_pyr4_);
  if (fbo_pyr4_) glDeleteFramebuffers(1, &fbo_pyr4_);
  if (tex_fused_) glDeleteTextures(1, &tex_fused_);
  if (fbo_fused_) glDeleteFramebuffers(1, &fbo_fused_);
  // NN pass（v0.10.102）：纹理 + CPU 缓冲一并释放
  if (tex_nn_packed_) glDeleteTextures(1, &tex_nn_packed_);
  if (fbo_nn_packed_) glDeleteFramebuffers(1, &fbo_nn_packed_);
  if (tex_nn_out_) glDeleteTextures(1, &tex_nn_out_);
  if (tex_nn_fused_) glDeleteTextures(1, &tex_nn_fused_);
  if (fbo_nn_fused_) glDeleteFramebuffers(1, &fbo_nn_fused_);
  if (tex_nn_fdummy_) glDeleteTextures(1, &tex_nn_fdummy_);
  if (tex_nn_rdummy_) glDeleteTextures(1, &tex_nn_rdummy_);
  tex_nn_packed_ = fbo_nn_packed_ = 0;
  tex_nn_out_ = 0;
  tex_nn_fused_ = fbo_nn_fused_ = 0;
  tex_nn_fdummy_ = 0;
  tex_nn_rdummy_ = 0;
  nn_w_ = nn_h_ = 0;
  nn_packed_.clear();
  nn_packed_.shrink_to_fit();
  nn_out_.clear();
  nn_out_.shrink_to_fit();
  nn_weight_.clear();
  nn_weight_.shrink_to_fit();
  nn_ramp_.clear();
  nn_ramp_.shrink_to_fit();
  prog_nn_pack_ = prog_nn_unpack_ = 0;
  if (tex_mis_) glDeleteTextures(1, &tex_mis_);
  if (fbo_mis_) glDeleteFramebuffers(1, &fbo_mis_);
  if (tex_w_) glDeleteTextures(1, &tex_w_);
  tex_w_ = 0;
  prog_mfnr_pyr_ = prog_mfnr_down_ = prog_mfnr_fuse_ = prog_raw_dn_fused_ = 0;
  prog_raw_dn_ts_ = 0;
  prog_hl_mask_ = prog_hl_dil_ = prog_hl_chroma_ = prog_hl_apply_ = 0;
  prog_hl_ref_ = 0;
  prog_hl_refdown_ = 0;
  tex_hl_mask_ = fbo_hl_mask_ = 0;
  tex_hl_dil_ = fbo_hl_dil_ = 0;
  tex_hl_sum_ = fbo_hl_sum_ = 0;
  tex_hl_ = fbo_hl_ = 0;
  tex_hl_ref_ = fbo_hl_ref_ = 0;
  tex_hl_ref2_ = fbo_hl_ref2_ = 0;
  hl_w_ = hl_h_ = hl_mw_ = hl_mh_ = hl_tw_ = hl_th_ = 0;
  hr_src_override_ = 0;
  prog_mfnr_mis_ = 0;
  tex_pyr1_ = tex_pyr2_ = tex_pyr3_ = tex_pyr4_ = 0;
  fbo_pyr1_ = fbo_pyr2_ = fbo_pyr3_ = fbo_pyr4_ = 0;
  tex_fused_ = fbo_fused_ = 0;
  tex_mis_ = fbo_mis_ = 0;
  pyr1_w_ = pyr1_h_ = 0;
  mfnr_w_ = mfnr_h_ = 0;
  tiles_w_ = tiles_h_ = 0;
  if (vs_rot_) glDeleteShader(vs_rot_);
  if (vs_plain_) glDeleteShader(vs_plain_);
  if (still_tex_) glDeleteTextures(1, &still_tex_);
  if (edit_tex_) glDeleteTextures(1, &edit_tex_);  // E1-5 编辑器输入纹理
  if (raw_tex_) glDeleteTextures(1, &raw_tex_);
  if (lsc_tex_) glDeleteTextures(1, &lsc_tex_);
  still_tex_ = 0;
  edit_tex_ = 0;
  edit_w_ = edit_h_ = 0;
  raw_tex_ = 0;
  lsc_tex_ = 0;
  lsc_w_ = lsc_h_ = 0;
  lut_tex_ = 0;
  lut_size_ = 0;
  raw_w_ = raw_h_ = 0;
  tex_dir_ = tex_g_ = 0;
  fbo_dir_ = fbo_g_ = 0;
  rcd_w_ = rcd_h_ = 0;
  tex_mos_ = tex_dn_ = tex_hist_ = 0;
  fbo_mos_ = fbo_dn_ = fbo_hist_ = 0;
  tex_mo_stat_ = fbo_mo_stat_ = 0;   // 档 A 统计网格
  releaseDiagTargets();              // v0.10.166：诊断统计纹理/FBO（已自带置 0）
  hist_w_ = hist_h_ = 0;
  tex_scene_ = tex_work_ = tex_cap_ = 0;
  tex_gainmap_ = fbo_gainmap_ = 0;
  fbo_scene_ = fbo_work_ = fbo_cap_ = 0;
  prog_oes_ = prog_still_ = prog_film_ = prog_out_ = prog_fill_ = prog_raw_ = 0;
  prog_rcd_dir_ = prog_rcd_g_ = prog_rcd_chroma_ = 0;
  prog_ccm_ = 0;
  prog_hdr_mertens_ = 0;  // HDR 融合 P2
  prog_raw_dn_ = prog_chroma_dn_ = prog_moire_stat_ = 0;
  prog_diag_down_ = prog_diag_pack_ = 0;   // 诊断统计（v0.10.166）
  prog_chroma_down_ = prog_chroma_at_ = prog_chroma_apply_ = 0;
  prog_hist_ = 0;
  prog_local_ = 0;
  vs_rot_ = vs_plain_ = 0;
  oes_tex_ = 0;
  still_w_ = still_h_ = 0;
  scene_w_ = scene_h_ = cap_w_ = cap_h_ = 0;
}

void FrameRenderer::makeTex(GLuint* tex, GLuint* fbo, GLenum internal, int w,
                            int h) {
  if (*tex == 0) glGenTextures(1, tex);
  if (*fbo == 0) glGenFramebuffers(1, fbo);
  glBindTexture(GL_TEXTURE_2D, *tex);
  // RGBA16F 无线性过滤（X300 实测 quirk）；管内 1:1 采样 NEAREST 即可
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  // 类型必须与内部格式匹配：RGBA8 用 UNSIGNED_BYTE，RGBA16F 用 HALF_FLOAT。
  // （X300 实测：RGBA8 + HALF_FLOAT 非法组合静默失败 → 捕获 FBO 永不完整 → 照片全黑）
  // 卡 H2：交付档有三种内部格式（RGBA8 / GL_RGB10_A2 / RGBA16F）⇒ 映射收口到
  // OutputBitDepth.h 的 outputUploadTypeOf（10bit 档 = GL_UNSIGNED_INT_2_10_10_10_REV）。
  const GLenum type = outputUploadTypeOf(internal);
  glTexImage2D(GL_TEXTURE_2D, 0, static_cast<GLint>(internal), w, h, 0, GL_RGBA,
               type, nullptr);
  glBindFramebuffer(GL_FRAMEBUFFER, *fbo);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                         *tex, 0);
}

// 逐 pass 诊断：GL 错误码 + 目标(0,0)像素，定位渲染断点层级
// internal（卡 H2）：目标附件的内部格式 —— 三种读回口径由真源统一给出：
//   · 交付档（RGBA8 / GL_RGB10_A2）→ (kOutputGlReadFormat, outputUploadTypeOf)
//     ⇒ 10bit 档按 GL_UNSIGNED_INT_2_10_10_10_REV 读，解包成 10bit 整数打印
//     （**不再**用 8bit 口径去读 10bit 附件：非法组合会被驱动拒绝，把
//      GL_INVALID_OPERATION 误报成"渲染失败"，诊断自己变成事故源）。
//   · 中间纹理（RGBA16F）→ GL_HALF_FLOAT，照旧换算成浮点打印。
void FrameRenderer::logPassDiag(const char* stage, GLuint fbo, GLenum internal) {
  const GLenum err = glGetError();
  glBindFramebuffer(GL_FRAMEBUFFER, fbo);
  std::string kv =
      std::string("{\"stage\":\"") + stage + "\",\"glErr\":" + std::to_string(err);
  if (isOutputInternal(internal)) {
    // 交付档：RGBA8 与 RGB10_A2 都是定点，但**每通道位宽不同** ⇒ 分开解包。
    GLubyte b4[4] = {0, 0, 0, 0};
    if (internal == kOutput10GlInternalFormat) {
      glReadPixels(0, 0, 1, 1, kOutput10GlReadFormat, kOutput10GlReadType, b4);
      // 2_10_10_10_REV = LSB-first：bit0..9 = R、10..19 = G、20..29 = B
      const uint32_t v = static_cast<uint32_t>(b4[0]) |
                         (static_cast<uint32_t>(b4[1]) << 8) |
                         (static_cast<uint32_t>(b4[2]) << 16) |
                         (static_cast<uint32_t>(b4[3]) << 24);
      kv += ",\"bits\":10,\"r\":" + std::to_string(v & 0x3FFu);
      kv += ",\"g\":" + std::to_string((v >> 10) & 0x3FFu);
      kv += ",\"b\":" + std::to_string((v >> 20) & 0x3FFu);
      kv += ",\"a\":" + std::to_string((v >> 30) & 0x3u) + "}";
    } else {
      glReadPixels(0, 0, 1, 1, kOutputGlReadFormat, kOutputGlReadType, b4);
      kv += ",\"bits\":8,\"r\":" + std::to_string(b4[0]);
      kv += ",\"g\":" + std::to_string(b4[1]);
      kv += ",\"b\":" + std::to_string(b4[2]);
      kv += ",\"a\":" + std::to_string(b4[3]) + "}";
    }
  } else {
    GLushort raw[4] = {0, 0, 0, 0};
    glReadPixels(0, 0, 1, 1, GL_RGBA, GL_HALF_FLOAT, raw);
    kv += ",\"r\":" + std::to_string(halfToFloat(raw[0]));
    kv += ",\"g\":" + std::to_string(halfToFloat(raw[1]));
    kv += ",\"b\":" + std::to_string(halfToFloat(raw[2])) + "}";
  }
  Logger::instance().log(LogLevel::kInfo, "engine", "render.diag", kv);
}

// ── 卡 H2：10bit 交付档读回自检（**绝对**验证，不是往返）─────────────────────
// 为什么不能用上传→下载往返：同一种错误布局在往返里会**互相抵消**（写进去按错布局
// 解释、读出来又按同一错布局还原），测出来是"通过"而实际交付错色。
// 所以这里用 glClear 写**规范定义的浮点值**，再按交付口径读回，逐点与
// "浮点 → 10bit 定点四舍五入"的期望值比对（容差 1 LSB，覆盖 round-half 的驱动差异）。
// 颜色刻意选 R/G/B 各自独占满量程的三个 case ⇒ **通道位序**（LSB-first：R 在 bit0..9、
// G 在 10..19、B 在 20..29）一旦不是预期就会立刻红 —— 这正是 Kotlin 侧
// Heif10Pipeline.unpackRgb10 的前提。
bool FrameRenderer::selfCheck10BitReadback(std::string* detail) {
  struct Case {
    float r, g, b;
    int er, eg, eb;
    const char* what;
  };
  // 期望值 = round(v × 1023)（ES 3.0 §2.1.2 定点转换 = 就近取整）；0.5×1023 = 511.5
  // 处于 half-way，故容差取 1 LSB（驱动可 round-half-even 或 round-half-up）。
  static const Case kCases[6] = {
      {0.0f, 0.0f, 0.0f, 0, 0, 0, "black"},
      {1.0f, 0.0f, 0.0f, 1023, 0, 0, "R-full"},
      {0.0f, 1.0f, 0.0f, 0, 1023, 0, "G-full"},
      {0.0f, 0.0f, 1.0f, 0, 0, 1023, "B-full"},
      {0.25f, 0.5f, 0.75f, 256, 512, 767, "ramp"},
      {1.0f, 1.0f, 1.0f, 1023, 1023, 1023, "white"},
  };
  constexpr int kN = 6;

  // 保存将被改动的状态（本函数可在任意渲染阶段被调用）
  GLint prev_fbo = 0, prev_vp[4] = {0, 0, 0, 0};
  GLint prev_scissor[4] = {0, 0, 0, 0};
  GLint prev_tex = 0;
  GLfloat prev_clear[4] = {0.0f, 0.0f, 0.0f, 0.0f};
  const GLboolean had_scissor = glIsEnabled(GL_SCISSOR_TEST);
  glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prev_fbo);
  glGetIntegerv(GL_VIEWPORT, prev_vp);
  glGetIntegerv(GL_SCISSOR_BOX, prev_scissor);
  glGetIntegerv(GL_TEXTURE_BINDING_2D, &prev_tex);
  glGetFloatv(GL_COLOR_CLEAR_VALUE, prev_clear);
  while (glGetError() != GL_NO_ERROR) {
  }

  GLuint tex = 0, fbo = 0;
  makeTex(&tex, &fbo, kOutput10GlInternalFormat, kN, 1);
  glBindFramebuffer(GL_FRAMEBUFFER, fbo);
  const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
  bool ok = (status == GL_FRAMEBUFFER_COMPLETE);
  int bad = 0;
  std::string rows;
  GLenum read_err = 0;
  if (ok) {
    glViewport(0, 0, kN, 1);
    glEnable(GL_SCISSOR_TEST);
    for (int i = 0; i < kN; ++i) {
      glScissor(i, 0, 1, 1);
      glClearColor(kCases[i].r, kCases[i].g, kCases[i].b, 1.0f);
      glClear(GL_COLOR_BUFFER_BIT);
    }
    // readback 必须用交付口径的 (format, type)；每像素 4 B（packed uint32）
    unsigned char raw[kN * 4] = {0};
    glReadPixels(0, 0, kN, 1, kOutput10GlReadFormat, kOutput10GlReadType, raw);
    read_err = glGetError();
    for (int i = 0; i < kN; ++i) {
      const uint32_t v = static_cast<uint32_t>(raw[i * 4]) |
                         (static_cast<uint32_t>(raw[i * 4 + 1]) << 8) |
                         (static_cast<uint32_t>(raw[i * 4 + 2]) << 16) |
                         (static_cast<uint32_t>(raw[i * 4 + 3]) << 24);
      const int r = static_cast<int>(v & 0x3FFu);
      const int g = static_cast<int>((v >> 10) & 0x3FFu);
      const int b = static_cast<int>((v >> 20) & 0x3FFu);
      const int a = static_cast<int>((v >> 30) & 0x3u);
      const auto near1 = [](int got, int want) {
        return got >= want - 1 && got <= want + 1;
      };
      // alpha 只有 2 bit（GL_RGB10_A2）：clear alpha=1.0 ⇒ 期望 3
      const bool good = near1(r, kCases[i].er) && near1(g, kCases[i].eg) &&
                        near1(b, kCases[i].eb) && a == 3;
      if (!good) ++bad;
      rows += std::string(rows.empty() ? "" : ",") + "{\"case\":\"" +
              kCases[i].what + "\",\"got\":[" + std::to_string(r) + "," +
              std::to_string(g) + "," + std::to_string(b) + "," +
              std::to_string(a) + "],\"want\":[" + std::to_string(kCases[i].er) +
              "," + std::to_string(kCases[i].eg) + "," +
              std::to_string(kCases[i].eb) + ",3],\"ok\":" +
              (good ? "true" : "false") + "}";
    }
  }

  // 恢复状态 + 释放（自检不得留下任何痕迹：纹理/FBO/视口/清屏色/裁剪框全部还原）
  glDisable(GL_SCISSOR_TEST);
  if (tex) glDeleteTextures(1, &tex);
  if (fbo) glDeleteFramebuffers(1, &fbo);
  glBindFramebuffer(GL_FRAMEBUFFER, prev_fbo);
  glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(prev_tex));
  glViewport(prev_vp[0], prev_vp[1], prev_vp[2], prev_vp[3]);
  glScissor(prev_scissor[0], prev_scissor[1], prev_scissor[2], prev_scissor[3]);
  if (had_scissor) glEnable(GL_SCISSOR_TEST);
  glClearColor(prev_clear[0], prev_clear[1], prev_clear[2], prev_clear[3]);
  while (glGetError() != GL_NO_ERROR) {
  }

  if (detail != nullptr) {
    *detail = std::string("{\"cases\":[") + rows + "],\"fbStatus\":" +
              std::to_string(status) + ",\"readErr\":" +
              std::to_string(read_err) + ",\"bad\":" + std::to_string(bad) + "}";
  }
  return ok && bad == 0 && read_err == GL_NO_ERROR;
}

// 卡 H2：进程级一次性自检（见头文件注释）。状态机：0 = 未跑、3 = 运行中、
// 1 = 通过、2 = 失败。compare_exchange 保证两个实例/两条线程只跑一次。
bool FrameRenderer::selfCheck10BitOnce() {
  static std::atomic<int> state{0};
  int s = state.load(std::memory_order_acquire);
  if (s == 0) {
    int expect = 0;
    if (state.compare_exchange_strong(expect, 3, std::memory_order_acq_rel)) {
      std::string detail;
      const bool ok = selfCheck10BitReadback(&detail);
      // 事件名与真机探针的 heif10.* 同命名空间 ⇒ 用户导日志后一条 grep 就能看到
      // 交付档是否可信（探针 = 编码器/容器侧，本自检 = GL 交付档侧）。
      Logger::instance().log(ok ? LogLevel::kInfo : LogLevel::kError, "engine",
                             "heif10.selfcheck",
                             std::string("{\"ok\":") + (ok ? "true" : "false") +
                                 ",\"detail\":" + detail + "}");
      state.store(ok ? 1 : 2, std::memory_order_release);
      return ok;
    }
    // 另一线程正在跑（自检 < 1 ms）：自旋等结果
    while ((s = state.load(std::memory_order_acquire)) == 3) {
    }
  }
  return s == 1;
}

bool FrameRenderer::ensureTargets(int w, int h) {
  if (w <= 0 || h <= 0) return false;
  if (w != scene_w_ || h != scene_h_) {
    if (tex_scene_) {
      glDeleteTextures(1, &tex_scene_);
      glDeleteFramebuffers(1, &fbo_scene_);
      glDeleteTextures(1, &tex_work_);
      glDeleteFramebuffers(1, &fbo_work_);
    }
    tex_scene_ = tex_work_ = 0;
    fbo_scene_ = fbo_work_ = 0;
    // @domain: D4 —— 主链场景线性（4a 后：CCM 输出 → P4 → D-1 汇聚）
    makeTex(&tex_scene_, &fbo_scene_, GL_RGBA16F, w, h);
    // D3 = RCD 的去马赛克输出（第 4a 期起，LSC 后 / CCM 前）。
    // ⚠ 本标签不完整：同一纹理也是 passFilm 的输出目标（显示域 D5）—— 正是 §3.1
    // 告警的『一帧内先后承载多域的纹理，标签本身会说谎』。4a 已把 RCD(D3) 与
    // CCM(D4) 拆开，但 passFilm 仍复用 tex_work_ 作输出 ⇒ **这是全链仅剩的一处
    // 多域复用**，待后续期次拆 per-domain 纹理（成本：+1 张全分辨率 RGBA16F
    // = 100MB，需用户拍板）。
    // @domain: D3
    makeTex(&tex_work_, &fbo_work_, GL_RGBA16F, w, h);
    scene_w_ = w;
    scene_h_ = h;
  }
  // 两个 16F 目标都要完整：任一不完整时该 pass 的绘制会被驱动整个丢弃
  bool ok = true;
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_scene_);
  ok = ok && glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_work_);
  ok = ok && glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
  if (!ok) {
    Logger::instance().log(
        LogLevel::kError, "engine", "render.fbo_incomplete",
        "{\"w\":" + std::to_string(w) + ",\"h\":" + std::to_string(h) + "}");
  }
  return ok;
}

// 降档输出的 RGBA8 中间目标：makeTex 固定 NEAREST，这里必须 LINEAR（blit 升采
// 的平滑性全靠它）。RGBA16F 在 X300 无线性过滤（实测 quirk），RGBA8 有。
bool FrameRenderer::ensureOutScaleTarget(int w, int h) {
  if (w <= 0 || h <= 0) return false;
  if (w != out_s_w_ || h != out_s_h_) {
    if (tex_out_s_) {
      glDeleteTextures(1, &tex_out_s_);
      glDeleteFramebuffers(1, &fbo_out_s_);
    }
    tex_out_s_ = fbo_out_s_ = 0;
    // @domain: D5 —— 预览降档输出（OETF 后，RGBA8）
    makeTex(&tex_out_s_, &fbo_out_s_, kOutputGlInternalFormat, w, h);
    glBindTexture(GL_TEXTURE_2D, tex_out_s_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    out_s_w_ = w;
    out_s_h_ = h;
  }
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_out_s_);
  const bool ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
  if (!ok) {
    Logger::instance().log(
        LogLevel::kError, "engine", "render.outs_incomplete",
        "{\"w\":" + std::to_string(w) + ",\"h\":" + std::to_string(h) + "}");
  }
  return ok;
}

void FrameRenderer::setPreviewScale(float s) {
  g_preview_scale.store(std::clamp(s, 0.25f, 1.0f), std::memory_order_relaxed);
}

float FrameRenderer::previewScale() {
  return g_preview_scale.load(std::memory_order_relaxed);
}

// 诊断统计开关（v0.10.166，prefs `diag_stats`，**默认开**）：只置一个 atomic，其余
// 见文件头的 diag 块。任意线程可调；关闭时全链零额外开销（每个统计点一次
// relaxed atomic load）。
void FrameRenderer::setGainmapMath(int mode) {
  g_gainmap_math.store(mode == 1 ? 1 : 0);
}

int FrameRenderer::gainmapMath() { return g_gainmap_math.load(); }

void FrameRenderer::previewMetering(float* geo, float* ratio) {
  if (geo) *geo = g_pv_geo_ema;
  if (ratio) *ratio = g_domain_ratio;
}

void FrameRenderer::setAutoExposure(bool on) { g_ae_auto.store(on); }

// 局部影调开关（默认开）：用函数内 static 避免全局构造顺序问题
static std::atomic<bool>& toneEqFlag() {
  static std::atomic<bool> f{true};
  return f;
}
void FrameRenderer::setToneEq(bool on) { toneEqFlag().store(on); }
bool FrameRenderer::toneEq() { return toneEqFlag().load(std::memory_order_relaxed); }
bool FrameRenderer::autoExposure() { return g_ae_auto.load(std::memory_order_relaxed); }

static std::atomic<bool>& aeRobustFlag() {
  static std::atomic<bool> f{false};
  return f;
}
void FrameRenderer::setAeRobust(bool on) { aeRobustFlag().store(on); }
bool FrameRenderer::aeRobust() { return aeRobustFlag().load(std::memory_order_relaxed); }

static std::atomic<bool>& aeDisplayFlag() {
  // 0.12.55：**默认关**。0.12.51~54 把显示域闭环做在**预览链**上，等于和 HAL 的 AE
  // 打擂台：HAL 在 30fps 内实时收敛，我们的外环再用滞后的读数推 EV ⇒ 双回路互相激发，
  // 室内表现为"忽暗忽亮"（真机 14:36），成片还因为走 hl 约束而与取景器不一致。
  // 预览/成片一致性只能靠**同一次曝光**保证（PC 的做法），不能在渲染端各算一套。
  // 正确落点 = HAL 的 `CONTROL_AE_EXPOSURE_COMPENSATION`（见 CameraController），
  // 这条渲染端通路保留代码但默认不启用（A/B 与离线复现用）。
  static std::atomic<bool> f{false};
  return f;
}
void FrameRenderer::setAeDisplay(bool on) { aeDisplayFlag().store(on); }
bool FrameRenderer::aeDisplay() { return aeDisplayFlag().load(std::memory_order_relaxed); }

static std::atomic<bool>& aeHalFlag() {
  // 0.12.58：**默认关**。固定目标（0.18）闭环 = 把每个场景的平均亮度都拉到 0.18，
  // 室内平均反射率低 ⇒ 必然过曝（真机 0.12.57 当场拉爆）。这不是参数问题，是
  // "用固定目标闭环"本身的数学性质：暗场景永远会被拉爆，亮场景永远会被压暗。
  // 保留开关供 A/B，默认回到 HAL 自己的 AE。
  static std::atomic<bool> f{false};
  return f;
}
void FrameRenderer::setAeHal(bool on) {
  aeHalFlag().store(on);
  if (on) {
    // 清掉历史闭环残留，避免旧值继续叠加到预览曝光上。

    g_disp_ae_ev = 0.0f;
  }
}
bool FrameRenderer::aeHal() { return aeHalFlag().load(std::memory_order_relaxed); }
float FrameRenderer::displayP50() { return g_pv_disp_p50; }

// 显示域中位（sRGB 编码 0..1）。kFsHist 的 stat_mode=0 输出显示域 luma，直接取中位。
float FrameRenderer::measureDisplayP50(GLuint src) {
  constexpr int kN = 256;
  int hist[kN];
  if (!histogramFrom(src, hist, kN, /*bypass_lut=*/true, /*stat_ch=*/0,
                     /*stat_mode=*/0, /*center_frac=*/0.0f, nullptr)) {
    return 0.0f;
  }
  long total = 0;
  for (int i = 0; i < kN; ++i) total += hist[i];
  if (total <= 0) return 0.0f;
  const long want = total / 2;
  long acc = 0;
  for (int i = 0; i < kN; ++i) {
    acc += hist[i];
    if (acc >= want) return (static_cast<float>(i) + 0.5f) / static_cast<float>(kN);
  }
  return 1.0f;
}

// ── 自动曝光（v0.11.61）────────────────────────────────────────────────────
// 只跑 RAW 静拍链：`film_src` 是降噪/去边之后的 RAW 线性纹理（曝光前），从这里取
// **本张**的 P50 / P99.9。为什么不用上一张的 sw999：那是采集端预测口径，会话第一张
// 必然没有统计（真机日志 `hlEv:-99`），场景一变就用错；PC/MGC 是拍完再按本张统计
// 决定 BaselineExposure，本函数即那一层。
// 返回要追加到 exposure_ev 上的 EV；0 = 不介入（开关关 / 统计失败 / 曝光已正确）。
float FrameRenderer::passAutoExposure(GLuint src, const FilmParams& p,
                                      float short_sat) {
  ae_ev_ = 0.0f;
  ae_mid_ev_ = 0.0f;
  ae_hl_ev_ = 0.0f;
  ae_p50_ = 0.0f;
  ae_p999_ = 0.0f;
  ae_p995_ = 0.0f;
  ae_why_ = 0;
  ae_p_geo_ = 0.0f;
  // 0.12.56：HAL-AE 模式 —— 渲染端 AE 完全退出（额外 EV 恒 0）。
  // 为什么必须彻底退出：渲染端与 HAL 是两条独立回路，同时改 EV 必然互相激发
  // （0.12.51~54 真机：室内忽暗忽亮、成片与取景器亮度不一致）。
  if (aeHal()) {
    ae_ev_ = 0.0f;
    ae_why_ = 6;   // 6 = hal（诊断）
    return 0.0f;
  }
  ae_target_mid_ = kAeTargetMid;
  ae_white_ev_ = kAeRawWhiteEv;
  ae_brk_sat_ = short_sat;
  if (!autoExposure() || src == 0) return 0.0f;
  // 决策公式的零依赖自测：每个进程跑一次，失败只打日志（诊断包可见），不中断出片。
  static std::once_flag once;
  std::call_once(once, [] {
    char err[192] = {0};
    if (!aeMeteringSelfTest(err, sizeof(err))) {
      Logger::instance().log(LogLevel::kError, "engine", "ae.selftest_failed",
                             "{\"err\":\"" + std::string(err) + "\"}");
    }
  });
  // v0.11.78：两层统计各取所需 ——
  //   中灰（主体亮度）用**中央 2/3**（PC centerFractionPerAxis）：画面主体通常在中
  //     央，边缘的窗/天空不该决定主体亮度；
  //   高光（防裁切）用**全图**：0.11.77 只用中央时，阳台/室内场景的中央没有天空，
  //     AE 便一路提亮直接把蓝天顶成灰白（真机 170249：aeEv +1.25、hi240 9.55%）。
  const SceneStats st_full = measureSceneStats(src, 0.0f);
  const SceneStats st_center = measureSceneStats(src, 2.0f / 3.0f);
  if (!st_full.valid || !st_center.valid) {
    ae_why_ = 4;   // 统计失败：保持历史行为（不介入）
    return 0.0f;
  }
  AeInputs in;
  in.valid = true;
  // v0.11.91：中灰锚点用**几何平均**（感知亮度），不再用 P50。
  // P50 对"一半暗部 + 一半亮部"的构图会严重偏低，AE 于是把整张提亮到过曝
  // （真机 2147：P50 0.1076 → +2.0EV → mean 184；同场景几何平均约 0.19 → +1.2EV）。
  // 统计仍取中央 2/3（主体在中心）。geo 无效（空直方图）时退回 P50。
  in.p_mid = st_center.p_geo > 1e-6f ? st_center.p_geo : st_center.p_mid;
  ae_p_geo_ = in.p_mid;
  // v0.11.92：**目标亮度 = 取景器**（预览的几何平均），不再用固定常量。
  // 这是"对齐取景器"的落地：厂商 AE 已经做了区域测光/场景识别，预览就是用户
  // 按下快门前看到的那个亮度。取景器不可用（冷启动首帧 / 无预览）时退回 0.45。
  // clamp [0.12, 0.70] 防止预览统计异常（黑边、遮挡）把成片带飞。
  if (g_pv_geo_ema > 1e-4f) {
    // ── v0.12.23：**除以域比**（域混用修正，取景器/成片亮度差 1.6EV 的根因）──────
    // `g_pv_geo_ema` 是**取景器的几何均值**，量级是 RAW 域的 `g_domain_ratio` 倍
    // （在线 EMA，实测 1.7~3.9；真机 17:00:23 的两边值是 0.2053 / 0.0656 = 3.1）。
    // 旧式直接把它当 **RAW 域**的期望亮度用 ⇒ target 被抬高 3.1 倍 ⇒ AE 额外提亮
    // 1.645 EV ⇒ 成片整体比"A E 认为的正确曝光"亮 1.6EV，代价是 `hi240` 顶死 3~10%。
    // 而预览的输入本就在 HAL 域、被 shader 里的 `uDomainRatio` 除回 RAW 等效域，
    // 所以它拿到的是正确曝光 ⇒ **预览比成片暗 1.6EV**，这正是真机反馈的现象。
    // 除以域比后：target 回到 RAW 域语义，预览与成片同曝光；顶死的高光也会自然消失。
    in.target_mid = std::clamp(g_pv_geo_ema / std::max(g_domain_ratio, 1e-3f), 0.12f, 0.70f);
  }
  // 诊断字段必须在 in.robust/in.p_robust 赋值**之后**再取（0.12.47 把它写在这里，
  // 那时 in.robust 还是默认 false ⇒ 日志恒显示 0.12，真机 10:36 实测复现）。
  ae_target_mid_ = (aeRobust() && st_full.p_robust > 1e-9f) ? in.target_robust : in.target_mid;
  in.p_hi995 = st_full.p_hi995;
  in.user_ev = p.exposure_ev;
  // ── v0.11.87：高光判据的两处语义修正 ──────────────────────────────
  // ① 裁切比例：bracket 下"融合后 ≥0.98 白点"不是裁切，短帧救回的高光也算进去了
  //    （真机 4641：13%，而短帧 sat 0.0% ⇒ 信息其实都在）。有 bracket 时改用采集端
  //    实测的**短帧裁切比例**（= 真丢信息的比例，长帧饱和 ∩ 短帧饱和 ⊆ 短帧饱和）。
  // ② 高光上限：有信息时才允许用"显示域 243 LSB"（kAeDisplayClipEv 4.8EV）；短帧也
  //    饱和（≥0.5%）说明那里确实没信息，退回 RAW 白点保守处理。
  const bool bracket_metering = short_sat >= 0.0f;
  in.sat_frac = bracket_metering ? short_sat : st_full.sat_frac;
  // v0.11.88：白点**统一** 4.8（不再因短帧 sat 大就退回 RAW 白点 2.438）——
  // 压到 2.438 只是把"死白"变成"灰白"，还把整张压暗 2.4EV（真机 2733/2751：
  // 短帧 sat 1.1% ⇒ 退回 2.438 ⇒ aeEv −2.0、mean 34）。真裁切的比例只进日志。
  // v0.11.90：**统一** 4.8（非 bracket 也一样）。旧式非 bracket 用 RAW 白点 2.438，
  // 真机 5517（关 bracket）因此压到 −2.21EV、mean 31.3 —— 而这场景的 P99.5 是 4.34，
  // 压到 2.438 只是把图压暗，长帧饱和处的信息一点也救不回来。真正兜底的是 teq 的
  // 高光压缩（−1.1 / −2.2），不是把中灰压到死。
  // ── 0.12.49：**短帧有余量时，长帧不该再被高光压死** ────────────────────────
  // 真机 2026-10-23 10:36:46（逆光窗）：`aeBrkSat 0.0`（短帧 RAW 零饱和 ⇒ 高光
  // 信息全在），但 `force_hl` 仍按 4.8EV 白点把长帧压到 1.87EV，而中灰想要 2.52EV
  // ⇒ **整体偏暗 0.65EV**。这正是用户报的"带天空场景整体偏暗"。
  // 语义（HDR+ §4 / MGC ComputeAeResults）：短帧存在的意义就是把高光信息带回来，
  // 长帧因此可以按中灰给足；只有短帧本身也饱和（真丢信息）时才退回保守白点。
  // 白点用**曲线死白点 6.22EV**（阶段 2 报告 §4.2：sigmoid 量化到 6.22EV 才不再增长），
  // 不是"不约束"——v0.11.88 的 2712 事故是"完全不约束 ⇒ aeEv 冲 +2.0"，不是放宽白点。
  const bool bracket_has_headroom =
      bracket_metering && short_sat >= 0.0f && short_sat < kAeMaxClippedFrac;
  // 0.12.64：短帧有余量时也**不再放到曲线死白点（6.22）** —— 那是"彻底无层次"的位置，
  // 真机 10-23 17:32（夕阳逆光窗）实证：白点 6.22 时 `aeEv` 冲到 2.0、天空 p95 246
  // （8.6% 平顶）；同日落另一张（17:31）`aeEv` 0.55、p95 216（0% 平顶）。
  // 4.8EV ≈ 243 LSB 才是"还能分辨"的上限。楼暗由长帧过曝补（0.12.63），不靠这里。
  (void)bracket_has_headroom;
  in.white_ev = kAeDisplayClipEv;
  // v0.11.88：bracket 下**永远**跑高光约束 —— 旧逻辑在"真裁切 0"时把整条判据关掉，
  // 于是 aeEv 一路冲到 +2.0 限幅、白墙/窗外撞白（真机 2712：hi240 3.97%）。
  in.force_hl = bracket_metering;
  in.manual_meter = meter_manual_;  // v0.12.67：用户接管测光 ⇒ 渲染端不补中灰
  in.p_hi95 = st_full.p_hi95;
  // 0.12.52：BLOCK「一套」——RAW 域鲁棒档不再参与决策（显示域闭环是唯一来源）。
  // `p_robust` 仍算、仍进日志，供离线核对统计量，但不影响 EV。
  in.p_robust = st_full.p_robust;
  in.robust = false;
  ae_p_robust_ = st_full.p_robust;
  ae_robust_on_ = in.robust;
  ae_white_ev_ = in.white_ev;
  ae_sat_raw_ = st_full.sat_frac;
  const AeDecision d = decideAe(in);
  ae_p50_ = st_center.p_mid;
  ae_p999_ = st_full.p_hi999;   // 诊断：P99.9 仍记录（判据用的是 P99.5）
  ae_p995_ = st_full.p_hi995;
  ae_p95_ = st_full.p_hi95;
  ae_sat_frac_ = in.sat_frac;
  ae_hl_soft_ev_ = d.ev_hl_soft;
  ae_mid_ev_ = d.ev_mid;
  ae_hl_ev_ = d.ev_hl;
  ae_ev_ = d.ev;
  // why → 数字（日志紧凑）：1 mid / 2 highlight / 3 clamped / 4 invalid / 0 none
  if (d.why[0] == 'i') {
    ae_why_ = 4;
  } else if (std::strchr(d.why, '_') != nullptr) {
    ae_why_ = 3;
  } else if (d.why[0] == 'h') {
    ae_why_ = 2;
  } else if (d.why[0] == 'm') {
    ae_why_ = 1;
  }
  // ── 0.12.51：显示域闭环优先 ────────────────────────────────────────────
  // 预览侧已经按**显示域中位 → 目标 0.18** 把 EV 积分收敛（见 submitFrame 末尾），
  // 预览与成片共用这个 EMA ⇒ 成片第一张就拿到正确值。这里只叠高光兜底，
  // 不再用 RAW 域 ev_mid 覆盖（两套目标会互相拉扯，正是此前反复调 target 的原因）。
  if (aeDisplay() && g_pv_disp_p50 > 0.005f) {
    const float hl = std::min(d.ev_hl, d.ev_hl_soft);
    const float ev = std::min(g_disp_ae_ev, hl);
    ae_ev_ = ev;
    ae_why_ = 5;   // 5 = display（显示域闭环）
    return ev;
  }
  return d.ev;
}

void FrameRenderer::setDiagStats(bool on) { g_diag_stats.store(on); }

void FrameRenderer::setNnInferFn(NnInferFn fn) { nn_infer_slot() = fn; }

NnInferFn FrameRenderer::nnInferFn() { return nn_infer_slot(); }

void FrameRenderer::setNnInfoFn(NnInfoFn fn) { nn_info_slot() = fn; }

NnInfoFn FrameRenderer::nnInfoFn() { return nn_info_slot(); }

bool FrameRenderer::submitFrame(const float* st, int rot, float sx, float sy,
                                int dst_w, int dst_h, const FilmParams& p,
                                bool fill_test) {
  if (dst_w <= 0 || dst_h <= 0) return false;
  frames_.fetch_add(1, std::memory_order_relaxed);
  // E4-3 颗粒：预览路径的种子**恒 0** ⇒ 图案不随帧变化。旧实现用 uTime =
  // frames_ % 512，颗粒每帧重掷 = 取景时满屏闪烁（设计文档 §3.1.6 方案 A 的缺陷）。
  grain_seed_ = 0.0f;

  // 预览降载（v0.10.93）：pass1/2/3 与后处理（局部色调映射、直方图统计、
  // filmic 统计）全按缩放后尺寸跑，成本随像素数下降（0.5 → 1/4）。显示不变：
  // pass3 渲到低分辨率 RGBA8（OETF 后 display 域，8bit 无截断），再 blit 线性
  // 升到全窗口 —— 16F 的 NEAREST 放大会出像素块，RGBA8 才有 LINEAR。
  // scale=1 时走原路径（零改动零回归）；成片链不经此函数。
  const float s = previewScale();
  const int rw = std::max(1, static_cast<int>(std::lround(dst_w * s)));
  const int rh = std::max(1, static_cast<int>(std::lround(dst_h * s)));
  const bool scaled = rw != dst_w || rh != dst_h;
  if (!ensureTargets(rw, rh)) return false;

  // 吞掉上一帧/诊断残留的 GL 错误，保证诊断只反映本帧问题
  while (glGetError() != GL_NO_ERROR) {
  }
  glDisable(GL_BLEND);
  glDisable(GL_DEPTH_TEST);
  const bool diag = frames_.load(std::memory_order_relaxed) == 1;

  if (fill_test) {
    passFill(rw, rh);
    if (diag) logPassDiag("pass1_fill", fbo_scene_, GL_RGBA16F);
  } else {
    passOES(rw, rh, st, rot, sx, sy, p);
    if (diag) logPassDiag("pass1_oes", fbo_scene_, GL_RGBA16F);
  }
  // 局部影调（tone equalizer）：在 film 曲线之前按 mask 分区做曝光补偿
  const GLuint teq_src = applyToneEq(rw, rh, tex_scene_);
  {
    // v0.11.93：预览叠上成片 AE 的 EMA ⇒ 取景器看到的亮度就是成片亮度（WYSIWYG）
    FilmParams pv = p;
    // v0.12.71：同上，预览不叠渲染端 AE。
    passFilm(rw, rh, pv, teq_src, /*still=*/false, /*hal_domain=*/true);
  }
  if (diag) logPassDiag("pass2_film", fbo_work_, GL_RGBA16F);

  if (scaled) {
    // 降档：pass3 在低分辨率 RGBA8 上完成，blit 升到窗口。blit 建不出/不完整
    // （罕见）则整帧退回全尺寸重渲 —— 取景正确性优先于省电。
    if (ensureOutScaleTarget(rw, rh)) {
      passOutput(rw, rh, p, false, true);  // 渲到 fbo_out_s_（passOutput 不改绑定）
      glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo_out_s_);
      glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
      glBlitFramebuffer(0, 0, rw, rh, 0, 0, dst_w, dst_h,
                        GL_COLOR_BUFFER_BIT, GL_LINEAR);
      glBindFramebuffer(GL_FRAMEBUFFER, 0);
      return true;
    }
    if (!ensureTargets(dst_w, dst_h)) return false;
    if (fill_test) {
      passFill(dst_w, dst_h);
    } else {
      passOES(dst_w, dst_h, st, rot, sx, sy, p);
    }
    const GLuint teq_src = applyToneEq(dst_w, dst_h, tex_scene_);
    {
      FilmParams pv = p;
      // v0.12.71：预览不叠任何渲染端 AE —— 曝光 = HAL + planner + baseEv，取景器即成片。
      passFilm(dst_w, dst_h, pv, teq_src, /*still=*/false, /*hal_domain=*/true);
    }
  }

  // 必须显式绑回窗口默认帧缓冲：上一 pass 留下的绑定是 fbo_work_
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  passOutput(dst_w, dst_h, p, false, true);  // 预览：允许过曝斑马纹
  if (diag) logPassDiag("pass3_out", 0, kOutputGlInternalFormat);
  // ── 0.12.51：显示域 AE 闭环（首张生效的关键）────────────────────────────
  // 预览每 10 帧量一次**最终显示输出**（tex_cap_，sRGB 编码）的中位，按目标 0.18
  // （线性）积分 EV 到 g_still_ae_ema。预览与成片共用这个 EMA（见 passFilm 的
  // `pv.exposure_ev += g_still_ae_ema`）⇒ 用户构图时闭环已经收敛，按下快门那一刻
  // 成片拿到的就是收敛后的 EV，**第一张就是对的**，不需要"拍完再修正"。
  if (aeHal()) {
    // ⚠ 0.12.52：预览的 passOutput 直接画到默认帧缓冲（屏幕），**没有中间纹理**；
    // 0.12.51 去读 tex_cap_ 读到的是空内容 ⇒ `aeDispP50` 恒 0、闭环从未生效。
    // 改法 = 直接读屏幕中心 64×64（所见即所得，也就是用户看到的那块画面）。
    // 0.12.53：节流 15 帧 → **5 帧**（@60fps ≈ 80ms/步）。15 帧 + 0.15 步长要从 0 爬到
    // 2EV 需要 3~5 秒，用户当场质疑"PC 也要这么久吗"——不应该，HAL 的 AE 是几百毫秒
    // 级收敛。这里把采样加密、增益提到 0.7，典型 1.2EV 缺口 3 步内到位（≈0.25s）。
    if ((++disp_tick_ % 5) == 0 && dst_w >= 64 && dst_h >= 64) {
      unsigned char px[64 * 64 * 4];
      glReadPixels(dst_w / 2 - 32, dst_h / 2 - 32, 64, 64, GL_RGBA,
                   GL_UNSIGNED_BYTE, px);
      if (glGetError() == GL_NO_ERROR) {
        // 加权对数域直方图 + 截尾 25%~75%：与 PhotonCamera `MeteringSystem` 的
        // `robustMidToneLinearLuma` 同口径（MIDTONE_TRIM 0.25/0.75 + 中心高斯权重）。
        // 这才是"一套"：统计对象是**显示域**，统计方式是**鲁棒中灰**，目标是 0.18。
        constexpr int kBins = 64;
        double bins[kBins] = {0.0};
        double wsum = 0.0;
        for (int y = 0; y < 64; ++y) {
          for (int x = 0; x < 64; ++x) {
            const size_t i = (static_cast<size_t>(y) * 64 + x) * 4;
            const float sr = px[i] / 255.0f, sg = px[i + 1] / 255.0f, sb = px[i + 2] / 255.0f;
            const float luma = 0.2126f * sr + 0.7152f * sg + 0.0722f * sb;
            const float lin = luma <= 0.04045f ? luma / 12.92f
                                               : std::pow((luma + 0.055f) / 1.055f, 2.4f);
            const float dx = (x + 0.5f - 32.0f) / 32.0f;
            const float dy = (y + 0.5f - 32.0f) / 32.0f;
            const float w = 0.35f + 0.65f * std::exp(-0.5f * (dx * dx + dy * dy) / 0.1024f);
            // 对数域 [-12, 8] → [0, kBins-1]（与 measureSceneStats 的编码同区间）
            const float enc = (std::log2(std::max(lin, 1e-4f)) + 12.0f) / 20.0f;
            const int bi = std::clamp(static_cast<int>(enc * (kBins - 1)), 0, kBins - 1);
            bins[bi] += w;
            wsum += w;
          }
        }
        if (wsum > 0.0) {
          const double lo = wsum * 0.25, hi = wsum * 0.75;
          double cum = 0.0, acc = 0.0, n = 0.0;
          for (int i = 0; i < kBins; ++i) {
            if (bins[i] <= 0.0) continue;
            const double a = cum, b = cum + bins[i];
            cum = b;
            const double s = std::max(a, lo), e = std::min(b, hi);
            if (e <= s) continue;
            acc += (e - s) * (static_cast<double>(i) / (kBins - 1) * 20.0 - 12.0);
            n += e - s;
          }
          const float mid = n > 0.0 ? std::exp2(static_cast<float>(acc / n)) : 0.0f;
          // 0.12.54：EMA 0.7/0.3 → 0.85/0.15。旧 EMA 让读数滞后约 3 步，而增益是 0.7
          // —— "用旧读数猛推"必然过冲（真机室内 14:36~14:37：p50 卡在 0.17 而 ev
          // 一路涨到 2.0、mean 185）。测量滞后是振荡的根因，不是增益本身。
          g_pv_disp_p50 = g_pv_disp_p50 > 0.001f
                              ? g_pv_disp_p50 * 0.85f + mid * 0.15f
                              : mid;
          // 0.12.56：**这里不再改 EV**。统计值只供 Kotlin 外环读取（1Hz、0.3EV 步长、
          // 下发 HAL 的 EV 补偿）。渲染端碰 EV = 和 HAL 打架，这是已证明的结论。
        }
      }
    }
  }
  return true;
}

// （拍照走 submitStill 全分辨率路径；captureLastFrame 已移除）

// ── U-3（2026-10-11）：[88] temp_mired / [89] tint 在**拍照链路**折成 RGB 增益 ──
// 与编辑器链路 `passStyle` 的 `wb_tot` **同一算法、同一函数**（`tempTintGain`，
// ADR-14 裁决 1）：两者同属线性域增益 ⇒ 同一个乘性操作 ⇒ 并入已有的成对往返，
// **零额外 GPU 成本、零额外量化误差**（架构师定案 `preset-DESIGN.md` §9.3）。
// 三个消费点全部走这里：`wbCompGNorm`（OES 预览 / YUV 静拍）+ `prepareRawCommon`
// 的两个调用点（RAW 单帧 / MFNR 成片）—— 漏一处的后果正是"预览有色偏、成片没有"。
// 恒等性（硬判据，逐位）：[88]=[89]=0 时**整个 if 分支不执行** ⇒ out 就是 [1..3] 的
// 原值（不是"乘 1.0 得近似值"）⇒ 三条链的输出与接线前逐位一致。
static void wbTempTint(const FilmParams& p, float out[3]) {
  out[0] = p.wb_r;
  out[1] = p.wb_g;
  out[2] = p.wb_b;
  if (p.temp_mired != 0.0f || p.tint != 0.0f) {
    float tt[3];
    tempTintGain(p.temp_mired, p.tint, tt);
    out[0] *= tt[0];
    // tt[1] 恒 1.0f（`tempTintGain` 的 G 通道不动）—— 保留这次乘法是为了与
    // passStyle 的 `wb_tot` **逐字同式**（两侧算法一致性靠文本可比对维持，见报告）。
    out[1] *= tt[1];
    out[2] *= tt[2];
  }
}

// ── 第 2 期 S13：三条链的 WB 统一 **G 归一**口径 ──────────────────────────────
// RAW 成片链在 prepareRawCommon 里以 (wb_r/wb_g, 1, wb_b/wb_g) 乘在 asShotNeutral
// 之上；而 OES 预览 / YUV 静拍此前把原始 (wb_r, wb_g, wb_b) 直接乘进 pass1 的线性值
// ——**没有除 wb_g**。wb_g ≠ 1 时这两条链的整体亮度会被 wb_g 缩放（RAW 成片不会），
// 静拍侧当时也**不做域比归一**（域标志被 `still` 兼任）⇒ 亮度随 WB 的 G 分量漂移。
// 【第 6 期已修】域标志独立为 `hal_domain`：YUV 静拍链与预览链同走域比归一
//（uHalDomainMap），且域比的在线标定也只认 RAW 静拍帧（详见 passFilm 内注释）。
// 逐通道防呆与 prepareRawCommon 完全同口径：该通道 WB 补偿非法（≤0）时取 1（不动）。
// U-3：[88][89] 先并入 wbTempTint，再走本口径 —— `tempTintGain` 的输出本身就是
// "R/B 相对 G 的增益"（G 恒 1）⇒ 与 G 归一**同一口径**，不需要额外换算。
static void wbCompGNorm(const FilmParams& p, float out[3]) {
  float wb[3];
  wbTempTint(p, wb);   // U-3：[1..3] × [88][89]（[88]=[89]=0 时逐位 = [1..3]）
  const bool ok_g = p.wb_g > 1e-6f;
  out[0] = (ok_g && wb[0] > 1e-6f) ? wb[0] / p.wb_g : 1.0f;
  out[1] = 1.0f;
  out[2] = (ok_g && wb[2] > 1e-6f) ? wb[2] / p.wb_g : 1.0f;
}

// ── U-4（2026-10-11）：清晰度环半径的**唯一维护点**（三条调用链共用）───────────
// 半径 = max(1px, 0.5% × 输出短边)：**相对短边**标定 ⇒ 预览与全分辨率导出的观感一致
//（固定像素半径在两种分辨率下会差出数倍）。
// 消费点三处：passStyle（编辑器 / 查看页「胶片」）、passOutput（拍照预览 + 成片）、
// 以及 finishStillChain 的 raw.render 诊断字段 clarR —— "实际下发的半径"必须与下发值
// **同源**，否则日志会撒谎（check-diag-fields.py 记的那一类静默失效）。
// ⚠️ w/h 是**输出**尺寸（E2-12 起 = 裁剪后的尺寸），不是传感器/源尺寸。
static float clarityStepPx(int w, int h) {
  return std::max(1.0f, 0.005f * static_cast<float>(std::min(w, h)));
}

void FrameRenderer::passOES(int dst_w, int dst_h, const float* st, int rot,
                            float sx, float sy, const FilmParams& p) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_scene_);
  glViewport(0, 0, dst_w, dst_h);
  glUseProgram(prog_oes_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_EXTERNAL_OES, oes_tex_);
  glUniformMatrix4fv(u_oes_.st, 1, GL_FALSE, st);
  glUniform1i(u_oes_.rot, rot);
  glUniform2f(u_oes_.scale, sx, sy);
  glUniform1i(u_oes_.tex, 0);
  float wb[3];
  wbCompGNorm(p, wb);   // S13：G 归一（与 RAW 链同口径，不再直接下发 wb_g）
  glUniform3f(u_oes_.wb, wb[0], wb[1], wb[2]);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// 静拍 pass1：采样 CPU 转换后的 RGB888 全分辨率纹理
void FrameRenderer::passStill(int dst_w, int dst_h, int rot,
                              const FilmParams& p) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_scene_);
  glViewport(0, 0, dst_w, dst_h);
  glUseProgram(prog_still_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, still_tex_);
  glUniform1i(u_still_.tex, 0);
  glUniform1i(u_still_.rot, rot);
  glUniform2f(u_still_.scale, 1.0f, 1.0f);  // 静拍不裁剪，整幅输出
  float wb[3];
  wbCompGNorm(p, wb);   // S13：G 归一（与 RAW 链同口径，不再直接下发 wb_g）
  glUniform3f(u_still_.wb, wb[0], wb[1], wb[2]);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// 全分辨率静拍：CPU YUV→RGB888（BT.601 limited）→ RGB8 上传 → 胶片链 → 读回。
// 单帧约 30–60ms CPU 转换（12.6MP），拍照场景可接受；NEON 优化记 M3。
bool FrameRenderer::submitStill(const void* y, const void* u, const void* v,
                                int w, int h, int y_stride, int uv_stride,
                                int uv_pix, int rotation, const FilmParams& p,
                                std::vector<uint8_t>* out_rgba, bool ten_bit) {
  if (w <= 0 || h <= 0 || !y || !u) return false;
  // 卡 H2：10bit 交付前先跑**一次**读回自检（进程级缓存）。失败 ⇒ 拒绝本次 10bit
  // 交付 ⇒ 调用方按回退链降级（照片优先红线：宁可回退 JPEG，不交付错位的数据）。
  if (ten_bit && !selfCheck10BitOnce()) return false;
  frames_.fetch_add(1, std::memory_order_relaxed);

  if (still_w_ != w || still_h_ != h) {
    if (still_tex_) glDeleteTextures(1, &still_tex_);
    still_tex_ = 0;
    glGenTextures(1, &still_tex_);
    glBindTexture(GL_TEXTURE_2D, still_tex_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB8, w, h, 0, GL_RGB, GL_UNSIGNED_BYTE,
                 nullptr);
    still_w_ = w;
    still_h_ = h;
    rgb_stage_.resize(static_cast<size_t>(w) * h * 3);
  }

  // CPU YUV→RGB（limited range 展开）。矩阵可选 BT.601（默认，旧行为）/
  // BT.709（新平台 HAL 常用）——错配时高饱和区 G 被低估 → 偏品红，
  // 设置页开关实拍 A/B 定夺
  const auto* yp = static_cast<const uint8_t*>(y);
  const auto* up = static_cast<const uint8_t*>(u);
  const auto* vp = static_cast<const uint8_t*>(v);
  const int cw = w / 2;
  // 归一化色度（±0.5）系数：R←Cr、G←Cb/Cr、B←Cb
  const float kRCr = yuv_matrix_ == 1 ? 1.793f : 1.596f;
  const float kGCb = yuv_matrix_ == 1 ? 0.213f : 0.391f;
  const float kGCr = yuv_matrix_ == 1 ? 0.533f : 0.813f;
  const float kBCb = yuv_matrix_ == 1 ? 2.112f : 2.017f;
  uint8_t* dst = rgb_stage_.data();
  for (int r = 0; r < h; ++r) {
    const uint8_t* yrow = yp + static_cast<size_t>(r) * y_stride;
    const uint8_t* urow = up + static_cast<size_t>(r / 2) * uv_stride;
    const uint8_t* vrow = v ? vp + static_cast<size_t>(r / 2) * uv_stride
                            : urow + 1;  // 半平面 V 在 U+1
    uint8_t* orow = dst + static_cast<size_t>(r) * w * 3;
    for (int c = 0; c < w; ++c) {
      // Y 必须先归一化到 [0,1] 再减 16 黑阶——若用原始 0–255 值，
      // 亮度比色度大 255 倍，色度可忽略 → 灰度照片（X300 实测事故）
      const float yv = (yrow[c] / 255.0f) * 1.164f - 0.0625f;
      const int cc = c >> (uv_pix >= 2 ? 1 : 0);
      const float cb = (uv_pix >= 2 ? urow[cc * uv_pix] : urow[cc]) / 255.0f - 0.5f;
      const float cr = (uv_pix >= 2 ? vrow[cc * uv_pix] : vrow[cc]) / 255.0f - 0.5f;
      float R = (yv + kRCr * cr) * 255.0f;
      float G = (yv - kGCb * cb - kGCr * cr) * 255.0f;
      float B = (yv + kBCb * cb) * 255.0f;
      orow[c * 3] = static_cast<uint8_t>(R < 0 ? 0 : R > 255 ? 255 : R);
      orow[c * 3 + 1] = static_cast<uint8_t>(G < 0 ? 0 : G > 255 ? 255 : G);
      orow[c * 3 + 2] = static_cast<uint8_t>(B < 0 ? 0 : B > 255 ? 255 : B);
    }
  }

  glBindTexture(GL_TEXTURE_2D, still_tex_);
  glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
  glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGB, GL_UNSIGNED_BYTE,
                  rgb_stage_.data());
  glPixelStorei(GL_UNPACK_ALIGNMENT, 4);

  // 实际渲染旋转 = 界面方向 + 传感器旋转（CPU 帧为传感器原始方向）。
  // 输出尺寸的横竖交换必须用同一旋转值判断——否则内容旋转了、画布没换向，
  // 竖向内容被塞进横画布 → 压扁（X300 实测事故）。
  const int effective_rot = (rotation + 1) % 4;
  const bool swap_wh = (effective_rot % 2) == 1;
  const int dst_w = swap_wh ? h : w;
  const int dst_h = swap_wh ? w : h;
  if (!ensureTargets(dst_w, dst_h)) return false;
  while (glGetError() != GL_NO_ERROR) {
  }

  passStill(dst_w, dst_h, effective_rot, p);
  // 第 6 期（S12）：YUV 静拍链的输入同样是 HAL 显示域 ⇒ 必须走域比归一。
  // 旧写法传 still=true 使它拿到 hal_domain=0，被当作场景线性域（亮度随 AWB 漂移）。
  const GLuint teq_src = applyToneEq(dst_w, dst_h, tex_scene_);
  passFilm(dst_w, dst_h, p, teq_src, /*still=*/true, /*hal_domain=*/true);

  // 卡 H2：**交付档切换**（8bit RGBA8 ↔ 10bit GL_RGB10_A2）也要重建 —— 只按尺寸
  // 记账会让"同尺寸的 JPEG 与 HEIF 连拍"复用错档附件（readback 口径不匹配 ⇒ 整幅
  // 错色且不报错）。cap_ten_bit_ 与 cap_w_/cap_h_ 同源记账。
  if (cap_w_ != dst_w || cap_h_ != dst_h || cap_ten_bit_ != ten_bit) {
    if (tex_cap_) {
      glDeleteTextures(1, &tex_cap_);
      glDeleteFramebuffers(1, &fbo_cap_);
    }
    tex_cap_ = fbo_cap_ = 0;
    // @domain: D5 —— 成片输出（ten_bit ? GL_RGB10_A2 : RGBA8，见 OutputBitDepth.h）
    makeTex(&tex_cap_, &fbo_cap_, outputInternalOf(ten_bit), dst_w, dst_h);
    cap_w_ = dst_w;
    cap_h_ = dst_h;
    cap_ten_bit_ = ten_bit;
  }
  // E4-3 颗粒：成片种子 = f(输出尺寸, 输入内容指纹) ⇒ 同一张两次导出**逐位相同**、
  // 不同照片不同图案（验收判据 6）。预览/编辑器路径各自置 0（图案稳定不闪）。
  // 指纹源 = rgb_stage_（CPU 转换后的 RGB8），与输出尺寸一起决定图案的起始相位。
  grain_seed_ = grainSeedFrom(
      grainFingerprint(rgb_stage_.data(), rgb_stage_.size()), dst_w, dst_h);
  // E4-5b（R1）：成片基准短边（同 finishStillChain 的同名段）—— **两条成片路径
  // （YUV 静拍 = 本函数 / RAW 静拍 = finishStillChain）都要设**，否则本路径的成片
  // shift ≠ 0：光晕半径与预览对不上，且会改变成片观感（本卡承诺"成片逐位不变"）。
  halo_ref_min_ = static_cast<float>(std::min(dst_w, dst_h));
  // 成片：斑马纹绝不进成片；dither 除数按交付档重标（255 / 1023）
  passOutput(dst_w, dst_h, p, true, false, outputMaxOf(ten_bit));
  logPassDiag("pass3_still", fbo_cap_, outputInternalOf(ten_bit));
  // 10bit 档每像素仍是 4 B（packed uint32，GL_UNSIGNED_INT_2_10_10_10_REV）
  // ⇒ 缓冲尺寸与 8bit 完全相同；解包在 Kotlin 侧（Heif10Pipeline.unpackRgb10）。
  out_rgba->resize(static_cast<size_t>(dst_w) * dst_h * 4);
  glReadPixels(0, 0, dst_w, dst_h, outputReadFormatOf(ten_bit),
               outputReadTypeOf(ten_bit), out_rgba->data());
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  return true;
}

// ── E1-3 编辑器渲染通路（2026-10-08）────────────────────────────────────────
// 已保存的成片（RGBA8，**显示域 sRGB，已过 OETF**）→ 只重跑 OETF 之后的三段
// （3D LUT → 颗粒 → TPDF dither）→ RGBA8 读回。见 kFsStyle 的域约定注释。
// ⚠ 绝不调 passFilm/passStill（submitStill 那条路会对成片再跑一遍 filmic + 域比
//   归一 ⇒ 双重影调映射，画面显著变暗变灰 —— 可行性报告 R1，本卡头号风险）；
//   也绝不插 OETF/EOTF（ADR-7）。
// 输入行序 = Bitmap 顶行在前：上传后顶行在纹理 t=0，屏幕底行取 t=0 ⇒ glReadPixels
// 第 0 行（屏幕底行）= 图像顶行 ⇒ 与 Bitmap 行序恒等，**不翻转**（kFsStyle 无 uFlipY）。
// ⚠ 别照抄 kFsOut 的 uFlipY：那一次翻转是抵消 kFsStill 自己的预翻转（见 kFsStyle 注释
//   的 ②），本通路不经 kFsStill，加了反而上下颠倒。
// 只依赖 still_tex_ + fbo_cap_：**不调 ensureTargets** —— 那条路会分配两张全分辨率
// RGBA16F（4080×3072 ≈ 400MB），而本 pass 一个中间纹理都不需要（报告 U7 内存红线）。
bool FrameRenderer::submitDisplay(const void* rgba8, int w, int h, int out_w,
                                  int out_h, const float crop_uv[4],
                                  int crop_rot, const FilmParams& p,
                                  std::vector<uint8_t>* out_rgba) {
  // E4-3 颗粒：编辑器链路的种子**恒 0** ⇒ 预览稳定（不随帧变）+ 导出可复现
  // （报告 R3 / 验收判据 6）。"成片每张不同"由 passOutput 的内容指纹承担。
  grain_seed_ = 0.0f;
  const bool prog_ok = prog_style_ != 0;
  bool ran = false;
  // E2-12：输出尺寸缺省（≤0）⇒ 与输入同尺寸（不裁剪）。w/h 始终 = 输入位图尺寸，
  // out_w/out_h = 输出尺寸（裁剪改变宽高比时二者不同，由 Kotlin 侧算好）。
  if (out_w <= 0 || out_h <= 0) {
    out_w = w;
    out_h = h;
  }
  // ── E1-5（2026-10-08 P0「真机编辑器打开全黑」）诊断 ──────────────────────
  // 真机机会宝贵 ⇒ 一次运行必须能定位到**哪一环**断：
  //   输入就黑            ⇒ Kotlin 上传侧（Bitmap→direct buffer）
  //   输入非黑、读回黑    ⇒ 本函数内的纹理 / FBO / draw / 读回
  // 采样统计（≤4096 点，成本可忽略）：判"是不是全黑"不需要精确均值。
  // ⚠ 字段在此声明，**日志统一发在 passStyle 之后**：tools/check-diag-fields.py
  //   要求字段行晚于本函数内的 passStyle 调用，否则"没执行却报执行"。
  int in_mean = -1, in_p0 = -1, out_mean = -1, out_p0 = -1;
  int tex_new = 0, tex_ifmt = 0, cap_ifmt = 0, tex_w = -1, tex_h = -1;
  GLenum e_tex = 0, e_sub = 0, e_fbo = 0, e_pass = 0, e_read = 0;
  GLenum fb_status = 0;
  if (rgba8 != nullptr && w > 0 && h > 0 && out_w > 0 && out_h > 0 &&
      out_rgba != nullptr) {
    const auto* px = static_cast<const uint8_t*>(rgba8);
    const size_t n = static_cast<size_t>(w) * h;
    const size_t step = n > 4096 ? n / 4096 : 1;
    int64_t sum = 0;
    size_t cnt = 0;
    for (size_t i = 0; i < n; i += step) {
      sum += px[i * 4];  // R 通道采样（全黑 ⇒ 0）
      ++cnt;
    }
    in_mean = cnt ? static_cast<int>(sum / static_cast<int64_t>(cnt)) : -1;
    in_p0 = px[0];
  }
  if (prog_ok && rgba8 != nullptr && out_rgba != nullptr && w > 0 && h > 0 &&
      out_w > 0 && out_h > 0) {
    while (glGetError() != GL_NO_ERROR) {
    }  // 入口清队列：下面各步的错误码才能归属本函数
    // 输入纹理：**编辑器自己的 RGBA8 纹理**（E1-5 修复，原复用 still_tex_）。
    // 两条理由：
    //  ① 内部格式必须与上传格式一致（RGBA8 ← GL_RGBA/GL_UNSIGNED_BYTE）。旧写法
    //     GL_RGB8 + GL_RGBA 上传是"内部/外部格式不匹配"：规范允许（alpha 丢弃），
    //     但部分驱动拒绝或写入未定义内容 ⇒ 采样恒 0 ⇒ 全黑。
    //  ② still_tex_ 与 submitStill（YUV 拍照）共享 ⇒ 编辑器不碰它，拍照链路
    //     （含 still_w_/still_h_ 记账与 RGB8 格式）逐位不变。
    if (edit_w_ != w || edit_h_ != h) {
      if (edit_tex_) glDeleteTextures(1, &edit_tex_);
      edit_tex_ = 0;
      glGenTextures(1, &edit_tex_);
      glBindTexture(GL_TEXTURE_2D, edit_tex_);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
      glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA,
                   GL_UNSIGNED_BYTE, nullptr);
      edit_w_ = w;
      edit_h_ = h;
      tex_new = 1;
    }
    e_tex = glGetError();  // 纹理创建（glTexImage2D）后
    glBindTexture(GL_TEXTURE_2D, edit_tex_);
    // 实际内部格式/尺寸：① 的直接判据（若拿到 GL_RGB8/0x8051 就是格式不匹配；
    // 尺寸 ≠ w/h ⇒ 纹理记账错位，采样会整幅偏移到纹理外 → clamp 到边缘）
    glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT,
                             &tex_ifmt);
    glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH, &tex_w);
    glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT, &tex_h);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE,
                    rgba8);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    e_sub = glGetError();  // 上传后（① 的直接判据：非 0 ⇒ 驱动拒绝了这次上传）

    // 输出目标：显示域 RGBA8（与成片同格式；dither 在 8bit 量化前生效）
    // E2-12：**输出**尺寸（裁剪后）—— 与输入纹理尺寸解耦；不裁剪时两者相同。
    // 卡 H2：编辑器链路**恒 8bit**（JPEG 交付）⇒ 若 tex_cap_ 还是 10bit 档的附件
    // （上一次 HEIF 拍照留下的，尺寸可能正好相同）必须重建：按 8bit 口径读一个
    // GL_RGB10_A2 附件是非法组合 ⇒ 编辑器导出整幅错色且不报错。
    if (cap_w_ != out_w || cap_h_ != out_h || cap_ten_bit_) {
      if (tex_cap_) {
        glDeleteTextures(1, &tex_cap_);
        glDeleteFramebuffers(1, &fbo_cap_);
      }
      tex_cap_ = fbo_cap_ = 0;
      // @domain: D5 —— 编辑器输出（OETF 后显示域，LUT/颗粒/dither 之后）
      makeTex(&tex_cap_, &fbo_cap_, kOutputGlInternalFormat, out_w, out_h);
      cap_w_ = out_w;
      cap_h_ = out_h;
      cap_ten_bit_ = false;
    }
    // FBO 不完整时 draw 被驱动静默丢弃（X300 实测：RGBA8 + HALF_FLOAT 非法组合
    // ⇒ 输出全黑且 glErr=0），必须显式查一次
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_cap_);
    fb_status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    e_fbo = glGetError();
    // 输出目标的实际内部格式（FBO 参数；tex_cap_ 由 makeTex 建，与本 FBO 同生共死）
    glBindTexture(GL_TEXTURE_2D, tex_cap_);
    glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT,
                             &cap_ifmt);
    glBindTexture(GL_TEXTURE_2D, edit_tex_);  // 恢复（passStyle 里还会再绑一次）
    if (fb_status == GL_FRAMEBUFFER_COMPLETE) {
      while (glGetError() != GL_NO_ERROR) {
      }
      passStyle(out_w, out_h, crop_uv, crop_rot, p);
      // ⚠ 必须在 logPassDiag 之前取：它内部会消费掉错误码
      e_pass = glGetError();
      logPassDiag("style", fbo_cap_, kOutputGlInternalFormat);
      out_rgba->resize(static_cast<size_t>(out_w) * out_h * 4);
      glReadPixels(0, 0, out_w, out_h, kOutputGlReadFormat, kOutputGlReadType,
                   out_rgba->data());
      e_read = glGetError();
      // 输出统计：readback 失败时 resize 的零填充会让输出看起来"全黑" ⇒ 与
      // "draw 真的产出黑"必须靠 eRead / ePass 与 logPassDiag 的 (0,0) 像素交叉判读
      // （outP0 与 render.diag 的 r 同位置：glReadPixels 第 0 行第 0 列）。
      {
        const auto* op = out_rgba->data();
        const size_t on = static_cast<size_t>(out_w) * out_h;
        const size_t ostep = on > 4096 ? on / 4096 : 1;
        int64_t osum = 0;
        size_t ocnt = 0;
        for (size_t i = 0; i < on; i += ostep) {
          osum += op[i * 4];
          ++ocnt;
        }
        out_mean = ocnt ? static_cast<int>(osum / static_cast<int64_t>(ocnt)) : -1;
        out_p0 = op[0];
      }
      glBindFramebuffer(GL_FRAMEBUFFER, 0);
      ran = true;
    } else {
      Logger::instance().log(
          LogLevel::kError, "engine", "render.fbo_incomplete",
          "{\"stage\":\"style\",\"w\":" + std::to_string(w) +
              ",\"h\":" + std::to_string(h) +
              ",\"outW\":" + std::to_string(out_w) +
              ",\"outH\":" + std::to_string(out_h) + "}");
    }
  }
  // ── E1-5（2026-10-08 P0「真机编辑器打开全黑」）一次定位诊断 ────────────────
  // 判读表（用户跑一次真机即可定位）：
  //   inMean/inP0 ≈ 0        ⇒ 输入就是黑的：问题在 Kotlin 上传侧（Bitmap→buffer）
  //   eTex/eSub != 0         ⇒ 纹理创建/上传被驱动拒绝（内部格式不匹配 → 见 texIfmt）
  //   texIfmt = 0x8051(32849)⇒ 拿到的是 GL_RGB8，而上传是 RGBA（① 复现）
  //   fbStatus != 0x8CD5     ⇒ FBO 不完整 ⇒ draw 被整帧丢弃（输出保持未初始化）
  //   ePass != 0             ⇒ draw 本身报错（uniform/采样器问题）
  //   eRead != 0             ⇒ glReadPixels 失败
  //   inMean 正常 + outMean≈0 + ePass=eRead=0 ⇒ 只有 render.diag 的 (0,0) 像素能区分
  //                            "draw 产出黑"与"读回黑"（两者同位置，逐字节可比）
  std::string kv_diag = std::string("{\"inMean\":") + std::to_string(in_mean);
  kv_diag += ",\"inP0\":" + std::to_string(in_p0);
  kv_diag += ",\"texNew\":" + std::to_string(tex_new);
  kv_diag += ",\"texIfmt\":" + std::to_string(tex_ifmt);
  kv_diag += ",\"texW\":" + std::to_string(tex_w);
  kv_diag += ",\"texH\":" + std::to_string(tex_h);
  kv_diag += ",\"eTex\":" + std::to_string(e_tex);
  kv_diag += ",\"eSub\":" + std::to_string(e_sub);
  kv_diag += ",\"fbStatus\":" + std::to_string(fb_status);
  kv_diag += ",\"eFbo\":" + std::to_string(e_fbo);
  kv_diag += ",\"capIfmt\":" + std::to_string(cap_ifmt);
  kv_diag += ",\"ePass\":" + std::to_string(e_pass);
  kv_diag += ",\"eRead\":" + std::to_string(e_read);
  kv_diag += ",\"outMean\":" + std::to_string(out_mean);
  kv_diag += ",\"outP0\":" + std::to_string(out_p0);
  kv_diag += ",\"w\":" + std::to_string(w) + ",\"h\":" + std::to_string(h);
  // E2-12：输出尺寸 + 裁剪矩形 + 旋转（真机核对"裁剪是否真的进了引擎"）
  kv_diag += ",\"outW\":" + std::to_string(out_w) + ",\"outH\":" +
             std::to_string(out_h);
  kv_diag += ",\"cropRot\":" + std::to_string(crop_rot);
  kv_diag += ",\"crop\":[";
  for (int ci = 0; ci < 4; ++ci) {
    kv_diag += jnum(crop_uv ? crop_uv[ci] : (ci < 2 ? 0.0f : 1.0f), 5);
    kv_diag += ci < 3 ? "," : "]";
  }
  kv_diag += "}";
  Logger::instance().log(LogLevel::kInfo, "engine", "render.style_diag", kv_diag);

  // 诊断三件套（ADR §6.2）：shader 是否链接成功 / 本帧是否真的执行 / 下发参数值。
  // 一行发全（字段写在同一出口 ⇒ tools/check-diag-fields.py 能核对"字段—函数—前置调用"；
  // 历史事故：字段写进别的函数 ⇒ 真机日志缺项，整轮 A/B 白跑）。
  std::string kv = std::string("{\"styleOk\":") + (prog_ok ? "true" : "false");
  kv += ",\"styleOn\":";
  kv += ran ? "true" : "false";
  kv += ",\"lutK\":" + std::to_string(p.lut_strength);
  kv += ",\"grain\":" + std::to_string(p.grain_amount);
  kv += ",\"grainSz\":" + std::to_string(p.grain_size);
  // E4-3 颗粒（granularity）：生效值三件套 —— 密度调制参数 [60]、亮度枢轴 [61]、
  // 本帧实际下发的种子（编辑器链路恒 0；成片链路的同名字段发在 raw.render）。
  kv += ",\"grainMb\":" + std::to_string(p.grain_midtone_bias);
  kv += ",\"grainPivot\":" + std::to_string(p.grain_pivot);
  kv += ",\"grainSeed\":" + jnum(grain_seed_, 6);
  // ── E4-5 halation（光晕）：编辑器链路的**生效性三件套 + 四参数**────────────
  //  · haloOn = 本帧 halo 链**真的执行了**（0 且 [62] > 0 ⇒ 静默失败：program 缺失 /
  //    纹理分配失败 / FBO 不完整）；字段行晚于上面的 passStyle 调用（纪律同 grain）。
  //  · haloMs = CPU 侧提交耗时（真机性能判据之一，GPU 完成时间看成片 totalMs）。
  //  · haloStrength/haloThr/haloRadius/haloWarmth = 实际下发的四个参数生效值。
  kv += ",\"haloOn\":" + std::to_string(halo_on_);
  kv += ",\"haloMs\":" + jnum(halo_ms_, 7);
  kv += ",\"haloStrength\":" + jnum(p.halation_strength, 6);
  kv += ",\"haloThr\":" + jnum(p.halation_threshold, 6);
  kv += ",\"haloRadius\":" + jnum(p.halation_radius, 6);
  kv += ",\"haloWarmth\":" + jnum(p.halation_warmth, 6);
  // E4-5b（R1）：halo 半径归一化的**基准短边**（= 成片短边）。核对"预览与成片
  // 是否用同一基准"就靠它 —— 两侧同一进程内应恒等（成片调用会更新它）。
  kv += ",\"haloRef\":" + jnum(halo_ref_min_, 7);
  // ── SG-1 柔光（soft glow）：生效性 + 三参数 ────────────────────────────────
  //  · glowOn = 本帧柔光链**真的执行了**（0 且 [95] > 0 ⇒ 静默失败：program 缺失 /
  //    纹理分配失败 / FBO 不完整 —— 与 haloOn 同口径，真机一眼可辨）。
  //  · glowMs = CPU 侧提交耗时（含共享金字塔 ①②③ 与 1 个合成 pass；GPU 完成时间
  //    看成片 totalMs —— 与 haloMs 同口径）。
  //  · glowStrength/glowRadius/glowThr = 实际下发的三个参数生效值（[95][96][97]）。
  kv += ",\"glowOn\":" + std::to_string(glow_on_);
  kv += ",\"glowMs\":" + jnum(glow_ms_, 7);
  kv += ",\"glowStrength\":" + jnum(p.glow_strength, 6);
  kv += ",\"glowRadius\":" + jnum(p.glow_radius, 6);
  kv += ",\"glowThr\":" + jnum(p.glow_threshold, 6);
  // E2-2 基础调整：**下发参数值**（与上面三个同出口 —— ADR §6.2 要求"下发值 /
  // 链接成功 / 本帧执行"三件套写在同一个日志出口；check-diag-fields.py 会核对
  // 字段行必须晚于本函数内的 passStyle 调用）。
  kv += ",\"ev\":" + jnum(p.exposure_ev, 5);
  kv += ",\"wbOn\":";
  kv += (p.exposure_ev != 0.0f || p.wb_r != 1.0f || p.wb_g != 1.0f ||
         p.wb_b != 1.0f) ? "true" : "false";
  kv += ",\"wb\":[" + jnum(p.wb_r, 5) + "," + jnum(p.wb_g, 5) + "," +
        jnum(p.wb_b, 5) + "]";
  kv += ",\"ct\":" + jnum(p.contrast, 5);
  kv += ",\"sat\":" + jnum(p.sat_mid, 5);
  kv += ",\"clr\":" + jnum(p.clarity, 5);
  // E2-11：色温/色调 + 影调分区 + 褪色，同样进**同一个出口**。ttOn/toneOn 与
  // passStyle 内的门控同式重算；wbTot = 含 [88][89] 折算后的**实际**下发增益
  //（真机核对"色温滑杆是否真的进了引擎"靠它，而不是靠 [1..3] 的裸值）。
  {
    float tt[3] = {1.0f, 1.0f, 1.0f};
    if (p.temp_mired != 0.0f || p.tint != 0.0f) tempTintGain(p.temp_mired, p.tint, tt);
    kv += ",\"ttOn\":";
    kv += (p.temp_mired != 0.0f || p.tint != 0.0f) ? "true" : "false";
    kv += ",\"tMired\":" + jnum(p.temp_mired, 4);
    kv += ",\"tint\":" + jnum(p.tint, 4);
    kv += ",\"wbTot\":[" + jnum(p.wb_r * tt[0], 5) + "," +
          jnum(p.wb_g * tt[1], 5) + "," + jnum(p.wb_b * tt[2], 5) + "]";
    kv += ",\"toneOn\":";
    kv += (p.highlights != 0.0f || p.shadows != 0.0f || p.whites != 0.0f ||
           p.blacks != 0.0f) ? "true" : "false";
    kv += ",\"hl\":" + jnum(p.highlights, 4);
    kv += ",\"sh\":" + jnum(p.shadows, 4);
    kv += ",\"wh\":" + jnum(p.whites, 4);
    kv += ",\"blk\":" + jnum(p.blacks, 4);
    kv += ",\"fade\":" + jnum(p.fade, 4);
  }
  // U-1：[23][24] 分离色调 + [25] 单色的**生效值**（同一个日志出口 —— 真机核对
  // "黑白预设是否真的进了编辑器链路"就靠这三个字段）。字段名带 W/C/On 后缀，避免与
  // diag.stats 的 `split`/`mono`（kFsFilm 的快照，见 g_diag_params）在日志里同名歧义。
  kv += ",\"splitW\":" + jnum(p.split_warm, 4);
  kv += ",\"splitC\":" + jnum(p.split_cool, 4);
  kv += ",\"monoOn\":";
  kv += p.monochrome > 0.0f ? "true" : "false";
  // E2-6 局部调整：下发值全部进**同一个出口**（历史事故：字段写进别的函数 ⇒
  // 真机日志缺项、整轮 A/B 白跑）。locOn 与 passStyle 内的门控同式重算。
  kv += ",\"locOn\":";
  kv += ((p.local_type >= 0.5f) && (p.local_ev != 0.0f || p.local_sat != 1.0f))
            ? "true" : "false";
  kv += ",\"locType\":" + jnum(p.local_type, 3);
  kv += ",\"locEv\":" + jnum(p.local_ev, 5);
  kv += ",\"locSat\":" + jnum(p.local_sat, 5);
  kv += ",\"locC\":[" + jnum(p.local_center_x, 5) + "," +
        jnum(p.local_center_y, 5) + "]";
  kv += ",\"locR\":" + jnum(p.local_radius, 5);
  kv += ",\"locF\":" + jnum(p.local_feather, 5);
  kv += ",\"locA\":" + jnum(p.local_angle, 5);
  kv += ",\"locW\":" + jnum(p.local_width, 5);
  kv += ",\"w\":" + std::to_string(w) + ",\"h\":" + std::to_string(h) + "}";
  Logger::instance().log(ran ? LogLevel::kInfo : LogLevel::kError, "engine",
                         "render.style", kv);
  return ran;
}

// RAW 静拍 pass1：整型纹理 texelFetch + shader 内 demosaic（见 kFsRaw 注释）
void FrameRenderer::passRaw(int dst_w, int dst_h, int rot, int cfa,
                            const float black_phase[4], float white_level,
                            const float gains[3], const float* ccm,
                            const FilmParams& p) {
  // 用户 EV 不在这里乘（v0.7.8 起统一在 passFilm 影调链之后下发）——
  // 这里的输出是场景统计的输入，提前乘会被 auto 黑点归一抵消。
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_scene_);
  glViewport(0, 0, dst_w, dst_h);
  glUseProgram(prog_raw_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, raw_tex_);
  glUniform1i(u_raw_.tex, 0);
  glUniform2i(u_raw_.size, raw_w_, raw_h_);
  glUniform1i(u_raw_.rot, rot);
  glUniform2f(u_raw_.scale, 1.0f, 1.0f);  // 静拍不裁剪，整幅输出
  glUniform1i(u_raw_.cfa, cfa);
  glUniform4f(u_raw_.black, black_phase[0], black_phase[1], black_phase[2],
              black_phase[3]);
  glUniform1f(u_raw_.white, white_level);
  glUniform3f(u_raw_.gain, gains[0], gains[1], gains[2]);
  // LSC 增益纹理（单元 1；1×1 恒等或 HAL 网格，submitRaw 每次准备）
  glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, lsc_tex_);
  glUniform1i(u_raw_.lsc, 1);
  glUniform2i(u_raw_.lsc_size, lsc_w_, lsc_h_);
  glActiveTexture(GL_TEXTURE0);
  // ccm 数组是行主序（ColorSpaceTransform 语义），GL 按列主序读取——必须 transpose，
  // 否则实际作用转置矩阵 → 严重偏色（v0.5.20 样张全局品红的根因）
  glUniformMatrix3fv(u_raw_.ccm, 1, GL_TRUE, ccm);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// RCD 中间目标：方向场与全图 G（raw 原始方向，w×h）。尺寸变化时重建。
// 降噪开时另建 tex_mos_（P0 输出，同 raw 尺寸）。
bool FrameRenderer::ensureRcdTargets(int w, int h) {
  if (w <= 0 || h <= 0) return false;
  if (w != rcd_w_ || h != rcd_h_) {
    if (tex_dir_) {
      glDeleteTextures(1, &tex_dir_);
      glDeleteFramebuffers(1, &fbo_dir_);
      glDeleteTextures(1, &tex_g_);
      glDeleteFramebuffers(1, &fbo_g_);
      glDeleteTextures(1, &tex_mos_);
      glDeleteFramebuffers(1, &fbo_mos_);
      glDeleteTextures(1, &tex_rcd_a_);
      glDeleteFramebuffers(1, &fbo_rcd_a_);
    }
    tex_dir_ = tex_g_ = tex_mos_ = tex_rcd_a_ = 0;
    fbo_dir_ = fbo_g_ = fbo_mos_ = fbo_rcd_a_ = 0;
    // @domain: A2 —— RCD 方向场（色差域的判据量）
    makeTex(&tex_dir_, &fbo_dir_, GL_RGBA16F, w, h);
    // @domain: D2
    makeTex(&tex_g_, &fbo_g_, GL_RGBA16F, w, h);
    // @domain: D2
    makeTex(&tex_mos_, &fbo_mos_, GL_RGBA16F, w, h);
    // @domain: D2 —— P3 拆双 pass（v0.10.180）的 3a 中间场：WB 域 RGB（对角段
    // 已填、无 LSC），3b 的交叉轴取数源。源几何（与 tex_g_ 同尺寸同坐标语义）。
    makeTex(&tex_rcd_a_, &fbo_rcd_a_, GL_RGBA16F, w, h);
    rcd_w_ = w;
    rcd_h_ = h;
  }
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_dir_);
  const bool ok1 = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_g_);
  const bool ok2 = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_mos_);
  const bool ok3 = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_rcd_a_);
  const bool ok4 = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
  return ok1 && ok2 && ok3 && ok4;
}

// 降噪输出目标：tex_dn_（旋转后 dst 尺寸，P4 输出，胶片链输入）
bool FrameRenderer::ensureDnTarget(int w, int h) {
  if (tex_dn_ != 0 && dn_w_ == w && dn_h_ == h) return true;
  if (tex_dn_) {
    glDeleteTextures(1, &tex_dn_);
    glDeleteFramebuffers(1, &fbo_dn_);
  }
  tex_dn_ = fbo_dn_ = 0;
  // @domain: D4 —— P4 色度降噪输出
  makeTex(&tex_dn_, &fbo_dn_, GL_RGBA16F, w, h);
  dn_w_ = w;
  dn_h_ = h;
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_dn_);
  return glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
}

// M4-7 局部低频图：尺寸随渲染分辨率（v0.6.34）。init 建的 32×24 是**兜底绑定**，
// kFsFilm 里存在 sampler2D，Mali 会预测执行 `if (uLocalStrength > 0)` 里的
// texture() —— 未绑合法纹理会让整帧 draw 报 1282（v0.6.0-m4 全黑同族事故）。
// 所以这里**失败也保证 tex_local_ 非 0**（退回 1×1），此时调用方关掉局部提亮。
bool FrameRenderer::ensureLocalTarget(int w, int h) {
  if (w <= 0 || h <= 0) return false;
  if (tex_local_ != 0 && local_w_ == w && local_h_ == h) return true;
  if (tex_local_) {
    glDeleteTextures(1, &tex_local_);
    glDeleteFramebuffers(1, &fbo_local_);
  }
  tex_local_ = fbo_local_ = 0;
  // @domain: D5 —— local_tone 的低频亮度图（显示域，RGBA8 8bit）
  makeTex(&tex_local_, &fbo_local_, GL_RGBA8, w, h);
  // 过滤必须 LINEAR：makeTex 默认 NEAREST，会把格子放大成可见方块（真机"亮斑"）
  glBindTexture(GL_TEXTURE_2D, tex_local_);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  local_w_ = w;
  local_h_ = h;
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_local_);
  if (glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) return true;
  // 兜底：退回 1×1（局部提亮退化成全局基准，调用方据返回值决定是否启用）
  glDeleteTextures(1, &tex_local_);
  glDeleteFramebuffers(1, &fbo_local_);
  // @domain: D5 —— local_tone 的低频亮度图（显示域，RGBA8 8bit）
  makeTex(&tex_local_, &fbo_local_, GL_RGBA8, 1, 1);
  local_w_ = local_h_ = 1;
  return false;
}

// P0：RAW 域同色联合 bilateral → 线性 mosaic（tex_mos_）
void FrameRenderer::passRawDenoise(int w, int h, int cfa,
                                   const float black_phase[4],
                                   float white_level, const float gains[3],
                                   const NoiseCoef& nc) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_mos_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_raw_dn_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, raw_tex_);
  glUniform1i(u_raw_dn_.tex, 0);
  glUniform2i(u_raw_dn_.size, raw_w_, raw_h_);
  glUniform1i(u_raw_dn_.cfalut, cfaLutOf(cfa));
  glUniform4f(u_raw_dn_.black, black_phase[0], black_phase[1], black_phase[2],
              black_phase[3]);
  glUniform1f(u_raw_dn_.white, white_level);
  glUniform3f(u_raw_dn_.gain, gains[0], gains[1], gains[2]);
  glUniform3f(u_raw_dn_.noise_a, nc.p0a[0], nc.p0a[1], nc.p0a[2]);
  glUniform1f(u_raw_dn_.mix, nc.mix_p0);   // v0.10.108：强度<1 的输出混合
  glUniform3f(u_raw_dn_.noise_b, nc.p0b[0], nc.p0b[1], nc.p0b[2]);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// 档 A（v0.10.165）：伪彩强度图统计网格边长。**必须与 kFsMoireStat 的 kGrid 一致**。
constexpr int kMoireGrid = 32;
// 档 A：伪彩度量的归一化尺度（"2σc 量级的色度起伏"给 moire = 0.5）。离线标定见
// /tmp/stageA/REPORT.md（扫参区间 1–4，取 σC(1) 降幅与色度带通损失的拐点）。
constexpr float kMoireK = 2.0f;
// 档 A 统计口径（与 kFsMoireStat 成对，见其注释）：4 锚点分段线性 CDF 估 P95；
// moSc = 1 + gain·mean（封顶 4·σc 在 gain ≤ 3 时恒不触发 ⇒ 精确）。
struct MoireStats {
  float mean = 0.f, p95 = 0.f, cov = 0.f, sc = 1.f;
};
MoireStats moireReduce(const float* grid, int w, int h, float gain) {
  double wsum = 0.0, s_mean = 0.0, s_c25 = 0.0, s_c50 = 0.0, s_c75 = 0.0;
  for (int gy = 0; gy < kMoireGrid; ++gy) {
    const int loy = gy * h / kMoireGrid;
    const int hiy = std::min(((gy + 1) * h + kMoireGrid - 1) / kMoireGrid, h);
    for (int gx = 0; gx < kMoireGrid; ++gx) {
      const int lox = gx * w / kMoireGrid;
      const int hix = std::min(((gx + 1) * w + kMoireGrid - 1) / kMoireGrid, w);
      // 每格 256 个等距样本代表整格 ⇒ 按格覆盖像素数加权（边缘格面积不同）
      const double wt = static_cast<double>(std::max(hix - lox, 1)) *
                        static_cast<double>(std::max(hiy - loy, 1));
      const float* s = &grid[(static_cast<size_t>(gy) * kMoireGrid + gx) * 4];
      wsum += wt;
      s_mean += wt * s[0];
      s_c25 += wt * s[1];
      s_c50 += wt * s[2];
      s_c75 += wt * s[3];
    }
  }
  MoireStats out;
  if (wsum <= 0.0) return out;
  const double mean = s_mean / wsum;
  out.mean = static_cast<float>(mean);
  out.cov = static_cast<float>(s_c50 / wsum);
  out.sc = static_cast<float>(1.0 + static_cast<double>(gain) * mean);
  const double p25 = s_c25 / wsum, p50 = s_c50 / wsum, p75 = s_c75 / wsum;
  const double xs[5] = {0.0, 0.25, 0.5, 0.75, 1.0};
  double fs[5] = {0.0, 1.0 - p25, 1.0 - p50, 1.0 - p75, 1.0};
  for (int i = 1; i < 5; ++i) fs[i] = std::max(fs[i], fs[i - 1]);  // 采样噪声防倒挂
  double p95 = 1.0;
  for (int i = 1; i < 5; ++i) {
    if (0.95 <= fs[i]) {
      const double t = fs[i] > fs[i - 1] ? (0.95 - fs[i - 1]) / (fs[i] - fs[i - 1]) : 0.0;
      p95 = xs[i - 1] + t * (xs[i] - xs[i - 1]);
      break;
    }
  }
  out.p95 = static_cast<float>(p95);
  return out;
}

// ── 0.12.161：Bento collar flow 的 CPU 段（照 `BentoRegionAdmission.kt` 逐段）──
// 入参 supp：每 tile 是否可观测（1 = 参考帧在该 tile 内无裁剪像素）。
// 与 PC 的差异：support 是二值（缺 agreement 项），故插值权重只有 1/d²；
// PC 的 confidence 加权在这里退化为等权。
// 返回 false = 全图没有任何可验证的 tile（PC 的 `resolveGeometry() == null`），
// 调用方保留原位移场（PC 语义：Bento 被拒绝，而不是"不融合"）。
constexpr int kCollarRadius = 4;   // PC 的 COLLAR_RADIUS
// 0.12.164：PC3D 的 A/B 关断开关（true = 本包关掉 PC3D，用于定位阴影边缘的亮度平台）
constexpr bool kPc3dAbOff = false;
static bool collarFlowFix(std::vector<float>* off, const std::vector<float>& supp,
                          int tw, int th, int* regions, int* regions_collar,
                          int* regions_global, int* fixed_tiles) {
  const int n = tw * th;
  const auto supported = [&](int t) { return supp[static_cast<size_t>(t)] >= 0.5f; };
  std::vector<int> sup;
  for (int t = 0; t < n; ++t)
    if (supported(t)) sup.push_back(t);
  *regions = *regions_collar = *regions_global = *fixed_tiles = 0;
  // PC：全图 supported < MIN_COLLAR_TILES ⇒ 无依据，拒绝整张修正。
  if (sup.size() < 4) return false;
  // 全图 supported 的逐轴中值（PC 的 globalSupportedFlow；conf=1 ⇒ 普通中值）
  float gmed[2] = {0.f, 0.f};
  {
    std::vector<float> ax(sup.size());
    for (int a = 0; a < 2; ++a) {
      for (size_t k = 0; k < sup.size(); ++k)
        ax[k] = (*off)[static_cast<size_t>(sup[k]) * 2 + a];
      std::sort(ax.begin(), ax.end());
      gmed[a] = ax[ax.size() / 2];
    }
  }
  // 8-连通分组（同组的 tile 共享同一套 collar —— PC 的关键结构）
  std::vector<int> group(static_cast<size_t>(n), -1);
  std::vector<std::vector<int>> groups;
  std::vector<int> queue(static_cast<size_t>(n));
  for (int start = 0; start < n; ++start) {
    if (supported(start) || group[static_cast<size_t>(start)] >= 0) continue;
    const int gid = static_cast<int>(groups.size());
    int head = 0, tail = 0;
    queue[tail++] = start;
    group[static_cast<size_t>(start)] = gid;
    while (head < tail) {
      const int t = queue[head++];
      const int tx = t % tw, ty = t / tw;
      for (int ny = std::max(0, ty - 1); ny <= std::min(th - 1, ty + 1); ++ny)
        for (int nx = std::max(0, tx - 1); nx <= std::min(tw - 1, tx + 1); ++nx) {
          const int nb = ny * tw + nx;
          if (!supported(nb) && group[static_cast<size_t>(nb)] < 0) {
            group[static_cast<size_t>(nb)] = gid;
            queue[tail++] = nb;
          }
        }
    }
    groups.emplace_back(queue.begin(), queue.begin() + tail);
  }
  *regions = static_cast<int>(groups.size());
  std::vector<int> stamp(static_cast<size_t>(n), -1);
  std::vector<int> collar;
  for (size_t gi = 0; gi < groups.size(); ++gi) {
    const auto& tiles = groups[gi];
    collar.clear();
    for (int t : tiles) {
      const int tx = t % tw, ty = t / tw;
      for (int ny = std::max(0, ty - kCollarRadius);
           ny <= std::min(th - 1, ty + kCollarRadius); ++ny)
        for (int nx = std::max(0, tx - kCollarRadius);
             nx <= std::min(tw - 1, tx + kCollarRadius); ++nx) {
          const int nb = ny * tw + nx;
          if (stamp[static_cast<size_t>(nb)] == static_cast<int>(gi) || !supported(nb))
            continue;
          stamp[static_cast<size_t>(nb)] = static_cast<int>(gi);
          collar.push_back(nb);
        }
    }
    // PC 的 MIN_COLLAR_TILES = 4
    const bool from_collar = collar.size() >= 4;
    if (from_collar) ++*regions_collar; else ++*regions_global;
    for (int t : tiles) {
      ++*fixed_tiles;
      float* o = &(*off)[static_cast<size_t>(t) * 2];
      if (!from_collar) {
        o[0] = gmed[0];
        o[1] = gmed[1];
        continue;
      }
      const int tx = t % tw, ty = t / tw;
      double total = 0.0, sx = 0.0, sy = 0.0;
      for (int nb : collar) {
        const double dx = static_cast<double>(nb % tw - tx);
        const double dy = static_cast<double>(nb / tw - ty);
        const double w = 1.0 / (dx * dx + dy * dy);
        total += w;
        sx += (*off)[static_cast<size_t>(nb) * 2 + 0] * w;
        sy += (*off)[static_cast<size_t>(nb) * 2 + 1] * w;
      }
      if (total > 1e-12) {
        o[0] = static_cast<float>(sx / total);
        o[1] = static_cast<float>(sy / total);
      }
    }
  }
  return true;
}

// 0.12.122：collar flow（见 kFsCollarSupport 注释）—— 修正 highlight 区的位移场。
// 0.12.161：结构改为「GPU 出 support → CPU 分组/collar → 上传」，
// 与 PC 的 BentoRegionAdmission 同构；tiles 只有数百个，CPU 成本可忽略。
bool FrameRenderer::passCollarFlow(GLuint off_tex, GLuint ref_tex, int w, int h,
                                   float white_level, int tw, int th) {
  dbg_collar_regions_ = dbg_collar_regions_collar_ = dbg_collar_regions_global_ = 0;
  dbg_collar_tiles_ = dbg_collar_supp_ = 0;
  if (off_tex == 0 || ref_tex == 0 || w <= 0 || h <= 0 || tw <= 0 || th <= 0 ||
      prog_collar_supp_ == 0)
    return false;
  const auto ensure = [&](GLuint* tex, GLuint* fbo, int* cw, int* ch, bool want_fbo) {
    if (*tex != 0 && *cw == tw && *ch == th) return true;
    if (*tex) glDeleteTextures(1, tex);
    if (*fbo) glDeleteFramebuffers(1, fbo);
    *tex = *fbo = 0;
    glGenTextures(1, tex);
    glBindTexture(GL_TEXTURE_2D, *tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, tw, th, 0, GL_RGBA,
                 GL_HALF_FLOAT, nullptr);
    if (want_fbo) {
      glGenFramebuffers(1, fbo);
      glBindFramebuffer(GL_FRAMEBUFFER, *fbo);
      glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                             *tex, 0);
      const GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
      glBindFramebuffer(GL_FRAMEBUFFER, 0);
      if (st != GL_FRAMEBUFFER_COMPLETE) return false;
    }
    *cw = tw;
    *ch = th;
    return true;
  };
  if (!ensure(&tex_off_fix_, &fbo_off_fix_, &off_fix_w_, &off_fix_h_, false))
    return false;
  if (!ensure(&tex_off_supp_, &fbo_off_supp_, &off_supp_w_, &off_supp_h_, true))
    return false;
  // ① support：参考帧在 tile 内是否出现裁剪像素（PC 的 clipped == 0 硬条件）
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_off_supp_);
  glViewport(0, 0, tw, th);
  glUseProgram(prog_collar_supp_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, off_tex);
  glUniform1i(u_collar_supp_.off, 0);
  glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, ref_tex);
  glUniform1i(u_collar_supp_.ref, 1);
  glActiveTexture(GL_TEXTURE0);
  glUniform2i(u_collar_supp_.size, w, h);
  glUniform2i(u_collar_supp_.tiles, tw, th);
  glUniform1i(u_collar_supp_.tilem, 2 * kMfnrTile);   // tile 边长（mosaic px）
  glUniform1f(u_collar_supp_.white, white_level);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  // ② 读回（tiles 只有数百个 ⇒ 数 KB；RGBA16F 必须 HALF_FLOAT，项目既有结论）
  std::vector<uint16_t> hb(static_cast<size_t>(tw) * th * 4);
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_off_supp_);
  glPixelStorei(GL_PACK_ALIGNMENT, 1);
  glReadPixels(0, 0, tw, th, GL_RGBA, GL_HALF_FLOAT, hb.data());
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  const int n = tw * th;
  std::vector<float> flow(static_cast<size_t>(n) * 2), supp(static_cast<size_t>(n));
  for (int i = 0; i < n; ++i) {
    flow[static_cast<size_t>(i) * 2 + 0] = halfToFloat(hb[static_cast<size_t>(i) * 4 + 0]);
    flow[static_cast<size_t>(i) * 2 + 1] = halfToFloat(hb[static_cast<size_t>(i) * 4 + 1]);
    supp[static_cast<size_t>(i)] = halfToFloat(hb[static_cast<size_t>(i) * 4 + 3]);
  }
  int nreg = 0, nreg_c = 0, nreg_g = 0, nfix = 0;
  const bool fixed = collarFlowFix(&flow, supp, tw, th, &nreg, &nreg_c, &nreg_g, &nfix);
  dbg_collar_regions_ = nreg;
  dbg_collar_regions_collar_ = nreg_c;
  dbg_collar_regions_global_ = nreg_g;
  dbg_collar_tiles_ = fixed ? nfix : 0;
  dbg_collar_supp_ = 0;
  for (int i = 0; i < n; ++i)
    if (supp[static_cast<size_t>(i)] >= 0.5f) ++dbg_collar_supp_;
  // ③ 上传（z = 权重恒 1，与打包位移场的既有约定一致；w 不再承载 support）
  std::vector<float> pack(static_cast<size_t>(n) * 4);
  for (int i = 0; i < n; ++i) {
    pack[static_cast<size_t>(i) * 4 + 0] = flow[static_cast<size_t>(i) * 2 + 0];
    pack[static_cast<size_t>(i) * 4 + 1] = flow[static_cast<size_t>(i) * 2 + 1];
    pack[static_cast<size_t>(i) * 4 + 2] = 1.f;
    pack[static_cast<size_t>(i) * 4 + 3] = 1.f;
  }
  glBindTexture(GL_TEXTURE_2D, tex_off_fix_);
  glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, tw, th, GL_RGBA, GL_FLOAT, pack.data());
  return true;
}

// P4：色度平滑（读 tex_scene_ → tex_dn_）。输出几何与 passRcdChroma 一致
// 0.12.112：降噪链出口的 SNR 混合（高光取原图）——见 kFsDnBlend 的注释。
bool FrameRenderer::passDnBlend(int w, int h, GLuint dn_tex, GLuint orig_tex,
                                const NoiseCoef& nc) {
  if (w <= 0 || h <= 0 || dn_tex == 0 || orig_tex == 0 || dn_tex == orig_tex)
    return false;
  if (prog_dn_blend_ == 0) return false;
  if (w != dnb_w_ || h != dnb_h_ || tex_dnb_ == 0) {
    if (tex_dnb_) glDeleteTextures(1, &tex_dnb_);
    if (fbo_dnb_) glDeleteFramebuffers(1, &fbo_dnb_);
    tex_dnb_ = fbo_dnb_ = 0;
    glGenTextures(1, &tex_dnb_);
    glBindTexture(GL_TEXTURE_2D, tex_dnb_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GL_RGBA, GL_HALF_FLOAT,
                 nullptr);
    glGenFramebuffers(1, &fbo_dnb_);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_dnb_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                           tex_dnb_, 0);
    const GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (st != GL_FRAMEBUFFER_COMPLETE) {
      Logger::instance().log(LogLevel::kError, "engine", "dnblend.fbo",
                             "{\"status\":" + std::to_string(st) + "}");
      return false;
    }
    dnb_w_ = w;
    dnb_h_ = h;
  }
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_dnb_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_dn_blend_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, dn_tex);
  glUniform1i(u_dn_blend_.dn, 0);
  glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, orig_tex);
  glUniform1i(u_dn_blend_.orig, 1);
  glUniform3f(u_dn_blend_.noise_a, nc.p4a[0], nc.p4a[1], nc.p4a[2]);
  glUniform3f(u_dn_blend_.noise_b, nc.p4b[0], nc.p4b[1], nc.p4b[2]);
  // SNR 门：≤40 全降噪（暗部/中灰，逐位不变），≥120 取原图（亮部 SNR≈210）。
  // 40/120 是"降噪能改善多少"的边界，不是画面参数：SNR=40 时残余噪声 2.5%
  // （肉眼在 L>80 的区域已看不见），再往下压收益 <0.6%。
  glUniform1f(u_dn_blend_.snr_lo, 40.0f);
  glUniform1f(u_dn_blend_.snr_hi, 120.0f);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  return true;
}

void FrameRenderer::passChromaDenoise(int dst_w, int dst_h, int rot,
                                      const NoiseCoef& nc, float moire,
                                      float cg_k, float cg_scope,
                                      GLuint src_tex) {
  (void)rot;  // kVertRot 旋转已在顶点层；frag 用 gl_FragCoord 定位
  dbg_mo_gain_ = moire > 0.0f ? moire : 0.0f;
  dbg_mo_mean_ = 0.f; dbg_mo_p95_ = 0.f; dbg_mo_cov_ = 0.f; dbg_mo_sc_ = 1.f;
  dbg_cg_k_ = cg_k > 0.0f ? cg_k : 0.f;      // v0.10.173（日志字段 cgK）
  dbg_cg_cap_ = 16.0f;                       // v0.10.174：shader 常量 kCgRel（相对上限 16·σc）
  dbg_cg_scope_ = cg_scope;
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_dn_);
  glViewport(0, 0, dst_w, dst_h);
  glUseProgram(prog_chroma_dn_);
  glActiveTexture(GL_TEXTURE0);
  // v0.10.175：src_tex = 0 时读 tex_scene_（原行为，**逐位不变**）；PC3D 开启时
  // 读 tex_pc3d_（同一域、同一几何，只是多了一层照抄 PC 的降噪）。
  glBindTexture(GL_TEXTURE_2D, src_tex ? src_tex : tex_scene_);
  glUniform1i(u_chroma_dn_.tex, 0);
  // C 专项：LSC 增益网格（传播 read 项 ×g²；关闭态绑 1×1 恒等 → 零分支）
  glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, lsc_tex_);
  glUniform1i(u_chroma_dn_.lsc, 1);
  glUniform2i(u_chroma_dn_.lsc_size, lsc_w_, lsc_h_);
  // 0.12.187：饱和掩码（单元 2）
  glActiveTexture(GL_TEXTURE2);
  glBindTexture(GL_TEXTURE_2D, tex_satm_dst_ ? tex_satm_dst_ : tex_satm_);
  glUniform1i(u_chroma_dn_.satmask, 2);
  if (u_chroma_dn_.satmask_on >= 0)
    glUniform1i(u_chroma_dn_.satmask_on, satmask_enabled_ ? 1 : 0);
  glActiveTexture(GL_TEXTURE1);
  glUniform2i(u_chroma_dn_.size, dst_w, dst_h);
  glUniform3f(u_chroma_dn_.noise_a, nc.p4a[0], nc.p4a[1], nc.p4a[2]);
  glUniform1f(u_chroma_dn_.mix, nc.mix_p4);   // v0.10.108
  glUniform3f(u_chroma_dn_.noise_b, nc.p4b[0], nc.p4b[1], nc.p4b[2]);
  // 档 A（v0.10.165）：伪彩强度 + 归一化尺度。uMoireK = 2.0 ⇒ "2σc 量级的色度
  // 起伏"给 moire = 0.5（与 PC 的 MOIRE 不同量纲，不可数值对标）。
  glUniform1f(u_chroma_dn_.moire, moire > 0.0f ? moire : 0.0f);
  glUniform1f(u_chroma_dn_.moire_k, kMoireK);
  // v0.10.173：强边缘色度梯度门（κ、作用域）。cg_k = 0 ⇒ cg = 0 ⇒ scEff/sy 与
  // 改动前逐位相同，a 通道也回到档 A 的 moire 语义。
  glUniform1f(u_chroma_dn_.cg_k, cg_k > 0.0f ? cg_k : 0.0f);
  glUniform1f(u_chroma_dn_.cg_scope, cg_scope);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glActiveTexture(GL_TEXTURE0);
  // 档 A 诊断：强度图统计（仅开启时执行 —— 关闭时零新增成本）。成本 = 32×32
  // fragment × 256 次 texelFetch（≈ 全图 2% 采样）+ 16KB 读回，每张成片一次。
  // v0.10.173：cg_k > 0 时 a 通道 = 门控占比，同一读回给 cgMean/cgP95/cgCov。
  if (moire > 0.0f || cg_k > 0.0f) {
    if (tex_mo_stat_ == 0) {
      makeTex(&tex_mo_stat_, &fbo_mo_stat_, GL_RGBA16F, kMoireGrid, kMoireGrid);
    }
    if (fbo_mo_stat_ != 0 &&
        glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
      glBindFramebuffer(GL_FRAMEBUFFER, fbo_mo_stat_);
      glViewport(0, 0, kMoireGrid, kMoireGrid);
      glUseProgram(prog_moire_stat_);
      glActiveTexture(GL_TEXTURE0);
      glBindTexture(GL_TEXTURE_2D, tex_dn_);   // P4 输出，a = moire
      glUniform1i(u_moire_stat_.src, 0);
      glUniform2i(u_moire_stat_.size, dst_w, dst_h);
      glDrawArrays(GL_TRIANGLES, 0, 3);
      std::vector<float> grid(static_cast<size_t>(kMoireGrid) * kMoireGrid * 4, 0.f);
      glReadPixels(0, 0, kMoireGrid, kMoireGrid, GL_RGBA, GL_FLOAT, grid.data());
      const MoireStats st = moireReduce(grid.data(), dst_w, dst_h, moire);
      dbg_mo_mean_ = st.mean;
      dbg_mo_p95_ = st.p95;
      dbg_mo_cov_ = st.cov;
      dbg_mo_sc_ = st.sc;
      // 恢复 P4 的目标绑定（后续 pass 各自绑 FBO，这里只为不留下意外状态）
      glBindFramebuffer(GL_FRAMEBUFFER, fbo_dn_);
      glViewport(0, 0, dst_w, dst_h);
    }
  }
}

// D 专项（v0.10.53）：半分辨率 à trous 色度降噪缓冲（2× 近似层 + 5× 细节层 + σ）
bool FrameRenderer::ensureChromaMst(int w, int h) {
  const int hw = (w + 1) / 2, hh = (h + 1) / 2;
  if (ch_w_ == hw && ch_h_ == hh && tex_ch_a_[0] != 0 && tex_ch_sig_ != 0) {
    return true;
  }
  for (int i = 0; i < 2; ++i) {
    if (tex_ch_a_[i]) glDeleteTextures(1, &tex_ch_a_[i]);
    if (fbo_ch_a_[i]) glDeleteFramebuffers(1, &fbo_ch_a_[i]);
    tex_ch_a_[i] = fbo_ch_a_[i] = 0;
    makeTex(&tex_ch_a_[i], &fbo_ch_a_[i], GL_RGBA16F, hw, hh);
  }
  for (int l = 0; l < kChromaLevels; ++l) {
    if (tex_ch_dp_[l]) glDeleteTextures(1, &tex_ch_dp_[l]);
    if (fbo_ch_dp_[l]) glDeleteFramebuffers(1, &fbo_ch_dp_[l]);
    tex_ch_dp_[l] = fbo_ch_dp_[l] = 0;
    makeTex(&tex_ch_dp_[l], &fbo_ch_dp_[l], GL_RGBA16F, hw, hh);
  }
  if (tex_ch_sig_) glDeleteTextures(1, &tex_ch_sig_);
  if (fbo_ch_sig_) glDeleteFramebuffers(1, &fbo_ch_sig_);
  tex_ch_sig_ = fbo_ch_sig_ = 0;
  // @domain: A2 —— D-1 的 σ 图（色差域的噪声尺度）
  makeTex(&tex_ch_sig_, &fbo_ch_sig_, GL_RGBA16F, hw, hh);
  if (tex_ch_a_[0] == 0 || tex_ch_sig_ == 0 || tex_ch_dp_[kChromaLevels - 1] == 0) {
    ch_w_ = ch_h_ = 0;                       // 分配失败 → 调用方保持 P4 输出
    return false;
  }
  ch_w_ = hw; ch_h_ = hh;
  return true;
}

// D 专项：色度多尺度降噪。输入 tex_dn_（P4 已平滑的输出），输出 tex_scene_
// （= P5 之后 passFilm 的输入）。返回 false = 未执行（调用方回落 tex_dn_）。
// src_tex（v0.10.134）：输入源可替换——defringe 开启时传 tex_df_（紫边抑制输出），
// 0 = tex_dn_（原行为）。
bool FrameRenderer::passChromaMultiScale(int dst_w, int dst_h,
                                        const NoiseCoef& nc, GLuint src_tex,
                                        float cg_k) {
  dbg_cms_ = 0; dbg_cms_k_ = 0.f;
  if (!prog_chroma_down_ || !prog_chroma_at_ || !prog_chroma_apply_) return false;
  if (!ensureChromaMst(dst_w, dst_h)) return false;
  const int hw = ch_w_, hh = ch_h_;
  const GLenum bufs2[2] = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1};
  // ① 全分辨率 → 半分辨率 Cb/Cr + σ（MRT：a_0 容器 + attachment1 临时挂 σ 图）
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_ch_a_[0]);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D,
                         tex_ch_sig_, 0);
  glDrawBuffers(2, bufs2);
  glViewport(0, 0, hw, hh);
  glUseProgram(prog_chroma_down_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, src_tex ? src_tex : tex_dn_);
  glUniform1i(u_chroma_down_.tex, 0);
  glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, lsc_tex_);
  glUniform1i(u_chroma_down_.lsc, 1);
  glUniform2i(u_chroma_down_.lsc_size, lsc_w_, lsc_h_);
  glUniform2i(u_chroma_down_.size, dst_w, dst_h);
  glUniform3f(u_chroma_down_.noise_a, nc.p4a[0], nc.p4a[1], nc.p4a[2]);
  glUniform3f(u_chroma_down_.noise_b, nc.p4b[0], nc.p4b[1], nc.p4b[2]);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, 0, 0);
  // ② 5 级 à trous：每级 MRT 写 (a_l, dp_l)。k/revert = 离线镜像定稿值
  glUseProgram(prog_chroma_at_);
  glUniform2i(u_chroma_at_.size, hw, hh);
  // v0.10.57：k 8.0 → 2.5、revert 0.08 → 0.20。8σ 的收缩阈值把幅度 2–5σ 的
  // **真实彩色纹理**（木纹/布纹等以色度为主的细节）整片压掉 —— 真机同刻诊断：
  // 色度 2/4/8px 带通只剩 PC 的 0.47/0.72/0.79，而亮度 1.34/1.08/1.02（未丢）。
  // Wiener 收缩的标准阈值是 1–3σ，8σ 属过度抑制；离线选参时只优化了 σC
  // （噪声），没看色度纹理，是第五次同类口径事故。
  glUniform1f(u_chroma_at_.k, 2.5f);
  glUniform1f(u_chroma_at_.revert, 0.20f);
  // v0.10.173：强边缘色度梯度门（逐级作用在收缩阈值 sig 上；引导量 = 半分辨率
  // Y 的中心差分，由 kFsChromaDown 的 oChroma.b 携带）。cg_k = 0 ⇒ 整段不执行。
  glUniform1f(u_chroma_at_.cg_k, cg_k > 0.0f ? cg_k : 0.0f);
  dbg_cms_ = 1; dbg_cms_k_ = 2.5f;
  glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, tex_ch_sig_);
  glUniform1i(u_chroma_at_.sigma, 1);
  for (int l = 0; l < kChromaLevels; ++l) {
    const int src = l & 1, dst = (l + 1) & 1;
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_ch_a_[dst]);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D,
                           tex_ch_dp_[l], 0);
    glDrawBuffers(2, bufs2);
    glViewport(0, 0, hw, hh);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex_ch_a_[src]);
    glUniform1i(u_chroma_at_.prev, 0);
    glUniform1i(u_chroma_at_.step, 1 << l);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, 0, 0);
  }
  // ③ 收口：Cb/Cr = a_L + Σ dp_l → 与 Y 合成写 tex_scene_
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_scene_);
  glDrawBuffers(1, bufs2);
  glViewport(0, 0, dst_w, dst_h);
  glUseProgram(prog_chroma_apply_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, tex_dn_);
  glUniform1i(u_chroma_apply_.tex, 0);
  glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, tex_ch_a_[kChromaLevels & 1]);
  glUniform1i(u_chroma_apply_.coarse, 1);
  for (int l = 0; l < kChromaLevels; ++l) {
    glActiveTexture(GL_TEXTURE2 + l);
    glBindTexture(GL_TEXTURE_2D, tex_ch_dp_[l]);
    glUniform1i(u_chroma_apply_.d[l], 2 + l);
  }
  glActiveTexture(GL_TEXTURE0);
  glUniform2i(u_chroma_apply_.size, dst_w, dst_h);
  glUniform2i(u_chroma_apply_.half, hw, hh);
  // D-2：亮度各向异性核（逐像素噪声阈值 + LSC 传播，与 P4 同源）
  glActiveTexture(GL_TEXTURE7);
  glBindTexture(GL_TEXTURE_2D, lsc_tex_);
  glUniform1i(u_chroma_apply_.lsc, 7);
  glUniform2i(u_chroma_apply_.lsc_size, lsc_w_, lsc_h_);
  glUniform3f(u_chroma_apply_.noise_a, nc.p4a[0], nc.p4a[1], nc.p4a[2]);
  glUniform3f(u_chroma_apply_.noise_b, nc.p4b[0], nc.p4b[1], nc.p4b[2]);
  glUniform1f(u_chroma_apply_.kr, 2.5f);
  glActiveTexture(GL_TEXTURE0);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  return true;
}

// ── RAW 域高光重建：darktable highlights / "inpaint opposed"（2026-10-03）─────
// 一手出处（逐行对照移植，无自研改写）：
//   src/iop/highlights.c —— 模式默认值、clips = d->clip × icoeffs[c]（OPPOSED 的
//     magics = 1.0）、processed_maximum 保持 scene-referred 不钳
//   hlreconstruct/opposed.c `_process_opposed` —— ① 块级 mask ② 块级膨胀
//     ③ 全图 chrominance 统计 ④ 应用
//   hlreconstruct/segbased.c:186 `_calc_refavg` —— 3×3 photosite 对立通道外推
//
// 为什么必须补这一层（真机实测驱动，非推测）：exp12「曝光给足」使 RAW 饱和像素
// 403→2372（6×）—— 中低亮度白斑按下去了（釉面 d>15 ↓4.7×），但被裁剪通道的颜色
// 信息随之丢失，此前只由 film 域小波 HR 兜底 ⇒ 高光边缘留彩色伪影（用户 2026-10-03
// 圈出的壶身玻璃反光带：该区域 1208 个 RAW 饱和像素，白墙 0 个）。darktable 的默认
// 路径正是在 RAW 域（去马赛克前）恢复被裁剪通道，本层补上这一环。
//
// 数据域与 darktable 逐项对应（不新造域）：
//   输入 = 线性 mosaic（已扣黑阶 + WB，白点 1.0）——v0.10.167 前置后由调用方传
//     P0 **之前**的 mosaic（MFNR = tex_fused_，R16F 单通道；单帧 = kNoDn 展开结
//     果，RGBA16F 相位布局），布局由 mono_src 描述
//   ⇒ clips[c] = 1.0 × gain[c]（darktable: d->clip × icoeffs[c]，clip 默认 1.0）
//   ⇒ correction[c] = 1.0（我们只有 as-shot WB，无 late correction 通道）
// 输出 tex_hl_（scene-referred，不钳到 1，值恒在 .r），v0.10.167 起交给 **P0**
// 作为输入（不再由 hr_src_override_ 转交 RCD —— 那时是后置）。
//
// ── v0.10.167：为什么前置（PhotonCamera / darktable 的顺序）──────────────────
// 本函数此前跑在 P0（RAW 域空间 bilateral）**之后**，而 PC/darktable 的 highlights
// 跑在空间降噪**之前**。同一张真实 DNG 的离线实测：不降噪时参考场 hlRgG = 0.9860，
// 经 P0 后 hlRgG = 1.0768 —— P0 把高光参考场的 R/G 抬高 9.2%（饱和像素旁的 G 被
// 同色 bilateral 平滑拉低，而 refavg 走 R/B 外推受影响小，`v − refavg` 被系统性
// 拉负 ⇒ 修复力度被吃掉）。真机同现象：亮部 R/G 1.063 → 1.131、chroma_G
// −0.047 → −0.138（真机 −0.250）。⇒ 统计口径必须用 **P0 前**的数据。
// ⚠ exp21 的教训（本次必须先修的结构性缺陷）：那时 mask/chroma 的输入换成 P0 前的
// tex_fused_ 时，顺手把 apply 的判定也换了 ⇒ 修复面 47269→63903 样本 ⇒ 修得更多、
// 画面更红。根因是 uStat 是**死参数**（原始字符串里 texelFetch(uStat 零命中），
// 统计/mask/apply 全走同一个 uMos ⇒ 改 src 即全改、没有独立旋钮。v0.10.167 先把
// uStat 接线成真正的统计源（mask/chrominance 走 uStat，apply/ref 走 uMos），再做
// 前置 —— 统计口径与判定口径从此是两件事。
// ── 0.12.181：饱和掩码 pass（参考帧 RAW → R8 全分辨率）──────────────────────
// 口径见 kFsSatMask 顶部。放在 HL **之前**：mask 描述的是"采集时有没有丢信息"，
// 与 HL 重建后的值无关（重建值不是测量值，不能反过来影响掩码）。
// 诊断：覆盖率sampling（64×64 块）—— 只用于确认开关生效，不进画质路径。
void FrameRenderer::passSatMask(int w, int h, int rot, int cfa,
                                const float black_phase[4], float white_level,
                                const float gains[3], const NoiseCoef& nc) {
  dbg_satm_cov_ = 0.f;  // 0.12.193：诊断字段一律先清零 —— 早退时它们必须显示"没测"，不能留上一张的值。
  dbg_satm_hit_ = 0.f;
  dbg_satm_cen_ = dbg_satm_max_ = 0.f;
  dbg_satm_w_ = dbg_satm_blk_ = 0.f;
  if (tex_satm_ == 0 || w <= 0 || h <= 0) return;
  if (raw_cpu_w_ != w || raw_cpu_h_ != h ||
      raw_cpu_.size() != static_cast<size_t>(w) * h)
    return;
  // ── 0.12.186：CPU 实现（GPU 版在 X300 上"强制 1.0 也读回 0"，链路不可诊断）──
  // ── 0.12.194：判据对齐 PC（bjzhou/PhotonCamera，MGC Bento）的传感器域口径 ──
  // 一手出处：`GlesMgcRawSpatialStacker.kt` 的 renderAlignedRawClippingMask
  //   （:5190 逐相位阈值）+ `GlesMgcRawSpatialShaders.kt` 的 quadIsNearClipping（:1600）。
  //   · 域 = **未归一化传感器码**（原文："Detection stays in the unnormalized sensor
  //     domain so exposure scaling cannot make a clipped sample appear valid."）
  //   · 阈值 = 逐相位 `black + (white − black) × BENTO_MAX_RGB_CLIPPING/255`
  //     （250/255 = 0.9804），**整数比较** `raw >= ceil(level)`（避免 Mali 把
  //     uint16 样本降成 FP16）
  //   · 不含 WB 增益、不含噪声项：饱和是"读数贴满阱"，不是"亮度接近白"
  // 旧口径 `wb >= gain − 3σ`（WB 域 + 噪声项）是自创，两个方向都错：
  //   σ→0（无噪声模型）时退化成 v ≥ 1.0，比 0.98 严 2% —— 白电平是标称值，传感器
  //   峰值常差 1~3 LSB，整片零命中；σ 大时阈值又随 ISO 漂到 0.9 以下，过判。
  const float kClipLevel = 250.f / 255.f;   // PC BENTO_MAX_RGB_CLIPPING = 250
  const size_t n = static_cast<size_t>(w) * h;
  std::vector<uint8_t> sat(n, 0), dil(n, 0), out(n * 4, 0);
  uint16_t mx = 0;
  for (int y = 0; y < h; ++y) {
    for (int x = 0; x < w; ++x) {
      const int k = ((y & 1) << 1) | (x & 1);
      const float bl = black_phase[k];
      const uint16_t rv = raw_cpu_[static_cast<size_t>(y) * w + x];
      if (rv > mx) mx = rv;
      const float lvl = std::ceil(bl + (white_level - bl) * kClipLevel);
      sat[static_cast<size_t>(y) * w + x] = (static_cast<float>(rv) >= lvl) ? 1 : 0;
    }
  }
  (void)cfa;    // 判据逐相位、与通道无关（PC 同口径）；保留参数以固定调用约定
  (void)gains;
  (void)nc;
  size_t sat_hits = 0;
  for (size_t i = 0; i < n; ++i) sat_hits += sat[i] ? 1 : 0;   // 0.12.192 诊断
  // 1px 膨胀（吸收 MFNR 对齐残差）
  for (int y = 0; y < h; ++y) {
    for (int x = 0; x < w; ++x) {
      uint8_t m = 0;
      for (int dy = -1; dy <= 1 && !m; ++dy) {
        for (int dx = -1; dx <= 1 && !m; ++dx) {
          const int yy = y + dy, xx = x + dx;
          if (yy < 0 || xx < 0 || yy >= h || xx >= w) continue;
          m = sat[static_cast<size_t>(yy) * w + xx];
        }
      }
      dil[static_cast<size_t>(y) * w + x] = m;
    }
  }
  // 2×2 聚合：块内任一（膨胀后）饱和 ⇒ 四个 photosite 一起标记
  size_t hit = 0;
  for (int y = 0; y < h; y += 2) {
    for (int x = 0; x < w; x += 2) {
      uint8_t m = 0;
      for (int dy = 0; dy < 2 && y + dy < h; ++dy)
        for (int dx = 0; dx < 2 && x + dx < w; ++dx)
          if (dil[static_cast<size_t>(y + dy) * w + x + dx]) m = 1;
      if (m) ++hit;
      for (int dy = 0; dy < 2 && y + dy < h; ++dy)
        for (int dx = 0; dx < 2 && x + dx < w; ++dx)
          out[(static_cast<size_t>(y + dy) * w + x + dx) * 4] = m ? 255 : 0;
    }
  }
  glBindTexture(GL_TEXTURE_2D, tex_satm_);
  glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
  glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE,
                  out.data());
  while (glGetError() != GL_NO_ERROR) {
  }
  // 覆盖率按 2×2 块计（与"饱和度"的物理单位一致）
  const size_t blocks = static_cast<size_t>((w + 1) / 2) * ((h + 1) / 2);
  dbg_satm_cov_ = 100.f * static_cast<float>(hit) / static_cast<float>(blocks);
  dbg_satm_hit_ = static_cast<float>(sat_hits);
  // 0.12.193：判据输入面（见 FrameRenderer.h 注释）
  dbg_satm_cen_ = static_cast<float>(raw_cpu_[(static_cast<size_t>(h / 2)) * w + w / 2]);
  dbg_satm_max_ = static_cast<float>(mx);
  dbg_satm_w_ = white_level;
  dbg_satm_blk_ = black_phase[0];

  // ── 0.12.191：转置到 D4 几何（映射与 kVertRot 逐字一致）──────────────
  // dst 像素 (x,y) 取样源 raw：uv = R(c)·scale + 0.5，c = (x/Wd-0.5, y/Hd-0.5)。
  const int Wd = (rot % 2 == 1) ? h : w;
  const int Hd = (rot % 2 == 1) ? w : h;
  if (satm_dst_w_ != Wd || satm_dst_h_ != Hd || tex_satm_dst_ == 0) {
    if (tex_satm_dst_) glDeleteTextures(1, &tex_satm_dst_);
    tex_satm_dst_ = 0;
    glGenTextures(1, &tex_satm_dst_);
    glBindTexture(GL_TEXTURE_2D, tex_satm_dst_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, Wd, Hd, 0, GL_RGBA,
                 GL_UNSIGNED_BYTE, nullptr);
    satm_dst_w_ = Wd;
    satm_dst_h_ = Hd;
  }
  {
    std::vector<uint8_t> dm(static_cast<size_t>(Wd) * Hd * 4, 0);
    for (int y = 0; y < Hd; ++y) {
      for (int x = 0; x < Wd; ++x) {
        const float cx = (static_cast<float>(x) + 0.5f) / Wd - 0.5f;
        const float cy = (static_cast<float>(y) + 0.5f) / Hd - 0.5f;
        float rx, ry;
        if (rot == 1) { rx = -cy; ry = cx; }
        else if (rot == 2) { rx = -cx; ry = -cy; }
        else if (rot == 3) { rx = cy; ry = -cx; }
        else { rx = cx; ry = cy; }
        const int sx = std::min(std::max(static_cast<int>((rx + 0.5f) * w), 0), w - 1);
        const int sy = std::min(std::max(static_cast<int>((ry + 0.5f) * h), 0), h - 1);
        dm[(static_cast<size_t>(y) * Wd + x) * 4] =
            out[(static_cast<size_t>(sy) * w + sx) * 4];
      }
    }
    glBindTexture(GL_TEXTURE_2D, tex_satm_dst_);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, Wd, Hd, GL_RGBA, GL_UNSIGNED_BYTE,
                    dm.data());
  }
}

HlRawResult FrameRenderer::passHighlightRaw(int w, int h, int cfa,
                                            const float gains[3], GLuint src,
                                            GLuint stat_src, int stat_raw,
                                            int mono_src, int grecon,
                                            int rbrecon, GLuint fix_mark) {
  HlRawResult r;
  // 0.12.133（方案 B 块 1）：掩码句柄每次重算 —— 本 pass 没跑完就等于"本张没有
  // RAW 饱和掩码"，下游 RCD/PC3D 必须退回旧行为（uMaskOn=0），不能拿上一张的。
  hl_mask_tex_ = 0;
  // v0.10.169：记录实际下发给 kFsHlApply 的 uGRecon（0/1），进 raw.render 的 hlGRecon。
  // 0.12.168：**恢复受 FilmParams[49] 控制**（A/B 通路）。0.12.132 曾为修"灯面偏暖"
  // 把它强制成 1，副作用是设置页的「高光重建·自创 allSat 档」开关变成死开关
  //（名不副实第三例），并让 10-29 的"灰斑/白平台"无法用设置做同场景 A/B。
  r.grecon = grecon ? 1 : 0;
  // v0.10.179：同上，uRbRecon（clip 过渡带 R/B 满阱比例反推）→ hlRb。
  r.rbrecon = rbrecon ? 1 : 0;
  const int mw = w / 3, mh = h / 3;    // 块网格（darktable: iwidth/3、iheight/3）
  const int tw = w / 16, th = h / 16;  // 色度统计归约网格（16×16）
  // 参考色度场网格（1/32 photosite 一块）：每通道期望 32²/4 = 256 个样本
  const int rw32 = (w + 31) / 32, rh32 = (h + 31) / 32;
  if (w <= 0 || h <= 0 || src == 0 || mw <= 0 || mh <= 0 || tw <= 0 || th <= 0)
    return r;
  const auto t0 = std::chrono::steady_clock::now();

  if (hl_w_ != w || hl_h_ != h || tex_hl_mask_ == 0) {
    if (tex_hl_mask_) glDeleteTextures(1, &tex_hl_mask_);
    if (fbo_hl_mask_) glDeleteFramebuffers(1, &fbo_hl_mask_);
    if (tex_hl_dil_) glDeleteTextures(1, &tex_hl_dil_);
    if (fbo_hl_dil_) glDeleteFramebuffers(1, &fbo_hl_dil_);
    if (tex_hl_sum_) glDeleteTextures(1, &tex_hl_sum_);
    if (fbo_hl_sum_) glDeleteFramebuffers(1, &fbo_hl_sum_);
    if (tex_hl_) glDeleteTextures(1, &tex_hl_);
    if (fbo_hl_) glDeleteFramebuffers(1, &fbo_hl_);
    if (tex_hl_ref_) glDeleteTextures(1, &tex_hl_ref_);
    if (fbo_hl_ref_) glDeleteFramebuffers(1, &fbo_hl_ref_);
    if (tex_hl_ref2_) glDeleteTextures(1, &tex_hl_ref2_);
    if (fbo_hl_ref2_) glDeleteFramebuffers(1, &fbo_hl_ref2_);
    tex_hl_mask_ = fbo_hl_mask_ = tex_hl_dil_ = fbo_hl_dil_ = 0;
    tex_hl_sum_ = fbo_hl_sum_ = tex_hl_ = fbo_hl_ = 0;
    tex_hl_ref_ = fbo_hl_ref_ = 0;
    tex_hl_ref2_ = fbo_hl_ref2_ = 0;
    // @domain: D2 —— 高光重建（S17 判定掩码）——输入域 = 线性 mosaic + WB
    makeTex(&tex_hl_mask_, &fbo_hl_mask_, GL_RGBA16F, mw, mh);
    // @domain: D2
    makeTex(&tex_hl_dil_, &fbo_hl_dil_, GL_RGBA16F, mw, mh);
    // @domain: D2
    makeTex(&tex_hl_sum_, &fbo_hl_sum_, GL_RGBA16F, tw, th);
    // @domain: D2 —— 高光重建输出（替代 tex_mos_ 进入 RCD）
    makeTex(&tex_hl_, &fbo_hl_, GL_RGBA16F, w, h);
    // 参考色度场：1/32 网格（尺寸键 = w/h，随上面的 hl_w_/hl_h_ 一起失效重建）
    // @domain: D2
    makeTex(&tex_hl_ref_, &fbo_hl_ref_, GL_RGBA16F, rw32, rh32);
    // 中尺度参考场（Pass3.55）：4×4 降采样 ⇒ 每格 128×128 CFA 像素
    // @domain: D2 —— 高光重建参考场（中尺度）
    makeTex(&tex_hl_ref2_, &fbo_hl_ref2_, GL_RGBA16F, (rw32 + 3) / 4, (rh32 + 3) / 4);
    hl_w_ = w;
    hl_h_ = h;
    hl_mw_ = mw;
    hl_mh_ = mh;
    hl_tw_ = tw;
    hl_th_ = th;
  }
  if (!tex_hl_mask_ || !tex_hl_dil_ || !tex_hl_sum_ || !tex_hl_ || !tex_hl_ref_ ||
      !tex_hl_ref2_)
    return r;

  const int cfalut = cfaLutOf(cfa);
  // ── 域核实（v0.10.161，改判据前必须钉死的一步）────────────────────────
  // 输入 = tex_mos_（扣黑阶 + WB 的线性 mosaic）。WB 增益 = asShotNeutral 倒数、
  // G 归一（prepareRawCommon: "WB 增益 = asShotNeutral 的倒数，G 归一"），即
  //   gain[c] = neutral_g / neutral_c,  neutral = asShotNeutral
  // 中性白在本域的值 = neutral_c × gain[c] = neutral_g ≡ 1.0（所有通道）——
  // 与 AWB 的绝对电平无关，是恒等式。满阱（raw 归一 1.0）在本域 = gain[c]。
  // ⇒ 判据「中性白 × 0.987」在本域就是标量 **0.987**，与 PhotonCamera
  //   tofloat.glsl:182 `hlVal = raw / whitePoint[c]; if (hlVal >= HLCLIP(0.987))`
  //   在同一域、逐字等价（hlVal × neutral_g ≡ 本域值；neutral_g 归一为 1）。
  // 反证旧口径：满阱 = gain[c] = 本机 (1.898, 1.0, 1.643)（真机日志 nzG）。G 的
  // 满阱恰好 = 中性白 ⇒ [中性白, 满阱) 这段过曝区在 R/B 上极宽、在 G 上为空；
  // 而饱和区在本域的值就是 (gainR, 1.0, gainB) —— 天然缺 G ⇒ 偏品红。
  const float kHlClipThr = 0.987f;
  const float hlclip[3] = {kHlClipThr, kHlClipThr, kHlClipThr};
  // 满阱（逐通道）：只交给 kFsHlApply 的 allSat 判定（"三通道都顶到传感器上限"）
  const float sat[3] = {gains[0], gains[1], gains[2]};
  // 0.12.105：裁剪判据的逐通道满阱（与上面 sat 同源）—— mask / apply 两处都按它归一
  const float satclip[3] = {gains[0], gains[1], gains[2]};

  // ① 块级 mask：3×3 photosite 一块，块内任一 photosite 达 clips[c] 即置位
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_hl_mask_);
  glViewport(0, 0, mw, mh);
  glUseProgram(prog_hl_mask_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, src);
  glUniform1i(u_hl_mask_.mos, 0);
  glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, stat_src);
  glUniform1i(u_hl_mask_.stat, 1);
  glUniform1i(u_hl_mask_.statraw, stat_raw);
  // v0.10.167：统计源的布局（src 与 stat_src 在本前置下同源同布局）
  glUniform1i(u_hl_mask_.mono, mono_src);
  glActiveTexture(GL_TEXTURE0);
  glUniform2i(u_hl_mask_.size, w, h);
  glUniform1i(u_hl_mask_.cfalut, cfalut);
  glUniform3f(u_hl_mask_.clips, hlclip[0], hlclip[1], hlclip[2]);
  glUniform3f(u_hl_mask_.satclip, satclip[0], satclip[1], satclip[2]);
  glDrawArrays(GL_TRIANGLES, 0, 3);

  // ② 块级膨胀（_mask_dilated 的 3/5/7 块环形模式）
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_hl_dil_);
  glViewport(0, 0, mw, mh);
  glUseProgram(prog_hl_dil_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, tex_hl_mask_);
  glUniform1i(u_hl_dil_.mask, 0);
  glUniform2i(u_hl_dil_.msize, mw, mh);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  // 0.12.140：alpha 传**未膨胀**的掩码（tex_hl_mask_）。
  // 膨胀版（tex_hl_dil_）是给 HL 的 chroma 统计用的，它把核心外 9px 也标成 1；
  // 而 PC3D 需要区分的是"饱和核心 vs 未饱和光晕"——用膨胀版会把光晕也算成核心，
  // 于是光晕拿到"核心的处理"（G 封顶）而混不到背景色（139 的绿/灰就是这个原因）。
  hl_mask_tex_ = tex_hl_mask_;

  // ③ chrominance 全图统计：两趟 16×16 归约（trip 0 = 偏差，trip 1 = 计数），
  // host 侧求和后按 darktable 的 100 样本闸求均值
  double sum[3] = {0.0, 0.0, 0.0}, cnt[3] = {0.0, 0.0, 0.0};
  std::vector<float> grid(static_cast<size_t>(tw) * th * 4);
  for (int mode = 0; mode < 2; ++mode) {
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_hl_sum_);
    glViewport(0, 0, tw, th);
    glUseProgram(prog_hl_chroma_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, src);
    glUniform1i(u_hl_chroma_.mos, 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, tex_hl_dil_);
    glUniform1i(u_hl_chroma_.dil, 1);
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, stat_src);
    glUniform1i(u_hl_chroma_.stat, 2);
    glUniform1i(u_hl_chroma_.statraw, stat_raw);
    glUniform1i(u_hl_chroma_.mono, mono_src);   // v0.10.167 统计源布局
    glActiveTexture(GL_TEXTURE0);
    glUniform2i(u_hl_chroma_.size, w, h);
    glUniform2i(u_hl_chroma_.msize, mw, mh);
    glUniform1i(u_hl_chroma_.cfalut, cfalut);
    // 0.12.121：chroma 统计的裁剪口径必须与 mask 的 clipAt **逐项一致**。
  // 0.12.105 把 mask 的 clipAt 改成 `thr × max(满阱,1)`（per-channel），但漏了这里：
  //   chroma 仍收标量 0.987 ⇒ 统计样本里混进大量未饱和像素（WB 域 >0.987 很容易）
  //   ⇒ 全图 chroma 被拉偏 ⇒ `refavg + uChroma` 给饱和区染上错误色相（黄绿）。
  // 真机证据：0.12.120 把更多像素推给 HL 后，轮廓黄边与天空黄绿同时加重。
  glUniform3f(u_hl_chroma_.clips,
              hlclip[0] * std::max(satclip[0], 1.0f),
              hlclip[1] * std::max(satclip[1], 1.0f),
              hlclip[2] * std::max(satclip[2], 1.0f));
    // 0.12.127：三态标记（纹理单元 3）。未绑（单帧 / 无 sat-fix）时 uMarkOn=0
    // ⇒ refavg 逐位回到旧口径（零回归）。
    const bool mark_on_chroma = (fix_mark != 0);
    glActiveTexture(GL_TEXTURE3);
    glBindTexture(GL_TEXTURE_2D, mark_on_chroma ? fix_mark : 0);
    glUniform1i(u_hl_chroma_.fixmark, 3);
    glUniform1i(u_hl_chroma_.markon, mark_on_chroma ? 1 : 0);
    glActiveTexture(GL_TEXTURE0);
    glUniform1i(u_hl_chroma_.mode, mode);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    std::fill(grid.begin(), grid.end(), 0.f);
    glReadPixels(0, 0, tw, th, GL_RGBA, GL_FLOAT, grid.data());
    double* dst = (mode == 0) ? sum : cnt;
    for (size_t i = 0; i + 3 < grid.size(); i += 4) {
      for (int k = 0; k < 3; ++k) {
        const float v = grid[i + k];
        if (std::isfinite(v)) dst[k] += v;
      }
    }
  }
  for (int k = 0; k < 3; ++k) {
    r.cnt[k] = static_cast<float>(cnt[k]);
    r.sum[k] = sum[k];
    r.chroma[k] = (cnt[k] > 100.0) ? static_cast<float>(sum[k] / cnt[k]) : 0.f;
  }

  // ③.5 参考色度场（1/32 网格）：只用未截断 photosite 统计 R/G、B/G 比例
  {
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_hl_ref_);
    glViewport(0, 0, rw32, rh32);
    glUseProgram(prog_hl_ref_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, src);
    glUniform1i(u_hl_ref_.mos, 0);
    glUniform2i(u_hl_ref_.size, w, h);
    glUniform1i(u_hl_ref_.cfalut, cfalut);
    glUniform3f(u_hl_ref_.clips, hlclip[0], hlclip[1], hlclip[2]);
    glUniform1i(u_hl_ref_.block, 32);
    glUniform1i(u_hl_ref_.mono, mono_src);   // v0.10.167 判定源布局
    glDrawArrays(GL_TRIANGLES, 0, 3);
  }

  // ③.55 中尺度参考场：对 1/32 参考场做 4×4 加权降采样（每格 128×128 CFA 像素）。
  // 这一级专治"大面积饱和区只剩全图比例可用"⇒ 多光源场景被平均成偏红（真机
  // hlRgG 1.108 对应"还是红"的反馈）。成本 32×24×16 ≈ 1.2 万次 texelFetch。
  const int rw128 = (rw32 + 3) / 4, rh128 = (rh32 + 3) / 4;
  {
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_hl_ref2_);
    glViewport(0, 0, rw128, rh128);
    glUseProgram(prog_hl_refdown_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex_hl_ref_);
    glUniform1i(u_hl_refdown_.ref1, 0);
    glUniform2i(u_hl_refdown_.refsize, rw32, rh32);
    glDrawArrays(GL_TRIANGLES, 0, 3);
  }

  // ③.6 全局兜底配对比例：读回 1/32 参考场，按**样本占比 w 加权**求全图 R/G、B/G。
  // 为什么需要它（离线实测，2026-10-04）：大面积饱和区里 32×32 块内 G 全截断 ⇒
  // 块参考不可用（真机实测占 G 饱和面的 56%）⇒ 那片整块漏修，R/G 卡在 1.553。
  // 兜底后退到全图比例，离线同口径复算 1.104（验收线 1.10）。
  // 为什么加权：无参考块（w=0）的 rg/bg 是 0/0 的脏值，等权平均会被它们污染。
  // ⚠ 口径：ref pass 输出的是**加权分子** (rg·w, bg·w, w)，故这里分子**不再乘 w**
  //（乘了就是 Σrg·w²，全局比例会偏 —— 手写双线性的插值口径要求分子分母同权）。
  // 成本：12288 块 × 4 float = 192KB 读回 + 12288 次累加，仅拍照路径一次
  //（同函数 ③ 的 chroma 统计本就有 8× 于此的两趟读回）。
  {
    // 注意：③.55 把 FBO 绑到了 ref2，这里读 ref 场前必须显式重绑（脏状态是隐形 bug）
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_hl_ref_);
    std::vector<float> rgrid(static_cast<size_t>(rw32) * rh32 * 4);
    glReadPixels(0, 0, rw32, rh32, GL_RGBA, GL_FLOAT, rgrid.data());
    // v0.10.148：ref 场四分量 = (mR·w, mG·w, mB·w, w)（**加权分子**，w 在 alpha）。
    // 故全局比例 = Σ(mR·w)/Σ(mG·w) = **像素等权池化比**（与局部/中尺度两级同口径）。
    // 注意不能用未截断样本的**原始和** ΣsR/ΣsG：G 的样本数是 R/B 的两倍，会差约 2 倍。
    // 旧口径（v0.10.143 前）是"块均值的算术平均之比"，小块等权 ⇒ 小样本块的比值噪声
    // 被放大 ⇒ 全局比例系统偏红（同片实测 1.0415 vs 池化 0.9466），窗户场景因此漏修。
    double sumR = 0.0, sumG = 0.0, sumB = 0.0, sumW = 0.0;
    long n_blk = 0;
    for (size_t i = 0; i + 3 < rgrid.size(); i += 4) {
      const float wgt = rgrid[i + 3];
      const float sg = rgrid[i + 1];
      if (!std::isfinite(wgt) || wgt <= 0.f || !(sg > 0.f)) continue;
      sumR += static_cast<double>(rgrid[i]);
      sumG += static_cast<double>(sg);
      sumB += static_cast<double>(rgrid[i + 2]);
      sumW += wgt;
      ++n_blk;
    }
    // sumW < 1.0 ⇔ 全图有效参考不足"一个满块"，比例不可信（整幅过曝）→ 退中性：
    // 0.501/0.658 = 该机型 WB 域的中性 R/G、B/G（离线基准脚本的"中性白假设"口径）。
    const bool ref_ok = n_blk > 0 && sumG > 1e-6 && std::isfinite(sumR) && std::isfinite(sumB);
    r.w_sum = sumW;
    r.rg_g = ref_ok ? static_cast<float>(sumR / sumG) : 0.501f;   // 池化比（allSat 用）
    r.bg_g = ref_ok ? static_cast<float>(sumB / sumG) : 0.658f;

    // 三级命中率诊断（v0.10.139）：读回中尺度场（32×24×4 float = 12KB），按 ref 格
    // 判定该格会走哪一级 —— 格值 > 0.30 走局部；否则看它在 ref2 里的归属格是否
    // > 0.05（中尺度命中）还是退全图。口径：格级近似（不含插值边界效应），只作诊断，
    // 真机用它回答"饱和天空区到底用了哪一级"。分母 = 局部参考不足的格数。
    std::vector<float> r2grid(static_cast<size_t>(rw128) * rh128 * 4);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_hl_ref2_);
    glReadPixels(0, 0, rw128, rh128, GL_RGBA, GL_FLOAT, r2grid.data());
    long n_mid = 0, n_glob = 0;
    for (int by = 0; by < rh32; ++by) {
      for (int bx = 0; bx < rw32; ++bx) {
        const size_t i = (static_cast<size_t>(by) * rw32 + bx) * 4;
        const float wgt = rgrid[i + 3];
        if (!std::isfinite(wgt) || wgt > 0.30f) continue;   // 第 1 级命中，不计入分母
        const size_t j = (static_cast<size_t>(by / 4) * rw128 + (bx / 4)) * 4;
        const float w2 = (j + 3 < r2grid.size()) ? r2grid[j + 3] : 0.f;
        if (std::isfinite(w2) && w2 > 0.05f) ++n_mid; else ++n_glob;
      }
    }
    const long n_fb = n_mid + n_glob;   // 局部不足的格（第 2/3 级的作用面）
    r.ref2_hit = n_fb > 0 ? static_cast<float>(100.0 * n_mid / n_fb) : 0.f;
    r.refg_hit = n_fb > 0 ? static_cast<float>(100.0 * n_glob / n_fb) : 0.f;
  }

  // ④ 应用：裁剪 photosite → max(实测, 对立外推 + chrominance)
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_hl_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_hl_apply_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, src);
  glUniform1i(u_hl_apply_.mos, 0);
  glUniform1i(u_hl_apply_.mono, mono_src);   // v0.10.167 判定源布局
  glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, stat_src);
  glUniform1i(u_hl_apply_.stat, 1);
  glUniform1i(u_hl_apply_.statraw, stat_raw);
  glActiveTexture(GL_TEXTURE0);
  glUniform2i(u_hl_apply_.size, w, h);
  glUniform1i(u_hl_apply_.cfalut, cfalut);
  glUniform3f(u_hl_apply_.clips, sat[0], sat[1], sat[2]);          // 满阱（allSat 判定）
  glUniform1f(u_hl_apply_.hlclip, kHlClipThr);                     // 裁剪判据 0.987
  // v0.10.124：**chrominance 补偿置 0**（有数据支撑的偏离原版）。
  // 原版 chroma 补偿的是"refavg 的系统性偏差"，前提是输入为**单帧未降噪** RAW。
  // 我们的输入是 MFNR(4 帧) + P0 空间 bilateral 之后：同色 3×3 平滑把饱和像素旁的
  // G 压低，而 refavg 走 R/B 外推受影响小 ⇒ `v−refavg` 被系统性拉负。离线在 151713
  // 上复算（R/G = 亮部 G 饱和像素的色偏）：未降噪 −0.047→1.063；平滑后 −0.138→1.131；
  // 真机 −0.250→1.12；**chroma=0 → R/G 0.996（中性）**。
  // 安全性：另外三张（客厅红杯/室内×2）chroma_G 仅 +0.015~+0.022，禁用前后 R/G 差
  // 0.007 —— 即该补偿只在"G 大量饱和"的逆光场景起作用，而那里它是有害的。
  // r.chroma 仍照常统计并写日志（hlChroma），便于日后换更正确的口径时对照。
  static const float kHlChromaOff[3] = {0.f, 0.f, 0.f};
  // v0.12.4：**按算法档位决定是否启用 chroma 补偿**（"回到 darktable 原版"的关键一环）。
  // 一手依据 = darktable `src/iop/hlreconstruct/opposed.c` 的写出式：
  //   `output[c] = (inval >= clips[c]) ? MAX(inval, ref + chrominance[c]) : inval;`
  // 即 opposed **永远只抬不压**，色比失衡由**加性** chrominance 补偿（不是替换三通道）。
  // 对比本文件的自创 allSat 分支（uGRecon=1）：它按参考比**替换**三通道、并用满阱 luma
  // 当亮度锚 ⇒ 把 sat-fix 救回的真值（可达 4.0）压回满阱（≈1.27），并把高光核色度抹平
  //（真机 164553 量化：亮核 chroma 2.17% vs 紧邻外圈 18.83%，同一束光差 8.7 倍）。
  // 因此：
  //   uGRecon = 1（自创档）→ chroma 保持 0（v0.10.124 真机结论：MFNR+P0 平滑后 refavg 偏负，
  //                        启用会把高光推离中性；防彩斑职责由 allSat 承担）
  //   uGRecon = 0（原版档）→ 启用 ③ 已统计出的 chrominance，补上"只抬 G 造成的色比失衡"
  //                        —— 0.12.2 关掉自创分支后仍出彩斑/绿天，缺的正是这一段。
  // ── 0.12.132：allSat 分支曾**强制启用**（不再受 hl_grecon 设置控制）──────────
  // 真机 2026-10-28 19:2x（椭圆灯面）：分界线没了，但**整个灯面偏暖米色而不是白**。
  // 数值原因可以算出来：三通道全饱和时本域的值恒为满阱比例
  // (gainR, 1.0, gainB) = (2.254, 1.0, 1.525)；opposed 的 refavg 只能把 G 抬到
  // ((cbrt(2.254)+cbrt(1.525))/2)³ = 1.89 ⇒ 归一化 (1.19, 1.0, 0.81) —— 明显偏暖。
  // 这不是"色相没保住"，而是 opposed 的语义（三通道满阱相同）在 WB 域不成立：
  // 满阱逐通道不同时，"另两个通道的平均"必然把低满阱的通道（G）抬不够。
  // 三通道都饱和 ⇒ **真实色度不可知**，唯一不骗人的表示是"中性白 + 真实亮度"，
  // 正是 kFsHlApply 里 allSat 分支的语义（按全局池化参考比 × 实测 luma 锚）。
  // 0.12.129 已移除 sat-fix 注入 ⇒ 三通道全饱和处的值就是满阱本身，luma 锚 = 满阱
  // luma，allSat 分支不会压暗任何东西（v0.12.14 担心的"压真值"前提已不存在）。
  // ── 0.12.168：**恢复受 FilmParams[49] 控制** ───────────────────────────
  // 0.12.132 的强制让设置页的「高光重建·自创 allSat 档」变成死开关，而 10-29
  // 粉蘑菇高光（hlCnt3=[0,5726,190] ⇒ 只有 G 饱和）与天空平白（nsat≥2 ⇒ allSat）
  // 恰好落在本档的两条分支上 —— 没有开关就无法做同场景 A/B 定案。
  // 默认值（0.12.169 起）= prefs `hl_grecon` 默认 false ⇒ grecon=0 ⇒ 原版 opposed。
  const bool grecon_on = (grecon != 0);
  // ── 0.12.171：chrominance 补偿**停用**（统计保留，只做诊断）───────────────
  // 原版 darktable/PC 的 chroma 补偿前提 = "输入是单帧未降噪 RAW"，此时
  // `v − refavg` 的偏差只来自 opposed 的立方均值近似，量级 ~1e-3。
  // 我们的输入是 **MFNR 4 帧融合 + P0 bilateral 之后** 的 mosaic：同色 3×3 平滑把
  // 高光边缘的 G 压低，refavg 相对 v 系统性偏负 —— 0.12.170 真机 153355 实测
  // `hlSum_G = +1710 / 7145 px` ⇒ chroma_G = **+0.239**（归一化域），直接把
  // `refavg_G + chroma` 抬到 1.0 以上 ⇒ `MAX` 把粉色高光的 G 顶起来 ⇒ **灰块**。
  // 同一结论早在 0.12.124（真机 151713）就量到过：chroma=0 → R/G 0.996（中性），
  // 启用 → 1.131。当时用自创 allSat 绕过去了，0.12.170 的归一化域 opposed 让
  // "三通道全饱和 ⇒ refavg=1 ⇒ 中性白" 自然成立，不再需要 this 补偿。
  // A/B 依据（10-29 15:33-15:36 三张同场景）：有 HL 有灰块 / 关 HL 块消失。
  // 0.12.179：保持**停用**（0.12.171 结论不变，本轮离线复算给出更强理由）：
  // WB 域实测该补偿会把粉云的 G 顶到 1.55（R/G 0.84、偏绿），而真实缺口只有 0.08。
  // 天空品红的修复改由下一段的"中性饱和核参考比重建"承担，不依赖本补偿。
  const float* hl_chroma = kHlChromaOff;
  glUniform3f(u_hl_apply_.chroma, hl_chroma[0], hl_chroma[1], hl_chroma[2]);
  // G 通道色度重建（RAW 域，uGRecon=0 时 shader 内整段跳过 = 改动前行为逐位一致）。
  // uGCap 是重建值的上界（场景参考域，防参考色度场噪声把值推爆）。
  glActiveTexture(GL_TEXTURE2);
  glBindTexture(GL_TEXTURE_2D, tex_hl_ref_);
  glUniform1i(u_hl_apply_.ref, 2);
  glActiveTexture(GL_TEXTURE3);
  glBindTexture(GL_TEXTURE_2D, tex_hl_ref2_);   // 中尺度兜底（三级参考的第 2 级）
  glUniform1i(u_hl_apply_.ref2, 3);
  // v0.12.8：三态信息标记（纹理单元 4，R16UI 的 G 通道），用作**重建判据**：
  // 标记 >= 1（有真值）⇒ shader 原样输出、不进 opposed；只有 0 才重建。
  // 未绑（非 bracket / 无 sat-fix 输出）时 uMarkOn = 0 ⇒ 逐位回到改动前行为。
  // 0.12.127：apply 侧不再使用标记（0.12.105 的判据 = 逐通道满阱归一，已自洽）。
  // 标记改为由 ③ chroma 统计消费（排除注入像素）；诊断字段同步改口径：
  // hlMarkOn = 1 表示"本次 chroma 统计按干净样本口径运行"。
  const bool mark_on_hl = (fix_mark != 0);
  dbg_hl_mark_on_ = mark_on_hl ? 1 : 0;
  glActiveTexture(GL_TEXTURE4);
  glBindTexture(GL_TEXTURE_2D, 0);
  glUniform1i(u_hl_apply_.fixmark, 4);
  glUniform1i(u_hl_apply_.markon, 0);
  glActiveTexture(GL_TEXTURE0);   // 回到单元 0（后续若有 glBindTexture 才不会错绑）
  // uGRecon（v0.10.169，来自 FilmParams[49] hl_grecon）：kFsHlApply 内"自创机制"两支
  // （G 三级池化比 + allSat 三通道同步改写）的总开关。
  //   1 = **现有行为逐位不变**（默认值，故本改动不改变默认画质）；
  //   0 = shader 内 `if (uGRecon > 0.5)` 整段跳过 ⇒ apply 支只剩分支① 的 darktable
  //       opposed 外推（= PhotonCamera OpposedGL 口径）。
  // 用途：真机同场景同光线 A/B 判定"过曝边缘品红"是否改善（离线：高光区
  // B/G 1.0856 → 1.0217、R/G 0.9773 → 1.0051）。风险：allSat 在基准片占 G 饱和像素
  // 44.15%，台灯类场景可能退化 ⇒ 默认不开。
  glUniform1f(u_hl_apply_.grecon, grecon_on ? 1.0f : 0.0f);
  // uRbRecon（v0.10.179，来自 FilmParams[57] hl_rb_recon）：clip 过渡带 R/B
  // 满阱比例反推支的总开关。
  //   1 = 启用（替换语义：本通道满阱、其余未满的 photosite 按三级参考比由
  //       G 与对侧通道反推替换，治 CCM 后 4~6px 品红窄带）；
  //   0 = **默认**，shader 内该支判据不满足 ⇒ 旧行为逐位不变。
  // 日志 hlRb 回读 pass 的 hl.rbrecon（= 那次 glUniform 的实际值）。
  glUniform1f(u_hl_apply_.rbrecon, rbrecon ? 1.0f : 0.0f);
  // uGCap = 重建值上界。v0.10.150：1.5 → 2.0。
  // 为什么改：1.5 的隐含假设是"G 最多被低估 50%"，即高光超出 G 满阱不超过 1.5 倍。
  // 这个假设在**逆光大片天空**上成立（该处 G 只需抬 ~1.09），但在"镜面高光/亚像素窄亮线"
  // 上不成立：真机 180318（室内，R/G/B 白点 1.98/1.0/1.76）的窄亮线是 G 饱和而 R/B 未饱和，
  // 离线按引擎公式算 gest 中位 1.74（G+B 类 69.9% 的像素撞 1.5 顶）⇒ 被削平到 1.5 ⇒
  // 修完 R/G 仍 1.17/B/G 1.17（残留品红）。放到 2.0 后该类落点 0.897/1.078（目标 0.906/1.026）。
  // 安全性：撞顶率是**上界是否够**的判据，不是"值会不会爆"——gest 仍经 clamp(·, v, uGCap)，
  // 且 rg/bg 来自未截断样本、真机日志 hlRgG/hlBgG 为 0.993/0.984（参考可信）。
  // 回归：逆光窗场景该值几乎不生效（gest 中位 1.14，撞顶 4.1%），放开后落点不变；
  // 台灯场景 B/G 会略降（1.036→0.979，更贴参考色度）——以真机实测为准。
  glUniform1f(u_hl_apply_.gcap, 2.0f);
  // 全局兜底比例（③.6 算出的场景色度），局部与中尺度都不可用时 shader 内取它
  glUniform2f(u_hl_apply_.refglobal, r.rg_g, r.bg_g);        // 池化比（allSat）
  // 参考场尺寸（手写双线性需要；点采样 + 硬件 LINEAR 在 Mali RGBA16F 上不可靠）
  glUniform2f(u_hl_apply_.refsize, static_cast<float>(rw32), static_cast<float>(rh32));
  glUniform2f(u_hl_apply_.ref2size, static_cast<float>(rw128), static_cast<float>(rh128));
  glDrawArrays(GL_TRIANGLES, 0, 3);

  while (glGetError() != GL_NO_ERROR) {
  }
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  r.blocks_x = mw;
  r.blocks_y = mh;
  r.ms = std::chrono::duration<float, std::milli>(
             std::chrono::steady_clock::now() - t0)
             .count();
  r.ok = true;
  return r;
}

// ── 紫边抑制（v0.10.134）────────────────────────────────────────────────
// 缓冲：tex_df_（全分辨率输出）、tex_df_lab_/tex_df_det_（半分辨率）、
// tex_df_avg_（1/16 网格 chroma 均值，CPU 读回 chromave）。尺寸缓存键 = w/h。
// 色差域中值滤波的资源（v0.10.196）：单个全分辨率 RGBA16F。
bool FrameRenderer::ensureColorSmooth(int w, int h) {
  if (cs_w_ == w && cs_h_ == h && tex_cs_ != 0 && tex_cs_tmp_ != 0) return true;
  if (tex_cs_) {
    glDeleteTextures(1, &tex_cs_);
    glDeleteFramebuffers(1, &fbo_cs_);
    tex_cs_ = fbo_cs_ = 0;
  }
  if (tex_cs_tmp_) {
    glDeleteTextures(1, &tex_cs_tmp_);
    glDeleteFramebuffers(1, &fbo_cs_tmp_);
    tex_cs_tmp_ = fbo_cs_tmp_ = 0;
  }
  if (tex_cm_) {   // 0.12.162：色差域中值
    glDeleteTextures(1, &tex_cm_);
    glDeleteFramebuffers(1, &fbo_cm_);
    tex_cm_ = fbo_cm_ = 0;
  }
  if (tex_cm_tmp_) {
    glDeleteTextures(1, &tex_cm_tmp_);
    glDeleteFramebuffers(1, &fbo_cm_tmp_);
    tex_cm_tmp_ = fbo_cm_tmp_ = 0;
  }
  // @domain: D4 —— 与 tex_dn_/tex_pc3d_ 同域同几何（两张用于 ping-pong 多 pass）
  makeTex(&tex_cs_, &fbo_cs_, GL_RGBA16F, w, h);
  makeTex(&tex_cs_tmp_, &fbo_cs_tmp_, GL_RGBA16F, w, h);
  if (tex_cs_ == 0 || tex_cs_tmp_ == 0) {
    cs_w_ = cs_h_ = 0;
    return false;
  }
  cs_w_ = w;
  cs_h_ = h;
  return true;
}

// 色差域 3×3 中值（v0.10.196，darktable color_smoothing 移植）。
// src = 任意 D4 域纹理（替换档传 tex_pc3d_，普通档传 tex_dn_）；
// 成功时结果在 tex_cs_，调用方改读它。失败返回 false（调用方回落 src，照片优先）。
bool FrameRenderer::passColorSmooth(int dst_w, int dst_h, GLuint src, bool to_tmp) {
  if (!prog_cs_ || src == 0) return false;
  if (!ensureColorSmooth(dst_w, dst_h)) return false;
  const GLuint cs_out_tex = to_tmp ? tex_cs_tmp_ : tex_cs_;
  const GLuint cs_out_fbo = to_tmp ? fbo_cs_tmp_ : fbo_cs_;
  // 单 pass（v0.10.200）：替换值已改为"沿法线两侧色差的平均"（判据保证两侧中性），
  // 不再依赖中值的迭代扩窗 —— 第二次迭代的两侧探测点会被第一次改写污染，反而
  // 削弱判据。v0.10.199 的 2-pass 中值方案已废弃。
  const int kPasses = 1;
  GLuint in = src;
  for (int i = 0; i < kPasses; ++i) {
    const bool last = (i == kPasses - 1);
    glBindFramebuffer(GL_FRAMEBUFFER, last ? cs_out_fbo : (to_tmp ? fbo_cs_ : fbo_cs_tmp_));
    glViewport(0, 0, dst_w, dst_h);
    glUseProgram(prog_cs_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, in);
    glUniform1i(u_cs_.tex, 0);
    glUniform2i(u_cs_.size, dst_w, dst_h);
  // 梯度门：gL = |∇L|·255（线性域）。伪彩只在强边缘 ⇒ 门设在 3~10
  // （低于 3 视为平坦区不动；10 以上全量中值）。
  // 离线定标（090738/090727/084716 三张真机 D4）：gL∈[6,16] 与 [3,10] 的
  // 伪彩压制量**逐项相同**（紫向 P50 45→10.6 / 蓝向 19.5→10.6），但改写像素
  // 从 7.3% 降到 4.1% ⇒ 取更紧的门（同样效果、更少改动）。
    // ── 梯度门 v0.10.203：**回到 6/16**（v0.10.200 曾提到 20/50，真机判死）──
    // 200 用 20/50 的后果：紫边的 gL 多在 6~20 段 ⇒ 被挡在 color_smooth 门外
    // ⇒ 紫边只剩 defringe 单路压制（199 是 color_smooth + defringe 双路）⇒
    // **用户实测"紫边大面积回归"**。故本版保持 199 的低门 6/16，让 color_smooth
    // 覆盖紫边与蓝绿伪彩，同时靠"两侧一致性判据"避免 199 那种"边缘变淡"。
    glUniform1f(u_cs_.grad_lo, 6.0f);
    glUniform1f(u_cs_.grad_hi, 16.0f);
    // v0.10.204 多尺度两侧一致性（见 shader 头注释的实测依据）：
    //   尺度 [3,6,12,18] 覆盖 PC3D 摊开后的 12~90px 宽色带；
    //   sideK 0.7（0.35 时带内仅 1/11 命中，0.7 时 6/11；全图蓝边覆盖 16.8%→43.1%）；
    //   uChromaMin=0.12 相对亮度下限（等价显示域 色度/亮度 ≈ 12%，低于此视为中性不动）。
    glUniform1f(u_cs_.side_k, 0.5f);    // min(两侧) < 0.5×中心（排除真实色度边缘）
    glUniform1f(u_cs_.peak_k, 1.05f);    // 中心 > 1.05×max(两侧)（"局部色度峰"，实测最优）
    glUniform1f(u_cs_.chroma_min, 0.12f);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    in = last ? cs_out_tex : (to_tmp ? tex_cs_ : tex_cs_tmp_);
  }
  glActiveTexture(GL_TEXTURE0);
  while (glGetError() != GL_NO_ERROR) {
  }
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  return true;
}

// 0.12.164（诊断）：亮度剖面 dump —— 4 条水平线 × 64 点，3 位 hex 编码。
// 为什么需要：真机 10:02 样片的阴影边缘有一条 6px 的亮度平台，而离线链
//（RCD + 恒等 LSC + 无 HL）复现不出；D4 离线是平滑的、PC3D 也不改 G。
// 直接把**真机的 D4 与 film 输出**剖面写进日志，一次拍摄就能定位平台在哪一层。
std::string FrameRenderer::profDump(GLuint fbo, GLuint tex, int w, int h) {
  std::string out;
  if (fbo == 0 || tex == 0 || w <= 0 || h <= 0) return out;
  glBindFramebuffer(GL_FRAMEBUFFER, fbo);
  std::vector<uint16_t> row(static_cast<size_t>(w) * 4);
  char buf[8];
  for (int k = 0; k < 4; ++k) {
    const int y = std::min(h - 1, (h * (k * 25 + 12)) / 100);
    glReadPixels(0, y, w, 1, GL_RGBA, GL_HALF_FLOAT, row.data());
    if (k) out.push_back('|');
    for (int i = 0; i < 64; ++i) {
      const int x = std::min(w - 1, (w * i) / 64);
      const float r = halfToFloat(row[static_cast<size_t>(x) * 4 + 0]);
      const float g = halfToFloat(row[static_cast<size_t>(x) * 4 + 1]);
      const float b = halfToFloat(row[static_cast<size_t>(x) * 4 + 2]);
      const float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
      const int q = std::max(0, std::min(4095, static_cast<int>(l * 4095.0f + 0.5f)));
      std::snprintf(buf, sizeof(buf), (i ? ",%03x" : "%03x"), q);
      out.append(buf);
    }
  }
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  return out;
}

// 0.12.165：D4 层大半径 USM —— 见 kFsUsm 头部。1/8 box 低频 + 相对增益。
bool FrameRenderer::passUsm(int w, int h, GLuint src) {
  dbg_usm_on_ = 0;
  if (w <= 0 || h <= 0 || src == 0 || prog_usm_ == 0 || prog_usm_down_ == 0)
    return false;
  const int lw = std::max(1, (w + 7) / 8), lh = std::max(1, (h + 7) / 8);
  const auto ensure = [&](GLuint* t, GLuint* f, int tw, int th) {
    if (*t && *f) return true;
    glGenTextures(1, t);
    glBindTexture(GL_TEXTURE_2D, *t);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, tw, th, 0, GL_RGBA,
                 GL_HALF_FLOAT, nullptr);
    glGenFramebuffers(1, f);
    glBindFramebuffer(GL_FRAMEBUFFER, *f);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                           *t, 0);
    const GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return st == GL_FRAMEBUFFER_COMPLETE;
  };
  if (usm_w_ != w || usm_h_ != h) {
    if (tex_usm_) glDeleteTextures(1, &tex_usm_);
    if (fbo_usm_) glDeleteFramebuffers(1, &fbo_usm_);
    tex_usm_ = fbo_usm_ = 0;
    usm_w_ = w; usm_h_ = h;
  }
  if (lc1_w_ != lw || lc1_h_ != lh) {
    if (tex_lc1_) glDeleteTextures(1, &tex_lc1_);
    if (fbo_lc1_) glDeleteFramebuffers(1, &fbo_lc1_);
    tex_lc1_ = fbo_lc1_ = 0;
    lc1_w_ = lw; lc1_h_ = lh;
  }
  if (!ensure(&tex_usm_, &fbo_usm_, w, h) || !ensure(&tex_lc1_, &fbo_lc1_, lw, lh))
    return false;
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_lc1_);
  glViewport(0, 0, lw, lh);
  glUseProgram(prog_usm_down_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, src);
  glUniform1i(u_usm_down_.tex, 0);
  glUniform2i(u_usm_down_.size, w, h);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_usm_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_usm_);
  glBindTexture(GL_TEXTURE_2D, src);
  glUniform1i(u_usm_.tex, 0);
  glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, tex_lc1_);
  glUniform1i(u_usm_.low, 1);
  glActiveTexture(GL_TEXTURE0);
  glUniform2i(u_usm_.size, w, h);
  glUniform2i(u_usm_.low_size, lw, lh);
  glUniform1f(u_usm_.k, 1.0f);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  dbg_usm_on_ = 1;
  return true;
}

// 0.12.162：色差域 3×3 中值（darktable color_smoothing 原版）—— 见 kFsColorMedian。
// 跑 2 次（darktable 的 npasses；离线实测同场景紫像素 5.86% → 4.15%，
// 平均色度只降 2.5%）。输入任意 D4 域纹理，输出 tex_cm_。
bool FrameRenderer::passColorMedian(int dst_w, int dst_h, GLuint src) {
  if (dst_w <= 0 || dst_h <= 0 || src == 0 || prog_cm_ == 0) return false;
  const auto ensure = [&](GLuint* tex, GLuint* fbo) {
    if (*tex != 0) return true;
    glGenTextures(1, tex);
    glBindTexture(GL_TEXTURE_2D, *tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, dst_w, dst_h, 0, GL_RGBA,
                 GL_HALF_FLOAT, nullptr);
    glGenFramebuffers(1, fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, *fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                           *tex, 0);
    const GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return st == GL_FRAMEBUFFER_COMPLETE;
  };
  if (cm_w_ != dst_w || cm_h_ != dst_h) {
    if (tex_cm_) glDeleteTextures(1, &tex_cm_);
    if (tex_cm_tmp_) glDeleteTextures(1, &tex_cm_tmp_);
    if (fbo_cm_) glDeleteFramebuffers(1, &fbo_cm_);
    if (fbo_cm_tmp_) glDeleteFramebuffers(1, &fbo_cm_tmp_);
    tex_cm_ = tex_cm_tmp_ = fbo_cm_ = fbo_cm_tmp_ = 0;
    cm_w_ = dst_w;
    cm_h_ = dst_h;
  }
  if (!ensure(&tex_cm_, &fbo_cm_) || !ensure(&tex_cm_tmp_, &fbo_cm_tmp_))
    return false;
  GLuint in = src;
  for (int i = 0; i < 2; ++i) {
    const bool last = (i == 1);
    glBindFramebuffer(GL_FRAMEBUFFER, last ? fbo_cm_ : fbo_cm_tmp_);
    glViewport(0, 0, dst_w, dst_h);
    glUseProgram(prog_cm_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, in);
    glUniform1i(u_cm_.tex, 0);
    glUniform2i(u_cm_.size, dst_w, dst_h);
    glUniform1f(u_cm_.chroma_min, 0.12f);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    in = last ? tex_cm_ : tex_cm_tmp_;
  }
  glActiveTexture(GL_TEXTURE0);
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  return true;
}

// ── HDR gainmap（v0.11.50）────────────────────────────────────────────
bool FrameRenderer::ensureGainmap(int w, int h) {
  // gainmap 是 base 的 1/2 边长（与 UltraHdrEncoder 的 kPcDownsample=2 同口径）
  const int gw = (w + 1) / 2, gh = (h + 1) / 2;
  if (gainmap_w_ == gw && gainmap_h_ == gh && tex_gainmap_ != 0) return true;
  if (tex_gainmap_) {
    glDeleteTextures(1, &tex_gainmap_);
    glDeleteFramebuffers(1, &fbo_gainmap_);
    tex_gainmap_ = fbo_gainmap_ = 0;
  }
  // RGBA8：ES 3.0 保证可渲染 + 可 readback（见 kFsGainmap 头注释）
  makeTex(&tex_gainmap_, &fbo_gainmap_, GL_RGBA8, gw, gh);
  if (tex_gainmap_ == 0) {
    gainmap_w_ = gainmap_h_ = 0;
    return false;
  }
  gainmap_w_ = gw;
  gainmap_h_ = gh;
  return true;
}

bool FrameRenderer::passGainmap(int w, int h, GLuint hdr_tex, GLuint sdr_tex) {
  if (!prog_gainmap_ || hdr_tex == 0 || sdr_tex == 0) return false;
  if (!ensureGainmap(w, h)) return false;
  // ⚠ **必须保存/恢复 FBO 与 viewport**（v0.11.50 真机事故）：
  // 调用点紧跟 `passOutput`，而 passOutput 把 fbo_cap_ 绑上后**故意不恢复** ——
  // 后面的 `glReadPixels` 就从"当前绑定的 FBO"读成片。本函数若把绑定改成
  // fbo_gainmap_ 再收尾成 0，glReadPixels 会去读**默认帧缓冲** ⇒ 成片全黑
  //（真机实测：avgLuma=0、bytes 343KB 的纯黑 JPEG）。
  GLint prev_fbo = 0, prev_vp[4] = {0, 0, 0, 0};
  glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prev_fbo);
  glGetIntegerv(GL_VIEWPORT, prev_vp);
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_gainmap_);
  glViewport(0, 0, gainmap_w_, gainmap_h_);
  glUseProgram(prog_gainmap_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, hdr_tex);
  glUniform1i(u_gainmap_.hdr, 0);
  glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, sdr_tex);
  glUniform1i(u_gainmap_.sdr, 1);
  glUniform2f(u_gainmap_.src_size, static_cast<float>(w), static_cast<float>(h));
  glUniform1i(u_gainmap_.math, g_gainmap_math.load());
  // P4：scene 域中灰锚（本张 ae_p50_，与 uHdr=同一 film_src 测得；9593 在 passFilm 前赋值、
  // 这里在其后使用 ⇒ 同一次 submit 内先写后读，无跨张时序）。预览链不跑 gainmap，
  // ae_p50_ 恒 0 的路径不存在；兜底钳到 1e-4（纯黑场景）。
  glUniform1f(u_gainmap_.scene_mid, std::max(ae_p50_, 1e-4f));
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glActiveTexture(GL_TEXTURE0);
  while (glGetError() != GL_NO_ERROR) {
  }
  // 恢复调用点的绑定（见函数头注释：不恢复会让成片读成默认帧缓冲 = 全黑）
  glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(prev_fbo));
  glViewport(prev_vp[0], prev_vp[1], prev_vp[2], prev_vp[3]);
  return true;
}

bool FrameRenderer::readGainmap(std::vector<unsigned char>* out) {
  if (!out || tex_gainmap_ == 0 || gainmap_w_ <= 0 || gainmap_h_ <= 0) return false;
  const size_t n = static_cast<size_t>(gainmap_w_) * gainmap_h_;
  out->assign(n, 0);
  // 读 R 通道即可（shader 里 G/B 同值）；用 GL_RGBA 保证组合在所有驱动上都可用，
  // 读回后再抽 R —— 直接 GL_RED 在某些 Mali 上不支持（项目既有结论：8bit 读回走 RGBA）。
  std::vector<unsigned char> rgba(n * 4);
  GLint prev_fbo = 0;
  glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prev_fbo);
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_gainmap_);
  glReadPixels(0, 0, gainmap_w_, gainmap_h_, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
  glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(prev_fbo));
  const GLenum err = glGetError();
  if (err != GL_NO_ERROR) {
    while (glGetError() != GL_NO_ERROR) {
    }
    return false;
  }
  for (size_t i = 0; i < n; ++i) (*out)[i] = rgba[i * 4];
  // 统计（供 raw.render 日志）：min/max/mean 直接回答"gainmap 有没有内容"
  double sum = 0.0;
  unsigned char mn = 255, mx = 0;
  for (size_t i = 0; i < n; ++i) {
    const unsigned char v = (*out)[i];
    mn = std::min(mn, v);
    mx = std::max(mx, v);
    sum += v;
  }
  dbg_gm_min_ = static_cast<float>(mn) / 255.0f;
  dbg_gm_max_ = static_cast<float>(mx) / 255.0f;
  dbg_gm_mean_ = static_cast<float>(sum / static_cast<double>(n)) / 255.0f;
  // ⚠ **必须在这里独立输出**（v0.11.53 教训）：`raw.render` 的日志在本函数**之前**就已经
  // 输出了（native 侧先返回成片字节，Kotlin 才在回调里调 readGainmap），所以把统计塞进
  // raw.render 只会得到全 0 —— 真机实测 `gmMax` 恒 0，完全辨别不出"场景没高光"还是"统计没生效"。
  // 判据：`gmMax≈0.5` ⇒ HDR/SDR 比值恒 1（场景无内容）；`>0.9` ⇒ 接近 4 倍上限。
  Logger::instance().log(LogLevel::kInfo, "engine", "gainmap.readback",
                         "{\"gw\":" + std::to_string(gainmap_w_) +
                             ",\"gh\":" + std::to_string(gainmap_h_) +
                             ",\"gmMath\":" + std::to_string(g_gainmap_math.load()) +
                             ",\"gmMin\":" + jnum(dbg_gm_min_, 4) +
                             ",\"gmMax\":" + jnum(dbg_gm_max_, 4) +
                             ",\"gmMean\":" + jnum(dbg_gm_mean_, 4) + "}");
  return true;
}

bool FrameRenderer::ensureDefringe(int w, int h) {
  const int hw = (w + 1) / 2, hh = (h + 1) / 2;
  const int aw = (hw + 15) / 16, ah = (hh + 15) / 16;
  if (df_w_ == w && df_h_ == h && tex_df_ != 0 && tex_df_lab_ != 0 &&
      tex_df_det_ != 0 && tex_df_avg_ != 0) {
    return true;
  }
  if (tex_df_) {
    glDeleteTextures(1, &tex_df_);
    glDeleteFramebuffers(1, &fbo_df_);
    tex_df_ = fbo_df_ = 0;
  }
  if (tex_df_lab_) {
    glDeleteTextures(1, &tex_df_lab_);
    glDeleteFramebuffers(1, &fbo_df_lab_);
    tex_df_lab_ = fbo_df_lab_ = 0;
  }
  if (tex_df_det_) {
    glDeleteTextures(1, &tex_df_det_);
    glDeleteFramebuffers(1, &fbo_df_det_);
    tex_df_det_ = fbo_df_det_ = 0;
  }
  if (tex_df_avg_) {
    glDeleteTextures(1, &tex_df_avg_);
    glDeleteFramebuffers(1, &fbo_df_avg_);
    tex_df_avg_ = fbo_df_avg_ = 0;
  }
  // @domain: D4 —— defringe 输出（与 tex_dn_ 同域同几何）
  makeTex(&tex_df_, &fbo_df_, GL_RGBA16F, w, h);
  // @domain: A7
  makeTex(&tex_df_lab_, &fbo_df_lab_, GL_RGBA16F, hw, hh);
  // @domain: A7
  makeTex(&tex_df_det_, &fbo_df_det_, GL_RGBA16F, hw, hh);
  // @domain: A7 —— defringe 局部模糊基线（Lab 域）
  makeTex(&tex_df_avg_, &fbo_df_avg_, GL_RGBA16F, aw, ah);
  if (tex_df_ == 0 || tex_df_lab_ == 0 || tex_df_det_ == 0 || tex_df_avg_ == 0) {
    df_w_ = df_h_ = 0;   // 分配失败 → 调用方保持 P4 输出（照片优先红线）
    return false;
  }
  df_w_ = w;
  df_h_ = h;
  return true;
}

// ── PC ESD3D2 完整移植（v0.10.175）────────────────────────────────────────
// 下列常数 = PC 的 Tunable 默认值，**照抄不换算**（ESD3D2.java:29-55）：
//   noiseToKernelSize 24.0 / noiseTarget 1/256 / minSize 7 / maxSize 21
//   MOIRE 1.5 / LUMA 0.8
// 尺度结构 = ESD3D2.Run（ESD3D2.java:121-176）：scale 判定 → 低分辨率趟
// （MOIRE = 0）→ guided upsample → 全分辨率第二趟（MOIRE = 1.5）。
constexpr float kPc3dNoiseToKernel = 24.0f;
constexpr float kPc3dNoiseTarget = 1.0f / 256.0f;
constexpr int kPc3dMinSize = 7;
constexpr int kPc3dMaxSize = 21;
constexpr float kPc3dMoire = 1.5f;
constexpr float kPc3dLuma = 0.8f;

bool FrameRenderer::passPc3d(int dst_w, int dst_h, const NoiseCoef& nc,
                             const FilmParams& p, GLuint in_src) {
  // v0.10.205：输入源（0 = tex_scene_，逐位等于改动前）
  const GLuint pc_src = (in_src != 0) ? in_src : tex_scene_;
  dbg_pc3d_on_ = 0;
  dbg_pc3d_m_ = 0;
  dbg_pc3d_scale_ = 1;
  dbg_pc3d_k_ = 0.f;
  if (!prog_pc3d_ || !prog_pc3d_down_ || !prog_pc3d_up_) return false;
  if (dst_w <= 0 || dst_h <= 0) return false;

  // ① 噪声模型 → sigY 的 (斜率, 截距)。PC 的 NOISES/NOISEO 是**方差模型**；
  //    权重形状 w = 1 − d²/(d² + sigY) 的两边同为平方量纲 ⇒ 照抄即自洽。
  //    luma 权重用 PC 的 (0.25,0.5,0.25)（esd3d2_steered.glsl:64）。
  const float sigA = 0.25f * nc.p4a[0] + 0.5f * nc.p4a[1] + 0.25f * nc.p4a[2];
  const float sigB = 0.25f * nc.p4b[0] + 0.5f * nc.p4b[1] + 0.25f * nc.p4b[2];
  // ② 核尺寸（照抄 ESD3D2.java:89-94，逐字）：
  //      noiseMpy  = max((S+O)/noiseTarget, 1e-7)
  //      kernelSize = 1 + sqrt(noiseMpy)·noiseToKernelSize
  //      msize = min(minSize + (int)kernelSize − (int)kernelSize%2, maxSize)
  const double noiseMpy =
      std::max(static_cast<double>(sigA + sigB) / kPc3dNoiseTarget, 1e-7);
  const double kernelSize = 1.0 + std::sqrt(noiseMpy) * kPc3dNoiseToKernel;
  int msize = std::min(kPc3dMinSize + static_cast<int>(kernelSize) -
                           static_cast<int>(kernelSize) % 2,
                       kPc3dMaxSize);
  // [55] pc3d_msize > 0 = 真机 A/B 强制核尺寸（选项，代价表见 REPORT）；
  // 0 = 自动（PC 公式）。奇数化 + 夹到 PC 的 [minSize, maxSize] 内。
  if (p.pc3d_msize > 0.f) {
    int forced = static_cast<int>(p.pc3d_msize + 0.5f);
    if (forced % 2 == 0) ++forced;
    msize = std::min(std::max(forced, kPc3dMinSize), kPc3dMaxSize);
  }
  // ③ 尺度（照抄 ESD3D2.java:126-129）：
  //      N = sqrt(0.5·S + O)；scaleF = clamp(N/noiseTarget, 1, 4)；scale = round(scaleF)
  const double nSqrt = std::sqrt(0.5 * sigA + sigB);
  const float scaleF =
      std::min(std::max(static_cast<float>(nSqrt / kPc3dNoiseTarget), 1.f), 4.f);
  int scale = static_cast<int>(scaleF + 0.5f);
  if (scale < 1) scale = 1;
  if (scale > 4) scale = 4;

  // ④ 缓冲（惰性；[54] = 0 时一个都不建）
  const int lw = std::max(1, dst_w / scale), lh = std::max(1, dst_h / scale);
  if (tex_pc3d_ == 0 || pc3d_w_ != dst_w || pc3d_h_ != dst_h) {
    if (tex_pc3d_) {
      glDeleteTextures(1, &tex_pc3d_);
      glDeleteFramebuffers(1, &fbo_pc3d_);
      glDeleteTextures(1, &tex_pc3d_t1_);
      glDeleteFramebuffers(1, &fbo_pc3d_t1_);
    }
    tex_pc3d_ = fbo_pc3d_ = tex_pc3d_t1_ = fbo_pc3d_t1_ = 0;
    // @domain: D4 —— PC3D 全分辨率输出（与 tex_scene_ 同域同几何）
    makeTex(&tex_pc3d_, &fbo_pc3d_, GL_RGBA16F, dst_w, dst_h);
    makeTex(&tex_pc3d_t1_, &fbo_pc3d_t1_, GL_RGBA16F, dst_w, dst_h);
    pc3d_w_ = dst_w;
    pc3d_h_ = dst_h;
    pc3d_lw_ = pc3d_lh_ = 0;   // 尺度可能变，低分辨率缓冲一并失效
  }
  if (scale > 1 && (tex_pc3d_lo_[0] == 0 || pc3d_lw_ != lw || pc3d_lh_ != lh)) {
    for (int i = 0; i < 2; ++i) {
      if (tex_pc3d_lo_[i]) {
        glDeleteTextures(1, &tex_pc3d_lo_[i]);
        glDeleteFramebuffers(1, &fbo_pc3d_lo_[i]);
      }
      tex_pc3d_lo_[i] = fbo_pc3d_lo_[i] = 0;
      makeTex(&tex_pc3d_lo_[i], &fbo_pc3d_lo_[i], GL_RGBA16F, lw, lh);
    }
    pc3d_lw_ = lw;
    pc3d_lh_ = lh;
  }
  if (tex_pc3d_ == 0 || tex_pc3d_t1_ == 0 ||
      (scale > 1 && (tex_pc3d_lo_[0] == 0 || tex_pc3d_lo_[1] == 0))) {
    return false;   // 分配失败 → 调用方沿用 tex_scene_（照片优先红线）
  }

  const GLenum bufs1[1] = {GL_COLOR_ATTACHMENT0};
  auto runPc3d = [&](GLuint src, GLuint dst_fbo, int w, int h, int ms,
                     float moire, float sA, float sB, GLuint orig,
                     int mask_on, int down) {
    glBindFramebuffer(GL_FRAMEBUFFER, dst_fbo);
    glDrawBuffers(1, bufs1);
    glViewport(0, 0, w, h);
    glUseProgram(prog_pc3d_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, src);
    glUniform1i(u_pc3d_.tex, 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, lsc_tex_);
    glUniform1i(u_pc3d_.lsc, 1);
    glUniform2i(u_pc3d_.lsc_size, lsc_w_, lsc_h_);
    glUniform2i(u_pc3d_.size, w, h);
    glUniform1i(u_pc3d_.k, (ms - 1) / 2);
    glUniform1f(u_pc3d_.kernel, static_cast<float>(kernelSize));
    glUniform1f(u_pc3d_.sig_a, sA);
    glUniform1f(u_pc3d_.sig_b, sB);
    glUniform1f(u_pc3d_.moire, moire);
    glUniform1f(u_pc3d_.luma, kPc3dLuma);
    if (u_pc3d_.orig >= 0) {
      glActiveTexture(GL_TEXTURE2);
      glBindTexture(GL_TEXTURE_2D, orig);
      glUniform1i(u_pc3d_.orig, 2);
      glActiveTexture(GL_TEXTURE0);
    }
    if (u_pc3d_.maskon >= 0) glUniform1i(u_pc3d_.maskon, mask_on);
    // 0.12.181：饱和掩码（单元 3）—— 关时 shader 回落 alpha 路径（逐位不变）
    if (u_pc3d_.satmask >= 0) {
      glActiveTexture(GL_TEXTURE3);
      glBindTexture(GL_TEXTURE_2D, tex_satm_dst_ ? tex_satm_dst_ : tex_satm_);
      glUniform1i(u_pc3d_.satmask, 3);
      glActiveTexture(GL_TEXTURE0);
    }
    if (u_pc3d_.satmaskon >= 0)
      glUniform1i(u_pc3d_.satmaskon,
                  (satmask_enabled_ && tex_satm_dst_ != 0) ? 1 : 0);
    if (u_pc3d_.down >= 0) glUniform1i(u_pc3d_.down, down);
    if (u_pc3d_.fullsize >= 0) glUniform2i(u_pc3d_.fullsize, dst_w, dst_h);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glActiveTexture(GL_TEXTURE0);
  };

  // ⑤ 第一趟（ESD3D2.java:135-155）：scale > 1 时在**降采样域**降噪再引导上采样。
  //    scale == 1 时直接全分辨率（原文同一分支）。
  float sigA1 = sigA, sigB1 = sigB;
  if (scale > 1) {
    // PC：ESD3DRun(..., scaleF * 0.75f) ⇒ shader 内 NoiseS/scale、NoiseO/scale
    const float sc = std::max(scaleF * 0.75f, 1e-3f);
    sigA1 = sigA / sc;
    sigB1 = sigB / sc;
    const int stride = std::max(1, scale / 2);
    const float blur = static_cast<float>(scale) * 0.5f;
    // gaussdown：tex_scene_ → tex_pc3d_lo_[0]
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_pc3d_lo_[0]);
    glDrawBuffers(1, bufs1);
    glViewport(0, 0, lw, lh);
    glUseProgram(prog_pc3d_down_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, pc_src);
    glUniform1i(u_pc3d_down_.tex, 0);
    glUniform2i(u_pc3d_down_.size, dst_w, dst_h);
    glUniform1i(u_pc3d_down_.resize, scale);
    glUniform1i(u_pc3d_down_.stride, stride);
    glUniform1f(u_pc3d_down_.sigma, blur / static_cast<float>(stride));
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glActiveTexture(GL_TEXTURE0);
    // 核尺寸也随缩放后的噪声模型（PC 的 ESD3DRun 重算 MSIZE）
    const double mp1 =
        std::max(static_cast<double>(sigA1 + sigB1) / kPc3dNoiseTarget, 1e-7);
    const double ks1 = 1.0 + std::sqrt(mp1) * kPc3dNoiseToKernel;
    int ms1 = std::min(kPc3dMinSize + static_cast<int>(ks1) -
                           static_cast<int>(ks1) % 2,
                       kPc3dMaxSize);
    if (p.pc3d_msize > 0.f) ms1 = msize;
    // 首趟（低分辨率）：按 mask 排除高光样本 —— 不把高光色度摊进 1/2 域
    runPc3d(tex_pc3d_lo_[0], fbo_pc3d_lo_[1], lw, lh, ms1, 0.0f, sigA1, sigB1,
            pc_src, 1, scale);
    // guided upsample：低分辨率结果 + 全分辨率引导 → tex_pc3d_t1_
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_pc3d_t1_);
    glDrawBuffers(1, bufs1);
    glViewport(0, 0, dst_w, dst_h);
    glUseProgram(prog_pc3d_up_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex_pc3d_lo_[1]);
    glUniform1i(u_pc3d_up_.low, 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, pc_src);
    glUniform1i(u_pc3d_up_.guide_hi, 1);
    glUniform2i(u_pc3d_up_.low_size, lw, lh);
    glUniform2i(u_pc3d_up_.hi_size, dst_w, dst_h);
    glUniform1i(u_pc3d_up_.r, scale);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glActiveTexture(GL_TEXTURE0);
    dbg_pc3d_scale_ = scale;
  } else {
    // ── 0.12.145：scale==1 时**这一趟就是唯一的平滑趟**，mask 排除必须同样启用 ──
    // 0.12.143 只给"低分辨率首趟"传了 mask_on=1，而 scale==1 走的是这个分支，
    // 传的是 0 ⇒ 排除从未生效。真机日志（21:25:32）的 `pc3dSc:1` 就是证据：
    // 那一版的光晕宽度完全没有变化。语义与低分辨率首趟一致：只在这一趟排。
    runPc3d(pc_src, fbo_pc3d_t1_, dst_w, dst_h, msize, 0.0f, sigA, sigB,
            pc_src, 1, 1);
  }
  // ⑥ 第二趟（ESD3D2.java:161-162）：全分辨率 + MOIRE = 1.5（伪彩强度逻辑在这里
  //    才真正开火；第一趟 MOIRE = 0 ⇒ sigZ = sigY）。
  // 第二趟（全分辨率）：不排除 —— 保持 132 的降噪强度（亮部色噪不放大）
  runPc3d(tex_pc3d_t1_, fbo_pc3d_, dst_w, dst_h, msize, kPc3dMoire, sigA, sigB,
          0, 0, 1);

  dbg_pc3d_on_ = 1;
  dbg_pc3d_m_ = msize;
  dbg_pc3d_k_ = static_cast<float>(kernelSize);
  return true;
}

// 四段：down+Lab+门 → 检测 → 1/16 均值（读回 chromave）→ 替换。
// 输入 tex_dn_（P4 输出），输出 tex_df_。任何一步失败返回 false（调用方回落）。
bool FrameRenderer::passDefringe(int dst_w, int dst_h, float df_gate, GLuint src) {
  dbg_df_gate_ = df_gate > 0.f ? df_gate : 0.f;   // v0.10.173（日志字段 dfG）
  dbg_df_cavg_ = 0.f;
  if (!prog_df_down_ || !prog_df_det_ || !prog_df_avg_ || !prog_df_apply_) {
    return false;
  }
  if (!ensureDefringe(dst_w, dst_h)) return false;
  const GLuint df_src_ = (src != 0) ? src : tex_dn_;   // 替换档传 tex_pc3d_
  const int hw = (dst_w + 1) / 2, hh = (dst_h + 1) / 2;
  const int aw = (hw + 15) / 16, ah = (hh + 15) / 16;
  // ① 2×2 box down + 线性 RGB → Lab + 色相门
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_df_lab_);
  glViewport(0, 0, hw, hh);
  glUseProgram(prog_df_down_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, df_src_);
  glUniform1i(u_df_down_.tex, 0);
  glUniform2i(u_df_down_.size, dst_w, dst_h);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  // ② 11×11 box 局部基线 + chroma（门已乘入）
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_df_det_);
  glViewport(0, 0, hw, hh);
  glUseProgram(prog_df_det_);
  glBindTexture(GL_TEXTURE_2D, tex_df_lab_);
  glUniform1i(u_df_det_.src, 0);
  glUniform2i(u_df_det_.half, hw, hh);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  // ③ chroma 均值网格 + CPU 读回（RGBA16F + GL_FLOAT 组合 X300 已验证）
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_df_avg_);
  glViewport(0, 0, aw, ah);
  glUseProgram(prog_df_avg_);
  glBindTexture(GL_TEXTURE_2D, tex_df_det_);
  glUniform1i(u_df_avg_.src, 0);
  glUniform2i(u_df_avg_.size, hw, hh);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  float cavg = 0.f;
  {
    std::vector<float> grid(static_cast<size_t>(aw) * ah * 4);
    glReadPixels(0, 0, aw, ah, GL_RGBA, GL_FLOAT, grid.data());
    double s = 0.0;
    for (size_t i = 0; i < grid.size(); i += 4) s += grid[i];
    cavg = static_cast<float>(s / (aw * ah));
    if (!(cavg > 0.f)) {   // 读回异常（NaN/全零）→ 放弃本帧（fail-safe）
      glBindFramebuffer(GL_FRAMEBUFFER, 0);
      return false;
    }
  }
  dbg_df_cavg_ = cavg;
  dbg_cs_ok_ = (prog_cs_ != 0) ? 1 : 0;   // v0.10.198 诊断：shader 是否链接成功
  // ④ 命中替换（L 零损失；未命中像素原样直通）
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_df_);
  glViewport(0, 0, dst_w, dst_h);
  glUseProgram(prog_df_apply_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, df_src_);
  glUniform1i(u_df_apply_.tex, 0);
  glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, tex_df_det_);
  glUniform1i(u_df_apply_.det, 1);
  glUniform2i(u_df_apply_.size, dst_w, dst_h);
  glUniform2i(u_df_apply_.half, hw, hh);
  // ── v0.10.194：阈值全部改为**相对 uChromaAvg 的倍数**（离线真跑定标）──────
  // 193 真机紫线原样保留的根因：阈值写成绝对值 5.0（**显示域**离线量出的数），
  // 而 shader 跑的是**线性域**（linMid≈0.07、pHi≈3.3）⇒ 恒 false ⇒ 全未命中。
  // 离线定标（tools/engine-shader-offline.py 真跑本 shader，873 线性域）：
  //   强紫边  |B−R| = 7.2/16.3/27.0 ×cavg（P10/P50/P90）
  //           base_std = 12.7/15.7/19.5 ×cavg
  //   全图    |B−R| P90 = 0.79×cavg、base_std P90 = 2.06×cavg
  //   紫象限（B>R>G）判据本身：暖色误伤 0、绿向误伤 0
  // ⇒ thr = 3×、std_thr = 2×：247/247 强紫边命中，误伤 0。
  glUniform1f(u_df_apply_.chroma_avg, cavg);
  glUniform1f(u_df_apply_.thresh_k, 0.8f);     // v0.10.195: 3.0 → 0.8（真机 cavg 偏大）
  glUniform1f(u_df_apply_.thresh_min, 0.012f); // 绝对下限（暗场景防过检）
  glUniform1f(u_df_apply_.thresh_max, 0.06f);  // 绝对上限（防 cavg 异常大失效）
  glUniform1f(u_df_apply_.std_k, 1.2f);        // v0.10.195: 2.0 → 1.2
  glUniform1f(u_df_apply_.std_max, 0.10f);     // std 绝对上限（紫边 std 实测 ≈0.21）
  // 梯度下限：gL = |∇L|·255（线性域）。全图 P90 = 4.97、P99 = 55.8；
  // 强紫边 P10 = 6.75。取 5.0：高于全图 P90（排除平坦区），覆盖紫边主体。
  glUniform1f(u_df_apply_.grad_min, 5.0f);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glActiveTexture(GL_TEXTURE0);
  while (glGetError() != GL_NO_ERROR) {
  }
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  return true;
}

// 横向色差判据（v0.10.170），单位 = **全分辨率像素**：
//   kCaMinShift = 角部位移下限 —— 低于半个像素的横向色差在成片上看不出来，
//     而"看起来像 LCA"的噪声拟合恰好落在这一区间（离线：基准样片 B 通道
//     原始估计 0.13~0.41px，全是噪声，判定后必须不动作）。
//   kCaConsist = 内外半区一致性容差 —— 真实 LCA 由**同一个 k** 支配所有半径
//     （位移 ∝ r），噪声/局部伪影拟合的 k 在内（r<0.6R）与外（≥0.6R）半区互不
//     相容。这是"零假设下自然拒绝"的判据，不依赖任何经验阈值标定。
//   kCaMaxShift = 单通道位移上限（1/8 估计图的梯度尺度 8px ⇒ 可测 ±4px）。
static constexpr float kCaMinShift = 0.5f;
static constexpr float kCaConsist = 0.5f;
static constexpr float kCaMaxShift = 4.0f;

// ── 横向色差校正（v0.10.170，lateral chromatic aberration）──────────────────
// 作用域 = **D3**（RCD 去马赛克后、passCcm 前，相机 RGB）。选择依据：
//   ① 色差是**纯几何**位移 —— 此域通道尚未被 CCM 混合，改 R/B 不会污染颜色矩阵
//      的输入；CCM 之后（D4/场景线性）再改会与已混合的通道打架。
//   ② G 作参考通道**逐位不动**（PhotonCamera 与 darktable 的共同口径：G 采样最密、
//      信噪比最高，动它会把整幅画面的亮度结构一起带走）。
//   ③ 位置在 diagStatsTex(D3-camrgb) **之后** ⇒ 该诊断基线的既有语义不变。
// 估计（无镜头配置文件）：1/8 box 降采样 → readPixels → CPU 一维径向 LK。
// 估计失败 / 位移低于门限 ⇒ 返回 false ⇒ 调用方仍读 tex_work_（逐位等于改动前）。
bool FrameRenderer::passCaCorrect(int dst_w, int dst_h, float strength) {
  dbg_ca_applied_ = 0;
  dbg_ca_kr_ = dbg_ca_kb_ = 0.f;
  dbg_ca_shift_r_ = dbg_ca_shift_b_ = 0.f;
  dbg_ca_ms_ = 0.f;
  // ⚠ AMaZE 的计时/自检**不能在这里清零**：passCaCorrect 在 AMaZE 之后、
  // 日志之前执行，清了就会把 amazeMs/amazeR 全报成 0（0.12.148 的诊断假象）。
  // 它们由 finishStillChain 帧首清零。
  // 判据与上限见文件作用域的 kCaMinShift / kCaConsist / kCaMaxShift。
  if (!prog_ca_ds_ || !prog_ca_corr_) return false;
  if (!ensureCaTargets(dst_w, dst_h)) return false;
  const auto t0 = std::chrono::steady_clock::now();
  const int dw = ca_ds_w_, dh = ca_ds_h_;
  // ① 1/8 box 降采样（估计输入；8×8 均值同时压低估计噪声）
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_ca_ds_);
  glViewport(0, 0, dw, dh);
  glUseProgram(prog_ca_ds_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, tex_work_);
  glUniform1i(u_ca_ds_.tex, 0);
  glUniform2i(u_ca_ds_.size, dst_w, dst_h);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  // ② CPU 读回（RGBA16F + GL_FLOAT，与 passDefringe 同组合，X300 已验证）
  std::vector<float> ds(static_cast<size_t>(dw) * dh * 4);
  glReadPixels(0, 0, dw, dh, GL_RGBA, GL_FLOAT, ds.data());
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  float kr = 0.f, kb = 0.f, kraw_r = 0.f, kraw_b = 0.f;
  if (!estimateCaRadial(ds.data(), dw, dh, dst_w, dst_h, &kr, &kb, &kraw_r,
                        &kraw_b)) {
    return false;
  }
  const float rmax = 0.5f * std::sqrt(static_cast<float>(dst_w) * dst_w +
                                      static_cast<float>(dst_h) * dst_h);
  const float kr_s = kr * strength, kb_s = kb * strength;
  const float sr = kr_s * rmax, sb = kb_s * rmax;   // 画面角部的实际位移（px）
  dbg_ca_kr_ = kr;
  dbg_ca_kb_ = kb;
  dbg_ca_kraw_r_ = kraw_r;
  dbg_ca_kraw_b_ = kraw_b;
  dbg_ca_shift_r_ = sr;
  dbg_ca_shift_b_ = sb;
  // 门限：两个通道都没通过判据 ⇒ 判定本帧无横向色差 ⇒ 整段跳过。
  // 这是"默认开也安全"的**唯一**依据（对无 LCA 的场景零动作、零副作用）。
  if (std::fabs(sr) < kCaMinShift && std::fabs(sb) < kCaMinShift) return false;
  // ③ 全分辨率径向位移校正（只重采样 R/B，G 原样）
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_ca_);
  glViewport(0, 0, dst_w, dst_h);
  glUseProgram(prog_ca_corr_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, tex_work_);
  glUniform1i(u_ca_corr_.tex, 0);
  glUniform2i(u_ca_corr_.size, dst_w, dst_h);
  glUniform2f(u_ca_corr_.center, 0.5f * dst_w, 0.5f * dst_h);
  glUniform1f(u_ca_corr_.kr, kr_s);
  glUniform1f(u_ca_corr_.kb, kb_s);
  glUniform1f(u_ca_corr_.maxshift, kCaMaxShift);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glActiveTexture(GL_TEXTURE0);
  while (glGetError() != GL_NO_ERROR) {
  }
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  dbg_ca_applied_ = 1;
  dbg_ca_ms_ = std::chrono::duration<float, std::milli>(
                   std::chrono::steady_clock::now() - t0).count();
  return true;
}

bool FrameRenderer::ensureCaTargets(int w, int h) {
  const int dw = (w + 7) / 8, dh = (h + 7) / 8;
  if (ca_w_ == w && ca_h_ == h && tex_ca_ != 0 && tex_ca_ds_ != 0) return true;
  if (tex_ca_) {
    glDeleteTextures(1, &tex_ca_);
    glDeleteFramebuffers(1, &fbo_ca_);
    tex_ca_ = fbo_ca_ = 0;
  }
  if (tex_ca_ds_) {
    glDeleteTextures(1, &tex_ca_ds_);
    glDeleteFramebuffers(1, &fbo_ca_ds_);
    tex_ca_ds_ = fbo_ca_ds_ = 0;
  }
  makeTex(&tex_ca_, &fbo_ca_, GL_RGBA16F, w, h);
  makeTex(&tex_ca_ds_, &fbo_ca_ds_, GL_RGBA16F, dw, dh);
  ca_w_ = w;
  ca_h_ = h;
  ca_ds_w_ = dw;
  ca_ds_h_ = dh;
  return tex_ca_ != 0 && tex_ca_ds_ != 0;
}

// 逐环带一维径向 Lucas-Kanade。把 darktable cacorrect（RAW 域分块位移场 + 二次
// 曲面拟合）的模型退化成**单一径向系数**：横向色差的位移场沿径向、中心为 0、
// 随半径单调 ⇒ d = k·r 已是完整模型（这也是它比逐像素估计稳健一个量级的原因：
// 自由度从 2N 降到 2，噪声无法伪装成位移）。
// 对 ch ∈ {R,B}：minimize Σ (k·r·(∇G·r̂) − (G−ch))²
//   ⇒ k = Σ[r·g_r·(G−ch)] / Σ[r²·g_r²]，g_r = ∇G·r̂。
// ∇G 代替 ∇ch 是 PhotonCamera 与 darktable 的共同口径。It 先做 3×3 高通：
// LK 只对"梯度相关的分量"敏感，缓变色偏（整块偏紫）不该被读成位移。
// 只用强梯度像素（能量 ≥ P50）：LCA 只在高对比边缘显现，平坦区只有噪声。
bool FrameRenderer::estimateCaRadial(const float* ds, int dw, int dh, int full_w,
                                     int full_h, float* kr, float* kb,
                                     float* raw_kr, float* raw_kb) {
  if (!ds || !kr || !kb || !raw_kr || !raw_kb || dw < 16 || dh < 16) return false;
  const float sx = static_cast<float>(full_w) / static_cast<float>(dw);
  const float sy = static_cast<float>(full_h) / static_cast<float>(dh);
  const float cx = 0.5f * static_cast<float>(full_w);
  const float cy = 0.5f * static_cast<float>(full_h);
  const int npx = dw * dh;
  std::vector<float> gx(npx, 0.f), gy(npx, 0.f), dR(npx, 0.f), dB(npx, 0.f),
      energy(npx, 0.f);
  // 读回有限性检查（NaN/inf = 读回异常 → 放弃本帧，fail-safe）
  for (int k = 0; k < npx; ++k) {
    const float r = ds[k * 4], g = ds[k * 4 + 1], b = ds[k * 4 + 2];
    if (!std::isfinite(r) || !std::isfinite(g) || !std::isfinite(b)) return false;
    dR[k] = g - r;   // It_R = G − R
    dB[k] = g - b;
  }
  for (int j = 1; j < dh - 1; ++j) {
    for (int i = 1; i < dw - 1; ++i) {
      const int k = j * dw + i;
      gx[k] = 0.5f * (ds[(k + 1) * 4 + 1] - ds[(k - 1) * 4 + 1]) / sx;
      gy[k] = 0.5f * (ds[(k + dw) * 4 + 1] - ds[(k - dw) * 4 + 1]) / sy;
      energy[k] = gx[k] * gx[k] + gy[k] * gy[k];
    }
  }
  // It 的 3×3 box 高通（去掉缓变色偏，只留与梯度同尺度的分量）
  std::vector<float> dRh(npx, 0.f), dBh(npx, 0.f);
  for (int j = 1; j < dh - 1; ++j) {
    for (int i = 1; i < dw - 1; ++i) {
      const int k = j * dw + i;
      float sr = 0.f, sb = 0.f;
      for (int jj = -1; jj <= 1; ++jj) {
        for (int ii = -1; ii <= 1; ++ii) {
          const int kk = k + jj * dw + ii;
          sr += dR[kk];
          sb += dB[kk];
        }
      }
      dRh[k] = dR[k] - sr * (1.f / 9.f);
      dBh[k] = dB[k] - sb * (1.f / 9.f);
    }
  }
  // 梯度能量分位数（P50）—— 只用强边缘像素
  std::vector<float> es(energy);
  const size_t mid = es.size() / 2;
  std::nth_element(es.begin(), es.begin() + mid, es.end());
  const float ethr = es[mid];
  if (!(ethr > 0.f)) return false;   // 全图无梯度（纯平坦/纯黑）⇒ 不动作
  // 三组累加：0 = 全体、1 = 内半区（r < 0.6R）、2 = 外半区。
  // 内/外一致性 = "径向模型成立"的必要条件（见 kCaConsist 的说明）。
  double numR[3] = {0.0, 0.0, 0.0}, numB[3] = {0.0, 0.0, 0.0},
         den[3] = {0.0, 0.0, 0.0};
  long used[3] = {0, 0, 0};
  const float rmax = 0.5f * std::sqrt(static_cast<float>(full_w) * full_w +
                                      static_cast<float>(full_h) * full_h);
  const float rsplit = 0.6f * rmax;
  for (int j = 1; j < dh - 1; ++j) {
    for (int i = 1; i < dw - 1; ++i) {
      const int k = j * dw + i;
      if (energy[k] < ethr) continue;
      const float dx = (i + 0.5f) * sx - cx;
      const float dy = (j + 0.5f) * sy - cy;
      const float r = std::sqrt(dx * dx + dy * dy);
      if (r < 1.f) continue;   // 中心位移恒 0，无信息（dt/PC 同样不估计中心）
      const float gr = (gx[k] * dx + gy[k] * dy) / r;   // ∇G·r̂
      const float wgt = r * gr;                          // = r·g_r
      const int half = (r < rsplit) ? 1 : 2;
      const double wr = static_cast<double>(wgt) * dRh[k];
      const double wb = static_cast<double>(wgt) * dBh[k];
      const double w2 = static_cast<double>(wgt) * wgt;
      numR[0] += wr;
      numB[0] += wb;
      den[0] += w2;
      ++used[0];
      numR[half] += wr;
      numB[half] += wb;
      den[half] += w2;
      ++used[half];
    }
  }
  if (used[0] < 500 || den[0] <= 1e-9) return false;   // 有效边缘太少 ⇒ 不动作
  const float kraw[2] = {static_cast<float>(numR[0] / den[0]),
                         static_cast<float>(numB[0] / den[0])};
  if (!std::isfinite(kraw[0]) || !std::isfinite(kraw[1])) return false;
  *raw_kr = kraw[0];
  *raw_kb = kraw[1];
  // 通道级判定：① 角部位移够大（kCaMinShift）② 内外半区一致（kCaConsist）。
  // 任一不满足 ⇒ 该通道不动作（k 记 0），日志里 caKraw 仍保留原始估计供归因。
  float kacc[2] = {0.f, 0.f};
  for (int c = 0; c < 2; ++c) {
    if (std::fabs(kraw[c] * rmax) < kCaMinShift) continue;
    if (den[1] <= 1e-9 || den[2] <= 1e-9) continue;
    const float kI = static_cast<float>((c == 0 ? numR[1] : numB[1]) / den[1]);
    const float kO = static_cast<float>((c == 0 ? numR[2] : numB[2]) / den[2]);
    if (!std::isfinite(kI) || !std::isfinite(kO)) continue;
    const float mx = std::max(std::fabs(kI), std::fabs(kO));
    if (std::fabs(kO - kI) > kCaConsist * mx) continue;   // 非径向 ⇒ 不动作
    kacc[c] = kraw[c];
  }
  *kr = kacc[0];
  *kb = kacc[1];
  return true;   // 两通道都为 0 = 判定无横向色差（调用方据门限跳过整段）
}

// RCD P1：方向场（vh + pq 统计），raw 原始方向全屏
void FrameRenderer::passRcdDir(int w, int h, int cfa,
                               const float black_phase[4], float white_level,
                               const float gains[3], bool mosaic) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_dir_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_rcd_dir_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, raw_tex_);
  glUniform1i(u_rcd_dir_.tex, 0);
  if (u_rcd_dir_.mos >= 0) {
    glActiveTexture(GL_TEXTURE1);
    // RAW 域高光重建成功时读重建后的 mosaic（hr_src_override_），否则原行为
    glBindTexture(GL_TEXTURE_2D, hr_src_override_ ? hr_src_override_ : tex_mos_);
    glUniform1i(u_rcd_dir_.mos, 1);
  }
  if (u_rcd_dir_.mosaic >= 0) glUniform1f(u_rcd_dir_.mosaic, mosaic ? 1.0f : 0.0f);
  glUniform2i(u_rcd_dir_.size, raw_w_, raw_h_);
  glUniform1i(u_rcd_dir_.cfalut, cfaLutOf(cfa));
  glUniform4f(u_rcd_dir_.black, black_phase[0], black_phase[1], black_phase[2],
              black_phase[3]);
  glUniform1f(u_rcd_dir_.white, white_level);
  glUniform3f(u_rcd_dir_.gain, gains[0], gains[1], gains[2]);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// RCD P2：G@RB（比值修正 + 方向混合 + 限幅），输出全图 G 到 tex_g_
void FrameRenderer::passRcdG(int w, int h, int cfa,
                             const float black_phase[4], float white_level,
                             const float gains[3], bool mosaic, float g_orig) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_g_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_rcd_g_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, raw_tex_);
  if (u_rcd_g_.mos >= 0) {
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, hr_src_override_ ? hr_src_override_ : tex_mos_);
    glUniform1i(u_rcd_g_.mos, 1);
  }
  if (u_rcd_g_.mosaic >= 0) glUniform1f(u_rcd_g_.mosaic, mosaic ? 1.0f : 0.0f);
  glUniform1i(u_rcd_g_.tex, 0);
  glActiveTexture(GL_TEXTURE2);
  glBindTexture(GL_TEXTURE_2D, tex_dir_);
  glUniform1i(u_rcd_g_.dir, 2);
  glUniform2i(u_rcd_g_.size, raw_w_, raw_h_);
  glUniform1i(u_rcd_g_.cfalut, cfaLutOf(cfa));
  glUniform4f(u_rcd_g_.black, black_phase[0], black_phase[1], black_phase[2],
              black_phase[3]);
  glUniform1f(u_rcd_g_.white, white_level);
  glUniform3f(u_rcd_g_.gain, gains[0], gains[1], gains[2]);
  if (u_rcd_g_.gorig >= 0) glUniform1f(u_rcd_g_.gorig, g_orig);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// RCD P3：色度填充（+ LSC）→ **D3**（相机 RGB：LSC 后、CCM 前，写 tex_work_）。
// 第 4a 期（S14）域拆分：CCM 已移出本 pass（见 kFsCcm / passCcm），本 pass 只负责
// D2→D3；输出几何仍与 passRaw 一致（kVertRot 旋转，uST 已在 init 置单位阵）。
void FrameRenderer::passRcdChroma(int dst_w, int dst_h, int rot, int cfa,
                                  const float black_phase[4],
                                  float white_level, const float gains[3],
                                  const float* ccm, bool mosaic, float cd_fix,
                                  float g_orig) {
  // 第 4a 期：D3 走 tex_work_（fbo_work_）—— RCD→CCM 区间它是空闲的，CCM 消费完
  // D3 之后 passFilm 才覆盖它；不新增纹理（零新增显存）。
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_work_);
  glViewport(0, 0, dst_w, dst_h);
  glUseProgram(prog_rcd_chroma_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, raw_tex_);
  if (u_rcd_chroma_.mos >= 0) {
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, hr_src_override_ ? hr_src_override_ : tex_mos_);
    glUniform1i(u_rcd_chroma_.mos, 1);
  }
  if (u_rcd_chroma_.mosaic >= 0)
    glUniform1f(u_rcd_chroma_.mosaic, mosaic ? 1.0f : 0.0f);
  if (u_rcd_chroma_.cd_fix >= 0) glUniform1f(u_rcd_chroma_.cd_fix, cd_fix);
  if (u_rcd_chroma_.gorig >= 0) glUniform1f(u_rcd_chroma_.gorig, g_orig);
  glActiveTexture(GL_TEXTURE2);
  glBindTexture(GL_TEXTURE_2D, tex_dir_);
  glActiveTexture(GL_TEXTURE3);
  glBindTexture(GL_TEXTURE_2D, tex_g_);
  glUniform1i(u_rcd_chroma_.tex, 0);
  glUniform1i(u_rcd_chroma_.dir, 2);
  glUniform1i(u_rcd_chroma_.g, 3);
  glUniform2i(u_rcd_chroma_.size, raw_w_, raw_h_);
  glUniform1i(u_rcd_chroma_.rot, rot);
  glUniform2f(u_rcd_chroma_.scale, 1.0f, 1.0f);
  glUniform1i(u_rcd_chroma_.cfalut, cfaLutOf(cfa));
  glUniform4f(u_rcd_chroma_.black, black_phase[0], black_phase[1],
              black_phase[2], black_phase[3]);
  glUniform1f(u_rcd_chroma_.white, white_level);
  glUniform3f(u_rcd_chroma_.gain, gains[0], gains[1], gains[2]);
  // LSC 增益纹理（单元 4；0/1/2/3 已被 raw/mos/dir/g 占用）
  glActiveTexture(GL_TEXTURE4);
  glBindTexture(GL_TEXTURE_2D, lsc_tex_);
  glUniform1i(u_rcd_chroma_.lsc, 4);
  glUniform2i(u_rcd_chroma_.lsc_size, lsc_w_, lsc_h_);
  glActiveTexture(GL_TEXTURE0);
  // 行主序 CCM 必须 transpose=GL_TRUE（v0.5.20 品红根因，见 passRaw 同款注释）
  glUniformMatrix3fv(u_rcd_chroma_.ccm, 1, GL_TRUE, ccm);
  // 0.12.133（方案 B 块 1）：RAW 饱和掩码（单元 5，0-4 已被占用）。
  // 未跑 HL 时 uMaskOn=0 ⇒ 逐位回到旧行为（alpha 恒 0，PC3D 不收手）。
  const bool hl_mask_on = (hl_mask_tex_ != 0);
  glActiveTexture(GL_TEXTURE5);
  glBindTexture(GL_TEXTURE_2D, hl_mask_on ? hl_mask_tex_ : 0);
  glUniform1i(u_rcd_chroma_.hlmask, 5);
  glUniform1i(u_rcd_chroma_.maskon, hl_mask_on ? 1 : 0);
  glActiveTexture(GL_TEXTURE0);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glActiveTexture(GL_TEXTURE0);
}

// P3 拆双 pass（v0.10.180，[58] rcd_p3_split）。
// 3a：对角段填充（= 原版 STEP 4.2）→ tex_rcd_a_（源几何，WB 域 RGB 无 LSC）。
// 输入与 passRcdChroma 相同（raw/mos/dir/g），仅 G 位是占位值（3b 重算）。
// 几何与 passRcdDir/passRcdG 同构：vs_plain_ + gl_FragCoord 源几何直写。
void FrameRenderer::passRcdChromaA(int w, int h, int cfa,
                                   const float black_phase[4],
                                   float white_level, const float gains[3],
                                   bool mosaic, float cd_fix) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_rcd_a_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_rcd_chroma_a_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, raw_tex_);
  if (u_rcd_chroma_a_.mos >= 0) {
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, hr_src_override_ ? hr_src_override_ : tex_mos_);
    glUniform1i(u_rcd_chroma_a_.mos, 1);
  }
  if (u_rcd_chroma_a_.mosaic >= 0)
    glUniform1f(u_rcd_chroma_a_.mosaic, mosaic ? 1.0f : 0.0f);
  if (u_rcd_chroma_a_.cd_fix >= 0) glUniform1f(u_rcd_chroma_a_.cd_fix, cd_fix);
  glActiveTexture(GL_TEXTURE2);
  glBindTexture(GL_TEXTURE_2D, tex_dir_);
  glActiveTexture(GL_TEXTURE3);
  glBindTexture(GL_TEXTURE_2D, tex_g_);
  glUniform1i(u_rcd_chroma_a_.tex, 0);
  glUniform1i(u_rcd_chroma_a_.dir, 2);
  glUniform1i(u_rcd_chroma_a_.g, 3);
  glUniform2i(u_rcd_chroma_a_.size, raw_w_, raw_h_);
  glUniform1i(u_rcd_chroma_a_.cfalut, cfaLutOf(cfa));
  glUniform4f(u_rcd_chroma_a_.black, black_phase[0], black_phase[1],
              black_phase[2], black_phase[3]);
  glUniform1f(u_rcd_chroma_a_.white, white_level);
  glUniform3f(u_rcd_chroma_a_.gain, gains[0], gains[1], gains[2]);
  if (u_rcd_chroma_a_.satclip >= 0)
    glUniform3f(u_rcd_chroma_a_.satclip, gains[0], gains[1], gains[2]);
  glActiveTexture(GL_TEXTURE0);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// 3b：G 位轴向填充（= 原版 STEP 4.3，交叉轴从 3a 的 tex_rcd_a_ 读已填充色）
// → 乘 LSC 写 tex_work_（成片几何 D3）。几何与 kFsRcdChroma 逐字同款
//（kVertRot + uST 单位阵（init 置）+ uScale=1 + frag 的 tuv 翻转）。
void FrameRenderer::passRcdChromaB(int dst_w, int dst_h, int rot, int cfa,
                                   const float gains[3]) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_work_);
  glViewport(0, 0, dst_w, dst_h);
  glUseProgram(prog_rcd_chroma_b_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, tex_rcd_a_);
  glUniform1i(u_rcd_chroma_b_.rcd_a, 0);
  glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, tex_dir_);
  glUniform1i(u_rcd_chroma_b_.dir, 1);
  glActiveTexture(GL_TEXTURE2);
  glBindTexture(GL_TEXTURE_2D, tex_g_);
  glUniform1i(u_rcd_chroma_b_.g, 2);
  glUniform2i(u_rcd_chroma_b_.size, raw_w_, raw_h_);
  glUniform1i(u_rcd_chroma_b_.rot, rot);
  glUniform2f(u_rcd_chroma_b_.scale, 1.0f, 1.0f);
  glUniform1i(u_rcd_chroma_b_.cfalut, cfaLutOf(cfa));
  // LSC 增益纹理（单元 3；0/1/2 已被 rcd_a/dir/g 占用）
  glActiveTexture(GL_TEXTURE3);
  glBindTexture(GL_TEXTURE_2D, lsc_tex_);
  glUniform1i(u_rcd_chroma_b_.lsc, 3);
  glUniform2i(u_rcd_chroma_b_.lsc_size, lsc_w_, lsc_h_);
  if (u_rcd_chroma_b_.satclip >= 0)
    glUniform3f(u_rcd_chroma_b_.satclip, gains[0], gains[1], gains[2]);
  // 0.12.133（方案 B 块 1）：RAW 饱和掩码（单元 4，0-3 已被占用）。
  // p 是**源 mosaic 坐标**⇒ p/3 直接落在块网格上；alpha 随 RGB 一起经 fbo 旋转。
  const bool hl_mask_on_b = (hl_mask_tex_ != 0);
  glActiveTexture(GL_TEXTURE4);
  glBindTexture(GL_TEXTURE_2D, hl_mask_on_b ? hl_mask_tex_ : 0);
  glUniform1i(u_rcd_chroma_b_.hlmask, 4);
  glUniform1i(u_rcd_chroma_b_.maskon, hl_mask_on_b ? 1 : 0);
  glActiveTexture(GL_TEXTURE0);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// P3.5（第 4a 期 S14）：**D3 → D4** 独立 CCM pass（tex_work_ → tex_scene_）。
// 纯逐像素映射：读 tex_work_（D3：相机 RGB，LSC 后、CCM 前）→ 乘 CCM → 写
// tex_scene_（D4：场景线性）。几何与 passChromaDenoise 同构（kVertRot + uST 单位阵，
// frag 用 gl_FragCoord 定位），输出尺寸 = 输入尺寸，无旋转/无重采样。
// 只在 RCD 档（demosaic == 1）调用；双线性档仍由 passRaw 内联 CCM（kFsRaw，本期不动）。
// v0.10.170：src 非 0 时改读它（横向色差校正输出 tex_ca_）；0 = 原行为（tex_work_）。
void FrameRenderer::passCcm(int dst_w, int dst_h, const float* ccm, GLuint src) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_scene_);
  glViewport(0, 0, dst_w, dst_h);
  glUseProgram(prog_ccm_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, src ? src : tex_work_);
  glUniform1i(u_ccm_.tex, 0);
  glUniform2i(u_ccm_.size, dst_w, dst_h);
  // 行主序 CCM 必须 transpose=GL_TRUE（v0.5.20 品红根因，见 passRaw 同款注释）
  glUniformMatrix3fv(u_ccm_.ccm, 1, GL_TRUE, ccm);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// ── HDR 曝光融合 P2（0.12.77）──────────────────────────────────────────
bool FrameRenderer::ensureHdrSideTargets(int dst_w, int dst_h) {
  if (dst_w <= 0 || dst_h <= 0) return false;
  if (hdr_side_dst_w_ == dst_w && hdr_side_dst_h_ == dst_h) return true;
  if (tex_hdr_side_) glDeleteTextures(1, &tex_hdr_side_);
  if (tex_hdr_zero_) glDeleteTextures(1, &tex_hdr_zero_);
  tex_hdr_side_ = tex_hdr_zero_ = 0;
  // 侧帧 D4 与 zero D4 拷贝：RGBA16F、成片几何（与 tex_scene_ 同规格 ×2）
  const auto mkD4 = [&](GLuint* tex) {
    glGenTextures(1, tex);
    glBindTexture(GL_TEXTURE_2D, *tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, dst_w, dst_h, 0, GL_RGBA,
                 GL_HALF_FLOAT, nullptr);
  };
  mkD4(&tex_hdr_side_);
  mkD4(&tex_hdr_zero_);
  hdr_side_dst_w_ = dst_w;
  hdr_side_dst_h_ = dst_h;
  return true;
}

void FrameRenderer::passHdrMertens(int dst_w, int dst_h, float ev_side) {
  // prog 编译/link 失败（可选能力）：直接返回，成片 = zero 链（J10 语义）。
  // zero 的 D4 拷贝（tex_hdr_zero_）由 finishStillChain 的调用序列完成 ——
  // Mertens 的输入必须与渲染目标（fbo_scene_ = tex_scene_）分离，同纹理既采样
  // 又当 attachment 是 feedback loop（GL 未定义行为；PC 同构全独立）。
  if (!prog_hdr_mertens_ || !tex_hdr_side_ || !tex_hdr_zero_) return;
  const auto t0 = std::chrono::steady_clock::now();
  // 融合：读 tex_hdr_zero_（zero）+ tex_hdr_side_（侧帧）→ 写 fbo_scene_。
  // GL 状态纪律（2026-10-20 教训）：本 pass 是链中间件 —— 自己绑 fbo_scene_
  // 画完**不乱动**（后续 P4/PC3D 各自绑自己的目标），viewport 必须设全
  // （passCcm 用的同一视口）。
  glViewport(0, 0, dst_w, dst_h);
  glUseProgram(prog_hdr_mertens_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, tex_hdr_zero_);
  glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, tex_hdr_side_);
  if (u_hdr_mertens_.zero >= 0) glUniform1i(u_hdr_mertens_.zero, 0);
  if (u_hdr_mertens_.side >= 0) glUniform1i(u_hdr_mertens_.side, 1);
  if (u_hdr_mertens_.size >= 0)
    glUniform2i(u_hdr_mertens_.size, dst_w, dst_h);
  // PC DEFAULT_MERTENS_CONTRAST/SATURATION/EXPOSURE_WEIGHT = 1.0（A/B 口留）
  if (u_hdr_mertens_.wc >= 0) glUniform1f(u_hdr_mertens_.wc, 1.0f);
  if (u_hdr_mertens_.ws >= 0) glUniform1f(u_hdr_mertens_.ws, 1.0f);
  if (u_hdr_mertens_.we >= 0) glUniform1f(u_hdr_mertens_.we, 1.0f);
  // PC uExposureScale 语义：侧帧/zero 的曝光乘积比（normalizeMertensExposureProducts
  // 的 [1/32,32] 钳制在 shader 内 clamp）—— deghostScoreAt 用它算期望亮度。
  if (u_hdr_mertens_.scale >= 0)
    glUniform1f(u_hdr_mertens_.scale, std::pow(2.0f, ev_side));
  glDrawArrays(GL_TRIANGLES, 0, 3);
  // 纹理单元复位（后续 pass 从 unit 0 起绑，不复位会撞已有绑定）
  glActiveTexture(GL_TEXTURE0);
  const float ms = std::chrono::duration<float, std::milli>(
                       std::chrono::steady_clock::now() - t0)
                       .count();
  Logger::instance().log(
      LogLevel::kInfo, "engine", "hdr.mertens",
      "{\"evSide\":" + std::to_string(ev_side) + ",\"ms\":" +
          std::to_string(ms) + ",\"w\":" + std::to_string(dst_w) +
          ",\"h\":" + std::to_string(dst_h) + "}");
}

// ── AMaZE 档（v0.10.172）───────────────────────────────────────────────
// CPU 侧跑 AMaZE 并把结果搬到 GPU。三段：① u16 RAW → 线性化 + WB（与
// kFsRaw/kFsRcd 的 s() 逐字同口径）→ ② AmazeDemosaic（多线程 tile 并行）→
// ③ LSC（WB 域乘，网格双线性同 kFsRaw 的 lscAt 口径）→ 上传 tex_amaze_。
// ⚠ 未完成项：本路径**不经过 P0（RAW 域 bilateral）/ HR 前置**（那两者是 GPU
//   pass）；高 ISO 下本档噪点会多于 RCD 档。见 docs/reports/amaze-REPORT.md「未完成」。
bool FrameRenderer::runAmazeCpu(const ChainCtx& c) {
  const int w = c.w, h = c.h;
  if (!c.amaze_raw || !c.gains || !c.black_phase || w < 32 || h < 32)
    return false;
  // CFA 相位 → 通道（cfaLutOf 的 2bit/相位打包；k=(y&1)*2+(x&1)）
  const int lut = cfaLutOf(c.cfa);
  int pattern[4];
  for (int k = 0; k < 4; ++k) pattern[k] = (lut >> (k << 1)) & 3;
  const uint16_t* raw = static_cast<const uint16_t*>(c.amaze_raw);
  const int stride = c.amaze_stride > 0 ? c.amaze_stride : w;

  // ① 线性化 + WB（黑阶按相位、白阶归一、gains 按通道）
  std::vector<float> cfa(static_cast<size_t>(w) * h);
  for (int y = 0; y < h; ++y) {
    const uint16_t* row = raw + static_cast<size_t>(y) * stride;
    for (int x = 0; x < w; ++x) {
      const int k = ((y & 1) << 1) | (x & 1);
      const float bl = c.black_phase[k];
      const float den = std::max(c.white_level - bl, 1.0f);
      const float lin = std::max((static_cast<float>(row[x]) - bl) / den, 0.0f);
      cfa[static_cast<size_t>(y) * w + x] = lin * c.gains[pattern[k]];
    }
  }
  // ② AMaZE（clip_pt = WB 域最大通道满阱；实测 1.0~8.0 对结果无实质影响）
  std::vector<float> rgb(static_cast<size_t>(w) * h * 3);
  const float clip_pt = std::max(c.gains[0], std::max(c.gains[1], c.gains[2]));
  AmazeDemosaic(cfa.data(), w, h, pattern, clip_pt, rgb.data());
  cfa.clear();
  cfa.shrink_to_fit();
  // ③ LSC（网格双线性；无数据 = 恒等）
  if (c.amaze_lsc && c.lsc_cols > 1 && c.lsc_rows > 1) {
    const int lw = c.lsc_cols, lh = c.lsc_rows;
    for (int y = 0; y < h; ++y) {
      const float gy = ((static_cast<float>(y) + 0.5f) / h) * lh - 0.5f;
      const int by = static_cast<int>(std::floor(gy));
      const float ty = gy - by;
      for (int x = 0; x < w; ++x) {
        const float gx = ((static_cast<float>(x) + 0.5f) / w) * lw - 0.5f;
        const int bx = static_cast<int>(std::floor(gx));
        const float tx = gx - bx;
        float g[3] = {0.f, 0.f, 0.f};
        for (int dy = 0; dy <= 1; ++dy) {
          const int yy = std::min(std::max(by + dy, 0), lh - 1);
          const float wy = dy ? ty : (1.f - ty);
          for (int dx = 0; dx <= 1; ++dx) {
            const int xx = std::min(std::max(bx + dx, 0), lw - 1);
            const float wx = dx ? tx : (1.f - tx);
            const float* s = c.amaze_lsc + (static_cast<size_t>(yy) * lw + xx) * 3;
            const float wgt = wx * wy;
            g[0] += s[0] * wgt;
            g[1] += s[1] * wgt;
            g[2] += s[2] * wgt;
          }
        }
        float* o = rgb.data() + (static_cast<size_t>(y) * w + x) * 3;
        o[0] *= g[0];
        o[1] *= g[1];
        o[2] *= g[2];
      }
    }
  }
  // ④ 上传（源几何；RGB16F 与 lsc_tex_ 同款用法）
  return uploadAmazeTex(rgb.data(), w, h);
}

bool FrameRenderer::uploadAmazeTex(const float* rgb, int w, int h) {
  if (rgb == nullptr || w <= 0 || h <= 0) return false;
  if (tex_amaze_ == 0 || amaze_w_ != w || amaze_h_ != h) {
    if (tex_amaze_) glDeleteTextures(1, &tex_amaze_);
    glGenTextures(1, &tex_amaze_);
    glBindTexture(GL_TEXTURE_2D, tex_amaze_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB16F, w, h, 0, GL_RGB, GL_FLOAT,
                 nullptr);
    amaze_w_ = w;
    amaze_h_ = h;
  }
  glBindTexture(GL_TEXTURE_2D, tex_amaze_);
  glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
  glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
  // 0.12.151：上传走 **GL_HALF_FLOAT**（75MB 而非 150MB）。目标纹理本就是
  // RGB16F，用 GL_FLOAT 上传意味着驱动要对 12.5M 像素做一次 float→half 转换，
  // 真机实测这段是 amazeMs 的大头之一。CPU 侧先转好再传，语义完全等价。
  std::vector<unsigned short> hbuf(static_cast<size_t>(w) * h * 3);
  for (size_t k = 0; k < hbuf.size(); ++k)
    hbuf[k] = grain::floatToHalf(rgb[k]);
  glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGB, GL_HALF_FLOAT,
                  hbuf.data());
  if (glGetError() != GL_NO_ERROR) {
    Logger::instance().log(LogLevel::kError, "engine", "amaze.upload_failed",
                           "{\"w\":" + std::to_string(w) +
                               ",\"h\":" + std::to_string(h) + "}");
    return false;
  }
  return true;
}

// 0.12.147：AMaZE 吃**完整前链**的输出（tex_mos_ = HL 前置 + P0 之后，与 RCD 同源）。
// 为什么读回而不是用 c.amaze_raw：前者含饱和重建与 RAW 域降噪，两者的差异就只剩
// 去马赛克算法本身 —— 这才是"AMaZE 值不值得换"的可归因对比。
// RGBA16F 的 R 通道承载 mosaic（P0 的输出布局），按行块读回以限制峰值内存。
bool FrameRenderer::runAmazeFromMosaic(const ChainCtx& c) {
  const int w = c.w, h = c.h;
  if (w < 32 || h < 32 || tex_mos_ == 0 || fbo_mos_ == 0 || c.gains == nullptr)
    return false;
  const int lut = cfaLutOf(c.cfa);
  int pattern[4];
  for (int k = 0; k < 4; ++k) pattern[k] = (lut >> (k << 1)) & 3;

  const auto tRd0 = std::chrono::steady_clock::now();
  std::vector<float> cfa(static_cast<size_t>(w) * h);
  {
    GLint prev_fbo = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prev_fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_mos_);
    const int rows = 128;
    // ⚠ **必须用 GL_HALF_FLOAT**：ES 3.0 只保证 (GL_RGBA, GL_UNSIGNED_BYTE)，
    // 浮点图像读回依赖扩展 —— X300 上 (GL_RGBA, GL_FLOAT) 读 RGBA16F 拿到的是
    // 无效数据（0.12.147 首次接入时成片**全红**，正是这条）。HALF_FLOAT 是本项目
    // 已在用的组合（logPassDiag 一直这样读 RGBA16F 诊断像素）。
    std::vector<unsigned short> buf(static_cast<size_t>(w) * rows * 4);
    float vmin = 1e30f, vmax = -1e30f;
    bool ok = true;
    for (int y0 = 0; y0 < h; y0 += rows) {
      const int nr = std::min(rows, h - y0);
      glPixelStorei(GL_PACK_ALIGNMENT, 4);
      glReadPixels(0, y0, w, nr, GL_RGBA, GL_HALF_FLOAT, buf.data());
      if (glGetError() != GL_NO_ERROR) { ok = false; break; }
      for (int i = 0; i < nr; ++i) {
        const int y = y0 + i;
        for (int x = 0; x < w; ++x) {
          // P0 的输出是**按通道分量**存的（kFsRawDenoise:
          //   oColor = vec4(ch==0?v:0, ch==1?v:0, ch==2?v:0, 1)）——
          // 每个 photosite 只有自己那个分量有值，其余为 0。必须按 photosite
          // 的通道取，口径与 HL 的 mosAt() = at3(rgb, chanAt(p)) 一致。
          // 读 .r 会让 G/B 位置全部拿到 0 ⇒ 喂给 AMaZE 的是"只有 R 有值"的
          // mosaic ⇒ 成片全红（0.12.148 的回归）。
          const int k = ((y & 1) << 1) | (x & 1);
          const int ch = pattern[k];
          const float v =
              halfToFloat(buf[(static_cast<size_t>(i) * w + x) * 4 + ch]);
          // 0.12.154：**除以该通道 WB 增益**，把输入域还原到 AMaZE 的前提
          // 「所有通道满阱相同」。LibRaw 版写死 initialGain = max(pre_mul)、
          // clip_pt = 1/initialGain —— 那是**标量**；而 tex_mos_ 是逐通道 WB 后
          // 的值，满阱 = gains = (2.23, 1.0, 1.51)。
          // 原来直接传 clip_pt = max(gains) ⇒ clip_pt8 = 1.78，G/B 的饱和值
          // （1.0 / 1.51）永远够不着 ⇒ 高光判据只对 R 生效，G/B 的饱和像素被当成
          // 真实值参与插值 ⇒ 笔记本屏幕糊成一片白（0.12.153 实测）。
          const float ginv = 1.f / std::max(c.gains[ch], 1e-6f);
          const float vn = v * ginv;
          cfa[static_cast<size_t>(y) * w + x] = vn;
          if (vn < vmin) vmin = vn;
          if (vn > vmax) vmax = vn;
        }
      }
    }
    glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(prev_fbo));
    if (!ok) return false;
    // 读回自检：mosaic 必须有真实动态范围。全 0 / NaN ⇒ 口径错了，
    // ⚠ 0.12.154 起这里是**归一化域**（已除 WB 增益）⇒ 正常范围 [~1e-4, 1.0]，
    // 不再是 WB 域的 [~1e-4, 2.23]。
    // 直接失败比交出一张红图好（照片优先红线）。
    if (!(vmax > 1e-6f) || !std::isfinite(vmin) || !std::isfinite(vmax))
      return false;
    dbg_amaze_min_ = vmin;
    dbg_amaze_max_ = vmax;
  }
  const auto tRd1 = std::chrono::steady_clock::now();
  dbg_amaze_rd_ms_ += std::chrono::duration<float, std::milli>(tRd1 - tRd0).count();
  std::vector<float> rgb(static_cast<size_t>(w) * h * 3);
  // 归一化域 ⇒ 满阱 = 1.0（所有通道一致），这正是 AMaZE 的 clip_pt 语义
  const float clip_pt = 1.0f;
  AmazeDemosaic(cfa.data(), w, h, pattern, clip_pt, rgb.data());
  cfa.clear();
  cfa.shrink_to_fit();
  // 回到 WB 域（下游 CCM / LSC / film 全按 WB 域口径）
  for (size_t i = 0, n = static_cast<size_t>(w) * h; i < n; ++i) {
    rgb[i * 3 + 0] *= c.gains[0];
    rgb[i * 3 + 1] *= c.gains[1];
    rgb[i * 3 + 2] *= c.gains[2];
  }
  const auto tCpu1 = std::chrono::steady_clock::now();
  dbg_amaze_cpu_ms_ +=
      std::chrono::duration<float, std::milli>(tCpu1 - tRd1).count();
  // ③ LSC（**必须在这里乘**）：P0 的 kFsRawDenoise 只做黑阶/白阶/WB 增益，
  // 不含 uLsc；LSC 是 RCD 在自己的 shader 里乘的（lscAt(p)）。本路径跳过了
  // RCD，所以不乘 LSC 就等于**整档没有镜头阴影矫正** —— 四角变暗、亮度分布
  // 偏离，Mertens 的融合权重随之失真（0.12.149 的 HDR 观感异常）。
  // 网格双线性，口径与 kFsRaw/kFsRcd 的 lscAt 逐字一致。
  if (c.amaze_lsc && c.lsc_cols > 1 && c.lsc_rows > 1) {
    const int lw = c.lsc_cols, lh = c.lsc_rows;
    for (int y = 0; y < h; ++y) {
      const float gy = ((static_cast<float>(y) + 0.5f) / h) * lh - 0.5f;
      const int by = static_cast<int>(std::floor(gy));
      const float ty = gy - by;
      for (int x = 0; x < w; ++x) {
        const float gx = ((static_cast<float>(x) + 0.5f) / w) * lw - 0.5f;
        const int bx = static_cast<int>(std::floor(gx));
        const float tx = gx - bx;
        float g[3] = {0.f, 0.f, 0.f};
        for (int dy = 0; dy <= 1; ++dy) {
          const int yy = std::min(std::max(by + dy, 0), lh - 1);
          const float wy = dy ? ty : (1.f - ty);
          for (int dx = 0; dx <= 1; ++dx) {
            const int xx = std::min(std::max(bx + dx, 0), lw - 1);
            const float wx = dx ? tx : (1.f - tx);
            const float* s2 = c.amaze_lsc + (static_cast<size_t>(yy) * lw + xx) * 3;
            const float wgt = wx * wy;
            g[0] += s2[0] * wgt;
            g[1] += s2[1] * wgt;
            g[2] += s2[2] * wgt;
          }
        }
        float* o = rgb.data() + (static_cast<size_t>(y) * w + x) * 3;
        o[0] *= g[0];
        o[1] *= g[1];
        o[2] *= g[2];
      }
    }
  }
  const bool up_ok = uploadAmazeTex(rgb.data(), w, h);
  dbg_amaze_up_ms_ += std::chrono::duration<float, std::milli>(
                          std::chrono::steady_clock::now() - tCpu1).count();
  return up_ok;
}

// tex_amaze_（源几何）→ tex_work_（成片几何 D3）：几何口径与 kFsRcdChroma 逐字
// 一致（kVertRot + uST 单位阵 + uScale=1 + frag 的 tuv 翻转），保证与 RCD 档
// 成片方向完全一致。
void FrameRenderer::passAmazeUp(int dst_w, int dst_h, int rot) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_work_);
  glViewport(0, 0, dst_w, dst_h);
  glUseProgram(prog_amaze_up_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, tex_amaze_);
  if (u_amaze_up_.tex >= 0) glUniform1i(u_amaze_up_.tex, 0);
  if (u_amaze_up_.size >= 0) glUniform2i(u_amaze_up_.size, amaze_w_, amaze_h_);
  if (u_amaze_up_.rot >= 0) glUniform1i(u_amaze_up_.rot, rot);
  if (u_amaze_up_.scale >= 0) glUniform2f(u_amaze_up_.scale, 1.0f, 1.0f);
  // 0.12.150：饱和掩码 → D3 的 alpha（下游 CCM/PC3D/color_smooth 都按 alpha 读它）
  const bool has_mask = (hl_mask_tex_ != 0 && hl_mw_ > 0 && hl_mh_ > 0);
  if (u_amaze_up_.hlmask >= 0) {
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, has_mask ? hl_mask_tex_ : 0);
    glUniform1i(u_amaze_up_.hlmask, 1);
    glActiveTexture(GL_TEXTURE0);
  }
  if (u_amaze_up_.maskon >= 0) glUniform1i(u_amaze_up_.maskon, has_mask ? 1 : 0);
  if (u_amaze_up_.masksize >= 0)
    glUniform2i(u_amaze_up_.masksize, std::max(1, hl_mw_), std::max(1, hl_mh_));
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// RAW 静拍端到端：u16 Bayer 上传 → passRaw（黑阶/WB/demosaic/CCM）→
// passFilm/passOutput 汇流胶片链 → RGBA8 读回。旋转/换轴逻辑与 submitStill 一致。
bool FrameRenderer::submitRaw(const void* raw, int w, int h, int row_stride_px,
                              int cfa, const float black_phase[4],
                              int white_level, float neutral_r, float neutral_g,
                              float neutral_b, const float* ccm9,
                              const float* lsc, int lsc_cols, int lsc_rows,
                              int rotation, const FilmParams& p, int demosaic,
                              float noise_strength, const float* noise_model,
                              float noise_sigma, bool nn_dn,
                              std::vector<uint8_t>* out_rgba, bool ten_bit) {
  if (w <= 0 || h <= 0 || !raw || !black_phase) return false;
  // 卡 H2：同 submitStill —— 自检未通过就拒绝 10bit 交付（fail-closed）
  if (ten_bit && !selfCheck10BitOnce()) return false;
  // 0.12.102：单帧链不能继承上一张 burst 的 sat-fix 标记（跨张残留会让 HR 按
  // 上一张的高光标记跳过重建）。burst 入口自己会重设，这里只需清零。
  hr_fix_mark_ = 0;
  dbg_hr_mark_on_ = 0;
  frames_.fetch_add(1, std::memory_order_relaxed);
  // 分段计时（M3 §3.3 出口标准的度量基础）：上传 / pass 链 / 读回
  const auto t0 = std::chrono::steady_clock::now();

  if (ensureRawTex(w, h) == 0) return false;
  if (!uploadRawTex(raw_tex_, raw, w, h, row_stride_px)) return false;
  // 0.12.186：饱和掩码用 CPU 副本（见 FrameRenderer.h 的注释）
  if (p.sat_mask > 0.5f) {
    const auto* rp = static_cast<const uint16_t*>(raw);
    raw_cpu_.assign(static_cast<size_t>(w) * h, 0);
    for (int y = 0; y < h; ++y)
      std::memcpy(&raw_cpu_[static_cast<size_t>(y) * w],
                  rp + static_cast<size_t>(y) * row_stride_px,
                  sizeof(uint16_t) * w);
    raw_cpu_w_ = w;
    raw_cpu_h_ = h;
  }
  const auto t1 = std::chrono::steady_clock::now();

  // U-3：[1..3] × [88][89] 后交给 prepareRawCommon（同一口径，见 wbTempTint 注释）
  float wb_tt[3];
  wbTempTint(p, wb_tt);
  const RawCommon rc =
      prepareRawCommon(neutral_r, neutral_g, neutral_b, wb_tt[0], wb_tt[1],
                       wb_tt[2], ccm9, lsc, lsc_cols, lsc_rows);

  // 实际渲染旋转 = 界面方向 + 传感器旋转（与 submitStill 同一约定）
  const int effective_rot = (rotation + 1) % 4;
  const bool swap_wh = (effective_rot % 2) == 1;
  const int dst_w = swap_wh ? h : w;
  const int dst_h = swap_wh ? w : h;
  if (!ensureTargets(dst_w, dst_h)) return false;
  while (glGetError() != GL_NO_ERROR) {
  }

  const bool rcd = demosaic == 1;
  // C 专项：模型解析（6 float：S_r,S_g,S_b,O_r,O_g,O_b；nullptr/非有限/全 ≤0 = 无效）
  NoiseModel model;
  if (noise_model != nullptr) {
    bool ok = true;
    for (int i = 0; i < 3; ++i) {
      model.S[i] = noise_model[i];
      model.O[i] = noise_model[3 + i];
      if (!std::isfinite(model.S[i]) || !std::isfinite(model.O[i]) ||
          model.S[i] < 0.f || model.O[i] < 0.f)
        ok = false;
    }
    if (!ok) model = NoiseModel{};
  }
  NoiseCoef nc;
  denoiseParams(noise_strength, model, rc.gains, noise_sigma, &nc);
  ChainCtx c;
  c.w = w;
  c.h = h;
  c.dst_w = dst_w;
  c.dst_h = dst_h;
  c.cfa = cfa;
  c.black_phase = black_phase;
  c.white_level = static_cast<float>(white_level);
  c.gains = rc.gains;
  c.ccm = rc.ccm;
  c.p = &p;
  c.effective_rot = effective_rot;
  c.rcd = rcd;
  // E4-3 颗粒：成片种子的内容指纹（u16 RAW ⇒ 字节数 = w·h·2）。用**原始 RAW** 而不是
  // 成片像素：RAW 是输入侧的不变量，重跑同一次拍摄必然得到同一指纹 ⇒ 导出可复现。
  c.grain_fp = grainFingerprint(raw, static_cast<size_t>(w) * h * 2);
  // AMaZE 档（demosaic == 2，v0.10.172）：CPU 侧从这份 u16 RAW 直接跑 AMaZE
  c.amaze = demosaic == 2;
  if (c.amaze) {
    c.amaze_raw = raw;
    c.amaze_stride = row_stride_px;
    c.amaze_lsc = lsc;
  }
  // 0.12.147：AMaZE 档同样要进 P0/P4（否则两档对比混入降噪差异）
  c.dn = (rcd || demosaic == 2) && noise_strength > 0.f;
  c.dn_k = noise_strength;
  c.nc = nc;
  c.nm = model;
  c.ns = nc.ns_typ;
  c.ns_raw = nc.ns_typ;
  c.lsc_ok = rc.lsc_ok;
  c.lsc_cols = lsc_cols;
  c.lsc_rows = lsc_rows;
  c.upload_ms = std::chrono::duration<float, std::milli>(t1 - t0).count();
  c.nn = nn_dn;
  // 卡 H2：交付档（10bit HEIF）—— 成片 target / readback / dither 三处同源切换
  c.ten_bit = ten_bit;
  // NN 诊断字段每次合成都要清零（成员跨张残留会把上一张的读数记到这一张）
  dbg_nn_ok_ = 0; dbg_nn_fail_ = 0; dbg_nn_del_ = "";
  dbg_nn_ms_ = 0.f; dbg_nn_tiles_ = 0; dbg_nn_gain_ = 0.f;
  finishStillChain(c, t0, out_rgba);
  return true;
}

// ── RAW 静拍共享件（v0.9.0 从 submitRaw 抽出，submitRawBurst 复用）────────

GLuint FrameRenderer::ensureRawTex(int w, int h) {
  if (raw_w_ != w || raw_h_ != h || raw_tex_ == 0) {
    if (raw_tex_) glDeleteTextures(1, &raw_tex_);
    raw_tex_ = 0;
    glGenTextures(1, &raw_tex_);
    glBindTexture(GL_TEXTURE_2D, raw_tex_);
    // 整型纹理必须 NEAREST（无过滤语义）
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_R16UI, w, h, 0, GL_RED_INTEGER,
                 GL_UNSIGNED_SHORT, nullptr);
    raw_w_ = w;
    raw_h_ = h;
  }
  // 0.12.181：饱和掩码随 raw 尺寸重建（R8，全分辨率，1 = 饱和核）
  if (satm_w_ != w || satm_h_ != h || tex_satm_ == 0) {
    if (tex_satm_) glDeleteTextures(1, &tex_satm_);
  if (tex_satm_dst_) glDeleteTextures(1, &tex_satm_dst_);
    if (fbo_satm_) glDeleteFramebuffers(1, &fbo_satm_);
    tex_satm_ = fbo_satm_ = 0;
    glGenTextures(1, &tex_satm_);
    glBindTexture(GL_TEXTURE_2D, tex_satm_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    // 0.12.184：R8 → RGBA8。R8 的 FBO 在 X300 上"完整但不落值"（真机 satmTx/
    // satmPrg 均 1、无 fbo_incomplete、cov 恒 0），改用项目既有的 RGBA8 路径，
    // mask 写 .r（其余分量填 0/1）。内存 4×（50MB），但可读回、可验证。
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA,
                 GL_UNSIGNED_BYTE, nullptr);
    glGenFramebuffers(1, &fbo_satm_);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_satm_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                           tex_satm_, 0);
    const GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (st != GL_FRAMEBUFFER_COMPLETE) {
      Logger::instance().log(LogLevel::kError, "engine", "satm.fbo_incomplete",
                             "{\"status\":" + std::to_string(st) + "}");
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    satm_w_ = w;
    satm_h_ = h;
  }
  return raw_tex_;
}

bool FrameRenderer::uploadRawTex(GLuint tex, const void* raw, int w, int h,
                                 int row_stride_px) {
  glBindTexture(GL_TEXTURE_2D, tex);
  // Image rowStride 可能含对齐填充（> w*2 字节），按像素为单位交给驱动跳行
  glPixelStorei(GL_UNPACK_ROW_LENGTH, row_stride_px);
  glPixelStorei(GL_UNPACK_ALIGNMENT, 2);
  glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RED_INTEGER,
                  GL_UNSIGNED_SHORT, raw);
  glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
  glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
  if (glGetError() != GL_NO_ERROR) {
    Logger::instance().log(LogLevel::kError, "engine", "raw.upload_failed",
                           "{\"w\":" + std::to_string(w) +
                               ",\"h\":" + std::to_string(h) + "}");
    return false;
  }
  return true;
}

FrameRenderer::RawCommon FrameRenderer::prepareRawCommon(
    float neutral_r, float neutral_g, float neutral_b,
    float wb_r, float wb_g, float wb_b, const float* ccm9,
    const float* lsc, int lsc_cols, int lsc_rows) {
  RawCommon rc;
  // WB 增益 = asShotNeutral 的倒数，G 归一；非法值回中性（防全 0/负数防呆）
  if (neutral_r > 1e-6f && neutral_g > 1e-6f && neutral_b > 1e-6f) {
    const float gr = neutral_g / neutral_r;
    const float gb = neutral_g / neutral_b;
    if (gr > 0.05f && gr < 20.0f && gb > 0.05f && gb < 20.0f) {
      rc.gains[0] = gr;
      rc.gains[2] = gb;
    }
  }
  // 用户 WB 补偿（updateWbCompensation 的目标/估计增益比）乘在 asShotNeutral
  // 之上：作用点在黑阶扣除后、CCM 前的 raw 域（darktable temperature / libraw
  // scale_colors 的标准位置）。G 归一除以 wb_g，保持 G 增益 = 1 不改整体亮度。
  // 【第 2 期 S13 更正】旧注释写"预览 OES / YUV 静拍在 pass1 乘同一组
  // (wb_r,wb_g,wb_b)，三条链语义一致"——**那是错的**：它们当时没有除 wb_g。
  // 现在两条链的 uWb 都改走 wbCompGNorm()，才是与下面两行同口径的 G 归一。
  // 防呆：单值非法（≤0）时跳过该通道，与 FilmParams::fromDoubles 的全 0 回退互补。
  if (wb_r > 1e-6f && wb_g > 1e-6f) rc.gains[0] *= wb_r / wb_g;
  if (wb_b > 1e-6f && wb_g > 1e-6f) rc.gains[2] *= wb_b / wb_g;
  // CCM 行主序 sensor→sRGB；缺失=单位阵
  if (ccm9) {
    for (int i = 0; i < 9; ++i) rc.ccm[i] = ccm9[i];
  }
  // LSC 增益图（M3 P0 镜头阴影校正）：Java 侧把 HAL 的 4 平面（R/Gr/Gb/B）折成
  // RGB 交错；无数据或开关关 → 1×1 恒等（shader 里等价于乘 1，零分支）。
  // 每次拍照重传（17×13×3 float ≈ 2.6KB，可忽略）。
  rc.lsc_ok = lsc != nullptr && lsc_cols > 1 && lsc_rows > 1;
  rc.lsc_w = rc.lsc_ok ? lsc_cols : 1;
  rc.lsc_h = rc.lsc_ok ? lsc_rows : 1;
  if (lsc_tex_ == 0 || lsc_w_ != rc.lsc_w || lsc_h_ != rc.lsc_h) {
    if (lsc_tex_ == 0) glGenTextures(1, &lsc_tex_);
    glBindTexture(GL_TEXTURE_2D, lsc_tex_);
    // NEAREST：双线性在 shader 内手写（X300 上 float 纹理过滤不可依赖，见 makeTex）
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB16F, rc.lsc_w, rc.lsc_h, 0, GL_RGB,
                 GL_FLOAT, nullptr);
    lsc_w_ = rc.lsc_w;
    lsc_h_ = rc.lsc_h;
  }
  static const float kLscIdent[3] = {1.0f, 1.0f, 1.0f};
  glBindTexture(GL_TEXTURE_2D, lsc_tex_);
  glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, rc.lsc_w, rc.lsc_h, GL_RGB, GL_FLOAT,
                  rc.lsc_ok ? lsc : kLscIdent);
  return rc;
}

// C 专项（2026-09-29）：噪声模型 → 降噪系数。
// 旧实现是"单值 ns × 档位系数 × 经验项 √(0.2+4x)"，且 ns 长期被夹到下限
// 0.004（真实 σ 在 [0,1] 域约 0.006–0.02 @ISO1970）——值域权重近透传。
// 现在：σ²(x) = A·x + B，A/B 逐通道（R,G,B）：
//   model 有效（Camera2 SENSOR_NOISE_PROFILE）：A = S_c·g_c·k²、B = O_c·g_c²·k²
//     （g = WB 增益，即 afterWb 传播；k = 档位强度乘子，作用于 σ）
//   model 无效（legacy 回退）：把旧经验式精确改写为 (A,B) 等效 ——
//     σ(x) = ns·k·√(0.2+4x) ⇒ σ² = (4·ns²k²)·x + (0.2·ns²k²)；P4 再补 0.008²
//     地板进 B（忽略交叉项，仅回退路径）。
// 档位强度（作用于真实 σ 的乘子）：P0 标准 1.0（= σ 真值，1σ 内平均）/ 强 2.5；
// P4 标准 1.5 / 强 2.5（色差噪声 ≈ √2·σ_ch，且 P4 后还有提亮放大 ~1.1EV）。
void FrameRenderer::denoiseParams(float strength, const NoiseModel& model,
                                  const float gains[3], float noise_sigma,
                                  NoiseCoef* out) {
  // 强度滑杆（0–2，步长 0.01）：**σ 恒为真实噪声**，强度分层承担 ——
  //   >1：作 σ 乘子（σ 放大 = 权重曲线更宽容 = 更激进的降噪）
  //   <1：作输出混合 out = mix(orig, denoised, k)（线性、各亮度段一致）
  // 旧实现把 k² 直接乘进 σ：k=0.45 时 σ 只有真值 0.45×，bilateral 的 d 放大 2.2×、
  // w=exp(−0.5d²) 指数塌陷 ⇒ 降噪近似失效，且**噪声越大的地方（亮部，散粒 ∝√x）
  // 越严重** —— 2026-10-02 真机定案（盘沿亮部 σY 4.48 vs Filmix 1.72/PC 0.92）。
  const float sg_p0 = std::max(1.0f, strength);
  const float sg_p4 = std::max(1.0f, strength);
  const float mix_k = std::min(1.0f, std::max(0.0f, strength));
  const float k_p0 = strength;
  const float k_p4 = 1.5f * strength;
  out->k_p0 = k_p0;
  out->k_p4 = k_p4;
  out->mix_p0 = mix_k;
  out->mix_p4 = mix_k;
  if (model.valid()) {
    const NoiseModel wb = model.afterWb(gains);  // P0 域（WB 后）
    for (int c = 0; c < 3; ++c) {
      out->p0a[c] = wb.S[c] * sg_p0 * sg_p0;
      out->p0b[c] = wb.O[c] * sg_p0 * sg_p0;
      out->p4a[c] = wb.S[c] * 2.25f * sg_p4 * sg_p4;   // 1.5² = 色差噪声标定
      out->p4b[c] = wb.O[c] * 2.25f * sg_p4 * sg_p4;
      // mismatch/tile-RMS：k=1 的真实噪声（与档位无关）
      out->mma[c] = wb.S[c];
      out->mmb[c] = wb.O[c];
    }
    out->model_src = true;
    // 典型 σ（中灰 0.18，G 通道——gains[1] 恒 = 1）
    out->ns_typ = std::sqrt(std::max(wb.S[1] * 0.18f + wb.O[1], 1e-12f));
  } else {
    // legacy：旧式等效（σ 回退值按 ISO 估算，见 Kotlin 侧；引擎兜底 0.012）
    const float ns = noise_sigma > 0.0f ? noise_sigma : 0.012f;
    const float sr_k = sg_p0;
    const float sc_k = sg_p4;
    for (int c = 0; c < 3; ++c) {
      out->p0a[c] = 4.0f * ns * ns * sr_k * sr_k;
      out->p0b[c] = 0.2f * ns * ns * sr_k * sr_k;
      out->p4a[c] = 4.0f * ns * ns * sc_k * sc_k;
      out->p4b[c] = 0.2f * ns * ns * sc_k * sc_k + 0.008f * 0.008f;
      // mismatch 等效：mma=0、mmb=ns²/2 ⇒ shader 的 var(r)+var(alt)=ns²（旧常数 σ 判据）
      out->mma[c] = 0.f;
      out->mmb[c] = 0.5f * ns * ns;
    }
    out->model_src = false;
    out->ns_typ = std::min(std::max(ns, 0.004f), 0.040f);
  }
}

// 成片链共享后半段（v0.9.0）：P0（raw 或 fused 输入）→ RCD/bilinear → P4 →
// filmic → 输出读回 → raw.render 日志。submitRaw 与 submitRawBurst 共用。
void FrameRenderer::finishStillChain(const ChainCtx& c,
                                     std::chrono::steady_clock::time_point t0,
                                     std::vector<uint8_t>* out_rgba) {
  const auto t1 = std::chrono::steady_clock::now();
  const int w = c.w, h = c.h, dst_w = c.dst_w, dst_h = c.dst_h;
  const FilmParams& p = *c.p;
  float dir_ms = 0.0f, g_ms = 0.0f, ccm_ms = 0.0f, dn_ms = 0.0f;
  diagBeginFrame();  // 诊断统计：关时仅一次 atomic load，无 pass / 无 GL 调用
  // AMaZE 诊断帧首清零（passCaCorrect 里的清零已删除，见那里的注释）
  dbg_amaze_ms_ = 0.f;
  dbg_amaze_min_ = 0.f;
  dbg_amaze_max_ = 0.f;
  dbg_amaze_rd_ms_ = dbg_amaze_cpu_ms_ = dbg_amaze_up_ms_ = 0.f;
  // ── 0.12.147：AMaZE 并入 RCD 分支（**吃完整前链**）────────────────────────
  // v0.10.172 的 AMaZE 是独立第一支：原始 u16 → 自行线性化/WB/LSC → CPU AMaZE，
  // 完全绕开 HL 饱和重建与 P0（RAW 域降噪）⇒ 与 RCD 档对比时"去马赛克质量"和
  // "前链退化"两个变量混在一起，当时的结论（amaze-REPORT §0）无法归因。
  // 现在 AMaZE 跑在**同一份 tex_mos_**（HL 前置 + P0 的输出，与 RCD 逐字节同源），
  // 之后的 CCM → P4 → color_smooth → PC3D → defringe → film 与 RCD 档完全共用。
  if (c.rcd || c.amaze) {
    if (!ensureRcdTargets(w, h)) return;
    const auto d0 = std::chrono::steady_clock::now();
    // ── v0.6.34：P0 **无条件执行**（关降噪时 k=0 = 只做线性化+WB，零邻域操作）──
    // 起因（真机 v0.6.33 日志）：关降噪反而慢得多 —— dn=false 时 P1 直接读 R16UI
    // 整型 RAW 纹理、自己解码黑阶/WB，比读 P0 的 float mosaic 慢 4–7 倍。
    // 安全性：P0 的 raw0() 与 P1 的 s() RAW 分支**逐字相同**（黑阶相位、白阶归一、
    // WB 增益都同一式），所以 k=0 的 P0 与"P1 直读 RAW"数学等价 —— 关降噪的成片
    // 只差一次 RGBA16F 量化（开降噪档本来就走这条路），**零细节代价**（无邻域采样）。
    // 故下面三处 mosaic 标志恒为 true。
    // MFNR（v0.9.0）：P0 输入换成 tex_fused_（融合后线性 mosaic，已扣黑阶/WB），
    // bilateral 逻辑同款（kFsRawDenoiseFused）。
    // C 专项：关档 = 全零系数（A=B=0，shader 里精确跳过邻域）。
    static const NoiseCoef kNoDn{};

    // ══ v0.10.167：HR **前置**到 P0 之前 ═══════════════════════════════════
    // 顺序由 "P0 → HR" 改为 "HR → P0"，对齐 PhotonCamera / darktable（二者的
    // highlights 都跑在空间降噪之前）。根因（离线同 DNG 逐位实测）：不降噪时
    // 参考场 hlRgG = 0.9860，经 P0 后 = 1.0768 —— P0 把高光参考场的 R/G 抬高
    // 9.2%（饱和像素旁的 G 被同色 bilateral 平滑拉低，而 refavg 走 R/B 外推受
    // 影响小 ⇒ `v − refavg` 被系统性拉负 ⇒ 修复力度被吃掉）。
    // ⚠ 统计口径与判定口径是**两个独立旋钮**：统计（mask + chrominance）走
    //   stat_src（uStat），判定（apply + ref 场）走 src（uMos）。exp21 翻车正是
    //   因为当时两者共用一个 src —— 换统计源时把判定也换了（修复面 47269→63903
    //   样本、画面更红）。前置后二者同指 P0 前的 mosaic，但代码上已解耦。
    // ⚠ 值域（第 2 步）：HR 输出是 scene-referred、可 >1，而 P0 的噪声模型
    //   σ²(x) = A·x+B 标定在 [0,1] ⇒ 前置路径下 P0 用 wclamp=true（权重/σ 用钳位
    //   值、信号用原值）。量化见 /tmp/hr-pre/REPORT.md。
    hr_src_override_ = 0;   // 前置后 RCD 读 P0 的输出 tex_mos_（已含 HR 修复）
    // 0.12.133：掩码句柄必须每张清零 —— 否则单帧/未跑 HL 的帧会拿到上一张的
    // tex_hl_dil_（跨帧残留 = 假 mask，PC3D 会在错误的位置收手）。
    hl_mask_tex_ = 0;
    dbg_hl_ok_ = 0;
    dbg_hl_cnt_ = 0.f;
    dbg_hl_ms_ = 0.f;
    dbg_hl_rgg_ = 0.f;
    dbg_hl_bgg_ = 0.f;
    dbg_hl_refw_ = 0.0;
    dbg_hl_r2hit_ = 0.f;
    dbg_hl_rghit_ = 0.f;
    dbg_hl_pre_ = 0;
    dbg_hl_wclamp_ = 0.f;
    // v0.10.169：无条件写下参数真值（hr_raw 关时 pass 不执行，日志 hlGRecon 仍可读）
    // 0.12.168：恢复受控 ⇒ 回显 FilmParams[49] 的实际下发值（与 passHighlightRaw 的 r.grecon 一致）
    dbg_hl_grecon_ = p.hl_grecon > 0.5f ? 1 : 0;
    // v0.10.179：同上，uRbRecon（FilmParams[57]）→ 日志 hlRb
    dbg_hl_rbrecon_ = p.hl_rb_recon > 0.5f ? 1 : 0;
    dbg_hl_stat_src_ = "none";
    dbg_hl_decide_src_ = "none";
    for (int k = 0; k < 3; ++k) {
      dbg_hl_chroma_[k] = 0.f;
      dbg_hl_sum_[k] = 0.0;
      dbg_hl_cnt3_[k] = 0.f;
    }

    // v0.11.89：bracket 下"信息没丢"就不跑 HR。两个理由：
    //   ① 少数"长帧+短帧都饱和"的像素（真机 4217：短帧 sat 0、clipB 64）会把整块
    //      64×64 送进 wavelet inpaint，而周围像素其实有信息 —— 视觉就是窗玻璃上的灰块；
    //   ② sat-fix 已经把"长帧饱和但短帧有值"的像素换成真实值，HR 没有可救的东西。
    // 判据用采集端实测的短帧裁切比例（真丢信息的比例）：<0.2% ⇒ 关。非 bracket 不受影响。
    const bool bracket_info_ok = ae_brk_sat_ >= 0.0f && ae_brk_sat_ < 0.002f;
    // v0.12.2：**HL 必须开**（0.12.1 强制关掉后，饱和区立刻出现大面积粉紫彩斑 ——
    // RCD 在 R/B 先饱和、G 未饱和处的色比外推失真，这正是 HL 存在的理由）。
    // 但灰斑也确实是 HL 造成的 —— 区别在**它重建了什么**：
    //   · 色度校正（rg/bg 比例）→ 必需，不碰亮度，结构保留
    //   · **G 通道 / 亮度重建（hl_grecon）→ 抹结构**，镜面高光上的阴影条纹被
    //     邻域池化比替换成灰块 —— 这就是"灰斑"的直接来源
    // 所以：HL 开、G 重建关。这个组合以前从未在真机上跑过（前四版的 pe 门控全部
    // 没到达这里，详见下面对 grecon 的直接改写）。
    // ── 0.12.181：饱和掩码（[101]）—— 参考帧 RAW 逐 photosite，HL 之前生成 ──
    // 与 HL 的重建值无关（重建值不是测量值，不能反向影响"哪里丢了信息"的判定）。
    satmask_enabled_ = p.sat_mask > 0.5f;
    dbg_satm_p_ = p.sat_mask;
    dbg_satm_cov_ = 0.f;
    if (satmask_enabled_) {
      // 0.12.189：用 **raw 尺寸**（与参考帧 RAW / tex_satm_ 同几何）。之前传 dst
      // 尺寸，竖拍时 4080×3072 vs 3072×4080 不匹配 ⇒ pass 直接返回 ⇒ cov 恒 0。
      passSatMask(raw_w_, raw_h_, c.effective_rot, c.cfa, c.black_phase,
                  c.white_level, c.gains, c.dn ? c.nc : kNoDn);
    }
    const bool hr_on = p.hr_raw > 0.5f && !bracket_info_ok;
    // ① P0 前的 mosaic（HR 的输入域）
    //    MFNR：tex_fused_（融合后 R16F 单通道，已扣黑阶+WB）
    //    单帧：无现成中间纹理 ⇒ 先用 kNoDn 的 P0 做一趟纯线性化+WB 展开（无邻域
    //          采样），结果落 tex_mos_（相位布局）；真 P0 稍后覆盖 tex_mos_。
    //          只在 hr_raw 开时才付这一趟 ⇒ 关 HR = 原链路逐位不变（A/B 红线）。
    // ── 0.12.133（方案 B 块 3）：全饱和区亮度 inpaint ────────────────────
    // 位置 = HL 之前（HL 的判定/统计/亮度锚都吃这一张）：饱和像素先有"短帧真值
    // 或邻域扩散出来的真实亮度"，HL 的 allSat 分支拿到的 lv 才不是满阱常数。
    // 条件 = MFNR 路径 + 本张有短帧真值（scale>1 = 真的是更短的曝光）+ 有饱和标记。
    // 任何一步不满足 ⇒ fused_src 仍是 tex_fused_（132 语义，逐位不变）。
    dbg_hl_inp_ = 0;
    dbg_hl_inp_ms_ = 0.f;
    GLuint fused_src = tex_fused_;
    // ── 0.12.135：inpaint **默认不启用**（代码保留，改 true 即回到 A/B 通路）───
    // 真机数据（2026-10-28 20:18，灯面 RAW 逐通道分析）：
    //   · 灯的发光面三通道**同时**饱和（饱和率 0.82/0.82/0.83），未饱和部分的
    //     归一化 RAW 已经 0.86（R/G/B 一致 = 中性），真实亮度就在满阱附近；
    //   · 短帧（−1.5EV，scale=2.82）在该处归一化后 k ≈ 1.0~1.2 ⇒ inpaint 能给的
    //     亮度提升只有 0~20%（显示域几乎看不出来），却把短帧的逐点噪声与 1~2px
    //     对齐误差搬进高光 ⇒ 灯面亮度台阶（用户看到的"色彩断层/黄色色块"）。
    //   · 133/134 的灯面实测比 132 **更暗**（0.890/0.898 vs 0.932）—— k 未被夹住
    //     时会小于 1，inpaint 反而压暗。收益为负。
    // ⇒ 亮度提升这条线要等 HDR/Mertens 那条路（真正的多曝光信息），在此之前
    //   宁可保持 132 的"满阱亮度"（干净、无台阶）。见 docs §22。
    if (kHlInpaintOn && c.mfnr_fused && short_val_ok_ &&
        short_val_scale_ > 1.001f && sv_w_ == w && sv_h_ == h &&
        hr_fix_mark_ != 0) {
      if (passHlInpaint(w, h, c.cfa, c.gains, short_val_scale_, tex_fused_,
                        tex_short_val_)) {
        fused_src = tex_fused_inp_;
      }
    }
    GLuint p0_pre = 0;
    int p0_pre_mono = 0;
    if (c.mfnr_fused) {
      p0_pre = fused_src;
      p0_pre_mono = 1;
    } else if (hr_on) {
      passRawDenoise(w, h, c.cfa, c.black_phase, c.white_level, c.gains, kNoDn);
      p0_pre = tex_mos_;
      p0_pre_mono = 0;
    }

    // ② HR（前置）：src == stat_src == P0 前的 mosaic（判定源与统计源各自独立下发）
    GLuint hr_out = 0;
    if (hr_on && p0_pre != 0) {
      dbg_hl_pre_ = 1;
      dbg_hl_stat_src_ = c.mfnr_fused ? "tex_fused_P0pre" : "tex_mos_P0pre_expand";
      dbg_hl_decide_src_ = dbg_hl_stat_src_;
      // stat_raw：本前置把**已展开**的 mosaic 交给统计，故恒 0（uStatRaw 仍是死参数，
      // 见 REPORT 的"未做/无法判定"一节 —— 接 raw 展开会另开一条数据流）。
      hr_stat_raw_ = 0;
      // v0.10.169：G 通道重建开关（FilmParams[49]）→ 下发给 apply pass 的 uGRecon；
      // 日志 hlGRecon 回读 pass 的 hl.grecon（= 那次 glUniform 的实际值），不另算一份。
      const HlRawResult hl = passHighlightRaw(w, h, c.cfa, c.gains, p0_pre, p0_pre,
                                              0, p0_pre_mono,
                                              // v0.12.5：0 = darktable 原版档（默认，只抬不压 +
                                              // 加性 chroma 补偿）；1 = 自创档（allSat 三通道替换，
                                              // 满阱 luma 锚 ⇒ 会把真值压暗并去色）。设置页可切。
                                              p.hl_grecon > 0.5f ? 1 : 0,
                                              p.hl_rb_recon > 0.5f ? 1 : 0,
                                              // 0.12.127：标记重新接上，但**消费者换了** ——
                                              // apply 的判据已是逐通道满阱归一（0.12.105），
                                              // 不再需要它；现在它是 ③ chroma 统计的口径开关：
                                              // refavg 只采纳标记 2（长帧本身未饱和）的邻域
                                              // 样本，把 sat-fix 注入值（可达 3~4，超满阱）
                                              // 从统计里剔除。真机 0.12.126 的
                                              // hlChroma=[-2.191,...] 物理上不可能，就是
                                              // 注入值进了 refavg 的 3×3 邻域造成的。
                                              hr_fix_mark_);
      dbg_hl_ok_ = hl.ok ? 1 : 0;
      dbg_hl_grecon_ = hl.grecon;
      dbg_hl_rbrecon_ = hl.rbrecon;
      dbg_hl_cnt_ = hl.cnt[1];
      dbg_hl_ms_ = hl.ms;
      dbg_hl_rgg_ = hl.rg_g;
      dbg_hl_bgg_ = hl.bg_g;
      dbg_hl_refw_ = hl.w_sum;
      dbg_hl_r2hit_ = hl.ref2_hit;
      dbg_hl_rghit_ = hl.refg_hit;
      for (int k = 0; k < 3; ++k) {
        dbg_hl_chroma_[k] = hl.chroma[k];
        dbg_hl_sum_[k] = hl.sum[k];
        dbg_hl_cnt3_[k] = hl.cnt[k];
      }
      if (hl.ok) hr_out = tex_hl_;   // ③ P0 的输入（单通道 .r 布局）
    }
    // 0.12.133：本张 RCD 能否把 RAW 饱和掩码写进 alpha（= PC3D 收手的前提）。
    // 必须在 passHighlightRaw **之后**读 —— 掩码句柄是那次调用写下的（之前恒 0，
    // 若放在 HR 之前，日志会永远报 hlMaskTx=0，等于诊断自己骗自己）。
    dbg_hl_mask_tx_ = (hl_mask_tex_ != 0) ? 1 : 0;

    // ── NN 降噪 pass（v0.10.102）：融合后/RAW 域、去马赛克前 ────────────────
    // PMRID（融合后 RAW 域 pass）：成功 → P0 空域降噪被 NN 替代（强度 0 直读
    // 展开，输入 tex_nn_fused_，与 tex_fused_ 同域）；失败 → 整张自动回退 P0
    // 原链（照片优先红线，dbg_nn_fail_=1 留痕）。
    // v0.10.167 前置：NN 的输入换成 HR 输出（tex_hl_ 是单通道 .r，与 tex_fused_
    // 同读法 ⇒ kFsNnPack 零改动且域等价：linFused(HR 输出) ≡ linRaw(raw)）。
    // HR 关或失败时保持原输入（tex_fused_ / raw_tex_）。
    bool nn_ok = false;
    if (c.nn) {
      NnSrc nsrc;
      if (hr_out != 0) {
        nsrc.fused_tex = hr_out;   // HR 后的 P0 前 mosaic
        nsrc.src = 0;
      } else if (c.mfnr_fused) {
        nsrc.fused_tex = fused_src;
        nsrc.src = 0;
      } else {
        nsrc.fused_tex = 0;
        nsrc.src = 1;              // 单帧原始域（R16UI）
      }
      nn_ok = runNnPass(c, nsrc, c.gains, c.cfa, c.black_phase, c.white_level);
      if (nn_ok) {
        dbg_p0s_ = 0;  // NN 替代 P0；9/96 是 P0 原路径的样本数语义
      } else {
        // 0.10.102 教训：失败没置位，日志 nnOk=0/nnFail=0 分不清"回退成功"
        dbg_nn_fail_ = 1;
      }
    }

    // ── P0（前置后输入 = HR 输出；NN 成功时输入 = NN 输出）──────────────────
    const GLuint p0_in = nn_ok ? tex_nn_fused_
                               : (hr_out != 0 ? hr_out
                                              : (c.mfnr_fused ? fused_src : tex_mos_));
    // 值域保护只在"P0 直接吃 HR 输出"时需要（NN 输出本就落在 [0,1] 训练域）
    // 0.12.133：inpaint 输出同样是 scene-referred（可 >1）⇒ 与 HR 输出同口径保护
    const bool p0_wclamp = (!nn_ok && (hr_out != 0 || fused_src != tex_fused_));
    if (c.mfnr_fused) {
      if (nn_ok) {
        passRawDenoiseFused(w, h, c.cfa, c.black_phase, c.white_level,
                            c.gains, kNoDn, nullptr, /*subpixel=*/false,
                            tex_nn_fused_, /*allow_ts=*/false, /*wclamp=*/false);
      } else {
      passRawDenoiseFused(w, h, c.cfa, c.black_phase, c.white_level,
                          c.gains, c.dn ? c.nc : kNoDn, nullptr, c.p0_sub, p0_in,
                          /*allow_ts=*/true, p0_wclamp);
      }
    } else {
      if (nn_ok) {
        // 单帧 NN：PMRID 输出已落 tex_nn_fused_（fused 域），走同一条
        // "强度 0 直读展开" 产出 tex_mos_（单帧无 tex_mfnr_，Ts 本就不命中，
        // allow_ts=false 显式钉死）
        passRawDenoiseFused(w, h, c.cfa, c.black_phase, c.white_level,
                            c.gains, kNoDn, nullptr, /*subpixel=*/false,
                            tex_nn_fused_, /*allow_ts=*/false, /*wclamp=*/false);
      } else if (hr_on) {
        // v0.10.167 单帧前置：输入 = ① 的展开结果（或 HR 输出）。kFsRawDenoiseFused
        // 的 bilateral 与 kFsRawDenoise 逐字相同（都读已展开的值、同一 σ 式与核）。
        passRawDenoiseFused(w, h, c.cfa, c.black_phase, c.white_level, c.gains,
                            c.dn ? c.nc : kNoDn, nullptr, /*subpixel=*/false,
                            p0_in, /*allow_ts=*/false, p0_wclamp);
      } else {
        passRawDenoise(w, h, c.cfa, c.black_phase, c.white_level, c.gains,
                       c.dn ? c.nc : kNoDn);
      }
    }
    // ── 0.12.147：AMaZE 与 RCD 二选一（前链共用，见块首注释）────────────────
    if (c.amaze) {
      const auto a0 = std::chrono::steady_clock::now();
      if (!runAmazeFromMosaic(c)) {
        Logger::instance().log(LogLevel::kError, "engine", "amaze.failed", "{}");
        return;  // 照片优先红线：失败不出图（调用方按 ok=false 处理）
      }
      passAmazeUp(dst_w, dst_h, c.effective_rot);
      glFinish();
      dbg_amaze_ms_ = std::chrono::duration<float, std::milli>(
                          std::chrono::steady_clock::now() - a0).count();
    }
    if (!c.amaze) {
    passRcdDir(w, h, c.cfa, c.black_phase, c.white_level, c.gains, true);
    glFinish();
    const auto d1 = std::chrono::steady_clock::now();
    passRcdG(w, h, c.cfa, c.black_phase, c.white_level, c.gains, true,
             p.rcd_g_orig);
    glFinish();
    const auto d2 = std::chrono::steady_clock::now();
    dir_ms = std::chrono::duration<float, std::milli>(d1 - d0).count();
    g_ms = std::chrono::duration<float, std::milli>(d2 - d1).count();
    // ── P3 色度段（v0.10.180 加 [58] 拆双 pass A/B 路径）───────────────────
    // rcdSplit=1：3a 对角段填充（tex_rcd_a_）→ 3b G 位轴向填充（交叉轴 ±1 读
    // 3a 的 STEP4.2 已填充色，恢复原版四正邻双轴估计）——单 pass 版并行读不到
    // 本 pass 内对角段结果 ⇒ 原版就有的 B 缓 G 陡错位（品红峰 +43.8 WB 域）被
    // 放大 2×（+85.6），docs/research/11 号 §8。3a/3b 分别计时（rcdAMs/rcdBMs，
    // 铁律：性能取舍必须实测）；3b 含 glFinish 同步，与 ccmMs 同口径。
    // rcdSplit=0：旧单 pass，逐位不变。
    dbg_rcd_split_ = 0;
    dbg_rcd_a_ms_ = dbg_rcd_b_ms_ = 0.f;
    if (p.rcd_p3_split > 0.5f) {
      passRcdChromaA(w, h, c.cfa, c.black_phase, c.white_level, c.gains, true,
                     p.rcd_cdfix);
      glFinish();
      const auto d2a = std::chrono::steady_clock::now();
      passRcdChromaB(dst_w, dst_h, c.effective_rot, c.cfa, c.gains);
      glFinish();
      const auto d2b = std::chrono::steady_clock::now();
      dbg_rcd_a_ms_ =
          std::chrono::duration<float, std::milli>(d2a - d2).count();
      dbg_rcd_b_ms_ =
          std::chrono::duration<float, std::milli>(d2b - d2a).count();
      dbg_rcd_split_ = 1;
    } else {
      passRcdChroma(dst_w, dst_h, c.effective_rot, c.cfa, c.black_phase,
                    c.white_level, c.gains, c.ccm, true, p.rcd_cdfix,
                    p.rcd_g_orig);
    }
    }  // !c.amaze：AMaZE 档不走 RCD 三段（D3 由 passAmazeUp 写 tex_work_）
    // 第 4a 期（S14）：RCD 档的 D3→D4 收口（tex_work_ D3 → tex_scene_ D4）。
    // 必须在 P4（读 tex_scene_）之前；双线性档不经此处（passRaw 内联 CCM）。
    // v0.10.154：本 pass 是 4a 新增的全屏 pass，单独计时入日志（ccmMs）——
    // 项目铁律要求"性能取舍必须实测"，没有这个字段就无法在真机上评估拆分成本。
    const auto d3 = std::chrono::steady_clock::now();
    // 诊断统计 D3（RCD 去马赛克后 / CCM 前，相机 RGB）。统计耗时不计入 ccmMs。
    // 放在 passCcm 之前 ⇒ 读到的是 CCM 的输入（tex_work_ 此刻承载 D3）。
    diagStatsTex("D3-camrgb", tex_work_, dst_w, dst_h, &p);
    // 横向色差校正（v0.10.170）：D3（去马赛克后 / CCM 前，相机 RGB）→ CCM。
    // 放在 D3 诊断**之后**：保持该基线的既有语义（统计到的是校正前的相机 RGB）。
    // caApplied=0（关闭/估计失败/位移低于门限）时 ccm_src 仍是 tex_work_，
    // 与加本模块之前**逐位相同**。
    GLuint ccm_src = tex_work_;
    // 先清零诊断字段：ca_corr 关闭时 passCaCorrect 根本不被调用，若不在调用点
    // 清零，日志会残留上一帧的值（假"动作"）。
    dbg_ca_applied_ = 0;
    dbg_ca_kr_ = dbg_ca_kb_ = 0.f;
    dbg_ca_kraw_r_ = dbg_ca_kraw_b_ = 0.f;
    dbg_ca_shift_r_ = dbg_ca_shift_b_ = 0.f;
    dbg_ca_ms_ = 0.f;
    // 注意：这里**不能**清零 dbg_amaze_ms_ —— AMaZE 分支在上游刚写完它。
    if (p.ca_corr > 0.5f) {
      // 在 pass 外计时：**不动作时也要有成本读数**（估计阶段的 1/8 降采样 +
      // readPixels 无论判据是否通过都已经付了），否则真机无法评估本模块的开销。
      glFinish();   // 起点同步：只计本模块的工作，不背前面 pass 的尾巴
      const auto ca_t0 = std::chrono::steady_clock::now();
      if (passCaCorrect(dst_w, dst_h, p.ca_corr)) {
        ccm_src = tex_ca_;
      }
      dbg_ca_ms_ = std::chrono::duration<float, std::milli>(
                       std::chrono::steady_clock::now() - ca_t0).count();
    }
    passCcm(dst_w, dst_h, c.ccm, ccm_src);
    glFinish();
    ccm_ms = std::chrono::duration<float, std::milli>(
                 std::chrono::steady_clock::now() - d3).count();
    // 诊断统计 D4（CCM 后 / P4 前，场景线性）
    diagStatsTex("D4-scene", tex_scene_, dst_w, dst_h, &p);
    dbg_prof_d4_ = profDump(fbo_scene_, tex_scene_, dst_w, dst_h);   // 0.12.164 诊断
    // ── HDR 融合 P2（0.12.78）：侧帧前链 + zero/侧帧 Mertens 融合 ────────
    // 位置 = D4 之后、P4/PC3D/defringe 之前（设计 §4.3：融合后的整图再统一
    // 降噪/伪彩处理）。ponytail: 逐像素加权（接缝风险已知，P3 pyramid 解决）。
    // 序列：① zero 的 D4 存走（tex_hdr_zero_）→ ② high 重传 raw_tex_
    //（passRawDenoise/RCD 的固定输入是 raw_tex_ —— 0.12.77 的独立 Bayer 纹理
    // 方案让侧帧链跑在 zero 数据上，D4-hdr≈D4-scene，已废）→ ③ 侧帧前链
    //（展开 kNoDn → RCD 三段 → CCM）→ ④ side 的 D4 存走 → ⑤ Mertens 写回
    // tex_scene_。任何一步失败：留痕并跳过融合（成片 = zero 链，照片优先）。
    if (c.hdr_mertens && c.hdr_side_raw != nullptr) {
      const auto hs0 = std::chrono::steady_clock::now();
      bool hdr_ok = ensureHdrSideTargets(dst_w, dst_h);
      const auto hdrFail = [&](const char* why) {
        Logger::instance().log(LogLevel::kWarn, "engine", "hdr.side_fail",
                               std::string("{\"why\":\"") + why + "\"}");
        hdr_ok = false;
      };
      // ① zero 的 D4 存走（fbo_scene_ 为 READ FB）
      if (hdr_ok) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo_scene_);
        glBindTexture(GL_TEXTURE_2D, tex_hdr_zero_);
        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, dst_w, dst_h);
        if (glGetError() != GL_NO_ERROR) hdrFail("zero_copy");
      }
      // ② high 重传 raw_tex_（zero 主链此时已不再消费它）
      if (hdr_ok && !uploadRawTex(raw_tex_, c.hdr_side_raw, w, h,
                                  c.hdr_side_stride)) {
        hdrFail("upload");
      }
      // ③ 侧帧前链：与 zero 同域同分支（[58] 拆分档照跑）；不跑 HR/P0 时域/CA
      //（HR 修复的饱和区正是 highlightValidity 要拒绝的区域；PC 侧帧同构）
      if (hdr_ok) {
        // 侧帧前链（0.12.79，与主链 v0.10.167 前置结构同构）：
        // ① kNoDn 展开（raw_tex_ → tex_mos_，HR 的统计/判定输入）
        // ② HR 前置（0.12.78 缺失 ⇒ 侧帧饱和边缘的 RCD 色度外推误差在 0.5~0.96
        //    肩部被 highlightValidity 放行、权重独大 = 高光斑块根因之一）
        // ③ P0（输入 = HR 输出；σ 按侧帧实际增益重标：GCam 模型 σ²=A·x+B 的
        //    读噪声项 B∝gain²，散粒项 A 归一化域不变；strength 跟随主链 c.dn）
        // ④ RCD 三段 → CCM（原样）。
        // ⚠ 侧帧 HR 会覆盖 dbg_hl_* 诊断成员（主链已写完、raw.render 还没读）
        // ⇒ 全套保存/恢复。
        static const NoiseCoef kNoDnSide{};
        passRawDenoise(w, h, c.cfa, c.black_phase, c.white_level, c.gains,
                       kNoDnSide);
        GLuint side_hl = 0;
        int sv_ok = dbg_hl_ok_, sv_pre = dbg_hl_pre_, sv_grecon = dbg_hl_grecon_,
            sv_rbrecon = dbg_hl_rbrecon_, sv_stat_src = 0;
        float sv_cnt = dbg_hl_cnt_, sv_ms = dbg_hl_ms_, sv_rgg = dbg_hl_rgg_,
              sv_bgg = dbg_hl_bgg_, sv_refw = dbg_hl_refw_,
              sv_r2 = dbg_hl_r2hit_, sv_rg = dbg_hl_rghit_,
              sv_wclamp = dbg_hl_wclamp_;
        const char* sv_stat = dbg_hl_stat_src_;
        const char* sv_dec = dbg_hl_decide_src_;
        float sv_chroma[3], sv_sum[3], sv_cnt3[3];
        for (int k = 0; k < 3; ++k) {
          sv_chroma[k] = dbg_hl_chroma_[k];
          sv_sum[k] = dbg_hl_sum_[k];
          sv_cnt3[k] = dbg_hl_cnt3_[k];
        }
        const bool hr_side_on = p.hr_raw > 0.5f;
        if (hr_side_on) {
          hr_stat_raw_ = 0;
          const HlRawResult hl = passHighlightRaw(
              w, h, c.cfa, c.gains, tex_mos_, tex_mos_, 0, 0,
              p.hl_grecon > 0.5f ? 1 : 0, p.hl_rb_recon > 0.5f ? 1 : 0,
              /*fix_mark=*/0);
          if (hl.ok) side_hl = tex_hl_;
        }
        dbg_hl_ok_ = sv_ok; dbg_hl_pre_ = sv_pre;
        dbg_hl_grecon_ = sv_grecon; dbg_hl_rbrecon_ = sv_rbrecon;
        dbg_hl_cnt_ = sv_cnt; dbg_hl_ms_ = sv_ms;
        dbg_hl_rgg_ = sv_rgg; dbg_hl_bgg_ = sv_bgg;
        dbg_hl_refw_ = sv_refw; dbg_hl_r2hit_ = sv_r2; dbg_hl_rghit_ = sv_rg;
        dbg_hl_wclamp_ = sv_wclamp;
        dbg_hl_stat_src_ = sv_stat; dbg_hl_decide_src_ = sv_dec;
        for (int k = 0; k < 3; ++k) {
          dbg_hl_chroma_[k] = sv_chroma[k];
          dbg_hl_sum_[k] = sv_sum[k];
          dbg_hl_cnt3_[k] = sv_cnt3[k];
        }
        (void)sv_stat_src;
        // ③ P0（σ 对齐 zero 链等效噪声；HR 输出直读时 wclamp=true，与主链单帧
        // 前置同构）
        // 0.12.81：侧帧 P0 只做"σ 真值"降噪时，残余噪声仍是 zero 链的 ~10×
        //（单帧 ISO 3200 读噪声 = zero 的 10×，且 zero 另有 2 帧时域堆叠；
        // 真机 204721 实锤：融合档高频能量 = 采集档 ×3，Mertens 的 contrast
        // 因子对 σ 响应 ∝ 噪声 —— 权重选中的是侧帧噪声不是细节 = 高光斑块）。
        // 修法 = HDR+ §5 的既定原则"按实际合并更新噪声方差"：把侧帧 P0 的
        // σ ×2（等效 strength 2，项目降噪强度档语义），使其残余对齐 zero 链
        //（2 帧堆叠 + P0@真值）。实现 = σ² 系数 ×4：A×4（散粒 σ_shot×2）、
        // B×(gr2×4)（读噪声 = 增益标定 ×gr2 与降噪 ×4 的合并）。zero 链与
        // 关档零影响。
        const GLuint side_p0_in = side_hl != 0 ? side_hl : tex_mos_;
        if (c.dn) {
          NoiseCoef nc_side = c.nc;
          const float gr2 = c.hdr_side_gain * c.hdr_side_gain;
          for (int k = 0; k < 3; ++k) {
            nc_side.p0a[k] *= 4.f;
            nc_side.p0b[k] *= gr2 * 4.f;
            nc_side.mma[k] *= gr2;
            nc_side.mmb[k] *= gr2;
          }
          passRawDenoiseFused(w, h, c.cfa, c.black_phase, c.white_level,
                              c.gains, nc_side, nullptr, /*subpixel=*/false,
                              side_p0_in, /*allow_ts=*/false,
                              /*wclamp=*/side_hl != 0);
        } else {
          passRawDenoiseFused(w, h, c.cfa, c.black_phase, c.white_level,
                              c.gains, kNoDnSide, nullptr, /*subpixel=*/false,
                              side_p0_in, /*allow_ts=*/false,
                              /*wclamp=*/side_hl != 0);
        }
        // 0.12.151：侧帧也走与主帧**同一个去马赛克**（AMaZE 档时）。
        // 之前侧帧恒走 RCD ⇒ 主/侧两帧的饱和边缘伪彩不同 ⇒ Mertens 在高光边缘
        // 融合两张"伪彩不一样"的图，观感发脏。侧帧只需贡献融合权重，但两张图的
        // 伪彩必须同源，否则权重再准也白搭。
        // ⚠ 侧帧的 AMaZE 失败**必须回退 RCD**，不能 hdrFail：侧帧只贡献融合权重，
        // 少一次 HDR 融合的代价（动态范围塌成单帧）远大于"主/侧去马赛克算法不一致"。
        // 0.12.151 写成 hdrFail ⇒ 侧帧一失败就整段跳过融合，成片退化成主帧 ——
        // 表现正是"包围曝光没走"。
        bool side_amaze_ok = false;
        if (c.amaze) {
          const auto sa0 = std::chrono::steady_clock::now();
          side_amaze_ok = runAmazeFromMosaic(c);
          if (side_amaze_ok) {
            passAmazeUp(dst_w, dst_h, c.effective_rot);
            glFinish();
            // 累加进 amazeMs：日志里的 amazeMs 是**所有帧**（主帧 + 侧帧的
            // 读回/CPU/上传）之和，评估总成本必须看它。
            dbg_amaze_ms_ += std::chrono::duration<float, std::milli>(
                                 std::chrono::steady_clock::now() - sa0)
                                 .count();
          } else {
            Logger::instance().log(LogLevel::kWarn, "engine",
                                   "hdr.side_amaze_fallback", "{}");
          }
        }
        if (!side_amaze_ok) {
          passRcdDir(w, h, c.cfa, c.black_phase, c.white_level, c.gains, true);
          passRcdG(w, h, c.cfa, c.black_phase, c.white_level, c.gains, true,
                   p.rcd_g_orig);
          if (p.rcd_p3_split > 0.5f) {
            passRcdChromaA(w, h, c.cfa, c.black_phase, c.white_level, c.gains,
                           true, p.rcd_cdfix);
            passRcdChromaB(dst_w, dst_h, c.effective_rot, c.cfa, c.gains);
          } else {
            passRcdChroma(dst_w, dst_h, c.effective_rot, c.cfa, c.black_phase,
                          c.white_level, c.gains, c.ccm, true, p.rcd_cdfix,
                          p.rcd_g_orig);
          }
        }
        passCcm(dst_w, dst_h, c.ccm, tex_work_);
        // ④ side 的 D4 存走
        glBindFramebuffer(GL_FRAMEBUFFER, fbo_scene_);
        glBindTexture(GL_TEXTURE_2D, tex_hdr_side_);
        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, dst_w, dst_h);
        if (glGetError() != GL_NO_ERROR) hdrFail("side_copy");
      }
      // ⑤ Mertens 融合写回 tex_scene_ + 诊断
      if (hdr_ok) {
        passHdrMertens(dst_w, dst_h, c.hdr_ev_side);
        diagStatsTex("D4-hdr", tex_scene_, dst_w, dst_h, &p);
        Logger::instance().log(
            LogLevel::kInfo, "engine", "hdr.side",
            "{\"ev\":" + std::to_string(c.hdr_ev_side) + ",\"ms\":" +
                std::to_string(std::chrono::duration<float, std::milli>(
                                   std::chrono::steady_clock::now() - hs0)
                                   .count()) +
                "}");
      }
    }
  } else {
    passRaw(dst_w, dst_h, c.effective_rot, c.cfa, c.black_phase, c.white_level,
            c.gains, c.ccm, p);
  }
  bool mst_ok = false;
  bool df_ok = false;
  bool pc3d_ok = false;
  bool pc3d_replace = false;   // [54] = 1：PC3D 顶替 P4/defringe/D-1（改 passFilm 输入源）
  if (c.dn) {
    if (!ensureDnTarget(dst_w, dst_h)) return;
    const auto n0 = std::chrono::steady_clock::now();
    // ── PC ESD3D2 完整移植（v0.10.175）────────────────────────────────────
    // [54] = 0（关）⇒ 整段不执行，tex_scene_ 直通 P4 ⇒ 与 v0.10.174 **逐位相同**。
    // [54] = 1（替换档）= 照抄 PC 的**链路地位**：PC 链上 ESD3D2 是去马赛克后唯一
    //   的降噪 pass（PostPipeline.java:1504），故 PC3D 的输出直接进 film，
    //   P4/defringe/D-1 三步**旁路**（代码全部保留、[54] 一改即用 —— 铁律：不预删）。
    // [54] = 2（叠加档，**v0.10.177 默认**，docs/reports/pc3d-reval-REPORT.md）：
    //   PC3D → P4 → defringe → D-1 串联。
    dbg_pc3d_on_ = 0;
    dbg_pc3d_m_ = 0;
    dbg_pc3d_scale_ = 1;
    dbg_pc3d_k_ = 0.f;
    dbg_pc3d_ms_ = 0.f;
    GLuint p4_src = 0;
    // ── v0.10.205：色差域中值**前置到 PC3D 之前** ──────────────────────────
    // 真机实测（112643 成片剖面）：用户所见彩边是 PC3D 把 1~3px 伪彩**摊开**
    // 后的 12~90px 宽钟形带 ⇒ 任何"两侧一致性"判据的探测点都落在带内 ⇒
    // 覆盖率 0.0%（203/204 两版都卡在这里）。前置后伪彩仍是窄带，判据成立，
    // 且探测尺度只需 ±3/±6 ⇒ 代价远小于宽带处理。
    // 输入 = tex_scene_（D4，CCM 后场景线性），与 PC3D 同域同几何。
    bool cs_ok = false;
    if (p.color_smooth > 0.5f) {
      dbg_cs_k_ = p.color_smooth;
      dbg_cs_ok_ = (prog_cs_ != 0) ? 1 : 0;
      dbg_cs_on_ = 0;
      if (passColorSmooth(dst_w, dst_h, tex_scene_)) {
        cs_ok = true;
        dbg_cs_on_ = 1;
      }
    }
    // ── 0.12.162：色差域 3×3 中值（darktable color_smoothing 原版）─────────
    // 位置 = color_smooth 之后、PC3D 之前（与 color_smooth 同域：D4 场景线性）。
    // 为什么必须有：真机 09:47 阳台地砖的阴影紫晕，在 D4 层就已经存在
    //（离线 AMaZE+CCM 5.37% 紫像素 vs AHD 2.50%），而 color_smooth 的梯度门
    // 在暗部（|∇L|·255≈2.5）打不开 ⇒ 只替了 0.05% 的像素。中值不判门、不判色相。
    dbg_cm_on_ = 0;
    GLuint cm_src = 0;
    if (passColorMedian(dst_w, dst_h, cs_ok ? tex_cs_ : tex_scene_)) {
      cm_src = tex_cm_;
      dbg_cm_on_ = 1;
    }
    // ── 0.12.164：A/B 诊断开关（临时）──────────────────────────────────────
    // 真机 10:02 样片（FILMKIT_20261029-100221）的阴影边缘有一条约 6px 的**亮度平台**：
    //   AMaZE 输出平滑（0.047→0.137）、CCM 后 D4 也平滑（Y 0.046→0.136），
    //   而成片在同一位置是 0.325→0.329（几乎不动）然后突然跳到 0.48。
    // 平台只能产生在 D4 之后。链路（replace 档）= CS → PC3D → defringe → film；
    // color_smooth 只改 R/B、defringe 判据要求 B>R>G（此处 R<G）⇒ 唯一动了整段
    // 色度的只剩 PC3D。本开关强制关它，用于确认"平台是不是 PC3D 造的"。
    // ⚠ 只作 A/B：关掉后彩噪会回来、画面观感变化，不作为最终默认。
    if (!kPc3dAbOff && p.pc3d > 0.5f) {
      glFinish();   // 起点同步：只计本模块的工作，不背前面 pass 的尾巴
      const auto pc0 = std::chrono::steady_clock::now();
      pc3d_ok = passPc3d(dst_w, dst_h, c.nc, p,
                            cm_src ? cm_src : (cs_ok ? tex_cs_ : 0));
      glFinish();
      dbg_pc3d_ms_ = std::chrono::duration<float, std::milli>(
                         std::chrono::steady_clock::now() - pc0).count();
      if (!pc3d_ok && cm_src != 0) p4_src = cm_src;  // 0.12.162：中值输出接 P4
      if (pc3d_ok) {
        p4_src = tex_pc3d_;
        pc3d_replace = (p.pc3d < 1.5f);
      }
    }
    if (pc3d_replace) {
      // v0.10.182（用户拍板方案 A）：替换档 [54]=1 下**保留 defringe**。
      // 为什么：替换档照抄 PC 链路地位（ESD3D2 后直进 film）会旁路 defringe，
      // 而 defringe（Lab a 色相门 + 局部基线偏离检测 + 邻居加权替换）正是为
      // "逆光边缘品红窄带"设计的模块；替换档是色度平滑，对 1~3px 窄线效果有限
      // ⇒ 真机留下淡紫线（2026-10-07 样片：PC 无、我们 B-G 峰 +104）。
      // 输入 = tex_pc3d_（PC3D 输出，D4 场景线性，与 tex_dn_ 同域同几何）。
      // P4/D-1 仍然旁路（保持替换档的观感基调，只补回窄带紫边抑制）。
      // v0.10.196：色差域中值（darktable color_smoothing）—— 在 defringe 之前。
      // 顺序理由：中值是"无偏"的（不判色相，治任意色向的 1~3px 色差异常），
      // 先用它把窄带伪彩压掉，defringe 再处理剩下的紫/品红定向部分。
      // v0.10.209：**回退 v0.10.208 的"后置再跑一次"** —— 真机实测该改动有害：
      // 它把**中性/暗部像素染成蓝色**（例：(386,812) 由 RGB(79,73,73) 变成
      // (132,151,181)；(286,866) 由中性灰 (147,151,152) 变成 (138,147,186)），
      // 全图过冲像素数反而从 932 升到 1209。根因 = 后置的替换值取"最中性探测点"
      // 的色差，而该点落在**蓝色背景**上时就把蓝色**搬进了中性区**。
      // df_src 优先级：PC3D 输出 > 前置结果 > D4 原图。
      GLuint df_src = pc3d_ok ? tex_pc3d_
                    : (cm_src ? cm_src : (cs_ok ? tex_cs_ : tex_scene_));
      dbg_cs_on_ = cs_ok ? 1 : 0;
      df_ok = (p.defringe > 0.5f) ? passDefringe(dst_w, dst_h, p.df_gate, df_src) : false;
    } else {
      passChromaDenoise(dst_w, dst_h, c.effective_rot, c.nc, c.p->moire,
                        p.chroma_gate, p.cg_scope, p4_src);
      // 紫边抑制（v0.10.134）：P4 后、D-1 前。逆光边缘品红渐晕 56~66% 贴边
      // （P4/D-1 保边判据恰好保护它），Lab 域 defringe（RT 同形态）对症。
      // 失败/关闭回落 tex_dn_（照片优先红线）。成片链 only —— 预览链成本
      // 待真机评估后再接（3 pass 半分辨率 ≈ P4 的 1/3）。
      // v0.10.196：色差域中值（同替换档，输入为 P4 输出 tex_dn_）
      // v0.10.205：color_smooth 已前置（PC3D 之前）；叠加档下 P4 已吃它的输出
      // （p4_src = tex_pc3d_，而 PC3D 读 tex_cs_）⇒ 此处不重复。
      GLuint df_src = tex_dn_;
      df_ok = (p.defringe > 0.5f) ? passDefringe(dst_w, dst_h, p.df_gate, df_src) : false;
      // D 专项（v0.10.53）：多尺度色度降噪（4-32px 色斑）。失败则保持 P4 输出
      mst_ok = passChromaMultiScale(dst_w, dst_h, c.nc,
                                    df_ok ? tex_df_ : tex_dn_, p.chroma_gate);
    }
    // ── 0.12.109：降噪链**逐段**色度诊断（治"灰块到底哪一段抹的"）───────────
    // 现有 diag.stats 只到 D3/D4/FILM/D5 四个大阶段，中间这条链（PC3D → P4 →
    // defringe → D-1）是黑盒：真机 15:19:38 vs 15:20:09（只动降噪滑杆）的亮部
    // C*<6 占比从 21.1% 掉到 8.5%，但离线逐段复算只得到 P0 0.2% / P4 不动 /
    // D-1 反向 —— 说明离线复现与真机之间还有缺口，必须直接量真机的每一段。
    // 成本 = 每段一次 1/8 降采样 + 64 字节读回（diag_stats 关时零开销）。
    if (pc3d_ok) diagStatsTex("PC3D", tex_pc3d_, dst_w, dst_h, &p);
    if (!pc3d_replace) diagStatsTex("P4", tex_dn_, dst_w, dst_h, &p);
    if (df_ok) diagStatsTex("DEFRINGE", tex_df_, dst_w, dst_h, &p);
    if (mst_ok) diagStatsTex("D1", tex_scene_, dst_w, dst_h, &p);
    glFinish();
    dn_ms = std::chrono::duration<float, std::milli>(
                std::chrono::steady_clock::now() - n0)
                .count();
  }
  // v0.10.16：HR 已移入 passFilm（黑白点标定之后、still 才跑）——darktable 的
  // 高光重建与黑白点 autodetect 是配套的（裁剪阈值由 white_source 决定）。
  // D 专项：P5 成功时结果在 tex_scene_；否则回落 P4 的 tex_dn_（defringe 成功时
  // 为 tex_df_——紫边抑制输出，与 tex_dn_ 同域同几何）
  // v0.10.145：把当帧 WB 增益与 CCM 交给 HR mask 的阈值换算（runHighlightReconstruct
  // 用它算"RAW 满阱在 WB+CCM 域的范数下界"—— 旧式误用 RAW 域白点当阈值，真机上
  // threshold 是实际范数的 2.9 倍、命中率 0%）。CCM 为行主序（sensor→sRGB）。
  for (int i = 0; i < 3; ++i) hr_gain_[i] = c.gains[i];
  for (int i = 0; i < 9; ++i) hr_ccm_[i] = c.ccm[i];
  // 第 6 期（S12）：RAW 静拍链的输入本来就是**场景线性域** ⇒ hal_domain = false
  //（不做域比归一）。三条链的域标志至此齐全：预览=true、YUV 静拍=true、RAW 静拍=false。
  // ── 0.12.112：降噪链出口按 SNR 混合（高光取原图，见 kFsDnBlend）──────────
  // dn_out = 降噪链最终产物；与 tex_scene_（D4 原图）不同才需要混合。
  // mst_ok 时产物已写回 tex_scene_ ⇒ 无原图可比，跳过（该路径本身就是原图缓冲）。
  GLuint dn_out = 0;
  if (c.dn) {
    if (pc3d_replace) {
      dn_out = df_ok ? tex_df_ : (pc3d_ok ? tex_pc3d_ : 0);
    } else {
      dn_out = mst_ok ? 0 : (df_ok ? tex_df_ : tex_dn_);
    }
  }
  // ── 0.12.117：**停用** D4 域的降噪出口混合 ──────────────────────────────
  // 0.12.112~116 在 D4 域用"SNR / 饱和度 / G 过剩"三种判据决定哪块不降噪，
  // 结果是场景之间来回摆（灰块 ↔ 绿斑 ↔ 黄斑）——根因不是判据参数，而是
  // **判据放错了域**：D4 里"饱和伪彩"与"真实弱色度"在数值上不可分
  //（灰块 G/R-1 只有 0.022，而绿斑 0.05~0.12，两者与饱和程度也无单调关系）。
  // 正确机制在 **RAW 域**：饱和与否是传感器满阱的物理事实（Bento 的 highlight
  // mask 就建在那一层，PC 的 `hlVal = raw/whitePoint[c] ≥ 0.987` 同理），
  // 之后的颜色应由**短帧真值**（sat-fix 标记 1）或**邻域参考比**（HL opposed）
  // 重建，而不是靠"这块要不要降噪"来间接决定。
  // 代码保留（kFsDnBlend / passDnBlend），改判据重启用只需打开下面这行。
  const bool kDnBlendEnabled = false;
  GLuint dn_blend_tex = 0;
  dbg_dn_blend_ = (kDnBlendEnabled && dn_out != 0 && dn_out != tex_scene_ &&
                   passDnBlend(dst_w, dst_h, dn_out, tex_scene_, c.nc)) ? 1 : 0;
  if (dbg_dn_blend_) dn_blend_tex = tex_dnb_;
  // 成片链的**实际**场景线性输入（降噪/去边之后的那张）。gainmap 必须用它 ——
  // 用 tex_scene_（降噪前）会让 HDR 源与 SDR base 不是同一像素（defringe 改过色）。
  // 0.12.112：有 SNR 混合结果就用它（高光已回退到原图），否则走原选择逻辑
  const GLuint film_src =
      dn_blend_tex ? dn_blend_tex
      : (pc3d_replace ? (df_ok ? tex_df_ : tex_pc3d_)   // [54]=1：PC3D →(defringe)→ film
                      : ((c.dn && mst_ok) ? tex_scene_
                          : (c.dn ? (df_ok ? tex_df_ : tex_dn_) : tex_scene_)));
  // ── 自动曝光（v0.11.61）──────────────────────────────────────────────
  // 本张 RAW 统计 ⇒ 渲染端 EV。必须在 passFilm 之前：ev 要参与 filmic 的黑白点标定、
  // HR 阈值与 uExposure 三处（它们都以 pe.exposure_ev 为唯一来源）。
  // 只在 still 链追加；预览/YUV 静拍不受影响（域不同，见 AeMetering.h）。
  // ── 局部影调（tone equalizer）────────────────────────────────────────
  // v0.11.87 **顺序反转**（旧注释"teq 必须在 AE 之前"已被真机否掉）：
  // 旧链路 teq → AE 有个耦合病 —— teq 把高光压了 0.6EV，AE 随后又按压过的 P99.5
  // 提亮，两者都是乘性增益 ⇒ teq 的压缩被原样还回去（真机 10:00:29：teq 压完
  // aeEv +2.0，hi240 反而 10.03%）。现在 AE 先按原始 film_src 决策，teq 再按
  // "AE 为保高光放弃了多少中灰"算分区曲线，压缩真正落在 film 的输入上。
  // 旧顺序（teq → AE）有致命耦合：teq 压了高光，AE 随后又按压过的 P99.5 提亮，
  // 把 teq 的压缩原样还回去（真机 10:00:29：teq −0.6EV 后 aeEv +2.0，hi240 10.03%）。
  // 反过来以后 teq 的压缩才真正作用在 film 的输入上，且中灰由 AE 负责、teq 只补
  // 暗部，职责不重叠。
  FilmParams pe = p;
  // v0.11.94：**在成片链入口**按"短帧有没有真丢信息"决定要不要跑高光重建（HR + HL 色度）。
  // bracket 且短帧裁切 <0.5% ⇒ 高光信息都在（sat-fix 已给真实值），重建只会把它抹成
  // 灰块/斑块（真机 144940 杯口：hlCnt 9205、hlGRecon 1，而短帧 sat 只有 0.06%）。
  // 为什么不只依赖 passFilm 里的同判据：真机日志出现 `aeSat 0.0006` 与 `hlOk 1`
  // 自相矛盾（门控没生效），这里在**传参前**改掉，不依赖成员变量时序。
  // v0.12.0：判据改用 `c.bracket`（采集侧确定，brkRatio 4.0 即真），不再依赖
  // short_sat 的符号/数值。
  // v0.12.4 修正这里的结论（上一版引用有误，已按一手源码核对）：
  //   · darktable 官方对 opposed 的说法是"对小反光点和大面积都基本够用"
  //     （hlreconstruct/opposed.c 顶部注释），并没有"大面积必须 clip"；
  //   · clip 在 darktable 里是**非 RAW / 4bayer 的强制兜底**，不是大面积策略；
  //   · 大面积全饱和的专门方案是 segmentation based + recovery。
  // 这一段现在只关**小波 HR**（[41]/[45]）：它在 film 域修影调，与 RAW 域 HL 是两件事，
  // bracket 下短帧已给真值，film 域再重建只会把结构抹平。
  // ⚠ 不再在这里强制 `hl_grecon = 0`：那是**算法选择**（FilmParams[49]，设置页可切），
  // 强制它会让用户永远切不到自创档做 A/B —— 0.11.94~0.12.2 的"门控生效但画面没变"
  // 就有这一层的原因。
  if (c.bracket && !c.hdr) {
    pe.hr_raw = 0.f;
  }
  // ── 0.12.102：HDR 四帧**不关** HR，改用三态标记做条件重建 ────────────────
  // 旧式 `if (c.bracket)` 一刀切：P0 让 HDR 也进 sat-fix 后 brk=1 ⇒ HR 整个关掉，
  // 标记 0 的无真值区（短帧也烧了）就没人管，灰块只会从"大面积"变成"小面积"。
  // 现在 sat-fix 的 G 通道三态（0 无真值 / 1 短帧真值 / 2 长帧未饱和）经
  // hr_fix_mark_ 进 kFsHrMask：标记 ≥1 跳过重建、只有 0 才 inpaint —— 这正是
  // 0.11.86 的设计意图，之前只是被这个门控挡在门外。
  // 不用 short_sat 当判据：HDR 下 App 不传（= -1），且一旦透传会连带改 AE 的
  // force_hl / sat_frac（passAutoExposure 的 bracket_metering），与灰块无关的
  // 曝光行为会一起变。解耦：这里只认"有没有标记图"。
  // v0.12.71（PC 架构，用户拍板）：渲染端 AE **只诊断、不参与曝光**。
  // 曝光 = HAL 的 AE + planner + baseEv + 用户 EV；预览和成片同一次曝光，逐张独立。
  // 旧行为 `pe.exposure_ev += ae` 是自研的第二套 AE（补中灰/压高光），与 HAL 打架。
  const float ae = passAutoExposure(film_src, pe, c.short_sat);
  (void)ae;
  // v0.11.91：teq 的"大面积高光"判据从"软约束是否主导"改为**绝对亮度**：
  // P95 必须真的亮到白点以上（>2.0EV），且 P99.5 不比它高太多（<1.5×，说明是
  // 平坦大面积而非孤立亮点）。旧判据在"室内窗景"上也会触发 −2.2EV，把窗外压成
  // 灰块（真机 2011，与 2855 同场景 —— 正是"修一个场景搞坏另一个"）。
  const float p95_ev = ae_p95_ > 1e-9f ? std::log2(ae_p95_ / 0.1845f) : -99.0f;
  const float hl_ratio = (ae_p95_ > 1e-9f) ? ae_p995_ / ae_p95_ : 99.0f;
  // ── v0.12.16：判据改用 **AE 之后**的 P95（域错位修正）────────────────────────
  // `ae_p95_` 是**融合后、AE 之前**的分位，而 teq 作用在 `pe.exposure_ev += ae`
  // **之后**的图上。拿提亮前的亮度去决定"要不要压提亮后的高光"，两边不是同一张图。
  // 真机对照（同一片天空，`aeP95` 几乎相同、结论完全不同）：
  //   2456  aeP95 0.6473  aeEv −0.05  ⇒ 1.81 EV（提亮前）→ 1.76（提亮后）不触发 ⇒ 用户"正好"
  //   2426  aeP95 0.6473  aeEv +1.42  ⇒ 1.81 → **3.23** 用户"天空偏淡"
  //   4609  aeP95 0.6834  aeEv +1.62  ⇒ 1.89 → **3.51** 用户"天空偏灰"
  // 即：偏淡/偏灰那两个，正是"AE 把天空推过白点、而 teq 因为看着提亮前的值没接管"。
  // 判据改到提亮后 ⇒ 它们触发 −2.2EV，2456 仍然不触发（差 1.5 EV，容错够）。
  const float p95_ev_after = p95_ev + ae;
  // v0.12.16：`hl_ratio < 1.5` → `< 2.2`。这道门是 v0.11.91 为"室内窗景不误压"加的，
  // 但那个场景 P95 只有 1.57 EV、已经被亮度门挡住 ⇒ 这道门是冗余的；而它会误伤
  // "平坦天空 + 少量窗格高光"（4609 的 P99.5/P95 = 1.72 被挡在 1.5 外）。
  // 放宽到 2.2 后 4609 能进；孤立亮点的尾部比值通常 > 3，仍被挡在外面。
  const bool hl_dominant = p95_ev_after > 2.0f && hl_ratio < 2.2f;
  // v0.12.19：大面积高光的压深不再是固定 −2.2，而是**按"AE 后 P95 超出白点多少"**算：
  // 目标把 P95 拉回白点下方 0.6 EV。白点 = log2(1/0.1845) = 2.44 EV（相对中灰）。
  //   161952（云）：P95_after 3.52 → 超出 1.08 EV → 压 1.68 EV（旧固定 −2.2 过深 0.5）
  //   0.11.89 的天空（P95 4.0 EV）：超出 1.56 → 压 2.16 EV ≈ 旧 −2.2（与该场景标定吻合）
  const float p95_rel_white = p95_ev_after - std::log2(1.0f / 0.1845f);
  setTeqCurveFromAe(std::max(ae_mid_ev_ - ae, 0.0f), hl_dominant, p95_rel_white);
  // 0.12.165：D4 大半径 USM（收窄暗部软边；不动 tone curve）
  GLuint film_in = film_src;
  // 0.12.167：默认关（0 = 不跑）。165 强制开启时用户反馈"画面毁了" ⇒ 改成设置页显式开。
  if (p.d4_usm > 0.5f && passUsm(dst_w, dst_h, film_src)) film_in = tex_usm_;
  const GLuint teq_src = applyToneEq(dst_w, dst_h, film_in, hl_dominant);
  passFilm(dst_w, dst_h, pe, teq_src, /*still=*/true, /*hal_domain=*/false);
  dbg_prof_fm_ = profDump(fbo_work_, tex_work_, dst_w, dst_h);   // 0.12.164 诊断
  const auto t2 = std::chrono::steady_clock::now();
  // 诊断统计：kFsFilm 的整链输出（filmic → gamut → sat/split → look → mono）。
  // 放在 t2 之后，passMs 不受统计耗时污染。
  diagStatsTex("FILM-out", tex_work_, dst_w, dst_h, &p);

  // 卡 H2：交付档（c.ten_bit）与尺寸**一起**记账（理由见 submitStill 的同名段）
  if (cap_w_ != dst_w || cap_h_ != dst_h || cap_ten_bit_ != c.ten_bit) {
    if (tex_cap_) {
      glDeleteTextures(1, &tex_cap_);
      glDeleteFramebuffers(1, &fbo_cap_);
    }
    tex_cap_ = fbo_cap_ = 0;
    // @domain: D5 —— 成片输出（c.ten_bit ? GL_RGB10_A2 : RGBA8，见 OutputBitDepth.h）
    makeTex(&tex_cap_, &fbo_cap_, outputInternalOf(c.ten_bit), dst_w, dst_h);
    cap_w_ = dst_w;
    cap_h_ = dst_h;
    cap_ten_bit_ = c.ten_bit;
  }
  // E4-3 颗粒：成片种子 = f(输出尺寸, 输入内容指纹)（指纹由 submitRaw/submitRawBurst
  // 填进 ChainCtx）⇒ 同一张两次导出**逐位相同**、不同照片不同图案（验收判据 6）。
  // 预览/编辑器路径各自置 0（submitFrame/submitDisplay）⇒ 取景时图案稳定不闪。
  grain_seed_ = grainSeedFrom(c.grain_fp, dst_w, dst_h);
  // ── E4-5b（R1）：halo 半径归一化的基准短边 = **本张成片的短边**（画幅的权威来源）
  // 必须在 passOutput（其内部调 runHalation）**之前**设置：成片调用时
  // `min(w,h) == halo_ref_min_` ⇒ `level_shift = log2(1.0f) = 0.0f` ⇒ 权重逐位等于
  // 修正前（**成片零观感变化**）。预览/编辑器（短边 ≠ 成片短边）按同一基准归一化
  // ⇒ 三者的光晕**相对**半径一致（修掉了"预览 2.8× 于成片"的 WYSIWYG 破绽）。
  halo_ref_min_ = static_cast<float>(std::min(dst_w, dst_h));
  // 成片：斑马纹绝不进成片；dither 除数按交付档重标（255 / 1023）
  passOutput(dst_w, dst_h, p, true, false, outputMaxOf(c.ten_bit));
  // ── HDR gainmap（v0.11.50）─────────────────────────────────────────
  // 位置：必须在 passOutput **之后**（tex_cap_ 才是最终 SDR 成片，含 LUT/颗粒/dither），
  // 在 glReadPixels **之前**（同一次 GL 提交里算完，省一次 flush）。
  // 只在 8bit 档生成：10bit 档 tex_cap_ 是 GL_RGB10_A2，本 shader 按 sRGB 8bit 采样会错；
  // 而 HDR 交付只走 JPEG 8bit 路径（HEIC 的 HDR 走平台 HeifWriter，不经这里）。
  if (!c.ten_bit) {
    if (passGainmap(dst_w, dst_h, film_src, tex_cap_)) {
      dbg_gainmap_ok_ = 1;
    } else {
      dbg_gainmap_ok_ = 0;
    }
  } else {
    dbg_gainmap_ok_ = 0;
  }
  // readPixels 是异步提交，glFinish 后计时才是真实端到端耗时（拍照单张路径可接受）
  glFinish();
  // ⚠ **显式绑回 fbo_cap_**（v0.11.50 真机事故的保险）：原实现依赖"passOutput 把
  // fbo_cap_ 绑上后一直不恢复"，而中间插入的 passGainmap 会切 FBO。哪怕它内部的
  // 保存/恢复出任何纰漏，这里显式绑定都能保证成片从**正确的**附件读回 ——
  // 否则 glReadPixels 读默认帧缓冲 ⇒ 全黑（真机实测 avgLuma=0）。
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_cap_);
  glViewport(0, 0, dst_w, dst_h);
  out_rgba->resize(static_cast<size_t>(dst_w) * dst_h * 4);
  glReadPixels(0, 0, dst_w, dst_h, outputReadFormatOf(c.ten_bit),
               outputReadTypeOf(c.ten_bit), out_rgba->data());
  // 诊断统计 D5（最终成片，基准；零 GL 调用的 CPU 侧统计）。
  // ⚠ 卡 H2：该统计的口径是 **u8**（逐字节 /255 的直方图/均值），10bit 档下字节流是
  //   打包的 2_10_10_10_REV ⇒ 统计值无意义（会把 10bit 位段读成 8bit 灰度）。
  //   因此 10bit 档**跳过**（不是"改了统计"，而是"该口径不适用于该档"）——
  //   对应的诊断需求由 heif10.selfcheck / render.diag 的 bits:10 分支承担。
  if (!c.ten_bit) diagStatsU8("D5-out-u8", out_rgba->data(), dst_w, dst_h, &p);
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  const auto t3 = std::chrono::steady_clock::now();
  const auto ms = [](auto a, auto b) {
    return std::chrono::duration<double, std::milli>(b - a).count();
  };
  std::string kv =
      std::string("{\"uploadMs\":") + jnum(c.upload_ms, 6) +
      ",\"passMs\":" + jnum(ms(t1, t2), 6) +
      ",\"totalMs\":" + jnum(ms(t0, t3), 7) +
      // ── 强边缘色度门（v0.10.174，FilmParams[51]/[52]/[53]）─────────────────
      // cgK = 下发的 κ（0 = 关）；cgCap = shader 常量 kCgRel = 16.0（**相对**上限
      // 16·σc；v0.10.173 这里是绝对 0.2，正是 73.7% 像素被放宽的来源）；
      // cgScope = 1 = PC 的 w2 权重形式（v2 默认）、0 = 字面版（只放大 wc）；
      // dfG = 紫边判据自适应强度。
      // cgK > 0 时 P4 的 a 通道 = **门控开度** smoothstep(0.10, 0.30, |∇L|)
      // ⇒ cgMean/cgP95/cgCov 直接读作"门控覆盖率"（cgCov = 开度 > 0.5 的面积比；
      // 真机健康区间：cgMean 应 ≤ 0.15、cgCov ≤ 0.05 —— 离线 4 张样片 cgGate
      // 覆盖 < 8%；v0.10.173 的旧口径下这个数是 100%）。
      // ⚠️ cgK > 0 时下面 moMean/moP95/moCov 是**同一组数**（档 A 的强度图观测
      // 被让位），moSc 此时无意义（档 A 关时恒 1）。
      ",\"cgK\":" + jnum(dbg_cg_k_, 4) +
      ",\"cgCap\":" + jnum(dbg_cg_cap_, 4) +
      ",\"cgScope\":" + jnum(dbg_cg_scope_, 3) +
      ",\"cgMean\":" + jnum(dbg_cg_k_ > 0.f ? dbg_mo_mean_ : 0.f, 6) +
      ",\"cgP95\":" + jnum(dbg_cg_k_ > 0.f ? dbg_mo_p95_ : 0.f, 6) +
      ",\"cgCov\":" + jnum(dbg_cg_k_ > 0.f ? dbg_mo_cov_ : 0.f, 6) +
      ",\"dfG\":" + jnum(dbg_df_gate_, 4) +
      // ── PC ESD3D2 完整移植（v0.10.175，FilmParams[54]/[55]）───────────────
      // pc3dOn = 1 表示本帧真的跑了照抄档且 P4 已改读 tex_pc3d_；
      // pc3dM  = 本轮实际 MSIZE（PC 公式 + 本机噪声模型，7..21）；
      // pc3dSc = 尺度结构实际用的降采样倍数（1 = 全分辨率单趟，≥2 = 走
      //          gaussdown + guided upsample）；
      // pc3dMs = 本模块（含 glFinish 同步）耗时，与 dnMs 同量级为健康。
      // 判读：pc3dOn=0 而 [54]>0.5 ⇒ **没生效**（shader 链接失败或纹理分配失败）；
      //       pc3dSc 恒 1 而噪声模型正常 ⇒ scale 判定与 PC 公式不一致；
      //       pc3dMs ≫ dnMs ⇒ 成本过高（把 [55] 调小或关 [54] 的信号）。
      ",\"pc3dOn\":" + std::to_string(dbg_pc3d_on_) +
      ",\"pc3dM\":" + std::to_string(dbg_pc3d_m_) +
      ",\"pc3dK\":" + jnum(dbg_pc3d_k_, 6) +
      ",\"pc3dSc\":" + std::to_string(dbg_pc3d_scale_) +
      ",\"pc3dMs\":" + jnum(dbg_pc3d_ms_, 7) +
      ",\"w\":" + std::to_string(w) + ",\"h\":" + std::to_string(h) +
      ",\"demosaic\":\"" + (c.amaze ? "amaze" : (c.rcd ? "rcd" : "bilinear")) +
      "\"" + ",\"dem\":\"" + (c.amaze ? "amaze" : (c.rcd ? "rcd" : "bilinear")) +
      "\"" + ",\"amazeMs\":" + jnum(dbg_amaze_ms_, 8) +
      ",\"amazeR\":[" + jnum(dbg_amaze_min_, 6) + "," +
      jnum(dbg_amaze_max_, 6) + "]" +
      ",\"amazeRd\":" + jnum(dbg_amaze_rd_ms_, 8) +
      ",\"amazeCpu\":" + jnum(dbg_amaze_cpu_ms_, 8) +
      ",\"amazeUp\":" + jnum(dbg_amaze_up_ms_, 8) +
      // 0.12.147：AMaZE 与 RCD 现在吃同一份 tex_mos_（HL 前置 + P0 之后）
      // ⇒ 两档的差异只剩去马赛克算法本身，amazeSrc 统一记 "p0"。
      ",\"amazeSrc\":\"" + (c.amaze ? "p0" : "-") +
      "\"" +
      // RAW 域高光重建（darktable highlights / inpaint opposed）：生效性 + 色度
      // 校正量 + 样本数。hlOk=0 而 hr_raw 开 = pass 失败（已回退原行为，照片不受
      // 影响，照片优先红线）；hlCnt < 100 时 darktable 语义 = 色度校正不启用。
      ",\"hlOk\":" + std::to_string(dbg_hl_ok_) +
      ",\"hlMs\":" + jnum(dbg_hl_ms_, 6) +
      // 注意：jnum(v, n) 的 n 是**字符数**（std::to_string 后 resize），不是小数
      // 位数 —— 样本数是整数，用 to_string 输出，否则 40077 会被截成 "4"
      //（2026-10-03 真机据此误判"样本只有 4 个、highlights 没生效"）。
      ",\"hlCnt\":" + std::to_string(static_cast<int>(dbg_hl_cnt_ + 0.5f)) +
      ",\"hlMarkOn\":" + std::to_string(dbg_hl_mark_on_) +
      ",\"hrMarkOn\":" + std::to_string(dbg_hr_mark_on_) +
      ",\"hlChroma\":[" + jnum(dbg_hl_chroma_[0], 6) + "," +
      jnum(dbg_hl_chroma_[1], 6) + "," + jnum(dbg_hl_chroma_[2], 6) + "]" +
      // 全局兜底比例（v0.10.143 起为**池化比** mR/mG、mB/mG，不再是块比值加权）与
      // 权重和：真机据此核对兜底比例是否接近该场景中性比例（应 ~0.5/0.66 量级）；
      // 明显跑偏 = w 加权或配对口径有问题。hlRefW 上限 = 块数（4080×3072 → 12288）。
      ",\"hlRgG\":" + jnum(dbg_hl_rgg_, 6) +   // 池化比 ΣR/ΣG（真机对数用）
      ",\"hlBgG\":" + jnum(dbg_hl_bgg_, 6) +
      ",\"hlRefW\":" + jnum(static_cast<float>(dbg_hl_refw_), 8) +
      // 三级参考命中占比（%，v0.10.139）：在"32 块局部参考不足"的格里，中尺度
      // （hlRef2Hit）与全图兜底（hlRefGHit）各占多少 —— 真机看饱和天空用了哪一级。
      ",\"hlRef2Hit\":" + jnum(dbg_hl_r2hit_, 6) +
      ",\"hlRefGHit\":" + jnum(dbg_hl_rghit_, 6) +
      // ── v0.10.167 HR 前置的生效性字段（照 p0s/nnOk 的教训：改完必须能自证）──
      // hlPre=1 表示 HR 已前置到 P0 之前（输入 = P0 前的 mosaic、输出是该 P0 的输入）。
      // hlStatSrc / hlDecideSrc = 统计源（uStat，喂 mask/chrominance）与判定源
      // （uMos，喂 apply 与参考色度场）**实际绑定的纹理名** —— 真机据此直接回答
      // "两者是否指向不同纹理"。exp21 翻车的结构性根因就是二者共用一个 src、
      // 没有独立旋钮（换统计口径时把判定也换了 ⇒ 修复面 47269→63903、画面更红）。
      // 前置生效时两者同为 P0 前的 mosaic；hlPre=0 的 "P0post" = 未前置/回退。
      ",\"hlPre\":" + std::to_string(dbg_hl_pre_) +
      ",\"hlStatSrc\":\"" + dbg_hl_stat_src_ + "\"" +
      ",\"hlDecideSrc\":\"" + dbg_hl_decide_src_ + "\"" +
      // hlWClamp = P0 实际收到的 uWClamp（1 = 权重/σ 用钳位值、信号用原值）。
      // ⚠ 只有 P0 走 kFsRawDenoiseFused / kFsRawDenoiseFusedTs 时才记得到；
      // 单帧未前置（hr_raw 关）路径不经过它 ⇒ 该字段为 0 是正常的。
      ",\"hlWClamp\":" + jnum(dbg_hl_wclamp_, 4);
  // 0.12.147：AMaZE 也走同一条 D3→D4 收口（CCM/P4/color_smooth 共用）
  if (c.rcd || c.amaze) {
    // ccmMs（v0.10.154，第 4a 期新增）：D3→D4 独立 CCM pass 的实测成本。
    // 它是本期唯一的运行时开销（新增一次全屏 pass：读 100MB + 写 100MB），
    // 按铁律须由真机数据决定是否接受；跨版本对比时需同场景同 ISO。
    // rcdSplit/rcdAMs/rcdBMs（v0.10.180，[58]）：P3 拆双 pass 的生效性与
    // 分 pass 成本 —— rcdSplit=0 而 [58]>0.5 ⇒ 未生效；rcdAMs+rcdBMs 对比
    // 旧单 pass 成本（多一次全分辨率 RGBA16F 往返）。
    kv += ",\"dirMs\":" + jnum(dir_ms, 6) +
          ",\"gMs\":" + jnum(g_ms, 6) +
          ",\"ccmMs\":" + jnum(ccm_ms, 6);
    // 局部影调（tone equalizer）：开关状态（关 = 完全跳过，逐位不变）
    kv += ",\"teq\":" + std::to_string(FrameRenderer::toneEq() ? 1 : 0) +
          ",\"teqMs\":" + jnum(dbg_teq_ms_, 6) +
          ",\"teqHl\":" + jnum(dbg_teq_hl_, 9) +
          ",\"teqSh\":" + jnum(dbg_teq_sh_, 9) +
          // v0.12.31：**local_tone 的实际下发值**（诊断）。它是暗部线性提亮比例，
          // 默认 0.25（标定旋钮，见 Stage3EngineScopeAuditTest），但预设/编辑器
          // 可以把它外推到 0.475 甚至更高（EditorPresetAmountTest 的"无 UI 上界"）。
          // 之前日志里没有这个字段 ⇒ 无法判断"暗部偏亮"里它占多少。
          ",\"lt\":" + jnum(pe.local_tone, 6);
    kv += ",\"rcdSplit\":" + std::to_string(dbg_rcd_split_);
    kv += ",\"rcdAMs\":" + jnum(dbg_rcd_a_ms_, 7);
    kv += ",\"rcdBMs\":" + jnum(dbg_rcd_b_ms_, 7);
  }
  if (c.dn) {
    // v0.10.109：nzSrc 取值改 "ext"/"legacy"（原 "cam2"）—— 原值与 camera 侧
    // raw.noise 的 src:"builtin" 撞名：同一张照片里两个 nzSrc 语义不同（引擎侧只
    // 表达"外部下发 vs 引擎回退"），真机曾据此误读模型来源。
    // C 专项（2026-09-29）：噪声模型诊断族 —— nzSrc/nzIso/nzS/nzO = 模型来源与
    // 逐 ISO 系数（WB 前，[0,1] 域）；p0A/p0B/p4A/p4B = 实际下发 shader 的 (A,B)
    // （WB 后、含强度 k²；σ² = A·x + B）；ns/nsHal = 典型 σ（融合后/单帧）；
    // 验收口径：σ 随 ISO（nzS/O 变）与信号变化，P0/P4/A 同源。
    //（原「rms/rmsSig = tile RMS 采样（中心/左上/右上/左下）与模型代入后的 σ」随
    // passTileRms 于第 1a 期字段删除而失效，第 1b 期同步清理本注。）
    const auto fe = [](float v) {
      char b[32];
      snprintf(b, sizeof(b), "%.4e", v);
      return std::string(b);
    };
    const auto fa3 = [&](const float* v) {
      return "[" + fe(v[0]) + "," + fe(v[1]) + "," + fe(v[2]) + "]";
    };
    kv += std::string(",\"dn\":") + jnum(c.dn_k, 4) +
          ",\"nzSrc\":\"" + (c.nc.model_src ? "ext" : "legacy") + "\"" +
          ",\"nzS\":" + fa3(c.nm.S) + ",\"nzO\":" + fa3(c.nm.O) +
          ",\"nzG\":[" + jnum(c.gains[0], 6) + "," +
          jnum(c.gains[1], 6) + "," +
          jnum(c.gains[2], 6) + "]" +
          ",\"kP0\":" + jnum(c.nc.k_p0, 4) +
          ",\"kP4\":" + jnum(c.nc.k_p4, 4) +
          ",\"p0A\":" + fa3(c.nc.p0a) + ",\"p0B\":" + fa3(c.nc.p0b) +
          ",\"p4A\":" + fa3(c.nc.p4a) + ",\"p4B\":" + fa3(c.nc.p4b) +
          ",\"ns\":" + jnum(c.ns, 6) +
          ",\"nsHal\":" + jnum(c.ns_raw, 6) +
          ",\"dnMs\":" + jnum(dn_ms, 6) +
          // 紫边抑制（v0.10.134）：ok = 执行与否，cavg = Lab chroma 全图均值
          //（读回值，评估判定阈值是否跟场景走），hit 需 GPU 计数暂缺
          ",\"df\":" + std::to_string(df_ok ? 1 : 0) +
          ",\"dfCavg\":" + jnum(dbg_df_cavg_, 6) +
          // v0.10.198 色差中值滤波诊断：csK = 下发的 FilmParams[59]；csOk = shader 是否
          // 链接成功（0 ⇒ prog_cs_=0、pass 静默失效 = 196/197 "完全没效果"的根因）；
          // csOn = 本帧 pass 是否真的执行。
          ",\"csK\":" + jnum(dbg_cs_k_, 3) +
          ",\"csOk\":" + std::to_string(dbg_cs_ok_) +
          ",\"csOn\":" + std::to_string(dbg_cs_on_) +
          // 0.12.162：色差域 3×3 中值（darktable color_smoothing）是否真的跑了
          ",\"cmOn\":" + std::to_string(dbg_cm_on_) +
          ",\"usmOn\":" + std::to_string(dbg_usm_on_) +
           // 档 A（v0.10.165）伪彩感知色度降噪：moGain = 下发强度（0 = 关/A/B 通路），
           // moMean/moP95 = 伪彩强度图全图均值/P95（P95 为 4 锚点分段线性 CDF 近似），
           // moCov = moire>0.5 的像素占比，moSc = σc 实际平均放大倍数
           //（= 1 + moGain·moMean；封顶未触发时精确）。口径见 kFsMoireStat。
           ",\"moGain\":" + jnum(dbg_mo_gain_, 4) +
           ",\"moMean\":" + jnum(dbg_mo_mean_, 6) +
           ",\"moP95\":" + jnum(dbg_mo_p95_, 6) +
           ",\"moCov\":" + jnum(dbg_mo_cov_, 6) +
           ",\"moSc\":" + jnum(dbg_mo_sc_, 6);
  }
  // 0.12.168：剖面 dump 从 `if (c.dn)` 块移出 —— 降噪关（dn=0）时同样是定位
  // "灰斑平台在哪一层"的唯一现场证据（10-29 的 143435/144543 两张全缺）。
  kv += ",\"d4Y\":\"" + dbg_prof_d4_ + "\"";
  kv += ",\"fmY\":\"" + dbg_prof_fm_ + "\"";
  kv += ",\"tone\":" + jnum(p.tone_strength, 3);
  kv += ",\"ev\":" + jnum(p.exposure_ev, 5);
  // v0.11.61 自动曝光（本张统计）：`aeEv` 是**追加**到 ev 上的量（实际生效 = ev + aeEv），
  // 离线对拍务必把两者相加。aeWhy：0 none / 1 mid / 2 highlight / 3 clamped / 4 invalid。
  kv += ",\"aeOn\":" + std::to_string(autoExposure() ? 1 : 0);
  kv += ",\"aeEv\":" + jnum(ae_ev_, 5);
  kv += ",\"aeMid\":" + jnum(ae_mid_ev_, 5);
  kv += ",\"aeHl\":" + jnum(ae_hl_ev_, 5);
  kv += ",\"aeP50\":" + jnum(ae_p50_, 6);
  // v0.11.91：中灰锚点（几何平均）—— 与 aeP50 对比可看出"P50 低估了多少"
  kv += ",\"aeGeo\":" + jnum(ae_p_geo_, 8);
  kv += ",\"aeRobust\":" + std::string(ae_robust_on_ ? "1" : "0");
  kv += ",\"aePRobust\":" + jnum(ae_p_robust_, 8);
  kv += ",\"aeTargetMode\":" + std::string(ae_robust_on_ ? "\"robust\"" : "\"pv\"");
  kv += ",\"aeDispP50\":" + jnum(g_pv_disp_p50, 5);
  kv += ",\"aeDispEv\":" + jnum(g_disp_ae_ev, 4);
  kv += ",\"aeTarget\":" + jnum(ae_target_mid_, 8);
  kv += ",\"aeP995\":" + jnum(ae_p995_, 6);
  kv += ",\"aeP999\":" + jnum(ae_p999_, 6);
  kv += ",\"aeWhy\":" + std::to_string(ae_why_);
  // v0.11.77：裁切比例（PC maximumClippedFraction 口径）。v0.11.87 起 bracket 时
  // = 采集端短帧实测（真丢信息的比例），非 bracket = 融合后 >=0.98 白点的比例。
  kv += ",\"aeSat\":" + jnum(ae_sat_frac_, 6);
  // v0.11.87：高光判据的输入域 —— 白点（2.438 RAW / 4.8 显示）与短帧裁切比例。
  kv += ",\"aeWhiteEv\":" + jnum(ae_white_ev_, 4);
  kv += ",\"aeBrkSat\":" + jnum(ae_brk_sat_, 6);
  // v0.11.88：融合后 >=0.98 白点的占比（"大面积高光"的直接证据）与 P95 软约束
  kv += ",\"aeSatRaw\":" + jnum(ae_sat_raw_, 6);
  kv += ",\"aeHlSoft\":" + jnum(ae_hl_soft_ev_, 5);
  kv += ",\"aeP95\":" + jnum(ae_p95_, 6);
  // 用户 WB 补偿的**原始值**（目标/估计增益比）：1,1 = 自动/中性。
  // 【第 2 期 S13】三条链实际消费的都是 G 归一后的 (wb_r/wb_g, 1, wb_b/wb_g)
  // —— RAW 链见 prepareRawCommon，预览 OES / YUV 静拍见 wbCompGNorm()。
  // 本字段保留原始值（不除 wb_g），离线对拍须自行归一后再与链路口径比较。
  kv += ",\"wb\":[" + jnum(p.wb_r, 6) + "," +
        jnum(p.wb_g, 6) + "," +
        jnum(p.wb_b, 6) + "]";
  // U-3（[4] + [88][89] 的**拍照链路生效值**，与 render.style_diag 的同名字段同义）：
  // `ct` = 用户/预设的对比度，与同出口的 `cp`（自动白点上探补偿）**相乘**才是实际进入
  // spline 求解的 contrast_param ⇒ 真机核对"预设的 [4] 是否真的进了引擎"看这两个字段；
  // `wbTot` = [1..3] × [88][89] 折算后的**实际**白平衡增益（裸值，不除 wb_g —— 与
  // 相邻 `wb` 字段同口径；三条链口的 G 归一在离线对拍时自行折算）。
  kv += ",\"ct\":" + jnum(p.contrast, 5);
  {
    float wb_tt_diag[3];
    wbTempTint(p, wb_tt_diag);
    kv += ",\"wbTot\":[" + jnum(wb_tt_diag[0], 5) + "," +
          jnum(wb_tt_diag[1], 5) + "," + jnum(wb_tt_diag[2], 5) + "]";
  }
  // v0.7.3：ag 一并废除 —— 记录 filmic 的实际标定（EV，相对中灰 18.45%）与场景分位，
  // 供离线用同一组 EV 复算 spline 系数对拍（tools/tuning/filmic_offline.py 的 compute_spline）。
  // substr(0,9)：filmic_verify.py 用这些值复算 spline，截断到 "-5.024" 会把
  // 1.4e-4 的输入误差放大成 2.4e-4 的 power 误差，自检假阳性（v0.7.9 实测）
  kv += ",\"blackEv\":" + jnum(ev_black_, 9);
  kv += ",\"whiteEv\":" + jnum(ev_white_, 9);
  // v0.10.27：白点高光感知判据诊断 —— w99/w999 = P99/P99.9 的 raw 判据值（EV，钳制前），
  // cp = 高光上探的 contrast_param 补偿（1 = 未上探）。white 上探 = w999 驱动、
  // w > clamp(w99, 2.438, 4.556)；固定曲线/未上探时 cp 恒 1。
  // v0.10.28：wmax = 采样 max 判据值（上探主驱动，+0.4 security 后与 w99+1.5 取小）。
  kv += ",\"w99\":" + jnum(last_w99_, 6);
  kv += ",\"w999\":" + jnum(last_w999_, 6);
  kv += ",\"wmax\":" + jnum(last_wmax_, 6);
  kv += ",\"cp\":" + jnum(last_cp_, 6);
  kv += ",\"linMid\":" + jnum(last_lin_mid_, 6);
  kv += ",\"pLo\":" + jnum(last_p_lo_, 7);
  kv += ",\"pHi\":" + jnum(last_p_hi_, 7);
  // spline 系数（v0.7.3 自检）：真机日志 → 用 tools/tuning/filmic_verify.py 的
  // compute_spline 按同一组 EV 复算，逐项对比 m1..m5 —— 系数不一致就是实现错了。
  // E4-4（per-channel）：fM* 是 **9 个数**，通道优先展平
  // [R.toe,R.sh,R.lin, G.toe,G.sh,G.lin, B.toe,B.sh,B.lin]；fLat 是 6 个数
  // [toeR,toeG,toeB, shR,shG,shB] = 求解出的过渡区位置（随场景 EV 变）；
  // latToe/latSh 是**实际生效的 latitude**（clamp 后，逐通道）—— 核对 [5..10]
  // 是否真进了引擎看这两个字段（fLat 依赖 EV，不能反推参数值）。
  {
    const auto j3 = [](const float* v) {
      return "[" + std::to_string(v[0]) + "," + std::to_string(v[1]) + "," +
             std::to_string(v[2]) + "]";
    };
    const auto j9 = [](const float m[3][3]) {
      std::string s = "[";
      for (int ch = 0; ch < 3; ++ch) {
        for (int seg = 0; seg < 3; ++seg) {
          if (ch > 0 || seg > 0) s += ",";
          s += std::to_string(m[ch][seg]);
        }
      }
      return s + "]";
    };
    kv += ",\"fM1\":" + j9(filmic_.m1) + ",\"fM2\":" + j9(filmic_.m2) +
          ",\"fM3\":" + j9(filmic_.m3) + ",\"fM4\":" + j9(filmic_.m4) +
          ",\"fM5\":" + j9(filmic_.m5);
    kv += ",\"fLat\":[" + std::to_string(filmic_.lat_min[0]) + "," +
          std::to_string(filmic_.lat_min[1]) + "," +
          std::to_string(filmic_.lat_min[2]) + "," +
          std::to_string(filmic_.lat_max[0]) + "," +
          std::to_string(filmic_.lat_max[1]) + "," +
          std::to_string(filmic_.lat_max[2]) + "]";
    kv += ",\"latToe\":" + j3(filmic_.lat_toe);
    kv += ",\"latSh\":" + j3(filmic_.lat_sh);
    kv += ",\"fPow\":" + std::to_string(filmic_.output_power) +
          ",\"fCon\":" + std::to_string(filmic_.contrast);
  }
  kv += ",\"auto\":" + std::to_string(p.auto_tone > 0.5 ? 1 : 0);
  kv += ",\"lt\":" + jnum(p.local_tone, 4);
  kv += ",\"sh\":" + jnum(p.sharpen, 4);
  kv += ",\"gm\":" + jnum(p.gamut, 4);
  // LSC：网格尺寸（"off" = 恒等/A-B 关闭档）
  kv += std::string(",\"lsc\":\"") +
        (c.lsc_ok ? std::to_string(c.lsc_cols) + "x" + std::to_string(c.lsc_rows)
                  : "off") +
        "\"";
  // v0.10.15/16：HR 观测（hrMs = 高光重建耗时；clipB = 64×48 块中命中裁剪的块数，
  // -1 = 未执行/开关关/无裁剪）。值由 passFilm 内的 HR 写入成员变量。
  kv += ",\"hr\":" + jnum(p.hr_wavelet, 3);
  kv += ",\"hrMs\":" + jnum(hr_ms_, 6);
  kv += ",\"clipB\":" + std::to_string(hr_blocks_);
  // v0.10.121/145：HR 输入域（曝光前）的白点 EV。**v0.10.145 起它不再是阈值来源**：
  // 阈值改为 kSaturationNormCalib × |clip(CCM·(0,gain_G,0))|（满阱范数标定系数，
  // 域换算，见 runHighlightReconstruct），
  // 因为旧式把 RAW 域白点当成了 WB+CCM 域的范数门槛（真机 hrWhiteEv 5.194 时
  // threshold 6.75 是实际范数 2.355 的 2.9 倍 ⇒ 命中率 0%）。字段保留供对照，
  // 实际阈值见 hrThr。
  // 与 tools/hr-mask-check.py --white-ev 同一口径（改阈值后靠它核对真机覆盖面对不对）。
  kv += ",\"hrWhiteEv\":" + jnum(hr_white_ev_, 6) +
        ",\"hrThr\":" + jnum(dbg_hr_thr_, 7);   // v0.10.145 实际下发的 mask 阈值
  // v0.10.123：1 = mask/chrominance 取自 P0 之前的 tex_fused_（darktable 位置）
  kv += ",\"hrStatRaw\":" + std::to_string(hr_stat_raw_);
  // v0.10.169：apply 支实际下发的 uGRecon。1 = G 通道重建开（默认 / 现有行为）；
  // 0 = A/B 关（apply 只留 opposed 外推分支）。hr_raw 关时本 pass 不执行，此处为
  // 调用点写入的参数真值（不是 pass 回填值），仍然可读。
  kv += ",\"hlGRecon\":" + std::to_string(dbg_hl_grecon_);
  // 0.12.133（方案 B）：hlMaskTx=1 表示 RCD 拿到了 RAW 饱和掩码（alpha 链生效，
  // PC3D 收手有据可依）；hlInp=1 表示全饱和区亮度 inpaint 真跑了（短帧真值有效）。
  kv += ",\"hlMaskTx\":" + std::to_string(dbg_hl_mask_tx_) +
        ",\"hlInp\":" + std::to_string(dbg_hl_inp_) +
        ",\"hlInpMs\":" + jnum(dbg_hl_inp_ms_, 8);
  // v0.10.179：apply 支实际下发的 uRbRecon。1 = clip 过渡带 R/B 满阱比例反推开；
  // 0 = 默认旧行为。hr_raw 关时 pass 不执行，此处为调用点写入的参数真值。
  kv += ",\"hlRb\":" + std::to_string(dbg_hl_rbrecon_);
  // v0.10.170：横向色差校正的估计结果与执行状态（镜头横向色差 / lateral CA）。
  // caK = **实际采用**的径向系数（位移 = k·r，r 自画面中心；0 = 未通过判据）；
  // caKraw = 原始估计（未过判据）—— 用来分辨"位移太小"与"内外半区不一致"；
  // caShiftR/B = 画面角部的实际位移（px，已乘强度并 clamp 到 ±4）；
  // caApplied = 1 表示真的跑了校正 pass（0 = 关闭 / 估计失败 / 判据未通过
  // ⇒ 走的是原 tex_work_，逐位等于加本模块之前）。
  kv += ",\"dnb\":" + std::to_string(dbg_dn_blend_) +
        ",\"caK\":[" + jnum(dbg_ca_kr_, 10) + "," + jnum(dbg_ca_kb_, 10) + "]";
  kv += ",\"caKraw\":[" + jnum(dbg_ca_kraw_r_, 10) + "," +
        jnum(dbg_ca_kraw_b_, 10) + "]";
  kv += ",\"caShiftR\":" + jnum(dbg_ca_shift_r_, 9) +
        ",\"caShiftB\":" + jnum(dbg_ca_shift_b_, 9);
  kv += ",\"caApplied\":" + std::to_string(dbg_ca_applied_);
  kv += ",\"caMs\":" + jnum(dbg_ca_ms_, 7);
  // v0.10.122：opposed chrominance 的分通道分子/样本数。chroma = sum/cnt（cnt>100），
  // 与 tools/hl-opposed-check.py 的 ③ 同口径 —— 用来定位"引擎 chroma 偏大 3.4×"。
  // 注意 jnum(v,n) 的 n 是**字符数**不是小数位（2026-10-03 教训）：sum 可达 ±5e4
  kv += ",\"hlSum\":[" + jnum(static_cast<float>(dbg_hl_sum_[0]), 14) + "," +
        jnum(static_cast<float>(dbg_hl_sum_[1]), 14) + "," +
        jnum(static_cast<float>(dbg_hl_sum_[2]), 14) + "]";
  kv += ",\"hlCnt3\":[" + jnum(dbg_hl_cnt3_[0], 12) + "," + jnum(dbg_hl_cnt3_[1], 12) +
        "," + jnum(dbg_hl_cnt3_[2], 12) + "]";
  // E4-3 颗粒（granularity）：成片链的生效值。颗粒段在 passOutput 内，已在本函数内
  // 执行完 ⇒ 字段行晚于调用（check-diag-fields.py 的同一条纪律）。
  // grainSeed = "同一张两次导出逐位相同"的核对依据（内容指纹派生，见 grainSeedFrom）。
  kv += ",\"grain\":" + std::to_string(p.grain_amount);
  kv += ",\"grainSz\":" + std::to_string(p.grain_size);
  kv += ",\"grainMb\":" + std::to_string(p.grain_midtone_bias);
  kv += ",\"grainPivot\":" + std::to_string(p.grain_pivot);
  kv += ",\"grainSeed\":" + jnum(grain_seed_, 6);
  // ── U-4（2026-10-11）显示域五项：[78] 清晰度 + [90..94] 影调分区/褪色 ──────────
  // 字段 = **实际下发的生效值**（不是默认值），用来在真机日志里核对"预设套用后这五项
  // 真的进了引擎"。三段的门控与数学都在 passOutput 内（已在本函数内执行完）⇒ 字段行
  // 晚于调用，符合 check-diag-fields.py 的纪律（前置调用 passOutput 必须更早）。
  //  · clarR = 实际下发的清晰度环半径（px）—— 与 passOutput 同源（clarityStepPx）；
  //  · toneGate = [90..93] 四项门控（0 ⇒ shader 整段跳过；[94] fade 由 shader 内自判）；
  //  · 名字全部带 `tone`/`clar` 前缀：submitDisplay 的 diag.stats 快照已占用
  //    `toneOn`/`hl`/`wh`/`fade`（编辑器链路的同义字段），本脚本取 hits[0] ⇒ 同名会让
  //    核对指到 submitDisplay 上（U-1 的 splitW/splitC/monoOn 是同一条纪律）。
  {
    const float clar_px = clarityStepPx(dst_w, dst_h);
    const bool tone_on = p.highlights != 0.0f || p.shadows != 0.0f ||
                         p.whites != 0.0f || p.blacks != 0.0f;
    kv += ",\"clar\":" + jnum(p.clarity, 6) + ",\"clarR\":" + jnum(clar_px, 7);
    kv += ",\"toneGate\":" + std::to_string(tone_on ? 1 : 0);
    // ⚠ 每个字段独立一行（check-diag-fields.py 按行取 "kv" 写入语句，折行会导致漏检）
    kv += ",\"toneHi\":" + jnum(p.highlights, 6);
    kv += ",\"toneSh\":" + jnum(p.shadows, 6);
    kv += ",\"toneWh\":" + jnum(p.whites, 6);
    kv += ",\"toneBl\":" + jnum(p.blacks, 6);
    kv += ",\"toneFade\":" + jnum(p.fade, 6);
  }
  // ── E4-5 halation（光晕）：成片链路的生效性 + 四参数 ────────────────────────
  // halo 链在 passOutput 内、OETF 之前执行（已在本函数内跑完）⇒ 字段行晚于调用
  // （check-diag-fields.py 的同一条纪律）。
  // ⚠ 字段名与 submitDisplay 出口**同名**：本脚本取 hits[0]（行号更小的
  //   submitDisplay）⇒ 白名单核对的是编辑器出口，成片侧由真机日志核对（grain 同款）。
  kv += ",\"haloOn\":" + std::to_string(halo_on_);
  kv += ",\"haloMs\":" + jnum(halo_ms_, 7);
  kv += ",\"haloStrength\":" + jnum(p.halation_strength, 6);
  kv += ",\"haloThr\":" + jnum(p.halation_threshold, 6);
  kv += ",\"haloRadius\":" + jnum(p.halation_radius, 6);
  kv += ",\"haloWarmth\":" + jnum(p.halation_warmth, 6);
  // E4-5b（R1）：halo 半径归一化的**基准短边**（= 成片短边）。核对"预览与成片
  // 是否用同一基准"就靠它 —— 两侧同一进程内应恒等（成片调用会更新它）。
  kv += ",\"haloRef\":" + jnum(halo_ref_min_, 7);
  // ── SG-1 柔光（soft glow）：生效性 + 三参数 ────────────────────────────────
  //  · glowOn = 本帧柔光链**真的执行了**（0 且 [95] > 0 ⇒ 静默失败：program 缺失 /
  //    纹理分配失败 / FBO 不完整 —— 与 haloOn 同口径，真机一眼可辨）。
  //  · glowMs = CPU 侧提交耗时（含共享金字塔 ①②③ 与 1 个合成 pass；GPU 完成时间
  //    看成片 totalMs —— 与 haloMs 同口径）。
  //  · glowStrength/glowRadius/glowThr = 实际下发的三个参数生效值（[95][96][97]）。
  kv += ",\"glowOn\":" + std::to_string(glow_on_);
  kv += ",\"glowMs\":" + jnum(glow_ms_, 7);
  kv += ",\"glowStrength\":" + jnum(p.glow_strength, 6);
  kv += ",\"glowRadius\":" + jnum(p.glow_radius, 6);
  kv += ",\"glowThr\":" + jnum(p.glow_threshold, 6);
  if (!c.mfnr_kv.empty()) kv += "," + c.mfnr_kv;
  // v0.10.59：新增算法的生效性字段 —— 必须在这里（finishStillChain 内、日志发出
  // 前一行），因为 P0 与色度多尺度两个 pass 都在本函数内执行完；此前误加在
  // submitRawBurst 的 c.mfnr_kv 与调用点之后，均读不到值（0.10.59 真机 p0s 缺失）。
  kv += ",\"p0s\":" + std::to_string(dbg_p0s_);
  kv += ",\"cms\":" + std::to_string(dbg_cms_);
  kv += ",\"cmsK\":" + jnum(dbg_cms_k_, 4);
  // p0w = 中心点权重和；接近 9 = 样本几乎全被值域权重拒绝，接近 97 = 全采纳
  kv += ",\"p0w\":" + jnum(dbg_p0w_, 6);
  // NN 降噪 pass（v0.10.102）：生效性读数 —— nnOk/nnFail/nnDel/nnMs/nnTiles。
  // 必须在这里（runNnPass 于本函数内执行完之后、日志发出前）读取。
  kv += ",\"nn\":" + std::to_string(c.nn ? 1 : 0);
  if (c.nn) {
    kv += ",\"nnOk\":" + std::to_string(dbg_nn_ok_);
    kv += ",\"nnFail\":" + std::to_string(dbg_nn_fail_);
    kv += ",\"nnDel\":\"" + dbg_nn_del_ + "\"";
    kv += ",\"nnMs\":" + jnum(dbg_nn_ms_, 6);
    kv += ",\"nnTiles\":" + std::to_string(dbg_nn_tiles_);
    kv += ",\"nnGain\":" + jnum(dbg_nn_gain_, 6);
  }
  kv += ",\"satmP\":" + jnum(dbg_satm_p_, 4);
  kv += ",\"satmRaw\":" + std::to_string(raw_cpu_w_ > 0 ? 1 : 0);
  kv += ",\"satmHit\":" + jnum(dbg_satm_hit_, 12);
  kv += ",\"satmCen\":" + jnum(dbg_satm_cen_, 8);
  kv += ",\"satmMax\":" + jnum(dbg_satm_max_, 8);
  kv += ",\"satmW\":" + jnum(dbg_satm_w_, 8);
  kv += ",\"satmBlk\":" + jnum(dbg_satm_blk_, 8);
  kv += ",\"satmCov\":" + jnum(dbg_satm_cov_, 8);
  kv += ",\"satmTx\":" + std::to_string(tex_satm_ != 0 ? 1 : 0);
  kv += ",\"satmPrg\":" + std::to_string(prog_satm_ != 0 ? 1 : 0);
  kv += ",\"gainmapOk\":" + std::to_string(dbg_gainmap_ok_);
  kv += ",\"gmMin\":" + jnum(dbg_gm_min_, 4) +
        ",\"gmMax\":" + jnum(dbg_gm_max_, 4) +
        ",\"gmMean\":" + jnum(dbg_gm_mean_, 4);
  kv += "}";
  Logger::instance().log(LogLevel::kInfo, "engine", "raw.render", kv);
  diagEndFrame();  // 诊断统计：逐阶段已即时上报，这里只清链标志
}

// ── MFNR 阶段一（v0.9.0）：纹理 / 金字塔 / 融合 / burst 端到端 ───────────

bool FrameRenderer::ensureMfnrTargets(int w, int h) {
  if (w <= 0 || h <= 0) return false;
  const int w1 = (w + 1) / 2, h1 = (h + 1) / 2;
  const int tw = (w1 + kMfnrTile - 1) / kMfnrTile;
  const int th = (h1 + kMfnrTile - 1) / kMfnrTile;
  if (mfnr_w_ == w && mfnr_h_ == h) return true;
  // burst 帧 1..3（R16UI，同 raw_tex_ 规格）
  for (auto& t : tex_mfnr_) {
    if (t) glDeleteTextures(1, &t);
    t = 0;
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_R16UI, w, h, 0, GL_RED_INTEGER,
                 GL_UNSIGNED_SHORT, nullptr);
  }
  // 对齐金字塔 L1..L4（RGBA8，sqrt 预编码灰度；L2..L4 是 readback 层）
  const auto mkPyr = [&](GLuint* tex, GLuint* fbo, int ww, int hh) {
    if (*tex) {
      glDeleteTextures(1, tex);
      glDeleteFramebuffers(1, fbo);
    }
    *tex = *fbo = 0;
    makeTex(tex, fbo, GL_RGBA8, ww, hh);
  };
  mkPyr(&tex_pyr1_, &fbo_pyr1_, w1, h1);
  mkPyr(&tex_pyr2_, &fbo_pyr2_, (w1 + 1) / 2, (h1 + 1) / 2);
  mkPyr(&tex_pyr3_, &fbo_pyr3_, ((w1 + 1) / 2 + 1) / 2, ((h1 + 1) / 2 + 1) / 2);
  mkPyr(&tex_pyr4_, &fbo_pyr4_, (((w1 + 1) / 2 + 1) / 2 + 1) / 2,
        (((h1 + 1) / 2 + 1) / 2 + 1) / 2);
  pyr1_w_ = w1;
  pyr1_h_ = h1;
  // 融合输出：R16F 线性 mosaic（raw 尺寸）。R16F 的 format 是 GL_RED，
  // makeTex 写死 GL_RGBA 不适用，手动建。
  if (tex_fused_) {
    glDeleteTextures(1, &tex_fused_);
    glDeleteFramebuffers(1, &fbo_fused_);
  }
  tex_fused_ = fbo_fused_ = 0;
  glGenTextures(1, &tex_fused_);
  glBindTexture(GL_TEXTURE_2D, tex_fused_);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_R16F, w, h, 0, GL_RED, GL_HALF_FLOAT,
               nullptr);
  glGenFramebuffers(1, &fbo_fused_);
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_fused_);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                         tex_fused_, 0);
  // 位移场纹理（RGBA16F，tiles 尺寸；CPU 侧 GL_FLOAT 上传由驱动转 half）
  // tex_off_ab_ = A/B 诊断的第二组（旧层对齐），同规格。
  const auto mkOff = [&](GLuint* arr, int n) {
    for (int i = 0; i < n; ++i) {
      GLuint& t = arr[i];
      if (t) glDeleteTextures(1, &t);
      t = 0;
      glGenTextures(1, &t);
      glBindTexture(GL_TEXTURE_2D, t);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
      glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, tw, th, 0, GL_RGBA,
                   GL_HALF_FLOAT, nullptr);
    }
  };
  mkOff(tex_off_, 3);
  mkOff(tex_off_ab_, 3);
  // mismatch map（RGBA8，4×tiles 网格 = 32px cell；m∈[0,1] 8bit 量化 0.4% 可忽略）
  const int cw = tw * 4, chh = th * 4;
  if (tex_mis_) {
    glDeleteTextures(1, &tex_mis_);
    glDeleteFramebuffers(1, &fbo_mis_);
  }
  // @domain: A8 —— MFNR 对齐的运动/不匹配图（RGBA8）
  makeTex(&tex_mis_, &fbo_mis_, GL_RGBA8, cw, chh);
  // 权重图（RGBA8 **LINEAR**——8bit 纹理硬件双线性可用，Fuse 采样即 32px 平滑过渡；
  // 与 tex_mis_ 同网格）
  if (tex_w_) glDeleteTextures(1, &tex_w_);
  tex_w_ = 0;
  glGenTextures(1, &tex_w_);
  glBindTexture(GL_TEXTURE_2D, tex_w_);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, cw, chh, 0, GL_RGBA,
               GL_UNSIGNED_BYTE, nullptr);
  mfnr_w_ = w;
  mfnr_h_ = h;
  tiles_w_ = tw;
  tiles_h_ = th;
  return glGetError() == GL_NO_ERROR;
}

void FrameRenderer::passMfnrPyr(GLuint raw_tex, int cfa,
                                const float black_phase[4], float white_level,
                                const float gains[3], float frame_scale) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_pyr1_);
  glViewport(0, 0, pyr1_w_, pyr1_h_);
  glUseProgram(prog_mfnr_pyr_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, raw_tex);
  glUniform1i(u_mfnr_pyr_.tex, 0);
  glUniform2i(u_mfnr_pyr_.size, raw_w_, raw_h_);
  glUniform1i(u_mfnr_pyr_.cfalut, cfaLutOf(cfa));
  glUniform4f(u_mfnr_pyr_.black, black_phase[0], black_phase[1], black_phase[2],
              black_phase[3]);
  glUniform1f(u_mfnr_pyr_.white, white_level);
  glUniform3f(u_mfnr_pyr_.gain, gains[0], gains[1], gains[2]);
  if (u_mfnr_pyr_.scale >= 0) glUniform1f(u_mfnr_pyr_.scale, frame_scale);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

void FrameRenderer::passMfnrDown(GLuint src, GLuint fbo, int sw, int sh) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo);
  glViewport(0, 0, (sw + 1) / 2, (sh + 1) / 2);
  glUseProgram(prog_mfnr_down_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, src);
  glUniform1i(u_mfnr_down_.tex, 0);
  glUniform2i(u_mfnr_down_.size, sw, sh);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

void FrameRenderer::passMfnrFuse(int w, int h, int cfa,
                                 const float black_phase[4], float white_level,
                                 const float gains[3],
                                 const float frame_scale[4]) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_fused_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_mfnr_fuse_);
  const GLenum units[8] = {GL_TEXTURE0, GL_TEXTURE1, GL_TEXTURE2, GL_TEXTURE3,
                           GL_TEXTURE4, GL_TEXTURE5, GL_TEXTURE6, GL_TEXTURE7};
  const GLuint raws[4] = {ref_fuse_tex_, tex_mfnr_[0], tex_mfnr_[1],
                          tex_mfnr_[2]};
  for (int i = 0; i < 4; ++i) {
    glActiveTexture(units[i]);
    glBindTexture(GL_TEXTURE_2D, raws[i]);
    glUniform1i(u_mfnr_fuse_.raw[i], i);
  }
  for (int i = 0; i < 3; ++i) {
    glActiveTexture(units[4 + i]);
    glBindTexture(GL_TEXTURE_2D, tex_off_[i]);
    glUniform1i(u_mfnr_fuse_.off[i], 4 + i);
  }
  glActiveTexture(GL_TEXTURE7);
  glBindTexture(GL_TEXTURE_2D, tex_w_);
  glUniform1i(u_mfnr_fuse_.w, 7);
  glActiveTexture(GL_TEXTURE0);
  glUniform2i(u_mfnr_fuse_.size, w, h);
  glUniform2f(u_mfnr_fuse_.tiles, static_cast<float>(tiles_w_),
              static_cast<float>(tiles_h_));
  glUniform1f(u_mfnr_fuse_.tile_m, static_cast<float>(kMfnrTile) * 2.0f);
  glUniform1i(u_mfnr_fuse_.cfalut, cfaLutOf(cfa));
  glUniform4f(u_mfnr_fuse_.black, black_phase[0], black_phase[1], black_phase[2],
              black_phase[3]);
  glUniform1f(u_mfnr_fuse_.white, white_level);
  glUniform3f(u_mfnr_fuse_.gain, gains[0], gains[1], gains[2]);
  if (u_mfnr_fuse_.frame_scale >= 0) {
    glUniform4f(u_mfnr_fuse_.frame_scale, frame_scale[0], frame_scale[1],
                frame_scale[2], frame_scale[3]);
  }
  if (u_mfnr_fuse_.sat_k >= 0) glUniform1f(u_mfnr_fuse_.sat_k, 0.98f);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// ── 降噪专项 A：DFT 逐频收缩合并（HDR+ §5 式 6/7）────────────────────────
// burst 4 帧汇集到 R16UI 2DArray（GPU-GPU copy）→ kFsMfnrFft compute 4 次
// dispatch（tile 相位组串行化 overlap-add）→ per-tile neff 读回均值。
// 输出写进 tex_fused_（与 fuse 路径同域同格式），后段 P0/RCD/胶片链零改动。
// ── 局部影调（tone equalizer）：mask 生成 + 查表应用 ─────────────────────
// 自动预设曲线：9 个分区 = −8…0 EV（相对 scene 白点 1.0）；中灰 −2.44 EV 落在
// 第 5.56 格（≈ +0.03 EV，锁定）；高光 −0.6 EV；最暗 +0.3 EV。
// 这是 darktable "compress shadows/highlights" 预设的口径。
// v0.11.87：改成按本张 AE 的缺口自适应（见 setTeqCurveFromAe）—— 固定 −0.6/+0.3
// 只能压 2/3 档，而真机高光场景的缺口是 1.5~3 EV（真机 4641：aeMid +1.59、aeEv -1.74）。
void FrameRenderer::setTeqCurveFromAe(float gap_ev, bool hl_dominant,
                                   float p95_rel_white) {
  // gap = 中灰想要 − 实际给到（>=0 表示"为保高光把中灰压暗了多少"）。
  const float t = std::clamp(gap_ev / 1.5f, 0.0f, 1.0f);
  // 高光：AE 已经把中灰压了 ⇒ 高光更需要局部压，给曲线留余量。
  // v0.11.89：**大面积高光**（天空/白墙 ≥30% 画面）压到 −2.2EV —— 真机 4254 的天空
  // 在 teq 前 4.0EV（~219 LSB），−1.1 只压到 2.9EV（~219？表值）仍是灰白；压到 1.8EV
  // 才落回曲线的线性段，蓝色才回得来。判据由 AE 给（P95 软约束是否比 P99.5 更严）。
  // ── v0.12.25：高光压深**只压超出白点的部分**，不再有"无条件压 0.6EV"那条 ──────
  // 旧式普通场景 = `-0.60 - 0.50*t`：**不管高光有没有超白点都压 0.6EV**。
  // 真机 172200/172152（室外有天空、base_ev=0.3）：
  //   成片 p50 107/104  vs rawpy(autoBright) 110/78  ⇒ 中灰是准的
  //   成片 p95 199/205  vs rawpy(autoBright) 243/212 ⇒ **高光被压了 0.9EV**
  //   而那两张的 RAW 高光根本没超白点（rawpy 给 243 而非 255） ⇒ 这 0.6EV 是白压的，
  //   表现为"有高光的场景整体偏暗"（用户原话）。中灰不动、只高光暗，正是这个签名。
  // 现规则：`hl = -max(0, p95_rel_white)` —— 高光在白点以内就**不压**，
  // 超出去多少压回来多少。172200 的 p95_rel_white≈0 ⇒ hl=0（高光保住，回到 243）；
  // 4609/161952（超白点 1.07EV）⇒ hl≈-1.07（压回白点，天空不顶死）。
  // ── 0.12.198：高光压深**归零**做 A/B（真机 10:36 叶子场景）──────────────
  // 该张读数：aeEv +0.847（AE 为把 P50 0.056 提到 0.12 推了近 1EV）、aeP95 1.1148
  // >白点、teqHl −1.0045 ⇒ 高光被"先推上去、再压回来"整整一档，观感就是用户说的
  // "像糊了一层东西"（而 normal 场景 teqHl = 0 时画面正常）。
  // 先关掉这一项做单变量验证：若高光层次回来而没顶白，说明压深的量与时机错了；
  // 若没变化，则糊在 filmic 的 shoulder，不在 teq。
  // 0.12.203：从"全关"改成"半强度的原式"。198 全关的代价在高动态场景暴露：
  // 144156 的 aeP95 = 2.52（超白点 2.5EV）而 teqHl = 0 ⇒ SDR base 直接死白，
  // gainmap 无可补。半强度让 −1.0EV 场只压 −0.5EV（叶子那种 "糊一层" 不再出现），
  // 同时超出白点 2.5EV 的窗外仍有 1.25EV 由这里吃掉，剩下的交给 filmic shoulder。
  // 0.12.204：回到**全关**（0 = 不压）。203 试过半强度，但它与 gainmap strength
  // 方向相反（一个压高光、一个提高光），叠加后观感变差；而且真机 144156 的
  // aeP95=2.52EV 即使全压也留不住层次 —— 那类场景的层次只能由 gainmap 端表达
  // （SDR base 顶白是 Ultra HDR 的预期状态，PC 同样如此）。
  const float hl = 0.0f;
  (void)hl_dominant;
  // ── v0.12.27：**去掉无条件的 +0.30 暗部提亮**（与高光那条同型）──────────────
  // 旧式 `0.30 + 0.55*t`：不管 AE 有没有让步，暗部先提亮 0.3EV。而 0.3EV 的线性
  // 提亮经 sigmoid 暗端（斜率大）放大后 ≈ 0.6EV 的观感，表现为"中间暗部亮太多、
  // 画面发灰、对比度被压"。真机 184146 与 PC 同机位对比：
  //   p5  44.6 vs 26.9（我们亮 65%≈0.7EV）  <40 的像素 4.5% vs 21.2%
  //   p50 124.8 vs 112.1（只差 0.15EV）      p95 214 vs 226（我们反而暗）
  // 即：整体亮度是对的，**暗部被单独抬起来了**，对比度因此塌掉。
  // 注释本来的设计意图写得很清楚 —— "AE 压掉多少中灰，就在暗部补回"，
  // 那是 gap_ev>0 时的补偿，不该在 gap=0 时也无条件发生。现在按意图来：
  const float sh = 0.55f * t;            // 0 … +0.55（只在 AE 让步时补）
  dbg_teq_hl_ = hl;
  dbg_teq_sh_ = sh;
  // 9 点：0..6 是暗部→中灰（线性过渡），7 = 高光过渡，8 = 白点。
  // 中灰点（5.56 格）保持 0：teq 不改中灰是硬约束（AE 的锚点不能漂）。
  static const float kBase[7] = {1.00f, 0.93f, 0.80f, 0.60f,
                                 0.40f, 0.20f, 0.07f};
  for (int i = 0; i < 7; ++i) teq_curve_[i] = sh * kBase[i];
  // ── v0.12.20：**压深只给第 6 档（q = −2 EV），白点档恒 0** ─────────────────
  // 9 档曲线覆盖 q ∈ [−8, 0] EV，第 i 档对应 q = i−8：
  //   第 6 档 → q = −2 EV：天空主体、白墙、大面积高光在这里 ⇒ 全部压深放这
  //   第 7 档 → q = −1 EV：天空上沿 / 窗框，压 30% 作过渡
  //   第 8 档 → q =  0 EV：**云、镜面反光、阳光过曝斑**在这里 ⇒ 恒 0，不压
  // 旧版把压深放在第 8 档（`teq_curve_[8] = hl`），于是"本来就该白"的东西全被压
  // 暗：161952 的白云被压 2.2 EV 成了乌云。而真正该压的天空在第 6 档只拿到
  // +0.06（提亮）⇒ 顶死成灰白。整整四轮 wTop 都在补这个错位。
  teq_curve_[6] += hl;        // hl 为负（−0.6 ~ −2.2），相加即压暗
  teq_curve_[7] = hl * 0.30f; // 过渡档只压 30%：云的 q≈−0.5 落在这里与第 8 档之间
  teq_curve_[8] = 0.0f;       // 白点档：云 / 反光点 / 过曝斑一律不压
}

bool FrameRenderer::ensureTeqTargets(int w, int h) {
  const int tw = (w + 3) / 4, th = (h + 3) / 4;
  if (teq_w_ == tw && teq_h_ == th && tex_teq_out_ != 0) return true;
  const auto del = [](GLuint* t, GLuint* f) {
    if (*t) glDeleteTextures(1, t);
    if (*f) glDeleteFramebuffers(1, f);
    *t = *f = 0;
  };
  del(&tex_teq_i_, &fbo_teq_i_);
  del(&tex_teq_mean_, &fbo_teq_mean_);
  del(&tex_teq_tmp_, &fbo_teq_tmp_);
  del(&tex_teq_gain_, &fbo_teq_gain_);
  del(&tex_teq_out_, &fbo_teq_out_);
  makeTex(&tex_teq_i_, &fbo_teq_i_, GL_RGBA16F, tw, th);
  makeTex(&tex_teq_mean_, &fbo_teq_mean_, GL_RGBA16F, tw, th);
  makeTex(&tex_teq_tmp_, &fbo_teq_tmp_, GL_RGBA16F, tw, th);
  makeTex(&tex_teq_gain_, &fbo_teq_gain_, GL_RGBA16F, tw, th);
  makeTex(&tex_teq_out_, &fbo_teq_out_, GL_RGBA16F, w, h);
  teq_w_ = tw;
  teq_h_ = th;
  const bool ok = tex_teq_i_ && tex_teq_mean_ && tex_teq_tmp_ &&
                  tex_teq_gain_ && tex_teq_out_;
  if (!ok) {
    Logger::instance().log(LogLevel::kError, "engine", "teq.alloc_failed",
                           "{\"w\":" + std::to_string(w) +
                               ",\"h\":" + std::to_string(h) + "}");
    return false;
  }
  return true;
}

GLuint FrameRenderer::applyToneEq(int w, int h, GLuint src, bool hl_dominant) {
  dbg_teq_ms_ = 0.0f;
  if (!toneEq() || src == 0 || w <= 0 || h <= 0)
    return src;
  const auto teq_t0 = std::chrono::steady_clock::now();
  if (!ensureTeqTargets(w, h)) return src;
  const int tw = teq_w_, th = teq_h_;
  // ── v0.12.12：mask 半径 /24 → /12（≈ 画面短边的 8%）─────────────────────
  // 原来的 4% 在 1/4 图上只有 8 px = 全分辨率 32 px。而真实场景里的"镜面反光块"
  // 通常是 60~150 px（真机绿袋反光约 100 px = 1/4 图的 25 px）⇒ 反光块**内部**
  // 的窗口完全被自己填满 ⇒ q ≡ I ⇒ mask 没有"分区"，teq 退化成一个逐像素的
  // 亮度曲线。这正是"局部影调"把反光压成脏灰、而天空该压的地方又压不准的原因。
  // 半径 ×2 后（1/4 图 16 px = 全分辨率 64 px），反光块的整体轮廓能进窗口 ⇒
  // q 才真的低于 I ⇒ v0.12.11 的 excess 判据才有区分度。
  // 代价：分区更"粗"，大面积高光的压缩边界会更平缓（这正是 guided filter 该有的行为）。
  const int radius = std::max(3, std::min(tw, th) / 12);
  // ① scene → I = log2(luma), I²（1/4 分辨率）
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_teq_i_);
  glViewport(0, 0, tw, th);
  glUseProgram(prog_teq_down_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, src);
  glUniform1i(u_teq_down_.tex, 0);
  glUniform2f(u_teq_down_.src_texel, 1.0f / static_cast<float>(w),
              1.0f / static_cast<float>(h));
  glDrawArrays(GL_TRIANGLES, 0, 3);
  // ② 可分离盒式（H+V）：mean(I)、mean(I²)
  const auto box = [&](GLuint in, GLuint out_fbo, int dir) {
    glBindFramebuffer(GL_FRAMEBUFFER, out_fbo);
    glViewport(0, 0, tw, th);
    glUseProgram(prog_teq_box_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, in);
    glUniform1i(u_teq_box_.tex, 0);
    glUniform2f(u_teq_box_.texel, 1.0f / static_cast<float>(tw),
                1.0f / static_cast<float>(th));
    glUniform1i(u_teq_box_.dir, dir);
    glUniform1i(u_teq_box_.radius, radius);
    glDrawArrays(GL_TRIANGLES, 0, 3);
  };
  box(tex_teq_i_, fbo_teq_mean_, 0);
  box(tex_teq_mean_, fbo_teq_tmp_, 1);
  // ③ guided filter 系数 a/b（自引导，log 域 ⇒ 曝光无关）
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_teq_mean_);
  glViewport(0, 0, tw, th);
  glUseProgram(prog_teq_coef_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, tex_teq_tmp_);
  glUniform1i(u_teq_coef_.mean, 0);
  // ── v0.12.12：uEps 0.05 → 0.35（**这一步才让 teq 真正"分区"**）──────────────
  // uEps 是 a = var/(var+eps) 里的方差阈值，单位 = (log2 EV)²。
  // 0.05 ⇒ std ≈ 0.22 EV —— 比场景里任何正常亮度过渡都小 ⇒ var >> eps ⇒ a ≈ 1
  // ⇒ q = a·I + b ≈ I：mask 退化成了**逐像素亮度曲线**，"分区"根本没发生。
  // 后果：teq 的高光压缩对"镜面反光点"和"天空"一视同仁地压 0.6 EV —— 真机把反光
  // 压成脏灰（185008：RAW 最亮通道 0.63 → 我们 233 / rawpy 250，差 20 LSB ≈ 0.6 EV）。
  // 0.35 ⇒ std ≈ 0.59 EV：比局部亮度起伏大、比跨区域亮度差小 ⇒ a 落在 0.3~0.7，
  // q 才真的是"局部均值 EV"。此时 v0.12.11 的 excess = I − q 判据才有区分度：
  //   反光点（局部均值被暗区拉低）⇒ excess > 1 EV ⇒ 不压
  //   天空（自身即局部均值）      ⇒ excess ≈ 0   ⇒ 照压
  // v0.12.11 加了这个判据却没生效，正是因为 eps 让 q ≡ I、excess 恒为 0。
  glUniform1f(u_teq_coef_.eps, 0.35f);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  // ④ 平滑 a/b（两个盒式）
  box(tex_teq_mean_, fbo_teq_tmp_, 0);
  box(tex_teq_tmp_, fbo_teq_mean_, 1);
  // ⑤（v0.11.95 删除）原来这里在 1/4 分辨率上算 gain，再由 apply 双线性上采样 ——
  // 上采样的是"结论"而不是"系数"，边缘在 1/4 就被抹平 ⇒ 高对比边缘出现灰带/光晕。
  // 现在直接进 ⑥：apply 上采样 a/b，用**全分辨率**的 I 算 q（guided upsample）。
  // ⑥ 全分辨率应用（guided upsample + 分区曲线）
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_teq_out_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_teq_apply_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, src);
  glUniform1i(u_teq_apply_.tex, 0);
  glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, tex_teq_mean_);
  glUniform1i(u_teq_apply_.gain, 1);
  glUniform2f(glGetUniformLocation(prog_teq_apply_, "uGainSize"),
              static_cast<float>(tw), static_cast<float>(th));
  glUniform1fv(glGetUniformLocation(prog_teq_apply_, "uCurve"), 9, teq_curve_);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  glActiveTexture(GL_TEXTURE0);
  glFinish();
  dbg_teq_ms_ = std::chrono::duration<float, std::milli>(
                    std::chrono::steady_clock::now() - teq_t0)
                    .count();
  return tex_teq_out_;
}

// 期 B bracket（0.11.72）：参考帧饱和修复目标（R16UI，raw 尺寸，可复用）
bool FrameRenderer::ensureSatFixTarget(int w, int h) {
  if (w <= 0 || h <= 0) return false;
  if (tex_ref_fix_ != 0 && sat_fix_w_ == w && sat_fix_h_ == h) return true;
  if (tex_ref_fix_) {
    glDeleteTextures(1, &tex_ref_fix_);
    glDeleteFramebuffers(1, &fbo_ref_fix_);
    tex_ref_fix_ = fbo_ref_fix_ = 0;
  }
  glGenTextures(1, &tex_ref_fix_);
  glBindTexture(GL_TEXTURE_2D, tex_ref_fix_);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RG16UI, w, h, 0, GL_RG_INTEGER,
               GL_UNSIGNED_SHORT, nullptr);
  glGenFramebuffers(1, &fbo_ref_fix_);
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_ref_fix_);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                         tex_ref_fix_, 0);
  const GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  if (st != GL_FRAMEBUFFER_COMPLETE) {
    Logger::instance().log(LogLevel::kError, "engine", "mfnr.satfix_fbo",
                           "{\"status\":" + std::to_string(st) + "}");
    return false;
  }
  sat_fix_w_ = w;
  sat_fix_h_ = h;
  return true;
}

// ── 0.12.133（方案 B 块 3）：短帧真值图 + 全饱和区 inpaint ────────────────────
// 与 ensureSatFixTarget 同构（惰性、尺寸缓存、失败返回 false 由调用方降级）。
bool FrameRenderer::ensureShortValTarget(int w, int h) {
  if (w <= 0 || h <= 0) return false;
  if (tex_short_val_ != 0 && sv_w_ == w && sv_h_ == h) return true;
  if (tex_short_val_) {
    glDeleteTextures(1, &tex_short_val_);
    glDeleteFramebuffers(1, &fbo_short_val_);
    tex_short_val_ = fbo_short_val_ = 0;
  }
  // @domain: D2 —— 短帧真值（WB 线性 mosaic，可 > 满阱）+ 有效标记（alpha）
  makeTex(&tex_short_val_, &fbo_short_val_, GL_RGBA16F, w, h);
  if (!tex_short_val_ || !fbo_short_val_) {
    sv_w_ = sv_h_ = 0;
    return false;
  }
  sv_w_ = w;
  sv_h_ = h;
  return true;
}

void FrameRenderer::passMfnrShortVal(GLuint short_tex, int w, int h, int cfa,
                                     const float black_phase[4],
                                     float white_level, const float gains[3],
                                     float short_scale, GLuint off_tex,
                                     int tiles_w, int tiles_h) {
  if (!prog_mfnr_sv_ || !ensureShortValTarget(w, h) || off_tex == 0) return;
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_short_val_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_mfnr_sv_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, short_tex);
  glUniform1i(u_mfnr_sv_.shrt, 0);
  glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, off_tex);
  glUniform1i(u_mfnr_sv_.off, 1);
  glActiveTexture(GL_TEXTURE0);
  glUniform2i(u_mfnr_sv_.size, w, h);
  glUniform2f(u_mfnr_sv_.tiles, static_cast<float>(tiles_w),
              static_cast<float>(tiles_h));
  glUniform1f(u_mfnr_sv_.tile_m, static_cast<float>(kMfnrTile) * 2.0f);
  glUniform1i(u_mfnr_sv_.cfalut, cfaLutOf(cfa));
  glUniform4f(u_mfnr_sv_.black, black_phase[0], black_phase[1],
              black_phase[2], black_phase[3]);
  glUniform1f(u_mfnr_sv_.white, white_level);
  glUniform3f(u_mfnr_sv_.gain, gains[0], gains[1], gains[2]);
  glUniform1f(u_mfnr_sv_.scale, short_scale);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  short_val_scale_ = short_scale;
  short_val_ok_ = 1;
}

bool FrameRenderer::ensureHlInpTarget(int w, int h) {
  if (w <= 0 || h <= 0) return false;
  if (tex_fused_inp_ != 0 && inp_w_ == w && inp_h_ == h) return true;
  if (tex_fused_inp_) {
    glDeleteTextures(1, &tex_fused_inp_);
    glDeleteFramebuffers(1, &fbo_fused_inp_);
    tex_fused_inp_ = fbo_fused_inp_ = 0;
  }
  // @domain: D2 —— 与 tex_fused_ 同域同格式（R16F 单通道，mosaic）。
  // 注意：makeTex 的外部格式写死 GL_RGBA，R16F 必须用 GL_RED（见 tex_fused_
  // 创建处的注释：格式不匹配会让 FBO 静默不完整）。所以这里手写。
  glGenTextures(1, &tex_fused_inp_);
  glBindTexture(GL_TEXTURE_2D, tex_fused_inp_);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_R16F, w, h, 0, GL_RED, GL_HALF_FLOAT,
               nullptr);
  glGenFramebuffers(1, &fbo_fused_inp_);
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_fused_inp_);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                         tex_fused_inp_, 0);
  const GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  if (st != GL_FRAMEBUFFER_COMPLETE) {
    Logger::instance().log(LogLevel::kError, "engine", "hl.inp_fbo",
                           "{\"status\":" + std::to_string(st) + "}");
    glDeleteTextures(1, &tex_fused_inp_);
    glDeleteFramebuffers(1, &fbo_fused_inp_);
    tex_fused_inp_ = fbo_fused_inp_ = 0;
    inp_w_ = inp_h_ = 0;
    return false;
  }
  inp_w_ = w;
  inp_h_ = h;
  return true;
}

bool FrameRenderer::passHlInpaint(int w, int h, int cfa, const float gains[3],
                                  float short_scale, GLuint fused_tex,
                                  GLuint short_val_tex) {
  if (!prog_hl_inp_ || fused_tex == 0 || short_val_tex == 0) return false;
  if (!ensureHlInpTarget(w, h)) return false;
  const auto t0 = std::chrono::steady_clock::now();
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_fused_inp_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_hl_inp_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, fused_tex);
  glUniform1i(u_hl_inp_.fused, 0);
  glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, short_val_tex);
  glUniform1i(u_hl_inp_.shrt, 1);
  glActiveTexture(GL_TEXTURE0);
  glUniform2i(u_hl_inp_.size, w, h);
  glUniform3f(u_hl_inp_.gain, gains[0], gains[1], gains[2]);
  glUniform1f(u_hl_inp_.shortk, short_scale);
  glUniform1i(u_hl_inp_.cfalut, cfaLutOf(cfa));
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glFinish();
  dbg_hl_inp_ms_ = std::chrono::duration<float, std::milli>(
                       std::chrono::steady_clock::now() - t0)
                       .count();
  dbg_hl_inp_ = 1;
  return true;
}

void FrameRenderer::passMfnrSatFix(GLuint ref_tex, GLuint short_tex, int w, int h,
                                   const float black_phase[4], float white_level,
                                   float short_scale, GLuint sat_map, int sat_w,
                                   int sat_h, GLuint off_tex, int tiles_w,
                                   int tiles_h) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_ref_fix_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_mfnr_satfix_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, ref_tex);
  glUniform1i(u_mfnr_satfix_.ref, 0);
  glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, short_tex);
  glUniform1i(u_mfnr_satfix_.shrt, 1);
  glActiveTexture(GL_TEXTURE2);
  glBindTexture(GL_TEXTURE_2D, sat_map);
  glUniform1i(u_mfnr_satfix_.satmap, 2);
  glActiveTexture(GL_TEXTURE3);
  glBindTexture(GL_TEXTURE_2D, off_tex);
  glUniform1i(u_mfnr_satfix_.off, 3);
  glActiveTexture(GL_TEXTURE0);
  glUniform2i(u_mfnr_satfix_.size, w, h);
  glUniform2i(u_mfnr_satfix_.satsize, sat_w, sat_h);
  glUniform2i(u_mfnr_satfix_.offtiles, tiles_w, tiles_h);
  glUniform1f(u_mfnr_satfix_.minerr, 0.02f);
  glUniform4f(u_mfnr_satfix_.black, black_phase[0], black_phase[1],
              black_phase[2], black_phase[3]);
  glUniform1f(u_mfnr_satfix_.white, white_level);
  glUniform1f(u_mfnr_satfix_.scale, short_scale);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

bool FrameRenderer::ensureMfnrFftTargets(int w, int h) {
  if (burst_w_ == w && burst_h_ == h && tex_fft_out_[3] != 0) return true;
  for (auto& t : tex_fft_out_) {
    if (t) glDeleteTextures(1, &t);
    t = 0;
  }
  // 输入直接用现有 raw_tex_/tex_mfnr_（usampler2D，与 kFsMfnrFuse 同款已验证
  // 采样路径），无独立 burst 纹理
  // 相位组输出 ×4（RGBA16F image2D writeonly = spike 真机验证过的写入
  // 语义；每张被本组 tile 全图覆盖免清零）
  for (auto& t : tex_fft_out_) {
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    // **必须 glTexStorage2D（不可变存储）**（v0.10.50 真机定案）：glTexImage2D
    // 创建的可变 RGBA16F 纹理在该 Mali 上 imageStore 写入全丢（主内核 sum 恒
    // 0、1-texel 微测试同款失败），而全部读回成功的 imageStore 路径
    //（SpikeFftMerge 的 tex_out、SpikePass 的 makeTex）都用 glTexStorage2D。
    // 0.10.37–49 的"init 创建的 compute program 写入全丢"是**误归因**：0.10.49
    // 现场创建后照样丢写（store_check micro:false + sum:0），真变量一直是
    // 纹理创建方式（spike 恰好同时具备两个差异，把相关当成了因果）。
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, w, h);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  }
  burst_w_ = w;
  burst_h_ = h;
  return tex_fft_out_[0] != 0 && tex_fft_out_[3] != 0;
}

bool FrameRenderer::passMfnrFft(int w, int h, int cfa,
                                const float black_phase[4], float white_level,
                                const float gains[3], const float plane_a[3],
                                const float plane_b[3],
                                const float frame_scale[4],
                                const float gain_ratio[4], float* out_neff,
                                float* out_ms) {
  *out_neff = 0.f;
  if (burst_w_ != w || burst_h_ != h) return false;
  if (prog_mfnr_fft_sum_ == 0) {
    Logger::instance().log(LogLevel::kError, "engine", "mfnr.fft.unavailable",
                           "{}");
    return false;
  }
  const int lut = cfaLutOf(cfa);
  // 平面 p 的通道 = (cfaLut >> (p<<1)) & 3（k = (oy<<1)|ox 恰等于 p），
  // σ 系数按平面展开（WB 后域、含强度 k²，单帧口径 —— 合并折减由 neff 完成）
  float pa[4] = {0, 0, 0, 0}, pb[4] = {0, 0, 0, 0};
  for (int p = 0; p < 4; ++p) {
    const int ch = (lut >> (p << 1)) & 3;
    pa[p] = plane_a[ch];
    pb[p] = plane_b[ch];
  }
  // ── compute program 现场创建、用完删除（v0.10.49 起；**事后判定为无关变量**）
  // 0.10.48 曾据"现场创建的 spike 成功、init 创建的微测试失败"推断 init 创建的
  // program 写入丢失，0.10.49 改为现场创建——0.10.49 真机 store_check
  //（micro:false + sum:0 + spikeOk:true）证伪该推断：现场创建的微测试同样失败。
  // 真变量是输出纹理的创建方式（glTexStorage2D，见 ensureMfnrFftTargets）。
  // 现场创建暂留（一次只动一个变量），验收通过后可回退到 init 创建省 CPU 编译。
  const GLuint cs_fft = compileShader(GL_COMPUTE_SHADER, kFsMfnrFft);
  GLuint prog_fft = 0;
  if (cs_fft) {
    prog_fft = glCreateProgram();
    glAttachShader(prog_fft, cs_fft);
    glLinkProgram(prog_fft);
    GLint linked = 0;
    glGetProgramiv(prog_fft, GL_LINK_STATUS, &linked);
    if (!linked) {
      char buf[512];
      glGetProgramInfoLog(prog_fft, sizeof(buf), nullptr, buf);
      Logger::instance().log(
          LogLevel::kError, "engine", "mfnr.fft.link_fail",
          "{\"log\":\"" + jsonEscape(buf) + "\"}");
      glDeleteProgram(prog_fft);
      prog_fft = 0;
    }
  }
  if (cs_fft) glDeleteShader(cs_fft);
  if (prog_fft == 0) {
    Logger::instance().log(LogLevel::kError, "engine", "mfnr.fft.compile_fail",
                           "{}");
    return false;
  }
  glUseProgram(prog_fft);
  // image units（uOut0..3 = 相位组输出；sampler units 见下）
  const char* outnames[4] = {"uOut0", "uOut1", "uOut2", "uOut3"};
  for (int i = 0; i < 4; ++i) {
    const GLint loc = glGetUniformLocation(prog_fft, outnames[i]);
    if (loc >= 0) glUniform1i(loc, i);
  }
  // 2) 4 次 dispatch（tile 相位组 (gx,gy)）：每次 xy = 组内 tile 网格的一半
  //    （每 workgroup 2 个水平相邻同组 tile）
  const auto t_dsp = std::chrono::steady_clock::now();
  GpuTimer gpu_fft;
  const bool gpu_ok = GpuTimer::supported();
  if (gpu_ok) gpu_fft.begin();
  const int pw = (w + 1) / 2, ph = (h + 1) / 2;
  const int tx_max = (pw + 7) / 8 - 1, ty_max = (ph + 7) / 8 - 1;
  // 输入直接绑现有纹理(raw_tex_ = 帧 0,与 passMfnrFuse 同源同款 usampler2D)
  const GLuint fft_raws[4] = {ref_fuse_tex_, tex_mfnr_[0], tex_mfnr_[1],
                              tex_mfnr_[2]};
  for (int i = 0; i < 4; ++i) {
    glActiveTexture(GL_TEXTURE0 + i);
    glBindTexture(GL_TEXTURE_2D, fft_raws[i]);
  }
  for (int i = 0; i < 3; ++i) {
    glActiveTexture(GL_TEXTURE4 + i);
    glBindTexture(GL_TEXTURE_2D, tex_off_[i]);
  }
  glActiveTexture(GL_TEXTURE0);
  const auto uni = [&](const char* n) {
    return glGetUniformLocation(prog_fft, n);
  };
  glUniform2i(uni("uSize"), w, h);
  glUniform2i(uni("uOffTiles"), tiles_w_, tiles_h_);
  glUniform1i(uni("uTxMax"), tx_max);
  glUniform4f(uni("uBlack"), black_phase[0], black_phase[1], black_phase[2],
              black_phase[3]);
  glUniform1f(uni("uWhite"), white_level);
  glUniform3f(uni("uGain"), gains[0], gains[1], gains[2]);
  // ⚠️ uCfaLut 必须下发（v0.10.52）：漏了则默认 0 → chanAt() 恒 0 →
  // gainAt() 四相位全取 uGain.x（R 增益）→ G/B 被乘上 R 增益、通道平衡丢失，
  // 成片全绿（真机 dftProbe 2×2 = [0.089,0.049,0.051,0.098] 与 DNG 同点
  // [0.0479,0.0260,0.0271,0.0573] 之比四相位同为 1.85 = gains[0] 实锤）。
  glUniform1i(uni("uCfaLut"), lut);
  glUniform4f(uni("uFrameScale"), frame_scale[0], frame_scale[1], frame_scale[2],
              frame_scale[3]);
  glUniform4f(uni("uGainRatio"), gain_ratio[0], gain_ratio[1], gain_ratio[2],
              gain_ratio[3]);
  glUniform4f(uni("uPlaneA"), pa[0], pa[1], pa[2], pa[3]);
  glUniform4f(uni("uPlaneB"), pb[0], pb[1], pb[2], pb[3]);
  glUniform1f(uni("uC"), 256.0f);
  {
    const GLint l0 = uni("uRaw0"), l1 = uni("uRaw1"), l2 = uni("uRaw2"),
                l3 = uni("uRaw3");
    if (l0 >= 0) glUniform1i(l0, 0);
    if (l1 >= 0) glUniform1i(l1, 1);
    if (l2 >= 0) glUniform1i(l2, 2);
    if (l3 >= 0) glUniform1i(l3, 3);
    const GLint o1 = uni("uOff1"), o2 = uni("uOff2"), o3 = uni("uOff3");
    if (o1 >= 0) glUniform1i(o1, 4);
    if (o2 >= 0) glUniform1i(o2, 5);
    if (o3 >= 0) glUniform1i(o3, 6);
  }
  // twiddle 表（spike 同款：2π·i/16 的 (cos, sin)，i = 0..7）
  float tw[16];
  for (int i = 0; i < 8; ++i) {
    const float ang = 6.283185307179586f * static_cast<float>(i) / 16.0f;
    tw[2 * i] = std::cos(ang);
    tw[2 * i + 1] = std::sin(ang);
  }
  glUniform2fv(uni("uTw"), 8, tw);
  for (int g = 0; g < 4; ++g) {
    glBindImageTexture(g, tex_fft_out_[g], 0, GL_FALSE, 0, GL_WRITE_ONLY,
                       GL_RGBA16F);
  }
  for (int g = 0; g < 4; ++g) {
    const int gx = g & 1, gy = g >> 1;
    // 组内 tile：tx ≡ 2gx−1 (mod 2)，x ∈ [-1, tx_max]；每 workgroup 2 tile
    int n = 0;
    for (int tx = -1; tx <= tx_max; ++tx) {
      if (((tx + 1) & 1) == gx) ++n;
    }
    int m = 0;
    for (int ty = -1; ty <= ty_max; ++ty) {
      if (((ty + 1) & 1) == gy) ++m;
    }
    glUniform2i(uni("uGroup"), gx, gy);
    glUniform1i(uni("uLayer"), gy * 2 + gx);
    glUniform1i(uni("uRows"), m);
    // plane 并入 y 维（z 恒 1 = spike 真机口径）：wg.y / uRows = plane
    glDispatchCompute((n + 1) / 2, m * 4, 1);
    // 层间无写竞争（各写各层），无需组间 barrier
  }
  // 末组写入要被**收口 pass 的纹理采样**与 **neff 的 framebuffer 读回**看到
  // —— TEXTURE_FETCH / FRAMEBUFFER barrier（ALL_BARRIER_BITS 一次覆盖；
  // v0.10.38 只下 SHADER_IMAGE_ACCESS 时收口读全 0 的教训保留）。
  glMemoryBarrier(GL_ALL_BARRIER_BITS);
  const float gpu_ms = gpu_ok ? gpu_fft.endMs() : -1.f;
  // ── store 自检（v0.10.44）：imageStore 曾在该 Mali 上"计算照跑、写入全丢"
  //（dftGpu 477-577ms vs 输出恒 0；0.10.37–49 连续多版全黑）——dispatch 后
  // 立即读每组输出中心 1px，全 0 判定写入丢失并回退 mismatch 路径（成片从机制
  // 上不黑）。读回组合 = RGBA16F + GL_FLOAT（X300 已验证）。
  // 根因（v0.10.50 定案，见 ensureMfnrFftTargets）：输出纹理必须 glTexStorage2D。
  {
    GLuint chk_fbo = 0;
    glGenFramebuffers(1, &chk_fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, chk_fbo);
    float chk_sum = 0.f;
    glFinish();  // 读回前同步（spike 同款）：避免把"未完成"误判成"写入丢失"
    for (int g = 0; g < 4; ++g) {
      glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                             GL_TEXTURE_2D, tex_fft_out_[g], 0);
      float pv[4] = {0, 0, 0, 0};
      glReadPixels(w / 2, h / 2, 1, 1, GL_RGBA, GL_FLOAT, pv);
      chk_sum += std::fabs(pv[0]);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &chk_fbo);
    while (glGetError() != GL_NO_ERROR) {
    }
    // 读回仍在（参与成片）：全 0 判定 imageStore 丢写 → 回退 mismatch 路径。
    Logger::instance().log(
        LogLevel::kInfo, "engine", "mfnr.fft.store_check",
        std::string("{\"sum\":") + jnum(chk_sum, 8) + "}");
    if (!(chk_sum > 0.f) || !std::isfinite(chk_sum)) {
      Logger::instance().log(
          LogLevel::kError, "engine", "mfnr.fft.store_lost",
          "{\"fuseMs\":" + jnum(gpu_ms, 8) + "}");
      glDeleteProgram(prog_fft);
      *out_neff = 0.f;
      return false;  // 调用方回退 mismatch 路径
    }
  }
  const auto t_rb = std::chrono::steady_clock::now();
  // 3b) overlap-add 收口：4 层相位组 → tex_fused_（窗和恒 1，直接相加）
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_fused_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_mfnr_fft_sum_);
  for (int g = 0; g < 4; ++g) {
    glActiveTexture(GL_TEXTURE0 + g);
    glBindTexture(GL_TEXTURE_2D, tex_fft_out_[g]);
  }
  glActiveTexture(GL_TEXTURE0);
  glUniform2i(u_mfnr_fft_sum_.size, w, h);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  while (glGetError() != GL_NO_ERROR) {
  }
  // neff 恒 4 = HDR+ 原版"假设 N 帧完美平均"（σ²/N）；NS §3.3 的按实际
  // 合并更新留待输出链真机验证通过后做（SSBO 方案）
  *out_neff = 4.0f;
  const auto t_end = std::chrono::steady_clock::now();
  const auto ms = [](auto a, auto b) {
    return std::chrono::duration<float, std::milli>(b - a).count();
  };
  out_ms[0] = 0.f;  // 上传段已随输入切换删除(输入直接用现有纹理)
  out_ms[1] = ms(t_dsp, t_rb);
  out_ms[2] = ms(t_rb, t_end);
  out_ms[3] = gpu_ms;
  glDeleteProgram(prog_fft);
  return true;
}

// mismatch pass（阶段二）：位移场 xy + 4 帧 → tex_mis_（RGBA8 tiles 网格）。
// 采样路径与 kFsMfnrFuse 逐字同构 —— d = ref(p) − alt_sub(p+off) 正是融合
// 权重为 1 时该帧注入的误差，mismatch 与融合行为严格一致。σ = 单帧线性域。
void FrameRenderer::passMfnrMismatch(int cfa, const float black_phase[4],
                                     float white_level, const float gains[3],
                                     const float a[3], const float b[3],
                                     float s_const, const float frame_scale[4],
                                     const float gain_ratio[4]) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_mis_);
  glViewport(0, 0, tiles_w_ * 4, tiles_h_ * 4);
  glUseProgram(prog_mfnr_mis_);
  const GLenum units[7] = {GL_TEXTURE0, GL_TEXTURE1, GL_TEXTURE2, GL_TEXTURE3,
                           GL_TEXTURE4, GL_TEXTURE5, GL_TEXTURE6};
  const GLuint raws[4] = {ref_fuse_tex_, tex_mfnr_[0], tex_mfnr_[1],
                          tex_mfnr_[2]};
  for (int i = 0; i < 4; ++i) {
    glActiveTexture(units[i]);
    glBindTexture(GL_TEXTURE_2D, raws[i]);
    glUniform1i(u_mfnr_mis_.raw[i], i);
  }
  for (int i = 0; i < 3; ++i) {
    glActiveTexture(units[4 + i]);
    glBindTexture(GL_TEXTURE_2D, tex_off_[i]);
    glUniform1i(u_mfnr_mis_.off[i], 4 + i);
  }
  glActiveTexture(GL_TEXTURE0);
  glUniform2i(u_mfnr_mis_.size, mfnr_w_, mfnr_h_);
  // uTiles = 4×tile 网格（32px cell）；uOffTiles = 位移场网格（tile 级）
  glUniform2f(u_mfnr_mis_.tiles, static_cast<float>(tiles_w_ * 4),
              static_cast<float>(tiles_h_ * 4));
  glUniform2f(u_mfnr_mis_.off_tiles, static_cast<float>(tiles_w_),
              static_cast<float>(tiles_h_));
  glUniform1f(u_mfnr_mis_.tile_m, static_cast<float>(kMfnrTile) / 2.0f);
  // C 专项：σ² 由常数改为逐像素模型（WB 后、k=1 的真实噪声；legacy 回退也非零，
  // 见 NoiseCoef::mma/mmb —— 模型无效时全零会让 m≡1、融合整体失效）
  glUniform3f(u_mfnr_mis_.noise_a, a[0], a[1], a[2]);
  glUniform3f(u_mfnr_mis_.noise_b, b[0], b[1], b[2]);
  glUniform1f(u_mfnr_mis_.s, s_const);
  glUniform1i(u_mfnr_mis_.cfalut, cfaLutOf(cfa));
  glUniform4f(u_mfnr_mis_.black, black_phase[0], black_phase[1], black_phase[2],
              black_phase[3]);
  glUniform1f(u_mfnr_mis_.white, white_level);
  glUniform3f(u_mfnr_mis_.gain, gains[0], gains[1], gains[2]);
  if (u_mfnr_mis_.frame_scale >= 0) {
    glUniform4f(u_mfnr_mis_.frame_scale, frame_scale[0], frame_scale[1],
                frame_scale[2], frame_scale[3]);
  }
  if (u_mfnr_mis_.gain_ratio >= 0) {
    glUniform4f(u_mfnr_mis_.gain_ratio, gain_ratio[0], gain_ratio[1],
                gain_ratio[2], gain_ratio[3]);
  }
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

void FrameRenderer::passRawDenoiseFused(int w, int h, int cfa,
                                        const float black_phase[4],
                                        float white_level, const float gains[3],
                                        const NoiseCoef& nc,
                                        const GLuint* off_tex, bool subpixel,
                                        GLuint fused_tex, bool allow_ts,
                                        bool wclamp) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_mos_);
  glViewport(0, 0, w, h);
  // D-2（v0.10.58）：时域 × 空间联合（4 帧各取 3×3 同相位邻域 = 36 独立样本）。
  // 缺任一输入（无位移场/无原始帧）则回落到旧的"融合后 9 样本"路径。
  // v0.10.102：NN 直读展开时强制 allow_ts=false —— Ts 的时域样本来自未降噪
  // 原始帧，与 NN 后的参考域不一致，且 P0 已被 NN 替代、再采时域无意义。
  const GLuint* offs = off_tex != nullptr ? off_tex : tex_off_;
  if (allow_ts && prog_raw_dn_ts_ && offs[2] != 0 && tex_mfnr_[2] != 0) {
    glUseProgram(prog_raw_dn_ts_);
    const GLuint raws[4] = {raw_tex_, tex_mfnr_[0], tex_mfnr_[1], tex_mfnr_[2]};
    for (int i = 0; i < 4; ++i) {
      glActiveTexture(GL_TEXTURE0 + i);
      glBindTexture(GL_TEXTURE_2D, raws[i]);
      glUniform1i(u_raw_dn_ts_.raw[i], i);
    }
    for (int i = 0; i < 3; ++i) {
      glActiveTexture(GL_TEXTURE4 + i);
      glBindTexture(GL_TEXTURE_2D, offs[i]);
      glUniform1i(u_raw_dn_ts_.off[i], 4 + i);
    }
    glActiveTexture(GL_TEXTURE8);
    glBindTexture(GL_TEXTURE_2D, tex_satm_);
    glUniform1i(u_raw_dn_ts_.satmask, 8);
    if (u_raw_dn_ts_.satmask_on >= 0)
      glUniform1i(u_raw_dn_ts_.satmask_on, satmask_enabled_ ? 1 : 0);
    glActiveTexture(GL_TEXTURE7);
    // v0.10.167：Ts 的参考域必须与 P0 的输入同域 —— 前置时输入是 HR 输出 tex_hl_
    // （单通道 .r，与 tex_fused_ 同读法，故 Ts shader 无需改动）。
    glBindTexture(GL_TEXTURE_2D, fused_tex != 0 ? fused_tex : tex_fused_);
    glUniform1i(u_raw_dn_ts_.fused, 7);
    glActiveTexture(GL_TEXTURE0);
    glUniform2i(u_raw_dn_ts_.size, w, h);
    glUniform2i(u_raw_dn_ts_.off_tiles, tiles_w_, tiles_h_);
    glUniform1i(u_raw_dn_ts_.cfalut, cfaLutOf(cfa));
    glUniform4f(u_raw_dn_ts_.black, black_phase[0], black_phase[1],
                black_phase[2], black_phase[3]);
    glUniform1f(u_raw_dn_ts_.white, white_level);
    glUniform3f(u_raw_dn_ts_.gain, gains[0], gains[1], gains[2]);
    glUniform3f(u_raw_dn_ts_.noise_a, nc.p0a[0], nc.p0a[1], nc.p0a[2]);
    glUniform1f(u_raw_dn_ts_.mix, nc.mix_p0);   // v0.10.108
    glUniform1f(u_raw_dn_ts_.wclamp, wclamp ? 1.f : 0.f);   // v0.10.167 值域保护
    dbg_hl_wclamp_ = wclamp ? 1.f : 0.f;
    glUniform3f(u_raw_dn_ts_.noise_b, nc.p0b[0], nc.p0b[1], nc.p0b[2]);
    glUniform1f(u_raw_dn_ts_.sr_scale, 2.2360679f);   // √(neff+1)，neff≡4
    glUniform1f(u_raw_dn_ts_.sub, subpixel ? 1.f : 0.f);
    dbg_p0s_ = 96;                                   // 4 帧 × 24（±4 mosaic 内同相位）
    glDrawArrays(GL_TRIANGLES, 0, 3);
    // 诊断读回：alpha = wsum。平均权重 ≈ wsum / 97（96 邻域 + 中心）
    {
      GLuint dfbo = 0;
      glGenFramebuffers(1, &dfbo);
      glBindFramebuffer(GL_FRAMEBUFFER, dfbo);
      glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                             tex_mos_, 0);
      float pv[4] = {0, 0, 0, 0};
      glReadPixels(w / 2, h / 2, 1, 1, GL_RGBA, GL_FLOAT, pv);
      dbg_p0w_ = pv[3];
      glBindFramebuffer(GL_FRAMEBUFFER, 0);
      glDeleteFramebuffers(1, &dfbo);
    }
    return;
  }
  glUseProgram(prog_raw_dn_fused_);
  glActiveTexture(GL_TEXTURE0);
  // v0.10.102：NN 直读展开时输入 = tex_nn_fused_（PMRID 输出，与 tex_fused_
  // 同域同格式 —— 线性 mosaic、已扣黑阶 + WB）
  glBindTexture(GL_TEXTURE_2D, fused_tex != 0 ? fused_tex : tex_fused_);
  glUniform1i(u_raw_dn_f_.tex, 0);
  // 0.12.187：饱和掩码（单元 1；关时 shader 走原路径，逐位不变）
  glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, tex_satm_);
  glUniform1i(u_raw_dn_f_.satmask, 1);
  if (u_raw_dn_f_.satmask_on >= 0)
    glUniform1i(u_raw_dn_f_.satmask_on, satmask_enabled_ ? 1 : 0);
  glActiveTexture(GL_TEXTURE0);
  glUniform2i(u_raw_dn_f_.size, w, h);
  glUniform1i(u_raw_dn_f_.cfalut, cfaLutOf(cfa));
  glUniform3f(u_raw_dn_f_.noise_a, nc.p0a[0], nc.p0a[1], nc.p0a[2]);
  glUniform1f(u_raw_dn_f_.mix, nc.mix_p0);   // v0.10.108：强度<1 的输出混合
  glUniform3f(u_raw_dn_f_.noise_b, nc.p0b[0], nc.p0b[1], nc.p0b[2]);
  // v0.10.167 值域保护：输入为 RAW 域 HR 的输出（scene-referred、可 >1）时，
  // 权重/σ 用钳位值、信号用原值（口径与理由见 kFsRawDenoiseFused 的 uWClamp 注释）。
  glUniform1f(u_raw_dn_f_.wclamp, wclamp ? 1.f : 0.f);
  dbg_hl_wclamp_ = wclamp ? 1.f : 0.f;   // 生效性自证：真机日志直接可查
  dbg_p0s_ = 9;                                      // 旧路径：融合后 9 空间样本
  dbg_p0w_ = -1.f;                                   // 旧路径无 wsum 诊断
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// ── NN 降噪 pass（v0.10.102）：PMRID TFLite 接拍照链 ──────────────────────

bool FrameRenderer::ensureNnTargets(int w, int h) {
  if (w <= 0 || h <= 0) return false;
  const int w1 = (w + 1) / 2, h1 = (h + 1) / 2;
  const bool ready = tex_nn_packed_ && fbo_nn_packed_ && tex_nn_out_ &&
                     tex_nn_fused_ && fbo_nn_fused_ && tex_nn_fdummy_ &&
                     tex_nn_rdummy_;
  if (nn_w_ == w && nn_h_ == h && ready) return true;
  if (nn_w_ != w || nn_h_ != h) {
    if (tex_nn_packed_) glDeleteTextures(1, &tex_nn_packed_);
    if (fbo_nn_packed_) glDeleteFramebuffers(1, &fbo_nn_packed_);
    if (tex_nn_out_) glDeleteTextures(1, &tex_nn_out_);
    if (tex_nn_fused_) glDeleteTextures(1, &tex_nn_fused_);
    if (fbo_nn_fused_) glDeleteFramebuffers(1, &fbo_nn_fused_);
    tex_nn_packed_ = fbo_nn_packed_ = tex_nn_out_ = 0;
    tex_nn_fused_ = fbo_nn_fused_ = 0;
  }
  if (!tex_nn_fdummy_ || !tex_nn_rdummy_) {
    // 类型匹配的两个 1×1 兜底（kFsNnPack 的 sampler 分支互斥仍须全绑定：
    // 整型/浮点 sampler 不可混绑，Mali 预测执行 + 无绑定 = 整帧 1282 丢弃）
    glGenTextures(1, &tex_nn_fdummy_);
    glBindTexture(GL_TEXTURE_2D, tex_nn_fdummy_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_R16F, 1, 1, 0, GL_RED, GL_HALF_FLOAT,
                 nullptr);
    glGenTextures(1, &tex_nn_rdummy_);
    glBindTexture(GL_TEXTURE_2D, tex_nn_rdummy_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_R16UI, 1, 1, 0, GL_RED_INTEGER,
                 GL_UNSIGNED_SHORT, nullptr);
  }
  // packed 中转（RGBA16F；与 Java 侧 readback/上传同一格式）
  // @domain: A6 —— PMRID NN 的 pack/unpack 域桥
  makeTex(&tex_nn_packed_, &fbo_nn_packed_, GL_RGBA16F, w1, h1);
  // 推理输出上传目标（只上传 + 采样，无需 fbo）
  glGenTextures(1, &tex_nn_out_);
  glBindTexture(GL_TEXTURE_2D, tex_nn_out_);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w1, h1, 0, GL_RGBA, GL_HALF_FLOAT,
               nullptr);
  // unpack 输出：R16F mosaic（照 tex_fused_ 手写 —— makeTex 写死 GL_RGBA）
  glGenTextures(1, &tex_nn_fused_);
  glBindTexture(GL_TEXTURE_2D, tex_nn_fused_);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_R16F, w, h, 0, GL_RED, GL_HALF_FLOAT,
               nullptr);
  glGenFramebuffers(1, &fbo_nn_fused_);
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_nn_fused_);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                         tex_nn_fused_, 0);
  // CPU 缓冲（随实例常驻；NN 关闭路径不进来）
  nn_packed_.resize(static_cast<size_t>(w1) * h1 * 4);
  nn_out_.resize(static_cast<size_t>(w1) * h1 * 4);
  nn_weight_.resize(static_cast<size_t>(w1) * h1);
  nn_tile_in_.resize(static_cast<size_t>(kNnTile) * kNnTile * 4);
  nn_tile_out_.resize(static_cast<size_t>(kNnTile) * kNnTile * 4);
  if (nn_ramp_.empty()) buildNnRamp();
  nn_w_ = w;
  nn_h_ = h;
  return true;
}

void FrameRenderer::buildNnRamp() {
  nn_ramp_.assign(static_cast<size_t>(kNnTile) * kNnTile, 1.0f);
  for (int i = 0; i < kNnOverlap; ++i) {
    const float v = static_cast<float>(i) / (kNnOverlap - 1);
    for (int j = 0; j < kNnTile; ++j) {
      nn_ramp_[static_cast<size_t>(i) * kNnTile + j] *= v;
      nn_ramp_[static_cast<size_t>(kNnTile - 1 - i) * kNnTile + j] *= v;
      nn_ramp_[static_cast<size_t>(j) * kNnTile + i] *= v;
      nn_ramp_[static_cast<size_t>(j) * kNnTile + (kNnTile - 1 - i)] *= v;
    }
  }
}

bool FrameRenderer::runNnPass(const ChainCtx& c, const NnSrc& src,
                              const float gains[3], int cfa,
                              const float black_phase[4], float white_level) {
  const auto t0 = std::chrono::steady_clock::now();
  const NnInferFn infer = nn_infer_slot();
  if (!infer) {
    Logger::instance().log(LogLevel::kError, "engine", "nn.fail",
                           "{\"why\":\"no_fn\"}");
    return false;
  }
  if (!ensureNnTargets(c.w, c.h)) {
    Logger::instance().log(LogLevel::kError, "engine", "nn.fail",
                           "{\"why\":\"targets\"}");
    return false;
  }
  const int w = c.w, h = c.h;
  const int w1 = (w + 1) / 2, h1 = (h + 1) / 2;
  constexpr int kStep = kNnTile - kNnOverlap;
  const int nx = (w1 + kStep - 1) / kStep;
  const int ny = (h1 + kStep - 1) / kStep;

  // 官方 KSigma 增益（v0.10.105 重定案）：inp = img01 × cvt_k × 256，
  // cvt_k = S_train / S_input。S_input = 单帧 S / neff —— 融合路径喂 tex_fused_，
  // 噪声方差已按 neff 折减（与 P0/P4 系数同口径，见 nc.p0a）；单帧路径 neff=1。
  float cvt_k = 1.0f;
  if (c.nm.valid() && c.nm.S[1] > 1e-8f) {
    cvt_k = std::clamp(kNnAnchorS * c.neff / c.nm.S[1], 0.25f, 16.0f);
  }
  const float nn_gain = cvt_k * kNnInpScale;
  dbg_nn_gain_ = nn_gain;
  // 语义序相位表（官方训练口径 ch0=R、ch1=Gr、ch2=Gb、ch3=B）
  int ph_sem[4][2], ph_chmap[4];
  nnSemTables(cfa, ph_sem, ph_chmap);

  // 1) kFsNnPack：mosaic → packed 4ch 半分辨率（除回 WB、×VST）
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_nn_packed_);
  glViewport(0, 0, w1, h1);
  glUseProgram(prog_nn_pack_);
  if (src.src == 0) {
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex_fused_);
    glUniform1i(u_nn_pack_.fused, 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, tex_nn_rdummy_);
    glUniform1i(u_nn_pack_.raw, 1);
  } else {
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex_nn_fdummy_);
    glUniform1i(u_nn_pack_.fused, 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, raw_tex_);
    glUniform1i(u_nn_pack_.raw, 1);
  }
  glActiveTexture(GL_TEXTURE0);
  glUniform2i(u_nn_pack_.size, w, h);
  glUniform1i(u_nn_pack_.cfalut, cfaLutOf(cfa));
  glUniform4f(u_nn_pack_.black, black_phase[0], black_phase[1], black_phase[2],
              black_phase[3]);
  glUniform1f(u_nn_pack_.white, white_level);
  glUniform3f(u_nn_pack_.gain, gains[0], gains[1], gains[2]);
  glUniform1i(u_nn_pack_.src, src.src);
  glUniform1f(u_nn_pack_.scale, nn_gain);
  glUniform4i(u_nn_pack_.phx, ph_sem[0][0], ph_sem[1][0], ph_sem[2][0],
              ph_sem[3][0]);
  glUniform4i(u_nn_pack_.phy, ph_sem[0][1], ph_sem[1][1], ph_sem[2][1],
              ph_sem[3][1]);
  glDrawArrays(GL_TRIANGLES, 0, 3);

  // 2) 读回 packed（RGBA16F + GL_FLOAT = X300 已验证组合，见 v0.6.48 教训）
  glReadPixels(0, 0, w1, h1, GL_RGBA, GL_FLOAT, nn_packed_.data());
  glBindFramebuffer(GL_FRAMEBUFFER, 0);

  // 3) tile 循环：组块（边缘补零）→ 推理 → ramp 融合。
  //    组块/融合口径与 tools/nn/quality_eval.py run_pmrid 逐项一致：
  //    步进 496、pad 补零、ramp = linspace(0,1,16) 四边、out/max(weight,1e-6)。
  nn_out_.assign(nn_out_.size(), 0.f);
  nn_weight_.assign(nn_weight_.size(), 0.f);
  for (int iy = 0; iy < ny; ++iy) {
    for (int ix = 0; ix < nx; ++ix) {
      const int x0 = ix * kStep, y0 = iy * kStep;
      float* tin = nn_tile_in_.data();
      for (int ty = 0; ty < kNnTile; ++ty) {
        const int sy = y0 + ty;
        float* dst = tin + static_cast<size_t>(ty) * kNnTile * 4;
        if (sy < h1) {
          const int wrow = std::min(kNnTile, w1 - x0);
          std::memcpy(dst, nn_packed_.data() + (static_cast<size_t>(sy) * w1 + x0) * 4,
                      static_cast<size_t>(wrow) * 4 * sizeof(float));
          if (wrow < kNnTile)
            std::memset(dst + static_cast<size_t>(wrow) * 4, 0,
                        static_cast<size_t>(kNnTile - wrow) * 4 * sizeof(float));
        } else {
          std::memset(dst, 0, static_cast<size_t>(kNnTile) * 4 * sizeof(float));
        }
      }
      if (!infer(tin, nn_tile_out_.data())) {
        // 任一 tile 失败 → 整张回退 P0 原链（照片优先红线）；留痕
        Logger::instance().log(
            LogLevel::kError, "engine", "nn.fail",
            std::string("{\"why\":\"infer\",\"tile\":") +
                std::to_string(iy * nx + ix) + "}");
        Logger::instance().flush();
        return false;
      }
      const float* tout = nn_tile_out_.data();
      for (int ty = 0; ty < kNnTile; ++ty) {
        const int sy = y0 + ty;
        if (sy >= h1) break;
        const int wrow = std::min(kNnTile, w1 - x0);
        for (int tx = 0; tx < wrow; ++tx) {
          const float rw = nn_ramp_[static_cast<size_t>(ty) * kNnTile + tx];
          const size_t oi = static_cast<size_t>(sy) * w1 + x0 + tx;
          float* o = &nn_out_[oi * 4];
          const float* t = tout + (static_cast<size_t>(ty) * kNnTile + tx) * 4;
          o[0] += t[0] * rw;
          o[1] += t[1] * rw;
          o[2] += t[2] * rw;
          o[3] += t[3] * rw;
          nn_weight_[oi] += rw;
        }
      }
    }
  }
  for (size_t i = 0; i < nn_weight_.size(); ++i) {
    const float wv = std::max(nn_weight_[i], 1e-6f);
    nn_out_[i * 4 + 0] /= wv;
    nn_out_[i * 4 + 1] /= wv;
    nn_out_[i * 4 + 2] /= wv;
    nn_out_[i * 4 + 3] /= wv;
  }

  // 4) 上传 + kFsNnUnpack → tex_nn_fused_（线性 mosaic + WB 域，与 tex_fused_ 同域）
  glBindTexture(GL_TEXTURE_2D, tex_nn_out_);
  glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w1, h1, GL_RGBA, GL_FLOAT,
                  nn_out_.data());
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_nn_fused_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_nn_unpack_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, tex_nn_out_);
  glUniform1i(u_nn_unpack_.packed, 0);
  glUniform2i(u_nn_unpack_.size, w, h);
  glUniform1i(u_nn_unpack_.cfalut, cfaLutOf(cfa));
  glUniform3f(u_nn_unpack_.gain, gains[0], gains[1], gains[2]);
  glUniform1f(u_nn_unpack_.iscale, 1.0f / nn_gain);
  glUniform4i(u_nn_unpack_.chmap, ph_chmap[0], ph_chmap[1], ph_chmap[2],
              ph_chmap[3]);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glBindFramebuffer(GL_FRAMEBUFFER, 0);

  dbg_nn_ok_ = 1;
  dbg_nn_fail_ = 0;
  dbg_nn_tiles_ = nx * ny;
  if (nn_info_slot()) {
    if (const char* d = nn_info_slot()()) dbg_nn_del_ = d;
  }
  dbg_nn_ms_ = std::chrono::duration<float, std::milli>(
                   std::chrono::steady_clock::now() - t0)
                   .count();
  return true;
}


// ── v0.10.15：darktable filmic 高光重建（HR）纹理与编排 ─────────────────────
// 算法与参数的全部出处见 shader 区注释（darktable filmicrgb.c + bspline.h）。
void FrameRenderer::releaseHrTargets() {
  const auto del = [](GLuint* t, GLuint* f) {
    if (*t) {
      glDeleteTextures(1, t);
      *t = 0;
    }
    if (*f) {
      glDeleteFramebuffers(1, f);
      *f = 0;
    }
  };
  del(&tex_hr_mask_, &fbo_hr_mask_);
  del(&tex_hr_lf_[0], &fbo_hr_lf_[0]);
  del(&tex_hr_lf_[1], &fbo_hr_lf_[1]);
  del(&tex_hr_hf_, &fbo_hr_hf_);
  del(&tex_hr_hfrgb_, &fbo_hr_hfrgb_);
  del(&tex_hr_temp_, &fbo_hr_temp_);
  del(&tex_hr_recon_[0], &fbo_hr_recon_[0]);
  del(&tex_hr_recon_[1], &fbo_hr_recon_[1]);
  del(&tex_hr_norms_, &fbo_hr_norms_);
  del(&tex_hr_stat_, &fbo_hr_stat_);
  hr_w_ = hr_h_ = 0;
}

bool FrameRenderer::ensureHrTargets(int w, int h) {
  if (w <= 0 || h <= 0) return false;
  const bool full = tex_hr_mask_ && tex_hr_lf_[0] && tex_hr_lf_[1] && tex_hr_hf_ &&
                    tex_hr_hfrgb_ && tex_hr_temp_ && tex_hr_recon_[0] &&
                    tex_hr_recon_[1] && tex_hr_norms_ && tex_hr_stat_;
  if (w == hr_w_ && h == hr_h_ && full) return true;
  releaseHrTargets();
  // RGBA16F 走 makeTex；R16F 手写（format 必须是 GL_RED，照 tex_fused_ 先例）
  const auto makeR16F = [](GLuint* tex, GLuint* fbo, int ww, int hh) {
    glGenTextures(1, tex);
    glBindTexture(GL_TEXTURE_2D, *tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_R16F, ww, hh, 0, GL_RED, GL_HALF_FLOAT,
                 nullptr);
    glGenFramebuffers(1, fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, *fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                           *tex, 0);
  };
  makeTex(&tex_hr_lf_[0], &fbo_hr_lf_[0], GL_RGBA16F, w, h);
  makeTex(&tex_hr_lf_[1], &fbo_hr_lf_[1], GL_RGBA16F, w, h);
  // @domain: D4 —— HR 小波：高频层
  makeTex(&tex_hr_hf_, &fbo_hr_hf_, GL_RGBA16F, w, h);
  // @domain: D4
  makeTex(&tex_hr_hfrgb_, &fbo_hr_hfrgb_, GL_RGBA16F, w, h);
  // @domain: D4
  makeTex(&tex_hr_temp_, &fbo_hr_temp_, GL_RGBA16F, w, h);
  makeTex(&tex_hr_recon_[0], &fbo_hr_recon_[0], GL_RGBA16F, w, h);
  makeTex(&tex_hr_recon_[1], &fbo_hr_recon_[1], GL_RGBA16F, w, h);
  makeR16F(&tex_hr_mask_, &fbo_hr_mask_, w, h);
  makeR16F(&tex_hr_norms_, &fbo_hr_norms_, w, h);
  // v0.10.17：stat 用 RGBA8（makeTex）——R16F attachment 配 GL_UNSIGNED_BYTE
  // readback 在 ES 3.0 不保证可用（X300 实测静默回零 → clipB 恒 0、HR 永不触发）。
  // mask ∈ [0,1] 的 8bit 量化对"块内 max > 1/17"判据足够。
  // @domain: A5 —— HR 统计域
  makeTex(&tex_hr_stat_, &fbo_hr_stat_, GL_RGBA8, 64, 48);
  // 任一 FBO 不完整 = 该 pass 被驱动整体丢弃（X300 上 RGBA8+HALF_FLOAT 事故同型）
  bool ok = true;
  const GLuint fbos[10] = {fbo_hr_lf_[0], fbo_hr_lf_[1], fbo_hr_hf_, fbo_hr_hfrgb_,
                           fbo_hr_temp_, fbo_hr_recon_[0], fbo_hr_recon_[1],
                           fbo_hr_mask_, fbo_hr_norms_, fbo_hr_stat_};
  for (GLuint f : fbos) {
    glBindFramebuffer(GL_FRAMEBUFFER, f);
    ok = ok && glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
  }
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  if (!ok) {
    Logger::instance().log(
        LogLevel::kError, "engine", "hr.fbo_incomplete",
        "{\"w\":" + std::to_string(w) + ",\"h\":" + std::to_string(h) + "}");
    releaseHrTargets();
    return false;
  }
  hr_w_ = w;
  hr_h_ = h;
  return true;
}

GLuint FrameRenderer::runHighlightReconstruct(GLuint src, int w, int h,
                                              float white_ev_input_domain,
                                              int* out_clipped_blocks,
                                              float* out_ms) {
  if (out_clipped_blocks) *out_clipped_blocks = -1;
  if (out_ms) *out_ms = 0.0f;
  if (!ensureHrTargets(w, h)) return src;
  const auto t0 = std::chrono::steady_clock::now();

  // 参数（darktable 默认换算，全部物理量、不可调）：
  //   feather = 2^(12/3) = 16；normalize = feather / threshold；noise_level 0.2；
  //   gamma/gamma_comp = 0.5/0.5（structure_vs_texture 0 的换算 (p/100+1)/2）；
  //   beta/beta_comp 1/0；delta 1；HQ 迭代 1。
  // ── threshold 语义（2026-10-03 逐行对照 filmicrgb.c:3120 后修正）──────────
  // 原版：threshold = 2^(white_source + reconstruct_threshold) × grey_source，
  // reconstruct_threshold 默认 0.0 ⇒ **threshold = 白点**（mask 在范数 = 白点处 w=0.5）。
  // 旧实现写死 1.0（"RAW 饱和数据白点"）在 WB 后域系统性偏低：R/B 增益 ≈1.8/1.7 把
  // 大量**未饱和**像素的欧氏范数抬过 1.0 ⇒ 白墙/电视屏这类亮部被注入 20% 相对幅度
  // 的彩色噪声再做小波外推，正是"反光区色块"。实测闭环：
  //   ① 真机日志 clipB 523~601 / 3072 块（17~20%）；
  //   ② hl-opposed-check 在同批 DNG 上量到真 RAW 饱和仅 0.0065%（且只有 G 通道）；
  //   ③ 离线 tools/hr-mask-check.py（双线性 + 1.41× LSC 标定）复现：threshold=1.0
  //      覆盖 8.7% 的块且高权重区落在白墙/电视屏；改用白点后降到 1%，而真饱和像素
  //      的 mask 权重仍 ≈0.94 —— **两端都对**（关掉 hr_wavelet 会丢的就是这 0.94）。
  // 输入域 = 曝光**前**（kFsFilm 内部才乘 2^ev），故白点取曝光前域值。
  // 下限 1.0 = 2^2.4383EV × kGrey，与 filmic 的 kWhiteSat 同源（0.10.17 事故：
  // 阈值随自动白点掉到 0.5 曾把 47% 的块送进重建）。
  // ── v0.10.145：threshold 的**域换算**（真机 8 份日志实锤的量纲错配）──────
  // 旧式 = max(2^white_ev × grey, 1.0)：这是 **RAW 域的场景白点**，而判据用的
  // pixMax 在 **WB+CCM 之后**的域。真机 whiteEv 5.494 → threshold 6.75，而 G 饱和
  // 像素的 pixMax 中位只有 2.355 ⇒ 阈值是实际值的 2.9 倍 ⇒ 命中率 0%，这条链
  // 在真机上**从未重建过任何像素**（设置页却写着"治过曝边缘品红残留"）。
  // 新式 = kSaturationNormCalib × |clip(CCM · (0, gain_G, 0))|：
  //   · 取 G 通道：Bayer 里 G 最先饱和（gain 恒 1、且它没有 WB 增益放大），
  //     CCM 第二列的范数也是三列里最小的 ⇒ 它是"任一通道满阱"的范数下界；
  //   · 该量只依赖 CCM 第二列（WB 已由 gain_G=1 抵消）⇒ 不随场景白点/色温漂移，
  //     这正是旧式失效的根源（白点是自动标定的，5.19EV 时把整条链废掉）；
  //   · kSaturationNormCalib = 1.13 的**语义 = 满阱范数的标定系数**（**不是白点**：
  //     它把"G 满阱经 CCM 投影后的范数"标定到真机实际判据分布上），由离线标定得出
  //     （台灯 155309 + 112020，取两片真实 WB/CCM）：G 饱和命中 100% / 99.8%，
  //     未裁亮部误触发 0.07% / 1.46%
  //     （验收线 >90% / <2%；0.10.120 那次"写死 1.0"事故的误触发是 8.7% 的块）。
  //     ⚠ **失效条件：换 CCM / 换镜头需重新标定** —— 该系数标定的是"此 CCM 第二列
  //     范数 ↔ 满阱实际分布"的比值，CCM 或镜头（LSC/光谱）任一改变即失效。
  // sigmoid 软权重与 feather 语义完全不变（只换 threshold 的来源）。
  constexpr float kGrey = 0.1845f;         // 仅保留给旧式对照/日志语义说明
  constexpr float kSaturationNormCalib = 1.13f;   // 满阱范数标定系数（非白点）
  float gvec[3] = {0.f, hr_gain_[1], 0.f};
  double acc2 = 0.0;
  for (int i = 0; i < 3; ++i) {
    double s = 0.0;
    for (int j = 0; j < 3; ++j) s += static_cast<double>(hr_ccm_[i * 3 + j]) * gvec[j];
    const double cl = s > 0.0 ? s : 0.0;
    acc2 += cl * cl;
  }
  // G 满阱通道经 CCM 投影后的范数（WB+CCM 域；命名沿用 thr_raw_sat：它是该域的
  // **满阱范数**，不是白点）
  const float thr_raw_sat = static_cast<float>(std::sqrt(acc2));
  // ── v0.10.168：threshold 的**输入域**换算（真机 130208 帧 + 离线复算实锤）──
  // 上面这个量是 **WB+CCM 域**的满阱范数下界，而本函数的输入是**场景线性域**
  //（D4：CCM 之后、kFsFilm 乘 2^exposure_ev 之前；两条 RAW 静拍链都给 D4 或其
  // 保 Y 下游）。该域中性白仍是 (1,1,1)：tex_mos_ 的中性白 ≡ 1.0（见
  // passHighlightRaw 的 kHlClipThr 域核实），CCM 行和恒 = 1（DNG ColorMatrix 行和
  // 实测 1.000，真机 D3→D4 亦保 Y）⇒ CCM·(1,1,1) = (1,1,1)。
  // 而 kFsHrMask 的判据是**欧氏范数** pixMax = ‖c‖ ⇒ 中性白范数 = √3 = 1.732，
  // **大于**旧阈值 1.511（= 1.13×1.337）⇒ 未裁剪的正常亮部被判为裁剪：
  // 中性白 w = 0.835。真机证据：HR-filmsrc（= 本函数输出、filmic 输入）的
  // Rec.709 加权和比 D4-scene 高 **+2.03%**（68903 → 70299）且三通道同向
  //（R +4.60% / G +1.29% / B +1.56%）= 改亮度的形态；该段其它算子（P4、
  // à trous、defringe、D-1）均严格保 Y ⇒ 唯一能动 Y 的就是本函数。
  // 乘 √3 ⇒ 阈值 2.618 = 该域的**白点范数**，回到 darktable "threshold = 白点、
  // mask 在白点处 w = 0.5" 的原义；等价口径 = 只有中性像素 Y > 2.617/√3 = 1.511
  //（比中性白亮 +0.60 EV）才 w > 0.5。真裁剪仍被覆盖：满阱像素
  // CCM·(gR,gG,gB) 的范数 ≈ 3.20（130208 帧 CCM/WB）⇒ w ≈ 0.92；[中性白, 满阱)
  // 这段未裁剪区则退出重建。真 RAW 裁剪另由前置 RAW 域 HR 兜底（不在此处）。
  constexpr float kSceneLinearNorm = 1.7320508f;   // √3 = 场景线性域中性白的欧氏范数
  const float threshold =
      std::max(kSaturationNormCalib * (thr_raw_sat > 1e-6f ? thr_raw_sat : 1.0f),
               1.0f) * kSceneLinearNorm;
  dbg_hr_thr_ = threshold;
  (void)kGrey;
  (void)white_ev_input_domain;   // 保留参数：白点仍写日志（hrWhiteEv）供对照
  const float feather = 16.0f;
  // ── v0.10.125：inpaint_noise 的 noise_level 置 0（真机"大片色斑"的根因）────
  // 原版在这步往裁剪区注入**逐通道独立**的噪声：sigma = pix × noise_level / threshold。
  // 在饱和处 pix ≈ threshold ⇒ sigma ≈ noise_level = 0.2，即**相对信号 20%**；而
  // darktable 的白点通常远高于数据最大值（本例 2^6.2EV×0.1845 ≈ 9.6 对比数据最大
  // ≈1.4，曝光前域），同等公式下其相对幅度只有约 7%。逐通道独立 ⇒ 彩色，且在逆光
  // 窗户这种大片饱和区（mask 权重 ≈1）等于把整片天空换成彩色噪声，小波重建只能部分
  // 抹平 ⇒ 真机放大的"大片色斑"。
  // noise_level 在原版是用户可调 0~6 的参数（默认 0.2），置 0 不是删机制而是取边界值：
  // 裁剪切噪的纹理改由邻域外推提供（LF/HF 来自未裁剪区），平坦高光因此更干净。
  const float noise_level = 0.0f;
  const float normalize = feather / threshold;
  // scales（darktable get_scales）：最粗级滤波覆盖最长边的 1/BSPLINE_FSIZE
  const double argument =
      (2.0 * std::max(w, h)) / ((5.0 - 1.0) * 5.0) - 1.0;
  const int scales = std::clamp(
      static_cast<int>(std::floor(std::log2(std::max(argument, 1.0)))), 1, 10);

  const auto bind2 = [](GLint loc0, GLuint tex0, GLint loc1, GLuint tex1) {
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex0);
    glUniform1i(loc0, 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, tex1);
    glUniform1i(loc1, 1);
  };

  // ① mask_clipped_pixels
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_hr_mask_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_hr_mask_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, src);
  glUniform1i(u_hr_mask_, 0);
  glUniform1f(u_hr_mask_norm_, normalize);
  glUniform1f(u_hr_mask_feather_, feather);
  if (u_hr_mask_markon_ >= 0) {
    const bool mark_on = hr_fix_mark_ != 0;
    dbg_hr_mark_on_ = mark_on ? 1 : 0;
    glUniform1i(u_hr_mask_markon_, mark_on ? 1 : 0);
    if (mark_on) {
      glActiveTexture(GL_TEXTURE1);
      glBindTexture(GL_TEXTURE_2D, hr_fix_mark_);
      glUniform1i(u_hr_mask_fixmark_, 1);
      glActiveTexture(GL_TEXTURE0);
    }
  }
  glDrawArrays(GL_TRIANGLES, 0, 3);
  // ①b 裁剪统计（64×48 块内 max；> 1/17 ⇔ 存在 argument < 4 的像素）
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_hr_stat_);
  glViewport(0, 0, 64, 48);
  glUseProgram(prog_hr_stat_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, tex_hr_mask_);
  glUniform1i(u_hr_stat_mask_, 0);
  glUniform2i(u_hr_stat_size_, w, h);
  glUniform2i(u_hr_stat_out_, 64, 48);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glFinish();
  GLubyte stat8[64 * 48 * 4] = {0};
  glReadPixels(0, 0, 64, 48, GL_RGBA, GL_UNSIGNED_BYTE, stat8);
  int blocks = 0;
  for (int i = 0; i < 64 * 48; ++i) {
    if (stat8[i * 4] >= 16) ++blocks;  // 16/255 ≈ 0.0628 > 1/17 = 0.0588
  }
  if (out_clipped_blocks) *out_clipped_blocks = blocks;
  if (blocks == 0) {  // darktable: clipped <= 9 跳过（块级保守判定）
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return src;
  }

  // ② inpaint_noise：noise_level = 0 时该 pass 恒等（o = i·(1−w) + w·i = i），
  // 直接跳过 —— 省一次全分辨率 pass，并把 src 本身当 s=0 的 detail 与重建初值。
  (void)noise_level;
  (void)prog_hr_inpaint_;
  (void)u_hr_inp_noise_;
  (void)u_hr_inp_thr_;

  // ③ init_reconstruct（recon[0] = max(src×(1−mask), 0)）
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_hr_recon_[0]);
  glViewport(0, 0, w, h);
  glUseProgram(prog_hr_rin_);
  bind2(u_hr_rin_tex_, src, u_hr_rin_mask_, tex_hr_mask_);
  glDrawArrays(GL_TRIANGLES, 0, 3);

  // ④ à trous 小波（两轮共用：RGB 轮 variant=0 / ratios 轮 variant=1）
  const auto wavelets = [&](GLuint detail0, int variant, int* recon_rd) {
    GLuint detail = detail0;
    for (int s = 0; s < scales; ++s) {
      const int lf_idx = (s + 1) % 2;
      const int mult = 1 << s;
      // blur(detail, mult) V → temp
      glBindFramebuffer(GL_FRAMEBUFFER, fbo_hr_temp_);
      glViewport(0, 0, w, h);
      glUseProgram(prog_hr_blur_);
      glActiveTexture(GL_TEXTURE0);
      glBindTexture(GL_TEXTURE_2D, detail);
      glUniform1i(u_hr_blur_tex_, 0);
      glUniform2i(u_hr_blur_size_, w, h);
      glUniform1i(u_hr_blur_mult_, mult);
      glUniform1i(u_hr_blur_dir_, 0);
      glDrawArrays(GL_TRIANGLES, 0, 3);
      // blur V 结果 H → lf[lf_idx]
      glBindFramebuffer(GL_FRAMEBUFFER, fbo_hr_lf_[lf_idx]);
      glActiveTexture(GL_TEXTURE0);
      glBindTexture(GL_TEXTURE_2D, tex_hr_temp_);
      glUniform1i(u_hr_blur_tex_, 0);
      glUniform1i(u_hr_blur_dir_, 1);
      glDrawArrays(GL_TRIANGLES, 0, 3);
      // HF = detail − lf[lf_idx]
      glBindFramebuffer(GL_FRAMEBUFFER, fbo_hr_hf_);
      glUseProgram(prog_hr_hf_);
      bind2(u_hr_hf_detail_, detail, u_hr_hf_lf_, tex_hr_lf_[lf_idx]);
      glDrawArrays(GL_TRIANGLES, 0, 3);
      // blur(HF, 1) V → temp，H → hfrgb
      glBindFramebuffer(GL_FRAMEBUFFER, fbo_hr_temp_);
      glUseProgram(prog_hr_blur_);
      glActiveTexture(GL_TEXTURE0);
      glBindTexture(GL_TEXTURE_2D, tex_hr_hf_);
      glUniform1i(u_hr_blur_tex_, 0);
      glUniform1i(u_hr_blur_mult_, 1);
      glUniform1i(u_hr_blur_dir_, 0);
      glDrawArrays(GL_TRIANGLES, 0, 3);
      glBindFramebuffer(GL_FRAMEBUFFER, fbo_hr_hfrgb_);
      glActiveTexture(GL_TEXTURE0);
      glBindTexture(GL_TEXTURE_2D, tex_hr_temp_);
      glUniform1i(u_hr_blur_tex_, 0);
      glUniform1i(u_hr_blur_dir_, 1);
      glDrawArrays(GL_TRIANGLES, 0, 3);
      // 重建累加：recon[1−rd] = recon[rd] + mask·(delta·details + residual)
      glBindFramebuffer(GL_FRAMEBUFFER, fbo_hr_recon_[1 - *recon_rd]);
      glUseProgram(prog_hr_step_);
      glActiveTexture(GL_TEXTURE0);
      glBindTexture(GL_TEXTURE_2D, tex_hr_hfrgb_);
      glUniform1i(u_hr_step_hfrgb_, 0);
      glActiveTexture(GL_TEXTURE1);
      glBindTexture(GL_TEXTURE_2D, tex_hr_lf_[lf_idx]);
      glUniform1i(u_hr_step_lf_, 1);
      glActiveTexture(GL_TEXTURE2);
      glBindTexture(GL_TEXTURE_2D, tex_hr_hf_);
      glUniform1i(u_hr_step_hf_, 2);
      glActiveTexture(GL_TEXTURE3);
      glBindTexture(GL_TEXTURE_2D, tex_hr_mask_);
      glUniform1i(u_hr_step_mask_, 3);
      glActiveTexture(GL_TEXTURE4);
      glBindTexture(GL_TEXTURE_2D, tex_hr_recon_[*recon_rd]);
      glUniform1i(u_hr_step_recon_, 4);
      glUniform1f(u_hr_step_gamma_, 0.5f);
      glUniform1f(u_hr_step_gammac_, 0.5f);
      glUniform1f(u_hr_step_beta_, 1.0f);
      glUniform1f(u_hr_step_betac_, 0.0f);
      glUniform1f(u_hr_step_delta_, 1.0f);
      glUniform1i(u_hr_step_variant_, variant);
      glUniform1i(u_hr_step_si_, s);
      glUniform1i(u_hr_step_sc_, scales);
      glDrawArrays(GL_TRIANGLES, 0, 3);
      *recon_rd = 1 - *recon_rd;
      detail = tex_hr_lf_[lf_idx];
    }
  };

  int recon_rd = 0;
  wavelets(src, 0, &recon_rd);

  // ⑤ HQ ratios 轮（darktable high_quality_reconstruction 默认 1 次）
  // norms = max(‖recon‖, NORM_MIN)（R16F）
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_hr_norms_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_hr_norms_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, tex_hr_recon_[recon_rd]);
  glUniform1i(u_hr_norms_tex_, 0);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  // ratios = recon / norms（写 lf[0]，两轮已空）
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_hr_lf_[0]);
  glUseProgram(prog_hr_ratios_);
  bind2(u_hr_ratios_tex_, tex_hr_recon_[recon_rd], u_hr_ratios_norms_,
        tex_hr_norms_);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  // init：recon[1−rd] = max(ratios×(1−mask), 0)
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_hr_recon_[1 - recon_rd]);
  glUseProgram(prog_hr_rin_);
  bind2(u_hr_rin_tex_, tex_hr_lf_[0], u_hr_rin_mask_, tex_hr_mask_);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  recon_rd = 1 - recon_rd;
  wavelets(tex_hr_lf_[0], 1, &recon_rd);
  // restore：final = clamp(ratios_out, 0, 1) × norms（输出到另一张 recon）
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_hr_recon_[1 - recon_rd]);
  glUseProgram(prog_hr_restore_);
  bind2(u_hr_restore_ratios_, tex_hr_recon_[recon_rd], u_hr_restore_norms_,
        tex_hr_norms_);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  recon_rd = 1 - recon_rd;
  glFinish();
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  if (out_ms) {
    *out_ms = std::chrono::duration<float, std::milli>(
                  std::chrono::steady_clock::now() - t0)
                  .count();
  }
  return tex_hr_recon_[recon_rd];
}

// RAW 多帧融合端到端：4 帧 u16 Bayer → 上传 → Bayer 域金字塔对齐（Align.cpp）
// → 置信度加权均值（kFsMfnrFuse）→ P0（fused 变体）→ 与 submitRaw 完全相同的
// RCD/胶片链 → RGBA8 读回。元数据统一取帧 0（burst 恒曝光）。
bool FrameRenderer::submitRawBurst(const void* const* frames, int count,
                                   const int* row_strides_px, int w, int h,
                                   int cfa, const float black_phase[4],
                                   int white_level, float neutral_r,
                                   float neutral_g, float neutral_b,
                                   const float* ccm9, const float* lsc,
                                   int lsc_cols, int lsc_rows, int rotation,
                                   const FilmParams& p, int demosaic,
                                   float noise_strength, const float* noise_model,
                                   float noise_sigma,
                                   float ref_noise_scale, bool use_dft,
                                   bool align_l1, bool align_ab, bool p0_sub,
                                   bool nn_dn, float short_ratio,
                                   float short_sat, const float* hdr_ev,
                                   const float* hdr_gain, int hdr_high_idx_arg,
                                   std::vector<uint8_t>* out_rgba,
                                   bool ten_bit) {
  if (w <= 0 || h <= 0 || !frames || !black_phase || !row_strides_px)
    return false;
  // 卡 H2：同 submitStill / submitRaw —— 自检未通过就拒绝 10bit 交付（fail-closed）
  if (ten_bit && !selfCheck10BitOnce()) return false;
  // 融合 shader 固定 4 输入（uRaw0..3 / uOff1..3）：帧数不符是调用方 bug
  if (count != 4) {
    Logger::instance().log(LogLevel::kError, "engine", "mfnr.bad_count",
                           "{\"count\":" + std::to_string(count) + "}");
    return false;
  }
  frames_.fetch_add(1, std::memory_order_relaxed);
  const auto t0 = std::chrono::steady_clock::now();
  // 阶段日志（v0.9.1）：fwrite 有 FILE 缓冲，进程崩溃时缓冲丢失——每个阶段
  // 写完立即 flush，崩溃后日志的最后一条 stage 就是断点。
  const auto stage = [&](const char* s) {
    Logger::instance().log(LogLevel::kInfo, "engine", "mfnr.stage",
                           std::string("{\"s\":\"") + s + "\"}");
    Logger::instance().flush();
  };
  stage("upload");

  // ── HDR 融合 P2（0.12.77）：帧角色解析 ──────────────────────────────
  // hdr_ev[0..3] = 各帧相对帧 0 的实测 EV（采集端由 CaptureResult 算好下发）。
  // ev > 0.3 = 高帧（+2.2EV，Mertens 侧帧）；ev < −0.3 = 低帧（P3 才消费）；
  // 其余 = zero 帧。P2 恰好消费 1 张高帧；找不到 ⇒ hdr 关闭（= P1/0.12.76 行为）。
  // 失败永不丢照片（红线）：侧帧链任何一步失败都只是 hdr_active 置 false
  // + 日志留痕，成片回退 zero 链。
  // ── P3 架构对齐（0.12.82，对齐 PC RAW：MultiFrameStacker.processBurstRaw +
  // RawmaxExposurePlanner + GlesMgcRawSpatialStacker.processFrames）──────────
  // 侧帧**不再单帧直通**（0.12.77-81 的 YUV bracket 架构在 RAW 路径质量不成立：
  // 单帧 ISO 3200 读噪声 10×zero 且无时域，contrast 权重选噪声 = 高光斑块，
  // 真机 201852/204721 两轮实锤）。改为 PC RAW 同构：**4 帧（含跨曝光侧帧）
  // 全部进既有的对齐 + DFT/mismatch 融合**，每帧按实测 EV 归一
  // frame_scale[i] = 2^(−ev[i])（旧 bracket 短帧归一的通用化 —— 机制已真机
  // 验证：satW 饱和权重让长帧裁切像素退出、短帧归一后接管高光，暗部长帧
  // 以更高 SNR 参与平均）。侧帧因此获得与 zero 同等的对齐/降噪待遇。
  bool hdr_active = hdr_ev != nullptr;
  // ── 0.12.131：**回滚 0.12.130 的路径切换** ─────────────────────────────────
  // 0.12.130 让 HDR 走 kFsMfnrFuse（satW 整像素加权），真机日志确认路径已切
  //（`fuse:"mm"`, neff 2.76~3.01），但样片出现**比 129 更明显的水平分界线**。
  // 原因在 satW 自己的兜底分支：
  //   · 短帧未饱和 ⇒ 权重全给短帧 ⇒ v = 短帧归一值（可达 2.8×满阱）
  //   · 短帧也饱和 ⇒ 四帧权重全 0 ⇒ `v = r0`（参考帧饱和值 = 1.0×满阱）
  // 两者相差**整整一档**，而分界线的位置正是"短帧的饱和边界" —— 和 0.12.102~128
  // 的 sat-fix 是同一个坑，只是这次藏在融合的兜底分支里。
  // 要让 A 成立，全饱和区必须做 **clipped inpaint**（PC 的 `oInpaintingMask`
  // 那一支：从周围未裁剪像素扩散），否则任何"有无短帧真值"的二分都会留下接缝。
  // 那是 B 的一半工作量，不属于本次一行切换能解决的范围。
  // ⇒ 回到 129 已验证的"单路径"状态（无分界线），把 inpaint 留到 B 里一起做。
  // ── 0.12.130（已回滚）：HDR 多曝光强制走**空间域加权融合**（kFsMfnrFuse 的 satW）────
  // HDR+ §5 的式 6/7 频域收缩是为**等曝光 burst** 设计的：它假设各帧信号相同、
  // 只有噪声独立。多曝光下这个假设不成立 —— 长帧在饱和处的值与短帧归一后的值
  // 相差整档，频域收缩只能判成"运动/不一致"并退回参考帧（a→1），于是短帧在
  // 高光处**完全没有贡献**；而事后用 sat-fix 逐 photosite 替换又必然带来色比
  // 撕裂（0.12.102~128 的全部黄绿/暖斑/分界线都源于此）。
  // 空间域融合的 satW 是"整像素加权"：饱和帧的权重连续降为 0，未饱和的短帧
  // 自然接管 —— 这正是 PC 的 `w × (1 − mask)` 语义，也是 HDR+ 对多曝光的做法。
  // 代价：暗部少了频域收缩的降噪增益（mismatch + 加权平均仍在）。用户已拍板
  // 质量优先且高光优先，故选空间域。
  // if (hdr_active) use_dft = false;   // 0.12.131 回滚（理由见上）
  // frame_scale 派生（放在上传前，供 capturePyr 逐帧归一）：
  float hdr_frame_scale[4] = {1.f, 1.f, 1.f, 1.f};
  if (hdr_active) {
    for (int i = 0; i < count && i < 4; ++i) {
      const float ev = std::isfinite(hdr_ev[i]) ? hdr_ev[i] : 0.f;
      hdr_frame_scale[i] = std::exp2(std::min(std::max(-ev, -8.0f), 8.0f));
    }
  }

  // 上传：帧 0 → raw_tex_（参考帧），帧 1..3 → tex_mfnr_（hdr 时 4 帧原样，
  // 含跨曝光侧帧 —— 归一在各 pass 内做）
  if (ensureRawTex(w, h) == 0) return false;
  if (!uploadRawTex(raw_tex_, frames[0], w, h, row_strides_px[0])) return false;
  if (p.sat_mask > 0.5f) {   // 0.12.186：参考帧 CPU 副本（bracket 路径）
    const auto* rp = static_cast<const uint16_t*>(frames[0]);
    raw_cpu_.assign(static_cast<size_t>(w) * h, 0);
    for (int y = 0; y < h; ++y)
      std::memcpy(&raw_cpu_[static_cast<size_t>(y) * w],
                  rp + static_cast<size_t>(y) * row_strides_px[0],
                  sizeof(uint16_t) * w);
    raw_cpu_w_ = w;
    raw_cpu_h_ = h;
  }
  if (!ensureMfnrTargets(w, h)) return false;
  for (int i = 1; i < count; ++i) {
    if (!uploadRawTex(tex_mfnr_[i - 1], frames[i], w, h, row_strides_px[i]))
      return false;
  }
  // U-3：[1..3] × [88][89]（与单帧链同一口径 —— 两条成片链必须一致，见 wbTempTint 注释）
  float wb_tt[3];
  wbTempTint(p, wb_tt);
  const RawCommon rc =
      prepareRawCommon(neutral_r, neutral_g, neutral_b, wb_tt[0], wb_tt[1],
                       wb_tt[2], ccm9, lsc, lsc_cols, lsc_rows);
  const float wl = static_cast<float>(white_level);

  // ── HDR 融合 P2（0.12.78）：侧帧数据只在此登记（ChainCtx），前链挪到
  // finishStillChain 里 zero 的 passCcm 之后跑 —— passRawDenoise/RCD 读的是
  // raw_tex_，侧帧必须重传它（0.12.77 的独立 Bayer 纹理方案让侧帧链跑在
  // zero 数据上：真机 D4-hdr ≈ D4-scene，Mertens 变成 (zero,zero)→zero）。

  // 期 B bracket（0.11.66）：帧 0 是短曝光帧时先归一到长帧域（长/短），再对齐/融合。
  // **必须归一**：不归一 SAD 残差被 4× 亮度差淹没（confMed 掉到 0，2026-09-29 的
  // alt 帧不守恒事故同型）。归一位于扣黑阶之后（各 pass 内），不是原始 u16 上乘。
  // 短帧在**最后一帧**（帧 3）：长帧必须作参考帧（DFT 的 r0 / mismatch 的恒权基准），
  // 短帧作参考会让暗部被其 4× 噪声主导（2026-10-20 真机实测，见 startBurst 注释）。
  float frame_scale[4] = {1.f, 1.f, 1.f, 1.f};
  const float short_ratio_ok =
      std::isfinite(short_ratio) ? std::min(std::max(short_ratio, 1.0f), 8.0f) : 1.0f;
  // P3（0.12.82）：hdr 模式 frame_scale 从实测 EV 派生（2^−ev，帧 0 参考恒 1）；
  // 旧 bracket（short_ratio）路径照旧。两者互斥（采集端 hdr 时 brk=0）。
  if (hdr_active) {
    for (int i = 0; i < 4; ++i) frame_scale[i] = hdr_frame_scale[i];
  } else {
    frame_scale[3] = short_ratio_ok;
  }
  // per-frame 增益比（0.12.83）：hdr 传数组，旧路径全 1（B 项不变 = 零回归）
  float hdr_gain_4[4] = {1.f, 1.f, 1.f, 1.f};
  if (hdr_active && hdr_gain != nullptr) {
    for (int i = 0; i < 4; ++i) {
      const float g = hdr_gain[i];
      hdr_gain_4[i] = std::isfinite(g) && g > 0.f ? std::min(std::max(g, 0.25f), 64.f) : 1.f;
    }
  }
  // ── 0.12.102：HDR 四帧同样有短帧真值（帧 3 = −1.5EV，frame_scale[3]=2.82），
  // sat-fix 必须跑。旧式只看 short_ratio（旧 bracket 专用）⇒ HDR 下 bracket_on=0，
  // 参考帧饱和区不修复，长帧的饱和值原样进 HL/filmic（真机 110647/110703 灰块）。
  // ── 0.12.119：sat-fix 重新启用，注入前加**位移可信度门** ──────────────────
  // 真机定性（2026-10-28）：0.12.118 只关掉短帧注入 ⇒ 黄绿斑**完全消失**
  //   （117 有、118 无，其余全部相同）⇒ 斑由注入引入，与 hl_rb_recon 无关
  //   （用户关掉它后斑仍在）。
  // 机制：注入的位移来自 DFT 的 **tile 级**场，高对比边界上 1~2px 误差会把邻区
  //   的低 B 搬进来（B 偏低 + G 不变 ⇒ 黄绿）。HL 的 opposed 只抬不压，全链里
  //   只有注入会压低通道值。
  // 修法（shader 侧 kFsMfnrSatFix）：注入前用 4 个同相位邻居做局部一致性检查，
  //   平均相对差 > 6% 满阱（或周围全饱和、无独立证据）⇒ 不注入、也不标"有真值"，
  //   交给 HL 的参考比重建（max 语义不会压值）。
  const bool satfix_on = short_ratio_ok > 1.01f || hdr_active;
  // 短帧索引：旧 bracket 恒在帧 3；HDR 下取实测 EV 最低的帧（参考帧除外）——
  // 采集端当前顺序是 0/0/+2.2/−1.5，但不能假设顺序（侧帧角色由 planner 定）。
  int short_idx = 3;
  if (hdr_active) {
    float ev_min = 1e9f;
    for (int i = 1; i < count; ++i) {
      const float ev = std::isfinite(hdr_ev[i]) ? hdr_ev[i] : 0.f;
      if (ev < ev_min) {
        ev_min = ev;
        short_idx = i;
      }
    }
  }
  // 期 B bracket：**保留 HR**（2026-10-21 修正）。
  // 0.11.75 曾为消除"灰块"关掉 HR，结果饱和区失去色度重建 ⇒ RCD 在窗玻璃这类
  // 大面积饱和处产生**粉/紫色斑块**（真机 20261021 逆光场景）。两害相权：
  // HR 的 inpaint 灰块（大面积、低频）比 RCD 的彩色斑块（高频、扎眼）可接受，
  // 且灰块可以靠"饱和标记 + 条件重建"在下一轮消掉（HR 只重建短帧也没救到的像素）。
  FilmParams p_burst = p;
  stage("pyr");

  // 金字塔 + readback + CPU 对齐。1/2 域尺寸 w1×h1，tile kMfnrTile → tiles。
  const int w2 = (pyr1_w_ + 1) / 2, h2 = (pyr1_h_ + 1) / 2;
  const int w3 = (w2 + 1) / 2, h3 = (h2 + 1) / 2;
  const int w4 = (w3 + 1) / 2, h4 = (h3 + 1) / 2;
  // D-2 对齐精度专项（2026-09-30）：最细精化层移到 1/2 域（HDR+ §4 原版的
  // 2×2 Bayer 平均灰度最细层），因此需要 readback 1/2 域（w1*h1 ≈ 3MB/帧）。
  // readback 与 CPU SAD 分开计时（alignRbMs / alignCpuMs）—— 成本必须实测后
  // 交用户拍板，不能替用户决定（项目铁律）。
  float align_rb_ms = 0.f, align_cpu_ms = 0.f;
  auto capturePyr = [&](GLuint raw_tex, MfnrPyramid* py, float scale) {
    passMfnrPyr(raw_tex, cfa, black_phase, wl, rc.gains, scale);
    passMfnrDown(tex_pyr1_, fbo_pyr2_, pyr1_w_, pyr1_h_);
    passMfnrDown(tex_pyr2_, fbo_pyr3_, w2, h2);
    passMfnrDown(tex_pyr3_, fbo_pyr4_, w3, h3);
    const auto readL = [&](GLuint fbo, int ww, int hh,
                           std::vector<uint8_t>* dst) {
      glBindFramebuffer(GL_FRAMEBUFFER, fbo);
      std::vector<uint8_t> rgba(static_cast<size_t>(ww) * hh * 4);
      glReadPixels(0, 0, ww, hh, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
      dst->resize(static_cast<size_t>(ww) * hh);
      for (size_t k = 0; k < dst->size(); ++k) (*dst)[k] = rgba[k * 4];
    };
    const auto rb0 = std::chrono::steady_clock::now();
    // align_l1 = false（prefs align_l1，A/B 开关）时不读 1/2 域：l1 为空 →
    // mfnrAlign 自动走旧路径（use_l1=false），日志 alignL1 记 0。
    if (align_l1) readL(fbo_pyr1_, pyr1_w_, pyr1_h_, &py->l1);
    readL(fbo_pyr2_, w2, h2, &py->l2);
    readL(fbo_pyr3_, w3, h3, &py->l3);
    readL(fbo_pyr4_, w4, h4, &py->l4);
    align_rb_ms += std::chrono::duration<float, std::milli>(
                       std::chrono::steady_clock::now() - rb0)
                       .count();
    py->w1 = align_l1 ? pyr1_w_ : 0;
    py->h1 = align_l1 ? pyr1_h_ : 0;
    py->w2 = w2;
    py->h2 = h2;
    py->w3 = w3;
    py->h3 = h3;
    py->w4 = w4;
    py->h4 = h4;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
  };
  MfnrPyramid ref;
  capturePyr(raw_tex_, &ref, frame_scale[0]);
  // 期 B bracket：参考帧饱和修复（对齐用原始 ref，融合用修复后的 ref）
  ref_fuse_tex_ = raw_tex_;
  hr_fix_mark_ = 0;
  dbg_hr_mark_on_ = 0;   // 诊断字段同样不能跨张残留（HR 不跑时日志会撒谎）
  stage("align");
  float align_ms = 0.f, gx_sum = 0.f, gy_sum = 0.f, conf_sum = 0.f;
  float dx_med_max = 0.f, dx_p90_max = 0.f;
  int align_l1_frames = 0;  // 实际走 1/2 域最细层的帧数（应为 count-1 = 3）
  // σ（单帧，线性域 WB 后）：v0.10.1 短参考帧下参考帧噪声比典型帧高
  // ref_noise_scale（= √(AE_sec/secRef)，1.0 = 未压短）。
  // C 专项（2026-09-29）：σ² 由常数改为模型 var(x)=A·x+B —— P0/P4 用融合域
  // （融合后折减 ÷neff，见下）；mismatch 的噪声底 = var(ref)+var(alt)，shader
  // 内按各帧实测信号代入（不再需要 rn 归一）。rn 仅保留在日志。
  const float rn = std::max(ref_noise_scale, 1.0f);
  NoiseModel model;
  if (noise_model != nullptr) {
    bool ok = true;
    for (int i = 0; i < 3; ++i) {
      model.S[i] = noise_model[i];
      model.O[i] = noise_model[3 + i];
      if (!std::isfinite(model.S[i]) || !std::isfinite(model.O[i]) ||
          model.S[i] < 0.f || model.O[i] < 0.f)
        ok = false;
    }
    if (!ok) model = NoiseModel{};
  }
  NoiseCoef nc_single;  // 单帧系数（mismatch 用）
  denoiseParams(noise_strength, model, rc.gains, noise_sigma, &nc_single);
  const float ns_single = nc_single.ns_typ;
  // v0.10.5：保存每帧位移场（位移幅度运动检测用；v0.10.3 曾因权重独立成图而删）
  const int n_tiles = tiles_w_ * tiles_h_;
  std::vector<std::vector<float>> offs(
      static_cast<size_t>(count - 1),
      std::vector<float>(static_cast<size_t>(n_tiles) * 2, 0.f));
  for (int i = 1; i < count; ++i) {
    MfnrPyramid cur;
    capturePyr(tex_mfnr_[i - 1], &cur, frame_scale[i]);
    const auto cpu0 = std::chrono::steady_clock::now();
    const MfnrAlignOut a = mfnrAlign(ref, cur, tiles_w_, tiles_h_);
    // 同帧 A/B 诊断：再算一遍**旧路径**（最细层 1/4 域）的位移场，上传到第二
    // 组纹理。两遍用的是同一对金字塔，唯一差异 = 最细对齐层。
    MfnrAlignOut a_ab;
    if (align_ab) a_ab = mfnrAlign(ref, cur, tiles_w_, tiles_h_, false);
    align_cpu_ms += std::chrono::duration<float, std::milli>(
                        std::chrono::steady_clock::now() - cpu0)
                        .count();
    stage("align_frame");
    for (int t = 0; t < n_tiles; ++t) {
      offs[static_cast<size_t>(i - 1)][static_cast<size_t>(t) * 2 + 0] =
          a.off[static_cast<size_t>(t) * 2 + 0];
      offs[static_cast<size_t>(i - 1)][static_cast<size_t>(t) * 2 + 1] =
          a.off[static_cast<size_t>(t) * 2 + 1];
    }
    // 打包位移场（xy=mosaic 域位移、z=权重恒 1）。v0.9.2 定案：阶段一 =
    // 对齐 + **等权**时域均值（立项字面范围）。v0.9.1 曾用"残差/纹理度"算
    // 置信度加权，真机 A/B 确诊失效（confMed 0.005、fbTiles 9095/9216）——
    // 暗部的 SAD 残差与梯度都被噪声主导，比值系统性落进"不可信"区，而暗部
    // 恰是 MFNR 的主战场。阶段二（v0.10.0）的 mismatch 权重与该比值不同构：
    // m = d²/(d²+s·σ²) 的分母是**噪声方差**而非"纹理度"——噪声越大容忍度
    // 越高（残差被噪声解释 = 不惩罚），只有显著超出噪声底的残差（运动/鬼影）
    // 才压权重，因此不会被暗部噪声系统性误杀。
    std::vector<float> pack(static_cast<size_t>(tiles_w_) * tiles_h_ * 4, 1.f);
    for (int t = 0; t < tiles_w_ * tiles_h_; ++t) {
      pack[static_cast<size_t>(t) * 4 + 0] =
          a.off[static_cast<size_t>(t) * 2 + 0];
      pack[static_cast<size_t>(t) * 4 + 1] =
          a.off[static_cast<size_t>(t) * 2 + 1];
      pack[static_cast<size_t>(t) * 4 + 2] = 1.0f;
    }
    glBindTexture(GL_TEXTURE_2D, tex_off_[i - 1]);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, tiles_w_, tiles_h_, GL_RGBA,
                    GL_FLOAT, pack.data());
    if (align_ab) {
      for (int t = 0; t < tiles_w_ * tiles_h_; ++t) {
        pack[static_cast<size_t>(t) * 4 + 0] =
            a_ab.off[static_cast<size_t>(t) * 2 + 0];
        pack[static_cast<size_t>(t) * 4 + 1] =
            a_ab.off[static_cast<size_t>(t) * 2 + 1];
      }
      glBindTexture(GL_TEXTURE_2D, tex_off_ab_[i - 1]);
      glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, tiles_w_, tiles_h_, GL_RGBA,
                      GL_FLOAT, pack.data());
    }
    gx_sum += a.gx;
    gy_sum += a.gy;
    conf_sum += a.conf_med;
    if (a.used_l1) ++align_l1_frames;
    dx_med_max = std::max(dx_med_max, a.dx_med);
    dx_p90_max = std::max(dx_p90_max, a.dx_p90);
  }
  align_ms = std::chrono::duration<float, std::milli>(
                 std::chrono::steady_clock::now() - t0)
                 .count();

  // ── 0.12.102：参考帧饱和修复搬到 align **之后** ─────────────────────────
  // 旧位置在 align 之前（直接同坐标取短帧）⇒ HDR 四帧手持有位移时注入错位值。
  // 现在位移场已知，短帧按亚像素对齐采样（shader 侧 linSub 同式）。
  // 0.12.127：注入判据只剩"短帧自己饱没饱和"，不再需要 1/2 灰度上的 satFrac
  // 面积统计 ⇒ 这里的 passMfnrPyr 重算一并删除（tex_pyr1_ 由后面的 capturePyr
  // 重建，DFT 也不读它）。
  // 0.12.133（方案 B 块 3）：每张先作废上一张的短帧真值（无 bracket 时必须回退）。
  short_val_ok_ = 0;
  short_val_scale_ = 0.f;
  if (satfix_on) {
    if (!ensureSatFixTarget(w, h)) return false;
    // 0.12.122：先用 collar flow 修正短帧位移场（highlight 区从周围可靠 tile 插值）
    // 0.12.161：support 由参考帧 RAW 的裁剪判据给出（0.12.122 的版本是 no-op，
    // 因为位移场 w 通道恒 1 —— 见 kFsCollarSupport 注释）
    GLuint off_use = tex_off_[short_idx - 1];
    if (passCollarFlow(off_use, raw_tex_, w, h, wl, tiles_w_, tiles_h_))
      off_use = tex_off_fix_;
    passMfnrSatFix(raw_tex_, tex_mfnr_[short_idx - 1], w, h, black_phase, wl,
                   frame_scale[short_idx], tex_pyr1_, pyr1_w_, pyr1_h_,
                   off_use, tiles_w_, tiles_h_);
    ref_fuse_tex_ = tex_ref_fix_;
    // HR 条件重建：标记 ≥1 的像素（有真值）跳过 HR，只有 0（无真值）才重建
    hr_fix_mark_ = tex_ref_fix_;
    // 0.12.133：短帧真值图（与 sat-fix **同一套位移场与归一**，只是不注入、不判决，
    // 留给 finishStillChain 的 inpaint 用）。失败 ⇒ short_val_ok_=0 ⇒ 退回 132 语义。
    // 0.12.135：inpaint 默认关 ⇒ 这张 100MB 的纹理也不再生成（成本归零）。
    if (kHlInpaintOn) {
      passMfnrShortVal(tex_mfnr_[short_idx - 1], w, h, cfa, black_phase, wl,
                       rc.gains, frame_scale[short_idx], off_use, tiles_w_,
                       tiles_h_);
    }
  }

  // ── 合并路径二选一（降噪专项 A，2026-09-29 立项）──────────────────────
  // use_dft = true：DFT 逐频收缩合并（HDR+ §5 式 6/7，passMfnrFft）——逐频
  // 收缩自身鲁棒（错位/运动帧在高频被 Az 拒绝），按 HDR+ 原版语义**不叠**
  // mismatch/conf 权重，mismatch 链整体跳过；neff 由内核 per-tile 频率平均
  // 读回（NS §3.3"按实际合并更新 σ"）。
  // use_dft = false：原阶段二 robust merge（mismatch map + 分段权重 + fuse）。
  float fuse_ms = 0.f, neff = 0.f, m_med = 0.f, m_p90 = 0.f, mis_ms = 0.f,
        mot_med = 0.f, res_fade = 1.f, neff_dft = 0.f,
        dft_ms[4] = {0, 0, 0, 0};
  std::string mis_diag, dil_diag, mot_diag, msm_diag;
  bool dft_active = false;
  // upload_ms 语义 = t0 → 融合开始（两路径统一：upload+align+合并前段）
  std::chrono::steady_clock::time_point tf;
  if (use_dft) {
    stage("dft");
    tf = std::chrono::steady_clock::now();
    if (!ensureMfnrFftTargets(w, h)) return false;
    dft_active = passMfnrFft(w, h, cfa, black_phase, wl, rc.gains, nc_single.p0a,
                             nc_single.p0b, frame_scale, hdr_gain_4,
                             &neff_dft, dft_ms);
    if (dft_active) {
      glFinish();
      stage("chain");
      fuse_ms = std::chrono::duration<float, std::milli>(
                    std::chrono::steady_clock::now() - tf)
                    .count();
      neff = std::min(std::max(neff_dft, 1.0f), 4.0f);
    }
  }
  if (dft_active) {
    // DFT 路径到此（mismatch 链跳过）
  } else {
  // ── 阶段二 robust merge：mismatch map（4×tiles / 32px cell）→ 权重图 ──
  stage("mismatch");
  const auto tmis = std::chrono::steady_clock::now();
  passMfnrMismatch(cfa, black_phase, wl, rc.gains, nc_single.mma, nc_single.mmb,
                   kMfnrMisS, frame_scale, hdr_gain_4);
  // v0.10.3：网格 = 4×tiles（32px cell）。权重独立成图（tex_w_，LINEAR），
  // 与位移场解耦 —— 128px tile 级权重对叶缘带局部运动分辨率不足（重影残留）。
  const int cw = tiles_w_ * 4, chh = tiles_h_ * 4;
  const int n_cells = cw * chh;
  std::vector<uint8_t> mis(static_cast<size_t>(n_cells) * 4);
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_mis_);
  glReadPixels(0, 0, cw, chh, GL_RGBA, GL_UNSIGNED_BYTE, mis.data());
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  // m 场 (2kMisDilate+1)² 膨胀（per-frame）：运动区域（手/叶片）是**连通区域**，
  // 残差只在其边缘 cell 高 —— 膨胀让物体整体回退参考帧。v0.10.5 半径 1→2
  // （5×5 = 覆盖 64px 带）。
  std::vector<uint8_t> dil(static_cast<size_t>(n_cells) * 4);
  for (int y = 0; y < chh; ++y) {
    for (int x = 0; x < cw; ++x) {
      const int t = y * cw + x;
      for (int z = 0; z < count - 1; ++z) {
        uint8_t mx = 0;
        for (int dy = -kMisDilate; dy <= kMisDilate; ++dy) {
          const int yy = y + dy;
          if (yy < 0 || yy >= chh) continue;
          for (int dx = -kMisDilate; dx <= kMisDilate; ++dx) {
            const int xx = x + dx;
            if (xx < 0 || xx >= cw) continue;
            const uint8_t v = mis[(static_cast<size_t>(yy) * cw + xx) * 4 + z];
            if (v > mx) mx = v;
          }
        }
        dil[static_cast<size_t>(t) * 4 + z] = mx;
      }
    }
  }
  // 位移幅度运动检测（v0.10.5）：mag 超出全图中位（相机抖动基线）+kMotLo 起步、
  // +kMotHi 满分。与残差无关 —— 补上"平滑皮肤运动"的残差盲区（手掌叠影）。
  std::vector<std::vector<float>> mot;
  {
    std::vector<float> mags_all;
    mags_all.reserve(static_cast<size_t>(n_tiles) * (count - 1));
    for (int z = 0; z < count - 1; ++z) {
      for (int t = 0; t < n_tiles; ++t) {
        const float dx = offs[static_cast<size_t>(z)][static_cast<size_t>(t) * 2];
        const float dy = offs[static_cast<size_t>(z)][static_cast<size_t>(t) * 2 + 1];
        mags_all.push_back(std::sqrt(dx * dx + dy * dy));
      }
    }
    // v0.10.7：per-tile 判定（每 tile 独立投票，不稀释——v0.10.6 的逐 cell 双线性
    // 插值把大位移在空间上抹平，运动边缘 mag 降到阈值下漏检 → 融合错位 alt（采到
    // 手臂外背景）→ 手臂"整块缺肉"）；基线改 **P25**（v0.10.5/6 用中位，被大面积
    // 运动物体自己抬高——motMed 14.9 时阈值失准）。
    std::sort(mags_all.begin(), mags_all.end());
    const float mag_p25 = mags_all.empty()
                              ? 0.f
                              : mags_all[mags_all.size() / 4];
    mot.assign(static_cast<size_t>(count - 1),
               std::vector<float>(static_cast<size_t>(n_tiles), 0.f));
    float msbuf[9];
    for (int z = 0; z < count - 1; ++z) {
      const std::vector<float>& off = offs[static_cast<size_t>(z)];
      for (int t = 0; t < n_tiles; ++t) {
        const int tx = t % tiles_w_, ty = t / tiles_w_;
        const float dx = off[static_cast<size_t>(t) * 2];
        const float dy = off[static_cast<size_t>(t) * 2 + 1];
        // 3×3 邻域位移中值（x/y 独立），边界 clamp
        auto med3 = [&](int comp) {
          int n = 0;
          for (int j = -1; j <= 1; ++j) {
            for (int i = -1; i <= 1; ++i) {
              const int xx = std::min(std::max(tx + i, 0), tiles_w_ - 1);
              const int yy = std::min(std::max(ty + j, 0), tiles_h_ - 1);
              msbuf[n++] =
                  off[(static_cast<size_t>(yy) * tiles_w_ + xx) * 2 + comp];
            }
          }
          for (int a = 1; a < n; ++a) {
            const float key = msbuf[a];
            int b = a - 1;
            while (b >= 0 && msbuf[b] > key) {
              msbuf[b + 1] = msbuf[b];
              --b;
            }
            msbuf[b + 1] = key;
          }
          return msbuf[n / 2];
        };
        const float mdx = med3(0), mdy = med3(1);
        const float dev = std::sqrt((dx - mdx) * (dx - mdx) +
                                    (dy - mdy) * (dy - mdy));
        float mval = 0.f;
        if (dev <= kOffDev) {
          const float excess = std::sqrt(dx * dx + dy * dy) - mag_p25;
          mval = std::min(std::max((excess - kMotLo) / (kMotHi - kMotLo), 0.f), 1.f);
        }
        mot[static_cast<size_t>(z)][static_cast<size_t>(t)] = mval;
      }
    }
    mot_med = mag_p25;
  }
  // v0.10.7 三步：
  // ① cell m = max(残差膨胀, m_motion 的 **3×3 tile 邻域 max 传播**) —— max 传播
  //    不稀释（防 v0.10.6 缺肉）且把运动区域外扩一圈 tile（边界不落在运动物体上）。
  // ② cell 级 3×3 box 平滑 —— 把 tile 方块边界化开（96px 过渡，防 v0.10.5 锯齿；
  //    两种手臂轮廓的"换源"变为缓慢渐变）。
  // ③ 分段权重（低 m 全融合 / 高 m 硬清零）。
  // 残差项暗光衰减（ns_alt 代理 ISO；rn 已在 ns_single 里折回 alt 域）
  res_fade =
      ns_single <= kResFadeLo
          ? 1.f
          : std::max(kResFadeMin,
                     1.f - (ns_single - kResFadeLo) / (kResFadeHi - kResFadeLo) * 0.7f);
  std::vector<float> mfield(static_cast<size_t>(n_cells) * (count - 1), 0.f);
  for (int y = 0; y < chh; ++y) {
    for (int x = 0; x < cw; ++x) {
      const int t = y * cw + x;
      const int tcx = x / 4, tcy = y / 4;
      for (int z = 0; z < count - 1; ++z) {
        const float m_res =
            static_cast<float>(dil[static_cast<size_t>(t) * 4 + z]) / 255.0f * res_fade;
        float m_mot = 0.f;
        for (int dy = -1; dy <= 1; ++dy) {
          const int ty = tcy + dy;
          if (ty < 0 || ty >= tiles_h_) continue;
          for (int dx = -1; dx <= 1; ++dx) {
            const int tx = tcx + dx;
            if (tx < 0 || tx >= tiles_w_) continue;
            m_mot = std::max(
                m_mot,
                mot[static_cast<size_t>(z)][static_cast<size_t>(ty) * tiles_w_ + tx]);
          }
        }
        mfield[(static_cast<size_t>(t)) * (count - 1) + z] =
            std::max(m_res, m_mot);
      }
    }
  }
  // v0.10.8：box 平滑 → **3×3 中值** —— 暗光残差误报是散点式（每 tile 随机），
  // 中值把散点杀掉而真实运动区域（连片高 m）完整保留；box 均值做不到（散点被
  // 摊开成"低幅大面积"——正是块状噪声的成因之一）。
  std::vector<float> msm(mfield.size());
  float medbuf[9];
  for (int y = 0; y < chh; ++y) {
    for (int x = 0; x < cw; ++x) {
      const int t = y * cw + x;
      for (int z = 0; z < count - 1; ++z) {
        int n = 0;
        for (int dy = -1; dy <= 1; ++dy) {
          const int yy = y + dy;
          if (yy < 0 || yy >= chh) continue;
          for (int dx = -1; dx <= 1; ++dx) {
            const int xx = x + dx;
            if (xx < 0 || xx >= cw) continue;
            medbuf[n++] = mfield[(static_cast<size_t>(yy) * cw + xx) * (count - 1) + z];
          }
        }
        // 插入排序取中位（n ≤ 9）
        for (int a = 1; a < n; ++a) {
          const float key = medbuf[a];
          int b = a - 1;
          while (b >= 0 && medbuf[b] > key) {
            medbuf[b + 1] = medbuf[b];
            --b;
          }
          medbuf[b + 1] = key;
        }
        msm[(static_cast<size_t>(t)) * (count - 1) + z] = medbuf[n / 2];
      }
    }
  }
  std::vector<uint8_t> wtex(static_cast<size_t>(n_cells) * 4);
  double neff_acc = 0.0;
  std::vector<float> mags;
  mags.reserve(static_cast<size_t>(n_cells) * (count - 1));
  // v0.10.9 诊断：m 链各阶段的分布（每帧）——排查暗光误报，不改算法
  std::string mis_diag, dil_diag, mot_diag, msm_diag;
  {
    const auto stat3 = [&](const auto& get, const char* prefix) {
      std::string out = prefix;
      for (int z = 0; z < count - 1; ++z) {
        int a = 0, b = 0, c = 0;
        for (int t = 0; t < n_cells; ++t) {
          const float v = get(z, t);
          if (v < 0.10f) ++a; else if (v < 0.35f) ++b; else ++c;
        }
        const float n = static_cast<float>(std::max(n_cells, 1));
        const auto f2 = [](float x) {
          return jnum(x, 4);
        };
        out += f2(a / n) + "/" + f2(b / n) + "/" + f2(c / n) + " ";
      }
      return out;
    };
    mis_diag = stat3([&](int z, int t) {
      return static_cast<float>(mis[static_cast<size_t>(t) * 4 + z]) / 255.0f;
    }, "");
    dil_diag = stat3([&](int z, int t) {
      return static_cast<float>(dil[static_cast<size_t>(t) * 4 + z]) / 255.0f;
    }, "");
    mot_diag = stat3([&](int z, int t) {
      return mot[static_cast<size_t>(z)][static_cast<size_t>((t / cw / 4) * tiles_w_ + (t % cw) / 4)];
    }, "");
    msm_diag = stat3(
        [&](int z, int t) { return msm[static_cast<size_t>(t) * (count - 1) + z]; },
        "");
  }
  for (int t = 0; t < n_cells; ++t) {
    const size_t ti = static_cast<size_t>(t);
    double wsum_cell = 1.0;  // 参考帧恒权重 1
    for (int z = 0; z < count - 1; ++z) {
      const float m = msm[ti * (count - 1) + z];
      // 分段：≤kMisLo 全融合（保细节）；≥kMisHi 硬清零（不容重叠）；中间线性
      const float wgt = m <= kMisLo
                            ? 1.0f
                            : (m >= kMisHi
                                   ? 0.0f
                                   : (kMisHi - m) / (kMisHi - kMisLo));
      wtex[ti * 4 + z] = static_cast<uint8_t>(wgt * 255.0f + 0.5f);
      wsum_cell += wgt;
      mags.push_back(m);
    }
    wtex[ti * 4 + 3] = 255;
    neff_acc += wsum_cell;
  }
  neff = static_cast<float>(
      neff_acc / std::max(n_cells, 1));
  glBindTexture(GL_TEXTURE_2D, tex_w_);
  glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, cw, chh, GL_RGBA, GL_UNSIGNED_BYTE,
                  wtex.data());
  std::sort(mags.begin(), mags.end());
  m_med = mags.empty()
                          ? 0.f
                          : mags[mags.size() / 2];
  m_p90 = mags.empty()
                          ? 0.f
                          : mags[mags.size() * 9 / 10];
  mis_ms = std::chrono::duration<float, std::milli>(
               std::chrono::steady_clock::now() - tmis)
               .count();

  // 融合 → tex_fused_（R16F 线性 mosaic）
  tf = std::chrono::steady_clock::now();
  stage("fuse");
  passMfnrFuse(w, h, cfa, black_phase, wl, rc.gains, frame_scale);
  glFinish();
  stage("chain");
  fuse_ms = std::chrono::duration<float, std::milli>(
                std::chrono::steady_clock::now() - tf)
                .count();
  }  // use_dft 二选一分支结束

  // 成片链（P0 用 fused 变体）——与 submitRaw 共享
  const int effective_rot = (rotation + 1) % 4;
  const bool swap_wh = (effective_rot % 2) == 1;
  const int dst_w = swap_wh ? h : w;
  const int dst_h = swap_wh ? w : h;
  if (!ensureTargets(dst_w, dst_h)) return false;
  while (glGetError() != GL_NO_ERROR) {
  }
  const bool rcd = demosaic == 1;
  // MFNR（v0.9.3）：融合把噪声 σ 降为单帧的 1/√N，P0/P4 的值域 σ 正比于 ns，
  // 仍喂单帧 σ 会错配 —— 真机 A/B（v0.9.2）实测：残余噪声（>4σr）依旧漏过、
  // 低对比纹理（1-2σr）反被抹掉（"降噪一般 + 糊"的双重观感）。σ 折减后值域
  // 重新对准融合后的信噪比：对残余噪声恢复作用、对纹理保持选择性。
  // （HDR+ 同款思路：merge 后按实际合并帧数更新噪声方差 σ²/N。）
  // v0.10.0：√N 用**实际等效帧数 N_eff**（robust merge 下运动 tile 权重 <1，
  // 实际融合量 <N —— Night Sight §3.3：按实际融合量算 σ，该区域自动多拿
  // 空间降噪补偿）。N_eff 接近 4 时与旧硬编码 √4 等价。
  // C 专项：折减直接作用于模型系数（var/N ⇒ A,B ÷N；ns_typ ÷√N 保持 σ 语义）。
  const float neff_n = std::max(neff, 1.0f);
  NoiseCoef nc = nc_single;
  for (int i = 0; i < 3; ++i) {
    nc.p0a[i] = nc_single.p0a[i] / neff_n;
    nc.p0b[i] = nc_single.p0b[i] / neff_n;
    nc.p4a[i] = nc_single.p4a[i] / neff_n;
    nc.p4b[i] = nc_single.p4b[i] / neff_n;
    // mismatch/tile-RMS 系数同折减（融合后输入的真实噪声 = 单帧/neff）
    nc.mma[i] = nc_single.mma[i] / neff_n;
    nc.mmb[i] = nc_single.mmb[i] / neff_n;
  }
  const float ns = ns_single / std::sqrt(neff_n);
  ChainCtx c;
  c.w = w;
  c.h = h;
  c.dst_w = dst_w;
  c.dst_h = dst_h;
  c.cfa = cfa;
  c.black_phase = black_phase;
  c.white_level = wl;
  c.gains = rc.gains;
  c.ccm = rc.ccm;
  c.p = &p_burst;
  c.effective_rot = effective_rot;
  c.rcd = rcd;
  // E4-3 颗粒：内容指纹取**参考帧 frames[0]**（u16 RAW ⇒ 字节数 = w·h·2）—— 与
  // 单帧路径同口径（同一场拍摄的单帧档与 burst 档得到不同图案是预期的：输入不同）。
  c.grain_fp = grainFingerprint(frames[0], static_cast<size_t>(w) * h * 2);
  // AMaZE 档（demosaic == 2）：MFNR 的融合结果在 GPU（tex_fused_），CPU 侧拿不到
  // ⇒ burst 路径用**参考帧 frames[0]** 跑单帧 AMaZE（质量 = 单帧档，日志里
  // amazeSrc="burst0" 标注）。这是未完成项（见 REPORT.md），不是静默降级。
  c.amaze = demosaic == 2;
  if (c.amaze) {
    c.amaze_raw = frames[0];
    c.amaze_stride = row_strides_px[0];
    c.amaze_lsc = lsc;
  }
  // 0.12.147：AMaZE 档同样要进 P0/P4（否则两档对比混入降噪差异）
  c.dn = (rcd || demosaic == 2) && noise_strength > 0.f;
  c.dn_k = noise_strength;
  c.nc = nc;
  c.nm = model;
  c.ns = ns;
  c.ns_raw = ns_single;
  c.neff = neff_n;
  c.lsc_ok = rc.lsc_ok;
  c.lsc_cols = lsc_cols;
  c.lsc_rows = lsc_rows;
  c.upload_ms = std::chrono::duration<float, std::milli>(tf - t0).count();
  c.mfnr_fused = true;
  // v0.11.87：短帧裁切比例透传给 AE（bracket 关时 -1 ⇒ AE 退回旧判据）
  // 0.12.102：HDR 下 short_sat 仍为 -1（App 只给旧 bracket 算），AE 行为不变。
  c.short_sat = satfix_on ? short_sat : -1.0f;
  c.bracket = satfix_on;
  c.hdr = hdr_active;
  c.nn = nn_dn;
  // 卡 H2：交付档（10bit HEIF）—— 同 submitRaw
  c.ten_bit = ten_bit;
  dbg_p0s_ = 0; dbg_cms_ = 0; dbg_cms_k_ = 0.f; dbg_p0w_ = 0.f;
  // NN 诊断字段每次合成都要清零（成员跨张残留会把上一张的读数记到这一张）
  dbg_nn_ok_ = 0; dbg_nn_fail_ = 0; dbg_nn_del_ = "";
  dbg_nn_ms_ = 0.f; dbg_nn_tiles_ = 0; dbg_nn_gain_ = 0.f;
  c.align_ab = align_ab;
  c.p0_sub = p0_sub;
  const auto f3 = [](float v) { return jnum(v, 5); };
  c.mfnr_kv = std::string("\"mfnr\":\"on\"") +
              ",\"fuse\":\"" + (dft_active ? "dft" : "mm") + "\"" +

              (dft_active ? ",\"dftNeff\":" + f3(neff_dft) +
                                ",\"dftUp\":" + f3(dft_ms[0]) +
                                ",\"dftDsp\":" + f3(dft_ms[1]) +
                                ",\"dftRb\":" + f3(dft_ms[2]) +
                                ",\"dftGpu\":" + f3(dft_ms[3])

                          : "") +
              ",\"alignMs\":" + f3(align_ms) +
              ",\"alignRbMs\":" + f3(align_rb_ms) +
              ",\"alignCpuMs\":" + f3(align_cpu_ms) +
              ",\"alignL1\":" + std::to_string(align_l1_frames) +
              ",\"misMs\":" + f3(mis_ms) +
              ",\"fuseMs\":" + f3(fuse_ms) +
              ",\"gx\":" + f3(gx_sum / (count - 1)) +
              ",\"gy\":" + f3(gy_sum / (count - 1)) +
              ",\"confMed\":" + f3(conf_sum / (count - 1)) +
              ",\"mMed\":" + f3(m_med) +
              ",\"mP90\":" + f3(m_p90) +
              ",\"neff\":" + f3(neff) +
              ",\"rn\":" + f3(rn) +
              ",\"brk\":" + (satfix_on ? "1" : "0") +
              // v0.11.87：**修诊断字段错位** —— 旧式记的是 frame_scale[0]（参考帧恒 1.0），
              // 连续四轮把"归一是否生效"看成"没生效"。短帧索引恒为 3（BRACKET_SHORT_IDX）。
              ",\"brkRatio\":" + f3(frame_scale[3]) +
              ",\"motMed\":" + f3(mot_med) +
              ",\"resFade\":" + f3(res_fade) +
              ",\"nsSng\":" + f3(ns_single) +
              ",\"misD\":\"" + mis_diag + "\"" +
              ",\"dilD\":\"" + dil_diag + "\"" +
              ",\"motD\":\"" + mot_diag + "\"" +
              ",\"msmD\":\"" + msm_diag + "\"" +
              ",\"dMed\":" + f3(dx_med_max) +
              ",\"dP90\":" + f3(dx_p90_max) +
              ",\"tiles\":" + std::to_string(tiles_w_ * tiles_h_) +
              // 0.12.161：collar flow 生效性（0.12.122 的版本是 no-op，无字段可查）
              //   collarSupp/supp = 可观测 tile 数、collarRegions = 走 collar 插值的
              //   连通区数、globalRegions = 退全图中值、collarTiles = 被改写的 tile 数
              ",\"collarSupp\":" + std::to_string(dbg_collar_supp_) +
              ",\"collarRegions\":" + std::to_string(dbg_collar_regions_) +
              ",\"collarCReg\":" + std::to_string(dbg_collar_regions_collar_) +
              ",\"collarGReg\":" + std::to_string(dbg_collar_regions_global_) +
              ",\"collarTiles\":" + std::to_string(dbg_collar_tiles_);
  // HDR 融合 P2（0.12.77）：侧帧 D4 已存 tex_hdr_side_（失败时 hdr_active=false，
  // 下游 passHdrMertens 不跑 = zero 链，成片不丢）。ev 数组进日志供离线核对。
  // P3（0.12.82）：侧帧直通 + Mertens 架构退场（不再跑 finishStillChain 的
  // hdr 分支；代码保留，hdr_mertens 恒 false —— 回退通路仍在）。SDR base =
  // 跨曝光堆叠融合输出（PC RAW 同构）；HDR 参考（gainmap 改造）是 P4。
  c.hdr_mertens = false;
  c.hdr_ev_side = 0.f;
  if (hdr_ev != nullptr) {
    std::string evs, fss;
    for (int i = 0; i < count && i < 4; ++i) {
      if (i) { evs += ","; fss += ","; }
      evs += jnum(hdr_ev[i], 3);
      fss += jnum(frame_scale[i], 4);
    }
    c.mfnr_kv += ",\"hdrEv\":[" + evs + "],\"hdrFs\":[" + fss + "]";
  }
  finishStillChain(c, t0, out_rgba);
  return true;
}

// setLut：任意线程调用，只暂存（渲染线程在下一帧 applyPendingLut 里建纹理）。
// RGB → RGBA 补齐（RGBA16F 是 ES 3.0 required 且项目已在用的格式，比 RGB16F 稳）。
void FrameRenderer::setLut(const float* data, int size, const float dmin[3],
                           const float dmax[3]) {
  std::lock_guard<std::mutex> lk(lut_mu_);
  if (data == nullptr || size <= 1) {
    pending_lut_.clear();
    pending_lut_size_ = 0;
  } else {
    const size_t n = static_cast<size_t>(size) * size * size;
    pending_lut_.resize(n * 4);
    for (size_t i = 0; i < n; ++i) {
      pending_lut_[i * 4 + 0] = data[i * 3 + 0];
      pending_lut_[i * 4 + 1] = data[i * 3 + 1];
      pending_lut_[i * 4 + 2] = data[i * 3 + 2];
      pending_lut_[i * 4 + 3] = 1.0f;
    }
    pending_lut_size_ = size;
    for (int i = 0; i < 3; ++i) {
      pending_lut_dmin_[i] = dmin ? dmin[i] : 0.0f;
      pending_lut_dmax_[i] = dmax ? dmax[i] : 1.0f;
    }
  }
  lut_dirty_ = true;
}

// M4：把暂存的 LUT 上传为 RGBA16F 3D 纹理（渲染线程、须有当前 context）。
// 未变更时立即返回 —— 每帧调用一次，开销 = 一次加锁 + 标志判断。
void FrameRenderer::applyPendingLut() {
  std::vector<float> data;
  int size = 0;
  float dmin[3] = {0.0f, 0.0f, 0.0f};
  float dmax[3] = {1.0f, 1.0f, 1.0f};
  {
    std::lock_guard<std::mutex> lk(lut_mu_);
    if (!lut_dirty_) return;
    data.swap(pending_lut_);
    size = pending_lut_size_;
    for (int i = 0; i < 3; ++i) {
      dmin[i] = pending_lut_dmin_[i];
      dmax[i] = pending_lut_dmax_[i];
    }
    lut_dirty_ = false;
  }
  if (size <= 1 || data.empty()) {
    lut_size_ = 1;  // 关闭：uLutOn 恒 0（纹理留着供重选覆盖；size=1 → 采样恒在原点）
    Logger::instance().log(LogLevel::kInfo, "engine", "lut.load",
                           "{\"state\":\"off\"}");
    return;
  }
  const auto t0 = std::chrono::steady_clock::now();
  if (lut_tex_ == 0) glGenTextures(1, &lut_tex_);
  glBindTexture(GL_TEXTURE_3D, lut_tex_);
  glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
  glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
  glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA16F, size, size, size, 0, GL_RGBA,
               GL_FLOAT, data.data());
  const GLenum err = glGetError();
  for (int i = 0; i < 3; ++i) {
    lut_dmin_[i] = dmin[i];
    lut_dmax_[i] = dmax[i];
  }
  lut_size_ = size;
  const double ms = std::chrono::duration<double, std::milli>(
                        std::chrono::steady_clock::now() - t0)
                        .count();
  Logger::instance().log(
      LogLevel::kInfo, "engine", "lut.load",
      std::string("{\"state\":\"") + (err == GL_NO_ERROR ? "ok" : "gl_error") +
          "\",\"size\":" + std::to_string(size) + ",\"ms\":" + std::to_string(ms) +
          ",\"err\":" + std::to_string(static_cast<int>(err)) + "}");
}

// LUT 绑定与 uniform 下发（kFsOut 与 kFsHist 共用）。纹理单元 1 —— 单元 0 是输入帧。
// 注意：**无论开关都要绑定纹理并下发全部 uniform**。早期版本在关闭时提前 return，
// 结果 sampler3D 指向未绑定的单元 → Mali 预测执行采样 → draw 报 1282、整帧全黑。
void FrameRenderer::bindLut(GLint loc_tex, GLint loc_on, GLint loc_strength,
                            GLint loc_dmin, GLint loc_dinv, GLint loc_size,
                            float strength) {
  // v0.10.95 修复"强度滑杆 100% 以上无效"：上限放开到 1.5（下限 0 保留）。
  // >1 的语义 = 沿 LUT 色差线性外推（mix(c, lut(c), k) 对 k>1 本来就是外推，
  // 旧版 clamp 到 1.0 使 100%~150% 整段无变化——UI 允许拉到 150%，滑杆"拉了
  // 没反应"即此）。溢出由输出 RGBA8 显示域自然截断；与 PhotonCamera 的
  // >100% 强度语义一致（CameraController.applyLutStrengthPref 同一约定）。
  const float k = strength < 0.0f ? 0.0f : (strength > 1.5f ? 1.5f : strength);
  const bool on = lut_size_ > 1 && k > 0.0f;
  glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_3D, lut_tex_);
  glUniform1i(loc_tex, 1);
  glUniform1f(loc_on, on ? 1.0f : 0.0f);
  glUniform1f(loc_strength, on ? k : 0.0f);
  glUniform3f(loc_dmin, lut_dmin_[0], lut_dmin_[1], lut_dmin_[2]);
  glUniform3f(loc_dinv, 1.0f / (lut_dmax_[0] - lut_dmin_[0]),
              1.0f / (lut_dmax_[1] - lut_dmin_[1]),
              1.0f / (lut_dmax_[2] - lut_dmin_[2]));
  // 关闭态用 1（恒等纹理边长）：shader 里 (uLutSize-1) = 0，采样坐标恒为 (0,0,0)，不会越界
  glUniform1f(loc_size, static_cast<float>(lut_size_ > 1 ? lut_size_ : 1));
}

// 实时直方图（M3）：tex_work_（胶片输出）→ 显示域亮度 → 256×96 RGBA8 → 读回统计。
// 必须在渲染线程、有当前 context 时调用；尚未渲染过预览帧（tex_work_ 为空）返回 false。
bool FrameRenderer::histogram(int* out, int n) {
  return histogramFrom(tex_work_, out, n);
}

// 把 src 的**显示域亮度图**渲染到 fbo（w×h）。两个用途：
//   ① 直方图统计（256×96、单点采样 → readPixels 后算分位）；
//   ② 局部色调映射的低频图（32×24、**8×8 盒式平均**，只留在 GPU 上给 kFsFilm 采样）。
// ② 必须平均而非单点：每格覆盖源图 ~128×128 像素，单点采到深色物体就会整格提亮
// → 成片亮斑（真机反馈）。
// lut_strength 传 0 = 恒等（bindLut 仍会绑定纹理并下发全部 uniform，见其注释）。
void FrameRenderer::renderLuma(GLuint src, GLuint fbo, int w, int h,
                              float lut_strength, bool box_filter, int stat_ch,
                              int stat_mode) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo);
  glViewport(0, 0, w, h);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, src);
  if (box_filter) {
    glUseProgram(prog_local_);
    glUniform1i(u_local_src_.tex, 0);
    glUniform2f(u_local_src_.step, 1.0f / static_cast<float>(w),
                1.0f / static_cast<float>(h));
  } else {
    glUseProgram(prog_hist_);
    applyPendingLut();
    glUniform1i(u_hist_.tex, 0);
    // v0.7.3：0 = luma（UI 直方图）；1 = max 通道（filmic 自动 EV 判据）
    glUniform1f(u_hist_.stat_ch, stat_ch > 0 ? 1.0f : 0.0f);
    // v0.10.27：0 = 显示域 sRGB 编码（UI 直方图）；1 = 对数域（自动 EV 判据，见 kFsHist）
    glUniform1f(u_hist_.stat_mode, stat_mode > 0 ? 1.0f : 0.0f);
    bindLut(u_hist_.lut, u_hist_.lut_on, u_hist_.lut_strength, u_hist_.lut_dmin,
            u_hist_.lut_dinv, u_hist_.lut_size, lut_strength);
  }
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// M4-6：统计源参数化 —— 自适应影调必须对**场景纹理**（baseTone 之前）统计，
// 而 UI 直方图仍看 film 输出（tex_work_），两者共用 kFsHist 同一实现。
// v0.6.24：加 bypass_lut —— 风格 LUT 不该改曝光（默认参数下行为与旧版逐位一致）。
bool FrameRenderer::histogramFrom(GLuint src, int* out, int n, bool bypass_lut,
                                 int stat_ch, int stat_mode, float center_frac,
                                 double* weighted_out, float* bright_xy) {
  if (out == nullptr || n < 2 || src == 0 || scene_w_ <= 0) return false;
  // v0.10.28：256×96 → 512×288。24K 采样点上 P99.99 = 前 2.5 名、max 采样对
  // 0.001% 级的小面积高光（水壶镜面/白壶身）几乎必然 miss——真机 162012 实证：
  // 渲染域最亮 1.02（LSC 1.2× 抬升后刚好过数据白点）但统计 w999 只见 2.05，
  // 白点不上探、4.1% 的白壶身渐变被肩部压进 245+ 平台。147K 点让 P99.99 有
  // 15 个样本、max 有望命中 0.002% 级目标；readback 590KB/帧（≈0.3ms）可接受。
  // UI 直方图共用本 FBO（桶映射按 8bit 值，与 FBO 尺寸无关）。
  const int hw = 512, hh = 288;
  if (tex_hist_ == 0) {
    // @domain: A5 —— 直方图统计（显示域亮度）
    makeTex(&tex_hist_, &fbo_hist_, GL_RGBA8, hw, hh);
    hist_w_ = hw;
    hist_h_ = hh;
  }
  renderLuma(src, fbo_hist_, hw, hh, bypass_lut ? 0.0f : lut_strength_, false,
             stat_ch, stat_mode);
  std::vector<uint8_t> px(static_cast<size_t>(hw) * hh * 4);
  glReadPixels(0, 0, hw, hh, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
  for (int i = 0; i < n; ++i) out[i] = 0;
  if (weighted_out != nullptr) {
    for (int i = 0; i < n; ++i) weighted_out[i] = 0.0;
  }
  // 中心高斯权重（0.12.50，PhotonCamera MeteringSystem.centerWeight 同款）：
  // dx/dy 归一化到 [-1,1]，σ=0.32，w = 0.35 + 0.65·exp(−0.5·(dx²+dy²)/σ²)。
  // 为什么需要：截尾挡不住"天空占 40~45%"——真机 105249/105338（阴天天空占近半）
  // 的截尾中灰仍被天空抬到 0.19~0.20（> 目标 0.18）⇒ AE 判定"够亮"不提亮。
  // 中心权重让画面中央（主体/楼体）主导统计，边缘天空权重降到 ~0.6。
  const float cx = hw * 0.5f, cy = hh * 0.5f;
  const float inv_hw2 = 1.0f / (hw * 0.5f), inv_hh2 = 1.0f / (hh * 0.5f);
  constexpr float kSigma = 0.32f;
  // 中央区域门控（v0.11.77）：只统计中央 center_frac 宽高的区域，对齐 PC legacy
  // matcher 的 centerFractionPerAxis=2/3 —— 画面边缘的高光不参与曝光决策。
  const bool center_only = center_frac > 0.0f && center_frac < 1.0f;
  const float half_w = hw * center_frac * 0.5f;
  const float half_h = hh * center_frac * 0.5f;
  for (int y = 0; y < hh; ++y) {
    if (center_only && std::fabs(y + 0.5f - hh * 0.5f) > half_h) continue;
    for (int x = 0; x < hw; ++x) {
      if (center_only && std::fabs(x + 0.5f - hw * 0.5f) > half_w) continue;
      const size_t i = (static_cast<size_t>(y) * hw + x) * 4;
      const int bin = px[i] * (n - 1) / 255;
      ++out[bin];
      if (weighted_out != nullptr) {
        const float dx = (x + 0.5f - cx) * inv_hw2;
        const float dy = (y + 0.5f - cy) * inv_hh2;
        const float g = std::exp(-0.5f * (dx * dx + dy * dy) / (kSigma * kSigma));
        weighted_out[bin] += static_cast<double>(0.35f + 0.65f * g);
      }
    }
  }

  // ── v0.12.67：高光簇中心 —— 逐行移植 PhotonCamera `LutRenderer.calculateMeteringResults`
  // 的 HIGHLIGHT_PRIORITY 分支（不是自创）：32×32 luma 网格 → top2% 动态阈值
  // （且 ≥128 显示 / 对应编码 140）→ 5×5 簇求和 → 焦点偏置 1/(1+d·0.05) →
  // 历史偏置（距上一帧 <2 格 ×1.2）→ 归一化坐标（GL y 向上翻转）。
  // 本 FBO 512×288，每格 16×9 像素平均，等价于 PC 的 32×32 metering FBO。
  if (bright_xy != nullptr) {
    constexpr int G = 32;
    double grid[G * G] = {0.0};
    int cnt[G * G] = {0};
    for (int y = 0; y < hh; ++y) {
      const int gy = y * G / hh;
      for (int x = 0; x < hw; ++x) {
        const size_t i = (static_cast<size_t>(y) * hw + x) * 4;
        const int gx = x * G / hw;
        grid[gy * G + gx] += px[i];
        ++cnt[gy * G + gx];
      }
    }
    for (int i = 0; i < G * G; ++i) {
      grid[i] = cnt[i] > 0 ? grid[i] / cnt[i] : 0.0;
    }
    int hist_g[256] = {0};
    for (int i = 0; i < G * G; ++i) {
      int v = static_cast<int>(grid[i]);
      hist_g[v < 0 ? 0 : (v > 255 ? 255 : v)]++;
    }
    int acc_g = 0, thr = 140;
    for (int v = 255; v >= 0; --v) {
      acc_g += hist_g[v];
      if (acc_g >= 20) {
        thr = v > 140 ? v : 140;
        break;
      }
    }
    double best = -1.0;
    int bx = -1, by = -1;
    for (int y = 2; y < G - 2; ++y) {
      for (int x = 2; x < G - 2; ++x) {
        if (grid[y * G + x] < thr) continue;
        double sum = 0.0;
        for (int ky = -2; ky <= 2; ++ky) {
          for (int kx = -2; kx <= 2; ++kx) {
            sum += grid[(y + ky) * G + (x + kx)];
          }
        }
        double bias = 1.0;
        if (hl_focus_x_ >= 0.0f) {
          const double fx = hl_focus_x_ * G;
          const double fy = (1.0 - hl_focus_y_) * G;
          bias = 1.0 / (1.0 + std::hypot(x - fx, y - fy) * 0.05);
        }
        double hb = 1.0;
        if (hl_best_x_ >= 0) {
          const double dx = x - hl_best_x_, dy = y - hl_best_y_;
          if (dx * dx + dy * dy < 4.0) hb = 1.2;
        }
        const double score = sum * bias * hb;
        if (score > best) {
          best = score;
          bx = x;
          by = y;
        }
      }
    }
    if (bx >= 0) {
      hl_best_x_ = bx;
      hl_best_y_ = by;
      bright_xy[0] = (static_cast<float>(bx) + 0.5f) / static_cast<float>(G);
      bright_xy[1] = 1.0f - (static_cast<float>(by) + 0.5f) / static_cast<float>(G);
    }
  }
  return true;
}

// 自适应曝光增益：把场景**中间调**（显示域中位）折回线性，归一到目标亮度。
// 职责是**提亮中间调**（把色彩带浓，C* 随 L* 上升），不是"校正曝光正确性"。
// ── v0.6.33 回退中位锚定（真机"室内过亮 + 噪声爆炸"）────────────────────
// 中位锚定（v0.6.26–v0.6.32）的**域搞错了**：kTarget 0.26 是把 PhotonCamera
// **成片**（已提亮、显示域）的中位 139/255 折回线性得来的，却被当作**提亮之前**
// 线性域的目标。两者不是一回事 —— 那是 PC 的输出，不是它的输入目标。
// 后果（5 张室内样片，成片 vs DNG+libraw 参照的 P50）：我们又亮 **2.4–3.2 倍**，
// 155852 实测中位线性 0.263（= 正好命中 kTarget，锚定"精确生效"，方向却错）。
// 判据本身也站不住：中位锚定等价于断言"每个场景的中位反射率都是 18%"，对室内暗
// 场景必然过曝；而参照的 P99 已落在 175–255（HAL 曝光本来是准的，不该大改）。
// 恢复 v0.6.18 定稿的 **P95 白点判据**（尊重场景自身的亮度范围：暗场景提亮、亮
// 场景不动），并把上限从 2.0 收到 1.5（暗场景 +0.58EV 封顶）。
// v0.6.35 再把判据本体从 P95 换成 **P99**（原因见下方 lin_hi 处），上限提到 1.6。
// ⚠️ 上面 v0.6.19 那句"kTarget 0.80 必须与 kFsFilm 的 knee 一致"指的是**软肩 knee
// 0.80**，与现行 kTarget（线性中位目标 0.02）**不是同一个量**，别照着改；
// "限幅只许提亮（≥1.0）"也已在 v0.7.0 废除（新曲线自带暗部提亮，亮场景必须能压暗）。
// ── 场景分位统计（v0.7.3：中位 → 分位，服务 filmic 的自动 EV 标定）──────────────
// 为什么取 **max 通道**：filmic 内部按 max RGB 归一（darktable 的 norm），用 luma 会
// 系统性偏低（luma ≤ max），算出的黑点/白点都会往里收。旁路 LUT：风格层不该改 EV 判据。
// **一次直方图出三个分位**：P0.5 → black relative exposure，P50 → 诊断，P99.5 → white。
// 统计失败返回 valid=false（调用方保持上一帧参数，不写 last_*）。
FrameRenderer::SceneStats FrameRenderer::measureSceneStats(GLuint src,
                                                          float center_frac) {
  // v0.10.27：64 → 256 桶。对数域统计编码下 readback 的 256 级与桶一一对应
  // （px[i] ∈ [0,255] → out[px[i]]），每级 0.078EV；旧 64 桶会把这分辨率扔掉 3/4。
  constexpr int kN = 256;
  SceneStats st;
  int hist[kN];
  double wh[kN];
  float bx = -1.0f, by = -1.0f;
  if (!histogramFrom(src, hist, kN, /*bypass_lut=*/true, /*stat_ch=*/1,
                     /*stat_mode=*/1, center_frac, wh,
                     center_frac <= 0.0f ? &bx : nullptr)) return st;
  if (bx >= 0.0f) {
    hl_x_ = bx;
    hl_y_ = by;
    hl_valid_ = true;
  }
  long total = 0;
  for (int i = 0; i < kN; ++i) total += hist[i];
  if (total <= 0) return st;
  const auto pct = [&](double q) {
    const long want = static_cast<long>(static_cast<double>(total) * q);
    long acc = 0;
    for (int i = 0; i < kN; ++i) {
      acc += hist[i];
      if (acc >= want) return static_cast<float>(i) / static_cast<float>(kN - 1);
    }
    return 1.0f;
  };
  // 对数域反折（与 kFsHist 的 uStatMode=1 编码同式）：enc = (log2(m)+12)/20
  //   ⇒ m = exp2(enc·20 − 12)。撞底桶读成 2^-12 的截断语义由 black 判据的
  //   −7.65 物理下限兜底；撞顶读成 2^8 由 white 的 4.556 上限兜底。
  // 编码单调 ⇒ 折回后仍是同一像素的 max 通道值，不是近似。
  // 旧显示域统计的真问题（v0.10.27 定案）：srgbEncode 先 clamp(c,0,1)，线性 ≥1.0
  // 的强光源全部并进顶桶 —— P99 撞顶恒 1.0，白点对高光失明（真机 auto 模式
  // whiteEv 恒钉 2.438 下限的直接原因），成片高光平顶白墙。
  // v0.10.17：分位 P0.5/P99.5 → **P0.1/P99**（真机 0.10.16 校准）——P0.5 把 black 收到
  // −5.6EV，dr 从 12.2 收到 8.0，power 被派生公式推到 4.7+ ⇒ 中间调压暗 + 色度比
  // 放大（用户实测"中间调低、天空过饱和"）；P99.5 的 shoulder 过渡太窄 ⇒ 水壶高光
  // 推白。P0.1 更贴近 darktable autodetect"框选最暗"的 min 语义，P99 给 shoulder 留
  // 过渡带。（P99 的"撞顶失明"由 updateFilmicEV 的 P99.9 上探修正，见彼处。）
  // v0.10.23：折回函数与 kFsOut/kFsHist 的编码**必须同步**的教训继承于此 ——
  // 对数编码与反折两处必须逐字一致，否则 linMid/pLo/pHi 与成片域错位、自动判据
  // 吃进失真值。
  const auto to_lin = [](float enc) { return std::exp2(enc * 20.0f - 12.0f); };
  // v0.11.77：RAW 裁切像素比例（>= 0.98 白点）—— PC `maximumClippedFraction` 判据的量。
  {
    long sat = 0;
    for (int i = 0; i < kN; ++i) {
      if (to_lin(static_cast<float>(i) / static_cast<float>(kN - 1)) >= 0.98f) sat += hist[i];
    }
    st.sat_frac = total > 0 ? static_cast<float>(sat) / static_cast<float>(total) : 0.0f;
  }
  st.p_lo = to_lin(pct(0.001));
  st.p_mid = to_lin(pct(0.50));
  st.p_hi = to_lin(pct(0.99));
  st.p_hi999 = to_lin(pct(0.999));
  // v0.11.63：自动曝光的高光判据 = P99.5（允许 0.5% 像素裁切，与 PC legacy matcher
  // 的 maximumClippedFraction=0.005 同口径）。P99.9 留给 filmic 白点上探，不改其语义。
  st.p_hi995 = to_lin(pct(0.995));
  // v0.11.88：P95 = **大面积高光**的判据（见 SceneStats 注释）。同一张直方图，零成本。
  st.p_hi95 = to_lin(pct(0.95));
  // v0.11.91：几何平均（对数域直方图的加权平均再折回）—— 感知亮度的稳健代理。
  // 空桶跳过（to_lin 在最低桶会给出 2^-12 的截断值，直接参与平均会把 geo 拉塌）。
  {
    double acc = 0.0;
    long n = 0;
    for (int i = 0; i < kN; ++i) {
      if (hist[i] <= 0) continue;
      acc += static_cast<double>(hist[i]) * std::log(static_cast<double>(to_lin(
                 static_cast<float>(i) / static_cast<float>(kN - 1))));
      n += hist[i];
    }
    st.p_geo = n > 0 ? static_cast<float>(std::exp(acc / static_cast<double>(n))) : 0.0f;
  }
  // v0.12.46：鲁棒中灰（截尾 25%~75% 的 log 域均值）。直方图已在手，零额外成本。
  // 只在截尾区间内累加 log(线性值)，权重 = 落入该区间的桶计数。
  // 为什么要截尾：p_geo 把最亮 25% 也算进去，天空/白墙占 30~40% 时被抬高 ⇒ AE 少提亮。
  // 0.12.50：改用**中心高斯加权**直方图（PC MeteringSystem 同口径）。
  // 旧实现用全图未加权直方图，天空占 40%+ 时截尾仍被抬高中灰（真机 105249/105338）。
  {
    double wtotal = 0.0;
    for (int i = 0; i < kN; ++i) wtotal += wh[i];
    const double lo = wtotal * 0.25, hi = wtotal * 0.75;
    double cum = 0.0, acc = 0.0, n = 0.0;
    for (int i = 0; i < kN; ++i) {
      if (wh[i] <= 0.0) continue;
      const double a = cum, b = cum + wh[i];
      cum = b;
      const double s = std::max(a, lo), e = std::min(b, hi);
      if (e <= s) continue;
      acc += (e - s) *
             std::log(static_cast<double>(to_lin(static_cast<float>(i) / static_cast<float>(kN - 1))));
      n += e - s;
    }
    st.p_robust = n > 0.0 ? static_cast<float>(std::exp(acc / n)) : 0.0f;
  }
  last_p_robust_ = st.p_robust;
  // v0.10.28：p_max = **采样到的最高非空桶**（darktable autodetect 的 max 语义）。
  // 对数编码上限 2^8（= +8EV）后 max 不再被显示域 clamp 淹没——0.10.18 当时
  // 改用分位正是迫于此；现在分位保留（P99 驱动 black/基础判据），max 只驱动
  // 白点上探（见 updateFilmicEV：+0.4EV security、w99+1.5 幅度闸、4.556 上限）。
  for (int i = kN - 1; i >= 0; --i) {
    if (hist[i] > 0) {
      st.p_max = to_lin(static_cast<float>(i) / static_cast<float>(kN - 1));
      break;
    }
  }
  st.valid = true;
  last_lin_mid_ = st.p_mid;  // 诊断字段（raw.render / preview.filmic）
  last_lin_geo_ = st.p_geo;  // v0.11.92：预览几何平均（成片 AE 的目标亮度）
  last_p_lo_ = st.p_lo;
  last_p_hi_ = st.p_hi;
  return st;
}

namespace {
// 5×5 线性系统：列主元高斯消元 + 回代（A 行主序，解写回 x）。奇异返回 false。
// 供 filmic 的 toe/shoulder 系数求解用（darktable 用 gauss_solve，等价）。
bool solve5(double a[5][5], double b[5], double x[5]) {
  for (int col = 0; col < 5; ++col) {
    int piv = col;
    for (int r = col + 1; r < 5; ++r)
      if (std::abs(a[r][col]) > std::abs(a[piv][col])) piv = r;
    if (std::abs(a[piv][col]) < 1e-12) return false;
    if (piv != col) {  // 手写交换：不依赖 <utility> 的 std::swap 可见性
      for (int c = 0; c < 5; ++c) {
        const double t = a[piv][c];
        a[piv][c] = a[col][c];
        a[col][c] = t;
      }
      const double tb = b[piv];
      b[piv] = b[col];
      b[col] = tb;
    }
    for (int r = col + 1; r < 5; ++r) {
      const double f = a[r][col] / a[col][col];
      for (int c = col; c < 5; ++c) a[r][c] -= f * a[col][c];
      b[r] -= f * b[col];
    }
  }
  for (int r = 4; r >= 0; --r) {
    double s = b[r];
    for (int c = r + 1; c < 5; ++c) s -= a[r][c] * x[c];
    x[r] = s / a[r][r];
  }
  return true;
}

// U-2：CPU 侧 sRGB 编码（IEC 61966-2-1）—— 与 kFsOut/kFsStyle 的 `srgbEncode` **同一条
// 曲线**（系数 12.92 / 1.055 / 2.4 / 0.055、拐点 0.0031308），只是把 [37][38] 的 EV 语义
// 映射到显示域端点时用（`computeEditorCurve`），不参与逐像素路径。
double srgbEncodeD(double c) {
  if (!(c > 0.0)) return 0.0;
  if (c >= 1.0) return 1.0;
  return c > 0.0031308 ? 1.055 * std::pow(c, 1.0 / 2.4) - 0.055 : 12.92 * c;
}
}  // namespace

// ── filmic spline（darktable `dt_iop_filmic_rgb_compute_spline` 的 v3 分支）────────
// 公式逐项对照 darktable commit 92f6c28（src/iop/filmicrgb.c）；系数组织与
// PhotonCamera 的 computeFilmicToneCurveUniforms 一致（便于交叉核对，两边数学等价）。
// ⚠️ output_power **不是自由参数**：darktable `_compute_output_power` 由
// black/white/grey 派生（PC 固化 3.6148 就是该式在它那组参数下的值，复算差 0.04%）。
// 系数顺序：m1..m5 各 3 个（下标 [0]=toe [1]=shoulder [2]=中间线性段），
// 每段 y = m1 + x·(m2 + x·(m3 + x·(m4 + x·m5)))。
FrameRenderer::FilmicSpline FrameRenderer::computeFilmicSpline(float black_ev, float white_ev,
                                                               float contrast_param_in,
                                                               const float lat_sh[3],
                                                               const float lat_toe[3]) {
  // 全程 double：shoulder 的 5×5 系统条件数差，用 float 中间量会把 1e-7 的舍入放大到
  // 1e-5（与 float64 参考实现对拍时可见）。虽然远低于 8bit 量化精度（1/255 ≈ 4e-3），
  // 但没有理由把它引进来 —— darktable 的 gauss_solve 同样在 double 下求解。
  constexpr double kGrey = 0.1845;                // grey_source（18.45% 中灰）
  constexpr double kSafety = 0.01;                // darktable SAFETY_MARGIN
  constexpr double kBlackTargetPct = 0.01517634;  // black_point_target 默认（%）
  // v0.10.27：contrast 由 constexpr 提升为函数参数 —— darktable 原版就是四个独立滑杆
  // （black/white/latitude/contrast），此前 contrast 恒 1.0 只是不带滑杆的默认值。
  // 自动白点高光上探用 contrast 参数补偿 power 回退（updateFilmicEV）。
  // v0.11.5（E4-4）：latitude 同样由标量提升为 **per-channel**（lat_sh/lat_toe 各 3 个，
  // 来自 FilmParams[5..7]/[8..10]）—— 见函数体内注释的"原版参数维度自然展开"论证。
  const double kContrastParam = static_cast<double>(contrast_param_in);
  FilmicSpline sp;
  const double black =
      std::min(static_cast<double>(black_ev), static_cast<double>(white_ev) - 0.2);
  const double white = std::max(static_cast<double>(white_ev), black + 0.2);
  const double dr = white - black;
  const double power = std::log(kGrey) / std::log(-black / dr);
  const double black_disp = std::pow(kBlackTargetPct / 100.0, 1.0 / power);
  const double white_disp = 1.0;
  const double grey_disp = std::pow(kGrey, 1.0 / power);
  const double grey_log = std::abs(black) / dr;

  const double slope = kContrastParam * dr / 8.0;
  double min_c = 1.0;  // 低于它黑/白点都够不到
  min_c = std::max(min_c, (white_disp - grey_disp) / (1.0 - grey_log));
  min_c = std::max(min_c, (grey_disp - black_disp) / grey_log);
  min_c += kSafety;
  double contrast = slope / (power * std::pow(grey_disp, power - 1.0));
  contrast = std::min(std::max(contrast, min_c), 100.0);

  const double li = grey_disp - contrast * grey_log;
  const double xmin = (black_disp + kSafety * (white_disp - black_disp) - li) / contrast;
  const double xmax = (white_disp - kSafety * (white_disp - black_disp) - li) / contrast;
  // ── E4-4：per-channel 过渡区位置 + 3×2 个 5×5 系统 ────────────────────────
  // 原版（darktable v3）里 toe 与 shoulder 的插值本来就是两个独立表达式
  // （`toe_log = (1-lat)·grey_log + lat·xmin` / `sh_log = (1-lat)·grey_log + lat·xmax`），
  // 只是共用一个 `lat` ⇒ 拆成 lat_toe/lat_sh 各 per-channel 是**原版参数维度的自然
  // 展开**（三层乳剂的 toe/shoulder 位置不同 = 阴影/高光色偏的物理机制），不是自研。
  // 端点/斜率约束（f(0)/f'(0)/f(Tl)/f'(Tl)/f''(Tl)）一条不改 ⇒ [37][38] 定义的端点
  // 与 [5..10] 无关（正交性证据见交付报告）；[4] contrast 改的是线性段斜率，与
  // lat 改的"过渡区位置"有交互 —— 由同一组求解自然表达。
  // 默认全 0.01（= darktable 默认 = 接线前硬编码值）⇒ 三次求解的输入逐位相同 ⇒
  // 系数与接线前逐位相同（A/B 通路纪律）。
  for (int ch = 0; ch < 3; ++ch) {
    // clamp 到 [0,1]（规格 §3.4.4）；非有限值（NaN/±Inf 经 clamp 后仍可能为 NaN）
    // 回退 darktable 默认 0.01 —— 输入来自预设/JNI，属信任边界。
    const float raw_toe = std::isfinite(lat_toe[ch]) ? lat_toe[ch] : 0.01f;
    const float raw_sh = std::isfinite(lat_sh[ch]) ? lat_sh[ch] : 0.01f;
    const double kLatToe = static_cast<double>(std::clamp(raw_toe, 0.0f, 1.0f));
    const double kLatSh = static_cast<double>(std::clamp(raw_sh, 0.0f, 1.0f));
    const double toe_log = (1.0 - kLatToe) * grey_log + kLatToe * xmin;
    const double sh_log = (1.0 - kLatSh) * grey_log + kLatSh * xmax;
    const double toe_d = toe_log * contrast + li;
    const double sh_d = sh_log * contrast + li;

    // POLY_4 toe：f(0)=black_disp / f'(0)=0 / f(Tl)=toe_d / f'(Tl)=contrast / f''(Tl)=0
    {
      const double t = toe_log;
      double a[5][5] = {{0, 0, 0, 0, 1},
                        {0, 0, 0, 1, 0},
                        {t * t * t * t, t * t * t, t * t, t, 1},
                        {4 * t * t * t, 3 * t * t, 2 * t, 1, 0},
                        {12 * t * t, 6 * t, 2, 0, 0}};
      double b[5] = {black_disp, 0.0, toe_d, contrast, 0.0};
      double x[5] = {0, 0, 0, 0, 0};
      if (solve5(a, b, x)) {  // 解按 x⁴..x⁰ 排列 → m5..m1
        sp.m5[ch][0] = static_cast<float>(x[0]);
        sp.m4[ch][0] = static_cast<float>(x[1]);
        sp.m3[ch][0] = static_cast<float>(x[2]);
        sp.m2[ch][0] = static_cast<float>(x[3]);
        sp.m1[ch][0] = static_cast<float>(x[4]);
      }
    }
    // POLY_4 shoulder：f(1)=white_disp / f'(1)=0 / f(Sl)=sh_d / f'(Sl)=contrast / f''(Sl)=0
    {
      const double s = sh_log;
      double a[5][5] = {{1, 1, 1, 1, 1},
                        {4, 3, 2, 1, 0},
                        {s * s * s * s, s * s * s, s * s, s, 1},
                        {4 * s * s * s, 3 * s * s, 2 * s, 1, 0},
                        {12 * s * s, 6 * s, 2, 0, 0}};
      double b[5] = {white_disp, 0.0, sh_d, contrast, 0.0};
      double x[5] = {0, 0, 0, 0, 0};
      if (solve5(a, b, x)) {
        sp.m5[ch][1] = static_cast<float>(x[0]);
        sp.m4[ch][1] = static_cast<float>(x[1]);
        sp.m3[ch][1] = static_cast<float>(x[2]);
        sp.m2[ch][1] = static_cast<float>(x[3]);
        sp.m1[ch][1] = static_cast<float>(x[4]);
      }
    }
    // 中间线性段：y = (toe_d − contrast·toe_log) + contrast·x
    sp.m1[ch][2] = static_cast<float>(toe_d - contrast * toe_log);
    sp.m2[ch][2] = static_cast<float>(contrast);
    sp.m3[ch][2] = 0.0f;
    sp.m4[ch][2] = 0.0f;
    sp.m5[ch][2] = 0.0f;

    sp.lat_min[ch] = static_cast<float>(toe_log);
    sp.lat_max[ch] = static_cast<float>(sh_log);
    sp.lat_toe[ch] = static_cast<float>(kLatToe);
    sp.lat_sh[ch] = static_cast<float>(kLatSh);
  }
  sp.black_ev = static_cast<float>(black);
  sp.white_ev = static_cast<float>(white);
  sp.dynamic_range = static_cast<float>(dr);
  sp.input_min = static_cast<float>(std::max(std::pow(2.0, black) * kGrey, 1e-8));
  sp.input_max =
      static_cast<float>(std::max(std::pow(2.0, white) * kGrey,
                                  static_cast<double>(sp.input_min) + 1e-8));
  sp.output_power = static_cast<float>(power);
  sp.black_disp = static_cast<float>(black_disp);
  sp.grey_log = static_cast<float>(grey_log);
  sp.contrast = static_cast<float>(contrast);
  sp.contrast_param = contrast_param_in;
  return sp;
}

// ── U-2：编辑器链路胶片曲线（[5..10] latitude + [37][38] 黑白点；显示域）────────────
// 用户 2026-10-11 指令"不统一的要统一掉，缺的补上"（参数统一方案 §9 的最后一批：
// 补完本段后 §9.2 的 9 项缺口全部关闭，P-5 的"仅拍照/仅后期"提示不再有实例）。
//
// ── ① 为什么是"相对默认 latitude 的差值"，而不是把 filmic 重跑一遍 ──────────────
// 拍照链路的 filmic 是"**场景线性 → 显示域**"的完整映射：默认参数下线性 1.0 → 线性
// 显示域 0.718（srgb 0.83）、线性 4.35 → 1.0，中灰 0.1845 → 0.1845（不动点）。编辑器
// 的输入**已经是它的输出**（成片 sRGB）⇒ 再跑一遍 = 二次影调（整幅被再压一次、端点
// 也不再是 0/1）。用户改 `[5..10]` 的真实意图是"把这条成片按另一个 latitude 重新分布"，
// 数学上就是 **C(lat) ∘ C(lat0)⁻¹**。本实现取它的一阶等价形式：
//     **y = t + C(lat, t) − C(lat0, t)**        （差值法）
//   · t = 默认曲线时两项相消 ⇒ **逐位恒等**（零回归的 A/B 路径，配合下面的门控）；
//   · 两条曲线在 t=0 / t=1 处同值 ⇒ **端点严格不动**（判据：只改 [5..10] 时 f(0)/f(1)
//     逐位不变）；
//   · 不需要求逆（GPU 上逐像素求四阶多项式的逆不可行）—— C(lat0)⁻¹ 只在"两曲线相近"
//     时可用一阶展开替代，而实测两曲线差异下该近似的最大偏差达 5.28/255（lat=0.5，
//     见报告 §5），**不足以**替代，故直接用差值（精确）。
//
// ── ② 曲线族 C(lat, ·)：显示域、端点 0/1、中段过显示域中灰 ──────────────────────
//  · 域 = **显示域**（与 kFsStyle 的输入同域，与 kFsFilm 的 log 域不同 —— 见 §5 的口径）；
//  · 中段 = 斜率 c 的直线，且**过显示域中灰 g = srgbEncode(0.1845) = 0.46670**
//    （`li = g·(1−c)`）。中灰是**不动点**，与拍照链路 filmic 的"中灰永远映射到自己"
//    同语义（[37][38] 只改两端、不改中灰）；
//  · `toe = (1−lat_toe)·g + lat_toe·xmin`、`sh = (1−lat_sh)·g + lat_sh·xmax` ——
//    **逐字沿用** computeFilmicSpline 的 `latitude → 过渡区位置` 公式，xmin/xmax 同款
//    由"中段直线到达 kSafety 边界"解出（darktable SAFETY_MARGIN = 0.01）；
//  · toe / shoulder 段 = 同款 5×5 系统，端点约束换成显示域的 `f(0)=0` / `f(1)=1`
//    （拍照侧是 black_disp / white_disp）⇒ 曲线族在显示域 [0,1] 上闭合；
//  · **c = 1 时整条曲线族恒等于 y = x**（5 条约束被恒等函数同时满足）——这是"差值 = 0"
//    的第二条路径，A/B 自检用。
//
// ── ③ 中段斜率 c 的来源（为什么是"拍照链路同一个量"而不是自造常数）─────────────
// c = `computeFilmicSpline(默认黑白点, contrast_param = 1, lat = 默认)` 的派生
// `contrast`（≈ 1.4338，静态缓存一次）。依据两条：
//   (a) **它就是拍照链路同一条 spline 的中段斜率** —— 同一份求解代码算出来的同一个量，
//       不是新标定出来的数字（改 computeFilmicSpline 它会自动跟随）；
//   (b) 实测用它时"编辑器改 lat 的效果幅度"与"拍照链路改 lat 的成片变化"**同阶**
//       （lat = 0.1 / 0.3 时逐点最大差 ≤ 1.4/255，见 tools/check-u2-editor-curve-sat.py
//       的判据 ⑤ 与报告 §5 的对照表）。
// ⚠️ **诚实标注**：c 是 **log 域**（spline 的 e 轴）斜率，本曲线把它当**显示域**斜率用
//    —— 域不同，只保证"方向一致 + 幅度同阶"，**不宣称逐点等价**。c=1（= 曲线族退化为
//    恒等）时的幅度为 0，c 越大效果越强（实测 c=1.2/1.4338/2.0 → 22/28/41 计数单位）。
// ⚠️ **c 不含 `p.contrast`**（[4]）：见 ④。
//
// ── ④ [37][38] 的落点 = 端点重映射（分段仿射、中灰不动）────────────────────────
//  · `black_ev` 增大 = "更暗的地方算黑" ⇒ 显示域黑点 `bl` 抬高 ⇒ x < g 段被压缩到
//    [0, g]（输入 ≤ bl 全黑）；`white_ev` 减小 = "更亮的地方算白" ⇒ 白点 `wh` 下降 ⇒
//    x ≥ g 段被拉伸到 [g, 1]（输入 ≥ wh 全白）。**方向与拍照链路一致**：black_ev
//    −7.65 → −6 的成片实测暗部 −2.2 ~ −5.4/255、中灰 0/255（见 exp3 对照表）。
//  · bl/wh 用**同一套 EV 语义**映射到显示域：
//      `bl = srgbEncode(grey·2^black_ev) − srgbEncode(grey·2^black_ev_default)`（默认恒 0）
//      `wh = srgbEncode(grey·2^white_ev)`（默认恒 1.0）
//    ⇒ 默认值下 (a,b) = (1,0)，且 `1.0·x + 0.0` 在 IEEE 下逐位 == x。
//  · 防呆（信任边界，输入来自预设 / JNI）：非有限值回默认；`bl` clamp 到
//    [−0.5, g−0.05]、`wh` clamp 到 [g+0.05, 1] —— 否则分段仿射的斜率爆炸（与 darktable
//    的 SAFETY_MARGIN 同一性质）。极端设置退化为"暗部/亮部整段压死"，不会出 NaN。
//
// ── ⑤ 与既有 `[4]` contrast 的关系 = **正交；先后序 = [4] 先、本曲线后** ─────────
// `[4]` = 显示域绕 0.5 的**线性拉伸**（E2-2 既有实现，本卡**一行不动**）；本曲线 =
// "相对默认 latitude 的形状差"（非线性重分布）。两者都改对比度，但：
//   · **互不读取对方的参数** —— 本段的 c 恒取"默认黑白点 + contrast_param = 1"的派生值，
//     **不含 `p.contrast`**。若把 [4] 折进 c，"调 [4] 会连带改变胶片曲线的形状"——正是
//     规格 §3.4.3 记的"多个影调旋钮互相打架"的事故形态（历史上出过一次）；
//   · shader 里 [4] 在前（`uContrastOn` 段）、本曲线在后 ⇒ `[4]` 决定"整体拉伸多少"、
//     本曲线决定"拉伸后如何重分布"；两者各自有独立门控，关掉任一个另一个照常工作
//     （正交性实测数字见报告 §4：`[4]` ∈ {0.5, 1.0, 1.5} × lat ∈ {0.01, 0.3} 的 2×3 矩阵）。
//
// ── ⑥ 零回归（硬判据）────────────────────────────────────────────────────────
// `[5..10]` 全 0.01 且 `[37][38]` 全默认 ⇒ `on = false` ⇒ kFsStyle 整段不进 ⇒ 逐位不变。
// 门控判据用"与 `FilmParams{}` 默认值逐位比较"，默认值的唯一维护点仍是 FilmParams.h
// （不在本函数里抄常数）。
FrameRenderer::EditorCurve FrameRenderer::computeEditorCurve(const float lat_sh[3],
                                                             const float lat_toe[3],
                                                             float black_ev,
                                                             float white_ev) {
  EditorCurve ec;
  constexpr double kGrey = 0.1845;   // darktable grey_source（与 computeFilmicSpline 同）
  constexpr double kSafety = 0.01;   // darktable SAFETY_MARGIN（端点安全边距）
  const FilmParams d{};              // 默认值 = 唯一维护点（FilmParams.h）
  const double grey_disp = srgbEncodeD(kGrey);   // 显示域中灰 0.4666961…
  // ── 门控（⑥）──
  bool lat_on = false;
  for (int ch = 0; ch < 3; ++ch) {
    if (lat_sh[ch] != d.shoulder[ch] || lat_toe[ch] != d.toe[ch]) lat_on = true;
  }
  const bool bw_on =
      black_ev != d.filmic_black_ev || white_ev != d.filmic_white_ev;
  ec.on = lat_on || bw_on;
  if (!ec.on) return ec;   // 默认：全部字段保持恒等值 ⇒ shader 整段跳过（零回归）
  // ── ④ 端点重映射 ──
  const double bl_def =
      srgbEncodeD(kGrey * std::pow(2.0, static_cast<double>(d.filmic_black_ev)));
  double bl =
      srgbEncodeD(kGrey * std::pow(2.0, static_cast<double>(black_ev))) - bl_def;
  double wh = srgbEncodeD(kGrey * std::pow(2.0, static_cast<double>(white_ev)));
  if (!std::isfinite(bl)) bl = 0.0;
  if (!std::isfinite(wh)) wh = 1.0;
  bl = std::clamp(bl, -0.5, grey_disp - 0.05);
  wh = std::clamp(wh, grey_disp + 0.05, 1.0);
  // 分段仿射 t = a·x + b（两段都在中灰处取 t = g ⇒ 中灰不动；a=1/b=0 时逐位恒等）
  const double s_lo = grey_disp / (grey_disp - bl);
  const double s_hi = (1.0 - grey_disp) / (wh - grey_disp);
  ec.lo_a = static_cast<float>(s_lo);
  ec.lo_b = static_cast<float>(grey_disp * (1.0 - s_lo));
  ec.hi_a = static_cast<float>(s_hi);
  ec.hi_b = static_cast<float>(grey_disp * (1.0 - s_hi));
  // ── ③ 中段斜率（静态缓存：与参数无关，只依赖 FilmParams 的默认值与同一条 spline）──
  static const float kSlopeC = [] {
    const FilmParams dd{};
    const float lat[3] = {dd.shoulder[0], dd.shoulder[1], dd.shoulder[2]};
    return computeFilmicSpline(dd.filmic_black_ev, dd.filmic_white_ev, dd.contrast,
                               lat, lat).contrast;
  }();
  const double c = std::isfinite(kSlopeC) ? static_cast<double>(kSlopeC) : 1.0;
  const double li = grey_disp * (1.0 - c);          // 中段：y = c·t + li（过 (g, g)）
  const double xmin = (kSafety - li) / c;           // 中段直线到达 kSafety 的位置
  const double xmax = (1.0 - kSafety - li) / c;     // 中段直线到达 1−kSafety 的位置
  // ── ② 两组三段多项式（cur = 用户 lat；base = 默认 lat）──────────────────────────
  // toe 段：f(0)=0 / f'(0)=0 / f(toe)=y_toe / f'(toe)=c / f''(toe)=0
  // sh  段：f(1)=1 / f'(1)=0 / f(sh)=y_sh / f'(sh)=c / f''(sh)=0
  // ⚠ 与 computeFilmicSpline 的同一套 solve5；中间线性段 y = li + c·x。
  const auto build = [&](double lat_toe, double lat_sh, float* o_toe5, float* o_sh5,
                         float* o_lin2, float* o_at2) {
    const double lt = std::clamp(std::isfinite(lat_toe) ? lat_toe : 0.01, 0.0, 1.0);
    const double ls = std::clamp(std::isfinite(lat_sh) ? lat_sh : 0.01, 0.0, 1.0);
    const double toe = (1.0 - lt) * grey_disp + lt * xmin;
    const double sh = (1.0 - ls) * grey_disp + ls * xmax;
    const double y_toe = c * toe + li;
    const double y_sh = c * sh + li;
    {
      const double t = toe;
      double a[5][5] = {{0, 0, 0, 0, 1},
                        {0, 0, 0, 1, 0},
                        {t * t * t * t, t * t * t, t * t, t, 1},
                        {4 * t * t * t, 3 * t * t, 2 * t, 1, 0},
                        {12 * t * t, 6 * t, 2, 0, 0}};
      double b[5] = {0.0, 0.0, y_toe, c, 0.0};   // 显示域黑点端点 = 0（拍照侧是 black_disp）
      double x[5] = {0, 0, 0, 0, 0};
      if (solve5(a, b, x)) {
        for (int k = 0; k < 5; ++k) o_toe5[k] = static_cast<float>(x[4 - k]);
      }
    }
    {
      const double s = sh;
      double a[5][5] = {{1, 1, 1, 1, 1},
                        {4, 3, 2, 1, 0},
                        {s * s * s * s, s * s * s, s * s, s, 1},
                        {4 * s * s * s, 3 * s * s, 2 * s, 1, 0},
                        {12 * s * s, 6 * s, 2, 0, 0}};
      double b[5] = {1.0, 0.0, y_sh, c, 0.0};    // 显示域白点端点 = 1（拍照侧是 white_disp）
      double x[5] = {0, 0, 0, 0, 0};
      if (solve5(a, b, x)) {
        for (int k = 0; k < 5; ++k) o_sh5[k] = static_cast<float>(x[4 - k]);
      }
    }
    o_lin2[0] = static_cast<float>(li);
    o_lin2[1] = static_cast<float>(c);
    o_at2[0] = static_cast<float>(toe);
    o_at2[1] = static_cast<float>(sh);
  };
  for (int ch = 0; ch < 3; ++ch) {
    // clamp 到 [0,1]（规格 §3.4.4）；非有限值回退 darktable 默认 0.01 —— 输入来自
    // 预设 / JNI，属信任边界（与 computeFilmicSpline 的逐通道防呆同款）。
    build(lat_toe[ch], lat_sh[ch], ec.toe[ch], ec.sh[ch], ec.lin[ch], ec.at[ch]);
    build(d.toe[ch], d.shoulder[ch], ec.b_toe[ch], ec.b_sh[ch], ec.b_lin[ch],
          ec.b_at[ch]);
  }
  return ec;
}

// ── 自动 EV 标定（v0.7.3）────────────────────────────────────────────────────
// 自适应对象从"亮度"换成"动态范围"：black/white relative exposure 由场景分位算出，
// 中灰锚点固定 18.45%。于是**不同场景输出不同亮度**（暗的地方暗），而压 EV / 降 AE
// 曝光能真的传导到成片 —— 旧的"中位锚定"会把它们补偿掉（v0.7.2 的死结）。
// auto_on=false：用 darktable 默认（−7.65/+4.56），曲线完全固定、不随场景变。
void FrameRenderer::updateFilmicEV(GLuint src, bool still, bool auto_on,
                                  float user_black, float user_white,
                                  float white_hl, float white_floor,
                                  float white_probe, float user_ev,
                                  float user_contrast,
                                  const float lat_sh[3], const float lat_toe[3]) {
  constexpr float kGrey = 0.1845f;
  // ── v0.7.6：黑白点由**用户配置**（FilmParams[37]/[38]），PC 的做法 ────────────
  // 默认 −7.65/+4.56 = darktable 默认值 = PhotonCamera 固化值（两边一致，有出处）；
  // 单位是"相对中灰 18.45% 的 EV"，物理量 ⇒ 换机型/换曲线都成立。
  //   black_ev = "多暗算黑"（曲线的 toe 端点，不是硬截断）；white_ev = "多亮算白"。
  // auto_tone 开时**黑点**改由场景分位自动（白点仍用用户值）；**默认关** = 曲线固定、
  // 曝光交给 AE。为什么不默认自动：实测自动黑点（P0.5 分位）比 darktable 默认高 2EV
  // （−5.6 vs −7.65）⇒ 等于把每个场景最暗的 0.5% 都钉在显示黑 ⇒ 暗部压死、画面更暗，
  // 与已被否掉的 kTarget 中位锚定同型（抹掉"这个场景暗部有多深"这一维）。
  float b = user_black;
  float w = user_white;
  float w99 = 0.0f, w999 = 0.0f, cp = 1.0f;
  const SceneStats st = measureSceneStats(src);  // 开关两边都统计（日志不留盲区）

  // v0.11.59：曝光判据（sw999）与 auto_tone **解耦**。高光保护读的就是它，而默认
  // [35]=0 ⇒ 此前恒 0 ⇒ sceneHighlightEv()=NaN ⇒ ExposurePlanner 永远不做高光约束，
  // 采集侧一直用 HAL AE 的曝光。这里只在 still 链写，预览域不碰（两者不是一个尺度）。
  if (still && st.valid) {
    last_still_w999_ = std::log2(std::max(st.p_hi999, 1e-6f) / kGrey);
  }
  if (st.valid && auto_on) {
    // v0.10.16/18：黑白点**成对**自动 = darktable autodetect 的 autotune 语义
    // （apply_auto_black / apply_auto_white_point_source），取样统计量 = P0.1 / P99
    // （max 通道）。v0.10.17 真机教训：分位必须钳在**物理量**上，否则自动化自毁——
    //   ① black：暗光噪声把 P0.1 拉到噪声底（预览 blackEv 撞 −16 clamp）→ 下限钉
    //      darktable 固定默认 −7.65（场景没有比默认更暗的东西时用默认）；
    //   ② white：无饱和场景（暗光室内）P99 = 场景最亮 0.5 → white_ev 1.44 →
    //      dr 7.75 → power 被派生公式推到 8.2（中间调对比爆炸）→ 下限钉 **RAW 饱和
    //      数据白点** 2.44EV（= log2(1.0/0.1845)，数据天花板）；上限 darktable 默认
    //      4.56（数据 max = 1.0 → P99 恒 ≤ 2.44，上限是防御）。
    //   结果：暗光 ≈ black −7.65 / white 2.44（dr 10.1 → power ≈ 4.2，darktable
    //   autodetect 的正常区间）；白天 black 随场景收紧（−4.5~−6）。
    b = std::clamp(std::log2(std::max(st.p_lo, 1e-6f) / kGrey), -16.0f, -1.0f);
    b = std::max(b, -7.654144f);
    // ── v0.10.27：白点高光感知（"高光太白压不住"的修正）──────────────────────
    // 真机定案（151748）：显示域统计 clamp 使 P99 撞顶恒 1.0 → whiteEv 恒钉 2.44
    // 下限 → dr 只剩 black−2.44（室内 black −5.63 时 dr 8.07、power 4.70）→ 肩部
    // 只覆盖线性 0.19→1.04（2.5EV）→ +1.5EV→209 / +2.0EV→242 / +2.5EV→255 顶格，
    // 高光块(245+) 7.99%、最大连片 7.49% 全图。占帧 <1% 的窗/灯强光源 P99 分位
    // 看不见（这正是当年选 P99 而非 P99.5 的理由），但对白点它是真实存在的内容。
    // 修正 = P99.9 驱动上探（对数统计后才有分辨率），两道闸防失控：
    //   ① 上探幅度 ≤ +1.5EV（P99 与 P99.9 之间常夹着噪声尾/孤立高光，无限上探会把
    //      power 压垮、画面变灰）；
    //   ② 物理钳制 [2.438, 4.556] 不变（下限防 power 爆炸、上限 = darktable 默认）。
    // 真暗光（无强光源）P99.9 ≈ P99 → 行为与 v0.10.18 逐位一致。
    // ── v0.10.28：上探主驱动改 **max 语义**（darktable autodetect 原版）────────
    // 178 真机（162012 白壶）：P99.9 也看不见 0.002% 级的近饱和高光——渲染域最亮
    // 1.02（LSC 1.2× 把 0.85 抬过数据白点）但 w999 只见 2.05，白点不上探，4.1% 的
    // 白壶身渐变被肩部压进 245+ 平台（"水壶压不住"）。darktable autodetect 的
    // white 本义 = **取样域最大像素 + security**——对数统计（上限 2^8）+ 147K 采样
    // 点后 max 语义可以忠实落地：w_max = log2(p_max/grey)，+0.4EV security；
    // w99+1.5 幅度闸与物理钳制不变。max 被 P0 降噪/MFNR 融合先行压掉坏点，
    // 噪声尖峰由幅度闸与上限兜底。
    w99 = std::log2(std::max(st.p_hi, 1e-6f) / kGrey);
    w999 = std::log2(std::max(st.p_hi999, 1e-6f) / kGrey);
    // last_still_w999_ 已在 auto_on 之外无条件写入（v0.11.59）
    const float w_max = std::log2(std::max(st.p_max, 1e-6f) / kGrey);
    if (white_hl > 0.5f) {
      // v0.10.111：上探幅度由 white_probe 控制（旧硬编码 1.5）。真机实测该闸每次都
      // 满额（红杯 w99=1.03 → 白点 2.53），把 1% 以上的高光整段推到肩部以下 ⇒ 白斑。
      const float probe = (white_probe > 0.0f) ? white_probe : 1.5f;
      w = std::max(w99, std::min(w_max + 0.4f, w99 + probe));
    } else {
      w = w99;  // v0.10.18 行为（A/B 通路）
    }
    // ── v0.10.31：white 判据整体 +ev（"整张白掉"的根治）─────────────────────
    // 错位实锤（172002 阳台，22.3% 像素 ≥245 全白）：统计域**不含** base_ev/用户
    // EV（src_tex 测于 kFsFilm 链首乘 uExposure 之前），而 filmic 渲染输入**含**
    // （×2^ev）——enc 公式里曲线白点 w 对准的是**渲染域** EV，而自适应判据从
    // **未乘域**统计 ⇒ 白点比渲染域 P99 低 ev、**余量为负**（ev=0.62 时渲染域
    // P99 就超白点），场景里 +1.8EV 以上的内容（边缘白墙经 LSC 3~5× 校正后普遍
    // 在 +2.5~3EV）全部顶白——**与白色判据版本无关**（178→182 五版皆白的原因）。
    // RAW 域满井饱和仅 0.001%，白是渲染域的产物，不是采集过曝。
    // 正解 = 判据结果整体 +ev：内层自适应/钳制语义（[2.438, 4.556]，防 power 爆炸、
    // darktable 默认上限）作用于未乘域，结果平移到渲染域。
    // black 不需要动：b 取自未乘域分位，enc 公式吃乘后输入，两处错位**恰好**等效
    // 于黑点对准渲染域 P0.1（暗部锚定本来就是对的）；dr 因此 +ev（略降 power）。
    // 自检：ev=0 → 与 179 逐位一致；auto=0（固定曲线）不走本分支。
    const float ev_shift = user_ev;
    // ── v0.10.111：白点下限的场景自适应（治"密集高光点变白斑"）───────────────
    // 旧下限恒为 RAW 饱和数据白点 2.438（v0.10.17 为暗光室内防 power 爆炸而钉）。
    // 但"高光点密集、面积很小"的场景（釉面/白瓷）P99 只有 ~1.0EV，白点被钉在
    // 2.44~2.53 ⇒ 比场景高光高 1.4~1.5EV ⇒ 1% 以上的高光全落在肩部**以下**、根本
    // 不被压缩 ⇒ 密集高光点一路白上去（2026-10-03 真机红杯实测）。
    // 修法：下限改为 min(物理饱和点, w99 + 边距)，边距可配（FilmParams[43]，0 = 旧行为）。
    // 中间调对比由下方已有的 cp 补偿兜住（当年"power 爆炸"的直接后果就是中间调对比，
    // 而 cp 正是为此引入）。
    const float kWhiteSat = 2.438307f;
    const float w_floor = (white_floor > 0.0f)
        ? std::min(kWhiteSat, w99 + white_floor)
        : kWhiteSat;
    w = std::clamp(w, w_floor, 4.556152f) + ev_shift;
    // ── contrast_param 补偿（darktable contrast 滑杆的自动化用法）────────────
    // 上探拉大 dr → power 派生回落（151748 型场景 4.70→~3.2），中灰以上提亮变少
    // = 用户验收过的"通透感"回退。补偿 = 上探前后 power 比（cp = power_old/new，
    // clamp [1.0, 1.6] 只补不降）：spline v3 里中灰斜率 = contrast_param/8 与
    // power 无关，cp 直接抬对数域斜率，把上探前曲线的中间调梯度找回来；肩部
    // 过渡宽度由 dr 决定不受 cp 影响 —— 高光余量与中间调对比同时保住。
    // 未上探（w == w_old）时 cp 恒 1.0，固定曲线（auto=0）不走此分支恒 1.0。
    const float w_old = std::clamp(w99, w_floor, 4.556152f) + ev_shift;
    if (w > w_old + 1e-3f) {
      auto derive_power = [&](float wv) {
        const float bl = std::min(b, wv - 0.2f);
        const float wh = std::max(wv, bl + 0.2f);
        return std::log(kGrey) / std::log(-static_cast<double>(bl) /
                                         static_cast<double>(wh - bl));
      };
      const double pow_old = derive_power(w_old);
      const double pow_new = derive_power(w);
      cp = static_cast<float>(std::clamp(pow_old / std::max(pow_new, 1e-6),
                                         1.0, 1.6));
    }
  }
  // EMA 平滑防闪；**静拍与首帧直通** —— 静拍只有这一帧可渲染，用历史值等于用错曝光
  //（v0.6.28 实测：静拍拿到取景早期的 ag=3.042 而正确值 ≈1.5，福字被推成粉白）。
  if (still) {
    ev_black_ = b;
    ev_white_ = w;  // 不写 EMA 状态，避免污染预览
  } else if (ev_smooth_black_ == 0.0f) {
    ev_smooth_black_ = b;
    ev_smooth_white_ = w;
    ev_black_ = b;
    ev_white_ = w;
  } else {
    ev_smooth_black_ = ev_smooth_black_ * 0.7f + b * 0.3f;
    ev_smooth_white_ = ev_smooth_white_ * 0.7f + w * 0.3f;
    ev_black_ = ev_smooth_black_;
    ev_white_ = ev_smooth_white_;
  }
  // ── U-3（[4] contrast）：用户的对比度与内部补偿 `cp` **相乘**，不是覆盖 ────────
  // 两个来源语义正交，先分清（这是本卡最容易做错的一处）：
  //   · `cp`（本函数内算，初值 1.0）= **引擎内部补偿**：自动白点上探把 dr 拉大 ⇒
  //     power 派生回落 ⇒ 中灰以上提亮变少（"通透感"回退）。cp = 上探前后 power 比，
  //     clamp [1.0, 1.6]，**只补不降**；未上探 / 固定曲线（auto=0）时恒 1.0。
  //   · `user_contrast`（= FilmParams[4]）= **用户/预设的风格参数**，规格量程
  //     0.5..1.5，1.0 = 恒等。
  // 相乘的依据：darktable 的 contrast 滑杆在 spline v3 里只改一处 —— 线性段斜率
  // `slope = contrast_param · dr / 8`（`computeFilmicSpline` 内）。cp 与 [4] 作用的
  // 是**同一个物理量**（同一处 log 域斜率）⇒ 相乘 = 两者叠加，覆盖则会让自动上探的
  // 回退补偿被用户值吃掉（"高光压不住 + 中灰变灰"同时出现）。端点（[37][38] 与
  // toe/shoulder 位置）不由 contrast 决定 ⇒ 正交性见交付报告。
  // 零回归（硬判据，逐位）：默认 [4] = 1.0f ⇒ `cp * 1.0f` 在 IEEE 754 下逐位 == cp
  //（cp 的取值由上面的 clamp 与初值限定在 [1.0, 1.6]，是有限规格化数）⇒ 5×5 系统的
  // 右端项逐位不变 ⇒ 全部系数逐位不变（离线逐位对拍见 check-u3-camera-contrast-wb.py）。
  // 防呆（信任边界，与本函数内 latitude 的纪律同款）：非有限值或非正值 → 恒等 1.0。
  // NaN 会让 5×5 系统的右端项全 NaN ⇒ 整条 filmic 链输出 NaN（X300 同型事故）；
  // 非正值在 FilmParams::fromDoubles 已回退，此处覆盖预设/直连 JNI 的旁路。
  // ⚠ 不在此处加量程 clamp：编辑器链路 passStyle 也不 clamp（`u_style_.contrast` 直接
  // 下发），两侧语义必须一致；越界值的兜底是 computeFilmicSpline 内的 contrast clamp。
  const float contrast_user =
      (std::isfinite(user_contrast) && user_contrast > 0.0f) ? user_contrast : 1.0f;
  filmic_ = computeFilmicSpline(ev_black_, ev_white_, cp * contrast_user, lat_sh, lat_toe);
  last_w99_ = w99;
  last_w999_ = w999;
  last_cp_ = cp;
  last_wmax_ = st.valid && auto_on ? std::log2(std::max(st.p_max, 1e-6f) / kGrey)
                                   : 0.0f;
}

void FrameRenderer::passFill(int dst_w, int dst_h) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_scene_);
  glViewport(0, 0, dst_w, dst_h);
  glUseProgram(prog_fill_);
  glUniform1i(u_fill_.rot, 0);
  glUniform2f(u_fill_.scale, 1.0f, 1.0f);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

void FrameRenderer::passFilm(int w, int h, const FilmParams& p, GLuint src_tex,
                             bool still, bool hal_domain) {
  // ── 引擎插槽（阶段 2）：`[98] engine_slot` 的选路 ────────────────────────────
  // `init()` 只能编译引擎 0（那时拿不到 FilmParams，见 init() 内注释）⇒ 这里按请求的
  // slot **一次性**换 program（`buildFilmProgram`，不是每帧重编译：只在 slot 与已编译
  // 的 slot 不同时触发）。三种情况：
  //   · 请求 slot == 已编译 slot（含恒定的 0）⇒ 只比一个 int，零开销（绝大多数帧）；
  //   · 请求 slot 未注册 ⇒ 契约 5：明确降级到引擎 0 + 记 WARN（不静默、不崩、不伪装）；
  //   · 请求 slot 已注册但编译/链接失败 ⇒ 保持原 program（= 引擎 0 继续出图）+ 记
  //     kError，并记住该失败 slot **不重试**（否则每帧重编译一个坏 shader = 每帧一次
  //     35KB 编译 + 日志刷屏）。请求的 slot 变化时才重新尝试。
  const int engine_slot = static_cast<int>(std::lround(p.engine_slot));
  if (engine_slot != film_engine_slot_) {
    if (findEngine(engine_slot) == nullptr) {
      warnEngineSlotUnavailable(engine_slot);  // 未注册：降级到已编译的引擎 0
    } else if (engine_slot != film_build_failed_slot_) {
      if (buildFilmProgram(engine_slot)) {
        film_build_failed_slot_ = -1;
      } else {
        film_build_failed_slot_ = engine_slot;  // 失败不重试，等 slot 变化
        Logger::instance().log(
            LogLevel::kError, "engine", "engine.slot_build_failed",
            "{\"requested\":" + std::to_string(engine_slot) + ",\"fallback\":" +
                std::to_string(film_engine_slot_) + "}");
      }
    }
  }
  // filmic 的自动 EV 标定（v0.7.3）：**刻意放在 passFilm 内** —— 预览 / YUV 静拍 /
  // RAW 静拍三条路径都经过这里，一处实现即全覆盖。
  // 开关语义（v0.7.6）：黑白点来自用户配置（FilmParams[37]/[38]，默认 −7.65/+4.56）；
  // auto_tone 开 = **黑点**改由场景分位自动（白点仍用配置值），默认关。
  // 注意：updateFilmicEV 内部会切 FBO/viewport（直方图统计），必须先于下面的绑定调用。
  // E4-4：[5..7]=shoulder latitude（R/G/B）、[8..10]=toe latitude（R/G/B）透传给
  // CPU 侧 spline 求解（per-channel）。默认全 0.01 ⇒ 与接线前逐位一致。
  // U-3：[4] contrast 一并透传（与内部补偿 cp **相乘**，见 updateFilmicEV 内注释）；
  // 默认 1.0 ⇒ 逐位零回归。
  updateFilmicEV(src_tex, still, p.auto_tone > 0.5f, p.filmic_black_ev,
                 p.filmic_white_ev, p.white_hl, p.white_floor, p.white_probe,
                 p.exposure_ev, p.contrast, p.shoulder, p.toe);
  // ── v0.10.15/18：darktable filmic 高光重建（HR），仅成片路径 ─────────────
  // 裁剪阈值恒 = RAW 饱和数据白点 1.0（物理量，与 filmic 白点解耦——0.10.17 的
  // 阈值随 white 自动掉到 0.5 曾把 47% 的块送进重建）。重建结果直接替换 filmic
  // 的输入（darktable: in = reconstructed）。blocks == 0（无裁剪像素）或分配
  // 失败时返回原纹理。
  hr_blocks_ = -1;
  hr_ms_ = 0.0f;
  hr_white_ev_ = 0.0f;
  hr_stat_raw_ = -1;
  GLuint film_src = src_tex;
  if (still && p.hr_wavelet > 0.5f) {
    // HR 输入域 = 曝光前（kFsFilm 内部才乘 2^exposure_ev）⇒ 白点也要去掉 ev 平移
    // 才是同一域的量（ev_white_ 是 updateFilmicEV 刚算出的、含 ev_shift 的白点）。
    const float white_ev_in = ev_white_ - p.exposure_ev;
    hr_white_ev_ = white_ev_in;
    film_src = runHighlightReconstruct(src_tex, w, h, white_ev_in, &hr_blocks_, &hr_ms_);
  }
  // 诊断统计：filmic 的**实际输入**（HR 重建输出；HR 关闭/失败时 = src_tex）。
  // 只在成片链内触发（g_diag_chain），预览帧与 YUV 静拍帧不跑统计。
  diagStatsTex("HR-filmsrc", film_src, w, h, &p);
  // 预览域映射的在线标定（v0.8.0）：updateFilmicEV 刚把 last_lin_mid_ 更新为本帧
  // 统计中位 —— 预览帧 = HAL OES 域，静拍帧 = RAW/YUV 链域。同一场景两类帧交替
  // 到达，用 EMA 各记一侧，静拍时对出比值：
  //   ratio = 预览中位 / RAW 中位（v0.6.48 实测范围 1.70–3.87，EMA 平滑 AE/构图抖动）
  // 预览除以它回到 RAW 等效域（shader 的 uHalDomainMap 分支），filmic 曲线因此与
  // 成片共用（WYSIWYG）。首拍前 ratio 用初值 2.0（实测范围中位）兜底。
  // ── 第 6 期（S12，完整修复）──────────────────────────────────────────
  // 判据从 `still` 改为 `!hal_domain`：域比是"HAL 域 / RAW 域"之比，**只有 RAW 静拍帧**
  // 才在 RAW 域。YUV 静拍帧同为 HAL 域，若参与标定会把比值拉向 1，污染的是**预览**的
  // 域映射（影响面比 S12 原文写的"静拍亮度漂移"更大）。`still` 在此处本就是错用的
  // 语义 —— 它在 HR 小波（:8457）与日志节流（:8558）处的含义才是"是否静拍"。
  if (!hal_domain) {
    if (g_pv_mid_ema > 1e-4f && last_lin_mid_ > 1e-6f) {
      const float r = std::clamp(g_pv_mid_ema / last_lin_mid_, 1.2f, 4.5f);
      g_domain_ratio = g_domain_ratio * 0.7f + r * 0.3f;
    }
  } else {
    g_pv_mid_ema = g_pv_mid_ema > 1e-4f ? g_pv_mid_ema * 0.7f + last_lin_mid_ * 0.3f
                                        : last_lin_mid_;
    if (last_lin_geo_ > 1e-5f) {
      g_pv_geo_ema = g_pv_geo_ema > 1e-5f
                         ? g_pv_geo_ema * 0.7f + last_lin_geo_ * 0.3f
                         : last_lin_geo_;
    }
  }
  // M4-7 局部色调映射：低频亮度图，与直方图同源（kFsLocal 的 8×8 盒式平均）。
  // 必须与 updateAutoGain 一样先于 fbo_work_ 绑定（renderLuma 会切 FBO/viewport）。
  // 强度 ≤ 0 时不重渲染，但仍绑**已存在的** tex_local_ 并下发 0 —— sampler 必须有
  // 合法绑定（v0.6.0-m4 全黑事故），shader 侧靠 uLocalStrength > 0 短路。
  // v0.6.34：低频图尺寸**随渲染分辨率**（每格目标 ≈128px ≈ 3% 画面宽），不再固定
  // 32×24 —— 固定尺寸只在成片（4080×3072）成立，预览（1216×1621）每格仅 38×67px、
  // 等效提亮半径缩到 1/3，于是预览与成片的局部提亮分布对不上
  //（真机反馈"预览和成片差很多"）。kFsLocal 的采样跨度取 uStep=1/输出尺寸，
  // 所以采样密度不随格子数变化。
  float local = (p.local_tone > 0.0f && src_tex != 0) ? p.local_tone : 0.0f;
  const int lw = std::clamp(w / 128, 4, 64);
  const int lh = std::clamp(h / 128, 4, 64);
  float local_ref = 0.5f;  // 局部图未生成时的兜底基准（中灰）
  if (local > 0.0f && !ensureLocalTarget(lw, lh)) local = 0.0f;  // 建不出就退回关闭
  if (local > 0.0f) {
    renderLuma(src_tex, fbo_local_, local_w_, local_h_, 0.0f, /*box_filter=*/true);
    // 读回小图取中位亮度当"画面基准"—— 判据必须相对它算，绝对阈值在普遍欠曝的
    // 实拍场景里会退化成全局提亮（见 shader 注释）。
    std::vector<uint8_t> px(static_cast<size_t>(local_w_) * local_h_ * 4);
    glReadPixels(0, 0, local_w_, local_h_, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
    std::vector<uint8_t> lum;
    lum.reserve(static_cast<size_t>(local_w_) * local_h_);
    for (size_t i = 0; i + 3 < px.size(); i += 4) lum.push_back(px[i]);
    if (!lum.empty()) {
      const size_t mid = lum.size() / 2;
      std::nth_element(lum.begin(), lum.begin() + mid, lum.end());
      local_ref = std::max(lum[mid] / 255.0f, 1e-3f);
    }
  }
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_work_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_film_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, film_src);  // v0.10.16：HR 重建结果替换 filmic 输入
  glUniform1i(u_film_.tex, 0);
  // filmic：spline 系数 + EV 标定（CPU 侧算好，见 computeFilmicSpline）
  // E4-4：per-channel —— 每个 uniform 的 .r/.g/.b = R/G/B 三层乳剂（[通道] 是第二维，
  // 见 FilmicSpline 注释）。**逐通道手写**（GPU 三禁），不用 glUniform3fv 传数组。
  glUniform3f(u_film_.filmic_toe_m1, filmic_.m1[0][0], filmic_.m1[1][0], filmic_.m1[2][0]);
  glUniform3f(u_film_.filmic_toe_m2, filmic_.m2[0][0], filmic_.m2[1][0], filmic_.m2[2][0]);
  glUniform3f(u_film_.filmic_toe_m3, filmic_.m3[0][0], filmic_.m3[1][0], filmic_.m3[2][0]);
  glUniform3f(u_film_.filmic_toe_m4, filmic_.m4[0][0], filmic_.m4[1][0], filmic_.m4[2][0]);
  glUniform3f(u_film_.filmic_toe_m5, filmic_.m5[0][0], filmic_.m5[1][0], filmic_.m5[2][0]);
  glUniform3f(u_film_.filmic_sh_m1, filmic_.m1[0][1], filmic_.m1[1][1], filmic_.m1[2][1]);
  glUniform3f(u_film_.filmic_sh_m2, filmic_.m2[0][1], filmic_.m2[1][1], filmic_.m2[2][1]);
  glUniform3f(u_film_.filmic_sh_m3, filmic_.m3[0][1], filmic_.m3[1][1], filmic_.m3[2][1]);
  glUniform3f(u_film_.filmic_sh_m4, filmic_.m4[0][1], filmic_.m4[1][1], filmic_.m4[2][1]);
  glUniform3f(u_film_.filmic_sh_m5, filmic_.m5[0][1], filmic_.m5[1][1], filmic_.m5[2][1]);
  glUniform3f(u_film_.filmic_lin_m1, filmic_.m1[0][2], filmic_.m1[1][2], filmic_.m1[2][2]);
  glUniform3f(u_film_.filmic_lin_m2, filmic_.m2[0][2], filmic_.m2[1][2], filmic_.m2[2][2]);
  glUniform3f(u_film_.filmic_lat_min, filmic_.lat_min[0], filmic_.lat_min[1],
              filmic_.lat_min[2]);
  glUniform3f(u_film_.filmic_lat_max, filmic_.lat_max[0], filmic_.lat_max[1],
              filmic_.lat_max[2]);
  glUniform1f(u_film_.filmic_black_ev, filmic_.black_ev);
  glUniform1f(u_film_.filmic_dr, filmic_.dynamic_range);
  glUniform1f(u_film_.filmic_input_min, filmic_.input_min);
  glUniform1f(u_film_.filmic_input_max, filmic_.input_max);
  glUniform1f(u_film_.filmic_power, filmic_.output_power);
  glUniform1f(u_film_.filmic_black_disp, filmic_.black_disp);
  // ── 引擎 1（sigmoid，阶段 2）：7 个标量**无条件下发** ─────────────────────────
  // 与上面 filmic 段同款：CPU 侧算好（`computeSigmoidParams`，init() 期一次），这里只
  // 做 7 次 glUniform1f。**不按 slot 分支**：引擎 0 的 program 里这 7 个 location = −1，
  // GL 规范下 `glUniform*(−1, …)` 是安全 no-op ⇒ 零分支、零风险（也避免"某天有人把
  // 分支写反 ⇒ 引擎 1 拿到旧值"的静默失效）。
  glUniform1f(u_film_.sig_white_target, sigmoid_.white_target);
  glUniform1f(u_film_.sig_black_target, sigmoid_.black_target);
  glUniform1f(u_film_.sig_paper_exposure, sigmoid_.paper_exposure);
  glUniform1f(u_film_.sig_film_fog, sigmoid_.film_fog);
  glUniform1f(u_film_.sig_film_power, sigmoid_.film_power);
  glUniform1f(u_film_.sig_paper_power, sigmoid_.paper_power);
  glUniform1f(u_film_.sig_hue_preservation, sigmoid_.hue_preservation);
  // v0.10.13：darktable v5 gamut mapping 开关（1 = 标准，0 = A/B 关）
  glUniform1f(u_film_.gamut, p.gamut);
  // 预览域映射（v0.8.0）：预览先 /uDomainRatio 回 RAW 等效域再走 filmic；静拍=1。
  // 第 6 期（S12）：域标志**独立于 still** —— YUV 静拍链的输入也是 HAL 显示域，
  // 旧写法用 `still` 兼任域标志，把它当场景线性域送进 filmic。
  glUniform1f(u_film_.hal_domain, hal_domain ? 1.0f : 0.0f);
  glUniform1f(u_film_.domain_ratio, g_domain_ratio);
  // 用户 EV 滑杆 = 显示亮度补偿（v0.7.8 影调链之后乘；预览在域映射后同乘）
  glUniform1f(u_film_.exposure, std::exp2(p.exposure_ev));
  glUniform3f(u_film_.sat, p.sat_shadow, p.sat_mid, p.sat_high);
  glUniform2f(u_film_.split, p.split_warm, p.split_cool);
  glUniform1f(u_film_.mono, p.monochrome);
  glUniform1f(u_film_.tone_k, p.tone_strength);
  glUniform1f(u_film_.look, p.look_strength);
  glUniform1f(u_film_.look_sat, p.look_sat);
  glUniform1f(u_film_.look_chroma, p.look_chroma);
  glUniform1f(u_film_.local_strength, local);
  glUniform1f(u_film_.local_knee, kLocalKnee);
  glUniform1f(u_film_.local_knee_hi, kLocalKneeHi);
  glUniform1f(u_film_.local_ref, local_ref);
  // 手写双线性的尺寸必须与实际绑定的低频图一致（v0.6.34：尺寸随渲染分辨率）
  glUniform2f(u_film_.local_size, static_cast<float>(local_w_),
              static_cast<float>(local_h_));
  glActiveTexture(GL_TEXTURE2);
  glBindTexture(GL_TEXTURE_2D, tex_local_);
  glUniform1i(u_film_.local, 2);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  // 预览统计观测（排查"预览过曝 vs 成片正常"）：每 120 帧一条，与 raw.render 的
  // blackEv/whiteEv/linMid 直接对比 —— 若同场景下预览的 linMid 与成片明显不一致，
  // 说明 OES 预览流与 RAW/YUV 链的线性亮度标定不同（两条源的判据不同）。
  if (!still && ++ag_log_tick_ >= 120) {
    ag_log_tick_ = 0;
    Logger::instance().log(
        LogLevel::kInfo, "engine", "preview.filmic",
        "{\"blackEv\":" + jnum(ev_black_, 9) +
            ",\"whiteEv\":" + jnum(ev_white_, 9) +
            ",\"w99\":" + jnum(last_w99_, 6) +
            ",\"w999\":" + jnum(last_w999_, 6) +
            ",\"wmax\":" + jnum(last_wmax_, 6) +
            ",\"cp\":" + jnum(last_cp_, 6) +
            ",\"linMid\":" + jnum(last_lin_mid_, 6) +
            ",\"ratio\":" + jnum(g_domain_ratio, 6) +
            ",\"pLo\":" + jnum(last_p_lo_, 7) +
            ",\"pHi\":" + jnum(last_p_hi_, 7) +
            ",\"localRef\":" + jnum(local_ref, 5) +
            ",\"w\":" + std::to_string(w) + ",\"h\":" + std::to_string(h) + "}");
  }
}


// E4-3：相纸响应 LUT（darktable grain.c `evaluate_grain_lut`；数学在 GrainLut.h）。
// 为什么在 CPU 侧算：[60]/[61] 变化时才重算 128×128 = 16384 次 exp/log（亚毫秒级）；
// 放进 shader 就是 12.5M px × 2 次 exp/log 的纯浪费（darktable 同样是 CPU 侧 LUT）。
// 纹理 = 128×128 **R16F**（LUT 值域 ±100，half 的相对精度 2^-11 远优于 LUT 自身
// 1/127 的采样分辨率），用 **glTexStorage2D**（不可变存储 —— v0.10.50 真机定案：
// Mali 上 glTexImage2D 创建的纹理在部分路径上写入全丢）。
// 过滤 = NEAREST：shader 侧手写双线性（texelFetch），不依赖 16F 的硬件 LINEAR
//（X300/Mali 实测无线性过滤，项目多处手写，见 makeTex / kFsRcdChroma 注释）。
// 布局 = x 轴噪声轴（gu ∈ ±0.5）、y 轴亮度轴（l ∈ [0,1]），与 darktable 的
// grain_lut[j*SIZE + i] 一致。
void FrameRenderer::ensureGrainLut(float mb, float pivot) {
  if (tex_grain_lut_ != 0 && grain_lut_mb_ == mb && grain_lut_pivot_ == pivot) return;
  float lut[grain::kLutSize * grain::kLutSize];
  grain::buildGrainLut(lut, mb, pivot);
  std::vector<uint16_t> px(static_cast<size_t>(grain::kLutSize) * grain::kLutSize);
  for (size_t k = 0; k < px.size(); ++k) px[k] = grain::floatToHalf(lut[k]);
  // ⚠ **必须显式切到纹理单元 2**：glBindTexture / glTexStorage2D / glTexSubImage2D 全部
  // 作用于**当前 active 单元**，而本函数是在调用方的 `glActiveTexture(GL_TEXTURE0)` +
  // 绑定输入帧（uTex）**之后**被调用的 —— 不切单元就会把单元 0 的输入帧纹理改绑成
  // grain LUT ⇒ uTex 采样到 LUT 内容、整帧画面错乱。只在首帧与 [60]/[61] 变化时发生，
  // 属于最难查的间歇性错误（离线验收走 Python 侧纹理绑定，覆盖不到这条路径）。
  glActiveTexture(GL_TEXTURE2);
  if (tex_grain_lut_ == 0) {
    glGenTextures(1, &tex_grain_lut_);
    glBindTexture(GL_TEXTURE_2D, tex_grain_lut_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_R16F, grain::kLutSize, grain::kLutSize);
  } else {
    glBindTexture(GL_TEXTURE_2D, tex_grain_lut_);
  }
  glPixelStorei(GL_UNPACK_ALIGNMENT, 2);
  glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, grain::kLutSize, grain::kLutSize, GL_RED,
                  GL_HALF_FLOAT, px.data());
  glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
  grain_lut_mb_ = mb;
  grain_lut_pivot_ = pivot;
}

// ══ E4-5 halation（光晕）═════════════════════════════════════════════════════
// 参考实现 = jie60517/emulsion `src/render/effects/library/halation.ts`（唯一有
// **通道扩散**的公开实现；darktable `bloom.c` 是 Orton 效应、不含通道扩散，仅对照）。
// 物理、逐行对照、落点、参数映射见 docs/reports/editor-E4-film-params-DESIGN.md §3.2。
//
// 乳剂的逐通道扩散比（emulsion 同名常量 CHANNEL_SPREAD）。**硬编码不给槽位** ——
// 这是乳剂的物理常数（红光在片基里散射最远、蓝光最近），正是"白→橙→红"径向渐变的
// 成因；做成滑杆反而会让用户把它调成均匀红雾（emulsion 原注释同义）。
static constexpr float kHaloSpread[3] = {1.0f, 0.6f, 0.32f};
static constexpr float kHaloSigma = 0.95f;  // emulsion SPREAD_SIGMA（级间高斯宽度）
static constexpr int kHaloLevels = 5;       // emulsion LEVELS（1/2 … 1/32）

// CPU 侧 sRGB 解码（IEC 61966-2-1，与 shader 的 srgbDecode 逐字同式）：[63] 的单位是
// **显示域** sRGB 编码值（用户直觉"多亮算高光"），进 shader 前换算到线性显示域。
// 用真分段曲线，不用 pow(2.2) 近似（暗部往返误差实测可达 9/255，见 kFsOut 注释）。
static float srgbToLinear1(float c) {
  c = std::clamp(c, 0.0f, 1.0f);
  return c <= 0.04045f ? c / 12.92f : std::pow((c + 0.055f) / 1.055f, 2.4f);
}

// emulsion `haloWeights`：逐通道高斯，峰值落在 level = radius01·(LEVELS−1)·SPREAD[c]。
// ⇒ **红通道峰值在更高级（更大半径）、蓝通道在更低级** ⇒ 同一个点光源在三个通道上
// 得到三个不同的扩散半径 ⇒ "白（核心）→ 橙（中环，R+G）→ 红（外环，仅 R）"。
// 每通道**独立归一化**（权重和为 1）⇒ 半径只改"分布形状"，不改总能量。
//
// ── E4-5b（R1）：为什么需要 `level_shift` ────────────────────────────────────
// 金字塔各级是**相对尺寸**（`w >> (i+1)`），但 blur 的 σ = 1.637 **texel @ 该级**
// ⇒ 等效全分辨率 σ_px = `1.637·2^(i+1)`，**与图像尺寸无关** ⇒ halo 实际是**固定像素
// 尺度**的：归一化半径 ∝ 1/短边。实测预览短边 1080 vs 成片 3072 ⇒ 预览的光晕相对
// 半径是成片的 **2.8×**（WYSIWYG 破绽；规格 §3.2.5 的"等比缩放"声称不成立）。
// 修正 = 把峰值按 `log2(min(w,h) / refMin)` 平移（**纯 CPU、零 GPU 成本**）：
//   归一化半径 = 1.637·2^(centre+1) / min(w,h)，代入 centre' = centre + shift 得
//   1.637·2^(centre+1) / refMin ⇒ **与 min(w,h) 无关** ✓。
// `refMin` = 画幅短边（= 成片短边）⇒ **成片 shift = log2(1) = 0 ⇒ 逐位等于修正前**。
// ⚠ 已知下限（记录，非缺陷）：`centre' < 0` 时权重被 level0 截断（金字塔没有更小的
//   级）⇒ 预览在**很小的 [64]**（R 通道 `radius01 < ~0.38` @1080/3072）下归一化半径
//   略大于成片。消除它需再加一级全分辨率 blur（+19% 采样）—— 不在本卡范围。
// ⚠ **SG-1 重构点**：`spread` 从硬编码 `kHaloSpread` 变成参数 —— halation 传乳剂的
// 逐通道扩散比（红最远），柔光传 `{1,1,1}`（**通道无关**，规格 §1）。函数体其余部分
// 逐字未改 ⇒ halation 的权重数组逐位不变（零回归；见报告"重构 vs 功能"一节）。
void FrameRenderer::haloWeights(float radius01, float level_shift,
                                const float spread[3],
                                float w[kHaloLevels][3]) {
  for (int c = 0; c < 3; ++c) {
    const float centre = radius01 * static_cast<float>(kHaloLevels - 1) *
                             spread[c] +
                         level_shift;
    float raw[kHaloLevels];
    float sum = 0.0f;
    for (int i = 0; i < kHaloLevels; ++i) {
      const float d = static_cast<float>(i) - centre;
      raw[i] = std::exp(-(d * d) / (2.0f * kHaloSigma * kHaloSigma));
      sum += raw[i];
    }
    if (sum <= 0.0f) {
      // 极端平移下全部下溢（float 的 exp < 1e-38）⇒ 退化为"最接近 centre 的级独占"
      //（防 0/0 = NaN；实测范围内走不到这里，纯防御）。
      const int nearest =
          std::clamp(static_cast<int>(std::lround(centre)), 0, kHaloLevels - 1);
      for (int i = 0; i < kHaloLevels; ++i) w[i][c] = (i == nearest) ? 1.0f : 0.0f;
      continue;
    }
    for (int i = 0; i < kHaloLevels; ++i) w[i][c] = raw[i] / sum;
  }
}

void FrameRenderer::releaseHaloTargets() {
  for (int i = 0; i < kHaloLevels; ++i) {
    if (tex_halo_[i]) glDeleteTextures(1, &tex_halo_[i]);
    if (fbo_halo_[i]) glDeleteFramebuffers(1, &fbo_halo_[i]);
    tex_halo_[i] = 0;
    fbo_halo_[i] = 0;
    halo_w_[i] = 0;
    halo_h_[i] = 0;
  }
  if (tex_halo_tmp_) glDeleteTextures(1, &tex_halo_tmp_);
  if (fbo_halo_tmp_) glDeleteFramebuffers(1, &fbo_halo_tmp_);
  tex_halo_tmp_ = 0;
  fbo_halo_tmp_ = 0;
  halo_tmp_w_ = 0;
  halo_tmp_h_ = 0;
  if (tex_halo_out_) glDeleteTextures(1, &tex_halo_out_);
  if (fbo_halo_out_) glDeleteFramebuffers(1, &fbo_halo_out_);
  tex_halo_out_ = 0;
  fbo_halo_out_ = 0;
  halo_src_w_ = 0;
  halo_src_h_ = 0;
}

// 5 级金字塔 + **单张**最大级 tmp + 全分辨率输出。尺寸变化（预览缩放 / 编辑器换图 /
// 成片分辨率变化）时整体重建 —— 缓存键 = 输入尺寸（= 输出尺寸）。
// ⚠ tmp 只有最大级（1/2）尺寸（省 5 张逐级 tmp ≈ 33MB）：处理更小的级时用 uScale 把
//   归一化 UV 缩到 tmp **已写入的左下角子矩形**上（GL 的 FBO 原点在左下角 ⇒
//   viewport(0,0,w_i,h_i) 写左下图块，纹理坐标 (0,0) 也在左下 ⇒ vUV0·scale 精确对上）。
//
// SG-1：`need_out` = 是否要那张**全分辨率**输出（12.6MP ≈ 100MB）。
//   · halation 要（④ 合成写它）；
//   · 柔光**不要**（它的合成结果是 1/2 分辨率的 tex_halo_tmp_）⇒ 只开柔光时省下
//     100MB。缓存键因此含 need_out：先只开柔光、后开光晕时**重建一次**（正确性优先；
//     passOutput/passStyle 里 halation 恒在 glow 之前 ⇒ 同一帧内不会来回重建）。
bool FrameRenderer::ensureHaloTargets(int w, int h, bool need_out) {
  if (w <= 0 || h <= 0) return false;
  if (w == halo_src_w_ && h == halo_src_h_ && tex_halo_[0] != 0 &&
      (!need_out || tex_halo_out_ != 0)) {
    return true;
  }
  releaseHaloTargets();
  // @domain: D5 —— halation 的金字塔各级（kFsFilm 输出的**线性显示域**；编辑器链路在
  // 入口先 sRGB 解码、出口再编码，中间全程同域）。域字典 D5 = "filmic 输出，白点 1.0，
  // [0,1]，允许锐化/OETF" —— 与 tex_work_ 的显示域口径一致。
  for (int i = 0; i < kHaloLevels; ++i) {
    // emulsion：level_i = max(2, w >> (i+1)) —— 小图也有合法尺寸（1/32 级不为 0）
    halo_w_[i] = std::max(2, w >> (i + 1));
    halo_h_[i] = std::max(2, h >> (i + 1));
    makeTex(&tex_halo_[i], &fbo_halo_[i], GL_RGBA16F, halo_w_[i], halo_h_[i]);
  }
  halo_tmp_w_ = halo_w_[0];
  halo_tmp_h_ = halo_h_[0];
  // @domain: D5 —— 横向模糊的中间量（只有最大级尺寸，更小的级靠 uScale 对齐子矩形）；
  // 下方 tex_halo_out_ 同域（全分辨率合成结果）。SG-1 起 tmp 还兼作**柔光图**（柔光的
  // 合成 pass 在 blur 之后写它 ⇒ 复用而非新分配一张 1/2 分辨率纹理）。
  makeTex(&tex_halo_tmp_, &fbo_halo_tmp_, GL_RGBA16F, halo_tmp_w_, halo_tmp_h_);
  if (need_out) makeTex(&tex_halo_out_, &fbo_halo_out_, GL_RGBA16F, w, h);
  // FBO 不完整时 draw 被驱动**静默丢弃**（X300 实测：输出保持未初始化且 glErr=0）
  // ⇒ 必须显式查一次，否则真机上只表现为"光晕没效果"（静默失败）。
  bool ok = true;
  for (int i = 0; i < kHaloLevels; ++i) {
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_halo_[i]);
    ok = ok && glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
  }
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_halo_tmp_);
  ok = ok && glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
  if (need_out) {
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_halo_out_);
    ok = ok && glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
  }
  if (!ok) {
    Logger::instance().log(LogLevel::kError, "engine", "halo.fbo_incomplete",
                           "{\"w\":" + std::to_string(w) +
                               ",\"h\":" + std::to_string(h) + "}");
    releaseHaloTargets();
    return false;
  }
  halo_src_w_ = w;
  halo_src_h_ = h;
  return true;
}

// ══ SG-1 共享金字塔（**重构**：E4-5 的 runHalation 内联版 ①②③ 逐字搬出）═══════
// 规格 §5：halation 与柔光都需要"高光提取 → 降采样金字塔 → 模糊"，区别只在
// **提取公式**与**合成方式** ⇒ 把 ①②③ 抽成这一层，两个效果共用同一批 pass、
// 同一批纹理、同一套 FBO 完整性检查。
//
// `glow_mode`：
//   0 = halation 提取（`lin × smoothstep(阈值±knee, luma)`，**保留色度** —— 通道相关
//       半径由 mix 阶段的逐通道权重实现）；
//   1 = 柔光提取（`max(0, luma − thr)/(1 − thr)`，**标量灰度** —— 通道无关，规格 §1）。
// 两者必须不同：这正是"红色外晕"与"发白外扩"可分辨的根源（同一公式会让两效果重叠）。
//
// `thr_lin` / `knee` 由调用方在 CPU 侧算好（halation = [63]、柔光 = [97]；
// 都是**显示域**阈值 ⇒ 先 srgbToLinear1 换算，shader 内不做 pow）。
//
// ⚠ **调用顺序契约**：柔光的合成结果占用 `tex_halo_tmp_`（halation 的横向模糊中间量，
//   blur 跑完即空闲）⇒ 同一帧内**必须 halation 先跑、柔光后跑**，否则柔光图会被
//   halation 的 blur 覆盖（passOutput/passStyle 的调用顺序即此契约）。
// ⚠ 任何一环不可用（program 缺失 / 纹理分配失败 / FBO 不完整）都返回 false，
//   调用方直通并把 *_on_ 置 0（真机日志因此能区分"没开"与"静默失败"）。
bool FrameRenderer::runHaloPyramid(int w, int h, GLuint src_tex, bool decode_in,
                                   float thr_lin, float knee, bool glow_mode) {
  if (src_tex == 0) return false;
  if (!prog_halo_thr_ || !prog_halo_blur_) return false;
  // need_out = false：柔光只用金字塔各级 + tmp，不要那张全分辨率输出（12.6MP ≈ 100MB）。
  if (!ensureHaloTargets(w, h, /*need_out=*/!glow_mode)) return false;
  glDisable(GL_BLEND);
  glDisable(GL_DEPTH_TEST);
  // ── ① 高光提取（线性域）+ 手写 2×2 盒式降到 1/2（kFsHaloThr）──────────────
  // emulsion 此 pass 靠硬件 LINEAR 完成降采样；X300 的 RGBA16F 无线性过滤 ⇒ 4 采样/px。
  glUseProgram(prog_halo_thr_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, src_tex);
  glUniform1i(u_halo_thr_.src, 0);
  glUniform1f(u_halo_thr_.decode_in, decode_in ? 1.0f : 0.0f);
  glUniform1f(u_halo_thr_.thr_lin, thr_lin);
  glUniform1f(u_halo_thr_.knee, knee);
  glUniform1f(u_halo_thr_.thr_on, 1.0f);
  glUniform1f(u_halo_thr_.glow_on, glow_mode ? 1.0f : 0.0f);
  glUniform2f(u_halo_thr_.texel, 1.0f / static_cast<float>(w),
              1.0f / static_cast<float>(h));
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_halo_[0]);
  glViewport(0, 0, halo_w_[0], halo_h_[0]);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  // ── ② 逐级降采样（同一 program，只关阈值/解码/柔光三个门控）────────────────
  glUniform1f(u_halo_thr_.thr_on, 0.0f);
  glUniform1f(u_halo_thr_.decode_in, 0.0f);
  glUniform1f(u_halo_thr_.glow_on, 0.0f);
  for (int i = 1; i < kHaloLevels; ++i) {
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_halo_[i]);
    glViewport(0, 0, halo_w_[i], halo_h_[i]);
    glBindTexture(GL_TEXTURE_2D, tex_halo_[i - 1]);
    glUniform2f(u_halo_thr_.texel, 1.0f / static_cast<float>(halo_w_[i - 1]),
                1.0f / static_cast<float>(halo_h_[i - 1]));
    glDrawArrays(GL_TRIANGLES, 0, 3);
  }
  // ── ③ 逐级可分离高斯（10 次 draw：每级先横后纵）──────────────────────────
  // 横 pass 写 tmp（只有最大级尺寸 ⇒ 更小的级写进左下角子矩形，由 uScale 对齐）。
  glUseProgram(prog_halo_blur_);
  glUniform1i(u_halo_blur_.src, 0);
  for (int i = 0; i < kHaloLevels; ++i) {
    const float sx =
        static_cast<float>(halo_w_[i]) / static_cast<float>(halo_tmp_w_);
    const float sy =
        static_cast<float>(halo_h_[i]) / static_cast<float>(halo_tmp_h_);
    glUniform2f(u_halo_blur_.scale, sx, sy);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_halo_tmp_);
    glViewport(0, 0, halo_w_[i], halo_h_[i]);
    glBindTexture(GL_TEXTURE_2D, tex_halo_[i]);
    glUniform2f(u_halo_blur_.dir, 1.0f / static_cast<float>(halo_w_[i]), 0.0f);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_halo_[i]);
    glViewport(0, 0, halo_w_[i], halo_h_[i]);
    glBindTexture(GL_TEXTURE_2D, tex_halo_tmp_);
    glUniform2f(u_halo_blur_.dir, 0.0f, 1.0f / static_cast<float>(halo_h_[i]));
    glDrawArrays(GL_TRIANGLES, 0, 3);
  }
  return true;
}

// halation 合成（16 个 pass：Thr(1) + Down(4) + Blur(10) + Mix(1)）。
// **任何一环不可用都返回 src_tex（直通）并把 halo_on_ 置 0** —— 真机日志因此能区分
// "[62]=0 没开"与"[62]>0 但静默失败"（验收判据 6 的失败判据之一）。
GLuint FrameRenderer::runHalation(int w, int h, GLuint src_tex, const FilmParams& p,
                                  bool decode_in) {
  halo_on_ = 0;
  halo_ms_ = 0.0f;
  const float amount = std::clamp(p.halation_strength, 0.0f, 1.0f);
  // [62] ≤ 0 ⇒ **整链旁路**：零 pass、零纹理分配、零成本（验收判据 1 的 A/B 通路）。
  if (amount <= 0.0f || src_tex == 0) return src_tex;
  if (!prog_halo_thr_ || !prog_halo_blur_ || !prog_halo_mix_) return src_tex;
  if (!ensureHaloTargets(w, h, /*need_out=*/true)) return src_tex;
  // ⚠ 计时起点与改动前**同一位置**（纹理分配之后）⇒ halo_ms_ 语义逐位不变
  //（runHaloPyramid 内部那次 ensureHaloTargets 必然缓存命中 = 两次整数比较）。
  const auto t0 = std::chrono::steady_clock::now();
  // ① ② ③ = 共享金字塔（halation 提取模式；阈值与 knee 按 [63] 在 CPU 侧算好）。
  const float thr_lin = srgbToLinear1(p.halation_threshold);
  if (!runHaloPyramid(w, h, src_tex, decode_in, thr_lin,
                      std::max(0.02f, thr_lin * 0.55f), /*glow_mode=*/false)) {
    return src_tex;
  }
  // ── ④ 合成（全分辨率，6 采样/px）：src + Σ halo_i·w_i·tint·amount ─────────
  // 权重与 tint 都在 CPU 侧算好（emulsion 同款）；5 个权重是**独立 uniform**
  // （三禁规避：不用数组 uniform、不用数组初始化器）。
  // E4-5b（R1）：`level_shift` 把峰值按输出分辨率平移 ⇒ halo 的**归一化**半径与
  // 图像尺寸无关（预览与成片一致）。成片（min == halo_ref_min_）时 shift = 0.0f
  // ⇒ 权重逐位等于修正前（零观感变化）；两条链路都走这里 ⇒ 编辑器链路同样归一化
  // （它的 w/h = edit_w_/edit_h_ = 输入位图尺寸，见 passStyle 的调用注释）。
  const float level_shift =
      std::log2(static_cast<float>(std::min(w, h)) / halo_ref_min_);
  float wgt[kHaloLevels][3];
  haloWeights(std::clamp(p.halation_radius, 0.0f, 1.0f), level_shift, kHaloSpread,
              wgt);
  const float warmth = std::clamp(p.halation_warmth, 0.0f, 1.0f);
  glUseProgram(prog_halo_mix_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, src_tex);
  glUniform1i(u_halo_mix_.src, 0);
  for (int i = 0; i < kHaloLevels; ++i) {
    glActiveTexture(GL_TEXTURE1 + i);
    glBindTexture(GL_TEXTURE_2D, tex_halo_[i]);
  }
  glActiveTexture(GL_TEXTURE0);
  glUniform1i(u_halo_mix_.halo0, 1);
  glUniform1i(u_halo_mix_.halo1, 2);
  glUniform1i(u_halo_mix_.halo2, 3);
  glUniform1i(u_halo_mix_.halo3, 4);
  glUniform1i(u_halo_mix_.halo4, 5);
  glUniform3f(u_halo_mix_.w0, wgt[0][0], wgt[0][1], wgt[0][2]);
  glUniform3f(u_halo_mix_.w1, wgt[1][0], wgt[1][1], wgt[1][2]);
  glUniform3f(u_halo_mix_.w2, wgt[2][0], wgt[2][1], wgt[2][2]);
  glUniform3f(u_halo_mix_.w3, wgt[3][0], wgt[3][1], wgt[3][2]);
  glUniform3f(u_halo_mix_.w4, wgt[4][0], wgt[4][1], wgt[4][2]);
  glUniform3f(u_halo_mix_.tint, 1.0f, 0.24f + 0.34f * warmth,
              0.44f - 0.22f * warmth);
  glUniform1f(u_halo_mix_.amount, amount);
  glUniform1f(u_halo_mix_.decode_in, decode_in ? 1.0f : 0.0f);
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_halo_out_);
  glViewport(0, 0, w, h);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  halo_on_ = 1;
  // ⚠ CPU 侧**提交**耗时（与 passMs / rcdAMs 同口径，不是 GPU 完成耗时）。真机判据
  //   见报告：成片路径末尾有 glFinish ⇒ totalMs 含 halo 的真实 GPU 时间。
  halo_ms_ = std::chrono::duration<float, std::milli>(
                 std::chrono::steady_clock::now() - t0)
                 .count();
  return tex_halo_out_;
}

// ══ SG-1 柔光（soft glow / Orton bloom）══════════════════════════════════════
// 参考实现 = darktable `src/iop/bloom.c` 的 Orton 支（规格 §2）：
//   ① h = max(0, luma − threshold)/(1 − threshold)   （kFsHaloThr 的 glow 模式）
//   ② b = gaussian_blur(h, radius)                    （**通道无关、单半径** = 共享金字塔）
//   ③ out = 1 − (1 − c)(1 − b·strength)               （**Screen**，落在 kFsOut/kFsStyle）
// 本函数只做 ①② + "5 级加权成一张柔光图"（规格 §5 的"少数 pass"）；③ 在 kFsOut /
// kFsStyle 的显示域段内做 —— 那里才拿得到"经过影调分区/褪色的当前 c"（规格 §2 的顺序）。
//
// 与 runHalation 共用：①② 的**全部 pass**（1 次提取 + 4 次降采样 + 10 次模糊）、
// 5 级金字塔纹理、tmp、FBO 完整性检查。柔光**只多 1 个 1/2 分辨率合成 pass**。
// 不复用 halation 的**提取结果**：两者的提取公式不同（彩色 × 权重 vs 标量阈值），
// 且 [63] ≠ [97] ⇒ 数学上不可互换（报告 §"共享金字塔省了多少"有量化）。
//
// 返回 = 柔光图纹理（**1/2 分辨率**，R 通道 = 线性显示域的模糊高光值）；
// **0 = 未执行**（[95] ≤ 0 / 任何一环不可用）⇒ 调用方把 uGlowOn 置 0 直通。
GLuint FrameRenderer::runGlow(int w, int h, GLuint src_tex, const FilmParams& p,
                              bool decode_in) {
  glow_on_ = 0;
  glow_ms_ = 0.0f;
  const float amount = std::clamp(p.glow_strength, 0.0f, 1.0f);
  // [95] ≤ 0 ⇒ **整链旁路**：零 pass、零纹理分配、零成本（验收判据 1 的 A/B 通路）。
  if (amount <= 0.0f || src_tex == 0) return 0;
  if (!prog_halo_thr_ || !prog_halo_blur_ || !prog_glow_sum_) return 0;
  if (!ensureHaloTargets(w, h, /*need_out=*/false)) return 0;
  const auto t0 = std::chrono::steady_clock::now();
  // [97] 是**显示域**阈值（与 [63] 同口径）⇒ CPU 侧换算到线性显示域。
  // clamp 到 0.999：thr = 1.0 时 shader 的分母为 0（那里另有 max(...,0.001) 防御）。
  const float thr_lin =
      srgbToLinear1(std::clamp(p.glow_threshold, 0.0f, 0.999f));
  // knee 不参与柔光提取（glow 模式不用它），传 0 以表明"无过渡带"。
  if (!runHaloPyramid(w, h, src_tex, decode_in, thr_lin, 0.0f,
                      /*glow_mode=*/true)) {
    return 0;
  }
  // ── ④ 单半径加权合成（1/2 分辨率，5 采样/px）──────────────────────────────
  // 权重 = **标量**（三通道同一个值，spread = {1,1,1}）⇒ 三通道同半径 = 通道无关
  //（规格 §1 的判据；halation 在这里传 kHaloSpread ⇒ 逐通道不同峰值）。
  // level_shift 与 halation 同式：半径按**短边**归一化 ⇒ 预览与成片的相对半径一致。
  const float level_shift =
      std::log2(static_cast<float>(std::min(w, h)) / halo_ref_min_);
  const float spread_flat[3] = {1.0f, 1.0f, 1.0f};
  float wgt[kHaloLevels][3];
  haloWeights(std::clamp(p.glow_radius, 0.0f, 1.0f), level_shift, spread_flat,
              wgt);
  glUseProgram(prog_glow_sum_);
  for (int i = 0; i < kHaloLevels; ++i) {
    glActiveTexture(GL_TEXTURE1 + i);
    glBindTexture(GL_TEXTURE_2D, tex_halo_[i]);
  }
  glActiveTexture(GL_TEXTURE0);
  glUniform1i(u_glow_sum_.g0, 1);
  glUniform1i(u_glow_sum_.g1, 2);
  glUniform1i(u_glow_sum_.g2, 3);
  glUniform1i(u_glow_sum_.g3, 4);
  glUniform1i(u_glow_sum_.g4, 5);
  // 三通道权重相同 ⇒ 只下发 w[i][0]（R 通道那一份，shader 里是标量 uniform）。
  glUniform1f(u_glow_sum_.s0, wgt[0][0]);
  glUniform1f(u_glow_sum_.s1, wgt[1][0]);
  glUniform1f(u_glow_sum_.s2, wgt[2][0]);
  glUniform1f(u_glow_sum_.s3, wgt[3][0]);
  glUniform1f(u_glow_sum_.s4, wgt[4][0]);
  // 目标 = tex_halo_tmp_（1/2 分辨率，blur 已完成 ⇒ 空闲）⇒ **柔光零额外纹理**。
  // ⚠ 这也正是"柔光必须在 halation 之后跑"的原因（见 runHaloPyramid 的顺序契约）。
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_halo_tmp_);
  glViewport(0, 0, halo_tmp_w_, halo_tmp_h_);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glow_on_ = 1;
  glow_ms_ = std::chrono::duration<float, std::milli>(
                 std::chrono::steady_clock::now() - t0)
                 .count();
  return tex_halo_tmp_;
}

// E1-3 编辑器通路：显示域输入（still_tex_）→ LUT/颗粒/dither → fbo_cap_（RGBA8）。
// 与 passOutput 的区别见 kFsStyle 注释：不重跑滚降/锐化/OETF，无翻转、无斑马纹。
// 颗粒种子固定为 0（submitDisplay 置）⇒ 同一输入 + 同一参数 ⇒ 输出可复现（报告 R3）。
void FrameRenderer::passStyle(int w, int h, const float crop_uv[4],
                              int crop_rot, const FilmParams& p) {
  // ── E4-5 halation（光晕）：**在 passStyle 之前**跑金字塔 ────────────────────
  // kFsStyle 是单 pass，装不下 5 级金字塔 ⇒ 金字塔独立成 pass 链，其"加法"落在
  // kFsStyle 的 **LUT 之前**，与拍照链路（kFsFilm 后 / kFsOut 的 OETF 与 LUT 前）
  // 同序（规格 §3.2.2）。输入 = edit_tex_（**显示域 sRGB** ⇒ decode_in=true），
  // 输出域跟随输入域（显示域）⇒ kFsStyle 的域约定与下游代码**零改动**。
  // ⚠ 尺寸用 edit_w_/edit_h_（**输入位图**尺寸），**不是**本函数的 w/h（= 裁剪后
  //   输出尺寸）：halo 是片基上的固定物理尺寸，必须按原画幅归一化（规格 §3.2.5）。
  //   裁剪只是取景，halo 坐标系不跟着放大 —— 这正是"按原画幅而非输出画幅"的实现。
  GLuint style_src = edit_tex_;
  GLuint glow_src = 0;
  if (p.halation_strength > 0.0f && edit_w_ > 0 && edit_h_ > 0) {
    style_src = runHalation(edit_w_, edit_h_, edit_tex_, p, /*decode_in=*/true);
  }
  // ── SG-1 柔光（soft glow）：与 halation **共用金字塔**，必须排在它之后 ────────
  // ① 顺序：柔光图占用 tex_halo_tmp_（halation 横向模糊的中间量）⇒ 先柔光后 halation
  //    会把柔光图覆盖掉（见 runHaloPyramid 的顺序契约）。
  // ② 输入用 edit_tex_（**原图**，不是 halation 的结果）：两者是独立效果（镜头柔光
  //    vs 乳剂背反射），让柔光再去提取 halation 的红色光晕会让两者视觉重叠 ——
  //    规格 §1 的判据要求"关掉任一项另一项仍在"且**可分辨**。
  // ③ 尺寸同 halation = edit_w_/edit_h_（原画幅）：柔光半径按短边归一化 ⇒ 与裁剪
  //    无关（裁剪只是取景，kFsStyle 内用 cropUV 采样柔光图）。
  if (p.glow_strength > 0.0f && edit_w_ > 0 && edit_h_ > 0) {
    glow_src = runGlow(edit_w_, edit_h_, edit_tex_, p, /*decode_in=*/true);
  }
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_cap_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_style_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, style_src);  // E1-5：编辑器自己的 RGBA8 输入纹理
  glUniform1i(u_style_.tex, 0);
  // ── SG-1 柔光图绑定（**关闭态也绑** —— Mali 对未绑定的 sampler 会预测执行采样，
  // 与 grain LUT / 3D LUT 同型事故）。关闭时绑 style_src（一定有效）⇒ 采样结果被
  // uGlowOn 门控丢弃，不会报错、也不会读未初始化显存。
  // 纹理单元 3（0 = 输入帧，1 = 3D LUT，2 = 颗粒 LUT）。
  glActiveTexture(GL_TEXTURE3);
  glBindTexture(GL_TEXTURE_2D, glow_src != 0 ? glow_src : style_src);
  glUniform1i(u_style_.glow, 3);
  glActiveTexture(GL_TEXTURE0);
  // ── E4-3 颗粒：CPU 侧算好尺度常数（shader 内不做 W/H 除法与 clamp）────────
  // 短边归一化（ADR-11）：噪声坐标 = 像素/短边 ⇒ 预览（降采样）与导出（全分辨率）的
  // 颗粒相对空间频率一致，与像素数无关。zoom 越大 ⇒ 频率越低 ⇒ 颗粒越粗。
  // seed：预览/编辑器恒 0（图案稳定不闪）；成片由输出尺寸 + 输入内容指纹派生
  //（同一张可复现、不同张不同 —— 验收判据 6）。
  glUniform1f(u_style_.grain_amount, p.grain_amount);
  glUniform1f(u_style_.grain_seed, grain_seed_);
  glUniform1f(u_style_.grain_zoom, grain::grainZoom(p.grain_size));
  glUniform1f(u_style_.grain_norm, 1.0f / static_cast<float>(std::min(w, h)));
  // 相纸响应 LUT（[60]/[61] 变化才重算；**关闭态也绑** —— Mali 对未绑定的 sampler
  // 会预测执行采样，与 bindLut 注释里的同型事故）。纹理单元 2（0 = 输入帧，1 = 3D LUT）。
  ensureGrainLut(std::clamp(p.grain_midtone_bias, 0.0f, 100.0f),
                 std::clamp(p.grain_pivot, 0.0f, 1.0f));
  glActiveTexture(GL_TEXTURE2);
  glBindTexture(GL_TEXTURE_2D, tex_grain_lut_);
  glUniform1i(u_style_.grain_lut, 2);
  // ── E2-12 裁剪 + 90° 步进旋转（ADR-16；几何变换，不占 FilmParams 槽位）──────
  // nullptr = 全幅：origin=(0,0)、size=(1,1)、rot=0 ⇒ shader 内 cropUV 恒等
  //（`0.0 + o × 1.0` 在 IEEE 下逐位精确）⇒ 不裁剪时输出与改动前逐位一致（零回归）。
  // ⚠ 本函数的 w/h 是**输出**尺寸（裁剪后）：暗角尺度 uVigScaleXY 与清晰度环半径
  //   uClarStep 都由它标定 ⇒ 裁剪后暗角自动相对裁剪后画面居中、清晰度环仍是
  //   "输出图像短边的 0.5%"（这正是"裁剪在引擎输入侧做"的收益，ADR-16）。
  const float crop_x0 = crop_uv ? crop_uv[0] : 0.0f;
  const float crop_y0 = crop_uv ? crop_uv[1] : 0.0f;
  const float crop_cw = crop_uv ? crop_uv[2] : 1.0f;
  const float crop_ch = crop_uv ? crop_uv[3] : 1.0f;
  glUniform2f(u_style_.crop_origin, crop_x0, crop_y0);
  glUniform2f(u_style_.crop_size, crop_cw, crop_ch);
  glUniform1f(u_style_.crop_rot, static_cast<float>(crop_rot));
  // ── E2-2 基础调整（ADR §5.6；域约定见 kFsStyle 注释）─────────────────────
  // 门控一律在 CPU 侧算 ⇒ 默认（ev=0、WB=(1,1,1)、contrast=1、sat=1、clarity=0）
  // 时 shader 内**整段跳过**，输出逐位不变（零回归 / A/B 开关 = 这五个默认值）。
  // ── E2-11 色温/色调：CPU 侧折算成增益后**并入 [1..3]**（ADR-14 裁决 1）──────
  // 与 [1..3] 同属线性域增益 ⇒ 同一个乘性操作 ⇒ 复用已有的成对往返（零额外 GPU
  // 成本、零额外量化误差），且 chainLuma 的 `dot(uWbGain, luma)` 域提升**自动**带上
  // 它们（这也是"不另起一段"的收益：少一处忘记同步 ⇒ 清晰度变成整体偏色的坑）。
  // 恒等性：[88]=[89]=0 时 tt_gain 恒 (1,1,1)（参考点差分，逐位）⇒ 门控与下发增益
  // 都与改动前完全一致 ⇒ 零回归。
  float tt_gain[3] = {1.0f, 1.0f, 1.0f};
  const bool temp_tint_on = p.temp_mired != 0.0f || p.tint != 0.0f;
  if (temp_tint_on) tempTintGain(p.temp_mired, p.tint, tt_gain);
  const float wb_tot[3] = {p.wb_r * tt_gain[0], p.wb_g * tt_gain[1],
                           p.wb_b * tt_gain[2]};
  const bool exp_wb_on = p.exposure_ev != 0.0f || wb_tot[0] != 1.0f ||
                         wb_tot[1] != 1.0f || wb_tot[2] != 1.0f;
  glUniform1f(u_style_.ev, p.exposure_ev);
  glUniform3f(u_style_.wb_gain, wb_tot[0], wb_tot[1], wb_tot[2]);
  glUniform1f(u_style_.exp_wb_on, exp_wb_on ? 1.0f : 0.0f);
  glUniform1f(u_style_.contrast, p.contrast);
  glUniform1f(u_style_.contrast_on, p.contrast != 1.0f ? 1.0f : 0.0f);
  // ── U-2 [20..22] 分区饱和三档（替代旧版"只读 [21]"）────────────────────────
  // 用户 2026-10-11"不完整的要补上"：§1.6 记的"编辑器只读 [21]（UI 一个滑杆同写三槽）"
  // 至此关闭 —— 三档都下发给引擎，预设里只含 [22] 时也能被如实执行。
  // 门控：三槽**任一** ≠ 1 才开（旧版只看 [21]）⇒ 默认三槽 = 1.0 时门控与下发都与
  // 改动前等价（`uSatOn = 0` ⇒ shader 整段跳过 ⇒ 逐位零回归）。
  // ⚠ luma 从 Rec.601 改为 Rec.709（与 kFsFilm 的分区判据同源）：默认值下 `sat = 1`
  // 使 mix 恒等（与 luma 无关）⇒ 零回归不受影响；非默认值下的输出变化 = 消除两侧
  // luma 口径差异，逐位对照见 tools/check-u2-editor-curve-sat.py 的判据 ④。
  // 量程：不 clamp（与 passFilm 同口径 —— 那里 `clamp(..., 0, 2)` 在 shader 内做）。
  glUniform1f(u_style_.sat_shadow, p.sat_shadow);
  glUniform1f(u_style_.sat_mid, p.sat_mid);
  glUniform1f(u_style_.sat_high, p.sat_high);
  const bool sat_on =
      p.sat_shadow != 1.0f || p.sat_mid != 1.0f || p.sat_high != 1.0f;
  glUniform1f(u_style_.sat_on, sat_on ? 1.0f : 0.0f);
  // ── U-2 胶片曲线 [5..10] + [37][38]（display 域）──────────────────────────
  // CPU 侧算 12 个 5×5 系统 + 端点仿射（见 computeEditorCurve 的长注释）；默认
  // （8 个槽位全默认）⇒ `ec.on = false` ⇒ **一次 glUniform 都不发**、shader 整段不进
  // ⇒ 逐位零回归。⚠ 只在 on 时下发是安全的：shader 在不进该段时**不读任何曲线
  // uniform**，不存在"残留旧值"的路径（与 LUT/颗粒那种"关闭态也必须绑定纹理"的
  // Mali 预测执行问题不同 —— 那些是 sampler，这里是纯数值 uniform）。
  const EditorCurve ec = computeEditorCurve(p.shoulder, p.toe, p.filmic_black_ev,
                                            p.filmic_white_ev);
  glUniform1f(u_style_.curve_on, ec.on ? 1.0f : 0.0f);
  if (ec.on) {
    glUniform4f(u_style_.curve_lo, ec.lo_a, ec.lo_b, 0.0f, 0.0f);
    glUniform4f(u_style_.curve_hi, ec.hi_a, ec.hi_b, 0.0f, 0.0f);
    glUniform3f(u_style_.curve_toe_at, ec.at[0][0], ec.at[1][0], ec.at[2][0]);
    glUniform3f(u_style_.curve_sh_at, ec.at[0][1], ec.at[1][1], ec.at[2][1]);
    glUniform3f(u_style_.curve_toe_m1, ec.toe[0][0], ec.toe[1][0], ec.toe[2][0]);
    glUniform3f(u_style_.curve_toe_m2, ec.toe[0][1], ec.toe[1][1], ec.toe[2][1]);
    glUniform3f(u_style_.curve_toe_m3, ec.toe[0][2], ec.toe[1][2], ec.toe[2][2]);
    glUniform3f(u_style_.curve_toe_m4, ec.toe[0][3], ec.toe[1][3], ec.toe[2][3]);
    glUniform3f(u_style_.curve_toe_m5, ec.toe[0][4], ec.toe[1][4], ec.toe[2][4]);
    glUniform3f(u_style_.curve_sh_m1, ec.sh[0][0], ec.sh[1][0], ec.sh[2][0]);
    glUniform3f(u_style_.curve_sh_m2, ec.sh[0][1], ec.sh[1][1], ec.sh[2][1]);
    glUniform3f(u_style_.curve_sh_m3, ec.sh[0][2], ec.sh[1][2], ec.sh[2][2]);
    glUniform3f(u_style_.curve_sh_m4, ec.sh[0][3], ec.sh[1][3], ec.sh[2][3]);
    glUniform3f(u_style_.curve_sh_m5, ec.sh[0][4], ec.sh[1][4], ec.sh[2][4]);
    glUniform3f(u_style_.curve_lin_m1, ec.lin[0][0], ec.lin[1][0], ec.lin[2][0]);
    glUniform3f(u_style_.curve_lin_m2, ec.lin[0][1], ec.lin[1][1], ec.lin[2][1]);
    glUniform3f(u_style_.base_toe_at, ec.b_at[0][0], ec.b_at[1][0], ec.b_at[2][0]);
    glUniform3f(u_style_.base_sh_at, ec.b_at[0][1], ec.b_at[1][1], ec.b_at[2][1]);
    glUniform3f(u_style_.base_toe_m1, ec.b_toe[0][0], ec.b_toe[1][0], ec.b_toe[2][0]);
    glUniform3f(u_style_.base_toe_m2, ec.b_toe[0][1], ec.b_toe[1][1], ec.b_toe[2][1]);
    glUniform3f(u_style_.base_toe_m3, ec.b_toe[0][2], ec.b_toe[1][2], ec.b_toe[2][2]);
    glUniform3f(u_style_.base_toe_m4, ec.b_toe[0][3], ec.b_toe[1][3], ec.b_toe[2][3]);
    glUniform3f(u_style_.base_toe_m5, ec.b_toe[0][4], ec.b_toe[1][4], ec.b_toe[2][4]);
    glUniform3f(u_style_.base_sh_m1, ec.b_sh[0][0], ec.b_sh[1][0], ec.b_sh[2][0]);
    glUniform3f(u_style_.base_sh_m2, ec.b_sh[0][1], ec.b_sh[1][1], ec.b_sh[2][1]);
    glUniform3f(u_style_.base_sh_m3, ec.b_sh[0][2], ec.b_sh[1][2], ec.b_sh[2][2]);
    glUniform3f(u_style_.base_sh_m4, ec.b_sh[0][3], ec.b_sh[1][3], ec.b_sh[2][3]);
    glUniform3f(u_style_.base_sh_m5, ec.b_sh[0][4], ec.b_sh[1][4], ec.b_sh[2][4]);
    glUniform3f(u_style_.base_lin_m1, ec.b_lin[0][0], ec.b_lin[1][0], ec.b_lin[2][0]);
    glUniform3f(u_style_.base_lin_m2, ec.b_lin[0][1], ec.b_lin[1][1], ec.b_lin[2][1]);
  }
  glUniform1f(u_style_.clarity, p.clarity);
  // 清晰度环半径 = max(1px, 0.5% × min(W,H))（唯一维护点 = clarityStepPx，U-4 起
  // passStyle / passOutput / 成片诊断三处共用同一份公式）。
  const float clar_px = clarityStepPx(w, h);
  glUniform2f(u_style_.clar_step, clar_px / static_cast<float>(w),
              clar_px / static_cast<float>(h));
  // ── E2-11 影调分区 + 褪色（display 域；门控在 CPU 侧算）────────────────────
  // uToneOn = 四项色阶任一非 0；[94] fade 由 shader 内 `uFade > 0.0` 自行门控
  //（它是一条独立公式，不需要与四项色阶共用门控）。默认全 0 ⇒ 两段都整段跳过。
  const bool tone_on = p.highlights != 0.0f || p.shadows != 0.0f ||
                       p.whites != 0.0f || p.blacks != 0.0f;
  glUniform1f(u_style_.tone_on, tone_on ? 1.0f : 0.0f);
  glUniform1f(u_style_.highlights, p.highlights);
  glUniform1f(u_style_.shadows, p.shadows);
  glUniform1f(u_style_.whites, p.whites);
  glUniform1f(u_style_.blacks, p.blacks);
  glUniform1f(u_style_.fade, p.fade);
  // ── SG-1 柔光（soft glow）：生效值下发（与 kFsOut 逐字同段）────────────
  // uGlowOn = glow_src != 0（[95] > 0 且共享金字塔链成功）⇒ 关闭时 shader 整段跳过。
  // uGlowTexel 用 halo_tmp_*（柔光图写在 tex_halo_tmp_ 上，1/2 分辨率）：glowFetch 的
  // 手写双线性需要它把 UV 换成像素坐标（shader 内不做除法）。
  // ⚠ max(1, ...) 兜底：[95] = 0 时 halo_tmp_* 可能从未分配（0）⇒ 1/0 = inf。
  glUniform1f(u_style_.glow_on, glow_src != 0 ? 1.0f : 0.0f);
  glUniform1f(u_style_.glow_strength, std::clamp(p.glow_strength, 0.0f, 1.0f));
  glUniform2f(u_style_.glow_texel,
              1.0f / static_cast<float>(std::max(1, halo_tmp_w_)),
              1.0f / static_cast<float>(std::max(1, halo_tmp_h_)));
  // ── E2-6 局部调整（ADR §5.7）────────────────────────────────────────────
  // 门控一律在 CPU 侧算（与上面五个门控同款）：[79]=0（默认）或 ev/sat 都恒等时
  // uLocalOn=0 ⇒ shader 内 mask 恒 0 ⇒ 整段无副作用（A/B 开关 = [79]）。
  // local_ev_on / local_sat_on 分开下发：只有局部曝光非 0 时才需要那次往返
  //（局部饱和度是显示域操作，不该为它付一次 decode/encode 的量化代价）。
  const bool local_radial = p.local_type >= 0.5f && p.local_type < 1.5f;
  const bool local_linear = p.local_type >= 1.5f;
  const bool local_ev_on = p.local_ev != 0.0f;
  const bool local_sat_on = p.local_sat != 1.0f;
  const bool local_on =
      (local_radial || local_linear) && (local_ev_on || local_sat_on);
  glUniform1f(u_style_.local_on, local_on ? 1.0f : 0.0f);
  glUniform1f(u_style_.local_type, p.local_type);
  glUniform2f(u_style_.local_center, p.local_center_x, p.local_center_y);
  // E2-7 正圆修正：radius 拆成 (rx, ry) —— rx = [82]（相对图像宽），ry = [82]×W/H
  //（相对图像高）。与暗角 uVigScaleXY = (W,H)/max 同款"域常数在 CPU 侧算好"：
  // shader 内不做 W/H 除法。效果 = `length(p/r)` 在像素空间等距 ⇒ 非方形画幅下是正圆
  //（推导见 kFsStyle 的 uLocalRadius 声明：rx·W = ry·H = R ⇒ ry = rx·W/H）。
  const float local_aspect =
      (h > 0) ? static_cast<float>(w) / static_cast<float>(h) : 1.0f;
  glUniform2f(u_style_.local_radius, p.local_radius,
              p.local_radius * local_aspect);
  glUniform1f(u_style_.local_feather, p.local_feather);
  glUniform1f(u_style_.local_angle, p.local_angle);
  glUniform1f(u_style_.local_width, p.local_width);
  glUniform1f(u_style_.local_ev, p.local_ev);
  glUniform1f(u_style_.local_ev_on, local_ev_on ? 1.0f : 0.0f);
  glUniform1f(u_style_.local_sat, p.local_sat);
  glUniform1f(u_style_.local_sat_on, local_sat_on ? 1.0f : 0.0f);
  // E4-2 暗角（vignette）：域常数在 CPU 侧算好（shader 内不做 W/H 除法）。
  // pv 尺度 = (W,H)/max(W,H)；fscale 的 100/min(W,H) 下限 = darktable 同款抗锯齿
  // 保护（feather=0 ⇒ 不产生硬边）。[66] ≥ 0 ⇒ uVigBrightness = 0 ⇒ shader 整段跳过。
  const float vig_max = static_cast<float>(std::max(w, h));
  const float vig_min = static_cast<float>(std::min(w, h));
  glUniform2f(u_style_.vig_scale, static_cast<float>(w) / vig_max,
              static_cast<float>(h) / vig_max);
  glUniform1f(u_style_.vig_dscale,
              std::clamp(p.vignette_radius, 0.0f, 200.0f) / 100.0f);
  glUniform1f(u_style_.vig_fscale,
              std::max(p.vignette_feather, 100.0f / vig_min) / 100.0f);
  glUniform1f(u_style_.vig_brightness,
              std::clamp(p.vignette_strength, -1.0f, 0.0f));
  // ── U-1 [23][24] 分离色调 + [25] 单色（display 域；与 passFilm 同式）──────────
  // 门控在 shader 内（三槽全默认 ⇒ 整段跳过）；这里照 passFilm 的裸值下发，
  // 不做 clamp —— kFsFilm 也读同一对槽位的原值，两侧口径必须一致。
  glUniform2f(u_style_.split, p.split_warm, p.split_cool);
  glUniform1f(u_style_.mono, p.monochrome);
  // 与 passOutput 同款：先落 pending LUT，再 bindLut 下发（关闭态也绑定纹理 + 下发
  // 全部 uniform —— 提前 return 会让 sampler3D 指向未绑定单元，Mali 预测执行采样
  // ⇒ draw 报 1282、整帧全黑，见 bindLut 注释）
  applyPendingLut();
  bindLut(u_style_.lut, u_style_.lut_on, u_style_.lut_strength, u_style_.lut_dmin,
          u_style_.lut_dinv, u_style_.lut_size, p.lut_strength);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

void FrameRenderer::passOutput(int w, int h, const FilmParams& p, bool flip_y,
                               bool zebra, float out_max) {
  // flip_y 仅在拍照路径使用（渲染到 fbo_cap_）；预览直接渲染到当前窗口面
  if (flip_y) glBindFramebuffer(GL_FRAMEBUFFER, fbo_cap_);
  // ── E4-5 halation（光晕）：**在 OETF 之前**（tex_work_ = kFsFilm 输出的线性显示域）
  // 加光交给本 shader 的 highlightRolloff 压缩（规格 §3.2.1 ⑦ / §3.2.4）⇒ halation
  // pass 内**不做** shoulder，也不改变 kFsOut 的其余段。
  // ⚠ 本函数**不改绑定**是调用方的约定（submitFrame 的降档分支已绑 fbo_out_s_、预览
  //   路径已绑窗口 0）⇒ halo 链切过 FBO 后必须**恢复进入时的目标**，不能硬编码
  //   `flip_y ? fbo_cap_ : 0`（那会把降档预览画到窗口上）。halo 关闭时**一个额外 GL
  //   调用都不发**（glGetIntegerv 也在分支内）⇒ 与改动前逐位零回归。
  GLuint out_src = tex_work_;
  GLuint glow_src = 0;
  if (p.halation_strength > 0.0f) {
    GLint prev_fbo = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prev_fbo);
    out_src = runHalation(w, h, tex_work_, p, /*decode_in=*/false);
    glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(prev_fbo));
  }
  // ── SG-1 柔光（soft glow）：与 halation **共用金字塔**，排在它之后 ───────────
  // ① 顺序：柔光图占用 tex_halo_tmp_ ⇒ 必须在 halation 的 blur 之后（顺序契约）。
  // ② 输入用 tex_work_（**原始帧**，不是 halation 的结果）—— 理由同 passStyle ②：
  //    两者是独立效果，柔光不该再去提取 halation 的红色光晕（规格 §1 的可分辨判据）。
  // ③ 域：tex_work_ 是线性显示域（kFsFilm 输出）⇒ decode_in=false；金字塔输出仍是
  //    线性显示域，Screen 混合在 kFsOut 的 srgbEncode **之后**做（规格 §2 的显示域
  //    落点）—— glowFetch 负责那一次 OETF。
  // ⚠ [95] = 0 时**一个额外 GL 调用都不发**（glGetIntegerv 也在分支内）⇒ 与改动前
  //   逐位零回归（验收判据 1）。
  if (p.glow_strength > 0.0f) {
    GLint prev_fbo = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prev_fbo);
    glow_src = runGlow(w, h, tex_work_, p, /*decode_in=*/false);
    glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(prev_fbo));
  }
  glViewport(0, 0, w, h);
  glUseProgram(prog_out_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, out_src);
  glUniform1i(u_out_.tex, 0);
  // ── SG-1 柔光图绑定（**关闭态也绑** —— 与 grain LUT / 3D LUT 同型：Mali 对未绑定
  // 的 sampler 会预测执行采样）。关闭时绑 out_src（一定有效）⇒ 结果被 uGlowOn 门控
  // 丢弃。纹理单元 3（0 = 输入帧，1 = 3D LUT，2 = 颗粒 LUT）。
  glActiveTexture(GL_TEXTURE3);
  glBindTexture(GL_TEXTURE_2D, glow_src != 0 ? glow_src : out_src);
  glUniform1i(u_out_.glow, 3);
  glActiveTexture(GL_TEXTURE0);
  glUniform2f(u_out_.px, 1.0f / static_cast<float>(w),
              1.0f / static_cast<float>(h));
  // ── E4-3 颗粒：CPU 侧算好尺度常数（shader 内不做 W/H 除法与 clamp）────────
  // 短边归一化（ADR-11）：噪声坐标 = 像素/短边 ⇒ 预览（降采样）与导出（全分辨率）的
  // 颗粒相对空间频率一致，与像素数无关。zoom 越大 ⇒ 频率越低 ⇒ 颗粒越粗。
  // seed：预览/编辑器恒 0（图案稳定不闪）；成片由输出尺寸 + 输入内容指纹派生
  //（同一张可复现、不同张不同 —— 验收判据 6）。
  glUniform1f(u_out_.grain_amount, p.grain_amount);
  glUniform1f(u_out_.grain_seed, grain_seed_);
  glUniform1f(u_out_.grain_zoom, grain::grainZoom(p.grain_size));
  glUniform1f(u_out_.grain_norm, 1.0f / static_cast<float>(std::min(w, h)));
  // 相纸响应 LUT（[60]/[61] 变化才重算；**关闭态也绑** —— Mali 对未绑定的 sampler
  // 会预测执行采样，与 bindLut 注释里的同型事故）。纹理单元 2（0 = 输入帧，1 = 3D LUT）。
  ensureGrainLut(std::clamp(p.grain_midtone_bias, 0.0f, 100.0f),
                 std::clamp(p.grain_pivot, 0.0f, 1.0f));
  glActiveTexture(GL_TEXTURE2);
  glBindTexture(GL_TEXTURE_2D, tex_grain_lut_);
  glUniform1i(u_out_.grain_lut, 2);
  glUniform1f(u_out_.flip_y, flip_y ? 1.0f : 0.0f);
  // 斑马纹只走预览：成片路径一律 0（提示不进成片）
  glUniform1f(u_out_.zebra, zebra ? p.zebra_thr : 0.0f);
  // ── 卡 H2：dither 除数 = **交付档上限**（OutputBitDepth.h 的 outputMaxOf）────────
  // 8bit JPEG 链 = 255.0（并与 shader 兜底同值 ⇒ 逐位同改动前）；10bit HEIF = 1023.0
  //（dither 幅度随位深重标：设计 §2.5 / §4.4）。**必须显式下发**：若依赖 shader 兜底，
  // 10bit 就会用 8bit 幅度抖动 ⇒ 有效精度掉回 ~8.6bit（静默降位）。
  glUniform1f(u_out_.out_max, out_max);
  // v0.9.6 轻度锐化（luma USM，滚降后 OETF 前；0 = 关 = A/B 通路）
  glUniform1f(u_out_.sharpen, p.sharpen);
  glUniform1f(u_out_.rolloff, p.hl_rolloff > 0.5f ? 1.0f : 0.0f);   // 0.12.166：高光滚降开关
  // E4-2 暗角（vignette）：域常数在 CPU 侧算好（shader 内不做 W/H 除法）。
  // pv 尺度 = (W,H)/max(W,H)；fscale 的 100/min(W,H) 下限 = darktable 同款抗锯齿
  // 保护（feather=0 ⇒ 不产生硬边）。[66] ≥ 0 ⇒ uVigBrightness = 0 ⇒ shader 整段跳过。
  const float vig_max = static_cast<float>(std::max(w, h));
  const float vig_min = static_cast<float>(std::min(w, h));
  glUniform2f(u_out_.vig_scale, static_cast<float>(w) / vig_max,
              static_cast<float>(h) / vig_max);
  glUniform1f(u_out_.vig_dscale,
              std::clamp(p.vignette_radius, 0.0f, 200.0f) / 100.0f);
  glUniform1f(u_out_.vig_fscale,
              std::max(p.vignette_feather, 100.0f / vig_min) / 100.0f);
  glUniform1f(u_out_.vig_brightness,
              std::clamp(p.vignette_strength, -1.0f, 0.0f));
  // ── U-4 清晰度 + 影调分区 + 褪色（display 域；**与 passStyle 的同名段逐字同式**）──
  // 落点 = shader 的 srgbEncode 之后 ⇒ 与 kFsStyle 的输入域（已成片 sRGB）**同域**，
  // 因此这三段的公式全段照抄 passStyle（系数/权重/区间/先后序），不重设计。
  // 门控与 passStyle 逐字同判据：clarity / fade 在 shader 内判（`> 0.0`）、
  // tone_on 由 CPU 侧算（四项任一非 0）。默认值（[78]=0、[90..94]=0）⇒ 三段整段跳过
  // ⇒ 逐位零回归（A/B 开关 = 这五个值）。
  glUniform1f(u_out_.clarity, p.clarity);
  // 清晰度环半径 = max(1px, 0.5% × min(W,H))（唯一维护点 = clarityStepPx，与
  // passStyle / 成片诊断同源）。
  const float clar_px = clarityStepPx(w, h);
  glUniform2f(u_out_.clar_step, clar_px / static_cast<float>(w),
              clar_px / static_cast<float>(h));
  const bool tone_on = p.highlights != 0.0f || p.shadows != 0.0f ||
                       p.whites != 0.0f || p.blacks != 0.0f;
  glUniform1f(u_out_.tone_on, tone_on ? 1.0f : 0.0f);
  glUniform1f(u_out_.highlights, p.highlights);
  glUniform1f(u_out_.shadows, p.shadows);
  glUniform1f(u_out_.whites, p.whites);
  glUniform1f(u_out_.blacks, p.blacks);
  glUniform1f(u_out_.fade, p.fade);
  // U-1（期 B 从 u_film_ 挪来）：与 passStyle 的 u_style_.split/mono 同一对参数
  glUniform2f(u_out_.split, p.split_warm, p.split_cool);
  glUniform1f(u_out_.mono, p.monochrome);
  // 期 B：对比度只有引擎 1（sigmoid）需要 —— 引擎 0 的 `[4]` 已由 filmic 曲线系数承担
  //（updateFilmicEV），在这里再乘一次就是双重对比度 ⇒ 引擎 0 下发 1.0（恒等、零回归）。
  // 判据 = `engine_slot >= 0.5`（与 filmSource 的引擎选路同一口径；[98] 是 float 槽）。
  glUniform1f(u_out_.contrast_out, p.engine_slot >= 0.5f ? p.contrast : 1.0f);
  // ── SG-1 柔光（soft glow）：生效值下发（与 kFsOut 逐字同段）────────────
  // uGlowOn = glow_src != 0（[95] > 0 且共享金字塔链成功）⇒ 关闭时 shader 整段跳过。
  // uGlowTexel 用 halo_tmp_*（柔光图写在 tex_halo_tmp_ 上，1/2 分辨率）：glowFetch 的
  // 手写双线性需要它把 UV 换成像素坐标（shader 内不做除法）。
  // ⚠ max(1, ...) 兜底：[95] = 0 时 halo_tmp_* 可能从未分配（0）⇒ 1/0 = inf。
  glUniform1f(u_out_.glow_on, glow_src != 0 ? 1.0f : 0.0f);
  glUniform1f(u_out_.glow_strength, std::clamp(p.glow_strength, 0.0f, 1.0f));
  glUniform2f(u_out_.glow_texel,
              1.0f / static_cast<float>(std::max(1, halo_tmp_w_)),
              1.0f / static_cast<float>(std::max(1, halo_tmp_h_)));
  // M4：display 域 LUT（同一 pass 内，不额外增加 pass；斑马纹判定排在 LUT 之后，
  // 与用户实际看到的画面一致）
  applyPendingLut();
  lut_strength_ = p.lut_strength;
  bindLut(u_out_.lut, u_out_.lut_on, u_out_.lut_strength, u_out_.lut_dmin,
          u_out_.lut_dinv, u_out_.lut_size, p.lut_strength);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

}  // namespace filmkit
