#!/bin/bash
#
# F-Droid prebuild step.
#
# The fdroiddata recipe invokes this as
#     ../prebuild_fdroid.sh <additional-build-number> <app-id> <app-hash>
# with the app module (the recipe's `subdir:`) as the working directory.
#
# Everything the recipe used to spell out as a list of `sed` commands lives here instead: a new
# build entry then stays short, and the patching travels in the same commit as the sources it
# patches, so a change to the jni build scripts can never drift away from the sed that fixes them.

set -eu

if [ ! -d ../TMessagesProj/jni ]; then
    echo "prebuild_fdroid.sh: run me from the app module directory (the recipe's subdir:)" >&2
    exit 1
fi

vars=../gradle.properties

# Java 21, and drop what cannot ship on F-Droid (Play Services, GMS captcha).
sed -i -e '/JavaVersion/s/17/21/' {../TMessagesProj,.}/build.gradle
sed -i -e '/play/d' ../TMessagesProj/build.gradle
sed -i -e '/gms/d' ../TMessagesProj/src/main/java/org/telegram/messenger/CaptchaController.java

# Build flags the recipe passes in.
echo "DUMMY_CONST=0" >> $vars
echo "ADDITIONAL_BUILD_NUMBER=$1" >> $vars
echo "APP_ID=$2" >> $vars
echo "APP_HASH=$3" >> $vars
echo "F_DROID=1" >> $vars
echo "org.gradle.workers.max=1" >> $vars

# The native build scripts still target API 16; the F-Droid NDK needs 21.
sed -i -e 's/ANDROID_API=16/ANDROID_API=21/g' ../TMessagesProj/jni/build_dav1d_clang.sh ../TMessagesProj/jni/build_ffmpeg_clang.sh
sed -i -e 's/API=16/API=21/g' ../TMessagesProj/jni/build_boringssl.sh

# Point the toolchain at LLVM instead of the retired GNU binutils prefixes.
sed -i -e 's|${TOOLS_PREFIX}ld|${LLVM_BIN}/ld.lld|g' -e 's|${TOOLS_PREFIX}ar|${LLVM_BIN}/llvm-ar|g' -e 's|${TOOLS_PREFIX}strip|${LLVM_BIN}/llvm-strip|g' -e 's|${TOOLS_PREFIX}nm|${LLVM_BIN}/llvm-nm|g' -e 's|${TOOLS_PREFIX}ranlib|${LLVM_BIN}/llvm-ranlib|g' ../TMessagesProj/jni/build_dav1d_clang.sh ../TMessagesProj/jni/build_libvpx_clang.sh ../TMessagesProj/jni/build_ffmpeg_clang.sh
sed -i -e 's|CROSS_PREFIX=${PREBUILT}/bin/${ARCH_NAME}-linux-${BIN_MIDDLE}-|CROSS_PREFIX=${LLVM_BIN}/llvm-|' ../TMessagesProj/jni/build_dav1d_clang.sh ../TMessagesProj/jni/build_libvpx_clang.sh ../TMessagesProj/jni/build_ffmpeg_clang.sh
sed -i -e '/export ISYSTEM=/d' -e '/--libc=/d' -e 's/-std=c++11/-std=c++17/g' ../TMessagesProj/jni/build_libvpx_clang.sh

# prepare.py resets tde2e from git; re-apply the NDK/API bump right after it does.
sed -i -e 's|git checkout -- tde2e/|& \&\& sed -i -e "s/23.2.8568313/27.2.12479018/g" -e "s/android-16/android-21/g" tde2e/build-tdlib.sh|' ../TMessagesProj/jni/prepare.py
