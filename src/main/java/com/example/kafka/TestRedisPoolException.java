package com.example.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试类：模拟Redis连接池耗尽异常
 * 场景：小连接池 + 多线程并发请求 + 长时间占用连接
 */
public class TestRedisPoolException {
    private static final Logger logger = LoggerFactory.getLogger(TestRedisPoolException.class);
    private static AtomicInteger successCount = new AtomicInteger(0);
    private static AtomicInteger failCount = new AtomicInteger(0);

    public static void main(String[] args) {
        logger.info("========== 开始测试Redis连接池耗尽异常 ==========");

        // 初始化Jedis连接池
        JedisPool jedisPool = initJedisPool();

        if (jedisPool == null) {
            logger.error("连接池初始化失败");
            return;
        }

        // 创建25个线程，但连接池只有16个连接
        int threadCount = 25;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        logger.info("创建 {} 个线程，但连接池最大连接数为 16", threadCount);
        logger.info("预期：前16个线程获取连接成功，后9个线程在20ms后超时失败\n");

        // 启动多个线程同时获取连接
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i + 1;
            new Thread(() -> {
                try {
                    // 等待所有线程就绪，然后同时开始
                    startLatch.await();

                    logger.info("[Thread-{}] 开始尝试获取Redis连接...", threadId);
                    long startTime = System.currentTimeMillis();

                    try {
                        // 获取连接（这里可能会阻塞等待或抛出异常）
                        Jedis jedis = jedisPool.getResource();
                        long getTime = System.currentTimeMillis() - startTime;

                        logger.info("[Thread-{}] ✅ 成功获取连接！耗时: {}ms", threadId, getTime);
                        successCount.incrementAndGet();

                        // 模拟长时间占用连接（5秒）
                        logger.info("[Thread-{}] 开始占用连接5秒...", threadId);
                        jedis.ping(); // 确认连接可用
                        Thread.sleep(5000);

                        // 释放连接
                        jedis.close();
                        logger.info("[Thread-{}] 连接已释放", threadId);

                    } catch (JedisConnectionException e) {
                        long failTime = System.currentTimeMillis() - startTime;
                        logger.error("[Thread-{}] ❌ 获取连接失败！耗时: {}ms", threadId, failTime);
                        logger.error("[Thread-{}] 异常类型: {}", threadId, e.getClass().getSimpleName());
                        logger.error("[Thread-{}] 异常消息: {}", threadId, e.getMessage());
                        failCount.incrementAndGet();
                    } catch (Exception e) {
                        logger.error("[Thread-{}] 发生其他异常: {}", threadId, e.getMessage(), e);
                        failCount.incrementAndGet();
                    }

                } catch (InterruptedException e) {
                    logger.error("[Thread-{}] 线程被中断", threadId);
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }, "RedisTest-" + threadId).start();
        }

        // 所有线程就绪后，同时开始
        try {
            Thread.sleep(500); // 让所有线程都准备好
            logger.info("\n⏰ 所有线程就绪，开始并发执行！\n");
            startLatch.countDown(); // 释放所有线程

            // 等待所有线程完成
            endLatch.await();
            Thread.sleep(1000); // 等待日志输出

            // 输出统计结果
            logger.info("\n========== 测试结果统计 ==========");
            logger.info("✅ 成功获取连接的线程数: {}", successCount.get());
            logger.info("❌ 获取连接失败的线程数: {}", failCount.get());
            logger.info("总线程数: {}", threadCount);
            logger.info("连接池最大连接数: 16");
            logger.info("连接池最大等待时间: 20ms (极短超时)");

            if (failCount.get() > 0) {
                logger.info("\n🎯 成功触发 RedisConnectionException 异常！");
                logger.info("说明：连接池耗尽时，后续请求会等待 maxWaitMillis 后抛出异常");
            } else {
                logger.warn("\n⚠️  未能触发异常，可能需要调整配置或线程数");
            }

            logger.info("========== 测试完成 ==========\n");

        } catch (InterruptedException e) {
            logger.error("测试过程被中断", e);
            Thread.currentThread().interrupt();
        } finally {
            // 关闭连接池
            if (jedisPool != null && !jedisPool.isClosed()) {
                jedisPool.close();
                logger.info("连接池已关闭");
            }
        }
    }

    /**
     * 初始化Jedis连接池
     */
    private static JedisPool initJedisPool() {
        Properties props = new Properties();
        try (InputStream input = TestRedisPoolException.class.getClassLoader()
                .getResourceAsStream("application.properties")) {

            if (input == null) {
                logger.error("无法找到配置文件 application.properties");
                return null;
            }
            props.load(input);

            String host = props.getProperty("redis.host", "localhost");
            int port = Integer.parseInt(props.getProperty("redis.port", "6379"));
            int timeout = Integer.parseInt(props.getProperty("redis.timeout", "3000"));

            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(Integer.parseInt(props.getProperty("redis.pool.maxTotal", "16")));
            poolConfig.setMaxIdle(Integer.parseInt(props.getProperty("redis.pool.maxIdle", "16")));
            poolConfig.setMinIdle(Integer.parseInt(props.getProperty("redis.pool.minIdle", "4")));
            poolConfig.setMaxWaitMillis(Long.parseLong(props.getProperty("redis.pool.maxWaitMillis", "20")));
            poolConfig.setTestOnBorrow(Boolean.parseBoolean(props.getProperty("redis.pool.testOnBorrow", "true")));

            JedisPool jedisPool = new JedisPool(poolConfig, host, port, timeout);

            logger.info("Redis连接池配置:");
            logger.info("  - 地址: {}:{}", host, port);
            logger.info("  - maxTotal: {}", poolConfig.getMaxTotal());
            logger.info("  - maxIdle: {}", poolConfig.getMaxIdle());
            logger.info("  - minIdle: {}", poolConfig.getMinIdle());
            logger.info("  - maxWaitMillis: {}ms", poolConfig.getMaxWaitMillis());
            logger.info("  - testOnBorrow: {}\n", poolConfig.getTestOnBorrow());

            return jedisPool;

        } catch (Exception e) {
            logger.error("初始化连接池失败", e);
            return null;
        }
    }
}
