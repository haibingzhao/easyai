#!/usr/bin/env bash
#
# Build the self-contained backend artifact for the desktop client:
#   1. easyai-console  -> dist/            (frontend, injected into the jar)
#   2. easyai library  -> local Maven repo (only when snapshots are missing)
#   3. easyai-desktop-server -> fat jar    (copied to easyai-desktop/backend/)
#
# Usage:
#   scripts/build-backend.sh            # incremental
#   scripts/build-backend.sh --full     # always rebuild easyai library too
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DESKTOP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKSPACE_DIR="$(cd "$DESKTOP_DIR/.." && pwd)"
CONSOLE_DIR="$WORKSPACE_DIR/easyai-console"
EASYAI_DIR="$WORKSPACE_DIR/easyai"
EXAMPLES_DIR="$WORKSPACE_DIR/easyai-apps"
BACKEND_DIR="$DESKTOP_DIR/backend"

VERSION="2026.0.1-SNAPSHOT"
MAVEN_BIN="${MAVEN_BIN:-mvn}"
command -v "$MAVEN_BIN" >/dev/null 2>&1 || MAVEN_BIN="mvn"

MODE="${1:-incremental}"

echo "==> [1/3] Building easyai-console frontend"
cd "$CONSOLE_DIR"
if [ ! -d node_modules ]; then
  npm ci
fi
npm run build

if [ "$MODE" = "--full" ] || [ ! -f "$HOME/.m2/repository/com/easy/easyai-web/$VERSION/easyai-web-$VERSION.jar" ]; then
  echo "==> [2/3] Installing easyai library snapshots into the local Maven repo"
  cd "$EASYAI_DIR"
  "$MAVEN_BIN" install -DskipTests -q
else
  echo "==> [2/3] easyai library snapshots present, skipping (use --full to force)"
fi

echo "==> [3/3] Packaging easyai-desktop-server fat jar"
cd "$EXAMPLES_DIR"
"$MAVEN_BIN" package -pl easyai-desktop-server -DskipTests -q

mkdir -p "$BACKEND_DIR"
cp "$EXAMPLES_DIR/easyai-desktop-server/target/easyai-desktop-server-$VERSION.jar" \
   "$BACKEND_DIR/easyai-desktop-server.jar"

echo "==> Backend artifact ready: $BACKEND_DIR/easyai-desktop-server.jar"
ls -lh "$BACKEND_DIR/easyai-desktop-server.jar"
