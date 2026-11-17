#!/bin/bash
set -euo pipefail

cd "$(dirname "$0")/.."
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
cxx="$sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang++"
adb="$sdk/platform-tools/adb"
build="${FILMKIT_NATIVE_BUILD:-app/build/filmengine/arm64-v8a}"
work="$(mktemp -d /tmp/filmkit-ultrahdr.XXXXXX)"
trap 'rm -rf "$work"' EXIT
flags=(-std=c++20 -O3 -DNDEBUG -Wall -Wextra -Werror)
jpeg_includes=(-I"$build/libjpeg-turbo/src/src" -I"$build/libjpeg-turbo/build")

# Preserve an explicit baseline when testing a revision whose HEAD already contains the change.
if [[ -n "${ULTRAHDR_REFERENCE_SOURCE:-}" ]]; then
  cp "$ULTRAHDR_REFERENCE_SOURCE" "$work/reference.cpp"
else
  git show HEAD:core/engine/src/jni/UltraHdrEncoder.cpp > "$work/reference.cpp"
fi
"$cxx" "${flags[@]}" -Icore/engine/src/jni -Icore/engine/third_party/ultrahdr \
  -DJava_com_filmkit_app_EngineBridge_nativeEncodeUltraHdr=referenceEncode \
  -c "$work/reference.cpp" -o "$work/reference.o"
"$cxx" "${flags[@]}" -Icore/engine/third_party/ultrahdr \
  -DJava_com_filmkit_app_EngineBridge_nativeEncodeUltraHdr=candidateEncode \
  -c core/engine/src/jni/UltraHdrEncoder.cpp -o "$work/candidate.o"
"$cxx" "${flags[@]}" "${jpeg_includes[@]}" -DFILMKIT_HDR \
  -DencodeJpegTurbo444=encodeJpegTurbo444Real \
  -c core/engine/src/jni/JpegEncode.cpp -o "$work/jpeg.o"
"$cxx" "${flags[@]}" "${jpeg_includes[@]}" -Wno-missing-field-initializers \
  tools/check-ultrahdr-input.cpp "$work/reference.o" "$work/candidate.o" "$work/jpeg.o" \
  "$build/libuhdr_core.a" "$build/libuhdr_image_io.a" \
  "$build/libjpeg-turbo/build/libjpeg.a" -llog -o "$work/check-ultrahdr-input"
"$adb" push "$work/check-ultrahdr-input" /data/local/tmp/filmkit-check-ultrahdr-input
"$adb" shell /data/local/tmp/filmkit-check-ultrahdr-input "$@"
