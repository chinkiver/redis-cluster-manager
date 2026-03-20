package com.redis.manager.repository;

import com.redis.manager.entity.RedisInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RedisInstanceRepository extends JpaRepository<RedisInstance, Long> {

    /**
     * 根据服务器ID查询实例
     */
    List<RedisInstance> findByServerId(Long serverId);

    /**
     * 根据服务器ID和端口查询
     */
    Optional<RedisInstance> findByServerIdAndPort(Long serverId, Integer port);

    /**
     * 根据状态查询实例
     */
    List<RedisInstance> findByStatus(Integer status);

    /**
     * 根据集群节点ID查询
     */
    Optional<RedisInstance> findByClusterNodeId(String clusterNodeId);

    /**
     * 查询所有运行中的实例
     */
    @Query("SELECT r FROM RedisInstance r WHERE r.status = 1")
    List<RedisInstance> findAllRunning();

    /**
     * 查询指定集群的实例
     */
    List<RedisInstance> findByClusterId(Long clusterId);

    /**
     * 查询指定服务器的实例
     */
    List<RedisInstance> findByServerIdOrderByPortAsc(Long serverId);

    /**
     * 统计集群内实例数量
     */
    long countByClusterId(Long clusterId);
}
