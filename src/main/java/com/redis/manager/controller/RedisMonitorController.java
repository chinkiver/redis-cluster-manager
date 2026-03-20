package com.redis.manager.controller;

import com.redis.manager.dto.Result;
import com.redis.manager.service.RedisMonitorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Redis监控控制器
 */
@RestController
@RequestMapping("/api/monitor")
public class RedisMonitorController {

    @Autowired
    private RedisMonitorService monitorService;

    /**
     * 获取实例监控数据
     */
    @GetMapping("/instance/{instanceId}")
    public Result<Map<String, Object>> getInstanceMetrics(@PathVariable Long instanceId) {
        return monitorService.getInstanceMetrics(instanceId);
    }

    /**
     * 获取集群监控概览
     */
    @GetMapping("/cluster/{groupId}")
    public Result<Map<String, Object>> getClusterOverview(@PathVariable Long groupId) {
        return monitorService.getClusterOverview(groupId);
    }

    /**
     * 获取所有集群状态（支持分页和服务器组筛选）
     * @param groupId 服务器组ID（可选，不传则查询所有或默认组）
     * @param page 页码（从1开始）
     * @param size 每页大小
     * @param defaultGroup 是否查询默认服务器组的集群
     */
    @GetMapping("/clusters")
    public Result<Map<String, Object>> getAllClustersStatus(
            @RequestParam(required = false) Long groupId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "false") boolean defaultGroup) {
        return monitorService.getAllClustersStatus(groupId, page, size, defaultGroup);
    }

    /**
     * 获取集群查询调试信息（显示执行的命令和返回结果）
     */
    @GetMapping("/clusters/debug")
    public Result<List<Map<String, Object>>> getAllClustersDebugInfo() {
        return monitorService.getAllClustersDebugInfo();
    }

    /**
     * 获取物理机监控数据（必须指定服务器组）
     * @param groupId 服务器组ID
     */
    @GetMapping("/physical")
    public Result<List<Map<String, Object>>> getPhysicalHostsMonitoring(
            @RequestParam Long groupId) {
        if (groupId == null) {
            return Result.error("必须指定服务器组ID");
        }
        return monitorService.getPhysicalHostsMonitoring(groupId);
    }

    /**
     * 获取实例级监控数据（必须指定集群）
     * @param clusterId 集群ID
     */
    @GetMapping("/instances")
    public Result<List<Map<String, Object>>> getInstancesMonitoring(
            @RequestParam Long clusterId) {
        if (clusterId == null) {
            return Result.error("必须指定集群ID");
        }
        return monitorService.getInstancesMonitoring(clusterId);
    }

    /**
     * 获取集群级监控数据（必须指定集群）
     * @param clusterId 集群ID
     */
    @GetMapping("/cluster-level")
    public Result<List<Map<String, Object>>> getClusterLevelMonitoring(
            @RequestParam Long clusterId) {
        if (clusterId == null) {
            return Result.error("必须指定集群ID");
        }
        return monitorService.getClusterLevelMonitoring(clusterId);
    }

    /**
     * 获取默认服务器组信息
     */
    @GetMapping("/default-group")
    public Result<Map<String, Object>> getDefaultServerGroup() {
        return monitorService.getDefaultServerGroup();
    }

    /**
     * 获取全局统计数据（首页顶部统计卡片用）
     * 返回所有集群的统计总数，不受分页影响
     * @param groupId 服务器组ID（可选）
     */
    @GetMapping("/global-statistics")
    public Result<Map<String, Object>> getGlobalStatistics(
            @RequestParam(required = false) Long groupId) {
        return monitorService.getGlobalStatistics(groupId);
    }

    /**
     * 手动刷新所有集群状态
     * 实时连接所有集群节点获取最新监控数据并更新数据库
     */
    @PostMapping("/clusters/refresh")
    public Result<Map<String, Object>> refreshAllClustersStatus() {
        return monitorService.refreshAllClustersStatus();
    }

    /**
     * 手动刷新指定集群状态
     * 实时连接集群节点获取最新监控数据并更新数据库
     * @param clusterId 集群ID
     */
    @PostMapping("/clusters/{clusterId}/refresh")
    public Result<Map<String, Object>> refreshClusterStatus(@PathVariable Long clusterId) {
        return monitorService.refreshClusterStatus(clusterId);
    }
}
