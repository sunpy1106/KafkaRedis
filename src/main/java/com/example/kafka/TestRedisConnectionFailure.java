package com.example.kafka;

import com.example.kafka.model.Message;
import com.example.kafka.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis Connection Failure 测试
 *
 * 测试目标: 触发 JedisConnectionException: Could not get a resource from the pool
 * 测试场景: Redis服务不可用时尝试创建连接
 *
 * 测试步骤:
 * 1. 先暂停Redis服务 (docker pause kafka-redis)
 * 2. 启动压力测试，此时连接创建会失败
 * 3. 触发 JedisConnectionException: Could not get a resource from the pool
 */
public class TestRedisConnectionFailure {
    private static final Logger logger = LoggerFactory.getLogger(TestRedisConnectionFailure.class);

    // 测试参数配置
    private static final int THREAD_POOL_SIZE = 10;  // 线程池大小
    private static final int TOTAL_MESSAGES = 100;   // 总消息数

    // 统计计数器
    private static final AtomicInteger successCount = new AtomicInteger(0);
    private static final AtomicInteger failureCount = new AtomicInteger(0);
    private static final AtomicInteger jedisConnectionExceptionCount = new AtomicInteger(0);
    private static final AtomicInteger otherExceptionCount = new AtomicInteger(0);

    public static void main(String[] args) {
        logger.info("========================================");
        logger.info("Redis Connection Failure 测试启动");
        logger.info("========================================");
        logger.info("⚠️  请先手动执行: docker pause kafka-redis");
        logger.info("");
        logger.info("测试配置:");
        logger.info("  - 线程池大小: {}", THREAD_POOL_SIZE);
        logger.info("  - 总消息数: {}", TOTAL_MESSAGES);
        logger.info("");
        logger.info("测试目标: 触发 JedisConnectionException: Could not get a resource from the pool");
        logger.info("========================================");
        logger.info("");

        // 等待用户暂停Redis
        logger.warn("等待5秒，请确保已执行 docker pause kafka-redis ...");
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        MessageService messageService = new MessageService();
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        CountDownLatch latch = new CountDownLatch(TOTAL_MESSAGES);

        long startTime = System.currentTimeMillis();

        // 提交所有任务
        logger.info("开始提交 {} 个消息发送任务...", TOTAL_MESSAGES);
        for (int i = 0; i < TOTAL_MESSAGES; i++) {
            final int messageId = i;

            executor.submit(() -> {
                try {
                    // 创建消息
                    Message message = new Message("连接失败测试-" + messageId);

                    // 发送消息
                    boolean result = messageService.sendMessage(message);

                    if (result) {
                        successCount.incrementAndGet();
                        logger.info("✅ 消息 #{} 发送成功", messageId);
                    } else {
                        failureCount.incrementAndGet();
                        logger.warn("⚠️  消息 #{} 发送失败", messageId);
                    }

                } catch (Exception e) {
                    failureCount.incrementAndGet();

                    // 提取根本原因
                    Throwable rootCause = e;
                    while (rootCause.getCause() != null) {
                        rootCause = rootCause.getCause();
                    }

                    String exceptionClass = rootCause.getClass().getName();
                    String exceptionMsg = rootCause.getMessage();

                    // 检查是否为JedisConnectionException
                    if (exceptionClass.contains("JedisConnectionException")) {
                        jedisConnectionExceptionCount.incrementAndGet();
                        logger.error("🎯 JedisConnectionException (消息 #{}) - {}: {}",
                            messageId, exceptionClass, exceptionMsg);

                        // 打印完整堆栈以确认
                        if (messageId < 3) {
                            logger.error("完整异常堆栈:", e);
                        }
                    } else {
                        otherExceptionCount.incrementAndGet();
                        logger.error("❌ 其他异常 (消息 #{}) - {}: {}",
                            messageId, exceptionClass, exceptionMsg);
                    }

                } finally {
                    latch.countDown();
                }
            });
        }

        logger.info("所有任务已提交，等待执行完成...");
        logger.info("");

        // 等待所有任务完成
        try {
            boolean completed = latch.await(2, TimeUnit.MINUTES);

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            if (!completed) {
                logger.warn("⚠️  超时: 部分任务未在2分钟内完成");
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
            messageService.close();
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
        logger.info("总消息数: {}", TOTAL_MESSAGES);
        logger.info("执行时长: {} ms ({} 秒)", duration, duration / 1000.0);
        logger.info("");
        logger.info("✅ 成功: {} ({}%)",
            successCount.get(),
            String.format("%.1f", successCount.get() * 100.0 / TOTAL_MESSAGES));
        logger.info("❌ 失败: {} ({}%)",
            failureCount.get(),
            String.format("%.1f", failureCount.get() * 100.0 / TOTAL_MESSAGES));
        logger.info("");
        logger.info("异常分类:");
        logger.info("  🎯 JedisConnectionException: {} ({}%)",
            jedisConnectionExceptionCount.get(),
            String.format("%.1f", jedisConnectionExceptionCount.get() * 100.0 / TOTAL_MESSAGES));
        logger.info("  ❓ 其他异常: {} ({}%)",
            otherExceptionCount.get(),
            String.format("%.1f", otherExceptionCount.get() * 100.0 / TOTAL_MESSAGES));
        logger.info("");

        // 判断测试是否成功
        if (jedisConnectionExceptionCount.get() > 0) {
            logger.info("🎉 测试成功! JedisConnectionException 已成功触发");
            logger.info("   触发率: {}%",
                String.format("%.1f", jedisConnectionExceptionCount.get() * 100.0 / TOTAL_MESSAGES));
        } else {
            logger.error("❌ 测试失败: 未触发JedisConnectionException");
            logger.error("   请确保Redis服务已暂停 (docker pause kafka-redis)");
        }

        logger.info("========================================");
        logger.info("");
        logger.info("⚠️  测试完成后请恢复Redis服务: docker unpause kafka-redis");
    }
}
