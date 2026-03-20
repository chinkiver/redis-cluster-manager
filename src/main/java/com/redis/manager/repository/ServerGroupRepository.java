package com.redis.manager.repository;

import com.redis.manager.entity.ServerGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ServerGroupRepository extends JpaRepository<ServerGroup, Long> {

    /**
     * 根据名称查询组
     */
    Optional<ServerGroup> findByName(String name);

    /**
     * 查询所有组，包含服务器
     */
    @Query("SELECT DISTINCT g FROM ServerGroup g LEFT JOIN FETCH g.servers ORDER BY g.createTime DESC")
    List<ServerGroup> findAllWithServers();

    /**
     * 根据ID查询组，包含服务器
     */
    @Query("SELECT g FROM ServerGroup g LEFT JOIN FETCH g.servers WHERE g.id = :id")
    Optional<ServerGroup> findByIdWithServers(Long id);

    /**
     * 检查名称是否存在
     */
    boolean existsByName(String name);

    /**
     * 查询默认服务器组
     */
    Optional<ServerGroup> findByIsDefault(Integer isDefault);

    /**
     * 查询所有启用的服务器组
     */
    List<ServerGroup> findByIsDefaultOrderByCreateTimeDesc(Integer isDefault);
}
