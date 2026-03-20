package com.redis.manager.controller;

import com.redis.manager.dto.Result;
import com.redis.manager.service.RedisDeployService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Redis部署控制器
 */
@RestController
@RequestMapping("/api/deploy")
public class RedisDeployController {

    @Autowired
    private RedisDeployService deployService;

    /**
     * 部署集群
     */
    @PostMapping("/cluster/{groupId}")
    public Result<String> deployCluster(@PathVariable Long groupId) {
        return deployService.deployCluster(groupId);
    }

    /**
     * 启动集群
     */
    @PostMapping("/cluster/{groupId}/start")
    public Result<String> startCluster(@PathVariable Long groupId) {
        return deployService.startCluster(groupId);
    }

    /**
     * 停止集群
     */
    @PostMapping("/cluster/{groupId}/stop")
    public Result<String> stopCluster(@PathVariable Long groupId) {
        return deployService.stopCluster(groupId);
    }

    /**
     * 卸载集群
     */
    @PostMapping("/cluster/{groupId}/uninstall")
    public Result<String> uninstallCluster(@PathVariable Long groupId) {
        return deployService.uninstallCluster(groupId);
    }

    /**
     * 检查集群状态
     */
    @GetMapping("/cluster/{groupId}/status")
    public Result<Map<String, Object>> checkClusterStatus(@PathVariable Long groupId) {
        return deployService.checkClusterStatus(groupId);
    }
}
