package com.redis.manager.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 集群节点实体
 * 记录集群中每个Redis实例的信息
 */
@Entity
@Table(name = "cluster_nodes")
public class ClusterNode {

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
     * 对应的服务器
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_id")
    private Server server;

    /**
     * 节点索引（0-N，根据集群规模动态确定）
     */
    @Column(name = "node_index")
    private Integer nodeIndex;

    /**
     * 节点角色: 0-主节点, 1-从节点
     */
    @Column(name = "node_role")
    private Integer nodeRole;

    /**
     * IP地址
     */
    @Column(length = 50)
    private String ip;

    /**
     * 端口号
     */
    @Column
    private Integer port;

    /**
     * 节点状态: 0-离线, 1-在线, 2-异常
     */
    @Column
    private Integer status = 0;

    /**
     * Redis节点ID（集群内部ID）
     */
    @Column(name = "node_id", length = 50)
    private String nodeId;

    /**
     * 如果是从节点，对应的主节点索引
     */
    @Column(name = "master_index")
    private Integer masterIndex;

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
     * 主节点ID（从节点使用）
     */
    @Column(name = "master_id", length = 50)
    private String masterId;

    /**
     * 关联的主节点（从节点使用）
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_node_id")
    private ClusterNode masterNode;

    /**
     * 使用内存 (MB) - 仅主节点
     */
    @Column(name = "used_memory")
    private Long usedMemory;

    /**
     * 最大内存限制 (MB) - 仅主节点
     */
    @Column(name = "max_memory")
    private Long maxMemory;

    /**
     * 连接数 - 仅主节点
     */
    @Column(name = "connected_clients")
    private Integer connectedClients;

    /**
     * 最后监控时间
     */
    @Column(name = "last_monitor_time")
    private LocalDateTime lastMonitorTime;

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

    public Integer getNodeIndex() {
        return nodeIndex;
    }

    public void setNodeIndex(Integer nodeIndex) {
        this.nodeIndex = nodeIndex;
    }

    public Integer getNodeRole() {
        return nodeRole;
    }

    public void setNodeRole(Integer nodeRole) {
        this.nodeRole = nodeRole;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public Integer getMasterIndex() {
        return masterIndex;
    }

    public void setMasterIndex(Integer masterIndex) {
        this.masterIndex = masterIndex;
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

    public String getRoleDesc() {
        return nodeRole != null && nodeRole == 0 ? "主节点" : "从节点";
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

    public String getMasterId() {
        return masterId;
    }

    public void setMasterId(String masterId) {
        this.masterId = masterId;
    }

    public ClusterNode getMasterNode() {
        return masterNode;
    }

    public void setMasterNode(ClusterNode masterNode) {
        this.masterNode = masterNode;
    }

    public Long getUsedMemory() {
        return usedMemory;
    }

    public void setUsedMemory(Long usedMemory) {
        this.usedMemory = usedMemory;
    }

    public Long getMaxMemory() {
        return maxMemory;
    }

    public void setMaxMemory(Long maxMemory) {
        this.maxMemory = maxMemory;
    }

    public Integer getConnectedClients() {
        return connectedClients;
    }

    public void setConnectedClients(Integer connectedClients) {
        this.connectedClients = connectedClients;
    }

    public LocalDateTime getLastMonitorTime() {
        return lastMonitorTime;
    }

    public void setLastMonitorTime(LocalDateTime lastMonitorTime) {
        this.lastMonitorTime = lastMonitorTime;
    }

    /**
     * 节点角色字符串表示
     */
    public String getNodeRoleStr() {
        if (nodeRole == null) return null;
        return nodeRole == 0 ? "master" : "slave";
    }

    public void setNodeRoleStr(String role) {
        if ("master".equals(role)) {
            this.nodeRole = 0;
        } else if ("slave".equals(role)) {
            this.nodeRole = 1;
        }
    }
}
