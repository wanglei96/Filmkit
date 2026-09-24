#include "FrameRenderer.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <string>

#include "../log/Logger.h"

#ifndef GL_TEXTURE_EXTERNAL_OES
#define GL_TEXTURE_EXTERNAL_OES 0x8D65
#endif

namespace filmkit {
namespace {

// M4-7 局部色调映射的低频亮度图尺寸与 knee（都是设备端标定旋钮）。
// 32×24 在 4080×3072 上等效模糊半径约 128px ≈ 3% 画面宽：再小会退化成全局曲线
// 并引入光晕，再大（如直接复用 256×96 的直方图目标）就等于逐像素亮度，提亮量会
// 跟着细节走、把暗部内部对比压平。
constexpr int kLocalW = 32, kLocalH = 24;
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
uniform float uExposure;
void main() {
    vec3 rgb = clamp(texture(uTex, vUV0).rgb, 0.0, 1.0);
    vec3 lin = pow(rgb, vec3(2.2)) * uWb * uExposure;
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
uniform float uExposure;
void main() {
    vec2 uv = vec2(vUV0.x, 1.0 - vUV0.y);
    vec3 rgb = clamp(texture(uTex, uv).rgb, 0.0, 1.0);
    vec3 lin = pow(rgb, vec3(2.2)) * uWb * uExposure;
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
uniform float uHlK;    // 高光重建强度（0 = 关，旧行为）
const float EPS = 1e-6;
// ── 高光重建（M3 §3.2 阶段 4）──────────────────────────────────────
// 传感器饱和后被截断在满阱（s() 里 raw=white → 1.0×gain），CCM 再把这种截断
// 失真按矩阵混进其它通道 —— 真机实测灯泡 R/B 先饱和、G 未饱和 → 高光偏品红。
// 这里在 CCM 之前，用未饱和通道反推辐照度（raw 归一化域 1.0 = 满阱），把饱和
// 通道替换成该辐照度对应的通道值（中性高光假设 = 最保守的估计，宁可趋白也不
// 停在截断点上）。全饱和像素没有可信参考，保持原值交给后段高光 rolloff。
vec3 hlRecon(vec3 lin) {
    vec3 thr = uGain * 0.99;
    vec3 sat = step(thr, lin);
    float nsat = sat.r + sat.g + sat.b;
    if (nsat <= 0.0 || nsat >= 3.0) return lin;
    vec3 rawv = lin / max(uGain, vec3(1e-6));
    float x = 0.0;
    if (sat.r < 0.5) x = max(x, rawv.r);
    if (sat.g < 0.5) x = max(x, rawv.g);
    if (sat.b < 0.5) x = max(x, rawv.b);
    return mix(lin, x * uGain, sat * uHlK);
}
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
    // LSC 在 hlRecon 之后、CCM 之前：饱和判定仍用未校正值（原语义不变），
    // 校正只作用在 CCM 前的线性 RGB（网格低频，去马赛克前后差异 <1%）
    vec3 rgb = uCcm * (hlRecon(vec3(r, g, b)) * lscAt(p));
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
uniform float uNS;     // 归一化域噪声 σ（SENSOR_NOISE_PROFILE 或回退估计）
uniform float uDnK;    // 值域 σ 系数：标准 0.28 / 强 2.20（v0.5.35 放轻）
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
    // v0.6.34：uDnK <= 0 = 关降噪档，只做线性化+WB、**不取任何邻居**（1 次采样，
    // 其余 8 个同色位全跳过）。这条路径与 P1 直读 RAW 整型纹理完全等价，但省掉了
    // P1 在那条路上的重复解码 —— 真机实测关降噪反而比开降噪慢 4-7 倍的原因。
    if (uDnK > 0.0) {
        // v0.5.33 实测：系数 1.1 时 σY 恰降 1/2.8≈1/3（9 点平均的理论值），权重已
        // 饱和成无条件平均；0.50 恢复选择性（强边缘 0% 损失）但画面仍偏平，0.28 更轻。
        float sr = max(uNS, 0.004) * uDnK * sqrt(0.2 + 4.0 * c0);
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
    int ch = chanAt(p);
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
uniform float uNS;
uniform float uDnK;    // 值域 σ 系数：标准 2.75 / 强 3.25（只动色度）
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
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
    float sc = max(uNS, 0.004) * uDnK * sqrt(0.2 + 4.0 * y0) + 0.008;
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

// Pass2：胶片模拟内核（M1 简化模型；M3 升级密度域完整模型，§5）。
const char* kFsFilm = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform float uContrast;
uniform vec3 uShoulder;
uniform vec3 uToe;
uniform mat3 uCross;
uniform vec3 uSat;    // 阴影/中间/高光 饱和度
uniform vec2 uSplit;  // 阴影暖 / 高光冷
uniform float uMono;
uniform float uToneK;      // 基准影调强度（0 = 恒等/旧行为，1 = 完整基准）
uniform float uLook;       // 色彩外观层强度（0 = 恒等/A-B 关，M3 P1）
uniform float uLookSat;    // 亮度保持的饱和增益
uniform float uLookChroma; // 色度 S 曲线端点增益
// M4-6 自适应影调：曝光增益由 C++ 侧算好下发 —— 开启 = 按场景白点自适应，
// 关闭 = 固定 exp2(0.35)。两条路都只经 baseTone 的线性曝光这一处，语义唯一。
uniform float uExposureGain;
// 预览直通（v0.6.48）：HAL 的 OES 预览流是**已被 ISP tone 过的 8bit 显示域**，动态范围
// 已经定死；再套 baseTone 的 S 曲线（围绕 mn=0.2184、以下压暗）会把它的暗部直接压到 0
// —— 真机实测 pass1 0.0174 → pass2 0.000295（压 59×），预览暗部死黑 11.4%（成片 0%），
// 而线性增益无法同时对齐中位与暗部（ag 0.838 中位对/暗部黑；ag 1.95 暗部对/中位过亮 195）。
// 所以预览只做 pass3 的高光滚降 + LUT，保留 HAL 自身的观感。
// 后续可换成"域映射曲线"（把 HAL 线性映射回 RAW 等效域）以恢复 WYSIWYG。
uniform float uPreviewBypass;
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
float baseTone(float x) {
    const float blk = 0.0080;   // 黑点 —— 压黑位靠它，不靠 p
    const float mn  = 0.2184;   // 中间调枢轴（线性 0.22）＝ S 曲线的**不动点**
    const float p   = 1.60;     // S 强度 —— 对比旋钮（不动点不随它移动）
    // v0.6.49：曝光增益对**高光淡出**（x ≥ kAgFadeHi 处增益退化为 1）。
    // 动机：ag 是**全局**增益，暗场景打满 3.0 时会把画面里本来就亮的部分（白柜子/
    // 白墙）一起推过滚降白点。真机实测（室内 ISO 254，ag 2.386）：n0 P90 0.399
    // ×2.386 = 0.95 > 0.80 → 显示域 244/255，用户报"柜子高光溢出"；淡出后 P90 落到
    // 0.61（显示 231）。中位（n0 0.151）与暗部完全不受影响，室外场景 P90 仅 0.58→0.53。
    const float kAgFadeLo = 0.20;
    const float kAgFadeHi = 0.50;
    float agEff = mix(uExposureGain, 1.0, smoothstep(kAgFadeLo, kAgFadeHi, x));
    float xn = clamp((x * agEff - blk) / (1.0 - blk), 0.0, 1.0);
    // 归一化到以 mn 为 0.5 的坐标：S 曲线在 t=0.5 处 s=0.5，映射回来正好是 mn
    float t = xn <= mn ? 0.5 * xn / mn : 0.5 + 0.5 * (xn - mn) / (1.0 - mn);
    float tp = pow(t, p);
    float s = tp / max(tp + pow(1.0 - t, p), 1e-6);
    // 映射回：s=0.5 处回到 mn（不动点），端点 0/1 精确取到
    return s <= 0.5 ? mn * (s / 0.5) : mn + (1.0 - mn) * ((s - 0.5) / 0.5);
}
// ±4EV 内为有效域（toe/shoulder 只在这里数值安全）；域外线性延展——
// 硬截断曾把 -4.5EV 的暗部 14% 像素钉死在同一灰度（直方图尖峰+色带）
float filmCurve(float x, float toe, float shoulder) {
    if (x < 0.0 || x > 1.0) return x; // 域外延展段：无 toe/shoulder（exp 会发散）
    x = clamp((x - 0.5) * uContrast + 0.5, 0.0, 1.0);
    float y = x;
    y -= toe * exp(-x * 6.0) * x * (1.0 - x);
    y += shoulder * exp(-(1.0 - x) * 6.0) * x * (1.0 - x);
    return clamp(y, 0.0, 1.0);
}
void main() {
    vec3 lin = texture(uTex, vUV0).rgb;
    // 预览直通：HAL 流已是成品显示域，本 pass 的影调链会把它压死（见 uPreviewBypass 注释）
    if (uPreviewBypass > 0.5) {
        oColor = vec4(lin, 1.0);
        return;
    }
    // log 域：以 0.18 中灰为轴。±4EV 有效域斜率 0.125（对比不变），
    // 域外 -6.5..-4 / 4..6.5EV 半斜率延展，更暗/更亮继续线性外推到 ~±9EV
    vec3 e = log2(max(lin, vec3(1e-6)) / 0.18);
    vec3 lg = e * 0.125 + 0.5;
    // step 参数必须同维（vec3,vec3）——step(e,-4.0) 无此重载，编译失败=全链路黑屏
    lg = mix(lg, 1.0 + (e - 4.0) * 0.0625, step(vec3(4.0), e));
    lg = mix(lg, (e + 4.0) * 0.0625, step(e, vec3(-4.0)));
    lg = uCross * lg;
    vec3 g;
    g.r = filmCurve(lg.r, uToe.r, uShoulder.r);
    g.g = filmCurve(lg.g, uToe.g, uShoulder.g);
    g.b = filmCurve(lg.b, uToe.b, uShoulder.b);
    vec3 out3 = 0.18 * exp2((g - 0.5) * 8.0);
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
    if (uToneK > 0.0) {
        float n0 = max(max(out3.r, out3.g), out3.b);
        float n1 = baseTone(n0);
        vec3 m = out3 * mix(1.0, n1 / max(n0, 1e-6), uToneK);
        // 高光去饱和：范数越过白点后色度向峰值收敛（过曝光源趋白）。
        // 必须与亮度压缩成对出现——只做等比压缩会保留暖色比例，让过曝的灯泡
        // "变红/变粉"（v0.5.37 用户反馈）；硬截断之所以看着正常，是因为它把
        // 三通道一起顶到 1。Filmic/AgX 的高光处理同样是"压缩 + 去饱和"。
        // ── v0.6.38：区间 (0.85, 1.30) → (0.40, 0.75)（对标 PhotonCamera）──────
        // 真机同机位对标（2026-09-24，LUT 关、降噪开；PC 那张也是"无 LUT"）：
        //   天空 我们 C* 19.20 / sat 0.232 / RGB 约 (167,201,241)
        //        PC   C*  8.45 / sat 0.114 / RGB 约 (215,230,245)  ← 接近白
        //   全图 P50 我们 161.9 vs PC 196.9
        // 根因：判据 n0 是**线性域**的 max 通道，而天空（B 通道最高）反推出来是
        // **n0≈0.59** —— 旧区间 (0.85, 1.30) 在实拍里 w 恒为 0，**这条去饱和从未
        // 触发过**，天空于是带着全量色度被提亮。
        // 新区间当时由反推定：n0=0.59 → w=0.577（天空 C* 预期 19.2→8.1，对齐 PC 8.45）；
        // 中间调（(120,122,125) → n0=0.177）w=0，**完全不受影响**（PC 的中间调色度
        // 比我们还高 50–90%，不能一起压）；亮白墙/亮暖色 n0≈0.55 → w=0.39，但它们
        // 本就近中性，去饱和代价小。
        // 判据用 max 通道而非 luma：蓝色天空 luma 只有 198/255 却因 B 通道高而命中，
        // 暖色亮部 luma 更高却只擦到边 —— 正好是要的方向。
        // 注意 n0 取自 baseTone **之前**，不含 uExposureGain，判据不随 ag 漂移。
        // ── v0.6.45：区间 (0.40, 0.75) → (0.32, 0.60)（成片反推实测标定）────────
        // v0.6.38 的区间**仍然几乎没生效**（用户复核："天空观感还是过饱和"）：
        // 同机位对标（filmkit_20260924-101537.jpg vs PC 哈苏 101648）显示天空
        // C* 我们 24.55/18.80（L70-80 / L80-90 段）vs PC 14.08/9.47 —— 仍是 1.7–2.0×。
        // 成片反推实测（test_temp/desat_scan.py）给出天空 **n0 中位 0.460**
        // （P10 0.397 / P90 0.599）：距起点 0.40 只有 0.06 → **w 中位仅 0.077**。
        // 根因与旧区间同型 —— **判据取值靠估算而不是实测分布**：v0.6.38 的 0.59
        // 取自上一张照片，且估算时漏了 LSC（画面上部增益 ≈1.8×，正好把 0.25 抬到 0.46）。
        // 新区间按实测分布定：w(0.46)=0.50 → 天空 C* 24.55/18.80 → **14.15/8.55**
        //（对齐 PC 14.08/9.47）；**中间调 n0 中位 0.148 → w=0，逐项复算确认不变**。
        // 代价：全图 C* 5.41→4.21（PC 6.51）—— 我们的缺口本就在中间调/暗部，
        // 不归这条去饱和管（见 decisions"暗部提色"待办）。
        float w = uToneK * smoothstep(0.32, 0.60, n0);
        // v0.6.49：**色度保护** —— max 通道判据会把"单通道高的浓色物体"误判成高光：
        // 红色水杯的 R 通道高（n0 由 R 决定）但 G/B 仍很低，**根本没有过曝**，却被
        // 整段去饱和 → 用户报"红色水杯泛白"。判据取的是色度（rel = max|cc|/luma，
        // 与外观层同式）：rel 越高说明颜色越浓、离"过曝趋白"越远。
        // 真机实测室内场景 n0 中位 0.357（远高于室外的 0.148），去饱和区间正好压在
        // 中间调上，所以这个误伤在室内尤其明显。天空 rel≈0.38 < 0.45，**不受影响**。
        float lw = luma(out3);
        float devw = max(max(abs(out3.r - lw), abs(out3.g - lw)), abs(out3.b - lw));
        float relw = devw / max(lw, 0.02);
        w *= 1.0 - smoothstep(0.45, 0.85, relw);
        m = mix(m, vec3(max(max(m.r, m.g), m.b)), w);
        out3 = m;
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
vec3 highlightRolloff(vec3 c) {
    const float knee = 0.80;  // 必须与 updateAutoGain 的 kTarget 对齐
    float l = max(max(c.r, c.g), c.b);
    if (l <= knee) return c;
    float u = (l - knee) / (1.0 - knee);              // [0, ∞)
    float lc = knee + (1.0 - knee) * (1.0 - exp(-u)); // → [knee, 1)
    vec3 cc = c * (lc / l);                           // 等比压缩，保持色相
    return mix(cc, vec3(lc), smoothstep(knee, 1.6, l));  // 过曝趋白
}
void main() {
    vec2 uv = vUV0;
    if (uFlipY > 0.5) uv.y = 1.0 - uv.y;
    vec3 c = texture(uTex, uv).rgb;
    c = highlightRolloff(c);
    c = pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2));
    // M4：display 域 3D LUT（在 OETF 之后 —— 显示域 LUT 放到线性域会错色）
    if (uLutOn > 0.5) c = mix(c, lutApply(c), uLutStrength);
    if (uGrainAmount > 0.0) {
        vec2 gp = gl_FragCoord.xy * 6.0 / max(uGrainSize, 0.25) + uTime;
        float l = dot(c, vec3(0.299, 0.587, 0.114));
        float w = 4.0 * l * (1.0 - l);
        c += (vnoise(gp) - 0.5) * uGrainAmount * w;
    }
    c += (hash(gl_FragCoord.xy) - 0.5) / 255.0;
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
void main() {
    vec3 c = pow(clamp(texture(uTex, vUV0).rgb, 0.0, 1.0), vec3(1.0 / 2.2));
    if (uLutOn > 0.5) c = mix(c, lutApply(c), uLutStrength);
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
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
  if (!vs_rot_ || !vs_plain_ || !fs_oes || !fs_still || !fs_film || !fs_out ||
      !fs_fill || !fs_raw || !fs_rcd_dir || !fs_rcd_g || !fs_rcd_chroma ||
      !fs_raw_dn || !fs_chroma_dn || !fs_hist || !fs_local)
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
  if (!prog_oes_ || !prog_still_ || !prog_film_ || !prog_out_ || !prog_fill_ ||
      !prog_raw_ || !prog_rcd_dir_ || !prog_rcd_g_ || !prog_rcd_chroma_ ||
      !prog_raw_dn_ || !prog_chroma_dn_ || !prog_hist_ || !prog_local_)
    return false;

  u_oes_.st = glGetUniformLocation(prog_oes_, "uST");
  u_oes_.rot = glGetUniformLocation(prog_oes_, "uRot");
  u_oes_.scale = glGetUniformLocation(prog_oes_, "uScale");
  u_oes_.tex = glGetUniformLocation(prog_oes_, "uTex");
  u_oes_.wb = glGetUniformLocation(prog_oes_, "uWb");
  u_oes_.exposure = glGetUniformLocation(prog_oes_, "uExposure");

  u_still_.st = glGetUniformLocation(prog_still_, "uST");
  u_still_.rot = glGetUniformLocation(prog_still_, "uRot");
  u_still_.scale = glGetUniformLocation(prog_still_, "uScale");
  u_still_.tex = glGetUniformLocation(prog_still_, "uTex");
  u_still_.wb = glGetUniformLocation(prog_still_, "uWb");
  u_still_.exposure = glGetUniformLocation(prog_still_, "uExposure");
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
  u_rcd_chroma_.hl_k = glGetUniformLocation(prog_rcd_chroma_, "uHlK");
  u_rcd_chroma_.lsc = glGetUniformLocation(prog_rcd_chroma_, "uLsc");
  u_rcd_chroma_.lsc_size = glGetUniformLocation(prog_rcd_chroma_, "uLscSize");

  u_raw_dn_.tex = glGetUniformLocation(prog_raw_dn_, "uRaw");
  u_raw_dn_.size = glGetUniformLocation(prog_raw_dn_, "uSize");
  u_raw_dn_.cfalut = glGetUniformLocation(prog_raw_dn_, "uCfaLut");
  u_raw_dn_.black = glGetUniformLocation(prog_raw_dn_, "uBlack");
  u_raw_dn_.white = glGetUniformLocation(prog_raw_dn_, "uWhite");
  u_raw_dn_.gain = glGetUniformLocation(prog_raw_dn_, "uGain");
  u_raw_dn_.ns = glGetUniformLocation(prog_raw_dn_, "uNS");
  u_raw_dn_.k = glGetUniformLocation(prog_raw_dn_, "uDnK");

  u_chroma_dn_.tex = glGetUniformLocation(prog_chroma_dn_, "uTex");
  u_chroma_dn_.size = glGetUniformLocation(prog_chroma_dn_, "uSize");
  u_chroma_dn_.ns = glGetUniformLocation(prog_chroma_dn_, "uNS");
  u_chroma_dn_.k = glGetUniformLocation(prog_chroma_dn_, "uDnK");
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
  u_film_.preview_bypass = glGetUniformLocation(prog_film_, "uPreviewBypass");
  u_film_.contrast = glGetUniformLocation(prog_film_, "uContrast");
  u_film_.shoulder = glGetUniformLocation(prog_film_, "uShoulder");
  u_film_.toe = glGetUniformLocation(prog_film_, "uToe");
  u_film_.cross = glGetUniformLocation(prog_film_, "uCross");
  u_film_.sat = glGetUniformLocation(prog_film_, "uSat");
  u_film_.split = glGetUniformLocation(prog_film_, "uSplit");
  u_film_.mono = glGetUniformLocation(prog_film_, "uMono");
  u_film_.tone_k = glGetUniformLocation(prog_film_, "uToneK");
  u_film_.look = glGetUniformLocation(prog_film_, "uLook");
  u_film_.look_sat = glGetUniformLocation(prog_film_, "uLookSat");
  u_film_.look_chroma = glGetUniformLocation(prog_film_, "uLookChroma");
  u_film_.exposure_gain = glGetUniformLocation(prog_film_, "uExposureGain");
  u_film_.local = glGetUniformLocation(prog_film_, "uLocal");
  u_film_.local_strength = glGetUniformLocation(prog_film_, "uLocalStrength");
  u_film_.local_knee = glGetUniformLocation(prog_film_, "uLocalKnee");
  u_film_.local_knee_hi = glGetUniformLocation(prog_film_, "uLocalKneeHi");
  u_film_.local_ref = glGetUniformLocation(prog_film_, "uLocalRef");
  u_film_.local_size = glGetUniformLocation(prog_film_, "uLocalSize");

  u_out_.tex = glGetUniformLocation(prog_out_, "uTex");
  u_out_.px = glGetUniformLocation(prog_out_, "uPx");
  u_out_.grain_amount = glGetUniformLocation(prog_out_, "uGrainAmount");
  u_out_.grain_size = glGetUniformLocation(prog_out_, "uGrainSize");
  u_out_.time = glGetUniformLocation(prog_out_, "uTime");
  u_out_.flip_y = glGetUniformLocation(prog_out_, "uFlipY");
  u_out_.zebra = glGetUniformLocation(prog_out_, "uZebra");
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
  glUniform1f(u_oes_.exposure, std::exp2(p.exposure_ev));
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
  glUniform1f(u_still_.exposure, std::exp2(p.exposure_ev));
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
  (void)p;  // WB/曝光已在 RAW 线性域由 uGain 承担；胶片链在 pass2
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
                                   float ns, float k) {
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
  glUniform1f(u_raw_dn_.ns, ns);
  glUniform1f(u_raw_dn_.k, k);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// P4：色度平滑（读 tex_scene_ → tex_dn_）。输出几何与 passRcdChroma 一致
void FrameRenderer::passChromaDenoise(int dst_w, int dst_h, int rot,
                                      float ns, float k) {
  (void)rot;  // kVertRot 旋转已在顶点层；frag 用 gl_FragCoord 定位
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_dn_);
  glViewport(0, 0, dst_w, dst_h);
  glUseProgram(prog_chroma_dn_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, tex_scene_);
  glUniform1i(u_chroma_dn_.tex, 0);
  glUniform2i(u_chroma_dn_.size, dst_w, dst_h);
  glUniform1f(u_chroma_dn_.ns, ns);
  glUniform1f(u_chroma_dn_.k, k);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glActiveTexture(GL_TEXTURE0);
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
                                  const float* ccm, bool mosaic, float hl_k) {
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
  glUniform1f(u_rcd_chroma_.hl_k, hl_k);
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
                              int noise_level, float noise_sigma,
                              std::vector<uint8_t>* out_rgba) {
  if (w <= 0 || h <= 0 || !raw || !black_phase) return false;
  frames_.fetch_add(1, std::memory_order_relaxed);
  // 分段计时（M3 §3.3 出口标准的度量基础）：上传 / pass 链 / 读回
  const auto t0 = std::chrono::steady_clock::now();

  if (raw_w_ != w || raw_h_ != h) {
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

  glBindTexture(GL_TEXTURE_2D, raw_tex_);
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
  const auto t1 = std::chrono::steady_clock::now();

  // WB 增益 = asShotNeutral 的倒数，G 归一；非法值回中性（防全 0/负数防呆）
  float gains[3] = {1.0f, 1.0f, 1.0f};
  if (neutral_r > 1e-6f && neutral_g > 1e-6f && neutral_b > 1e-6f) {
    const float gr = neutral_g / neutral_r;
    const float gb = neutral_g / neutral_b;
    if (gr > 0.05f && gr < 20.0f && gb > 0.05f && gb < 20.0f) {
      gains[0] = gr;
      gains[2] = gb;
    }
  }
  // CCM 行主序 sensor→sRGB；缺失=单位阵
  float ccm[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
  if (ccm9) {
    for (int i = 0; i < 9; ++i) ccm[i] = ccm9[i];
  }

  // LSC 增益图（M3 P0 镜头阴影校正）：Java 侧把 HAL 的 4 平面（R/Gr/Gb/B）折成
  // RGB 交错；无数据或开关关 → 1×1 恒等（shader 里等价于乘 1，零分支）。
  // 每次拍照重传（17×13×3 float ≈ 2.6KB，可忽略）。
  const bool lsc_ok = lsc != nullptr && lsc_cols > 1 && lsc_rows > 1;
  const int lsc_w = lsc_ok ? lsc_cols : 1;
  const int lsc_h = lsc_ok ? lsc_rows : 1;
  if (lsc_tex_ == 0 || lsc_w_ != lsc_w || lsc_h_ != lsc_h) {
    if (lsc_tex_ == 0) glGenTextures(1, &lsc_tex_);
    glBindTexture(GL_TEXTURE_2D, lsc_tex_);
    // NEAREST：双线性在 shader 内手写（X300 上 float 纹理过滤不可依赖，见 makeTex）
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB16F, lsc_w, lsc_h, 0, GL_RGB,
                 GL_FLOAT, nullptr);
    lsc_w_ = lsc_w;
    lsc_h_ = lsc_h;
  }
  static const float kLscIdent[3] = {1.0f, 1.0f, 1.0f};
  glBindTexture(GL_TEXTURE_2D, lsc_tex_);
  glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, lsc_w, lsc_h, GL_RGB, GL_FLOAT,
                  lsc_ok ? lsc : kLscIdent);

  // 实际渲染旋转 = 界面方向 + 传感器旋转（与 submitStill 同一约定）
  const int effective_rot = (rotation + 1) % 4;
  const bool swap_wh = (effective_rot % 2) == 1;
  const int dst_w = swap_wh ? h : w;
  const int dst_h = swap_wh ? w : h;
  if (!ensureTargets(dst_w, dst_h)) return false;
  while (glGetError() != GL_NO_ERROR) {
  }

  // RCD 质量档：三段中间计时（各插一次 glFinish，拍照单张路径可接受），
  // 真机据此定位 pass 瓶颈（M3-1 ≤900ms 出口标准的分解度量）。
  // M3-2 降噪：P0 RAW 域 bilateral（mosaic 模式供 P1–P3）+ P4 色度平滑；
  // 仅 RCD 路径生效，bilinear 保持纯净基线。
  const bool rcd = demosaic == 1;
  const bool dn = rcd && noise_level > 0;
  // v0.5.34 重标定（v0.5.33 真机实测：两档无差别、细节 -59%、JPEG 体积腰斩 =
  // P0 值域权重饱和退化成 9 点无条件平均）。Laplacian 损失全部来自 P0（P4 保 Y
  // 不动），故 P0 收温和保细节、P4 保持较强只压色度 —— 两档这才拉开真实差距。
  const bool dn_high = noise_level >= 2;
  // v0.5.35：标准档仍偏"平"（Lap 3.11 vs 关 5.89、体积 -36%）——用户要更清晰、
  // 体积别掉太多。P0 是细节与体积的主因（亮度高频），再放轻 0.50→0.28；彩噪改由
  // P4 补强 1.80→2.20（P4 只动色度、保 Y，不影响亮度细节与体积大头）。
  const float sr_k = dn_high ? 2.20f : 0.28f;  // P0 值域 σ 系数
  // v0.6.19：自适应提亮 ~1.1EV 把噪声一起放大（真机同场景 A/B：亮度 σ +21%、
  // **色度 σ +84%**），故 P4 色度系数再补强约 25%。P4 只动色度、保 Y 不动，
  // 加强它不影响亮度细节与体积（P0 的 sr_k 刻意不放，避免动到细节大头）。
  const float sc_k = dn_high ? 3.25f : 2.75f;  // P4 值域 σ 系数
  // v0.6.32：回退 0.015 → 0.012。σc 的降噪职责已交给半径（见 kFsChromaDenoise），
  // 值域阈值只负责保住真实色度边缘 —— 所以它不再需要"暗中当降噪量用"。
  const float ns_raw = noise_sigma > 0.0f ? noise_sigma : 0.012f;  // 回退估计
  // 防呆（修好 SENSOR_NOISE_PROFILE 解包后 HAL 值可能首次生效）：HAL 与回退值同处
  // 物理量级，夹紧避免异常值让降噪失效或过猛。
  // v0.6.30：下限 0.008 → 0.004、回退 0.030 → 0.008 —— 旧值比 DNG 实测（ISO 50–767
  // 的 RAW σ 仅 0.0013–0.0033）高 4~10 倍，P4 的 σc 正比于它，于是**真实色度被当
  // 噪声抹掉**（真机同机位对照：暗部 C*/L* 只有 PhotonCamera 的 54%，而同场景 libraw
  // 原始数据的暗部色度完好，rel 0.285 vs 我们 0.093）。
  const float ns = std::min(std::max(ns_raw, 0.004f), 0.040f);
  float dir_ms = 0.0f, g_ms = 0.0f, dn_ms = 0.0f;
  if (rcd) {
    if (!ensureRcdTargets(w, h)) return false;
    const auto d0 = std::chrono::steady_clock::now();
    // ── v0.6.34：P0 **无条件执行**（关降噪时 k=0 = 只做线性化+WB，零邻域操作）──
    // 起因（真机 v0.6.33 日志）：关降噪反而慢得多 —— dirMs 232–244ms（开降噪
    // 34–60ms）、passMs 484–700（开 225–487）。因为 dn=false 时 P1 直接读 R16UI
    // 整型 RAW 纹理、自己解码黑阶/WB，比读 P0 的 float mosaic 慢 4–7 倍。
    // 安全性：P0 的 raw0() 与 P1 的 s() RAW 分支**逐字相同**（黑阶相位、白阶归一、
    // WB 增益都同一式），所以 k=0 的 P0 与"P1 直读 RAW"数学等价 —— 关降噪的成片
    // 只差一次 RGBA16F 量化（开降噪档本来就走这条路），**零细节代价**（无邻域采样）。
    // 故下面三处 mosaic 标志恒为 true。
    passRawDenoise(w, h, cfa, black_phase, static_cast<float>(white_level), gains,
                   ns, dn ? sr_k : 0.0f);
    passRcdDir(w, h, cfa, black_phase, static_cast<float>(white_level), gains,
               true);
    glFinish();
    const auto d1 = std::chrono::steady_clock::now();
    passRcdG(w, h, cfa, black_phase, static_cast<float>(white_level), gains, true);
    glFinish();
    const auto d2 = std::chrono::steady_clock::now();
    dir_ms = std::chrono::duration<float, std::milli>(d1 - d0).count();
    g_ms = std::chrono::duration<float, std::milli>(d2 - d1).count();
    passRcdChroma(dst_w, dst_h, effective_rot, cfa, black_phase,
                  static_cast<float>(white_level), gains, ccm, true, p.hl_recon);
  } else {
    passRaw(dst_w, dst_h, effective_rot, cfa, black_phase,
            static_cast<float>(white_level), gains, ccm, p);
  }
  if (dn) {
    if (!ensureDnTarget(dst_w, dst_h)) return false;
    const auto n0 = std::chrono::steady_clock::now();
    passChromaDenoise(dst_w, dst_h, effective_rot, ns, sc_k);
    glFinish();
    dn_ms = std::chrono::duration<float, std::milli>(
                std::chrono::steady_clock::now() - n0)
                .count();
  }
  passFilm(dst_w, dst_h, p, dn ? tex_dn_ : tex_scene_, /*still=*/true);
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
      std::string("{\"uploadMs\":") + std::to_string(ms(t0, t1)).substr(0, 6) +
      ",\"passMs\":" + std::to_string(ms(t1, t2)).substr(0, 6) +
      ",\"totalMs\":" + std::to_string(ms(t0, t3)).substr(0, 7) +
      ",\"w\":" + std::to_string(w) + ",\"h\":" + std::to_string(h) +
      ",\"demosaic\":\"" + (rcd ? "rcd" : "bilinear") + "\"";
  if (rcd) {
    kv += ",\"dirMs\":" + std::to_string(dir_ms).substr(0, 6) +
          ",\"gMs\":" + std::to_string(g_ms).substr(0, 6);
  }
  if (dn) {
    kv += std::string(",\"dn\":\"") + (dn_high ? "high" : "std") +
          "\",\"srK\":" + std::to_string(sr_k).substr(0, 4) +
          ",\"scK\":" + std::to_string(sc_k).substr(0, 4) +
          ",\"ns\":" + std::to_string(ns).substr(0, 5) +
          ",\"nsHal\":" + std::to_string(ns_raw).substr(0, 6) +
          ",\"dnMs\":" + std::to_string(dn_ms).substr(0, 6);
  }
  kv += ",\"tone\":" + std::to_string(p.tone_strength).substr(0, 3);
  kv += ",\"ag\":" + std::to_string(film_gain_).substr(0, 5);
  // 判据输入（提亮前场景中位，线性）—— 与 preview.ag 的 linMid 对比可判定
  // "预览与静拍的统计源是否同一标定"。见 updateAutoGain 的 last_lin_mid_。
  kv += ",\"linMid\":" + std::to_string(last_lin_mid_).substr(0, 6);
  kv += ",\"auto\":" + std::to_string(p.auto_tone > 0.5 ? 1 : 0);
  kv += ",\"lt\":" + std::to_string(p.local_tone).substr(0, 4);
  // LSC：网格尺寸（"off" = 恒等/A-B 关闭档）
  kv += std::string(",\"lsc\":\"") +
        (lsc_ok ? std::to_string(lsc_cols) + "x" + std::to_string(lsc_rows)
                : "off") +
        "\"";
  if (rcd) kv += ",\"hl\":" + std::to_string(p.hl_recon).substr(0, 3);
  kv += "}";
  Logger::instance().log(LogLevel::kInfo, "engine", "raw.render", kv);
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
                              float lut_strength, bool box_filter) {
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
    bindLut(u_hist_.lut, u_hist_.lut_on, u_hist_.lut_strength, u_hist_.lut_dmin,
            u_hist_.lut_dinv, u_hist_.lut_size, lut_strength);
  }
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// M4-6：统计源参数化 —— 自适应影调必须对**场景纹理**（baseTone 之前）统计，
// 而 UI 直方图仍看 film 输出（tex_work_），两者共用 kFsHist 同一实现。
// v0.6.24：加 bypass_lut —— 风格 LUT 不该改曝光（默认参数下行为与旧版逐位一致）。
bool FrameRenderer::histogramFrom(GLuint src, int* out, int n, bool bypass_lut) {
  if (out == nullptr || n < 2 || src == 0 || scene_w_ <= 0) return false;
  const int hw = 256, hh = 96;
  if (tex_hist_ == 0) {
    makeTex(&tex_hist_, &fbo_hist_, GL_RGBA8, hw, hh);
    hist_w_ = hw;
    hist_h_ = hh;
  }
  renderLuma(src, fbo_hist_, hw, hh, bypass_lut ? 0.0f : lut_strength_, false);
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
// kTarget 0.80 必须与 kFsFilm 的 knee 一致（v0.6.19 决策）：白点落在滚降起点。
// 限幅仍只允许提亮（≥1.0）—— 压暗用户设定的曝光不在引擎职责内。
float FrameRenderer::updateAutoGain(GLuint src, bool still) {
  constexpr int kN = 64;
  // 场景**中位**（中间调）的目标亮度（线性）—— 不是白点，见下方判据注释。
  // v0.6.46：0.38 → **0.26**（两场景成片反推实测标定）。v0.6.38 提到 0.38 的依据是
  // "PC 全图 P50 196.9 而我们 161.9"，但那是**另一个场景**、且当时去饱和区间还没修
  // （天空带着全量色度，P50 不可比）。2026-09-24 同机位双场景重新反推（test_temp/ag_scan.py）：
  //   场景 A 室外(ag 1.576)：lin_mid 0.218，实测 P50 200.7 vs PC 181.7 → **过亮 19**，
  //                          匹配 PC 只需 ag **1.2** ⇒ kTarget = 1.2×0.218 ≈ 0.26
  //   场景 B 阳台逆光(ag 1.900 打满)：lin_mid 0.069，实测 102.8 vs PC 145.4 → **欠曝 43**，
  //                          匹配 PC 需 ag **3.0**
  // 两个场景都指向 kTarget≈0.26（B 场景 3.0×0.069=0.21，A 场景 0.26，取上限侧）——
  // 0.38 在亮场景给多（ag 1.576 > 1.2）、在暗场景又因上限不够给不上。
  constexpr float kTarget = 0.26f;
  // 上限 4.0（v0.6.18）→ 2.0（v0.6.31）→ 1.5（v0.6.33）→ 1.6（v0.6.35）→ 1.9（v0.6.38）
  // → **3.0**（v0.6.46）。v0.6.31 收上限的理由是"4.0 把窗口场景的福字推进 rolloff
  // 去饱和区（粉白）且噪声 ×4"——但那是**去饱和区间还没下移**时的结论。实测 B 场景
  // （lin_mid 0.069，逆光阳台）要匹配 PC 亮度需要 ag 3.0，1.9 把它锁死在欠曝 43/255。
  // ⚠️ 待真机复核：暗场景提亮 3× 的噪声，以及亮红色物体是否被新去饱和区间吃掉。
  constexpr float kMax = 3.0f;
  const float fallback = auto_gain_ > 0.0f ? auto_gain_ : 1.0f;
  int hist[kN];
  // 旁路 LUT：风格层不该改曝光（判据本身见上方注释）
  if (!histogramFrom(src, hist, kN, /*bypass_lut=*/true)) return fallback;
  long total = 0;
  for (int i = 0; i < kN; ++i) total += hist[i];
  if (total <= 0) return fallback;
  const auto binAt = [&](double q) {
    const long want = static_cast<long>(static_cast<double>(total) * q);
    long acc = 0;
    for (int i = 0; i < kN; ++i) {
      acc += hist[i];
      if (acc >= want) return i;
    }
    return kN - 1;
  };
  // 判据：**中间调锚定**（场景中位 → kTarget），不是高光判据。
  // ── v0.6.36 把 v0.6.35 的 P99 换回中位（真机"室外又灰了、室内反而亮"）──
  // v0.6.35 用 P99 的动机是"曝光正确性看高光"，但那**丢掉了本功能真正的职责**：
  // 提亮中间调。C* 随 L* 上升，所以"提中间调"是**为了色彩**，不是为了曝光。改成
  // P99 后室外（有天空/高光）落到 gain 1.0–1.12 → 中间调不动 → 色彩回淡；实测
  // 165347 的 P90 线性 0.745（已进 rolloff 去饱和区）而参照只有 0.19 —— 那就是"灰"。
  // v0.6.32 的中位锚定被否掉，是因为**目标值 0.26 的推导错了**（拿 PhotonCamera
  // **成片**、已提亮的显示域中位 139/255 折回线性，当成**提亮前**的目标），**不是
  // 因为方向错**——两者本该分开判断，我上轮把它们一起毙了。
  // 现在目标值有独立依据：真机 v0.6.34 实测"全场景 1.5 时室内偏暗一丢丢，1.6 比较
  // 好"。所以保留 0.26 让绝大多数场景打满，把**上限**当作真正的控制量（那是用户
  // 能直接感知、也直接说出口的数）。很亮的场景仍会自动少提：中位线性 > 0.26/1.6
  // = 0.163（显示域 >116）时开始降。
  const float lin_mid = std::pow(static_cast<float>(binAt(0.50)) / (kN - 1), 2.2f);
  // v0.6.47：**预览允许压暗**（下限 0.35），静拍仍只提亮（下限 1.0）。
  // 真机实测（2026-09-24，同场景同曝光，pvSec/stSec 完全相同）—— 预览的统计源是
  // HAL 的 OES 预览流，**已被 ISP tone 过**，其线性中位是 RAW 链的 1.7–3.1 倍：
  //   室外   预览 linMid 0.4100 / 静拍 0.2410（1.70×）
  //   阳台逆光 预览 linMid 0.2744 / 静拍 0.0891（3.08×）
  // 中位锚定对"已经偏亮"的输入只能算出 ag<1，却被下限卡在 1.0 → HAL 的提亮与
  // baseTone 的 S 曲线叠加 → 用户报"预览过曝、成片正常"。截图实测显示域中位
  // **225/255（预览）vs 161/255（成片）**。放开下限后两条路径的显示中位都落在
  // kTarget：预览 0.26/0.410=0.63、成片 0.26/0.241=1.08，输出一致。
  // ⚠️ 只是**线性**增益:ISP tone 是非线性的(暗部提得多),中位对齐后分布形状仍有差,
  // 要完全一致需按域映射曲线校正(kFsOes 侧),留待真机复核后再定。
  const float kMinGain = still ? 1.0f : 0.35f;
  const float gain = std::clamp(kTarget / std::max(lin_mid, 1e-4f), kMinGain, kMax);
  last_lin_mid_ = lin_mid;  // 供 preview.ag / raw.render 观测（排查预览 vs 成片判据差异）
  // EMA 平滑：**静拍与首帧直通** —— 静拍只有这一帧可渲染，用历史值等于用错曝光。
  // 真机实测（v0.6.28）：同一个窗口场景，10:25:09 的静拍拿到 ag=3.042（取景早期
  // 暗场景的滞后值），2 秒后预览已收敛到 1.164 —— 福字因此被推进 rolloff 的去饱和
  // 区，L* 79 / C* 25 变成粉白，而正确增益（≈1.5）下应是 L* 57 的鲜红。
  // 静拍不写回 auto_gain_，以免污染预览的平滑状态。
  if (still) return gain;
  if (auto_gain_ <= 0.0f) {
    auto_gain_ = gain;
  } else {
    auto_gain_ = auto_gain_ * 0.7f + gain * 0.3f;
  }
  return auto_gain_;
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
  // 自适应影调（M4-6）：按场景中位亮度算线性曝光增益。**刻意放在 passFilm 内** ——
  // 预览 / YUV 静拍 / RAW 静拍三条路径都经过这里，一处实现即全覆盖。
  // 关闭（auto_tone ≤ 0.5）时退回固定 ev 0.35，与 v0.6.17 行为逐位一致（A/B 通路）。
  // 注意：updateAutoGain 内部会切 FBO/viewport，必须先于下面的绑定调用。
  const float gain =
      p.auto_tone > 0.5f ? updateAutoGain(src_tex, still) : std::exp2(0.35f);
  film_gain_ = gain;  // 本帧实际增益（raw.render 记 ag —— 自适应影调的唯一可观测点）
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
  glBindTexture(GL_TEXTURE_2D, src_tex);
  glUniform1i(u_film_.tex, 0);
  glUniform1f(u_film_.exposure_gain, gain);
  // 预览直通（v0.6.48）：HAL 流是成品显示域，本 pass 的影调链会压死它的暗部 ——
  // 只保留 pass3 的高光滚降 + LUT。静拍不受影响（仍走完整影调链）。
  glUniform1f(u_film_.preview_bypass, still ? 0.0f : 1.0f);
  glUniform1f(u_film_.contrast, p.contrast);
  glUniform3f(u_film_.shoulder, p.shoulder[0], p.shoulder[1], p.shoulder[2]);
  glUniform3f(u_film_.toe, p.toe[0], p.toe[1], p.toe[2]);
  glUniformMatrix3fv(u_film_.cross, 1, GL_FALSE, p.cross);
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
  // 预览 ag 观测（排查"预览过曝 vs 成片正常"）：每 120 帧一条，与 raw.render 的
  // ag/linMid 直接对比 —— 若同场景下预览的 linMid 明显偏小（ag 偏大），说明 OES
  // 预览流与 RAW/YUV 链的线性亮度标定不一致（两条源的判据不同）。
  if (!still && ++ag_log_tick_ >= 120) {
    ag_log_tick_ = 0;
    Logger::instance().log(
        LogLevel::kInfo, "engine", "preview.ag",
        "{\"ag\":" + std::to_string(gain).substr(0, 5) +
            ",\"linMid\":" + std::to_string(last_lin_mid_).substr(0, 6) +
            ",\"localRef\":" + std::to_string(local_ref).substr(0, 5) +
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
  // M4：display 域 LUT（同一 pass 内，不额外增加 pass；斑马纹判定排在 LUT 之后，
  // 与用户实际看到的画面一致）
  applyPendingLut();
  lut_strength_ = p.lut_strength;
  bindLut(u_out_.lut, u_out_.lut_on, u_out_.lut_strength, u_out_.lut_dmin,
          u_out_.lut_dinv, u_out_.lut_size, p.lut_strength);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

}  // namespace filmkit
