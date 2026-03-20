package com.redis.manager.ssh;

import com.redis.manager.entity.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSH连接池
 * 管理SSH连接，支持连接复用
 */
@Component
public class SSHConnectionPool {

    private static final Logger logger = LoggerFactory.getLogger(SSHConnectionPool.class);
    private final Map<String, SSHClient> connections = new ConcurrentHashMap<>();

    /**
     * 获取或创建SSH连接
     */
    public SSHClient getConnection(Server server) throws Exception {
        String key = server.getIp() + ":" + server.getSshPort();
        
        SSHClient client = connections.get(key);
        if (client != null) {
            try {
                // 测试连接是否有效
                client.executeCommand("echo 'ping'");
                return client;
            } catch (Exception e) {
                // 连接已失效，移除
                logger.warn("SSH连接已失效，重新创建: {}", key);
                connections.remove(key);
            }
        }
        
        // 创建新连接
        client = new SSHClient(
                server.getIp(),
                server.getSshPort(),
                server.getSshUser(),
                server.getSshPassword()
        );
        
        client.connect();
        connections.put(key, client);
        return client;
    }

    /**
     * 移除连接
     */
    public void removeConnection(String ip, int port) {
        String key = ip + ":" + port;
        SSHClient client = connections.remove(key);
        if (client != null) {
            client.disconnect();
            logger.info("SSH连接已移除: {}", key);
        }
    }

    /**
     * 关闭所有连接
     */
    public void closeAll() {
        for (Map.Entry<String, SSHClient> entry : connections.entrySet()) {
            try {
                entry.getValue().disconnect();
                logger.info("SSH连接已关闭: {}", entry.getKey());
            } catch (Exception e) {
                logger.error("关闭SSH连接失败: {}", entry.getKey(), e);
            }
        }
        connections.clear();
    }

    /**
     * 测试服务器连接
     */
    public boolean testConnection(Server server) {
        SSHClient client = null;
        try {
            client = new SSHClient(
                    server.getIp(),
                    server.getSshPort(),
                    server.getSshUser(),
                    server.getSshPassword()
            );
            client.connect();
            SSHClient.SSHResult result = client.executeCommand("echo 'connected'");
            return result.isSuccess() && result.getStdout().contains("connected");
        } catch (Exception e) {
            logger.error("连接测试失败: {}@{}", server.getSshUser(), server.getIp(), e);
            return false;
        } finally {
            if (client != null) {
                client.disconnect();
            }
        }
    }

    /**
     * 测试服务器连接（直接参数）
     */
    public boolean testConnection(String ip, Integer port, String user, String password) {
        SSHClient client = null;
        try {
            client = new SSHClient(ip, port, user, password);
            client.connect();
            SSHClient.SSHResult result = client.executeCommand("echo 'connected'");
            return result.isSuccess() && result.getStdout().contains("connected");
        } catch (Exception e) {
            logger.error("连接测试失败: {}@{}", user, ip, e);
            return false;
        } finally {
            if (client != null) {
                client.disconnect();
            }
        }
    }

    /**
     * 检查远程文件是否存在
     */
    public boolean checkFileExists(String ip, Integer port, String user, String password, String filePath) {
        SSHClient client = null;
        try {
            client = new SSHClient(ip, port, user, password);
            client.connect();
            SSHClient.SSHResult result = client.executeCommand("test -f " + filePath + " && echo 'exists'");
            return result.isSuccess() && result.getStdout().contains("exists");
        } catch (Exception e) {
            logger.error("检查文件失败: {} - {}", ip, filePath, e);
            return false;
        } finally {
            if (client != null) {
                client.disconnect();
            }
        }
    }

    /**
     * 获取Redis版本
     * @return 版本号，如 "6.2.14" 或 "5.0.14"，获取失败返回 null
     */
    public String getRedisVersion(String ip, Integer port, String user, String password, String redisPath) {
        SSHClient client = null;
        try {
            client = new SSHClient(ip, port, user, password);
            client.connect();
            // 执行 redis-server --version 或 redis-cli --version
            SSHClient.SSHResult result = client.executeCommand(redisPath + "/redis-server --version");
            if (!result.isSuccess() || result.getStdout().isEmpty()) {
                // 尝试 redis-cli
                result = client.executeCommand(redisPath + "/redis-cli --version");
            }
            if (result.isSuccess() && !result.getStdout().isEmpty()) {
                String output = result.getStdout();
                // 解析版本号，格式如：Redis server v=6.2.14 sha=... 或 redis-cli 6.2.14
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d+\\.\\d+\\.\\d+)");
                java.util.regex.Matcher matcher = pattern.matcher(output);
                if (matcher.find()) {
                    String version = matcher.group(1);
                    logger.info("获取Redis版本成功: {} - {}", ip, version);
                    return version;
                }
            }
            logger.warn("无法解析Redis版本: {} - {}", ip, result.getStdout());
            return null;
        } catch (Exception e) {
            logger.error("获取Redis版本失败: {}", ip, e);
            return null;
        } finally {
            if (client != null) {
                client.disconnect();
            }
        }
    }
}
