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
 * 测试类：使用 iptables DROP 规则触发 "batch creation" 超时
 *
 * 策略:
 *  - 先发送一条测试消息，让 metadata 缓存
 *  - 使用 iptables DROP 规则丢弃到 Kafka 的数据包
 *  - DROP 不会返回错误，导致连接挂起
 *  - 等待 delivery.timeout.ms 超时
 *
 * 目标错误: "X ms has passed since batch creation"
 *
 * 使用方法:
 *  1. 运行此程序
 *  2. 看到提示后执行（需要 sudo 权限）:
 *     macOS: sudo pfctl -e && echo "block drop out proto tcp to any port 9092" | sudo pfctl -f -
 *     Linux: sudo iptables -A OUTPUT -p tcp --dport 9092 -j DROP
 *  3. 等待超时
 *  4. 恢复网络:
 *     macOS: sudo pfctl -d
 *     Linux: sudo iptables -D OUTPUT -p tcp --dport 9092 -j DROP
 */
public class TestBatchCreationIptablesDrop {
    private static final Logger logger = LoggerFactory.getLogger(TestBatchCreationIptablesDrop.class);

    public static void main(String[] args) {
        logger.info("========== 测试 iptables DROP 触发 Batch Creation 超时 ==========\n");

        testIptablesDrop();

        logger.info("\n========== 测试完成 ==========");
    }

    private static void testIptablesDrop() {
        logger.info("【测试】iptables DROP 方案");
        logger.info("策略: 使用防火墙规则丢弃数据包，模拟网络挂起\n");

        logger.warn("⚠️  准备步骤:");
        logger.warn("   1. 确保 Kafka broker 正在运行");
        logger.warn("   2. 本测试需要 sudo 权限");
        logger.warn("   3. 看到提示后执行防火墙命令\n");

        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");  // 真实 Kafka

        // 关键配置
        props.put("acks", "1");
        props.put("delivery.timeout.ms", "30000");  // 30秒超时
        props.put("request.timeout.ms", "10000");
        props.put("linger.ms", "0");  // 立即发送
        props.put("batch.size", "16384");
        props.put("retries", "3");
        props.put("retry.backoff.ms", "100");
        props.put("max.block.ms", "5000");  // metadata 获取超时

        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        KafkaProducer<String, String> producer = null;
        final CountDownLatch warmupLatch = new CountDownLatch(1);
        final CountDownLatch testLatch = new CountDownLatch(5);
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

            // 给用户时间设置防火墙规则
            logger.warn("\n");
            logger.warn("⚠️⚠️⚠️ 立即执行以下命令阻断网络:");
            logger.warn("   macOS: sudo pfctl -e && echo \"block drop out proto tcp to any port 9092\" | sudo pfctl -f -");
            logger.warn("   Linux: sudo iptables -A OUTPUT -p tcp --dport 9092 -j DROP");
            logger.warn("\n请在 5 秒内执行...\n");

            // 等待 5 秒让用户执行命令
            Thread.sleep(5000);

            logger.info("3. 发送测试消息（应该会被阻塞）...");

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

                        testLatch.countDown();
                    }
                });

                logger.info("   消息 #{} 已提交", i);
            }

            logger.warn("\n等待 delivery.timeout.ms=30秒 超时...\n");

            // 启动监控线程
            Thread monitorThread = new Thread(() -> {
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
                        break;
                    }
                }
            });
            monitorThread.setDaemon(true);
            monitorThread.start();

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
                    producer.close(5, TimeUnit.SECONDS);  // 最多等待5秒
                    logger.info("   KafkaProducer 已关闭\n");
                } catch (Exception e) {
                    logger.debug("关闭 producer 时发生异常", e);
                }
            }

            logger.warn("\n⚠️  记得恢复网络:");
            logger.warn("   macOS: sudo pfctl -d");
            logger.warn("   Linux: sudo iptables -D OUTPUT -p tcp --dport 9092 -j DROP\n");
        }
    }
}