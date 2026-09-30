#pragma once

#include <GLES3/gl32.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "../film/FilmParams.h"

namespace filmkit {

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
  float ns_typ = 0.f;             // 中灰典型 σ（WB 后 G 通道，单帧；resFade/日志）
  bool model_src = false;         // true = Camera2 模型，false = legacy σ 回退
};

// tile RMS 归约的诊断结果（C 专项；A 的 σ 输入底座）
struct TileRmsResult {
  bool ok = false;
  int tiles_w = 0, tiles_h = 0;
  float rms[4] = {0, 0, 0, 0};   // 采样 tile 的 RMS（中心/左上/右上/左下）
  float sigma[4] = {0, 0, 0, 0}; // 代入模型后的 σ（G 通道）
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
  bool submitStill(const void* y, const void* u, const void* v, int w, int h,
                   int y_stride, int uv_stride, int uv_pix, int rotation,
                   const FilmParams& p, std::vector<uint8_t>* out_rgba);

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
  bool submitRaw(const void* raw, int w, int h, int row_stride_px, int cfa,
                 const float black_phase[4], int white_level,
                 float neutral_r, float neutral_g, float neutral_b,
                 const float* ccm9, const float* lsc, int lsc_cols,
                 int lsc_rows, int rotation, const FilmParams& p, int demosaic,
                 float noise_strength, const float* noise_model,
                 float noise_sigma, std::vector<uint8_t>* out_rgba);

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
                      float ref_noise_scale, bool use_dft,
                      std::vector<uint8_t>* out_rgba);

  // 预览实时直方图（M3）：胶片输出纹理 → 显示域亮度（OETF 同 kFsOut）→ 256×96
  // 下采样 → 读回统计到 out[0..n-1]（n 建议 256）。渲染线程调用；无可用帧返回 false。
  bool histogram(int* out, int n);

  int64_t frameCount() const { return frames_.load(std::memory_order_relaxed); }
  int sceneW() const { return scene_w_; }
  int sceneH() const { return scene_h_; }

 private:
  bool ensureTargets(int w, int h);
  bool ensureRcdTargets(int w, int h);
  bool ensureDnTarget(int w, int h);
  bool ensureChromaMst(int w, int h);
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
  void passRawDenoiseFused(int w, int h, int cfa, const float black_phase[4],
                           float white_level, const float gains[3],
                           const NoiseCoef& nc);
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
  // out_probe[4] = 收口后 tex_fused_ 的 4 个采样点（黑图归因探针）。
  bool passMfnrFft(int w, int h, int cfa, const float black_phase[4],
                   float white_level, const float gains[3],
                   const float plane_a[3], const float plane_b[3],
                   float* out_neff, float* out_probe,
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
    float dn_k = 0.f;  // 降噪强度（日志）
    NoiseCoef nc;         // (A,B) 系数（P0/P4/mismatch，C 专项）
    NoiseModel nm;        // 原始噪声模型（WB 前、单帧；日志 nzS/nzO）
    TileRmsResult rms;    // tile RMS 归约诊断（P0 后）
    float ns = 0.f;       // 典型 σ（融合折减后；resFade/日志）
    float ns_raw = 0.f;   // 单帧典型 σ（日志 nsHal / nsSng 兼容）
    bool lsc_ok = false;
    int lsc_cols = 0, lsc_rows = 0;
    float upload_ms = 0.f;  // t0 → 传参时刻（MFNR 时含上传+对齐+融合）
    bool mfnr_fused = false;
    std::string mfnr_kv;  // MFNR 时非空：追加进 raw.render
  };
  void finishStillChain(const ChainCtx& c,
                        std::chrono::steady_clock::time_point t0,
                        std::vector<uint8_t>* out_rgba);
  // WB 增益 / CCM / LSC 纹理准备（两条静拍路径共用）
  struct RawCommon {
    float gains[3] = {1.f, 1.f, 1.f};
    float ccm[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    bool lsc_ok = false;
    int lsc_w = 1, lsc_h = 1;
  };
  // wb_r/g/b = FilmParams 的用户 WB 补偿（与预览 OES / YUV 静拍的 uWb 同源）：
  // RAW 链在 asShotNeutral 增益之上相乘，缺它则手动 WB 只动预览不动 M 档成片。
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
                float white_level, const float gains[3], bool mosaic);
  void passRcdChroma(int dst_w, int dst_h, int rot, int cfa,
                     const float black_phase[4], float white_level,
                     const float gains[3], const float* ccm, bool mosaic);
  // M3-2 降噪：P0 RAW 域同色 bilateral（输出线性 mosaic）；P4 色度平滑。
  // C 专项起值域 σ 由 (A,B) 模型逐像素给出（nc，强度已折入系数）。
  void passRawDenoise(int w, int h, int cfa, const float black_phase[4],
                      float white_level, const float gains[3],
                      const NoiseCoef& nc);
  void passChromaDenoise(int dst_w, int dst_h, int rot, const NoiseCoef& nc);
  // D 专项（v0.10.53）：色度多尺度降噪（半分辨率 à trous 5 级）
  bool passChromaMultiScale(int dst_w, int dst_h, const NoiseCoef& nc);
  static constexpr int kChromaLevels = 5;
  // C 专项：tile RMS 归约（HDR+ §5 —— 逐 tile RMS 代入噪声模型得 σ，A 的输入
  // 底座）。每像素输出一个 tile 的 RMS（mosaic 信号，线性域）。dst = tiles 网格。
  // a/b = k=1 的 WB 后系数（NoiseCoef::mma/mmb），用于读回诊断 σ。返回诊断供日志。
  TileRmsResult passTileRms(GLuint src_tex, int w, int h, const float a[3],
                            const float b[3]);
  // M4-7 局部低频图：尺寸变化时重建（RGBA8 + LINEAR）。返回是否可用。
  bool ensureLocalTarget(int w, int h);
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
    // v0.10.28：采样 max（最高非空桶，darktable autodetect 的 max 语义）——
    // 白点上探的主驱动量：小面积近饱和高光（白壶身镜面，占比 0.001% 级）
    // 任何分位都看不见，见 162012 真机定案。
    float p_max = 0.0f;
    bool valid = false;
  };
  SceneStats measureSceneStats(GLuint src);
  // ── filmic spline（darktable `dt_iop_filmic_rgb_compute_spline` 的 v3 分支）──
  // 公式出处与逐项对照见 .cpp；纯函数，可离线用同一组 EV 复算系数对拍。
  struct FilmicSpline {
    float m1[3] = {0, 0, 0}, m2[3] = {1, 1, 1}, m3[3] = {0, 0, 0};
    float m4[3] = {0, 0, 0}, m5[3] = {0, 0, 0};   // [0]=toe [1]=shoulder [2]=线性段
    float lat_min = 0.0f, lat_max = 1.0f;         // toe_log / shoulder_log
    float black_ev = -7.65f, white_ev = 4.56f, dynamic_range = 12.21f;
    float input_min = 0.0f, input_max = 1.0f;
    float output_power = 4.0f, black_disp = 0.0f, grey_log = 0.5f;
    float contrast = 1.0f;
    // v0.10.27：darktable contrast 滑杆参数（默认 1.0 = 中性）。自动白点高光上探
    // 时由 updateFilmicEV 按 power 前后比设定，补偿 gamma 回退；固定曲线恒 1.0。
    float contrast_param = 1.0f;
  };
  static FilmicSpline computeFilmicSpline(float black_ev, float white_ev,
                                          float contrast_param = 1.0f,
                                          float latitude = 0.01f);
  // filmic 黑白点（v0.7.6）：来自**用户配置**（FilmParams[37]/[38]，默认 −7.65/+4.56，
  // 即 darktable 默认 / PhotonCamera 固化值）。auto_on=true 时黑点改由场景分位自动
  // （白点仍用配置值）。理由见 .cpp。
  // still=true（静拍）用当帧值不做 EMA —— 单帧机会，历史值等于用错曝光（v0.6.28 实测）。
  void updateFilmicEV(GLuint src, bool still, bool auto_on, float user_black,
                      float user_white, float white_hl, float user_ev);
  void passFilm(int w, int h, const FilmParams& p, GLuint src_tex, bool still);
  void passFilm(int w, int h, const FilmParams& p);
  // zebra —— 过曝提示（仅预览；成片传 false，提示绝不进成片）
  void passOutput(int w, int h, const FilmParams& p, bool flip_y, bool zebra);
  void passFill(int dst_w, int dst_h);

  static void makeTex(GLuint* tex, GLuint* fbo, GLenum internal, int w, int h);
  void logPassDiag(const char* stage, GLuint fbo, bool half_float);

  GLuint prog_oes_ = 0, prog_still_ = 0, prog_film_ = 0, prog_out_ = 0,
         prog_fill_ = 0, prog_raw_ = 0, prog_rcd_dir_ = 0, prog_rcd_g_ = 0,
         prog_rcd_chroma_ = 0, prog_raw_dn_ = 0, prog_chroma_dn_ = 0,
         prog_raw_dn_ts_ = 0,
         prog_chroma_down_ = 0, prog_chroma_at_ = 0, prog_chroma_apply_ = 0,
         prog_hist_ = 0, prog_local_ = 0, prog_tile_rms_ = 0;
  GLuint vs_rot_ = 0, vs_plain_ = 0;

  GLuint oes_tex_ = 0;
  int yuv_matrix_ = 0;
  GLuint still_tex_ = 0;  // RGB8，静拍 CPU 转换后上传
  int still_w_ = 0, still_h_ = 0;
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
  GLuint tex_off_[3] = {0, 0, 0};         // RGBA16F 位移场（tiles×tiles）
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
  GLuint fbo_mos_ = 0, tex_mos_ = 0;  // RGBA16F，P0 降噪后线性 mosaic
  GLuint fbo_dn_ = 0, tex_dn_ = 0;    // RGBA16F，P4 色度平滑输出
  int dn_w_ = 0, dn_h_ = 0;
  // D 专项：半分辨率 à trous 色度降噪缓冲（2× 近似层 ping-pong + 5× 细节层
  // + 1× σ 图；RGBA16F、1/2 分辨率 ≈ 25MB/张）
  GLuint tex_ch_a_[2] = {0, 0}, fbo_ch_a_[2] = {0, 0};
  GLuint tex_ch_dp_[kChromaLevels] = {0}, fbo_ch_dp_[kChromaLevels] = {0};
  GLuint tex_ch_sig_ = 0, fbo_ch_sig_ = 0;
  int ch_w_ = 0, ch_h_ = 0;
  // v0.10.59 诊断：本轮新增算法是否真的执行（0.10.58 起日志无字段，无法验证生效）
  int dbg_p0s_ = 0;      // P0 实际样本数（9 = 旧"融合后"路径，36 = 时域×空间联合）
  int dbg_cms_ = 0;      // 色度多尺度（D-1）是否执行
  float dbg_cms_k_ = 0.f;
  // C 专项：tile RMS 归约（R16F，tiles 网格；A 的 σ 输入底座，见 passTileRms）
  GLuint fbo_rms_ = 0, tex_rms_ = 0;
  int rms_w_ = 0, rms_h_ = 0;
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
  GLuint runHighlightReconstruct(GLuint src, int w, int h,
                                 int* out_clipped_blocks, float* out_ms);
  bool ensureHrTargets(int w, int h);
  void releaseHrTargets();
  // passFilm 内的 HR 观测（raw.render 的 hr/hrMs/clipB 由 finishStillChain 读取）
  int hr_blocks_ = -1;
  float hr_ms_ = 0.0f;
  GLuint fbo_hist_ = 0, tex_hist_ = 0;  // RGBA8 直方图下采样目标（256×96）
  int hist_w_ = 0, hist_h_ = 0;
  // M4-7 局部色调映射的低频亮度图（RGBA8、LINEAR 上采样）。与 tex_hist_
  // 同源（都走 kFsHist 的显示域 luma），但**分辨率低得多**才叫"局部"。
  // v0.6.34：尺寸不再固定 32×24，改为**随渲染分辨率**按"每格 ≈128px"算
  //（ensureLocalTarget），否则预览（1216×1621）的等效提亮半径只有成片的 1/3，
  // 预览与成片的局部提亮分布对不上。init 仍建 32×24 作兜底绑定。
  GLuint fbo_local_ = 0, tex_local_ = 0;
  int local_w_ = 0, local_h_ = 0;
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
  GLuint fbo_cap_ = 0, tex_cap_ = 0;      // RGBA8 拍照读回
  int scene_w_ = 0, scene_h_ = 0;
  int cap_w_ = 0, cap_h_ = 0;
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
          black = -1, white = -1, gain = -1;
  } u_rcd_g_;
  struct {
    GLint st = -1, rot = -1, scale = -1, tex = -1, mos = -1, mosaic = -1,
          dir = -1, g = -1, size = -1, cfalut = -1, black = -1, white = -1,
          gain = -1, ccm = -1, lsc = -1, lsc_size = -1;
  } u_rcd_chroma_;
  struct {
    GLint tex = -1, size = -1, cfalut = -1, black = -1, white = -1, gain = -1,
          noise_a = -1, noise_b = -1;
  } u_raw_dn_;
  struct {
    GLint tex = -1, size = -1, cfalut = -1, noise_a = -1, noise_b = -1;
  } u_raw_dn_f_;
  // D-2（v0.10.58）：时域 × 空间联合 P0（4 帧 × 3×3 同相位邻域）
  struct {
    GLint raw[4] = {-1, -1, -1, -1}, off[3] = {-1, -1, -1}, fused = -1,
          size = -1, off_tiles = -1, cfalut = -1, black = -1, white = -1,
          gain = -1, noise_a = -1, noise_b = -1, sr_scale = -1;
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
          lsc_size = -1;
  } u_chroma_dn_;
  struct {
    GLint tex = -1, size = -1, noise_a = -1, noise_b = -1, lsc = -1,
          lsc_size = -1;
  } u_chroma_down_;
  struct {
    GLint prev = -1, sigma = -1, size = -1, step = -1, k = -1, revert = -1;
  } u_chroma_at_;
  struct {
    GLint tex = -1, coarse = -1, d[5] = {-1, -1, -1, -1, -1}, size = -1,
          half = -1, noise_a = -1, noise_b = -1, lsc = -1, lsc_size = -1, l = -1,
          kr = -1;
  } u_chroma_apply_;
  struct {
    GLint tex = -1, size = -1, noise_a = -1, noise_b = -1;
  } u_tile_rms_;
  struct {
    // v0.7.9：contrast/shoulder/toe/cross（旧 log 域胶片链）已随链删除；
    // FilmParams 槽位保留解析（契约不移位），引擎不再消费。
    GLint tex = -1, sat = -1, split = -1, mono = -1, tone_k = -1, look = -1,
          look_sat = -1, look_chroma = -1, local = -1,
          local_strength = -1, local_knee = -1, local_knee_hi = -1, local_ref = -1,
          local_size = -1, preview_map = -1, domain_ratio = -1,
          // ── filmic（v0.7.3，darktable v3 spline）────────────────────────────
          // 三段四次多项式 y = M1 + x·(M2 + x·(M3 + x·(M4 + x·M5)))，下标
          // [0]=toe [1]=shoulder [2]=中间线性段。系数由 CPU 侧 computeFilmicSpline 求解。
          filmic_m1 = -1, filmic_m2 = -1, filmic_m3 = -1, filmic_m4 = -1,
          filmic_m5 = -1, filmic_lat_min = -1, filmic_lat_max = -1,
          filmic_black_ev = -1, filmic_dr = -1, filmic_input_min = -1,
          filmic_input_max = -1, filmic_power = -1, filmic_black_disp = -1,
          gamut = -1,  // v0.10.13：darktable filmic v5 gamut mapping（0 = A/B 关）
          exposure = -1;  // v0.7.8：用户 EV 滑杆（影调链后的显示亮度补偿）
  } u_film_;
  struct {
    GLint tex = -1, px = -1, grain_amount = -1, grain_size = -1, time = -1,
          flip_y = -1, zebra = -1, sharpen = -1, lut = -1, lut_on = -1,
          lut_strength = -1, lut_dmin = -1, lut_dinv = -1, lut_size = -1;
  } u_out_;
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
