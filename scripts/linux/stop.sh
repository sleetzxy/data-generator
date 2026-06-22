#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "$SCRIPT_DIR/env.sh"

resolve_app_home

PID_FILE="$APP_HOME/run/data-generator.pid"

if [[ ! -f "$PID_FILE" ]]; then
    echo "未找到 PID 文件，服务可能未运行"
    exit 0
fi

pid="$(cat "$PID_FILE")"

if ! kill -0 "$pid" 2>/dev/null; then
    echo "进程 $pid 不存在，清理 PID 文件"
    rm -f "$PID_FILE"
    exit 0
fi

echo "正在停止 Data Generator (PID=$pid) ..."

kill "$pid" 2>/dev/null || true

for ((i = 1; i <= 30; i++)); do
    if ! kill -0 "$pid" 2>/dev/null; then
        rm -f "$PID_FILE"
        echo "已停止"
        exit 0
    fi
    sleep 1
done

echo "优雅停止超时，强制终止 ..."
kill -9 "$pid" 2>/dev/null || true
rm -f "$PID_FILE"
echo "已强制停止"
