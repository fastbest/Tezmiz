#!/usr/bin/env bash
# ntfs-3g projesinin mkntfs aracını Android için derler ve jniLibs altına koyar.
# ntfs-3g GPL-2.0 lisanslıdır: https://github.com/tuxera/ntfs-3g
set -euo pipefail
VER=2022.10.3
OUT="${1:-$PWD/app/src/main/jniLibs}"
NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}"
[ -d "$NDK" ] || { echo "NDK bulunamadı"; exit 1; }
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

work=$(mktemp -d)
curl -fsSL "https://github.com/tuxera/ntfs-3g/archive/refs/tags/$VER.tar.gz" | tar -xz -C "$work"
src="$work/ntfs-3g-$VER"
(cd "$src" && ./autogen.sh)

for abi in arm64-v8a armeabi-v7a x86_64 x86; do
  case $abi in
    arm64-v8a)   host=aarch64-linux-android;    cc=aarch64-linux-android21-clang ;;
    armeabi-v7a) host=arm-linux-androideabi;    cc=armv7a-linux-androideabi21-clang ;;
    x86_64)      host=x86_64-linux-android;     cc=x86_64-linux-android21-clang ;;
    x86)         host=i686-linux-android;       cc=i686-linux-android21-clang ;;
  esac
  b="$work/build-$abi"
  mkdir -p "$b"
  (
    cd "$b"
    "$src/configure" --host="$host" CC="$TC/$cc" AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" \
      STRIP="$TC/llvm-strip" CFLAGS="-O2 -fPIE" LDFLAGS="-pie" \
      --disable-ntfs-3g --disable-crypto --disable-plugins --disable-shared --enable-static \
      --without-uuid --without-hd --disable-mtab --disable-ldconfig
    make -j"$(nproc)" -C include
    make -j"$(nproc)" -C libntfs-3g
    make -j"$(nproc)" -C ntfsprogs mkntfs
    mkdir -p "$OUT/$abi"
    "$TC/llvm-strip" -o "$OUT/$abi/libmkntfs.so" ntfsprogs/mkntfs
  )
done
ls -la "$OUT"/*/
