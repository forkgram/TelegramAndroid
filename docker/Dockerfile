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
# NDK 21.4 is used by the native dependency build scripts in TMessagesProj/jni.
# Gradle's externalNativeBuild uses NDK 27.2 (see TMessagesProj/build.gradle).
# Both must be installed for an end-to-end reproducible build.
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
# SDK components (SDK 35, Build Tools 35, NDK 21.4 + 23.2 + 27.2, CMake 3.10)
# ============================================================
RUN yes | sdkmanager --licenses > /dev/null 2>&1 && \
    sdkmanager \
      "platforms;android-35" \
      "build-tools;35.0.0" \
      "ndk;21.4.7075529" \
      "ndk;23.2.8568313" \
      "ndk;27.2.12479018" \
      "cmake;3.10.2.4988404"

# Make SDK cmake available as system cmake (Gradle needs 3.10.2 for AndroidNdkModules)
# Also provide cmake3 alias for tde2e build
RUN ln -sf /opt/android-sdk/cmake/3.10.2.4988404/bin/cmake /usr/local/bin/cmake && \
    ln -sf /opt/android-sdk/cmake/3.10.2.4988404/bin/cmake /usr/local/bin/cmake3

WORKDIR /project
