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
 * 测试类：通过填满磁盘触发 "batch creation" 超时
 *
 * 策略:
 *  - Kafka 正常运行
 *  - 发送预热消息（缓存 metadata）
 *  - 在 Kafka 数据目录填满磁盘
 *  - 发送测试消息
 *  - Broker 无法写入磁盘，无法返回确认
 *  - 等待 delivery.timeout.ms 超时
 *
 * 目标错误: "X ms has passed since batch creation"
 *
 * 使用方法:
 *  1. 运行此程序
 *  2. 看到提示后在另一个终端执行:
 *     docker exec kafka-broker dd if=/dev/zero of=/tmp/kafka-logs/fillup.dat bs=1M count=10000
 *  3. 等待超时
 *  4. 清理: docker exec kafka-broker rm /tmp/kafka-logs/fillup.dat
 */
public class TestBatchCreationDiskFull {
    private static final Logger logger = LoggerFactory.getLogger(TestBatchCreationDiskFull.class);

    public static void main(String[] args) {
        logger.info("========== 测试磁盘填满触发 Batch Creation 超时 ==========\n");

        testDiskFull();

        logger.info("\n========== 测试完成 ==========");
    }

    private static void testDiskFull() {
        logger.info("【测试】磁盘填满方案");
        logger.info("策略: 填满 Kafka 数据目录，导致 Broker 无法写入\n");

        logger.warn("⚠️  准备步骤:");
        logger.warn("   1. 确保 Kafka broker 正在运行");
        logger.warn("   2. 准备在看到提示后执行命令填满磁盘");
        logger.warn("   3. 测试完成后记得清理磁盘空间\n");

        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");  // 真实 Kafka

        // 关键配置
        props.put("acks", "1");  // Broker 必须写入磁盘才确认
        props.put("delivery.timeout.ms", "30000");  // 30秒超时
        props.put("request.timeout.ms", "10000");
        props.put("linger.ms", "1000");  // 稍微延迟
        props.put("batch.size", "16384");
        props.put("retries", "3");
        props.put("retry.backoff.ms", "100");

        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        KafkaProducer<String, String> producer = null;
        final CountDownLatch warmupLatch = new CountDownLatch(1);
        final CountDownLatch testLatch = new CountDownLatch(10);
        final long startTime[] = {0};

        try {
            logger.info("1. 创建 KafkaProducer...");
            producer = new KafkaProducer<>(props);
            logger.info("   ✅ KafkaProducer 创建成功\n");

            logger.info("2. 发送预热消息（缓存 metadata）...");
            ProducerRecord<String, String> warmupRecord =
                new ProducerRecord<>("test-topic", "warmup-key", "warmup-value");

            producer.send(warmupRecord, new Callback() {
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

            logger.warn("\n");
            logger.warn("⚠️⚠️⚠️ 关键步骤 - 请在另一个终端执行:");
            logger.warn("   # 查看 Kafka 数据目录");
            logger.warn("   docker exec kafka-broker df -h /kafka");
            logger.warn("");
            logger.warn("   # 填满磁盘（创建大文件 - 填满剩余 30G）");
            logger.warn("   docker exec kafka-broker dd if=/dev/zero of=/kafka/kafka-logs-kafka/fillup.dat bs=1M count=30000");
            logger.warn("");
            logger.warn("   # 或者更激进：填满所有可用空间");
            logger.warn("   docker exec kafka-broker sh -c 'dd if=/dev/zero of=/kafka/kafka-logs-kafka/fillup.dat bs=1M || true'");
            logger.warn("");
            logger.warn("⏰ 请在 10 秒内执行填满磁盘命令...\n");

            // 等待用户填满磁盘
            Thread.sleep(10000);

            logger.info("3. 发送10条测试消息...");
            logger.info("   （Broker 应该无法写入磁盘）\n");

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
                                } else if (message.contains("30") && message.contains("ms")) {
                                    logger.info("\n🎯 触发了30秒超时！");
                                    logger.info("✅ 完整消息: {}", message);
                                } else if (message.contains("disk") || message.contains("space") ||
                                          message.contains("full") || message.contains("quota")) {
                                    logger.info("\n🎯 触发了磁盘相关错误！");
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

                logger.info("   消息 #{} 已提交", i);
            }

            logger.warn("\n等待 delivery.timeout.ms=30秒 超时...");
            logger.warn("（如果磁盘已满，Broker 无法写入，应该会超时）\n");

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

            // 等待所有 Callback 完成（最多 35 秒）
            boolean completed = testLatch.await(35, TimeUnit.SECONDS);

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
                    producer.close(5, TimeUnit.SECONDS);
                    logger.info("   KafkaProducer 已关闭\n");
                } catch (Exception e) {
                    logger.debug("关闭 producer 时发生异常", e);
                }
            }

            logger.warn("\n⚠️  记得清理磁盘空间:");
            logger.warn("   docker exec kafka-broker rm -f /kafka/kafka-logs-kafka/fillup.dat");
            logger.warn("   docker exec kafka-broker df -h /kafka\n");
        }
    }
}
