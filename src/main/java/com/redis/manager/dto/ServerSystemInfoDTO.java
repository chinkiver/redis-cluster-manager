package com.redis.manager.dto;

import java.util.List;

/**
 * 服务器系统信息DTO
 */
public class ServerSystemInfoDTO {

    private Long serverId;
    private String ip;

    // CPU信息
    private String cpuModel;
    private String cpuCores;
    private String cpuUsage;

    // 内存信息
    private String totalMemory;
    private String usedMemory;
    private String freeMemory;
    private String memoryUsage;

    // 操作系统信息
    private String osName;
    private String osVersion;
    private String kernelVersion;

    // 磁盘信息列表
    private List<DiskInfoDTO> diskList;

    // 连接状态
    private boolean connected;
    private String errorMessage;

    public ServerSystemInfoDTO() {
    }

    public ServerSystemInfoDTO(Long serverId, String ip) {
        this.serverId = serverId;
        this.ip = ip;
    }

    // Getters and Setters
    public Long getServerId() {
        return serverId;
    }

    public void setServerId(Long serverId) {
        this.serverId = serverId;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getCpuModel() {
        return cpuModel;
    }

    public void setCpuModel(String cpuModel) {
        this.cpuModel = cpuModel;
    }

    public String getCpuCores() {
        return cpuCores;
    }

    public void setCpuCores(String cpuCores) {
        this.cpuCores = cpuCores;
    }

    public String getCpuUsage() {
        return cpuUsage;
    }

    public void setCpuUsage(String cpuUsage) {
        this.cpuUsage = cpuUsage;
    }

    public String getTotalMemory() {
        return totalMemory;
    }

    public void setTotalMemory(String totalMemory) {
        this.totalMemory = totalMemory;
    }

    public String getUsedMemory() {
        return usedMemory;
    }

    public void setUsedMemory(String usedMemory) {
        this.usedMemory = usedMemory;
    }

    public String getFreeMemory() {
        return freeMemory;
    }

    public void setFreeMemory(String freeMemory) {
        this.freeMemory = freeMemory;
    }

    public String getMemoryUsage() {
        return memoryUsage;
    }

    public void setMemoryUsage(String memoryUsage) {
        this.memoryUsage = memoryUsage;
    }

    public String getOsName() {
        return osName;
    }

    public void setOsName(String osName) {
        this.osName = osName;
    }

    public String getOsVersion() {
        return osVersion;
    }

    public void setOsVersion(String osVersion) {
        this.osVersion = osVersion;
    }

    public String getKernelVersion() {
        return kernelVersion;
    }

    public void setKernelVersion(String kernelVersion) {
        this.kernelVersion = kernelVersion;
    }

    public List<DiskInfoDTO> getDiskList() {
        return diskList;
    }

    public void setDiskList(List<DiskInfoDTO> diskList) {
        this.diskList = diskList;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
