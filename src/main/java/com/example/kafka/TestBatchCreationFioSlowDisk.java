package com.example.kafka;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试类：使用 fio 制造磁盘 I/O 压力触发 "batch creation" 超时
 *
 * 原理:
 *  - 使用 fio 在 Kafka 数据目录制造大量同步写入压力
 *  - 磁盘 I/O 队列饱和，导致 Kafka 的 fsync 阻塞
 *  - 消息在 batch 中等待超过 delivery.timeout.ms 后超时
 *
 * 目标错误: "X ms has passed since batch creation"
 *
 * 使用方法:
 *   mvn exec:java -Dexec.mainClass="com.example.kafka.TestBatchCreationFioSlowDisk"
 */
public class TestBatchCreationFioSlowDisk {
    private static final Logger logger = LoggerFactory.getLogger(TestBatchCreationFioSlowDisk.class);

    private static final String BOOTSTRAP_SERVERS = "localhost:19092";
    private static final String TOPIC = "test-topic";

    // Kafka 数据卷路径
    private static final String KAFKA_DATA_PATH = "/var/lib/docker/volumes/kafkaredis_kafka_data/_data";

    // 超时配置
    private static final int DELIVERY_TIMEOUT_MS = 30000;  // 30秒
    private static final int REQUEST_TIMEOUT_MS = 10000;   // 10秒

    // fio 进程
    private static Process fioProcess = null;

    public static void main(String[] args) {
        logger.info("╔══════════════════════════════════════════════════════════════╗");
        logger.info("║   FIO 磁盘压力方法触发 Batch Creation 超时                     ║");
        logger.info("╚══════════════════════════════════════════════════════════════╝\n");

        // 检查 fio 是否可用
        if (!checkFioAvailable()) {
            logger.error("❌ fio 未安装，请先安装: yum install -y fio 或 apt install -y fio");
            return;
        }

        runTest();

        logger.info("\n========== 测试完成 ==========");
    }

    private static void runTest() {
        KafkaProducer<String, String> producer = null;
        final AtomicBoolean fioRunning = new AtomicBoolean(false);
        final CountDownLatch warmupLatch = new CountDownLatch(1);
        final CountDownLatch testLatch = new CountDownLatch(20);
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
            props.put("max.block.ms", "15000");
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

            // 3. 启动 fio 制造 I/O 压力
            logger.info("3. 启动 fio 制造磁盘 I/O 压力...");
            logger.info("   └─ 目标目录: {}", KAFKA_DATA_PATH);

            if (startFioStress()) {
                fioRunning.set(true);
                logger.info("   ✅ fio 已启动");
                logger.info("   📋 参数: 8个并发作业, 4K随机写, fsync强制刷盘\n");
            } else {
                logger.error("   ❌ 启动 fio 失败");
                return;
            }

            // 等待 I/O 压力生效
            logger.info("   ⏳ 等待 5 秒让 I/O 压力充分生效...\n");
            Thread.sleep(5000);

            // 4. 发送测试消息
            logger.info("4. 发送 20 条测试消息...");
            logger.info("   └─ 磁盘 I/O 饱和会导致 fsync 阻塞");
            logger.info("   └─ 等待 delivery.timeout.ms={} 秒后超时\n", DELIVERY_TIMEOUT_MS / 1000);

            startTime[0] = System.currentTimeMillis();

            for (int i = 1; i <= 20; i++) {
                final int msgNum = i;
                String value = "fio-test-value-" + i + "-" + System.currentTimeMillis();
                ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, "key-" + i, value);

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

                if (i % 10 == 0) {
                    logger.info("   └─ 已提交 {} 条消息", i);
                }
            }

            // 5. 等待结果
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
                logger.info("\n🎉 使用 fio I/O 压力成功复现 Batch Creation 超时！");
            } else if (successCount.get() == 20) {
                logger.info("\n⚠️ 所有消息都成功了，可能需要增加 fio 压力");
                logger.info("   建议: 增加 numjobs 或 iodepth 参数");
            }

        } catch (Exception e) {
            logger.error("测试异常: {}", e.getMessage(), e);
        } finally {
            // 7. 停止 fio
            if (fioRunning.get()) {
                logger.info("\n6. 停止 fio...");
                stopFioStress();
                logger.info("   ✅ fio 已停止");

                // 清理测试文件
                cleanupFioFiles();
                logger.info("   ✅ 测试文件已清理");
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

    private static boolean checkFioAvailable() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"which", "fio"});
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean startFioStress() {
        try {
            // 构建 fio 命令 - 在 Kafka 数据目录制造极大的同步写入压力
            String[] cmd = {
                "fio",
                "--name=kafka-stress",
                "--directory=" + KAFKA_DATA_PATH,
                "--rw=randwrite",           // 随机写
                "--bs=4k",                  // 4K 块大小
                "--size=500M",              // 每个作业写 500MB
                "--numjobs=8",              // 8 个并发作业
                "--iodepth=64",             // I/O 队列深度 64
                "--direct=1",               // 绕过缓存
                "--fsync=1",                // 每次写入都 fsync
                "--group_reporting",
                "--time_based",
                "--runtime=120"             // 运行 120 秒
            };

            logger.info("   执行: fio --name=kafka-stress --directory={} --rw=randwrite --bs=4k --numjobs=8 --iodepth=64 --direct=1 --fsync=1", KAFKA_DATA_PATH);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            fioProcess = pb.start();

            // 启动一个线程读取 fio 输出（避免阻塞）
            Thread outputReader = new Thread(() -> {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(fioProcess.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains("IOPS") || line.contains("BW=")) {
                            logger.debug("fio: {}", line);
                        }
                    }
                } catch (Exception e) {
                    // ignore
                }
            });
            outputReader.setDaemon(true);
            outputReader.start();

            // 等待 fio 启动
            Thread.sleep(2000);
            return fioProcess.isAlive();

        } catch (Exception e) {
            logger.error("启动 fio 失败: {}", e.getMessage());
            return false;
        }
    }

    private static void stopFioStress() {
        try {
            if (fioProcess != null && fioProcess.isAlive()) {
                fioProcess.destroy();
                fioProcess.waitFor(5, TimeUnit.SECONDS);
                if (fioProcess.isAlive()) {
                    fioProcess.destroyForcibly();
                }
            }

            // 同时杀死所有 fio 进程
            Runtime.getRuntime().exec(new String[]{"pkill", "-9", "fio"}).waitFor();

        } catch (Exception e) {
            logger.debug("停止 fio 异常: {}", e.getMessage());
        }
    }

    private static void cleanupFioFiles() {
        try {
            // 删除 fio 创建的测试文件
            String[] cmd = {"bash", "-c", "rm -f " + KAFKA_DATA_PATH + "/kafka-stress.*.0"};
            Runtime.getRuntime().exec(cmd).waitFor();
        } catch (Exception e) {
            logger.debug("清理文件异常: {}", e.getMessage());
        }
    }
}
