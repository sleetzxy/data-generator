#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "$SCRIPT_DIR/env.sh"

init_app_env

PID_FILE="$APP_HOME/run/data-generator.pid"
LOG_DIR="$APP_HOME/logs"
LOG_FILE="$LOG_DIR/console.log"
RUN_DIR="$APP_HOME/run"

mkdir -p "$LOG_DIR" "$RUN_DIR" "$APP_HOME/data/configs/jobs" "$APP_HOME/data/job-logs"

if [[ -f "$PID_FILE" ]]; then
    old_pid="$(cat "$PID_FILE")"
    if kill -0 "$old_pid" 2>/dev/null; then
        if is_app_ready "$old_pid" "$APP_PORT" "$LOG_FILE"; then
            echo "Data Generator 已在运行 (PID=$old_pid, 端口=$APP_PORT)"
            exit 1
        fi
        echo "警告: 发现未就绪的残留进程 (PID=$old_pid)，正在停止 ..."
        kill "$old_pid" 2>/dev/null || true
        sleep 2
        kill -9 "$old_pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
fi

cd "$APP_HOME"

SPRING_OPTS=(
    "--spring.config.additional-location=optional:file:${APP_HOME}/conf/"
    "--server.port=${APP_PORT}"
)

if [[ -f "$APP_HOME/conf/application-local.yml" || -f "$APP_HOME/conf/application-local.yaml" ]]; then
    SPRING_OPTS+=("--spring.profiles.active=local")
fi

case "$JAVA_SOURCE" in
    bundled) java_desc="内置 JDK ($JAVA_CMD)" ;;
    JAVA_HOME) java_desc="系统 JAVA_HOME ($JAVA_CMD)" ;;
    PATH) java_desc="系统 PATH ($JAVA_CMD)" ;;
    *) java_desc="$JAVA_CMD" ;;
esac

echo "启动 Data Generator ..."
echo "  安装目录: $APP_HOME"
echo "  Java:     $java_desc"
echo "  端口:     $APP_PORT (来源: ${APP_PORT_SOURCE:-未知})"
echo "  JAR:      $APP_JAR"
echo "  日志:     $LOG_FILE"

: >"$LOG_FILE"

nohup "$JAVA_CMD" ${JAVA_OPTS:-} -jar "$APP_JAR" "${SPRING_OPTS[@]}" \
    >"$LOG_FILE" 2>&1 &

new_pid=$!
echo "$new_pid" > "$PID_FILE"

if ! wait_for_app_ready "$new_pid" "$APP_PORT" "$LOG_FILE"; then
    rm -f "$PID_FILE"
    exit 1
fi

echo "已启动 (PID=$new_pid)，访问 http://localhost:${APP_PORT}"
