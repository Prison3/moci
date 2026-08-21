#!/usr/bin/env bash
# 安装 Vosk 英文小模型到 Android assets。
# 用法：
#   ./scripts/fetch_vosk_model.sh                  # 用默认 URL 下载
#   ./scripts/fetch_vosk_model.sh /path/to/model.zip  # 用本地 zip
#   VOSK_MODEL_URL='https://...' ./scripts/fetch_vosk_model.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/client/app/src/main/assets/model-en-us"
DEFAULT_URL="https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
URL="${VOSK_MODEL_URL:-$DEFAULT_URL}"
LOCAL_ZIP="${1:-}"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if [[ -f "$DEST/am/final.mdl" || -f "$DEST/conf/model.conf" ]]; then
  if [[ ! -f "$DEST/uuid" ]]; then
    uuidgen | tr '[:upper:]' '[:lower:]' > "$DEST/uuid"
    echo "模型已存在，已补写 uuid：$DEST/uuid"
  else
    echo "模型已存在：$DEST"
  fi
  exit 0
fi

if [[ -n "$LOCAL_ZIP" ]]; then
  if [[ ! -f "$LOCAL_ZIP" ]]; then
    echo "找不到本地 zip：$LOCAL_ZIP" >&2
    exit 1
  fi
  echo "使用本地文件 $LOCAL_ZIP ..."
  cp "$LOCAL_ZIP" "$TMP/model.zip"
else
  echo "下载 $URL ..."
  curl -L --fail --retry 5 -C - -o "$TMP/model.zip" "$URL"
fi

unzip -t "$TMP/model.zip" >/dev/null
unzip -q "$TMP/model.zip" -d "$TMP"
SRC="$(find "$TMP" -maxdepth 1 -type d -name 'vosk-model-*' | head -1)"
if [[ -z "$SRC" ]]; then
  # 有的 zip 解压后直接是 am/conf/...
  if [[ -d "$TMP/am" || -d "$TMP/conf" ]]; then
    SRC="$TMP"
  else
    echo "解压后未找到模型目录（需要 am/ conf/ 等）" >&2
    exit 1
  fi
fi

# 保留 README.md 说明
KEEP_README=""
if [[ -f "$DEST/README.md" ]]; then
  KEEP_README="$(mktemp)"
  cp "$DEST/README.md" "$KEEP_README"
fi

rm -rf "$DEST"
mkdir -p "$DEST"
# 若 SRC 就是 TMP，只拷模型内容；否则移动整个目录内容
if [[ "$SRC" == "$TMP" ]]; then
  shopt -s dotglob
  for item in "$TMP"/*; do
    base="$(basename "$item")"
    [[ "$base" == "model.zip" ]] && continue
    mv "$item" "$DEST/"
  done
else
  shopt -s dotglob
  mv "$SRC"/* "$DEST/"
fi

if [[ -n "$KEEP_README" ]]; then
  mv "$KEEP_README" "$DEST/README.md"
fi

# Vosk StorageService.unpack 需要 assets 内的 uuid 文件做版本同步
uuidgen | tr '[:upper:]' '[:lower:]' > "$DEST/uuid"

echo "已安装到 $DEST ($(du -sh "$DEST" | awk '{print $1}'))"
