#!/usr/bin/env bash
# 公共环境：解析安装目录、JAR、Java、服务端口
# 注意：仅设置脚本内局部变量，不 export JAVA_HOME/PATH，不影响系统已安装的 JDK

resolve_app_home() {
    local script_dir
    script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    APP_HOME="$(cd "$script_dir/../.." && pwd)"
}

resolve_jar() {
    local lib_dir="$APP_HOME/lib"
    if [[ ! -d "$lib_dir" ]]; then
        echo "错误: 未找到 lib 目录: $lib_dir" >&2
        exit 1
    fi

    local jars=("$lib_dir"/*.jar)
    if [[ ! -e "${jars[0]}" ]]; then
        echo "错误: lib 目录下没有 jar 包: $lib_dir" >&2
        exit 1
    fi
    if [[ ${#jars[@]} -gt 1 ]]; then
        echo "警告: lib 目录存在多个 jar，使用 ${jars[0]}" >&2
    fi
    APP_JAR="${jars[0]}"
}

# 优先级：包内 jdk/ > 系统 JAVA_HOME > PATH 中的 java
resolve_java() {
    JAVA_SOURCE=""

    if [[ -x "$APP_HOME/jdk/bin/java" ]]; then
        JAVA_CMD="$APP_HOME/jdk/bin/java"
        JAVA_SOURCE="bundled"
        return
    fi

    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        JAVA_CMD="$JAVA_HOME/bin/java"
        JAVA_SOURCE="JAVA_HOME"
        return
    fi

    if command -v java >/dev/null 2>&1; then
        JAVA_CMD="$(command -v java)"
        JAVA_SOURCE="PATH"
        return
    fi

    echo "错误: 未找到 Java。" >&2
    echo "  方式一: 将 JDK 21+ 解压到 $APP_HOME/jdk（仅本应用使用，不改系统 JDK）" >&2
    echo "  方式二: 安装系统 JDK 并设置 JAVA_HOME 或加入 PATH" >&2
    exit 1
}

load_java_opts() {
    JAVA_OPTS="${JAVA_OPTS:-}"
    if [[ -f "$APP_HOME/conf/java.opts" ]]; then
        # shellcheck disable=SC1091
        source "$APP_HOME/conf/java.opts"
    fi
}

read_port_from_yaml() {
    local file="$1"
    [[ -f "$file" ]] || return 1

    awk '
        { sub(/\r$/, "") }
        /^[[:space:]]*server\.port:[[:space:]]*[0-9]+/ {
            if (match($0, /[0-9]+$/)) {
                print substr($0, RSTART, RLENGTH)
                exit
            }
        }
        /^[[:space:]]*server:[[:space:]]*$/ { in_server=1; next }
        /^[[:space:]]*server:[[:space:]]*\{/ { in_server=1; next }
        /^[^[:space:]#]/ { in_server=0 }
        in_server && /^[[:space:]]+port:[[:space:]]*[0-9]+/ {
            if (match($0, /[0-9]+$/)) {
                print substr($0, RSTART, RLENGTH)
                exit
            }
        }
    ' "$file"
}

# 优先级：SERVER_PORT > conf 下 yml（application-local* 优先）> 默认 8080
resolve_server_port() {
    APP_PORT_SOURCE=""

    if [[ -n "${SERVER_PORT:-}" ]]; then
        APP_PORT="$SERVER_PORT"
        APP_PORT_SOURCE="环境变量 SERVER_PORT"
        return
    fi

    local file port

    for file in \
        "$APP_HOME/conf/application-local.yml" \
        "$APP_HOME/conf/application-local.yaml" \
        "$APP_HOME/conf/application.yml" \
        "$APP_HOME/conf/application.yaml"
    do
        [[ -f "$file" ]] || continue
        port="$(read_port_from_yaml "$file" 2>/dev/null || true)"
        if [[ -n "$port" ]]; then
            APP_PORT="$port"
            APP_PORT_SOURCE="$file"
            return
        fi
    done

    shopt -s nullglob
    for file in "$APP_HOME/conf"/*.yml "$APP_HOME/conf"/*.yaml; do
        [[ -f "$file" ]] || continue
        case "$file" in
            "$APP_HOME/conf/application-local.yml" | \
            "$APP_HOME/conf/application-local.yaml" | \
            "$APP_HOME/conf/application.yml" | \
            "$APP_HOME/conf/application.yaml")
                continue
                ;;
        esac
        port="$(read_port_from_yaml "$file" 2>/dev/null || true)"
        if [[ -n "$port" ]]; then
            APP_PORT="$port"
            APP_PORT_SOURCE="$file"
            shopt -u nullglob
            return
        fi
    done
    shopt -u nullglob

    APP_PORT=8080
    APP_PORT_SOURCE="默认值"
}

check_java_version() {
    local version_line major
    version_line="$("$JAVA_CMD" -version 2>&1 | head -n 1)"
    if [[ "$version_line" =~ \"([0-9]+) ]]; then
        major="${BASH_REMATCH[1]}"
        if [[ "$major" -lt 21 ]]; then
            echo "警告: 需要 JDK 21+，当前: $version_line" >&2
        fi
    fi
}

is_port_in_use() {
    local port="$1"

    if command -v ss >/dev/null 2>&1; then
        # 必须用 -H 去掉表头，否则 grep 会把表头当成“端口已占用”
        if ss -ltnH "sport = :${port}" 2>/dev/null | grep -q .; then
            return 0
        fi
        # 兼容不支持 -H 的旧版 ss
        if ss -ltn "sport = :${port}" 2>/dev/null | awk 'NR > 1 { exit 0 } END { exit 1 }'; then
            return 0
        fi
        return 1
    fi

    if command -v netstat >/dev/null 2>&1; then
        if netstat -ltn 2>/dev/null | awk -v port=":${port}$" '$1 == "tcp" && $4 ~ port { found=1; exit } END { exit !found }'; then
            return 0
        fi
        return 1
    fi

    # 兜底：能连上说明有服务在监听
    (echo >/dev/tcp/127.0.0.1/"${port}") 2>/dev/null
}

get_listening_pid_on_port() {
    local port="$1"
    local pid=""

    if command -v ss >/dev/null 2>&1; then
        pid="$(ss -ltnp "sport = :${port}" 2>/dev/null | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2)"
        if [[ -n "$pid" ]]; then
            echo "$pid"
            return 0
        fi
    fi

    if command -v lsof >/dev/null 2>&1; then
        pid="$(lsof -tiTCP:"${port}" -sTCP:LISTEN 2>/dev/null | head -n1)"
        if [[ -n "$pid" ]]; then
            echo "$pid"
            return 0
        fi
    fi

    if command -v fuser >/dev/null 2>&1; then
        pid="$(fuser "${port}/tcp" 2>/dev/null | tr -s ' ' '\n' | grep -E '^[0-9]+$' | head -n1)"
        if [[ -n "$pid" ]]; then
            echo "$pid"
            return 0
        fi
    fi

    return 1
}

log_has_startup_failure() {
    local log_file="$1"
    [[ -f "$log_file" ]] || return 1
    grep -qE 'Application run failed|APPLICATION FAILED TO START|PortInUseException|Address already in use|Web server failed to start|BindException|Failed to bind to|端口.*已被占用' "$log_file" 2>/dev/null
}

log_has_startup_success() {
    local log_file="$1"
    [[ -f "$log_file" ]] || return 1
    grep -qE 'Started DataGeneratorApplication|Tomcat started on port' "$log_file" 2>/dev/null
}

is_app_ready() {
    local pid="$1"
    local port="$2"
    local log_file="$3"
    local listen_pid=""

    kill -0 "$pid" 2>/dev/null || return 1

    listen_pid="$(get_listening_pid_on_port "$port" 2>/dev/null || true)"
    if [[ -n "$listen_pid" && "$listen_pid" == "$pid" ]]; then
        return 0
    fi

    if log_has_startup_success "$log_file"; then
        return 0
    fi

    return 1
}

wait_for_app_ready() {
    local pid="$1"
    local port="$2"
    local log_file="$3"
    local timeout="${STARTUP_TIMEOUT:-90}"
    local i

    echo "等待服务就绪 (最多 ${timeout}s) ..."
    for ((i = 1; i <= timeout; i++)); do
        if is_app_ready "$pid" "$port" "$log_file"; then
            return 0
        fi

        if ! kill -0 "$pid" 2>/dev/null; then
            echo "错误: 进程已退出，启动失败" >&2
            tail -n 40 "$log_file" >&2 2>/dev/null || true
            return 1
        fi

        # 进程仍在运行时不根据日志判失败（避免 Spring 尚未写完日志时误判）
        sleep 1
    done

    if log_has_startup_failure "$log_file"; then
        echo "错误: 启动失败，请查看日志: $log_file" >&2
        tail -n 40 "$log_file" >&2 2>/dev/null || true
        kill "$pid" 2>/dev/null || true
        return 1
    fi

    echo "错误: 启动超时 (${timeout}s)，端口 ${port} 未就绪" >&2
    tail -n 40 "$log_file" >&2 2>/dev/null || true
    kill "$pid" 2>/dev/null || true
    return 1
}

init_app_env() {
    resolve_app_home
    resolve_jar
    resolve_java
    load_java_opts
    resolve_server_port
    check_java_version
}
