package com.redis.manager.util;

import org.apache.poi.xwpf.usermodel.*;

import java.io.IOException;

/**
 * 文档导出工具类
 * 用于生成系统操作手册和投产上线文档
 * 
 * @author Redis Manager
 * @version 1.0.0
 */
public class DocumentExportUtil {

    /**
     * 生成系统功能操作手册
     */
    public static void generateUserManual(String outputPath) throws IOException {
        DocumentGenerator doc = new DocumentGenerator();
        
        doc.addTitle("Redis Cluster Manager");
        doc.addTitle("系统功能操作手册");
        doc.addEmptyLine();
        doc.addStyledParagraph("版本：V1.0.0", false, 11, null);
        doc.addStyledParagraph("日期：2026年3月", false, 11, null);
        doc.addEmptyLine();

        // 概述
        doc.addHeading1("概述");
        doc.addParagraph("Redis Cluster Manager是企业级Redis集群可视化管理平台，专注于3×3（3主3从）标准架构。");
        doc.addEmptyLine();

        // 系统登录
        doc.addHeading1("系统登录");
        doc.addParagraph("默认访问地址：http://localhost:8080");
        doc.addParagraph("用户名：admin  密码：admin");
        doc.addWarning("生产环境请务必修改默认密码！");
        doc.addEmptyLine();

        // 主页功能
        doc.addHeading1("主页功能");
        doc.addParagraph("首页显示统计卡片和集群概览：");
        doc.addParagraph("• 创建集群数、导入集群数、总节点数、服务器组数");
        doc.addParagraph("• 集群状态、内存使用、QPS等实时监控");
        doc.addParagraph("• 支持列可见性切换和分页浏览");
        doc.addEmptyLine();

        // 服务器组管理
        doc.addHeading1("服务器组管理");
        doc.addParagraph("服务器组是部署Redis集群的基础，包含3-6台服务器。");
        doc.addHeading2("创建服务器组");
        doc.addParagraph("1. 点击左侧菜单“服务器组”");
        doc.addParagraph("2. 点击“新建服务器组”按钮");
        doc.addParagraph("3. 填写组名称、描述和服务器信息");
        doc.addParagraph("4. 系统自动验证SSH连接");
        doc.addEmptyLine();

        // 集群管理
        doc.addHeading1("集群管理");
        doc.addHeading2("创建集群");
        doc.addParagraph("1. 进入“集群管理” → “创建集群”");
        doc.addParagraph("2. 选择服务器组并配置集群参数");
        doc.addParagraph("3. 配置包括：集群名称、Redis版本、端口、密码、内存限制等");
        doc.addParagraph("4. 预览配置并创建");
        doc.addEmptyLine();
        doc.addWarning("系统仅支持3×3架构（3主3从）");
        doc.addEmptyLine();

        doc.addHeading2("导入现有集群");
        doc.addWarning("导入前提：集群必须正常运行，且为3主3从架构");
        doc.addParagraph("1. 进入“集群管理” → “导入集群”");
        doc.addParagraph("2. 选择服务器组并输入端口和密码");
        doc.addParagraph("3. 开始检查并导入");
        doc.addEmptyLine();

        // 监控管理
        doc.addHeading1("监控管理");
        doc.addHeading2("物理机监控");
        doc.addParagraph("监控指标：CPU、内存、磁盘、网络、系统负载、Swap");
        doc.addEmptyLine();
        doc.addHeading2("集群监控");
        doc.addParagraph("集群级：健康状态、槽位分配、主从复制、总QPS、总内存");
        doc.addParagraph("实例级：内存使用、碎片率、连接数、QPS、P99延迟、慢查询");
        doc.addEmptyLine();

        // 数据备份
        doc.addHeading1("数据备份管理");
        doc.addParagraph("系统提供完整的数据库备份功能：");
        doc.addHeading2("自动备份");
        doc.addParagraph("• 备份时间：每日凌晨2:00");
        doc.addParagraph("• 保留策略：自动保畐最近30天");
        doc.addParagraph("• 备份位置：./data/backup/");
        doc.addEmptyLine();
        doc.addHeading2("手动备份");
        doc.addParagraph("1. 以管理员身份登录");
        doc.addParagraph("2. 进入“系统” → “数据备份”");
        doc.addParagraph("3. 点击“立即备份”按钮");
        doc.addEmptyLine();
        doc.addHeading2("备份文件管理");
        doc.addParagraph("• 分页查看备份文件列表（每页10条）");
        doc.addParagraph("• 删除单个备份文件");
        doc.addParagraph("• 手动清理过期备份");
        doc.addEmptyLine();

        // 系统配置
        doc.addHeading1("系统配置");
        doc.addParagraph("管理员可自定义系统展示内容：");
        doc.addParagraph("• 系统名称、Logo、Icon");
        doc.addParagraph("• 登录页标题和副标题");
        doc.addParagraph("• 版权信息");
        doc.addParagraph("• 主题色");
        doc.addEmptyLine();

        doc.addParagraph("如遇其他问题，请联系系统管理员。");
        
        doc.save(outputPath);
        doc.close();
        System.out.println("操作手册已生成: " + outputPath);
    }

    /**
     * 生成投产上线文档
     */
    public static void generateDeploymentGuide(String outputPath) throws IOException {
        DocumentGenerator doc = new DocumentGenerator();
        
        doc.addTitle("Redis Cluster Manager");
        doc.addTitle("投产上线文档");
        doc.addEmptyLine();
        doc.addStyledParagraph("版本：V1.0.0", false, 11, null);
        doc.addStyledParagraph("日期：2026年3月", false, 11, null);
        doc.addEmptyLine();

        // 系统要求
        doc.addHeading1("系统要求");
        doc.addHeading2("服务器要求");
        XWPFTable serverTable = doc.createTable(5, 3);
        doc.setCellText(serverTable.getRow(0).getCell(0), "项目", true);
        doc.setCellText(serverTable.getRow(0).getCell(1), "最低要求", true);
        doc.setCellText(serverTable.getRow(0).getCell(2), "建议配置", true);
        doc.setCellText(serverTable.getRow(1).getCell(0), "CPU", false);
        doc.setCellText(serverTable.getRow(1).getCell(1), "2核", false);
        doc.setCellText(serverTable.getRow(1).getCell(2), "4核及以上", false);
        doc.setCellText(serverTable.getRow(2).getCell(0), "内存", false);
        doc.setCellText(serverTable.getRow(2).getCell(1), "2GB", false);
        doc.setCellText(serverTable.getRow(2).getCell(2), "4GB及以上", false);
        doc.setCellText(serverTable.getRow(3).getCell(0), "磁盘", false);
        doc.setCellText(serverTable.getRow(3).getCell(1), "10GB", false);
        doc.setCellText(serverTable.getRow(3).getCell(2), "50GB及以上", false);
        doc.setCellText(serverTable.getRow(4).getCell(0), "JDK", false);
        doc.setCellText(serverTable.getRow(4).getCell(1), "1.8", false);
        doc.setCellText(serverTable.getRow(4).getCell(2), "1.8及以上", false);
        doc.addEmptyLine();

        // 部署流程
        doc.addHeading1("部署流程");
        doc.addHeading2("一、环境准备");
        doc.addParagraph("1. 确保JDK已安装并配置环境变量");
        doc.addCodeBlock("java -version");
        doc.addParagraph("2. 创建应用目录");
        doc.addCodeBlock("mkdir -p /opt/redis-manager\ncd /opt/redis-manager");
        doc.addEmptyLine();
        
        doc.addHeading2("二、应用部署");
        doc.addParagraph("1. 上传应用包");
        doc.addCodeBlock("scp target/redis-cluster-manager-1.0.0.jar user@server:/opt/redis-manager/");
        doc.addParagraph("2. 创建启动脚本 start.sh");
        doc.addCodeBlock("#!/bin/bash\ncd /opt/redis-manager\nnohup java -jar redis-cluster-manager-1.0.0.jar > logs/app.log 2>&1 &\necho $! > app.pid");
        doc.addParagraph("3. 创建停止脚本 stop.sh");
        doc.addCodeBlock("#!/bin/bash\nkill $(cat /opt/redis-manager/app.pid)\nrm /opt/redis-manager/app.pid");
        doc.addParagraph("4. 赋予执行权限");
        doc.addCodeBlock("chmod +x start.sh stop.sh");
        doc.addEmptyLine();

        // 初始化配置
        doc.addHeading1("初始化配置");
        doc.addHeading2("首次登录");
        doc.addParagraph("1. 访问应用URL: http://server-ip:8080");
        doc.addParagraph("2. 使用默认账号登录（admin/admin）");
        doc.addParagraph("3. 立即修改默认密码");
        doc.addEmptyLine();
        
        doc.addHeading2("系统配置");
        doc.addParagraph("1. 进入系统配置页面");
        doc.addParagraph("2. 修改系统名称、上传Logo和Icon");
        doc.addParagraph("3. 设置登录页文案和版权信息");
        doc.addEmptyLine();

        // 数据备份
        doc.addHeading1("数据备份策略");
        doc.addHeading2("自动备份");
        doc.addParagraph("系统默认已开启自动备份：");
        doc.addCodeBlock("redis:\n  manager:\n    backup:\n      enabled: true\n      cron: \"0 0 2 * * ?\"\n      retain-days: 30\n      directory: ./data/backup");
        doc.addEmptyLine();
        
        doc.addHeading2("备份验证");
        doc.addParagraph("部署后请手动执行一次备份，确认备份功能正常。");
        doc.addEmptyLine();

        // 安全配置
        doc.addHeading1("安全配置");
        doc.addHeading2("密码管理");
        doc.addParagraph("• 首次登录后立即修改默认密码");
        doc.addParagraph("• 使用强密码策略（8位以上，大小写+数字+特殊字符）");
        doc.addParagraph("• 定期更新密码（建议90天）");
        doc.addEmptyLine();
        
        doc.addHeading2("网络安全");
        doc.addParagraph("• 配置防火墙，仅开放必要端口");
        doc.addParagraph("• 使用Nginx反向代理和HTTPS");
        doc.addEmptyLine();

        // 故障处理
        doc.addHeading1("故障处理");
        doc.addHeading2("应用无法启动");
        doc.addParagraph("1. 检查JDK版本是否正确");
        doc.addParagraph("2. 检查端口是否被占用");
        doc.addParagraph("3. 查看日志 logs/redis-manager.log");
        doc.addEmptyLine();
        
        doc.addHeading2("数据库恢复");
        doc.addCodeBlock("./stop.sh\ncp backup/redis-manager-backup-xxxx.mv.db data/redis-manager.mv.db\n./start.sh");
        doc.addEmptyLine();

        // 附录
        doc.addHeading1("附录");
        doc.addHeading2("目录结构");
        doc.addCodeBlock("/opt/redis-manager/\n├── redis-cluster-manager-1.0.0.jar\n├── start.sh\n├── stop.sh\n├── logs/\n├── data/\n│   ├── redis-manager.mv.db\n│   └── backup/\n└── uploads/");
        doc.addEmptyLine();
        
        doc.addHeading2("常用命令");
        XWPFTable cmdTable = doc.createTable(5, 2);
        doc.setCellText(cmdTable.getRow(0).getCell(0), "操作", true);
        doc.setCellText(cmdTable.getRow(0).getCell(1), "命令", true);
        doc.setCellText(cmdTable.getRow(1).getCell(0), "启动应用", false);
        doc.setCellText(cmdTable.getRow(1).getCell(1), "./start.sh", false);
        doc.setCellText(cmdTable.getRow(2).getCell(0), "停止应用", false);
        doc.setCellText(cmdTable.getRow(2).getCell(1), "./stop.sh", false);
        doc.setCellText(cmdTable.getRow(3).getCell(0), "查看日志", false);
        doc.setCellText(cmdTable.getRow(3).getCell(1), "tail -f logs/redis-manager.log", false);
        doc.setCellText(cmdTable.getRow(4).getCell(0), "查看进程", false);
        doc.setCellText(cmdTable.getRow(4).getCell(1), "ps -ef | grep redis-manager", false);
        doc.addEmptyLine();

        doc.addParagraph("本文档由技术部编制，如有更新请及时检索最新版本。");
        
        doc.save(outputPath);
        doc.close();
        System.out.println("投产上线文档已生成: " + outputPath);
    }

    public static void main(String[] args) {
        try {
            java.io.File docsDir = new java.io.File("docs");
            if (!docsDir.exists()) {
                docsDir.mkdirs();
            }
            generateUserManual("docs/系统功能操作手册.docx");
            generateDeploymentGuide("docs/投产上线文档.docx");
            System.out.println("\n文档生成完成！位置: docs/");
        } catch (IOException e) {
            System.err.println("生成文档失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
