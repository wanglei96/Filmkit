#!/usr/bin/env bash
# Build :app:assembleRelease -PfilmkitHdr=true before running on an idle USB device.
set -euo pipefail
cd "$(dirname "$0")/.."
out=${1:?provide a fresh output directory}
mkdir -p "$out"
python3 tools/prepare-spektrafilm-regression-dng.py "$out/fixtures"
sdk=${ANDROID_HOME:-$HOME/Library/Android/sdk}
adb="$sdk/platform-tools/adb"
ndk="$sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt"
host="$ndk/darwin-x86_64"
[[ -d "$host" ]] || host="$ndk/linux-x86_64"
lib=${LIBRARY:-app/build/filmengine/arm64-v8a/libfilmengine.so}
assets=app/src/main/assets/spektrafilm
"$host/bin/aarch64-linux-android26-clang++" -std=c++20 -O2 tools/check-renderer-device.cpp \
    "$lib" -lEGL -lGLESv3 -llog -o "$out/replay"
if "$adb" shell dumpsys window | grep 'mCurrentFocus=.*com.filmkit.app'; then
    echo 'FilmKit is foreground; leave the camera before controlled replay' >&2
    exit 4
fi
remote=$("$adb" shell mktemp -d /data/local/tmp/filmkit-dng-vk.XXXXXXXX)
[[ $remote =~ ^/data/local/tmp/filmkit-dng-vk\.[[:alnum:]]{8}$ ]]
trap '"$adb" shell rm -r "$remote"' EXIT
"$adb" shell "mkdir '$remote/raw' '$remote/result'"
"$adb" push "$out/replay" "$lib" "$assets/scan-cam16-v1.fkcam16" \
    "$host/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$remote/"
"$adb" shell "cd '$remote'; chmod 700 replay; sha256sum libfilmengine.so scan-cam16-v1.fkcam16" > "$out/libraries.sha256"
for scene in ${SCENES:-110832 110846 110904 110936}; do
    fixture="$out/fixtures/$scene"
    "$adb" push "$fixture/f0.raw" "$fixture/replay.txt" "$remote/raw/"
    "$adb" shell "cd '$remote/raw' && sha256sum f0.raw replay.txt" > "$fixture/device-input.sha256"
    grep -F "$(jq -er .mosaicSha256 "$fixture/manifest.json")  f0.raw" "$fixture/device-input.sha256"
    grep -F "$(jq -er .replaySha256 "$fixture/manifest.json")  replay.txt" "$fixture/device-input.sha256"
    for recipe in ${RECIPES:-kodak_gold_200--kodak_portra_endura fujifilm_provia_100f--direct}; do
        row=$(jq -er --arg r "$recipe" '.profiles[]|select(.id==$r)|[.asset,.response]|@tsv' "$assets/catalog.json")
        IFS=$'\t' read -r profile response <<< "$row"
        "$adb" push "$assets/$profile" "$assets/$response" "$remote/"
        for bits in ${BITS:-8 10}; do
            i=0
            for backend in ${ORDER:-A B}; do
                label="$out/$scene-$recipe-$bits-$i-$backend"
                mkdir "$label"
                flags=""
                [[ $bits != 10 ]] || flags="$flags --ten-bit"
                if [[ $backend == B ]]; then flags="$flags --spektrafilm-vulkan"; fi
                start=$SECONDS
                until "$adb" shell dumpsys thermalservice > "$label/thermal-before.txt" &&
                    grep -q 'Thermal Status: 0' "$label/thermal-before.txt"; do
                    ((SECONDS - start < 120)) || exit 3
                    sleep 5
                done
                "$adb" shell "cd '$remote' && unset FILMKIT_VULKAN_HANATOS; LD_LIBRARY_PATH=. ./replay $flags \
                    ${EXTRA_FLAGS:-} --single-raw --no-stage-sync --warmup --ae-hal --pc3d ${PC3D:-0} \
                    --scan-cam16 scan-cam16-v1.fkcam16 --raw-directory raw --output result \
                    --spektrafilm '$profile' '$response'" > "$label/render.log" 2>&1
                grep -q 'PASS full production FrameRenderer RAW single' "$label/render.log"
                if [[ $backend == B ]]; then
                    grep -Eq '"event":"spektrafilm.vulkan_hanatos","kv":\{"pixels":12533760,"totalPixels":12533760.*"inputFormat":[12]' "$label/render.log"
                elif grep -q '"event":"spektrafilm.vulkan_hanatos"' "$label/render.log"; then
                    echo "CPU control unexpectedly used Vulkan: $label" >&2
                    exit 5
                fi
                "$adb" shell dumpsys thermalservice > "$label/thermal-after.txt"
                grep -q 'Thermal Status: 0' "$label/thermal-after.txt"
                "$adb" pull "$remote/result/render.rgba" "$remote/result/render.gainmap" "$remote/result/render.jpg" "$label/"
                printf '%s PASS\n' "$label"
                i=$((i+1))
            done
        done
    done
done
