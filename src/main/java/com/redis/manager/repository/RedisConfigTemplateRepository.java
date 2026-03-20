package com.redis.manager.repository;

import com.redis.manager.entity.RedisConfigTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RedisConfigTemplateRepository extends JpaRepository<RedisConfigTemplate, Long> {

    /**
     * 根据名称查询模板
     */
    Optional<RedisConfigTemplate> findByName(String name);

    /**
     * 查询默认模板（旧方法，保留兼容）
     */
    Optional<RedisConfigTemplate> findByIsDefaultTrue();

    /**
     * 根据Redis版本查询默认模板
     */
    Optional<RedisConfigTemplate> findByRedisVersionAndIsDefaultTrue(String redisVersion);

    /**
     * 根据Redis版本查询模板
     */
    List<RedisConfigTemplate> findByRedisVersion(String redisVersion);

    /**
     * 检查名称是否存在
     */
    boolean existsByName(String name);
}
