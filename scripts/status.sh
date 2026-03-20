#!/bin/bash
#
# Redis Cluster Manager 状态检查脚本（Linux/macOS）
#

# 脚本所在目录（即项目根目录）
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
# PID 文件
PID_FILE="${APP_HOME}/redis-manager.pid"
# 日志目录
LOG_DIR="${APP_HOME}/logs"

# 应用配置
APP_NAME="Redis Cluster Manager"
SERVER_PORT="${SERVER_PORT:-8080}"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  ${APP_NAME} 状态检查${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# 检查运行状态
check_running() {
    if [ -f "$PID_FILE" ]; then
        local pid=$(cat "$PID_FILE")
        if ps -p "$pid" > /dev/null 2>&1; then
            return 0
        fi
    fi
    return 1
}

# 获取系统资源信息
get_system_info() {
    echo -e "${YELLOW}【系统信息】${NC}"
    
    # 操作系统
    if [ -f /etc/os-release ]; then
        local os=$(grep PRETTY_NAME /etc/os-release | cut -d '"' -f 2)
        echo -e "  操作系统: ${os}"
    elif [ "$(uname)" == "Darwin" ]; then
        echo -e "  操作系统: macOS $(sw_vers -productVersion)"
    else
        echo -e "  操作系统: $(uname -s) $(uname -r)"
    fi
    
    # CPU 信息
    local cpu_cores
    if command -v nproc &> /dev/null; then
        cpu_cores=$(nproc)
    elif command -v sysctl &> /dev/null; then
        cpu_cores=$(sysctl -n hw.ncpu)
    else
        cpu_cores="未知"
    fi
    echo -e "  CPU核心: ${cpu_cores}"
    
    # 内存信息
    if command -v free &> /dev/null; then
        local mem_info=$(free -h | awk '/^Mem:/{print $2" 总计, "$3" 已用, "$4" 空闲"}')
        echo -e "  内存: ${mem_info}"
    elif command -v vm_stat &> /dev/null; then
        echo -e "  内存: $(vm_stat | grep "Pages free" | awk '{print $3}' | tr -d '.') 页空闲"
    fi
    
    echo ""
}

# 获取 Java 进程信息
get_process_info() {
    local pid=$1
    
    echo -e "${YELLOW}【进程信息】${NC}"
    echo -e "  PID: ${pid}"
    
    # 运行时间
    local etime=$(ps -p "$pid" -o etime= 2>/dev/null | tr -d ' ')
    echo -e "  运行时间: ${etime}"
    
    # CPU 和内存使用率
    if command -v ps &> /dev/null; then
        local cpu_mem=$(ps -p "$pid" -o %cpu,%mem --no-headers 2>/dev/null)
        if [ -n "$cpu_mem" ]; then
            echo -e "  CPU/内存: ${cpu_mem}"
        fi
    fi
    
    # 线程数
    local threads=$(ls /proc/$pid/task 2>/dev/null | wc -l)
    if [ "$threads" -gt 0 ]; then
        echo -e "  线程数: ${threads}"
    fi
    
    # 启动时间
    local start_time=$(ps -p "$pid" -o lstart= 2>/dev/null)
    if [ -n "$start_time" ]; then
        echo -e "  启动时间: ${start_time}"
    fi
    
    echo ""
}

# 获取 JVM 信息
get_jvm_info() {
    local pid=$1
    
    if command -v jcmd &> /dev/null; then
        echo -e "${YELLOW}【JVM 信息】${NC}"
        
        # JVM 版本
        local jvm_version=$(jcmd "$pid" VM.version 2>/dev/null | head -1)
        if [ -n "$jvm_version" ]; then
            echo -e "  ${jvm_version}"
        fi
        
        # GC 信息
        local gc_info=$(jcmd "$pid" GC.run 2>/dev/null)
        if [ -n "$gc_info" ]; then
            echo -e "  GC算法: $(echo "$gc_info" | head -1)"
        fi
        
        echo ""
    fi
}

# 检查端口监听
check_port() {
    echo -e "${YELLOW}【网络状态】${NC}"
    
    if command -v netstat &> /dev/null; then
        local port_info=$(netstat -tlnp 2>/dev/null | grep ":${SERVER_PORT}")
        if [ -n "$port_info" ]; then
            echo -e "  端口 ${SERVER_PORT}: ${GREEN}正在监听${NC}"
            echo -e "  连接信息:"
            echo "$port_info" | while read line; do
                echo -e "    ${line}"
            done
        else
            echo -e "  端口 ${SERVER_PORT}: ${RED}未监听${NC}"
        fi
    elif command -v ss &> /dev/null; then
        local port_info=$(ss -tlnp 2>/dev/null | grep ":${SERVER_PORT}")
        if [ -n "$port_info" ]; then
            echo -e "  端口 ${SERVER_PORT}: ${GREEN}正在监听${NC}"
        else
            echo -e "  端口 ${SERVER_PORT}: ${RED}未监听${NC}"
        fi
    else
        echo -e "  端口 ${SERVER_PORT}: 无法检测（缺少 netstat/ss 工具）"
    fi
    
    echo ""
}

# 检查日志
get_log_info() {
    echo -e "${YELLOW}【日志信息】${NC}"
    
    if [ -f "${LOG_DIR}/redis-manager.log" ]; then
        local log_size=$(ls -lh "${LOG_DIR}/redis-manager.log" | awk '{print $5}')
        echo -e "  应用日志: ${LOG_DIR}/redis-manager.log (${log_size})"
        
        # 最后几行日志
        if command -v tail &> /dev/null; then
            echo -e "  最后3行日志:"
            tail -3 "${LOG_DIR}/redis-manager.log" | sed 's/^/    /'
        fi
    else
        echo -e "  应用日志: 未找到"
    fi
    
    if [ -f "${LOG_DIR}/gc.log" ]; then
        local gc_size=$(ls -lh "${LOG_DIR}/gc.log" | awk '{print $5}')
        echo -e "  GC日志: ${LOG_DIR}/gc.log (${gc_size})"
    fi
    
    echo ""
}

# 显示状态
show_status() {
    get_system_info
    
    if check_running; then
        local pid=$(cat "$PID_FILE")
        echo -e "${GREEN}【运行状态】正在运行 ✓${NC}"
        echo ""
        
        get_process_info "$pid"
        get_jvm_info "$pid"
        check_port
        get_log_info
        
        echo -e "${BLUE}访问地址: http://localhost:${SERVER_PORT}${NC}"
    else
        echo -e "${RED}【运行状态】未运行 ✗${NC}"
        echo ""
        
        if [ -f "$PID_FILE" ]; then
            echo -e "${YELLOW}提示: PID 文件存在但进程已停止，建议删除 ${PID_FILE}${NC}"
        fi
        
        echo -e "使用以下命令启动:"
        echo -e "  ${GREEN}./start.sh${NC}"
    fi
}

# 主函数
main() {
    show_status
}

main "$@"
