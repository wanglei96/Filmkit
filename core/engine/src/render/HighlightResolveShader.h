#pragma once

namespace filmkit {

// PC Spatial RGB resolve's source-white policy, AFTER demosaic and BEFORE CCM.
// Input/output are full RGB in destination geometry, with WB and LSC applied.
// This is an output-white policy, NOT native sensor clipping detection: genuine
// unsaturated coloured measurements above the source-neutral ceiling can be
// reduced. It must not be described as preserving every unclipped measurement.
//
// The source CameraWhite is native/no-WB neutral normalized by its maximum;
// uGain includes the actual applied (possibly creative) WB. Multiplying by it
// transports the ceiling into the input's WB domain; do not derive CameraWhite
// from creative WB, and do not force a neutral (1,1,1) WB-domain ceiling.
//
// uTex: RGBA16F D3, destination geometry, WB+LSC, nearest texel fetch.
// uBentoMask: accepted selected-short RAW/4 R8, LINEAR + CLAMP_TO_EDGE. Valid
// typed dummy required without an accepted short; uHasShort=0 forces share=0.
// uLsc: same RGB LSC texture and dimensions consumed by RCD; manual bilinear
// interpolation matches kFsRcdChroma::lscAt, including integer RAW positioning.
// uRot: effective_rot (0..3), with kVertRot scale=1 and ST=identity. The mapping
// below reproduces kVertRot followed by kFsRcdChroma's vertical tuv flip.
// uReferenceScale/uShortScale: reference-radiance exposure scales, not an extra
// output gain. Preserve source alpha; render to a DIFFERENT texture than uTex.
// Run before CA: CA spatially shifts R/B, breaking the exact local LSC inverse.
inline constexpr const char* kFsHighlightResolve = R"(#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform highp sampler2D uBentoMask;
uniform highp sampler2D uLsc;
uniform ivec2 uDstSize;
uniform ivec2 uRawSize;
uniform int uRot;
uniform ivec2 uLscSize;
uniform vec3 uCameraWhite;
uniform vec3 uGain;
uniform float uReferenceScale;
uniform float uShortScale;
uniform int uHasShort;

vec2 sourceUv(ivec2 dst) {
    vec2 c = (vec2(dst) + 0.5) / vec2(uDstSize) - 0.5;
    vec2 r = c;
    if (uRot == 1) r = vec2(-c.y, c.x);
    else if (uRot == 2) r = -c;
    else if (uRot == 3) r = vec2(c.y, -c.x);
    return vec2(r.x + 0.5, 0.5 - r.y);
}
vec3 lscAt(ivec2 p) {
    vec2 g = ((vec2(p) + 0.5) / vec2(uRawSize)) * vec2(uLscSize) - 0.5;
    ivec2 b = ivec2(floor(g));
    vec2 t = fract(g);
    ivec2 mx = uLscSize - 1;
    vec3 c00 = texelFetch(uLsc, clamp(b, ivec2(0), mx), 0).rgb;
    vec3 c10 = texelFetch(uLsc, clamp(b + ivec2(1, 0), ivec2(0), mx), 0).rgb;
    vec3 c01 = texelFetch(uLsc, clamp(b + ivec2(0, 1), ivec2(0), mx), 0).rgb;
    vec3 c11 = texelFetch(uLsc, clamp(b + ivec2(1, 1), ivec2(0), mx), 0).rgb;
    return mix(mix(c00, c10, t.x), mix(c01, c11, t.x), t.y);
}
void main() {
    ivec2 dst = clamp(ivec2(gl_FragCoord.xy), ivec2(0), uDstSize - 1);
    vec4 source = texelFetch(uTex, dst, 0);
    vec2 uv = sourceUv(dst);
    ivec2 raw = clamp(ivec2(uv * vec2(uRawSize)), ivec2(0), uRawSize - 1);
    vec3 shading = lscAt(raw);
    float shortShare = uHasShort != 0 ? clamp(texture(uBentoMask, uv).r, 0.0, 1.0) : 0.0;
    float whiteScale = mix(uReferenceScale, uShortScale, shortShare);
    vec3 ceiling = uCameraWhite * uGain * whiteScale;
    // Valid LSC gains are positive. Host must validate the source calibration;
    // no epsilon-derived fallback may silently substitute a different policy.
    vec3 unshaded = source.rgb / shading;
    oColor = vec4(min(unshaded, ceiling) * shading, source.a);
})";

}  // namespace filmkit
