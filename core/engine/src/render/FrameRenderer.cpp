#include "FrameRenderer.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <string>

#include "../log/Logger.h"
#include "Align.h"

#ifndef GL_TEXTURE_EXTERNAL_OES
#define GL_TEXTURE_EXTERNAL_OES 0x8D65
#endif

namespace filmkit {
namespace {

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
uniform ivec2 uSize;
uniform vec2 uTiles;
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
ivec2 evenNear(vec2 o) {
    // 最近偶数：round 到 2 的倍数，保证采样点与参考帧同 Bayer 相位
    return ivec2(floor(o.x * 0.5 + 0.5) * 2.0, floor(o.y * 0.5 + 0.5) * 2.0);
}
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float acc = linAt(uRaw0, p);
    float wsum = 1.0;
    vec2 uv = (vec2(p) + 0.5) / (uTiles * 64.0);
    vec3 f1 = off1At(uv);
    float w1 = f1.z;
    acc += w1 * linAt(uRaw1, p + evenNear(f1.xy));
    wsum += w1;
    vec3 f2 = off2At(uv);
    float w2 = f2.z;
    acc += w2 * linAt(uRaw2, p + evenNear(f2.xy));
    wsum += w2;
    vec3 f3 = off3At(uv);
    float w3 = f3.z;
    acc += w3 * linAt(uRaw3, p + evenNear(f3.xy));
    wsum += w3;
    float v = acc / max(wsum, 1e-4);
    oColor = vec4(v, v, v, 1.0);
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
uniform float uNS;
uniform float uDnK;
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
    if (uDnK > 0.0) {
        float sr = max(uNS, 0.004) * uDnK * sqrt(0.2 + 4.0 * c0);
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
    int ch = chanAt(p);
    oColor = vec4(ch == 0 ? v : 0.0, ch == 1 ? v : 0.0, ch == 2 ? v : 0.0, 1.0);
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
// v0.7.8：用户 EV 滑杆 = **显示亮度补偿**，乘在整条影调链**之后**。
// 为什么不放在 RAW 链（v0.7.7 曾接在 CCM 后）：那里在 updateFilmicEV 的场景统计
// **之前** —— auto 黑点（P0.5 分位）会跟着输入一起漂，filmic 又把它压回来，
// 用户拉 ±1EV 成片纹丝不动（v0.7.7 真机 A/B 实测：+0.7EV 两张 P50 差 1.93/255）。
// 放在链后：统计与曲线都不受扰动，输出严格 ×2^EV，滑杆多少补多少；
// 提亮逼近显示上限时由 pass3 的输出域 rolloff 兜高光。
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
void main() {
    vec3 lin = texture(uTex, vUV0).rgb;
    // 预览域映射（v0.8.0，取代直通）：先除以在线估计的 HAL/RAW 域比，映射回 RAW
    // 等效域，再走与成片完全相同的 filmic 链 —— 真 WYSIWYG；EV 在统计之后乘（v0.7.9）。
    // filmicTone 输出 pow(clamp(spline), power) ∈ [0,1]，数学有界 —— 预览不再可能爆白。
    if (uPreviewMap > 0.5) {
        vec3 mapped = max(lin, vec3(0.0)) / uDomainRatio * uExposure;
        oColor = vec4(filmicTone(mapped), 1.0);
        return;
    }
    // v0.7.9：旧 log 域胶片链（log2 编码 / filmCurve / 域外延展 / 0.18·exp2 重构）已删，
    // filmic 直接吃线性场景输入 —— blackEv/whiteEv 的标定域与渲染域首次严格一致。
    vec3 out3 = max(lin, vec3(0.0));
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
        out3 = mix(out3, filmicTone(out3), uToneK);
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
    oColor = vec4(out3 * uExposure, 1.0);
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
// v0.7.3：统计通道 —— 0 = luma（UI 直方图，用户看的亮度分布）；
// 1 = max 通道（filmic 自动 EV 的判据，见 measureSceneStats）。
uniform float uStatCh;
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
    // max 与 luma 都在显示域；pow 单调 ⇒ CPU 侧 pow(·, 2.2) 折回线性后仍是 max 通道
    float l = (uStatCh > 0.5) ? max(max(c.r, c.g), c.b)
                              : dot(c, vec3(0.2126, 0.7152, 0.0722));
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
  const GLuint fs_mfnr_pyr = compileShader(GL_FRAGMENT_SHADER, kFsMfnrPyr);
  const GLuint fs_mfnr_down = compileShader(GL_FRAGMENT_SHADER, kFsMfnrDown);
  const GLuint fs_mfnr_fuse = compileShader(GL_FRAGMENT_SHADER, kFsMfnrFuse);
  const GLuint fs_raw_dn_f = compileShader(GL_FRAGMENT_SHADER, kFsRawDenoiseFused);
  if (!vs_rot_ || !vs_plain_ || !fs_oes || !fs_still || !fs_film || !fs_out ||
      !fs_fill || !fs_raw || !fs_rcd_dir || !fs_rcd_g || !fs_rcd_chroma ||
      !fs_raw_dn || !fs_chroma_dn || !fs_hist || !fs_local ||
      !fs_mfnr_pyr || !fs_mfnr_down || !fs_mfnr_fuse || !fs_raw_dn_f)
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
  prog_mfnr_pyr_ = linkProgram(vs_plain_, fs_mfnr_pyr);
  prog_mfnr_down_ = linkProgram(vs_plain_, fs_mfnr_down);
  prog_mfnr_fuse_ = linkProgram(vs_plain_, fs_mfnr_fuse);
  prog_raw_dn_fused_ = linkProgram(vs_plain_, fs_raw_dn_f);
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
  glDeleteShader(fs_mfnr_pyr);
  glDeleteShader(fs_mfnr_down);
  glDeleteShader(fs_mfnr_fuse);
  glDeleteShader(fs_raw_dn_f);
  if (!prog_oes_ || !prog_still_ || !prog_film_ || !prog_out_ || !prog_fill_ ||
      !prog_raw_ || !prog_rcd_dir_ || !prog_rcd_g_ || !prog_rcd_chroma_ ||
      !prog_raw_dn_ || !prog_chroma_dn_ || !prog_hist_ || !prog_local_ ||
      !prog_mfnr_pyr_ || !prog_mfnr_down_ || !prog_mfnr_fuse_ ||
      !prog_raw_dn_fused_)
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

  u_raw_dn_f_.tex = glGetUniformLocation(prog_raw_dn_fused_, "uTex");
  u_raw_dn_f_.size = glGetUniformLocation(prog_raw_dn_fused_, "uSize");
  u_raw_dn_f_.cfalut = glGetUniformLocation(prog_raw_dn_fused_, "uCfaLut");
  u_raw_dn_f_.ns = glGetUniformLocation(prog_raw_dn_fused_, "uNS");
  u_raw_dn_f_.k = glGetUniformLocation(prog_raw_dn_fused_, "uDnK");

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
  u_mfnr_fuse_.size = glGetUniformLocation(prog_mfnr_fuse_, "uSize");
  u_mfnr_fuse_.tiles = glGetUniformLocation(prog_mfnr_fuse_, "uTiles");
  u_mfnr_fuse_.cfalut = glGetUniformLocation(prog_mfnr_fuse_, "uCfaLut");
  u_mfnr_fuse_.black = glGetUniformLocation(prog_mfnr_fuse_, "uBlack");
  u_mfnr_fuse_.white = glGetUniformLocation(prog_mfnr_fuse_, "uWhite");
  u_mfnr_fuse_.gain = glGetUniformLocation(prog_mfnr_fuse_, "uGain");

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
  u_film_.filmic_power = glGetUniformLocation(prog_film_, "uFilmicPower");
  u_film_.filmic_black_disp = glGetUniformLocation(prog_film_, "uFilmicBlackDisp");
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
  u_hist_.stat_ch = glGetUniformLocation(prog_hist_, "uStatCh");
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
  if (prog_mfnr_pyr_) glDeleteProgram(prog_mfnr_pyr_);
  if (prog_mfnr_down_) glDeleteProgram(prog_mfnr_down_);
  if (prog_mfnr_fuse_) glDeleteProgram(prog_mfnr_fuse_);
  if (prog_raw_dn_fused_) glDeleteProgram(prog_raw_dn_fused_);
  for (auto& t : tex_mfnr_) {
    if (t) glDeleteTextures(1, &t);
    t = 0;
  }
  for (auto& t : tex_off_) {
    if (t) glDeleteTextures(1, &t);
    t = 0;
  }
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
  prog_mfnr_pyr_ = prog_mfnr_down_ = prog_mfnr_fuse_ = prog_raw_dn_fused_ = 0;
  tex_pyr1_ = tex_pyr2_ = tex_pyr3_ = tex_pyr4_ = 0;
  fbo_pyr1_ = fbo_pyr2_ = fbo_pyr3_ = fbo_pyr4_ = 0;
  tex_fused_ = fbo_fused_ = 0;
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

  if (ensureRawTex(w, h) == 0) return false;
  if (!uploadRawTex(raw_tex_, raw, w, h, row_stride_px)) return false;
  const auto t1 = std::chrono::steady_clock::now();

  const RawCommon rc =
      prepareRawCommon(neutral_r, neutral_g, neutral_b, ccm9, lsc, lsc_cols,
                       lsc_rows);

  // 实际渲染旋转 = 界面方向 + 传感器旋转（与 submitStill 同一约定）
  const int effective_rot = (rotation + 1) % 4;
  const bool swap_wh = (effective_rot % 2) == 1;
  const int dst_w = swap_wh ? h : w;
  const int dst_h = swap_wh ? w : h;
  if (!ensureTargets(dst_w, dst_h)) return false;
  while (glGetError() != GL_NO_ERROR) {
  }

  const bool rcd = demosaic == 1;
  bool dn_high = false;
  float sr_k = 0.f, sc_k = 0.f, ns = 0.f, ns_raw = 0.f;
  denoiseParams(noise_level, noise_sigma, &dn_high, &sr_k, &sc_k, &ns, &ns_raw);
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
  c.dn = rcd && noise_level > 0;
  c.dn_high = dn_high;
  c.sr_k = sr_k;
  c.sc_k = sc_k;
  c.ns = ns;
  c.ns_raw = ns_raw;
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
    float neutral_r, float neutral_g, float neutral_b, const float* ccm9,
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

void FrameRenderer::denoiseParams(int noise_level, float noise_sigma,
                                  bool* dn_high, float* sr_k, float* sc_k,
                                  float* ns, float* ns_raw) {
  // v0.5.34 重标定（v0.5.33 真机实测：两档无差别、细节 -59%、JPEG 体积腰斩 =
  // P0 值域权重饱和退化成 9 点无条件平均）。Laplacian 损失全部来自 P0（P4 保 Y
  // 不动），故 P0 收温和保细节、P4 保持较强只压色度 —— 两档这才拉开真实差距。
  *dn_high = noise_level >= 2;
  // v0.5.35：标准档仍偏"平"。P0 是细节与体积的主因，再放轻 0.50→0.28；彩噪由
  // P4 补强 1.80→2.20（P4 只动色度、保 Y，不影响亮度细节与体积大头）。
  *sr_k = *dn_high ? 2.20f : 0.28f;  // P0 值域 σ 系数
  // v0.6.19：自适应提亮 ~1.1EV 把噪声一起放大（真机同场景 A/B：色度 σ +84%），
  // P4 色度系数再补强约 25%。
  *sc_k = *dn_high ? 3.25f : 2.75f;  // P4 值域 σ 系数
  // 回退估计 + 防呆夹紧（v0.6.30：下限 0.004、上限 0.040——详见 kFsChromaDenoise 注）
  *ns_raw = noise_sigma > 0.0f ? noise_sigma : 0.012f;
  *ns = std::min(std::max(*ns_raw, 0.004f), 0.040f);
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
    if (c.mfnr_fused) {
      passRawDenoiseFused(w, h, c.cfa, c.ns, c.dn ? c.sr_k : 0.0f);
    } else {
      passRawDenoise(w, h, c.cfa, c.black_phase, c.white_level, c.gains, c.ns,
                     c.dn ? c.sr_k : 0.0f);
    }
    passRcdDir(w, h, c.cfa, c.black_phase, c.white_level, c.gains, true);
    glFinish();
    const auto d1 = std::chrono::steady_clock::now();
    passRcdG(w, h, c.cfa, c.black_phase, c.white_level, c.gains, true);
    glFinish();
    const auto d2 = std::chrono::steady_clock::now();
    dir_ms = std::chrono::duration<float, std::milli>(d1 - d0).count();
    g_ms = std::chrono::duration<float, std::milli>(d2 - d1).count();
    passRcdChroma(dst_w, dst_h, c.effective_rot, c.cfa, c.black_phase,
                  c.white_level, c.gains, c.ccm, true, p.hl_recon);
  } else {
    passRaw(dst_w, dst_h, c.effective_rot, c.cfa, c.black_phase, c.white_level,
            c.gains, c.ccm, p);
  }
  if (c.dn) {
    if (!ensureDnTarget(dst_w, dst_h)) return;
    const auto n0 = std::chrono::steady_clock::now();
    passChromaDenoise(dst_w, dst_h, c.effective_rot, c.ns, c.sc_k);
    glFinish();
    dn_ms = std::chrono::duration<float, std::milli>(
                std::chrono::steady_clock::now() - n0)
                .count();
  }
  passFilm(dst_w, dst_h, p, c.dn ? tex_dn_ : tex_scene_, /*still=*/true);
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
      std::string("{\"uploadMs\":") + std::to_string(c.upload_ms).substr(0, 6) +
      ",\"passMs\":" + std::to_string(ms(t1, t2)).substr(0, 6) +
      ",\"totalMs\":" + std::to_string(ms(t0, t3)).substr(0, 7) +
      ",\"w\":" + std::to_string(w) + ",\"h\":" + std::to_string(h) +
      ",\"demosaic\":\"" + (c.rcd ? "rcd" : "bilinear") + "\"";
  if (c.rcd) {
    kv += ",\"dirMs\":" + std::to_string(dir_ms).substr(0, 6) +
          ",\"gMs\":" + std::to_string(g_ms).substr(0, 6);
  }
  if (c.dn) {
    kv += std::string(",\"dn\":\"") + (c.dn_high ? "high" : "std") +
          "\",\"srK\":" + std::to_string(c.sr_k).substr(0, 4) +
          ",\"scK\":" + std::to_string(c.sc_k).substr(0, 4) +
          ",\"ns\":" + std::to_string(c.ns).substr(0, 5) +
          ",\"nsHal\":" + std::to_string(c.ns_raw).substr(0, 6) +
          ",\"dnMs\":" + std::to_string(dn_ms).substr(0, 6);
  }
  kv += ",\"tone\":" + std::to_string(p.tone_strength).substr(0, 3);
  kv += ",\"ev\":" + std::to_string(p.exposure_ev).substr(0, 5);
  // v0.7.3：ag 一并废除 —— 记录 filmic 的实际标定（EV，相对中灰 18.45%）与场景分位，
  // 供离线用同一组 EV 复算 spline 系数对拍（tools/tuning/filmic_offline.py 的 compute_spline）。
  // substr(0,9)：filmic_verify.py 用这些值复算 spline，截断到 "-5.024" 会把
  // 1.4e-4 的输入误差放大成 2.4e-4 的 power 误差，自检假阳性（v0.7.9 实测）
  kv += ",\"blackEv\":" + std::to_string(ev_black_).substr(0, 9);
  kv += ",\"whiteEv\":" + std::to_string(ev_white_).substr(0, 9);
  kv += ",\"linMid\":" + std::to_string(last_lin_mid_).substr(0, 6);
  kv += ",\"pLo\":" + std::to_string(last_p_lo_).substr(0, 7);
  kv += ",\"pHi\":" + std::to_string(last_p_hi_).substr(0, 7);
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
  kv += ",\"lt\":" + std::to_string(p.local_tone).substr(0, 4);
  // LSC：网格尺寸（"off" = 恒等/A-B 关闭档）
  kv += std::string(",\"lsc\":\"") +
        (c.lsc_ok ? std::to_string(c.lsc_cols) + "x" + std::to_string(c.lsc_rows)
                  : "off") +
        "\"";
  if (c.rcd) kv += ",\"hl\":" + std::to_string(p.hl_recon).substr(0, 3);
  if (!c.mfnr_kv.empty()) kv += "," + c.mfnr_kv;
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
  const GLenum units[7] = {GL_TEXTURE0, GL_TEXTURE1, GL_TEXTURE2, GL_TEXTURE3,
                           GL_TEXTURE4, GL_TEXTURE5, GL_TEXTURE6};
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
  glActiveTexture(GL_TEXTURE0);
  glUniform2i(u_mfnr_fuse_.size, w, h);
  glUniform2f(u_mfnr_fuse_.tiles, static_cast<float>(tiles_w_),
              static_cast<float>(tiles_h_));
  glUniform1i(u_mfnr_fuse_.cfalut, cfaLutOf(cfa));
  glUniform4f(u_mfnr_fuse_.black, black_phase[0], black_phase[1], black_phase[2],
              black_phase[3]);
  glUniform1f(u_mfnr_fuse_.white, white_level);
  glUniform3f(u_mfnr_fuse_.gain, gains[0], gains[1], gains[2]);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

void FrameRenderer::passRawDenoiseFused(int w, int h, int cfa, float ns,
                                        float k) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_mos_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_raw_dn_fused_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, tex_fused_);
  glUniform1i(u_raw_dn_f_.tex, 0);
  glUniform2i(u_raw_dn_f_.size, w, h);
  glUniform1i(u_raw_dn_f_.cfalut, cfaLutOf(cfa));
  glUniform1f(u_raw_dn_f_.ns, ns);
  glUniform1f(u_raw_dn_f_.k, k);
  glDrawArrays(GL_TRIANGLES, 0, 3);
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
                                   int noise_level, float noise_sigma,
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

  // 上传：帧 0 → raw_tex_（参考帧），帧 1..3 → tex_mfnr_
  if (ensureRawTex(w, h) == 0) return false;
  if (!uploadRawTex(raw_tex_, frames[0], w, h, row_strides_px[0])) return false;
  if (!ensureMfnrTargets(w, h)) return false;
  for (int i = 1; i < count; ++i) {
    if (!uploadRawTex(tex_mfnr_[i - 1], frames[i], w, h, row_strides_px[i]))
      return false;
  }
  const RawCommon rc =
      prepareRawCommon(neutral_r, neutral_g, neutral_b, ccm9, lsc, lsc_cols,
                       lsc_rows);
  const float wl = static_cast<float>(white_level);

  // 金字塔 + readback + CPU 对齐。1/2 域尺寸 w1×h1，tile 32 → tiles。
  const int w2 = (pyr1_w_ + 1) / 2, h2 = (pyr1_h_ + 1) / 2;
  const int w3 = (w2 + 1) / 2, h3 = (h2 + 1) / 2;
  const int w4 = (w3 + 1) / 2, h4 = (h3 + 1) / 2;
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
    readL(fbo_pyr2_, w2, h2, &py->l2);
    readL(fbo_pyr3_, w3, h3, &py->l3);
    readL(fbo_pyr4_, w4, h4, &py->l4);
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
  float align_ms = 0.f, gx_sum = 0.f, gy_sum = 0.f, conf_sum = 0.f;
  int fallback = 0;
  for (int i = 1; i < count; ++i) {
    MfnrPyramid cur;
    capturePyr(tex_mfnr_[i - 1], &cur);
    const MfnrAlignOut a = mfnrAlign(ref, cur, tiles_w_, tiles_h_);
    // 打包位移场（xy=mosaic 域位移、z=conf、w=1）上传
    std::vector<float> pack(static_cast<size_t>(tiles_w_) * tiles_h_ * 4, 1.f);
    for (int t = 0; t < tiles_w_ * tiles_h_; ++t) {
      pack[static_cast<size_t>(t) * 4 + 0] = a.off[static_cast<size_t>(t) * 2];
      pack[static_cast<size_t>(t) * 4 + 1] =
          a.off[static_cast<size_t>(t) * 2 + 1];
      pack[static_cast<size_t>(t) * 4 + 2] = a.conf[static_cast<size_t>(t)];
      if (a.conf[static_cast<size_t>(t)] < 0.1f) ++fallback;
    }
    glBindTexture(GL_TEXTURE_2D, tex_off_[i - 1]);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, tiles_w_, tiles_h_, GL_RGBA,
                    GL_FLOAT, pack.data());
    gx_sum += a.gx;
    gy_sum += a.gy;
    conf_sum += a.conf_med;
  }
  align_ms = std::chrono::duration<float, std::milli>(
                 std::chrono::steady_clock::now() - t0)
                 .count();

  // 融合 → tex_fused_（R16F 线性 mosaic）
  const auto tf = std::chrono::steady_clock::now();
  passMfnrFuse(w, h, cfa, black_phase, wl, rc.gains);
  glFinish();
  const float fuse_ms = std::chrono::duration<float, std::milli>(
                            std::chrono::steady_clock::now() - tf)
                            .count();

  // 成片链（P0 用 fused 变体）——与 submitRaw 共享
  const int effective_rot = (rotation + 1) % 4;
  const bool swap_wh = (effective_rot % 2) == 1;
  const int dst_w = swap_wh ? h : w;
  const int dst_h = swap_wh ? w : h;
  if (!ensureTargets(dst_w, dst_h)) return false;
  while (glGetError() != GL_NO_ERROR) {
  }
  const bool rcd = demosaic == 1;
  bool dn_high = false;
  float sr_k = 0.f, sc_k = 0.f, ns = 0.f, ns_raw = 0.f;
  denoiseParams(noise_level, noise_sigma, &dn_high, &sr_k, &sc_k, &ns, &ns_raw);
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
  c.dn = rcd && noise_level > 0;
  c.dn_high = dn_high;
  c.sr_k = sr_k;
  c.sc_k = sc_k;
  c.ns = ns;
  c.ns_raw = ns_raw;
  c.lsc_ok = rc.lsc_ok;
  c.lsc_cols = lsc_cols;
  c.lsc_rows = lsc_rows;
  c.upload_ms = std::chrono::duration<float, std::milli>(tf - t0).count();
  c.mfnr_fused = true;
  const auto f3 = [](float v) { return std::to_string(v).substr(0, 5); };
  c.mfnr_kv = std::string("\"mfnr\":\"on\"") +
              ",\"alignMs\":" + f3(align_ms) +
              ",\"fuseMs\":" + f3(fuse_ms) +
              ",\"gx\":" + f3(gx_sum / (count - 1)) +
              ",\"gy\":" + f3(gy_sum / (count - 1)) +
              ",\"confMed\":" + f3(conf_sum / (count - 1)) +
              ",\"fbTiles\":" + std::to_string(fallback) +
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
                              float lut_strength, bool box_filter, int stat_ch) {
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
    bindLut(u_hist_.lut, u_hist_.lut_on, u_hist_.lut_strength, u_hist_.lut_dmin,
            u_hist_.lut_dinv, u_hist_.lut_size, lut_strength);
  }
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// M4-6：统计源参数化 —— 自适应影调必须对**场景纹理**（baseTone 之前）统计，
// 而 UI 直方图仍看 film 输出（tex_work_），两者共用 kFsHist 同一实现。
// v0.6.24：加 bypass_lut —— 风格 LUT 不该改曝光（默认参数下行为与旧版逐位一致）。
bool FrameRenderer::histogramFrom(GLuint src, int* out, int n, bool bypass_lut,
                                 int stat_ch) {
  if (out == nullptr || n < 2 || src == 0 || scene_w_ <= 0) return false;
  const int hw = 256, hh = 96;
  if (tex_hist_ == 0) {
    makeTex(&tex_hist_, &fbo_hist_, GL_RGBA8, hw, hh);
    hist_w_ = hw;
    hist_h_ = hh;
  }
  renderLuma(src, fbo_hist_, hw, hh, bypass_lut ? 0.0f : lut_strength_, false, stat_ch);
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
  constexpr int kN = 64;
  SceneStats st;
  int hist[kN];
  if (!histogramFrom(src, hist, kN, /*bypass_lut=*/true, /*stat_ch=*/1)) return st;
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
  // 直方图在**显示域**（kFsHist 内部已过 OETF）；pow 单调 ⇒ pow(·, 2.2) 折回线性后
  // 仍是同一像素的 max 通道值，不是近似。
  const auto to_lin = [](float v) { return std::pow(v, 2.2f); };
  st.p_lo = to_lin(pct(0.005));
  st.p_mid = to_lin(pct(0.50));
  st.p_hi = to_lin(pct(0.995));
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
FrameRenderer::FilmicSpline FrameRenderer::computeFilmicSpline(float black_ev, float white_ev) {
  // 全程 double：shoulder 的 5×5 系统条件数差，用 float 中间量会把 1e-7 的舍入放大到
  // 1e-5（与 float64 参考实现对拍时可见）。虽然远低于 8bit 量化精度（1/255 ≈ 4e-3），
  // 但没有理由把它引进来 —— darktable 的 gauss_solve 同样在 double 下求解。
  constexpr double kGrey = 0.1845;                // grey_source（18.45% 中灰）
  constexpr double kSafety = 0.01;                // darktable SAFETY_MARGIN
  constexpr double kBlackTargetPct = 0.01517634;  // black_point_target 默认（%）
  constexpr double kContrastParam = 1.0;          // darktable contrast 默认
  constexpr double kLatitude = 0.01;              // darktable latitude 默认（%）
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
  return sp;
}

// ── 自动 EV 标定（v0.7.3）────────────────────────────────────────────────────
// 自适应对象从"亮度"换成"动态范围"：black/white relative exposure 由场景分位算出，
// 中灰锚点固定 18.45%。于是**不同场景输出不同亮度**（暗的地方暗），而压 EV / 降 AE
// 曝光能真的传导到成片 —— 旧的"中位锚定"会把它们补偿掉（v0.7.2 的死结）。
// auto_on=false：用 darktable 默认（−7.65/+4.56），曲线完全固定、不随场景变。
void FrameRenderer::updateFilmicEV(GLuint src, bool still, bool auto_on,
                                  float user_black, float user_white) {
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
  const float w = user_white;
  const SceneStats st = measureSceneStats(src);  // 开关两边都统计（日志不留盲区）
  if (st.valid && auto_on) {
    b = std::clamp(std::log2(std::max(st.p_lo, 1e-6f) / kGrey), -16.0f, -1.0f);
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
  filmic_ = computeFilmicSpline(ev_black_, ev_white_);
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
                 p.filmic_white_ev);
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
  glBindTexture(GL_TEXTURE_2D, src_tex);
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
        "{\"blackEv\":" + std::to_string(ev_black_).substr(0, 9) +
            ",\"whiteEv\":" + std::to_string(ev_white_).substr(0, 9) +
            ",\"linMid\":" + std::to_string(last_lin_mid_).substr(0, 6) +
            ",\"ratio\":" + std::to_string(g_domain_ratio).substr(0, 6) +
            ",\"pLo\":" + std::to_string(last_p_lo_).substr(0, 7) +
            ",\"pHi\":" + std::to_string(last_p_hi_).substr(0, 7) +
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
