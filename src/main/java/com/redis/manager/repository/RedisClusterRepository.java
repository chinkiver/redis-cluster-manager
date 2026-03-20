package com.redis.manager.repository;

import com.redis.manager.entity.RedisCluster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RedisClusterRepository extends JpaRepository<RedisCluster, Long> {

    /**
     * 查询所有集群，包含节点信息
     */
    @Query("SELECT DISTINCT c FROM RedisCluster c LEFT JOIN FETCH c.nodes ORDER BY c.basePort DESC")
    List<RedisCluster> findAllWithNodes();

    /**
     * 根据ID查询集群，包含节点信息
     */
    @Query("SELECT c FROM RedisCluster c LEFT JOIN FETCH c.nodes WHERE c.id = :id")
    Optional<RedisCluster> findByIdWithNodes(Long id);

    /**
     * 根据服务器组ID查询集群
     */
    List<RedisCluster> findByServerGroupId(Long groupId);

    /**
     * 根据名称查询集群
     */
    Optional<RedisCluster> findByName(String name);

    /**
     * 检查名称是否存在
     */
    boolean existsByName(String name);

    /**
     * 检查服务器组是否已创建集群
     */
    boolean existsByServerGroupId(Long groupId);

    /**
     * 统计服务器组关联的集群数量
     */
    long countByServerGroupId(Long groupId);

    /**
     * 根据集群类型统计数量
     */
    long countByClusterType(Integer clusterType);

    /**
     * 根据服务器组ID和集群类型统计数量
     */
    long countByServerGroupIdAndClusterType(Long groupId, Integer clusterType);

    /**
     * 根据ID查询集群，包含详细信息（别名方法）
     */
    default Optional<RedisCluster> findByIdWithDetails(Long id) {
        return findByIdWithNodes(id);
    }

    /**
     * 查询所有集群，包含实例信息（别名方法）
     */
    default List<RedisCluster> findAllWithInstances() {
        return findAllWithNodes();
    }
}
