package com.redis.manager.controller;

import com.redis.manager.dto.Result;
import com.redis.manager.dto.ServerGroupDTO;
import com.redis.manager.dto.ServerSystemInfoDTO;
import com.redis.manager.service.ServerGroupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 服务器组管理控制器
 */
@RestController
@RequestMapping("/api/groups")
public class ServerGroupController {

    @Autowired
    private ServerGroupService groupService;

    /**
     * 创建服务器组
     */
    @PostMapping
    public Result<ServerGroupDTO> createGroup(@RequestBody ServerGroupDTO dto) {
        return groupService.createGroup(dto);
    }

    /**
     * 更新服务器组
     */
    @PutMapping("/{id}")
    public Result<ServerGroupDTO> updateGroup(@PathVariable Long id, @RequestBody ServerGroupDTO dto) {
        return groupService.updateGroup(id, dto);
    }

    /**
     * 删除服务器组
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteGroup(@PathVariable Long id) {
        return groupService.deleteGroup(id);
    }

    /**
     * 获取服务器组详情
     */
    @GetMapping("/{id}")
    public Result<ServerGroupDTO> getGroup(@PathVariable Long id) {
        return groupService.getGroup(id);
    }

    /**
     * 获取所有服务器组
     */
    @GetMapping
    public Result<List<ServerGroupDTO>> getAllGroups() {
        return groupService.getAllGroups();
    }

    /**
     * 测试服务器连接
     */
    @PostMapping("/servers/{serverId}/test")
    public Result<Boolean> testServerConnection(@PathVariable Long serverId) {
        return groupService.testServerConnection(serverId);
    }

    /**
     * 测试组内所有服务器连接
     */
    @PostMapping("/{groupId}/test-all")
    public Result<List<String>> testGroupConnection(@PathVariable Long groupId) {
        return groupService.testGroupConnection(groupId);
    }

    /**
     * 更新服务器SSH配置
     */
    @PostMapping("/servers/{serverId}/ssh")
    public Result<Void> updateServerSSH(@PathVariable Long serverId,
                                        @RequestParam String user,
                                        @RequestParam String password,
                                        @RequestParam(defaultValue = "0") Integer authType) {
        return groupService.updateServerSSH(serverId, user, password, authType);
    }

    /**
     * 获取可用的服务器组（已配置6台服务器且未创建集群）
     */
    @GetMapping("/available")
    public Result<List<ServerGroupDTO>> getAvailableGroups() {
        return groupService.getAvailableGroups();
    }

    /**
     * 获取服务器组关联的集群数量
     */
    @GetMapping("/{groupId}/cluster-count")
    public Result<Map<String, Long>> getClusterCount(@PathVariable Long groupId) {
        return groupService.getClusterCount(groupId);
    }

    /**
     * 获取服务器系统信息
     */
    @GetMapping("/servers/{serverId}/system-info")
    public Result<ServerSystemInfoDTO> getServerSystemInfo(@PathVariable Long serverId) {
        return groupService.getServerSystemInfo(serverId);
    }

    /**
     * 设置默认服务器组
     * @param groupId 服务器组ID
     */
    @PostMapping("/{groupId}/set-default")
    public Result<Void> setDefaultGroup(@PathVariable Long groupId) {
        return groupService.setDefaultGroup(groupId);
    }

    /**
     * 取消默认服务器组设置
     * @param groupId 服务器组ID
     */
    @PostMapping("/{groupId}/cancel-default")
    public Result<Void> cancelDefaultGroup(@PathVariable Long groupId) {
        return groupService.cancelDefaultGroup(groupId);
    }
}
