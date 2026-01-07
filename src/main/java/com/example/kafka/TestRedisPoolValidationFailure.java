package com.example.kafka;

import com.example.kafka.service.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis Pool Validation Failure 测试
 *
 * 测试目标: 触发 JedisConnectionException: Could not get a resource from the pool
 * 测试场景: testOnBorrow=true, 连接验证失败
 *
 * 测试步骤:
 * 1. 启动Redis并初始化连接池（testOnBorrow=true）
 * 2. 等待连接池创建初始连接
 * 3. 停止Redis服务
 * 4. 尝试从连接池获取连接（testOnBorrow验证会失败）
 * 5. 触发 JedisConnectionException: Could not get a resource from the pool
 */
public class TestRedisPoolValidationFailure {
    private static final Logger logger = LoggerFactory.getLogger(TestRedisPoolValidationFailure.class);

    // 测试参数配置
    private static final int THREAD_POOL_SIZE = 5;  // 线程池大小
    private static final int TOTAL_OPERATIONS = 50;   // 总操作数

    // 统计计数器
    private static final AtomicInteger successCount = new AtomicInteger(0);
    private static final AtomicInteger failureCount = new AtomicInteger(0);
    private static final AtomicInteger jedisConnectionExceptionCount = new AtomicInteger(0);
    private static final AtomicInteger correctMessageCount = new AtomicInteger(0);
    private static final AtomicInteger otherExceptionCount = new AtomicInteger(0);

    public static void main(String[] args) {
        logger.info("========================================");
        logger.info("Redis Pool Validation Failure 测试启动");
        logger.info("========================================");
        logger.info("测试配置:");
        logger.info("  - 线程池大小: {}", THREAD_POOL_SIZE);
        logger.info("  - 总操作数: {}", TOTAL_OPERATIONS);
        logger.info("  - testOnBorrow: true (连接验证开启)");
        logger.info("");
        logger.info("测试目标: 触发 JedisConnectionException: Could not get a resource from the pool");
        logger.info("========================================");
        logger.info("");

        // Step 1: 初始化Redis服务和连接池
        logger.info("Step 1: 初始化Redis服务和连接池...");
        RedisService redisService = new RedisService();

        // Step 2: 等待连接池稳定
        logger.info("Step 2: 等待连接池稳定...");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Step 3: 停止Redis服务
        logger.info("Step 3: 停止Redis服务...");
        try {
            Process process = Runtime.getRuntime().exec("docker stop kafka-redis");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                logger.info("Docker output: {}", line);
            }
            process.waitFor();
            logger.info("Redis已停止");
        } catch (Exception e) {
            logger.error("停止Redis失败", e);
            return;
        }

        // Step 4: 等待一下确保Redis完全停止
        logger.info("Step 4: 等待Redis完全停止...");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Step 5: 尝试使用连接（此时testOnBorrow会失败）
        logger.info("Step 5: 开始测试（此时testOnBorrow验证会失败）...");
        logger.info("");

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        CountDownLatch latch = new CountDownLatch(TOTAL_OPERATIONS);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < TOTAL_OPERATIONS; i++) {
            final int operationId = i;

            executor.submit(() -> {
                try {
                    // 尝试检查一个UUID (会触发testOnBorrow验证)
                    String testUuid = "test-uuid-" + operationId;
                    boolean exists = redisService.isUuidExists(testUuid);

                    successCount.incrementAndGet();
                    logger.info("✅ 操作 #{} 成功: {}", operationId, exists);

                } catch (Exception e) {
                    failureCount.incrementAndGet();

                    // 提取根本原因
                    Throwable cause = e;
                    while (cause.getCause() != null && cause.getCause() != cause) {
                        cause = cause.getCause();
                    }

                    String exceptionClass = cause.getClass().getName();
                    String exceptionMsg = cause.getMessage();

                    // 检查是否为JedisConnectionException
                    if (exceptionClass.contains("JedisConnectionException")) {
                        jedisConnectionExceptionCount.incrementAndGet();

                        // 检查消息是否匹配
                        if (exceptionMsg != null && exceptionMsg.contains("Could not get a resource from the pool")) {
                            correctMessageCount.incrementAndGet();
                            logger.error("🎯🎯🎯 成功！JedisConnectionException (操作 #{}) - {}: {}",
                                operationId, exceptionClass, exceptionMsg);

                            // 打印前3个的完整堆栈
                            if (correctMessageCount.get() <= 3) {
                                logger.error("完整异常堆栈:", e);
                            }
                        } else {
                            logger.error("🎯 JedisConnectionException但消息不匹配 (操作 #{}) - {}: {}",
                                operationId, exceptionClass, exceptionMsg);
                        }
                    } else {
                        otherExceptionCount.incrementAndGet();
                        if (otherExceptionCount.get() <= 3) {
                            logger.error("❌ 其他异常 (操作 #{}) - {}: {}",
                                operationId, exceptionClass, exceptionMsg);
                        }
                    }

                } finally {
                    latch.countDown();
                }
            });
        }

        // 等待所有任务完成
        try {
            boolean completed = latch.await(1, TimeUnit.MINUTES);

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            if (!completed) {
                logger.warn("⚠️  超时: 部分任务未在1分钟内完成");
            }

            // 关闭线程池
            executor.shutdown();
            executor.awaitTermination(30, TimeUnit.SECONDS);

            // 输出测试结果
            printTestResults(duration);

        } catch (InterruptedException e) {
            logger.error("测试被中断", e);
            Thread.currentThread().interrupt();
        } finally {
            // 关闭服务
            redisService.close();

            // 恢复Redis
            logger.info("");
            logger.info("恢复Redis服务...");
            try {
                Process process = Runtime.getRuntime().exec("docker start kafka-redis");
                process.waitFor();
                logger.info("Redis已恢复");
            } catch (Exception e) {
                logger.error("恢复Redis失败，请手动执行: docker start kafka-redis", e);
            }
        }
    }

    /**
     * 打印测试结果统计
     */
    private static void printTestResults(long duration) {
        logger.info("");
        logger.info("========================================");
        logger.info("测试结果统计");
        logger.info("========================================");
        logger.info("总操作数: {}", TOTAL_OPERATIONS);
        logger.info("执行时长: {} ms ({} 秒)", duration, duration / 1000.0);
        logger.info("");
        logger.info("✅ 成功: {} ({}%)",
            successCount.get(),
            String.format("%.1f", successCount.get() * 100.0 / TOTAL_OPERATIONS));
        logger.info("❌ 失败: {} ({}%)",
            failureCount.get(),
            String.format("%.1f", failureCount.get() * 100.0 / TOTAL_OPERATIONS));
        logger.info("");
        logger.info("异常分类:");
        logger.info("  🎯 JedisConnectionException: {} ({}%)",
            jedisConnectionExceptionCount.get(),
            String.format("%.1f", jedisConnectionExceptionCount.get() * 100.0 / TOTAL_OPERATIONS));
        logger.info("  🎯🎯🎯 正确消息 (Could not get a resource from the pool): {} ({}%)",
            correctMessageCount.get(),
            String.format("%.1f", correctMessageCount.get() * 100.0 / TOTAL_OPERATIONS));
        logger.info("  ❓ 其他异常: {} ({}%)",
            otherExceptionCount.get(),
            String.format("%.1f", otherExceptionCount.get() * 100.0 / TOTAL_OPERATIONS));
        logger.info("");

        // 判断测试是否成功
        if (correctMessageCount.get() > 0) {
            logger.info("🎉🎉🎉 测试成功！");
            logger.info("成功触发: JedisConnectionException: Could not get a resource from the pool");
            logger.info("触发率: {}%",
                String.format("%.1f", correctMessageCount.get() * 100.0 / TOTAL_OPERATIONS));
        } else if (jedisConnectionExceptionCount.get() > 0) {
            logger.warn("⚠️  测试部分成功: 触发了JedisConnectionException但消息不匹配");
        } else {
            logger.error("❌ 测试失败: 未触发目标异常");
        }

        logger.info("========================================");
    }
}
