#pragma once

#include <GLES3/gl31.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <vector>

#include "../film/FilmParams.h"

namespace filmkit {

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
  //   noise_level —— 0 = 关；1 = 标准；2 = 强（M3-2：P0 RAW 域 bilateral + P4
  //                  色度平滑，仅 RCD 路径生效；bilinear 保持纯净基线）
  //   noise_sigma —— 归一化域噪声 σ（SENSOR_NOISE_PROFILE，≤0 时引擎内回退估计）
  //   lsc/lsc_cols/lsc_rows —— 镜头阴影增益网格（RGB 交错，cols×rows，(0,0)=左上；
  //                  nullptr 或尺寸 ≤1 = 恒等，即 LSC 关闭（A/B 对照））
  bool submitRaw(const void* raw, int w, int h, int row_stride_px, int cfa,
                 const float black_phase[4], int white_level,
                 float neutral_r, float neutral_g, float neutral_b,
                 const float* ccm9, const float* lsc, int lsc_cols,
                 int lsc_rows, int rotation, const FilmParams& p, int demosaic,
                 int noise_level, float noise_sigma,
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
  // hl_k —— 高光重建强度（FilmParams.hl_recon，0 = 关；CCM 前做，见 .cpp）
  void passRcdChroma(int dst_w, int dst_h, int rot, int cfa,
                     const float black_phase[4], float white_level,
                     const float gains[3], const float* ccm, bool mosaic,
                     float hl_k);
  // M3-2 降噪：P0 RAW 域同色 bilateral（输出线性 mosaic）；P4 色度平滑
  // k —— 值域 σ 系数（P0 标准 0.50 / 强 2.20；P4 标准 1.80 / 强 2.60）
  void passRawDenoise(int w, int h, int cfa, const float black_phase[4],
                      float white_level, const float gains[3], float ns,
                      float k);
  void passChromaDenoise(int dst_w, int dst_h, int rot, float ns, float k);
  // M4-6 自适应影调：从 src 纹理统计"显示域亮度直方图"（kFsHist 自带 OETF，
  // 与公开的 histogram() 同一实现），取**中位**折回线性归一到 baseTone 的中间调
  // 不动点（0.2184）→ 曝光增益（限幅 [1,4]×，只提亮不压暗）。
  // auto_gain_ 做 EMA 平滑（首帧直通）防抖动。
  // bypass_lut：自适应影调统计场景本体，不让风格 LUT 改曝光（UI 直方图仍含 LUT）。
  bool histogramFrom(GLuint src, int* out, int n, bool bypass_lut = false);
  // 把 src 的"显示域亮度图"（kFsHist，OETF 同 kFsOut）渲染到 fbo（w×h）。
  // 直方图统计与局部色调映射的低频图共用这一次 draw。
  void renderLuma(GLuint src, GLuint fbo, int w, int h, float lut_strength);
  float updateAutoGain(GLuint src);
  void passFilm(int w, int h, const FilmParams& p, GLuint src_tex);
  void passFilm(int w, int h, const FilmParams& p);
  // zebra —— 过曝提示（仅预览；成片传 false，提示绝不进成片）
  void passOutput(int w, int h, const FilmParams& p, bool flip_y, bool zebra);
  void passFill(int dst_w, int dst_h);

  static void makeTex(GLuint* tex, GLuint* fbo, GLenum internal, int w, int h);
  void logPassDiag(const char* stage, GLuint fbo, bool half_float);

  GLuint prog_oes_ = 0, prog_still_ = 0, prog_film_ = 0, prog_out_ = 0,
         prog_fill_ = 0, prog_raw_ = 0, prog_rcd_dir_ = 0, prog_rcd_g_ = 0,
         prog_rcd_chroma_ = 0, prog_raw_dn_ = 0, prog_chroma_dn_ = 0,
         prog_hist_ = 0;
  GLuint vs_rot_ = 0, vs_plain_ = 0;

  GLuint oes_tex_ = 0;
  int yuv_matrix_ = 0;
  GLuint still_tex_ = 0;  // RGB8，静拍 CPU 转换后上传
  int still_w_ = 0, still_h_ = 0;
  GLuint raw_tex_ = 0;  // R16UI，Bayer 原始（texelFetch 整型采样）
  int raw_w_ = 0, raw_h_ = 0;
  // LSC 增益网格（RGB16F，NEAREST；1×1 全 1 = 关闭/无数据，见 submitRaw）
  GLuint lsc_tex_ = 0;
  int lsc_w_ = 0, lsc_h_ = 0;
  GLuint fbo_dir_ = 0, tex_dir_ = 0;  // RGBA16F，RCD 方向场（vh + pq 统计）
  GLuint fbo_g_ = 0, tex_g_ = 0;      // RGBA16F，RCD 全图 G（g 通道有效）
  int rcd_w_ = 0, rcd_h_ = 0;
  GLuint fbo_mos_ = 0, tex_mos_ = 0;  // RGBA16F，P0 降噪后线性 mosaic
  GLuint fbo_dn_ = 0, tex_dn_ = 0;    // RGBA16F，P4 色度平滑输出
  int dn_w_ = 0, dn_h_ = 0;
  GLuint fbo_hist_ = 0, tex_hist_ = 0;  // RGBA8 直方图下采样目标（256×96）
  int hist_w_ = 0, hist_h_ = 0;
  // M4-7 局部色调映射的低频亮度图（RGBA8，32×24、LINEAR 上采样）。与 tex_hist_
  // 同源（都走 kFsHist 的显示域 luma），但**分辨率低得多**才叫"局部"：256×96 在
  // 4080×3072 上等效半径只有 16×32px，等于逐像素亮度，会把暗部内部的细节压平。
  GLuint fbo_local_ = 0, tex_local_ = 0;
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
  float auto_gain_ = 0.0f;     // 自适应曝光增益（≤ 0 = 未初始化，首帧直通）
  float film_gain_ = 1.0f;     // 本帧 passFilm 实际用的曝光增益（raw.render 记 ag）
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
    GLint st = -1, rot = -1, scale = -1, tex = -1, wb = -1, exposure = -1;
  } u_oes_;
  struct {
    GLint st = -1, rot = -1, scale = -1, tex = -1, wb = -1, exposure = -1;
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
          gain = -1, ccm = -1, hl_k = -1, lsc = -1, lsc_size = -1;
  } u_rcd_chroma_;
  struct {
    GLint tex = -1, size = -1, cfalut = -1, black = -1, white = -1, gain = -1,
          ns = -1, k = -1;
  } u_raw_dn_;
  struct {
    GLint tex = -1, size = -1, ns = -1, k = -1;
  } u_chroma_dn_;
  struct {
    GLint tex = -1, contrast = -1, shoulder = -1, toe = -1, cross = -1,
          sat = -1, split = -1, mono = -1, tone_k = -1, look = -1,
          look_sat = -1, look_chroma = -1, exposure_gain = -1, local = -1,
          local_strength = -1, local_knee = -1, local_ref = -1;
  } u_film_;
  struct {
    GLint tex = -1, px = -1, grain_amount = -1, grain_size = -1, time = -1,
          flip_y = -1, zebra = -1, lut = -1, lut_on = -1, lut_strength = -1,
          lut_dmin = -1, lut_dinv = -1, lut_size = -1;
  } u_out_;
  struct {
    GLint tex = -1, lut = -1, lut_on = -1, lut_strength = -1, lut_dmin = -1,
          lut_dinv = -1, lut_size = -1;
  } u_hist_;
  GLint u_fill_st_ = -1;
  struct {
    GLint rot = -1, scale = -1;
  } u_fill_;
};

}  // namespace filmkit
