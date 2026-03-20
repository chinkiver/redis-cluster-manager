package com.redis.manager.service;

import com.redis.manager.dto.Result;
import com.redis.manager.entity.*;
import com.redis.manager.repository.RedisClusterRepository;
import com.redis.manager.repository.RedisConfigTemplateRepository;
import com.redis.manager.repository.RedisInstanceRepository;
import com.redis.manager.ssh.SSHClient;
import com.redis.manager.ssh.SSHConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 集群部署服务
 * 支持动态集群规模（2×2到6×6）的部署模式
 * 
 * 部署步骤：
 * 1. 根据配置模板生成配置文件
 * 2. 分发配置文件至各个服务器
 * 3. 启动redis服务（使用各服务器设定的bin路径）
 * 4. 创建集群，配置主从关系（N主N从，N=2-6）
 * 5. 验证集群状态
 */
@Service
public class ClusterDeployService {

    private static final Logger logger = LoggerFactory.getLogger(ClusterDeployService.class);

    @Autowired
    private RedisClusterRepository clusterRepository;

    @Autowired
    private RedisInstanceRepository instanceRepository;

    @Autowired
    private RedisConfigTemplateRepository templateRepository;

    @Autowired
    private SSHConnectionPool sshPool;

    @Value("${redis.manager.remote-data-base:/data/redis}")
    private String remoteDataBase;

    /**
     * 创建集群（部署前准备）
     */
    @Transactional
    public Result<RedisCluster> createCluster(Long groupId, String name, Long templateId, 
                                               Integer basePort, String password, String redisVersion) {
        if (groupId == null || name == null || name.trim().isEmpty()) {
            return Result.error("参数不完整");
        }

        if (clusterRepository.existsByServerGroupId(groupId)) {
            return Result.error("该服务器组已创建集群");
        }

        RedisConfigTemplate template = null;
        if (templateId != null) {
            template = templateRepository.findById(templateId).orElse(null);
        }
        if (template == null) {
            template = templateRepository.findByIsDefaultTrue().orElse(null);
        }
        if (template == null) {
            return Result.error("没有可用的配置模板");
        }

        RedisCluster cluster = new RedisCluster();
        cluster.setName(name);
        cluster.setConfigTemplate(template);
        cluster.setBasePort(basePort != null ? basePort : 6379);
        cluster.setRedisPassword(password);
        cluster.setRedisVersion(redisVersion);
        cluster.setStatus(0);

        cluster = clusterRepository.save(cluster);
        return Result.success("集群创建成功", cluster);
    }

    /**
     * 部署集群
     */
    @Transactional
    public Result<String> deployCluster(Long clusterId) {
        Optional<RedisCluster> opt = clusterRepository.findByIdWithDetails(clusterId);
        if (!opt.isPresent()) {
            return Result.error("集群不存在");
        }

        RedisCluster cluster = opt.get();
        ServerGroup group = cluster.getServerGroup();
        List<Server> servers = group.getServers();
        int serverCount = servers.size();

        if (serverCount < 2 || serverCount > 12) {
            return Result.error("服务器数量必须在2-12台之间（支持2×2到6×6集群）");
        }

        cluster.setDeployMode(serverCount);
        cluster.setStatus(1);
        clusterRepository.save(cluster);

        new Thread(() -> {
            try {
                if (serverCount == 3) {
                    deploy3ServerCluster(cluster, servers);
                } else {
                    deploy6ServerCluster(cluster, servers);
                }
            } catch (Exception e) {
                logger.error("集群部署失败", e);
                cluster.setStatus(4);
                logger.error("部署异常: {}", e.getMessage());
                clusterRepository.save(cluster);
            }
        }).start();

        return Result.success("部署任务已启动");
    }

    /**
     * 部署6台服务器集群（标准模式）
     */
    private void deploy6ServerCluster(RedisCluster cluster, List<Server> servers) throws Exception {
        logger.info("[部署] 开始6台服务器标准模式部署，集群ID: {}", cluster.getId());
        
        int basePort = cluster.getBasePort();
        RedisConfigTemplate template = cluster.getConfigTemplate();
        String password = cluster.getRedisPassword();
        
        List<RedisInstance> instances = createInstances(cluster, servers, basePort, template);
        
        // 步骤1: 生成并分发配置文件
        logger.info("[部署] 步骤1/5: 生成并分发配置文件");
        for (int i = 0; i < 6; i++) {
            deployInstanceConfig(instances.get(i), template, password, cluster.getId().toString(), i);
        }
        
        // 步骤2: 启动Redis服务
        logger.info("[部署] 步骤2/5: 启动Redis服务");
        for (RedisInstance instance : instances) {
            startRedisServer(instance);
        }
        Thread.sleep(3000);
        
        // 步骤3: 创建集群（配置主从关系）
        logger.info("[部署] 步骤3/5: 创建集群，配置主从关系");
        createClusterWithReplicas(instances, password);
        
        // 步骤4: 验证集群
        logger.info("[部署] 步骤4/5: 验证集群状态");
        boolean verified = verifyCluster(instances.get(0), password);
        
        if (verified) {
            // 更新实例状态
            for (int i = 0; i < 6; i++) {
                RedisInstance instance = instances.get(i);
                instance.setStatus(1);
                instance.setClusterNodeId(getNodeId(instance, password));
                instanceRepository.save(instance);
            }
            
            cluster.setStatus(2);
            clusterRepository.save(cluster);
            logger.info("[部署] 步骤5/5: 集群部署完成！");
        } else {
            throw new RuntimeException("集群验证失败");
        }
    }

    /**
     * 部署3台服务器集群（交叉备份模式）
     */
    private void deploy3ServerCluster(RedisCluster cluster, List<Server> servers) throws Exception {
        logger.info("[部署] 开始3台服务器交叉备份模式部署，集群ID: {}", cluster.getId());
        
        int basePort = cluster.getBasePort();
        RedisConfigTemplate template = cluster.getConfigTemplate();
        String password = cluster.getRedisPassword();
        
        // 每台服务器2个实例
        List<RedisInstance> instances = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Server server = servers.get(i);
            
            // 主节点
            RedisInstance master = createInstance(cluster, server, basePort, "master", template);
            instances.add(master);
            
            // 从节点（端口+1）
            RedisInstance slave = createInstance(cluster, server, basePort + 1, "slave", template);
            instances.add(slave);
        }
        instanceRepository.saveAll(instances);
        cluster.setInstances(instances);
        
        // 步骤1: 生成并分发配置文件
        logger.info("[部署] 步骤1/5: 生成并分发配置文件");
        for (int i = 0; i < 6; i++) {
            deployInstanceConfig(instances.get(i), template, password, cluster.getId().toString(), i);
        }
        
        // 步骤2: 启动Redis服务
        logger.info("[部署] 步骤2/5: 启动Redis服务");
        for (RedisInstance instance : instances) {
            startRedisServer(instance);
        }
        Thread.sleep(3000);
        
        // 步骤3: 创建集群
        logger.info("[部署] 步骤3/5: 创建集群，配置主从关系");
        createClusterWithReplicas(instances, password);
        
        // 步骤4: 验证集群
        logger.info("[部署] 步骤4/5: 验证集群状态");
        boolean verified = verifyCluster(instances.get(0), password);
        
        if (verified) {
            for (RedisInstance instance : instances) {
                instance.setStatus(1);
                instance.setClusterNodeId(getNodeId(instance, password));
                instanceRepository.save(instance);
            }
            cluster.setStatus(2);
            clusterRepository.save(cluster);
            logger.info("[部署] 步骤5/5: 集群部署完成！");
        } else {
            throw new RuntimeException("集群验证失败");
        }
    }

    /**
     * 创建实例列表（6台模式）
     */
    private List<RedisInstance> createInstances(RedisCluster cluster, List<Server> servers, 
                                                 int basePort, RedisConfigTemplate template) {
        List<RedisInstance> instances = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Server server = servers.get(i);
            String nodeType = i < 3 ? "master" : "slave";
            instances.add(createInstance(cluster, server, basePort, nodeType, template));
        }
        instanceRepository.saveAll(instances);
        cluster.setInstances(instances);
        return instances;
    }

    /**
     * 创建单个实例
     */
    private RedisInstance createInstance(RedisCluster cluster, Server server, int port, 
                                          String nodeType, RedisConfigTemplate template) {
        RedisInstance instance = new RedisInstance();
        instance.setCluster(cluster);
        instance.setServer(server);
        instance.setPort(port);
        instance.setNodeType(nodeType);
        instance.setDataDir(remoteDataBase + "/" + port);
        instance.setConfigPath(remoteDataBase + "/" + port + "/redis.conf");
        instance.setMaxMemory(template.getDefaultMaxMemory());
        instance.setStatus(0);
        return instance;
    }

    /**
     * 获取Redis安装路径
     */
    private String getRedisPath(Server server) {
        String path = server.getRedisPath();
        return (path != null && !path.isEmpty()) ? path : "/usr/local/bin";
    }

    /**
     * 生成并部署配置文件
     */
    private void deployInstanceConfig(RedisInstance instance, RedisConfigTemplate template, 
                                       String password, String clusterId, int nodeIndex) throws Exception {
        Server server = instance.getServer();
        String redisPath = getRedisPath(server);
        
        logger.info("[部署] 配置实例: server={}, port={}, redisPath={}", 
            server.getIp(), instance.getPort(), redisPath);
        
        // 生成配置内容
        String configContent = generateConfig(instance, template, password, clusterId, nodeIndex);
        
        // SSH上传配置
        SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(), 
            server.getSshUser(), server.getSshPassword());
        try {
            ssh.connect();
            
            // 创建数据目录
            ssh.executeCommand("mkdir -p " + instance.getDataDir());
            
            // 上传配置文件
            ssh.uploadFileContent(configContent, instance.getConfigPath());
            
            logger.info("[部署] 配置文件已分发: {}", instance.getConfigPath());
        } finally {
            ssh.disconnect();
        }
    }

    /**
     * 生成配置文件内容
     * 支持 Redis 5.x 和 Redis 6.x 的多线程配置
     */
    private String generateConfig(RedisInstance instance, RedisConfigTemplate template, 
                                   String password, String clusterId, int nodeIndex) {
        StringBuilder config = new StringBuilder();
        
        // 判断 Redis 版本
        boolean isRedis6 = template.getRedisVersion() != null && 
                          (template.getRedisVersion().startsWith("6") || template.getRedisVersion().equals("6.x"));
        
        // 基本配置
        config.append("# Redis Cluster Configuration\n");
        config.append("# Generated by Redis Cluster Manager\n");
        config.append("# Cluster ID: ").append(clusterId).append("\n");
        config.append("# Redis Version: ").append(isRedis6 ? "6.x" : "5.x").append("\n\n");
        
        config.append("# 网络配置\n");
        config.append("bind 0.0.0.0\n");
        config.append("port ").append(instance.getPort()).append("\n");
        config.append("protected-mode no\n");
        config.append("tcp-backlog 511\n");
        config.append("timeout 0\n");
        config.append("tcp-keepalive 300\n\n");
        
        config.append("# 通用配置\n");
        config.append("daemonize yes\n");
        config.append("supervised no\n");
        config.append("pidfile ").append(instance.getDataDir()).append("/redis.pid\n");
        config.append("loglevel notice\n");
        config.append("logfile \"").append(instance.getDataDir()).append("/redis.log\"\n");
        config.append("databases 16\n\n");
        
        // Redis 6.x 多线程配置（可选）
        if (isRedis6 && template.getIoThreadsEnabled() != null && template.getIoThreadsEnabled()) {
            int threadCount = template.getIoThreadsCount() != null ? template.getIoThreadsCount() : 1;
            config.append("# 多线程 IO 配置（Redis 6.0+ 特性）\n");
            config.append("# 注意：多线程配置需谨慎使用，建议充分压测后再生产环境启用\n");
            config.append("io-threads ").append(threadCount).append("\n");
            
            if (template.getIoThreadsDoReads() != null && template.getIoThreadsDoReads()) {
                config.append("io-threads-do-reads yes\n");
            }
            config.append("\n");
        }
        
        config.append("# 数据目录\n");
        config.append("dir \"").append(instance.getDataDir()).append("\"\n\n");
        
        // 持久化配置
        config.append("# 持久化配置\n");
        config.append("save 900 1\n");
        config.append("save 300 10\n");
        config.append("save 60 10000\n");
        config.append("stop-writes-on-bgsave-error yes\n");
        config.append("rdbcompression yes\n");
        config.append("rdbchecksum yes\n");
        config.append("dbfilename dump.rdb\n\n");
        
        // AOF配置
        config.append("# AOF配置\n");
        config.append("appendonly yes\n");
        config.append("appendfilename \"appendonly.aof\"\n");
        config.append("appendfsync everysec\n");
        config.append("no-appendfsync-on-rewrite no\n");
        config.append("auto-aof-rewrite-percentage 100\n");
        config.append("auto-aof-rewrite-min-size 64mb\n\n");
        
        // 内存配置
        if (instance.getMaxMemory() != null && instance.getMaxMemory() > 0) {
            config.append("# 内存配置\n");
            config.append("maxmemory ").append(instance.getMaxMemory()).append("mb\n");
            config.append("maxmemory-policy allkeys-lru\n\n");
        }
        
        // 集群配置
        config.append("# 集群配置\n");
        config.append("cluster-enabled yes\n");
        config.append("cluster-config-file nodes.conf\n");
        config.append("cluster-node-timeout 15000\n");
        config.append("cluster-require-full-coverage no\n\n");
        
        // 密码配置
        if (password != null && !password.isEmpty()) {
            config.append("# 安全配置\n");
            config.append("requirepass ").append(password).append("\n");
            config.append("masterauth ").append(password).append("\n\n");
        }
        
        // 禁用危险命令
        config.append("# 禁用危险命令\n");
        config.append("rename-command FLUSHALL \"\"\n");
        config.append("rename-command FLUSHDB \"\"\n");
        config.append("rename-command KEYS \"\"\n\n");
        
        // Redis 6.x Lazy Free 优化
        if (isRedis6) {
            config.append("# Lazy Free 配置（Redis 6.0 优化）\n");
            config.append("lazyfree-lazy-eviction yes\n");
            config.append("lazyfree-lazy-expire yes\n");
            config.append("lazyfree-lazy-server-del yes\n");
            config.append("replica-lazy-flush yes\n\n");
        }
        
        // 慢查询配置
        config.append("# 慢查询配置\n");
        config.append("slowlog-log-slower-than 10000\n");
        config.append("slowlog-max-len 128\n");
        
        return config.toString();
    }

    /**
     * 启动Redis服务
     */
    private void startRedisServer(RedisInstance instance) throws Exception {
        Server server = instance.getServer();
        String redisPath = getRedisPath(server);
        int port = instance.getPort();
        
        logger.info("[部署] 启动Redis: server={}, port={}, redisPath={}", 
            server.getIp(), port, redisPath);
        
        SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(), 
            server.getSshUser(), server.getSshPassword());
        try {
            ssh.connect();
            
            // 检查端口是否被占用
            SSHClient.SSHResult checkResult = ssh.executeCommand(
                "netstat -tlnp 2>/dev/null | grep :" + port + " || ss -tlnp | grep :" + port);
            
            if (checkResult.getExitCode() == 0 && !checkResult.getStdout().isEmpty()) {
                logger.warn("[部署] 端口 {} 已被占用，尝试停止占用进程", port);
                ssh.executeCommand("fuser -k " + port + "/tcp 2>/dev/null || true");
                Thread.sleep(1000);
            }
            
            // 启动Redis
            String startCmd = String.format("%s/redis-server %s", 
                redisPath, instance.getConfigPath());
            SSHClient.SSHResult result = ssh.executeCommand(startCmd, 10);
            
            if (result.getExitCode() != 0 && !result.getStdout().isEmpty()) {
                logger.warn("[部署] Redis启动警告: {}", result.getStdout());
            }
            
            // 验证启动
            Thread.sleep(1000);
            SSHClient.SSHResult verifyResult = ssh.executeCommand(
                "ps -ef | grep redis-server | grep " + port + " | grep -v grep");
            
            if (verifyResult.getExitCode() != 0 || verifyResult.getStdout().isEmpty()) {
                throw new RuntimeException("Redis启动失败: " + server.getIp() + ":" + port);
            }
            
            logger.info("[部署] Redis启动成功: {}:{}", server.getIp(), port);
            
        } finally {
            ssh.disconnect();
        }
    }

    /**
     * 创建集群（配置主从关系）
     */
    private void createClusterWithReplicas(List<RedisInstance> instances, String password) throws Exception {
        if (instances.size() != 6) {
            throw new RuntimeException("创建集群需要6个实例");
        }
        
        // 构建集群创建命令
        RedisInstance firstInstance = instances.get(0);
        Server firstServer = firstInstance.getServer();
        String redisPath = getRedisPath(firstServer);
        
        StringBuilder cmdBuilder = new StringBuilder();
        cmdBuilder.append(redisPath).append("/redis-cli --cluster create");
        
        // 添加所有节点
        for (RedisInstance instance : instances) {
            cmdBuilder.append(" ").append(instance.getServer().getIp())
                     .append(":").append(instance.getPort());
        }
        
        // 配置副本数（1个从节点）
        cmdBuilder.append(" --cluster-replicas 1");
        
        // 密码认证
        if (password != null && !password.isEmpty()) {
            cmdBuilder.append(" -a ").append(password);
        }
        
        String createCmd = cmdBuilder.toString();
        logger.info("[部署] 执行集群创建命令: {}", createCmd);
        
        SSHClient ssh = new SSHClient(firstServer.getIp(), firstServer.getSshPort(), 
            firstServer.getSshUser(), firstServer.getSshPassword());
        try {
            ssh.connect();
            
            // 使用yes自动确认
            String cmdWithYes = "echo \"yes\" | " + createCmd;
            SSHClient.SSHResult result = ssh.executeCommand(cmdWithYes, 60);
            
            String output = result.getStdout() + result.getStderr();
            logger.info("[部署] 集群创建输出: {}", output);
            
            // 等待集群创建完成
            Thread.sleep(3000);
            
            // 检查是否成功
            boolean success = output.contains("OK") || output.contains("[OK]") 
                || output.contains("Cluster created");
            
            if (!success) {
                // 再检查cluster nodes
                String checkCmd = String.format("%s/redis-cli -h %s -p %d %s cluster nodes | head -5",
                    redisPath, firstServer.getIp(), firstInstance.getPort(),
                    (password != null && !password.isEmpty()) ? "-a " + password : "");
                
                SSHClient.SSHResult checkResult = ssh.executeCommand(checkCmd);
                if (checkResult.getStdout().isEmpty() || !checkResult.getStdout().contains("myself")) {
                    throw new RuntimeException("集群创建失败: " + output);
                }
            }
            
            logger.info("[部署] 集群创建成功");
            
        } finally {
            ssh.disconnect();
        }
    }

    /**
     * 验证集群状态
     */
    private boolean verifyCluster(RedisInstance instance, String password) throws Exception {
        Server server = instance.getServer();
        String redisPath = getRedisPath(server);
        int port = instance.getPort();
        
        logger.info("[部署] 验证集群状态: {}:{}", server.getIp(), port);
        
        SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(), 
            server.getSshUser(), server.getSshPassword());
        try {
            ssh.connect();
            
            String authOption = (password != null && !password.isEmpty()) ? "-a " + password : "";
            
            // 检查 cluster info
            String infoCmd = String.format("%s/redis-cli -h %s -p %d %s cluster info",
                redisPath, server.getIp(), port, authOption);
            
            SSHClient.SSHResult infoResult = ssh.executeCommand(infoCmd, 10);
            String infoOutput = infoResult.getStdout();
            
            // 过滤掉警告信息（包含密码的警告）
            String[] lines = infoOutput.split("\n");
            StringBuilder filteredInfo = new StringBuilder();
            for (String line : lines) {
                if (line.contains("cluster_state") || line.contains("cluster_slots_assigned") 
                    || line.contains("cluster_slots_ok") || line.contains("cluster_known_nodes")) {
                    filteredInfo.append(line).append("\n");
                }
            }
            
            logger.info("[部署] Cluster Info:\n{}", filteredInfo.toString());
            
            // 检查集群状态
            boolean stateOk = infoOutput.contains("cluster_state:ok");
            boolean slotsAssigned = infoOutput.contains("cluster_slots_assigned:16384");
            
            if (!stateOk) {
                logger.error("[部署] 集群状态不正常: cluster_state 不是 ok");
            }
            if (!slotsAssigned) {
                logger.error("[部署] 槽位分配不完整: cluster_slots_assigned 不是 16384");
            }
            
            // 检查 cluster nodes
            String nodesCmd = String.format("%s/redis-cli -h %s -p %d %s cluster nodes",
                redisPath, server.getIp(), port, authOption);
            
            SSHClient.SSHResult nodesResult = ssh.executeCommand(nodesCmd, 10);
            String nodesOutput = nodesResult.getStdout();
            
            // 统计主从节点
            int masterCount = 0;
            int slaveCount = 0;
            for (String line : nodesOutput.split("\n")) {
                if (line.contains("master")) masterCount++;
                if (line.contains("slave")) slaveCount++;
            }
            
            logger.info("[部署] Cluster Nodes: 主节点={}, 从节点={}", masterCount, slaveCount);
            
            boolean nodesOk = masterCount == 3 && slaveCount == 3;
            
            if (stateOk && slotsAssigned && nodesOk) {
                logger.info("[部署] 集群验证通过");
                return true;
            } else {
                logger.error("[部署] 集群验证失败: stateOk={}, slotsAssigned={}, nodesOk={}", 
                    stateOk, slotsAssigned, nodesOk);
                return false;
            }
            
        } finally {
            ssh.disconnect();
        }
    }

    /**
     * 获取节点ID
     */
    private String getNodeId(RedisInstance instance, String password) throws Exception {
        Server server = instance.getServer();
        String redisPath = getRedisPath(server);
        int port = instance.getPort();
        
        SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(), 
            server.getSshUser(), server.getSshPassword());
        try {
            ssh.connect();
            
            String authOption = (password != null && !password.isEmpty()) ? "-a " + password : "";
            String cmd = String.format("%s/redis-cli -h %s -p %d %s cluster nodes | grep myself",
                redisPath, server.getIp(), port, authOption);
            
            SSHClient.SSHResult result = ssh.executeCommand(cmd);
            String output = result.getStdout().trim();
            
            if (!output.isEmpty()) {
                // 输出格式: <node-id> <ip:port> ... myself ...
                String nodeId = output.split(" ")[0];
                return nodeId;
            }
            
            return null;
        } finally {
            ssh.disconnect();
        }
    }

    // ==================== 集群管理操作 ====================

    @Transactional
    public Result<String> stopCluster(Long clusterId) {
        Optional<RedisCluster> opt = clusterRepository.findByIdWithDetails(clusterId);
        if (!opt.isPresent()) {
            return Result.error("集群不存在");
        }

        RedisCluster cluster = opt.get();
        String password = cluster.getRedisPassword();
        
        for (RedisInstance instance : cluster.getInstances()) {
            try {
                stopRedisInstance(instance, password);
                instance.setStatus(0);
                instanceRepository.save(instance);
            } catch (Exception e) {
                logger.error("停止实例失败: {}", instance.getServer().getIp(), e);
            }
        }
        
        cluster.setStatus(3); // 已停止
        clusterRepository.save(cluster);
        return Result.success("集群已停止");
    }

    @Transactional
    public Result<String> startCluster(Long clusterId) {
        Optional<RedisCluster> opt = clusterRepository.findByIdWithDetails(clusterId);
        if (!opt.isPresent()) {
            return Result.error("集群不存在");
        }

        RedisCluster cluster = opt.get();
        
        for (RedisInstance instance : cluster.getInstances()) {
            try {
                startRedisServer(instance);
                instance.setStatus(1);
                instanceRepository.save(instance);
            } catch (Exception e) {
                logger.error("启动实例失败: {}", instance.getServer().getIp(), e);
            }
        }
        
        cluster.setStatus(2); // 运行中
        clusterRepository.save(cluster);
        return Result.success("集群已启动");
    }

    @Transactional
    public Result<String> deleteCluster(Long clusterId) {
        Optional<RedisCluster> opt = clusterRepository.findById(clusterId);
        if (!opt.isPresent()) {
            return Result.error("集群不存在");
        }

        RedisCluster cluster = opt.get();
        
        if (cluster.getStatus() == 2) {
            stopCluster(clusterId);
        }

        instanceRepository.deleteAll(cluster.getInstances());
        clusterRepository.delete(cluster);
        
        return Result.success("集群已删除");
    }

    public Result<RedisCluster> getCluster(Long clusterId) {
        Optional<RedisCluster> opt = clusterRepository.findByIdWithDetails(clusterId);
        if (!opt.isPresent()) {
            return Result.error("集群不存在");
        }
        return Result.success(opt.get());
    }

    /**
     * 停止单个Redis实例
     */
    private void stopRedisInstance(RedisInstance instance, String password) throws Exception {
        Server server = instance.getServer();
        String redisPath = getRedisPath(server);
        int port = instance.getPort();
        
        SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(), 
            server.getSshUser(), server.getSshPassword());
        try {
            ssh.connect();
            
            String authOption = (password != null && !password.isEmpty()) ? "-a " + password : "";
            String cmd = String.format("%s/redis-cli -h %s -p %d %s shutdown nosave",
                redisPath, server.getIp(), port, authOption);
            
            ssh.executeCommand(cmd);
            logger.info("Redis已停止: {}:{}", server.getIp(), port);
        } finally {
            ssh.disconnect();
        }
    }
}
