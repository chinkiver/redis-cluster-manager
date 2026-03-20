package com.redis.manager.entity;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis集群实体
 * 记录通过系统创建或导入的集群信息
 */
@Entity
@Table(name = "redis_clusters")
public class RedisCluster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 集群名称
     */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * 关联的服务器组
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private ServerGroup serverGroup;

    /**
     * Redis版本（如 6.2.14）
     */
    @Column(name = "redis_version", length = 50)
    private String redisVersion;

    /**
     * 集群基础端口
     */
    @Column(name = "base_port")
    private Integer basePort;

    /**
     * 集群密码
     */
    @Column(length = 100)
    private String password;

    /**
     * 集群状态: 0-创建中, 1-运行中, 2-已停止, 3-异常
     */
    @Column(nullable = false)
    private Integer status = 0;

    /**
     * 集群类型: 0-系统创建, 1-外部导入
     */
    @Column(name = "cluster_type")
    private Integer clusterType = 0;

    /**
     * 主从配置（JSON格式存储）
     * 如：{"masters":[0,1,2],"slaves":[3,4,5]}
     */
    @Column(name = "master_slave_config", length = 500)
    private String masterSlaveConfig;

    /**
     * 使用的配置模板ID
     */
    @Column(name = "template_id")
    private Long templateId;

    /**
     * 集群节点信息
     */
    @OneToMany(mappedBy = "cluster", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("nodeIndex asc")
    private List<ClusterNode> nodes = new ArrayList<>();

    /**
     * Redis实例列表（兼容旧代码）
     */
    @OneToMany(mappedBy = "cluster", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<RedisInstance> instances = new ArrayList<>();

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ServerGroup getServerGroup() {
        return serverGroup;
    }

    public void setServerGroup(ServerGroup serverGroup) {
        this.serverGroup = serverGroup;
    }

    public String getRedisVersion() {
        return redisVersion;
    }

    public void setRedisVersion(String redisVersion) {
        this.redisVersion = redisVersion;
    }

    public Integer getBasePort() {
        return basePort;
    }

    public void setBasePort(Integer basePort) {
        this.basePort = basePort;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getClusterType() {
        return clusterType;
    }

    public void setClusterType(Integer clusterType) {
        this.clusterType = clusterType;
    }

    public String getMasterSlaveConfig() {
        return masterSlaveConfig;
    }

    public void setMasterSlaveConfig(String masterSlaveConfig) {
        this.masterSlaveConfig = masterSlaveConfig;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public List<ClusterNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<ClusterNode> nodes) {
        this.nodes = nodes;
    }

    public List<RedisInstance> getInstances() {
        return instances;
    }

    public void setInstances(List<RedisInstance> instances) {
        this.instances = instances;
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

    public String getStatusDesc() {
        switch (status) {
            case 0: return "创建中";
            case 1: return "运行中";
            case 2: return "已停止";
            case 3: return "异常";
            default: return "未知";
        }
    }

    // 别名方法，用于向后兼容
    public String getRedisPassword() {
        return password;
    }

    public void setRedisPassword(String password) {
        this.password = password;
    }

    public RedisConfigTemplate getConfigTemplate() {
        // 返回一个基于templateId的轻量级模板对象
        if (templateId == null) return null;
        RedisConfigTemplate template = new RedisConfigTemplate();
        template.setId(templateId);
        return template;
    }

    public void setConfigTemplate(RedisConfigTemplate template) {
        if (template != null) {
            this.templateId = template.getId();
        }
    }

    public void setDeployMode(int mode) {
        // 部署模式已废弃，保留方法用于兼容
    }
}
