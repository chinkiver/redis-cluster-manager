package com.redis.manager.controller;

import com.redis.manager.dto.Result;
import com.redis.manager.entity.RedisConfigTemplate;
import com.redis.manager.service.RedisConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Redis配置控制器
 */
@RestController
@RequestMapping("/api/config")
public class RedisConfigController {

    @Autowired
    private RedisConfigService configService;

    // ==================== 配置模板管理 ====================

    /**
     * 创建配置模板
     */
    @PostMapping("/templates")
    public Result<RedisConfigTemplate> createTemplate(@RequestBody RedisConfigTemplate template) {
        return configService.createTemplate(template);
    }

    /**
     * 更新配置模板
     */
    @PutMapping("/templates/{id}")
    public Result<RedisConfigTemplate> updateTemplate(@PathVariable Long id, @RequestBody RedisConfigTemplate template) {
        return configService.updateTemplate(id, template);
    }

    /**
     * 删除配置模板
     */
    @DeleteMapping("/templates/{id}")
    public Result<Void> deleteTemplate(@PathVariable Long id) {
        return configService.deleteTemplate(id);
    }

    /**
     * 获取所有配置模板
     */
    @GetMapping("/templates")
    public Result<List<RedisConfigTemplate>> getAllTemplates() {
        return configService.getAllTemplates();
    }

    /**
     * 获取配置模板详情
     */
    @GetMapping("/templates/{id}")
    public Result<RedisConfigTemplate> getTemplate(@PathVariable Long id) {
        return configService.getTemplate(id);
    }

    /**
     * 获取默认模板
     */
    @GetMapping("/templates/default")
    public Result<RedisConfigTemplate> getDefaultTemplate() {
        return configService.getDefaultTemplate();
    }

    // ==================== 动态参数配置 ====================

    /**
     * 设置单个实例参数
     */
    @PostMapping("/instance/{instanceId}")
    public Result<Boolean> configSet(@PathVariable Long instanceId,
                                     @RequestParam String parameter,
                                     @RequestParam String value) {
        return configService.configSet(instanceId, parameter, value);
    }

    /**
     * 批量设置集群参数
     */
    @PostMapping("/cluster/{clusterId}")
    public Result<Map<String, Object>> configSetCluster(@PathVariable Long clusterId,
                                                         @RequestParam String parameter,
                                                         @RequestParam String value) {
        return configService.configSetCluster(clusterId, parameter, value);
    }

    /**
     * 获取实例配置
     */
    @GetMapping("/instance/{instanceId}")
    public Result<Map<String, String>> configGet(@PathVariable Long instanceId,
                                                 @RequestParam String parameter) {
        return configService.configGet(instanceId, parameter);
    }

    /**
     * 保存配置到文件
     */
    @PostMapping("/instance/{instanceId}/rewrite")
    public Result<Boolean> configRewrite(@PathVariable Long instanceId) {
        return configService.configRewrite(instanceId);
    }

    /**
     * 批量保存集群配置
     */
    @PostMapping("/cluster/{clusterId}/rewrite")
    public Result<Map<String, Object>> configRewriteCluster(@PathVariable Long clusterId) {
        return configService.configRewriteCluster(clusterId);
    }
}
