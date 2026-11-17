#pragma once

namespace filmkit {

// PC GlesMgcRawSpatialShaders.mergeBayer (1597..1847), fused across four frames.
// This is the MergeBayer kernel, NOT the old MFNR bilinear-RAW sampler/inpaint.
//
// Host contract (all images share the same reference/native CFA layout):
// * uRaw0..3: native R16UI, NEAREST, even uSize >= 2. No canonical rearrangement.
// * uCfaLut: packed 2-bit RGB color per NATIVE phase x+2*y; R=0 G=1 B=2.
//   RGGB=0x94, GRBG=0x61, GBRG=0x49, BGGR=0x16.
// * uBlack: HAL black levels in NATIVE (00,10,01,11) order, shared by the four
//   frames; uWhite: native sensor white code. A canonical R/G1/G2/B black array
//   MUST be mapped back to native phases on the CPU, including the two greens.
//   This interface requires identical black/white metadata for all four inputs.
// * uGain: RGB WB, both green phases share .g. uFrameScale: reference-domain
//   exposure multipliers (usually 1, referenceTET/alternateTET, ...). Output .r
//   is WB * (code-black)/(white-black) * frameScale, unclamped reference RAW,
//   i.e. PC short-domain resolve * ratio is the identical unit conversion.
//   No baseline/exposure metadata correction belongs downstream of this pass.
// * uOff1..3: existing temporal coarse 128-RAW-pixel solver field, mosaic dx/dy.
//   uTiles is its exact texture dimension. As in kFsMfnrFuse, bilinear flow and
//   uW use UV=(RAW pixel centre)/(uTiles*128), including padded edge tiles. This
//   deliberately preserves the project's coarse solver, not PC's fine solver.
// * uBentoFlow: ACCEPTED, forward/backward-corrected collar flow in mosaic dx/dy,
//   ceil(uSize/16), one texel per 8x8 guide/Bayer quads. Fixed nearest tile fetch
//   is PC's default interpolationFlowTolerance=0. Never bind the coarse flow or
//   a full-RAW normalized flow texture here. Its short frame replaces uOffN.
// * uBentoMask: accepted selected-short weight slice, RAW/4 R8 UNORM, LINEAR
//   + CLAMP_TO_EDGE. CPU generates adjusted M at RAW/2, then performs the exact
//   PC RAW/4 rewrite/complement/re-complement R8 quantization before upload.
//   Frame weights sample EACH RAW pixel centre (PC Bayer per-phase sampling).
//   Never upload initial/RAW/2 M.
// * uLinearMask: RAW/4 R8 UNORM, LINEAR + CLAMP_TO_EDGE, strict binary 3x3
//   min!=max on the selected short's rejection slice (PC UpdateLinearKernelMask,
//   NOT a temporal union and NOT a 3x3 operation on RAW/2 M). Quad-centre lookup.
// * uW: existing temporal mismatch RGB weights for frames 1/2/3, LINEAR + CLAMP.
//   short=M (ignores uW); all others=(1-M)*existingWeight, reference weight=1.
// * uLongWeights: RAW/4 RGBA8, LINEAR + CLAMP, RGB = frame 1/2/3 weights after
//   the TWO original long-frame exclusions (reference highlight, native source
//   clipping). uLongFrames bit 0/1/2 selects these instead of (1-M)*uW. Do not
//   multiply by adjusted M again; long and accepted short have distinct masks.
//   CPU must reject the WHOLE short frame by binding M=0, linearMask=0 and
//   uShortIndex=-1 AND originalShort's uGlobalWeight=0 to exclude that frame.
//   In rejection mode M/linearMix are forced 0 regardless of texture contents;
//   bind valid dummy textures (1x1 is sufficient) for unused Bento inputs.
//   Accepted short index is 1..3; reference 0 remains reference.
// * uKernelSigma: one inverse spatial width per frame, squared INSIDE exp.
//   PC: 1/(baseScale*weightMultiplier*selectedMultiplier); Bayer baseScale maps
//   merged SNR {14.5:.6,29.5:.42,44:.35}, multiplier {10:1,30:1.4140000343}.
// * uGlobalWeight: PC MaximumMergeWeight per frame, ref=1, others
//   min(50,referenceRead/(alternateRead*frameScale^2)), metadata-less exponent 1.
//   Read variances are in each frame's native normalized sensor domain before
//   exposure scaling. uW carries mismatch confidence, NOT this global factor.
//   Accepted selected short overrides its global to 1 (also for kernel tuning).
// * uNoiseShot/uNoiseRead: base native normalized RGB shot S / read O; both
//   native green phases use .g. uNoiseGainRatio: per-frame native sensor gain
//   relative to that base calibration (NOT exposure ratio or creative WB).
//   Shot/read must be finite >=0, sensor gain and frameScale finite >0. No
//   noise-strength slider is folded into these physical sensor coefficients.
//   A source tap z in its own sensor domain has variance S*g*max(z,0)+O*g*g.
//   Exposure scaling multiplies variance by scale^2; final WB by WB^2.
// * MRT location 1 oNoise (RGBA32F recommended): .r exact propagated marginal
//   variance from actual individual source signals; .g effective reference/WB
//   shot slope A; .b effective read B; .a accepted-short weighted support share.
//   A=sum(h^2*S*g*scale)*WB / totalSupport^2,
//   B=sum(h^2*O*g^2*scale^2)*WB^2 / totalSupport^2,
//   where h=kernel*frameWeight. Mirror/clamp duplicate source pixels are grouped
//   BEFORE squaring h (correlated observations), separately within each frame.
//   Vactual need not equal A*merged+B on textured/multi-exposure input; downstream
//   consumers needing signal-specific variance should use .r, not that shortcut.
//   Short share uses the same unnormalized dual-green support as image resolve,
//   not M alone. These are marginal statistics, NOT inter-output covariance.
// No per-tap satW/darkW: accepted-short admission and long-frame exclusions are
// upstream, with distinct contracts. Negative noise/highlights >1 are retained. CameraWhite's
// nonlinear bound belongs AFTER actual demosaic/unshade in RGB resolve; it must
// not be applied to Bayer here because clipping and demosaic do not commute.
// Render location 0 to R16F (.r); optional location 1 RGBA32F; no blending.
// If no attachment is bound at location 1, the noise output is discarded by GL.
inline constexpr const char* kFsHighlightMerge = R"(#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;
precision highp sampler2D;
layout(location = 0) out vec4 oColor;
layout(location = 1) out vec4 oNoise;
uniform highp usampler2D uRaw0;
uniform highp usampler2D uRaw1;
uniform highp usampler2D uRaw2;
uniform highp usampler2D uRaw3;
uniform highp sampler2D uOff1;
uniform highp sampler2D uOff2;
uniform highp sampler2D uOff3;
uniform highp sampler2D uW;
uniform highp sampler2D uLongWeights;
uniform int uLongFrames;
uniform highp sampler2D uBentoFlow;
uniform highp sampler2D uBentoMask;
uniform highp sampler2D uLinearMask;
uniform ivec2 uSize;
uniform vec2 uTiles;
uniform int uCfaLut;
uniform vec4 uBlack;
uniform float uWhite;
uniform vec3 uGain;
uniform vec4 uFrameScale;
uniform int uShortIndex;
uniform vec4 uKernelSigma;
uniform vec4 uGlobalWeight;
uniform vec3 uNoiseShot;
uniform vec3 uNoiseRead;
uniform vec4 uNoiseGainRatio;

int colorAtPhase(int phase) { return (uCfaLut >> (phase * 2)) & 3; }
float gainAtPhase(int phase) {
    int c = colorAtPhase(phase);
    return c == 0 ? uGain.r : (c == 1 ? uGain.g : uGain.b);
}
int mirrorCoordinate(int coordinate, int extent) {
    if (extent <= 1) return 0;
    if (coordinate < 0) coordinate = -coordinate - 1;
    if (coordinate >= extent) coordinate = 2 * extent - coordinate - 1;
    return clamp(coordinate, 0, extent - 1);
}
ivec2 mirrorQuad(ivec2 p) {
    ivec2 extent = uSize / 2;
    return ivec2(mirrorCoordinate(p.x, extent.x),
                 mirrorCoordinate(p.y, extent.y));
}
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
float normalizedAt(highp usampler2D raw, ivec2 quad, int phase, float scale) {
    ivec2 p = quad * 2 + ivec2(phase & 1, phase >> 1);
    float gain = scale / max(uWhite - uBlack[phase], 1.0);
    // PC's raw*gains + blackTerms, no [0,1] clamp or per-tap clipping veto.
    return float(texelFetch(raw, p, 0).r) * gain - uBlack[phase] * gain;
}
float kernelWeight(vec2 offset, float sigmaSquared, float linearMix) {
    float gaussian = exp(-0.5 * dot(offset * offset, vec2(sigmaSquared)));
    float linear = max(1.0 - length(offset) * 0.4, 0.0);
    return mix(gaussian, linear, linearMix);
}
// Multiple stencil taps can mirror onto exactly the same physical sensor
// sample at borders. Accumulate their linear coefficients before variance.
void appendTap(ivec2 coordinate, float value, float weight,
               inout ivec2 coordinates[18], inout vec2 taps[18], inout int count) {
    for (int i = 0; i < 18; ++i) {
        if (i >= count) break;
        if (all(equal(coordinates[i], coordinate))) {
            taps[i].y += weight;
            return;
        }
    }
    coordinates[count] = coordinate;
    taps[count] = vec2(value, weight);
    ++count;
}
struct FrameMerge {
    vec2 sum;
    vec3 noise;
};
FrameMerge mergeFrame(highp usampler2D raw, ivec2 outputQuad, int phase,
                vec2 mosaicFlow, float scale, float sigma, float linearMix,
                float sensorGain) {
    ivec2 tile = outputQuad / 8;
    ivec2 within = outputQuad - tile * 8;
    vec2 tilePosition = vec2(tile * 8) + mosaicFlow * 0.5;
    ivec2 alignedTileQuad = ivec2(roundEven(tilePosition));
    vec2 residual = 2.0 * (vec2(alignedTileQuad) - tilePosition);
    ivec2 anchor = alignedTileQuad + within;
    float intensity = 0.0;
    float support = 0.0;
    float sigmaSquared = sigma * sigma;
    ivec2 coordinates[18];
    vec2 taps[18];
    int count = 0;
    // Both green lattices ALWAYS participate; the mask only changes shape.
    bool green = colorAtPhase(phase) == 1;
    int otherPhase = 3 - phase;
    vec2 otherPhaseOffset = vec2(ivec2(otherPhase & 1, otherPhase >> 1)
                                  - ivec2(phase & 1, phase >> 1));
    for (int y = -1; y <= 1; ++y) {
        if (abs(residual.y + 2.0 * float(y)) > 2.5) continue;
        for (int x = -1; x <= 1; ++x) {
            vec2 offset = residual + 2.0 * vec2(x, y);
            ivec2 quad = mirrorQuad(anchor + ivec2(x, y));
            if (abs(offset.x) <= 1.5 && abs(offset.y) <= 1.5) {
                float w = kernelWeight(offset, sigmaSquared, linearMix);
                float value = normalizedAt(raw, quad, phase, scale);
                intensity += value * w;
                support += w;
                appendTap(quad * 2 + ivec2(phase & 1, phase >> 1), value, w,
                          coordinates, taps, count);
            }
            vec2 otherOffset = offset + otherPhaseOffset;
            if (green && abs(otherOffset.x) <= 1.5 && abs(otherOffset.y) <= 1.5) {
                float w = kernelWeight(otherOffset, sigmaSquared, linearMix);
                float value = normalizedAt(raw, quad, otherPhase, scale);
                intensity += value * w;
                support += w;
                appendTap(quad * 2 + ivec2(otherPhase & 1, otherPhase >> 1), value, w,
                          coordinates, taps, count);
            }
        }
    }
    int color = colorAtPhase(phase);
    float shot = uNoiseShot[color] * sensorGain;
    float read = uNoiseRead[color] * sensorGain * sensorGain;
    float actualVariance = 0.0;
    float shotSlope = 0.0;
    float readVariance = 0.0;
    for (int i = 0; i < 18; ++i) {
        if (i >= count) break;
        float squaredWeight = taps[i].y * taps[i].y;
        float nativeSignal = taps[i].x / scale;
        actualVariance += squaredWeight *
            (shot * max(nativeSignal, 0.0) + read) * scale * scale;
        shotSlope += squaredWeight * shot * scale;
        readVariance += squaredWeight * read * scale * scale;
    }
    return FrameMerge(vec2(intensity, support),
                      vec3(actualVariance, shotSlope, readVariance));
}
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 q = p / 2;
    int phase = (p.x & 1) + 2 * (p.y & 1);
    vec2 weightUv = (vec2(p) + 0.5) / (uTiles * 128.0);
    vec2 rawUv = (vec2(p) + 0.5) / vec2(uSize);
    vec2 quadUv = (2.0 * vec2(q) + 1.0) / vec2(uSize);
    bool accepted = uShortIndex >= 1 && uShortIndex <= 3;
    float m = accepted ? texture(uBentoMask, rawUv).r : 0.0;
    float linearMix = accepted ? texture(uLinearMask, quadUv).r : 0.0;
    vec3 temporalWeight = texture(uW, weightUv).rgb;
    vec3 longWeight = texture(uLongWeights, rawUv).rgb;
    vec3 ordinaryWeight = (1.0-m)*temporalWeight;
    if ((uLongFrames & 1) != 0) ordinaryWeight.r = longWeight.r;
    if ((uLongFrames & 2) != 0) ordinaryWeight.g = longWeight.g;
    if ((uLongFrames & 4) != 0) ordinaryWeight.b = longWeight.b;
    ivec2 flowTile = clamp(q / 8, ivec2(0), textureSize(uBentoFlow, 0) - 1);
    vec2 shortFlow = texelFetch(uBentoFlow, flowTile, 0).xy;
    vec2 f1 = uShortIndex == 1 ? shortFlow : coarseFlow(uOff1, weightUv);
    vec2 f2 = uShortIndex == 2 ? shortFlow : coarseFlow(uOff2, weightUv);
    vec2 f3 = uShortIndex == 3 ? shortFlow : coarseFlow(uOff3, weightUv);
    vec4 frameWeight = vec4((1.0-m)*uGlobalWeight.x,
        uShortIndex == 1 ? m : ordinaryWeight.r*uGlobalWeight.y,
        uShortIndex == 2 ? m : ordinaryWeight.g*uGlobalWeight.z,
        uShortIndex == 3 ? m : ordinaryWeight.b*uGlobalWeight.w);
    FrameMerge r0 = mergeFrame(uRaw0, q, phase, vec2(0), uFrameScale.x,
                               uKernelSigma.x, linearMix, uNoiseGainRatio.x);
    FrameMerge r1 = mergeFrame(uRaw1, q, phase, f1, uFrameScale.y,
                               uKernelSigma.y, linearMix, uNoiseGainRatio.y);
    FrameMerge r2 = mergeFrame(uRaw2, q, phase, f2, uFrameScale.z,
                               uKernelSigma.z, linearMix, uNoiseGainRatio.z);
    FrameMerge r3 = mergeFrame(uRaw3, q, phase, f3, uFrameScale.w,
                               uKernelSigma.w, linearMix, uNoiseGainRatio.w);
    vec2 sum = r0.sum*frameWeight.x + r1.sum*frameWeight.y
             + r2.sum*frameWeight.z + r3.sum*frameWeight.w;
    vec4 squared = frameWeight * frameWeight;
    vec3 noise = r0.noise*squared.x + r1.noise*squared.y
               + r2.noise*squared.z + r3.noise*squared.w;
    float totalSupport = max(sum.y, 1e-8);
    float wb = gainAtPhase(phase);
    // Divide ONCE, preserving PC's additive Bayer/weight resolve. Noise squares
    // the SAME actual source coefficients, with no scalar-neff substitution.
    oColor = vec4(sum.x / totalSupport * wb, 0.0, 0.0, 1.0);
    vec3 varianceScale = vec3(wb*wb, wb, wb*wb) / (totalSupport*totalSupport);
    float shortSupport = uShortIndex == 1 ? r1.sum.y*frameWeight.y
                       : (uShortIndex == 2 ? r2.sum.y*frameWeight.z
                       : (uShortIndex == 3 ? r3.sum.y*frameWeight.w : 0.0));
    oNoise = vec4(noise * varianceScale, shortSupport / totalSupport);
})";

}  // namespace filmkit
