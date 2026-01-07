package com.example.kafka.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Redis服务类，用于消息去重
 */
public class RedisService {
    private static final Logger logger = LoggerFactory.getLogger(RedisService.class);
    private static final String UUID_PREFIX = "message:uuid:";

    private JedisPool jedisPool;
    private int expireSeconds;

    public RedisService() {
        initJedisPool();
    }

    /**
     * 初始化Jedis连接池
     */
    private void initJedisPool() {
        Properties props = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (input == null) {
                logger.error("无法找到配置文件 application.properties");
                throw new RuntimeException("配置文件不存在");
            }
            props.load(input);

            String host = props.getProperty("redis.host", "localhost");
            int port = Integer.parseInt(props.getProperty("redis.port", "6379"));
            int timeout = Integer.parseInt(props.getProperty("redis.timeout", "3000"));
            int database = Integer.parseInt(props.getProperty("redis.database", "0"));
            String password = props.getProperty("redis.password");
            this.expireSeconds = Integer.parseInt(props.getProperty("redis.uuid.expire.seconds", "604800"));

            JedisPoolConfig poolConfig = new JedisPoolConfig();
            // 从配置文件读取连接池参数
            poolConfig.setMaxTotal(Integer.parseInt(props.getProperty("redis.pool.maxTotal", "20")));
            poolConfig.setMaxIdle(Integer.parseInt(props.getProperty("redis.pool.maxIdle", "10")));
            poolConfig.setMinIdle(Integer.parseInt(props.getProperty("redis.pool.minIdle", "5")));
            poolConfig.setMaxWaitMillis(Long.parseLong(props.getProperty("redis.pool.maxWaitMillis", "3000")));
            poolConfig.setTestOnBorrow(Boolean.parseBoolean(props.getProperty("redis.pool.testOnBorrow", "true")));
            poolConfig.setTestWhileIdle(Boolean.parseBoolean(props.getProperty("redis.pool.testWhileIdle", "false")));

            if (password != null && !password.trim().isEmpty()) {
                jedisPool = new JedisPool(poolConfig, host, port, timeout, password, database);
            } else {
                jedisPool = new JedisPool(poolConfig, host, port, timeout, null, database);
            }

            logger.info("Redis连接池初始化成功: {}:{}, maxTotal={}, maxWaitMillis={}ms",
                host, port, poolConfig.getMaxTotal(), poolConfig.getMaxWaitMillis());
        } catch (IOException e) {
            logger.error("加载配置文件失败", e);
            throw new RuntimeException("加载配置文件失败", e);
        }
    }

    /**
     * 检查UUID是否已存在（消息是否已发送）
     *
     * @param uuid 消息UUID
     * @return true-已存在，false-不存在
     */
    public boolean isUuidExists(String uuid) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = UUID_PREFIX + uuid;

            // ⭐ 模拟慢速Redis操作（用于压力测试，让连接长时间被占用）
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            Boolean exists = jedis.exists(key);
            logger.debug("检查UUID: {}, 结果: {}", uuid, exists);
            return exists;
        } catch (Exception e) {
            logger.error("检查UUID失败: {}", uuid, e);
            throw new RuntimeException("Redis操作失败", e);
        }
    }

    /**
     * 保存UUID到Redis（标记消息已发送）
     *
     * @param uuid 消息UUID
     * @return true-保存成功，false-保存失败
     */
    public boolean saveUuid(String uuid) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = UUID_PREFIX + uuid;
            String value = String.valueOf(System.currentTimeMillis());

            // ⭐ 模拟慢速Redis操作（用于压力测试，让连接长时间被占用）
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 设置键值对，并设置过期时间
            String result = jedis.setex(key, expireSeconds, value);
            boolean success = "OK".equals(result);

            if (success) {
                logger.info("UUID保存成功: {}, 过期时间: {}秒", uuid, expireSeconds);
            } else {
                logger.warn("UUID保存失败: {}", uuid);
            }

            return success;
        } catch (Exception e) {
            logger.error("保存UUID失败: {}", uuid, e);
            throw new RuntimeException("Redis操作失败", e);
        }
    }

    /**
     * 删除UUID（用于测试或异常处理）
     *
     * @param uuid 消息UUID
     * @return true-删除成功，false-删除失败
     */
    public boolean deleteUuid(String uuid) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = UUID_PREFIX + uuid;
            Long result = jedis.del(key);
            logger.debug("删除UUID: {}, 结果: {}", uuid, result > 0);
            return result > 0;
        } catch (Exception e) {
            logger.error("删除UUID失败: {}", uuid, e);
            throw new RuntimeException("Redis操作失败", e);
        }
    }

    /**
     * 关闭Redis连接池
     */
    public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            logger.info("Redis连接池已关闭");
        }
    }
}
