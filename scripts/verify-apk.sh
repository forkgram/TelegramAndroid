#!/usr/bin/env bash
# Compare two APKs entry-by-entry, ignoring the v1 signature block which is
# always different even for identical source builds (it depends on the signing
# key, not the build output).
#
# Usage:
#   scripts/verify-apk.sh <reference.apk> <rebuilt.apk>
#
# Exit code 0 if APKs are equivalent, non-zero otherwise.

set -euo pipefail

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <reference.apk> <rebuilt.apk>" >&2
    exit 2
fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REFERENCE="$1"
REBUILT="$2"

for f in "$REFERENCE" "$REBUILT"; do
    if [ ! -f "$f" ]; then
        echo "error: $f not found" >&2
        exit 2
    fi
done

echo "==> SHA-256:"
sha256sum "$REFERENCE" "$REBUILT"

echo "==> Comparing entries (ignoring META-INF signature)..."
python3 "${REPO_ROOT}/apkdiff.py" "$REFERENCE" "$REBUILT"
