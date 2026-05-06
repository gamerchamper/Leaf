#!/bin/sh
# Build Leaf distribution jars. Paperweight only supports runnable Mojmap Paperclip/Bundler for releases;
# Reobf variants need optional debug flags (may still change upstream).
#
# POSIX sh — safe with: ./build-paperclip.sh | sh build-paperclip.sh
#
# Usage:
#   ./build-paperclip.sh
#       → :leaf-server:jar + createMojmapBundlerJar + createMojmapPaperclipJar
#
#   GRADLE_CONTINUE=1 ./build-paperclip.sh
#       → ./gradlew --continue …
#
#   INCLUDE_REOBF_LEGACY=1 ./build-paperclip.sh
#       → also createReobfBundlerJar + createReobfPaperclipJar (passes Paperweight debug props)

set -eu

ROOT="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
cd "$ROOT"

GRADLE="${GRADLE:-./gradlew}"
if [ ! -x "$GRADLE" ] && [ -f "$GRADLE" ]; then
  chmod +x "$GRADLE" 2>/dev/null || true
fi

set -- "$GRADLE"

if [ "${GRADLE_CONTINUE:-0}" = "1" ]; then
  set -- "$@" --continue
fi

if [ "${INCLUDE_REOBF_LEGACY:-0}" = "1" ]; then
  echo "[build-paperclip] INCLUDE_REOBF_LEGACY=1 — adding reobf bundler/paperclip (unsupported for releases; debug bypass)"
  # Paperweight: "Enable paperweight debug mode to bypass this error" on reobf Paperclip/Bundler.
  set -- "$@" -Ppaperweight.debug=true -Dpaperweight.debug=true
fi

set -- "$@" \
  :leaf-server:jar \
  :leaf-server:createMojmapBundlerJar \
  :leaf-server:createMojmapPaperclipJar

if [ "${INCLUDE_REOBF_LEGACY:-0}" = "1" ]; then
  set -- "$@" \
    :leaf-server:createReobfBundlerJar \
    :leaf-server:createReobfPaperclipJar
fi

echo "[build-paperclip] Running: $*"
"$@"

LIBS="$ROOT/leaf-server/build/libs"
echo
echo "[build-paperclip] Outputs under leaf-server/build/libs/:"
if [ -d "$LIBS" ]; then
  ls -lh "$LIBS"/*.jar 2>/dev/null || ls -lh "$LIBS"
else
  echo "  (directory missing: $LIBS)"
fi
