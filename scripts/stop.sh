#!/bin/bash
#
# Redis Cluster Manager 停止脚本（Linux/macOS）
#
# 用法: ./stop.sh [pid文件名]
# 示例: ./stop.sh redis-manager.pid
#

# 脚本所在目录（即项目根目录）
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
# PID 文件名（默认值或命令行参数）
PID_NAME="${1:-redis-manager.pid}"
# PID 文件路径
PID_FILE="${APP_HOME}/${PID_NAME}"

# 应用配置
APP_NAME="Redis Cluster Manager"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

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

# 停止应用
stop_app() {
    if ! check_running; then
        echo -e "${YELLOW}${APP_NAME} 未在运行${NC}"
        rm -f "$PID_FILE"
        return 0
    fi
    
    local pid=$(cat "$PID_FILE")
    echo -e "${BLUE}正在停止 ${APP_NAME} (PID: ${pid})...${NC}"
    
    # 发送 SIGTERM 信号
    kill "$pid"
    
    # 等待进程结束
    local count=0
    while [ $count -lt 30 ]; do
        if ! ps -p "$pid" > /dev/null 2>&1; then
            echo -e "${GREEN}${APP_NAME} 已停止${NC}"
            rm -f "$PID_FILE"
            return 0
        fi
        sleep 1
        count=$((count + 1))
        echo -n "."
    done
    
    # 如果还在运行，发送 SIGKILL
    echo -e "\n${YELLOW}正常停止超时，强制终止...${NC}"
    kill -9 "$pid" 2>/dev/null
    sleep 1
    
    if ! ps -p "$pid" > /dev/null 2>&1; then
        echo -e "${GREEN}${APP_NAME} 已强制停止${NC}"
        rm -f "$PID_FILE"
        return 0
    else
        echo -e "${RED}停止失败，请手动终止进程 ${pid}${NC}"
        return 1
    fi
}

# 主函数
main() {
    stop_app
}

main "$@"
