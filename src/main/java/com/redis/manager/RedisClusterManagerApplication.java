package com.redis.manager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.redis.manager.config.BackupProperties;

/**
 * Redis Cluster Manager 应用程序入口
 * 
 * @author Redis Manager
 * @version 1.0.0
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(BackupProperties.class)
public class RedisClusterManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedisClusterManagerApplication.class, args);
        System.out.println("===============================================");
        System.out.println("  缓存3×3英雄 启动成功");
        System.out.println("  访问地址: http://localhost:8080");
        System.out.println("  H2控制台: http://localhost:8080/h2-console");
        System.out.println("===============================================");
    }
}
