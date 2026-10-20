#pragma once

#include <GLES3/gl32.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "../film/FilmParams.h"
#include "../film/SigmoidParams.h"
#include "OutputBitDepth.h"  // 交付位深单一真源（8bit JPEG / 10bit HEIF，卡 H2）

namespace filmkit {

// NN tile 推理回调（v0.10.102）：Bridge.cpp 注入（JNI 调 NnInferEngine.inferTile）。
// in/out 各为 512×512×4ch NHWC float（packed Bayer 空间序）。返回 false = 推理失败
// （调用方整张回退 P0 原链 —— 照片优先红线）。进程级单槽：预览/拍照实例共用，
// Bridge 侧保证只在拍照合成调用栈内被调（JNIEnv 契约见 Bridge.cpp 注释）。
using NnInferFn = bool (*)(const float* in, float* out);

// delegate 信息回调（v0.10.102）：返回 "gpu"/"cpu"（Bridge 侧缓存的静态缓冲），
// NN pass 成功后取一次记入日志。与 NnInferFn 同槽生命周期。
using NnInfoFn = const char* (*)();

// ── 噪声模型（C 专项，2026-09-29）────────────────────────────────────
// 传感器噪声 = read + shot：var_c(x) = S_c·x + O_c，x = 渲染线性域信号
// （黑阶后 / 白阶归一，[0,1]，与 DNG NoiseProfile 同域），WB 前。
// 来源 = Camera2 SENSOR_NOISE_PROFILE（每 ISO 一组，MGC 语义：4 通道 × (S,O)
// CFA 相位序），X300 实测与写出的 DNG 51041 tag 同源同域（S≈7e-4 @ISO1970）。
// 通道序对外固定 R,G,B（G = 两绿相位均值）。
struct NoiseModel {
  float S[3] = {0.f, 0.f, 0.f};
  float O[3] = {0.f, 0.f, 0.f};
  bool valid() const { return S[0] > 0.f || S[1] > 0.f || S[2] > 0.f; }
  // 方差折减（融合 N 帧：var/N ⇒ 系数 /N）
  NoiseModel scaled(float k) const {
    NoiseModel m;
    for (int c = 0; c < 3; ++c) {
      m.S[c] = S[c] / k;
      m.O[c] = O[c] / k;
    }
    return m;
  }
  // WB 传播（乘性增益：shot ×g、read ×g²；x' = g·x 域）
  NoiseModel afterWb(const float gains[3]) const {
    NoiseModel m;
    for (int c = 0; c < 3; ++c) {
      m.S[c] = S[c] * gains[c];
      m.O[c] = O[c] * gains[c] * gains[c];
    }
    return m;
  }
};

// 降噪系数（模型 → shader 的 (A,B)，即 σ²(x) = A·x + B，单位方差域；
// 强度系数 k 已折入：A = S'·k², B = O'·k²）。P0 = mosaic 域（WB 后），
// P4 = 同一域（LSC 增益在 shader 内按格点传播）。全部 3 通道（R,G,B）。
struct NoiseCoef {
  float p0a[3] = {0.f, 0.f, 0.f}, p0b[3] = {0.f, 0.f, 0.f};
  float p4a[3] = {0.f, 0.f, 0.f}, p4b[3] = {0.f, 0.f, 0.f};
  // mismatch / tile-RMS 用：k=1（与降噪档位无关）的 WB 后 (A,B)。legacy 回退时
  // mma=0、mmb=ns²/2 —— 使 shader 的 var(ref)+var(alt) = ns²，与旧常数 σ 判据
  // 一致（2026-09-29 真机回归：模型无效时这里曾传全零，m≡1 → neff=1 融合失效）。
  float mma[3] = {0.f, 0.f, 0.f}, mmb[3] = {0.f, 0.f, 0.f};
  float k_p0 = 0.f, k_p4 = 0.f;   // 强度乘子（日志）
  // v0.10.108 修正：强度 <1 不再缩小 σ。σ 报小 ⇒ bilateral 的 d=(c−c0)/σ 被放大 ⇒
  // w=exp(−0.5d²) 指数级塌掉 ⇒ 降噪几乎归零（真机实测：降噪 0.45 时盘沿亮部
  // σY 4.48 = Filmix 2.6×/PC 4.9×，且 nzPostP90≈nzPreP90「最噪的 10% 没被降噪」）。
  // 现在 σ 恒用真实噪声，强度 <1 由输出混合承担；强度 >1 仍作 σ 乘子（更激进降噪）。
  float mix_p0 = 1.f, mix_p4 = 1.f;
  float ns_typ = 0.f;             // 中灰典型 σ（WB 后 G 通道，单帧；resFade/日志）
  bool model_src = false;         // true = Camera2 模型，false = legacy σ 回退
};


// RAW 域高光重建（darktable highlights / "inpaint opposed"）诊断结果。
// chroma = 全图色度校正量（darktable `chrominance`），cnt = 参与统计的样本数
// （darktable 的 100 样本闸）。blocks = 有裁剪 photosite 的块数（膨胀前）。
struct HlRawResult {
  bool ok = false;
  float chroma[3] = {0.f, 0.f, 0.f};
  float cnt[3] = {0.f, 0.f, 0.f};
  // v0.10.122 诊断：chrominance 的分子（Σ(实测−对立外推)，host 双精度累加）。
  // 与 cnt 一起判定"引擎 chroma 比原版公式偏大 3.4×"是样本集合问题还是 refavg
  // 问题 —— 离线 tools/hl-opposed-check.py 给出同一口径的对照值。
  double sum[3] = {0.0, 0.0, 0.0};
  // 2026-10-04：全局兜底配对比例（Pass3.5 ref 场按样本占比 w 加权的全图 R/G、B/G）。
  // 用途 = Pass4 在局部块无有效参考（大面积饱和区）时退到它，否则那片整块漏修。
  // w_sum = Σw（权重和，用于判定"全图几乎无有效参考"→ 退中性）。
  float rg_g = 0.f, bg_g = 0.f;      // 池化比（allSat + 普通 G 路径第 3 级兜底用）
  double w_sum = 0.0;
  // 2026-10-04 v0.10.139 三级参考诊断：中尺度（1/128 网格）与全图兜底各自的命中
  // 格数占"32 块局部参考不足的格"的百分比（两者之和 = 100）。真机据此看"天空区
  // 到底用了哪一级"。口径：按 ref 格（每格 1024 CFA 像素）计，不含插值边界效应。
  float ref2_hit = 0.f, refg_hit = 0.f;
  int blocks_x = 0, blocks_y = 0;
  float ms = 0.f;
  // v0.10.169：apply 支实际下发的 uGRecon（0/1），进 raw.render 的 hlGRecon 字段 ——
  // 真机 A/B 时用它确认"这一张到底是哪一支"。
  int grecon = 1;
  // v0.10.179：apply 支实际下发的 uRbRecon（0/1），进 raw.render 的 hlRb 字段。
  int rbrecon = 0;
};

// 共享后段渲染器：SurfaceTexture(OES) → 场景线性 → 胶片曲线 → 输出。
// pass1 采用系统 SurfaceTexture 路径（参照 PhotonCamera）；
// M2 RAW 路径在此层替换 pass1，pass2/3 复用。
class FrameRenderer {
 public:
  FrameRenderer() = default;
  ~FrameRenderer();

  FrameRenderer(const FrameRenderer&) = delete;
  FrameRenderer& operator=(const FrameRenderer&) = delete;

  bool init();  // 编译程序（须有当前上下文）
  void release();

  void setOesTexture(GLuint tex) { oes_tex_ = tex; }

  // 预览降载（v0.10.93）：拍照期间把预览链的**中间渲染**缩到 s×（每维），输出
  // pass 仍上屏全窗口（RGBA8 中间 + glBlitFramebuffer 线性升采，见 submitFrame）。
  // 进程级 static（.cpp）：预览 / 拍照两个实例共享一个快照，任意线程可调——
  // 切换是帧级生效（下一帧 submitFrame 按新值建目标），拍照结束设回 1.0 立即复原，
  // 无需重建 Camera2 session。成片链（submitStill/submitRaw/submitRawBurst）不经过
  // submitFrame，不受影响（画质红线）。
  static void setPreviewScale(float s);   // clamp [0.25, 1.0]
  static float previewScale();

  // 诊断统计（v0.10.166）：prefs `diag_stats`（**默认开**）= 在成片链的 5 个阶段
  // （D3-camrgb / D4-scene / HR-filmsrc / FILM-out / D5-out-u8）把色度统计量
  // **在 GPU 内归约**成 16 个 float32，位打包成 8×2 RGBA8 后用 ES 3.0 唯一保证
  // 可用的 `GL_RGBA + GL_UNSIGNED_BYTE` 读回 64 字节，逐阶段写一条 `diag.stats`。
  // 上一版（v0.10.163 `diag_dump`）把渲染纹理挂到诊断 FBO 读浮点图像，真机上
  // 读回失败且破坏了渲染状态（成片全黑），已彻底删除。
  // 关闭时全链零额外开销（每个统计点一次 relaxed atomic load）。任意线程可调。
  static void setDiagStats(bool on);
  // gainmap 数学版本（v0.11.50）：0 = PC 版（offset 1e-4）/ 1 = libultrahdr 官方版
  // （offset 1e-7 + 暗部钳位）。供真机 A/B —— 离线对比用的是近似 SDR（Reinhard），
  // 结论只在"两版相对行为"上成立，绝对效果必须真机实测。
  static void setGainmapMath(int mode);
  static int gainmapMath();

  // 自动曝光（v0.11.61）：本张 RAW 的 P50/P99.9 ⇒ 渲染端 EV（中灰锚定 + 高光约束，
  // 高光优先）。prefs `ae_auto`（默认开），进程级 static、任意线程可调。
  // 关 = 完全不介入（ev 恒 0，与 v0.11.60 行为逐位一致）。
  static void setAutoExposure(bool on);
  static bool autoExposure();

  // NN 推理回调注入（v0.10.102，见 NnInferFn 注释）。幂等，Bridge 在每次
  // submit 入口重设（顺带刷新其 JNIEnv 快照）。
  static void setNnInferFn(NnInferFn fn);
  static NnInferFn nnInferFn();
  static void setNnInfoFn(NnInfoFn fn);
  static NnInfoFn nnInfoFn();

  /// 静拍 YUV 解码矩阵：0 = BT.601（默认），1 = BT.709
  void setYuvMatrix(int m) { yuv_matrix_ = m ? 1 : 0; }

  // 3D LUT（M4 §6）：display 域 LUT，域感知插桩在**输出 OETF 之后**（kFsOut 与
  // kFsHist 同源，保证直方图仍是"用户实际看到"的分布）。可在任意线程调用 ——
  // 数据暂存，渲染线程在下一帧上传（此处无上下文，不能直接建纹理）。
  //   data —— RGB 交错，顺序与 .cube 一致（红最快），长度 size³×3
  //   size ≤ 1 或 data 为空 = 关闭（A/B 通路）；dmin/dmax = .cube 的 DOMAIN
  void setLut(const float* data, int size, const float dmin[3], const float dmax[3]);

  // 预览帧：OES 纹理（Kotlin 侧已 updateTexImage）→ 渲染到当前窗口面。
  //   st      —— SurfaceTexture.getTransformMatrix()（列主序 16 float）
  //   rot     —— 传感器旋转 90° 步进（0..3）
  //   sx/sy   —— center-crop 缩放（由调用方按源/目标宽高比计算）
  bool submitFrame(const float* st, int rot, float sx, float sy, int dst_w,
                   int dst_h, const FilmParams& p, bool fill_test);

  // 静拍：全分辨率 YUV（CPU 转 RGB888 上传，避开 RG8 TexSub 驱动问题）
  // → 胶片链 → RGBA8 读回。旋转后尺寸 = (rot 为奇数 ? (h,w) : (w,h))。
  // ten_bit（卡 H2）：true = 成片按**10bit 交付档**出（tex_cap_ 用 GL_RGB10_A2、
  //   readback 用 GL_UNSIGNED_INT_2_10_10_10_REV、dither 除数 1023）——字节流仍是
  //   4 B/像素（packed uint32，位序见 OutputBitDepth.h），由 Kotlin 侧解包。
  //   false = 现有 8bit 链（**逐位不变**，JPEG 路径的零回归守卫）。
  bool submitStill(const void* y, const void* u, const void* v, int w, int h,
                   int y_stride, int uv_stride, int uv_pix, int rotation,
                   const FilmParams& p, std::vector<uint8_t>* out_rgba,
                   bool ten_bit = false);

  // 编辑器通路（E1-3，2026-10-08）：**已经过 OETF 的成片**（RGBA8，显示域 sRGB，
  // 顶行在前）→ 只重跑 OETF 之后的三段（3D LUT → 颗粒 → TPDF dither）→ RGBA8 读回。
  // 域约定（ADR-7）：内部**不插任何 OETF/EOTF**；**绝不复用 submitStill** ——
  // 那条路会对成片再跑一遍 filmic ⇒ 双重影调映射（画面变暗变灰，报告 R1）。
  // 只读 p 的 grain_amount[26]/grain_size[27]/lut_strength[34]，其余槽位一律不影响
  // 输出（ADR §5.1 的 I-2：同一输入 + 只改无关槽位 ⇒ 输出逐像素一致）。
  // 必须在该实例的渲染线程（有 current context）上调用。
  //
  // ── E2-12 裁剪 + 90° 步进旋转（ADR-16；几何变换，**不进 FilmParams**）──────
  //   out_w/out_h —— **输出**尺寸（≠ w/h 时即裁剪改变宽高比；Kotlin 侧按裁剪矩形
  //                  的像素尺寸 + 旋转交换算好传入）。w/h 始终 = 输入位图尺寸。
  //   crop_uv[4]  —— {x0, y0, cw, ch}，**原图**归一化 UV 子矩形（原点 = 左上，
  //                  y 与 Bitmap 行序同向 = 与 UI 裁剪框同向）。nullptr = 全幅
  //                  （零回归：不裁剪 + rot=0 ⇒ 输出与输入 max|Δ| ≤ 1 LSB）。
  //   crop_rot    —— 顺时针 90° 步进（0..3），作用于**裁剪后**的画面。
  // 裁剪在**引擎输入侧**做（shader 内 UV 变换）：后续处理（暗角 / 局部调整 /
  // LUT / 颗粒）一律继续用 vUV0（= 裁剪后的归一化坐标）⇒ 暗角自动相对裁剪后
  // 画面居中、局部调整中心自动落在用户拖的位置。**若改为最后裁输出位图，
  // 暗角会跑到画面外、局部调整中心会偏**（ADR-16 的核心裁决）。
  bool submitDisplay(const void* rgba8, int w, int h, int out_w, int out_h,
                     const float crop_uv[4], int crop_rot, const FilmParams& p,
                     std::vector<uint8_t>* out_rgba);

  // RAW 静拍（M2-3）：u16 Bayer（R16UI 整型纹理 + texelFetch）→ 黑阶扣除
  // → 白阶归一 → WB（asShotNeutral）→ demosaic（CFA 相位）→ CCM
  // → 场景线性，汇流 pass2/3 胶片链 → RGBA8 读回。
  //   black_phase —— 按相位 (0,0)(1,0)(0,1)(1,1) 的四通道黑阶
  //   neutral*    —— JPEG_NEUTRAL_COLOR_POINT（asShotNeutral），增益=其倒数 G 归一
  //   ccm9        —— sensor→sRGB 3×3 行主序；nullptr = 单位阵
  //   demosaic    —— 0 = bilinear 单 pass（速度档）；1 = RCD 三段管线（质量档，M3-1）
  //   noise_strength —— 降噪强度 0–2（C 专项滑杆：0 = 关（A=B=0 精确跳过）、
  //                  1 = 真实 σ、2 = 2×σ；仅 RCD 路径生效，bilinear 保持纯净基线）
  //   noise_model —— 6 float [S_r,S_g,S_b,O_r,O_g,O_b]（C 专项，Camera2
  //                  SENSOR_NOISE_PROFILE，[0,1] 归一化 raw 域）；nullptr/非法 = 无效
  //   noise_sigma —— 归一化域噪声 σ（模型无效时的 legacy 回退；≤0 时引擎内估计）
  //   lsc/lsc_cols/lsc_rows —— 镜头阴影增益网格（RGB 交错，cols×rows，(0,0)=左上；
  //                  nullptr 或尺寸 ≤1 = 恒等，即 LSC 关闭（A/B 对照））
  //   nn_dn —— v0.10.102：NN 降噪 pass（PMRID，融合后/RAW 域、去马赛克前）。
  //          true 时 P0 空域降噪被 NN 替代（直读展开）；NN 失败自动回退 P0 原链。
  // ── HDR gainmap 读回（v0.11.50）────────────────────────────────────
  // 成片链跑完后调用：把 1/2 分辨率的 8bit gainmap 读回 CPU。
  // out 由调用方分配，长度 = gainmapW()*gainmapH()；返回 false = 未生成/读回失败。
  // 只在 HDR 交付时调用（普通 JPEG 不走这条，零额外开销）。
  bool readGainmap(std::vector<unsigned char>* out);
  int gainmapWidth() const { return gainmap_w_; }
  int gainmapHeight() const { return gainmap_h_; }

  bool submitRaw(const void* raw, int w, int h, int row_stride_px, int cfa,
                 const float black_phase[4], int white_level,
                 float neutral_r, float neutral_g, float neutral_b,
                 const float* ccm9, const float* lsc, int lsc_cols,
                 int lsc_rows, int rotation, const FilmParams& p, int demosaic,
                 float noise_strength, const float* noise_model,
                 float noise_sigma, bool nn_dn,
                 std::vector<uint8_t>* out_rgba, bool ten_bit = false);

  // RAW 多帧融合（MFNR 阶段一，v0.9.0）：count 帧 u16 Bayer → Bayer 域
  // tile 对齐（Align.cpp，CPU 金字塔搜索）+ 置信度加权时域均值 → P0 降噪位
  //（demosaic 前）→ 与 submitRaw 完全相同的 RCD/胶片链 → RGBA8 读回。
  //   frames[0] = 参考帧（DNG 底片同一帧）；count 必须 == 4（融合 shader 固定
  //   4 输入）。元数据统一取帧 0（burst 恒曝光，逐帧黑阶/WB 视为一致，
  //   mfnr.capture 日志可核对）。noise_model/noise_sigma/demosaic 语义同 submitRaw。
  bool submitRawBurst(const void* const* frames, int count,
                      const int* row_strides_px, int w, int h, int cfa,
                      const float black_phase[4], int white_level,
                      float neutral_r, float neutral_g, float neutral_b,
                      const float* ccm9, const float* lsc, int lsc_cols,
                      int lsc_rows, int rotation, const FilmParams& p,
                      int demosaic, float noise_strength,
                      const float* noise_model, float noise_sigma,
                      float ref_noise_scale, bool use_dft, bool align_l1,
                      bool align_ab, bool p0_sub, bool nn_dn,
                      std::vector<uint8_t>* out_rgba, bool ten_bit = false);

  // 预览实时直方图（M3）：胶片输出纹理 → 显示域亮度（OETF 同 kFsOut）→ 256×96
  // 下采样 → 读回统计到 out[0..n-1]（n 建议 256）。渲染线程调用；无可用帧返回 false。
  bool histogram(int* out, int n);

  int64_t frameCount() const { return frames_.load(std::memory_order_relaxed); }
  int sceneW() const { return scene_w_; }
  int sceneH() const { return scene_h_; }

  // ── 曝光决策专项 v2：高光保护判据（2026-10-02）────────────────────────────
  // sceneW99/sceneW999/sceneWMax = 场景高光分位（log2，相对中灰 0.1845），**测于
  // kFsFilm 链首乘 uExposure 之前**（v0.10.31 已核对统计域）⇒ camera 侧可直接与
  // "将要施加的提亮 ev"相加，判断提亮后高光是否会撞 RAW 饱和白点（2.4383）。
  // highlightClipBlocks = 成片高光重建命中的块数（64×48 网格），用于离线核对
  // "欠曝是否真的把裁剪压下来"。渲染线程写、camera 侧读，单字读取（决策/诊断用途）。
  float sceneW99() const { return last_w99_; }
  // v0.10.113：**成片链**最后一次的 P99.9（log2 相对中灰，EV 前）。与 sceneW999()
  // 的区别是关键的：预览链走 OES（ISP 已 tonemap 的流），与 RAW 归一化域**不是一个
  // 尺度**（同场景实测预览 2.09 / 成片 2.674，差 0.58EV 且逐次不同）——曝光判据用
  // 预览值必然既不准也不稳（2026-10-03 真机教训）。判据只认这个成片域的值。
  float stillW999() const { return last_still_w999_; }
  float sceneW999() const { return last_w999_; }
  float sceneWMax() const { return last_wmax_; }
  int highlightClipBlocks() const { return hr_blocks_; }

 private:
  bool ensureTargets(int w, int h);
  bool ensureRcdTargets(int w, int h);
  // tex_amaze_（源几何）→ 成片几何 tex_work_（D3）。与 kFsRcdChroma 同一几何口径。
  void passAmazeUp(int dst_w, int dst_h, int rot);
  bool ensureDnTarget(int w, int h);
  bool ensureChromaMst(int w, int h);
  bool ensureDefringe(int w, int h);
  // 色差域中值滤波（v0.10.196）：对标 darktable color_smoothing
  bool ensureColorSmooth(int w, int h);
  // MFNR 阶段一（v0.9.0）：raw 尺寸的 burst 纹理 / 金字塔 / 融合目标
  bool ensureMfnrTargets(int w, int h);
  // u16 Bayer 上传（submitRaw / submitRawBurst 共用）：rowStride 跳行 + 失败日志
  bool uploadRawTex(GLuint tex, const void* raw, int w, int h,
                    int row_stride_px);
  // raw_tex_ 的建/复用（尺寸变化时重建），返回纹理 id（0 = 失败）
  GLuint ensureRawTex(int w, int h);
  // 金字塔 pass：raw → 1/2 灰度（kFsMfnrPyr，sqrt 预编码）；再逐层 2× box（kFsMfnrDown）
  void passMfnrPyr(GLuint raw_tex, int cfa, const float black_phase[4],
                   float white_level, const float gains[3]);
  void passMfnrDown(GLuint src, GLuint fbo, int sw, int sh);
  // 阶段二 robust merge（v0.10.0）：Night Sight 式 mismatch map —— per-tile
  // 对齐后残差 mean(d²) → m = d²/(d²+s·σ²)，渲染到 tex_mis_（RGBA8 tiles 网格，
  // R/G/B = 帧 1/2/3）。CPU 读回算 w = max(1−c·m, 0) 写回位移场 z 通道。
  // C 专项：σ² 由常数改为逐像素模型（var(r) + var(r_alt)），a/b = k=1 的 WB 后
  // 系数（NoiseCoef::mma/mmb —— legacy 回退也保证非零，见其注释）。
  void passMfnrMismatch(int cfa, const float black_phase[4], float white_level,
                        const float gains[3], const float a[3],
                        const float b[3], float s_const);
  // 位移场 + 4 帧 → 置信度加权均值 → tex_fused_（R16F mosaic，线性化+WB 域）
  void passMfnrFuse(int w, int h, int cfa, const float black_phase[4],
                    float white_level, const float gains[3]);
  // P0 变体：从 tex_fused_（float mosaic，已线性化+WB）读入的同色 bilateral
  // off_tex = nullptr 时用正常位移场 tex_off_；A/B 诊断传 tex_off_ab_。
  // subpixel = true 走逐通道网格亚像素采样（uSub=1，v0.10.75 杠杆 B）。
  // fused_tex = v0.10.102：P0 输入纹理替换（NN pass 的输出 tex_fused_nn_，
  //   0 = 默认 tex_fused_）。allow_ts = false 强制跳过 Ts 变体（NN 直读展开时
  //   时域样本引用的是未降噪的原始帧，与 NN 后的参考域不一致，且无意义）。
  // wclamp = v0.10.167：HR 前置的值域保护（输入是 scene-referred、可 >1 的 HR
  //   输出时置 true —— 权重/σ 用 clamp(x,0,1)，信号仍用原值）。
  void passRawDenoiseFused(int w, int h, int cfa, const float black_phase[4],
                           float white_level, const float gains[3],
                           const NoiseCoef& nc, const GLuint* off_tex = nullptr,
                           bool subpixel = false, GLuint fused_tex = 0,
                           bool allow_ts = true, bool wclamp = false);
  // ── 降噪专项 A（2026-09-29 立项）：HDR+ §5 式 6/7 DFT 逐频收缩合并 ──────
  // passMfnrFuse 的并联路径（prefs mfnr_dft）：4 帧 Bayer mosaic → 按 CFA 平面
  // 独立合并（R/Gr/Gb/B 各自半分辨率网格上 16×16 tile、半重叠、修改升余弦窗），
  // tile 内 CPU 对齐位移取整到 mosaic 偶数（像素级对齐 = HDR+ 原版语义，平面域
  // 整数平移免相位校正），行/列 radix-2 FFT（spike v2 寄存器架构）→ 逐频收缩
  // Az=|Dz|²/(|Dz|²+cσ²)（c=8·n²/4²·2=256，σ²=A_c·RMS+B_c 由内核内 tile RMS
  // 归约代入）→ IDFT → 半重叠 overlap-add 累加进 tex_fused_（与 fuse 路径
  // 同域同格式，后段 P0/RCD/胶片链零改动）。mismatch/conf 权重按 HDR+ 原版
  // 不参与（逐频收缩自身鲁棒）。输出每 tile 的频率平均等效 neff（NS §3.3
  // "按实际合并更新 σ"），CPU 读回取均值供 P0/P4 折减与日志。
  bool ensureMfnrFftTargets(int w, int h);
  // 返回 false = compute 程序不可用（非 ES 3.2 设备），调用方回退 mismatch 路径。
  // frames/strides = 4 帧原始 u16 指针与行距（burst 数组直传，见实现内注释）；
  bool passMfnrFft(int w, int h, int cfa, const float black_phase[4],
                   float white_level, const float gains[3],
                   const float plane_a[3], const float plane_b[3],
                   float* out_neff,
                   float* out_ms);  // out_ms = {直传, dispatch, 收口+读回, GPU} 分段 ms
  // ── submitRaw / submitRawBurst 共享的成片链（P0 → RCD/bilinear → P4 →
  // filmic → 输出读回 → raw.render 日志）。P0/RCD 的输入域由 mfnr_fused 决定。
  struct ChainCtx {
    int w = 0, h = 0, dst_w = 0, dst_h = 0;
    int cfa = 0;
    const float* black_phase = nullptr;
    float white_level = 0.f;
    const float* gains = nullptr;
    const float* ccm = nullptr;
    const FilmParams* p = nullptr;
    int effective_rot = 0;
    bool rcd = false, dn = false;
    // AMaZE 档（demosaic == 2，v0.10.172）：CPU 侧跑 AMaZE（AmazeDemosaic.cpp），
    // 输入 = u16 RAW（amaze_raw / amaze_stride，未降噪）；输出 WB 域相机 RGB →
    // 上传 tex_amaze_ → passAmazeUp 旋转到成片几何 → 既有 passCcm → 胶片链。
    // amaze_lsc = 镜头阴影网格（可为 null = 恒等），CPU 侧在 WB 域乘。
    // ⚠ 未完成项：本档**不经过** P0（RAW 域 bilateral）/ HR 前置 —— 见
    //   docs/reports/amaze-REPORT.md「未完成」，高 ISO 下噪点会多于 RCD 档。
    bool amaze = false;
    const void* amaze_raw = nullptr;
    int amaze_stride = 0;
    const float* amaze_lsc = nullptr;
    float dn_k = 0.f;  // 降噪强度（日志）
    NoiseCoef nc;         // (A,B) 系数（P0/P4/mismatch，C 专项）
    NoiseModel nm;        // 原始噪声模型（WB 前、单帧；日志 nzS/nzO）
    float ns = 0.f;       // 典型 σ（融合折减后；resFade/日志）
    float ns_raw = 0.f;   // 单帧典型 σ（日志 nsHal / nsSng 兼容）
    bool lsc_ok = false;
    int lsc_cols = 0, lsc_rows = 0;
    float upload_ms = 0.f;  // t0 → 传参时刻（MFNR 时含上传+对齐+融合）
    bool mfnr_fused = false;
    // MFNR 等效帧数（噪声方差折减 ÷neff）。NN 输入增益要按**实际输入**的噪声
    // 标定：融合路径喂的是 tex_fused_（方差已折减），与 P0/P4 系数同口径。
    float neff = 1.f;
    // 同帧 A/B 诊断（v0.10.74）：同一次 burst 用**旧层**位移场再跑一遍 P0，
    // 与正常那遍比降噪倍数。四轮真机 A/B 全因 ISO/场景/手抖不同而作废，
    // 唯一干净的对照就是同一帧数据。
    bool align_ab = false;
    bool p0_sub = false;   // P0 亚像素采样（prefs p0_sub，v0.10.75）
    bool nn = false;       // NN 降噪 pass（prefs nn_dn，v0.10.102；执行结果在 dbg_nn_*）
    // E4-3 颗粒：输入数据的**内容指纹**（submitRaw/submitRawBurst/submitStill 填充；
    // finishStillChain / submitStill 用它派生颗粒种子）。0 = 无指纹（种子退化为尺寸派生）。
    // 为什么要内容指纹而不是时间戳：引擎内**没有任何时间源**（全仓 grep timestamp /
    // time() 零命中），而验收判据 6 要求"同参数两次导出逐位相同" —— 内容指纹同时满足
    // "同一张可复现"与"不同张不同图案"（darktable 用文件名 hash 达到同一目的）。
    uint32_t grain_fp = 0;
    std::string mfnr_kv;  // MFNR 时非空：追加进 raw.render
    // 卡 H2：10bit 交付档（HEIF）。true ⇒ 成片 tex_cap_ = GL_RGB10_A2、readback 用
    // GL_UNSIGNED_INT_2_10_10_10_REV、dither 除数 1023（三处同源，见 OutputBitDepth.h）。
    // 由 submitRaw / submitRawBurst 的 ten_bit 参数填充；submitStill 不走本结构。
    bool ten_bit = false;
  };
  void finishStillChain(const ChainCtx& c,
                        std::chrono::steady_clock::time_point t0,
                        std::vector<uint8_t>* out_rgba);
  // AMaZE 档（v0.10.172）：CPU 跑 AMaZE（src/render/AmazeDemosaic.cpp，多线程
  // tile 并行）→ LSC → 上传 tex_amaze_（源几何）。返回 false = 失败（调用方
  // 必须回退，照片优先红线）。demosaic == 2 才调用。
  bool runAmazeCpu(const ChainCtx& c);
  // ── NN 降噪 pass（v0.10.102）：PMRID TFLite ─────────────────────────────
  // 位置 = 融合后 RAW 域、去马赛克前（finishStillChain 的 P0 之前）。数据流：
  // kFsNnPack（mosaic→packed 4ch 半分辨率、除回 WB、×VST 959/256）→ readPixels
  // → CPU 组 tile（512/步进 496/边缘补零，quality_eval.py 口径）→ NnInferFn
  // 逐 tile 回调 → CPU ramp 融合（16px 线性）→ 上传 → kFsNnUnpack（÷VST、乘回
  // WB、展开 mosaic）→ tex_fused_nn_。返回 false = 失败（调用方回退 P0 原链，
  // dbg_nn_fail 置位）。nn_src 由调用方按路径填（见参数说明）。
  struct NnSrc {
    GLuint fused_tex = 0;  // fused 域输入（MFNR 路径 = tex_fused_；0 = 单帧 raw_tex_）
    int src = 0;           // 0 = fused 域（R16F，已扣黑阶+WB）/ 1 = 原始域（R16UI）
  };
  bool runNnPass(const ChainCtx& c, const NnSrc& src, const float gains[3],
                 int cfa, const float black_phase[4], float white_level);
  bool ensureNnTargets(int w, int h);
  // 16px 线性 ramp（quality_eval.py 的 linspace(0,1,16) 四边、对边反转），
  // 首次构建后常驻（ensureNnTargets 首次调用时构建）。
  void buildNnRamp();
  // WB 增益 / CCM / LSC 纹理准备（两条静拍路径共用）
  struct RawCommon {
    float gains[3] = {1.f, 1.f, 1.f};
    float ccm[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    bool lsc_ok = false;
    int lsc_w = 1, lsc_h = 1;
  };
  // wb_r/g/b = FilmParams 的用户 WB 补偿**原始值**（G 分量未归一）。RAW 链在本函数
  // 内换算成 (wb_r/wb_g, 1, wb_b/wb_g)；预览 OES / YUV 静拍的 uWb 走同一换算
  // （wbCompGNorm）—— 三条链因此同口径（第 2 期 S13）。
  RawCommon prepareRawCommon(float neutral_r, float neutral_g, float neutral_b,
                             float wb_r, float wb_g, float wb_b,
                             const float* ccm9, const float* lsc, int lsc_cols,
                             int lsc_rows);
  // 降噪参数（C 专项）：模型（或 legacy σ）→ P0/P4 的 (A,B) 系数（含强度两档
  // 与 WB 传播）。model 无效时退 legacy：σ(x) = ns·k·√(0.2+4x) 的等效 (A,B)。
  static void denoiseParams(float strength, const NoiseModel& model,
                            const float gains[3], float noise_sigma,
                            NoiseCoef* out);
  void passOES(int dst_w, int dst_h, const float* st, int rot, float sx,
               float sy, const FilmParams& p);
  void passStill(int dst_w, int dst_h, int rot, const FilmParams& p);
  void passRaw(int dst_w, int dst_h, int rot, int cfa,
               const float black_phase[4], float white_level,
               const float gains[3], const float* ccm, const FilmParams& p);
  // RCD 质量档（M3-1）：方向场 → G@RB（比值修正）→ 色度填充（伪彩限幅）
  void passRcdDir(int w, int h, int cfa, const float black_phase[4],
                  float white_level, const float gains[3], bool mosaic);
  void passRcdG(int w, int h, int cfa, const float black_phase[4],
                float white_level, const float gains[3], bool mosaic,
                float g_orig);
  void passRcdChroma(int dst_w, int dst_h, int rot, int cfa,
                     const float black_phase[4], float white_level,
                     const float gains[3], const float* ccm, bool mosaic,
                     float cd_fix, float g_orig);
  // P3 拆双 pass（v0.10.180，FilmParams[58] rcd_p3_split，仅 =1 时调用）：
  // 3a 对角段填充（= 原版 STEP 4.2）→ tex_rcd_a_（源几何，WB 域 RGB 无 LSC）；
  // 3b G 位轴向填充（= 原版 STEP 4.3，交叉轴 ±1 从 tex_rcd_a_ 读已填充色，
  // 恢复四正邻双轴估计）→ 乘 LSC 写 tex_work_（D3，与旧 P3 输出域一致）。
  // 机理：单 pass 版并行读不到本 pass 内对角段结果 ⇒ B 缓 G 陡错位放大 2×
  //（品红峰 +43.8 → +85.6 WB 域，docs/research/11 号 §8）。
  void passRcdChromaA(int w, int h, int cfa, const float black_phase[4],
                      float white_level, const float gains[3], bool mosaic,
                      float cd_fix);
  void passRcdChromaB(int dst_w, int dst_h, int rot, int cfa,
                      const float gains[3]);
  // P3.5（第 4a 期 S14）：**D3 → D4** 独立 CCM pass（tex_work_ → tex_scene_），
  // 纯逐像素映射（kVertRot + uST 单位阵，frag 用 gl_FragCoord 定位）。
  // 只在 RCD 档调用（双线性档 passRaw 内联 CCM）。S14 的 clamp 收口在 shader 内。
  // v0.10.170：src 可指定输入纹理（横向色差校正输出 tex_ca_）；0/tex_work_ = 原行为。
  void passCcm(int dst_w, int dst_h, const float* ccm, GLuint src = 0);
  // 横向色差估计（v0.10.170）：对 1/8 降采样图做逐环带一维径向 Lucas-Kanade
  //（d·r̂ = k·r 的过原点最小二乘），输出 R/B 的径向系数 kr/kb。只用强梯度像素。
  // 返回 false = 梯度能量不足（低纹理/读回异常）⇒ 调用方不动作。
  bool estimateCaRadial(const float* ds, int dw, int dh, int full_w, int full_h,
                        float* kr, float* kb, float* raw_kr, float* raw_kb);
  // M3-2 降噪：P0 RAW 域同色 bilateral（输出线性 mosaic）；P4 色度平滑。
  // C 专项起值域 σ 由 (A,B) 模型逐像素给出（nc，强度已折入系数）。
  void passRawDenoise(int w, int h, int cfa, const float black_phase[4],
                      float white_level, const float gains[3],
                      const NoiseCoef& nc);
  // 档 A（v0.10.165）伪彩感知色度降噪：moire = FilmParams[48]（0 = 关）。>0 时
  // shader 用逐像素伪彩强度放大 σc，并在 pass 内做一次强度图统计读回（诊断字段）。
  // v0.10.174：cg_k/cg_scope = 强边缘色度门（FilmParams[51]/[52]），**逐像素门 +
  // 有界放宽**（v0.10.173 的绝对 cap 0.2 全图生效 ⇒ 真机色渗事故）。cg_k > 0
  // 时 P4 的 a 通道改输出门控开度 ⇒ 同一统计读回即 cgMean/cgCov（= 门控覆盖率）。
  void passChromaDenoise(int dst_w, int dst_h, int rot, const NoiseCoef& nc,
                         float moire, float cg_k, float cg_scope,
                         GLuint src_tex = 0);
  // D 专项（v0.10.53）：色度多尺度降噪（半分辨率 à trous 5 级）
  // v0.10.174：cg_k = 同一门，但**只在最细两级**（uStep ≤ 2 = 2/4 px）生效
  // —— 大尺度级（16/32 px）抹掉的是真实色度结构，是"大片色块"的唯一来源。
  bool passChromaMultiScale(int dst_w, int dst_h, const NoiseCoef& nc,
                            GLuint src_tex = 0, float cg_k = 0.f);
  // 逆光边缘紫边抑制（v0.10.134，RawTherapee Defringe 同形态）：P4 后、D-1 前。
  // 输入 tex_dn_，成功时输出 tex_df_（返回 true）；失败/关闭返回 false（调用方
  // 沿用 tex_dn_）。Lab 域"色度相对局部基线偏离"检测 + a 通道色相门 + 低偏离
  // 邻居加权替换，L 通道零损失。
  // v0.10.173：df_gate = 判据的梯度自适应强度（FilmParams[53]，0 = 关 ⇒ 判据
  // 与 v0.10.172 逐位相同）。>0 时阈值 = uThreshK·uChromaAvg/(1 + df_gate·|∇L|)
  // ⇒ 强亮度边缘附近的色度偏离更容易进入校正区。
  // src: 输入纹理（默认 tex_dn_ = P4 输出；替换档 [54]=1 时传 tex_pc3d_）
  bool passDefringe(int dst_w, int dst_h, float df_gate = 0.f, GLuint src = 0);
  // 色差域 3×3 中值（darktable color_smoothing）：输入任意 D4 域纹理，
  // 输出 tex_cs_。失败返回 false（调用方回落 src）。
  // to_tmp（v0.10.208）：输出目标 —— false = tex_cs_（PC3D **之前**的前置调用）；
  // true = tex_cs_tmp_（PC3D **之后**的后置调用）。两次调用是必要的：前置治
  // RCD 产生的窄带伪彩，后置治 **PC3D 自身产生**的色度过冲（真机实测：D4 域
  // 该位置 ch 平缓无过冲，而成片有 52 vs 两侧 10/29 的过冲 ⇒ 过冲产生于 PC3D）。
  bool passColorSmooth(int dst_w, int dst_h, GLuint src, bool to_tmp = false);
  // ── PC ESD3D2 完整移植（v0.10.175）─────────────────────────────────────
  // 输入 tex_scene_（CCM 后的场景线性 RGB，与 P4 同域），成功时输出 tex_pc3d_
  // 并把 P4/D-1 的输入源切到它；返回 false = 未执行（调用方沿用 tex_scene_，
  // 与加本模块之前**逐位相同**）。
  // 结构完全照抄 PhotonCamera 的 ESD3D2.Run（ESD3D2.java:121-176）：
  //   ① scale = clamp(round(clamp(sqrt(0.5·S+O)/noiseTarget,1,4)),1,4)
  //   ② scale > 1：gaussdown(scale) → PC3D(低分辨率, MOIRE=0, 噪声/scaleF·0.75)
  //                → guidedUpsample(低分辨率结果, 全分辨率原图) → 全分辨率
  //      scale = 1：PC3D(全分辨率, MOIRE=0)
  //   ③ 第二趟：PC3D(全分辨率, MOIRE=1.5)
  // pc3d_msize_override > 0 时强制 MSIZE（真机 A/B 拍板用；0 = 按 PC 公式）。
  // in_src（v0.10.205）：输入源覆盖 —— 0 = tex_scene_（原行为，逐位不变）；
  // 非 0 = 用该纹理（color_smooth 前置后的 tex_cs_）。**为什么需要**：PC3D 的
  // 色度平滑会把 1~3px 伪彩摊成 12~90px 宽带（真机实测），一旦摊开，任何
  // "两侧一致性"判据的探测点都落在带内 ⇒ 失效。把 color_smooth 提到 PC3D
  // **之前**，伪彩仍是窄带，判据才成立。
  bool passPc3d(int dst_w, int dst_h, const NoiseCoef& nc,
                const FilmParams& p, GLuint in_src = 0);
  // 横向色差校正（v0.10.170，镜头侧向色差 / lateral CA）：D3（RCD 去马赛克后、
  // passCcm 前）的相机 RGB 域。自动估计（无镜头配置文件）+ 径向位移校正，只重采样
  // R/B，G 逐位不动。返回 true 时 tex_ca_ = 校正输出（调用方把 CCM 源换成它）；
  // 返回 false = 关闭 / 估计失败 / 位移低于门限（tex_work_ 直通，逐位等于改动前）。
  bool passCaCorrect(int dst_w, int dst_h, float strength);
  bool ensureCaTargets(int w, int h);
  static constexpr int kChromaLevels = 5;
  // RAW 域高光重建（darktable highlights 模块 / "inpaint opposed"，2026-10-03）：
  // 在**去马赛克前**对线性 mosaic（已扣黑阶 + WB，白点 1.0）恢复被裁剪通道 ——
  // mask(3×3 photosite 块) → 膨胀 → 全图 chrominance 统计 → 对立外推应用。
  // v0.10.167 起本 pass 被**前置到 P0 之前**执行：输入 = P0 前的 mosaic，输出
  // tex_hl_（单通道 .r 布局）转交 P0 作为输入；RCD 读 P0 的输出 tex_mos_（含 HR
  // 修复），故 hr_src_override_ 前置路径下恒为 0。
  // stat_src/stat_raw（v0.10.123 起）：统计源与判定源的**独立旋钮**。
  //   v0.10.167 前 uStat 是死参数（三个 hl shader 只声明不读取）⇒ 改 src 即全改
  //   （exp21 翻车的结构性根因）。现在 mask/chrominance 走 stat_src，apply 走 src。
  // mono_src（v0.10.167）：源的布局 —— 1 = 单通道 R16F（值在 .r，tex_fused_ 布局），
  //   0 = RGBA16F 按 CFA 相位写槽位（tex_mos_ 布局）。只影响读取方式，不改判据。
  // grecon（v0.10.169）：kFsHlApply 内"自创机制"两支（G 三级池化比 + allSat 三通道
  //   改写）的总开关，来自 FilmParams[49] hl_grecon。1 = 现有行为（默认）；0 = 只留
  //   分支① 的 darktable opposed 外推（= PhotonCamera OpposedGL 口径），供真机 A/B。
  // rbrecon（v0.10.179）：clip 过渡带 R/B 满阱比例反推支的总开关，来自 FilmParams[57]
  //   hl_rb_recon。1 = 启用（替换语义，见 kFsHlApply 新分支注释）；0 = 默认，旧行为
  //   逐位不变。
  HlRawResult passHighlightRaw(int w, int h, int cfa, const float gains[3],
                               GLuint src, GLuint stat_src, int stat_raw,
                               int mono_src = 0, int grecon = 1, int rbrecon = 0);
  // M4-7 局部低频图：尺寸变化时重建（RGBA8 + LINEAR）。返回是否可用。
  bool ensureLocalTarget(int w, int h);
  // v0.10.93 预览降载：降档输出的 RGBA8 中间目标（LINEAR，供 blit 升采）。
  bool ensureOutScaleTarget(int w, int h);
  // 显示域直方图统计（kFsHist 自带 OETF，与公开的 histogram() 同一实现）。
  // stat_ch：统计通道 —— 0 = luma（UI 直方图，用户看的亮度分布）；
  //          1 = max 通道（filmic 自动 EV 的判据：filmic 内部按 max RGB 做归一，
  //              用 luma 会系统性偏低；darktable 的 auto-tune 同样取 max 通道）。
  // bypass_lut：程序性统计（自动 EV）不该被风格 LUT 改判据（UI 直方图仍含 LUT）。
  bool histogramFrom(GLuint src, int* out, int n, bool bypass_lut = false,
                     int stat_ch = 0, int stat_mode = 0);
  // 把 src 的"显示域亮度图"（OETF 同 kFsOut）渲染到 fbo（w×h）。
  // box_filter=false → kFsHist 单点采样（直方图统计用）；
  // box_filter=true  → kFsLocal 8×8 盒式平均（局部色调映射的低频图，见其注释）。
  // stat_mode=1 → 对数域统计编码（measureSceneStats 专用，见 kFsHist 的 uStatMode）。
  void renderLuma(GLuint src, GLuint fbo, int w, int h, float lut_strength,
                  bool box_filter, int stat_ch = 0, int stat_mode = 0);
  // ── 场景分位统计（v0.7.3：中位 → 分位）───────────────────────────────────
  // 服务 filmic 的自动 EV 标定。取 max 通道（见 histogramFrom 的 stat_ch），
  // **一次直方图算三个分位**：P0.5 → black relative exposure，P99.5 → white。
  // 测到就写 last_* 供日志；valid=false = 统计失败（调用方保持上一帧参数）。
  struct SceneStats {
    float p_lo = 0.0f, p_mid = 0.0f, p_hi = 0.0f;  // 线性（P0.1 / P50 / P99）
    // v0.10.27：P99.9（对数域统计才有分辨率，见 measureSceneStats）—— 高光感知
    // 白点判据的驱动量：占比 <1% 的窗/灯强光源 P99 看不见，P99.9 能。
    float p_hi999 = 0.0f;
    // v0.11.63：P99.5 —— 自动曝光的高光判据。0.5% 的像素允许超白点，与 PhotonCamera
    // legacy matcher 的 `maximumClippedFraction = 0.005`（raw_legacy_auto_exposure_solver）
    // 同口径；用 P99.9 会为 0.1% 的像素压全图 ~2EV（真机 2026-10-20 实测）。
    float p_hi995 = 0.0f;
    // v0.10.28：采样 max（最高非空桶，darktable autodetect 的 max 语义）——
    // 白点上探的主驱动量：小面积近饱和高光（白壶身镜面，占比 0.001% 级）
    // 任何分位都看不见，见 162012 真机定案。
    float p_max = 0.0f;
    bool valid = false;
  };
  SceneStats measureSceneStats(GLuint src);
  // ── filmic spline（darktable `dt_iop_filmic_rgb_compute_spline` 的 v3 分支）──
  // 公式出处与逐项对照见 .cpp；纯函数，可离线用同一组 EV 复算系数对拍。
  //
  // E4-4（2026-10-11）：**per-channel**。`m1..m5` 由 `float[3]`（段）扩为
  // `float[3][3]`（[通道][段]）：通道 0=R/1=G/2=B，段 0=toe/1=shoulder/2=中间线性段。
  // 依据 = darktable v3 里 toe 与 shoulder 的插值本来就是两个独立表达式
  // （`toe_log = (1-lat)·grey_log + lat·xmin` / `sh_log = (1-lat)·grey_log + lat·xmax`），
  // 只是共用一个 `lat` 值 ⇒ 拆成 `lat_toe`/`lat_sh` 各 per-channel 是**原版参数维度
  // 的自然展开**（三层乳剂的 toe/shoulder 位置不同 = 阴影/高光色偏的物理机制），
  // 不是自研机制。默认全 0.01（darktable 默认）⇒ 三通道系数逐位相同 ⇒ 与接线前
  // 输出逐位一致（A/B 通路纪律）。
  struct FilmicSpline {
    float m1[3][3] = {{0, 0, 0}, {0, 0, 0}, {0, 0, 0}};
    float m2[3][3] = {{1, 1, 1}, {1, 1, 1}, {1, 1, 1}};
    float m3[3][3] = {{0, 0, 0}, {0, 0, 0}, {0, 0, 0}};
    float m4[3][3] = {{0, 0, 0}, {0, 0, 0}, {0, 0, 0}};
    float m5[3][3] = {{0, 0, 0}, {0, 0, 0}, {0, 0, 0}};
    float lat_min[3] = {0.0f, 0.0f, 0.0f};  // toe_log（逐通道）
    float lat_max[3] = {1.0f, 1.0f, 1.0f};  // shoulder_log（逐通道）
    // E4-4：**实际参与求解**的 latitude（= clamp 后，逐通道）—— 诊断日志的
    // "生效值"直接取它，不取 FilmParams 的裸值（裸值可能越界/NaN）。
    float lat_sh[3] = {0.01f, 0.01f, 0.01f};
    float lat_toe[3] = {0.01f, 0.01f, 0.01f};
    float black_ev = -7.65f, white_ev = 4.56f, dynamic_range = 12.21f;
    float input_min = 0.0f, input_max = 1.0f;
    float output_power = 4.0f, black_disp = 0.0f, grey_log = 0.5f;
    float contrast = 1.0f;
    // v0.10.27：darktable contrast 滑杆参数（默认 1.0 = 中性）。自动白点高光上探
    // 时由 updateFilmicEV 按 power 前后比设定，补偿 gamma 回退；固定曲线恒 1.0。
    float contrast_param = 1.0f;
  };
  // E4-4：latitude 由标量扩为 per-channel（`lat_sh` = FilmParams[5..7]、
  // `lat_toe` = FilmParams[8..10]），调用方显式传入；内部 clamp 到 [0,1] 并回填
  // `sp.lat_sh`/`sp.lat_toe`（诊断用）。
  static FilmicSpline computeFilmicSpline(float black_ev, float white_ev,
                                          float contrast_param,
                                          const float lat_sh[3],
                                          const float lat_toe[3]);
  // ── U-2 编辑器链路胶片曲线（[5..10] latitude + [37][38] 黑白点；**显示域**）────────
  // 用户 2026-10-11"不统一的要统一掉，缺的补上"（参数统一方案 §9 的最后一批）。
  // 完整设计依据（为什么是"相对默认的差值"、中段斜率 c 的来源、与 [4] 的正交性、
  // [37][38] 的方向）逐条写在 .cpp 的 `computeEditorCurve` 上方；这里只声明形状。
  //
  // 为什么与 `FilmicSpline` 分开：那条是**拍照链路**的 log 域 spline（输入 = 场景线性，
  // 输出 = 线性显示域，端点 = black_disp/1.0）；这条是**编辑器链路**的显示域曲线
  // （输入 = 已成片 sRGB，输出 = 显示域，端点 = 0/1，且以"默认 latitude 的曲线"为
  // 差值参考）。域与端点都不同 ⇒ 强行共用结构只会让两边的约束互相污染。
  struct EditorCurve {
    // 三段四次多项式 y = m1 + x·(m2 + x·(m3 + x·(m4 + x·m5)))；`[ch][m1..m5]`，
    // linear 段只用 m1/m2（与 `FilmicSpline` 的系数组织一致，便于对拍）。
    // 两组：`cur` = 当前 latitude、`base` = 默认 latitude(0.01) 的参考曲线。
    // kFsStyle 里逐像素做 `y = t + cur(t) − base(t)`（差值法 ⇒ lat 默认时逐位恒等）。
    float toe[3][5] = {};      // cur 的 toe 段
    float sh[3][5] = {};       // cur 的 shoulder 段
    float lin[3][2] = {{0.0f, 1.0f}, {0.0f, 1.0f}, {0.0f, 1.0f}};  // cur 的中间线性段
    float at[3][2] = {};       // cur 的分段点 `[ch][toe_at, sh_at]`
    float b_toe[3][5] = {};    // base（差值参考曲线）
    float b_sh[3][5] = {};
    float b_lin[3][2] = {{0.0f, 1.0f}, {0.0f, 1.0f}, {0.0f, 1.0f}};
    float b_at[3][2] = {};
    // [37][38] 的显示域**端点重映射**（分段仿射、以显示域中灰为界、中灰不动）：
    // `t = a·x + b`，lo = `x < 中灰` 段、hi = `x ≥ 中灰` 段。默认（黑点 0 / 白点 1）
    // ⇒ (a,b) = (1,0) 两段都精确恒等（IEEE 下 `1.0*x + 0.0` 逐位 == x）。
    float lo_a = 1.0f, lo_b = 0.0f, hi_a = 1.0f, hi_b = 0.0f;
    // 门控：8 个槽位（[5..10] + [37][38]）全默认 ⇒ false ⇒ kFsStyle 整段跳过。
    bool on = false;
  };
  // U-2：CPU 侧求解（三段 × 2 组 × 3 通道 = 12 个 5×5 系统，成本与 filmic 同量级）。
  // ⚠ **参数显式列出**（不传整个 FilmParams）—— 与 `updateFilmicEV` 同一风格，且让
  // `passStyle` 的函数体里**字面出现** `p.shoulder`/`p.toe`/`p.filmic_*`：编辑器链路的
  // 读取集合护栏（`EditorStyleWhitelistTest` / `tools/check-style-passthrough.py` ④）是
  // 按"函数体内出现 `p.<字段>`"抽的，传整个结构体会让这 8 个槽位在护栏里**隐形**。
  static EditorCurve computeEditorCurve(const float lat_sh[3], const float lat_toe[3],
                                        float black_ev, float white_ev);

  // filmic 黑白点（v0.7.6）：来自**用户配置**（FilmParams[37]/[38]，默认 −7.65/+4.56，
  // 即 darktable 默认 / PhotonCamera 固化值）。auto_on=true 时黑点改由场景分位自动
  // （白点仍用配置值）。理由见 .cpp。
  // still=true（静拍）用当帧值不做 EMA —— 单帧机会，历史值等于用错曝光（v0.6.28 实测）。
  // E4-4：lat_sh/lat_toe = FilmParams[5..7]/[8..10]（filmic latitude，per-channel）。
  // U-3：user_contrast = FilmParams[4]（darktable contrast 滑杆的用户值，规格 0.5..1.5）
  // —— 与内部补偿 cp **相乘**后交给 computeFilmicSpline（不是覆盖：cp 保自动白点上探的
  // 中间调回退补偿，[4] 是风格参数，两者作用同一处 log 域斜率）。
  // 默认 1.0f ⇒ `cp * 1.0f` 逐位 == cp ⇒ 系数逐位不变（零回归）。
  void updateFilmicEV(GLuint src, bool still, bool auto_on, float user_black,
                      float user_white, float white_hl, float white_floor,
                      float white_probe, float user_ev, float user_contrast,
                      const float lat_sh[3], const float lat_toe[3]);
  // HDR gainmap：tex_scene_（Rec.2020 线性）÷ tex_cap_（sRGB 8bit）→ 8bit 比值图
  bool passGainmap(int w, int h, GLuint hdr_tex, GLuint sdr_tex);
  bool ensureGainmap(int w, int h);

  void passFilm(int w, int h, const FilmParams& p, GLuint src_tex, bool still,
                bool hal_domain);
  void passFilm(int w, int h, const FilmParams& p);
  // ── 引擎插槽（阶段 2）：film pass 的 program 按引擎 slot 编译 ────────────────
  // `queryFilmUniforms()`：查询 `u_film_` 的**全部** location。**必须**在每次换
  // program 之后重跑 —— location 是 per-program 的（重建后旧值全部失效，包括 uTex
  // 这类公共 uniform）。init() 与 buildFilmProgram() 各调用一次。
  void queryFilmUniforms();
  // `buildFilmProgram(slot)`：按注册表组装源码 → 编译 → 链接 → 换掉 `prog_film_` →
  // 重查 location。**任一环节失败都返回 false 且不换程序**（调用方保留原 program =
  // 回落引擎 0，符合方案 §4 契约 5：不 identity 伪装、不崩）。
  bool buildFilmProgram(int slot);
  // zebra —— 过曝提示（仅预览；成片传 false，提示绝不进成片）
  // out_max —— dither 除数 = **交付档上限**（卡 H2）。默认 kOutputMax(255) = 8bit 链，
  //   与改动前逐位一致；10bit HEIF 链传 kOutput10Max(1023)。见 OutputBitDepth.h。
  void passOutput(int w, int h, const FilmParams& p, bool flip_y, bool zebra,
                  float out_max = static_cast<float>(kOutputMax));
  // E1-3 编辑器通路：显示域输入 → LUT/颗粒/dither → fbo_cap_（RGBA8）
  // E2-12：w/h = **输出**尺寸（裁剪后）；crop_uv/crop_rot 见 submitDisplay 注释
  // （nullptr = 全幅 + rot=0 ⇒ 与改动前逐位一致）。
  void passStyle(int w, int h, const float crop_uv[4], int crop_rot,
                 const FilmParams& p);
  void passFill(int dst_w, int dst_h);

  static void makeTex(GLuint* tex, GLuint* fbo, GLenum internal, int w, int h);
  // internal —— 目标附件的**内部格式**（不再是 bool half_float 二态）：读回口径由
  //   OutputBitDepth.h 的 isOutputInternal / outputUploadTypeOf 给出 ⇒ 10bit 档
  //   （GL_RGB10_A2）也能取 (0,0) 诊断像素。用 8bit 口径去读 GL_RGB10_A2 是非法
  //   组合，会把 GL_INVALID_OPERATION 误报成"渲染失败"（诊断本身变成事故源）。
  void logPassDiag(const char* stage, GLuint fbo, GLenum internal);
  // ── 卡 H2：10bit 交付档的**绝对**读回自检（防"静默交付错文件"）─────────────
  // 为什么需要：GL_UNSIGNED_INT_2_10_10_10_REV 的位序（LSB-first：bit0..9=R、
  //   10..19=G、20..29=B）是下游 Kotlin 解包（Heif10Pipeline.unpackRgb10）的**唯一
  //   依据**；若驱动/平台实际布局不同，整幅成片会错色且**不报任何错**。
  // 做法：建 1×6 的 10bit 交付 FBO → 分段 glClear **已知浮点值**（含 R/G/B 各自
  //   独占满量程的三个 case ⇒ 通道位序一旦不符立刻红）→ 按交付口径 readback →
  //   与"浮点 → 10bit 定点四舍五入"的规范期望逐点比对（容差 1 LSB）。
  // ⚠ 必须用已知值做**绝对**比对：上传/下载往返（round-trip）同错会互相抵消，
  //   测不出布局错误（这正是本自检不用 glTexSubImage2D 往返的原因）。
  // 返回 true = 驱动行为与本项目解包口径一致；detail = 逐点实测（进日志）。
  bool selfCheck10BitReadback(std::string* detail);
  // 进程级**一次性**执行（结果缓存；两个 FrameRenderer 实例共享 —— 自检只取决于
  // 驱动行为，与场景/参数无关）。失败 ⇒ 调用方**拒绝本次 10bit 交付**并回退，
  // 这是"防静默交付错文件"的 fail-closed 落点。
  bool selfCheck10BitOnce();

  // ── E4-3 颗粒：128×128 R16F 相纸响应 LUT（darktable grain.c 的 evaluate_grain_lut）
  // mb = [60]、pivot = [61]（pivot 折进 LUT 亮度轴，见 GrainLut.h 的理由）。
  // 缓存键 = (mb, pivot)：两者都未变则立即返回（每帧调用一次，开销 = 两次比较）。
  void ensureGrainLut(float mb, float pivot);

  // ── E4-5 halation（光晕；emulsion `halation.ts` 的通道扩散金字塔）──────────
  // 5 级金字塔（1/2 … 1/32）+ 逐级可分离高斯 + 逐通道权重合成，共 16 个 pass。
  // 输入/输出域**跟随调用方**（uDecodeIn）：拍照链路 = 线性显示域（tex_work_，
  // kFsFilm 输出）；编辑器链路 = 显示域 sRGB（edit_tex_，已成片 RGBA8）。
  // 两条链路喂给金字塔的是同一个线性量 ⇒ 同参数下逐位可比（验收判据 4）。
  // 返回 = 合成结果纹理；**任何一步不可用（[62]≤0 / 分配失败 / program 缺失）都
  // 返回 src_tex（直通）**，并把 halo_on_ 置 0 ⇒ 真机日志能区分"没开"与"静默失败"。
  GLuint runHalation(int w, int h, GLuint src_tex, const FilmParams& p,
                     bool decode_in);

  // ── SG-1 柔光（soft glow；darktable bloom.c 的 Orton 支）────────────────────
  // **与 halation 共享金字塔**（规格 §5）：本函数只多"标量提取模式"（uGlowOn）与
  // 一个 1/2 分辨率加权合成 pass（kFsGlowSum）。Screen 混合不在这里 —— 它在
  // kFsOut/kFsStyle 的显示域段内（那里才拿得到"经过影调分区/褪色的当前 c"）。
  // 返回 = 柔光图纹理（1/2 分辨率，R 通道 = 线性显示域模糊高光）；0 = 未执行。
  GLuint runGlow(int w, int h, GLuint src_tex, const FilmParams& p, bool decode_in);

  // 共享金字塔（**SG-1 重构**：E4-5 的 runHalation 内联版 ①②③ 逐字搬出）。
  // glow_mode = 0 → halation 彩色提取（lin × smoothstep 权重，通道相关半径在 mix 实现）
  //           = 1 → 柔光标量提取（max(0, luma−thr)/(1−thr)，**通道无关**）。
  // 两者必须不同 —— 这是"红色外晕"与"发白外扩"可分辨的根源（规格 §1）。
  // ⚠ 顺序契约：柔光图占用 tex_halo_tmp_ ⇒ 同一帧内 halation 必须先跑（见 .cpp）。
  bool runHaloPyramid(int w, int h, GLuint src_tex, bool decode_in, float thr_lin,
                      float knee, bool glow_mode);

  // 5 级金字塔纹理 + 单张最大级 tmp（+ 可选的全分辨率输出）。尺寸变化时整体重建。
  // `need_out`：halation 要全分辨率输出，柔光不要（省 100MB；缓存键含它）。
  bool ensureHaloTargets(int w, int h, bool need_out);
  void releaseHaloTargets();
  // emulsion `haloWeights`：逐通道高斯，峰值在 centre_c = radius01·4·SPREAD[c]。
  // 输出 w[i][c]（级 i、通道 c），每通道**独立归一化**（和为 1）。
  // `spread`（SG-1 起是参数）：halation 传 kHaloSpread = {1.0, 0.6, 0.32}（乳剂物理
  //   常数 ⇒ 通道相关）；柔光传 {1,1,1}（**通道无关**，规格 §1）。
  // ⚠ **E4-5b（R1 修正）**：`level_shift = log2(min(w,h) / halo_ref_min_)` —— 把峰值按
  //   **输出分辨率**平移，使 halo 的**归一化**半径与图像尺寸无关（= 规格 §3.2.5 的原始
  //   声称；原实现实测是"固定像素尺度"，预览与成片差 2.8×）。
  //   成片（min(w,h) == refMin）时 shift = log2(1.0f) = 0.0f ⇒ `x + 0.0f` 逐位 == x
  //   ⇒ **成片行为与修正前逐位一致**（零观感变化；见 e4-5b 报告）。
  static void haloWeights(float radius01, float level_shift, const float spread[3],
                          float w[5][3]);

  GLuint prog_oes_ = 0, prog_still_ = 0, prog_film_ = 0, prog_out_ = 0,
         prog_fill_ = 0, prog_raw_ = 0, prog_rcd_dir_ = 0, prog_rcd_g_ = 0,
         prog_rcd_chroma_ = 0,
         // P3 拆双 pass（v0.10.180）：3a 对角填充 / 3b G 位轴向
         prog_rcd_chroma_a_ = 0, prog_rcd_chroma_b_ = 0,
         prog_ccm_ = 0, prog_amaze_up_ = 0,
         prog_raw_dn_ = 0,
         prog_chroma_dn_ = 0,
         prog_moire_stat_ = 0,   // 档 A（v0.10.165）伪彩强度图统计
         prog_raw_dn_ts_ = 0,
         prog_chroma_down_ = 0, prog_chroma_at_ = 0, prog_chroma_apply_ = 0,
         prog_hist_ = 0, prog_local_ = 0,
         prog_nn_pack_ = 0, prog_nn_unpack_ = 0,
         // RAW 域高光重建（darktable highlights / inpaint opposed）
         prog_hl_mask_ = 0, prog_hl_dil_ = 0, prog_hl_chroma_ = 0,
         prog_hl_apply_ = 0, prog_hl_ref_ = 0, prog_hl_refdown_ = 0,
         // 紫边抑制（v0.10.134）：down+Lab / 检测 / 1/16 均值 / 替换
         prog_df_down_ = 0, prog_df_det_ = 0, prog_df_avg_ = 0,
         prog_df_apply_ = 0,
         prog_cs_ = 0, prog_gainmap_ = 0,
         // 横向色差校正（v0.10.170）：1/8 降采样（估计）+ 全分辨率径向位移校正
         prog_ca_ds_ = 0, prog_ca_corr_ = 0,
         // PC ESD3D2 完整移植（v0.10.175）：本体 + 尺度结构入口/收口
         prog_pc3d_ = 0, prog_pc3d_down_ = 0, prog_pc3d_up_ = 0;
  // E1-3 编辑器通路（2026-10-08）：成片 JPEG 显示域 → LUT/颗粒/dither
  // （与 kFsOut 同段；kFsOut 一个字节未动 ⇒ 成片路径零回归）
  GLuint prog_style_ = 0;
  GLuint vs_rot_ = 0, vs_plain_ = 0;

  GLuint oes_tex_ = 0;
  int yuv_matrix_ = 0;
  GLuint still_tex_ = 0;  // RGB8，静拍 CPU 转换后上传
  int still_w_ = 0, still_h_ = 0;
  // ── E4-3 颗粒（granularity，darktable grain.c）──────────────────────────
  // tex_grain_lut_ = 128×128 R16F 相纸响应 LUT（CPU 侧按 [60]/[61] 重算，见
  //   ensureGrainLut）。**两条链路共用一份**：LUT 内容只依赖 [60][61]，与输入无关，
  //   且两条链路同在一个 GL context ⇒ 没有理由各存一份（省 64KB 与一次重算）。
  // grain_lut_mb_/grain_lut_pivot_ = 该 LUT 对应的参数（缓存的失效键）。
  // grain_seed_ = 噪声坐标 x 的**相对**偏移：预览/编辑器恒 0（图案稳定、不闪），
  //   成片由"输出尺寸 + 输入内容指纹"派生（同一张可复现、不同张不同）。
  GLuint tex_grain_lut_ = 0;
  float grain_lut_mb_ = -1.0f;
  float grain_lut_pivot_ = -1.0f;
  float grain_seed_ = 0.0f;
  // ── E4-5 halation（光晕，emulsion halation.ts）────────────────────────────
  // 5 级金字塔（1/2 … 1/32）+ **单张**最大级 tmp（25MB；逐级 tmp 要多 33MB，
  // 靠 uScale 把更小级的归一化 UV 缩到 tmp 已写入的左下角子矩形上）+ 全分辨率
  // 输出。**纹理只在 [62] > 0 时分配**（[62] = 0 = 整链旁路：零 pass、零纹理、
  // 零成本 —— 验收判据 1）。
  // 内存峰值（4080×3072，全分辨率独立输出方案）= 158.6MB：
  //   金字塔 4.17M px + tmp 3.13M px + out 12.53M px = 19.83M px × 8B。
  // ⚠ 规格 §3.2.7 的 D4（out 与 tex_work_ 乒乓，省 100MB）**不预先套用** ——
  //   按项目铁律"先完整实现、真机实测内存后再由用户拍板"（报告 §降级选项）。
  GLuint tex_halo_[5] = {0, 0, 0, 0, 0};
  GLuint fbo_halo_[5] = {0, 0, 0, 0, 0};
  int halo_w_[5] = {0, 0, 0, 0, 0};
  int halo_h_[5] = {0, 0, 0, 0, 0};
  GLuint tex_halo_tmp_ = 0, fbo_halo_tmp_ = 0;
  int halo_tmp_w_ = 0, halo_tmp_h_ = 0;
  GLuint tex_halo_out_ = 0, fbo_halo_out_ = 0;
  int halo_src_w_ = 0, halo_src_h_ = 0;  // 缓存键 = 输入（= 输出）尺寸
  // E4-5b（R1）：halo 半径归一化的**基准短边**（= 画幅短边 = 成片短边）。由
  // finishStillChain 用 `min(dst_w, dst_h)` 更新（成片是"画幅"的权威来源）；
  // 首拍之前用 kHaloRefMinDefault 兜底（= 4080×3072 画幅的短边，规格 §3.2.5
  // "本版无裁切/固定画幅"）。同一个 [64] 在成片上因此**逐位等于修正前**。
  float halo_ref_min_ = 3072.0f;
  // 诊断：halo_on_ = 本帧 halo 链**真的执行了**（0 且 [62] > 0 ⇒ 静默失败：
  // program 缺失 / 纹理分配失败 / FBO 不完整 —— 真机日志一眼可辨）。
  int halo_on_ = 0;
  float halo_ms_ = 0.f;
  // ── SG-1 柔光（soft glow）─────────────────────────────────────────────────
  // glow_on_ / glow_ms_ 与 halation 同口径（0 且 [95] > 0 ⇒ 静默失败）。
  // **不新增任何纹理成员**：柔光复用 halation 的 5 级金字塔 + tmp（tex_halo_tmp_
  // 兼作柔光图，1/2 分辨率）⇒ 额外显存 0（规格 §5 的"共享金字塔"收益，报告 §性能）。
  int glow_on_ = 0;
  float glow_ms_ = 0.f;
  GLuint prog_halo_thr_ = 0, prog_halo_blur_ = 0, prog_halo_mix_ = 0;
  GLuint prog_glow_sum_ = 0;  // SG-1：5 级单半径加权合成（1/2 分辨率，5 采样/px）
  struct {
    GLint src = -1, texel = -1, decode_in = -1, thr_on = -1, thr_lin = -1,
          knee = -1, glow_on = -1;  // glow_on = SG-1 柔光提取模式门控
  } u_halo_thr_;
  struct {
    GLint src = -1, dir = -1, scale = -1;
  } u_halo_blur_;
  struct {
    GLint src = -1, halo0 = -1, halo1 = -1, halo2 = -1, halo3 = -1, halo4 = -1,
          w0 = -1, w1 = -1, w2 = -1, w3 = -1, w4 = -1, tint = -1, amount = -1,
          decode_in = -1;
  } u_halo_mix_;
  struct {
    GLint g0 = -1, g1 = -1, g2 = -1, g3 = -1, g4 = -1,
          s0 = -1, s1 = -1, s2 = -1, s3 = -1, s4 = -1;
  } u_glow_sum_;
  // E1-5（2026-10-08 P0 全黑定位）：编辑器输入纹理。**独立于 still_tex_** ——
  // 编辑器上传的是 RGBA8，内部格式必须同为 RGBA8（RGB8 内部格式 + RGBA 上传是
  // 「内部/外部格式不匹配」，规范允许但部分驱动会拒绝或写入未定义内容）。
  // 与 submitStill 分离 ⇒ YUV 拍照链路的 still_tex_ 记账/格式逐位不变。
  GLuint edit_tex_ = 0;  // RGBA8，编辑器成片上传（submitDisplay 专用）
  int edit_w_ = 0, edit_h_ = 0;
  GLuint raw_tex_ = 0;  // R16UI，Bayer 原始（texelFetch 整型采样）
  int raw_w_ = 0, raw_h_ = 0;
  // ── MFNR 阶段一（v0.9.0）──────────────────────────────────────────────
  GLuint prog_mfnr_pyr_ = 0, prog_mfnr_down_ = 0, prog_mfnr_fuse_ = 0,
         prog_raw_dn_fused_ = 0;
  GLuint prog_mfnr_mis_ = 0;  // 阶段二（v0.10.0）：mismatch map
  GLuint tex_mfnr_[3] = {0, 0, 0};  // R16UI，burst 帧 1..3（帧 0 复用 raw_tex_）
  GLuint tex_pyr1_ = 0, fbo_pyr1_ = 0;  // RGBA8，1/2 灰度（sqrt 预编码）
  GLuint tex_pyr2_ = 0, fbo_pyr2_ = 0;  // RGBA8，1/4（readback 层）
  GLuint tex_pyr3_ = 0, fbo_pyr3_ = 0;  // RGBA8，1/8（readback 层）
  GLuint tex_pyr4_ = 0, fbo_pyr4_ = 0;  // RGBA8，1/16（readback 层）
  int pyr1_w_ = 0, pyr1_h_ = 0;
  GLuint tex_fused_ = 0, fbo_fused_ = 0;  // R16F，融合后线性 mosaic（raw 尺寸）
  // ── NN 降噪 pass（v0.10.102）纹理与 CPU 缓冲 ────────────────────────────
  // tex_nn_packed_ = kFsNnPack 输出（(w/2)×(h/2) RGBA16F，packed Bayer ×VST）；
  // tex_nn_out_ = 推理结果上传目标（同规格）；tex_fused_nn_ = kFsNnUnpack 输出
  //（w×h R16F，mosaic、线性+WB 域 —— 与 tex_fused_ 同域，下游零改动）。
  // CPU 缓冲随实例常驻（readback 50MB + 输出 50MB + 权重 12.5MB），NN 关闭时不分配。
  GLuint tex_nn_packed_ = 0, fbo_nn_packed_ = 0;
  GLuint tex_nn_out_ = 0;
  GLuint tex_nn_fused_ = 0, fbo_nn_fused_ = 0;
  // 1×1 兜底纹理：kFsNnPack 的两个 sampler 分支互斥，但 Mali 铁律 = 所有
  // sampler 无论分支都必须有**类型匹配**的合法绑定（整型/浮点不可混绑）。
  GLuint tex_nn_fdummy_ = 0;  // R16F（uFused 未消费时）
  GLuint tex_nn_rdummy_ = 0;  // R16UI（uRaw 未消费时）
  int nn_w_ = 0, nn_h_ = 0;
  std::vector<float> nn_packed_, nn_out_, nn_weight_, nn_ramp_;
  std::vector<float> nn_tile_in_, nn_tile_out_;  // 512²×4 tile 暂存（与 Java 缓冲间 memcpy）
  GLuint tex_off_[3] = {0, 0, 0};         // RGBA16F 位移场（tiles×tiles）
  GLuint tex_off_ab_[3] = {0, 0, 0};      // 同上，A/B 诊断的第二组（旧层对齐）
  GLuint tex_mis_ = 0, fbo_mis_ = 0;      // RGBA8 mismatch map（4×tiles 网格）
  GLuint tex_w_ = 0;                      // RGBA8 LINEAR 权重图（4×tiles，→Fuse uW）
  int mfnr_w_ = 0, mfnr_h_ = 0;
  int tiles_w_ = 0, tiles_h_ = 0;
  // LSC 增益网格（RGB16F，NEAREST；1×1 全 1 = 关闭/无数据，见 submitRaw）
  GLuint lsc_tex_ = 0;
  int lsc_w_ = 0, lsc_h_ = 0;
  GLuint fbo_dir_ = 0, tex_dir_ = 0;  // RGBA16F，RCD 方向场（vh + pq 统计）
  GLuint fbo_g_ = 0, tex_g_ = 0;      // RGBA16F，RCD 全图 G（g 通道有效）
  int rcd_w_ = 0, rcd_h_ = 0;
  // P3 拆双 pass（v0.10.180）的 3a 中间场：源几何 RGBA16F，WB 域 RGB
  //（对角段已填、无 LSC）；3b 的交叉轴取数源。仅 [58]=1 时被写/读。
  GLuint fbo_rcd_a_ = 0, tex_rcd_a_ = 0;
  GLuint fbo_mos_ = 0, tex_mos_ = 0;  // RGBA16F，P0 降噪后线性 mosaic
  // AMaZE 档（v0.10.172）：CPU AMaZE 输出的 WB 域相机 RGB（源几何，RGB16F）。
  // 只在 demosaic == 2 时创建/上传；RCD/双线性档完全不触碰（零回归面）。
  GLuint tex_amaze_ = 0;
  int amaze_w_ = 0, amaze_h_ = 0;
  // ── RAW 域高光重建（darktable highlights / inpaint opposed）─────────────
  // tex_hl_mask_ / tex_hl_dil_ = 3×3 photosite 块网格（w/3 × h/3）的裁剪掩码与
  // 膨胀掩码；tex_hl_sum_/tex_hl_cnt_ = 16×16 归约的 chrominance 累加（MRT 双目标）；
  // tex_hl_ = 重建后的线性 mosaic（w×h），由 hr_src_override_ 转交 RCD 三段。
  GLuint tex_hl_mask_ = 0, fbo_hl_mask_ = 0;
  GLuint tex_hl_dil_ = 0, fbo_hl_dil_ = 0;
  GLuint tex_hl_sum_ = 0, fbo_hl_sum_ = 0;  // chrominance 归约（两趟累加复用）
  GLuint tex_hl_ = 0, fbo_hl_ = 0;
  GLuint tex_hl_ref_ = 0, fbo_hl_ref_ = 0;  // 1/32 网格参考色度场 (rg·w, bg·w, w)
  GLuint tex_hl_ref2_ = 0, fbo_hl_ref2_ = 0;  // 1/128 中尺度参考场（同上三元组）
  int hl_w_ = 0, hl_h_ = 0, hl_mw_ = 0, hl_mh_ = 0, hl_tw_ = 0, hl_th_ = 0;
  // 非 0 时 RCD 三段改读它（= tex_hl_）；0 = 原行为（直读 tex_mos_）。
  GLuint hr_src_override_ = 0;
  GLuint fbo_dn_ = 0, tex_dn_ = 0;    // RGBA16F，P4 色度平滑输出
  int dn_w_ = 0, dn_h_ = 0;
  // 紫边抑制（v0.10.134）：tex_df_ = defringe 输出（替换 tex_dn_ 进 D-1/film）；
  // 半分辨率 Lab/检测缓冲与 1/16 均值网格（CPU 读回 chromave）。
  GLuint fbo_df_ = 0, tex_df_ = 0;          // 全分辨率 RGBA16F
  GLuint fbo_df_lab_ = 0, tex_df_lab_ = 0;  // 半分辨率 (L,a,b,gate)
  GLuint fbo_df_det_ = 0, tex_df_det_ = 0;  // 半分辨率 (a,b,chroma,gate)
  GLuint fbo_df_avg_ = 0, tex_df_avg_ = 0;  // 1/16 网格 chroma 均值
  int df_w_ = 0, df_h_ = 0;                 // 全分辨率尺寸（缓存键）
  int dbg_cs_ok_ = -1;                      // v0.10.198：色差中值 shader 是否链接成功
  int dbg_cs_on_ = -1;                      // v0.10.198：本帧该 pass 是否真的跑了
  float dbg_cs_k_ = -1.f;                   // v0.10.198：下发的 FilmParams[59] 实际值
  // 色差域中值滤波（v0.10.196）：tex_cs_ = 输出（替换 src 进 defringe/film）
  GLuint fbo_cs_ = 0, tex_cs_ = 0;          // 全分辨率 RGBA16F（最终输出）
  GLuint fbo_cs_tmp_ = 0, tex_cs_tmp_ = 0;  // ping-pong 中间缓冲（多 pass）
  int cs_w_ = 0, cs_h_ = 0;                 // 全分辨率尺寸（缓存键）
  float dbg_df_cavg_ = 0.f;                 // 最近一次 chromave（日志）
  // 横向色差校正（v0.10.170）：tex_ca_ = 校正输出（只在 caApplied=1 时替换
  // tex_work_ 进 passCcm）；tex_ca_ds_ = 1/8 box 降采样（CPU readback 做估计）。
  // dbg_ca_*：kr/kb = 估计的径向系数（位移 = k·r）、shiftR/shiftB = 画面角部的
  // 实际最大位移（px，已乘强度并 clamp）、applied = 是否真的执行了校正 pass。
  GLuint fbo_ca_ = 0, tex_ca_ = 0;
  GLuint fbo_ca_ds_ = 0, tex_ca_ds_ = 0;
  int ca_w_ = 0, ca_h_ = 0, ca_ds_w_ = 0, ca_ds_h_ = 0;
  float dbg_ca_kr_ = 0.f, dbg_ca_kb_ = 0.f;   // 实际采用的径向系数（0 = 未通过判据）
  float dbg_ca_kraw_r_ = 0.f, dbg_ca_kraw_b_ = 0.f;  // 原始估计（未过判据，诊断用）
  float dbg_ca_shift_r_ = 0.f, dbg_ca_shift_b_ = 0.f;
  int dbg_ca_applied_ = 0;
  float dbg_ca_ms_ = 0.f;
  // P3 拆双 pass（v0.10.180，[58]）生效性与分 pass 成本（照 p0s/hlOk 的教训：
  // 改完必须能自证）。rcdSplit = 1 = 本帧真的走了 3a→3b；rcdAMs/rcdBMs =
  // 两个 pass 各自耗时（含 glFinish 同步，ms）。日志字段 rcdSplit/rcdAMs/rcdBMs。
  int dbg_rcd_split_ = 0;
  float dbg_rcd_a_ms_ = 0.f, dbg_rcd_b_ms_ = 0.f;
  // AMaZE 档（v0.10.172）：CPU AMaZE + 上传耗时（日志 amazeMs；0 = 未走本档）
  float dbg_amaze_ms_ = 0.f;
  // 档 A（v0.10.165）伪彩强度图诊断（passChromaDenoise 内 GPU 统计读回）：
  //   moGain = 下发强度（FilmParams[48]）；moMean = 强度图全图均值；
  //   moP95  = 强度图的 P95（4 锚点分段线性 CDF 近似，口径见 kFsMoireStat）；
  //   moCov  = moire > 0.5 的像素占比；moSc = σc 实际平均放大倍数
  //   （= 1 + moGain·moMean；封顶 4·σc 在 moGain ≤ 3 时恒不触发 ⇒ 精确）。
  float dbg_mo_gain_ = 0.f, dbg_mo_mean_ = 0.f, dbg_mo_p95_ = 0.f,
        dbg_mo_cov_ = 0.f, dbg_mo_sc_ = 1.f;
  // 强边缘色度门（v0.10.174，FilmParams[51]/[52]/[53]）：
  //   cgK     = 下发的 κ（0 = 关）；cgCap = shader 常量 kCgRel = 16.0（**相对**上限
  //             16·σc，v0.10.173 这里是绝对 0.2 = 73.7% 像素被放宽的来源）；
  //   cgScope = 1 = PC 的 w2 权重形式（v2 默认）；0 = 字面版（只放大 wc）。
  //   cgK > 0 时 P4 的 a 通道改输出**门控开度** smoothstep(0.10, 0.30, |∇L|)
  //   ⇒ 复用档 A 的统计读回，字段 cgMean/cgP95/cgCov = 门控覆盖率的
  //   mean/P95/>0.5 占比（此时 moMean 等三个字段是**同一组数**，档 A 的强度图
  //   观测被让位，见 REPORT）。真机健康区间：cgMean ≤ 0.15、cgCov ≤ 0.05。
  float dbg_cg_k_ = 0.f, dbg_cg_cap_ = 16.0f, dbg_cg_scope_ = 1.f;
  float dbg_df_gate_ = 0.f;   // 紫边判据的梯度自适应强度（[53]；0 = 判据未改）
  // ── PC ESD3D2 完整移植（v0.10.175）─────────────────────────────────────
  // 生效性字段（照 p0s/hlOk 的教训：改完必须能自证）：
  //   pc3dOn  = 1 = 本帧真的跑了 PC3D 且输出已替换 P4 的输入
  //   pc3dM   = 本轮实际的 MSIZE（PC 公式 + 本机噪声模型算出，7..21）
  //   pc3dK   = 实际下发的 KERNELSIZE（f1 的高斯 σ）
  //   pc3dSc  = 尺度结构实际用的降采样倍数（PC ESD3D2.Run 的 scale，1..4；
  //             1 = 全分辨率单趟、≥2 = 走 gaussdown + guided upsample）
  //   pc3dMs  = 本模块 GPU+提交耗时（ms）
  // 健康区间见 FilmParams.h 的 [54] 注释。
  int dbg_pc3d_on_ = 0, dbg_pc3d_m_ = 0, dbg_pc3d_scale_ = 1;
  float dbg_pc3d_k_ = 0.f, dbg_pc3d_ms_ = 0.f;
  // tex_pc3d_ = 本模块的全分辨率输出（只在 pc3dOn = 1 时被 P4 读）；
  // tex_pc3d_t1_ = pass1（MOIRE = 0）的全分辨率中间；tex_pc3d_lo_ = 低分辨率中间。
  // 全部 RGBA16F，惰性分配（开关关闭时零显存）。
  GLuint fbo_pc3d_ = 0, tex_pc3d_ = 0;
  GLuint fbo_pc3d_t1_ = 0, tex_pc3d_t1_ = 0;
  GLuint fbo_pc3d_lo_[2] = {0, 0}, tex_pc3d_lo_[2] = {0, 0};
  int pc3d_w_ = 0, pc3d_h_ = 0, pc3d_lw_ = 0, pc3d_lh_ = 0;
  // 档 A：强度图统计网格（32×32 RGBA16F，惰性分配；强度 = 0 时零成本不分配）
  GLuint tex_mo_stat_ = 0, fbo_mo_stat_ = 0;
  // D 专项：半分辨率 à trous 色度降噪缓冲（2× 近似层 ping-pong + 5× 细节层
  // + 1× σ 图；RGBA16F、1/2 分辨率 ≈ 25MB/张）
  GLuint tex_ch_a_[2] = {0, 0}, fbo_ch_a_[2] = {0, 0};
  GLuint tex_ch_dp_[kChromaLevels] = {0}, fbo_ch_dp_[kChromaLevels] = {0};
  GLuint tex_ch_sig_ = 0, fbo_ch_sig_ = 0;
  int ch_w_ = 0, ch_h_ = 0;
  // v0.10.59 诊断：本轮新增算法是否真的执行（0.10.58 起日志无字段，无法验证生效）
  int dbg_p0s_ = 0;      // P0 实际样本数（9 = 旧"融合后"路径，36 = 时域×空间联合）
  // RAW 域高光重建（darktable highlights）生效性：照 p0s/nnOk 的教训，必须由
  // finishStillChain 在日志组装前读取。
  int dbg_hl_ok_ = 0;        // 1 = 执行成功（下游改用 tex_hl_）；0 = 未启用/失败
  float dbg_hl_cnt_ = 0.f;   // 色度统计样本数（G 通道；darktable 的 100 样本闸）
  float dbg_hl_chroma_[3] = {0.f, 0.f, 0.f};  // 色度校正量
  double dbg_hl_sum_[3] = {0.0, 0.0, 0.0};    // Σ(实测−对立外推)，chroma 的分子
  float dbg_hl_cnt3_[3] = {0.f, 0.f, 0.f};    // 分通道样本数（原只记 G 通道）
  float dbg_hl_ms_ = 0.f;
  // 全局兜底比例诊断（2026-10-04）：真机据此核对"全局比例是否接近该场景中性比例"，
  // 明显跑偏说明 w 加权或配对口径有问题。
  // v0.10.145：HR mask 阈值的域换算所需 —— 当帧 WB 增益与 CCM（行主序）。
  // 阈值 = kSaturationNormCalib × |clip(CCM · (0, gain_G, 0))| —— kSaturationNormCalib
  // 是**满阱范数的标定系数（非白点）**，换 CCM / 换镜头需重标；G 最先饱和，见 .cpp 注释。
  float hr_gain_[3] = {1.f, 1.f, 1.f};
  float hr_ccm_[9] = {1.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f, 0.f, 1.f};
  float dbg_hr_thr_ = 0.f;   // 实际下发的 mask threshold（日志 hrThr）
  float dbg_hl_rgg_ = 0.f, dbg_hl_bgg_ = 0.f;
  double dbg_hl_refw_ = 0.0;  // Σw（ref 场权重和；理论上限 = 块数）
  float dbg_hl_r2hit_ = 0.f, dbg_hl_rghit_ = 0.f;  // 三级参考命中占比（%）
  int dbg_hl_grecon_ = 1;  // v0.10.169：apply 支实际下发的 uGRecon（日志 hlGRecon）
  int dbg_hl_rbrecon_ = 0;  // v0.10.179：apply 支实际下发的 uRbRecon（日志 hlRb）
  // ── v0.10.167 HR 前置的生效性字段（照 p0s/hlOk 的教训：改完必须能自证）──
  // 为什么必须记"统计源/判定源"两个名字：exp21 翻车的结构性根因就是二者共用
  // 一个 src、没有独立旋钮（改统计口径时把判定口径一起改了 ⇒ 修复面 47269→63903
  // 样本、画面更红）。这两个字段让真机日志直接回答"它们是否指向不同纹理"。
  const char* dbg_hl_stat_src_ = "none";    // uStat 实际绑定（统计源）
  const char* dbg_hl_decide_src_ = "none";  // uMos 实际绑定（判定源）
  int dbg_hl_pre_ = 0;        // 1 = HR 已前置到 P0 之前；0 = 后置/未启用
  float dbg_hl_wclamp_ = 0.f;  // 实际下发的 uWClamp（1 = P0 权重域已保护）
  int dbg_cms_ = 0;      // 色度多尺度（D-1）是否执行
  float dbg_cms_k_ = 0.f;
  float dbg_p0w_ = 0.f;   // P0 中心点权重和（诊断）
  // ── NN 降噪 pass（v0.10.102）诊断（raw.render 的 nnOk/nnDel/nnMs 族）────
  // 生效性字段照 p0s 的教训：必须由 finishStillChain 在日志组装前读取（runNnPass
  // 在 finishStillChain 内执行），不能写在调用方。
  int dbg_nn_ok_ = 0;      // 1 = NN pass 成功（P0 走直读展开）；0 = 未启用
  int dbg_nn_fail_ = 0;    // 0 = 成功；1 = 推理链失败（已回退 P0 原链）
  std::string dbg_nn_del_; // "gpu" / "cpu"（来自 NnInferEngine，随 tile 交换）
  float dbg_nn_ms_ = 0.f;  // NN pass 端到端（pack+读回+推理+ramp+上传+unpack）
  int dbg_nn_tiles_ = 0;
  float dbg_nn_gain_ = 0.f; // 网络输入增益（cvt_k×256，官方 KSigma 口径；日志 nnGain）
  // ── 降噪专项 A：DFT 逐频收缩合并（compute）──────────────────────────
  // overlap-add = R32F scratch（ES 3.1 规范保证 image load+store）× 4 次
  // 串行 dispatch + kFsMfnrFftSum 收口进 tex_fused_。compute 编译失败（非
  // ES 3.1+/r32f image 不可用设备）→ 置 0，运行时回退 mismatch 融合路径。
  GLuint prog_mfnr_fft_sum_ = 0;  // 相位组求和 pass(fragment,init 创建)
  int burst_w_ = 0, burst_h_ = 0;  // 输出纹理尺寸标记(输入直接用 4 张 2D)
  // 相位组输出 ×4（R32F image2D writeonly；glslang ES profile 只放行 r32f、
  // image2DArray 的 store 在 X300 Mali 上静默丢失 —— v0.10.42 dftGpu 577ms
  // vs dftCnt 0 实锤）
  GLuint tex_fft_out_[4] = {0, 0, 0, 0};
  struct {
    GLint g[4] = {-1, -1, -1, -1}, size = -1;
  } u_mfnr_fft_sum_;
  // ── v0.10.15：darktable filmic 高光重建（HR，dst 尺寸；8+1 张全分辨率）──
  // 出处 darktable filmicrgb.c 的 mask_clipped_pixels/inpaint_noise/
  // reconstruct_highlights（à trous 小波）。生命周期：仅拍照路径（finishStillChain
  // 内 P4 后、passFilm 前），结果纹理直接作为 filmic 输入。
  GLuint prog_hr_mask_ = 0, prog_hr_inpaint_ = 0, prog_hr_blur_ = 0,
         prog_hr_hf_ = 0, prog_hr_rin_ = 0, prog_hr_step_ = 0,
         prog_hr_norms_ = 0, prog_hr_ratios_ = 0, prog_hr_restore_ = 0,
         prog_hr_stat_ = 0;
  GLuint tex_hr_mask_ = 0, fbo_hr_mask_ = 0;    // R16F（sigmoid 权重）
  GLuint tex_hr_lf_[2] = {0, 0}, fbo_hr_lf_[2] = {0, 0};  // RGBA16F（LF 乒乓）
  GLuint tex_hr_hf_ = 0, fbo_hr_hf_ = 0;        // RGBA16F（HF = detail − LF）
  GLuint tex_hr_hfrgb_ = 0, fbo_hr_hfrgb_ = 0;  // RGBA16F（blur(HF)）
  GLuint tex_hr_temp_ = 0, fbo_hr_temp_ = 0;    // RGBA16F（blur 垂直 pass 中间）
  GLuint tex_hr_recon_[2] = {0, 0}, fbo_hr_recon_[2] = {0, 0};  // RGBA16F 乒乓
  GLuint tex_hr_norms_ = 0, fbo_hr_norms_ = 0;  // R16F（HQ ratios 轮的 norm）
  GLuint tex_hr_stat_ = 0, fbo_hr_stat_ = 0;    // R16F 64×48（裁剪统计 readback）
  int hr_w_ = 0, hr_h_ = 0;
  GLint u_hr_mask_ = -1, u_hr_mask_norm_ = -1, u_hr_mask_feather_ = -1;
  GLint u_hr_inp_tex_ = -1, u_hr_inp_mask_ = -1, u_hr_inp_noise_ = -1,
        u_hr_inp_thr_ = -1;
  GLint u_hr_blur_tex_ = -1, u_hr_blur_size_ = -1, u_hr_blur_mult_ = -1,
        u_hr_blur_dir_ = -1;
  GLint u_hr_hf_detail_ = -1, u_hr_hf_lf_ = -1;
  GLint u_hr_rin_tex_ = -1, u_hr_rin_mask_ = -1;
  GLint u_hr_step_hfrgb_ = -1, u_hr_step_lf_ = -1, u_hr_step_hf_ = -1,
        u_hr_step_mask_ = -1, u_hr_step_recon_ = -1, u_hr_step_gamma_ = -1,
        u_hr_step_gammac_ = -1, u_hr_step_beta_ = -1, u_hr_step_betac_ = -1,
        u_hr_step_delta_ = -1, u_hr_step_variant_ = -1, u_hr_step_si_ = -1,
        u_hr_step_sc_ = -1;
  GLint u_hr_norms_tex_ = -1;
  GLint u_hr_ratios_tex_ = -1, u_hr_ratios_norms_ = -1;
  GLint u_hr_restore_ratios_ = -1, u_hr_restore_norms_ = -1;
  GLint u_hr_stat_mask_ = -1, u_hr_stat_size_ = -1, u_hr_stat_out_ = -1;
  // 返回重建后的纹理：未触发/失败/关闭时返回 src（调用方零分支）。
  // white_ev_input_domain = HR 输入域（曝光前）的白点 EV，暗含 darktable 的
  // threshold = 2^white_source × grey_source 语义（见实现处注释）。
  GLuint runHighlightReconstruct(GLuint src, int w, int h,
                                 float white_ev_input_domain,
                                 int* out_clipped_blocks, float* out_ms);
  bool ensureHrTargets(int w, int h);
  void releaseHrTargets();
  // passFilm 内的 HR 观测（raw.render 的 hr/hrThr/hrMs/clipB 由 finishStillChain 读取）
  int hr_blocks_ = -1;
  float hr_ms_ = 0.0f;
  float hr_white_ev_ = 0.0f;
  int hr_stat_raw_ = -1;   // 1 = mask/chrominance 用了 P0 前的 tex_fused_
  GLuint fbo_hist_ = 0, tex_hist_ = 0;  // RGBA8 直方图下采样目标（256×96）
  int hist_w_ = 0, hist_h_ = 0;
  // M4-7 局部色调映射的低频亮度图（RGBA8、LINEAR 上采样）。与 tex_hist_
  // 同源（都走 kFsHist 的显示域 luma），但**分辨率低得多**才叫"局部"。
  // v0.6.34：尺寸不再固定 32×24，改为**随渲染分辨率**按"每格 ≈128px"算
  //（ensureLocalTarget），否则预览（1216×1621）的等效提亮半径只有成片的 1/3，
  // 预览与成片的局部提亮分布对不上。init 仍建 32×24 作兜底绑定。
  GLuint fbo_local_ = 0, tex_local_ = 0;
  int local_w_ = 0, local_h_ = 0;
  // ── 诊断统计（v0.10.166，见 .cpp 的 diag 块注释）────────────────────────
  // 4 张归约网格（RGBA32F，不可渲染时回落 RGBA16F）+ 1 张位打包输出（RGBA8 8×2）。
  // 惰性创建、随实例释放：**源纹理只作 sampler 读**，诊断只写这 5 张自己的纹理。
  GLuint tex_diag_ds_[4] = {0, 0, 0, 0};
  GLuint fbo_diag_ds_ = 0;
  GLuint tex_diag_out_ = 0, fbo_diag_out_ = 0;
  bool diag_ds_f32_ = false;  // 归约网格是否用了 RGBA32F（日志/排错用）
  GLuint prog_diag_down_ = 0, prog_diag_pack_ = 0;
  struct {
    GLint src = -1, size = -1;
  } u_diag_down_;
  struct {
    GLint t0 = -1, t1 = -1, t2 = -1, t3 = -1;
  } u_diag_pack_;
  bool ensureDiagTargets();
  void releaseDiagTargets();
  // 阶段纹理 → GPU 归约 → 读回标量 → diag.stats。失败只写 diag.stats_fail。
  // 进入/退出成对保存恢复 FBO/viewport/program/纹理绑定/blend/scissor/depth。
  void diagStatsTex(const char* stage, GLuint tex, int w, int h, const FilmParams* p);
  // 最终成片：对已读回的 u8 缓冲做 CPU 侧同口径统计（**零 GL 调用**）。
  void diagStatsU8(const char* stage, const unsigned char* px, int w, int h,
                   const FilmParams* p);
  // v0.10.93 预览降载：降档帧的输出中间目标（RGBA8、渲染分辨率 = 缩放后尺寸）。
  // pass3（OETF+锐化+LUT+颗粒）整体跑在低分辨率上，blitFramebuffer(GL_LINEAR)
  // 升到全窗口 —— X300 的 RGBA16F 无线性过滤（NEAREST 放大 = 像素块），必须借
  // RGBA8（display 域 [0,1]，OETF 后量化无截断）的 LINEAR 能力做平滑放大。
  GLuint fbo_out_s_ = 0, tex_out_s_ = 0;
  int out_s_w_ = 0, out_s_h_ = 0;
  std::vector<uint8_t> rgb_stage_;
  // M4 3D LUT（display 域）：RGBA16F 3D 纹理，LINEAR/CLAMP；lut_size_ ≤ 1 = 关闭
  GLuint lut_tex_ = 0;
  int lut_size_ = 0;
  float lut_dmin_[3] = {0.0f, 0.0f, 0.0f};
  float lut_dmax_[3] = {1.0f, 1.0f, 1.0f};
  std::mutex lut_mu_;
  std::vector<float> pending_lut_;  // RGBA 交错（已补 alpha，供 RGBA16F 上传）
  int pending_lut_size_ = 0;
  float pending_lut_dmin_[3] = {0.0f, 0.0f, 0.0f};
  float pending_lut_dmax_[3] = {1.0f, 1.0f, 1.0f};
  bool lut_dirty_ = false;
  float lut_strength_ = 1.0f;  // 最近一次输出的 LUT 强度（histogram 沿用，保证同源）
  // ── filmic 状态（v0.7.3）─────────────────────────────────────────────────
  FilmicSpline filmic_{};                       // 本帧下发的 spline（CPU 侧算好）
  // ── 引擎插槽状态（阶段 2）────────────────────────────────────────────────
  // `sigmoid_` = 引擎 1 的 7 个标量（CPU 侧 `computeSigmoidParams` 在 init() 期算一次；
  // 当前无 UI 通道 ⇒ 恒为 SigmoidParams.h 的 `kSigmoidDefault*`）。每帧无条件下发。
  SigmoidParams sigmoid_{};
  // 当前**已编译进 `prog_film_`** 的引擎 slot（0 = 内置 filmic）。
  // `init()` 期拿不到 `FilmParams` ⇒ 恒先编译引擎 0；运行期首个非 0 取值由
  // `passFilm` 触发一次性重建（见 buildFilmProgram）。
  int film_engine_slot_ = 0;
  // 重建**失败过**的 slot：失败后不重试（否则每帧重编译一个坏 shader = 每帧一次
  // 35KB 编译 + 日志刷屏）。请求的 slot 变化时才重新尝试。
  int film_build_failed_slot_ = -1;
  float ev_black_ = -7.65f, ev_white_ = 4.56f;  // 本帧实际 EV（日志用）
  float ev_smooth_black_ = 0.0f, ev_smooth_white_ = 0.0f;  // EMA（0 = 未初始化）
  // 排查"预览过曝 vs 成片正常"：预览统计 OES 预览流(passOES)、静拍统计 RAW/YUV 链，
  // **两条源的线性亮度标定未必一致** —— 记录判据输入供对比（preview.ag / raw.render.linMid）。
  float last_lin_mid_ = 0.0f;   // 场景中位（线性）
  float last_p_lo_ = 0.0f, last_p_hi_ = 0.0f;  // 场景暗/亮分位（线性，自动 EV 的输入）
  // v0.10.27：白点判据的诊断量（EV，相对中灰）——w99/w999 = P99/P99.9 的 raw 判据值，
  // cp = 高光上探的 contrast_param 补偿（1 = 未上探）。raw.render / preview.filmic 输出。
  // v0.10.28：wmax = 采样 max 判据值（上探主驱动）。
  float last_w99_ = 0.0f, last_w999_ = 0.0f, last_cp_ = 1.0f, last_wmax_ = 0.0f;
  float last_still_w999_ = 0.0f;   // 成片链最后一次的 P99.9（EV 前，RAW 归一化域）
  // ── 自动曝光（v0.11.61）─────────────────────────────────────────────────
  // 本张 RAW 的 P50/P99.9 ⇒ 渲染端 EV（中灰锚定 + 高光约束，高光优先）。
  // 只在 submitRawBurst 的 still 链跑；预览/YUV 不走（域不同，见 AeMetering.h）。
  float ae_ev_ = 0.0f;        // 本次实际追加到 exposure_ev 的量
  float ae_mid_ev_ = 0.0f;    // 中灰锚定要求的 EV（诊断）
  float ae_hl_ev_ = 0.0f;     // 高光约束允许的 EV（诊断）
  float ae_p50_ = 0.0f;       // 本张 P50（线性，max 通道）
  float ae_p999_ = 0.0f;      // 本张 P99.9（线性；**诊断用**，判据是 P99.5）
  float ae_p995_ = 0.0f;      // 本张 P99.5（线性；高光判据 —— 允许 0.5% 像素裁切）
  int ae_why_ = 0;            // 0 none / 1 mid / 2 highlight / 3 clamped / 4 invalid
  // 本张统计 + 决策。返回要**额外**加到 exposure_ev 上的 EV（0 = 不介入）。
  float passAutoExposure(GLuint src, const FilmParams& p);
  // 预览域映射（v0.8.0）：HAL OES 域与 RAW 链域的线性中位比，在线估计。
  // pv_mid_ema_ = 预览统计中位的 EMA；静拍时 ratio_new = pv_mid_ema_ / RAW 中位，
  // domain_ratio_ 对其 EMA（clamp 1.2–4.5，v0.6.48 实测范围 1.70–3.87）。
  // 初值 2.0 = 实测范围的中位；首次静拍前预览用它兜底。
  // ⚠️ v0.8.2：两值提为 .cpp 的进程级 static —— nativeEngineStart 每次 new
  // FrameRenderer，成员变量会随 engine stop/start 归零（真机日志：ratio 长期
  // 钉在初值 2.0，跨会话不收敛）。
  int ag_log_tick_ = 0;         // 预览统计日志节流（每 120 帧一条）
  void applyPendingLut();
  void bindLut(GLint loc_tex, GLint loc_on, GLint loc_strength, GLint loc_dmin,
               GLint loc_dinv, GLint loc_size, float strength);
  GLuint fbo_scene_ = 0, tex_scene_ = 0;  // RGBA16F 场景线性
  GLuint fbo_work_ = 0, tex_work_ = 0;    // RGBA16F 胶片输出
  GLuint fbo_cap_ = 0, tex_cap_ = 0;      // 成片读回（8bit RGBA8 / 10bit GL_RGB10_A2）
  // HDR gainmap（v0.11.50）：RGBA8、1/2 分辨率，R 通道 = 8bit 归一化比值。
  // 为什么单独一张纹理：ES 3.0 只保证 8bit 读回（浮点读回真机没有），所以 gainmap
  // 必须在 GPU 内算成 8bit 再读回 —— 见 kFsGainmap 的头注释。
  GLuint fbo_gainmap_ = 0, tex_gainmap_ = 0;
  int gainmap_w_ = 0, gainmap_h_ = 0;
  int dbg_gainmap_ok_ = 0;   // 本帧 gainmap 是否生成成功（0 = 无/失败，供日志）
  // gainmap 的实际数值分布（0..1 的 8bit 值）。**这是判断"HDR 有没有内容"的唯一依据**：
  // 若 min≈max≈0.5，说明 HDR/SDR 比值恒 1 ⇒ 该场景没有可表达的动态范围。
  float dbg_gm_min_ = 0.f, dbg_gm_max_ = 0.f, dbg_gm_mean_ = 0.f;
  int scene_w_ = 0, scene_h_ = 0;
  int cap_w_ = 0, cap_h_ = 0;
  // 卡 H2：tex_cap_ 当前所在的**交付档**（false = 8bit RGBA8 / true = 10bit GL_RGB10_A2）。
  // ⚠ 必须与尺寸一起记账：两个档的内部格式与 readback 口径都不同，若只按尺寸判断复用，
  //   连续拍"同尺寸的 JPEG 与 HEIF"会复用错档的 tex_cap_（8bit 附件按 2_10_10_10_REV 读
  //   = 非法组合/整幅错色，且**不报错**）。编辑器链路恒 8bit，也靠它把 10bit 附件换回来。
  bool cap_ten_bit_ = false;
  std::atomic<int64_t> frames_{0};

  struct {
    GLint st = -1, rot = -1, scale = -1, tex = -1, wb = -1;
  } u_oes_;
  struct {
    GLint st = -1, rot = -1, scale = -1, tex = -1, wb = -1;
  } u_still_;
  struct {
    GLint st = -1, rot = -1, scale = -1, tex = -1, size = -1, cfa = -1,
          black = -1, white = -1, gain = -1, ccm = -1, lsc = -1,
          lsc_size = -1;
  } u_raw_;
  struct {
    GLint tex = -1, mos = -1, mosaic = -1, size = -1, cfalut = -1, black = -1,
          white = -1, gain = -1;
  } u_rcd_dir_;
  struct {
    GLint tex = -1, mos = -1, mosaic = -1, dir = -1, size = -1, cfalut = -1,
          black = -1, white = -1, gain = -1, gorig = -1;
  } u_rcd_g_;
  struct {
    GLint st = -1, rot = -1, scale = -1, tex = -1, mos = -1, mosaic = -1,
          dir = -1, g = -1, size = -1, cfalut = -1, black = -1, white = -1,
          gain = -1, ccm = -1, lsc = -1, lsc_size = -1, cd_fix = -1,
          gorig = -1;
  } u_rcd_chroma_;
  // P3 拆双 pass（v0.10.180，[58] rcd_p3_split）：3a（vs_plain_ 源几何直写）
  // 与 3b（kVertRot 成片几何，与 kFsRcdChroma 同款坐标变换）的 uniform 句柄
  struct {
    GLint tex = -1, mos = -1, mosaic = -1, dir = -1, g = -1, size = -1,
          cfalut = -1, black = -1, white = -1, gain = -1, cd_fix = -1,
          satclip = -1;
  } u_rcd_chroma_a_;
  struct {
    GLint st = -1, rot = -1, scale = -1, rcd_a = -1, dir = -1, g = -1,
          size = -1, cfalut = -1, lsc = -1, lsc_size = -1, satclip = -1;
  } u_rcd_chroma_b_;
  // 第 4a 期（S14）：D3→D4 独立 CCM pass 的 uniform 句柄
  struct {
    GLint tex = -1, size = -1, ccm = -1;
  } u_ccm_;
  // AMaZE 档（v0.10.172）：tex_amaze_（源几何，CPU AMaZE 上传的 WB 域相机 RGB）
  // → 按成片几何旋转写入 tex_work_（D3）。几何口径与 kFsRcdChroma 逐字一致
  // （kVertRot + uST 单位阵 + uScale=1，frag 里 tuv=(vUV0.x, 1−vUV0.y)）。
  struct {
    GLint tex = -1, size = -1, st = -1, rot = -1, scale = -1;
  } u_amaze_up_;
  struct {
    GLint tex = -1, size = -1, cfalut = -1, black = -1, white = -1, gain = -1,
          noise_a = -1, noise_b = -1, mix = -1;
  } u_raw_dn_;
  struct {
    // v0.10.167：wclamp = uWClamp，HR 前置的值域保护（权重用钳位值、信号用原值）
    GLint tex = -1, size = -1, cfalut = -1, noise_a = -1, noise_b = -1, mix = -1,
          wclamp = -1;
  } u_raw_dn_f_;
  // D-2（v0.10.58）：时域 × 空间联合 P0（4 帧 × 3×3 同相位邻域）
  struct {
    GLint raw[4] = {-1, -1, -1, -1}, off[3] = {-1, -1, -1}, fused = -1,
          size = -1, off_tiles = -1, cfalut = -1, black = -1, white = -1,
          gain = -1, noise_a = -1, noise_b = -1, sr_scale = -1, sub = -1, mix = -1,
          wclamp = -1;   // v0.10.167：σ 的值域保护
  } u_raw_dn_ts_;
  struct {
    GLint tex = -1, size = -1, black = -1, white = -1, gain = -1, cfalut = -1;
  } u_mfnr_pyr_;
  struct {
    GLint tex = -1, size = -1;
  } u_mfnr_down_;
  struct {
    GLint raw[4] = {-1, -1, -1, -1}, off[3] = {-1, -1, -1}, w = -1,
          size = -1, tiles = -1, tile_m = -1, black = -1, white = -1,
          gain = -1, cfalut = -1;
  } u_mfnr_fuse_;
  struct {
    GLint raw[4] = {-1, -1, -1, -1}, off[3] = {-1, -1, -1}, size = -1,
          tiles = -1, off_tiles = -1, tile_m = -1, noise_a = -1, noise_b = -1,
          s = -1, black = -1, white = -1, gain = -1, cfalut = -1;
  } u_mfnr_mis_;
  struct {
    GLint tex = -1, size = -1, noise_a = -1, noise_b = -1, lsc = -1,
          lsc_size = -1, mix = -1, moire = -1, moire_k = -1, cg_k = -1,
          cg_scope = -1;
  } u_chroma_dn_;
  // 档 A：伪彩强度图统计（32×32 网格归约）
  struct {
    GLint src = -1, size = -1;
  } u_moire_stat_;
  // 紫边抑制（v0.10.134）三个 pass 的 uniform 句柄
  struct {
    GLint tex = -1, size = -1;
  } u_df_down_;
  struct {
    GLint src = -1, half = -1;
  } u_df_det_;
  struct {
    GLint src = -1, size = -1;
  } u_df_avg_;
  struct {
    GLint tex = -1, det = -1, size = -1, half = -1,
          chroma_avg = -1, thresh_k = -1, thresh_min = -1, thresh_max = -1,
          std_k = -1, std_max = -1, grad_min = -1;
  } u_df_apply_;
  // 色差域中值滤波（v0.10.196）
  struct {
    GLint tex = -1, size = -1, grad_lo = -1, grad_hi = -1, side_k = -1, chroma_min = -1, peak_k = -1;
  } u_cs_;
  // HDR gainmap（v0.11.50）
  struct {
    GLint hdr = -1, sdr = -1, src_size = -1, math = -1;
  } u_gainmap_;
  // 横向色差校正（v0.10.170）：① 1/8 降采样（估计输入）② 径向位移校正
  struct {
    GLint tex = -1, size = -1;
  } u_ca_ds_;
  struct {
    GLint tex = -1, size = -1, center = -1, kr = -1, kb = -1, maxshift = -1;
  } u_ca_corr_;
  struct {
    GLint tex = -1, size = -1, noise_a = -1, noise_b = -1, lsc = -1,
          lsc_size = -1;
  } u_chroma_down_;
  struct {
    GLint prev = -1, sigma = -1, size = -1, step = -1, k = -1, revert = -1,
          cg_k = -1;
  } u_chroma_at_;
  struct {
    GLint tex = -1, coarse = -1, d[5] = {-1, -1, -1, -1, -1}, size = -1,
          half = -1, noise_a = -1, noise_b = -1, lsc = -1, lsc_size = -1,
          kr = -1;
  } u_chroma_apply_;
  // PC ESD3D2 完整移植（v0.10.175）：
  //   u_pc3d_      = 本体（k / kernel / sigA / sigB / moire / luma / lsc）
  //   u_pc3d_down_ = gaussdown（resize / stride / sigma）
  //   u_pc3d_up_   = guidedupsample（low / guidehi / lowSize / hiSize / R）
  struct {
    GLint tex = -1, size = -1, k = -1, kernel = -1, sig_a = -1, sig_b = -1,
          moire = -1, luma = -1, lsc = -1, lsc_size = -1;
  } u_pc3d_;
  struct {
    GLint tex = -1, size = -1, resize = -1, stride = -1, sigma = -1;
  } u_pc3d_down_;
  struct {
    GLint low = -1, guide_hi = -1, low_size = -1, hi_size = -1, r = -1;
  } u_pc3d_up_;
  // RAW 域高光重建（darktable highlights）：mask / dilate / chroma / apply
  // mono（v0.10.167）= 源布局开关：1 = 单通道 R16F（值在 .r），0 = RGBA16F 相位布局
  struct {
    GLint mos = -1, stat = -1, statraw = -1, mono = -1, size = -1, cfalut = -1,
          clips = -1;
  } u_hl_mask_;
  struct {
    GLint mask = -1, msize = -1;
  } u_hl_dil_;
  struct {
    GLint mos = -1, stat = -1, statraw = -1, mono = -1, dil = -1, size = -1,
          msize = -1, cfalut = -1, clips = -1, mode = -1;
  } u_hl_chroma_;
  struct {
    // clips = uSatClip（满阱，仅 allSat 判定）；hlclip = uHlClip（裁剪判据 0.987，
    // v0.10.161 —— 与满阱解耦，理由见 passHighlightRaw 的域核实注释）
    GLint mos = -1, stat = -1, statraw = -1, mono = -1, size = -1, cfalut = -1,
          clips = -1, hlclip = -1, chroma = -1, ref = -1, grecon = -1, gcap = -1,
          rbrecon = -1, refglobal = -1, refsize = -1, ref2 = -1, ref2size = -1;
  } u_hl_apply_;
  struct {
    // v0.10.167：mono = 源布局（本 pass 仍读 uMos；前置后 uMos 即 P0 前的 mosaic）
    GLint mos = -1, size = -1, cfalut = -1, clips = -1, block = -1, mono = -1;
  } u_hl_ref_;
  struct {
    GLint ref1 = -1, refsize = -1;
  } u_hl_refdown_;
  // NN pass（v0.10.102/104）：pack / unpack 两个 fragment 程序的 uniform
  struct {
    GLint fused = -1, raw = -1, size = -1, cfalut = -1, black = -1, white = -1,
          gain = -1, src = -1, scale = -1, phx = -1, phy = -1;
  } u_nn_pack_;
  struct {
    GLint packed = -1, size = -1, cfalut = -1, gain = -1, iscale = -1,
          chmap = -1;
  } u_nn_unpack_;
  struct {
    // v0.7.9：contrast/shoulder/toe/cross（旧 log 域胶片链）已随链删除；
    // FilmParams 槽位保留解析（契约不移位），引擎不再消费。
    GLint tex = -1, sat = -1, split = -1, mono = -1, tone_k = -1, look = -1,
          look_sat = -1, look_chroma = -1, local = -1,
          local_strength = -1, local_knee = -1, local_knee_hi = -1, local_ref = -1,
          local_size = -1, hal_domain = -1, domain_ratio = -1,
          // ── filmic（v0.7.3，darktable v3 spline）────────────────────────────
          // 三段四次多项式 y = M1 + x·(M2 + x·(M3 + x·(M4 + x·M5)))。系数由 CPU 侧
          // computeFilmicSpline 求解。
          // E4-4：**per-channel**（vec3 的 .r/.g/.b = R/G/B 三层乳剂），按**段**分组
          // （旧版是按段打包进一个 vec3、三通道共用）。toe 段 5 个 + shoulder 段 5 个 +
          // 中间线性段 2 个（y = m1 + m2·x）+ 逐通道过渡区位置 2 个 = 14 个 vec3。
          // shader 侧**手写 R/G/B 三份**求值（GPU 三禁：无数组参数、无动态索引、无多行宏）。
          filmic_toe_m1 = -1, filmic_toe_m2 = -1, filmic_toe_m3 = -1,
          filmic_toe_m4 = -1, filmic_toe_m5 = -1,
          filmic_sh_m1 = -1, filmic_sh_m2 = -1, filmic_sh_m3 = -1,
          filmic_sh_m4 = -1, filmic_sh_m5 = -1,
          filmic_lin_m1 = -1, filmic_lin_m2 = -1,
          filmic_lat_min = -1, filmic_lat_max = -1,
          filmic_black_ev = -1, filmic_dr = -1, filmic_input_min = -1,
          filmic_input_max = -1, filmic_power = -1, filmic_black_disp = -1,
          gamut = -1,  // v0.10.13：darktable filmic v5 gamut mapping（0 = A/B 关）
          // ── 引擎 1（sigmoid，阶段 2）────────────────────────────────────────
          // 7 个标量由 CPU 侧 `computeSigmoidParams`（film/SigmoidParams.h，逐行照抄
          // darktable `commit_params`）求解，init() 期算一次存 `sigmoid_`，每帧无条件
          // 下发。**引擎 0 的 program 里这 7 个 location = −1**（该 program 不含这些
          // uniform）⇒ GL 规范下 `glUniform*(−1, …)` 是安全 no-op ⇒ 零分支、零风险。
          // `sig_black_target` 在 per-channel 路径下**不参与曲线**（见 shader 注释），
          // 仍查询 + 下发 = 与 CPU 侧 7 标量契约一一对应。
          sig_white_target = -1, sig_black_target = -1, sig_paper_exposure = -1,
          sig_film_fog = -1, sig_film_power = -1, sig_paper_power = -1,
          sig_hue_preservation = -1,
          exposure = -1;  // v0.7.8：用户 EV 滑杆（影调链后的显示亮度补偿）
  } u_film_;
  struct {
    GLint tex = -1, px = -1,
          // E4-3 颗粒（granularity）：与 u_style_ 同名同义 —— 两条链路共用一段
          // **逐字相同**的 shader 逻辑与 CPU 下发段（ADR-9 末段 / ADR-11）
          grain_amount = -1, grain_seed = -1, grain_zoom = -1, grain_norm = -1,
          grain_lut = -1,
          flip_y = -1, zebra = -1, sharpen = -1, lut = -1, lut_on = -1,
          lut_strength = -1, lut_dmin = -1, lut_dinv = -1, lut_size = -1,
          // E4-2 暗角（vignette）：与 u_style_ 同名同义 —— 两条链路共用一段
          // 逐字相同的 shader 逻辑（ADR-9）
          vig_scale = -1, vig_dscale = -1, vig_fscale = -1, vig_brightness = -1,
          // U-4 清晰度 + 影调分区 + 褪色（display 域，OETF 之后）：与 u_style_ 同名同义
          // —— 两条链路共用一段**逐字相同**的 shader 数学（照抄 kFsStyle 的 ④/①/② 段）。
          // 唯一的域差异 = 清晰度 tap 要在本 shader 内过 highlightRolloff + srgbEncode
          //（uTex 是滚降前的线性显示域），见 kFsOut 的 blurLuma16 注释。
          clarity = -1, clar_step = -1,
          highlights = -1, shadows = -1, whites = -1, blacks = -1, fade = -1,
          tone_on = -1,
          // U-1 [23][24] split + [25] mono —— 期 B 从 u_film_ 挪来（那边是**线性工作空间
          // 域**，与 u_style_ 的显示域差 43%）；与 u_style_ 同名同义，两条链路共用一段
          // **逐字相同**的显示域数学。位置 = 暗角之后、LUT 之前（与 kFsStyle 同序）。
          split = -1, mono = -1,
          // 期 B：引擎 1 的对比度（引擎 0 恒等 1.0）—— 见 kFsOut 的 uContrastOut 注释
          contrast_out = -1,
          // SG-1 柔光：与 u_style_ 同名同义 —— 两条链路共用一段**逐字相同**的
          // shader 逻辑（glowFetch + Screen），落点都在各自 shader 的显示域段。
          glow = -1, glow_on = -1, glow_strength = -1, glow_texel = -1,
          // 卡 H2：dither 除数（交付档上限）。8bit = 255.0、10bit HEIF = 1023.0 ——
          // 值来自 OutputBitDepth.h 的 outputMaxOf()，**只在成片链**按档切换
          //（预览/编辑器恒 8bit）。见 kFsOut 的 dither 行注释。
          out_max = -1;
  } u_out_;
  // kFsStyle（E1-3 编辑器通路）
  struct {
    GLint tex = -1, lut = -1,
          // E4-3 颗粒（granularity）：见 u_out_。**旧的 uSeed 已删除** —— 全仓核对
          // 确认它只服务颗粒（dither 走 dhash，不读 uSeed），故由 uGrainSeed 取代，
          // 不存在"改名后 dither 跟着变"的连带影响。
          grain_amount = -1, grain_seed = -1, grain_zoom = -1, grain_norm = -1,
          grain_lut = -1,
          lut_on = -1, lut_strength = -1, lut_dmin = -1, lut_dinv = -1,
          lut_size = -1,
          // E4-2 暗角（vignette）：见 u_out_
          vig_scale = -1, vig_dscale = -1, vig_fscale = -1, vig_brightness = -1,
          // E2-2 基础调整（ADR §5.6）：ev/wb = 线性域往返段；contrast/sat/
          // clarity = 显示域段。门控 *_on 由 CPU 侧算（默认恒等 ⇒ 整段跳过）。
          ev = -1, wb_gain = -1, exp_wb_on = -1, contrast = -1, contrast_on = -1,
          // U-2：[20..22] 分区饱和三档（替代原来的单值 uSat）——权重与 kFsFilm 同口径。
          sat_shadow = -1, sat_mid = -1, sat_high = -1, sat_on = -1,
          // U-2：胶片曲线（[5..10] latitude + [37][38] 黑白点，显示域）。`curve_on`
          // 由 CPU 侧算；两组三段多项式（cur / base）+ 2×2 个分段点 + 端点重映射
          // 的 2 个 vec4。逐通道手写 R/G/B 求值（GPU 三禁），见 kFsStyle 的同名声明。
          curve_on = -1, curve_lo = -1, curve_hi = -1,
          curve_toe_at = -1, curve_sh_at = -1,
          curve_toe_m1 = -1, curve_toe_m2 = -1, curve_toe_m3 = -1,
          curve_toe_m4 = -1, curve_toe_m5 = -1,
          curve_sh_m1 = -1, curve_sh_m2 = -1, curve_sh_m3 = -1,
          curve_sh_m4 = -1, curve_sh_m5 = -1,
          curve_lin_m1 = -1, curve_lin_m2 = -1,
          base_toe_at = -1, base_sh_at = -1,
          base_toe_m1 = -1, base_toe_m2 = -1, base_toe_m3 = -1,
          base_toe_m4 = -1, base_toe_m5 = -1,
          base_sh_m1 = -1, base_sh_m2 = -1, base_sh_m3 = -1,
          base_sh_m4 = -1, base_sh_m5 = -1,
          base_lin_m1 = -1, base_lin_m2 = -1,
          clarity = -1, clar_step = -1,
          // E2-6 局部调整（ADR §5.7）：mask 程序化生成（纯算术，无纹理/无 pass）。
          // 首版只支持**一个**局部调整（GLSL 三禁 ⇒ 多组参数无法安全表达）。
          local_on = -1, local_type = -1, local_center = -1, local_radius = -1,
          local_feather = -1, local_angle = -1, local_width = -1, local_ev = -1,
          local_ev_on = -1, local_sat = -1, local_sat_on = -1,
          // E2-11 影调分区 + 褪色（display 域）：[90..93] 四项色阶 + [94] 褪色。
          // ⚠️ 色温/色调 [88][89] **没有** uniform —— CPU 侧折算成 RGB 增益后并入
          // `uWbGain`（ADR-14 裁决 1：同属线性域增益 ⇒ 复用已有的成对往返，
          // 零额外 GPU 成本；也因此 chainLuma 的域提升自动带上它们，无需改动）。
          highlights = -1, shadows = -1, whites = -1, blacks = -1, fade = -1,
          tone_on = -1,
          // U-1 分离色调 [23][24] + 单色 [25]（display 域）：与 u_film_ 同名同义 ——
          // kFsStyle 里照抄 kFsFilm 的同一套数学（同系数、同 Rec.709 分区权重）。
          split = -1, mono = -1,
          // E2-12 裁剪 + 90° 步进旋转（ADR-16）：输出坐标 → 原图 UV 的仿射映射
          //（origin + o×size，再按 rot 置换轴）。不裁剪 = (0,0)/(1,1)/0 ⇒ 恒等。
          crop_origin = -1, crop_size = -1, crop_rot = -1,
          // SG-1 柔光：与 u_out_ 同名同义（同一段 shader 数学）。
          // ⚠ 采样坐标用 cropUV(vUV0) —— 柔光图是**全图**（edit_w_×edit_h_，
          //   与 halation 的 style_src 同坐标系），裁剪映射必须与 uTex 一致。
          glow = -1, glow_on = -1, glow_strength = -1, glow_texel = -1;
  } u_style_;
  struct {
    GLint tex = -1, lut = -1, lut_on = -1, lut_strength = -1, lut_dmin = -1,
          lut_dinv = -1, lut_size = -1, stat_ch = -1, stat_mode = -1;
  } u_hist_;
  // kFsLocal（局部低频图的下采样 shader）
  struct {
    GLint tex = -1, step = -1;
  } u_local_src_;
  GLint u_fill_st_ = -1;
  struct {
    GLint rot = -1, scale = -1;
  } u_fill_;
};

}  // namespace filmkit
