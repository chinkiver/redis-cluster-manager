package com.redis.manager.service;

import com.redis.manager.dto.Result;
import com.redis.manager.entity.*;
import com.redis.manager.repository.*;
import com.redis.manager.ssh.SSHClient;
import com.redis.manager.ssh.SSHConnectionPool;
import com.redis.manager.util.RedisDeployUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ClusterService {

    private static final Logger logger = LoggerFactory.getLogger(ClusterService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String REMOTE_CONFIG_DIR = "/opt/redis-cluster/config";
    private static final String REMOTE_DATA_DIR = "/opt/redis-cluster/data";

    // 创建进度缓存
    private final Map<Long, Map<String, Object>> createProgressCache = new ConcurrentHashMap<>();
    
    // 操作进度缓存（启动/停止）
    private final Map<String, Map<String, Object>> operationProgressCache = new ConcurrentHashMap<>();

    @Autowired
    private RedisClusterRepository clusterRepository;

    @Autowired
    private ClusterNodeRepository nodeRepository;

    @Autowired
    private ServerGroupRepository groupRepository;

    @Autowired
    private ServerRepository serverRepository;

    @Autowired
    private RedisConfigTemplateRepository templateRepository;

    @Autowired
    private SSHConnectionPool sshPool;

    /**
     * 获取所有集群（列表页，不实时查询监控数据）
     */
    public Result<List<Map<String, Object>>> getAllClusters() {
        List<RedisCluster> clusters = clusterRepository.findAllWithNodes();
        List<Map<String, Object>> result = new ArrayList<>();
        
        for (RedisCluster cluster : clusters) {
            Map<String, Object> dto = convertToDTO(cluster, false); // 列表页不实时查询
            result.add(dto);
        }
        
        return Result.success(result);
    }

    /**
     * 获取集群详情（详情页，实时查询监控数据）
     */
    public Result<Map<String, Object>> getCluster(Long id) {
        Optional<RedisCluster> opt = clusterRepository.findByIdWithNodes(id);
        if (!opt.isPresent()) {
            return Result.error("集群不存在");
        }
        return Result.success(convertToDTO(opt.get(), true)); // 详情页实时查询
    }

    /**
     * 创建集群
     */
    @Transactional
    public Result<Map<String, Object>> createCluster(Map<String, Object> params) {
        Long groupId = Long.valueOf(params.get("groupId").toString());
        Long templateId = Long.valueOf(params.get("templateId").toString());
        Integer basePort = Integer.valueOf(params.get("basePort").toString());
        String redisVersion = (String) params.get("redisVersion");
        String password = (String) params.get("password");
        String name = (String) params.get("name");
        String clusterConfigDir = (String) params.get("clusterConfigDir"); // 自定义集群配置目录
        Integer maxMemory = params.get("maxMemory") != null ? Integer.valueOf(params.get("maxMemory").toString()) : null;
        String maxmemoryPolicy = (String) params.get("maxmemoryPolicy");
        @SuppressWarnings("unchecked")
        Map<String, Object> masterSlaveConfig = (Map<String, Object>) params.get("masterSlaveConfig");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodeConfigs = (List<Map<String, Object>>) params.get("nodeConfigs"); // 节点配置列表

        // 检查服务器组
        Optional<ServerGroup> groupOpt = groupRepository.findByIdWithServers(groupId);
        if (!groupOpt.isPresent()) {
            return Result.error("服务器组不存在");
        }

        ServerGroup group = groupOpt.get();
        // 预加载服务器列表（避免在异步线程中懒加载）
        List<Server> servers = new ArrayList<>(group.getServers());
        if (servers.size() < 2) {
            return Result.error("服务器组至少需要包含2台服务器");
        }
        if (servers.size() > 12) {
            return Result.error("服务器组最多支持12台服务器");
        }

        // 检查名称是否重复
        if (clusterRepository.existsByName(name)) {
            return Result.error("集群名称已存在");
        }

        // 获取配置模板
        Optional<RedisConfigTemplate> templateOpt = templateRepository.findById(templateId);
        if (!templateOpt.isPresent()) {
            return Result.error("配置模板不存在");
        }
        RedisConfigTemplate template = templateOpt.get();
        
        // 预加载模板数据（避免在异步线程中懒加载）
        final String templateContent = template.getTemplateContent();

        // 如果未指定集群配置目录，使用模板中的默认配置
        if (clusterConfigDir == null || clusterConfigDir.trim().isEmpty()) {
            clusterConfigDir = template.getClusterConfigDir();
            if (clusterConfigDir == null || clusterConfigDir.trim().isEmpty()) {
                clusterConfigDir = "/opt/redis-cluster";
            }
        }

        // 创建集群记录
        RedisCluster cluster = new RedisCluster();
        cluster.setName(name);
        cluster.setServerGroup(group);
        cluster.setRedisVersion(redisVersion);
        cluster.setBasePort(basePort);
        cluster.setPassword(password);
        cluster.setStatus(0); // 创建中
        cluster.setClusterType(0); // 系统创建
        cluster.setTemplateId(templateId);
        
        // 保存主从配置
        final Map<String, Object> finalMasterSlaveConfig = masterSlaveConfig != null ? 
            new HashMap<>(masterSlaveConfig) : null;
        if (finalMasterSlaveConfig != null) {
            cluster.setMasterSlaveConfig("{\"masters\":" + finalMasterSlaveConfig.get("masters") + 
                ",\"slaves\":" + finalMasterSlaveConfig.get("slaves") + "}");
        }

        cluster = clusterRepository.save(cluster);

        // 初始化进度
        final Long clusterId = cluster.getId();
        Map<String, Object> progress = new ConcurrentHashMap<>();
        progress.put("status", "running");
        progress.put("currentStep", 0);
        progress.put("totalSteps", 6);
        progress.put("steps", new ArrayList<Map<String, Object>>());
        createProgressCache.put(clusterId, progress);

        // 异步执行创建过程 - 传递预加载的数据
        final String finalClusterConfigDir = clusterConfigDir;
        final List<Server> finalServers = servers;
        final List<Map<String, Object>> finalNodeConfigs = nodeConfigs;
        final Integer finalMaxMemory = maxMemory;
        final String finalMaxmemoryPolicy = maxmemoryPolicy;
        final Integer finalBasePort = basePort;
        final String finalTemplateContent = templateContent;
        
        new Thread(() -> {
            try {
                doCreateCluster(clusterId, finalServers, finalBasePort, finalMasterSlaveConfig, 
                    finalTemplateContent, password, finalClusterConfigDir, finalNodeConfigs, 
                    finalMaxMemory, finalMaxmemoryPolicy);
            } catch (Exception e) {
                logger.error("创建集群线程异常: clusterId={}", clusterId, e);
                // 更新进度为失败
                Map<String, Object> progressCache = createProgressCache.get(clusterId);
                if (progressCache != null) {
                    progressCache.put("status", "failed");
                    progressCache.put("error", "创建线程异常: " + e.getMessage());
                }
            }
        }).start();

        Map<String, Object> result = new HashMap<>();
        result.put("id", clusterId);
        result.put("clusterId", clusterId);
        result.put("message", "集群创建任务已启动");
        return Result.success(result);
    }

    /**
     * 执行创建集群的实际操作
     * @param clusterConfigDir 集群数据目录，实际会使用 ${DATA_DIR}/${PORT} 作为根目录
     */
    private void doCreateCluster(Long clusterId, List<Server> servers, Integer basePort, 
            Map<String, Object> masterSlaveConfig, String templateContent, String password, 
            String clusterConfigDir, List<Map<String, Object>> nodeConfigs,
            Integer maxMemory, String maxmemoryPolicy) {
        
        // 获取模板对象（用于获取默认值）
        RedisConfigTemplate template = null;
        try {
            Optional<RedisCluster> clusterOpt = clusterRepository.findById(clusterId);
            if (clusterOpt.isPresent()) {
                Long templateId = clusterOpt.get().getTemplateId();
                if (templateId != null) {
                    template = templateRepository.findById(templateId).orElse(null);
                }
            }
        } catch (Exception e) {
            logger.warn("无法加载模板对象，将使用默认值", e);
        }
        
        // 解析主从配置
        List<Integer> masters = new ArrayList<>();
        List<Integer> slaves = new ArrayList<>();
        
        if (masterSlaveConfig != null) {
            // 支持新的 pairs 格式
            if (masterSlaveConfig.containsKey("pairs")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> pairs = (List<Map<String, Object>>) masterSlaveConfig.get("pairs");
                if (pairs != null) {
                    // pairs 定义了主从关系
                    // 第 i 对中，master 是第 i 个主节点（索引 i），slave 是第 i 个从节点（索引 i + pairs.size()）
                    int masterCount = pairs.size();
                    for (int i = 0; i < pairs.size(); i++) {
                        Map<String, Object> pair = pairs.get(i);
                        if (pair != null) {
                            Object masterObj = pair.get("master");
                            Object slaveObj = pair.get("slave");
                            
                            // 主节点索引就是 i（0, 1, 2, ...）
                            masters.add(i);
                            
                            // 从节点索引是 i + masterCount（在 nodeConfigsList 中，从节点排在主节点后面）
                            slaves.add(i + masterCount);
                            
                            // 如果需要验证，可以检查 masterObj 和 slaveObj 中的 serverIndex
                            // 但 masters 和 slaves 列表只需要存储节点索引
                        }
                    }
                }
            } else {
                // 兼容旧的 masters/slaves 格式
                Object mastersObj = masterSlaveConfig.get("masters");
                Object slavesObj = masterSlaveConfig.get("slaves");
                if (mastersObj instanceof List) {
                    for (Object m : (List<?>) mastersObj) {
                        if (m instanceof Integer) masters.add((Integer) m);
                        else if (m instanceof Number) masters.add(((Number) m).intValue());
                    }
                }
                if (slavesObj instanceof List) {
                    for (Object s : (List<?>) slavesObj) {
                        if (s instanceof Integer) slaves.add((Integer) s);
                        else if (s instanceof Number) slaves.add(((Number) s).intValue());
                    }
                }
            }
        }
        
        logger.info("解析主从配置: masters={}, slaves={}", masters, slaves);
        
        Map<String, Object> progress = createProgressCache.get(clusterId);
        if (progress == null) {
            logger.error("无法获取进度信息，集群ID: {}", clusterId);
            return;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) progress.get("steps");
        if (steps == null) {
            steps = new ArrayList<>();
            progress.put("steps", steps);
        }
            
        // 保存节点信息
        Map<Integer, ClusterNode> nodeMap = new HashMap<>();
        Map<Integer, String> nodeIdMap = new HashMap<>();

        try {
            // 验证主从配置 - 支持2*2到6*6的架构
            if (masters.size() != slaves.size()) {
                throw new RuntimeException("主从配置错误：主节点和从节点数量必须相等（" + masters.size() + "主" + slaves.size() + "从）");
            }
            if (masters.size() != 3) {
                throw new RuntimeException("主从配置错误：系统仅支持3*3架构（3主3从），当前" + masters.size() + "个主节点");
            }
            
            // 计算总节点数
            int totalNodes = masters.size() + slaves.size();
            
            // 验证nodeConfigs是否匹配
            if (nodeConfigs == null || nodeConfigs.size() != totalNodes) {
                throw new RuntimeException("节点配置不匹配：期待" + totalNodes + "个节点配置，实际" + (nodeConfigs == null ? 0 : nodeConfigs.size()) + "个");
            }
            
            // 验证每个节点的serverIndex是否有效
            for (int i = 0; i < nodeConfigs.size(); i++) {
                Map<String, Object> nodeConfig = nodeConfigs.get(i);
                if (nodeConfig == null) {
                    throw new RuntimeException("节点配置缺失：第" + (i + 1) + "个节点配置为空");
                }
                Object serverIndexObj = nodeConfig.get("serverIndex");
                if (serverIndexObj == null) {
                    throw new RuntimeException("节点配置缺失serverIndex：第" + (i + 1) + "个节点");
                }
                int serverIndex = Integer.valueOf(serverIndexObj.toString());
                if (serverIndex < 0 || serverIndex >= servers.size()) {
                    throw new RuntimeException("节点配置无效：第" + (i + 1) + "个节点的serverIndex(" + serverIndex + ")超出范围[0, " + (servers.size() - 1) + "]");
                }
            }
        
            // 步骤1: 生成并上传配置文件
            addStep(steps, 1, "生成并上传配置文件", "running", "正在为" + totalNodes + "台服务器生成并上传Redis配置文件...");
            updateProgress(clusterId, 1, steps);
            
            for (int i = 0; i < totalNodes; i++) {
                // 从nodeConfigs获取每个节点的配置
                int port = basePort;
                int serverIndex = i; // 默认按顺序
                String role = masters.contains(i) ? "master" : "slave";
                
                if (nodeConfigs != null && i < nodeConfigs.size() && nodeConfigs.get(i) != null) {
                    Map<String, Object> nodeConfig = nodeConfigs.get(i);
                    Object portObj = nodeConfig.get("port");
                    if (portObj != null) {
                        port = Integer.valueOf(portObj.toString());
                    }
                    Object serverIndexObj = nodeConfig.get("serverIndex");
                    if (serverIndexObj != null) {
                        serverIndex = Integer.valueOf(serverIndexObj.toString());
                    }
                }
                
                Server server = servers.get(serverIndex);
                
                // 创建SSH连接
                SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(), 
                    server.getSshUser(), server.getSshPassword());
                ssh.connect();
                
                try {
                    // 使用新的目录结构：${DATA_DIR}/${PORT}/conf、data、nodes、logs
                    String nodeBaseDir = clusterConfigDir + "/" + port;
                    String remoteConfigDir = nodeBaseDir + "/conf";
                    String remoteDataDir = nodeBaseDir + "/data";
                    String remoteNodesDir = nodeBaseDir + "/nodes";
                    String remoteLogsDir = nodeBaseDir + "/logs";
                    
                    // 检查目录是否已存在
                    if (RedisDeployUtil.checkDirExists(ssh, nodeBaseDir)) {
                        throw new RuntimeException("服务器 " + server.getIp() + " 上的目录 " + nodeBaseDir + " 已存在，" +
                            "请先手动清理或选择其他端口。清理命令：rm -rf " + nodeBaseDir);
                    }
                    
                    // 检查目录权限
                    try {
                        RedisDeployUtil.checkDirWritable(ssh, clusterConfigDir);
                    } catch (Exception e) {
                        throw new RuntimeException("服务器 " + server.getIp() + " 目录权限检查失败: " + e.getMessage());
                    }
                    
                    // 创建标准目录结构
                    RedisDeployUtil.createClusterDirStructure(ssh, clusterConfigDir, port, false);
                    
                    // 生成配置文件（传入节点根目录，模板中使用 ${DATA_DIR}/data 等形式）
                    String configContent = generateConfig(templateContent, server.getIp(), 
                        port, nodeBaseDir, password, i, template, maxMemory, maxmemoryPolicy);
                    
                    // 上传配置文件到 conf 目录
                    String remoteConfigPath = remoteConfigDir + "/redis.conf";
                    RedisDeployUtil.uploadConfigFile(ssh, configContent, remoteConfigPath);
                    
                    // 创建节点记录
                    ClusterNode node = new ClusterNode();
                    node.setCluster(clusterRepository.findById(clusterId).get());
                    node.setNodeIndex(i);
                    node.setIp(server.getIp());
                    node.setPort(port);
                    node.setNodeRoleStr(role);
                    node.setStatus(0); // 停止
                    node.setConfigPath(remoteConfigPath);
                    node.setDataDir(remoteDataDir);
                    node = nodeRepository.save(node);
                    nodeMap.put(i, node);
                    
                    logger.info("节点{}配置文件上传成功: {}", i, remoteConfigPath);
                    
                } finally {
                    ssh.disconnect();
                }
            }
            
            addStep(steps, 1, "生成并上传配置文件", "success", totalNodes + "个节点的配置文件已上传到各服务器");
            updateProgress(clusterId, 2, steps);

            // 步骤2: 启动Redis实例
            addStep(steps, 2, "启动Redis实例", "running", "正在" + totalNodes + "台服务器上启动Redis实例...");
            updateProgress(clusterId, 2, steps);
            
            for (int i = 0; i < totalNodes; i++) {
                Server server = servers.get(i);
                ClusterNode node = nodeMap.get(i);
                
                SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(), 
                    server.getSshUser(), server.getSshPassword());
                ssh.connect();
                
                try {
                    // 使用服务器上配置的Redis路径
                    String finalRedisPath = server.getRedisPath() != null && !server.getRedisPath().isEmpty() 
                        ? server.getRedisPath() : "/usr/local/bin";
                    // 传入服务器IP用于验证（配置文件中bind的是服务器IP）
                    RedisDeployUtil.startRedisInstance(ssh, finalRedisPath, node.getConfigPath(), server.getIp());
                    
                    // 更新节点状态
                    node.setStatus(1); // 运行中
                    nodeRepository.save(node);
                    
                } finally {
                    ssh.disconnect();
                }
            }
            
            addStep(steps, 2, "启动Redis实例", "success", totalNodes + "个Redis实例已启动，等待服务可用...");
            updateProgress(clusterId, 2, steps);
            
            // 等待Redis完全启动并可用
            logger.info("等待Redis实例完全启动...");
            Thread.sleep(3000);
            
            // 验证所有Redis实例是否都正常运行
            StringBuilder errorMessages = new StringBuilder();
            boolean allRunning = true;
            
            for (int i = 0; i < totalNodes; i++) {
                Server server = servers.get(i);
                // 从nodeConfigs获取每个节点的端口
                int checkPort = basePort;
                if (nodeConfigs != null && i < nodeConfigs.size() && nodeConfigs.get(i) != null) {
                    Object portObj = nodeConfigs.get(i).get("port");
                    if (portObj != null) {
                        checkPort = Integer.valueOf(portObj.toString());
                    }
                }
                String serverIp = server.getIp();
                ClusterNode node = nodeMap.get(i);
                
                SSHClient ssh = new SSHClient(serverIp, server.getSshPort(), 
                    server.getSshUser(), server.getSshPassword());
                ssh.connect();
                
                try {
                    boolean isRunning = false;
                    int maxCheck = 15;
                    String lastErrorOutput = "";
                    
                    for (int check = 0; check < maxCheck; check++) {
                        // 先尝试用127.0.0.1检查（本地检查更可靠）
                        SSHClient.SSHResult localResult = ssh.executeCommand(
                            String.format("redis-cli -h 127.0.0.1 -p %d -a '%s' ping 2>&1", checkPort, password != null ? password : ""));
                        if (localResult.getStdout().contains("PONG")) {
                            isRunning = true;
                            logger.info("Redis本地连接成功: {}:{}", serverIp, checkPort);
                            break;
                        }
                        
                        // 再用服务器IP检查
                        SSHClient.SSHResult remoteResult = ssh.executeCommand(
                            String.format("redis-cli -h %s -p %d -a '%s' ping 2>&1", serverIp, checkPort, password != null ? password : ""));
                        lastErrorOutput = remoteResult.getStdout().trim();
                        if (remoteResult.getStdout().contains("PONG")) {
                            isRunning = true;
                            logger.info("Redis远程连接成功: {}:{}", serverIp, checkPort);
                            break;
                        }
                        
                        Thread.sleep(1000);
                    }
                    
                    if (!isRunning) {
                        allRunning = false;
                        // 收集诊断信息
                        StringBuilder diagInfo = new StringBuilder();
                        diagInfo.append("\n=== 服务器 ").append(serverIp).append(" 诊断信息 ===");
                        
                        // 检查Redis进程
                        SSHClient.SSHResult psResult = ssh.executeCommand("ps aux | grep redis-server | grep -v grep");
                        diagInfo.append("\nRedis进程: ").append(psResult.getStdout().isEmpty() ? "未运行" : psResult.getStdout());
                        
                        // 检查端口占用
                        SSHClient.SSHResult portResult = ssh.executeCommand("netstat -tlnp 2>/dev/null | grep :" + checkPort + " || ss -tlnp | grep :" + checkPort);
                        diagInfo.append("\n端口占用: ").append(portResult.getStdout().isEmpty() ? "未监听" : portResult.getStdout());
                        
                        // 检查Redis日志文件
                        String logPath = "/opt/redis-cluster-mine/" + checkPort + "/logs/redis_" + checkPort + ".log";
                        SSHClient.SSHResult logResult = ssh.executeCommand("tail -50 " + logPath + " 2>&1");
                        if (!logResult.getStdout().isEmpty()) {
                            diagInfo.append("\nRedis日志最后50行:\n").append(logResult.getStdout());
                        }
                        
                        // 检查启动日志
                        SSHClient.SSHResult startupLogResult = ssh.executeCommand("cat " + node.getConfigPath() + ".startup.log 2>&1");
                        if (!startupLogResult.getStdout().isEmpty()) {
                            diagInfo.append("\n启动日志:\n").append(startupLogResult.getStdout());
                        }
                        
                        // 检查目录权限
                        SSHClient.SSHResult dirResult = ssh.executeCommand("ls -la /opt/redis-cluster-mine/" + checkPort + "/ 2>&1");
                        diagInfo.append("\n数据目录权限:\n").append(dirResult.getStdout());
                        
                        diagInfo.append("\n最后ping错误输出: ").append(lastErrorOutput);
                        
                        String errorDetail = diagInfo.toString();
                        logger.error(errorDetail);
                        errorMessages.append(errorDetail);
                    } else {
                        logger.info("Redis实例验证成功: {}:{}", serverIp, checkPort);
                    }
                } finally {
                    ssh.disconnect();
                }
            }
            
            if (!allRunning) {
                throw new RuntimeException("部分Redis实例启动失败，详情：" + errorMessages.toString());
            }
            
            addStep(steps, 2, "启动Redis实例", "success", totalNodes + "个Redis实例已成功启动并可用");
            updateProgress(clusterId, 3, steps);

            // 步骤3: 创建集群（cluster meet）
            addStep(steps, 3, "创建集群连接", "running", "正在执行cluster meet建立节点连接...");
            updateProgress(clusterId, 3, steps);
            
            // 以第一个节点为基准，让所有节点meet它
            Server firstServer = servers.get(0);
            String firstIp = firstServer.getIp();
            int firstPort = basePort;
            if (nodeConfigs != null && !nodeConfigs.isEmpty() && nodeConfigs.get(0) != null) {
                Object portObj = nodeConfigs.get(0).get("port");
                if (portObj != null) {
                    firstPort = Integer.valueOf(portObj.toString());
                }
            }
            
            for (int i = 1; i < totalNodes; i++) {
                Server server = servers.get(i);
                String serverIp = server.getIp();
                // 从nodeConfigs获取每个节点的端口
                int port = basePort;
                if (nodeConfigs != null && i < nodeConfigs.size() && nodeConfigs.get(i) != null) {
                    Object portObj = nodeConfigs.get(i).get("port");
                    if (portObj != null) {
                        port = Integer.valueOf(portObj.toString());
                    }
                }
                
                // 记录命令用于调试
                String authCmd = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";
                String meetCommand = String.format("redis-cli -h %s -p %d%s cluster meet %s %d", 
                    serverIp, port, authCmd, firstIp, firstPort);
                updateOperationStep(steps, 3, "创建集群连接", "running", 
                    "正在执行: " + serverIp + " -> " + firstIp + ":" + firstPort, meetCommand);
                
                SSHClient ssh = new SSHClient(serverIp, server.getSshPort(), 
                    server.getSshUser(), server.getSshPassword());
                ssh.connect();
                
                try {
                    // 在目标服务器上执行 cluster meet，连接到第一个节点
                    // 使用服务器自身IP连接本地Redis
                    RedisDeployUtil.clusterMeet(ssh, serverIp, port, firstIp, firstPort, password);
                } finally {
                    ssh.disconnect();
                }
            }
            
            // 等待集群建立
            Thread.sleep(2000);
            
            addStep(steps, 3, "创建集群连接", "success", totalNodes + "个节点已互相连接");
            updateProgress(clusterId, 4, steps);

            // 步骤4: 分配哈希槽
            int masterCount = masters.size();
            addStep(steps, 4, "分配哈希槽", "running", "正在为" + masterCount + "个主节点分配16384个哈希槽...");
            updateProgress(clusterId, 4, steps);
            
            // 动态计算槽范围 - 16384个槽平均分配给N个主节点
            int[][] slotRanges = calculateSlotRanges(masterCount);
            
            for (int i = 0; i < masters.size(); i++) {
                int nodeIndex = masters.get(i);
                Server server = servers.get(nodeIndex);
                String serverIp = server.getIp();
                // 从nodeConfigs获取每个节点的端口
                int port = basePort;
                if (nodeConfigs != null && nodeIndex < nodeConfigs.size() && nodeConfigs.get(nodeIndex) != null) {
                    Object portObj = nodeConfigs.get(nodeIndex).get("port");
                    if (portObj != null) {
                        port = Integer.valueOf(portObj.toString());
                    }
                }
                
                // 记录命令用于调试
                String authCmd = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";
                String slotsCmd = String.format("redis-cli -h %s -p %d%s cluster addslots %d-%d", 
                    serverIp, port, authCmd, slotRanges[i][0], slotRanges[i][1]);
                updateOperationStep(steps, 4, "分配哈希槽", "running", 
                    "主节点 " + serverIp + " 分配槽 " + slotRanges[i][0] + "-" + slotRanges[i][1], slotsCmd);
                
                SSHClient ssh = new SSHClient(serverIp, server.getSshPort(), 
                    server.getSshUser(), server.getSshPassword());
                ssh.connect();
                
                try {
                    RedisDeployUtil.assignSlots(ssh, serverIp, port, slotRanges[i][0], slotRanges[i][1], password);
                } finally {
                    ssh.disconnect();
                }
            }
            
            addStep(steps, 4, "分配哈希槽", "success", "16384个哈希槽已分配完成");
            updateProgress(clusterId, 5, steps);

            // 步骤5: 配置主从关系
            addStep(steps, 5, "配置主从关系", "running", "正在设置从节点复制关系...");
            updateProgress(clusterId, 5, steps);
            
            // 获取主节点的nodeId
            for (int i = 0; i < masters.size(); i++) {
                int masterIndex = masters.get(i);
                
                Server masterServer = servers.get(masterIndex);
                String masterIp = masterServer.getIp();
                // 从nodeConfigs获取每个节点的端口
                int masterPort = basePort;
                if (nodeConfigs != null && masterIndex < nodeConfigs.size() && nodeConfigs.get(masterIndex) != null) {
                    Object portObj = nodeConfigs.get(masterIndex).get("port");
                    if (portObj != null) {
                        masterPort = Integer.valueOf(portObj.toString());
                    }
                }
                
                // 记录命令用于调试
                String authCmdMyid = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";
                String myidCmd = String.format("redis-cli -h %s -p %d%s cluster myid", masterIp, masterPort, authCmdMyid);
                updateOperationStep(steps, 5, "配置主从复制", "running", 
                    "获取主节点 " + masterIp + " 的nodeId", myidCmd);
                
                // 获取主节点ID
                SSHClient ssh = new SSHClient(masterIp, masterServer.getSshPort(), 
                    masterServer.getSshUser(), masterServer.getSshPassword());
                ssh.connect();
                
                String masterNodeId;
                try {
                    masterNodeId = RedisDeployUtil.getNodeId(ssh, masterIp, masterPort, password);
                    if (masterNodeId == null || masterNodeId.isEmpty()) {
                        throw new RuntimeException("获取主节点ID失败: " + masterIp + ":" + masterPort);
                    }
                    nodeIdMap.put(masterIndex, masterNodeId);
                    String shortId = masterNodeId.length() > 16 ? masterNodeId.substring(0, 16) + "..." : masterNodeId;
                    updateOperationStep(steps, 5, "配置主从复制", "running", 
                        "主节点 " + masterIp + " 的nodeId: " + shortId, myidCmd);
                    logger.info("获取主节点ID成功: {}:{} -> {}", masterIp, masterPort, masterNodeId);
                } finally {
                    ssh.disconnect();
                }
            }
            
            // 设置从节点复制
            for (int i = 0; i < masters.size(); i++) {
                int masterIndex = masters.get(i);
                int slaveIndex = slaves.get(i);
                
                String masterNodeId = nodeIdMap.get(masterIndex);
                if (masterNodeId == null || masterNodeId.isEmpty()) {
                    throw new RuntimeException("主节点ID为空，无法设置从节点: masterIndex=" + masterIndex);
                }
                
                Server slaveServer = servers.get(slaveIndex);
                String slaveIp = slaveServer.getIp();
                // 从nodeConfigs获取每个节点的端口
                int slavePort = basePort;
                if (nodeConfigs != null && slaveIndex < nodeConfigs.size() && nodeConfigs.get(slaveIndex) != null) {
                    Object portObj = nodeConfigs.get(slaveIndex).get("port");
                    if (portObj != null) {
                        slavePort = Integer.valueOf(portObj.toString());
                    }
                }
                
                // 记录命令用于调试
                String authCmdRep = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";
                String replicateCmd = String.format("redis-cli -h %s -p %d%s cluster replicate %s", 
                    slaveIp, slavePort, authCmdRep, masterNodeId);
                updateOperationStep(steps, 5, "配置主从复制", "running", 
                    "从节点 " + slaveIp + " 复制主节点 " + masterNodeId.substring(0, 16) + "...", replicateCmd);
                
                SSHClient ssh = new SSHClient(slaveIp, slaveServer.getSshPort(), 
                    slaveServer.getSshUser(), slaveServer.getSshPassword());
                ssh.connect();
                
                try {
                    RedisDeployUtil.replicateMaster(ssh, slaveIp, slavePort, masterNodeId, password);
                    
                    // 更新从节点信息
                    ClusterNode slaveNode = nodeMap.get(slaveIndex);
                    slaveNode.setMasterId(masterNodeId);
                    slaveNode.setMasterNode(nodeMap.get(masterIndex));
                    nodeRepository.save(slaveNode);
                    
                    logger.info("设置从节点成功: {}:{} 复制主节点 {}", slaveIp, slavePort, masterNodeId);
                } finally {
                    ssh.disconnect();
                }
            }
            
            addStep(steps, 5, "配置主从关系", "success", masterCount + "主" + masterCount + "从复制关系配置完成");
            updateProgress(clusterId, 6, steps);

            // 步骤6: 验证集群
            addStep(steps, 6, "验证集群", "running", "正在验证集群状态...");
            updateProgress(clusterId, 6, steps);
            
            // 等待集群完全就绪
            logger.info("等待集群就绪...");
            Thread.sleep(3000);
            
            // 验证第一个节点（带重试）
            Server verifyServer = servers.get(0);
            String verifyIp = verifyServer.getIp();
            int verifyPort = basePort;
            if (nodeConfigs != null && !nodeConfigs.isEmpty() && nodeConfigs.get(0) != null) {
                Object portObj = nodeConfigs.get(0).get("port");
                if (portObj != null) {
                    verifyPort = Integer.valueOf(portObj.toString());
                }
            }
            
            // 记录验证命令
            String authCmdVerify = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";
            String verifyCmd = String.format("redis-cli -h %s -p %d%s cluster info", verifyIp, verifyPort, authCmdVerify);
            updateOperationStep(steps, 6, "验证集群", "running", 
                "验证节点: " + verifyIp + ":" + verifyPort, verifyCmd);
            
            boolean clusterOk = false;
            int maxVerifyRetries = 5;
            for (int retry = 0; retry < maxVerifyRetries; retry++) {
                SSHClient ssh = new SSHClient(verifyIp, verifyServer.getSshPort(), 
                    verifyServer.getSshUser(), verifyServer.getSshPassword());
                ssh.connect();
                
                try {
                    clusterOk = RedisDeployUtil.verifyCluster(ssh, verifyIp, verifyPort, password, totalNodes);
                    if (clusterOk) {
                        logger.info("集群验证成功");
                        break;
                    }
                } finally {
                    ssh.disconnect();
                }
                
                if (retry < maxVerifyRetries - 1) {
                    logger.warn("集群验证失败，第{}次重试...", retry + 1);
                    Thread.sleep(2000);
                }
            }
            
            if (!clusterOk) {
                throw new RuntimeException("集群验证失败，状态不正常，请检查日志确认各节点状态");
            }
            
            addStep(steps, 6, "验证集群", "success", "集群验证通过，运行正常");
            
            // 更新集群状态为运行中
            Optional<RedisCluster> clusterOpt = clusterRepository.findById(clusterId);
            if (clusterOpt.isPresent()) {
                RedisCluster cluster = clusterOpt.get();
                cluster.setStatus(1); // 运行中
                clusterRepository.save(cluster);
            }
            
            progress.put("status", "completed");
            logger.info("集群创建成功: clusterId={}", clusterId);
            
        } catch (Exception e) {
            logger.error("创建集群失败", e);
            addStep(steps, steps.size() + 1, "创建失败", "error", e.getMessage());
            progress.put("status", "failed");
            progress.put("error", e.getMessage());
            
            // 创建失败，保留记录以便后续处理（删除、停止等）
            logger.info("创建集群失败，保留记录以便后续处理: clusterId={}", clusterId);
            try {
                // 更新集群状态为异常
                Optional<RedisCluster> clusterOpt = clusterRepository.findById(clusterId);
                if (clusterOpt.isPresent()) {
                    RedisCluster cluster = clusterOpt.get();
                    cluster.setStatus(3); // 异常
                    clusterRepository.save(cluster);
                }
                
                // 更新节点状态为异常
                for (ClusterNode node : nodeMap.values()) {
                    if (node.getId() != null) {
                        node.setStatus(3); // 异常
                        nodeRepository.save(node);
                    }
                }
                
                logger.info("集群和节点状态已更新为异常: clusterId={}", clusterId);
            } catch (Exception updateEx) {
                logger.error("更新集群状态失败", updateEx);
            }
        }
    }

    /**
     * 计算哈希槽范围 - 将16384个槽平均分配给N个主节点
     * @param masterCount 主节点数量
     * @return 每个主节点的槽范围数组，如 [[0, 8191], [8192, 16383]]
     */
    private int[][] calculateSlotRanges(int masterCount) {
        if (masterCount < 1 || masterCount > 6) {
            throw new IllegalArgumentException("主节点数量必须在1-6之间");
        }
        
        int[][] ranges = new int[masterCount][2];
        int slotsPerMaster = 16384 / masterCount;
        int remainder = 16384 % masterCount;
        
        int currentSlot = 0;
        for (int i = 0; i < masterCount; i++) {
            ranges[i][0] = currentSlot;
            // 前 remainder 个主节点多分一个槽
            int slots = slotsPerMaster + (i < remainder ? 1 : 0);
            ranges[i][1] = currentSlot + slots - 1;
            currentSlot += slots;
        }
        
        return ranges;
    }

    /**
     * 生成Redis配置文件
     * @param templateContent 模板内容
     * @param ip 节点IP
     * @param port 端口
     * @param nodeBaseDir 节点根目录（如 /opt/redis-cluster/6100）
     * @param password 密码
     * @param nodeIndex 节点索引
     * @param template 配置模板（用于获取默认值）
     * @param maxMemory 最大内存
     * @param maxmemoryPolicy 内存淘汰策略
     */
    private String generateConfig(String templateContent, String ip, int port, 
            String nodeBaseDir, String password, int nodeIndex, RedisConfigTemplate template,
            Integer maxMemory, String maxmemoryPolicy) {
        String config = templateContent;
        
        // 替换基本变量
        config = config.replace("${PORT}", String.valueOf(port));
        config = config.replace("${NODE_IP}", ip);
        config = config.replace("${DATA_DIR}", nodeBaseDir);
        config = config.replace("${NODE_INDEX}", String.valueOf(nodeIndex));
        
        // 替换密码占位符（多种格式兼容）
        if (password != null && !password.isEmpty()) {
            config = config.replace("${PASSWORD}", password);
            config = config.replace("${REQUIREPASS}", password);
            config = config.replace("${MASTERAUTH}", password);
            // 确保有requirepass配置
            if (!config.contains("requirepass")) {
                config += "\nrequirepass " + password;
            }
            if (!config.contains("masterauth")) {
                config += "\nmasterauth " + password;
            }
        } else {
            // 如果没有密码，替换为空字符串或注释掉
            config = config.replace("${REQUIREPASS}", "");
            config = config.replace("${MASTERAUTH}", "");
        }
        
        // 替换maxmemory占位符（优先使用传入的值，否则使用模板默认值）
        if (config.contains("${MAX_MEMORY}")) {
            Long finalMaxMemory = maxMemory != null && maxMemory > 0 
                ? maxMemory.longValue()
                : (template != null && template.getDefaultMaxMemory() != null 
                    ? template.getDefaultMaxMemory() : 1024L);
            config = config.replace("${MAX_MEMORY}", String.valueOf(finalMaxMemory));
        }
        
        // 替换maxmemory-policy占位符（优先使用传入的值，否则使用模板默认值）
        if (config.contains("${MAXMEMORY_POLICY}")) {
            String finalPolicy = maxmemoryPolicy != null && !maxmemoryPolicy.isEmpty()
                ? maxmemoryPolicy
                : (template != null && template.getDefaultMaxMemoryPolicy() != null 
                    && !template.getDefaultMaxMemoryPolicy().isEmpty()
                    ? template.getDefaultMaxMemoryPolicy() : "allkeys-lru");
            config = config.replace("${MAXMEMORY_POLICY}", finalPolicy);
        }
        
        // 替换appendonly占位符（使用模板默认值）
        if (config.contains("${APPENDONLY}")) {
            Boolean appendOnly = template != null && template.getDefaultAppendOnly() != null 
                ? template.getDefaultAppendOnly() : true;
            config = config.replace("${APPENDONLY}", appendOnly ? "yes" : "no");
        }
        
        // 替换集群相关占位符
        config = config.replace("${CLUSTER_ENABLED}", "yes");
        config = config.replace("${CLUSTER_CONFIG_FILE}", nodeBaseDir + "/nodes/nodes-" + port + ".conf");
        
        // 确保启用集群模式
        if (!config.contains("cluster-enabled yes")) {
            config += "\ncluster-enabled yes";
        }
        if (!config.contains("cluster-config-file")) {
            config += "\ncluster-config-file " + nodeBaseDir + "/nodes/nodes-" + port + ".conf";
        }
        if (!config.contains("cluster-node-timeout")) {
            config += "\ncluster-node-timeout 5000";
        }
        
        // 处理 bind 配置 - 确保Redis监听正确的地址
        // 先移除模板中可能存在的 bind 127.0.0.1，替换为 bind 0.0.0.0
        // 使用 0.0.0.0 允许从任何地址访问（集群节点需要互相通信）
        if (config.contains("bind 127.0.0.1")) {
            config = config.replace("bind 127.0.0.1", "bind 0.0.0.0");
            logger.debug("将 bind 127.0.0.1 替换为 bind 0.0.0.0");
        }
        // 如果没有 bind 配置，添加 bind 0.0.0.0
        if (!config.contains("bind ")) {
            config += "\nbind 0.0.0.0";
            logger.debug("添加 bind 0.0.0.0");
        }
        
        // 确保 protected-mode 为 no（集群模式下需要）
        if (config.contains("protected-mode yes")) {
            config = config.replace("protected-mode yes", "protected-mode no");
            logger.debug("将 protected-mode yes 替换为 no");
        }
        if (!config.contains("protected-mode")) {
            config += "\nprotected-mode no";
        }
        
        // 清理不完整的配置行（修复损坏的模板）
        config = cleanupBrokenConfigLines(config);
        
        return config;
    }
    
    /**
     * 清理配置文件中不完整的配置行
     * 修复可能因模板损坏导致的不完整配置项
     */
    private String cleanupBrokenConfigLines(String config) {
        StringBuilder cleaned = new StringBuilder();
        String[] lines = config.split("\n");
        
        for (String line : lines) {
            String trimmed = line.trim();
            
            // 跳过不完整的配置行（以连字符结尾的配置项）
            if (trimmed.endsWith("-") && !trimmed.contains(" ") && !trimmed.startsWith("#")) {
                logger.warn("移除不完整的配置行: {}", trimmed);
                continue;
            }
            
            // 跳过已知的无效配置项
            if (trimmed.equals("zset-max-ziplist-") || 
                trimmed.equals("hash-max-ziplist-") ||
                trimmed.equals("list-max-ziplist-") ||
                trimmed.equals("set-max-intset-")) {
                logger.warn("移除无效配置项: {}", trimmed);
                continue;
            }
            
            cleaned.append(line).append("\n");
        }
        
        return cleaned.toString().trim();
    }

    /**
     * 预览生成的配置文件
     * 根据模板和参数生成最终的配置文件内容（替换所有占位符）
     */
    public Result<Map<String, Object>> previewConfig(Long templateId, String ip, Integer port, 
            String dataDir, String password, String clusterConfigDir, Integer maxMemory, String maxmemoryPolicy) {
        // 获取配置模板
        Optional<RedisConfigTemplate> templateOpt = templateRepository.findById(templateId);
        if (!templateOpt.isPresent()) {
            return Result.error("配置模板不存在");
        }

        RedisConfigTemplate template = templateOpt.get();
        String templateContent = template.getTemplateContent();
        
        // 获取模板的默认集群配置目录
        String templateClusterConfigDir = template.getClusterConfigDir();
        if (templateClusterConfigDir == null || templateClusterConfigDir.isEmpty()) {
            templateClusterConfigDir = "/opt/redis-cluster";
        }
        
        // 使用传入的集群配置目录，如果没有则使用模板默认值
        String actualClusterConfigDir = (clusterConfigDir != null && !clusterConfigDir.isEmpty()) 
            ? clusterConfigDir : templateClusterConfigDir;
        
        // 使用示例IP（如果未指定）
        if (ip == null || ip.isEmpty()) {
            ip = "192.168.1.100";
        }
        
        // 使用示例端口（如果未指定）
        if (port == null) {
            port = 6037;
        }
        
        // 构建新的目录结构：${DATA_DIR}/${PORT}
        String actualNodeDir = actualClusterConfigDir + "/" + port;
        
        // 生成配置文件内容
        String configContent = generateConfig(templateContent, ip, port, actualNodeDir, password, 0, template, maxMemory, maxmemoryPolicy);
        
        Map<String, Object> result = new HashMap<>();
        result.put("configContent", configContent);
        result.put("templateName", template.getName());
        result.put("redisVersion", template.getRedisVersion());
        result.put("defaultClusterConfigDir", templateClusterConfigDir);
        result.put("usedClusterConfigDir", actualClusterConfigDir);
        result.put("exampleIp", ip);
        result.put("examplePort", port);
        result.put("exampleDataDir", actualNodeDir);
        Map<String, String> dirStructure = new HashMap<>();
        dirStructure.put("baseDir", actualClusterConfigDir + "/" + port);
        dirStructure.put("confDir", actualClusterConfigDir + "/" + port + "/conf");
        dirStructure.put("dataDir", actualClusterConfigDir + "/" + port + "/data");
        dirStructure.put("nodesDir", actualClusterConfigDir + "/" + port + "/nodes");
        dirStructure.put("logsDir", actualClusterConfigDir + "/" + port + "/logs");
        result.put("dirStructure", dirStructure);
        
        return Result.success(result);
    }

    private void addStep(List<Map<String, Object>> steps, int stepNum, String name, String status, String message) {
        // 查找是否已有该步骤
        for (Map<String, Object> step : steps) {
            if (step.get("step").equals(stepNum)) {
                step.put("status", status);
                step.put("message", message);
                return;
            }
        }
        
        Map<String, Object> step = new HashMap<>();
        step.put("step", stepNum);
        step.put("name", name);
        step.put("status", status);
        step.put("message", message);
        step.put("time", System.currentTimeMillis());
        steps.add(step);
    }

    private void updateProgress(Long clusterId, int currentStep, List<Map<String, Object>> steps) {
        Map<String, Object> progress = createProgressCache.get(clusterId);
        if (progress != null) {
            progress.put("currentStep", currentStep);
            progress.put("steps", steps);
        }
    }

    /**
     * 获取创建进度
     */
    public Result<Map<String, Object>> getCreateProgress(Long clusterId) {
        Map<String, Object> progress = createProgressCache.get(clusterId);
        if (progress == null) {
            return Result.error("未找到创建任务");
        }
        return Result.success(progress);
    }

    /**
     * 启动集群
     */
    @Transactional
    public Result<Map<String, Object>> startCluster(Long clusterId) {
        Optional<RedisCluster> opt = clusterRepository.findByIdWithNodes(clusterId);
        if (!opt.isPresent()) {
            return Result.error("集群不存在");
        }

        RedisCluster cluster = opt.get();
        List<ClusterNode> nodes = cluster.getNodes();
        
        if (nodes.isEmpty()) {
            return Result.error("集群没有节点");
        }

        // 预加载服务器组数据，避免在异步线程中出现 LazyInitializationException
        ServerGroup serverGroup = cluster.getServerGroup();
        List<Server> servers = (serverGroup != null) ? new ArrayList<>(serverGroup.getServers()) : new ArrayList<>();
        
        // 创建节点到服务器的映射（使用IP匹配，支持导入的集群）
        Map<Long, Server> nodeServerMap = new HashMap<>();
        for (ClusterNode node : nodes) {
            Server matchedServer = findServerByNodeIp(servers, node.getIp());
            if (matchedServer != null) {
                nodeServerMap.put(node.getId(), matchedServer);
            }
        }

        // 初始化操作进度
        String operationId = "start_" + clusterId + "_" + System.currentTimeMillis();
        Map<String, Object> progress = initOperationProgress(operationId, "启动集群", nodes.size());
        
        // 异步执行启动 - 传递预加载的数据
        final Long finalClusterId = clusterId;
        new Thread(() -> doStartCluster(finalClusterId, nodes, nodeServerMap, operationId)).start();

        Map<String, Object> result = new HashMap<>();
        result.put("operationId", operationId);
        result.put("message", "集群启动任务已启动");
        return Result.success(result);
    }

    /**
     * 执行启动集群
     */
    private void doStartCluster(Long clusterId, List<ClusterNode> nodes, 
            Map<Long, Server> nodeServerMap, String operationId) {
        Map<String, Object> progress = operationProgressCache.get(operationId);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) progress.get("steps");
        
        try {
            for (int i = 0; i < nodes.size(); i++) {
                ClusterNode node = nodes.get(i);
                Server server = nodeServerMap.get(node.getId());
                
                if (server == null) {
                    throw new RuntimeException("找不到节点 " + node.getIp() + ":" + node.getPort() + " 对应的服务器");
                }
                
                updateOperationStep(steps, i + 1, "启动节点 " + node.getIp() + ":" + node.getPort(), 
                    "running", "正在启动...");
                updateOperationProgress(operationId, i + 1, steps);
                
                SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(), 
                    server.getSshUser(), server.getSshPassword());
                ssh.connect();
                
                try {
                    String redisPath = server.getRedisPath() != null ? server.getRedisPath() : "/usr/local/bin";
                    // 传入服务器IP用于验证（配置文件中bind的是服务器IP）
                    RedisDeployUtil.startRedisInstance(ssh, redisPath, node.getConfigPath(), server.getIp());
                    
                    // 更新节点状态 - 重新从数据库获取并更新
                    Optional<ClusterNode> nodeOpt = nodeRepository.findById(node.getId());
                    if (nodeOpt.isPresent()) {
                        ClusterNode updatedNode = nodeOpt.get();
                        updatedNode.setStatus(1); // 运行中
                        nodeRepository.save(updatedNode);
                    }
                    
                    updateOperationStep(steps, i + 1, "启动节点 " + node.getIp() + ":" + node.getPort(), 
                        "success", "启动成功");
                } finally {
                    ssh.disconnect();
                }
            }
            
            // 更新集群状态
            Optional<RedisCluster> clusterOpt = clusterRepository.findById(clusterId);
            if (clusterOpt.isPresent()) {
                RedisCluster cluster = clusterOpt.get();
                cluster.setStatus(1); // 运行中
                clusterRepository.save(cluster);
            }
            
            progress.put("status", "completed");
            logger.info("集群启动成功: clusterId={}", clusterId);
            
        } catch (Exception e) {
            logger.error("启动集群失败", e);
            progress.put("status", "failed");
            progress.put("error", e.getMessage());
        }
    }

    /**
     * 停止集群
     */
    @Transactional
    public Result<Map<String, Object>> stopCluster(Long clusterId) {
        Optional<RedisCluster> opt = clusterRepository.findByIdWithNodes(clusterId);
        if (!opt.isPresent()) {
            return Result.error("集群不存在");
        }

        RedisCluster cluster = opt.get();
        List<ClusterNode> nodes = cluster.getNodes();
        
        if (nodes.isEmpty()) {
            return Result.error("集群没有节点");
        }

        // 预加载服务器组数据，避免在异步线程中出现 LazyInitializationException
        ServerGroup serverGroup = cluster.getServerGroup();
        List<Server> servers = (serverGroup != null) ? new ArrayList<>(serverGroup.getServers()) : new ArrayList<>();
        
        // 创建节点到服务器的映射（使用IP匹配，支持导入的集群）
        Map<Long, Server> nodeServerMap = new HashMap<>();
        for (ClusterNode node : nodes) {
            Server matchedServer = findServerByNodeIp(servers, node.getIp());
            if (matchedServer != null) {
                nodeServerMap.put(node.getId(), matchedServer);
            }
        }

        // 初始化操作进度
        String operationId = "stop_" + clusterId + "_" + System.currentTimeMillis();
        Map<String, Object> progress = initOperationProgress(operationId, "停止集群", nodes.size());
        
        // 异步执行停止 - 传递预加载的数据
        final Long finalClusterId = clusterId;
        new Thread(() -> doStopCluster(finalClusterId, nodes, nodeServerMap, operationId)).start();

        Map<String, Object> result = new HashMap<>();
        result.put("operationId", operationId);
        result.put("message", "集群停止任务已启动");
        return Result.success(result);
    }

    /**
     * 执行停止集群
     */
    private void doStopCluster(Long clusterId, List<ClusterNode> nodes, 
            Map<Long, Server> nodeServerMap, String operationId) {
        Map<String, Object> progress = operationProgressCache.get(operationId);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) progress.get("steps");
        
        try {
            for (int i = 0; i < nodes.size(); i++) {
                ClusterNode node = nodes.get(i);
                Server server = nodeServerMap.get(node.getId());
                
                if (server == null) {
                    throw new RuntimeException("找不到节点 " + node.getIp() + ":" + node.getPort() + " 对应的服务器");
                }
                
                updateOperationStep(steps, i + 1, "停止节点 " + node.getIp() + ":" + node.getPort(), 
                    "running", "正在停止...");
                updateOperationProgress(operationId, i + 1, steps);
                
                SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(), 
                    server.getSshUser(), server.getSshPassword());
                ssh.connect();
                
                try {
                    RedisDeployUtil.stopRedisInstance(ssh, node.getPort());
                    
                    // 更新节点状态 - 重新从数据库获取并更新
                    Optional<ClusterNode> nodeOpt = nodeRepository.findById(node.getId());
                    if (nodeOpt.isPresent()) {
                        ClusterNode updatedNode = nodeOpt.get();
                        updatedNode.setStatus(0); // 停止
                        nodeRepository.save(updatedNode);
                    }
                    
                    updateOperationStep(steps, i + 1, "停止节点 " + node.getIp() + ":" + node.getPort(), 
                        "success", "停止成功");
                } finally {
                    ssh.disconnect();
                }
            }
            
            // 更新集群状态
            Optional<RedisCluster> clusterOpt = clusterRepository.findById(clusterId);
            if (clusterOpt.isPresent()) {
                RedisCluster cluster = clusterOpt.get();
                cluster.setStatus(2); // 已停止
                clusterRepository.save(cluster);
            }
            
            progress.put("status", "completed");
            logger.info("集群停止成功: clusterId={}", clusterId);
            
        } catch (Exception e) {
            logger.error("停止集群失败", e);
            progress.put("status", "failed");
            progress.put("error", e.getMessage());
        }
    }

    /**
     * 获取操作进度（启动/停止）
     */
    public Result<Map<String, Object>> getOperationProgress(String operationId) {
        Map<String, Object> progress = operationProgressCache.get(operationId);
        if (progress == null) {
            return Result.error("未找到操作任务");
        }
        return Result.success(progress);
    }

    private Map<String, Object> initOperationProgress(String operationId, String operationName, int totalSteps) {
        Map<String, Object> progress = new ConcurrentHashMap<>();
        progress.put("operationId", operationId);
        progress.put("operationName", operationName);
        progress.put("status", "running");
        progress.put("currentStep", 0);
        progress.put("totalSteps", totalSteps);
        
        List<Map<String, Object>> steps = new ArrayList<>();
        progress.put("steps", steps);
        
        operationProgressCache.put(operationId, progress);
        return progress;
    }

    private void updateOperationStep(List<Map<String, Object>> steps, int stepNum, String name, 
            String status, String message) {
        updateOperationStep(steps, stepNum, name, status, message, null);
    }
    
    private void updateOperationStep(List<Map<String, Object>> steps, int stepNum, String name, 
            String status, String message, String command) {
        for (Map<String, Object> step : steps) {
            if (step.get("step").equals(stepNum)) {
                step.put("status", status);
                step.put("message", message);
                if (command != null) {
                    step.put("command", command);
                }
                return;
            }
        }
        
        Map<String, Object> step = new HashMap<>();
        step.put("step", stepNum);
        step.put("name", name);
        step.put("status", status);
        step.put("message", message);
        if (command != null) {
            step.put("command", command);
        }
        step.put("time", System.currentTimeMillis());
        steps.add(step);
    }

    private void updateOperationProgress(String operationId, int currentStep, List<Map<String, Object>> steps) {
        Map<String, Object> progress = operationProgressCache.get(operationId);
        if (progress != null) {
            progress.put("currentStep", currentStep);
            progress.put("steps", steps);
        }
    }

    /**
     * 删除集群
     * 异步执行：停止服务 -> 删除记录，保留服务器上的配置文件和日志文件
     */
    @Transactional
    public Result<Map<String, Object>> deleteCluster(Long id) {
        Optional<RedisCluster> opt = clusterRepository.findByIdWithNodes(id);
        if (!opt.isPresent()) {
            return Result.error("集群不存在");
        }

        RedisCluster cluster = opt.get();
        List<ClusterNode> nodes = cluster.getNodes();
        
        if (nodes.isEmpty()) {
            // 没有节点，直接删除记录
            clusterRepository.delete(cluster);
            createProgressCache.remove(id);
            Map<String, Object> result = new HashMap<>();
            result.put("operationId", "delete_" + id + "_" + System.currentTimeMillis());
            result.put("message", "集群删除成功（无节点）");
            return Result.success(result);
        }

        // 预加载所有节点和服务器信息（避免在异步线程中懒加载）
        List<ClusterNodeInfo> nodeInfos = new ArrayList<>();
        ServerGroup group = cluster.getServerGroup();
        List<Server> servers = (group != null) ? group.getServers() : null;
        
        for (ClusterNode node : nodes) {
            ClusterNodeInfo info = new ClusterNodeInfo();
            info.nodeId = node.getId();
            info.ip = node.getIp();
            info.port = node.getPort();
            info.nodeIndex = node.getNodeIndex();
            
            // 从集群关联的服务器组中获取服务器信息（使用IP匹配）
            Server matchedServer = findServerByNodeIp(servers, node.getIp());
            
            // 设置服务器连接信息
            if (matchedServer != null) {
                info.serverIp = matchedServer.getIp();
                info.sshPort = matchedServer.getSshPort();
                info.sshUser = matchedServer.getSshUser();
                info.sshPassword = matchedServer.getSshPassword();
            }
            
            nodeInfos.add(info);
        }
        
        final String clusterName = cluster.getName();
        final Long clusterId = cluster.getId();

        // 初始化操作进度
        String operationId = "delete_" + id + "_" + System.currentTimeMillis();
        Map<String, Object> progress = initOperationProgress(operationId, "删除集群", nodes.size() + 1);
        progress.put("clusterId", id);
        progress.put("clusterName", clusterName);
        
        // 异步执行删除 - 传递预加载的数据
        final List<ClusterNodeInfo> finalNodeInfos = nodeInfos;
        new Thread(() -> doDeleteCluster(clusterId, clusterName, finalNodeInfos, operationId)).start();

        Map<String, Object> result = new HashMap<>();
        result.put("operationId", operationId);
        result.put("message", "集群删除任务已启动，将停止服务并删除记录");
        return Result.success(result);
    }

    /**
     * 仅删除集群记录（不停止服务器上的集群服务）
     * 用于删除导入的集群或不需要停止服务的场景
     */
    @Transactional
    public Result<Map<String, Object>> deleteClusterRecordOnly(Long id) {
        Optional<RedisCluster> opt = clusterRepository.findByIdWithNodes(id);
        if (!opt.isPresent()) {
            return Result.error("集群不存在");
        }

        RedisCluster cluster = opt.get();
        String clusterName = cluster.getName();
        int nodeCount = cluster.getNodes() != null ? cluster.getNodes().size() : 0;
        
        // 删除集群记录（级联删除节点记录）
        clusterRepository.delete(cluster);
        
        // 清除缓存
        createProgressCache.remove(id);
        
        Map<String, Object> result = new HashMap<>();
        result.put("clusterId", id);
        result.put("clusterName", clusterName);
        result.put("nodeCount", nodeCount);
        result.put("message", "集群记录已删除（服务器上的集群服务仍在运行）");
        
        logger.info("集群记录已删除（仅删除记录，不停止服务）: {} ({}), 节点数: {}", clusterName, id, nodeCount);
        return Result.success(result);
    }
    
    /**
     * 根据节点IP查找对应的服务器
     * 用于统一处理系统创建和导入的集群的服务器匹配
     * 
     * @param servers 服务器列表
     * @param nodeIp  节点IP地址
     * @return 匹配的服务器，未找到返回null
     */
    private Server findServerByNodeIp(List<Server> servers, String nodeIp) {
        if (servers == null || servers.isEmpty() || nodeIp == null) {
            return null;
        }
        
        for (Server server : servers) {
            if (server.getIp() != null && server.getIp().equals(nodeIp)) {
                return server;
            }
        }
        return null;
    }
    
    /**
     * 集群节点信息内部类（用于在异步线程中传递数据）
     */
    private static class ClusterNodeInfo {
        Long nodeId;
        String ip;
        int port;
        int nodeIndex;
        String serverIp;
        Integer sshPort;
        String sshUser;
        String sshPassword;
    }

    /**
     * 执行删除集群的实际操作
     * 步骤：1.停止各节点Redis服务 2.删除数据库记录
     * 注意：不删除服务器上的配置文件和日志文件
     */
    private void doDeleteCluster(Long clusterId, String clusterName, List<ClusterNodeInfo> nodeInfos, String operationId) {
        Map<String, Object> progress = operationProgressCache.get(operationId);
        if (progress == null) {
            logger.error("删除集群失败：找不到进度缓存 operationId={}", operationId);
            // 创建一个临时进度记录，避免前端卡住
            progress = new ConcurrentHashMap<>();
            progress.put("operationId", operationId);
            progress.put("operationName", "删除集群");
            progress.put("status", "failed");
            progress.put("error", "删除任务初始化失败，请刷新页面重试");
            progress.put("currentStep", 0);
            progress.put("totalSteps", nodeInfos.size() + 1);
            List<Map<String, Object>> steps = new ArrayList<>();
            progress.put("steps", steps);
            operationProgressCache.put(operationId, progress);
            return;
        }
        
        List<Map<String, Object>> steps = (List<Map<String, Object>>) progress.get("steps");
        
        boolean allStopped = true;
        StringBuilder errorMsg = new StringBuilder();
        
        try {
            // 步骤1-N: 停止各节点的Redis服务
            for (int i = 0; i < nodeInfos.size(); i++) {
                ClusterNodeInfo nodeInfo = nodeInfos.get(i);
                
                String stepName = "停止节点 " + nodeInfo.ip + ":" + nodeInfo.port;
                updateOperationStep(steps, i + 1, stepName, "running", "正在停止Redis服务...");
                updateOperationProgress(operationId, i + 1, steps);
                
                // 检查是否有服务器连接信息（导入的集群可能没有）
                if (nodeInfo.serverIp == null || nodeInfo.sshUser == null || nodeInfo.sshPassword == null) {
                    String warnMsg = "跳过停止节点 " + nodeInfo.ip + ":" + nodeInfo.port + "：未找到对应的服务器信息（可能是导入的集群）";
                    updateOperationStep(steps, i + 1, stepName, "warning", warnMsg);
                    logger.warn(warnMsg);
                    updateOperationProgress(operationId, i + 1, steps);
                    continue;
                }
                
                try {
                    SSHClient ssh = new SSHClient(nodeInfo.serverIp, nodeInfo.sshPort, 
                        nodeInfo.sshUser, nodeInfo.sshPassword);
                    ssh.connect();
                    
                    try {
                        // 停止Redis实例
                        RedisDeployUtil.stopRedisInstance(ssh, nodeInfo.port);
                        
                        updateOperationStep(steps, i + 1, stepName, "success", "Redis服务已停止");
                        logger.info("节点Redis服务停止成功: {}:{}", nodeInfo.ip, nodeInfo.port);
                    } finally {
                        ssh.disconnect();
                    }
                } catch (Exception e) {
                    allStopped = false;
                    String err = String.format("停止节点 %s:%d 失败: %s", nodeInfo.ip, nodeInfo.port, e.getMessage());
                    errorMsg.append(err).append("; ");
                    updateOperationStep(steps, i + 1, stepName, "error", e.getMessage());
                    logger.error(err, e);
                }
                
                updateOperationProgress(operationId, i + 1, steps);
            }
            
            // 最后一步：删除数据库记录
            int finalStep = nodeInfos.size() + 1;
            updateOperationStep(steps, finalStep, "删除集群记录", "running", "正在删除数据库记录...");
            updateOperationProgress(operationId, finalStep, steps);
            
            try {
                // 从数据库重新获取集群和节点记录进行删除
                Optional<RedisCluster> clusterOpt = clusterRepository.findByIdWithNodes(clusterId);
                if (clusterOpt.isPresent()) {
                    RedisCluster cluster = clusterOpt.get();
                    // 删除节点记录
                    nodeRepository.deleteAll(cluster.getNodes());
                    // 删除集群记录
                    clusterRepository.delete(cluster);
                }
                // 清除进度缓存
                createProgressCache.remove(clusterId);
                
                updateOperationStep(steps, finalStep, "删除集群记录", "success", "数据库记录已删除");
                logger.info("集群记录删除成功: clusterId={}", clusterId);
            } catch (Exception e) {
                updateOperationStep(steps, finalStep, "删除集群记录", "error", e.getMessage());
                throw e;
            }
            
            updateOperationProgress(operationId, finalStep, steps);
            
            // 完成任务
            if (allStopped) {
                progress.put("status", "completed");
                progress.put("message", "集群删除成功，所有Redis服务已停止，数据库记录已删除");
                logger.info("集群删除成功: clusterId={}, name={}", clusterId, clusterName);
            } else {
                progress.put("status", "completed_with_errors");
                progress.put("message", "集群删除完成，但部分节点停止失败: " + errorMsg.toString());
                progress.put("errors", errorMsg.toString());
                logger.warn("集群删除完成但存在错误: clusterId={}, errors={}", clusterId, errorMsg.toString());
            }
            
        } catch (Exception e) {
            logger.error("删除集群失败", e);
            progress.put("status", "failed");
            progress.put("error", e.getMessage());
        }
    }

    /**
     * 检查服务器组节点是否可导入
     */
    public Result<Map<String, Object>> checkImportCluster(Map<String, Object> params) {
        String name = (String) params.get("name");
        Long serverGroupId = Long.valueOf(params.get("serverGroupId").toString());
        Integer port = Integer.valueOf(params.get("port").toString());
        String password = (String) params.get("password");

        // 检查名称是否重复
        if (clusterRepository.existsByName(name)) {
            return Result.error("集群名称已存在");
        }

        // 获取服务器组
        ServerGroup group = groupRepository.findById(serverGroupId).orElse(null);
        if (group == null) {
            return Result.error("服务器组不存在");
        }

        List<Server> servers = group.getServers();
        if (servers == null || servers.isEmpty()) {
            return Result.error("服务器组中没有服务器");
        }

        // 密码参数处理 - 使用双引号包含密码避免特殊字符问题
        String authCmd = (password != null && !password.isEmpty()) ? " -a \"" + password + "\"" : "";
        List<Map<String, Object>> checks = new ArrayList<>();
        int successCount = 0;
        String redisVersion = null;

        logger.info("开始检查集群: 服务器组ID={}, 端口={}, 服务器数={}", serverGroupId, port, servers.size());

        // 检查每个服务器上是否存在指定端口的Redis节点
        for (Server server : servers) {
            Map<String, Object> check = new HashMap<>();
            check.put("serverName", server.getName() != null && !server.getName().isEmpty() ? server.getName() : server.getIp());
            check.put("ip", server.getIp());

            try {
                logger.info("检查服务器: {}:{}", server.getIp(), port);
                SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(), server.getSshUser(), server.getSshPassword());
                ssh.connect();

                // 先用 ping 检查连接
                String pingCmd = String.format("redis-cli -h %s -p %d%s ping", server.getIp(), port, authCmd);
                SSHClient.SSHResult pingResult = ssh.executeCommand(pingCmd);
                logger.info("服务器 {} ping 结果: exitCode={}, output={}", 
                    server.getIp(), pingResult.getExitCode(), pingResult.getStdout());

                // 检查指定端口是否是Redis集群节点
                String checkCmd = String.format("redis-cli -h %s -p %d%s CLUSTER INFO", server.getIp(), port, authCmd);
                SSHClient.SSHResult result = ssh.executeCommand(checkCmd);
                String output = result.getStdout();
                
                logger.info("服务器 {} CLUSTER INFO 结果: exitCode={}, output={}", 
                    server.getIp(), result.getExitCode(), 
                    output != null && output.length() > 100 ? output.substring(0, 100) + "..." : output);

                // 判断是否是集群节点
                if (result.getExitCode() == 0 && output != null && output.contains("cluster_state")) {
                    check.put("status", "success");
                    check.put("message", "存在Redis集群节点");
                    successCount++;
                    logger.info("服务器 {} 检查通过", server.getIp());

                    // 获取Redis版本（只获取一次）
                    if (redisVersion == null) {
                        String versionCmd = String.format("redis-cli -h %s -p %d%s INFO server | grep redis_version", server.getIp(), port, authCmd);
                        SSHClient.SSHResult versionResult = ssh.executeCommand(versionCmd);
                        if (versionResult.getExitCode() == 0 && versionResult.getStdout().contains("redis_version")) {
                            redisVersion = versionResult.getStdout().trim().split(":")[1];
                        }
                    }
                } else if (output != null && output.contains("NOAUTH")) {
                    check.put("status", "error");
                    check.put("message", "认证失败：密码错误");
                    logger.warn("服务器 {} 密码认证失败", server.getIp());
                } else if (output != null && (output.contains("ERR") || output.contains("Connection refused"))) {
                    check.put("status", "error");
                    check.put("message", "连接失败: " + output.trim());
                } else {
                    check.put("status", "error");
                    check.put("message", "未发现Redis集群节点");
                    logger.warn("服务器 {} 未发现集群节点: {}", server.getIp(), output);
                }

                ssh.disconnect();
            } catch (Exception e) {
                check.put("status", "exception");
                check.put("message", "SSH连接失败: " + e.getMessage());
                logger.error("检查服务器{}:{} 失败", server.getIp(), port, e);
            }

            checks.add(check);
        }
        
        logger.info("检查完成: 成功{}/{} 个节点", successCount, servers.size());

        // 如果找到了节点，进行主从架构验证（支持2*2到6*6）
        String validationMessage = null;
        boolean architectureValid = true;
        
        if (successCount > 0) {
            // 尝试获取集群节点信息来验证架构
            try {
                // 找到一个成功的服务器来获取集群节点信息
                Server firstServer = servers.stream()
                    .filter(s -> {
                        for (Map<String, Object> check : checks) {
                            if (s.getIp().equals(check.get("ip")) && "success".equals(check.get("status"))) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .findFirst()
                    .orElse(null);
                
                if (firstServer != null) {
                    SSHClient ssh = new SSHClient(firstServer.getIp(), firstServer.getSshPort(), 
                        firstServer.getSshUser(), firstServer.getSshPassword());
                    ssh.connect();
                    
                    String nodesCmd = String.format("redis-cli -h %s -p %d%s cluster nodes 2>&1", 
                        firstServer.getIp(), port, authCmd);
                    SSHClient.SSHResult nodesResult = ssh.executeCommand(nodesCmd);
                    String nodesOutput = nodesResult.getStdout();
                    
                    if (nodesOutput.contains("connected")) {
                        List<Map<String, String>> nodeInfos = parseClusterNodes(nodesOutput);
                        
                        // 验证1：检查集群状态（统计connected的主节点）
                        long connectedMasters = nodeInfos.stream()
                            .filter(n -> {
                                String flags = n.get("flags");
                                String linkState = n.get("linkState");
                                return flags != null && flags.contains("master") && "connected".equals(linkState);
                            })
                            .count();
                        
                        // 验证2：支持2*2到6*6的架构
                        long totalMasters = nodeInfos.stream()
                            .filter(n -> n.get("flags") != null && n.get("flags").contains("master"))
                            .count();
                        long totalSlaves = nodeInfos.stream()
                            .filter(n -> n.get("flags") != null && n.get("flags").contains("slave"))
                            .count();
                        
                        // 检查架构是否支持（2-6个主节点，主从数量相等）
                        if (totalMasters < 2 || totalMasters > 6) {
                            architectureValid = false;
                            validationMessage = "集群架构不支持：主节点数量必须在2-6之间（当前" + totalMasters + "个）";
                        } else if (totalMasters != totalSlaves) {
                            architectureValid = false;
                            validationMessage = "集群架构不支持：主节点和从节点数量必须相等（当前" + totalMasters + "主" + totalSlaves + "从）";
                        } else if (connectedMasters < totalMasters) {
                            architectureValid = false;
                            validationMessage = "集群状态异常：部分主节点未正常运行（" + connectedMasters + "/" + totalMasters + "个正常）";
                        }
                        
                        if (architectureValid) {
                            validationMessage = "集群架构验证通过：" + totalMasters + "主" + totalSlaves + "从，运行正常";
                        }
                    }
                    
                    ssh.disconnect();
                }
            } catch (Exception e) {
                logger.warn("集群架构验证失败: {}", e.getMessage());
                validationMessage = "集群架构验证失败: " + e.getMessage();
                architectureValid = false;
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("checks", checks);
        result.put("nodeCount", successCount);
        result.put("redisVersion", redisVersion);
        // 只有节点检查通过且架构验证通过才能导入
        result.put("canImport", successCount > 0 && architectureValid);
        result.put("architectureValid", architectureValid);

        if (successCount == 0) {
            result.put("message", "未在服务器组中发现任何Redis集群节点，请确认端口号和密码是否正确");
        } else if (!architectureValid) {
            result.put("message", validationMessage != null ? validationMessage : "集群架构不符合导入要求");
        } else {
            result.put("message", validationMessage != null ? validationMessage : "发现 " + successCount + " 个节点");
        }

        return Result.success(result);
    }

    /**
     * 导入现有集群
     */
    @Transactional
    public Result<Map<String, Object>> importCluster(Map<String, Object> params) {
        String name = (String) params.get("name");
        Long serverGroupId = Long.valueOf(params.get("serverGroupId").toString());
        Integer port = Integer.valueOf(params.get("port").toString());
        String password = (String) params.get("password");

        // 检查名称是否重复
        if (clusterRepository.existsByName(name)) {
            return Result.error("集群名称已存在");
        }

        // 获取服务器组
        ServerGroup group = groupRepository.findById(serverGroupId).orElse(null);
        if (group == null) {
            return Result.error("服务器组不存在");
        }

        List<Server> servers = group.getServers();
        if (servers == null || servers.isEmpty()) {
            return Result.error("服务器组中没有服务器");
        }

        String authCmd = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";
        List<Map<String, String>> nodeInfos = new ArrayList<>();
        String redisVersion = null;

        // 查找一个可用的节点获取集群信息
        for (Server server : servers) {
            try {
                SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(), server.getSshUser(), server.getSshPassword());
                ssh.connect();

                // 获取集群节点信息
                String nodesCmd = String.format("redis-cli -h %s -p %d%s cluster nodes 2>&1", server.getIp(), port, authCmd);
                SSHClient.SSHResult nodesResult = ssh.executeCommand(nodesCmd);
                String nodesOutput = nodesResult.getStdout();

                if (nodesOutput.contains("cluster_state") || nodesOutput.contains("connected")) {
                    nodeInfos = parseClusterNodes(nodesOutput);

                    // 获取Redis版本
                    String versionCmd = String.format("redis-cli -h %s -p %d%s info server 2>&1 | grep redis_version", server.getIp(), port, authCmd);
                    SSHClient.SSHResult versionResult = ssh.executeCommand(versionCmd);
                    if (versionResult.getStdout().contains("redis_version")) {
                        redisVersion = versionResult.getStdout().trim().split(":")[1].trim();
                    }

                    ssh.disconnect();
                    break;
                }

                ssh.disconnect();
            } catch (Exception e) {
                logger.warn("从服务器{}:{} 获取集群信息失败", server.getIp(), port);
            }
        }

        if (nodeInfos.isEmpty()) {
            return Result.error("无法获取集群节点信息，请确认端口和密码是否正确");
        }

        // 验证1：检查集群是否正常运行（所有主节点都是connected状态）
        long connectedMasters = nodeInfos.stream()
            .filter(n -> {
                String flags = n.get("flags");
                String linkState = n.get("linkState");
                return flags != null && flags.contains("master") && "connected".equals(linkState);
            })
            .count();
        
        long connectedSlaves = nodeInfos.stream()
            .filter(n -> {
                String flags = n.get("flags");
                String linkState = n.get("linkState");
                return flags != null && flags.contains("slave") && "connected".equals(linkState);
            })
            .count();
        
        // 验证2：支持2*2到6*6的架构
        long totalMasters = nodeInfos.stream()
            .filter(n -> n.get("flags") != null && n.get("flags").contains("master"))
            .count();
        long totalSlaves = nodeInfos.stream()
            .filter(n -> n.get("flags") != null && n.get("flags").contains("slave"))
            .count();
        
        // 检查主节点数量是否在2-6之间
        if (totalMasters < 2 || totalMasters > 6) {
            return Result.error("集群架构不支持：主节点数量必须在2-6之间（当前" + totalMasters + "个）");
        }
        
        // 检查主从数量是否相等
        if (totalMasters != totalSlaves) {
            return Result.error("集群架构不支持：主节点和从节点数量必须相等（当前" + totalMasters + "主" + totalSlaves + "从）");
        }
        
        // 检查是否所有主节点都正常运行
        if (connectedMasters < totalMasters) {
            return Result.error("集群状态异常：部分主节点未正常运行（" + connectedMasters + "/" + totalMasters + "个正常），请确保集群正常运行后再导入");
        }

        try {
            // 创建集群记录
            RedisCluster cluster = new RedisCluster();
            cluster.setName(name);
            cluster.setBasePort(port);
            cluster.setPassword(password);
            cluster.setStatus(1); // 运行中
            cluster.setClusterType(1); // 外部导入
            cluster.setRedisVersion(redisVersion != null ? redisVersion : "未知");
            cluster.setServerGroup(group); // 关联服务器组
            cluster = clusterRepository.save(cluster);

            // 使用现有的服务器组
            int masterCount = 0;
            int slaveCount = 0;
            List<ClusterNode> savedNodes = new ArrayList<>();
            
            // 分离主节点和从节点，确保主节点先处理，从节点后处理
            List<Map<String, String>> masterNodes = nodeInfos.stream()
                .filter(n -> n.get("flags") != null && n.get("flags").contains("master"))
                .collect(Collectors.toList());
            List<Map<String, String>> slaveNodes = nodeInfos.stream()
                .filter(n -> n.get("flags") != null && n.get("flags").contains("slave"))
                .collect(Collectors.toList());
            
            // 先处理主节点
            int nodeIndex = 0;
            for (Map<String, String> nodeInfo : masterNodes) {
                String currentNodeIp = nodeInfo.get("ip");
                int currentNodePort = Integer.parseInt(nodeInfo.get("port"));

                // 在现有服务器组中查找匹配的服务器
                Server server = servers.stream()
                    .filter(s -> s.getIp().equals(currentNodeIp))
                    .findFirst()
                    .orElse(null);

                if (server == null) {
                    server = new Server();
                    server.setGroup(group);
                    server.setName(currentNodeIp);
                    server.setIp(currentNodeIp);
                    server.setSshPort(22);
                    server.setStatus(1);
                    server = serverRepository.save(server);
                }

                ClusterNode node = new ClusterNode();
                node.setCluster(cluster);
                node.setServer(server);
                node.setNodeIndex(nodeIndex++);
                node.setNodeId(nodeInfo.get("nodeId"));
                node.setIp(currentNodeIp);
                node.setPort(currentNodePort);
                node.setNodeRole(0); // master
                node.setNodeRoleStr("master");
                String linkState = nodeInfo.get("linkState");
                node.setStatus("connected".equals(linkState) ? 1 : 2);
                
                node = nodeRepository.save(node);
                savedNodes.add(node);
                masterCount++;
            }
            
            // 再处理从节点
            for (Map<String, String> nodeInfo : slaveNodes) {
                String currentNodeIp = nodeInfo.get("ip");
                int currentNodePort = Integer.parseInt(nodeInfo.get("port"));

                Server server = servers.stream()
                    .filter(s -> s.getIp().equals(currentNodeIp))
                    .findFirst()
                    .orElse(null);

                if (server == null) {
                    server = new Server();
                    server.setGroup(group);
                    server.setName(currentNodeIp);
                    server.setIp(currentNodeIp);
                    server.setSshPort(22);
                    server.setStatus(1);
                    server = serverRepository.save(server);
                }

                ClusterNode node = new ClusterNode();
                node.setCluster(cluster);
                node.setServer(server);
                node.setNodeIndex(nodeIndex++);
                node.setNodeId(nodeInfo.get("nodeId"));
                node.setIp(currentNodeIp);
                node.setPort(currentNodePort);
                node.setNodeRole(1); // slave
                node.setNodeRoleStr("slave");
                String linkState = nodeInfo.get("linkState");
                node.setStatus("connected".equals(linkState) ? 1 : 2);
                
                // 设置主节点ID
                String masterId = nodeInfo.get("masterId");
                if (masterId != null && !masterId.isEmpty() && !"-".equals(masterId)) {
                    node.setMasterId(masterId);
                    logger.info("从节点 {}:{} 的主节点ID: {}", currentNodeIp, currentNodePort, masterId);
                }
                
                node = nodeRepository.save(node);
                savedNodes.add(node);
                slaveCount++;
            }

            // 更新主从关系（使用保存后的节点列表）
            updateMasterSlaveRelationships(savedNodes);

            Map<String, Object> result = new HashMap<>();
            result.put("clusterId", cluster.getId());
            result.put("totalNodes", nodeInfos.size());
            result.put("masterCount", masterCount);
            result.put("slaveCount", slaveCount);
            result.put("message", "集群导入成功，共发现 " + nodeInfos.size() + " 个节点（" + masterCount + " 主 " + slaveCount + " 从）");
            return Result.success(result);

        } catch (Exception e) {
            logger.error("导入集群失败", e);
            return Result.error("导入失败: " + e.getMessage());
        }
    }
    
    /**
     * 解析 CLUSTER NODES 输出
     */
    private List<Map<String, String>> parseClusterNodes(String output) {
        List<Map<String, String>> nodes = new ArrayList<>();
        
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("Warning")) {
                continue;
            }
            
            // 格式: <node-id> <ip:port@cport> <flags> <master-id> <ping-sent> <pong-recv> <config-epoch> <link-state> <slot>
            // 使用\s+分割，处理多个连续空格
            String[] parts = line.split("\\s+");
            if (parts.length < 8) {
                continue;
            }
            
            Map<String, String> node = new HashMap<>();
            node.put("nodeId", parts[0]);
            
            // 解析 ip:port
            String[] ipPort = parts[1].split("@")[0].split(":");
            if (ipPort.length == 2) {
                node.put("ip", ipPort[0]);
                node.put("port", ipPort[1]);
            } else {
                // 可能是内网IP，格式为 127.0.0.1:port
                String[] parts2 = parts[1].split("@")[0].split(":");
                if (parts2.length >= 2) {
                    node.put("ip", parts2[0]);
                    node.put("port", parts2[1]);
                }
            }
            
            node.put("flags", parts[2]);
            node.put("masterId", parts[3].equals("-") ? null : parts[3]);
            node.put("linkState", parts[7]);
            
            if (parts.length > 8) {
                StringBuilder slots = new StringBuilder();
                for (int i = 8; i < parts.length; i++) {
                    if (i > 8) slots.append(" ");
                    slots.append(parts[i]);
                }
                node.put("slots", slots.toString());
            }
            
            nodes.add(node);
        }
        
        return nodes;
    }
    
    /**
     * 更新主从关系
     */
    private void updateMasterSlaveRelationships(List<ClusterNode> nodes) {
        Map<String, ClusterNode> nodeMap = new HashMap<>();
        
        // 先建立 nodeId 到节点的映射
        for (ClusterNode node : nodes) {
            if (node.getNodeId() != null) {
                nodeMap.put(node.getNodeId(), node);
            }
        }
        
        logger.info("更新主从关系，共 {} 个节点，nodeId 映射建立完成: {} 个", 
            nodes.size(), nodeMap.size());
        
        // 打印所有节点信息便于调试
        logger.info("==== 节点详情 ====");
        for (ClusterNode node : nodes) {
            logger.info("节点: nodeId={}, nodeIndex={}, role={}, masterId={}",
                node.getNodeId(), node.getNodeIndex(), 
                node.getNodeRole() == 0 ? "master" : "slave",
                node.getMasterId());
        }
        
        // 设置从节点的主节点
        for (ClusterNode node : nodes) {
            if (node.getNodeRole() == 1 && node.getMasterId() != null) {
                ClusterNode masterNode = nodeMap.get(node.getMasterId());
                if (masterNode != null) {
                    node.setMasterNode(masterNode);
                    // 同时设置 masterIndex 为主节点的 nodeIndex，用于前端匹配
                    if (masterNode.getNodeIndex() != null) {
                        node.setMasterIndex(masterNode.getNodeIndex());
                    }
                    nodeRepository.save(node);
                    logger.info("设置从节点(nodeIndex={}) 的主节点为nodeIndex={}, masterId={}", 
                        node.getNodeIndex(), masterNode.getNodeIndex(), masterNode.getNodeId());
                } else {
                    logger.warn("未找到从节点(nodeIndex={}, nodeId={}) 的主节点(masterId={})", 
                        node.getNodeIndex(), node.getNodeId(), node.getMasterId());
                }
            }
        }
        logger.info("==== 主从关系更新完成 ====");
    }

    /**
     * 转换为DTO
     * @param cluster 集群实体
     * @param realtime 是否实时查询监控数据（列表页用false，详情页用true）
     */
    private Map<String, Object> convertToDTO(RedisCluster cluster, boolean realtime) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", cluster.getId());
        dto.put("name", cluster.getName());
        dto.put("redisVersion", cluster.getRedisVersion());
        dto.put("basePort", cluster.getBasePort());
        dto.put("password", cluster.getPassword());  // 集群密码
        dto.put("status", cluster.getStatus());
        dto.put("statusDesc", cluster.getStatusDesc());
        dto.put("clusterType", cluster.getClusterType());
        dto.put("serverGroupName", cluster.getServerGroup() != null ? cluster.getServerGroup().getName() : null);
        dto.put("serverGroupId", cluster.getServerGroup() != null ? cluster.getServerGroup().getId() : null);
        dto.put("createTime", cluster.getCreateTime() != null ? cluster.getCreateTime().format(DATE_FORMATTER) : null);
        
        // 节点信息
        List<Map<String, Object>> nodes = new ArrayList<>();
        long totalUsedMemoryMB = 0;      // 主节点使用内存总和 (MB)
        long totalMaxMemoryMB = 0;       // 主节点内存限制总和 (MB)
        int totalConnectedClients = 0;   // 主节点连接数总和
        String memoryUnit = "MB";        // 内存单位
        int masterCount = 0;             // 主节点数量
        
        for (ClusterNode node : cluster.getNodes()) {
            Map<String, Object> nodeDto = new HashMap<>();
            nodeDto.put("id", node.getId());
            nodeDto.put("nodeId", node.getNodeId());  // Redis节点ID，用于主从匹配
            nodeDto.put("nodeIndex", node.getNodeIndex());
            nodeDto.put("ip", node.getIp());
            nodeDto.put("port", node.getPort());
            nodeDto.put("role", node.getNodeRoleStr());  // 返回字符串 master/slave
            nodeDto.put("roleDesc", node.getRoleDesc());
            nodeDto.put("nodeType", node.getNodeRoleStr());  // 为了兼容性
            nodeDto.put("status", node.getStatus());
            nodeDto.put("configPath", node.getConfigPath());
            nodeDto.put("masterId", node.getMasterId());  // 从节点对应的主节点ID(Redis节点ID)
            nodeDto.put("masterIndex", node.getMasterIndex());  // 从节点对应的主节点索引
            
            // 获取节点监控数据：详情页实时查询，列表页使用数据库缓存
            Map<String, Object> metrics;
            if (realtime) {
                metrics = fetchNodeMetricsRealtime(cluster, node);
            } else {
                metrics = getNodeMetricsFromCache(node);
            }
            nodeDto.put("usedMemory", metrics.get("usedMemory"));
            nodeDto.put("usedMemoryHuman", metrics.get("usedMemoryHuman"));
            nodeDto.put("maxMemory", metrics.get("maxMemory"));
            nodeDto.put("maxMemoryHuman", metrics.get("maxMemoryHuman"));
            nodeDto.put("connectedClients", metrics.get("connectedClients"));
            nodeDto.put("memoryUnit", metrics.get("memoryUnit"));
            nodes.add(nodeDto);
            
            // 统计主节点数据
            if (node.getNodeRole() != null && node.getNodeRole() == 0) {
                masterCount++;
                Long usedMem = (Long) metrics.get("usedMemory");
                Long maxMem = (Long) metrics.get("maxMemory");
                Integer clients = (Integer) metrics.get("connectedClients");
                String unit = (String) metrics.get("memoryUnit");
                
                if (usedMem != null) {
                    totalUsedMemoryMB += usedMem;
                }
                if (maxMem != null) {
                    totalMaxMemoryMB += maxMem;
                }
                if (clients != null) {
                    totalConnectedClients += clients;
                }
                if (unit != null) {
                    memoryUnit = unit;
                }
            }
        }
        dto.put("nodes", nodes);
        
        // 主节点统计信息
        dto.put("masterCount", masterCount);
        dto.put("totalUsedMemoryMB", totalUsedMemoryMB);
        dto.put("totalMaxMemoryMB", totalMaxMemoryMB);
        dto.put("totalUsedMemoryHuman", formatMemoryHuman(totalUsedMemoryMB));
        dto.put("totalMaxMemoryHuman", formatMemoryHuman(totalMaxMemoryMB));
        dto.put("totalConnectedClients", totalConnectedClients);
        dto.put("memoryUsageRate", totalMaxMemoryMB > 0 ? 
                Math.round((double) totalUsedMemoryMB / totalMaxMemoryMB * 100) : 0);
        
        return dto;
    }
    
    /**
     * 从数据库缓存获取节点监控数据（用于列表页快速加载）
     */
    private Map<String, Object> getNodeMetricsFromCache(ClusterNode node) {
        Map<String, Object> metrics = new HashMap<>();
        
        Long usedMemory = node.getUsedMemory();
        Long maxMemory = node.getMaxMemory();
        Integer connectedClients = node.getConnectedClients();
        
        metrics.put("usedMemory", usedMemory != null ? usedMemory : 0L);
        metrics.put("usedMemoryHuman", formatMemoryHuman(usedMemory != null ? usedMemory : 0L));
        metrics.put("maxMemory", maxMemory != null ? maxMemory : 0L);
        metrics.put("maxMemoryHuman", formatMemoryHuman(maxMemory != null ? maxMemory : 0L));
        metrics.put("connectedClients", connectedClients != null ? connectedClients : 0);
        metrics.put("memoryUnit", "MB");
        
        return metrics;
    }
    
    /**
     * 实时获取节点监控数据
     */
    private Map<String, Object> fetchNodeMetricsRealtime(RedisCluster cluster, ClusterNode node) {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("usedMemory", 0L);
        metrics.put("usedMemoryHuman", "0MB");
        metrics.put("maxMemory", 0L);
        metrics.put("maxMemoryHuman", "0MB");
        metrics.put("connectedClients", 0);
        metrics.put("memoryUnit", "MB");
        
        // 获取SSH连接信息
        Server server = getServerForNode(cluster, node);
        if (server == null) {
            logger.warn("无法获取节点 {}:{} 的SSH连接信息", node.getIp(), node.getPort());
            return metrics;
        }
        
        String password = cluster.getPassword();
        String host = node.getIp();
        int port = node.getPort();
        
        try {
            SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(),
                    server.getSshUser(), server.getSshPassword());
            ssh.connect();
            
            try {
                String authCmd = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";
                
                // 首先检查Redis是否可连接
                String pingCmd = String.format("redis-cli -h %s -p %d%s ping 2>&1", host, port, authCmd);
                SSHClient.SSHResult pingResult = ssh.executeCommand(pingCmd);
                String pingOutput = pingResult.getStdout().trim();
                // Redis 6.x 使用 -a 参数会输出警告信息，需要检查是否包含 PONG
                if (!pingOutput.toUpperCase().contains("PONG")) {
                    logger.warn("节点 {}:{} Redis连接失败: {}", host, port, pingOutput);
                    return metrics;
                }
                
                // 获取内存使用
                String usedMemCmd = String.format("redis-cli -h %s -p %d%s info memory 2>&1 | grep '^used_memory:'", host, port, authCmd);
                SSHClient.SSHResult usedMemResult = ssh.executeCommand(usedMemCmd);
                String usedMemOutput = usedMemResult.getStdout().trim();
                logger.debug("节点 {}:{} used_memory 输出: {}", host, port, usedMemOutput);
                if (usedMemOutput.contains(":")) {
                    try {
                        String valueStr = usedMemOutput.split(":")[1].trim();
                        long usedMemoryBytes = Long.parseLong(valueStr);
                        long usedMemoryMB = usedMemoryBytes / 1024 / 1024;
                        metrics.put("usedMemory", usedMemoryMB);
                        metrics.put("usedMemoryHuman", formatMemoryHuman(usedMemoryMB));
                        logger.debug("节点 {}:{} 内存使用: {} MB", host, port, usedMemoryMB);
                    } catch (Exception e) {
                        logger.warn("节点 {}:{} 解析used_memory失败: {}", host, port, e.getMessage());
                    }
                }
                
                // 获取最大内存限制
                String maxMemCmd = String.format("redis-cli -h %s -p %d%s info memory 2>&1 | grep '^maxmemory:'", host, port, authCmd);
                SSHClient.SSHResult maxMemResult = ssh.executeCommand(maxMemCmd);
                String maxMemOutput = maxMemResult.getStdout().trim();
                logger.debug("节点 {}:{} maxmemory 输出: {}", host, port, maxMemOutput);
                if (maxMemOutput.contains(":")) {
                    try {
                        String valueStr = maxMemOutput.split(":")[1].trim();
                        long maxMemoryBytes = Long.parseLong(valueStr);
                        long maxMemoryMB = maxMemoryBytes / 1024 / 1024;
                        metrics.put("maxMemory", maxMemoryMB);
                        metrics.put("maxMemoryHuman", formatMemoryHuman(maxMemoryMB));
                        logger.debug("节点 {}:{} 最大内存: {} MB", host, port, maxMemoryMB);
                    } catch (Exception e) {
                        logger.warn("节点 {}:{} 解析maxmemory失败: {}", host, port, e.getMessage());
                    }
                }
                
                // 获取连接数
                String clientsCmd = String.format("redis-cli -h %s -p %d%s info clients 2>&1 | grep '^connected_clients:'", host, port, authCmd);
                SSHClient.SSHResult clientsResult = ssh.executeCommand(clientsCmd);
                String clientsOutput = clientsResult.getStdout().trim();
                logger.debug("节点 {}:{} connected_clients 输出: {}", host, port, clientsOutput);
                if (clientsOutput.contains(":")) {
                    try {
                        String valueStr = clientsOutput.split(":")[1].trim();
                        int connectedClients = Integer.parseInt(valueStr);
                        metrics.put("connectedClients", connectedClients);
                        logger.debug("节点 {}:{} 连接数: {}", host, port, connectedClients);
                    } catch (Exception e) {
                        logger.warn("节点 {}:{} 解析connected_clients失败: {}", host, port, e.getMessage());
                    }
                }
                
            } finally {
                ssh.disconnect();
            }
        } catch (Exception e) {
            logger.warn("获取节点 {}:{} 监控数据失败: {}", host, port, e.getMessage());
        }
        
        return metrics;
    }
    
    /**
     * 格式化内存为人类可读格式
     */
    private String formatMemoryHuman(long mb) {
        if (mb < 1024) {
            return mb + "MB";
        } else {
            return String.format("%.2fGB", mb / 1024.0);
        }
    }

    /**
     * 获取集群节点信息 (CLUSTER NODES)
     */
    public Result<String> getClusterNodes(Long clusterId) {
        Optional<RedisCluster> opt = clusterRepository.findByIdWithNodes(clusterId);
        if (!opt.isPresent()) {
            return Result.error("集群不存在");
        }

        RedisCluster cluster = opt.get();
        List<ClusterNode> nodes = cluster.getNodes();
        if (nodes.isEmpty()) {
            return Result.error("集群没有节点");
        }

        // 找到第一个运行中的节点
        ClusterNode targetNode = nodes.stream()
                .filter(n -> n.getStatus() == 1)
                .findFirst()
                .orElse(nodes.get(0));

        // 获取SSH连接信息（支持系统创建和导入的集群）
        Server server = getServerForNode(cluster, targetNode);
        if (server == null) {
            return Result.error("无法获取节点的SSH连接信息");
        }

        try {
            SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(),
                    server.getSshUser(), server.getSshPassword());
            ssh.connect();

            try {
                String password = cluster.getPassword();
                String authCmd = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";
                String command = String.format("redis-cli -h %s -p %d%s cluster nodes 2>&1",
                        targetNode.getIp(), targetNode.getPort(), authCmd);

                SSHClient.SSHResult result = ssh.executeCommand(command);
                String output = result.getStdout();
                logger.debug("CLUSTER INFO 输出:\n{}", output);
                
                // 检查是否包含错误信息
                if (output.contains("cluster_state")) {
                    logger.info("获取CLUSTER INFO成功: cluster_state={}", 
                            output.contains("cluster_state:ok") ? "ok" : "fail");
                } else {
                    logger.warn("获取CLUSTER INFO异常: {}", output);
                }
                
                return Result.success(output);
            } finally {
                ssh.disconnect();
            }
        } catch (Exception e) {
            logger.error("获取CLUSTER NODES失败", e);
            return Result.error("获取失败: " + e.getMessage());
        }
    }

    /**
     * 获取集群信息 (CLUSTER INFO)
     */
    public Result<String> getClusterInfo(Long clusterId) {
        Optional<RedisCluster> opt = clusterRepository.findByIdWithNodes(clusterId);
        if (!opt.isPresent()) {
            return Result.error("集群不存在");
        }

        RedisCluster cluster = opt.get();
        List<ClusterNode> nodes = cluster.getNodes();
        if (nodes.isEmpty()) {
            return Result.error("集群没有节点");
        }

        // 找到第一个运行中的节点
        ClusterNode targetNode = nodes.stream()
                .filter(n -> n.getStatus() == 1)
                .findFirst()
                .orElse(nodes.get(0));

        // 获取SSH连接信息（支持系统创建和导入的集群）
        Server server = getServerForNode(cluster, targetNode);
        if (server == null) {
            return Result.error("无法获取节点的SSH连接信息");
        }

        try {
            SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(),
                    server.getSshUser(), server.getSshPassword());
            ssh.connect();

            try {
                String password = cluster.getPassword();
                String authCmd = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";
                String command = String.format("redis-cli -h %s -p %d%s cluster info 2>&1",
                        targetNode.getIp(), targetNode.getPort(), authCmd);

                SSHClient.SSHResult result = ssh.executeCommand(command);
                return Result.success(result.getStdout());
            } finally {
                ssh.disconnect();
            }
        } catch (Exception e) {
            logger.error("获取CLUSTER INFO失败", e);
            return Result.error("获取失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取节点对应的SSH连接信息（支持系统创建和导入的集群）
     */
    private Server getServerForNode(RedisCluster cluster, ClusterNode node) {
        logger.debug("获取节点SSH信息: 节点{}:{}, nodeIndex={}, nodeId={}", 
                node.getIp(), node.getPort(), node.getNodeIndex(), node.getNodeId());
        
        // 优先使用节点直接关联的Server（导入的集群）
        if (node.getServer() != null) {
            Server s = node.getServer();
            logger.debug("使用节点关联的Server: {}:{}", s.getIp(), s.getSshPort());
            return s;
        }
        
        // 系统创建的集群，从ServerGroup获取
        ServerGroup serverGroup = cluster.getServerGroup();
        logger.debug("集群 {} 的ServerGroup: {}", cluster.getId(), 
                serverGroup != null ? serverGroup.getId() : "null");
        
        if (serverGroup != null && serverGroup.getServers() != null) {
            List<Server> servers = new ArrayList<>(serverGroup.getServers());
            logger.debug("服务器组有 {} 台服务器", servers.size());
            
            if (node.getNodeIndex() >= 0 && node.getNodeIndex() < servers.size()) {
                Server s = servers.get(node.getNodeIndex());
                logger.debug("根据nodeIndex={}获取到服务器: {}:{}", 
                        node.getNodeIndex(), s.getIp(), s.getSshPort());
                return s;
            } else {
                logger.warn("节点nodeIndex={}超出服务器列表范围[0, {}]", 
                        node.getNodeIndex(), servers.size() - 1);
            }
        } else {
            logger.warn("集群 {} 没有关联ServerGroup或servers为空", cluster.getId());
        }
        
        return null;
    }

    /**
     * 动态更新集群参数
     */
    @Transactional
    public Result<Map<String, Object>> updateClusterConfig(Long clusterId, String parameter, String value) {
        Optional<RedisCluster> opt = clusterRepository.findByIdWithNodes(clusterId);
        if (!opt.isPresent()) {
            return Result.error("集群不存在");
        }

        RedisCluster cluster = opt.get();
        List<ClusterNode> nodes = cluster.getNodes();
        if (nodes.isEmpty()) {
            return Result.error("集群没有节点");
        }

        List<Map<String, Object>> details = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (ClusterNode node : nodes) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("nodeId", node.getId());
            detail.put("address", node.getIp() + ":" + node.getPort());
            detail.put("role", node.getNodeRoleStr());

            if (node.getStatus() != 1) {
                detail.put("success", false);
                detail.put("message", "节点未运行");
                failCount++;
                details.add(detail);
                continue;
            }

            // 获取SSH连接信息（支持系统创建和导入的集群）
            Server server = getServerForNode(cluster, node);
            if (server == null) {
                detail.put("success", false);
                detail.put("message", "无法获取SSH连接信息");
                failCount++;
                details.add(detail);
                continue;
            }

            try {
                SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(),
                        server.getSshUser(), server.getSshPassword());
                ssh.connect();

                try {
                    String password = cluster.getPassword();
                    String authCmd = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";

                    // 执行 CONFIG SET
                    String setCommand = String.format("redis-cli -h %s -p %d%s config set %s %s 2>&1",
                            node.getIp(), node.getPort(), authCmd, parameter, value);
                    SSHClient.SSHResult setResult = ssh.executeCommand(setCommand);

                    if (setResult.getStdout().contains("OK")) {
                        // 执行 CONFIG REWRITE 写入配置文件
                        String rewriteCommand = String.format("redis-cli -h %s -p %d%s config rewrite 2>&1",
                                node.getIp(), node.getPort(), authCmd);
                        SSHClient.SSHResult rewriteResult = ssh.executeCommand(rewriteCommand);

                        if (rewriteResult.getStdout().contains("OK") || rewriteResult.getStdout().isEmpty()) {
                            detail.put("success", true);
                            detail.put("message", "配置已更新并写入文件");
                            successCount++;
                        } else {
                            detail.put("success", true);
                            detail.put("message", "配置已更新，但写入文件失败: " + rewriteResult.getStdout());
                            successCount++;
                        }
                    } else {
                        detail.put("success", false);
                        detail.put("message", "CONFIG SET 失败: " + setResult.getStdout());
                        failCount++;
                    }
                } finally {
                    ssh.disconnect();
                }
            } catch (Exception e) {
                logger.error("更新节点配置失败: " + node.getIp() + ":" + node.getPort(), e);
                detail.put("success", false);
                detail.put("message", "执行失败: " + e.getMessage());
                failCount++;
            }

            details.add(detail);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("parameter", parameter);
        result.put("value", value);
        result.put("successCount", successCount);
        result.put("failCount", failCount);
        result.put("totalCount", nodes.size());
        result.put("details", details);

        return Result.success(result);
    }

    /**
     * 查询缓存Key列表 - 集群模式查询所有主节点
     */
    public Result<List<Map<String, Object>>> queryKeys(Long clusterId, String pattern) {
        Optional<RedisCluster> opt = clusterRepository.findById(clusterId);
        if (!opt.isPresent()) {
            return Result.error("集群不存在");
        }

        RedisCluster cluster = opt.get();
        String password = cluster.getPassword();

        try {
            // 获取所有主节点
            List<ClusterNode> masterNodes = cluster.getNodes().stream()
                    .filter(n -> n.getNodeRole() != null && n.getNodeRole() == 0)
                    .collect(Collectors.toList());
            
            if (masterNodes.isEmpty()) {
                return Result.error("集群没有可用的主节点");
            }

            // 在每个主节点上查询
            List<Map<String, Object>> allKeys = new ArrayList<>();
            Set<String> uniqueKeys = new HashSet<>();
            
            for (ClusterNode masterNode : masterNodes) {
                try {
                    List<Map<String, Object>> nodeKeys = queryKeysFromNode(cluster, masterNode, password, pattern, 50);
                    for (Map<String, Object> keyInfo : nodeKeys) {
                        String key = (String) keyInfo.get("key");
                        if (key != null && !key.isEmpty() && uniqueKeys.add(key)) {
                            keyInfo.put("nodeIp", masterNode.getIp());
                            keyInfo.put("nodePort", masterNode.getPort());
                            allKeys.add(keyInfo);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("从节点 {}:{} 查询key失败: {}", masterNode.getIp(), masterNode.getPort(), e.getMessage());
                }
            }
            
            return Result.success(allKeys);
        } catch (Exception e) {
            logger.error("查询缓存失败", e);
            return Result.error("查询失败: " + e.getMessage());
        }
    }
    
    /**
     * 从单个节点查询key
     */
    private List<Map<String, Object>> queryKeysFromNode(RedisCluster cluster, ClusterNode node, String password, String pattern, int maxCount) throws Exception {
        List<Map<String, Object>> keys = new ArrayList<>();
        Server server = getServerForNode(cluster, node);
        if (server == null) {
            throw new Exception("无法获取SSH连接信息");
        }

        SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(),
                server.getSshUser(), server.getSshPassword());
        ssh.connect();

        try {
            String ip = node.getIp();
            int port = node.getPort();
            // 转义密码中的单引号
            String safePassword = password != null ? password.replace("'", "'\"'\"'") : "";
            String authCmd = !safePassword.isEmpty() ? " -a '" + safePassword + "'" : "";
            
            int count = 0;
            String cursor = "0";
            int maxIterations = 10;
            int iterations = 0;
            
            do {
                iterations++;
                if (iterations > maxIterations) {
                    logger.warn("SCAN迭代次数超过限制，停止查询");
                    break;
                }
                
                // 不使用 2>&1，避免Warning信息混入输出
                String scanCommand = String.format("redis-cli -h %s -p %d%s --raw scan %s MATCH '%s' COUNT 50",
                        ip, port, authCmd, cursor, pattern);
                SSHClient.SSHResult result = ssh.executeCommand(scanCommand);
                String output = filterWarningLines(result.getStdout()).trim();
                
                if (output.isEmpty()) break;
                
                String[] lines = output.split("\\n");
                if (lines.length == 0) break;
                
                // 第一行是cursor
                String newCursor = lines[0].trim();
                if (!newCursor.matches("\\d+")) {
                    logger.warn("无效的cursor: {}", newCursor);
                    break;
                }
                cursor = newCursor;
                
                // 从第二行开始是key列表
                for (int i = 1; i < lines.length && count < maxCount; i++) {
                    String key = lines[i].trim();
                    if (key.isEmpty() || key.equals("(empty array)") || key.equals("0")) continue;
                    // 过滤掉纯数字行（可能是cursor被错误解析）
                    if (key.matches("\\d+")) continue;
                    
                    Map<String, Object> keyInfo = getKeyInfo(ssh, ip, port, authCmd, key);
                    if (keyInfo != null) {
                        keys.add(keyInfo);
                        count++;
                    }
                }
            } while (!"0".equals(cursor) && count < maxCount);
            
            return keys;
        } finally {
            ssh.disconnect();
        }
    }
    
    /**
     * 过滤掉Warning行
     */
    private String filterWarningLines(String output) {
        if (output == null || output.isEmpty()) return "";
        return Arrays.stream(output.split("\\n"))
                .filter(line -> !line.trim().startsWith("Warning:"))
                .collect(Collectors.joining("\\n"));
    }
    
    /**
     * 获取单个key的信息
     */
    private Map<String, Object> getKeyInfo(SSHClient ssh, String ip, int port, String authCmd, String key) {
        try {
            // 转义key中的单引号
            String escapedKey = key.replace("'", "'\"'\"'");
            
            // 获取类型
            String typeCommand = String.format("redis-cli -h %s -p %d%s --raw type '%s'",
                    ip, port, authCmd, escapedKey);
            SSHClient.SSHResult typeResult = ssh.executeCommand(typeCommand);
            String type = filterWarningLines(typeResult.getStdout()).trim();
            
            // 如果类型是"none"，说明key不存在
            if ("none".equals(type) || type.isEmpty()) {
                return null;
            }
            
            // 获取TTL
            String ttlCommand = String.format("redis-cli -h %s -p %d%s --raw ttl '%s'",
                    ip, port, authCmd, escapedKey);
            SSHClient.SSHResult ttlResult = ssh.executeCommand(ttlCommand);
            long ttl = -1;
            try {
                String ttlStr = filterWarningLines(ttlResult.getStdout()).trim();
                ttl = Long.parseLong(ttlStr);
            } catch (NumberFormatException ignored) {}
            
            Map<String, Object> keyInfo = new HashMap<>();
            keyInfo.put("key", key);
            keyInfo.put("type", type);
            keyInfo.put("ttl", ttl);
            return keyInfo;
        } catch (Exception e) {
            logger.debug("获取key {} 信息失败: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 获取缓存Key的值
     */
    public Result<Map<String, Object>> getKeyValue(Long clusterId, String ip, Integer port, String key) {
        Optional<RedisCluster> opt = clusterRepository.findById(clusterId);
        if (!opt.isPresent()) {
            return Result.error("集群不存在");
        }

        RedisCluster cluster = opt.get();
        String password = cluster.getPassword();

        try {
            ClusterNode targetNode = cluster.getNodes().stream()
                    .filter(n -> n.getIp().equals(ip) && n.getPort() != null && n.getPort().equals(port))
                    .findFirst()
                    .orElse(null);
            
            if (targetNode == null) {
                return Result.error("节点不存在");
            }

            Server server = getServerForNode(cluster, targetNode);
            if (server == null) {
                return Result.error("无法获取SSH连接信息");
            }

            SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(),
                    server.getSshUser(), server.getSshPassword());
            ssh.connect();

            try {
                String authCmd = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";
                
                // 获取类型
                String typeCommand = String.format("redis-cli -h %s -p %d%s type %s 2>&1",
                        ip, port, authCmd, key);
                SSHClient.SSHResult typeResult = ssh.executeCommand(typeCommand);
                String type = typeResult.getStdout().trim();
                
                // 获取TTL
                String ttlCommand = String.format("redis-cli -h %s -p %d%s ttl %s 2>&1",
                        ip, port, authCmd, key);
                SSHClient.SSHResult ttlResult = ssh.executeCommand(ttlCommand);
                long ttl = -1;
                try {
                    ttl = Long.parseLong(ttlResult.getStdout().trim());
                } catch (NumberFormatException ignored) {}
                
                // 获取值（根据类型使用不同命令）
                String valueCommand;
                switch (type) {
                    case "string":
                        valueCommand = String.format("redis-cli -h %s -p %d%s get %s 2>&1", ip, port, authCmd, key);
                        break;
                    case "hash":
                        valueCommand = String.format("redis-cli -h %s -p %d%s hgetall %s 2>&1", ip, port, authCmd, key);
                        break;
                    case "list":
                        valueCommand = String.format("redis-cli -h %s -p %d%s lrange %s 0 -1 2>&1", ip, port, authCmd, key);
                        break;
                    case "set":
                        valueCommand = String.format("redis-cli -h %s -p %d%s smembers %s 2>&1", ip, port, authCmd, key);
                        break;
                    case "zset":
                        valueCommand = String.format("redis-cli -h %s -p %d%s zrange %s 0 -1 withscores 2>&1", ip, port, authCmd, key);
                        break;
                    default:
                        valueCommand = String.format("redis-cli -h %s -p %d%s get %s 2>&1", ip, port, authCmd, key);
                }
                
                SSHClient.SSHResult valueResult = ssh.executeCommand(valueCommand);
                String value = valueResult.getStdout().trim();
                // 限制值长度
                if (value.length() > 1000) {
                    value = value.substring(0, 1000) + "... (已截断)";
                }
                
                Map<String, Object> result = new HashMap<>();
                result.put("key", key);
                result.put("type", type);
                result.put("ttl", ttl);
                result.put("value", value);
                
                return Result.success(result);
            } finally {
                ssh.disconnect();
            }
        } catch (Exception e) {
            logger.error("获取key值失败", e);
            return Result.error("获取失败: " + e.getMessage());
        }
    }

    /**
     * 获取节点配置项
     */
    public Result<String> getNodeConfig(Long clusterId, String ip, Integer port, String param) {
        logger.info("开始获取节点配置: clusterId={}, ip={}, port={}, param={}", clusterId, ip, port, param);
        
        Optional<RedisCluster> opt = clusterRepository.findByIdWithNodes(clusterId);
        if (!opt.isPresent()) {
            logger.warn("集群不存在: clusterId={}", clusterId);
            return Result.error("集群不存在");
        }

        RedisCluster cluster = opt.get();
        String password = cluster.getPassword();
        logger.debug("集群密码是否为空: {}", (password != null && !password.isEmpty()) ? "否" : "是");

        try {
            // 检查节点列表
            List<ClusterNode> nodes = cluster.getNodes();
            logger.info("集群 {} 节点数量: {}", clusterId, nodes != null ? nodes.size() : 0);
            
            if (nodes != null && !nodes.isEmpty()) {
                nodes.forEach(n -> logger.debug("集群节点: {}:{}, nodeIndex={}", 
                        n.getIp(), n.getPort(), n.getNodeIndex()));
            }
            
            ClusterNode targetNode = nodes.stream()
                    .filter(n -> {
                        boolean ipMatch = n.getIp().equals(ip);
                        // 使用 equals 比较 Integer，避免 == 在大于127的数字时比较引用地址
                        boolean portMatch = n.getPort() != null && n.getPort().equals(port);
                        logger.debug("匹配节点 {}:{} - ip匹配: {}, port匹配: {} (n.getPort()={}, port={})", 
                                ip, port, ipMatch, portMatch, n.getPort(), port);
                        return ipMatch && portMatch;
                    })
                    .findFirst()
                    .orElse(null);
            
            if (targetNode == null) {
                logger.warn("节点不存在: {}:{} 在集群 {} 的 {} 个节点中未找到", 
                        ip, port, clusterId, nodes != null ? nodes.size() : 0);
                return Result.error("节点不存在");
            }
            logger.info("找到节点: {}:{}, nodeIndex={}, nodeId={}", ip, port, targetNode.getNodeIndex(), targetNode.getNodeId());

            Server server = getServerForNode(cluster, targetNode);
            if (server == null) {
                logger.error("无法获取SSH连接信息: 节点 {}:{}, nodeIndex={}", ip, port, targetNode.getNodeIndex());
                return Result.error("无法获取SSH连接信息");
            }
            logger.info("使用服务器连接: {}:{}, sshUser={}", server.getIp(), server.getSshPort(), server.getSshUser());

            SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(),
                    server.getSshUser(), server.getSshPassword());
            ssh.connect();
            logger.debug("成功连接SSH到服务器 {}:{}", server.getIp(), server.getSshPort());

            try {
                String authCmd = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";
                logger.info("集群 {} 密码长度: {}", clusterId, 
                        (password != null ? password.length() : 0));
                logger.debug("生成的authCmd: [{}]", authCmd.replace(password != null ? password : "", "***"));
                
                // 对于 maxmemory-policy，直接使用 info memory 获取（更可靠）
                if ("maxmemory-policy".equals(param)) {
                    String infoCmd = String.format("redis-cli -h %s -p %d%s info memory 2>&1 | grep '^maxmemory_policy:' | cut -d: -f2",
                            ip, port, authCmd);
                    // 打印脱敏后的命令
                    String maskedCmd = infoCmd;
                    if (password != null && !password.isEmpty()) {
                        maskedCmd = infoCmd.replace(password, "***");
                    }
                    logger.info("执行命令获取maxmemory_policy: {}", maskedCmd);
                    
                    SSHClient.SSHResult infoResult = ssh.executeCommand(infoCmd);
                    String infoValue = infoResult.getStdout().trim();
                    String stderr = infoResult.getStderr().trim();
                    int exitCode = infoResult.getExitCode();
                    logger.info("命令返回: stdout=[{}], stderr=[{}], exitCode={}", infoValue, stderr, exitCode);
                    
                    // 如果返回空，可能是密码错误或需要密码
                    if (infoValue.isEmpty() && stderr.contains("NOAUTH")) {
                        logger.error("获取maxmemory_policy失败: 需要密码或密码错误");
                        return Result.error("需要密码或密码错误");
                    }
                    
                    if (!infoValue.isEmpty()) {
                        logger.info("成功获取maxmemory_policy: {}", infoValue);
                        return Result.success(infoValue);
                    }
                    logger.warn("获取maxmemory_policy为空，可能Redis没有设置该参数，返回unknown");
                    return Result.success("unknown");
                }
                
                // 其他参数使用 config get 获取
                String command = String.format("redis-cli -h %s -p %d%s config get %s 2>&1 | tail -1",
                        ip, port, authCmd, param);
                logger.debug("执行命令: {}", command);
                SSHClient.SSHResult result = ssh.executeCommand(command);
                String value = result.getStdout().trim();
                logger.debug("命令返回: {}", value);
                
                // 如果返回的是配置项名称，说明没有值，尝试从 info 中获取
                if (value.equals(param) || value.isEmpty()) {
                    // 尝试从 info memory 中获取（info 中使用下划线格式）
                    String infoParam = param.replace("-", "_");
                    String infoCmd = String.format("redis-cli -h %s -p %d%s info memory 2>&1 | grep '^%s:' | cut -d: -f2",
                            ip, port, authCmd, infoParam);
                    logger.debug("尝试从info memory获取: {}", infoCmd);
                    SSHClient.SSHResult infoResult = ssh.executeCommand(infoCmd);
                    String infoValue = infoResult.getStdout().trim();
                    logger.debug("从info memory获取返回: {}", infoValue);
                    
                    if (!infoValue.isEmpty()) {
                        return Result.success(infoValue);
                    }
                    
                    return Result.success("");
                }
                
                return Result.success(value);
            } finally {
                ssh.disconnect();
                logger.debug("断开SSH连接");
            }
        } catch (Exception e) {
            logger.error("获取节点配置失败: clusterId={}, ip={}, port={}, param={}, 错误: {}, 堆栈: {}", 
                    clusterId, ip, port, param, e.getMessage(), e);
            return Result.error("获取失败: " + e.getMessage());
        }
    }

    /**
     * 修改集群名称
     */
    @Transactional
    public Result<Map<String, Object>> updateClusterName(Long clusterId, String newName) {
        // 参数校验
        if (newName == null || newName.trim().isEmpty()) {
            return Result.error("集群名称不能为空");
        }
        
        newName = newName.trim();
        
        // 名称长度校验
        if (newName.length() > 100) {
            return Result.error("集群名称长度不能超过100个字符");
        }
        
        // 检查集群是否存在
        Optional<RedisCluster> opt = clusterRepository.findById(clusterId);
        if (!opt.isPresent()) {
            return Result.error("集群不存在");
        }
        
        RedisCluster cluster = opt.get();
        
        // 如果名称没有变化，直接返回成功
        if (newName.equals(cluster.getName())) {
            Map<String, Object> result = new HashMap<>();
            result.put("clusterId", clusterId);
            result.put("name", newName);
            result.put("message", "集群名称未发生变化");
            return Result.success(result);
        }
        
        // 检查新名称是否已存在
        if (clusterRepository.existsByName(newName)) {
            return Result.error("集群名称已存在");
        }
        
        // 更新名称
        String oldName = cluster.getName();
        cluster.setName(newName);
        clusterRepository.save(cluster);
        
        logger.info("集群名称修改成功: clusterId={}, 旧名称='{}', 新名称='{}'", 
                clusterId, oldName, newName);
        
        Map<String, Object> result = new HashMap<>();
        result.put("clusterId", clusterId);
        result.put("name", newName);
        result.put("oldName", oldName);
        result.put("message", "集群名称修改成功");
        return Result.success(result);
    }
}
