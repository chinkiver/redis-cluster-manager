package com.redis.manager.service;

import com.redis.manager.dto.Result;
import com.redis.manager.entity.RedisConfigTemplate;
import com.redis.manager.entity.RedisInstance;
import com.redis.manager.repository.RedisConfigTemplateRepository;
import com.redis.manager.repository.RedisInstanceRepository;
import com.redis.manager.util.RedisCommandUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.*;

/**
 * Redis配置服务
 * 管理配置模板和动态参数设置
 */
@Service
public class RedisConfigService {

    private static final Logger logger = LoggerFactory.getLogger(RedisConfigService.class);

    @Autowired
    private RedisConfigTemplateRepository templateRepository;

    @Autowired
    private RedisInstanceRepository instanceRepository;

    @Value("${redis.manager.remote-install-base:/opt/redis}")
    private String remoteInstallBase;

    /**
     * 系统初始化时不再自动创建默认模板
     * 由用户根据需要自行创建配置模板
     */
    @PostConstruct
    public void initDefaultTemplate() {
        // 不再自动创建 v5 和 v6 默认模板
        // 用户可以在前端根据推荐配置自行创建模板
        logger.info("配置模板模块已就绪，等待用户创建模板");
    }

    /**
     * 获取默认模板内容
     * 这是系统初始化时创建默认模板的来源，也用于修复损坏的模板
     * 注意：前端新建模板时使用 config-templates.html 中的 generateTemplateContent() 动态生成
     */
    private String getDefaultTemplateContent() {
        return  "# Redis 集群配置\n" +
                "# 由 Redis Manager 自动生成\n\n" +

                "# 基础配置\n" +
                "port ${PORT}\n" +
                "bind ${NODE_IP}\n" +
                "protected-mode no\n" +
                "daemonize yes\n" +
                "supervised no\n" +
                "dir ${DATA_DIR}/data\n" +
                "pidfile ${DATA_DIR}/redis_${PORT}.pid\n" +
                "logfile ${DATA_DIR}/logs/redis_${PORT}.log\n\n" +

                "# 内存配置\n" +
                "maxmemory ${MAX_MEMORY}mb\n" +
                "maxmemory-policy ${MAXMEMORY_POLICY}\n\n" +

                "# 持久化配置\n" +
                "appendonly ${APPENDONLY}\n" +
                "appendfilename \"appendonly.aof\"\n" +
                "appendfsync everysec\n" +
                "no-appendfsync-on-rewrite yes\n" +
                "auto-aof-rewrite-percentage 100\n" +
                "auto-aof-rewrite-min-size 64mb\n" +
                "aof-load-truncated yes\n\n" +

                "# RDB配置\n" +
                "save 900 1\n" +
                "save 300 10\n" +
                "save 60 10000\n" +
                "stop-writes-on-bgsave-error yes\n" +
                "rdbcompression yes\n" +
                "rdbchecksum yes\n" +
                "dbfilename dump_${PORT}.rdb\n\n" +

                "# 集群配置\n" +
                "cluster-enabled yes\n" +
                "cluster-config-file ${DATA_DIR}/nodes/nodes_${PORT}.conf\n" +
                "cluster-node-timeout 5000\n" +
                "cluster-require-full-coverage no\n\n" +

                "# 网络配置\n" +
                "tcp-backlog 511\n" +
                "timeout 0\n" +
                "tcp-keepalive 300\n\n" +

                "# 安全配置\n" +
                "requirepass ${REQUIREPASS}\n" +
                "masterauth ${MASTERAUTH}\n\n" +

                "# 性能配置\n" +
                "hash-max-ziplist-entries 512\n" +
                "hash-max-ziplist-value 64\n" +
                "list-max-ziplist-size -2\n" +
                "set-max-intset-entries 512\n" +
                "zset-max-ziplist-entries 128\n" +
                "zset-max-ziplist-value 64\n" +
                "hll-sparse-max-bytes 3000\n" +
                "activerehashing yes\n" +
                "client-output-buffer-limit normal 0 0 0\n" +
                "client-output-buffer-limit replica 256mb 64mb 60\n" +
                "client-output-buffer-limit pubsub 32mb 8mb 60\n" +
                "hz 10\n" +
                "dynamic-hz yes\n" +
                "aof-rewrite-incremental-fsync yes\n" +
                "rdb-save-incremental-fsync yes\n";
    }

    /**
     * 创建配置模板
     */
    @Transactional
    public Result<RedisConfigTemplate> createTemplate(RedisConfigTemplate template) {
        if (templateRepository.existsByName(template.getName())) {
            return Result.error("模板名称已存在");
        }

        // 如果设置为默认，则取消同版本的其他默认模板
        if (template.getIsDefault() != null && template.getIsDefault()) {
            String version = template.getRedisVersion();
            if (version != null && !version.isEmpty()) {
                templateRepository.findByRedisVersionAndIsDefaultTrue(version).ifPresent(t -> {
                    t.setIsDefault(false);
                    templateRepository.save(t);
                });
            }
        }

        templateRepository.save(template);
        return Result.success("创建成功", template);
    }

    /**
     * 更新配置模板
     */
    @Transactional
    public Result<RedisConfigTemplate> updateTemplate(Long id, RedisConfigTemplate template) {
        Optional<RedisConfigTemplate> opt = templateRepository.findById(id);
        if (!opt.isPresent()) {
            return Result.error("模板不存在");
        }

        RedisConfigTemplate existing = opt.get();

        if (!existing.getName().equals(template.getName()) &&
                templateRepository.existsByName(template.getName())) {
            return Result.error("模板名称已存在");
        }

        // 如果设置为默认且版本变化或之前不是默认，则取消同版本的其他默认模板
        if (template.getIsDefault() != null && template.getIsDefault()) {
            String version = template.getRedisVersion();
            if (version != null && !version.isEmpty()) {
                templateRepository.findByRedisVersionAndIsDefaultTrue(version).ifPresent(t -> {
                    if (!t.getId().equals(id)) {
                        t.setIsDefault(false);
                        templateRepository.save(t);
                    }
                });
            }
        }

        existing.setName(template.getName());
        existing.setDescription(template.getDescription());
        existing.setRedisVersion(template.getRedisVersion());
        existing.setTemplateContent(template.getTemplateContent());
        existing.setDefaultMaxMemory(template.getDefaultMaxMemory());
        existing.setDefaultAppendOnly(template.getDefaultAppendOnly());
        existing.setDefaultAppendFsync(template.getDefaultAppendFsync());
        existing.setDefaultMaxMemoryPolicy(template.getDefaultMaxMemoryPolicy());
        existing.setDefaultPassword(template.getDefaultPassword());
        // 使用 clusterConfigDir（优先）或 defaultDataDir（兼容旧数据）
        if (template.getClusterConfigDir() != null) {
            existing.setClusterConfigDir(template.getClusterConfigDir());
        } else if (template.getDefaultDataDir() != null) {
            existing.setDefaultDataDir(template.getDefaultDataDir());
        }
        existing.setIsDefault(template.getIsDefault());
        // 保存危险命令配置
        existing.setDisableKeys(template.getDisableKeys());
        existing.setDisableFlushdb(template.getDisableFlushdb());
        existing.setDisableFlushall(template.getDisableFlushall());
        existing.setRenameEval(template.getRenameEval());
        existing.setRenameEvalName(template.getRenameEvalName());
        existing.setRenameEvalsha(template.getRenameEvalsha());
        existing.setRenameEvalshaName(template.getRenameEvalshaName());

        templateRepository.save(existing);
        return Result.success("更新成功", existing);
    }

    /**
     * 删除配置模板
     */
    @Transactional
    public Result<Void> deleteTemplate(Long id) {
        Optional<RedisConfigTemplate> opt = templateRepository.findById(id);
        if (!opt.isPresent()) {
            return Result.error("模板不存在");
        }

        RedisConfigTemplate template = opt.get();
        if (template.getDeletable() != null && !template.getDeletable()) {
            return Result.error("系统默认模板不可删除");
        }

        templateRepository.deleteById(id);
        return Result.<Void>success("删除成功", null);
    }

    /**
     * 获取所有模板
     */
    public Result<List<RedisConfigTemplate>> getAllTemplates() {
        return Result.success(templateRepository.findAll());
    }

    /**
     * 获取模板详情
     */
    public Result<RedisConfigTemplate> getTemplate(Long id) {
        Optional<RedisConfigTemplate> opt = templateRepository.findById(id);
        if (!opt.isPresent()) {
            return Result.error("模板不存在");
        }
        return Result.success(opt.get());
    }

    /**
     * 获取默认模板
     */
    public Result<RedisConfigTemplate> getDefaultTemplate() {
        Optional<RedisConfigTemplate> opt = templateRepository.findByIsDefaultTrue();
        if (!opt.isPresent()) {
            return Result.error("没有默认模板");
        }
        return Result.success(opt.get());
    }

    /**
     * 动态设置单个实例参数
     */
    public Result<Boolean> configSet(Long instanceId, String parameter, String value) {
        Optional<RedisInstance> opt = instanceRepository.findById(instanceId);
        if (!opt.isPresent()) {
            return Result.error("实例不存在");
        }

        RedisInstance instance = opt.get();
        if (instance.getStatus() != 1) {
            return Result.error("实例未运行");
        }

        boolean success = RedisCommandUtil.configSet(
                instance.getServer().getIp(),
                instance.getPort(),
                parameter,
                value
        );

        if (success) {
            return Result.success("参数设置成功", true);
        } else {
            return Result.error("参数设置失败");
        }
    }

    /**
     * 批量设置集群参数
     */
    public Result<Map<String, Object>> configSetCluster(Long clusterId, String parameter, String value) {
        List<RedisInstance> instances = instanceRepository.findByClusterId(clusterId);

        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> details = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (RedisInstance instance : instances) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("address", instance.getAddress());

            if (instance.getStatus() != 1) {
                detail.put("success", false);
                detail.put("message", "实例未运行");
                failCount++;
            } else {
                boolean success = RedisCommandUtil.configSet(
                        instance.getServer().getIp(),
                        instance.getPort(),
                        parameter,
                        value
                );
                detail.put("success", success);
                detail.put("message", success ? "设置成功" : "设置失败");
                if (success) successCount++;
                else failCount++;
            }

            details.add(detail);
        }

        result.put("parameter", parameter);
        result.put("value", value);
        result.put("successCount", successCount);
        result.put("failCount", failCount);
        result.put("details", details);

        return Result.success(result);
    }

    /**
     * 获取单个实例配置
     */
    public Result<Map<String, String>> configGet(Long instanceId, String parameter) {
        Optional<RedisInstance> opt = instanceRepository.findById(instanceId);
        if (!opt.isPresent()) {
            return Result.error("实例不存在");
        }

        RedisInstance instance = opt.get();
        String value = RedisCommandUtil.configGet(
                instance.getServer().getIp(),
                instance.getPort(),
                parameter
        );

        Map<String, String> result = new HashMap<>();
        result.put("parameter", parameter);
        result.put("value", value);

        return Result.success(result);
    }

    /**
     * 保存配置到文件
     */
    public Result<Boolean> configRewrite(Long instanceId) {
        Optional<RedisInstance> opt = instanceRepository.findById(instanceId);
        if (!opt.isPresent()) {
            return Result.error("实例不存在");
        }

        RedisInstance instance = opt.get();
        boolean success = RedisCommandUtil.configRewrite(
                instance.getServer().getIp(),
                instance.getPort()
        );

        if (success) {
            return Result.success("配置已保存到文件", true);
        } else {
            return Result.error("保存配置失败");
        }
    }

    /**
     * 批量保存集群配置
     */
    public Result<Map<String, Object>> configRewriteCluster(Long clusterId) {
        List<RedisInstance> instances = instanceRepository.findByClusterId(clusterId);

        Map<String, Object> result = new HashMap<>();
        int successCount = 0;

        for (RedisInstance instance : instances) {
            if (instance.getStatus() == 1) {
                boolean success = RedisCommandUtil.configRewrite(
                        instance.getServer().getIp(),
                        instance.getPort()
                );
                if (success) successCount++;
            }
        }

        result.put("total", instances.size());
        result.put("successCount", successCount);

        return Result.success(result);
    }
}
