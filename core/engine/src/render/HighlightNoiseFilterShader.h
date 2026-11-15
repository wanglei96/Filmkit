#pragma once

namespace filmkit {

// All outputs below are full RGB 6cov: loc0=(RR,GG,BB,1), loc1=(RG,RB,GB,1).
// RGBA32F / NEAREST / no blending. All coordinates already in D4 geometry.
// These are PSD covariance UPPER BOUNDS, not exact variances. They allow arbitrary
// spatial/input cross-correlation. Down matches the signal's linear coefficients;
// guided includes its full local first-order signal Jacobian (including guide
// dependence and output normalization). Nonlinear higher-order noise is not
// bounded. PC3D helper below freezes adaptive denoising weights, explicitly.
// DO NOT apply neff, scaleF*.75, WB/LSC/CCM again to any of these maps.
inline constexpr const char* kFsHighlightNoiseDown = R"(#version 300 es
precision highp float;
precision highp int;
layout(location=0) out vec4 oVariance;
layout(location=1) out vec4 oCovariance;
uniform highp sampler2D uVariance;
uniform highp sampler2D uCovariance;
uniform ivec2 uSize;
uniform int uResize;
uniform int uStride;
uniform float uSigma;
mat3 covarianceAt(ivec2 p) {
    p=clamp(p,ivec2(0),uSize-1);
    vec3 d=texelFetch(uVariance,p,0).rgb;
    vec3 o=texelFetch(uCovariance,p,0).rgb;
    return mat3(d.r,o.r,o.g, o.r,d.g,o.b, o.g,o.b,d.b);
}
float normpdf(float x,float sigma) {
    return 0.39894*exp(-0.5*x*x/(sigma*sigma))/sigma;
}
void main() {
    ivec2 xy=clamp(ivec2(gl_FragCoord.xy),ivec2(0),uSize-1)*uResize;
    mat3 total=mat3(0.0);
    float ws=0.00001; // EXACT signal shader normalization seed
    float observedWeight=0.0;
    for(int i=-2;i<=2;++i) for(int j=-2;j<=2;++j) {
        float w=normpdf(float(j),uSigma)*normpdf(float(i),uSigma);
        total+=w*covarianceAt(xy+ivec2(i*uStride,j*uStride));
        ws+=w;
        observedWeight+=w;
    }
    // sum alpha < 1 because signal shader's denominator has a constant seed.
    // Cauchy: Cov(sum alpha X) <= (sum alpha)*sum alpha Sigma.
    mat3 result=total*(observedWeight/(ws*ws));
    oVariance=vec4(result[0][0],result[1][1],result[2][2],1.0);
    oCovariance=vec4(result[1][0],result[2][0],result[2][1],1.0);
}
)";

// Uniforms map 1:1 to kFsPc3dUp, plus uLowVariance/uLowCovariance from first
// PC3D's OUTPUT map and uGuideVariance/uGuideCovariance from full-res INPUT.
// Uses the same ridge 3e-4, support, duplicate clamp, and denominator branches.
inline constexpr const char* kFsHighlightNoiseUp = R"(#version 300 es
precision highp float;
precision highp int;
layout(location=0) out vec4 oVariance;
layout(location=1) out vec4 oCovariance;
uniform highp sampler2D uLow;
uniform highp sampler2D uGuideHi;
uniform highp sampler2D uLowVariance;
uniform highp sampler2D uLowCovariance;
uniform highp sampler2D uGuideVariance;
uniform highp sampler2D uGuideCovariance;
uniform ivec2 uLowSize;
uniform ivec2 uHiSize;
uniform int uR;
const vec3 L=vec3(1.0/3.0);
mat3 loadCov(highp sampler2D diagTex,highp sampler2D offTex,ivec2 p) {
    vec3 d=texelFetch(diagTex,p,0).rgb;
    vec3 o=texelFetch(offTex,p,0).rgb;
    return mat3(d.r,o.r,o.g, o.r,d.g,o.b, o.g,o.b,d.b);
}
void main() {
    ivec2 xy=clamp(ivec2(gl_FragCoord.xy),ivec2(0),uHiSize-1);
    float g0=dot(texelFetch(uGuideHi,xy,0).rgb,L);
    float sigma=0.4*float(uR);
    float divisor=2.0*sigma*sigma;
    float sumX=0.0,sumX2=0.0,ws=0.0;
    vec3 sumY=vec3(0.0),sumXY=vec3(0.0);
    for(int i=-4;i<=4;++i) {
        if(i < -uR || i > uR) continue;
        float wi=exp(-float(i*i)/divisor);
        for(int j=-4;j<=4;++j) {
            if(j < -uR || j > uR) continue;
            float w=wi*exp(-float(j*j)/divisor);
            ivec2 p=clamp(xy+ivec2(i,j),ivec2(0),uHiSize-1);
            float x=dot(texelFetch(uGuideHi,p,0).rgb,L);
            vec3 y=texelFetch(uLow,clamp(p/uR,ivec2(0),uLowSize-1),0).rgb;
            sumX+=x*w; sumY+=y*w; sumX2+=x*x*w; sumXY+=y*(x*w); ws+=w;
        }
    }
    float meanX=sumX/ws;
    vec3 meanY=sumY/ws;
    float varX=sumX2/ws-meanX*meanX;
    float den=max(varX,0.0)+3e-4;
    vec3 a=(sumXY/ws-meanX*meanY)/den;
    float delta=g0-meanX;
    vec3 fit=a*delta+meanY;
    float outL=dot(fit,L);
    float threshold=max(0.25*g0,1e-6);
    float normalizer=max(outL,threshold);
    mat3 Jfit=mat3(g0/normalizer);
    vec3 Jg0=fit/normalizer;
    if(outL>=threshold) Jfit-=outerProduct(fit*(g0/(normalizer*normalizer)),L);
    else if(0.25*g0>=1e-6) Jg0-=fit*(0.25*g0/(normalizer*normalizer));
    // Same max(o,0) output clamp: inalive output channels have zero derivative.
    vec3 alive=vec3(greaterThan(fit*(g0/normalizer),vec3(0.0)));
    mat3 aliveJ=mat3(alive.r,0,0, 0,alive.g,0, 0,0,alive.b);
    Jfit=aliveJ*Jfit; Jg0*=alive;
    mat3 lowSum=mat3(0.0),guideSum=mat3(0.0);
    float lowK=0.0,guideK=0.0;
    // Treat direct center-guide derivative as a separate correlated observation.
    vec3 centerJ=Jfit*a+Jg0;
    float centerK=length(centerJ);
    if(centerK>0.0) {
        float vg=dot(L,loadCov(uGuideVariance,uGuideCovariance,xy)*L);
        guideSum+=outerProduct(centerJ,centerJ)*(max(vg,0.0)/centerK);
        guideK+=centerK;
    }
    for(int i=-4;i<=4;++i) {
        if(i < -uR || i > uR) continue;
        float wi=exp(-float(i*i)/divisor);
        for(int j=-4;j<=4;++j) {
            if(j < -uR || j > uR) continue;
            float fraction=wi*exp(-float(j*j)/divisor)/ws;
            ivec2 p=clamp(xy+ivec2(i,j),ivec2(0),uHiSize-1);
            ivec2 lp=clamp(p/uR,ivec2(0),uLowSize-1);
            float x=dot(texelFetch(uGuideHi,p,0).rgb,L);
            vec3 y=texelFetch(uLow,lp,0).rgb;
            float beta=fraction*(1.0+delta*(x-meanX)/den);
            lowSum+=abs(beta)*loadCov(uLowVariance,uLowCovariance,lp);
            lowK+=abs(beta);
            // Full derivative of fitted RGB wrt this guide lightness; when
            // numerical varX clamps to zero the ridge denominator is constant.
            vec3 derivative=fraction*((y-meanY)*(delta/den)-a);
            if(varX>0.0) derivative-=fraction*a*(2.0*delta*(x-meanX)/den);
            derivative=Jfit*derivative;
            float k=length(derivative);
            if(k>0.0) {
                float vg=dot(L,loadCov(uGuideVariance,uGuideCovariance,p)*L);
                guideSum+=outerProduct(derivative,derivative)*(max(vg,0.0)/k);
                guideK+=k;
            }
        }
    }
    mat3 lowBound=Jfit*(lowSum*lowK)*transpose(Jfit);
    mat3 guideBound=guideSum*guideK;
    // Low image and high guide originate in the same capture, NOT independent.
    float branches=(lowK>0.0 && guideK>0.0)?2.0:1.0;
    mat3 result=branches*(lowBound+guideBound);
    oVariance=vec4(result[0][0],result[1][1],result[2][2],1.0);
    oCovariance=vec4(result[1][0],result[2][0],result[2][1],1.0);
}
)";

// Copy this GLSL helper into kFsPc3d before main; no new uniforms. Integration:
//   mat3 noiseA=kSeed*localCovariance(centerUV), noiseB=noiseA;
//   EACH actual main-kernel term, AFTER w/w2*=ws and f1, accumulate:
//     noiseA += f1*(w.x*cov(q0)+w.y*cov(q1)+w.z*cov(q2)+w.w*cov(q3));
//     noiseB += f1*(w2.x*cov(q0)+w2.y*cov(q1)+w2.z*cov(q2)+w2.w*cov(q3));
//   A=final_colour/Z; B=final_colour2/Z2;
//   result=pc3dOutputNoise(cin,A,B,noiseA/Z,noiseB/Z2,centerCov,uLuma);
//   loc1 diag(result); loc2 offdiag(result); image remains loc0.
// EXACT same accepted weights / duplicate taps / seed as signal pass; no kernel
// radius surrogate. Nonnegative normalized weights make noiseA/B PSD convex
// bounds even for shared sources, overlapping output kernels and border repeats.
// This freezes noise-dependent weights; it is NOT full adaptive-filter Jacobian.
inline constexpr const char* kGlslPc3dOutputNoise = R"(
mat3 pc3dOutputNoise(vec3 center,vec3 A,vec3 B,mat3 covA,mat3 covB,
                    mat3 covCenter,float lumaMix) {
    const vec3 L=vec3(0.25,0.5,0.25);
    float t=dot(B,L);
    float denominator=max(1e-6,t);
    vec3 u=B/denominator;
    float br=mix(dot(center,L),dot(A,L),lumaMix);
    mat3 J0=outerProduct(u*(1.0-lumaMix),L);
    mat3 JA=outerProduct(u*lumaMix,L);
    mat3 JB=mat3(br/denominator);
    if(t>=1e-6) JB-=outerProduct(B*(br/(denominator*denominator)),L);
    vec3 alive=vec3(greaterThan(u*br,vec3(0.0)));
    mat3 D=mat3(alive.r,0,0, 0,alive.g,0, 0,0,alive.b);
    J0=D*J0; JA=D*JA; JB=D*JB;
    mat3 sum=J0*covCenter*transpose(J0)+JA*covA*transpose(JA)+JB*covB*transpose(JB);
    float count=1.0; // B branch
    if(lumaMix!=1.0) count+=1.0;
    if(lumaMix!=0.0) count+=1.0;
    return count*sum; // arbitrary unknown correlation between branches
}
)";
}  // namespace filmkit
