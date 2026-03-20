package com.redis.manager.repository;

import com.redis.manager.entity.ClusterNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClusterNodeRepository extends JpaRepository<ClusterNode, Long> {

    /**
     * 根据集群ID查询节点
     */
    List<ClusterNode> findByClusterId(Long clusterId);

    /**
     * 根据集群ID和节点角色查询
     */
    List<ClusterNode> findByClusterIdAndNodeRole(Long clusterId, Integer nodeRole);

    /**
     * 根据节点角色查询所有节点（带server和cluster信息）
     */
    @Query("SELECT n FROM ClusterNode n LEFT JOIN FETCH n.server LEFT JOIN FETCH n.cluster c LEFT JOIN FETCH c.serverGroup g LEFT JOIN FETCH g.servers WHERE n.nodeRole = :nodeRole")
    List<ClusterNode> findByNodeRoleWithServer(Integer nodeRole);

    /**
     * 根据节点角色查询所有节点
     */
    List<ClusterNode> findByNodeRole(Integer nodeRole);

    /**
     * 根据集群的服务器组ID统计节点数量
     */
    @Query("SELECT COUNT(n) FROM ClusterNode n WHERE n.cluster.serverGroup.id = :groupId")
    long countByClusterServerGroupId(Long groupId);
}
