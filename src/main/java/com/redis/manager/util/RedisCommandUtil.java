package com.redis.manager.util;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Redis命令工具类
 * 用于执行Redis命令和获取监控指标
 */
public class RedisCommandUtil {

    /**
     * 获取Redis实例信息（无密码）
     */
    public static Map<String, String> getRedisInfo(String host, int port) {
        return getRedisInfo(host, port, null);
    }

    /**
     * 获取Redis实例信息（带密码）
     */
    public static Map<String, String> getRedisInfo(String host, int port, String password) {
        Map<String, String> info = new HashMap<>();
        Jedis jedis = null;
        
        try {
            jedis = new Jedis(host, port, 5000);
            
            // 如果有密码，先认证
            if (password != null && !password.isEmpty()) {
                jedis.auth(password);
            }
            
            jedis.connect();
            
            String infoStr = jedis.info();
            parseInfo(infoStr, info);
            
            // 测试PING
            String pong = jedis.ping();
            info.put("ping", pong);
            info.put("status", "1");
            
        } catch (Exception e) {
            info.put("status", "0");
            info.put("error", e.getMessage());
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
        
        return info;
    }

    /**
     * 解析INFO命令返回的信息
     */
    private static void parseInfo(String infoStr, Map<String, String> info) {
        String[] lines = infoStr.split("\r?\n");
        for (String line : lines) {
            if (line.startsWith("#") || line.trim().isEmpty()) {
                continue;
            }
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                info.put(key, value);
            }
        }
    }

    /**
     * 获取内存使用信息
     */
    public static long getUsedMemory(String host, int port) {
        try {
            Map<String, String> info = getRedisInfo(host, port);
            String usedMemory = info.get("used_memory");
            if (usedMemory != null) {
                return Long.parseLong(usedMemory);
            }
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }

    /**
     * 获取连接数
     */
    public static int getConnectedClients(String host, int port) {
        try {
            Map<String, String> info = getRedisInfo(host, port);
            String clients = info.get("connected_clients");
            if (clients != null) {
                return Integer.parseInt(clients);
            }
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }

    /**
     * 获取角色
     */
    public static String getRole(String host, int port) {
        try {
            Map<String, String> info = getRedisInfo(host, port);
            return info.getOrDefault("role", "unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 获取数据库键数量
     */
    public static long getKeyCount(String host, int port) {
        long total = 0;
        Jedis jedis = null;
        
        try {
            jedis = new Jedis(host, port, 5000);
            for (int db = 0; db < 16; db++) {
                try {
                    jedis.select(db);
                    total += jedis.dbSize();
                } catch (Exception e) {
                    break;
                }
            }
        } catch (Exception e) {
            // ignore
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
        
        return total;
    }

    /**
     * 动态配置Redis参数
     */
    public static boolean configSet(String host, int port, String parameter, String value) {
        Jedis jedis = null;
        try {
            jedis = new Jedis(host, port, 5000);
            String result = jedis.configSet(parameter, value);
            return "OK".equals(result);
        } catch (Exception e) {
            return false;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * 获取配置项
     */
    public static String configGet(String host, int port, String parameter) {
        Jedis jedis = null;
        try {
            jedis = new Jedis(host, port, 5000);
            List<String> values = jedis.configGet(parameter);
            if (values != null && values.size() >= 2) {
                return values.get(1);
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * 保存配置到配置文件
     */
    public static boolean configRewrite(String host, int port) {
        Jedis jedis = null;
        try {
            jedis = new Jedis(host, port, 5000);
            String result = jedis.configRewrite();
            return "OK".equals(result);
        } catch (Exception e) {
            return false;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * 检查Redis是否可连接
     */
    public static boolean isConnectable(String host, int port) {
        Jedis jedis = null;
        try {
            jedis = new Jedis(host, port, 3000);
            jedis.connect();
            return "PONG".equals(jedis.ping());
        } catch (Exception e) {
            return false;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }
}
