package com.redis.manager.dto;

/**
 * 磁盘信息DTO
 */
public class DiskInfoDTO {
    private String filesystem;  // 文件系统
    private String size;        // 总容量
    private String used;        // 已用
    private String available;   // 可用
    private String usePercent;  // 使用率
    private String mountedOn;   // 挂载点

    public DiskInfoDTO() {
    }

    public DiskInfoDTO(String filesystem, String size, String used, String available, String usePercent, String mountedOn) {
        this.filesystem = filesystem;
        this.size = size;
        this.used = used;
        this.available = available;
        this.usePercent = usePercent;
        this.mountedOn = mountedOn;
    }

    public String getFilesystem() {
        return filesystem;
    }

    public void setFilesystem(String filesystem) {
        this.filesystem = filesystem;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public String getUsed() {
        return used;
    }

    public void setUsed(String used) {
        this.used = used;
    }

    public String getAvailable() {
        return available;
    }

    public void setAvailable(String available) {
        this.available = available;
    }

    public String getUsePercent() {
        return usePercent;
    }

    public void setUsePercent(String usePercent) {
        this.usePercent = usePercent;
    }

    public String getMountedOn() {
        return mountedOn;
    }

    public void setMountedOn(String mountedOn) {
        this.mountedOn = mountedOn;
    }
}
