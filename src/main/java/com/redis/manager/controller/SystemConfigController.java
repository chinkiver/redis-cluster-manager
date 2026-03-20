package com.redis.manager.controller;

import com.redis.manager.dto.Result;
import com.redis.manager.entity.SystemConfig;
import com.redis.manager.service.SystemConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemConfigController {

    @Autowired
    private SystemConfigService systemConfigService;

    /**
     * 获取系统展示配置（公开接口，无需登录）
     */
    @GetMapping("/config/display")
    public Result<Map<String, Object>> getDisplayConfig() {
        return Result.success(systemConfigService.getSystemDisplayConfig());
    }

    /**
     * 获取所有配置（需要登录）
     */
    @GetMapping("/config")
    public Result<Map<String, String>> getAllConfigs() {
        return Result.success(systemConfigService.getAllConfigs());
    }

    /**
     * 获取配置项列表（包含详细信息）
     */
    @GetMapping("/config/list")
    public Result<List<SystemConfig>> getConfigList() {
        return Result.success(systemConfigService.getAllConfigEntities());
    }

    /**
     * 更新单个配置
     */
    @PostMapping("/config")
    public Result<SystemConfig> updateConfig(@RequestBody Map<String, String> params) {
        String key = params.get("key");
        String value = params.get("value");
        String description = params.get("description");

        if (key == null || key.trim().isEmpty()) {
            return Result.error("配置键不能为空");
        }

        SystemConfig config = systemConfigService.saveOrUpdate(key, value, description);
        return Result.success(config);
    }

    /**
     * 批量更新配置
     */
    @PostMapping("/config/batch")
    public Result<Void> batchUpdateConfig(@RequestBody Map<String, String> configs) {
        if (configs == null || configs.isEmpty()) {
            return Result.error("配置不能为空");
        }
        systemConfigService.batchUpdate(configs);
        return Result.success();
    }

    /**
     * 删除配置
     */
    @DeleteMapping("/config/{key}")
    public Result<Void> deleteConfig(@PathVariable String key) {
        systemConfigService.deleteByKey(key);
        return Result.success();
    }

    /**
     * 重置配置为默认值
     */
    @PostMapping("/config/reset/{key}")
    public Result<Void> resetConfig(@PathVariable String key) {
        systemConfigService.resetToDefault(key);
        return Result.success();
    }

    /**
     * 重置所有配置
     */
    @PostMapping("/config/reset-all")
    public Result<Void> resetAllConfig() {
        systemConfigService.resetAllToDefault();
        return Result.success();
    }

    /**
     * 刷新配置缓存
     */
    @PostMapping("/config/refresh")
    public Result<Void> refreshCache() {
        systemConfigService.refreshCache();
        return Result.success();
    }

    /**
     * 上传Logo或Icon
     */
    @PostMapping("/upload/logo")
    public Result<Map<String, String>> uploadLogo(@RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                                   @RequestParam(value = "type", defaultValue = "logo") String type) {
        try {
            if (file.isEmpty()) {
                return Result.error("请选择要上传的文件");
            }

            // 检查文件类型
            String contentType = file.getContentType();
            if (contentType == null || (!contentType.startsWith("image/png") && !contentType.startsWith("image/jpeg") 
                    && !contentType.startsWith("image/gif") && !contentType.startsWith("image/svg+xml"))) {
                return Result.error("仅支持 PNG、JPEG、GIF、SVG 格式的图片");
            }

            // 检查文件大小 (最大 2MB)
            if (file.getSize() > 2 * 1024 * 1024) {
                return Result.error("文件大小不能超过 2MB");
            }

            // 生成文件名
            String originalFilename = file.getOriginalFilename();
            String ext = ".png";
            if (originalFilename != null && originalFilename.contains(".")) {
                ext = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String filename = "sys_" + type + "_" + System.currentTimeMillis() + ext;

            // 保存文件到运行目录下的 upload 文件夹
            java.nio.file.Path uploadDir = java.nio.file.Paths.get(System.getProperty("user.dir"), "uploads", "images");
            java.nio.file.Path uploadPath = uploadDir.resolve(filename);
            java.nio.file.Files.createDirectories(uploadDir);
            java.nio.file.Files.write(uploadPath, file.getBytes());

            // 返回访问路径
            String accessPath = "/uploads/images/" + filename;
            
            Map<String, String> result = new HashMap<>();
            result.put("path", accessPath);
            result.put("filename", filename);
            
            return Result.success(result);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error("上传失败: " + e.getMessage());
        }
    }
}
