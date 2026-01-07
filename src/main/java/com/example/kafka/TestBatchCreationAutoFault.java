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

/**
 * 测试类：自动化故障注入触发 "batch creation" 超时
 *
 * 策略:
 *  1. 发送预热消息成功（确保 metadata 缓存）
 *  2. 自动在 Kafka 容器内注入网络故障（tc 延迟或 iptables 丢包）
 *  3. 发送测试消息
 *  4. 等待 delivery.timeout.ms 超时
 *  5. 自动清理故障
 *
 * 目标错误: "X ms has passed since batch creation"
 *
 * 使用方法:
 *   mvn exec:java -Dexec.mainClass="com.example.kafka.TestBatchCreationAutoFault"
 */
public class TestBatchCreationAutoFault {
    private static final Logger logger = LoggerFactory.getLogger(TestBatchCreationAutoFault.class);

    // 配置
    private static final String BOOTSTRAP_SERVERS = "localhost:19092";
    private static final String TOPIC = "test-topic";
    private static final String CONTAINER_NAME = "kafka-broker";
    private static final int DELIVERY_TIMEOUT_MS = 30000;  // 30秒
    private static final int REQUEST_TIMEOUT_MS = 10000;   // 10秒

    public static void main(String[] args) {
        logger.info("========== 自动化故障注入测试 Batch Creation 超时 ==========\n");

        // 选择故障注入方法
        String method = args.length > 0 ? args[0] : "tc";

        switch (method) {
            case "tc":
                testWithTcDelay();
                break;
            case "iptables":
                testWithIptablesDrop();
                break;
            case "combined":
                testWithCombinedFault();
                break;
            default:
                logger.info("可用方法: tc, iptables, combined");
                logger.info("默认使用 tc 方法\n");
                testWithTcDelay();
        }

        logger.info("\n========== 测试完成 ==========");
    }

    /**
     * 方法1: 使用 tc netem 制造网络延迟
     */
    private static void testWithTcDelay() {
        logger.info("【方法1】使用 tc netem 制造网络延迟");
        logger.info("策略: 在容器内添加 60 秒网络延迟，超过 delivery.timeout.ms\n");

        // 检查 tc 是否可用
        if (!checkTcAvailable()) {
            logger.warn("tc 不可用，尝试安装...");
            execCommand("docker exec " + CONTAINER_NAME + " bash -c 'yum install -y iproute-tc 2>/dev/null || apt-get update && apt-get install -y iproute2 2>/dev/null || true'");
        }

        runTest(() -> {
            // 添加 60 秒延迟到出站流量
            String addDelay = String.format(
                "docker exec %s tc qdisc add dev eth0 root netem delay 60000ms 2>/dev/null || " +
                "docker exec %s tc qdisc change dev eth0 root netem delay 60000ms",
                CONTAINER_NAME, CONTAINER_NAME);
            return execCommand(addDelay) != null;
        }, () -> {
            // 清理 tc 规则
            String removeDelay = String.format(
                "docker exec %s tc qdisc del dev eth0 root 2>/dev/null || true",
                CONTAINER_NAME);
            execCommand(removeDelay);
        }, "tc-delay");
    }

    /**
     * 方法2: 使用 iptables DROP 丢弃数据包
     */
    private static void testWithIptablesDrop() {
        logger.info("【方法2】使用 iptables DROP 丢弃数据包");
        logger.info("策略: 在容器内丢弃所有入站 TCP 数据包\n");

        runTest(() -> {
            // 丢弃到 9092 端口的入站流量
            String addRule = String.format(
                "docker exec %s iptables -A INPUT -p tcp --dport 9092 -j DROP 2>/dev/null || true",
                CONTAINER_NAME);
            return execCommand(addRule) != null;
        }, () -> {
            // 清理 iptables 规则
            String removeRule = String.format(
                "docker exec %s iptables -D INPUT -p tcp --dport 9092 -j DROP 2>/dev/null || true",
                CONTAINER_NAME);
            execCommand(removeRule);
        }, "iptables-drop");
    }

    /**
     * 方法3: 组合故障 - tc 延迟 + 部分丢包
     */
    private static void testWithCombinedFault() {
        logger.info("【方法3】组合故障 - tc 延迟 + 部分丢包");
        logger.info("策略: 添加 30 秒延迟 + 50% 丢包率\n");

        runTest(() -> {
            // 添加延迟和丢包
            String addFault = String.format(
                "docker exec %s tc qdisc add dev eth0 root netem delay 30000ms loss 50%% 2>/dev/null || " +
                "docker exec %s tc qdisc change dev eth0 root netem delay 30000ms loss 50%%",
                CONTAINER_NAME, CONTAINER_NAME);
            return execCommand(addFault) != null;
        }, () -> {
            // 清理
            String removeFault = String.format(
                "docker exec %s tc qdisc del dev eth0 root 2>/dev/null || true",
                CONTAINER_NAME);
            execCommand(removeFault);
        }, "combined-fault");
    }

    /**
     * 通用测试框架
     */
    private static void runTest(FaultInjector injector, Runnable cleanup, String testName) {
        KafkaProducer<String, String> producer = null;
        final CountDownLatch warmupLatch = new CountDownLatch(1);
        final CountDownLatch testLatch = new CountDownLatch(10);
        final long[] startTime = {0};
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);

        try {
            // 1. 创建 Producer
            Properties props = createProducerConfig();
            logger.info("1. 创建 KafkaProducer...");
            logger.info("   配置: bootstrap.servers={}", BOOTSTRAP_SERVERS);
            logger.info("   配置: delivery.timeout.ms={}", DELIVERY_TIMEOUT_MS);
            logger.info("   配置: request.timeout.ms={}", REQUEST_TIMEOUT_MS);
            producer = new KafkaProducer<>(props);
            logger.info("   ✅ KafkaProducer 创建成功\n");

            // 2. 发送预热消息
            logger.info("2. 发送预热消息（缓存 metadata）...");
            final KafkaProducer<String, String> finalProducer = producer;

            producer.send(new ProducerRecord<>(TOPIC, "warmup-key", "warmup-value-" + System.currentTimeMillis()),
                (metadata, exception) -> {
                    if (exception != null) {
                        logger.error("   ❌ 预热消息发送失败: {}", exception.getMessage());
                    } else {
                        logger.info("   ✅ 预热消息发送成功 - Partition: {}, Offset: {}",
                            metadata.partition(), metadata.offset());
                    }
                    warmupLatch.countDown();
                });

            if (!warmupLatch.await(15, TimeUnit.SECONDS)) {
                logger.error("   ❌ 预热消息超时，测试终止");
                return;
            }

            // 确保消息完全发送
            producer.flush();
            Thread.sleep(1000);

            // 3. 注入故障
            logger.info("\n3. 注入故障 [{}]...", testName);
            boolean injected = injector.inject();
            if (injected) {
                logger.info("   ✅ 故障注入成功\n");
            } else {
                logger.warn("   ⚠️ 故障注入可能未完全成功，继续测试\n");
            }

            // 等待故障生效
            Thread.sleep(2000);

            // 4. 发送测试消息
            logger.info("4. 发送 10 条测试消息...");
            startTime[0] = System.currentTimeMillis();

            for (int i = 1; i <= 10; i++) {
                final int msgNum = i;
                ProducerRecord<String, String> record =
                    new ProducerRecord<>(TOPIC, "key-" + i, "test-value-" + i + "-" + System.currentTimeMillis());

                producer.send(record, (metadata, exception) -> {
                    long elapsed = System.currentTimeMillis() - startTime[0];

                    if (exception != null) {
                        failureCount.incrementAndGet();
                        logger.error("\n❌ 消息 #{} 发送失败！耗时: {}ms ({:.1f}秒)",
                            msgNum, elapsed, elapsed / 1000.0);
                        logger.error("   异常类型: {}", exception.getClass().getName());
                        logger.error("   异常消息: {}", exception.getMessage());

                        // 检查是否是目标错误
                        checkTargetError(exception.getMessage());

                        if (exception.getCause() != null) {
                            logger.error("   根本原因: {} - {}",
                                exception.getCause().getClass().getSimpleName(),
                                exception.getCause().getMessage());
                        }
                    } else {
                        successCount.incrementAndGet();
                        logger.info("   ✅ 消息 #{} 发送成功 ({}ms) - Partition: {}, Offset: {}",
                            msgNum, elapsed, metadata.partition(), metadata.offset());
                    }

                    testLatch.countDown();
                });

                if (i % 5 == 0) {
                    logger.info("   已提交 {} 条消息...", i);
                }
            }

            // 5. 等待结果
            logger.info("\n5. 等待 delivery.timeout.ms={} 秒超时...", DELIVERY_TIMEOUT_MS / 1000);

            // 启动倒计时线程
            Thread countdown = new Thread(() -> {
                int seconds = DELIVERY_TIMEOUT_MS / 1000 + 10;
                for (int i = seconds; i > 0; i--) {
                    if (testLatch.getCount() == 0) break;
                    if (i % 10 == 0 || i <= 5) {
                        logger.info("   ⏱ 还需等待 {} 秒... (成功:{}, 失败:{})",
                            i, successCount.get(), failureCount.get());
                    }
                    try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                }
            });
            countdown.setDaemon(true);
            countdown.start();

            boolean completed = testLatch.await(DELIVERY_TIMEOUT_MS + 15000, TimeUnit.MILLISECONDS);

            // 6. 输出结果
            logger.info("\n========== 测试结果 [{}] ==========", testName);
            logger.info("   成功: {} 条", successCount.get());
            logger.info("   失败: {} 条", failureCount.get());
            logger.info("   完成: {}", completed ? "是" : "否（超时）");

            if (failureCount.get() > 0) {
                logger.info("\n   📋 请检查上面的错误消息是否包含:");
                logger.info("      - 'batch creation'");
                logger.info("      - 'since batch'");
                logger.info("      - 'Expiring X record(s)'");
            }
            logger.info("==========================================\n");

        } catch (Exception e) {
            logger.error("测试异常: {}", e.getMessage(), e);
        } finally {
            // 7. 清理故障
            logger.info("6. 清理故障...");
            try {
                cleanup.run();
                logger.info("   ✅ 故障已清理\n");
            } catch (Exception e) {
                logger.warn("   ⚠️ 清理时发生异常: {}", e.getMessage());
            }

            // 8. 关闭 Producer
            if (producer != null) {
                logger.info("7. 关闭 KafkaProducer...");
                try {
                    producer.close(5, TimeUnit.SECONDS);
                    logger.info("   ✅ KafkaProducer 已关闭");
                } catch (Exception e) {
                    logger.debug("关闭时异常", e);
                }
            }
        }
    }

    /**
     * 创建 Producer 配置
     */
    private static Properties createProducerConfig() {
        Properties props = new Properties();
        props.put("bootstrap.servers", BOOTSTRAP_SERVERS);
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        // 关键超时配置
        props.put("acks", "all");  // 需要所有副本确认
        props.put("delivery.timeout.ms", String.valueOf(DELIVERY_TIMEOUT_MS));
        props.put("request.timeout.ms", String.valueOf(REQUEST_TIMEOUT_MS));
        props.put("max.block.ms", "10000");  // metadata 获取超时

        // 批量配置
        props.put("linger.ms", "100");  // 100ms 延迟让消息积累成 batch
        props.put("batch.size", "1024");  // 小 batch size

        // 重试配置
        props.put("retries", "3");
        props.put("retry.backoff.ms", "500");

        return props;
    }

    /**
     * 检查是否是目标错误
     */
    private static void checkTargetError(String message) {
        if (message == null) return;

        if (message.contains("batch creation") ||
            message.contains("since batch") ||
            message.contains("since last append")) {
            logger.info("\n🎯🎯🎯 成功触发 Batch Creation 超时！🎯🎯🎯");
            logger.info("✅✅✅ 目标错误消息: {}", message);
        } else if (message.contains("Expiring") &&
                   (message.contains("record") || message.contains("batch"))) {
            logger.info("\n🎯🎯🎯 触发了 Batch/Record 过期！🎯🎯🎯");
            logger.info("✅✅✅ 这是 batch creation 超时的变体！");
            logger.info("✅ 完整消息: {}", message);
        } else if (message.contains(String.valueOf(DELIVERY_TIMEOUT_MS)) ||
                   message.contains("ms has passed")) {
            logger.info("\n🎯 触发了时间相关超时！");
            logger.info("✅ 消息: {}", message);
        }
    }

    /**
     * 检查 tc 是否可用
     */
    private static boolean checkTcAvailable() {
        String result = execCommand("docker exec " + CONTAINER_NAME + " which tc 2>/dev/null");
        return result != null && result.contains("tc");
    }

    /**
     * 执行命令
     */
    private static String execCommand(String command) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"bash", "-c", command});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            while ((line = errorReader.readLine()) != null) {
                logger.debug("命令错误输出: {}", line);
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.debug("命令退出码: {}", exitCode);
            }
            return output.toString().trim();
        } catch (Exception e) {
            logger.debug("执行命令异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 故障注入接口
     */
    @FunctionalInterface
    interface FaultInjector {
        boolean inject();
    }
}
