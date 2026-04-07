FROM ubuntu:22.04 AS base

ENV DEBIAN_FRONTEND=noninteractive

# ============================================================
# System packages
# ============================================================
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jdk-headless \
    python3 python3-pip \
    git \
    wget \
    unzip \
    ninja-build \
    make \
    yasm \
    nasm \
    meson \
    pkg-config \
    patch \
    perl \
    autoconf \
    automake \
    libtool \
    golang-go \
    gperf \
    php-cli \
    libssl-dev \
    g++ \
    && rm -rf /var/lib/apt/lists/*

# ============================================================
# Environment
# ============================================================
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_NDK_HOME=/opt/android-sdk/ndk/21.4.7075529
ENV ANDROID_SDK=/opt/android-sdk
ENV ANDROID_NDK=/opt/android-sdk/ndk/21.4.7075529
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

# ============================================================
# Android command-line tools
# ============================================================
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
         -O /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_HOME}/cmdline-tools && \
    mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm /tmp/cmdline-tools.zip

# ============================================================
# SDK components (SDK 35, Build Tools 35, NDK 21.4, CMake 3.10)
# ============================================================
RUN yes | sdkmanager --licenses > /dev/null 2>&1 && \
    sdkmanager \
      "platforms;android-35" \
      "build-tools;35.0.0" \
      "ndk;21.4.7075529" \
      "ndk;23.2.8568313" \
      "cmake;3.10.2.4988404"

# Make SDK cmake available as system cmake
RUN ln -sf /opt/android-sdk/cmake/3.10.2.4988404/bin/cmake /usr/local/bin/cmake && \
    ln -sf /opt/android-sdk/cmake/3.10.2.4988404/bin/cmake /usr/local/bin/cmake3

# ============================================================
# Stage 2: Build native libraries
# ============================================================
FROM base AS native-builder

COPY TMessagesProj/jni/ /build/jni/
WORKDIR /build/jni

# Phase 1: Build static libraries directly (bypasses prepare.py which
# assumes git submodules and rm/re-checkout cycle unsuitable for Docker).
ENV NDK=$ANDROID_NDK_HOME
ENV NINJA_PATH=/usr/bin/ninja

RUN ./build_dav1d_clang.sh arm64 arm

RUN ./build_libvpx_clang.sh arm64 arm

RUN ./build_ffmpeg_clang.sh arm64 arm && ./patch_ffmpeg.sh

RUN ./patch_boringssl.sh && ./build_boringssl.sh arm64 arm

RUN tde2e_dir=$(pwd)/tde2e && \
    source_dir=$(pwd)/tde2e_source && \
    boringssl_dir=$(pwd)/boringssl && \
    for arch in arm64-v8a armeabi-v7a; do \
      mkdir -p $source_dir/example/android/third-party/openssl/$arch/lib/ && \
      cp "$boringssl_dir/build/$arch/crypto/libcrypto.a" "$source_dir/example/android/third-party/openssl/$arch/lib/" && \
      cp "$boringssl_dir/build/$arch/ssl/libssl.a" "$source_dir/example/android/third-party/openssl/$arch/lib/" && \
      cp -R "$boringssl_dir/include" "$source_dir/example/android/third-party/openssl/$arch/"; \
    done && \
    cp "$tde2e_dir/build-tdlib.sh" "$source_dir/example/android/." && \
    cp "$tde2e_dir/CMakeLists.txt" "$source_dir/example/android/." && \
    sed -i "s/ php//g" "$source_dir/example/android/check-environment.sh" && \
    sed -i "s/PHP_EXECUTABLE/FALSE/g" "$source_dir/td/generate/CMakeLists.txt" && \
    cd "$source_dir/example/android" && \
    SED_CMDS="s/x86_64//g;s/x86//g;s/rm tdlib/# rm tdlib/g;s/jar/# jar/g;s/mv tdlib/# mv tdlib/g;" && \
    sed "$SED_CMDS" ./build-tdlib.sh | bash -s -- "$NDK/../.." && \
    for arch in arm64-v8a armeabi-v7a; do \
      mkdir -p $tde2e_dir/$arch && \
      cp "$source_dir/example/android/build-$arch-Java/td/tde2e/libtde2e.a" $tde2e_dir/$arch && \
      cp "$source_dir/example/android/build-$arch-Java/td/tdutils/libtdutils.a" $tde2e_dir/$arch; \
    done

# Collect Phase 1 artifacts (.a files) for CI reuse
RUN mkdir -p /prebuilt && \
    for lib in boringssl ffmpeg libvpx dav1d tde2e; do \
      if [ -d "/build/jni/$lib/build" ]; then \
        mkdir -p /prebuilt/$lib && cp -r /build/jni/$lib/build /prebuilt/$lib/; \
      fi; \
    done && \
    for arch in arm64-v8a armeabi-v7a; do \
      if [ -d "/build/jni/tde2e/$arch" ]; then \
        mkdir -p /prebuilt/tde2e/$arch && cp /build/jni/tde2e/$arch/*.a /prebuilt/tde2e/$arch/; \
      fi; \
    done

# ============================================================
# Stage 3: Final image (tools + prebuilt Phase 1 .a files)
# ============================================================
FROM base AS final

COPY --from=native-builder /prebuilt/ /prebuilt/

WORKDIR /project
