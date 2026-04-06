#!/usr/bin/env bash
set -euo pipefail

# ============================================================
#  Telegram Android — Remote Docker Build Script
# ============================================================
#
#  Prerequisites on Windows:
#    1. OpenSSH Server enabled
#    2. Docker Desktop (with WSL2 backend)
#    3. rsync (choco install rsync) or WSL2 with rsync
#    4. platform-tools (adb) if phone is connected to Windows
#
#  Prerequisites on Mac:
#    1. rsync (pre-installed)
#    2. sshpass (brew install sshpass)
#    3. adb (brew install android-platform-tools) if phone on Mac
#
#  Quick start:
#    1. Edit scripts/remote-build.conf
#    2. ./scripts/remote-build.sh setup     (one-time: builds Docker image)
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
REMOTE_PORT="2222"
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

# Build SSH/SCP commands with sshpass
_ssh()  { sshpass -p "${REMOTE_PASS}" ssh ${SSH_OPTS} "${REMOTE_USER}@${REMOTE_HOST}" "$@"; }
_scp()  { sshpass -p "${REMOTE_PASS}" scp -o PreferredAuthentications=password -o StrictHostKeyChecking=no -P "${REMOTE_PORT}" "$@"; }
_rsync() { sshpass -p "${REMOTE_PASS}" rsync "$@"; }

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
log()  { echo -e "${CYAN}[$(date +%H:%M:%S)]${NC} $*"; }
ok()   { echo -e "${GREEN}[$(date +%H:%M:%S)] OK${NC} $*"; }
warn() { echo -e "${YELLOW}[$(date +%H:%M:%S)] WARN${NC} $*"; }
err()  { echo -e "${RED}[$(date +%H:%M:%S)] ERR${NC} $*" >&2; }

# ============================================================
#  sync — rsync project to remote
# ============================================================
do_sync() {
    log "Syncing files to ${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_PROJECT_DIR} ..."

    # Fix ownership from previous Docker builds (runs as root)
    _ssh "echo '${REMOTE_PASS}' | sudo -S chown -R \$(id -u):\$(id -g) ${REMOTE_PROJECT_DIR} 2>/dev/null; true"

    _rsync -az --delete \
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
        _scp "${PROJECT_DIR}/.env" \
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

    _ssh bash -s -- "${REMOTE_PROJECT_DIR}" "${DOCKER_IMAGE}" "${GRADLE_TASK}" <<'REMOTESCRIPT'
set -e
PROJECT="$1"
IMAGE="$2"
TASK="$3"
cd "$PROJECT"

HOSTUID=$(id -u)
HOSTGID=$(id -g)
docker run --rm \
    -v "$(pwd):/project" \
    -v gradle-cache:/root/.gradle \
    "$IMAGE" \
    bash -c '
        git config --global --add safe.directory "*"
        cd /project

        # Apply .env values into gradle.properties
        if [ -f .env ]; then
            while IFS="=" read -r key value; do
                [ -z "$key" ] || [ "${key#\#}" != "$key" ] && continue
                sed -i "s|^${key}=.*|${key}=${value}|" gradle.properties
            done < .env
        fi

        chmod +x gradlew && ./gradlew '"$TASK"'
        chown -R '"$HOSTUID:$HOSTGID"' /project
    '
REMOTESCRIPT

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
            _ssh bash -s -- "${REMOTE_PROJECT_DIR}" "${APK_REL_PATH}" <<'REMOTESCRIPT'
set -e
cd "$1"
adb install -r "$2"
REMOTESCRIPT
            ok "Installed on phone (from Windows)."
            ;;

        mac)
            log "Fetching APK from ${REMOTE_HOST} ..."
            _scp "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_PROJECT_DIR}/${APK_REL_PATH}" \
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
            _scp "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_PROJECT_DIR}/${APK_REL_PATH}" \
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
#  setup — build Docker image on remote (one-time)
# ============================================================
do_setup() {
    _ssh "mkdir -p ${REMOTE_PROJECT_DIR}"

    if [ -n "${DOCKER_REGISTRY_IMAGE}" ]; then
        log "Pulling Docker image from registry ..."
        _ssh "docker pull ${DOCKER_REGISTRY_IMAGE} && docker tag ${DOCKER_REGISTRY_IMAGE} ${DOCKER_IMAGE}"
    else
        log "Building Docker image '${DOCKER_IMAGE}' locally on ${REMOTE_HOST} ..."
        log "This will take 10-20 minutes (downloads SDK, NDK, etc.)."

        _scp "${PROJECT_DIR}/docker/Dockerfile" \
            "${REMOTE_USER}@${REMOTE_HOST}:/tmp/forkgram-builder-Dockerfile"

        _ssh bash <<REMOTESCRIPT
docker build -t ${DOCKER_IMAGE} -f /tmp/forkgram-builder-Dockerfile /tmp
rm -f /tmp/forkgram-builder-Dockerfile
REMOTESCRIPT
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
        echo "    Make sure OpenSSH Server is running and password is correct."
        exit 1
    fi

    log "Checking Docker on remote ..."
    if _ssh "docker info" >/dev/null 2>&1; then
        ok "Docker is running."
    else
        err "Docker is not accessible on remote."
        echo "    Make sure Docker Desktop is running."
        exit 1
    fi

    log "Checking Docker image ..."
    if _ssh "docker image inspect ${DOCKER_IMAGE}" >/dev/null 2>&1; then
        ok "Docker image '${DOCKER_IMAGE}' exists."
    else
        warn "Docker image '${DOCKER_IMAGE}' not found. Run: $0 setup"
    fi

    log "Checking rsync on remote ..."
    if _ssh "which rsync" >/dev/null 2>&1; then
        ok "rsync available on remote."
    else
        warn "rsync not found on remote."
        echo "    Install: choco install rsync"
        echo "    Or use WSL2 with rsync."
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
  setup    Build Docker image on remote (one-time)
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
