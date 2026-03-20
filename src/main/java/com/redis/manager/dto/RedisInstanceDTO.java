package com.redis.manager.dto;

/**
 * Redis实例DTO
 */
public class RedisInstanceDTO {

    private Long id;
    private Long serverId;
    private String serverIp;
    private Integer port;
    private String nodeType;
    private String masterId;
    private String clusterNodeId;
    private Integer status;
    private Long maxMemory;
    private Long usedMemory;
    private Integer connectedClients;
    private Long totalCommandsProcessed;
    private Integer instantaneousOpsPerSec;
    private Long keyCount;
    private String role;

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getServerId() {
        return serverId;
    }

    public void setServerId(Long serverId) {
        this.serverId = serverId;
    }

    public String getServerIp() {
        return serverIp;
    }

    public void setServerIp(String serverIp) {
        this.serverIp = serverIp;
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

    public Long getMaxMemory() {
        return maxMemory;
    }

    public void setMaxMemory(Long maxMemory) {
        this.maxMemory = maxMemory;
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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
