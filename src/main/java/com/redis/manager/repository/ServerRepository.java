package com.redis.manager.repository;

import com.redis.manager.entity.Server;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ServerRepository extends JpaRepository<Server, Long> {

    /**
     * 根据组ID查询服务器
     */
    List<Server> findByGroupId(Long groupId);

    /**
     * 根据IP查询服务器
     */
    List<Server> findByIp(String ip);

    /**
     * 根据状态查询服务器
     */
    List<Server> findByStatus(Integer status);

    /**
     * 查询组内服务器数量
     */
    long countByGroupId(Long groupId);
}
