package com.redis.manager.controller;

import com.redis.manager.dto.Result;
import com.redis.manager.entity.User;
import com.redis.manager.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserService userService;

    /**
     * 健康检查接口（无需登录）
     */
    @GetMapping("/health")
    public Result<String> health() {
        return Result.success("服务正常运行");
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> params, HttpServletRequest request) {
        try {
            String username = params.get("username");
            String password = params.get("password");
            return userService.login(username, password, request);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error("登录处理异常: " + e.getMessage());
        }
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        return userService.logout(request);
    }

    @GetMapping("/current")
    public Result<Map<String, Object>> getCurrentUser(HttpServletRequest request) {
        User user = userService.getCurrentUser(request);
        if (user == null) {
            return Result.error("未登录");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("nickname", user.getNickname() != null ? user.getNickname() : user.getUsername());
        result.put("role", user.getRole());
        result.put("isAdmin", user.isAdmin());
        return Result.success(result);
    }

    /**
     * 修改密码
     */
    @PostMapping("/change-password")
    public Result<Void> changePassword(@RequestBody Map<String, String> params, HttpServletRequest request) {
        User user = userService.getCurrentUser(request);
        if (user == null) {
            return Result.error("未登录");
        }
        
        String oldPassword = params.get("oldPassword");
        String newPassword = params.get("newPassword");
        
        if (oldPassword == null || oldPassword.isEmpty()) {
            return Result.error("请输入当前密码");
        }
        if (newPassword == null || newPassword.isEmpty()) {
            return Result.error("请输入新密码");
        }
        if (newPassword.length() < 6) {
            return Result.error("新密码至少需要6个字符");
        }
        
        return userService.changePassword(user.getId(), oldPassword, newPassword);
    }
}
