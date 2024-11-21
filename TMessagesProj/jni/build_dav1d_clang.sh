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

setCurrentPlatform
checkPreRequisites

cd dav1d

## common
LLVM_PREFIX="${NDK}/toolchains/llvm/prebuilt/linux-x86_64"
LLVM_BIN="${LLVM_PREFIX}/bin"
VERSION="4.9"
ANDROID_API=21

function build {
	for arg in "$@"; do
		case "${arg}" in
			x86_64)
				ARCH=x86_64
				PREFIX="$(pwd)/build/"

				echo "Building ${ARCH}..."
				echo "Configuring..."

				meson setup builddir-x86_64 \
				  --prefix "$PREFIX/x86_64" \
				  --libdir="lib" \
				  --includedir="include" \
				  --buildtype=release -Denable_tests=false -Denable_tools=false -Ddefault_library=static \
				  --cross-file <(echo "
					[binaries]
					c = '${NDK}/toolchains/llvm/prebuilt/darwin-x86_64/bin/x86_64-linux-android21-clang'
					ar = '${NDK}/toolchains/llvm/prebuilt/darwin-x86_64/bin/x86_64-linux-android-ar'
					
					[host_machine]
					system = 'android'
					cpu_family = 'x86_64'
					cpu = 'x86_64'
					endian = 'little'
				  ")
				ninja -C builddir-x86_64
				ninja -C builddir-x86_64 install
			;;
			x86)
				ARCH=x86
				PREFIX="$(pwd)/build/"

				echo "Building ${ARCH}..."
				echo "Configuring..."

				meson setup builddir-x86 \
				  --prefix "$PREFIX/x86" \
				  --libdir="lib" \
				  --includedir="include" \
				  --buildtype=release -Denable_tests=false -Denable_tools=false -Ddefault_library=static \
				  --cross-file <(echo "
					[binaries]
					c = '${NDK}/toolchains/llvm/prebuilt/darwin-x86_64/bin/i686-linux-android21-clang'
					ar = '${NDK}/toolchains/llvm/prebuilt/darwin-x86_64/bin/i686-linux-android-ar'
					
					[host_machine]
					system = 'android'
					cpu_family = 'x86'
					cpu = 'i686'
					endian = 'little'
				  ")
				ninja -C builddir-x86
				ninja -C builddir-x86 install
			;;
			arm64)
				ARCH=arm64
				PREFIX="$(pwd)/build/"

				echo "Building ${ARCH}..."
				echo "Configuring..."

				meson setup builddir-arm64 \
				  --prefix "$PREFIX/arm64-v8a" \
				  --libdir="lib" \
				  --includedir="include" \
				  --buildtype=release -Denable_tests=false -Denable_tools=false -Ddefault_library=static \
				  --cross-file <(echo "
					[binaries]
					c = '${NDK}/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android21-clang'
					ar = '${NDK}/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android-ar'
					
					[host_machine]
					system = 'android'
					cpu_family = 'aarch64'
					cpu = 'arm64'
					endian = 'little'
				  ")
				ninja -C builddir-arm64
				ninja -C builddir-arm64 install

			;;
			arm)
				ARCH=arm
				PREFIX="$(pwd)/build/"

				echo "Building ${ARCH}..."
				echo "Configuring..."

				meson setup builddir-armv7 \
				  --prefix "$PREFIX/armeabi-v7a" \
				  --libdir="lib" \
				  --includedir="include" \
				  --buildtype=release -Denable_tests=false -Denable_tools=false -Ddefault_library=static \
				  --cross-file <(echo "
					[binaries]
					c = '${NDK}/toolchains/llvm/prebuilt/darwin-x86_64/bin/armv7a-linux-androideabi21-clang'
					ar = '${NDK}/toolchains/llvm/prebuilt/darwin-x86_64/bin/arm-linux-androideabi-ar'
					
					[host_machine]
					system = 'android'
					cpu_family = 'arm'
					cpu = 'armv7'
					endian = 'little'
				  ") \
				  -Dc_args="-DDAV1D_NO_GETAUXVAL"
				ninja -C builddir-armv7
				ninja -C builddir-armv7 install
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
