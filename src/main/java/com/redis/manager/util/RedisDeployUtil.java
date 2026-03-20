package com.redis.manager.util;

import com.redis.manager.entity.Server;
import com.redis.manager.ssh.SSHClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Redis部署工具类
 * 封装Redis集群部署相关的静态操作（目录创建、配置文件上传、集群命令执行）
 * 注意：实例启动/停止等运行时操作由 ClusterService 直接控制
 */
public class RedisDeployUtil {

    private static final Logger logger = LoggerFactory.getLogger(RedisDeployUtil.class);

    /**
     * 检查目录是否存在且为空
     * @return true-目录不存在或为空，可以创建；false-目录存在且不为空
     */
    public static void checkDirEmpty(SSHClient ssh, String remoteDir) throws Exception {
        // 检查目录是否存在
        SSHClient.SSHResult result = ssh.executeCommand("test -d " + remoteDir + " && echo 'EXISTS' || echo 'NOT_EXISTS'");
        String existsStatus = result.getStdout().trim();
        
        if ("EXISTS".equals(existsStatus)) {
            // 目录存在，检查是否为空
            result = ssh.executeCommand("ls -A " + remoteDir + " | wc -l");
            int fileCount = 0;
            try {
                fileCount = Integer.parseInt(result.getStdout().trim());
            } catch (NumberFormatException e) {
                // 如果解析失败，假设目录不为空
                fileCount = 1;
            }
            
            if (fileCount > 0) {
                throw new RuntimeException("目录 " + remoteDir + " 已存在且不为空，请手动清理后重试");
            }
        }
    }

    /**
     * 检查目录写权限
     */
    public static void checkDirWritable(SSHClient ssh, String parentDir) throws Exception {
        // 先检查目录是否存在，不存在则尝试创建
        SSHClient.SSHResult result = ssh.executeCommand("test -d " + parentDir + " && echo 'EXISTS' || echo 'NOT_EXISTS'");
        String existsStatus = result.getStdout().trim();
        
        if ("NOT_EXISTS".equals(existsStatus)) {
            // 目录不存在，尝试创建
            result = ssh.executeCommand("mkdir -p " + parentDir + " && echo 'CREATED' || echo 'FAILED'");
            String createStatus = result.getStdout().trim();
            if (!"CREATED".equals(createStatus)) {
                throw new RuntimeException("无法创建目录 " + parentDir + "，请检查父目录权限");
            }
            return;
        }
        
        // 目录存在，检查写权限
        result = ssh.executeCommand("test -w " + parentDir + " && echo 'WRITABLE' || echo 'NOT_WRITABLE'");
        String writableStatus = result.getStdout().trim();
        
        if (!"WRITABLE".equals(writableStatus)) {
            throw new RuntimeException("没有权限在 " + parentDir + " 目录下创建文件夹，请检查目录权限");
        }
    }

    /**
     * 创建远程目录（带权限检查）
     */
    public static void createRemoteDir(SSHClient ssh, String remoteDir) throws Exception {
        SSHClient.SSHResult result = ssh.executeCommand("mkdir -p " + remoteDir);
        if (!result.isSuccess()) {
            throw new RuntimeException("创建目录失败: " + result.getStderr());
        }
        logger.info("创建远程目录成功: {}", remoteDir);
    }

    /**
     * 检查目录是否存在
     */
    public static boolean checkDirExists(SSHClient ssh, String remoteDir) throws Exception {
        SSHClient.SSHResult result = ssh.executeCommand("test -d " + remoteDir + " && echo 'EXISTS' || echo 'NOT_EXISTS'");
        return "EXISTS".equals(result.getStdout().trim());
    }

    /**
     * 创建Redis集群标准目录结构
     * 在 baseDir 下创建 ${PORT}/conf、data、nodes、logs 目录
     */
    public static void createClusterDirStructure(SSHClient ssh, String baseDir, int port, boolean forceClean) throws Exception {
        String portDir = baseDir + "/" + port;
        String[] subDirs = {"conf", "data", "nodes", "logs"};
        
        // 先检查父目录权限
        checkDirWritable(ssh, baseDir);
        
        // 检查端口目录是否存在
        if (checkDirExists(ssh, portDir)) {
            if (forceClean) {
                // 强制清理现有目录
                logger.warn("目录 {} 已存在，执行强制清理", portDir);
                ssh.executeCommand("rm -rf " + portDir + "/*");
            } else {
                // 检查是否为空
                checkDirEmpty(ssh, portDir);
            }
        }
        
        // 创建端口目录
        createRemoteDir(ssh, portDir);
        
        // 创建子目录
        for (String subDir : subDirs) {
            String fullPath = portDir + "/" + subDir;
            createRemoteDir(ssh, fullPath);
        }
        
        logger.info("创建集群目录结构成功: {}/{} (conf, data, nodes, logs)", baseDir, port);
    }

    /**
     * 上传并写入配置文件
     */
    public static void uploadConfigFile(SSHClient ssh, String configContent, String remotePath) throws Exception {
        ssh.uploadFileContent(configContent, remotePath);
        logger.info("配置文件上传成功: {}", remotePath);
    }

    /**
     * 检查Redis实例是否运行（默认连接本地）
     */
    public static boolean isRedisRunning(SSHClient ssh, int port) throws Exception {
        return isRedisRunning(ssh, "127.0.0.1", port, null);
    }
    
    /**
     * 检查Redis实例是否运行（默认连接本地，带密码）
     */
    public static boolean isRedisRunning(SSHClient ssh, int port, String password) throws Exception {
        return isRedisRunning(ssh, "127.0.0.1", port, password);
    }
    
    /**
     * 检查Redis实例是否运行（指定IP和端口）
     */
    public static boolean isRedisRunning(SSHClient ssh, String host, int port, String password) throws Exception {
        String authCmd = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";
        String command = String.format("redis-cli -h %s -p %d%s ping 2>&1", host, port, authCmd);
        SSHClient.SSHResult result = ssh.executeCommand(command);
        String output = result.getStdout().trim();
        
        logger.debug("Redis ping检查: host={}, port={}, exitCode={}, output={}", 
                host, port, result.getExitCode(), output);
        
        // 返回 PONG 或包含 PONG 表示运行正常
        boolean isRunning = output.contains("PONG");
        
        if (!isRunning) {
            logger.warn("Redis连接失败: {}:{}, 输出: {}", host, port, output);
        }
        
        return isRunning;
    }

    /**
     * 执行cluster meet命令
     * @param ssh SSH客户端
     * @param localHost 本地Redis绑定的IP地址
     * @param localPort 本地Redis端口
     * @param targetIp 目标节点IP
     * @param targetPort 目标节点端口
     * @param password 密码
     */
    public static void clusterMeet(SSHClient ssh, String localHost, int localPort, String targetIp, int targetPort, String password) throws Exception {
        String authCmd = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";
        String command = String.format("redis-cli -h %s -p %d%s cluster meet %s %d 2>&1", 
            localHost, localPort, authCmd, targetIp, targetPort);
        
        SSHClient.SSHResult result = ssh.executeCommand(command);
        String output = result.getStdout();
        
        // 检查结果：包含 OK 表示成功
        // 注意：Redis 6.0+ 会输出密码警告，但这不影响命令执行
        if (!output.contains("OK")) {
            // 如果没有 OK，检查是否有错误信息
            if (output.contains("Connection refused") || output.contains("NOAUTH") || 
                output.contains("ERR") || output.toLowerCase().contains("error")) {
                throw new RuntimeException("Cluster meet失败: " + output);
            }
        }
        
        logger.info("Cluster meet成功: {}:{} -> {}:{}", localHost, localPort, targetIp, targetPort);
    }

    /**
     * 分配哈希槽
     */
    public static void assignSlots(SSHClient ssh, String host, int port, int startSlot, int endSlot, String password) throws Exception {
        String authCmd = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";
        
        // 构建slot列表
        StringBuilder slots = new StringBuilder();
        for (int i = startSlot; i <= endSlot; i++) {
            if (i > startSlot) slots.append(" ");
            slots.append(i);
        }
        
        String command = String.format("redis-cli -h %s -p %d%s cluster addslots %s 2>&1", 
            host, port, authCmd, slots.toString());
        
        SSHClient.SSHResult result = ssh.executeCommand(command, 30);
        String output = result.getStdout();
        
        // 检查是否成功（包含 OK 或不包含错误信息）
        if (!output.contains("OK") && (output.contains("ERR") || output.toLowerCase().contains("error"))) {
            throw new RuntimeException("分配slots失败: " + output);
        }
        
        logger.info("分配slots成功: {}-{} 到 {}:{}", startSlot, endSlot, host, port);
    }

    /**
     * 设置从节点
     */
    public static void replicateMaster(SSHClient ssh, String slaveHost, int slavePort, String masterNodeId, String password) throws Exception {
        String authCmd = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";
        String command = String.format("redis-cli -h %s -p %d%s cluster replicate %s 2>&1", 
            slaveHost, slavePort, authCmd, masterNodeId);
        
        SSHClient.SSHResult result = ssh.executeCommand(command);
        String output = result.getStdout();
        
        // 检查是否成功
        if (!output.contains("OK") && (output.contains("ERR") || output.toLowerCase().contains("error"))) {
            throw new RuntimeException("设置从节点失败: " + output);
        }
        
        logger.info("设置从节点成功: {}:{} 复制 {}", slaveHost, slavePort, masterNodeId);
    }

    /**
     * 获取节点ID
     */
    public static String getNodeId(SSHClient ssh, String host, int port, String password) throws Exception {
        String authCmd = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";
        String command = String.format("redis-cli -h %s -p %d%s cluster myid 2>&1", host, port, authCmd);
        
        SSHClient.SSHResult result = ssh.executeCommand(command);
        String output = result.getStdout().trim();
        
        // 从输出中提取节点ID（40位十六进制字符串）
        // 注意：Redis 6.0+ 会输出密码警告，需要从多行输出中提取
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("[a-f0-9]{40}");
        java.util.regex.Matcher matcher = pattern.matcher(output);
        
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    /**
     * 验证集群状态
     * @param expectedNodes 预期的节点数量
     */
    public static boolean verifyCluster(SSHClient ssh, String host, int port, String password, int expectedNodes) throws Exception {
        String authCmd = (password != null && !password.isEmpty()) ? " -a '" + password + "'" : "";
        String command = String.format("redis-cli -h %s -p %d%s cluster info 2>&1", host, port, authCmd);
        
        SSHClient.SSHResult result = ssh.executeCommand(command);
        String output = result.getStdout();
        
        // 检查集群状态
        boolean stateOk = output.contains("cluster_state:ok");
        boolean nodesOk = output.contains("cluster_known_nodes:" + expectedNodes);
        
        if (!stateOk) {
            logger.warn("集群状态不正常: {}", output);
        }
        if (!nodesOk) {
            logger.warn("集群节点数不匹配，期待{}个，实际: {}", expectedNodes, 
                output.contains("cluster_known_nodes:") ? output.split("cluster_known_nodes:")[1].split("\\n")[0] : "未知");
        }
        
        return stateOk && nodesOk;
    }
    
    /**
     * 验证集群状态（兼容旧版本，默认6个节点）
     */
    public static boolean verifyCluster(SSHClient ssh, String host, int port, String password) throws Exception {
        return verifyCluster(ssh, host, port, password, 6);
    }

    /**
     * 清除Redis数据目录
     */
    public static void clearDataDir(SSHClient ssh, String dataDir) throws Exception {
        // 停止Redis进程
        SSHClient.SSHResult result = ssh.executeCommand("ps aux | grep redis-server | grep -v grep | awk '{print $2}' | xargs kill -9 2>/dev/null || true");
        
        // 等待进程停止
        TimeUnit.SECONDS.sleep(2);
        
        // 删除数据文件
        result = ssh.executeCommand("rm -rf " + dataDir + "/* 2>/dev/null || true");
        
        logger.info("清理数据目录: {}", dataDir);
    }

    /**
     * 启动Redis实例
     * @param ssh SSH客户端
     * @param redisPath Redis安装路径
     * @param configPath 配置文件路径
     * @param serverIp 服务器IP（用于验证）
     */
    public static void startRedisInstance(SSHClient ssh, String redisPath, String configPath, String serverIp) throws Exception {
        String redisServerCmd = redisPath + "/redis-server";
        String command = String.format("nohup %s %s > /dev/null 2>&1 &", redisServerCmd, configPath);
        
        SSHClient.SSHResult result = ssh.executeCommand(command);
        if (!result.isSuccess()) {
            throw new RuntimeException("启动Redis实例失败: " + result.getStderr());
        }
        
        logger.info("启动Redis实例成功: {}", configPath);
    }

    /**
     * 停止Redis实例
     * @param ssh SSH客户端
     * @param port Redis端口
     */
    public static void stopRedisInstance(SSHClient ssh, int port) throws Exception {
        // 查找并杀死Redis进程
        String command = String.format("ps aux | grep 'redis-server' | grep ':%d' | grep -v grep | awk '{print $2}' | xargs kill -9 2>/dev/null || true", port);
        ssh.executeCommand(command);
        
        logger.info("停止Redis实例: 端口{}", port);
    }
}
