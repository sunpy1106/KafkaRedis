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
 * 测试类：渐进式I/O压力测试
 *
 * 策略改进：
 *  1. 先建立连接并完成预热
 *  2. 然后启动较轻的I/O压力（5个dd而不是20个）
 *  3. 观察结果，根据情况调整dd数量
 *
 * 关键区别：
 *  - 之前：20个dd，I/O完全饱和，Kafka无法响应连接
 *  - 现在：5个dd，I/O部分饱和，Kafka能响应但fsync慢
 */
public class TestBatchCreationProgressiveIO {
    private static final Logger logger = LoggerFactory.getLogger(TestBatchCreationProgressiveIO.class);

    public static void main(String[] args) {
        logger.info("========== 渐进式I/O压力测试 - Batch Creation超时 ==========\n");

        // 可通过命令行参数指定dd进程数量，默认5个
        int ddCount = 5;
        if (args.length > 0) {
            try {
                ddCount = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                logger.warn("无效的dd数量参数，使用默认值: 5");
            }
        }

        testWithProgressiveIO(ddCount);

        logger.info("\n========== 测试完成 ==========");
    }

    private static void testWithProgressiveIO(int ddCount) {
        logger.info("【测试】渐进式I/O压力方案");
        logger.info("策略: 先建立连接 → 启动{}个dd进程 → 发送消息\n", ddCount);

        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");

        // 优化配置
        props.put("acks", "all");  // 需要所有ISR确认
        props.put("delivery.timeout.ms", "60000");  // 60秒超时（更宽容）
        props.put("request.timeout.ms", "20000");   // 20秒请求超时
        props.put("linger.ms", "100");
        props.put("batch.size", "1024");
        props.put("retries", "2");
        props.put("retry.backoff.ms", "100");

        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        KafkaProducer<String, String> producer = null;
        final CountDownLatch warmupLatch = new CountDownLatch(1);
        final CountDownLatch testLatch = new CountDownLatch(50);  // 50条消息
        final long startTime[] = {0};
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);

        try {
            logger.info("步骤1: 创建 KafkaProducer...");
            producer = new KafkaProducer<>(props);
            logger.info("   ✅ KafkaProducer 创建成功");
            logger.info("   配置: acks=all, delivery.timeout=60s, request.timeout=20s\n");

            logger.info("步骤2: 发送预热消息（缓存 metadata）...");
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

            logger.info("步骤3: 启动{}个dd进程制造I/O压力...\n", ddCount);

            // 启动dd进程（较少的数量，较小的写入量）
            ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                String.format("for i in {1..%d}; do " +
                    "docker exec -d kafka-broker dd if=/dev/zero of=/var/lib/kafka/data/stress_$i.dat " +
                    "bs=4M count=500 oflag=sync 2>/dev/null & " +
                    "done", ddCount));

            Process process = pb.start();
            process.waitFor(5, TimeUnit.SECONDS);

            // 验证dd进程启动
            Thread.sleep(2000);
            ProcessBuilder checkPb = new ProcessBuilder("bash", "-c",
                "docker exec kafka-broker ps aux | grep dd | grep -v grep | wc -l");
            Process checkProcess = checkPb.start();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(checkProcess.getInputStream()));
            String count = reader.readLine();
            logger.info("   已启动dd进程数: {}", count != null ? count.trim() : "未知");

            logger.info("\n步骤4: 等待5秒让I/O压力生效...\n");
            Thread.sleep(5000);

            logger.info("步骤5: 发送50条测试消息...");
            logger.info("   此时磁盘I/O应该有压力，但不至于完全阻塞\n");

            startTime[0] = System.currentTimeMillis();

            // 发送50条消息
            for (int i = 1; i <= 50; i++) {
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
                                if (message.contains("batch creation") ||
                                    message.contains("since batch") ||
                                    message.contains("since last append")) {
                                    logger.info("\n🎯🎯🎯 成功触发 Batch Creation 超时！🎯🎯🎯");
                                    logger.info("✅✅✅ 完整消息: {}", message);
                                } else if (message.contains("Expiring") &&
                                          (message.contains("record") || message.contains("batch"))) {
                                    logger.info("\n🎯🎯🎯 触发了 Batch/Record 过期超时！🎯🎯🎯");
                                    logger.info("✅✅✅ 完整消息: {}", message);
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

                        } else {
                            successCount.incrementAndGet();
                            if (msgNum % 10 == 0 || elapsed > 1000) {
                                logger.info("✅ 消息 #{} 发送成功 - 耗时: {}ms, Partition: {}, Offset: {}",
                                    msgNum, elapsed, metadata.partition(), metadata.offset());
                            }
                        }

                        testLatch.countDown();
                    }
                });

                if (i % 10 == 0) {
                    logger.info("   已提交 {} 条消息...", i);
                }
            }

            logger.warn("\n等待消息处理完成（最多65秒）...");
            logger.warn("（如果{}个dd进程产生的I/O压力刚好，应该会触发超时）\n", ddCount);

            // 启动监控线程
            Thread monitorThread = new Thread(() -> {
                for (int i = 60; i > 0; i--) {
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
            monitorThread.setDaemon(true);
            monitorThread.start();

            // 等待所有 Callback 完成
            boolean completed = testLatch.await(65, TimeUnit.SECONDS);

            logger.info("\n========== 测试结果 ==========");
            logger.info("dd进程数量: {}", ddCount);
            logger.info("成功发送: {} 条", successCount.get());
            logger.info("发送失败: {} 条", failureCount.get());
            logger.info("处理完成: {}", completed ? "是" : "否（部分超时）");

            // 给出调整建议
            if (failureCount.get() == 0 && successCount.get() == 50) {
                logger.warn("\n⚠️  所有消息成功，I/O压力不够");
                logger.warn("   建议：增加dd进程数量，尝试 {} 个", ddCount + 2);
            } else if (successCount.get() == 0) {
                logger.warn("\n⚠️  所有消息失败，I/O压力过大");
                logger.warn("   建议：减少dd进程数量，尝试 {} 个", Math.max(1, ddCount - 2));
            } else {
                logger.info("\n✅ 部分成功部分失败，找到了临界点！");
                logger.info("   {}个dd进程产生的I/O压力刚好合适", ddCount);
            }
            logger.info("==============================\n");

        } catch (Exception e) {
            logger.error("\n发生其他异常: {}", e.getClass().getName());
            logger.error("异常消息: {}", e.getMessage(), e);

        } finally {
            if (producer != null) {
                try {
                    logger.info("\n步骤6: 关闭 KafkaProducer...");
                    producer.close(5, TimeUnit.SECONDS);
                    logger.info("   KafkaProducer 已关闭\n");
                } catch (Exception e) {
                    logger.debug("关闭 producer 时发生异常", e);
                }
            }

            // 清理dd进程
            try {
                logger.info("清理dd进程和压力文件...");
                ProcessBuilder cleanupPb = new ProcessBuilder("bash", "-c",
                    "docker exec kafka-broker pkill dd 2>/dev/null; " +
                    "docker exec kafka-broker rm -f /var/lib/kafka/data/stress_*.dat 2>/dev/null");
                cleanupPb.start().waitFor(5, TimeUnit.SECONDS);
                logger.info("   清理完成\n");
            } catch (Exception e) {
                logger.debug("清理时发生异常", e);
            }
        }
    }
}
