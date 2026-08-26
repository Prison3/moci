#!/usr/bin/env bash
# 编译 Android release APK，复制到 server/downloads/moci.apk，并上传到服务器
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLIENT="$ROOT/client"
OUT="$ROOT/server/downloads"
APK_NAME="moci.apk"
REMOTE_HOST="${MOCI_UPLOAD_HOST:-root@S1}"
REMOTE_DIR="${MOCI_UPLOAD_DIR:-/root/moci/server/downloads}"
REMOTE="${REMOTE_HOST}:${REMOTE_DIR}/${APK_NAME}"

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

VERSION_CODE="$(git -C "$ROOT" rev-list --count HEAD)"
VERSION_NAME="${VERSION_CODE} - $(git -C "$ROOT" describe --tags --always)"
cat > "$OUT/app_version.json" <<EOF
{"version_code": ${VERSION_CODE}, "version_name": "${VERSION_NAME}"}
EOF

echo "Release APK: $OUT/$APK_NAME (v${VERSION_NAME})"
ls -lh "$OUT/$APK_NAME"

if [[ "${MOCI_UPLOAD:-1}" != "0" ]]; then
  echo "上传到 $REMOTE ..."
  ssh "$REMOTE_HOST" "mkdir -p '$REMOTE_DIR'"
  scp "$OUT/$APK_NAME" "$OUT/app_version.json" "$REMOTE_HOST:$REMOTE_DIR/"
  echo "上传完成"
fi
