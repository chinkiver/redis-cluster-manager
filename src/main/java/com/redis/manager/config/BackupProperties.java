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

    /**
     * 是否使用外部 Crontab 触发备份
     * true: 禁用应用内定时任务，通过 Linux Crontab 调用 API 触发
     * false: 使用应用内 @Scheduled 定时任务（默认）
     */
    private boolean useCronJob = false;

    /**
     * API 调用 Token（用于 Crontab 调用时的安全验证）
     * 建议设置复杂字符串，如 UUID
     */
    private String apiToken = "";

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

    public boolean isUseCronJob() {
        return useCronJob;
    }

    public void setUseCronJob(boolean useCronJob) {
        this.useCronJob = useCronJob;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }
}
