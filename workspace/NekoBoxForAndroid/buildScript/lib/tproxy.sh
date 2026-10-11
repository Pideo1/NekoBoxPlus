#!/usr/bin/env bash
set -euo pipefail

# Run from libcore, with the same NDK/toolchain as the AAR build.
llvm="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
for abi in armeabi-v7a arm64-v8a x86 x86_64; do
    case "$abi" in
        armeabi-v7a) arch=arm; compiler=armv7a-linux-androideabi23-clang ;;
        arm64-v8a) arch=arm64; compiler=aarch64-linux-android23-clang ;;
        x86) arch=386; compiler=i686-linux-android23-clang ;;
        x86_64) arch=amd64; compiler=x86_64-linux-android23-clang ;;
    esac
    output="../app/src/main/jniLibs/$abi"
    mkdir -p "$output"
    CGO_ENABLED=1 GOOS=android GOARCH="$arch" GOARM=7 CC="$llvm/$compiler" \
        go build -buildmode=pie -trimpath -ldflags='-s -w' \
        -o "$output/libnb4atproxy.so" ./cmd/nb4a-tproxy
done
