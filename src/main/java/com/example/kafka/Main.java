package com.example.kafka;

import com.example.kafka.model.Message;
import com.example.kafka.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 测试主类
 * 演示消息去重发送功能
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        MessageService messageService = new MessageService();

        try {
            // 测试1: 发送第一条消息
            logger.info("========== 测试1: 发送第一条消息 ==========");
            Message message1 = new Message("这是第一条测试消息");
            boolean result1 = messageService.sendMessage(message1);
            logger.info("发送结果: {}", result1 ? "成功" : "失败");

            // 等待一下，让Kafka和Redis操作完成
            Thread.sleep(1000);

            // 测试2: 重复发送相同UUID的消息（应该被跳过）
            logger.info("\n========== 测试2: 重复发送相同UUID的消息 ==========");
            boolean result2 = messageService.sendMessage(message1);
            logger.info("发送结果: {}", result2 ? "成功" : "失败（消息已存在）");

            // 测试3: 发送第二条新消息
            logger.info("\n========== 测试3: 发送第二条新消息 ==========");
            Message message2 = new Message("这是第二条测试消息");
            boolean result3 = messageService.sendMessage(message2);
            logger.info("发送结果: {}", result3 ? "成功" : "失败");

            Thread.sleep(1000);

            // 测试4: 检查消息是否已发送
            logger.info("\n========== 测试4: 检查消息发送状态 ==========");
            logger.info("消息1 (UUID: {}) 是否已发送: {}",
                    message1.getUuid(),
                    messageService.isMessageSent(message1.getUuid()));
            logger.info("消息2 (UUID: {}) 是否已发送: {}",
                    message2.getUuid(),
                    messageService.isMessageSent(message2.getUuid()));

            // 测试5: 批量发送消息
            logger.info("\n========== 测试5: 批量发送消息 ==========");
            for (int i = 1; i <= 5; i++) {
                Message message = new Message("批量消息 " + i);
                boolean result = messageService.sendMessage(message);
                logger.info("批量消息 {} 发送结果: {}", i, result ? "成功" : "失败");
                Thread.sleep(200);
            }

            logger.info("\n========== 所有测试完成 ==========");

        } catch (InterruptedException e) {
            logger.error("测试过程中被中断", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("测试过程中发生异常", e);
        } finally {
            // 关闭服务
            messageService.close();
            logger.info("MessageService已关闭");
        }
    }
}
