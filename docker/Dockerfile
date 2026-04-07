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

RUN ln -sf /opt/android-sdk/cmake/3.10.2.4988404/bin/cmake /usr/local/bin/cmake && \
    ln -sf /opt/android-sdk/cmake/3.10.2.4988404/bin/cmake /usr/local/bin/cmake3

# ============================================================
# Stage 2: Build native libraries
# ============================================================
FROM base AS native-builder

COPY TMessagesProj/jni/ /build/jni/
WORKDIR /build/jni

# Phase 1: prepare.py (boringssl, ffmpeg, libvpx, dav1d, tde2e)
RUN python3 prepare.py arm64 arm ndk=$ANDROID_NDK_HOME silent

# Phase 2: CMake build of libtmessages.49.so per ABI
RUN for abi in arm64-v8a armeabi-v7a; do \
      mkdir -p /build/cmake-build/$abi && \
      cd /build/cmake-build/$abi && \
      cmake \
        -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
        -DCMAKE_BUILD_TYPE=Release \
        -DANDROID_ABI=$abi \
        -DANDROID_STL=c++_static \
        -DANDROID_PLATFORM=android-21 \
        /build/jni/ && \
      make -j$(nproc); \
    done

# Collect .so files
RUN mkdir -p /prebuilt/arm64-v8a /prebuilt/armeabi-v7a && \
    cp /build/cmake-build/arm64-v8a/lib*.so /prebuilt/arm64-v8a/ && \
    cp /build/cmake-build/armeabi-v7a/lib*.so /prebuilt/armeabi-v7a/

# ============================================================
# Stage 3: Final image (tools + prebuilt .so)
# ============================================================
FROM base AS final

COPY --from=native-builder /prebuilt/ /prebuilt/

WORKDIR /project
