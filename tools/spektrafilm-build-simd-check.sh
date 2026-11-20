#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 2 ]]; then
    printf 'Usage: bash %s ANDROID_CLANG++ OUTPUT_DIRECTORY\n' "$0" >&2
    exit 2
fi
root=$(cd "$(dirname "$0")/.." && pwd)
cxx=$1
cc=${cxx%++}
mkdir "$2"
out=$(cd "$2" && pwd)
src=$root/core/engine/src
arm=$root/core/engine/third_party/arm-math/math
flags=(-std=c++20 -O3 -ffp-contract=off -pthread -I"$src"
    -DFILMKIT_SPEKTRAFILM_ARM_EXP10 -DFILMKIT_SPEKTRAFILM_ARM_CAM16)
objects=()
for name in advsimd/exp10 v_exp_data advsimd/pow v_pow_log_data v_pow_exp_data \
            advsimd/atan2 advsimd/hypot advsimd/log1p advsimd/expm1; do
    object=$out/$(basename "$name").o
    lto=(-fno-lto)
    if [[ $name == advsimd/exp10 ]]; then lto=(-flto=thin); fi
    "$cc" -O3 "${lto[@]}" -I"$arm/include" -I"$arm" -I"$arm/aarch64/advsimd" \
        -c "$arm/aarch64/$name.c" -o "$object"
    objects+=("$object")
done
for source in "$src"/film/spektrafilm/*.cpp; do
    object=$out/$(basename "$source" .cpp).o
    lto=(-fno-lto)
    if [[ ${source##*/} == Spectral.cpp ]]; then lto=(-flto=thin); fi
    "$cxx" "${flags[@]}" "${lto[@]}" -c "$source" -o "$object"
    objects+=("$object")
done
renames=(-DlookupMaximumChroma=scalarLookupMaximumChroma -DreinhardKnee=scalarReinhardKnee
    -DxyzToCam16Ucs=scalarXyzToCam16Ucs -Dcam16UcsToXyz=scalarCam16UcsToXyz
    -DcompressSrgbCam16Ucs=scalarCompressSrgbCam16Ucs -DfinishScanSrgb=scalarFinishScanSrgb)
"$cxx" "${flags[@]}" -UFILMKIT_SPEKTRAFILM_ARM_CAM16 "${renames[@]}" \
    -c "$src/film/spektrafilm/OutputGamut.cpp" -o "$out/scalar-output.o"
"$cxx" "${flags[@]}" -static-libstdc++ "$root/tools/spektrafilm-simd-check.cpp" \
    "$out/scalar-output.o" "${objects[@]}" -o "$out/simd-check"
for check in recipe filter scan-output spectral; do
    "$cxx" "${flags[@]}" -static-libstdc++ "$root/tools/spektrafilm-$check-check.cpp" \
        "${objects[@]}" -o "$out/$check-check"
done
