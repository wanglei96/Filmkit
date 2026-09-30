#include "FrameRenderer.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <string>

#include "../log/GpuTimer.h"
#include "../spike/SpikeFftMerge.h"
#include "../log/Logger.h"
#include "Align.h"

#ifndef GL_TEXTURE_EXTERNAL_OES
#define GL_TEXTURE_EXTERNAL_OES 0x8D65
#endif

namespace filmkit {
namespace {

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

// 预览域映射的在线标定状态（v0.8.2 提为**进程级**）：nativeEngineStart 每次
// new FrameRenderer，放在成员里会随 engine stop/start 归零 —— 真机日志实测
// ratio 长期钉在初值 2.0 不收敛。进程存活期间跨实例保持；App 冷启动回落
// 初值、拍一张静拍即重新收敛（EMA α=0.3）。
float g_pv_mid_ema = 0.0f;   // 预览统计中位的 EMA（0 = 未初始化）
float g_domain_ratio = 2.0f; // HAL 域 / RAW 域 中位比（初值 = 实测范围 1.70–3.87 的中位）

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
void main() {
    vec3 rgb = clamp(texture(uTex, vUV0).rgb, 0.0, 1.0);
    vec3 lin = pow(rgb, vec3(2.2)) * uWb;
    oColor = vec4(lin, 1.0);
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
void main() {
    vec2 uv = vec2(vUV0.x, 1.0 - vUV0.y);
    vec3 rgb = clamp(texture(uTex, uv).rgb, 0.0, 1.0);
    vec3 lin = pow(rgb, vec3(2.2)) * uWb;
    oColor = vec4(lin, 1.0);
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
        // 伪彩抑制：限幅到四邻实测 G 范围
        float lo = min(min(cN, cS), min(cE, cW));
        float hi = max(max(cN, cS), max(cE, cW));
        g = clamp(g, lo, hi);
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
uniform mat3 uCcm;     // sensor→sRGB 行主序
const float EPS = 1e-6;
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
// 同为目标色实测位）。P/Q 两方向色差按对角梯度反比交叉加权，pq 方向混合；
// 色差限幅到 4 对角色差范围（伪彩抑制）。
float chromaDiag(ivec2 p) {
    float g0 = gAt(p);
    ivec2 nw = p + ivec2(-1, -1), se = p + ivec2(1, 1);
    ivec2 ne = p + ivec2(1, -1), sw = p + ivec2(-1, 1);
    float cnw = s(nw), cse = s(se), cne = s(ne), csw = s(sw);
    float gnw = abs(cnw - cse) + abs(g0 - gAt(p + ivec2(-3, -3))) + EPS;
    float gse = abs(cse - cnw) + abs(g0 - gAt(p + ivec2(3, 3))) + EPS;
    float pEst = (gnw * (cse - gAt(se)) + gse * (cnw - gAt(nw))) / (gnw + gse);
    float gne = abs(cne - csw) + abs(g0 - gAt(p + ivec2(3, -3))) + EPS;
    float gsw = abs(csw - cne) + abs(g0 - gAt(p + ivec2(-3, 3))) + EPS;
    float qEst = (gne * (csw - gAt(sw)) + gsw * (cne - gAt(ne))) / (gne + gsw);
    float d = mix(pEst, qEst, dir5(p, 1));
    float lo = min(min(cnw - gAt(nw), cse - gAt(se)), min(cne - gAt(ne), csw - gAt(sw)));
    float hi = max(max(cnw - gAt(nw), cse - gAt(se)), max(cne - gAt(ne), csw - gAt(sw)));
    return clamp(d, lo, hi);
}
// 轴向色差填充（G 位置专用）：Bayer 中 G 位的 R/B 都在四正邻（±1），
// 哪个轴是 R 由 G 的两种相位决定（水平邻 R 或垂直邻 R），按 chanAt 实测
// 分派；同轴 ±3 为第二对。近对反梯度加权为主、远对弱混合，限幅防过冲。
float axialFill(ivec2 p, int c) {
    ivec2 d = chanAt(p + ivec2(1, 0)) == c ? ivec2(1, 0) : ivec2(0, 1);
    float c1 = s(p + d), c2 = s(p - d);
    float c3 = s(p + 3 * d), c4 = s(p - 3 * d);
    float d1 = c1 - gAt(p + d), d2 = c2 - gAt(p - d);
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
    vec3 rgb = uCcm * (vec3(r, g, b) * lscAt(p));
    oColor = vec4(clamp(rgb, vec3(0.0), vec3(64.0)), 1.0);
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
// 白阶归一/WB 后。A = shot·g·k²、B = read·g²·k²，k = 强度档系数）。
// 来源 Camera2 SENSOR_NOISE_PROFILE（模型）或 legacy σ 等效（见 denoiseParams）。
// A=B=0 精确表示"关降噪档"（只做线性化+WB）。
uniform vec3 uNoiseA;
uniform vec3 uNoiseB;
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
        v = acc / wsum;
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
// WB 后 raw 域，强度档 k 已折入）。y = 本 pass 输入 luma（CCM 后，已含 LSC 放大）。
uniform vec3 uNoiseA;
uniform vec3 uNoiseB;
// LSC 增益网格（C 专项：增益传播）。shot 项随被放大的信号 y0 自然增长；
// read 项不随信号，需显式 ×g²（var' = A·g·x + B·g²，见 NoiseModel::afterWb）。
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
void main() {
    ivec2 p = clamp(ivec2(gl_FragCoord.xy), ivec2(0), uSize - 1);
    vec3 c0 = texelFetch(uTex, p, 0).rgb;
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
    float sc = sqrt(max(ay * y0 + by * glY * glY, 1e-12));
    vec3 acc = cd0;
    float wsum = 1.0;
    for (int dy = -3; dy <= 3; ++dy)
        for (int dx = -3; dx <= 3; ++dx) {
            if (dx == 0 && dy == 0) continue;
            vec3 c = texelFetch(uTex, clamp(p + ivec2(dx, dy), ivec2(0), uSize - 1), 0).rgb;
            float y = dot(c, LUMA);
            vec3 cd = c - y;
            float wy = (y - y0) / (sc * 1.2);
            float wc = length(cd - cd0) / sc;
            float w = exp(-0.5 * (wy * wy + wc * wc)
                          - 0.5 * float(dx * dx + dy * dy) / 9.68);  // σs=2.2（v0.6.32）
            acc += w * cd;
            wsum += w;
        }
    oColor = vec4(y0 + acc / wsum, 1.0);
})";

// ── D 专项（v0.10.53）：色度多尺度降噪（半分辨率 à trous 小波）────────────
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
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy) * 2;
    vec2 acc = vec2(0.0);
    float sacc = 0.0;
    float ay = dot(uNoiseA, LUMA);
    float by = dot(uNoiseB, LUMA);
    for (int i = 0; i < 4; ++i) {
        ivec2 q = clamp(p + ivec2(i & 1, i >> 1), ivec2(0), uSize - 1);
        vec3 c = texelFetch(uTex, q, 0).rgb;
        float y = dot(c, LUMA);
        acc += vec2(c.b - y, c.r - y);
        float glY = dot(lscAt(q), LUMA);
        sacc += sqrt(max(ay * y + by * glY * glY, 1e-12));
    }
    oChroma = vec4(acc * 0.25, 0.0, 1.0);
    // 2×2 box 平均使白噪 σ 减半：σ_half = mean(σ_full)/2
    oSigma = vec4(sacc * 0.125, 0.0, 0.0, 1.0);
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
const float B3[5] = float[5](0.0625, 0.25, 0.375, 0.25, 0.0625);
void main() {
    ivec2 p = clamp(ivec2(gl_FragCoord.xy), ivec2(0), uSize - 1);
    vec2 a0 = texelFetch(uPrev, p, 0).rg;
    vec2 s = vec2(0.0);
    for (int i = 0; i < 5; ++i)
        for (int j = 0; j < 5; ++j) {
            ivec2 q = clamp(p + ivec2((j - 2) * uStep, (i - 2) * uStep),
                            ivec2(0), uSize - 1);
            s += (B3[i] * B3[j]) * texelFetch(uPrev, q, 0).rg;
        }
    vec2 d = a0 - s;
    float sig = max(texelFetch(uSigma, p, 0).r * uK, 1e-6);
    vec2 dp = d * (d * d) / (d * d + vec2(sig * sig) + vec2(1e-12));
    dp += uRevert * (d - dp);          // detail reconstruction（PC revert factor）
    oNext = vec4(s, 0.0, 1.0);
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
uniform int uL;        // 切向采样半长（≤ kLMax）
uniform float uKr;     // 值域阈值系数（× 逐像素 σ）
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const int kLMax = 16;
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
float lumAt(ivec2 p) {
    return dot(texelFetch(uTex, clamp(p, ivec2(0), uSize - 1), 0).rgb, LUMA);
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
    // ── 亮度各向异性核（v0.10.56 起**默认关闭**，uL=0 时整段跳过）──────────
    // 事故记录：0.10.54（L=16 无条件）与 0.10.55（L=6 + coherence 门控）两版
    // 真机的 8px 尺度纹理能量都掉（0.10.55 同刻实测 3.17 vs PC 3.59 = −12%），
    // 用户两次判定"画面比 PC 糊"。根因是**单帧去马赛克后**的空间核在噪声与
    // 中尺度纹理同量级时必然二选一 —— 收益（σY 2.87→1.30）是拿纹理买的，
    // 细节优先是红线，故整条路径停用。真正的杠杆在 merge 阶段的多帧空间核
    //（PC：每帧 3×3 quad × 4 帧 = 加样本不加空间模糊），代码保留待那边落地后
    // 以"小核 + 多帧"的形态重开，而不是这里放大核。
    float dyn = 0.0;
    if (uL > 0) {
    float y00 = lumAt(p + ivec2(-1, -1)), y10 = lumAt(p + ivec2(0, -1)),
          y20 = lumAt(p + ivec2(1, -1));
    float y01 = lumAt(p + ivec2(-1, 0)),   y21 = lumAt(p + ivec2(1, 0));
    float y02 = lumAt(p + ivec2(-1, 1)),   y12 = lumAt(p + ivec2(0, 1)),
          y22 = lumAt(p + ivec2(1, 1));
    float gx = ((y20 + y21 + y22) - (y00 + y01 + y02)) / 6.0;   // 右列 − 左列
    float gy = ((y02 + y12 + y22) - (y00 + y10 + y20)) / 6.0;   // 下行 − 上行
    float Jxx = gx * gx + 1e-9, Jyy = gy * gy + 1e-9, Jxy = gx * gy;
    float th = 0.5 * atan(2.0 * Jxy, Jxx - Jyy);
    vec2 tv = vec2(-sin(th), cos(th));          // 切向（沿边缘）
    // ⚠️ v0.10.54 事故：长核无条件施加 → 平坦/无方向处也取 ±uL，等于 33 点平均
    // 抹平纹理（真机"叶片纹理被抹"）。核长必须随 coherence=(λ1−λ2)/(λ1+λ2)
    // 收缩 —— 无结构处退化为短核，这正是 Sabre 核回归"平坦区退回小高斯"的语义。
    float jt = sqrt(max((Jxx - Jyy) * (Jxx - Jyy) + 4.0 * Jxy * Jxy, 0.0));
    float l1 = (Jxx + Jyy + jt) * 0.5;
    float l2 = max((Jxx + Jyy - jt) * 0.5, 1e-9);
    int lmax = int(float(uL) * ((l1 - l2) / (l1 + l2)));
    float ay = dot(uNoiseA, LUMA), by = dot(uNoiseB, LUMA);
    float glY = dot(lscAt(p), LUMA);
    float sr = max(uKr * sqrt(max(ay * y + by * glY * glY, 1e-12)), 1e-5);
    float acc = y, ws = 1.0;
    for (int k = -kLMax; k <= kLMax; ++k) {
        if (k == 0 || abs(k) > lmax) continue;
        ivec2 q = clamp(p + ivec2(round(float(k) * tv)), ivec2(0), uSize - 1);
        float yq = dot(texelFetch(uTex, q, 0).rgb, LUMA);
        float d = (yq - y) / sr;
        float w = exp(-0.5 * d * d);
        acc += w * yq;
        ws += w;
    }
    dyn = acc / ws - y;                         // 加性回写（乘性会在暗部放大噪声）
    }
    vec3 o = vec3(y + ch.y + dyn, 0.0, y + ch.x + dyn);   // R = Y+Cr, B = Y+Cb
    o.g = (y + dyn - 0.2126 * o.r - 0.0722 * o.b) / 0.7152;
    oColor = vec4(o, 1.0);
})";

// ── MFNR 阶段一（v0.9.0）────────────────────────────────────────────
// 多帧融合降噪：4 帧 RAW 的 Bayer 域对齐 + 时域均值，插在 P0 降噪位（demosaic 前）。
// 依据：HDR+（Hasinoff 2016 §4-5）与 Night Sight（Liba 2019 §3）——彩噪跨帧独立、
// 信号跨帧相关 ⇒ 时域均值 √N 提 SNR，是空间降噪给不了的低频彩噪根治手段。
// 域约定：对齐在 2×2 Bayer 块平均的灰度金字塔（1/4、1/8、1/16 readback）上做，
// 位移场以 mosaic 域表示，融合采样 round 到**最近偶数**——1/2 域整数位移 ×2 恒
// 为偶数，Bayer 相位不翻转（奇数位移会把 R 位采到 B，等价于把色度打乱）。
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
    float acc = linAt(uRaw0, p);
    float wsum = 1.0;
    vec2 uv = (vec2(p) + 0.5) / (uTiles * uTileM);
    // 权重（v0.10.3）：独立权重图（32px cell，LINEAR 双线性过渡），与位移场解耦
    // —— 128px tile 级权重对叶缘带局部运动分辨率不足（重影残留）。
    vec4 wv = texture(uW, uv);
    vec3 f1 = off1At(uv);
    float w1 = wv.r;
    acc += w1 * linSub(uRaw1, p, f1.xy);
    wsum += w1;
    vec3 f2 = off2At(uv);
    float w2 = wv.g;
    acc += w2 * linSub(uRaw2, p, f2.xy);
    wsum += w2;
    vec3 f3 = off3At(uv);
    float w3 = wv.b;
    acc += w3 * linSub(uRaw3, p, f3.xy);
    wsum += w3;
    float v = acc / max(wsum, 1e-4);
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
uniform float uS;      // mismatch 的 σ 缩放常数（标定旋钮）
uniform int uCfaLut;
uniform vec4 uBlack;
uniform float uWhite;
uniform vec3 uGain;
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
            float r = linAt(uRaw0, p);
            float a1 = linSub(uRaw1, p, o1);
            float a2 = linSub(uRaw2, p, o2);
            float a3 = linSub(uRaw3, p, o3);
            float d1 = r - a1;
            float d2 = r - a2;
            float d3 = r - a3;
            s1 += d1 * d1;
            s2 += d2 * d2;
            s3 += d3 * d3;
            // var(r)+var(a) = A·r+B + A·a+B = A·(r+a) + 2B（同域同模型）
            va1 += av * (r + a1) + 2.0 * bv;
            va2 += av * (r + a2) + 2.0 * bv;
            va3 += av * (r + a3) + 2.0 * bv;
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
int chanAt(ivec2 p) {
    int k = ((p.y & 1) << 1) | (p.x & 1);
    return (uCfaLut >> (k << 1)) & 3;
}
float fused0(ivec2 p) {
    p = clamp(p, ivec2(0), uSize - 1);
    return texelFetch(uTex, p, 0).r;
}
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float c0 = fused0(p);
    float v = c0;
    int ch = chanAt(p);
    float av = ch == 0 ? uNoiseA.x : (ch == 1 ? uNoiseA.y : uNoiseA.z);
    float bv = ch == 0 ? uNoiseB.x : (ch == 1 ? uNoiseB.y : uNoiseB.z);
    float sr2 = av * c0 + bv;
    if (sr2 > 1e-12) {
        float sr = sqrt(sr2);
        float wsum = 1.0, acc = c0;
        for (int dy = -4; dy <= 4; dy += 2)
            for (int dx = -4; dx <= 4; dx += 2) {
                if (dx == 0 && dy == 0) continue;
                float c = fused0(p + ivec2(dx, dy));
                float d = (c - c0) / sr;
                float w = exp(-0.5 * d * d
                              - 0.5 * float(dx * dx + dy * dy) / 6.48);
                acc += w * c;
                wsum += w;
            }
        v = acc / wsum;
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
uniform ivec2 uSize;
uniform ivec2 uOffTiles;
uniform int uCfaLut;
uniform vec4 uBlack;
uniform float uWhite;
uniform vec3 uGain;
uniform vec3 uNoiseA;
uniform vec3 uNoiseB;
uniform float uSrScale;
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
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float ref = texelFetch(uFused, p, 0).r;
    int ch = chanAt(p);
    float av = ch == 0 ? uNoiseA.x : (ch == 1 ? uNoiseA.y : uNoiseA.z);
    float bv = ch == 0 ? uNoiseB.x : (ch == 1 ? uNoiseB.y : uNoiseB.z);
    float sr = sqrt(max(av * ref + bv, 1e-12)) * uSrScale;
    float wsum = 1.0, acc = ref;
    vec2 ctr = vec2(p) + 0.5;
    for (int z = 0; z < 4; ++z) {
        ivec2 off = ivec2(0);
        if (z > 0) {
            vec2 dz;
            if (z == 1) dz = offAt(uOff1, ctr);
            else if (z == 2) dz = offAt(uOff2, ctr);
            else dz = offAt(uOff3, ctr);
            off = ivec2(floor(dz * 0.5 + 0.5)) * 2;   // round 偶数保相位
        }
        for (int dy = -4; dy <= 4; dy += 2)
            for (int dx = -4; dx <= 4; dx += 2) {
                if (dx == 0 && dy == 0) continue;
                float c = linZ(z, p + off + ivec2(dx, dy));
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
    float v = acc / wsum;
    // v0.10.61 诊断：alpha = 权重和 wsum（含中心 1.0）。样本数已足够（每帧 24 个
    // 同相位邻域 × 4 帧 = 96），实测收益却远低于 √96 —— 需知**实际采纳了多少**，
    // 故把 wsum 带出（tex_mos_ 的 alpha 无下游消费者），由 host 读回记入日志。
    oColor = vec4(ch == 0 ? v : 0.0, ch == 1 ? v : 0.0, ch == 2 ? v : 0.0, wsum);
})";

// kFsTileRms（C 专项，2026-09-29）：逐 tile RMS 归约 —— HDR+ §5 的做法："For
// each tile, we compute the variance by evaluating our noise model using a single
// value, the root-mean-square (RMS) of the samples in the tile."
// 输入 = P0 输出的线性 mosaic（RGB 中恰一路非零）；每像素输出一个 16×16 tile
// 的 RMS（R16F 单通道）。这是 A（DFT 逐频收缩合并）的 σ 输入底座：合并时
// σ_tile = √(A·RMS + B) 即模型代入。分块在 [0,1] 线性域，与模型同域。
// 每个输出像素采 tile 内全部 16×16 = 256 点（覆盖 2×2 全相位，避免单相位偏差）。
const char* kFsTileRms = R"(#version 300 es
precision highp float;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform ivec2 uSize;   // mosaic 全尺寸
void main() {
    ivec2 t = ivec2(gl_FragCoord.xy);
    ivec2 base = t * 16;
    float acc = 0.0;
    float dacc = 0.0;
    int dn = 0;
    for (int dy = 0; dy < 16; ++dy)
        for (int dx = 0; dx < 16; ++dx) {
            ivec2 p = clamp(base + ivec2(dx, dy), ivec2(0), uSize - 1);
            vec3 c = texelFetch(uTex, p, 0).rgb;
            float v = c.r + c.g + c.b;
            acc += v * v;
            // v0.10.63 诊断：同相位（步长 2）水平 lag-2 差分 —— 平坦区里信号抵消，
            // 残差 std/√2 = 纯噪声 σ。tile RMS 是**信号主导**的（实测 tile0 = 0.43
            // 而 σ 仅 0.0137），噪声降 8 倍在 RMS 上只动 0.0002，根本测不出来。
            if (dx + 2 < 16) {
                vec3 c2 = texelFetch(uTex, clamp(base + ivec2(dx + 2, dy),
                                                 ivec2(0), uSize - 1), 0).rgb;
                float d = (c2.r + c2.g + c2.b) - v;
                dacc += d * d;
                dn += 1;
            }
        }
    float rms = sqrt(acc * (1.0 / 256.0));
    float nz = dn > 0 ? sqrt(dacc / float(dn) * 0.5) : 0.0;
    oColor = vec4(rms, nz, 0.0, 1.0);
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
uniform vec4 uPlaneA;     // 平面 0..3 的 A 系数（按 cfaLut 展开，含强度 k²）
uniform vec4 uPlaneB;
uniform float uC;         // 收缩常数（256）
uniform ivec2 uGroup;     // 本轮 dispatch 的 tile 相位组 (gx, gy) ∈ {0,1}²
uniform highp vec2 uTw[8];

shared float sre[2][4][16][16];
shared float sim[2][4][16][16];
shared float sRmsRow[2][16];  // 参考帧行平方和（未加窗 RMS 归约用）
shared float sRms[2];

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
    return max((float(v) - bl) / max(uWhite - bl, 1.0), 0.0) * gainAt(chanAt(p));
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
                float wx = 0.5 - 0.5 * cos(TWO_PI * (float(lx) + 0.5) / 16.0);
                float wyy = 0.5 - 0.5 * cos(TWO_PI * (float(yy) + 0.5) / 16.0);
                sre[tt][zz][yy][lx] = v * wx * wyy;
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
            float cs2 = uC * sig2 + 1e-12;
            for (int zz = 1; zz < 4; ++zz) {
                float rz = sre[tt][zz][by][bx];
                float iz = sim[tt][zz][by][bx];
                float dr = r0 - rz;
                float di = i0 - iz;
                float d2 = dr * dr + di * di;
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

// kFsMicroStore（v0.10.45）：1-texel imageStore 微测试 —— 与 DFT 内核完全
// 解耦,判定拍照上下文里 image 写入是否工作(写 16×16 图的 (0,0) = 常量 1)。
// v0.10.50 起同一 program 跑两次（glTexImage2D / glTexStorage2D 各一次）做
// 纹理创建方式的 A/B 对照。
const char* kFsMicroStore = R"(#version 310 es
precision highp float;
layout(local_size_x = 16, local_size_y = 16) in;
layout(rgba16f, binding = 0) writeonly uniform highp image2D uMicro;
void main() {
    if (gl_LocalInvocationID.x == 0u && gl_LocalInvocationID.y == 0u) {
        imageStore(uMicro, ivec2(0, 0), vec4(1.0, 1.0, 1.0, 1.0));
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
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
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
uniform float uPreviewMap;
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
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
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
uniform vec3 uFilmicM1, uFilmicM2, uFilmicM3, uFilmicM4, uFilmicM5;
uniform float uFilmicLatMin, uFilmicLatMax;
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
float filmicSpline(float e) {
    if (e < uFilmicLatMin)
        return uFilmicM1.x + e * (uFilmicM2.x + e * (uFilmicM3.x + e * (uFilmicM4.x + e * uFilmicM5.x)));
    if (e > uFilmicLatMax)
        return uFilmicM1.y + e * (uFilmicM2.y + e * (uFilmicM3.y + e * (uFilmicM4.y + e * uFilmicM5.y)));
    return uFilmicM1.z + e * uFilmicM2.z;
}
float filmicScalar(float v, float lo) {
    float s = max(v, kFilmicNormMin);
    float e = clamp((log2(s / kFilmicGrey) - uFilmicBlackEv) / max(uFilmicDr, 0.2), 0.0, 1.0);
    float c = clamp(filmicSpline(e), lo, 1.0);
    return pow(max(c, 0.0), uFilmicPower);
}
// darktable 的颜色模式：0.5 × 逐通道 + 0.5 × max-RGB 归一。
// ⚠️ 本函数**只压亮度、保色相**：过曝的灯管（R/B 高、G 低）会留品红 —— 这是
// v0.5.37「过曝灯泡变红」与 v0.6.50 反复出现的老问题。所以后面的高光去饱和（w）
// **不是可选项**：离线实测同一场景，缺它时高光 R/G 1.277，补上后 1.000。
vec3 filmicTone(vec3 c) {
    vec3 p = max(c, vec3(0.0));
    vec3 naive = vec3(filmicScalar(p.r, 0.0), filmicScalar(p.g, 0.0),
                      filmicScalar(p.b, 0.0));
    float mx = max(max(p.r, p.g), p.b);
    float ratioNorm = max(mx, uFilmicInputMin);
    float toneNorm = clamp(mx, uFilmicInputMin, uFilmicInputMax);
    vec3 normed = (p / ratioNorm) * filmicScalar(toneNorm, uFilmicBlackDisp);
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
// 常量：全部来自 darktable 源码，不可调。
const float kYchY1931To2006 = 1.05785528;
const float kYchD65R = 0.21902143;
const float kYchD65G = 0.54371398;
const vec3 kLmsSrgbToLmsL = vec3(0.2986531876, 0.7060763220, 0.0656966248);
const vec3 kLmsSrgbToLmsM = vec3(0.0959000021, 0.7198304285, 0.1011531117);
const vec3 kLmsSrgbToLmsS = vec3(0.0224644230, 0.0449176289, 0.5270630110);
const vec3 kLmsToSrgbRowR = vec3(4.8627131007, -4.7893329888, 0.3130405567);
const vec3 kLmsToSrgbRowG = vec3(-0.6262137162, 2.0228185813, -0.3101607577);
const vec3 kLmsToSrgbRowB = vec3(-0.1538905312, 0.0317407724, 1.9103966448);
const vec3 kLmsToFilmRowR = vec3(1.08771930, -0.66666667, 0.02061856);
const vec3 kLmsToFilmRowG = vec3(-0.08771930, 1.66666667, -0.05154639);
const vec3 kLmsToFilmRowB = vec3(0.0, 0.0, 1.03092784);
const vec3 kFilmToLmsRowL = vec3(0.95, 0.38, 0.0);
const vec3 kFilmToLmsRowM = vec3(0.05, 0.62, 0.03);
const vec3 kFilmToLmsRowS = vec3(0.0, 0.0, 0.97);
vec3 gamutSrgbToLms(vec3 c) {
    return vec3(dot(kLmsSrgbToLmsL, c), dot(kLmsSrgbToLmsM, c), dot(kLmsSrgbToLmsS, c));
}
vec3 gamutLmsToSrgb(vec3 l) {
    return vec3(dot(kLmsToSrgbRowR, l), dot(kLmsToSrgbRowG, l), dot(kLmsToSrgbRowB, l));
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
vec4 gamutRgbToYch(vec3 c) { return gamutYrgToYch(gamutLmsToYrg(gamutSrgbToLms(c))); }
vec3 gamutYchToRgb(vec4 ych) { return gamutLmsToSrgb(gamutYrgToLms(gamutYchToYrg(ych))); }
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
    float newC = gamutClipChroma(kLmsToSrgbRowR, kLmsToSrgbRowG, kLmsToSrgbRowB,
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
    // 预览域映射（v0.8.0，取代直通）：先除以在线估计的 HAL/RAW 域比，映射回 RAW
    // 等效域，再走与成片完全相同的 filmic 链 —— 真 WYSIWYG。
    // filmicTone 输出 pow(clamp(spline), power) ∈ [0,1]，数学有界 —— 预览不再可能爆白。
    if (uPreviewMap > 0.5) {
        vec3 evMapped = mapped / uDomainRatio;
        vec3 toned = filmicTone(evMapped);
        // 预览与成片同链（WYSIWYG）：gamut mapping 同样生效
        oColor = vec4(uGamut > 0.5 ? gamutMapV5(evMapped, toned) : toned, 1.0);
        return;
    }
    // v0.7.9：旧 log 域胶片链（log2 编码 / filmCurve / 域外延展 / 0.18·exp2 重构）已删，
    // filmic 直接吃线性场景输入 —— blackEv/whiteEv 的标定域与渲染域首次严格一致。
    vec3 out3 = mapped;
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
    out3 += uSplit.x * ws * vec3(0.05, 0.02, -0.04)
          + uSplit.y * wh * vec3(-0.03, 0.0, 0.05);
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
    out3 = mix(out3, vec3(luma(clamp(out3, 0.0, 4.0))), uMono);
    oColor = vec4(out3, 1.0);
})";

// Pass3：输出变换（OETF + 颗粒 + dither），量化到 8bit
const char* kFsOut = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform vec2 uPx;
uniform float uGrainAmount;
uniform float uGrainSize;
uniform float uTime;
uniform float uFlipY;  // 拍照读回 GL 底行在前，翻转后 JPEG 方向正确
uniform float uZebra;  // 过曝提示阈值（显示域，0 = 关；仅预览路径传非 0）
uniform float uSharpen;  // v0.9.6 轻度锐化强度（0 = 关，A/B 通路；FilmParams[39]）
// ── M4 3D LUT（display 域）────────────────────────────────────────────
// 域感知插桩：display 域 LUT 必须接在输出 OETF 之后（§0-D10 / §6.1）。
// 采样用四面体插值而非三线性 —— 三线性在 33³ 下有可见色差（§3.3-5）。
uniform highp sampler3D uLut;
uniform float uLutOn;        // 0 = 旁路（未选 LUT / 强度 0）
uniform float uLutStrength;  // 0..1 混合强度
uniform vec3 uLutDomainMin;  // .cube DOMAIN_MIN
uniform vec3 uLutDomainInv;  // 1/(DOMAIN_MAX - DOMAIN_MIN)
uniform float uLutSize;      // 3D 边长 N
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
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
// v0.10.67 dither 专用 hash：现有的 sin 版在 gl_FragCoord 大坐标（3072×4080）下
// highp sin 精度不足，会产生**规律性图案** —— dither 一旦有规律就等于没有。
// Dave Hoskins 的无 sin 整数风格 hash。单独一个函数，避免改动 grain 的既有观感。
float dhash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
float vnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i), b = hash(i + vec2(1, 0));
    float c = hash(i + vec2(0, 1)), d = hash(i + vec2(1, 1));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
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
    float l = max(max(c.r, c.g), c.b);
    if (l <= knee) return c;
    float u = (l - knee) / (1.0 - knee);              // [0, ∞)
    float lc = knee + (1.0 - knee) * (1.0 - exp(-u)); // → [knee, 1)
    vec3 cc = c * (lc / l);                           // 等比压缩，保持色相
    return mix(cc, vec3(lc), smoothstep(knee, 1.0, l));  // 过曝趋白（终点 = 显示上限）
}
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
    float l0 = luma(c);
    float lb = 4.0 * l0;
    vec3 t = highlightRolloff(texture(uTex, uv + uPx * vec2(-1.0, 0.0)).rgb);
    lb += 2.0 * luma(t);
    t = highlightRolloff(texture(uTex, uv + uPx * vec2(1.0, 0.0)).rgb);
    lb += 2.0 * luma(t);
    t = highlightRolloff(texture(uTex, uv + uPx * vec2(0.0, -1.0)).rgb);
    lb += 2.0 * luma(t);
    t = highlightRolloff(texture(uTex, uv + uPx * vec2(0.0, 1.0)).rgb);
    lb += 2.0 * luma(t);
    t = highlightRolloff(texture(uTex, uv + uPx * vec2(-1.0, -1.0)).rgb);
    lb += luma(t);
    t = highlightRolloff(texture(uTex, uv + uPx * vec2(1.0, -1.0)).rgb);
    lb += luma(t);
    t = highlightRolloff(texture(uTex, uv + uPx * vec2(-1.0, 1.0)).rgb);
    lb += luma(t);
    t = highlightRolloff(texture(uTex, uv + uPx * vec2(1.0, 1.0)).rgb);
    lb += luma(t);
    float gain = uSharpen * (l0 - lb / 16.0) / max(l0, 0.01);
    c *= 1.0 + clamp(gain, -0.4, 0.4);
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
void main() {
    vec2 uv = vUV0;
    if (uFlipY > 0.5) uv.y = 1.0 - uv.y;
    vec3 c = texture(uTex, uv).rgb;
    c = highlightRolloff(c);
    if (uSharpen > 0.0) sharpenLuma(c, uv);
    c = srgbEncode(c);
    // M4：display 域 3D LUT（在 OETF 之后 —— 显示域 LUT 放到线性域会错色）
    if (uLutOn > 0.5) c = mix(c, lutApply(c), uLutStrength);
    if (uGrainAmount > 0.0) {
        vec2 gp = gl_FragCoord.xy * 6.0 / max(uGrainSize, 0.25) + uTime;
        float l = dot(c, vec3(0.299, 0.587, 0.114));
        float w = 4.0 * l * (1.0 - l);
        c += (vnoise(gp) - 0.5) * uGrainAmount * w;
    }
    // v0.10.67：RPDF ±0.5LSB → **TPDF ±1LSB**（两个独立均匀分布之和 = 三角分布）。
    // 依据：真机成片最平坦块实测 44.7% 相邻像素完全相同、行间 |Δ| 恰好 1.000 ——
    // 规则量化斜坡被人眼 Mach band 放大成条带（用户报的"边缘断层"）。TPDF 把
    // 零变占比降到 35.6%，即把规则台阶打散为随机台阶。均匀分布(RPDF)只能移动
    // 台阶、不能打散，故必须用三角分布。
    c += (dhash(gl_FragCoord.xy) + dhash(gl_FragCoord.xy + vec2(37.0, 17.0)) - 1.0) / 255.0;
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
void main() {
    vec3 raw = texture(uTex, vUV0).rgb;
    vec3 c = srgbEncode(raw);
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

}  // namespace

FrameRenderer::~FrameRenderer() { release(); }

bool FrameRenderer::init() {
  vs_rot_ = compileShader(GL_VERTEX_SHADER, kVertRot);
  vs_plain_ = compileShader(GL_VERTEX_SHADER, kVertPlain);
  const GLuint fs_oes = compileShader(GL_FRAGMENT_SHADER, kFsOes);
  const GLuint fs_still = compileShader(GL_FRAGMENT_SHADER, kFsStill);
  const GLuint fs_film = compileShader(GL_FRAGMENT_SHADER, kFsFilm);
  const GLuint fs_out = compileShader(GL_FRAGMENT_SHADER, kFsOut);
  const GLuint fs_hist = compileShader(GL_FRAGMENT_SHADER, kFsHist);
  const GLuint fs_local = compileShader(GL_FRAGMENT_SHADER, kFsLocal);
  const GLuint fs_fill = compileShader(GL_FRAGMENT_SHADER, kFsFillTest);
  const GLuint fs_raw = compileShader(GL_FRAGMENT_SHADER, kFsRaw);
  const GLuint fs_rcd_dir = compileShader(GL_FRAGMENT_SHADER, kFsRcdDir);
  const GLuint fs_rcd_g = compileShader(GL_FRAGMENT_SHADER, kFsRcdG);
  const GLuint fs_rcd_chroma = compileShader(GL_FRAGMENT_SHADER, kFsRcdChroma);
  const GLuint fs_raw_dn = compileShader(GL_FRAGMENT_SHADER, kFsRawDenoise);
  const GLuint fs_chroma_dn = compileShader(GL_FRAGMENT_SHADER, kFsChromaDenoise);
  const GLuint fs_chroma_down = compileShader(GL_FRAGMENT_SHADER, kFsChromaDown);
  const GLuint fs_chroma_at = compileShader(GL_FRAGMENT_SHADER, kFsChromaAtrous);
  const GLuint fs_chroma_apply = compileShader(GL_FRAGMENT_SHADER, kFsChromaApply);
  const GLuint fs_mfnr_pyr = compileShader(GL_FRAGMENT_SHADER, kFsMfnrPyr);
  const GLuint fs_mfnr_down = compileShader(GL_FRAGMENT_SHADER, kFsMfnrDown);
  const GLuint fs_mfnr_fuse = compileShader(GL_FRAGMENT_SHADER, kFsMfnrFuse);
  const GLuint fs_mfnr_mis =
      compileShader(GL_FRAGMENT_SHADER, kFsMfnrMismatch);
  const GLuint fs_raw_dn_f = compileShader(GL_FRAGMENT_SHADER, kFsRawDenoiseFused);
  const GLuint fs_raw_dn_ts = compileShader(GL_FRAGMENT_SHADER, kFsRawDenoiseFusedTs);
  const GLuint fs_tile_rms = compileShader(GL_FRAGMENT_SHADER, kFsTileRms);
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
  if (!vs_rot_ || !vs_plain_ || !fs_oes || !fs_still || !fs_film || !fs_out ||
      !fs_fill || !fs_raw || !fs_rcd_dir || !fs_rcd_g || !fs_rcd_chroma ||
      !fs_raw_dn || !fs_chroma_dn || !fs_chroma_down || !fs_chroma_at ||
      !fs_chroma_apply || !fs_hist || !fs_local ||
      !fs_mfnr_pyr || !fs_mfnr_down || !fs_mfnr_fuse || !fs_mfnr_mis ||
      !fs_raw_dn_f || !fs_raw_dn_ts || !fs_tile_rms || !fs_hr_mask ||
      !fs_hr_inp || !fs_hr_blur ||
      !fs_hr_hf || !fs_hr_rin || !fs_hr_step || !fs_hr_norms || !fs_hr_ratios ||
      !fs_hr_restore || !fs_hr_stat)
    return false;

  prog_oes_ = linkProgram(vs_rot_, fs_oes);
  prog_still_ = linkProgram(vs_rot_, fs_still);
  prog_film_ = linkProgram(vs_plain_, fs_film);
  prog_out_ = linkProgram(vs_plain_, fs_out);
  prog_hist_ = linkProgram(vs_plain_, fs_hist);
  prog_local_ = linkProgram(vs_plain_, fs_local);
  prog_fill_ = linkProgram(vs_rot_, fs_fill);
  prog_raw_ = linkProgram(vs_rot_, fs_raw);
  prog_rcd_dir_ = linkProgram(vs_plain_, fs_rcd_dir);
  prog_rcd_g_ = linkProgram(vs_plain_, fs_rcd_g);
  prog_rcd_chroma_ = linkProgram(vs_rot_, fs_rcd_chroma);
  prog_raw_dn_ = linkProgram(vs_plain_, fs_raw_dn);
  prog_chroma_dn_ = linkProgram(vs_rot_, fs_chroma_dn);
  prog_chroma_down_ = linkProgram(vs_rot_, fs_chroma_down);
  prog_chroma_at_ = linkProgram(vs_rot_, fs_chroma_at);
  prog_chroma_apply_ = linkProgram(vs_rot_, fs_chroma_apply);
  prog_mfnr_pyr_ = linkProgram(vs_plain_, fs_mfnr_pyr);
  prog_mfnr_down_ = linkProgram(vs_plain_, fs_mfnr_down);
  prog_mfnr_fuse_ = linkProgram(vs_plain_, fs_mfnr_fuse);
  prog_mfnr_mis_ = linkProgram(vs_plain_, fs_mfnr_mis);
  prog_raw_dn_fused_ = linkProgram(vs_plain_, fs_raw_dn_f);
  prog_raw_dn_ts_ = linkProgram(vs_plain_, fs_raw_dn_ts);
  prog_tile_rms_ = linkProgram(vs_plain_, fs_tile_rms);
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
  glDeleteShader(fs_hist);
  glDeleteShader(fs_local);
  glDeleteShader(fs_fill);
  glDeleteShader(fs_raw);
  glDeleteShader(fs_rcd_dir);
  glDeleteShader(fs_rcd_g);
  glDeleteShader(fs_rcd_chroma);
  glDeleteShader(fs_raw_dn);
  glDeleteShader(fs_chroma_dn);
  glDeleteShader(fs_chroma_down);
  glDeleteShader(fs_chroma_at);
  glDeleteShader(fs_chroma_apply);
  glDeleteShader(fs_mfnr_pyr);
  glDeleteShader(fs_mfnr_down);
  glDeleteShader(fs_mfnr_fuse);
  glDeleteShader(fs_mfnr_mis);
  glDeleteShader(fs_raw_dn_f);
  glDeleteShader(fs_raw_dn_ts);
  glDeleteShader(fs_tile_rms);
  glDeleteShader(fs_mfnr_fft_sum);
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
  if (!prog_oes_ || !prog_still_ || !prog_film_ || !prog_out_ || !prog_fill_ ||
      !prog_raw_ || !prog_rcd_dir_ || !prog_rcd_g_ || !prog_rcd_chroma_ ||
      !prog_raw_dn_ || !prog_chroma_dn_ || !prog_chroma_down_ ||
      !prog_chroma_at_ || !prog_chroma_apply_ || !prog_hist_ || !prog_local_ ||
      !prog_mfnr_pyr_ || !prog_mfnr_down_ || !prog_mfnr_fuse_ ||
      !prog_mfnr_mis_ || !prog_raw_dn_fused_ || !prog_tile_rms_ ||
      !prog_hr_mask_ ||
      !prog_hr_inpaint_ || !prog_hr_blur_ || !prog_hr_hf_ || !prog_hr_rin_ ||
      !prog_hr_step_ || !prog_hr_norms_ || !prog_hr_ratios_ ||
      !prog_hr_restore_ || !prog_hr_stat_)
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

  u_raw_dn_.tex = glGetUniformLocation(prog_raw_dn_, "uRaw");
  u_raw_dn_.size = glGetUniformLocation(prog_raw_dn_, "uSize");
  u_raw_dn_.cfalut = glGetUniformLocation(prog_raw_dn_, "uCfaLut");
  u_raw_dn_.black = glGetUniformLocation(prog_raw_dn_, "uBlack");
  u_raw_dn_.white = glGetUniformLocation(prog_raw_dn_, "uWhite");
  u_raw_dn_.gain = glGetUniformLocation(prog_raw_dn_, "uGain");
  u_raw_dn_.noise_a = glGetUniformLocation(prog_raw_dn_, "uNoiseA");
  u_raw_dn_.noise_b = glGetUniformLocation(prog_raw_dn_, "uNoiseB");

  u_raw_dn_f_.tex = glGetUniformLocation(prog_raw_dn_fused_, "uTex");
  u_raw_dn_f_.size = glGetUniformLocation(prog_raw_dn_fused_, "uSize");
  u_raw_dn_f_.cfalut = glGetUniformLocation(prog_raw_dn_fused_, "uCfaLut");
  u_raw_dn_f_.noise_a = glGetUniformLocation(prog_raw_dn_fused_, "uNoiseA");
  u_raw_dn_f_.noise_b = glGetUniformLocation(prog_raw_dn_fused_, "uNoiseB");
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
  u_raw_dn_ts_.sr_scale = glGetUniformLocation(prog_raw_dn_ts_, "uSrScale");

  u_tile_rms_.tex = glGetUniformLocation(prog_tile_rms_, "uTex");
  u_tile_rms_.size = glGetUniformLocation(prog_tile_rms_, "uSize");
  u_tile_rms_.noise_a = glGetUniformLocation(prog_tile_rms_, "uNoiseA");
  u_tile_rms_.noise_b = glGetUniformLocation(prog_tile_rms_, "uNoiseB");

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

  u_chroma_dn_.tex = glGetUniformLocation(prog_chroma_dn_, "uTex");
  u_chroma_dn_.size = glGetUniformLocation(prog_chroma_dn_, "uSize");
  u_chroma_dn_.noise_a = glGetUniformLocation(prog_chroma_dn_, "uNoiseA");
  u_chroma_dn_.noise_b = glGetUniformLocation(prog_chroma_dn_, "uNoiseB");
  u_chroma_dn_.lsc = glGetUniformLocation(prog_chroma_dn_, "uLsc");
  u_chroma_dn_.lsc_size = glGetUniformLocation(prog_chroma_dn_, "uLscSize");
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
  u_chroma_apply_.l = glGetUniformLocation(prog_chroma_apply_, "uL");
  u_chroma_apply_.kr = glGetUniformLocation(prog_chroma_apply_, "uKr");
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

  u_film_.tex = glGetUniformLocation(prog_film_, "uTex");
  u_film_.preview_map = glGetUniformLocation(prog_film_, "uPreviewMap");
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
  u_film_.filmic_m1 = glGetUniformLocation(prog_film_, "uFilmicM1");
  u_film_.filmic_m2 = glGetUniformLocation(prog_film_, "uFilmicM2");
  u_film_.filmic_m3 = glGetUniformLocation(prog_film_, "uFilmicM3");
  u_film_.filmic_m4 = glGetUniformLocation(prog_film_, "uFilmicM4");
  u_film_.filmic_m5 = glGetUniformLocation(prog_film_, "uFilmicM5");
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

  u_out_.tex = glGetUniformLocation(prog_out_, "uTex");
  u_out_.px = glGetUniformLocation(prog_out_, "uPx");
  u_out_.grain_amount = glGetUniformLocation(prog_out_, "uGrainAmount");
  u_out_.grain_size = glGetUniformLocation(prog_out_, "uGrainSize");
  u_out_.time = glGetUniformLocation(prog_out_, "uTime");
  u_out_.flip_y = glGetUniformLocation(prog_out_, "uFlipY");
  u_out_.zebra = glGetUniformLocation(prog_out_, "uZebra");
  u_out_.sharpen = glGetUniformLocation(prog_out_, "uSharpen");
  u_out_.lut = glGetUniformLocation(prog_out_, "uLut");
  u_out_.lut_on = glGetUniformLocation(prog_out_, "uLutOn");
  u_out_.lut_strength = glGetUniformLocation(prog_out_, "uLutStrength");
  u_out_.lut_dmin = glGetUniformLocation(prog_out_, "uLutDomainMin");
  u_out_.lut_dinv = glGetUniformLocation(prog_out_, "uLutDomainInv");
  u_out_.lut_size = glGetUniformLocation(prog_out_, "uLutSize");
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
  if (tex_dir_) glDeleteTextures(1, &tex_dir_);
  if (tex_g_) glDeleteTextures(1, &tex_g_);
  if (tex_mos_) glDeleteTextures(1, &tex_mos_);
  if (tex_dn_) glDeleteTextures(1, &tex_dn_);
  if (tex_hist_) glDeleteTextures(1, &tex_hist_);
  if (lut_tex_) glDeleteTextures(1, &lut_tex_);
  if (fbo_scene_) glDeleteFramebuffers(1, &fbo_scene_);
  if (fbo_work_) glDeleteFramebuffers(1, &fbo_work_);
  if (fbo_cap_) glDeleteFramebuffers(1, &fbo_cap_);
  if (fbo_dir_) glDeleteFramebuffers(1, &fbo_dir_);
  if (fbo_g_) glDeleteFramebuffers(1, &fbo_g_);
  if (fbo_mos_) glDeleteFramebuffers(1, &fbo_mos_);
  if (fbo_dn_) glDeleteFramebuffers(1, &fbo_dn_);
  if (fbo_hist_) glDeleteFramebuffers(1, &fbo_hist_);
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
  if (prog_fill_) glDeleteProgram(prog_fill_);
  if (prog_raw_) glDeleteProgram(prog_raw_);
  if (prog_rcd_dir_) glDeleteProgram(prog_rcd_dir_);
  if (prog_rcd_g_) glDeleteProgram(prog_rcd_g_);
  if (prog_rcd_chroma_) glDeleteProgram(prog_rcd_chroma_);
  if (prog_raw_dn_) glDeleteProgram(prog_raw_dn_);
  if (prog_chroma_dn_) glDeleteProgram(prog_chroma_dn_);
  if (prog_chroma_down_) glDeleteProgram(prog_chroma_down_);
  if (prog_chroma_at_) glDeleteProgram(prog_chroma_at_);
  if (prog_chroma_apply_) glDeleteProgram(prog_chroma_apply_);
  if (prog_mfnr_pyr_) glDeleteProgram(prog_mfnr_pyr_);
  if (prog_mfnr_down_) glDeleteProgram(prog_mfnr_down_);
  if (prog_mfnr_fuse_) glDeleteProgram(prog_mfnr_fuse_);
  if (prog_mfnr_mis_) glDeleteProgram(prog_mfnr_mis_);
  if (prog_raw_dn_fused_) glDeleteProgram(prog_raw_dn_fused_);
  if (prog_raw_dn_ts_) glDeleteProgram(prog_raw_dn_ts_);
  if (prog_tile_rms_) glDeleteProgram(prog_tile_rms_);
  if (prog_mfnr_fft_sum_) glDeleteProgram(prog_mfnr_fft_sum_);
  for (auto& t : tex_fft_out_) {
    if (t) glDeleteTextures(1, &t);
    t = 0;
  }
  prog_mfnr_fft_sum_ = 0;
  burst_w_ = burst_h_ = 0;
  if (tex_rms_) glDeleteTextures(1, &tex_rms_);
  if (fbo_rms_) glDeleteFramebuffers(1, &fbo_rms_);
  for (auto& t : tex_mfnr_) {
    if (t) glDeleteTextures(1, &t);
    t = 0;
  }
  for (auto& t : tex_off_) {
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
  if (tex_mis_) glDeleteTextures(1, &tex_mis_);
  if (fbo_mis_) glDeleteFramebuffers(1, &fbo_mis_);
  if (tex_w_) glDeleteTextures(1, &tex_w_);
  tex_w_ = 0;
  prog_mfnr_pyr_ = prog_mfnr_down_ = prog_mfnr_fuse_ = prog_raw_dn_fused_ = 0;
  prog_raw_dn_ts_ = 0;
  prog_tile_rms_ = 0;
  tex_rms_ = fbo_rms_ = 0;
  rms_w_ = rms_h_ = 0;
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
  if (raw_tex_) glDeleteTextures(1, &raw_tex_);
  if (lsc_tex_) glDeleteTextures(1, &lsc_tex_);
  still_tex_ = 0;
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
  hist_w_ = hist_h_ = 0;
  tex_scene_ = tex_work_ = tex_cap_ = 0;
  fbo_scene_ = fbo_work_ = fbo_cap_ = 0;
  prog_oes_ = prog_still_ = prog_film_ = prog_out_ = prog_fill_ = prog_raw_ = 0;
  prog_rcd_dir_ = prog_rcd_g_ = prog_rcd_chroma_ = 0;
  prog_raw_dn_ = prog_chroma_dn_ = 0;
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
  const GLenum type = internal == GL_RGBA8 ? GL_UNSIGNED_BYTE : GL_HALF_FLOAT;
  glTexImage2D(GL_TEXTURE_2D, 0, static_cast<GLint>(internal), w, h, 0, GL_RGBA,
               type, nullptr);
  glBindFramebuffer(GL_FRAMEBUFFER, *fbo);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                         *tex, 0);
}

// 逐 pass 诊断：GL 错误码 + 目标(0,0)像素，定位渲染断点层级
void FrameRenderer::logPassDiag(const char* stage, GLuint fbo, bool half_float) {
  const GLenum err = glGetError();
  glBindFramebuffer(GL_FRAMEBUFFER, fbo);
  std::string kv =
      std::string("{\"stage\":\"") + stage + "\",\"glErr\":" + std::to_string(err);
  if (half_float) {
    GLushort raw[4] = {0, 0, 0, 0};
    glReadPixels(0, 0, 1, 1, GL_RGBA, GL_HALF_FLOAT, raw);
    kv += ",\"r\":" + std::to_string(halfToFloat(raw[0]));
    kv += ",\"g\":" + std::to_string(halfToFloat(raw[1]));
    kv += ",\"b\":" + std::to_string(halfToFloat(raw[2])) + "}";
  } else {
    GLubyte b4[4] = {0, 0, 0, 0};
    glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, b4);
    kv += ",\"r\":" + std::to_string(b4[0]);
    kv += ",\"g\":" + std::to_string(b4[1]);
    kv += ",\"b\":" + std::to_string(b4[2]);
    kv += ",\"a\":" + std::to_string(b4[3]) + "}";
  }
  Logger::instance().log(LogLevel::kInfo, "engine", "render.diag", kv);
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
    makeTex(&tex_scene_, &fbo_scene_, GL_RGBA16F, w, h);
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

bool FrameRenderer::submitFrame(const float* st, int rot, float sx, float sy,
                                int dst_w, int dst_h, const FilmParams& p,
                                bool fill_test) {
  if (dst_w <= 0 || dst_h <= 0) return false;
  frames_.fetch_add(1, std::memory_order_relaxed);
  if (!ensureTargets(dst_w, dst_h)) return false;

  // 吞掉上一帧/诊断残留的 GL 错误，保证诊断只反映本帧问题
  while (glGetError() != GL_NO_ERROR) {
  }
  glDisable(GL_BLEND);
  glDisable(GL_DEPTH_TEST);
  const bool diag = frames_.load(std::memory_order_relaxed) == 1;

  if (fill_test) {
    passFill(dst_w, dst_h);
    if (diag) logPassDiag("pass1_fill", fbo_scene_, true);
  } else {
    passOES(dst_w, dst_h, st, rot, sx, sy, p);
    if (diag) logPassDiag("pass1_oes", fbo_scene_, true);
  }
  passFilm(dst_w, dst_h, p, tex_scene_, /*still=*/false);
  if (diag) logPassDiag("pass2_film", fbo_work_, true);

  // 必须显式绑回窗口默认帧缓冲：上一 pass 留下的绑定是 fbo_work_
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  passOutput(dst_w, dst_h, p, false, true);  // 预览：允许过曝斑马纹
  if (diag) logPassDiag("pass3_out", 0, false);
  return true;
}

// （拍照走 submitStill 全分辨率路径；captureLastFrame 已移除）

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
  glUniform3f(u_oes_.wb, p.wb_r, p.wb_g, p.wb_b);
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
  glUniform3f(u_still_.wb, p.wb_r, p.wb_g, p.wb_b);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// 全分辨率静拍：CPU YUV→RGB888（BT.601 limited）→ RGB8 上传 → 胶片链 → 读回。
// 单帧约 30–60ms CPU 转换（12.6MP），拍照场景可接受；NEON 优化记 M3。
bool FrameRenderer::submitStill(const void* y, const void* u, const void* v,
                                int w, int h, int y_stride, int uv_stride,
                                int uv_pix, int rotation, const FilmParams& p,
                                std::vector<uint8_t>* out_rgba) {
  if (w <= 0 || h <= 0 || !y || !u) return false;
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
  passFilm(dst_w, dst_h, p, tex_scene_, /*still=*/true);

  if (cap_w_ != dst_w || cap_h_ != dst_h) {
    if (tex_cap_) {
      glDeleteTextures(1, &tex_cap_);
      glDeleteFramebuffers(1, &fbo_cap_);
    }
    tex_cap_ = fbo_cap_ = 0;
    makeTex(&tex_cap_, &fbo_cap_, GL_RGBA8, dst_w, dst_h);
    cap_w_ = dst_w;
    cap_h_ = dst_h;
  }
  passOutput(dst_w, dst_h, p, true, false);  // 成片：斑马纹绝不进成片
  logPassDiag("pass3_still", fbo_cap_, false);
  out_rgba->resize(static_cast<size_t>(dst_w) * dst_h * 4);
  glReadPixels(0, 0, dst_w, dst_h, GL_RGBA, GL_UNSIGNED_BYTE, out_rgba->data());
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  return true;
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
    }
    tex_dir_ = tex_g_ = tex_mos_ = 0;
    fbo_dir_ = fbo_g_ = fbo_mos_ = 0;
    makeTex(&tex_dir_, &fbo_dir_, GL_RGBA16F, w, h);
    makeTex(&tex_g_, &fbo_g_, GL_RGBA16F, w, h);
    makeTex(&tex_mos_, &fbo_mos_, GL_RGBA16F, w, h);
    rcd_w_ = w;
    rcd_h_ = h;
  }
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_dir_);
  const bool ok1 = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_g_);
  const bool ok2 = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_mos_);
  const bool ok3 = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
  return ok1 && ok2 && ok3;
}

// 降噪输出目标：tex_dn_（旋转后 dst 尺寸，P4 输出，胶片链输入）
bool FrameRenderer::ensureDnTarget(int w, int h) {
  if (tex_dn_ != 0 && dn_w_ == w && dn_h_ == h) return true;
  if (tex_dn_) {
    glDeleteTextures(1, &tex_dn_);
    glDeleteFramebuffers(1, &fbo_dn_);
  }
  tex_dn_ = fbo_dn_ = 0;
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
  glUniform3f(u_raw_dn_.noise_b, nc.p0b[0], nc.p0b[1], nc.p0b[2]);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// P4：色度平滑（读 tex_scene_ → tex_dn_）。输出几何与 passRcdChroma 一致
void FrameRenderer::passChromaDenoise(int dst_w, int dst_h, int rot,
                                      const NoiseCoef& nc) {
  (void)rot;  // kVertRot 旋转已在顶点层；frag 用 gl_FragCoord 定位
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_dn_);
  glViewport(0, 0, dst_w, dst_h);
  glUseProgram(prog_chroma_dn_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, tex_scene_);
  glUniform1i(u_chroma_dn_.tex, 0);
  // C 专项：LSC 增益网格（传播 read 项 ×g²；关闭态绑 1×1 恒等 → 零分支）
  glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, lsc_tex_);
  glUniform1i(u_chroma_dn_.lsc, 1);
  glUniform2i(u_chroma_dn_.lsc_size, lsc_w_, lsc_h_);
  glUniform2i(u_chroma_dn_.size, dst_w, dst_h);
  glUniform3f(u_chroma_dn_.noise_a, nc.p4a[0], nc.p4a[1], nc.p4a[2]);
  glUniform3f(u_chroma_dn_.noise_b, nc.p4b[0], nc.p4b[1], nc.p4b[2]);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glActiveTexture(GL_TEXTURE0);
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
bool FrameRenderer::passChromaMultiScale(int dst_w, int dst_h,
                                        const NoiseCoef& nc) {
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
  glBindTexture(GL_TEXTURE_2D, tex_dn_);
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
  glUniform1i(u_chroma_apply_.l, 0);   // v0.10.57：**真正**停用（0.10.56 只加了 shader 门控却没把 uniform 置 0，核仍在跑）
  glUniform1f(u_chroma_apply_.kr, 2.5f);
  glActiveTexture(GL_TEXTURE0);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  return true;
}

// C 专项：tile RMS 归约（P0 输出 → tiles 网格 R16F）。每 tile 16×16 = 256 点
// 全采样（覆盖 2×2 全相位）。诊断读回：中心 + 三角 tile 的 RMS 与模型 σ。
TileRmsResult FrameRenderer::passTileRms(GLuint src_tex, int w, int h,
                                         const float a[3], const float b[3]) {
  TileRmsResult r;
  const int tw = w / 16, th = h / 16;
  if (tw <= 0 || th <= 0) return r;
  if (tex_rms_ == 0 || rms_w_ != tw || rms_h_ != th) {
    if (tex_rms_) glDeleteTextures(1, &tex_rms_);
    if (fbo_rms_) glDeleteFramebuffers(1, &fbo_rms_);
    // RGBA16F（R16F + 部分读回组合在 ES 3.0 不保证；RGBA16F + GL_FLOAT 是
    // X300 已验证组合，见 v0.6.48 浮点 readback 教训）
    makeTex(&tex_rms_, &fbo_rms_, GL_RGBA16F, tw, th);
    rms_w_ = tw;
    rms_h_ = th;
  }
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_rms_);
  glViewport(0, 0, tw, th);
  glUseProgram(prog_tile_rms_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, src_tex);
  glUniform1i(u_tile_rms_.tex, 0);
  glUniform2i(u_tile_rms_.size, w, h);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  // 诊断读回：一次读回整张 tiles 网格（RGBA16F ≈783KB），既取原来的 4 个采样
  // 点，也算全网格的稳健统计（nz 中位/p90）。此前 4 点分别读回、且只有 2 个 nz
  // 进日志 —— 单张 A/B 的样本量不足（2026-09-30 对齐 A/B 被此卡住）。
  const int sx[4] = {tw / 2, 0, tw - 1, 0};
  const int sy[4] = {th / 2, th - 1, th - 1, 0};
  std::vector<float> grid(static_cast<size_t>(tw) * th * 4);
  glReadPixels(0, 0, tw, th, GL_RGBA, GL_FLOAT, grid.data());
  for (int i = 0; i < 4; ++i) {
    const size_t o = (static_cast<size_t>(sy[i]) * tw + sx[i]) * 4;
    r.rms[i] = std::isfinite(grid[o]) ? grid[o] : 0.f;
    r.nz[i] = std::isfinite(grid[o + 1]) ? grid[o + 1] : 0.f;  // 去信号 σ（差分法）
    // 代入模型（G 通道典型的 k=1 系数：σ = √(A_g·RMS + B_g)；legacy 回退亦非零）
    r.sigma[i] = std::sqrt(std::max(a[1] * r.rms[i] + b[1], 0.f));
  }
  {
    std::vector<float> nzs, rmss;
    nzs.reserve(grid.size() / 4);
    rmss.reserve(grid.size() / 4);
    for (size_t k = 0; k + 1 < grid.size(); k += 4) {
      const float nz = grid[k + 1];
      if (!std::isfinite(nz) || nz <= 0.f) continue;  // 纯黑/无效 tile 不计
      nzs.push_back(nz);
      rmss.push_back(std::isfinite(grid[k]) ? grid[k] : 0.f);
    }
    if (!nzs.empty()) {
      double sum = 0.0;
      for (float v : nzs) sum += v;
      r.nz_mean = static_cast<float>(sum / nzs.size());
      std::sort(nzs.begin(), nzs.end());
      std::sort(rmss.begin(), rmss.end());
      r.nz_med = nzs[nzs.size() / 2];
      r.nz_p90 = nzs[nzs.size() * 9 / 10];
      r.rms_med = rmss[rmss.size() / 2];
      r.nz_n = static_cast<int>(nzs.size());
    }
  }
  while (glGetError() != GL_NO_ERROR) {
  }
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  r.ok = true;
  r.tiles_w = tw;
  r.tiles_h = th;
  return r;
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
    glBindTexture(GL_TEXTURE_2D, tex_mos_);
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
                             const float gains[3], bool mosaic) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_g_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_rcd_g_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, raw_tex_);
  if (u_rcd_g_.mos >= 0) {
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, tex_mos_);
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
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// RCD P3：色度填充 + CCM → 场景线性（tex_scene_）。输出几何与 passRaw 一致
// （kVertRot 旋转，uST 已在 init 置单位阵）。
void FrameRenderer::passRcdChroma(int dst_w, int dst_h, int rot, int cfa,
                                  const float black_phase[4],
                                  float white_level, const float gains[3],
                                  const float* ccm, bool mosaic) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_scene_);
  glViewport(0, 0, dst_w, dst_h);
  glUseProgram(prog_rcd_chroma_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, raw_tex_);
  if (u_rcd_chroma_.mos >= 0) {
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, tex_mos_);
    glUniform1i(u_rcd_chroma_.mos, 1);
  }
  if (u_rcd_chroma_.mosaic >= 0)
    glUniform1f(u_rcd_chroma_.mosaic, mosaic ? 1.0f : 0.0f);
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
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glActiveTexture(GL_TEXTURE0);
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
                              float noise_sigma,
                              std::vector<uint8_t>* out_rgba) {
  if (w <= 0 || h <= 0 || !raw || !black_phase) return false;
  frames_.fetch_add(1, std::memory_order_relaxed);
  // 分段计时（M3 §3.3 出口标准的度量基础）：上传 / pass 链 / 读回
  const auto t0 = std::chrono::steady_clock::now();

  if (ensureRawTex(w, h) == 0) return false;
  if (!uploadRawTex(raw_tex_, raw, w, h, row_stride_px)) return false;
  const auto t1 = std::chrono::steady_clock::now();

  const RawCommon rc =
      prepareRawCommon(neutral_r, neutral_g, neutral_b, p.wb_r, p.wb_g,
                       p.wb_b, ccm9, lsc, lsc_cols, lsc_rows);

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
  c.dn = rcd && noise_strength > 0.f;
  c.dn_k = noise_strength;
  c.nc = nc;
  c.nm = model;
  c.ns = nc.ns_typ;
  c.ns_raw = nc.ns_typ;
  c.lsc_ok = rc.lsc_ok;
  c.lsc_cols = lsc_cols;
  c.lsc_rows = lsc_rows;
  c.upload_ms = std::chrono::duration<float, std::milli>(t1 - t0).count();
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
  // scale_colors 的标准位置）。G 归一除以 wb_g，保持 G 增益 = 1 不改整体亮度；
  // 预览 OES / YUV 静拍在 pass1 乘同一组 (wb_r, wb_g, wb_b)，三条链语义一致。
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
  // 强度滑杆（0–2，步长 0.01）：σ 乘子 —— 0 = 关（A=B=0 精确跳过）、1 = 真实 σ、
  // 2 = 2×σ。P4 保持 1.5× 的比例（色差噪声 ≈ √2·σ_ch 且后续提亮再放大）。
  const float k_p0 = strength;
  const float k_p4 = 1.5f * strength;
  out->k_p0 = k_p0;
  out->k_p4 = k_p4;
  if (model.valid()) {
    const NoiseModel wb = model.afterWb(gains);  // P0 域（WB 后）
    for (int c = 0; c < 3; ++c) {
      out->p0a[c] = wb.S[c] * k_p0 * k_p0;
      out->p0b[c] = wb.O[c] * k_p0 * k_p0;
      out->p4a[c] = wb.S[c] * k_p4 * k_p4;
      out->p4b[c] = wb.O[c] * k_p4 * k_p4;
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
    const float sr_k = k_p0;
    const float sc_k = k_p4;
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
  float dir_ms = 0.0f, g_ms = 0.0f, dn_ms = 0.0f;
  TileRmsResult rms_res;  // C 专项：tile RMS 诊断（P0 后计算，日志用）
  if (c.rcd) {
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
    if (c.mfnr_fused) {
      passRawDenoiseFused(w, h, c.cfa, c.black_phase, c.white_level,
                          c.gains, c.dn ? c.nc : kNoDn);
    } else {
      passRawDenoise(w, h, c.cfa, c.black_phase, c.white_level, c.gains,
                     c.dn ? c.nc : kNoDn);
    }
    // D-2 诊断（v0.10.62）：P0 **之前**也测一次 tile RMS（tex_fused_），与 P0 之后的
    // 同网格结果对比即可读出 P0 的实际降噪量 —— 否则只能靠跨场景成片猜测
    //（0.10.57→0.10.58 的"1.5 倍"就是这么来的，不可信）。
    if (c.mfnr_fused && tex_fused_) {
      rms_pre_res = passTileRms(tex_fused_, w, h, c.nc.mma, c.nc.mmb);
    }
    // C 专项：tile RMS 归约（HDR+ §5，A 的 σ 输入底座；诊断读回进 raw.render）。
    // 位置 = P0 之后（tex_mos_ = 线性 mosaic，与模型同域），每 tile 256 采样。
    // σ 诊断用 k=1 的真实噪声系数（mma/mmb；融合时已折减）。
    rms_res = passTileRms(tex_mos_, w, h, c.nc.mma, c.nc.mmb);
    passRcdDir(w, h, c.cfa, c.black_phase, c.white_level, c.gains, true);
    glFinish();
    const auto d1 = std::chrono::steady_clock::now();
    passRcdG(w, h, c.cfa, c.black_phase, c.white_level, c.gains, true);
    glFinish();
    const auto d2 = std::chrono::steady_clock::now();
    dir_ms = std::chrono::duration<float, std::milli>(d1 - d0).count();
    g_ms = std::chrono::duration<float, std::milli>(d2 - d1).count();
    passRcdChroma(dst_w, dst_h, c.effective_rot, c.cfa, c.black_phase,
                  c.white_level, c.gains, c.ccm, true);
  } else {
    passRaw(dst_w, dst_h, c.effective_rot, c.cfa, c.black_phase, c.white_level,
            c.gains, c.ccm, p);
  }
  bool mst_ok = false;
  if (c.dn) {
    if (!ensureDnTarget(dst_w, dst_h)) return;
    const auto n0 = std::chrono::steady_clock::now();
    passChromaDenoise(dst_w, dst_h, c.effective_rot, c.nc);
    // D 专项（v0.10.53）：多尺度色度降噪（4-32px 色斑）。失败则保持 P4 输出
    mst_ok = passChromaMultiScale(dst_w, dst_h, c.nc);
    glFinish();
    dn_ms = std::chrono::duration<float, std::milli>(
                std::chrono::steady_clock::now() - n0)
                .count();
  }
  // v0.10.16：HR 已移入 passFilm（黑白点标定之后、still 才跑）——darktable 的
  // 高光重建与黑白点 autodetect 是配套的（裁剪阈值由 white_source 决定）。
  // D 专项：P5 成功时结果在 tex_scene_；否则回落 P4 的 tex_dn_
  passFilm(dst_w, dst_h, p,
           (c.dn && mst_ok) ? tex_scene_ : (c.dn ? tex_dn_ : tex_scene_),
           /*still=*/true);
  const auto t2 = std::chrono::steady_clock::now();

  if (cap_w_ != dst_w || cap_h_ != dst_h) {
    if (tex_cap_) {
      glDeleteTextures(1, &tex_cap_);
      glDeleteFramebuffers(1, &fbo_cap_);
    }
    tex_cap_ = fbo_cap_ = 0;
    makeTex(&tex_cap_, &fbo_cap_, GL_RGBA8, dst_w, dst_h);
    cap_w_ = dst_w;
    cap_h_ = dst_h;
  }
  passOutput(dst_w, dst_h, p, true, false);  // 成片：斑马纹绝不进成片
  // readPixels 是异步提交，glFinish 后计时才是真实端到端耗时（拍照单张路径可接受）
  glFinish();
  out_rgba->resize(static_cast<size_t>(dst_w) * dst_h * 4);
  glReadPixels(0, 0, dst_w, dst_h, GL_RGBA, GL_UNSIGNED_BYTE, out_rgba->data());
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  const auto t3 = std::chrono::steady_clock::now();
  const auto ms = [](auto a, auto b) {
    return std::chrono::duration<double, std::milli>(b - a).count();
  };
  std::string kv =
      std::string("{\"uploadMs\":") + jnum(c.upload_ms, 6) +
      ",\"passMs\":" + jnum(ms(t1, t2), 6) +
      ",\"totalMs\":" + jnum(ms(t0, t3), 7) +
      ",\"w\":" + std::to_string(w) + ",\"h\":" + std::to_string(h) +
      ",\"demosaic\":\"" + (c.rcd ? "rcd" : "bilinear") + "\"";
  if (c.rcd) {
    kv += ",\"dirMs\":" + jnum(dir_ms, 6) +
          ",\"gMs\":" + jnum(g_ms, 6);
  }
  if (c.dn) {
    // C 专项（2026-09-29）：噪声模型诊断族 —— nzSrc/nzIso/nzS/nzO = 模型来源与
    // 逐 ISO 系数（WB 前，[0,1] 域）；p0A/p0B/p4A/p4B = 实际下发 shader 的 (A,B)
    // （WB 后、含强度 k²；σ² = A·x + B）；ns/nsHal = 典型 σ（融合后/单帧）；
    // rms/rmsSig = tile RMS 采样（中心/左上/右上/左下）与模型代入后的 σ ——
    // 验收口径：σ 随 ISO（nzS/O 变）与信号（rmsSig 随 rms）变化，P0/P4/A 同源。
    const auto fe = [](float v) {
      char b[32];
      snprintf(b, sizeof(b), "%.4e", v);
      return std::string(b);
    };
    const auto fa3 = [&](const float* v) {
      return "[" + fe(v[0]) + "," + fe(v[1]) + "," + fe(v[2]) + "]";
    };
    kv += std::string(",\"dn\":") + jnum(c.dn_k, 4) +
          ",\"nzSrc\":\"" + (c.nc.model_src ? "cam2" : "legacy") + "\"" +
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
          ",\"dnMs\":" + jnum(dn_ms, 6);
    if (rms_res.ok) {
      kv += ",\"rms\":[" + jnum(rms_res.rms[0], 6) + "," +
            jnum(rms_res.rms[1], 6) + "," +
            jnum(rms_res.rms[2], 6) + "," +
            jnum(rms_res.rms[3], 6) + "]";
      char sb[4][32];
      for (int i = 0; i < 4; ++i) snprintf(sb[i], sizeof(sb[i]), "%.4e", rms_res.sigma[i]);
      kv += std::string(",\"rmsSig\":[") + sb[0] + "," + sb[1] + "," + sb[2] + "," + sb[3] + "]";
    }
  }
  kv += ",\"tone\":" + jnum(p.tone_strength, 3);
  kv += ",\"ev\":" + jnum(p.exposure_ev, 5);
  // 实际进 RAW 链的用户 WB 补偿（目标/估计增益比）：1,1 = 自动/中性。
  // 与预览 OES 的 uWb 同值——两者不一致即"M 档 WB 与预览不符"的直接证据。
  kv += ",\"wb\":[" + jnum(p.wb_r, 6) + "," +
        jnum(p.wb_g, 6) + "," +
        jnum(p.wb_b, 6) + "]";
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
  // spline 系数（v0.7.3 自检）：真机日志 → 用 tools/tuning/filmic_offline.py 的
  // compute_spline 按同一组 EV 复算，逐项对比 m1..m5 —— 系数不一致就是实现错了。
  {
    const auto j3 = [](const float* v) {
      return "[" + std::to_string(v[0]) + "," + std::to_string(v[1]) + "," +
             std::to_string(v[2]) + "]";
    };
    kv += ",\"fM1\":" + j3(filmic_.m1) + ",\"fM2\":" + j3(filmic_.m2) +
          ",\"fM3\":" + j3(filmic_.m3) + ",\"fM4\":" + j3(filmic_.m4) +
          ",\"fM5\":" + j3(filmic_.m5);
    kv += ",\"fLat\":[" + std::to_string(filmic_.lat_min) + "," +
          std::to_string(filmic_.lat_max) + "]";
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
  if (!c.mfnr_kv.empty()) kv += "," + c.mfnr_kv;
  // v0.10.59：新增算法的生效性字段 —— 必须在这里（finishStillChain 内、日志发出
  // 前一行），因为 P0 与色度多尺度两个 pass 都在本函数内执行完；此前误加在
  // submitRawBurst 的 c.mfnr_kv 与调用点之后，均读不到值（0.10.59 真机 p0s 缺失）。
  kv += ",\"p0s\":" + std::to_string(dbg_p0s_);
  kv += ",\"cms\":" + std::to_string(dbg_cms_);
  kv += ",\"cmsK\":" + jnum(dbg_cms_k_, 4);
  // p0w = 中心点权重和；接近 9 = 样本几乎全被值域权重拒绝，接近 97 = 全采纳
  kv += ",\"p0w\":" + jnum(dbg_p0w_, 6);
  // P0 前后 tile RMS（同网格）→ 直接读出 P0 的实际降噪倍数
  if (rms_pre_res.ok && rms_res.ok) {
    kv += ",\"rmsPre\":[" + jnum(rms_pre_res.rms[0], 7) + "," +
          jnum(rms_pre_res.rms[1], 7) + "]";
    kv += ",\"rmsPost\":[" + jnum(rms_res.rms[0], 7) + "," +
          jnum(rms_res.rms[1], 7) + "]";
    // 去信号的噪声 σ（差分法）：nzPre/nzPost 的比值才是 P0 的真实降噪倍数
    kv += ",\"nzPre\":[" + jnum(rms_pre_res.nz[0], 8) + "," +
          jnum(rms_pre_res.nz[1], 8) + "]";
    kv += ",\"nzPost\":[" + jnum(rms_res.nz[0], 8) + "," +
          jnum(rms_res.nz[1], 8) + "]";
    // 全网格稳健统计（v0.10.72）：降噪倍数用 nzPreMed/nzPostMed 的比值判定 ——
    // 单点读数会被"这次手抖幅度/场景内容"带偏，中位数不会。
    kv += ",\"nzPreMed\":" + jnum(rms_pre_res.nz_med, 8);
    kv += ",\"nzPostMed\":" + jnum(rms_res.nz_med, 8);
    kv += ",\"nzPreP90\":" + jnum(rms_pre_res.nz_p90, 8);
    kv += ",\"nzPostP90\":" + jnum(rms_res.nz_p90, 8);
    kv += ",\"nzN\":" + std::to_string(rms_pre_res.nz_n);
  }
  kv += "}";
  Logger::instance().log(LogLevel::kInfo, "engine", "raw.render", kv);
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
  for (auto& t : tex_off_) {
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
  // mismatch map（RGBA8，4×tiles 网格 = 32px cell；m∈[0,1] 8bit 量化 0.4% 可忽略）
  const int cw = tw * 4, chh = th * 4;
  if (tex_mis_) {
    glDeleteTextures(1, &tex_mis_);
    glDeleteFramebuffers(1, &fbo_mis_);
  }
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
                                const float gains[3]) {
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
                                 const float gains[3]) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_fused_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_mfnr_fuse_);
  const GLenum units[8] = {GL_TEXTURE0, GL_TEXTURE1, GL_TEXTURE2, GL_TEXTURE3,
                           GL_TEXTURE4, GL_TEXTURE5, GL_TEXTURE6, GL_TEXTURE7};
  const GLuint raws[4] = {raw_tex_, tex_mfnr_[0], tex_mfnr_[1], tex_mfnr_[2]};
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
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// ── 降噪专项 A：DFT 逐频收缩合并（HDR+ §5 式 6/7）────────────────────────
// burst 4 帧汇集到 R16UI 2DArray（GPU-GPU copy）→ kFsMfnrFft compute 4 次
// dispatch（tile 相位组串行化 overlap-add）→ per-tile neff 读回均值。
// 输出写进 tex_fused_（与 fuse 路径同域同格式），后段 P0/RCD/胶片链零改动。
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
                                const float plane_b[3], float* out_neff,
                                float* out_probe,
                                float* out_ms) {
  *out_neff = 0.f;
  out_probe[0] = out_probe[1] = out_probe[2] = out_probe[3] = -1.f;
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
  const GLuint fft_raws[4] = {raw_tex_, tex_mfnr_[0], tex_mfnr_[1],
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
    // 微测试（v0.10.50 双分支对照）:1-texel imageStore,与内核完全解耦,
    // **唯一变量 = 输出纹理的创建方式**——同 program、同绑定顺序、同 dispatch、
    // 同读回,一次真机定案根因:
    //   micro:false + microSt:true → 真变量是 glTexImage2D 可变纹理（该 Mali
    //     对可变存储的 imageStore 丢写），主内核改 glTexStorage2D 即修复
    //   micro:false + microSt:false → 差异不止纹理创建,回到 spike 逐行 diff
    // 绑定顺序逐字复刻 spike:use → uniform → **bind image** → dispatch。
    GLuint micro_fbo = 0, micro_prog = 0;
    float micro_v[4] = {0, 0, 0, 0}, micro_st_v[4] = {0, 0, 0, 0};
    {
      const GLuint cs = compileShader(GL_COMPUTE_SHADER, kFsMicroStore);
      if (cs) {
        micro_prog = glCreateProgram();
        glAttachShader(micro_prog, cs);
        glLinkProgram(micro_prog);
        GLint linked = 0;
        glGetProgramiv(micro_prog, GL_LINK_STATUS, &linked);
        if (!linked) {
          glDeleteProgram(micro_prog);
          micro_prog = 0;
        }
      }
      if (cs) glDeleteShader(cs);
    }
    if (micro_prog != 0) {
      glGenFramebuffers(1, &micro_fbo);
      glBindFramebuffer(GL_FRAMEBUFFER, micro_fbo);
      for (int k = 0; k < 2; ++k) {
        GLuint mtex = 0;
        glGenTextures(1, &mtex);
        glBindTexture(GL_TEXTURE_2D, mtex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        if (k == 0) {
          // 旧路径（0.10.37–49 一直丢写的那条）
          glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, 16, 16, 0, GL_RGBA,
                       GL_HALF_FLOAT, nullptr);
        } else {
          // spike 同款（真机唯一验证过写入成功的创建方式）
          glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, 16, 16);
        }
        glUseProgram(micro_prog);
        glUniform1i(glGetUniformLocation(micro_prog, "uMicro"), 0);
        glBindImageTexture(0, mtex, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute(1, 1, 1);
        glMemoryBarrier(GL_ALL_BARRIER_BITS);
        glFinish();  // spike 同款同步:排除"读得太早"的假阴性
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                               GL_TEXTURE_2D, mtex, 0);
        glReadPixels(0, 0, 1, 1, GL_RGBA, GL_FLOAT,
                     k == 0 ? micro_v : micro_st_v);
        glDeleteTextures(1, &mtex);
      }
      glBindFramebuffer(GL_FRAMEBUFFER, 0);
      glDeleteFramebuffers(1, &micro_fbo);
      // image unit 0 用毕重绑回 tex_fft_out_[0]（主内核输出）
      glBindImageTexture(0, tex_fft_out_[0], 0, GL_FALSE, 0, GL_WRITE_ONLY,
                         GL_RGBA16F);
      glDeleteProgram(micro_prog);
      while (glGetError() != GL_NO_ERROR) {
      }
    }
    const bool micro_ok = micro_v[0] > 0.5f;
    const bool micro_st_ok = micro_st_v[0] > 0.5f;

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
    // spike 对照（v0.10.46 起保留）：真机验证过的完整参考路径在同上下文跑
    // 一次，spikeOk=false 即可判定"拍照上下文整体写入失效"（换实现路线）。
    // 0.10.49 起它同时是"我方内核 vs 参考路径"的活体对照：一直 true，说明
    // 上下文与 program 创建时机都不是变量（真变量见 ensureMfnrFftTargets）。
    const FftSpikeResult sp = runSpikeFftMerge(256, 256, 1, false, 2);
    Logger::instance().log(
        LogLevel::kInfo, "engine", "mfnr.fft.store_check",
        std::string("{\"sum\":") +
            jnum(chk_sum, 8) +
            ",\"micro\":" + (micro_ok ? "true" : "false") +
            ",\"microSt\":" + (micro_st_ok ? "true" : "false") +
            ",\"microProg\":\"live\"" +
            ",\"spikeOk\":" + (sp.readback_ok ? "true" : "false") +
            ",\"spikeGpu\":" + jnum(sp.gpu_median_ms, 8) +
            ",\"spikeErr\":\"" + jsonEscape(sp.error) + "\"}");
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
  // 网格纹探针：中心 2×2 mosaic 块（4 个 CFA 相位各一点）。DFT 正确时四点值应
  // 彼此接近（同一物理位置的相邻采样）；相位组/层错位会立刻表现为块内跳变
  // ——v0.10.50 绿色网格事故就是 dftProbe 两点同址但 0.494 vs 0.042/0.030 的
  // 散点布局掩盖了块内跳变。
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_fused_);
  const int pcx = (w / 2) & ~1, pcy = (h / 2) & ~1;
  const int px4[4] = {pcx, pcx + 1, pcx, pcx + 1};
  const int py4[4] = {pcy, pcy, pcy + 1, pcy + 1};
  for (int k = 0; k < 4; ++k) {
    float v[4] = {0, 0, 0, 0};
    glReadPixels(px4[k], py4[k], 1, 1, GL_RGBA, GL_FLOAT, v);
    out_probe[k] = v[0];
  }
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
                                     float s_const) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_mis_);
  glViewport(0, 0, tiles_w_ * 4, tiles_h_ * 4);
  glUseProgram(prog_mfnr_mis_);
  const GLenum units[7] = {GL_TEXTURE0, GL_TEXTURE1, GL_TEXTURE2, GL_TEXTURE3,
                           GL_TEXTURE4, GL_TEXTURE5, GL_TEXTURE6};
  const GLuint raws[4] = {raw_tex_, tex_mfnr_[0], tex_mfnr_[1], tex_mfnr_[2]};
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
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

void FrameRenderer::passRawDenoiseFused(int w, int h, int cfa,
                                        const float black_phase[4],
                                        float white_level, const float gains[3],
                                        const NoiseCoef& nc) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_mos_);
  glViewport(0, 0, w, h);
  // D-2（v0.10.58）：时域 × 空间联合（4 帧各取 3×3 同相位邻域 = 36 独立样本）。
  // 缺任一输入（无位移场/无原始帧）则回落到旧的"融合后 9 样本"路径。
  if (prog_raw_dn_ts_ && tex_off_[2] != 0 && tex_mfnr_[2] != 0) {
    glUseProgram(prog_raw_dn_ts_);
    const GLuint raws[4] = {raw_tex_, tex_mfnr_[0], tex_mfnr_[1], tex_mfnr_[2]};
    for (int i = 0; i < 4; ++i) {
      glActiveTexture(GL_TEXTURE0 + i);
      glBindTexture(GL_TEXTURE_2D, raws[i]);
      glUniform1i(u_raw_dn_ts_.raw[i], i);
    }
    for (int i = 0; i < 3; ++i) {
      glActiveTexture(GL_TEXTURE4 + i);
      glBindTexture(GL_TEXTURE_2D, tex_off_[i]);
      glUniform1i(u_raw_dn_ts_.off[i], 4 + i);
    }
    glActiveTexture(GL_TEXTURE7);
    glBindTexture(GL_TEXTURE_2D, tex_fused_);
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
    glUniform3f(u_raw_dn_ts_.noise_b, nc.p0b[0], nc.p0b[1], nc.p0b[2]);
    glUniform1f(u_raw_dn_ts_.sr_scale, 2.2360679f);   // √(neff+1)，neff≡4
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
  glBindTexture(GL_TEXTURE_2D, tex_fused_);
  glUniform1i(u_raw_dn_f_.tex, 0);
  glUniform2i(u_raw_dn_f_.size, w, h);
  glUniform1i(u_raw_dn_f_.cfalut, cfaLutOf(cfa));
  glUniform3f(u_raw_dn_f_.noise_a, nc.p0a[0], nc.p0a[1], nc.p0a[2]);
  glUniform3f(u_raw_dn_f_.noise_b, nc.p0b[0], nc.p0b[1], nc.p0b[2]);
  dbg_p0s_ = 9;                                      // 旧路径：融合后 9 空间样本
  dbg_p0w_ = -1.f;                                   // 旧路径无 wsum 诊断
  glDrawArrays(GL_TRIANGLES, 0, 3);
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
  makeTex(&tex_hr_hf_, &fbo_hr_hf_, GL_RGBA16F, w, h);
  makeTex(&tex_hr_hfrgb_, &fbo_hr_hfrgb_, GL_RGBA16F, w, h);
  makeTex(&tex_hr_temp_, &fbo_hr_temp_, GL_RGBA16F, w, h);
  makeTex(&tex_hr_recon_[0], &fbo_hr_recon_[0], GL_RGBA16F, w, h);
  makeTex(&tex_hr_recon_[1], &fbo_hr_recon_[1], GL_RGBA16F, w, h);
  makeR16F(&tex_hr_mask_, &fbo_hr_mask_, w, h);
  makeR16F(&tex_hr_norms_, &fbo_hr_norms_, w, h);
  // v0.10.17：stat 用 RGBA8（makeTex）——R16F attachment 配 GL_UNSIGNED_BYTE
  // readback 在 ES 3.0 不保证可用（X300 实测静默回零 → clipB 恒 0、HR 永不触发）。
  // mask ∈ [0,1] 的 8bit 量化对"块内 max > 1/17"判据足够。
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
                                              int* out_clipped_blocks,
                                              float* out_ms) {
  if (out_clipped_blocks) *out_clipped_blocks = -1;
  if (out_ms) *out_ms = 0.0f;
  if (!ensureHrTargets(w, h)) return src;
  const auto t0 = std::chrono::steady_clock::now();

  // 参数（darktable 默认换算，全部物理量、不可调）：
  //   threshold = 2^(white_source + 0EV) × grey_source；feather = 2^(12/3) = 16；
  //   normalize = feather / threshold；noise_level 0.2；
  //   gamma/gamma_comp = 0.5/0.5（structure_vs_texture 0 的换算 (p/100+1)/2）；
  //   beta/beta_comp 1/0；delta 1；HQ 迭代 1。
  // v0.10.18：threshold 与 filmic 的 white_source **解耦，恒 = RAW 饱和数据白点
  // 1.0**（= 2^2.4383EV × 0.1845）。原因：0.10.17 真机——white 自动（P99）在无
  // 饱和场景掉到 1.44EV → 阈值 0.5 → 比 P99 亮的普通像素全被当"裁剪"送进小波
  // 重建（clipB=1435/3072 = 47% 的块，灾难）。darktable 语境里 white_source 由
  // 用户设在数据 max 附近 → 阈值 ≈ 数据白点；自动语境下"裁剪重建"的物理语义 =
  // RAW 饱和（线性 1.0），恒定阈值是该行为的忠实落地。
  constexpr float kGrey = 0.1845f;
  const float threshold = 1.0f;
  const float feather = 16.0f;
  const float normalize = feather / threshold;
  const float noise_level = 0.2f;
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

  // ② inpaint_noise（裁剪区注入乘性高斯噪声；写 lf[0] 作为 s=0 的 detail）
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_hr_lf_[0]);
  glViewport(0, 0, w, h);
  glUseProgram(prog_hr_inpaint_);
  bind2(u_hr_inp_tex_, src, u_hr_inp_mask_, tex_hr_mask_);
  glUniform1f(u_hr_inp_noise_, noise_level);
  glUniform1f(u_hr_inp_thr_, threshold);
  glDrawArrays(GL_TRIANGLES, 0, 3);

  // ③ init_reconstruct（recon[0] = max(inpainted×(1−mask), 0)）
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_hr_recon_[0]);
  glUseProgram(prog_hr_rin_);
  bind2(u_hr_rin_tex_, tex_hr_lf_[0], u_hr_rin_mask_, tex_hr_mask_);
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
  wavelets(tex_hr_lf_[0], 0, &recon_rd);

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
                                   bool align_l1,
                                   std::vector<uint8_t>* out_rgba) {
  if (w <= 0 || h <= 0 || !frames || !black_phase || !row_strides_px)
    return false;
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

  // 上传：帧 0 → raw_tex_（参考帧），帧 1..3 → tex_mfnr_
  if (ensureRawTex(w, h) == 0) return false;
  if (!uploadRawTex(raw_tex_, frames[0], w, h, row_strides_px[0])) return false;
  if (!ensureMfnrTargets(w, h)) return false;
  for (int i = 1; i < count; ++i) {
    if (!uploadRawTex(tex_mfnr_[i - 1], frames[i], w, h, row_strides_px[i]))
      return false;
  }
  const RawCommon rc =
      prepareRawCommon(neutral_r, neutral_g, neutral_b, p.wb_r, p.wb_g,
                       p.wb_b, ccm9, lsc, lsc_cols, lsc_rows);
  const float wl = static_cast<float>(white_level);
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
  auto capturePyr = [&](GLuint raw_tex, MfnrPyramid* py) {
    passMfnrPyr(raw_tex, cfa, black_phase, wl, rc.gains);
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
  capturePyr(raw_tex_, &ref);
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
    capturePyr(tex_mfnr_[i - 1], &cur);
    const auto cpu0 = std::chrono::steady_clock::now();
    const MfnrAlignOut a = mfnrAlign(ref, cur, tiles_w_, tiles_h_);
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

  // ── 合并路径二选一（降噪专项 A，2026-09-29 立项）──────────────────────
  // use_dft = true：DFT 逐频收缩合并（HDR+ §5 式 6/7，passMfnrFft）——逐频
  // 收缩自身鲁棒（错位/运动帧在高频被 Az 拒绝），按 HDR+ 原版语义**不叠**
  // mismatch/conf 权重，mismatch 链整体跳过；neff 由内核 per-tile 频率平均
  // 读回（NS §3.3"按实际合并更新 σ"）。
  // use_dft = false：原阶段二 robust merge（mismatch map + 分段权重 + fuse）。
  float fuse_ms = 0.f, neff = 0.f, m_med = 0.f, m_p90 = 0.f, mis_ms = 0.f,
        mot_med = 0.f, res_fade = 1.f, neff_dft = 0.f,
        dft_probe[4] = {-1, -1, -1, -1}, dft_ms[4] = {0, 0, 0, 0};
  std::string mis_diag, dil_diag, mot_diag, msm_diag;
  bool dft_active = false;
  // upload_ms 语义 = t0 → 融合开始（两路径统一：upload+align+合并前段）
  std::chrono::steady_clock::time_point tf;
  if (use_dft) {
    stage("dft");
    tf = std::chrono::steady_clock::now();
    if (!ensureMfnrFftTargets(w, h)) return false;
    dft_active = passMfnrFft(w, h, cfa, black_phase, wl, rc.gains, nc_single.p0a,
                             nc_single.p0b, &neff_dft, dft_probe, dft_ms);
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
                   kMfnrMisS);
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
  passMfnrFuse(w, h, cfa, black_phase, wl, rc.gains);
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
  c.p = &p;
  c.effective_rot = effective_rot;
  c.rcd = rcd;
  c.dn = rcd && noise_strength > 0.f;
  c.dn_k = noise_strength;
  c.nc = nc;
  c.nm = model;
  c.ns = ns;
  c.ns_raw = ns_single;
  c.lsc_ok = rc.lsc_ok;
  c.lsc_cols = lsc_cols;
  c.lsc_rows = lsc_rows;
  c.upload_ms = std::chrono::duration<float, std::milli>(tf - t0).count();
  c.mfnr_fused = true;
  dbg_p0s_ = 0; dbg_cms_ = 0; dbg_cms_k_ = 0.f; dbg_p0w_ = 0.f;
  rms_pre_res = TileRmsResult{};
  const auto f3 = [](float v) { return jnum(v, 5); };
  c.mfnr_kv = std::string("\"mfnr\":\"on\"") +
              ",\"fuse\":\"" + (dft_active ? "dft" : "mm") + "\"" +

              (dft_active ? ",\"dftNeff\":" + f3(neff_dft) +
                                ",\"dftUp\":" + f3(dft_ms[0]) +
                                ",\"dftDsp\":" + f3(dft_ms[1]) +
                                ",\"dftRb\":" + f3(dft_ms[2]) +
                                ",\"dftGpu\":" + f3(dft_ms[3]) +

                                ",\"dftProbe\":[" +
                                f3(dft_probe[0]) + "," + f3(dft_probe[1]) + "," +
                                f3(dft_probe[2]) + "," + f3(dft_probe[3]) + "]"
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
              ",\"motMed\":" + f3(mot_med) +
              ",\"resFade\":" + f3(res_fade) +
              ",\"nsSng\":" + f3(ns_single) +
              ",\"misD\":\"" + mis_diag + "\"" +
              ",\"dilD\":\"" + dil_diag + "\"" +
              ",\"motD\":\"" + mot_diag + "\"" +
              ",\"msmD\":\"" + msm_diag + "\"" +
              ",\"dMed\":" + f3(dx_med_max) +
              ",\"dP90\":" + f3(dx_p90_max) +
              ",\"tiles\":" + std::to_string(tiles_w_ * tiles_h_);
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
  const float k = strength < 0.0f ? 0.0f : (strength > 1.0f ? 1.0f : strength);
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
                                 int stat_ch, int stat_mode) {
  if (out == nullptr || n < 2 || src == 0 || scene_w_ <= 0) return false;
  // v0.10.28：256×96 → 512×288。24K 采样点上 P99.99 = 前 2.5 名、max 采样对
  // 0.001% 级的小面积高光（水壶镜面/白壶身）几乎必然 miss——真机 162012 实证：
  // 渲染域最亮 1.02（LSC 1.2× 抬升后刚好过数据白点）但统计 w999 只见 2.05，
  // 白点不上探、4.1% 的白壶身渐变被肩部压进 245+ 平台。147K 点让 P99.99 有
  // 15 个样本、max 有望命中 0.002% 级目标；readback 590KB/帧（≈0.3ms）可接受。
  // UI 直方图共用本 FBO（桶映射按 8bit 值，与 FBO 尺寸无关）。
  const int hw = 512, hh = 288;
  if (tex_hist_ == 0) {
    makeTex(&tex_hist_, &fbo_hist_, GL_RGBA8, hw, hh);
    hist_w_ = hw;
    hist_h_ = hh;
  }
  renderLuma(src, fbo_hist_, hw, hh, bypass_lut ? 0.0f : lut_strength_, false,
             stat_ch, stat_mode);
  std::vector<uint8_t> px(static_cast<size_t>(hw) * hh * 4);
  glReadPixels(0, 0, hw, hh, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
  for (int i = 0; i < n; ++i) out[i] = 0;
  for (size_t i = 0; i + 3 < px.size(); i += 4) {
    ++out[px[i] * (n - 1) / 255];
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
FrameRenderer::SceneStats FrameRenderer::measureSceneStats(GLuint src) {
  // v0.10.27：64 → 256 桶。对数域统计编码下 readback 的 256 级与桶一一对应
  // （px[i] ∈ [0,255] → out[px[i]]），每级 0.078EV；旧 64 桶会把这分辨率扔掉 3/4。
  constexpr int kN = 256;
  SceneStats st;
  int hist[kN];
  if (!histogramFrom(src, hist, kN, /*bypass_lut=*/true, /*stat_ch=*/1,
                     /*stat_mode=*/1)) return st;
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
  st.p_lo = to_lin(pct(0.001));
  st.p_mid = to_lin(pct(0.50));
  st.p_hi = to_lin(pct(0.99));
  st.p_hi999 = to_lin(pct(0.999));
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
                                                               float latitude_in) {
  // 全程 double：shoulder 的 5×5 系统条件数差，用 float 中间量会把 1e-7 的舍入放大到
  // 1e-5（与 float64 参考实现对拍时可见）。虽然远低于 8bit 量化精度（1/255 ≈ 4e-3），
  // 但没有理由把它引进来 —— darktable 的 gauss_solve 同样在 double 下求解。
  constexpr double kGrey = 0.1845;                // grey_source（18.45% 中灰）
  constexpr double kSafety = 0.01;                // darktable SAFETY_MARGIN
  constexpr double kBlackTargetPct = 0.01517634;  // black_point_target 默认（%）
  // v0.10.27：contrast/latitude 由 constexpr 提升为函数参数 —— darktable 原版就是
  // 四个独立滑杆（black/white/latitude/contrast），此前 latitude 恒 0.01、contrast 恒
  // 1.0 只是不带滑杆的默认值。自动白点高光上探用 contrast 参数补偿 power 回退
  // （updateFilmicEV）；latitude 保持 darktable 默认（0.01）。
  const double kContrastParam = static_cast<double>(contrast_param_in);
  const double kLatitude = static_cast<double>(latitude_in);
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
  const double toe_log = (1.0 - kLatitude) * grey_log + kLatitude * xmin;
  const double sh_log = (1.0 - kLatitude) * grey_log + kLatitude * xmax;
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
      sp.m5[0] = static_cast<float>(x[0]);
      sp.m4[0] = static_cast<float>(x[1]);
      sp.m3[0] = static_cast<float>(x[2]);
      sp.m2[0] = static_cast<float>(x[3]);
      sp.m1[0] = static_cast<float>(x[4]);
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
      sp.m5[1] = static_cast<float>(x[0]);
      sp.m4[1] = static_cast<float>(x[1]);
      sp.m3[1] = static_cast<float>(x[2]);
      sp.m2[1] = static_cast<float>(x[3]);
      sp.m1[1] = static_cast<float>(x[4]);
    }
  }
  // 中间线性段：y = (toe_d − contrast·toe_log) + contrast·x
  sp.m1[2] = static_cast<float>(toe_d - contrast * toe_log);
  sp.m2[2] = static_cast<float>(contrast);
  sp.m3[2] = 0.0f;
  sp.m4[2] = 0.0f;
  sp.m5[2] = 0.0f;

  sp.lat_min = static_cast<float>(toe_log);
  sp.lat_max = static_cast<float>(sh_log);
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

// ── 自动 EV 标定（v0.7.3）────────────────────────────────────────────────────
// 自适应对象从"亮度"换成"动态范围"：black/white relative exposure 由场景分位算出，
// 中灰锚点固定 18.45%。于是**不同场景输出不同亮度**（暗的地方暗），而压 EV / 降 AE
// 曝光能真的传导到成片 —— 旧的"中位锚定"会把它们补偿掉（v0.7.2 的死结）。
// auto_on=false：用 darktable 默认（−7.65/+4.56），曲线完全固定、不随场景变。
void FrameRenderer::updateFilmicEV(GLuint src, bool still, bool auto_on,
                                  float user_black, float user_white,
                                  float white_hl, float user_ev) {
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
    const float w_max = std::log2(std::max(st.p_max, 1e-6f) / kGrey);
    if (white_hl > 0.5f) {
      w = std::max(w99, std::min(w_max + 0.4f, w99 + 1.5f));
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
    w = std::clamp(w, 2.438307f, 4.556152f) + ev_shift;
    // ── contrast_param 补偿（darktable contrast 滑杆的自动化用法）────────────
    // 上探拉大 dr → power 派生回落（151748 型场景 4.70→~3.2），中灰以上提亮变少
    // = 用户验收过的"通透感"回退。补偿 = 上探前后 power 比（cp = power_old/new，
    // clamp [1.0, 1.6] 只补不降）：spline v3 里中灰斜率 = contrast_param/8 与
    // power 无关，cp 直接抬对数域斜率，把上探前曲线的中间调梯度找回来；肩部
    // 过渡宽度由 dr 决定不受 cp 影响 —— 高光余量与中间调对比同时保住。
    // 未上探（w == w_old）时 cp 恒 1.0，固定曲线（auto=0）不走此分支恒 1.0。
    const float w_old = std::clamp(w99, 2.438307f, 4.556152f) + ev_shift;
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
  filmic_ = computeFilmicSpline(ev_black_, ev_white_, cp);
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
                             bool still) {
  // filmic 的自动 EV 标定（v0.7.3）：**刻意放在 passFilm 内** —— 预览 / YUV 静拍 /
  // RAW 静拍三条路径都经过这里，一处实现即全覆盖。
  // 开关语义（v0.7.6）：黑白点来自用户配置（FilmParams[37]/[38]，默认 −7.65/+4.56）；
  // auto_tone 开 = **黑点**改由场景分位自动（白点仍用配置值），默认关。
  // 注意：updateFilmicEV 内部会切 FBO/viewport（直方图统计），必须先于下面的绑定调用。
  updateFilmicEV(src_tex, still, p.auto_tone > 0.5f, p.filmic_black_ev,
                 p.filmic_white_ev, p.white_hl, p.exposure_ev);
  // ── v0.10.15/18：darktable filmic 高光重建（HR），仅成片路径 ─────────────
  // 裁剪阈值恒 = RAW 饱和数据白点 1.0（物理量，与 filmic 白点解耦——0.10.17 的
  // 阈值随 white 自动掉到 0.5 曾把 47% 的块送进重建）。重建结果直接替换 filmic
  // 的输入（darktable: in = reconstructed）。blocks == 0（无裁剪像素）或分配
  // 失败时返回原纹理。
  hr_blocks_ = -1;
  hr_ms_ = 0.0f;
  GLuint film_src = src_tex;
  if (still && p.hr_wavelet > 0.5f) {
    film_src = runHighlightReconstruct(src_tex, w, h, &hr_blocks_, &hr_ms_);
  }
  // 预览域映射的在线标定（v0.8.0）：updateFilmicEV 刚把 last_lin_mid_ 更新为本帧
  // 统计中位 —— 预览帧 = HAL OES 域，静拍帧 = RAW/YUV 链域。同一场景两类帧交替
  // 到达，用 EMA 各记一侧，静拍时对出比值：
  //   ratio = 预览中位 / RAW 中位（v0.6.48 实测范围 1.70–3.87，EMA 平滑 AE/构图抖动）
  // 预览除以它回到 RAW 等效域（shader 的 uPreviewMap 分支），filmic 曲线因此与
  // 成片共用（WYSIWYG）。首拍前 ratio 用初值 2.0（实测范围中位）兜底。
  if (still) {
    if (g_pv_mid_ema > 1e-4f && last_lin_mid_ > 1e-6f) {
      const float r = std::clamp(g_pv_mid_ema / last_lin_mid_, 1.2f, 4.5f);
      g_domain_ratio = g_domain_ratio * 0.7f + r * 0.3f;
    }
  } else {
    g_pv_mid_ema = g_pv_mid_ema > 1e-4f ? g_pv_mid_ema * 0.7f + last_lin_mid_ * 0.3f
                                        : last_lin_mid_;
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
  glUniform3f(u_film_.filmic_m1, filmic_.m1[0], filmic_.m1[1], filmic_.m1[2]);
  glUniform3f(u_film_.filmic_m2, filmic_.m2[0], filmic_.m2[1], filmic_.m2[2]);
  glUniform3f(u_film_.filmic_m3, filmic_.m3[0], filmic_.m3[1], filmic_.m3[2]);
  glUniform3f(u_film_.filmic_m4, filmic_.m4[0], filmic_.m4[1], filmic_.m4[2]);
  glUniform3f(u_film_.filmic_m5, filmic_.m5[0], filmic_.m5[1], filmic_.m5[2]);
  glUniform1f(u_film_.filmic_lat_min, filmic_.lat_min);
  glUniform1f(u_film_.filmic_lat_max, filmic_.lat_max);
  glUniform1f(u_film_.filmic_black_ev, filmic_.black_ev);
  glUniform1f(u_film_.filmic_dr, filmic_.dynamic_range);
  glUniform1f(u_film_.filmic_input_min, filmic_.input_min);
  glUniform1f(u_film_.filmic_input_max, filmic_.input_max);
  glUniform1f(u_film_.filmic_power, filmic_.output_power);
  glUniform1f(u_film_.filmic_black_disp, filmic_.black_disp);
  // v0.10.13：darktable v5 gamut mapping 开关（1 = 标准，0 = A/B 关）
  glUniform1f(u_film_.gamut, p.gamut);
  // 预览域映射（v0.8.0）：预览先 /uDomainRatio 回 RAW 等效域再走 filmic；静拍=1。
  glUniform1f(u_film_.preview_map, still ? 0.0f : 1.0f);
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

void FrameRenderer::passOutput(int w, int h, const FilmParams& p, bool flip_y,
                               bool zebra) {
  // flip_y 仅在拍照路径使用（渲染到 fbo_cap_）；预览直接渲染到当前窗口面
  if (flip_y) glBindFramebuffer(GL_FRAMEBUFFER, fbo_cap_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_out_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, tex_work_);
  glUniform1i(u_out_.tex, 0);
  glUniform2f(u_out_.px, 1.0f / static_cast<float>(w),
              1.0f / static_cast<float>(h));
  glUniform1f(u_out_.grain_amount, p.grain_amount);
  glUniform1f(u_out_.grain_size, p.grain_size);
  glUniform1f(u_out_.time, static_cast<float>(frames_.load() % 512));
  glUniform1f(u_out_.flip_y, flip_y ? 1.0f : 0.0f);
  // 斑马纹只走预览：成片路径一律 0（提示不进成片）
  glUniform1f(u_out_.zebra, zebra ? p.zebra_thr : 0.0f);
  // v0.9.6 轻度锐化（luma USM，滚降后 OETF 前；0 = 关 = A/B 通路）
  glUniform1f(u_out_.sharpen, p.sharpen);
  // M4：display 域 LUT（同一 pass 内，不额外增加 pass；斑马纹判定排在 LUT 之后，
  // 与用户实际看到的画面一致）
  applyPendingLut();
  lut_strength_ = p.lut_strength;
  bindLut(u_out_.lut, u_out_.lut_on, u_out_.lut_strength, u_out_.lut_dmin,
          u_out_.lut_dinv, u_out_.lut_size, p.lut_strength);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

}  // namespace filmkit
