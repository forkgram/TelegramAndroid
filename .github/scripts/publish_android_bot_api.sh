#!/usr/bin/env bash
set -euo pipefail
version=$1
shift
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]
: "${ANDROID_BOT_TOKEN:?Missing Android bot token}"
: "${ANDROID_BOT_API_SSH_KEY:?Missing Bot API SSH key}"
: "${ANDROID_BOT_API_SSH_KNOWN_HOSTS:?Missing pinned SSH host key}"
umask 077
tunnel=$(mktemp -d "$RUNNER_TEMP/android-tg.XXXXXX")
cleanup() {
  if [[ -S "$tunnel/socket" ]]; then
    ssh -F /dev/null -S "$tunnel/socket" -O exit "telegram-ci@$host" >/dev/null 2>&1 || true
  fi
  rm -f -- "$tunnel/socket" "$tunnel/key" "$tunnel/known_hosts"
  rmdir -- "$tunnel"
}
host=
trap cleanup EXIT
printf '%s\n' "$ANDROID_BOT_API_SSH_KEY" > "$tunnel/key"
printf '%s\n' "$ANDROID_BOT_API_SSH_KNOWN_HOSTS" > "$tunnel/known_hosts"
unset ANDROID_BOT_API_SSH_KEY
mapfile -t pinned < "$tunnel/known_hosts"
[[ "${#pinned[@]}" == 1 ]]
read -r host key_type key_data extra <<< "${pinned[0]}"
[[ "$host" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*$ && "$key_type" == ssh-ed25519 && -n "$key_data" && -z "$extra" ]]
ssh -F /dev/null -i "$tunnel/key" -M -S "$tunnel/socket" -fNT \
  -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes \
  -o "UserKnownHostsFile=$tunnel/known_hosts" -o GlobalKnownHostsFile=/dev/null \
  -o UpdateHostKeys=no -o VerifyHostKeyDNS=no -o HostKeyAlgorithms=ssh-ed25519 \
  -o ExitOnForwardFailure=yes -o ConnectTimeout=10 \
  -L 127.0.0.1:18083:127.0.0.1:18083 "telegram-ci@$host"
rm -f -- "$tunnel/key"
python "$GITHUB_WORKSPACE/.github/scripts/publish_android_optional.py" "$version" "$@"
