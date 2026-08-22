#!/usr/bin/env bash
# 生成 Python gRPC 代码到 server/grpc_gen/
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/server/grpc_gen"
PROTO="$ROOT/proto/moci.proto"
ANDROID_PROTO="$ROOT/client/app/src/main/proto/moci.proto"

mkdir -p "$OUT" "$(dirname "$ANDROID_PROTO")"
cp "$PROTO" "$ANDROID_PROTO"
touch "$OUT/__init__.py"

python3 -m grpc_tools.protoc \
  -I"$ROOT/proto" \
  --python_out="$OUT" \
  --grpc_python_out="$OUT" \
  --pyi_out="$OUT" \
  "$PROTO"

# grpc_tools 生成相对 import，包内引用需要修正
if grep -q '^import moci_pb2' "$OUT/moci_pb2_grpc.py" 2>/dev/null; then
  sed -i '' 's/^import moci_pb2/from . import moci_pb2/' "$OUT/moci_pb2_grpc.py" 2>/dev/null \
    || sed -i 's/^import moci_pb2/from . import moci_pb2/' "$OUT/moci_pb2_grpc.py"
fi

echo "Generated: $OUT"
