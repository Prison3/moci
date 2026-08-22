#!/usr/bin/env bash
# 编译 Android release APK 并复制到 server/downloads/moci.apk
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLIENT="$ROOT/client"
OUT="$ROOT/server/downloads"
APK_NAME="moci.apk"

cd "$CLIENT"
if [[ -x ./gradlew ]]; then
  ./gradlew :app:assembleRelease || {
    GRADLE_BIN="$(find "$HOME/.gradle/wrapper/dists/gradle-8.13-bin" -path '*/bin/gradle' -type f 2>/dev/null | head -1)"
    if [[ -n "$GRADLE_BIN" ]]; then
      "$GRADLE_BIN" :app:assembleRelease
    else
      exit 1
    fi
  }
else
  echo "找不到 gradlew" >&2
  exit 1
fi

BUILT="$(find "$CLIENT/app/build/outputs/apk/release" -name '*.apk' -type f | head -1)"
if [[ -z "$BUILT" ]]; then
  echo "未找到 release APK" >&2
  exit 1
fi

mkdir -p "$OUT"
cp "$BUILT" "$OUT/$APK_NAME"
echo "Release APK: $OUT/$APK_NAME"
ls -lh "$OUT/$APK_NAME"

if [[ "${MOCI_UPLOAD:-}" == "1" ]]; then
  REMOTE="${MOCI_UPLOAD_HOST:-root@cn}:/root/moci/server/downloads/moci.apk"
  echo "上传到 $REMOTE ..."
  scp "$OUT/$APK_NAME" "$REMOTE"
fi