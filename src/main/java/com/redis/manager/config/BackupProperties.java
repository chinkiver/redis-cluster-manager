package com.redis.manager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 数据库备份配置属性
 * 
 * @author Redis Manager
 * @version 1.0.0
 */
@Component
@ConfigurationProperties(prefix = "redis.manager.backup")
public class BackupProperties {

    /**
     * 是否启用备份功能
     */
    private boolean enabled = true;

    /**
     * 备份任务执行时间（cron 表达式）
     * 默认每天凌晨 2 点执行
     */
    private String cron = "0 0 2 * * ?";

    /**
     * 备份文件保留天数
     * 默认 30 天
     */
    private int retainDays = 30;

    /**
     * 备份目录路径
     * 默认 ./data/backup
     */
    private String directory = "./data/backup";

    /**
     * 数据库文件路径
     * 默认 ./data/redis-manager.mv.db
     */
    private String databaseFile = "./data/redis-manager.mv.db";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public int getRetainDays() {
        return retainDays;
    }

    public void setRetainDays(int retainDays) {
        this.retainDays = retainDays;
    }

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(String directory) {
        this.directory = directory;
    }

    public String getDatabaseFile() {
        return databaseFile;
    }

    public void setDatabaseFile(String databaseFile) {
        this.databaseFile = databaseFile;
    }
}
