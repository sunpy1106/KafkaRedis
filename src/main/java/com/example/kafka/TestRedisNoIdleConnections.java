package com.example.kafka;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 验证 testOnBorrow=false 也能触发 "Could not get a resource from the pool"
 *
 * 关键场景：无空闲连接 + Redis不可达
 *
 * 测试步骤：
 * 1. 创建连接池 (testOnBorrow=false, maxTotal=3, minIdle=0)
 * 2. 借出所有连接并持有（不归还）
 * 3. 停止Redis
 * 4. 尝试获取新连接 → 池中无空闲连接 → 尝试创建新连接 → 失败
 */
public class TestRedisNoIdleConnections {
    private static final Logger logger = LoggerFactory.getLogger(TestRedisNoIdleConnections.class);

    public static void main(String[] args) {
        logger.info("========================================");
        logger.info("验证: testOnBorrow=false 触发目标异常");
        logger.info("场景: 无空闲连接 + Redis不可达");
        logger.info("========================================");
        logger.info("");

        // Step 1: 创建连接池配置
        logger.info("Step 1: 创建连接池 (testOnBorrow=false)");
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(10);          // 池子足够大，不会耗尽
        poolConfig.setMaxIdle(10);
        poolConfig.setMinIdle(0);            // 不预创建连接
        poolConfig.setTestOnBorrow(false);   // 关键：不验证
        poolConfig.setTestWhileIdle(false);  // 不清理空闲连接
        poolConfig.setMaxWaitMillis(1000);
        poolConfig.setBlockWhenExhausted(false); // 不等待，直接尝试创建

        logger.info("配置: maxTotal=10, minIdle=0, testOnBorrow=false, blockWhenExhausted=false");

        JedisPool jedisPool = new JedisPool(poolConfig, "localhost", 6379, 2000);
        List<Jedis> heldConnections = new ArrayList<>();

        try {
            // Step 2: 借出所有连接并持有
            logger.info("");
            logger.info("Step 2: 借出所有3个连接并持有（不归还）");
            for (int i = 0; i < 3; i++) {
                Jedis jedis = jedisPool.getResource();
                jedis.ping(); // 确保连接有效
                heldConnections.add(jedis);
                logger.info("  持有连接 {}/3", i + 1);
            }

            // Step 3: 验证池状态
            logger.info("");
            logger.info("Step 3: 验证连接池状态");
            logger.info("  Active: {}, Idle: {}, MaxTotal: {}",
                jedisPool.getNumActive(),
                jedisPool.getNumIdle(),
                poolConfig.getMaxTotal());
            logger.info("  预期: Active=3, Idle=0 (有连接在使用，但池未满)");

            // Step 4: 停止Redis
            logger.info("");
            logger.info("Step 4: 停止Redis服务");
            try {
                Process process = Runtime.getRuntime().exec("docker stop kafka-redis");
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info("  Docker output: {}", line);
                }
                process.waitFor();
                logger.info("  Redis已停止");
            } catch (Exception e) {
                logger.error("  停止Redis失败", e);
                return;
            }

            // Step 5: 等待Redis完全停止
            logger.info("");
            logger.info("Step 5: 等待Redis完全停止...");
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Step 6: 尝试获取新连接（关键时刻）
            logger.info("");
            logger.info("Step 6: 尝试获取第4个连接");
            logger.info("");
            logger.info("🎯 当前状态:");
            logger.info("   - 池中无空闲连接 (Idle=0)");
            logger.info("   - 所有连接都在使用中 (Active=3)");
            logger.info("   - 新请求到达");
            logger.info("");
            logger.info("🎯 预期流程:");
            logger.info("   1. borrowObject() 检查空闲队列 → 无空闲连接 (Idle=0)");
            logger.info("   2. 检查是否可以创建新连接 → 可以 (Active=3 < MaxTotal=10)");
            logger.info("   3. 调用 create() → JedisFactory.makeObject()");
            logger.info("   4. jedis.connect() → 尝试连接 localhost:6379");
            logger.info("   5. Redis已停止 → ConnectException: Connection refused");
            logger.info("   6. 包装为: JedisConnectionException: Failed connecting to host");
            logger.info("   7. Pool.getResource() catch (Exception e) 捕获");
            logger.info("   8. 🎯 转换为: JedisConnectionException: Could not get a resource from the pool");
            logger.info("");
            logger.info("注意: testOnBorrow=false 在这个场景下无关紧要！");
            logger.info("      因为根本没有空闲连接可以验证，必须创建新连接！");
            logger.info("");

            try {
                Jedis jedis = jedisPool.getResource();
                logger.info("❌ 意外成功获取连接");
                jedis.close();

            } catch (Exception e) {
                logger.error("================== 捕获到异常 ==================");
                logger.error("异常类型: {}", e.getClass().getName());
                logger.error("异常消息: {}", e.getMessage());
                logger.error("");

                // 提取根本原因
                Throwable cause = e;
                while (cause.getCause() != null && cause.getCause() != cause) {
                    cause = cause.getCause();
                }
                logger.error("根本原因: {}", cause.getClass().getName());
                logger.error("根本原因消息: {}", cause.getMessage());
                logger.error("");

                // 判断是否是目标异常
                if (e.getMessage() != null &&
                    e.getMessage().contains("Could not get a resource from the pool")) {
                    logger.info("✅✅✅ 成功触发目标异常！");
                    logger.info("");
                    logger.info("🎉 验证成功：testOnBorrow=false 也能触发此异常！");
                    logger.info("");
                    logger.info("关键点:");
                    logger.info("  - testOnBorrow=false 只影响「有空闲连接时是否验证」");
                    logger.info("  - 当「无空闲连接」时，必须创建新连接");
                    logger.info("  - 创建失败 → 触发相同的异常");
                    logger.info("");
                    logger.info("生产环境场景:");
                    logger.info("  - maxTotal=16, 高并发耗尽连接池");
                    logger.info("  - 第17个请求到达，池满，等待");
                    logger.info("  - 如果此时Redis重启或网络抖动");
                    logger.info("  - 等待超时后尝试创建连接 → 失败");
                    logger.info("  - 或者连接归还后立即被清理，新请求创建连接 → 失败");
                } else if (e.getMessage() != null &&
                           e.getMessage().contains("Unexpected end of stream")) {
                    logger.warn("❌ 触发了不同的异常：Unexpected end of stream");
                    logger.warn("这说明池中还有空闲连接被返回了");
                } else {
                    logger.error("❓ 触发了其他异常");
                }

                logger.error("");
                logger.error("完整堆栈:");
                e.printStackTrace();
                logger.error("==============================================");
            }

        } finally {
            // 清理：关闭所有持有的连接
            logger.info("");
            logger.info("清理: 关闭所有持有的连接...");
            for (Jedis jedis : heldConnections) {
                try {
                    jedis.close();
                } catch (Exception e) {
                    // Redis已停止，关闭可能失败，忽略
                }
            }

            // 恢复Redis
            logger.info("恢复Redis服务...");
            try {
                Process process = Runtime.getRuntime().exec("docker start kafka-redis");
                process.waitFor();
                Thread.sleep(2000); // 等待Redis完全启动
                logger.info("Redis已恢复");
            } catch (Exception e) {
                logger.error("恢复Redis失败", e);
            }

            jedisPool.close();
        }

        logger.info("");
        logger.info("========================================");
        logger.info("测试总结:");
        logger.info("");
        logger.info("testOnBorrow参数的真正作用:");
        logger.info("  - true:  从池中取出连接时验证（PING）");
        logger.info("  - false: 从池中取出连接时不验证");
        logger.info("");
        logger.info("但是！");
        logger.info("  当池中「没有空闲连接」时:");
        logger.info("  - 无论testOnBorrow是true还是false");
        logger.info("  - 都必须调用 create() 创建新连接");
        logger.info("  - 如果创建失败，都会抛出相同的异常");
        logger.info("");
        logger.info("因此:");
        logger.info("  \"Could not get a resource from the pool\"");
        logger.info("  本质上是「创建新连接失败」的异常");
        logger.info("  与 testOnBorrow 无关！");
        logger.info("========================================");
    }
}
