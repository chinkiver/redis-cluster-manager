package com.redis.manager.controller;

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
