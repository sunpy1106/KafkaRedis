package com.example.kafka;

import com.example.kafka.service.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 测试：Redis 服务运行，但网络层阻断连接
 *
 * 场景：使用 iptables 阻止到 Redis 的连接
 * 目的：验证 Redis 可用但无法连接时，是否触发同样的异常
 */
public class TestRedisRunningButBlocked {
    private static final Logger logger = LoggerFactory.getLogger(TestRedisRunningButBlocked.class);

    public static void main(String[] args) {
        logger.info("========================================");
        logger.info("测试：Redis 运行但网络阻断");
        logger.info("========================================");
        logger.info("");

        // Step 1: 确认 Redis 服务运行
        logger.info("Step 1: 确认 Redis 服务正在运行");
        try {
            Process process = Runtime.getRuntime().exec("docker ps --filter name=kafka-redis --format '{{.Status}}'");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String status = reader.readLine();
            logger.info("  Redis 容器状态: {}", status);

            if (status == null || !status.contains("Up")) {
                logger.error("Redis 服务未运行，请先启动");
                return;
            }
        } catch (Exception e) {
            logger.error("检查 Redis 状态失败", e);
            return;
        }

        // Step 2: 初始化连接池并测试
        logger.info("");
        logger.info("Step 2: 初始化连接池 (testOnBorrow=true)");
        RedisService redisService = new RedisService();

        try {
            boolean exists = redisService.isUuidExists("test-initial");
            logger.info("  初始连接测试成功: {}", exists);
        } catch (Exception e) {
            logger.error("  初始连接测试失败", e);
            return;
        }

        // Step 3: 等待连接稳定
        logger.info("");
        logger.info("Step 3: 等待2秒...");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Step 4: 使用 iptables 阻断到 Redis 的连接
        logger.info("");
        logger.info("Step 4: 使用 iptables 阻断到 Redis 的 TCP 连接");
        logger.info("  注意：此时 Redis 服务仍在运行！");
        logger.info("");

        try {
            // 获取 Redis 容器的 IP
            Process getIpProcess = Runtime.getRuntime().exec(
                "docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' kafka-redis");
            BufferedReader ipReader = new BufferedReader(new InputStreamReader(getIpProcess.getInputStream()));
            String redisIp = ipReader.readLine();
            logger.info("  Redis 容器 IP: {}", redisIp);

            if (redisIp == null || redisIp.trim().isEmpty()) {
                logger.error("无法获取 Redis IP，使用 localhost");
                redisIp = "127.0.0.1";
            }

            // 添加 iptables 规则阻断到 Redis 6379 端口的连接
            // 方案1: 阻断 OUTPUT 链（从本机发出的包）
            String[] iptablesCommands = {
                // 阻断到 Redis 的新连接（只阻断 SYN 包）
                "sudo iptables -A OUTPUT -p tcp --dport 6379 --syn -j DROP",
                // 或者阻断所有到 6379 的包
                // "sudo iptables -A OUTPUT -p tcp --dport 6379 -j REJECT --reject-with tcp-reset"
            };

            for (String cmd : iptablesCommands) {
                logger.info("  执行: {}", cmd);
                Process process = Runtime.getRuntime().exec(cmd);
                process.waitFor();

                BufferedReader cmdReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                String line;
                while ((line = cmdReader.readLine()) != null) {
                    logger.info("    {}", line);
                }
            }

            logger.info("  iptables 规则已添加");
            logger.info("");
            logger.info("  当前 iptables OUTPUT 链:");
            Process listProcess = Runtime.getRuntime().exec("sudo iptables -L OUTPUT -n -v");
            BufferedReader listReader = new BufferedReader(new InputStreamReader(listProcess.getInputStream()));
            String line;
            while ((line = listReader.readLine()) != null) {
                logger.info("    {}", line);
            }

        } catch (Exception e) {
            logger.error("设置 iptables 失败", e);
            logger.error("可能需要 sudo 权限");
            return;
        }

        // Step 5: 等待 iptables 生效
        logger.info("");
        logger.info("Step 5: 等待 iptables 规则生效...");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Step 6: 尝试访问 Redis
        logger.info("");
        logger.info("Step 6: 尝试访问 Redis（预期：连接被阻断）");
        logger.info("");
        logger.info("🎯 当前状态:");
        logger.info("   - Redis 服务: 运行中 ✅");
        logger.info("   - 网络层: 被 iptables 阻断 ❌");
        logger.info("   - 预期异常: Could not get a resource from the pool");
        logger.info("");

        try {
            boolean exists = redisService.isUuidExists("test-blocked");
            logger.info("❌ 意外成功: {}", exists);

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
                logger.info("✅✅✅ 成功触发目标异常！");
                logger.info("");
                logger.info("🎉 验证成功：Redis 运行但网络阻断也能触发此异常！");
                logger.info("");
                logger.info("说明:");
                logger.info("  - Redis 服务本身正常运行");
                logger.info("  - 但由于网络层阻断，无法建立 TCP 连接");
                logger.info("  - 连接创建失败 → 触发异常");
                logger.info("");
                logger.info("生产场景:");
                logger.info("  - 防火墙规则变更");
                logger.info("  - 安全组配置错误");
                logger.info("  - 网络设备故障");
            } else if (cause.getMessage() != null &&
                       (cause.getMessage().contains("timeout") ||
                        cause.getMessage().contains("Connection timed out"))) {
                logger.info("⚠️  触发了超时异常");
                logger.info("可能需要调整连接超时时间");
            } else {
                logger.error("❓ 触发了其他异常");
            }

            logger.error("");
            logger.error("完整堆栈:");
            e.printStackTrace();
            logger.error("==============================================");

        } finally {
            // 清理：删除 iptables 规则
            logger.info("");
            logger.info("清理: 删除 iptables 规则...");
            try {
                String[] cleanupCommands = {
                    "sudo iptables -D OUTPUT -p tcp --dport 6379 --syn -j DROP",
                };

                for (String cmd : cleanupCommands) {
                    logger.info("  执行: {}", cmd);
                    Process process = Runtime.getRuntime().exec(cmd);
                    process.waitFor();
                }
                logger.info("  iptables 规则已删除");

                // 验证规则已删除
                Process listProcess = Runtime.getRuntime().exec("sudo iptables -L OUTPUT -n -v");
                BufferedReader listReader = new BufferedReader(new InputStreamReader(listProcess.getInputStream()));
                logger.info("  当前 iptables OUTPUT 链:");
                String line;
                while ((line = listReader.readLine()) != null) {
                    logger.info("    {}", line);
                }

            } catch (Exception e) {
                logger.error("删除 iptables 规则失败", e);
                logger.error("请手动执行: sudo iptables -D OUTPUT -p tcp --dport 6379 --syn -j DROP");
            }

            // 验证连接已恢复
            logger.info("");
            logger.info("验证: 测试 Redis 连接是否恢复...");
            try {
                Thread.sleep(1000);
                boolean exists = redisService.isUuidExists("test-recovery");
                logger.info("  连接恢复成功: {}", exists);
            } catch (Exception e) {
                logger.error("  连接仍然失败", e);
            }

            redisService.close();
        }

        logger.info("");
        logger.info("========================================");
        logger.info("测试总结:");
        logger.info("");
        logger.info("结论:");
        logger.info("  即使 Redis 服务正常运行，");
        logger.info("  只要网络层无法建立连接，");
        logger.info("  就会触发相同的异常。");
        logger.info("");
        logger.info("生产环境排查建议:");
        logger.info("  1. 检查 Redis 服务状态");
        logger.info("  2. 检查网络连通性 (telnet/nc)");
        logger.info("  3. 检查防火墙/安全组规则");
        logger.info("  4. 检查 DNS 解析");
        logger.info("========================================");
    }
}
