package com.redis.manager.ssh;

import com.jcraft.jsch.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * SSH客户端工具类
 * 用于远程执行命令、上传文件等操作
 */
public class SSHClient {

    private static final Logger logger = LoggerFactory.getLogger(SSHClient.class);
    private static final int DEFAULT_TIMEOUT = 30000;
    private static final int DEFAULT_PORT = 22;

    private String host;
    private int port;
    private String username;
    private String password;
    private String privateKey;
    private Session session;

    public SSHClient(String host, String username, String password) {
        this(host, DEFAULT_PORT, username, password);
    }

    public SSHClient(String host, int port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    public SSHClient(String host, int port, String username, String privateKey, boolean useKey) {
        this.host = host;
        this.port = port;
        this.username = username;
        if (useKey) {
            this.privateKey = privateKey;
        }
    }

    /**
     * 连接服务器
     */
    public boolean connect() throws Exception {
        try {
            JSch jsch = new JSch();
            
            if (privateKey != null && !privateKey.isEmpty()) {
                jsch.addIdentity(privateKey);
            }
            
            session = jsch.getSession(username, host, port);
            
            if (password != null && !password.isEmpty()) {
                session.setPassword(password);
            }
            
            // 跳过主机密钥检查
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            
            session.connect(DEFAULT_TIMEOUT);
            logger.info("SSH连接成功: {}@{}", username, host);
            return true;
        } catch (Exception e) {
            logger.error("SSH连接失败: {}@{}", username, host, e);
            throw e;
        }
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        if (session != null && session.isConnected()) {
            session.disconnect();
            logger.info("SSH断开连接: {}", host);
        }
    }

    /**
     * 执行命令
     */
    public SSHResult executeCommand(String command) throws Exception {
        return executeCommand(command, 60);
    }

    /**
     * 执行命令（带超时）
     */
    public SSHResult executeCommand(String command, int timeoutSeconds) throws Exception {
        if (session == null || !session.isConnected()) {
            throw new IllegalStateException("SSH未连接");
        }

        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.setErrStream(System.err);
            
            InputStream in = channel.getInputStream();
            InputStream err = channel.getErrStream();
            
            channel.connect();
            
            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();
            
            byte[] tmp = new byte[1024];
            long startTime = System.currentTimeMillis();
            
            while (true) {
                while (in.available() > 0) {
                    int i = in.read(tmp, 0, 1024);
                    if (i < 0) break;
                    output.append(new String(tmp, 0, i));
                }
                
                while (err.available() > 0) {
                    int i = err.read(tmp, 0, 1024);
                    if (i < 0) break;
                    error.append(new String(tmp, 0, i));
                }
                
                if (channel.isClosed()) {
                    break;
                }
                
                // 超时检查
                if (System.currentTimeMillis() - startTime > timeoutSeconds * 1000) {
                    logger.warn("命令执行超时: {}", command);
                    break;
                }
                
                try {
                    Thread.sleep(100);
                } catch (Exception ignored) {}
            }
            
            int exitCode = channel.getExitStatus();
            logger.debug("命令执行完成: {}, exitCode={}", command, exitCode);
            
            return new SSHResult(exitCode, output.toString().trim(), error.toString().trim());
            
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    /**
     * 上传文件
     */
    public void uploadFile(String localPath, String remotePath) throws Exception {
        if (session == null || !session.isConnected()) {
            throw new IllegalStateException("SSH未连接");
        }

        ChannelSftp channel = null;
        try {
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect();
            
            // 创建远程目录
            String remoteDir = remotePath.substring(0, remotePath.lastIndexOf('/'));
            try {
                channel.mkdir(remoteDir);
            } catch (SftpException e) {
                // 目录可能已存在
            }
            
            channel.put(localPath, remotePath);
            logger.info("文件上传成功: {} -> {}", localPath, remotePath);
            
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    /**
     * 上传文件内容
     */
    public void uploadFileContent(String content, String remotePath) throws Exception {
        if (session == null || !session.isConnected()) {
            throw new IllegalStateException("SSH未连接");
        }

        ChannelSftp channel = null;
        try {
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect();
            
            // 创建远程目录
            String remoteDir = remotePath.substring(0, remotePath.lastIndexOf('/'));
            try {
                channel.mkdir(remoteDir);
            } catch (SftpException e) {
                // 目录可能已存在
            }
            
            channel.put(new ByteArrayInputStream(content.getBytes()), remotePath);
            logger.info("文件内容上传成功: {}", remotePath);
            
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    /**
     * 下载文件
     */
    public String downloadFileContent(String remotePath) throws Exception {
        if (session == null || !session.isConnected()) {
            throw new IllegalStateException("SSH未连接");
        }

        ChannelSftp channel = null;
        try {
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect();
            
            InputStream in = channel.get(remotePath);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            
            byte[] buffer = new byte[1024];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
            
            return out.toString("UTF-8");
            
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    /**
     * 检查文件是否存在
     */
    public boolean fileExists(String remotePath) {
        try {
            executeCommand("test -f " + remotePath);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取主机地址
     */
    public String getHost() {
        return host;
    }

    /**
     * SSH执行结果
     */
    public static class SSHResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        public SSHResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getStdout() {
            return stdout;
        }

        public String getStderr() {
            return stderr;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }

        @Override
        public String toString() {
            return "SSHResult{" +
                    "exitCode=" + exitCode +
                    ", stdout='" + stdout + '\'' +
                    ", stderr='" + stderr + '\'' +
                    '}';
        }
    }
}
