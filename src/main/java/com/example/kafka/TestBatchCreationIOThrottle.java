package com.example.kafka;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试类：使用 cgroup I/O 限速触发 "batch creation" 超时
 *
 * 原理:
 *  - 使用 cgroup v2 的 io.max 限制 Kafka 容器的磁盘写入速度
 *  - 极低的写入速度 (如 1KB/s) 会导致 fsync 阻塞
 *  - 消息在 batch 中等待超过 delivery.timeout.ms 后超时
 *
 * 目标错误: "X ms has passed since batch creation"
 *
 * 使用方法:
 *   sudo mvn exec:java -Dexec.mainClass="com.example.kafka.TestBatchCreationIOThrottle"
 *
 * 注意: 需要 root 权限来修改 cgroup 设置
 */
public class TestBatchCreationIOThrottle {
    private static final Logger logger = LoggerFactory.getLogger(TestBatchCreationIOThrottle.class);

    private static final String BOOTSTRAP_SERVERS = "localhost:19092";
    private static final String TOPIC = "test-topic";
    private static final String CONTAINER_NAME = "kafka-broker";

    // 设备号 (sda = 8:0)
    private static final String DEVICE_MAJOR_MINOR = "8:0";

    // I/O 限速: 1KB/s 写入速度 (极慢)
    private static final long WRITE_BPS_LIMIT = 1024;  // 1 KB/s

    // 超时配置
    private static final int DELIVERY_TIMEOUT_MS = 30000;  // 30秒
    private static final int REQUEST_TIMEOUT_MS = 10000;   // 10秒

    public static void main(String[] args) {
        logger.info("╔══════════════════════════════════════════════════════════════╗");
        logger.info("║   Cgroup I/O 限速方法触发 Batch Creation 超时                  ║");
        logger.info("╚══════════════════════════════════════════════════════════════╝\n");

        // 检查是否有 root 权限
        if (!checkRootPrivilege()) {
            logger.error("❌ 需要 root 权限来修改 cgroup 设置");
            logger.error("   请使用: sudo mvn exec:java -Dexec.mainClass=\"com.example.kafka.TestBatchCreationIOThrottle\"");
            return;
        }

        runTest();

        logger.info("\n========== 测试完成 ==========");
    }

    private static void runTest() {
        String cgroupPath = findCgroupPath();
        if (cgroupPath == null) {
            logger.error("❌ 无法找到 Kafka 容器的 cgroup 路径");
            return;
        }

        logger.info("找到 cgroup 路径: {}", cgroupPath);
        String ioMaxPath = cgroupPath + "/io.max";

        KafkaProducer<String, String> producer = null;
        final AtomicBoolean ioLimited = new AtomicBoolean(false);
        final CountDownLatch warmupLatch = new CountDownLatch(1);
        final CountDownLatch testLatch = new CountDownLatch(10);
        final long[] startTime = {0};
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);
        final AtomicBoolean targetErrorTriggered = new AtomicBoolean(false);

        try {
            // 1. 创建 Producer
            Properties props = new Properties();
            props.put("bootstrap.servers", BOOTSTRAP_SERVERS);
            props.put("key.serializer", StringSerializer.class.getName());
            props.put("value.serializer", StringSerializer.class.getName());
            props.put("acks", "all");
            props.put("delivery.timeout.ms", String.valueOf(DELIVERY_TIMEOUT_MS));
            props.put("request.timeout.ms", String.valueOf(REQUEST_TIMEOUT_MS));
            props.put("max.block.ms", "10000");
            props.put("linger.ms", "500");
            props.put("batch.size", "16384");
            props.put("retries", "3");
            props.put("retry.backoff.ms", "1000");

            logger.info("1. 创建 KafkaProducer...");
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
                logger.error("   ❌ 预热超时");
                return;
            }
            producer.flush();
            logger.info("   ✅ Metadata 已缓存\n");

            Thread.sleep(2000);

            // 3. 设置 I/O 限速
            logger.info("3. 设置 I/O 限速 (写入速度: {} bytes/s)...", WRITE_BPS_LIMIT);
            if (setIOLimit(ioMaxPath, WRITE_BPS_LIMIT)) {
                ioLimited.set(true);
                logger.info("   ✅ I/O 限速已设置");
                logger.info("   📋 当前限制: {} wbps={}", DEVICE_MAJOR_MINOR, WRITE_BPS_LIMIT);

                // 显示当前设置
                String currentLimit = readFile(ioMaxPath);
                logger.info("   📋 io.max 内容: {}\n", currentLimit.trim());
            } else {
                logger.error("   ❌ 设置 I/O 限速失败");
                return;
            }

            // 等待限速生效
            Thread.sleep(3000);

            // 4. 发送测试消息
            logger.info("4. 发送 10 条测试消息...");
            logger.info("   └─ 极低的 I/O 速度会导致 fsync 阻塞");
            logger.info("   └─ 等待 delivery.timeout.ms={} 秒后超时\n", DELIVERY_TIMEOUT_MS / 1000);

            startTime[0] = System.currentTimeMillis();

            for (int i = 1; i <= 10; i++) {
                final int msgNum = i;
                // 发送较大的消息以增加 I/O 压力
                String largeValue = generateLargeMessage(1024);  // 1KB 消息
                ProducerRecord<String, String> record = new ProducerRecord<>(
                    TOPIC, "key-" + i, largeValue
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

                        if (errMsg != null && (errMsg.contains("batch creation") ||
                            errMsg.contains("since batch") ||
                            errMsg.contains("Expiring"))) {
                            targetErrorTriggered.set(true);
                            logger.info("╠════════════════════════════════════════╣");
                            logger.info("║ 🎯🎯🎯 成功触发 Batch Creation 超时！  ║");
                            logger.info("╚════════════════════════════════════════╝");
                        }
                        logger.error("╚════════════════════════════════════════╝");
                    } else {
                        successCount.incrementAndGet();
                        logger.info("   ✅ 消息 #{} 成功 ({}ms)", msgNum, elapsed);
                    }

                    testLatch.countDown();
                });

                logger.info("   └─ 已提交消息 #{}", i);
            }

            // 5. 等待超时
            logger.info("\n5. 等待消息处理...");

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
            logger.info("║ 成功发送:    {:2}                      ║", successCount.get());
            logger.info("║ 发送失败:    {:2}                      ║", failureCount.get());
            logger.info("║ 目标错误:    {}                     ║", targetErrorTriggered.get() ? "✅" : "❌");
            logger.info("╚══════════════════════════════════════╝");

            if (targetErrorTriggered.get()) {
                logger.info("\n🎉 使用 I/O 限速成功复现 Batch Creation 超时！");
            }

        } catch (Exception e) {
            logger.error("测试异常: {}", e.getMessage(), e);
        } finally {
            // 7. 清理 I/O 限速
            if (ioLimited.get()) {
                logger.info("\n6. 清理 I/O 限速...");
                if (clearIOLimit(ioMaxPath)) {
                    logger.info("   ✅ I/O 限速已清理");
                } else {
                    logger.warn("   ⚠️ 清理失败，请手动执行:");
                    logger.warn("   echo '' > {}", ioMaxPath);
                }
            }

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

    private static boolean checkRootPrivilege() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"id", "-u"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String uid = reader.readLine();
            return "0".equals(uid);
        } catch (Exception e) {
            return false;
        }
    }

    private static String findCgroupPath() {
        try {
            // 获取容器 ID
            Process p = Runtime.getRuntime().exec(new String[]{
                "docker", "inspect", CONTAINER_NAME, "--format", "{{.Id}}"
            });
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String containerId = reader.readLine();
            if (containerId == null || containerId.isEmpty()) {
                return null;
            }

            // 查找 cgroup 路径
            String basePath = "/sys/fs/cgroup/system.slice";
            File dir = new File(basePath);
            if (dir.exists() && dir.isDirectory()) {
                for (File f : dir.listFiles()) {
                    if (f.getName().contains(containerId.substring(0, 12))) {
                        return f.getAbsolutePath();
                    }
                }
            }

            // 尝试其他路径
            Process findP = Runtime.getRuntime().exec(new String[]{
                "find", "/sys/fs/cgroup", "-name", "*" + containerId.substring(0, 12) + "*", "-type", "d"
            });
            BufferedReader findReader = new BufferedReader(new InputStreamReader(findP.getInputStream()));
            return findReader.readLine();

        } catch (Exception e) {
            logger.error("查找 cgroup 路径失败: {}", e.getMessage());
            return null;
        }
    }

    private static boolean setIOLimit(String ioMaxPath, long writeBps) {
        try {
            String limit = DEVICE_MAJOR_MINOR + " wbps=" + writeBps;
            Files.write(Paths.get(ioMaxPath), limit.getBytes());
            return true;
        } catch (Exception e) {
            logger.error("设置 I/O 限速失败: {}", e.getMessage());
            return false;
        }
    }

    private static boolean clearIOLimit(String ioMaxPath) {
        try {
            Files.write(Paths.get(ioMaxPath), "".getBytes());
            return true;
        } catch (Exception e) {
            logger.error("清理 I/O 限速失败: {}", e.getMessage());
            return false;
        }
    }

    private static String readFile(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)));
        } catch (Exception e) {
            return "无法读取";
        }
    }

    private static String generateLargeMessage(int size) {
        StringBuilder sb = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        return sb.toString();
    }
}
