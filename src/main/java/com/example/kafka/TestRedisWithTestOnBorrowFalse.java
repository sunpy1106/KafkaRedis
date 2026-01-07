package com.example.kafka;

import com.example.kafka.service.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 对比测试: testOnBorrow=false 时的异常行为
 *
 * 目的：演示为什么 testOnBorrow=false 无法触发目标异常
 */
public class TestRedisWithTestOnBorrowFalse {
    private static final Logger logger = LoggerFactory.getLogger(TestRedisWithTestOnBorrowFalse.class);

    public static void main(String[] args) {
        logger.info("========================================");
        logger.info("对比测试: testOnBorrow=false");
        logger.info("========================================");
        logger.info("");
        logger.info("⚠️  注意：请先修改 application.properties:");
        logger.info("   redis.pool.testOnBorrow=false");
        logger.info("");
        logger.info("然后重新编译运行此测试");
        logger.info("========================================");
        logger.info("");

        // Step 1: 初始化连接池
        logger.info("Step 1: 初始化Redis连接池 (testOnBorrow=false)");
        RedisService redisService = new RedisService();

        // Step 2: 首次访问，创建连接
        logger.info("Step 2: 首次访问Redis，创建初始连接");
        try {
            boolean exists = redisService.isUuidExists("test-uuid-init");
            logger.info("初始连接创建成功，检查结果: {}", exists);
        } catch (Exception e) {
            logger.error("初始连接创建失败", e);
            return;
        }

        // Step 3: 等待连接稳定
        logger.info("Step 3: 等待2秒...");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Step 4: 停止Redis
        logger.info("Step 4: 停止Redis服务");
        try {
            Process process = Runtime.getRuntime().exec("docker stop kafka-redis");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                logger.info("Docker output: {}", line);
            }
            process.waitFor();
            logger.info("Redis已停止");
        } catch (Exception e) {
            logger.error("停止Redis失败", e);
            return;
        }

        // Step 5: 等待Redis完全停止
        logger.info("Step 5: 等待Redis完全停止...");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Step 6: 再次访问Redis（关键时刻）
        logger.info("Step 6: 再次访问Redis（testOnBorrow=false，不验证）");
        logger.info("");
        logger.info("🎯 预期行为:");
        logger.info("   1. borrowObject() 从池中取出旧连接");
        logger.info("   2. 跳过验证（testOnBorrow=false）");
        logger.info("   3. 直接返回连接给应用");
        logger.info("   4. 应用使用连接时才发现问题");
        logger.info("   5. 抛出异常: JedisConnectionException: Unexpected end of stream");
        logger.info("");

        try {
            boolean exists = redisService.isUuidExists("test-uuid-after-stop");
            logger.info("意外成功: {}", exists);

        } catch (Exception e) {
            logger.error("================== 捕获到异常 ==================");
            logger.error("异常类型: {}", e.getClass().getName());
            logger.error("异常消息: {}", e.getMessage());
            logger.error("");

            // 提取根本原因
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            logger.error("根本原因: {}", cause.getClass().getName());
            logger.error("根本原因消息: {}", cause.getMessage());
            logger.error("");

            // 判断是否是目标异常
            if (e.getMessage() != null &&
                e.getMessage().contains("Could not get a resource from the pool")) {
                logger.info("✅ 成功触发目标异常！");
            } else if (e.getMessage() != null &&
                       e.getMessage().contains("Unexpected end of stream")) {
                logger.warn("⚠️  触发了不同的异常：Unexpected end of stream");
                logger.warn("这是因为 testOnBorrow=false，连接在使用时才检测到失效");
            } else {
                logger.error("❌ 触发了其他异常");
            }

            logger.error("完整堆栈:");
            e.printStackTrace();
            logger.error("==============================================");
        } finally {
            // 恢复Redis
            logger.info("");
            logger.info("恢复Redis服务...");
            try {
                Process process = Runtime.getRuntime().exec("docker start kafka-redis");
                process.waitFor();
                logger.info("Redis已恢复");
            } catch (Exception e) {
                logger.error("恢复Redis失败", e);
            }

            redisService.close();
        }

        logger.info("");
        logger.info("========================================");
        logger.info("测试结论:");
        logger.info("testOnBorrow=false 时:");
        logger.info("  - 不会在获取连接时验证");
        logger.info("  - 不会触发创建新连接");
        logger.info("  - 异常在使用连接时抛出");
        logger.info("  - 异常消息不同！");
        logger.info("========================================");
    }
}
