# Redis Cluster Manager（缓存3×3英雄）

企业级Redis集群可视化管理平台，专注于3×3（3主3从）标准架构，支持多服务器组管理、集群快速创建与导入、实时监控、动态参数配置、自动数据备份等完整功能，让Redis集群运维更简单高效。

## 功能特性

### 核心功能

| 模块 | 功能说明 |
|------|----------|
| **用户认证** | 安全的登录/登出机制，支持修改密码，Session会话管理 |
| **服务器组管理** | 支持3-6台服务器分组管理，SSH连接测试，系统信息采集 |
| **集群生命周期** | 创建、启动、停止、删除、导入现有集群 |
| **配置模板** | 按Redis版本（5.x/6.x）管理配置模板，支持占位符动态替换 |
| **动态配置** | 运行时修改Redis参数（CONFIG SET），支持单实例和批量操作 |
| **系统配置** | 动态调整系统名称、Logo、Icon、登录页文案、主题色等展示内容 |
| **数据浏览** | 查询缓存Key列表，查看Key的Value值和类型 |
| **多维度监控** | 物理机监控、集群监控（融合实例监控）两层监控体系 |
| **数据备份** | 每日定时自动备份、手动备份、备份文件管理（支持分页和删除） |
| **首页性能优化** | 数据库缓存查询，秒级加载，支持手动实时刷新 |
| **集群名称编辑** | 支持修改已创建/导入集群的名称 |

### 支持的集群架构

- **标准3×3架构（推荐）**：3主3从，共6个节点
  - 支持部署在3台服务器（每台1主1从）或6台服务器（每台1个节点）
  - 自动主从配对，智能分配槽位

> ⚠️ **注意**：系统当前仅支持3×3架构（3主3从），暂不支持其他节点数配置。

## 技术架构

### 系统架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        Web 前端层                                │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌───────────┐  │
│  │   首页仪表盘 │ │ 服务器组管理  │ │  集群管理    │ │  监控中心   │ │
│  └─────────────┘ └─────────────┘ └─────────────┘ └───────────┘ │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌───────────┐ │
│  │ 配置模板管理 │ │  数据浏览    │ │  数据备份    │ │  用户中心   │ │
│  └─────────────┘ └─────────────┘ └─────────────┘ └───────────┘ │
└─────────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────────┐
│                      RESTful API 层                              │
│  /api/users  /api/groups  /api/clusters  /api/monitor          │
│  /api/config  /api/deploy  /api/upload  /api/backup             │
└─────────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────────┐
│                      业务服务层                                  │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌───────────┐ │
│  │ UserService │ │ServerGroup  │ │Cluster      │ │RedisConfig│ │
│  │             │ │   Service   │ │  Service    │ │  Service  │ │
│  └─────────────┘ └─────────────┘ └─────────────┘ └───────────┘ │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌───────────┐ │
│  │RedisDeploy  │ │RedisMonitor │ │FileUpload   │ │Database   │ │
│  │  Service    │ │  Service    │ │  Service    │ │  Backup   │ │
│  └─────────────┘ └─────────────┘ └─────────────┘ └───────────┘ │
└─────────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────────┐
│                      数据访问层                                  │
│              JPA Repository (H2 文件数据库)                      │
└─────────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────────┐
│                      基础设施层                                  │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌───────────┐ │
│  │  SSH连接池  │ │ Jedis客户端 │ │Thymeleaf    │ │  文件系统  │ │
│  │  (JSch)     │ │             │ │ 模板引擎    │ │           │ │
│  └─────────────┘ └─────────────┘ └─────────────┘ └───────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

### 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 2.7.18 | 核心框架 |
| JDK | 1.8+ | Java运行时 |
| Thymeleaf | 3.x | 服务端模板引擎 |
| Spring Data JPA | 2.7.x | 数据持久层 |
| H2 Database | 2.1.214 | 嵌入式文件数据库 |
| Jedis | 4.4.6 | Redis Java客户端 |
| JSch | 0.1.55 | SSH连接库 |
| Fastjson | 2.0.43 | JSON处理 |
| Apache Commons | 3.12.0 | 工具库 |

## 系统要求

### 服务端要求

- **JDK**: 1.8 或更高版本
- **Maven**: 3.6+（用于编译构建）
- **内存**: 建议 2GB+
- **磁盘**: 建议 10GB+ 可用空间

### 被管理服务器要求

- **操作系统**: Linux（CentOS 7+/Ubuntu 16.04+）
- **Redis版本**: 5.0+ 或 6.0+
- **SSH服务**: 已启用并允许远程连接
- **端口**: Redis端口和集群总线端口（port + 10000）需开放

## 快速开始

### 1. 环境准备

确保已安装JDK和Maven：

```bash
# 检查JDK版本
java -version

# 检查Maven版本
mvn -version
```

### 2. 下载与编译

```bash
# 克隆或下载项目
cd redis-cluster-manager

# 编译打包（跳过测试加速构建）
mvn clean package -DskipTests

# 生成的jar包位于：target/redis-cluster-manager-1.0.0.jar
```

### 3. 启动应用

```bash
# 进入target目录
cd target

# 方式1：直接运行
java -jar redis-cluster-manager-1.0.0.jar

# 方式2：指定端口运行
java -jar redis-cluster-manager-1.0.0.jar --server.port=8080

# 方式3：使用脚本启动（推荐生产环境）
cd scripts
./start.sh
```

### 4. 访问系统

打开浏览器访问：http://localhost:8080

默认登录账号：
- 用户名：`admin`
- 密码：`admin`

> ⚠️ **安全提示**：生产环境请务必修改默认密码！

## 使用指南

### 一、修改密码

登录后点击右上角管理员下拉菜单，选择"修改密码"：
1. 输入当前密码
2. 输入新密码（至少6位）
3. 确认新密码
4. 点击"确认修改"
5. 修改成功后需要重新登录

### 二、初始化配置模板

系统启动时会自动初始化Redis 5.x和6.x的默认配置模板。您也可以：

1. 进入"配置模板"菜单
2. 点击"新建模板"创建自定义模板
3. 支持占位符：`${PORT}`, `${NODE_IP}`, `${DATA_DIR}`, `${MAX_MEMORY}`, `${REQUIREPASS}`等

### 三、创建服务器组

1. 点击"服务器组"菜单 → "新建服务器组"
2. 填写基本信息：
   - 组名称（唯一）
   - 描述（可选）
3. 配置服务器信息（3-6台）：
   - IP地址
   - SSH端口（默认22）
   - 用户名/密码
   - Redis安装路径（默认/usr/local/bin）
4. 保存时自动验证SSH连接和Redis环境

### 四、创建Redis集群

1. 进入"集群管理" → "创建集群"
2. 选择服务器组（必须已配置且验证通过）
3. 配置集群参数：
   - 集群名称
   - Redis版本（5.x或6.x）
   - 配置模板
   - 基础端口（如6001）
   - 集群密码（可选）
   - 最大内存限制
   - 内存淘汰策略
   - 数据目录（可选，默认/data/redis/{port}）
4. 点击"预览配置"查看生成的redis.conf
5. 确认无误后点击"创建集群"
6. 等待异步创建完成（约30-60秒）

> **架构说明**：系统仅支持3×3架构（3主3从），主节点和从节点会自动分配。

### 五、导入现有集群

> **⚠️ 导入前提条件**
> 1. **集群必须正常运行** - 只有正常服务的集群才能获取集群结构信息
> 2. **必须是3主3从架构** - 且所有节点使用**统一服务端口**

1. 进入"集群管理" → "导入集群"
2. 确认待导入集群满足上述前提条件
3. 填写集群信息：
   - 集群名称
   - 选择服务器组（集群所在的服务器组）
   - 端口号（集群的统一服务端口）
   - 密码（如有）
4. 点击"开始检查"进行验证
5. 验证通过后，点击"确认导入"

### 六、监控管理

系统提供两层监控体系：

#### 1. 物理机监控
- CPU使用率
- 内存使用率
- 磁盘使用率和I/O
- 网络流量
- 系统负载
- Swap使用率

#### 2. 集群监控（融合实例监控）
- **集群级指标**：
  - 集群健康状态
  - 槽位分配情况（16384槽位）
  - 主从复制状态
  - 总QPS
  - 总内存使用
  
- **实例级指标**：
  - 内存使用率和使用量
  - 内存碎片率
  - 连接数
  - QPS（每秒查询数）
  - P99延迟
  - 复制延迟（从节点）
  - 慢查询数量
  - AOF重写状态

#### 3. 首页仪表盘
- **全局统计卡片**：显示创建集群数、导入集群数、总节点数、服务器组数
- **集群概览表格**：展示所有集群的实时状态、内存使用、健康度等
- **列可见性控制**：可切换显示/隐藏"内存占比"和"健康度"列
- **分页功能**：支持分页浏览，默认每页5条
- **手动刷新**：点击刷新按钮可实时连接所有集群获取最新状态（带进度提示）
- **定时刷新提示**：系统每10分钟自动更新集群信息

### 七、动态配置管理

1. 进入集群详情页
2. 点击"应用配置"按钮
3. 选择配置项或手动输入参数名
4. 输入新值并应用
5. 支持批量应用到整个集群

常用可动态修改的参数：
- `maxmemory` - 最大内存限制
- `maxmemory-policy` - 淘汰策略
- `timeout` - 连接超时时间
- `tcp-keepalive` - TCP保活检测
- `save` - RDB持久化策略

### 八、系统配置管理

系统支持动态调整展示内容，管理员可通过"系统配置"页面自定义：

#### 可配置项

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| **系统名称** | 显示在侧边栏、页面标题等位置 | 缓存英雄 |
| **系统 Logo** | 侧边栏和登录页显示的图片 | /Redis.png |
| **系统 Icon** | 浏览器标签页 Favicon | /Redis.png |
| **登录页标题** | 登录页面主标题 | 缓存英雄 |
| **登录页副标题** | 登录页面副标题 | Redis集群管理，请登录继续操作 |
| **版权信息** | 登录页底部版权文字 | Redis集群管理系统 |
| **主题色** | 系统主题颜色（Redis红） | #dc382c |

#### 操作步骤

1. 以管理员身份登录系统
2. 在左侧菜单找到"系统" → "系统配置"
3. 修改需要调整的配置项：
   - **基本信息**：修改系统名称、登录页文案、版权信息
   - **图片配置**：上传自定义 Logo 和 Icon（支持 PNG、JPEG、GIF、SVG，最大 2MB）
   - **主题配置**：调整系统主题色
4. 实时预览区域会显示修改效果
5. 点击"保存配置"使修改生效

### 九、数据浏览

1. 进入集群详情页
2. 点击"数据查询"选项卡
3. 输入Key模式（如`user:*`）
4. 点击Key查看详细Value
5. 支持String、Hash、List、Set、ZSet等类型

### 十、数据备份管理

系统提供完整的数据库备份功能，确保数据安全：

#### 功能特性

| 特性 | 说明 |
|------|------|
| **定时自动备份** | 每日凌晨2点自动执行备份（可配置） |
| **手动备份** | 支持随时手动触发备份 |
| **保留策略** | 自动保留最近30天备份，过期自动清理 |
| **备份管理** | 支持分页查看、手动删除备份文件 |
| **完整性验证** | 备份完成后自动验证文件完整性 |

#### 操作步骤

1. 以管理员身份登录系统
2. 在左侧菜单找到"系统" → "数据备份"
3. 查看备份统计信息：
   - 备份总数
   - 备份总大小
   - 最新备份时间
   - 下次备份时间
4. 备份操作：
   - **立即备份**：点击"立即备份"按钮手动触发
   - **清理过期**：手动清理超过30天的备份
   - **刷新列表**：刷新备份文件列表
5. 备份文件管理：
   - 分页查看备份文件列表（每页10条）
   - 点击删除按钮可删除单个备份文件

#### 配置文件

```yaml
redis:
  manager:
    backup:
      enabled: true              # 启用备份
      cron: "0 0 2 * * ?"        # 每天凌晨2点执行
      retain-days: 30            # 保留30天
      directory: ./data/backup   # 备份目录
      database-file: ./data/redis-manager.mv.db
```

## 配置说明

### application.yml 完整配置

```yaml
server:
  port: 8080                          # Web服务端口
  servlet:
    context-path: /                   # 应用上下文路径

spring:
  application:
    name: redis-cluster-manager
  
  # 数据源配置（H2文件数据库）
  datasource:
    url: jdbc:h2:file:./data/redis-manager;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
    driver-class-name: org.h2.Driver
    username: sa
    password:
  
  # JPA配置
  jpa:
    hibernate:
      ddl-auto: update                # 自动更新表结构
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.H2Dialect
        format_sql: true
  
  # H2控制台（开发调试用）
  h2:
    console:
      enabled: true
      path: /h2-console
      settings:
        web-allow-others: true
  
  # Thymeleaf配置
  thymeleaf:
    cache: false                      # 生产环境设为true启用缓存
  
  # 静态资源配置
  web:
    resources:
      static-locations: classpath:/static/,classpath:/public/,classpath:/resources/

# Redis Manager自定义配置
redis:
  manager:
    remote-install-base: /opt/redis         # 远程安装基础路径
    remote-data-base: /data/redis           # 远程数据目录
    ssh-timeout: 30000                      # SSH连接超时（毫秒）
    monitor-interval: 600000                # 监控采集间隔（毫秒，默认10分钟）
    supported-versions:                     # 支持的Redis版本
      - redis-6.2.14
      - redis-5.0.14
    # 数据备份配置
    backup:
      enabled: true                         # 启用备份功能
      cron: "0 0 2 * * ?"                   # 备份执行时间（每天凌晨2点）
      retain-days: 30                       # 备份文件保留天数
      directory: ./data/backup              # 备份目录
      database-file: ./data/redis-manager.mv.db  # 数据库文件路径

# 日志配置
logging:
  level:
    root: INFO
    com.redis.manager: DEBUG
  file:
    name: logs/redis-manager.log
```

## 数据备份与恢复

应用使用 H2 文件数据库存储数据，数据库文件位于 `./data/redis-manager.mv.db`。

### 自动备份

系统默认开启自动备份功能：
- **备份时间**：每日凌晨 2:00
- **保留策略**：自动保留最近 30 天的备份
- **备份位置**：`./data/backup/` 目录
- **文件命名**：`redis-manager-backup-YYYYMMDD-HHMMSS.mv.db`

### 手动备份

通过 Web 界面操作：
1. 进入"系统" → "数据备份"
2. 点击"立即备份"按钮
3. 等待备份完成，刷新列表查看

或通过命令行：
```bash
# 直接复制数据库文件（应用停止时）
cp data/redis-manager.mv.db backup/redis-manager-$(date +%Y%m%d).mv.db

# 使用H2控制台导出（应用运行时）
# 访问 http://localhost:8080/h2-console
# JDBC URL: jdbc:h2:file:./data/redis-manager
# 用户名: sa
# 密码: （留空）
```

### 恢复数据

```bash
# 停止应用
./stop.sh

# 恢复数据库文件
cp backup/redis-manager-backup-20240101-120000.mv.db data/redis-manager.mv.db

# 启动应用
./start.sh
```

## API接口文档

### 用户管理

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/users/health` | GET | 健康检查（无需登录） |
| `/api/users/login` | POST | 用户登录 |
| `/api/users/logout` | POST | 用户登出 |
| `/api/users/current` | GET | 获取当前登录用户信息 |
| `/api/users/change-password` | POST | 修改密码 |

### 服务器组管理

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/groups` | GET | 获取所有服务器组 |
| `/api/groups` | POST | 创建服务器组 |
| `/api/groups/{id}` | GET | 获取服务器组详情 |
| `/api/groups/{id}` | PUT | 更新服务器组 |
| `/api/groups/{id}` | DELETE | 删除服务器组 |
| `/api/groups/{id}/test-all` | POST | 测试组内所有服务器连接 |

### 集群管理

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/clusters` | GET | 获取所有集群列表 |
| `/api/clusters` | POST | 创建新集群 |
| `/api/clusters/{id}` | GET | 获取集群详情 |
| `/api/clusters/{id}` | DELETE | 删除集群（异步） |
| `/api/clusters/{id}/start` | POST | 启动集群 |
| `/api/clusters/{id}/stop` | POST | 停止集群 |
| `/api/clusters/import` | POST | 导入现有集群 |
| `/api/clusters/{id}/nodes` | GET | 获取集群节点信息 |
| `/api/clusters/{id}/config` | POST | 更新集群参数 |
| `/api/clusters/{id}/query-keys` | GET | 查询缓存Key列表 |

### 监控接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/monitor/clusters` | GET | 获取所有集群监控状态 |
| `/api/monitor/global-statistics` | GET | 获取全局统计信息 |
| `/api/monitor/cluster-level` | GET | 获取集群级监控数据 |
| `/api/monitor/instances` | GET | 获取实例级监控数据 |
| `/api/monitor/physical` | GET | 获取物理机监控数据 |
| `/api/monitor/clusters/debug` | GET | 获取集群调试信息 |
| `/api/monitor/clusters/refresh` | POST | 手动刷新所有集群状态 |
| `/api/monitor/clusters/{id}/refresh` | POST | 手动刷新指定集群状态 |

### 系统配置

| 接口 | 方法 | 说明 | 权限 |
|------|------|------|------|
| `/api/system/config/display` | GET | 获取系统展示配置 | 公开 |
| `/api/system/config` | GET | 获取所有配置 | 登录用户 |
| `/api/system/config` | POST | 更新单个配置 | 登录用户 |
| `/api/system/config/batch` | POST | 批量更新配置 | 登录用户 |
| `/api/system/upload/logo` | POST | 上传 Logo/Icon | 登录用户 |

### 数据备份

| 接口 | 方法 | 说明 | 权限 |
|------|------|------|------|
| `/api/backup/list` | GET | 获取备份列表（分页） | 管理员 |
| `/api/backup/stats` | GET | 获取备份统计信息 | 管理员 |
| `/api/backup/manual` | POST | 手动触发备份 | 管理员 |
| `/api/backup/cleanup` | POST | 清理过期备份 | 管理员 |
| `/api/backup/delete` | POST | 删除指定备份 | 管理员 |

## 项目目录结构

```
redis-cluster-manager/
├── src/
│   └── main/
│       ├── java/com/redis/manager/
│       │   ├── RedisClusterManagerApplication.java    # 启动类
│       │   ├── config/                                 # 配置类
│       │   │   ├── BackupProperties.java               # 备份配置
│       │   ├── controller/                             # 控制器层
│       │   │   ├── BackupController.java               # 备份管理接口
│       │   ├── dto/                                    # 数据传输对象
│       │   │   ├── PageResult.java                     # 分页结果
│       │   ├── entity/                                 # 实体类
│       │   ├── repository/                             # 数据访问层
│       │   ├── service/                                # 业务逻辑层
│       │   │   ├── DatabaseBackupService.java          # 备份服务
│       │   ├── ssh/                                    # SSH工具
│       │   └── util/                                   # 工具类
│       └── resources/
│           ├── application.yml                         # 应用配置
│           ├── templates/                              # Thymeleaf模板
│           │   ├── backup.html                         # 备份管理页面
│           └── static/                                 # 静态资源
├── data/                                               # H2数据库文件
│   └── backup/                                         # 备份文件目录
├── logs/                                               # 日志文件
├── uploads/                                            # 上传文件目录
├── pom.xml                                             # Maven配置
├── build.sh                                            # Linux构建脚本
├── build.bat                                           # Windows构建脚本
└── README.md                                           # 本文件
```

## 常见问题

### 1. SSH连接失败

**现象**：服务器组保存时提示SSH连接失败

**排查步骤**：
1. 检查服务器IP、端口、用户名、密码是否正确
2. 确认目标服务器SSH服务已启动：`systemctl status sshd`
3. 检查防火墙是否允许SSH端口：`iptables -L -n | grep 22`
4. 查看应用日志获取详细错误信息

### 2. Redis命令执行失败

**现象**：提示redis-cli或redis-server不存在

**解决方案**：
1. 确认服务器已安装Redis
2. 检查"Redis安装路径"配置是否正确
3. 验证路径下是否存在redis-cli和redis-server

### 3. 集群创建失败

**现象**：集群创建过程中报错或卡住

**排查步骤**：
1. 检查端口是否被占用：`netstat -tlnp | grep {port}`
2. 检查数据目录是否有写权限
3. 查看Redis日志：`tail -f /data/redis/{port}/redis.log`
4. 确认所有节点时间同步

### 4. 导入集群后主从关系显示不正确

**现象**：导入的集群显示主从关系不正确

**解决方案**：
1. 确保集群是标准的3×3架构（3主3从）
2. 确保所有节点使用统一的服务端口
3. 检查应用日志，确认CLUSTER NODES命令返回正常
4. 如仍有问题，尝试删除后重新导入

### 5. 数据备份与恢复

**自动备份**：
- 系统默认每日凌晨2点自动备份
- 备份文件保存在 `./data/backup/` 目录
- 保留最近30天的备份，过期自动清理

**手动备份**：
```bash
# 通过Web界面操作：系统 → 数据备份 → 立即备份
```

**恢复**：
```bash
./stop.sh
cp backup/redis-manager-backup-20240101-120000.mv.db data/redis-manager.mv.db
./start.sh
```

### 6. 编译时编码问题

**现象**：Maven编译时出现GBK编码警告

**解决方案**：
1. 检查系统环境变量 `JAVA_TOOL_OPTIONS`，删除或修改为 UTF-8：
   ```bash
   # Windows PowerShell
   $env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8"
   ```
2. 或在 IDEA 的 Maven Runner VM Options 中添加：`-Dfile.encoding=UTF-8`

## Nginx反向代理配置示例

```nginx
server {
    listen 80;
    server_name redis-manager.example.com;
    return 301 https://$server_name$request_uri;
}

server {
    listen 443 ssl;
    server_name redis-manager.example.com;

    ssl_certificate /path/to/cert.pem;
    ssl_certificate_key /path/to/key.pem;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

<p align="center">
  <b>Redis Cluster Manager - 让Redis集群管理更简单</b><br>
  Made with ❤️ by Redis Cluster Manager Team
</p>
