package com.example.kafka;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 测试类：使用 acks=all + min.insync.replicas 冲突触发 "batch creation" 超时
 *
 * 策略:
 *  - Topic: batch-timeout-test (min.insync.replicas=2, replication-factor=1)
 *  - Producer: acks=all (要求所有副本确认)
 *  - 结果: 无法满足 min.insync.replicas 要求，batch 创建后无法发送
 *
 * 目标错误: "X ms has passed since batch creation"
 */
public class TestBatchCreationWithAcksAll {
    private static final Logger logger = LoggerFactory.getLogger(TestBatchCreationWithAcksAll.class);

    public static void main(String[] args) {
        logger.info("========== 测试 Batch Creation 超时（acks=all 方案）==========\n");

        // 30秒版本（快速验证）
        testBatchCreationWithAcksAll30s();

        // 120秒版本（真实配置）
        // testBatchCreationWithAcksAll120s();

        logger.info("\n========== 测试完成 ==========");
    }

    /**
     * 测试 acks=all + min.insync.replicas 冲突（30秒版本）
     *
     * Topic 配置:
     *  - batch-timeout-test
     *  - replication-factor=1 (只有1个副本)
     *  - min.insync.replicas=2 (要求至少2个副本)
     *
     * Producer 配置:
     *  - acks=all (要求所有 ISR 副本确认)
     *  - delivery.timeout.ms=30000 (30秒超时)
     *
     * 预期:
     *  - Metadata 成功获取 ✅
     *  - Batch 创建成功 ✅
     *  - 发送失败（副本数不足）❌
     *  - 30秒后触发 "Expiring X record(s)... ms has passed since batch creation"
     */
    private static void testBatchCreationWithAcksAll30s() {
        logger.info("【测试】acks=all + min.insync.replicas 冲突（30秒版本）");
        logger.info("Topic: batch-timeout-test");
        logger.info("  - replication-factor=1 (只有1个副本)");
        logger.info("  - min.insync.replicas=2 (要求2个副本)");
        logger.info("  - ⚠️  这是一个不可能满足的条件！\n");

        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");  // 真实 Kafka

        // ⚠️ 关键配置
        props.put("acks", "all");  // 要求所有 ISR 副本确认
        props.put("delivery.timeout.ms", "30000");  // 30秒超时
        props.put("request.timeout.ms", "10000");
        props.put("linger.ms", "100");
        props.put("batch.size", "16384");
        props.put("retries", "3");
        props.put("retry.backoff.ms", "100");

        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        KafkaProducer<String, String> producer = null;
        final CountDownLatch latch = new CountDownLatch(10);
        final long startTime[] = {0};

        try {
            logger.info("1. 创建 KafkaProducer（acks=all）...");
            producer = new KafkaProducer<>(props);
            logger.info("   ✅ KafkaProducer 创建成功\n");

            logger.info("2. 发送10条消息到 batch-timeout-test topic...");
            logger.info("   （Metadata 会成功获取，Batch 会创建）");
            logger.info("   （但发送会失败：min.insync.replicas=2，实际只有1个副本）\n");

            startTime[0] = System.currentTimeMillis();

            // 发送10条消息
            for (int i = 1; i <= 10; i++) {
                final int msgNum = i;
                ProducerRecord<String, String> record =
                    new ProducerRecord<>("batch-timeout-test", "key-" + i, "value-" + i);

                producer.send(record, new Callback() {
                    @Override
                    public void onCompletion(RecordMetadata metadata, Exception exception) {
                        long elapsed = System.currentTimeMillis() - startTime[0];

                        if (exception != null) {
                            logger.error("\n❌ 消息 #{} 发送失败！耗时: {}ms ({} 秒)",
                                msgNum, elapsed, elapsed / 1000);
                            logger.error("异常类型: {}", exception.getClass().getName());
                            logger.error("异常消息: {}", exception.getMessage());

                            // ✅ 检查是否包含 "batch creation"
                            String message = exception.getMessage();
                            if (message != null) {
                                if (message.contains("batch creation") || message.contains("since batch")) {
                                    logger.info("\n🎯🎯🎯 成功触发 Batch Creation 超时！🎯🎯🎯");
                                    logger.info("✅✅✅ 异常消息包含: 'batch creation'");
                                    logger.info("✅ 完整消息: {}", message);
                                } else if (message.contains("Expiring") && message.contains("record")) {
                                    logger.info("\n🎯 触发了 Expiring 记录超时！");
                                    logger.info("✅ 异常消息: {}", message);

                                    // 检查是否包含 "since"
                                    if (message.contains("since")) {
                                        logger.info("✅✅ 消息包含 'since' - 很可能是 batch creation 超时！");
                                    }
                                } else if (message.contains("NOT_ENOUGH_REPLICAS") ||
                                           message.contains("not enough")) {
                                    logger.info("\n🎯 触发了副本不足错误！");
                                    logger.info("✅ 这证明配置生效了");
                                    logger.info("✅ 异常消息: {}", message);
                                } else {
                                    logger.warn("\n⚠️  触发了其他异常");
                                    logger.warn("   异常消息: {}", message);
                                }
                            }

                            // 打印异常的 cause
                            if (exception.getCause() != null) {
                                logger.error("根本原因: {}", exception.getCause().getClass().getName());
                                logger.error("原因消息: {}", exception.getCause().getMessage());
                            }

                        } else {
                            logger.warn("⚠️  消息 #{} 发送成功（不应该发生，说明配置可能有问题）", msgNum);
                            logger.warn("   Partition: {}, Offset: {}",
                                metadata.partition(), metadata.offset());
                        }

                        latch.countDown();
                    }
                });

                logger.info("   消息 #{} 已提交", i);
            }

            logger.warn("\n等待 delivery.timeout.ms=30秒 超时...");
            logger.warn("（应该很快就会因为副本不足而失败）\n");

            // 等待所有 Callback 完成（最多 35 秒）
            boolean completed = latch.await(35, TimeUnit.SECONDS);

            if (!completed) {
                logger.warn("⚠️  等待超时，部分 Callback 未执行");
            }

        } catch (Exception e) {
            logger.error("\n发生其他异常: {}", e.getClass().getName());
            logger.error("异常消息: {}", e.getMessage(), e);

        } finally {
            if (producer != null) {
                try {
                    logger.info("\n3. 关闭 KafkaProducer...");
                    producer.close();
                    logger.info("   KafkaProducer 已关闭\n");
                } catch (Exception e) {
                    logger.debug("关闭 producer 时发生异常", e);
                }
            }
        }
    }

    /**
     * 测试 acks=all + min.insync.replicas 冲突（120秒版本）
     *
     * 配置同上，但使用真实的 120 秒超时
     */
    private static void testBatchCreationWithAcksAll120s() {
        logger.info("\n【测试】acks=all + min.insync.replicas 冲突（真实120秒版本）");
        logger.warn("⚠️  警告: 如果触发超时，需要等待 120+ 秒\n");
        logger.info("Topic: batch-timeout-test");
        logger.info("  - replication-factor=1");
        logger.info("  - min.insync.replicas=2\n");

        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");

        // ⚠️ 真实的120秒超时
        props.put("acks", "all");
        props.put("delivery.timeout.ms", "120000");  // 120秒
        props.put("request.timeout.ms", "30000");
        props.put("linger.ms", "100");
        props.put("batch.size", "16384");
        props.put("retries", "3");
        props.put("retry.backoff.ms", "100");

        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        KafkaProducer<String, String> producer = null;
        final CountDownLatch latch = new CountDownLatch(5);
        final long startTime[] = {0};

        try {
            logger.info("1. 创建 KafkaProducer（acks=all）...");
            producer = new KafkaProducer<>(props);
            logger.info("   ✅ KafkaProducer 创建成功\n");

            logger.info("2. 发送5条消息...\n");

            startTime[0] = System.currentTimeMillis();

            for (int i = 1; i <= 5; i++) {
                final int msgNum = i;
                ProducerRecord<String, String> record =
                    new ProducerRecord<>("batch-timeout-test", "key-" + i, "value-" + i);

                producer.send(record, new Callback() {
                    @Override
                    public void onCompletion(RecordMetadata metadata, Exception exception) {
                        long elapsed = System.currentTimeMillis() - startTime[0];

                        if (exception != null) {
                            logger.error("\n❌ 消息 #{} 发送失败！耗时: {}ms ({} 秒)",
                                msgNum, elapsed, elapsed / 1000);
                            logger.error("异常类型: {}", exception.getClass().getName());
                            logger.error("异常消息: {}", exception.getMessage());

                            String message = exception.getMessage();
                            if (message != null) {
                                if (message.contains("120000") || message.contains("120 seconds")) {
                                    logger.info("\n🎯 成功触发真实的 120 秒超时！");
                                    logger.info("✅ 异常消息包含: 120000 ms");
                                } else if (message.contains("batch creation") ||
                                           message.contains("Expiring")) {
                                    logger.info("\n🎯 触发了 Batch/Expiring 超时！");
                                    logger.info("✅ 异常消息: {}", message);
                                }
                            }

                        } else {
                            logger.warn("⚠️  消息 #{} 发送成功（不应该发生）", msgNum);
                        }

                        latch.countDown();
                    }
                });

                logger.info("   消息 #{} 已提交", i);
            }

            logger.warn("\n等待 delivery.timeout.ms=120秒 超时（或更快失败）...\n");

            // 等待所有 Callback 完成（最多 130 秒）
            boolean completed = latch.await(130, TimeUnit.SECONDS);

            if (!completed) {
                logger.warn("⚠️  等待超时");
            }

        } catch (Exception e) {
            logger.error("\n发生其他异常: {}", e.getClass().getName());
            logger.error("异常消息: {}", e.getMessage(), e);

        } finally {
            if (producer != null) {
                try {
                    logger.info("\n3. 关闭 KafkaProducer...");
                    producer.close();
                    logger.info("   KafkaProducer 已关闭\n");
                } catch (Exception e) {
                    logger.debug("关闭 producer 时发生异常", e);
                }
            }
        }
    }
}
