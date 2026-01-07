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
 * 测试类：使用改进的 TCP 代理触发 "batch creation" 超时
 *
 * 改进策略:
 *  - 启动 TCP 代理（监听 19092，转发到 9092）
 *  - Producer 连接到代理（localhost:19092）
 *  - 发送预热消息缓存 metadata
 *  - 发送测试消息到 batch
 *  - ⭐ 关键改进：仅阻止响应转发（Kafka->Client）
 *  - 请求仍然到达 Broker（Client->Kafka）
 *  - 但 Producer 收不到 ACK 响应
 *  - 等待 delivery.timeout.ms 超时
 *
 * 目标错误: "X ms has passed since batch creation"
 *
 * 使用方法:
 *  1. 先运行 KafkaTcpProxy（在另一个终端或后台）
 *  2. 然后运行此测试类
 */
public class TestBatchCreationWithProxyV2 {
    private static final Logger logger = LoggerFactory.getLogger(TestBatchCreationWithProxyV2.class);

    public static void main(String[] args) {
        logger.info("========== 测试改进的 TCP 代理（仅阻止响应）触发 Batch Creation 超时 ==========\n");

        testWithProxyV2();

        logger.info("\n========== 测试完成 ==========");
    }

    private static void testWithProxyV2() {
        logger.info("【测试】改进的 TCP 代理方案 V2");
        logger.info("策略: 阻止 Broker->Producer 响应，但允许 Producer->Broker 请求\n");

        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:19092");  // 连接到代理

        // 关键配置
        props.put("acks", "1");
        props.put("delivery.timeout.ms", "30000");  // 30秒超时
        props.put("request.timeout.ms", "10000");
        props.put("linger.ms", "0");  // 立即发送（不需要延迟）
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
            logger.info("1. 创建 KafkaProducer（连接到代理 localhost:19092）...");
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

            logger.info("3. 发送10条测试消息...");
            logger.info("   （消息将通过代理发送）\n");

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

            logger.warn("\n");
            logger.warn("⚠️⚠️⚠️ 关键步骤:");
            logger.warn("   消息已发送（linger.ms=0，立即发送）");
            logger.warn("   现在立即阻止响应转发");
            logger.warn("\n");

            // ⭐ 关键改进：仅停止响应转发
            logger.warn("🛑 停止响应转发（Kafka->Client）...");
            KafkaTcpProxy.stopResponseForwarding();
            logger.warn("✅ 代理已停止响应转发");
            logger.warn("   请求仍会转发，但 Producer 收不到 ACK");
            logger.warn("   等待超时...");

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

            // 恢复代理转发
            logger.info("恢复代理转发...");
            KafkaTcpProxy.resumeForwarding();
        }
    }
}
