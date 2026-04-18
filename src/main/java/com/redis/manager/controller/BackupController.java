package com.redis.manager.controller;

import com.redis.manager.config.BackupProperties;
import com.redis.manager.dto.PageResult;
import com.redis.manager.dto.Result;
import com.redis.manager.service.DatabaseBackupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库备份管理控制器
 * <p>
 * 提供备份管理相关的 REST API：
 * - 手动触发备份
 * - 查看备份列表
 * - 清理过期备份
 * 
 * @author Redis Manager
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api/backup")
public class BackupController {

    private static final Logger logger = LoggerFactory.getLogger(BackupController.class);

    @Autowired
    private DatabaseBackupService backupService;

    @Autowired
    private BackupProperties backupProperties;

    /**
     * 手动触发数据库备份
     * 
     * @return 备份结果
     */
    @PostMapping("/manual")
    public Result<String> manualBackup() {
        logger.info("管理员手动触发数据库备份");
        String result = backupService.manualBackup();
        
        if (result.startsWith("备份成功")) {
            return Result.success(result);
        } else {
            return Result.error(500, result);
        }
    }

    /**
     * 供外部 Crontab 调用的系统备份接口
     * <p>
     * 使用方式（Linux Crontab）：
     * 0 2 * * * curl -X POST "http://localhost:8080/api/backup/cron?token=YOUR_TOKEN"
     * 
     * @param token 安全验证 Token（需与配置 redis.manager.backup.api-token 一致）
     * @return 备份结果（纯文本，便于日志记录）
     */
    @PostMapping("/cron")
    @ResponseBody
    public String cronBackup(@RequestParam("token") String token) {
        logger.info("【Crontab请求】收到外部Crontab备份请求");
        
        // 检查是否启用了 Crontab 模式
        if (!backupProperties.isUseCronJob()) {
            logger.warn("【Crontab请求】当前未启用 Crontab 模式，请设置 redis.manager.backup.use-cron-job=true");
            return "ERROR: 未启用 Crontab 模式";
        }
        
        // 检查 Token 是否配置
        if (!org.springframework.util.StringUtils.hasText(backupProperties.getApiToken())) {
            logger.error("【Crontab请求】API Token 未配置，请设置 redis.manager.backup.api-token");
            return "ERROR: API Token 未配置";
        }
        
        // 执行备份
        String result = backupService.triggerSystemBackup(token);
        
        // 记录结果到日志
        if (result.startsWith("SUCCESS")) {
            logger.info("【Crontab备份】执行结果: {}", result);
        } else {
            logger.error("【Crontab备份】执行结果: {}", result);
        }
        
        return result;
    }

    /**
     * 获取备份列表（分页）
     * 
     * @param page 页码（从1开始）
     * @param size 每页大小（默认10）
     * @return 分页备份文件列表
     */
    @GetMapping("/list")
    public Result<PageResult<DatabaseBackupService.BackupInfo>> getBackupList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageResult<DatabaseBackupService.BackupInfo> result = backupService.getBackupList(page, size);
        return Result.success(result);
    }

    /**
     * 删除指定备份文件
     * 
     * @param filename 备份文件名
     * @return 删除结果
     */
    @PostMapping("/delete")
    public Result<String> deleteBackup(@RequestParam String filename) {
        logger.info("管理员删除备份文件: {}", filename);
        boolean success = backupService.deleteBackup(filename);
        if (success) {
            return Result.success("删除成功: " + filename);
        } else {
            return Result.error(500, "删除失败: " + filename);
        }
    }

    /**
     * 清理过期备份文件
     * 
     * @return 清理结果
     */
    @PostMapping("/cleanup")
    public Result<Map<String, Object>> cleanupOldBackups() {
        int deletedCount = backupService.cleanupOldBackups();
        
        Map<String, Object> result = new HashMap<>();
        result.put("deletedCount", deletedCount);
        result.put("message", "成功清理 " + deletedCount + " 个过期备份文件");
        
        logger.info("管理员手动清理备份文件，删除 {} 个文件", deletedCount);
        return Result.success(result);
    }

    /**
     * 获取备份统计信息
     * 
     * @return 备份统计
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> getBackupStats() {
        List<DatabaseBackupService.BackupInfo> backups = backupService.getBackupList();
        
        long totalSize = backups.stream().mapToLong(DatabaseBackupService.BackupInfo::getSize).sum();
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("backupCount", backups.size());
        stats.put("totalSize", totalSize);
        stats.put("formattedTotalSize", formatSize(totalSize));
        
        if (!backups.isEmpty()) {
            stats.put("latestBackup", backups.get(0).getFilename());
            stats.put("latestBackupTime", backups.get(0).getCreateTime());
        }
        
        return Result.success(stats);
    }

    /**
     * 格式化文件大小
     */
    private String formatSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.2f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", size / 1024.0 / 1024.0);
        } else {
            return String.format("%.2f GB", size / 1024.0 / 1024.0 / 1024.0);
        }
    }
}
