package com.example.kafka;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 测试：Redis 服务运行，但连接超时
 *
 * 场景：使用 docker pause 暂停 Redis 容器
 * 目的：验证 Redis 进程存在但不响应时，是否触发同样的异常
 */
public class TestRedisConnectionTimeout {
    private static final Logger logger = LoggerFactory.getLogger(TestRedisConnectionTimeout.class);

    public static void main(String[] args) {
        logger.info("========================================");
        logger.info("测试：Redis 运行但被暂停（docker pause）");
        logger.info("========================================");
        logger.info("");

        // Step 1: 创建连接池，使用较短的连接超时
        logger.info("Step 1: 创建连接池（连接超时=2000ms）");
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(10);
        poolConfig.setMaxIdle(10);
        poolConfig.setMinIdle(0);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setMaxWaitMillis(3000);

        // 2秒连接超时
        int connectionTimeout = 2000;
        int soTimeout = 2000;

        logger.info("配置: connectionTimeout={}ms, soTimeout={}ms", connectionTimeout, soTimeout);

        // Jedis 3.1.0 构造函数: (poolConfig, host, port, connectionTimeout, soTimeout, password, database, clientName)
        JedisPool jedisPool = new JedisPool(
            poolConfig,
            "localhost",          // host
            6379,                 // port
            connectionTimeout,    // connectionTimeout
            soTimeout,            // soTimeout
            null,                 // password
            0,                    // database
            null                  // clientName
        );

        try {
            // Step 2: 测试正常连接
            logger.info("");
            logger.info("Step 2: 测试正常连接（Redis 正常运行）");
            try (Jedis jedis = jedisPool.getResource()) {
                String pong = jedis.ping();
                logger.info("  PING 成功: {}", pong);
            } catch (Exception e) {
                logger.error("  初始连接失败", e);
                return;
            }

            // Step 3: 暂停 Redis 容器
            logger.info("");
            logger.info("Step 3: 暂停 Redis 容器（docker pause kafka-redis）");
            logger.info("  docker pause 会冻结容器内所有进程");
            logger.info("  容器状态显示为 'Paused'");
            logger.info("  TCP 端口仍然监听，但进程不响应任何请求");
            logger.info("");

            try {
                Process process = Runtime.getRuntime().exec("docker pause kafka-redis");
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info("  {}", line);
                }
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    logger.info("  ✅ Redis 容器已暂停");
                } else {
                    logger.error("  ❌ 暂停容器失败，退出码: {}", exitCode);
                    return;
                }
            } catch (Exception e) {
                logger.error("  暂停容器失败", e);
                return;
            }

            // Step 4: 等待
            logger.info("");
            logger.info("Step 4: 等待2秒...");
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Step 5: 尝试连接
            logger.info("");
            logger.info("Step 5: 尝试连接到已暂停的 Redis");
            logger.info("");
            logger.info("🎯 当前状态:");
            logger.info("   - Redis 容器: Paused（进程冻结）");
            logger.info("   - Redis 进程: 存在但不响应");
            logger.info("   - TCP 端口: 可能仍在监听（系统层面）");
            logger.info("   - 连接超时: 2000ms");
            logger.info("");
            logger.info("🎯 预期结果:");
            logger.info("   - 连接尝试挂起（无响应）");
            logger.info("   - 2秒后超时");
            logger.info("   - 抛出: SocketTimeoutException: connect timed out");
            logger.info("   - 包装为: JedisConnectionException");
            logger.info("   - 最终: Could not get a resource from the pool");
            logger.info("");

            long startTime = System.currentTimeMillis();

            try (Jedis jedis = jedisPool.getResource()) {
                String pong = jedis.ping();
                logger.info("❌ 意外成功: {}", pong);

            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - startTime;

                logger.error("================== 捕获到异常 ==================");
                logger.error("耗时: {}ms", elapsed);
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

                // 判断异常类型
                if (e.getMessage() != null &&
                    e.getMessage().contains("Could not get a resource from the pool")) {
                    logger.info("✅✅✅ 成功触发目标异常！");
                    logger.info("");
                    logger.info("🎉 验证成功：Redis 运行但暂停也能触发此异常！");
                    logger.info("");
                    logger.info("说明:");
                    logger.info("  - Redis 容器进程存在（虽然被冻结）");
                    logger.info("  - 连接尝试挂起，无响应");
                    logger.info("  - 连接超时 ({}ms)", elapsed);
                    logger.info("  - 连接创建失败 → 触发异常");
                    logger.info("");
                    logger.info("生产场景:");
                    logger.info("  - Redis 假死（进程存在但不响应）");
                    logger.info("  - CPU 100% 导致 Redis 无法处理请求");
                    logger.info("  - 内存交换导致进程挂起");
                    logger.info("  - 网络设备故障导致极大延迟");

                } else if (cause.getMessage() != null &&
                           (cause.getMessage().toLowerCase().contains("timeout") ||
                            cause.getMessage().contains("timed out"))) {
                    logger.info("⚠️  触发了超时异常");
                    logger.info("根本原因: {}", cause.getMessage());
                    logger.info("");
                    logger.info("虽然消息不完全相同，但本质是连接创建超时");
                    logger.info("在某些 Jedis 版本中，超时异常也会被包装为目标异常");

                } else {
                    logger.error("❓ 触发了其他异常");
                }

                logger.error("");
                logger.error("完整堆栈:");
                e.printStackTrace();
                logger.error("==============================================");
            }

        } finally {
            // 恢复 Redis
            logger.info("");
            logger.info("清理: 恢复 Redis 容器...");
            try {
                Process process = Runtime.getRuntime().exec("docker unpause kafka-redis");
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info("  {}", line);
                }
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    logger.info("  ✅ Redis 容器已恢复");
                } else {
                    logger.error("  ❌ 恢复容器失败，退出码: {}", exitCode);
                }

                // 等待 Redis 完全恢复
                Thread.sleep(2000);

            } catch (Exception e) {
                logger.error("  恢复容器失败", e);
            }

            // 验证连接恢复
            logger.info("");
            logger.info("验证: 测试连接是否恢复...");
            try (Jedis jedis = jedisPool.getResource()) {
                String pong = jedis.ping();
                logger.info("  ✅ 连接恢复成功: {}", pong);
            } catch (Exception e) {
                logger.error("  ❌ 连接仍然失败", e);
            }

            jedisPool.close();
        }

        logger.info("");
        logger.info("========================================");
        logger.info("测试总结:");
        logger.info("");
        logger.info("Redis 可用情况下触发此异常的场景:");
        logger.info("");
        logger.info("1. docker stop (Redis 停止)");
        logger.info("   - TCP 端口关闭");
        logger.info("   - 立即返回 Connection refused");
        logger.info("   - ✅ 触发异常");
        logger.info("");
        logger.info("2. docker pause (Redis 冻结)");
        logger.info("   - 进程冻结，不响应");
        logger.info("   - 连接挂起，超时后失败");
        logger.info("   - ✅ 触发异常（如果超时）");
        logger.info("");
        logger.info("3. 网络阻断（防火墙/iptables）");
        logger.info("   - Redis 运行但无法连接");
        logger.info("   - 连接被拦截");
        logger.info("   - ✅ 触发异常");
        logger.info("");
        logger.info("4. 连接数达到上限");
        logger.info("   - Redis 运行但拒绝新连接");
        logger.info("   - ERR max number of clients reached");
        logger.info("   - ❌ 触发不同异常 (JedisDataException)");
        logger.info("");
        logger.info("结论:");
        logger.info("  任何导致「创建新连接失败」的场景");
        logger.info("  都可能触发: Could not get a resource from the pool");
        logger.info("");
        logger.info("  Redis 是否真正\"可用\"并不重要，");
        logger.info("  关键是能否成功建立TCP连接！");
        logger.info("========================================");
    }
}
