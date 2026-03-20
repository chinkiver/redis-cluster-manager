package com.redis.manager.service;

import com.redis.manager.config.BackupProperties;
import com.redis.manager.dto.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 数据库备份服务
 * <p>
 * 提供 H2 数据库文件的定时备份功能，支持：
 * - 每日定时自动备份
 * - 自动清理超过保留期的备份文件
 * - 备份文件完整性验证
 * 
 * @author Redis Manager
 * @version 1.0.0
 */
@Service
public class DatabaseBackupService {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseBackupService.class);
    private static final DateTimeFormatter BACKUP_FILENAME_FORMATTER = 
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String BACKUP_FILE_PREFIX = "redis-manager-backup-";
    private static final String BACKUP_FILE_SUFFIX = ".mv.db";

    @Autowired
    private BackupProperties backupProperties;

    /**
     * 初始化备份服务
     * 检查并创建备份目录
     */
    @PostConstruct
    public void init() {
        if (!backupProperties.isEnabled()) {
            logger.info("数据库备份功能已禁用");
            return;
        }

        try {
            Path backupDir = Paths.get(backupProperties.getDirectory());
            if (!Files.exists(backupDir)) {
                Files.createDirectories(backupDir);
                logger.info("创建备份目录: {}", backupDir.toAbsolutePath());
            }
            logger.info("数据库备份服务初始化完成，备份目录: {}，保留天数: {} 天，执行时间: {}", 
                    backupDir.toAbsolutePath(), 
                    backupProperties.getRetainDays(),
                    backupProperties.getCron());
        } catch (IOException e) {
            logger.error("初始化备份目录失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 执行定时备份任务
     * 默认每天凌晨 2 点执行
     */
    @Scheduled(cron = "${redis.manager.backup.cron:0 0 2 * * ?}")
    public void performBackup() {
        if (!backupProperties.isEnabled()) {
            return;
        }

        logger.info("开始执行数据库备份任务...");
        
        try {
            // 1. 检查数据库文件是否存在
            Path sourceFile = Paths.get(backupProperties.getDatabaseFile());
            if (!Files.exists(sourceFile)) {
                logger.error("数据库文件不存在: {}", sourceFile.toAbsolutePath());
                return;
            }

            // 2. 检查磁盘空间（至少保留 100MB 可用空间）
            if (!checkDiskSpace()) {
                logger.error("磁盘空间不足，跳过本次备份");
                return;
            }

            // 3. 生成备份文件名
            String timestamp = LocalDateTime.now().format(BACKUP_FILENAME_FORMATTER);
            String backupFilename = BACKUP_FILE_PREFIX + timestamp + BACKUP_FILE_SUFFIX;
            Path backupDir = Paths.get(backupProperties.getDirectory());
            Path backupFile = backupDir.resolve(backupFilename);

            // 4. 确保备份目录存在
            if (!Files.exists(backupDir)) {
                Files.createDirectories(backupDir);
            }

            // 5. 复制数据库文件
            logger.info("正在复制数据库文件到: {}", backupFile.toAbsolutePath());
            long startTime = System.currentTimeMillis();
            Files.copy(sourceFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
            long duration = System.currentTimeMillis() - startTime;

            // 6. 验证备份文件
            if (!verifyBackupFile(backupFile)) {
                logger.error("备份文件验证失败，删除无效备份: {}", backupFile.toAbsolutePath());
                Files.deleteIfExists(backupFile);
                return;
            }

            long backupSize = Files.size(backupFile);
            logger.info("数据库备份成功: {}，大小: {} MB，耗时: {} ms", 
                    backupFilename, 
                    String.format("%.2f", backupSize / 1024.0 / 1024.0),
                    duration);

            // 7. 清理旧备份
            int deletedCount = cleanupOldBackups();
            if (deletedCount > 0) {
                logger.info("清理了 {} 个过期备份文件", deletedCount);
            }

        } catch (Exception e) {
            logger.error("数据库备份失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 手动触发备份
     * 可用于管理员手动执行备份
     * 
     * @return 备份结果信息
     */
    public String manualBackup() {
        if (!backupProperties.isEnabled()) {
            return "备份功能已禁用";
        }

        try {
            Path sourceFile = Paths.get(backupProperties.getDatabaseFile());
            if (!Files.exists(sourceFile)) {
                return "数据库文件不存在";
            }

            String timestamp = LocalDateTime.now().format(BACKUP_FILENAME_FORMATTER);
            String backupFilename = BACKUP_FILE_PREFIX + timestamp + BACKUP_FILE_SUFFIX;
            Path backupDir = Paths.get(backupProperties.getDirectory());
            Path backupFile = backupDir.resolve(backupFilename);

            if (!Files.exists(backupDir)) {
                Files.createDirectories(backupDir);
            }

            Files.copy(sourceFile, backupFile, StandardCopyOption.REPLACE_EXISTING);

            if (!verifyBackupFile(backupFile)) {
                Files.deleteIfExists(backupFile);
                return "备份文件验证失败";
            }

            return "备份成功: " + backupFilename;
        } catch (Exception e) {
            logger.error("手动备份失败: {}", e.getMessage(), e);
            return "备份失败: " + e.getMessage();
        }
    }

    /**
     * 清理超过保留期的旧备份文件
     * 
     * @return 删除的文件数量
     */
    public int cleanupOldBackups() {
        int deletedCount = 0;
        Path backupDir = Paths.get(backupProperties.getDirectory());
        
        if (!Files.exists(backupDir)) {
            return deletedCount;
        }

        LocalDateTime now = LocalDateTime.now();
        int retainDays = backupProperties.getRetainDays();

        try {
            deletedCount = Files.walk(backupDir, 1)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(BACKUP_FILE_PREFIX))
                    .filter(path -> path.getFileName().toString().endsWith(BACKUP_FILE_SUFFIX))
                    .filter(path -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                            LocalDateTime fileTime = LocalDateTime.ofInstant(
                                    attrs.creationTime().toInstant(), 
                                    java.time.ZoneId.systemDefault());
                            long days = ChronoUnit.DAYS.between(fileTime, now);
                            return days > retainDays;
                        } catch (IOException e) {
                            logger.warn("无法读取文件属性: {}", path, e);
                            return false;
                        }
                    })
                    .mapToInt(path -> {
                        try {
                            Files.delete(path);
                            logger.debug("删除过期备份文件: {}", path.getFileName());
                            return 1;
                        } catch (IOException e) {
                            logger.warn("删除备份文件失败: {}", path, e);
                            return 0;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            logger.error("清理旧备份文件失败: {}", e.getMessage(), e);
        }

        return deletedCount;
    }

    /**
     * 获取备份列表信息（无分页）
     * 
     * @return 备份文件信息列表
     */
    public List<BackupInfo> getBackupList() {
        List<BackupInfo> backups = new ArrayList<>();
        Path backupDir = Paths.get(backupProperties.getDirectory());

        if (!Files.exists(backupDir)) {
            return backups;
        }

        try {
            Files.walk(backupDir, 1)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(BACKUP_FILE_PREFIX))
                    .filter(path -> path.getFileName().toString().endsWith(BACKUP_FILE_SUFFIX))
                    .forEach(path -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                            backups.add(new BackupInfo(
                                    path.getFileName().toString(),
                                    Files.size(path),
                                    attrs.creationTime().toMillis()
                            ));
                        } catch (IOException e) {
                            logger.warn("无法读取备份文件信息: {}", path, e);
                        }
                    });
        } catch (IOException e) {
            logger.error("获取备份列表失败: {}", e.getMessage(), e);
        }

        // 按创建时间倒序排序
        backups.sort((a, b) -> Long.compare(b.getCreateTime(), a.getCreateTime()));
        return backups;
    }

    /**
     * 获取备份列表信息（分页）
     * 
     * @param page 页码（从1开始）
     * @param size 每页大小
     * @return 分页结果
     */
    public PageResult<BackupInfo> getBackupList(int page, int size) {
        List<BackupInfo> allBackups = getBackupList();
        
        int total = allBackups.size();
        int start = (page - 1) * size;
        int end = Math.min(start + size, total);
        
        List<BackupInfo> pageList;
        if (start >= total) {
            pageList = new ArrayList<>();
        } else {
            pageList = allBackups.subList(start, end);
        }
        
        return new PageResult<>(page, size, total, pageList);
    }

    /**
     * 删除指定备份文件
     * 
     * @param filename 备份文件名
     * @return true 如果删除成功
     */
    public boolean deleteBackup(String filename) {
        // 验证文件名格式，防止路径穿越
        if (!filename.startsWith(BACKUP_FILE_PREFIX) || !filename.endsWith(BACKUP_FILE_SUFFIX)) {
            logger.error("非法的备份文件名: {}", filename);
            return false;
        }
        
        Path backupDir = Paths.get(backupProperties.getDirectory());
        Path backupFile = backupDir.resolve(filename);
        
        // 确保文件在备份目录下
        try {
            if (!backupFile.toRealPath().startsWith(backupDir.toRealPath())) {
                logger.error("文件不在备份目录中: {}", filename);
                return false;
            }
        } catch (IOException e) {
            logger.error("验证文件路径失败: {}", e.getMessage());
            return false;
        }
        
        try {
            if (!Files.exists(backupFile)) {
                logger.warn("备份文件不存在: {}", filename);
                return false;
            }
            
            Files.delete(backupFile);
            logger.info("删除备份文件成功: {}", filename);
            return true;
        } catch (IOException e) {
            logger.error("删除备份文件失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 检查磁盘空间
     * 
     * @return true 如果可用空间大于 100MB
     */
    private boolean checkDiskSpace() {
        try {
            Path backupDir = Paths.get(backupProperties.getDirectory());
            if (!Files.exists(backupDir)) {
                backupDir = backupDir.getParent();
            }
            long usableSpace = Files.getFileStore(backupDir).getUsableSpace();
            // 至少需要 100MB 可用空间
            return usableSpace > 100 * 1024 * 1024;
        } catch (IOException e) {
            logger.warn("检查磁盘空间失败: {}", e.getMessage());
            return true; // 如果检查失败，允许继续备份
        }
    }

    /**
     * 验证备份文件完整性
     * 
     * @param backupFile 备份文件路径
     * @return true 如果验证通过
     */
    private boolean verifyBackupFile(Path backupFile) {
        try {
            if (!Files.exists(backupFile)) {
                return false;
            }
            long size = Files.size(backupFile);
            // 文件大小必须大于 0
            return size > 0;
        } catch (IOException e) {
            logger.error("验证备份文件失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 备份文件信息类
     */
    public static class BackupInfo {
        private String filename;
        private long size;
        private long createTime;

        public BackupInfo(String filename, long size, long createTime) {
            this.filename = filename;
            this.size = size;
            this.createTime = createTime;
        }

        public String getFilename() {
            return filename;
        }

        public void setFilename(String filename) {
            this.filename = filename;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }

        public long getCreateTime() {
            return createTime;
        }

        public void setCreateTime(long createTime) {
            this.createTime = createTime;
        }

        /**
         * 获取格式化的文件大小
         */
        public String getFormattedSize() {
            if (size < 1024) {
                return size + " B";
            } else if (size < 1024 * 1024) {
                return String.format("%.2f KB", size / 1024.0);
            } else {
                return String.format("%.2f MB", size / 1024.0 / 1024.0);
            }
        }
    }
}
