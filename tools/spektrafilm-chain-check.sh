#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 3 && $# != 4 ]]; then
    printf 'Usage: bash %s PYTHON UPSTREAM D0_SIDECAR_OR_DNG [ANDROID_CLANG++]\n' "$0" >&2
    exit 2
fi
root=$(cd "$(dirname "$0")/.." && pwd)
tools=$root/tools
src=$root/core/engine/src
engine=$src/film/spektrafilm
scratch=$(mktemp -d "${TMPDIR:-/tmp}/filmkit-spektra-chain.XXXXXXXX")
remote=
cleanup() {
    if [[ -n $remote ]]; then "$adb" shell "rm -rf '$remote'" || true; fi
    rm -rf -- "$scratch"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
python=$1
upstream=$2
input=$3
scan_options=(--chain)
scan_output=
full=false
if [[ ${input##*.} == [dD][nN][gG] ]]; then
    full=true
    : "${AB_OUTPUT:?Set AB_OUTPUT to a new comparison JPEG path for a DNG run}"
    [[ ! -e $AB_OUTPUT ]]
    scan_options+=(--full-rgb "$scratch/reference.npy")
    scan_output=native-rgb
fi

export PYTHONDONTWRITEBYTECODE=1
export NUMBA_CACHE_DIR=$scratch/numba
"$python" "$tools/spektrafilm-film-exposure-fixture.py" "$upstream" "$input" "$scratch/film-exposure.bin" --chain-log "$scratch/log.npy"
"$python" "$tools/spektrafilm-dir-fixture.py" "$upstream" "$scratch/log.npy" "$scratch/dir.bin" chain
"$python" "$tools/spektrafilm-grain-fixture.py" "$upstream" "$scratch/log.npy" "$scratch/grain.bin" portra --chain-cmy "$scratch/cmy.npy"
"$python" "$tools/spektrafilm-print-scan-fixture.py" "$upstream" "$scratch/cmy.npy" "$scratch/print-scan.bin" "${scan_options[@]}"
rm -- "$scratch/log.npy" "$scratch/cmy.npy"

build() {
    local cxx=$1 out=$2
    shift 2
    mkdir "$out"
    local file module
    local objects=()
    for file in "$engine"/*.cpp; do
        module=$(basename "$file" .cpp)
        "$cxx" -std=c++20 -O3 -ffp-contract=off -I"$src" -c "$file" -o "$out/$module.o"
        objects+=("$out/$module.o")
    done
    for module in film-exposure dir grain print-scan; do
        "$cxx" -std=c++20 -O3 -ffp-contract=off -pthread -I"$src" "$tools/spektrafilm-$module-check.cpp" \
            "${objects[@]}" "$@" -o "$out/$module"
    done
}

if [[ $# == 3 || $full == false ]]; then
    build "${CXX:-clang++}" "$scratch/host"
    printf '\nHost (scalar spectral backend)\n'
    "$scratch/host/film-exposure" "$scratch/film-exposure.bin" "$scratch/native-log"
    "$scratch/host/dir" "$scratch/dir.bin" "$scratch/native-log" "$scratch/native-dir"
    rm -- "$scratch/native-log"
    "$scratch/host/grain" "$scratch/grain.bin" "$scratch/native-dir" "$scratch/native-cmy"
    rm -- "$scratch/native-dir"
    host_scan=("$scratch/host/print-scan" "$scratch/print-scan.bin" "$scratch/native-cmy")
    if [[ $full == true ]]; then host_scan+=("$scratch/$scan_output"); fi
    "${host_scan[@]}"
fi

if [[ $# == 4 ]]; then
    adb=${ADB:-adb}
    build "$4" "$scratch/android" -static-libstdc++
    remote=$("$adb" shell mktemp -d /data/local/tmp/filmkit-spektra-chain.XXXXXXXX)
    [[ $remote =~ ^/data/local/tmp/filmkit-spektra-chain\.[[:alnum:]]{8}$ ]] || { remote=; exit 2; }
    for module in film-exposure dir grain print-scan; do
        "$adb" push "$scratch/android/$module" "$scratch/$module.bin" "$remote/"
    done
    printf '\nAndroid (scalar spectral backend)\n'
    "$adb" shell "cd '$remote' && chmod 700 film-exposure dir grain print-scan &&
        ./film-exposure film-exposure.bin native-log &&
        ./dir dir.bin native-log native-dir && rm native-log &&
        ./grain grain.bin native-dir native-cmy && rm native-dir &&
        ./print-scan print-scan.bin native-cmy $scan_output"
    if [[ $full == true ]]; then
        "$adb" pull "$remote/native-rgb" "$scratch/native-rgb"
    fi
fi
if [[ $full == true ]]; then
    "$python" "$tools/spektrafilm-chain-preview.py" "$scratch/reference.npy" "$scratch/native-rgb" "$AB_OUTPUT"
fi
