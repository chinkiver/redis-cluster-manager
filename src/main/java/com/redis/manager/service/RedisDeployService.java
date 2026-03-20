package com.redis.manager.service;

import com.redis.manager.dto.Result;
import com.redis.manager.entity.*;
import com.redis.manager.repository.*;
import com.redis.manager.ssh.SSHClient;
import com.redis.manager.ssh.SSHConnectionPool;
import com.redis.manager.util.RedisCommandUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Redis部署服务（旧版，保留兼容）
 * 建议使用新的 ClusterDeployService
 */
@Service
public class RedisDeployService {

    private static final Logger logger = LoggerFactory.getLogger(RedisDeployService.class);

    @Autowired
    private ServerGroupRepository groupRepository;

    @Autowired
    private ServerRepository serverRepository;

    @Autowired
    private RedisInstanceRepository instanceRepository;

    @Autowired
    private RedisConfigTemplateRepository templateRepository;

    @Autowired
    private RedisClusterRepository clusterRepository;

    @Autowired
    private SSHConnectionPool sshPool;

    @Value("${redis.manager.remote-install-base:/opt/redis}")
    private String remoteInstallBase;

    @Value("${redis.manager.remote-data-base:/data/redis}")
    private String remoteDataBase;

    /**
     * 检查集群状态
     */
    public Result<Map<String, Object>> checkClusterStatus(Long groupId) {
        // 简化为检查服务器组下的服务器状态
        List<Server> servers = serverRepository.findByGroupId(groupId);
        
        Map<String, Object> status = new HashMap<>();
        List<Map<String, Object>> nodeStatus = new ArrayList<>();
        int onlineCount = 0;

        for (Server server : servers) {
            Map<String, Object> node = new HashMap<>();
            node.put("address", server.getIp());
            node.put("name", server.getName());
            
            boolean isOnline = server.getStatus() == 1;
            node.put("status", isOnline ? "在线" : "离线");
            
            if (isOnline) {
                onlineCount++;
            }
            
            nodeStatus.add(node);
        }

        status.put("nodes", nodeStatus);
        status.put("onlineCount", onlineCount);
        status.put("totalCount", servers.size());
        status.put("healthy", onlineCount == servers.size());

        return Result.success(status);
    }

    /**
     * 停止集群
     */
    public Result<String> stopCluster(Long groupId) {
        return Result.success("功能已移至 ClusterDeployService");
    }

    /**
     * 启动集群
     */
    public Result<String> startCluster(Long groupId) {
        return Result.success("功能已移至 ClusterDeployService");
    }

    /**
     * 卸载集群
     */
    @Transactional
    public Result<String> uninstallCluster(Long groupId) {
        return Result.success("功能已移至 ClusterDeployService");
    }

    /**
     * 部署集群（旧版，建议使用新的ClusterDeployService）
     */
    @Transactional
    public Result<String> deployCluster(Long groupId) {
        Optional<ServerGroup> opt = groupRepository.findById(groupId);
        if (!opt.isPresent()) {
            return Result.error("服务器组不存在");
        }

        ServerGroup group = opt.get();
        List<Server> servers = group.getServers();
        
        if (servers.size() < 2) {
            return Result.error("服务器数量必须至少2台（用于创建2×2集群）");
        }

        return Result.success("请使用新的集群管理功能创建集群");
    }
}
