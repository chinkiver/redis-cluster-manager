package com.redis.manager.service;

import com.redis.manager.dto.Result;
import com.redis.manager.entity.User;
import com.redis.manager.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class UserService implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);
    private static final String SESSION_USER_KEY = "currentUser";

    @Autowired
    private UserRepository userRepository;

    /**
     * 初始化默认管理员用户 admin/admin
     */
    @Override
    public void run(String... args) {
        try {
            if (!userRepository.existsByUsername("admin")) {
                User admin = new User();
                admin.setUsername("admin");
                admin.setPassword(encryptPassword("admin"));
                admin.setNickname("管理员");
                admin.setRole(1); // 管理员
                admin.setStatus(1); // 启用
                userRepository.save(admin);
                logger.info("初始化默认管理员用户: admin/admin");
            }
        } catch (Exception e) {
            logger.error("初始化默认用户失败", e);
        }
    }

    /**
     * 用户登录 - 仅支持 admin 用户
     */
    public Result<Map<String, Object>> login(String username, String password, HttpServletRequest request) {
        if (username == null || username.trim().isEmpty()) {
            return Result.error("用户名不能为空");
        }
        if (password == null || password.isEmpty()) {
            return Result.error("密码不能为空");
        }

        // 仅支持 admin 用户登录
        if (!"admin".equals(username)) {
            return Result.error("用户名或密码错误");
        }

        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return Result.error("用户名或密码错误");
        }

        if (!user.isEnabled()) {
            return Result.error("用户已被禁用");
        }

        if (!user.getPassword().equals(encryptPassword(password))) {
            return Result.error("用户名或密码错误");
        }

        // 更新登录信息
        user.setLastLoginTime(LocalDateTime.now());
        user.setLastLoginIp(getClientIp(request));
        userRepository.save(user);

        // 存入Session
        HttpSession session = request.getSession();
        session.setAttribute(SESSION_USER_KEY, user);
        session.setMaxInactiveInterval(3600); // 1小时有效期

        Map<String, Object> result = new HashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("nickname", user.getNickname());
        result.put("role", user.getRole());
        result.put("isAdmin", user.isAdmin());

        logger.info("管理员登录成功: {}, IP: {}", username, user.getLastLoginIp());
        return Result.success(result);
    }

    /**
     * 用户注销
     */
    public Result<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            User user = (User) session.getAttribute(SESSION_USER_KEY);
            if (user != null) {
                logger.info("用户注销: {}", user.getUsername());
            }
            session.invalidate();
        }
        return Result.success(null);
    }

    /**
     * 获取当前登录用户
     */
    public User getCurrentUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            return (User) session.getAttribute(SESSION_USER_KEY);
        }
        return null;
    }

    /**
     * 检查用户是否已登录
     */
    public boolean isLoggedIn(HttpServletRequest request) {
        return getCurrentUser(request) != null;
    }

    /**
     * 密码加密（MD5）
     */
    private String encryptPassword(String password) {
        return DigestUtils.md5DigestAsHex(password.getBytes());
    }

    /**
     * 获取客户端IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip != null && ip.contains(",") ? ip.split(",")[0].trim() : ip;
    }

    /**
     * 修改密码
     * @param userId 用户ID
     * @param oldPassword 旧密码
     * @param newPassword 新密码
     * @return 操作结果
     */
    @Transactional
    public Result<Void> changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return Result.error("用户不存在");
        }
        
        // 验证旧密码
        if (!user.getPassword().equals(encryptPassword(oldPassword))) {
            return Result.error("当前密码错误");
        }
        
        // 更新密码
        user.setPassword(encryptPassword(newPassword));
        userRepository.save(user);
        
        logger.info("用户修改密码成功: {}", user.getUsername());
        return Result.success(null);
    }
}
