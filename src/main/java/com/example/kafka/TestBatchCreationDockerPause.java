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
 * 测试类：使用 Docker pause 触发 "batch creation" 超时
 *
 * 策略:
 *  - 使用正常的 topic（不需要特殊配置）
 *  - 发送消息后立即暂停 Kafka broker
 *  - Broker 挂起（进程冻结）但连接不断开
 *  - 等待 delivery.timeout.ms 超时
 *
 * 目标错误: "X ms has passed since batch creation"
 *
 * 使用方法:
 *  1. 运行此程序
 *  2. 看到提示后立即执行: docker pause kafka-broker
 *  3. 等待30秒超时
 *  4. 完成后执行: docker unpause kafka-broker
 */
public class TestBatchCreationDockerPause {
    private static final Logger logger = LoggerFactory.getLogger(TestBatchCreationDockerPause.class);

    public static void main(String[] args) {
        logger.info("========== 测试 Docker pause 触发 Batch Creation 超时 ==========\n");

        testDockerPause();

        logger.info("\n========== 测试完成 ==========");
    }

    private static void testDockerPause() {
        logger.info("【测试】Docker pause 方案");
        logger.info("策略: 发送消息后暂停 Kafka broker\n");

        logger.warn("⚠️  准备步骤:");
        logger.warn("   1. 确保 Kafka broker 正在运行");
        logger.warn("   2. 看到提示后立即执行: docker pause kafka-broker");
        logger.warn("   3. 测试完成后执行: docker unpause kafka-broker\n");

        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");  // 真实 Kafka

        // 关键配置
        props.put("acks", "1");  // 正常的 acks 配置
        props.put("delivery.timeout.ms", "30000");  // 30秒超时
        props.put("request.timeout.ms", "10000");
        props.put("linger.ms", "0");  // 立即发送
        props.put("batch.size", "16384");
        props.put("retries", "3");
        props.put("retry.backoff.ms", "100");

        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        KafkaProducer<String, String> producer = null;
        final CountDownLatch latch = new CountDownLatch(5);
        final long startTime[] = {0};

        try {
            logger.info("1. 创建 KafkaProducer...");
            producer = new KafkaProducer<>(props);
            logger.info("   ✅ KafkaProducer 创建成功\n");

            logger.info("2. 发送5条消息到 test-topic...");

            startTime[0] = System.currentTimeMillis();

            for (int i = 1; i <= 5; i++) {
                final int msgNum = i;
                ProducerRecord<String, String> record =
                    new ProducerRecord<>("test-topic", "key-" + i, "value-" + i);

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
                                // 检查各种可能的 batch creation 相关消息
                                if (message.contains("batch creation") ||
                                    message.contains("since batch") ||
                                    message.contains("since last append")) {
                                    logger.info("\n🎯🎯🎯 成功触发 Batch Creation 超时！🎯🎯🎯");
                                    logger.info("✅✅✅ 完整消息: {}", message);
                                } else if (message.contains("Expiring") &&
                                          (message.contains("record") || message.contains("batch"))) {
                                    logger.info("\n🎯🎯🎯 触发了 Batch/Record 过期超时！🎯🎯🎯");
                                    logger.info("✅✅✅ 这很可能就是 batch creation 超时！");
                                    logger.info("✅ 完整消息: {}", message);
                                } else if (message.contains("ms") &&
                                          (message.contains("passed") || message.contains("elapsed"))) {
                                    logger.info("\n🎯 触发了时间相关的超时！");
                                    logger.info("✅ 完整消息: {}", message);
                                } else if (message.contains("timeout") || message.contains("Timeout")) {
                                    logger.info("\n⚠️  触发了超时异常");
                                    logger.info("   消息: {}", message);
                                } else {
                                    logger.warn("\n⚠️  触发了其他异常");
                                    logger.warn("   消息: {}", message);
                                }
                            }

                            if (exception.getCause() != null) {
                                logger.error("根本原因: {}", exception.getCause().getClass().getName());
                                logger.error("原因消息: {}", exception.getCause().getMessage());
                            }

                        } else {
                            logger.info("✅ 消息 #{} 发送成功 - Partition: {}, Offset: {}",
                                msgNum, metadata.partition(), metadata.offset());
                        }

                        latch.countDown();
                    }
                });

                logger.info("   消息 #{} 已提交", i);
            }

            logger.warn("\n");
            logger.warn("⚠️⚠️⚠️ 立即执行以下命令暂停 Kafka broker:");
            logger.warn("   docker pause kafka-broker");
            logger.warn("\n等待 delivery.timeout.ms=30秒 超时...\n");

            // 给用户2秒时间执行 docker pause
            Thread.sleep(2000);
            logger.info("假设 Kafka broker 已被暂停，开始等待超时...\n");

            // 等待所有 Callback 完成（最多 35 秒）
            boolean completed = latch.await(35, TimeUnit.SECONDS);

            if (!completed) {
                logger.warn("⚠️  等待超时，部分 Callback 未执行");
                logger.warn("   可能需要更长的等待时间");
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

            logger.warn("\n⚠️  记得恢复 Kafka broker:");
            logger.warn("   docker unpause kafka-broker\n");
        }
    }
}