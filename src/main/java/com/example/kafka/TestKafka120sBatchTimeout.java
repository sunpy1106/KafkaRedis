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
 * 测试类：触发真实的 "120000 ms has passed since batch creation" 错误
 *
 * 策略:
 *  - 配置 delivery.timeout.ms=120000（真实的120秒）
 *  - 使用错误的 broker 地址，但允许 metadata 缓存
 *  - 异步发送消息，不使用 .get()
 *
 * 关键: delivery.timeout.ms 控制从 batch 创建到发送成功的总时间
 */
public class TestKafka120sBatchTimeout {
    private static final Logger logger = LoggerFactory.getLogger(TestKafka120sBatchTimeout.class);

    public static void main(String[] args) {
        logger.info("========== 测试 Kafka \"120000 ms has passed since batch creation\" ==========\n");

        // 方案: 缩短的超时（30秒）用于快速验证
        testBatchCreationTimeout30s();

        // 真实的120秒超时（可选，耗时长）
        // testBatchCreationTimeout120s();

        logger.info("\n========== 测试完成 ==========");
    }

    /**
     * 测试 Batch Creation 超时（30秒版本，快速验证）
     *
     * 配置:
     *  - delivery.timeout.ms = 30000 (30秒)
     *  - bootstrap.servers = localhost:9999 (错误端口，无法发送)
     *  - linger.ms = 1000 (延迟发送，确保 batch 创建)
     *
     * 预期:
     *  - Batch 创建成功（在内存中）
     *  - 30秒后触发 "Expiring X record(s) for topic: 30XXX ms has passed since batch creation"
     */
    private static void testBatchCreationTimeout30s() {
        logger.info("【测试】Batch Creation 超时（30秒版本）");
        logger.info("配置: delivery.timeout.ms=30000, linger.ms=1000, broker=localhost:9999\n");

        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9999");  // 错误端口

        // ⚠️ 关键配置
        props.put("delivery.timeout.ms", "30000");  // 30秒超时
        props.put("linger.ms", "1000");             // 延迟1秒发送，确保 batch 创建
        props.put("batch.size", "16384");
        props.put("request.timeout.ms", "10000");
        props.put("max.block.ms", "5000");          // 快速 metadata 超时后继续

        // 关键：允许在 metadata 失败后继续
        props.put("max.in.flight.requests.per.connection", "5");
        props.put("retries", "0");  // 不重试，快速失败

        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        KafkaProducer<String, String> producer = null;
        final CountDownLatch latch = new CountDownLatch(10);  // 等待10条消息
        final long startTime[] = {0};

        try {
            logger.info("1. 创建 KafkaProducer...");
            producer = new KafkaProducer<>(props);
            logger.info("   ✅ KafkaProducer 创建成功\n");

            logger.info("2. 异步发送10条消息...");
            logger.info("   （消息会进入 buffer，但无法发送到 broker）\n");

            startTime[0] = System.currentTimeMillis();

            // 发送10条消息
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

                            // ✅ 检查是否包含 "batch creation"
                            String message = exception.getMessage();
                            if (message != null) {
                                if (message.contains("batch creation") || message.contains("since batch")) {
                                    logger.info("\n🎯 成功触发 Batch Creation 超时！");
                                    logger.info("✅ 异常消息包含: 'batch creation'");
                                    logger.info("✅ 完整消息: {}", message);
                                } else if (message.contains("Expiring") && message.contains("ms")) {
                                    logger.info("\n🎯 触发了过期异常！");
                                    logger.info("✅ 异常消息: {}", message);

                                    // 检查是否包含 30000ms
                                    if (message.contains("30") || message.contains("3")) {
                                        logger.info("✅ 包含 30 秒相关的超时时间");
                                    }
                                } else {
                                    logger.warn("\n⚠️  触发了其他异常");
                                    logger.warn("   异常消息: {}", message);
                                }
                            }

                        } else {
                            logger.warn("⚠️  消息 #{} 发送成功（不应该发生）", msgNum);
                        }

                        latch.countDown();
                    }
                });

                logger.info("   消息 #{} 已提交到 buffer", i);
            }

            logger.warn("\n等待 delivery.timeout.ms=30秒 超时...");
            logger.warn("（大约需要 30-35 秒）\n");

            // 等待所有 Callback 完成（最多 40 秒）
            boolean completed = latch.await(40, TimeUnit.SECONDS);

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
     * 测试真实的 120 秒 Batch Creation 超时（可选）
     *
     * 配置:
     *  - delivery.timeout.ms = 120000 (120秒)
     *
     * 警告: 需要等待 120+ 秒
     */
    private static void testBatchCreationTimeout120s() {
        logger.info("\n【测试】Batch Creation 超时（真实120秒版本）");
        logger.warn("⚠️  警告: 此测试需要 120+ 秒完成\n");
        logger.info("配置: delivery.timeout.ms=120000, linger.ms=1000, broker=localhost:9999\n");

        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9999");  // 错误端口

        // ⚠️ 真实的120秒超时
        props.put("delivery.timeout.ms", "120000");  // 120秒
        props.put("linger.ms", "1000");
        props.put("batch.size", "16384");
        props.put("request.timeout.ms", "30000");
        props.put("max.block.ms", "5000");
        props.put("retries", "0");

        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        KafkaProducer<String, String> producer = null;
        final CountDownLatch latch = new CountDownLatch(5);
        final long startTime[] = {0};

        try {
            logger.info("1. 创建 KafkaProducer...");
            producer = new KafkaProducer<>(props);
            logger.info("   ✅ KafkaProducer 创建成功\n");

            logger.info("2. 异步发送5条消息...\n");

            startTime[0] = System.currentTimeMillis();

            // 发送5条消息
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
                                if (message.contains("120000") || message.contains("120 seconds")) {
                                    logger.info("\n🎯 成功触发真实的 120 秒超时！");
                                    logger.info("✅ 异常消息包含: 120000 ms");
                                    logger.info("✅ 完整消息: {}", message);
                                } else if (message.contains("batch creation") || message.contains("Expiring")) {
                                    logger.info("\n🎯 触发了 Batch Creation 超时！");
                                    logger.info("✅ 异常消息: {}", message);
                                }
                            }

                        } else {
                            logger.warn("⚠️  消息 #{} 发送成功（不应该发生）", msgNum);
                        }

                        latch.countDown();
                    }
                });

                logger.info("   消息 #{} 已提交到 buffer", i);
            }

            logger.warn("\n等待 delivery.timeout.ms=120秒 超时...");
            logger.warn("（需要约 120-125 秒）");
            logger.warn("⏰ 请耐心等待...\n");

            // 启动倒计时线程
            Thread countdownThread = new Thread(() -> {
                for (int i = 120; i > 0; i--) {
                    if (i % 20 == 0 || i <= 5) {
                        logger.info("   还需等待: {} 秒...", i);
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            });
            countdownThread.setDaemon(true);
            countdownThread.start();

            // 等待所有 Callback 完成（最多 130 秒）
            boolean completed = latch.await(130, TimeUnit.SECONDS);

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
}
