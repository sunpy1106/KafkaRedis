package com.example.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisConnectionException;

/**
 * 测试类：模拟Redis网络异常
 * 场景：连接建立后网络中断、服务器关闭等
 *
 * 注意：此测试需要手动操作来触发异常
 * 1. Broken Pipe: 需要在连接建立后手动停止Redis服务器
 * 2. Connection Reset: 需要在连接建立后手动重启Redis服务器
 */
public class TestRedisNetworkException {
    private static final Logger logger = LoggerFactory.getLogger(TestRedisNetworkException.class);

    public static void main(String[] args) {
        logger.info("========== 开始测试Redis网络异常 ==========");
        logger.info("提示：某些测试需要手动操作Redis服务器才能触发异常\n");

        // 场景1：模拟网络中断（需要手动停止Redis）
        testBrokenPipe();

        logger.info("========== 测试完成 ==========\n");
    }

    /**
     * 场景1：模拟Broken Pipe异常
     * 操作步骤：
     * 1. 运行此程序
     * 2. 看到提示后，手动停止Redis容器: docker stop kafka-redis
     * 3. 观察异常输出
     * 4. 重新启动Redis容器: docker start kafka-redis
     */
    private static void testBrokenPipe() {
        logger.info("\n【场景1】模拟网络中断 - Broken Pipe");
        logger.info("说明：程序将获取一个连接并保持，请在看到提示后手动停止Redis\n");

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(2);
        poolConfig.setMaxWaitMillis(3000);
        poolConfig.setTestOnBorrow(false); // 关闭testOnBorrow，让连接在使用时才检测

        JedisPool jedisPool = null;
        Jedis jedis = null;

        try {
            jedisPool = new JedisPool(poolConfig, "localhost", 6379, 3000);

            logger.info("1. 正在获取Redis连接...");
            jedis = jedisPool.getResource();

            logger.info("2. 连接获取成功！执行PING测试...");
            String pong = jedis.ping();
            logger.info("   PING响应: {}", pong);

            logger.info("\n⏰ 请在接下来的30秒内执行以下命令来停止Redis:");
            logger.info("   docker stop kafka-redis");
            logger.info("   (如果Redis不是容器运行，请使用 redis-cli shutdown)\n");

            // 倒计时
            for (int i = 30; i > 0; i--) {
                if (i % 5 == 0 || i <= 3) {
                    logger.info("   倒计时: {}秒...", i);
                }
                Thread.sleep(1000);
            }

            logger.info("\n3. 倒计时结束，现在尝试执行Redis操作...");
            long startTime = System.currentTimeMillis();

            try {
                // 尝试执行操作，如果Redis已停止，会触发异常
                String result = jedis.set("test:network", "value");
                logger.info("   SET操作成功: {}", result);

                String value = jedis.get("test:network");
                logger.info("   GET操作成功: {}", value);

                logger.warn("\n⚠️  操作成功，说明Redis仍在运行");
                logger.warn("    如需观察Broken Pipe异常，请重新运行测试并停止Redis");

            } catch (JedisConnectionException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                logger.error("\n❌ Redis操作失败！耗时: {}ms", elapsed);
                logger.error("异常类型: {}", e.getClass().getName());
                logger.error("异常消息: {}", e.getMessage());

                if (e.getCause() != null) {
                    logger.error("根本原因: {}", e.getCause().getClass().getName());
                    logger.error("原因消息: {}", e.getCause().getMessage());
                }

                // 检查是否是预期的异常类型
                String message = e.getMessage();
                if (message != null) {
                    if (message.contains("Broken pipe") || message.contains("Connection reset")) {
                        logger.info("\n✅ 成功触发网络异常: {}", message);
                    } else if (message.contains("Unexpected end of stream")) {
                        logger.info("\n✅ 成功触发网络异常: Unexpected end of stream");
                    } else {
                        logger.info("\n✅ 成功触发 JedisConnectionException");
                    }
                }
            }

        } catch (InterruptedException e) {
            logger.error("测试被中断", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("测试过程发生异常", e);
        } finally {
            // 清理资源
            if (jedis != null) {
                try {
                    jedis.close();
                } catch (Exception e) {
                    logger.debug("关闭Jedis连接时发生异常（正常，因为Redis可能已停止）", e);
                }
            }

            if (jedisPool != null && !jedisPool.isClosed()) {
                jedisPool.close();
                logger.info("\n连接池已关闭");
            }

            logger.info("\n如果Redis已停止，请重新启动:");
            logger.info("  docker start kafka-redis");
        }
    }
}
