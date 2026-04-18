package com.redis.manager.controller;

import com.redis.manager.dto.Result;
import com.redis.manager.service.ClusterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 集群管理控制器
 */
@RestController
@RequestMapping("/api/clusters")
public class ClusterController {

    @Autowired
    private ClusterService clusterService;

    /**
     * 获取所有集群
     */
    @GetMapping
    public Result<List<Map<String, Object>>> getAllClusters() {
        return clusterService.getAllClusters();
    }

    /**
     * 获取集群详情
     */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> getCluster(@PathVariable Long id) {
        return clusterService.getCluster(id);
    }

    /**
     * 创建集群
     */
    @PostMapping
    public Result<Map<String, Object>> createCluster(@RequestBody Map<String, Object> params) {
        return clusterService.createCluster(params);
    }

    /**
     * 启动集群
     */
    @PostMapping("/{id}/start")
    public Result<Map<String, Object>> startCluster(@PathVariable Long id) {
        return clusterService.startCluster(id);
    }

    /**
     * 停止集群
     */
    @PostMapping("/{id}/stop")
    public Result<Map<String, Object>> stopCluster(@PathVariable Long id) {
        return clusterService.stopCluster(id);
    }

    /**
     * 删除集群（异步执行，停止服务并删除记录）
     */
    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> deleteCluster(@PathVariable Long id) {
        return clusterService.deleteCluster(id);
    }

    /**
     * 仅删除集群记录（不停止服务器上的集群服务）
     * 用于删除导入的集群或不需要停止服务的场景
     */
    @DeleteMapping("/{id}/record-only")
    public Result<Map<String, Object>> deleteClusterRecordOnly(@PathVariable Long id) {
        return clusterService.deleteClusterRecordOnly(id);
    }

    /**
     * 预览生成的配置文件
     */
    @GetMapping("/preview-config")
    public Result<Map<String, Object>> previewConfig(
            @RequestParam Long templateId,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port,
            @RequestParam(required = false) String dataDir,
            @RequestParam(required = false) String password,
            @RequestParam(required = false) String clusterConfigDir,
            @RequestParam(required = false) Integer maxMemory,
            @RequestParam(required = false) String maxmemoryPolicy) {
        return clusterService.previewConfig(templateId, ip, port, dataDir, password, clusterConfigDir, maxMemory, maxmemoryPolicy);
    }

    /**
     * 检查服务器组节点是否可导入
     */
    @PostMapping("/import-check")
    public Result<Map<String, Object>> checkImportCluster(@RequestBody Map<String, Object> params) {
        return clusterService.checkImportCluster(params);
    }

    /**
     * 导入现有集群
     */
    @PostMapping("/import")
    public Result<Map<String, Object>> importCluster(@RequestBody Map<String, Object> params) {
        return clusterService.importCluster(params);
    }

    /**
     * 获取创建进度
     */
    @GetMapping("/{id}/progress")
    public Result<Map<String, Object>> getCreateProgress(@PathVariable Long id) {
        return clusterService.getCreateProgress(id);
    }

    /**
     * 获取操作进度（启动/停止）
     */
    @GetMapping("/operation/{operationId}/progress")
    public Result<Map<String, Object>> getOperationProgress(@PathVariable String operationId) {
        return clusterService.getOperationProgress(operationId);
    }

    /**
     * 获取集群节点信息 (CLUSTER NODES)
     */
    @GetMapping("/{id}/nodes")
    public Result<String> getClusterNodes(@PathVariable Long id) {
        return clusterService.getClusterNodes(id);
    }

    /**
     * 获取集群信息 (CLUSTER INFO)
     */
    @GetMapping("/{id}/info")
    public Result<String> getClusterInfo(@PathVariable Long id) {
        return clusterService.getClusterInfo(id);
    }

    /**
     * 动态更新集群参数
     */
    @PostMapping("/{id}/config")
    public Result<Map<String, Object>> updateClusterConfig(
            @PathVariable Long id,
            @RequestParam String parameter,
            @RequestParam String value) {
        return clusterService.updateClusterConfig(id, parameter, value);
    }

    /**
     * 查询缓存Key列表
     */
    @GetMapping("/{id}/query-keys")
    public Result<List<Map<String, Object>>> queryKeys(
            @PathVariable Long id,
            @RequestParam String pattern) {
        return clusterService.queryKeys(id, pattern);
    }

    /**
     * 获取缓存Key的值
     */
    @GetMapping("/{id}/key-value")
    public Result<Map<String, Object>> getKeyValue(
            @PathVariable Long id,
            @RequestParam String ip,
            @RequestParam Integer port,
            @RequestParam String key) {
        return clusterService.getKeyValue(id, ip, port, key);
    }

    /**
     * 获取节点配置项
     */
    @GetMapping("/{id}/node-config")
    public Result<String> getNodeConfig(
            @PathVariable Long id,
            @RequestParam String ip,
            @RequestParam Integer port,
            @RequestParam String param) {
        return clusterService.getNodeConfig(id, ip, port, param);
    }

    /**
     * 获取节点完整配置（CONFIG GET *）
     */
    @GetMapping("/{id}/node-config-all")
    public Result<String> getNodeConfigAll(
            @PathVariable Long id,
            @RequestParam String ip,
            @RequestParam Integer port) {
        return clusterService.getNodeConfigAll(id, ip, port);
    }

    /**
     * 修改集群名称
     */
    @PutMapping("/{id}/name")
    public Result<Map<String, Object>> updateClusterName(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        String newName = request.get("name");
        return clusterService.updateClusterName(id, newName);
    }
}
