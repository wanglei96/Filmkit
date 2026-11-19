#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 4 || $# -gt 6 ]]; then
    printf 'Usage: bash %s PYTHON UPSTREAM DNG NEW_OUTPUT_DIR [ANDROID_CLANG++ [ARM_MATH_ROOT]]\n' "$0" >&2
    exit 2
fi
root=$(cd "$(dirname "$0")/.." && pwd)
src=$root/core/engine/src
scratch=$(mktemp -d "${TMPDIR:-/tmp}/filmkit-spektra-render.XXXXXXXX")
remote=
adb=${ADB:-adb}
cleanup() {
    if [[ -n $remote ]]; then "$adb" shell "rm -rf '$remote'" || true; fi
    rm -rf -- "$scratch"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir "$4"
out=$(cd "$4" && pwd)
options=("$2" "$3" "$scratch/input.bin")
if [[ ${ORACLE:-0} == 1 ]]; then options+=(--oracle); fi
PYTHONDONTWRITEBYTECODE=1 NUMBA_CACHE_DIR="$scratch/numba" \
    "$1" "$root/tools/spektrafilm-render-fixture.py" "${options[@]}" | tee "$out/input.json"
cxx=${5:-${CXX:-clang++}}
flags=(-std=c++20 -O3 -ffp-contract=off -Wall -Wextra -pthread -I"$src")
objects=()
if [[ $# -ge 5 ]]; then flags+=(-static-libstdc++); fi
if [[ $# == 6 ]]; then
    arm=$6
    cc=${cxx%++}
    flags+=(-DFILMKIT_SPEKTRAFILM_ARM_EXP10)
    "$cc" -O3 -I"$arm/math/include" -I"$arm/math" -I"$arm/math/aarch64/advsimd" \
        -c "$arm/math/aarch64/advsimd/exp10.c" -o "$scratch/exp10.o"
    "$cc" -O3 -I"$arm/math" -c "$arm/math/aarch64/v_exp_data.c" -o "$scratch/v_exp_data.o"
    objects+=("$scratch/exp10.o" "$scratch/v_exp_data.o")
    git -C "$arm" rev-parse HEAD > "$out/arm-math-revision.txt"
fi
for source in "$src"/film/spektrafilm/*.cpp; do
    object=$scratch/$(basename "$source" .cpp).o
    "$cxx" "${flags[@]}" -c "$source" -o "$object"
    objects+=("$object")
done
"$cxx" "${flags[@]}" "$root/tools/spektrafilm-render-bench.cpp" "${objects[@]}" -o "$scratch/render"
"$cxx" "${flags[@]}" "$root/tools/spektrafilm-filter-check.cpp" \
    "$scratch/SpatialFilter.o" -o "$scratch/filter-check"
"$cxx" --version > "$out/compiler.txt"
if [[ $# -ge 5 ]]; then
    remote=$("$adb" shell mktemp -d /data/local/tmp/filmkit-spektra-render.XXXXXXXX)
    [[ $remote =~ ^/data/local/tmp/filmkit-spektra-render\.[[:alnum:]]{8}$ ]] || { remote=; exit 2; }
    "$adb" push "$scratch/render" "$scratch/filter-check" "$scratch/input.bin" "$remote/"
    "$adb" shell "chmod 700 '$remote/render' '$remote/filter-check'"
    device_hash=$("$adb" shell "sha256sum '$remote/input.bin'")
    printf '%s\n' "$device_hash" | tee "$out/device-input-sha256.txt"
    expected_hash=$("$1" -c 'import json, sys; print(json.load(open(sys.argv[1]))["fixture_sha256"])' "$out/input.json")
    [[ ${device_hash%% *} == "$expected_hash" ]]
    "$adb" shell "'$remote/filter-check'" | tee "$out/filter-check.log"
else
    "$scratch/filter-check" | tee "$out/filter-check.log"
fi
i=0
for workers in ${WORKERS:-4 8 8 4}; do
    [[ $workers =~ ^[1-8]$ ]] || exit 2
    for filter_workers in ${FILTER_WORKERS:-1}; do
        [[ $filter_workers =~ ^[1-8]$ ]] || exit 2
        if [[ $# -ge 5 ]]; then
            result_path=$remote/current.rgb
            result=
            if [[ ${VERIFY_FILTERS:-0} == 1 ]]; then result="'$result_path'"; fi
            "$adb" shell dumpsys thermalservice > "$out/thermal-before-$i.log"
            "$adb" shell "'$remote/render' '$remote/input.bin' '$workers' '$workers' '$workers' '$filter_workers' $result" | tee "$out/run-$i.log"
            "$adb" shell dumpsys thermalservice > "$out/thermal-after-$i.log"
            if [[ ${VERIFY_FILTERS:-0} == 1 ]]; then
                if [[ $i == 0 ]]; then
                    "$adb" shell "mv '$result_path' '$remote/baseline.rgb'"
                else
                    "$adb" shell "cmp '$remote/baseline.rgb' '$result_path' && rm '$result_path'"
                    printf 'run=%s: full FP64 output byte-identical to run=0\n' "$i" | tee -a "$out/comparisons.log"
                fi
            fi
        else
            arguments=("$workers" "$workers" "$workers" "$filter_workers")
            if [[ ${VERIFY_FILTERS:-0} == 1 ]]; then arguments+=("$scratch/current.rgb"); fi
            "$scratch/render" "$scratch/input.bin" "${arguments[@]}" | tee "$out/run-$i.log"
            if [[ ${VERIFY_FILTERS:-0} == 1 ]]; then
                if [[ $i == 0 ]]; then
                    mv "$scratch/current.rgb" "$scratch/baseline.rgb"
                else
                    cmp "$scratch/baseline.rgb" "$scratch/current.rgb"
                    rm "$scratch/current.rgb"
                    printf 'run=%s: full FP64 output byte-identical to run=0\n' "$i" | tee -a "$out/comparisons.log"
                fi
            fi
        fi
        i=$((i + 1))
    done
done
