#!/usr/bin/env bash
# 按服务角色（web / ai）启动、停止、查看状态

start_app() {
    local role="${1:-web}"
    init_app_env "$role"

    local pid_file="$APP_HOME/run/$APP_PID_NAME"
    local log_file="$APP_HOME/logs/$APP_LOG_NAME"
    local run_dir="$APP_HOME/run"

    mkdir -p "$APP_HOME/logs" "$run_dir"
    if [[ "$role" == "web" ]]; then
        mkdir -p "$APP_HOME/data/configs/jobs" "$APP_HOME/data/job-logs"
    fi

    if [[ -f "$pid_file" ]]; then
        local old_pid
        old_pid="$(cat "$pid_file")"
        if kill -0 "$old_pid" 2>/dev/null; then
            if is_app_ready "$old_pid" "$APP_PORT" "$log_file"; then
                echo "$APP_DISPLAY_NAME 已在运行 (PID=$old_pid, 端口=$APP_PORT)"
                return 1
            fi
            echo "警告: 发现未就绪的残留进程 (PID=$old_pid)，正在停止 ..."
            kill "$old_pid" 2>/dev/null || true
            sleep 2
            kill -9 "$old_pid" 2>/dev/null || true
        fi
        rm -f "$pid_file"
    fi

    cd "$APP_HOME"

    local spring_opts=(
        "--spring.config.additional-location=optional:file:${APP_CONF_DIR}/"
        "--server.port=${APP_PORT}"
    )

    if [[ -f "$APP_CONF_DIR/application-local.yml" || -f "$APP_CONF_DIR/application-local.yaml" ]]; then
        spring_opts+=("--spring.profiles.active=local")
    fi

    local java_desc
    case "$JAVA_SOURCE" in
        bundled) java_desc="内置 JDK ($JAVA_CMD)" ;;
        JAVA_HOME) java_desc="系统 JAVA_HOME ($JAVA_CMD)" ;;
        PATH) java_desc="系统 PATH ($JAVA_CMD)" ;;
        *) java_desc="$JAVA_CMD" ;;
    esac

    echo "启动 $APP_DISPLAY_NAME ..."
    echo "  安装目录: $APP_HOME"
    echo "  Java:     $java_desc"
    echo "  端口:     $APP_PORT (来源: ${APP_PORT_SOURCE:-未知})"
    echo "  JAR:      $APP_JAR"
    echo "  配置:     $APP_CONF_DIR"
    echo "  日志:     $log_file"

    : >"$log_file"

    nohup "$JAVA_CMD" ${JAVA_OPTS:-} -jar "$APP_JAR" "${spring_opts[@]}" \
        >"$log_file" 2>&1 &

    local new_pid=$!
    echo "$new_pid" > "$pid_file"

    if ! wait_for_app_ready "$new_pid" "$APP_PORT" "$log_file"; then
        rm -f "$pid_file"
        return 1
    fi

    echo "已启动 (PID=$new_pid)，访问 http://localhost:${APP_PORT}"
}

stop_app() {
    local role="${1:-web}"
    init_app_env "$role"

    local pid_file="$APP_HOME/run/$APP_PID_NAME"

    if [[ ! -f "$pid_file" ]]; then
        echo "$APP_DISPLAY_NAME: 未找到 PID 文件，服务可能未运行"
        return 0
    fi

    local pid
    pid="$(cat "$pid_file")"

    if ! kill -0 "$pid" 2>/dev/null; then
        echo "$APP_DISPLAY_NAME: 进程 $pid 不存在，清理 PID 文件"
        rm -f "$pid_file"
        return 0
    fi

    echo "正在停止 $APP_DISPLAY_NAME (PID=$pid) ..."

    kill "$pid" 2>/dev/null || true

    local i
    for ((i = 1; i <= 30; i++)); do
        if ! kill -0 "$pid" 2>/dev/null; then
            rm -f "$pid_file"
            echo "$APP_DISPLAY_NAME: 已停止"
            return 0
        fi
        sleep 1
    done

    echo "$APP_DISPLAY_NAME: 优雅停止超时，强制终止 ..."
    kill -9 "$pid" 2>/dev/null || true
    rm -f "$pid_file"
    echo "$APP_DISPLAY_NAME: 已强制停止"
}

status_app() {
    local role="${1:-web}"
    init_app_env "$role"

    local pid_file="$APP_HOME/run/$APP_PID_NAME"
    local log_file="$APP_HOME/logs/$APP_LOG_NAME"

    if [[ ! -f "$pid_file" ]]; then
        if is_port_in_use "$APP_PORT"; then
            local occupier_pid
            occupier_pid="$(get_listening_pid_on_port "$APP_PORT" 2>/dev/null || true)"
            echo "$APP_DISPLAY_NAME: 未运行 (PID 文件缺失，但端口 $APP_PORT 已被占用${occupier_pid:+, PID=$occupier_pid})"
            return 1
        fi
        echo "$APP_DISPLAY_NAME: 未运行"
        return 1
    fi

    local pid
    pid="$(cat "$pid_file")"
    if ! kill -0 "$pid" 2>/dev/null; then
        echo "$APP_DISPLAY_NAME: 未运行 (残留 PID 文件: $pid)"
        return 1
    fi

    if is_app_ready "$pid" "$APP_PORT" "$log_file"; then
        echo "$APP_DISPLAY_NAME: 运行中 (PID=$pid, 端口=$APP_PORT)"
        return 0
    fi

    echo "$APP_DISPLAY_NAME: 进程存在但未就绪 (PID=$pid, 端口=$APP_PORT)"
    return 1
}
