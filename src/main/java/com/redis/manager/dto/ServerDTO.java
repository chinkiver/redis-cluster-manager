package com.redis.manager.dto;

import java.util.List;

/**
 * 服务器DTO
 */
public class ServerDTO {

    private Long id;
    private Long groupId;
    private String name;
    private String ip;
    private Integer sshPort;
    private String sshUser;
    private String sshPassword;
    private Integer authType;
    private Integer status;
    private String description;
    private String redisPath;
    private String redisVersion;
    private Integer nodeIndex;
    private List<RedisInstanceDTO> redisInstances;

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
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

    public List<RedisInstanceDTO> getRedisInstances() {
        return redisInstances;
    }

    public void setRedisInstances(List<RedisInstanceDTO> redisInstances) {
        this.redisInstances = redisInstances;
    }
}
