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
 * Kafka→Redis流量压力测试 - 多线程Producer方案
 *
 * 测试目标: 触发 Redis Connection Exception
 * 测试场景: 高并发Producer发送消息，Redis连接池资源耗尽
 *
 * 预期结果:
 * - Redis异常: JedisConnectionException: Could not get a resource from the pool
 * - 成功率: 30-40% (650-750条消息触发Redis异常)
 */
public class TestKafkaRedisFlowProducerPressure {
    private static final Logger logger = LoggerFactory.getLogger(TestKafkaRedisFlowProducerPressure.class);

    // 测试参数配置
    private static final int THREAD_POOL_SIZE = 30;  // 线程池大小
    private static final int TOTAL_MESSAGES = 1000;   // 总消息数

    // 统计计数器
    private static final AtomicInteger successCount = new AtomicInteger(0);
    private static final AtomicInteger failureCount = new AtomicInteger(0);
    private static final AtomicInteger redisExceptionCount = new AtomicInteger(0);
    private static final AtomicInteger kafkaExceptionCount = new AtomicInteger(0);
    private static final AtomicInteger otherExceptionCount = new AtomicInteger(0);

    public static void main(String[] args) {
        logger.info("========================================");
        logger.info("Kafka→Redis 流量压力测试启动");
        logger.info("========================================");
        logger.info("测试配置:");
        logger.info("  - 线程池大小: {}", THREAD_POOL_SIZE);
        logger.info("  - 总消息数: {}", TOTAL_MESSAGES);
        logger.info("  - 预期Redis连接池: maxTotal=10, maxWait=100ms");
        logger.info("  - 预期Redis操作延迟: 200ms");
        logger.info("");
        logger.info("测试目标: 触发 Redis Connection Pool Exhaustion");
        logger.info("========================================");
        logger.info("");

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
                    Message message = new Message("压力测试消息-" + messageId);

                    // 发送消息
                    boolean result = messageService.sendMessage(message);

                    if (result) {
                        successCount.incrementAndGet();
                        if (messageId % 100 == 0) {
                            logger.info("✅ 消息 #{} 发送成功 - UUID: {}", messageId, message.getUuid());
                        }
                    } else {
                        failureCount.incrementAndGet();
                        logger.warn("⚠️  消息 #{} 发送失败（业务逻辑返回false）", messageId);
                    }

                } catch (Exception e) {
                    failureCount.incrementAndGet();

                    // 分类异常 - 通过异常类型和消息内容
                    String exceptionMsg = e.getMessage();
                    String exceptionClass = e.getClass().getName(); // 使用完整类名
                    String exceptionSimpleName = e.getClass().getSimpleName();

                    // 检查是否为Redis异常 (Jedis相关异常)
                    if (exceptionClass.contains("redis.clients.jedis") ||
                        (exceptionMsg != null && exceptionMsg.toLowerCase().contains("redis"))) {
                        redisExceptionCount.incrementAndGet();
                        logger.error("🎯 Redis异常 (消息 #{}) - {}: {}",
                            messageId, exceptionSimpleName, exceptionMsg);
                    } else if (exceptionClass.contains("kafka") ||
                               (exceptionMsg != null && exceptionMsg.toLowerCase().contains("kafka"))) {
                        kafkaExceptionCount.incrementAndGet();
                        logger.error("📨 Kafka异常 (消息 #{}) - {}: {}",
                            messageId, exceptionSimpleName, exceptionMsg);
                    } else {
                        otherExceptionCount.incrementAndGet();
                        logger.error("❌ 其他异常 (消息 #{}) - {}: {}",
                            messageId, exceptionSimpleName, exceptionMsg);
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
            // 最多等待5分钟
            boolean completed = latch.await(5, TimeUnit.MINUTES);

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            if (!completed) {
                logger.warn("⚠️  超时: 部分任务未在5分钟内完成");
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
        logger.info("  🎯 Redis异常: {} ({}%)",
            redisExceptionCount.get(),
            String.format("%.1f", redisExceptionCount.get() * 100.0 / TOTAL_MESSAGES));
        logger.info("  📨 Kafka异常: {} ({}%)",
            kafkaExceptionCount.get(),
            String.format("%.1f", kafkaExceptionCount.get() * 100.0 / TOTAL_MESSAGES));
        logger.info("  ❓ 其他异常: {} ({}%)",
            otherExceptionCount.get(),
            String.format("%.1f", otherExceptionCount.get() * 100.0 / TOTAL_MESSAGES));
        logger.info("");

        // 判断测试是否成功触发Redis异常
        if (redisExceptionCount.get() >= 500) {
            logger.info("🎉 测试成功! Redis连接池耗尽异常已成功触发");
            logger.info("   触发率: {}%", String.format("%.1f", redisExceptionCount.get() * 100.0 / TOTAL_MESSAGES));
        } else if (redisExceptionCount.get() > 0) {
            logger.warn("⚠️  测试部分成功: Redis异常触发率较低 ({}%)",
                String.format("%.1f", redisExceptionCount.get() * 100.0 / TOTAL_MESSAGES));
            logger.warn("   建议: 增加线程数或减小Redis连接池大小");
        } else {
            logger.error("❌ 测试失败: 未触发Redis异常");
            logger.error("   可能原因:");
            logger.error("   1. Redis连接池配置过大 (maxTotal应为10)");
            logger.error("   2. Redis操作延迟不足 (应为200ms)");
            logger.error("   3. 并发线程数不足 (当前{}线程)", THREAD_POOL_SIZE);
        }

        logger.info("========================================");
        logger.info("");

        // 计算吞吐量
        double throughput = TOTAL_MESSAGES * 1000.0 / duration;
        logger.info("平均吞吐量: {:.2f} 消息/秒", throughput);
        logger.info("平均延迟: {:.2f} ms/消息", duration * 1.0 / TOTAL_MESSAGES);
        logger.info("");
    }
}
