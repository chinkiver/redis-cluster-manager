#!/bin/bash
#
# Redis Cluster Manager 构建脚本（Linux/macOS）
#

APP_HOME="$(cd "$(dirname "$0")" && pwd)"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Redis Cluster Manager 构建脚本${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# 检查 Maven
check_maven() {
    if ! command -v mvn &> /dev/null; then
        echo -e "${RED}错误：未找到 Maven，请先安装 Maven 3.6+${NC}"
        exit 1
    fi
    
    local mvn_version=$(mvn -version 2>/dev/null | head -1 | awk '{print $3}')
    echo -e "${BLUE}Maven 版本: ${mvn_version}${NC}"
}

# 清理旧构建
clean_build() {
    echo -e "${YELLOW}正在清理旧构建...${NC}"
    cd "${APP_HOME}"
    mvn clean -q
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}清理完成${NC}"
    else
        echo -e "${RED}清理失败${NC}"
        return 1
    fi
}

# 执行构建
run_build() {
    echo -e "${YELLOW}开始编译打包...${NC}"
    cd "${APP_HOME}"
    
    # 使用 -DskipTests 跳过测试加速构建
    mvn package -DskipTests -q
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}构建成功！${NC}"
        
        # 显示构建结果
        if [ -f "${APP_HOME}/target/redis-cluster-manager-1.0.0.jar" ]; then
            local jar_size=$(ls -lh "${APP_HOME}/target/redis-cluster-manager-1.0.0.jar" | awk '{print $5}')
            echo -e "${GREEN}JAR 文件: target/redis-cluster-manager-1.0.0.jar (${jar_size})${NC}"
        fi
        
        return 0
    else
        echo -e "${RED}构建失败，请检查错误信息${NC}"
        return 1
    fi
}

# 主函数
main() {
    check_maven
    
    # 检查是否需要清理
    if [ "$1" == "clean" ] || [ "$1" == "rebuild" ]; then
        clean_build
    fi
    
    run_build
}

main "$@"
