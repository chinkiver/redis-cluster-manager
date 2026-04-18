package com.redis.manager.service;

import com.redis.manager.config.BackupProperties;
import com.redis.manager.dto.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 数据库备份服务
 * <p>
 * 提供 H2 数据库文件的定时备份功能，支持：
 * - 每日定时自动备份（系统备份）
 * - 手动触发备份（人工备份）
 * - 自动清理超过保留期的备份文件
 * - 备份文件完整性验证
 * 
 * @author Redis Manager
 * @version 1.1.0
 */
@Service
public class DatabaseBackupService {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseBackupService.class);
    private static final DateTimeFormatter BACKUP_FILENAME_FORMATTER = 
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String BACKUP_FILE_PREFIX = "redis-manager-backup-";
    private static final String BACKUP_FILE_SUFFIX = ".mv.db";
    
    // 备份来源类型
    public static final String SOURCE_SYSTEM = "SYSTEM";  // 系统自动备份
    public static final String SOURCE_MANUAL = "MANUAL";  // 人工手动备份
    
    // 文件名正则表达式：redis-manager-backup-(SYSTEM|MANUAL)-timestamp.mv.db
    private static final Pattern BACKUP_FILENAME_PATTERN = 
            Pattern.compile(BACKUP_FILE_PREFIX + "(SYSTEM|MANUAL)-(.+)" + BACKUP_FILE_SUFFIX);

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
            
            // 根据备份模式输出不同的日志
            if (backupProperties.isUseCronJob()) {
                String tokenStatus = org.springframework.util.StringUtils.hasText(backupProperties.getApiToken()) 
                        ? "已配置" : "未配置";
                logger.info("数据库备份服务初始化完成 [Crontab模式]，备份目录: {}，保留天数: {} 天，API Token: {}，服务器时区: {}", 
                        backupDir.toAbsolutePath(), 
                        backupProperties.getRetainDays(),
                        tokenStatus,
                        java.time.ZoneId.systemDefault());
                logger.info("Crontab 配置示例: 0 2 * * * curl -X POST \"http://localhost:8080/api/backup/cron?token=YOUR_TOKEN\"");
            } else {
                logger.info("数据库备份服务初始化完成 [应用内定时任务]，备份目录: {}，保留天数: {} 天，执行时间: {}，服务器时区: {}", 
                        backupDir.toAbsolutePath(), 
                        backupProperties.getRetainDays(),
                        backupProperties.getCron(),
                        java.time.ZoneId.systemDefault());
            }
        } catch (IOException e) {
            logger.error("初始化备份目录失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 执行定时备份任务（系统备份）
     * 默认每天凌晨 2 点执行
     * 当 useCronJob=true 时，禁用此定时任务，改由外部 Crontab 触发
     */
    @Scheduled(cron = "${redis.manager.backup.cron:0 0 2 * * ?}")
    public void performBackup() {
        // 如果配置了使用外部 Crontab，则跳过应用内定时任务
        if (backupProperties.isUseCronJob()) {
            logger.debug("应用内定时任务已禁用，当前使用外部 Crontab 触发备份");
            return;
        }
        logger.info("【定时任务触发】系统备份任务被触发，当前时间: {}", 
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        performBackupInternal(SOURCE_SYSTEM);
    }

    /**
     * 供外部 Crontab 调用的系统备份方法
     * 
     * @param token 安全验证 Token
     * @return 备份结果信息
     */
    public String triggerSystemBackup(String token) {
        // 验证 Token
        String configuredToken = backupProperties.getApiToken();
        if (!StringUtils.hasText(configuredToken)) {
            logger.error("【Crontab备份】API Token 未配置，拒绝执行备份");
            return "ERROR: API Token 未配置";
        }
        if (!configuredToken.equals(token)) {
            logger.warn("【Crontab备份】API Token 验证失败");
            return "ERROR: API Token 验证失败";
        }
        
        logger.info("【Crontab触发】系统备份任务被外部Crontab触发，当前时间: {}", 
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        
        // 检查今天是否已有系统备份
        if (hasSystemBackupToday()) {
            logger.info("【Crontab触发】今天已有系统备份，跳过重复备份");
            return "SUCCESS: 今天已有系统备份，跳过重复备份";
        }
        
        String result = performBackupInternal(SOURCE_SYSTEM);
        return result != null ? "SUCCESS: " + result : "SUCCESS: 系统备份完成";
    }

    /**
     * 检查今天是否已有系统备份
     */
    private boolean hasSystemBackupToday() {
        LocalDateTime now = LocalDateTime.now();
        String todayPrefix = BACKUP_FILE_PREFIX + SOURCE_SYSTEM + "-" + 
                now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        
        Path backupDir = Paths.get(backupProperties.getDirectory());
        if (!Files.exists(backupDir)) {
            return false;
        }
        
        try {
            return Files.walk(backupDir, 1)
                    .filter(Files::isRegularFile)
                    .anyMatch(path -> path.getFileName().toString().startsWith(todayPrefix));
        } catch (IOException e) {
            logger.warn("检查今日备份状态失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 手动触发备份（人工备份）
     * 
     * @return 备份结果信息
     */
    public String manualBackup() {
        return performBackupInternal(SOURCE_MANUAL);
    }

    /**
     * 内部备份方法
     * 
     * @param source 备份来源：SYSTEM 或 MANUAL
     * @return 备份结果信息（手动备份时返回描述，系统备份时返回null）
     */
    private String performBackupInternal(String source) {
        if (!backupProperties.isEnabled()) {
            logger.info("备份功能已禁用，跳过[{}]备份", source);
            return source.equals(SOURCE_MANUAL) ? "备份功能已禁用" : null;
        }

        String sourceName = source.equals(SOURCE_SYSTEM) ? "系统" : "人工";
        LocalDateTime now = LocalDateTime.now();
        logger.info("【备份执行】开始执行[{}]数据库备份任务，当前时间: {}，配置的cron: {}", 
                sourceName, 
                now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                backupProperties.getCron());
        
        try {
            // 1. 检查数据库文件是否存在
            Path sourceFile = Paths.get(backupProperties.getDatabaseFile());
            if (!Files.exists(sourceFile)) {
                logger.error("数据库文件不存在: {}", sourceFile.toAbsolutePath());
                return source.equals(SOURCE_MANUAL) ? "数据库文件不存在" : null;
            }

            // 2. 检查磁盘空间（至少保留 100MB 可用空间）
            if (!checkDiskSpace()) {
                logger.error("磁盘空间不足，跳过[{}]备份", sourceName);
                return source.equals(SOURCE_MANUAL) ? "磁盘空间不足" : null;
            }

            // 3. 生成备份文件名（包含来源标记）
            String timestamp = LocalDateTime.now().format(BACKUP_FILENAME_FORMATTER);
            String backupFilename = BACKUP_FILE_PREFIX + source + "-" + timestamp + BACKUP_FILE_SUFFIX;
            Path backupDir = Paths.get(backupProperties.getDirectory());
            Path backupFile = backupDir.resolve(backupFilename);

            // 4. 确保备份目录存在
            if (!Files.exists(backupDir)) {
                Files.createDirectories(backupDir);
            }

            // 5. 复制数据库文件
            logger.info("[{}]正在复制数据库文件到: {}", sourceName, backupFile.toAbsolutePath());
            long startTime = System.currentTimeMillis();
            Files.copy(sourceFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
            long duration = System.currentTimeMillis() - startTime;

            // 6. 验证备份文件
            if (!verifyBackupFile(backupFile)) {
                logger.error("[{}]备份文件验证失败，删除无效备份: {}", sourceName, backupFile.toAbsolutePath());
                Files.deleteIfExists(backupFile);
                return source.equals(SOURCE_MANUAL) ? "备份文件验证失败" : null;
            }

            long backupSize = Files.size(backupFile);
            logger.info("[{}]数据库备份成功: {}，大小: {} MB，耗时: {} ms", 
                    sourceName,
                    backupFilename, 
                    String.format("%.2f", backupSize / 1024.0 / 1024.0),
                    duration);

            // 7. 清理旧备份（只在系统备份时执行，避免频繁清理）
            if (source.equals(SOURCE_SYSTEM)) {
                int deletedCount = cleanupOldBackups();
                if (deletedCount > 0) {
                    logger.info("清理了 {} 个过期备份文件", deletedCount);
                }
            }

            return source.equals(SOURCE_MANUAL) ? "备份成功: " + backupFilename : null;

        } catch (Exception e) {
            logger.error("[{}]数据库备份失败: {}", sourceName, e.getMessage(), e);
            return source.equals(SOURCE_MANUAL) ? "备份失败: " + e.getMessage() : null;
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
                            String filename = path.getFileName().toString();
                            
                            // 解析文件名获取来源
                            String source = parseBackupSource(filename);
                            
                            backups.add(new BackupInfo(
                                    filename,
                                    Files.size(path),
                                    attrs.creationTime().toMillis(),
                                    source
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
     * 从文件名解析备份来源
     * 
     * @param filename 备份文件名
     * @return 来源：SYSTEM、MANUAL 或 UNKNOWN
     */
    private String parseBackupSource(String filename) {
        Matcher matcher = BACKUP_FILENAME_PATTERN.matcher(filename);
        if (matcher.matches()) {
            String source = matcher.group(1);
            return source != null ? source : "UNKNOWN";
        }
        // 兼容旧版文件名格式（没有来源标记）
        return "SYSTEM";
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
        private String source;  // 备份来源：SYSTEM 或 MANUAL

        public BackupInfo(String filename, long size, long createTime) {
            this(filename, size, createTime, "SYSTEM");
        }

        public BackupInfo(String filename, long size, long createTime, String source) {
            this.filename = filename;
            this.size = size;
            this.createTime = createTime;
            this.source = source;
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

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
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

        /**
         * 获取来源中文描述
         */
        public String getSourceDesc() {
            if ("MANUAL".equals(source)) {
                return "人工";
            } else if ("SYSTEM".equals(source)) {
                return "系统";
            }
            return source;
        }

        /**
         * 是否为人工备份
         */
        public boolean isManual() {
            return "MANUAL".equals(source);
        }
    }
}
