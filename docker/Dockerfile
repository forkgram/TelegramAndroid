FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive
ENV TZ=UTC
ENV LC_ALL=C.UTF-8
ENV LANG=C.UTF-8

# ============================================================
# System packages
# ============================================================
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jdk-headless \
    python3 \
    git \
    wget \
    unzip \
    ninja-build \
    cmake \
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
# NDK 27.2 is used both by the native dependency build scripts in TMessagesProj/jni
# and by gradle's externalNativeBuild (see TMessagesProj/build.gradle).
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_NDK_HOME=/opt/android-sdk/ndk/27.2.12479018
ENV ANDROID_SDK=/opt/android-sdk
ENV ANDROID_NDK=/opt/android-sdk/ndk/27.2.12479018
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
# SDK components (SDK 36, Build Tools 36, NDK 27.2, CMake 3.22.1)
# ============================================================
RUN yes | sdkmanager --licenses > /dev/null 2>&1 && \
    sdkmanager \
      "platforms;android-36" \
      "build-tools;36.0.0" \
      "ndk;27.2.12479018" \
      "cmake;3.22.1"

# ============================================================
# Rust toolchain (tlottie is a Rust staticlib, see jni/prebuild/build_tlottie.sh)
# ============================================================
ENV RUSTUP_HOME=/opt/rustup
ENV CARGO_HOME=/opt/cargo
ENV PATH="${CARGO_HOME}/bin:${PATH}"
RUN wget -qO- https://sh.rustup.rs | sh -s -- -y --no-modify-path --profile minimal --default-toolchain stable && \
    rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android

WORKDIR /project
