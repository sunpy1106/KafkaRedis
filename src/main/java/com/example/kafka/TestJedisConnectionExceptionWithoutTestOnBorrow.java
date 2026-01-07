package com.example.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisConnectionException;

/**
 * 测试类：在 testOnBorrow=false 的情况下触发 JedisConnectionException
 *
 * 重要：模拟真实生产环境故障场景
 * - testOnBorrow=false（默认配置）
 * - Redis 网络正常
 * - 但仍然报 JedisConnectionException: "Could not get a resource from the pool"
 */
public class TestJedisConnectionExceptionWithoutTestOnBorrow {
    private static final Logger logger = LoggerFactory.getLogger(TestJedisConnectionExceptionWithoutTestOnBorrow.class);

    public static void main(String[] args) {
        logger.info("========== testOnBorrow=false 场景测试 ==========\n");

        // 场景1：Redis 认证失败（密码错误）
        testAuthenticationFailure();

        // 场景2：连接到错误端口（makeObject 失败）
        testInvalidPort();

        // 场景3：连接超时（极短 timeout）
        testConnectionTimeout();

        logger.info("\n========== 测试完成 ==========");
    }

    /**
     * 场景1：Redis 认证失败
     *
     * 原理：
     * - Redis 设置了密码，但客户端使用错误密码
     * - makeObject() 时会尝试认证
     * - 认证失败抛出异常
     * - 被包装为 JedisConnectionException
     */
    private static void testAuthenticationFailure() {
        logger.info("【场景1】Redis 认证失败（密码错误）");
        logger.info("配置: testOnBorrow=false, password=wrongpassword\n");

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(0);  // 避免连接池创建时就失败
        poolConfig.setMaxWaitMillis(3000);
        poolConfig.setTestOnBorrow(false);  // ⚠️ 明确设置为 false

        JedisPool jedisPool = null;

        try {
            // 使用错误的密码（假设 Redis 需要密码）
            jedisPool = new JedisPool(poolConfig, "localhost", 6379, 3000, "wrongpassword");
            logger.info("1. 连接池创建成功（minIdle=0 不会立即创建连接）\n");

            logger.info("2. 尝试获取连接...");
            logger.info("   testOnBorrow=false，但 makeObject() 会尝试认证\n");

            long startTime = System.currentTimeMillis();

            try {
                Jedis jedis = jedisPool.getResource();
                logger.warn("✅ 获取连接成功（Redis 可能未设置密码）");
                jedis.close();

            } catch (JedisConnectionException e) {
                long elapsed = System.currentTimeMillis() - startTime;

                logger.error("❌ 获取连接失败！耗时: {}ms", elapsed);
                logger.error("异常类型: {}", e.getClass().getName());
                logger.error("异常消息: {}", e.getMessage());

                if (e.getCause() != null) {
                    logger.error("根本原因: {}", e.getCause().getClass().getName());
                    logger.error("原因消息: {}", e.getCause().getMessage());
                }

                if ("Could not get a resource from the pool".equals(e.getMessage())) {
                    logger.info("\n🎯 成功触发目标异常！");
                    logger.info("✅ 异常类型: JedisConnectionException");
                    logger.info("✅ 异常消息: Could not get a resource from the pool");
                    logger.info("✅ testOnBorrow: false");
                    logger.info("\n触发原因: Redis 认证失败");
                }

            } catch (Exception e) {
                logger.error("发生其他异常: {}", e.getClass().getName(), e);
            }

        } catch (Exception e) {
            logger.error("测试过程发生异常", e);
        } finally {
            if (jedisPool != null && !jedisPool.isClosed()) {
                jedisPool.close();
            }
        }
    }

    /**
     * 场景2：连接到错误端口
     *
     * 原理：
     * - testOnBorrow=false
     * - 但 makeObject() 创建新连接时会尝试连接
     * - 连接失败抛出异常
     */
    private static void testInvalidPort() {
        logger.info("\n【场景2】连接到错误端口（makeObject 失败）");
        logger.info("配置: testOnBorrow=false, port=16379\n");

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(0);
        poolConfig.setMaxWaitMillis(3000);
        poolConfig.setTestOnBorrow(false);  // ⚠️ 明确设置为 false

        JedisPool jedisPool = null;

        try {
            jedisPool = new JedisPool(poolConfig, "localhost", 16379, 1000);
            logger.info("1. 连接池创建成功\n");

            logger.info("2. 尝试获取连接...");
            logger.info("   testOnBorrow=false，但仍需创建连接\n");

            long startTime = System.currentTimeMillis();

            try {
                Jedis jedis = jedisPool.getResource();
                logger.warn("✅ 获取连接成功（不应该发生）");
                jedis.close();

            } catch (JedisConnectionException e) {
                long elapsed = System.currentTimeMillis() - startTime;

                logger.error("❌ 获取连接失败！耗时: {}ms", elapsed);
                logger.error("异常类型: {}", e.getClass().getName());
                logger.error("异常消息: {}", e.getMessage());

                if (e.getCause() != null) {
                    logger.error("根本原因: {}", e.getCause().getClass().getName());
                    logger.error("原因消息: {}", e.getCause().getMessage());
                }

                if ("Could not get a resource from the pool".equals(e.getMessage())) {
                    logger.info("\n🎯 成功触发目标异常！");
                    logger.info("✅ 异常类型: JedisConnectionException");
                    logger.info("✅ 异常消息: Could not get a resource from the pool");
                    logger.info("✅ testOnBorrow: false");
                    logger.info("\n触发原因: makeObject() 创建连接失败");
                }

            } catch (Exception e) {
                logger.error("发生其他异常: {}", e.getClass().getName(), e);
            }

        } catch (Exception e) {
            logger.error("测试过程发生异常", e);
        } finally {
            if (jedisPool != null && !jedisPool.isClosed()) {
                jedisPool.close();
            }
        }
    }

    /**
     * 场景3：连接超时
     *
     * 原理：
     * - testOnBorrow=false
     * - 连接到不可达主机，超时时间很短
     * - makeObject() 超时失败
     */
    private static void testConnectionTimeout() {
        logger.info("\n【场景3】连接超时（不可达主机）");
        logger.info("配置: testOnBorrow=false, host=192.0.2.1, timeout=2000ms\n");

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(0);
        poolConfig.setMaxWaitMillis(3000);
        poolConfig.setTestOnBorrow(false);  // ⚠️ 明确设置为 false

        JedisPool jedisPool = null;

        try {
            jedisPool = new JedisPool(poolConfig, "192.0.2.1", 6379, 2000);
            logger.info("1. 连接池创建成功\n");

            logger.info("2. 尝试获取连接...");
            logger.info("   testOnBorrow=false，但 makeObject() 会尝试连接\n");

            long startTime = System.currentTimeMillis();

            try {
                Jedis jedis = jedisPool.getResource();
                logger.warn("✅ 获取连接成功（不应该发生）");
                jedis.close();

            } catch (JedisConnectionException e) {
                long elapsed = System.currentTimeMillis() - startTime;

                logger.error("❌ 获取连接失败！耗时: {}ms", elapsed);
                logger.error("异常类型: {}", e.getClass().getName());
                logger.error("异常消息: {}", e.getMessage());

                if (e.getCause() != null) {
                    logger.error("根本原因: {}", e.getCause().getClass().getName());
                    logger.error("原因消息: {}", e.getCause().getMessage());
                }

                if ("Could not get a resource from the pool".equals(e.getMessage())) {
                    logger.info("\n🎯 成功触发目标异常！");
                    logger.info("✅ 异常类型: JedisConnectionException");
                    logger.info("✅ 异常消息: Could not get a resource from the pool");
                    logger.info("✅ testOnBorrow: false");
                    logger.info("\n触发原因: makeObject() 连接超时");
                }

            } catch (Exception e) {
                logger.error("发生其他异常: {}", e.getClass().getName(), e);
            }

        } catch (Exception e) {
            logger.error("测试过程发生异常", e);
        } finally {
            if (jedisPool != null && !jedisPool.isClosed()) {
                jedisPool.close();
            }
        }
    }
}
