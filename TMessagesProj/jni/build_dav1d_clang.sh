#!/bin/bash
set -e

function setCurrentPlatform {

	CURRENT_PLATFORM="$(uname -s)"
	case "${CURRENT_PLATFORM}" in
		Darwin*)
			BUILD_PLATFORM=darwin-x86_64
			COMPILATION_PROC_COUNT=`sysctl -n hw.physicalcpu`
			;;
		Linux*)
			BUILD_PLATFORM=linux-x86_64
			COMPILATION_PROC_COUNT=$(nproc)
			;;
		*)
			echo -e "\033[33mWarning! Unknown platform ${CURRENT_PLATFORM}! falling back to linux-x86_64\033[0m"
			BUILD_PLATFORM=linux-x86_64
			COMPILATION_PROC_COUNT=1
			;;
	esac

	echo "Build platform: ${BUILD_PLATFORM}"
	echo "Parallel jobs: ${COMPILATION_PROC_COUNT}"

}

function checkPreRequisites {

	if ! [ -d "dav1d" ] || ! [ "$(ls -A dav1d)" ]; then
		echo -e "\033[31mFailed! Submodule 'dav1d' not found!\033[0m"
		echo -e "\033[31mTry to run: 'git submodule init && git submodule update'\033[0m"
		exit
	fi

	if [ -z "$NDK" -a "$NDK" == "" ]; then
		echo -e "\033[31mFailed! NDK is empty. Run 'export NDK=[PATH_TO_NDK]'\033[0m"
		exit
	fi
}

function build_one {
	echo "Building ${ARCH}..."

	if [[ "${CURRENT_PLATFORM}" == "Darwin"* ]]; then
		PREBUILT=${NDK}/toolchains/llvm/prebuilt/darwin-x86_64
	else
		PREBUILT=${NDK}/toolchains/${PREBUILT_ARCH}${PREBUILT_MIDDLE}-${VERSION}/prebuilt/${BUILD_PLATFORM}
	fi

	PLATFORM=${NDK}/platforms/android-${ANDROID_API}/arch-${ARCH}

	if [[ "${CURRENT_PLATFORM}" == "Darwin"* ]]; then
		TOOLS_PREFIX="${PREBUILT}/bin/${ARCH_NAME}-linux-${BIN_MIDDLE}-"
	else
		TOOLS_PREFIX="${LLVM_BIN}/${ARCH_NAME}-linux-${BIN_MIDDLE}-"
	fi

	LD=${TOOLS_PREFIX}ld
	AR=${TOOLS_PREFIX}ar
	STRIP=${TOOLS_PREFIX}strip
	NM=${TOOLS_PREFIX}nm

	if [[ "${CURRENT_PLATFORM}" == "Darwin"* ]]; then
		CC_PREFIX="${PREBUILT}/bin/${CLANG_PREFIX}-linux-${BIN_MIDDLE}${ANDROID_API}-"
	else
		CC_PREFIX="${LLVM_BIN}/${CLANG_PREFIX}-linux-${BIN_MIDDLE}${ANDROID_API}-"
	fi

	CC=${CC_PREFIX}clang
	CXX=${CC_PREFIX}clang++
	CROSS_PREFIX=${PREBUILT}/bin/${ARCH_NAME}-linux-${BIN_MIDDLE}-

	INCLUDES=" -I${LIBVPXPREFIX}/include"
	LIBS=" -L${LIBVPXPREFIX}/lib"

	echo "Cleaning..."
	rm -f config.h
	make clean || true

	echo "Configuring..."

	meson setup builddir-${ARCH} \
	  --prefix "$PREFIX" \
	  --libdir="lib" \
	  --includedir="include" \
	  --buildtype=release -Denable_tests=false -Denable_tools=false -Ddefault_library=static \
	  --cross-file <(echo "
		[binaries]
		c = '${CC}'
		ar = '${AR}'
		
		[host_machine]
		system = 'android'
		cpu_family = '${MESON_CPU_FAMILY}'
		cpu = '${MESON_CPU}'
		endian = 'little'
	  ")
	ninja -C builddir-${ARCH}
	ninja -C builddir-${ARCH} install
}

setCurrentPlatform
checkPreRequisites

cd dav1d

## common
LLVM_PREFIX="${NDK}/toolchains/llvm/prebuilt/${BUILD_PLATFORM}"
LLVM_BIN="${LLVM_PREFIX}/bin"
VERSION="4.9"
ANDROID_API=21

function build {
	for arg in "$@"; do
		case "${arg}" in
			x86_64)
				ANDROID_API=21

				ARCH=x86_64
				ARCH_NAME=x86_64
				PREBUILT_ARCH=x86_64
				PREBUILT_MIDDLE=
				CLANG_PREFIX=x86_64
				BIN_MIDDLE=android
				CPU=x86_64
				PREFIX="$(pwd)/build/x86_64"
				LIBVPXPREFIX=../libvpx/build/$ARCH_NAME
				ADDITIONAL_CONFIGURE_FLAG="--disable-asm"

				MESON_CPU=x86_64
				MESON_CPU_FAMILY=x86_64

				build_one
			;;
			arm64)
				ANDROID_API=21

				ARCH=arm64
				ARCH_NAME=aarch64
				PREBUILT_ARCH=aarch64
				PREBUILT_MIDDLE="-linux-android"
				CLANG_PREFIX=aarch64
				BIN_MIDDLE=android
				CPU=arm64-v8a
				OPTIMIZE_CFLAGS=
				PREFIX="$(pwd)/build/arm64-v8a"
				LIBVPXPREFIX=../libvpx/build/$CPU
				ADDITIONAL_CONFIGURE_FLAG="--enable-neon --enable-optimizations"

				MESON_CPU=arm64
				MESON_CPU_FAMILY=aarch64

				build_one
			;;
			arm)
				ANDROID_API=16

				ARCH=arm
				ARCH_NAME=arm
				PREBUILT_ARCH=arm
				PREBUILT_MIDDLE="-linux-androideabi"
				CLANG_PREFIX=armv7a
				BIN_MIDDLE=androideabi
				CPU=armv7-a
				OPTIMIZE_CFLAGS="-marm -march=$CPU"
				PREFIX="$(pwd)/build/armeabi-v7a"
				LIBVPXPREFIX=../libvpx/build/armeabi-v7a
				ADDITIONAL_CONFIGURE_FLAG=--enable-neon

				MESON_CPU=armv7
				MESON_CPU_FAMILY=arm

				build_one
			;;
			x86)
				ANDROID_API=16

				ARCH=x86
				ARCH_NAME=i686
				PREBUILT_ARCH=x86
				PREBUILT_MIDDLE=
				CLANG_PREFIX=i686
				BIN_MIDDLE=android
				CPU=i686
				OPTIMIZE_CFLAGS="-march=$CPU"
				PREFIX="$(pwd)/build/x86"
				LIBVPXPREFIX=../libvpx/build/$ARCH
				ADDITIONAL_CONFIGURE_FLAG="--disable-x86asm --disable-inline-asm --disable-asm"

				MESON_CPU=i686
				MESON_CPU_FAMILY=x86

				build_one
			;;
			*)
			;;
		esac
	done
}

if (( $# == 0 )); then
	build x86_64 x86 arm arm64
else
	build $@
fi