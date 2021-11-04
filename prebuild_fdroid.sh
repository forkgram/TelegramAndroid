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
sed -i -e '/com\.google\.android\.play/d' -e '/play-services/d' ../TMessagesProj/build.gradle
sed -i -e '/gms/d' ../TMessagesProj/src/main/java/org/telegram/messenger/CaptchaController.java

# Build flags the recipe passes in.
echo "DUMMY_CONST=0" >> $vars
echo "ADDITIONAL_BUILD_NUMBER=$1" >> $vars
echo "APP_ID=$2" >> $vars
echo "APP_HASH=$3" >> $vars
echo "F_DROID=1" >> $vars
echo "org.gradle.workers.max=1" >> $vars

# tlottie's build script wants `stable` by name; the recipe pins its own toolchain.
# prepare.py does `git checkout -- prebuild` right before building, so patch prepare.py.
rust_toolchain=$(rustup show active-toolchain 2>/dev/null | awk 'NR == 1 { print $1 }')
if ! rustup run "$rust_toolchain" rustc --version >/dev/null 2>&1; then
    rustup default stable
    rust_toolchain=$(rustup show active-toolchain | awk 'NR == 1 { print $1 }')
    rustup run "$rust_toolchain" rustc --version >/dev/null
fi
rustup target add --toolchain "$rust_toolchain" \
    aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
prep=../TMessagesProj/jni/prepare.py
grep -q '\./prebuild/build_all\.sh' $prep || { echo "$prep: prebuild entry point moved" >&2; exit 1; }
sed -i -e "s|\./prebuild/build_all\.sh|TLOTTIE_RUST_TOOLCHAIN=$rust_toolchain &|" $prep
