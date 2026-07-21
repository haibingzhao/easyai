#!/usr/bin/env bash
#
# Fetch a Temurin JRE 21 for the current platform into easyai-desktop/backend/jre.
#
# The bundled JRE is used by the Electron main process to run the backend jar
# without requiring a system-wide Java installation. In dev you may skip this
# script entirely — the shell falls back to `java` on PATH.
#
# Usage:
#   scripts/fetch-jre.sh              # auto-detect OS/arch
#   scripts/fetch-jre.sh mac aarch64  # explicit target (mac|linux|windows, x64|aarch64)
#   scripts/fetch-jre.sh --if-missing # skip when backend/jre already has a java binary
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)/backend"
JRE_DIR="$BACKEND_DIR/jre"

if [ "${1:-}" = "--if-missing" ] && { [ -x "$JRE_DIR/bin/java" ] || [ -f "$JRE_DIR/bin/java.exe" ]; }; then
  echo "==> JRE already present at $JRE_DIR, skipping download"
  "$JRE_DIR/bin/java" -version 2>&1 | head -1 || true
  exit 0
fi
[ "${1:-}" = "--if-missing" ] && shift

JDK_FEATURE_VERSION=21
VENDOR_API="https://api.adoptium.net/v3/binary/latest"

detect_os() {
  case "$(uname -s)" in
    Darwin) echo "mac" ;;
    Linux) echo "linux" ;;
    MINGW*|MSYS*|CYGWIN*) echo "windows" ;;
    *) echo "unsupported OS: $(uname -s)" >&2; exit 1 ;;
  esac
}

detect_arch() {
  case "$(uname -m)" in
    x86_64|amd64) echo "x64" ;;
    arm64|aarch64) echo "aarch64" ;;
    *) echo "unsupported arch: $(uname -m)" >&2; exit 1 ;;
  esac
}

OS="${1:-$(detect_os)}"
ARCH="${2:-$(detect_arch)}"
EXT="tar.gz"
[ "$OS" = "windows" ] && EXT="zip"

URL="$VENDOR_API/$JDK_FEATURE_VERSION/ga/$OS/$ARCH/jre/hotspot/normal/eclipse"
echo "==> Fetching Temurin JRE $JDK_FEATURE_VERSION ($OS/$ARCH)"
echo "    $URL"

mkdir -p "$BACKEND_DIR"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
ARCHIVE="$TMP_DIR/jre.$EXT"

curl -fSL --retry 3 -o "$ARCHIVE" "$URL"

echo "==> Extracting"
if [ "$EXT" = "zip" ]; then
  unzip -q "$ARCHIVE" -d "$TMP_DIR/extracted"
else
  mkdir -p "$TMP_DIR/extracted"
  tar -xzf "$ARCHIVE" -C "$TMP_DIR/extracted"
fi

# Locate the JRE home: macOS archives nest it under <top>/Contents/Home.
JRE_HOME="$(dirname "$(dirname "$(find "$TMP_DIR/extracted" -type f -name java -path '*/bin/java' | head -1)")")"
if [ -z "$JRE_HOME" ] || [ ! -x "$JRE_HOME/bin/java" ] && [ ! -f "$JRE_HOME/bin/java.exe" ]; then
  echo "Failed to locate JRE home in the downloaded archive" >&2
  exit 1
fi

rm -rf "$JRE_DIR"
mv "$JRE_HOME" "$JRE_DIR"

# macOS Gatekeeper: strip quarantine so the bundled binary can execute.
if [ "$OS" = "mac" ]; then
  xattr -dr com.apple.quarantine "$JRE_DIR" 2>/dev/null || true
fi

echo "==> JRE installed at $JRE_DIR"
"$JRE_DIR/bin/java" -version 2>&1 | head -1 || true
