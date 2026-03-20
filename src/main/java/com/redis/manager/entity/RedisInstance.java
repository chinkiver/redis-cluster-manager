package com.redis.manager.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * Redis实例实体类
 * 每台服务器上运行的Redis实例
 */
@Entity
@Table(name = "redis_instances")
public class RedisInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属集群
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cluster_id", nullable = false)
    private RedisCluster cluster;

    /**
     * 所在服务器
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "server_id", nullable = false)
    private Server server;

    /**
     * 端口号
     */
    @Column(nullable = false)
    private Integer port;

    /**
     * 实例类型: master-主节点, slave-从节点
     */
    @Column(name = "node_type", length = 20)
    private String nodeType;

    /**
     * 如果是从节点，对应的主节点ID
     */
    @Column(name = "master_id", length = 100)
    private String masterId;

    /**
     * 集群节点ID
     */
    @Column(name = "cluster_node_id", length = 100)
    private String clusterNodeId;

    /**
     * 实例状态: 0-停止, 1-运行中, 2-异常
     */
    @Column(nullable = false)
    private Integer status = 0;

    /**
     * 配置文件路径
     */
    @Column(name = "config_path", length = 500)
    private String configPath;

    /**
     * 数据目录
     */
    @Column(name = "data_dir", length = 500)
    private String dataDir;

    /**
     * 内存限制 (MB)
     */
    @Column(name = "max_memory")
    private Long maxMemory;

    /**
     * 最后监控时间
     */
    @Column(name = "last_monitor_time")
    private LocalDateTime lastMonitorTime;

    /**
     * 使用内存 (MB)
     */
    @Column(name = "used_memory")
    private Long usedMemory;

    /**
     * 连接数
     */
    @Column(name = "connected_clients")
    private Integer connectedClients;

    /**
     * 总命令处理数
     */
    @Column(name = "total_commands_processed")
    private Long totalCommandsProcessed;

    /**
     * 每秒命令数
     */
    @Column(name = "instantaneous_ops_per_sec")
    private Integer instantaneousOpsPerSec;

    /**
     * 键数量
     */
    @Column(name = "key_count")
    private Long keyCount;

    /**
     * 拒绝连接数
     */
    @Column(name = "rejected_connections")
    private Long rejectedConnections;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public RedisCluster getCluster() {
        return cluster;
    }

    public void setCluster(RedisCluster cluster) {
        this.cluster = cluster;
    }

    public Server getServer() {
        return server;
    }

    public void setServer(Server server) {
        this.server = server;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getNodeType() {
        return nodeType;
    }

    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }

    public String getMasterId() {
        return masterId;
    }

    public void setMasterId(String masterId) {
        this.masterId = masterId;
    }

    public String getClusterNodeId() {
        return clusterNodeId;
    }

    public void setClusterNodeId(String clusterNodeId) {
        this.clusterNodeId = clusterNodeId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getConfigPath() {
        return configPath;
    }

    public void setConfigPath(String configPath) {
        this.configPath = configPath;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public Long getMaxMemory() {
        return maxMemory;
    }

    public void setMaxMemory(Long maxMemory) {
        this.maxMemory = maxMemory;
    }

    public LocalDateTime getLastMonitorTime() {
        return lastMonitorTime;
    }

    public void setLastMonitorTime(LocalDateTime lastMonitorTime) {
        this.lastMonitorTime = lastMonitorTime;
    }

    public Long getUsedMemory() {
        return usedMemory;
    }

    public void setUsedMemory(Long usedMemory) {
        this.usedMemory = usedMemory;
    }

    public Integer getConnectedClients() {
        return connectedClients;
    }

    public void setConnectedClients(Integer connectedClients) {
        this.connectedClients = connectedClients;
    }

    public Long getTotalCommandsProcessed() {
        return totalCommandsProcessed;
    }

    public void setTotalCommandsProcessed(Long totalCommandsProcessed) {
        this.totalCommandsProcessed = totalCommandsProcessed;
    }

    public Integer getInstantaneousOpsPerSec() {
        return instantaneousOpsPerSec;
    }

    public void setInstantaneousOpsPerSec(Integer instantaneousOpsPerSec) {
        this.instantaneousOpsPerSec = instantaneousOpsPerSec;
    }

    public Long getKeyCount() {
        return keyCount;
    }

    public void setKeyCount(Long keyCount) {
        this.keyCount = keyCount;
    }

    public Long getRejectedConnections() {
        return rejectedConnections;
    }

    public void setRejectedConnections(Long rejectedConnections) {
        this.rejectedConnections = rejectedConnections;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    /**
     * 获取实例状态描述
     */
    public String getStatusDesc() {
        switch (status) {
            case 0: return "停止";
            case 1: return "运行中";
            case 2: return "异常";
            default: return "未知";
        }
    }

    /**
     * 获取完整的地址
     */
    public String getAddress() {
        if (server != null) {
            return server.getIp() + ":" + port;
        }
        return ":" + port;
    }

    @Override
    public String toString() {
        return "RedisInstance{" +
                "id=" + id +
                ", port=" + port +
                ", nodeType='" + nodeType + '\'' +
                ", status=" + status +
                '}';
    }
}
