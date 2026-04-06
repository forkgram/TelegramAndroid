#!/usr/bin/env bash
set -euo pipefail

# ============================================================
#  Forkgram Android — Remote Docker Build Script
# ============================================================
#
#  Prerequisites on Windows:
#    1. OpenSSH Server enabled (port 22)
#    2. WSL2 with Ubuntu, Docker, rsync
#
#  Prerequisites on Mac:
#    1. rsync (pre-installed)
#    2. sshpass (brew install sshpass)
#    3. adb (brew install android-platform-tools)
#
#  Quick start:
#    1. Edit .env in project root
#    2. ./scripts/remote-build.sh setup     (one-time)
#    3. ./scripts/remote-build.sh deploy    (sync + build + install)
#
# ============================================================

# ============================================================
#  CONFIGURATION
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${PROJECT_DIR}/.env"

# Defaults
REMOTE_USER=""
REMOTE_HOST=""
REMOTE_PORT="22"
REMOTE_PASS=""
REMOTE_PROJECT_DIR=""

DOCKER_IMAGE="forkgram-builder"
DOCKER_REGISTRY_IMAGE=""
GRADLE_TASK=":TMessagesProj_App:assembleAfatDebug"

PHONE_LOCATION="mac"
PHONE_WIFI_IP=""

# Load from .env
if [ -f "${ENV_FILE}" ]; then
    # shellcheck source=/dev/null
    source "${ENV_FILE}"
else
    err ".env not found at ${ENV_FILE}"
    exit 1
fi

# ============================================================
#  Internal variables
# ============================================================
APK_REL_PATH="TMessagesProj_App/build/outputs/apk/afat/debug/app.apk"

SSH_OPTS="-o ConnectTimeout=10 -o StrictHostKeyChecking=no -o PreferredAuthentications=password -p ${REMOTE_PORT}"

# SSH to Windows, commands run via wsl bash
_ssh()  { sshpass -p "${REMOTE_PASS}" ssh ${SSH_OPTS} "${REMOTE_USER}@${REMOTE_HOST}" "$@"; }
_wsl()  { _ssh "wsl -- $*"; }
_scp()  { sshpass -p "${REMOTE_PASS}" scp -o PreferredAuthentications=password -o StrictHostKeyChecking=no -P "${REMOTE_PORT}" "$@"; }
_rsync() { sshpass -p "${REMOTE_PASS}" rsync "$@"; }

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
log()  { echo -e "${CYAN}[$(date +%H:%M:%S)]${NC} $*"; }
ok()   { echo -e "${GREEN}[$(date +%H:%M:%S)] OK${NC} $*"; }
warn() { echo -e "${YELLOW}[$(date +%H:%M:%S)] WARN${NC} $*"; }
err()  { echo -e "${RED}[$(date +%H:%M:%S)] ERR${NC} $*" >&2; }

# ============================================================
#  sync — rsync project to remote (via WSL2 rsync)
# ============================================================
do_sync() {
    log "Syncing files to ${REMOTE_HOST}:${REMOTE_PROJECT_DIR} ..."

    # Fix ownership from previous Docker builds
    _ssh "wsl -u root chown -R 1000:1000 ${REMOTE_PROJECT_DIR} 2>nul & echo ok" >/dev/null 2>&1 || true

    _rsync -az --delete \
        --rsync-path="wsl rsync" \
        --exclude='.gradle/' \
        --exclude='**/build/' \
        --exclude='.idea/' \
        --exclude='local.properties' \
        --exclude='.env' \
        --exclude='.vscode/' \
        --exclude='*.iml' \
        --exclude='.DS_Store' \
        --exclude='*.svg' \
        --exclude='*copy.tgs' \
        --exclude='TMessagesProj/jni/cache_keys/' \
        --exclude='TMessagesProj/jni/dav1d/' \
        --exclude='TMessagesProj/jni/libvpx/' \
        --exclude='TMessagesProj/jni/ffmpeg/' \
        --exclude='TMessagesProj/jni/boringssl/' \
        --exclude='TMessagesProj/jni/tde2e_source/' \
        --exclude='TMessagesProj/jni/tde2e/' \
        --exclude='TMessagesProj/.cxx/' \
        -e "ssh ${SSH_OPTS}" \
        "${PROJECT_DIR}/" \
        "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_PROJECT_DIR}/"

    # Sync .env separately (contains secrets)
    if [ -f "${PROJECT_DIR}/.env" ]; then
        _rsync -az \
            --rsync-path="wsl rsync" \
            -e "ssh ${SSH_OPTS}" \
            "${PROJECT_DIR}/.env" \
            "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_PROJECT_DIR}/.env"
    fi

    ok "Files synced."
}

# ============================================================
#  build — run Gradle inside Docker on remote
# ============================================================
do_build() {
    log "Building on ${REMOTE_HOST} via Docker ..."

    local start=$SECONDS

    # Create build script locally and upload
    cat > /tmp/docker-build.sh << BUILDSCRIPT
#!/bin/bash
set -e
cd /project
git config --global --add safe.directory "*"

if [ -f .env ]; then
    while IFS="=" read -r key value; do
        [ -z "\$key" ] || [ "\${key#\#}" != "\$key" ] && continue
        sed -i "s|^\${key}=.*|\${key}=\${value}|" gradle.properties
    done < .env
fi

chmod +x gradlew && ./gradlew ${GRADLE_TASK}
chown -R \$(stat -c %u:%g /project) /project
BUILDSCRIPT

    _rsync -az --rsync-path="wsl rsync" \
        -e "ssh ${SSH_OPTS}" \
        /tmp/docker-build.sh \
        "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_PROJECT_DIR}/.docker-build.sh"

    _ssh "wsl -- chmod +x ${REMOTE_PROJECT_DIR}/.docker-build.sh"
    _ssh "wsl -- docker run --rm -v ${REMOTE_PROJECT_DIR}:/project -v gradle-cache:/root/.gradle ${DOCKER_IMAGE} /project/.docker-build.sh"

    local elapsed=$(( SECONDS - start ))
    ok "Build complete in $(( elapsed / 60 ))m $(( elapsed % 60 ))s."
}

# ============================================================
#  install — put APK on phone
# ============================================================
do_install() {
    case "${PHONE_LOCATION}" in
        windows)
            log "Installing APK via ADB on Windows ..."
            _ssh "wsl -e bash -c 'cd ${REMOTE_PROJECT_DIR} && adb install -r ${APK_REL_PATH}'"
            ok "Installed on phone (from Windows)."
            ;;

        mac)
            log "Fetching APK from ${REMOTE_HOST} ..."
            _rsync -az \
                --rsync-path="wsl rsync" \
                -e "ssh ${SSH_OPTS}" \
                "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_PROJECT_DIR}/${APK_REL_PATH}" \
                /tmp/telegram-debug.apk
            log "Installing APK via ADB ..."
            adb install -r /tmp/telegram-debug.apk
            ok "Installed on phone (from Mac)."
            ;;

        wifi)
            [ -z "${PHONE_WIFI_IP}" ] && { err "PHONE_WIFI_IP is not set."; exit 1; }
            log "Connecting to phone via WiFi ADB (${PHONE_WIFI_IP}) ..."
            adb connect "${PHONE_WIFI_IP}:5555" 2>/dev/null || true

            log "Fetching APK from ${REMOTE_HOST} ..."
            _rsync -az \
                --rsync-path="wsl rsync" \
                -e "ssh ${SSH_OPTS}" \
                "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_PROJECT_DIR}/${APK_REL_PATH}" \
                /tmp/telegram-debug.apk

            adb -s "${PHONE_WIFI_IP}:5555" install -r /tmp/telegram-debug.apk
            ok "Installed on phone (WiFi ADB)."
            ;;

        *)
            err "Unknown PHONE_LOCATION: ${PHONE_LOCATION}"
            exit 1
            ;;
    esac
}

# ============================================================
#  setup — pull or build Docker image on remote
# ============================================================
do_setup() {
    _wsl "mkdir -p ${REMOTE_PROJECT_DIR}"

    if [ -n "${DOCKER_REGISTRY_IMAGE}" ]; then
        log "Pulling Docker image from registry ..."
        _wsl "docker pull ${DOCKER_REGISTRY_IMAGE} && docker tag ${DOCKER_REGISTRY_IMAGE} ${DOCKER_IMAGE}"
    else
        log "Building Docker image '${DOCKER_IMAGE}' locally ..."

        _rsync -az \
            --rsync-path="wsl rsync" \
            -e "ssh ${SSH_OPTS}" \
            "${PROJECT_DIR}/docker/Dockerfile" \
            "${REMOTE_USER}@${REMOTE_HOST}:/tmp/forkgram-builder-Dockerfile"

        _wsl "docker build -t ${DOCKER_IMAGE} -f /tmp/forkgram-builder-Dockerfile /tmp && rm -f /tmp/forkgram-builder-Dockerfile"
    fi

    ok "Docker image '${DOCKER_IMAGE}' is ready."
}

# ============================================================
#  check — verify connectivity and prerequisites
# ============================================================
do_check() {
    log "Checking SSH connectivity ..."
    if _ssh "echo ok" >/dev/null 2>&1; then
        ok "SSH connection to ${REMOTE_HOST}."
    else
        err "Cannot SSH to ${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_PORT}"
        exit 1
    fi

    log "Checking WSL2 ..."
    if _wsl "echo ok" >/dev/null 2>&1; then
        ok "WSL2 available."
    else
        err "WSL2 not accessible."
        exit 1
    fi

    log "Checking Docker ..."
    if _wsl "docker info" >/dev/null 2>&1; then
        ok "Docker is running."
    else
        err "Docker is not accessible."
        exit 1
    fi

    log "Checking Docker image ..."
    if _wsl "docker image inspect ${DOCKER_IMAGE}" >/dev/null 2>&1; then
        ok "Docker image '${DOCKER_IMAGE}' exists."
    else
        warn "Docker image '${DOCKER_IMAGE}' not found. Run: $0 setup"
    fi

    log "Checking rsync ..."
    if _wsl "which rsync" >/dev/null 2>&1; then
        ok "rsync available."
    else
        err "rsync not found in WSL2. Run: wsl sudo apt install rsync"
        exit 1
    fi

    if [ "${PHONE_LOCATION}" = "mac" ] || [ "${PHONE_LOCATION}" = "wifi" ]; then
        log "Checking local adb ..."
        if command -v adb >/dev/null 2>&1; then
            ok "adb available locally."
        else
            warn "adb not found. Install: brew install android-platform-tools"
        fi
    fi

    echo ""
    ok "All checks passed."
}

# ============================================================
#  MAIN
# ============================================================
usage() {
    cat <<EOF
Usage: $(basename "$0") <command>

Commands:
  setup    Pull/build Docker image on remote (one-time)
  check    Verify connectivity and prerequisites
  sync     Sync files to remote
  build    Sync + build
  deploy   Sync + build + install on phone
  install  Install last built APK (no rebuild)

Config: .env in project root
EOF
}

case "${1:-}" in
    setup)   do_setup ;;
    check)   do_check ;;
    sync)    do_sync ;;
    build)   do_sync; do_build ;;
    deploy)  do_sync; do_build; do_install ;;
    install) do_install ;;
    -h|--help|help) usage ;;
    *)       usage; exit 1 ;;
esac
