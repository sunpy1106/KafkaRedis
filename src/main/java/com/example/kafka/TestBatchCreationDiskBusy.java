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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试类：通过磁盘I/O繁忙触发 "batch creation" 超时
 *
 * 策略:
 *  - Kafka 正常运行
 *  - 发送预热消息（缓存 metadata）
 *  - 启动大量dd进程制造极大的磁盘I/O压力
 *  - 发送测试消息
 *  - Broker的fsync操作被阻塞，无法及时返回确认
 *  - 等待 delivery.timeout.ms 超时
 *
 * 目标错误: "X ms has passed since batch creation"
 *
 * 关键区别：
 *  - 磁盘满：fsync仍然快（预分配空间内）
 *  - 磁盘繁忙：fsync真正阻塞在内核I/O操作上
 *
 * 使用方法:
 *  1. 运行此程序
 *  2. 看到提示后在另一个终端执行I/O压力命令
 *  3. 等待超时
 *  4. 清理dd进程和文件
 */
public class TestBatchCreationDiskBusy {
    private static final Logger logger = LoggerFactory.getLogger(TestBatchCreationDiskBusy.class);

    public static void main(String[] args) {
        logger.info("========== 测试磁盘I/O繁忙触发 Batch Creation 超时 ==========\n");

        testDiskBusy();

        logger.info("\n========== 测试完成 ==========");
    }

    private static void testDiskBusy() {
        logger.info("【测试】磁盘I/O繁忙方案");
        logger.info("策略: 制造极大磁盘I/O压力，阻塞Broker的fsync操作\n");

        logger.warn("⚠️  准备步骤:");
        logger.warn("   1. 确保 Kafka broker 正在运行");
        logger.warn("   2. 准备在看到提示后执行I/O压力命令");
        logger.warn("   3. 测试完成后记得清理进程和文件\n");

        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");  // 真实 Kafka

        // 优化配置以增加触发概率
        props.put("acks", "all");  // 需要所有ISR确认（更严格）
        props.put("delivery.timeout.ms", "40000");  // 40秒超时
        props.put("request.timeout.ms", "15000");   // 15秒请求超时
        props.put("linger.ms", "100");  // 100ms延迟，让消息积累
        props.put("batch.size", "1024");  // 小batch size，容易填满
        props.put("retries", "3");
        props.put("retry.backoff.ms", "100");

        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        KafkaProducer<String, String> producer = null;
        final CountDownLatch warmupLatch = new CountDownLatch(1);
        final CountDownLatch testLatch = new CountDownLatch(100);  // 100条消息
        final long startTime[] = {0};
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);

        try {
            logger.info("1. 创建 KafkaProducer...");
            producer = new KafkaProducer<>(props);
            logger.info("   ✅ KafkaProducer 创建成功");
            logger.info("   配置: acks=all, delivery.timeout=40s, request.timeout=15s\n");

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
            logger.warn("⚠️⚠️⚠️ 关键步骤 - 请在另一个终端执行以下命令:");
            logger.warn("");
            logger.warn("# 启动20个dd进程制造极大I/O压力");
            logger.warn("for i in {{1..20}}; do");
            logger.warn("  docker exec -d kafka-broker dd if=/dev/zero \\");
            logger.warn("    of=/kafka/kafka-logs-kafka/stress_$i.dat \\");
            logger.warn("    bs=4M count=2000 oflag=sync 2>/dev/null &");
            logger.warn("done");
            logger.warn("");
            logger.warn("# 验证I/O压力（可选）");
            logger.warn("docker exec kafka-broker ps aux | grep -c dd");
            logger.warn("");
            logger.warn("⏰ 请在 15 秒内启动I/O压力...\n");

            // 等待用户启动I/O压力
            Thread.sleep(15000);

            logger.info("3. 等待5秒，让I/O压力充分生效...\n");
            Thread.sleep(5000);

            logger.info("4. 发送100条测试消息（增加触发flush的概率）...");
            logger.info("   此时磁盘I/O应该极度繁忙");
            logger.info("   Broker的fsync操作应该被严重阻塞\n");

            startTime[0] = System.currentTimeMillis();

            // 发送100条消息
            for (int i = 1; i <= 100; i++) {
                final int msgNum = i;
                ProducerRecord<String, String> record =
                    new ProducerRecord<>("test-topic", "key-" + i, "value-" + i);

                producer.send(record, new Callback() {
                    @Override
                    public void onCompletion(RecordMetadata metadata, Exception exception) {
                        long elapsed = System.currentTimeMillis() - startTime[0];

                        if (exception != null) {
                            failureCount.incrementAndGet();
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
                                } else if (message.contains("40") && message.contains("ms")) {
                                    logger.info("\n🎯 触发了40秒超时！");
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
                            successCount.incrementAndGet();
                            if (msgNum % 20 == 0) {
                                logger.info("✅ 消息 #{} 发送成功 - Partition: {}, Offset: {}",
                                    msgNum, metadata.partition(), metadata.offset());
                            }
                        }

                        testLatch.countDown();
                    }
                });

                if (i % 20 == 0) {
                    logger.info("   已提交 {} 条消息...", i);
                }
            }

            logger.warn("\n等待 delivery.timeout.ms=40秒 超时...");
            logger.warn("（如果磁盘I/O极度繁忙，Broker fsync阻塞，应该会超时）\n");

            // 启动倒计时线程
            Thread countdownThread = new Thread(() -> {
                for (int i = 40; i > 0; i--) {
                    if (i % 10 == 0 || i <= 5) {
                        logger.info("   还需等待: {} 秒... (成功:{}, 失败:{})",
                            i, successCount.get(), failureCount.get());
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

            // 等待所有 Callback 完成（最多 45 秒）
            boolean completed = testLatch.await(45, TimeUnit.SECONDS);

            logger.info("\n========== 测试结果 ==========");
            logger.info("成功发送: {} 条", successCount.get());
            logger.info("发送失败: {} 条", failureCount.get());
            logger.info("处理完成: {}", completed ? "是" : "否（部分超时）");
            logger.info("==============================\n");

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
                    logger.info("\n5. 关闭 KafkaProducer...");
                    producer.close(5, TimeUnit.SECONDS);
                    logger.info("   KafkaProducer 已关闭\n");
                } catch (Exception e) {
                    logger.debug("关闭 producer 时发生异常", e);
                }
            }

            logger.warn("\n⚠️  记得清理I/O压力:");
            logger.warn("   # 停止所有dd进程");
            logger.warn("   docker exec kafka-broker pkill dd");
            logger.warn("");
            logger.warn("   # 删除压力文件");
            logger.warn("   docker exec kafka-broker rm -f /kafka/kafka-logs-kafka/stress_*.dat");
            logger.warn("");
            logger.warn("   # 验证清理");
            logger.warn("   docker exec kafka-broker df -h /kafka\n");
        }
    }
}
