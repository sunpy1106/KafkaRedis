package com.example.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisConnectionException;

/**
 * 测试类：自动化测试 JedisConnectionException: Could not get a resource from the pool
 *
 * 方案：testOnBorrow=true + 连接到错误的端口
 * - 连接池创建时不会立即测试连接
 * - 但 getResource() 时会因为 testOnBorrow=true 而尝试验证
 * - 连接到错误端口会导致验证失败
 * - 抛出 JedisConnectionException
 */
public class TestJedisConnectionExceptionAuto {
    private static final Logger logger = LoggerFactory.getLogger(TestJedisConnectionExceptionAuto.class);

    public static void main(String[] args) {
        logger.info("========== 自动化测试 JedisConnectionException ==========\n");

        // 场景1：testOnBorrow + 错误端口
        testWithInvalidPort();

        // 场景2：testOnBorrow + 不可达主机
        testWithUnreachableHost();

        logger.info("\n========== 测试完成 ==========");
    }

    /**
     * 场景1：testOnBorrow=true + 连接到错误端口
     */
    private static void testWithInvalidPort() {
        logger.info("【场景1】testOnBorrow=true + 错误端口");
        logger.info("配置: port=16379 (未监听), testOnBorrow=true\n");

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(0);  // minIdle=0 避免创建时就失败
        poolConfig.setMaxWaitMillis(3000);
        poolConfig.setTestOnBorrow(true);  // ⚠️ 关键配置

        JedisPool jedisPool = null;

        try {
            // 连接到错误的端口
            jedisPool = new JedisPool(poolConfig, "localhost", 16379, 1000);
            logger.info("1. 连接池创建成功（连接池创建不会立即测试连接）\n");

            logger.info("2. 尝试获取连接...");
            logger.info("   testOnBorrow=true 会触发 PING 验证");
            logger.info("   但端口16379未监听，连接失败\n");

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
                } else {
                    logger.info("\n✅ 触发了 JedisConnectionException");
                    logger.info("   但消息不同: {}", e.getMessage());
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
     * 场景2：testOnBorrow=true + 不可达主机
     */
    private static void testWithUnreachableHost() {
        logger.info("\n【场景2】testOnBorrow=true + 不可达主机");
        logger.info("配置: host=192.0.2.1 (保留IP), testOnBorrow=true, timeout=2000ms\n");

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(0);  // minIdle=0 避免创建时就失败
        poolConfig.setMaxWaitMillis(3000);
        poolConfig.setTestOnBorrow(true);  // ⚠️ 关键配置

        JedisPool jedisPool = null;

        try {
            // 连接到不可达的主机
            jedisPool = new JedisPool(poolConfig, "192.0.2.1", 6379, 2000);
            logger.info("1. 连接池创建成功\n");

            logger.info("2. 尝试获取连接...");
            logger.info("   将尝试连接到不可达的主机\n");

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
                } else {
                    logger.info("\n✅ 触发了 JedisConnectionException");
                    logger.info("   但消息不同: {}", e.getMessage());
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
