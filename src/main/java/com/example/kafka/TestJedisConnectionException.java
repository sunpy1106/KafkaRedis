package com.example.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisConnectionException;

/**
 * 测试类：模拟 JedisConnectionException: Could not get a resource from the pool
 *
 * 关键原理：
 * - testOnBorrow=true: 从池中获取连接时会执行 PING 验证
 * - Redis服务器关闭后，验证失败会抛出异常
 * - 这个异常被包装成 JedisConnectionException
 */
public class TestJedisConnectionException {
    private static final Logger logger = LoggerFactory.getLogger(TestJedisConnectionException.class);

    public static void main(String[] args) {
        logger.info("========== 测试 JedisConnectionException ==========\n");

        // 场景1：testOnBorrow + Redis关闭（需要手动操作）
        testWithRedisShutdown();

        logger.info("\n========== 测试完成 ==========");
    }

    /**
     * 场景1：testOnBorrow=true + Redis服务器关闭
     *
     * 操作步骤：
     * 1. 程序启动，连接到Redis
     * 2. 倒计时30秒，等待手动停止Redis
     * 3. 程序尝试获取连接
     * 4. 由于 testOnBorrow=true，会执行 PING 验证
     * 5. Redis已关闭，PING失败
     * 6. 抛出 JedisConnectionException: Could not get a resource from the pool
     */
    private static void testWithRedisShutdown() {
        logger.info("【场景1】testOnBorrow=true + Redis服务器关闭");
        logger.info("说明：程序将创建连接池，请在倒计时期间手动停止Redis\n");

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(2);
        poolConfig.setMaxWaitMillis(3000);

        // ⚠️ 关键配置：从池中获取连接时验证
        poolConfig.setTestOnBorrow(true);

        JedisPool jedisPool = null;

        try {
            jedisPool = new JedisPool(poolConfig, "localhost", 6379, 3000);

            logger.info("1. 连接池创建成功");
            logger.info("   配置: testOnBorrow=true, maxTotal=8\n");

            // 先测试连接是否正常
            logger.info("2. 测试初始连接...");
            try (Jedis jedis = jedisPool.getResource()) {
                String pong = jedis.ping();
                logger.info("   PING响应: {} ✅ 连接正常\n", pong);
            }

            // 倒计时，等待手动停止Redis
            logger.info("⏰ 请在接下来的30秒内执行以下命令来停止Redis:");
            logger.info("   docker stop kafka-redis");
            logger.info("   (或者 docker-compose stop redis)");
            logger.info("   (或者 redis-cli shutdown)\n");

            for (int i = 30; i > 0; i--) {
                if (i % 5 == 0 || i <= 3) {
                    logger.info("   倒计时: {}秒...", i);
                }
                Thread.sleep(1000);
            }

            logger.info("\n3. 倒计时结束，尝试从连接池获取连接...");
            logger.info("   由于 testOnBorrow=true，会执行 PING 验证\n");

            long startTime = System.currentTimeMillis();

            try {
                // 关键操作：getResource() 会触发验证
                Jedis jedis = jedisPool.getResource();

                // 如果能走到这里，说明Redis还在运行
                logger.warn("✅ 获取连接成功（Redis仍在运行）");
                logger.warn("   如需观察异常，请重新运行测试并停止Redis");
                jedis.close();

            } catch (JedisConnectionException e) {
                long elapsed = System.currentTimeMillis() - startTime;

                logger.error("\n❌ 获取连接失败！耗时: {}ms", elapsed);
                logger.error("异常类型: {}", e.getClass().getName());
                logger.error("异常消息: {}", e.getMessage());

                if (e.getCause() != null) {
                    logger.error("根本原因: {}", e.getCause().getClass().getName());
                    logger.error("原因消息: {}", e.getCause().getMessage());
                }

                // 检查是否是目标异常
                if ("Could not get a resource from the pool".equals(e.getMessage())) {
                    logger.info("\n🎯 成功触发目标异常！");
                    logger.info("异常类型: JedisConnectionException");
                    logger.info("异常消息: Could not get a resource from the pool");
                    logger.info("\n触发机制:");
                    logger.info("1. testOnBorrow=true → 获取连接时执行验证");
                    logger.info("2. Redis服务器已关闭 → 验证失败");
                    logger.info("3. 验证异常被包装 → JedisConnectionException");
                } else {
                    logger.info("\n✅ 成功触发 JedisConnectionException");
                    logger.info("   消息: {}", e.getMessage());
                }

            } catch (Exception e) {
                logger.error("\n发生其他类型异常: {}", e.getClass().getName());
                logger.error("异常消息: {}", e.getMessage(), e);
            }

        } catch (InterruptedException e) {
            logger.error("测试被中断", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("测试过程发生异常", e);
        } finally {
            if (jedisPool != null && !jedisPool.isClosed()) {
                jedisPool.close();
                logger.info("\n连接池已关闭");
            }

            logger.info("\n如果Redis已停止，请重新启动:");
            logger.info("  docker start kafka-redis");
        }
    }
}
