package com.redis.manager.config;

import com.redis.manager.entity.RedisConfigTemplate;
import com.redis.manager.repository.RedisConfigTemplateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 配置模板初始化器
 * 
 * 注意：系统不再自动创建默认模板，所有模板都需要用户手动创建
 * 这样可以确保用户根据实际需求来配置模板
 */
@Component
public class TemplateInitializer implements CommandLineRunner {

    @Autowired
    private RedisConfigTemplateRepository templateRepository;

    @Override
    public void run(String... args) throws Exception {
        // 不再自动创建默认模板
        // 所有模板都需要用户在界面上手动创建
        // 这样可以确保用户充分了解并自定义自己的配置
    }
}
