package com.redis.manager.service;

import com.redis.manager.entity.SystemConfig;
import com.redis.manager.repository.SystemConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SystemConfigService {

    @Autowired
    private SystemConfigRepository systemConfigRepository;

    // 本地缓存，避免频繁查询数据库
    private final Map<String, String> configCache = new ConcurrentHashMap<>();

    // 默认配置
    public static final Map<String, String> DEFAULT_CONFIGS = new HashMap<>();

    static {
        DEFAULT_CONFIGS.put("sys.name", "缓存3×3监控英雄");
        DEFAULT_CONFIGS.put("sys.logo", "/redis.png");
        DEFAULT_CONFIGS.put("sys.icon", "/favicon.ico");
        DEFAULT_CONFIGS.put("sys.login.title", "缓存3×3监控英雄");
        DEFAULT_CONFIGS.put("sys.login.subtitle", "Redis集群管理，请登录继续操作");
        DEFAULT_CONFIGS.put("sys.copyright", "Redis集群管理系统");
        DEFAULT_CONFIGS.put("sys.theme.primary", "#ff7324");
    }

    /**
     * 系统启动时初始化配置缓存
     */
    @PostConstruct
    public void init() {
        refreshCache();
    }

    /**
     * 刷新配置缓存
     */
    public void refreshCache() {
        configCache.clear();
        List<SystemConfig> configs = systemConfigRepository.findAll();
        for (SystemConfig config : configs) {
            if (config.getConfigValue() != null) {
                configCache.put(config.getConfigKey(), config.getConfigValue());
            }
        }
    }

    /**
     * 获取配置值，如果不存在返回默认值
     */
    public String getValue(String key) {
        String value = configCache.get(key);
        if (value == null) {
            value = DEFAULT_CONFIGS.get(key);
        }
        return value;
    }

    /**
     * 获取配置值，带自定义默认值
     */
    public String getValue(String key, String defaultValue) {
        String value = configCache.get(key);
        return value != null ? value : defaultValue;
    }

    /**
     * 获取所有配置
     */
    public Map<String, String> getAllConfigs() {
        Map<String, String> result = new HashMap<>(DEFAULT_CONFIGS);
        result.putAll(configCache);
        return result;
    }

    /**
     * 获取所有配置项（包含详细信息）
     */
    public List<SystemConfig> getAllConfigEntities() {
        return systemConfigRepository.findAll();
    }

    /**
     * 更新或保存配置
     */
    @Transactional
    public SystemConfig saveOrUpdate(String key, String value, String description) {
        Optional<SystemConfig> existing = systemConfigRepository.findByConfigKey(key);
        SystemConfig config;
        if (existing.isPresent()) {
            config = existing.get();
            config.setConfigValue(value);
            if (description != null) {
                config.setDescription(description);
            }
        } else {
            config = new SystemConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setDescription(description);
        }
        SystemConfig saved = systemConfigRepository.save(config);
        // 更新缓存
        configCache.put(key, value);
        return saved;
    }

    /**
     * 批量更新配置
     */
    @Transactional
    public void batchUpdate(Map<String, String> configs) {
        for (Map.Entry<String, String> entry : configs.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value != null) {
                saveOrUpdate(key, value, null);
            }
        }
    }

    /**
     * 删除配置
     */
    @Transactional
    public void deleteByKey(String key) {
        systemConfigRepository.deleteByConfigKey(key);
        configCache.remove(key);
    }

    /**
     * 重置配置为默认值
     */
    @Transactional
    public void resetToDefault(String key) {
        deleteByKey(key);
    }

    /**
     * 重置所有配置为默认值
     */
    @Transactional
    public void resetAllToDefault() {
        systemConfigRepository.deleteAll();
        configCache.clear();
    }

    /**
     * 获取系统展示配置（给前端使用）
     */
    public Map<String, Object> getSystemDisplayConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("name", getValue("sys.name"));
        config.put("logo", getValue("sys.logo"));
        config.put("icon", getValue("sys.icon"));
        config.put("loginTitle", getValue("sys.login.title"));
        config.put("loginSubtitle", getValue("sys.login.subtitle"));
        config.put("copyright", getValue("sys.copyright"));
        config.put("themePrimary", getValue("sys.theme.primary"));
        return config;
    }
}
