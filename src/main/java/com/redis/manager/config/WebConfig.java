package com.redis.manager.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web配置类
 * 配置静态资源和CORS
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private LoginInterceptor loginInterceptor;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 确保static目录下的资源可以被访问
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/", "classpath:/public/")
                .setCachePeriod(3600);
        
        // 配置上传文件的静态资源访问
        String uploadPath = "file:" + System.getProperty("user.dir") + "/uploads/images/";
        registry.addResourceHandler("/uploads/images/**")
                .addResourceLocations(uploadPath)
                .setCachePeriod(0);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                    "/login",
                    "/api/users/login",
                    "/api/users/logout",
                    "/api/users/health",
                    "/api/system/config/display",
                    "/vendor/**",
                    "/css/**",
                    "/js/**",
                    "/images/**",
                    "/upload/**",
                    "/uploads/**",
                    "/fonts/**",
                    "/webjars/**",
                    "/assets/**",
                    "/favicon.ico",
                    "/Redis.png",
                    "/error"
                );
    }
}
