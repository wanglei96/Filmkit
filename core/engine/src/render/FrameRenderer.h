#pragma once

#include <GLES3/gl31.h>

#include <atomic>
#include <cstdint>
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
  bool submitRaw(const void* raw, int w, int h, int row_stride_px, int cfa,
                 const float black_phase[4], int white_level,
                 float neutral_r, float neutral_g, float neutral_b,
                 const float* ccm9, int rotation, const FilmParams& p,
                 int demosaic, std::vector<uint8_t>* out_rgba);

  int64_t frameCount() const { return frames_.load(std::memory_order_relaxed); }
  int sceneW() const { return scene_w_; }
  int sceneH() const { return scene_h_; }

 private:
  bool ensureTargets(int w, int h);
  bool ensureRcdTargets(int w, int h);
  void passOES(int dst_w, int dst_h, const float* st, int rot, float sx,
               float sy, const FilmParams& p);
  void passStill(int dst_w, int dst_h, int rot, const FilmParams& p);
  void passRaw(int dst_w, int dst_h, int rot, int cfa,
               const float black_phase[4], float white_level,
               const float gains[3], const float* ccm, const FilmParams& p);
  // RCD 质量档（M3-1）：方向场 → G@RB（比值修正）→ 色度填充（伪彩限幅）
  void passRcdDir(int w, int h, int cfa, const float black_phase[4],
                  float white_level, const float gains[3]);
  void passRcdG(int w, int h, int cfa, const float black_phase[4],
                float white_level, const float gains[3]);
  void passRcdChroma(int dst_w, int dst_h, int rot, int cfa,
                     const float black_phase[4], float white_level,
                     const float gains[3], const float* ccm);
  void passFilm(int w, int h, const FilmParams& p);
  void passOutput(int w, int h, const FilmParams& p, bool flip_y);
  void passFill(int dst_w, int dst_h);

  static void makeTex(GLuint* tex, GLuint* fbo, GLenum internal, int w, int h);
  void logPassDiag(const char* stage, GLuint fbo, bool half_float);

  GLuint prog_oes_ = 0, prog_still_ = 0, prog_film_ = 0, prog_out_ = 0,
         prog_fill_ = 0, prog_raw_ = 0, prog_rcd_dir_ = 0, prog_rcd_g_ = 0,
         prog_rcd_chroma_ = 0;
  GLuint vs_rot_ = 0, vs_plain_ = 0;

  GLuint oes_tex_ = 0;
  int yuv_matrix_ = 0;
  GLuint still_tex_ = 0;  // RGB8，静拍 CPU 转换后上传
  int still_w_ = 0, still_h_ = 0;
  GLuint raw_tex_ = 0;  // R16UI，Bayer 原始（texelFetch 整型采样）
  int raw_w_ = 0, raw_h_ = 0;
  GLuint fbo_dir_ = 0, tex_dir_ = 0;  // RGBA16F，RCD 方向场（vh + pq 统计）
  GLuint fbo_g_ = 0, tex_g_ = 0;      // RGBA16F，RCD 全图 G（g 通道有效）
  int rcd_w_ = 0, rcd_h_ = 0;
  std::vector<uint8_t> rgb_stage_;
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
          black = -1, white = -1, gain = -1, ccm = -1;
  } u_raw_;
  struct {
    GLint tex = -1, size = -1, cfa = -1, black = -1, white = -1, gain = -1;
  } u_rcd_dir_;
  struct {
    GLint tex = -1, dir = -1, size = -1, cfa = -1, black = -1, white = -1,
          gain = -1;
  } u_rcd_g_;
  struct {
    GLint st = -1, rot = -1, scale = -1, tex = -1, dir = -1, g = -1, size = -1,
          cfa = -1, black = -1, white = -1, gain = -1, ccm = -1;
  } u_rcd_chroma_;
  struct {
    GLint tex = -1, contrast = -1, shoulder = -1, toe = -1, cross = -1,
          sat = -1, split = -1, mono = -1;
  } u_film_;
  struct {
    GLint tex = -1, px = -1, grain_amount = -1, grain_size = -1, time = -1,
          flip_y = -1;
  } u_out_;
  GLint u_fill_st_ = -1;
  struct {
    GLint rot = -1, scale = -1;
  } u_fill_;
};

}  // namespace filmkit
