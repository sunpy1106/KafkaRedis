package com.example.kafka;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * 测试类：模拟 Kafka Producer 超时异常
 *
 * 目标异常: "120000 ms has passed since batch creation"
 * 或: "Failed to update metadata after 120000 ms"
 *
 * 关键配置: max.block.ms
 */
public class TestKafkaTimeoutException {
    private static final Logger logger = LoggerFactory.getLogger(TestKafkaTimeoutException.class);

    public static void main(String[] args) {
        logger.info("========== 开始测试 Kafka Producer 超时异常 ==========\n");

        // 场景1: 错误端口 + 5秒超时（推荐，快速验证）
        testInvalidPortQuick();

        // 场景2: 不可达主机 + 5秒超时
        testUnreachableHost();

        // 场景3: 真实 120 秒超时（可选，耗时长）
        // testRealTimeout120s();

        logger.info("\n========== 测试完成 ==========");
    }

    /**
     * 场景1: 错误端口 + 5秒超时（推荐）
     *
     * 配置:
     *  - bootstrap.servers = localhost:9999 (错误端口)
     *  - max.block.ms = 5000 (5秒超时)
     *
     * 预期: 5秒后抛出 TimeoutException
     */
    private static void testInvalidPortQuick() {
        logger.info("【场景1】错误端口 + 5秒超时");
        logger.info("配置: bootstrap.servers=localhost:9999, max.block.ms=5000\n");

        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9999");  // 错误端口
        props.put("max.block.ms", "5000");                 // 5秒超时
        props.put("request.timeout.ms", "3000");
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        KafkaProducer<String, String> producer = null;
        long startTime = 0;

        try {
            logger.info("1. 创建 KafkaProducer...");
            producer = new KafkaProducer<>(props);
            logger.info("   KafkaProducer 创建成功\n");

            logger.info("2. 准备发送消息...");
            ProducerRecord<String, String> record =
                new ProducerRecord<>("test-topic", "test-key", "test-value");

            startTime = System.currentTimeMillis();
            logger.info("   开始发送消息（将尝试获取 metadata）...\n");

            // 同步发送（会阻塞直到超时）
            producer.send(record).get();

            logger.warn("✅ 消息发送成功（不应该发生）");

        } catch (TimeoutException e) {
            long elapsed = System.currentTimeMillis() - startTime;

            logger.error("\n❌ 超时异常！耗时: {}ms", elapsed);
            logger.error("异常类型: {}", e.getClass().getName());
            logger.error("异常消息: {}", e.getMessage());

            if (e.getCause() != null) {
                logger.error("根本原因: {}", e.getCause().getClass().getName());
                logger.error("原因消息: {}", e.getCause().getMessage());
            }

            // 检查异常消息
            String message = e.getMessage();
            if (message != null) {
                if (message.contains("metadata") || message.contains("batch creation")) {
                    logger.info("\n🎯 成功触发 Kafka 超时异常！");
                    logger.info("✅ 异常类型: TimeoutException");
                    logger.info("✅ 等待时间: ~5 秒");
                    logger.info("✅ 原因: 无法连接到 Kafka broker");
                } else {
                    logger.info("\n✅ 成功触发 TimeoutException");
                    logger.info("   消息: {}", message);
                }
            }

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

    /**
     * 场景2: 不可达主机 + 5秒超时
     *
     * 配置:
     *  - bootstrap.servers = 192.0.2.1:9092 (不可达IP)
     *  - max.block.ms = 5000 (5秒超时)
     *
     * 预期: 5秒后抛出 TimeoutException
     */
    private static void testUnreachableHost() {
        logger.info("\n【场景2】不可达主机 + 5秒超时");
        logger.info("配置: bootstrap.servers=192.0.2.1:9092, max.block.ms=5000\n");

        Properties props = new Properties();
        props.put("bootstrap.servers", "192.0.2.1:9092");  // 不可达IP（文档保留IP）
        props.put("max.block.ms", "5000");                 // 5秒超时
        props.put("request.timeout.ms", "3000");
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        KafkaProducer<String, String> producer = null;
        long startTime = 0;

        try {
            logger.info("1. 创建 KafkaProducer...");
            producer = new KafkaProducer<>(props);
            logger.info("   KafkaProducer 创建成功\n");

            logger.info("2. 准备发送消息...");
            ProducerRecord<String, String> record =
                new ProducerRecord<>("test-topic", "test-key", "test-value");

            startTime = System.currentTimeMillis();
            logger.info("   开始发送消息（将尝试获取 metadata）...\n");

            // 同步发送（会阻塞直到超时）
            producer.send(record).get();

            logger.warn("✅ 消息发送成功（不应该发生）");

        } catch (TimeoutException e) {
            long elapsed = System.currentTimeMillis() - startTime;

            logger.error("\n❌ 超时异常！耗时: {}ms", elapsed);
            logger.error("异常类型: {}", e.getClass().getName());
            logger.error("异常消息: {}", e.getMessage());

            if (e.getCause() != null) {
                logger.error("根本原因: {}", e.getCause().getClass().getName());
                logger.error("原因消息: {}", e.getCause().getMessage());
            }

            String message = e.getMessage();
            if (message != null) {
                if (message.contains("metadata") || message.contains("batch creation")) {
                    logger.info("\n🎯 成功触发 Kafka 超时异常！");
                    logger.info("✅ 异常类型: TimeoutException");
                    logger.info("✅ 等待时间: ~5 秒");
                    logger.info("✅ 原因: 主机不可达");
                } else {
                    logger.info("\n✅ 成功触发 TimeoutException");
                    logger.info("   消息: {}", message);
                }
            }

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

    /**
     * 场景3: 真实 120 秒超时（可选，耗时长）
     *
     * 配置:
     *  - bootstrap.servers = localhost:9999 (错误端口)
     *  - max.block.ms = 120000 (120秒超时，模拟真实配置)
     *
     * 预期: 120秒后抛出 TimeoutException
     * 警告: 此测试需要 120+ 秒完成
     */
    private static void testRealTimeout120s() {
        logger.info("\n【场景3】真实 120 秒超时");
        logger.warn("⚠️  此测试需要 120+ 秒，请耐心等待\n");
        logger.info("配置: bootstrap.servers=localhost:9999, max.block.ms=120000\n");

        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9999");  // 错误端口
        props.put("max.block.ms", "120000");               // 120秒超时（真实配置）
        props.put("request.timeout.ms", "30000");
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());

        KafkaProducer<String, String> producer = null;
        long startTime = 0;

        try {
            logger.info("1. 创建 KafkaProducer...");
            producer = new KafkaProducer<>(props);
            logger.info("   KafkaProducer 创建成功\n");

            logger.info("2. 准备发送消息...");
            ProducerRecord<String, String> record =
                new ProducerRecord<>("test-topic", "test-key", "test-value");

            startTime = System.currentTimeMillis();
            logger.info("   开始发送消息（需要等待 120 秒）...");
            logger.info("   ⏰ 倒计时开始...\n");

            // 启动倒计时线程
            Thread countdownThread = new Thread(() -> {
                for (int i = 120; i > 0; i--) {
                    if (i % 10 == 0 || i <= 5) {
                        logger.info("   还需等待: {} 秒...", i);
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            });
            countdownThread.setDaemon(true);
            countdownThread.start();

            // 同步发送（会阻塞直到超时）
            producer.send(record).get();

            logger.warn("✅ 消息发送成功（不应该发生）");

        } catch (TimeoutException e) {
            long elapsed = System.currentTimeMillis() - startTime;

            logger.error("\n❌ 超时异常！耗时: {}ms ({}秒)", elapsed, elapsed / 1000);
            logger.error("异常类型: {}", e.getClass().getName());
            logger.error("异常消息: {}", e.getMessage());

            if (e.getCause() != null) {
                logger.error("根本原因: {}", e.getCause().getClass().getName());
                logger.error("原因消息: {}", e.getCause().getMessage());
            }

            String message = e.getMessage();
            if (message != null) {
                if (message.contains("120000") || message.contains("120 seconds")) {
                    logger.info("\n🎯 成功触发真实的 120 秒超时异常！");
                    logger.info("✅ 异常类型: TimeoutException");
                    logger.info("✅ 等待时间: ~120 秒");
                    logger.info("✅ 异常消息包含: 120000 ms");
                } else if (message.contains("metadata") || message.contains("batch creation")) {
                    logger.info("\n🎯 成功触发 Kafka 超时异常！");
                    logger.info("✅ 异常类型: TimeoutException");
                    logger.info("✅ 等待时间: ~120 秒");
                } else {
                    logger.info("\n✅ 成功触发 TimeoutException");
                    logger.info("   消息: {}", message);
                }
            }

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
