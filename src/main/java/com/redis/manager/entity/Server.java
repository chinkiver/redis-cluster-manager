package com.redis.manager.entity;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务器实体类
 * 每台服务器可以运行多个Redis实例
 */
@Entity
@Table(name = "servers")
public class Server {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属组
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private ServerGroup group;

    /**
     * 服务器名称/别名
     */
    @Column(length = 100)
    private String name;

    /**
     * 服务器IP地址
     */
    @Column(nullable = false, length = 50)
    private String ip;

    /**
     * SSH端口
     */
    @Column(name = "ssh_port")
    private Integer sshPort = 22;

    /**
     * SSH用户名
     */
    @Column(name = "ssh_user", length = 50)
    private String sshUser;

    /**
     * SSH密码（加密存储）
     */
    @Column(name = "ssh_password", length = 255)
    private String sshPassword;

    /**
     * 认证方式: 0-密码, 1-密钥
     */
    @Column(name = "auth_type")
    private Integer authType = 0;

    /**
     * 服务器状态: 0-离线, 1-在线, 2-异常
     */
    @Column(nullable = false)
    private Integer status = 0;

    /**
     * 服务器描述
     */
    @Column(length = 200)
    private String description;

    /**
     * Redis服务路径（如 /usr/local/bin）
     * 该路径下需要包含 redis-cli、redis-server 等命令
     */
    @Column(name = "redis_path", length = 200)
    private String redisPath = "/usr/local/bin";

    /**
     * Redis版本（自动检测，如 6.2.14）
     */
    @Column(name = "redis_version", length = 50)
    private String redisVersion;

    /**
     * 节点索引（0-5，对应服务器组中的位置）
     */
    @Column(name = "node_index")
    private Integer nodeIndex;

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

    public ServerGroup getGroup() {
        return group;
    }

    public void setGroup(ServerGroup group) {
        this.group = group;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public Integer getSshPort() {
        return sshPort;
    }

    public void setSshPort(Integer sshPort) {
        this.sshPort = sshPort;
    }

    public String getSshUser() {
        return sshUser;
    }

    public void setSshUser(String sshUser) {
        this.sshUser = sshUser;
    }

    public String getSshPassword() {
        return sshPassword;
    }

    public void setSshPassword(String sshPassword) {
        this.sshPassword = sshPassword;
    }

    public Integer getAuthType() {
        return authType;
    }

    public void setAuthType(Integer authType) {
        this.authType = authType;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRedisPath() {
        return redisPath;
    }

    public void setRedisPath(String redisPath) {
        this.redisPath = redisPath;
    }

    public String getRedisVersion() {
        return redisVersion;
    }

    public void setRedisVersion(String redisVersion) {
        this.redisVersion = redisVersion;
    }

    public Integer getNodeIndex() {
        return nodeIndex;
    }

    public void setNodeIndex(Integer nodeIndex) {
        this.nodeIndex = nodeIndex;
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
     * 获取服务器状态描述
     */
    public String getStatusDesc() {
        switch (status) {
            case 0: return "离线";
            case 1: return "在线";
            case 2: return "异常";
            default: return "未知";
        }
    }

    @Override
    public String toString() {
        return "Server{" +
                "id=" + id +
                ", ip='" + ip + '\'' +
                ", status=" + status +
                '}';
    }
}
