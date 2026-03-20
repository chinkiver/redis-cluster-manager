package com.redis.manager.service;

import com.redis.manager.dto.DiskInfoDTO;
import com.redis.manager.dto.Result;
import com.redis.manager.dto.ServerDTO;
import com.redis.manager.dto.ServerGroupDTO;
import com.redis.manager.dto.ServerSystemInfoDTO;
import com.redis.manager.entity.Server;
import com.redis.manager.entity.ServerGroup;
import com.redis.manager.repository.RedisClusterRepository;
import com.redis.manager.repository.ServerGroupRepository;
import com.redis.manager.repository.ServerRepository;
import com.redis.manager.ssh.SSHClient;
import com.redis.manager.ssh.SSHConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 服务器组服务
 * 简化版：只管理服务器组和SSH连接
 */
@Service
public class ServerGroupService {

    private static final Logger logger = LoggerFactory.getLogger(ServerGroupService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private ServerGroupRepository groupRepository;

    @Autowired
    private ServerRepository serverRepository;

    @Autowired
    private SSHConnectionPool sshPool;

    @Autowired
    private RedisClusterRepository clusterRepository;

    /**
     * 创建服务器组
     */
    @Transactional
    public Result<ServerGroupDTO> createGroup(ServerGroupDTO dto) {
        // 检查名称是否已存在
        if (groupRepository.existsByName(dto.getName())) {
            return Result.error("组名称已存在");
        }

        // 检查服务器数量（至少2台）
        if (dto.getServers() == null || dto.getServers().size() < 2) {
            return Result.error("服务器组必须包含至少2台服务器");
        }

        // 验证SSH连接和Redis路径，同时获取版本
        List<String> validationErrors = new ArrayList<>();
        List<String> serverVersions = new ArrayList<>();
        
        for (int i = 0; i < dto.getServers().size(); i++) {
            ServerDTO serverDTO = dto.getServers().get(i);
            String redisPath = serverDTO.getRedisPath() != null ? serverDTO.getRedisPath() : "/usr/local/bin";
            
            // 验证SSH连接
            boolean connected = sshPool.testConnection(serverDTO.getIp(), serverDTO.getSshPort(), 
                    serverDTO.getSshUser(), serverDTO.getSshPassword());
            if (!connected) {
                validationErrors.add("服务器 #" + (i + 1) + " (" + serverDTO.getIp() + ") SSH连接失败");
                continue;
            }
            
            // 验证Redis路径（检查redis-cli和redis-server是否存在）
            boolean hasRedisCli = sshPool.checkFileExists(serverDTO.getIp(), serverDTO.getSshPort(),
                    serverDTO.getSshUser(), serverDTO.getSshPassword(), redisPath + "/redis-cli");
            boolean hasRedisServer = sshPool.checkFileExists(serverDTO.getIp(), serverDTO.getSshPort(),
                    serverDTO.getSshUser(), serverDTO.getSshPassword(), redisPath + "/redis-server");
            
            if (!hasRedisCli) {
                validationErrors.add("服务器 #" + (i + 1) + " (" + serverDTO.getIp() + ") 未找到 redis-cli (" + redisPath + ")");
            }
            if (!hasRedisServer) {
                validationErrors.add("服务器 #" + (i + 1) + " (" + serverDTO.getIp() + ") 未找到 redis-server (" + redisPath + ")");
                continue;
            }
            
            // 获取Redis版本
            String version = sshPool.getRedisVersion(serverDTO.getIp(), serverDTO.getSshPort(),
                    serverDTO.getSshUser(), serverDTO.getSshPassword(), redisPath);
            if (version != null) {
                serverDTO.setRedisVersion(version);
                serverVersions.add(version);
                logger.info("服务器 #{} ({}) Redis版本: {}", i + 1, serverDTO.getIp(), version);
            } else {
                validationErrors.add("服务器 #" + (i + 1) + " (" + serverDTO.getIp() + ") 无法获取Redis版本");
            }
        }
        
        if (!validationErrors.isEmpty()) {
            return Result.error("验证失败:\n" + String.join("\n", validationErrors));
        }
        
        // 检查所有服务器的Redis版本是否一致
        if (!serverVersions.isEmpty()) {
            String firstVersion = serverVersions.get(0);
            boolean allSame = serverVersions.stream().allMatch(v -> v.equals(firstVersion));
            if (!allSame) {
                logger.warn("服务器组中Redis版本不一致: {}", serverVersions);
                // 版本不一致只是警告，不阻止保存
            }
        }

        ServerGroup group = new ServerGroup();
        group.setName(dto.getName());
        group.setDescription(dto.getDescription());

        group = groupRepository.save(group);

        // 保存服务器
        List<Server> servers = new ArrayList<>();
        int index = 0;
        for (ServerDTO serverDTO : dto.getServers()) {
            Server server = new Server();
            server.setGroup(group);
            server.setName(serverDTO.getName());
            server.setIp(serverDTO.getIp());
            server.setSshPort(serverDTO.getSshPort());
            server.setSshUser(serverDTO.getSshUser());
            server.setSshPassword(serverDTO.getSshPassword());
            server.setAuthType(serverDTO.getAuthType());
            server.setStatus(1); // 已验证在线
            server.setDescription(serverDTO.getDescription());
            server.setRedisPath(serverDTO.getRedisPath() != null ? serverDTO.getRedisPath() : "/usr/local/bin");
            server.setRedisVersion(serverDTO.getRedisVersion()); // 保存检测到的版本
            server.setNodeIndex(index++); // 设置节点索引
            servers.add(server);
        }
        
        serverRepository.saveAll(servers);
        group.setServers(servers);

        logger.info("创建服务器组成功: {}", group.getName());
        return Result.success("创建成功", convertToDTO(group));
    }

    /**
     * 更新服务器组
     */
    @Transactional
    public Result<ServerGroupDTO> updateGroup(Long id, ServerGroupDTO dto) {
        Optional<ServerGroup> opt = groupRepository.findById(id);
        if (!opt.isPresent()) {
            return Result.error("服务器组不存在");
        }

        ServerGroup group = opt.get();
        
        // 检查名称是否与其他组冲突
        if (!group.getName().equals(dto.getName()) && groupRepository.existsByName(dto.getName())) {
            return Result.error("组名称已存在");
        }

        // 检查服务器数量（至少2台）
        if (dto.getServers() == null || dto.getServers().size() < 2) {
            return Result.error("服务器组必须包含至少2台服务器");
        }

        // 验证SSH连接和Redis路径，同时获取版本
        List<String> validationErrors = new ArrayList<>();
        List<String> serverVersions = new ArrayList<>();
        
        for (int i = 0; i < dto.getServers().size(); i++) {
            ServerDTO serverDTO = dto.getServers().get(i);
            String redisPath = serverDTO.getRedisPath() != null ? serverDTO.getRedisPath() : "/usr/local/bin";
            
            // 验证SSH连接
            boolean connected = sshPool.testConnection(serverDTO.getIp(), serverDTO.getSshPort(), 
                    serverDTO.getSshUser(), serverDTO.getSshPassword());
            if (!connected) {
                validationErrors.add("服务器 #" + (i + 1) + " (" + serverDTO.getIp() + ") SSH连接失败");
                continue;
            }
            
            // 验证Redis路径（检查redis-cli和redis-server是否存在）
            boolean hasRedisCli = sshPool.checkFileExists(serverDTO.getIp(), serverDTO.getSshPort(),
                    serverDTO.getSshUser(), serverDTO.getSshPassword(), redisPath + "/redis-cli");
            boolean hasRedisServer = sshPool.checkFileExists(serverDTO.getIp(), serverDTO.getSshPort(),
                    serverDTO.getSshUser(), serverDTO.getSshPassword(), redisPath + "/redis-server");
            
            if (!hasRedisCli) {
                validationErrors.add("服务器 #" + (i + 1) + " (" + serverDTO.getIp() + ") 未找到 redis-cli (" + redisPath + ")");
            }
            if (!hasRedisServer) {
                validationErrors.add("服务器 #" + (i + 1) + " (" + serverDTO.getIp() + ") 未找到 redis-server (" + redisPath + ")");
                continue;
            }
            
            // 获取Redis版本
            String version = sshPool.getRedisVersion(serverDTO.getIp(), serverDTO.getSshPort(),
                    serverDTO.getSshUser(), serverDTO.getSshPassword(), redisPath);
            if (version != null) {
                serverVersions.add(version);
                logger.info("服务器 {} Redis版本: {}", serverDTO.getIp(), version);
            }
        }
        
        // 如果有验证错误，返回错误信息
        if (!validationErrors.isEmpty()) {
            String errorMsg = String.join("; ", validationErrors);
            logger.error("服务器组更新验证失败: {}", errorMsg);
            return Result.error("验证失败: " + errorMsg);
        }
        
        // 所有验证通过，更新基本信息
        group.setName(dto.getName());
        group.setDescription(dto.getDescription());

        // 更新服务器列表 - 先删除旧的服务器，再添加新的
        // 由于orphanRemoval=true，删除关联后数据库会自动删除
        group.getServers().clear();
        
        // 添加新的服务器
        String detectedVersion = serverVersions.isEmpty() ? null : serverVersions.get(0);
        for (int i = 0; i < dto.getServers().size(); i++) {
            ServerDTO serverDTO = dto.getServers().get(i);
            Server server = new Server();
            server.setGroup(group);
            server.setNodeIndex(i); // 设置节点索引 0-5
            server.setDescription(serverDTO.getDescription()); // 设置服务器描述
            server.setName(serverDTO.getDescription() != null && !serverDTO.getDescription().isEmpty() 
                    ? serverDTO.getDescription() : "节点" + (i + 1));
            server.setIp(serverDTO.getIp());
            server.setSshPort(serverDTO.getSshPort());
            server.setSshUser(serverDTO.getSshUser());
            server.setSshPassword(serverDTO.getSshPassword());
            server.setRedisPath(serverDTO.getRedisPath() != null ? serverDTO.getRedisPath() : "/usr/local/bin");
            server.setRedisVersion(detectedVersion);
            server.setStatus(1); // 在线
            group.getServers().add(server);
        }

        group = groupRepository.save(group);
        logger.info("服务器组更新成功: {}, 包含 {} 台服务器", group.getName(), group.getServers().size());
        return Result.success("更新成功", convertToDTO(group));
    }

    /**
     * 删除服务器组
     */
    @Transactional
    public Result<Void> deleteGroup(Long id) {
        Optional<ServerGroup> opt = groupRepository.findById(id);
        if (!opt.isPresent()) {
            return Result.error("服务器组不存在");
        }

        ServerGroup group = opt.get();
        
        // 检查是否有关联的集群（需要通过RedisClusterRepository查询，这里简化处理）
        // TODO: 检查是否有关联的Redis集群

        groupRepository.delete(group);
        logger.info("删除服务器组成功: {}", group.getName());
        return Result.<Void>success("删除成功", null);
    }

    /**
     * 获取服务器组详情
     */
    public Result<ServerGroupDTO> getGroup(Long id) {
        Optional<ServerGroup> opt = groupRepository.findById(id);
        if (!opt.isPresent()) {
            return Result.error("服务器组不存在");
        }
        return Result.success(convertToDTO(opt.get()));
    }

    /**
     * 获取所有服务器组
     * 排序规则：默认组置顶，其他组按名称字母顺序排序
     */
    public Result<List<ServerGroupDTO>> getAllGroups() {
        List<ServerGroup> groups = groupRepository.findAllWithServers();
        List<ServerGroupDTO> dtos = groups.stream()
                .sorted((g1, g2) -> {
                    // 默认组置顶（isDefault = 1 的排在最前面）
                    Integer default1 = g1.getIsDefault() != null ? g1.getIsDefault() : 0;
                    Integer default2 = g2.getIsDefault() != null ? g2.getIsDefault() : 0;
                    if (!default1.equals(default2)) {
                        return default2.compareTo(default1); // 降序，1在前，0在后
                    }
                    // 其他按名称排序（忽略大小写）
                    String name1 = g1.getName() != null ? g1.getName().toLowerCase() : "";
                    String name2 = g2.getName() != null ? g2.getName().toLowerCase() : "";
                    return name1.compareTo(name2);
                })
                .map(this::convertToDTOWithClusterCount)
                .collect(Collectors.toList());
        return Result.success(dtos);
    }

    /**
     * 获取服务器组关联的集群数量
     */
    public Result<Map<String, Long>> getClusterCount(Long groupId) {
        Optional<ServerGroup> opt = groupRepository.findById(groupId);
        if (!opt.isPresent()) {
            return Result.error("服务器组不存在");
        }

        long count = clusterRepository.countByServerGroupId(groupId);
        Map<String, Long> result = new HashMap<>();
        result.put("clusterCount", count);
        return Result.success(result);
    }

    /**
     * 获取服务器系统信息
     */
    public Result<ServerSystemInfoDTO> getServerSystemInfo(Long serverId) {
        Optional<Server> opt = serverRepository.findById(serverId);
        if (!opt.isPresent()) {
            return Result.error("服务器不存在");
        }

        Server server = opt.get();
        ServerSystemInfoDTO info = new ServerSystemInfoDTO(server.getId(), server.getIp());

        try {
            SSHClient ssh = new SSHClient(server.getIp(), server.getSshPort(),
                    server.getSshUser(), server.getSshPassword());
            ssh.connect();
            info.setConnected(true);

            try {
                // 获取 CPU 信息
                try {
                    SSHClient.SSHResult cpuModelResult = ssh.executeCommand(
                            "cat /proc/cpuinfo | grep 'model name' | head -1 | cut -d: -f2 | xargs");
                    String cpuModel = cpuModelResult.getStdout().trim();
                    info.setCpuModel(cpuModel.isEmpty() ? "未知" : cpuModel);

                    SSHClient.SSHResult coresResult = ssh.executeCommand("nproc");
                    info.setCpuCores(coresResult.getStdout().trim() + " 核");

                    // 获取 CPU 使用率
                    SSHClient.SSHResult cpuUsageResult = ssh.executeCommand(
                            "top -bn1 | grep 'Cpu(s)' | awk '{print $2}' | cut -d'%' -f1");
                    String cpuUsageStr = cpuUsageResult.getStdout().trim();
                    if (!cpuUsageStr.isEmpty()) {
                        info.setCpuUsage(String.format("%.1f%%", Double.parseDouble(cpuUsageStr)));
                    } else {
                        info.setCpuUsage("未知");
                    }
                } catch (Exception e) {
                    logger.warn("获取CPU信息失败: {}", e.getMessage());
                    info.setCpuModel("获取失败");
                    info.setCpuCores("未知");
                    info.setCpuUsage("未知");
                }

                // 获取内存信息
                try {
                    SSHClient.SSHResult memTotalResult = ssh.executeCommand(
                            "free -m | grep Mem | awk '{print $2}'");
                    int totalMem = Integer.parseInt(memTotalResult.getStdout().trim());

                    SSHClient.SSHResult memUsedResult = ssh.executeCommand(
                            "free -m | grep Mem | awk '{print $3}'");
                    int usedMem = Integer.parseInt(memUsedResult.getStdout().trim());

                    SSHClient.SSHResult memFreeResult = ssh.executeCommand(
                            "free -m | grep Mem | awk '{print $7}'");
                    int freeMem = Integer.parseInt(memFreeResult.getStdout().trim());

                    info.setTotalMemory(String.format("%.2f GB", totalMem / 1024.0));
                    info.setUsedMemory(String.format("%.2f GB", usedMem / 1024.0));
                    info.setFreeMemory(String.format("%.2f GB", freeMem / 1024.0));
                    info.setMemoryUsage(String.format("%.1f%%", (usedMem * 100.0 / totalMem)));
                } catch (Exception e) {
                    logger.warn("获取内存信息失败: {}", e.getMessage());
                    info.setTotalMemory("获取失败");
                    info.setUsedMemory("获取失败");
                    info.setFreeMemory("获取失败");
                    info.setMemoryUsage("未知");
                }

                // 获取操作系统信息 - 文字展示
                try {
                    SSHClient.SSHResult osResult = ssh.executeCommand("cat /etc/issue");
                    String osRaw = osResult.getStdout().trim();
                    // 清理输出：移除ANSI转义码和换行，取第一行有效内容
                    String osClean = osRaw.replaceAll("\\x1B\\[[0-9;]*[a-zA-Z]", "")  // 移除ANSI颜色码
                                          .replaceAll("\\\\[a-zA-Z]", "")           // 移除\n \l等
                                          .replaceAll("\\\\r", "")               // 移除\r
                                          .replaceAll("\\\\l", "")               // 移除\l
                                          .replaceAll("\\\\m", "")               // 移除\m
                                          .replaceAll("\\\\s", "")               // 移除\s
                                          .replaceAll("\\\\v", "")               // 移除\v
                                          .trim();
                    // 取第一行为系统名称
                    String osName = osClean.split("\\n")[0].trim();
                    info.setOsName(osName.isEmpty() ? "Linux" : osName);
                } catch (Exception e) {
                    logger.warn("获取OS信息失败: {}", e.getMessage());
                    info.setOsName("Linux");
                }

                // 获取磁盘信息 - 解析为结构化数据
                try {
                    SSHClient.SSHResult diskResult = ssh.executeCommand("df -h");
                    String diskRaw = diskResult.getStdout().trim();
                    List<DiskInfoDTO> diskList = parseDiskInfo(diskRaw);
                    info.setDiskList(diskList);
                } catch (Exception e) {
                    logger.warn("获取磁盘信息失败: {}", e.getMessage());
                    info.setDiskList(new ArrayList<>());
                }

                return Result.success(info);
            } finally {
                ssh.disconnect();
            }
        } catch (Exception e) {
            logger.error("获取服务器系统信息失败: {}", e.getMessage(), e);
            info.setConnected(false);
            info.setErrorMessage(e.getMessage());
            return Result.error("获取系统信息失败: " + e.getMessage());
        }
    }

    /**
     * 解析 df -h 输出
     */
    private List<DiskInfoDTO> parseDiskInfo(String dfOutput) {
        List<DiskInfoDTO> diskList = new ArrayList<>();
        if (dfOutput == null || dfOutput.isEmpty()) {
            return diskList;
        }
        
        String[] lines = dfOutput.split("\\n");
        for (int i = 1; i < lines.length; i++) { // 跳过标题行
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            
            // 处理带有空格的文件系统名（如 /dev/mapper/centos-root）
            // df -h 格式：Filesystem Size Used Avail Use% Mounted on
            String[] parts = line.split("\\s+");
            if (parts.length >= 6) {
                DiskInfoDTO disk = new DiskInfoDTO();
                disk.setFilesystem(parts[0]);
                disk.setSize(parts[1]);
                disk.setUsed(parts[2]);
                disk.setAvailable(parts[3]);
                disk.setUsePercent(parts[4]);
                disk.setMountedOn(parts[5]);
                diskList.add(disk);
            } else if (parts.length == 1 && i + 1 < lines.length) {
                // 处理文件系统名带空格的情况（如 Windows 共享）
                String nextLine = lines[++i].trim();
                String[] nextParts = nextLine.split("\\s+");
                if (nextParts.length >= 5) {
                    DiskInfoDTO disk = new DiskInfoDTO();
                    disk.setFilesystem(parts[0]);
                    disk.setSize(nextParts[0]);
                    disk.setUsed(nextParts[1]);
                    disk.setAvailable(nextParts[2]);
                    disk.setUsePercent(nextParts[3]);
                    disk.setMountedOn(nextParts[4]);
                    diskList.add(disk);
                }
            }
        }
        return diskList;
    }

    /**
     * 测试服务器连接
     */
    public Result<Boolean> testServerConnection(Long serverId) {
        Optional<Server> opt = serverRepository.findById(serverId);
        if (!opt.isPresent()) {
            return Result.error("服务器不存在");
        }

        Server server = opt.get();
        boolean connected = sshPool.testConnection(server);
        
        // 更新状态
        server.setStatus(connected ? 1 : 0);
        serverRepository.save(server);
        
        return Result.success(connected ? "连接成功" : "连接失败", connected);
    }

    /**
     * 测试组内所有服务器连接
     */
    public Result<List<String>> testGroupConnection(Long groupId) {
        List<Server> servers = serverRepository.findByGroupId(groupId);
        List<String> results = new ArrayList<>();
        
        for (Server server : servers) {
            boolean connected = sshPool.testConnection(server);
            server.setStatus(connected ? 1 : 0);
            serverRepository.save(server);
            
            String result = String.format("%s (%s): %s", 
                    server.getIp(), 
                    server.getName() != null ? server.getName() : "无名称", 
                    connected ? "在线" : "离线");
            results.add(result);
        }
        
        return Result.success(results);
    }

    /**
     * 更新服务器SSH配置
     */
    @Transactional
    public Result<Void> updateServerSSH(Long serverId, String user, String password, Integer authType) {
        Optional<Server> opt = serverRepository.findById(serverId);
        if (!opt.isPresent()) {
            return Result.error("服务器不存在");
        }

        Server server = opt.get();
        server.setSshUser(user);
        server.setSshPassword(password);
        server.setAuthType(authType);
        serverRepository.save(server);
        
        return Result.<Void>success("更新成功", null);
    }

    /**
     * 获取可用的服务器组（已配置至少1台服务器且未创建集群）
     */
    public Result<List<ServerGroupDTO>> getAvailableGroups() {
        List<ServerGroup> allGroups = groupRepository.findAllWithServers();
        System.out.println("[DEBUG] getAvailableGroups - 总服务器组数量: " + allGroups.size());
        
        for (ServerGroup g : allGroups) {
            int serverCount = g.getServers() != null ? g.getServers().size() : 0;
            System.out.println("[DEBUG] 组: " + g.getName() + "(id=" + g.getId() + "), 服务器数量: " + serverCount);
            // 详细打印servers内容
            if (g.getServers() != null && !g.getServers().isEmpty()) {
                for (Server s : g.getServers()) {
                    System.out.println("[DEBUG]   - 服务器: " + s.getIp() + ", group_id: " + (s.getGroup() != null ? s.getGroup().getId() : "null"));
                }
            }
        }
        
        List<ServerGroupDTO> availableGroups = allGroups.stream()
                .filter(g -> {
                    boolean hasServers = g.getServers() != null && !g.getServers().isEmpty();
                    System.out.println("[DEBUG] 过滤检查 - 组: " + g.getName() + ", hasServers: " + hasServers + ", count: " + (g.getServers() != null ? g.getServers().size() : 0));
                    return hasServers;
                })
                .map(this::convertToDTO)
                .collect(Collectors.toList());
        
        System.out.println("[DEBUG] getAvailableGroups - 最终返回可用组数量: " + availableGroups.size());
        return Result.success(availableGroups);
    }

    /**
     * 转换为DTO（带集群数统计）
     */
    private ServerGroupDTO convertToDTOWithClusterCount(ServerGroup group) {
        ServerGroupDTO dto = convertToDTO(group);
        
        // 查询关联的集群数
        long clusterCount = clusterRepository.countByServerGroupId(group.getId());
        dto.setClusterCount((int) clusterCount);
        
        return dto;
    }

    /**
     * 转换为DTO
     */
    private ServerGroupDTO convertToDTO(ServerGroup group) {
        ServerGroupDTO dto = new ServerGroupDTO();
        dto.setId(group.getId());
        dto.setName(group.getName());
        dto.setDescription(group.getDescription());
        dto.setIsDefault(group.getIsDefault());
        
        if (group.getCreateTime() != null) {
            dto.setCreateTime(group.getCreateTime().format(DATE_FORMATTER));
        }
        
        if (group.getServers() != null && !group.getServers().isEmpty()) {
            List<ServerDTO> serverDTOs = group.getServers().stream()
                    .map(this::convertToDTO)
                    .collect(Collectors.toList());
            dto.setServers(serverDTOs);
        }
        
        return dto;
    }

    private ServerDTO convertToDTO(Server server) {
        ServerDTO dto = new ServerDTO();
        dto.setId(server.getId());
        dto.setGroupId(server.getGroup().getId());
        dto.setName(server.getName());
        dto.setIp(server.getIp());
        dto.setSshPort(server.getSshPort());
        dto.setSshUser(server.getSshUser());
        // 编辑时需要密码回显，返回原密码
        dto.setSshPassword(server.getSshPassword());
        dto.setAuthType(server.getAuthType());
        dto.setStatus(server.getStatus());
        dto.setDescription(server.getDescription());
        dto.setRedisPath(server.getRedisPath());
        dto.setRedisVersion(server.getRedisVersion());
        dto.setNodeIndex(server.getNodeIndex());
        
        return dto;
    }

    /**
     * 设置默认服务器组
     * 将指定组设为默认，同时取消其他组的默认设置
     */
    @Transactional
    public Result<Void> setDefaultGroup(Long groupId) {
        Optional<ServerGroup> groupOpt = groupRepository.findById(groupId);
        if (!groupOpt.isPresent()) {
            return Result.error("服务器组不存在");
        }
        
        // 先取消所有组的默认设置
        List<ServerGroup> allGroups = groupRepository.findAll();
        for (ServerGroup group : allGroups) {
            if (group.getIsDefault() != null && group.getIsDefault() == 1) {
                group.setIsDefault(0);
                groupRepository.save(group);
            }
        }
        
        // 设置指定组为默认
        ServerGroup group = groupOpt.get();
        group.setIsDefault(1);
        groupRepository.save(group);
        
        logger.info("设置默认服务器组: groupId={}, name={}", groupId, group.getName());
        return Result.success(null);
    }

    /**
     * 取消默认服务器组设置
     */
    @Transactional
    public Result<Void> cancelDefaultGroup(Long groupId) {
        Optional<ServerGroup> groupOpt = groupRepository.findById(groupId);
        if (!groupOpt.isPresent()) {
            return Result.error("服务器组不存在");
        }
        
        ServerGroup group = groupOpt.get();
        if (group.getIsDefault() != null && group.getIsDefault() == 1) {
            group.setIsDefault(0);
            groupRepository.save(group);
            logger.info("取消默认服务器组设置: groupId={}, name={}", groupId, group.getName());
        }
        
        return Result.success(null);
    }
}
