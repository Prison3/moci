#!/usr/bin/env bash
# 墨词后端启动脚本
#
# 用法：
#   ./scripts/start_server.sh              # 启动（优先 Supervisor）
#   ./scripts/start_server.sh setup        # 首次部署：venv + 依赖 + gRPC 代码
#   ./scripts/start_server.sh dev          # 开发模式（Flask debug + gRPC）
#   ./scripts/start_server.sh prod         # 生产模式前台 gunicorn
#   ./scripts/start_server.sh stop|restart|status
#   ./scripts/start_server.sh install-supervisor
#
# 环境变量（可选）：
#   PORT=5000          Flask / gunicorn 端口
#   GRPC_PORT=50051    gRPC 端口
#   DATABASE_URL       默认 sqlite:///instance/words.db
#   MOCI_WORKERS=1     gunicorn workers
#   MOCI_THREADS=4     gunicorn threads
#   MOCI_TIMEOUT=60    gunicorn timeout（秒）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER="$ROOT/server"
VENV="$SERVER/.venv"
PYTHON="${VENV}/bin/python"
PIP="${VENV}/bin/pip"
GUNICORN="${VENV}/bin/gunicorn"
PID_FILE="$SERVER/instance/moci.pid"
SUPERVISOR_NAME="moci"

PORT="${PORT:-5000}"
GRPC_PORT="${GRPC_PORT:-50051}"
DATABASE_URL="${DATABASE_URL:-sqlite:///instance/words.db}"
MOCI_WORKERS="${MOCI_WORKERS:-1}"
MOCI_THREADS="${MOCI_THREADS:-4}"
MOCI_TIMEOUT="${MOCI_TIMEOUT:-60}"

export PORT GRPC_PORT DATABASE_URL

usage() {
  cat <<'EOF'
墨词后端启动脚本

  ./scripts/start_server.sh [command]

命令：
  start              启动服务（默认；有 Supervisor 时用 supervisorctl）
  setup              创建 venv、安装依赖、生成 gRPC 代码
  dev                开发模式（python app.py）
  prod               生产模式前台 gunicorn
  stop               停止服务
  restart            重启服务
  status             查看运行状态
  install-supervisor 写入 /etc/supervisor/conf.d/ 并 reload
EOF
}

need_venv() {
  if [[ ! -x "$PYTHON" ]]; then
    echo "未找到虚拟环境，请先运行: ./scripts/start_server.sh setup" >&2
    exit 1
  fi
}

cmd_setup() {
  if [[ ! -x "$PYTHON" ]]; then
    echo "创建虚拟环境 $VENV ..."
    python3 -m venv "$VENV"
  fi
  echo "安装依赖 ..."
  "$PIP" install -q -r "$SERVER/requirements.txt"
  echo "生成 gRPC 代码 ..."
  PATH="$VENV/bin:$PATH" "$ROOT/scripts/gen_proto.sh"
  mkdir -p "$SERVER/instance" "$SERVER/downloads"
  echo "setup 完成。"
}

has_supervisor() {
  command -v supervisorctl >/dev/null 2>&1 \
    && supervisorctl avail 2>/dev/null | awk '{print $1}' | grep -qx "$SUPERVISOR_NAME"
}

run_gunicorn() {
  exec "$GUNICORN" \
    --workers "$MOCI_WORKERS" \
    --threads "$MOCI_THREADS" \
    --bind "0.0.0.0:${PORT}" \
    --timeout "$MOCI_TIMEOUT" \
    'app:create_app()'
}

cmd_dev() {
  need_venv
  cd "$SERVER"
  echo "开发模式: http://0.0.0.0:${PORT}  gRPC :${GRPC_PORT}"
  exec "$PYTHON" app.py
}

cmd_prod() {
  need_venv
  cd "$SERVER"
  echo "生产模式（前台）: http://0.0.0.0:${PORT}  gRPC :${GRPC_PORT}"
  run_gunicorn
}

cmd_start() {
  need_venv
  if has_supervisor; then
    echo "通过 Supervisor 启动 $SUPERVISOR_NAME ..."
    supervisorctl start "$SUPERVISOR_NAME"
    supervisorctl status "$SUPERVISOR_NAME"
    return
  fi
  if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "服务已在运行 (pid $(cat "$PID_FILE"))"
    return
  fi
  cd "$SERVER"
  echo "后台启动 gunicorn: http://0.0.0.0:${PORT}  gRPC :${GRPC_PORT}"
  nohup "$GUNICORN" \
    --workers "$MOCI_WORKERS" \
    --threads "$MOCI_THREADS" \
    --bind "0.0.0.0:${PORT}" \
    --timeout "$MOCI_TIMEOUT" \
    'app:create_app()' >>"$SERVER/instance/gunicorn.log" 2>&1 &
  echo $! >"$PID_FILE"
  echo "已启动 (pid $(cat "$PID_FILE"))，日志: $SERVER/instance/gunicorn.log"
}

kill_stale_workers() {
  # Supervisor 有时只停 master，worker 会残留并占用 gRPC 端口导致 App 登录超时
  local pattern="${SERVER}/.venv/bin/gunicorn"
  if pgrep -f "$pattern" >/dev/null 2>&1; then
    echo "清理残留的 gunicorn worker ..."
    pkill -f "$pattern" 2>/dev/null || true
    sleep 1
  fi
}

cmd_stop() {
  if has_supervisor; then
    echo "通过 Supervisor 停止 $SUPERVISOR_NAME ..."
    supervisorctl stop "$SUPERVISOR_NAME"
    kill_stale_workers
    return
  fi
  if [[ ! -f "$PID_FILE" ]]; then
    echo "未找到 PID 文件，服务可能未运行。"
    return
  fi
  pid="$(cat "$PID_FILE")"
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid"
    echo "已停止 (pid $pid)"
  else
    echo "进程 $pid 不存在。"
  fi
  rm -f "$PID_FILE"
}

cmd_restart() {
  cmd_stop
  kill_stale_workers
  sleep 1
  cmd_start
}

cmd_status() {
  if has_supervisor; then
    supervisorctl status "$SUPERVISOR_NAME"
    return
  fi
  if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "运行中 (pid $(cat "$PID_FILE"))"
  else
    echo "未运行"
  fi
}

cmd_install_supervisor() {
  need_venv
  local conf_dir="/etc/supervisor/conf.d"
  local conf="$conf_dir/${SUPERVISOR_NAME}.conf"
  if [[ ! -d "$conf_dir" ]]; then
    echo "未找到 $conf_dir，请先安装 Supervisor。" >&2
    exit 1
  fi
  cat >"$conf" <<EOF
[program:${SUPERVISOR_NAME}]
directory=${SERVER}
command=${GUNICORN} --workers ${MOCI_WORKERS} --threads ${MOCI_THREADS} --bind 0.0.0.0:${PORT} --timeout ${MOCI_TIMEOUT} 'app:create_app()'
environment=DATABASE_URL="${DATABASE_URL}",GRPC_PORT="${GRPC_PORT}"
autostart=true
autorestart=true
startsecs=3
stopwaitsecs=10
stopasgroup=true
killasgroup=true
stdout_logfile=/var/log/supervisor/${SUPERVISOR_NAME}.stdout.log
stderr_logfile=/var/log/supervisor/${SUPERVISOR_NAME}.stderr.log
stdout_logfile_maxbytes=10MB
stderr_logfile_maxbytes=10MB
stdout_logfile_backups=3
stderr_logfile_backups=3
EOF
  echo "已写入 $conf"
  supervisorctl reread
  supervisorctl update
  supervisorctl status "$SUPERVISOR_NAME"
}

main() {
  local cmd="${1:-start}"
  case "$cmd" in
    setup) cmd_setup ;;
    dev) cmd_dev ;;
    prod) cmd_prod ;;
    start) cmd_start ;;
    stop) cmd_stop ;;
    restart) cmd_restart ;;
    status) cmd_status ;;
    install-supervisor) cmd_install_supervisor ;;
    -h|--help|help) usage ;;
    *)
      echo "未知命令: $cmd" >&2
      usage >&2
      exit 1
      ;;
  esac
}

main "${1:-start}"
