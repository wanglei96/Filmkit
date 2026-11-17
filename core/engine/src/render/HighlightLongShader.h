#pragma once

namespace filmkit {

// PC f3449a1 alignedRawClippingMask + two bentoRewriteWeight passes for
// SHADOW_LONG. RGB packs the independent frame 1/2/3 R8 slices; no reduction.
// The sole alignment adaptation is the existing coarse bilinear mosaic-pixel
// field instead of PC's converted normalized flow. Both use RAW/4 centres,
// roundEven Bayer-quad anchors and the complete 3x3, four-phase native stencil.
inline constexpr const char* kFsHighlightLongClip = R"(#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;
precision highp sampler2D;
uniform highp usampler2D uRaw1;
uniform highp usampler2D uRaw2;
uniform highp usampler2D uRaw3;
uniform highp sampler2D uOff1;
uniform highp sampler2D uOff2;
uniform highp sampler2D uOff3;
uniform ivec2 uRawSize;
uniform vec2 uTiles;
uniform vec4 uPhaseClippingLevels;
uniform int uLongFrames; // bit 0/1/2 = frame 1/2/3; never the accepted short
out vec4 oClippingMask;

vec2 coarseFlow(highp sampler2D field, vec2 uv) {
    vec2 p = uv * uTiles - 0.5;
    ivec2 i = ivec2(floor(p));
    ivec2 lo = clamp(i, ivec2(0), ivec2(uTiles) - 1);
    ivec2 hi = clamp(i + 1, ivec2(0), ivec2(uTiles) - 1);
    vec2 f = fract(p);
    return mix(mix(texelFetch(field, lo, 0).xy,
                   texelFetch(field, ivec2(hi.x, lo.y), 0).xy, f.x),
               mix(texelFetch(field, ivec2(lo.x, hi.y), 0).xy,
                   texelFetch(field, hi, 0).xy, f.x), f.y);
}
bool quadIsNearClipping(highp usampler2D raw, ivec2 quad) {
    quad = clamp(quad, ivec2(0), uRawSize / 2 - ivec2(1));
    ivec2 origin = quad * 2;
    for (int phase = 0; phase < 4; ++phase) {
        ivec2 p = min(origin + ivec2(phase & 1, phase >> 1), uRawSize - 1);
        uint value = texelFetch(raw, p, 0).r;
        if (value >= uint(ceil(uPhaseClippingLevels[phase]))) return true;
    }
    return false;
}
float alignedClipping(highp usampler2D raw, highp sampler2D field) {
    ivec2 referenceQuad = min(ivec2(gl_FragCoord.xy) * 2 + 1,
                               uRawSize / 2 - ivec2(1));
    vec2 uv = (2.0 * vec2(referenceQuad) + 1.0) / (uTiles * 128.0);
    vec2 sourceFlow = coarseFlow(field, uv) * 0.5;
    ivec2 anchor = ivec2(roundEven(vec2(referenceQuad) + sourceFlow));
    bool clipped = false;
    for (int y = -1; y <= 1; ++y)
        for (int x = -1; x <= 1; ++x)
            clipped = clipped || quadIsNearClipping(raw, anchor + ivec2(x, y));
    return clipped ? 1.0 : 0.0;
}
void main() {
    oClippingMask = vec4(
        (uLongFrames & 1) != 0 ? alignedClipping(uRaw1, uOff1) : 0.0,
        (uLongFrames & 2) != 0 ? alignedClipping(uRaw2, uOff2) : 0.0,
        (uLongFrames & 4) != 0 ? alignedClipping(uRaw3, uOff3) : 0.0, 1.0);
})";

// Pass 1: sample the original RAW/2 reference-highlight mask with NEAREST,
// temporal confidence with LINEAR in its padded coarse domain, store RGBA8.
// This mask is the original reference highlight, NOT adjusted selected-short M.
inline constexpr const char* kFsHighlightLongReferenceWeight = R"(#version 300 es
precision highp float;
precision highp sampler2D;
uniform highp sampler2D uW;
uniform highp sampler2D uReferenceHighlight;
uniform ivec2 uRawSize;
uniform ivec2 uOutputSize;
uniform vec2 uTiles;
out vec4 oWeight;
void main() {
    vec2 uv = gl_FragCoord.xy / vec2(uOutputSize);
    vec2 temporalUv = uv * vec2(uRawSize) / (uTiles * 128.0);
    oWeight = vec4(texture(uW, temporalUv).rgb *
                   (1.0 - texture(uReferenceHighlight, uv).r), 1.0);
})";

// Pass 2: the separately quantized first weight times the source mask's
// complement, stored again in RGBA8, then sampled LINEAR at RAW pixel centres.
inline constexpr const char* kFsHighlightLongSourceWeight = R"(#version 300 es
precision highp float;
precision highp sampler2D;
uniform highp sampler2D uExistingWeight;
uniform highp sampler2D uClippingMask;
uniform ivec2 uOutputSize;
out vec4 oWeight;
void main() {
    vec2 uv = gl_FragCoord.xy / vec2(uOutputSize);
    oWeight = vec4(texture(uExistingWeight, uv).rgb *
                  (1.0 - texture(uClippingMask, uv).rgb), 1.0);
})";

}  // namespace filmkit
