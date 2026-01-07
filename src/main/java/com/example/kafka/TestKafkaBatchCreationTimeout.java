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
 * 测试类：触发 Kafka Producer "batch creation" 超时异常
 *
 * 目标异常: "X ms has passed since batch creation"
 *
 * 关键配置: delivery.timeout.ms
 */
public class TestKafkaBatchCreationTimeout {
    private static final Logger logger = LoggerFactory.getLogger(TestKafkaBatchCreationTimeout.class);

    public static void main(String[] args) {
        logger.info("========== 测试 Kafka Batch Creation 超时异常 ==========\n");

        // 方案1: 使用真实 Kafka + 手动停止（推荐，最可靠）
        testWithRealKafka();

        // 方案2: 异步发送 + 错误端口（备选，可能触发）
        // testAsyncWithWrongPort();

        logger.info("\n========== 测试完成 ==========");
    }

    /**
     * 方案1: 使用真实 Kafka + 手动停止（推荐）⭐⭐⭐⭐⭐
     *
     * 步骤:
     *  1. 确保 Kafka 正在运行: docker-compose up -d
     *  2. 程序发送消息（异步）
     *  3. 手动停止 Kafka: docker stop kafka-redis
     *  4. 等待 delivery.timeout.ms 超时
     *  5. Callback 收到 "batch creation" 超时
     *
     * 配置:
     *  - bootstrap.servers = localhost:9092 (真实 Kafka)
     *  - delivery.timeout.ms = 30000 (30秒)
     *
     * 预期: Callback 收到包含 "batch creation" 的 TimeoutException
     */
    private static void testWithRealKafka() {
        logger.info("【方案1】使用真实 Kafka + 手动停止（推荐）");
        logger.warn("\n⚠️  准备工作:");
        logger.warn("   1. 确保 Kafka 正在运行:");
        logger.warn("      docker-compose up -d");
        logger.warn("\n   2. 程序启动后，看到提示时立即停止 Kafka:");
        logger.warn("      docker stop kafka-redis");
        logger.warn("\n   3. 测试完成后重启 Kafka:");
        logger.warn("      docker start kafka-redis\n");

        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");  // 真实 Kafka

        // ⚠️ 关键配置
        props.put("delivery.timeout.ms", "30000");  // 30秒超时
        props.put("request.timeout.ms", "10000");   // 10秒请求超时
        props.put("linger.ms", "100");              // 批处理延迟
        props.put("batch.size", "16384");
        props.put("acks", "1");
        props.put("retries", "3");

        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        KafkaProducer<String, String> producer = null;
        final CountDownLatch latch = new CountDownLatch(1);
        final long startTime[] = {0};

        try {
            logger.info("1. 创建 KafkaProducer（连接到真实 Kafka）...");
            producer = new KafkaProducer<>(props);
            logger.info("   ✅ KafkaProducer 创建成功\n");

            logger.info("2. 发送多条消息（异步模式）...");
            logger.info("   将发送 100 条消息，每条延迟 50ms\n");

            startTime[0] = System.currentTimeMillis();

            // 发送100条消息，给足够时间手动停止 Kafka
            final int messageCount = 100;
            final CountDownLatch messageLatch = new CountDownLatch(messageCount);

            logger.warn("\n⚠️  ⚠️  ⚠️  请立即在另一个终端执行以下命令停止 Kafka:");
            logger.warn("   docker stop kafka-redis");
            logger.warn("\n开始发送消息，每50ms一条，共100条...\n");

            for (int i = 0; i < messageCount; i++) {
                final int msgNum = i + 1;
                ProducerRecord<String, String> record =
                    new ProducerRecord<>("test-topic", "key-" + msgNum, "value-" + msgNum);

                // ✅ 使用异步发送 + Callback
                producer.send(record, new Callback() {
                    @Override
                    public void onCompletion(RecordMetadata metadata, Exception exception) {
                        long elapsed = System.currentTimeMillis() - startTime[0];

                        if (exception != null) {
                            logger.error("\n❌ 消息 #{} 发送失败！耗时: {}ms ({} 秒)", msgNum, elapsed, elapsed / 1000);
                            logger.error("异常类型: {}", exception.getClass().getName());
                            logger.error("异常消息: {}", exception.getMessage());

                            // ✅ 检查是否包含 "batch creation"
                            String message = exception.getMessage();
                            if (message != null && message.contains("batch creation")) {
                                logger.info("\n🎯 成功触发 Batch Creation 超时！");
                                logger.info("✅ 异常消息包含: 'batch creation'");
                                logger.info("✅ 完整消息: {}", message);
                            } else if (message != null && message.contains("Expiring")) {
                                logger.info("\n🎯 触发了过期异常（消息 #{}）！", msgNum);
                                logger.info("✅ 异常消息: {}", message);
                            } else {
                                logger.warn("\n⚠️  消息 #{} 触发了其他超时异常", msgNum);
                                logger.warn("   异常消息: {}", message);
                            }

                        } else {
                            if (msgNum % 10 == 0 || msgNum <= 5) {
                                logger.info("✅ 消息 #{} 发送成功 - Partition: {}, Offset: {}",
                                    msgNum, metadata.partition(), metadata.offset());
                            }
                        }

                        messageLatch.countDown();
                    }
                });

                // 每条消息之间延迟50ms，给足够时间停止 Kafka
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    break;
                }
            }

            logger.warn("\n等待所有消息的 Callback 完成（最多 40 秒）...\n");

            // 等待 Callback 完成或超时
            boolean completed = messageLatch.await(40, TimeUnit.SECONDS);

            if (!completed) {
                logger.warn("⚠️  等待超时，Callback 未执行");
            }

        } catch (Exception e) {
            logger.error("\n发生其他异常: {}", e.getClass().getName());
            logger.error("异常消息: {}", e.getMessage(), e);

        } finally {
            if (producer != null) {
                try {
                    logger.info("\n3. 关闭 KafkaProducer...");
                    producer.close();
                    logger.info("   KafkaProducer 已关闭");
                } catch (Exception e) {
                    logger.debug("关闭 producer 时发生异常", e);
                }
            }

            logger.warn("\n⚠️  测试完成后，记得重启 Kafka:");
            logger.warn("   docker start kafka-redis\n");
        }
    }

    /**
     * 方案2: 异步发送 + 错误端口（备选）
     *
     * 配置:
     *  - bootstrap.servers = localhost:9999 (错误端口)
     *  - delivery.timeout.ms = 10000 (10秒)
     *
     * 注意: 可能仍然在 metadata 阶段失败
     * 预期: 可能触发 "batch creation" 或 "metadata" 超时
     */
    private static void testAsyncWithWrongPort() {
        logger.info("\n【方案2】异步发送 + 错误端口（备选）");
        logger.info("配置: bootstrap.servers=localhost:9999, delivery.timeout.ms=10000\n");

        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9999");  // 错误端口

        // 尝试绕过 metadata 阻塞
        props.put("delivery.timeout.ms", "10000");  // 10秒
        props.put("request.timeout.ms", "3000");
        props.put("max.block.ms", "3000");  // 快速 metadata 超时
        props.put("retries", "0");          // 不重试

        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        KafkaProducer<String, String> producer = null;
        final CountDownLatch latch = new CountDownLatch(1);
        final long startTime[] = {0};

        try {
            logger.info("1. 创建 KafkaProducer...");
            producer = new KafkaProducer<>(props);
            logger.info("   KafkaProducer 创建成功\n");

            logger.info("2. 发送消息（异步模式）...");
            ProducerRecord<String, String> record =
                new ProducerRecord<>("test-topic", "test-key", "test-value");

            startTime[0] = System.currentTimeMillis();

            producer.send(record, new Callback() {
                @Override
                public void onCompletion(RecordMetadata metadata, Exception exception) {
                    long elapsed = System.currentTimeMillis() - startTime[0];

                    if (exception != null) {
                        logger.error("\n❌ 发送失败！耗时: {}ms", elapsed);
                        logger.error("异常类型: {}", exception.getClass().getName());
                        logger.error("异常消息: {}", exception.getMessage());

                        String message = exception.getMessage();
                        if (message != null) {
                            if (message.contains("batch creation")) {
                                logger.info("\n🎯 成功触发 Batch Creation 超时！");
                            } else if (message.contains("metadata")) {
                                logger.warn("\n⚠️  触发了 Metadata 超时（不是目标错误）");
                                logger.warn("   需要使用方案1（真实 Kafka + 手动停止）");
                            } else {
                                logger.warn("\n⚠️  触发了其他异常");
                            }
                            logger.info("   消息: {}", message);
                        }

                    } else {
                        logger.info("✅ 消息发送成功（不应该发生）");
                    }

                    latch.countDown();
                }
            });

            logger.info("   等待 Callback 执行...\n");

            // 等待 Callback 完成
            latch.await(15, TimeUnit.SECONDS);

        } catch (Exception e) {
            logger.error("\n发生其他异常: {}", e.getClass().getName());
            logger.error("异常消息: {}", e.getMessage(), e);

        } finally {
            if (producer != null) {
                try {
                    producer.close();
                    logger.info("\nKafkaProducer 已关闭");
                } catch (Exception e) {
                    logger.debug("关闭 producer 时发生异常", e);
                }
            }
        }
    }
}
