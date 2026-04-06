#!/usr/bin/env bash
# Build the release APK inside a pinned Docker image for byte-for-byte
# reproducibility. SOURCE_DATE_EPOCH is derived from the HEAD commit so the
# same git revision always produces the same archive timestamps.
#
# Usage:
#   scripts/reproducible-build.sh [gradle-task]
#
# Default task: :TMessagesProj_App:assembleAfatRelease
# Output:      TMessagesProj_App/build/outputs/apk/...

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

GRADLE_TASK="${1:-:TMessagesProj_App:assembleAfatRelease}"
IMAGE_TAG="${IMAGE_TAG:-telegramandroid-reproducible:latest}"

if [ -z "${SOURCE_DATE_EPOCH:-}" ]; then
    SOURCE_DATE_EPOCH="$(git log -1 --format=%ct HEAD)"
fi
export SOURCE_DATE_EPOCH

echo "==> SOURCE_DATE_EPOCH = ${SOURCE_DATE_EPOCH} ($(date -u -r "${SOURCE_DATE_EPOCH}" '+%Y-%m-%d %H:%M:%S UTC' 2>/dev/null || date -u -d "@${SOURCE_DATE_EPOCH}"))"
echo "==> Gradle task        = ${GRADLE_TASK}"
echo "==> Docker image       = ${IMAGE_TAG}"

echo "==> Building Docker image..."
docker build -t "${IMAGE_TAG}" -f docker/Dockerfile docker/

echo "==> Running Gradle build inside container..."
docker run --rm \
    -v "${REPO_ROOT}:/project" \
    -w /project \
    -e SOURCE_DATE_EPOCH \
    -e GRADLE_OPTS="-Dorg.gradle.daemon=false -Duser.timezone=UTC" \
    "${IMAGE_TAG}" \
    bash -c "
        set -e
        # Prebuild the native dependencies that are not part of the gradle
        # externalNativeBuild graph.
        cd TMessagesProj/jni
        export NDK=\${ANDROID_NDK_HOME}
        export NINJA_PATH=/usr/bin/ninja
        ./build_libvpx_clang.sh
        ./build_ffmpeg_clang.sh
        ./patch_ffmpeg.sh
        ./patch_boringssl.sh
        ./build_boringssl.sh
        cd /project
        ./gradlew --no-daemon --init-script reproducible.gradle ${GRADLE_TASK}
    "

echo "==> Build artifacts:"
find TMessagesProj_App/build/outputs/apk -name '*.apk' -print 2>/dev/null | while read -r apk; do
    sha256sum "$apk"
done
