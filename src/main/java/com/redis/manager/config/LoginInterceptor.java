package com.redis.manager.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.manager.dto.Result;
import com.redis.manager.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Autowired
    private UserService userService;

    private static final List<String> WHITE_LIST = Arrays.asList(
        "/login",
        "/api/users/login",
        "/api/users/logout",
        "/api/users/health",
        "/api/system/config/display",  // 系统展示配置公开接口
        "/vendor/",
        "/css/",
        "/js/",
        "/images/",
        "/upload/",  // 上传的图片资源（旧路径兼容）
        "/uploads/",  // 上传的图片资源（新路径）
        "/fonts/",
        "/webjars/",
        "/assets/",
        "/favicon.ico",
        "/Redis.png",
        "/error"
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();

        // 白名单放行
        for (String whitePath : WHITE_LIST) {
            if (uri.startsWith(whitePath) || uri.equals(whitePath)) {
                return true;
            }
        }

        // 检查是否已登录
        if (userService.isLoggedIn(request)) {
            return true;
        }

        // AJAX请求返回JSON
        if (isAjaxRequest(request)) {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(401);
            PrintWriter writer = response.getWriter();
            writer.write(new ObjectMapper().writeValueAsString(Result.error("未登录或登录已过期")));
            writer.flush();
        } else {
            // 页面请求重定向到登录页
            response.sendRedirect("/login");
        }
        return false;
    }

    private boolean isAjaxRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        String xRequestedWith = request.getHeader("X-Requested-With");
        return (accept != null && accept.contains("application/json")) ||
               "XMLHttpRequest".equalsIgnoreCase(xRequestedWith);
    }
}
