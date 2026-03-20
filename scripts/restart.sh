#!/bin/bash
#
# Redis Cluster Manager 重启脚本（Linux/macOS）
#
# 用法: ./restart.sh [jar文件名] [日志级别] [pid文件名]
# 示例: 
#   ./restart.sh                          # 使用默认配置
#   ./restart.sh my-app.jar DEBUG         # 指定 JAR 和 DEBUG 日志级别
#   ./restart.sh my-app.jar INFO my.pid   # 指定 JAR、日志级别和 PID 文件
#

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# 获取参数
JAR_NAME="${1:-}"
LOG_LEVEL="${2:-}"
PID_NAME="${3:-}"

# 显示帮助信息
show_help() {
    echo "用法: $0 [jar文件名] [日志级别] [pid文件名]"
    echo ""
    echo "参数:"
    echo "  jar文件名    可选，JAR 文件名，默认为 redis-cluster-manager-1.0.0.jar"
    echo "  日志级别     可选，DEBUG/INFO/WARN/ERROR，默认为 INFO（或通过交互式选择）"
    echo "  pid文件名    可选，PID 文件名，默认为 redis-manager.pid"
    echo ""
    echo "示例:"
    echo "  $0                                    # 使用默认配置"
    echo "  $0 my-app.jar                         # 指定 JAR，交互式选择日志级别"
    echo "  $0 my-app.jar DEBUG                   # 指定 JAR 和 DEBUG 日志级别"
    echo "  $0 my-app.jar WARN custom.pid         # 指定 JAR、WARN 日志级别和 PID 文件"
    echo ""
    echo "日志级别说明:"
    echo "  DEBUG - 调试信息，输出最详细的日志（开发调试使用）"
    echo "  INFO  - 常规信息，推荐生产环境使用"
    echo "  WARN  - 警告信息，仅显示警告和错误"
    echo "  ERROR - 错误信息，仅显示错误"
}

# 检查帮助参数
if [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
    show_help
    exit 0
fi

echo "=========================================="
echo "  Redis Cluster Manager 重启"
echo "=========================================="
echo ""

# 先停止
if [ -n "$PID_NAME" ]; then
    "${SCRIPT_DIR}/stop.sh" "$PID_NAME"
else
    "${SCRIPT_DIR}/stop.sh"
fi

# 等待一下
sleep 2

# 再启动
if [ -n "$JAR_NAME" ] && [ -n "$LOG_LEVEL" ]; then
    # 同时指定了 JAR 和日志级别
    "${SCRIPT_DIR}/start.sh" "$JAR_NAME" "$LOG_LEVEL"
elif [ -n "$JAR_NAME" ]; then
    # 只指定了 JAR，日志级别通过交互式选择
    "${SCRIPT_DIR}/start.sh" "$JAR_NAME"
else
    # 使用默认配置
    "${SCRIPT_DIR}/start.sh"
fi
