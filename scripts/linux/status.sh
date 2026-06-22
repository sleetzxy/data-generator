#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "$SCRIPT_DIR/env.sh"

resolve_app_home
resolve_server_port

PID_FILE="$APP_HOME/run/data-generator.pid"
LOG_FILE="$APP_HOME/logs/console.log"

if [[ ! -f "$PID_FILE" ]]; then
    if is_port_in_use "$APP_PORT"; then
        occupier_pid="$(get_listening_pid_on_port "$APP_PORT" 2>/dev/null || true)"
        echo "状态: 未运行 (PID 文件缺失，但端口 $APP_PORT 已被占用${occupier_pid:+, PID=$occupier_pid})"
        exit 1
    fi
    echo "状态: 未运行"
    exit 1
fi

pid="$(cat "$PID_FILE")"
if ! kill -0 "$pid" 2>/dev/null; then
    echo "状态: 未运行 (残留 PID 文件: $pid)"
    exit 1
fi

if is_app_ready "$pid" "$APP_PORT" "$LOG_FILE"; then
    echo "状态: 运行中 (PID=$pid, 端口=$APP_PORT)"
    exit 0
fi

echo "状态: 进程存在但未就绪 (PID=$pid, 端口=$APP_PORT)"
exit 1
