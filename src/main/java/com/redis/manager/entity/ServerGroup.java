package com.redis.manager.entity;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务器组实体类
 * 一个组包含多台服务器，用于部署Redis集群
 * 服务器数量不再固定，可根据需求动态配置
 */
@Entity
@Table(name = "server_groups")
public class ServerGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 组名称
     */
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /**
     * 组描述
     */
    @Column(length = 500)
    private String description;

    /**
     * 关联的服务器列表（动态数量）
     */
    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    @OrderBy("id asc")
    private List<Server> servers = new ArrayList<>();

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    /**
     * 是否为默认展示的服务器组（首页默认筛选）
     * 0-否, 1-是，只能有一个默认
     */
    @Column(name = "is_default", nullable = false)
    private Integer isDefault = 0;

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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<Server> getServers() {
        return servers;
    }

    public void setServers(List<Server> servers) {
        this.servers = servers;
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

    public Integer getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Integer isDefault) {
        this.isDefault = isDefault;
    }

    /**
     * 获取服务器数量
     */
    public int getServerCount() {
        return servers != null ? servers.size() : 0;
    }

    /**
     * 检查服务器数量是否有效（至少2台，用于组成最小2*2集群）
     */
    public boolean isValidServerCount() {
        int count = getServerCount();
        return count >= 2;
    }
    
    /**
     * 获取服务器组支持的最大集群规格
     * 2台 -> 2*2, 3台 -> 3*3, 以此类推，最大6*6
     */
    public int getMaxClusterSize() {
        int count = getServerCount();
        return Math.min(count, 6);
    }

    /**
     * 获取服务器数量描述
     */
    public String getServerCountDesc() {
        int count = getServerCount();
        int maxCluster = getMaxClusterSize();
        if (count >= 2 && count <= 6) {
            return count + "台（支持最大 " + maxCluster + "*" + maxCluster + " 集群）";
        } else if (count > 6) {
            return count + "台（支持最大 6*6 集群，可创建多个集群）";
        } else {
            return count + "台";
        }
    }
}
