package com.example.kafka;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 测试类：使用 docker pause 触发 "batch creation" 超时
 *
 * 核心策略:
 *  1. 发送预热消息成功（metadata 缓存）
 *  2. 使用 docker pause 暂停 Kafka 容器
 *     - 容器暂停后，网络连接保持但不响应
 *     - 这模拟了"灰色故障"状态
 *  3. 发送测试消息（会被缓存到 batch）
 *  4. 等待 delivery.timeout.ms 超时
 *  5. 使用 docker unpause 恢复容器
 *
 * 目标错误: "X ms has passed since batch creation"
 *
 * 使用方法:
 *   mvn exec:java -Dexec.mainClass="com.example.kafka.TestBatchCreationDockerPauseV2"
 */
public class TestBatchCreationDockerPauseV2 {
    private static final Logger logger = LoggerFactory.getLogger(TestBatchCreationDockerPauseV2.class);

    private static final String BOOTSTRAP_SERVERS = "localhost:19092";
    private static final String TOPIC = "test-topic";
    private static final String CONTAINER_NAME = "kafka-broker";

    // 超时配置 - 使用较短的超时以便快速测试
    private static final int DELIVERY_TIMEOUT_MS = 30000;  // 30秒
    private static final int REQUEST_TIMEOUT_MS = 10000;   // 10秒
    private static final int MAX_BLOCK_MS = 10000;         // 10秒

    public static void main(String[] args) {
        logger.info("╔══════════════════════════════════════════════════════════════╗");
        logger.info("║   Docker Pause 方法触发 Batch Creation 超时                    ║");
        logger.info("╚══════════════════════════════════════════════════════════════╝\n");

        runTest();

        logger.info("\n========== 测试完成 ==========");
    }

    private static void runTest() {
        KafkaProducer<String, String> producer = null;
        final AtomicBoolean containerPaused = new AtomicBoolean(false);
        final CountDownLatch warmupLatch = new CountDownLatch(1);
        final CountDownLatch testLatch = new CountDownLatch(20);  // 20条消息
        final long[] startTime = {0};
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);
        final AtomicBoolean targetErrorTriggered = new AtomicBoolean(false);

        try {
            // 确保容器是运行状态
            ensureContainerRunning();

            // 1. 创建 Producer
            Properties props = new Properties();
            props.put("bootstrap.servers", BOOTSTRAP_SERVERS);
            props.put("key.serializer", StringSerializer.class.getName());
            props.put("value.serializer", StringSerializer.class.getName());

            // 关键配置
            props.put("acks", "all");
            props.put("delivery.timeout.ms", String.valueOf(DELIVERY_TIMEOUT_MS));
            props.put("request.timeout.ms", String.valueOf(REQUEST_TIMEOUT_MS));
            props.put("max.block.ms", String.valueOf(MAX_BLOCK_MS));

            // 批量配置 - 让消息积累成 batch
            props.put("linger.ms", "500");      // 500ms 延迟
            props.put("batch.size", "16384");   // 16KB batch

            // 重试配置
            props.put("retries", "5");
            props.put("retry.backoff.ms", "1000");

            logger.info("1. 创建 KafkaProducer...");
            logger.info("   └─ bootstrap.servers: {}", BOOTSTRAP_SERVERS);
            logger.info("   └─ delivery.timeout.ms: {}", DELIVERY_TIMEOUT_MS);
            logger.info("   └─ request.timeout.ms: {}", REQUEST_TIMEOUT_MS);
            logger.info("   └─ linger.ms: 500");
            logger.info("   └─ acks: all");

            producer = new KafkaProducer<>(props);
            logger.info("   ✅ KafkaProducer 创建成功\n");

            // 2. 发送预热消息
            logger.info("2. 发送预热消息（缓存 metadata）...");
            producer.send(
                new ProducerRecord<>(TOPIC, "warmup", "warmup-" + System.currentTimeMillis()),
                (metadata, exception) -> {
                    if (exception != null) {
                        logger.error("   ❌ 预热失败: {}", exception.getMessage());
                    } else {
                        logger.info("   ✅ 预热成功 - Partition: {}, Offset: {}",
                            metadata.partition(), metadata.offset());
                    }
                    warmupLatch.countDown();
                }
            );

            if (!warmupLatch.await(15, TimeUnit.SECONDS)) {
                logger.error("   ❌ 预热超时，测试终止");
                return;
            }

            // 强制刷新确保预热完成
            producer.flush();
            logger.info("   ✅ Metadata 已缓存\n");

            // 等待一下确保连接稳定
            Thread.sleep(2000);

            // 3. 暂停容器
            logger.info("3. 暂停 Kafka 容器 [{}]...", CONTAINER_NAME);
            boolean paused = pauseContainer();
            if (paused) {
                containerPaused.set(true);
                logger.info("   ✅ 容器已暂停");
                logger.info("   📋 说明: 容器暂停后网络连接保持但不响应\n");
            } else {
                logger.error("   ❌ 暂停容器失败，测试终止");
                return;
            }

            // 等待暂停生效
            Thread.sleep(1000);

            // 4. 发送测试消息
            logger.info("4. 发送 20 条测试消息...");
            logger.info("   └─ 这些消息将被缓存到 batch 中");
            logger.info("   └─ 等待 delivery.timeout.ms={} 秒后超时\n", DELIVERY_TIMEOUT_MS / 1000);

            startTime[0] = System.currentTimeMillis();

            for (int i = 1; i <= 20; i++) {
                final int msgNum = i;
                ProducerRecord<String, String> record = new ProducerRecord<>(
                    TOPIC,
                    "key-" + i,
                    "value-" + i + "-" + System.currentTimeMillis()
                );

                producer.send(record, (metadata, exception) -> {
                    long elapsed = System.currentTimeMillis() - startTime[0];

                    if (exception != null) {
                        failureCount.incrementAndGet();
                        String errMsg = exception.getMessage();

                        logger.error("\n╔════ 消息 #{} 发送失败 ════╗", msgNum);
                        logger.error("║ 耗时: {}ms ({:.1f}秒)", elapsed, elapsed / 1000.0);
                        logger.error("║ 类型: {}", exception.getClass().getSimpleName());
                        logger.error("║ 消息: {}", errMsg);

                        // 检查目标错误
                        if (errMsg != null) {
                            if (errMsg.contains("batch creation") ||
                                errMsg.contains("since batch") ||
                                errMsg.contains("since last append")) {
                                targetErrorTriggered.set(true);
                                logger.info("╠════════════════════════════════════════╣");
                                logger.info("║ 🎯🎯🎯 成功触发 Batch Creation 超时！  ║");
                                logger.info("╚════════════════════════════════════════╝");
                            } else if (errMsg.contains("Expiring") &&
                                       (errMsg.contains("record") || errMsg.contains("batch"))) {
                                targetErrorTriggered.set(true);
                                logger.info("╠════════════════════════════════════════╣");
                                logger.info("║ 🎯 触发了 Record/Batch 过期超时！      ║");
                                logger.info("╚════════════════════════════════════════╝");
                            } else if (errMsg.contains("ms has passed")) {
                                targetErrorTriggered.set(true);
                                logger.info("╠════════════════════════════════════════╣");
                                logger.info("║ 🎯 触发了时间超时！                     ║");
                                logger.info("╚════════════════════════════════════════╝");
                            }
                        }

                        if (exception.getCause() != null) {
                            logger.error("║ 原因: {}", exception.getCause().getMessage());
                        }
                        logger.error("╚════════════════════════════════════════╝");
                    } else {
                        successCount.incrementAndGet();
                        logger.info("   ✅ 消息 #{} 成功 ({}ms)", msgNum, elapsed);
                    }

                    testLatch.countDown();
                });

                if (i % 10 == 0) {
                    logger.info("   └─ 已提交 {} 条消息到缓冲区", i);
                }
            }

            // 5. 等待超时
            logger.info("\n5. 等待消息处理...");
            logger.info("   └─ 预计等待时间: {} 秒 (delivery.timeout.ms)", DELIVERY_TIMEOUT_MS / 1000);

            // 倒计时线程
            Thread countdown = new Thread(() -> {
                int totalSeconds = DELIVERY_TIMEOUT_MS / 1000 + 15;
                for (int i = totalSeconds; i > 0; i--) {
                    if (testLatch.getCount() == 0) break;
                    if (i % 10 == 0 || i <= 5) {
                        logger.info("   ⏱ 剩余 {} 秒 | 成功: {} | 失败: {}",
                            i, successCount.get(), failureCount.get());
                    }
                    try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                }
            });
            countdown.setDaemon(true);
            countdown.start();

            boolean completed = testLatch.await(DELIVERY_TIMEOUT_MS + 20000, TimeUnit.MILLISECONDS);

            // 6. 输出结果
            logger.info("\n╔══════════════════════════════════════╗");
            logger.info("║           测试结果汇总                ║");
            logger.info("╠══════════════════════════════════════╣");
            logger.info("║ 总消息数:    20                      ║");
            logger.info("║ 成功发送:    {:2}                      ║", successCount.get());
            logger.info("║ 发送失败:    {:2}                      ║", failureCount.get());
            logger.info("║ 处理完成:    {}                     ║", completed ? "是" : "否");
            logger.info("║ 目标错误:    {}                     ║", targetErrorTriggered.get() ? "✅" : "❌");
            logger.info("╚══════════════════════════════════════╝");

            if (targetErrorTriggered.get()) {
                logger.info("\n🎉🎉🎉 成功复现 Batch Creation 超时！🎉🎉🎉");
            } else if (failureCount.get() > 0) {
                logger.info("\n📋 已触发超时错误，请检查上面的错误消息详情");
            }

        } catch (Exception e) {
            logger.error("测试异常: {}", e.getMessage(), e);
        } finally {
            // 7. 恢复容器
            if (containerPaused.get()) {
                logger.info("\n6. 恢复 Kafka 容器...");
                if (unpauseContainer()) {
                    logger.info("   ✅ 容器已恢复运行");
                } else {
                    logger.warn("   ⚠️ 恢复容器失败，请手动执行: docker unpause {}", CONTAINER_NAME);
                }
            }

            // 8. 关闭 Producer
            if (producer != null) {
                logger.info("\n7. 关闭 KafkaProducer...");
                try {
                    producer.close(5, TimeUnit.SECONDS);
                    logger.info("   ✅ 已关闭");
                } catch (Exception e) {
                    logger.debug("关闭异常", e);
                }
            }
        }
    }

    private static void ensureContainerRunning() {
        String status = execCommand("docker inspect -f '{{.State.Status}}' " + CONTAINER_NAME);
        if (status == null || !status.contains("running")) {
            logger.info("容器未运行，尝试启动...");
            execCommand("docker start " + CONTAINER_NAME);
            try { Thread.sleep(5000); } catch (InterruptedException e) {}
        }
    }

    private static boolean pauseContainer() {
        String result = execCommand("docker pause " + CONTAINER_NAME);
        return result != null;
    }

    private static boolean unpauseContainer() {
        String result = execCommand("docker unpause " + CONTAINER_NAME);
        return result != null;
    }

    private static String execCommand(String command) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"bash", "-c", command});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            int exitCode = process.waitFor();
            return exitCode == 0 ? output.toString().trim() : null;
        } catch (Exception e) {
            logger.debug("命令执行异常: {}", e.getMessage());
            return null;
        }
    }
}
