package com.redis.manager.service;

import com.redis.manager.dto.Result;
import com.redis.manager.entity.ClusterNode;
import com.redis.manager.entity.RedisInstance;
import com.redis.manager.entity.RedisCluster;
import com.redis.manager.entity.Server;
import com.redis.manager.entity.ServerGroup;
import com.redis.manager.repository.ClusterNodeRepository;
import com.redis.manager.repository.RedisInstanceRepository;
import com.redis.manager.repository.RedisClusterRepository;
import com.redis.manager.repository.ServerGroupRepository;
import com.redis.manager.repository.ServerRepository;
import com.redis.manager.ssh.SSHClient;
import com.redis.manager.util.RedisCommandUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Redis监控服务
 * 定时采集Redis集群监控指标
 */
@Service
public class RedisMonitorService {

    private static final Logger logger = LoggerFactory.getLogger(RedisMonitorService.class);

    @Autowired
    private RedisInstanceRepository instanceRepository;

    @Autowired
    private RedisClusterRepository clusterRepository;

    @Autowired
    private ClusterNodeRepository nodeRepository;

    @Autowired
    private ServerGroupRepository groupRepository;

    @Autowired
    private ServerRepository serverRepository;

    /**
     * 定时采集监控数据（默认每10分钟，可通过配置调整）
     */
    @Scheduled(fixedRateString = "${redis.manager.monitor-interval:600000}")
    public void collectMetrics() {
        // 采集 RedisInstance 的监控数据
        List<RedisInstance> instances = instanceRepository.findAllRunning();
        for (RedisInstance instance : instances) {
            try {
                collectInstanceMetrics(instance);
            } catch (Exception e) {
                logger.error("采集监控数据失败: {}", instance.getAddress());
            }
        }
        
        // 采集 ClusterNode（主节点）的监控数据（带server信息）
        List<ClusterNode> masterNodes = nodeRepository.findByNodeRoleWithServer(0); // 0 = 主节点
        for (ClusterNode node : masterNodes) {
            try {
                collectClusterNodeMetrics(node);
            } catch (Exception e) {
                logger.error("采集节点监控数据失败: {}:{}", node.getIp(), node.getPort());
            }
        }
    }
    
    /**
     * 采集主节点监控数据
     * 通过SSH连接到节点所在服务器执行redis-cli命令获取监控信息
     */
    private void collectClusterNodeMetrics(ClusterNode node) {
        RedisCluster cluster = node.getCluster();
        if (cluster == null) {
            logger.warn("节点 {}:{} 没有关联集群，跳过监控采集", node.getIp(), node.getPort());
            return;
        }
        
        // 获取SSH连接信息
        Server server = getServerForNode(cluster, node);
        if (server == null) {
            logger.warn("无法获取节点 {}:{} 的SSH连接信息，节点nodeIndex={}", 
                    node.getIp(), node.getPort(), node.getNodeIndex());
            return;
        }
        
        String host = node.getIp();
        int port = node.getPort();
        String password = cluster.getPassword();
        
        logger.debug("开始采集节点监控数据: {}:{}, 使用服务器: {}:{}, sshUser={}", 
                host, port, server.getIp(), server.getSshPort(), server.getSshUser());
        
        try {
            SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(),
                    server.getSshUser(), server.getSshPassword());
            ssh.connect();
            logger.debug("成功连接SSH到服务器 {}:{}", server.getIp(), server.getSshPort());
            
            try {
                String authCmd = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";
                logger.debug("节点 {}:{} 使用密码: {}", host, port, (password != null && !password.isEmpty() ? "是" : "否"));
                
                // 测试连接状态
                String pingCmd = String.format("redis-cli -h %s -p %d%s ping 2>&1", host, port, authCmd);
                logger.debug("执行PING命令: {}", pingCmd);
                SSHClient.SSHResult pingResult = ssh.executeCommand(pingCmd);
                String pingOutput = pingResult.getStdout().trim();
                logger.debug("节点 {}:{} PING返回: {}", host, port, pingOutput);
                
                if (!pingOutput.toUpperCase().contains("PONG")) {
                    logger.error("节点 {}:{} Redis连接失败，PING返回: {}", host, port, pingOutput);
                    node.setStatus(2); // 异常
                    return;
                }
                node.setStatus(1); // 在线
                
                // 获取内存使用
                String usedMemCmd = String.format("redis-cli -h %s -p %d%s info memory 2>&1 | grep '^used_memory:'", host, port, authCmd);
                logger.debug("执行命令: {}", usedMemCmd);
                SSHClient.SSHResult usedMemResult = ssh.executeCommand(usedMemCmd);
                String usedMemOutput = usedMemResult.getStdout().trim();
                logger.debug("节点 {}:{} used_memory返回: {}", host, port, usedMemOutput);
                // 解析输出格式: used_memory:12345678
                if (usedMemOutput.contains(":")) {
                    try {
                        long usedMemoryBytes = Long.parseLong(usedMemOutput.split(":")[1].trim());
                        node.setUsedMemory(usedMemoryBytes / 1024 / 1024); // 转为MB
                    } catch (Exception e) {
                        logger.warn("解析used_memory失败: {}, 输出: {}", usedMemOutput, e.getMessage());
                    }
                }
                
                // 获取最大内存限制
                String maxMemCmd = String.format("redis-cli -h %s -p %d%s info memory 2>&1 | grep '^maxmemory:'", host, port, authCmd);
                logger.debug("执行命令: {}", maxMemCmd);
                SSHClient.SSHResult maxMemResult = ssh.executeCommand(maxMemCmd);
                String maxMemOutput = maxMemResult.getStdout().trim();
                logger.debug("节点 {}:{} maxmemory返回: {}", host, port, maxMemOutput);
                // 解析输出格式: maxmemory:1073741824
                if (maxMemOutput.contains(":")) {
                    try {
                        long maxMemoryBytes = Long.parseLong(maxMemOutput.split(":")[1].trim());
                        node.setMaxMemory(maxMemoryBytes / 1024 / 1024); // 转为MB
                    } catch (Exception e) {
                        logger.warn("解析maxmemory失败: {}, 输出: {}", maxMemOutput, e.getMessage());
                    }
                }
                
                // 获取连接数
                String clientsCmd = String.format("redis-cli -h %s -p %d%s info clients 2>&1 | grep '^connected_clients:'", host, port, authCmd);
                logger.debug("执行命令: {}", clientsCmd);
                SSHClient.SSHResult clientsResult = ssh.executeCommand(clientsCmd);
                String clientsOutput = clientsResult.getStdout().trim();
                logger.debug("节点 {}:{} connected_clients返回: {}", host, port, clientsOutput);
                // 解析输出格式: connected_clients:10
                if (clientsOutput.contains(":")) {
                    try {
                        int connectedClients = Integer.parseInt(clientsOutput.split(":")[1].trim());
                        node.setConnectedClients(connectedClients);
                    } catch (Exception e) {
                        logger.warn("解析connected_clients失败: {}, 输出: {}", clientsOutput, e.getMessage());
                    }
                }
                
            } finally {
                ssh.disconnect();
            }
            
        } catch (Exception e) {
            logger.error("采集节点监控数据失败: {}:{}, 错误: {}, 堆栈: {}", 
                    host, port, e.getMessage(), e);
            node.setStatus(2); // 异常
        }
        
        node.setLastMonitorTime(LocalDateTime.now());
        nodeRepository.save(node);
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
     * 采集单个实例的监控数据
     */
    private void collectInstanceMetrics(RedisInstance instance) {
        String host = instance.getServer().getIp();
        int port = instance.getPort();
        String password = instance.getCluster() != null ? instance.getCluster().getRedisPassword() : null;
        
        Map<String, String> info = RedisCommandUtil.getRedisInfo(host, port, password);
        
        if ("1".equals(info.get("status"))) {
            // 内存使用 (字节转MB)
            try {
                long usedMemory = Long.parseLong(info.getOrDefault("used_memory", "0"));
                instance.setUsedMemory(usedMemory / 1024 / 1024);
            } catch (Exception e) {
                instance.setUsedMemory(0L);
            }
            
            // 连接数
            try {
                instance.setConnectedClients(
                        Integer.parseInt(info.getOrDefault("connected_clients", "0")));
            } catch (Exception e) {
                instance.setConnectedClients(0);
            }
            
            // 总命令处理数
            try {
                instance.setTotalCommandsProcessed(
                        Long.parseLong(info.getOrDefault("total_commands_processed", "0")));
            } catch (Exception e) {
                instance.setTotalCommandsProcessed(0L);
            }
            
            // 每秒命令数
            try {
                instance.setInstantaneousOpsPerSec(
                        Integer.parseInt(info.getOrDefault("instantaneous_ops_per_sec", "0")));
            } catch (Exception e) {
                instance.setInstantaneousOpsPerSec(0);
            }
            
            // 拒绝连接数
            try {
                instance.setRejectedConnections(
                        Long.parseLong(info.getOrDefault("rejected_connections", "0")));
            } catch (Exception e) {
                instance.setRejectedConnections(0L);
            }
            
            // 集群节点ID
            if (instance.getClusterNodeId() == null) {
                String nodeId = info.get("node_id");
                if (nodeId != null) {
                    instance.setClusterNodeId(nodeId);
                }
            }
            
            instance.setStatus(1);
        } else {
            instance.setStatus(2);  // 异常
        }
        
        instance.setLastMonitorTime(LocalDateTime.now());
        instanceRepository.save(instance);
    }

    /**
     * 获取实例监控数据
     */
    public Result<Map<String, Object>> getInstanceMetrics(Long instanceId) {
        Optional<RedisInstance> opt = instanceRepository.findById(instanceId);
        if (!opt.isPresent()) {
            return Result.error("实例不存在");
        }

        RedisInstance instance = opt.get();
        Map<String, Object> metrics = new HashMap<>();
        
        metrics.put("instanceId", instance.getId());
        metrics.put("address", instance.getAddress());
        metrics.put("port", instance.getPort());
        metrics.put("status", instance.getStatus());
        metrics.put("nodeType", instance.getNodeType());
        
        // 内存信息
        Map<String, Object> memory = new HashMap<>();
        memory.put("used", instance.getUsedMemory());
        memory.put("max", instance.getMaxMemory());
        if (instance.getMaxMemory() != null && instance.getMaxMemory() > 0) {
            double usageRate = (double) instance.getUsedMemory() / instance.getMaxMemory() * 100;
            memory.put("usageRate", String.format("%.2f%%", usageRate));
        }
        metrics.put("memory", memory);
        
        // 连接信息
        metrics.put("connectedClients", instance.getConnectedClients());
        metrics.put("totalCommandsProcessed", instance.getTotalCommandsProcessed());
        metrics.put("instantaneousOpsPerSec", instance.getInstantaneousOpsPerSec());
        metrics.put("rejectedConnections", instance.getRejectedConnections());
        
        // 实时数据
        if (instance.getStatus() == 1) {
            String password = instance.getCluster() != null ? instance.getCluster().getRedisPassword() : null;
            Map<String, String> info = RedisCommandUtil.getRedisInfo(
                    instance.getServer().getIp(), instance.getPort(), password);
            metrics.put("realtime", info);
        }
        
        return Result.success(metrics);
    }

    /**
     * 获取集群监控概览
     */
    public Result<Map<String, Object>> getClusterOverview(Long clusterId) {
        Optional<RedisCluster> opt = clusterRepository.findById(clusterId);
        if (!opt.isPresent()) {
            return Result.error("集群不存在");
        }

        RedisCluster cluster = opt.get();
        List<RedisInstance> instances = cluster.getInstances();
        
        if (instances.isEmpty()) {
            return Result.error("集群没有实例");
        }

        Map<String, Object> overview = new HashMap<>();
        
        // 统计信息
        int totalNodes = instances.size();
        int runningNodes = 0;
        long totalMemory = 0;
        long usedMemory = 0;
        int totalClients = 0;
        long totalOps = 0;
        int masterCount = 0;
        int slaveCount = 0;
        
        List<Map<String, Object>> nodes = new ArrayList<>();
        
        for (RedisInstance instance : instances) {
            Map<String, Object> node = new HashMap<>();
            node.put("id", instance.getId());
            node.put("address", instance.getAddress());
            node.put("port", instance.getPort());
            node.put("status", instance.getStatus());
            node.put("nodeType", instance.getNodeType());
            
            if (instance.getStatus() == 1) {
                runningNodes++;
            }
            
            if (instance.getMaxMemory() != null) {
                totalMemory += instance.getMaxMemory();
            }
            if (instance.getUsedMemory() != null) {
                usedMemory += instance.getUsedMemory();
            }
            if (instance.getConnectedClients() != null) {
                totalClients += instance.getConnectedClients();
            }
            if (instance.getInstantaneousOpsPerSec() != null) {
                totalOps += instance.getInstantaneousOpsPerSec();
            }
            
            if ("master".equals(instance.getNodeType())) {
                masterCount++;
            } else if ("slave".equals(instance.getNodeType())) {
                slaveCount++;
            }
            
            nodes.add(node);
        }
        
        overview.put("clusterName", cluster.getName());
        overview.put("totalNodes", totalNodes);
        overview.put("runningNodes", runningNodes);
        overview.put("stoppedNodes", totalNodes - runningNodes);
        overview.put("healthRate", String.format("%.1f%%", (double) runningNodes / totalNodes * 100));
        
        // 内存统计
        Map<String, Object> memoryStats = new HashMap<>();
        memoryStats.put("total", totalMemory);
        memoryStats.put("used", usedMemory);
        memoryStats.put("free", totalMemory - usedMemory);
        if (totalMemory > 0) {
            memoryStats.put("usageRate", String.format("%.2f%%", (double) usedMemory / totalMemory * 100));
        }
        overview.put("memory", memoryStats);
        
        // 连接统计
        overview.put("totalClients", totalClients);
        overview.put("opsPerSec", totalOps);
        overview.put("masterCount", masterCount);
        overview.put("slaveCount", slaveCount);
        
        overview.put("nodes", nodes);
        
        return Result.success(overview);
    }

    /**
     * 获取所有集群监控状态（支持分页和服务器组筛选）
     * 使用数据库缓存的监控数据，避免实时SSH查询，提高性能
     * 
     * @param groupId 服务器组ID（可选）
     * @param page 页码（从1开始）
     * @param size 每页大小
     * @param defaultGroup 是否只查询默认服务器组的集群
     */
    public Result<Map<String, Object>> getAllClustersStatus(Long groupId, int page, int size, boolean defaultGroup) {
        // 确定要查询的服务器组
        Long targetGroupId = groupId;
        
        if (defaultGroup && targetGroupId == null) {
            // 查询默认服务器组
            Optional<ServerGroup> defaultGroupOpt = groupRepository.findByIsDefault(1);
            if (defaultGroupOpt.isPresent()) {
                targetGroupId = defaultGroupOpt.get().getId();
            }
        }
        
        // 获取集群列表
        List<RedisCluster> allClusters;
        if (targetGroupId != null) {
            // 查询指定服务器组的集群
            allClusters = clusterRepository.findByServerGroupId(targetGroupId);
        } else {
            // 查询所有集群
            allClusters = clusterRepository.findAllWithNodes();
        }
        
        // 计算分页
        int total = allClusters.size();
        int totalPages = (int) Math.ceil((double) total / size);
        int start = (page - 1) * size;
        int end = Math.min(start + size, total);
        
        // 截取当前页数据
        List<RedisCluster> pageClusters = start < total ? 
                allClusters.subList(start, end) : new ArrayList<>();
        
        List<Map<String, Object>> result = new ArrayList<>();
        
        for (RedisCluster cluster : pageClusters) {
            Map<String, Object> status = new HashMap<>();
            status.put("clusterId", cluster.getId());
            status.put("clusterName", cluster.getName());
            status.put("status", cluster.getStatus());
            status.put("clusterType", cluster.getClusterType()); // 0-系统创建, 1-外部导入
            status.put("serverGroupId", cluster.getServerGroup() != null ? cluster.getServerGroup().getId() : null);
            status.put("serverGroupName", cluster.getServerGroup() != null ? cluster.getServerGroup().getName() : null);
            
            List<ClusterNode> nodes = cluster.getNodes();
            int runningCount = 0;
            int totalNodes = nodes.size();
            
            // 统计所有节点的运行状态
            for (ClusterNode node : nodes) {
                if (node.getStatus() == 1) {
                    runningCount++;
                }
            }
            
            // 使用数据库缓存的监控数据（避免实时SSH查询）
            long totalUsedMemory = 0;      // 主节点使用内存总和 (MB)
            long totalMaxMemory = 0;       // 主节点内存限制总和 (MB)
            int totalClients = 0;          // 主节点连接数总和
            int masterCount = 0;           // 主节点数量
            
            // 从缓存数据累加主节点监控指标
            for (ClusterNode node : nodes) {
                if (node.getNodeRole() != null && node.getNodeRole() == 0) {
                    masterCount++;
                    // 使用数据库缓存的数据，避免实时SSH查询
                    Long usedMem = node.getUsedMemory();
                    Long maxMem = node.getMaxMemory();
                    Integer clients = node.getConnectedClients();
                    
                    if (usedMem != null) {
                        totalUsedMemory += usedMem;
                    }
                    if (maxMem != null) {
                        totalMaxMemory += maxMem;
                    }
                    if (clients != null) {
                        totalClients += clients;
                    }
                }
            }
            
            // 计算内存使用占比
            double memoryUsageRate = 0;
            if (totalMaxMemory > 0) {
                memoryUsageRate = (double) totalUsedMemory / totalMaxMemory * 100;
            }
            
            status.put("totalNodes", totalNodes);
            status.put("runningNodes", runningCount);
            status.put("masterCount", masterCount);              // 主节点数
            status.put("usedMemory", totalUsedMemory);           // 主节点使用内存 (MB)
            status.put("maxMemory", totalMaxMemory);             // 主节点内存限制 (MB)
            status.put("memoryUsageRate", Math.round(memoryUsageRate)); // 内存使用占比 (%)
            status.put("connectedClients", totalClients);        // 主节点连接数
            
            result.add(status);
        }
        
        // 构建分页响应
        Map<String, Object> pageResult = new HashMap<>();
        pageResult.put("content", result);
        pageResult.put("totalElements", total);
        pageResult.put("totalPages", totalPages);
        pageResult.put("currentPage", page);
        pageResult.put("pageSize", size);
        pageResult.put("defaultGroupId", targetGroupId); // 返回当前查询的服务器组ID
        
        return Result.success(pageResult);
    }
    
    /**
     * 实时获取单个节点监控数据
     * 返回: usedMemory(MB), maxMemory(MB), connectedClients
     */
    private Map<String, Object> fetchNodeMetricsRealtime(RedisCluster cluster, ClusterNode node) {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("usedMemory", 0L);
        metrics.put("maxMemory", 0L);
        metrics.put("connectedClients", 0);
        
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
                    logger.warn("节点 {}:{} Redis连接失败，返回: {}", host, port, pingOutput);
                    return metrics;
                }
                
                // 获取内存使用 (used_memory 字节转MB)
                String usedMemCmd = String.format("redis-cli -h %s -p %d%s info memory 2>&1 | grep '^used_memory:'", host, port, authCmd);
                SSHClient.SSHResult usedMemResult = ssh.executeCommand(usedMemCmd);
                String usedMemOutput = usedMemResult.getStdout().trim();
                if (usedMemOutput.contains(":")) {
                    try {
                        String valueStr = usedMemOutput.split(":")[1].trim();
                        long usedMemoryBytes = Long.parseLong(valueStr);
                        long usedMemoryMB = usedMemoryBytes / 1024 / 1024;
                        metrics.put("usedMemory", usedMemoryMB);
                    } catch (Exception e) {
                        logger.warn("节点 {}:{} 解析used_memory失败: {}", host, port, e.getMessage());
                    }
                }
                
                // 获取最大内存限制 (maxmemory 字节转MB)
                String maxMemCmd = String.format("redis-cli -h %s -p %d%s info memory 2>&1 | grep '^maxmemory:'", host, port, authCmd);
                SSHClient.SSHResult maxMemResult = ssh.executeCommand(maxMemCmd);
                String maxMemOutput = maxMemResult.getStdout().trim();
                if (maxMemOutput.contains(":")) {
                    try {
                        String valueStr = maxMemOutput.split(":")[1].trim();
                        long maxMemoryBytes = Long.parseLong(valueStr);
                        long maxMemoryMB = maxMemoryBytes / 1024 / 1024;
                        metrics.put("maxMemory", maxMemoryMB);
                    } catch (Exception e) {
                        logger.warn("节点 {}:{} 解析maxmemory失败: {}", host, port, e.getMessage());
                    }
                }
                
                // 获取连接数
                String clientsCmd = String.format("redis-cli -h %s -p %d%s info clients 2>&1 | grep '^connected_clients:'", host, port, authCmd);
                SSHClient.SSHResult clientsResult = ssh.executeCommand(clientsCmd);
                String clientsOutput = clientsResult.getStdout().trim();
                if (clientsOutput.contains(":")) {
                    try {
                        String valueStr = clientsOutput.split(":")[1].trim();
                        int connectedClients = Integer.parseInt(valueStr);
                        metrics.put("connectedClients", connectedClients);
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
     * 获取所有集群的调试信息
     * 返回每个集群查询详情：命令、返回结果、计算过程
     */
    public Result<List<Map<String, Object>>> getAllClustersDebugInfo() {
        List<RedisCluster> clusters = clusterRepository.findAllWithNodes();
        List<Map<String, Object>> result = new ArrayList<>();
        
        for (RedisCluster cluster : clusters) {
            Map<String, Object> debugInfo = new HashMap<>();
            debugInfo.put("clusterId", cluster.getId());
            debugInfo.put("clusterName", cluster.getName());
            debugInfo.put("clusterStatus", cluster.getStatus());
            
            List<ClusterNode> nodes = cluster.getNodes();
            
            // 获取所有主节点
            List<ClusterNode> masterNodes = new ArrayList<>();
            for (ClusterNode node : nodes) {
                if (node.getNodeRole() != null && node.getNodeRole() == 0) {
                    masterNodes.add(node);
                }
            }
            
            debugInfo.put("totalNodes", nodes.size());
            debugInfo.put("masterNodesCount", masterNodes.size());
            
            // 查询每个主节点的详细信息
            List<Map<String, Object>> nodeDebugInfos = new ArrayList<>();
            long totalUsedMemory = 0;
            long totalMaxMemory = 0;
            int totalClients = 0;
            
            for (ClusterNode masterNode : masterNodes) {
                Map<String, Object> nodeDebugInfo = fetchNodeDebugInfo(cluster, masterNode);
                nodeDebugInfos.add(nodeDebugInfo);
                
                // 累加计算
                totalUsedMemory += ((Number) nodeDebugInfo.get("usedMemoryMB")).longValue();
                totalMaxMemory += ((Number) nodeDebugInfo.get("maxMemoryMB")).longValue();
                totalClients += ((Number) nodeDebugInfo.get("connectedClients")).intValue();
            }
            
            debugInfo.put("nodeDetails", nodeDebugInfos);
            
            // 统计信息
            double memoryUsageRate = totalMaxMemory > 0 ? (double) totalUsedMemory / totalMaxMemory * 100 : 0;
            Map<String, Object> summary = new HashMap<>();
            summary.put("totalUsedMemoryMB", totalUsedMemory);
            summary.put("totalMaxMemoryMB", totalMaxMemory);
            summary.put("totalConnectedClients", totalClients);
            summary.put("memoryUsageRate", String.format("%.2f%%", memoryUsageRate));
            summary.put("calculationFormula", "总内存使用 = 各3个主节点used_memory(字节) / 1024 / 1024");
            summary.put("maxMemoryFormula", "总内存限制 = 各3个主节点maxmemory(字节) / 1024 / 1024");
            summary.put("clientsFormula", "总连接数 = 3个主节点connected_clients相加");
            debugInfo.put("summary", summary);
            
            result.add(debugInfo);
        }
        
        return Result.success(result);
    }
    
    /**
     * 获取单个节点的调试信息
     */
    private Map<String, Object> fetchNodeDebugInfo(RedisCluster cluster, ClusterNode node) {
        Map<String, Object> debugInfo = new HashMap<>();
        debugInfo.put("nodeId", node.getId());
        debugInfo.put("nodeIp", node.getIp());
        debugInfo.put("nodePort", node.getPort());
        debugInfo.put("nodeRole", node.getNodeRole() == 0 ? "master" : "slave");
        debugInfo.put("nodeStatus", node.getStatus());
        
        // 默认值
        debugInfo.put("usedMemoryBytes", 0L);
        debugInfo.put("usedMemoryMB", 0L);
        debugInfo.put("usedMemoryHuman", "0 MB");
        debugInfo.put("maxMemoryBytes", 0L);
        debugInfo.put("maxMemoryMB", 0L);
        debugInfo.put("maxMemoryHuman", "0 MB");
        debugInfo.put("connectedClients", 0);
        debugInfo.put("pingResult", "unknown");
        
        // 获取SSH连接信息
        Server server = getServerForNode(cluster, node);
        if (server == null) {
            debugInfo.put("error", "无法获取SSH连接信息");
            Map<String, Object> sshInfoUnknown = new HashMap<>();
            sshInfoUnknown.put("serverIp", "unknown");
            sshInfoUnknown.put("sshPort", "unknown");
            sshInfoUnknown.put("sshUser", "unknown");
            debugInfo.put("sshInfo", sshInfoUnknown);
            return debugInfo;
        }
        
        Map<String, Object> sshInfo = new HashMap<>();
        sshInfo.put("serverIp", server.getIp());
        sshInfo.put("sshPort", server.getSshPort());
        sshInfo.put("sshUser", server.getSshUser());
        debugInfo.put("sshInfo", sshInfo);
        
        String password = cluster.getPassword();
        String host = node.getIp();
        int port = node.getPort();
        String authCmd = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";
        
        List<Map<String, String>> commands = new ArrayList<>();
        
        try {
            SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(),
                    server.getSshUser(), server.getSshPassword());
            ssh.connect();
            
            try {
                // 1. PING 测试
                String pingCmd = String.format("redis-cli -h %s -p %d%s ping 2>&1", host, port, authCmd);
                SSHClient.SSHResult pingResult = ssh.executeCommand(pingCmd);
                String pingOutput = pingResult.getStdout().trim();
                String pingError = pingResult.getStderr().trim();
                debugInfo.put("pingResult", pingOutput);
                debugInfo.put("pingError", pingError);
                Map<String, String> pingCmdInfo = new HashMap<>();
                pingCmdInfo.put("name", "PING测试");
                pingCmdInfo.put("command", pingCmd);
                pingCmdInfo.put("stdout", pingOutput);
                pingCmdInfo.put("stderr", pingError);
                pingCmdInfo.put("exitCode", String.valueOf(pingResult.getExitCode()));
                commands.add(pingCmdInfo);
                
                // Redis 6.x 使用 -a 参数会输出警告信息，需要检查是否包含 PONG
                if (!pingOutput.toUpperCase().contains("PONG")) {
                    debugInfo.put("error", "Redis连接失败: " + pingOutput);
                    debugInfo.put("commands", commands);
                    return debugInfo;
                }
                
                // 2. 获取 used_memory
                String usedMemCmd = String.format("redis-cli -h %s -p %d%s info memory 2>&1 | grep '^used_memory:'", host, port, authCmd);
                SSHClient.SSHResult usedMemResult = ssh.executeCommand(usedMemCmd);
                String usedMemOutput = usedMemResult.getStdout().trim();
                Map<String, String> usedMemCmdInfo = new HashMap<>();
                usedMemCmdInfo.put("name", "获取used_memory");
                usedMemCmdInfo.put("command", usedMemCmd);
                usedMemCmdInfo.put("stdout", usedMemOutput);
                usedMemCmdInfo.put("stderr", usedMemResult.getStderr().trim());
                usedMemCmdInfo.put("exitCode", String.valueOf(usedMemResult.getExitCode()));
                commands.add(usedMemCmdInfo);
                
                long usedMemoryBytes = 0;
                long usedMemoryMB = 0;
                if (usedMemOutput.contains(":")) {
                    try {
                        String valueStr = usedMemOutput.split(":")[1].trim();
                        usedMemoryBytes = Long.parseLong(valueStr);
                        usedMemoryMB = usedMemoryBytes / 1024 / 1024;
                        debugInfo.put("usedMemoryBytes", usedMemoryBytes);
                        debugInfo.put("usedMemoryMB", usedMemoryMB);
                        debugInfo.put("usedMemoryHuman", formatMemoryHuman(usedMemoryMB));
                        debugInfo.put("usedMemoryCalculation", String.format("%d / 1024 / 1024 = %d MB", usedMemoryBytes, usedMemoryMB));
                    } catch (Exception e) {
                        debugInfo.put("usedMemoryError", "解析失败: " + e.getMessage());
                    }
                }
                
                // 3. 获取 maxmemory
                String maxMemCmd = String.format("redis-cli -h %s -p %d%s info memory 2>&1 | grep '^maxmemory:'", host, port, authCmd);
                SSHClient.SSHResult maxMemResult = ssh.executeCommand(maxMemCmd);
                String maxMemOutput = maxMemResult.getStdout().trim();
                Map<String, String> maxMemCmdInfo = new HashMap<>();
                maxMemCmdInfo.put("name", "获取maxmemory");
                maxMemCmdInfo.put("command", maxMemCmd);
                maxMemCmdInfo.put("stdout", maxMemOutput);
                maxMemCmdInfo.put("stderr", maxMemResult.getStderr().trim());
                maxMemCmdInfo.put("exitCode", String.valueOf(maxMemResult.getExitCode()));
                commands.add(maxMemCmdInfo);
                
                long maxMemoryBytes = 0;
                long maxMemoryMB = 0;
                if (maxMemOutput.contains(":")) {
                    try {
                        String valueStr = maxMemOutput.split(":")[1].trim();
                        maxMemoryBytes = Long.parseLong(valueStr);
                        maxMemoryMB = maxMemoryBytes / 1024 / 1024;
                        debugInfo.put("maxMemoryBytes", maxMemoryBytes);
                        debugInfo.put("maxMemoryMB", maxMemoryMB);
                        debugInfo.put("maxMemoryHuman", formatMemoryHuman(maxMemoryMB));
                        debugInfo.put("maxMemoryCalculation", String.format("%d / 1024 / 1024 = %d MB", maxMemoryBytes, maxMemoryMB));
                    } catch (Exception e) {
                        debugInfo.put("maxMemoryError", "解析失败: " + e.getMessage());
                    }
                }
                
                // 4. 获取 connected_clients
                String clientsCmd = String.format("redis-cli -h %s -p %d%s info clients 2>&1 | grep '^connected_clients:'", host, port, authCmd);
                SSHClient.SSHResult clientsResult = ssh.executeCommand(clientsCmd);
                String clientsOutput = clientsResult.getStdout().trim();
                Map<String, String> clientsCmdInfo = new HashMap<>();
                clientsCmdInfo.put("name", "获取connected_clients");
                clientsCmdInfo.put("command", clientsCmd);
                clientsCmdInfo.put("stdout", clientsOutput);
                clientsCmdInfo.put("stderr", clientsResult.getStderr().trim());
                clientsCmdInfo.put("exitCode", String.valueOf(clientsResult.getExitCode()));
                commands.add(clientsCmdInfo);
                
                if (clientsOutput.contains(":")) {
                    try {
                        String valueStr = clientsOutput.split(":")[1].trim();
                        int connectedClients = Integer.parseInt(valueStr);
                        debugInfo.put("connectedClients", connectedClients);
                    } catch (Exception e) {
                        debugInfo.put("clientsError", "解析失败: " + e.getMessage());
                    }
                }
                
                debugInfo.put("commands", commands);
                
            } finally {
                ssh.disconnect();
            }
        } catch (Exception e) {
            debugInfo.put("error", "SSH执行异常: " + e.getMessage());
            debugInfo.put("exception", e.getClass().getName());
        }
        
        return debugInfo;
    }
    
    /**
     * 格式化内存为人类可读格式
     */
    private String formatMemoryHuman(long mb) {
        if (mb < 1024) {
            return mb + " MB";
        } else {
            return String.format("%.2f GB", mb / 1024.0);
        }
    }

    // ==================== 三级监控新方法 ====================

    /**
     * 获取实时监控数据汇总（三级监控合一）
     * @param clusterId 集群ID，如果为null或-1，获取所有集群数据
     * @return 包含physical、instances、clusterLevel三个key的Map
     */
    public Result<Map<String, Object>> getRealtimeMonitoring(Long clusterId) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 获取物理机监控数据
            Result<List<Map<String, Object>>> physicalResult = getPhysicalHostsMonitoring(clusterId);
            result.put("physical", physicalResult.isSuccess() ? physicalResult.getData() : new ArrayList<>());
            
            // 获取实例监控数据
            Result<List<Map<String, Object>>> instancesResult = getInstancesMonitoring(clusterId);
            result.put("instances", instancesResult.isSuccess() ? instancesResult.getData() : new ArrayList<>());
            
            // 获取集群级监控数据
            Result<List<Map<String, Object>>> clusterLevelResult = getClusterLevelMonitoring(clusterId);
            result.put("clusterLevel", clusterLevelResult.isSuccess() ? clusterLevelResult.getData() : new ArrayList<>());
            
            return Result.success(result);
        } catch (Exception e) {
            logger.error("获取实时监控数据失败: clusterId={}", clusterId, e);
            return Result.error("获取实时监控数据失败: " + e.getMessage());
        }
    }

    /**
     * 获取物理机监控数据（根据服务器组ID）
     * @param groupId 服务器组ID
     * @return 物理机监控数据列表
     */
    public Result<List<Map<String, Object>>> getPhysicalHostsMonitoring(Long groupId) {
        List<Map<String, Object>> result = new ArrayList<>();
        
        try {
            // 获取服务器组
            Optional<ServerGroup> groupOpt = groupRepository.findById(groupId);
            if (!groupOpt.isPresent()) {
                return Result.error("服务器组不存在");
            }
            
            ServerGroup group = groupOpt.get();
            List<Server> servers = serverRepository.findByGroupId(groupId);
            
            if (servers.isEmpty()) {
                return Result.success(result);
            }
            
            // 遍历服务器组中的每台服务器
            for (Server server : servers) {
                Map<String, Object> hostInfo = fetchPhysicalHostMetrics(server, group);
                result.add(hostInfo);
            }
            
            return Result.success(result);
        } catch (Exception e) {
            logger.error("获取物理机监控数据失败: groupId={}", groupId, e);
            return Result.error("获取物理机监控数据失败: " + e.getMessage());
        }
    }

    /**
     * 获取单个物理机的监控指标
     */
    private Map<String, Object> fetchPhysicalHostMetrics(Server server, ServerGroup group) {
        Map<String, Object> metrics = new HashMap<>();
        String ip = server.getIp();
        
        metrics.put("ip", ip);
        metrics.put("memoryTotal", 0.0);
        metrics.put("memoryUsed", 0.0);
        metrics.put("memoryFree", 0.0);
        metrics.put("memoryFreePercent", 0.0);
        metrics.put("cpuUsage", 0.0);
        metrics.put("diskIOUsage", 0.0);
        metrics.put("networkUsage", 0.0);
        metrics.put("swapUsage", 0.0);
        metrics.put("instanceCount", 0);
        metrics.put("instances", new ArrayList<Map<String, Object>>());
        metrics.put("status", "unknown");
        metrics.put("connected", false);
        
        try {
            SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(),
                    server.getSshUser(), server.getSshPassword());
            ssh.connect();
            metrics.put("connected", true);
            
            try {
                // 获取内存信息 (free -m)
                SSHClient.SSHResult memResult = ssh.executeCommand("free -m | grep -E '(Mem|Swap)'");
                parseMemoryInfo(metrics, memResult.getStdout());
                
                // 获取CPU使用率 (top)
                SSHClient.SSHResult cpuResult = ssh.executeCommand("top -bn1 | grep 'Cpu(s)' | awk '{print $2}' | cut -d'%' -f1");
                parseCpuUsage(metrics, cpuResult.getStdout());
                
                // 获取磁盘IO使用率 (iostat，如果可用)
                SSHClient.SSHResult iostatResult = ssh.executeCommand("command -v iostat >/dev/null 2>&1 && iostat -x 1 1 | tail -n +4 | head -1 | awk '{print 100-$NF}' || echo 0");
                parseDiskIOUsage(metrics, iostatResult.getStdout());
                
                // 获取网络带宽使用率（通过两次采样计算实时速率）
                SSHClient.SSHResult netResult = ssh.executeCommand(
                    "iface=$(cat /proc/net/dev | grep -E '(eth|ens)' | head -1 | awk -F: '{print $1}'); " +
                    "read1=$(cat /proc/net/dev | grep \"$iface:\" | awk '{print $2+$10}'); " +
                    "sleep 1; " +
                    "read2=$(cat /proc/net/dev | grep \"$iface:\" | awk '{print $2+$10}'); " +
                    "echo \"scale=2; ($read2 - $read1) / 1024 / 1024\" | bc"
                );
                parseNetworkBandwidth(metrics, netResult.getStdout(), server.getIp());
                
                // 获取Swap使用率
                SSHClient.SSHResult swapResult = ssh.executeCommand("free -m | grep Swap | awk '{print ($3/$2)*100}' 2>/dev/null || echo 0");
                parseSwapUsage(metrics, swapResult.getStdout());
                
                // 获取Redis实例统计
                List<Map<String, Object>> redisInstances = fetchHostRedisInstances(ssh, server);
                metrics.put("instanceCount", redisInstances.size());
                metrics.put("instances", redisInstances);
                
                metrics.put("status", "ok");
                
            } finally {
                ssh.disconnect();
            }
        } catch (Exception e) {
            logger.warn("获取物理机 {} 监控数据失败: {}", ip, e.getMessage());
            metrics.put("status", "error");
            metrics.put("errorMessage", e.getMessage());
        }
        
        return metrics;
    }

    /**
     * 解析内存信息
     */
    private void parseMemoryInfo(Map<String, Object> metrics, String output) {
        try {
            String[] lines = output.split("\n");
            for (String line : lines) {
                if (line.contains("Mem:")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 4) {
                        long total = Long.parseLong(parts[1]);
                        long used = Long.parseLong(parts[2]);
                        long free = Long.parseLong(parts[3]);
                        double freePercent = total > 0 ? (double) free / total * 100 : 0;
                        
                        metrics.put("memoryTotal", total);
                        metrics.put("memoryUsed", used);
                        metrics.put("memoryFree", free);
                        metrics.put("memoryFreePercent", Math.round(freePercent * 100) / 100.0);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("解析内存信息失败: {}", e.getMessage());
        }
    }

    /**
     * 解析CPU使用率
     */
    private void parseCpuUsage(Map<String, Object> metrics, String output) {
        try {
            double cpuUsage = Double.parseDouble(output.trim());
            metrics.put("cpuUsage", Math.round(cpuUsage * 100) / 100.0);
        } catch (Exception e) {
            logger.warn("解析CPU使用率失败: {}", e.getMessage());
        }
    }

    /**
     * 解析磁盘IO使用率
     */
    private void parseDiskIOUsage(Map<String, Object> metrics, String output) {
        try {
            double diskIO = Double.parseDouble(output.trim());
            metrics.put("diskIOUsage", Math.round(diskIO * 100) / 100.0);
        } catch (Exception e) {
            logger.warn("解析磁盘IO使用率失败: {}", e.getMessage());
        }
    }

    /**
     * 解析网络使用率
     */
    private void parseNetworkUsage(Map<String, Object> metrics, String output) {
        try {
            double netUsage = Double.parseDouble(output.trim());
            metrics.put("networkUsage", Math.round(netUsage * 100) / 100.0);
        } catch (Exception e) {
            logger.warn("解析网络使用率失败: {}", e.getMessage());
        }
    }

    /**
     * 解析网络带宽（MB/s）
     */
    private void parseNetworkBandwidth(Map<String, Object> metrics, String output, String ip) {
        try {
            double bandwidthMB = Double.parseDouble(output.trim());
            // 限制在合理范围内，避免异常值
            if (bandwidthMB < 0) bandwidthMB = 0;
            if (bandwidthMB > 10000) bandwidthMB = 0; // 超过10GB/s视为异常
            metrics.put("networkBandwidthMB", Math.round(bandwidthMB * 100) / 100.0);
            logger.debug("服务器 {} 网络带宽: {} MB/s", ip, bandwidthMB);
        } catch (Exception e) {
            logger.warn("解析网络带宽失败: {}", e.getMessage());
            metrics.put("networkBandwidthMB", 0.0);
        }
    }

    /**
     * 解析Swap使用率
     */
    private void parseSwapUsage(Map<String, Object> metrics, String output) {
        try {
            double swapUsage = Double.parseDouble(output.trim());
            metrics.put("swapUsage", Math.round(swapUsage * 100) / 100.0);
        } catch (Exception e) {
            logger.warn("解析Swap使用率失败: {}", e.getMessage());
        }
    }

    /**
     * 获取主机上的Redis实例列表
     * 解析格式：redis-server IP:PORT [cluster] 或 /usr/local/bin/redis-server IP:PORT [cluster]
     * 
     * @param ssh 已连接的SSHClient
     * @param server 服务器信息
     * @return Redis实例列表，包含port、role、pid、memory等信息
     */
    private List<Map<String, Object>> fetchHostRedisInstances(SSHClient ssh, Server server) {
        List<Map<String, Object>> instances = new ArrayList<>();
        
        try {
            // 获取运行中的Redis实例 - 解析多种格式
            // 支持：redis-server IP:PORT [cluster] 或 /usr/local/bin/redis-server IP:PORT [cluster]
            SSHClient.SSHResult result = ssh.executeCommand(
                "ps aux | grep '[r]edis-server' | grep -v grep"
            );
            
            String output = result.getStdout().trim();
            if (output.isEmpty()) {
                return instances;
            }
            
            String[] lines = output.split("\n");
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                
                // 解析ps aux输出格式
                // root 5880 0.2 0.0 144328 3900 ? Ssl Feb09 56:06 redis-server 130.1.14.125:6002 [cluster]
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 11) continue;
                
                try {
                    String pid = parts[1];
                    String cpuUsage = parts[2];
                    String memUsage = parts[3];
                    String vsz = parts[4];
                    String rss = parts[5];
                    
                    // 查找包含IP:端口的字段
                    String bindAddress = null;
                    int port = 0;
                    for (int i = 10; i < parts.length; i++) {
                        String part = parts[i];
                        if (part.matches("\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+")) {
                            String[] addrParts = part.split(":");
                            bindAddress = addrParts[0];
                            port = Integer.parseInt(addrParts[1]);
                            break;
                        }
                    }
                    
                    if (port == 0) continue;
                    
                    Map<String, Object> instance = new HashMap<>();
                    instance.put("port", port);
                    instance.put("pid", pid);
                    instance.put("cpuPercent", cpuUsage);
                    instance.put("memPercent", memUsage);
                    instance.put("vsz", vsz);
                    instance.put("rss", rss);
                    
                    // 判断是否为集群模式
                    boolean isClusterMode = line.contains("[cluster]");
                    instance.put("clusterMode", isClusterMode);
                    
                    instances.add(instance);
                    
                } catch (Exception e) {
                    logger.debug("解析Redis实例行失败: {}", line);
                }
            }
        } catch (Exception e) {
            logger.warn("获取主机 {} 上的Redis实例失败: {}", server.getIp(), e.getMessage());
        }
        
        return instances;
    }

    /**
     * 获取实例级监控数据（指定集群）
     * @param clusterId 集群ID（必填）
     * @return 实例监控数据列表
     */
    public Result<List<Map<String, Object>>> getInstancesMonitoring(Long clusterId) {
        List<Map<String, Object>> result = new ArrayList<>();
        
        try {
            if (clusterId == null) {
                return Result.error("必须指定集群ID");
            }
            
            Optional<RedisCluster> opt = clusterRepository.findById(clusterId);
            if (!opt.isPresent()) {
                return Result.error("集群不存在");
            }
            
            RedisCluster cluster = opt.get();
            List<ClusterNode> nodes = cluster.getNodes();
            
            for (ClusterNode node : nodes) {
                Map<String, Object> instanceMetrics = fetchInstanceMetrics(node, cluster);
                result.add(instanceMetrics);
            }
            
            return Result.success(result);
        } catch (Exception e) {
            logger.error("获取实例监控数据失败: clusterId={}", clusterId, e);
            return Result.error("获取实例监控数据失败: " + e.getMessage());
        }
    }

    /**
     * 获取单个实例的详细监控指标
     */
    private Map<String, Object> fetchInstanceMetrics(ClusterNode node, RedisCluster cluster) {
        Map<String, Object> metrics = new HashMap<>();
        
        metrics.put("clusterName", cluster.getName());
        metrics.put("address", node.getIp() + ":" + node.getPort());
        metrics.put("role", node.getNodeRole() != null && node.getNodeRole() == 0 ? "master" : "slave");
        metrics.put("memoryUsed", 0L);
        metrics.put("memoryMax", 0L);
        metrics.put("memoryUsagePercent", 0.0);
        metrics.put("fragmentationRatio", 0.0);
        metrics.put("connectedClients", 0);
        metrics.put("qps", 0);
        metrics.put("latencyP99", 0);
        metrics.put("replicationDelay", 0);
        metrics.put("slowQueries", 0);
        metrics.put("aofRewriteInProgress", false);
        metrics.put("rdbLastSaveDuration", 0);
        metrics.put("status", node.getStatus() == 1 ? "running" : "stopped");
        
        Server server = getServerForNode(cluster, node);
        if (server == null) {
            metrics.put("status", "error");
            metrics.put("errorMessage", "无法获取SSH连接信息");
            return metrics;
        }
        
        String password = cluster.getPassword();
        String host = node.getIp();
        int port = node.getPort();
        String authCmd = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";
        
        try {
            SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(),
                    server.getSshUser(), server.getSshPassword());
            ssh.connect();
            
            try {
                // 首先检查Redis是否可连接
                String pingCmd = String.format("redis-cli -h %s -p %d%s ping 2>&1", host, port, authCmd);
                SSHClient.SSHResult pingResult = ssh.executeCommand(pingCmd);
                if (!pingResult.getStdout().trim().toUpperCase().contains("PONG")) {
                    metrics.put("status", "unreachable");
                    return metrics;
                }
                
                // 获取内存信息 (info memory)
                String memCmd = String.format("redis-cli -h %s -p %d%s info memory 2>&1", host, port, authCmd);
                SSHClient.SSHResult memResult = ssh.executeCommand(memCmd);
                parseInstanceMemoryInfo(metrics, memResult.getStdout());
                
                // 获取统计信息 (info stats)
                String statsCmd = String.format("redis-cli -h %s -p %d%s info stats 2>&1", host, port, authCmd);
                SSHClient.SSHResult statsResult = ssh.executeCommand(statsCmd);
                parseInstanceStatsInfo(metrics, statsResult.getStdout());
                
                // 获取客户端信息 (info clients)
                String clientsCmd = String.format("redis-cli -h %s -p %d%s info clients 2>&1", host, port, authCmd);
                SSHClient.SSHResult clientsResult = ssh.executeCommand(clientsCmd);
                parseInstanceClientsInfo(metrics, clientsResult.getStdout());
                
                // 获取复制信息 (info replication)
                String replCmd = String.format("redis-cli -h %s -p %d%s info replication 2>&1", host, port, authCmd);
                SSHClient.SSHResult replResult = ssh.executeCommand(replCmd);
                parseInstanceReplicationInfo(metrics, replResult.getStdout());
                
                // 获取持久化信息 (info persistence)
                String persistCmd = String.format("redis-cli -h %s -p %d%s info persistence 2>&1", host, port, authCmd);
                SSHClient.SSHResult persistResult = ssh.executeCommand(persistCmd);
                parseInstancePersistenceInfo(metrics, persistResult.getStdout());
                
                // 获取P99延迟 (使用redis-cli --latency，简化处理)
                String latencyCmd = String.format("redis-cli -h %s -p %d%s --latency 1 2>&1 | tail -1 | awk '{print $2}'", 
                        host, port, authCmd);
                SSHClient.SSHResult latencyResult = ssh.executeCommand(latencyCmd);
                parseLatencyInfo(metrics, latencyResult.getStdout());
                
                // 获取慢查询数量
                String slowCmd = String.format("redis-cli -h %s -p %d%s slowlog len 2>&1", host, port, authCmd);
                SSHClient.SSHResult slowResult = ssh.executeCommand(slowCmd);
                parseSlowQueriesInfo(metrics, slowResult.getStdout());
                
                metrics.put("status", "running");
                metrics.put("lastMonitorTime", LocalDateTime.now().toString());
                
            } finally {
                ssh.disconnect();
            }
        } catch (Exception e) {
            logger.warn("获取实例 {}:{} 监控数据失败: {}", host, port, e.getMessage());
            metrics.put("status", "error");
            metrics.put("errorMessage", e.getMessage());
        }
        
        return metrics;
    }

    /**
     * 解析实例内存信息
     */
    private void parseInstanceMemoryInfo(Map<String, Object> metrics, String output) {
        Map<String, String> infoMap = parseInfoOutput(output);
        
        try {
            long usedMemory = Long.parseLong(infoMap.getOrDefault("used_memory", "0"));
            long maxMemory = Long.parseLong(infoMap.getOrDefault("maxmemory", "0"));
            double memFragmentationRatio = Double.parseDouble(infoMap.getOrDefault("mem_fragmentation_ratio", "0"));
            
            long usedMB = usedMemory / 1024 / 1024;
            long maxMB = maxMemory / 1024 / 1024;
            double usagePercent = maxMB > 0 ? (double) usedMB / maxMB * 100 : 0;
            
            metrics.put("memoryUsed", usedMB);
            metrics.put("memoryMax", maxMB);
            metrics.put("memoryUsagePercent", Math.round(usagePercent * 100) / 100.0);
            metrics.put("fragmentationRatio", Math.round(memFragmentationRatio * 100) / 100.0);
        } catch (Exception e) {
            logger.warn("解析实例内存信息失败: {}", e.getMessage());
        }
    }

    /**
     * 解析实例统计信息
     */
    private void parseInstanceStatsInfo(Map<String, Object> metrics, String output) {
        Map<String, String> infoMap = parseInfoOutput(output);
        
        try {
            int qps = Integer.parseInt(infoMap.getOrDefault("instantaneous_ops_per_sec", "0"));
            metrics.put("qps", qps);
        } catch (Exception e) {
            logger.warn("解析实例统计信息失败: {}", e.getMessage());
        }
    }

    /**
     * 解析实例客户端信息
     */
    private void parseInstanceClientsInfo(Map<String, Object> metrics, String output) {
        Map<String, String> infoMap = parseInfoOutput(output);
        
        try {
            int connectedClients = Integer.parseInt(infoMap.getOrDefault("connected_clients", "0"));
            metrics.put("connectedClients", connectedClients);
        } catch (Exception e) {
            logger.warn("解析实例客户端信息失败: {}", e.getMessage());
        }
    }

    /**
     * 解析实例复制信息
     */
    private void parseInstanceReplicationInfo(Map<String, Object> metrics, String output) {
        Map<String, String> infoMap = parseInfoOutput(output);
        
        try {
            // 如果是从节点，获取复制延迟
            String role = infoMap.get("role");
            if ("slave".equals(role)) {
                long replDelay = Long.parseLong(infoMap.getOrDefault("master_last_io_seconds_ago", "0"));
                metrics.put("replicationDelay", replDelay);
            }
        } catch (Exception e) {
            logger.warn("解析实例复制信息失败: {}", e.getMessage());
        }
    }

    /**
     * 解析实例持久化信息
     */
    private void parseInstancePersistenceInfo(Map<String, Object> metrics, String output) {
        Map<String, String> infoMap = parseInfoOutput(output);
        
        try {
            String aofRewrite = infoMap.getOrDefault("aof_rewrite_in_progress", "0");
            metrics.put("aofRewriteInProgress", "1".equals(aofRewrite));
            
            // 获取RDB上次保存耗时（秒）
            String rdbSaveTime = infoMap.getOrDefault("rdb_last_save_time_sec", "0");
            try {
                long saveDuration = Long.parseLong(rdbSaveTime);
                metrics.put("rdbLastSaveDuration", saveDuration);
            } catch (NumberFormatException e) {
                metrics.put("rdbLastSaveDuration", 0);
            }
        } catch (Exception e) {
            logger.warn("解析实例持久化信息失败: {}", e.getMessage());
        }
    }

    /**
     * 解析延迟信息
     */
    private void parseLatencyInfo(Map<String, Object> metrics, String output) {
        try {
            double latency = Double.parseDouble(output.trim());
            metrics.put("latencyP99", Math.round(latency * 100) / 100.0);
        } catch (Exception e) {
            metrics.put("latencyP99", 0);
        }
    }

    /**
     * 解析慢查询数量
     */
    private void parseSlowQueriesInfo(Map<String, Object> metrics, String output) {
        try {
            int slowCount = Integer.parseInt(output.trim());
            metrics.put("slowQueries", slowCount);
        } catch (Exception e) {
            metrics.put("slowQueries", 0);
        }
    }

    /**
     * 解析Redis INFO命令输出为Map
     */
    private Map<String, String> parseInfoOutput(String output) {
        Map<String, String> result = new HashMap<>();
        String[] lines = output.split("\n");
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                result.put(key, value);
            }
        }
        
        return result;
    }

    /**
     * 获取集群级监控数据（指定集群）
     * @param clusterId 集群ID（必填）
     * @return 集群级监控数据列表
     */
    public Result<List<Map<String, Object>>> getClusterLevelMonitoring(Long clusterId) {
        List<Map<String, Object>> result = new ArrayList<>();
        
        try {
            if (clusterId == null) {
                return Result.error("必须指定集群ID");
            }
            
            Optional<RedisCluster> opt = clusterRepository.findById(clusterId);
            if (!opt.isPresent()) {
                return Result.error("集群不存在");
            }
            
            RedisCluster cluster = opt.get();
            Map<String, Object> clusterMetrics = fetchClusterLevelMetrics(cluster);
            result.add(clusterMetrics);
            
            return Result.success(result);
        } catch (Exception e) {
            logger.error("获取集群级监控数据失败: clusterId={}", clusterId, e);
            return Result.error("获取集群级监控数据失败: " + e.getMessage());
        }
    }

    /**
     * 获取单个集群的监控指标
     */
    private Map<String, Object> fetchClusterLevelMetrics(RedisCluster cluster) {
        Map<String, Object> metrics = new HashMap<>();
        
        metrics.put("clusterId", cluster.getId());
        metrics.put("clusterName", cluster.getName());
        metrics.put("clusterState", "unknown");
        metrics.put("slotsAssigned", 0);
        metrics.put("slotsFail", 0);
        metrics.put("masterCount", 0);
        metrics.put("slaveCount", 0);
        metrics.put("totalMemoryUsed", 0L);
        metrics.put("totalQps", 0);
        metrics.put("replicationHealth", "unknown");
        metrics.put("nodes", new ArrayList<Map<String, Object>>());
        
        List<ClusterNode> nodes = cluster.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            return metrics;
        }
        
        // 统计主从节点数
        int masterCount = 0;
        int slaveCount = 0;
        List<Map<String, Object>> nodeDetails = new ArrayList<>();
        
        for (ClusterNode node : nodes) {
            if (node.getNodeRole() != null) {
                if (node.getNodeRole() == 0) {
                    masterCount++;
                } else {
                    slaveCount++;
                }
            }
        }
        
        metrics.put("masterCount", masterCount);
        metrics.put("slaveCount", slaveCount);
        metrics.put("totalNodes", nodes.size());
        
        // 计算正在运行的节点数和健康度
        int runningNodes = 0;
        for (ClusterNode node : nodes) {
            if (node.getStatus() != null && node.getStatus() == 1) {
                runningNodes++;
            }
        }
        metrics.put("runningNodes", runningNodes);
        double healthRate = nodes.size() > 0 ? (double) runningNodes / nodes.size() * 100 : 0;
        metrics.put("healthRate", healthRate);
        
        // 获取第一个主节点的服务器信息用于执行cluster info
        ClusterNode firstMaster = null;
        for (ClusterNode node : nodes) {
            if (node.getNodeRole() != null && node.getNodeRole() == 0) {
                firstMaster = node;
                break;
            }
        }
        
        if (firstMaster == null) {
            return metrics;
        }
        
        Server server = getServerForNode(cluster, firstMaster);
        if (server == null) {
            return metrics;
        }
        
        String password = cluster.getPassword();
        String host = firstMaster.getIp();
        int port = firstMaster.getPort();
        String authCmd = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";
        
        long totalMemoryUsed = 0;
        int totalQps = 0;
        boolean hasReplicationDelay = false;
        boolean hasReplicationError = false;
        
        try {
            SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(),
                    server.getSshUser(), server.getSshPassword());
            ssh.connect();
            
            try {
                // 获取集群信息
                String clusterInfoCmd = String.format("redis-cli -h %s -p %d%s cluster info 2>&1", host, port, authCmd);
                SSHClient.SSHResult clusterInfoResult = ssh.executeCommand(clusterInfoCmd);
                parseClusterInfoMetrics(metrics, clusterInfoResult.getStdout());
                
                // 获取每个节点的详细信息
                for (ClusterNode node : nodes) {
                    Map<String, Object> nodeDetail = fetchClusterNodeDetail(ssh, node, cluster);
                    nodeDetails.add(nodeDetail);
                    
                    // 累加总内存使用
                    Object memUsed = nodeDetail.get("memoryUsed");
                    if (memUsed instanceof Number) {
                        totalMemoryUsed += ((Number) memUsed).longValue();
                    }
                    
                    // 累加QPS
                    Object qps = nodeDetail.get("qps");
                    if (qps instanceof Number) {
                        totalQps += ((Number) qps).intValue();
                    }
                    
                    // 检查复制状态
                    String replHealth = (String) nodeDetail.get("replicationHealth");
                    if ("延迟".equals(replHealth)) {
                        hasReplicationDelay = true;
                    } else if ("断开".equals(replHealth)) {
                        hasReplicationError = true;
                    }
                }
                
                metrics.put("totalMemoryUsed", totalMemoryUsed);
                metrics.put("totalQps", totalQps);
                metrics.put("nodes", nodeDetails);
                
                // 计算复制健康度
                if (hasReplicationError) {
                    metrics.put("replicationHealth", "断开");
                } else if (hasReplicationDelay) {
                    metrics.put("replicationHealth", "延迟");
                } else {
                    metrics.put("replicationHealth", "正常");
                }
                
            } finally {
                ssh.disconnect();
            }
        } catch (Exception e) {
            logger.warn("获取集群 {} 监控数据失败: {}", cluster.getName(), e.getMessage());
        }
        
        return metrics;
    }

    /**
     * 解析集群信息
     */
    private void parseClusterInfoMetrics(Map<String, Object> metrics, String output) {
        Map<String, String> infoMap = parseInfoOutput(output);
        
        String clusterState = infoMap.getOrDefault("cluster_state", "unknown");
        metrics.put("clusterState", clusterState);
        
        try {
            int slotsAssigned = Integer.parseInt(infoMap.getOrDefault("cluster_slots_assigned", "0"));
            int slotsFail = Integer.parseInt(infoMap.getOrDefault("cluster_slots_fail", "0"));
            metrics.put("slotsAssigned", slotsAssigned);
            metrics.put("slotsFail", slotsFail);
        } catch (Exception e) {
            logger.warn("解析集群槽位信息失败: {}", e.getMessage());
        }
    }

    /**
     * 获取集群节点详情
     */
    private Map<String, Object> fetchClusterNodeDetail(SSHClient ssh, ClusterNode node, RedisCluster cluster) {
        Map<String, Object> detail = new HashMap<>();
        
        detail.put("nodeId", node.getNodeId());
        detail.put("address", node.getIp() + ":" + node.getPort());
        detail.put("role", node.getNodeRole() != null && node.getNodeRole() == 0 ? "master" : "slave");
        detail.put("status", node.getStatus() == 1 ? "online" : "offline");
        detail.put("memoryUsed", 0L);
        detail.put("qps", 0);
        detail.put("replicationHealth", "unknown");
        
        String password = cluster.getPassword();
        String host = node.getIp();
        int port = node.getPort();
        String authCmd = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";
        
        try {
            // 获取内存使用
            String memCmd = String.format("redis-cli -h %s -p %d%s info memory 2>&1 | grep '^used_memory:' | cut -d':' -f2", 
                    host, port, authCmd);
            SSHClient.SSHResult memResult = ssh.executeCommand(memCmd);
            String memOutput = memResult.getStdout().trim();
            if (!memOutput.isEmpty()) {
                long usedMemoryBytes = Long.parseLong(memOutput);
                detail.put("memoryUsed", usedMemoryBytes / 1024 / 1024);
            }
            
            // 获取QPS
            String qpsCmd = String.format("redis-cli -h %s -p %d%s info stats 2>&1 | grep '^instantaneous_ops_per_sec:' | cut -d':' -f2", 
                    host, port, authCmd);
            SSHClient.SSHResult qpsResult = ssh.executeCommand(qpsCmd);
            String qpsOutput = qpsResult.getStdout().trim();
            if (!qpsOutput.isEmpty()) {
                detail.put("qps", Integer.parseInt(qpsOutput));
            }
            
            // 获取复制延迟（仅对从节点）
            if (node.getNodeRole() != null && node.getNodeRole() == 1) {
                String replCmd = String.format("redis-cli -h %s -p %d%s info replication 2>&1 | grep '^master_last_io_seconds_ago:' | cut -d':' -f2", 
                        host, port, authCmd);
                SSHClient.SSHResult replResult = ssh.executeCommand(replCmd);
                String replOutput = replResult.getStdout().trim();
                if (!replOutput.isEmpty()) {
                    long delay = Long.parseLong(replOutput);
                    detail.put("replicationDelay", delay);
                    if (delay > 10) {
                        detail.put("replicationHealth", "延迟");
                    } else {
                        detail.put("replicationHealth", "正常");
                    }
                } else {
                    detail.put("replicationHealth", "断开");
                }
            } else {
                detail.put("replicationHealth", "-");
            }
            
        } catch (Exception e) {
            logger.warn("获取节点 {}:{} 详情失败: {}", host, port, e.getMessage());
        }
        
        return detail;
    }

    /**
     * 获取默认服务器组信息
     */
    public Result<Map<String, Object>> getDefaultServerGroup() {
        Optional<ServerGroup> defaultGroupOpt = groupRepository.findByIsDefault(1);
        
        if (defaultGroupOpt.isPresent()) {
            ServerGroup group = defaultGroupOpt.get();
            Map<String, Object> result = new HashMap<>();
            result.put("id", group.getId());
            result.put("name", group.getName());
            result.put("description", group.getDescription());
            result.put("isDefault", group.getIsDefault());
            return Result.success(result);
        }
        
        return Result.success(null);
    }

    /**
     * 获取全局统计数据（首页顶部统计卡片用）
     * 直接从数据库记录数统计，避免实时SSH查询，提高性能
     * @param groupId 服务器组ID（可选，null则统计所有）
     */
    public Result<Map<String, Object>> getGlobalStatistics(Long groupId) {
        try {
            // 使用数据库原生查询统计，避免加载所有实体
            long createdClusters;
            long importedClusters;
            long totalNodes;
            long serverGroupCount = groupRepository.count();
            
            if (groupId != null) {
                // 按服务器组统计
                createdClusters = clusterRepository.countByServerGroupIdAndClusterType(groupId, 0);
                importedClusters = clusterRepository.countByServerGroupIdAndClusterType(groupId, 1);
                totalNodes = nodeRepository.countByClusterServerGroupId(groupId);
            } else {
                // 统计全部
                createdClusters = clusterRepository.countByClusterType(0);
                importedClusters = clusterRepository.countByClusterType(1);
                totalNodes = nodeRepository.count();
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("createdClusters", (int) createdClusters);
            result.put("importedClusters", (int) importedClusters);
            result.put("totalClusters", (int) (createdClusters + importedClusters));
            result.put("totalNodes", (int) totalNodes);
            result.put("serverGroupCount", (int) serverGroupCount);
            
            // 注意：以下实时指标不再统计，如果需要可通过单独的实时接口获取
            result.put("runningNodes", 0);  // 不再实时统计
            result.put("usedMemory", 0L);   // 不再实时统计
            result.put("maxMemory", 0L);    // 不再实时统计
            result.put("connectedClients", 0);  // 不再实时统计
            
            return Result.success(result);
        } catch (Exception e) {
            logger.error("获取全局统计数据失败", e);
            return Result.error("获取统计数据失败: " + e.getMessage());
        }
    }

    /**
     * 手动刷新所有集群状态
     * 实时连接所有集群节点获取最新监控数据并更新数据库
     */
    public Result<Map<String, Object>> refreshAllClustersStatus() {
        logger.info("开始手动刷新所有集群状态...");
        
        List<RedisCluster> clusters = clusterRepository.findAllWithNodes();
        int totalClusters = clusters.size();
        int successCount = 0;
        int failCount = 0;
        
        for (RedisCluster cluster : clusters) {
            try {
                refreshClusterNodesMetrics(cluster);
                successCount++;
                logger.debug("刷新集群 [{}] 状态成功", cluster.getName());
            } catch (Exception e) {
                failCount++;
                logger.error("刷新集群 [{}] 状态失败: {}", cluster.getName(), e.getMessage());
            }
        }
        
        logger.info("手动刷新完成: 成功 {} 个, 失败 {} 个, 总计 {} 个", successCount, failCount, totalClusters);
        
        Map<String, Object> result = new HashMap<>();
        result.put("total", totalClusters);
        result.put("success", successCount);
        result.put("failed", failCount);
        return Result.success(result);
    }

    /**
     * 手动刷新指定集群状态
     */
    public Result<Map<String, Object>> refreshClusterStatus(Long clusterId) {
        logger.info("开始手动刷新集群 [{}] 状态...", clusterId);
        
        Optional<RedisCluster> opt = clusterRepository.findById(clusterId);
        if (!opt.isPresent()) {
            return Result.error("集群不存在");
        }
        
        RedisCluster cluster = opt.get();
        try {
            refreshClusterNodesMetrics(cluster);
            
            Map<String, Object> result = new HashMap<>();
            result.put("clusterId", clusterId);
            result.put("clusterName", cluster.getName());
            result.put("status", "success");
            return Result.success(result);
        } catch (Exception e) {
            logger.error("刷新集群 [{}] 状态失败: {}", cluster.getName(), e.getMessage());
            return Result.error("刷新失败: " + e.getMessage());
        }
    }

    /**
     * 刷新集群所有节点的监控数据
     */
    private void refreshClusterNodesMetrics(RedisCluster cluster) {
        List<ClusterNode> masterNodes = nodeRepository.findByClusterIdAndNodeRole(cluster.getId(), 0);
        
        for (ClusterNode node : masterNodes) {
            try {
                collectClusterNodeMetrics(node);
            } catch (Exception e) {
                logger.error("刷新节点 [{}:{}] 监控数据失败: {}", node.getIp(), node.getPort(), e.getMessage());
            }
        }
    }
}
