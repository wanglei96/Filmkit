#pragma once

namespace filmkit {

// Production RAW-noise MODEL propagation (not a RCD Jacobian oracle).
// uNoise is native full-resolution RGBA32F merge MRT (Vactual,Aeff,Beff,share),
// already WB/reference-exposure normalized. Read Vactual, NOT Aeff*merged+Beff.
// Bind with the SAME kVertRot/uST/rotation state and output dimensions as the
// image's D3 resolve: vUV0 and the native-coordinate Y flip below intentionally
// match kFsRcdChroma. Valid also for RAW/2 D4 output. uRawSize is NATIVE RAW size.
// LSC is sampled at the rotated-back native coordinate, before covariance CCM.
// Native quad model: R/B take their native phase V; green is mean(Vg1,Vg2),
// a conservative upper bound for the variance of an equal-green average under
// ANY admissible covariance (Var((g1+g2)/2) <= (Vg1+Vg2)/2). Thus dual-green merge
// correlation is not silently treated as independent. This bounds the QUAD
// proxy, not nonlinear RCD residuals/demosaic error. No claim of exact RCD noise.
// Camera cross-color covariance is zero in this RAW model: source R/G/B are
// disjoint, but CCM then generates all 3 off-diagonal covariance components.
// Outputs, NEAREST/no blending, two RGBA32F targets in rotated D4 geometry:
//   loc0 = (SigmaRR,SigmaGG,SigmaBB,1)
//   loc1 = (SigmaRG,SigmaRB,SigmaGB,1) -- signed, NEVER clamp these to >=0!
// This explicit 6-component contract is consumed by PC3D/P4 projections/pairs;
// do not average it back into one global scalar. P0 uses native uNoise directly.
// Lower-res/guided stages must propagate their OWN covariance; this shader only
// constructs the input model. No second WB, exposure, neff, or LSC downstream.
inline constexpr const char* kFsHighlightNoise = R"(#version 300 es
precision highp float;
precision highp int;
in vec2 vUV0;
layout(location = 0) out vec4 oVariance;
layout(location = 1) out vec4 oCovariance;
uniform highp sampler2D uNoise;
uniform highp sampler2D uLsc;
uniform ivec2 uRawSize;
uniform ivec2 uLscSize;
uniform int uCfaLut;
uniform mat3 uCcm;
vec3 lscAt(ivec2 p) {
    vec2 g = ((vec2(p) + 0.5) / vec2(uRawSize)) * vec2(uLscSize) - 0.5;
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
    vec2 tuv = vec2(vUV0.x, 1.0 - vUV0.y);
    ivec2 p = clamp(ivec2(tuv * vec2(uRawSize)), ivec2(0), uRawSize - 1);
    ivec2 quad = (p / 2) * 2;
    vec3 v = vec3(0.0);
    for (int phase = 0; phase < 4; ++phase) {
        ivec2 q = min(quad + ivec2(phase & 1, phase >> 1), uRawSize - 1);
        float variance = max(texelFetch(uNoise, q, 0).r, 0.0);
        int channel = (uCfaLut >> (phase * 2)) & 3;
        if (channel == 0) v.r += variance;
        else if (channel == 1) v.g += 0.5 * variance;
        else if (channel == 2) v.b += variance;
    }
    vec3 gain = lscAt(p);
    v *= gain * gain;
    mat3 sigma = uCcm * mat3(v.r, 0.0, 0.0,
                             0.0, v.g, 0.0,
                             0.0, 0.0, v.b) * transpose(uCcm);
    oVariance = vec4(max(vec3(sigma[0][0], sigma[1][1], sigma[2][2]), vec3(0.0)), 1.0);
    oCovariance = vec4(sigma[1][0], sigma[2][0], sigma[2][1], 1.0);
}
)";

}  // namespace filmkit
