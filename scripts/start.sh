#!/bin/bash
#
# Redis Cluster Manager 启动脚本（Linux/macOS）
# 特性：根据系统资源动态优化 JVM 参数，支持日志级别配置
#
# 用法: ./start.sh [jar文件名] [日志级别]
# 示例: ./start.sh redis-cluster-manager-1.0.0.jar DEBUG
#       ./start.sh                          # 使用默认配置 (INFO)
#       ./start.sh my-app.jar DEBUG         # 指定 JAR 和 DEBUG 日志
#

# 脚本所在目录（即项目根目录）
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
# Jar 文件名（默认值或命令行参数）
JAR_NAME="${1:-redis-cluster-manager-1.0.0.jar}"
# 日志级别（默认值或命令行参数）：DEBUG/INFO/WARN/ERROR
LOG_LEVEL="${2:-INFO}"
# Jar 文件路径（在项目根目录下）
JAR_FILE="${APP_HOME}/${JAR_NAME}"
# 日志目录
LOG_DIR="${APP_HOME}/logs"
# PID 文件
PID_FILE="${APP_HOME}/redis-manager.pid"

# 应用配置
APP_NAME="Redis Cluster Manager"
SERVER_PORT="${SERVER_PORT:-8080}"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 检查 Java 环境
check_java() {
    if ! command -v java &> /dev/null; then
        echo -e "${RED}错误：未找到 Java 环境，请先安装 JDK 1.8+${NC}"
        exit 1
    fi
    
    JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
    echo -e "${BLUE}Java 版本: ${JAVA_VERSION}${NC}"
}

# 获取系统内存（MB）
get_system_memory() {
    local mem_mb
    if command -v free &> /dev/null; then
        # Linux
        mem_mb=$(free -m | awk '/^Mem:/{print $2}')
    elif command -v vm_stat &> /dev/null; then
        # macOS
        local pagesize=$(vm_stat | grep "page size" | awk '{print $8}')
        local free_pages=$(vm_stat | grep "Pages free" | awk '{print $3}' | tr -d '.')
        local inactive_pages=$(vm_stat | grep "Pages inactive" | awk '{print $3}' | tr -d '.')
        mem_mb=$(( (free_pages + inactive_pages) * pagesize / 1024 / 1024 ))
    else
        # 默认值
        mem_mb=2048
    fi
    echo "$mem_mb"
}

# 获取系统 CPU 核心数
get_cpu_cores() {
    local cores
    if command -v nproc &> /dev/null; then
        cores=$(nproc)
    elif command -v sysctl &> /dev/null; then
        cores=$(sysctl -n hw.ncpu)
    else
        cores=2
    fi
    echo "$cores"
}

# 交互式选择日志级别
select_log_level() {
    # 如果已经通过参数指定，直接返回
    if [ -n "$2" ]; then
        LOG_LEVEL=$(echo "$2" | tr '[:lower:]' '[:upper:]')
        case "$LOG_LEVEL" in
            DEBUG|INFO|WARN|ERROR)
                return
                ;;
            *)
                echo -e "${YELLOW}无效的日志级别: $LOG_LEVEL，将使用交互式选择${NC}"
                ;;
        esac
    fi
    
    echo ""
    echo -e "${BLUE}请选择日志级别:${NC}"
    echo "  1) INFO  - 常规信息（推荐，生产环境使用）"
    echo "  2) DEBUG - 调试信息（详细日志，开发调试使用）"
    echo "  3) WARN  - 警告信息（仅显示警告和错误）"
    echo "  4) ERROR - 错误信息（仅显示错误）"
    echo ""
    
    # 默认选择 INFO
    read -r -p "请输入选项 [1-4] (默认: 1): " choice
    
    case "$choice" in
        2) LOG_LEVEL="DEBUG" ;;
        3) LOG_LEVEL="WARN" ;;
        4) LOG_LEVEL="ERROR" ;;
        *) LOG_LEVEL="INFO" ;;
    esac
}

# 计算优化的 JVM 参数
calculate_jvm_opts() {
    local total_mem=$1
    local cpu_cores=$2
    
    # 堆内存计算：根据系统内存大小动态调整
    local heap_size
    if [ "$total_mem" -lt 1024 ]; then
        # 小于 1GB：使用 256MB
        heap_size="256m"
    elif [ "$total_mem" -lt 2048 ]; then
        # 1GB - 2GB：使用 512MB
        heap_size="512m"
    elif [ "$total_mem" -lt 4096 ]; then
        # 2GB - 4GB：使用 1GB
        heap_size="1g"
    elif [ "$total_mem" -lt 8192 ]; then
        # 4GB - 8GB：使用 2GB
        heap_size="2g"
    elif [ "$total_mem" -lt 16384 ]; then
        # 8GB - 16GB：使用 4GB
        heap_size="4g"
    else
        # 大于 16GB：使用 8GB
        heap_size="8g"
    fi
    
    # Metaspace 大小
    local metaspace_size="256m"
    local max_metaspace="512m"
    
    # 年轻代大小（堆的 1/3）
    local young_size
    case "$heap_size" in
        256m) young_size="96m" ;;
        512m) young_size="192m" ;;
        1g) young_size="384m" ;;
        2g) young_size="768m" ;;
        4g) young_size="1536m" ;;
        8g) young_size="3g" ;;
        *) young_size="384m" ;;
    esac
    
    # GC 线程数
    local gc_threads
    if [ "$cpu_cores" -le 2 ]; then
        gc_threads=2
    elif [ "$cpu_cores" -le 4 ]; then
        gc_threads=4
    elif [ "$cpu_cores" -le 8 ]; then
        gc_threads=6
    else
        gc_threads=8
    fi
    
    # 根据内存大小选择 GC 算法
    local gc_opts=""
    if [ "$total_mem" -lt 4096 ]; then
        # 小内存使用 Serial GC 或 G1GC
        gc_opts="-XX:+UseG1GC -XX:MaxGCPauseMillis=200"
    else
        # 大内存使用 G1GC
        gc_opts="-XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+UseStringDeduplication"
    fi
    
    # 构造 JVM 参数
    JVM_OPTS="-server"
    JVM_OPTS="${JVM_OPTS} -Xms${heap_size}"
    JVM_OPTS="${JVM_OPTS} -Xmx${heap_size}"
    JVM_OPTS="${JVM_OPTS} -Xmn${young_size}"
    JVM_OPTS="${JVM_OPTS} -XX:MetaspaceSize=${metaspace_size}"
    JVM_OPTS="${JVM_OPTS} -XX:MaxMetaspaceSize=${max_metaspace}"
    JVM_OPTS="${JVM_OPTS} ${gc_opts}"
    JVM_OPTS="${JVM_OPTS} -XX:ParallelGCThreads=${gc_threads}"
    JVM_OPTS="${JVM_OPTS} -XX:ConcGCThreads=$((gc_threads / 2))"
    
    # OOM 时生成堆转储
    JVM_OPTS="${JVM_OPTS} -XX:+HeapDumpOnOutOfMemoryError"
    JVM_OPTS="${JVM_OPTS} -XX:HeapDumpPath=${LOG_DIR}/heapdump_$(date +%Y%m%d_%H%M%S).hprof"
    
    # GC 日志（Java 8 和 Java 11+ 兼容）
    local java_major_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
    if [ "$java_major_version" -ge 11 ]; then
        JVM_OPTS="${JVM_OPTS} -Xlog:gc*:file=${LOG_DIR}/gc.log:time,uptime,level,tags:filecount=5,filesize=100m"
    else
        JVM_OPTS="${JVM_OPTS} -Xloggc:${LOG_DIR}/gc.log"
        JVM_OPTS="${JVM_OPTS} -XX:+PrintGCDetails"
        JVM_OPTS="${JVM_OPTS} -XX:+PrintGCDateStamps"
        JVM_OPTS="${JVM_OPTS} -XX:+UseGCLogFileRotation"
        JVM_OPTS="${JVM_OPTS} -XX:NumberOfGCLogFiles=5"
        JVM_OPTS="${JVM_OPTS} -XX:GCLogFileSize=100M"
    fi
    
    # 性能优化参数
    JVM_OPTS="${JVM_OPTS} -XX:+AlwaysPreTouch"
    JVM_OPTS="${JVM_OPTS} -XX:+DisableExplicitGC"
    JVM_OPTS="${JVM_OPTS} -XX:+UseCompressedOops"
    JVM_OPTS="${JVM_OPTS} -XX:+OptimizeStringConcat"
    
    # 输出优化信息
    echo -e "${GREEN}系统资源检测：${NC}"
    echo -e "  - 总内存: ${total_mem} MB"
    echo -e "  - CPU核心: ${cpu_cores}"
    echo -e "${GREEN}JVM 参数优化：${NC}"
    echo -e "  - 堆内存: ${heap_size}"
    echo -e "  - 年轻代: ${young_size}"
    echo -e "  - Metaspace: ${metaspace_size} - ${max_metaspace}"
    echo -e "  - GC线程: ${gc_threads}"
}

# 检查是否在运行
check_running() {
    if [ -f "$PID_FILE" ]; then
        local pid=$(cat "$PID_FILE")
        if ps -p "$pid" > /dev/null 2>&1; then
            return 0
        fi
    fi
    return 1
}

# 启动应用
start_app() {
    if check_running; then
        echo -e "${YELLOW}${APP_NAME} 已经在运行中 (PID: $(cat "$PID_FILE"))${NC}"
        exit 1
    fi
    
    # 检查 jar 文件
    if [ ! -f "$JAR_FILE" ]; then
        echo -e "${RED}错误：未找到 JAR 文件: ${JAR_FILE}${NC}"
        echo -e "${YELLOW}请先执行: mvn clean package -DskipTests${NC}"
        exit 1
    fi
    
    # 创建日志目录
    mkdir -p "$LOG_DIR"
    
    # 选择日志级别
    select_log_level "$@"
    
    # 获取系统资源
    local total_mem=$(get_system_memory)
    local cpu_cores=$(get_cpu_cores)
    
    # 计算 JVM 参数
    calculate_jvm_opts "$total_mem" "$cpu_cores"
    
    # 根据日志级别设置日志配置
    local logging_config=""
    case "$LOG_LEVEL" in
        DEBUG)
            logging_config="--logging.level.root=DEBUG --logging.level.com.redis.manager=DEBUG"
            echo -e "${YELLOW}日志级别: DEBUG (将输出详细调试信息)${NC}"
            ;;
        WARN)
            logging_config="--logging.level.root=WARN --logging.level.com.redis.manager=WARN"
            echo -e "${YELLOW}日志级别: WARN (仅显示警告和错误)${NC}"
            ;;
        ERROR)
            logging_config="--logging.level.root=ERROR --logging.level.com.redis.manager=ERROR"
            echo -e "${YELLOW}日志级别: ERROR (仅显示错误)${NC}"
            ;;
        *)
            logging_config="--logging.level.root=INFO --logging.level.com.redis.manager=INFO"
            echo -e "${GREEN}日志级别: INFO (常规信息)${NC}"
            ;;
    esac
    
    echo -e "${GREEN}正在启动 ${APP_NAME}...${NC}"
    echo -e "${BLUE}JAR 文件: ${JAR_FILE}${NC}"
    echo -e "${BLUE}端口: ${SERVER_PORT}${NC}"
    echo -e "${BLUE}日志: ${LOG_DIR}/redis-manager.log${NC}"
    
    # 启动应用
    nohup java ${JVM_OPTS} \
        -jar "${JAR_FILE}" \
        --server.port=${SERVER_PORT} \
        --logging.file.name=${LOG_DIR}/redis-manager.log \
        ${logging_config} \
        > /dev/null 2>&1 &
    
    local pid=$!
    echo $pid > "$PID_FILE"
    
    # 等待启动
    echo -e "${YELLOW}等待应用启动...${NC}"
    local count=0
    while [ $count -lt 30 ]; do
        if ps -p "$pid" > /dev/null 2>&1; then
            # 检查端口是否监听
            if command -v netstat &> /dev/null; then
                if netstat -tlnp 2>/dev/null | grep -q ":${SERVER_PORT}"; then
                    echo -e "${GREEN}${APP_NAME} 启动成功！${NC}"
                    echo -e "${GREEN}访问地址: http://localhost:${SERVER_PORT}${NC}"
                    echo -e "${GREEN}PID: ${pid}${NC}"
                    return 0
                fi
            elif command -v ss &> /dev/null; then
                if ss -tlnp 2>/dev/null | grep -q ":${SERVER_PORT}"; then
                    echo -e "${GREEN}${APP_NAME} 启动成功！${NC}"
                    echo -e "${GREEN}访问地址: http://localhost:${SERVER_PORT}${NC}"
                    echo -e "${GREEN}PID: ${pid}${NC}"
                    return 0
                fi
            fi
        else
            echo -e "${RED}${APP_NAME} 启动失败，请检查日志${NC}"
            rm -f "$PID_FILE"
            return 1
        fi
        sleep 1
        count=$((count + 1))
        echo -n "."
    done
    
    echo -e "\n${YELLOW}${APP_NAME} 启动中，PID: ${pid}${NC}"
    echo -e "${YELLOW}请稍后访问: http://localhost:${SERVER_PORT}${NC}"
}

# 显示帮助信息
show_help() {
    echo "用法: $0 [jar文件名] [日志级别]"
    echo ""
    echo "参数:"
    echo "  jar文件名    可选，JAR 文件名，默认为 redis-cluster-manager-1.0.0.jar"
    echo "  日志级别     可选，DEBUG/INFO/WARN/ERROR，默认为 INFO"
    echo ""
    echo "示例:"
    echo "  $0                                    # 使用默认 JAR 和 INFO 日志级别"
    echo "  $0 my-app.jar                         # 指定 JAR，使用 INFO 日志级别"
    echo "  $0 redis-cluster-manager-1.0.0.jar DEBUG   # 指定 JAR 和 DEBUG 日志级别"
    echo ""
    echo "日志级别说明:"
    echo "  DEBUG - 调试信息，输出最详细的日志（开发调试使用）"
    echo "  INFO  - 常规信息，推荐生产环境使用"
    echo "  WARN  - 警告信息，仅显示警告和错误"
    echo "  ERROR - 错误信息，仅显示错误"
    echo ""
    echo "环境变量:"
    echo "  SERVER_PORT  服务端口，默认为 8080"
}

# 主函数
main() {
    # 检查帮助参数
    if [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
        show_help
        exit 0
    fi
    
    check_java
    start_app "$@"
}

main "$@"
