package com.redis.manager.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * Redis配置模板实体类
 * 统一的Redis配置模板，支持自定义参数
 */
@Entity
@Table(name = "redis_config_templates")
public class RedisConfigTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 模板名称
     */
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /**
     * 模板描述
     */
    @Column(length = 500)
    private String description;

    /**
     * 是否是默认模板
     */
    @Column(name = "is_default")
    private Boolean isDefault = false;

    /**
     * Redis版本适配
     */
    @Column(name = "redis_version", length = 50)
    private String redisVersion;

    /**
     * 配置模板内容（使用占位符）
     */
    @Lob
    @Column(nullable = false, columnDefinition = "CLOB")
    private String templateContent;

    /**
     * 默认内存限制 (MB)
     */
    @Column(name = "default_max_memory")
    private Long defaultMaxMemory = 4096L;

    /**
     * 是否启用AOF
     */
    @Column(name = "default_appendonly")
    private Boolean defaultAppendOnly = true;

    /**
     * 默认AOF策略
     */
    @Column(name = "default_appendfsync", length = 20)
    private String defaultAppendFsync = "everysec";

    /**
     * 默认缓存淘汰策略
     */
    @Column(name = "default_maxmemory_policy", length = 50)
    private String defaultMaxMemoryPolicy = "allkeys-lru";

    /**
     * 默认密码（用于requirepass和masterauth）
     */
    @Column(name = "default_password", length = 100)
    private String defaultPassword;

    /**
     * 集群数据目录
     * 实际会使用 ${DATA_DIR}/${PORT} 作为根目录
     * 系统会在该目录下分别创建 conf、data、nodes 和 logs 文件夹
     * 分别用来存放配置文件、数据文件、集群配置文件和日志文件
     */
    @Column(name = "cluster_config_dir", length = 200)
    private String clusterConfigDir = "/opt/redis-cluster";

    /**
     * 默认Redis安装路径
     */
    @Column(name = "default_redis_path", length = 200)
    private String defaultRedisPath = "/usr/local/bin";

    /**
     * 是否禁用KEYS命令
     */
    @Column(name = "disable_keys")
    private Boolean disableKeys = true;

    /**
     * 是否禁用FLUSHDB命令
     */
    @Column(name = "disable_flushdb")
    private Boolean disableFlushdb = true;

    /**
     * 是否禁用FLUSHALL命令
     */
    @Column(name = "disable_flushall")
    private Boolean disableFlushall = true;

    /**
     * 是否重命名EVAL命令
     */
    @Column(name = "rename_eval")
    private Boolean renameEval = false;

    /**
     * EVAL命令新名称
     */
    @Column(name = "rename_eval_name", length = 50)
    private String renameEvalName;

    /**
     * 是否重命名EVALSHA命令
     */
    @Column(name = "rename_evalsha")
    private Boolean renameEvalsha = false;

    /**
     * EVALSHA命令新名称
     */
    @Column(name = "rename_evalsha_name", length = 50)
    private String renameEvalshaName;

    /**
     * 是否可删除（默认模板不可删除）
     */
    @Column(name = "deletable")
    private Boolean deletable = true;

    // ==================== Redis 6.x 多线程配置 ====================
    
    /**
     * 是否启用 I/O 多线程（仅 Redis 6.x+）
     */
    @Column(name = "io_threads_enabled")
    private Boolean ioThreadsEnabled = false;
    
    /**
     * I/O 线程数量（仅 Redis 6.x+）
     */
    @Column(name = "io_threads_count")
    private Integer ioThreadsCount = 1;
    
    /**
     * 是否启用多线程读取（仅 Redis 6.x+）
     */
    @Column(name = "io_threads_do_reads")
    private Boolean ioThreadsDoReads = false;

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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Boolean isDefault) {
        this.isDefault = isDefault;
    }

    public String getRedisVersion() {
        return redisVersion;
    }

    public void setRedisVersion(String redisVersion) {
        this.redisVersion = redisVersion;
    }

    public String getTemplateContent() {
        return templateContent;
    }

    public void setTemplateContent(String templateContent) {
        this.templateContent = templateContent;
    }

    public Long getDefaultMaxMemory() {
        return defaultMaxMemory;
    }

    public void setDefaultMaxMemory(Long defaultMaxMemory) {
        this.defaultMaxMemory = defaultMaxMemory;
    }

    public Boolean getDefaultAppendOnly() {
        return defaultAppendOnly;
    }

    public void setDefaultAppendOnly(Boolean defaultAppendOnly) {
        this.defaultAppendOnly = defaultAppendOnly;
    }

    public String getDefaultAppendFsync() {
        return defaultAppendFsync;
    }

    public void setDefaultAppendFsync(String defaultAppendFsync) {
        this.defaultAppendFsync = defaultAppendFsync;
    }

    public String getDefaultMaxMemoryPolicy() {
        return defaultMaxMemoryPolicy;
    }

    public void setDefaultMaxMemoryPolicy(String defaultMaxMemoryPolicy) {
        this.defaultMaxMemoryPolicy = defaultMaxMemoryPolicy;
    }

    public String getDefaultPassword() {
        return defaultPassword;
    }

    public void setDefaultPassword(String defaultPassword) {
        this.defaultPassword = defaultPassword;
    }

    public String getClusterConfigDir() {
        return clusterConfigDir;
    }

    public void setClusterConfigDir(String clusterConfigDir) {
        this.clusterConfigDir = clusterConfigDir;
    }
    
    /**
     * 向后兼容的方法
     */
    public String getDefaultDataDir() {
        return clusterConfigDir;
    }

    public void setDefaultDataDir(String defaultDataDir) {
        this.clusterConfigDir = defaultDataDir;
    }

    public String getDefaultRedisPath() {
        return defaultRedisPath;
    }

    public void setDefaultRedisPath(String defaultRedisPath) {
        this.defaultRedisPath = defaultRedisPath;
    }

    public Boolean getDisableKeys() {
        return disableKeys;
    }

    public void setDisableKeys(Boolean disableKeys) {
        this.disableKeys = disableKeys;
    }

    public Boolean getDisableFlushdb() {
        return disableFlushdb;
    }

    public void setDisableFlushdb(Boolean disableFlushdb) {
        this.disableFlushdb = disableFlushdb;
    }

    public Boolean getDisableFlushall() {
        return disableFlushall;
    }

    public void setDisableFlushall(Boolean disableFlushall) {
        this.disableFlushall = disableFlushall;
    }

    public Boolean getRenameEval() {
        return renameEval;
    }

    public void setRenameEval(Boolean renameEval) {
        this.renameEval = renameEval;
    }

    public String getRenameEvalName() {
        return renameEvalName;
    }

    public void setRenameEvalName(String renameEvalName) {
        this.renameEvalName = renameEvalName;
    }

    public Boolean getRenameEvalsha() {
        return renameEvalsha;
    }

    public void setRenameEvalsha(Boolean renameEvalsha) {
        this.renameEvalsha = renameEvalsha;
    }

    public String getRenameEvalshaName() {
        return renameEvalshaName;
    }

    public void setRenameEvalshaName(String renameEvalshaName) {
        this.renameEvalshaName = renameEvalshaName;
    }

    public Boolean getDeletable() {
        return deletable;
    }

    public void setDeletable(Boolean deletable) {
        this.deletable = deletable;
    }

    public Boolean getIoThreadsEnabled() {
        return ioThreadsEnabled;
    }

    public void setIoThreadsEnabled(Boolean ioThreadsEnabled) {
        this.ioThreadsEnabled = ioThreadsEnabled;
    }

    public Integer getIoThreadsCount() {
        return ioThreadsCount;
    }

    public void setIoThreadsCount(Integer ioThreadsCount) {
        this.ioThreadsCount = ioThreadsCount;
    }

    public Boolean getIoThreadsDoReads() {
        return ioThreadsDoReads;
    }

    public void setIoThreadsDoReads(Boolean ioThreadsDoReads) {
        this.ioThreadsDoReads = ioThreadsDoReads;
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
     * 根据参数生成配置内容
     */
    public String generateConfig(String nodeIp, Integer port, String dataDir, 
                                  Long maxMemory, Boolean appendOnly) {
        return generateConfig(nodeIp, port, dataDir, maxMemory, appendOnly, null, null);
    }

    /**
     * 根据参数生成配置内容（支持密码）
     */
    public String generateConfig(String nodeIp, Integer port, String dataDir, 
                                  Long maxMemory, Boolean appendOnly, String password) {
        return generateConfig(nodeIp, port, dataDir, maxMemory, appendOnly, password, null);
    }

    /**
     * 根据参数生成配置内容（支持密码和淘汰策略）
     * @param nodeIp 节点IP
     * @param port 端口
     * @param nodeBaseDir 节点根目录（如 /opt/redis-cluster/6100）
     * @param maxMemory 最大内存(MB)
     * @param appendOnly 是否开启AOF
     * @param password 密码
     * @param maxMemoryPolicy 淘汰策略
     */
    public String generateConfig(String nodeIp, Integer port, String nodeBaseDir, 
                                  Long maxMemory, Boolean appendOnly, String password, String maxMemoryPolicy) {
        String config = templateContent;
        config = config.replace("${NODE_IP}", nodeIp);
        config = config.replace("${PORT}", String.valueOf(port));
        config = config.replace("${DATA_DIR}", nodeBaseDir);
        config = config.replace("${CLUSTER_CONFIG_DIR}", nodeBaseDir);
        config = config.replace("${MAX_MEMORY}", String.valueOf(maxMemory));
        config = config.replace("${APPENDONLY}", appendOnly ? "yes" : "no");
        config = config.replace("${CLUSTER_ENABLED}", "yes");
        config = config.replace("${CLUSTER_CONFIG_FILE}", nodeBaseDir + "/nodes/nodes-" + port + ".conf");
        
        // 替换密码占位符
        String requirePass = (password != null && !password.isEmpty()) ? password : "";
        String masterAuth = (password != null && !password.isEmpty()) ? password : "";
        config = config.replace("${REQUIREPASS}", requirePass);
        config = config.replace("${MASTERAUTH}", masterAuth);
        
        // 替换淘汰策略占位符
        String policy = (maxMemoryPolicy != null && !maxMemoryPolicy.isEmpty()) ? maxMemoryPolicy : "allkeys-lru";
        config = config.replace("${MAXMEMORY_POLICY}", policy);
        
        return config;
    }
}
