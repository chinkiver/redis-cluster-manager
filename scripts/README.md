# Redis Cluster Manager 脚本使用说明

本目录包含 Redis Cluster Manager 的启动、停止、重启和管理脚本，支持根据系统资源动态优化 JVM 参数。

## 脚本列表

| 脚本 | 适用系统 | 说明 |
|------|----------|------|
| `start.sh` | Linux | 启动应用（带动态 JVM 优化和日志级别配置） |
| `stop.sh` | Linux | 停止应用 |
| `restart.sh` | Linux | 重启应用（支持日志级别配置） |
| `status.sh` | Linux | 查看应用状态 |
| `build.sh` | Linux | 编译构建 |

## 日志级别配置（v1.1.0 新增）

启动脚本支持通过命令行参数或交互式选择设置日志级别：

| 日志级别 | 说明 | 适用场景 |
|----------|------|----------|
| `DEBUG` | 最详细的调试信息，包括 SQL、SSH 连接、Redis 命令等 | 开发调试、问题排查 |
| `INFO`  | 常规信息，包括启动信息、操作记录等 | **生产环境推荐** |
| `WARN`  | 仅警告和错误信息 | 减少日志量 |
| `ERROR` | 仅错误信息 | 最小日志量 |

### 使用方式

**方式一：命令行参数（推荐）**

```bash
# 启动时指定 DEBUG 级别
./start.sh redis-cluster-manager-1.0.0.jar DEBUG

# 重启时指定 WARN 级别
./restart.sh redis-cluster-manager-1.0.0.jar WARN
```

**方式二：交互式选择**

不指定日志级别时，脚本会提示选择：

```
请选择日志级别:
  1) INFO  - 常规信息（推荐，生产环境使用）
  2) DEBUG - 调试信息（详细日志，开发调试使用）
  3) WARN  - 警告信息（仅显示警告和错误）
  4) ERROR - 错误信息（仅显示错误）

请输入选项 [1-4] (默认: 1):
```

## 脚本参数说明

### start.sh

```bash
./start.sh [jar文件名] [日志级别]
```

| 参数 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| jar文件名 | 否 | redis-cluster-manager-1.0.0.jar | 要启动的 JAR 文件名 |
| 日志级别 | 否 | INFO（交互式选择） | DEBUG/INFO/WARN/ERROR |

### restart.sh

```bash
./restart.sh [jar文件名] [日志级别] [pid文件名]
```

| 参数 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| jar文件名 | 否 | redis-cluster-manager-1.0.0.jar | 要启动的 JAR 文件名 |
| 日志级别 | 否 | INFO（交互式选择） | DEBUG/INFO/WARN/ERROR |
| pid文件名 | 否 | redis-manager.pid | PID 文件名 |

## 快速使用

### Linux

```bash
# 进入脚本目录
cd scripts

# 给脚本添加执行权限（首次使用）
chmod +x *.sh

# 启动应用（使用默认 JAR 文件名）
./start.sh

# 启动应用（指定 JAR 文件名）
./start.sh redis-cluster-manager-1.0.0.jar

# 启动应用（指定 JAR 文件名和 DEBUG 日志级别）
./start.sh redis-cluster-manager-1.0.0.jar DEBUG

# 查看状态
./status.sh

# 停止应用（使用默认 PID 文件）
./stop.sh

# 停止应用（指定 PID 文件名）
./stop.sh redis-manager.pid

# 重启应用
./restart.sh

# 重启应用（指定 DEBUG 日志级别）
./restart.sh redis-cluster-manager-1.0.0.jar DEBUG

# 重新构建
./build.sh clean
```

## JVM 参数动态优化说明

启动脚本会根据系统资源自动计算最优的 JVM 参数：

### 内存分配策略

| 系统内存 | 堆内存(-Xmx) | 年轻代(-Xmn) | 适用场景 |
|----------|--------------|--------------|----------|
| < 1GB | 256MB | 96MB | 测试环境 |
| 1-2GB | 512MB | 192MB | 小型部署 |
| 2-4GB | 1GB | 384MB | 开发环境 |
| 4-8GB | 2GB | 768MB | 生产环境（推荐） |
| 8-16GB | 4GB | 1536MB | 大型生产环境 |
| > 16GB | 8GB | 3GB | 超大规模部署 |

### GC 配置

- **GC 算法**：使用 G1GC，兼顾吞吐量和延迟
- **GC 线程数**：根据 CPU 核心数自动计算（2-8 线程）
- **暂停时间目标**：最大 200ms

### 其他优化参数

- `-XX:+HeapDumpOnOutOfMemoryError`：OOM 时自动生成堆转储文件
- `-XX:+AlwaysPreTouch`：启动时预先分配内存，减少运行时停顿
- `-XX:+DisableExplicitGC`：禁止显式 GC 调用
- `-XX:+UseCompressedOops`：启用压缩对象指针（64 位系统）

## 环境变量

可以通过环境变量自定义配置：

```bash
# 指定服务端口（默认 8080）
export SERVER_PORT=8081
./start.sh
```

## 日志配置

### 日志级别

启动脚本支持通过命令行参数或交互式选择设置日志级别：

| 日志级别 | 说明 | 适用场景 |
|----------|------|----------|
| `DEBUG` | 输出最详细的调试信息，包括 SQL、请求详情等 | 开发调试、问题排查 |
| `INFO`  | 输出常规信息，包括启动信息、操作记录等 | **生产环境推荐** |
| `WARN`  | 仅输出警告和错误信息 | 减少日志量 |
| `ERROR` | 仅输出错误信息 | 最小日志量 |

### 设置日志级别

**方式一：命令行参数**

```bash
# 启动时指定 DEBUG 日志级别
./start.sh redis-cluster-manager-1.0.0.jar DEBUG

# 重启时指定 WARN 日志级别
./restart.sh redis-cluster-manager-1.0.0.jar WARN
```

**方式二：交互式选择**

如果不指定日志级别，脚本会提示选择：

```
请选择日志级别:
  1) INFO  - 常规信息（推荐，生产环境使用）
  2) DEBUG - 调试信息（详细日志，开发调试使用）
  3) WARN  - 警告信息（仅显示警告和错误）
  4) ERROR - 错误信息（仅显示错误）

请输入选项 [1-4] (默认: 1):
```

### 日志位置

- **应用日志**：`logs/redis-manager.log`
- **GC 日志**：`logs/gc.log`（Linux）
- **OOM 堆转储**：`logs/heapdump_YYYYMMDD_HHMMSS.hprof`

### 查看日志

```bash
# 实时查看日志（INFO 级别）
tail -f logs/redis-manager.log

# 查看 DEBUG 级别的详细日志
tail -f logs/redis-manager.log | grep DEBUG

# 仅查看错误日志
tail -f logs/redis-manager.log | grep ERROR
```

## 故障排查

### 启动失败

1. 检查 Java 环境：`java -version`
2. 检查 JAR 文件是否存在：`ls target/*.jar`
3. 检查端口是否被占用：`netstat -tlnp | grep 8080`
4. 查看日志：`tail -f logs/redis-manager.log`

### 开启 DEBUG 日志排查问题

如果遇到问题需要详细日志，可以启动 DEBUG 模式：

```bash
# 方式一：命令行参数
./start.sh redis-cluster-manager-1.0.0.jar DEBUG

# 方式二：交互式选择
./start.sh
# 然后选择选项 2 (DEBUG)
```

DEBUG 模式会输出：
- SQL 执行语句
- 请求处理详情
- SSH 连接过程
- Redis 命令执行
- 内存使用情况

### JAR 文件未找到

如果使用自定义 JAR 文件名，请确保文件存在：

```bash
# 检查文件是否存在
ls -la target/redis-cluster-manager-1.0.0.jar

# 使用正确的文件名启动
./start.sh target/my-custom-name.jar
```

### 内存不足

如果系统内存较小（< 2GB），可以手动修改 `start.sh` 中的堆内存设置：

```bash
# 将默认的自动计算改为固定值
HEAP_SIZE="512m"
```

### PID 文件残留

如果停止脚本无法正常工作，可能 PID 文件残留：

```bash
# 删除 PID 文件后重新启动
rm redis-manager.pid
./start.sh
```

## 性能调优建议

### 生产环境推荐配置

**服务器配置**：
- CPU：4 核心以上
- 内存：8GB 以上
- 磁盘：SSD，10GB+ 可用空间

**JVM 参数**（8GB 内存服务器自动配置）：
```
-Xms4g -Xmx4g -Xmn1536m
-XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=512m
-XX:+UseG1GC -XX:MaxGCPauseMillis=100
-XX:ParallelGCThreads=6 -XX:ConcGCThreads=3
```

### 监控指标

通过 `status.sh` 可以查看：
- 进程运行时间和资源占用
- JVM 内存使用情况
- GC 活动情况
- 端口监听状态
- 日志文件大小

## 注意事项

1. **脚本位置**：脚本文件应该放在项目根目录下，与 JAR 文件同级
2. **首次运行**：需要先执行 `mvn clean package -DskipTests` 生成 JAR 文件
3. **端口占用**：确保 8080 端口未被其他应用占用
4. **权限问题**：Linux/macOS 脚本需要执行权限：`chmod +x *.sh`
5. **后台运行**：Linux 使用 `nohup` 后台运行
6. **多实例**：如需运行多个实例，请指定不同的 JAR 文件名和 PID 文件名，以及不同的端口

## 多实例运行示例

```bash
# 实例 1（默认，INFO 日志）
./start.sh redis-cluster-manager-1.jar

# 实例 2（不同端口、JAR 文件，DEBUG 日志）
export SERVER_PORT=8081
./start.sh redis-cluster-manager-2.jar DEBUG

# 停止实例 1
./stop.sh redis-manager.pid

# 停止实例 2
./stop.sh redis-manager-2.pid
```

## 使用示例

### 场景一：开发调试

```bash
# 开发时使用 DEBUG 级别，查看详细日志
./start.sh redis-cluster-manager-1.0.0.jar DEBUG

# 或者交互式选择 DEBUG
./start.sh
# 请输入选项 [1-4] (默认: 1): 2
```

### 场景二：生产部署

```bash
# 生产环境使用 INFO 级别（默认）
./start.sh

# 或者明确指定
./start.sh redis-cluster-manager-1.0.0.jar INFO
```

### 场景三：减少日志量

```bash
# 当磁盘空间有限时，只记录警告和错误
./start.sh redis-cluster-manager-1.0.0.jar WARN

# 或者只记录错误
./start.sh redis-cluster-manager-1.0.0.jar ERROR
```

### 场景四：查看帮助

```bash
./start.sh --help
./restart.sh --help
```
