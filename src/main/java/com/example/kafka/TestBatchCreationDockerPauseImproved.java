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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 测试类：改进的 Docker pause 方案触发 "batch creation" 超时
 *
 * 策略:
 *  - 使用 linger.ms=5000 延迟 batch 发送
 *  - 先发送预热消息缓存 metadata
 *  - 发送消息后在 linger 时间内暂停 Kafka broker
 *  - Batch 已创建但无法发送
 *  - 等待 delivery.timeout.ms 超时
 *
 * 目标错误: "X ms has passed since batch creation"
 *
 * 使用方法:
 *  1. 运行此程序
 *  2. 程序会自动在合适的时机提示执行 docker pause
 *  3. 等待超时
 *  4. 程序结束后执行 docker unpause
 */
public class TestBatchCreationDockerPauseImproved {
    private static final Logger logger = LoggerFactory.getLogger(TestBatchCreationDockerPauseImproved.class);
    private static final AtomicBoolean shouldPause = new AtomicBoolean(false);

    public static void main(String[] args) {
        logger.info("========== 测试改进的 Docker pause 触发 Batch Creation 超时 ==========\n");

        testDockerPauseImproved();

        logger.info("\n========== 测试完成 ==========");
    }

    private static void testDockerPauseImproved() {
        logger.info("【测试】改进的 Docker pause 方案");
        logger.info("策略: 使用 linger.ms 延迟发送，在此期间暂停 broker\n");

        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");  // 真实 Kafka

        // 关键配置：使用 linger.ms 延迟 batch 发送
        props.put("acks", "1");
        props.put("delivery.timeout.ms", "30000");  // 30秒超时
        props.put("request.timeout.ms", "10000");
        props.put("linger.ms", "5000");  // ⚠️ 关键：延迟5秒发送，给我们时间暂停broker
        props.put("batch.size", "16384");
        props.put("retries", "3");
        props.put("retry.backoff.ms", "100");

        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        KafkaProducer<String, String> producer = null;
        final CountDownLatch warmupLatch = new CountDownLatch(1);
        final CountDownLatch testLatch = new CountDownLatch(10);
        final long startTime[] = {0};

        // 启动一个线程监控何时暂停
        Thread pauseThread = new Thread(() -> {
            while (!shouldPause.get()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }

            logger.warn("\n");
            logger.warn("⚠️⚠️⚠️ 立即执行:");
            logger.warn("   docker pause kafka-broker");
            logger.warn("⚠️⚠️⚠️ 你有 3 秒时间执行！");
            logger.warn("\n");

            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                // ignore
            }

            logger.info("假设 Kafka broker 已被暂停，继续等待超时...\n");
        });
        pauseThread.setDaemon(true);
        pauseThread.start();

        try {
            logger.info("1. 创建 KafkaProducer (linger.ms=5000)...");
            producer = new KafkaProducer<>(props);
            logger.info("   ✅ KafkaProducer 创建成功\n");

            logger.info("2. 发送预热消息（缓存 metadata）...");

            // 先用一个没有linger的producer发送预热消息
            Properties warmupProps = new Properties();
            warmupProps.putAll(props);
            warmupProps.put("linger.ms", "0");  // 预热消息立即发送

            KafkaProducer<String, String> warmupProducer = new KafkaProducer<>(warmupProps);
            ProducerRecord<String, String> warmupRecord =
                new ProducerRecord<>("test-topic", "warmup-key", "warmup-value");

            warmupProducer.send(warmupRecord, new Callback() {
                @Override
                public void onCompletion(RecordMetadata metadata, Exception exception) {
                    if (exception != null) {
                        logger.error("预热消息发送失败: {}", exception.getMessage());
                    } else {
                        logger.info("   ✅ 预热消息发送成功 - Partition: {}, Offset: {}",
                            metadata.partition(), metadata.offset());
                        logger.info("   ✅ Metadata 已缓存\n");
                    }
                    warmupLatch.countDown();
                }
            });

            // 等待预热完成
            if (!warmupLatch.await(10, TimeUnit.SECONDS)) {
                logger.error("预热消息发送超时");
                return;
            }

            warmupProducer.close();

            logger.info("3. 发送10条测试消息（batch 将在5秒后发送）...");
            logger.info("   由于 linger.ms=5000，消息会在 batch 中等待5秒\n");

            startTime[0] = System.currentTimeMillis();

            // 发送10条消息，它们会被放入同一个 batch
            for (int i = 1; i <= 10; i++) {
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
                                } else if (message.contains("30") && message.contains("ms")) {
                                    logger.info("\n🎯 触发了30秒超时！");
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

                        testLatch.countDown();
                    }
                });

                logger.info("   消息 #{} 已加入 batch", i);
            }

            logger.info("\n✅ 所有消息已加入 batch");
            logger.info("⏰ Batch 将在 5 秒后（linger.ms）尝试发送");
            logger.info("📌 现在是暂停 Kafka broker 的最佳时机！\n");

            // 触发暂停提示
            shouldPause.set(true);

            // 等待2秒，让用户有时间暂停
            Thread.sleep(2000);

            logger.warn("\n等待 delivery.timeout.ms=30秒 超时...\n");

            // 启动倒计时线程
            Thread countdownThread = new Thread(() -> {
                for (int i = 30; i > 0; i--) {
                    if (i % 10 == 0 || i <= 5) {
                        logger.info("   还需等待: {} 秒...", i);
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                    if (testLatch.getCount() == 0) {
                        logger.info("   所有消息处理完毕");
                        break;
                    }
                }
            });
            countdownThread.setDaemon(true);
            countdownThread.start();

            // 等待所有 Callback 完成（最多 40 秒）
            boolean completed = testLatch.await(40, TimeUnit.SECONDS);

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
                    logger.info("\n4. 关闭 KafkaProducer...");
                    producer.close(5, TimeUnit.SECONDS);  // 最多等待5秒
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